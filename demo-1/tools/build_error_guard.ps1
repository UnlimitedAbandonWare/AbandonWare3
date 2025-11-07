\
Param()
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$patFile = Join-Path $root "tools\build_error_patterns.txt"
$logCandidates = @(
  (Join-Path $root "build\logs\build.log"),
  (Join-Path $root "build\reports\tests\test\index.html"),
  (Join-Path $root "build\tmp\compileJava\previous-compilation-data.bin")
)
Write-Host "[guard] scanning for known bad patterns..."
$rc = 0
if (Test-Path $patFile) {
  $patterns = Get-Content $patFile | Where-Object { $_ -and ($_ -notmatch "^\s*#") }
  foreach ($log in $logCandidates) {
    if (Test-Path $log) {
      foreach ($pat in $patterns) {
        $match = Select-String -Path $log -Pattern $pat -SimpleMatch -ErrorAction SilentlyContinue
        if ($match) {
          Write-Host "::warning file=$log:: matched pattern: $pat"
          $rc = 1
        }
      }
    }
  }
}
# banned tokens in sources (auto-sanitize for {스터프3})
find "$ROOT_DIR/src" -type f -name "*.*" -print0 | xargs -0 sed -i.bak 's/{스터프3}//g' || true
exit $rc


\
            function Check-DuplicateYamlKeys {
              param([string]$Root)
              $files = @(
                "$Root/src/main/resources/application.yml",
                "$Root/app/src/main/resources/application.yml",
                "$Root/app/resources/application.yml",
                "$Root/demo-1/src/main/resources/application.yml"
              )
              foreach ($f in $files) {
                if (Test-Path $f) {
                  $cnt = (Select-String -Path $f -Pattern '^\s*retrieval:' -AllMatches).Matches.Count
                  if ($cnt -gt 1) {
                    Write-Host "[guard] duplicate 'retrieval:' keys detected in $f (count=$cnt)"
                    exit 1
                  }
                }
              }
            }
            Check-DuplicateYamlKeys -Root $PSScriptRoot\..
