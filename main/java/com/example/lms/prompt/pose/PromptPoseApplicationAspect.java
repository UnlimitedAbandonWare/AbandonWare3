package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Map;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PromptPoseApplicationAspect {

    private final PromptPoseProperties props;
    private final PromptPoseApplicationJudge judge;

    public PromptPoseApplicationAspect(PromptPoseProperties props, PromptPoseApplicationJudge judge) {
        this.props = props == null ? new PromptPoseProperties() : props;
        this.judge = judge;
    }

    @Around("execution(* com.example.lms.service.ChatService.continueChat(..)) || execution(* com.example.lms.service.ChatService.ask(..))")
    public Object aroundChatEntry(ProceedingJoinPoint pjp) throws Throwable {
        if (!props.isEnabled() || props.getApplication() == null || !props.getApplication().isEnabled() || judge == null) {
            return pjp.proceed();
        }

        GuardContext existing = GuardContextHolder.get();
        GuardContext ctx = existing == null ? GuardContext.defaultContext() : existing;
        boolean createdContext = existing == null;
        if (createdContext) {
            GuardContextHolder.set(ctx);
        }

        try {
            applyDecision(ctx, extractMessage(pjp.getArgs()));
            return pjp.proceed();
        } finally {
            if (createdContext) {
                GuardContextHolder.clear();
            }
        }
    }

    private void applyDecision(GuardContext ctx, String rawMessage) {
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(rawMessage, props);
        if (input.blocked()) {
            PromptPoseTrace.writeApplicationDecision(PromptPoseApplicationDecision.disabled(input.skipReason()), input);
            return;
        }
        try {
            PromptPoseApplicationDecision decision = judge.decide(input, requestedMaxQueries(ctx));
            if (decision == null || !decision.enabled()) {
                PromptPoseTrace.writeApplicationDecision(decision, input);
                return;
            }
            applyGuardOverrides(ctx, decision);
            PromptPoseTrace.writePlan(decision.toPlan(draftRoute()), input);
            PromptPoseTrace.writeApplicationDecision(decision, input);
        } catch (Throwable t) {
            TraceStore.put("promptPose.application.failureClass", "prompt_pose_application_failed");
            PromptPoseTrace.writeApplicationDecision(PromptPoseApplicationDecision.disabled("application_exception"), input);
        }
    }

    private static void applyGuardOverrides(GuardContext ctx, PromptPoseApplicationDecision decision) {
        if (ctx == null || decision == null || !decision.enabled()) {
            return;
        }
        ctx.putPlanOverride("search.zero100.enabled", true);
        putLaneWeights(ctx, decision.laneWeights());
        putRatios(ctx, "CallBudgetRatio", decision.callBudgetRatios());
        putRatios(ctx, "TimeboxRatio", decision.timeboxRatios());
        ctx.putPlanOverride("search.zero100.queryBurstMax", decision.queryBurstMax());
        ctx.putPlanOverride("search.zero100.riskConsensus.enabled", true);
        ctx.putPlanOverride("search.zero100.riskConsensus.minLaneCoverage", decision.minLaneCoverage());
        ctx.putPlanOverride("search.zero100.riskConsensus.riskPenaltyLambda", decision.riskPenaltyLambda());
        ctx.putPlanOverride("expand.selfAsk.count", decision.selfAskCount());
        ctx.putPlanOverride("selfask.enabled", decision.selfAskCount() > 0);
        ctx.putPlanOverride("selfask.planOverride.reason", "promptPose.application");
        ctx.putPlanOverride("llm.answer.temperature", decision.answerTemperature());
        ctx.putPlanOverride("llm.selfAsk.temperature", decision.selfAskTemperature());
        ctx.putPlanOverride("promptPose.application.intentSlot", decision.intentSlot());
        ctx.putPlanOverride("promptPose.application.feedbackTile", decision.feedbackTile());
        ctx.putPlanOverride("promptPose.application.decisionHash12", decision.decisionHash12());
        if (decision.minCitations() > 0) {
            Integer existing = ctx.getMinCitations();
            ctx.setMinCitations(existing == null ? decision.minCitations() : Math.max(existing, decision.minCitations()));
        }
        if ("overdrive_hint".equals(decision.compressionMode())) {
            ctx.setCompressionMode(true);
            ctx.putPlanOverride("overdrive.enabled", true);
            ctx.putPlanOverride("overdrive.reason", "promptPose.application");
        }
    }

    private static void putLaneWeights(GuardContext ctx, Map<String, Double> weights) {
        if (weights == null || weights.isEmpty()) {
            return;
        }
        put(ctx, "search.zero100.strictWeight", weights.get("BQ"));
        put(ctx, "search.zero100.relaxedWeight", weights.get("ER"));
        put(ctx, "search.zero100.exploreWeight", weights.get("RC"));
    }

    private static void putRatios(GuardContext ctx, String suffix, Map<String, Double> ratios) {
        if (ratios == null || ratios.isEmpty()) {
            return;
        }
        put(ctx, "search.zero100.strict" + suffix, ratios.get("BQ"));
        put(ctx, "search.zero100.relaxed" + suffix, ratios.get("ER"));
        put(ctx, "search.zero100.explore" + suffix, ratios.get("RC"));
    }

    private static void put(GuardContext ctx, String key, Object value) {
        if (ctx != null && key != null && value != null) {
            ctx.putPlanOverride(key, value);
        }
    }

    private int requestedMaxQueries(GuardContext ctx) {
        int policyMax = props.getPolicy() == null ? 18 : props.getPolicy().getMaxQueryburstCount();
        if (ctx == null) {
            return policyMax;
        }
        return ctx.planInt("search.zero100.queryBurstMax", policyMax);
    }

    private String draftRoute() {
        String route = props.getDraft() == null ? null : props.getDraft().getModel();
        return route == null || route.isBlank() ? "llmrouter.light" : route.trim();
    }

    private static String extractMessage(Object[] args) {
        if (args == null) {
            return "";
        }
        for (Object arg : args) {
            if (arg instanceof ChatRequestDto dto) {
                String msg = dto.getMessage();
                return msg == null ? "" : msg;
            }
            if (arg instanceof String s) {
                return s;
            }
        }
        return "";
    }
}
