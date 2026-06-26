---
name: demo1-cross-subsystem-guard
description: Use when a demo-1 patch touches StrategyConflictResolver, ExecutionPlan, ExtremeZ, CFVM, MoE, ArtPlate, HYPERNOVA, ZCA, PromptBuilder, AutoConfiguration, or two or more S01-S08 Dynamic RAG subsystems.
---

# Demo1 Cross Subsystem Guard

## Purpose

Prevent a Safe Patch in one Dynamic RAG subsystem from silently breaking another. Use this before editing shared orchestration files and before claiming a multi-subsystem patch is complete.

## Stop Rules

- Do not modify two or more cross-subsystem files in one patch cycle unless the user explicitly asks or a compile/runtime blocker proves it necessary.
- Do not patch inactive mirrors. Prove active sourceSet ownership first.
- Do not bypass `PromptBuilder.build(PromptContext)`.
- Do not introduce raw secret, prompt, query, or Authorization values into logs, TraceStore, SSE, HTML, or tests.
- Do not proceed if a required focused test for an affected subsystem is missing; report `evidence_needed`.

## Impact Map

| File pattern | Required focused tests |
| --- | --- |
| `StrategyConflictResolver.java` | `*StrategyConflict*`, `*ExtremeZ*`, `*Overdrive*`, `*Hypernova*` |
| `ExecutionPlan*.java` | all booster mode and strategy conflict tests |
| `ExtremeZ*` | `*ExtremeZ*`, `*CancelShield*`, `*TimeBudget*` |
| `RawSlotExtractor.java`, `RawMatrixBuffer.java`, `Cfvm*` | `*Cfvm*`, `*RawMatrix*`, `*RetrievalOrder*` |
| `NineArtPlateGate.java`, `ArtPlateEvolver.java`, `RgbStrategySelector.java` | `*ArtPlate*`, `*MoE*`, `*RgbStrategy*` |
| `NovaNextFusionService.java` | `*NovaNextFusion*`, `*TailWeighted*`, `*DppDiversity*`, `*CvarAgg*` |
| `LowRankWhiteningTransform.java`, embedding normalizers | `*Whitening*`, `*Matryoshka*`, `*EmbeddingFallback*` |
| `Nova*AutoConfiguration.java`, `AutoConfiguration.imports` | `compileJava`, `:app:classes`, context tests if present |

## Required Trace Keys

For relevant patches, preserve or add low-cardinality breadcrumbs:

- `boosterMode.active`
- `retrievalOrder.lastSetBy`
- `extremeZ.cancelShieldWrapped`
- `extremeZ.timeBudgetConsumedMs`
- `hypernova.cvarPhi`
- `hypernova.dppApplied`
- `hypernova.sourceScoreScaleMismatchCount`
- `cihRag.breadcrumb.queryRedacted`
- `moe.evolverPlateRegistered`
- `cfvm.boltzmannTemp`
- `cfvm.tempSource`
- `hypernova.whitening.provider`

## Workflow

1. List changed target files and map each to S01-S08.
2. If two or more subsystems are affected, write the focused test matrix before editing.
3. Patch the smallest confirmed seam.
4. Run focused tests for every affected subsystem.
5. Run broad gates:

```powershell
$env:AWX_AGENT_HOST='desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop-codex'
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle-awx-desktop"
$pcd="$env:LOCALAPPDATA\awx-gradle-project-cache\desktop-codex"
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test --no-daemon --project-cache-dir $pcd
```

6. Run an active-root secret scan that reports count only.

## Report

Return:

- files changed,
- affected subsystem IDs,
- focused tests run,
- broad gates run,
- secret count,
- remaining `evidence_needed`.
