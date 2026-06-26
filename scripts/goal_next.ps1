[CmdletBinding()]
param(
    [string]$Root = '',

    [string]$OutputDir = '',

    [string]$Topic = 'mcp-control-loop',

    [switch]$Status,

    [switch]$Help
)

$ErrorActionPreference = 'Stop'

$autoScript = Join-Path $PSScriptRoot 'goal_next_auto.ps1'

if ($Help) {
    @'
[AWX][goal-next-wrapper] usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1 -Root <repo-root> -Topic mcp-control-loop
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1 -Root <repo-root> -Status

Purpose:
  Short "next" entrypoint for goal_next_auto.ps1.
  Default behavior runs goal_next_auto.ps1 -EnsureFresh.
  Use -Status to read the current latest/status artifacts without refreshing.

Safety:
  Delegates secret redaction and evidence_needed handling to goal_next_auto.ps1.
  Does not print token values, Authorization headers, cookies, JDBC URLs, or raw secrets.
'@ | Write-Host
    exit 0
}

$runnerArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $autoScript)
if (-not [string]::IsNullOrWhiteSpace($Root)) {
    $runnerArgs += @('-Root', $Root)
}
if (-not [string]::IsNullOrWhiteSpace($OutputDir)) {
    $runnerArgs += @('-OutputDir', $OutputDir)
}
if (-not [string]::IsNullOrWhiteSpace($Topic)) {
    $runnerArgs += @('-Topic', $Topic)
}
if ($Status) {
    $runnerArgs += '-Status'
} else {
    $runnerArgs += '-EnsureFresh'
}

& powershell @runnerArgs
exit $LASTEXITCODE
