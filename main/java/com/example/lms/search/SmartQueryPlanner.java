// patched SmartQueryPlanner
package com.example.lms.search;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.infra.resilience.NoiseRoutingGate;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;

import com.example.lms.transform.QueryTransformer;
import com.example.lms.transform.QueryTransformerBypassReason;
import com.example.lms.search.QueryHygieneFilter;
import com.example.lms.search.extract.HybridKeywordExtractor;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import com.example.lms.search.KeywordSelectionService;
import com.example.lms.search.terms.SelectedTerms;
import com.example.lms.search.terms.SelectedTermsDebug;
import com.example.lms.trace.SafeRedactor;
import java.util.Objects;

import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.probe.EvidenceSignals;
import com.example.lms.search.probe.NeedleProbeSynthesizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Keyword selection types

/**
 * 지능형 다중 쿼리 생성기.
 * QueryTransformer.transformEnhanced() 결과를 받아 위생 처리(Hygiene) 및 상한(Cap) 적용 후
 * 반환합니다.
 */
@Component
public class SmartQueryPlanner {
    private static final Logger log = LoggerFactory.getLogger(SmartQueryPlanner.class);

    private final HybridKeywordExtractor extractor;

    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;
    private final QueryTransformer transformer;
    private final SubjectResolver subjectResolver;
    private final KnowledgeBaseService knowledgeBase;
    // Unified noise clipper used to normalise user prompts prior to
    // subject/domain inference and keyword extraction. This ensures
    // consistent cleaning and prevents duplicated clipping logic across
    // retrievers and planners.
    private final com.example.lms.search.NoiseClipper noiseClipper;

    /**
     * Optional LLM driven keyword selection service. When available this
     * service will be invoked before the hybrid keyword extractor to
     * dynamically assemble search vocabulary from the full conversation.
     * When not injected (e.g. in tests or when disabled via config) the
     * planner will fall back to the existing extraction logic.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KeywordSelectionService selector;

    /** Optional conditional probe/needle query synthesizer. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NeedleProbeSynthesizer needleProbeSynthesizer;

    private static double boundedDoubleProperty(String key, double fallback, String stage) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return Double.isFinite(parsed) ? Math.max(0.0d, Math.min(1.0d, parsed)) : fallback;
        } catch (NumberFormatException ignore) {
            traceSuppressed(stage, ignore);
            return fallback;
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        try {
            TraceStore.put("smartQueryPlanner.suppressed." + safeStage, true);
            TraceStore.put("smartQueryPlanner.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException ignored) {
            log.debug("[SmartQueryPlanner] trace suppression failed stage={} errorHash={} errorLength={}",
                    safeStage, SafeRedactor.hashValue(String.valueOf(ignored)), String.valueOf(ignored).length());
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    /**
     * 의존성 주입을 위한 생성자.
     *
     * @param extractor       하이브리드 키워드 추출기
     * @param transformer     쿼리 변환을 수행할 트랜스포머 (앵커드 쿼리 플래너에서 사용)
     * @param subjectResolver SubjectResolver
     * @param knowledgeBase   KnowledgeBaseService
     */
    public SmartQueryPlanner(
            HybridKeywordExtractor extractor,
            @Qualifier("queryTransformer") QueryTransformer transformer,
            SubjectResolver subjectResolver,
            KnowledgeBaseService knowledgeBase,
            com.example.lms.search.NoiseClipper noiseClipper) {
        this.extractor = extractor;
        this.transformer = transformer;
        this.subjectResolver = subjectResolver;
        this.knowledgeBase = knowledgeBase;
        this.noiseClipper = noiseClipper;
    }

    /**
     * Generates a small number (1~2) of high-precision probe queries only when
     * evidence quality signals indicate the current retrieval pool is weak.
     */
    public List<String> planNeedleProbes(
            String userPrompt,
            QueryDomain domain,
            EvidenceSignals signals,
            List<String> alreadyPlanned,
            java.util.Locale locale) {
        if (needleProbeSynthesizer == null) {
            return List.of();
        }
        try {
            return needleProbeSynthesizer.synthesize(userPrompt, domain, signals, alreadyPlanned, locale);
        } catch (Exception e) {
            traceSuppressed("needleProbeSynthesizer", e);
            return List.of();
        }
    }

    /**
     * 사용자 질문(+선택적 초안)을 바탕으로 검색에 투입할 "핵심 쿼리" 목록을 생성합니다.
     * <ul>
     * <li><b>중앙 집중 생성</b>: 쿼리 생성 로직은 QueryTransformer로 중앙화합니다.</li>
     * <li><b>위생 및 정제</b>: QueryHygieneFilter를 통해 중복 제거, 빈 문자열 필터링, 길이 제한 등을
     * 적용합니다.</li>
     * </ul>
     * 
     * @param userPrompt     사용자 원본 질문
     * @param assistantDraft (선택 사항) 모델이 생성한 1차 초안. 쿼리 확장 힌트로 사용될 수 있습니다.
     * @param maxQueries     반환할 최대 쿼리 개수 (1~4개로 제한)
     * @return 정제된 쿼리 문자열 목록
     */
    public List<String> plan(String userPrompt, @Nullable String assistantDraft, int maxQueries) {
        // Clear any existing trace data for the current request. Without this
        // explicit clear, previous trace values could leak across threads.
        // When we're called from a flow that already seeded a request trace
        // (trace.runId),
        // don't wipe it here.
        if (com.example.lms.search.TraceStore.get("trace.runId") == null) {
            com.example.lms.search.TraceStore.clear();
        }

        // 0) Clean the user prompt once at the entry point. The NoiseClipper
        // removes polite suffixes, leading labels and collapses whitespace. When
        // noiseClipper is not available (e.g. in tests) fall back to the raw
        // prompt. An empty or null input yields an empty cleaned string.
        String cleaned = (noiseClipper != null)
                ? noiseClipper.clip(userPrompt)
                : Objects.toString(userPrompt, "").trim();

        // 1) Infer domain and subject based on the cleaned prompt. Domain
        // inference determines the cap and deduplication thresholds; subject
        // resolution provides the anchor used in sanitisation.
        String domain = knowledgeBase.inferDomain(cleaned);
        String subject = subjectResolver.resolve(cleaned, domain).orElse(null);

        // 2) Attempt to select search vocabulary via LLM. When a
        // KeywordSelectionService is present and returns a non-empty
        // result, bypass the hybrid keyword extractor and build queries
        // directly from the selected terms. This branch returns
        // immediately after sanitisation. Should the service be
        // unavailable or fail to parse the JSON, control falls back to
        // the existing extraction logic below.
        boolean bypassKeywordSelection = false;
        GuardContext gctx = GuardContextHolder.get();
        if (gctx != null && gctx.isAggressivePlan())
            bypassKeywordSelection = true;
        // UAW: aux degrade or STRIKE → skip expensive aux calls
        if (gctx != null && ((gctx.isAuxDegraded() || gctx.isAuxHardDown())
                || gctx.isStrikeMode())) {
            bypassKeywordSelection = true;
        }

        // NoiseGate: COMPRESSION-only bypass is sometimes a false-positive.
        // Allow a small escape probability so keyword selection can still run.
        if (!bypassKeywordSelection && gctx != null && gctx.isCompressionMode()) {
            try {
                boolean stageNoiseEnabled = Boolean.parseBoolean(System.getProperty("orch.noiseGate.keywordSelection.compression.enabled", "true"));
                if (stageNoiseEnabled) {
                    double irr = (gctx != null) ? gctx.getIrregularityScore() : 0.0;
                    double max = boundedDoubleProperty(
                            "orch.noiseGate.keywordSelection.compression.escapeP.max", 0.16d,
                            "keywordSelection.max.parse");
                    double min = boundedDoubleProperty(
                            "orch.noiseGate.keywordSelection.compression.escapeP.min", 0.03d,
                            "keywordSelection.min.parse");
                    double t = Math.min(1.0, Math.max(0.0, (irr - 0.35) / 0.45));
                    double escapeP = max + (min - max) * t;

                    boolean escape = NoiseRoutingGate.decideEscape("keywordSelection.compression", escapeP, gctx).escape();
                    if (!escape) {
                        bypassKeywordSelection = true;
                    } else {
                        try {
                            TraceStore.put("keywordSelection.bypass.noiseEscape", true);
                            TraceStore.put("keywordSelection.bypass.noiseEscape.escapeP", escapeP);
                        } catch (Throwable ignore) {
                            traceSuppressed("keywordSelection.noiseEscape.trace", ignore);
                        }
                    }
                } else {
                    bypassKeywordSelection = true;
                }
            } catch (Throwable ignore) {
                traceSuppressed("keywordSelection.noiseGate", ignore);
                bypassKeywordSelection = true;
            }
        }
        if (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.KEYWORD_SELECTION_SELECT)) {
            bypassKeywordSelection = true;
        }
        if (bypassKeywordSelection) {
            TraceStore.put("keywordSelection", "bypassed");
            try {
                TraceStore.put("keywordSelection.mode", "bypassed");
                String reason = (gctx != null && gctx.isAggressivePlan()) ? "gctx.aggressive=true"
                        : (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.KEYWORD_SELECTION_SELECT)
                                ? "nightmare_open"
                                : "unknown");
                TraceStore.put("keywordSelection.bypass.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                TraceStore.append("web.selectedTerms.rules", "keywordSelection.mode=bypassed reason=" + SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            } catch (Throwable ignore) {
                traceSuppressed("keywordSelection.bypassTrace", ignore);
            }
        }

        if (selector != null && !bypassKeywordSelection) {
            try {
                java.util.Optional<SelectedTerms> maybeTerms = selector.select(cleaned, domain, 3);
                if (maybeTerms.isPresent()) {
                    SelectedTerms selected = maybeTerms.get();
                    // assemble tokens: quoted exact phrases, must and should keywords
                    List<String> tokens = new ArrayList<>();
                    if (selected.getExact() != null) {
                        for (String e : selected.getExact()) {
                            if (e != null && !e.isBlank()) {
                                String trimmed = e.trim();
                                // wrap in quotes to anchor exact matches
                                tokens.add("\"" + trimmed + "\"");
                            }
                        }
                    }
                    if (selected.getMust() != null) {
                        for (String m : selected.getMust()) {
                            if (m != null && !m.isBlank()) {
                                tokens.add(m.trim());
                            }
                        }
                    }
                    if (selected.getShould() != null) {
                        for (String s : selected.getShould()) {
                            if (s != null && !s.isBlank()) {
                                tokens.add(s.trim());
                            }
                        }
                    }
                    // Deduplicate and limit the number of tokens to avoid overly long queries.
                    List<String> dedup = tokens.stream()
                            .filter(t -> t != null && !t.isBlank())
                            .distinct()
                            .limit(12)
                            .collect(Collectors.toList());
                    String q0 = String.join(" ", dedup);
                    // Keep the raw selected terms internal for downstream consumers; public
                    // diagnostics only receive count/hash summaries.
                    com.example.lms.search.TraceStore.putInternal("selectedTerms", selected);
                    var selectedDebug = SelectedTermsDebug.toDebugMap(selected, 6);
                    TraceStore.put("selectedTerms.debug", selectedDebug);
                    TraceStore.put("selectedTerms.summary", SelectedTermsDebug.toSummaryString(selected));
                    TraceStore.put("web.selectedTerms", selectedDebug);
                    TraceStore.put("web.selectedTerms.summary", SelectedTermsDebug.toSummaryString(selected));
                    // Compose one or two queries: base q0 and optionally prefix with subject
                    List<String> qs = new ArrayList<>();
                    qs.add(q0);
                    if (subject != null && !subject.isBlank()
                            && !q0.toLowerCase().contains(subject.toLowerCase())) {
                        qs.add((subject + " " + q0).trim());
                    }
                    // Apply hygiene filtering based on domain caps and thresholds
                    List<String> cleanedQs = QueryHygieneFilter.sanitizeForDomain(qs, domain);
                    if (cleanedQs != null && !cleanedQs.isEmpty()) {
                        return cleanedQs;
                    }
                    // Fail-soft: keywordSelection produced only empty/filtered queries; fall through to
                    // hybrid extraction rather than returning an empty plan (prevents fallback_blank loops).
                    TraceStore.put("smartQueryPlanner.keywordSelection.emptySanitized", true);
                }
            } catch (Exception e) {
                traceSuppressed("keywordSelection.select", e);
            }
        }

        // 3) Determine cap and jaccard based on the domain. GENERAL
        // allows more diversity (6-8 queries) with a lower deduplication
        // threshold; specialised domains cap at 4 queries with a higher
        // similarity threshold.
        boolean isGeneral = "GENERAL".equalsIgnoreCase(domain);
        int cap;
        double jaccard;
        if (isGeneral) {
            cap = Math.min(8, Math.max(6, maxQueries));
            jaccard = 0.60;
        } else {
            cap = Math.max(1, Math.min(4, maxQueries));
            jaccard = 0.80;
        }

        // 3) Use the hybrid keyword extractor to propose candidate queries from
        // the cleaned input. The extractor will internally choose between
        // rule, LLM or hybrid strategies based on configuration and heuristics.
        List<String> cand = extractor.extract(cleaned, assistantDraft, subject, domain, cap, jaccard);
        // Retrieve the raw LLM proposals. When the extractor did not invoke
        // the LLM this list may be empty. This data can be used for trace
        // visualisation or debugging.
        List<String> llmProposed = extractor.getLastProposed();
        // 4) Apply hygiene filtering and mandatory subject anchoring. This
        // step removes duplicates, enforces the cap and inserts the subject
        // anchor into queries that lack it. Capture the kept list for trace.
        List<String> hygieneKept = QueryHygieneFilter.sanitizeAnchored(cand, cap, jaccard, subject, null);
        // In the current implementation the final used queries are identical
        // to the hygiene kept list. If additional downstream filters are
        // introduced this variable can be updated accordingly.
        List<String> finalQs = hygieneKept;

        // Fail-soft: always ensure at least one planned query so downstream retrieval
        // never collapses.
        if (finalQs == null || finalQs.isEmpty()) {
            List<String> planned = new java.util.ArrayList<>();
            planned.add(cleaned);
            if (domain != null && !domain.isBlank()) {
                planned.add(cleaned + " " + domain);
            }
            finalQs = QueryHygieneFilter.sanitize(planned, Math.max(1, cap), 0.80);
        }

        // Ensure SelectedTerms exists for downstream components (e.g.,
        // NeedleProbeEngine).
        if (TraceStore.get("selectedTerms") == null) {
            try {
                List<String> kws = new ArrayList<>();
                if (subject != null && !subject.isBlank()) {
                    kws.add(subject.trim());
                }
                if (cleaned != null && !cleaned.isBlank()) {
                    String c = cleaned.trim();
                    boolean dup = false;
                    for (String k : kws) {
                        if (k != null && k.equalsIgnoreCase(c)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        kws.add(c);
                    }
                }
                if (kws.isEmpty() && finalQs != null) {
                    kws.addAll(finalQs);
                }

                SelectedTerms fallbackTerms = SelectedTerms.builder()
                        .must(kws)
                        .exact((subject != null && !subject.isBlank()) ? List.of(subject) : List.of())
                        .domains(new ArrayList<>())
                        .domainProfile((domain != null && !domain.isBlank()) ? domain : "GENERAL")
                        .build();
                TraceStore.putInternal("selectedTerms", fallbackTerms);
                TraceStore.put("selectedTerms.debug", SelectedTermsDebug.toDebugMap(fallbackTerms, 6));
                TraceStore.put("selectedTerms.summary", SelectedTermsDebug.toSummaryString(fallbackTerms));
                TraceStore.put("selectedTerms.fallback", true);
            } catch (Throwable ignore) {
                traceSuppressed("selectedTerms.fallbackTrace", ignore);
            }
        }
        // Record trace information for downstream diagnostics. The
        // TraceStore stores values on a per-thread basis and is cleared
        // automatically at the start of this method.
        List<String> llmProposedHashes = safeHashList(llmProposed);
        List<String> hygieneKeptHashes = safeHashList(hygieneKept);
        List<String> finalUsedHashes = safeHashList(finalQs);
        com.example.lms.search.TraceStore.put("llmProposed", llmProposedHashes);
        TraceStore.put("queryPlanner.llmProposed", llmProposedHashes);
        TraceStore.put("queryPlanner.llmProposed.count", safeSize(llmProposed));
        com.example.lms.search.TraceStore.put("hygieneKept", hygieneKeptHashes);
        TraceStore.put("queryPlanner.hygieneKept", hygieneKeptHashes);
        TraceStore.put("queryPlanner.hygieneKept.count", safeSize(hygieneKept));
        com.example.lms.search.TraceStore.put("finalUsed", finalUsedHashes);
        TraceStore.put("queryPlanner.finalUsed", finalUsedHashes);
        TraceStore.put("queryPlanner.finalUsed.count", safeSize(finalQs));
        return finalQs;
    }

    /**
     * assistantDraft 없이 최대 2개의 쿼리를 생성하는 편의 메서드입니다.
     * 
     * @param userPrompt 사용자 원본 질문
     * @return 정제된 쿼리 문자열 목록
     */
    public List<String> plan(String userPrompt) {
        return plan(userPrompt, null, 2);
    }

    /**
     * PAIRING 등에서 주어를 강제 포함시키는 쿼리 플래너.
     * 앵커 삽입 및 정제는 QueryHygieneFilter에 위임합니다.
     */
    public List<String> planAnchored(
            String userPrompt,
            String subjectPrimary,
            @Nullable String subjectAlias,
            @Nullable String assistantDraft,
            int maxQueries) {
        int cap = Math.max(1, Math.min(4, maxQueries));

        // 1. QueryTransformer를 통해 원시 쿼리 목록을 생성합니다.
        List<String> raw = transformEnhancedFailSoft(
                Objects.toString(userPrompt, ""),
                assistantDraft,
                subjectPrimary,
                false);

        // 2. [수정] 앵커링 및 정제 작업을 QueryHygieneFilter.sanitizeAnchored 메서드에 모두 위임합니다.
        // - 이 클래스 내에서 앵커를 미리 추가하는 중복 로직을 제거했습니다.
        List<String> planned = QueryHygieneFilter.sanitizeAnchored(raw, cap, 0.80, subjectPrimary, subjectAlias);
        if (planned == null || planned.isEmpty()) {
            planned = new java.util.ArrayList<>();
            String base = Objects.toString(userPrompt, "").strip();
            if (!base.isEmpty())
                planned.add(base);
            if (subjectPrimary != null && !subjectPrimary.isBlank()) {
                planned.add(base + " " + subjectPrimary);
            }
            planned = QueryHygieneFilter.sanitize(planned, cap, 0.80);
        }
        return planned;
    }

    /**
     * ✨ 새 오버로드: (주제 미지정) → SubjectResolver로 자동 추정 후 앵커 적용
     */
    public List<String> planAnchored(
            String userPrompt,
            @Nullable String assistantDraft,
            int maxQueries) {
        int cap = Math.max(1, Math.min(4, maxQueries));
        String domain = knowledgeBase.inferDomain(userPrompt);
        String subject = subjectResolver.resolve(userPrompt, domain).orElse(null);
        List<String> raw = transformEnhancedFailSoft(
                Objects.toString(userPrompt, ""),
                assistantDraft,
                subject,
                true);
        List<String> planned = QueryHygieneFilter.sanitizeAnchored(raw, cap, 0.80, subject, null);
        if (planned == null || planned.isEmpty()) {
            planned = new java.util.ArrayList<>();
            String base = Objects.toString(userPrompt, "").strip();
            if (!base.isEmpty())
                planned.add(base);
            if (domain != null && !domain.isBlank()) {
                planned.add(base + " " + domain);
            }
            planned = QueryHygieneFilter.sanitize(planned, cap, 0.80);
        }
        return planned;
    }

    // ⬅️ [제거] 이 메서드는 QueryHygieneFilter.sanitizeAnchored 내부 로직과 중복되므로 제거합니다.
    // private static String ensureSubjectAnchor(String q, String primary, String
    // alias) { /* ... */ }
    // Added by patch: simple multi-query plan

    private static int safeSize(List<String> values) {
        return values == null ? 0 : values.size();
    }

    private static List<String> safeHashList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> value == null || value.isBlank() ? "" : SafeRedactor.hash12(value))
                .collect(Collectors.toList());
    }

    private List<String> transformEnhancedFailSoft(
            String userPrompt,
            @Nullable String assistantDraft,
            @Nullable String subject,
            boolean includeSubject) {
        try {
            List<String> transformed = includeSubject
                    ? transformer.transformEnhanced(userPrompt, assistantDraft, subject)
                    : transformer.transformEnhanced(userPrompt, assistantDraft);
            if (transformed != null && !transformed.isEmpty()) {
                return transformed;
            }
            recordQueryTransformerBypass("empty_result", userPrompt);
        } catch (Exception e) {
            traceSuppressed("queryTransformer.bypass", e);
            recordQueryTransformerBypass(QueryTransformerBypassReason.classify(e), userPrompt);
        }
        return fallbackQueries(userPrompt, subject);
    }

    private static List<String> fallbackQueries(String userPrompt, @Nullable String subject) {
        List<String> fallback = new ArrayList<>();
        String base = Objects.toString(userPrompt, "").strip();
        if (!base.isEmpty()) {
            fallback.add(base);
            if (subject != null && !subject.isBlank()) {
                fallback.add((base + " " + subject.strip()).strip());
            }
        }
        return fallback;
    }

    private static void recordQueryTransformerBypass(String reasonCode, String rawQuery) {
        String safeReasonCode = SafeRedactor.traceLabelOrFallback(reasonCode, "unknown");
        TraceStore.put("queryTransformer.bypassed", true);
        TraceStore.put("queryTransformer.reason", safeReasonCode);
        TraceStore.put("queryTransformer.bypassed.queryHash12",
                SafeRedactor.hash12(Objects.toString(rawQuery, "")));
        TraceStore.put("queryTransformer.bypassed.queryLength", rawQuery == null ? 0 : rawQuery.length());
        TraceStore.put("aux.queryTransformer.degraded", true);
        TraceStore.putIfAbsent("aux.queryTransformer.degraded.reason", safeReasonCode);
    }

}
