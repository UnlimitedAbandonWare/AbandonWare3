import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPORT_PATH = ROOT / "scripts" / "harmony_pressure_report.py"
SPEC = importlib.util.spec_from_file_location("harmony_pressure_report", REPORT_PATH)
harmony_pressure_report = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(harmony_pressure_report)


class HarmonyPressureReportTest(unittest.TestCase):
    def test_scans_cross_subsystem_and_catch_pressure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "main" / "java" / "com" / "example"
            src.mkdir(parents=True)
            (src / "MixedFlow.java").write_text(
                """
                package com.example;

                import org.aspectj.lang.annotation.Aspect;

                @Aspect
                public class MixedFlow {
                    void run() {
                        try {
                            System.out.println("Overdrive CFVM ExtremeZ");
                        } catch (Exception ex) {
                            int ignored = 1;
                        }
                    }
                }
                """,
                encoding="utf-8",
            )
            (src / "OrderedFlow.java").write_text(
                """
                package com.example;

                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.core.annotation.Order;

                @Aspect
                @Order(10)
                public class OrderedFlow {
                    void run() {
                        try {
                            System.out.println("PromptBuilder HYPERNOVA DPP");
                        } catch (RuntimeException ex) {
                            log.warn("reason={}", ex.toString());
                        }
                        try {
                            System.out.println("Matryoshka Embedding");
                        } catch (RuntimeException ex) {
                            logSuppressed("embedding.stage", ex);
                        }
                        try {
                            System.out.println("OpenAI PromptBuilder");
                        } catch (Throwable ex) {
                            traceSuppressed("adapter.stage", ex);
                        }
                        try {
                            System.out.println("ExtremeZ TimeBudget");
                        } catch (Throwable ex) {
                            traceFailure("extremez.stage", ex);
                        }
                        try {
                            System.out.println("CancelShield");
                        } catch (Throwable ex) {
                            traceTelemetrySkipped("cancel.stage", ex);
                        }
                        try {
                            System.out.println("Analyze web search");
                        } catch (Exception ex) {
                            traceCancelFailure("web.stage", ex);
                        }
                        try {
                            System.out.println("Rethrow path");
                        } catch (Exception ex) {
                            throw ex;
                        }
                        try {
                            System.out.println("Hybrid fallback provider");
                        } catch (Throwable ex) {
                            recordProviderError("naver", ex);
                        }
                        try {
                            System.out.println("QueryTransformer worker");
                        } catch (Throwable ex) {
                            recordCachedLlmWorkerThrowable("prompt", ex);
                        }
                        try {
                            System.out.println("Debug event emit");
                        } catch (Throwable ex) {
                            recordDebugEventEmitFailure(ex);
                        }
                        try {
                            System.out.println("LangGraph shadow");
                        } catch (Exception ex) {
                            recordGraphExceptionTrace("langgraph.shadow", request, ex, 1L, 0);
                        }
                        try {
                            System.out.println("LangGraph delayed shadow");
                        } catch (Exception ex) {
                            debug.put("langgraph.shadow.legacyResultCount", legacyCount);
                            debug.put("langgraph.shadow.graphResultCount", 0);
                            debug.put("langgraph.shadow.latencyMsLegacy", legacyLatencyMs);
                            debug.put("langgraph.shadow.latencyMsGraph", graphLatencyMs);
                            debug.put("langgraph.shadow.repairTriggered", false);
                            debug.put("langgraph.shadow.fallbackTriggered", true);
                            debug.put("langgraph.shadow.answerDiffScore", 1.0d);
                            debug.put("langgraph.shadow.tookMs", graphLatencyMs);
                            debug.put("langgraph.shadow.resultCount", 0);
                            debug.put("langgraph.shadow.deltaResultCount", -legacyCount);
                            debug.put("langgraph.shadow.error", operationalErrorType(ex));
                            debug.put("langgraph.shadow.promotionScore", 0.0d);
                            debug.put("langgraph.shadow.promotionEligible", false);
                            debug.put("langgraph.shadow.promotionBlockers", java.util.List.of("graph_exception"));
                            debug.put("langgraph.shadow.transitionScore", 0.0d);
                            debug.put("langgraph.shadow.policyRuleId", "shadow-fallback");
                            debug.put("langgraph.shadow.controlMode", "observe");
                            debug.put("langgraph.shadow.invokeSource", "graph");
                            debug.put("langgraph.shadow.checkpointBackend", "memory");
                            debug.put("langgraph.shadow.candidateCount", 0);
                            recordGraphExceptionTrace("langgraph.shadow", request, ex, 1L, 0);
                        }
                        try {
                            System.out.println("Noise filter");
                        } catch (RuntimeException ex) {
                            recordNoiseFilterFallback("safe_int", ex);
                        }
                        try {
                            System.out.println("Safe numeric parse");
                        } catch (NumberFormatException ignored) {
                            INVALID_NUMBER_SUPPRESSOR.accept("onnxDocIndex");
                        }
                        String ui = \"""
                            function getKey(){ try { return sessionStorage.getItem(LS_KEY) || ''; } catch(e){ console.debug('ui storage skipped'); return ''; } }
                            try { obj = txt ? JSON.parse(txt) : null; } catch(e) { console.debug('ui json parse skipped'); }
                            navigator.clipboard.writeText(text).catch(function(){ fallbackCopy(text); });
                            \""";
                    }
                }
                """,
                encoding="utf-8",
            )
            tools = root / "main" / "java" / "com" / "example" / "lms" / "tools"
            tools.mkdir(parents=True)
            (tools / "HarmonyBuildScanner.java").write_text(
                """
                package com.example.lms.tools;

                public class HarmonyBuildScanner {
                    String all = "Overdrive CFVM MoE Matryoshka ExtremeZ HYPERNOVA CIH-RAG OpenAI PromptBuilder";
                }
                """,
                encoding="utf-8",
            )

            report = harmony_pressure_report.build_report(root)
            rendered = json.dumps(report, sort_keys=True)

        self.assertEqual(3, report["activeJavaFileCount"])
        self.assertEqual(3, report["crossSubsystemFiles"])
        self.assertEqual(2, report["runtimeCrossSubsystemFiles"])
        self.assertEqual(1, report["diagnosticCrossSubsystemFiles"])
        self.assertNotIn("tools/HarmonyBuildScanner.java", json.dumps(report["topRuntimeCrossSubsystemFiles"]))
        self.assertEqual(2, report["aspectFiles"])
        self.assertEqual(1, report["aspectFilesWithExplicitOrderApprox"])
        self.assertEqual(1, report["unorderedAspectCount"])
        self.assertEqual(1, report["criticalUnorderedAspectCount"])
        self.assertEqual(
            "main/java/com/example/MixedFlow.java",
            report["topUnorderedAspectHotspots"][0]["file"],
        )
        self.assertFalse(report["topUnorderedAspectHotspots"][0]["explicitOrder"])
        self.assertGreaterEqual(report["topUnorderedAspectHotspots"][0]["subsystemCount"], 2)
        self.assertEqual(15, report["catchBlocks"])
        self.assertEqual(1, report["catchWithoutLocalBreadcrumbApprox"])
        self.assertEqual(1, report["broadCatchWithoutLocalBreadcrumbApprox"])
        self.assertEqual(
            "main/java/com/example/MixedFlow.java",
            report["topCatchPressureFiles"][0]["file"],
        )
        self.assertEqual(1, report["topCatchPressureFiles"][0]["catchWithoutLocalBreadcrumbApprox"])
        self.assertEqual(1, report["topCatchPressureFiles"][0]["broadCatchWithoutLocalBreadcrumbApprox"])
        self.assertIn("main/java/com/example/MixedFlow.java", rendered)
        self.assertNotIn(str(root), rendered)

    def test_manual_prompt_candidates_ignore_llm_transport_adapters(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "main" / "java" / "com" / "example"
            src.mkdir(parents=True)
            (src / "ManualBypassService.java").write_text(
                """
                package com.example;

                import dev.langchain4j.data.message.UserMessage;
                import dev.langchain4j.model.chat.ChatModel;
                import java.util.List;

                public class ManualBypassService {
                    private final ChatModel model;
                    ManualBypassService(ChatModel model) {
                        this.model = model;
                    }
                    String run(String question) {
                        String unsafePrompt = "Question: " + question;
                        return model.chat(List.of(UserMessage.from(unsafePrompt))).aiMessage().text();
                    }
                }
                """,
                encoding="utf-8",
            )
            (src / "OpenAiResponsesChatModel.java").write_text(
                """
                package com.example;

                import dev.langchain4j.data.message.ChatMessage;
                import dev.langchain4j.model.chat.ChatModel;
                import dev.langchain4j.model.chat.response.ChatResponse;
                import java.util.List;

                public final class OpenAiResponsesChatModel implements ChatModel {
                    public ChatResponse chat(List<ChatMessage> messages) {
                        String input = messages.toString();
                        StringBuilder sb = new StringBuilder();
                        sb.append(input);
                        return null;
                    }
                }
                """,
                encoding="utf-8",
            )

            report = harmony_pressure_report.build_report(root)

        self.assertEqual(1, report["manualPromptCandidateCount"])
        self.assertEqual(
            "main/java/com/example/ManualBypassService.java",
            report["manualPromptCandidateFiles"][0]["file"],
        )


if __name__ == "__main__":
    unittest.main()
