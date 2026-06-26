package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.service.trace.TraceHtmlBuilder;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSnapshotRedactionTest {

    @AfterEach
    void clearMdc() {
        TraceStore.clear();
        MDC.clear();
    }

    @Test
    void firstSnapshotForTraceBypassesMinIntervalBudget() {
        TraceSnapshotStore store = enabledStore();
        ReflectionTestUtils.setField(store, "minIntervalMs", 200L);
        ReflectionTestUtils.setField(store, "maxPerTrace", 8);
        TraceStore.put("trace.id", "probe-trace-001");
        TraceStore.put("traceSnapshot.probe.search.restoredSelectedTrace", true);

        String id = store.captureCurrent("probe_search", "POST", "/api/probe/search", 200, null);

        assertNotNull(id);
        TraceStore.clear();
    }

    @Test
    void consoleBreadcrumbUsesHashOnlyIdentifiersAndPathSummary() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/TraceSnapshotStore.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("id={} sid={} traceId={} reqId={}"));
        assertFalse(source.contains("safe(path),\n                        (err == null ?"));
        assertFalse(source.contains("t.toString()"));
        assertTrue(source.contains("sidHash={} traceHash={} reqHash={}"));
        assertTrue(source.contains("pathHash={} pathLength={}"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(source.contains("[TRACE_SNAPSHOT] capture failed errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }

    @Test
    void traceSnapshotStoreDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/TraceSnapshotStore.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "trace snapshot fail-soft paths need stage breadcrumbs instead of exact empty catch bodies");
        assertFalse(source.contains("catch (Exception ignore) {\n            return 0;\n        }"));
        assertTrue(source.contains("catch (NumberFormatException ignore) {"));
        assertTrue(source.contains("TraceStore.put(\"trace.snapshot.suppressed.intValue\", true)"));
        assertTrue(source.contains("return 0;"));
    }

    @Test
    void numericFallbacksUseStableInvalidNumberLabels() throws Exception {
        TraceStore.clear();
        TraceSnapshotStore store = enabledStore();
        Content content = Content.from(TextSegment.from("doc", Metadata.from(Map.of("score", "not-a-score"))));
        Method score = TraceSnapshotStore.class.getDeclaredMethod("tryExtractScore", Content.class);
        Method intValue = TraceSnapshotStore.class.getDeclaredMethod("intValue", Object.class);
        score.setAccessible(true);
        intValue.setAccessible(true);

        assertEquals(null, score.invoke(store, content));
        assertEquals(0, intValue.invoke(null, "not-an-int"));
        assertEquals("invalid_number", TraceStore.get("trace.snapshot.suppressed.scoreParse.errorType"));
        assertEquals("invalid_number", TraceStore.get("trace.snapshot.suppressed.intValue.errorType"));

        TraceStore.clear();
        assertEquals(0, intValue.invoke(null, Double.POSITIVE_INFINITY));
        assertEquals("invalid_number", TraceStore.get("trace.snapshot.suppressed.intValue.errorType"));
    }

    @Test
    void snapshotSummaryTraceAndHtmlDoNotRetainRawInputs() {
        TraceSnapshotStore store = enabledStore();
        MDC.put("sid", "snapshot-session-raw");
        MDC.put("traceId", "snapshot-trace-raw");
        MDC.put("x-request-id", "snapshot-request-raw");
        MDC.put("user-agent", "raw browser agent");

        String rawQuery = "private trace query";
        String rawSnippet = "<script>alert('trace')</script> raw snippet";
        Map<String, Object> trace = Map.of(
                "http.query", "q=" + rawQuery,
                "http.ua", "raw browser agent",
                "web.effectiveQuery", rawQuery,
                "web.topK", List.of(Content.from(TextSegment.from(rawSnippet, Metadata.from(Map.of(
                        "snippet", rawSnippet,
                        "url", "https://example.com/path?q=" + rawQuery))))));

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                500,
                new IllegalStateException("boom " + rawQuery),
                trace,
                null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String dump = snapshot.toString() + "\n" + snapshot.html();

        assertTrue(snapshot.sid().startsWith("hash:"));
        assertTrue(snapshot.traceId().startsWith("hash:"));
        assertFalse(dump.contains(rawQuery));
        assertFalse(dump.contains(rawSnippet));
        assertFalse(dump.contains("<script>"));
        assertFalse(dump.contains("snapshot-session-raw"));
        assertFalse(dump.contains("raw browser agent"));
        assertTrue(dump.contains("hash12"));
    }

    @Test
    void snapshotTraceKeysAreLabelsNotRawText() {
        TraceSnapshotStore store = enabledStore();
        String fakeSecret = "sk-" + "12345678901234567890";
        String rawKey = "private trace key " + fakeSecret;
        String nestedRawKey = "nested ownerToken=secret";

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                500,
                null,
                Map.of(
                        rawKey, "provider-disabled",
                        "web.trace.meta", Map.of(nestedRawKey, "provider-disabled")),
                null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String dump = snapshot.toString() + "\n" + snapshot.html();

        assertTrue(snapshot.trace().keySet().stream().anyMatch(k -> k.startsWith("hash:")), dump);
        assertFalse(dump.contains(rawKey));
        assertFalse(dump.contains(nestedRawKey));
        assertFalse(dump.contains(fakeSecret));
        assertFalse(dump.contains("ownerToken"));
    }

    @Test
    void topKFallbackObjectsAreDiagnosticSummaries() {
        TraceSnapshotStore store = enabledStore();
        String rawPayload = "private topk object payload must not remain in snapshot";
        Object objectWithRawToString = new Object() {
            @Override
            public String toString() {
                return rawPayload;
            }
        };

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                500,
                null,
                Map.of("web.topK", List.of(objectWithRawToString)),
                null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String dump = snapshot.toString() + "\n" + snapshot.html();

        assertFalse(dump.contains(rawPayload));
        assertTrue(dump.contains("hash12"));
    }

    @Test
    void htmlOverrideIsTreatedAsUntrustedUnlessMarkedSanitized() {
        TraceSnapshotStore store = enabledStore();
        String rawPayload = "private override html must not remain in snapshot";

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                500,
                null,
                Map.of("trace.marker", "html-override-redaction"),
                "<div>" + rawPayload + "</div>");

        assertNotNull(id);
        String html = store.get(id).orElseThrow().html();

        assertFalse(html.contains(rawPayload));
        assertTrue(html.contains("hash12"));
    }

    @Test
    void forgedSanitizedMarkerDoesNotBypassHtmlOverrideRedaction() {
        TraceSnapshotStore store = enabledStore();
        String rawPayload = "private forged marker html must not remain in snapshot";

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                500,
                null,
                Map.of("trace.marker", "forged-html-override-redaction"),
                "<div data-trace-redacted=\"1\">" + rawPayload + "</div>");

        assertNotNull(id);
        String html = store.get(id).orElseThrow().html();

        assertFalse(html.contains(rawPayload));
        assertTrue(html.contains("hash12"));
    }

    @Test
    void forgedBuilderPrefixWithoutTraceHtmlMetaDoesNotBypassHtmlOverrideRedaction() {
        TraceSnapshotStore store = enabledStore();
        String rawPayload = "private forged builder prefix html must not remain in snapshot";

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                500,
                null,
                Map.of("trace.marker", "forged-builder-prefix-redaction"),
                "<details data-trace-redacted=\"1\" class=\"search-trace trace-risk-ok\"><summary>ok</summary>"
                        + rawPayload
                        + "</details>");

        assertNotNull(id);
        String html = store.get(id).orElseThrow().html();

        assertFalse(html.contains(rawPayload));
        assertTrue(html.contains("hash12"));
    }

    @Test
    void matchingTraceHtmlMetaAllowsGeneratedSplitPanelOverride() {
        TraceSnapshotStore store = enabledStore();
        String generatedPanel = "<details data-trace-redacted=\"1\" class=\"search-trace trace-risk-ok\">"
                + "<summary>ok</summary><div>safe generated panel</div></details>";

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                500,
                null,
                Map.of(
                        "ui.traceHtml.kind", "splitPanel",
                        "ui.traceHtml.length", generatedPanel.length()),
                generatedPanel);

        assertNotNull(id);
        String html = store.get(id).orElseThrow().html();

        assertTrue(html.contains("safe generated panel"));
        assertFalse(html.contains("hash12"));
    }

    @Test
    void snapshotReasonIsMaskedInDetailSummaryAndHtml() {
        TraceSnapshotStore store = enabledStoreWithHtmlBuilder();
        String secret = "sk-" + "test-trace-snapshot-reason-1234567890";

        String id = store.captureCustom(
                "unit_test reason api_key=" + secret,
                "GET",
                "/api/chat",
                500,
                null,
                Map.of("trace.marker", "reason-redaction"),
                null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String rendered = snapshot + "\n" + snapshot.html() + "\n" + store.listSummaries(10);

        assertFalse(rendered.contains(secret));
        assertTrue(rendered.contains("redacted") || rendered.contains("*"));
    }

    @Test
    void freeFormSnapshotReasonIsStoredAsHashLabel() {
        TraceSnapshotStore store = enabledStoreWithHtmlBuilder();
        String privateReason = "private student query next appointment";

        String id = store.captureCustom(
                privateReason,
                "GET",
                "/api/chat",
                500,
                null,
                Map.of("trace.marker", "reason-label-redaction"),
                null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String summaryReason = String.valueOf(store.listSummaries(10).get(0).get("reason"));
        String rendered = snapshot + "\n" + snapshot.html() + "\n" + store.listSummaries(10);

        assertFalse(rendered.contains(privateReason), rendered);
        assertFalse(rendered.contains("private student"), rendered);
        assertFalse(rendered.contains("next appointment"), rendered);
        assertTrue(snapshot.reason().startsWith("hash:"), rendered);
        assertTrue(summaryReason.startsWith("hash:"), rendered);
    }

    @Test
    void snapshotPathDropsQueryAndKeepsHashMetadata() {
        TraceSnapshotStore store = enabledStore();
        String rawQuery = "private-path-query";

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat?q=" + rawQuery + "#frag",
                500,
                null,
                Map.of("trace.marker", "path-redaction"),
                null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String rendered = snapshot + "\n" + snapshot.html() + "\n" + store.listSummaries(10);

        assertEquals("/api/chat", snapshot.path());
        assertFalse(rendered.contains(rawQuery));
        assertFalse(rendered.contains("?q="));
        assertTrue(rendered.contains("pathHash"));
        assertTrue(rendered.contains("pathLength"));
    }

    @Test
    void snapshotPathWithSecretLikeSegmentUsesDiagnosticSummary() {
        TraceSnapshotStore store = enabledStore();
        String rawPath = "/api/debug/raw-user-question/sk-" + "snapshotpathtrace0123456789012345";

        String id = store.captureCustom(
                "unit_test",
                "GET",
                rawPath,
                500,
                null,
                Map.of("trace.marker", "path-secret-segment-redaction"),
                null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String rendered = snapshot + "\n" + snapshot.html() + "\n" + store.listSummaries(10);

        assertFalse(rendered.contains(rawPath));
        assertFalse(rendered.contains("raw-user-question"));
        assertFalse(rendered.contains("snapshotpathtrace0123456789012345"));
        assertTrue(snapshot.path().contains("hash12="), rendered);
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotDetailExtractsStandardRagOrchestrationWithoutRawPayloads() {
        TraceSnapshotStore store = enabledStore();
        String rawQuery = "snapshot orchestration raw query";
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("v", 1);
        event.put("seq", 1);
        event.put("ts", "2026-05-27T00:00:00Z");
        event.put("traceId", "trace-raw");
        event.put("sessionId", "session-raw");
        event.put("requestId", "request-raw");
        event.put("kind", "rag.pipeline");
        event.put("phase", "retrieval");
        event.put("stage", "search");
        event.put("step", "complete");
        event.put("component", "UnitTest");
        event.put("status", "empty");
        event.put("input", Map.of(
                "query", rawQuery,
                "queryHash", "hash123",
                "queryLen", rawQuery.length(),
                "requestedTopK", 5,
                "planId", "unit",
                "mode", "active_gated"));
        event.put("output", Map.of(
                "returnedCount", 0,
                "afterFilterCount", 0,
                "selectedCount", 0,
                "stageMs", 9,
                "sourceDiversity", 0.0d));
        event.put("failure", Map.of(
                "reasonCode", "zero_result",
                "failureClass", "zero_result",
                "exceptionType", "None"));
        event.put("control", Map.of(
                "action", "recovery",
                "applied", true,
                "reasonCode", "zero_result",
                "breadcrumbId", "bc-1"));
        Map<String, Object> trace = Map.of(
                "orch.events.v1", List.of(event));

        String id = store.captureCustom("unit_test", "GET", "/api/chat", 500, null, trace, null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        Map<String, Object> orchestration = snapshot.orchestration();

        assertEquals("rag-orch-events.v1", orchestration.get("contractVersion"));
        assertEquals(1, orchestration.get("eventCount"));
        assertEquals(1, orchestration.get("controlCount"));
        assertEquals("zero_result", orchestration.get("lastFailureReason"));
        assertEquals("recovery", orchestration.get("lastControlAction"));
        assertFalse(orchestration.toString().contains(rawQuery));

        List<Map<String, Object>> timeline = (List<Map<String, Object>>) orchestration.get("timeline");
        assertEquals(1, timeline.size());
        assertEquals("search", timeline.get(0).get("stage"));
        assertEquals("zero_result", timeline.get(0).get("reasonCode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotFailureReasonCodesAreLabelsNotRawText() {
        TraceSnapshotStore store = enabledStore();
        String rawReason = "private orchestration reason should not remain in snapshot";
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("v", 1);
        event.put("seq", 1);
        event.put("ts", "2026-05-27T00:00:00Z");
        event.put("kind", "rag.pipeline");
        event.put("phase", "retrieval");
        event.put("stage", "search");
        event.put("step", "complete");
        event.put("component", "UnitTest");
        event.put("status", "empty");
        event.put("failure", Map.of(
                "reasonCode", rawReason,
                "failureClass", rawReason,
                "exceptionType", rawReason));
        event.put("control", Map.of(
                "action", rawReason,
                "applied", true,
                "reasonCode", rawReason,
                "breadcrumbId", rawReason));

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                500,
                null,
                Map.of("orch.events.v1", List.of(event)),
                null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        Map<String, Object> orchestration = snapshot.orchestration();
        String dump = orchestration.toString() + "\n" + snapshot.html();

        assertFalse(dump.contains(rawReason));
        assertTrue(String.valueOf(orchestration.get("lastFailureReason")).startsWith("hash:"), dump);

        List<Map<String, Object>> timeline = (List<Map<String, Object>>) orchestration.get("timeline");
        assertTrue(String.valueOf(timeline.get(0).get("reasonCode")).startsWith("hash:"), dump);
        Map<String, Object> failureSummary = (Map<String, Object>) orchestration.get("failureSummary");
        Map<String, Object> byReason = (Map<String, Object>) failureSummary.get("byReason");
        assertTrue(byReason.keySet().stream().allMatch(k -> k.startsWith("hash:")), dump);
    }

    @Test
    void snapshotRetainsSanitizedMlaBreadcrumbAndPromotedEvidence() {
        TraceSnapshotStore store = enabledStore();
        Map<String, Object> breadcrumb = new LinkedHashMap<>();
        breadcrumb.put("component", "TraceFilter");
        breadcrumb.put("decision", "request_started");
        breadcrumb.put("requestId", "raw-request-id");
        breadcrumb.put("sessionId", "raw-session-id");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("marker", "W1");
        evidence.put("kind", "WEB");
        evidence.put("title", "Alpha");
        evidence.put("source", "https://example.com/a");
        evidence.put("lineStart", 4);
        evidence.put("confidence", 0.75d);

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                200,
                null,
                Map.of(
                        "ml.breadcrumbs.v1", List.of(breadcrumb),
                        "rag.evidence.public", List.of(evidence)),
                null);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String dump = snapshot.toString();
        assertTrue(snapshot.hasMlBreadcrumbs());
        assertTrue(dump.contains("request_started"));
        assertTrue(dump.contains("rag.evidence.public"));
        assertFalse(dump.contains("raw-session-id"));
        assertFalse(dump.contains("raw-request-id"));
    }

    @Test
    void snapshotHtmlRendersRelationThumbnailSliceMapWithoutRawEntities() {
        TraceSnapshotStore store = enabledStoreWithHtmlBuilder();
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("rank", 1);
        row1.put("hash", "aaaaaaaaaaaa");
        row1.put("relationKind", "RELATIONSHIP_SUPPORTS");
        row1.put("selectionReason", "token_overlap");
        row1.put("contextLayer", "breadcrumb");
        row1.put("overlap", 2);
        row1.put("rawEntity", "Alpha Private Entity");
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("rank", 2);
        row2.put("hash", "bbbbbbbbbbbb");
        row2.put("relationKind", "RELATIONSHIP_CONTRASTS");
        row2.put("selectionReason", "anchor_seed");
        row2.put("contextLayer", "anchor");
        row2.put("overlap", 0);
        row2.put("rawEntity", "Beta Private Entity");

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                200,
                null,
                Map.of(
                        "retrieval.kg.relationThumbnail.sliceMapCount", 2,
                        "retrieval.kg.relationThumbnail.sliceMap", List.of(row1, row2),
                        "rgb.soak.strategy.R_ONLY_KG.relationThumbnailContextLayerCounts",
                        Map.of("breadcrumb", 1, "anchor", 1),
                        "rgb.soak.strategy.R_ONLY_KG.relationThumbnailContextLayers",
                        List.of("breadcrumb", "anchor", "Alpha Private Entity")),
                null);

        assertNotNull(id);
        String html = store.get(id).orElseThrow().html();
        assertNotNull(html);
        int panelStart = html.indexOf("Relation Thumbnail Slice Map");
        assertTrue(panelStart >= 0);
        int panelEnd = html.indexOf("</table>", panelStart);
        assertTrue(panelEnd > panelStart);
        String panel = html.substring(panelStart, panelEnd);
        assertTrue(panel.contains("sliceMapCount"));
        assertTrue(panel.contains("RELATIONSHIP_SUPPORTS"));
        assertTrue(panel.contains("anchor_seed"));
        assertTrue(panel.contains("aaaaaaaaaaaa"));
        assertFalse(panel.contains("Alpha Private Entity"));
        assertFalse(panel.contains("Beta Private Entity"));
        int layerStart = html.indexOf("Relation Thumbnail Context Layers");
        assertTrue(layerStart >= 0);
        int layerEnd = html.indexOf("</table>", layerStart);
        assertTrue(layerEnd > layerStart);
        String layerPanel = html.substring(layerStart, layerEnd);
        assertTrue(layerPanel.contains("R_ONLY_KG"));
        assertTrue(layerPanel.contains("breadcrumb"));
        assertTrue(layerPanel.contains("anchor"));
        assertTrue(layerPanel.contains("1"));
        assertFalse(layerPanel.contains("Alpha Private Entity"));
    }

    @Test
    void snapshotHtmlRendersUawRelationThumbnailBudgetWithoutRawAnchors() {
        TraceSnapshotStore store = enabledStoreWithHtmlBuilder();

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                200,
                null,
                Map.of(
                        "uaw.thumbnail.relationThumbnail.inputAnchorCount", 12,
                        "uaw.thumbnail.relationThumbnail.selectedAnchorCount", 8,
                        "uaw.thumbnail.relationThumbnail.anchorBudget", 8,
                        "uaw.thumbnail.relationThumbnail.pairBudget", 16,
                        "uaw.thumbnail.relationThumbnail.emittedPairCount", 16,
                        "uaw.thumbnail.relationThumbnail.sliced", true,
                        "uaw.thumbnail.relationThumbnail.rawAnchor", "Anchor09 Private Graph Entity"),
                null);

        assertNotNull(id);
        String html = store.get(id).orElseThrow().html();
        assertNotNull(html);
        int panelStart = html.indexOf("UAW Relation Thumbnail Budget");
        assertTrue(panelStart >= 0);
        int panelEnd = html.indexOf("</table>", panelStart);
        assertTrue(panelEnd > panelStart);
        String panel = html.substring(panelStart, panelEnd);
        assertTrue(panel.contains("inputAnchorCount"));
        assertTrue(panel.contains("selectedAnchorCount"));
        assertTrue(panel.contains("anchorBudget"));
        assertTrue(panel.contains("pairBudget"));
        assertTrue(panel.contains("emittedPairCount"));
        assertTrue(panel.contains("sliced"));
        assertTrue(panel.contains("<code>12</code>"));
        assertTrue(panel.contains("<code>8</code>"));
        assertTrue(panel.contains("<code>16</code>"));
        assertTrue(panel.contains("<code>true</code>"));
        assertFalse(panel.contains("Anchor09 Private Graph Entity"));
    }

    @Test
    void snapshotHtmlCoercesUawRelationThumbnailBudgetValuesBeforeRendering() {
        TraceSnapshotStore store = enabledStoreWithHtmlBuilder();

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                200,
                null,
                Map.of(
                        "uaw.thumbnail.relationThumbnail.inputAnchorCount", "Anchor09 Private Graph Entity",
                        "uaw.thumbnail.relationThumbnail.selectedAnchorCount", 8,
                        "uaw.thumbnail.relationThumbnail.sliced", "Anchor10 Private Graph Entity"),
                null);

        assertNotNull(id);
        String html = store.get(id).orElseThrow().html();
        assertNotNull(html);
        int panelStart = html.indexOf("UAW Relation Thumbnail Budget");
        assertTrue(panelStart >= 0);
        int panelEnd = html.indexOf("</table>", panelStart);
        assertTrue(panelEnd > panelStart);
        String panel = html.substring(panelStart, panelEnd);
        assertTrue(panel.contains("inputAnchorCount"));
        assertTrue(panel.contains("selectedAnchorCount"));
        assertTrue(panel.contains("sliced"));
        assertTrue(panel.contains("<code>n/a</code>"));
        assertTrue(panel.contains("<code>8</code>"));
        assertFalse(panel.contains("Anchor09 Private Graph Entity"));
        assertFalse(panel.contains("Anchor10 Private Graph Entity"));
    }

    @Test
    void snapshotHtmlRendersKgAxisUawRelationThumbnailBudgetAliases() {
        TraceSnapshotStore store = enabledStoreWithHtmlBuilder();

        String id = store.captureCustom(
                "unit_test",
                "GET",
                "/api/chat",
                200,
                null,
                Map.of(
                        "rag.eval.kgAxis.uawRelationThumbnailInputAnchorCount", 9,
                        "rag.eval.kgAxis.uawRelationThumbnailSelectedAnchorCount", 4,
                        "rag.eval.kgAxis.uawRelationThumbnailAnchorBudget", 4,
                        "rag.eval.kgAxis.uawRelationThumbnailPairBudget", 6,
                        "rag.eval.kgAxis.uawRelationThumbnailEmittedPairCount", 6,
                        "rag.eval.kgAxis.uawRelationThumbnailSliced", true,
                        "rag.eval.kgAxis.rawAnchor", "Anchor11 Private Graph Entity"),
                null);

        assertNotNull(id);
        String html = store.get(id).orElseThrow().html();
        assertNotNull(html);
        int panelStart = html.indexOf("UAW Relation Thumbnail Budget");
        assertTrue(panelStart >= 0);
        int panelEnd = html.indexOf("</table>", panelStart);
        assertTrue(panelEnd > panelStart);
        String panel = html.substring(panelStart, panelEnd);
        assertTrue(panel.contains("inputAnchorCount"));
        assertTrue(panel.contains("selectedAnchorCount"));
        assertTrue(panel.contains("anchorBudget"));
        assertTrue(panel.contains("pairBudget"));
        assertTrue(panel.contains("emittedPairCount"));
        assertTrue(panel.contains("sliced"));
        assertTrue(panel.contains("<code>9</code>"));
        assertTrue(panel.contains("<code>4</code>"));
        assertTrue(panel.contains("<code>6</code>"));
        assertTrue(panel.contains("<code>true</code>"));
        assertFalse(panel.contains("Anchor11 Private Graph Entity"));
    }

    private static TraceSnapshotStore enabledStore() {
        return enabledStore(false);
    }

    private static TraceSnapshotStore enabledStoreWithHtmlBuilder() {
        return enabledStore(true);
    }

    private static TraceSnapshotStore enabledStore(boolean withHtmlBuilder) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (withHtmlBuilder) {
            factory.registerSingleton("traceHtmlBuilder", new TraceHtmlBuilder(null));
        }
        ObjectProvider<TraceHtmlBuilder> provider = factory.getBeanProvider(TraceHtmlBuilder.class);
        TraceSnapshotStore store = new TraceSnapshotStore(provider);
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "maxValueLen", 1000);
        ReflectionTestUtils.setField(store, "maxEntries", 100);
        ReflectionTestUtils.setField(store, "allowReasonsCsv", "");
        ReflectionTestUtils.setField(store, "denyReasonsCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysMode", "any");
        ReflectionTestUtils.setField(store, "denyKeysCsv", "");
        ReflectionTestUtils.setField(store, "captureSample", 1.0d);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
        ReflectionTestUtils.setField(store, "maxPerTrace", 10);
        ReflectionTestUtils.setField(store, "budgetWindowMs", 600_000L);
        ReflectionTestUtils.setField(store, "httpStatusMin", 400);
        ReflectionTestUtils.setField(store, "captureHttpOnDebug", true);
        ReflectionTestUtils.setField(store, "captureHttpOnMl", true);
        ReflectionTestUtils.setField(store, "captureHttpOnOrch", true);
        ReflectionTestUtils.setField(store, "captureHttpOnException", true);
        ReflectionTestUtils.setField(store, "htmlEnabled", true);
        ReflectionTestUtils.setField(store, "htmlMaxLen", 60_000);
        return store;
    }
}
