$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$Root = (Resolve-Path (Join-Path $ScriptsRoot "..")).Path
$SmokeScript = Join-Path $ScriptsRoot 'smoke_websoak_kpi_provider_disabled.ps1'
$SecurityConfig = Join-Path $Root 'main\java\com\example\lms\config\AppSecurityConfig.java'
$PowerShellExe = $null
try {
    $PowerShellExe = (Get-Process -Id $PID).Path
} catch {
    $PowerShellExe = $null
}
if ([string]::IsNullOrWhiteSpace($PowerShellExe)) {
    $PowerShellExe = 'powershell'
}
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[websoak-kpi-smoke-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[websoak-kpi-smoke-test][FAIL] $Name :: $Message"
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
    Assert-True $Name ($Text.Contains($Needle)) "expected source to contain '$Needle'"
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name (-not $Text.Contains($Needle)) "expected source not to contain '$Needle'"
}

try {
    Assert-True 'websoak smoke script exists' (Test-Path -LiteralPath $SmokeScript) 'missing scripts\smoke_websoak_kpi_provider_disabled.ps1'
    if (Test-Path -LiteralPath $SmokeScript) {
        $source = Get-Content -Raw -LiteralPath $SmokeScript
        Assert-Contains 'smoke starts bootRun' $source 'bootRun'
        Assert-Contains 'smoke hides runtime window' $source '-WindowStyle Hidden'
        Assert-Contains 'smoke forces desktop gradle user home' $source '.gradle-awx-desktop'
        Assert-Contains 'smoke forces desktop project cache' $source 'awx-gradle-project-cache\desktop'
        Assert-Contains 'smoke enables probe by env' $source 'PROBE_WEBSOAK_KPI_ENABLED'
        Assert-Contains 'smoke requires header key' $source 'X-Probe-Key'
        Assert-Contains 'smoke avoids query-param key' $source 'PROBE_WEBSOAK_KPI_ALLOW_QUERY_PARAM_KEY'
        Assert-Contains 'smoke clears Naver keys for disabled path' $source 'NAVER_KEYS'
        Assert-Contains 'smoke clears Brave key for disabled path' $source 'BRAVE_API_KEY'
        Assert-Contains 'smoke clears SerpApi key for disabled path' $source 'SERPAPI_API_KEY'
        Assert-Contains 'smoke checks provider disabled fields' $source 'providerDisabled'
        Assert-Contains 'smoke checks cache only fields' $source 'cacheOnly.merged.count'
        Assert-Contains 'smoke checks rescue merge fields' $source 'rescueMerge.used'
        Assert-Contains 'smoke checks starvation trigger fields' $source 'starvationFallback.trigger'
        Assert-Contains 'smoke scans high confidence secret hits' $source 'secretPatternHits'
        Assert-Contains 'smoke scans raw query leakage' $source 'rawQueryHits'
        Assert-Contains 'smoke emits evidence needed' $source 'evidence_needed'
        Assert-NotContains 'smoke does not dump full environment' $source 'Get-ChildItem Env:'
        Assert-NotContains 'smoke does not put key in URL' $source '?key='

        $tempOut = Join-Path ([IO.Path]::GetTempPath()) ('awx-websoak-kpi-smoke-test-' + [guid]::NewGuid().ToString('N'))
        $lines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
            -StaticOnly `
            -OutputDir $tempOut 2>&1 |
            ForEach-Object { $_.ToString() }
        $invokeOutput = $lines -join "`n"
        Assert-Contains 'static smoke exits cleanly' $invokeOutput '[AWX][websoak-smoke] staticOnly=true'
        Assert-Contains 'static smoke reports script path' $invokeOutput 'script='
        Assert-NotContains 'static smoke does not print probe key value' $invokeOutput 'websoak-smoke-'
        if (Test-Path -LiteralPath $tempOut) {
            Remove-Item -LiteralPath $tempOut -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    Assert-True 'app security config exists' (Test-Path -LiteralPath $SecurityConfig) 'missing AppSecurityConfig.java'
    if (Test-Path -LiteralPath $SecurityConfig) {
        $securitySource = Get-Content -Raw -LiteralPath $SecurityConfig
        Assert-Contains 'security permits internal websoak probe chain' $securitySource '"/internal/probe/**"'
        Assert-Contains 'security still permits api probe chain' $securitySource '"/api/probe/**"'
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
}

if ($Failures -gt 0) {
    Write-Host "[websoak-kpi-smoke-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[websoak-kpi-smoke-test][SUMMARY] failed=0'
exit 0
