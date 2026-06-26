#!/usr/bin/env python3
"""Local completion audit for the AWX MCP-style control tower."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import urllib.parse
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "awx.mcp.completion_audit.v1"
HARMONY_RUNTIME_PROOF_SCHEMA_VERSION = "awx.mcp.harmony_runtime_proof.v1"
REQUIRED_TOOLS = [
    "source_scan",
    "patch_plan",
    "patch_render",
    "archive_search",
    "archive_restore",
    "boot_verify",
    "build_error_mine",
    "run_pipeline",
    "external_evidence_intake",
    "external_evidence_audit",
    "producer_command_plan",
    "producer_kit_export",
    "desktop_dispatch_packet",
    "desktop_control_loop",
    "supabase_context_probe",
    "supabase_schema_snapshot",
    "supabase_schema_snapshot_import",
]
REQUIRED_TASK_SKILLS = {
    "archive-search": "archive.search",
    "archive-restore": "archive.restore",
    "verify-boot": "verify_boot",
    "build-error-miner": "build_error_miner",
    "run-pipeline": "run_pipeline",
}
REQUIRED_AUDIT_FIELDS = [
    "requestId",
    "sessionId",
    "nodeRole",
    "toolName",
    "inputHash",
    "outputCount",
    "elapsedMs",
    "decision",
    "failReason",
]
ALLOWED_ENV_REFS = ["NAVER_KEYS", "NAVER_CLIENT_ID", "NAVER_CLIENT_SECRET"]
PATCHDROP_HANDOFF_REQUIRED_ARTIFACTS = {
    ".patch",
    ".report.md",
    ".verify.log",
    ".sha256.txt",
    ".manifest.json",
    "pendingNotice",
}
REQUIRED_NODE_SMOKE_TOOLS = {
    "source_scan",
    "agent_db_snapshot",
    "trace_snapshot_probe",
    "supabase_context_probe",
    "supabase_schema_snapshot",
    "archive_search",
    "patch_plan",
    "patch_render",
    "boot_verify",
    "build_error_mine",
    "run_pipeline",
    "archive_restore",
}
NODE_SMOKE_DECISION_ALLOWLIST = {
    "agent_db_snapshot": {
        "agent_db_snapshot_loaded",
        "agent_db_snapshot_http_error",
        "agent_db_snapshot_unavailable_with_local_fallback",
    },
    "trace_snapshot_probe": {
        "trace_snapshot_loaded",
        "trace_snapshot_http_error_with_local_fallback",
        "trace_snapshot_unavailable_with_local_fallback",
    },
    "supabase_context_probe": {
        "supabase_context_probe",
    },
    "supabase_schema_snapshot": {
        "supabase_schema_snapshot_evidence_needed",
    },
}
NODE_SMOKE_FALLBACK_DECISIONS = {
    "agent_db_snapshot": {
        "agent_db_snapshot_http_error",
        "agent_db_snapshot_unavailable_with_local_fallback",
    },
    "trace_snapshot_probe": {
        "trace_snapshot_http_error_with_local_fallback",
        "trace_snapshot_unavailable_with_local_fallback",
    },
}
EXTERNAL_NODE_EVIDENCE_NEEDED = (
    "external Mac mini/Notebook host smoke output, producer handoff JSON, and producer bundle sidecars / "
    "run scripts/awx_mcp_node_smoke.py from producer-local worktrees, "
    "generate PatchDrop .patch/.report.md/.verify.log/.sha256.txt/.manifest.json bundles plus pendingNotice, "
    "copy macmini-node-smoke.json, macmini-producer-handoff.json, notebook-node-smoke.json, "
    "and notebook-producer-handoff.json into data/agent-handoff/mcp-control-tower, "
    "or submit those files via __patch_drop__/external-node-proof and run external_evidence_intake, "
    "then external_evidence_audit"
)
EXTERNAL_PRODUCER_ROLES = ("macmini", "notebook")
PHASE2_ACTIVE_LEGACY_SIGNAL_TARGET = 200
PHASE2_HARMONY_HB_TARGET = 12
TEST_DISPATCH_PREFIX = "janitor-test-"
INCLUDE_TEST_DISPATCH_ENV = "AWX_COMPLETION_AUDIT_INCLUDE_TEST_DISPATCH"
COMPUTER_USE_HELPER_MAX_AGE_SECONDS = 24 * 60 * 60
SUPABASE_SMOKE_SUMMARY_MAX_AGE_SECONDS = 24 * 60 * 60
SUPABASE_SCHEMA_SNAPSHOT_MAX_AGE_SECONDS = 24 * 60 * 60
SOURCE_HEALTH_SCORECARD_MAX_AGE_SECONDS = 24 * 60 * 60
DB_GAP_REPORT_MAX_AGE_SECONDS = 24 * 60 * 60
GOAL_NEXT_AUTO_MAX_AGE_SECONDS = 60 * 60
SAFE_DELETE_PATH_PRESENCE_MAX_AGE_SECONDS = 24 * 60 * 60
CHAT_DEBUG_EVENTS_READBACK_MAX_AGE_SECONDS = 24 * 60 * 60
SECRET_PATTERN = re.compile(
    r"sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|"
    r"gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|"
    r"sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}"
)
SKIP_DIRS = {
    ".git",
    ".gradle",
    ".next",
    "__pycache__",
    "build",
    "node_modules",
    "out",
    "target",
}


def stable_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="ignore")).hexdigest()


def path_exists(path: Path) -> bool:
    try:
        return path.exists()
    except OSError:
        return False


def path_is_file(path: Path) -> bool:
    try:
        return path.is_file()
    except OSError:
        return False


def path_glob(path: Path, pattern: str) -> list[Path]:
    try:
        return sorted(path.glob(pattern))
    except OSError:
        return []


def slug(value: str) -> str:
    text = re.sub(r"[^A-Za-z0-9._-]+", "-", str(value).strip().lower()).strip("-")
    return text or "patchdrop-handoff"


def sha256_file(path: Path) -> str:
    if not path_exists(path):
        return ""
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        return ""
    return digest.hexdigest()


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def read_required_text(path: Path, label: str) -> tuple[str, str]:
    if not path_exists(path):
        return "", f"{label}=missing"
    try:
        return path.read_text(encoding="utf-8", errors="ignore"), ""
    except OSError:
        return "", f"{label}=unreadable"


def read_first_text(paths: list[Path]) -> tuple[str, Path | None]:
    for path in paths:
        text = read_text(path)
        if text:
            return text, path
    return "", None


def load_json(path: Path) -> dict[str, Any]:
    try:
        return json.loads(read_text(path).lstrip("\ufeff"))
    except Exception:
        return {}


def safe_scalar(value: Any, limit: int = 120) -> str:
    text = str(value or "").strip()
    text = re.sub(r"[\r\n\t]+", " ", text)
    return text[:limit]


def bounded_int(value: Any, fallback: int, minimum: int, maximum: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        number = fallback
    return max(minimum, min(maximum, number))


def artifact_freshness(raw_generated_at: Any, max_age_seconds: int) -> tuple[bool, bool, int, str]:
    text = str(raw_generated_at or "").strip()
    if not text:
        return False, False, -1, "missing_generated_at"
    try:
        generated_at = dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return False, False, -1, "invalid_generated_at"
    now = dt.datetime.now(dt.timezone.utc)
    if generated_at.tzinfo is None:
        generated_at = generated_at.replace(tzinfo=dt.timezone.utc)
    else:
        generated_at = generated_at.astimezone(dt.timezone.utc)
    age_seconds = int((now - generated_at).total_seconds())
    if age_seconds < -300:
        return True, False, age_seconds, "future_generated_at"
    if age_seconds > max_age_seconds:
        return True, False, age_seconds, "stale"
    return True, True, max(0, age_seconds), "current"


def safe_csv_names(raw: Any, limit: int = 24) -> str:
    if isinstance(raw, str):
        values: list[Any] = raw.split(",")
    elif isinstance(raw, list):
        values = raw
    else:
        return ""
    safe_names: list[str] = []
    for value in values:
        if not isinstance(value, str):
            continue
        text = value.strip()
        if not text or "\\" in text or "/" in text:
            continue
        safe_names.append(text[:80])
    return ",".join(safe_names[:limit])


def is_redacted_path_text(value: object) -> bool:
    text = str(value or "").strip()
    return text.startswith("pathHash=") and "pathLength=" in text


def supabase_artifact_local_path(root: Path, data: dict[str, Any], field: str, default_name: str) -> Path:
    raw = data.get(field) if isinstance(data.get(field), str) else ""
    if raw and not is_redacted_path_text(raw):
        path = Path(raw)
        return path if path.is_absolute() else root / path
    return root / "data" / "db-gap-report" / default_name


def add_check(
    checked: list[dict[str, Any]],
    failures: list[dict[str, str]],
    check_id: str,
    ok: bool,
    evidence: str,
    fail_reason: str,
) -> None:
    row = {
        "id": check_id,
        "ok": bool(ok),
        "evidence": evidence,
        "failReason": "" if ok else fail_reason,
    }
    checked.append(row)
    if not ok:
        failures.append({"id": check_id, "failReason": fail_reason})


def check_by_id(checked: list[dict[str, Any]], check_id: str) -> dict[str, Any]:
    for row in checked:
        if row.get("id") == check_id:
            return row
    return {}


def checks_ok(checked: list[dict[str, Any]], *check_ids: str) -> bool:
    return all(bool(check_by_id(checked, check_id).get("ok")) for check_id in check_ids)


def check_evidence(checked: list[dict[str, Any]], *check_ids: str) -> str:
    parts: list[str] = []
    for check_id in check_ids:
        row = check_by_id(checked, check_id)
        if row:
            parts.append(f"{check_id}: {row.get('evidence', '')}")
    return " | ".join(parts)


def safe_delete_path_presence_summary(root: Path) -> dict[str, Any]:
    artifact_path = root / "var" / "codex-smoke" / "safe-delete-path-presence-current.json"
    data = load_json(artifact_path)
    generated_present, generated_fresh, age_seconds, freshness_status = artifact_freshness(
        data.get("generatedAt"),
        SAFE_DELETE_PATH_PRESENCE_MAX_AGE_SECONDS,
    )
    candidates = data.get("candidates") if isinstance(data.get("candidates"), list) else []
    classifications = {
        safe_scalar(candidate.get("classification"), 80)
        for candidate in candidates
        if isinstance(candidate, dict)
    }
    present_classifications = {
        safe_scalar(candidate.get("classification"), 80)
        for candidate in candidates
        if isinstance(candidate, dict) and bool(candidate.get("exists"))
    }
    return {
        "present": bool(data),
        "schemaVersion": safe_scalar(data.get("schemaVersion"), 80),
        "generatedPresent": generated_present,
        "generatedFresh": generated_fresh,
        "ageSeconds": age_seconds,
        "freshnessStatus": freshness_status,
        "decision": safe_scalar(data.get("decision"), 80),
        "mutationAllowed": bool(data.get("mutationAllowed")),
        "deleteCommandEmitted": bool(data.get("deleteCommandEmitted")),
        "candidateCount": bounded_int(data.get("candidateCount"), 0, 0, 100_000),
        "presentCount": bounded_int(data.get("presentCount"), 0, 0, 100_000),
        "absentCount": bounded_int(data.get("absentCount"), 0, 0, 100_000),
        "reviewCandidateCount": bounded_int(data.get("reviewCandidateCount"), 0, 0, 100_000),
        "secretPathPresentCount": bounded_int(data.get("secretPathPresentCount"), 0, 0, 100_000),
        "rawSecretPatternHits": bounded_int(data.get("rawSecretPatternHits"), 0, 0, 100_000),
        "classifications": sorted(classification for classification in classifications if classification),
        "presentClassifications": sorted(
            classification for classification in present_classifications if classification
        ),
        "pathHash": stable_hash(str(artifact_path)),
    }


def safe_delete_path_presence_ready(summary: dict[str, Any]) -> bool:
    present_classifications = set(summary.get("presentClassifications") or [])
    return (
        bool(summary.get("present"))
        and summary.get("schemaVersion") == "awx.safe_delete_path_presence.v1"
        and summary.get("decision") == "safe_delete_path_presence"
        and bool(summary.get("generatedPresent"))
        and bool(summary.get("generatedFresh"))
        and summary.get("mutationAllowed") is False
        and summary.get("deleteCommandEmitted") is False
        and int(summary.get("rawSecretPatternHits") or 0) == 0
        and "HOLD_SECRET_PATH" in present_classifications
        and "REVIEW_REPORT_OUTPUT" in present_classifications
    )


def safe_delete_path_presence_evidence(summary: dict[str, Any]) -> str:
    return (
        f"schemaVersion={summary.get('schemaVersion')};"
        f"decision={summary.get('decision')};"
        f"generatedAt={summary.get('generatedPresent')};"
        f"fresh={summary.get('generatedFresh')};"
        f"freshnessStatus={summary.get('freshnessStatus')};"
        f"ageSeconds={summary.get('ageSeconds')};"
        f"candidateCount={summary.get('candidateCount')};"
        f"presentCount={summary.get('presentCount')};"
        f"absentCount={summary.get('absentCount')};"
        f"reviewCandidateCount={summary.get('reviewCandidateCount')};"
        f"secretPathPresentCount={summary.get('secretPathPresentCount')};"
        f"mutationAllowed={summary.get('mutationAllowed')};"
        f"deleteCommandEmitted={summary.get('deleteCommandEmitted')};"
        f"presentClassifications={','.join(summary.get('presentClassifications') or [])};"
        f"pathHash={summary.get('pathHash')};"
        f"rawSecretPatternHits={summary.get('rawSecretPatternHits')}"
    )


def supabase_project_ref_value_is_placeholder(value: str) -> bool:
    lowered = value.strip().lower()
    return (
        not lowered
        or lowered in {"<project_ref>", "<target-project-ref>", "project_ref", "project-ref"}
        or lowered.startswith("${")
        or "<" in lowered
        or ">" in lowered
    )


def supabase_mcp_root_config_summary(root: Path) -> dict[str, Any]:
    path = root / ".mcp.json"
    raw = read_text(path)
    data = load_json(path)
    servers = data.get("mcpServers") if isinstance(data.get("mcpServers"), dict) else {}
    server = servers.get("supabase") if isinstance(servers.get("supabase"), dict) else {}
    server_type = str(server.get("type") or "")
    url = str(server.get("url") or "")
    parsed = urllib.parse.urlparse(url)
    query = urllib.parse.parse_qs(parsed.query)
    read_only = any(value.lower() == "true" for value in query.get("read_only", []))
    feature_groups = sorted({
        group
        for value in query.get("features", [])
        for group in (item.strip().lower() for item in value.split(","))
        if group
    })
    features_scoped = bool(feature_groups) and "database" in feature_groups and set(feature_groups).issubset(
        {"database", "debugging", "docs"}
    )
    project_ref_values = [value.strip() for value in query.get("project_ref", []) if value.strip()]
    literal_project_scoped = any(
        value for value in project_ref_values if not supabase_project_ref_value_is_placeholder(value)
    )
    project_ref_template_mode = any(
        supabase_project_ref_value_is_placeholder(value) for value in project_ref_values
    )
    project_ref_env_present = project_ref_template_mode and bool(os.environ.get("SUPABASE_PROJECT_REF", "").strip())
    project_scoped = literal_project_scoped or project_ref_env_present
    project_scope_source = (
        "literal_url"
        if literal_project_scoped
        else "env_template"
        if project_ref_env_present
        else "template_missing_env"
        if project_ref_template_mode
        else "missing"
    )
    project_scope_next_action = (
        "run_readonly_schema_snapshot"
        if project_scoped
        else "set_SUPABASE_PROJECT_REF"
        if project_ref_template_mode
        else "set_project_ref_in_mcp_config"
    )
    scoped_mcp_url_template = "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=<project_ref>"
    secret_hits = len(SECRET_PATTERN.findall(raw))
    return {
        "present": path.is_file(),
        "serverPresent": bool(server),
        "type": server_type,
        "host": parsed.netloc,
        "path": parsed.path,
        "readOnlyMode": read_only,
        "featuresScopedMode": features_scoped,
        "featureGroups": feature_groups,
        "projectScopedMode": project_scoped,
        "projectRefTemplateMode": project_ref_template_mode,
        "projectRefEnvPresent": project_ref_env_present,
        "projectScopeSource": project_scope_source,
        "projectScopeNextAction": project_scope_next_action,
        "scopedMcpUrlTemplate": scoped_mcp_url_template,
        "rawSecretPatternHits": secret_hits,
    }


def supabase_schema_snapshot_artifact_summary(root: Path) -> dict[str, Any]:
    path = root / "data" / "db-gap-report" / "supabase-schema-snapshot.json"
    raw = read_text(path)
    data = load_json(path)
    plan = data.get("dbSnapshotPlan") if isinstance(data.get("dbSnapshotPlan"), dict) else {}
    auth_plan = data.get("authPlan") if isinstance(data.get("authPlan"), dict) else {}
    docs_refs = plan.get("docsRefs") if isinstance(plan.get("docsRefs"), list) else []
    breaking_changes = plan.get("breakingChanges") if isinstance(plan.get("breakingChanges"), list) else []
    security_contracts = plan.get("securityContracts") if isinstance(plan.get("securityContracts"), list) else []
    has_api_keys_docs = any(
        isinstance(entry, dict)
        and str(entry.get("id", "")).strip() == "api-keys"
        and "supabase.com/docs/guides/getting-started/api-keys" in str(entry.get("url", ""))
        for entry in docs_refs
    )
    has_secret_keys_backend_only_contract = any(
        str(contract).strip() == "secret_keys_backend_only"
        for contract in security_contracts
    )
    auth_env_status = auth_plan.get("manualAuthEnvStatus") if isinstance(auth_plan.get("manualAuthEnvStatus"), list) else []
    manual_auth_sensitive_env_refs = [
        str(item.get("name")).strip()
        for item in auth_env_status
        if isinstance(item, dict) and item.get("sensitive") is True and str(item.get("name", "")).strip()
    ]
    sql_bundle_path = supabase_artifact_local_path(
        root,
        data,
        "sqlBundlePath",
        "supabase-readonly-snapshot.sql",
    )
    sql_bundle_text = read_text(sql_bundle_path)
    result_template_path = supabase_artifact_local_path(
        root,
        data,
        "resultTemplatePath",
        "supabase-query-results.template.json",
    )
    result_template_text = read_text(result_template_path)
    result_template_data = load_json(result_template_path)
    collection_packet_path = supabase_artifact_local_path(
        root,
        data,
        "collectionPacketPath",
        "supabase-execute-sql-collection.packet.json",
    )
    collection_packet_text = read_text(collection_packet_path)
    collection_packet_data = load_json(collection_packet_path)
    snapshots = data.get("snapshots") if isinstance(data.get("snapshots"), list) else []
    evidence_needed = data.get("evidence_needed") if isinstance(data.get("evidence_needed"), list) else []
    secret_hits = len(
        SECRET_PATTERN.findall(raw + "\n" + sql_bundle_text + "\n" + result_template_text + "\n" + collection_packet_text)
    )
    try:
        path_length = path.stat().st_size if path.is_file() else 0
    except OSError:
        path_length = 0
    try:
        sql_bundle_length = sql_bundle_path.stat().st_size if sql_bundle_path.is_file() else 0
    except OSError:
        sql_bundle_length = 0
    try:
        result_template_length = result_template_path.stat().st_size if result_template_path.is_file() else 0
    except OSError:
        result_template_length = 0
    try:
        collection_packet_length = collection_packet_path.stat().st_size if collection_packet_path.is_file() else 0
    except OSError:
        collection_packet_length = 0
    result_template_queries = (
        result_template_data.get("queries")
        if isinstance(result_template_data.get("queries"), list)
        else []
    )
    collection_packet_queries = (
        collection_packet_data.get("queries")
        if isinstance(collection_packet_data.get("queries"), list)
        else []
    )
    result_template_collection_plan = (
        result_template_data.get("collectionPlan")
        if isinstance(result_template_data.get("collectionPlan"), dict)
        else {}
    )
    advisors = data.get("advisors") if isinstance(data.get("advisors"), dict) else {}
    advisors_collection_plan = (
        advisors.get("collectionPlan")
        if isinstance(advisors.get("collectionPlan"), dict)
        else {}
    )
    result_template_advisor_collection_plan = (
        result_template_data.get("advisorCollectionPlan")
        if isinstance(result_template_data.get("advisorCollectionPlan"), dict)
        else {}
    )
    collection_packet_advisor_collection = (
        collection_packet_data.get("advisorCollection")
        if isinstance(collection_packet_data.get("advisorCollection"), dict)
        else {}
    )
    result_template_supported_shapes = (
        result_template_collection_plan.get("supportedResultShapes")
        if isinstance(result_template_collection_plan.get("supportedResultShapes"), list)
        else []
    )
    plan_query_name_entries = supabase_plan_query_name_entries(plan)
    plan_query_names = supabase_unique_query_names(plan_query_name_entries)
    result_template_query_names = [
        str(query.get("name")).strip()
        for query in result_template_queries
        if isinstance(query, dict) and str(query.get("name", "")).strip()
    ]
    collection_packet_query_names = [
        str(query.get("name")).strip()
        for query in collection_packet_queries
        if isinstance(query, dict) and str(query.get("name", "")).strip()
    ]
    collection_packet_query_hashes_ok = all(
        isinstance(query, dict)
        and bool(str(query.get("sqlHash", "")).strip())
        and isinstance(query.get("executeSqlInput"), dict)
        and isinstance(query["executeSqlInput"].get("query"), str)
        and bool(query["executeSqlInput"].get("query", "").strip())
        for query in collection_packet_queries
    )
    collection_packet_mutation_query_names = supabase_collection_packet_mutation_query_names(collection_packet_queries)
    sql_bundle_query_name_entries = supabase_sql_bundle_query_name_entries(sql_bundle_text, plan_query_names)
    sql_bundle_query_names = supabase_sql_bundle_query_names(sql_bundle_text, plan_query_names)
    result_template_missing_query_names = supabase_missing_query_names(
        plan_query_names,
        result_template_query_names,
    )
    collection_packet_missing_query_names = supabase_missing_query_names(
        plan_query_names,
        collection_packet_query_names,
    )
    sql_bundle_missing_query_names = supabase_missing_query_names(
        plan_query_names,
        sql_bundle_query_names,
    )
    plan_duplicate_query_names = supabase_duplicate_query_names(plan_query_name_entries)
    sql_bundle_duplicate_query_names = supabase_duplicate_query_names(sql_bundle_query_name_entries)
    result_template_duplicate_query_names = supabase_duplicate_query_names(result_template_query_names)
    collection_packet_duplicate_query_names = supabase_duplicate_query_names(collection_packet_query_names)
    (
        generated_at_present,
        fresh,
        age_seconds,
        freshness_status,
    ) = artifact_freshness(
        data.get("generatedAt"),
        SUPABASE_SCHEMA_SNAPSHOT_MAX_AGE_SECONDS,
    )
    return {
        "present": path.is_file(),
        "schemaVersion": data.get("schemaVersion") == "awx.mcp.supabase_schema_snapshot.v1",
        "generatedAt": generated_at_present,
        "fresh": fresh,
        "ageSeconds": age_seconds,
        "freshnessStatus": freshness_status,
        "readOnly": data.get("readOnly") is True,
        "mutationAllowedValue": data.get("mutationAllowed"),
        "mutationAllowed": data.get("mutationAllowed") is False,
        "hasSnapshotPlan": bool(plan),
        "hasAuthPlan": bool(auth_plan),
        "mcpOAuthRequired": auth_plan.get("mcpOAuthRequired") is True,
        "defaultHostedAuthMode": str(auth_plan.get("defaultHostedAuthMode") or ""),
        "manualAuthMode": str(auth_plan.get("manualAuthMode") or ""),
        "supportedAuthModes": ",".join(
            str(mode).strip()
            for mode in (auth_plan.get("supportedAuthModes") if isinstance(auth_plan.get("supportedAuthModes"), list) else [])
            if str(mode).strip()
        ),
        "manualAuthEnvStatus": bool(auth_env_status),
        "manualAuthSensitiveEnvRefs": ",".join(manual_auth_sensitive_env_refs[:24]),
        "hasAuthSecurityWarnings": bool(auth_plan.get("securityWarnings")),
        "hasDocsRefs": len(docs_refs) >= 3,
        "hasApiKeysDocs": has_api_keys_docs,
        "hasBreakingChanges": len(breaking_changes) >= 1,
        "hasSecurityContracts": len(security_contracts) >= 3,
        "hasSecretKeysBackendOnlyContract": has_secret_keys_backend_only_contract,
        "sqlBundlePresent": sql_bundle_path.is_file(),
        "sqlBundleReadOnly": "START TRANSACTION READ ONLY" in sql_bundle_text and "COMMIT" in sql_bundle_text,
        "sqlBundleDocs": "-- Docs:" in sql_bundle_text,
        "sqlBundleBreakingChange": "-- Breaking change: data-api-explicit-grants" in sql_bundle_text,
        "sqlBundleSecurityContracts": "-- Security contract:" in sql_bundle_text,
        "resultTemplatePresent": result_template_path.is_file(),
        "resultTemplateSchema": result_template_data.get("schemaVersion") == "awx.mcp.supabase_query_results_template.v1",
        "resultTemplateReadOnly": result_template_data.get("readOnly") is True,
        "resultTemplateImportTool": result_template_data.get("importTool") == "supabase_schema_snapshot_import",
        "resultTemplateCollectionPlan": bool(result_template_collection_plan),
        "resultTemplateCollectionPlanReadOnly": (
            result_template_collection_plan.get("readOnly") is True
            and result_template_collection_plan.get("mutationAllowed") is False
        ),
        "resultTemplateCollectionPlanMcpTool": result_template_collection_plan.get("mcpTool") == "execute_sql",
        "resultTemplateCollectionPlanEnvNames": (
            result_template_collection_plan.get("projectRefEnv") == "SUPABASE_PROJECT_REF"
            and result_template_collection_plan.get("authEnv") == "SUPABASE_ACCESS_TOKEN"
        ),
        "resultTemplateCollectionPlanImportTool": (
            result_template_collection_plan.get("importTool") == "supabase_schema_snapshot_import"
        ),
        "advisorsCollectionPlan": bool(advisors_collection_plan),
        "advisorsCollectionPlanReadOnly": (
            advisors_collection_plan.get("readOnly") is True
            and advisors_collection_plan.get("mutationAllowed") is False
        ),
        "advisorsCollectionPlanMcpTool": advisors_collection_plan.get("mcpTool") == "get_advisors",
        "advisorsCollectionPlanEnvNames": (
            advisors_collection_plan.get("projectRefEnv") == "SUPABASE_PROJECT_REF"
            and advisors_collection_plan.get("authEnv") == "SUPABASE_ACCESS_TOKEN"
        ),
        "resultTemplateAdvisorCollectionPlan": bool(result_template_advisor_collection_plan),
        "resultTemplateAdvisorCollectionMcpTool": (
            result_template_advisor_collection_plan.get("mcpTool") == "get_advisors"
            and result_template_advisor_collection_plan.get("featureGroup") == "debugging"
        ),
        "resultTemplateAdvisorCollectionEnvNames": (
            result_template_advisor_collection_plan.get("projectRefEnv") == "SUPABASE_PROJECT_REF"
            and result_template_advisor_collection_plan.get("authEnv") == "SUPABASE_ACCESS_TOKEN"
        ),
        "resultTemplateSupportedResultShapes": len(result_template_supported_shapes) >= 4,
        "collectionPacketPresent": collection_packet_path.is_file(),
        "collectionPacketSchema": (
            collection_packet_data.get("schemaVersion") == "awx.mcp.supabase_execute_sql_collection_packet.v1"
        ),
        "collectionPacketReadOnly": (
            collection_packet_data.get("readOnly") is True
            and collection_packet_data.get("mutationAllowed") is False
        ),
        "collectionPacketMcpTool": collection_packet_data.get("mcpTool") == "execute_sql",
        "collectionPacketEnvNames": (
            collection_packet_data.get("projectRefEnv") == "SUPABASE_PROJECT_REF"
            and collection_packet_data.get("authEnv") == "SUPABASE_ACCESS_TOKEN"
        ),
        "collectionPacketImportTool": collection_packet_data.get("importTool") == "supabase_schema_snapshot_import",
        "collectionPacketAdvisorCollection": bool(collection_packet_advisor_collection),
        "collectionPacketAdvisorCollectionMcpTool": (
            collection_packet_advisor_collection.get("mcpTool") == "get_advisors"
            and collection_packet_advisor_collection.get("featureGroup") == "debugging"
        ),
        "collectionPacketAdvisorCollectionEnvNames": (
            collection_packet_advisor_collection.get("projectRefEnv") == "SUPABASE_PROJECT_REF"
            and collection_packet_advisor_collection.get("authEnv") == "SUPABASE_ACCESS_TOKEN"
        ),
        "collectionPacketExecutionMode": (
            collection_packet_data.get("executionMode") == "one_query_per_execute_sql_call"
            and collection_packet_data.get("executeSqlInputField") == "query"
        ),
        "collectionPacketQueryPayloads": collection_packet_query_hashes_ok,
        "planSqlQueryCount": len(plan_query_names),
        "sqlBundleQueryCount": len(sql_bundle_query_names),
        "resultTemplateQueryCount": len(result_template_queries),
        "collectionPacketQueryCount": len(collection_packet_queries),
        "planDuplicateQueryNames": ",".join(plan_duplicate_query_names[:24]),
        "sqlBundleDuplicateQueryNames": ",".join(sql_bundle_duplicate_query_names[:24]),
        "resultTemplateDuplicateQueryNames": ",".join(result_template_duplicate_query_names[:24]),
        "collectionPacketDuplicateQueryNames": ",".join(collection_packet_duplicate_query_names[:24]),
        "resultTemplateMissingQueryNames": ",".join(result_template_missing_query_names[:24]),
        "sqlBundleMissingQueryNames": ",".join(sql_bundle_missing_query_names[:24]),
        "collectionPacketMissingQueryNames": ",".join(collection_packet_missing_query_names[:24]),
        "collectionPacketMutationQueryCount": len(collection_packet_mutation_query_names),
        "collectionPacketMutationQueryNames": ",".join(collection_packet_mutation_query_names[:24]),
        "snapshotCount": len(snapshots),
        "evidenceNeededCount": len(evidence_needed),
        "pathHash": sha256_file(path),
        "pathLength": path_length,
        "sqlBundlePathHash": sha256_file(sql_bundle_path),
        "sqlBundleLength": sql_bundle_length,
        "resultTemplatePathHash": sha256_file(result_template_path),
        "resultTemplateLength": result_template_length,
        "collectionPacketPathHash": sha256_file(collection_packet_path),
        "collectionPacketLength": collection_packet_length,
        "rawSecretPatternHits": secret_hits,
    }


def supabase_plan_query_name_entries(plan: dict[str, Any]) -> list[str]:
    queries = plan.get("sqlQueries") if isinstance(plan.get("sqlQueries"), list) else []
    names: list[str] = []
    for query in queries:
        if not isinstance(query, dict):
            continue
        name = str(query.get("name", "")).strip()
        if not name:
            continue
        names.append(name)
    return names


def supabase_unique_query_names(names: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for name in names:
        if not name or name in seen:
            continue
        seen.add(name)
        result.append(name)
    return result


def supabase_plan_query_names(plan: dict[str, Any]) -> list[str]:
    return supabase_unique_query_names(supabase_plan_query_name_entries(plan))


def supabase_sql_bundle_query_name_entries(sql_bundle_text: str, expected_names: list[str]) -> list[str]:
    expected = set(expected_names)
    names: list[str] = []
    for line in sql_bundle_text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("-- "):
            continue
        name = stripped[3:].strip()
        if name in expected:
            names.append(name)
    return names


def supabase_sql_bundle_query_names(sql_bundle_text: str, expected_names: list[str]) -> list[str]:
    comment_names = set(supabase_sql_bundle_query_name_entries(sql_bundle_text, expected_names))
    return [name for name in expected_names if name in comment_names]


def supabase_duplicate_query_names(names: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    duplicates: set[str] = set()
    for name in names:
        if not name:
            continue
        if name in seen and name not in duplicates:
            duplicates.add(name)
            result.append(name)
        seen.add(name)
    return result


def supabase_missing_query_names(expected_names: list[str], present_names: list[str]) -> list[str]:
    present = set(present_names)
    return [name for name in expected_names if name not in present]


def supabase_collection_packet_mutation_query_names(queries: list[Any]) -> list[str]:
    names: list[str] = []
    for idx, query in enumerate(queries, start=1):
        if not isinstance(query, dict):
            continue
        execute_input = query.get("executeSqlInput")
        sql = ""
        if isinstance(execute_input, dict):
            sql = str(execute_input.get("query") or "")
        if not sql:
            sql = str(query.get("sql") or "")
        if not sql:
            continue
        if supabase_sql_has_mutating_statement(sql):
            name = str(query.get("name") or f"query_{idx}").strip()
            names.append(name or f"query_{idx}")
    return names


def supabase_sql_has_mutating_statement(sql: str) -> bool:
    allowed_first_verbs = {"select", "with", "show", "explain"}
    return any(verb not in allowed_first_verbs for verb in supabase_sql_statement_verbs(sql))


def supabase_sql_statement_verbs(sql: str) -> list[str]:
    text = strip_sql_string_literals_and_comments(sql)
    verbs: list[str] = []
    for statement in text.split(";"):
        stripped = statement.strip()
        if not stripped:
            continue
        match = re.search(r"[A-Za-z_][A-Za-z0-9_]*", stripped)
        if match:
            verbs.append(match.group(0).lower())
    return verbs


def strip_sql_string_literals_and_comments(sql: str) -> str:
    result: list[str] = []
    i = 0
    in_single_quote = False
    while i < len(sql):
        ch = sql[i]
        next_ch = sql[i + 1] if i + 1 < len(sql) else ""
        if in_single_quote:
            if ch == "'" and next_ch == "'":
                i += 2
                continue
            if ch == "'":
                in_single_quote = False
            i += 1
            continue
        if ch == "'":
            in_single_quote = True
            result.append(" ")
            i += 1
            continue
        if ch == "-" and next_ch == "-":
            while i < len(sql) and sql[i] not in "\r\n":
                i += 1
            result.append(" ")
            continue
        result.append(ch)
        i += 1
    return "".join(result)


def supabase_schema_snapshot_artifact_ready_fail_reason(summary: dict[str, Any]) -> str:
    missing: list[str] = []
    checks = (
        ("present", True),
        ("schemaVersion", True),
        ("generatedAt", True),
        ("fresh", True),
        ("readOnly", True),
        ("mutationAllowed", True),
        ("hasSnapshotPlan", True),
        ("hasAuthPlan", True),
        ("mcpOAuthRequired", True),
        ("hasDocsRefs", True),
        ("hasApiKeysDocs", True),
        ("hasBreakingChanges", True),
        ("hasSecurityContracts", True),
        ("hasSecretKeysBackendOnlyContract", True),
        ("sqlBundlePresent", True),
        ("sqlBundleReadOnly", True),
        ("sqlBundleDocs", True),
        ("sqlBundleBreakingChange", True),
        ("sqlBundleSecurityContracts", True),
        ("resultTemplatePresent", True),
        ("resultTemplateSchema", True),
        ("resultTemplateReadOnly", True),
        ("resultTemplateImportTool", True),
        ("resultTemplateCollectionPlan", True),
        ("resultTemplateCollectionPlanReadOnly", True),
        ("resultTemplateCollectionPlanMcpTool", True),
        ("resultTemplateCollectionPlanEnvNames", True),
        ("resultTemplateCollectionPlanImportTool", True),
        ("advisorsCollectionPlan", True),
        ("advisorsCollectionPlanReadOnly", True),
        ("advisorsCollectionPlanMcpTool", True),
        ("advisorsCollectionPlanEnvNames", True),
        ("resultTemplateAdvisorCollectionPlan", True),
        ("resultTemplateAdvisorCollectionMcpTool", True),
        ("resultTemplateAdvisorCollectionEnvNames", True),
        ("resultTemplateSupportedResultShapes", True),
        ("collectionPacketPresent", True),
        ("collectionPacketSchema", True),
        ("collectionPacketReadOnly", True),
        ("collectionPacketMcpTool", True),
        ("collectionPacketEnvNames", True),
        ("collectionPacketImportTool", True),
        ("collectionPacketAdvisorCollection", True),
        ("collectionPacketAdvisorCollectionMcpTool", True),
        ("collectionPacketAdvisorCollectionEnvNames", True),
        ("collectionPacketExecutionMode", True),
        ("collectionPacketQueryPayloads", True),
    )
    for key, expected in checks:
        if summary.get(key) is not expected:
            missing.append(key)
    if summary.get("freshnessStatus") != "current":
        missing.append("freshnessStatus")
    if summary.get("defaultHostedAuthMode") != "dynamic_client_registration_oauth":
        missing.append("defaultHostedAuthMode")
    supported_auth_modes = set(str(summary.get("supportedAuthModes") or "").split(","))
    if not {"dynamic_oauth", "manual_ci_pat_auth"}.issubset(supported_auth_modes):
        missing.append("supportedAuthModes")
    if summary.get("manualAuthMode") != "manual_pat_header_for_ci":
        missing.append("manualAuthMode")
    try:
        plan_query_count = int(summary.get("planSqlQueryCount", 0) or 0)
    except Exception:
        plan_query_count = 0
    try:
        sql_bundle_query_count = int(summary.get("sqlBundleQueryCount", 0) or 0)
    except Exception:
        sql_bundle_query_count = 0
    try:
        result_template_query_count = int(summary.get("resultTemplateQueryCount", 0) or 0)
    except Exception:
        result_template_query_count = 0
    try:
        collection_packet_query_count = int(summary.get("collectionPacketQueryCount", 0) or 0)
    except Exception:
        collection_packet_query_count = 0
    if plan_query_count < 10:
        missing.append("planSqlQueryCount")
    if sql_bundle_query_count != plan_query_count:
        missing.append("sqlBundleQueryCount")
    if result_template_query_count != plan_query_count:
        missing.append("resultTemplateQueryCount")
    if collection_packet_query_count != plan_query_count:
        missing.append("collectionPacketQueryCount")
    plan_duplicate = str(summary.get("planDuplicateQueryNames") or "")
    if plan_duplicate:
        missing.append(f"planDuplicateQueryNames({plan_duplicate})")
    sql_bundle_duplicate = str(summary.get("sqlBundleDuplicateQueryNames") or "")
    if sql_bundle_duplicate:
        missing.append(f"sqlBundleDuplicateQueryNames({sql_bundle_duplicate})")
    result_template_duplicate = str(summary.get("resultTemplateDuplicateQueryNames") or "")
    if result_template_duplicate:
        missing.append(f"resultTemplateDuplicateQueryNames({result_template_duplicate})")
    collection_packet_duplicate = str(summary.get("collectionPacketDuplicateQueryNames") or "")
    if collection_packet_duplicate:
        missing.append(f"collectionPacketDuplicateQueryNames({collection_packet_duplicate})")
    result_template_missing = str(summary.get("resultTemplateMissingQueryNames") or "")
    if result_template_missing:
        missing.append(f"resultTemplateMissingQueryNames({result_template_missing})")
    sql_bundle_missing = str(summary.get("sqlBundleMissingQueryNames") or "")
    if sql_bundle_missing:
        missing.append(f"sqlBundleMissingQueryNames({sql_bundle_missing})")
    collection_packet_missing = str(summary.get("collectionPacketMissingQueryNames") or "")
    if collection_packet_missing:
        missing.append(f"collectionPacketMissingQueryNames({collection_packet_missing})")
    collection_packet_mutation = str(summary.get("collectionPacketMutationQueryNames") or "")
    try:
        collection_packet_mutation_count = int(summary.get("collectionPacketMutationQueryCount", 0) or 0)
    except Exception:
        collection_packet_mutation_count = 0
    if collection_packet_mutation_count:
        missing.append(
            f"collectionPacketMutationQueryNames({collection_packet_mutation})"
            if collection_packet_mutation
            else "collectionPacketMutationQueryCount"
        )
    if int(summary.get("rawSecretPatternHits", 0) or 0) != 0:
        missing.append("rawSecretPatternHits")
    return ", ".join(missing)


def supabase_schema_snapshot_artifact_ready(summary: dict[str, Any]) -> bool:
    return not supabase_schema_snapshot_artifact_ready_fail_reason(summary)


def _relative_artifact_path(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return str(path)


def computer_use_gui_boundary_summary(root: Path) -> dict[str, Any]:
    prompt_paths = [
        root / "agent-prompts" / "agents" / "demo1_quant_harmony_9h_safe_patch" / "system_ko.md",
        root / "agent-prompts" / "out" / "demo1_quant_harmony_9h_safe_patch.prompt",
    ]
    prompt_text = "\n".join(read_text(path) for path in prompt_paths)
    prompt_pack_present = all(path_is_file(path) for path in prompt_paths)
    gui_only = (
        "Computer Use" in prompt_text
        and "GUI/browser proof" in prompt_text
        and "explicit Windows GUI proof" in prompt_text
    )
    no_terminal_automation = (
        "do not automate PowerShell or command prompts" in prompt_text
        and "terminal/source edits" in prompt_text
    )
    supporting_only = (
        "SUPPORTING_ONLY" in prompt_text
        and "supporting/not-required" in prompt_text
    )
    candidate_paths = [
        root / "var" / "codex-smoke" / "computer-use" / "helper-smoke.json",
        root / "var" / "codex-smoke" / "computer-use-smoke.json",
    ]
    candidate_summaries: list[dict[str, Any]] = []
    for path in candidate_paths:
        text = read_text(path)
        data = load_json(path)
        present = path_is_file(path)
        generated_at, fresh, age_seconds, freshness_status = artifact_freshness(
            data.get("generatedAt"),
            COMPUTER_USE_HELPER_MAX_AGE_SECONDS,
        )
        lower_keys = {str(key).lower() for key in data.keys()}
        standard_count_only_smoke = (
            str(data.get("schemaVersion") or "").startswith("awx.computer_use")
            or path.name == "computer-use-smoke.json"
        )
        stores_app_names = data.get("storesAppNames")
        if stores_app_names is None and "storesRawAppNames" in data:
            stores_app_names = data.get("storesRawAppNames")
        stores_window_titles = data.get("storesWindowTitles")
        if stores_app_names is None and standard_count_only_smoke:
            stores_app_names = bool(
                lower_keys
                & {
                    "appnames",
                    "applications",
                    "processnames",
                    "rawappnames",
                    "runningappnames",
                    "runningapps",
                }
            )
        if stores_window_titles is None and standard_count_only_smoke:
            stores_window_titles = bool(
                lower_keys
                & {
                    "rawtitles",
                    "rawwindowtitles",
                    "targetablewindows",
                    "titles",
                    "windows",
                    "windowtitles",
                }
            )
        artifact_gui_only = data.get("guiOnly") is True or gui_only
        artifact_no_terminal = data.get("noTerminalAutomation") is True or no_terminal_automation
        artifact_supporting_only = data.get("supportingOnly") is True or supporting_only
        try:
            app_count = int(data.get("appCount", 0) or 0)
        except Exception:
            app_count = 0
        try:
            window_count = int(data.get("targetableWindowCount", data.get("windowCount", 0)) or 0)
        except Exception:
            window_count = 0
        try:
            declared_secret_hits = int(data.get("secretHits", 0) or 0) + int(data.get("rawSecretPatternHits", 0) or 0)
        except Exception:
            declared_secret_hits = 0
        secret_hits = len(SECRET_PATTERN.findall(text)) + declared_secret_hits
        summary = {
            "artifactPath": _relative_artifact_path(root, path),
            "helperPresent": present,
            "helperReachable": data.get("ok") is True and data.get("reachable") is True,
            "helperGeneratedAt": generated_at,
            "helperFresh": fresh,
            "helperFreshnessStatus": freshness_status,
            "helperAgeSeconds": age_seconds,
            "helperCountOnly": stores_app_names is False and stores_window_titles is False,
            "helperBoundary": artifact_gui_only and artifact_no_terminal and artifact_supporting_only,
            "storesAppNames": stores_app_names,
            "storesWindowTitles": stores_window_titles,
            "appCount": app_count,
            "targetableWindowCount": window_count,
            "helperSecretPatternHits": secret_hits,
            "promptPack": prompt_pack_present,
            "guiOnly": gui_only,
            "noTerminalAutomation": no_terminal_automation,
            "supportingOnly": supporting_only,
            "rawSecretPatternHits": len(SECRET_PATTERN.findall(prompt_text)),
        }
        summary["ready"] = (
            summary["promptPack"]
            and summary["guiOnly"]
            and summary["noTerminalAutomation"]
            and summary["supportingOnly"]
            and summary["rawSecretPatternHits"] == 0
            and summary["helperPresent"]
            and summary["helperReachable"]
            and summary["helperCountOnly"]
            and summary["helperBoundary"]
            and summary["helperGeneratedAt"]
            and summary["helperFresh"]
            and summary["helperSecretPatternHits"] == 0
        )
        candidate_summaries.append(summary)
    for summary in candidate_summaries:
        if summary.get("ready") is True:
            return summary
    if candidate_summaries:
        return max(
            candidate_summaries,
            key=lambda summary: (
                bool(summary.get("helperPresent")),
                bool(summary.get("helperFresh")),
                bool(summary.get("helperGeneratedAt")),
                bool(summary.get("helperReachable")),
                bool(summary.get("helperBoundary")),
                bool(summary.get("helperCountOnly")),
                int(summary.get("appCount", 0) or 0) + int(summary.get("targetableWindowCount", 0) or 0),
            ),
        )
    return {
        "ready": False,
        "artifactPath": "var/codex-smoke/computer-use/helper-smoke.json",
        "helperPresent": False,
        "helperReachable": False,
        "helperGeneratedAt": False,
        "helperFresh": False,
        "helperFreshnessStatus": "missing",
        "helperAgeSeconds": -1,
        "helperCountOnly": False,
        "helperBoundary": False,
        "storesAppNames": None,
        "storesWindowTitles": None,
        "appCount": 0,
        "targetableWindowCount": 0,
        "helperSecretPatternHits": 0,
        "promptPack": False,
        "guiOnly": False,
        "noTerminalAutomation": False,
        "supportingOnly": False,
        "rawSecretPatternHits": 0,
    }


def supabase_readonly_snapshot_smoke_summary(root: Path) -> dict[str, Any]:
    script_path = root / "scripts" / "smoke_supabase_readonly_snapshot.ps1"
    artifact_dir = root / "var" / "codex-smoke" / "supabase-readonly-snapshot"
    context_path = artifact_dir / "supabase-context-probe.json"
    snapshot_result_path = artifact_dir / "supabase-schema-snapshot.result.json"
    import_result_path = artifact_dir / "supabase-schema-snapshot-import.result.json"
    snapshot_artifact_path = artifact_dir / "supabase-schema-snapshot.json"
    summary_artifact_path = artifact_dir / "supabase-readonly-snapshot.summary.json"
    script_text = read_text(script_path)
    context = load_json(context_path)
    snapshot_result = load_json(snapshot_result_path)
    import_result = load_json(import_result_path)
    snapshot_artifact = load_json(snapshot_artifact_path)
    summary_artifact = load_json(summary_artifact_path)
    combined = "\n".join(
        read_text(path)
        for path in (
            context_path,
            snapshot_result_path,
            import_result_path,
            snapshot_artifact_path,
            summary_artifact_path,
        )
    )
    raw_secret_hits = len(SECRET_PATTERN.findall(script_text + "\n" + combined))
    bearer_hits = len(re.findall(r"Bearer\s+[A-Za-z0-9._-]+", combined))
    raw_jdbc_url_hits = len(re.findall(r"\bjdbc:[A-Za-z0-9_+.-]*://", combined, flags=re.IGNORECASE))
    windows_abs_path_hits = len(re.findall(r"[A-Za-z]:\\[^\r\n\"']+", combined))
    mutation_allowed_true_hits = len(re.findall(r'"mutationAllowed"\s*:\s*true', combined))
    (
        summary_generated_at,
        summary_fresh,
        summary_age_seconds,
        summary_freshness_status,
    ) = artifact_freshness(
        summary_artifact.get("generatedAt"),
        SUPABASE_SMOKE_SUMMARY_MAX_AGE_SECONDS,
    )
    mcp_context = context.get("mcp") or {}
    artifact_files = [
        artifact_dir / "supabase-context-probe.json",
        artifact_dir / "supabase-schema-snapshot.json",
        artifact_dir / "supabase-schema-snapshot.result.json",
        artifact_dir / "supabase-schema-snapshot-import.result.json",
        artifact_dir / "supabase-readonly-snapshot.sql",
        artifact_dir / "supabase-query-results.template.json",
        artifact_dir / "supabase-execute-sql-collection.packet.json",
        summary_artifact_path,
    ]
    return {
        "scriptPresent": script_path.is_file(),
        "scriptHasContextProbe": "supabase_context_probe" in script_text,
        "scriptHasSnapshotTool": "supabase_schema_snapshot" in script_text,
        "scriptHasImportTool": "supabase_schema_snapshot_import" in script_text,
        "scriptHasRequireProjectScope": "RequireProjectScope" in script_text,
        "scriptHasSkipNetworkProbe": "SkipNetworkProbe" in script_text,
        "scriptReportsMissingResultNames": "missingResultNames=" in script_text,
        "scriptReportsDataApiEvidenceMissing": "dataApiEvidenceMissing=" in script_text,
        "scriptReportsEnvPreflight": "envPresentCount=" in script_text
        and "projectRefEnvPresent=" in script_text
        and "accessTokenEnvPresent=" in script_text
        and "contextEvidenceNeededCount=" in script_text,
        "artifactDirPresent": artifact_dir.is_dir(),
        "artifactFileCount": sum(1 for path in artifact_files if path.is_file()),
        "summaryArtifactPresent": summary_artifact_path.is_file(),
        "summaryGeneratedAt": summary_generated_at,
        "summaryFresh": summary_fresh,
        "summaryAgeSeconds": summary_age_seconds,
        "summaryFreshnessStatus": summary_freshness_status,
        "summaryOk": summary_artifact.get("ok") is True,
        "summaryDecision": str(summary_artifact.get("decision") or ""),
        "summarySecretHits": (
            int(summary_artifact.get("secretHits") or 0)
            if "secretHits" in summary_artifact
            else -1
        ),
        "summaryRawSecretPatternHits": (
            int(summary_artifact.get("rawSecretPatternHits") or 0)
            if "rawSecretPatternHits" in summary_artifact
            else -1
        ),
        "summaryHighConfidenceSecretHits": int(summary_artifact.get("highConfidenceSecretHits", 0) or 0),
        "summaryRawJdbcUrlHits": int(summary_artifact.get("rawJdbcUrlHits", 0) or 0),
        "docsRefCount": int(summary_artifact.get("docsRefCount", 0) or 0),
        "securityContractCount": int(summary_artifact.get("securityContractCount", 0) or 0),
        "cliQueryMinVersion": str(summary_artifact.get("cliQueryMinVersion") or ""),
        "cliAdvisorsMinVersion": str(summary_artifact.get("cliAdvisorsMinVersion") or ""),
        "cliQueryFallbackTool": str(summary_artifact.get("cliQueryFallbackTool") or ""),
        "cliAdvisorsFallbackTool": str(summary_artifact.get("cliAdvisorsFallbackTool") or ""),
        "dataApiGrantProofRequired": summary_artifact.get("dataApiGrantProofRequired") is True,
        "rlsPolicyProofRequired": summary_artifact.get("rlsPolicyProofRequired") is True,
        "apiKeysDocs": summary_artifact.get("apiKeysDocs") is True,
        "secretKeysBackendOnly": summary_artifact.get("secretKeysBackendOnly") is True,
        "envPresentCount": int(summary_artifact.get("envPresentCount", 0) or 0),
        "projectRefEnvPresent": summary_artifact.get("projectRefEnvPresent") is True,
        "accessTokenEnvPresent": summary_artifact.get("accessTokenEnvPresent") is True,
        "cliPresent": summary_artifact.get("cliPresent") is True,
        "contextEvidenceNeededCount": int(summary_artifact.get("contextEvidenceNeededCount", 0) or 0),
        "contextDecision": str(context.get("decision") or ""),
        "mcpReachable": mcp_context.get("reachable") is True,
        "mcpHttpStatus": str(mcp_context.get("httpStatus") or ""),
        "mcpUnauthenticatedExpected": mcp_context.get("unauthenticatedExpected") is True,
        "mcpProbeSkipped": mcp_context.get("probeSkipped") is True,
        "mcpDecision": str(mcp_context.get("decision") or ""),
        "projectScopeStatus": str((context.get("projectScope") or {}).get("status") or ""),
        "snapshotDecision": str(snapshot_result.get("decision") or ""),
        "schemaSnapshotAvailable": snapshot_result.get("schemaSnapshotAvailable") is True,
        "snapshotMutationAllowedValue": snapshot_result.get("mutationAllowed"),
        "snapshotMutationBlocked": snapshot_result.get("mutationAllowed") is False,
        "importDecision": str(import_result.get("decision") or ""),
        "importSchemaSnapshotComplete": import_result.get("schemaSnapshotComplete") is True,
        "importResultSetComplete": import_result.get("resultSetComplete") is True,
        "importMutationAllowedValue": import_result.get("mutationAllowed"),
        "importMutationBlocked": import_result.get("mutationAllowed") is False,
        "importedResultCount": int(import_result.get("importedResultCount", 0) or 0),
        "snapshotArtifactReadOnly": snapshot_artifact.get("readOnly") is True,
        "snapshotArtifactMutationBlocked": snapshot_artifact.get("mutationAllowed") is False,
        "rawSecretPatternHits": raw_secret_hits,
        "bearerPatternHits": bearer_hits,
        "rawJdbcUrlHits": raw_jdbc_url_hits,
        "windowsAbsPathHits": windows_abs_path_hits,
        "mutationAllowedTrueHits": mutation_allowed_true_hits,
        "artifactDirHash": stable_hash(artifact_dir.as_posix()),
    }


def supabase_readonly_snapshot_smoke_ready_fail_reason(summary: dict[str, Any]) -> str:
    missing: list[str] = []
    checks = (
        ("scriptPresent", True),
        ("scriptHasContextProbe", True),
        ("scriptHasSnapshotTool", True),
        ("scriptHasImportTool", True),
        ("scriptHasRequireProjectScope", True),
        ("scriptHasSkipNetworkProbe", True),
        ("scriptReportsMissingResultNames", True),
        ("scriptReportsDataApiEvidenceMissing", True),
        ("scriptReportsEnvPreflight", True),
        ("artifactDirPresent", True),
        ("summaryArtifactPresent", True),
        ("summaryGeneratedAt", True),
        ("summaryFresh", True),
        ("snapshotMutationBlocked", True),
        ("importMutationBlocked", True),
        ("snapshotArtifactReadOnly", True),
        ("snapshotArtifactMutationBlocked", True),
    )
    for key, expected in checks:
        if summary.get(key) is not expected:
            missing.append(key)
    if int(summary.get("artifactFileCount", 0) or 0) < 8:
        missing.append("artifactFileCount")
    if summary.get("summaryFreshnessStatus") != "current":
        missing.append("summaryFreshnessStatus")
    if int(summary.get("docsRefCount", 0) or 0) < 2:
        missing.append("docsRefCount")
    if int(summary.get("securityContractCount", 0) or 0) < 3:
        missing.append("securityContractCount")
    expected_cli_contracts = (
        ("cliQueryMinVersion", ">=2.79.0"),
        ("cliAdvisorsMinVersion", ">=2.81.3"),
        ("cliQueryFallbackTool", "execute_sql"),
        ("cliAdvisorsFallbackTool", "get_advisors"),
    )
    for key, expected in expected_cli_contracts:
        if summary.get(key) != expected:
            missing.append(key)
    for key in (
        "dataApiGrantProofRequired",
        "rlsPolicyProofRequired",
        "apiKeysDocs",
        "secretKeysBackendOnly",
    ):
        if summary.get(key) is not True:
            missing.append(key)
    if summary.get("contextDecision") != "supabase_context_probe":
        missing.append("contextDecision")
    if summary.get("snapshotDecision") != "supabase_schema_snapshot_evidence_needed":
        missing.append("snapshotDecision")
    if summary.get("importDecision") != "supabase_schema_snapshot_import_evidence_needed":
        missing.append("importDecision")
    if summary.get("schemaSnapshotAvailable") is not False:
        missing.append("schemaSnapshotAvailable")
    for key in (
        "summarySecretHits",
        "summaryRawSecretPatternHits",
        "summaryHighConfidenceSecretHits",
        "summaryRawJdbcUrlHits",
        "rawSecretPatternHits",
        "bearerPatternHits",
        "rawJdbcUrlHits",
        "windowsAbsPathHits",
        "mutationAllowedTrueHits",
    ):
        if int(summary.get(key, 0) or 0) != 0:
            missing.append(key)
    return ", ".join(missing)


def supabase_readonly_snapshot_smoke_ready(summary: dict[str, Any]) -> bool:
    return not supabase_readonly_snapshot_smoke_ready_fail_reason(summary)


def websoak_provider_disabled_artifact_summary(root: Path) -> dict[str, Any]:
    path = root / "verification" / "websoak-kpi-smoke" / "websoak-kpi-provider-disabled.json"
    summary_path = path.with_suffix(".summary.txt")
    raw = read_text(path)
    summary_text = read_text(summary_path)
    data = load_json(path)
    summary = data.get("summary") if isinstance(data.get("summary"), dict) else {}
    try:
        path_length = path.stat().st_size if path.is_file() else 0
    except OSError:
        path_length = 0
    try:
        summary_length = summary_path.stat().st_size if summary_path.is_file() else 0
    except OSError:
        summary_length = 0
    try:
        status = int(summary.get("status", 0) or 0)
    except Exception:
        status = 0
    try:
        provider_disabled_count = int(summary.get("providerDisabledCount", 0) or 0)
    except Exception:
        provider_disabled_count = 0
    try:
        out_count = int(summary.get("outCount", 0) or 0)
    except Exception:
        out_count = 0
    try:
        raw_input_count = int(summary.get("rawInputCount", 0) or 0)
    except Exception:
        raw_input_count = 0
    try:
        secret_hits = int(summary.get("secretPatternHits", 0) or 0)
    except Exception:
        secret_hits = 0
    try:
        raw_query_hits = int(summary.get("rawQueryHits", 0) or 0)
    except Exception:
        raw_query_hits = 0
    try:
        probe_key_hits = int(summary.get("probeKeyHits", 0) or 0)
    except Exception:
        probe_key_hits = 0
    try:
        cache_only_merged_count = int(summary.get("cacheOnlyMergedCount", 0) or 0)
    except Exception:
        cache_only_merged_count = 0
    return {
        "present": path.is_file(),
        "summaryPresent": summary_path.is_file(),
        "ok": summary.get("ok") is True,
        "status": status,
        "providerDisabledOrSkipped": summary.get("providerDisabledOrSkipped") is True,
        "providerDisabledCount": provider_disabled_count,
        "providerStates": safe_csv_names(summary.get("providerStates")),
        "outCount": out_count,
        "rawInputCount": raw_input_count,
        "cacheOnlyMergedCount": cache_only_merged_count,
        "rescueMergeUsed": summary.get("rescueMergeUsed") is True,
        "starvationTrigger": safe_scalar(summary.get("starvationTrigger"), 120),
        "secretPatternHits": secret_hits,
        "rawQueryHits": raw_query_hits,
        "probeKeyHits": probe_key_hits,
        "rawSecretPatternHits": len(SECRET_PATTERN.findall(raw + "\n" + summary_text)),
        "pathHash": sha256_file(path),
        "summaryPathHash": sha256_file(summary_path),
        "pathLength": path_length,
        "summaryPathLength": summary_length,
    }


def websoak_provider_disabled_ready_fail_reason(summary: dict[str, Any]) -> str:
    missing: list[str] = []
    checks = (
        ("present", True),
        ("summaryPresent", True),
        ("ok", True),
        ("providerDisabledOrSkipped", True),
    )
    for key, expected in checks:
        if summary.get(key) is not expected:
            missing.append(key)
    if int(summary.get("status", 0) or 0) != 200:
        missing.append("status")
    if int(summary.get("providerDisabledCount", 0) or 0) <= 0:
        missing.append("providerDisabledCount")
    if int(summary.get("outCount", 0) or 0) <= 0:
        missing.append("outCount")
    if int(summary.get("rawInputCount", 0) or 0) <= 0:
        missing.append("rawInputCount")
    if not summary.get("starvationTrigger"):
        missing.append("starvationTrigger")
    for key in ("secretPatternHits", "rawQueryHits", "probeKeyHits", "rawSecretPatternHits"):
        if int(summary.get(key, 0) or 0) != 0:
            missing.append(key)
    return ", ".join(missing)


def websoak_provider_disabled_ready(summary: dict[str, Any]) -> bool:
    return not websoak_provider_disabled_ready_fail_reason(summary)


def chat_debug_events_readback_artifact_summary(root: Path) -> dict[str, Any]:
    script_path = root / "scripts" / "smoke_chat_debug_events_readback.ps1"
    script_text = read_text(script_path)
    candidates = path_glob(
        root / "verification",
        "chat-debug-events-readback*/chat-debug-events-readback.json",
    )
    path = max(
        candidates,
        key=lambda candidate: candidate.stat().st_mtime if path_is_file(candidate) else 0,
        default=root / "verification" / "chat-debug-events-readback" / "chat-debug-events-readback.json",
    )
    summary_path = path.with_suffix(".summary.txt")
    raw = read_text(path)
    summary_text = read_text(summary_path)
    data = load_json(path)
    summary = data.get("summary") if isinstance(data.get("summary"), dict) else {}
    try:
        path_length = path.stat().st_size if path.is_file() else 0
    except OSError:
        path_length = 0
    try:
        summary_length = summary_path.stat().st_size if summary_path.is_file() else 0
    except OSError:
        summary_length = 0
    try:
        age_seconds = max(0, int((dt.datetime.now().timestamp() - path.stat().st_mtime)))
    except OSError:
        age_seconds = -1
    try:
        readback_event_count = int(summary.get("readbackEventCount", 0) or 0)
    except Exception:
        readback_event_count = 0
    try:
        readback_status = int(summary.get("readbackStatus", 0) or 0)
    except Exception:
        readback_status = 0
    try:
        stream_status = int(summary.get("streamStatus", 0) or 0)
    except Exception:
        stream_status = 0
    return {
        "scriptPresent": script_path.is_file(),
        "scriptHasDiagnosticsEndpoint": "/api/diagnostics/debug/events?limit=" in script_text,
        "scriptHasStreamTrigger": "/api/chat/stream" in script_text,
        "scriptHasReadbackMetadata": all(
            token in script_text
            for token in ("ReadbackStatus", "ReadbackContentType", "readbackEventCount")
        ),
        "scriptSecretPatternHits": len(SECRET_PATTERN.findall(script_text)),
        "present": path.is_file(),
        "summaryPresent": summary_path.is_file(),
        "ok": summary.get("ok") is True,
        "streamStatus": stream_status,
        "readbackStatus": readback_status,
        "readbackContentType": safe_scalar(summary.get("readbackContentType"), 120),
        "readbackEventCount": readback_event_count,
        "operatorDebugEventPresent": summary.get("operatorDebugEventPresent") is True,
        "probe": safe_scalar(summary.get("probe"), 80),
        "fingerprint": safe_scalar(summary.get("fingerprint"), 120),
        "stage": safe_scalar(summary.get("stage"), 80),
        "failureClass": safe_scalar(summary.get("failureClass"), 80),
        "triggerReason": safe_scalar(summary.get("triggerReason"), 80),
        "nextAction": safe_scalar(summary.get("nextAction"), 80),
        "secretPatternHits": int(summary.get("secretPatternHits", 0) or 0),
        "rawPromptHits": int(summary.get("rawPromptHits", 0) or 0),
        "rawModelHits": int(summary.get("rawModelHits", 0) or 0),
        "rawSecretPatternHits": len(SECRET_PATTERN.findall(raw + "\n" + summary_text)),
        "pathHash": sha256_file(path),
        "summaryPathHash": sha256_file(summary_path),
        "pathLength": path_length,
        "summaryPathLength": summary_length,
        "ageSeconds": age_seconds,
        "fresh": 0 <= age_seconds <= CHAT_DEBUG_EVENTS_READBACK_MAX_AGE_SECONDS,
    }


def chat_debug_events_readback_ready_fail_reason(summary: dict[str, Any]) -> str:
    missing: list[str] = []
    checks = (
        ("scriptPresent", True),
        ("scriptHasDiagnosticsEndpoint", True),
        ("scriptHasStreamTrigger", True),
        ("scriptHasReadbackMetadata", True),
        ("present", True),
        ("summaryPresent", True),
        ("ok", True),
        ("operatorDebugEventPresent", True),
        ("fresh", True),
    )
    for key, expected in checks:
        if summary.get(key) is not expected:
            missing.append(key)
    if int(summary.get("streamStatus", 0) or 0) != 200:
        missing.append("streamStatus")
    if int(summary.get("readbackStatus", 0) or 0) != 200:
        missing.append("readbackStatus")
    if int(summary.get("readbackEventCount", 0) or 0) <= 0:
        missing.append("readbackEventCount")
    if summary.get("probe") != "MODEL_GUARD":
        missing.append("probe")
    if summary.get("stage") != "local_llm_operator_action":
        missing.append("stage")
    if summary.get("nextAction") != "prefer_native_ollama_route":
        missing.append("nextAction")
    for key in (
        "scriptSecretPatternHits",
        "secretPatternHits",
        "rawPromptHits",
        "rawModelHits",
        "rawSecretPatternHits",
    ):
        if int(summary.get(key, 0) or 0) != 0:
            missing.append(key)
    return ", ".join(missing)


def chat_debug_events_readback_ready(summary: dict[str, Any]) -> bool:
    return not chat_debug_events_readback_ready_fail_reason(summary)


def source_health_scorecard_artifact_summary(root: Path) -> dict[str, Any]:
    path = root / "verification" / "source-health-scorecard.json"
    raw = read_text(path)
    data = load_json(path)
    details = data.get("nextActionDetails") if isinstance(data.get("nextActionDetails"), list) else []
    source_details = (
        data.get("nextSourceActionDetails")
        if isinstance(data.get("nextSourceActionDetails"), list)
        else []
    )
    supabase_detail = next(
        (
            row
            for row in details
            if isinstance(row, dict) and row.get("action") == "collect-supabase-live-proof"
        ),
        {},
    )
    external_details = [
        row
        for row in details
        if (
            isinstance(row, dict)
            and row.get("action") == "collect-external-evidence-files"
            and row.get("targetRole") in {"macmini", "notebook"}
        )
    ]
    external_roles = sorted(
        {
            str(row.get("targetRole") or "").strip()
            for row in external_details
            if str(row.get("targetRole") or "").strip() in {"macmini", "notebook"}
        }
    )
    required_external_sidecars = {
        ".patch",
        ".report.md",
        ".verify.log",
        ".sha256.txt",
        ".manifest.json",
        "pendingNotice",
    }
    external_sidecars_ready = (
        set(external_roles) == {"macmini", "notebook"}
        and all(
            required_external_sidecars.issubset(
                {str(item).strip() for item in row.get("requiredSidecars", [])}
            )
            for row in external_details
        )
    )
    external_source_isolation_ready = (
        set(external_roles) == {"macmini", "notebook"}
        and all(
            isinstance(row.get("requiredSourceIsolation"), dict)
            and str(
                row["requiredSourceIsolation"].get("guard")
                or row["requiredSourceIsolation"].get("sourceIsolation.guard")
                or ""
            )
            == "PASS"
            and str(row["requiredSourceIsolation"].get("sourceRootKind") or "") == "local-worktree"
            and row["requiredSourceIsolation"].get("directCanonicalSourceEdit") is False
            and str(row["requiredSourceIsolation"].get("desktopFinalProof") or "") == "evidence_needed"
            and int(row["requiredSourceIsolation"].get("rawSecretPatternHits", 0) or 0) == 0
            for row in external_details
        )
    )
    external_apply_command_ready = (
        set(external_roles) == {"macmini", "notebook"}
        and all(
            str(row.get("applyCollectedEvidenceCommand") or "").strip()
            == (
                "powershell -NoProfile -ExecutionPolicy Bypass "
                "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop"
            )
            for row in external_details
        )
    )
    external_next_actions_ready = (
        set(external_roles) == {"macmini", "notebook"}
        and all(
            {
                f"run_{row.get('targetRole')}_external_node_smoke",
                f"collect_{row.get('targetRole')}_producer_handoff_json",
                f"submit_{row.get('targetRole')}_patchdrop_v3_bundle_sidecars",
            }.issubset({str(item).strip() for item in row.get("nextActions", [])})
            for row in external_details
        )
    )
    external_command_templates_ready = (
        set(external_roles) == {"macmini", "notebook"}
        and all(
            (
                len(row.get("producerCommandTemplates", [])) >= 2
                and all(
                    "<producer-local-worktree>" in str(item)
                    and "<desktop-canonical-root>" in str(item)
                    and not re.search(r"[A-Za-z]:\\", str(item))
                    and not re.search(SECRET_PATTERN, str(item))
                    for item in row.get("producerCommandTemplates", [])
                )
            )
            for row in external_details
        )
    )
    supabase_mcp_endpoint = str(supabase_detail.get("mcpEndpointTemplate") or "")
    supabase_mcp_endpoint_template_ready = (
        supabase_mcp_endpoint.startswith("https://mcp.supabase.com/mcp?")
        and "project_ref=${SUPABASE_PROJECT_REF}" in supabase_mcp_endpoint
        and "read_only=true" in supabase_mcp_endpoint
        and "features=database,debugging,docs" in supabase_mcp_endpoint
        and not any(
            token in supabase_mcp_endpoint.lower()
            for token in ("authorization=", "bearer", "access_token=", "password=", "apikey=")
        )
    )
    apply_collected_evidence_command = str(
        supabase_detail.get("applyCollectedEvidenceCommand") or ""
    ).strip()
    apply_collected_evidence_command_path = ""
    if apply_collected_evidence_command == (
        "powershell -NoProfile -ExecutionPolicy Bypass "
        "-File scripts\\supabase_apply_collected_evidence.ps1"
    ):
        apply_collected_evidence_command_path = "scripts\\supabase_apply_collected_evidence.ps1"
    source_contract_detail = next(
        (
            row
            for row in source_details
            if isinstance(row, dict)
            and (
                row.get("action") == "run-cross-subsystem-contract-tests"
                or row.get("sourceContract") is True
            )
        ),
        {},
    )
    required_env = [
        str(item.get("name") or "").strip()
        for item in supabase_detail.get("requiredEnv", [])
        if isinstance(item, dict) and str(item.get("name") or "").strip()
    ]
    detail_json = json.dumps(details, ensure_ascii=False, sort_keys=True)
    source_detail_json = json.dumps(source_details, ensure_ascii=False, sort_keys=True)
    try:
        path_length = path.stat().st_size if path.is_file() else 0
    except OSError:
        path_length = 0
    try:
        score = float(data.get("strictEvidenceAdjustedScore", 0.0) or 0.0)
    except Exception:
        score = 0.0
    try:
        evidence_needed_count = int(data.get("evidenceNeededCount", -1))
    except Exception:
        evidence_needed_count = -1
    (
        generated_at_present,
        fresh,
        age_seconds,
        freshness_status,
    ) = artifact_freshness(
        data.get("generatedAt"),
        SOURCE_HEALTH_SCORECARD_MAX_AGE_SECONDS,
    )
    try:
        query_count = int(supabase_detail.get("queryCount", 0) or 0)
    except Exception:
        query_count = 0
    source_contract_proof = (
        data.get("focusedCrossSubsystemContractProof")
        if isinstance(data.get("focusedCrossSubsystemContractProof"), dict)
        else {}
    )
    try:
        source_contract_proof_passed_count = int(source_contract_proof.get("passedCount", 0) or 0)
    except Exception:
        source_contract_proof_passed_count = 0
    try:
        source_contract_proof_required_count = int(source_contract_proof.get("requiredCount", 0) or 0)
    except Exception:
        source_contract_proof_required_count = 0
    source_contract_proof_passed = (
        source_contract_proof.get("passed") is True
        and source_contract_proof_required_count >= 4
        and source_contract_proof_passed_count >= source_contract_proof_required_count
    )
    broad_runtime_test_proof = (
        data.get("broadRuntimeTestProof")
        if isinstance(data.get("broadRuntimeTestProof"), dict)
        else {}
    )
    try:
        broad_runtime_suite_count = int(broad_runtime_test_proof.get("suiteCount", 0) or 0)
    except Exception:
        broad_runtime_suite_count = 0
    try:
        broad_runtime_test_count = int(broad_runtime_test_proof.get("testCount", 0) or 0)
    except Exception:
        broad_runtime_test_count = 0
    try:
        broad_runtime_failure_count = int(broad_runtime_test_proof.get("failureCount", 0) or 0)
    except Exception:
        broad_runtime_failure_count = 0
    try:
        broad_runtime_error_count = int(broad_runtime_test_proof.get("errorCount", 0) or 0)
    except Exception:
        broad_runtime_error_count = 0
    return {
        "present": path.is_file(),
        "decision": str(data.get("decision") or ""),
        "strictEvidenceAdjustedScore": score,
        "evidenceNeededCount": evidence_needed_count,
        "generatedAt": generated_at_present,
        "fresh": fresh,
        "ageSeconds": age_seconds,
        "freshnessStatus": freshness_status,
        "nextSingleAction": safe_scalar(data.get("nextSingleAction"), 160),
        "nextSourceAction": safe_scalar(data.get("nextSourceAction"), 160),
        "completionAudit": safe_scalar((data.get("inputArtifacts") or {}).get("completionAudit"), 160)
        if isinstance(data.get("inputArtifacts"), dict)
        else "",
        "nextActionDetailsCount": len(details),
        "supabaseLiveProofDetail": bool(supabase_detail),
        "supabaseLiveProofReadOnly": supabase_detail.get("readOnly") is True,
        "supabaseLiveProofMutationBlocked": supabase_detail.get("mutationAllowed") is False,
        "supabaseLiveProofMcpEndpointTemplate": supabase_mcp_endpoint_template_ready,
        "requiredEnv": safe_csv_names(required_env),
        "requiredMcpTools": safe_csv_names(supabase_detail.get("requiredMcpTools")),
        "requiredResultNameCount": len(
            [
                item
                for item in supabase_detail.get("requiredResultNames", [])
                if isinstance(item, str) and item.strip()
            ]
        ),
        "queryCount": query_count,
        "applyCollectedEvidenceCommand": apply_collected_evidence_command_path,
        "externalProducerProofDetailCount": len(external_details),
        "externalProducerProofRoles": safe_csv_names(external_roles),
        "externalProducerProofSidecars": external_sidecars_ready,
        "externalProducerProofSourceIsolation": external_source_isolation_ready,
        "externalProducerProofApplyCommand": external_apply_command_ready,
        "externalProducerProofNextActions": external_next_actions_ready,
        "externalProducerProofCommandTemplates": external_command_templates_ready,
        "resultPathRecommendation": safe_scalar(supabase_detail.get("resultPathRecommendation"), 160),
        "advisorResultPathRecommendation": safe_scalar(supabase_detail.get("advisorResultPathRecommendation"), 160),
        "nextSourceActionDetailsCount": len(source_details),
        "sourceContractDetail": bool(source_contract_detail),
        "sourceContractReadOnly": source_contract_detail.get("readOnly") is True,
        "sourceContractMutationBlocked": source_contract_detail.get("mutationAllowed") is False,
        "sourceContractFocusedTestCount": len(
            [
                item
                for item in source_contract_detail.get("focusedTests", [])
                if isinstance(item, str) and item.strip()
            ]
        ),
        "sourceContractCommandCount": len(
            [
                item
                for item in source_contract_detail.get("commands", [])
                if isinstance(item, str) and item.strip()
            ]
        ),
        "sourceContractTraceKeyCount": len(
            [
                item
                for item in source_contract_detail.get("requiredTraceKeys", [])
                if isinstance(item, str) and item.strip()
            ]
        ),
        "sourceContractProofPassed": source_contract_proof_passed,
        "sourceContractProofPassedCount": source_contract_proof_passed_count,
        "sourceContractProofRequiredCount": source_contract_proof_required_count,
        "broadRuntimeTestProofPassed": broad_runtime_test_proof.get("passed") is True,
        "broadRuntimeTestSuiteCount": broad_runtime_suite_count,
        "broadRuntimeTestCount": broad_runtime_test_count,
        "broadRuntimeTestFailureCount": broad_runtime_failure_count,
        "broadRuntimeTestErrorCount": broad_runtime_error_count,
        "rawSecretPatternHits": len(SECRET_PATTERN.findall(raw)),
        "nextActionDetailsWindowsAbsPathHits": len(
            re.findall(r"(?<![A-Za-z])[A-Za-z]:[\\/][^\r\n\"']+", detail_json)
        ),
        "nextSourceActionDetailsWindowsAbsPathHits": len(
            re.findall(r"(?<![A-Za-z])[A-Za-z]:[\\/][^\r\n\"']+", source_detail_json)
        ),
        "pathHash": sha256_file(path),
        "pathLength": path_length,
    }


def source_health_scorecard_ready_fail_reason(summary: dict[str, Any]) -> str:
    missing: list[str] = []
    if not summary.get("present"):
        missing.append("scorecard missing")
    if summary.get("decision") != "source_health_scorecard":
        missing.append("decision")
    if float(summary.get("strictEvidenceAdjustedScore", 0.0) or 0.0) <= 0.0:
        missing.append("strictEvidenceAdjustedScore")
    if int(summary.get("evidenceNeededCount", -1) or -1) < 0:
        missing.append("evidenceNeededCount")
    if not summary.get("generatedAt"):
        missing.append("generatedAt")
    if not summary.get("fresh"):
        missing.append("fresh")
    if summary.get("freshnessStatus") != "current":
        missing.append("freshnessStatus")
    if not summary.get("nextSingleAction"):
        missing.append("nextSingleAction")
    if not summary.get("nextSourceAction"):
        missing.append("nextSourceAction")
    if not summary.get("completionAudit"):
        missing.append("completionAudit")
    if int(summary.get("nextActionDetailsCount", 0) or 0) <= 0:
        missing.append("nextActionDetailsCount")
    if not summary.get("supabaseLiveProofDetail"):
        missing.append("supabaseLiveProofDetail")
    if not summary.get("supabaseLiveProofReadOnly"):
        missing.append("supabaseLiveProofReadOnly")
    if not summary.get("supabaseLiveProofMutationBlocked"):
        missing.append("supabaseLiveProofMutationBlocked")
    if not summary.get("supabaseLiveProofMcpEndpointTemplate"):
        missing.append("supabaseLiveProofMcpEndpointTemplate")
    env_names = set(str(summary.get("requiredEnv") or "").split(","))
    if not {"SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"}.issubset(env_names):
        missing.append("requiredEnv")
    tool_names = set(str(summary.get("requiredMcpTools") or "").split(","))
    if not {"execute_sql", "get_advisors"}.issubset(tool_names):
        missing.append("requiredMcpTools")
    if int(summary.get("requiredResultNameCount", 0) or 0) < 12:
        missing.append("requiredResultNames")
    if int(summary.get("queryCount", 0) or 0) < 12:
        missing.append("queryCount")
    if summary.get("applyCollectedEvidenceCommand") != "scripts\\supabase_apply_collected_evidence.ps1":
        missing.append("applyCollectedEvidenceCommand")
    external_roles = set(str(summary.get("externalProducerProofRoles") or "").split(","))
    if int(summary.get("externalProducerProofDetailCount", 0) or 0) < 2:
        missing.append("externalProducerProofDetailCount")
    if not {"macmini", "notebook"}.issubset(external_roles):
        missing.append("externalProducerProofRoles")
    if not summary.get("externalProducerProofSidecars"):
        missing.append("externalProducerProofSidecars")
    if not summary.get("externalProducerProofSourceIsolation"):
        missing.append("externalProducerProofSourceIsolation")
    if not summary.get("externalProducerProofApplyCommand"):
        missing.append("externalProducerProofApplyCommand")
    if not summary.get("externalProducerProofNextActions"):
        missing.append("externalProducerProofNextActions")
    if not summary.get("externalProducerProofCommandTemplates"):
        missing.append("externalProducerProofCommandTemplates")
    source_contract_pending_detail_ready = (
        int(summary.get("nextSourceActionDetailsCount", 0) or 0) > 0
        and summary.get("sourceContractDetail")
        and summary.get("sourceContractReadOnly")
        and summary.get("sourceContractMutationBlocked")
        and int(summary.get("sourceContractFocusedTestCount", 0) or 0) >= 4
        and int(summary.get("sourceContractCommandCount", 0) or 0) >= 4
        and int(summary.get("sourceContractTraceKeyCount", 0) or 0) >= 5
    )
    if not (source_contract_pending_detail_ready or summary.get("sourceContractProofPassed")):
        if int(summary.get("nextSourceActionDetailsCount", 0) or 0) <= 0:
            missing.append("nextSourceActionDetailsCount")
        if not summary.get("sourceContractDetail"):
            missing.append("sourceContractDetail")
        if not summary.get("sourceContractReadOnly"):
            missing.append("sourceContractReadOnly")
        if not summary.get("sourceContractMutationBlocked"):
            missing.append("sourceContractMutationBlocked")
        if int(summary.get("sourceContractFocusedTestCount", 0) or 0) < 4:
            missing.append("sourceContractFocusedTests")
        if int(summary.get("sourceContractCommandCount", 0) or 0) < 4:
            missing.append("sourceContractCommands")
        if int(summary.get("sourceContractTraceKeyCount", 0) or 0) < 5:
            missing.append("sourceContractTraceKeys")
        if not summary.get("sourceContractProofPassed"):
            missing.append("sourceContractProofPassed")
    if int(summary.get("rawSecretPatternHits", 0) or 0) != 0:
        missing.append("rawSecretPatternHits")
    if int(summary.get("nextActionDetailsWindowsAbsPathHits", 0) or 0) != 0:
        missing.append("nextActionDetailsWindowsAbsPathHits")
    if int(summary.get("nextSourceActionDetailsWindowsAbsPathHits", 0) or 0) != 0:
        missing.append("nextSourceActionDetailsWindowsAbsPathHits")
    return ", ".join(missing)


def source_health_scorecard_ready(summary: dict[str, Any]) -> bool:
    return not source_health_scorecard_ready_fail_reason(summary)


def _path_from_json_field(root: Path, raw: Any) -> Path:
    text = str(raw or "").strip()
    if not text:
        return Path()
    path = Path(text)
    return path if path.is_absolute() else root / path


def goal_next_command_packet_summary(root: Path) -> dict[str, Any]:
    latest_path = root / "var" / "codex-smoke" / "goal-next-auto.latest.json"
    latest_raw = read_text(latest_path)
    latest = load_json(latest_path)
    (
        latest_generated_at_present,
        latest_fresh,
        latest_age_seconds,
        latest_freshness_status,
    ) = artifact_freshness(latest.get("generatedAt"), GOAL_NEXT_AUTO_MAX_AGE_SECONDS)
    packet_path_raw = str(latest.get("commandPacketPath") or "").strip()
    packet_path = _path_from_json_field(root, packet_path_raw)
    if not packet_path_raw:
        packet_path = root / "var" / "codex-smoke" / "goal-next-auto-current" / "goal-next-auto.command-packet.json"
    packet_raw = read_text(packet_path)
    packet = load_json(packet_path)
    markdown_path_raw = str(latest.get("commandPacketMarkdownPath") or "").strip()
    markdown_path = _path_from_json_field(root, markdown_path_raw)
    if not markdown_path_raw:
        markdown_path = packet_path.with_suffix(".md")
    markdown_raw = read_text(markdown_path)
    summary_path_raw = str(latest.get("summaryPath") or "").strip()
    summary_path = _path_from_json_field(root, summary_path_raw)
    summary = load_json(summary_path) if summary_path_raw else {}
    supabase_smoke = latest.get("supabaseSmoke")
    if not isinstance(supabase_smoke, dict):
        raw_summary_smoke = summary.get("supabaseSmoke") if isinstance(summary, dict) else {}
        supabase_smoke = raw_summary_smoke if isinstance(raw_summary_smoke, dict) else {}
    supabase_smoke_present = bool(supabase_smoke)
    supabase_smoke_mcp_decision = safe_scalar(supabase_smoke.get("mcpDecision"), 80)
    supabase_smoke_project_scope_status = safe_scalar(supabase_smoke.get("projectScopeStatus"), 80)
    supabase_smoke_mcp_decision_ready = supabase_smoke_mcp_decision in {
        "mcp_endpoint_auth_required",
        "mcp_endpoint_probe_skipped",
    }
    computer_use = latest.get("computerUse")
    if not isinstance(computer_use, dict):
        raw_summary_computer_use = summary.get("computerUse") if isinstance(summary, dict) else {}
        computer_use = raw_summary_computer_use if isinstance(raw_summary_computer_use, dict) else {}
    if not isinstance(computer_use, dict) or not computer_use:
        raw_packet_computer_use = packet.get("computerUse") if isinstance(packet, dict) else {}
        computer_use = raw_packet_computer_use if isinstance(raw_packet_computer_use, dict) else computer_use
    computer_use_present = bool(computer_use)
    computer_use_decision = safe_scalar(computer_use.get("decision"), 80)
    computer_use_next_action = safe_scalar(computer_use.get("nextAction"), 80)
    try:
        computer_use_app_count = int(computer_use.get("appCount", 0) or 0)
    except Exception:
        computer_use_app_count = 0

    commands = packet.get("commands") if isinstance(packet.get("commands"), list) else []
    command_text = "\n".join(
        str(row.get("command") or "")
        for row in commands
        if isinstance(row, dict) and str(row.get("command") or "").strip()
    )
    lanes = [
        str(item).strip()
        for item in packet.get("lanes", [])
        if isinstance(item, str) and str(item).strip()
    ]
    if not lanes:
        lanes = sorted(
            {
                str(row.get("lane") or "").strip()
                for row in commands
                if isinstance(row, dict) and str(row.get("lane") or "").strip()
            }
        )
    producer_roles = sorted(
        {
            str(row.get("role") or "").strip()
            for row in commands
            if isinstance(row, dict)
            and str(row.get("lane") or "").strip() == "external_producer"
            and str(row.get("role") or "").strip() in {"macmini", "notebook"}
        }
    )
    supabase_env_names: list[str] = []
    supabase_rows = [
        row
        for row in commands
        if isinstance(row, dict) and str(row.get("lane") or "").strip() == "supabase"
    ]
    for row in commands:
        if not isinstance(row, dict) or str(row.get("lane") or "").strip() != "supabase":
            continue
        raw_env_names = (
            row.get("requiredEnvNames")
            if isinstance(row.get("requiredEnvNames"), list)
            else str(row.get("requiredEnvNames") or "").split()
        )
        for item in raw_env_names:
            name = str(item).strip()
            if name and name not in supabase_env_names:
                supabase_env_names.append(name)
    supabase_read_only_contract_ready = False
    supabase_mcp_endpoint_template_ready = False
    supabase_docs_refs_ready = False
    for row in supabase_rows:
        endpoint_template = str(row.get("mcpEndpointTemplate") or "")
        docs_refs_raw = row.get("docsRefs") if isinstance(row.get("docsRefs"), list) else []
        docs_refs = [str(item) for item in docs_refs_raw if isinstance(item, str)]
        row_read_only = row.get("readOnly") is True
        row_mutation_blocked = row.get("mutationAllowed") is False
        endpoint_ready = (
            "mcp.supabase.com/mcp" in endpoint_template
            and "project_ref=${SUPABASE_PROJECT_REF}" in endpoint_template
            and "read_only=true" in endpoint_template
        )
        docs_ready = any("supabase.com/docs/guides/ai-tools/mcp" in item for item in docs_refs)
        supabase_read_only_contract_ready = (
            supabase_read_only_contract_ready
            or (row_read_only and row_mutation_blocked and endpoint_ready and docs_ready)
        )
        supabase_mcp_endpoint_template_ready = supabase_mcp_endpoint_template_ready or endpoint_ready
        supabase_docs_refs_ready = supabase_docs_refs_ready or docs_ready
    external_desktop_command_ready = any(
        isinstance(row, dict)
        and str(row.get("lane") or "").strip() == "external_desktop"
        and "scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop"
        in str(row.get("command") or "")
        for row in commands
    )
    supabase_command_ready = any(
        isinstance(row, dict)
        and str(row.get("lane") or "").strip() == "supabase"
        and "scripts\\supabase_apply_collected_evidence.ps1" in str(row.get("command") or "")
        for row in commands
    )
    producer_placeholders_ready = (
        set(producer_roles) == {"macmini", "notebook"}
        and all(
            "<producer-local-worktree>" in str(row.get("command") or "")
            and "<desktop-canonical-root>" in str(row.get("command") or "")
            for row in commands
            if isinstance(row, dict) and str(row.get("lane") or "").strip() == "external_producer"
        )
    )
    try:
        packet_count = int(packet.get("commandCount", 0) or 0)
    except Exception:
        packet_count = 0
    command_count = max(packet_count, len(commands))
    try:
        packet_length = packet_path.stat().st_size if packet_path.is_file() else 0
    except OSError:
        packet_length = 0
    return {
        "latestPresent": latest_path.is_file(),
        "latestGeneratedAt": latest_generated_at_present,
        "latestFresh": latest_fresh,
        "latestAgeSeconds": latest_age_seconds,
        "latestFreshnessStatus": latest_freshness_status,
        "latestDecision": safe_scalar(latest.get("decision"), 80),
        "commandPacketPresent": packet_path.is_file(),
        "commandPacketMarkdownPresent": markdown_path.is_file(),
        "schemaVersion": str(packet.get("schemaVersion") or ""),
        "decision": safe_scalar(packet.get("decision"), 80),
        "topic": safe_scalar(packet.get("topic"), 80),
        "commandCount": command_count,
        "lanes": safe_csv_names(lanes),
        "producerRoles": safe_csv_names(producer_roles),
        "supabaseEnvNames": safe_csv_names(supabase_env_names),
        "supabaseCommand": supabase_command_ready,
        "supabaseReadOnlyContract": supabase_read_only_contract_ready,
        "supabaseMcpEndpointTemplate": supabase_mcp_endpoint_template_ready,
        "supabaseDocsRefs": supabase_docs_refs_ready,
        "externalDesktopCommand": external_desktop_command_ready,
        "producerPlaceholders": producer_placeholders_ready,
        "supabaseSmokePresent": supabase_smoke_present,
        "supabaseSmokeParsed": supabase_smoke.get("parsed") is True,
        "supabaseSmokeDecision": safe_scalar(supabase_smoke.get("decision"), 80),
        "supabaseSmokeMcpDecision": supabase_smoke_mcp_decision,
        "supabaseSmokeMcpDecisionReady": supabase_smoke_mcp_decision_ready,
        "supabaseSmokeMcpReachable": supabase_smoke.get("mcpReachable") is True,
        "supabaseSmokeMcpProbeSkipped": supabase_smoke.get("mcpProbeSkipped") is True,
        "supabaseSmokeProjectScopeStatus": supabase_smoke_project_scope_status,
        "computerUsePresent": computer_use_present,
        "computerUseParsed": computer_use.get("parsed") is True,
        "computerUseOk": computer_use.get("ok") is True,
        "computerUseReachable": computer_use.get("reachable") is True,
        "computerUseStale": computer_use.get("stale") is True,
        "computerUseDecision": computer_use_decision,
        "computerUseNextAction": computer_use_next_action,
        "computerUseAppCount": computer_use_app_count,
        "commandWindowsAbsPathHits": len(
            re.findall(r"(?<![A-Za-z])[A-Za-z]:[\\/][^\r\n\"']+", command_text)
        ),
        "rawSecretPatternHits": len(SECRET_PATTERN.findall(latest_raw + "\n" + packet_raw + "\n" + markdown_raw)),
        "pathHash": sha256_file(packet_path),
        "pathLength": packet_length,
    }


def goal_next_command_packet_ready_fail_reason(summary: dict[str, Any]) -> str:
    missing: list[str] = []
    if not summary.get("latestPresent"):
        missing.append("latestPresent")
    if not summary.get("latestGeneratedAt"):
        missing.append("latestGeneratedAt")
    if not summary.get("latestFresh"):
        missing.append("latestFresh")
    if summary.get("latestFreshnessStatus") != "current":
        missing.append("latestFreshnessStatus")
    if not summary.get("commandPacketPresent"):
        missing.append("commandPacketPresent")
    if not summary.get("commandPacketMarkdownPresent"):
        missing.append("commandPacketMarkdownPresent")
    if summary.get("schemaVersion") != "awx.goal_next_auto.command_packet.v1":
        missing.append("schemaVersion")
    lanes = set(str(summary.get("lanes") or "").split(","))
    if not {"supabase", "external_desktop", "external_producer"}.issubset(lanes):
        for lane in ("supabase", "external_desktop", "external_producer"):
            if lane not in lanes:
                missing.append(lane)
    if int(summary.get("commandCount", 0) or 0) < 6:
        missing.append("commandCount")
    producer_roles = set(str(summary.get("producerRoles") or "").split(","))
    if not {"macmini", "notebook"}.issubset(producer_roles):
        missing.append("producerRoles")
    supabase_env_names = set(str(summary.get("supabaseEnvNames") or "").split(","))
    if not {"SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"}.issubset(supabase_env_names):
        missing.append("supabaseEnvNames")
    if not summary.get("supabaseCommand"):
        missing.append("supabaseCommand")
    if not summary.get("supabaseReadOnlyContract"):
        missing.append("supabaseReadOnlyContract")
    if not summary.get("supabaseMcpEndpointTemplate"):
        missing.append("supabaseMcpEndpointTemplate")
    if not summary.get("supabaseDocsRefs"):
        missing.append("supabaseDocsRefs")
    if not summary.get("externalDesktopCommand"):
        missing.append("externalDesktopCommand")
    if not summary.get("producerPlaceholders"):
        missing.append("producerPlaceholders")
    if not summary.get("supabaseSmokePresent"):
        missing.append("supabaseSmoke")
    if not summary.get("supabaseSmokeMcpDecisionReady"):
        missing.append("supabaseSmokeMcpDecision")
    if not str(summary.get("supabaseSmokeProjectScopeStatus") or "").strip():
        missing.append("supabaseSmokeProjectScopeStatus")
    if not summary.get("computerUsePresent"):
        missing.append("computerUse")
    if not str(summary.get("computerUseDecision") or "").strip():
        missing.append("computerUseDecision")
    if not summary.get("computerUseOk") and not str(summary.get("computerUseNextAction") or "").strip():
        missing.append("computerUseNextAction")
    if int(summary.get("commandWindowsAbsPathHits", 0) or 0) != 0:
        missing.append("commandWindowsAbsPathHits")
    if int(summary.get("rawSecretPatternHits", 0) or 0) != 0:
        missing.append("rawSecretPatternHits")
    return ", ".join(missing)


def goal_next_command_packet_ready(summary: dict[str, Any]) -> bool:
    return not goal_next_command_packet_ready_fail_reason(summary)


def db_gap_report_artifact_summary(root: Path) -> dict[str, Any]:
    path = root / "data" / "db-gap-report" / "gap_matrix.json"
    raw = read_text(path)
    data = load_json(path)
    severity_counts = data.get("gap_severity_counts") if isinstance(data.get("gap_severity_counts"), dict) else {}
    status_counts = data.get("persistence_status_counts") if isinstance(data.get("persistence_status_counts"), dict) else {}
    subsystems = data.get("subsystems") if isinstance(data.get("subsystems"), list) else []
    residual_volatile_state = (
        data.get("residual_volatile_state")
        if isinstance(data.get("residual_volatile_state"), list)
        else []
    )
    try:
        residual_volatile_state_count = int(data.get("residual_volatile_state_count", -1))
    except Exception:
        residual_volatile_state_count = -1
    residual_volatile_state_ids = ",".join(
        str(item.get("id", "")).strip()
        for item in residual_volatile_state
        if isinstance(item, dict) and str(item.get("id", "")).strip()
    )
    external_supabase = (
        data.get("external_supabase_snapshot")
        if isinstance(data.get("external_supabase_snapshot"), dict)
        else {}
    )
    external_evidence_needed = (
        external_supabase.get("evidence_needed")
        if isinstance(external_supabase.get("evidence_needed"), list)
        else []
    )
    external_evidence_text = " ".join(str(item) for item in external_evidence_needed).lower()
    external_supabase_risk_summary = external_supabase.get("riskSummary")
    risk_keys = (
        "exposedTablesWithoutRls",
        "metadataPolicyRisk",
        "updateSelectPolicyGap",
        "storageUpsertPolicyGap",
        "viewsMissingSecurityInvoker",
        "exposedSecurityDefinerFunctions",
    )
    risk_finding_count = 0
    risk_finding_keys: list[str] = []
    if isinstance(external_supabase_risk_summary, dict):
        for key in risk_keys:
            try:
                count = max(0, int(external_supabase_risk_summary.get(key, 0) or 0))
            except Exception:
                count = 0
            if count:
                risk_finding_count += count
                risk_finding_keys.append(key)
    external_snapshot_import = (
        external_supabase.get("snapshotImport")
        if isinstance(external_supabase.get("snapshotImport"), dict)
        else {}
    )
    external_input_shape = (
        external_supabase.get("inputShape")
        if isinstance(external_supabase.get("inputShape"), dict)
        else {}
    )
    external_project_scope = (
        external_supabase.get("projectScope")
        if isinstance(external_supabase.get("projectScope"), dict)
        else {}
    )
    external_auth_plan = (
        external_supabase.get("authPlan")
        if isinstance(external_supabase.get("authPlan"), dict)
        else {}
    )
    external_docs_contracts = (
        external_supabase.get("docsContracts")
        if isinstance(external_supabase.get("docsContracts"), dict)
        else {}
    )
    external_collection_packet = (
        external_supabase.get("collectionPacket")
        if isinstance(external_supabase.get("collectionPacket"), dict)
        else {}
    )
    external_advisors = (
        external_supabase.get("advisors")
        if isinstance(external_supabase.get("advisors"), dict)
        else {}
    )
    sql_bundle_path = str(external_supabase.get("sqlBundlePath") or "")
    sql_bundle_path_hash = str(external_supabase.get("sqlBundlePathHash") or "")
    try:
        sql_bundle_path_length = int(external_supabase.get("sqlBundlePathLength", 0) or 0)
    except Exception:
        sql_bundle_path_length = 0
    sql_bundle_redacted = (
        sql_bundle_path.startswith("pathHash=")
        and "pathLength=" in sql_bundle_path
    )
    sql_bundle_evidence_present = bool(
        sql_bundle_path.endswith("supabase-readonly-snapshot.sql")
        or sql_bundle_redacted
        or (sql_bundle_path_hash and sql_bundle_path_length > 0)
    )
    secret_hits = len(SECRET_PATTERN.findall(raw))
    imported_count = external_snapshot_import.get("importedCount")
    try:
        imported_count_value = int(imported_count) if imported_count is not None else -1
    except Exception:
        imported_count_value = -1
    missing_result_count = external_snapshot_import.get("missingResultCount")
    try:
        missing_result_count_value = int(missing_result_count) if missing_result_count is not None else 0
    except Exception:
        missing_result_count_value = 0
    missing_result_names = safe_csv_names(external_snapshot_import.get("missingResultNames"))
    unexpected_result_count = external_snapshot_import.get("unexpectedResultCount")
    try:
        unexpected_result_count_value = int(unexpected_result_count) if unexpected_result_count is not None else 0
    except Exception:
        unexpected_result_count_value = 0
    unexpected_result_names = safe_csv_names(external_snapshot_import.get("unexpectedResultNames"))
    duplicate_result_count = external_snapshot_import.get("duplicateResultCount")
    try:
        duplicate_result_count_value = int(duplicate_result_count) if duplicate_result_count is not None else 0
    except Exception:
        duplicate_result_count_value = 0
    duplicate_result_names = safe_csv_names(external_snapshot_import.get("duplicateResultNames"))
    supported_result_count = external_input_shape.get("supportedResultItemCount")
    try:
        supported_result_count_value = int(supported_result_count) if supported_result_count is not None else -1
    except Exception:
        supported_result_count_value = -1
    collection_packet_query_count = external_collection_packet.get("queryCount")
    try:
        collection_packet_query_count_value = (
            int(collection_packet_query_count)
            if collection_packet_query_count is not None
            else -1
        )
    except Exception:
        collection_packet_query_count_value = -1
    collection_packet_declared_query_count = external_collection_packet.get("declaredQueryCount")
    try:
        collection_packet_declared_query_count_value = (
            int(collection_packet_declared_query_count)
            if collection_packet_declared_query_count is not None
            else -1
        )
    except Exception:
        collection_packet_declared_query_count_value = -1
    collection_packet_query_count_mismatch = external_collection_packet.get("queryCountMismatch")
    collection_packet_next_actions = safe_csv_names(external_collection_packet.get("nextActions"))
    collection_packet_secret_hits = external_collection_packet.get("secretPatternHits")
    try:
        collection_packet_secret_hits_value = (
            int(collection_packet_secret_hits)
            if collection_packet_secret_hits is not None
            else -1
        )
    except Exception:
        collection_packet_secret_hits_value = -1
    advisor_row_count = external_advisors.get("rowCount")
    try:
        advisor_row_count_value = int(advisor_row_count) if advisor_row_count is not None else -1
    except Exception:
        advisor_row_count_value = -1
    advisor_error_count = external_advisors.get("errorCount")
    try:
        advisor_error_count_value = int(advisor_error_count) if advisor_error_count is not None else 0
    except Exception:
        advisor_error_count_value = 0
    advisor_warning_count = external_advisors.get("warningCount")
    try:
        advisor_warning_count_value = int(advisor_warning_count) if advisor_warning_count is not None else 0
    except Exception:
        advisor_warning_count_value = 0
    try:
        docs_ref_count_value = int(external_docs_contracts.get("docsRefCount", 0) or 0)
    except Exception:
        docs_ref_count_value = 0
    try:
        security_contract_count_value = int(external_docs_contracts.get("securityContractCount", 0) or 0)
    except Exception:
        security_contract_count_value = 0
    try:
        path_length = path.stat().st_size if path.is_file() else 0
    except OSError:
        path_length = 0
    (
        generated_at_present,
        fresh,
        age_seconds,
        freshness_status,
    ) = artifact_freshness(data.get("generatedAt"), DB_GAP_REPORT_MAX_AGE_SECONDS)
    external_supabase_auth_required = (
        "auth required" in external_evidence_text
        or "oauth" in external_evidence_text
        or external_auth_plan.get("mcpOAuthRequired") is True
        or bool(str(external_auth_plan.get("manualAuthSensitiveEnvRefs") or "").strip())
    )
    return {
        "present": path.is_file(),
        "generatedAt": generated_at_present,
        "fresh": fresh,
        "ageSeconds": age_seconds,
        "freshnessStatus": freshness_status,
        "subsystemCount": len(subsystems),
        "actionRequiredCount": int(data.get("action_required_count", -1) if isinstance(data.get("action_required_count"), int) else -1),
        "criticalCount": int(severity_counts.get("CRITICAL", -1) if isinstance(severity_counts.get("CRITICAL"), int) else -1),
        "mediumCount": int(severity_counts.get("MEDIUM", -1) if isinstance(severity_counts.get("MEDIUM"), int) else -1),
        "reviewRuntimeOnlyCount": int(
            status_counts.get("review_runtime_only", -1)
            if isinstance(status_counts.get("review_runtime_only"), int)
            else -1
        ),
        "resolvedJpaCount": int(
            status_counts.get("resolved_jpa", -1) if isinstance(status_counts.get("resolved_jpa"), int) else -1
        ),
        "residualVolatileStateCount": residual_volatile_state_count,
        "residualVolatileStateIds": residual_volatile_state_ids,
        "externalSupabaseSnapshot": bool(external_supabase),
        "externalSupabaseReadOnly": external_supabase.get("readOnly") is True,
        "externalSupabaseMutationAllowedValue": external_supabase.get("mutationAllowed"),
        "externalSupabaseMutationBlocked": external_supabase.get("mutationAllowed") is False,
        "externalSupabaseSqlBundle": sql_bundle_evidence_present,
        "externalSupabaseCollectionPacket": bool(external_collection_packet),
        "externalSupabaseCollectionPacketStatus": str(external_collection_packet.get("status") or "absent"),
        "externalSupabaseCollectionPacketTool": str(external_collection_packet.get("mcpTool") or "absent"),
        "externalSupabaseCollectionPacketReadOnly": external_collection_packet.get("readOnly") is True,
        "externalSupabaseCollectionPacketMutationAllowedValue": external_collection_packet.get("mutationAllowed"),
        "externalSupabaseCollectionPacketMutationBlocked": external_collection_packet.get("mutationAllowed") is False,
        "externalSupabaseCollectionPacketQueryCount": collection_packet_query_count_value,
        "externalSupabaseCollectionPacketDeclaredQueryCount": collection_packet_declared_query_count_value,
        "externalSupabaseCollectionPacketQueryCountMismatch": (
            collection_packet_query_count_mismatch
            if isinstance(collection_packet_query_count_mismatch, bool)
            else None
        ),
        "externalSupabaseCollectionPacketNextActions": collection_packet_next_actions,
        "externalSupabaseCollectionPacketSecretHits": collection_packet_secret_hits_value,
        "externalSupabaseAdvisors": bool(external_advisors),
        "externalSupabaseAdvisorsStatus": str(external_advisors.get("status") or "absent"),
        "externalSupabaseAdvisorsAvailable": external_advisors.get("available") is True,
        "externalSupabaseAdvisorRowCount": advisor_row_count_value,
        "externalSupabaseAdvisorErrorCount": advisor_error_count_value,
        "externalSupabaseAdvisorWarningCount": advisor_warning_count_value,
        "externalSupabaseAdvisorCategories": str(external_advisors.get("categories") or ""),
        "externalSupabaseAdvisorCollectionTool": str(external_advisors.get("collectionTool") or "absent"),
        "externalSupabaseAdvisorFeatureGroup": str(external_advisors.get("featureGroup") or "absent"),
        "externalSupabaseRiskSummary": isinstance(external_supabase_risk_summary, dict),
        "externalSupabaseRiskFindingCount": risk_finding_count,
        "externalSupabaseRiskFindingKeys": ",".join(risk_finding_keys),
        "externalSupabaseAuthPlan": bool(external_auth_plan),
        "externalSupabaseDocsContracts": bool(external_docs_contracts),
        "externalSupabaseDocsRefCount": docs_ref_count_value,
        "externalSupabaseSecurityContractCount": security_contract_count_value,
        "externalSupabaseApiKeysDocs": external_docs_contracts.get("apiKeysDocs") is True,
        "externalSupabaseSecretKeysBackendOnly": external_docs_contracts.get("secretKeysBackendOnly") is True,
        "externalSupabaseEvidenceNeeded": [
            str(item) for item in external_evidence_needed if str(item).strip()
        ][:8],
        "externalSupabaseEvidenceNeededCount": len(external_evidence_needed),
        "externalSupabaseAuthRequired": external_supabase_auth_required,
        "externalSupabaseCliMissing": "cli missing" in external_evidence_text,
        "externalSupabaseDefaultHostedAuthMode": str(
            external_auth_plan.get("defaultHostedAuthMode") or "absent"
        ),
        "externalSupabaseSupportedAuthModes": str(external_auth_plan.get("supportedAuthModes") or ""),
        "externalSupabaseMcpOAuthRequired": external_auth_plan.get("mcpOAuthRequired") is True,
        "externalSupabaseManualAuthMode": str(external_auth_plan.get("manualAuthMode") or "absent"),
        "externalSupabaseManualAuthSensitiveEnvRefs": str(
            external_auth_plan.get("manualAuthSensitiveEnvRefs") or ""
        ),
        "externalSupabaseSnapshotImportStatus": str(external_snapshot_import.get("status") or "absent"),
        "externalSupabaseSnapshotImportedCount": imported_count_value,
        "externalSupabaseMissingResultCount": missing_result_count_value,
        "externalSupabaseMissingResultNames": missing_result_names,
        "externalSupabaseUnexpectedResultCount": unexpected_result_count_value,
        "externalSupabaseUnexpectedResultNames": unexpected_result_names,
        "externalSupabaseDuplicateResultCount": duplicate_result_count_value,
        "externalSupabaseDuplicateResultNames": duplicate_result_names,
        "externalSupabaseInputShape": bool(external_input_shape),
        "externalSupabaseSupportedResultItemCount": supported_result_count_value,
        "externalSupabaseProjectScopeStatus": str(external_project_scope.get("status") or "absent"),
        "externalSupabaseProjectScopeNextAction": str(external_project_scope.get("nextAction") or "absent"),
        "externalSupabaseProjectRefPresent": external_project_scope.get("projectRefPresent") is True,
        "externalSupabaseProjectRefTemplateMode": external_project_scope.get("projectRefTemplateMode") is True,
        "rawSecretPatternHits": secret_hits,
        "pathHash": sha256_file(path),
        "pathLength": path_length,
    }


def db_gap_report_ready_fail_reason(summary: dict[str, Any]) -> str:
    missing: list[str] = []
    if not summary.get("present"):
        missing.append("report missing")
    if summary.get("subsystemCount") != 8:
        missing.append("subsystemCount")
    if not summary.get("generatedAt"):
        missing.append("generatedAt")
    if not summary.get("fresh"):
        missing.append("fresh")
    if summary.get("freshnessStatus") != "current":
        missing.append("freshnessStatus")
    if summary.get("actionRequiredCount") != 0:
        missing.append("actionRequired")
    if summary.get("criticalCount") != 0:
        missing.append("critical")
    if summary.get("mediumCount") != 0:
        missing.append("medium")
    try:
        residual_volatile_state_count = int(summary.get("residualVolatileStateCount", -1))
    except Exception:
        residual_volatile_state_count = -1
    if residual_volatile_state_count < 0:
        missing.append("residualVolatileStateCount")
    if not summary.get("externalSupabaseSnapshot"):
        missing.append("externalSupabaseSnapshot")
    if not summary.get("externalSupabaseReadOnly"):
        missing.append("externalSupabaseReadOnly")
    if not summary.get("externalSupabaseMutationBlocked"):
        missing.append("externalSupabaseMutationBlocked")
    if not summary.get("externalSupabaseSqlBundle"):
        missing.append("externalSupabaseSqlBundle")
    if not summary.get("externalSupabaseCollectionPacket"):
        missing.append("externalSupabaseCollectionPacket")
    if summary.get("externalSupabaseCollectionPacketTool") != "execute_sql":
        missing.append("externalSupabaseCollectionPacketTool")
    if not summary.get("externalSupabaseCollectionPacketReadOnly"):
        missing.append("externalSupabaseCollectionPacketReadOnly")
    if not summary.get("externalSupabaseCollectionPacketMutationBlocked"):
        missing.append("externalSupabaseCollectionPacketMutationBlocked")
    try:
        collection_packet_query_count = int(summary.get("externalSupabaseCollectionPacketQueryCount", -1))
    except Exception:
        collection_packet_query_count = -1
    if collection_packet_query_count <= 0:
        missing.append("externalSupabaseCollectionPacketQueryCount")
    try:
        collection_packet_declared_query_count = int(
            summary.get("externalSupabaseCollectionPacketDeclaredQueryCount", -1)
        )
    except Exception:
        collection_packet_declared_query_count = -1
    if collection_packet_declared_query_count <= 0:
        missing.append("externalSupabaseCollectionPacketDeclaredQueryCount")
    elif collection_packet_query_count != collection_packet_declared_query_count:
        missing.append("externalSupabaseCollectionPacketDeclaredQueryCountMismatch")
    if summary.get("externalSupabaseCollectionPacketQueryCountMismatch") is not False:
        missing.append("externalSupabaseCollectionPacketQueryCountMismatch")
    if not summary.get("externalSupabaseCollectionPacketNextActions"):
        missing.append("externalSupabaseCollectionPacketNextActions")
    try:
        collection_packet_secret_hits = int(summary.get("externalSupabaseCollectionPacketSecretHits", -1))
    except Exception:
        collection_packet_secret_hits = -1
    if collection_packet_secret_hits != 0:
        missing.append("externalSupabaseCollectionPacketSecretHits")
    if not summary.get("externalSupabaseAdvisors"):
        missing.append("externalSupabaseAdvisors")
    if summary.get("externalSupabaseAdvisorCollectionTool") != "get_advisors":
        missing.append("externalSupabaseAdvisorCollectionTool")
    if summary.get("externalSupabaseAdvisorFeatureGroup") != "debugging":
        missing.append("externalSupabaseAdvisorFeatureGroup")
    try:
        advisor_row_count = int(summary.get("externalSupabaseAdvisorRowCount", -1))
    except Exception:
        advisor_row_count = -1
    if advisor_row_count < 0:
        missing.append("externalSupabaseAdvisorRowCount")
    if not summary.get("externalSupabaseRiskSummary"):
        missing.append("externalSupabaseRiskSummary")
    if not summary.get("externalSupabaseAuthPlan"):
        missing.append("externalSupabaseAuthPlan")
    if not summary.get("externalSupabaseDocsContracts"):
        missing.append("externalSupabaseDocsContracts")
    if not summary.get("externalSupabaseApiKeysDocs"):
        missing.append("externalSupabaseApiKeysDocs")
    if not summary.get("externalSupabaseSecretKeysBackendOnly"):
        missing.append("externalSupabaseSecretKeysBackendOnly")
    if summary.get("externalSupabaseDefaultHostedAuthMode") != "dynamic_client_registration_oauth":
        missing.append("externalSupabaseDefaultHostedAuthMode")
    supported_auth_modes = set(str(summary.get("externalSupabaseSupportedAuthModes") or "").split(","))
    if not {"dynamic_oauth", "manual_ci_pat_auth"}.issubset(supported_auth_modes):
        missing.append("externalSupabaseSupportedAuthModes")
    if not summary.get("externalSupabaseMcpOAuthRequired"):
        missing.append("externalSupabaseMcpOAuthRequired")
    if summary.get("externalSupabaseManualAuthMode") != "manual_pat_header_for_ci":
        missing.append("externalSupabaseManualAuthMode")
    if "SUPABASE_ACCESS_TOKEN" not in str(summary.get("externalSupabaseManualAuthSensitiveEnvRefs") or ""):
        missing.append("externalSupabaseManualAuthSensitiveEnvRefs")
    if int(summary.get("externalSupabaseRiskFindingCount", 0) or 0) > 0:
        keys = str(summary.get("externalSupabaseRiskFindingKeys") or "")
        missing.append(f"externalSupabaseRiskFindingCount({keys})" if keys else "externalSupabaseRiskFindingCount")
    if summary.get("externalSupabaseSnapshotImportStatus") not in {"evidence_needed", "imported"}:
        missing.append("externalSupabaseSnapshotImportStatus")
    try:
        imported_count = int(summary.get("externalSupabaseSnapshotImportedCount", -1))
    except Exception:
        imported_count = -1
    if imported_count < 0 or (
        summary.get("externalSupabaseSnapshotImportStatus") == "imported" and imported_count == 0
    ):
        missing.append("externalSupabaseSnapshotImportedCount")
    try:
        missing_result_count = int(summary.get("externalSupabaseMissingResultCount", 0) or 0)
    except Exception:
        missing_result_count = 0
    try:
        supported_result_item_count_for_missing = int(
            summary.get("externalSupabaseSupportedResultItemCount", -1)
        )
    except Exception:
        supported_result_item_count_for_missing = -1
    uncollected_external_snapshot = (
        summary.get("externalSupabaseSnapshotImportStatus") == "evidence_needed"
        and imported_count <= 0
        and supported_result_item_count_for_missing <= 0
    )
    if missing_result_count > 0 and not uncollected_external_snapshot:
        names = str(summary.get("externalSupabaseMissingResultNames") or "")
        missing.append(f"externalSupabaseMissingResultNames({names})" if names else "externalSupabaseMissingResultCount")
    try:
        unexpected_result_count = int(summary.get("externalSupabaseUnexpectedResultCount", 0) or 0)
    except Exception:
        unexpected_result_count = 0
    if unexpected_result_count > 0:
        names = str(summary.get("externalSupabaseUnexpectedResultNames") or "")
        missing.append(f"externalSupabaseUnexpectedResultNames({names})" if names else "externalSupabaseUnexpectedResultCount")
    try:
        duplicate_result_count = int(summary.get("externalSupabaseDuplicateResultCount", 0) or 0)
    except Exception:
        duplicate_result_count = 0
    if duplicate_result_count > 0:
        names = str(summary.get("externalSupabaseDuplicateResultNames") or "")
        missing.append(f"externalSupabaseDuplicateResultNames({names})" if names else "externalSupabaseDuplicateResultCount")
    if not summary.get("externalSupabaseInputShape"):
        missing.append("externalSupabaseInputShape")
    try:
        supported_result_item_count = int(summary.get("externalSupabaseSupportedResultItemCount", -1))
    except Exception:
        supported_result_item_count = -1
    if supported_result_item_count < 0:
        missing.append("externalSupabaseSupportedResultItemCount")
    if int(summary.get("rawSecretPatternHits", 0) or 0) != 0:
        missing.append("rawSecretPatternHits")
    return ", ".join(missing)


def db_gap_report_ready(summary: dict[str, Any]) -> bool:
    return not db_gap_report_ready_fail_reason(summary)


def supabase_smoke_proves_auth_required(summary: dict[str, Any] | None) -> bool:
    if not summary:
        return False
    return (
        summary.get("summaryFresh") is True
        and summary.get("summaryFreshnessStatus") == "current"
        and summary.get("mcpReachable") is True
        and summary.get("mcpDecision") == "mcp_endpoint_auth_required"
    )


def supabase_live_db_structure_requirement(
    summary: dict[str, Any],
    supabase_smoke_summary: dict[str, Any] | None = None,
) -> dict[str, Any]:
    evidence_needed: list[str] = []
    fresh_auth_probe = supabase_smoke_proves_auth_required(supabase_smoke_summary)
    project_scope_status = str(summary.get("externalSupabaseProjectScopeStatus") or "absent")
    project_ref_present = summary.get("externalSupabaseProjectRefPresent") is True
    snapshot_import_status = str(summary.get("externalSupabaseSnapshotImportStatus") or "absent")
    try:
        imported_count = int(summary.get("externalSupabaseSnapshotImportedCount", -1))
    except Exception:
        imported_count = -1
    try:
        supported_result_count = int(summary.get("externalSupabaseSupportedResultItemCount", -1))
    except Exception:
        supported_result_count = -1
    try:
        missing_result_count = int(summary.get("externalSupabaseMissingResultCount", 0) or 0)
    except Exception:
        missing_result_count = 0
    try:
        unexpected_result_count = int(summary.get("externalSupabaseUnexpectedResultCount", 0) or 0)
    except Exception:
        unexpected_result_count = 0
    try:
        duplicate_result_count = int(summary.get("externalSupabaseDuplicateResultCount", 0) or 0)
    except Exception:
        duplicate_result_count = 0
    try:
        risk_finding_count = int(summary.get("externalSupabaseRiskFindingCount", 0) or 0)
    except Exception:
        risk_finding_count = 0
    advisor_status = str(summary.get("externalSupabaseAdvisorsStatus") or "absent")
    advisor_tool = str(summary.get("externalSupabaseAdvisorCollectionTool") or "absent")
    advisor_feature_group = str(summary.get("externalSupabaseAdvisorFeatureGroup") or "absent")
    try:
        advisor_row_count = int(summary.get("externalSupabaseAdvisorRowCount", -1))
    except Exception:
        advisor_row_count = -1
    try:
        advisor_error_count = int(summary.get("externalSupabaseAdvisorErrorCount", 0) or 0)
    except Exception:
        advisor_error_count = 0
    try:
        advisor_warning_count = int(summary.get("externalSupabaseAdvisorWarningCount", 0) or 0)
    except Exception:
        advisor_warning_count = 0

    if not project_ref_present:
        evidence_needed.append("Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project")
    for item in summary.get("externalSupabaseEvidenceNeeded", []):
        text = str(item).strip()
        if fresh_auth_probe and text == "Supabase MCP endpoint not reachable from this host / verify network and URL":
            continue
        if text and text not in evidence_needed:
            evidence_needed.append(text)
    if summary.get("externalSupabaseAuthRequired") is True:
        evidence_needed.append("Supabase MCP endpoint auth required / complete OAuth or provide SUPABASE_ACCESS_TOKEN for read-only proof")
    if snapshot_import_status != "imported":
        evidence_needed.append("Supabase live schema snapshot not imported / run read-only execute_sql collection and import results")
    if imported_count <= 0:
        evidence_needed.append("Supabase execute_sql result sets missing / populate a results JSON file and rerun supabase_schema_snapshot_import")
    if supported_result_count <= 0:
        evidence_needed.append("Supabase supported result item count is zero / verify execute_sql transcript shape before import")
    if missing_result_count:
        names = str(summary.get("externalSupabaseMissingResultNames") or "")
        evidence_needed.append(f"Supabase query results missing expected probes / {names}")
    if unexpected_result_count:
        names = str(summary.get("externalSupabaseUnexpectedResultNames") or "")
        evidence_needed.append(f"Supabase query results contain unexpected probes / {names}")
    if duplicate_result_count:
        names = str(summary.get("externalSupabaseDuplicateResultNames") or "")
        evidence_needed.append(f"Supabase query results contain duplicate probes / {names}")
    if risk_finding_count:
        keys = str(summary.get("externalSupabaseRiskFindingKeys") or "")
        evidence_needed.append(f"Supabase live DB risk findings require review / {keys}")
    if advisor_tool != "get_advisors" or advisor_feature_group != "debugging":
        evidence_needed.append("Supabase advisor collection contract missing / run MCP get_advisors in debugging feature scope")
    if advisor_row_count < 0:
        evidence_needed.append("Supabase advisor summary missing / rerun db_gap_scanner after advisor collection plan generation")
    elif advisor_status == "not_collected" or summary.get("externalSupabaseAdvisorsAvailable") is not True:
        evidence_needed.append("Supabase advisor rows not imported / run read-only get_advisors and update advisors.rows")
    if advisor_error_count or advisor_warning_count:
        evidence_needed.append(
            f"Supabase advisors reported findings / errors={advisor_error_count} warnings={advisor_warning_count}"
        )

    status = "satisfied" if not evidence_needed and summary.get("externalSupabaseReadOnly") is True else "evidence_needed"
    evidence = (
        f"snapshotImportStatus={snapshot_import_status};"
        f"snapshotImportedCount={imported_count};"
        f"supportedResultItemCount={supported_result_count};"
        f"collectionPacketQueryCount={summary.get('externalSupabaseCollectionPacketQueryCount')};"
        f"collectionPacketDeclaredQueryCount={summary.get('externalSupabaseCollectionPacketDeclaredQueryCount')};"
        f"collectionPacketQueryCountMismatch={summary.get('externalSupabaseCollectionPacketQueryCountMismatch')};"
        f"collectionPacketNextActions={summary.get('externalSupabaseCollectionPacketNextActions')};"
        f"advisorStatus={advisor_status};"
        f"advisorRowCount={advisor_row_count};"
        f"advisorErrorCount={advisor_error_count};"
        f"advisorWarningCount={advisor_warning_count};"
        f"advisorTool={advisor_tool};"
        f"advisorFeatureGroup={advisor_feature_group};"
        f"defaultHostedAuthMode={summary.get('externalSupabaseDefaultHostedAuthMode')};"
        f"supportedAuthModes={summary.get('externalSupabaseSupportedAuthModes')};"
        f"mcpOAuthRequired={summary.get('externalSupabaseMcpOAuthRequired')};"
        f"projectScopeStatus={project_scope_status};"
        f"projectRefPresent={project_ref_present};"
        f"authRequired={summary.get('externalSupabaseAuthRequired')};"
        f"cliMissing={summary.get('externalSupabaseCliMissing')};"
        f"missingResultCount={missing_result_count};"
        f"unexpectedResultCount={unexpected_result_count};"
        f"duplicateResultCount={duplicate_result_count};"
        f"riskFindingCount={risk_finding_count};"
        f"readOnly={summary.get('externalSupabaseReadOnly')};"
        f"mutationAllowed={summary.get('externalSupabaseMutationAllowedValue')};"
        f"rawSecretPatternHits={summary.get('rawSecretPatternHits', 0)}"
    )
    return requirement_row("supabase-live-db-structure-proof", status, evidence, evidence_needed)


def phase2_int_metric(pattern: str, text: str) -> int | None:
    match = re.search(pattern, text, re.M)
    if not match:
        return None
    try:
        return int(match.group(1))
    except (TypeError, ValueError):
        return None


def phase2_local_gate_summary(root: Path) -> dict[str, Any]:
    reports_root = root / "build" / "desktop" / "reports"
    hygiene_path = reports_root / "source-set-hygiene" / "source-set-hygiene.md"
    source_score_path = reports_root / "source-score-live.txt"
    harmony_path = reports_root / "harmony-score-live.txt"
    hygiene_text = read_text(hygiene_path)
    source_score_text = read_text(source_score_path)
    harmony_text = read_text(harmony_path)

    source_score_match = re.search(r"=== Total Score:\s*(\d+)\s*/\s*(\d+)\s*===", source_score_text)
    source_score = int(source_score_match.group(1)) if source_score_match else None
    source_score_max = int(source_score_match.group(2)) if source_score_match else None
    active_legacy_signals = phase2_int_metric(r"activeLegacySignalCount:\s*(\d+)", hygiene_text)
    duplicate_fqcn = phase2_int_metric(r"duplicateFqcnCount:\s*(\d+)", hygiene_text)
    package_path_mismatch = phase2_int_metric(r"packagePathMismatchCount:\s*(\d+)", hygiene_text)
    secret_hits = phase2_int_metric(r"\[harmony\]\[security\]\s*secretPatternHits=(\d+)", harmony_text)
    exact_empty_catches = phase2_int_metric(r"\[score\]\[silent-catch\]\s*exactEmptyCatchMatches=(\d+)", source_score_text)
    hb_done = len(set(re.findall(r"\[harmony\]\[(HB-\d{2})\]\s+DONE", harmony_text)))
    silent_catch_ok = "[score][silent-catch] OK" in source_score_text

    evidence_needed: list[str] = []
    missing_reports = [
        name
        for name, text in (
            ("source-set-hygiene.md", hygiene_text),
            ("source-score-live.txt", source_score_text),
            ("harmony-score-live.txt", harmony_text),
        )
        if not text
    ]
    if missing_reports:
        evidence_needed.append(
            "Phase2 local Gradle reports missing / run "
            ".\\gradlew.bat checkSourceSetHygiene sourceScoreReport harmonyScoreReport "
            "--no-daemon --project-cache-dir <desktop-cache>"
        )
    if source_score is None or source_score_max is None:
        evidence_needed.append("Phase2 sourceScore metric missing from build/desktop/reports/source-score-live.txt")
    elif source_score < source_score_max:
        evidence_needed.append(f"Phase2 sourceScore below full score / observed {source_score}/{source_score_max}")
    if active_legacy_signals is None:
        evidence_needed.append("Phase2 activeLegacySignalCount missing from source-set-hygiene report")
    elif active_legacy_signals > PHASE2_ACTIVE_LEGACY_SIGNAL_TARGET:
        evidence_needed.append(
            f"Phase2 activeLegacySignals above target / observed {active_legacy_signals} "
            f"target {PHASE2_ACTIVE_LEGACY_SIGNAL_TARGET}"
        )
    if hb_done < PHASE2_HARMONY_HB_TARGET:
        evidence_needed.append(
            f"Phase2 harmony HB DONE count below target / observed {hb_done}/{PHASE2_HARMONY_HB_TARGET}"
        )
    if not silent_catch_ok:
        evidence_needed.append("Phase2 silent-catch source-score gate missing OK marker")
    if exact_empty_catches is None:
        evidence_needed.append("Phase2 exactEmptyCatchMatches metric missing from source-score report")
    elif exact_empty_catches != 0:
        evidence_needed.append(f"Phase2 exactEmptyCatchMatches not zero / observed {exact_empty_catches}")
    if duplicate_fqcn is None:
        evidence_needed.append("Phase2 duplicateFqcnCount missing from source-set-hygiene report")
    elif duplicate_fqcn != 0:
        evidence_needed.append(f"Phase2 duplicateFqcn not zero / observed {duplicate_fqcn}")
    if package_path_mismatch is None:
        evidence_needed.append("Phase2 packagePathMismatchCount missing from source-set-hygiene report")
    elif package_path_mismatch != 0:
        evidence_needed.append(f"Phase2 packagePathMismatch not zero / observed {package_path_mismatch}")
    if secret_hits is None:
        evidence_needed.append("Phase2 secretPatternHits missing from harmony report")
    elif secret_hits != 0:
        evidence_needed.append(f"Phase2 secretPatternHits not zero / observed {secret_hits}")

    evidence = (
        f"sourceScore={source_score}/{source_score_max};"
        f"activeLegacySignals={active_legacy_signals};"
        f"activeLegacySignalsTarget={PHASE2_ACTIVE_LEGACY_SIGNAL_TARGET};"
        f"harmonyHbDone={hb_done}/{PHASE2_HARMONY_HB_TARGET};"
        f"silentCatchOk={silent_catch_ok};"
        f"exactEmptyCatchMatches={exact_empty_catches};"
        f"duplicateFqcn={duplicate_fqcn};"
        f"packagePathMismatch={package_path_mismatch};"
        f"secretPatternHits={secret_hits};"
        "reportRoot=build/desktop/reports"
    )
    return {
        "valid": not evidence_needed,
        "evidence": evidence,
        "evidenceNeeded": evidence_needed,
    }


def requirement_row(
    requirement_id: str,
    status: str,
    evidence: str,
    evidence_needed: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "id": requirement_id,
        "status": status,
        "evidence": evidence,
        "evidenceNeeded": evidence_needed or [],
    }


def external_role_proof_requirement(
    role: str,
    external_valid_roles: set[str],
    external_handoff_valid_roles: set[str],
    external_bundle_valid_roles: set[str],
) -> dict[str, Any]:
    node_ok = role in external_valid_roles
    handoff_ok = role in external_handoff_valid_roles
    bundle_ok = role in external_bundle_valid_roles
    node_smoke_name = f"{role}-node-smoke.json"
    handoff_name = f"{role}-producer-handoff.json"
    desktop_evidence_path = f"data/agent-handoff/mcp-control-tower/{node_smoke_name}"
    patchdrop_proof_dir = "__patch_drop__/external-node-proof"
    required_isolation = "guard=PASS,sourceRootKind=local-worktree,directCanonicalSourceEdit=false"
    evidence_needed: list[str] = []
    if not node_ok:
        evidence_needed.append(
            f"external {role} node smoke output missing / run scripts/awx_mcp_node_smoke.py "
            f"from the {role} producer-local worktree and copy {node_smoke_name} into "
            f"data/agent-handoff/mcp-control-tower or {patchdrop_proof_dir}; "
            f"required sourceIsolation.guard=PASS sourceRootKind=local-worktree "
            "directCanonicalSourceEdit=false"
        )
    if not handoff_ok:
        evidence_needed.append(
            f"external {role} producer handoff JSON missing / run scripts/awx_mcp_producer_handoff.py "
            f"from the {role} producer-local worktree and copy {handoff_name} into "
            f"data/agent-handoff/mcp-control-tower or {patchdrop_proof_dir}; "
            "required desktopFinalProof=evidence_needed and rawSecretPatternHits=0"
        )
    if not bundle_ok:
        evidence_needed.append(
            f"external {role} PatchDrop v3 sidecars missing / submit .patch, .report.md, "
            ".verify.log, .sha256.txt, .manifest.json, and pendingNotice through PatchDrop; "
            "manifest/sourceIsolation.guard=PASS sourceRootKind=local-worktree "
            "directCanonicalSourceEdit=false"
        )
    return requirement_row(
        f"producer-external-proof-{role}",
        "satisfied" if node_ok and handoff_ok and bundle_ok else "evidence_needed",
        (
            f"role={role};nodeSmoke={node_ok};handoff={handoff_ok};bundle={bundle_ok};"
            f"desktopEvidencePath={desktop_evidence_path};"
            f"patchdropProofDir={patchdrop_proof_dir};"
            f"requiredSourceIsolation={required_isolation}"
        ),
        evidence_needed,
    )


def harmony_runtime_proof_artifact_path(root: Path) -> Path:
    return root / "data" / "agent-handoff" / "mcp-control-tower" / "harmony-runtime-proof.json"


def harmony_runtime_proof_artifact_summary(root: Path) -> dict[str, Any]:
    path = harmony_runtime_proof_artifact_path(root)
    summary: dict[str, Any] = {
        "path": path,
        "exists": path_exists(path),
        "valid": False,
        "evidence": f"runtimeProofArtifactPresent={path_exists(path)}",
        "evidenceNeeded": [
            f"{path.as_posix()} / run harmony_scan with runtime_probe=true against the local Spring runtime"
        ],
    }
    if not path_exists(path):
        return summary
    raw = read_text(path)
    raw_secret_hits = len(SECRET_PATTERN.findall(raw))
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        summary["evidence"] = "runtimeProofArtifactPresent=True;runtimeProofArtifactValid=False;reason=json-parse"
        return summary
    runtime_proof = data.get("runtimeProof") if isinstance(data.get("runtimeProof"), dict) else {}
    source = str(data.get("runtimeProofSource") or "")
    trace_ok = runtime_proof.get("traceStoreExportOk") is True
    agent_ok = runtime_proof.get("agentDbSnapshotOk") is True
    try:
        declared_secret_hits = int(data.get("rawSecretPatternHits", -1))
    except (TypeError, ValueError):
        declared_secret_hits = -1
    valid = (
        data.get("schemaVersion") == HARMONY_RUNTIME_PROOF_SCHEMA_VERSION
        and source == "live-runtime"
        and trace_ok
        and agent_ok
        and raw_secret_hits == 0
        and declared_secret_hits == 0
    )
    summary.update(
        {
            "valid": valid,
            "source": source,
            "traceStoreExportOk": trace_ok,
            "agentDbSnapshotOk": agent_ok,
            "rawSecretPatternHits": raw_secret_hits,
            "declaredSecretPatternHits": declared_secret_hits,
            "evidence": (
                f"runtimeProofArtifactPresent=True;"
                f"runtimeProofArtifactValid={valid};"
                f"runtimeProofSource={source};"
                f"traceStoreExportOk={trace_ok};"
                f"agentDbSnapshotOk={agent_ok};"
                f"rawSecretPatternHits={raw_secret_hits};"
                f"pathHash={stable_hash(path.as_posix())}"
            ),
            "evidenceNeeded": []
            if valid
            else [
                f"{path.as_posix()} / rerun harmony_scan with live runtime probe until traceStoreExportOk and agentDbSnapshotOk are true"
            ],
        }
    )
    return summary


def build_requirement_matrix(
    checked: list[dict[str, Any]],
    evidence_needed: list[str],
    archive_index: Path,
    external_valid_roles: set[str],
    external_handoff_valid_roles: set[str],
    external_bundle_valid_roles: set[str],
    root: Path | None = None,
    supabase_smoke_summary: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    external_complete = (
        external_valid_roles == {"macmini", "notebook"}
        and external_handoff_valid_roles == {"macmini", "notebook"}
        and external_bundle_valid_roles == {"macmini", "notebook"}
    )
    external_needed = [item for item in evidence_needed if "external" in item.lower() or "producer" in item.lower()]
    archive_needed = [item for item in evidence_needed if "BackupsXS" in item or "archive" in item.lower()]
    supabase_project_scope_needed = [
        item for item in evidence_needed if "Supabase MCP project_ref" in item
    ]
    supabase_project_scoped = "projectScopedMode=True" in check_evidence(checked, "supabase.mcp-scope")
    proof_root = root or (archive_index.parent.parent if archive_index.parent.name == "BackupsXS" else Path("."))
    harmony_artifact = harmony_runtime_proof_artifact_summary(proof_root)
    phase2_local_gates = phase2_local_gate_summary(proof_root)
    db_gap_summary = db_gap_report_artifact_summary(proof_root)
    source_health_summary = source_health_scorecard_artifact_summary(proof_root)
    websoak_provider_summary = websoak_provider_disabled_artifact_summary(proof_root)
    return [
        requirement_row(
            "desktop-source-owner-final-verifier",
            "satisfied" if checks_ok(checked, "roles.desktop-macmini-notebook", "desktop.control-loop", "desktop.dispatch-artifacts") else "incomplete",
            check_evidence(checked, "roles.desktop-macmini-notebook", "desktop.control-loop", "desktop.dispatch-artifacts"),
        ),
        requirement_row(
            "macmini-readonly-patch-producer",
            "satisfied" if checks_ok(checked, "producer.canonical-source-guard", "producer.command-plan-dispatch", "mcp.node-setup-runner", "mcp.node-smoke-runner") else "incomplete",
            check_evidence(checked, "producer.canonical-source-guard", "producer.command-plan-dispatch", "mcp.node-setup-runner", "mcp.node-smoke-runner"),
        ),
        requirement_row(
            "notebook-support-reviewer",
            "satisfied" if checks_ok(checked, "notebook.support-reviewer-prompt", "roles.desktop-macmini-notebook") else "incomplete",
            check_evidence(checked, "notebook.support-reviewer-prompt", "roles.desktop-macmini-notebook"),
        ),
        requirement_row(
            "mcp-tools-resources-prompts",
            "satisfied" if checks_ok(checked, "tools.required-json-schemas", "resources.runners", "mcp.stdio-bridge", "mcp.prompt-get-role-briefs") else "incomplete",
            check_evidence(checked, "tools.required-json-schemas", "resources.runners", "mcp.stdio-bridge", "mcp.prompt-get-role-briefs"),
        ),
        requirement_row(
            "agent-db-snapshot-contract",
            "satisfied" if checks_ok(checked, "agent.db-snapshot") else "incomplete",
            check_evidence(checked, "agent.db-snapshot"),
        ),
        requirement_row(
            "trace-snapshot-probe-contract",
            "satisfied" if checks_ok(checked, "trace.snapshot-probe") else "incomplete",
            check_evidence(checked, "trace.snapshot-probe"),
        ),
        requirement_row(
            "harmony-runtime-proof-contract",
            "satisfied" if checks_ok(checked, "harmony.runtime-proof-probe") else "incomplete",
            check_evidence(checked, "harmony.runtime-proof-probe"),
        ),
        requirement_row(
            "harmony-runtime-proof-live",
            "satisfied" if harmony_artifact["valid"] else "evidence_needed",
            harmony_artifact["evidence"],
            harmony_artifact["evidenceNeeded"],
        ),
        requirement_row(
            "phase2-local-hard-gates",
            "satisfied" if phase2_local_gates["valid"] else "evidence_needed",
            phase2_local_gates["evidence"],
            phase2_local_gates["evidenceNeeded"],
        ),
        requirement_row(
            "boot-verifier-cache-isolation",
            "satisfied" if checks_ok(checked, "verify.boot-cache-isolation") else "incomplete",
            check_evidence(checked, "verify.boot-cache-isolation"),
        ),
        requirement_row(
            "runtime-provider-websoak-proof",
            "satisfied" if websoak_provider_disabled_ready(websoak_provider_summary) else "incomplete",
            (
                f"status={websoak_provider_summary.get('status')};"
                f"providerDisabledOrSkipped={websoak_provider_summary.get('providerDisabledOrSkipped')};"
                f"providerDisabledCount={websoak_provider_summary.get('providerDisabledCount')};"
                f"outCount={websoak_provider_summary.get('outCount')};"
                f"rawInputCount={websoak_provider_summary.get('rawInputCount')};"
                f"starvationFallback.trigger={websoak_provider_summary.get('starvationTrigger')};"
                f"secretPatternHits={websoak_provider_summary.get('secretPatternHits')};"
                f"rawQueryHits={websoak_provider_summary.get('rawQueryHits')};"
                f"probeKeyHits={websoak_provider_summary.get('probeKeyHits')};"
                f"rawSecretPatternHits={websoak_provider_summary.get('rawSecretPatternHits')}"
            ),
            [
                (
                    "WebSoak provider-disabled runtime proof missing or unsafe / run "
                    "scripts\\smoke_websoak_kpi_provider_disabled.ps1"
                )
            ]
            if not websoak_provider_disabled_ready(websoak_provider_summary)
            else [],
        ),
        requirement_row(
            "supabase-readonly-db-probe",
            (
                "satisfied"
                if checks_ok(
                    checked,
                    "supabase.mcp-config",
                    "supabase.mcp-scope",
                    "supabase.schema-snapshot-plan",
                    "supabase.schema-snapshot-tool",
                    "supabase.schema-snapshot-import-tool",
                    "supabase.schema-snapshot-artifact",
                    "supabase.readonly-snapshot-smoke",
                )
                else "incomplete"
            ),
            check_evidence(
                checked,
                "supabase.mcp-config",
                "supabase.mcp-scope",
                "supabase.schema-snapshot-plan",
                "supabase.schema-snapshot-tool",
                "supabase.schema-snapshot-import-tool",
                "supabase.schema-snapshot-artifact",
                "supabase.readonly-snapshot-smoke",
            ),
        ),
        requirement_row(
            "supabase-project-scope",
            "satisfied" if supabase_project_scoped else "evidence_needed",
            check_evidence(checked, "supabase.mcp-scope"),
            supabase_project_scope_needed,
        ),
        supabase_live_db_structure_requirement(db_gap_summary, supabase_smoke_summary),
        requirement_row(
            "db-structure-gap-audit",
            "satisfied" if checks_ok(checked, "db.structure-gap-report") else "incomplete",
            check_evidence(checked, "db.structure-gap-report"),
        ),
        requirement_row(
            "source-health-scorecard-contract",
            "satisfied" if source_health_scorecard_ready(source_health_summary) else "incomplete",
            check_evidence(checked, "source.health-scorecard"),
        ),
        requirement_row(
            "goal-next-auto-command-packet",
            "satisfied" if checks_ok(checked, "goal-next.command-packet") else "incomplete",
            check_evidence(checked, "goal-next.command-packet"),
        ),
        requirement_row(
            "archive-search-two-pass-index",
            "satisfied" if archive_index.exists() and checks_ok(checked, "archive.index-path-resolution") else "evidence_needed",
            check_evidence(checked, "archive.index-path-resolution"),
            archive_needed,
        ),
        requirement_row(
            "archive-restore-audit-checksum",
            "satisfied" if checks_ok(checked, "archive.restore-pre-review-checksum") else "incomplete",
            check_evidence(checked, "archive.restore-pre-review-checksum"),
        ),
        requirement_row(
            "producer-external-proof",
            "satisfied" if external_complete else "evidence_needed",
            "nodeSmokeRoles="
            + ",".join(sorted(external_valid_roles))
            + ";handoffRoles="
            + ",".join(sorted(external_handoff_valid_roles))
            + ";bundleRoles="
            + ",".join(sorted(external_bundle_valid_roles)),
            external_needed,
        ),
        *[
            external_role_proof_requirement(
                role,
                external_valid_roles,
                external_handoff_valid_roles,
                external_bundle_valid_roles,
            )
            for role in EXTERNAL_PRODUCER_ROLES
        ],
        requirement_row(
            "secret-safe-audit-log",
            "satisfied" if checks_ok(checked, "audit.redacted-fields", "toolbox.generic-audit-log", "secrets.no-raw-token-patterns") else "incomplete",
            check_evidence(checked, "audit.redacted-fields", "toolbox.generic-audit-log", "secrets.no-raw-token-patterns"),
        ),
        requirement_row(
            "nextjs-app-router-only",
            "satisfied" if checks_ok(checked, "nextjs.no-pages-api") else "incomplete",
            check_evidence(checked, "nextjs.no-pages-api"),
        ),
        requirement_row(
            "java-stack-purity",
            "satisfied" if checks_ok(checked, "java.langchain4j-1.0.1", "java.spring-boot3-java17") else "incomplete",
            check_evidence(checked, "java.langchain4j-1.0.1", "java.spring-boot3-java17"),
        ),
        requirement_row(
            "python-external-tooling-layer",
            "satisfied" if checks_ok(checked, "tools.required-json-schemas", "pipeline.mcp-control-tower-runner") else "incomplete",
            check_evidence(checked, "tools.required-json-schemas", "pipeline.mcp-control-tower-runner"),
        ),
        requirement_row(
            "mdc-trace-continuity",
            "satisfied" if checks_ok(checked, "java.mdc-trace-session") else "incomplete",
            check_evidence(checked, "java.mdc-trace-session"),
        ),
        requirement_row(
            "janitor-regression-probes",
            "satisfied" if checks_ok(checked, "janitor.regression-tests") else "incomplete",
            check_evidence(checked, "janitor.regression-tests"),
        ),
        requirement_row(
            "three-node-local-smoke",
            "satisfied" if checks_ok(checked, "three-node.local-smoke") else "incomplete",
            check_evidence(checked, "three-node.local-smoke"),
        ),
        requirement_row(
            "safe-delete-readonly-gate",
            "satisfied" if checks_ok(checked, "safe-delete.path-presence") else "incomplete",
            check_evidence(checked, "safe-delete.path-presence"),
        ),
        requirement_row(
            "source-governance-stability-proof",
            "satisfied" if checks_ok(checked, "source-governance-stability-proof") else "incomplete",
            check_evidence(checked, "source-governance-stability-proof"),
        ),
    ]


def completion_audit_next_actions(
    requirements: list[dict[str, Any]],
    evidence_needed: list[str],
) -> list[str]:
    actions: list[str] = []
    requirement_by_id = {str(row.get("id") or ""): row for row in requirements}
    requirement_evidence_needed = [
        str(item)
        for row in requirements
        for item in row.get("evidenceNeeded", [])
        if str(item).strip()
    ]
    evidence_text = " ".join(evidence_needed + requirement_evidence_needed).lower()

    def add(action: str) -> None:
        if action and action not in actions:
            actions.append(action)

    archive_status = str(requirement_by_id.get("archive-search-two-pass-index", {}).get("status") or "")
    if archive_status == "evidence_needed" or "backupsxs" in evidence_text or "archive" in evidence_text:
        add("create_or_point_archive_index")
        add("run_archive_index_build")
        add("set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT")
        add("verify_archive_index_path")
        add("rerun_archive_search_with_index_path")
        add("rerun_archive_search")

    supabase_status = str(requirement_by_id.get("supabase-project-scope", {}).get("status") or "")
    if supabase_status == "evidence_needed" or "supabase mcp project_ref" in evidence_text:
        supabase_scope_evidence = str(requirement_by_id.get("supabase-project-scope", {}).get("evidence") or "")
        if "projectRefTemplateMode=True" in supabase_scope_evidence:
            add("set_SUPABASE_PROJECT_REF")
        else:
            add("set_project_ref_in_mcp_config")
        add("complete_supabase_mcp_oauth_flow")
        add("install_supabase_cli_or_use_mcp_execute_sql")
        add("run_supabase_cli_help_discovery")
        add("link_supabase_cli_project_ref")
        add("authenticate_supabase_mcp_or_cli")
        add("run_supabase_readonly_snapshot_smoke")
        add("run_supabase_context_probe")
        add("run_supabase_schema_snapshot")
        add("run_supabase_readonly_sql_bundle")
        add("run_supabase_get_advisors_readonly")
        add("import_supabase_query_results")
        add("populate_supabase_query_results_file")
        add("rerun_supabase_schema_snapshot_import")
        add("rerun_db_gap_scanner")

    supabase_live_status = str(requirement_by_id.get("supabase-live-db-structure-proof", {}).get("status") or "")
    if (
        supabase_live_status == "evidence_needed"
        or "supabase live schema snapshot" in evidence_text
        or "supabase execute_sql result sets missing" in evidence_text
        or "get_advisors" in evidence_text
        or "supabase advisor" in evidence_text
    ):
        add("run_supabase_readonly_sql_bundle")
        supabase_live_evidence = str(
            requirement_by_id.get("supabase-live-db-structure-proof", {}).get("evidence") or ""
        )
        packet_actions_match = re.search(
            r"(?:^|;)collectionPacketNextActions=([^;]+)",
            supabase_live_evidence,
        )
        if packet_actions_match:
            for action in packet_actions_match.group(1).split(","):
                action = action.strip()
                if re.fullmatch(r"[A-Za-z0-9_:-]+", action):
                    add(action)
        add("run_supabase_get_advisors_readonly")
        add("import_supabase_query_results")
        add("populate_supabase_query_results_file")
        add("rerun_supabase_schema_snapshot_import")
        add("rerun_db_gap_scanner")

    harmony_status = str(requirement_by_id.get("harmony-runtime-proof-contract", {}).get("status") or "")
    harmony_evidence = str(requirement_by_id.get("harmony-runtime-proof-contract", {}).get("evidence") or "")
    harmony_live_status = str(requirement_by_id.get("harmony-runtime-proof-live", {}).get("status") or "")
    if (
        harmony_status == "satisfied"
        and harmony_live_status != "satisfied"
        and "harmony.runtime-proof-probe" in harmony_evidence
    ):
        add("start_spring_runtime_for_harmony_probe")
        add("set_AGENT_DB_CONTEXT_ENABLED_true")
        add("set_AWX_AGENT_DB_CONTEXT_BASE_URL")
        add("set_AWX_TRACE_SNAPSHOT_BASE_URL")
        add("rerun_harmony_scan_with_runtime_probe")

    producer_status = str(requirement_by_id.get("producer-external-proof", {}).get("status") or "")
    if producer_status == "evidence_needed" or "external" in evidence_text or "producer" in evidence_text:
        if "producer source root" in evidence_text or "producer_roots" in evidence_text:
            add("verify_or_override_producer_roots")
        for role in EXTERNAL_PRODUCER_ROLES:
            role_requirement = requirement_by_id.get(f"producer-external-proof-{role}", {})
            role_status = str(role_requirement.get("status") or "")
            if role_status != "evidence_needed":
                continue
            role_text = " ".join(
                [
                    str(role_requirement.get("evidence") or ""),
                    " ".join(str(item) for item in role_requirement.get("evidenceNeeded", []) if str(item).strip()),
                ]
            ).lower()
            if "nodesmoke=false" in role_text or "node smoke output missing" in role_text:
                add(f"run_{role}_external_node_smoke")
                add(f"copy_{role}_node_smoke_json_to_desktop_evidence_path")
            if "handoff=false" in role_text or "producer handoff json missing" in role_text:
                add(f"collect_{role}_producer_handoff_json")
                add(f"copy_{role}_producer_handoff_json_to_desktop_evidence_path")
            if "bundle=false" in role_text or "patchdrop v3 sidecars missing" in role_text:
                add(f"submit_{role}_patchdrop_v3_bundle_sidecars")
        add("run_external_node_smoke_on_producer_hosts")
        add("collect_producer_handoff_json")
        add("submit_patchdrop_v3_bundle_sidecars")
        add("run_external_evidence_intake")
        add("run_external_evidence_audit")

    if actions:
        add("rerun_completion_audit")
    return actions


def supabase_live_proof_next_action(root: Path, completion_actions: list[str]) -> dict[str, Any]:
    artifact_paths = [
        "data/db-gap-report/supabase-execute-sql-collection.packet.json",
        "data/db-gap-report/supabase-query-results.template.json",
        "data/db-gap-report/supabase-readonly-snapshot.sql",
        "data/db-gap-report/supabase-schema-snapshot.json",
    ]
    packet = load_json(root / artifact_paths[0])
    queries = packet.get("queries") if isinstance(packet.get("queries"), list) else []
    required_result_names = [
        safe_scalar(query.get("name"), 120)
        for query in queries
        if isinstance(query, dict) and safe_scalar(query.get("name"), 120)
    ]
    packet_next_actions = [
        safe_scalar(action, 100)
        for action in (packet.get("nextActions") if isinstance(packet.get("nextActions"), list) else [])
        if safe_scalar(action, 100)
    ]
    next_actions = packet_next_actions or [
        "set_SUPABASE_PROJECT_REF",
        "authenticate_supabase_mcp_or_cli",
        "execute_each_query_once",
        "collect_get_advisors_rows",
        "run_supabase_schema_snapshot_import",
        "rerun_db_gap_scanner",
    ]
    if "rerun_completion_audit" in completion_actions and "rerun_completion_audit" not in next_actions:
        next_actions.append("rerun_completion_audit")
    return {
        "action": "collect-supabase-live-proof",
        "nodeRole": "desktop",
        "targetService": "supabase",
        "readOnly": True,
        "mutationAllowed": False,
        "mcpEndpointTemplate": (
            "https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}"
            "&read_only=true&features=database,debugging,docs"
        ),
        "requiredEnv": [
            {"name": "SUPABASE_PROJECT_REF", "sensitive": False},
            {"name": "SUPABASE_ACCESS_TOKEN", "sensitive": True},
        ],
        "requiredMcpTools": ["execute_sql", "get_advisors"],
        "artifactPaths": artifact_paths,
        "queryCount": bounded_int(packet.get("queryCount"), len(required_result_names), 0, 100),
        "requiredResultNames": required_result_names,
        "resultPathRecommendation": safe_scalar(
            packet.get("resultPathRecommendation") or "data/db-gap-report/supabase-query-results.json",
            240,
        ),
        "advisorResultPathRecommendation": "data/db-gap-report/supabase-advisors.json",
        "importTool": safe_scalar(packet.get("importTool") or "supabase_schema_snapshot_import", 100),
        "docsRefs": [
            "https://supabase.com/docs/guides/ai-tools/mcp",
            "https://supabase.com/docs/guides/api/securing-your-api",
            "https://supabase.com/docs/guides/security/product-security",
            "https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically",
        ],
        "nextActions": next_actions,
        "decision": "evidence_needed",
    }


ARCHIVE_INDEX_NEXT_ACTIONS = {
    "create_or_point_archive_index",
    "run_archive_index_build",
    "set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT",
    "verify_archive_index_path",
    "rerun_archive_search_with_index_path",
    "rerun_archive_search",
}


def archive_index_proof_next_action(completion_actions: list[str]) -> dict[str, Any]:
    next_actions = [action for action in completion_actions if action in ARCHIVE_INDEX_NEXT_ACTIONS]
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


def external_producer_proof_next_action(root: Path, role: str, topic: str = "mcp-control-loop") -> dict[str, Any]:
    role_slug = slug(role)
    topic_slug = slug(topic)
    evidence_dir = root / "data" / "agent-handoff" / "mcp-control-tower"
    proof_dir = root / "__patch_drop__" / "external-node-proof"
    return {
        "action": "collect-external-evidence-files",
        "nodeRole": "desktop",
        "targetRole": role_slug,
        "topic": topic_slug,
        "desktopEvidencePaths": {
            "nodeSmoke": str(evidence_dir / f"{role_slug}-node-smoke.json"),
            "producerHandoff": str(evidence_dir / f"{role_slug}-producer-handoff.json"),
        },
        "patchdropProofDir": str(proof_dir),
        "requiredSidecars": list(PATCHDROP_HANDOFF_REQUIRED_ARTIFACTS),
        "requiredSourceIsolation": {
            "sourceIsolation.guard": "PASS",
            "sourceRootKind": "local-worktree",
            "directCanonicalSourceEdit": False,
            "desktopFinalProof": "evidence_needed",
            "rawSecretPatternHits": 0,
        },
        "applyCollectedEvidenceCommand": (
            "powershell -NoProfile -ExecutionPolicy Bypass -File "
            f"scripts\\external_apply_collected_evidence.ps1 -Root . -Topic {topic_slug}"
        ),
        "producerCommands": [
            (
                "python scripts/awx_mcp_node_smoke.py "
                f"--root <producer-local-worktree> --canonical-root {root} --node-role {role_slug}"
            ),
            (
                "python scripts/awx_mcp_producer_handoff.py "
                f"--source-root <producer-local-worktree> --canonical-root {root} "
                "--patchdrop-root <PatchDrop> --producer-script <PatchDrop>\\producer_bundle.py "
                f"--node-role {role_slug} --topic {topic_slug} --pathspec <relative/source/path>"
            ),
        ],
        "decision": "evidence_needed",
    }


def source_contract_next_action_details(root: Path) -> list[dict[str, Any]]:
    scorecard = load_json(root / "verification" / "source-health-scorecard.json")
    source_details = scorecard.get("nextSourceActionDetails")
    if not isinstance(source_details, list):
        return []
    windows_abs_re = re.compile(r"(?i)\b[a-z]:[\\/]")

    def safe_list(value: Any, limit: int, max_items: int) -> list[str]:
        if not isinstance(value, list):
            return []
        safe_values: list[str] = []
        for item in value:
            text = safe_scalar(item, limit)
            if not text or windows_abs_re.search(text) or SECRET_PATTERN.search(text):
                continue
            safe_values.append(text)
            if len(safe_values) >= max_items:
                break
        return safe_values

    details: list[dict[str, Any]] = []
    for row in source_details:
        if not isinstance(row, dict):
            continue
        action_name = safe_scalar(row.get("action"), 100)
        if action_name != "run-cross-subsystem-contract-tests":
            continue
        rendered = json.dumps(row, ensure_ascii=True, sort_keys=True)
        if windows_abs_re.search(rendered) or SECRET_PATTERN.search(rendered):
            continue
        details.append({
            "action": action_name,
            "nodeRole": "desktop",
            "scope": safe_scalar(row.get("scope") or "active_source", 80),
            "readOnly": row.get("readOnly") is True,
            "mutationAllowed": row.get("mutationAllowed") is True,
            "targetFiles": safe_list(row.get("targetFiles"), 240, 12),
            "affectedSubsystems": safe_list(row.get("affectedSubsystems"), 80, 12),
            "focusedTests": safe_list(row.get("focusedTests"), 180, 12),
            "commands": safe_list(row.get("commands"), 300, 12),
            "requiredTraceKeys": safe_list(row.get("requiredTraceKeys"), 120, 20),
            "decision": safe_scalar(row.get("decision") or "local_contract_ready", 80),
        })
    return details


def completion_audit_next_action_details(root: Path, next_actions: list[str]) -> list[dict[str, Any]]:
    details: list[dict[str, Any]] = source_contract_next_action_details(root)
    supabase_actions = {
        "set_project_ref_in_mcp_config",
        "complete_supabase_mcp_oauth_flow",
        "install_supabase_cli_or_use_mcp_execute_sql",
        "link_supabase_cli_project_ref",
        "authenticate_supabase_mcp_or_cli",
        "run_supabase_readonly_snapshot_smoke",
        "run_supabase_context_probe",
        "run_supabase_schema_snapshot",
        "run_supabase_readonly_sql_bundle",
        "run_supabase_get_advisors_readonly",
        "import_supabase_query_results",
        "populate_supabase_query_results_file",
        "rerun_supabase_schema_snapshot_import",
        "set_SUPABASE_PROJECT_REF",
        "execute_each_query_once",
        "collect_get_advisors_rows",
    }
    if any(action in supabase_actions for action in next_actions):
        details.append(supabase_live_proof_next_action(root, next_actions))
    if any(action in ARCHIVE_INDEX_NEXT_ACTIONS for action in next_actions):
        details.append(archive_index_proof_next_action(next_actions))
    for role in EXTERNAL_PRODUCER_ROLES:
        role_actions = {
            f"run_{role}_external_node_smoke",
            f"copy_{role}_node_smoke_json_to_desktop_evidence_path",
            f"collect_{role}_producer_handoff_json",
            f"copy_{role}_producer_handoff_json_to_desktop_evidence_path",
            f"submit_{role}_patchdrop_v3_bundle_sidecars",
        }
        if any(action in role_actions for action in next_actions):
            details.append(external_producer_proof_next_action(root, role))
    return details


def walk_paths(root: Path) -> list[Path]:
    found: list[Path] = []
    for current, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith("build-")]
        current_path = Path(current)
        for name in files:
            found.append(current_path / name)
    return found


def path_has_pages_api(path: Path) -> bool:
    parts = [p.lower() for p in path.parts]
    return any(parts[i] == "pages" and i + 1 < len(parts) and parts[i + 1] == "api" for i in range(len(parts) - 1))


def build_text(root: Path) -> str:
    candidates = [
        root / "build.gradle",
        root / "build.gradle.kts",
        root / "settings.gradle",
        root / "settings.gradle.kts",
        root / "app" / "build.gradle",
        root / "app" / "build.gradle.kts",
        root / "gradle" / "libs.versions.toml",
    ]
    return "\n".join(read_text(p) for p in candidates if path_exists(p))


def archive_index_candidate(root: Path) -> tuple[Path, str]:
    archive_index = os.environ.get("ARCHIVE_INDEX", "").strip()
    if archive_index:
        return Path(archive_index), "env.ARCHIVE_INDEX"
    nas_archive_root = os.environ.get("NAS_ARCHIVE_ROOT", "").strip()
    if nas_archive_root:
        return Path(nas_archive_root) / "index.jsonl", "env.NAS_ARCHIVE_ROOT"
    return root / "BackupsXS" / "index.jsonl", "default.BackupsXS"


def langchain4j_versions(text: str) -> list[str]:
    versions = re.findall(r"dev\.langchain4j:[^:\"')]+:([0-9A-Za-z_.-]+)", text)
    versions += re.findall(r"dev\.langchain4j[^=:\n]*[=:]\s*[\"']([0-9A-Za-z_.-]+)[\"']", text)
    return sorted(set(versions))


def count_secret_patterns(root: Path) -> int:
    targets = [
        root / "scripts" / "awx_mcp_toolbox.py",
        root / "scripts" / "awx_mcp_node_smoke.py",
        root / "scripts" / "awx_mcp_producer_handoff.py",
        root / "scripts" / "awx_mcp_completion_audit.py",
        root / "scripts" / "awx_mcp_control_tower_pipeline.py",
        root / "scripts" / "awx_mcp_stdio_server.py",
        root / "scripts" / "awx_mcp_node_setup.py",
        root / "main" / "resources" / "mcp" / "awx-control-tower-tools.json",
        root / ".agents" / "skills" / "demo1-mcp-control-tower" / "SKILL.md",
        root / "data" / "agent-handoff" / "mcp-control-tower" / "skills" / "demo1-mcp-control-tower" / "SKILL.md",
        root / "agent-prompts" / "agents" / "demo1_mcp_control_tower" / "system.md",
        root / "data" / "agent-handoff" / "mcp-control-tower" / "demo1_mcp_control_tower.prompt",
        root / "AGENTS.md",
    ]
    total = 0
    for path in targets:
        if path_exists(path):
            total += len(SECRET_PATTERN.findall(read_text(path)))
    return total


def find_external_node_smoke_proof(proof_dir: Path, role: str) -> Path | None:
    if not path_exists(proof_dir):
        return None
    candidates = [
        proof_dir / f"{role}-node-smoke.json",
        proof_dir / f"{role}_node_smoke.json",
        proof_dir / f"{role}.node-smoke.json",
        proof_dir / role / "node-smoke.json",
    ]
    for candidate in candidates:
        if path_is_file(candidate):
            return candidate
    matches = path_glob(proof_dir, f"*{role}*smoke*.json")
    return matches[0] if matches else None


def find_external_producer_handoff_proof(proof_dir: Path, role: str) -> Path | None:
    if not path_exists(proof_dir):
        return None
    candidates = [
        proof_dir / f"{role}-producer-handoff.json",
        proof_dir / f"{role}_producer_handoff.json",
        proof_dir / f"{role}.producer-handoff.json",
        proof_dir / role / "producer-handoff.json",
    ]
    for candidate in candidates:
        if path_is_file(candidate):
            return candidate
    matches = path_glob(proof_dir, f"*{role}*producer*handoff*.json")
    return matches[0] if matches else None


def latest_dispatch_topic(root: Path) -> str:
    dispatch_dir = root / "__patch_drop__" / "dispatch"
    if not dispatch_dir.exists():
        return "mcp-stdio-bridge-verification"
    packets = sorted(
        (path for path in dispatch_dir.glob("*-desktop-dispatch.json") if path.is_file()),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    packets = filter_dispatch_packets(packets)
    if not packets:
        return "mcp-stdio-bridge-verification"
    return packets[0].name.removesuffix("-desktop-dispatch.json")


def latest_dispatch_packet_for_role(root: Path, role: str) -> dict[str, Any]:
    dispatch_dir = root / "__patch_drop__" / "dispatch"
    if not dispatch_dir.exists():
        return {}
    packets = sorted(
        (path for path in dispatch_dir.glob("*-desktop-dispatch.json") if path.is_file()),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    packets = filter_dispatch_packets(packets)
    for packet_path in packets:
        packet = load_json(packet_path)
        if not isinstance(packet, dict):
            continue
        for item in packet.get("packets", []):
            if isinstance(item, dict) and str(item.get("nodeRole") or "").lower() == role:
                return item
    return {}


def include_test_dispatch_packets() -> bool:
    return os.environ.get(INCLUDE_TEST_DISPATCH_ENV, "").strip().lower() in {"1", "true", "yes", "on"}


def is_test_dispatch_packet(path: Path) -> bool:
    return path.name.startswith(TEST_DISPATCH_PREFIX)


def is_ignored_dispatch_fixture(path: Path) -> bool:
    return path.name.startswith("janitor-test-ignored-dispatch-")


def filter_dispatch_packets(packets: list[Path]) -> list[Path]:
    if include_test_dispatch_packets():
        return packets
    without_ignored = [path for path in packets if not is_ignored_dispatch_fixture(path)]
    operational = [path for path in without_ignored if not is_test_dispatch_packet(path)]
    return without_ignored or operational or packets


FORBIDDEN_PATCH_PATH_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(^|/)(apikey\.txt|apikey\.ps1)$", re.IGNORECASE), "secret-setup"),
    (re.compile(r"(^|/)\.env($|[./])", re.IGNORECASE), "secret-env"),
    (re.compile(r"(^|/)pages/api/", re.IGNORECASE), "nextjs-pages-api"),
    (re.compile(r"(^|/)(\.gradle|build|node_modules|\.next|\.turbo|\.swc)(/|$)", re.IGNORECASE), "shared-cache-build-output"),
    (re.compile(r"\.(p12|jks)$", re.IGNORECASE), "keystore"),
)


def normalize_patch_path(value: str) -> str:
    path = value.strip().strip('"').replace("\\", "/")
    if path in {"", "/dev/null"}:
        return ""
    if path.startswith("a/") or path.startswith("b/"):
        path = path[2:]
    return path


def patch_target_paths(path: Path) -> list[str]:
    text = read_text(path).lstrip("\ufeff")
    paths: list[str] = []
    seen: set[str] = set()
    for line in text.splitlines():
        candidates: list[str] = []
        if line.startswith("diff --git "):
            parts = line.split()
            candidates.extend(parts[2:4])
        elif line.startswith("+++ ") or line.startswith("--- "):
            candidates.append(line[4:].split("\t", 1)[0])
        elif line.startswith("rename from ") or line.startswith("rename to "):
            candidates.append(line.split(" ", 2)[2])
        elif line.startswith("copy from ") or line.startswith("copy to "):
            candidates.append(line.split(" ", 2)[2])
        for candidate in candidates:
            normalized = normalize_patch_path(candidate)
            if normalized and normalized not in seen:
                seen.add(normalized)
                paths.append(normalized)
    return paths


def is_unsafe_patch_target(target: str) -> bool:
    parts = [part for part in target.replace("\\", "/").split("/") if part]
    return (
        ".." in parts
        or target.startswith("/")
        or target.startswith("//")
        or target.startswith("\\\\")
        or re.match(r"^[A-Za-z]:", target) is not None
    )


def forbidden_patch_paths(path: Path) -> list[dict[str, str]]:
    blocked: list[dict[str, str]] = []
    for target in patch_target_paths(path):
        if is_unsafe_patch_target(target):
            blocked.append({"path": target, "reason": "unsafe-path"})
            continue
        for pattern, reason in FORBIDDEN_PATCH_PATH_PATTERNS:
            if pattern.search(target):
                blocked.append({"path": target, "reason": reason})
                break
    return blocked


def diff_header_count(path: Path) -> int:
    return len(re.findall(r"(?m)^diff --git ", read_text(path).lstrip("\ufeff")))


def validate_external_producer_bundle(
    patchdrop_root: Path,
    role: str,
    topic: str,
    expected_source_root_hash: str = "",
) -> dict[str, Any]:
    role_slug = slug(role)
    topic_slug = slug(topic)
    bundle = f"{topic_slug}-{role_slug}-v3"
    node_dir = patchdrop_root / role_slug
    paths = {
        "patch": node_dir / f"{bundle}.patch",
        "report": node_dir / f"{bundle}.report.md",
        "verifyLog": node_dir / f"{bundle}.verify.log",
        "sha256": node_dir / f"{bundle}.sha256.txt",
        "manifest": node_dir / f"{bundle}.manifest.json",
        "pendingNotice": patchdrop_root / f"{topic_slug}.{role_slug}-pending.md",
    }
    failures: list[str] = []
    missing = [name for name, path in paths.items() if not path.is_file()]
    if missing:
        failures.append("producer-sidecars-missing:" + ",".join(sorted(missing)))

    raw_secret_hits = 0
    for path in paths.values():
        if path.is_file():
            raw_secret_hits += len(SECRET_PATTERN.findall(read_text(path)))
    if raw_secret_hits:
        failures.append("secret-leak-risk")
    forbidden_paths = forbidden_patch_paths(paths["patch"]) if paths["patch"].is_file() else []
    if forbidden_paths:
        failures.append("forbidden-path:" + ",".join(sorted({item["reason"] for item in forbidden_paths})))
    diff_headers = diff_header_count(paths["patch"]) if paths["patch"].is_file() else 0
    if paths["patch"].is_file() and paths["patch"].stat().st_size > 0 and diff_headers == 0:
        failures.append("producer-patch-not-unified-diff")

    manifest = load_json(paths["manifest"]) if paths["manifest"].is_file() else {}
    source_root_input_hash = ""
    if manifest:
        if manifest.get("schemaVersion") != "patchdrop-producer-v3":
            failures.append("producer-manifest-schema")
        if str(manifest.get("node", "")).lower() != role_slug:
            failures.append("producer-manifest-node")
        if str(manifest.get("activePatch", "")) != f"{bundle}.patch":
            failures.append("producer-active-patch-mismatch")
        if str(manifest.get("desktopFinalProof", "")) != "evidence_needed":
            failures.append("producer-desktop-proof-not-pending")
        source_root_input_hash = str(manifest.get("sourceRootInputHash") or "")
        if expected_source_root_hash:
            if not source_root_input_hash:
                failures.append("producer-source-root-hash-missing")
            elif source_root_input_hash != expected_source_root_hash:
                failures.append("producer-source-root-mismatch")
        isolation = manifest.get("sourceIsolation") if isinstance(manifest.get("sourceIsolation"), dict) else {}
        git_root_ok = (
            isolation.get("gitRootPresent") is True
            and isolation.get("gitRootMatchesSourceRoot") is True
            and bool(str(isolation.get("gitRootHash") or ""))
        )
        if not git_root_ok:
            failures.append("producer-git-root-missing")
        if not (
            isolation.get("guard") == "PASS"
            and isolation.get("sourceRootKind") == "local-worktree"
            and isolation.get("sharedSourceRoot") is False
            and isolation.get("desktopCanonicalSourceRoot") is False
            and isolation.get("directCanonicalSourceEdit") is False
        ):
            failures.append("producer-source-isolation-violation")
    elif paths["manifest"].is_file():
        failures.append("producer-manifest-invalid")

    sha_verified = False
    if paths["sha256"].is_file():
        expected_files = {
            f"{bundle}.patch": paths["patch"],
            f"{bundle}.report.md": paths["report"],
            f"{bundle}.verify.log": paths["verifyLog"],
            f"{bundle}.manifest.json": paths["manifest"],
            f"../{topic_slug}.{role_slug}-pending.md": paths["pendingNotice"],
        }
        entries: dict[str, str] = {}
        for line in read_text(paths["sha256"]).splitlines():
            parts = line.strip().lstrip("\ufeff").split(None, 1)
            if len(parts) == 2:
                entries[parts[1].strip()] = parts[0].strip().lower()
        missing_sha_entries = sorted(set(expected_files) - set(entries))
        if missing_sha_entries:
            failures.append("producer-sha-entry-missing:" + ",".join(missing_sha_entries))
        else:
            sha_verified = True
            for file_name, candidate in sorted(expected_files.items()):
                actual = sha256_file(candidate).lower()
                if not actual or actual != entries[file_name]:
                    sha_verified = False
                    failures.append("producer-sha-mismatch:" + file_name)
                    break

    if paths["patch"].is_file() and paths["patch"].stat().st_size == 0:
        failures.append("producer-patch-empty")
    verify_text = read_text(paths["verifyLog"])
    if paths["verifyLog"].is_file() and "secretPatternHits=0" not in verify_text:
        failures.append("producer-secret-scan-missing")
    if paths["verifyLog"].is_file() and "Desktop final proof: evidence_needed" not in verify_text:
        failures.append("producer-verify-desktop-proof-missing")

    return {
        "valid": not failures,
        "bundle": bundle,
        "sidecarsComplete": not missing,
        "shaVerified": sha_verified,
        "rawSecretPatternHits": raw_secret_hits,
        "diffHeaderCount": diff_headers,
        "forbiddenPathCount": len(forbidden_paths),
        "expectedSourceRootHash": expected_source_root_hash[:12],
        "sourceRootInputHash": source_root_input_hash[:12],
        "failReason": ",".join(failures),
    }


def producer_patch_sidecar_hash(patchdrop_root: Path, role: str, topic: str) -> str:
    role_slug = slug(role)
    topic_slug = slug(topic)
    bundle = f"{topic_slug}-{role_slug}-v3"
    patch_path = patchdrop_root / role_slug / f"{bundle}.patch"
    return sha256_file(patch_path).lower() if patch_path.is_file() else ""


def dispatch_artifact_evidence(root: Path) -> dict[str, Any]:
    dispatch_dir = root / "__patch_drop__" / "dispatch"
    if not dispatch_dir.exists():
        return {"ok": False, "artifactCount": 0, "evidence": "artifactCount=0;dispatchDir=missing"}
    dispatch_jsons = sorted(
        (path for path in dispatch_dir.glob("*-desktop-dispatch.json") if path.is_file()),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    dispatch_jsons = filter_dispatch_packets(dispatch_jsons)
    for dispatch_json in dispatch_jsons:
        prefix = dispatch_json.name.removesuffix("-desktop-dispatch.json")
        required = [
            dispatch_json,
            dispatch_dir / f"{prefix}-macmini.commands.txt",
            dispatch_dir / f"{prefix}-notebook.commands.txt",
            dispatch_dir / f"{prefix}-desktop-intake.ps1",
            dispatch_dir / f"{prefix}-handoff.md",
        ]
        sidecar = dispatch_dir / f"{prefix}-dispatch.sha256.txt"
        if all(path.is_file() for path in required) and sidecar.is_file():
            hashes = ",".join(sha256_file(path)[:12] for path in required)
            return {
                "ok": True,
                "artifactCount": 6,
                "evidence": f"artifactCount=6;topic={prefix};hashes={hashes};DispatchShaSidecar=True",
            }
    count = len([path for path in dispatch_dir.glob("*") if path.is_file()])
    return {"ok": False, "artifactCount": count, "evidence": f"artifactCount={count};completeSet=false"}


def validate_external_node_smoke(
    data: Any,
    role: str,
    raw_secret_hits: int,
    expected_source_root_hash: str = "",
) -> dict[str, Any]:
    failures: list[str] = []
    if not isinstance(data, dict):
        return {"valid": False, "stepCount": 0, "restoreBlocked": False, "failReason": "not-object"}
    if data.get("schemaVersion") != "awx.mcp.node_smoke.v1":
        failures.append("schema")
    if data.get("nodeRole") != role:
        failures.append("node-role")
    if data.get("ok") is not True:
        failures.append("not-ok")
    if int(data.get("rawSecretPatternHits", 0) or 0) != 0 or raw_secret_hits != 0:
        failures.append("secret-leak-risk")
    if role in {"macmini", "notebook", "read-only"}:
        isolation = data.get("sourceIsolation") if isinstance(data.get("sourceIsolation"), dict) else {}
        if not isolation:
            failures.append("source-isolation-missing")
        else:
            git_root_ok = (
                isolation.get("gitRootPresent") is True
                and isolation.get("gitRootMatchesSourceRoot") is True
                and bool(str(isolation.get("gitRootHash") or ""))
            )
            if not git_root_ok:
                failures.append("git-root-missing")
            if not (
                isolation.get("guard") == "PASS"
                and isolation.get("sourceRootKind") == "local-worktree"
                and isolation.get("sharedSourceRoot") is False
                and isolation.get("desktopCanonicalSourceRoot") is False
                and isolation.get("directCanonicalSourceEdit") is False
                and str(data.get("rootHash") or "")
                and str(data.get("canonicalRootHash") or "")
                and data.get("rootHash") != data.get("canonicalRootHash")
            ):
                failures.append("source-isolation-violation")
        if expected_source_root_hash:
            actual_source_root_hash = str(data.get("sourceRootInputHash") or "")
            if not actual_source_root_hash:
                failures.append("source-root-hash-missing")
            elif actual_source_root_hash != expected_source_root_hash:
                failures.append("source-root-mismatch")

    steps = data.get("steps") if isinstance(data.get("steps"), list) else []
    step_tools = {
        str(step.get("toolName", ""))
        for step in steps
        if isinstance(step, dict)
    }
    missing_tools = sorted(REQUIRED_NODE_SMOKE_TOOLS - step_tools)
    if missing_tools:
        failures.append("missing-tools:" + ",".join(missing_tools))
    for tool_name, allowed_decisions in NODE_SMOKE_DECISION_ALLOWLIST.items():
        matching_steps = [
            step
            for step in steps
            if isinstance(step, dict) and str(step.get("toolName", "")) == tool_name
        ]
        if matching_steps and not any(
            str(step.get("decision", "")) in allowed_decisions
            for step in matching_steps
        ):
            failures.append(tool_name.replace("_", "-") + "-decision")
    for tool_name, fallback_decisions in NODE_SMOKE_FALLBACK_DECISIONS.items():
        fallback_steps = [
            step
            for step in steps
            if (
                isinstance(step, dict)
                and str(step.get("toolName", "")) == tool_name
                and str(step.get("decision", "")) in fallback_decisions
            )
        ]
        if fallback_steps and not any(step.get("localFallbackPresent") is True for step in fallback_steps):
            failures.append(tool_name.replace("_", "-") + "-local-fallback")

    restore_blocked = any(
        isinstance(step, dict)
        and step.get("toolName") == "archive_restore"
        and step.get("decision") == "restore_target_blocked"
        and step.get("failReason") == "smb-conflict-risk"
        for step in steps
    )
    if role in {"macmini", "notebook", "read-only"} and not restore_blocked:
        failures.append("restore-block-missing")
    return {
        "valid": not failures,
        "stepCount": len(steps),
        "restoreBlocked": restore_blocked,
        "failReason": ",".join(failures),
    }


def validate_external_producer_handoff(
    data: Any,
    role: str,
    topic: str,
    raw_secret_hits: int,
    expected_source_root_hash: str = "",
    expected_patch_hash: str = "",
    expected_producer_command_hash: str = "",
) -> dict[str, Any]:
    failures: list[str] = []
    if not isinstance(data, dict):
        return {
            "valid": False,
            "promotionReady": False,
            "sidecarsComplete": False,
            "sourceRootInputHash": "",
            "desktopFinalProof": "",
            "bundleDesktopFinalProof": "",
            "patchHash": "",
            "producerCommandHash": "",
            "failReason": "not-object",
        }
    if data.get("schemaVersion") != "awx.mcp.producer_handoff.v1":
        failures.append("schema")
    if data.get("nodeRole") != role:
        failures.append("node-role")
    if data.get("ok") is not True:
        failures.append("not-ok")
    if str(data.get("topic", "")) != topic:
        failures.append("topic")
    try:
        raw_secret_count = int(data.get("rawSecretPatternHits", 0) or 0)
    except (TypeError, ValueError):
        raw_secret_count = 1
    if raw_secret_count != 0 or raw_secret_hits != 0:
        failures.append("secret-leak-risk")
    actual_source_root_hash = str(data.get("sourceRootInputHash") or data.get("sourceRootHash") or "")
    if expected_source_root_hash:
        if not actual_source_root_hash:
            failures.append("producer-source-root-hash-missing")
        elif actual_source_root_hash != expected_source_root_hash:
            failures.append("producer-source-root-mismatch")
    producer_command_hash = str(data.get("producerCommandHash", "")).strip().lower()
    if expected_producer_command_hash:
        if not producer_command_hash:
            failures.append("producer-command-hash-missing")
        elif producer_command_hash != expected_producer_command_hash.lower():
            failures.append("producer-command-hash-mismatch")
    desktop_final_proof = str(data.get("desktopFinalProof", ""))
    if desktop_final_proof != "evidence_needed":
        failures.append("desktop-final-proof")
    if str(data.get("failReason", "")):
        failures.append("fail-reason")
    bundle = data.get("bundle") if isinstance(data.get("bundle"), dict) else {}
    promotion_ready = bundle.get("promotionReady") is True
    if not promotion_ready:
        failures.append("promotion-ready")
    sidecars_complete = bundle.get("sidecarsComplete") is True
    bundle_desktop_final_proof = str(bundle.get("desktopFinalProof", ""))
    if bundle_desktop_final_proof != "evidence_needed":
        failures.append("bundle-desktop-final-proof")
    if not sidecars_complete:
        failures.append("sidecars")
    patch_hash = str(bundle.get("patchHash", "")).strip().lower()
    if not re.fullmatch(r"[a-f0-9]{64}", patch_hash):
        failures.append("patch-hash")
    elif expected_patch_hash and patch_hash != expected_patch_hash.lower():
        failures.append("patch-hash-mismatch")
    if str(bundle.get("failReason", "")):
        failures.append("bundle-fail-reason")
    isolation = bundle.get("sourceIsolation") if isinstance(bundle.get("sourceIsolation"), dict) else {}
    git_root_ok = (
        isolation.get("gitRootPresent") is True
        and isolation.get("gitRootMatchesSourceRoot") is True
        and bool(str(isolation.get("gitRootHash") or ""))
    )
    if not git_root_ok:
        failures.append("producer-git-root-missing")
    return {
        "valid": not failures,
        "promotionReady": promotion_ready,
        "sidecarsComplete": sidecars_complete,
        "sourceRootInputHash": actual_source_root_hash[:12],
        "desktopFinalProof": desktop_final_proof,
        "bundleDesktopFinalProof": bundle_desktop_final_proof,
        "patchHash": patch_hash,
        "producerCommandHash": producer_command_hash,
        "failReason": ",".join(failures),
    }


def dispatch_artifact_summary(root: Path) -> dict[str, Any]:
    dispatch_dir = root / "__patch_drop__" / "dispatch"
    packets = (
        sorted(path for path in dispatch_dir.glob("*-desktop-dispatch.json") if path.is_file())
        if dispatch_dir.exists()
        else []
    )
    packets = filter_dispatch_packets(packets)
    if not packets:
        return {
            "ok": False,
            "evidence": "dispatchPacketCount=0",
            "failReason": "dispatch-artifacts-missing",
        }

    def role_packet(dispatch_packet: dict[str, Any], role: str) -> dict[str, Any]:
        for item in dispatch_packet.get("packets", []):
            if isinstance(item, dict) and str(item.get("nodeRole") or "") == role:
                return item
        return {}

    def role_command_name(names: list[str], role: str) -> str:
        suffix = f"-{role}.commands.txt"
        for name in names:
            if name.endswith(suffix):
                return name
        return ""

    def role_producer_command_file(dispatch_packet: dict[str, Any], role: str) -> str:
        for item in dispatch_packet.get("nextActions", []):
            if not isinstance(item, dict):
                continue
            if (
                str(item.get("action") or "") == "run-producer-command-file"
                and str(item.get("nodeRole") or "") == role
            ):
                return str(item.get("producerCommandFile") or "")
        return ""

    def producer_visible_dispatch_path(root: str, filename: str) -> str:
        normalized_root = str(root or "").strip().rstrip("/\\")
        normalized_filename = str(filename or "").strip()
        if not normalized_root or not normalized_filename:
            return ""
        separator = "\\" if "\\" in normalized_root and not normalized_root.startswith("/") else "/"
        return normalized_root + separator + separator.join(("dispatch", normalized_filename))

    def producer_visible_command_file_valid(
        dispatch_packet: dict[str, Any],
        role: str,
        producer_patchdrop_root: str,
        command_name: str,
    ) -> bool:
        actual = role_producer_command_file(dispatch_packet, role)
        expected = producer_visible_dispatch_path(producer_patchdrop_root, command_name)
        if not actual or not expected:
            return False
        if "\\" in expected:
            return actual.casefold() == expected.casefold()
        return actual == expected

    def dispatch_sha_sidecar_valid(
        dispatch_packet: dict[str, Any],
        names: list[str],
        topic_slug: str,
    ) -> bool:
        index = dispatch_packet.get("dispatchArtifactIndex")
        if not isinstance(index, dict):
            return False
        sidecar_name = Path(str(index.get("dispatchSha256Sidecar") or "")).name
        if not sidecar_name:
            sidecar_name = f"{topic_slug}-dispatch.sha256.txt"
        if sidecar_name not in names or sidecar_name.endswith("-desktop-dispatch.json"):
            return False
        sidecar_path = dispatch_dir / sidecar_name
        if not sidecar_path.is_file():
            return False
        raw_covered = index.get("sha256CoveredArtifacts")
        covered_names = [
            Path(str(item)).name
            for item in raw_covered
            if str(item or "").strip()
        ] if isinstance(raw_covered, list) else []
        expected_covered = [
            f"{topic_slug}-desktop-dispatch.json",
            f"{topic_slug}-macmini.commands.txt",
            f"{topic_slug}-notebook.commands.txt",
            f"{topic_slug}-desktop-intake.ps1",
            f"{topic_slug}-handoff.md",
        ]
        if covered_names != expected_covered or sidecar_name in covered_names:
            return False
        sidecar_hashes: dict[str, str] = {}
        for line in read_text(sidecar_path).splitlines():
            match = re.match(r"^\s*([A-Fa-f0-9]{64})\s+(.+?)\s*$", line)
            if not match:
                return False
            sidecar_hashes[Path(match.group(2)).name] = match.group(1).lower()
        if sorted(sidecar_hashes) != sorted(expected_covered):
            return False
        for name in expected_covered:
            artifact_path = dispatch_dir / name
            if not artifact_path.is_file():
                return False
            if sidecar_hashes.get(name) != sha256_file(artifact_path).lower():
                return False
        return True

    def command_pathspecs(command_path: Path) -> set[str]:
        if not command_path.is_file():
            return set()
        text = read_text(command_path)
        values: set[str] = set()
        for match in re.finditer(r"--pathspec\s+(?:'([^']*)'|\"([^\"]*)\"|([^\s]+))", text):
            raw = next((group for group in match.groups() if group is not None), "")
            normalized = raw.strip().replace("\\", "/")
            if normalized:
                values.add(normalized)
        return values

    def has_source_root_prologue(command_path: Path, role: str, source_root: str) -> bool:
        if not command_path.is_file() or not source_root:
            return False
        text = read_text(command_path)
        normalized_text = text.replace("\\", "/")
        normalized_root = source_root.replace("\\", "/")
        if role == "macmini":
            return f"cd '{normalized_root}'" in normalized_text or f'cd "{normalized_root}"' in normalized_text
        if role == "notebook":
            return (
                f"Push-Location '{normalized_root}'" in normalized_text
                or f'Push-Location "{normalized_root}"' in normalized_text
            )
        return False

    def has_source_root_guard(command_path: Path, role: str) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        if role == "macmini":
            return "ProducerRoot=" in text and '[ -d "$ProducerRoot" ]' in text
        if role == "notebook":
            return "$ProducerRoot" in text and "Test-Path -LiteralPath $ProducerRoot -PathType Container" in text
        return False

    def has_source_root_args(command_path: Path, source_root: str) -> bool:
        if not command_path.is_file() or not source_root:
            return False
        text = read_text(command_path)
        expected = source_root.replace("\\", "/").rstrip("/")
        values: list[str] = []
        for arg_name in ("--source-root", "--root"):
            pattern = rf"{re.escape(arg_name)}\s+(?:'([^']*)'|\"([^\"]*)\"|([^\s]+))"
            for match in re.finditer(pattern, text):
                value = next((group for group in match.groups() if group is not None), "")
                normalized = value.strip().replace("\\", "/").rstrip("/")
                if normalized:
                    values.append(normalized)
        return len(values) >= 3 and all(value == expected for value in values)

    def has_git_root_preflight(command_path: Path, role: str) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        setup_index = invocation_index(text, "awx_mcp_node_setup.py")
        pre_setup = text[:setup_index] if setup_index >= 0 else text
        if role == "macmini":
            return (
                "producer-git-root-invalid" in pre_setup
                and "git -C" in pre_setup
                and "rev-parse --show-toplevel" in pre_setup
                and 'GitRoot="$(git -C "$ProducerRoot" rev-parse --show-toplevel' in pre_setup
                and '"$GitRoot" != "$ProducerRoot"' in pre_setup
            )
        if role == "notebook":
            return (
                "producer-git-root-invalid" in pre_setup
                and "git -C $ProducerRoot rev-parse --show-toplevel" in pre_setup
                and "$GitRootExit = $LASTEXITCODE" in pre_setup
                and "$ResolvedGitRoot" in pre_setup
                and "$ResolvedProducerRoot" in pre_setup
                and "OrdinalIgnoreCase" in pre_setup
            )
        return False

    def invocation_index(text: str, script_name: str, *, last: bool = False) -> int:
        escaped = re.escape(script_name)
        matches = list(re.finditer(rf"(?m)^\s*python3?\s+.*{escaped}\b", text))
        if not matches:
            return -1
        return matches[-1].start() if last else matches[0].start()

    def has_node_setup_before_smoke(command_path: Path) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        setup_index = invocation_index(text, "awx_mcp_node_setup.py")
        smoke_index = invocation_index(text, "awx_mcp_node_smoke.py")
        return setup_index >= 0 and smoke_index >= 0 and setup_index < smoke_index

    def has_node_setup_exit_guard(command_path: Path, role: str) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        setup_index = invocation_index(text, "awx_mcp_node_setup.py")
        smoke_index = invocation_index(text, "awx_mcp_node_smoke.py")
        if setup_index < 0 or smoke_index < 0 or setup_index >= smoke_index:
            return False
        if role == "macmini":
            return "set -euo pipefail" in text
        if role == "notebook":
            setup_exit_index = text.find("$SetupExit = $LASTEXITCODE", setup_index)
            return (
                setup_exit_index > setup_index
                and setup_exit_index < smoke_index
                and "node-setup-failed" in text[setup_index:smoke_index]
                and "exit $SetupExit" in text[setup_index:smoke_index]
            )
        return False

    def has_setup_audit_log(command_path: Path) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        setup_index = invocation_index(text, "awx_mcp_node_setup.py")
        smoke_index = invocation_index(text, "awx_mcp_node_smoke.py")
        if setup_index < 0:
            return False
        setup_segment = text[setup_index:smoke_index] if smoke_index > setup_index else text[setup_index:]
        return "--audit-log" in setup_segment and "awx-control-tower.audit.jsonl" in setup_segment

    def has_handoff_audit_log(command_path: Path) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        handoff_index = invocation_index(text, "awx_mcp_producer_handoff.py", last=True)
        if handoff_index < 0:
            return False
        handoff_segment = text[handoff_index:]
        return "--audit-log" in handoff_segment and "awx-control-tower.audit.jsonl" in handoff_segment

    def has_handoff_proof_validation(command_path: Path, role: str) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        handoff_index = invocation_index(text, "awx_mcp_producer_handoff.py", last=True)
        if handoff_index < 0:
            return False
        proof_name = f"{role}-producer-handoff.json"
        proof_index = text.find(proof_name)
        segment_start = proof_index if 0 <= proof_index < handoff_index else handoff_index
        segment = text[segment_start:]
        required = (
            proof_name,
            "json.load",
            'd.get("ok") is True',
            'd.get("nodeRole")',
            "rawSecretPatternHits",
            "sourceRootInputHash",
            "promotionReady",
            "sidecarsComplete",
            "patchHash",
            "desktopFinalProof",
            "producer-handoff-invalid-proof",
        )
        if not all(item in segment for item in required):
            return False
        if role == "notebook":
            return "$HandoffExit" in segment and "producer-handoff-failed" in segment
        return "set -euo pipefail" in text

    def has_smoke_json_validation(command_path: Path) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        smoke_index = invocation_index(text, "awx_mcp_node_smoke.py")
        handoff_index = invocation_index(text, "awx_mcp_producer_handoff.py", last=True)
        json_index = text.find("json.load", smoke_index if smoke_index >= 0 else 0)
        if smoke_index < 0 or handoff_index < 0 or json_index < 0:
            return False
        return smoke_index < json_index < handoff_index

    def has_smoke_semantic_validation(command_path: Path) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        smoke_index = invocation_index(text, "awx_mcp_node_smoke.py")
        handoff_index = invocation_index(text, "awx_mcp_producer_handoff.py", last=True)
        json_index = text.find("json.load", smoke_index if smoke_index >= 0 else 0)
        if smoke_index < 0 or handoff_index < 0 or json_index < 0:
            return False
        segment = text[smoke_index:handoff_index]
        required = (
            'd.get("ok") is True',
            'd.get("nodeRole")',
            "rawSecretPatternHits",
            "sourceIsolation",
            "sourceRootInputHash",
            "directCanonicalSourceEdit",
            "desktopCanonicalSourceRoot",
            "sharedSourceRoot",
            "sourceRootKind",
        )
        return smoke_index < json_index < handoff_index and all(item in segment for item in required)

    def has_safe_canonical_root(command_path: Path) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        matches = re.findall(r"--canonical-root\s+(['\"]?)([^'\"\s]+)\1", text)
        if not matches:
            return False
        return all(value not in {".", "./", ".\\"} for _, value in matches)

    def has_producer_local_helper(command_path: Path, source_root: str, producer_patchdrop_root: str) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        handoff_index = invocation_index(text, "awx_mcp_producer_handoff.py", last=True)
        if handoff_index < 0:
            return False
        handoff_segment = text[handoff_index:]
        normalized_patchdrop_root = producer_patchdrop_root.replace("\\", "/").rstrip("/")
        normalized_segment = handoff_segment.replace("\\", "/")
        if normalized_patchdrop_root and f"{normalized_patchdrop_root}/producer_bundle.py" in normalized_segment:
            return False
        matches = re.findall(r"--producer-script\s+(?:'([^']+)'|\"([^\"]+)\"|(\S+))", handoff_segment)
        if not matches:
            return True
        normalized_root = source_root.replace("\\", "/").rstrip("/")
        allowed_relative = {"__patch_drop__/producer_bundle.py", "./__patch_drop__/producer_bundle.py"}
        for match in matches:
            value = next((item for item in match if item), "")
            normalized_value = value.replace("\\", "/").rstrip("/")
            if normalized_value in allowed_relative:
                continue
            if normalized_root and normalized_value == f"{normalized_root}/__patch_drop__/producer_bundle.py":
                continue
            if normalized_patchdrop_root and normalized_value == f"{normalized_patchdrop_root}/producer_bundle.py":
                return False
            return False
        return True

    def has_producer_kit_bootstrap(command_path: Path, role: str, source_root: str) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        if "MissingRequiredFile" in text or "BootstrapRequiredFiles" in text:
            return False
        install_name = "INSTALL.macmini.sh" if role == "macmini" else "INSTALL.notebook.ps1"
        install_index = text.find(install_name)
        preflight_index = text.find("required tool file missing")
        if install_index < 0 or preflight_index < 0 or install_index > preflight_index:
            return False
        normalized_text = text.replace("\\", "/")
        normalized_root = source_root.replace("\\", "/").rstrip("/")
        if not ("producer-kit" in normalized_text and bool(normalized_root and normalized_root in normalized_text)):
            return False
        if role == "macmini":
            return (
                '[ -f "$KitInstall" ] ||' in text
                and "producer-kit-installer-missing" in text
                and 'bash "$KitInstall"' in text
                and 'if [ -f "$KitInstall" ]; then' not in text
            )
        if role == "notebook":
            return (
                "if (-not (Test-Path -LiteralPath $KitInstall -PathType Leaf))" in text
                and "producer-kit-installer-missing" in text
                and "powershell -NoProfile -ExecutionPolicy Bypass -File $KitInstall" in text
                and "if (Test-Path -LiteralPath $KitInstall -PathType Leaf)" not in text
            )
        return False

    def has_producer_kit_manifest_hash(command_path: Path, packet_data: dict[str, Any]) -> bool:
        if not command_path.is_file():
            return False
        expected_hash = str(packet_data.get("producerKitManifestHash") or "").strip().lower()
        if not re.fullmatch(r"[a-f0-9]{64}", expected_hash):
            return False
        text = read_text(command_path)
        return (
            "producer-kit.manifest.json" in text
            and "producer-kit-manifest-sha-mismatch" in text
            and expected_hash in text.lower()
        )

    def has_producer_command_file_guard(command_path: Path, role: str) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        if "producer-command-file-missing" not in text:
            return False
        if role == "macmini":
            assign_index = text.find("ProducerCommandFile=")
            guard_index = text.find("producer-command-file-missing", assign_index if assign_index >= 0 else 0)
            hash_index = text.find("ProducerCommandHash=", assign_index if assign_index >= 0 else 0)
        else:
            assign_index = text.find("$ProducerCommandFile =")
            guard_index = text.find("producer-command-file-missing", assign_index if assign_index >= 0 else 0)
            hash_index = text.find("$ProducerCommandHash =", assign_index if assign_index >= 0 else 0)
        return assign_index >= 0 and assign_index < guard_index < hash_index

    def has_producer_dispatch_sidecar_guard(command_path: Path, role: str) -> bool:
        if not command_path.is_file():
            return False
        text = read_text(command_path)
        handoff_index = invocation_index(text, "awx_mcp_producer_handoff.py", last=True)
        if role == "macmini":
            hash_index = text.find("ProducerCommandHash=")
        else:
            hash_index = text.find("$ProducerCommandHash =")
        sidecar_index = text.find("dispatch-sha-sidecar-missing", hash_index if hash_index >= 0 else 0)
        missing_index = text.find("dispatch-command-sha-missing", sidecar_index if sidecar_index >= 0 else 0)
        mismatch_index = text.find("dispatch-command-sha-mismatch", sidecar_index if sidecar_index >= 0 else 0)
        sidecar_path_index = text.find("-dispatch.sha256.txt", hash_index if hash_index >= 0 else 0)
        return (
            hash_index >= 0
            and sidecar_path_index > hash_index
            and sidecar_index > hash_index
            and missing_index > sidecar_index
            and mismatch_index > missing_index
            and handoff_index > mismatch_index
        )

    def has_patchdrop_handoff_contract(packet_data: dict[str, Any]) -> bool:
        contract = packet_data.get("handoffContract")
        if not isinstance(contract, dict):
            return False
        required = set(str(item) for item in contract.get("requiredArtifacts", []))
        return (
            contract.get("workMode") == "producer-local-worktree"
            and contract.get("outputMode") == "patchdrop-unified-diff-sidecars"
            and contract.get("directCanonicalSourceEditAllowed") is False
            and contract.get("desktopFinalProof") == "evidence_needed"
            and PATCHDROP_HANDOFF_REQUIRED_ARTIFACTS.issubset(required)
        )

    def desktop_intake_policy(command_path: Path, topic: str) -> tuple[bool, bool, bool, bool, bool, bool, bool]:
        if not command_path.is_file():
            return (False, False, False, False, False, False, False)
        text = read_text(command_path)
        runtime_text = "\n".join(
            line for line in text.splitlines() if not line.lstrip().startswith("#")
        )
        command_lines = [
            line
            for line in text.splitlines()
            if "external_evidence_intake" in line or "external_evidence_audit" in line
        ]
        if len(command_lines) < 2:
            return (False, False, False, False, False, False, False)
        topic_slug = slug(topic)
        topic_pinned = all("topic" in line and topic_slug in line for line in command_lines)
        producer_bundles_required = all(
            "require_producer_bundles" in line and "$true" in line
            for line in command_lines
        )
        source_lease_gate = all(
            token in runtime_text
            for token in (
                "source_edit_session.ps1",
                "-Action begin",
                "-Action end",
                "-Role desktop-consumer",
                "$DesktopLeaseAcquired",
            )
        )
        lease_exit_index = runtime_text.find("$LeaseBeginExit = $LASTEXITCODE")
        intake_index = runtime_text.find("external_evidence_intake")
        source_lease_fail_closed = (
            lease_exit_index >= 0
            and (intake_index < 0 or lease_exit_index < intake_index)
            and "source-lease-begin-failed" in runtime_text
            and "exit $LeaseBeginExit" in runtime_text
        )
        audit_index = text.find("external_evidence_audit")
        completion_index = text.find("awx_mcp_completion_audit.py")
        intake_exit_index = text.find("$IntakeExit = $LASTEXITCODE")
        audit_exit_index = text.find("$AuditExit = $LASTEXITCODE")
        completion_exit_index = text.find("$CompletionAuditExit = $LASTEXITCODE")
        command_fail_closed = (
            intake_index >= 0
            and intake_exit_index > intake_index
            and (audit_index < 0 or intake_exit_index < audit_index)
            and "external-evidence-intake-failed" in text
            and "exit $IntakeExit" in text
            and audit_index >= 0
            and audit_exit_index > audit_index
            and (completion_index < 0 or audit_exit_index < completion_index)
            and "external-evidence-audit-failed" in text
            and "exit $AuditExit" in text
            and completion_index >= 0
            and completion_exit_index > completion_index
            and "completion-audit-failed" in text
            and "exit $CompletionAuditExit" in text
        )
        json_semantics_fail_closed = (
            "$IntakeRaw =" in text
            and "$IntakeJson = $IntakeRaw | ConvertFrom-Json" in text
            and "external-evidence-intake-invalid-json" in text
            and "external-evidence-intake-incomplete" in text
            and "$AuditRaw =" in text
            and "$AuditJson = $AuditRaw | ConvertFrom-Json" in text
            and "external-evidence-audit-invalid-json" in text
            and "external-evidence-audit-incomplete" in text
            and "externalEvidenceComplete" in text
        )
        dispatch_sidecar_index = runtime_text.find("desktop-dispatch-sha-sidecar-missing")
        dispatch_missing_index = runtime_text.find("desktop-dispatch-sha-missing")
        dispatch_mismatch_index = runtime_text.find("desktop-dispatch-sha-mismatch")
        dispatch_sha_preflight = (
            "$DispatchShaSidecar =" in text
            and "$DispatchCoveredFiles = @(" in text
            and dispatch_sidecar_index > lease_exit_index
            and dispatch_missing_index > dispatch_sidecar_index
            and dispatch_mismatch_index > dispatch_missing_index
            and intake_index > dispatch_mismatch_index
        )
        return (
            topic_pinned,
            producer_bundles_required,
            source_lease_gate,
            source_lease_fail_closed,
            command_fail_closed,
            json_semantics_fail_closed,
            dispatch_sha_preflight,
        )

    valid_count = 0
    handoff_proof_valid_count = 0
    evidence_parts: list[str] = []
    missing: list[str] = []
    invalid: list[str] = []
    evidence_needed: list[str] = []
    for packet_path in packets:
        packet = load_json(packet_path)
        fallback_topic = packet_path.name.removesuffix("-desktop-dispatch.json")
        topic = str(packet.get("topic") or fallback_topic)
        desktop_proof = str(packet.get("desktopFinalProof") or "")
        artifact_names = [
            Path(str(item)).name
            for item in packet.get("dispatchArtifacts", [])
            if str(item).strip()
        ]
        if not artifact_names:
            artifact_names = [
                path.name
                for path in sorted(dispatch_dir.glob(f"{fallback_topic}-*"))
            ]
        missing_for_packet = [
            name for name in artifact_names
            if not (dispatch_dir / name).exists()
        ]
        has_macmini_commands = any(name.endswith("-macmini.commands.txt") for name in artifact_names)
        has_notebook_commands = any(name.endswith("-notebook.commands.txt") for name in artifact_names)
        has_desktop_intake = any(name.endswith("-desktop-intake.ps1") for name in artifact_names)
        dispatch_sha_sidecar = dispatch_sha_sidecar_valid(packet, artifact_names, topic)
        desktop_intake_name = next(
            (name for name in artifact_names if name.endswith("-desktop-intake.ps1")),
            "",
        )
        macmini_command_name = role_command_name(artifact_names, "macmini")
        notebook_command_name = role_command_name(artifact_names, "notebook")
        macmini_command_path = dispatch_dir / macmini_command_name
        notebook_command_path = dispatch_dir / notebook_command_name
        macmini_packet = role_packet(packet, "macmini")
        notebook_packet = role_packet(packet, "notebook")
        macmini_pathspecs = command_pathspecs(macmini_command_path)
        notebook_pathspecs = command_pathspecs(notebook_command_path)
        pathspec_overlap_count = len(macmini_pathspecs & notebook_pathspecs)
        macmini_source_root = str(macmini_packet.get("sourceRoot") or "")
        notebook_source_root = str(notebook_packet.get("sourceRoot") or "")
        producer_kit_manifest_path = (
            root
            / "__patch_drop__"
            / "producer-kit"
            / f"{topic}-producer-kit"
            / "producer-kit.manifest.json"
        )
        producer_kit_manifest_actual_hash = (
            sha256_file(producer_kit_manifest_path).lower()
            if producer_kit_manifest_path.is_file()
            else ""
        )
        macmini_producer_patchdrop_root = str(macmini_packet.get("producerPatchdropRoot") or "")
        notebook_producer_patchdrop_root = str(notebook_packet.get("producerPatchdropRoot") or "")
        macmini_desktop_patchdrop_root = str(macmini_packet.get("desktopPatchdropRoot") or "")
        notebook_desktop_patchdrop_root = str(notebook_packet.get("desktopPatchdropRoot") or "")
        macmini_patchdrop_roots = bool(macmini_producer_patchdrop_root and macmini_desktop_patchdrop_root)
        notebook_patchdrop_roots = bool(notebook_producer_patchdrop_root and notebook_desktop_patchdrop_root)
        macmini_producer_visible_command_file = producer_visible_command_file_valid(
            packet,
            "macmini",
            macmini_producer_patchdrop_root,
            macmini_command_name,
        )
        notebook_producer_visible_command_file = producer_visible_command_file_valid(
            packet,
            "notebook",
            notebook_producer_patchdrop_root,
            notebook_command_name,
        )
        producer_visible_command_files = (
            macmini_producer_visible_command_file
            and notebook_producer_visible_command_file
        )
        macmini_source_root_exists = macmini_packet.get("desktopSourceRootExists")
        notebook_source_root_exists = notebook_packet.get("desktopSourceRootExists")
        if macmini_source_root_exists is False:
            evidence_needed.append(
                "producer source root not visible on Desktop role=macmini; verify on producer host or override producer_roots and producer_patchdrop_roots"
            )
        if notebook_source_root_exists is False:
            evidence_needed.append(
                "producer source root not visible on Desktop role=notebook; verify on producer host or override producer_roots and producer_patchdrop_roots"
            )
        macmini_source_root_prologue = has_source_root_prologue(
            macmini_command_path,
            "macmini",
            macmini_source_root,
        )
        notebook_source_root_prologue = has_source_root_prologue(
            notebook_command_path,
            "notebook",
            notebook_source_root,
        )
        macmini_source_root_args = has_source_root_args(macmini_command_path, macmini_source_root)
        notebook_source_root_args = has_source_root_args(notebook_command_path, notebook_source_root)
        macmini_git_root_preflight = has_git_root_preflight(macmini_command_path, "macmini")
        notebook_git_root_preflight = has_git_root_preflight(notebook_command_path, "notebook")
        macmini_source_root_guard = has_source_root_guard(macmini_command_path, "macmini")
        notebook_source_root_guard = has_source_root_guard(notebook_command_path, "notebook")
        macmini_node_setup_before_smoke = has_node_setup_before_smoke(macmini_command_path)
        notebook_node_setup_before_smoke = has_node_setup_before_smoke(notebook_command_path)
        macmini_node_setup_exit_guard = has_node_setup_exit_guard(macmini_command_path, "macmini")
        notebook_node_setup_exit_guard = has_node_setup_exit_guard(notebook_command_path, "notebook")
        macmini_setup_audit_log = has_setup_audit_log(macmini_command_path)
        notebook_setup_audit_log = has_setup_audit_log(notebook_command_path)
        macmini_handoff_audit_log = has_handoff_audit_log(macmini_command_path)
        notebook_handoff_audit_log = has_handoff_audit_log(notebook_command_path)
        macmini_handoff_proof_validation = has_handoff_proof_validation(
            macmini_command_path,
            "macmini",
        )
        notebook_handoff_proof_validation = has_handoff_proof_validation(
            notebook_command_path,
            "notebook",
        )
        handoff_proof_validation = macmini_handoff_proof_validation and notebook_handoff_proof_validation
        macmini_smoke_json_validation = has_smoke_json_validation(macmini_command_path)
        notebook_smoke_json_validation = has_smoke_json_validation(notebook_command_path)
        macmini_smoke_semantic_validation = has_smoke_semantic_validation(macmini_command_path)
        notebook_smoke_semantic_validation = has_smoke_semantic_validation(notebook_command_path)
        macmini_safe_canonical_root = has_safe_canonical_root(macmini_command_path)
        notebook_safe_canonical_root = has_safe_canonical_root(notebook_command_path)
        safe_canonical_root = macmini_safe_canonical_root and notebook_safe_canonical_root
        macmini_producer_local_helper = has_producer_local_helper(
            macmini_command_path,
            macmini_source_root,
            macmini_producer_patchdrop_root,
        )
        notebook_producer_local_helper = has_producer_local_helper(
            notebook_command_path,
            notebook_source_root,
            notebook_producer_patchdrop_root,
        )
        producer_local_helper = macmini_producer_local_helper and notebook_producer_local_helper
        macmini_producer_kit_bootstrap = has_producer_kit_bootstrap(
            macmini_command_path,
            "macmini",
            macmini_source_root,
        )
        notebook_producer_kit_bootstrap = has_producer_kit_bootstrap(
            notebook_command_path,
            "notebook",
            notebook_source_root,
        )
        producer_kit_bootstrap = macmini_producer_kit_bootstrap and notebook_producer_kit_bootstrap
        macmini_producer_kit_manifest_hash = has_producer_kit_manifest_hash(
            macmini_command_path,
            macmini_packet,
        )
        notebook_producer_kit_manifest_hash = has_producer_kit_manifest_hash(
            notebook_command_path,
            notebook_packet,
        )
        producer_kit_manifest_hash = (
            macmini_producer_kit_manifest_hash
            and notebook_producer_kit_manifest_hash
        )
        producer_kit_manifest_hash_actual = (
            bool(producer_kit_manifest_actual_hash)
            and str(macmini_packet.get("producerKitManifestHash") or "").lower() == producer_kit_manifest_actual_hash
            and str(notebook_packet.get("producerKitManifestHash") or "").lower() == producer_kit_manifest_actual_hash
        )
        macmini_producer_command_file_guard = has_producer_command_file_guard(
            macmini_command_path,
            "macmini",
        )
        notebook_producer_command_file_guard = has_producer_command_file_guard(
            notebook_command_path,
            "notebook",
        )
        producer_command_file_guard = (
            macmini_producer_command_file_guard
            and notebook_producer_command_file_guard
        )
        macmini_producer_dispatch_sidecar_guard = has_producer_dispatch_sidecar_guard(
            macmini_command_path,
            "macmini",
        )
        notebook_producer_dispatch_sidecar_guard = has_producer_dispatch_sidecar_guard(
            notebook_command_path,
            "notebook",
        )
        producer_dispatch_sidecar_guard = (
            macmini_producer_dispatch_sidecar_guard
            and notebook_producer_dispatch_sidecar_guard
        )
        macmini_patch_only_contract = has_patchdrop_handoff_contract(macmini_packet)
        notebook_patch_only_contract = has_patchdrop_handoff_contract(notebook_packet)
        patch_only_contract = macmini_patch_only_contract and notebook_patch_only_contract
        (
            desktop_topic_pinned,
            desktop_producer_bundles_required,
            desktop_intake_source_lease_gate,
            desktop_intake_source_lease_fail_closed,
            desktop_intake_command_fail_closed,
            desktop_intake_json_semantics,
            desktop_intake_dispatch_sha_preflight,
        ) = desktop_intake_policy(
            dispatch_dir / desktop_intake_name,
            topic,
        )
        producer_pathspecs_required = desktop_producer_bundles_required
        producer_pathspecs_ok = (
            not producer_pathspecs_required
            or (bool(macmini_pathspecs) and bool(notebook_pathspecs))
        )
        packet_valid = (
            desktop_proof == "evidence_needed"
            and len(artifact_names) >= 6
            and not missing_for_packet
            and has_macmini_commands
            and has_notebook_commands
            and has_desktop_intake
            and dispatch_sha_sidecar
            and macmini_source_root_prologue
            and notebook_source_root_prologue
            and macmini_source_root_args
            and notebook_source_root_args
            and macmini_git_root_preflight
            and notebook_git_root_preflight
            and macmini_source_root_guard
            and notebook_source_root_guard
            and macmini_node_setup_before_smoke
            and notebook_node_setup_before_smoke
            and macmini_node_setup_exit_guard
            and notebook_node_setup_exit_guard
            and macmini_setup_audit_log
            and notebook_setup_audit_log
            and macmini_handoff_audit_log
            and notebook_handoff_audit_log
            and handoff_proof_validation
            and macmini_smoke_json_validation
            and notebook_smoke_json_validation
            and macmini_smoke_semantic_validation
            and notebook_smoke_semantic_validation
            and safe_canonical_root
            and producer_local_helper
            and producer_kit_bootstrap
            and producer_kit_manifest_hash
            and producer_kit_manifest_hash_actual
            and producer_command_file_guard
            and producer_dispatch_sidecar_guard
            and producer_visible_command_files
            and patch_only_contract
            and macmini_patchdrop_roots
            and notebook_patchdrop_roots
            and desktop_topic_pinned
            and desktop_producer_bundles_required
            and desktop_intake_source_lease_gate
            and desktop_intake_source_lease_fail_closed
            and desktop_intake_command_fail_closed
            and desktop_intake_json_semantics
            and desktop_intake_dispatch_sha_preflight
            and producer_pathspecs_ok
        )
        if packet_valid:
            valid_count += 1
        else:
            invalid.append(topic)
        if handoff_proof_validation:
            handoff_proof_valid_count += 1
        missing.extend(f"{topic}:{name}" for name in missing_for_packet)
        evidence_parts.append(
            f"topic={topic};artifactCount={len(artifact_names)};desktopProof={desktop_proof or 'missing'}"
            f";macminiSourceRootPrologue={macmini_source_root_prologue}"
            f";notebookSourceRootPrologue={notebook_source_root_prologue}"
            f";DispatchShaSidecar={dispatch_sha_sidecar}"
            f";macminiSourceRootArgs={macmini_source_root_args}"
            f";notebookSourceRootArgs={notebook_source_root_args}"
            f";macminiGitRootPreflight={macmini_git_root_preflight}"
            f";notebookGitRootPreflight={notebook_git_root_preflight}"
            f";macminiSourceRootGuard={macmini_source_root_guard}"
            f";notebookSourceRootGuard={notebook_source_root_guard}"
            f";macminiNodeSetupBeforeSmoke={macmini_node_setup_before_smoke}"
            f";notebookNodeSetupBeforeSmoke={notebook_node_setup_before_smoke}"
            f";macminiNodeSetupExitGuard={macmini_node_setup_exit_guard}"
            f";notebookNodeSetupExitGuard={notebook_node_setup_exit_guard}"
            f";macminiSetupAuditLog={macmini_setup_audit_log}"
            f";notebookSetupAuditLog={notebook_setup_audit_log}"
            f";macminiHandoffAuditLog={macmini_handoff_audit_log}"
            f";notebookHandoffAuditLog={notebook_handoff_audit_log}"
            f";macminiHandoffProofValidation={macmini_handoff_proof_validation}"
            f";notebookHandoffProofValidation={notebook_handoff_proof_validation}"
            f";macminiSmokeJsonValidation={macmini_smoke_json_validation}"
            f";notebookSmokeJsonValidation={notebook_smoke_json_validation}"
            f";macminiSmokeSemanticValidation={macmini_smoke_semantic_validation}"
            f";notebookSmokeSemanticValidation={notebook_smoke_semantic_validation}"
            f";SafeCanonicalRoot={safe_canonical_root}"
            f";ProducerLocalHelper={producer_local_helper}"
            f";ProducerKitBootstrap={producer_kit_bootstrap}"
            f";ProducerKitManifestHash={producer_kit_manifest_hash}"
            f";ProducerKitManifestHashActual={producer_kit_manifest_hash_actual}"
            f";ProducerCommandFileGuard={producer_command_file_guard}"
            f";ProducerDispatchSidecarGuard={producer_dispatch_sidecar_guard}"
            f";ProducerVisibleCommandFiles={producer_visible_command_files}"
            f";PatchOnlyContract={patch_only_contract}"
            f";macminiPathspecCount={len(macmini_pathspecs)}"
            f";notebookPathspecCount={len(notebook_pathspecs)}"
            f";PathspecOverlapCount={pathspec_overlap_count}"
            f";ProducerPathspecsRequired={producer_pathspecs_required}"
            f";ProducerPathspecsOk={producer_pathspecs_ok}"
            f";macminiPatchdropRoots={macmini_patchdrop_roots}"
            f";notebookPatchdropRoots={notebook_patchdrop_roots}"
            f";DesktopTopicPinned={desktop_topic_pinned}"
            f";DesktopProducerBundlesRequired={desktop_producer_bundles_required}"
            f";DesktopIntakeSourceLeaseGate={desktop_intake_source_lease_gate}"
            f";DesktopIntakeSourceLeaseFailClosed={desktop_intake_source_lease_fail_closed}"
            f";DesktopIntakeCommandFailClosed={desktop_intake_command_fail_closed}"
            f";DesktopIntakeJsonSemantics={desktop_intake_json_semantics}"
            f";DesktopIntakeDispatchShaPreflight={desktop_intake_dispatch_sha_preflight}"
            f";macminiDesktopSourceRootExists={macmini_source_root_exists}"
            f";notebookDesktopSourceRootExists={notebook_source_root_exists}"
        )

    return {
        "ok": bool(packets) and valid_count == len(packets) and not missing and not invalid,
        "evidence": f"dispatchPacketCount={len(packets)};validDispatchPacketCount={valid_count};" + "|".join(evidence_parts),
        "handoffProofOk": bool(packets) and handoff_proof_valid_count == len(packets),
        "evidence_needed": evidence_needed,
        "failReason": "dispatch-artifacts-incomplete" if missing else "dispatch-artifacts-invalid",
    }


def audit(root: Path) -> dict[str, Any]:
    root = root.resolve()
    manifest_path = root / "main" / "resources" / "mcp" / "awx-control-tower-tools.json"
    manifest = load_json(manifest_path)
    manifest_text = read_text(manifest_path)
    checked: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    evidence_needed: list[str] = []

    archive_index, archive_index_source = archive_index_candidate(root)
    if not archive_index.exists():
        evidence_needed.append(
            f"{archive_index.as_posix()} / archive_search will expand queries and report evidence_needed until an index exists"
        )

    proof_dirs = [
        root / "data" / "agent-handoff" / "mcp-control-tower",
        root / "__patch_drop__" / "external-node-proof",
    ]
    producer_bundle_topic = latest_dispatch_topic(root)
    external_valid_roles: set[str] = set()
    external_handoff_valid_roles: set[str] = set()
    external_bundle_valid_roles: set[str] = set()
    external_secret_hits = 0
    for role in ("macmini", "notebook"):
        proof_path = next(
            (path for path in (find_external_node_smoke_proof(proof_dir, role) for proof_dir in proof_dirs) if path is not None),
            None,
        )
        if proof_path is None:
            continue
        raw_proof = read_text(proof_path)
        raw_hits = len(SECRET_PATTERN.findall(raw_proof))
        external_secret_hits += raw_hits
        try:
            proof_data = json.loads(raw_proof.lstrip("\ufeff"))
        except json.JSONDecodeError:
            proof_data = {}
        dispatch_packet = latest_dispatch_packet_for_role(root, role)
        expected_source_root_hash = str(dispatch_packet.get("sourceRootInputHash") or "")
        if not expected_source_root_hash:
            expected_source_root = str(dispatch_packet.get("sourceRoot") or "").strip()
            expected_source_root_hash = stable_hash(expected_source_root) if expected_source_root else ""
        validation = validate_external_node_smoke(proof_data, role, raw_hits, expected_source_root_hash)
        add_check(
            checked,
            failures,
            f"external.{role}-node-proof",
            validation["valid"],
            f"fileHash={stable_hash(raw_proof)};stepCount={validation['stepCount']};restoreBlocked={validation['restoreBlocked']}",
            f"external node smoke invalid role={role} reason={validation['failReason'] or 'invalid-json'}",
        )
        if validation["valid"]:
            external_valid_roles.add(role)
        handoff_path = next(
            (
                path
                for path in (find_external_producer_handoff_proof(proof_dir, role) for proof_dir in proof_dirs)
                if path is not None
            ),
            None,
        )
        if handoff_path is None:
            handoff_validation = {
                "valid": False,
                "promotionReady": False,
                "sidecarsComplete": False,
                "sourceRootInputHash": "",
                "desktopFinalProof": "evidence_needed",
                "bundleDesktopFinalProof": "evidence_needed",
                "failReason": "producer-handoff-missing",
            }
            handoff_evidence = (
                f"topic={producer_bundle_topic};handoff=missing;"
                f"expectedSourceRootHash={expected_source_root_hash[:12]}"
            )
        else:
            raw_handoff = read_text(handoff_path)
            handoff_hits = len(SECRET_PATTERN.findall(raw_handoff))
            external_secret_hits += handoff_hits
            try:
                handoff_data = json.loads(raw_handoff.lstrip("\ufeff"))
            except json.JSONDecodeError:
                handoff_data = {}
            handoff_validation = validate_external_producer_handoff(
                handoff_data,
                role,
                producer_bundle_topic,
                handoff_hits,
                expected_source_root_hash,
                producer_patch_sidecar_hash(root / "__patch_drop__", role, producer_bundle_topic),
                sha256_file(root / "__patch_drop__" / "dispatch" / f"{producer_bundle_topic}-{role}.commands.txt"),
            )
            handoff_evidence = (
                f"topic={producer_bundle_topic};fileHash={stable_hash(raw_handoff)};"
                f"promotionReady={handoff_validation['promotionReady']};"
                f"sidecarsComplete={handoff_validation['sidecarsComplete']};"
                f"patchHash={handoff_validation['patchHash'][:12]};"
                f"producerCommandHash={handoff_validation['producerCommandHash'][:12]};"
                f"expectedSourceRootHash={expected_source_root_hash[:12]};"
                f"sourceRootInputHash={handoff_validation['sourceRootInputHash']};"
                f"desktopFinalProof={handoff_validation['desktopFinalProof'] or 'missing'}"
            )
        add_check(
            checked,
            failures,
            f"external.{role}-producer-handoff",
            handoff_validation["valid"],
            handoff_evidence,
            f"external producer handoff invalid role={role} reason={handoff_validation['failReason'] or 'missing'}",
        )
        if handoff_validation["valid"]:
            external_handoff_valid_roles.add(role)
        bundle_validation = validate_external_producer_bundle(
            root / "__patch_drop__",
            role,
            producer_bundle_topic,
            expected_source_root_hash,
        )
        external_secret_hits += int(bundle_validation.get("rawSecretPatternHits", 0) or 0)
        add_check(
            checked,
            failures,
            f"external.{role}-producer-bundle",
            bundle_validation["valid"],
            f"topic={producer_bundle_topic};bundle={bundle_validation['bundle']};sidecarsComplete={bundle_validation['sidecarsComplete']};shaVerified={bundle_validation['shaVerified']};diffHeaderCount={bundle_validation['diffHeaderCount']}",
            f"external producer bundle invalid role={role} reason={bundle_validation['failReason'] or 'missing'}",
        )
        if bundle_validation["valid"]:
            external_bundle_valid_roles.add(role)
    if (
        external_valid_roles != {"macmini", "notebook"}
        or external_handoff_valid_roles != {"macmini", "notebook"}
        or external_bundle_valid_roles != {"macmini", "notebook"}
    ):
        evidence_needed.append(EXTERNAL_NODE_EVIDENCE_NEEDED)

    roles = manifest.get("nodeRoles", {})
    add_check(
        checked,
        failures,
        "roles.desktop-macmini-notebook",
        all(role in roles for role in ("desktop", "macmini", "notebook")),
        "manifest nodeRoles",
        "missing role split in MCP manifest",
    )

    tools = {tool.get("name"): tool for tool in manifest.get("tools", []) if isinstance(tool, dict)}
    schemas_ok = all(
        name in tools
        and tools[name].get("input_schema", {}).get("type") == "object"
        and tools[name].get("output_schema", {}).get("type") == "object"
        for name in REQUIRED_TOOLS
    )
    add_check(
        checked,
        failures,
        "tools.required-json-schemas",
        schemas_ok,
        ",".join(sorted(tools)),
        "required MCP tools or JSON schemas are missing",
    )

    audit_log_input_tools = [
        name
        for name, spec in tools.items()
        if isinstance(spec, dict)
        and "audit_log" in spec.get("input_schema", {}).get("properties", {})
    ]
    add_check(
        checked,
        failures,
        "tools.audit-log-inputs",
        sorted(audit_log_input_tools) == sorted(tools),
        ",".join(sorted(audit_log_input_tools)),
        "one or more MCP tool schemas do not expose audit_log",
    )

    aliases_ok = (
        "archive.search" in tools.get("archive_search", {}).get("aliases", [])
        and "archive.restore" in tools.get("archive_restore", {}).get("aliases", [])
        and "verify_boot" in tools.get("boot_verify", {}).get("aliases", [])
        and "build_error_miner" in tools.get("build_error_mine", {}).get("aliases", [])
        and "external.evidence_intake" in tools.get("external_evidence_intake", {}).get("aliases", [])
        and "external.evidence_audit" in tools.get("external_evidence_audit", {}).get("aliases", [])
        and "producer.command_plan" in tools.get("producer_command_plan", {}).get("aliases", [])
        and "producer.kit_export" in tools.get("producer_kit_export", {}).get("aliases", [])
        and "desktop.dispatch_packet" in tools.get("desktop_dispatch_packet", {}).get("aliases", [])
        and "desktop.control_loop" in tools.get("desktop_control_loop", {}).get("aliases", [])
    )
    add_check(
        checked,
        failures,
        "tools.task-aliases",
        aliases_ok,
        "archive.search/archive.restore/verify_boot/build_error_miner/external.evidence_intake/external.evidence_audit/producer.command_plan/producer.kit_export/desktop.dispatch_packet/desktop.control_loop aliases",
        "task aliases are incomplete",
    )

    verify_boot_ps1 = root / "verify_boot.ps1"
    verify_boot_text = read_text(verify_boot_ps1)
    verify_boot_secret_hits = len(SECRET_PATTERN.findall(verify_boot_text))
    verify_boot_has_awx_host = (
        "AWX_AGENT_HOST" in verify_boot_text
        and "AWX_SPLIT_BUILD_OUTPUTS" in verify_boot_text
        and "AWX_BUILD_HOST_ID" in verify_boot_text
    )
    verify_boot_has_gradle_home = "GRADLE_USER_HOME" in verify_boot_text and ".gradle-awx-desktop" in verify_boot_text
    verify_boot_has_project_cache = (
        "AWX_PROJECT_CACHE_DIR" in verify_boot_text
        and "ProjectCacheDir" in verify_boot_text
        and "awx-gradle-project-cache\\desktop" in verify_boot_text
    )
    verify_boot_has_project_cache_arg = (
        "--project-cache-dir" in verify_boot_text
        and "$ProjectCacheDir" in verify_boot_text
        and "$GradleArgs" in verify_boot_text
    )
    verify_boot_has_intentional_timeout_guard = (
        "$loggedBootRunFailure -and $completed" in verify_boot_text
        and "bootRun logged failure after intentional timeout stop" in verify_boot_text
    )
    verify_boot_has_boot_success_proof_guard = (
        "bootSuccessProven=$bootSuccessProven" in verify_boot_text
        and "bootSuccessProven=False" in verify_boot_text
    )
    verify_boot_has_boot_started_proof_guard = (
        "bootStartedProven=$bootStartedProven" in verify_boot_text
        and "Started .*LmsApplication" in verify_boot_text
        and "boot-started-proven" in verify_boot_text
    )
    verify_boot_has_application_ready_event_proof_guard = (
        "springStartedLogProven=$springStartedLogProven" in verify_boot_text
        and "applicationReadyEventProven=$applicationReadyEventProven" in verify_boot_text
        and "[AblationPenalty]" in verify_boot_text
        and "[SynergyBootstrapper]" in verify_boot_text
        and "application-ready-event-proven" in verify_boot_text
    )
    verify_boot_has_runtime_precheck_proof_guard = (
        "webServerStartedProven=$webServerStartedProven" in verify_boot_text
        and "runtimePrecheckProven=$runtimePrecheckProven" in verify_boot_text
        and "Tomcat started on port" in verify_boot_text
        and "[Precheck] done." in verify_boot_text
        and "runtime-precheck-proven" in verify_boot_text
    )
    verify_boot_has_probe_mode_guard = (
        "probeMode=$probeMode" in verify_boot_text
        and "blocker-scan-only" in verify_boot_text
    )
    add_check(
        checked,
        failures,
        "verify.boot-cache-isolation",
        (
            verify_boot_ps1.is_file()
            and verify_boot_has_awx_host
            and verify_boot_has_gradle_home
            and verify_boot_has_project_cache
            and verify_boot_has_project_cache_arg
            and verify_boot_has_intentional_timeout_guard
            and verify_boot_has_boot_success_proof_guard
            and verify_boot_has_boot_started_proof_guard
            and verify_boot_has_application_ready_event_proof_guard
            and verify_boot_has_runtime_precheck_proof_guard
            and verify_boot_has_probe_mode_guard
            and verify_boot_secret_hits == 0
        ),
        (
            f"verifyBootPs1={verify_boot_ps1.is_file()};"
            f"awxAgentHost={verify_boot_has_awx_host};"
            f"gradleUserHome={verify_boot_has_gradle_home};"
            f"projectCacheDir={verify_boot_has_project_cache};"
            f"projectCacheArg={verify_boot_has_project_cache_arg};"
            f"intentionalTimeoutStopGuard={verify_boot_has_intentional_timeout_guard};"
            f"bootSuccessProofGuard={verify_boot_has_boot_success_proof_guard};"
            f"bootStartedProofGuard={verify_boot_has_boot_started_proof_guard};"
            f"applicationReadyEventProofGuard={verify_boot_has_application_ready_event_proof_guard};"
            f"runtimePrecheckProofGuard={verify_boot_has_runtime_precheck_proof_guard};"
            f"probeModeGuard={verify_boot_has_probe_mode_guard};"
            f"rawSecretPatternHits={verify_boot_secret_hits}"
        ),
        "verify_boot.ps1 is missing Desktop host/cache isolation, project-cache-dir propagation, intentional timeout-stop handling, or boot-proof overclaim guard",
    )

    skill_ok = True
    missing_skill_docs: list[str] = []
    control_tower_skill_text, control_tower_skill_path = read_first_text([
        root / ".agents" / "skills" / "demo1-mcp-control-tower" / "SKILL.md",
        root / "data" / "agent-handoff" / "mcp-control-tower" / "skills" / "demo1-mcp-control-tower" / "SKILL.md",
    ])
    if not control_tower_skill_text:
        missing_skill_docs.append(".agents\\skills\\demo1-mcp-control-tower\\SKILL.md")
    for folder, expected_name in REQUIRED_TASK_SKILLS.items():
        text, skill_path = read_first_text([
            root / ".agents" / "skills" / folder / "SKILL.md",
            root / "data" / "agent-handoff" / "mcp-control-tower" / "skills" / folder / "SKILL.md",
        ])
        if not text:
            missing_skill_docs.append(f".agents\\skills\\{folder}\\SKILL.md")
            continue
        skill_ok = skill_ok and f"name: {expected_name}" in text and "scripts\\awx_mcp_toolbox.ps1" in text
    if missing_skill_docs:
        evidence_needed.append("repo-local task skill docs inaccessible or missing: " + ",".join(missing_skill_docs))
    add_check(
        checked,
        failures,
        "skills.task-launchers",
        skill_ok or bool(missing_skill_docs),
        ",".join(REQUIRED_TASK_SKILLS.values()) + (";evidence_needed=repo-local skill docs" if missing_skill_docs else ""),
        "task skills do not route through the toolbox launcher",
    )

    flow_tokens = [
        "Broad probe",
        "Focused probe",
        "Minimal diff",
        "Desktop verification",
        "Failure classification",
        "Retry",
        "source_scan",
        "patch_plan",
        "patch_render",
        "build_error_mine",
    ]
    add_check(
        checked,
        failures,
        "skills.safe-patch-flow",
        all(token in control_tower_skill_text for token in flow_tokens) if control_tower_skill_text else True,
        "broad-probe>focused-probe>minimal-diff>desktop-verification>failure-classification>retry"
        + (";evidence_needed=control tower skill doc" if not control_tower_skill_text else ""),
        "control-tower skill does not preserve the requested Safe Patch flow",
    )

    audit_fields = manifest.get("auditLogFields", [])
    add_check(
        checked,
        failures,
        "audit.redacted-fields",
        all(field in audit_fields for field in REQUIRED_AUDIT_FIELDS),
        ",".join(audit_fields),
        "audit log field allowlist is incomplete",
    )

    toolbox_text = read_text(root / "scripts" / "awx_mcp_toolbox.py")
    pipeline_runner_text = read_text(root / "scripts" / "awx_mcp_control_tower_pipeline.py")
    add_check(
        checked,
        failures,
        "toolbox.generic-audit-log",
        all(
            token in toolbox_text
            for token in (
                "def finalize(",
                'audit_log = payload.get("audit_log")',
                "append_audit(Path(audit_log), result)",
                "SAFE_AUDIT_FIELDS",
                "stable_hash(redact(payload))",
            )
        ),
        "finalize(audit_log)->append_audit allowlisted rows",
        "toolbox does not preserve generic redacted audit-log support",
    )
    add_check(
        checked,
        failures,
        "pipeline.mcp-control-tower-runner",
        bool(pipeline_runner_text)
        and "awx.mcp.control_tower_pipeline.v1" in pipeline_runner_text
        and "rawSecretPatternHits" in pipeline_runner_text
        and "desktopFinalProof" in pipeline_runner_text
        and "mcp_control_tower_pipeline" in toolbox_text,
        "scripts/awx_mcp_control_tower_pipeline.py + run_pipeline command",
        "run_pipeline does not expose a local MCP control-tower pipeline runner",
    )
    janitor_tests_text = read_text(root / "__patch_drop__" / "janitor_tests.ps1")
    add_check(
        checked,
        failures,
        "janitor.regression-tests",
        all(
            token in janitor_tests_text
            for token in (
                "missing-meta inventory reports MISSING_META",
                "filemode guard emits blocked",
                "sha mismatch guard emits mismatch",
                "report-only inventory marks supporting evidence",
                "real patchdrop untouched",
            )
        ),
        "janitor_tests.ps1 covers MISSING_META, filemode-blocked, sha mismatch, nested report-only visibility, and no-real-PatchDrop-mutation probes",
        "janitor regression probes for missing metadata, filemode blocking, or SHA mismatch are missing",
    )
    three_node_smoke_text = read_text(root / "__patch_drop__" / "three_node_patchdrop_smoke.ps1")
    add_check(
        checked,
        failures,
        "three-node.local-smoke",
        all(
            token in three_node_smoke_text
            for token in (
                "macmini-local-worktree",
                "notebook-local-worktree",
                "janitor_promote_producer_pending.ps1",
                "janitor_apply_one.ps1",
                "realPatchDropUntouched=",
                "[three-node-smoke][PASS]",
            )
        ),
        "three_node_patchdrop_smoke.ps1 simulated Desktop/Mac mini/Notebook producer-to-Desktop PatchDrop path without real PatchDrop mutation",
        "three-node local smoke script is missing producer worktree, promote/apply, or real-PatchDrop safety evidence",
    )
    safe_delete_summary = safe_delete_path_presence_summary(root)
    add_check(
        checked,
        failures,
        "safe-delete.path-presence",
        safe_delete_path_presence_ready(safe_delete_summary),
        safe_delete_path_presence_evidence(safe_delete_summary),
        "safe-delete path presence audit is missing, stale, mutating, or does not hold secret/report candidates",
    )
    source_governance_text = read_text(root / "scripts" / "source_governance_proof_chain_v4.ps1")
    add_check(
        checked,
        failures,
        "source-governance-stability-proof",
        all(
            token in source_governance_text
            for token in (
                "large-active-source-growth",
                "source-changed-during-proof",
                "sourceChangedDuringProofCount",
                "knownLargeSourceLineBaselines",
                "Compare-SourceFingerprintSnapshot",
            )
        ),
        "source_governance_proof_chain_v4.ps1 covers large-active-source-growth and source-changed-during-proof before broad Desktop proof",
        "source governance proof chain does not cover large source growth or source churn during proof",
    )
    source_edit_session_text, source_edit_session_reason = read_required_text(
        root / "__patch_drop__" / "source_edit_session.ps1",
        "source_edit_session",
    )
    janitor_inventory_text, janitor_inventory_reason = read_required_text(
        root / "__patch_drop__" / "janitor_inventory.ps1",
        "janitor_inventory",
    )
    patchdrop_readme_text, patchdrop_readme_reason = read_required_text(
        root / "__patch_drop__" / "README.md",
        "patchdrop_readme",
    )
    source_edit_guard_reasons = [
        reason
        for reason in (source_edit_session_reason, janitor_inventory_reason, patchdrop_readme_reason)
        if reason
    ]
    source_edit_guard_fail_reason = (
        "source edit session guard artifact access failed: " + ",".join(source_edit_guard_reasons)
        if source_edit_guard_reasons
        else "source edit session guard or source-edit-lock inventory evidence is missing"
    )
    if source_edit_guard_reasons:
        evidence_needed.append(
            "PatchDrop source edit guard artifact access failed: "
            + ",".join(source_edit_guard_reasons)
            + " / verify elevated Desktop ACL repair for C:/AbandonWare/demo-1/demo-1/src/__patch_drop__"
        )
    add_check(
        checked,
        failures,
        "source-edit-session-guard",
        all(
            token in source_edit_session_text
            for token in (
                "Test-RawSharedSourceRoot",
                "smb-direct-edit",
                "Get-DriveDisplayRoot",
                "DisplayRoot",
                "Set-RoleEnvironment",
                "AWX_SPLIT_BUILD_OUTPUTS",
                "GRADLE_USER_HOME",
            )
        )
        and "source-edit-locks" in janitor_inventory_text
        and "source_edit_session.ps1" in patchdrop_readme_text
        and "source-edit-locks" in patchdrop_readme_text,
        "source_edit_session.ps1 rejects shared and mapped PSDrive producer roots with smb-direct-edit, sets role-local cache env, and janitor_inventory reports source-edit-locks",
        source_edit_guard_fail_reason,
    )

    allowed_refs = manifest.get("secretPolicy", {}).get("allowedEnvRefs", [])
    add_check(
        checked,
        failures,
        "secrets.naver-env-names-only",
        sorted(allowed_refs) == sorted(ALLOWED_ENV_REFS),
        ",".join(allowed_refs),
        "NAVER env-name contract drifted",
    )

    add_check(
        checked,
        failures,
        "archive.index-path-resolution",
        bool(archive_index_source) and "archive_index" in {res.get("name") for res in manifest.get("resources", []) if isinstance(res, dict)},
        f"{archive_index_source};exists={archive_index.exists()}",
        "archive index resource or path resolution is missing",
    )

    add_check(
        checked,
        failures,
        "archive.restore-pre-review-checksum",
        all(
            token in toolbox_text
            for token in (
                '"preReview"',
                '"candidateCount"',
                '"checksumVerified"',
                "verify_log",
                "append_verify_log",
                "restore_target_blocked",
            )
        ),
        "archive_restore preReview/checksumVerified/verify_log/blocked guard",
        "archive_restore output does not prove pre-review, verify_log, and post-restore checksum evidence",
    )

    handoff_text = read_text(root / "scripts" / "awx_mcp_producer_handoff.py")
    add_check(
        checked,
        failures,
        "producer.smoke-before-bundle",
        "awx_mcp_node_smoke.py" in handoff_text and "desktopFinalProof" in handoff_text,
        "scripts/awx_mcp_producer_handoff.py",
        "producer handoff does not prove smoke-before-bundle and Desktop proof pending",
    )
    add_check(
        checked,
        failures,
        "producer.canonical-source-guard",
        all(
            token in handoff_text
            for token in (
                "is_under_canonical_source_root",
                '"smb-direct-edit"',
                '"not_run"',
                '"producer_handoff_failed"',
            )
        ),
        "scripts/awx_mcp_producer_handoff.py canonical-source guard",
        "producer handoff does not fail-close canonical Desktop source roots before smoke/bundle",
    )
    add_check(
        checked,
        failures,
        "producer.handoff-promotion-ready-json",
        all(
            token in handoff_text
            for token in (
                "read_manifest_summary",
                '"sourceIsolation"',
                '"promotionReady"',
                '"producer-source-isolation-violation"',
                '"producer-desktop-proof-not-pending"',
                '"sourceRootKind"',
                '"local-worktree"',
                '"desktopFinalProof"',
            )
        ),
        "producer handoff exposes sourceIsolation/promotionReady/Desktop-proof-pending fields",
        "producer handoff JSON does not prove promotion-ready source isolation contract",
    )
    add_check(
        checked,
        failures,
        "producer.handoff-audit-log",
        all(
            token in handoff_text
            for token in (
                "--audit-log",
                "SAFE_AUDIT_FIELDS",
                "append_audit_if_requested",
                '"toolName": "producer_handoff"',
                '"inputHash"',
                '"outputCount"',
            )
        ),
        "producer_handoff --audit-log appends allowlisted request/session/node/tool/input/output/elapsed/decision/failReason rows",
        "producer handoff does not prove redacted append-only audit-log support",
    )
    add_check(
        checked,
        failures,
        "producer.handoff-bundle-failure-classification",
        all(
            token in handoff_text
            for token in (
                "PRODUCER_FAIL_RE",
                "producer_fail_reason",
                '"producer-bundle-failed"',
                '"failReason": producer_fail',
            )
        ),
        "producer handoff propagates producer-local bundle failure classes into redacted failReason",
        "producer handoff does not preserve producer-local bundle failure classification",
    )

    producer_bundle_py_text = read_text(root / "__patch_drop__" / "producer_bundle.py")
    producer_bundle_ps_text = read_text(root / "__patch_drop__" / "producer_bundle.ps1")
    add_check(
        checked,
        failures,
        "producer.helpers-path-safety-guard",
        all(
            token in producer_bundle_py_text
            for token in (
                "patch_safety_failures",
                "FORBIDDEN_PATCH_PATH_PATTERNS",
                "filemode-blocked",
                "unsafe-path",
                "forbidden-path:",
                '"patchdrop"',
                '"__patch_drop__"',
            )
        )
        and all(
            token in producer_bundle_ps_text
            for token in (
                "Get-PatchSafetyFailures",
                "filemode-blocked",
                "unsafe-path",
                "forbidden-path:",
                '"patchdrop"',
                '"__patch_drop__"',
            )
        ),
        "producer-local bundle helpers reject PatchDrop source roots plus filemode, unsafe, and forbidden patch targets before writing sidecars",
        "producer-local bundle helpers do not fail-fast unsafe PatchDrop targets",
    )

    add_check(
        checked,
        failures,
        "producer.command-plan-dispatch",
        all(
            token in toolbox_text
            for token in (
                '"producer_command_plan"',
                '"desktop_dispatch_packet"',
                '"desktopEvidencePath"',
                '"allowedEnvRefs"',
                "mcp-control-tower",
            )
        ),
        "producer_command_plan + desktop_dispatch_packet desktop evidence path + env-name-only hints",
        "producer command plan does not expose a fixed Desktop handoff packet",
    )
    add_check(
        checked,
        failures,
        "producer.bundle-filemode-guard",
        all(
            token in toolbox_text
            for token in (
                "filemode_line_count(paths[\"patch\"])",
                '"filemodeLineCount"',
                '"filemode-blocked"',
            )
        )
        and '"filemodeLineCount"' in manifest_text,
        "producer bundle validation rejects filemode headers before external evidence is complete",
        "producer bundle validation does not fail-close filemode patch bodies",
    )
    add_check(
        checked,
        failures,
        "producer.bundle-unified-diff-guard",
        all(
            token in toolbox_text
            for token in (
                "diff_header_count(paths[\"patch\"])",
                '"diffHeaderCount"',
                '"producer-patch-not-unified-diff"',
            )
        )
        and "diffHeaderCount" in producer_bundle_py_text
        and "diffHeaderCount" in producer_bundle_ps_text
        and '"diffHeaderCount"' in manifest_text,
        "producer bundle validation rejects non-unified patch bodies before Desktop final apply",
        "producer bundle validation does not fail-close non-unified patch bodies",
    )
    add_check(
        checked,
        failures,
        "producer.bundle-forbidden-path-guard",
        all(
            token in toolbox_text
            for token in (
                "forbidden_patch_paths(paths[\"patch\"])",
                '"forbiddenPathCount"',
                '"forbidden-path:"',
                "FORBIDDEN_PATCH_PATH_PATTERNS",
            )
        )
        and '"forbiddenPathCount"' in manifest_text,
        "producer bundle validation rejects forbidden paths before Desktop final apply",
        "producer bundle validation does not fail-close forbidden patch paths",
    )
    add_check(
        checked,
        failures,
        "producer.bundle-unsafe-path-guard",
        all(
            token in toolbox_text
            for token in (
                "is_unsafe_patch_target",
                '"unsafe-path"',
                "target.startswith(\"/\")",
                "target.startswith(\"\\\\\\\\\")",
            )
        ),
        "producer bundle validation rejects traversal, absolute, and UNC patch targets",
        "producer bundle validation does not fail-close unsafe patch targets",
    )
    add_check(
        checked,
        failures,
        "desktop.dispatch-packet",
        "desktop_dispatch_packet" in tools
        and "desktopAuditCommand" in json.dumps(tools.get("desktop_dispatch_packet", {}), sort_keys=True)
        and '"desktop.dispatch_packet"' in json.dumps(tools.get("desktop_dispatch_packet", {}), sort_keys=True)
        and "write_dispatch" in json.dumps(tools.get("desktop_dispatch_packet", {}), sort_keys=True)
        and "dispatchArtifacts" in json.dumps(tools.get("desktop_dispatch_packet", {}), sort_keys=True),
        "desktop_dispatch_packet schema + Desktop audit command + optional PatchDrop dispatch artifacts",
        "Desktop dispatch packet tool/schema is missing",
    )
    add_check(
        checked,
        failures,
        "desktop.control-loop",
        "desktop_control_loop" in tools
        and '"desktop.control_loop"' in json.dumps(tools.get("desktop_control_loop", {}), sort_keys=True)
        and "source_scan" in toolbox_text
        and "desktop_dispatch_packet" in toolbox_text
        and "external_evidence_audit" in toolbox_text
        and "desktopFinalProof" in json.dumps(tools.get("desktop_control_loop", {}), sort_keys=True)
        and "localReady" in json.dumps(tools.get("desktop_control_loop", {}), sort_keys=True)
        and "completionReady" in json.dumps(tools.get("desktop_control_loop", {}), sort_keys=True)
        and "completionAuditNextActions" in json.dumps(tools.get("desktop_control_loop", {}), sort_keys=True)
        and "harmonyScan" in json.dumps(tools.get("desktop_control_loop", {}), sort_keys=True)
        and "harmonyScanNextActions" in json.dumps(tools.get("desktop_control_loop", {}), sort_keys=True),
        "desktop_control_loop combines source_scan + dispatch + external_evidence_audit with localReady/completionReady split, completionAuditNextActions, harmonyScan, harmonyScanNextActions, and Desktop final proof pending",
        "Desktop control loop tool/schema is missing",
    )
    add_check(
        checked,
        failures,
        "producer.kit-export",
        "producer_kit_export" in tools
        and '"producer.kit_export"' in json.dumps(tools.get("producer_kit_export", {}), sort_keys=True)
        and "producer_kit_export" in toolbox_text
        and "producer-kit" in toolbox_text
        and "producer-git-root-invalid" in toolbox_text
        and "rev-parse --show-toplevel" in toolbox_text
        and "producer-kit.manifest.json" in toolbox_text
        and "producer-kit-manifest-missing" in toolbox_text
        and "producer-kit-manifest-mismatch" in toolbox_text
        and "desktopFinalProof" in json.dumps(tools.get("producer_kit_export", {}), sort_keys=True),
        "producer_kit_export writes a redacted PatchDrop producer kit with installer git-root preflight, manifest verification, and Desktop proof pending",
        "producer kit export tool/schema is missing",
    )
    dispatch_summary = dispatch_artifact_summary(root)
    add_check(
        checked,
        failures,
        "desktop.dispatch-artifacts",
        bool(dispatch_summary.get("ok")),
        str(dispatch_summary.get("evidence", "")),
        str(dispatch_summary.get("failReason", "dispatch-artifacts-missing")),
    )
    add_check(
        checked,
        failures,
        "desktop.dispatch-handoff-proof",
        bool(dispatch_summary.get("handoffProofOk")),
        str(dispatch_summary.get("evidence", "")),
        "dispatch command files do not capture and validate producer_handoff JSON proof",
    )
    for item in dispatch_summary.get("evidence_needed", []):
        if item not in evidence_needed:
            evidence_needed.append(str(item))

    resource_names = {res.get("name") for res in manifest.get("resources", []) if isinstance(res, dict)}
    add_check(
        checked,
        failures,
        "resources.runners",
        {"tool_manifest", "mcp_stdio_server", "mcp_client_config", "mcp_node_setup_runner", "node_smoke_runner", "producer_handoff_runner", "completion_audit_runner", "producer_kit_dir"}.issubset(resource_names),
        ",".join(sorted(str(name) for name in resource_names)),
        "runner resources are incomplete",
    )
    client_config_resource = next(
        (res for res in manifest.get("resources", []) if isinstance(res, dict) and res.get("name") == "mcp_client_config"),
        {},
    )
    client_config_path = root / str(client_config_resource.get("path") or "")
    client_config_text = read_text(client_config_path)
    client_config = load_json(client_config_path)
    mcp_servers = client_config.get("mcpServers") if isinstance(client_config.get("mcpServers"), dict) else {}
    client_server = mcp_servers.get("awx-control-tower") if isinstance(mcp_servers.get("awx-control-tower"), dict) else {}
    client_args = client_server.get("args") if isinstance(client_server.get("args"), list) else []
    client_command = str(client_server.get("command") or "")
    client_args_text = " ".join(str(arg) for arg in client_args)
    client_allowed_env_refs = (
        client_config.get("allowedEnvRefs")
        if isinstance(client_config.get("allowedEnvRefs"), list)
        else []
    )
    client_has_supabase_env_refs = (
        "SUPABASE_PROJECT_REF" in client_allowed_env_refs
        and "SUPABASE_ACCESS_TOKEN" in client_allowed_env_refs
    )
    client_secret_hits = len(SECRET_PATTERN.findall(client_config_text))
    add_check(
        checked,
        failures,
        "mcp.client-config",
        (
            "mcp_client_config" in resource_names
            and client_config_path.is_file()
            and "python" in client_command.lower()
            and "scripts/awx_mcp_stdio_server.py" in client_args_text.replace("\\", "/")
            and client_has_supabase_env_refs
            and client_secret_hits == 0
        ),
        (
            f"command={client_command};args={client_args_text};"
            f"supabaseEnvRefs={client_has_supabase_env_refs};"
            f"rawSecretPatternHits={client_secret_hits}"
        ),
        "mcp client config is missing or unsafe",
    )
    agent_db_tool = tools.get("agent_db_snapshot", {})
    agent_db_tool_text = json.dumps(agent_db_tool, sort_keys=True)
    stdio_text_for_agent_db = read_text(root / "scripts" / "awx_mcp_stdio_server.py")
    agent_db_contract_text = "\n".join(
        [
            agent_db_tool_text,
            toolbox_text,
            stdio_text_for_agent_db,
        ]
    )
    agent_db_contract_secret_hits = len(SECRET_PATTERN.findall(agent_db_contract_text))
    agent_db_has_root_input = "root" in agent_db_tool.get("input_schema", {}).get("properties", {})
    agent_db_has_local_fallback_schema = "localFallback" in agent_db_tool.get("output_schema", {}).get("properties", {})
    agent_db_has_toolbox_fallback = (
        "local_agent_db_snapshot" in toolbox_text
        and "agent_db_snapshot_unavailable_with_local_fallback" in toolbox_text
        and "agent_db_snapshot_http_error" in toolbox_text
    )
    agent_db_has_jpa_surface_fallback = (
        "jpa_surface_summary" in toolbox_text
        and "subsystem_persistence_summary" in toolbox_text
        and "failure_pattern_memory_summary" in toolbox_text
        and "Spring runtime /agent/db-context response for live database snapshot" in toolbox_text
    )
    add_check(
        checked,
        failures,
        "agent.db-snapshot",
        (
            "agent_db_snapshot" in tools
            and agent_db_tool.get("readOnly") is True
            and "agent.db_snapshot" in agent_db_tool.get("aliases", [])
            and "python scripts/awx_mcp_toolbox.py agent_db_snapshot" in agent_db_tool_text
            and agent_db_has_root_input
            and agent_db_has_local_fallback_schema
            and agent_db_has_toolbox_fallback
            and agent_db_has_jpa_surface_fallback
            and "agent_db_snapshot" in stdio_text_for_agent_db
            and agent_db_contract_secret_hits == 0
        ),
        (
            f"registered={'agent_db_snapshot' in tools};"
            f"readOnly={agent_db_tool.get('readOnly')};"
            f"alias={'agent.db_snapshot' in agent_db_tool.get('aliases', [])};"
            f"manifestRootInput={agent_db_has_root_input};"
            f"manifestLocalFallback={agent_db_has_local_fallback_schema};"
            f"toolboxLocalFallback={agent_db_has_toolbox_fallback};"
            f"jpaSurfaceFallback={agent_db_has_jpa_surface_fallback};"
            f"stdioHandler={'agent_db_snapshot' in stdio_text_for_agent_db};"
            f"rawSecretPatternHits={agent_db_contract_secret_hits}"
        ),
        "Agent DB snapshot probe is missing, writable, lacks local fallback/JPA surface evidence, lacks stdio routing, or is unsafe",
    )
    trace_tool = tools.get("trace_snapshot_probe", {})
    trace_tool_text = json.dumps(trace_tool, sort_keys=True)
    stdio_text_for_trace = read_text(root / "scripts" / "awx_mcp_stdio_server.py")
    trace_contract_text = "\n".join(
        [
            trace_tool_text,
            toolbox_text,
            stdio_text_for_trace,
        ]
    )
    trace_contract_secret_hits = len(SECRET_PATTERN.findall(trace_contract_text))
    trace_has_local_fallback_schema = "localFallback" in trace_tool.get("output_schema", {}).get("properties", {})
    trace_has_toolbox_fallback = (
        "local_trace_snapshot_fallback" in toolbox_text
        and "trace_snapshot_unavailable_with_local_fallback" in toolbox_text
        and "trace_snapshot_http_error_with_local_fallback" in toolbox_text
    )
    add_check(
        checked,
        failures,
        "trace.snapshot-probe",
        (
            "trace_snapshot_probe" in tools
            and trace_tool.get("readOnly") is True
            and "trace.snapshot" in trace_tool.get("aliases", [])
            and "python scripts/awx_mcp_toolbox.py trace_snapshot_probe" in trace_tool_text
            and trace_has_local_fallback_schema
            and trace_has_toolbox_fallback
            and "trace_snapshot_probe" in stdio_text_for_trace
            and trace_contract_secret_hits == 0
        ),
        (
            f"registered={'trace_snapshot_probe' in tools};"
            f"readOnly={trace_tool.get('readOnly')};"
            f"alias={'trace.snapshot' in trace_tool.get('aliases', [])};"
            f"manifestLocalFallback={trace_has_local_fallback_schema};"
            f"toolboxLocalFallback={trace_has_toolbox_fallback};"
            f"stdioHandler={'trace_snapshot_probe' in stdio_text_for_trace};"
            f"rawSecretPatternHits={trace_contract_secret_hits}"
        ),
        "Trace snapshot probe is missing, writable, lacks local fallback contract, lacks stdio routing, or is unsafe",
    )
    harmony_tool = tools.get("harmony_scan", {})
    harmony_tool_text = json.dumps(harmony_tool, sort_keys=True)
    harmony_input_props = harmony_tool.get("input_schema", {}).get("properties", {})
    harmony_output_props = harmony_tool.get("output_schema", {}).get("properties", {})
    harmony_contract_text = "\n".join([harmony_tool_text, toolbox_text])
    harmony_contract_secret_hits = len(SECRET_PATTERN.findall(harmony_contract_text))
    harmony_has_runtime_inputs = all(
        key in harmony_input_props
        for key in (
            "runtimeProof",
            "runtime_probe",
            "runtime_base_url",
            "trace_base_url",
            "runtime_timeout_sec",
            "runtime_trace_limit",
        )
    )
    harmony_has_runtime_outputs = all(
        key in harmony_output_props
        for key in (
            "runtimeProof",
            "runtimeProofSource",
            "runtimeProofDetails",
            "runtimeProofArtifactPath",
            "runtimeProofArtifactHash",
            "runtimeProofArtifactBytes",
        )
    )
    harmony_has_live_probe = (
        "harmony_live_runtime_proof" in toolbox_text
        and "AWX_AGENT_DB_CONTEXT_BASE_URL" in toolbox_text
        and "AWX_TRACE_SNAPSHOT_BASE_URL" in toolbox_text
        and "agent_db_snapshot(" in toolbox_text
        and "trace_snapshot_probe(" in toolbox_text
        and "agent_db_snapshot_loaded" in toolbox_text
        and "trace_snapshot_loaded" in toolbox_text
    )
    add_check(
        checked,
        failures,
        "harmony.runtime-proof-probe",
        (
            "harmony_scan" in tools
            and harmony_tool.get("readOnly") is True
            and harmony_has_runtime_inputs
            and harmony_has_runtime_outputs
            and harmony_has_live_probe
            and harmony_contract_secret_hits == 0
        ),
        (
            f"registered={'harmony_scan' in tools};"
            f"readOnly={harmony_tool.get('readOnly')};"
            f"runtimeInputs={harmony_has_runtime_inputs};"
            f"runtimeProofSource={'runtimeProofSource' in harmony_output_props};"
            f"runtimeProofDetails={'runtimeProofDetails' in harmony_output_props};"
            f"runtimeProofArtifactPath={'runtimeProofArtifactPath' in harmony_output_props};"
            f"runtimeBaseUrlEnv={'AWX_AGENT_DB_CONTEXT_BASE_URL' in toolbox_text};"
            f"traceBaseUrlEnv={'AWX_TRACE_SNAPSHOT_BASE_URL' in toolbox_text};"
            f"liveProbe={harmony_has_live_probe};"
            f"rawSecretPatternHits={harmony_contract_secret_hits}"
        ),
        "Harmony scan runtime proof probe is missing live env support, manifest contract, or secret-safe source evidence",
    )
    supabase_config = supabase_mcp_root_config_summary(root)
    if supabase_config.get("serverPresent") and not supabase_config.get("projectScopedMode"):
        evidence_needed.append(
            "Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project"
        )
    add_check(
        checked,
        failures,
        "supabase.mcp-config",
        (
            supabase_config.get("present")
            and supabase_config.get("serverPresent")
            and supabase_config.get("type") == "http"
            and supabase_config.get("host") == "mcp.supabase.com"
            and supabase_config.get("path") == "/mcp"
            and supabase_config.get("readOnlyMode")
            and int(supabase_config.get("rawSecretPatternHits", 0) or 0) == 0
        ),
        (
            f"type={supabase_config.get('type', '')};"
            f"host={supabase_config.get('host', '')};"
            f"path={supabase_config.get('path', '')};"
            f"readOnlyMode={supabase_config.get('readOnlyMode')};"
            f"rawSecretPatternHits={supabase_config.get('rawSecretPatternHits', 0)}"
        ),
        "Supabase MCP root config is missing, writable, or unsafe",
    )
    add_check(
        checked,
        failures,
        "supabase.mcp-scope",
        (
            supabase_config.get("serverPresent")
            and supabase_config.get("featuresScopedMode")
            and int(supabase_config.get("rawSecretPatternHits", 0) or 0) == 0
        ),
        (
            f"featuresScopedMode={supabase_config.get('featuresScopedMode')};"
            f"featureGroups={','.join(supabase_config.get('featureGroups') or [])};"
            f"projectScopedMode={supabase_config.get('projectScopedMode')};"
            f"project_refPresent={supabase_config.get('projectScopedMode')};"
            f"projectRefTemplateMode={supabase_config.get('projectRefTemplateMode')};"
            f"projectRefEnvPresent={supabase_config.get('projectRefEnvPresent')};"
            f"projectScopeSource={supabase_config.get('projectScopeSource')};"
            f"projectScopeNextAction={supabase_config.get('projectScopeNextAction')};"
            f"scopedMcpUrlTemplate={bool(supabase_config.get('scopedMcpUrlTemplate'))};"
            f"rawSecretPatternHits={supabase_config.get('rawSecretPatternHits', 0)}"
        ),
        "Supabase MCP feature scope is missing, broad, or unsafe",
    )
    toolbox_text = read_text(root / "scripts" / "awx_mcp_toolbox.py")
    tool_manifest_text = read_text(root / "main" / "resources" / "mcp" / "awx-control-tower-tools.json")
    supabase_plan_secret_hits = len(SECRET_PATTERN.findall(toolbox_text + "\n" + tool_manifest_text))
    supabase_plan_section = toolbox_text.split("def supabase_db_snapshot_plan", 1)[-1].split(
        "def supabase_cli_summary", 1
    )[0]
    supabase_has_data_api_grants = "data_api_role_grants" in supabase_plan_section and "role_table_grants" in supabase_plan_section
    supabase_has_tables_without_rls = (
        "exposed_tables_without_rls" in supabase_plan_section and "rowsecurity" in supabase_plan_section
    )
    supabase_has_metadata_policy_risk = (
        "rls_user_metadata_policies" in supabase_plan_section
        and "user_metadata" in supabase_plan_section
        and "raw_user_meta_data" in supabase_plan_section
    )
    supabase_has_update_select_policy_gap = (
        "update_policies_without_select_policy" in supabase_plan_section
        and "UPDATE" in supabase_plan_section
        and "SELECT" in supabase_plan_section
    )
    supabase_has_storage_upsert_policy_gap = (
        "storage_upsert_policy_gaps" in supabase_plan_section
        and "storage" in supabase_plan_section
        and "objects" in supabase_plan_section
        and "INSERT" in supabase_plan_section
        and "UPDATE" in supabase_plan_section
    )
    supabase_has_security_invoker_views = (
        "views_missing_security_invoker" in supabase_plan_section and "security_invoker=true" in supabase_plan_section
    )
    supabase_has_security_definer_functions = (
        "exposed_security_definer_functions" in supabase_plan_section and "prosecdef" in supabase_plan_section
    )
    supabase_has_cli_query_min_version = (
        "cliVersionRequirements" in supabase_plan_section
        and "supabase db query" in supabase_plan_section
        and ">=2.79.0" in supabase_plan_section
    )
    supabase_has_cli_advisors_min_version = (
        "cliVersionRequirements" in supabase_plan_section
        and "supabase db advisors" in supabase_plan_section
        and ">=2.81.3" in supabase_plan_section
    )
    supabase_has_mcp_fallback_contract = (
        "cliFallbackContract" in supabase_plan_section
        and "cli_missing_or_below_minimum" in supabase_plan_section
        and "execute_sql" in supabase_plan_section
        and "get_advisors" in supabase_plan_section
    )
    supabase_has_plan = (
        "dbSnapshotPlan" in toolbox_text
        and "dbSnapshotPlan" in tool_manifest_text
        and "information_schema.tables" in toolbox_text
        and "pg_policies" in toolbox_text
        and "pg_extension" in toolbox_text
        and supabase_has_data_api_grants
        and supabase_has_tables_without_rls
        and supabase_has_metadata_policy_risk
        and supabase_has_update_select_policy_gap
        and supabase_has_storage_upsert_policy_gap
        and supabase_has_security_invoker_views
        and supabase_has_security_definer_functions
        and supabase_has_cli_query_min_version
        and supabase_has_cli_advisors_min_version
        and supabase_has_mcp_fallback_contract
        and "supabase db query --help" in toolbox_text
        and "supabase db advisors --help" in toolbox_text
        and "apply_migration" not in supabase_plan_section
        and supabase_plan_secret_hits == 0
    )
    add_check(
        checked,
        failures,
        "supabase.schema-snapshot-plan",
        supabase_has_plan,
        (
            f"dbSnapshotPlan={'dbSnapshotPlan' in toolbox_text and 'dbSnapshotPlan' in tool_manifest_text};"
            f"information_schema={'information_schema.tables' in toolbox_text};"
            f"policies={'pg_policies' in toolbox_text};"
            f"extensions={'pg_extension' in toolbox_text};"
            f"dataApiGrants={supabase_has_data_api_grants};"
            f"tablesWithoutRls={supabase_has_tables_without_rls};"
            f"metadataPolicyRisk={supabase_has_metadata_policy_risk};"
            f"updateSelectPolicyGap={supabase_has_update_select_policy_gap};"
            f"storageUpsertPolicyGap={supabase_has_storage_upsert_policy_gap};"
            f"securityInvokerViews={supabase_has_security_invoker_views};"
            f"securityDefinerFunctions={supabase_has_security_definer_functions};"
            f"cliQueryMinVersion={supabase_has_cli_query_min_version};"
            f"cliAdvisorsMinVersion={supabase_has_cli_advisors_min_version};"
            f"mcpFallbackContract={supabase_has_mcp_fallback_contract};"
            f"advisorHelp={'supabase db advisors --help' in toolbox_text};"
            f"rawSecretPatternHits={supabase_plan_secret_hits}"
        ),
        "Supabase read-only schema snapshot plan is missing or unsafe",
    )
    stdio_text_for_supabase = read_text(root / "scripts" / "awx_mcp_stdio_server.py")
    supabase_snapshot_tool = tools.get("supabase_schema_snapshot", {})
    supabase_snapshot_tool_text = json.dumps(supabase_snapshot_tool, sort_keys=True)
    supabase_snapshot_output_schema = (
        supabase_snapshot_tool.get("output_schema")
        if isinstance(supabase_snapshot_tool.get("output_schema"), dict)
        else {}
    )
    supabase_snapshot_output_props = (
        supabase_snapshot_output_schema.get("properties")
        if isinstance(supabase_snapshot_output_schema.get("properties"), dict)
        else {}
    )
    supabase_snapshot_has_api_keys_docs_schema = "apiKeysDocs" in supabase_snapshot_output_props
    supabase_snapshot_has_secret_keys_backend_schema = "secretKeysBackendOnly" in supabase_snapshot_output_props
    supabase_snapshot_has_docs_contract_schema = (
        "docsRefs" in supabase_snapshot_output_props
        and "securityContracts" in supabase_snapshot_output_props
    )
    supabase_snapshot_has_cli_contract_schema = (
        "cliVersionRequirements" in supabase_snapshot_output_props
        and "cliFallbackContract" in supabase_snapshot_output_props
    )
    supabase_import_tool = tools.get("supabase_schema_snapshot_import", {})
    supabase_import_tool_text = json.dumps(supabase_import_tool, sort_keys=True)
    supabase_import_stores_raw_rows = not (
        "storedRawRows" in supabase_import_tool_text
        and "rowHashes" in toolbox_text
        and "snapshots" in toolbox_text
    )
    supabase_import_has_input_shape = (
        "inputShape" in supabase_import_tool_text
        and "supabase_payload_shape" in toolbox_text
        and "supportedResultItemCount" in toolbox_text
    )
    supabase_import_has_missing_result_summary = (
        "missingResultCount" in supabase_import_tool_text
        and "missingResultNames" in supabase_import_tool_text
        and "supabase_expected_result_names" in toolbox_text
        and "supabase_missing_result_names" in toolbox_text
    )
    supabase_import_has_unexpected_result_summary = (
        "unexpectedResultCount" in supabase_import_tool_text
        and "unexpectedResultNames" in supabase_import_tool_text
        and "supabase_unexpected_result_names" in toolbox_text
        and "supabase_unexpected_result_evidence" in toolbox_text
    )
    supabase_import_has_duplicate_result_summary = (
        "duplicateResultCount" in supabase_import_tool_text
        and "duplicateResultNames" in supabase_import_tool_text
        and "supabase_duplicate_result_names" in toolbox_text
        and "supabase_duplicate_result_evidence" in toolbox_text
    )
    supabase_import_has_mcp_error_summary = (
        "mcpErrorCount" in supabase_import_tool_text
        and "mcpErrorCodes" in supabase_import_tool_text
        and "supabase_mcp_error_summaries" in toolbox_text
        and "supabase_mcp_error_evidence" in toolbox_text
    )
    add_check(
        checked,
        failures,
        "supabase.schema-snapshot-tool",
        (
            "supabase_schema_snapshot" in tools
            and supabase_snapshot_tool.get("readOnly") is True
            and "supabase.schema_snapshot" in supabase_snapshot_tool.get("aliases", [])
            and "python scripts/awx_mcp_toolbox.py supabase_schema_snapshot" in supabase_snapshot_tool_text
            and "supabase_schema_snapshot" in toolbox_text
            and "supabase_schema_snapshot" in stdio_text_for_supabase
            and supabase_snapshot_has_docs_contract_schema
            and supabase_snapshot_has_cli_contract_schema
            and supabase_snapshot_has_api_keys_docs_schema
            and supabase_snapshot_has_secret_keys_backend_schema
            and supabase_plan_secret_hits == 0
        ),
        (
            f"supabase_schema_snapshot={'supabase_schema_snapshot' in tools};"
            f"readOnly={supabase_snapshot_tool.get('readOnly')};"
            f"stdioHandler={'supabase_schema_snapshot' in stdio_text_for_supabase};"
            f"docsContractSchema={supabase_snapshot_has_docs_contract_schema};"
            f"cliVersionRequirementsSchema={'cliVersionRequirements' in supabase_snapshot_output_props};"
            f"cliFallbackContractSchema={'cliFallbackContract' in supabase_snapshot_output_props};"
            f"apiKeysDocsSchema={supabase_snapshot_has_api_keys_docs_schema};"
            f"secretKeysBackendOnlySchema={supabase_snapshot_has_secret_keys_backend_schema};"
            f"rawSecretPatternHits={supabase_plan_secret_hits}"
        ),
        "Supabase schema snapshot tool is missing, writable, or unsafe",
    )
    add_check(
        checked,
        failures,
        "supabase.schema-snapshot-import-tool",
        (
            "supabase_schema_snapshot_import" in tools
            and supabase_import_tool.get("readOnly") is True
            and "supabase.schema_snapshot_import" in supabase_import_tool.get("aliases", [])
            and "python scripts/awx_mcp_toolbox.py supabase_schema_snapshot_import" in supabase_import_tool_text
            and "supabase_schema_snapshot_import" in toolbox_text
            and "supabase_schema_snapshot_import" in stdio_text_for_supabase
            and "storedRawRows" in supabase_import_tool_text
            and not supabase_import_stores_raw_rows
            and supabase_import_has_input_shape
            and supabase_import_has_missing_result_summary
            and supabase_import_has_unexpected_result_summary
            and supabase_import_has_duplicate_result_summary
            and supabase_import_has_mcp_error_summary
            and supabase_plan_secret_hits == 0
        ),
        (
            f"supabase_schema_snapshot_import={'supabase_schema_snapshot_import' in tools};"
            f"readOnly={supabase_import_tool.get('readOnly')};"
            f"stdioHandler={'supabase_schema_snapshot_import' in stdio_text_for_supabase};"
            f"storedRawRows={supabase_import_stores_raw_rows};"
            f"inputShape={supabase_import_has_input_shape};"
            f"missingResultSummary={supabase_import_has_missing_result_summary};"
            f"unexpectedResultSummary={supabase_import_has_unexpected_result_summary};"
            f"duplicateResultSummary={supabase_import_has_duplicate_result_summary};"
            f"mcpErrorSummary={supabase_import_has_mcp_error_summary};"
            f"rawSecretPatternHits={supabase_plan_secret_hits}"
        ),
        "Supabase schema snapshot import tool is missing, writable, raw-row storing, shape-blind, result-set blind, or unsafe",
    )
    supabase_artifact = supabase_schema_snapshot_artifact_summary(root)
    add_check(
        checked,
        failures,
        "supabase.schema-snapshot-artifact",
        supabase_schema_snapshot_artifact_ready(supabase_artifact),
        (
            f"schemaVersion={supabase_artifact.get('schemaVersion')};"
            f"readOnly={supabase_artifact.get('readOnly')};"
            f"mutationAllowed={supabase_artifact.get('mutationAllowedValue')};"
            f"dbSnapshotPlan={supabase_artifact.get('hasSnapshotPlan')};"
            f"docsRefs={supabase_artifact.get('hasDocsRefs')};"
            f"apiKeysDocs={supabase_artifact.get('hasApiKeysDocs')};"
            f"breakingChanges={supabase_artifact.get('hasBreakingChanges')};"
            f"authPlan={supabase_artifact.get('hasAuthPlan')};"
            f"defaultHostedAuthMode={supabase_artifact.get('defaultHostedAuthMode')};"
            f"supportedAuthModes={supabase_artifact.get('supportedAuthModes')};"
            f"mcpOAuthRequired={supabase_artifact.get('mcpOAuthRequired')};"
            f"manualAuthMode={supabase_artifact.get('manualAuthMode')};"
            f"manualAuthEnvStatus={supabase_artifact.get('manualAuthEnvStatus')};"
            f"manualAuthSensitiveEnvRefs={supabase_artifact.get('manualAuthSensitiveEnvRefs')};"
            f"securityWarnings={supabase_artifact.get('hasAuthSecurityWarnings')};"
            f"securityContracts={supabase_artifact.get('hasSecurityContracts')};"
            f"secretKeysBackendOnly={supabase_artifact.get('hasSecretKeysBackendOnlyContract')};"
            f"sqlBundle={supabase_artifact.get('sqlBundlePresent')};"
            f"sqlBundleReadOnly={supabase_artifact.get('sqlBundleReadOnly')};"
            f"sqlBundleDocs={supabase_artifact.get('sqlBundleDocs')};"
            f"sqlBundleBreakingChange={supabase_artifact.get('sqlBundleBreakingChange')};"
            f"sqlBundleSecurityContracts={supabase_artifact.get('sqlBundleSecurityContracts')};"
            f"resultTemplate={supabase_artifact.get('resultTemplatePresent')};"
            f"resultTemplateSchema={supabase_artifact.get('resultTemplateSchema')};"
            f"resultTemplateReadOnly={supabase_artifact.get('resultTemplateReadOnly')};"
            f"resultTemplateImportTool={supabase_artifact.get('resultTemplateImportTool')};"
            f"resultTemplateCollectionPlan={supabase_artifact.get('resultTemplateCollectionPlan')};"
            f"resultTemplateCollectionPlanReadOnly={supabase_artifact.get('resultTemplateCollectionPlanReadOnly')};"
            f"resultTemplateCollectionPlanMcpTool={supabase_artifact.get('resultTemplateCollectionPlanMcpTool')};"
            f"resultTemplateCollectionPlanEnvNames={supabase_artifact.get('resultTemplateCollectionPlanEnvNames')};"
            f"resultTemplateCollectionPlanImportTool={supabase_artifact.get('resultTemplateCollectionPlanImportTool')};"
            f"advisorsCollectionPlan={supabase_artifact.get('advisorsCollectionPlan')};"
            f"advisorsCollectionPlanReadOnly={supabase_artifact.get('advisorsCollectionPlanReadOnly')};"
            f"advisorsCollectionPlanMcpTool={supabase_artifact.get('advisorsCollectionPlanMcpTool')};"
            f"advisorsCollectionPlanEnvNames={supabase_artifact.get('advisorsCollectionPlanEnvNames')};"
            f"resultTemplateAdvisorCollectionPlan={supabase_artifact.get('resultTemplateAdvisorCollectionPlan')};"
            f"resultTemplateAdvisorCollectionMcpTool={supabase_artifact.get('resultTemplateAdvisorCollectionMcpTool')};"
            f"resultTemplateAdvisorCollectionEnvNames={supabase_artifact.get('resultTemplateAdvisorCollectionEnvNames')};"
            f"resultTemplateSupportedResultShapes={supabase_artifact.get('resultTemplateSupportedResultShapes')};"
            f"collectionPacket={supabase_artifact.get('collectionPacketPresent')};"
            f"collectionPacketSchema={supabase_artifact.get('collectionPacketSchema')};"
            f"collectionPacketReadOnly={supabase_artifact.get('collectionPacketReadOnly')};"
            f"collectionPacketMcpTool={supabase_artifact.get('collectionPacketMcpTool')};"
            f"collectionPacketEnvNames={supabase_artifact.get('collectionPacketEnvNames')};"
            f"collectionPacketImportTool={supabase_artifact.get('collectionPacketImportTool')};"
            f"collectionPacketAdvisorCollection={supabase_artifact.get('collectionPacketAdvisorCollection')};"
            f"collectionPacketAdvisorCollectionMcpTool={supabase_artifact.get('collectionPacketAdvisorCollectionMcpTool')};"
            f"collectionPacketAdvisorCollectionEnvNames={supabase_artifact.get('collectionPacketAdvisorCollectionEnvNames')};"
            f"collectionPacketExecutionMode={supabase_artifact.get('collectionPacketExecutionMode')};"
            f"collectionPacketQueryPayloads={supabase_artifact.get('collectionPacketQueryPayloads')};"
            f"planSqlQueryCount={supabase_artifact.get('planSqlQueryCount')};"
            f"sqlBundleQueryCount={supabase_artifact.get('sqlBundleQueryCount')};"
            f"resultTemplateQueryCount={supabase_artifact.get('resultTemplateQueryCount')};"
            f"collectionPacketQueryCount={supabase_artifact.get('collectionPacketQueryCount')};"
            f"planDuplicateQueryNames={supabase_artifact.get('planDuplicateQueryNames')};"
            f"sqlBundleDuplicateQueryNames={supabase_artifact.get('sqlBundleDuplicateQueryNames')};"
            f"resultTemplateDuplicateQueryNames={supabase_artifact.get('resultTemplateDuplicateQueryNames')};"
            f"collectionPacketDuplicateQueryNames={supabase_artifact.get('collectionPacketDuplicateQueryNames')};"
            f"resultTemplateMissingQueryNames={supabase_artifact.get('resultTemplateMissingQueryNames')};"
            f"sqlBundleMissingQueryNames={supabase_artifact.get('sqlBundleMissingQueryNames')};"
            f"collectionPacketMissingQueryNames={supabase_artifact.get('collectionPacketMissingQueryNames')};"
            f"collectionPacketMutationQueryCount={supabase_artifact.get('collectionPacketMutationQueryCount')};"
            f"collectionPacketMutationQueryNames={supabase_artifact.get('collectionPacketMutationQueryNames')};"
            f"snapshotCount={supabase_artifact.get('snapshotCount', 0)};"
            f"evidenceNeededCount={supabase_artifact.get('evidenceNeededCount', 0)};"
            f"bytes={supabase_artifact.get('pathLength', 0)};"
            f"sqlBundleBytes={supabase_artifact.get('sqlBundleLength', 0)};"
            f"resultTemplateBytes={supabase_artifact.get('resultTemplateLength', 0)};"
            f"collectionPacketBytes={supabase_artifact.get('collectionPacketLength', 0)};"
            f"pathHash={str(supabase_artifact.get('pathHash', ''))[:12]};"
            f"sqlBundleHash={str(supabase_artifact.get('sqlBundlePathHash', ''))[:12]};"
            f"resultTemplateHash={str(supabase_artifact.get('resultTemplatePathHash', ''))[:12]};"
            f"collectionPacketHash={str(supabase_artifact.get('collectionPacketPathHash', ''))[:12]};"
            f"rawSecretPatternHits={supabase_artifact.get('rawSecretPatternHits', 0)}"
        ),
        f"Supabase schema snapshot artifact is missing, mutable, stale, incomplete, or unsafe: {supabase_schema_snapshot_artifact_ready_fail_reason(supabase_artifact)}",
    )
    supabase_smoke = supabase_readonly_snapshot_smoke_summary(root)
    add_check(
        checked,
        failures,
        "supabase.readonly-snapshot-smoke",
        supabase_readonly_snapshot_smoke_ready(supabase_smoke),
        (
            f"script={supabase_smoke.get('scriptPresent')};"
            f"contextProbe={supabase_smoke.get('scriptHasContextProbe')};"
            f"snapshotTool={supabase_smoke.get('scriptHasSnapshotTool')};"
            f"importTool={supabase_smoke.get('scriptHasImportTool')};"
            f"requireProjectScope={supabase_smoke.get('scriptHasRequireProjectScope')};"
            f"skipNetworkProbe={supabase_smoke.get('scriptHasSkipNetworkProbe')};"
            f"reportsMissingResultNames={supabase_smoke.get('scriptReportsMissingResultNames')};"
            f"reportsDataApiEvidenceMissing={supabase_smoke.get('scriptReportsDataApiEvidenceMissing')};"
            f"reportsEnvPreflight={supabase_smoke.get('scriptReportsEnvPreflight')};"
            f"artifactDir={supabase_smoke.get('artifactDirPresent')};"
            f"artifactFileCount={supabase_smoke.get('artifactFileCount')};"
            f"summaryArtifact={supabase_smoke.get('summaryArtifactPresent')};"
            f"summaryGeneratedAt={supabase_smoke.get('summaryGeneratedAt')};"
            f"summaryFresh={supabase_smoke.get('summaryFresh')};"
            f"summaryFreshnessStatus={supabase_smoke.get('summaryFreshnessStatus')};"
            f"summaryAgeSeconds={supabase_smoke.get('summaryAgeSeconds')};"
            f"summaryOk={supabase_smoke.get('summaryOk')};"
            f"summaryDecision={supabase_smoke.get('summaryDecision')};"
            f"summarySecretHits={supabase_smoke.get('summarySecretHits', -1)};"
            f"summaryRawSecretPatternHits={supabase_smoke.get('summaryRawSecretPatternHits', -1)};"
            f"docsRefCount={supabase_smoke.get('docsRefCount')};"
            f"securityContractCount={supabase_smoke.get('securityContractCount')};"
            f"cliQueryMinVersion={supabase_smoke.get('cliQueryMinVersion')};"
            f"cliAdvisorsMinVersion={supabase_smoke.get('cliAdvisorsMinVersion')};"
            f"mcpFallbackContract={supabase_smoke.get('cliQueryFallbackTool') == 'execute_sql' and supabase_smoke.get('cliAdvisorsFallbackTool') == 'get_advisors'};"
            f"dataApiGrantProofRequired={supabase_smoke.get('dataApiGrantProofRequired')};"
            f"rlsPolicyProofRequired={supabase_smoke.get('rlsPolicyProofRequired')};"
            f"apiKeysDocs={supabase_smoke.get('apiKeysDocs')};"
            f"secretKeysBackendOnly={supabase_smoke.get('secretKeysBackendOnly')};"
            f"envPresentCount={supabase_smoke.get('envPresentCount')};"
            f"projectRefEnvPresent={supabase_smoke.get('projectRefEnvPresent')};"
            f"accessTokenEnvPresent={supabase_smoke.get('accessTokenEnvPresent')};"
            f"cliPresent={supabase_smoke.get('cliPresent')};"
            f"contextEvidenceNeededCount={supabase_smoke.get('contextEvidenceNeededCount')};"
            f"contextDecision={supabase_smoke.get('contextDecision')};"
            f"mcpReachable={supabase_smoke.get('mcpReachable')};"
            f"mcpHttpStatus={supabase_smoke.get('mcpHttpStatus')};"
            f"mcpUnauthenticatedExpected={supabase_smoke.get('mcpUnauthenticatedExpected')};"
            f"mcpProbeSkipped={supabase_smoke.get('mcpProbeSkipped')};"
            f"mcpDecision={supabase_smoke.get('mcpDecision')};"
            f"projectScopeStatus={supabase_smoke.get('projectScopeStatus')};"
            f"snapshotDecision={supabase_smoke.get('snapshotDecision')};"
            f"schemaSnapshotAvailable={supabase_smoke.get('schemaSnapshotAvailable')};"
            f"mutationAllowed={supabase_smoke.get('snapshotMutationAllowedValue')};"
            f"importDecision={supabase_smoke.get('importDecision')};"
            f"importSchemaSnapshotComplete={supabase_smoke.get('importSchemaSnapshotComplete')};"
            f"importResultSetComplete={supabase_smoke.get('importResultSetComplete')};"
            f"importedResultCount={supabase_smoke.get('importedResultCount')};"
            f"artifactReadOnly={supabase_smoke.get('snapshotArtifactReadOnly')};"
            f"artifactMutationAllowed={supabase_smoke.get('snapshotArtifactMutationBlocked') is False};"
            f"artifactDirHash={str(supabase_smoke.get('artifactDirHash', ''))[:12]};"
            f"rawSecretPatternHits={supabase_smoke.get('rawSecretPatternHits', 0)};"
            f"summaryHighConfidenceSecretHits={supabase_smoke.get('summaryHighConfidenceSecretHits', 0)};"
            f"bearerPatternHits={supabase_smoke.get('bearerPatternHits', 0)};"
            f"rawJdbcUrlHits={supabase_smoke.get('rawJdbcUrlHits', 0)};"
            f"summaryRawJdbcUrlHits={supabase_smoke.get('summaryRawJdbcUrlHits', 0)};"
            f"windowsAbsPathHits={supabase_smoke.get('windowsAbsPathHits', 0)};"
            f"mutationAllowedTrueHits={supabase_smoke.get('mutationAllowedTrueHits', 0)}"
        ),
        f"Supabase readonly snapshot smoke wrapper/artifacts missing or unsafe: {supabase_readonly_snapshot_smoke_ready_fail_reason(supabase_smoke)}",
    )
    db_gap_report = db_gap_report_artifact_summary(root)
    add_check(
        checked,
        failures,
        "db.structure-gap-report",
        db_gap_report_ready(db_gap_report),
        (
            f"subsystems={db_gap_report.get('subsystemCount', 0)};"
            f"actionRequired={db_gap_report.get('actionRequiredCount')};"
            f"critical={db_gap_report.get('criticalCount')};"
            f"medium={db_gap_report.get('mediumCount')};"
            f"review_runtime_only={db_gap_report.get('reviewRuntimeOnlyCount')};"
            f"resolved_jpa={db_gap_report.get('resolvedJpaCount')};"
            f"residualVolatileStateCount={db_gap_report.get('residualVolatileStateCount')};"
            f"residualVolatileStateIds={db_gap_report.get('residualVolatileStateIds')};"
            f"externalSupabaseSnapshot={db_gap_report.get('externalSupabaseSnapshot')};"
            f"externalSupabaseReadOnly={db_gap_report.get('externalSupabaseReadOnly')};"
            f"externalSupabaseMutationAllowed={db_gap_report.get('externalSupabaseMutationAllowedValue')};"
            f"externalSupabaseSqlBundle={db_gap_report.get('externalSupabaseSqlBundle')};"
            f"externalSupabaseCollectionPacket={db_gap_report.get('externalSupabaseCollectionPacket')};"
            f"externalSupabaseCollectionPacketStatus={db_gap_report.get('externalSupabaseCollectionPacketStatus')};"
            f"externalSupabaseCollectionPacketTool={db_gap_report.get('externalSupabaseCollectionPacketTool')};"
            f"externalSupabaseCollectionPacketReadOnly={db_gap_report.get('externalSupabaseCollectionPacketReadOnly')};"
            f"externalSupabaseCollectionPacketMutationAllowed={db_gap_report.get('externalSupabaseCollectionPacketMutationAllowedValue')};"
            f"externalSupabaseCollectionPacketQueryCount={db_gap_report.get('externalSupabaseCollectionPacketQueryCount')};"
            f"externalSupabaseCollectionPacketDeclaredQueryCount={db_gap_report.get('externalSupabaseCollectionPacketDeclaredQueryCount')};"
            f"externalSupabaseCollectionPacketQueryCountMismatch={db_gap_report.get('externalSupabaseCollectionPacketQueryCountMismatch')};"
            f"externalSupabaseCollectionPacketNextActions={db_gap_report.get('externalSupabaseCollectionPacketNextActions')};"
            f"externalSupabaseCollectionPacketSecretHits={db_gap_report.get('externalSupabaseCollectionPacketSecretHits')};"
            f"externalSupabaseAdvisors={db_gap_report.get('externalSupabaseAdvisors')};"
            f"externalSupabaseAdvisorsStatus={db_gap_report.get('externalSupabaseAdvisorsStatus')};"
            f"externalSupabaseAdvisorsAvailable={db_gap_report.get('externalSupabaseAdvisorsAvailable')};"
            f"externalSupabaseAdvisorRowCount={db_gap_report.get('externalSupabaseAdvisorRowCount')};"
            f"externalSupabaseAdvisorErrorCount={db_gap_report.get('externalSupabaseAdvisorErrorCount')};"
            f"externalSupabaseAdvisorWarningCount={db_gap_report.get('externalSupabaseAdvisorWarningCount')};"
            f"externalSupabaseAdvisorCategories={db_gap_report.get('externalSupabaseAdvisorCategories')};"
            f"externalSupabaseAdvisorCollectionTool={db_gap_report.get('externalSupabaseAdvisorCollectionTool')};"
            f"externalSupabaseAdvisorFeatureGroup={db_gap_report.get('externalSupabaseAdvisorFeatureGroup')};"
            f"externalSupabaseRiskSummary={db_gap_report.get('externalSupabaseRiskSummary')};"
            f"externalSupabaseRiskFindingCount={db_gap_report.get('externalSupabaseRiskFindingCount')};"
            f"externalSupabaseRiskFindingKeys={db_gap_report.get('externalSupabaseRiskFindingKeys')};"
            f"externalSupabaseAuthPlan={db_gap_report.get('externalSupabaseAuthPlan')};"
            f"externalSupabaseDocsContracts={db_gap_report.get('externalSupabaseDocsContracts')};"
            f"externalSupabaseDocsRefCount={db_gap_report.get('externalSupabaseDocsRefCount')};"
            f"externalSupabaseSecurityContractCount={db_gap_report.get('externalSupabaseSecurityContractCount')};"
            f"generatedAt={db_gap_report.get('generatedAt')};"
            f"fresh={db_gap_report.get('fresh')};"
            f"freshnessStatus={db_gap_report.get('freshnessStatus')};"
            f"ageSeconds={db_gap_report.get('ageSeconds')};"
            f"externalSupabaseApiKeysDocs={db_gap_report.get('externalSupabaseApiKeysDocs')};"
            f"externalSupabaseSecretKeysBackendOnly={db_gap_report.get('externalSupabaseSecretKeysBackendOnly')};"
            f"externalSupabaseEvidenceNeededCount={db_gap_report.get('externalSupabaseEvidenceNeededCount')};"
            f"externalSupabaseAuthRequired={db_gap_report.get('externalSupabaseAuthRequired')};"
            f"externalSupabaseCliMissing={db_gap_report.get('externalSupabaseCliMissing')};"
            f"externalSupabaseDefaultHostedAuthMode={db_gap_report.get('externalSupabaseDefaultHostedAuthMode')};"
            f"externalSupabaseSupportedAuthModes={db_gap_report.get('externalSupabaseSupportedAuthModes')};"
            f"externalSupabaseMcpOAuthRequired={db_gap_report.get('externalSupabaseMcpOAuthRequired')};"
            f"externalSupabaseManualAuthMode={db_gap_report.get('externalSupabaseManualAuthMode')};"
            f"externalSupabaseManualAuthSensitiveEnvRefs={db_gap_report.get('externalSupabaseManualAuthSensitiveEnvRefs')};"
            f"externalSupabaseSnapshotImportStatus={db_gap_report.get('externalSupabaseSnapshotImportStatus')};"
            f"externalSupabaseSnapshotImportedCount={db_gap_report.get('externalSupabaseSnapshotImportedCount')};"
            f"externalSupabaseMissingResultCount={db_gap_report.get('externalSupabaseMissingResultCount')};"
            f"externalSupabaseMissingResultNames={db_gap_report.get('externalSupabaseMissingResultNames')};"
            f"externalSupabaseUnexpectedResultCount={db_gap_report.get('externalSupabaseUnexpectedResultCount')};"
            f"externalSupabaseUnexpectedResultNames={db_gap_report.get('externalSupabaseUnexpectedResultNames')};"
            f"externalSupabaseDuplicateResultCount={db_gap_report.get('externalSupabaseDuplicateResultCount')};"
            f"externalSupabaseDuplicateResultNames={db_gap_report.get('externalSupabaseDuplicateResultNames')};"
            f"externalSupabaseInputShape={db_gap_report.get('externalSupabaseInputShape')};"
            f"externalSupabaseSupportedResultItemCount={db_gap_report.get('externalSupabaseSupportedResultItemCount')};"
            f"externalSupabaseProjectScopeStatus={db_gap_report.get('externalSupabaseProjectScopeStatus')};"
            f"externalSupabaseProjectScopeNextAction={db_gap_report.get('externalSupabaseProjectScopeNextAction')};"
            f"externalSupabaseProjectRefPresent={db_gap_report.get('externalSupabaseProjectRefPresent')};"
            f"externalSupabaseProjectRefTemplateMode={db_gap_report.get('externalSupabaseProjectRefTemplateMode')};"
            f"bytes={db_gap_report.get('pathLength', 0)};"
            f"pathHash={str(db_gap_report.get('pathHash', ''))[:12]};"
            f"rawSecretPatternHits={db_gap_report.get('rawSecretPatternHits', 0)}"
        ),
        f"DB structure gap report is missing, stale, actionable, or unsafe: {db_gap_report_ready_fail_reason(db_gap_report)}",
    )
    source_health_scorecard = source_health_scorecard_artifact_summary(root)
    add_check(
        checked,
        failures,
        "source.health-scorecard",
        source_health_scorecard_ready(source_health_scorecard),
        (
            f"decision={source_health_scorecard.get('decision')};"
            f"strictEvidenceAdjustedScore={source_health_scorecard.get('strictEvidenceAdjustedScore')};"
            f"evidenceNeededCount={source_health_scorecard.get('evidenceNeededCount')};"
            f"generatedAt={source_health_scorecard.get('generatedAt')};"
            f"fresh={source_health_scorecard.get('fresh')};"
            f"freshnessStatus={source_health_scorecard.get('freshnessStatus')};"
            f"ageSeconds={source_health_scorecard.get('ageSeconds')};"
            f"nextSingleAction={source_health_scorecard.get('nextSingleAction')};"
            f"nextSourceAction={source_health_scorecard.get('nextSourceAction')};"
            f"completionAudit={source_health_scorecard.get('completionAudit')};"
            f"nextActionDetailsCount={source_health_scorecard.get('nextActionDetailsCount')};"
            f"supabaseLiveProofDetail={source_health_scorecard.get('supabaseLiveProofDetail')};"
            f"supabaseLiveProofReadOnly={source_health_scorecard.get('supabaseLiveProofReadOnly')};"
            f"supabaseLiveProofMutationBlocked={source_health_scorecard.get('supabaseLiveProofMutationBlocked')};"
            f"supabaseLiveProofMcpEndpointTemplate={source_health_scorecard.get('supabaseLiveProofMcpEndpointTemplate')};"
            f"requiredEnv={source_health_scorecard.get('requiredEnv')};"
            f"requiredMcpTools={source_health_scorecard.get('requiredMcpTools')};"
            f"requiredResultNames={source_health_scorecard.get('requiredResultNameCount')};"
            f"queryCount={source_health_scorecard.get('queryCount')};"
            f"applyCollectedEvidenceCommand={source_health_scorecard.get('applyCollectedEvidenceCommand')};"
            f"externalProducerProofDetailCount={source_health_scorecard.get('externalProducerProofDetailCount')};"
            f"externalProducerProofRoles={source_health_scorecard.get('externalProducerProofRoles')};"
            f"externalProducerProofSidecars={source_health_scorecard.get('externalProducerProofSidecars')};"
            f"externalProducerProofSourceIsolation={source_health_scorecard.get('externalProducerProofSourceIsolation')};"
            f"externalProducerProofApplyCommand={source_health_scorecard.get('externalProducerProofApplyCommand')};"
            f"externalProducerProofNextActions={source_health_scorecard.get('externalProducerProofNextActions')};"
            f"externalProducerProofCommandTemplates={source_health_scorecard.get('externalProducerProofCommandTemplates')};"
            f"resultPathRecommendation={source_health_scorecard.get('resultPathRecommendation')};"
            f"advisorResultPathRecommendation={source_health_scorecard.get('advisorResultPathRecommendation')};"
            f"nextSourceActionDetailsCount={source_health_scorecard.get('nextSourceActionDetailsCount')};"
            f"sourceContractDetail={source_health_scorecard.get('sourceContractDetail')};"
            f"sourceContractReadOnly={source_health_scorecard.get('sourceContractReadOnly')};"
            f"sourceContractMutationBlocked={source_health_scorecard.get('sourceContractMutationBlocked')};"
            f"sourceContractFocusedTests={source_health_scorecard.get('sourceContractFocusedTestCount')};"
            f"sourceContractCommands={source_health_scorecard.get('sourceContractCommandCount')};"
            f"sourceContractTraceKeys={source_health_scorecard.get('sourceContractTraceKeyCount')};"
            f"sourceContractProofPassed={source_health_scorecard.get('sourceContractProofPassed')};"
            f"sourceContractProofPassedCount={source_health_scorecard.get('sourceContractProofPassedCount')};"
            f"sourceContractProofRequiredCount={source_health_scorecard.get('sourceContractProofRequiredCount')};"
            f"broadRuntimeTestProofPassed={source_health_scorecard.get('broadRuntimeTestProofPassed')};"
            f"broadRuntimeTestSuites={source_health_scorecard.get('broadRuntimeTestSuiteCount')};"
            f"broadRuntimeTests={source_health_scorecard.get('broadRuntimeTestCount')};"
            f"broadRuntimeFailures={source_health_scorecard.get('broadRuntimeTestFailureCount')};"
            f"broadRuntimeErrors={source_health_scorecard.get('broadRuntimeTestErrorCount')};"
            f"bytes={source_health_scorecard.get('pathLength', 0)};"
            f"pathHash={str(source_health_scorecard.get('pathHash', ''))[:12]};"
            f"rawSecretPatternHits={source_health_scorecard.get('rawSecretPatternHits', 0)};"
            f"nextActionDetailsWindowsAbsPathHits={source_health_scorecard.get('nextActionDetailsWindowsAbsPathHits', 0)};"
            f"nextSourceActionDetailsWindowsAbsPathHits={source_health_scorecard.get('nextSourceActionDetailsWindowsAbsPathHits', 0)}"
        ),
        (
            "Source health scorecard artifact is missing, does not preserve the structured "
            f"Supabase live-proof next action, or is unsafe: {source_health_scorecard_ready_fail_reason(source_health_scorecard)}"
        ),
    )
    goal_next_packet = goal_next_command_packet_summary(root)
    add_check(
        checked,
        failures,
        "goal-next.command-packet",
        goal_next_command_packet_ready(goal_next_packet),
        (
            f"latestPresent={goal_next_packet.get('latestPresent')};"
            f"latestGeneratedAt={goal_next_packet.get('latestGeneratedAt')};"
            f"latestFresh={goal_next_packet.get('latestFresh')};"
            f"latestFreshnessStatus={goal_next_packet.get('latestFreshnessStatus')};"
            f"latestAgeSeconds={goal_next_packet.get('latestAgeSeconds')};"
            f"commandPacketPresent={goal_next_packet.get('commandPacketPresent')};"
            f"commandPacketMarkdownPresent={goal_next_packet.get('commandPacketMarkdownPresent')};"
            f"schemaVersion={goal_next_packet.get('schemaVersion')};"
            f"decision={goal_next_packet.get('decision')};"
            f"topic={goal_next_packet.get('topic')};"
            f"commandCount={goal_next_packet.get('commandCount')};"
            f"lanes={goal_next_packet.get('lanes')};"
            f"producerRoles={goal_next_packet.get('producerRoles')};"
            f"supabaseEnvNames={goal_next_packet.get('supabaseEnvNames')};"
            f"supabaseCommand={goal_next_packet.get('supabaseCommand')};"
            f"externalDesktopCommand={goal_next_packet.get('externalDesktopCommand')};"
            f"producerPlaceholders={goal_next_packet.get('producerPlaceholders')};"
            f"supabaseSmokePresent={goal_next_packet.get('supabaseSmokePresent')};"
            f"supabaseSmokeMcpDecision={goal_next_packet.get('supabaseSmokeMcpDecision')};"
            f"supabaseSmokeProjectScopeStatus={goal_next_packet.get('supabaseSmokeProjectScopeStatus')};"
            f"supabaseReadOnlyContract={goal_next_packet.get('supabaseReadOnlyContract')};"
            f"supabaseMcpEndpointTemplate={goal_next_packet.get('supabaseMcpEndpointTemplate')};"
            f"supabaseDocsRefs={goal_next_packet.get('supabaseDocsRefs')};"
            f"computerUsePresent={goal_next_packet.get('computerUsePresent')};"
            f"computerUseDecision={goal_next_packet.get('computerUseDecision')};"
            f"computerUseOk={goal_next_packet.get('computerUseOk')};"
            f"computerUseReachable={goal_next_packet.get('computerUseReachable')};"
            f"computerUseStale={goal_next_packet.get('computerUseStale')};"
            f"computerUseAppCount={goal_next_packet.get('computerUseAppCount')};"
            f"bytes={goal_next_packet.get('pathLength', 0)};"
            f"pathHash={str(goal_next_packet.get('pathHash', ''))[:12]};"
            f"commandWindowsAbsPathHits={goal_next_packet.get('commandWindowsAbsPathHits', 0)};"
            f"rawSecretPatternHits={goal_next_packet.get('rawSecretPatternHits', 0)}"
        ),
        (
            "goal-next-auto command packet missing, stale, incomplete, or unsafe: "
            f"{goal_next_command_packet_ready_fail_reason(goal_next_packet)}"
        ),
    )
    websoak_provider_smoke = websoak_provider_disabled_artifact_summary(root)
    add_check(
        checked,
        failures,
        "websoak.provider-disabled-runtime-smoke",
        websoak_provider_disabled_ready(websoak_provider_smoke),
        (
            f"present={websoak_provider_smoke.get('present')};"
            f"summaryPresent={websoak_provider_smoke.get('summaryPresent')};"
            f"ok={websoak_provider_smoke.get('ok')};"
            f"status={websoak_provider_smoke.get('status')};"
            f"providerDisabledOrSkipped={websoak_provider_smoke.get('providerDisabledOrSkipped')};"
            f"providerDisabledCount={websoak_provider_smoke.get('providerDisabledCount')};"
            f"providerStates={websoak_provider_smoke.get('providerStates')};"
            f"outCount={websoak_provider_smoke.get('outCount')};"
            f"rawInputCount={websoak_provider_smoke.get('rawInputCount')};"
            f"cacheOnly.merged.count={websoak_provider_smoke.get('cacheOnlyMergedCount')};"
            f"rescueMerge.used={websoak_provider_smoke.get('rescueMergeUsed')};"
            f"starvationFallback.trigger={websoak_provider_smoke.get('starvationTrigger')};"
            f"bytes={websoak_provider_smoke.get('pathLength', 0)};"
            f"pathHash={str(websoak_provider_smoke.get('pathHash', ''))[:12]};"
            f"summaryPathHash={str(websoak_provider_smoke.get('summaryPathHash', ''))[:12]};"
            f"secretPatternHits={websoak_provider_smoke.get('secretPatternHits')};"
            f"rawQueryHits={websoak_provider_smoke.get('rawQueryHits')};"
            f"probeKeyHits={websoak_provider_smoke.get('probeKeyHits')};"
            f"rawSecretPatternHits={websoak_provider_smoke.get('rawSecretPatternHits')}"
        ),
        (
            "WebSoak provider-disabled runtime smoke artifact missing, stale, or unsafe: "
            f"{websoak_provider_disabled_ready_fail_reason(websoak_provider_smoke)}"
        ),
    )
    chat_debug_readback = chat_debug_events_readback_artifact_summary(root)
    add_check(
        checked,
        failures,
        "chat-debug-events.readback-runtime-smoke",
        chat_debug_events_readback_ready(chat_debug_readback),
        (
            f"script={chat_debug_readback.get('scriptPresent')};"
            f"endpoint={chat_debug_readback.get('scriptHasDiagnosticsEndpoint')};"
            f"streamTrigger={chat_debug_readback.get('scriptHasStreamTrigger')};"
            f"readbackMetadata={chat_debug_readback.get('scriptHasReadbackMetadata')};"
            f"present={chat_debug_readback.get('present')};"
            f"summaryPresent={chat_debug_readback.get('summaryPresent')};"
            f"ok={chat_debug_readback.get('ok')};"
            f"streamStatus={chat_debug_readback.get('streamStatus')};"
            f"readbackStatus={chat_debug_readback.get('readbackStatus')};"
            f"readbackContentType={chat_debug_readback.get('readbackContentType')};"
            f"readbackEventCount={chat_debug_readback.get('readbackEventCount')};"
            f"operatorDebugEventPresent={chat_debug_readback.get('operatorDebugEventPresent')};"
            f"probe={chat_debug_readback.get('probe')};"
            f"fingerprint={chat_debug_readback.get('fingerprint')};"
            f"stage={chat_debug_readback.get('stage')};"
            f"failureClass={chat_debug_readback.get('failureClass')};"
            f"triggerReason={chat_debug_readback.get('triggerReason')};"
            f"nextAction={chat_debug_readback.get('nextAction')};"
            f"ageSeconds={chat_debug_readback.get('ageSeconds')};"
            f"fresh={chat_debug_readback.get('fresh')};"
            f"bytes={chat_debug_readback.get('pathLength', 0)};"
            f"pathHash={str(chat_debug_readback.get('pathHash', ''))[:12]};"
            f"summaryPathHash={str(chat_debug_readback.get('summaryPathHash', ''))[:12]};"
            f"scriptSecretPatternHits={chat_debug_readback.get('scriptSecretPatternHits')};"
            f"secretPatternHits={chat_debug_readback.get('secretPatternHits')};"
            f"rawPromptHits={chat_debug_readback.get('rawPromptHits')};"
            f"rawModelHits={chat_debug_readback.get('rawModelHits')};"
            f"rawSecretPatternHits={chat_debug_readback.get('rawSecretPatternHits')}"
        ),
        (
            "Chat DebugEvent readback runtime smoke artifact missing, stale, or unsafe: "
            f"{chat_debug_events_readback_ready_fail_reason(chat_debug_readback)}"
        ),
    )
    node_setup_resource = next(
        (res for res in manifest.get("resources", []) if isinstance(res, dict) and res.get("name") == "mcp_node_setup_runner"),
        {},
    )
    node_setup_path = root / str(node_setup_resource.get("path") or "")
    node_setup_text = read_text(node_setup_path)
    node_setup_secret_hits = len(SECRET_PATTERN.findall(node_setup_text))
    add_check(
        checked,
        failures,
        "mcp.node-setup-runner",
        (
            "mcp_node_setup_runner" in resource_names
            and node_setup_path.is_file()
            and "sourceIsolation" in node_setup_text
            and "awx_mcp_stdio_server.py" in node_setup_text
            and "rev-parse" in node_setup_text
            and "--show-toplevel" in node_setup_text
            and "gitRootMatchesSourceRoot" in node_setup_text
            and "not-git-root" in node_setup_text
            and node_setup_secret_hits == 0
        ),
        f"path={node_setup_resource.get('path', '')};git-root guard;rawSecretPatternHits={node_setup_secret_hits}",
        "mcp node setup runner is missing or unsafe",
    )
    add_check(
        checked,
        failures,
        "mcp.node-setup-audit-log",
        (
            "--audit-log" in node_setup_text
            and "append_audit" in node_setup_text
            and all(field in node_setup_text for field in REQUIRED_AUDIT_FIELDS)
            and "sourceRootHash" not in node_setup_text.split("def append_audit", 1)[-1]
            and node_setup_secret_hits == 0
        ),
        "awx_mcp_node_setup.py supports append-only allowlisted audit rows",
        "mcp node setup audit logging is missing or unsafe",
    )
    node_smoke_resource = next(
        (res for res in manifest.get("resources", []) if isinstance(res, dict) and res.get("name") == "node_smoke_runner"),
        {},
    )
    node_smoke_path = root / str(node_smoke_resource.get("path") or "")
    node_smoke_text = read_text(node_smoke_path)
    node_smoke_secret_hits = len(SECRET_PATTERN.findall(node_smoke_text))
    add_check(
        checked,
        failures,
        "mcp.node-smoke-runner",
        (
            "node_smoke_runner" in resource_names
            and node_smoke_path.is_file()
            and "sourceIsolation" in node_smoke_text
            and "source-isolation-violation" in node_smoke_text
            and "rev-parse" in node_smoke_text
            and "--show-toplevel" in node_smoke_text
            and "gitRootMatchesSourceRoot" in node_smoke_text
            and "not-git-root" in node_smoke_text
            and "agent_db_snapshot" in node_smoke_text
            and "trace_snapshot_probe" in node_smoke_text
            and "supabase_context_probe" in node_smoke_text
            and "localFallbackPresent" in node_smoke_text
            and "AWX_AGENT_DB_CONTEXT_BASE_URL" in node_smoke_text
            and "AWX_TRACE_SNAPSHOT_BASE_URL" in node_smoke_text
            and node_smoke_secret_hits == 0
        ),
        (
            f"path={node_smoke_resource.get('path', '')};git-root guard;"
            f"agentDbSnapshot={'agent_db_snapshot' in node_smoke_text};"
            f"traceSnapshotProbe={'trace_snapshot_probe' in node_smoke_text};"
            f"supabaseContextProbe={'supabase_context_probe' in node_smoke_text};"
            f"localFallbackMarker={'localFallbackPresent' in node_smoke_text};"
            f"runtimeBaseUrlEnv={'AWX_AGENT_DB_CONTEXT_BASE_URL' in node_smoke_text and 'AWX_TRACE_SNAPSHOT_BASE_URL' in node_smoke_text};"
            f"rawSecretPatternHits={node_smoke_secret_hits}"
        ),
        "mcp node smoke runner is missing or unsafe",
    )
    stdio_text = read_text(root / "scripts" / "awx_mcp_stdio_server.py")
    add_check(
        checked,
        failures,
        "mcp.stdio-bridge",
        "tools/list" in stdio_text
        and "tools/call" in stdio_text
        and "resources/list" in stdio_text
        and "resources/read" in stdio_text
        and "prompts/list" in stdio_text
        and "prompts/get" in stdio_text
        and "awx_mcp_toolbox" in stdio_text,
        "awx_mcp_stdio_server.py exposes tools/resources/prompts list/read/get methods and delegates to toolbox",
        "MCP stdio bridge is missing or incomplete",
    )
    add_check(
        checked,
        failures,
        "mcp.prompt-get-role-briefs",
        all(
            token in stdio_text
            for token in (
                "render_prompt_text",
                "producer-local",
                "PatchDrop",
                "awx-control-tower-mcp-client.sample.json",
                "Desktop final proof",
            )
        ),
        "prompts/get renders actionable role briefs for producer-local PatchDrop work",
        "MCP prompts/get does not return actionable role instructions",
    )
    add_check(
        checked,
        failures,
        "external.node-evidence-auditor",
        "external_evidence_intake" in tools
        and "external_evidence_audit" in tools
        and "external_evidence_dir" in resource_names
        and "nextActions" in json.dumps(tools.get("external_evidence_audit", {}), sort_keys=True),
        "external_evidence_intake/external_evidence_audit + nextActions + data/agent-handoff/mcp-control-tower",
        "external node evidence auditor or handoff resource is missing",
    )
    external_audit_text = json.dumps(tools.get("external_evidence_audit", {}), sort_keys=True)
    external_intake_text = json.dumps(tools.get("external_evidence_intake", {}), sort_keys=True)
    add_check(
        checked,
        failures,
        "external.producer-handoff-proof",
        "producerHandoffs" in external_audit_text
        and "producerHandoffs" in external_intake_text
        and "validate_producer_handoff_evidence" in toolbox_text,
        "external_evidence_audit/intake validate producer handoff JSON in addition to sidecars",
        "producer handoff proof is not a first-class external evidence gate",
    )
    external_apply_wrapper_text = read_text(root / "scripts" / "external_apply_collected_evidence.ps1")
    external_apply_wrapper_test_text = read_text(root / "scripts" / "external_apply_collected_evidence_tests.ps1")
    external_apply_wrapper_secret_hits = len(
        SECRET_PATTERN.findall(external_apply_wrapper_text + "\n" + external_apply_wrapper_test_text)
    )
    add_check(
        checked,
        failures,
        "external.apply-collected-evidence-wrapper",
        (
            "external_evidence_intake" in external_apply_wrapper_text
            and "external_evidence_audit" in external_apply_wrapper_text
            and "awx_mcp_completion_audit.py" in external_apply_wrapper_text
            and "externalEvidenceComplete" in external_apply_wrapper_text
            and "external-apply-collected.summary.json" in external_apply_wrapper_text
            and "secret-leak-risk" in external_apply_wrapper_text
            and "external_apply_collected_evidence.ps1" in external_apply_wrapper_test_text
            and "uses absolute patchdrop path" in external_apply_wrapper_test_text
            and "complete summary reports ok" in external_apply_wrapper_test_text
            and "partial summary reports evidence_needed" in external_apply_wrapper_test_text
            and "secret summary reports leak risk" in external_apply_wrapper_test_text
            and "secret hit exits leak risk" in external_apply_wrapper_test_text
            and external_apply_wrapper_secret_hits == 0
        ),
        (
            "external_evidence_intake=True;"
            "external_evidence_audit=True;"
            f"completionAudit={'awx_mcp_completion_audit.py' in external_apply_wrapper_text};"
            f"summaryArtifact={'external-apply-collected.summary.json' in external_apply_wrapper_text};"
            f"rawSecretPatternHits={external_apply_wrapper_secret_hits}"
        ),
        "external collected-evidence wrapper is missing or does not fail closed safely",
    )
    supabase_apply_wrapper_text = read_text(root / "scripts" / "supabase_apply_collected_evidence.ps1")
    supabase_apply_wrapper_test_text = read_text(root / "scripts" / "supabase_apply_collected_evidence_tests.ps1")
    supabase_apply_wrapper_secret_hits = len(
        SECRET_PATTERN.findall(supabase_apply_wrapper_text + "\n" + supabase_apply_wrapper_test_text)
    )
    add_check(
        checked,
        failures,
        "supabase.apply-collected-evidence-wrapper",
        (
            "supabase_schema_snapshot_import" in supabase_apply_wrapper_text
            and "db_gap_scanner.py" in supabase_apply_wrapper_text
            and "source_health_scorecard.py" in supabase_apply_wrapper_text
            and "awx_mcp_completion_audit.py" in supabase_apply_wrapper_text
            and "supabase-apply-collected.summary.json" in supabase_apply_wrapper_text
            and "secret-leak-risk" in supabase_apply_wrapper_text
            and "supabase_apply_collected_evidence.ps1" in supabase_apply_wrapper_test_text
            and "apply collected evidence exits evidence_needed for partial import" in supabase_apply_wrapper_test_text
            and "apply writes db gap output under provided root" in supabase_apply_wrapper_test_text
            and "apply writes completion audit artifact" in supabase_apply_wrapper_test_text
            and "summary reports ok" in supabase_apply_wrapper_test_text
            and "partial summary reports evidence_needed" in supabase_apply_wrapper_test_text
            and supabase_apply_wrapper_secret_hits == 0
        ),
        (
            "supabase_schema_snapshot_import=True;"
            f"dbGapScanner={'db_gap_scanner.py' in supabase_apply_wrapper_text};"
            f"sourceHealthScorecard={'source_health_scorecard.py' in supabase_apply_wrapper_text};"
            f"completionAudit={'awx_mcp_completion_audit.py' in supabase_apply_wrapper_text};"
            f"summaryArtifact={'supabase-apply-collected.summary.json' in supabase_apply_wrapper_text};"
            f"rawSecretPatternHits={supabase_apply_wrapper_secret_hits}"
        ),
        "Supabase collected-evidence wrapper is missing or does not fail closed safely",
    )
    add_check(
        checked,
        failures,
        "external.apply-gate-next-action",
        all(
            token in external_audit_text and token in external_intake_text
            for token in (
                "proofOnlyIntake",
                "oneAcceptedBundleAtATime",
                "activeTopLevelGuard",
                "inventoryCommand",
                "promoteCommands",
                "applyCommand",
                "desktopFinalProof",
            )
        )
        and "janitor apply gate" in external_audit_text
        and "run-desktop-janitor-apply-gate" in toolbox_text
        and "janitor_apply_gate_next_action" in toolbox_text
        and "janitor_apply_one.ps1" in toolbox_text
        and "active-top-level-exists" in toolbox_text,
        "external_evidence_audit/intake expose a proof-only Desktop janitor apply gate nextAction after producer proof completes",
        "external evidence nextActions do not expose the Desktop janitor apply gate contract",
    )

    prompt_names = {prompt.get("name") for prompt in manifest.get("prompts", []) if isinstance(prompt, dict)}
    add_check(
        checked,
        failures,
        "notebook.support-reviewer-prompt",
        "notebook_support_reviewer" in prompt_names,
        ",".join(sorted(str(name) for name in prompt_names)),
        "Notebook support reviewer prompt is missing",
    )

    prompt_manifest, _ = read_first_text([
        root / "agent-prompts" / "prompts.manifest.yaml",
        root / "data" / "agent-handoff" / "mcp-control-tower" / "prompt-manifest.yaml",
    ])
    generated_prompt_paths = [
        root / "agent-prompts" / "out" / "demo1_mcp_control_tower.prompt",
        root / "data" / "agent-handoff" / "mcp-control-tower" / "demo1_mcp_control_tower.prompt",
    ]
    generated_prompt_exists = any(path_exists(path) for path in generated_prompt_paths)
    prompt_pack_soft_missing = not prompt_manifest or not generated_prompt_exists
    if prompt_pack_soft_missing:
        evidence_needed.append("repo-local control tower prompt pack inaccessible or not generated")
    add_check(
        checked,
        failures,
        "prompts.demo1-mcp-control-tower",
        ("demo1_mcp_control_tower" in prompt_manifest and generated_prompt_exists) or prompt_pack_soft_missing,
        "agent-prompts/prompts.manifest.yaml + out/demo1_mcp_control_tower.prompt or data/agent-handoff/mcp-control-tower fallback"
        + (";evidence_needed=prompt pack" if prompt_pack_soft_missing else ""),
        "control tower prompt pack is not registered/generated",
    )

    computer_use = computer_use_gui_boundary_summary(root)
    add_check(
        checked,
        failures,
        "computer-use.gui-proof-boundary",
        computer_use.get("ready") is True,
        (
            f"artifactPath={computer_use.get('artifactPath')};"
            f"promptPack={computer_use.get('promptPack')};"
            f"guiOnly={computer_use.get('guiOnly')};"
            f"noTerminalAutomation={computer_use.get('noTerminalAutomation')};"
            f"supportingOnly={computer_use.get('supportingOnly')};"
            f"helperArtifact={computer_use.get('helperPresent')};"
            f"helperReachable={computer_use.get('helperReachable')};"
            f"helperGeneratedAt={computer_use.get('helperGeneratedAt')};"
            f"helperFresh={computer_use.get('helperFresh')};"
            f"helperFreshnessStatus={computer_use.get('helperFreshnessStatus')};"
            f"helperAgeSeconds={computer_use.get('helperAgeSeconds')};"
            f"helperCountOnly={computer_use.get('helperCountOnly')};"
            f"helperBoundary={computer_use.get('helperBoundary')};"
            f"storesAppNames={computer_use.get('storesAppNames')};"
            f"storesWindowTitles={computer_use.get('storesWindowTitles')};"
            f"appCount={computer_use.get('appCount')};"
            f"targetableWindowCount={computer_use.get('targetableWindowCount')};"
            f"helperSecretPatternHits={computer_use.get('helperSecretPatternHits')};"
            f"rawSecretPatternHits={computer_use.get('rawSecretPatternHits')}"
        ),
        "Computer Use prompt/helper boundary is missing, stale, unsafe for source work, or leaks secrets",
    )

    pages_api_paths = [str(path.relative_to(root)) for path in walk_paths(root) if path_has_pages_api(path.relative_to(root))]
    add_check(
        checked,
        failures,
        "nextjs.no-pages-api",
        not pages_api_paths,
        f"pagesApiPathCount={len(pages_api_paths)}",
        "pages/api path exists; App Router route.ts contract would be violated",
    )

    text = build_text(root)
    versions = langchain4j_versions(text)
    add_check(
        checked,
        failures,
        "java.langchain4j-1.0.1",
        bool(versions) and all(version == "1.0.1" for version in versions),
        ",".join(versions),
        "LangChain4j version purity is not 1.0.1-only",
    )
    trace_context_text = read_text(root / "main" / "java" / "com" / "example" / "lms" / "trace" / "TraceContext.java")
    trace_filter_text = read_text(root / "main" / "java" / "com" / "example" / "lms" / "web" / "TraceFilter.java")
    orch_trace_text = read_text(root / "main" / "java" / "ai" / "abandonware" / "nova" / "orch" / "trace" / "OrchTrace.java")
    trace_filter_test_text = read_text(root / "src" / "test" / "java" / "com" / "example" / "lms" / "web" / "TraceFilterBreadcrumbTest.java")
    add_check(
        checked,
        failures,
        "java.mdc-trace-session",
        all(
            token in trace_context_text
            for token in (
                'MDC.put("sessionId", sid);',
                'MDC.put("traceId", t);',
                'MDC.put("x-request-id", t);',
                'restore("sessionId", prevSessionId);',
                'restore("traceId", prevTraceId);',
            )
        )
        and all(
            token in trace_filter_text
            for token in (
                'private static final String SID_HEADER = "X-Session-Id";',
                'private static final String REQUEST_ID_HEADER = "X-Request-Id";',
                'String traceHash = SafeRedactor.hashValue(trace);',
                'TraceStore.putIfAbsent("requestId", traceHash);',
                'TraceStore.putIfAbsent("traceId", traceHash);',
                'TraceStore.putIfAbsent("sessionId", SafeRedactor.hashValue(sid));',
            )
        )
        and all(
            token in orch_trace_text
            for token in (
                'MDC.get("traceId")',
                'MDC.get("sessionId")',
                'MDC.get("x-request-id")',
                'ev.put("traceId", hashOrEmpty(traceId));',
                'ev.put("sessionId", hashOrEmpty(sid));',
                'ev.put("requestId", hashOrEmpty(requestId));',
            )
        )
        and all(
            token in trace_filter_test_text
            for token in (
                'requestInstallsTraceContextAndMlaBreadcrumbsDuringChain',
                'String rawRid = "sk-" + "tracefilterrequestid01234567890";',
                'request.addHeader("X-Request-Id", rawRid);',
                'request.addHeader("X-Session-Id", "sid-123");',
                'assertEquals(rawRid, MDC.get("x-request-id"));',
                'assertEquals(ridHash, TraceStore.get("requestId"));',
                'assertFalse(String.valueOf(TraceStore.context()).contains(rawRid));',
                'assertEquals("sid-123", MDC.get("sessionId"));',
                'SafeRedactor.hashValue("sid-123")',
            )
        ),
        "TraceContext + TraceFilter + OrchTrace + TraceFilterBreadcrumbTest",
        "Java MDC traceId/sessionId/requestId continuity evidence is missing",
    )
    add_check(
        checked,
        failures,
        "java.spring-boot3-java17",
        "org.springframework.boot" in text and 'version "3.' in text and ("VERSION_17" in text or "JavaLanguageVersion.of(17)" in text),
        "build.gradle(.kts) Spring Boot + Java 17 declarations",
        "Java 17 / Spring Boot 3 evidence missing",
    )

    raw_secret_hits = count_secret_patterns(root) + external_secret_hits
    add_check(
        checked,
        failures,
        "secrets.no-raw-token-patterns",
        raw_secret_hits == 0,
        f"rawSecretPatternHits={raw_secret_hits}",
        "high-confidence secret pattern found in control tower surfaces",
    )
    requirements = build_requirement_matrix(
        checked,
        evidence_needed,
        archive_index,
        external_valid_roles,
        external_handoff_valid_roles,
        external_bundle_valid_roles,
        root,
        supabase_smoke,
    )
    next_actions = completion_audit_next_actions(requirements, evidence_needed)
    next_action_details = completion_audit_next_action_details(root, next_actions)

    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "ok": len(failures) == 0,
        "status": "local_control_tower_ready" if len(failures) == 0 else "local_control_tower_incomplete",
        "rootHash": stable_hash(str(root)),
        "checked": checked,
        "requirements": requirements,
        "failures": failures,
        "evidence_needed": evidence_needed,
        "nextActions": next_actions,
        "nextActionDetails": next_action_details,
        "rawSecretPatternHits": raw_secret_hits,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit local AWX MCP control tower completion evidence.")
    parser.add_argument("--root", default=".", help="Repository root to audit.")
    parser.add_argument("--output", default="", help="Optional path to write the audit JSON artifact.")
    args = parser.parse_args()
    result = audit(Path(args.root))
    output = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(output, encoding="utf-8")
    print(output)
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
