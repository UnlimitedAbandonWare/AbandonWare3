---
name: demo1-autonomous-patch-conductor
description: Use when Codex or an Antigravity agent must run a multi-hour autonomous safe-patch session on the demo-1 Dynamic RAG platform, cycling through a scored patch backlog, verifying each patch with Gradle, updating the ledger, and stopping only when all P0 hard gates pass or 9 hours elapse. Enforces sourceSet, FQCN, PromptBuilder, secret, and version purity rules throughout.
---

# demo1 Autonomous Patch Conductor

> **목적**: 다중 시간(예: 9시간) 자율 패치 세션을 안전하게 실행한다.  
> 한 사이클에 하나의 파일 세트만 수정한다. 패치 실패 시 즉시 역복구(revert 또는 대체 구현) 후 다음 후보로 넘어간다.  
> 목표 점수(9x)에 도달하거나 시간이 소진되면 세션을 닫고 최종 보고서를 작성한다.

---

## 0. 비협상 규칙 (전 사이클 공통)

| 항목 | 규칙 |
|------|------|
| 버전 | `dev.langchain4j:*` = `1.0.1` 고정. beta/RC 혼입 즉시 롤백 |
| 프롬프트 | `PromptBuilder.build(PromptContext)` 경유만. `setFinalPromptText()` 호출 감지 시 중단 |
| 시크릿 | API key/token/secret 값 로그·trace·SSE 출력 금지. `rg` secret scan 0건 미달 시 중단 |
| catch | `catch(Exception e){}` 금지. `SafeRedactor.traceLabelOrFallback` + breadcrumb 필수 |
| FQCN | simple name만 보고 병합 금지. package + FQCN 기준으로 확인 |
| 소스루트 | `main/java`, `main/resources` 우선. 다른 루트는 Gradle 증거 필요 |
| 파일 전체 재작성 | 금지. 최소 diff만 적용 |

---

## 1. 세션 시작 체크리스트

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

# 0. 인덱스 락 확인
if (Test-Path ".git\index.lock") { Write-Error "[AWX] index-lock-conflict"; exit 1 }

# 1. PatchDrop 대기 패치 없는지 확인
$pending = Get-ChildItem "__patch_drop__" -Filter "*.patch" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'applied|rejected|superseded' }
if ($pending) { Write-Warning "[AWX] patch-drop-pending: $($pending.Name)"; exit 1 }

# 2. 기준 컴파일
$pcd = "$Env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $pcd | Out-Null
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd

# 3. 기준 sourceScoreReport
.\gradlew.bat sourceScoreReport --no-daemon --project-cache-dir $pcd 2>&1 | Tee-Object "$Root\build-logs\baseline-score.log"
```

---

## 2. 패치 사이클 루프

각 사이클에서:

### 2-a. 후보 선택
- 이 스킬 아래 `patch_backlog.yaml` 또는 현재 지시서의 Patch Backlog에서 **첫 번째 OPEN** 항목 선택.
- 항목 구조: `id`, `priority`, `status` (OPEN/DONE/SKIP), `target_files`, `trace_keys_required`, `test_pattern`, `risk`.

### 2-b. 패치 적용
- 대상 파일만 최소 diff 적용.
- TraceStore 키 추가 시 `SafeRedactor.traceLabelOrFallback` 통과 확인.
- 프롬프트 조립 경로 미개입 확인.

### 2-c. 검증 (통과 기준)
```powershell
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat test --no-daemon --project-cache-dir $pcd --tests $testPattern
.\gradlew.bat checkLangchain4jVersionPurity --no-daemon --project-cache-dir $pcd
$hits = Get-ChildItem build,logs -Filter "*.log","*.txt" -Recurse -ErrorAction SilentlyContinue |
        Select-String 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}' 2>$null
if (@($hits).Count -gt 0) { Write-Error "[AWX][security] secretPatternHits>0"; exit 1 }
```

검증 통과 → 백로그 항목 `status: DONE` 표시, 다음 사이클.  
검증 실패 → 패치 역복구, 항목 `status: SKIP` + `failReason` 기록, 다음 사이클.

### 2-d. 점수 확인 (매 3사이클)
```powershell
.\gradlew.bat sourceScoreReport --no-daemon --project-cache-dir $pcd 2>&1 |
  Select-String 'Total Score' | Select-Object -Last 1
```
목표 점수(≥90) 도달 시 세션 완료.

---

## 3. 세션 종료 보고서 형식

```markdown
## 자율 패치 세션 완료 보고

### 세션 요약
- 시작: <timestamp>
- 종료: <timestamp>
- 경과: <HH:MM:SS>
- 적용 사이클: N
- 최종 sourceScoreReport: X / 100
- 목표 달성: YES / NO (reason)

### 완료 패치 목록
| id | priority | target_files | trace_keys_added | test_result |
|---|---|---|---|---|

### 스킵 목록
| id | failReason |
|---|---|

### 잔존 blind spot
- ...

### 다음 세션 권장 사항
- ...
```

---

## 4. Hard Gate 최종 점검

세션 종료 전 필수:
```powershell
.\gradlew.bat :app:classes -x test bootJar -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat janitor_tests -Suite CoreGuards 2>$null
# secret scan
$hits = Get-ChildItem "main\java","main\resources" -Filter "*.java","*.yml","*.yaml","*.properties" -Recurse |
        Select-String 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}' 2>$null
Write-Host "[AWX][security] activeMainSecretHits=$(@($hits).Count)"
```

---

## 5. 패치 백로그 관리

백로그 파일: `C:\AbandonWare\demo-1\demo-1\src\agent-prompts\data\patch_backlog.yaml`  
없으면 이 스킬의 지시서에 인라인으로 기재된 Patch Backlog를 사용한다.  
각 사이클 후 YAML을 업데이트하거나, 인라인 상태를 갱신한다.

---

## 6. 금지 사항

- 한 사이클에 4개 이상의 파일 수정 (긴급 wiring 수정 제외).
- `bootRun`을 같은 호스트에 동시 2개 이상 실행.
- sourceScoreReport가 stale 아티팩트를 현재 증거로 사용.
- 패치 없이 `status: DONE` 표시.
- 실패 테스트를 무시하고 다음 사이클 진행.
