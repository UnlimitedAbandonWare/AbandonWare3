---
name: demo1-mcp-control-tower
description: Use when coordinating Desktop, Mac mini, and Notebook agents through the demo-1 MCP-style external toolbox, including desktop_control_loop, producer_kit_export, source_scan, patch_plan, patch_render, archive_search, archive_restore, boot_verify, build_error_mine, or run_pipeline.
---

# Demo1 MCP Control Tower

## Purpose

Use this skill to route multi-node safe-patch work through the repo-local MCP-style toolbox without direct SMB source edits.

Role split:

- Desktop: canonical source owner and final verifier for `C:\AbandonWare\demo-1\demo-1\src`.
- Mac mini: read-only investigator and PatchDrop producer from a separate worktree/local clone.
- Notebook: supporting probe, documentation, and diff-review node.

## Tool Manifest

Read `main/resources/mcp/awx-control-tower-tools.json` for the fixed JSON schemas. Invoke tools through:

```powershell
@{ nodeRole = "macmini"; q = "search terms" } |
  ConvertTo-Json -Depth 20 -Compress |
  python .\scripts\awx_mcp_toolbox.py archive_search
```

For clients that can speak line-delimited JSON-RPC over stdio, expose the same
manifest through:

```powershell
python .\scripts\awx_mcp_stdio_server.py
```

Secret-free client config sample:

```text
main/resources/mcp/awx-control-tower-mcp-client.sample.json
```

Mac mini and Notebook producers must set that config's `cwd` to a producer-local
worktree or clone, never to the Desktop canonical root.

Producer nodes can render a role-local config with a source-isolation guard:

```bash
python scripts/awx_mcp_node_setup.py --node-role macmini --source-root . --canonical-root C:/AbandonWare/demo-1/demo-1/src --output .codex/awx-control-tower.mcp.json --audit-log .codex/awx-control-tower.audit.jsonl
```

## Standard Flow

Safe Patch flow contract:

1. Desktop broad loop: run `desktop_control_loop` when assigning distributed work; it combines `source_scan`, optional dispatch refresh, and `external_evidence_audit` while keeping Desktop final proof as `evidence_needed`.
2. Producer bootstrap: run `producer_kit_export` when producer worktrees may not already contain the MCP scripts, manifest, skills, and prompts; install the exported kit into producer-local worktrees only.
3. Broad probe: run `source_scan` and inspect PatchDrop/source isolation when a narrower one-tool probe is enough.
4. Focused probe: use `archive_search`, focused source reads, or current failure evidence to narrow the target.
5. Minimal diff: use `patch_plan` and `patch_render` to produce only the smallest PatchDrop candidate.
6. Desktop verification: final apply and Gradle/boot proof stay on the Desktop canonical root.
7. Failure classification: use `build_error_mine` and tool `failReason` fields without raw log dumps.
8. Retry: retry only once per unchanged failure class, then require new evidence or a different class.

1. `source_scan`: broad probe of active roots, PatchDrop state, env-name references, and secret-pattern hit count.
2. `archive_search`: search `index_path`, `ARCHIVE_INDEX`, `NAS_ARCHIVE_ROOT/index.jsonl`, then `BackupsXS/index.jsonl` with `q`, `filters`, and `top_k`; require at least two passes and emit `evidence_needed` if still empty.
3. `patch_plan`: convert evidence into a direct/2-way/N-way Safe Patch plan.
4. `patch_render`: produce the PatchDrop v3 sidecar contract and scan any candidate patch for secret/filemode blockers.
5. Desktop: `desktop_control_loop` is the default assignment command; set `write_dispatch=true` to refresh dispatch JSON, role command files, and Desktop intake script under PatchDrop `dispatch/`, then read `nextActions`.
6. Desktop: `producer_kit_export` writes `__patch_drop__/producer-kit/<topic>-producer-kit/` with checksummed MCP runner files, schemas, skills, prompts, and install helpers.
7. Desktop: `desktop_dispatch_packet` is still available when only dispatch rendering is needed.
8. `producer_command_plan`: render exact node setup, single-node smoke, and PatchDrop handoff commands from a producer-local worktree, including `desktopEvidencePath` and env-name-only hints.
9. Desktop only: promote/apply through `__patch_drop__` janitor gates.
10. `boot_verify`: return role-local verification commands; Desktop final proof remains `evidence_needed` until run on the canonical root.
11. `build_error_mine`: classify build logs without raw log dumps.
12. Desktop: run `external_evidence_intake` to copy valid PatchDrop proof into `data/agent-handoff/mcp-control-tower`, then run `external_evidence_audit` to validate Mac mini/Notebook host proof without raw logs. If proof is missing, use the returned `nextActions` and `optionalNextActions` for the exact producer command files, Desktop intake script, setup runner, MCP client config, and archive-index hints.
13. Retry only once per unchanged failure class.

## Archive Rules

- `archive_search` reads only `index.jsonl` rows and returns path/title/hash evidence, not full archived content.
- `archive_restore` must receive `mode=restore`, `glob`, `target_dir`, and `audit_log`; pass `verify_log` when a producer or Desktop needs append-only checksum proof.
- Mac mini and Notebook `archive_restore` calls must target local temp/worktree paths; if `canonical_root` is provided and `target_dir` is under it, the tool must return `restore_target_blocked` / `smb-conflict-risk`.
- Restore returns explicit `preReview.performed`, `preReview.candidateCount`, and restored-row `checksumVerified=true` evidence, writes append-only audit records with only `requestId`, `sessionId`, `nodeRole`, `toolName`, `inputHash`, `outputCount`, `elapsedMs`, `decision`, and `failReason`, and can write `verify_log` rows containing pre-review plus checksum evidence without restored file contents.

## Producer Bundle Rules

- `external_evidence_audit` rejects producer `.patch` sidecars that contain Git file mode headers with `filemode-blocked`, path traversal / absolute / UNC targets with `unsafe-path`, or forbidden targets such as `pages/api/**`, `.env*`, `apikey.*`, keystores, and shared cache/build directories with `forbidden-path:*`; Desktop janitor still owns any explicit override decision during final apply.

## Redaction

Never log real key values, tokens, cookies, private env dumps, raw full prompts, personal data, or large base64. `NAVER_KEYS`, `NAVER_CLIENT_ID`, and `NAVER_CLIENT_SECRET` may appear only as environment-variable names.

## Verification

Use the toolbox regression first:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox_tests.ps1
```

Producer nodes can run the cross-platform smoke from their own worktree/local clone:

```bash
python scripts/awx_mcp_node_smoke.py --root . --canonical-root C:/AbandonWare/demo-1/demo-1/src --node-role macmini
```

After local edits, producer nodes can run the smoke-to-bundle handoff:

```bash
python scripts/awx_mcp_producer_handoff.py --source-root . --canonical-root C:/AbandonWare/demo-1/demo-1/src --patchdrop-root /path/to/PatchDrop --producer-script ./__patch_drop__/producer_bundle.py --node-role macmini --topic <topic> --pathspec <relative/path> --audit-log .codex/awx-control-tower.audit.jsonl
```

Desktop can audit the local control-tower contract without treating external host proof as complete:

```powershell
python .\scripts\awx_mcp_completion_audit.py --root .
```

Desktop can run the one-command control loop before assigning or rechecking producer work:

```powershell
@{ nodeRole = "desktop"; topic = "<topic>"; patchdrop_root = ".\__patch_drop__"; write_dispatch = $true; write_producer_kit = $true; producer_roots = @{ macmini = "<macmini-worktree>"; notebook = "<notebook-worktree>" }; pathspec = @("<relative/path>") } |
  ConvertTo-Json -Depth 20 -Compress |
  python .\scripts\awx_mcp_toolbox.py desktop_control_loop
```

Desktop can export a producer install kit into PatchDrop:

```powershell
@{ nodeRole = "desktop"; topic = "<topic>"; patchdrop_root = ".\__patch_drop__" } |
  ConvertTo-Json -Depth 20 -Compress |
  python .\scripts\awx_mcp_toolbox.py producer_kit_export
```

Desktop can validate copied Mac mini/Notebook smoke evidence:

```powershell
@{ nodeRole = "desktop"; evidence_dir = "data\agent-handoff\mcp-control-tower"; required_roles = @("macmini","notebook") } |
  ConvertTo-Json -Depth 20 -Compress |
  python .\scripts\awx_mcp_toolbox.py external_evidence_audit
```

Desktop can render a two-node dispatch packet before assigning producer work:

```powershell
@{ nodeRole = "desktop"; topic = "<topic>"; patchdrop_root = ".\__patch_drop__"; producer_roots = @{ macmini = "<macmini-worktree>"; notebook = "<notebook-worktree>" }; pathspec = @("<relative/path>") } |
  ConvertTo-Json -Depth 20 -Compress |
  python .\scripts\awx_mcp_toolbox.py desktop_dispatch_packet
```

To emit copyable PatchDrop dispatch files:

```powershell
@{ nodeRole = "desktop"; topic = "<topic>"; patchdrop_root = ".\__patch_drop__"; write_dispatch = $true; producer_roots = @{ macmini = "<macmini-worktree>"; notebook = "<notebook-worktree>" }; pathspec = @("<relative/path>") } |
  ConvertTo-Json -Depth 20 -Compress |
  python .\scripts\awx_mcp_toolbox.py desktop_dispatch_packet
```

For broader Desktop proof, continue with janitor and Gradle gates from `patchdrop-safe-patch-orchestrator`.
