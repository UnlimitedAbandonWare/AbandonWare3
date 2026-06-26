---
name: build_error_miner
description: Use when classifying build or boot logs through the demo-1 MCP control tower without returning raw log dumps, especially cannot-find-symbol, duplicate FQCN, Spring bean/bind, YAML, LangChain4j purity, or Gradle distribution failures.
---

# Build Error Miner

Use this task skill to classify a build log into stable failure classes without exposing raw logs.

Run through the launcher:

```powershell
@{
  nodeRole = "desktop"
  log_path = "build-logs\latest.log"
  audit_log = "logs\awx-mcp-audit.ndjson"
} | ConvertTo-Json -Depth 20 -Compress |
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox.ps1 -Tool build_error_miner
```

Rules:

- Return class counts and a primary class only.
- Do not paste full build logs into reports.
- Use `evidence_needed` when the log path is missing.
- Retry only once after a specific patch and only when the blocker class is unchanged.
