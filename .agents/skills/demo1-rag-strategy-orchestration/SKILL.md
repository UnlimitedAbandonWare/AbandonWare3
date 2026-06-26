---
name: demo1-rag-strategy-orchestration
description: Design, audit, or patch demo-1 RAG orchestration strategies: Plan DSL, MoE Strategy Selector, Self-Ask 3-way query decomposition, QueryBurst, Massive Parallel Query Expansion, Anchor-Based Context Compression, Brave mode, RuleBreak, FullScaleSearchStrategy, Hypernova TWPM/CVaR/Risk-K, GRANDAS fusion, DPP diversity reranking, K allocation, tree/scenario analysis, and prompt-context handoff.
---

# Demo1 RAG Strategy Orchestration

## Strategy Rules

- Treat strategy modes as parameterized orchestration over existing components, not new parallel implementations.
- Keep final prompt construction outside strategy modules. Strategy modules prepare `PromptContext` evidence lists; `PromptBuilder.build(ctx)` owns prompt text.
- Preserve session/request propagation through every search, rerank, model, and trace call.
- Keep every high-power mode fail-soft. If a booster fails, return to the regular RAG path with a trace reason.
- Do not widen source roots. Patch `main/java` and `main/resources` unless Gradle evidence proves another active owner.
- Keep Plan DSL changes declarative. Prefer modifying existing `plans/*.yaml` and existing loaders over adding a new Java switchboard.

## Workflow

1. Identify the requested mode: safe, AP1/AP3/AP9, Brave, RuleBreak, FullScale, Hypernova, Side Train, Zero-100, prediction tree, thumbnail, or custom.
2. Read `references/strategy-map.md` to map the concept to concrete files.
3. Locate the current chain entrypoint with `rg -n "DynamicRetrievalHandlerChain|PlannerNexus|PlanDsl|SelfAskPlanner|QueryBurst|ExtremeZ|Overdrive|Dpp|RRF|Cvar|RiskK" main app/src/main/java_clean`.
4. Change the narrowest owner:
   - Plan shape: `main/resources/plans/*.yaml`
   - Branch generation: existing Self-Ask/query planner classes
   - Search expansion: existing QueryBurst/ExtremeZ handlers
   - Context compression: existing Overdrive/Anchor classes
   - Fusion/rerank: existing RRF, GRANDAS, DPP, Cross-Encoder, calibrator classes
5. Add or update trace keys only with redacted, stable names. Do not log raw query, snippets, keys, or owner tokens.
6. Verify with `compileJava` and, for behavior, a focused test or `/api/probe/search`/soak endpoint if already present.

## Guardrails

- High-recall modes may increase K, branch count, or timeout only under explicit policy and budget gates.
- RuleBreak and FullScale must include audit metadata and user-visible mode notice if results leave normal policy.
- Brave and FullScale should strengthen CitationGate/FinalSigmoid thresholds rather than relaxing final answer safety.
- Hypernova score amplifiers must clamp outputs with existing Bode/tanh/math clamp utilities.

