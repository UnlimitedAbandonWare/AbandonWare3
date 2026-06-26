package com.example.lms.prompt;

import com.example.lms.util.FutureTechDetector;
import com.example.lms.search.TraceStore;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.ensemble.SampledCandidate;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.rag.content.Content;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import com.example.lms.domain.enums.VisionMode;
import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.learning.chat.LearningActorRole;
import com.example.lms.learning.chat.LearningSignal;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * [Jammini Dual-Vision Patch]
 * Standard prompt builder that:
 * - formats SEARCH RESULTS from web & vector (rag) context
 * - injects a Context Priority Protocol via buildInstructions(...)
 *
 * See REV-20251206-THINKING.
 */
@Component
@ConditionalOnProperty(name = "prompt.standard.enabled", havingValue = "true", matchIfMissing = true)
public class StandardPromptBuilder implements PromptBuilder {

    private static final System.Logger LOG = System.getLogger(StandardPromptBuilder.class.getName());

    @Override
    public String build(List<PromptContext> contexts, String question) {
        // [FIX-C1] Null-safe: contexts/question 방어 + null 요소 스킵
        if (contexts == null) {
            contexts = java.util.Collections.emptyList();
        }
        String safeQuestion = question == null ? "" : question.trim();
        if (contexts.isEmpty()) {
            TraceStore.put("promptBuilder.evidenceEmpty", true);
            return "### SEARCH RESULTS\n(검색 결과 없음)\n\n### USER QUESTION\n" + safeQuestion;
        }

        // Generic mode: when a caller provides only a systemInstruction and a
        // plain question/text (no web/rag/memory), build a minimal prompt.
        // This is used by non-chat tasks (e.g. NER) to avoid ad-hoc
        // system+user string concatenation.
        if (contexts.size() == 1) {
            PromptContext only = contexts.get(0);
            if (only != null) {
                String sys = only.systemInstruction();
                boolean noEvidence = (only.web() == null || only.web().isEmpty())
                        && (only.rag() == null || only.rag().isEmpty())
                        && (only.localDocs() == null || only.localDocs().isEmpty());
                boolean noMemory = (only.memory() == null || only.memory().isBlank());
                if (sys != null && !sys.isBlank() && noEvidence && noMemory) {
                    TraceStore.put("promptBuilder.evidenceEmpty", true);
                    return sys.strip() + "\n\n" + safeQuestion;
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        // Optional memory injection (first non-blank wins)
        for (PromptContext ctx : contexts) {
            if (ctx == null) {
                continue;
            }
            try {
                String mem = ctx.memory();
                if (mem != null && !mem.isBlank()) {
                    sb.append("### MEMORY\n");
                    sb.append(mem.strip()).append("\n\n");
                    break;
                }
            } catch (Throwable error) {
                traceSkipped("memory", error);
            }
        }

        int localDocsRendered = appendLocalDocuments(sb, contexts);
        int citableEvidenceRendered = appendCitableEvidenceMetadata(sb, contexts);

        sb.append("### SEARCH RESULTS\n");

        int webIdx = 1;
        int ragIdx = 1;
        int legacyIdx = 1;
        for (PromptContext ctx : contexts) {
            if (ctx == null) {
                continue;
            }
            boolean anyFromLists = false;

            List<Content> webList = ctx.web();
            if (webList != null) {
                for (Content c : webList) {
                    String snippet = safeSnippet(c);
                    if (snippet.isEmpty()) {
                        continue;
                    }
                    sb.append(String.format("[W%d] %s%n", webIdx, snippet));
                    webIdx++;
                    anyFromLists = true;
                }
            }

            List<Content> ragList = ctx.rag();
            if (ragList != null) {
                for (Content c : ragList) {
                    String snippet = safeSnippet(c);
                    if (snippet.isEmpty()) {
                        continue;
                    }
                    sb.append(String.format("[V%d] %s%n", ragIdx, snippet));
                    ragIdx++;
                    anyFromLists = true;
                }
            }

            // Fallback: legacy evidence-style fields when list-based snippets are absent
            if (!anyFromLists) {
                String snippet = ctx.snippet != null ? ctx.snippet.strip() : "";
                if (!snippet.isEmpty()) {
                    sb.append(String.format("[V%d] %s%n", legacyIdx, snippet));
                    if (ctx.source != null && !ctx.source.isBlank()) {
                        sb.append("  - src: ").append(ctx.source).append('\n');
                    }
                    legacyIdx++;
                }
            }
        }

        boolean searchEvidenceRendered = webIdx > 1 || ragIdx > 1 || legacyIdx > 1;
        TraceStore.put("promptBuilder.evidenceEmpty",
                !searchEvidenceRendered && localDocsRendered == 0 && citableEvidenceRendered == 0);

        appendEnsembleCandidates(sb, contexts);

        sb.append("\n### USER QUESTION\n");
        sb.append(safeQuestion);

        return sb.toString();
    }

    private static void appendEnsembleCandidates(StringBuilder sb, List<PromptContext> contexts) {
        if (sb == null || contexts == null || contexts.isEmpty()) {
            TraceStore.put("prompt.ensembleCandidatesRenderedCount", 0);
            return;
        }
        int rendered = 0;
        boolean headerWritten = false;
        for (PromptContext ctx : contexts) {
            if (ctx == null || !ctx.ensembleJudgeMode()
                    || ctx.ensembleCandidates() == null || ctx.ensembleCandidates().isEmpty()) {
                continue;
            }
            if (!headerWritten) {
                sb.append("\n### ENSEMBLE CANDIDATES\n");
                sb.append("- Synthesize these drafts by evidence fit, causal coherence, and contradiction handling.\n");
                headerWritten = true;
            }
            int idx = 1;
            for (SampledCandidate candidate : ctx.ensembleCandidates()) {
                if (candidate == null || candidate.text() == null || candidate.text().isBlank()) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT,
                        "[candidate %d | node=%s | citation=%.2f | risk=%.2f]%n",
                        idx++,
                        safeLine(candidate.nodeId(), 64),
                        candidate.citationScore(),
                        candidate.riskScore()));
                String safeText = SafeRedactor.redact(candidate.text().replace('\u0000', ' ').strip());
                sb.append(truncate(safeText, 2_000)).append("\n\n");
                rendered++;
            }
        }
        TraceStore.put("prompt.ensembleCandidatesRenderedCount", rendered);
    }

    private static int appendCitableEvidenceMetadata(StringBuilder sb, List<PromptContext> contexts) {
        if (sb == null || contexts == null || contexts.isEmpty()) {
            TraceStore.put("prompt.citableEvidenceRenderedCount", 0);
            return 0;
        }
        int rendered = 0;
        boolean headerWritten = false;
        for (PromptContext ctx : contexts) {
            if (ctx == null || ctx.evidence() == null || ctx.evidence().isEmpty()) {
                continue;
            }
            for (RagEvidenceMetadata item : ctx.evidence()) {
                if (item == null || item.marker() == null || item.marker().isBlank()) {
                    continue;
                }
                if (!headerWritten) {
                    sb.append("### CITABLE EVIDENCE METADATA\n");
                    sb.append("- Only markers in this block are public citations; SEARCH RESULTS markers without metadata are retrieval context only.\n");
                    headerWritten = true;
                }
                sb.append('[').append(safeLine(item.marker(), 24)).append("] ");
                sb.append("kind=").append(safeLine(item.kind(), 40));
                appendMeta(sb, "title", item.title(), 180);
                appendMeta(sb, "source", item.source(), 300);
                appendMeta(sb, "filePath", item.filePath(), 300);
                if (item.lineStart() != null) {
                    sb.append("; lines=").append(item.lineStart());
                    if (item.lineEnd() != null && !item.lineEnd().equals(item.lineStart())) {
                        sb.append('-').append(item.lineEnd());
                    }
                }
                if (item.confidence() != null) {
                    sb.append("; confidence=")
                            .append(String.format(Locale.ROOT, "%.3f", item.confidence()));
                } else if (item.confidenceSource() != null && !item.confidenceSource().isBlank()) {
                    sb.append("; confidence=").append(safeLine(item.confidenceSource(), 80));
                }
                sb.append('\n');
                rendered++;
            }
        }
        if (headerWritten) {
            sb.append('\n');
        }
        TraceStore.put("prompt.citableEvidenceRenderedCount", rendered);
        return rendered;
    }

    private static void appendMeta(StringBuilder sb, String key, String value, int max) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("; ").append(key).append('=').append(safeLine(value, max));
    }

    private static String safeLine(String value, int max) {
        if (value == null) {
            return "";
        }
        String s = value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
        if (max > 0 && s.length() > max) {
            return s.substring(0, max);
        }
        return s;
    }

    private static int appendLocalDocuments(StringBuilder sb, List<PromptContext> contexts) {
        if (sb == null || contexts == null || contexts.isEmpty()) {
            TraceStore.put("prompt.localDocsRenderedCount", 0);
            TraceStore.put("prompt.localDocsRenderedChars", 0);
            return 0;
        }
        int idx = 1;
        int renderedChars = 0;
        boolean headerWritten = false;
        for (PromptContext ctx : contexts) {
            if (ctx == null || ctx.localDocs() == null) {
                continue;
            }
            for (Document doc : ctx.localDocs()) {
                String snippet = safeDocumentSnippet(doc);
                if (snippet.isEmpty()) {
                    continue;
                }
                if (!headerWritten) {
                    sb.append("### LOCAL DOCUMENTS\n");
                    headerWritten = true;
                }
                int remaining = 16_000 - renderedChars;
                if (remaining <= 0) {
                    break;
                }
                String bounded = truncate(snippet, Math.min(4_000, remaining));
                sb.append(String.format("[D%d] %s%n", idx, bounded));
                renderedChars += bounded.length();
                idx++;
            }
        }
        if (headerWritten) {
            sb.append('\n');
        }
        int renderedCount = Math.max(0, idx - 1);
        TraceStore.put("prompt.localDocsRenderedCount", renderedCount);
        TraceStore.put("prompt.localDocsRenderedChars", renderedChars);
        return renderedCount;
    }

    @Override
    public String buildInstructions(PromptContext ctx) {
        String currentDate = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        boolean hasSnippets = hasAnySnippets(ctx);
        String safeQuery = (ctx == null || ctx.userQuery() == null) ? "" : ctx.userQuery();
        boolean futureTech = FutureTechDetector.isFutureTechQuery(safeQuery);

        // VisionMode 결정 (null 방어)
        VisionMode visionMode = (ctx != null && ctx.visionMode() != null) ? ctx.visionMode() : VisionMode.HYBRID;

        // [NEW] AnswerMode / MemoryMode 결정 (null 방어)
        AnswerMode answerMode = (ctx != null && ctx.answerMode() != null) ? ctx.answerMode() : AnswerMode.ALL_ROUNDER;
        MemoryMode memoryMode = (ctx != null && ctx.memoryMode() != null) ? ctx.memoryMode() : MemoryMode.HYBRID;

        String sectionSpecBlock = "";
        if (ctx != null && ctx.sectionSpec() != null && !ctx.sectionSpec().isEmpty()) {
            sectionSpecBlock = "\n### SECTION TEMPLATE\n" +
                    "- 아래 순서로 섹션 헤더를 사용해 답변을 구성해:\n" +
                    "  " + String.join(" -> ", ctx.sectionSpec()) + "\n";
        }

        String visionBlock;
        String guardBlock;

        switch (visionMode) {
            case STRICT -> {
                visionBlock = """
                        ### ACTIVE VISION: View 1 (Memory-Strict)
                        - You represent the "Strict Guardian Jammini".
                        - SEARCH RESULTS and MEMORY are primary evidence, but still check for conflicts, dates, and missing support.
                        - Do NOT fabricate information not present in the snippets; report evidence gaps when support is insufficient.
                        """;
                guardBlock = """
                        ### STRICT GUARD
                        - If SEARCH RESULTS are present but weak, stale, or conflicting, say what is supported and what still needs evidence.
                        - If no relevant info is found, admit it cleanly.
                        """;
            }
            case FREE -> {
                visionBlock = """
                        ### ACTIVE VISION: View 2 (Free-Idea)
                        - You represent the "Free-thinking Jammini".
                        - You are NOT bound by strict evidence if scarce.
                        - You MAY use your internal knowledge for creative/speculative answers.
                        - **CRITICAL:** Mark speculation clearly as '(추측)' or '(비공식 아이디어)'.
                        """;
                guardBlock = """
                        ### FLEXIBLE GUARD
                        - If SEARCH RESULTS are empty, separate creative ideas from factual claims.
                        - Provide best-effort hypotheses only when clearly labeled.
                        - Focus on engagement and clarity for Entertainment/Community/Lifestyle topics (including games), but keep claims grounded in provided sources.
                        """;
            }
            default -> { // HYBRID
                visionBlock = """
                        ### ACTIVE VISION: Hybrid (View 1 + View 2)
                        - First, answer with factual summary based on SEARCH RESULTS / MEMORY.
                        - Then optionally add a short '추가 아이디어(비공식)' section for creative thoughts.
                        """;
                guardBlock = """
                        ### HYBRID GUARD
                        - If SEARCH RESULTS contain info, use them as PRIMARY source.
                        - If scarce, provide hedged answer with '변경될 가능성이 있습니다'.
                        - Only say "정보 없음" if SEARCH RESULTS are TRULY empty.
                        """;
            }
        }

        StringBuilder sb = new StringBuilder();

        // user rule: fixed single-line instruction prefix (must be first)
        sb.append("### INSTRUCTIONS: Synthesize answers from sources (higher authority first). Cite evidence. If insufficient, reply '정보 없음'.\n");

        // [시선1 핵심] 시간 앵커 + 모드 인젝션
        sb.append("### CRITICAL SYSTEM CONTEXT ###\n");
        sb.append("Current Date: ").append(currentDate).append("\n");
        sb.append("ANSWER_MODE: ").append(answerMode.name()).append("\n");
        sb.append("MEMORY_MODE: ").append(memoryMode.name()).append("\n\n");
        appendResourceAllocationBlock(sb, ctx);
        appendLearningRoleBlock(sb, ctx);

        // AnswerMode별 지침
        switch (answerMode) {
            case ALL_ROUNDER -> sb.append("""
                    ### MODE: ALL_ROUNDER
                    - 어떤 주제든 '구조화된 실행형 답변'을 기본으로 제공합니다.
                    - 기본 흐름: (1) 요약 1~2문장 → (2) 핵심 답변 → (3) 추가 설명/비교/옵션 → (4) 주의/확인 → (5) 다음 단계.
                    - 사용자가 '짧게/요약만'을 원하면, 위 흐름을 유지하되 각 섹션을 1~2줄로 압축합니다.
                    - 질문이 애매하면 가능한 해석 2~3개를 제시하고, 가장 중요한 확인 질문 1개를 마지막에 둡니다.
                    - 버전/시점이 중요한 주제(소프트웨어, 정책/법령, 제품 출시, 게임/커뮤니티 포함)는 근거에 날짜/버전이 없으면 단정하지 말고 '근거에 표시된 시점 기준'으로 표현합니다.
                    """);
            case FACT -> sb.append("""
                    ### MODE: FACTUAL (시선1)
                    - 당신의 우선순위는 '사실 정합성'입니다.
                    - 검색된 근거에 어긋나는 내용을 절대 만들어내지 마십시오.
                    - 과거 루머/예상 문서는 최신 공식 정보가 있으면 버리십시오.
                    - 현재 날짜 기준으로 과거/미래를 정확히 판단하십시오.
                    """);
            case CREATIVE -> sb.append("""
                    ### MODE: CREATIVE (시선2)
                    - 당신의 우선순위는 '창의적 아이디어/서사'입니다.
                    - 근거는 참고용으로만 사용하고, 추론과 상상력을 적극적으로 사용하십시오.
                    - 사실이 아닌 내용은 '(추측)' 또는 '(상상)'임을 명시하십시오.
                    - "정보 없음"보다는 가설이라도 제시하는 것이 좋습니다.
                    """);
            default -> sb.append("""
                    ### MODE: BALANCED (하이브리드)
                    - 사실과 창의성의 균형을 맞추십시오.
                    - 핵심 factual 정보는 근거에 맞춰 설명하되, 예시/비유는 자유롭게 생성해도 됩니다.
                    - 불확실한 내용은 '변경될 가능성이 있습니다'로 hedge하십시오.
                    """);
        }

        sb.append("""
                ### SYSTEM ROLE
                You are Jammini's RAG tutor with Dual-Vision architecture:
                - View1 (Evidence-Strict): Grounds answers in SEARCH RESULTS and MEMORY when they are relevant and sufficient.
                - View2 (Free-Idea): Capable of creative reasoning.
                %s
                ### CONTEXT PRIORITY PROTOCOL (MUST FOLLOW)
                1. Treat provided 'SEARCH RESULTS' as primary evidence, not absolute truth.
                2. Current Date is %s. Use source dates/versions; do not infer freshness when evidence lacks them.
                3. If SEARCH RESULTS contain specific details (specs, dates, aliases), use them only when internally consistent.
                4. When evidence conflicts with memory/training data, state the conflict or evidence gap instead of fabricating certainty.
                %s
                ### STYLE
                - 답변은 한국어로, 구조화된 올라운더 형태를 기본으로 합니다.
                - 핵심 결론을 먼저 제시하고, 필요하면 비교/대안/리스크/다음행동까지 포함합니다.
                - 근거가 있는 문장에는 [W1], [V2] 등 마커를 붙이고, 근거가 약하면 '추정/확인 필요'로 표시합니다.
                """.formatted(visionBlock, currentDate, guardBlock));

        // THINKING SETUP: 내부 사고 유도 (출력은 결론만)
        sb.append("""
                ### THINKING SETUP (INTERNAL)
                - 답변 전 내부적으로 3단계로 정리: (1) 의도/제약 파악 → (2) 근거 추출/충돌 확인 → (3) 섹션별 아웃라인 작성
                - 내부 추론/메모(Chain-of-thought)는 출력하지 말고, 최종 답변만 출력합니다.
                - 계산/비교/판단이 필요하면, 가능한 한 명확한 기준(가정 포함)을 선언하고 진행합니다.
                """);



        // OUTPUT BUDGET: 토큰 예산 힌트 (transport-level 제한이 안 될 때 프롬프트로 제어)
        Integer tokBudget = (ctx == null) ? null : ctx.targetTokenBudgetOut();
        Integer minWords = (ctx == null) ? null : ctx.minWordCount();
        String vh = (ctx == null) ? null : ctx.verbosityHint();
        String aud = (ctx == null) ? null : ctx.audience();
        if ((tokBudget != null && tokBudget > 0) || (minWords != null && minWords > 0)
                || (vh != null && !vh.isBlank()) || (aud != null && !aud.isBlank())) {
            sb.append("\n### OUTPUT BUDGET\n");
            if (vh != null && !vh.isBlank()) sb.append("- verbosity: ").append(vh).append("\n");
            if (aud != null && !aud.isBlank()) sb.append("- audience: ").append(aud).append("\n");
            if (minWords != null && minWords > 0) sb.append("- minimum words: ").append(minWords).append("\n");
            if (tokBudget != null && tokBudget > 0) sb.append("- target output tokens: ").append(tokBudget).append("\n");
            sb.append("- If the budget is tight, prioritize: 결론 → 핵심 근거 → 다음 행동.\n");
            sb.append("- Avoid long preambles; keep citations compact.\n");
        }

        sb.append(sectionSpecBlock);



        // [FUTURE_TECH FIX] Unreleased / rumor product mode (web-first, no refusal-only answers)
        if (futureTech) {
            sb.append("""

[CRITICAL: UNRELEASED / RUMOR PRODUCT MODE]
- Do NOT refuse just because there is no official announcement.
- Summarize rumors/leaks/expectations ONLY from SEARCH RESULTS (prefer [WEB]).
- Clearly label each major claim as (루머)/(유출)/(예상) and say it may change.
- Use sections: (1) 예상 출시 시기, (2) 루머 스펙, (3) 디자인/변경점, (4) 신뢰도/출처.
- If sources conflict, present both and label as '상충되는 루머'.
- End with: '출시 전 정보는 변경될 수 있습니다.'
- NEVER end with refusal-only answers like '공식 정보 없음', '정보가 없습니다'.
""");
        }

        return sb.toString();
    }

    private static void appendLearningRoleBlock(StringBuilder sb, PromptContext ctx) {
        if (sb == null || ctx == null) {
            return;
        }
        LearningActorRole role = ctx.learningRole();
        List<LearningSignal> signals = ctx.learningSignals();
        String summary = ctx.learningContextSummary();
        boolean hasSignals = signals != null && !signals.isEmpty();
        boolean hasSummary = summary != null && !summary.isBlank();
        if ((role == null || role == LearningActorRole.ANONYMOUS) && !hasSignals && !hasSummary) {
            return;
        }
        LearningActorRole effectiveRole = role == null ? LearningActorRole.ANONYMOUS : role;
        sb.append("### RAG LEARNING SUPPORT CONTEXT\n");
        sb.append("- role: ").append(effectiveRole.trainingRagLabel()).append("\n");
        sb.append("- These Training RAG support signals are internal personalization or operations hints, not public citation evidence.\n");
        sb.append("- Do not cite these signals as [W]/[V] sources; use them only to scope the answer and decide what to emphasize.\n");
        switch (effectiveRole) {
            case STUDENT -> sb.append("- Policy: answer from the user's own Training RAG history, weakness signals, validated samples, progress, and personalized context only.\n")
                    .append("- Do not infer or expose other users' records.\n");
            case TEACHER -> sb.append("- Policy: summarize authorized Training RAG progress, wrong-answer patterns, validation backlog, and feedback needs within the assigned support scope.\n")
                    .append("- Keep the view scoped to assigned Training RAG support data and avoid unrelated user records.\n");
            case ADMIN -> sb.append("- Policy: focus on aggregate learning statistics, RAG quality, AutoLearn quality, quarantine, and operations health.\n")
                    .append("- Do not expose raw PII, raw queries, owner tokens, or raw trace payloads.\n");
            case ANONYMOUS -> sb.append("- Policy: no personalized learning database context is available; keep the normal RAG flow.\n");
        }
        if (hasSummary) {
            sb.append("- summary: ").append(truncate(summary.strip(), 480)).append("\n");
        }
        if (hasSignals) {
            sb.append("### RAG LEARNING SUPPORT SIGNALS\n");
            for (LearningSignal signal : signals.stream().limit(8).toList()) {
                if (signal != null) {
                    sb.append("- ").append(truncate(signal.render(), 220)).append("\n");
                }
            }
        }
        sb.append("\n");
    }

    private static void appendResourceAllocationBlock(StringBuilder sb, PromptContext ctx) {
        if (sb == null || ctx == null || !hasResourceAllocationHints(ctx)) {
            return;
        }
        sb.append("### RESOURCE ALLOCATION\n");
        if (ctx.resourceTier() != null && !ctx.resourceTier().isBlank()) {
            sb.append("- tier: ").append(ctx.resourceTier().trim().toUpperCase(Locale.ROOT)).append("\n");
        }
        appendMetric(sb, "decisionValueScore", ctx.resourceValueScore());
        appendMetric(sb, "optimismScore", ctx.resourceOptimismScore());
        appendMetric(sb, "riskAdjustedConfidence", ctx.resourceRiskAdjustedConfidence());
        appendMetric(sb, "rewriteTemperature", ctx.resourceRewriteTemperature());
        appendMetric(sb, "searchRangeMultiplier", ctx.resourceSearchRangeMultiplier());
        sb.append("- Policy: LOW-value tasks should use narrow evidence and low latency; HIGH/CRITICAL decisions should prefer official evidence, broader retrieval, contradiction checks, and no early stop on a plausible answer.\n\n");
    }

    private static boolean hasResourceAllocationHints(PromptContext ctx) {
        return ctx.resourceTier() != null
                || ctx.resourceValueScore() != null
                || ctx.resourceOptimismScore() != null
                || ctx.resourceRiskAdjustedConfidence() != null
                || ctx.resourceRewriteTemperature() != null
                || ctx.resourceSearchRangeMultiplier() != null;
    }

    private static void appendMetric(StringBuilder sb, String key, Double value) {
        if (value == null || !Double.isFinite(value)) {
            return;
        }
        sb.append("- ").append(key).append(": ")
                .append(String.format(Locale.ROOT, "%.3f", value))
                .append("\n");
    }

    private boolean hasAnySnippets(PromptContext ctx) {
        if (ctx == null)
            return false;
        try {
            java.util.List<Content> web = ctx.web();
            if (web != null && !web.isEmpty()) {
                return true;
            }
        } catch (Throwable error) {
            traceSkipped("web_snippets_probe", error);
        }
        try {
            java.util.List<Content> rag = ctx.rag();
            if (rag != null && !rag.isEmpty()) {
                return true;
            }
        } catch (Throwable error) {
            traceSkipped("rag_snippets_probe", error);
        }
        // 레거시 snippet/fileContext는 존재하더라도 우선순위가 낮으므로 여기서는 생략하거나
        // 필요시 아래와 같이 확장 가능:
        // Legacy fileContext probing intentionally stays disabled here.
        return false;
    }

    private static String safeSnippet(Content c) {
        if (c == null)
            return "";
        try {
            var seg = c.textSegment();
            if (seg != null && seg.text() != null) {
                String t = seg.text().strip();
                if (!t.isEmpty()) {
                    return truncate(t, 512);
                }
            }
        } catch (Exception error) {
            traceSkipped("content_snippet", error);
        }
        return "";
    }

    private static String safeDocumentSnippet(Document doc) {
        if (doc == null) {
            return "";
        }
        try {
            String text = doc.text();
            if (text == null || text.isBlank()) {
                return "";
            }
            String normalized = text.replace("\u0000", "").strip();
            if (normalized.contains("<w:t") || normalized.contains("<p:sld") || normalized.contains("<xml")) {
                normalized = normalized.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
            }
            return normalized;
        } catch (Throwable error) {
            traceSkipped("local_document_snippet", error);
            return "";
        }
    }

    private static void traceSkipped(String stage, Throwable error) {
        LOG.log(System.Logger.Level.DEBUG,
                "Standard prompt builder fail-soft stage=" + stage + " errorType=" + errorType(error));
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        // [PATCH] Prefer head+tail sampling to preserve sparse evidence near the end of long docs.
        if (max <= 12) {
            return text.substring(0, Math.min(max, text.length()));
        }
        int head = max / 2;
        int tail = max - head - 1;
        if (tail <= 0) {
            return text.substring(0, Math.min(max, text.length()));
        }
        return text.substring(0, head) + "…" + text.substring(Math.max(0, text.length() - tail));
    }
}
