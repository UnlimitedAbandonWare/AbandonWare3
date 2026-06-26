package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanHintApplierTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void braveExpandSelfAskCountActivatesRequestScopedSelfAskMetadata() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        PlanHints hints = applier.load("brave");
        OrchestrationHints orchestrationHints = OrchestrationHints.defaults();
        orchestrationHints.setEnableSelfAsk(false);
        Map<String, Object> meta = new HashMap<>();

        applier.applyToHintsAndMeta(hints, orchestrationHints, meta);

        assertEquals(3, meta.get("expand.selfAsk.count"));
        assertEquals("true", String.valueOf(meta.get("selfask.enabled")));
        assertEquals("true", String.valueOf(meta.get("enableSelfAsk")));
        assertEquals("expand.selfAsk.count", meta.get("selfask.planOverride.reason"));
        assertTrue(orchestrationHints.isEnableSelfAsk());
    }

    @Test
    void passthroughMetadataCarriesZero100SearchKnobs() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        PlanHints hints = applier.load("zero100.v1");
        OrchestrationHints orchestrationHints = OrchestrationHints.defaults();
        Map<String, Object> meta = new HashMap<>();

        applier.applyToHintsAndMeta(hints, orchestrationHints, meta);

        assertEquals(Boolean.TRUE, meta.get("search.zero100.enabled"));
        assertEquals(400, meta.get("search.zero100.sliceMs"));
        assertEquals(9, meta.get("search.zero100.queryBurstMax"));
    }

    @Test
    void zero100AliasDoesNotBecomeZeroBreak() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        PlanHints hints = applier.load("zero100");
        OrchestrationHints orchestrationHints = OrchestrationHints.defaults();
        Map<String, Object> meta = new HashMap<>();

        applier.applyToHintsAndMeta(hints, orchestrationHints, meta);

        assertEquals("zero100.v1", hints.planId());
        assertEquals(Boolean.TRUE, meta.get("search.zero100.enabled"));
    }

    @Test
    void malformedSensitivePlanFailsClosedToSafe() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("bad_token.v1", """
                id: bad_token.v1
                params:
                  owner-token: ${OWNER_TOKEN}
                """));

        PlanHints hints = applier.load("bad_token.v1");

        assertEquals("safe.v1", hints.planId());
        String invalid = String.valueOf(TraceStore.get("plan.schema.invalid"));
        assertTrue(invalid.contains("forbidden_sensitive_key:params.owner-token"));
        assertFalse(invalid.contains("OWNER_TOKEN"));
        assertEquals("bad_token.v1->safe.v1", TraceStore.get("plan.schema.fallback"));
    }

    @Test
    void camelCaseSecretPlanKeyFailsClosedWithoutValueLeak() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("client_secret.v1", """
                id: client_secret.v1
                params:
                  clientSecret: do-not-log-this-value
                """));

        PlanHints hints = applier.load("client_secret.v1");

        assertEquals("safe.v1", hints.planId());
        String invalid = String.valueOf(TraceStore.get("plan.schema.invalid"));
        assertTrue(invalid.contains("forbidden_sensitive_key:params.clientSecret"));
        assertFalse(invalid.contains("do-not-log-this-value"));
    }

    @Test
    void sensitivePlanIdIsRedactedFromInvalidPlanTrace() {
        String fakeKey = "sk-" + "test-1234567890abcdefghijklmnop";
        String rawPlanId = fakeKey + ".v1";
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor(rawPlanId,
                "id: " + rawPlanId + "\n"
                        + "params:\n"
                        + "  owner-token: ${OWNER_TOKEN}\n"));

        PlanHints hints = applier.load(rawPlanId);

        assertEquals("safe.v1", hints.planId());
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(fakeKey));
        assertFalse(trace.contains(rawPlanId));
        assertTrue(trace.contains("forbidden_sensitive_key:params.owner-token"));
    }

    @Test
    void pathLikePlanIdFallsBackWithoutTraceLeak() {
        String rawPlanId = "../private customer plan.v1";
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("safe.v1", """
                id: safe.v1
                params:
                  retrieval:
                    k:
                      web: 2
                """));

        PlanHints hints = applier.load(rawPlanId);

        assertEquals("safe.v1", hints.planId());
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("../"));
        assertFalse(trace.contains("private customer plan"));
        assertFalse(trace.contains(rawPlanId));
    }

    @Test
    void nonCredentialWordsContainingSecretDoNotFalseRedPlanDsl() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("neutral_secretariat.v1", """
                id: neutral_secretariat.v1
                params:
                  secretariatNote: public routing note
                """));

        PlanHints hints = applier.load("neutral_secretariat.v1");

        assertEquals("neutral_secretariat.v1", hints.planId());
        assertFalse(String.valueOf(TraceStore.getAll()).contains("forbidden_sensitive_key"));
    }

    @Test
    void malformedPlanYamlRecordsStableLoadErrorWithoutParserClassName() {
        PlanHintApplier applier = new PlanHintApplier(resourceLoaderFor("broken_parse.v1", "id: ["));

        PlanHints hints = applier.load("broken_parse.v1");

        assertEquals("broken_parse.v1", hints.planId());
        String trace = String.valueOf(TraceStore.get("plan.load.error"));
        assertTrue(trace.contains("broken_parse.v1:load_failed"));
        assertFalse(trace.contains("JsonParseException"));
        assertFalse(trace.contains("Exception"));
    }

    @Test
    void nonFiniteNumericHintsAreIgnoredInsteadOfOverflowing() throws Exception {
        Method asInt = PlanHintApplier.class.getDeclaredMethod("asInt", Object.class);
        Method asLong = PlanHintApplier.class.getDeclaredMethod("asLong", Object.class);
        asInt.setAccessible(true);
        asLong.setAccessible(true);

        assertEquals(null, asInt.invoke(null, Double.POSITIVE_INFINITY));
        assertEquals(null, asInt.invoke(null, Double.NaN));
        assertEquals(null, asLong.invoke(null, Double.NEGATIVE_INFINITY));
        assertEquals(null, asLong.invoke(null, Double.NaN));
    }

    @Test
    void guardContextReceivesExplicitSelfAskPlanOverride() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());
        PlanHints hints = applier.load("brave");
        GuardContext guardContext = new GuardContext();

        applier.applyToGuardContext(hints, guardContext);

        assertEquals(3, guardContext.getPlanOverride("expand.selfAsk.count"));
        assertEquals(Boolean.TRUE, guardContext.getPlanOverride("selfask.enabled"));
        assertEquals("expand.selfAsk.count", guardContext.getPlanOverride("selfask.planOverride.reason"));
    }

    @Test
    void documentEvidencePlanLoadsAttachmentFocusedRagKnobs() {
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader());

        PlanHints hints = applier.load("document_evidence.v1");

        assertEquals(3, hints.minCitations());
        assertEquals(Boolean.TRUE, hints.onnxEnabled());
        assertEquals(Boolean.TRUE, hints.overdriveEnabled());
        assertEquals(12, hints.vecTopK());
        assertEquals(24, hints.rerankCeTopK());
        assertEquals(8, hints.rerankTopK());
    }

    @Test
    void rerankBackendTraceUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/plan/PlanHintApplier.java"));

        assertFalse(source.contains("TraceStore.put(\"plan.rerankBackend\", ph.rerankBackend());"));
        assertTrue(source.contains("TraceStore.put(\"plan.rerankBackendHash\", SafeRedactor.hashValue(ph.rerankBackend()));"));
        assertTrue(source.contains("TraceStore.put(\"plan.rerankBackendLength\", ph.rerankBackend() == null ? 0 : ph.rerankBackend().length());"));
        assertFalse(source.contains("catch (Exception ignored) { return null; }"));
        assertFalse(source.contains("catch (NumberFormatException ignored) { return null; }"));
        assertTrue(source.contains("traceParseSkipped(\"int\", ignored);"));
        assertTrue(source.contains("traceParseSkipped(\"long\", ignored);"));
        assertTrue(source.contains("PlanHintApplier parse skipped stage="));
        assertTrue(source.contains("private static String errorType(Throwable error)"));
        assertTrue(source.contains("error instanceof NumberFormatException"));
        assertTrue(source.contains("return \"invalid_number\";"));
        assertTrue(source.contains("+ errorType(error)"));
        assertFalse(source.contains("+ SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), \"unknown\")"));
    }

    private static ResourceLoader resourceLoaderFor(String planId, String yaml) {
        DefaultResourceLoader delegate = new DefaultResourceLoader();
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                if (location.contains(planId)) {
                    byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
                    return new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return planId + ".yaml";
                        }
                    };
                }
                return delegate.getResource(location);
            }

            @Override
            public ClassLoader getClassLoader() {
                return delegate.getClassLoader();
            }
        };
    }
}
