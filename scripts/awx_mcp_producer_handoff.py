#!/usr/bin/env python3
"""Run MCP node smoke and then create a PatchDrop producer bundle.

This is the producer-node handoff wrapper for Mac mini/Notebook local
worktrees. It keeps the Desktop canonical root as verification-only evidence:
the smoke proves the MCP contract and restore guard, then the existing
PatchDrop producer helper renders the v3 bundle from an explicit pathspec.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "awx.mcp.producer_handoff.v1"
DESKTOP_CANONICAL = Path("C:/AbandonWare/demo-1/demo-1/src")
SAFE_AUDIT_FIELDS = (
    "requestId",
    "sessionId",
    "nodeRole",
    "toolName",
    "inputHash",
    "outputCount",
    "elapsedMs",
    "decision",
    "failReason",
)
SECRET_RE = re.compile(
    r"sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|"
    r"pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|"
    r"sbp_[A-Za-z0-9_-]{10,}",
    re.ASCII,
)
PRODUCER_FAIL_RE = re.compile(r"\[producer-bundle\]\[FAIL\]\[([A-Za-z0-9_.:-]+)\]", re.ASCII)


def main() -> int:
    parser = argparse.ArgumentParser(description="AWX MCP producer handoff")
    parser.add_argument("--source-root", default=".", help="Producer-local worktree/clone root.")
    parser.add_argument("--canonical-root", default=str(DESKTOP_CANONICAL), help="Desktop canonical root for guard proof.")
    parser.add_argument("--patchdrop-root", default="", help="PatchDrop output root.")
    parser.add_argument(
        "--producer-script",
        default="",
        help="Path to producer-local __patch_drop__/producer_bundle.py.",
    )
    parser.add_argument("--node-role", default="macmini", choices=("macmini", "notebook", "desktop"))
    parser.add_argument("--topic", required=True)
    parser.add_argument("--pathspec", required=True, action="append", nargs="+")
    parser.add_argument("--audit-log", default="", help="Append a redacted allowlisted audit row.")
    parser.add_argument("--producer-command-hash", default="", help="SHA256 of the Desktop-rendered producer command file.")
    args = parser.parse_args()

    started = time.monotonic()
    pathspecs = [item for group in args.pathspec for item in group]
    slug = slugify(args.topic)
    bundle = f"{slug}-{args.node_role}-v3"
    if args.node_role in {"macmini", "notebook"} and is_shared_source_root(args.source_root):
        result = failure_result(
            args=args,
            slug=slug,
            bundle=bundle,
            started=started,
            fail_reason="smb-direct-edit",
            decision="producer_handoff_failed",
        )
        append_audit_if_requested(args.audit_log, result)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1

    source_root = Path(args.source_root).resolve()
    canonical_root = Path(args.canonical_root).resolve()
    if args.node_role in {"macmini", "notebook"} and is_shared_source_root(str(source_root)):
        result = failure_result(
            args=args,
            slug=slug,
            bundle=bundle,
            started=started,
            fail_reason="smb-direct-edit",
            decision="producer_handoff_failed",
        )
        append_audit_if_requested(args.audit_log, result)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1
    if args.node_role in {"macmini", "notebook"} and is_under_canonical_source_root(source_root, canonical_root):
        result = failure_result(
            args=args,
            slug=slug,
            bundle=bundle,
            started=started,
            fail_reason="smb-direct-edit",
            decision="producer_handoff_failed",
        )
        append_audit_if_requested(args.audit_log, result)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1

    patchdrop_root = Path(args.patchdrop_root).resolve() if args.patchdrop_root else source_root / "__patch_drop__"
    producer_script = (
        Path(args.producer_script).resolve()
        if args.producer_script
        else source_root / "__patch_drop__" / "producer_bundle.py"
    )
    if args.node_role in {"macmini", "notebook"} and not is_under_path(producer_script, source_root):
        result = failure_result(
            args=args,
            slug=slug,
            bundle=bundle,
            started=started,
            fail_reason="producer-helper-shared",
            decision="producer_handoff_failed",
        )
        append_audit_if_requested(args.audit_log, result)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1
    node_smoke = Path(__file__).resolve().with_name("awx_mcp_node_smoke.py")

    smoke = run_json(
        [
            sys.executable,
            str(node_smoke),
            "--root",
            str(source_root),
            "--canonical-root",
            str(canonical_root),
            "--node-role",
            args.node_role,
            "--query",
            f"{slug} producer handoff",
        ],
        cwd=source_root,
    )

    raw_outputs = [smoke["raw"]]
    failures: list[str] = []
    if smoke["exitCode"] != 0 or not bool(smoke.get("json", {}).get("ok", False)):
        failures.append("node-smoke-failed")

    bundle_result: dict[str, Any] = {
        "ok": False,
        "exitCode": None,
        "sidecarsComplete": False,
        "shaVerified": False,
        "sourceIsolation": {},
        "desktopFinalProof": "evidence_needed",
        "promotionReady": False,
        "diffHeaderCount": 0,
        "outputHash": "",
        "outputLineCount": 0,
        "failReason": "",
    }

    if not failures:
        command = [
            sys.executable,
            str(producer_script),
            "--topic",
            args.topic,
            "--node",
            args.node_role,
            "--source-root",
            str(source_root),
            "--patchdrop-root",
            str(patchdrop_root),
        ]
        for spec in pathspecs:
            command.extend(["--pathspec", spec])
        producer = run_text(command, cwd=source_root)
        producer_fail = producer_fail_reason(producer["raw"])
        raw_outputs.append(producer["raw"])
        sidecars_complete = all(
            (patchdrop_root / args.node_role / f"{bundle}{suffix}").exists()
            for suffix in (".patch", ".report.md", ".verify.log", ".sha256.txt", ".manifest.json")
        ) and (patchdrop_root / f"{slug}.{args.node_role}-pending.md").exists()
        patch_path = patchdrop_root / args.node_role / f"{bundle}.patch"
        manifest_summary = read_manifest_summary(patchdrop_root / args.node_role / f"{bundle}.manifest.json")
        sha_summary = read_producer_sha_summary(patchdrop_root, args.node_role, slug, bundle) if sidecars_complete else {
            "ok": False,
            "failReason": "producer-sidecars-missing",
        }
        promotion_ready = producer["exitCode"] == 0 and sidecars_complete and manifest_summary["ok"] and sha_summary["ok"]
        bundle_result = {
            "ok": promotion_ready,
            "exitCode": producer["exitCode"],
            "sidecarsComplete": sidecars_complete,
            "shaVerified": sha_summary["ok"],
            "sourceIsolation": manifest_summary["sourceIsolation"],
            "desktopFinalProof": manifest_summary["desktopFinalProof"],
            "promotionReady": promotion_ready,
            "diffHeaderCount": manifest_summary["diffHeaderCount"],
            "patchHash": sha256_file(patch_path),
            "outputHash": stable_hash(producer["raw"]),
            "outputLineCount": len([line for line in producer["raw"].splitlines() if line.strip()]),
            "failReason": producer_fail,
        }
        if producer["exitCode"] != 0:
            failures.append("producer-bundle-failed")
            if producer_fail:
                failures.append(producer_fail)
        if not sidecars_complete:
            failures.append("producer-sidecars-missing")
        if sidecars_complete and not sha_summary["ok"]:
            failures.append(sha_summary["failReason"])
        if not manifest_summary["ok"]:
            failures.append(manifest_summary["failReason"])

    raw_secret_hits = len(SECRET_RE.findall("\n".join(raw_outputs)))
    if raw_secret_hits:
        failures.append("secret-leak-risk")

    result = {
        "schemaVersion": SCHEMA_VERSION,
        "ok": not failures,
        "requestId": "producer-handoff",
        "sessionId": safe_scalar(smoke.get("json", {}).get("sessionId", "awx-mcp-producer-handoff"), 96),
        "nodeRole": args.node_role,
        "toolName": "producer_handoff",
        "inputHash": producer_input_hash(args, pathspecs),
        "outputCount": 1 if not failures else 0,
        "topic": slug,
        "producerCommandHash": normalize_sha256(args.producer_command_hash),
        "sourceRootInputHash": stable_hash(args.source_root.strip()),
        "sourceRootHash": stable_hash(str(source_root)),
        "canonicalRootHash": stable_hash(str(canonical_root)),
        "patchDropHash": stable_hash(str(patchdrop_root)),
        "smoke": {
            "ok": bool(smoke.get("json", {}).get("ok", False)),
            "exitCode": smoke["exitCode"],
            "decision": safe_scalar(smoke.get("json", {}).get("decision", ""), 80),
            "evidence_needed": smoke.get("json", {}).get("evidence_needed", []),
        },
        "bundle": bundle_result,
        "desktopFinalProof": "evidence_needed",
        "rawSecretPatternHits": raw_secret_hits,
        "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
        "decision": "producer_handoff" if not failures else "producer_handoff_failed",
        "failReason": ",".join(failures),
    }
    append_audit_if_requested(args.audit_log, result)
    print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    return 0 if result["ok"] else 1


def is_shared_source_root(raw_path: str) -> bool:
    stripped = raw_path.strip()
    if stripped.startswith("\\\\"):
        return True
    if windows_mapped_drive_root(stripped):
        return True
    normalized = stripped.replace("\\", "/").lower()
    parts = [part for part in normalized.split("/") if part]
    if "patchdrop" in parts or "__patch_drop__" in parts:
        return True
    return normalized.startswith(("/volumes/", "/mnt/", "/media/"))


def windows_mapped_drive_root(raw_path: str) -> str:
    stripped = raw_path.strip()
    if not re.match(r"^[A-Za-z]:", stripped):
        return ""
    if os.name != "nt":
        return ""
    try:
        import ctypes
        from ctypes import wintypes

        drive = stripped[:2].upper()
        size = wintypes.DWORD(512)
        buffer = ctypes.create_unicode_buffer(size.value)
        result = ctypes.windll.mpr.WNetGetConnectionW(drive, buffer, ctypes.byref(size))
        if result == 234 and size.value > len(buffer):
            buffer = ctypes.create_unicode_buffer(size.value)
            result = ctypes.windll.mpr.WNetGetConnectionW(drive, buffer, ctypes.byref(size))
        if result != 0:
            return ""
        remote = buffer.value.strip()
        return remote if remote.startswith("\\\\") else ""
    except Exception:
        return ""


def is_under_canonical_source_root(source_root: Path, canonical_root: Path) -> bool:
    return source_root == canonical_root or canonical_root in source_root.parents


def is_under_path(child: Path, parent: Path) -> bool:
    return child == parent or parent in child.parents


def producer_fail_reason(raw_output: str) -> str:
    match = PRODUCER_FAIL_RE.search(raw_output or "")
    if not match:
        return ""
    return safe_scalar(match.group(1), 120)


def failure_result(
    *,
    args: argparse.Namespace,
    slug: str,
    bundle: str,
    started: float,
    fail_reason: str,
    decision: str,
) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "ok": False,
        "requestId": "producer-handoff",
        "sessionId": "awx-mcp-producer-handoff",
        "nodeRole": args.node_role,
        "toolName": "producer_handoff",
        "inputHash": producer_input_hash(args, []),
        "outputCount": 0,
        "topic": slug,
        "producerCommandHash": normalize_sha256(args.producer_command_hash),
        "sourceRootInputHash": stable_hash(str(args.source_root).strip()),
        "sourceRootHash": stable_hash(str(args.source_root)),
        "canonicalRootHash": stable_hash(str(args.canonical_root)),
        "patchDropHash": stable_hash(str(args.patchdrop_root)),
        "smoke": {
            "ok": False,
            "exitCode": None,
            "decision": "not_run",
            "evidence_needed": [],
        },
        "bundle": {
            "ok": False,
            "exitCode": None,
            "sidecarsComplete": False,
            "shaVerified": False,
            "sourceIsolation": {},
            "desktopFinalProof": "evidence_needed",
            "promotionReady": False,
            "diffHeaderCount": 0,
            "patchHash": "",
            "outputHash": "",
            "outputLineCount": 0,
        },
        "desktopFinalProof": "evidence_needed",
        "rawSecretPatternHits": 0,
        "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
        "decision": decision,
        "failReason": fail_reason,
    }


def producer_input_hash(args: argparse.Namespace, pathspecs: list[str]) -> str:
    payload = {
        "sourceRoot": str(getattr(args, "source_root", "")),
        "canonicalRoot": str(getattr(args, "canonical_root", "")),
        "patchdropRoot": str(getattr(args, "patchdrop_root", "")),
        "nodeRole": str(getattr(args, "node_role", "")),
        "topic": str(getattr(args, "topic", "")),
        "producerCommandHash": normalize_sha256(getattr(args, "producer_command_hash", "")),
        "pathspecCount": len(pathspecs),
    }
    return stable_hash(json.dumps(payload, sort_keys=True, separators=(",", ":")))


def append_audit_if_requested(raw_path: str, result: dict[str, Any]) -> None:
    if not raw_path.strip():
        return
    path = Path(raw_path).resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    row = {key: result.get(key, "") for key in SAFE_AUDIT_FIELDS}
    path.write_text("", encoding="utf-8") if not path.exists() else None
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=True, separators=(",", ":")) + "\n")


def read_manifest_summary(manifest_path: Path) -> dict[str, Any]:
    if not manifest_path.exists():
        return {
            "ok": False,
            "failReason": "producer-manifest-missing",
            "sourceIsolation": {},
            "desktopFinalProof": "evidence_needed",
            "diffHeaderCount": 0,
        }
    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {
            "ok": False,
            "failReason": "producer-manifest-invalid",
            "sourceIsolation": {},
            "desktopFinalProof": "evidence_needed",
            "diffHeaderCount": 0,
        }
    if not isinstance(data, dict):
        return {
            "ok": False,
            "failReason": "producer-manifest-invalid",
            "sourceIsolation": {},
            "desktopFinalProof": "evidence_needed",
            "diffHeaderCount": 0,
        }

    desktop_final_proof = safe_scalar(data.get("desktopFinalProof", "evidence_needed"), 80)
    verification = data.get("verification") if isinstance(data.get("verification"), dict) else {}
    diff_header_count = safe_int(verification.get("diffHeaderCount", 0))
    isolation = data.get("sourceIsolation")
    if not isinstance(isolation, dict):
        return {
            "ok": False,
            "failReason": "producer-source-isolation-missing",
            "sourceIsolation": {},
            "desktopFinalProof": desktop_final_proof,
            "diffHeaderCount": diff_header_count,
        }

    source_isolation = {
        "guard": safe_scalar(isolation.get("guard", ""), 40),
        "sourceRootKind": safe_scalar(isolation.get("sourceRootKind", ""), 40),
        "sharedSourceRoot": bool(isolation.get("sharedSourceRoot", False)),
        "desktopCanonicalSourceRoot": bool(isolation.get("desktopCanonicalSourceRoot", False)),
        "directCanonicalSourceEdit": bool(isolation.get("directCanonicalSourceEdit", False)),
        "gitRootPresent": isolation.get("gitRootPresent") is True,
        "gitRootMatchesSourceRoot": isolation.get("gitRootMatchesSourceRoot") is True,
        "gitRootHash": safe_scalar(isolation.get("gitRootHash", ""), 120),
    }
    git_root_missing = (
        not source_isolation["gitRootPresent"]
        or not source_isolation["gitRootMatchesSourceRoot"]
        or not source_isolation["gitRootHash"]
    )
    if git_root_missing:
        return {
            "ok": False,
            "failReason": "producer-git-root-missing",
            "sourceIsolation": source_isolation,
            "desktopFinalProof": desktop_final_proof,
            "diffHeaderCount": diff_header_count,
        }
    source_isolation_violation = (
        source_isolation["guard"] != "PASS"
        or source_isolation["sourceRootKind"] != "local-worktree"
        or source_isolation["sharedSourceRoot"]
        or source_isolation["desktopCanonicalSourceRoot"]
        or source_isolation["directCanonicalSourceEdit"]
    )
    if source_isolation_violation:
        return {
            "ok": False,
            "failReason": "producer-source-isolation-violation",
            "sourceIsolation": source_isolation,
            "desktopFinalProof": desktop_final_proof,
            "diffHeaderCount": diff_header_count,
        }
    if desktop_final_proof != "evidence_needed":
        return {
            "ok": False,
            "failReason": "producer-desktop-proof-not-pending",
            "sourceIsolation": source_isolation,
            "desktopFinalProof": desktop_final_proof,
            "diffHeaderCount": diff_header_count,
        }
    if diff_header_count <= 0:
        return {
            "ok": False,
            "failReason": "producer-patch-not-unified-diff",
            "sourceIsolation": source_isolation,
            "desktopFinalProof": desktop_final_proof,
            "diffHeaderCount": diff_header_count,
        }
    return {
        "ok": True,
        "failReason": "",
        "sourceIsolation": source_isolation,
        "desktopFinalProof": desktop_final_proof,
        "diffHeaderCount": diff_header_count,
    }


def read_producer_sha_summary(patchdrop_root: Path, node_role: str, slug: str, bundle: str) -> dict[str, Any]:
    node_dir = patchdrop_root / node_role
    sha_path = node_dir / f"{bundle}.sha256.txt"
    expected_files = {
        f"{bundle}.patch": node_dir / f"{bundle}.patch",
        f"{bundle}.report.md": node_dir / f"{bundle}.report.md",
        f"{bundle}.verify.log": node_dir / f"{bundle}.verify.log",
        f"{bundle}.manifest.json": node_dir / f"{bundle}.manifest.json",
        f"../{slug}.{node_role}-pending.md": patchdrop_root / f"{slug}.{node_role}-pending.md",
    }
    if not sha_path.exists():
        return {"ok": False, "failReason": "producer-sha-sidecar-missing"}

    entries: dict[str, str] = {}
    try:
        lines = sha_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return {"ok": False, "failReason": "producer-sha-sidecar-unreadable"}
    for line in lines:
        parts = line.strip().split(None, 1)
        if len(parts) != 2:
            continue
        digest, name = parts[0].lower(), parts[1].strip().replace("\\", "/")
        if re.fullmatch(r"[a-f0-9]{64}", digest):
            entries[name] = digest

    missing_entries = [name for name in expected_files if name not in entries]
    if missing_entries:
        return {
            "ok": False,
            "failReason": "producer-sha-entry-missing:" + ",".join(missing_entries),
        }

    for name, path in expected_files.items():
        if not path.exists():
            return {"ok": False, "failReason": "producer-sidecars-missing:" + name}
        actual = sha256_file(path)
        if actual != entries[name]:
            return {"ok": False, "failReason": "producer-sha-mismatch:" + name}

    return {"ok": True, "failReason": ""}


def safe_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def run_json(command: list[str], cwd: Path) -> dict[str, Any]:
    raw = run_text(command, cwd)
    try:
        raw["json"] = json.loads(raw["stdout"])
    except json.JSONDecodeError:
        raw["json"] = {"ok": False, "decision": "json_parse_failed", "failReason": "json-parse"}
    return raw


def run_text(command: list[str], cwd: Path) -> dict[str, Any]:
    proc = subprocess.run(
        command,
        cwd=str(cwd),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    raw = (proc.stdout or "") + (proc.stderr or "")
    return {"exitCode": proc.returncode, "stdout": proc.stdout or "", "raw": raw}


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9._-]+", "-", value.strip().lower()).strip("-")
    if not slug:
        raise SystemExit("topic-invalid")
    return slug


def safe_scalar(value: Any, limit: int) -> str:
    text = "" if value is None else str(value)
    text = text.replace("\r", " ").replace("\n", " ").strip()
    return text[:limit]


def normalize_sha256(value: Any) -> str:
    text = safe_scalar(value, 120).lower()
    return text if re.fullmatch(r"[a-f0-9]{64}", text) else ""


def stable_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="ignore")).hexdigest()


def sha256_file(path: Path) -> str:
    if not path.exists():
        return ""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())
