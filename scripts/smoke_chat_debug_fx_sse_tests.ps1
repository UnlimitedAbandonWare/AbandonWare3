$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$SmokeScript = Join-Path $ScriptsRoot 'smoke_chat_debug_fx_sse.ps1'
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
    Write-Host "[chat-debug-fx-sse-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[chat-debug-fx-sse-test][FAIL] $Name :: $Message"
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
    Assert-True 'chat debug fx sse smoke script exists' (Test-Path -LiteralPath $SmokeScript) 'missing scripts\smoke_chat_debug_fx_sse.ps1'
    if (Test-Path -LiteralPath $SmokeScript) {
        $source = Get-Content -Raw -LiteralPath $SmokeScript
        Assert-Contains 'smoke loads http client assembly' $source 'Add-Type -AssemblyName System.Net.Http'
        Assert-Contains 'smoke can start bootRun' $source 'bootRun'
        Assert-Contains 'smoke hides runtime window' $source '-WindowStyle Hidden'
        Assert-Contains 'smoke supports assume-running mode' $source 'AssumeRunning'
        Assert-Contains 'smoke supports query rewrite requirement mode' $source 'RequireQueryRewrite'
        Assert-Contains 'smoke posts to stream endpoint' $source '/api/chat/stream'
        Assert-Contains 'smoke disables rag in payload' $source 'useRag = $false'
        Assert-Contains 'smoke enables rag only for query rewrite proof' $source 'useRag = [bool]$RequireQueryRewrite'
        Assert-Contains 'smoke disables web search in payload' $source 'useWebSearch = $false'
        Assert-Contains 'smoke forces search mode off' $source 'searchMode = "OFF"'
        Assert-Contains 'smoke parses debug fx event' $source 'event: debug_fx'
        Assert-Contains 'smoke parses transformer event' $source 'event -eq "transformer"'
        Assert-Contains 'smoke locates rewrite transformer block' $source 'Get-TransformerBlock $candidate "rewrite"'
        Assert-Contains 'smoke requires super-token rewrite reason' $source 'super-tokens:'
        Assert-Contains 'smoke summarizes query rewrite proof' $source 'queryRewritePresent'
        Assert-Contains 'smoke streams response headers first' $source 'ResponseHeadersRead'
        Assert-Contains 'smoke reads sse incrementally' $source 'ReadLineAsync'
        Assert-Contains 'smoke exits after local labels' $source 'debugFxSatisfied'
        Assert-Contains 'smoke checks local trigger label' $source 'localLlmTriggerReason'
        Assert-Contains 'smoke checks local action label' $source 'localLlmNextAction'
        Assert-Contains 'smoke scans high confidence secret hits' $source 'secretPatternHits'
        Assert-Contains 'smoke scans raw prompt leakage' $source 'rawPromptHits'
        Assert-Contains 'smoke scans raw model leakage' $source 'rawModelHits'
        Assert-Contains 'smoke emits evidence needed' $source 'evidence_needed'
        Assert-Contains 'smoke clears duplicate local api key env path' $source '$env:LLM_API_KEY = ""'
        Assert-NotContains 'smoke does not set duplicate local api key value' $source '$env:LLM_API_KEY = "ollama"'
        Assert-Contains 'smoke overrides naver self placeholder for boot' $source 'SPRING_APPLICATION_JSON'
        Assert-Contains 'smoke sets naver keys override' $source '"keys":""'
        Assert-Contains 'smoke sets naver client id override' $source '"client-id":""'
        Assert-Contains 'smoke sets naver client secret override' $source '"client-secret":""'
        Assert-NotContains 'smoke does not dump full environment' $source 'Get-ChildItem Env:'
        Assert-NotContains 'smoke does not put data in query string' $source '?message='
        Assert-NotContains 'smoke does not print authorization headers' $source 'Authorization='

        $tempOut = Join-Path ([IO.Path]::GetTempPath()) ('awx-chat-debug-fx-sse-test-' + [guid]::NewGuid().ToString('N'))
        $lines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
            -StaticOnly `
            -OutputDir $tempOut 2>&1 |
            ForEach-Object { $_.ToString() }
        $invokeOutput = $lines -join "`n"
        Assert-Contains 'static smoke exits cleanly' $invokeOutput '[AWX][chat-debug-fx-sse] staticOnly=true'
        Assert-Contains 'static smoke reports script path' $invokeOutput 'script='
        Assert-NotContains 'static smoke does not print raw prompt' $invokeOutput 'AWX debug fx local route probe'
        Assert-NotContains 'static smoke does not print raw model' $invokeOutput 'qwen3:8b'
        if (Test-Path -LiteralPath $tempOut) {
            Remove-Item -LiteralPath $tempOut -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
}

if ($Failures -gt 0) {
    Write-Host "[chat-debug-fx-sse-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[chat-debug-fx-sse-test][SUMMARY] failed=0'
