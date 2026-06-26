---
name: demo1-ablation-harmony-tracker
description: >
  Use after quantitative metric normalization and before code patches that touch
  Dynamic RAG booster harmony, retrieval-order authority, ExtremeZ cancellation,
  HYPERNOVA score fusion, MLA breadcrumbs, ArtPlateEvolver registration, or CFVM
  Boltzmann temperature behavior. Produces a source-backed harmony break ledger,
  required TraceStore keys, and a Safe Patch directive for demo-1 Desktop.
---

# Demo-1 Ablation Harmony Tracker

This skill turns an ablation contribution report into a source-backed patch
directive. Its job is to find where independent Dynamic RAG subsystems stop
behaving harmoniously when combined, then require trace keys and minimal tests
that make the conflict visible.

Use this skill in this order:

1. Run or read `quantitative-metric-normalizer` output.
2. Use this skill to classify harmony breaks and design ambiguities.
3. Use `demo1-subsystem-patch-directive` only when an algorithm body needs
   subsystem-specific implementation detail.
4. Use `demo1-autonomous-patch-conductor` only after the patch lane and
   verification gates are concrete.

Do not use this skill as a license for broad rewrites. It is a Safe Patch
triage and directive skill.

## Non-Negotiables

- Desktop canonical root is `C:\AbandonWare\demo-1\demo-1\src`.
- Reconfirm Gradle sourceSets before editing. Default active roots are
  `main/java`, `main/resources`, `src/test/java`, and for `:app`
  `app/src/main/java_clean`, `app/src/main/resources`.
- Existing source files and real command output outrank prompts, memory,
  attachments, and Mac mini logs.
- Keep every `dev.langchain4j:*` dependency exactly `1.0.1`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)` when
  that boundary exists.
- Do not log or print raw API keys, client secrets, owner tokens, Authorization
  headers, cookies, raw prompts, full environment dumps, or raw sensitive
  queries.
- Do not edit `apikey.txt`, `apikey.ps1`, `.env*`, shell profiles, real secret
  setup, `openssl`, or `opnessl` keys.
- If the source evidence is missing, write `evidence_needed: <artifact> /
  verify with <exact command>` instead of guessing.

## Inputs

Prefer these inputs, in priority order:

1. Live source and Gradle output from Desktop.
2. Current ablation scorecard or attached directive, such as
   `codex_ablation_harmony_patch_prompt.md`.
3. PatchDrop evidence from Mac mini or Notebook.
4. Prior memory and older prompt packs.

If the attached directive is mojibake, recover only stable identifiers:
file paths, TraceStore keys, subsystem names, failure classes, Gradle commands,
and P0/P1/P2 labels.

## Decomposition

Default to 3-way Self-Ask only when it helps:

1. Subsystem axis: which S01-S08 subsystem owns the behavior?
2. Authority axis: which class, bean, property, or sourceSet is canonical?
3. Failure axis: which harmony break, trace gap, or verification failure is
   observable?

Use direct mode for exact missing TraceStore keys, one-file syntax fixes,
confirmed duplicate YAML keys, or focused tests that already identify the seam.

Record:

```md
Decomposition decision:
- mode: direct | 2-way | 3-way | N-way
- reason: <why this is efficient>
- skipped_axes: <if any>
```

## Subsystem Map

Use this map for ownership checks before editing:

| ID | Subsystem | Canonical live anchors |
| --- | --- | --- |
| S01 | Overdrive / anchor compression | `OverdriveGuard`, `DynamicContextCompressor` |
| S02 | CFVM / failure pattern memory | `RawMatrixBuffer`, `RawSlotExtractor`, `CfvmFailureRecorder` |
| S03 | MoE strategy selector | `RgbStrategySelector`, `RetrievalOrderService`, MoE gates |
| S04 | Matryoshka slicing | embedding/vector dimension adapters and vector services |
| S05 | ExtremeZ / massive query fan-out | `ExtremeZSystemHandler`, ExtremeZ aspects |
| S06 | HYPERNOVA fusion | `NovaNextFusionService`, `TailWeightedPowerMeanFuser`, `CvarAggregator` |
| S07 | CIH-RAG / MLA breadcrumb | `MlaBreadcrumb`, breadcrumb SSE/telemetry paths |
| S08 | OpenAI adapter / version purity | `OpenAiResponsesChatModel`, model guard, Gradle purity task |

Never merge classes by simple name. Use package, caller, bean registration, and
Gradle sourceSet evidence.

## Harmony Break Board

Classify findings with these IDs. P0 means the next patch must either add
evidence or stop with `evidence_needed`.

| ID | Priority | Required trace or gate |
| --- | --- | --- |
| H01 | P0 | `boosterMode.active`, `boosterMode.excludedModes`, `boosterMode.exclusionReason` |
| H02 | P0 | `extremeZ.cancelShieldWrapped`, `extremeZ.interruptPropagated`, `extremeZ.timeBudgetConsumedMs` |
| H03 | P0 | `hypernova.twpmP`, `hypernova.clampApplied`, `hypernova.cvarPhi` |
| H04 | P0 | `retrievalOrder.lastSetBy=MoE|CFVM|PLAN_DSL|DEFAULT` |
| H05 | P0 | PromptBuilder boundary not bypassed; LangChain4j purity remains PASS |
| H06 | P1 | `cihRag.breadcrumb.queryRedacted=true` |
| H07 | P1 | `moe.evolverPlateRegistered=true|false` |
| H08 | P2 | `cfvm.boltzmannTemp`, `cfvm.tempAnnealApplied=true|false` |

Optional aggregate score keys may be added by a later scoring patch:

```text
harmony.score.S01_S05
harmony.score.S05_S06
harmony.score.S03_CFVM
harmony.score.overall
```

Do not fabricate these aggregate scores without a source-owned scorer or
reproducible calculation.

## Patch Gate

Before writing code, prove all of the following:

1. The target file is in an active sourceSet.
2. The target class is canonical or directly called by canonical code.
3. The missing trace key or behavior is verified by a failing focused test, a
   source read, or a command output.
4. The patch is the smallest reversible change that creates the evidence.
5. The verification command is known.

If any item is missing, write `evidence_needed` and do a read-only directive
instead of editing source.

## Required Verification

Use Windows PowerShell from Desktop root:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

For focused harmony patches, run only the tests that prove the touched seams
first, then broaden.

## Output Format

Return this shape:

```md
## 요약
- Source-backed harmony findings and patch status.

## Observation
- Repo root, sourceSets, branch/Git metadata status, PatchDrop status.
- Decomposition decision.
- Evidence used from attachments or Mac mini.
- `evidence_needed` items.

## Harmony Ledger
| ID | subsystem pair | severity | source evidence | required trace key | decision |

## Patch Directive
| file | minimal change | why this file only | rollback |

## Verification
| command | expected | observed | failure class | retry |

## Risks & Next
- Up to five risks.
- next single most urgent patch.
- confidence: L/M/H.
```

Never claim build, boot, provider, browser, or prompt success without command
output.
