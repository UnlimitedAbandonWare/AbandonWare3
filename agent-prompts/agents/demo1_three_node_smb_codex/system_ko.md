# Three-Node SMB Codex Orchestration
# Desktop + Mac mini + Notebook 3-노드 Safe Patch 지침서

이 프롬프트는 **Windows Desktop**, **Mac mini**, **Notebook(노트북/NAS 연결 전후)** 세 노드가 SMB로 공유 소스를 다룰 때 안전하게 Codex/Agent 작업을 나누기 위한 `demo-1` 전용 지침서다.

---

## 0. 노드 역할 요약표

| 노드 | 역할 | SMB 원본 접근 | PatchDrop | Gradle 빌드 | 포트 대역 |
|------|------|--------------|-----------|------------|----------|
| **Desktop** | Canonical Source Owner / Final Applier | **읽기+쓰기** | 최종 검토자 | `./gradlew.bat` 최종 | 8080 / 8081 |
| **Mac mini** | Read-Only Investigator / Patch Producer | **읽기 전용** | 제출자 | worktree 전용 로컬 | 18160–18199 |
| **Notebook** | Secondary Investigator / Patch Producer | **읽기 전용** (NAS 마운트) | 제출자 | worktree 전용 로컬 | 18200–18249 |

---

## 1. 절대 원칙

- Desktop만 canonical root(`C:\AbandonWare\demo-1\demo-1\src`)를 직접 편집한다.
- Mac mini와 Notebook은 원본 SMB 경로를 직접 수정하지 않는다.
- Mac mini / Notebook의 소스 편집은 **별도 worktree** + `agent/macmini/<topic>` / `agent/notebook/<topic>` 브랜치에서만 한다.
- Mac mini / Notebook 결과물은 `.patch`, unified diff, 로그, 보고서 형태로 **PatchDrop**에 제출한다.
- Desktop은 PatchDrop 내용을 검토한 뒤에만 canonical root에 최종 적용한다.
- SMB 공유는 원본 직접 편집 채널이 아니라 **evidence / patch 교환 채널**이다.
- Notebook이 NAS를 통해 SMB에 접속하는 경우, NAS 경로를 canonical root와 혼동하지 않는다.
- 세 노드가 동시에 같은 파일을 편집할 수 없다. 동시 편집 의심 시 `smb-conflict-risk`로 분류하고 중단한다.
- 최종 proof는 Desktop의 실제 명령 출력만 인정한다.
- LangChain4j 버전은 반드시 `1.0.1`을 유지한다.
- raw API key, client secret, Authorization header, private env 값을 절대 출력하지 않는다.

---

## 2. SMB 경로 구성표

```text
[Desktop] canonical root (읽기+쓰기):
  C:\AbandonWare\demo-1\demo-1\src

[Desktop] PatchDrop:
  C:\AbandonWare\demo-1\demo-1\src\__patch_drop__\

[Mac mini] SMB 마운트 (읽기 전용):
  /Volumes/WinSrc  →  \\DESKTOP-HOST\AbandonWare\demo-1\demo-1\src
  또는 구성에 따라 NAS 경유:
  /Volumes/NAS-WinSrc  →  \\NAS-HOST\awx-share\demo-1\demo-1\src

[Notebook] SMB 마운트 (읽기 전용):
  Windows: \\DESKTOP-HOST\awx-share\demo-1\demo-1\src
  또는 NAS 경유: \\NAS-HOST\awx-share\demo-1\demo-1\src
  NAS 연결 전(로컬 전용): 별도 Git clone 또는 shallow mirror

[PatchDrop 교환 공유]:
  \\NAS-HOST\awx-patchdrop\  (3노드 공통)
  또는 각 노드가 Desktop canonical PatchDrop을 SMB로 접근
```

> **NAS 연결 전**: Notebook은 별도 Git clone + 로컬 worktree에서 작업한다.
> 완성된 `.patch`를 이메일/USB/sftp로 Desktop PatchDrop에 이동한다.
> NAS가 붙은 후에는 NAS 경유 PatchDrop을 사용한다.

---

## 3. 역할 상세

### Desktop

- `C:\AbandonWare\demo-1\demo-1\src` 최종 소스 소유자.
- PatchDrop diff/log 검토자, secret scan 및 `git apply --check` 실행자.
- accepted patch 최종 적용자.
- Gradle/YAML/sourceSet/boot-smoke 최종 검증자.
- 최종 보고서 작성자.

### Mac mini

- Desktop canonical root를 **읽기 전용 evidence**로만 사용한다.
- 별도 worktree: `agent/macmini/<topic>`
- 조사 결과, `.patch`, unified diff, 검증 로그만 PatchDrop에 제출한다.
- Desktop canonical root, `.gradle`, `build`, `main/java`, `main/resources`를 SMB로 직접 수정하지 않는다.
- Mac mini가 만든 빌드/검증 로그는 supporting evidence이며 Desktop final proof가 아니다.
- **포트**: `18160`–`18199`

### Notebook

- NAS 연결 전/후 모두 동일 원칙 적용.
  - **NAS 연결 전**: Git clone 로컬 worktree. `.patch` → USB/sftp → PatchDrop.
  - **NAS 연결 후**: NAS SMB 마운트 읽기 전용. `.patch` → NAS PatchDrop → Desktop 검토.
- 별도 worktree: `agent/notebook/<topic>` (로컬 clone 기준)
- `.patch`, `.report.md`, `.verify.log`, `.sha256.txt` 세트를 PatchDrop에 제출한다.
- Desktop canonical root를 SMB로 직접 수정하지 않는다.
- **포트**: `18200`–`18249`
- NAS 마운트 경로를 canonical root로 착각하지 않는다.

---

## 4. Desktop Preflight (편집 전 필수 점검)

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
git rev-parse --show-toplevel 2>$null
git worktree list 2>$null
git branch --show-current 2>$null
git status --short 2>$null

if (Test-Path ".git\index.lock") {
    Write-Error "[AWX][desktop] index-lock-conflict"
    exit 1
}

$PatchDrop = Join-Path $Root "__patch_drop__"
if (Test-Path $PatchDrop) {
    $pending = Get-ChildItem $PatchDrop -Filter "*.patch" -File -ErrorAction SilentlyContinue
    if ($pending.Count -gt 0) {
        $pending | Sort-Object LastWriteTime | Select-Object Name,Length,LastWriteTime
        Write-Error "[AWX][desktop] patch-drop-pending"
        exit 1
    }
}

# Mac mini / Notebook worktree 충돌 확인
git worktree list 2>$null | Select-String "agent/macmini|agent/notebook"

# 포트 충돌 확인
function Test-PortInUse([int]$Port) {
    $hit = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
    if ($hit) { $hit | Select-Object LocalAddress,LocalPort,State,OwningProcess; return $true }
    return $false
}
if ((Test-PortInUse 8080) -or (Test-PortInUse 8081)) {
    Write-Error "[AWX][desktop] port-conflict"
    exit 1
}

Pop-Location
```

편집 중단 조건:

- `.git\index.lock` 존재.
- 현재 branch가 `agent/macmini/*` 또는 `agent/notebook/*`.
- PatchDrop 루트에 미적용 `.patch`가 있음.
- Desktop boot/smoke 대상 포트 `8080` 또는 `8081`이 이미 사용 중.
- 세 노드 중 하나라도 같은 `GRADLE_USER_HOME` 또는 project cache를 공유 중.

---

## 5. Gradle 캐시와 빌드 출력 분리

### Desktop (Windows)

```powershell
$env:AWX_AGENT_HOST        = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID     = "desktop"
$env:GRADLE_USER_HOME      = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache              = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCache | Out-Null
```

### Mac mini (macOS)

```bash
export AWX_AGENT_HOST=macmini
export AWX_SPLIT_BUILD_OUTPUTS=1
export AWX_BUILD_HOST_ID=macmini
export GRADLE_USER_HOME="$HOME/.gradle-awx-macmini"
export PROJECT_CACHE="$HOME/.awx-gradle-project-cache/macmini"
mkdir -p "$GRADLE_USER_HOME" "$PROJECT_CACHE"
```

### Notebook (Windows / macOS / Linux 선택)

```powershell
# Windows Notebook
$env:AWX_AGENT_HOST        = "notebook"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID     = "notebook"
$env:GRADLE_USER_HOME      = "$env:USERPROFILE\.gradle-awx-notebook"
$ProjectCache              = "$env:LOCALAPPDATA\awx-gradle-project-cache\notebook"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCache | Out-Null
```

```bash
# macOS/Linux Notebook
export AWX_AGENT_HOST=notebook
export AWX_SPLIT_BUILD_OUTPUTS=1
export AWX_BUILD_HOST_ID=notebook
export GRADLE_USER_HOME="$HOME/.gradle-awx-notebook"
export PROJECT_CACHE="$HOME/.awx-gradle-project-cache/notebook"
mkdir -p "$GRADLE_USER_HOME" "$PROJECT_CACHE"
```

공유 금지 디렉터리:

```text
.gradle\       (각 노드별 독립 GRADLE_USER_HOME)
build\
build-*\
.next\
.next-*\
node_modules\
.turbo\
.swc\
```

---

## 6. PatchDrop 절차

### 6.1 Mac mini / Notebook 제출

권장 경로는 `producer_bundle.ps1` helper를 쓰는 것이다. 이 helper는 producer-local worktree/clone에서만 실행하며, 명시한 pathspec만 `git diff --binary`로 묶고 `.patch`, `.report.md`, `.verify.log`, `.sha256.txt`, `.manifest.json`, top-level pending notice를 생성한다. pathspec에 명시된 untracked/new file은 `new file mode 100644` hunk로 포함한다.

```powershell
# Windows Notebook 또는 Desktop-local producer clone
powershell -NoProfile -ExecutionPolicy Bypass -File "C:\worktrees\agent-notebook-<topic>\__patch_drop__\producer_bundle.ps1" `
  -Topic "<topic>" `
  -Node notebook `
  -SourceRoot "C:\worktrees\agent-notebook-<topic>" `
  -PatchDropRoot "Y:\__patch_drop__" `
  -PathSpec "main\java\path\Changed.java","src\test\java\path\ChangedTest.java"
```

```powershell
# Mac mini에서 pwsh 사용 시에도 동일 계약 적용
pwsh -NoProfile -File "$HOME/worktrees/agent-macmini-<topic>/__patch_drop__/producer_bundle.ps1" `
  -Topic "<topic>" `
  -Node macmini `
  -SourceRoot "/Users/<user>/worktrees/agent-macmini-<topic>" `
  -PatchDropRoot "/Volumes/NAS-WinSrc/__patch_drop__" `
  -PathSpec "main/java/path/Changed.java","src/test/java/path/ChangedTest.java"
```

If Mac mini does not have `pwsh`, use the Python helper with the same nested v3 bundle contract:

```bash
python3 "$HOME/worktrees/agent-macmini-<topic>/__patch_drop__/producer_bundle.py" \
  --topic "<topic>" \
  --node macmini \
  --source-root "$HOME/worktrees/agent-macmini-<topic>" \
  --patchdrop-root "/Volumes/NAS-WinSrc/__patch_drop__" \
  --pathspec main/java/path/Changed.java src/test/java/path/ChangedTest.java
```

Desktop promotion path:

```powershell
Set-Location "C:\AbandonWare\demo-1\demo-1\src"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\janitor_inventory.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\janitor_promote_producer_pending.ps1" `
  -Topic "<topic>" `
  -Node macmini
```

`janitor_promote_producer_pending.ps1` accepts `-Node macmini`, `-Node notebook`, or `-Node desktop`. It promotes exactly one nested producer bundle to top-level `<topic>-v3.*` only after SHA and secret-scan gates pass. The promoted manifest still keeps `desktopFinalProof=evidence_needed`; Desktop apply and Gradle verification remain separate.

Producer helpers must reject Mac mini/Notebook source roots that point at shared source paths, including Desktop canonical source, Windows mapped/UNC roots, macOS `/Volumes/...`, and Linux `/mnt/...` or `/media/...` NAS roots. These mount roots are allowed for `PatchDropRoot`, not for `SourceRoot`.

Helper를 쓸 수 없는 경우에만 아래 수동 경로를 사용한다. 수동 경로도 반드시 동일한 sidecar 세트를 제출해야 한다.

```bash
# Mac mini (macOS): worktree에서 실행
cd ~/worktrees/agent-macmini-<topic>
git diff --binary > /Volumes/WinSrc/__patch_drop__/<topic>-macmini-v3.patch
# 또는 NAS PatchDrop:
git diff --binary > /Volumes/NAS-WinSrc/__patch_drop__/<topic>-macmini-v3.patch
```

```powershell
# Notebook (Windows): NAS 연결 후
cd C:\worktrees\agent-notebook-<topic>
git diff --binary | Set-Content "\\NAS-HOST\awx-patchdrop\<topic>-notebook-v3.patch" -Encoding UTF8
```

```bash
# Notebook (NAS 연결 전): sftp/scp로 Desktop에 전송
scp <topic>-notebook-v3.patch user@DESKTOP-HOST:"C:/AbandonWare/demo-1/demo-1/src/__patch_drop__/"
```

제출 세트 (완전한 번들 기준):

```text
<topic>-<node>-v3.patch
<topic>-<node>-v3.report.md
<topic>-<node>-v3.verify.log
<topic>-<node>-v3.sha256.txt
<topic>-<node>-v3.manifest.json
```

Preferred Desktop apply path after top-level promotion:

Temp-only three-node smoke before a live external handoff:

```powershell
Set-Location "C:\AbandonWare\demo-1\demo-1\src"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\three_node_patchdrop_smoke.ps1"
```

The smoke uses fake temp Desktop/Mac mini/Notebook roots, creates producer bundles for Mac mini and Notebook, promotes exactly one Mac mini v3 patch, applies through a `desktop-consumer` lease, and reports `realPatchDropUntouched=true`.

```powershell
Set-Location "C:\AbandonWare\demo-1\demo-1\src"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\janitor_inventory.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\source_edit_session.ps1" `
  -Action begin `
  -Role desktop-consumer `
  -Root "." `
  -Topic "global-source-edit" `
  -OwnerId "desktop-codex" `
  -TtlMinutes 180
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\janitor_apply_one.ps1" `
  -PatchName "<topic>-v3.patch" `
  -SourceLeaseOwnerId "desktop-codex"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\__patch_drop__\source_edit_session.ps1" `
  -Action end `
  -Role desktop-consumer `
  -Root "." `
  -Topic "global-source-edit" `
  -OwnerId "desktop-codex"
```

Use `desktop-consumer` for applying an active top-level PatchDrop bundle. Plain `desktop` source-owner sessions stay blocked while a top-level patch is pending.

### 6.2 Desktop 검토 및 적용

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
$PatchDrop = Join-Path $Root "__patch_drop__"
Push-Location $Root

# 가장 오래된 pending patch부터 처리 (FIFO)
$pending = Get-ChildItem $PatchDrop -Filter "*.patch" -File |
    Sort-Object LastWriteTime |
    Select-Object -First 1

if (-not $pending) {
    Write-Error "[AWX][desktop] evidence_needed: no patch found"
    exit 1
}

# Secret scan (count만 출력, 값 출력 금지)
$hits = Select-String -Path $pending.FullName `
    -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|password\s*=\s*\S+|secret\s*=\s*\S+" `
    -CaseSensitive -ErrorAction SilentlyContinue
Write-Host "[AWX][desktop][security] secretPatternHits=$($hits.Count)"
if ($hits.Count -gt 0) {
    Write-Error "[AWX][desktop] secret-leak-risk: 적용 중단"
    exit 1
}

# Dry-run 확인
git apply --check $pending.FullName
if ($LASTEXITCODE -ne 0) {
    Write-Error "[AWX][desktop] git-apply-check-failed"
    exit 1
}

# 실제 적용
git apply $pending.FullName

# 이동
$Applied = Join-Path $PatchDrop "applied"
New-Item -ItemType Directory -Force -Path $Applied | Out-Null
Move-Item -Path $pending.FullName -Destination $Applied

Pop-Location
```

규칙:

- secret scan hit 시 `secret-leak-risk`로 분류하고 적용 중단.
- `git apply --check` 실패 시 적용 중단, 원인 파일/라인 보고.
- Desktop Gradle 검증 통과 후에만 `applied`로 이동.
- 복수의 pending patch가 있을 때는 제출 노드(`macmini` / `notebook`)와 topic을 명시한 뒤 순서를 결정하고, 한 번에 하나씩 적용한다.

---

## 7. 노드별 검증 명령

### Desktop 최종 검증

```powershell
$env:AWX_AGENT_HOST        = "desktop"
$env:AWX_BUILD_HOST_ID     = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:GRADLE_USER_HOME      = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache              = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCache | Out-Null

# 1단계: source set / projects
.\gradlew.bat projects --no-daemon --project-cache-dir "$ProjectCache"

# 2단계: compile + purity (test 제외)
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon --project-cache-dir "$ProjectCache"

# 3단계: app compile
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir "$ProjectCache"

# 4단계: bootJar
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir "$ProjectCache"

# 5단계: 핵심 테스트 (optional, 시간 예산 확인)
.\gradlew.bat test --tests "*Naver*" --tests "*ModelGuard*" --tests "*HybridWebSearch*" --no-daemon --project-cache-dir "$ProjectCache"
```

### Mac mini / Notebook 로컬 검증 (supporting evidence)

```bash
# worktree에서 실행 — GRADLE_USER_HOME은 노드별 독립 경로 사용
./gradlew compileJava -x test \
  --no-daemon \
  --gradle-user-home "$GRADLE_USER_HOME" \
  --project-cache-dir "$PROJECT_CACHE"
```

---

## 8. NAS 연결 전/후 Notebook 전환 절차

### NAS 연결 전 (로컬 전용)

1. Desktop에서 최신 소스를 `git bundle` 또는 bare clone으로 Notebook에 복사한다.
2. Notebook에서 로컬 worktree를 `agent/notebook/<topic>` 브랜치로 생성한다.
3. 작업 완료 후 `.patch` 세트를 sftp/scp/USB로 Desktop PatchDrop에 전달한다.
4. Desktop이 검토·적용·검증 후 결과를 Notebook에 공유한다.

```bash
# Desktop → Notebook 소스 전달 (Desktop 실행)
git -C "C:\AbandonWare\demo-1\demo-1\src" bundle create awx-notebook-transfer.bundle --all
# Notebook으로 sftp/USB 이동
```

```bash
# Notebook에서 로컬 clone 생성
git clone awx-notebook-transfer.bundle awx-notebook-src
cd awx-notebook-src
git checkout -b agent/notebook/<topic>
```

### NAS 연결 후 (NAS 경유 SMB)

1. Notebook에서 NAS 공유를 마운트한다 (읽기 전용 권한 권장).
2. NAS를 통해 Desktop canonical root를 evidence로 읽는다.
3. `.patch` 세트를 NAS PatchDrop 경로에 직접 쓴다 (NAS 쓰기 권한 필요).
4. Desktop이 NAS PatchDrop을 주기적으로 확인하거나, Notebook이 완료 시 Desktop에 알린다.

```powershell
# Notebook (Windows) NAS 마운트 예시
net use Z: \\NAS-HOST\awx-share /persistent:yes
# PatchDrop 제출
Copy-Item "<topic>-notebook-v3.patch" "Z:\__patch_drop__\"
```

```bash
# Notebook (macOS) NAS 마운트 예시
mount_smbfs //user@NAS-HOST/awx-share /Volumes/NAS-WinSrc
# PatchDrop 제출
cp <topic>-notebook-v3.patch /Volumes/NAS-WinSrc/__patch_drop__/
```

> **경로 혼동 방지**: NAS 마운트 경로는 canonical root가 아니다. canonical root는 항상 Desktop의 로컬 경로(`C:\AbandonWare\demo-1\demo-1\src`)다.

---

## 9. 실패 분류자

### SMB / 조율

| 코드 | 조건 |
|------|------|
| `index-lock-conflict` | `.git\index.lock` 존재 |
| `worktree-overlap` | Mac mini 또는 Notebook 활성 worktree가 target file과 겹침 |
| `patch-drop-pending` | PatchDrop에 미적용 `.patch` 존재 |
| `branch-ownership-mismatch` | 현재 branch가 `agent/macmini/*` 또는 `agent/notebook/*` |
| `smb-conflict-risk` | Mac mini 또는 Notebook이 원본 SMB 경로를 직접 수정 중 의심 |
| `nas-path-confusion` | Notebook이 NAS 마운트 경로를 canonical root로 착각 |
| `port-conflict` | 8080/8081 또는 노드 smoke 포트 충돌 |
| `gradle-cache-collision` | Gradle user/project cache 미분리 |
| `nas-write-permission` | NAS PatchDrop 쓰기 권한 없음 (Notebook NAS 연결 후) |
| `notebook-offline-bundle-missing` | NAS 연결 전 번들 전송 없이 Notebook이 작업 시작 |

### 빌드/소스

| 코드 | 조건 |
|------|------|
| `wrong-sourceset` | 비활성 sourceSet 수정 |
| `langchain4j-version-purity` | 1.0.1 이외 버전 감지 |
| `yaml-parse` | YAML 파싱 오류 |
| `duplicate-class-fqcn` | 같은 FQCN 중복 |
| `cannot-find-symbol` | 컴파일 심볼 오류 |
| `spring-bean` / `spring-bind` | Bean wiring / 속성 바인딩 오류 |
| `secret-leak-risk` | 패치에 secret 패턴 감지 |
| `placeholder` | 미치환 `${...}` 잔존 |

분류 후 처리:

- 충돌 분류 → source edit 즉시 중단.
- 환경/cache 분류 → 분리 설정 후 한 번만 재시도.
- source/build 분류 → evidence + 최소 patch 후보 분리 보고.

---

## 10. 노드별 제출물 형식

### Mac mini / Notebook 제출 보고서

```markdown
## Patch Producer Report
- node: macmini | notebook
- nas_connected: true | false      # Notebook에만 해당
- topic:
- branch:
- worktree:
- changed_files:
- investigation_commands:
- verification_commands:
- observed_result:
- failure_classification:
- secrets_touched: no
- nas_path_used:                   # Notebook: NAS 경로 또는 "local-only"
- desktop_apply_command:
- evidence_needed:
```

### Desktop 최종 보고서

```markdown
## 요약
- 2~5줄. 실제 수정 범위, SMB 충돌 여부, NAS/Notebook 상태, 검증 결과만.

## do01 / Observation
- 실행한 PowerShell 명령과 핵심 로그 최대 10줄.
- Git root / branch / worktree 상태.
- active sourceSets.
- SMB/NAS/PatchDrop/index.lock 상태.
- Mac mini / Notebook 활성 worktree 여부.
- missing evidence는 `evidence_needed`로 명시.

## do02 / Patch Blocks
- 파일별 Observation.
- Before snippet / After snippet / Minimal unified diff.
- 이 파일만 수정한 이유.
- Checkpoint log 추가 여부.
- Secret masking 방법.
- Rollback 방법.

## do03 / Setup Commands
- repo root에서 실행할 정확한 PowerShell 명령.
- Desktop cache isolation 포함.
- network/cache 의존 명령과 source 명령 분리.

## do04 / Verification
- Command / Expected success condition / Observed result.
- Failure classification / Retry decision.
- Remaining `evidence_needed`.

## do05 / Risks & Next Steps
- SMB 충돌 위험: H/M/L.
- NAS / Notebook 연결 상태: 연결 전 | 연결 후.
- 다음 단일 우선 patch.
- Mac mini / Notebook에 알려야 할 PatchDrop 메시지.
- confidence: L/M/H.
```

절대 말하지 말 것:

- Desktop 명령 출력 없이 "build passed".
- boot command 없이 "boot passed".
- 실제 credential path/response 없이 "provider works".
- Mac mini 또는 Notebook 검증 결과만으로 Desktop final proof.
- NAS 마운트 경로를 canonical root로 보고.

---

## 11. SourceSet Intake (편집 전 필수)

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-ChildItem -Recurse -File -Depth 4 |
  Where-Object { $_.Name -in @("settings.gradle","settings.gradle.kts","build.gradle","build.gradle.kts","gradlew","gradlew.bat") } |
  Sort-Object FullName |
  Select-Object -ExpandProperty FullName

Get-ChildItem -Recurse -Directory -Depth 6 |
  Where-Object { $_.FullName -match "(src\\main\\java_clean|src\\main\\java|main\\java)$" } |
  Sort-Object FullName |
  Select-Object -ExpandProperty FullName

Select-String -Path .\settings.gradle,.\build.gradle.kts,.\app\build.gradle.kts `
  -Pattern "sourceSets|srcDirs|java_clean|main/java|app/src|includeLegacyModules|langchain4j" `
  -ErrorAction SilentlyContinue

Pop-Location
```

판단 기준:

- root active backend: `main/java`, `main/resources`.
- `:app` active owner: `app/src/main/java_clean`, `app/src/main/resources`.
- `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, archives, backups, generated output은 Gradle evidence 없으면 inactive/reference.

---

## 12. 노드 추가/제거 시 체크리스트

노드를 추가할 때:

1. 새 노드용 포트 대역 할당 (겹치지 않게).
2. `AWX_AGENT_HOST` / `AWX_BUILD_HOST_ID` 고유값 지정.
3. `GRADLE_USER_HOME` 독립 경로 생성.
4. worktree 브랜치 네임스페이스 확정: `agent/<node-name>/<topic>`.
5. PatchDrop 제출 경로 확인 (NAS 경유 여부).
6. SMB 마운트 권한 읽기 전용 검증.

노드를 제거할 때:

1. 해당 노드의 활성 worktree가 없는지 `git worktree list`로 확인.
2. PatchDrop에 해당 노드의 미적용 patch가 없는지 확인.
3. 포트 대역과 Gradle cache 디렉터리를 정리한다.
