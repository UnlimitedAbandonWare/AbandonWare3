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

$FakeRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-safe-delete-presence-tests-' + [guid]::NewGuid().ToString('N'))
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[safe-delete-presence-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[safe-delete-presence-test][FAIL] $Name :: $Message"
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = 'assertion failed'
    )
    if ($Condition) { Write-Pass $Name } else { Write-Fail $Name $Message }
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

function Invoke-SafeDeletePresence {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    $scriptPath = Join-Path $script:ScriptsRoot 'safe_delete_path_presence.ps1'
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $scriptPath,
        '-Root',
        $Root,
        '-OutputPath',
        $OutputPath,
        '-Json'
    )
    $lines = & $script:PowerShellExe @arguments 2>&1 |
        ForEach-Object { $_.ToString() }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
        Json = if (Test-Path -LiteralPath $OutputPath) {
            try { Get-Content -LiteralPath $OutputPath -Raw | ConvertFrom-Json } catch { $null }
        } else {
            $null
        }
    }
}

try {
    New-Item -ItemType Directory -Force -Path $FakeRoot | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $FakeRoot '__reports__') | Out-Null
    Set-TestFile (Join-Path $FakeRoot 'BackupsXS\index.jsonl') '{"path":"archive/example.md","summary":"fixture"}'
    Set-TestFile (Join-Path $FakeRoot 'apikey.txt') ('sk-' + ('A' * 24))

    $outputPath = Join-Path $FakeRoot 'safe-delete-path-presence.json'
    $result = Invoke-SafeDeletePresence -Root $FakeRoot -OutputPath $outputPath
    $rawArtifact = if (Test-Path -LiteralPath $outputPath) { Get-Content -LiteralPath $outputPath -Raw } else { '' }
    $combinedOutput = $result.Output + "`n" + $rawArtifact

    Assert-True 'safe delete presence script exits zero' ($result.ExitCode -eq 0) "exit=$($result.ExitCode) output=$($result.Output)"
    Assert-True 'safe delete presence writes parseable json' ($null -ne $result.Json) "output=$($result.Output)"

    if ($null -ne $result.Json) {
        Assert-True 'safe delete presence is read-only' ($result.Json.mutationAllowed -eq $false -and $result.Json.deleteCommandEmitted -eq $false) "json=$rawArtifact"
        Assert-True 'safe delete presence records present and absent candidates' ([int]$result.Json.presentCount -ge 3 -and [int]$result.Json.absentCount -ge 1) "json=$rawArtifact"

        $apikey = @($result.Json.candidates | Where-Object { $_.path -eq 'apikey.txt' } | Select-Object -First 1)
        Assert-True 'safe delete presence holds secret path candidates' ($null -ne $apikey -and $apikey.classification -eq 'HOLD_SECRET_PATH' -and $apikey.deleteAllowed -eq $false) "json=$rawArtifact"

        $archiveIndex = @($result.Json.candidates | Where-Object { $_.path -eq 'BackupsXS\index.jsonl' } | Select-Object -First 1)
        Assert-True 'safe delete presence detects archive index separately' ($null -ne $archiveIndex -and $archiveIndex.exists -eq $true -and $archiveIndex.classification -eq 'HOLD_ARCHIVE_INDEX') "json=$rawArtifact"
    }

    Assert-True 'safe delete presence output does not reveal secret-like values' (-not ($combinedOutput -match 'sk-[A-Za-z0-9_-]{20,}|sbp_[A-Za-z0-9_-]{10,}|sb_secret_[A-Za-z0-9_-]{10,}')) "output=$combinedOutput"
    Assert-True 'safe delete presence output emits no remove command' (-not ($combinedOutput -match 'Remove-Item')) "output=$combinedOutput"
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
} finally {
    if (Test-Path $FakeRoot) {
        Remove-Item -LiteralPath $FakeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($Failures -gt 0) {
    Write-Host "[safe-delete-presence-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[safe-delete-presence-test][SUMMARY] failed=0'
exit 0
