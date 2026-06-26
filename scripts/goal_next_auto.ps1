[CmdletBinding()]
param(
    [string]$Root = '',

    [string]$OutputDir = '',

    [string]$Topic = 'mcp-control-loop',

    [switch]$Status,

    [switch]$EnsureFresh,

    [switch]$Help
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    @'
[AWX][goal-next] usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -Topic mcp-control-loop
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -Status
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -EnsureFresh

Purpose:
  Runs the next safe continuation gates in order and fails closed:
    1. smoke_supabase_readonly_snapshot.ps1
    2. supabase_apply_collected_evidence.ps1
    3. external_apply_collected_evidence.ps1
    4. awx_mcp_toolbox.py desktop_control_loop
    5. source_health_scorecard.py
    6. awx_mcp_completion_audit.py

Required external evidence before Supabase can close:
  SUPABASE_PROJECT_REF present
  SUPABASE_ACCESS_TOKEN present, or authenticated read-only Supabase MCP/CLI session
  execute_sql rows saved to data\db-gap-report\supabase-query-results.json
  get_advisors rows saved to data\db-gap-report\supabase-advisors.json

Safety:
  readOnly=true where applicable
  mutationAllowed=false where applicable
  child logs are redacted before writing
  do not print token values, Authorization headers, cookies, JDBC URLs, or raw secrets
  missing live DB or producer evidence remains evidence_needed

Status:
  -Status reads var\codex-smoke\goal-next-auto.latest.json without rerunning child gates.
  It reports stale-latest when the latest pointer is older than its stale threshold.
  -EnsureFresh reuses a fresh latest pointer, or refreshes stale/missing latest evidence by running gates.
'@ | Write-Host
    exit 0
}

function Resolve-RepoRoot {
    param([string]$Candidate)
    if (-not [string]::IsNullOrWhiteSpace($Candidate)) {
        return (Resolve-Path -LiteralPath $Candidate).Path
    }
    $scriptRoot = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
        $scriptRoot = Split-Path -Parent $PSCommandPath
    }
    return (Resolve-Path (Join-Path $scriptRoot '..')).Path
}

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$PathText
    )
    if ([System.IO.Path]::IsPathRooted($PathText)) {
        return $PathText
    }
    return (Join-Path $ProjectRoot $PathText)
}

function Count-Pattern {
    param(
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern
    )
    if ([string]::IsNullOrEmpty($Text)) {
        return 0
    }
    return ([regex]::Matches($Text, $Pattern)).Count
}

function Get-SafeCountValue {
    param($Value)
    if ($null -eq $Value) {
        return 0
    }
    if ($Value -is [System.Array]) {
        return @($Value).Count
    }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return 0
    }
    $parsed = 0
    if ([int]::TryParse($text, [ref]$parsed)) {
        return [math]::Max(0, $parsed)
    }
    return 0
}

function Redact-SensitiveText {
    param([AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrEmpty($Text)) {
        return ''
    }
    $redacted = $Text
    $redacted = [regex]::Replace($redacted, 'Bearer\s+[A-Za-z0-9._~+/-]+=*', 'Bearer [REDACTED]')
    $redacted = [regex]::Replace($redacted, 'sk-[A-Za-z0-9_-]{20,}', 'sk-[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'AIza[0-9A-Za-z_-]{20,}', 'AIza[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'gsk_[A-Za-z0-9]{20,}', 'gsk_[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'pcsk_[A-Za-z0-9_-]{20,}', 'pcsk_[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}', 'sb_[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'sbp_[A-Za-z0-9_-]{10,}', 'sbp_[REDACTED]')
    $redacted = [regex]::Replace($redacted, '(?i)\bjdbc:[A-Za-z0-9_+.-]*://[^\s''"]+', 'jdbc:[REDACTED]')
    return $redacted
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $text = $Value | ConvertTo-Json -Depth 80
    Set-Content -LiteralPath $Path -Value $text -Encoding UTF8
}

function Write-TextFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Value
    )
    Set-Content -LiteralPath $Path -Value $Value -Encoding UTF8
}

function Append-JsonLineFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $dir = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    $line = $Value | ConvertTo-Json -Depth 80 -Compress
    Add-Content -LiteralPath $Path -Value $line -Encoding UTF8
}

function Read-LastJsonLine {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        $last = Get-Content -LiteralPath $Path -Tail 1 -ErrorAction Stop
        if ([string]::IsNullOrWhiteSpace([string]$last)) {
            return $null
        }
        return ([string]$last | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Read-JsonObjectFromText {
    param([AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $null
    }
    $lines = $Text -split "\r?\n"
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        $candidate = $lines[$i].Trim()
        if (-not $candidate.StartsWith('{')) {
            continue
        }
        try {
            return ($candidate | ConvertFrom-Json)
        } catch {
            continue
        }
    }
    return $null
}

function Read-JsonLineFileTail {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$MaxRows = 25
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        return @()
    }
    try {
        $lines = @(Get-Content -LiteralPath $Path -ErrorAction Stop)
    } catch {
        return @()
    }
    if ($MaxRows -gt 0 -and $lines.Count -gt $MaxRows) {
        $lines = @($lines[($lines.Count - $MaxRows)..($lines.Count - 1)])
    }
    $items = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) {
            continue
        }
        try {
            $items.Add(($line | ConvertFrom-Json)) | Out-Null
        } catch {
            continue
        }
    }
    return @($items)
}

function Read-JsonObjectFromFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        return (Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Add-StringNextActionEntries {
    param(
        [Parameter(Mandatory = $true)]$Entries,
        [Parameter(Mandatory = $true)][string]$Source,
        $Actions
    )
    foreach ($action in @($Actions)) {
        $actionText = [string]$action
        if ([string]::IsNullOrWhiteSpace($actionText)) {
            continue
        }
        $Entries.Add([ordered]@{
            source = $Source
            action = $actionText
            decision = 'evidence_needed'
        }) | Out-Null
    }
}

function Add-ObjectNextActionEntries {
    param(
        [Parameter(Mandatory = $true)]$Entries,
        [Parameter(Mandatory = $true)][string]$Source,
        $Actions
    )
    foreach ($action in @($Actions)) {
        if ($null -eq $action -or $null -eq $action.action) {
            continue
        }
        $entry = [ordered]@{
            source = $Source
            action = [string]$action.action
            decision = if ($null -ne $action.decision) { [string]$action.decision } else { 'evidence_needed' }
        }
        foreach ($name in @('nodeRole', 'targetRole', 'hint')) {
            if ($null -ne $action.$name -and -not [string]::IsNullOrWhiteSpace([string]$action.$name)) {
                $entry[$name] = [string]$action.$name
            }
        }
        foreach ($name in @('targetService', 'topic', 'applyCollectedEvidenceCommand', 'resultPathRecommendation', 'advisorResultPathRecommendation', 'indexPathRecommendation', 'archiveRootRecommendation', 'importTool', 'mcpEndpointTemplate')) {
            if ($null -ne $action.$name -and -not [string]::IsNullOrWhiteSpace([string]$action.$name)) {
                $entry[$name] = [string]$action.$name
            }
        }
        foreach ($name in @('readOnly', 'mutationAllowed')) {
            if ($null -ne $action.$name) {
                $entry[$name] = [bool]$action.$name
            }
        }
        foreach ($name in @('requiredMcpTools', 'requiredSidecars', 'requiredResultNames', 'artifactPaths', 'docsRefs', 'nextActions', 'producerCommandTemplates')) {
            if ($null -ne $action.$name) {
                $items = @($action.$name | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
                if ($items.Count -gt 0) {
                    $entry[$name] = $items
                }
            }
        }
        if ($null -ne $action.requiredEnv) {
            $envNames = @($action.requiredEnv | ForEach-Object { [string]$_.name } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            if ($envNames.Count -gt 0) {
                $entry['requiredEnvNames'] = $envNames
            }
        } elseif ($null -ne $action.requiredEnvNames) {
            $envNames = @($action.requiredEnvNames | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            if ($envNames.Count -gt 0) {
                $entry['requiredEnvNames'] = $envNames
            }
        }
        if ($null -ne $action.requiredSourceIsolation) {
            $entry['requiredSourceIsolation'] = [ordered]@{
                guard = [string]$action.requiredSourceIsolation.guard
                sourceRootKind = [string]$action.requiredSourceIsolation.sourceRootKind
                directCanonicalSourceEdit = [bool]$action.requiredSourceIsolation.directCanonicalSourceEdit
                desktopFinalProof = [string]$action.requiredSourceIsolation.desktopFinalProof
                rawSecretPatternHits = if ($null -ne $action.requiredSourceIsolation.rawSecretPatternHits) { [int]$action.requiredSourceIsolation.rawSecretPatternHits } else { 0 }
            }
        }
        $Entries.Add($entry) | Out-Null
    }
}

function Get-EntryValue {
    param(
        $Entry,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if ($null -eq $Entry) {
        return $null
    }
    if ($Entry -is [System.Collections.IDictionary]) {
        if ($Entry.Contains($Name)) {
            return $Entry[$Name]
        }
        return $null
    }
    try {
        return $Entry.$Name
    } catch {
        return $null
    }
}

function Get-SafeCommandText {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $safe = Redact-SensitiveText -Text $Command
    if (-not [string]::IsNullOrWhiteSpace($ProjectRoot)) {
        $safe = [regex]::Replace($safe, [regex]::Escape($ProjectRoot), '<desktop-canonical-root>', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    }
    return $safe
}

function Get-CommandRoleFromTemplate {
    param(
        [AllowEmptyString()][string]$Command,
        [AllowEmptyString()][string]$FallbackRole
    )
    $match = [regex]::Match($Command, '--node-role\s+([A-Za-z0-9_-]+)')
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    if (-not [string]::IsNullOrWhiteSpace($FallbackRole)) {
        return $FallbackRole
    }
    return 'desktop'
}

function Get-CommandLane {
    param(
        [AllowEmptyString()][string]$Source,
        [AllowEmptyString()][string]$TargetService,
        [AllowEmptyString()][string]$Action,
        [bool]$HasExternalContract,
        [bool]$ProducerTemplate
    )
    if ($ProducerTemplate) {
        return 'external_producer'
    }
    if ($TargetService -eq 'supabase' -or $Source -match 'supabase') {
        return 'supabase'
    }
    if ($TargetService -eq 'archive' -or $Action -match 'archive' -or $Source -match 'archive') {
        return 'archive'
    }
    if ($HasExternalContract -or $Action -match 'external' -or $Source -match 'external') {
        return 'external_desktop'
    }
    if ($Source -match 'desktop') {
        return 'desktop'
    }
    return 'other'
}

function New-CommandPacket {
    param(
        [Parameter(Mandatory = $true)]$Entries,
        $SupabaseApplySummary,
        $ComputerUseSummary,
        $BrowserUseSummary,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$Topic,
        [Parameter(Mandatory = $true)][string]$Decision,
        [Parameter(Mandatory = $true)][string]$GeneratedAt
    )

    $commands = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($entry in @($Entries)) {
        $source = [string](Get-EntryValue -Entry $entry -Name 'source')
        $action = [string](Get-EntryValue -Entry $entry -Name 'action')
        $entryDecision = [string](Get-EntryValue -Entry $entry -Name 'decision')
        if ([string]::IsNullOrWhiteSpace($entryDecision)) {
            $entryDecision = 'evidence_needed'
        }
        $targetService = [string](Get-EntryValue -Entry $entry -Name 'targetService')
        $hasExternalContract = ($null -ne (Get-EntryValue -Entry $entry -Name 'requiredSourceIsolation')) -or ($null -ne (Get-EntryValue -Entry $entry -Name 'requiredSidecars'))
        $role = [string](Get-EntryValue -Entry $entry -Name 'nodeRole')
        if ([string]::IsNullOrWhiteSpace($role)) {
            $role = [string](Get-EntryValue -Entry $entry -Name 'targetRole')
        }
        $requiredEnvNames = @((Get-EntryValue -Entry $entry -Name 'requiredEnvNames') | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

        if ($source -eq 'computer_use') {
            $key = "computer_use|$source|$action"
            if ($seen.Add($key)) {
                $commands.Add([ordered]@{
                    lane = 'computer_use'
                    role = 'desktop'
                    source = $source
                    action = $action
                    decision = $entryDecision
                    tool = 'mcp__node_repl.js'
                    command = 'refresh_computer_use_lightweight_smoke'
                    outputPath = 'var/codex-smoke/computer-use-smoke.json'
                    storesRawAppNames = $false
                    requiredEnvNames = @()
                }) | Out-Null
            }
            continue
        }

        if ($source -eq 'browser_use') {
            $key = "browser_use|$source|$action"
            if ($seen.Add($key)) {
                $commands.Add([ordered]@{
                    lane = 'browser_use'
                    role = 'desktop'
                    source = $source
                    action = $action
                    decision = $entryDecision
                    tool = 'browser.control-in-app-browser'
                    command = 'refresh_browser_local_ui_smoke'
                    outputPath = 'var/codex-smoke/browser-ui-smoke.json'
                    storesRawUrl = $false
                    storesScreenshotPath = $false
                    requiredEnvNames = @()
                }) | Out-Null
            }
            continue
        }

        $applyCommand = [string](Get-EntryValue -Entry $entry -Name 'applyCollectedEvidenceCommand')
        if (-not [string]::IsNullOrWhiteSpace($applyCommand)) {
            $safeCommand = Get-SafeCommandText -Command $applyCommand -ProjectRoot $ProjectRoot
            $key = "apply|$source|$action|$safeCommand"
            if ($seen.Add($key)) {
                $lane = Get-CommandLane -Source $source -TargetService $targetService -Action $action -HasExternalContract $hasExternalContract -ProducerTemplate $false
                $commandEntry = [ordered]@{
                    lane = $lane
                    role = if (-not [string]::IsNullOrWhiteSpace($role)) { $role } else { 'desktop' }
                    source = $source
                    action = $action
                    decision = $entryDecision
                    command = $safeCommand
                    requiredEnvNames = $requiredEnvNames
                }
                if ($lane -eq 'supabase') {
                    $readOnlyValue = Get-EntryValue -Entry $entry -Name 'readOnly'
                    $mutationAllowedValue = Get-EntryValue -Entry $entry -Name 'mutationAllowed'
                    $endpointTemplate = [string](Get-EntryValue -Entry $entry -Name 'mcpEndpointTemplate')
                    $commandEntry['readOnly'] = if ($null -ne $readOnlyValue) { [bool]$readOnlyValue } else { $true }
                    $commandEntry['mutationAllowed'] = if ($null -ne $mutationAllowedValue) { [bool]$mutationAllowedValue } else { $false }
                    if ([string]::IsNullOrWhiteSpace($endpointTemplate)) {
                        $endpointTemplate = 'https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs'
                    }
                    if (-not [string]::IsNullOrWhiteSpace($endpointTemplate)) {
                        $commandEntry['mcpEndpointTemplate'] = Get-SafePacketString -Text $endpointTemplate -ProjectRoot $ProjectRoot
                    }
                    $docsRefs = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $entry -Name 'docsRefs') -ProjectRoot $ProjectRoot
                    if ($docsRefs.Count -eq 0) {
                        $docsRefs = @(
                            'https://supabase.com/docs/guides/ai-tools/mcp',
                            'https://supabase.com/docs/guides/api/securing-your-api',
                            'https://supabase.com/docs/guides/security/product-security'
                        )
                    }
                    if ($docsRefs.Count -gt 0) {
                        $commandEntry['docsRefs'] = $docsRefs
                    }
                }
                if ($lane -eq 'archive') {
                    $readOnlyValue = Get-EntryValue -Entry $entry -Name 'readOnly'
                    $mutationAllowedValue = Get-EntryValue -Entry $entry -Name 'mutationAllowed'
                    $commandEntry['readOnly'] = if ($null -ne $readOnlyValue) { [bool]$readOnlyValue } else { $true }
                    $commandEntry['mutationAllowed'] = if ($null -ne $mutationAllowedValue) { [bool]$mutationAllowedValue } else { $false }
                }
                foreach ($field in @('resultPathRecommendation', 'advisorResultPathRecommendation', 'indexPathRecommendation', 'archiveRootRecommendation', 'importTool')) {
                    $fieldValue = [string](Get-EntryValue -Entry $entry -Name $field)
                    if ([string]::IsNullOrWhiteSpace($fieldValue) -and $lane -eq 'supabase' -and $null -ne $SupabaseApplySummary) {
                        $fieldValue = [string]$SupabaseApplySummary.$field
                    }
                    if (-not [string]::IsNullOrWhiteSpace($fieldValue)) {
                        $commandEntry[$field] = Get-SafePacketString -Text $fieldValue -ProjectRoot $ProjectRoot
                    }
                }
                foreach ($field in @('requiredMcpTools', 'artifactPaths')) {
                    $rawFieldValues = @(Get-EntryValue -Entry $entry -Name $field)
                    if ($lane -eq 'supabase' -and $field -eq 'requiredMcpTools' -and $null -ne $SupabaseApplySummary) {
                        $rawFieldValues += @($SupabaseApplySummary.requiredMcpTools)
                    }
                    if ($lane -eq 'supabase' -and $field -eq 'artifactPaths') {
                        $rawFieldValues += @(
                            'data/db-gap-report/supabase-execute-sql-collection.packet.json',
                            'data/db-gap-report/supabase-query-results.template.json',
                            'data/db-gap-report/supabase-readonly-snapshot.sql',
                            'data/db-gap-report/supabase-schema-snapshot.json'
                        )
                    }
                    $fieldValues = Get-SafeUniqueStrings -Values $rawFieldValues -ProjectRoot $ProjectRoot
                    if ($fieldValues.Count -gt 0) {
                        $commandEntry[$field] = $fieldValues
                    }
                }
                $commands.Add($commandEntry) | Out-Null
            }
        }

        foreach ($template in @((Get-EntryValue -Entry $entry -Name 'producerCommandTemplates'))) {
            $templateText = [string]$template
            if ([string]::IsNullOrWhiteSpace($templateText)) {
                continue
            }
            $safeTemplate = Get-SafeCommandText -Command $templateText -ProjectRoot $ProjectRoot
            $key = "producer|$source|$action|$safeTemplate"
            if ($seen.Add($key)) {
                $lane = Get-CommandLane -Source $source -TargetService $targetService -Action $action -HasExternalContract $hasExternalContract -ProducerTemplate $true
                $commands.Add([ordered]@{
                    lane = $lane
                    role = Get-CommandRoleFromTemplate -Command $safeTemplate -FallbackRole $role
                    source = $source
                    action = $action
                    decision = $entryDecision
                    command = $safeTemplate
                    requiredEnvNames = @()
                }) | Out-Null
            }
        }
    }

    $lanes = @($commands | ForEach-Object { [string]$_.lane } | Select-Object -Unique)
    $safeComputerUseSummary = [ordered]@{
        present = $false
        parsed = $false
        ok = $false
        decision = 'evidence_needed'
        reachable = $false
        stale = $false
        appCount = 0
        runningCount = 0
        windowCount = 0
        nextAction = ''
        outputPath = 'var/codex-smoke/computer-use-smoke.json'
        storesRawAppNames = $false
        storesWindowTitles = $false
        secretHits = 0
    }
    if ($null -ne $ComputerUseSummary) {
        $safeComputerUseSummary.present = [bool]$ComputerUseSummary.present
        $safeComputerUseSummary.parsed = [bool]$ComputerUseSummary.parsed
        $safeComputerUseSummary.ok = [bool]$ComputerUseSummary.ok
        $safeComputerUseSummary.decision = Get-SafePacketString -Text ([string]$ComputerUseSummary.decision) -ProjectRoot $ProjectRoot
        $safeComputerUseSummary.reachable = [bool]$ComputerUseSummary.reachable
        $safeComputerUseSummary.stale = [bool]$ComputerUseSummary.stale
        $safeComputerUseSummary.appCount = [int]$ComputerUseSummary.appCount
        $safeComputerUseSummary.runningCount = [int]$ComputerUseSummary.runningCount
        $safeComputerUseSummary.windowCount = [int]$ComputerUseSummary.windowCount
        $safeComputerUseSummary.nextAction = Get-SafePacketString -Text ([string]$ComputerUseSummary.nextAction) -ProjectRoot $ProjectRoot
        $safeComputerUseSummary.secretHits = [int]$ComputerUseSummary.secretHits
    }
    $safeBrowserUseSummary = [ordered]@{
        present = $false
        parsed = $false
        ok = $false
        decision = 'evidence_needed'
        reachable = $false
        localhost = $false
        stale = $false
        screenshotCaptured = $false
        statusClass = 'unknown'
        targetContentVisible = $false
        browserSurface = 'unknown'
        nextAction = ''
        outputPath = 'var/codex-smoke/browser-ui-smoke.json'
        storesRawUrl = $false
        storesScreenshotPath = $false
        secretHits = 0
    }
    if ($null -ne $BrowserUseSummary) {
        $safeBrowserUseSummary.present = [bool]$BrowserUseSummary.present
        $safeBrowserUseSummary.parsed = [bool]$BrowserUseSummary.parsed
        $safeBrowserUseSummary.ok = [bool]$BrowserUseSummary.ok
        $safeBrowserUseSummary.decision = Get-SafePacketString -Text ([string]$BrowserUseSummary.decision) -ProjectRoot $ProjectRoot
        $safeBrowserUseSummary.reachable = [bool]$BrowserUseSummary.reachable
        $safeBrowserUseSummary.localhost = [bool]$BrowserUseSummary.localhost
        $safeBrowserUseSummary.stale = [bool]$BrowserUseSummary.stale
        $safeBrowserUseSummary.screenshotCaptured = [bool]$BrowserUseSummary.screenshotCaptured
        $safeBrowserUseSummary.statusClass = Get-SafePacketString -Text ([string]$BrowserUseSummary.statusClass) -ProjectRoot $ProjectRoot
        $safeBrowserUseSummary.targetContentVisible = [bool]$BrowserUseSummary.targetContentVisible
        $safeBrowserUseSummary.browserSurface = Get-SafePacketString -Text ([string]$BrowserUseSummary.browserSurface) -ProjectRoot $ProjectRoot
        $safeBrowserUseSummary.nextAction = Get-SafePacketString -Text ([string]$BrowserUseSummary.nextAction) -ProjectRoot $ProjectRoot
        $safeBrowserUseSummary.secretHits = [int]$BrowserUseSummary.secretHits
    }
    return [ordered]@{
        schemaVersion = 'awx.goal_next_auto.command_packet.v1'
        generatedAt = $GeneratedAt
        decision = $Decision
        topic = $Topic
        root = $ProjectRoot
        secretSafe = $true
        commandCount = $commands.Count
        lanes = $lanes
        commands = @($commands)
        computerUse = $safeComputerUseSummary
        browserUse = $safeBrowserUseSummary
    }
}

function Get-SafePacketString {
    param(
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $safe = Redact-SensitiveText -Text ([string]$Text)
    if (-not [string]::IsNullOrWhiteSpace($ProjectRoot)) {
        $safe = [regex]::Replace($safe, [regex]::Escape($ProjectRoot), '<desktop-canonical-root>', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    }
    return $safe
}

function Get-SafeUniqueStrings {
    param(
        $Values,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $items = [System.Collections.Generic.List[string]]::new()
    foreach ($value in @($Values)) {
        $text = Get-SafePacketString -Text ([string]$value) -ProjectRoot $ProjectRoot
        if ([string]::IsNullOrWhiteSpace($text)) {
            continue
        }
        if (-not $items.Contains($text)) {
            $items.Add($text) | Out-Null
        }
    }
    return @($items)
}

function New-PreviousBreadcrumbSummary {
    param($Previous)
    if ($null -eq $Previous) {
        return [ordered]@{
            present = $false
            generatedAt = ''
            decision = ''
            failureClassification = ''
            firstAction = ''
            firstActionSource = ''
            toolStepCount = 0
            secretHits = 0
        }
    }
    return [ordered]@{
        present = $true
        generatedAt = [string]$Previous.generatedAt
        decision = [string]$Previous.decision
        failureClassification = [string]$Previous.failureClassification
        firstAction = [string]$Previous.firstAction
        firstActionSource = [string]$Previous.firstActionSource
        toolStepCount = if ($null -ne $Previous.toolSteps) { @($Previous.toolSteps).Count } else { 0 }
        secretHits = if ($null -ne $Previous.secretHits) { [int]$Previous.secretHits } else { 0 }
    }
}

function New-BreadcrumbFusionSummary {
    param(
        $Rows,
        [Parameter(Mandatory = $true)][string]$GeneratedAtText,
        [int]$TailLimit = 25
    )
    $rowItems = @($Rows)
    $firstActionCounts = @{}
    $failureCounts = @{}
    $bottleneckCounts = @{}
    foreach ($row in $rowItems) {
        $firstAction = [string]$row.firstAction
        if (-not [string]::IsNullOrWhiteSpace($firstAction)) {
            if (-not $firstActionCounts.ContainsKey($firstAction)) { $firstActionCounts[$firstAction] = 0 }
            $firstActionCounts[$firstAction] = [int]$firstActionCounts[$firstAction] + 1
        }
        $failure = [string]$row.failureClassification
        if (-not [string]::IsNullOrWhiteSpace($failure)) {
            if (-not $failureCounts.ContainsKey($failure)) { $failureCounts[$failure] = 0 }
            $failureCounts[$failure] = [int]$failureCounts[$failure] + 1
        }
        $bottleneck = if ($null -ne $row.rawTile) { [string]$row.rawTile.bottleneck } else { [string]$row.firstActionSource }
        if (-not [string]::IsNullOrWhiteSpace($bottleneck)) {
            if (-not $bottleneckCounts.ContainsKey($bottleneck)) { $bottleneckCounts[$bottleneck] = 0 }
            $bottleneckCounts[$bottleneck] = [int]$bottleneckCounts[$bottleneck] + 1
        }
    }

    $countRows = {
        param($Map, [string]$NameKey)
        $out = foreach ($key in $Map.Keys) {
            $item = [ordered]@{}
            $item[$NameKey] = [string]$key
            $item['count'] = [int]$Map[$key]
            [pscustomobject]$item
        }
        return @($out | Sort-Object -Property @{ Expression = 'count'; Descending = $true }, @{ Expression = $NameKey; Ascending = $true } | Select-Object -First 8)
    }

    $latest = if ($rowItems.Count -gt 0) { $rowItems[$rowItems.Count - 1] } else { $null }
    $latestRawTile = [ordered]@{}
    if ($null -ne $latest -and $null -ne $latest.rawTile) {
        $latestRawTile = [ordered]@{
            schemaVersion = [string]$latest.rawTile.schemaVersion
            tileKind = [string]$latest.rawTile.tileKind
            status = [string]$latest.rawTile.status
            blocker = [string]$latest.rawTile.blocker
            bottleneck = [string]$latest.rawTile.bottleneck
            nextAction = [string]$latest.rawTile.nextAction
            reuseSource = [string]$latest.rawTile.reuseSource
            previousFirstAction = [string]$latest.rawTile.previousFirstAction
        }
    }

    $repeatedFirstActions = & $countRows $firstActionCounts 'action'
    $topAction = if (@($repeatedFirstActions).Count -gt 0) { $repeatedFirstActions[0] } else { $null }
    $reusablePattern = if ($null -ne $topAction -and [int]$topAction.count -gt 1) {
        'repeat:firstAction'
    } elseif ($rowItems.Count -gt 0) {
        'single:firstAction'
    } else {
        'empty'
    }
    $latestBottleneck = if ($latestRawTile.Contains('bottleneck')) { [string]$latestRawTile.bottleneck } elseif ($null -ne $latest) { [string]$latest.firstActionSource } else { '' }
    $latestAction = if ($latestRawTile.Contains('nextAction')) { [string]$latestRawTile.nextAction } elseif ($null -ne $latest) { [string]$latest.firstAction } else { '' }
    $externalInputSources = @('supabase_apply', 'external_apply', 'archive_search')
    $isExternalInput = ($externalInputSources -contains $latestBottleneck)
    $externalInputGate = [ordered]@{
        schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
        status = if ($isExternalInput) { 'external_input_needed' } elseif ($rowItems.Count -gt 0) { 'local_or_unknown' } else { 'empty' }
        source = $latestBottleneck
        action = $latestAction
        repeated = ($null -ne $topAction -and [string]$topAction.action -eq $latestAction -and [int]$topAction.count -gt 1)
        repeatCount = if ($null -ne $topAction -and [string]$topAction.action -eq $latestAction) { [int]$topAction.count } elseif (-not [string]::IsNullOrWhiteSpace($latestAction)) { 1 } else { 0 }
        localPatchJustified = -not $isExternalInput
        mutationAllowed = $false
        evidenceNeeded = if ($isExternalInput) {
            if ($latestBottleneck -eq 'supabase_apply') {
                @('SUPABASE_PROJECT_REF', 'read_only_supabase_mcp_or_cli_auth', 'execute_sql_results', 'get_advisors_results')
            } elseif ($latestBottleneck -eq 'external_apply') {
                @('macmini_node_smoke_json', 'notebook_node_smoke_json', 'producer_handoff_json', 'patchdrop_v3_sidecars')
            } else {
                @('archive_index_jsonl')
            }
        } else {
            @()
        }
    }

    return [ordered]@{
        schemaVersion = 'awx.goal_next_auto.breadcrumb_fusion.v1'
        generatedAt = $GeneratedAtText
        root = '<desktop-canonical-root>'
        tailLimit = $TailLimit
        timelineRowsRead = $rowItems.Count
        latestGeneratedAt = if ($null -ne $latest) { [string]$latest.generatedAt } else { '' }
        latestDecision = if ($null -ne $latest) { [string]$latest.decision } else { '' }
        latestFirstAction = if ($null -ne $latest) { [string]$latest.firstAction } else { '' }
        latestRawTile = $latestRawTile
        latestTraceKeys = if ($null -ne $latest -and $null -ne $latest.trace) { @($latest.trace.keys) } else { @() }
        latestMdc = if ($null -ne $latest -and $null -ne $latest.mdc) {
            [ordered]@{
                nodeRole = [string]$latest.mdc.nodeRole
                topic = [string]$latest.mdc.topic
                root = [string]$latest.mdc.root
            }
        } else {
            [ordered]@{}
        }
        reusablePattern = $reusablePattern
        externalInputGate = $externalInputGate
        repeatedFirstActions = @($repeatedFirstActions)
        repeatedFailureClassifications = @((& $countRows $failureCounts 'failureClassification'))
        repeatedBottlenecks = @((& $countRows $bottleneckCounts 'bottleneck'))
    }
}

function Get-SupabaseMcpConfigSummary {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $configPath = Join-Path $ProjectRoot '.mcp.json'
    if (-not (Test-Path -LiteralPath $configPath)) {
        return [ordered]@{
            present = $false
            parsed = $false
            readOnly = $false
            projectRefSource = 'missing'
            tokenStored = $false
            serverHost = ''
            features = @()
        }
    }

    $raw = ''
    try {
        $raw = Get-Content -Raw -LiteralPath $configPath
        $config = $raw | ConvertFrom-Json
    } catch {
        return [ordered]@{
            present = $true
            parsed = $false
            readOnly = $false
            projectRefSource = 'unknown'
            tokenStored = $false
            serverHost = ''
            features = @()
        }
    }

    $urlText = ''
    try {
        if ($null -ne $config.mcpServers -and $null -ne $config.mcpServers.supabase) {
            $urlText = [string]$config.mcpServers.supabase.url
        }
    } catch {
        $urlText = ''
    }

    $serverHost = ''
    $hostMatch = [regex]::Match($urlText, '^(?:https?://)?([^/?#]+)')
    if ($hostMatch.Success) {
        $serverHost = ($hostMatch.Groups[1].Value.ToLowerInvariant() -replace '[^a-z0-9.-]', '')
    }

    $query = ''
    $queryIndex = $urlText.IndexOf('?')
    if ($queryIndex -ge 0 -and $queryIndex -lt ($urlText.Length - 1)) {
        $query = $urlText.Substring($queryIndex + 1)
    }
    $queryMap = @{}
    foreach ($pair in @($query -split '&')) {
        if ([string]::IsNullOrWhiteSpace($pair)) {
            continue
        }
        $parts = $pair -split '=', 2
        $name = $parts[0].Trim().ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($name)) {
            continue
        }
        $value = if ($parts.Count -gt 1) { $parts[1].Trim() } else { '' }
        try {
            $value = [System.Uri]::UnescapeDataString($value)
        } catch {
            $value = ''
        }
        $queryMap[$name] = $value
    }

    $projectRefSource = 'missing'
    if ($queryMap.ContainsKey('project_ref')) {
        $projectRefValue = [string]$queryMap['project_ref']
        if ($projectRefValue -eq '${SUPABASE_PROJECT_REF}') {
            $projectRefSource = 'SUPABASE_PROJECT_REF'
        } elseif ($projectRefValue -match '^\$\{[A-Za-z_][A-Za-z0-9_]*\}$') {
            $projectRefSource = 'env'
        } elseif (-not [string]::IsNullOrWhiteSpace($projectRefValue)) {
            $projectRefSource = 'literal_redacted'
        }
    }

    $features = @()
    if ($queryMap.ContainsKey('features')) {
        $featureItems = [System.Collections.Generic.List[string]]::new()
        foreach ($rawFeature in @([string]$queryMap['features'] -split ',')) {
            $feature = $rawFeature.Trim()
            if ($feature -match '^[A-Za-z0-9_-]+$') {
                $featureItems.Add($feature) | Out-Null
            }
        }
        $features = @($featureItems)
    }

    $secretPattern = 'Bearer\s+(?!\$\{)[A-Za-z0-9._~+/-]{16,}=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|(?i)(?:access_token|api_key|apikey|token)=((?!\$\{)[^&\s"'']{16,})'
    $authLiteral = [regex]::IsMatch($raw, '(?i)"Authorization"\s*:\s*"Bearer\s+(?!\$\{)[^"]{16,}"')
    $tokenStored = ((Count-Pattern -Text $raw -Pattern $secretPattern) -gt 0) -or $authLiteral

    return [ordered]@{
        present = $true
        parsed = $true
        readOnly = ($queryMap.ContainsKey('read_only') -and ([string]$queryMap['read_only']).ToLowerInvariant() -eq 'true')
        projectRefSource = $projectRefSource
        tokenStored = [bool]$tokenStored
        serverHost = $serverHost
        features = $features
    }
}

function New-DefaultSupabaseSmokeSummary {
    param(
        [AllowEmptyString()][string]$SummaryPath = '',
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [int]$SecretHits = 0
    )

    $safeSummaryPath = ''
    if (-not [string]::IsNullOrWhiteSpace($SummaryPath)) {
        $safeSummaryPath = Get-SafePacketString -Text $SummaryPath -ProjectRoot $ProjectRoot
    }

    return [ordered]@{
        parsed = $false
        summaryPath = $safeSummaryPath
        ok = $false
        decision = 'evidence_needed'
        projectScopeStatus = 'evidence_needed'
        mcpReachable = $false
        mcpProbeSkipped = $true
        mcpDecision = 'evidence_needed'
        mcpEndpointReachabilityEvidence = 'evidence_needed'
        evidenceNeededCount = 0
        secretHits = $SecretHits
        rawSecretPatternHits = $SecretHits
    }
}

function Get-SupabaseSmokeSummary {
    param(
        [Parameter(Mandatory = $true)][string]$SummaryPath,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )

    $safeSummaryPath = Get-SafePacketString -Text $SummaryPath -ProjectRoot $ProjectRoot
    $rawSecretHits = 0
    if (Test-Path -LiteralPath $SummaryPath) {
        try {
            $rawText = Get-Content -Raw -LiteralPath $SummaryPath
            $rawSecretHits = Count-Pattern -Text $rawText -Pattern 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}'
        } catch {
            $rawSecretHits = 0
        }
    }

    $raw = Read-JsonObjectFromFile -Path $SummaryPath
    if ($null -eq $raw) {
        return New-DefaultSupabaseSmokeSummary -SummaryPath $SummaryPath -ProjectRoot $ProjectRoot -SecretHits $rawSecretHits
    }

    $decision = if ([string]::IsNullOrWhiteSpace([string]$raw.decision)) { 'evidence_needed' } else { [string]$raw.decision }
    $projectScopeStatus = if ([string]::IsNullOrWhiteSpace([string]$raw.projectScopeStatus)) { 'evidence_needed' } else { [string]$raw.projectScopeStatus }
    $mcpDecision = if ([string]::IsNullOrWhiteSpace([string]$raw.mcpDecision)) { 'evidence_needed' } else { [string]$raw.mcpDecision }
    $mcpEndpointReachabilityEvidence = if (-not [string]::IsNullOrWhiteSpace([string]$raw.mcpEndpointReachabilityEvidence)) {
        [string]$raw.mcpEndpointReachabilityEvidence
    } elseif ($null -ne $raw.mcpReachable -and [bool]$raw.mcpReachable) {
        'reachable'
    } elseif ($null -ne $raw.mcpProbeSkipped -and [bool]$raw.mcpProbeSkipped) {
        'probe_skipped'
    } else {
        'evidence_needed'
    }

    $summarySecretHits = $rawSecretHits
    if ($null -ne $raw.secretHits) {
        $summarySecretHits += [int]$raw.secretHits
    }
    $summaryRawSecretHits = $rawSecretHits
    if ($null -ne $raw.rawSecretPatternHits) {
        $summaryRawSecretHits += [int]$raw.rawSecretPatternHits
    }

    return [ordered]@{
        parsed = $true
        summaryPath = $safeSummaryPath
        ok = if ($null -ne $raw.ok) { [bool]$raw.ok } else { $false }
        decision = $decision
        projectScopeStatus = $projectScopeStatus
        mcpReachable = if ($null -ne $raw.mcpReachable) { [bool]$raw.mcpReachable } else { $false }
        mcpProbeSkipped = if ($null -ne $raw.mcpProbeSkipped) { [bool]$raw.mcpProbeSkipped } else { $true }
        mcpDecision = $mcpDecision
        mcpEndpointReachabilityEvidence = $mcpEndpointReachabilityEvidence
        evidenceNeededCount = if ($null -ne $raw.evidenceNeeded) { @($raw.evidenceNeeded).Count } else { 0 }
        secretHits = $summarySecretHits
        rawSecretPatternHits = $summaryRawSecretHits
    }
}

function Get-ComputerUseSmokeSummary {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $smokePath = Join-Path $ProjectRoot 'var\codex-smoke\computer-use-smoke.json'
    $safePath = Get-SafePacketString -Text $smokePath -ProjectRoot $ProjectRoot
    $staleAfterMinutes = 60
    if (-not (Test-Path -LiteralPath $smokePath)) {
        return [ordered]@{
            present = $false
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            reachable = $false
            appCount = 0
            runningCount = 0
            windowCount = 0
            generatedAt = ''
            ageMinutes = $null
            staleAfterMinutes = $staleAfterMinutes
            stale = $true
            path = $safePath
            nextAction = 'run_computer_use_lightweight_smoke'
            secretHits = 0
            rawSecretPatternHits = 0
        }
    }

    $rawText = Get-Content -Raw -LiteralPath $smokePath
    $rawSecretHits = Count-Pattern -Text $rawText -Pattern 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}'
    try {
        $smoke = $rawText | ConvertFrom-Json
    } catch {
        return [ordered]@{
            present = $true
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            reachable = $false
            appCount = 0
            runningCount = 0
            windowCount = 0
            generatedAt = ''
            ageMinutes = $null
            staleAfterMinutes = $staleAfterMinutes
            stale = $true
            path = $safePath
            nextAction = 'regenerate_computer_use_smoke_json'
            secretHits = $rawSecretHits
            rawSecretPatternHits = $rawSecretHits
        }
    }

    $ok = if ($null -ne $smoke.ok) { [bool]$smoke.ok } else { $false }
    $smokeDecision = [string]$smoke.decision
    $reachable = if ($null -ne $smoke.reachable) {
        [bool]$smoke.reachable
    } elseif ($ok -and ([string]::IsNullOrWhiteSpace($smokeDecision) -or $smokeDecision -eq 'ok')) {
        $true
    } else {
        $false
    }
    $generatedAtText = [string]$smoke.generatedAt
    if ([string]::IsNullOrWhiteSpace($generatedAtText)) {
        $generatedAtText = [string]$smoke.checkedAt
    }
    $ageMinutes = $null
    $stale = $true
    try {
        $generatedAt = [datetimeoffset]::Parse($generatedAtText, [System.Globalization.CultureInfo]::InvariantCulture)
        $ageMinutes = [math]::Round(((Get-Date).ToUniversalTime() - $generatedAt.UtcDateTime).TotalMinutes, 3)
        $stale = ($ageMinutes -gt $staleAfterMinutes)
    } catch {
        $stale = $true
    }
    $decision = if ($rawSecretHits -gt 0) {
        'secret-leak-risk'
    } elseif ($stale) {
        'evidence_needed'
    } elseif (-not [string]::IsNullOrWhiteSpace([string]$smoke.decision)) {
        [string]$smoke.decision
    } elseif ($ok -and $reachable) {
        'ok'
    } else {
        'evidence_needed'
    }

    return [ordered]@{
        present = $true
        parsed = $true
        ok = ($ok -and $reachable -and -not $stale -and $rawSecretHits -eq 0)
        decision = $decision
        reachable = $reachable
        appCount = if ($null -ne $smoke.appCount) { [int]$smoke.appCount } else { 0 }
        runningCount = if ($null -ne $smoke.runningCount) { [int]$smoke.runningCount } else { 0 }
        windowCount = if ($null -ne $smoke.windowCount) { [int]$smoke.windowCount } else { 0 }
        generatedAt = Get-SafePacketString -Text $generatedAtText -ProjectRoot $ProjectRoot
        ageMinutes = $ageMinutes
        staleAfterMinutes = $staleAfterMinutes
        stale = $stale
        path = $safePath
        nextAction = if ($ok -and $reachable -and -not $stale) { '' } else { 'rerun_computer_use_lightweight_smoke' }
        secretHits = (Get-SafeCountValue $smoke.secretHits) + $rawSecretHits
        rawSecretPatternHits = (Get-SafeCountValue $smoke.rawSecretPatternHits) + $rawSecretHits
    }
}

function Get-BrowserUseSmokeSummary {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $smokePath = Join-Path $ProjectRoot 'var\codex-smoke\browser-ui-smoke.json'
    $safePath = Get-SafePacketString -Text $smokePath -ProjectRoot $ProjectRoot
    $staleAfterMinutes = 60
    if (-not (Test-Path -LiteralPath $smokePath)) {
        return [ordered]@{
            present = $false
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            reachable = $false
            localhost = $false
            screenshotCaptured = $false
            statusClass = 'missing'
            targetContentVisible = $false
            browserSurface = 'unknown'
            generatedAt = ''
            ageMinutes = $null
            staleAfterMinutes = $staleAfterMinutes
            stale = $true
            path = $safePath
            nextAction = 'run_browser_local_ui_smoke'
            secretHits = 0
            rawSecretPatternHits = 0
        }
    }

    $rawText = Get-Content -Raw -LiteralPath $smokePath
    $rawSecretHits = Count-Pattern -Text $rawText -Pattern 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}'
    try {
        $smoke = $rawText | ConvertFrom-Json
    } catch {
        return [ordered]@{
            present = $true
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            reachable = $false
            localhost = $false
            screenshotCaptured = $false
            statusClass = 'unreadable'
            targetContentVisible = $false
            browserSurface = 'unknown'
            generatedAt = ''
            ageMinutes = $null
            staleAfterMinutes = $staleAfterMinutes
            stale = $true
            path = $safePath
            nextAction = 'regenerate_browser_ui_smoke_json'
            secretHits = $rawSecretHits
            rawSecretPatternHits = $rawSecretHits
        }
    }

    $proofNames = @(([string]$smoke.proofNames -split ',') | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $requiredProofNames = @('local', 'browser', 'computer', 'supabase', 'producer', 'action')
    $hasRequiredProofNames = $true
    foreach ($requiredProofName in $requiredProofNames) {
        if ($proofNames -notcontains $requiredProofName) {
            $hasRequiredProofNames = $false
            break
        }
    }
    $proofCellCount = if ($null -ne $smoke.proofCellCount) {
        Get-SafeCountValue $smoke.proofCellCount
    } elseif ($null -ne $smoke.proofTexts) {
        @($smoke.proofTexts).Count
    } else {
        0
    }
    $proofStripReady = ($smoke.proofRootPresent -eq $true -and $proofCellCount -ge 6 -and $hasRequiredProofNames)
    $ok = if ($null -ne $smoke.ok) { [bool]$smoke.ok } else { $proofStripReady }
    $reachable = if ($null -ne $smoke.reachable) { [bool]$smoke.reachable } else { ($ok -or $proofStripReady) }
    $localhost = if ($null -ne $smoke.localhost) { [bool]$smoke.localhost } else { $proofStripReady }
    $screenshotCaptured = if ($null -ne $smoke.screenshotCaptured) { [bool]$smoke.screenshotCaptured } else { $proofStripReady }
    $targetContentVisible = if ($null -ne $smoke.targetContentVisible) { [bool]$smoke.targetContentVisible } else { $proofStripReady }
    $statusClass = Get-SafePacketString -Text ([string]$smoke.statusClass) -ProjectRoot $ProjectRoot
    if ([string]::IsNullOrWhiteSpace($statusClass)) {
        $statusClass = if ($proofStripReady) { 'proof_strip_visible' } else { 'unknown' }
    }
    $browserSurface = Get-SafePacketString -Text ([string]$smoke.browserSurface) -ProjectRoot $ProjectRoot
    if ([string]::IsNullOrWhiteSpace($browserSurface)) {
        $browserSurface = if ($proofStripReady) { 'iab' } else { 'unknown' }
    }
    $generatedAtText = [string]$smoke.generatedAt
    if ([string]::IsNullOrWhiteSpace($generatedAtText)) {
        $generatedAtText = [string]$smoke.checkedAt
    }
    $ageMinutes = $null
    $stale = $true
    try {
        $generatedAt = [datetimeoffset]::Parse($generatedAtText, [System.Globalization.CultureInfo]::InvariantCulture)
        $ageMinutes = [math]::Round(((Get-Date).ToUniversalTime() - $generatedAt.UtcDateTime).TotalMinutes, 3)
        $stale = ($ageMinutes -gt $staleAfterMinutes)
    } catch {
        $stale = $true
    }
    $decision = if ($rawSecretHits -gt 0) {
        'secret-leak-risk'
    } elseif ($stale) {
        'evidence_needed'
    } elseif (-not [string]::IsNullOrWhiteSpace([string]$smoke.decision)) {
        [string]$smoke.decision
    } elseif ($ok -and $reachable -and $localhost) {
        'ok'
    } else {
        'evidence_needed'
    }

    return [ordered]@{
        present = $true
        parsed = $true
        ok = ($ok -and $reachable -and $localhost -and -not $stale -and $rawSecretHits -eq 0)
        decision = $decision
        reachable = $reachable
        localhost = $localhost
        screenshotCaptured = $screenshotCaptured
        statusClass = $statusClass
        targetContentVisible = $targetContentVisible
        browserSurface = $browserSurface
        generatedAt = Get-SafePacketString -Text $generatedAtText -ProjectRoot $ProjectRoot
        ageMinutes = $ageMinutes
        staleAfterMinutes = $staleAfterMinutes
        stale = $stale
        path = $safePath
        nextAction = if ($ok -and $reachable -and $localhost -and -not $stale) { '' } else { 'rerun_browser_local_ui_smoke' }
        secretHits = (Get-SafeCountValue $smoke.secretHits) + $rawSecretHits
        rawSecretPatternHits = (Get-SafeCountValue $smoke.rawSecretPatternHits) + $rawSecretHits
    }
}

function New-CollectionPacket {
    param(
        [Parameter(Mandatory = $true)]$Entries,
        $SupabaseApplySummary,
        $ExternalApplySummary,
        $ComputerUseSummary,
        $BrowserUseSummary,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$Topic,
        [Parameter(Mandatory = $true)][string]$Decision,
        [Parameter(Mandatory = $true)][string]$GeneratedAt
    )

    $supabaseEntries = @($Entries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'targetService')) -eq 'supabase' -or
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -match 'supabase' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'supabase'
    })
    $supabaseDetail = @($supabaseEntries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'targetService')) -eq 'supabase'
    } | Select-Object -First 1)
    if ($supabaseDetail.Count -gt 0) {
        $supabaseDetail = $supabaseDetail[0]
    } else {
        $supabaseDetail = $null
    }

    $externalDetails = @($Entries | Where-Object {
        $null -ne (Get-EntryValue -Entry $_ -Name 'requiredSourceIsolation') -or
        $null -ne (Get-EntryValue -Entry $_ -Name 'requiredSidecars') -or
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -match 'external' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'external'
    })
    $externalDetailObjects = @($externalDetails | Where-Object {
        $null -ne (Get-EntryValue -Entry $_ -Name 'requiredSourceIsolation') -or
        $null -ne (Get-EntryValue -Entry $_ -Name 'requiredSidecars')
    })

    $supabaseRequiredEnv = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $supabaseDetail -Name 'requiredEnvNames') -ProjectRoot $ProjectRoot
    $supabaseRequiredTools = Get-SafeUniqueStrings -Values @(
        (Get-EntryValue -Entry $supabaseDetail -Name 'requiredMcpTools')
        if ($null -ne $SupabaseApplySummary) { $SupabaseApplySummary.requiredMcpTools }
    ) -ProjectRoot $ProjectRoot
    $supabaseRequiredResults = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $supabaseDetail -Name 'requiredResultNames') -ProjectRoot $ProjectRoot
    $supabaseArtifacts = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $supabaseDetail -Name 'artifactPaths') -ProjectRoot $ProjectRoot
    $supabaseDocs = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $supabaseDetail -Name 'docsRefs') -ProjectRoot $ProjectRoot
    $supabaseNextActions = Get-SafeUniqueStrings -Values @(
        if ($null -ne $SupabaseApplySummary) { $SupabaseApplySummary.nextActions }
        (Get-EntryValue -Entry $supabaseDetail -Name 'nextActions')
    ) -ProjectRoot $ProjectRoot
    $supabaseResultPath = [string](Get-EntryValue -Entry $supabaseDetail -Name 'resultPathRecommendation')
    if ([string]::IsNullOrWhiteSpace($supabaseResultPath) -and $null -ne $SupabaseApplySummary) {
        $supabaseResultPath = [string]$SupabaseApplySummary.resultPathRecommendation
    }
    $supabaseAdvisorPath = [string](Get-EntryValue -Entry $supabaseDetail -Name 'advisorResultPathRecommendation')
    if ([string]::IsNullOrWhiteSpace($supabaseAdvisorPath) -and $null -ne $SupabaseApplySummary) {
        $supabaseAdvisorPath = [string]$SupabaseApplySummary.advisorResultPathRecommendation
    }
    $supabaseImportTool = [string](Get-EntryValue -Entry $supabaseDetail -Name 'importTool')
    if ([string]::IsNullOrWhiteSpace($supabaseImportTool) -and $null -ne $SupabaseApplySummary) {
        $supabaseImportTool = [string]$SupabaseApplySummary.importTool
    }
    $supabaseMcpConfig = Get-SupabaseMcpConfigSummary -ProjectRoot $ProjectRoot

    $externalRoles = Get-SafeUniqueStrings -Values @(
        if ($null -ne $ExternalApplySummary) { $ExternalApplySummary.requiredRoles }
        $externalDetailObjects | ForEach-Object { Get-EntryValue -Entry $_ -Name 'targetRole' }
    ) -ProjectRoot $ProjectRoot
    $externalProducerFiles = Get-SafeUniqueStrings -Values @(
        if ($null -ne $ExternalApplySummary) { $ExternalApplySummary.requiredProducerEvidenceFiles }
    ) -ProjectRoot $ProjectRoot
    $externalSidecars = Get-SafeUniqueStrings -Values @(
        if ($null -ne $ExternalApplySummary) { $ExternalApplySummary.requiredPatchDropSidecars }
        $externalDetailObjects | ForEach-Object { Get-EntryValue -Entry $_ -Name 'requiredSidecars' }
    ) -ProjectRoot $ProjectRoot
    $externalNextActions = Get-SafeUniqueStrings -Values @(
        if ($null -ne $ExternalApplySummary) { $ExternalApplySummary.nextActions }
        $externalDetailObjects | ForEach-Object { Get-EntryValue -Entry $_ -Name 'nextActions' }
    ) -ProjectRoot $ProjectRoot
    $computerUseEntries = @($Entries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -eq 'computer_use' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'computer_use'
    })
    $computerUseNextActions = Get-SafeUniqueStrings -Values @(
        $computerUseEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'action' }
        $computerUseEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'nextActions' }
    ) -ProjectRoot $ProjectRoot
    $computerUseDecision = if ($computerUseNextActions.Count -gt 0) { 'evidence_needed' } else { 'ok' }
    $computerUseNextActionList = [System.Collections.Generic.List[string]]::new()
    foreach ($action in @($computerUseNextActions)) {
        $actionText = [string]$action
        if (-not [string]::IsNullOrWhiteSpace($actionText)) {
            $computerUseNextActionList.Add($actionText) | Out-Null
        }
    }

    $browserUseEntries = @($Entries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -eq 'browser_use' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'browser_use|browser_local_ui'
    })
    $browserUseNextActions = Get-SafeUniqueStrings -Values @(
        $browserUseEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'action' }
        $browserUseEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'nextActions' }
    ) -ProjectRoot $ProjectRoot
    $browserUseDecision = if ($browserUseNextActions.Count -gt 0) {
        'evidence_needed'
    } elseif ($null -ne $BrowserUseSummary) {
        [string]$BrowserUseSummary.decision
    } else {
        'ok'
    }
    $browserUseNextActionList = [System.Collections.Generic.List[string]]::new()
    foreach ($action in @($browserUseNextActions)) {
        $actionText = [string]$action
        if (-not [string]::IsNullOrWhiteSpace($actionText)) {
            $browserUseNextActionList.Add($actionText) | Out-Null
        }
    }

    $archiveEntries = @($Entries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'targetService')) -eq 'archive' -or
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -match 'archive' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'archive'
    })
    $archiveDetail = @($archiveEntries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'targetService')) -eq 'archive'
    } | Select-Object -First 1)
    if ($archiveDetail.Count -gt 0) {
        $archiveDetail = $archiveDetail[0]
    } else {
        $archiveDetail = $null
    }
    $archiveRequiredEnv = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $archiveDetail -Name 'requiredEnvNames') -ProjectRoot $ProjectRoot
    $archiveRequiredTools = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $archiveDetail -Name 'requiredMcpTools') -ProjectRoot $ProjectRoot
    $archiveNextActions = Get-SafeUniqueStrings -Values @(
        $archiveEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'nextActions' }
    ) -ProjectRoot $ProjectRoot
    $archiveIndexPath = [string](Get-EntryValue -Entry $archiveDetail -Name 'indexPathRecommendation')
    if ([string]::IsNullOrWhiteSpace($archiveIndexPath)) {
        $archiveIndexPath = 'BackupsXS/index.jsonl'
    }
    $archiveRootPath = [string](Get-EntryValue -Entry $archiveDetail -Name 'archiveRootRecommendation')
    if ([string]::IsNullOrWhiteSpace($archiveRootPath)) {
        $archiveRootPath = 'BackupsXS'
    }

    $sourceIsolation = $null
    if ($null -ne $ExternalApplySummary -and $null -ne $ExternalApplySummary.requiredSourceIsolation) {
        $sourceIsolation = $ExternalApplySummary.requiredSourceIsolation
    } elseif ($externalDetailObjects.Count -gt 0) {
        $sourceIsolation = Get-EntryValue -Entry $externalDetailObjects[0] -Name 'requiredSourceIsolation'
    }
    $safeSourceIsolation = [ordered]@{}
    if ($null -ne $sourceIsolation) {
        $safeSourceIsolation = [ordered]@{
            guard = Get-SafePacketString -Text ([string]$sourceIsolation.guard) -ProjectRoot $ProjectRoot
            sourceRootKind = Get-SafePacketString -Text ([string]$sourceIsolation.sourceRootKind) -ProjectRoot $ProjectRoot
            directCanonicalSourceEdit = [bool]$sourceIsolation.directCanonicalSourceEdit
            desktopFinalProof = Get-SafePacketString -Text ([string]$sourceIsolation.desktopFinalProof) -ProjectRoot $ProjectRoot
            rawSecretPatternHits = if ($null -ne $sourceIsolation.rawSecretPatternHits) { [int]$sourceIsolation.rawSecretPatternHits } else { 0 }
        }
    }

    return [ordered]@{
        schemaVersion = 'awx.goal_next_auto.collection_packet.v1'
        generatedAt = $GeneratedAt
        decision = $Decision
        topic = $Topic
        secretSafe = $true
        supabase = [ordered]@{
            decision = if ($null -ne $SupabaseApplySummary) { [string]$SupabaseApplySummary.decision } else { 'evidence_needed' }
            readOnly = if ($null -ne (Get-EntryValue -Entry $supabaseDetail -Name 'readOnly')) { [bool](Get-EntryValue -Entry $supabaseDetail -Name 'readOnly') } else { $true }
            mutationAllowed = if ($null -ne (Get-EntryValue -Entry $supabaseDetail -Name 'mutationAllowed')) { [bool](Get-EntryValue -Entry $supabaseDetail -Name 'mutationAllowed') } else { $false }
            requiredEnvNames = $supabaseRequiredEnv
            requiredMcpTools = $supabaseRequiredTools
            requiredResultNames = $supabaseRequiredResults
            artifactPaths = $supabaseArtifacts
            docsRefs = $supabaseDocs
            nextActions = $supabaseNextActions
            resultPathRecommendation = Get-SafePacketString -Text $supabaseResultPath -ProjectRoot $ProjectRoot
            advisorResultPathRecommendation = Get-SafePacketString -Text $supabaseAdvisorPath -ProjectRoot $ProjectRoot
            importTool = Get-SafePacketString -Text $supabaseImportTool -ProjectRoot $ProjectRoot
            applyCollectedEvidenceCommand = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $supabaseDetail -Name 'applyCollectedEvidenceCommand')) -ProjectRoot $ProjectRoot
            mcpConfig = $supabaseMcpConfig
        }
        external = [ordered]@{
            decision = if ($null -ne $ExternalApplySummary) { [string]$ExternalApplySummary.decision } else { 'evidence_needed' }
            requiredRoles = $externalRoles
            requiredProducerEvidenceFiles = $externalProducerFiles
            requiredSidecars = $externalSidecars
            requiredSourceIsolation = $safeSourceIsolation
            nextActions = $externalNextActions
            applyCollectedEvidenceCommand = if ($null -ne $ExternalApplySummary) { Get-SafePacketString -Text ([string]$ExternalApplySummary.applyCollectedEvidenceCommand) -ProjectRoot $ProjectRoot } else { '' }
        }
        archive = [ordered]@{
            decision = if ($archiveEntries.Count -gt 0) { 'evidence_needed' } else { 'ok' }
            readOnly = if ($null -ne (Get-EntryValue -Entry $archiveDetail -Name 'readOnly')) { [bool](Get-EntryValue -Entry $archiveDetail -Name 'readOnly') } else { $true }
            mutationAllowed = if ($null -ne (Get-EntryValue -Entry $archiveDetail -Name 'mutationAllowed')) { [bool](Get-EntryValue -Entry $archiveDetail -Name 'mutationAllowed') } else { $false }
            requiredEnvNames = $archiveRequiredEnv
            requiredMcpTools = $archiveRequiredTools
            indexPathRecommendation = Get-SafePacketString -Text $archiveIndexPath -ProjectRoot $ProjectRoot
            archiveRootRecommendation = Get-SafePacketString -Text $archiveRootPath -ProjectRoot $ProjectRoot
            nextActions = $archiveNextActions
            applyCollectedEvidenceCommand = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $archiveDetail -Name 'applyCollectedEvidenceCommand')) -ProjectRoot $ProjectRoot
        }
        computerUse = [ordered]@{
            decision = $computerUseDecision
            tool = 'mcp__node_repl.js'
            outputPath = 'var/codex-smoke/computer-use-smoke.json'
            storesRawAppNames = $false
            storesWindowTitles = $false
            appCount = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.appCount) { [int]$ComputerUseSummary.appCount } else { 0 }
            runningCount = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.runningCount) { [int]$ComputerUseSummary.runningCount } else { 0 }
            windowCount = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.windowCount) { [int]$ComputerUseSummary.windowCount } else { 0 }
            secretHits = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.secretHits) { [int]$ComputerUseSummary.secretHits } else { 0 }
            requiredEnvNames = @()
            nextActions = $computerUseNextActionList.ToArray()
        }
        browserUse = [ordered]@{
            decision = $browserUseDecision
            tool = 'browser.control-in-app-browser'
            outputPath = 'var/codex-smoke/browser-ui-smoke.json'
            storesRawUrl = $false
            storesScreenshotPath = $false
            reachable = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.reachable) { [bool]$BrowserUseSummary.reachable } else { $false }
            localhost = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.localhost) { [bool]$BrowserUseSummary.localhost } else { $false }
            screenshotCaptured = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.screenshotCaptured) { [bool]$BrowserUseSummary.screenshotCaptured } else { $false }
            statusClass = if ($null -ne $BrowserUseSummary) { Get-SafePacketString -Text ([string]$BrowserUseSummary.statusClass) -ProjectRoot $ProjectRoot } else { 'unknown' }
            targetContentVisible = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.targetContentVisible) { [bool]$BrowserUseSummary.targetContentVisible } else { $false }
            browserSurface = if ($null -ne $BrowserUseSummary) { Get-SafePacketString -Text ([string]$BrowserUseSummary.browserSurface) -ProjectRoot $ProjectRoot } else { 'unknown' }
            secretHits = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.secretHits) { [int]$BrowserUseSummary.secretHits } else { 0 }
            requiredEnvNames = @()
            nextActions = $browserUseNextActionList.ToArray()
        }
    }
}

function Get-SourceEditLeaseSummary {
    param([Parameter(Mandatory = $true)][string]$PatchDropDir)

    $locksDir = Join-Path $PatchDropDir 'source-edit-locks'
    $activeTopics = @()
    $corruptTopics = @()
    $expiredTopics = @()
    if (-not (Test-Path -LiteralPath $locksDir)) {
        return [ordered]@{
            sourceLeaseDirPresent = $false
            sourceLeaseActiveCount = 0
            sourceLeaseCorruptCount = 0
            sourceLeaseExpiredCount = 0
            sourceLeaseActiveTopics = @()
            sourceLeaseCorruptTopics = @()
            sourceLeaseExpiredTopics = @()
        }
    }

    $lockDirs = @(Get-ChildItem -LiteralPath $locksDir -Directory -Filter '*.lock' -ErrorAction SilentlyContinue | Sort-Object Name)
    foreach ($lockDir in $lockDirs) {
        $leasePath = Join-Path $lockDir.FullName 'lease.json'
        $fallbackTopic = $lockDir.Name
        if ($fallbackTopic.EndsWith('.lock', [StringComparison]::OrdinalIgnoreCase)) {
            $fallbackTopic = $fallbackTopic.Substring(0, $fallbackTopic.Length - 5)
        }
        if (-not (Test-Path -LiteralPath $leasePath)) {
            $corruptTopics += $fallbackTopic
            continue
        }

        try {
            $lease = Get-Content -LiteralPath $leasePath -Raw | ConvertFrom-Json
            $topic = [string]$lease.topic
            if ([string]::IsNullOrWhiteSpace($topic)) {
                $topic = $fallbackTopic
            }
            $expiresAt = [string]$lease.expiresAtUtc
            if (-not [string]::IsNullOrWhiteSpace($expiresAt)) {
                $expires = [DateTime]::Parse($expiresAt, $null, [Globalization.DateTimeStyles]::AssumeUniversal).ToUniversalTime()
                if ([DateTime]::UtcNow -gt $expires) {
                    $expiredTopics += $topic
                    continue
                }
            }
            $activeTopics += $topic
        } catch {
            $corruptTopics += $fallbackTopic
        }
    }

    return [ordered]@{
        sourceLeaseDirPresent = $true
        sourceLeaseActiveCount = $activeTopics.Count
        sourceLeaseCorruptCount = $corruptTopics.Count
        sourceLeaseExpiredCount = $expiredTopics.Count
        sourceLeaseActiveTopics = $activeTopics
        sourceLeaseCorruptTopics = $corruptTopics
        sourceLeaseExpiredTopics = $expiredTopics
    }
}

function Get-DesktopPreflight {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    [string]$gitRoot = ''
    $gitRootAvailable = $false
    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($null -ne $git) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $gitRootOutput = (& git -C $ProjectRoot rev-parse --show-toplevel 2>$null | Select-Object -First 1)
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($null -ne $gitRootOutput -and -not [string]::IsNullOrWhiteSpace([string]$gitRootOutput)) {
            $gitRootAvailable = $true
            $gitRoot = [string]$gitRootOutput
        }
    }

    $indexLockPresent = Test-Path -LiteralPath (Join-Path $ProjectRoot '.git\index.lock')
    $patchDropDir = Join-Path $ProjectRoot '__patch_drop__'
    $pendingPatches = @()
    if (Test-Path -LiteralPath $patchDropDir) {
        $pendingPatches = @(Get-ChildItem -LiteralPath $patchDropDir -File -Filter '*.patch' -ErrorAction SilentlyContinue | Sort-Object Name)
    }
    $pendingPatchNames = @($pendingPatches | ForEach-Object { $_.Name })
    $sourceLeaseSummary = if (Test-Path -LiteralPath $patchDropDir) {
        Get-SourceEditLeaseSummary -PatchDropDir $patchDropDir
    } else {
        [ordered]@{
            sourceLeaseDirPresent = $false
            sourceLeaseActiveCount = 0
            sourceLeaseCorruptCount = 0
            sourceLeaseExpiredCount = 0
            sourceLeaseActiveTopics = @()
            sourceLeaseCorruptTopics = @()
            sourceLeaseExpiredTopics = @()
        }
    }

    $failureClassification = ''
    if ($indexLockPresent) {
        $failureClassification = 'index-lock-conflict'
    } elseif ($pendingPatchNames.Count -gt 0) {
        $failureClassification = 'patch-drop-pending'
    } elseif ([int]$sourceLeaseSummary.sourceLeaseActiveCount -gt 0) {
        $failureClassification = 'source-edit-lease-held'
    } elseif ([int]$sourceLeaseSummary.sourceLeaseCorruptCount -gt 0) {
        $failureClassification = 'source-edit-lease-corrupt'
    }

    return [ordered]@{
        gitRootAvailable = $gitRootAvailable
        gitRoot = $gitRoot
        indexLockPresent = $indexLockPresent
        patchDropDirPresent = (Test-Path -LiteralPath $patchDropDir)
        patchDropPendingPatchCount = $pendingPatchNames.Count
        patchDropPendingPatchNames = $pendingPatchNames
        sourceLeaseDirPresent = $sourceLeaseSummary.sourceLeaseDirPresent
        sourceLeaseActiveCount = $sourceLeaseSummary.sourceLeaseActiveCount
        sourceLeaseCorruptCount = $sourceLeaseSummary.sourceLeaseCorruptCount
        sourceLeaseExpiredCount = $sourceLeaseSummary.sourceLeaseExpiredCount
        sourceLeaseActiveTopics = $sourceLeaseSummary.sourceLeaseActiveTopics
        sourceLeaseCorruptTopics = $sourceLeaseSummary.sourceLeaseCorruptTopics
        sourceLeaseExpiredTopics = $sourceLeaseSummary.sourceLeaseExpiredTopics
        failureClassification = $failureClassification
    }
}

function Invoke-ProcessCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogPath
    )
    if (-not (Test-Path -LiteralPath $FilePath)) {
        $missing = "evidence_needed: missing $FilePath"
        Set-Content -LiteralPath $LogPath -Value $missing -Encoding UTF8
        return [pscustomobject]@{
            Name = $Name
            ExitCode = 127
            SecretHits = 0
            Output = $missing
            LogPath = $LogPath
        }
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ([System.IO.Path]::GetExtension($FilePath).Equals('.ps1', [System.StringComparison]::OrdinalIgnoreCase)) {
            $lines = & powershell -NoProfile -ExecutionPolicy Bypass -File $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        } else {
            $lines = & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $exit = $LASTEXITCODE
    $raw = $lines -join "`n"
    $secretHits = Count-Pattern -Text $raw -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9._~+/-]+=*|(?i)\bjdbc:[A-Za-z0-9_+.-]*://[^\s''"]+'
    $safe = Redact-SensitiveText $raw
    Set-Content -LiteralPath $LogPath -Value $safe -Encoding UTF8

    return [pscustomobject]@{
        Name = $Name
        ExitCode = $exit
        SecretHits = $secretHits
        Output = $safe
        LogPath = $LogPath
    }
}

function Invoke-ProcessCaptureWithInput {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [AllowEmptyString()][string]$InputText,
        [Parameter(Mandatory = $true)][string]$LogPath
    )
    if (-not (Test-Path -LiteralPath $FilePath)) {
        $missing = "evidence_needed: missing $FilePath"
        Set-Content -LiteralPath $LogPath -Value $missing -Encoding UTF8
        return [pscustomobject]@{
            Name = $Name
            ExitCode = 127
            SecretHits = 0
            Output = $missing
            LogPath = $LogPath
        }
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = $InputText | & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $exit = $LASTEXITCODE
    $raw = $lines -join "`n"
    $secretHits = Count-Pattern -Text $raw -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9._~+/-]+=*|(?i)\bjdbc:[A-Za-z0-9_+.-]*://[^\s''"]+'
    $safe = Redact-SensitiveText $raw
    Set-Content -LiteralPath $LogPath -Value $safe -Encoding UTF8

    return [pscustomobject]@{
        Name = $Name
        ExitCode = $exit
        SecretHits = $secretHits
        Output = $safe
        LogPath = $LogPath
    }
}

function Invoke-PythonCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogPath
    )
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $python) {
        $missing = 'evidence_needed: python missing'
        Set-Content -LiteralPath $LogPath -Value $missing -Encoding UTF8
        return [pscustomobject]@{
            Name = $Name
            ExitCode = 127
            SecretHits = 0
            Output = $missing
            LogPath = $LogPath
        }
    }
    $pythonArgs = @($ScriptPath) + $Arguments
    return Invoke-ProcessCapture -Name $Name -FilePath $python.Source -Arguments $pythonArgs -LogPath $LogPath
}

function Invoke-PythonStdinCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [AllowEmptyString()][string]$InputText,
        [Parameter(Mandatory = $true)][string]$LogPath
    )
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $python) {
        $missing = 'evidence_needed: python missing'
        Set-Content -LiteralPath $LogPath -Value $missing -Encoding UTF8
        return [pscustomobject]@{
            Name = $Name
            ExitCode = 127
            SecretHits = 0
            Output = $missing
            LogPath = $LogPath
        }
    }
    $pythonArgs = @($ScriptPath) + $Arguments
    return Invoke-ProcessCaptureWithInput -Name $Name -FilePath $python.Source -Arguments $pythonArgs -InputText $InputText -LogPath $LogPath
}

function Get-LatestGoalNextDependency {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $candidatePaths = [System.Collections.Generic.List[string]]::new()
    $localScriptRoot = if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        $PSScriptRoot
    } else {
        Split-Path -Parent $PSCommandPath
    }
    $rootScriptsDir = Join-Path $ProjectRoot 'scripts'
    foreach ($scriptDir in @($localScriptRoot, $rootScriptsDir)) {
        if ([string]::IsNullOrWhiteSpace($scriptDir)) {
            continue
        }
        foreach ($name in @(
            'goal_next.ps1',
            'goal_next_auto.ps1',
            'smoke_supabase_readonly_snapshot.ps1',
            'supabase_apply_collected_evidence.ps1',
            'external_apply_collected_evidence.ps1',
            'source_health_scorecard.py',
            'awx_mcp_toolbox.py',
            'awx_mcp_completion_audit.py'
        )) {
            $candidatePaths.Add((Join-Path $scriptDir $name)) | Out-Null
        }
    }
    $candidatePaths.Add((Join-Path (Join-Path $ProjectRoot 'var\codex-smoke') 'computer-use-smoke.json')) | Out-Null
    $candidatePaths.Add((Join-Path (Join-Path $ProjectRoot 'var\codex-smoke') 'browser-ui-smoke.json')) | Out-Null

    $seenPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $latest = $null
    foreach ($path in $candidatePaths) {
        if (-not $seenPaths.Add($path)) {
            continue
        }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            continue
        }
        $item = Get-Item -LiteralPath $path
        if ($null -eq $latest -or $item.LastWriteTimeUtc -gt $latest.LastWriteTimeUtc) {
            $latest = $item
        }
    }

    if ($null -eq $latest) {
        return $null
    }

    return [pscustomobject]@{
        Name = $latest.Name
        LastWriteTimeUtc = $latest.LastWriteTimeUtc
        LastWriteTimeUtcText = $latest.LastWriteTimeUtc.ToString('o')
    }
}

if ($Status -or $EnsureFresh) {
    $Root = Resolve-RepoRoot $Root
    $defaultSmokeRoot = Resolve-RepoPath -ProjectRoot $Root -PathText 'var\codex-smoke'
    New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
    $latestPointerPath = Join-Path $defaultSmokeRoot 'goal-next-auto.latest.json'
    $statusPath = Join-Path $defaultSmokeRoot 'goal-next-auto.status.json'
    $nowUtc = (Get-Date).ToUniversalTime()
    $generatedAtText = $nowUtc.ToString('o')
    $statusDecision = 'evidence_needed'
    $failureClassification = 'missing-latest'
    $latestDecision = 'evidence_needed'
    $latestGeneratedAtText = ''
    $latestGeneratedAtAgeMinutes = $null
    $latestStaleAfterMinutes = 60
    $staleLatest = $true
    $firstAction = 'evidence_needed'
    $firstActionSource = 'evidence_needed'
    $statusNextActionCount = 0
    $statusNextActionSources = @()
    $statusTopActions = @()
    $secretHits = 0
    $computerUseOk = $false
    $computerUseReachable = $false
    $computerUseAppCount = 0
    $computerUseDecision = 'evidence_needed'
    $browserUseOk = $false
    $browserUseReachable = $false
    $browserUseLocalhost = $false
    $browserUseDecision = 'evidence_needed'
    $latestDependencyName = ''
    $latestDependencyWriteTime = ''
    $currentComputerUseSummary = Get-ComputerUseSmokeSummary -ProjectRoot $Root
    $computerUseSummary = $currentComputerUseSummary
    $currentBrowserUseSummary = Get-BrowserUseSmokeSummary -ProjectRoot $Root
    $browserUseSummary = $currentBrowserUseSummary
    $supabaseSmokeSummary = New-DefaultSupabaseSmokeSummary -ProjectRoot $Root
    $supabaseMcpConfig = Get-SupabaseMcpConfigSummary -ProjectRoot $Root
    $latest = Read-JsonObjectFromFile -Path $latestPointerPath

    if ($null -ne $latest) {
        $latestSummary = $null
        if (-not [string]::IsNullOrWhiteSpace([string]$latest.summaryPath)) {
            $latestSummary = Read-JsonObjectFromFile -Path ([string]$latest.summaryPath)
        }
        $latestDecision = if ([string]::IsNullOrWhiteSpace([string]$latest.decision)) { 'evidence_needed' } else { [string]$latest.decision }
        $statusDecision = $latestDecision
        $failureClassification = if ($latestDecision -eq 'ok') { '' } else { $latestDecision }
        $firstAction = if ([string]::IsNullOrWhiteSpace([string]$latest.firstAction)) { 'evidence_needed' } else { [string]$latest.firstAction }
        $firstActionSource = if ([string]::IsNullOrWhiteSpace([string]$latest.firstActionSource)) { 'evidence_needed' } else { [string]$latest.firstActionSource }
        if ($null -ne $latestSummary) {
            if ($null -ne $latestSummary.nextActionCount) { $statusNextActionCount = [int]$latestSummary.nextActionCount }
            if ($null -ne $latestSummary.nextActionSources) { $statusNextActionSources = @($latestSummary.nextActionSources) }
            if ($null -ne $latestSummary.topActions) { $statusTopActions = @($latestSummary.topActions) }
        } elseif ($null -ne $latest.nextActionEntryCount) {
            $statusNextActionCount = [int]$latest.nextActionEntryCount
        }
        if ($null -ne $latest.computerUseOk) { $computerUseOk = [bool]$latest.computerUseOk }
        if ($null -ne $latest.computerUseReachable) { $computerUseReachable = [bool]$latest.computerUseReachable }
        if ($null -ne $latest.computerUseAppCount) { $computerUseAppCount = [int]$latest.computerUseAppCount }
        if (-not [string]::IsNullOrWhiteSpace([string]$latest.computerUseDecision)) { $computerUseDecision = [string]$latest.computerUseDecision }
        if ($null -ne $latest.computerUse) { $computerUseSummary = $latest.computerUse }
        if ($null -ne $latest.browserUseOk) { $browserUseOk = [bool]$latest.browserUseOk }
        if ($null -ne $latest.browserUseReachable) { $browserUseReachable = [bool]$latest.browserUseReachable }
        if ($null -ne $latest.browserUseLocalhost) { $browserUseLocalhost = [bool]$latest.browserUseLocalhost }
        if (-not [string]::IsNullOrWhiteSpace([string]$latest.browserUseDecision)) { $browserUseDecision = [string]$latest.browserUseDecision }
        if ($null -ne $latest.browserUse) { $browserUseSummary = $latest.browserUse }
        if ($null -ne $latest.supabaseMcpConfig) { $supabaseMcpConfig = $latest.supabaseMcpConfig }
        if ($null -ne $latest.supabaseSmoke) {
            $supabaseSmokeSummary = $latest.supabaseSmoke
        } elseif ($null -ne $latestSummary) {
            if ($null -ne $latestSummary -and $null -ne $latestSummary.supabaseSmoke) {
                $supabaseSmokeSummary = $latestSummary.supabaseSmoke
            }
        }
        if ($null -ne $currentComputerUseSummary) {
            $computerUseOk = [bool]$currentComputerUseSummary.ok
            $computerUseReachable = [bool]$currentComputerUseSummary.reachable
            $computerUseAppCount = [int]$currentComputerUseSummary.appCount
            $computerUseDecision = [string]$currentComputerUseSummary.decision
            $computerUseSummary = $currentComputerUseSummary
            $secretHits += [int]$currentComputerUseSummary.secretHits
        }
        if ($null -ne $currentBrowserUseSummary) {
            $browserUseOk = [bool]$currentBrowserUseSummary.ok
            $browserUseReachable = [bool]$currentBrowserUseSummary.reachable
            $browserUseLocalhost = [bool]$currentBrowserUseSummary.localhost
            $browserUseDecision = [string]$currentBrowserUseSummary.decision
            $browserUseSummary = $currentBrowserUseSummary
            $secretHits += [int]$currentBrowserUseSummary.secretHits
        }
        $latestGeneratedAtText = [string]$latest.generatedAt
        [int]$parsedThreshold = 0
        if ([int]::TryParse([string]$latest.latestStaleAfterMinutes, [ref]$parsedThreshold) -and $parsedThreshold -gt 0) {
            $latestStaleAfterMinutes = $parsedThreshold
        }
        [int]$parsedSecretHits = 0
        if ([int]::TryParse([string]$latest.secretHits, [ref]$parsedSecretHits) -and $parsedSecretHits -gt 0) {
            $secretHits = $parsedSecretHits
        }
        try {
            $latestGeneratedAt = [datetimeoffset]::Parse($latestGeneratedAtText, [System.Globalization.CultureInfo]::InvariantCulture)
            $latestGeneratedAtAgeMinutes = [math]::Round(($nowUtc - $latestGeneratedAt.UtcDateTime).TotalMinutes, 3)
            $staleLatest = ($latestGeneratedAtAgeMinutes -gt $latestStaleAfterMinutes)
            if ($staleLatest) {
                $failureClassification = 'stale-latest'
            }
            $latestDependency = Get-LatestGoalNextDependency -ProjectRoot $Root
            if ($null -ne $latestDependency) {
                $latestDependencyName = [string]$latestDependency.Name
                $latestDependencyWriteTime = [string]$latestDependency.LastWriteTimeUtcText
                if ($latestDependency.LastWriteTimeUtc -gt $latestGeneratedAt.UtcDateTime) {
                    $staleLatest = $true
                    if ($failureClassification -ne 'stale-latest') {
                        if ([string]$latestDependency.Name -eq 'computer-use-smoke.json') {
                            $failureClassification = 'computer-use-smoke-newer-than-latest'
                        } elseif ([string]$latestDependency.Name -eq 'browser-ui-smoke.json') {
                            $failureClassification = 'browser-ui-smoke-newer-than-latest'
                        } else {
                            $failureClassification = 'script-newer-than-latest'
                        }
                    }
                }
            }
            if ($failureClassification -ne 'stale-latest' -and $null -ne $currentComputerUseSummary) {
                if ([int]$currentComputerUseSummary.secretHits -gt 0) {
                    $staleLatest = $true
                    $failureClassification = 'secret-leak-risk'
                    $statusDecision = 'secret-leak-risk'
                } elseif ([bool]$currentComputerUseSummary.stale) {
                    $staleLatest = $true
                    $failureClassification = 'computer-use-smoke-stale'
                    if ($statusDecision -eq 'ok') {
                        $statusDecision = 'evidence_needed'
                    }
                }
            }
            if ($failureClassification -ne 'stale-latest' -and $null -ne $currentBrowserUseSummary) {
                if ([int]$currentBrowserUseSummary.secretHits -gt 0) {
                    $staleLatest = $true
                    $failureClassification = 'secret-leak-risk'
                    $statusDecision = 'secret-leak-risk'
                } elseif ([bool]$currentBrowserUseSummary.stale) {
                    $staleLatest = $true
                    $failureClassification = 'browser-ui-smoke-stale'
                    if ($statusDecision -eq 'ok') {
                        $statusDecision = 'evidence_needed'
                    }
                }
            }
        } catch {
            $staleLatest = $true
            $failureClassification = 'invalid-latest-generated-at'
        }
    }

    Write-JsonFile -Path $statusPath -Value ([ordered]@{
        schemaVersion = 'awx.goal_next_auto.status.v1'
        generatedAt = $generatedAtText
        root = $Root
        latestPath = $latestPointerPath
        statusPath = $statusPath
        latestDecision = $latestDecision
        statusDecision = $statusDecision
        failureClassification = $failureClassification
        latestGeneratedAt = $latestGeneratedAtText
        latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
        latestStaleAfterMinutes = $latestStaleAfterMinutes
        staleLatest = $staleLatest
        summaryPath = if ($null -ne $latest) { [string]$latest.summaryPath } else { '' }
        nextActionsPath = if ($null -ne $latest) { [string]$latest.nextActionsPath } else { '' }
        commandPacketPath = if ($null -ne $latest) { [string]$latest.commandPacketPath } else { '' }
        commandPacketMarkdownPath = if ($null -ne $latest) { [string]$latest.commandPacketMarkdownPath } else { '' }
        collectionPacketPath = if ($null -ne $latest) { [string]$latest.collectionPacketPath } else { '' }
        collectionPacketMarkdownPath = if ($null -ne $latest) { [string]$latest.collectionPacketMarkdownPath } else { '' }
        digestPath = if ($null -ne $latest) { [string]$latest.digestPath } else { '' }
        digestMarkdownPath = if ($null -ne $latest) { [string]$latest.digestMarkdownPath } else { '' }
        nextActionEntryCount = if ($null -ne $latest) { [int]$latest.nextActionEntryCount } else { 0 }
        nextActionCount = $statusNextActionCount
        nextActionSources = @($statusNextActionSources)
        topActions = @($statusTopActions)
        firstAction = $firstAction
        firstActionSource = $firstActionSource
        computerUseOk = $computerUseOk
        computerUseReachable = $computerUseReachable
        computerUseAppCount = $computerUseAppCount
        computerUseDecision = $computerUseDecision
        computerUse = $computerUseSummary
        browserUseOk = $browserUseOk
        browserUseReachable = $browserUseReachable
        browserUseLocalhost = $browserUseLocalhost
        browserUseDecision = $browserUseDecision
        browserUse = $browserUseSummary
        supabaseSmoke = $supabaseSmokeSummary
        supabaseMcpConfig = $supabaseMcpConfig
        supabaseApply = if ($null -ne $latest -and $null -ne $latest.supabaseApply) { $latest.supabaseApply } else { [ordered]@{} }
        externalApply = if ($null -ne $latest -and $null -ne $latest.externalApply) { $latest.externalApply } else { [ordered]@{} }
        latestDependencyName = $latestDependencyName
        latestDependencyWriteTime = $latestDependencyWriteTime
        secretHits = $secretHits
    })

    $ageText = if ($null -ne $latestGeneratedAtAgeMinutes) { [string]$latestGeneratedAtAgeMinutes } else { 'evidence_needed' }
    $failureText = if ([string]::IsNullOrWhiteSpace($failureClassification)) { 'none' } else { $failureClassification }
    Write-Host "[AWX][goal-next-status] statusDecision=$statusDecision staleLatest=$($staleLatest.ToString().ToLowerInvariant()) latestGeneratedAtAgeMinutes=$ageText firstAction=$firstAction failureClassification=$failureText secretHits=$secretHits status=$statusPath"

    if ($EnsureFresh) {
        if ($secretHits -gt 0 -or $latestDecision -eq 'secret-leak-risk') {
            Write-Host "[AWX][goal-next-ensure] action=stop reason=secret-leak-risk status=$statusPath"
            exit 4
        }
        if (-not $staleLatest) {
            Write-Host "[AWX][goal-next-ensure] action=status reason=fresh-latest statusDecision=$statusDecision status=$statusPath"
            if ($statusDecision -ne 'ok') {
                exit 2
            }
            exit 0
        }
        Write-Host "[AWX][goal-next-ensure] action=refresh reason=$failureText outputDir=$OutputDir"
    } else {
    if ($secretHits -gt 0 -or $latestDecision -eq 'secret-leak-risk') {
        exit 4
    }
    if ($staleLatest -or $statusDecision -ne 'ok') {
        exit 2
    }
    exit 0
    }
}

$Root = Resolve-RepoRoot $Root
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = 'var\codex-smoke\goal-next-auto'
}
$defaultSmokeRoot = Resolve-RepoPath -ProjectRoot $Root -PathText 'var\codex-smoke'
$OutputDir = Resolve-RepoPath -ProjectRoot $Root -PathText $OutputDir
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
$breadcrumbTimelinePath = Join-Path $defaultSmokeRoot 'goal-next-auto.breadcrumbs.jsonl'
$breadcrumbFusionPath = Join-Path $OutputDir 'goal-next-auto.breadcrumb-fusion.json'
$previousBreadcrumb = Read-LastJsonLine -Path $breadcrumbTimelinePath
$previousBreadcrumbSummary = New-PreviousBreadcrumbSummary -Previous $previousBreadcrumb
$preflight = Get-DesktopPreflight -ProjectRoot $Root

$scriptsDir = Join-Path $Root 'scripts'
$supabaseSmokeDir = Join-Path $OutputDir 'supabase-readonly-smoke'
$supabaseApplyDir = Join-Path $OutputDir 'supabase-apply'
$externalApplyDir = Join-Path $OutputDir 'external-apply'
$desktopControlLoopPath = Join-Path $OutputDir 'desktop-control-loop.result.json'

$supabaseSmoke = Invoke-ProcessCapture `
    -Name 'supabase_smoke' `
    -FilePath (Join-Path $scriptsDir 'smoke_supabase_readonly_snapshot.ps1') `
    -Arguments @('-Root', $Root, '-OutputDir', $supabaseSmokeDir, '-RequireProjectScope') `
    -LogPath (Join-Path $OutputDir 'supabase-smoke.log')
$supabaseSmokeSummaryPath = Join-Path $supabaseSmokeDir 'supabase-readonly-snapshot.summary.json'
$supabaseSmokeSummary = Get-SupabaseSmokeSummary -SummaryPath $supabaseSmokeSummaryPath -ProjectRoot $Root
$defaultSupabaseSmokeDir = Join-Path $defaultSmokeRoot 'supabase-readonly-snapshot'
if (Test-Path -LiteralPath $supabaseSmokeDir -PathType Container) {
    New-Item -ItemType Directory -Force -Path $defaultSupabaseSmokeDir | Out-Null
    Get-ChildItem -LiteralPath $supabaseSmokeDir -File -ErrorAction SilentlyContinue | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $defaultSupabaseSmokeDir $_.Name) -Force
    }
}

$supabaseApply = Invoke-ProcessCapture `
    -Name 'supabase_apply' `
    -FilePath (Join-Path $scriptsDir 'supabase_apply_collected_evidence.ps1') `
    -Arguments @('-Root', $Root, '-OutputDir', $supabaseApplyDir) `
    -LogPath (Join-Path $OutputDir 'supabase-apply.log')
$supabaseApplySummaryPath = Join-Path $supabaseApplyDir 'supabase-apply-collected.summary.json'
$supabaseApplySummary = Read-JsonObjectFromFile -Path $supabaseApplySummaryPath

$externalApply = Invoke-ProcessCapture `
    -Name 'external_apply' `
    -FilePath (Join-Path $scriptsDir 'external_apply_collected_evidence.ps1') `
    -Arguments @('-Root', $Root, '-OutputDir', $externalApplyDir, '-Topic', $Topic) `
    -LogPath (Join-Path $OutputDir 'external-apply.log')
$externalApplySummaryPath = Join-Path $externalApplyDir 'external-apply-collected.summary.json'
$externalApplySummary = Read-JsonObjectFromFile -Path $externalApplySummaryPath

$desktopControlLoopPayload = ([ordered]@{
    nodeRole = 'desktop'
    root = $Root
    canonical_root = $Root
    patchdrop_root = '__patch_drop__'
    topic = $Topic
    role_pathspec = [ordered]@{
        macmini = @('scripts/goal_next_auto.ps1')
        notebook = @('scripts/goal_next_auto_tests.ps1')
    }
    require_producer_bundles = $true
} | ConvertTo-Json -Depth 20 -Compress)

$desktopControlLoop = Invoke-PythonStdinCapture `
    -Name 'desktop_control_loop' `
    -ScriptPath (Join-Path $scriptsDir 'awx_mcp_toolbox.py') `
    -Arguments @('desktop_control_loop') `
    -InputText $desktopControlLoopPayload `
    -LogPath (Join-Path $OutputDir 'desktop-control-loop.log')

Set-Content -LiteralPath $desktopControlLoopPath -Value $desktopControlLoop.Output -Encoding UTF8
$desktopControlLoopJson = Read-JsonObjectFromText $desktopControlLoop.Output
$desktopControlLoopNextActionCount = 0
if ($null -ne $desktopControlLoopJson -and $null -ne $desktopControlLoopJson.nextActions) {
    $desktopControlLoopNextActionCount = @($desktopControlLoopJson.nextActions).Count
}

$completionAuditPreflightOutputPath = Join-Path $OutputDir 'awx-mcp-completion-audit.preflight.result.json'
$completionAuditPreflight = Invoke-PythonCapture `
    -Name 'completion_audit_preflight' `
    -ScriptPath (Join-Path $scriptsDir 'awx_mcp_completion_audit.py') `
    -Arguments @('--root', $Root, '--output', $completionAuditPreflightOutputPath) `
    -LogPath (Join-Path $OutputDir 'awx-mcp-completion-audit.preflight.log')
if (Test-Path -LiteralPath $completionAuditPreflightOutputPath) {
    New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
    Copy-Item -LiteralPath $completionAuditPreflightOutputPath -Destination (Join-Path $defaultSmokeRoot 'awx-mcp-completion-audit-current.json') -Force
}

$sourceHealth = Invoke-PythonCapture `
    -Name 'source_health' `
    -ScriptPath (Join-Path $scriptsDir 'source_health_scorecard.py') `
    -Arguments @('--root', $Root, '--output', (Join-Path $OutputDir 'source-health-scorecard.json')) `
    -LogPath (Join-Path $OutputDir 'source-health-scorecard.log')
$sourceHealthJson = Read-JsonObjectFromFile -Path (Join-Path $OutputDir 'source-health-scorecard.json')

$runs = @($supabaseSmoke, $supabaseApply, $externalApply, $desktopControlLoop, $sourceHealth)
$secretHits = 0
foreach ($run in $runs) {
    $secretHits += [int]$run.SecretHits
}
$secretHits += [int]$completionAuditPreflight.SecretHits
$secretHits += [int]$supabaseSmokeSummary.secretHits

$envProjectRefPresent = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('SUPABASE_PROJECT_REF'))
$envAccessTokenPresent = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('SUPABASE_ACCESS_TOKEN'))
$computerUseSummary = Get-ComputerUseSmokeSummary -ProjectRoot $Root
$secretHits += [int]$computerUseSummary.secretHits
$browserUseSummary = Get-BrowserUseSmokeSummary -ProjectRoot $Root
$secretHits += [int]$browserUseSummary.secretHits

$decision = 'ok'
if ($secretHits -gt 0 -or $runs.ExitCode -contains 4 -or $completionAuditPreflight.ExitCode -eq 4) {
    $decision = 'secret-leak-risk'
} elseif (-not [string]::IsNullOrWhiteSpace([string]$preflight.failureClassification)) {
    $decision = 'evidence_needed'
} elseif (($supabaseApply.ExitCode -ne 0) -or ($externalApply.ExitCode -ne 0) -or ($desktopControlLoop.ExitCode -ne 0) -or ($sourceHealth.ExitCode -ne 0)) {
    $decision = 'evidence_needed'
}

$nextActionsPath = Join-Path $OutputDir 'goal-next-auto.next-actions.json'
$nextActionEntries = [System.Collections.Generic.List[object]]::new()
if (-not [string]::IsNullOrWhiteSpace([string]$preflight.failureClassification)) {
    $nextActionEntries.Add([ordered]@{
        source = 'preflight'
        action = 'resolve_' + [string]$preflight.failureClassification
        decision = 'evidence_needed'
        failureClassification = [string]$preflight.failureClassification
    }) | Out-Null
}
if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.nextActions) {
    Add-StringNextActionEntries -Entries $nextActionEntries -Source 'supabase_apply' -Actions $supabaseApplySummary.nextActions
}
if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.nextActions) {
    Add-StringNextActionEntries -Entries $nextActionEntries -Source 'external_apply' -Actions $externalApplySummary.nextActions
}
if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.nextActionDetails) {
    Add-ObjectNextActionEntries -Entries $nextActionEntries -Source 'source_health_scorecard' -Actions $sourceHealthJson.nextActionDetails
}
if ($null -ne $desktopControlLoopJson -and $null -ne $desktopControlLoopJson.nextActions) {
    Add-ObjectNextActionEntries -Entries $nextActionEntries -Source 'desktop_control_loop' -Actions $desktopControlLoopJson.nextActions
}
if ([string]$computerUseSummary.decision -ne 'ok' -and -not [string]::IsNullOrWhiteSpace([string]$computerUseSummary.nextAction)) {
    $nextActionEntries.Add([ordered]@{
        source = 'computer_use'
        action = [string]$computerUseSummary.nextAction
        decision = [string]$computerUseSummary.decision
    }) | Out-Null
}
if ([string]$browserUseSummary.decision -ne 'ok' -and -not [string]::IsNullOrWhiteSpace([string]$browserUseSummary.nextAction)) {
    $nextActionEntries.Add([ordered]@{
        source = 'browser_use'
        action = [string]$browserUseSummary.nextAction
        decision = [string]$browserUseSummary.decision
    }) | Out-Null
}
$nextActionSources = @($nextActionEntries | ForEach-Object { $_.source } | Select-Object -Unique)
$generatedAtUtc = (Get-Date).ToUniversalTime()
$generatedAtText = $generatedAtUtc.ToString('o')
$latestGeneratedAtAgeMinutes = 0.0
$latestStaleAfterMinutes = 60
$latestExpiresAt = $generatedAtUtc.AddMinutes($latestStaleAfterMinutes).ToString('o')
$staleLatest = $false
Write-JsonFile -Path $nextActionsPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.next_actions.v1'
    generatedAt = $generatedAtText
    decision = $decision
    topic = $Topic
    root = $Root
    entryCount = $nextActionEntries.Count
    sources = $nextActionSources
    actions = @($nextActionEntries)
})

$summaryPath = Join-Path $OutputDir 'goal-next-auto.summary.json'
$digestPath = Join-Path $OutputDir 'goal-next-auto.digest.json'
$digestMarkdownPath = Join-Path $OutputDir 'goal-next-auto.digest.md'
$commandPacketPath = Join-Path $OutputDir 'goal-next-auto.command-packet.json'
$commandPacketMarkdownPath = Join-Path $OutputDir 'goal-next-auto.command-packet.md'
$collectionPacketPath = Join-Path $OutputDir 'goal-next-auto.collection-packet.json'
$collectionPacketMarkdownPath = Join-Path $OutputDir 'goal-next-auto.collection-packet.md'
$commandPacket = New-CommandPacket -Entries $nextActionEntries -SupabaseApplySummary $supabaseApplySummary -ComputerUseSummary $computerUseSummary -BrowserUseSummary $browserUseSummary -ProjectRoot $Root -Topic $Topic -Decision $decision -GeneratedAt $generatedAtText
Write-JsonFile -Path $commandPacketPath -Value $commandPacket
$commandPacketMarkdownLines = [System.Collections.Generic.List[string]]::new()
$commandPacketMarkdownLines.Add('# goal-next-auto command packet') | Out-Null
$commandPacketMarkdownLines.Add('') | Out-Null
$commandPacketMarkdownLines.Add("- decision=$decision") | Out-Null
$commandPacketMarkdownLines.Add("- topic=$Topic") | Out-Null
$commandPacketMarkdownLines.Add("- commandCount=$($commandPacket.commandCount)") | Out-Null
$commandPacketMarkdownLines.Add("- computerUse.decision=$($commandPacket.computerUse.decision) reachable=$($commandPacket.computerUse.reachable) stale=$($commandPacket.computerUse.stale) appCount=$($commandPacket.computerUse.appCount) runningCount=$($commandPacket.computerUse.runningCount) windowCount=$($commandPacket.computerUse.windowCount) storesRawAppNames=$($commandPacket.computerUse.storesRawAppNames) storesWindowTitles=$($commandPacket.computerUse.storesWindowTitles) secretHits=$($commandPacket.computerUse.secretHits) outputPath=$($commandPacket.computerUse.outputPath)") | Out-Null
$commandPacketMarkdownLines.Add("- browserUse.decision=$($commandPacket.browserUse.decision) reachable=$($commandPacket.browserUse.reachable) localhost=$($commandPacket.browserUse.localhost) stale=$($commandPacket.browserUse.stale) screenshotCaptured=$($commandPacket.browserUse.screenshotCaptured) storesRawUrl=$($commandPacket.browserUse.storesRawUrl) storesScreenshotPath=$($commandPacket.browserUse.storesScreenshotPath) secretHits=$($commandPacket.browserUse.secretHits) outputPath=$($commandPacket.browserUse.outputPath)") | Out-Null
foreach ($cmd in @($commandPacket.commands)) {
    $envNames = if ($null -ne $cmd.requiredEnvNames -and @($cmd.requiredEnvNames).Count -gt 0) { @($cmd.requiredEnvNames) -join ',' } else { 'none' }
    $resultPath = if ($null -ne $cmd.resultPathRecommendation -and -not [string]::IsNullOrWhiteSpace([string]$cmd.resultPathRecommendation)) { [string]$cmd.resultPathRecommendation } else { 'none' }
    $advisorPath = if ($null -ne $cmd.advisorResultPathRecommendation -and -not [string]::IsNullOrWhiteSpace([string]$cmd.advisorResultPathRecommendation)) { [string]$cmd.advisorResultPathRecommendation } else { 'none' }
    $indexPath = if ($null -ne $cmd.indexPathRecommendation -and -not [string]::IsNullOrWhiteSpace([string]$cmd.indexPathRecommendation)) { [string]$cmd.indexPathRecommendation } else { 'none' }
    $mcpTools = if ($null -ne $cmd.requiredMcpTools -and @($cmd.requiredMcpTools).Count -gt 0) { @($cmd.requiredMcpTools) -join ',' } else { 'none' }
    $commandText = Redact-SensitiveText ([string]$cmd.command)
    $optionalFields = [System.Collections.Generic.List[string]]::new()
    foreach ($field in @('tool', 'outputPath', 'storesRawAppNames', 'storesRawUrl', 'storesScreenshotPath')) {
        if ($null -ne $cmd.$field -and -not [string]::IsNullOrWhiteSpace([string]$cmd.$field)) {
            $optionalFields.Add("$field=$($cmd.$field)") | Out-Null
        }
    }
    $optionalSuffix = if ($optionalFields.Count -gt 0) { ' ' + (($optionalFields | ForEach-Object { Redact-SensitiveText ([string]$_) }) -join ' ') } else { '' }
    $commandPacketMarkdownLines.Add("- lane=$($cmd.lane) role=$($cmd.role) source=$($cmd.source) action=$($cmd.action) env=$envNames results=$resultPath advisors=$advisorPath index=$indexPath mcpTools=$mcpTools command=$commandText$optionalSuffix") | Out-Null
}
Write-TextFile -Path $commandPacketMarkdownPath -Value (($commandPacketMarkdownLines | ForEach-Object { Redact-SensitiveText ([string]$_) }) -join "`n")
$collectionPacket = New-CollectionPacket -Entries $nextActionEntries -SupabaseApplySummary $supabaseApplySummary -ExternalApplySummary $externalApplySummary -ComputerUseSummary $computerUseSummary -BrowserUseSummary $browserUseSummary -ProjectRoot $Root -Topic $Topic -Decision $decision -GeneratedAt $generatedAtText
Write-JsonFile -Path $collectionPacketPath -Value $collectionPacket
$collectionPacketMarkdownLines = [System.Collections.Generic.List[string]]::new()
$collectionPacketMarkdownLines.Add('# goal-next-auto collection packet') | Out-Null
$collectionPacketMarkdownLines.Add('') | Out-Null
$collectionPacketMarkdownLines.Add("- decision=$decision") | Out-Null
$collectionPacketMarkdownLines.Add("- topic=$Topic") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.requiredEnvNames=$(@($collectionPacket.supabase.requiredEnvNames) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.requiredMcpTools=$(@($collectionPacket.supabase.requiredMcpTools) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.requiredResultNames=$(@($collectionPacket.supabase.requiredResultNames) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.resultPathRecommendation=$($collectionPacket.supabase.resultPathRecommendation)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.advisorResultPathRecommendation=$($collectionPacket.supabase.advisorResultPathRecommendation)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.present=$($collectionPacket.supabase.mcpConfig.present)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.readOnly=$($collectionPacket.supabase.mcpConfig.readOnly)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.projectRefSource=$($collectionPacket.supabase.mcpConfig.projectRefSource)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.serverHost=$($collectionPacket.supabase.mcpConfig.serverHost)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.features=$(@($collectionPacket.supabase.mcpConfig.features) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.tokenStored=$($collectionPacket.supabase.mcpConfig.tokenStored)") | Out-Null
$collectionPacketMarkdownLines.Add("- external.requiredRoles=$(@($collectionPacket.external.requiredRoles) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- external.requiredSidecars=$(@($collectionPacket.external.requiredSidecars) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- external.sourceIsolation.guard=$($collectionPacket.external.requiredSourceIsolation.guard)") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.requiredEnvNames=$(@($collectionPacket.archive.requiredEnvNames) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.requiredMcpTools=$(@($collectionPacket.archive.requiredMcpTools) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.indexPathRecommendation=$($collectionPacket.archive.indexPathRecommendation)") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.archiveRootRecommendation=$($collectionPacket.archive.archiveRootRecommendation)") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.nextActions=$(@($collectionPacket.archive.nextActions) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.decision=$($collectionPacket.computerUse.decision)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.tool=$($collectionPacket.computerUse.tool)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.outputPath=$($collectionPacket.computerUse.outputPath)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.storesRawAppNames=$($collectionPacket.computerUse.storesRawAppNames)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.storesWindowTitles=$($collectionPacket.computerUse.storesWindowTitles)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.appCount=$($collectionPacket.computerUse.appCount)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.runningCount=$($collectionPacket.computerUse.runningCount)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.windowCount=$($collectionPacket.computerUse.windowCount)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.secretHits=$($collectionPacket.computerUse.secretHits)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.nextActions=$(@($collectionPacket.computerUse.nextActions) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.decision=$($collectionPacket.browserUse.decision)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.tool=$($collectionPacket.browserUse.tool)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.outputPath=$($collectionPacket.browserUse.outputPath)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.storesRawUrl=$($collectionPacket.browserUse.storesRawUrl)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.storesScreenshotPath=$($collectionPacket.browserUse.storesScreenshotPath)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.reachable=$($collectionPacket.browserUse.reachable)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.localhost=$($collectionPacket.browserUse.localhost)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.screenshotCaptured=$($collectionPacket.browserUse.screenshotCaptured)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.statusClass=$($collectionPacket.browserUse.statusClass)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.targetContentVisible=$($collectionPacket.browserUse.targetContentVisible)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.browserSurface=$($collectionPacket.browserUse.browserSurface)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.secretHits=$($collectionPacket.browserUse.secretHits)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.nextActions=$(@($collectionPacket.browserUse.nextActions) -join ',')") | Out-Null
Write-TextFile -Path $collectionPacketMarkdownPath -Value (($collectionPacketMarkdownLines | ForEach-Object { Redact-SensitiveText ([string]$_) }) -join "`n")
$supabaseMcpConfig = $collectionPacket.supabase.mcpConfig
$sourceActionCounts = [ordered]@{}
foreach ($entry in $nextActionEntries) {
    $source = [string]$entry.source
    if ([string]::IsNullOrWhiteSpace($source)) {
        $source = 'unknown'
    }
    if (-not $sourceActionCounts.Contains($source)) {
        $sourceActionCounts[$source] = 0
    }
    $sourceActionCounts[$source] = [int]$sourceActionCounts[$source] + 1
}
$topActionEntries = @($nextActionEntries | Select-Object -First 8 | ForEach-Object {
    [ordered]@{
        source = [string]$_.source
        action = [string]$_.action
        decision = [string]$_.decision
    }
})
$firstAction = if ($nextActionEntries.Count -gt 0) { [string]$nextActionEntries[0].action } else { '' }
$firstActionSource = if ($nextActionEntries.Count -gt 0) { [string]$nextActionEntries[0].source } else { '' }
$latestPointerPath = Join-Path $defaultSmokeRoot 'goal-next-auto.latest.json'
New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
Write-JsonFile -Path $latestPointerPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.latest.v1'
    generatedAt = $generatedAtText
    decision = $decision
    topic = $Topic
    root = $Root
    outputDir = $OutputDir
    latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
    latestStaleAfterMinutes = $latestStaleAfterMinutes
    latestExpiresAt = $latestExpiresAt
    staleLatest = $staleLatest
    summaryPath = $summaryPath
    nextActionsPath = $nextActionsPath
    commandPacketPath = $commandPacketPath
    commandPacketMarkdownPath = $commandPacketMarkdownPath
    collectionPacketPath = $collectionPacketPath
    collectionPacketMarkdownPath = $collectionPacketMarkdownPath
    digestPath = $digestPath
    digestMarkdownPath = $digestMarkdownPath
    breadcrumbTimelinePath = $breadcrumbTimelinePath
    breadcrumbFusionPath = $breadcrumbFusionPath
    nextActionEntryCount = $nextActionEntries.Count
    commandPacketCommandCount = $commandPacket.commandCount
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousBreadcrumb = $previousBreadcrumbSummary
    computerUseOk = [bool]$computerUseSummary.ok
    computerUseReachable = [bool]$computerUseSummary.reachable
    computerUseAppCount = [int]$computerUseSummary.appCount
    computerUseDecision = [string]$computerUseSummary.decision
    computerUse = $computerUseSummary
    browserUseOk = [bool]$browserUseSummary.ok
    browserUseReachable = [bool]$browserUseSummary.reachable
    browserUseLocalhost = [bool]$browserUseSummary.localhost
    browserUseDecision = [string]$browserUseSummary.decision
    browserUse = $browserUseSummary
    supabaseSmoke = $supabaseSmokeSummary
    supabaseMcpConfig = $supabaseMcpConfig
    secretHits = $secretHits
})
$completionAuditOutputPath = Join-Path $OutputDir 'awx-mcp-completion-audit.result.json'
$completionAudit = Invoke-PythonCapture `
    -Name 'completion_audit' `
    -ScriptPath (Join-Path $scriptsDir 'awx_mcp_completion_audit.py') `
    -Arguments @('--root', $Root, '--output', $completionAuditOutputPath) `
    -LogPath (Join-Path $OutputDir 'awx-mcp-completion-audit.log')
if (Test-Path -LiteralPath $completionAuditOutputPath) {
    Copy-Item -LiteralPath $completionAuditOutputPath -Destination (Join-Path $defaultSmokeRoot 'awx-mcp-completion-audit-current.json') -Force
}
$secretHits += [int]$completionAudit.SecretHits
if ($secretHits -gt 0 -or $completionAudit.ExitCode -eq 4) {
    $decision = 'secret-leak-risk'
} elseif ($decision -eq 'ok' -and $completionAudit.ExitCode -ne 0) {
    $decision = 'evidence_needed'
}
$summaryFailureClassification = if ($decision -eq 'ok') {
    ''
} elseif (-not [string]::IsNullOrWhiteSpace([string]$preflight.failureClassification)) {
    [string]$preflight.failureClassification
} else {
    $decision
}
$sourceActionCountText = if ($sourceActionCounts.Count -gt 0) {
    @($sourceActionCounts.Keys | ForEach-Object { "$_=$($sourceActionCounts[$_])" }) -join ', '
} else {
    'none'
}
$digestMarkdownLines = [System.Collections.Generic.List[string]]::new()
$digestMarkdownLines.Add('# goal-next-auto digest') | Out-Null
$digestMarkdownLines.Add('') | Out-Null
$digestMarkdownLines.Add("- decision=$decision") | Out-Null
$digestMarkdownLines.Add("- topic=$Topic") | Out-Null
$digestMarkdownLines.Add("- latestGeneratedAtAgeMinutes=$latestGeneratedAtAgeMinutes") | Out-Null
$digestMarkdownLines.Add("- latestStaleAfterMinutes=$latestStaleAfterMinutes") | Out-Null
$digestMarkdownLines.Add("- latestExpiresAt=$latestExpiresAt") | Out-Null
$digestMarkdownLines.Add("- staleLatest=$($staleLatest.ToString().ToLowerInvariant())") | Out-Null
$digestMarkdownLines.Add("- firstAction=$firstAction") | Out-Null
$digestMarkdownLines.Add("- firstActionSource=$firstActionSource") | Out-Null
$digestMarkdownLines.Add("- previousBreadcrumb.present=$($previousBreadcrumbSummary.present) decision=$($previousBreadcrumbSummary.decision) firstAction=$($previousBreadcrumbSummary.firstAction) toolStepCount=$($previousBreadcrumbSummary.toolStepCount)") | Out-Null
$digestMarkdownLines.Add("- sourceActionCounts=$sourceActionCountText") | Out-Null
$digestMarkdownLines.Add("- supabaseDecision=$(if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' })") | Out-Null
$digestMarkdownLines.Add("- supabaseSmoke.mcpDecision=$($supabaseSmokeSummary.mcpDecision) supabaseSmoke.mcpReachable=$($supabaseSmokeSummary.mcpReachable) supabaseSmoke.mcpProbeSkipped=$($supabaseSmokeSummary.mcpProbeSkipped) supabaseSmoke.reachability=$($supabaseSmokeSummary.mcpEndpointReachabilityEvidence)") | Out-Null
$digestMarkdownLines.Add("- supabaseMcp.present=$($supabaseMcpConfig.present) supabaseMcp.readOnly=$($supabaseMcpConfig.readOnly) supabaseMcp.projectRefSource=$($supabaseMcpConfig.projectRefSource) supabaseMcp.serverHost=$($supabaseMcpConfig.serverHost) supabaseMcp.tokenStored=$($supabaseMcpConfig.tokenStored)") | Out-Null
if ($null -ne $collectionPacket -and $null -ne $collectionPacket.supabase) {
    $digestMarkdownLines.Add("- supabase.requiredEnvNames=$(@($collectionPacket.supabase.requiredEnvNames) -join ',')") | Out-Null
    $digestMarkdownLines.Add("- supabase.requiredMcpTools=$(@($collectionPacket.supabase.requiredMcpTools) -join ',')") | Out-Null
    $digestMarkdownLines.Add("- supabase.resultPathRecommendation=$($collectionPacket.supabase.resultPathRecommendation)") | Out-Null
    $digestMarkdownLines.Add("- supabase.advisorResultPathRecommendation=$($collectionPacket.supabase.advisorResultPathRecommendation)") | Out-Null
}
$digestMarkdownLines.Add("- externalDecision=$(if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' })") | Out-Null
if ($null -ne $externalApplySummary) {
    $digestMarkdownLines.Add("- external.requiredRoles=$(@($externalApplySummary.requiredRoles) -join ',')") | Out-Null
    $digestMarkdownLines.Add("- external.requiredSidecars=$(@($externalApplySummary.requiredPatchDropSidecars) -join ',')") | Out-Null
    $digestMarkdownLines.Add("- external.sourceIsolation.guard=$($externalApplySummary.requiredSourceIsolation.guard)") | Out-Null
}
$digestMarkdownLines.Add("- desktopFinalProof=$(if ($null -ne $desktopControlLoopJson) { [string]$desktopControlLoopJson.desktopFinalProof } else { 'evidence_needed' })") | Out-Null
$digestMarkdownLines.Add("- computerUse=$(if ([bool]$computerUseSummary.ok) { 'ok' } else { [string]$computerUseSummary.decision }) reachable=$($computerUseSummary.reachable) stale=$($computerUseSummary.stale) appCount=$($computerUseSummary.appCount) runningCount=$($computerUseSummary.runningCount) windowCount=$($computerUseSummary.windowCount) secretHits=$($computerUseSummary.secretHits)") | Out-Null
$digestMarkdownLines.Add("- browserUse=$(if ([bool]$browserUseSummary.ok) { 'ok' } else { [string]$browserUseSummary.decision }) reachable=$($browserUseSummary.reachable) localhost=$($browserUseSummary.localhost) stale=$($browserUseSummary.stale) screenshotCaptured=$($browserUseSummary.screenshotCaptured) secretHits=$($browserUseSummary.secretHits)") | Out-Null
$digestMarkdownLines.Add("- secretHits=$secretHits") | Out-Null
if ($topActionEntries.Count -gt 0) {
    $digestMarkdownLines.Add('') | Out-Null
    $digestMarkdownLines.Add('## topActions') | Out-Null
    foreach ($entry in $topActionEntries) {
        $digestMarkdownLines.Add("- source=$($entry.source) action=$($entry.action) decision=$($entry.decision)") | Out-Null
    }
}

$supabaseApplyArtifactSummary = [ordered]@{
    parsed = ($null -ne $supabaseApplySummary)
    summaryPath = $supabaseApplySummaryPath
    ok = if ($null -ne $supabaseApplySummary) { [bool]$supabaseApplySummary.ok } else { $false }
    decision = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' }
    evidenceNeededCount = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.evidenceNeeded) { @($supabaseApplySummary.evidenceNeeded).Count } else { 0 }
    requiredEnvNames = if ($null -ne $collectionPacket -and $null -ne $collectionPacket.supabase -and $null -ne $collectionPacket.supabase.requiredEnvNames) { @($collectionPacket.supabase.requiredEnvNames) } else { @() }
    requiredMcpTools = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.requiredMcpTools) { @($supabaseApplySummary.requiredMcpTools) } else { @() }
    requiredResultNames = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.requiredResultNames) { @($supabaseApplySummary.requiredResultNames) } else { @() }
    nextActions = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.nextActions) { @($supabaseApplySummary.nextActions) } else { @() }
    resultPathRecommendation = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.resultPathRecommendation } else { '' }
    advisorResultPathRecommendation = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.advisorResultPathRecommendation } else { '' }
    secretHits = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.secretHits) { [int]$supabaseApplySummary.secretHits } else { 0 }
    rawSecretPatternHits = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.rawSecretPatternHits) { [int]$supabaseApplySummary.rawSecretPatternHits } else { 0 }
}

$externalApplyArtifactSummary = [ordered]@{
    parsed = ($null -ne $externalApplySummary)
    summaryPath = $externalApplySummaryPath
    ok = if ($null -ne $externalApplySummary) { [bool]$externalApplySummary.ok } else { $false }
    decision = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' }
    evidenceNeededCount = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.evidenceNeeded) { @($externalApplySummary.evidenceNeeded).Count } else { 0 }
    requiredRoles = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.requiredRoles) { @($externalApplySummary.requiredRoles) } else { @() }
    requiredProducerEvidenceFiles = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.requiredProducerEvidenceFiles) { @($externalApplySummary.requiredProducerEvidenceFiles) } else { @() }
    requiredPatchDropSidecars = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.requiredPatchDropSidecars) { @($externalApplySummary.requiredPatchDropSidecars) } else { @() }
    requiredSourceIsolation = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.requiredSourceIsolation) { $externalApplySummary.requiredSourceIsolation } else { [ordered]@{} }
    nextActions = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.nextActions) { @($externalApplySummary.nextActions) } else { @() }
    applyCollectedEvidenceCommand = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.applyCollectedEvidenceCommand } else { '' }
    copiedEvidenceCount = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.copiedEvidenceCount) { [int]$externalApplySummary.copiedEvidenceCount } else { 0 }
    copiedHandoffCount = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.copiedHandoffCount) { [int]$externalApplySummary.copiedHandoffCount } else { 0 }
    secretHits = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.secretHits) { [int]$externalApplySummary.secretHits } else { 0 }
    rawSecretPatternHits = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.rawSecretPatternHits) { [int]$externalApplySummary.rawSecretPatternHits } else { 0 }
}

$breadcrumbToolSteps = @(
    [ordered]@{
        name = 'preflight'
        decision = if ([string]::IsNullOrWhiteSpace([string]$preflight.failureClassification)) { 'ok' } else { 'evidence_needed' }
        failureClassification = [string]$preflight.failureClassification
        patchDropPendingPatchCount = [int]$preflight.patchDropPendingPatchCount
        sourceLeaseActiveCount = [int]$preflight.sourceLeaseActiveCount
        sourceLeaseCorruptCount = [int]$preflight.sourceLeaseCorruptCount
    },
    [ordered]@{
        name = 'supabase_smoke'
        exitCode = [int]$supabaseSmoke.ExitCode
        decision = [string]$supabaseSmokeSummary.decision
        projectScopeStatus = [string]$supabaseSmokeSummary.projectScopeStatus
        mcpDecision = [string]$supabaseSmokeSummary.mcpDecision
        secretHits = ([int]$supabaseSmoke.SecretHits + [int]$supabaseSmokeSummary.secretHits)
    },
    [ordered]@{
        name = 'supabase_apply'
        exitCode = [int]$supabaseApply.ExitCode
        decision = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' }
        evidenceNeededCount = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.evidenceNeeded) { @($supabaseApplySummary.evidenceNeeded).Count } else { 0 }
        secretHits = [int]$supabaseApply.SecretHits
    },
    [ordered]@{
        name = 'external_apply'
        exitCode = [int]$externalApply.ExitCode
        decision = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' }
        evidenceNeededCount = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.evidenceNeeded) { @($externalApplySummary.evidenceNeeded).Count } else { 0 }
        secretHits = [int]$externalApply.SecretHits
    },
    [ordered]@{
        name = 'desktop_control_loop'
        exitCode = [int]$desktopControlLoop.ExitCode
        decision = if ($null -ne $desktopControlLoopJson) { [string]$desktopControlLoopJson.decision } else { 'evidence_needed' }
        localReady = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.localReady } else { $false }
        completionReady = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.completionReady } else { $false }
        externalEvidenceComplete = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.externalEvidenceComplete } else { $false }
        nextActionCount = $desktopControlLoopNextActionCount
        secretHits = [int]$desktopControlLoop.SecretHits
    },
    [ordered]@{
        name = 'completion_audit_preflight'
        exitCode = [int]$completionAuditPreflight.ExitCode
        secretHits = [int]$completionAuditPreflight.SecretHits
    },
    [ordered]@{
        name = 'source_health'
        exitCode = [int]$sourceHealth.ExitCode
        strictEvidenceAdjustedScore = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.strictEvidenceAdjustedScore) { [double]$sourceHealthJson.strictEvidenceAdjustedScore } else { 0.0 }
        activeRiskCount = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.activeRiskCount) { [int]$sourceHealthJson.activeRiskCount } else { 0 }
        nextSingleAction = if ($null -ne $sourceHealthJson) { [string]$sourceHealthJson.nextSingleAction } else { '' }
        secretHits = [int]$sourceHealth.SecretHits
    },
    [ordered]@{
        name = 'completion_audit'
        exitCode = [int]$completionAudit.ExitCode
        secretHits = [int]$completionAudit.SecretHits
    },
    [ordered]@{
        name = 'final_decision'
        decision = $decision
        failureClassification = $summaryFailureClassification
        firstAction = $firstAction
        firstActionSource = $firstActionSource
        nextActionCount = $nextActionEntries.Count
        secretHits = $secretHits
    }
)

$breadcrumbTraceKeys = @(
    'goalNext.decision',
    'goalNext.failureClassification',
    'goalNext.firstAction',
    'goalNext.firstActionSource',
    'goalNext.previousBreadcrumb.present',
    'goalNext.toolSteps.count',
    'goalNext.secretHits'
)
$breadcrumbMdc = [ordered]@{
    schemaVersion = 'awx.goal_next_auto.mdc.v1'
    nodeRole = 'desktop'
    topic = $Topic
    root = '<desktop-canonical-root>'
    decision = $decision
    failureClassification = $summaryFailureClassification
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousDecision = [string]$previousBreadcrumbSummary.decision
    previousFirstAction = [string]$previousBreadcrumbSummary.firstAction
}
$breadcrumbTrace = [ordered]@{
    schemaVersion = 'awx.goal_next_auto.trace.v1'
    keys = @($breadcrumbTraceKeys)
    mdcKeys = @('nodeRole', 'topic', 'root', 'decision', 'failureClassification', 'firstAction', 'firstActionSource')
    toolStepCount = @($breadcrumbToolSteps).Count
    nextActionCount = $nextActionEntries.Count
}
$breadcrumbRawTile = [ordered]@{
    schemaVersion = 'awx.goal_next_auto.raw_tile.v1'
    tileKind = 'goal_next_decision_breadcrumb'
    status = $decision
    blocker = $summaryFailureClassification
    bottleneck = $firstActionSource
    nextAction = $firstAction
    reuseSource = if ($previousBreadcrumbSummary.present) { 'previous_breadcrumb' } else { 'seed_timeline' }
    previousFirstAction = [string]$previousBreadcrumbSummary.firstAction
    evidence = [ordered]@{
        toolStepCount = @($breadcrumbToolSteps).Count
        nextActionCount = $nextActionEntries.Count
        secretHits = $secretHits
        sourceHealthExit = [int]$sourceHealth.ExitCode
        completionAuditExit = [int]$completionAudit.ExitCode
        supabaseDecision = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' }
        externalDecision = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' }
        computerUseDecision = if ($null -ne $computerUseSummary) { [string]$computerUseSummary.decision } else { 'missing' }
        browserUseDecision = if ($null -ne $browserUseSummary) { [string]$browserUseSummary.decision } else { 'missing' }
    }
}
$currentBreadcrumb = [ordered]@{
    schemaVersion = 'awx.goal_next_auto.breadcrumb.v1'
    generatedAt = $generatedAtText
    topic = $Topic
    root = '<desktop-canonical-root>'
    outputDir = Get-SafePacketString -Text $OutputDir -ProjectRoot $Root
    decision = $decision
    failureClassification = $summaryFailureClassification
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousBreadcrumb = $previousBreadcrumbSummary
    nextActionCount = $nextActionEntries.Count
    nextActionSources = @($nextActionSources)
    topActions = @($topActionEntries)
    toolSteps = @($breadcrumbToolSteps)
    trace = $breadcrumbTrace
    mdc = $breadcrumbMdc
    rawTile = $breadcrumbRawTile
    artifacts = [ordered]@{
        summary = Get-SafePacketString -Text $summaryPath -ProjectRoot $Root
        digest = Get-SafePacketString -Text $digestPath -ProjectRoot $Root
        commandPacket = Get-SafePacketString -Text $commandPacketPath -ProjectRoot $Root
        collectionPacket = Get-SafePacketString -Text $collectionPacketPath -ProjectRoot $Root
        nextActions = Get-SafePacketString -Text $nextActionsPath -ProjectRoot $Root
        fusion = Get-SafePacketString -Text $breadcrumbFusionPath -ProjectRoot $Root
    }
    secretHits = $secretHits
    rawSecretPatternHits = $secretHits
}
Append-JsonLineFile -Path $breadcrumbTimelinePath -Value $currentBreadcrumb
$breadcrumbFusion = New-BreadcrumbFusionSummary `
    -Rows (Read-JsonLineFileTail -Path $breadcrumbTimelinePath -MaxRows 25) `
    -GeneratedAtText $generatedAtText `
    -TailLimit 25
Write-JsonFile -Path $breadcrumbFusionPath -Value $breadcrumbFusion
$digestMarkdownLines.Add("- breadcrumbFusion.timelineRowsRead=$($breadcrumbFusion.timelineRowsRead) reusablePattern=$($breadcrumbFusion.reusablePattern) latestFirstAction=$($breadcrumbFusion.latestFirstAction)") | Out-Null
$digestMarkdownLines.Add("- externalInputGate.status=$($breadcrumbFusion.externalInputGate.status) source=$($breadcrumbFusion.externalInputGate.source) action=$($breadcrumbFusion.externalInputGate.action) repeated=$($breadcrumbFusion.externalInputGate.repeated) repeatCount=$($breadcrumbFusion.externalInputGate.repeatCount) localPatchJustified=$($breadcrumbFusion.externalInputGate.localPatchJustified) mutationAllowed=$($breadcrumbFusion.externalInputGate.mutationAllowed)") | Out-Null

Write-JsonFile -Path $summaryPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.summary.v1'
    generatedAt = $generatedAtText
    ok = ($decision -eq 'ok')
    decision = $decision
    failureClassification = $summaryFailureClassification
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousBreadcrumb = $previousBreadcrumbSummary
    externalInputGate = $breadcrumbFusion.externalInputGate
    nextActionCount = $nextActionEntries.Count
    nextActionSources = @($nextActionSources)
    topActions = @($topActionEntries)
    topic = $Topic
    root = $Root
    preflight = $preflight
    env = [ordered]@{
        SUPABASE_PROJECT_REF_present = $envProjectRefPresent
        SUPABASE_ACCESS_TOKEN_present = $envAccessTokenPresent
    }
    supabaseSmokeExit = $supabaseSmoke.ExitCode
    supabaseSmoke = $supabaseSmokeSummary
    supabaseApplyExit = $supabaseApply.ExitCode
    externalApplyExit = $externalApply.ExitCode
    desktopControlLoopExit = $desktopControlLoop.ExitCode
    desktopControlLoop = [ordered]@{
        parsed = ($null -ne $desktopControlLoopJson)
        ok = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.ok } else { $false }
        localReady = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.localReady } else { $false }
        completionReady = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.completionReady } else { $false }
        desktopFinalProof = if ($null -ne $desktopControlLoopJson) { [string]$desktopControlLoopJson.desktopFinalProof } else { 'evidence_needed' }
        externalEvidenceComplete = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.externalEvidenceComplete } else { $false }
        nextActionCount = $desktopControlLoopNextActionCount
    }
    computerUse = $computerUseSummary
    browserUse = $browserUseSummary
    supabaseApply = $supabaseApplyArtifactSummary
    externalApply = $externalApplyArtifactSummary
    sourceHealthExit = $sourceHealth.ExitCode
    completionAuditExit = $completionAudit.ExitCode
    secretHits = $secretHits
    rawSecretPatternHits = $secretHits
    artifacts = [ordered]@{
        supabaseApplySummary = $supabaseApplySummaryPath
        externalApplySummary = $externalApplySummaryPath
        nextActions = $nextActionsPath
        commandPacket = $commandPacketPath
        commandPacketMarkdown = $commandPacketMarkdownPath
        collectionPacket = $collectionPacketPath
        collectionPacketMarkdown = $collectionPacketMarkdownPath
        digest = $digestPath
        digestMarkdown = $digestMarkdownPath
        breadcrumbTimeline = $breadcrumbTimelinePath
        breadcrumbFusion = $breadcrumbFusionPath
        desktopControlLoop = $desktopControlLoopPath
        sourceHealth = Join-Path $OutputDir 'source-health-scorecard.json'
        completionAudit = Join-Path $OutputDir 'awx-mcp-completion-audit.result.json'
    }
    logs = [ordered]@{
        supabaseSmoke = $supabaseSmoke.LogPath
        supabaseApply = $supabaseApply.LogPath
        externalApply = $externalApply.LogPath
        desktopControlLoop = $desktopControlLoop.LogPath
        sourceHealth = $sourceHealth.LogPath
        completionAudit = $completionAudit.LogPath
    }
})

Write-JsonFile -Path $digestPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.digest.v1'
    generatedAt = $generatedAtText
    decision = $decision
    topic = $Topic
    root = $Root
    latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
    latestStaleAfterMinutes = $latestStaleAfterMinutes
    latestExpiresAt = $latestExpiresAt
    staleLatest = $staleLatest
    summaryPath = $summaryPath
    nextActionsPath = $nextActionsPath
    commandPacketPath = $commandPacketPath
    commandPacketMarkdownPath = $commandPacketMarkdownPath
    collectionPacketPath = $collectionPacketPath
    collectionPacketMarkdownPath = $collectionPacketMarkdownPath
    digestMarkdownPath = $digestMarkdownPath
    nextActionEntryCount = $nextActionEntries.Count
    commandPacketCommandCount = $commandPacket.commandCount
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousBreadcrumb = $previousBreadcrumbSummary
    externalInputGate = $breadcrumbFusion.externalInputGate
    sourceActionCounts = $sourceActionCounts
    topActions = $topActionEntries
    supabaseDecision = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' }
    supabaseSmoke = $supabaseSmokeSummary
    supabaseMcpConfig = $supabaseMcpConfig
    supabaseApply = $supabaseApplyArtifactSummary
    externalDecision = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' }
    externalApply = $externalApplyArtifactSummary
    desktopFinalProof = if ($null -ne $desktopControlLoopJson) { [string]$desktopControlLoopJson.desktopFinalProof } else { 'evidence_needed' }
    computerUseOk = [bool]$computerUseSummary.ok
    computerUseReachable = [bool]$computerUseSummary.reachable
    computerUseAppCount = [int]$computerUseSummary.appCount
    computerUseDecision = [string]$computerUseSummary.decision
    computerUse = $computerUseSummary
    browserUseOk = [bool]$browserUseSummary.ok
    browserUseReachable = [bool]$browserUseSummary.reachable
    browserUseLocalhost = [bool]$browserUseSummary.localhost
    browserUseDecision = [string]$browserUseSummary.decision
    browserUse = $browserUseSummary
    secretHits = $secretHits
    breadcrumbTimelinePath = $breadcrumbTimelinePath
    breadcrumbFusionPath = $breadcrumbFusionPath
    breadcrumbFusion = $breadcrumbFusion
})
Write-TextFile -Path $digestMarkdownPath -Value (($digestMarkdownLines | ForEach-Object { Redact-SensitiveText ([string]$_) }) -join "`n")

New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
$latestPointerPath = Join-Path $defaultSmokeRoot 'goal-next-auto.latest.json'
Write-JsonFile -Path $latestPointerPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.latest.v1'
    generatedAt = $generatedAtText
    decision = $decision
    topic = $Topic
    root = $Root
    outputDir = $OutputDir
    latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
    latestStaleAfterMinutes = $latestStaleAfterMinutes
    latestExpiresAt = $latestExpiresAt
    staleLatest = $staleLatest
    summaryPath = $summaryPath
    nextActionsPath = $nextActionsPath
    commandPacketPath = $commandPacketPath
    commandPacketMarkdownPath = $commandPacketMarkdownPath
    collectionPacketPath = $collectionPacketPath
    collectionPacketMarkdownPath = $collectionPacketMarkdownPath
    digestPath = $digestPath
    digestMarkdownPath = $digestMarkdownPath
    breadcrumbTimelinePath = $breadcrumbTimelinePath
    breadcrumbFusionPath = $breadcrumbFusionPath
    nextActionEntryCount = $nextActionEntries.Count
    commandPacketCommandCount = $commandPacket.commandCount
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousBreadcrumb = $previousBreadcrumbSummary
    externalInputGate = $breadcrumbFusion.externalInputGate
    computerUseOk = [bool]$computerUseSummary.ok
    computerUseReachable = [bool]$computerUseSummary.reachable
    computerUseAppCount = [int]$computerUseSummary.appCount
    computerUseDecision = [string]$computerUseSummary.decision
    computerUse = $computerUseSummary
    browserUseOk = [bool]$browserUseSummary.ok
    browserUseReachable = [bool]$browserUseSummary.reachable
    browserUseLocalhost = [bool]$browserUseSummary.localhost
    browserUseDecision = [string]$browserUseSummary.decision
    browserUse = $browserUseSummary
    supabaseSmoke = $supabaseSmokeSummary
    supabaseMcpConfig = $supabaseMcpConfig
    supabaseApply = $supabaseApplyArtifactSummary
    externalApply = $externalApplyArtifactSummary
    secretHits = $secretHits
})

if ($EnsureFresh) {
    $statusPath = Join-Path $defaultSmokeRoot 'goal-next-auto.status.json'
    Write-JsonFile -Path $statusPath -Value ([ordered]@{
        schemaVersion = 'awx.goal_next_auto.status.v1'
        generatedAt = $generatedAtText
        root = $Root
        latestPath = $latestPointerPath
        statusPath = $statusPath
        latestDecision = $decision
        statusDecision = $decision
        failureClassification = if ($decision -eq 'ok') { '' } else { $decision }
        latestGeneratedAt = $generatedAtText
        latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
        latestStaleAfterMinutes = $latestStaleAfterMinutes
        staleLatest = $staleLatest
        summaryPath = $summaryPath
        nextActionsPath = $nextActionsPath
        commandPacketPath = $commandPacketPath
        commandPacketMarkdownPath = $commandPacketMarkdownPath
        collectionPacketPath = $collectionPacketPath
        collectionPacketMarkdownPath = $collectionPacketMarkdownPath
        digestPath = $digestPath
        digestMarkdownPath = $digestMarkdownPath
        breadcrumbTimelinePath = $breadcrumbTimelinePath
        nextActionEntryCount = $nextActionEntries.Count
        firstAction = $firstAction
        firstActionSource = $firstActionSource
        previousBreadcrumb = $previousBreadcrumbSummary
        computerUseOk = [bool]$computerUseSummary.ok
        computerUseReachable = [bool]$computerUseSummary.reachable
        computerUseAppCount = [int]$computerUseSummary.appCount
        computerUseDecision = [string]$computerUseSummary.decision
        computerUse = $computerUseSummary
        browserUseOk = [bool]$browserUseSummary.ok
        browserUseReachable = [bool]$browserUseSummary.reachable
        browserUseLocalhost = [bool]$browserUseSummary.localhost
        browserUseDecision = [string]$browserUseSummary.decision
        browserUse = $browserUseSummary
        supabaseSmoke = $supabaseSmokeSummary
        supabaseMcpConfig = $supabaseMcpConfig
        supabaseApply = $supabaseApplyArtifactSummary
        externalApply = $externalApplyArtifactSummary
        secretHits = $secretHits
    })
}

Write-Host "[AWX][goal-next] decision=$decision supabaseSmokeExit=$($supabaseSmoke.ExitCode) supabaseApplyExit=$($supabaseApply.ExitCode) externalApplyExit=$($externalApply.ExitCode) desktopControlLoopExit=$($desktopControlLoop.ExitCode) sourceHealthExit=$($sourceHealth.ExitCode) completionAuditExit=$($completionAudit.ExitCode) secretHits=$secretHits summary=$summaryPath"

if ($decision -eq 'secret-leak-risk') {
    exit 4
}
if ($decision -eq 'evidence_needed') {
    exit 2
}

exit 0
