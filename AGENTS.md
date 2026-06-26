# demo-1 Codex Operating Rules

## Runtime Boundary

- Treat the active runtime surface as evidence, not memory. Reconfirm Gradle settings and sourceSets before editing.
- Default backend owner: root `main/java` and `main/resources`.
- Default `:app` owner: `app/src/main/java_clean` and `app/src/main/resources`.
- Treat `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups, archives, and generated build outputs as inactive/reference unless Gradle evidence proves otherwise.

## Active Runtime Map

- Application entry and boot/runtime guards live under `main/java/com/example/lms`, with `main/java/com/example/lms/LmsApplication.java` as the root application entry.
- Non-web boot must remain valid for verification. Servlet request helpers must tolerate no current `HttpServletRequest`, and servlet `SecurityFilterChain` beans must be guarded with `@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)`.
- Canonical RAG prompt assembly is `main/java/com/example/lms/prompt/PromptBuilder.java`; keep final prompt construction on the builder/context boundary.
- Canonical ExtremeZ burst handling is `main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`. Files under `main/java/com/example/lms/extreme` or `main/java/com/example/lms/nova/extremez` are compatibility aliases unless live source evidence proves otherwise.
- Canonical Overdrive compression logic is `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java`. Legacy alias files must not become Spring components.
- Canonical DPP diversity reranking is `main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java`; legacy `com/abandonware/ai/service/rag/**` classes are aliases/adapters only.
- Active HYPERNOVA/CVaR fusion is under `main/java/com/nova/protocol/fusion`, including `CvarAggregator.java` and `NovaNextFusionService.java`; do not replace it with a second CVaR implementation.
- Active CFVM failure-pattern memory is under `main/java/com/example/lms/cfvm`, including `RawMatrixBuffer`, `RawSlotExtractor`, and `CfvmFailureRecorder`.
- Active time-budget surfaces are split between addon request budget context (`main/java/com/abandonware/ai/addons/budget`) and orchestration budget decisions (`main/java/ai/abandonware/nova/orch/timebudget/TimeBudgetGuard.java`). Treat similarly named `TimeBudget` stubs elsewhere as local adapters until live call evidence proves otherwise.
- PII guard naming is case-sensitive: `main/java/com/example/lms/service/guard/PIISanitizer.java` is the service-layer guard, while `main/java/com/example/lms/guard/PiiSanitizer.java` is the legacy component. Do not merge them by simple class name.
- Core fail-soft/provider seams include `HybridWebSearchProvider`, `NaverSearchService`, `BraveSearchService`, `SerpApiProvider`, `NightmareBreaker`, `QueryTransformer`, `TraceStore`, and `DebugEventStore`. Patch these only after live failure evidence identifies the seam.

## Desktop / Mac Mini / Notebook Workspaces

- Source isolation comes before build/cache isolation. Do not rely on `.next-*`, `build-*`, `build/<host-id>`, `.gradle`, or `node_modules` separation to make concurrent source edits safe.
- Treat `C:\AbandonWare\demo-1\demo-1\src` as the Desktop original/final verification area. Mac mini and Notebook agents may read it for evidence, but must not write this path through SMB, NAS, or a shared mount.
- Mac mini and Notebook source edits belong in separate Git worktrees or local clones, for example `C:\AbandonWare\worktrees\awx-macmini`, `C:\AbandonWare\worktrees\awx-notebook`, `~/Desktop/WinSrcMac`, or a Notebook-local clone, on dedicated branches like `agent/macmini/<topic>` or `agent/notebook/<topic>`.
- Exchange Mac mini/Notebook patches, diffs, verification logs, reports, manifests, and SHA sidecars through `C:\AbandonWare\demo-1\demo-1\src\__patch_drop__\`. Apply final changes back in the Desktop verification area only after reviewing the diff, secret scan, checksum, `git apply --check` result, and command evidence.
- Mac mini/Notebook/Desktop-local producer clones can use `__patch_drop__\producer_bundle.ps1` to emit a nested `<topic>-<node>-v3` PatchDrop bundle from an explicit pathspec without writing the Desktop canonical source. Mac mini producers without `pwsh` can use `__patch_drop__\producer_bundle.py` with the same bundle contract. Explicit untracked/new files in the pathspec are included as `new file mode 100644` patch hunks. Producer manifests must preserve `sourceIsolation.guard=PASS`, `sourceRootKind`, and `directCanonicalSourceEdit` evidence for Desktop review.
- Desktop can use `__patch_drop__\janitor_promote_producer_pending.ps1 -Topic <topic> -Node <node>` to promote one nested producer bundle into the single active top-level `<topic>-v3` bundle after SHA and secret-scan gates pass.
- Before Desktop edits, check `git worktree list`, `git branch --show-current`, `git status --short`, `Test-Path .git\index.lock`, PatchDrop pending `.patch` files, active source-edit leases, and ports `8080`/`8081` when boot or smoke is involved. Stop and classify `index-lock-conflict`, `worktree-overlap`, `patch-drop-pending`, `branch-ownership-mismatch`, `smb-conflict-risk`, `port-conflict`, or `gradle-cache-collision` when applicable.
- Move a successfully applied PatchDrop `.patch` into `__patch_drop__\applied\` only after Desktop verification succeeds. Mac mini and Notebook logs remain supporting evidence, not final proof.
- This checkout already supports host-specific Gradle output directories via `awx.splitBuildOutputs` / `AWX_SPLIT_BUILD_OUTPUTS` and `awx.buildHostId` / `AWX_BUILD_HOST_ID`. Keep host output split enabled for SMB/shared work, and set a stable host id when OS/arch is not specific enough.
- When building from multiple machines, also isolate `GRADLE_USER_HOME` and pass a host-local `--project-cache-dir`; `layout.buildDirectory` alone does not isolate the root `.gradle` project cache.
- Apply Next.js `distDir` isolation only in repositories that already prove a real Next.js app with files such as `package.json` and `next.config.*`. Do not create a Next.js config in this Java/Spring checkout only to satisfy a generic cache-isolation note.

## Safe Patch Rules

- Patch only the confirmed blocker with the fewest files and lines needed.
- Do not add duplicate wrappers, duplicate helpers, duplicate routes, shadow implementations, or new orchestration frameworks.
- Preserve existing property names and secret flow. Do not rename, delete, normalize, or restructure any `openssl` or `opnessl` key/value/name.
- Keep Spring Boot on the existing repo version. Keep every `dev.langchain4j` dependency exactly on `1.0.1`; stop and report if Gradle evidence shows mixed, beta, or non-`1.0.1` LangChain4j versions.
- Do not graft code from archives, SQL backups, UAW files, or memory unless the file is part of the active sourceSet and the current task proves it is required.

## Prompt, Search, And Provider Hygiene

- Final RAG prompt construction must stay on `PromptBuilder.build(PromptContext)` or the existing equivalent prompt boundary. Do not assemble final chat/RAG prompts by ad hoc string concatenation in ChatService paths.
- Optional external providers must fail soft when credentials are missing, blank, dummy, `test`, `changeme`, `sk-local`, or unresolved `${...}` placeholders.
- Missing optional credentials should produce provider disabled state, explicit `disabledReason`, no outbound provider call, and redacted diagnostics.
- Never emit fake search results. Classify empty provider output separately from after-filter starvation, timeout, rate limit, provider-disabled, and missing-key states.

## Reusable Prompt Packs

- Keep long task execution prompts under `agent-prompts`; keep this root file limited to durable repo rules.
- Use `demo1_p0_safe_patch_orchestrator` for general adaptive Self-Ask Safe Patch work; when a user invokes `@superpowers`, keep Superpowers skill use subordinate to repo evidence, secret-safety, active sourceSet proof, and verification.
- Use `demo1_dual_codex_smb_safe_patch` for Desktop canonical root plus Mac mini patch-producer orchestration over SMB/PatchDrop.
- Use `demo1_three_node_smb_codex` for Desktop + Mac mini + Notebook work where Notebook/NAS paths, local worktrees, source-edit leases, and PatchDrop-only producer bundles must be coordinated.
- Use `demo1_mcp_control_tower` when agents need the MCP-style `source_scan`/`patch_plan`/`patch_render`/`archive_search`/`archive_restore`/`boot_verify`/`build_error_mine`/`run_pipeline` tool contract, fixed schemas, and Desktop/Mac mini/Notebook role routing.
- Use `scripts\awx_mcp_stdio_server.py` when a client needs MCP-style stdio JSON-RPC (`tools/list`, `tools/call`, `resources/list`, `prompts/list`) over the same control-tower manifest.
- Use `main\resources\mcp\awx-control-tower-mcp-client.sample.json` as the secret-free MCP client config sample. Mac mini and Notebook producers must set its `cwd` to a producer-local worktree/clone, never to the Desktop canonical root.
- Use `desktop_dispatch_packet` / `desktop.dispatch_packet` from Desktop when assigning both Mac mini and Notebook work; it emits both producer command packets plus Desktop `external_evidence_intake` and `external_evidence_audit` commands. When the packet must be handed off through PatchDrop, set `write_dispatch=true` and keep `dispatch_dir` under `__patch_drop__\dispatch\`.
- Use `producer_command_plan` / `producer.command_plan` from Desktop before sending Mac mini or Notebook work: it renders producer-local smoke and PatchDrop handoff commands, `desktopEvidencePath`, and env-name-only hints without exposing secret values.
- Use `scripts\awx_mcp_node_smoke.py --root . --canonical-root <desktop-root> --node-role macmini|notebook` from producer-local worktrees before submitting PatchDrop evidence.
- Use `scripts\awx_mcp_producer_handoff.py --source-root <producer-worktree> --canonical-root <desktop-root> --patchdrop-root <PatchDrop> --producer-script <PatchDrop>\producer_bundle.py --node-role macmini|notebook --topic <topic> --pathspec <relative/path>` when a producer-local edit is ready to become a PatchDrop v3 bundle.
- Copy Mac mini/Notebook smoke JSON to `data\agent-handoff\mcp-control-tower\<role>-node-smoke.json`, then run `external_evidence_audit` from Desktop before treating external host proof as complete.
- Use `scripts\awx_mcp_completion_audit.py --root .` on Desktop to audit the local MCP tool/resource/prompt contract without treating external host smoke proof as complete.
- Use `$patchdrop-safe-patch-orchestrator` when deciding producer/consumer role, enforcing one active cumulative PatchDrop v3 bundle, or applying Desktop/Mac mini collision gates before source edits.
- Use `demo1_graphrag_kg_macmini_patchdrop` for GraphRAG/KG runtime-hardening passes where Mac mini investigates in `agent/macmini/<topic>` and submits only `.patch`/diff/log evidence to PatchDrop for Desktop final application.
- Use `demo1_macmini_subserver_integration` for Mac mini subserver/helper-node work.
- Use `demo1-mcp-control-tower` when coordinating Desktop, Mac mini, and Notebook agents through the repo-local MCP-style external toolbox (`source_scan`, `patch_plan`, `patch_render`, `archive_search`, `archive_restore`, `boot_verify`, `build_error_mine`, `run_pipeline`) while preserving Desktop final ownership and PatchDrop-only producer handoff.
- Use the task skills `archive.search`, `archive.restore`, `verify_boot`, `build_error_miner`, and `run_pipeline` for single-tool control-tower operations; all route through `scripts\awx_mcp_toolbox.ps1` and must keep audit output redacted.
- Use `demo1_dynamic_rag_autonomous_patch_directive` for Plan DSL, MLA/SSE, failure-pattern, Codex/OpenCode agent feedback, LangGraph auxiliary orchestration, MoE, and Hybrid LLM Gateway upgrade work.
- Use `demo1_orchestration_feature_expansion_plan` before adding new Plan DSL execution modes such as Brave, Zero100, RuleBreak, or safe_autorun variants; it defines owner boundaries, feature flags, mode priority, rollback paths, and trace keys.
- Use `demo1_desktop_patchdrop_janitor` when the Desktop needs to triage and apply a batch of pending PatchDrop bundles, isolate orphan metadata (bundles missing the `.patch` body), reject broken bundles, or perform a full PatchDrop cleanup after a Mac mini session. Helper scripts live in `__patch_drop__/`: `janitor_inventory.ps1` (state audit), `janitor_promote_producer_pending.ps1 -Topic <topic> -Node <node>` (nested producer bundle promotion), `janitor_isolate_orphan.ps1` (orphan quarantine), `janitor_apply_one.ps1 -PatchName <file>` (single-bundle apply with SHA256/secret-scan/rollback/git-apply).
- Use `demo1_desktop_referee_intent_packet` when Mac mini output must stay as non-applyable intent packets while Desktop builds a queue matrix, priority index, runtime-verifier gate, and apply/hold/reject judgment.
- Use `__patch_drop__\producer_bundle.ps1` or `__patch_drop__\producer_bundle.py` from a producer-local worktree/clone when Mac mini, Notebook, or Desktop-local workers need to submit only `.patch`, `.report.md`, `.verify.log`, `.sha256.txt`, `.manifest.json`, and a pending notice to PatchDrop.
- Use `__patch_drop__\three_node_patchdrop_smoke.ps1` on Desktop when you need temp-only proof that Mac mini and Notebook producer-local bundles can be created, inventoried, promoted one-at-a-time, and consumed through the Desktop `desktop-consumer` apply gate without mutating the real PatchDrop queue.
- Use `$macmini-safe-patch-assistant` when Mac mini needs to produce a PatchDrop v3 handoff: exactly one cumulative active bundle, no direct shared `WinSrc` edits, temp-copy apply proof, SHA256 manifest, secret scan, dry-run, focused Gradle, and Desktop consumer gates.
- Use `demo1_orch_patch_scanner` when the Desktop needs to systematically find new patch candidates. It scans the active sourceSet for silent failures, missing TraceStore keys, incomplete fail-soft ladders, and AOP proceed() double-invocation risks, then scores each candidate (0–10) and outputs a ranked list.
- Use `demo1_orch_debug_scan` when a runtime failure has occurred (bootRun error, test failure, starvation loop, empty SSE). It classifies the failure by layer (Spring Context → QueryTransformer → Web Search → KG → LLM → Evidence output) and maps it to the responsible patch candidate.
- Use `demo1_orch_directive_generator` to generate a ready-to-send Mac mini patch directive from a scored candidate. It includes the full patch contract, RED/GREEN test assertions, exact Gradle verification commands, and the PatchDrop bundle checklist. Pre-scored P0/P1/P2 candidates are embedded based on actual source analysis.
- Use `demo1_orch_kpi_dashboard` before and after any patch to measure quantitative improvement. It collects static KPIs (TraceStore.put count, catch-without-trace count, FQCN duplicate count, compile time) and dynamic KPIs (outCount, starvation trigger, kgAxis signals) and produces a before/after delta table.
- Use `$quantitative-metric-normalizer` and `demo1_quant_metric_normalization_antigravity` when an attached Dynamic RAG design needs source-backed score normalization, broken subsystem-combination analysis, or an Antigravity-ready read-only audit prompt before patching.
- Use `$demo1-ablation-harmony-tracker` immediately after `quantitative-metric-normalizer` when ablation contribution decomposition has been run and the next step is to classify harmony-break pairs (CRITICAL/WARNING/LOW), resolve design ambiguities (DA-01 through DA-08), and produce a TraceStore-key-anchored patch directive for Codex. Use `demo1_ablation_harmony_patch_directive` when the result should become an executable 9-hour Desktop Safe Patch prompt. The required verification keys include `boosterMode.active`, `retrievalOrder.lastSetBy`, `extremeZ.cancelShieldWrapped`, `extremeZ.timeBudgetConsumedMs`, `hypernova.cvarPhi`, `cihRag.breadcrumb.queryRedacted`, `moe.evolverPlateRegistered`, and `cfvm.boltzmannTemp`. Always use this skill before writing code that touches multi-booster activation, ExtremeZ fan-out cancellation, HYPERNOVA score amplification, RetrievalOrderService authority resolution, MLA breadcrumb redaction, MoE evolver registration, or CFVM temperature behavior.
- Use `demo1-harmony-contamination-scanner` to recompute source-backed `harmony-score.json` style before/after scores for HB-01 through HB-12, silent catch ratio, duplicate FQCNs, cross-subsystem files, TraceStore coverage, and count-only secret scans.
- Use `demo1-cross-subsystem-guard` before claiming a patch is complete when files touch two or more S01-S08 subsystems, shared booster arbitration, CFVM/MoE/HYPERNOVA/ZCA seams, PromptBuilder boundaries, or auto-configuration wiring.
- Use `demo1_harmony_9h_autonomous_patch` for a long-running Desktop Safe Patch prompt that loops through HB-01 through HB-12, rescoring after each phase and preserving active sourceSet proof, Gradle gates, and secret safety.
- Use `demo1_db_schema_source_patch_9h` when DB structure is suspected wrong and Codex must spend a bounded long pass quantifying Java/JPA/DDL vs live MariaDB/MySQL/H2/Supabase metadata, adding secret-safe DB schema tools only when needed, and patching the smallest active source or DDL blocker.
- Use `demo1_graphrag_only_source_patch_9h` when attached or pasted material must be reduced to GraphRAG/KG-only source-modification targets, source-backed normalized scoring, and a 9-hour Desktop Safe Patch directive without importing unrelated business/domain claims.
- Use `demo1_ai_debug_metrics_ledger` when turning the debugging surface itself into AI-assisted metrics, usage ledger, Failure Pattern Analysis tiles, and read-only debug dashboard/API views; keep it on existing `DebugEventStore`, `TraceStore`, redaction helpers, and admin debug pages.
- Use `demo1_graphrag_brain_moe_patch` for the integrated GraphRAG conversation indexing, Brain State listing, Anchor-Based Context Compression (Overdrive), CFVM/Failure Pattern Analysis, MoE Strategy Selector, Matryoshka Slicing adapter, ExtremeZ (Massive Parallel Query Expansion), and HYPERNOVA (TailWeightedPowerMean) hardening pass. This prompt enforces PromptBuilder-only construction, CancelShield toxicity rules, TraceStore key requirements, and SpecialMode mutual exclusion for all six subsystems.
- Use `$demo1-subsystem-patch-directive` when a subsystem implementation needs implementation-level detail that is not covered by the higher-level strategy or resilience skills: precise trigger thresholds, algorithm code snippets, file-to-file patch mappings, required TraceStore keys, and per-subsystem verification gates for S01 Overdrive/Anchor Compression, S02 CFVM/Failure Pattern Analysis, S03 MoE Strategy Selector, S04 Matryoshka Slicing, S05 ExtremeZ/Massive Parallel Query Expansion, S06 HYPERNOVA (TWPM+CVaR+Risk-K), S07 CIH-RAG/IQR/MLA Breadcrumb, and S08 OpenAI-Adapter/Version Purity. Always read this skill before writing new class bodies or modifying core algorithm logic for these subsystems.


## PatchDrop Bundle Rules

- A **complete** bundle requires: `.patch` + `.report.md` + `.verify.log` + `.sha256.txt`. Apply only complete bundles.
- A **PatchDrop v3** producer handoff allows exactly one active top-level cumulative patch per slug, named `<slug>-v3.patch`, with `<slug>-v3.report.md`, `<slug>-v3.verify.log`, `<slug>-v3.sha256.txt`, and `<slug>-v3.manifest.json`. Older v1/v2/incremental attempts from Mac mini, Notebook, or Desktop-side helpers must be moved to `superseded/` or `rejected/` with a reason before Desktop applies anything.
- Do not choose a PatchDrop patch by newest timestamp when multiple candidates exist. The Desktop may apply only the single manifest-pinned cumulative v3 patch, and must classify any ambiguous queue as `patch-drop-pending`.
- Producer promotion must fail closed when manifest `sourceIsolation` is missing, not `PASS`, not `local-worktree`, or indicates shared/canonical source editing.
- Desktop apply/reconcile helpers accept only a bare top-level patch filename. Node-local nested patch names such as `notebook\<slug>-notebook-v3.patch` must be promoted before Desktop consumption.
- Acquire the source-edit lease as `desktop-consumer` before applying an active top-level PatchDrop bundle; `desktop` source-owner leases remain blocked while a top-level patch is pending.
- A **MISSING_PATCH** (orphan) bundle has metadata files but no `.patch` body; move to `__patch_drop__/orphan/` and request Mac mini resubmission.
- A **MISSING_REPORT** bundle has `.patch` but no `.report.md`; read the patch content directly and apply only if the diff is safe and narrow.
- Move each bundle to `__patch_drop__/applied/` only after Desktop Gradle verification passes (projects → compileJava → focused tests → :app:classes → bootJar).
- Move to `__patch_drop__/rejected/` with a `.reason.txt` if verification fails.
- Run `__patch_drop__/janitor_inventory.ps1` first to get a current state snapshot before any apply session.

## AutoLearn Handoff Review

- Before patching AutoLearn failures, read `data/agent-handoff/codex/manifest.json`, then `data/agent-handoff/codex/cycles.jsonl`, then `data/agent-handoff/codex/rejected.jsonl`; use `accepted.jsonl` only as supporting evidence.
- Treat `train_rag.jsonl` as the file-backed training source of truth. Do not treat RDB, Vector DB, diagnostics HTML, or TraceStore as the raw training source.
- Treat Vector DB AutoLearn rows as staged shadow/quarantine metadata until a verifier promotes them; do not infer that a vector row is verified training data.
- Never overwrite raw JSONL handoff or training files while diagnosing. Write any review report under `data/agent-handoff/codex/report/` or another explicit output path.
- For source fixes, stay on existing UAW seams such as `UawAutolearnService`, `UawLearningAgentHandoffWriter`, `TrainRagIngestService`, `UawAutolearnDiagnosticsController`, and the read-only learning-data template.

## Redaction

- Never log or print raw API keys, client secrets, owner tokens, authorization headers, private environment values, raw sensitive queries, or full environment dumps.
- Prefer `hasKey`, `keySource`, host/path summaries, counts, timing, reason codes, masked tails, and hash-only values such as `queryHash`, `bodyHash`, and `backupHash`.
- Public evidence, trace, SSE, and HTML surfaces must remain allowlisted and redacted; raw evidence snippets stay out of TraceStore and UI payloads unless an existing gate explicitly promotes them.

## Evidence And Verification

- Existing repo files and real command output beat prompt assumptions. Official vendor documentation beats memory for external API, CLI, and library behavior.
- If evidence is insufficient, record `evidence_needed: <missing artifact> / verify with <exact command>` instead of inventing files, routes, API keys, tools, or test results.
- Use Windows/PowerShell-first commands in this checkout. Prefer `gradlew.bat` for Gradle verification.
- Verify the narrowest changed surface first, then broaden only when the patch crosses module boundaries.
- When `AWX_SPLIT_BUILD_OUTPUTS=1` / `AWX_BUILD_HOST_ID=desktop` is set, use current artifacts under `build\desktop\...` for boot proof. Treat stale default artifacts under `build\libs` as evidence only after their timestamp and class contents are verified.
- If a broad `test` run reports many `NoClassDefFoundError` or `ClassNotFoundException` failures while the referenced classes exist under `build\desktop\classes`, run `scripts\verify_full_test_refresh.ps1`; it uses Desktop cache isolation plus `--rerun-tasks --fail-fast` to separate stale class output from real source failures.
- For OpenAI/Codex facts, use official OpenAI documentation links. Use the Browser plugin only for local UI or localhost verification, not as a substitute for source or official-doc evidence.
- For the desktop/Mac mini topology, use `scripts\verify_control_plane_topology.ps1` as the standard Windows verification wrapper. It runs focused learning-ops tests, LangChain4j/sourceSet/build checks, the Mac mini read-only collector smoke, and desktop/simulated GPU gateway smokes in sequence.
- Do not parallelize `bootRun` smoke scripts on the same host/cache directory; use host-specific Gradle output and `--project-cache-dir` when Mac mini and desktop agents build the same SMB worktree.
