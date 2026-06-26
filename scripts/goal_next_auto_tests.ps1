$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw "[goal-next-test][FAIL] $Name :: $Message"
    }
    Write-Host "[goal-next-test][PASS] $Name"
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name ($Text.Contains($Needle)) "expected output to contain '$Needle'; output=$Text"
}

function Set-TestFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )
    $dir = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    Set-Content -LiteralPath $Path -Value $Content -Encoding UTF8
}

function Invoke-Captured {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $lines = & powershell -NoProfile -ExecutionPolicy Bypass @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
    }
}

function New-FakeGoalRoot {
    param([Parameter(Mandatory = $true)][string]$Mode)
    $root = Join-Path ([IO.Path]::GetTempPath()) ("awx-goal-next-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'scripts') | Out-Null
    Set-TestFile (Join-Path $root 'mode.txt') $Mode
    Set-TestFile (Join-Path $root '.mcp.json') @'
{
  "mcpServers": {
    "supabase": {
      "type": "http",
      "url": "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=${SUPABASE_PROJECT_REF}"
    }
  },
  "notes": [
    "Authenticate through the MCP client flow; do not store Supabase tokens in this file."
  ]
}
'@
    if ($Mode -eq 'patchdrop') {
        Set-TestFile (Join-Path $root '__patch_drop__\pending-safe.patch') @'
diff --git a/main/resources/example.yml b/main/resources/example.yml
--- a/main/resources/example.yml
+++ b/main/resources/example.yml
@@ -1 +1 @@
-enabled: false
+enabled: true
'@
    }
    if ($Mode -eq 'leaseheld') {
        $expires = [DateTime]::UtcNow.AddHours(1).ToString('o')
        Set-TestFile (Join-Path $root '__patch_drop__\source-edit-locks\active-topic.lock\lease.json') @"
{
  "topic": "active-topic",
  "ownerId": "macmini-codex",
  "role": "macmini",
  "startedAtUtc": "2026-01-01T00:00:00.0000000Z",
  "expiresAtUtc": "$expires"
}
"@
    }
    if ($Mode -eq 'leasecorrupt') {
        New-Item -ItemType Directory -Force -Path (Join-Path $root '__patch_drop__\source-edit-locks\corrupt-topic.lock') | Out-Null
    }
    if ($Mode -ne 'secret') {
        $computerGeneratedAt = if ($Mode -eq 'computerstale') { [DateTime]::UtcNow.AddHours(-2).ToString('o') } else { [DateTime]::UtcNow.ToString('o') }
        Set-TestFile (Join-Path $root 'var\codex-smoke\computer-use-smoke.json') @"
{
  "schemaVersion": "awx.computer_use.smoke.v1",
  "generatedAt": "$computerGeneratedAt",
  "ok": true,
  "decision": "ok",
  "reachable": true,
  "appCount": 3,
  "runningCount": 2,
  "windowCount": 4,
  "sampleApps": [
    {"id": "MSEdge", "displayName": "Microsoft Edge", "windowCount": 1}
  ],
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
        $browserGeneratedAt = if ($Mode -eq 'browserstale') { [DateTime]::UtcNow.AddHours(-2).ToString('o') } else { [DateTime]::UtcNow.ToString('o') }
        Set-TestFile (Join-Path $root 'var\codex-smoke\browser-ui-smoke.json') @"
{
  "schemaVersion": "awx.browser_ui.smoke.v1",
  "generatedAt": "$browserGeneratedAt",
  "ok": true,
  "decision": "ok",
  "reachable": true,
  "localhost": true,
  "screenshotCaptured": true,
  "statusClass": "ui_visible",
  "targetContentVisible": true,
  "browserSurface": "iab",
  "url": "http://localhost:8080/pipeline-status",
  "screenshotPath": "C:\\unsafe\\browser-ui-smoke.png",
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
    }

    Set-TestFile (Join-Path $root 'scripts\smoke_supabase_readonly_snapshot.ps1') @'
param([string]$Root, [string]$OutputDir, [switch]$RequireProjectScope)
$mode = (Get-Content -Raw -LiteralPath (Join-Path $Root 'mode.txt')).Trim()
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
if ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt') {
    [ordered]@{
        schemaVersion = 'awx.supabase.readonly_snapshot_smoke.summary.v1'
        ok = $true
        decision = 'ok'
        projectScopeStatus = 'project_ref_ready'
        mcpReachable = $true
        mcpProbeSkipped = $false
        mcpDecision = 'mcp_endpoint_auth_required'
        mcpEndpointReachabilityEvidence = 'reachable_auth_required'
        evidenceNeeded = @()
        secretHits = 0
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'supabase-readonly-snapshot.summary.json') -Encoding UTF8
    Write-Host '[fake-smoke] ok=true projectScopeStatus=project_ref_ready'
    exit 0
}
[ordered]@{
    schemaVersion = 'awx.supabase.readonly_snapshot_smoke.summary.v1'
    ok = $false
    decision = 'evidence_needed'
    projectScopeStatus = 'project_ref_missing'
    mcpReachable = $false
    mcpProbeSkipped = $true
    mcpDecision = 'mcp_endpoint_probe_skipped'
    mcpEndpointReachabilityEvidence = 'probe_skipped'
    evidenceNeeded = @('project_ref_missing')
    secretHits = 0
    rawSecretPatternHits = 0
} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'supabase-readonly-snapshot.summary.json') -Encoding UTF8
Write-Host '[fake-smoke] evidence_needed: project_ref_missing'
exit 2
'@

    Set-TestFile (Join-Path $root 'scripts\supabase_apply_collected_evidence.ps1') @'
param([string]$Root, [string]$OutputDir)
$mode = (Get-Content -Raw -LiteralPath (Join-Path $Root 'mode.txt')).Trim()
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
if ($mode -eq 'secret') {
    Write-Host ('Bearer ' + 'abcdefghijklmnopqrstuvwxyz' + '123456')
    exit 4
}
[ordered]@{
    schemaVersion = 'awx.supabase.apply_collected_evidence.summary.v1'
    ok = ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt')
    decision = if ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt') { 'ok' } else { 'evidence_needed' }
    requiredMcpTools = @('execute_sql','get_advisors')
    requiredResultNames = @('schemas_and_tables','rls_and_table_flags','policies')
    nextActions = @('set_SUPABASE_PROJECT_REF','collect_get_advisors_rows')
    evidenceNeeded = @('env_missing:SUPABASE_PROJECT_REF')
    resultPathRecommendation = 'data/db-gap-report/supabase-query-results.json'
    advisorResultPathRecommendation = 'data/db-gap-report/supabase-advisors.json'
    secretHits = 0
    rawSecretPatternHits = 0
} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'supabase-apply-collected.summary.json') -Encoding UTF8
if ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt') {
    Write-Host '[fake-supabase-apply] ok=true'
    exit 0
}
Write-Host '[fake-supabase-apply] evidence_needed: missing result sets'
exit 2
'@

    Set-TestFile (Join-Path $root 'scripts\external_apply_collected_evidence.ps1') @'
param([string]$Root, [string]$OutputDir, [string]$Topic)
$mode = (Get-Content -Raw -LiteralPath (Join-Path $Root 'mode.txt')).Trim()
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
if ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt') {
    [ordered]@{
        schemaVersion = 'awx.external.apply_collected_evidence.summary.v1'
        ok = $true
        decision = 'ok'
        topic = $Topic
        requiredRoles = @('macmini','notebook')
        requiredProducerEvidenceFiles = @('macmini-node-smoke.json','macmini-producer-handoff.json','notebook-node-smoke.json','notebook-producer-handoff.json')
        requiredPatchDropSidecars = @('pendingNotice','.manifest.json','.patch','.report.md','.verify.log','.sha256.txt')
        requiredSourceIsolation = [ordered]@{ guard = 'PASS'; sourceRootKind = 'local-worktree'; directCanonicalSourceEdit = $false; desktopFinalProof = 'evidence_needed'; rawSecretPatternHits = 0 }
        nextActions = @()
        applyCollectedEvidenceCommand = "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root . -Topic $Topic"
        evidenceNeeded = @()
        copiedEvidenceCount = 2
        copiedHandoffCount = 2
        secretHits = 0
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'external-apply-collected.summary.json') -Encoding UTF8
    Write-Host '[fake-external-apply] ok=true'
    exit 0
}
[ordered]@{
    schemaVersion = 'awx.external.apply_collected_evidence.summary.v1'
    ok = $false
    decision = 'evidence_needed'
    topic = $Topic
    requiredRoles = @('macmini','notebook')
    requiredProducerEvidenceFiles = @('macmini-node-smoke.json','macmini-producer-handoff.json','notebook-node-smoke.json','notebook-producer-handoff.json')
    requiredPatchDropSidecars = @('pendingNotice','.manifest.json','.patch','.report.md','.verify.log','.sha256.txt')
    requiredSourceIsolation = [ordered]@{ guard = 'PASS'; sourceRootKind = 'local-worktree'; directCanonicalSourceEdit = $false; desktopFinalProof = 'evidence_needed'; rawSecretPatternHits = 0 }
    nextActions = @('run_macmini_external_node_smoke','collect_macmini_producer_handoff_json','submit_macmini_patchdrop_v3_bundle_sidecars','run_notebook_external_node_smoke','collect_notebook_producer_handoff_json','submit_notebook_patchdrop_v3_bundle_sidecars')
    applyCollectedEvidenceCommand = "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root . -Topic $Topic"
    evidenceNeeded = @('intake:external node smoke missing role=macmini','audit:external node smoke missing role=notebook')
    copiedEvidenceCount = 0
    copiedHandoffCount = 0
    secretHits = 0
    rawSecretPatternHits = 0
} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'external-apply-collected.summary.json') -Encoding UTF8
Write-Host '[fake-external-apply] evidence_needed: producer evidence missing'
exit 2
'@

    Set-TestFile (Join-Path $root 'scripts\source_health_scorecard.py') @'
import argparse, json
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--root')
parser.add_argument('--output')
args = parser.parse_args()
root = Path(args.root)
current_audit = root / 'var' / 'codex-smoke' / 'awx-mcp-completion-audit-current.json'
current_audit_exists_before_source_health = current_audit.is_file()
Path(args.output).parent.mkdir(parents=True, exist_ok=True)
Path(args.output).write_text(json.dumps({
    "decision": "source_health_scorecard",
    "activeRiskCount": 0,
    "completionAuditFreshness": {
        "path": "var/codex-smoke/awx-mcp-completion-audit-current.json" if current_audit_exists_before_source_health else "",
        "fresh": current_audit_exists_before_source_health,
        "status": "current" if current_audit_exists_before_source_health else "missing_before_source_health"
    },
    "nextActionDetails": [
        {
            "action": "collect-supabase-live-proof",
            "nodeRole": "desktop",
            "targetService": "supabase",
            "readOnly": True,
            "mutationAllowed": False,
            "requiredMcpTools": ["execute_sql", "get_advisors"],
            "requiredEnv": [
                {"name": "SUPABASE_PROJECT_REF", "sensitive": False},
                {"name": "SUPABASE_ACCESS_TOKEN", "sensitive": True}
            ],
            "applyCollectedEvidenceCommand": "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\supabase_apply_collected_evidence.ps1",
            "decision": "evidence_needed"
        },
        {
            "action": "collect-external-evidence-files",
            "nodeRole": "desktop",
            "targetRole": "macmini",
            "requiredSidecars": [".patch", ".report.md", ".verify.log", ".sha256.txt", ".manifest.json", "pendingNotice"],
            "requiredSourceIsolation": {
                "guard": "PASS",
                "sourceRootKind": "local-worktree",
                "directCanonicalSourceEdit": False,
                "desktopFinalProof": "evidence_needed",
                "rawSecretPatternHits": 0
            },
            "applyCollectedEvidenceCommand": "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop",
            "producerCommandTemplates": [
                "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> --canonical-root <desktop-canonical-root> --node-role macmini",
                "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> --canonical-root <desktop-canonical-root> --patchdrop-root <PatchDrop> --producer-script <PatchDrop>\\producer_bundle.py --node-role macmini --topic mcp-control-loop --pathspec <relative/source/path>"
            ],
            "decision": "evidence_needed"
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
            "applyCollectedEvidenceCommand": "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search",
            "nextActions": ["create_or_point_archive_index", "verify_archive_index_path", "rerun_archive_search"],
            "decision": "evidence_needed"
        }
    ]
}), encoding='utf-8')
print('[fake-source-health] ok')
'@

    Set-TestFile (Join-Path $root 'scripts\awx_mcp_completion_audit.py') @'
import argparse, json
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--root')
parser.add_argument('--output')
args = parser.parse_args()
root = Path(args.root)
latest_path = root / "var" / "codex-smoke" / "goal-next-auto.latest.json"
missing = []
latest = {}
if not latest_path.is_file():
    missing.append("latest")
else:
    latest = json.loads(latest_path.read_text(encoding='utf-8-sig'))
packet_raw = str(latest.get("commandPacketPath") or "")
packet_path = Path(packet_raw) if packet_raw else Path()
if packet_raw and not packet_path.is_absolute():
    packet_path = root / packet_path
if not packet_raw or not packet_path.is_file():
    missing.append("command-packet")
Path(args.output).parent.mkdir(parents=True, exist_ok=True)
Path(args.output).write_text(json.dumps({"ok": not missing, "status": "local_control_tower_ready" if not missing else "missing_goal_next_packet", "missing": missing}), encoding='utf-8')
if missing:
    print('[fake-completion-audit] missing=' + ','.join(missing))
    raise SystemExit(5)
print('[fake-completion-audit] ok')
'@

    Set-TestFile (Join-Path $root 'scripts\awx_mcp_toolbox.py') @'
import argparse, json
import sys
parser = argparse.ArgumentParser()
parser.add_argument('tool')
parser.add_argument('--input-json', default='')
args = parser.parse_args()
payload = json.loads(args.input_json or sys.stdin.read() or '{}')
role_pathspec = (
    payload.get("role_pathspec")
    or payload.get("role_pathspecs")
    or payload.get("producer_pathspecs")
    or {}
)
role_pathspec_counts = {
    role: len(values) if isinstance(values, list) else (1 if isinstance(values, str) and values else 0)
    for role, values in role_pathspec.items()
}
missing = [
    role
    for role in ("macmini", "notebook")
    if role_pathspec_counts.get(role, 0) < 1
]
if missing:
    print(json.dumps({
        "ok": False,
        "toolName": args.tool,
        "topic": payload.get("topic", ""),
        "localReady": False,
        "completionReady": False,
        "desktopFinalProof": "evidence_needed",
        "externalEvidenceComplete": False,
        "failReason": "pathspec-required",
        "missingPathspecRoles": missing,
        "nextActions": [{"action": "assign-producer-role-pathspec"}],
    }, sort_keys=True))
    raise SystemExit(0)
print(json.dumps({
    "ok": True,
    "toolName": args.tool,
    "topic": payload.get("topic", ""),
    "localReady": True,
    "completionReady": False,
    "desktopFinalProof": "evidence_needed",
    "externalEvidenceComplete": False,
    "rolePathspecCounts": role_pathspec_counts,
    "nextActions": [{"action": "collect-supabase-live-proof"}],
}, sort_keys=True))
'@

    return $root
}

$script = Join-Path $PSScriptRoot 'goal_next_auto.ps1'
$nextScript = Join-Path $PSScriptRoot 'goal_next.ps1'
$failures = 0

try {
    $help = Invoke-Captured -Arguments @('-File', $script, '-Help')
    Assert-True 'goal next help exits zero' ($help.ExitCode -eq 0) "expected exit 0; output=$($help.Output)"
    Assert-Contains 'goal next help names Supabase project env' $help.Output 'SUPABASE_PROJECT_REF'
    Assert-Contains 'goal next help names smoke script' $help.Output 'smoke_supabase_readonly_snapshot.ps1'
    Assert-Contains 'goal next help names Supabase apply script' $help.Output 'supabase_apply_collected_evidence.ps1'
    Assert-Contains 'goal next help names external apply script' $help.Output 'external_apply_collected_evidence.ps1'
    Assert-Contains 'goal next help names desktop control loop' $help.Output 'awx_mcp_toolbox.py desktop_control_loop'
    Assert-Contains 'goal next help names status mode' $help.Output '-Status'
    Assert-Contains 'goal next help names ensure-fresh mode' $help.Output '-EnsureFresh'
    Assert-True 'goal next help prints no raw auth header' (-not ($help.Output -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*')) "output=$($help.Output)"

    $nextHelp = Invoke-Captured -Arguments @('-File', $nextScript, '-Help')
    Assert-True 'goal next wrapper help exits zero' ($nextHelp.ExitCode -eq 0) "expected exit 0; output=$($nextHelp.Output)"
    Assert-Contains 'goal next wrapper help names ensure-fresh default' $nextHelp.Output '-EnsureFresh'
    Assert-Contains 'goal next wrapper help names auto script' $nextHelp.Output 'goal_next_auto.ps1'
    Assert-True 'goal next wrapper help prints no raw auth header' (-not ($nextHelp.Output -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*')) "output=$($nextHelp.Output)"

    $partialRoot = New-FakeGoalRoot -Mode 'partial'
    $partialOutput = Join-Path $partialRoot 'out'
    $partial = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $partialOutput, '-Topic', 'mcp-control-loop')
    Assert-True 'partial goal next exits evidence_needed' ($partial.ExitCode -eq 2) "expected exit 2; output=$($partial.Output)"
    Assert-Contains 'partial goal next names evidence needed' $partial.Output 'decision=evidence_needed'
    Assert-Contains 'partial goal next ran smoke' $partial.Output 'supabaseSmokeExit=2'
    Assert-Contains 'partial goal next ran Supabase apply' $partial.Output 'supabaseApplyExit=2'
    Assert-Contains 'partial goal next ran external apply' $partial.Output 'externalApplyExit=2'
    Assert-Contains 'partial goal next ran desktop control loop' $partial.Output 'desktopControlLoopExit=0'
    Assert-Contains 'partial goal next runs completion audit after command packet is current' $partial.Output 'completionAuditExit=0'
    $partialSummaryPath = Join-Path $partialOutput 'goal-next-auto.summary.json'
    Assert-True 'partial goal next writes summary' (Test-Path $partialSummaryPath) 'missing partial summary'
    if (Test-Path $partialSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $partialSummaryPath | ConvertFrom-Json
        Assert-True 'partial summary reports evidence_needed' ($summary.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary reports not ok' ($summary.ok -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records first action' ([string]$summary.firstAction -eq 'set_SUPABASE_PROJECT_REF') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records first action source' ([string]$summary.firstActionSource -eq 'supabase_apply') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records failure classification' ([string]$summary.failureClassification -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records next action count' ([int]$summary.nextActionCount -ge 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records next action sources' (($summary.nextActionSources -join ',').Contains('supabase_apply')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records safe top actions' (@($summary.topActions).Count -gt 0 -and [string]$summary.topActions[0].action -eq 'set_SUPABASE_PROJECT_REF') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records git root as string' ($summary.preflight.gitRoot -is [string]) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop control loop exit' ([int]$summary.desktopControlLoopExit -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop local readiness' ($summary.desktopControlLoop.localReady -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop completion readiness' ($summary.desktopControlLoop.completionReady -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop final proof state' ($summary.desktopControlLoop.desktopFinalProof -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop next-action count' ([int]$summary.desktopControlLoop.nextActionCount -eq 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records final completion audit exit' ([int]$summary.completionAuditExit -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        $sourceHealthArtifact = Get-Content -Raw -LiteralPath $summary.artifacts.sourceHealth | ConvertFrom-Json
        Assert-True 'partial source-health saw fresh completion audit current' (
            [string]$sourceHealthArtifact.completionAuditFreshness.path -eq 'var/codex-smoke/awx-mcp-completion-audit-current.json' -and
            $sourceHealthArtifact.completionAuditFreshness.fresh -eq $true
        ) "sourceHealth=$($sourceHealthArtifact | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records computer smoke presence' ($summary.computerUse.present -eq $true -and $summary.computerUse.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records computer smoke readiness' ($summary.computerUse.ok -eq $true -and $summary.computerUse.reachable -eq $true -and [int]$summary.computerUse.appCount -eq 3) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records computer smoke path safely' (-not ([string]$summary.computerUse.path -match '[A-Za-z]:\\')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records browser smoke presence' ($summary.browserUse.present -eq $true -and $summary.browserUse.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records browser smoke readiness' ($summary.browserUse.ok -eq $true -and $summary.browserUse.reachable -eq $true -and $summary.browserUse.localhost -eq $true -and $summary.browserUse.screenshotCaptured -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records browser smoke status class' ([string]$summary.browserUse.statusClass -eq 'ui_visible' -and $summary.browserUse.targetContentVisible -eq $true -and [string]$summary.browserUse.browserSurface -eq 'iab') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary keeps browser smoke safe' (-not (($summary.browserUse | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary parses supabase smoke summary' ($summary.supabaseSmoke.parsed -eq $true -and [string]$summary.supabaseSmoke.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records supabase MCP probe skipped state' ($summary.supabaseSmoke.mcpProbeSkipped -eq $true -and [string]$summary.supabaseSmoke.mcpDecision -eq 'mcp_endpoint_probe_skipped') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records supabase MCP reachability evidence' ([string]$summary.supabaseSmoke.mcpEndpointReachabilityEvidence -eq 'probe_skipped' -and [string]$summary.supabaseSmoke.projectScopeStatus -eq 'project_ref_missing') "summary=$($summary | ConvertTo-Json -Compress)"
        $defaultSupabaseSmokeSummaryPath = Join-Path $partialRoot 'var\codex-smoke\supabase-readonly-snapshot\supabase-readonly-snapshot.summary.json'
        Assert-True 'partial goal next mirrors fresh supabase smoke for completion audit' (Test-Path $defaultSupabaseSmokeSummaryPath) "missing default smoke summary at $defaultSupabaseSmokeSummaryPath"
        $defaultSupabaseSmokeSummary = Get-Content -Raw -LiteralPath $defaultSupabaseSmokeSummaryPath | ConvertFrom-Json
        Assert-True 'partial goal next default supabase smoke is fresh run output' (
            [string]$defaultSupabaseSmokeSummary.mcpDecision -eq 'mcp_endpoint_probe_skipped' -and
            [string]$defaultSupabaseSmokeSummary.projectScopeStatus -eq 'project_ref_missing'
        ) "defaultSupabaseSmokeSummary=$($defaultSupabaseSmokeSummary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary parses supabase apply summary' ($summary.supabaseApply.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase apply decision' ($summary.supabaseApply.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase required MCP tools' (($summary.supabaseApply.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase required result names' (($summary.supabaseApply.requiredResultNames -join ',') -eq 'schemas_and_tables,rls_and_table_flags,policies') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary parses external apply summary' ($summary.externalApply.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external apply decision' ($summary.externalApply.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external sidecar contract' (($summary.externalApply.requiredPatchDropSidecars -join ',').Contains('.patch')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external source isolation contract' ($summary.externalApply.requiredSourceIsolation.guard -eq 'PASS') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external copied evidence counts' ([int]$summary.externalApply.copiedEvidenceCount -eq 0 -and [int]$summary.externalApply.copiedHandoffCount -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external next action' (($summary.externalApply.nextActions -join ',').Contains('run_macmini_external_node_smoke')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external apply command' ([string]$summary.externalApply.applyCollectedEvidenceCommand -eq 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes desktop control loop artifact' (Test-Path $summary.artifacts.desktopControlLoop) "summary=$($summary | ConvertTo-Json -Compress)"
        if (Test-Path $summary.artifacts.desktopControlLoop) {
            $desktopControlLoopArtifact = Get-Content -Raw -LiteralPath $summary.artifacts.desktopControlLoop | ConvertFrom-Json
            Assert-True 'partial desktop control loop received macmini pathspec' ([int]$desktopControlLoopArtifact.rolePathspecCounts.macmini -eq 1) "desktopControlLoop=$($desktopControlLoopArtifact | ConvertTo-Json -Compress)"
            Assert-True 'partial desktop control loop received notebook pathspec' ([int]$desktopControlLoopArtifact.rolePathspecCounts.notebook -eq 1) "desktopControlLoop=$($desktopControlLoopArtifact | ConvertTo-Json -Compress)"
        }
        Assert-True 'partial summary writes next-actions artifact' (Test-Path $summary.artifacts.nextActions) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes command packet artifact' (Test-Path $summary.artifacts.commandPacket) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes command packet markdown artifact' (Test-Path $summary.artifacts.commandPacketMarkdown) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes collection packet artifact' (Test-Path $summary.artifacts.collectionPacket) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes collection packet markdown artifact' (Test-Path $summary.artifacts.collectionPacketMarkdown) "summary=$($summary | ConvertTo-Json -Compress)"
        $collectionPacket = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacket | ConvertFrom-Json
        Assert-True 'partial collection packet reports evidence_needed' ([string]$collectionPacket.decision -eq 'evidence_needed') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase lane' ($collectionPacket.supabase.requiredEnvNames -contains 'SUPABASE_PROJECT_REF' -and $collectionPacket.supabase.requiredEnvNames -contains 'SUPABASE_ACCESS_TOKEN') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase MCP tools' (($collectionPacket.supabase.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase MCP config presence' ($collectionPacket.supabase.mcpConfig.present -eq $true -and $collectionPacket.supabase.mcpConfig.parsed -eq $true) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase MCP read-only scope' ($collectionPacket.supabase.mcpConfig.readOnly -eq $true -and $collectionPacket.supabase.mcpConfig.projectRefSource -eq 'SUPABASE_PROJECT_REF') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase MCP host and features' ($collectionPacket.supabase.mcpConfig.serverHost -eq 'mcp.supabase.com' -and (($collectionPacket.supabase.mcpConfig.features -join ',') -eq 'database,debugging,docs')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records no stored supabase token' ($collectionPacket.supabase.mcpConfig.tokenStored -eq $false) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet preserves computer count-only fields' (
            [int]$collectionPacket.computerUse.appCount -eq 3 -and
            [int]$collectionPacket.computerUse.runningCount -eq 2 -and
            [int]$collectionPacket.computerUse.windowCount -eq 4
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet keeps empty computer next-actions as array' (
            $collectionPacket.computerUse.nextActions -is [array] -and
            @($collectionPacket.computerUse.nextActions).Count -eq 0
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet preserves browser count-only fields' (
            $collectionPacket.browserUse.reachable -eq $true -and
            $collectionPacket.browserUse.localhost -eq $true -and
            $collectionPacket.browserUse.screenshotCaptured -eq $true
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet preserves browser status labels' ([string]$collectionPacket.browserUse.statusClass -eq 'ui_visible' -and $collectionPacket.browserUse.targetContentVisible -eq $true -and [string]$collectionPacket.browserUse.browserSurface -eq 'iab') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet keeps browser smoke path relative' ([string]$collectionPacket.browserUse.outputPath -eq 'var/codex-smoke/browser-ui-smoke.json') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet avoids raw browser URL and screenshot path' (-not (($collectionPacket.browserUse | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records external roles' (($collectionPacket.external.requiredRoles -join ',') -eq 'macmini,notebook') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records external sidecars' (($collectionPacket.external.requiredSidecars -join ',').Contains('.manifest.json') -and ($collectionPacket.external.requiredSidecars -join ',').Contains('.sha256.txt')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records source isolation' ($collectionPacket.external.requiredSourceIsolation.guard -eq 'PASS' -and $collectionPacket.external.requiredSourceIsolation.directCanonicalSourceEdit -eq $false) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records archive lane' (
            $collectionPacket.archive.readOnly -eq $true -and
            $collectionPacket.archive.mutationAllowed -eq $false -and
            (($collectionPacket.archive.requiredEnvNames -join ',') -eq 'ARCHIVE_INDEX,NAS_ARCHIVE_ROOT') -and
            [string]$collectionPacket.archive.indexPathRecommendation -eq 'BackupsXS/index.jsonl'
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records archive MCP tools' (($collectionPacket.archive.requiredMcpTools -join ',') -eq 'archive.search,archive.index_build') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records archive next action' (($collectionPacket.archive.nextActions -join ',').Contains('verify_archive_index_path')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet avoids Windows absolute paths' (-not (($collectionPacket | ConvertTo-Json -Depth 50) -match '[A-Za-z]:\\')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet is secret safe' (-not (($collectionPacket | ConvertTo-Json -Depth 50) -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        $collectionPacketMarkdown = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacketMarkdown
        Assert-Contains 'partial collection packet markdown records supabase env' $collectionPacketMarkdown 'SUPABASE_PROJECT_REF'
        Assert-Contains 'partial collection packet markdown records supabase result path' $collectionPacketMarkdown 'supabase.resultPathRecommendation=data/db-gap-report/supabase-query-results.json'
        Assert-Contains 'partial collection packet markdown records supabase advisor path' $collectionPacketMarkdown 'supabase.advisorResultPathRecommendation=data/db-gap-report/supabase-advisors.json'
        Assert-Contains 'partial collection packet markdown records supabase MCP read-only config' $collectionPacketMarkdown 'supabase.mcpConfig.readOnly=True'
        Assert-Contains 'partial collection packet markdown records supabase MCP project ref source' $collectionPacketMarkdown 'supabase.mcpConfig.projectRefSource=SUPABASE_PROJECT_REF'
        Assert-Contains 'partial collection packet markdown records supabase MCP host' $collectionPacketMarkdown 'supabase.mcpConfig.serverHost=mcp.supabase.com'
        Assert-Contains 'partial collection packet markdown records supabase MCP token guard' $collectionPacketMarkdown 'supabase.mcpConfig.tokenStored=False'
        Assert-Contains 'partial collection packet markdown records external sidecar' $collectionPacketMarkdown '.manifest.json'
        Assert-Contains 'partial collection packet markdown records archive index path' $collectionPacketMarkdown 'archive.indexPathRecommendation=BackupsXS/index.jsonl'
        Assert-Contains 'partial collection packet markdown records archive env names' $collectionPacketMarkdown 'archive.requiredEnvNames=ARCHIVE_INDEX,NAS_ARCHIVE_ROOT'
        Assert-Contains 'partial collection packet markdown records computer window-title guard' $collectionPacketMarkdown 'computerUse.storesWindowTitles=False'
        Assert-Contains 'partial collection packet markdown records computer app count' $collectionPacketMarkdown 'computerUse.appCount=3'
        Assert-Contains 'partial collection packet markdown records computer running count' $collectionPacketMarkdown 'computerUse.runningCount=2'
        Assert-Contains 'partial collection packet markdown records computer window count' $collectionPacketMarkdown 'computerUse.windowCount=4'
        Assert-Contains 'partial collection packet markdown records computer secret count' $collectionPacketMarkdown 'computerUse.secretHits=0'
        Assert-Contains 'partial collection packet markdown records browser output path' $collectionPacketMarkdown 'browserUse.outputPath=var/codex-smoke/browser-ui-smoke.json'
        Assert-Contains 'partial collection packet markdown records browser localhost' $collectionPacketMarkdown 'browserUse.localhost=True'
        Assert-Contains 'partial collection packet markdown records browser screenshot state' $collectionPacketMarkdown 'browserUse.screenshotCaptured=True'
        Assert-True 'partial collection packet markdown is secret safe' (-not ($collectionPacketMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "collectionPacketMarkdown=$collectionPacketMarkdown"
        $commandPacket = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacket | ConvertFrom-Json
        Assert-True 'partial command packet reports evidence_needed' ([string]$commandPacket.decision -eq 'evidence_needed') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet records commands' ([int]$commandPacket.commandCount -ge 3) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes supabase lane' (($commandPacket.lanes -join ',') -match 'supabase') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes external desktop lane' (($commandPacket.lanes -join ',') -match 'external_desktop') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes external producer lane' (($commandPacket.lanes -join ',') -match 'external_producer') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes archive lane' (($commandPacket.lanes -join ',') -match 'archive') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet preserves producer placeholders' (($commandPacket.commands.command -join "`n") -match '<producer-local-worktree>' -and ($commandPacket.commands.command -join "`n") -match '<desktop-canonical-root>') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet keeps env names only' (($commandPacket.commands.requiredEnvNames -join ',') -match 'SUPABASE_PROJECT_REF' -and ($commandPacket.commands.requiredEnvNames -join ',') -match 'SUPABASE_ACCESS_TOKEN') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries full computer smoke summary' ($commandPacket.computerUse.ok -eq $true -and $commandPacket.computerUse.reachable -eq $true -and [int]$commandPacket.computerUse.appCount -eq 3) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet preserves computer count-only fields' ([int]$commandPacket.computerUse.runningCount -eq 2 -and [int]$commandPacket.computerUse.windowCount -eq 4) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries browser smoke summary' ($commandPacket.browserUse.ok -eq $true -and $commandPacket.browserUse.reachable -eq $true -and $commandPacket.browserUse.localhost -eq $true -and $commandPacket.browserUse.screenshotCaptured -eq $true) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries browser status labels' ([string]$commandPacket.browserUse.statusClass -eq 'ui_visible' -and $commandPacket.browserUse.targetContentVisible -eq $true -and [string]$commandPacket.browserUse.browserSurface -eq 'iab') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet keeps browser raw fields disabled' ($commandPacket.browserUse.storesRawUrl -eq $false -and $commandPacket.browserUse.storesScreenshotPath -eq $false) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $supabaseCommand = @($commandPacket.commands | Where-Object { $_.lane -eq 'supabase' -and $_.action -eq 'collect-supabase-live-proof' } | Select-Object -First 1)
        Assert-True 'partial command packet carries supabase read-only contract' ($supabaseCommand.readOnly -eq $true -and $supabaseCommand.mutationAllowed -eq $false) "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase MCP endpoint template' ([string]$supabaseCommand.mcpEndpointTemplate -match 'mcp\.supabase\.com/mcp' -and [string]$supabaseCommand.mcpEndpointTemplate -match 'read_only=true') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase docs refs' (($supabaseCommand.docsRefs -join ',') -match 'supabase.com/docs/guides/ai-tools/mcp') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase query results path' ([string]$supabaseCommand.resultPathRecommendation -eq 'data/db-gap-report/supabase-query-results.json') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase advisor results path' ([string]$supabaseCommand.advisorResultPathRecommendation -eq 'data/db-gap-report/supabase-advisors.json') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase required MCP tools' (($supabaseCommand.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase artifact paths' (($supabaseCommand.artifactPaths -join ',').Contains('supabase-execute-sql-collection.packet.json') -and ($supabaseCommand.artifactPaths -join ',').Contains('supabase-query-results.template.json')) "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        $archiveCommand = @($commandPacket.commands | Where-Object { $_.lane -eq 'archive' -and $_.action -eq 'collect-archive-index-proof' } | Select-Object -First 1)
        Assert-True 'partial command packet carries archive read-only contract' ($archiveCommand.readOnly -eq $true -and $archiveCommand.mutationAllowed -eq $false) "archiveCommand=$($archiveCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries archive index path' ([string]$archiveCommand.indexPathRecommendation -eq 'BackupsXS/index.jsonl' -and [string]$archiveCommand.archiveRootRecommendation -eq 'BackupsXS') "archiveCommand=$($archiveCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries archive env names' (($archiveCommand.requiredEnvNames -join ',') -eq 'ARCHIVE_INDEX,NAS_ARCHIVE_ROOT') "archiveCommand=$($archiveCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries archive MCP tools' (($archiveCommand.requiredMcpTools -join ',') -eq 'archive.search,archive.index_build') "archiveCommand=$($archiveCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet avoids Windows absolute paths in commands' (-not (($commandPacket.commands.command -join "`n") -match '[A-Za-z]:\\')) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet is secret safe' (-not (($commandPacket.commands.command -join "`n") -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $commandPacketMarkdown = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacketMarkdown
        Assert-Contains 'partial command packet markdown records supabase lane' $commandPacketMarkdown 'lane=supabase'
        Assert-Contains 'partial command packet markdown records archive lane' $commandPacketMarkdown 'lane=archive'
        Assert-Contains 'partial command packet markdown records archive index path' $commandPacketMarkdown 'index=BackupsXS/index.jsonl'
        Assert-Contains 'partial command packet markdown records supabase result path' $commandPacketMarkdown 'results=data/db-gap-report/supabase-query-results.json'
        Assert-Contains 'partial command packet markdown records supabase advisor path' $commandPacketMarkdown 'advisors=data/db-gap-report/supabase-advisors.json'
        Assert-Contains 'partial command packet markdown records supabase MCP tools' $commandPacketMarkdown 'mcpTools=execute_sql,get_advisors'
        Assert-Contains 'partial command packet markdown records producer placeholder' $commandPacketMarkdown '<producer-local-worktree>'
        Assert-Contains 'partial command packet markdown records computer summary' $commandPacketMarkdown 'computerUse.decision=ok reachable=True stale=False appCount=3 runningCount=2 windowCount=4 storesRawAppNames=False storesWindowTitles=False secretHits=0 outputPath=var/codex-smoke/computer-use-smoke.json'
        Assert-Contains 'partial command packet markdown records browser summary' $commandPacketMarkdown 'browserUse.decision=ok reachable=True localhost=True stale=False screenshotCaptured=True storesRawUrl=False storesScreenshotPath=False secretHits=0 outputPath=var/codex-smoke/browser-ui-smoke.json'
        Assert-True 'partial command packet markdown is secret safe' (-not ($commandPacketMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "commandPacketMarkdown=$commandPacketMarkdown"
        $nextActions = Get-Content -Raw -LiteralPath $summary.artifacts.nextActions | ConvertFrom-Json
        Assert-True 'partial next-actions records entries' ([int]$nextActions.entryCount -ge 5) "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions includes supabase source' (($nextActions.sources -join ',') -match 'supabase_apply') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions includes external source' (($nextActions.sources -join ',') -match 'external_apply') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions includes desktop source' (($nextActions.sources -join ',') -match 'desktop_control_loop') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions includes source-health detail source' (($nextActions.sources -join ',') -match 'source_health_scorecard') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $sourceHealthActions = @($nextActions.actions | Where-Object { $_.source -eq 'source_health_scorecard' })
        Assert-True 'partial next-actions preserves source-health detail count' ($sourceHealthActions.Count -eq 3) "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $supabaseDetail = $sourceHealthActions | Where-Object { $_.action -eq 'collect-supabase-live-proof' } | Select-Object -First 1
        Assert-True 'partial next-actions preserves Supabase required MCP tools' (($supabaseDetail.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "detail=$($supabaseDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions preserves Supabase env names only' (($supabaseDetail.requiredEnvNames -join ',') -eq 'SUPABASE_PROJECT_REF,SUPABASE_ACCESS_TOKEN') "detail=$($supabaseDetail | ConvertTo-Json -Compress)"
        $externalDetail = $sourceHealthActions | Where-Object { $_.action -eq 'collect-external-evidence-files' } | Select-Object -First 1
        Assert-True 'partial next-actions preserves external sidecar contract' (($externalDetail.requiredSidecars -join ',').Contains('.manifest.json')) "detail=$($externalDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions preserves external source isolation guard' ($externalDetail.requiredSourceIsolation.guard -eq 'PASS') "detail=$($externalDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions preserves producer command templates' (($externalDetail.producerCommandTemplates -join "`n") -match '<producer-local-worktree>' -and ($externalDetail.producerCommandTemplates -join "`n") -match '<desktop-canonical-root>') "detail=$($externalDetail | ConvertTo-Json -Compress)"
        $archiveDetail = $sourceHealthActions | Where-Object { $_.action -eq 'collect-archive-index-proof' } | Select-Object -First 1
        Assert-True 'partial next-actions preserves archive env names' (($archiveDetail.requiredEnvNames -join ',') -eq 'ARCHIVE_INDEX,NAS_ARCHIVE_ROOT') "detail=$($archiveDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions preserves archive index path' ([string]$archiveDetail.indexPathRecommendation -eq 'BackupsXS/index.jsonl') "detail=$($archiveDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions keeps supabase first' (($nextActions.actions | Select-Object -First 1).source -eq 'supabase_apply') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $latestPath = Join-Path $partialRoot 'var\codex-smoke\goal-next-auto.latest.json'
        Assert-True 'partial goal next writes stable latest pointer' (Test-Path $latestPath) "missing latest pointer at $latestPath"
        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        Assert-True 'partial latest pointer records summary path' ([string]$latest.summaryPath -eq $partialSummaryPath) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records next-actions path' ([string]$latest.nextActionsPath -eq [string]$summary.artifacts.nextActions) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records decision' ([string]$latest.decision -eq 'evidence_needed') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records freshness age' ([double]$latest.latestGeneratedAtAgeMinutes -ge 0 -and [double]$latest.latestGeneratedAtAgeMinutes -lt 5) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records stale threshold' ([int]$latest.latestStaleAfterMinutes -eq 60) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records not stale at write' ($latest.staleLatest -eq $false) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records expires at' (-not [string]::IsNullOrWhiteSpace([string]$latest.latestExpiresAt)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records digest path' (-not [string]::IsNullOrWhiteSpace([string]$latest.digestPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest digest exists' (Test-Path $latest.digestPath) "missing latest digest at $($latest.digestPath)"
        Assert-True 'partial latest pointer records digest markdown path' (-not [string]::IsNullOrWhiteSpace([string]$latest.digestMarkdownPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest digest markdown exists' (Test-Path $latest.digestMarkdownPath) "missing latest digest markdown at $($latest.digestMarkdownPath)"
        Assert-True 'partial latest pointer records breadcrumb timeline path' (-not [string]::IsNullOrWhiteSpace([string]$latest.breadcrumbTimelinePath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb timeline exists' (Test-Path $latest.breadcrumbTimelinePath) "missing breadcrumb timeline at $($latest.breadcrumbTimelinePath)"
        Assert-True 'partial latest pointer records breadcrumb fusion path' (-not [string]::IsNullOrWhiteSpace([string]$latest.breadcrumbFusionPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb fusion exists' (Test-Path $latest.breadcrumbFusionPath) "missing breadcrumb fusion at $($latest.breadcrumbFusionPath)"
        $breadcrumbFusion = Get-Content -Raw -LiteralPath $latest.breadcrumbFusionPath | ConvertFrom-Json
        Assert-True 'partial breadcrumb fusion records schema and latest tile' ([string]$breadcrumbFusion.schemaVersion -eq 'awx.goal_next_auto.breadcrumb_fusion.v1' -and [string]$breadcrumbFusion.latestRawTile.nextAction -eq 'set_SUPABASE_PROJECT_REF') "fusion=$($breadcrumbFusion | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb fusion records repeated action count' ($breadcrumbFusion.timelineRowsRead -eq 1 -and [string]$breadcrumbFusion.repeatedFirstActions[0].action -eq 'set_SUPABASE_PROJECT_REF' -and [int]$breadcrumbFusion.repeatedFirstActions[0].count -eq 1) "fusion=$($breadcrumbFusion | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb fusion classifies external input gate' ([string]$breadcrumbFusion.externalInputGate.status -eq 'external_input_needed' -and $breadcrumbFusion.externalInputGate.localPatchJustified -eq $false -and [string]$breadcrumbFusion.externalInputGate.action -eq 'set_SUPABASE_PROJECT_REF') "fusion=$($breadcrumbFusion | ConvertTo-Json -Compress)"
        Assert-True 'partial summary surfaces external input gate' ([string]$summary.externalInputGate.status -eq 'external_input_needed' -and $summary.externalInputGate.localPatchJustified -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer surfaces external input gate' ([string]$latest.externalInputGate.status -eq 'external_input_needed' -and [string]$latest.externalInputGate.action -eq 'set_SUPABASE_PROJECT_REF') "latest=$($latest | ConvertTo-Json -Compress)"
        $digestMarkdown = Get-Content -Raw -LiteralPath $latest.digestMarkdownPath
        Assert-Contains 'partial digest markdown surfaces external input gate' $digestMarkdown 'externalInputGate.status=external_input_needed'
        Assert-Contains 'partial digest markdown surfaces local patch decision' $digestMarkdown 'localPatchJustified=False'
        Assert-True 'partial breadcrumb fusion keeps paths safe' (-not (($breadcrumbFusion | ConvertTo-Json -Depth 50) -match '[A-Za-z]:\\')) "fusion=$($breadcrumbFusion | ConvertTo-Json -Compress)"
        $breadcrumbLines = @(Get-Content -LiteralPath $latest.breadcrumbTimelinePath)
        Assert-True 'partial breadcrumb timeline has one run row' ($breadcrumbLines.Count -eq 1) "breadcrumbLines=$($breadcrumbLines -join "`n")"
        $breadcrumb = $breadcrumbLines[-1] | ConvertFrom-Json
        Assert-True 'partial breadcrumb records schema and decision' ([string]$breadcrumb.schemaVersion -eq 'awx.goal_next_auto.breadcrumb.v1' -and [string]$breadcrumb.decision -eq 'evidence_needed') "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb records tool steps' ((@($breadcrumb.toolSteps).name -join ',').Contains('supabase_smoke') -and (@($breadcrumb.toolSteps).name -join ',').Contains('completion_audit')) "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb records raw tile summary' ([string]$breadcrumb.rawTile.schemaVersion -eq 'awx.goal_next_auto.raw_tile.v1' -and [string]$breadcrumb.rawTile.nextAction -eq 'set_SUPABASE_PROJECT_REF') "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb records trace keys' ((@($breadcrumb.trace.keys) -join ',').Contains('goalNext.decision') -and (@($breadcrumb.trace.keys) -join ',').Contains('goalNext.previousBreadcrumb.present')) "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb records mdc labels' ([string]$breadcrumb.mdc.nodeRole -eq 'desktop' -and [string]$breadcrumb.mdc.topic -eq 'mcp-control-loop' -and [string]$breadcrumb.mdc.root -eq '<desktop-canonical-root>') "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb keeps paths safe' (-not (($breadcrumb | ConvertTo-Json -Depth 50) -match '[A-Za-z]:\\')) "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records previous breadcrumb absence' ($summary.previousBreadcrumb.present -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records command packet path' (-not [string]::IsNullOrWhiteSpace([string]$latest.commandPacketPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest command packet exists' (Test-Path $latest.commandPacketPath) "missing latest command packet at $($latest.commandPacketPath)"
        Assert-True 'partial latest pointer records collection packet path' (-not [string]::IsNullOrWhiteSpace([string]$latest.collectionPacketPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest collection packet exists' (Test-Path $latest.collectionPacketPath) "missing latest collection packet at $($latest.collectionPacketPath)"
        Assert-True 'partial latest pointer records supabase MCP config summary' ($latest.supabaseMcpConfig.present -eq $true -and $latest.supabaseMcpConfig.readOnly -eq $true -and [string]$latest.supabaseMcpConfig.projectRefSource -eq 'SUPABASE_PROJECT_REF') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records no stored supabase token' ($latest.supabaseMcpConfig.tokenStored -eq $false) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase smoke MCP decision' ($latest.supabaseSmoke.mcpProbeSkipped -eq $true -and [string]$latest.supabaseSmoke.mcpDecision -eq 'mcp_endpoint_probe_skipped') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase apply MCP tools' (($latest.supabaseApply.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase required result names' (($latest.supabaseApply.requiredResultNames -join ',') -eq 'schemas_and_tables,rls_and_table_flags,policies') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase apply result paths' ([string]$latest.supabaseApply.resultPathRecommendation -eq 'data/db-gap-report/supabase-query-results.json' -and [string]$latest.supabaseApply.advisorResultPathRecommendation -eq 'data/db-gap-report/supabase-advisors.json') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records external apply roles' (($latest.externalApply.requiredRoles -join ',') -eq 'macmini,notebook') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records external apply sidecars' (($latest.externalApply.requiredPatchDropSidecars -join ',').Contains('.manifest.json') -and ($latest.externalApply.requiredPatchDropSidecars -join ',').Contains('.sha256.txt')) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records external source isolation guard' ($latest.externalApply.requiredSourceIsolation.guard -eq 'PASS') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records external copied evidence counts' ([int]$latest.externalApply.copiedEvidenceCount -eq 0 -and [int]$latest.externalApply.copiedHandoffCount -eq 0) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer carries full computer smoke summary' ($latest.computerUse.ok -eq $true -and $latest.computerUse.reachable -eq $true -and [int]$latest.computerUse.appCount -eq 3) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer carries full browser smoke summary' ($latest.browserUse.ok -eq $true -and $latest.browserUse.reachable -eq $true -and $latest.browserUse.localhost -eq $true) "latest=$($latest | ConvertTo-Json -Compress)"
        $digest = Get-Content -Raw -LiteralPath $latest.digestPath | ConvertFrom-Json
        Assert-True 'partial digest records freshness age' ([double]$digest.latestGeneratedAtAgeMinutes -ge 0 -and [double]$digest.latestGeneratedAtAgeMinutes -lt 5) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records not stale at write' ($digest.staleLatest -eq $false) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records first action' ([string]$digest.firstAction -eq 'set_SUPABASE_PROJECT_REF') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records first action source' ([string]$digest.firstActionSource -eq 'supabase_apply') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records computer readiness' ($digest.computerUseOk -eq $true -and $digest.computerUseReachable -eq $true -and [int]$digest.computerUseAppCount -eq 3) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest carries full computer smoke summary' ($digest.computerUse.ok -eq $true -and $digest.computerUse.reachable -eq $true -and [int]$digest.computerUse.appCount -eq 3) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records browser readiness' ($digest.browserUseOk -eq $true -and $digest.browserUseReachable -eq $true -and $digest.browserUseLocalhost -eq $true) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest carries full browser smoke summary' ($digest.browserUse.ok -eq $true -and $digest.browserUse.reachable -eq $true -and $digest.browserUse.localhost -eq $true) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase MCP config summary' ($digest.supabaseMcpConfig.present -eq $true -and $digest.supabaseMcpConfig.readOnly -eq $true -and [string]$digest.supabaseMcpConfig.serverHost -eq 'mcp.supabase.com') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase MCP token guard' ($digest.supabaseMcpConfig.tokenStored -eq $false) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase smoke MCP decision' ($digest.supabaseSmoke.mcpProbeSkipped -eq $true -and [string]$digest.supabaseSmoke.mcpDecision -eq 'mcp_endpoint_probe_skipped') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase apply MCP tools' (($digest.supabaseApply.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase apply result paths' ([string]$digest.supabaseApply.resultPathRecommendation -eq 'data/db-gap-report/supabase-query-results.json' -and [string]$digest.supabaseApply.advisorResultPathRecommendation -eq 'data/db-gap-report/supabase-advisors.json') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external apply roles' (($digest.externalApply.requiredRoles -join ',') -eq 'macmini,notebook') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external apply sidecars' (($digest.externalApply.requiredPatchDropSidecars -join ',').Contains('.manifest.json') -and ($digest.externalApply.requiredPatchDropSidecars -join ',').Contains('.sha256.txt')) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external source isolation guard' ($digest.externalApply.requiredSourceIsolation.guard -eq 'PASS') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external copied evidence counts' ([int]$digest.externalApply.copiedEvidenceCount -eq 0 -and [int]$digest.externalApply.copiedHandoffCount -eq 0) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase action count' ([int]$digest.sourceActionCounts.supabase_apply -ge 2) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external action count' ([int]$digest.sourceActionCounts.external_apply -ge 2) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records desktop action count' ([int]$digest.sourceActionCounts.desktop_control_loop -ge 1) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records source-health detail action count' ([int]$digest.sourceActionCounts.source_health_scorecard -eq 3) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records safe top actions' (($digest.topActions | Select-Object -First 1).action -eq 'set_SUPABASE_PROJECT_REF') "digest=$($digest | ConvertTo-Json -Compress)"
        $digestMarkdown = Get-Content -Raw -LiteralPath $latest.digestMarkdownPath
        Assert-Contains 'partial digest markdown records decision' $digestMarkdown 'decision=evidence_needed'
        Assert-Contains 'partial digest markdown records freshness age' $digestMarkdown 'latestGeneratedAtAgeMinutes='
        Assert-Contains 'partial digest markdown records stale state' $digestMarkdown 'staleLatest=false'
        Assert-Contains 'partial digest markdown records first action' $digestMarkdown 'firstAction=set_SUPABASE_PROJECT_REF'
        Assert-Contains 'partial digest markdown records supabase source count' $digestMarkdown 'supabase_apply='
        Assert-Contains 'partial digest markdown records external source count' $digestMarkdown 'external_apply='
        Assert-Contains 'partial digest markdown records desktop source count' $digestMarkdown 'desktop_control_loop='
        Assert-Contains 'partial digest markdown records source-health source count' $digestMarkdown 'source_health_scorecard='
        Assert-Contains 'partial digest markdown records computer readiness' $digestMarkdown 'computerUse=ok'
        Assert-Contains 'partial digest markdown records computer count-only summary' $digestMarkdown 'computerUse=ok reachable=True stale=False appCount=3 runningCount=2 windowCount=4 secretHits=0'
        Assert-Contains 'partial digest markdown records browser readiness' $digestMarkdown 'browserUse=ok'
        Assert-Contains 'partial digest markdown records browser count-only summary' $digestMarkdown 'browserUse=ok reachable=True localhost=True stale=False screenshotCaptured=True secretHits=0'
        Assert-Contains 'partial digest markdown records external required roles' $digestMarkdown 'external.requiredRoles=macmini,notebook'
        Assert-Contains 'partial digest markdown records external sidecars' $digestMarkdown 'external.requiredSidecars=pendingNotice,.manifest.json,.patch,.report.md,.verify.log,.sha256.txt'
        Assert-Contains 'partial digest markdown records external source isolation guard' $digestMarkdown 'external.sourceIsolation.guard=PASS'
        Assert-Contains 'partial digest markdown records supabase required env names' $digestMarkdown 'supabase.requiredEnvNames=SUPABASE_PROJECT_REF,SUPABASE_ACCESS_TOKEN'
        Assert-Contains 'partial digest markdown records supabase required MCP tools' $digestMarkdown 'supabase.requiredMcpTools=execute_sql,get_advisors'
        Assert-Contains 'partial digest markdown records supabase result path' $digestMarkdown 'supabase.resultPathRecommendation=data/db-gap-report/supabase-query-results.json'
        Assert-Contains 'partial digest markdown records supabase advisor path' $digestMarkdown 'supabase.advisorResultPathRecommendation=data/db-gap-report/supabase-advisors.json'
        Assert-Contains 'partial digest markdown records supabase smoke MCP decision' $digestMarkdown 'supabaseSmoke.mcpDecision=mcp_endpoint_probe_skipped'
        Assert-Contains 'partial digest markdown records supabase MCP read-only config' $digestMarkdown 'supabaseMcp.readOnly=True'
        Assert-Contains 'partial digest markdown records supabase MCP token guard' $digestMarkdown 'supabaseMcp.tokenStored=False'
        Assert-True 'partial digest markdown is secret safe' (-not ($digestMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "digestMarkdown=$digestMarkdown"
        Assert-True 'partial summary has no secret hits' ([int]$summary.secretHits -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"

        $partialStatus = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-Status')
        Assert-True 'partial status exits evidence_needed' ($partialStatus.ExitCode -eq 2) "expected exit 2; output=$($partialStatus.Output)"
        Assert-Contains 'partial status reports evidence needed' $partialStatus.Output 'statusDecision=evidence_needed'
        Assert-Contains 'partial status reports fresh latest' $partialStatus.Output 'staleLatest=false'
        Assert-Contains 'partial status reports first action' $partialStatus.Output 'firstAction=set_SUPABASE_PROJECT_REF'
        $statusPath = Join-Path $partialRoot 'var\codex-smoke\goal-next-auto.status.json'
        Assert-True 'partial status writes status artifact' (Test-Path $statusPath) "missing status at $statusPath"
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'partial status records latest decision' ([string]$status.latestDecision -eq 'evidence_needed') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records status decision' ([string]$status.statusDecision -eq 'evidence_needed') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records not stale' ($status.staleLatest -eq $false) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records source-health source' ([string]$status.firstActionSource -eq 'supabase_apply') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records next action count' ([int]$status.nextActionCount -ge 1) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records next action sources' (($status.nextActionSources -join ',').Contains('supabase_apply')) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records safe top actions' (@($status.topActions).Count -gt 0 -and [string]$status.topActions[0].action -eq 'set_SUPABASE_PROJECT_REF') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records computer readiness' ($status.computerUseOk -eq $true -and $status.computerUseReachable -eq $true -and [int]$status.computerUseAppCount -eq 3) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status carries full computer smoke summary' ($status.computerUse.ok -eq $true -and $status.computerUse.reachable -eq $true -and [int]$status.computerUse.appCount -eq 3) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records browser readiness' ($status.browserUseOk -eq $true -and $status.browserUseReachable -eq $true -and $status.browserUseLocalhost -eq $true) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status carries full browser smoke summary' ($status.browserUse.ok -eq $true -and $status.browserUse.reachable -eq $true -and $status.browserUse.localhost -eq $true) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase MCP config summary' ($status.supabaseMcpConfig.present -eq $true -and $status.supabaseMcpConfig.readOnly -eq $true -and [string]$status.supabaseMcpConfig.projectRefSource -eq 'SUPABASE_PROJECT_REF') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase MCP token guard' ($status.supabaseMcpConfig.tokenStored -eq $false) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase smoke MCP decision' ($status.supabaseSmoke.mcpProbeSkipped -eq $true -and [string]$status.supabaseSmoke.mcpDecision -eq 'mcp_endpoint_probe_skipped') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase apply MCP tools' (($status.supabaseApply.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase required result names' (($status.supabaseApply.requiredResultNames -join ',') -eq 'schemas_and_tables,rls_and_table_flags,policies') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase apply result paths' ([string]$status.supabaseApply.resultPathRecommendation -eq 'data/db-gap-report/supabase-query-results.json' -and [string]$status.supabaseApply.advisorResultPathRecommendation -eq 'data/db-gap-report/supabase-advisors.json') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records external apply roles' (($status.externalApply.requiredRoles -join ',') -eq 'macmini,notebook') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records external apply sidecars' (($status.externalApply.requiredPatchDropSidecars -join ',').Contains('.manifest.json') -and ($status.externalApply.requiredPatchDropSidecars -join ',').Contains('.sha256.txt')) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records external source isolation guard' ($status.externalApply.requiredSourceIsolation.guard -eq 'PASS') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status has no secret hits' ([int]$status.secretHits -eq 0) "status=$($status | ConvertTo-Json -Compress)"

        $latest.generatedAt = (Get-Item -LiteralPath $script).LastWriteTimeUtc.AddSeconds(-1).ToString('o')
        $latest.latestStaleAfterMinutes = 999999
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8
        $scriptNewerStatus = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-Status')
        Assert-True 'script-newer status exits evidence_needed' ($scriptNewerStatus.ExitCode -eq 2) "expected exit 2; output=$($scriptNewerStatus.Output)"
        Assert-Contains 'script-newer status reports stale latest' $scriptNewerStatus.Output 'staleLatest=true'
        Assert-Contains 'script-newer status reports classifier' $scriptNewerStatus.Output 'failureClassification=script-newer-than-latest'
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'script-newer status records stale latest' ($status.staleLatest -eq $true) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'script-newer status records classifier' ([string]$status.failureClassification -eq 'script-newer-than-latest') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'script-newer status records dependency name only' (
            -not [string]::IsNullOrWhiteSpace([string]$status.latestDependencyName) -and
            -not ([string]$status.latestDependencyName -match '[\\/:]') -and
            ([string]$status.latestDependencyName -match '\.(ps1|py)$')
        ) "status=$($status | ConvertTo-Json -Compress)"

        $latest.generatedAt = (Get-Date).ToUniversalTime().AddMinutes(-90).ToString('o')
        $latest.latestStaleAfterMinutes = 60
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8
        $staleStatus = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-Status')
        Assert-True 'stale status exits evidence_needed' ($staleStatus.ExitCode -eq 2) "expected exit 2; output=$($staleStatus.Output)"
        Assert-Contains 'stale status reports stale latest' $staleStatus.Output 'staleLatest=true'
        Assert-Contains 'stale status reports stale classifier' $staleStatus.Output 'failureClassification=stale-latest'
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'stale status records stale latest' ($status.staleLatest -eq $true) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'stale status records stale classifier' ([string]$status.failureClassification -eq 'stale-latest') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'stale status records latest age' ([double]$status.latestGeneratedAtAgeMinutes -ge 60) "status=$($status | ConvertTo-Json -Compress)"

        $wrapperOutput = Join-Path $partialRoot 'wrapper-out'
        $wrapper = Invoke-Captured -Arguments @('-File', $nextScript, '-Root', $partialRoot, '-OutputDir', $wrapperOutput, '-Topic', 'mcp-control-loop')
        Assert-True 'goal next wrapper refreshes stale latest and exits evidence_needed' ($wrapper.ExitCode -eq 2) "expected exit 2; output=$($wrapper.Output)"
        Assert-Contains 'goal next wrapper reports refresh action' $wrapper.Output 'action=refresh'
        Assert-Contains 'goal next wrapper reports stale reason' $wrapper.Output 'reason=stale-latest'
        $wrapperSummaryPath = Join-Path $wrapperOutput 'goal-next-auto.summary.json'
        Assert-True 'goal next wrapper writes refreshed summary' (Test-Path $wrapperSummaryPath) "missing wrapper summary at $wrapperSummaryPath"
        $wrapperSummary = Get-Content -Raw -LiteralPath $wrapperSummaryPath | ConvertFrom-Json
        Assert-True 'goal next wrapper reuses previous breadcrumb' ($wrapperSummary.previousBreadcrumb.present -eq $true -and [string]$wrapperSummary.previousBreadcrumb.firstAction -eq 'set_SUPABASE_PROJECT_REF') "wrapperSummary=$($wrapperSummary | ConvertTo-Json -Compress)"
        Assert-True 'goal next wrapper records breadcrumb fusion artifact' (-not [string]::IsNullOrWhiteSpace([string]$wrapperSummary.artifacts.breadcrumbFusion) -and (Test-Path $wrapperSummary.artifacts.breadcrumbFusion)) "wrapperSummary=$($wrapperSummary | ConvertTo-Json -Compress)"
        $wrapperFusion = Get-Content -Raw -LiteralPath $wrapperSummary.artifacts.breadcrumbFusion | ConvertFrom-Json
        Assert-True 'goal next wrapper fusion merges repeated blocker' ($wrapperFusion.timelineRowsRead -ge 2 -and [string]$wrapperFusion.repeatedFirstActions[0].action -eq 'set_SUPABASE_PROJECT_REF' -and [int]$wrapperFusion.repeatedFirstActions[0].count -ge 2) "wrapperFusion=$($wrapperFusion | ConvertTo-Json -Compress)"
        Assert-True 'goal next wrapper fusion marks repeated external input gate' ([string]$wrapperFusion.externalInputGate.status -eq 'external_input_needed' -and $wrapperFusion.externalInputGate.repeated -eq $true -and [int]$wrapperFusion.externalInputGate.repeatCount -ge 2 -and [string]$wrapperFusion.externalInputGate.source -eq 'supabase_apply') "wrapperFusion=$($wrapperFusion | ConvertTo-Json -Compress)"
        Assert-True 'goal next wrapper summary surfaces repeated external input gate' ([string]$wrapperSummary.externalInputGate.status -eq 'external_input_needed' -and $wrapperSummary.externalInputGate.repeated -eq $true -and [int]$wrapperSummary.externalInputGate.repeatCount -ge 2) "wrapperSummary=$($wrapperSummary | ConvertTo-Json -Compress)"
        $breadcrumbLines = @(Get-Content -LiteralPath $latest.breadcrumbTimelinePath)
        Assert-True 'goal next wrapper appends breadcrumb row' ($breadcrumbLines.Count -ge 2) "breadcrumbLines=$($breadcrumbLines -join "`n")"
        $wrapperBreadcrumb = $breadcrumbLines[-1] | ConvertFrom-Json
        Assert-True 'goal next wrapper raw tile records reuse source' ([string]$wrapperBreadcrumb.rawTile.reuseSource -eq 'previous_breadcrumb' -and [string]$wrapperBreadcrumb.rawTile.previousFirstAction -eq 'set_SUPABASE_PROJECT_REF') "wrapperBreadcrumb=$($wrapperBreadcrumb | ConvertTo-Json -Compress)"

        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        $latest.generatedAt = (Get-Date).ToUniversalTime().AddMinutes(-90).ToString('o')
        $latest.latestStaleAfterMinutes = 60
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8

        $ensureOutput = Join-Path $partialRoot 'ensure-out'
        $ensure = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $ensureOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh')
        Assert-True 'ensure-fresh refreshes stale latest and exits evidence_needed' ($ensure.ExitCode -eq 2) "expected exit 2; output=$($ensure.Output)"
        Assert-Contains 'ensure-fresh reports refresh action' $ensure.Output 'action=refresh'
        Assert-Contains 'ensure-fresh reports stale reason' $ensure.Output 'reason=stale-latest'
        $ensureSummaryPath = Join-Path $ensureOutput 'goal-next-auto.summary.json'
        Assert-True 'ensure-fresh writes refreshed summary' (Test-Path $ensureSummaryPath) "missing ensure summary at $ensureSummaryPath"
        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        Assert-True 'ensure-fresh refreshes latest pointer' ([string]$latest.summaryPath -eq $ensureSummaryPath) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'ensure-fresh writes non-stale latest pointer' ($latest.staleLatest -eq $false) "latest=$($latest | ConvertTo-Json -Compress)"
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'ensure-fresh refreshes status artifact after refresh' ([string]$status.summaryPath -eq $ensureSummaryPath) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'ensure-fresh status artifact is non-stale after refresh' ($status.staleLatest -eq $false) "status=$($status | ConvertTo-Json -Compress)"

        $freshOutput = Join-Path $partialRoot 'ensure-out-fresh'
        $freshEnsure = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $freshOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh')
        Assert-True 'ensure-fresh keeps fresh evidence_needed status' ($freshEnsure.ExitCode -eq 2) "expected exit 2; output=$($freshEnsure.Output)"
        Assert-Contains 'ensure-fresh reports status action for fresh latest' $freshEnsure.Output 'action=status'
        Assert-Contains 'ensure-fresh reports fresh reason' $freshEnsure.Output 'reason=fresh-latest'
        Assert-True 'ensure-fresh does not rerun child gates when latest is fresh' (-not (Test-Path (Join-Path $freshOutput 'goal-next-auto.summary.json'))) "fresh ensure unexpectedly wrote summary under $freshOutput"

        $computerSmokePath = Join-Path $partialRoot 'var\codex-smoke\computer-use-smoke.json'
        $computerGeneratedAt = [DateTime]::UtcNow.ToString('o')
        Set-TestFile $computerSmokePath @"
{
  "schemaVersion": "awx.computer_use_smoke.v1",
  "generatedAt": "$computerGeneratedAt",
  "ok": true,
  "decision": "ok",
  "appCount": 5,
  "runningCount": 2,
  "windowCount": 4,
  "storesRawAppNames": false,
  "storesWindowTitles": false,
  "rawSecretPatternHits": 0
}
"@
        (Get-Item -LiteralPath $computerSmokePath).LastWriteTimeUtc = [DateTime]::UtcNow.AddSeconds(5)
        $computerRefreshOutput = Join-Path $partialRoot 'ensure-out-computer-refresh'
        $computerRefresh = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $computerRefreshOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh')
        Assert-True 'ensure-fresh refreshes when computer smoke is newer than latest' ($computerRefresh.ExitCode -eq 2) "expected exit 2; output=$($computerRefresh.Output)"
        Assert-Contains 'ensure-fresh reports refresh action for newer computer smoke' $computerRefresh.Output 'action=refresh'
        Assert-Contains 'ensure-fresh reports computer smoke newer reason' $computerRefresh.Output 'reason=computer-use-smoke-newer-than-latest'
        $computerRefreshSummaryPath = Join-Path $computerRefreshOutput 'goal-next-auto.summary.json'
        Assert-True 'ensure-fresh writes refreshed summary after newer computer smoke' (Test-Path $computerRefreshSummaryPath) "missing computer refresh summary at $computerRefreshSummaryPath"
        if (Test-Path $computerRefreshSummaryPath) {
            $computerSummary = Get-Content -Raw -LiteralPath $computerRefreshSummaryPath | ConvertFrom-Json
            Assert-True 'newer computer smoke is reflected in refreshed summary' ([int]$computerSummary.computerUse.appCount -eq 5) "summary=$($computerSummary | ConvertTo-Json -Compress)"
            Assert-True 'newer helper-style computer smoke infers readiness without reachable field' ($computerSummary.computerUse.ok -eq $true -and $computerSummary.computerUse.reachable -eq $true -and [string]$computerSummary.computerUse.nextAction -eq '') "summary=$($computerSummary | ConvertTo-Json -Compress)"
        }

        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        $latest.generatedAt = [DateTime]::UtcNow.ToString('o')
        $latest.latestStaleAfterMinutes = 60
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8
        $staleComputerGeneratedAt = [DateTime]::UtcNow.AddMinutes(-90).ToString('o')
        Set-TestFile $computerSmokePath @"
{
  "schemaVersion": "awx.computer_use.smoke.v1",
  "generatedAt": "$staleComputerGeneratedAt",
  "ok": true,
  "decision": "ok",
  "reachable": true,
  "appCount": 5,
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
        (Get-Item -LiteralPath $computerSmokePath).LastWriteTimeUtc = [DateTime]::UtcNow.AddMinutes(-5)
        $staleComputerRefreshOutput = Join-Path $partialRoot 'ensure-out-computer-stale-refresh'
        $staleComputerRefresh = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $staleComputerRefreshOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh')
        Assert-True 'ensure-fresh refreshes when computer smoke content is stale under fresh latest' ($staleComputerRefresh.ExitCode -eq 2) "expected exit 2; output=$($staleComputerRefresh.Output)"
        Assert-Contains 'ensure-fresh reports refresh action for stale computer smoke content' $staleComputerRefresh.Output 'action=refresh'
        Assert-Contains 'ensure-fresh reports stale computer smoke reason' $staleComputerRefresh.Output 'reason=computer-use-smoke-stale'
        $staleComputerRefreshSummaryPath = Join-Path $staleComputerRefreshOutput 'goal-next-auto.summary.json'
        Assert-True 'ensure-fresh writes refreshed summary after stale computer smoke content' (Test-Path $staleComputerRefreshSummaryPath) "missing stale computer refresh summary at $staleComputerRefreshSummaryPath"
        if (Test-Path $staleComputerRefreshSummaryPath) {
            $computerSummary = Get-Content -Raw -LiteralPath $staleComputerRefreshSummaryPath | ConvertFrom-Json
            Assert-True 'stale computer smoke is reflected in refreshed summary' ($computerSummary.computerUse.stale -eq $true -and [string]$computerSummary.computerUse.nextAction -eq 'rerun_computer_use_lightweight_smoke') "summary=$($computerSummary | ConvertTo-Json -Compress)"
        }
    }

    $computerStaleRoot = New-FakeGoalRoot -Mode 'computerstale'
    $computerStaleOutput = Join-Path $computerStaleRoot 'out'
    $computerStale = Invoke-Captured -Arguments @('-File', $script, '-Root', $computerStaleRoot, '-OutputDir', $computerStaleOutput)
    Assert-True 'stale computer smoke goal next exits evidence_needed' ($computerStale.ExitCode -eq 2) "expected exit 2; output=$($computerStale.Output)"
    $computerStaleSummaryPath = Join-Path $computerStaleOutput 'goal-next-auto.summary.json'
    Assert-True 'stale computer smoke writes summary' (Test-Path $computerStaleSummaryPath) "missing stale computer summary at $computerStaleSummaryPath"
    if (Test-Path $computerStaleSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $computerStaleSummaryPath | ConvertFrom-Json
        Assert-True 'stale computer smoke is not accepted as ready' ($summary.computerUse.ok -eq $false -and $summary.computerUse.reachable -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke records stale age' ($summary.computerUse.stale -eq $true -and [double]$summary.computerUse.ageMinutes -ge 60) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke requests rerun' ([string]$summary.computerUse.nextAction -eq 'rerun_computer_use_lightweight_smoke') "summary=$($summary | ConvertTo-Json -Compress)"
        $nextActions = Get-Content -Raw -LiteralPath $summary.artifacts.nextActions | ConvertFrom-Json
        $computerAction = $nextActions.actions | Where-Object { $_.source -eq 'computer_use' } | Select-Object -First 1
        Assert-True 'stale computer smoke adds computer next action' ($null -ne $computerAction -and [string]$computerAction.action -eq 'rerun_computer_use_lightweight_smoke') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $commandPacket = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacket | ConvertFrom-Json
        $computerCommand = $commandPacket.commands | Where-Object { $_.lane -eq 'computer_use' } | Select-Object -First 1
        Assert-True 'stale computer smoke command packet includes computer use lane' ($null -ne $computerCommand) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke command packet names node repl tool' ([string]$computerCommand.tool -eq 'mcp__node_repl.js') "command=$($computerCommand | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke command packet keeps output path relative' ([string]$computerCommand.outputPath -eq 'var/codex-smoke/computer-use-smoke.json') "command=$($computerCommand | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke command packet is secret safe' (-not (($computerCommand | ConvertTo-Json -Depth 20) -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "command=$($computerCommand | ConvertTo-Json -Compress)"
        $commandPacketMarkdown = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacketMarkdown
        Assert-Contains 'stale computer smoke command packet markdown records computer use lane' $commandPacketMarkdown 'lane=computer_use'
        Assert-Contains 'stale computer smoke command packet markdown records computer use tool' $commandPacketMarkdown 'tool=mcp__node_repl.js'
        Assert-Contains 'stale computer smoke command packet markdown records computer use output path' $commandPacketMarkdown 'outputPath=var/codex-smoke/computer-use-smoke.json'
        Assert-Contains 'stale computer smoke command packet markdown records raw app names guard' $commandPacketMarkdown 'storesRawAppNames=False'
        Assert-Contains 'stale computer smoke command packet markdown records computer summary' $commandPacketMarkdown 'computerUse.decision=evidence_needed reachable=True stale=True appCount=3 runningCount=2 windowCount=4 storesRawAppNames=False storesWindowTitles=False secretHits=0 outputPath=var/codex-smoke/computer-use-smoke.json'
        Assert-True 'stale computer smoke command packet markdown is secret safe' (-not ($commandPacketMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "commandPacketMarkdown=$commandPacketMarkdown"
        $collectionPacket = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacket | ConvertFrom-Json
        Assert-True 'stale computer smoke collection packet includes computer use lane' ($null -ne $collectionPacket.computerUse) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet records evidence needed' ([string]$collectionPacket.computerUse.decision -eq 'evidence_needed') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet names node repl tool' ([string]$collectionPacket.computerUse.tool -eq 'mcp__node_repl.js') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet keeps output path relative' ([string]$collectionPacket.computerUse.outputPath -eq 'var/codex-smoke/computer-use-smoke.json') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet keeps raw app names disabled' ($collectionPacket.computerUse.storesRawAppNames -eq $false) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet records rerun action' ($collectionPacket.computerUse.nextActions -contains 'rerun_computer_use_lightweight_smoke') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet is secret safe' (-not (($collectionPacket | ConvertTo-Json -Depth 50) -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        $collectionPacketMarkdown = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacketMarkdown
        Assert-Contains 'stale computer smoke collection packet markdown records computer use tool' $collectionPacketMarkdown 'computerUse.tool=mcp__node_repl.js'
        Assert-Contains 'stale computer smoke collection packet markdown records computer use output path' $collectionPacketMarkdown 'computerUse.outputPath=var/codex-smoke/computer-use-smoke.json'
        Assert-Contains 'stale computer smoke collection packet markdown records raw app names guard' $collectionPacketMarkdown 'computerUse.storesRawAppNames=False'
        Assert-Contains 'stale computer smoke collection packet markdown records window-title guard' $collectionPacketMarkdown 'computerUse.storesWindowTitles=False'
        Assert-Contains 'stale computer smoke collection packet markdown records app count' $collectionPacketMarkdown 'computerUse.appCount=3'
        Assert-Contains 'stale computer smoke collection packet markdown records running count' $collectionPacketMarkdown 'computerUse.runningCount=2'
        Assert-Contains 'stale computer smoke collection packet markdown records window count' $collectionPacketMarkdown 'computerUse.windowCount=4'
        Assert-Contains 'stale computer smoke collection packet markdown records secret count' $collectionPacketMarkdown 'computerUse.secretHits=0'
        Assert-True 'stale computer smoke collection packet markdown is secret safe' (-not ($collectionPacketMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "collectionPacketMarkdown=$collectionPacketMarkdown"
        $latestPath = Join-Path $computerStaleRoot 'var\codex-smoke\goal-next-auto.latest.json'
        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        Assert-True 'stale latest pointer carries full stale computer smoke summary' ($latest.computerUse.stale -eq $true -and [string]$latest.computerUse.nextAction -eq 'rerun_computer_use_lightweight_smoke') "latest=$($latest | ConvertTo-Json -Compress)"
    }

    $browserStaleRoot = New-FakeGoalRoot -Mode 'browserstale'
    $browserStaleOutput = Join-Path $browserStaleRoot 'out'
    $browserStale = Invoke-Captured -Arguments @('-File', $script, '-Root', $browserStaleRoot, '-OutputDir', $browserStaleOutput)
    Assert-True 'stale browser smoke goal next exits evidence_needed' ($browserStale.ExitCode -eq 2) "expected exit 2; output=$($browserStale.Output)"
    $browserStaleSummaryPath = Join-Path $browserStaleOutput 'goal-next-auto.summary.json'
    Assert-True 'stale browser smoke writes summary' (Test-Path $browserStaleSummaryPath) "missing stale browser summary at $browserStaleSummaryPath"
    if (Test-Path $browserStaleSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $browserStaleSummaryPath | ConvertFrom-Json
        Assert-True 'stale browser smoke is not accepted as ready' ($summary.browserUse.ok -eq $false -and $summary.browserUse.reachable -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke records stale age' ($summary.browserUse.stale -eq $true -and [double]$summary.browserUse.ageMinutes -ge 60) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke requests rerun' ([string]$summary.browserUse.nextAction -eq 'rerun_browser_local_ui_smoke') "summary=$($summary | ConvertTo-Json -Compress)"
        $nextActions = Get-Content -Raw -LiteralPath $summary.artifacts.nextActions | ConvertFrom-Json
        $browserAction = $nextActions.actions | Where-Object { $_.source -eq 'browser_use' } | Select-Object -First 1
        Assert-True 'stale browser smoke adds browser next action' ($null -ne $browserAction -and [string]$browserAction.action -eq 'rerun_browser_local_ui_smoke') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $commandPacket = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacket | ConvertFrom-Json
        $browserCommand = $commandPacket.commands | Where-Object { $_.lane -eq 'browser_use' } | Select-Object -First 1
        Assert-True 'stale browser smoke command packet includes browser use lane' ($null -ne $browserCommand) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke command packet names browser tool' ([string]$browserCommand.tool -eq 'browser.control-in-app-browser') "command=$($browserCommand | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke command packet keeps output path relative' ([string]$browserCommand.outputPath -eq 'var/codex-smoke/browser-ui-smoke.json') "command=$($browserCommand | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke command packet keeps raw fields disabled' ($browserCommand.storesRawUrl -eq $false -and $browserCommand.storesScreenshotPath -eq $false) "command=$($browserCommand | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke command packet is secret safe' (-not (($browserCommand | ConvertTo-Json -Depth 20) -match 'http://|https://|Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "command=$($browserCommand | ConvertTo-Json -Compress)"
        $collectionPacket = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacket | ConvertFrom-Json
        Assert-True 'stale browser smoke collection packet includes browser use lane' ($null -ne $collectionPacket.browserUse) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet records evidence needed' ([string]$collectionPacket.browserUse.decision -eq 'evidence_needed') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet names browser tool' ([string]$collectionPacket.browserUse.tool -eq 'browser.control-in-app-browser') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet keeps output path relative' ([string]$collectionPacket.browserUse.outputPath -eq 'var/codex-smoke/browser-ui-smoke.json') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet keeps raw fields disabled' ($collectionPacket.browserUse.storesRawUrl -eq $false -and $collectionPacket.browserUse.storesScreenshotPath -eq $false) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet records rerun action' ($collectionPacket.browserUse.nextActions -contains 'rerun_browser_local_ui_smoke') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet is secret safe' (-not (($collectionPacket.browserUse | ConvertTo-Json -Depth 20) -match 'http://|https://|Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        $latestPath = Join-Path $browserStaleRoot 'var\codex-smoke\goal-next-auto.latest.json'
        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        Assert-True 'stale latest pointer carries full stale browser smoke summary' ($latest.browserUse.stale -eq $true -and [string]$latest.browserUse.nextAction -eq 'rerun_browser_local_ui_smoke') "latest=$($latest | ConvertTo-Json -Compress)"
    }

    $proofStripRoot = New-FakeGoalRoot -Mode 'partial'
    $proofGeneratedAt = [DateTime]::UtcNow.ToString('o')
    Set-TestFile (Join-Path $proofStripRoot 'var\codex-smoke\computer-use-smoke.json') @"
{
  "ok": true,
  "decision": "ok",
  "checkedAt": "$proofGeneratedAt",
  "appCount": 42,
  "runningCount": 22,
  "windowCount": 147,
  "rawSecretPatternHits": []
}
"@
    Set-TestFile (Join-Path $proofStripRoot 'var\codex-smoke\browser-ui-smoke.json') @"
{
  "checkedAt": "$proofGeneratedAt",
  "proofRootPresent": true,
  "proofCellCount": 6,
  "proofNames": "local,browser,computer,supabase,producer,action",
  "flowStepCount": 6,
  "missionAxisCount": 5,
  "cockpitCellCount": 5,
  "matrixCellCount": 4,
  "heartbeatFieldCount": 29,
  "allDetailCellCount": 51,
  "detailLeakHits": [],
  "rawSecretPatternHits": []
}
"@
    $proofStripOutput = Join-Path $proofStripRoot 'out'
    $proofStrip = Invoke-Captured -Arguments @('-File', $script, '-Root', $proofStripRoot, '-OutputDir', $proofStripOutput)
    Assert-True 'proof-strip smoke goal next exits evidence_needed without parser error' ($proofStrip.ExitCode -eq 2) "expected exit 2; output=$($proofStrip.Output)"
    $proofStripSummaryPath = Join-Path $proofStripOutput 'goal-next-auto.summary.json'
    Assert-True 'proof-strip smoke writes summary' (Test-Path $proofStripSummaryPath) "missing proof-strip summary at $proofStripSummaryPath"
    if (Test-Path $proofStripSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $proofStripSummaryPath | ConvertFrom-Json
        Assert-True 'proof-strip computer smoke accepts checkedAt timestamp' ($summary.computerUse.ok -eq $true -and $summary.computerUse.reachable -eq $true -and $summary.computerUse.stale -eq $false -and [int]$summary.computerUse.appCount -eq 42 -and [int]$summary.computerUse.rawSecretPatternHits -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'proof-strip browser smoke infers visible localhost proof' ($summary.browserUse.ok -eq $true -and $summary.browserUse.reachable -eq $true -and $summary.browserUse.localhost -eq $true -and $summary.browserUse.screenshotCaptured -eq $true -and $summary.browserUse.targetContentVisible -eq $true -and $summary.browserUse.stale -eq $false -and [string]$summary.browserUse.statusClass -eq 'proof_strip_visible' -and [int]$summary.browserUse.rawSecretPatternHits -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $completeRoot = New-FakeGoalRoot -Mode 'complete'
    $completeOutput = Join-Path $completeRoot 'out'
    $complete = Invoke-Captured -Arguments @('-File', $script, '-Root', $completeRoot, '-OutputDir', $completeOutput)
    Assert-True 'complete goal next exits zero' ($complete.ExitCode -eq 0) "expected exit 0; output=$($complete.Output)"
    Assert-Contains 'complete goal next reports ok' $complete.Output 'decision=ok'
    Assert-Contains 'complete goal next ran desktop control loop' $complete.Output 'desktopControlLoopExit=0'
    $completeSummaryPath = Join-Path $completeOutput 'goal-next-auto.summary.json'
    Assert-True 'complete goal next writes summary' (Test-Path $completeSummaryPath) 'missing complete summary'
    if (Test-Path $completeSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $completeSummaryPath | ConvertFrom-Json
        Assert-True 'complete summary reports ok' ($summary.ok -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary records git root as string' ($summary.preflight.gitRoot -is [string]) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary records desktop control loop exit' ([int]$summary.desktopControlLoopExit -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary records desktop local readiness' ($summary.desktopControlLoop.localReady -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary records desktop completion readiness' ($summary.desktopControlLoop.completionReady -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary parses supabase apply summary' ($summary.supabaseApply.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary carries supabase apply ok decision' ($summary.supabaseApply.decision -eq 'ok') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary parses external apply summary' ($summary.externalApply.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary carries external apply ok decision' ($summary.externalApply.decision -eq 'ok') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary has no secret hits' ([int]$summary.secretHits -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $patchdropRoot = New-FakeGoalRoot -Mode 'patchdrop'
    $patchdropOutput = Join-Path $patchdropRoot 'out'
    $patchdrop = Invoke-Captured -Arguments @('-File', $script, '-Root', $patchdropRoot, '-OutputDir', $patchdropOutput)
    Assert-True 'patchdrop-pending goal next exits evidence_needed' ($patchdrop.ExitCode -eq 2) "expected exit 2; output=$($patchdrop.Output)"
    Assert-Contains 'patchdrop-pending goal next names classifier' $patchdrop.Output 'decision=evidence_needed'
    $patchdropSummaryPath = Join-Path $patchdropOutput 'goal-next-auto.summary.json'
    Assert-True 'patchdrop-pending goal next writes summary' (Test-Path $patchdropSummaryPath) 'missing patchdrop summary'
    if (Test-Path $patchdropSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $patchdropSummaryPath | ConvertFrom-Json
        Assert-True 'patchdrop summary reports evidence_needed' ($summary.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'patchdrop summary reports not ok' ($summary.ok -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'patchdrop summary records pending patch count' ([int]$summary.preflight.patchDropPendingPatchCount -eq 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'patchdrop summary records failure classifier' ($summary.preflight.failureClassification -eq 'patch-drop-pending') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'patchdrop summary records safe pending patch name only' (($summary.preflight.patchDropPendingPatchNames -join ',') -eq 'pending-safe.patch') "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $leaseHeldRoot = New-FakeGoalRoot -Mode 'leaseheld'
    $leaseHeldOutput = Join-Path $leaseHeldRoot 'out'
    $leaseHeld = Invoke-Captured -Arguments @('-File', $script, '-Root', $leaseHeldRoot, '-OutputDir', $leaseHeldOutput)
    Assert-True 'source lease held goal next exits evidence_needed' ($leaseHeld.ExitCode -eq 2) "expected exit 2; output=$($leaseHeld.Output)"
    Assert-Contains 'source lease held goal next reports evidence_needed' $leaseHeld.Output 'decision=evidence_needed'
    $leaseHeldSummaryPath = Join-Path $leaseHeldOutput 'goal-next-auto.summary.json'
    Assert-True 'source lease held goal next writes summary' (Test-Path $leaseHeldSummaryPath) 'missing source lease held summary'
    if (Test-Path $leaseHeldSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $leaseHeldSummaryPath | ConvertFrom-Json
        Assert-True 'source lease held summary records active count' ([int]$summary.preflight.sourceLeaseActiveCount -eq 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease held summary records corrupt count zero' ([int]$summary.preflight.sourceLeaseCorruptCount -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease held summary records failure classifier' ($summary.preflight.failureClassification -eq 'source-edit-lease-held') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease held summary records topic only' (($summary.preflight.sourceLeaseActiveTopics -join ',') -eq 'active-topic') "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $leaseCorruptRoot = New-FakeGoalRoot -Mode 'leasecorrupt'
    $leaseCorruptOutput = Join-Path $leaseCorruptRoot 'out'
    $leaseCorrupt = Invoke-Captured -Arguments @('-File', $script, '-Root', $leaseCorruptRoot, '-OutputDir', $leaseCorruptOutput)
    Assert-True 'source lease corrupt goal next exits evidence_needed' ($leaseCorrupt.ExitCode -eq 2) "expected exit 2; output=$($leaseCorrupt.Output)"
    Assert-Contains 'source lease corrupt goal next reports evidence_needed' $leaseCorrupt.Output 'decision=evidence_needed'
    $leaseCorruptSummaryPath = Join-Path $leaseCorruptOutput 'goal-next-auto.summary.json'
    Assert-True 'source lease corrupt goal next writes summary' (Test-Path $leaseCorruptSummaryPath) 'missing source lease corrupt summary'
    if (Test-Path $leaseCorruptSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $leaseCorruptSummaryPath | ConvertFrom-Json
        Assert-True 'source lease corrupt summary records active count zero' ([int]$summary.preflight.sourceLeaseActiveCount -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease corrupt summary records corrupt count' ([int]$summary.preflight.sourceLeaseCorruptCount -eq 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease corrupt summary records failure classifier' ($summary.preflight.failureClassification -eq 'source-edit-lease-corrupt') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease corrupt summary records topic only' (($summary.preflight.sourceLeaseCorruptTopics -join ',') -eq 'corrupt-topic') "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $secretRoot = New-FakeGoalRoot -Mode 'secret'
    $secretOutput = Join-Path $secretRoot 'out'
    $secret = Invoke-Captured -Arguments @('-File', $script, '-Root', $secretRoot, '-OutputDir', $secretOutput)
    Assert-True 'secret goal next exits leak risk' ($secret.ExitCode -eq 4) "expected exit 4; output=$($secret.Output)"
    Assert-Contains 'secret goal next names classifier' $secret.Output 'decision=secret-leak-risk'
    $secretLog = Join-Path $secretOutput 'supabase-apply.log'
    $bearerPattern = 'Bearer' + '\s+' + '[A-Za-z0-9._~+/-]' + '+=*'
    Assert-True 'secret child log is redacted' ((Test-Path $secretLog) -and -not ((Get-Content -Raw -LiteralPath $secretLog) -match $bearerPattern)) 'raw auth token leaked into child log'
} catch {
    $failures++
    Write-Host $_.Exception.Message
} finally {
    if ($failures -gt 0) {
        Write-Host "[goal-next-test][SUMMARY] failed=$failures"
        exit 1
    }
    Write-Host '[goal-next-test][SUMMARY] failed=0'
}
