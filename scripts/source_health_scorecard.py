#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
from pathlib import Path
from typing import Any
import xml.etree.ElementTree as ET


DEFAULT_QUANT_METRICS = Path("verification/dynamic-rag-quant-audit-metrics.json")
DEFAULT_HARMONY_METRICS = Path("verification/dynamic-rag-harmony-pressure-metrics.json")
DEFAULT_TEST_TREE_METRICS = Path("verification/test-tree-contamination-metrics.json")
DEFAULT_WEBSOAK_PROVIDER_SMOKE = Path("verification/websoak-kpi-smoke/websoak-kpi-provider-disabled.json")
DEFAULT_DB_GAP_MATRIX = Path("data/db-gap-report/gap_matrix.json")
DEFAULT_COMPLETION_AUDIT_DIR = Path("var/codex-smoke")
COMPLETION_AUDIT_GLOB = "awx-mcp-completion-audit*.json"
COMPLETION_AUDIT_MAX_AGE_SECONDS = 24 * 60 * 60
DB_GAP_MATRIX_MAX_AGE_SECONDS = 24 * 60 * 60
DEFAULT_OUTPUT = Path("verification/source-health-scorecard.json")
SUPABASE_DATA_API_EVIDENCE_NAMES = {
    "data_api_role_grants",
    "exposed_tables_without_rls",
    "rls_and_table_flags",
}
FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS = (
    "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
    "com.example.lms.orchestration.StrategyConflictResolverTest",
    "com.example.lms.orchestration.ExecutionPlanApplierTest",
    "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
)


def _stable_hash(value: Any) -> str:
    return hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:12]


def _read_json(root: Path, relative: Path) -> dict[str, Any]:
    path = root / relative
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8-sig"))


def _safe_is_dir(path: Path) -> bool:
    try:
        return path.is_dir()
    except OSError:
        return False


def _read_latest_completion_audit(root: Path) -> tuple[dict[str, Any], str]:
    directory = root / DEFAULT_COMPLETION_AUDIT_DIR
    if not _safe_is_dir(directory):
        return {}, ""
    candidates = sorted(
        (path for path in directory.glob(COMPLETION_AUDIT_GLOB) if path.is_file()),
        key=lambda path: (path.stat().st_mtime, path.name),
        reverse=True,
    )
    for path in candidates:
        try:
            return json.loads(path.read_text(encoding="utf-8-sig")), path.relative_to(root).as_posix()
        except Exception:
            continue
    return {}, ""


def _artifact_freshness(raw_generated_at: Any, max_age_seconds: int) -> tuple[bool, bool, int, str]:
    text = str(raw_generated_at or "").strip()
    if not text:
        return False, False, -1, "missing_generated_at"
    try:
        generated_at = _dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return False, False, -1, "invalid_generated_at"
    now = _dt.datetime.now(_dt.timezone.utc)
    if generated_at.tzinfo is None:
        generated_at = generated_at.replace(tzinfo=_dt.timezone.utc)
    else:
        generated_at = generated_at.astimezone(_dt.timezone.utc)
    age_seconds = int((now - generated_at).total_seconds())
    if age_seconds < -300:
        return True, False, age_seconds, "future_generated_at"
    if age_seconds > max_age_seconds:
        return True, False, age_seconds, "stale"
    return True, True, max(0, age_seconds), "current"


def _aspect_order_contract_present(root: Path) -> bool:
    path = root / "src/test/java/ai/abandonware/nova/orch/aop/AspectOrderingContractTest.java"
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return False
    required_tokens = (
        "ExtremeZBurstAspect",
        "RagCompressionAspect",
        "LlmRouterAspect",
    )
    return all(token in text for token in required_tokens)


def _junit_xml_passed(path: Path) -> bool:
    summary = _junit_xml_summary(path)
    if not summary:
        return False
    return (
        summary["tests"] > 0
        and summary["skipped"] == 0
        and summary["failures"] == 0
        and summary["errors"] == 0
    )


def _junit_xml_summary(path: Path) -> dict[str, int]:
    try:
        suite = ET.parse(path).getroot()
    except Exception:
        return {}
    try:
        tests = int(suite.attrib.get("tests", "0") or 0)
        skipped = int(suite.attrib.get("skipped", "0") or 0)
        failures = int(suite.attrib.get("failures", "0") or 0)
        errors = int(suite.attrib.get("errors", "0") or 0)
    except ValueError:
        return {}
    return {
        "tests": max(0, tests),
        "skipped": max(0, skipped),
        "failures": max(0, failures),
        "errors": max(0, errors),
    }


def _test_result_roots(root: Path) -> list[Path]:
    build_root = root / "build"
    host_id = os.environ.get("AWX_BUILD_HOST_ID", "").strip()
    result_roots: list[Path] = []
    if host_id:
        result_roots.append(build_root / host_id / "test-results" / "test")
    result_roots.append(build_root / "test-results" / "test")
    if _safe_is_dir(build_root):
        for child in sorted(build_root.iterdir(), key=lambda item: item.name):
            if _safe_is_dir(child):
                result_roots.append(child / "test-results" / "test")
    return result_roots


def _focused_cross_subsystem_contract_proof(root: Path) -> dict[str, Any]:
    result_roots = _test_result_roots(root)

    passed: set[str] = set()
    for class_name in FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS:
        file_name = f"TEST-{class_name}.xml"
        for result_root in result_roots:
            candidate = result_root / file_name
            if candidate.is_file() and _junit_xml_passed(candidate):
                passed.add(class_name)
                break
    return {
        "passed": len(passed) == len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS),
        "passedCount": len(passed),
        "requiredCount": len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS),
    }


def _broad_runtime_test_proof(root: Path) -> dict[str, Any]:
    summaries: list[dict[str, Any]] = []
    for result_root in _test_result_roots(root):
        if not _safe_is_dir(result_root):
            continue
        suites = sorted(path for path in result_root.glob("TEST-*.xml") if path.is_file())
        if not suites:
            continue
        suite_count = 0
        test_count = 0
        failure_count = 0
        error_count = 0
        for suite in suites:
            summary = _junit_xml_summary(suite)
            if not summary:
                continue
            suite_count += 1
            test_count += summary["tests"]
            failure_count += summary["failures"]
            error_count += summary["errors"]
        if suite_count == 0:
            continue
        summaries.append({
            "passed": (
                suite_count > len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS)
                and test_count > 0
                and failure_count == 0
                and error_count == 0
            ),
            "suiteCount": suite_count,
            "testCount": test_count,
            "failureCount": failure_count,
            "errorCount": error_count,
        })
    if summaries:
        return max(summaries, key=_broad_runtime_test_proof_rank)
    return {
        "passed": False,
        "suiteCount": 0,
        "testCount": 0,
        "failureCount": 0,
        "errorCount": 0,
    }


def _broad_runtime_test_proof_rank(summary: dict[str, Any]) -> tuple[bool, bool, bool, int, int]:
    suite_count = int(summary.get("suiteCount", 0) or 0)
    test_count = int(summary.get("testCount", 0) or 0)
    failure_count = int(summary.get("failureCount", 0) or 0)
    error_count = int(summary.get("errorCount", 0) or 0)
    clean = failure_count == 0 and error_count == 0
    broad = suite_count > len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS) and test_count > 0
    return (
        bool(summary.get("passed")),
        broad,
        clean,
        suite_count,
        test_count,
    )


def _clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def _round4(value: float) -> float:
    return round(value, 4)


def _safe_names(value: Any) -> list[str]:
    if isinstance(value, list):
        raw = value
    elif isinstance(value, str):
        raw = value.split(",")
    else:
        raw = []
    out = []
    for item in raw:
        text = str(item).strip()
        if text and text.replace("_", "").replace("-", "").isalnum():
            out.append(text)
    return out


def _safe_tool_names(value: Any) -> list[str]:
    if isinstance(value, list):
        raw = value
    elif isinstance(value, str):
        raw = value.split(",")
    else:
        raw = []
    out = []
    for item in raw:
        text = str(item).strip()
        if text and text.replace("_", "").replace("-", "").replace(".", "").isalnum():
            out.append(text)
    return out


def _checked_evidence(report: dict[str, Any], check_id: str) -> tuple[bool, dict[str, str]]:
    checked = report.get("checked")
    if not isinstance(checked, list):
        return False, {}
    for row in checked:
        if not isinstance(row, dict) or row.get("id") != check_id:
            continue
        evidence: dict[str, str] = {}
        for part in str(row.get("evidence") or "").split(";"):
            if "=" not in part:
                continue
            key, value = part.split("=", 1)
            evidence[key.strip()] = value.strip()
        return row.get("ok") is True, evidence
    return False, {}


def _evidence_int(evidence: dict[str, str], key: str) -> int:
    try:
        return int(evidence.get(key, "0") or 0)
    except ValueError:
        return 0


def _safe_relative_path(value: Any) -> str:
    text = str(value or "").strip().replace("\\", "/")
    if (
        not text
        or ":" in text
        or text.startswith("/")
        or text.startswith("//")
        or any(part == ".." for part in text.split("/"))
    ):
        return ""
    return text


def _safe_supabase_mcp_endpoint_template(value: Any) -> str:
    text = str(value or "").strip()
    if not text.startswith("https://mcp.supabase.com/mcp?"):
        return ""
    if any(token in text.lower() for token in ("authorization=", "bearer", "access_token=", "password=", "apikey=")):
        return ""
    allowed_chars = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?&=,$%{}")
    if any(ch not in allowed_chars for ch in text):
        return ""
    if "project_ref=${SUPABASE_PROJECT_REF}" not in text or "read_only=true" not in text:
        return ""
    return text


def _default_supabase_mcp_endpoint_template(safe: dict[str, Any]) -> str:
    env_names = {
        item.get("name")
        for item in safe.get("requiredEnv", [])
        if isinstance(item, dict)
    }
    if safe.get("readOnly") is True and "SUPABASE_PROJECT_REF" in env_names:
        return (
            "https://mcp.supabase.com/mcp?"
            "project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs"
        )
    return ""


def _safe_supabase_live_proof_details(report: dict[str, Any]) -> list[dict[str, Any]]:
    details = report.get("nextActionDetails")
    if not isinstance(details, list):
        return []
    out: list[dict[str, Any]] = []
    for row in details:
        if not isinstance(row, dict) or row.get("action") != "collect-supabase-live-proof":
            continue
        safe: dict[str, Any] = {
            "action": "collect-supabase-live-proof",
            "nodeRole": str(row.get("nodeRole") or ""),
            "targetService": "supabase",
            "readOnly": row.get("readOnly") is True,
            "mutationAllowed": row.get("mutationAllowed") is True,
            "applyCollectedEvidenceCommand": (
                "powershell -NoProfile -ExecutionPolicy Bypass "
                "-File scripts\\supabase_apply_collected_evidence.ps1"
            ),
            "requiredEnv": [
                {
                    "name": str(item.get("name") or ""),
                    "sensitive": item.get("sensitive") is True,
                }
                for item in row.get("requiredEnv", [])
                if isinstance(item, dict) and str(item.get("name") or "").replace("_", "").isalnum()
            ],
            "requiredMcpTools": _safe_names(row.get("requiredMcpTools")),
            "requiredResultNames": _safe_names(row.get("requiredResultNames")),
            "nextActions": _safe_names(row.get("nextActions")),
            "decision": str(row.get("decision") or ""),
        }
        endpoint_template = (
            _safe_supabase_mcp_endpoint_template(row.get("mcpEndpointTemplate"))
            or _default_supabase_mcp_endpoint_template(safe)
        )
        if endpoint_template:
            safe["mcpEndpointTemplate"] = endpoint_template
        for key in ("artifactPaths",):
            paths = [_safe_relative_path(item) for item in row.get(key, []) if _safe_relative_path(item)]
            if paths:
                safe[key] = paths
        for key in ("resultPathRecommendation", "advisorResultPathRecommendation"):
            path = _safe_relative_path(row.get(key))
            if path:
                safe[key] = path
        try:
            safe["queryCount"] = int(row.get("queryCount", 0) or 0)
        except Exception:
            safe["queryCount"] = 0
        docs_refs = [
            str(item)
            for item in row.get("docsRefs", [])
            if isinstance(item, str) and item.startswith("https://supabase.com/")
        ]
        if docs_refs:
            safe["docsRefs"] = docs_refs
        if str(row.get("importTool") or "").replace("_", "").isalnum():
            safe["importTool"] = str(row.get("importTool"))
        out.append(safe)
    return out


def _safe_patchdrop_sidecars(value: Any) -> list[str]:
    allowed = {
        ".patch",
        ".report.md",
        ".verify.log",
        ".sha256.txt",
        ".manifest.json",
        "pendingNotice",
    }
    raw = value if isinstance(value, list) else []
    out: list[str] = []
    for item in raw:
        text = str(item).strip()
        if text in allowed and text not in out:
            out.append(text)
    return out


def _safe_external_apply_command(value: Any) -> str:
    text = str(value or "").strip()
    prefix = (
        "powershell -NoProfile -ExecutionPolicy Bypass "
        "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic "
    )
    if not text.startswith(prefix):
        return ""
    topic = text[len(prefix):]
    if not topic or not topic.replace("_", "").replace("-", "").replace(".", "").replace(":", "").isalnum():
        return ""
    return text


def _safe_producer_command_templates(target_role: str, topic: str) -> list[str]:
    safe_topic = topic if topic else "<topic>"
    return [
        (
            "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
            f"--canonical-root <desktop-canonical-root> --node-role {target_role}"
        ),
        (
            "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
            "--canonical-root <desktop-canonical-root> --patchdrop-root <PatchDrop> "
            f"--producer-script <PatchDrop>\\producer_bundle.py --node-role {target_role} "
            f"--topic {safe_topic} --pathspec <relative/source/path>"
        ),
    ]


def _safe_external_evidence_details(report: dict[str, Any]) -> list[dict[str, Any]]:
    details = report.get("nextActionDetails")
    if not isinstance(details, list):
        return []
    out: list[dict[str, Any]] = []
    for row in details:
        if not isinstance(row, dict) or row.get("action") != "collect-external-evidence-files":
            continue
        target_role = str(row.get("targetRole") or "").strip()
        if target_role not in {"macmini", "notebook"}:
            continue
        topic = str(row.get("topic") or "").strip()
        if not topic.replace("_", "").replace("-", "").replace(".", "").replace(":", "").isalnum():
            topic = ""
        sidecars = _safe_patchdrop_sidecars(row.get("requiredSidecars"))
        isolation = row.get("requiredSourceIsolation")
        if not isinstance(isolation, dict):
            isolation = {}
        guard = str(isolation.get("guard") or isolation.get("sourceIsolation.guard") or "").strip()
        source_root_kind = str(isolation.get("sourceRootKind") or "").strip()
        direct_canonical_source_edit = isolation.get("directCanonicalSourceEdit") is True
        desktop_final_proof = str(isolation.get("desktopFinalProof") or "").strip()
        try:
            raw_secret_pattern_hits = int(isolation.get("rawSecretPatternHits", 0) or 0)
        except Exception:
            raw_secret_pattern_hits = 0
        command = _safe_external_apply_command(row.get("applyCollectedEvidenceCommand"))
        if not sidecars or guard != "PASS" or source_root_kind != "local-worktree" or not command:
            continue
        safe: dict[str, Any] = {
            "action": "collect-external-evidence-files",
            "nodeRole": "desktop",
            "targetRole": target_role,
            "requiredSidecars": sidecars,
            "requiredSourceIsolation": {
                "guard": guard,
                "sourceRootKind": source_root_kind,
                "directCanonicalSourceEdit": direct_canonical_source_edit,
                "desktopFinalProof": desktop_final_proof or "evidence_needed",
                "rawSecretPatternHits": raw_secret_pattern_hits,
            },
            "applyCollectedEvidenceCommand": command,
            "producerCommandTemplates": _safe_producer_command_templates(target_role, topic),
            "nextActions": [
                f"run_{target_role}_external_node_smoke",
                f"collect_{target_role}_producer_handoff_json",
                f"submit_{target_role}_patchdrop_v3_bundle_sidecars",
            ],
            "decision": str(row.get("decision") or "evidence_needed"),
        }
        if topic:
            safe["topic"] = topic
        out.append(safe)
    return out


def _safe_archive_apply_command(value: Any) -> str:
    text = str(value or "").strip()
    expected = "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search"
    if text == expected:
        return text
    return ""


def _archive_action_names(value: Any) -> list[str]:
    allowed = {
        "create_or_point_archive_index",
        "run_archive_index_build",
        "set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT",
        "verify_archive_index_path",
        "rerun_archive_search_with_index_path",
        "rerun_archive_search",
    }
    return [item for item in _safe_names(value) if item in allowed]


def _default_archive_index_detail(report: dict[str, Any]) -> dict[str, Any]:
    next_actions = _archive_action_names(report.get("nextActions"))
    if not next_actions:
        next_actions = [
            "create_or_point_archive_index",
            "run_archive_index_build",
            "set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT",
            "verify_archive_index_path",
            "rerun_archive_search_with_index_path",
            "rerun_archive_search",
        ]
    return {
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
        "nextActions": next_actions,
        "decision": "evidence_needed",
    }


def _safe_archive_index_details(report: dict[str, Any]) -> list[dict[str, Any]]:
    details = report.get("nextActionDetails")
    rows = details if isinstance(details, list) else []
    out: list[dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict) or row.get("action") != "collect-archive-index-proof":
            continue
        index_path = _safe_relative_path(row.get("indexPathRecommendation")) or "BackupsXS/index.jsonl"
        archive_root = _safe_relative_path(row.get("archiveRootRecommendation")) or "BackupsXS"
        command = _safe_archive_apply_command(row.get("applyCollectedEvidenceCommand")) or (
            "powershell -NoProfile -ExecutionPolicy Bypass "
            "-File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search"
        )
        safe = {
            "action": "collect-archive-index-proof",
            "nodeRole": "desktop",
            "targetService": "archive",
            "readOnly": row.get("readOnly") is not False,
            "mutationAllowed": row.get("mutationAllowed") is True,
            "requiredEnvNames": _safe_names(row.get("requiredEnvNames")) or ["ARCHIVE_INDEX", "NAS_ARCHIVE_ROOT"],
            "requiredMcpTools": _safe_tool_names(row.get("requiredMcpTools")) or ["archive.search"],
            "indexPathRecommendation": index_path,
            "archiveRootRecommendation": archive_root,
            "applyCollectedEvidenceCommand": command,
            "nextActions": _archive_action_names(row.get("nextActions")),
            "decision": str(row.get("decision") or "evidence_needed"),
        }
        if not safe["nextActions"]:
            safe["nextActions"] = _default_archive_index_detail(report)["nextActions"]
        out.append(safe)
    if out:
        return out

    evidence_text = " ".join(str(item) for item in report.get("evidence_needed", [])).lower()
    next_actions = _archive_action_names(report.get("nextActions"))
    if next_actions or "backupsxs" in evidence_text or "archive" in evidence_text:
        return [_default_archive_index_detail(report)]
    return []


def _safe_source_action_details(next_source_action: str) -> list[dict[str, Any]]:
    if next_source_action == "rerun_db_gap_scanner":
        return [
            {
                "action": "rerun-db-gap-scanner",
                "scope": "local_evidence",
                "nodeRole": "desktop",
                "readOnly": True,
                "mutationAllowed": False,
                "commands": [
                    "python scripts\\db_gap_scanner.py --root . --output data\\db-gap-report --format json",
                    "python scripts\\source_health_scorecard.py --root . --output verification\\source-health-scorecard.json",
                    "python scripts\\awx_mcp_completion_audit.py --root . --output var\\codex-smoke\\awx-mcp-completion-audit-control-tower-current.json",
                ],
                "decision": "db_gap_matrix_stale_or_missing_generated_at",
            }
        ]
    if next_source_action == "rerun_completion_audit":
        return [
            {
                "action": "rerun-completion-audit",
                "scope": "local_evidence",
                "nodeRole": "desktop",
                "readOnly": True,
                "mutationAllowed": False,
                "commands": [
                    "python scripts\\awx_mcp_completion_audit.py --root . --output var\\codex-smoke\\awx-mcp-completion-audit-control-tower-current.json",
                    "python scripts\\source_health_scorecard.py --root . --output verification\\source-health-scorecard.json",
                ],
                "decision": "completion_audit_stale_or_missing_generated_at",
            }
        ]
    if next_source_action == "source_runtime_proof_current":
        return [
            {
                "action": "source-runtime-proof-current",
                "scope": "active_source",
                "nodeRole": "desktop",
                "readOnly": True,
                "mutationAllowed": False,
                "proofState": "current",
                "commands": [
                    "python scripts\\source_health_scorecard.py --root . --output verification\\source-health-scorecard.json",
                    "python scripts\\awx_mcp_completion_audit.py --root .",
                    ".\\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene -x test --no-daemon --project-cache-dir <desktop-cache>",
                    ".\\verify_boot.ps1 -TimeoutSeconds 45 -LogPath logs\\verify_boot_source_runtime_current.log",
                ],
                "requiredMarkers": [
                    "sourceContractProofPassed=true",
                    "broadRuntimeTestProofPassed=true",
                    "applicationReadyEventProven=True with probeMode=application-ready-event-proven means ApplicationReadyEvent listener evidence was observed before intentional stop",
                    "bootStartedProven=True with probeMode=boot-started-proven means Spring startup marker was observed before intentional stop",
                    "runtimePrecheckProven=True with probeMode=runtime-precheck-proven means Tomcat and WiringPrecheck completed before intentional stop",
                    "bootSuccessProven=False means blocker-scan-only, not boot success",
                ],
                "decision": "local_runtime_proof_current",
            }
        ]
    if next_source_action == "audit_top_broad_catches_for_redacted_breadcrumbs":
        return [
            {
                "action": "audit-broad-catches-redacted-breadcrumbs",
                "sourceContract": True,
                "scope": "active_source",
                "nodeRole": "desktop",
                "readOnly": True,
                "mutationAllowed": False,
                "targetFiles": [
                    "main/java/com/example/lms/service",
                    "main/java/com/example/lms/trace",
                    "main/java/ai/abandonware/nova/orch/aop",
                    "main/java/ai/abandonware/nova/boot",
                ],
                "focusedTests": [
                    "com.example.lms.service.ChatWorkflowTraceRedactionContractTest",
                    "com.example.lms.guard.RemainingMediumEmptyCatchContractTest",
                    "com.example.lms.trace.TraceUtilityFallbackBreadcrumbContractTest",
                    "ai.abandonware.nova.orch.aop.WebFailSoftTraceSuppressionsTest",
                ],
                "commands": [
                    ".\\gradlew.bat test --tests \"com.example.lms.service.ChatWorkflowTraceRedactionContractTest\" --no-daemon --project-cache-dir <desktop-cache>",
                    ".\\gradlew.bat test --tests \"com.example.lms.guard.RemainingMediumEmptyCatchContractTest\" --no-daemon --project-cache-dir <desktop-cache>",
                    ".\\gradlew.bat test --tests \"com.example.lms.trace.TraceUtilityFallbackBreadcrumbContractTest\" --no-daemon --project-cache-dir <desktop-cache>",
                    ".\\gradlew.bat test --tests \"ai.abandonware.nova.orch.aop.WebFailSoftTraceSuppressionsTest\" --no-daemon --project-cache-dir <desktop-cache>",
                ],
                "requiredTraceKeys": [
                    "failSoft.suppressed.stage",
                    "failSoft.suppressed.errorType",
                    "ctx.debugPort.suppressed.count",
                    "ctx.propagation.missing.count",
                    "reactor.onErrorDropped.count",
                ],
                "decision": "local_contract_ready",
            }
        ]
    if next_source_action != "continue_small_contract_tests_on_cross_subsystem_runtime_seams":
        return []
    return [
        {
            "action": "run-cross-subsystem-contract-tests",
            "scope": "active_source",
            "readOnly": True,
            "mutationAllowed": False,
            "targetFiles": [
                "main/java/com/example/lms/orchestration/StrategyConflictResolver.java",
                "main/java/com/example/lms/orchestration/ExecutionPlanApplier.java",
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java",
                "main/java/ai/abandonware/nova/orch/aop/RagCompressionAspect.java",
                "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java",
            ],
            "affectedSubsystems": [
                "S01_Overdrive",
                "S05_ExtremeZ",
                "S06_Hypernova",
                "S07_CIH",
                "S08_Adapter",
            ],
            "focusedTests": [
                "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                "com.example.lms.orchestration.StrategyConflictResolverTest",
                "com.example.lms.orchestration.ExecutionPlanApplierTest",
                "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
            ],
            "commands": [
                ".\\gradlew.bat test --tests \"ai.abandonware.nova.orch.aop.AspectOrderingContractTest\" --no-daemon --project-cache-dir <desktop-cache>",
                ".\\gradlew.bat test --tests \"com.example.lms.orchestration.StrategyConflictResolverTest\" --no-daemon --project-cache-dir <desktop-cache>",
                ".\\gradlew.bat test --tests \"com.example.lms.orchestration.ExecutionPlanApplierTest\" --no-daemon --project-cache-dir <desktop-cache>",
                ".\\gradlew.bat test --tests \"com.example.lms.service.rag.burst.ExtremeZTriggerTest\" --no-daemon --project-cache-dir <desktop-cache>",
            ],
            "requiredTraceKeys": [
                "boosterMode.active",
                "boosterMode.excludedModes",
                "specialMode.conflict.suppressed",
                "routing.executionPlan.applied.primaryMode",
                "extremeZ.bypassReason",
            ],
            "decision": "local_contract_ready",
        }
    ]


def _component(
    *,
    component_id: str,
    label: str,
    weight: float,
    normalized: float,
    confidence: float,
    evidence: str,
    formula: str,
) -> dict[str, Any]:
    normalized = _round4(_clamp(normalized))
    confidence = _round4(_clamp(confidence))
    weighted_points = _round4(weight * normalized * confidence * 100.0)
    return {
        "id": component_id,
        "label": label,
        "weight": weight,
        "normalized": normalized,
        "confidence": confidence,
        "weightedPoints": weighted_points,
        "evidence": evidence,
        "formula": formula,
    }


def _severity(risk_score: float) -> str:
    if risk_score >= 0.66:
        return "high"
    if risk_score >= 0.33:
        return "medium"
    return "low"


def _risk_status(scope: str, risk_score: float) -> tuple[str, str]:
    if risk_score <= 0.0:
        return (
            "monitored",
            "riskScore=0.0; current evidence keeps this risk in the monitor-only lane",
        )
    if scope != "active_source":
        return (
            "evidence_needed",
            "external or project-scoped proof is required before this risk can be cleared",
        )
    return (
        "action_required",
        "active-source risk remains above zero and needs a focused proof or patch",
    )


def _risk(
    *,
    risk_id: str,
    label: str,
    risk_score: float,
    evidence: str,
    next_action: str,
    scope: str = "active_source",
    status: str | None = None,
    status_reason: str | None = None,
) -> dict[str, Any]:
    risk_score = _round4(_clamp(risk_score))
    if status is None:
        status, status_reason = _risk_status(scope, risk_score)
    elif not status_reason:
        status_reason = "status was assigned by a focused proof gate"
    return {
        "id": risk_id,
        "label": label,
        "scope": scope,
        "riskScore": risk_score,
        "severity": _severity(risk_score),
        "status": status,
        "statusReason": status_reason,
        "evidence": evidence,
        "nextAction": next_action,
    }


def build_scorecard(root: Path) -> dict[str, Any]:
    root = root.resolve()
    quant = _read_json(root, DEFAULT_QUANT_METRICS)
    harmony = _read_json(root, DEFAULT_HARMONY_METRICS)
    test_tree = _read_json(root, DEFAULT_TEST_TREE_METRICS)
    websoak_provider = _read_json(root, DEFAULT_WEBSOAK_PROVIDER_SMOKE)
    db_gap_matrix = _read_json(root, DEFAULT_DB_GAP_MATRIX)
    (
        db_gap_matrix_has_generated_at,
        db_gap_matrix_fresh,
        db_gap_matrix_age_seconds,
        db_gap_matrix_freshness_status,
    ) = _artifact_freshness(
        db_gap_matrix.get("generatedAt") if db_gap_matrix else "",
        DB_GAP_MATRIX_MAX_AGE_SECONDS,
    )
    db_gap_matrix_current = (
        bool(db_gap_matrix)
        and db_gap_matrix_has_generated_at
        and db_gap_matrix_fresh
        and db_gap_matrix_freshness_status == "current"
    )
    completion_audit, completion_audit_path = _read_latest_completion_audit(root)
    (
        completion_audit_has_generated_at,
        completion_audit_fresh,
        completion_audit_age_seconds,
        completion_audit_freshness_status,
    ) = _artifact_freshness(
        completion_audit.get("generatedAt") if completion_audit_path else "",
        COMPLETION_AUDIT_MAX_AGE_SECONDS,
    )
    completion_audit_current = (
        bool(completion_audit_path)
        and completion_audit_has_generated_at
        and completion_audit_fresh
        and completion_audit_freshness_status == "current"
    )
    next_action_details = (
        _safe_supabase_live_proof_details(completion_audit)
        + _safe_external_evidence_details(completion_audit)
        + _safe_archive_index_details(completion_audit)
    )

    harmony_summary = quant.get("harmonyPressureSummary", {})
    if harmony:
        harmony_summary = {**harmony_summary, **harmony}
    test_summary = quant.get("testTreeContamination", {})
    if test_tree:
        test_summary = {**test_summary, **test_tree}
    supabase = quant.get("supabaseReadonlySmoke", {})
    provider = quant.get("runtimeProviderDisabledSmoke", {})
    websoak_summary = websoak_provider.get("summary", {}) if isinstance(websoak_provider, dict) else {}
    if isinstance(websoak_summary, dict) and websoak_summary:
        provider = {**provider, **websoak_summary}

    duplicate_fqcn = int(quant.get("duplicateFqcnActiveCount", 0) or 0)
    secret_hits = int(quant.get("secretPatternHitCount", 0) or 0)
    source_integrity = 1.0 if duplicate_fqcn == 0 and secret_hits == 0 else 0.0

    provider_ok = (
        int(provider.get("status", 0) or 0) == 200
        and bool(provider.get("providerDisabledOrSkipped", False))
        and int(provider.get("secretPatternHits", 0) or 0) == 0
        and int(provider.get("rawQueryHits", 0) or 0) == 0
    )
    provider_health = 0.93 if provider_ok else 0.45

    aspect_files = int(harmony_summary.get("aspectFiles", 0) or 0)
    critical_unordered = int(harmony_summary.get("criticalUnorderedAspectCount", 0) or 0)
    cross_large_total = int(harmony_summary.get("crossSubsystemLargeFilesOver1000", 0) or 0)
    cross_large = int(
        harmony_summary.get("runtimeCrossSubsystemLargeFilesOver1000", cross_large_total)
        or 0
    )
    broad_catch_ratio = float(harmony_summary.get("broadCatchWithoutLocalBreadcrumbRatio", 0.0) or 0.0)
    aspect_order_coverage = float(harmony_summary.get("aspectOrderCoverageApprox", 0.0) or 0.0)
    critical_aspect_ratio = critical_unordered / aspect_files if aspect_files else 0.0
    harmony_pressure_risk = _clamp(
        (min(1.0, cross_large / 50.0) * 0.30)
        + (broad_catch_ratio * 0.25)
        + ((1.0 - aspect_order_coverage) * 0.25)
        + (critical_aspect_ratio * 0.20)
    )
    harmony_health = 1.0 - (harmony_pressure_risk * 0.50)

    test_risk = float(test_summary.get("riskScore", 0.0) or 0.0)
    test_health = 1.0 - _clamp(test_risk)

    supabase_read_only_safe = (
        bool(supabase.get("readOnlyMode", False))
        and not bool(supabase.get("mutationAllowed", True))
        and int(supabase.get("highConfidenceSecretHits", 0) or 0) == 0
    )
    supabase_project_status = str(supabase.get("projectScopeStatus", "missing"))
    supabase_snapshot_import = (
        db_gap_matrix.get("external_supabase_snapshot", {}).get("snapshotImport", {})
        if isinstance(db_gap_matrix, dict)
        else {}
    )
    supabase_missing_names = _safe_names(supabase.get("missingResultNames"))
    if not supabase_missing_names:
        supabase_missing_names = _safe_names(supabase_snapshot_import.get("missingResultNames"))
    supabase_data_api_missing_names = [
        name for name in supabase_missing_names
        if name in SUPABASE_DATA_API_EVIDENCE_NAMES
    ]
    supabase_data_api_result_sets_missing = bool(supabase_data_api_missing_names)
    supabase_smoke_ok, supabase_smoke_evidence = _checked_evidence(
        completion_audit,
        "supabase.readonly-snapshot-smoke",
    )
    supabase_local_smoke_contract_ready = (
        completion_audit_current
        and supabase_smoke_ok
        and supabase_smoke_evidence.get("reportsMissingResultNames") == "True"
        and supabase_smoke_evidence.get("reportsDataApiEvidenceMissing") == "True"
        and supabase_smoke_evidence.get("reportsEnvPreflight") == "True"
        and supabase_smoke_evidence.get("summaryArtifact") == "True"
        and supabase_smoke_evidence.get("summaryGeneratedAt") == "True"
        and supabase_smoke_evidence.get("summaryFresh") == "True"
        and supabase_smoke_evidence.get("summaryFreshnessStatus") == "current"
        and _evidence_int(supabase_smoke_evidence, "docsRefCount") >= 2
        and _evidence_int(supabase_smoke_evidence, "securityContractCount") >= 3
        and supabase_smoke_evidence.get("cliQueryMinVersion") == ">=2.79.0"
        and supabase_smoke_evidence.get("cliAdvisorsMinVersion") == ">=2.81.3"
        and supabase_smoke_evidence.get("mcpFallbackContract") == "True"
        and supabase_smoke_evidence.get("dataApiGrantProofRequired") == "True"
        and supabase_smoke_evidence.get("rlsPolicyProofRequired") == "True"
        and supabase_smoke_evidence.get("secretKeysBackendOnly") == "True"
        and supabase_smoke_evidence.get("rawSecretPatternHits") == "0"
        and supabase_smoke_evidence.get("summarySecretHits") == "0"
        and supabase_smoke_evidence.get("summaryRawSecretPatternHits") == "0"
        and supabase_smoke_evidence.get("summaryHighConfidenceSecretHits") == "0"
        and supabase_smoke_evidence.get("summaryRawJdbcUrlHits") == "0"
        and "envPresentCount" in supabase_smoke_evidence
        and "projectRefEnvPresent" in supabase_smoke_evidence
        and "accessTokenEnvPresent" in supabase_smoke_evidence
        and "cliPresent" in supabase_smoke_evidence
        and "contextEvidenceNeededCount" in supabase_smoke_evidence
    )
    computer_use_ok, computer_use_evidence = _checked_evidence(
        completion_audit,
        "computer-use.gui-proof-boundary",
    )
    computer_use_boundary_ready = (
        completion_audit_current
        and computer_use_ok
        and computer_use_evidence.get("promptPack") == "True"
        and computer_use_evidence.get("guiOnly") == "True"
        and computer_use_evidence.get("noTerminalAutomation") == "True"
        and computer_use_evidence.get("supportingOnly") == "True"
        and computer_use_evidence.get("helperArtifact") == "True"
        and computer_use_evidence.get("helperReachable") == "True"
        and computer_use_evidence.get("helperGeneratedAt") == "True"
        and computer_use_evidence.get("helperFresh") == "True"
        and computer_use_evidence.get("helperFreshnessStatus") == "current"
        and computer_use_evidence.get("helperCountOnly") == "True"
        and computer_use_evidence.get("helperBoundary") == "True"
        and computer_use_evidence.get("storesAppNames") == "False"
        and computer_use_evidence.get("storesWindowTitles") == "False"
        and computer_use_evidence.get("helperSecretPatternHits") == "0"
        and computer_use_evidence.get("rawSecretPatternHits") == "0"
    )
    computer_use_health = 1.0 if computer_use_boundary_ready else 0.45
    supabase_health = 0.60 if supabase_read_only_safe else 0.25
    if supabase_project_status not in ("project_ref_missing", "missing"):
        supabase_health = (
            0.65 if supabase_read_only_safe and supabase_data_api_result_sets_missing
            else 0.85 if supabase_read_only_safe
            else supabase_health
        )

    metric_integrity = 1.0 if quant and harmony_summary and test_summary else 0.55

    large_files = int(quant.get("largeActiveFilesOver2000", 0) or 0)
    p95_loc = float(quant.get("activeJavaLocP95", 0.0) or 0.0)
    maintainability_risk = _clamp((min(1.0, large_files / 24.0) * 0.55) + (min(1.0, p95_loc / 1000.0) * 0.45))
    maintainability_health = 1.0 - (maintainability_risk * 0.55)

    components = [
        _component(
            component_id="source_integrity",
            label="SourceSet, duplicate FQCN, and secret-scan integrity",
            weight=0.20,
            normalized=source_integrity,
            confidence=0.98,
            evidence=f"duplicateFqcnActiveCount={duplicate_fqcn}; secretPatternHitCount={secret_hits}",
            formula="PASS when duplicateFqcnActiveCount=0 and secretPatternHitCount=0",
        ),
        _component(
            component_id="runtime_provider_safety",
            label="Provider-disabled runtime smoke and redaction",
            weight=0.20,
            normalized=provider_health,
            confidence=0.90,
            evidence=f"status={provider.get('status')}; providerDisabledOrSkipped={provider.get('providerDisabledOrSkipped')}; rawQueryHits={provider.get('rawQueryHits')}",
            formula="0.93 when runtime smoke reaches 200 with provider-disabled/skipped and no secret/raw-query hits",
        ),
        _component(
            component_id="harmony_pressure",
            label="Cross-subsystem, catch, and AOP-order pressure",
            weight=0.15,
            normalized=harmony_health,
            confidence=0.90,
            evidence=f"runtimeCrossSubsystemLargeFilesOver1000={cross_large}; crossSubsystemLargeFilesOver1000={cross_large_total}; broadCatchWithoutLocalBreadcrumbRatio={broad_catch_ratio}; aspectOrderCoverageApprox={aspect_order_coverage}; criticalUnorderedAspectCount={critical_unordered}",
            formula="1 - 0.5 * weighted pressure risk",
        ),
        _component(
            component_id="test_tree_reliability",
            label="Broad test-tree active-source alignment",
            weight=0.10,
            normalized=test_health,
            confidence=0.92,
            evidence=f"missingImportCount={test_summary.get('missingImportCount')}; affectedTestFileCount={test_summary.get('affectedTestFileCount')}; riskScore={test_summary.get('riskScore')}",
            formula="1 - testTreeContamination.riskScore",
        ),
        _component(
            component_id="supabase_external_evidence",
            label="Supabase read-only project evidence",
            weight=0.10,
            normalized=supabase_health,
            confidence=0.72,
            evidence=(
                f"readOnlyMode={supabase.get('readOnlyMode')}; "
                f"mutationAllowed={supabase.get('mutationAllowed')}; "
                f"projectScopeStatus={supabase_project_status}; "
                f"missingResultNames={','.join(supabase_missing_names)}; "
                f"docsRefCount={supabase_smoke_evidence.get('docsRefCount')}; "
                f"securityContractCount={supabase_smoke_evidence.get('securityContractCount')}; "
                f"summaryGeneratedAt={supabase_smoke_evidence.get('summaryGeneratedAt')}; "
                f"summaryFresh={supabase_smoke_evidence.get('summaryFresh')}; "
                f"summaryFreshnessStatus={supabase_smoke_evidence.get('summaryFreshnessStatus')}; "
                f"summaryAgeSeconds={supabase_smoke_evidence.get('summaryAgeSeconds')}; "
                f"summarySecretHits={supabase_smoke_evidence.get('summarySecretHits')}; "
                f"summaryRawSecretPatternHits={supabase_smoke_evidence.get('summaryRawSecretPatternHits')}; "
                f"rawSecretPatternHits={supabase_smoke_evidence.get('rawSecretPatternHits')}; "
                f"cliQueryMinVersion={supabase_smoke_evidence.get('cliQueryMinVersion')}; "
                f"cliAdvisorsMinVersion={supabase_smoke_evidence.get('cliAdvisorsMinVersion')}; "
                f"mcpFallbackContract={supabase_smoke_evidence.get('mcpFallbackContract')}; "
                f"dataApiGrantProofRequired={supabase_smoke_evidence.get('dataApiGrantProofRequired')}; "
                f"rlsPolicyProofRequired={supabase_smoke_evidence.get('rlsPolicyProofRequired')}; "
                f"localSmokeContractReady={supabase_local_smoke_contract_ready}"
            ),
            formula="0.60 for read-only safe but project-scoped proof missing; 0.85 when project-scoped proof is available",
        ),
        _component(
            component_id="metric_integrity",
            label="Metric artifact completeness and parseability",
            weight=0.10,
            normalized=metric_integrity,
            confidence=0.94,
            evidence="quant, harmony, and test-tree JSON artifacts parsed",
            formula="PASS when required metric artifacts are present and parseable",
        ),
        _component(
            component_id="computer_use_evidence_boundary",
            label="Computer Use GUI-only supporting evidence boundary",
            weight=0.05,
            normalized=computer_use_health,
            confidence=0.88,
            evidence=(
                f"promptPack={computer_use_evidence.get('promptPack')}; "
                f"guiOnly={computer_use_evidence.get('guiOnly')}; "
                f"noTerminalAutomation={computer_use_evidence.get('noTerminalAutomation')}; "
                f"supportingOnly={computer_use_evidence.get('supportingOnly')}; "
                f"helperArtifact={computer_use_evidence.get('helperArtifact')}; "
                f"helperReachable={computer_use_evidence.get('helperReachable')}; "
                f"helperGeneratedAt={computer_use_evidence.get('helperGeneratedAt')}; "
                f"helperFresh={computer_use_evidence.get('helperFresh')}; "
                f"helperFreshnessStatus={computer_use_evidence.get('helperFreshnessStatus')}; "
                f"helperAgeSeconds={computer_use_evidence.get('helperAgeSeconds')}; "
                f"helperCountOnly={computer_use_evidence.get('helperCountOnly')}; "
                f"helperBoundary={computer_use_evidence.get('helperBoundary')}; "
                f"storesAppNames={computer_use_evidence.get('storesAppNames')}; "
                f"storesWindowTitles={computer_use_evidence.get('storesWindowTitles')}; "
                f"appCount={computer_use_evidence.get('appCount')}; "
                f"targetableWindowCount={computer_use_evidence.get('targetableWindowCount')}; "
                f"helperSecretPatternHits={computer_use_evidence.get('helperSecretPatternHits')}; "
                f"rawSecretPatternHits={computer_use_evidence.get('rawSecretPatternHits')}; "
                f"boundaryReady={computer_use_boundary_ready}"
            ),
            formula="PASS when Computer Use is limited to GUI/browser proof, not source or terminal automation",
        ),
        _component(
            component_id="scope_maintainability",
            label="Large-file and active LOC maintainability pressure",
            weight=0.10,
            normalized=maintainability_health,
            confidence=0.95,
            evidence=f"largeActiveFilesOver2000={large_files}; activeJavaLocP95={p95_loc}",
            formula="1 - 0.55 * weighted large-file/p95 pressure",
        ),
    ]

    score = _round4(sum(component["weightedPoints"] for component in components))
    aspect_order_risk = _clamp(max(1.0 - aspect_order_coverage, critical_aspect_ratio)) if aspect_files else 0.0
    aspect_order_contract_present = _aspect_order_contract_present(root)
    silent_swallow_risk = _clamp(broad_catch_ratio)
    cross_subsystem_risk = _clamp(cross_large / 50.0)
    cross_subsystem_contract_proof = _focused_cross_subsystem_contract_proof(root)
    cross_subsystem_contract_passed = bool(cross_subsystem_contract_proof.get("passed"))
    broad_runtime_test_proof = _broad_runtime_test_proof(root)
    broad_runtime_test_passed = bool(broad_runtime_test_proof.get("passed"))
    risk_ledger = [
        _risk(
            risk_id="aspect_order_hotspots",
            label="Implicit AOP ordering on critical cross-subsystem aspects",
            risk_score=aspect_order_risk,
            evidence=(
                f"unorderedAspectCount={harmony_summary.get('unorderedAspectCount')}; "
                f"criticalUnorderedAspectCount={critical_unordered}; "
                f"contractPresent={aspect_order_contract_present}; "
                f"top={_top_file(harmony_summary.get('topUnorderedAspectHotspots', []))}"
            ),
            next_action=(
                "Aspect ordering proof is current; keep the existing order contract green before search/LLM routing patches."
                if aspect_order_contract_present and aspect_order_risk == 0.0 and broad_runtime_test_passed
                else "Run focused AspectOrderingContractTest before search/LLM routing patches and keep the existing order contract green."
                if aspect_order_contract_present and aspect_order_risk == 0.0
                else "Add explicit order/call-path contracts for ExtremeZBurstAspect, LlmRouterAspect, and RagCompressionAspect before search/LLM routing patches."
            ),
        ),
        _risk(
            risk_id="cross_subsystem_concentration",
            label="Large files holding multiple S01-S08 subsystem families",
            risk_score=cross_subsystem_risk,
            evidence=(
                f"runtimeCrossSubsystemLargeFilesOver1000={cross_large}; "
                f"crossSubsystemLargeFilesOver1000={cross_large_total}; "
                f"focusedContractTests={'passed' if cross_subsystem_contract_passed else 'missing'}; "
                f"focusedContractTestCount={cross_subsystem_contract_proof.get('passedCount', 0)}/"
                f"{cross_subsystem_contract_proof.get('requiredCount', len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS))}"
            ),
            next_action=(
                "Broad runtime proof is current; keep safe patches focused and preserve the broad proof gate."
                if cross_subsystem_contract_passed and broad_runtime_test_passed
                else "Focused cross-subsystem contract tests passed; keep safe patches focused and move the next gate to broad runtime proof."
                if cross_subsystem_contract_passed
                else "Keep safe patches focused; split only with contract tests around the exact runtime seam."
            ),
            status="guarded" if cross_subsystem_contract_passed and broad_runtime_test_passed else None,
            status_reason=(
                "focused cross-subsystem contracts and broad runtime proof are current; monitor pressure without refactoring"
                if cross_subsystem_contract_passed and broad_runtime_test_passed
                else None
            ),
        ),
        _risk(
            risk_id="silent_swallow_pressure",
            label="Broad catches without nearby trace/log/breadcrumb",
            risk_score=silent_swallow_risk,
            evidence=f"broadCatchWithoutLocalBreadcrumbRatio={broad_catch_ratio}",
            next_action="Audit broad catches in top cross-subsystem files and add redacted breadcrumbs, not blanket catch rewrites.",
        ),
        _risk(
            risk_id="test_tree_contamination",
            label="Broad test tree imports no longer aligned with active source FQCNs",
            risk_score=_clamp(test_risk),
            evidence=f"missingImportCount={test_summary.get('missingImportCount')}; affectedTestFileCount={test_summary.get('affectedTestFileCount')}",
            next_action=(
                "Broad runtime proof is current; keep compileTestJava in the proof lane and preserve the broad test gate."
                if test_risk == 0 and broad_runtime_test_passed
                else "Keep compileTestJava in the proof lane and move the next gate to full test runtime proof."
                if test_risk == 0
                else "Realign or quarantine affected test files before treating broad test as final proof."
            ),
        ),
        _risk(
            risk_id="supabase_live_proof_missing",
            label="Supabase schema/advisor proof not project-scoped",
            risk_score=(
                0.40 if supabase_project_status == "project_ref_missing"
                else 0.25 if supabase_data_api_result_sets_missing
                else 0.15
            ),
            evidence=f"projectScopeStatus={supabase_project_status}; readOnlyMode={supabase.get('readOnlyMode')}; missingResultNames={','.join(supabase_missing_names)}",
            next_action=(
                "Collect Supabase data_api_role_grants, rls_and_table_flags, and exposed_tables_without_rls before claiming Data API/RLS health."
                if supabase_data_api_result_sets_missing and supabase_project_status != "project_ref_missing"
                else "Provide project_ref/authenticated read-only MCP or CLI path before claiming live Supabase health."
            ),
            scope="external_evidence",
        ),
    ]
    active_risk_count = sum(
        1 for row in risk_ledger
        if row.get("status") == "action_required" and row.get("scope") == "active_source"
    )

    evidence_needed = []
    if db_gap_matrix and not db_gap_matrix_current:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "db_gap_matrix_freshness",
                "reason": (
                    "DB gap matrix must include generatedAt and be current before reusing Supabase gap evidence: "
                    f"status={db_gap_matrix_freshness_status}"
                ),
            }
        )
    if completion_audit_path and not completion_audit_current:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "completion_audit_freshness",
                "reason": (
                    "latest completion audit must include generatedAt and be current: "
                    f"status={completion_audit_freshness_status}"
                ),
            }
        )
    if test_risk > 0:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "broad_test_tree_compile",
                "reason": "test-tree contamination remains nonzero",
            }
        )
    if supabase_project_status == "project_ref_missing":
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "supabase_project_scoped_schema_and_advisor_snapshot",
                "reason": "project_ref/authenticated read-only path missing",
            }
        )
    if supabase_data_api_result_sets_missing:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "supabase_data_api_grants_and_rls_result_sets",
                "reason": (
                    "missing result sets required after Supabase Data API explicit grant change: "
                    + ",".join(supabase_data_api_missing_names)
                ),
            }
        )
    if completion_audit_path and not supabase_local_smoke_contract_ready:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "supabase_readonly_snapshot_smoke_contract",
                "reason": (
                    "completion audit lacks missingResultNames/dataApiEvidenceMissing/docsRefCount/"
                    "securityContractCount/freshSummary/cliVersionRequirements/mcpFallbackContract/"
                    "dataApiGrantProofRequired/rlsPolicyProofRequired redacted summarySecretHits/"
                    "summaryRawSecretPatternHits readiness flags"
                ),
            }
        )
    if completion_audit_path and not computer_use_boundary_ready:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "computer_use_gui_proof_boundary",
                "reason": (
                    "completion audit lacks promptPack/guiOnly/noTerminalAutomation/"
                    "supportingOnly/helperArtifact/helperReachable/fresh helper/count-only/"
                    "rawSecretPatternHits Computer Use boundary flags"
                ),
            }
        )

    if db_gap_matrix and not db_gap_matrix_current:
        next_single_action = "rerun_db_gap_scanner"
    elif completion_audit_path and not completion_audit_current:
        next_single_action = "rerun_completion_audit"
    elif completion_audit_path and not supabase_local_smoke_contract_ready:
        next_single_action = "repair_supabase_readonly_snapshot_smoke_contract"
    elif completion_audit_path and not computer_use_boundary_ready:
        next_single_action = "repair_computer_use_gui_proof_boundary"
    elif supabase_project_status == "project_ref_missing":
        next_single_action = "provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot"
    elif supabase_data_api_result_sets_missing:
        next_single_action = "collect_supabase_data_api_grants_and_rls_result_sets"
    elif test_risk > 0:
        next_single_action = "realign_or_quarantine_test_tree_contamination_before_broad_test_gate"
    else:
        next_single_action = "run_broad_test_runtime_proof_or_triage_top_aspect_order_hotspots"

    if db_gap_matrix and not db_gap_matrix_current:
        next_source_action = "rerun_db_gap_scanner"
    elif completion_audit_path and not completion_audit_current:
        next_source_action = "rerun_completion_audit"
    elif completion_audit_path and not supabase_local_smoke_contract_ready:
        next_source_action = "repair_supabase_readonly_snapshot_smoke_contract"
    elif test_risk > 0:
        next_source_action = "realign_or_quarantine_test_tree_contamination_before_broad_test_gate"
    elif aspect_order_risk > 0:
        next_source_action = "triage_top_aspect_order_hotspots"
    elif silent_swallow_risk > 0:
        next_source_action = "audit_top_broad_catches_for_redacted_breadcrumbs"
    elif cross_subsystem_risk >= 0.33 and not cross_subsystem_contract_passed:
        next_source_action = "continue_small_contract_tests_on_cross_subsystem_runtime_seams"
    elif not broad_runtime_test_passed:
        next_source_action = "run_broad_test_runtime_proof"
    else:
        next_source_action = "source_runtime_proof_current"

    return {
        "generatedAt": _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds"),
        "decision": "source_health_scorecard",
        "scoreKind": "strict_evidence_adjusted_source_health",
        "strictEvidenceAdjustedScore": score,
        "componentScores": components,
        "riskLedger": risk_ledger,
        "riskCount": len(risk_ledger),
        "activeRiskCount": active_risk_count,
        "topUnorderedAspectHotspots": harmony_summary.get("topUnorderedAspectHotspots", [])[:10],
        "completionAuditFreshness": {
            "generatedAt": completion_audit_has_generated_at,
            "fresh": completion_audit_fresh,
            "ageSeconds": completion_audit_age_seconds,
            "status": completion_audit_freshness_status,
            "path": completion_audit_path
            or (DEFAULT_COMPLETION_AUDIT_DIR / COMPLETION_AUDIT_GLOB).as_posix(),
            "pathHash": _stable_hash(
                completion_audit_path
                or (DEFAULT_COMPLETION_AUDIT_DIR / COMPLETION_AUDIT_GLOB).as_posix()
            ),
        },
        "dbGapMatrixFreshness": {
            "generatedAt": db_gap_matrix_has_generated_at,
            "fresh": db_gap_matrix_fresh,
            "ageSeconds": db_gap_matrix_age_seconds,
            "status": db_gap_matrix_freshness_status,
        },
        "evidenceNeeded": evidence_needed,
        "evidenceNeededCount": len(evidence_needed),
        "inputArtifacts": {
            "quantMetrics": DEFAULT_QUANT_METRICS.as_posix(),
            "harmonyPressure": DEFAULT_HARMONY_METRICS.as_posix(),
            "testTreeContamination": DEFAULT_TEST_TREE_METRICS.as_posix(),
            "websoakProviderSmoke": DEFAULT_WEBSOAK_PROVIDER_SMOKE.as_posix(),
            "dbGapMatrix": DEFAULT_DB_GAP_MATRIX.as_posix(),
            "completionAudit": completion_audit_path
            or (DEFAULT_COMPLETION_AUDIT_DIR / COMPLETION_AUDIT_GLOB).as_posix(),
        },
        "focusedCrossSubsystemContractProof": cross_subsystem_contract_proof,
        "broadRuntimeTestProof": broad_runtime_test_proof,
        "nextActionDetails": next_action_details,
        "nextSingleAction": next_single_action,
        "nextSourceAction": next_source_action,
        "nextSourceActionDetails": _safe_source_action_details(next_source_action),
    }


def _top_file(rows: Any) -> str:
    if isinstance(rows, list) and rows:
        first = rows[0]
        if isinstance(first, dict):
            return str(first.get("file", ""))
    return ""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate strict source-health scorecard from repo-owned metrics")
    parser.add_argument("--root", default=".", help="Repository root")
    parser.add_argument("--output", default=DEFAULT_OUTPUT.as_posix(), help="JSON output path")
    args = parser.parse_args(argv)

    root = Path(args.root)
    output = Path(args.output)
    if not output.is_absolute():
        output = root / output
    scorecard = build_scorecard(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(scorecard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "[AWX][source-health] "
        f"report={output} strictEvidenceAdjustedScore={scorecard['strictEvidenceAdjustedScore']} "
        f"riskCount={scorecard['riskCount']} activeRiskCount={scorecard['activeRiskCount']} "
        f"evidenceNeeded={len(scorecard['evidenceNeeded'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
