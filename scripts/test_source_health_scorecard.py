import importlib.util
import datetime as dt
import json
import os
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPORT_PATH = ROOT / "scripts" / "source_health_scorecard.py"
SPEC = importlib.util.spec_from_file_location("source_health_scorecard", REPORT_PATH)
source_health_scorecard = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(source_health_scorecard)


def _current_utc_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


class SourceHealthScorecardTest(unittest.TestCase):
    def test_builds_weighted_scorecard_from_current_metric_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "activeRoots": ["main/java", "app/src/main/java_clean"],
                        "runtimeProviderDisabledSmoke": {
                            "status": 200,
                            "providerDisabledOrSkipped": True,
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                        },
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectFilesWithExplicitOrderApprox": 4,
                            "aspectOrderCoverageApprox": 0.4,
                            "criticalUnorderedAspectCount": 3,
                            "crossSubsystemLargeFilesOver1000": 5,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.25,
                            "topUnorderedAspectHotspots": [
                                {
                                    "file": "main/java/demo/HotAspect.java",
                                    "riskScore": 0.9,
                                }
                            ],
                        },
                        "testTreeContamination": {
                            "missingImportCount": 12,
                            "affectedTestFileCount": 4,
                            "riskScore": 0.12,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "largeActiveFilesOver2000": 3,
                        "activeJavaLocP95": 500,
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeCrossSubsystemLargeFilesOver1000": 2,
                        "topUnorderedAspectHotspots": [
                            {"file": "main/java/demo/HotAspect.java", "riskScore": 0.9}
                        ]
                    }
                ),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"missingImportCount": 12, "affectedTestFileCount": 4}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual(data["decision"], "source_health_scorecard")
        self.assertGreater(data["strictEvidenceAdjustedScore"], 0)
        self.assertLess(data["strictEvidenceAdjustedScore"], 100)
        self.assertEqual(round(sum(row["weight"] for row in data["componentScores"]), 4), 1.0)
        self.assertIn("aspect_order_hotspots", [row["id"] for row in data["riskLedger"]])
        self.assertIn("test_tree_contamination", [row["id"] for row in data["riskLedger"]])
        self.assertIn("supabase_live_proof_missing", [row["id"] for row in data["riskLedger"]])
        concentration = next(row for row in data["riskLedger"] if row["id"] == "cross_subsystem_concentration")
        self.assertEqual(0.04, concentration["riskScore"])
        self.assertIn("runtimeCrossSubsystemLargeFilesOver1000=2", concentration["evidence"])
        self.assertEqual(data["riskCount"], len(data["riskLedger"]))
        supabase_risk = next(row for row in data["riskLedger"] if row["id"] == "supabase_live_proof_missing")
        self.assertEqual("external_evidence", supabase_risk["scope"])
        allowed_statuses = {"action_required", "evidence_needed", "guarded", "monitored"}
        for row in data["riskLedger"]:
            self.assertIn(row["status"], allowed_statuses)
            self.assertTrue(row["statusReason"])
        self.assertEqual("evidence_needed", supabase_risk["status"])
        self.assertEqual(
            "action_required",
            concentration["status"],
        )
        self.assertEqual(
            data["activeRiskCount"],
            sum(
                1 for row in data["riskLedger"]
                if row["riskScore"] > 0.0 and row["scope"] == "active_source"
            ),
        )
        self.assertEqual(data["evidenceNeededCount"], len(data["evidenceNeeded"]))
        self.assertEqual(data["evidenceNeeded"][0]["classification"], "evidence_needed")
        rendered = json.dumps(data, ensure_ascii=False)
        self.assertIn("main/java/demo/HotAspect.java", rendered)
        self.assertNotIn(str(root), rendered)

    def test_zero_test_tree_risk_keeps_compile_gate_out_of_evidence_needed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0},
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertNotIn("broad_test_tree_compile", evidence_items)
        test_risk = next(row for row in data["riskLedger"] if row["id"] == "test_tree_contamination")
        self.assertEqual(test_risk["riskScore"], 0.0)
        self.assertEqual("monitored", test_risk["status"])
        self.assertIn("compileTestJava", test_risk["nextAction"])
        self.assertIn("supabase", data["nextSingleAction"])
        self.assertEqual(
            "run_broad_test_runtime_proof",
            data["nextSourceAction"],
        )

    def test_aspect_order_risk_uses_existing_contract_test_as_next_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "topUnorderedAspectHotspots": [],
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            test_path = (
                root
                / "src"
                / "test"
                / "java"
                / "ai"
                / "abandonware"
                / "nova"
                / "orch"
                / "aop"
                / "AspectOrderingContractTest.java"
            )
            test_path.parent.mkdir(parents=True)
            test_path.write_text(
                "class AspectOrderingContractTest { "
                "ExtremeZBurstAspect extremeZ; RagCompressionAspect rag; LlmRouterAspect llm; }",
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        risk = next(row for row in data["riskLedger"] if row["id"] == "aspect_order_hotspots")
        self.assertEqual(0.0, risk["riskScore"])
        self.assertIn("contractPresent=True", risk["evidence"])
        self.assertIn("Run focused AspectOrderingContractTest", risk["nextAction"])
        self.assertNotIn("Add explicit order/call-path contracts", risk["nextAction"])

    def test_cross_subsystem_next_source_action_exposes_focused_test_details(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "crossSubsystemLargeFilesOver1000": 31,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "largeActiveFilesOver2000": 12,
                        "activeJavaLocP95": 643.2,
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps({"runtimeCrossSubsystemLargeFilesOver1000": 28}),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("continue_small_contract_tests_on_cross_subsystem_runtime_seams", data["nextSourceAction"])
        details = data["nextSourceActionDetails"]
        self.assertEqual(1, len(details))
        detail = details[0]
        self.assertEqual("run-cross-subsystem-contract-tests", detail["action"])
        self.assertTrue(detail["readOnly"])
        self.assertFalse(detail["mutationAllowed"])
        self.assertIn("com.example.lms.orchestration.StrategyConflictResolverTest", detail["focusedTests"])
        self.assertIn("ai.abandonware.nova.orch.aop.AspectOrderingContractTest", detail["focusedTests"])
        self.assertIn("boosterMode.active", detail["requiredTraceKeys"])
        self.assertIn("routing.executionPlan.applied.primaryMode", detail["requiredTraceKeys"])
        rendered = json.dumps(detail, ensure_ascii=False)
        self.assertNotRegex(rendered, r"[A-Za-z]:[\\/]")

    def test_broad_catch_audit_source_action_has_structured_contract_detail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {
                            "status": 200,
                            "providerDisabledOrSkipped": True,
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                        },
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "crossSubsystemLargeFilesOver1000": 0,
                            "runtimeCrossSubsystemLargeFilesOver1000": 0,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.25,
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeCrossSubsystemLargeFilesOver1000": 0,
                        "broadCatchWithoutLocalBreadcrumbRatio": 0.25,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("audit_top_broad_catches_for_redacted_breadcrumbs", data["nextSourceAction"])
        details = data["nextSourceActionDetails"]
        self.assertEqual(1, len(details))
        detail = details[0]
        self.assertEqual("audit-broad-catches-redacted-breadcrumbs", detail["action"])
        self.assertTrue(detail["sourceContract"])
        self.assertTrue(detail["readOnly"])
        self.assertFalse(detail["mutationAllowed"])
        self.assertGreaterEqual(len(detail["focusedTests"]), 4)
        self.assertGreaterEqual(len(detail["commands"]), 4)
        self.assertGreaterEqual(len(detail["requiredTraceKeys"]), 5)
        self.assertIn("com.example.lms.service.ChatWorkflowTraceRedactionContractTest", detail["focusedTests"])
        self.assertIn("com.example.lms.guard.RemainingMediumEmptyCatchContractTest", detail["focusedTests"])
        self.assertIn("failSoft.suppressed.stage", detail["requiredTraceKeys"])
        rendered = json.dumps(detail, ensure_ascii=False)
        self.assertNotRegex(rendered, r"[A-Za-z]:[\\/]")

    def test_cross_subsystem_contract_test_proof_advances_next_source_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "crossSubsystemLargeFilesOver1000": 31,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps({"runtimeCrossSubsystemLargeFilesOver1000": 28}),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            test_results = root / "build" / "desktop-codex" / "test-results" / "test"
            test_results.mkdir(parents=True)
            for class_name in (
                "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                "com.example.lms.orchestration.StrategyConflictResolverTest",
                "com.example.lms.orchestration.ExecutionPlanApplierTest",
                "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
            ):
                (test_results / f"TEST-{class_name}.xml").write_text(
                    f'<testsuite name="{class_name}" tests="1" skipped="0" failures="0" errors="0">'
                    f'<testcase classname="{class_name}" name="contract"/></testsuite>',
                    encoding="utf-8",
                )

            data = source_health_scorecard.build_scorecard(root)

        concentration = next(row for row in data["riskLedger"] if row["id"] == "cross_subsystem_concentration")
        self.assertIn("focusedContractTests=passed", concentration["evidence"])
        self.assertIn("Focused cross-subsystem contract tests passed", concentration["nextAction"])
        self.assertEqual("run_broad_test_runtime_proof", data["nextSourceAction"])
        self.assertEqual([], data["nextSourceActionDetails"])

    def test_broad_runtime_test_proof_marks_source_runtime_current(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {
                            "aspectFiles": 10,
                            "aspectOrderCoverageApprox": 1.0,
                            "criticalUnorderedAspectCount": 0,
                            "crossSubsystemLargeFilesOver1000": 31,
                            "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
                        },
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                json.dumps({"runtimeCrossSubsystemLargeFilesOver1000": 28}),
                encoding="utf-8",
            )
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            contract_file = (
                root
                / "src/test/java/ai/abandonware/nova/orch/aop/AspectOrderingContractTest.java"
            )
            contract_file.parent.mkdir(parents=True)
            contract_file.write_text(
                "ExtremeZBurstAspect RagCompressionAspect LlmRouterAspect",
                encoding="utf-8",
            )
            test_results = root / "build" / "desktop-codex" / "test-results" / "test"
            test_results.mkdir(parents=True)
            for class_name in (
                "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                "com.example.lms.orchestration.StrategyConflictResolverTest",
                "com.example.lms.orchestration.ExecutionPlanApplierTest",
                "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
                "com.example.lms.service.ChatWorkflowTraceRedactionContractTest",
            ):
                (test_results / f"TEST-{class_name}.xml").write_text(
                    f'<testsuite name="{class_name}" tests="2" skipped="0" failures="0" errors="0">'
                    f'<testcase classname="{class_name}" name="contractA"/>'
                    f'<testcase classname="{class_name}" name="contractB"/></testsuite>',
                    encoding="utf-8",
                )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("source_runtime_proof_current", data["nextSourceAction"])
        details = data["nextSourceActionDetails"]
        self.assertEqual(1, len(details))
        self.assertEqual("source-runtime-proof-current", details[0]["action"])
        self.assertTrue(details[0]["readOnly"])
        self.assertFalse(details[0]["mutationAllowed"])
        self.assertIn("python scripts\\source_health_scorecard.py --root . --output verification\\source-health-scorecard.json", details[0]["commands"])
        self.assertIn("python scripts\\awx_mcp_completion_audit.py --root .", details[0]["commands"])
        self.assertIn("bootSuccessProven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("applicationReadyEventProven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("application-ready-event-proven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("bootStartedProven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("boot-started-proven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("runtimePrecheckProven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("runtime-precheck-proven", ",".join(details[0]["requiredMarkers"]))
        self.assertIn("blocker-scan-only", ",".join(details[0]["requiredMarkers"]))
        aspect_order = next(row for row in data["riskLedger"] if row["id"] == "aspect_order_hotspots")
        self.assertIn("Aspect ordering proof is current", aspect_order["nextAction"])
        self.assertNotIn("Run focused AspectOrderingContractTest", aspect_order["nextAction"])
        concentration = next(row for row in data["riskLedger"] if row["id"] == "cross_subsystem_concentration")
        self.assertIn("Broad runtime proof is current", concentration["nextAction"])
        self.assertNotIn("move the next gate to broad runtime proof", concentration["nextAction"])
        self.assertEqual("guarded", concentration["status"])
        self.assertIn("proof", concentration["statusReason"])
        test_risk = next(row for row in data["riskLedger"] if row["id"] == "test_tree_contamination")
        self.assertIn("Broad runtime proof is current", test_risk["nextAction"])
        self.assertNotIn("move the next gate to full test runtime proof", test_risk["nextAction"])
        self.assertEqual(0, data["activeRiskCount"])
        self.assertEqual(
            {"passed": True, "suiteCount": 5, "testCount": 10, "failureCount": 0, "errorCount": 0},
            data["broadRuntimeTestProof"],
        )

    def test_broad_runtime_test_proof_prefers_host_split_results_without_env(self):
        old_host = os.environ.get("AWX_BUILD_HOST_ID")
        os.environ.pop("AWX_BUILD_HOST_ID", None)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                verification = root / "verification"
                verification.mkdir(parents=True)
                (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                    json.dumps(
                        {
                            "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                            "harmonyPressureSummary": {
                                "aspectFiles": 10,
                                "aspectOrderCoverageApprox": 1.0,
                                "criticalUnorderedAspectCount": 0,
                                "crossSubsystemLargeFilesOver1000": 31,
                                "broadCatchWithoutLocalBreadcrumbRatio": 0.0,
                            },
                            "testTreeContamination": {
                                "riskScore": 0.0,
                                "missingImportCount": 0,
                                "affectedTestFileCount": 0,
                            },
                            "supabaseReadonlySmoke": {
                                "readOnlyMode": True,
                                "mutationAllowed": False,
                                "projectScopeStatus": "project_ref_missing",
                            },
                            "duplicateFqcnActiveCount": 0,
                            "secretPatternHitCount": 0,
                        }
                    ),
                    encoding="utf-8",
                )
                (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text(
                    json.dumps({"runtimeCrossSubsystemLargeFilesOver1000": 28}),
                    encoding="utf-8",
                )
                (verification / "test-tree-contamination-metrics.json").write_text(
                    json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                    encoding="utf-8",
                )
                stale_default = root / "build" / "test-results" / "test"
                stale_default.mkdir(parents=True)
                (stale_default / "TEST-com.example.lms.SingleSmokeTest.xml").write_text(
                    '<testsuite name="com.example.lms.SingleSmokeTest" tests="1" skipped="0" failures="0" errors="0">'
                    '<testcase classname="com.example.lms.SingleSmokeTest" name="smoke"/></testsuite>',
                    encoding="utf-8",
                )
                host_results = root / "build" / "desktop-codex" / "test-results" / "test"
                host_results.mkdir(parents=True)
                for class_name in (
                    "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                    "com.example.lms.orchestration.StrategyConflictResolverTest",
                    "com.example.lms.orchestration.ExecutionPlanApplierTest",
                    "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
                    "com.example.lms.service.ChatWorkflowTraceRedactionContractTest",
                ):
                    (host_results / f"TEST-{class_name}.xml").write_text(
                        f'<testsuite name="{class_name}" tests="2" skipped="0" failures="0" errors="0">'
                        f'<testcase classname="{class_name}" name="contractA"/>'
                        f'<testcase classname="{class_name}" name="contractB"/></testsuite>',
                        encoding="utf-8",
                    )

                data = source_health_scorecard.build_scorecard(root)
        finally:
            if old_host is None:
                os.environ.pop("AWX_BUILD_HOST_ID", None)
            else:
                os.environ["AWX_BUILD_HOST_ID"] = old_host

        self.assertEqual("source_runtime_proof_current", data["nextSourceAction"])
        self.assertEqual(
            {"passed": True, "suiteCount": 5, "testCount": 10, "failureCount": 0, "errorCount": 0},
            data["broadRuntimeTestProof"],
        )

    def test_broad_runtime_test_proof_skips_inaccessible_result_roots(self):
        class DeniedPath:
            def is_dir(self):
                raise PermissionError("denied")

        original_roots = source_health_scorecard._test_result_roots
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                good_results = root / "build" / "desktop-codex" / "test-results" / "test"
                good_results.mkdir(parents=True)
                for class_name in (
                    "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                    "com.example.lms.orchestration.StrategyConflictResolverTest",
                    "com.example.lms.orchestration.ExecutionPlanApplierTest",
                    "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
                    "com.example.lms.service.ChatWorkflowTraceRedactionContractTest",
                ):
                    (good_results / f"TEST-{class_name}.xml").write_text(
                        f'<testsuite name="{class_name}" tests="1" skipped="0" failures="0" errors="0">'
                        f'<testcase classname="{class_name}" name="contract"/></testsuite>',
                        encoding="utf-8",
                    )
                source_health_scorecard._test_result_roots = lambda _root: [DeniedPath(), good_results]

                proof = source_health_scorecard._broad_runtime_test_proof(root)
        finally:
            source_health_scorecard._test_result_roots = original_roots

        self.assertEqual(
            {"passed": True, "suiteCount": 5, "testCount": 5, "failureCount": 0, "errorCount": 0},
            proof,
        )

    def test_live_websoak_smoke_overrides_stale_provider_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {
                            "status": 500,
                            "providerDisabledOrSkipped": False,
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                        },
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0},
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            websoak = verification / "websoak-kpi-smoke"
            websoak.mkdir(parents=True)
            (websoak / "websoak-kpi-provider-disabled.json").write_text(
                json.dumps(
                    {
                        "summary": {
                            "status": 200,
                            "providerDisabledOrSkipped": True,
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                        }
                    }
                ),
                encoding="utf-8-sig",
            )

            data = source_health_scorecard.build_scorecard(root)

        provider = next(row for row in data["componentScores"] if row["id"] == "runtime_provider_safety")
        self.assertEqual(provider["normalized"], 0.93)
        self.assertIn("status=200", provider["evidence"])

    def test_project_scoped_supabase_still_requires_data_api_grant_and_rls_result_sets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0},
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_ready",
                            "missingResultCount": 2,
                            "missingResultNames": [
                                "data_api_role_grants",
                                "exposed_tables_without_rls",
                            ],
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("supabase_data_api_grants_and_rls_result_sets", evidence_items)
        self.assertIn("collect_supabase_data_api_grants_and_rls_result_sets", data["nextSingleAction"])
        supabase = next(row for row in data["componentScores"] if row["id"] == "supabase_external_evidence")
        self.assertLess(supabase["normalized"], 0.85)
        self.assertIn("data_api_role_grants", supabase["evidence"])

    def test_supabase_component_references_latest_completion_audit_smoke_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "reportsEnvPreflight=True;"
                                    "summaryArtifact=True;"
                                    "summaryGeneratedAt=True;"
                                    "summaryFresh=True;"
                                    "summaryFreshnessStatus=current;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "cliQueryMinVersion=>=2.79.0;"
                                    "cliAdvisorsMinVersion=>=2.81.3;"
                                    "mcpFallbackContract=True;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0;"
                                    "envPresentCount=1;"
                                    "projectRefEnvPresent=True;"
                                    "accessTokenEnvPresent=False;"
                                    "cliPresent=False;"
                                    "contextEvidenceNeededCount=2"
                                ),
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        supabase = next(row for row in data["componentScores"] if row["id"] == "supabase_external_evidence")
        self.assertIn("localSmokeContractReady=True", supabase["evidence"])
        self.assertIn("summaryFresh=True", supabase["evidence"])
        self.assertIn("summaryFreshnessStatus=current", supabase["evidence"])
        self.assertIn("cliQueryMinVersion=>=2.79.0", supabase["evidence"])
        self.assertIn("cliAdvisorsMinVersion=>=2.81.3", supabase["evidence"])
        self.assertIn("mcpFallbackContract=True", supabase["evidence"])
        self.assertEqual(
            "var/codex-smoke/awx-mcp-completion-audit-z.json",
            data["inputArtifacts"]["completionAudit"],
        )
        self.assertEqual(
            "var/codex-smoke/awx-mcp-completion-audit-z.json",
            data["completionAuditFreshness"]["path"],
        )
        self.assertEqual(
            source_health_scorecard._stable_hash("var/codex-smoke/awx-mcp-completion-audit-z.json"),
            data["completionAuditFreshness"]["pathHash"],
        )
        self.assertNotIn(str(root), json.dumps(data, ensure_ascii=False))

    def test_computer_use_boundary_is_reported_from_completion_audit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "summaryArtifact=True;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0"
                                ),
                            },
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "promptPack=True;"
                                    "guiOnly=True;"
                                    "noTerminalAutomation=True;"
                                    "supportingOnly=True;"
                                    "helperArtifact=True;"
                                    "helperReachable=True;"
                                    "helperGeneratedAt=True;"
                                    "helperFresh=True;"
                                    "helperFreshnessStatus=current;"
                                    "helperCountOnly=True;"
                                    "helperBoundary=True;"
                                    "storesAppNames=False;"
                                    "storesWindowTitles=False;"
                                    "helperSecretPatternHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            },
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        computer_use = next(row for row in data["componentScores"] if row["id"] == "computer_use_evidence_boundary")
        self.assertEqual(1.0, computer_use["normalized"])
        self.assertIn("guiOnly=True", computer_use["evidence"])
        self.assertIn("noTerminalAutomation=True", computer_use["evidence"])
        self.assertIn("supportingOnly=True", computer_use["evidence"])
        self.assertIn("helperReachable=True", computer_use["evidence"])
        self.assertIn("helperGeneratedAt=True", computer_use["evidence"])
        self.assertIn("helperFresh=True", computer_use["evidence"])
        self.assertIn("helperFreshnessStatus=current", computer_use["evidence"])
        self.assertIn("helperCountOnly=True", computer_use["evidence"])
        self.assertIn("helperBoundary=True", computer_use["evidence"])
        self.assertIn("storesAppNames=False", computer_use["evidence"])
        self.assertIn("storesWindowTitles=False", computer_use["evidence"])
        self.assertIn("helperSecretPatternHits=0", computer_use["evidence"])
        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertNotIn("computer_use_gui_proof_boundary", evidence_items)

    def test_incomplete_computer_use_boundary_stays_external_not_source_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "reportsEnvPreflight=True;"
                                    "summaryArtifact=True;"
                                    "summaryGeneratedAt=True;"
                                    "summaryFresh=True;"
                                    "summaryFreshnessStatus=current;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "cliQueryMinVersion=>=2.79.0;"
                                    "cliAdvisorsMinVersion=>=2.81.3;"
                                    "mcpFallbackContract=True;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0;"
                                    "envPresentCount=0;"
                                    "projectRefEnvPresent=False;"
                                    "accessTokenEnvPresent=False;"
                                    "cliPresent=False;"
                                    "contextEvidenceNeededCount=1"
                                ),
                            },
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": False,
                                "evidence": (
                                    "promptPack=True;"
                                    "guiOnly=True;"
                                    "noTerminalAutomation=True;"
                                    "supportingOnly=True;"
                                    "helperArtifact=True;"
                                    "helperReachable=True;"
                                    "helperGeneratedAt=True;"
                                    "helperFresh=False;"
                                    "helperFreshnessStatus=stale;"
                                    "helperCountOnly=True;"
                                    "helperBoundary=True;"
                                    "storesAppNames=False;"
                                    "storesWindowTitles=False;"
                                    "helperSecretPatternHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("computer_use_gui_proof_boundary", evidence_items)
        self.assertEqual("repair_computer_use_gui_proof_boundary", data["nextSingleAction"])
        self.assertNotEqual("repair_computer_use_gui_proof_boundary", data["nextSourceAction"])
        self.assertEqual("run_broad_test_runtime_proof", data["nextSourceAction"])
        self.assertEqual([], data["nextSourceActionDetails"])

    def test_incomplete_supabase_smoke_contract_is_prioritized_before_live_project_request(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-incomplete.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "rawSecretPatternHits=0"
                                ),
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("supabase_readonly_snapshot_smoke_contract", evidence_items)
        smoke_gap = next(
            item for item in data["evidenceNeeded"]
            if item["item"] == "supabase_readonly_snapshot_smoke_contract"
        )
        self.assertIn("docsRefCount", smoke_gap["reason"])
        self.assertIn("dataApiGrantProofRequired", smoke_gap["reason"])
        self.assertEqual("repair_supabase_readonly_snapshot_smoke_contract", data["nextSingleAction"])
        supabase = next(row for row in data["componentScores"] if row["id"] == "supabase_external_evidence")
        self.assertIn("localSmokeContractReady=False", supabase["evidence"])

    def test_stale_or_undated_completion_audit_is_not_current_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-undated.json").write_text(
                json.dumps(
                    {
                        "checked": [
                            {
                                "id": "computer-use.gui-proof-boundary",
                                "ok": True,
                                "evidence": (
                                    "promptPack=True;"
                                    "guiOnly=True;"
                                    "noTerminalAutomation=True;"
                                    "supportingOnly=True;"
                                    "helperArtifact=True;"
                                    "helperReachable=True;"
                                    "helperGeneratedAt=True;"
                                    "helperFresh=True;"
                                    "helperFreshnessStatus=current;"
                                    "helperCountOnly=True;"
                                    "helperBoundary=True;"
                                    "storesAppNames=False;"
                                    "storesWindowTitles=False;"
                                    "helperSecretPatternHits=0;"
                                    "rawSecretPatternHits=0"
                                ),
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("missing_generated_at", data["completionAuditFreshness"]["status"])
        self.assertFalse(data["completionAuditFreshness"]["fresh"])
        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("completion_audit_freshness", evidence_items)
        self.assertEqual("rerun_completion_audit", data["nextSingleAction"])
        computer_use = next(row for row in data["componentScores"] if row["id"] == "computer_use_evidence_boundary")
        self.assertLess(computer_use["normalized"], 1.0)

    def test_scorecard_generated_at_is_utc_aware(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        parsed = dt.datetime.fromisoformat(data["generatedAt"].replace("Z", "+00:00"))
        self.assertIsNotNone(parsed.tzinfo)
        self.assertEqual(dt.timezone.utc, parsed.astimezone(dt.timezone.utc).tzinfo)

    def test_undated_db_gap_matrix_is_not_current_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-current.json").write_text(
                json.dumps({"generatedAt": _current_utc_iso(), "checked": []}),
                encoding="utf-8",
            )
            db_gap_dir = root / "data" / "db-gap-report"
            db_gap_dir.mkdir(parents=True)
            (db_gap_dir / "gap_matrix.json").write_text(
                json.dumps(
                    {
                        "external_supabase_snapshot": {
                            "snapshotImport": {
                                "missingResultNames": ["rls_and_table_flags"],
                            }
                        }
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual("missing_generated_at", data["dbGapMatrixFreshness"]["status"])
        self.assertFalse(data["dbGapMatrixFreshness"]["fresh"])
        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("db_gap_matrix_freshness", evidence_items)
        self.assertEqual("rerun_db_gap_scanner", data["nextSingleAction"])
        self.assertEqual("rerun_db_gap_scanner", data["nextSourceAction"])

    def test_supabase_smoke_contract_requires_docs_and_security_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-old-smoke.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "summaryArtifact=True;"
                                    "rawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0"
                                ),
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        evidence_items = {item["item"] for item in data["evidenceNeeded"]}
        self.assertIn("supabase_readonly_snapshot_smoke_contract", evidence_items)
        smoke_gap = next(
            item for item in data["evidenceNeeded"]
            if item["item"] == "supabase_readonly_snapshot_smoke_contract"
        )
        self.assertIn("docsRefCount", smoke_gap["reason"])
        self.assertIn("dataApiGrantProofRequired", smoke_gap["reason"])
        self.assertEqual("repair_supabase_readonly_snapshot_smoke_contract", data["nextSingleAction"])
        supabase = next(row for row in data["componentScores"] if row["id"] == "supabase_external_evidence")
        self.assertIn("localSmokeContractReady=False", supabase["evidence"])

    def test_scorecard_exposes_structured_supabase_live_proof_next_action_details(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "dynamic-rag-quant-audit-metrics.json").write_text(
                json.dumps(
                    {
                        "runtimeProviderDisabledSmoke": {"status": 200, "providerDisabledOrSkipped": True},
                        "harmonyPressureSummary": {},
                        "testTreeContamination": {
                            "riskScore": 0.0,
                            "missingImportCount": 0,
                            "affectedTestFileCount": 0,
                        },
                        "supabaseReadonlySmoke": {
                            "readOnlyMode": True,
                            "mutationAllowed": False,
                            "projectScopeStatus": "project_ref_missing",
                            "highConfidenceSecretHits": 0,
                        },
                        "duplicateFqcnActiveCount": 0,
                        "secretPatternHitCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (verification / "dynamic-rag-harmony-pressure-metrics.json").write_text("{}", encoding="utf-8")
            (verification / "test-tree-contamination-metrics.json").write_text(
                json.dumps({"riskScore": 0.0, "missingImportCount": 0, "affectedTestFileCount": 0}),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "awx-mcp-completion-audit-z.json").write_text(
                json.dumps(
                    {
                        "generatedAt": _current_utc_iso(),
                        "checked": [
                            {
                                "id": "supabase.readonly-snapshot-smoke",
                                "ok": True,
                                "evidence": (
                                    "reportsMissingResultNames=True;"
                                    "reportsDataApiEvidenceMissing=True;"
                                    "summaryArtifact=True;"
                                    "docsRefCount=6;"
                                    "securityContractCount=7;"
                                    "dataApiGrantProofRequired=True;"
                                    "rlsPolicyProofRequired=True;"
                                    "secretKeysBackendOnly=True;"
                                    "rawSecretPatternHits=0;"
                                    "summarySecretHits=0;"
                                    "summaryRawSecretPatternHits=0;"
                                    "summaryHighConfidenceSecretHits=0;"
                                    "summaryRawJdbcUrlHits=0"
                                ),
                            }
                        ],
                        "nextActionDetails": [
                            {
                                "action": "collect-supabase-live-proof",
                                "nodeRole": "desktop",
                                "targetService": "supabase",
                                "readOnly": True,
                                "mutationAllowed": False,
                                "requiredEnv": [
                                    {"name": "SUPABASE_PROJECT_REF", "sensitive": False},
                                    {"name": "SUPABASE_ACCESS_TOKEN", "sensitive": True},
                                ],
                                "requiredMcpTools": ["execute_sql", "get_advisors"],
                                "requiredResultNames": [
                                    "data_api_role_grants",
                                    "exposed_tables_without_rls",
                                ],
                                "resultPathRecommendation": "data/db-gap-report/supabase-query-results.json",
                                "advisorResultPathRecommendation": "data/db-gap-report/supabase-advisors.json",
                                "nextActions": [
                                    "set_SUPABASE_PROJECT_REF",
                                    "authenticate_supabase_mcp_or_cli",
                                ],
                                "decision": "evidence_needed",
                            },
                            {
                                "action": "collect-external-evidence-files",
                                "nodeRole": "desktop",
                                "targetRole": "macmini",
                                "topic": "mcp-control-loop",
                                "desktopEvidencePaths": {"nodeSmoke": "C:/absolute/path.json"},
                                "requiredSidecars": [
                                    ".patch",
                                    ".report.md",
                                    ".verify.log",
                                    ".sha256.txt",
                                    ".manifest.json",
                                    "pendingNotice",
                                ],
                                "requiredSourceIsolation": {
                                    "sourceIsolation.guard": "PASS",
                                    "sourceRootKind": "local-worktree",
                                    "directCanonicalSourceEdit": False,
                                    "desktopFinalProof": "evidence_needed",
                                    "rawSecretPatternHits": 0,
                                },
                                "applyCollectedEvidenceCommand": (
                                    "powershell -NoProfile -ExecutionPolicy Bypass "
                                    "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop"
                                ),
                                "producerCommands": [
                                    (
                                        "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                        "--canonical-root C:\\AbandonWare\\demo-1\\demo-1\\src --node-role macmini"
                                    ),
                                    (
                                        "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                        "--canonical-root C:\\AbandonWare\\demo-1\\demo-1\\src --patchdrop-root <PatchDrop> "
                                        "--producer-script <PatchDrop>\\producer_bundle.py --node-role macmini "
                                        "--topic mcp-control-loop --pathspec <relative/source/path>"
                                    ),
                                ],
                                "decision": "evidence_needed",
                            },
                            {
                                "action": "collect-archive-index-proof",
                                "nodeRole": "desktop",
                                "targetService": "archive",
                                "readOnly": True,
                                "mutationAllowed": False,
                                "requiredEnvNames": ["ARCHIVE_INDEX", "NAS_ARCHIVE_ROOT"],
                                "requiredMcpTools": ["archive.search", "archive.index_build"],
                                "indexPathRecommendation": "BackupsXS/index.jsonl",
                                "archiveRootRecommendation": "BackupsXS",
                                "applyCollectedEvidenceCommand": (
                                    "powershell -NoProfile -ExecutionPolicy Bypass "
                                    "-File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search"
                                ),
                                "nextActions": [
                                    "create_or_point_archive_index",
                                    "verify_archive_index_path",
                                    "rerun_archive_search",
                                ],
                                "decision": "evidence_needed",
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            data = source_health_scorecard.build_scorecard(root)

        self.assertEqual(3, len(data["nextActionDetails"]))
        detail = data["nextActionDetails"][0]
        self.assertEqual("collect-supabase-live-proof", detail["action"])
        self.assertEqual(
            "https://mcp.supabase.com/mcp?"
            "project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs",
            detail["mcpEndpointTemplate"],
        )
        self.assertEqual(["execute_sql", "get_advisors"], detail["requiredMcpTools"])
        self.assertIn("data_api_role_grants", detail["requiredResultNames"])
        self.assertEqual(
            "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\supabase_apply_collected_evidence.ps1",
            detail["applyCollectedEvidenceCommand"],
        )
        self.assertEqual("SUPABASE_ACCESS_TOKEN", detail["requiredEnv"][1]["name"])
        self.assertTrue(detail["requiredEnv"][1]["sensitive"])
        external = data["nextActionDetails"][1]
        self.assertEqual("collect-external-evidence-files", external["action"])
        self.assertEqual("macmini", external["targetRole"])
        self.assertIn(".patch", external["requiredSidecars"])
        self.assertEqual("PASS", external["requiredSourceIsolation"]["guard"])
        self.assertEqual("local-worktree", external["requiredSourceIsolation"]["sourceRootKind"])
        self.assertFalse(external["requiredSourceIsolation"]["directCanonicalSourceEdit"])
        self.assertIn("run_macmini_external_node_smoke", external["nextActions"])
        self.assertEqual(
            "powershell -NoProfile -ExecutionPolicy Bypass "
            "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop",
            external["applyCollectedEvidenceCommand"],
        )
        self.assertNotIn("desktopEvidencePaths", json.dumps(external, ensure_ascii=False))
        self.assertEqual(
            [
                "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                "--canonical-root <desktop-canonical-root> --node-role macmini",
                "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                "--canonical-root <desktop-canonical-root> --patchdrop-root <PatchDrop> "
                "--producer-script <PatchDrop>\\producer_bundle.py --node-role macmini "
                "--topic mcp-control-loop --pathspec <relative/source/path>",
            ],
            external["producerCommandTemplates"],
        )
        archive = data["nextActionDetails"][2]
        self.assertEqual("collect-archive-index-proof", archive["action"])
        self.assertEqual("archive", archive["targetService"])
        self.assertTrue(archive["readOnly"])
        self.assertFalse(archive["mutationAllowed"])
        self.assertEqual(["ARCHIVE_INDEX", "NAS_ARCHIVE_ROOT"], archive["requiredEnvNames"])
        self.assertEqual("BackupsXS/index.jsonl", archive["indexPathRecommendation"])
        self.assertEqual("BackupsXS", archive["archiveRootRecommendation"])
        self.assertIn("archive.search", archive["requiredMcpTools"])
        self.assertIn("verify_archive_index_path", archive["nextActions"])
        self.assertEqual(
            "powershell -NoProfile -ExecutionPolicy Bypass "
            "-File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search",
            archive["applyCollectedEvidenceCommand"],
        )
        self.assertNotIn("C:/absolute/path.json", json.dumps(data, ensure_ascii=False))
        self.assertNotIn("C:\\AbandonWare\\demo-1\\demo-1\\src", json.dumps(data, ensure_ascii=False))
        self.assertNotIn(str(root), json.dumps(data, ensure_ascii=False))

    def test_gradle_task_is_registered_for_repo_owned_refresh(self):
        build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8", errors="ignore")

        self.assertIn('tasks.register<Exec>("sourceHealthScorecard")', build)
        self.assertIn('dependsOn("harmonyPressureReport", "testTreeContaminationReport")', build)
        self.assertIn("scripts/source_health_scorecard.py", build)
        self.assertIn('inputs.file(layout.projectDirectory.file("verification/websoak-kpi-smoke/websoak-kpi-provider-disabled.json")).optional()', build)
        self.assertIn('inputs.file(layout.projectDirectory.file("data/db-gap-report/gap_matrix.json")).optional()', build)
        self.assertIn('inputs.dir(layout.projectDirectory.dir("var/codex-smoke")).optional()', build)
        self.assertIn("verification/source-health-scorecard.json", build)


if __name__ == "__main__":
    unittest.main()
