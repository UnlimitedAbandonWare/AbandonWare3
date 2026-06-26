package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptPoseApplicationAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void chatEntryAppliesZero100SelfAskTemperatureAndCitationOverridesBeforeProceeding() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        PromptPoseApplicationAspect aspect = new PromptPoseApplicationAspect(
                props,
                new PromptPoseApplicationJudge(props, null));
        GuardContext ctx = new GuardContext();
        GuardContextHolder.set(ctx);
        String raw = "PromptPose 응용 탐색을 과감하게 넓히고 근거 압축도 같이 조율해줘";

        Object out = aspect.aroundChatEntry(new FakePjp("ok", raw));

        assertEquals("ok", out);
        assertTrue(ctx.planBool("search.zero100.enabled", false));
        assertTrue(ctx.planInt("search.zero100.queryBurstMax", 0) >= 12);
        assertEquals(3, ctx.planInt("expand.selfAsk.count", 0));
        assertTrue(ctx.planDouble("llm.answer.temperature", 0.0d) > 0.20d);
        assertTrue(ctx.getMinCitations() >= 2);
        assertTrue(ctx.planBool("overdrive.enabled", false));
        assertTrue(ctx.isCompressionMode());
        assertEquals(Boolean.TRUE, TraceStore.get(PromptPoseTrace.APPLICATION_APPLIED));
        assertEquals("explore", TraceStore.get(PromptPoseTrace.APPLICATION_INTENT_SLOT));
        assertFalse(TraceStore.getAll().toString().contains(raw));
    }

    @Test
    void applicationJudgeFailureUsesStableTraceLabelAndStillProceeds() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        PromptPoseApplicationAspect aspect = new PromptPoseApplicationAspect(props, new ThrowingJudge(props));

        Object out = aspect.aroundChatEntry(new FakePjp("ok", "PromptPose application failure path"));

        assertEquals("ok", out);
        assertEquals("prompt_pose_application_failed", TraceStore.get("promptPose.application.failureClass"));
        assertFalse(String.valueOf(TraceStore.get("promptPose.application.failureClass"))
                .contains("IllegalStateException"));
    }

    private static final class ThrowingJudge extends PromptPoseApplicationJudge {
        private ThrowingJudge(PromptPoseProperties props) {
            super(props, null);
        }

        @Override
        public PromptPoseApplicationDecision decide(PromptPoseInputSanitizer.SanitizedInput input,
                                                   int requestedMaxQueries) {
            throw new IllegalStateException("judge failed ownerToken=secret-prompt-pose");
        }
    }

    private static final class FakePjp implements ProceedingJoinPoint {
        private final Object result;
        private final Object[] args;

        private FakePjp(Object result, Object... args) {
            this.result = result;
            this.args = args;
        }

        @Override
        public Object proceed() {
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
            return result;
        }

        @Override
        public void set$AroundClosure(AroundClosure arc) {
        }

        @Override
        public Object getThis() {
            return this;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public Signature getSignature() {
            return null;
        }

        @Override
        public SourceLocation getSourceLocation() {
            return null;
        }

        @Override
        public String getKind() {
            return "method-execution";
        }

        @Override
        public JoinPoint.StaticPart getStaticPart() {
            return null;
        }

        @Override
        public String toShortString() {
            return "FakePjp";
        }

        @Override
        public String toLongString() {
            return "FakePjp";
        }
    }
}
