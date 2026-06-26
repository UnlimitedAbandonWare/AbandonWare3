package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.Zero100EngineProperties;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.SourceLocation;
import org.aspectj.runtime.internal.AroundClosure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Zero100SessionAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void zero100SessionTraceStoresMpIntentDiagnosticsWithoutRawQuery() throws Throwable {
        Zero100EngineProperties props = new Zero100EngineProperties();
        Zero100SessionAspect aspect = new Zero100SessionAspect(
                props,
                new Zero100SessionRegistry(props),
                new MockEnvironment());
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("search.zero100.enabled", true);
        String rawQuery = "raw zero100 query should never be written to TraceStore";
        ctx.setUserQuery(rawQuery);
        GuardContextHolder.set(ctx);

        Object out = aspect.aroundChatEntry(new FakePjp("ok", rawQuery));

        assertEquals("ok", out);
        assertNull(TraceStore.get("zero100.mpIntent"));
        assertEquals(Boolean.TRUE, TraceStore.get("zero100.mpIntent.present"));
        assertEquals("33-128", TraceStore.get("zero100.mpIntent.lengthBucket"));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("zero100.mpIntent.hash12"));
        assertFalse(TraceStore.getAll().containsValue(rawQuery));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
        assertTrue(Boolean.TRUE.equals(TraceStore.get("zero100.enabled")));
        assertTrue(TraceStore.get("zero100.branch.callRatios") instanceof java.util.Map<?, ?>);
        assertTrue(TraceStore.get("zero100.branch.timeboxMs") instanceof java.util.Map<?, ?>);
        assertEquals(Boolean.TRUE, TraceStore.get("zero100.riskConsensus.enabled"));
        assertEquals(2, TraceStore.get("zero100.riskConsensus.minLaneCoverage"));
        assertEquals(0.45d, TraceStore.get("zero100.riskConsensus.riskPenaltyLambda"));
    }

    @Test
    void zero100SessionTraceStoresSessionHashOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java"));

        assertFalse(source.contains("TraceStore.put(\"zero100.sessionId\", sid);"));
        assertTrue(source.contains("TraceStore.put(\"zero100.sessionId\", SafeRedactor.hashValue(sid));"));
        assertFalse(source.contains("TraceStore.put(\"zero100.scheduler.failureClass\", t.getClass().getSimpleName())"));
        assertTrue(source.contains("TraceStore.put(\"zero100.scheduler.failureClass\", \"zero100_scheduler_failed\")"));
    }

    @Test
    void zero100SessionFallbackCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.guardContextLookup\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.sessionIdLookup\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.enabledPlanOverride\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.planIdLookup\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.traceLongParse\", ignored);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"zero100.mapDoubleParse\", ignored);"));
    }

    @Test
    void zero100NumericTraceHelpersIgnoreNonFiniteNumbers() throws Exception {
        TraceStore.put("qtx.softCooldown.remainingMs", Double.POSITIVE_INFINITY);
        Long traceLong = ReflectionTestUtils.invokeMethod(Zero100SessionAspect.class, "traceLong",
                "qtx.softCooldown.remainingMs", 37L);
        assertEquals(37L, traceLong);

        Double mapDouble = ReflectionTestUtils.invokeMethod(Zero100SessionAspect.class, "mapDouble",
                Map.of("score", Double.NaN), "score", 0.42d);
        assertEquals(0.42d, mapDouble);
    }

    @Test
    void zero100WebTimeboxTraceStoresRequestHashOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/Zero100WebTimeboxAspect.java"));

        assertFalse(source.contains("TraceStore.put(\"zero100.webTimebox.rid\", LogCorrelation.requestId())"));
        assertTrue(source.contains(
                "TraceStore.put(\"zero100.webTimebox.rid\", SafeRedactor.hashValue(LogCorrelation.requestId()))"));
    }

    @Test
    void zero100WebTimeboxTreatsSerpApiAndTavilyRateLimitsAsAlreadyLimited() {
        Zero100EngineProperties props = new Zero100EngineProperties();
        props.setWebCallTimeboxMs(2500L);
        props.setWebCallTimeboxMsWhenRateLimited(650L);
        Zero100WebTimeboxAspect aspect = new Zero100WebTimeboxAspect(
                props,
                new Zero100SessionRegistry(props),
                null);

        TraceStore.put("web.serpapi.skipped.reason", "rate_limit");
        Long serpApiTimebox = ReflectionTestUtils.invokeMethod(aspect, "resolveTimeboxMs");
        TraceStore.clear();
        TraceStore.put("web.tavily.skipped.reason", "cooldown");
        Long tavilyTimebox = ReflectionTestUtils.invokeMethod(aspect, "resolveTimeboxMs");

        assertEquals(650L, serpApiTimebox);
        assertEquals(650L, tavilyTimebox);
    }

    @Test
    void zero100WebTimeboxDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/Zero100WebTimeboxAspect.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Zero100 web timebox needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void zero100SessionLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
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
