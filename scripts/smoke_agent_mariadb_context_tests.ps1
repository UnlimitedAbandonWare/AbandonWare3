[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ScriptUnderTest = Join-Path $PSScriptRoot "smoke_agent_mariadb_context.ps1"

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Detail = ""
    )
    if (-not $Condition) {
        throw "[FAIL] $Name $Detail"
    }
    Write-Host "[PASS] $Name"
}

Assert-True "agent db context smoke script exists" (Test-Path -LiteralPath $ScriptUnderTest)

$text = Get-Content -LiteralPath $ScriptUnderTest -Raw
Assert-True "source prints toolbox failReason" ($text -match '\$result\.failReason') "missing top-level failReason output"
Assert-True "source prints local fallback presence" ($text -match 'localFallbackPresent=') "missing local fallback output"

$output = & powershell -NoProfile -ExecutionPolicy Bypass -File $ScriptUnderTest `
    -Endpoint snapshot `
    -BaseUrl "http://127.0.0.1:9" `
    -SkipTcpProbe `
    -TimeoutSec 1

$httpLine = @($output | Where-Object { $_ -match '\[AWX\]\[agent-db-context\]\[http\]' } | Select-Object -Last 1)
Assert-True "http line emitted" ($httpLine.Count -eq 1) "output=$($output -join ' | ')"
Assert-True "fallback decision emitted" ($httpLine[0] -match 'decision=agent_db_snapshot_unavailable_with_local_fallback') $httpLine[0]
Assert-True "nonblank failReason emitted" ($httpLine[0] -match 'failReason=[A-Za-z0-9_.-]+') $httpLine[0]
Assert-True "local fallback presence emitted" ($httpLine[0] -match 'localFallbackPresent=True') $httpLine[0]

Write-Host "[AWX][agent-db-context][tests] ok=true"
