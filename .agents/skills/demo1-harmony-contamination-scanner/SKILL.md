---
name: demo1-harmony-contamination-scanner
description: Use when scoring demo-1 Dynamic RAG harmony health, contamination risk, HB-01 through HB-12 status, silent catch ratio, FQCN duplicates, cross-subsystem files, or before/after Safe Patch score reports.
---

# Demo1 Harmony Contamination Scanner

## Purpose

Produce a source-backed harmony score for the demo-1 Desktop canonical root before, during, or after autonomous Safe Patch work. Treat prior scores as a baseline only; recompute from the live active sourceSet.

## Intake

Run from `C:\AbandonWare\demo-1\demo-1\src`. Prefer active roots:

- `main/java`
- `main/resources`
- `src/test/java`
- `app/src/main/java_clean`
- `app/src/main/resources`

If Git metadata is absent, report that as evidence state and continue with filesystem and Gradle checks.

## Metrics

Collect these low-risk metrics:

- `silentCatchRatio`: exact empty catch blocks and catch-without-breadcrumb count divided by catch count.
- `duplicateFqcn`: duplicate fully-qualified Java type names in active roots only.
- `crossSubsystemFiles`: files that reference two or more S01-S08 subsystem keywords.
- `traceCoverage`: required TraceStore keys present in active seams.
- `harmonyBreaks`: HB-01 through HB-12 status with source evidence.
- `secretPatternHits`: count only; never print matched values.

Subsystem keywords:

| ID | Keywords |
| --- | --- |
| S01 | Overdrive, Anchor, DynamicContextCompressor |
| S02 | CFVM, RawMatrixBuffer, RawTile, RetrievalOrder |
| S03 | MoE, ArtPlate, RgbStrategySelector |
| S04 | Matryoshka, ZCA, Whitening, Embedding |
| S05 | ExtremeZ, CancelShield, TimeBudget |
| S06 | HYPERNOVA, TWPM, CVaR, Risk-K, DPP |
| S07 | CIH-RAG, MLA, Breadcrumb, IQR |
| S08 | LangChain4j, OpenAI adapter, VersionPurity |

## Score

Start from 100 and subtract open break scores:

| Break | Score |
| --- | ---: |
| HB-01 silent catch contamination | 35.6 |
| HB-02 RetrievalOrderService stub | 32.0 |
| HB-03 booster trigger conflict | 24.0 |
| HB-04 DPP not integrated into HYPERNOVA | 22.4 |
| HB-05 inline TWPM duplicate | 21.0 |
| HB-06 mixed source score scale | 20.0 |
| HB-07 CancelShield interrupt propagation | 18.0 |
| HB-08 CFVM Boltzmann temperature split | 16.8 |
| HB-09 CfvmRawTileBuilder always disabled | 14.0 |
| HB-10 MoE evolver promptTemplate bypass risk | 12.0 |
| HB-11 TimeBudgetGuard missing trace | 11.2 |
| HB-12 ZCA whitening provider contamination | 10.5 |

Clamp final score to `[0, 100]`. Mark a break `DONE` only when a source contract and focused test prove the fix.

## Output

Emit a compact JSON file or report:

```json
{
  "overallScore": 0.0,
  "silentCatchRatio": 0.0,
  "duplicateFqcn": 0,
  "crossSubsystemFiles": 0,
  "secretPatternHits": 0,
  "harmonyBreaks": [
    {"id": "HB-01", "score": 35.6, "status": "OPEN", "evidence": "file:line or evidence_needed"}
  ]
}
```

## Verification

Use the narrowest useful proof first, then run:

```powershell
$env:AWX_AGENT_HOST='desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop-codex'
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle-awx-desktop"
$pcd="$env:LOCALAPPDATA\awx-gradle-project-cache\desktop-codex"
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test --no-daemon --project-cache-dir $pcd
```

Secret scan must print counts only.
