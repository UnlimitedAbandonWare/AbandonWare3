$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$PowerShellExe = $null
try {
    $PowerShellExe = (Get-Process -Id $PID).Path
} catch {
    $PowerShellExe = $null
}
if ([string]::IsNullOrWhiteSpace($PowerShellExe)) {
    $PowerShellExe = 'powershell'
}

$FakeRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-supabase-apply-tests-' + [guid]::NewGuid().ToString('N'))
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[supabase-apply-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[supabase-apply-test][FAIL] $Name :: $Message"
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = 'assertion failed'
    )
    if ($Condition) { Write-Pass $Name } else { Write-Fail $Name $Message }
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
        [AllowEmptyString()][string]$Value
    )
    $parent = Split-Path -Parent $Path
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Invoke-Apply {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$OutputDir,
        [string]$SnapshotPath = '',
        [string]$ImportMode = 'complete'
    )
    $scriptPath = Join-Path $script:ScriptsRoot 'supabase_apply_collected_evidence.ps1'
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $scriptPath,
        '-Root',
        $Root,
        '-OutputDir',
        $OutputDir
    )
    if (-not [string]::IsNullOrWhiteSpace($SnapshotPath)) {
        $arguments += @('-SnapshotPath', $SnapshotPath)
    }
    $previousMode = $env:AWX_FAKE_SUPABASE_IMPORT_MODE
    $env:AWX_FAKE_SUPABASE_IMPORT_MODE = $ImportMode
    try {
        $lines = & $script:PowerShellExe @arguments 2>&1 |
            ForEach-Object { $_.ToString() }
    } finally {
        if ($null -eq $previousMode) {
            Remove-Item Env:\AWX_FAKE_SUPABASE_IMPORT_MODE -ErrorAction SilentlyContinue
        } else {
            $env:AWX_FAKE_SUPABASE_IMPORT_MODE = $previousMode
        }
    }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
    }
}

function Invoke-ApplyHelp {
    $scriptPath = Join-Path $script:ScriptsRoot 'supabase_apply_collected_evidence.ps1'
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $scriptPath,
        '-Help'
    )
    $lines = & $script:PowerShellExe @arguments 2>&1 |
        ForEach-Object { $_.ToString() }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
    }
}

try {
    $fakeScripts = Join-Path $FakeRoot 'scripts'
    $reportDir = Join-Path $FakeRoot 'data\db-gap-report'
    New-Item -ItemType Directory -Force -Path $fakeScripts,$reportDir | Out-Null

    $help = Invoke-ApplyHelp
    Assert-True 'apply help exits zero' ($help.ExitCode -eq 0) "expected exit 0; output=$($help.Output)"
    Assert-Contains 'apply help names project ref env' $help.Output 'SUPABASE_PROJECT_REF'
    Assert-Contains 'apply help names access token env without value' $help.Output 'SUPABASE_ACCESS_TOKEN'
    Assert-Contains 'apply help names results path' $help.Output 'data\db-gap-report\supabase-query-results.json'
    Assert-Contains 'apply help names advisors path' $help.Output 'data\db-gap-report\supabase-advisors.json'
    Assert-True 'apply help prints no raw auth header' (-not ($help.Output -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*')) "output=$($help.Output)"

    Set-TestFile (Join-Path $reportDir 'supabase-schema-snapshot.json') '{"schemaVersion":"awx.mcp.supabase_schema_snapshot.v1","readOnly":true,"mutationAllowed":false}'
    Set-TestFile (Join-Path $reportDir 'supabase-query-results.json') '{"results":[{"name":"schemas_and_tables","rows":[]}]}'
    Set-TestFile (Join-Path $reportDir 'supabase-advisors.json') '{"rows":[{"category":"SECURITY","level":"WARNING"}]}'

    Set-TestFile (Join-Path $fakeScripts 'awx_mcp_toolbox.ps1') @'
param(
    [string]$Root,
    [string]$Tool,
    [string]$InputJson
)

if ($Tool -ne 'supabase_schema_snapshot_import') {
    throw "unexpected tool=$Tool"
}
if ($env:AWX_FAKE_SUPABASE_IMPORT_MODE -eq 'partial') {
    @{
        decision = 'supabase_schema_snapshot_import_evidence_needed'
        schemaSnapshotAvailable = $true
        schemaSnapshotComplete = $false
        resultSetComplete = $false
        importedResultCount = 1
        advisorRowCount = 1
        rawSecretPatternHits = 0
        mutationAllowed = $false
        missingResultNames = @('data_api_role_grants','rls_and_table_flags')
        nextActions = @('execute_each_query_once','collect_get_advisors_rows','rerun_supabase_schema_snapshot_import')
        evidence_needed = @('Supabase execute_sql result sets missing / populate a results JSON file and rerun supabase_schema_snapshot_import')
    } | ConvertTo-Json -Depth 20 -Compress
    exit 0
}
@{
    decision = 'supabase_schema_snapshot_imported'
    schemaSnapshotAvailable = $true
    schemaSnapshotComplete = $true
    resultSetComplete = $true
    importedResultCount = 12
        advisorRowCount = 1
        rawSecretPatternHits = 0
        mutationAllowed = $false
        nextActions = @()
        evidence_needed = @()
} | ConvertTo-Json -Depth 20 -Compress
exit 0
'@

    Set-TestFile (Join-Path $fakeScripts 'db_gap_scanner.py') @'
import argparse
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--root')
parser.add_argument('--output')
parser.add_argument('--format')
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
(out / 'gap_matrix.json').write_text('{"action_required_count":0}', encoding='utf-8')
print('[fake-db-gap] ok')
'@

    Set-TestFile (Join-Path $fakeScripts 'source_health_scorecard.py') @'
import argparse
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--root')
parser.add_argument('--output')
args = parser.parse_args()
out = Path(args.output)
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text('{"decision":"source_health_scorecard","strictEvidenceAdjustedScore":90,"evidenceNeeded":[]}', encoding='utf-8')
print('[fake-source-health] ok')
'@

    Set-TestFile (Join-Path $fakeScripts 'awx_mcp_completion_audit.py') @'
print('{"ok":true,"status":"local_control_tower_ready","evidence_needed":[],"rawSecretPatternHits":0}')
'@

    $completeOutput = Join-Path $FakeRoot 'out-complete'
    $complete = Invoke-Apply -Root $FakeRoot -OutputDir $completeOutput -ImportMode 'complete'
    Assert-True 'apply collected evidence exits zero for complete import' ($complete.ExitCode -eq 0) "expected exit 0; output=$($complete.Output)"
    Assert-Contains 'apply prints imported decision' $complete.Output 'importDecision=supabase_schema_snapshot_imported'
    Assert-Contains 'apply runs db gap scanner' $complete.Output 'dbGapExit=0'
    Assert-Contains 'apply runs scorecard' $complete.Output 'sourceHealthExit=0'
    Assert-Contains 'apply runs completion audit' $complete.Output 'completionAuditExit=0'
    Assert-Contains 'apply reports no secret hits' $complete.Output 'secretHits=0'
    Assert-True 'apply writes import result artifact' (Test-Path (Join-Path $completeOutput 'supabase-schema-snapshot-import.result.json')) 'missing import result artifact'
    Assert-True 'apply writes completion audit artifact' (Test-Path (Join-Path $completeOutput 'awx-mcp-completion-audit.result.json')) 'missing completion audit artifact'
    $completeSummaryPath = Join-Path $completeOutput 'supabase-apply-collected.summary.json'
    Assert-True 'apply writes summary artifact' (Test-Path $completeSummaryPath) 'missing summary artifact'
    if (Test-Path $completeSummaryPath) {
        $completeSummary = Get-Content -Raw -LiteralPath $completeSummaryPath | ConvertFrom-Json
        Assert-True 'summary reports ok' ($completeSummary.ok -eq $true) "summary=$($completeSummary | ConvertTo-Json -Compress)"
        Assert-True 'summary reports zero secret hits' ([int]$completeSummary.secretHits -eq 0) "summary=$($completeSummary | ConvertTo-Json -Compress)"
        Assert-True 'summary carries import decision' ($completeSummary.importDecision -eq 'supabase_schema_snapshot_imported') "summary=$($completeSummary | ConvertTo-Json -Compress)"
    }
    Assert-True 'apply writes db gap output under provided root' (Test-Path (Join-Path $FakeRoot 'data\db-gap-report\gap_matrix.json')) 'missing fake-root DB gap output'
    Assert-True 'apply writes scorecard output under provided root' (Test-Path (Join-Path $FakeRoot 'verification\source-health-scorecard.json')) 'missing fake-root scorecard output'

    $freshBundleDir = Join-Path $FakeRoot 'var\codex-smoke\supabase-readonly-snapshot'
    New-Item -ItemType Directory -Force -Path $freshBundleDir | Out-Null
    $freshGeneratedAt = [DateTimeOffset]::UtcNow.ToString('o')
    Set-TestFile (Join-Path $freshBundleDir 'supabase-schema-snapshot.json') (@{
        schemaVersion = 'awx.mcp.supabase_schema_snapshot.v1'
        generatedAt = $freshGeneratedAt
        readOnly = $true
        mutationAllowed = $false
        snapshots = @()
        evidence_needed = @('project_ref_missing')
    } | ConvertTo-Json -Depth 20 -Compress)
    Set-TestFile (Join-Path $freshBundleDir 'supabase-readonly-snapshot.sql') '-- readonly sql bundle'
    Set-TestFile (Join-Path $freshBundleDir 'supabase-query-results.template.json') '{"schemaVersion":"awx.supabase.query_results.template.v1","readOnly":true}'
    Set-TestFile (Join-Path $freshBundleDir 'supabase-execute-sql-collection.packet.json') '{"schemaVersion":"awx.supabase.execute_sql.collection.v1","readOnly":true,"mutationAllowed":false}'
    $bundleOutput = Join-Path $FakeRoot 'out-bundle-sync'
    $bundle = Invoke-Apply -Root $FakeRoot -OutputDir $bundleOutput -SnapshotPath (Join-Path $freshBundleDir 'supabase-schema-snapshot.json') -ImportMode 'partial'
    Assert-True 'apply with fresh bundle still exits evidence_needed for partial import' ($bundle.ExitCode -eq 2) "expected exit 2; output=$($bundle.Output)"
    Assert-Contains 'apply reports read-only snapshot bundle sync' $bundle.Output 'snapshotBundleSynced=True'
    $canonicalSnapshot = Get-Content -Raw -LiteralPath (Join-Path $reportDir 'supabase-schema-snapshot.json') | ConvertFrom-Json
    Assert-True 'apply syncs fresh read-only snapshot generatedAt into canonical report' ([string]$canonicalSnapshot.generatedAt -eq $freshGeneratedAt) "snapshot=$($canonicalSnapshot | ConvertTo-Json -Compress)"
    Assert-True 'apply syncs readonly sql sidecar into canonical report' ((Get-Content -Raw -LiteralPath (Join-Path $reportDir 'supabase-readonly-snapshot.sql')) -eq '-- readonly sql bundle') 'sql sidecar was not synced'
    $bundleSummaryPath = Join-Path $bundleOutput 'supabase-apply-collected.summary.json'
    Assert-True 'bundle sync writes summary artifact' (Test-Path $bundleSummaryPath) 'missing bundle sync summary'
    if (Test-Path $bundleSummaryPath) {
        $bundleSummary = Get-Content -Raw -LiteralPath $bundleSummaryPath | ConvertFrom-Json
        Assert-True 'bundle summary records sync true' ($bundleSummary.snapshotBundleSynced -eq $true) "summary=$($bundleSummary | ConvertTo-Json -Compress)"
        Assert-True 'bundle summary records zero bundle secret hits' ([int]$bundleSummary.snapshotBundleSecretHits -eq 0) "summary=$($bundleSummary | ConvertTo-Json -Compress)"
    }

    $partialOutput = Join-Path $FakeRoot 'out-partial'
    $partial = Invoke-Apply -Root $FakeRoot -OutputDir $partialOutput -ImportMode 'partial'
    Assert-True 'apply collected evidence exits evidence_needed for partial import' ($partial.ExitCode -eq 2) "expected exit 2; output=$($partial.Output)"
    Assert-Contains 'apply prints partial import decision' $partial.Output 'importDecision=supabase_schema_snapshot_import_evidence_needed'
    Assert-Contains 'apply reports evidence needed' $partial.Output 'evidence_needed'
    Assert-Contains 'apply reports missing result names' $partial.Output 'missingResultNames=data_api_role_grants,rls_and_table_flags'
    Assert-Contains 'apply still runs db gap scanner for partial import' $partial.Output 'dbGapExit=0'
    $partialSummaryPath = Join-Path $partialOutput 'supabase-apply-collected.summary.json'
    Assert-True 'partial import writes summary artifact' (Test-Path $partialSummaryPath) 'missing partial summary artifact'
    if (Test-Path $partialSummaryPath) {
        $partialSummary = Get-Content -Raw -LiteralPath $partialSummaryPath | ConvertFrom-Json
        Assert-True 'partial summary reports evidence_needed' ($partialSummary.decision -eq 'evidence_needed') "summary=$($partialSummary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary reports not ok' ($partialSummary.ok -eq $false) "summary=$($partialSummary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary preserves missing result count' ([int]$partialSummary.missingResultCount -eq 2) "summary=$($partialSummary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary preserves missing result names' ((@($partialSummary.missingResultNames) -join ',') -eq 'data_api_role_grants,rls_and_table_flags') "summary=$($partialSummary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary preserves data api missing result names' ((@($partialSummary.dataApiEvidenceMissing) -join ',') -eq 'data_api_role_grants,rls_and_table_flags') "summary=$($partialSummary | ConvertTo-Json -Compress)"
        $partialSummaryText = $partialSummary | ConvertTo-Json -Depth 20 -Compress
        Assert-True 'partial summary carries safe evidence_needed entries' (@($partialSummary.evidenceNeeded).Count -ge 5) "summary=$partialSummaryText"
        Assert-True 'partial summary records safe evidence_needed count' ([int]$partialSummary.safeEvidenceNeededCount -eq @($partialSummary.evidenceNeeded).Count) "summary=$partialSummaryText"
        Assert-Contains 'partial summary includes results path recommendation' $partialSummaryText 'data/db-gap-report/supabase-query-results.json'
        Assert-Contains 'partial summary includes advisors path recommendation' $partialSummaryText 'data/db-gap-report/supabase-advisors.json'
        Assert-Contains 'partial summary includes collection packet recommendation' $partialSummaryText 'data/db-gap-report/supabase-execute-sql-collection.packet.json'
        Assert-Contains 'partial summary includes apply command' $partialSummaryText 'scripts\\supabase_apply_collected_evidence.ps1'
        Assert-Contains 'partial summary includes execute_sql required tool' $partialSummaryText 'execute_sql'
        Assert-Contains 'partial summary includes get_advisors required tool' $partialSummaryText 'get_advisors'
        Assert-Contains 'partial summary includes query collection next action' $partialSummaryText 'execute_each_query_once'
        Assert-Contains 'partial summary includes advisor collection next action' $partialSummaryText 'collect_get_advisors_rows'
        Assert-Contains 'partial summary includes import rerun next action' $partialSummaryText 'rerun_supabase_schema_snapshot_import'
        Assert-Contains 'partial summary includes required data api grants result' $partialSummaryText 'data_api_role_grants'
        Assert-Contains 'partial summary includes required rls flags result' $partialSummaryText 'rls_and_table_flags'
        Assert-True 'partial summary records required result count' ([int]$partialSummary.requiredResultCount -ge 12) "summary=$partialSummaryText"
        Assert-Contains 'partial summary includes import evidence_needed' $partialSummaryText 'import:Supabase execute_sql result sets missing / populate a results JSON file and rerun supabase_schema_snapshot_import'
        Assert-Contains 'partial summary includes missing data api grants result' $partialSummaryText 'missing_result_set:data_api_role_grants'
        Assert-Contains 'partial summary includes missing rls flags result' $partialSummaryText 'missing_result_set:rls_and_table_flags'
        Assert-Contains 'partial summary includes data api grants evidence gap' $partialSummaryText 'data_api_evidence_missing:data_api_role_grants'
        Assert-Contains 'partial summary includes rls flags evidence gap' $partialSummaryText 'data_api_evidence_missing:rls_and_table_flags'
        Assert-True 'partial summary does not expose secret-like values' (-not ($partialSummaryText -match 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|Bearer\s+|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|(?i)\bjdbc:[A-Za-z0-9_+.-]*://|[A-Za-z]:\\')) "summary=$partialSummaryText"
    }

    Remove-Item -LiteralPath (Join-Path $reportDir 'supabase-query-results.json') -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $reportDir 'supabase-advisors.json') -Force -ErrorAction SilentlyContinue
    $missingPathsOutput = Join-Path $FakeRoot 'out-missing-paths'
    $missingPaths = Invoke-Apply -Root $FakeRoot -OutputDir $missingPathsOutput -ImportMode 'partial'
    Assert-True 'missing result/advisor paths exits evidence_needed' ($missingPaths.ExitCode -eq 2) "expected exit 2; output=$($missingPaths.Output)"
    $missingPathsSummaryPath = Join-Path $missingPathsOutput 'supabase-apply-collected.summary.json'
    Assert-True 'missing result/advisor paths writes summary artifact' (Test-Path $missingPathsSummaryPath) 'missing summary artifact'
    if (Test-Path $missingPathsSummaryPath) {
        $missingPathsSummary = Get-Content -Raw -LiteralPath $missingPathsSummaryPath | ConvertFrom-Json
        $missingPathsSummaryText = $missingPathsSummary | ConvertTo-Json -Depth 20 -Compress
        Assert-True 'missing paths summary marks results path absent' ($missingPathsSummary.resultsPathPresent -eq $false) "summary=$missingPathsSummaryText"
        Assert-True 'missing paths summary marks advisors path absent' ($missingPathsSummary.advisorsPathPresent -eq $false) "summary=$missingPathsSummaryText"
        Assert-Contains 'missing paths summary names results path gap' $missingPathsSummaryText 'results_path_missing'
        Assert-Contains 'missing paths summary names advisors path gap' $missingPathsSummaryText 'advisors_path_missing'
        Assert-True 'missing paths summary records safe evidence_needed count' ([int]$missingPathsSummary.safeEvidenceNeededCount -eq @($missingPathsSummary.evidenceNeeded).Count) "summary=$missingPathsSummaryText"
        Assert-True 'missing paths summary remains secret safe' (-not ($missingPathsSummaryText -match 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|Bearer\s+|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|(?i)\bjdbc:[A-Za-z0-9_+.-]*://|[A-Za-z]:\\')) "summary=$missingPathsSummaryText"
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
} finally {
    if (Test-Path $FakeRoot) {
        Remove-Item -LiteralPath $FakeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($Failures -gt 0) {
    Write-Host "[supabase-apply-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[supabase-apply-test][SUMMARY] failed=0'
exit 0
