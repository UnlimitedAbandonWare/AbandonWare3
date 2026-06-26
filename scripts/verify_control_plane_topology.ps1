param(
    [switch]$SkipGradle,
    [switch]$SkipSmokes,
    [int]$BasePort = 18160,
    [int]$StartupTimeoutSeconds = 120,
    [int]$CollisionGraceSeconds = 15
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
$CollectorSmoke = Join-Path $PSScriptRoot "smoke_learning_ops_collector.ps1"
$GpuSmoke = Join-Path $PSScriptRoot "smoke_gpu_gateway_preflight.ps1"
$env:AWX_AGENT_HOST = if ([string]::IsNullOrWhiteSpace($env:AWX_AGENT_HOST)) { "desktop" } else { $env:AWX_AGENT_HOST }
$env:AWX_SPLIT_BUILD_OUTPUTS = if ([string]::IsNullOrWhiteSpace($env:AWX_SPLIT_BUILD_OUTPUTS)) { "1" } else { $env:AWX_SPLIT_BUILD_OUTPUTS }
$env:AWX_BUILD_HOST_ID = if ([string]::IsNullOrWhiteSpace($env:AWX_BUILD_HOST_ID)) { "desktop" } else { $env:AWX_BUILD_HOST_ID }
$env:GRADLE_USER_HOME = if ([string]::IsNullOrWhiteSpace($env:AWX_GRADLE_USER_HOME)) {
    Join-Path $env:USERPROFILE ".gradle-awx-desktop"
} else {
    $env:AWX_GRADLE_USER_HOME
}
$ProjectCacheDir = if ([string]::IsNullOrWhiteSpace($env:AWX_PROJECT_CACHE_DIR)) {
    Join-Path $env:LOCALAPPDATA "awx-gradle-project-cache\desktop"
} else {
    $env:AWX_PROJECT_CACHE_DIR
}
$env:AWX_PROJECT_CACHE_DIR = $ProjectCacheDir
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCacheDir | Out-Null

if (-not (Test-Path $Gradle)) {
    throw "[AWX][topology-verify] evidence_needed: gradlew.bat missing / verify repo root"
}
if (-not (Test-Path $CollectorSmoke)) {
    throw "[AWX][topology-verify] evidence_needed: collector smoke missing / verify scripts\smoke_learning_ops_collector.ps1"
}
if (-not (Test-Path $GpuSmoke)) {
    throw "[AWX][topology-verify] evidence_needed: GPU gateway smoke missing / verify scripts\smoke_gpu_gateway_preflight.ps1"
}

function Invoke-Step([string]$Name, [scriptblock]$Block) {
    Write-Host "[AWX][topology-verify] START $Name"
    & $Block
    Write-Host "[AWX][topology-verify] OK $Name"
}

function Invoke-Native([string]$Label, [string]$FilePath, [string[]]$Arguments) {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "[AWX][topology-verify] $Label failed exit=$LASTEXITCODE"
    }
}

function Get-GradleCollisionConflicts {
    $rootPattern = [regex]::Escape($Root)
    return @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.ProcessId -ne $PID -and
                $_.CommandLine -match $rootPattern -and
                ($_.CommandLine -match 'gradlew\.bat|GradleWrapperMain|GradleMain|bootRun' -or
                 $_.CommandLine -match 'build\\classes\\java\\main.*com\.example\.lms\.LmsApplication')
            } |
            Select-Object -First 5 -Property ProcessId, Name, CommandLine)
}

function Format-GradleCollisionSummary($Conflicts) {
    return ($Conflicts | ForEach-Object { "pid=$($_.ProcessId) name=$($_.Name)" }) -join "; "
}

function Wait-ForNoGradleCollision([string]$Stage) {
    $deadline = (Get-Date).AddSeconds($CollisionGraceSeconds)
    $reportedWait = $false
    do {
        $conflicts = @(Get-GradleCollisionConflicts)
        if ($conflicts.Count -eq 0) {
            return
        }
        if (-not $reportedWait) {
            Write-Host "[AWX][topology-verify] WAIT gradle-cache-collision-clear stage=$Stage conflictingProcesses=$(Format-GradleCollisionSummary $conflicts)"
            $reportedWait = $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $conflicts = @(Get-GradleCollisionConflicts)
    if ($conflicts.Count -gt 0) {
        $summary = Format-GradleCollisionSummary $conflicts
        throw "[AWX][topology-verify] gradle-cache-collision stage=$Stage conflictingProcesses=$summary"
    }
}

function Assert-NoGradleCollision([string]$Stage) {
    Wait-ForNoGradleCollision $Stage
}

Push-Location $Root
try {
    if (-not $SkipGradle) {
        Assert-NoGradleCollision "before-focused-tests"
        Invoke-Step "focused-tests" {
            Invoke-Native "focused-tests" $Gradle @(
                "test",
                "--tests", "com.example.lms.learning.ops.RagLearningOpsDashboardServiceTest",
                "--tests", "com.example.lms.learning.ops.RagLearningOpsCurationCollectorTest",
                "--tests", "com.example.lms.web.LearningDataTemplateTest",
                "--tests", "com.example.lms.manifest.LocalModelConfigYamlTest",
                "--no-daemon",
                "--project-cache-dir", $ProjectCacheDir
            )
        }
        Assert-NoGradleCollision "before-build-surface"
        Invoke-Step "build-surface" {
            Invoke-Native "build-surface" $Gradle @(
                "checkLangchain4jVersionPurity",
                "checkSourceSetHygiene",
                "compileJava",
                ":app:classes",
                "bootJar",
                "--rerun-tasks",
                "--no-daemon",
                "--project-cache-dir", $ProjectCacheDir,
                "-x", "test"
            )
        }
    } else {
        Write-Host "[AWX][topology-verify] SKIP gradle"
    }

    if (-not $SkipSmokes) {
        # These smokes run bootRun against the shared build/classes output. Keep them sequential.
        Assert-NoGradleCollision "before-macmini-learning-ops-collector"
        Invoke-Step "macmini-learning-ops-collector" {
            Invoke-Native "macmini-learning-ops-collector" "powershell" @(
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", $CollectorSmoke,
                "-Port", [string]$BasePort,
                "-ManagementPort", [string]($BasePort + 1),
                "-StartupTimeoutSeconds", [string]$StartupTimeoutSeconds,
                "-CollectorTimeoutSeconds", "60"
            )
        }
        Assert-NoGradleCollision "before-desktop-gpu-node"
        Invoke-Step "desktop-gpu-node" {
            Invoke-Native "desktop-gpu-node" "powershell" @(
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", $GpuSmoke,
                "-Mode", "desktop",
                "-Port", [string]($BasePort + 10),
                "-ManagementPort", [string]($BasePort + 11),
                "-StartupTimeoutSeconds", [string]$StartupTimeoutSeconds
            )
        }
        Assert-NoGradleCollision "before-macmini-gpu-gateway-simulated"
        Invoke-Step "macmini-gpu-gateway-simulated" {
            Invoke-Native "macmini-gpu-gateway-simulated" "powershell" @(
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", $GpuSmoke,
                "-Mode", "simulated",
                "-Port", [string]($BasePort + 20),
                "-ManagementPort", [string]($BasePort + 21),
                "-StartupTimeoutSeconds", [string]$StartupTimeoutSeconds
            )
        }
    } else {
        Write-Host "[AWX][topology-verify] SKIP smokes"
    }

    Write-Host "[AWX][topology-verify] OK controlPlane=macmini-control-plane gpuExecutor=desktop-rtx3090-rtx3060 collector=read_only_curation queue=verified"
} finally {
    Pop-Location
}
