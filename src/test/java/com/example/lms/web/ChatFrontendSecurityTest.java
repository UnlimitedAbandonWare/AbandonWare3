package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFrontendSecurityTest {

    @Test
    void chatStreamDoesNotExecuteTraceScripts() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertFalse(js.contains("data-trace-script"));
        assertFalse(js.contains("createElement('script')"));
        assertFalse(js.contains("createElement(\"script\")"));
        assertTrue(js.contains("replaceWithSanitizedHtml(holder, payload.html || \"\")"));
        assertEquals(1, countOccurrences(js, "if (type === \"status\")"));
        assertEquals(1, countOccurrences(js, "if (type === \"trace\")"));
        assertEquals(1, countOccurrences(js, "if (type === \"evidence\")"));
        assertFalse(js.contains("updateOrchestrationSignalBar({ streamStatus: \"streaming\", status: payload.data || \"\" });"));
    }

    @Test
    void evidenceRendererUsesTextNodesOnly() throws Exception {
        String evidence = Files.readString(Path.of("main/resources/static/js/evidence-ui.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(evidence.contains("textContent"));
        assertFalse(evidence.contains("innerHTML"));
        assertFalse(evidence.contains("insertAdjacentHTML"));
        assertTrue(orch.contains("el.textContent = text(value, fallback);"));
        assertFalse(orch.contains("innerHTML"));
        assertTrue(orch.contains("scoreDelta"));
        assertTrue(orch.contains("export function renderPlanModeCard"));
        assertTrue(orch.contains("holder.dataset.ttsIgnore = \"1\""));
        assertTrue(orch.contains("valueEl.textContent = text(value, \"-\");"));
    }

    @Test
    void transformerCoreRendererUsesTextNodesAndStreamHandler() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String transformer = Files.readString(Path.of("main/resources/static/js/transformer-core-ui.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("renderTransformerCoreRail"));
        assertTrue(chat.contains("if (type === \"transformer\")"));
        assertTrue(chat.contains("if (!hasBlocks && meta?.status === \"final\") return;"));
        assertTrue(chat.contains("deriveTransformerBadges(blocks)"));
        assertTrue(chat.contains("const modelReason = modelBlock ? blockReason(modelBlock) : null;"));
        assertTrue(chat.contains("model: modelReason"));
        assertTrue(chat.contains("modelActive: Boolean(modelBlock)"));
        assertTrue(chat.contains("modelBadge: modelBlock ? `Model: ${modelStatus || \"pending\"}` : \"Model\""));
        assertTrue(chat.contains("modelNeedsReview"));
        assertTrue(transformer.contains("export function renderTransformerCoreRail"));
        assertTrue(transformer.contains("setAttribute(\"data-role\", \"transformer-core-summary\")"));
        assertTrue(transformer.contains("setAttribute(\"data-role\", \"transformer-core-flow\")"));
        assertTrue(transformer.contains("setAttribute(\"data-role\", \"transformer-core-debug\")"));
        assertTrue(transformer.contains("slice(0, 12)"));
        assertTrue(transformer.contains("summarizeBlocks"));
        assertTrue(transformer.contains("Verification"));
        assertTrue(transformer.contains("Debug"));
        assertTrue(transformer.contains("Exception"));
        assertTrue(orch.contains("setBadge(root, \"model\", has(\"modelActive\") ? Boolean(partial.modelActive) : undefined, partial.modelBadge);"));
        assertTrue(orch.contains("setBadge(root, \"dpp\""));
        assertTrue(orch.contains("setBadge(root, \"cfvm\""));
        assertTrue(orch.contains("setBadge(root, \"supabase\""));
        assertTrue(html.contains("data-orch-badge=\"model\""));
        assertTrue(html.contains("data-orch-badge=\"dpp\""));
        assertTrue(html.contains("data-orch-badge=\"cfvm\""));
        assertTrue(html.contains("data-orch-badge=\"supabase\""));
        assertTrue(css.contains("repeat(auto-fit, minmax(92px, 1fr))"));
        assertTrue(transformer.contains("textContent"));
        assertFalse(transformer.contains("innerHTML"));
        assertFalse(transformer.contains("insertAdjacentHTML"));
        assertFalse(transformer.contains("createElement('script')"));
        assertFalse(transformer.contains("createElement(\"script\")"));
    }

    @Test
    void topUtilityBarUsesContentHeightInsteadOfClippingDebugSurfaces() throws Exception {
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);
        String modelPickerCss = Files.readString(Path.of("main/resources/static/css/model-picker.css"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String refreshedTopBarBlock = css.substring(css.lastIndexOf(".top-utility-bar {"),
                css.indexOf("}", css.lastIndexOf(".top-utility-bar {")) + 1);
        String refreshedMainBlock = css.substring(css.lastIndexOf(".main-container {"),
                css.indexOf("}", css.lastIndexOf(".main-container {")) + 1);
        String refreshedChatWrapperBlock = cssBlockAfter(css, ".chat-area-wrapper {\n    padding: 1.1rem 1.5rem;");
        String operatorDebugBlock = cssBlockAfter(css, ".operator-debug-console {\n    display: grid;");
        String modelStrategyBlock = cssBlockAfter(css, ".model-strategy-bar {\n    position: static;");
        String modelStrategyLabelBlock = cssBlockAfter(css, ".model-strategy-bar strong {");

        assertFalse(css.contains(".top-utility-bar {\n    height: var(--top-bar-height);"),
                "fixed top-bar height clips or overlaps the model picker and signal bar");
        assertFalse(refreshedTopBarBlock.contains("align-content: center;"),
                "centered grid content can overflow upward when debug surfaces wrap");
        assertTrue(css.contains("min-height: var(--top-bar-min-height);"));
        assertTrue(css.contains("body {\n    min-height: 100vh;\n    display: flex;\n    flex-direction: column;"));
        assertTrue(refreshedMainBlock.contains("flex: 1 1 auto;"));
        assertTrue(refreshedMainBlock.contains("min-height: 0;"));
        assertTrue(refreshedMainBlock.contains("height: auto;"));
        assertTrue(refreshedChatWrapperBlock.contains("overflow: clip;"),
                "the chat wrapper must not become the focus-scroll container");
        assertTrue(operatorDebugBlock.contains("grid-template-columns: minmax(180px, .28fr) minmax(0, 1fr);"));
        assertTrue(operatorDebugBlock.contains("overflow: visible;"));
        assertTrue(css.contains("grid-template-columns: repeat(7, minmax(96px, 1fr));"));
        assertTrue(css.contains("min-height: 44px;"));
        assertTrue(css.contains(".debug-console-foot {\n    display: none;"));
        assertTrue(modelStrategyBlock.contains("flex-wrap: nowrap;"));
        assertTrue(modelStrategyBlock.contains("overflow-x: auto;"));
        assertTrue(modelStrategyBlock.contains("overflow-y: hidden;"));
        assertTrue(modelStrategyLabelBlock.contains("white-space: nowrap;"));
        assertTrue(modelStrategyLabelBlock.contains("flex: 0 0 auto;"));
        assertTrue(css.contains("grid-template-columns: repeat(auto-fit, minmax(82px, 1fr));"));
        assertTrue(css.contains(".orch-signal-badges {\n    grid-column: span 2;"));
        assertTrue(modelPickerCss.contains("max-height: 42px;"));
        assertTrue(modelPickerCss.contains("overflow-x: auto;"));
        assertTrue(modelPickerCss.contains("overflow-y: hidden;"));
        assertTrue(modelPickerCss.contains("flex-wrap: nowrap;"));
        assertTrue(css.contains("@media (max-height: 760px) {"));
        assertTrue(css.contains(".chat-command-copy p,\n    .section-kicker {\n        display: none;"));
        assertTrue(css.contains("grid-template-columns: repeat(7, minmax(82px, 1fr));"));
        assertTrue(css.contains("grid-template-columns: repeat(auto-fit, minmax(72px, 1fr));"));
        assertTrue(css.contains("min-height: 36px;"));
        assertTrue(css.contains("#chatWindow {\n        flex: 1 1 112px;"));
        assertTrue(css.contains("min-height: clamp(96px, 18vh, 150px);"));
        assertTrue(css.contains(".model-strategy-bar {\n        gap: .35rem;\n        max-height: 54px;"));
        assertTrue(css.contains(".drop-zone,\n    .input-toggle-row {\n        display: none !important;"));
        assertTrue(html.contains("min-height: var(--top-bar-min-height);"));
        assertTrue(html.contains("height: auto;"));
    }

    @Test
    void chatTopBarShowsRedactedDebugHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"debugHeartbeatBar\""));
        assertTrue(html.contains("data-debug-heartbeat=\"root\""));
        assertTrue(html.contains("data-debug-heartbeat-summary"));
        assertTrue(html.contains("data-debug-heartbeat-field=\"core\""));
        assertTrue(html.contains("data-debug-heartbeat-field=\"ui\""));
        assertTrue(html.contains("data-debug-heartbeat-field=\"external\""));
        assertTrue(html.contains("data-debug-heartbeat-field=\"supabase\""));
        assertTrue(html.contains("data-debug-heartbeat-field=\"browser\""));

        assertTrue(css.contains(".debug-heartbeat-bar {"));
        assertTrue(css.contains("grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));"));
        assertTrue(css.contains(".debug-heartbeat-summary {"));
        assertTrue(css.contains("grid-column: 1 / -1;"));
        assertTrue(css.contains(".debug-heartbeat-card strong {"));
        assertTrue(css.contains(".debug-heartbeat-card small {"));

        assertTrue(chat.contains("const PIPELINE_HEALTH_API = '/agent/db-context/pipeline-health';"));
        assertTrue(chat.contains("debugHeartbeatBar: $(\"debugHeartbeatBar\")"));
        assertTrue(chat.contains("function renderDebugHeartbeat(data = {}) {"));
        assertTrue(chat.contains("setDebugHeartbeatField('core', coreStatus, coreDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('ui', uiStatus, uiDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('external', externalStatus, externalDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('supabase', supabaseStatus, supabaseDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('browser', browserStatus, browserDetail);"));
        assertTrue(chat.contains("function refreshDebugHeartbeat()"));
        assertTrue(chat.contains("apiCall(PIPELINE_HEALTH_API"));
        assertTrue(chat.contains("window.setInterval(refreshDebugHeartbeat, 30000);"));
        assertFalse(chat.contains("debugHeartbeatBar.innerHTML"));
    }

    @Test
    void chatTopBarShowsOneLineDebugSummaryFromExistingStatuses() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-summary"));
        assertTrue(html.contains("<strong>Core wait / UI wait / External wait</strong>"));

        assertTrue(chat.contains("function setDebugHeartbeatSummary(status, detail) {"));
        assertTrue(chat.contains("const summaryStatus = [coreStatus, uiStatus, externalStatus, gateStatus, actionStatus].includes('WARN') ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const summaryDetail = `core:${coreStatus} ui:${uiStatus} external:${externalStatus} next:${goalNextRow.nextAction || goalNextRow.firstAction || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatSummary(summaryStatus, summaryDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatSummary('WARN', 'pipeline_health_unavailable');"));

        assertFalse(chat.contains("summaryRaw"));
        assertFalse(chat.contains("summarySecret"));
        assertFalse(chat.contains("summaryAuthorization"));
    }

    @Test
    void chatTopBarSummaryIncludesLiveDefaultModelResponseState() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const debugHeartbeatSummaryState = {"));
        assertTrue(chat.contains("function refreshDebugHeartbeatSummary() {"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.liveStatus = liveStatus;"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.waitStatus = waitStatus;"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.timeoutStatus = timeoutStatus;"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.cancelStatus = cancelStatus;"));
        assertTrue(chat.contains("live:${debugHeartbeatSummaryState.liveStatus} wait:${debugHeartbeatSummaryState.waitStatus} timeout:${debugHeartbeatSummaryState.timeoutStatus} cancel:${debugHeartbeatSummaryState.cancelStatus}"));
        assertTrue(chat.contains("debugHeartbeatSummaryState.nextAction = goalNextRow.nextAction || goalNextRow.firstAction || 'none';"));
        assertTrue(chat.contains("refreshDebugHeartbeatSummary();"));

        assertFalse(chat.contains("debugHeartbeatSummaryState.raw"));
        assertFalse(chat.contains("debugHeartbeatSummaryState.secret"));
        assertFalse(chat.contains("debugHeartbeatSummaryState.authorization"));
    }

    @Test
    void chatTopBarShowsCompactDebugMatrixForCoreUiAndExternalState() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-heartbeat-matrix\""));
        assertTrue(html.contains("data-debug-matrix=\"root\""));
        assertTrue(html.contains("data-debug-matrix-cell=\"core-ui\""));
        assertTrue(html.contains("data-debug-matrix-cell=\"model-answer\""));
        assertTrue(html.contains("data-debug-matrix-cell=\"search-trace\""));
        assertTrue(html.contains("data-debug-matrix-cell=\"external-proof\""));

        assertTrue(css.contains(".debug-heartbeat-matrix {"));
        assertTrue(css.contains("grid-template-columns: repeat(4, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-matrix-cell[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-matrix-cell[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugMatrixCell(name, status, title, detail) {"));
        assertTrue(chat.contains("setDebugMatrixCell('core-ui', coreUiStatus, 'Core/UI', coreUiDetail);"));
        assertTrue(chat.contains("setDebugMatrixCell('model-answer', modelAnswerStatus, 'Model/Answer', modelAnswerDetail);"));
        assertTrue(chat.contains("setDebugMatrixCell('search-trace', searchTraceStatus, 'Search/Trace', searchTraceDetail);"));
        assertTrue(chat.contains("setDebugMatrixCell('external-proof', externalProofStatus, 'External Proof', externalProofDetail);"));
        assertTrue(chat.contains("setDebugMatrixCell('model-answer', modelAnswerLiveStatus, 'Model/Answer', modelAnswerLiveDetail);"));

        assertFalse(chat.contains("debugMatrixRaw"));
        assertFalse(chat.contains("debugMatrixSecret"));
        assertFalse(chat.contains("debugMatrixAuthorization"));
    }

    @Test
    void chatTopBarShowsOneGlanceDebugCockpitForRuntimeAndNextAction() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-cockpit-rail\""));
        assertTrue(html.contains("data-debug-cockpit=\"root\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"stream\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"model\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"search\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"external\""));
        assertTrue(html.contains("data-debug-cockpit-cell=\"next\""));

        assertTrue(css.contains(".debug-cockpit-rail {"));
        assertTrue(css.contains("grid-template-columns: repeat(5, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-cockpit-cell[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-cockpit-cell[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugCockpitCell(name, status, title, detail) {"));
        assertTrue(chat.contains("function safeDebugCockpitDetail(value) {"));
        assertTrue(chat.contains("small.textContent = safeDebugCockpitDetail(detail);"));
        assertTrue(chat.contains("[url]"));
        assertTrue(chat.contains("[path]"));
        assertTrue(chat.contains("project_ref:[redacted]"));
        assertTrue(chat.contains("slice(0, 180)"));
        assertTrue(chat.contains("function updateDebugCockpitFromState() {"));
        assertTrue(chat.contains("setDebugCockpitCell('stream', debugHeartbeatSummaryState.liveStatus, 'Stream',"));
        assertTrue(chat.contains("setDebugCockpitCell('model', modelStatus, 'Model',"));
        assertTrue(chat.contains("setDebugCockpitCell('search', searchTraceStatus, 'Search',"));
        assertTrue(chat.contains("setDebugCockpitCell('external', externalProofStatus, 'External',"));
        assertTrue(chat.contains("setDebugCockpitCell('next', actionStatus, 'Next', actionDetail);"));

        assertFalse(chat.contains("debugCockpitRaw"));
        assertFalse(chat.contains("debugCockpitSecret"));
        assertFalse(chat.contains("debugCockpitAuthorization"));
    }

    @Test
    void chatTopBarShowsMissionStripWithCurrentBlockingAxis() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-mission-strip\""));
        assertTrue(html.contains("data-debug-mission=\"root\""));
        assertTrue(html.contains("data-debug-mission-axis=\"core\""));
        assertTrue(html.contains("data-debug-mission-axis=\"ui\""));
        assertTrue(html.contains("data-debug-mission-axis=\"external\""));
        assertTrue(html.contains("data-debug-mission-axis=\"next\""));
        assertTrue(html.contains("data-debug-mission-axis=\"focus\""));

        assertTrue(css.contains(".debug-mission-strip {"));
        assertTrue(css.contains("grid-template-columns: repeat(5, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-mission-axis[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-mission-axis[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugMissionAxis(name, status, title, value, detail) {"));
        assertTrue(chat.contains("function firstWarnMissionAxis("));
        assertTrue(chat.contains("const missionFocus = firstWarnMissionAxis("));
        assertTrue(chat.contains("setDebugMissionAxis('core', coreUiStatus, 'Core',"));
        assertTrue(chat.contains("setDebugMissionAxis('ui', uiStatus, 'UI',"));
        assertTrue(chat.contains("setDebugMissionAxis('external', externalProofStatus, 'External',"));
        assertTrue(chat.contains("setDebugMissionAxis('next', actionStatus, 'Next',"));
        assertTrue(chat.contains("setDebugMissionAxis('focus', missionFocus.status, 'Focus', missionFocus.value, missionFocus.detail);"));
        assertTrue(chat.contains("setDebugMissionAxis('focus', 'WARN', 'Focus', 'pipeline', 'pipeline_health_unavailable');"));

        assertFalse(chat.contains("debugMissionRaw"));
        assertFalse(chat.contains("debugMissionSecret"));
        assertFalse(chat.contains("debugMissionAuthorization"));
    }

    @Test
    void chatTopBarShowsFlowRailForPipelineDebugSequence() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-flow-rail\""));
        assertTrue(html.contains("data-debug-flow=\"root\""));
        assertTrue(html.contains("data-debug-flow-step=\"input\""));
        assertTrue(html.contains("data-debug-flow-step=\"model\""));
        assertTrue(html.contains("data-debug-flow-step=\"search\""));
        assertTrue(html.contains("data-debug-flow-step=\"trace\""));
        assertTrue(html.contains("data-debug-flow-step=\"external\""));
        assertTrue(html.contains("data-debug-flow-step=\"action\""));

        assertTrue(css.contains(".debug-flow-rail {"));
        assertTrue(css.contains("grid-template-columns: repeat(6, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-flow-step[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-flow-step[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugFlowStep(name, status, title, value, detail) {"));
        assertTrue(chat.contains("setDebugFlowStep('input', debugHeartbeatSummaryState.liveStatus, 'Input',"));
        assertTrue(chat.contains("setDebugFlowStep('model', modelAnswerStatus, 'Model',"));
        assertTrue(chat.contains("setDebugFlowStep('search', searchTraceStatus, 'Search',"));
        assertTrue(chat.contains("setDebugFlowStep('trace', traceStatus, 'Trace',"));
        assertTrue(chat.contains("setDebugFlowStep('external', externalProofStatus, 'External',"));
        assertTrue(chat.contains("setDebugFlowStep('action', actionStatus, 'Action',"));
        assertTrue(chat.contains("setDebugFlowStep('action', 'WARN', 'Action', 'WAIT', 'next_proof_unknown');"));
        assertTrue(chat.contains("small.textContent = safeDebugCockpitDetail(detail);"));

        assertFalse(chat.contains("debugFlowRaw"));
        assertFalse(chat.contains("debugFlowSecret"));
        assertFalse(chat.contains("debugFlowAuthorization"));
    }

    @Test
    void chatHeartbeatVisibleDetailsUseSharedRedactor() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(functionBody(chat, "function setDebugHeartbeatField")
                .contains("small.textContent = safeDebugCockpitDetail(detail);"));
        assertTrue(functionBody(chat, "function setDebugHeartbeatSummary")
                .contains("small.textContent = safeDebugCockpitDetail(detail);"));
        assertTrue(functionBody(chat, "function setDebugMatrixCell")
                .contains("small.textContent = safeDebugCockpitDetail(detail);"));
        assertTrue(chat.contains(".replace(/https?:\\/\\/\\S+/gi, '[url]')"));
        assertTrue(chat.contains(".replace(/project[_-]?ref[:=][A-Za-z0-9_-]+/gi, 'project_ref:[redacted]')"));

        assertFalse(functionBody(chat, "function setDebugHeartbeatField")
                .contains("small.textContent = String(detail || 'no detail');"));
        assertFalse(functionBody(chat, "function setDebugHeartbeatSummary")
                .contains("small.textContent = String(detail || 'no detail');"));
        assertFalse(functionBody(chat, "function setDebugMatrixCell")
                .contains("small.textContent = String(detail || 'no detail');"));
    }

    @Test
    void chatTopBarShowsProofStripForLocalExternalReadiness() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("class=\"debug-proof-strip\""));
        assertTrue(html.contains("data-debug-proof=\"root\""));
        assertTrue(html.contains("data-debug-proof-cell=\"local\""));
        assertTrue(html.contains("data-debug-proof-cell=\"browser\""));
        assertTrue(html.contains("data-debug-proof-cell=\"computer\""));
        assertTrue(html.contains("data-debug-proof-cell=\"supabase\""));
        assertTrue(html.contains("data-debug-proof-cell=\"producer\""));
        assertTrue(html.contains("data-debug-proof-cell=\"action\""));

        assertTrue(css.contains(".debug-proof-strip {"));
        assertTrue(css.contains("grid-template-columns: repeat(6, minmax(0, 1fr));"));
        assertTrue(css.contains(".debug-proof-cell[data-status=\"warn\"]"));
        assertTrue(css.contains(".debug-proof-cell[data-status=\"ok\"]"));

        assertTrue(chat.contains("function setDebugProofCell(name, status, title, value, detail) {"));
        assertTrue(chat.contains("const localProofStatus = goalNextRow.sourceHealthExit === 0 && goalNextRow.completionAuditExit === 0 ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const producerProofStatus = noetherStatus === 'OK' && patchDropStatus === 'OK' && producerEvidenceRows.length > 0 ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("setDebugProofCell('local', localProofStatus, 'Local',"));
        assertTrue(chat.contains("setDebugProofCell('browser', browserStatus, 'Browser',"));
        assertTrue(chat.contains("setDebugProofCell('computer', computerStatus, 'Computer',"));
        assertTrue(chat.contains("setDebugProofCell('supabase', supabaseStatus, 'Supabase',"));
        assertTrue(chat.contains("setDebugProofCell('producer', producerProofStatus, 'Producer',"));
        assertTrue(chat.contains("setDebugProofCell('action', actionStatus, 'Action',"));
        assertTrue(functionBody(chat, "function setDebugProofCell")
                .contains("small.textContent = safeDebugCockpitDetail(detail);"));

        assertFalse(chat.contains("debugProofRaw"));
        assertFalse(chat.contains("debugProofSecret"));
        assertFalse(chat.contains("debugProofAuthorization"));
    }

    @Test
    void chatTopBarShowsNextExternalDebugActionWithoutSecrets() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"action\""));
        assertTrue(html.contains("debug-heartbeat-card--wide"));

        assertTrue(css.contains(".debug-heartbeat-card--wide {"));
        assertTrue(css.contains("grid-column: span 2;"));

        assertTrue(chat.contains("const goalNextRow = externalRows.find((item) => item?.service === 'goal-next-auto') || {};"));
        assertTrue(chat.contains("const actionStatus = goalNextRow.localPatchJustified === false || goalNextRow.decision === 'evidence_needed' ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const actionDetail = `next:${goalNextRow.nextAction || goalNextRow.firstAction || supabaseRow.nextAction || browserRow.nextAction || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('action', actionStatus, actionDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('action', 'WARN', 'next_proof_unknown');"));

        assertFalse(chat.contains("process.env"));
        assertFalse(chat.contains("SUPABASE_ACCESS_TOKEN"));
        assertFalse(chat.contains("SERVICE_ROLE"));
    }

    @Test
    void chatTopBarShowsSupabaseExternalInputNamesWithoutSecretValues() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const supabaseRequiredEnv = supabaseRow.requiredEnvNames || goalNextRow.supabaseRequiredEnvNames || 'none';"));
        assertTrue(chat.contains("const supabaseRequiredMcp = supabaseRow.requiredMcpTools || goalNextRow.supabaseRequiredMcpTools || 'none';"));
        assertTrue(chat.contains("const supabaseDetail = `scope:${supabaseRow.projectScopeStatus || 'unknown'} needed:${supabaseRow.evidenceNeededCount ?? goalNextRow.supabaseEvidenceNeededCount ?? 0} env:${supabaseRequiredEnv} mcp:${supabaseRequiredMcp}`;"));

        assertFalse(chat.contains("SUPABASE_ACCESS_TOKEN"));
        assertFalse(chat.contains("SERVICE_ROLE"));
        assertFalse(chat.contains("supabaseKey"));
    }

    @Test
    void chatTopBarShowsGoalNextGateHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"gate\""));
        assertTrue(html.contains("<span>Gate</span>"));

        assertTrue(chat.contains("const gateStatus = goalNextRow.decision === 'evidence_needed' || goalNextRow.externalInputGateStatus === 'external_input_needed' || goalNextRow.localPatchJustified === false ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const gateDetail = `gate:${goalNextRow.externalInputGateStatus || 'unknown'} source:${goalNextRow.sourceHealthExit ?? 'n/a'} audit:${goalNextRow.completionAuditExit ?? 'n/a'} localPatch:${goalNextRow.localPatchJustified === false ? 'no' : 'yes'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('gate', gateStatus, gateDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('gate', 'WARN', 'goal_next_gate_unknown');"));

        assertFalse(chat.contains("summaryPath"));
        assertFalse(chat.contains("summaryFileUpdatedAt"));
    }

    @Test
    void chatTopBarShowsDefaultModelRuntimeHeartbeatWithoutRawModel() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"model\""));
        assertTrue(html.contains("<span>Model</span>"));

        assertTrue(chat.contains("const modelRuntime = data.modelRuntime || {};"));
        assertTrue(chat.contains("const modelStatus = modelRuntime.status || (modelRuntime.finalDeliveryExpected || modelRuntime.fastBailTimeout || modelRuntime.waiting ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const modelDetail = `route:${modelRuntime.route || 'unknown'} delivery:${modelRuntime.deliveryState || 'unknown'} wait:${modelRuntime.defaultWaitCode || 'none'} hits:${modelRuntime.timeoutHits ?? 0} len:${modelRuntime.modelLength ?? 0}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('model', modelStatus, modelDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('model', 'WARN', 'model_runtime_unknown');"));

        assertFalse(chat.contains("modelRuntime.model ||"));
        assertFalse(chat.contains("modelRuntime['model']"));
        assertFalse(chat.contains("observedModel"));
    }

    @Test
    void chatTopBarShowsSearchProviderRuntimeHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"search\""));
        assertTrue(html.contains("<span>Search</span>"));

        assertTrue(chat.contains("const providerRows = Array.isArray(data.webProviders) ? data.webProviders : [];"));
        assertTrue(chat.contains("const providerRuntime = data.providerRuntime || {};"));
        assertTrue(chat.contains("const failSoftLadder = data.failSoftLadder || {};"));
        assertTrue(chat.contains("const searchEnabled = providerRows.filter((item) => item?.hasKey === true).length;"));
        assertTrue(chat.contains("const searchStatus = providerRuntime.status === 'WARN' || failSoftLadder.status === 'WARN' || providerRows.some((item) => item?.status === 'WARN') ? 'WARN' : (providerRuntime.status || failSoftLadder.status || (searchEnabled > 0 ? 'OK' : 'WARN'));"));
        assertTrue(chat.contains("const searchDetail = `enabled:${searchEnabled}/${providerRows.length} timeouts:${providerRuntime.awaitTimeoutCount ?? 0} cancels:${providerRuntime.cancelSuppressedCount ?? 0} cache:${failSoftLadder.cacheOnlyMergedCount ?? 0} vector:${failSoftLadder.vectorFallbackUsed ? 'yes' : 'no'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('search', searchStatus, searchDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('search', 'WARN', 'search_runtime_unknown');"));

        assertFalse(chat.contains("providerRuntime.raw"));
        assertFalse(chat.contains("failSoftLadder.raw"));
        assertFalse(chat.contains("webProviders.innerHTML"));
    }

    @Test
    void chatTopBarShowsFailSoftLadderHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"ladder\""));
        assertTrue(html.contains("<span>Ladder</span>"));

        assertTrue(chat.contains("const ladderStatus = failSoftLadder.status || (failSoftLadder.poolSafeEmpty ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const ladderDetail = `out:${failSoftLadder.outCount ?? 0} tracePool:${failSoftLadder.tracePoolSize ?? 0} rescue:${failSoftLadder.rescueMergeUsed ? 'yes' : 'no'} trigger:${failSoftLadder.starvationFallbackTrigger || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('ladder', ladderStatus, ladderDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('ladder', 'WARN', 'failsoft_ladder_unknown');"));

        assertFalse(chat.contains("failSoftLadder.query"));
        assertFalse(chat.contains("failSoftLadder.rawResults"));
    }

    @Test
    void chatTopBarShowsTraceSnapshotHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"trace\""));
        assertTrue(html.contains("<span>Trace</span>"));

        assertTrue(chat.contains("const traceSnapshotHealth = data.traceSnapshotHealth || {};"));
        assertTrue(chat.contains("const traceStatus = traceSnapshotHealth.status || (traceSnapshotHealth.available ? 'OK' : 'WARN');"));
        assertTrue(chat.contains("const traceDetail = `summaries:${traceSnapshotHealth.summaryCount ?? 0} entries:${traceSnapshotHealth.latestTraceEntryCount ?? 0} events:${traceSnapshotHealth.latestEventCount ?? 0} error:${traceSnapshotHealth.latestErrorPresent ? 'yes' : 'no'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('trace', traceStatus, traceDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('trace', 'WARN', 'trace_snapshot_unknown');"));

        assertFalse(chat.contains("traceSnapshotHealth.id"));
        assertFalse(chat.contains("traceSnapshotHealth.path"));
        assertFalse(chat.contains("traceSnapshotHealth.errorBody"));
    }

    @Test
    void chatTopBarShowsDebugAiMetricsHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"metrics\""));
        assertTrue(html.contains("<span>Metrics</span>"));

        assertTrue(chat.contains("const debugAiMetrics = data.debugAiMetrics || {};"));
        assertTrue(chat.contains("const metricsStatus = debugAiMetrics.status || (Number(debugAiMetrics.errorEvents || 0) > 0 || Number(debugAiMetrics.warnEvents || 0) > 0 ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const metricsDetail = `events:${debugAiMetrics.totalEvents ?? 0} warn:${debugAiMetrics.warnEvents ?? 0} error:${debugAiMetrics.errorEvents ?? 0} top:${debugAiMetrics.topTile || 'none'} fail:${debugAiMetrics.topFailureClass || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('metrics', metricsStatus, metricsDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('metrics', 'WARN', 'debug_ai_metrics_unknown');"));

        assertFalse(chat.contains("debugAiMetrics.rawEvent"));
        assertFalse(chat.contains("debugAiMetrics.rawQuery"));
        assertFalse(chat.contains("debugAiMetrics.rawPayload"));
    }

    @Test
    void chatTopBarShowsMemoryGateHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"memory\""));
        assertTrue(html.contains("<span>Memory</span>"));

        assertTrue(chat.contains("const memoryGate = data.memoryGate || {};"));
        assertTrue(chat.contains("const memoryStatus = memoryGate.status || (Number(memoryGate.quarantined || 0) > 0 || Number(memoryGate.stale || 0) > 0 ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const memoryDetail = `active:${memoryGate.active ?? 0}/${memoryGate.total ?? 0} pending:${memoryGate.pending ?? 0} quarantine:${memoryGate.quarantined ?? 0} stale:${memoryGate.stale ?? 0}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('memory', memoryStatus, memoryDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('memory', 'WARN', 'memory_gate_unknown');"));

        assertFalse(chat.contains("memoryGate.raw"));
        assertFalse(chat.contains("memoryGate.snippet"));
        assertFalse(chat.contains("memoryGate.prompt"));
    }

    @Test
    void chatTopBarShowsRuntimeLaneHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"lanes\""));
        assertTrue(html.contains("<span>Lanes</span>"));

        assertTrue(chat.contains("const laneRows = Array.isArray(data.lanes) ? data.lanes : [];"));
        assertTrue(chat.contains("const disabledLaneRows = laneRows.filter((item) => item?.status === 'DISABLED' || item?.enabled === false);"));
        assertTrue(chat.contains("const laneStatus = disabledLaneRows.length > 0 ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const laneDetail = `enabled:${laneRows.length - disabledLaneRows.length}/${laneRows.length} disabled:${disabledLaneRows.length} reason:${disabledLaneRows[0]?.disabledReason || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('lanes', laneStatus, laneDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('lanes', 'WARN', 'runtime_lanes_unknown');"));

        assertFalse(chat.contains("laneRows.innerHTML"));
        assertFalse(chat.contains("lanes.raw"));
    }

    @Test
    void chatTopBarShowsStrategyPerformanceHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"strategy\""));
        assertTrue(html.contains("<span>Strategy</span>"));

        assertTrue(chat.contains("const strategyRows = Array.isArray(data.strategyPerformances) ? data.strategyPerformances : [];"));
        assertTrue(chat.contains("const hotspotRows = Array.isArray(data.hotspotDistribution) ? data.hotspotDistribution : [];"));
        assertTrue(chat.contains("const strategyStatus = strategyRows.length > 0 || hotspotRows.length > 0 ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const strategyTop = strategyRows[0]?.strategyName || strategyRows[0]?.strategy || hotspotRows[0]?.hotspot || hotspotRows[0]?.label || hotspotRows[0]?.name || 'none';"));
        assertTrue(chat.contains("const strategyDetail = `strategies:${strategyRows.length} hotspots:${hotspotRows.length} top:${strategyTop}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('strategy', strategyStatus, strategyDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('strategy', 'WARN', 'strategy_runtime_unknown');"));

        assertFalse(chat.contains("strategyRows.innerHTML"));
        assertFalse(chat.contains("strategyRows.raw"));
        assertFalse(chat.contains("hotspotRows.raw"));
    }

    @Test
    void chatTopBarShowsRecentFailureHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"failures\""));
        assertTrue(html.contains("<span>Failures</span>"));

        assertTrue(chat.contains("const failureRows = Array.isArray(data.recentFailures) ? data.recentFailures : [];"));
        assertTrue(chat.contains("const failureStatus = failureRows.length > 0 ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const failureDetail = `recent:${failureRows.length} class:${failureRows[0]?.failureClass || failureRows[0]?.classification || failureRows[0]?.reason || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('failures', failureStatus, failureDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('failures', 'WARN', 'recent_failures_unknown');"));

        assertFalse(chat.contains("failureRows.innerHTML"));
        assertFalse(chat.contains("failureRows.raw"));
        assertFalse(chat.contains("failureRows.stackTrace"));
        assertFalse(chat.contains("failureRows.prompt"));
    }

    @Test
    void chatTopBarShowsQueryTransformerLaneHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"qtx\""));
        assertTrue(html.contains("<span>QTX</span>"));

        assertTrue(chat.contains("const qtxLane = laneRows.find((item) => item?.name === 'queryTransformer') || {};"));
        assertTrue(chat.contains("const qtxStatus = qtxLane.status || (qtxLane.enabled === false ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const qtxDetail = `enabled:${qtxLane.enabled === false ? 'no' : 'yes'} reason:${qtxLane.disabledReason || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('qtx', qtxStatus, qtxDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('qtx', 'WARN', 'query_transformer_unknown');"));

        assertFalse(chat.contains("qtxLane.rawQuery"));
        assertFalse(chat.contains("qtxLane.prompt"));
    }

    @Test
    void chatTopBarShowsCausalProbeHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"causal\""));
        assertTrue(html.contains("<span>Causal</span>"));

        assertTrue(chat.contains("const causalLane = laneRows.find((item) => item?.name === 'causalProbe') || {};"));
        assertTrue(chat.contains("const causalStatus = causalLane.status || (causalLane.enabled === false ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const causalDetail = `dominant:${causalLane.dominantFailure || 'none'} action:${causalLane.action || 'observe_only'} confidence:${causalLane.confidence ?? 0} axes:${causalLane.axisCount ?? 0}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('causal', causalStatus, causalDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('causal', 'WARN', 'causal_probe_unknown');"));

        assertFalse(chat.contains("causalLane.rawQuery"));
        assertFalse(chat.contains("causalLane.rawPrompt"));
        assertFalse(chat.contains("causalLane.stackTrace"));
    }

    @Test
    void chatTopBarShowsRetrieverLaneHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"retrievers\""));
        assertTrue(html.contains("<span>Retrievers</span>"));

        assertTrue(chat.contains("const retrieverLaneNames = ['webSearch', 'vectorSearch', 'kgSearch'];"));
        assertTrue(chat.contains("const retrieverRows = laneRows.filter((item) => retrieverLaneNames.includes(item?.name));"));
        assertTrue(chat.contains("const disabledRetrieverRows = retrieverRows.filter((item) => item?.status === 'DISABLED' || item?.enabled === false);"));
        assertTrue(chat.contains("const retrieverStatus = disabledRetrieverRows.length > 0 ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const retrieverDetail = `enabled:${retrieverRows.length - disabledRetrieverRows.length}/${retrieverRows.length} first:${disabledRetrieverRows[0]?.name || 'none'} reason:${disabledRetrieverRows[0]?.disabledReason || 'none'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('retrievers', retrieverStatus, retrieverDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('retrievers', 'WARN', 'retriever_lanes_unknown');"));

        assertFalse(chat.contains("retrieverRows.rawQuery"));
        assertFalse(chat.contains("retrieverRows.rawPrompt"));
    }

    @Test
    void chatTopBarShowsCircuitBreakerHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"breaker\""));
        assertTrue(html.contains("<span>Breaker</span>"));

        assertTrue(chat.contains("const breakerLane = laneRows.find((item) => item?.name === 'circuitBreaker') || {};"));
        assertTrue(chat.contains("const breakerStatus = breakerLane.status || (breakerLane.enabled === false ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const breakerDetail = `state:${breakerLane.disabledReason || 'closed'} enabled:${breakerLane.enabled === false ? 'no' : 'yes'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('breaker', breakerStatus, breakerDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('breaker', 'WARN', 'circuit_breaker_unknown');"));

        assertFalse(chat.contains("breakerLane.rawError"));
        assertFalse(chat.contains("breakerLane.stackTrace"));
    }

    @Test
    void chatTopBarShowsLiveStreamHeartbeatFromCurrentSseEvents() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"liveStream\""));
        assertTrue(html.contains("<span>Live</span>"));

        assertTrue(chat.contains("function renderLiveDebugHeartbeat(partial = {}) {"));
        assertTrue(chat.contains("const streamStatus = String(partial.streamStatus || 'unknown');"));
        assertTrue(chat.contains("const streamContext = String(partial.streamContext || 'none');"));
        assertTrue(chat.contains("const liveStatus = /^(final|done|complete)$/i.test(streamStatus) ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const liveDetail = `stream:${streamStatus} context:${streamContext}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('liveStream', liveStatus, liveDetail);"));
        assertTrue(chat.contains("renderLiveDebugHeartbeat({"));
        assertTrue(chat.contains("setDebugHeartbeatField('liveStream', 'WARN', 'live_stream_unknown');"));

        assertFalse(chat.contains("partial.raw"));
        assertFalse(chat.contains("partial.prompt"));
    }

    @Test
    void chatTopBarShowsDefaultModelWaitHeartbeatFromCurrentSseEvents() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"modelWait\""));
        assertTrue(html.contains("<span>Wait</span>"));

        assertTrue(chat.contains("const waitSource = [partial.streamStatus, partial.streamContext, partial.answerMode, partial.model, partial.modelBadge]"));
        assertTrue(chat.contains("const waitReason = /waiting[_-]for[_-]default[_-]model/i.test(waitSource) ? 'waiting_for_default_model' : 'none';"));
        assertTrue(chat.contains("const waitStatus = waitReason === 'none' && /^(final|done|complete)$/i.test(streamStatus) ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const waitDetail = `default:${waitReason} stream:${streamStatus}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('modelWait', waitStatus, waitDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('modelWait', 'WARN', 'model_wait_unknown');"));

        assertFalse(chat.contains("waitSource.rawQuery"));
        assertFalse(chat.contains("waitSource.Authorization"));
    }

    @Test
    void chatTopBarShowsTimeoutHeartbeatFromCurrentSseEvents() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"timeout\""));
        assertTrue(html.contains("<span>Timeout</span>"));

        assertTrue(chat.contains("const timeoutReason = /timeout/i.test(waitSource) ? 'timeout' : /fallback/i.test(waitSource) ? 'fallback' : /error/i.test(waitSource) ? 'error' : 'none';"));
        assertTrue(chat.contains("const timeoutStatus = timeoutReason === 'none' ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const timeoutDetail = `reason:${timeoutReason} stream:${streamStatus}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('timeout', timeoutStatus, timeoutDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('timeout', 'WARN', 'timeout_unknown');"));

        assertFalse(chat.contains("timeoutSource.rawQuery"));
        assertFalse(chat.contains("timeoutSource.Authorization"));
    }

    @Test
    void chatTopBarShowsCancellationHeartbeatFromCurrentSseEvents() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"cancel\""));
        assertTrue(html.contains("<span>Cancel</span>"));

        assertTrue(chat.contains("const cancelReason = /cancel/i.test(waitSource) ? 'cancelled' : 'none';"));
        assertTrue(chat.contains("const cancelStatus = cancelReason === 'none' ? 'OK' : 'WARN';"));
        assertTrue(chat.contains("const cancelDetail = `reason:${cancelReason} stream:${streamStatus}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('cancel', cancelStatus, cancelDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('cancel', 'WARN', 'cancel_state_unknown');"));

        assertFalse(chat.contains("cancelSource.rawQuery"));
        assertFalse(chat.contains("cancelSource.Authorization"));
    }

    @Test
    void chatTopBarShowsAnswerOutputHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"answer\""));
        assertTrue(html.contains("<span>Answer</span>"));

        assertTrue(chat.contains("const answerOutput = data.answerOutput || {};"));
        assertTrue(chat.contains("const answerStatus = answerOutput.status || (answerOutput.emptyAnswerGuardTriggered || answerOutput.blankBaseFallback ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const answerDetail = `mode:${answerOutput.answerMode || 'none'} guard:${answerOutput.emptyAnswerGuardTriggered ? 'yes' : 'no'} fallback:${answerOutput.emptyAnswerFallback || 'none'} docs:${answerOutput.evidenceDocs ?? 0}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('answer', answerStatus, answerDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('answer', 'WARN', 'answer_output_unknown');"));

        assertFalse(chat.contains("answerOutput.rawText"));
        assertFalse(chat.contains("answerOutput.rawPrompt"));
        assertFalse(chat.contains("answerOutput.Authorization"));
    }

    @Test
    void chatTopBarShowsComputerUseHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"computer\""));
        assertTrue(html.contains("<span>Computer</span>"));

        assertTrue(chat.contains("const computerRow = externalRows.find((item) => item?.service === 'computer-use') || {};"));
        assertTrue(chat.contains("const computerStatus = computerRow.status || (computerRow.reachable && !computerRow.stale ? 'OK' : 'WARN');"));
        assertTrue(chat.contains("const computerDetail = `apps:${computerRow.appCount ?? 0} windows:${computerRow.targetableWindowCount ?? computerRow.windowCount ?? 0} stale:${computerRow.stale ? 'yes' : 'no'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('computer', computerStatus, computerDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('computer', 'WARN', 'computer_use_unknown');"));

        assertFalse(chat.contains("storesAppNames: true"));
        assertFalse(chat.contains("storesWindowTitles: true"));
    }

    @Test
    void chatTopBarShowsNoetherHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"noether\""));
        assertTrue(html.contains("<span>Noether</span>"));

        assertTrue(chat.contains("const noetherRow = externalRows.find((item) => item?.service === 'noether') || {};"));
        assertTrue(chat.contains("const noetherStatus = noetherRow.status || (noetherRow.responded ? 'OK' : 'WARN');"));
        assertTrue(chat.contains("const noetherDetail = `wait:${noetherRow.waiting ? 'yes' : 'no'} reply:${noetherRow.responded ? 'yes' : 'no'} kind:${noetherRow.lastMessageKind || 'unknown'}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('noether', noetherStatus, noetherDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('noether', 'WARN', 'noether_status_unknown');"));

        assertFalse(chat.contains("agentId"));
        assertFalse(chat.contains("agentIdHash"));
    }

    @Test
    void chatTopBarShowsExternalEvidenceFreshnessHeartbeat() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"freshness\""));
        assertTrue(html.contains("<span>Fresh</span>"));

        assertTrue(chat.contains("const staleEvidenceRows = externalRows.filter((item) => item?.stale === true);"));
        assertTrue(chat.contains("const evidenceAgeMinutes = externalRows.map((item) => Number(item?.ageMinutes)).filter(Number.isFinite);"));
        assertTrue(chat.contains("const maxEvidenceAgeMinutes = evidenceAgeMinutes.length ? Math.max(...evidenceAgeMinutes) : 0;"));
        assertTrue(chat.contains("const freshnessStatus = externalRows.length === 0 || staleEvidenceRows.length > 0 ? 'WARN' : 'OK';"));
        assertTrue(chat.contains("const freshnessDetail = `stale:${staleEvidenceRows.length}/${externalRows.length} maxAge:${Math.round(maxEvidenceAgeMinutes)}m`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('freshness', freshnessStatus, freshnessDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('freshness', 'WARN', 'freshness_unknown');"));

        assertFalse(chat.contains("SUPABASE_ACCESS_TOKEN"));
        assertFalse(chat.contains("rawSecret"));
    }

    @Test
    void chatTopBarShowsPatchDropHeartbeatFromPipelineHealth() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-debug-heartbeat-field=\"patchdrop\""));
        assertTrue(html.contains("<span>PatchDrop</span>"));

        assertTrue(chat.contains("const patchDropRow = externalRows.find((item) => item?.service === 'patchdrop') || {};"));
        assertTrue(chat.contains("const patchDropStatus = patchDropRow.status || ((patchDropRow.activeTopLevelPatchCount ?? 0) > 0 || (patchDropRow.nestedProducerPatchCount ?? 0) > 0 || (patchDropRow.reportOnlyPendingCount ?? 0) > 0 ? 'WARN' : 'OK');"));
        assertTrue(chat.contains("const patchDropDetail = `top:${patchDropRow.activeTopLevelPatchCount ?? 0} nested:${patchDropRow.nestedProducerPatchCount ?? 0} report:${patchDropRow.reportOnlyPendingCount ?? 0}`;"));
        assertTrue(chat.contains("setDebugHeartbeatField('patchdrop', patchDropStatus, patchDropDetail);"));
        assertTrue(chat.contains("setDebugHeartbeatField('patchdrop', 'WARN', 'patchdrop_queue_unknown');"));

        assertFalse(chat.contains("patchBody"));
        assertFalse(chat.contains("rawPatch"));
    }

    @Test
    void debugFxRendererDisplaysLabelsWithTextNodesOnly() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const labels = signal.labels || {};"));
        assertTrue(chat.contains("const signal = payload.debugFxSignal || payload.debug_fx || {};"));
        assertTrue(chat.contains("if (type === \"debug_fx\")"));
        assertTrue(chat.contains("handleDebugFxSignal(payload);"));
        assertTrue(chat.contains("labelsHolder.dataset.role = \"debug-fx-labels\";"));
        assertTrue(chat.contains("const labelEntries = Object.entries(labels).filter(([key, value]) => key && value != null);"));
        assertTrue(chat.contains("labelEntries.slice(0, 8).forEach(([key, value]) => {"));
        assertTrue(chat.contains("labelKey.textContent = `${key}:`;"));
        assertTrue(chat.contains("labelValue.textContent = String(value ?? \"-\");"));
        assertTrue(chat.contains("card.appendChild(labelsHolder);"));
        assertFalse(chat.contains("labelsHolder.innerHTML"));
        assertFalse(chat.contains("insertAdjacentHTML"));
    }

    @Test
    void scoreDeltaRendererDisplaysRedactedSignalLabelsInSignalBar() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const signalLabel = (value) =>"));
        assertTrue(chat.contains("const scoreDeltaContext = (signal) =>"));
        assertTrue(chat.contains("signalLabel(signal.stage)"));
        assertTrue(chat.contains("signalLabel(signal.guard)"));
        assertTrue(chat.contains("signalLabel(signal.clampName)"));
        assertTrue(chat.contains("parts.push(`event:${signal.eventId}`);"));
        assertTrue(chat.contains("scoreDeltaContext: scoreDeltaContext(signal)"));
        assertTrue(chat.contains("const renderScoreDeltaDetail = (signal, target) =>"));
        assertTrue(chat.contains("detail.dataset.role = \"score-delta-detail\";"));
        assertTrue(chat.contains("appendScoreDeltaLabel(detail, \"raw\", signal.rawScoreDelta);"));
        assertTrue(chat.contains("appendScoreDeltaLabel(detail, \"stage\", signal.stage);"));
        assertTrue(chat.contains("appendScoreDeltaLabel(detail, \"guard\", signal.guard);"));
        assertTrue(chat.contains("appendScoreDeltaLabel(detail, \"clamp\", signal.clampName);"));
        assertTrue(chat.contains("appendScoreDeltaLabel(detail, \"event\", signal.eventId);"));
        assertTrue(chat.contains("renderScoreDeltaDetail(signal, bubble?.parentElement || dom.chatMessages);"));
        assertTrue(orch.contains("function scoreValue(value, context)"));
        assertTrue(orch.contains("const contextText = text(context, \"\");"));
        assertTrue(orch.contains("return contextText ? `${base} | ${contextText}` : base;"));
        assertTrue(orch.contains("setField(root, \"scoreDelta\", scoreValue(partial.scoreDelta, partial.scoreDeltaContext));"));
        assertFalse(orch.contains("scoreDeltaContext.innerHTML"));
        assertFalse(chat.contains("scoreDeltaDetail.innerHTML"));
    }

    @Test
    void statusSignalRendererDisplaysTimingAndCancelStateInSignalBar() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const statusContext = (signal) =>"));
        assertTrue(chat.contains("signal.remainingMs"));
        assertTrue(chat.contains("signal.tookMs"));
        assertTrue(chat.contains("signal.cancelled === true"));
        assertTrue(chat.contains("streamContext: waitReason || statusContext(signal)"));
        assertTrue(orch.contains("function streamValue(value, context)"));
        assertTrue(orch.contains("setField(root, \"streamStatus\", streamValue(streamStatus, partial.streamContext));"));
        assertTrue(orch.contains("return contextText ? `${base} | ${contextText}` : base;"));
    }

    @Test
    void streamingSignalBarShowsClientElapsedWhileWaitingForModel() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const streamStartedAt = nowMs();"));
        assertTrue(chat.contains("const streamHeartbeatContext = () =>"));
        assertTrue(chat.contains("client-wait:${elapsedMs}ms"));
        assertTrue(chat.contains("let streamHeartbeatTimer = null;"));
        assertTrue(chat.contains("const stopStreamHeartbeat = () =>"));
        assertTrue(chat.contains("window.clearInterval(streamHeartbeatTimer);"));
        assertTrue(chat.contains("streamHeartbeatTimer = window.setInterval(() => {"));
        assertTrue(chat.contains("const streamHeartbeatStatus = payload?.attach ? \"attaching\" : \"streaming\";"));
        assertTrue(chat.contains("streamStatus: streamHeartbeatStatus"));
        assertTrue(chat.contains("streamContext: streamHeartbeatContext()"));
        assertTrue(chat.contains("stopStreamHeartbeat();"));
    }

    @Test
    void transformerDefaultModelWaitKeepsSignalBarOutOfUnknownState() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("if (value === undefined) return;"));
        assertTrue(chat.contains("const defaultModelWaitReason = (reason) =>"));
        assertTrue(chat.contains("const modelWaitReason = defaultModelWaitReason(modelReason);"));
        assertTrue(chat.contains("streamStatus: modelWaitReason ? \"waiting_for_default_model\" : undefined"));
        assertTrue(chat.contains("streamContext: modelWaitReason"));
        assertTrue(chat.contains("answerMode: modelWaitReason ? \"MODEL_WAIT\" : undefined"));
    }

    @Test
    void operatorDebugLinksExposePipelineStatusFromChatUi() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-menu-action=\"open-pipeline-status\""));
        assertTrue(html.contains("href=\"/admin/pipeline-status\""));
        assertTrue(html.contains("<span>Pipeline Status</span>"));
        assertTrue(chat.contains("pipelineStatus: '/admin/pipeline-status'"));
        assertTrue(chat.contains("case 'open-pipeline-status':"));
        assertTrue(chat.contains("openOpsWindow('pipelineStatus')"));
    }

    @Test
    void debugEventsFingerprintSummaryShowsWindowAgeAndTotalSuppressed() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/debug-events.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("<th class=\"text-left p-2\">windowAgeMs</th>"));
        assertTrue(html.contains("<th class=\"text-left p-2\">totalSuppressed</th>"));
        assertTrue(html.contains("appendTextCell(tr, fp.windowAgeMs, 'mono');"));
        assertTrue(html.contains("appendTextCell(tr, fp.totalSuppressed, 'mono');"));
    }

    @Test
    void statusSignalDefaultModelWaitSetsModeWithoutTransformer() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const waitReason = defaultModelWaitReason(signal.code || signal.message || payload.data);"));
        assertTrue(chat.contains("streamContext: waitReason || statusContext(signal)"));
        assertTrue(chat.contains("answerMode: waitReason ? \"MODEL_WAIT\" : undefined"));
        assertTrue(chat.contains("modelActive: waitReason ? true : undefined"));
        assertTrue(chat.contains("modelBadge: waitReason ? \"Model: running\" : undefined"));
        assertTrue(chat.contains("resilienceActive: Boolean(waitReason) || signal.cancelled === true"));
        assertTrue(chat.contains("if (waitReason) {"));
        assertTrue(chat.contains("route: signal.phase || signal.code || \"status\""));
        assertTrue(chat.contains("answerMode: \"MODEL_WAIT\""));
        assertTrue(chat.contains("pipelineSnapshot: {}"));
    }

    @Test
    void transformerDefaultModelWaitRendersRouteModeBeforeFinal() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const pipeline = payload.pipelineSnapshot || payload.pipeline_snapshot || {};"));
        assertTrue(chat.contains("const hasLearningContext = payload.learningContext && Object.keys(payload.learningContext).length > 0;"));
        assertTrue(chat.contains("const transformerBadges = hasBlocks ? deriveTransformerBadges(blocks) : {};"));
        assertTrue(chat.contains("if (hasBlocks && (transformerBadges.answerMode || Object.keys(pipeline).length > 0 || hasLearningContext)) {"));
        assertTrue(chat.contains("answerMode: transformerBadges.answerMode || pipeline.answerMode"));
        assertTrue(chat.contains("route: pipeline.route || meta.status || \"transformer\""));
        assertTrue(chat.contains("pipelineSnapshot: pipeline"));
    }

    @Test
    void transformerModelReasonUpdatesTimeoutHeartbeat() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const liveHeartbeatModelReason = transformerBadges.model || transformerBadges.modelBadge || pipeline.failureClass || pipeline.disabledReason || pipeline.answerMode;"));
        assertTrue(chat.contains("streamStatus: meta.status || transformerBadges.streamStatus || \"transformer\""));
        assertTrue(chat.contains("streamContext: transformerBadges.streamContext || liveHeartbeatModelReason || transformerBadges.answerMode || pipeline.answerMode"));
        assertTrue(chat.contains("model: transformerBadges.model"));
        assertTrue(chat.contains("modelBadge: transformerBadges.modelBadge"));

        assertFalse(chat.contains("liveHeartbeatModelReason.rawQuery"));
        assertFalse(chat.contains("liveHeartbeatModelReason.Authorization"));
    }

    @Test
    void supabaseTransformerBlockReasonUpdatesSignalBarBadge() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const supabaseBlock = findBlock(\"supabase\");"));
        assertTrue(chat.contains("const supabaseReason = supabaseBlock ? blockReason(supabaseBlock) : null;"));
        assertTrue(chat.contains("supabaseBadge: supabaseBlock ? `Supabase: ${supabaseReason || \"pending\"}` : \"Supabase\""));
        assertTrue(orch.contains("if (has(\"supabaseActive\") || has(\"supabaseBadge\")) {"));
        assertTrue(orch.contains("setBadge(root, \"supabase\", has(\"supabaseActive\") ? Boolean(partial.supabaseActive) : undefined, partial.supabaseBadge || \"Supabase\");"));
        assertFalse(chat.contains("supabaseRaw"));
        assertFalse(orch.contains("supabaseRaw"));
    }

    @Test
    void finalEventDoesNotOverwriteTransformerModelDiagnosticInSignalBar() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("let transformerModelDiagnosticVisible = false;"));
        assertTrue(chat.contains("let transformerModelDiagnosticLabel = null;"));
        assertTrue(chat.contains("const modelFallbackDiagnostic = (model, mode) =>"));
        assertTrue(chat.contains("const isGenericModelDiagnostic = (value) =>"));
        assertTrue(chat.contains("if (transformerBadges.model) transformerModelDiagnosticVisible = true;"));
        assertTrue(chat.contains("if (finalModelDiagnostic) {"));
        assertTrue(chat.contains("transformerModelDiagnosticLabel = finalModelDiagnostic;"));
        assertTrue(chat.contains("if (meta?.status === \"final\" && transformerModelDiagnosticLabel && isGenericModelDiagnostic(transformerBadges.model)) {"));
        assertTrue(chat.contains("transformerBadges.model = transformerModelDiagnosticLabel;"));
        assertTrue(chat.contains("transformerBadges.modelBadge = `Model: ${transformerModelDiagnosticLabel}`;"));
        assertTrue(chat.contains("model: finalModelDiagnostic || (transformerModelDiagnosticVisible ? undefined : model),"));
        assertTrue(chat.contains("...(finalModelDiagnostic ? { modelActive: true, modelBadge: `Model: ${finalModelDiagnostic}` } : {}),"));
        assertFalse(chat.contains("streamStatus: \"final\",\n            plan: pipeline.planId || pipeline.plan || null,\n            model,\n            answerMode"));
    }

    @Test
    void orchestrationSignalBarTreatsEventsAsPartialUpdates() throws Exception {
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("const has = (key) => Object.prototype.hasOwnProperty.call(partial, key);"));
        assertTrue(orch.contains("const previousFieldValue = (name) =>"));
        assertTrue(orch.contains("if (has(\"streamStatus\") || has(\"streamContext\")) {"));
        assertTrue(orch.contains("const streamStatus = has(\"streamStatus\") && text(partial.streamStatus, \"\") ? partial.streamStatus : previousFieldValue(\"streamStatus\");"));
        assertTrue(orch.contains("setField(root, \"streamStatus\", streamValue(streamStatus, partial.streamContext));"));
        assertTrue(orch.contains("if (has(\"scoreDelta\") || has(\"scoreDeltaContext\")) {"));
        assertTrue(orch.contains("if (has(\"modelActive\") || has(\"modelBadge\")) {"));
        assertTrue(orch.contains("setBadge(root, \"model\", has(\"modelActive\") ? Boolean(partial.modelActive) : undefined, partial.modelBadge);"));
        assertTrue(orch.contains("if (active !== undefined) el.dataset.active = active ? \"true\" : \"false\";"));
        assertFalse(orch.contains("setBadge(root, \"model\", Boolean(partial.modelActive), partial.modelBadge || \"Model\");"));
    }

    @Test
    void sessionSignalRendererExposesSessionIdInSignalBar() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const sessionTraceLabel = (sessionId) =>"));
        assertTrue(chat.contains("traceTurn: sessionTraceLabel(sid)"));
        assertTrue(chat.contains("updateOrchestrationSignalBar({\n        traceTurn: sessionTraceLabel(sid)\n      });"));
        assertFalse(chat.contains("streamStatus: \"session\""));
        assertTrue(chat.contains("traceTurnId: traceTurnId || pipeline.traceTurnId || sessionTraceLabel(sid)"));
    }

    @Test
    void traceSignalRendererDisplaysNoHtmlDiagnosticsWithTextNodesOnly() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const renderTraceSignalDetail = (signal, pipeline, target) =>"));
        assertTrue(chat.contains("detail.dataset.role = \"trace-signal-detail\";"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"trace\", signal.traceIdHash || pipeline.traceTurnId);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"request\", signal.requestIdHash);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"session\", signal.sessionIdHash);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"events\", signal.eventCount);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"failure\", signal.failureClass || pipeline.failureClass);"));
        assertTrue(chat.contains("appendTraceSignalLabel(detail, \"reason\", signal.reasonCode || pipeline.disabledReason);"));
        assertTrue(chat.contains("Object.entries(signal.stageCounts || {})"));
        assertTrue(chat.contains("renderTraceSignalDetail(signal, pipeline, bubble?.parentElement || dom.chatMessages);"));
        assertTrue(chat.contains("traceValue.textContent = safeValue;"));
        assertFalse(chat.contains("traceSignalDetail.innerHTML"));
    }

    @Test
    void finalEventFallsBackToRagUsedForAnswerModeCard() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const finalAnswerMode = (payload, model) =>"));
        assertTrue(chat.contains("payload.ragUsed ?? payload.rag_used"));
        assertTrue(chat.contains("return ragUsed === true ? \"rag\" : ragUsed === false ? \"chat\" : null;"));
        assertTrue(chat.contains("const inferredMode = finalAnswerMode(payload, model);"));
        assertTrue(chat.contains("const inferredMode = finalAnswerMode(res, model);"));
    }

    @Test
    void finalEventInfersFallbackModeFromFallbackModelLabels() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const modelLower = String(model || \"\").toLowerCase();"));
        assertTrue(chat.contains("if (modelLower.includes(\"fallback:evidence\")) return \"FALLBACK_EVIDENCE\";"));
        assertTrue(chat.contains("if (modelLower.includes(\"fallback-local\") || modelLower.includes(\"fallback:local\")) return \"FALLBACK_LOCAL\";"));
        assertTrue(chat.contains("if (modelLower.includes(\"fallback\")) return \"FALLBACK\";"));
        assertTrue(chat.contains("if (upper === \"FALLBACK_LOCAL\") {"));
        assertTrue(chat.contains("return { text: \"fallback: local\""));
        assertTrue(chat.contains("if (upper === \"FALLBACK\") {"));
        assertTrue(chat.contains("return { text: \"fallback\""));
    }

    @Test
    void finalSignalBarPrefersServerPipelineAnswerModeOverGenericInference() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const finalMode = normalizedAnswerMode(pipeline.answerMode || pipeline.answer_mode || pipeline.mode) || inferredMode;"));
        assertTrue(chat.contains("answerMode: finalMode"));
        assertTrue(chat.contains("upsertAnswerModeBadge(bubble.parentElement, finalMode);"));
        assertTrue(chat.contains("persistAnswerModeBadge(sid || state.currentSessionId, finalMode);"));
        assertTrue(chat.contains("upsertSessionModeBadgeInList(sid || state.currentSessionId, finalMode, traceTurnId);"));
        assertFalse(chat.contains("answerMode: inferredMode || pipeline.answerMode"));
        assertFalse(chat.contains("const finalMode = pipeline.answerMode || inferredMode;"));
    }

    @Test
    void finalSignalBarIgnoresUnknownPipelineModeWhenFallbackModelIsKnown() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const normalizedAnswerMode = (mode) => {"));
        assertTrue(chat.contains("if (UNKNOWN_ANSWER_MODES.has(raw.toLowerCase())) return null;"));
        assertTrue(chat.contains("const explicit = normalizedAnswerMode(payload?.answerMode || payload?.answer_mode || payload?.mode);"));
        assertTrue(chat.contains("const finalMode = normalizedAnswerMode(pipeline.answerMode || pipeline.answer_mode || pipeline.mode) || inferredMode;"));
        assertTrue(chat.contains("const syncMode = normalizedAnswerMode(serverPipeline.answerMode || serverPipeline.answer_mode || serverPipeline.mode) || inferredMode;"));
        assertFalse(chat.contains("const syncMode = serverPipeline.answerMode || inferredMode;"));
    }

    @Test
    void syncFinalizerUpdatesSignalBarAndRouteModeFromResponseMetadata() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("const syncTraceTurnId = res?.traceTurnId || res?.trace_turn_id || res?.lastTraceTurnId || res?.last_trace_turn_id || null;"));
        assertTrue(chat.contains("const syncEvidence = Array.isArray(res?.evidence) ? res.evidence : [];"));
        assertTrue(chat.contains("const serverPipeline = res?.pipelineSnapshot || res?.pipeline_snapshot || {};"));
        assertTrue(chat.contains("const syncMode = normalizedAnswerMode(serverPipeline.answerMode || serverPipeline.answer_mode || serverPipeline.mode) || inferredMode;"));
        assertTrue(chat.contains("const syncStartedAt = nowMs();"));
        assertTrue(chat.contains("const syncTookMs = Math.max(0, Math.round(nowMs() - syncStartedAt));"));
        assertTrue(chat.contains("streamContext: syncFinalContext(syncTookMs, syncMode)"));
        assertTrue(chat.contains("parts.push(`sync-took:${safeMs}ms`);"));
        assertTrue(chat.contains("parts.push(\"slow-response\");"));
        assertTrue(chat.contains("renderEvidenceRail(syncEvidence, finalWrap);"));
        assertTrue(chat.contains("const syncPipeline = {"));
        assertTrue(chat.contains("...serverPipeline"));
        assertTrue(chat.contains("answerMode: syncMode"));
        assertTrue(chat.contains("finalContextCount: serverPipeline.finalContextCount ?? syncEvidence.length"));
        assertTrue(chat.contains("updateOrchestrationSignalBar({"));
        assertTrue(chat.contains("traceTurnId: syncTraceTurnId || sessionTraceLabel(res?.sessionId || state.currentSessionId)"));
        assertTrue(chat.contains("renderPlanModeCard({"));
        assertTrue(chat.contains("planId: serverPipeline.planId || serverPipeline.plan || \"sync\""));
        assertTrue(chat.contains("route: serverPipeline.route || (res?.ragUsed === true ? \"rag\" : \"chat\")"));
        assertTrue(chat.contains("upsertAnswerModeBadge(messageBubble.parentElement, syncMode);"));
        assertTrue(chat.contains("upsertAnswerModeBadge(lastWrap, syncMode);"));
        assertTrue(chat.contains("learningContext: res?.learningContext || {}"));
        assertTrue(chat.contains("pipelineSnapshot: syncPipeline"));
        assertFalse(chat.contains("pipelineSnapshot: raw"));
        assertFalse(chat.contains("streamContext: answer"));
    }

    @Test
    void thoughtSignalUpdatesSignalBarWithCountOnlyContext() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("let thoughtEventCount = 0;"));
        assertTrue(chat.contains("thoughtEventCount += 1;"));
        assertTrue(chat.contains("streamStatus: \"thought\""));
        assertTrue(chat.contains("streamContext: `thoughts:${thoughtEventCount}`"));
        assertFalse(chat.contains("streamContext: msg"));
    }

    @Test
    void understandingSignalUpdatesSignalBarWithCountOnlyContext() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("let understandingEventCount = 0;"));
        assertTrue(chat.contains("understandingEventCount += 1;"));
        assertTrue(chat.contains("streamStatus: \"understanding\""));
        assertTrue(chat.contains("streamContext: `understanding:${understandingEventCount}`"));
        assertFalse(chat.contains("streamContext: jsonStr"));
        assertFalse(chat.contains("streamContext: summary"));
    }

    @Test
    void planModeCardDisplaysPipelineQualitySignalsWithTextNodesOnly() throws Exception {
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("const pipeline = meta.pipelineSnapshot || {};"));
        assertTrue(orch.contains("appendLine(holder, \"citation\", ratio(pipeline.citationCoverage));"));
        assertTrue(orch.contains("appendLine(holder, \"finalSigmoid\", ratio(pipeline.finalSigmoid));"));
        assertTrue(orch.contains("valueEl.textContent = text(value, \"-\");"));
        assertFalse(orch.contains("holder.innerHTML"));
    }

    @Test
    void planModeCardDisplaysLearningSummaryPresenceWithoutRawSummary() throws Exception {
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("learning.summaryPresent === true ? \"present\" : \"absent\""));
        assertTrue(orch.contains("appendLine(holder, \"summary\", summaryState);"));
        assertFalse(orch.contains("learningContextSummary"));
        assertFalse(orch.contains("learning.summaryText"));
        assertFalse(orch.contains("learning.summaryHtml"));
        assertFalse(orch.contains("holder.innerHTML"));
    }

    @Test
    void planModeCardDisplaysPipelineCountsAndReasonsWithTextNodesOnly() throws Exception {
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);

        assertTrue(orch.contains("function count(value, fallback = \"-\")"));
        assertTrue(orch.contains("appendLine(holder, \"web\", count(pipeline.webCount));"));
        assertTrue(orch.contains("appendLine(holder, \"vector\", count(pipeline.vectorCount));"));
        assertTrue(orch.contains("appendLine(holder, \"context\", count(pipeline.finalContextCount));"));
        assertTrue(orch.contains("appendLine(holder, \"failure\", pipeline.failureClass || \"none\");"));
        assertTrue(orch.contains("appendLine(holder, \"disabled\", pipeline.disabledReason || \"none\");"));
        assertFalse(orch.contains("pipeline.raw"));
        assertFalse(orch.contains("pipeline.prompt"));
        assertFalse(orch.contains("pipeline.snippet"));
        assertFalse(orch.contains("holder.innerHTML"));
    }

    @Test
    void signalBarDisplaysPipelineRouteContextQualityAndHealth() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/chat-ui.html"), StandardCharsets.UTF_8);
        String orch = Files.readString(Path.of("main/resources/static/js/orchestration-ui.js"), StandardCharsets.UTF_8);
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-orch-field=\"route\""));
        assertTrue(html.contains("<span>Route</span>"));
        assertTrue(html.contains("data-orch-field=\"contextCounts\""));
        assertTrue(html.contains("<span>Context</span>"));
        assertTrue(html.contains("data-orch-field=\"quality\""));
        assertTrue(html.contains("<span>Quality</span>"));
        assertTrue(html.contains("data-orch-field=\"health\""));
        assertTrue(html.contains("<span>Health</span>"));
        assertTrue(orch.contains("function pipelineRouteValue(pipeline, fallbackRoute)"));
        assertTrue(orch.contains("function pipelineContextCountsValue(pipeline)"));
        assertTrue(orch.contains("function pipelineQualityValue(pipeline)"));
        assertTrue(orch.contains("function pipelineHealthValue(pipeline)"));
        assertTrue(orch.contains("const pipeline = partial.pipelineSnapshot || {};"));
        assertTrue(orch.contains("setField(root, \"route\", pipelineRouteValue(pipeline, partial.route), \"-\");"));
        assertTrue(orch.contains("setField(root, \"contextCounts\", pipelineContextCountsValue(pipeline), \"-\");"));
        assertTrue(orch.contains("setField(root, \"quality\", pipelineQualityValue(pipeline), \"-\");"));
        assertTrue(orch.contains("setField(root, \"health\", pipelineHealthValue(pipeline), \"ok\");"));
        assertTrue(chat.contains("pipelineSnapshot: pipeline"));
        assertTrue(chat.contains("pipelineSnapshot: syncPipeline"));
    }

    @Test
    void chatFrontendStripsAssistantReasoningBlocksBeforeRendering() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function stripAssistantReasoningBlocks"));
        assertTrue(chat.contains("assistantReasoningPattern"));
        assertTrue(chat.contains("let suppressAssistantReasoning = false;"));
        assertTrue(chat.contains("filterAssistantReasoningChunk"));
        assertTrue(chat.contains("stripAssistantReasoningBlocks(text)"));
        assertTrue(chat.contains("stripAssistantReasoningBlocks(html)"));
        assertTrue(chat.contains("stripAssistantReasoningBlocks(rawHtml)"));
        assertTrue(chat.contains("appendTextWithBreaks(bubble, filterAssistantReasoningChunk(payload.data || \"\"))"));
        assertFalse(chat.contains("appendTextWithBreaks(bubble, payload.data || \"\")"));
    }

    @Test
    void chatFrontendShowsIntegrityWarningForMojibakeAnswerText() throws Exception {
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String css = Files.readString(Path.of("main/resources/static/css/chat-style.css"), StandardCharsets.UTF_8);

        assertTrue(chat.contains("function looksLikeMojibake"));
        assertTrue(chat.contains("function renderAnswerIntegrityWarning"));
        assertTrue(chat.contains("answer-integrity-warning"));
        assertTrue(chat.contains("output.integrity"));
        assertTrue(chat.contains("looksLikeMojibake(cleanText)"));
        assertTrue(chat.contains("looksLikeMojibake(cleanHtml)"));
        assertTrue(css.contains(".answer-integrity-warning"));
    }

    @Test
    void imagineCommandUsesAsyncJobEndpoint() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String imageUi = Files.readString(Path.of("main/resources/static/js/image-jobs-ui.js"), StandardCharsets.UTF_8);

        assertTrue(js.contains("import { renderImageJobCard, updateImageJobCard, attachImageJobDebug, attachImageJobConfigDebug }"));
        assertTrue(js.contains("fetch(\"/api/image-plugin/jobs\""));
        assertFalse(js.contains("/api/image-plugin/generate"));
        assertTrue(js.contains("headers,"));
        assertTrue(js.contains("...(CSRF.token ? { [CSRF.header]: CSRF.token } : {})"));
        assertTrue(js.contains("const maxClientWaitMs = Math.min(30 * 60 * 1000, Math.max(120000, etaMs + 45000));"));
        assertTrue(js.contains("IMAGE_JOB_STILL_RUNNING_CHECK_STATUS_OR_MANIFEST"));
        assertTrue(js.contains("await attachImageJobDebug(card, id, headers);"));
        assertTrue(js.contains("await attachImageJobConfigDebug(card, headers);"));
        assertFalse(js.contains("content: `[image] ${prompt}`"));
        assertTrue(js.contains("content: \"[image generated]\""));
        assertTrue(imageUi.contains("value.startsWith(\"data:\")"));
        assertTrue(imageUi.contains("export async function attachImageJobDebug"));
        assertTrue(imageUi.contains("export async function attachImageJobConfigDebug"));
        assertTrue(imageUi.contains("/api/diagnostics/image/jobs/"));
        assertTrue(imageUi.contains("/api/diagnostics/image/config"));
        assertTrue(imageUi.contains("config[\"openai.image.enabled\"] === true && config[\"imageServiceAvailable\"] === false"));
        assertTrue(imageUi.contains("[data-image-job-debug]"));
        assertFalse(imageUi.contains("`job ${clean(job.id)}`"));
        assertFalse(imageUi.contains("promptEl.textContent = clean(prompt"));
        assertTrue(imageUi.contains("promptEl.textContent = \"Image request submitted\""));
    }

    @Test
    void chatFrontendDoesNotHardCodeAcademyLocationBiasIntoUserPrompt() throws Exception {
        String js = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
        String orgs = Files.readString(Path.of("main/resources/catalog/orgs.yml"), StandardCharsets.UTF_8);

        assertFalse(js.contains("LOCATION_BIAS_KEY"));
        assertFalse(js.contains("search.locationBias"));
        assertFalse(js.contains("needsDaejeonBias"));
        assertFalse(js.contains("applyLocationBiasIfNeeded"));
        assertFalse(js.contains("biasedText"));
        assertFalse(js.toLowerCase().contains("dwacademy"));
        assertTrue(js.contains("message: text,"));
        assertFalse(orgs.contains("dw_academy"));
        assertFalse(orgs.toLowerCase().contains("dwacademy"));
    }

    @Test
    void httpDebugUiDoesNotExposeRawRequestIdHeader() throws Exception {
        String fetchWrapper = Files.readString(Path.of("main/resources/static/js/fetch-wrapper.js"), StandardCharsets.UTF_8);
        String chat = Files.readString(Path.of("main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

        assertFalse(fetchWrapper.contains("requestId: pick('X-Request-Id')"));
        assertTrue(fetchWrapper.contains("requestIdHash: hashHeaderValue(pick('X-Request-Id'))"));
        assertFalse(chat.contains("requestId=${dbg.requestId}"));
        assertTrue(chat.contains("requestIdHash=${dbg.requestIdHash}"));
    }

    @Test
    void httpDebugUiDoesNotExposeRawUrlQuery() throws Exception {
        String fetchWrapper = Files.readString(Path.of("main/resources/static/js/fetch-wrapper.js"), StandardCharsets.UTF_8);

        assertFalse(fetchWrapper.contains("      url,"));
        assertFalse(fetchWrapper.contains("`${res.status} ${url}`"));
        assertTrue(fetchWrapper.contains("const debugUrl = safeDebugUrl(url);"));
        assertTrue(fetchWrapper.contains("urlPath: debugUrl.path"));
        assertTrue(fetchWrapper.contains("urlHash: debugUrl.hash"));
        assertTrue(fetchWrapper.contains("`[debug][http] ${res.status} ${debugUrl.path}`"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (text != null && needle != null && !needle.isEmpty()) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
        return count;
    }

    private static String cssBlockAfter(String text, String anchor) {
        int start = text.indexOf(anchor);
        if (start < 0) {
            return "";
        }
        int end = text.indexOf("}", start);
        return end < 0 ? text.substring(start) : text.substring(start, end + 1);
    }

    private static String functionBody(String text, String anchor) {
        int start = text.indexOf(anchor);
        if (start < 0) {
            return "";
        }
        int end = text.indexOf("\n  function ", start + anchor.length());
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }
}
