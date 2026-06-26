# demo-1 Context Contamination Scout Safe Patch Directive

Workspace: `C:\AbandonWare\demo-1\demo-1\src`
Mode: Desktop canonical root, Safe Patch, source modification directive.

이 지시서는 `스터프2`의 운영 생태계 아이디어를 실제 demo-1 소스수정으로 옮길 때,
`스터프1`처럼 도메인 밖 대화 로그가 RAG/AutoLearn/vector/prompt 컨텍스트에 섞였는지
정찰하고 빠르게 쳐내기 위한 Codex 지시서다.

`스터프2`는 아이디어/위험지도다. 실제 수정 여부는 현재 repo 파일, Gradle sourceSet,
호출처, 테스트 출력이 결정한다. `스터프1`의 사적 대화 원문은 테스트 fixture 또는 로그에
그대로 저장하지 말고, 해시/카운트/짧은 합성 샘플로만 다룬다.

## 0. 관측된 오염 패턴

첨부 `pasted-text.txt` 기준 현재 관측:

- 전체 2158줄, `{스터프1}` 시작 line 2089.
- `{스터프1}` 구간 70줄 중 chat header 패턴 66줄.
- media marker `사진`/`동영상` 11줄.
- aquarium/out-of-domain marker `우렁|어항|수초|나나|체리새우|메다카|소일|스피룰리나|재첩` 25줄.
- commerce/source marker `스마트스토어|출처 :` 1줄.

오염 signature:

```text
CHAT_EXPORT_HEADER: ^\[[^\]]+/[^\]]+\] \[(오전|오후) [0-9]+:[0-9]+\]
PRIVATE_CHAT_ALIAS_LOCATION: [닉네임/지역] 형태
MEDIA_PLACEHOLDER: 사진|동영상|사진 [0-9]+장
OUT_OF_DOMAIN_AQUARIUM: 우렁|어항|수초|나나|체리새우|메다카|소일|스피룰리나|재첩
COMMERCE_SNIPPET: 스마트스토어|출처 :
MOJIBAKE_EVIDENCE: 깨진 한글이 섞인 동일 입력 재해석 흔적
```

Primary failure class:

```text
history_context_contamination
```

Secondary classes:

```text
private-chat-transcript
domain-drift
media-placeholder-no-evidence
commerce-snippet-contamination
encoding-mojibake-risk
autolearn-dataset-poison-risk
vector-memory-poison-risk
prompt-context-drift
```

## 1. 절대 불변 규칙

- Desktop은 canonical source owner/final verifier다.
- Mac mini/Notebook 결과는 PatchDrop evidence일 뿐이며 최종 proof가 아니다.
- active sourceSet만 수정한다: root `main/java`, `main/resources`, tests `src/test/java`.
- `:app` 수정은 `app/src/main/java_clean`, `app/src/main/resources`가 live evidence로 필요할 때만 한다.
- `app/src/main/java`, `project/src/main/java`, archives, backups, generated output은 inactive/reference다.
- `apikey.txt`, `apikey.ps1`, `.env*`, shell profile, real secret, `openssl`, `opnessl`를 수정하지 않는다.
- LangChain4j는 반드시 `1.0.1` 순수성을 유지한다.
- raw chat transcript, raw user query, raw prompt, private nickname/location, URL token, Authorization header를 로그/TraceStore/UI/test report에 남기지 않는다.
- final prompt construction은 `PromptBuilder.build(PromptContext)` boundary를 우회하지 않는다.
- 새 context-cleaner framework, duplicate wrapper, duplicate route, duplicate vector store를 만들지 않는다.
- 이미 존재하는 `VectorPoisonGuard`, `ContextContaminationAnalyzer`, `UawDatasetTrainingDataFilter`, `TrainRagIngestService`, `VectorIngestProtectionService`, `PromptBuilder`, `TraceStore`, `DebugEventStore` seam을 우선 확장한다.

## 2. Intake Commands

PowerShell, repo root에서 실행:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
git branch --show-current 2>$null
git worktree list 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") { Write-Error "[AWX][context-scout] index-lock-present"; exit 1 }

$Env:AWX_AGENT_HOST = "desktop"
$Env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$Env:AWX_BUILD_HOST_ID = "desktop"
$Env:GRADLE_USER_HOME = "$Env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$Env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $Env:GRADLE_USER_HOME,$pcd | Out-Null

.\gradlew.bat projects --no-daemon --project-cache-dir $pcd
Select-String -Path "build.gradle.kts","app\build.gradle.kts","settings.gradle" `
  -Pattern "sourceSets|srcDirs|java_clean|langchain4j|checkLangchain4jVersionPurity" `
  -Context 0,2

if (Test-Path "__patch_drop__\janitor_inventory.ps1") {
  powershell -NoProfile -ExecutionPolicy Bypass -File "__patch_drop__\janitor_inventory.ps1"
}

Pop-Location
```

Stop conditions:

- `.git\index.lock` exists -> `index-lock-conflict`.
- top-level PatchDrop `.patch` pending -> `patch-drop-pending`.
- current branch starts with `agent/macmini/` -> `branch-ownership-mismatch`.
- `gradlew.bat projects` fails before project evaluation -> classify exact Gradle failure and do not edit source.

## 3. Decomposition Decision

Default mode for this task: `3-way`.

```md
Decomposition decision:
- mode: 3-way
- reason: user asked for scouts; source impact spans prompt, vector ingest, and AutoLearn dataset gates.
- skipped_axes: none
```

If the current failure is already localized to one file/test, use `direct` and record why.

## 4. Scout A — Transcript Signature Scout

Goal: identify the pollution fingerprint without retaining private content.

Inspect:

- `main/java/com/example/lms/service/guard/VectorPoisonGuard.java`
- `main/java/com/example/lms/service/rag/langgraph/ContextContaminationAnalyzer.java`
- `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java`
- `src/test/java/**/ContextContamination*Test.java`
- `src/test/java/**/VectorPoison*Test.java`

Patch only if live source lacks deterministic detection for private chat transcript patterns.

Minimal patch candidate:

1. Add small compiled patterns to `VectorPoisonGuard`:
   - `CHAT_EXPORT_HEADER`
   - `MEDIA_PLACEHOLDER`
   - `OUT_OF_DOMAIN_AQUARIUM`
   - optional `COMMERCE_SNIPPET`
2. Add a `private_chat_transcript` reason when:
   - chat header count >= 3, or
   - chat header ratio >= 0.35 and any media/aquarium marker exists.
3. At ingest, block by default:
   - `META_DOC_TYPE=CHAT_TRANSCRIPT`
   - `META_POISON_REASON=private_chat_transcript`
   - `allow=false`
4. At retrieval, treat existing segments with this metadata or content pattern as poisonous.
5. Trace only counts and reason:
   - `vector.poison.privateChat.lines`
   - `vector.poison.privateChat.headers`
   - `vector.poison.privateChat.domainMarkers`
   - `vector.poison.privateChat.reason`
   - never store raw nicknames, regions, message text, or URL body.

Test contract:

- New or extended `VectorPoisonGuardTest`.
- Use synthetic fixture, not real transcript:

```text
[UserA/RegionA] [오후 4:09] synthetic aquarium note
[UserB/RegionB] [오후 4:10] 사진
[UserA/RegionA] [오후 4:11] 어항 수초 synthetic
```

Assertions:

- ingest decision blocks.
- reason is `private_chat_transcript`.
- metadata has `CHAT_TRANSCRIPT` or poison reason.
- `TraceStore.getAll()` does not contain `UserA`, `RegionA`, or raw message text.

Do not:

- hardcode real names from `스터프1`.
- add a new sanitizer service unless existing seams cannot safely host the pattern.

## 5. Scout B — AutoLearn Dataset Purity Scout

Goal: prevent contaminated conversation fragments from becoming training or vector-shadow memory.

Inspect:

- `main/java/com/example/lms/uaw/autolearn/UawDatasetTrainingDataFilter.java`
- `main/java/com/example/lms/uaw/autolearn/UawDatasetFilterProperties.java`
- `main/java/com/example/lms/uaw/autolearn/LearningSampleValidationMetadataBuilder.java`
- `main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`
- `main/resources/application.yml`
- `main/resources/application-llm.yaml`
- tests under `src/test/java/com/example/lms/uaw/autolearn/**`

Patch only if current rules cannot exclude chat transcript/domain-drift samples.

Minimal patch candidate:

1. Add dataset-filter hard EXCLUDE rules in `main/resources/application.yml` under existing `uaw.autolearn.dataset-filter`:
   - rule `private-chat-transcript`
   - scope `question_answer`
   - group `context-purity`
   - priority high enough to beat broad allow rules
   - regex uses structural/synthetic markers, not private content.
2. If the YAML rule system cannot express ratio/count logic, add a private helper in `UawDatasetTrainingDataFilter` that checks the same synthetic patterns before allow overrides.
3. In `LearningSampleValidationMetadataBuilder`, map this condition to:
   - reject reason `private_chat_transcript_context`
   - contamination signal `history_context_contamination`
   - vector decision `QUARANTINE`
   - requery required but not confirmed.
4. In `TrainRagIngestService`, keep raw projection quarantined:
   - no raw question/answer projection unless validation accepted and contamination below threshold.
   - contaminated samples remain metadata-only or skipped, depending existing projection mode.

Trace/metrics:

- `learning.validation.contaminationSignals` contains `private_chat_transcript`.
- `uaw.autolearn.dataset_filter.exclude_rule_total{rule=private-chat-transcript}` increments.
- no raw content in logs.

Test contract:

- Extend `UawDatasetTrainingDataFilterTest` or add focused test.
- Extend `LearningSampleValidationMetadataBuilderTest`.
- Extend `TrainRagIngestServiceTest` only if projection behavior changes.
- Tests use synthetic chat lines and assert no raw alias/region leaks.

## 6. Scout C — Prompt/Context Assembly Scout

Goal: stop contaminated history from reaching final LLM prompt, while preserving `PromptBuilder.build(PromptContext)`.

Inspect:

- `main/java/com/example/lms/prompt/PromptBuilder.java`
- `main/java/com/example/lms/prompt/StandardPromptBuilder.java`
- `main/java/com/example/lms/prompt/PromptContext.java`
- `main/java/com/example/lms/service/rag/langgraph/ContextContaminationAnalyzer.java`
- `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java`
- `main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java`
- tests under `src/test/java/com/example/lms/prompt/**` and `src/test/java/com/example/lms/service/rag/langgraph/**`

Patch only if contaminated snippets can enter prompt context without a redacted/drop decision.

Minimal patch candidate:

1. Extend existing contamination analyzer or compressor scoring to recognize:
   - `private_chat_transcript`
   - `domain_drift_aquarium`
   - `media_placeholder_no_evidence`
2. When score crosses threshold:
   - drop or quarantine the offending snippet before prompt assembly,
   - set `prompt.builder.contaminationFlag=true`,
   - set `prompt.builder.contaminationReason=private_chat_transcript` or a safe label,
   - preserve normal answer path with clean remaining evidence.
3. Keep final prompt built only through `promptBuilder.build(promptContext)`.
4. If all snippets are dropped, fail soft with explicit reason:
   - `context_filtered_all`
   - `evidence_needed: clean source evidence / verify with focused query`.

Test contract:

- `PromptBuilderBoundaryTest` remains green.
- Add/extend contamination test:
   - contaminated synthetic chat snippet does not appear in built prompt.
   - `prompt.builder.contaminationFlag` is true.
   - prompt still contains clean evidence if available.
   - raw alias/region/message not present in `TraceStore`.

## 7. Scout D — Encoding/Mojibake Scout

Goal: detect decoded-garbage variants of the same contamination.

Use only if a current file/log read shows mojibake.

Inspect:

- `VectorPoisonGuard`
- `ContextContaminationAnalyzer`
- any existing `Encoding UTF8` or mojibake tests.

Patch only a small heuristic:

- high ratio of replacement/mojibake tokens around Korean chat header-like text -> `encoding_mojibake_risk`.
- quarantine/block with count/hash only.

Do not normalize user content globally. Do not rewrite stored training files during this pass.

## 8. Patch Priority Board

| Priority | Candidate | Patch Target | Why |
|---|---|---|---|
| P0 | private chat transcript block | `VectorPoisonGuard` + test | Directly prevents vector memory poison. |
| P0 | AutoLearn hard exclude | dataset filter config or `UawDatasetTrainingDataFilter` + tests | Prevents train_rag/data handoff contamination. |
| P1 | prompt/context drop reason | `ContextContaminationAnalyzer` or compressor + prompt tests | Prevents final prompt drift. |
| P1 | metadata-only quarantine proof | `TrainRagIngestService` tests | Proves contaminated samples are not raw-ingested. |
| P2 | mojibake heuristic | existing guard/analyzer | Only after real mojibake input is observed in live path. |

## 9. Verification Commands

Run focused tests first:

```powershell
$Env:AWX_AGENT_HOST = "desktop"
$Env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$Env:AWX_BUILD_HOST_ID = "desktop"
$Env:GRADLE_USER_HOME = "$Env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$Env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $Env:GRADLE_USER_HOME,$pcd | Out-Null

.\gradlew.bat test --tests "*VectorPoisonGuard*" --no-daemon --project-cache-dir $pcd
.\gradlew.bat test --tests "*UawDatasetTrainingDataFilter*" --tests "*LearningSampleValidationMetadataBuilder*" --no-daemon --project-cache-dir $pcd
.\gradlew.bat test --tests "*ContextContamination*" --tests "*PromptBuilderBoundary*" --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

If prompt-pack files are edited:

```powershell
python agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_context_contamination_scout_directive
```

Secret scan count only:

```powershell
$hits = Select-String -Path ".\main\java\**\*.java",".\src\test\java\**\*.java",".\main\resources\**\*.yml",".\agent-prompts\**\*.md" `
  -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" `
  -Recurse -EA SilentlyContinue
Write-Host "[AWX][context-scout][security] secretHits=$($hits.Count)"
```

## 10. Final Report Format

```md
## 요약
- 실제 수정 범위와 검증 상태.
- `스터프1` 오염 여부: confirmed | not found | evidence_needed.
- raw transcript 저장 여부: 반드시 no.

## Observation
- repo root / branch / active sourceSets.
- Decomposition decision.
- scout별 핵심 evidence.
- PatchDrop/Mac mini evidence 사용 여부.
- evidence_needed.

## Patch
- 파일별 최소 diff 요약.
- 왜 기존 seam만 수정했는지.
- secret/raw transcript masking 방법.
- rollback note.

## Verification
- Command / Expected / Observed / Failure classification / Retry decision.

## Risks & Next
- 남은 위험 최대 5개.
- SMB 충돌 위험 H/M/L.
- confidence L/M/H.
- next single most urgent patch.
```

## 11. Acceptance Gate

Accept only if:

- synthetic private chat transcript is blocked or quarantined.
- AutoLearn/training path excludes or quarantines contaminated sample.
- prompt path drops/quarantines contaminated snippet without bypassing `PromptBuilder`.
- raw transcript/private alias/location/message does not appear in logs, TraceStore, generated reports, or tests.
- focused tests and sourceSet hygiene pass or any failure is honestly classified.

Reject if:

- real `스터프1` lines are committed as fixtures.
- a new duplicate sanitizer architecture is added.
- only UI text is changed while vector/AutoLearn/prompt gates remain unprotected.
- broad refactor touches unrelated RAG/provider behavior.
- success is claimed without command output.
