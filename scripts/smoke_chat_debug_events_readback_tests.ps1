$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$SmokeScript = Join-Path $ScriptsRoot 'smoke_chat_debug_events_readback.ps1'
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
    Write-Host "[chat-debug-events-readback-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[chat-debug-events-readback-test][FAIL] $Name :: $Message"
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
    Assert-True 'chat debug events readback smoke script exists' (Test-Path -LiteralPath $SmokeScript) 'missing scripts\smoke_chat_debug_events_readback.ps1'
    if (Test-Path -LiteralPath $SmokeScript) {
        $source = Get-Content -Raw -LiteralPath $SmokeScript
        Assert-Contains 'readback smoke can start bootRun' $source 'bootRun'
        Assert-Contains 'readback smoke supports assume-running mode' $source 'AssumeRunning'
        Assert-Contains 'readback smoke supports static-only mode' $source 'StaticOnly'
        Assert-Contains 'readback smoke posts stream trigger' $source '/api/chat/stream'
        Assert-Contains 'readback smoke reads debug events endpoint' $source '/api/diagnostics/debug/events?limit='
        Assert-Contains 'readback smoke preserves endpoint HTTP metadata' $source 'ReadbackStatus'
        Assert-Contains 'readback smoke preserves endpoint content type' $source 'ReadbackContentType'
        Assert-Contains 'readback smoke summarizes event count' $source 'readbackEventCount'
        Assert-Contains 'readback smoke flattens nested JSON array wrappers' $source 'Add-ParsedEvent'
        Assert-Contains 'readback smoke uses response headers first' $source 'ResponseHeadersRead'
        Assert-Contains 'readback smoke awaits a single pending stream read' $source '$readTask.GetAwaiter().GetResult()'
        Assert-Contains 'readback smoke checks model guard probe' $source 'MODEL_GUARD'
        Assert-Contains 'readback smoke checks local operator action stage' $source 'local_llm_operator_action'
        Assert-Contains 'readback smoke checks safe next action' $source 'prefer_native_ollama_route'
        Assert-Contains 'readback smoke summarizes operator DebugEvent proof' $source 'operatorDebugEventPresent'
        Assert-Contains 'readback smoke scans high confidence secret hits' $source 'secretPatternHits'
        Assert-Contains 'readback smoke scans raw prompt leakage' $source 'rawPromptHits'
        Assert-Contains 'readback smoke scans raw model leakage' $source 'rawModelHits'
        Assert-Contains 'readback smoke emits evidence needed' $source 'evidence_needed'
        Assert-Contains 'readback smoke clears local api key env path' $source '$env:LLM_API_KEY = ""'
        Assert-Contains 'readback smoke sets naver keys override' $source '"keys":""'
        Assert-NotContains 'readback smoke does not set duplicate local api key value' $source '$env:LLM_API_KEY = "ollama"'
        Assert-NotContains 'readback smoke does not dump full environment' $source 'Get-ChildItem Env:'
        Assert-NotContains 'readback smoke does not print authorization headers' $source 'Authorization='
        Assert-NotContains 'readback smoke does not put raw message in query string' $source '?message='

        $tempOut = Join-Path ([IO.Path]::GetTempPath()) ('awx-chat-debug-events-readback-test-' + [guid]::NewGuid().ToString('N'))
        $lines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
            -StaticOnly `
            -OutputDir $tempOut 2>&1 |
            ForEach-Object { $_.ToString() }
        $invokeOutput = $lines -join "`n"
        Assert-Contains 'static readback smoke exits cleanly' $invokeOutput '[AWX][chat-debug-events-readback] staticOnly=true'
        Assert-Contains 'static readback smoke reports script path' $invokeOutput 'script='
        Assert-NotContains 'static readback smoke does not print raw prompt' $invokeOutput 'AWX debug fx local route probe'
        Assert-NotContains 'static readback smoke does not print raw model' $invokeOutput 'qwen3:8b'
        if (Test-Path -LiteralPath $tempOut) {
            Remove-Item -LiteralPath $tempOut -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
}

if ($Failures -gt 0) {
    Write-Host "[chat-debug-events-readback-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[chat-debug-events-readback-test][SUMMARY] failed=0'
