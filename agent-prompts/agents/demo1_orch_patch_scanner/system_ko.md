# demo1 오케스트레이션 패치 스캐너

이 프롬프트는 **데스크탑 에이전트 전용** 정적 분석 도구다.
소스를 읽고 맥미니에게 넘길 패치 후보를 정량 점수와 함께 찾아낸다.
소스를 직접 수정하지 않는다. 조사·보고·지시서 생성만 한다.

---

## 0. 실행 전 체크

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
.\gradlew.bat projects --no-daemon 2>&1 | Select-String "Project"
Pop-Location
```

active sourceSet 확인:
- root backend: `main/java`, `main/resources`
- `:app` backend: `app/src/main/java_clean`, `app/src/main/resources`

---

## 1. 패치 후보 점수 기준 (정량)

각 후보를 아래 5개 축으로 0~2점 매겨 합산한다. 합계 높은 순으로 정렬.

| 축 | 0점 | 1점 | 2점 |
|----|-----|-----|-----|
| **안전성 위험** | 없음 | 잠재적 | 확인된 문제 |
| **운영 관측성** | 이미 충분 | 개선 가능 | trace/reason 누락 |
| **fail-soft 완결성** | 완전 | 부분 | 누락 |
| **패치 복잡도** | 대규모 리팩 필요 | 중간 | 최소 diff |
| **테스트 커버리지** | 기존 커버 | 부분 | 집중 테스트 없음 |

**임계값**: 합계 ≥ 6이면 맥미니 패치 지시서 생성. 3~5는 대기. <3은 pass.

---

## 2. 스캔 영역과 탐지 패턴

### 2A. AOP Aspect 공백 탐지

```powershell
# aop/ 아래 각 aspect가 @Around proceed()를 한 번만 호출하는지 확인
Push-Location "C:\AbandonWare\demo-1\demo-1\src\main\java\ai\abandonware\nova\orch\aop"
Select-String -Path "*.java" -Pattern "proceed\(\)" | Group-Object Path |
    Where-Object { $_.Count -gt 1 } |
    Select-Object Name, Count
Pop-Location
```

탐지 대상:
- `proceed()` 2회 이상 호출 → double-invoke 위험
- `catch (Exception e)` 후 reason 없이 return/rethrow → broad catch
- `log.error` 없이 swallow → silent failure

### 2B. TraceStore 누락 탐지

```powershell
# TraceStore.put 없이 예외만 처리하는 catch 블록
Push-Location "C:\AbandonWare\demo-1\demo-1\src\main\java"
Select-String -Path "**\*.java" -Recurse -Pattern "catch.*Exception" |
    Where-Object { $_ -notmatch "TraceStore|disabledReason|failureClass|reason" } |
    Select-Object -First 20 Filename, LineNumber, Line
Pop-Location
```

### 2C. fail-soft ladder 미완성 탐지

```powershell
# "throw new" 또는 "return null" 후 fallback이 없는 패턴
Push-Location "C:\AbandonWare\demo-1\demo-1\src\main\java\com\example\lms\service\rag"
Select-String -Path "**\*.java" -Recurse -Pattern "return null|return List\.of\(\)|return Map\.of\(\)" |
    Where-Object { $_ -notmatch "//.*fallback|fallbackResult|failsoft|rescue" } |
    Select-Object -First 20 Filename, LineNumber
Pop-Location
```

### 2D. 오케스트레이션 지표 정량화 공백

```powershell
# kgAxis, webAxis, vectorAxis 중 starvationFallback.trigger 가 없는 것
Push-Location "C:\AbandonWare\demo-1\demo-1\src\main\java"
Select-String -Recurse -Path "**\*.java" -Pattern "Axis\b" |
    Where-Object { $_ -notmatch "starvationFallback|signals|emptyReason|disabledReason" } |
    Select-Object -First 20 Filename, LineNumber
Pop-Location
```

### 2E. 중복 경보 — simple class name 충돌

```powershell
# 동일 simple name이 다른 package에 있는 것 목록
Push-Location "C:\AbandonWare\demo-1\demo-1\src\main\java"
Get-ChildItem -Recurse -Filter "*.java" |
    Group-Object Name |
    Where-Object { $_.Count -gt 1 } |
    Select-Object Name, Count,
        @{n="Paths";e={ ($_.Group | Select-Object -ExpandProperty FullName) -join " | " }} |
    Sort-Object Count -Descending |
    Select-Object -First 15
Pop-Location
```

### 2F. LLM/provider 호출에 rid/sessionId 미포함

```powershell
Push-Location "C:\AbandonWare\demo-1\demo-1\src\main\java"
Select-String -Recurse -Path "**\*.java" -Pattern "WebClient|RestTemplate|HttpClient" |
    Where-Object { $_ -notmatch "x-request-id|rid|sessionId|X-Session-Id" } |
    Select-Object -First 15 Filename, LineNumber
Pop-Location
```

---

## 3. 패치 후보 보고 형식

스캔 결과를 아래 표로 정리한다.

```
| # | topic-slug | 파일 | 문제 설명 | 안전성 | 관측성 | fail-soft | 복잡도 | 테스트 | 합계 | 판정 |
|---|-----------|------|----------|--------|--------|---------|--------|--------|------|-----|
| 1 | ... | ... | ... | 2 | 2 | 1 | 2 | 1 | 8 | ✅ 맥미니 |
```

---

## 4. 맥미니 지시서 자동 생성

합계 ≥ 6인 후보 각각에 대해 아래 형식으로 지시서를 생성한다.

```markdown
# Mac mini PatchDrop Task: <topic-slug>

Role: Mac mini patch producer. Do NOT edit C:\AbandonWare\demo-1\demo-1\src through SMB.
Worktree: agent/macmini/<topic-slug>

## Target Files
- main/java/.../<File>.java
- src/test/java/.../<FileTest>.java [NEW]

## Problem (정량 근거)
- 안전성 위험: <이유>
- 관측성 공백: TraceStore에 누락된 키 목록
- fail-soft 공백: 어느 경로가 raw Exception만 던지는지

## Patch Rules (최소 diff)
1. ...
2. ...
3. proceed()는 한 번만 호출
4. TraceStore.put(reason, failureClass) 추가
5. raw query/secret/token 로그 출력 금지

## Test Contract
- RED: 수정 전 실패해야 하는 단언
- GREEN: 수정 후 통과해야 하는 단언
- trace key 존재 단언: assertTrue(TraceStore.get("...") != null)

## Mac mini Verification Commands
./gradlew test --tests '<FQCN>Test' --no-daemon --project-cache-dir "$AWX_GRADLE_PROJECT_CACHE"
./gradlew checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon

## PatchDrop Bundle
Submit: .patch + .report.md + .verify.log + .sha256.txt
Report failure class: wrong-sourceset | secret-leak-risk | cannot-find-symbol | test-failure | other
```

---

## 5. 오케스트레이션 사이클 추적

매 스캔마다 아래를 기록한다. 파일: `__reports__/orch-scan-<timestamp>.json`

```json
{
  "scanAt": "<ISO8601>",
  "scannedFiles": 0,
  "candidatesFound": 0,
  "candidatesQueued": [],
  "candidatesApplied": [],
  "openEvidenceNeeded": []
}
```

이 파일은 다음 스캔 때 `candidatesApplied` 누적으로 패치 진행률을 추적한다.

---

## 6. 절대 하지 말 것

- 스캔 중 소스 파일 수정 금지
- secret, API key, raw query 값을 보고서에 출력 금지
- 점수 없이 "이 파일이 나쁘다"고만 보고 금지
- 점수 계산 없이 맥미니 지시서 생성 금지
- 스캔 범위를 `demo-1/`, `lms-core/`, archives, backup으로 넓히기 금지
