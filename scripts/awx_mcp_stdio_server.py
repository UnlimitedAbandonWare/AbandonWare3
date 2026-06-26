#!/usr/bin/env python3
"""Minimal stdio JSON-RPC bridge for the AWX MCP-style toolbox.

This intentionally stays outside the Java runtime. It exposes the repo-local
tool manifest as MCP-shaped tools/resources/prompts and delegates tool calls to
awx_mcp_toolbox without printing raw secrets.
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path
from typing import Any

import awx_mcp_toolbox as toolbox


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json"
PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "awx-control-tower"

HANDLERS = {
    "source_scan": toolbox.source_scan,
    "harmony_scan": toolbox.harmony_scan,
    "agent_db_snapshot": toolbox.agent_db_snapshot,
    "trace_snapshot_probe": toolbox.trace_snapshot_probe,
    "supabase_context_probe": toolbox.supabase_context_probe,
    "supabase_schema_snapshot": toolbox.supabase_schema_snapshot,
    "supabase_schema_snapshot_import": toolbox.supabase_schema_snapshot_import,
    "patch_plan": toolbox.patch_plan,
    "patch_render": toolbox.patch_render,
    "archive_index_build": toolbox.archive_index_build,
    "archive_search": toolbox.archive_search,
    "archive_restore": toolbox.archive_restore,
    "boot_verify": toolbox.boot_verify,
    "build_error_mine": toolbox.build_error_mine,
    "run_pipeline": toolbox.run_pipeline,
    "external_evidence_intake": toolbox.external_evidence_intake,
    "external_evidence_audit": toolbox.external_evidence_audit,
    "producer_command_plan": toolbox.producer_command_plan,
    "producer_kit_export": toolbox.producer_kit_export,
    "desktop_dispatch_packet": toolbox.desktop_dispatch_packet,
    "desktop_control_loop": toolbox.desktop_control_loop,
}


def main() -> int:
    for raw_line in sys.stdin:
        raw_line = raw_line.strip()
        if not raw_line:
            continue
        try:
            request = json.loads(raw_line)
            reply = handle_request(request)
        except Exception as exc:
            reply = error_response(None, -32700, toolbox.safe_message(str(exc), 240))
        if reply is not None:
            sys.stdout.write(json.dumps(reply, ensure_ascii=True, separators=(",", ":")) + "\n")
            sys.stdout.flush()
    return 0


def handle_request(request: Any) -> dict[str, Any] | None:
    if not isinstance(request, dict):
        return error_response(None, -32600, "request_must_be_object")
    request_id = request.get("id")
    method = toolbox.safe_scalar(request.get("method", ""), 96)
    params = request.get("params") if isinstance(request.get("params"), dict) else {}

    if request_id is None and method.startswith("notifications/"):
        return None
    if method == "initialize":
        return ok_response(request_id, initialize_result())
    if method == "tools/list":
        return ok_response(request_id, {"tools": list_tools()})
    if method == "tools/call":
        return ok_response(request_id, call_tool(params))
    if method == "resources/list":
        return ok_response(request_id, {"resources": list_resources()})
    if method == "resources/read":
        return ok_response(request_id, read_resource(params))
    if method == "prompts/list":
        return ok_response(request_id, {"prompts": list_prompts()})
    if method == "prompts/get":
        return ok_response(request_id, get_prompt(params))
    return error_response(request_id, -32601, f"unknown_method:{method}")


def initialize_result() -> dict[str, Any]:
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "capabilities": {
            "tools": {},
            "resources": {},
            "prompts": {},
        },
        "serverInfo": {
            "name": SERVER_NAME,
            "version": "1.0.0",
        },
    }


def load_manifest() -> dict[str, Any]:
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def list_tools() -> list[dict[str, Any]]:
    manifest = load_manifest()
    out: list[dict[str, Any]] = []
    for item in manifest.get("tools", []):
        if not isinstance(item, dict):
            continue
        name = toolbox.safe_scalar(item.get("name", ""), 96)
        if not name:
            continue
        out.append({
            "name": name,
            "description": toolbox.safe_scalar(item.get("description", ""), 500),
            "inputSchema": item.get("input_schema", {"type": "object"}),
            "annotations": {
                "readOnlyHint": bool(item.get("readOnly", True)),
                "nodeRoles": [toolbox.safe_scalar(role, 32) for role in item.get("nodeRoles", [])],
                "aliases": [toolbox.safe_scalar(alias, 96) for alias in item.get("aliases", [])],
            },
        })
    return out


def call_tool(params: dict[str, Any]) -> dict[str, Any]:
    requested_name = toolbox.safe_scalar(params.get("name", ""), 96)
    tool_name = toolbox.TOOL_ALIASES.get(requested_name, requested_name)
    if tool_name not in HANDLERS:
        raise ValueError(f"unknown_tool:{tool_name}")
    arguments = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}
    started = time.monotonic()
    result = HANDLERS[tool_name](arguments)
    toolbox.finalize(tool_name, arguments, result, started)
    redacted = toolbox.redact(result)
    text = json.dumps(redacted, ensure_ascii=True, separators=(",", ":"))
    return {
        "content": [{"type": "text", "text": text}],
        "structuredContent": redacted,
        "isError": not bool(redacted.get("ok", True)),
    }


def list_resources() -> list[dict[str, Any]]:
    manifest = load_manifest()
    resources = [{
        "name": "tool_manifest",
        "uri": manifest_uri("main/resources/mcp/awx-control-tower-tools.json"),
        "description": "Fixed AWX MCP tool/resource/prompt contract.",
        "mimeType": "application/json",
    }]
    for item in manifest.get("resources", []):
        if not isinstance(item, dict):
            continue
        path = toolbox.safe_scalar(item.get("path", ""), 500)
        name = toolbox.safe_scalar(item.get("name", ""), 96)
        if not name or not path:
            continue
        resources.append({
            "name": name,
            "uri": manifest_uri(path),
            "description": toolbox.safe_scalar(item.get("usage", ""), 500),
            "mimeType": "text/plain",
        })
    return resources


def read_resource(params: dict[str, Any]) -> dict[str, Any]:
    uri = toolbox.safe_scalar(params.get("uri", ""), 600)
    resources = {item["uri"]: item for item in list_resources()}
    if uri not in resources:
        raise ValueError("unknown_resource")
    path = path_from_manifest_uri(uri)
    if not path.exists() or path.is_dir():
        return {"contents": [{"uri": uri, "mimeType": resources[uri].get("mimeType", "text/plain"), "text": ""}]}
    text = toolbox.redact_string(path.read_text(encoding="utf-8", errors="ignore"))
    return {"contents": [{"uri": uri, "mimeType": resources[uri].get("mimeType", "text/plain"), "text": text[:20000]}]}


def list_prompts() -> list[dict[str, Any]]:
    manifest = load_manifest()
    prompts: list[dict[str, Any]] = []
    for item in manifest.get("prompts", []):
        if not isinstance(item, dict):
            continue
        name = toolbox.safe_scalar(item.get("name", ""), 96)
        if not name:
            continue
        prompts.append({
            "name": name,
            "description": " -> ".join(toolbox.safe_scalar(step, 64) for step in item.get("flow", [])),
            "arguments": [],
        })
    return prompts


def get_prompt(params: dict[str, Any]) -> dict[str, Any]:
    name = toolbox.safe_scalar(params.get("name", ""), 96)
    manifest = load_manifest()
    for item in manifest.get("prompts", []):
        if isinstance(item, dict) and item.get("name") == name:
            flow = [toolbox.safe_scalar(step, 64) for step in item.get("flow", [])]
            text = render_prompt_text(name, flow)
            return {"description": name, "messages": [{"role": "user", "content": {"type": "text", "text": text}}]}
    raise ValueError("unknown_prompt")


def render_prompt_text(name: str, flow: list[str]) -> str:
    flow_text = " -> ".join(flow)
    common = [
        f"Prompt: {name}",
        f"Tool flow: {flow_text}",
        "Use main/resources/mcp/awx-control-tower-mcp-client.sample.json for the stdio bridge config.",
        "Keep logs redacted: requestId, sessionId, nodeRole, toolName, inputHash, outputCount, elapsedMs, decision, failReason.",
        "Allowed environment references are NAVER_KEYS, NAVER_CLIENT_ID, and NAVER_CLIENT_SECRET by name only.",
        "Desktop final proof remains evidence_needed until the Desktop canonical root runs intake, audit, and Gradle verification.",
    ]
    if name == "macmini_patch_producer":
        role = [
            "Role: Mac mini read-only investigator and PatchDrop patch producer.",
            "Set the MCP client cwd to a producer-local worktree or clone; never set it to the Desktop canonical source root.",
            "Run source_scan, archive_search, patch_plan, patch_render, boot_verify, and build_error_mine from the producer-local root.",
            "Submit only PatchDrop evidence, unified diffs, verification logs, SHA sidecars, and node-smoke JSON for Desktop intake.",
            "Do not write SMB canonical source paths; archive_restore targets must be local temp/worktree paths.",
        ]
    elif name == "desktop_final_verifier":
        role = [
            "Role: Desktop canonical source owner and final verifier for C:/AbandonWare/demo-1/demo-1/src.",
            "Render dispatch packets, review PatchDrop bundles, run secret scans and git-apply checks when Git metadata is available.",
            "Run external_evidence_intake and external_evidence_audit before accepting Mac mini or Notebook proof.",
            "Run the Desktop Gradle/source-governance chain before moving any PatchDrop bundle to applied.",
        ]
    elif name == "notebook_support_reviewer":
        role = [
            "Role: Notebook supporting probe, documentation, and diff-review node.",
            "Use a local clone or worktree only; do not edit the Desktop canonical source through SMB.",
            "Return read-only findings, review notes, build-log classifications, and PatchDrop evidence for Desktop final review.",
        ]
    else:
        role = ["Role: follow the manifest tool flow while preserving Desktop final-proof ownership."]
    return "\n".join(role + common)


def manifest_uri(path: str) -> str:
    return "awx://control-tower/" + path.replace("\\", "/").lstrip("/")


def path_from_manifest_uri(uri: str) -> Path:
    prefix = "awx://control-tower/"
    if not uri.startswith(prefix):
        raise ValueError("unsupported_resource_uri")
    rel = uri[len(prefix):]
    return (ROOT / rel).resolve()


def ok_response(request_id: Any, result: dict[str, Any]) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": toolbox.redact(result)}


def error_response(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {
            "code": code,
            "message": toolbox.safe_message(message, 240),
        },
    }


if __name__ == "__main__":
    raise SystemExit(main())
