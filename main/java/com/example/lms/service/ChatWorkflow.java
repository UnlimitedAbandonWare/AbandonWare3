package com.example.lms.service;
import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.llm.LlmFastBailoutException;
import com.example.lms.llm.RequestedModelTimeoutPolicy;
import com.example.lms.llm.TimedChatModelCaller;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.LlmConfigurationException;
import com.example.lms.llm.OpenAiEndpointCompatibility;
import com.example.lms.llm.OpenAiCompatBaseUrl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.net.URI;
import java.time.Duration;
import com.example.lms.infra.resilience.IrregularityProfiler;
import com.example.lms.infra.resilience.BypassRoutingService;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.util.QueryTypeHeuristics;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugProbeType;
import com.abandonware.ai.agent.integrations.TextUtils;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.prompt.PromptContext;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.domain.enums.VisionMode;
import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.domain.enums.MemoryGateProfile;
import com.example.lms.learning.chat.LearningSignal;
import com.example.lms.learning.chat.LearningSignalKind;
import com.example.lms.learning.context.RagLearningSupportContext;
import com.example.lms.learning.context.LearningContextSourceService;
import com.example.lms.nlp.QueryDomainClassifier;
import com.example.lms.orchestration.OrchStageKeys;
import com.example.lms.orchestration.StagePolicyProperties;
import com.example.lms.probe.needle.NeedleProbeEngine;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.InfoFailurePatterns;
import com.example.lms.service.routing.RouteSignal;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.orchestration.OrchestrationSignals;
import com.example.lms.service.rag.EvidenceAnswerComposer;
import com.example.lms.service.rag.RagEvidenceAttributionService;
import com.example.lms.service.verbosity.VerbosityDetector;
import com.example.lms.service.verbosity.VerbosityProfile;
import com.example.lms.service.verbosity.SectionSpecGenerator;
import com.example.lms.service.answer.LengthVerifierService;
import com.example.lms.service.answer.AnswerExpanderService;
import com.example.lms.util.HtmlTextUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.HttpException;
import com.example.lms.search.QueryHygieneFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.RerankKnobResolver;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import com.example.lms.service.disambiguation.QueryDisambiguationService;
import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.service.subject.SubjectAnalysis;
import com.example.lms.service.rag.detector.UniversalDomainDetector;
import com.example.lms.service.strategy.DomainStrategyFactory;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.MemoryReinforcementService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import java.util.stream.Collectors;
import dev.langchain4j.data.message.UserMessage;
import com.example.lms.service.rag.LangChainRAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.CollectionUtils;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import com.example.lms.service.ChatResult;
import java.util.regex.Pattern;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import com.example.lms.util.MLCalibrationUtil;
import com.example.lms.util.FutureTechDetector;
import com.example.lms.search.SmartQueryPlanner;
import com.example.lms.search.policy.SearchPolicyDecision;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.probe.EvidenceSignals;
import com.example.lms.search.probe.NeedleContribution;
import com.example.lms.search.probe.NeedleContributionEvaluator;
import com.example.lms.search.probe.NeedleOutcomeRewarder;
import com.example.lms.search.probe.NeedleProbeProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.routing.plan.RoutingPlanService;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.AttachmentService;
import com.example.lms.artplate.NineArtPlateGate;
import com.example.lms.artplate.ArtPlateSpec;
import com.example.lms.artplate.PlateContext;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import static com.example.lms.service.rag.LangChainRAGService.META_SID;
import java.util.function.Function;

import com.example.lms.service.FactVerifierService; // 寃利??쒕퉬??二쇱엯

/* ---------- LangChain4j ---------- */
import java.util.stream.Stream; // buildUnifiedContext ?ъ슜

// === Modularisation components (extracted from ChatService) ===

/* ---------- RAG ---------- */

import com.example.lms.transform.QueryTransformer;
//  hybrid retrieval content classes
import dev.langchain4j.data.document.Metadata; // [HARDENING]
import java.util.Map; // [HARDENING]

// (dedup) Qualifier already imported above
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment; // ??for evidence regen

/**
 * 以묒븰 ?덈툕 - OpenAI-Java 쨌 LangChain4j 쨌 RAG ?듯빀. (v7.2, RAG ?곗꽑 ?⑥튂 ?곸슜)
 * <p>
 * - LangChain4j 1.0.1 API ???
 * - "??RAG ?곗꽑" 4-Point ?⑥튂(?꾨＼?꾪듃 媛뺥솕 / 硫붿떆吏 ?쒖꽌 / RAG 湲몄씠 ?쒗븳 / ?붾쾭洹?濡쒓렇) 諛섏쁺
 * </p>
 *
 * <p>
 * 2024-08-06: ML 湲곕컲 蹂댁젙/蹂닿컯/?뺤젣/利앷컯 湲곕뒫???꾩엯?덉뒿?덈떎. ?덈줈???꾨뱶
 * {@code mlAlpha}, {@code mlBeta}, {@code mlGamma}, {@code mlMu},
 * {@code mlLambda} 諛?{@code mlD0} ? application.yml ?먯꽌 議곗젙????
 * ?덉뒿?덈떎. {@link MLCalibrationUtil} 瑜??ъ슜?섏뿬 LLM ?뚰듃 寃???먮뒗
 * 硫붾え由?媛뺥솕瑜??꾪븳 媛以묒튂瑜?怨꾩궛?????덉쑝硫? 蹂??덉젣?먯꽌??
 * {@link #reinforceAssistantAnswer(String, String, String)} ?댁뿉??
 * 臾몄옄??湲몄씠瑜?嫄곕━ d 濡??ъ슜?섏뿬 媛以묒튂 ?먯닔瑜?蹂댁젙?⑸땲??
 * ?ㅼ젣 ?ъ슜 ?쒖뿉???꾨찓?몄뿉 留욌뒗 d 媛믪쓣 ?낅젰??二쇱꽭??
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ChatWorkflow {
    private static final Logger log = LoggerFactory.getLogger(ChatWorkflow.class);
    private static final ObjectMapper OPENAI_COMPAT_MAPPER = new ObjectMapper();
    private static final List<String> HIGH_RISK_TERMS = List.of(
            "\uC9C4\uB2E8",
            "\uCC98\uBC29",
            "\uC99D\uC0C1",
            "\uBC95\uB960",
            "\uC18C\uC1A1",
            "\uD615\uB7C9",
            "\uD22C\uC790",
            "\uC218\uC775\uB960",
            "\uBCF4\uD5D8\uAE08");
    @Value("${openai.retry.max-attempts:0}")
    private int llmMaxAttempts;

    @Value("${openai.retry.backoff-ms:350}")
    private long llmBackoffMs;

    /**
     * Hard cap to avoid pathological retry+timeout accumulation.
     * <p>
     * 0 means "auto": cap ~= (timeout + backoff + small overhead).
     */
    @Value("${openai.retry.max-total-ms:0}")
    private long llmRetryMaxTotalMs;

    /**
     * When evidence is already present, a timeout is usually best handled by fast
     * evidence-only fallback.
     */
    @Value("${openai.retry.fast-bailout-on-timeout-with-evidence:true}")
    private boolean llmFastBailoutOnTimeoutWithEvidence;

    @Value("${openai.retry.fast-bailout-min-timeout-hits-with-evidence:1}")
    private int llmFastBailoutMinTimeoutHitsWithEvidence;

    @Value("${llm.timeout-seconds:12}")
    private int llmTimeoutSeconds;
    @Value("${llm.requested-model.timeout-seconds:180}")
    private int requestedModelTimeoutSeconds;
    @Value("${llm.cost.trace.warn-input-tokens:12000}")
    private int costTraceWarnInputTokens;
    @Value("${llm.openai.endpoint-compat.fallback-to-completions:${nova.llm.endpoint-compat.fallback-to-completions:true}}")
    private boolean openAiFallbackToCompletions;

    @Value("${llm.openai.endpoint-compat.fallback-to-responses:${nova.llm.endpoint-compat.fallback-to-responses:true}}")
    private boolean openAiFallbackToResponses;

    @Value("${llm.openai.endpoint-compat.fallback-debug:${nova.llm.endpoint-compat.fallback-debug:false}}")
    private boolean openAiEndpointCompatDebug;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IrregularityProfiler irregularityProfiler;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebugEventStore debugEventStore;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RagEvidenceAttributionService ragEvidenceAttributionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DynamicContextCompressor promptContextCompressor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LearningContextSourceService learningContextSourceService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelRuntimeHealthTracker modelRuntimeHealthTracker;

    // Optional: deep web search retriever (SmartQueryPlanner 湲곕컲)
    @Autowired(required = false)
    private AnalyzeWebSearchRetriever analyzeWebSearchRetriever;

    // Planner Nexus: auto-select plan id when absent
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.orchestration.WorkflowOrchestrator workflowOrchestrator;

    @Autowired(required = false)
    private com.example.lms.plan.PlanHintApplier planHintApplier;

    // Pipeline DSL (projection_agent.v1.yaml)
    @Autowired(required = false)
    private com.example.lms.service.rag.plan.PlanDslLoader planDslLoader;
    @Autowired(required = false)
    private com.example.lms.service.rag.plan.PlanPolicyMapper planPolicyMapper;
    @Autowired(required = false)
    private com.example.lms.service.rag.plan.PlanModelResolver planModelResolver;
    @Autowired(required = false)
    private com.example.lms.service.prompt.PromptAssetService promptAssetService;

    @Autowired(required = false)
    private com.example.lms.service.rag.ProjectionMergeService projectionMergeService;

    // (UAW) Needle probe: tiny 2-pass web detour when evidence quality is weak.
    @Autowired(required = false)
    private NeedleProbeEngine needleProbeEngine;

    // MERGE_HOOK:PROJ_AGENT::ORCH_STAGE_POLICY_WIRE
    @Autowired(required = false)
    private StagePolicyProperties stagePolicy;
    private final @Qualifier("queryTransformer") QueryTransformer queryTransformer;
    @Autowired(required = false)
    private java.util.Map<String, CrossEncoderReranker> rerankers;
    // NOTE: CircuitBreaker/TimeLimiter beans were previously injected but never
    // used.
    // Keeping the retry logic simple and explicit avoids "phantom" dependencies.
    private final QueryDomainClassifier queryDomainClassifier = new QueryDomainClassifier();
    private final GuardProfileProps guardProfileProps;
    @Value("${abandonware.reranker.backend:embedding-model}")
    private String rerankBackend;

    @Value("${abandonware.reranker.onnx.runtime-enabled:false}")
    private boolean onnxRuntimeEnabled;

    @Value("${abandonware.reranker.onnx.allow-auto:false}")
    private boolean onnxAutoSelectionEnabled;

    /**
     * Determine the active reranker.
     *
     * Supports per-plan override via PlanHintApplier(meta/planOverrides):
     * - rerank_backend / rerank.backend: onnx-runtime|embedding-model|noop|auto
     * - onnx.enabled: false will prevent selecting the ONNX backend in auto mode
     *
     * Fail-soft:
     * - If no matching bean is present, falls back to embedding or any available
     * reranker.
     */
    private CrossEncoderReranker reranker(String backendOverride, Boolean onnxEnabledOverride,
            boolean crossEncoderEnabled) {
        if (rerankers == null || rerankers.isEmpty()) {
            return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
        }

        String backend = backendOverride;
        if (backend == null || backend.isBlank())
            backend = rerankBackend;
        backend = (backend == null ? "" : backend.trim().toLowerCase(Locale.ROOT));

        boolean onnxAllowed = onnxRuntimeEnabled && onnxEnabledOverride != Boolean.FALSE;
        boolean onnxBreakerOpen = false;
        try {
            onnxBreakerOpen = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.RERANK_ONNX));
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("rerank.onnxBreakerOpen", ignore);
        }

        boolean hasOnnx = rerankers.containsKey("onnxCrossEncoderReranker");
        boolean hasEmbedding = rerankers.containsKey("embeddingCrossEncoderReranker");
        boolean hasNoop = rerankers.containsKey("noopCrossEncoderReranker");
        boolean onnxUsable = crossEncoderEnabled && onnxAllowed && hasOnnx && !onnxBreakerOpen;
        boolean autoOnnxUsable = onnxAutoSelectionEnabled && onnxUsable;

        String key;
        switch (backend) {
            case "", "auto" -> {
                // aggressive auto:
                // - CE disabled -> noop
                // - ONNX usable -> ONNX
                // - else -> embedding (if present) else noop
                if (!crossEncoderEnabled) {
                    key = "noopCrossEncoderReranker";
                } else if (autoOnnxUsable) {
                    key = "onnxCrossEncoderReranker";
                } else if (hasEmbedding) {
                    key = "embeddingCrossEncoderReranker";
                } else {
                    key = "noopCrossEncoderReranker";
                }
            }
            case "onnx-runtime", "onnx" -> {
                if (onnxUsable) {
                    key = "onnxCrossEncoderReranker";
                } else {
                    // explicit onnx requested but not usable ??fail-soft fallback
                    key = hasEmbedding ? "embeddingCrossEncoderReranker"
                            : "noopCrossEncoderReranker";
                }
            }
            case "embedding-model", "embedding", "bi-encoder", "biencoder" -> key = "embeddingCrossEncoderReranker";
            case "noop", "none", "disabled" -> key = "noopCrossEncoderReranker";
            default -> key = "embeddingCrossEncoderReranker";
        }

        CrossEncoderReranker r = rerankers.get(key);
        if (r != null)
            return r;

        // final fallback order: embedding -> noop
        if (hasEmbedding)
            return rerankers.get("embeddingCrossEncoderReranker");
        if (hasNoop)
            return rerankers.get("noopCrossEncoderReranker");
        return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
    }

    /* ????????????????????????????? DI ?????????????????????????????? */

    private final ChatHistoryService chatHistoryService;
    private final EvidenceAwareGuard evidenceAwareGuard;
    private final QueryDisambiguationService disambiguationService;
    private final SubjectResolver subjectResolver;
    private final UniversalDomainDetector domainDetector;
    private final DomainStrategyFactory domainStrategyFactory;
    // The OpenAI-Java SDK has been removed. The application now exclusively uses
    // LangChain4j's ChatModel. To retain the original field order and ensure
    // Spring can still construct this class via constructor injection, we leave
    // a shim field here. It is never initialised or used.
    private final ChatModel chatModel; // 湲곕낯 LangChain4j ChatModel
    private final DynamicChatModelFactory dynamicChatModelFactory;
    private final KeyResolver keyResolver;
    private final @Qualifier("openaiWebClient") WebClient openaiWebClient;
    private final MemoryReinforcementService memorySvc;
    private final FactVerifierService verifier; // ???좉퇋 二쇱엯

    // - 泥댁씤 罹먯떆 ??젣
    // private final com.github.benmanes.caffeine.cache.LoadingCache<String,
    // ConversationalRetrievalChain> chains = /* ... */

    private final LangChainRAGService ragSvc;

    // ?대? ?덈뒗 DI ?꾨뱶 ?꾨옒履쎌뿉 異붽?
    private final WebSearchProvider webSearchProvider;
    private final QueryContextPreprocessor qcPreprocessor; // ???숈쟻 洹쒖튃 ?꾩쿂由ш린

    private final SmartQueryPlanner smartQueryPlanner; // 燧낉툘 NEW DI
    // Centralised planner facade (caching + stability)
    private final RoutingPlanService routingPlanService;
    // Search policy tuning (mode-based slicing/topK/expansion)
    private final SearchPolicyEngine searchPolicyEngine;
    // Needle probe (2-pass retrieval) orchestration
    private final NeedleProbeProperties needleProbeProperties;
    private final NeedleContributionEvaluator needleContributionEvaluator;
    private final NeedleOutcomeRewarder needleOutcomeRewarder;
    private final AuthorityScorer authorityScorer;
    // Inject Spring environment for guard checks. This allows reading
    // guard.evidence_regen.enabled.
    private final Environment env;
    // ?뵻 NEW: ?ㅼ감???꾩쟻쨌蹂닿컯쨌?⑹꽦湲?
    // ?뵻 ?⑥씪 ?⑥뒪 ?ㅼ??ㅽ듃?덉씠?섏쓣 ?꾪빐 泥댁씤 罹먯떆???쒓굅

    private final HybridRetriever hybridRetriever;
    private final WebSearchRetriever webSearchRetriever;
    private final NineArtPlateGate nineArtPlateGate;
    private final PromptBuilder promptBuilder;
    private final ModelRouter modelRouter;
    // ??Verbosity & Expansion
    private final VerbosityDetector verbosityDetector;
    private final SectionSpecGenerator sectionSpecGenerator;
    private final LengthVerifierService lengthVerifier;
    private final AnswerExpanderService answerExpander;
    private final EvidenceAnswerComposer evidenceAnswerComposer;
    private final BypassRoutingService bypassRoutingService;
    private final com.example.lms.ensemble.EnsembleFinalAnswerService ensembleFinalAnswerService;
    // ??Memory evidence I/O
    private final com.example.lms.service.rag.handler.MemoryHandler memoryHandler;
    private final com.example.lms.service.rag.handler.MemoryWriteInterceptor memoryWriteInterceptor;
    // ?좉퇋: ?숈뒿 湲곕줉 ?명꽣?됲꽣
    private final com.example.lms.learning.gemini.LearningWriteInterceptor learningWriteInterceptor;
    // ?좉퇋: ?댄빐 ?붿빟 諛?湲곗뼲 紐⑤뱢 ?명꽣?됲꽣
    private final com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor understandAndMemorizeInterceptor;
    /** In-flight cancel flags per session (best-effort) */
    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    // [METRICS] 媛꾨떒??in-memory 移댁슫??(Micrometer ?곕룞 ?꾧퉴吏 ?꾩떆)
    private final AtomicLong rescueCount = new AtomicLong();
    private final AtomicLong emptyTopDocsCount = new AtomicLong();
    private final AtomicLong freeIdeaCount = new AtomicLong();

    @Value("${rag.hybrid.top-k:50}")
    private int hybridTopK;
    @Value("${rag.rerank.top-n:10}")
    private int rerankTopN;
    // ??reranker keep-top-n by verbosity
    @Value("${reranker.keep-top-n.brief:5}")
    private int keepNBrief;
    @Value("${reranker.keep-top-n.standard:8}")
    private int keepNStd;
    @Value("${reranker.keep-top-n.deep:12}")
    private int keepNDeep;
    @Value("${reranker.keep-top-n.ultra:16}")
    private int keepNUltra;
    /**
     * ?섏씠釉뚮━???고쉶(吏꾨떒??: true硫?HybridRetriever瑜?嫄대꼫?곌퀬 ?⑥씪?⑥뒪濡?泥섎━
     */
    @Value("${debug.hybrid.bypass:false}")
    private boolean bypassHybrid;

    // [FUTURE_TECH FIX] Feature flags for unreleased / next-gen product handling
    @Value("${rag.latest-tech.enabled:true}")
    private boolean latestTechEnabled;

    @Value("${rag.latest-tech.auto-disable-vector:true}")
    private boolean latestTechAutoDisableVector;

    @Value("${rag.latest-tech.skip-memory-read:true}")
    private boolean latestTechSkipMemoryRead;

    @Value("${naver.reinforce-assistant:false}")
    private boolean enableAssistantReinforcement;

    /* ??????????????????????? ?ㅼ젙 (application.yml) ??????????????????????? */
    // 湲곗〈 ?곸닔 吏?뚮룄 ?섍퀬 洹몃?濡??щ룄 ?곴??놁쓬

    @Value("${openai.web-context.max-tokens:8000}")
    private int defaultWebCtxMaxTokens; // ?뙋 Live-Web 理쒕? ?좏겙

    @Value("${openai.mem-context.max-tokens:7500}")
    private int defaultMemCtxMaxTokens; // ??

    @Value("${openai.rag-context.max-tokens:5000}")
    private int defaultRagCtxMaxTokens; // ??
    // Resolve the API key from configuration or environment. Prefer the
    // `openai.api.key` property and fall back to OPENAI_API_KEY. Do not
    // include other vendor keys (e.g. GROQ_API_KEY) to prevent invalid
    // authentication.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String openaiApiKey;
    @Value("${llm.chat-model:gemma4:26b}")
    private String defaultModel;
    @Value("${llm.provider:local}")
    private String llmProvider;
    @Value("${openai.fine-tuning.custom-model-id:}")
    private String tunedModelId;
    @Value("${openai.api.temperature.default:0.7}")
    private double defaultTemp;
    @Value("${openai.api.top-p.default:1.0}")
    private double defaultTopP;
    @Value("${openai.api.history.max-messages:6}")
    private int maxHistory;
    // ChatService ?대옒???꾨뱶 ?뱀뀡??
    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    /* ???????????????? Memory ?⑥튂: ?꾨＼?꾪듃 ???????????????? */

    /* ?뵺 怨듭떇 異쒖쿂 ?꾨찓???붿씠?몃━?ㅽ듃(?⑥튂/怨듭?瑜? */
    @Value("${search.official.domains:genshin.hoyoverse.com,hoyolab.com,youtube.com/@GenshinImpact,x.com/GenshinImpact}")
    private String officialDomainsCsv;

    // Legacy inline prompt constants removed; final prompt assembly is PromptBuilder-owned.
    /**
     * Additional safety boundary for sensitive topics.
     *
     * <p>
     * Injected as a system message only at the final answer related steps
     * (draft answer / final polish), so creative/exploration steps are not
     * unintentionally constrained.
     * </p>
     */
    private static final String PRIVACY_BOUNDARY_SYS = """
            [PRIVACY_BOUNDARY]
            - Do NOT claim to remember the user, past chats, or any personal details beyond what is explicitly provided in this conversation.
            - Do NOT invent personal stories, experiences, or "memories".
            - Do NOT infer or guess identities, addresses, phone numbers, emails, or other private details.
            - When uncertain, say so and give safe, general guidance.
            - If the user message contains sensitive personal information, avoid repeating it verbatim.
            """;

    /* ?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧??ML 蹂댁젙 ?뚮씪誘명꽣 ?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧??*/
    /**
     * Machine learning based correction parameters. These values can be
     * configured via application.yml using keys under the prefix
     * {@code ml.correction.*}. They correspond to the 慣, 棺, 款, 關,
     * 貫, and d? coefficients described in the specification. See
     * {@link MLCalibrationUtil} for details.
     */
    @Value("${ml.correction.alpha:0.0}")
    private double mlAlpha;
    @Value("${ml.correction.beta:0.0}")
    private double mlBeta;
    @Value("${ml.correction.gamma:0.0}")
    private double mlGamma;
    @Value("${ml.correction.mu:0.0}")
    private double mlMu;
    @Value("${ml.correction.lambda:1.0}")
    private double mlLambda;
    @Value("${ml.correction.d0:0.0}")
    private double mlD0;
    // 寃利?湲곕낯 ?쒖꽦???뚮옒洹?(application.yml: verification.enabled=true)
    @org.springframework.beans.factory.annotation.Value("${verification.enabled:true}")
    private boolean verificationEnabled;

    // ???????????? Guard detour cheap retry (one-shot) ?????????????????
    // When we emitted a detour due to insufficient citations, try ONE additional
    // cheap web search
    // (site-hinted) to recover citations without asking the user to re-ask.
    @Value("${guard.detour.cheap-retry.enabled:true}")
    private boolean detourCheapRetryEnabled;

    @Value("${guard.detour.cheap-retry.web-top-k:8}")
    private int detourCheapRetryWebTopK;

    @Value("${guard.detour.cheap-retry.web-budget-ms:1500}")
    private long detourCheapRetryWebBudgetMs;

    @Value("${guard.detour.cheap-retry.max-added-docs:6}")
    private int detourCheapRetryMaxAddedDocs;

    @Value("${guard.detour.cheap-retry.max-sites:1}")
    private int detourCheapRetryMaxSites;

    @Value("${guard.detour.cheap-retry.combine-sites-with-or:false}")
    private boolean detourCheapRetryCombineSitesWithOr;

    @Value("${guard.detour.cheap-retry.regen-llm.enabled:false}")
    private boolean detourCheapRetryRegenLlmEnabled;

    /**
     * Cost-control knob for probe deployments.
     * <p>
     * Even when {@code forceEscalate} is requested (entity/definitional + insufficient citations),
     * operators can disable the extra LLM regeneration call.
     * When disabled, the detour still attempts the cheap web retry and then falls back to
     * evidence-only composition.
     */
    @Value("${guard.detour.force-escalate.regen-llm.enabled:true}")
    private boolean detourForceEscalateRegenLlmEnabled;

    @Value("${guard.detour.cheap-retry.regen-llm.temperature:0.2}")
    private double detourCheapRetryRegenLlmTemperature;

    @Value("${guard.detour.cheap-retry.regen-llm.max-tokens:900}")
    private int detourCheapRetryRegenLlmMaxTokens;

    @Value("${guard.detour.cheap-retry.regen-llm.only-if-low-risk:true}")
    private boolean detourCheapRetryRegenLlmOnlyIfLowRisk;

    @Value("${guard.detour.cheap-retry.site-hints:wikipedia.org,namu.wiki,hoyolab.com}")
    private String detourCheapRetrySiteHintsCsv;

    // ???????????? Attachment injection ?????????????????
    /**
     * Service used to resolve uploaded attachment identifiers into prompt context
     * documents. Injected via constructor to allow attachments to be
     * incorporated into the PromptContext without manual bean lookup.
     */
    private final AttachmentService attachmentService;

    // =========================================================================
    // [SECTION 1] SSE/request orchestration
    // Comment-only roadmap; no extraction in this safe patch.
    // Future extraction candidate: ChatWorkflowSseManager.
    // =========================================================================

    /* ?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧??PUBLIC ENTRY ?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧?먥븧??*/

    /**
     * ?⑥씪 ?붾뱶?ъ씤?? ?붿껌 ?듭뀡???곕씪 RAG, OpenAI-Java, LangChain4j ?뚯씠?꾨씪?몄쑝濡?遺꾧린.
     */
    /* ????????????????????????? NEW ENTRY ????????????????????????? */
    /** RAG 쨌 Web 寃?됱쓣 紐⑤몢 ?쇱썙?ｌ쓣 ???덈뒗 ?뺤옣???붾뱶?ъ씤??*/
    // ???몃? 而⑦뀓?ㅽ듃 ?놁씠 ?곕뒗 ?⑥씪 踰꾩쟾?쇰줈 援먯껜
    // ChatService.java

    /**
     * RAG 쨌 WebSearch 쨌 Stand-Alone 쨌 Retrieval OFF 紐⑤몢 泥섎━?섎뒗 ?듯빀 硫붿꽌??
     */
    // ??1-?몄옄 ?섑띁 ? 而⑦듃濡ㅻ윭媛 ?몄텧
    @Cacheable(value = "chatResponses",
            // 罹먯떆 ?ㅻ뒗 ?몄뀡怨?紐⑤뜽蹂꾨줈 寃⑸━: ?숈씪 硫붿떆吏?쇰룄 ?몄뀡쨌紐⑤뜽???ㅻⅤ硫?蹂꾨룄 ???
            // Use a static helper to build the key without string concatenation
            key = "T(com.example.lms.service.ChatService).cacheKey(#req)")
    public ChatResult continueChat(ChatRequestDto req) {
        int webK = (req.getWebTopK() == null || req.getWebTopK() <= 0) ? 5 : req.getWebTopK();
        Function<String, List<String>> defaultProvider = q -> webSearchProvider.search(q, webK); // ?ㅼ씠踰?Top-K
        return continueChat(req, defaultProvider); // ???〓줈 ?꾩엫
    }

    // ?? intent/risk/濡쒓퉭 ?좏떥 ?????????????????????????????????????
    private String inferIntent(String q) {
        try {
            return qcPreprocessor.inferIntent(q);
        } catch (Exception e) {
            log.debug("[inferIntent] query transformer bypassed err={}", e.getClass().getSimpleName());
            TraceStore.putIfAbsent("queryTransformer.bypassed", "true");
            TraceStore.putIfAbsent("queryTransformer.reason", "infer_intent_failed");
            return "GENERAL";
        }
    }

    private String detectRisk(String q) {
        if (q == null)
            return null;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        return HIGH_RISK_TERMS.stream().anyMatch(s::contains) ? "HIGH" : null;
    }

    /**
     * [Dual-Vision] VisionMode 寃곗젙 濡쒖쭅
     *
     * ?곗꽑?쒖쐞:
     * 1. 怨좎쐞???꾨찓????STRICT 媛뺤젣
     * 2. ?ъ슜??紐낆떆???붿껌 ??洹몃?濡??ъ슜
     * 3. ?꾨찓??湲곕컲 ?먮룞 寃곗젙
     */
    private VisionMode decideVision(QueryDomain domain, String riskLevel, ChatRequestDto req) {
        // 湲곗〈 ?쒓렇?덉쿂??planId 媛 ?녿뒗 ?몄텧遺瑜??꾪빐 ?좎??섍퀬,
        // ?대??곸쑝濡쒕뒗 planId=null ???ｌ뼱 ?좉퇋 濡쒖쭅???ъ슜?쒕떎.
        return decideVision(domain, riskLevel, req, null);
    }

    /**
     * [Dual-Vision] VisionMode 寃곗젙 濡쒖쭅 v2
     *
     * ?곗꽑?쒖쐞:
     * 1. 怨좎쐞???꾨찓????STRICT 媛뺤젣
     * 2. ?ъ슜??紐낆떆???붿껌 ??洹몃?濡??ъ슜
     * 3. Plan ?ㅼ젙?먯꽌 吏?뺣맂 紐⑤뱶
     * 4. ?꾨찓??湲곕컲 ?먮룞 寃곗젙
     */
    private VisionMode decideVision(QueryDomain domain,
            String riskLevel,
            ChatRequestDto req,
            String planId) {
        // 1. HIGH risk ??臾댁“嫄?STRICT (?덉쟾留?
        if ("HIGH".equalsIgnoreCase(riskLevel)) {
            return VisionMode.STRICT;
        }

        // 2. ?ъ슜??紐낆떆???붿껌 (硫붿떆吏 ???뱀닔 而ㅻ㎤??湲곕컲)
        String userQuery = Optional.ofNullable(req.getMessage()).orElse("");
        if (userQuery.contains("/strict") || userQuery.contains("?꾧꺽?섍쾶")) {
            return VisionMode.STRICT;
        }
        if (userQuery.contains("/free") || userQuery.contains("?먯쑀濡?쾶")) {
            return VisionMode.FREE;
        }

        // 3. [NEW] Plan 湲곕컲 紐⑤뱶 寃곗젙
        if (planId != null && !planId.isBlank()) {
            String lower = planId.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("zero_break") || lower.contains("hypernova")) {
                return VisionMode.FREE;
            }
            if (lower.contains("safe") || lower.contains("strict")) {
                return VisionMode.STRICT;
            }
        }

        // 4. ?꾨찓??湲곕컲 ?먮룞 寃곗젙
        return switch (domain) {
            case GAME, SUBCULTURE -> VisionMode.FREE;
            case STUDY, SENSITIVE -> VisionMode.STRICT;
            default -> VisionMode.HYBRID;
        };
    }

    /**
     * [Dual-Vision] 理쒖떊/誘몃옒 Tech 荑쇰━ 媛먯?
     * ?숈뒿 而룹삤???댄썑 湲곌린???대씪?곕뱶/怨좎꽦??紐⑤뜽濡??곗꽑 ?쇱슦??
     */
    private boolean isLatestTechQuery(String query) {
        // [FUTURE_TECH FIX] Centralized detection for unreleased/next-gen tech product
        // queries
        return FutureTechDetector.isFutureTechQuery(query);
    }

    private static String getModelName(dev.langchain4j.model.chat.ChatModel m) {
        return (m == null) ? "unknown" : m.getClass().getSimpleName();
    }

    /**
     * Build a composite cache key from a chat request. This helper avoids
     * string concatenation in the SpEL expression by delegating the
     * composition to Java code. Each component is converted to a string
     * and joined with a colon separator. When the request is null or
     * fields are absent empty strings are used.
     *
     * @param req the chat request
     * @return a stable key of the form sessionId:model:message:useRag:useWebSearch
     */
    public static String cacheKey(com.example.lms.dto.ChatRequestDto req) {
        if (req == null)
            return "";
        String sid = String.valueOf(req.getSessionId());
        String model = String.valueOf(req.getModel());
        String msg = String.valueOf(SafeRedactor.hash12(req.getMessage()));
        String rag = String.valueOf(req.isUseRag());
        String web = String.valueOf(req.isUseWebSearch());
        return String.format("%s:%s:%s:%s:%s", sid, model, msg, rag, web);
    }

    private void reinforce(String sessionKey, String query, String answer,
            VisionMode visionMode,
            GuardProfile guardProfile,
            MemoryMode memoryMode) {
        try {
            reinforceAssistantAnswerWithProfile(sessionKey, query, answer, 0.5, null, visionMode, guardProfile,
                    memoryMode);
        } catch (Throwable t) {
            log.debug("[reinforce] memory reinforcement failed sessionHash={} err={}",
                    SafeRedactor.hashValue(sessionKey), t.getClass().getSimpleName());
            TraceStore.inc("memory.reinforce.failed");
        }
    }

    /**
     * ?섎룄 遺꾩꽍???듯빐 理쒖쥌 寃??荑쇰━瑜?寃곗젙?쒕떎.
     */
    /**
     * ?ъ슜?먯쓽 ?먮낯 荑쇰━? LLM???ъ옉?깊븳 荑쇰━ 以?理쒖쥌?곸쑝濡??ъ슜??荑쇰━瑜?寃곗젙?⑸땲??
     * ?ъ옉?깅맂 荑쇰━媛 ?좏슚?섍퀬, 紐⑤뜽??洹?寃곌낵???먯떊媛먯쓣 蹂댁씪 ?뚮쭔 ?ъ옉?깅맂 荑쇰━瑜??ъ슜?⑸땲??
     *
     * @param originalQuery ?ъ슜?먯쓽 ?먮낯 ?낅젰 荑쇰━
     * @param r             QueryRewriteResult, ?ъ옉?깅맂 荑쇰━? ?좊ː???먯닔瑜??ы븿
     * @return 理쒖쥌?곸쑝濡?RAG 寃?됱뿉 ?ъ슜??荑쇰━ 臾몄옄??
     */

    private String decideFinalQuery(String originalQuery, Long sessionId) {
        if (originalQuery == null || originalQuery.isBlank())
            return originalQuery;
        List<String> history = (sessionId != null)
                ? chatHistoryService.getFormattedRecentHistory(sessionId, 5)
                : java.util.Collections.emptyList();

        DisambiguationResult r = disambiguationService.clarify(originalQuery, history);
        if (r != null && r.isConfident() && r.getRewrittenQuery() != null && !r.getRewrittenQuery().isBlank()) {
            return r.getRewrittenQuery();
        }
        return originalQuery; // ????以꾩씠 諛섎뱶???덉뼱????
    }

    // ??2-?몄옄 ?ㅼ젣 援ы쁽 (?ㅻ뜑쨌以묎큵??諛섎뱶???ы븿!)
    public ChatResult continueChat(ChatRequestDto req,
            Function<String, List<String>> externalCtxProvider) {

        // ?? ?몄뀡???뺢퇋???⑥씪 ???꾪뙆) ???????????????????????????????
        String sessionKey = Optional.ofNullable(req.getSessionId())
                .map(String::valueOf)
                .map(s -> {
                    if (s.startsWith("chat-"))
                        return s;
                    if (s.matches("\\d+")) {
                        return String.format("chat-%s", s);
                    }
                    return s;
                })
                .orElse(UUID.randomUUID().toString());

        // Ensure any ThreadLocal trace values from a previous request are cleared
        // before starting this run. SmartQueryPlanner also clears TraceStore, but
        // Web/RAG-only flows may bypass it.
        try {
            TraceStore.clear();
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("traceStore.clear", ignore);
        }

        // [PATCH] Rehydrate minimal trace envelope after TraceStore.clear() so
        // planners/retrievers
        // keep breadcrumbs and SmartQueryPlanner does not wipe the trace bag again.
        try {
            // Seed a per-run id; SmartQueryPlanner respects this and won't clear TraceStore
            // again.
            TraceStore.put("trace.runId", String.format("chat:%s:%d", SafeRedactor.hashValue(sessionKey), System.nanoTime()));

            // Preserve request/browser sid as a secondary breadcrumb before we override
            // MDC[sid].
            String __requestSid = null;
            try {
                __requestSid = org.slf4j.MDC.get("sid");
                if (__requestSid != null && !__requestSid.isBlank() && sessionKey != null
                        && !__requestSid.equals(sessionKey)) {
                    TraceStore.putIfAbsent("req.sid", SafeRedactor.hashValue(__requestSid));
                    if (org.slf4j.MDC.get("requestSid") == null) {
                        org.slf4j.MDC.put("requestSid", __requestSid);
                    }
                }
            } catch (Throwable __ignoreReqSid) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.requestSid", __ignoreReqSid);
            }

            // Keep the numeric chat session id as a hint for debugging.
            try {
                if (req.getSessionId() != null) {
                    TraceStore.put("chatSessionHash", SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
                    if (org.slf4j.MDC.get("chatSessionId") == null) {
                        org.slf4j.MDC.put("chatSessionId", String.valueOf(req.getSessionId()));
                    }
                }
            } catch (Throwable __ignoreChatSid) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.chatSessionId", __ignoreChatSid);
            }

            // Keep request correlation id in TraceStore (best-effort from MDC).
            String __traceId = null;
            try {
                __traceId = org.slf4j.MDC.get("traceId");
                if (__traceId == null || __traceId.isBlank())
                    __traceId = org.slf4j.MDC.get("trace");
                if (__traceId == null || __traceId.isBlank())
                    __traceId = org.slf4j.MDC.get("x-request-id");
            } catch (Throwable __ignoreMdc) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.mdcTraceId", __ignoreMdc);
            }
            if (__traceId == null || __traceId.isBlank()) {
                __traceId = java.util.UUID.randomUUID().toString();
            }
            TraceStore.putIfAbsent("trace.id", __traceId);

            // Ensure session breadcrumb follows the normalized sessionKey.
            if (sessionKey != null && !sessionKey.isBlank()) {
                TraceStore.put("sid", SafeRedactor.hashValue(sessionKey));
                try {
                    org.slf4j.MDC.put("sid", sessionKey);
                    org.slf4j.MDC.put("sessionId", sessionKey);
                } catch (Throwable __ignoreMdc2) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.mdcSession", __ignoreMdc2);
                }
            }

            // Emit a minimal orch event for cross-cutting trace contracts/debug UI.
            try {
                java.util.Map<String, Object> __bc = new java.util.LinkedHashMap<>();
                __bc.put("conversationSidHash", SafeRedactor.hashValue(sessionKey));
                __bc.put("requestSidHash", SafeRedactor.hashValue(__requestSid));
                __bc.put("chatSessionHash", SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
                __bc.put("traceIdHash", SafeRedactor.hashValue(__traceId));
                ai.abandonware.nova.orch.trace.OrchEventEmitter.breadcrumb(
                        "conversation.breadcrumb.seed",
                        "Seeded conversation breadcrumb in MDC/TraceStore",
                        "ChatWorkflow.continueChat",
                        __bc);
            } catch (Throwable __ignoreBc) {
                ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.breadcrumb", __ignoreBc);
            }

        } catch (Exception __ignoreTraceSeed) {
            ChatWorkflowTraceSuppressions.traceSuppressed("traceSeed.envelope", __ignoreTraceSeed);
        }

        // ?? 0) ?ъ슜???낅젰 ?뺣낫 ?????????????????????????????????????
        final String userQuery = Optional.ofNullable(req.getMessage()).orElse("");
        final String requestedModel = Optional.ofNullable(req.getModel()).orElse("");

        if (userQuery.isBlank()) {
            return ChatResult.of("?뺣낫 ?놁쓬", String.format("lc:%s", chatModel.getClass().getSimpleName()), true);
        }

        // Domain classification for this query
        QueryDomain queryDomain = queryDomainClassifier.classify(userQuery);

        // [NEW] AnswerMode / MemoryMode from HTTP request (null-safe)
        AnswerMode answerMode = AnswerMode.fromString(req.getMode());
        MemoryMode memoryMode = MemoryMode.fromString(req.getMemoryMode());

        GuardProfile guardProfile;
        // ?ъ슜?먭? mode瑜?紐낆떆??寃쎌슦 AnswerMode 湲곕컲 GuardProfile濡?留ㅽ븨
        if (req.getMode() != null && !req.getMode().isBlank()) {
            guardProfile = GuardProfile.fromAnswerMode(answerMode);
        } else {
            // QueryDomain 湲곕컲 湲곕낯 GuardProfile 寃곗젙 (?쒖꽑1/2/3 ?듯빀)
            guardProfile = guardProfileProps.profileFor(queryDomain);
        }
        // EvidenceAwareGuard ?먯꽌 ?ъ슜???꾩옱 ?꾨줈?뚯씪 ?깅줉
        guardProfileProps.setCurrentProfile(guardProfile);

        // ??GuardContextHolder ?묐ぉ: 而⑦듃濡ㅻ윭媛 set??而⑦뀓?ㅽ듃瑜??ㅼ??ㅽ듃?덉씠?섏뿉??蹂닿컯
        // (?놁쑝硫?嫄대뱶由ъ? ?딆쓬; ThreadLocal?대?濡??ш린???앹꽦/clear???섏? ?딅뒗??)
        // ??gctx null 諛⑹?: 而⑦듃濡ㅻ윭/?꾪꽣媛 GuardContext瑜????ъ? 寃쎈줈?먯꽌??NPE 諛⑹뼱
        var gctx = GuardContextHolder.getOrDefault();
        com.example.lms.service.rag.plan.ProjectionAgentPlanSpec projectionPlan = null;
        boolean projectionPipeline = false;
        com.example.lms.plan.PlanHints planHints = null;
        if (gctx != null) {
            gctx.setEntityQueryFromQuestion(userQuery);
            if (gctx.getMode() == null || gctx.getMode().isBlank())
                gctx.setMode(answerMode.name());
            // planId媛 鍮꾩뼱?덉쑝硫?WorkflowOrchestrator濡??먮룞 ?좏깮
            if (workflowOrchestrator != null) {
                try {
                    boolean hasDocumentEvidence = req != null
                            && req.getAttachmentIds() != null
                            && !req.getAttachmentIds().isEmpty();
                    workflowOrchestrator.ensurePlanSelected(gctx, answerMode, queryDomain, userQuery, hasDocumentEvidence);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("workflow.planSelected", ignore); }
            }
            if (gctx.getPlanId() == null || gctx.getPlanId().isBlank())
                gctx.setPlanId("safe_autorun.v1");

            try {
                if (planHintApplier != null) {
                    planHints = planHintApplier.load(gctx.getPlanId());
                    planHintApplier.applyToGuardContext(planHints, gctx);
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("planHints.applyGuardContext", ignore); }

            // Pipeline DSL: projection_agent.v1.yaml (dual-view + merge)
            if (planDslLoader != null && gctx.getPlanId() != null) {
                projectionPlan = planDslLoader.loadProjectionAgent(gctx.getPlanId()).orElse(null);
                projectionPipeline = (projectionPlan != null);
            }

            if (projectionPipeline && planPolicyMapper != null && guardProfileProps != null) {
                // Strict branch is the primary execution context in projection_agent.v1
                var strict = projectionPlan.viewMemorySafe() != null ? projectionPlan.viewMemorySafe() : null;
                String guardProfileStr = strict != null ? strict.guardProfile()
                        : (projectionPlan.defaults() != null ? projectionPlan.defaults().guardProfile() : null);
                guardProfile = planPolicyMapper.resolveGuardProfile(guardProfileStr, guardProfile);

                // If user explicitly provided memoryMode, do not override.
                boolean userExplicitMemoryMode = (req.getMemoryMode() != null && !req.getMemoryMode().isBlank());
                String memoryProfileStr = strict != null ? strict.memoryProfile()
                        : (projectionPlan.defaults() != null ? projectionPlan.defaults().memoryProfile() : null);
                if (!userExplicitMemoryMode) {
                    memoryMode = planPolicyMapper.resolveMemoryMode(memoryProfileStr, memoryMode);
                }

                guardProfileProps.setCurrentProfile(guardProfile);
            }

            if (gctx.getGuardLevel() == null || gctx.getGuardLevel().isBlank())
                gctx.setGuardLevel(guardProfile.name());
            if (gctx.getMemoryProfile() == null || gctx.getMemoryProfile().isBlank()) {
                gctx.setMemoryProfile(memoryMode == MemoryMode.EPHEMERAL ? "NONE" : "MEMORY");
            }
        }

        // Per-plan overrides (projection_agent.v1.yaml): model / traits / token budget
        // / verification
        String effectiveRequestedModel = requestedModel;
        ChatRequestDto llmReq = req;
        if (projectionPipeline && projectionPlan != null) {
            var strictCfg = projectionPlan.viewMemorySafe() != null ? projectionPlan.viewMemorySafe() : null;
            if (strictCfg != null) {
                if (planModelResolver != null) {
                    String resolved = planModelResolver.resolveRequestedModel(strictCfg.model());
                    if (StringUtils.hasText(resolved)) {
                        effectiveRequestedModel = resolved;
                        llmReq = llmReq.toBuilder().model(resolved).build();
                    }
                }
                if (strictCfg.maxTokens() != null && strictCfg.maxTokens() > 0) {
                    llmReq = llmReq.toBuilder().maxTokens(strictCfg.maxTokens()).build();
                }
                if (strictCfg.traits() != null && !strictCfg.traits().isEmpty()) {
                    llmReq = llmReq.toBuilder().traits(strictCfg.traits()).build();
                }
                boolean citationsEnabled = projectionPlan.defaults() != null
                        && Boolean.TRUE.equals(projectionPlan.defaults().citations());
                if (citationsEnabled) {
                    // In this codebase, verification only runs if request flag is true.
                    llmReq = llmReq.toBuilder().useVerification(true).build();
                }
            }
        }

        // Final copy for lambda expressions
        final String effectiveRequestedModelFinal = effectiveRequestedModel;

        // [Dual-Vision] VisionMode 寃곗젙
        String riskLevel = detectRisk(userQuery);
        VisionMode visionMode = decideVision(queryDomain, riskLevel, req, gctx != null ? gctx.getPlanId() : null);
        log.debug("[DualVision] queryDomain={}, visionMode={}", queryDomain, visionMode);

        // ?? 0-A) ?몄뀡ID ?뺢퇋??& 荑쇰━ ?ъ옉??Disambiguation) ?????????
        Long sessionIdLong = parseNumericSessionId(req.getSessionId());
        throwIfCancelled(sessionIdLong); // ??異붽?

        java.util.List<String> recentHistory = (sessionIdLong != null)
                ? chatHistoryService.getFormattedRecentHistory(sessionIdLong, 5)
                : java.util.Collections.emptyList();

        DisambiguationResult dr;
        // 蹂댁“ LLM ?뚮줈媛 ?대? OPEN?대㈃ 遺덊븘?뷀븳 ?몄텧???섏? ?딄퀬 ?먮Ц?쇰줈 吏꾪뻾
        if (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.DISAMBIGUATION_CLARIFY)) {
            dr = new DisambiguationResult();
            dr.setRewrittenQuery(userQuery);
            dr.setConfidence("low");
            dr.setScore(0.0);
        } else {
            dr = disambiguationService.clarify(userQuery, recentHistory);
        }

        final String finalQuery;
        if (dr != null && dr.isConfident()
                && dr.getRewrittenQuery() != null && !dr.getRewrittenQuery().isBlank()) {
            finalQuery = dr.getRewrittenQuery();
        } else {
            finalQuery = userQuery;
        }

        // 0-B) Subject / Domain / Strategy 遺꾩꽍 (?쒖닔 ?먮컮)
        SubjectAnalysis analysis = subjectResolver.analyze(finalQuery, recentHistory, dr);
        String domain = domainDetector.detect(finalQuery, dr);
        DomainStrategyFactory.SearchStrategy searchStrategy = domainStrategyFactory.createStrategy(analysis, domain);

        if (log.isDebugEnabled()) {
            // SLF4J placeholder??臾몄옄??由ы꽣???덉뿉???ъ슜?댁빞 ?섎ŉ, 遺덊븘?뷀븳 ?곗샂??")???쒓굅?⑸땲??
            log.debug("[Domain] queryHash={}, queryLength={}, category={}, domain={}, profile={}",
                    SafeRedactor.hash12(finalQuery),
                    finalQuery == null ? 0 : finalQuery.length(),
                    analysis.getCategory(), domain, searchStrategy.getSearchProfile());
        }

        // ?? 0-1) Verbosity 媛먯? & ?뱀뀡 ?ㅽ럺 ?????????????????????????
        VerbosityProfile detectedVp = verbosityDetector.detect(finalQuery);
        String intent = inferIntent(finalQuery);
        // Pass detected domain into section spec generator so domain-specific templates
        // can be applied.
        List<String> sections = sectionSpecGenerator.generate(intent, domain, detectedVp.hint());

        // ?? 1) 寃???듯빀: Self-Ask ??HybridRetriever ??Cross-Encoder Rerank ?
        // 0-2) Retrieval ?뚮옒洹?

        boolean useWeb = req.isUseWebSearch() || searchStrategy.isUseWebSearch();
        boolean useRag = req.isUseRag() || searchStrategy.isUseVectorStore();
        if (req.getSearchMode() == com.example.lms.gptsearch.dto.SearchMode.OFF
                || Boolean.FALSE.equals(req.getUseWebSearch())) {
            useWeb = false;
        }
        if (Boolean.FALSE.equals(req.getUseRag())) {
            useRag = false;
        }

        // [FUTURE_TECH FIX] 理쒖떊/誘몄텧??李⑥꽭?) ?쒗뭹 荑쇰━????理쒖떊???곗꽑 + 援щ쾭??Vector ?ㅼ뿼 諛⑹?
        boolean futureTech = latestTechEnabled && isLatestTechQuery(finalQuery);
        if (futureTech && latestTechAutoDisableVector) {
            useWeb = true;
            useRag = false;
            log.info("[FutureTech] Web forced ON, Vector forced OFF. queryHash={}, queryLength={}",
                    SafeRedactor.hash12(finalQuery),
                    finalQuery == null ? 0 : finalQuery.length());
        }

        // plan hints: cap allowWeb/allowRag
        if (planHints != null) {
            if (planHints.allowWeb() != null && !planHints.allowWeb())
                useWeb = false;
            if (planHints.allowRag() != null && !planHints.allowRag())
                useRag = false;
        }

        // 1) (?듭뀡) ??寃??怨꾪쉷 諛??ㅽ뻾
        // ?? 蹂댁“ LLM ?μ븷 ?좏샇瑜?癒쇱? 怨꾩궛?섏뿬 ?뚮옒??以묐웾 ?④퀎瑜??ъ쟾 李⑤떒 ??
        // ?? Orchestration signal bus (STRIKE/COMPRESSION/BYPASS) ????????????
        OrchestrationSignals sig = OrchestrationSignals.compute(finalQuery, nightmareBreaker, gctx);
        if (gctx != null) {
            gctx.setStrikeMode(sig.strikeMode());
            gctx.setCompressionMode(sig.compressionMode());
            gctx.setBypassMode(sig.bypassMode());
            gctx.setWebRateLimited(sig.webRateLimited());
            if (gctx.getBypassReason() == null || gctx.getBypassReason().isBlank()) {
                gctx.setBypassReason(sig.reason());
            }
        }
        // vp??final ?좎뼵 ??議곌굔遺濡?????踰덈쭔 珥덇린?????뚮떎?먯꽌 effectively final濡??ъ슜 媛??        final VerbosityProfile vp;
        final VerbosityProfile vp;
        if (sig.strikeMode()) {
            // STRIKE 紐⑤뱶: 異쒕젰? 吏㏐퀬 ?듭떖留???꾩븘???덉씠?몃━諛??곹솴?먯꽌 fail-fast)
            int maxTokens = Math.min(detectedVp.targetTokenBudgetOut(), 768);
            int minWords = Math.min(detectedVp.minWordCount(), 90);
            vp = new VerbosityProfile("brief", minWords, maxTokens, detectedVp.audience(), detectedVp.citationStyle(),
                    detectedVp.sections());
        } else {
            vp = detectedVp;
        }
        OrchestrationHints hints = null;
        Map<String, Object> metaHints = null;

        List<String> planned = List.of();
        SearchPolicyDecision searchPolicyDecision = null;
        List<dev.langchain4j.rag.content.Content> fused = List.of();
        // Needle probe (2-pass) state (used for trace + outcome reward)
        EvidenceSignals needleBeforeSignals = EvidenceSignals.empty();
        EvidenceSignals needleAfterSignals = EvidenceSignals.empty();
        List<String> needlePlanned = List.of();
        java.util.Set<String> needleUrls = java.util.Set.of();
        java.util.List<Content> needleDocsForReward = java.util.List.of();
        boolean needleExecuted = false;
        if (useWeb) {

            // MERGE_HOOK:PROJ_AGENT::ORCH_STAGE_POLICY_PLANNER_GATE
            boolean allowPlannerByPolicy = true;
            try {
                if (stagePolicy != null && stagePolicy.isEnabled() && sig != null) {
                    allowPlannerByPolicy = stagePolicy.isStageEnabled(OrchStageKeys.PLAN_QUERY_PLANNER, sig.modeLabel(),
                            true);
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("stagePolicy.plannerGate", ignore); }

            // SearchPolicy: decide once per request (mode-based slicing/topK/expansion)
            try {
                boolean nightmareModeForPolicy = nightmareBreaker != null
                        && nightmareBreaker.isOpen(NightmareKeys.CHAT_DRAFT);
                Map<String, Object> spMeta = new HashMap<>();
                if (sig != null) {
                    spMeta.put("strikeMode", sig.strikeMode());
                    spMeta.put("compressionMode", sig.compressionMode());
                    spMeta.put("bypassMode", sig.bypassMode());
                    spMeta.put("webRateLimited", sig.webRateLimited());
                }
                spMeta.put("nightmareMode", nightmareModeForPolicy);
                if (req != null && req.getSearchMode() != null) {
                    spMeta.put("searchMode", req.getSearchMode().name());
                }
                searchPolicyDecision = searchPolicyEngine.decide(finalQuery, spMeta);
                if (searchPolicyDecision != null) {
                    TraceStore.put("search.policy.mode", searchPolicyDecision.mode().name());
                    TraceStore.put("search.policy.reason", SafeRedactor.traceLabelOrFallback(searchPolicyDecision.reason(), "unknown"));
                }
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("searchPolicy.decide", ignore);
            }
            if (futureTech && latestTechAutoDisableVector) {
                planned = List.of(finalQuery);
            } else if (sig != null && sig.auxLlmDown()) {
                // Aux LLM is degraded/hard-down: bypass planner and use the original query.
                planned = List.of(finalQuery);
            } else if (!allowPlannerByPolicy) {
                planned = List.of(finalQuery);
            } else {
                int maxBranches = gctx != null ? gctx.planInt("expand.queryBurst.count", 2) : 2;
                // safety clamp: keep planner branching bounded
                maxBranches = Math.max(2, Math.min(maxBranches, 32));
                if (searchPolicyDecision != null) {
                    maxBranches = searchPolicyEngine.tunePlannerMaxQueries(maxBranches, searchPolicyDecision);
                }
                planned = routingPlanService.plan(finalQuery, /* assistantDraft */ null, /* maxBranches */ maxBranches);
                if (planned == null || planned.isEmpty()) {
                    planned = List.of(finalQuery);
                }
            }

            // Apply policy variants (deterministic; does not call LLMs)
            if (searchPolicyDecision != null) {
                planned = searchPolicyEngine.apply(planned, finalQuery, searchPolicyDecision);
                if (planned == null || planned.isEmpty()) {
                    planned = List.of(finalQuery);
                }
            }

            // Planner can trip request-scoped aux-down / irregularity signals (e.g.
            // QueryTransformer soft-timeouts).
            // Recompute orchestration signals so plate + downstream handlers see up-to-date
            // flags.
            sig = OrchestrationSignals.compute(finalQuery, nightmareBreaker, gctx);
            if (gctx != null) {
                gctx.setStrikeMode(sig.strikeMode());
                gctx.setCompressionMode(sig.compressionMode());
                gctx.setBypassMode(sig.bypassMode());
                gctx.setWebRateLimited(sig.webRateLimited());
                if (gctx.getBypassReason() == null || gctx.getBypassReason().isBlank()) {
                    gctx.setBypassReason(sig.reason());
                }
            }
            // Nine Art Plate: decide (apply is request-scoped via metadata hints)
            PlateContext plateCtx = new PlateContext(
                    useWeb, useRag,
                    /* sessionRecur */ 0, /* evidenceCount */ 0,
                    /* authority */ 0.0, /* noisy */ false,
                    /* webGate */ (useWeb ? 0.55 : 0.30),
                    /* vectorGate */ (useRag ? 0.65 : 0.30),
                    /* memoryGate */ 0.30,
                    /* recallNeed */ (useRag ? 0.70 : 0.50));
            ArtPlateSpec plate = nineArtPlateGate.decide(plateCtx);

            boolean nightmareMode = nightmareBreaker != null
                    && nightmareBreaker.isOpen(NightmareKeys.CHAT_DRAFT);

            boolean auxLlmDown = sig.auxLlmDown();
            boolean auxDegraded = sig.auxDegraded();
            boolean auxHardDown = sig.auxHardDown();

            hints = OrchestrationHints.builder()
                    .plateId(plate.id())
                    .webTopK(plate.webTopK())
                    .vecTopK(plate.vecTopK())
                    .webBudgetMs((long) plate.webBudgetMs())
                    .vecBudgetMs((long) plate.vecBudgetMs())
                    // Soft aux degradation should not fully disable analysis/rerank.
                    // Only hard-down disables these building blocks; strike/compression gating
                    // happens below.
                    .enableSelfAsk(!nightmareMode && !auxHardDown)
                    .enableAnalyze(!nightmareMode && !auxHardDown)
                    .enableCrossEncoder(plate.crossEncoderOn() && !nightmareMode && !auxHardDown)
                    .nightmareMode(nightmareMode)
                    .auxLlmDown(auxLlmDown)
                    .allowWeb(useWeb)
                    .allowRag(useRag)
                    .build();

            // SearchPolicy: tune retrieval breadth (topK) for this request.
            // (Query slicing/expansion was already applied to the planned list.)
            if (searchPolicyDecision != null && hints != null) {
                try {
                    Integer baseWebTopK = hints.getWebTopK();
                    Integer baseVecTopK = hints.getVecTopK();
                    int tunedWeb = (baseWebTopK == null) ? 5
                            : searchPolicyEngine.tuneTopK(baseWebTopK, searchPolicyDecision);
                    int tunedVec = (baseVecTopK == null) ? 10
                            : searchPolicyEngine.tuneVecTopK(baseVecTopK, searchPolicyDecision);
                    hints = hints.toBuilder()
                            .webTopK(tunedWeb)
                            .vecTopK(tunedVec)
                            .build();
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("searchPolicy.tuneHints", ignore); }
            }

            // STRIKE/COMPRESSION/BYPASS mode wiring (same signal bus for all components)
            if (hints != null) {
                boolean strikeMode = sig.strikeMode();
                boolean compressionMode = sig.compressionMode();
                boolean bypassMode = sig.bypassMode();
                hints = hints.toBuilder()
                        .strikeMode(strikeMode)
                        .compressionMode(compressionMode)
                        .bypassMode(bypassMode)
                        .webRateLimited(sig.webRateLimited())
                        .bypassReason(gctx != null ? gctx.getBypassReason() : sig.reason())
                        // STRIKE/BYPASS: hard safety/escape-hatch => disable heavy steps.
                        // COMPRESSION: budget-saving mode; keep core reasoning (Analyze, rerank)
                        // available.
                        .enableSelfAsk(hints.isEnableSelfAsk() && !strikeMode && !compressionMode && !bypassMode)
                        .enableAnalyze(hints.isEnableAnalyze() && !strikeMode && !bypassMode)
                        .enableCrossEncoder(hints.isEnableCrossEncoder() && !strikeMode && !bypassMode)
                        .build();
            }

            metaHints = new HashMap<>();
            metaHints.put("plateId", hints.getPlateId());
            // [PATCH] Propagate request searchMode (OFF/FORCE_*) so retrieval handlers can
            // honor it.
            if (req != null && req.getSearchMode() != null) {
                metaHints.put("searchMode", req.getSearchMode().name());
            }
            metaHints.put("webTopK", hints.getWebTopK());
            metaHints.put("vecTopK", hints.getVecTopK());
            metaHints.put("webBudgetMs", hints.getWebBudgetMs());
            metaHints.put("vecBudgetMs", hints.getVecBudgetMs());
            // ?쇰? ?쇱씠釉뚮윭由щ뒗 boolean??metadata濡??꾨떖?????댁뒋媛 ?덉뼱 臾몄옄?대줈 ???
            metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
            metaHints.put("enableAnalyze", String.valueOf(hints.isEnableAnalyze()));
            metaHints.put("enableCrossEncoder", String.valueOf(hints.isEnableCrossEncoder()));
            metaHints.put("nightmareMode", String.valueOf(hints.isNightmareMode()));
            metaHints.put("auxLlmDown", String.valueOf(hints.isAuxLlmDown()));
            // UAW: expose aux soft/hard health for downstream diagnostics
            metaHints.put("auxDegraded", String.valueOf(sig.auxDegraded()));
            metaHints.put("auxHardDown", String.valueOf(sig.auxHardDown()));
            metaHints.put("allowWeb", String.valueOf(hints.isAllowWeb()));
            metaHints.put("allowRag", String.valueOf(hints.isAllowRag()));

            // Surface policy decision to downstream retrievers (no hard dependency).
            if (searchPolicyDecision != null) {
                try {
                    searchPolicyEngine.enrichMeta(metaHints, searchPolicyDecision);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("searchPolicy.enrichMeta", ignore); }
            }

            try {
                if (planHintApplier != null && planHints != null) {
                    planHintApplier.applyToHintsAndMeta(planHints, hints, metaHints);
                    // Surface guard knobs to retrieval via metadata (used by WebSearchRetriever
                    // siteFilter skip policy).
                    if (gctx != null && gctx.getMinCitations() != null) {
                        metaHints.putIfAbsent("minCitations", gctx.getMinCitations());
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("planHints.applyMeta", ignore); }
            if (hints != null && "true".equalsIgnoreCase(String.valueOf(metaHints.get("selfask.enabled")))) {
                boolean safeSelfAskOverride = !nightmareMode
                        && !auxHardDown
                        && hints.isAllowWeb()
                        && (sig == null || (!sig.strikeMode() && !sig.compressionMode() && !sig.bypassMode()));
                hints.setEnableSelfAsk(safeSelfAskOverride);
                metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
                if (!safeSelfAskOverride) {
                    metaHints.put("selfask.disabled.reason", "safety-gate");
                }
            }

            // MERGE_HOOK:PROJ_AGENT::ORCH_STAGE_POLICY_CLAMP
            applyStagePolicyClamp(sig, hints, metaHints, nightmareMode, auxHardDown);

            // [UAW] Policy-level conditional demotion:
            // - When web is effectively hard-down (both engines skipped / hybrid breaker
            // open),
            // disable the web stage for this request (fail-soft: rely on vector / other).
            // - Also disable web-dependent Analyze/SelfAsk so we don't spin on empty web
            // merges.
            boolean webHardDownStageOff = false;
            try {
                webHardDownStageOff = (sig != null && sig.webRateLimited())
                        || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited.effective"))
                        || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited"));
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("webHardDown.stageOffCheck", ignore); webHardDownStageOff = (sig != null && sig.webRateLimited());
            }
            if (webHardDownStageOff && hints != null) {
                try {
                    TraceStore.put("orch.webHardDown.stageOff", true);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("webHardDown.stageOffTrace", ignore); }
                hints.setAllowWeb(false);
                hints.setEnableAnalyze(false);
                hints.setEnableSelfAsk(false);
                metaHints.put("allowWeb", "false");
                metaHints.put("enableAnalyze", "false");
                metaHints.put("enableSelfAsk", "false");
            }

            // ??PERF: controller媛 ?대? ?섑뻾??web search 寃곌낵(Trace ?ы븿)瑜??ъ궗?⑺빐
            // WebSearchRetriever/HybridRetriever?먯꽌 ?숈씪 荑쇰━ ?ш??됱쓣 諛⑹??쒕떎.
            if (hints.isAllowWeb() && externalCtxProvider != null) {
                try {
                    String q0 = (planned != null && !planned.isEmpty()) ? planned.get(0) : finalQuery;
                    List<String> prefetched = externalCtxProvider.apply(q0);
                    if (prefetched != null && !prefetched.isEmpty()) {
                        metaHints.put("prefetch.web.query", q0);
                        metaHints.put("prefetch.web.snippets", prefetched);
                        addPrefetchDiagnostics(metaHints, q0, prefetched);
                    }
                } catch (Exception e) {
                    log.debug("[WebPrefetch] externalCtxProvider failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                }
            }

            // Domain/tile hints for retrieval & alias correction (all-rounder stability)
            metaHints.put("intent.domain", domain);
            metaHints.put("vp.topTile", mapDomainToTile(domain));

            // UAW: Orchestration mode flags
            metaHints.put("strikeMode", String.valueOf(hints.isStrikeMode()));
            metaHints.put("compressionMode", String.valueOf(hints.isCompressionMode()));
            metaHints.put("bypassMode", String.valueOf(hints.isBypassMode()));
            metaHints.put("webRateLimited", String.valueOf(hints.isWebRateLimited()));
            if (hints.getBypassReason() != null && !hints.getBypassReason().isBlank()) {
                metaHints.put("bypassReason", hints.getBypassReason());
            }

            try {
                TraceStore.put("plate.id", hints.getPlateId());
                TraceStore.put("plate.webTopK", hints.getWebTopK());
                TraceStore.put("plate.vecTopK", hints.getVecTopK());
                TraceStore.put("plate.webBudgetMs", hints.getWebBudgetMs());
                TraceStore.put("plate.vecBudgetMs", hints.getVecBudgetMs());
                TraceStore.put("plate.crossEncoder", hints.isEnableCrossEncoder());
                TraceStore.put("nightmare.mode", hints.isNightmareMode());
                TraceStore.put("aux.llm.down", hints.isAuxLlmDown());
                TraceStore.put("aux.llm.degraded", auxDegraded);
                TraceStore.put("aux.llm.hardDown", auxHardDown);

                // ??UX: trace/diagnostics?먯꽌 "??BYPASS/STRIKE媛 耳쒖죱?붿?"瑜????붾㈃?먯꽌 ?뺤씤?????덇쾶
                // OrchestrationSignals 湲곕컲???붿빟/?ъ쑀瑜?硫뷀?濡쒕룄 ?④릿??
                TraceStore.put("orch.mode", (sig != null ? sig.modeLabel() : ""));
                TraceStore.put("orch.strike", hints.isStrikeMode());
                TraceStore.put("orch.compression", hints.isCompressionMode());
                TraceStore.put("orch.bypass", hints.isBypassMode());
                TraceStore.put("orch.webRateLimited", hints.isWebRateLimited());
                TraceStore.put("orch.auxLlmDown", hints.isAuxLlmDown());
                TraceStore.put("orch.auxDegraded", auxDegraded);
                TraceStore.put("orch.auxHardDown", auxHardDown);
                if (sig != null) {
                    TraceStore.put("orch.highRisk", sig.highRisk());
                    TraceStore.put("orch.irregularity", sig.irregularity());
                    TraceStore.put("orch.userFrustration", sig.userFrustrationScore());
                    TraceStore.put("orch.reasons", sig.reasons() == null ? java.util.List.of() : sig.reasons().stream()
                            .map(reason -> SafeRedactor.traceLabelOrFallback(reason, "unknown"))
                            .toList());
                    TraceStore.put("orch.reason", SafeRedactor.traceLabelOrFallback(sig.reason(), "unknown"));

                    // MERGE_HOOK:PROJ_AGENT::ORCH_PARTS_TABLE_CALL
                    boolean plannerUsed = planned != null && planned.size() > 1;
                    boolean plannerAllowedByStagePolicy = stagePolicy == null
                            || stagePolicy.isStageEnabled(OrchStageKeys.PLAN_QUERY_PLANNER, sig.modeLabel(), true);
                    boolean qtxAllowedByStagePolicy = stagePolicy == null
                            || stagePolicy.isStageEnabled(OrchStageKeys.QUERY_TRANSFORMER, sig.modeLabel(), true);

                    sig.emitPartsPlanToTrace(
                            useWeb,
                            useRag,
                            hints,
                            plannerUsed,
                            plannerAllowedByStagePolicy,
                            qtxAllowedByStagePolicy,
                            planned != null ? planned.size() : 0,
                            llmReq != null && Boolean.TRUE.equals(llmReq.isUseVerification()));

                    // Debug: quantitative mode score + leave-one-out ablation
                    sig.emitDebugScorecardToTrace();

                    // Debug: auto report (Top-N causes + probe shortcuts)
                    com.example.lms.orchestration.OrchAutoReporter.emitToTrace();
                } else if (hints.getBypassReason() != null && !hints.getBypassReason().isBlank()) {
                    TraceStore.put("orch.reason", SafeRedactor.traceLabelOrFallback(hints.getBypassReason(), "unknown"));
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("orch.modeTrace", ignore); }

            int plateLimit = Math.max(hybridTopK, Math.max(plate.webTopK(), plate.vecTopK()) * 3);

            if (futureTech && latestTechAutoDisableVector) {
                // Web-only retrieval (still plate-scoped via metadata hints)
                var qObj = QueryUtils.buildQuery(finalQuery, sessionIdLong, null, metaHints);

                List<Content> tmp = null;

                boolean webHardDownNow = false;
                try {
                    long skipped = TraceStore.getLong("web.await.skipped.count");
                    webHardDownNow = (hints != null && hints.isWebRateLimited())
                            || Boolean.TRUE.equals(TraceStore.get("web.hardDown"))
                            || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited.effective"))
                            || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited"))
                            || skipped >= 2;
                } catch (Exception ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("webHardDown.nowCheck", ignore); webHardDownNow = (hints != null && hints.isWebRateLimited());
                }

                if (hints != null && hints.isAllowWeb() && !webHardDownNow) {
                    if (shouldUseAnalyzeWeb(metaHints) && analyzeWebSearchRetriever != null) {
                        try {
                            tmp = analyzeWebSearchRetriever.retrieve(qObj);
                        } catch (Exception e) {
                            TraceStore.put("retrieval.analyzeWeb.error", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
                        }
                    }
                    if (tmp == null || tmp.isEmpty()) {
                        tmp = webSearchRetriever.retrieve(qObj);
                    }
                } else {
                    try {
                        TraceStore.put("retrieval.web.skipped", true);
                        TraceStore.put("retrieval.web.skipped.reason",
                                webHardDownNow ? "webHardDown" : "allowWeb=false");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("retrieval.webSkippedTrace", ignore); }
                }
                fused = tmp;
            } else {
                fused = hybridRetriever.retrieveAll(planned, plateLimit, sessionIdLong, metaHints);
            }

            // ---- FAIL-SOFT (UAW): web 紐⑤뱶?몃뜲 ?꾨낫媛 0?대㈃ web-only濡?理쒖냼 ?꾨낫瑜?蹂듭썝 ----
            // ?쇰? ?꾨찓???꾪꽣 議고빀?먯꽌 fused媛 0?쇰줈 ?섎졃?섎㈃ ?댄썑 rerank/topDocs媛 鍮꾩뼱
            // citations.min??留욎텛吏 紐삵븯怨?Guard媛 BLOCK?쇰줈 ?곗뇙?섎뒗 ?⑦꽩???덉뿀??
            boolean webHardDownNow = false;
            try {
                long skipped = TraceStore.getLong("web.await.skipped.count");
                webHardDownNow = (hints != null && hints.isWebRateLimited())
                        || Boolean.TRUE.equals(TraceStore.get("web.hardDown"))
                        || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited.effective"))
                        || Boolean.TRUE.equals(TraceStore.get("orch.webRateLimited"))
                        || skipped >= 2;
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("webHardDown.fallbackCheck", ignore); webHardDownNow = (hints != null && hints.isWebRateLimited());
            }

            if (useWeb && (fused == null || fused.isEmpty()) && hints != null && hints.isAllowWeb()
                    && !webHardDownNow) {
                try {
                    TraceStore.put("fallback.webOnly", true);
                    var qObj = QueryUtils.buildQuery(finalQuery, sessionIdLong, null, metaHints);
                    List<dev.langchain4j.rag.content.Content> webOnly = null;
                    if (shouldUseAnalyzeWeb(metaHints) && analyzeWebSearchRetriever != null) {
                        try {
                            webOnly = analyzeWebSearchRetriever.retrieve(qObj);
                        } catch (Exception e) {
                            TraceStore.put("fallback.webOnly.analyzeError", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
                        }
                    }
                    if (webOnly == null || webOnly.isEmpty()) {
                        webOnly = webSearchRetriever.retrieve(qObj);
                    }
                    if (webOnly != null && !webOnly.isEmpty()) {
                        fused = webOnly;
                        TraceStore.put("fallback.webOnly.count", webOnly.size());
                    }
                } catch (Exception e) {
                    TraceStore.put("fallback.webOnly.error", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
                }
            } else if (useWeb && (fused == null || fused.isEmpty()) && webHardDownNow) {
                try {
                    TraceStore.put("fallback.webOnly.skipped", true);
                    TraceStore.put("fallback.webOnly.skipped.reason", "webHardDown");
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("fallback.webOnlySkippedTrace", ignore); }
            }
        }
        // planned / fused ?앹꽦???ㅼ쓬易?
        throwIfCancelled(sessionIdLong); // ??異붽?
        Map<String, Set<String>> rules = qcPreprocessor.getInteractionRules(finalQuery);

        int keepN = switch (Objects.toString(vp.hint(), "standard").toLowerCase(Locale.ROOT)) {
            case "brief" -> keepNBrief;
            case "deep" -> Math.max(rerankTopN, keepNDeep);
            case "ultra" -> Math.max(rerankTopN, keepNUltra);
            default -> keepNStd;
        };

        // Rerank knobs: read ONLY from metaHints (PlanHintApplier already injects
        // canonical keys).
        // This removes drift caused by ChatWorkflow reading PlanHints directly.
        RerankKnobResolver.Resolved rerankKnobs = RerankKnobResolver.resolve(metaHints);

        // Plan knob: rerank.topK / rerank_top_k
        // If explicitly set, respect as an override (but keep emergency clamps below).
        try {
            if (rerankKnobs.topK() != null && rerankKnobs.topK() > 0) {
                keepN = Math.max(1, rerankKnobs.topK());
                TraceStore.put("rerank.keepN.override", keepN);
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.keepNOverrideTrace", ignore); }
        // 蹂댁“ LLM ?μ븷/?섏씠?몃찓??STRIKE/?뺤텞 ?곹솴?먯꽌??而⑦뀓?ㅽ듃瑜?媛뺤젣 ?뺤텞
        if ((sig != null && sig.auxLlmDown())
                || (hints != null && (hints.isNightmareMode() || hints.isCompressionMode() || hints.isStrikeMode()))
                || sig.compressionMode()) {
            keepN = Math.min(keepN, 3);
        }

        List<dev.langchain4j.rag.content.Content> topDocs;
        if (useWeb && fused != null && !fused.isEmpty()) {
            boolean doRerank = (hints == null || hints.isEnableCrossEncoder());
            if (doRerank) {
                // Additional cost-control: optionally cap the number of candidates sent to the
                // cross-encoder.
                // - rerank_ce_top_k / rerank_candidate_k: explicit candidate cap (strongest
                // control)
                // - rerank_top_k: keepN override; if candidate cap is absent, derive a
                // conservative cap (~2x keepN)
                List<dev.langchain4j.rag.content.Content> rerankInput = fused;
                int candidateCap = fused.size();
                try {
                    if (rerankKnobs.ceTopK() != null && rerankKnobs.ceTopK() > 0) {
                        // Explicit candidate cap: score at most N docs.
                        candidateCap = Math.min(candidateCap, rerankKnobs.ceTopK());
                        // ensure enough candidates to keep keepN
                        candidateCap = Math.max(candidateCap, keepN);
                        if (candidateCap < fused.size()) {
                            rerankInput = fused.subList(0, candidateCap);
                        }
                        TraceStore.put("rerank.ce.candidateCap", candidateCap);
                        TraceStore.put("rerank.ce.candidateCap.override", rerankKnobs.ceTopK());
                    } else if (rerankKnobs.topK() != null && rerankKnobs.topK() > 0) {
                        // Derived cap: score at most ~2x the kept docs.
                        candidateCap = Math.min(candidateCap, Math.max(keepN * 2, keepN));
                        candidateCap = Math.max(candidateCap, keepN);
                        if (candidateCap < fused.size()) {
                            rerankInput = fused.subList(0, candidateCap);
                        }
                        TraceStore.put("rerank.ce.candidateCap", candidateCap);
                    }
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.candidateCapTrace", ignore); }

                // Backend selection: allow per-plan override + "auto" mode.
                String backendOverride = rerankKnobs.backend();
                Boolean onnxEnabledOverride = rerankKnobs.onnxEnabled();
                try {
                    topDocs = reranker(backendOverride, onnxEnabledOverride, true)
                            .rerank(finalQuery, rerankInput, keepN, rules);
                } catch (Exception e) {
                    // UAW fail-soft: if rerank fails, keep the pipeline moving with the original
                    // candidates.
                    try {
                        TraceStore.put("rerank.fallback", true);
                        TraceStore.put("rerank.fallback.reason", "exception");
                        TraceStore.put("rerank.fallback.error", "reranker_failed");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.exceptionFallbackTrace", ignore); }
                    log.warn("[ChatService] Reranker failed. Falling back to unreranked candidates. err={}",
                            String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                    topDocs = List.of();
                }

                // UAW fail-soft: rerank output can be empty (e.g., too strict filters). Do not
                // allow topDocs=0.
                if ((topDocs == null || topDocs.isEmpty()) && rerankInput != null && !rerankInput.isEmpty()) {
                    try {
                        TraceStore.put("rerank.fallback", true);
                        TraceStore.putIfAbsent("rerank.fallback.reason", "empty");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.emptyFallbackTrace", ignore); }
                    topDocs = rerankInput.stream().limit(Math.max(1, keepN)).toList();
                }
            } else {
                topDocs = fused.stream().limit(Math.max(1, keepN)).toList();
                try {
                    String why = "skipped_by_plate";
                    if (Boolean.FALSE.equals(rerankKnobs.crossEncoderEnabled())) {
                        why = "skipped_by_plan";
                    }
                    TraceStore.put("rerank", why);
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("rerank.skippedTrace", ignore); }
            }
        } else {
            topDocs = List.of();
        }

        if (useWeb) {
            if (topDocs == null || topDocs.isEmpty()) {
                long cnt = emptyTopDocsCount.incrementAndGet();
                log.warn("[ChatService] ?좑툘 ??寃??紐⑤뱶?대굹 topDocs 鍮꾩뼱?덉쓬! fused={} ??reranked=0. "
                        + "?꾪꽣留?怨쇰룄?? emptyTopDocsCount={}",
                        (fused != null ? fused.size() : 0),
                        cnt);
            } else {
                log.debug("[ChatService] Reranker: fused={} ??topDocs={}",
                        (fused != null ? fused.size() : 0),
                        (topDocs != null ? topDocs.size() : 0));
            }
        }

        // ?? (Needle Probe) 2-pass merge/rerank
        // When pass-1 evidence quality looks weak, run a tiny second-pass web detour
        // (1~2 high-authority site-filtered queries), then merge + rerank again.
        if (useWeb && needleProbeEngine != null
                && sig != null
                && !sig.strikeMode() && !sig.bypassMode()
                && !sig.webRateLimited()) {
            try {
                java.util.Map<String, Object> baseMeta = new java.util.HashMap<>();
                if (metaHints != null) {
                    baseMeta.putAll(metaHints);
                }
                needleBeforeSignals = EvidenceSignals.compute(finalQuery, topDocs, authorityScorer);

                NeedleProbeEngine.Result needle = needleProbeEngine.maybeProbe(
                        finalQuery,
                        topDocs,
                        keepN,
                        sessionIdLong,
                        baseMeta);

                if (needle != null && needle.triggered()
                        && needle.needleDocs() != null && !needle.needleDocs().isEmpty()) {
                    needleExecuted = true;
                    needlePlanned = needle.plan() == null ? java.util.List.of() : needle.plan().needleQueries();
                    needleUrls = needle.needleUrls() == null ? java.util.Set.of() : needle.needleUrls();
                    needleDocsForReward = java.util.List.copyOf(needle.needleDocs());
                    TraceStore.put("needle.triggered", true);
                    TraceStore.put("needle.plan.reason", SafeRedactor.traceLabelOrFallback(needle.plan() == null ? "unknown" : needle.plan().reason(), "unknown"));
                    TraceStore.put("needle.plan.queryCount", needlePlanned == null ? 0 : needlePlanned.size());
                    TraceStore.put("needle.plan.queryHashes", safeHashList(needlePlanned));
                    TraceStore.put("needle.plan.siteHintCount",
                            needle.plan() == null || needle.plan().siteHints() == null ? 0 : needle.plan().siteHints().size());
                    TraceStore.put("needle.plan.keywordCount",
                            needle.plan() == null || needle.plan().coreKeywords() == null ? 0 : needle.plan().coreKeywords().size());
                    java.util.List<Content> firstPassTopDocs =
                            topDocs == null ? java.util.List.of() : java.util.List.copyOf(topDocs);

                    java.util.List<Content> merged = mergeNeedleCandidates(
                            topDocs,
                            needle.needleDocs(),
                            fused,
                            needleProbeEngine.maxCandidatePool(keepN));
                    fused = merged;

                    // 2-pass rerank if cross-encoder enabled
                    boolean doRerank2 = (hints == null || hints.isEnableCrossEncoder());
                    long secondPassRemainingMs = -1L;
                    try {
                        throwIfCancelled(sessionIdLong);
                        com.abandonware.ai.addons.budget.TimeBudget tb =
                                com.abandonware.ai.addons.budget.TimeBudgetContext.get();
                        if (tb != null) {
                            secondPassRemainingMs = tb.remainingMillis();
                            TraceStore.put("rerank.secondPass.remainingMs", secondPassRemainingMs);
                            if (secondPassRemainingMs < 80L) {
                                doRerank2 = false;
                                TraceStore.put("rerank.secondPass.guard.reason", SafeRedactor.traceLabelOrFallback("budget_low", "unknown"));
                                TraceStore.put("rerank.secondPass.guard.applied", true);
                                com.example.lms.trace.AblationContributionTracker.recordPenaltyOnce(
                                        "rerank.secondPass.budget_low",
                                        "rerank.secondPass",
                                        "budget_low",
                                        0.20d,
                                        "second pass skipped because remaining budget was below 80ms");
                            }
                        }
                    } catch (java.util.concurrent.CancellationException ce) {
                        TraceStore.put("rerank.secondPass.guard.reason", SafeRedactor.traceLabelOrFallback("cancelled", "unknown"));
                        TraceStore.put("rerank.secondPass.guard.applied", true);
                        throw ce;
                    } catch (Exception ignore) {
                        ChatWorkflowTraceSuppressions.traceSuppressed("rerank.secondPass.guard", ignore);
                    }
                    if (doRerank2 && merged.size() > keepN) {
                        int cap = needleProbeEngine.secondPassCandidateCap(keepN, merged.size(), rerankKnobs);
                        java.util.List<Content> rerankInput2 = merged.subList(0, cap);

                        String backendOverride2 = rerankKnobs.backend();
                        Boolean onnxEnabledOverride2 = rerankKnobs.onnxEnabled();

                        topDocs = reranker(backendOverride2, onnxEnabledOverride2, true)
                                .rerank(finalQuery, rerankInput2, keepN, rules);

                        TraceStore.put("needle.rerank.secondPass", true);
                        TraceStore.put("needle.rerank.candidateCap", cap);
                        double secondPassDropRatio = retainedDropRatio(firstPassTopDocs, topDocs);
                        double secondPassScoreDelta = secondPassDropRatio;
                        TraceStore.put("rerank.secondPass.scoreDelta", secondPassScoreDelta);
                        TraceStore.put("rerank.secondPass.dropRatio", secondPassDropRatio);
                        TraceStore.put("rerank.secondPass.beforeCount", firstPassTopDocs.size());
                        TraceStore.put("rerank.secondPass.afterCount", topDocs == null ? 0 : topDocs.size());
                        if ((topDocs == null || topDocs.isEmpty())
                                || secondPassDropRatio >= 0.50d
                                || secondPassScoreDelta >= 0.20d) {
                            TraceStore.put("rerank.secondPass.guard.reason",
                                    SafeRedactor.traceLabelOrFallback((topDocs == null || topDocs.isEmpty()) ? "empty_second_pass" : "score_drop", "unknown"));
                            TraceStore.put("rerank.secondPass.guard.applied", true);
                            com.example.lms.trace.AblationContributionTracker.recordPenaltyOnce(
                                    "rerank.secondPass.score_drop",
                                    "rerank.secondPass",
                                    String.valueOf(TraceStore.get("rerank.secondPass.guard.reason")),
                                    secondPassScoreDelta,
                                    "second pass rollback to first-pass evidence");
                            topDocs = firstPassTopDocs;
                        }
                    } else {
                        topDocs = firstPassTopDocs.isEmpty()
                                ? merged.stream().limit(Math.max(1, keepN)).toList()
                                : firstPassTopDocs;
                    }
                    if ((topDocs == null || topDocs.isEmpty()) && merged != null && !merged.isEmpty()) {
                        topDocs = merged.stream().limit(Math.max(1, keepN)).toList();
                        TraceStore.put("needle.rerank.fallback", true);
                    }
                    needleAfterSignals = EvidenceSignals.compute(finalQuery, topDocs, authorityScorer);

                    int needleTopDocHits = needle.countTopDocsHits(topDocs);
                    TraceStore.put("needle.topDocs.hits", needleTopDocHits);

                    // MERGE_HOOK:PROJ_AGENT::NEEDLE_KEPT_RATIO_V1
                    // "keptRatio" := needle媛 理쒖쥌 ?곸쐞 利앷굅(topDocs)??湲곗뿬??鍮꾩쑉
                    int denom = Math.max(1, topDocs.size());
                    double keptRatio = ((double) needleTopDocHits) / denom;
                    TraceStore.put("needle.keptRatio", keptRatio);
                    TraceStore.put("needle.keptRatioDenom", denom);
                }
            } catch (Exception e) {
                ChatWorkflowTraceSuppressions.traceSuppressed("needle.probe", e); log.debug("[Needle] probe failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            }
        }
        // 1-b) (?듭뀡) RAG(Vector) 議고쉶
        List<dev.langchain4j.rag.content.Content> vectorDocs = List.of();
        if (useRag) {
            // Propagate request-scoped orchestration hints into vector retriever metadata.
            // This enables dynamic vecTopK (and future tuning knobs) without changing the
            // handler chain.
            java.util.Map<String, Object> vMeta = new java.util.HashMap<>();
            vMeta.put(
                    com.example.lms.service.rag.LangChainRAGService.META_SID,
                    (req.getSessionId() == null) ? "__TRANSIENT__" : req.getSessionId());
            // Orchestration flags (for downstream dynamic handlers)
            vMeta.put("auxLlmDown", String.valueOf(sig.auxLlmDown()));
            vMeta.put("auxDegraded", String.valueOf(sig.auxDegraded()));
            vMeta.put("auxHardDown", String.valueOf(sig.auxHardDown()));
            vMeta.put("strikeMode", String.valueOf(sig.strikeMode()));
            vMeta.put("compressionMode", String.valueOf(sig.compressionMode()));
            vMeta.put("bypassMode", String.valueOf(sig.bypassMode()));

            // Domain/tile hints (keep vector path consistent with hybrid retrieval)
            vMeta.put("intent.domain", domain);
            vMeta.put("vp.topTile", mapDomainToTile(domain));
            if (hints != null && hints.getVecTopK() != null) {
                vMeta.put("vecTopK", hints.getVecTopK());
                // Compatibility key for some retrievers.
                vMeta.put("vectorTopK", hints.getVecTopK());
            }
            vectorDocs = ragSvc.asContentRetriever(pineconeIndexName)
                    .retrieve(
                            QueryUtils.buildQuery(finalQuery, vMeta));
        }

        // Expose the final evidence sets (post rerank / retrieval) to the UI
        // layer. Controllers may read these from TraceStore to render the
        // "理쒖쥌 而⑦뀓?ㅽ듃" section without re-running retrieval.
        try {
            // Preserve "enabled" signal for the trace UI:
            // - null : disabled (feature not used)
            // - empty : enabled but no results
            TraceStore.put("finalWebTopK",
                    useWeb ? ((topDocs == null) ? java.util.Collections.emptyList() : topDocs) : null);
            TraceStore.put("finalVectorTopK",
                    useRag ? ((vectorDocs == null) ? java.util.Collections.emptyList() : vectorDocs) : null);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalEvidence.trace", ignore);
        }

        // 1-c) 硫붾え由?而⑦뀓?ㅽ듃(??긽 ?쒕룄) - ?꾨떞 ?몃뱾???ъ슜
        String memoryCtx = null;
        try {
            if (futureTech && latestTechSkipMemoryRead) {
                log.debug("[FutureTech] skip memory context load to avoid stale contamination. sessionHash={}",
                        SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
            } else if (memoryMode == null || memoryMode.isReadEnabled()) {
                memoryCtx = memoryHandler.loadForSession(req.getSessionId());
            } else {
                log.debug("[MemoryMode] {} -> skip memory context load for sessionHash={}", memoryMode, SafeRedactor.hashValue(String.valueOf(req.getSessionId())));
            }
        } catch (Exception ex) {
            log.debug("[Memory] failed to load memory context: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
        }

        // ?? 2) 紐낆떆??留λ씫 ?앹꽦(Verbosity-aware) ????????????????????????
        // ?몄뀡 ID(Long) ?뚯떛: 理쒓렐 assistant ?듬? & ?덉뒪?좊━ 議고쉶???ъ슜

        String lastAnswer = (sessionIdLong == null)
                ? null
                : chatHistoryService.getLastAssistantMessage(sessionIdLong).orElse(null);
        String historyStr = (sessionIdLong == null)
                ? ""
                : String.join("\n", chatHistoryService.getFormattedRecentHistory(sessionIdLong,
                        Math.max(2, Math.min(maxHistory, 8))));

        // PromptContext??紐⑤뱺 ?곹깭瑜?'紐낆떆?곸쑝濡? ?섏쭛
        // =========================================================================
        // [SECTION 2] Prompt context assembly
        // Future extraction candidate: PromptContextAssembler.
        // =========================================================================
        List<Content> promptWebDocs = useWeb ? filterPromptEligibleSelfAsk(topDocs) : null;
        List<Content> promptVectorDocs = useRag ? filterPromptEligibleSelfAsk(vectorDocs) : null;
        DynamicContextCompressor.PromptContextComposition promptComposition = null;
        if (promptContextCompressor != null) {
            try {
                promptComposition = promptContextCompressor.composeForPrompt(finalQuery, promptWebDocs, promptVectorDocs);
                if (promptComposition != null) {
                    promptWebDocs = promptComposition.web();
                    promptVectorDocs = promptComposition.rag();
                }
            } catch (Throwable ex) {
                TraceStore.put("prompt.context.composer.failSoft", true);
                TraceStore.put("prompt.context.composer.reason", "chatworkflow_exception_original_returned");
                TraceStore.put("prompt.context.composer.exception", "prompt_context_composer_failed");
            }
            try {
                memoryCtx = promptContextCompressor.compressMemoryForPrompt(finalQuery, memoryCtx);
            } catch (Throwable ex) {
                TraceStore.put("prompt.memory.compressor.activated", false);
                TraceStore.put("prompt.memory.compressor.reason", "chatworkflow_exception_original_returned");
                TraceStore.put("prompt.memory.compressor.exception", "memory_compressor_failed");
            }
        }
        if (debugEventStore != null && promptComposition != null && promptComposition.decision() != null) {
            try {
                debugEventStore.emit(
                        DebugProbeType.PROMPT,
                        promptComposition.decision().failSoft() ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                        "prompt.context.composer",
                        "Ablation-guided prompt context composition evaluated.",
                        "ChatWorkflow.promptContextComposer",
                        promptComposition.decision().toTraceMap(),
                        null);
            } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.contextComposerDebugEvent", ignore); }
        }

        RagLearningSupportContext ragSupportContext = RagLearningSupportContext.empty();
        if (learningContextSourceService != null) {
            try {
                RagLearningSupportContext loaded = learningContextSourceService.buildRagSupportForCurrentActor();
                if (loaded != null) {
                    ragSupportContext = loaded;
                }
            } catch (RuntimeException ex) {
                TraceStore.put("prompt.learning.failSoft", true);
                TraceStore.put("prompt.ragSupport.failSoft", true);
                TraceStore.put("prompt.learning.exception", "learning_context_failed");
                TraceStore.put("prompt.ragSupport.exception", "learning_context_failed");
                ragSupportContext = RagLearningSupportContext.degraded("learning_context_failed");
            }
        }

        var ctxBuilder = com.example.lms.prompt.PromptContext.builder()
                // Use the rewritten/final query so retrieval signals, section templates and
                // follow-up checks stay consistent.
                .userQuery(finalQuery)
                .lastAssistantAnswer(lastAnswer)
                .history(historyStr)
                .intent(intent)
                .domain(domain)
                .subject(analysis != null ? analysis.getTargetObject() : null)
                // Keep null to represent "disabled"; empty list means enabled but no results.
                .web(promptWebDocs)
                .rag(promptVectorDocs)
                .memory(memoryCtx) // ?몄뀡 ?κ린 硫붾え由??붿빟
                .interactionRules(rules) // ?숈쟻 愿怨?洹쒖튃
                .verbosityHint(vp.hint()) // brief|standard|deep|ultra
                .minWordCount(vp.minWordCount())
                .targetTokenBudgetOut(vp.targetTokenBudgetOut())
                .sectionSpec(sections)
                .citationStyle("inline")
                .queryDomain(queryDomain)
                .guardProfile(guardProfile)
                .visionMode(visionMode)
                .answerMode(answerMode)
                .memoryMode(memoryMode)
                .resourceTier(safeTraceString("resource.tier"))
                .resourceValueScore(safeTraceDouble("resource.valueScore"))
                .resourceOptimismScore(safeTraceDouble("resource.optimismScore"))
                .resourceRiskAdjustedConfidence(safeTraceDouble("resource.riskAdjustedConfidence"))
                .resourceRewriteTemperature(safeTraceDouble("resource.rewriteTemperature"))
                .resourceSearchRangeMultiplier(safeTraceDouble("resource.searchRangeMultiplier"))
                .learningRole(ragSupportContext.actorRole())
                .learningSignals(ragSupportContext.contextSignals())
                .learningContextSummary(ragSupportContext.contextSummary());
        java.util.List<dev.langchain4j.data.document.Document> promptLocalDocs = java.util.Collections.emptyList();
        // Inject uploaded attachments into the prompt context. Only when
        // attachment identifiers are present to avoid unnecessary overhead.
        java.util.List<String> __ids = (req == null) ? null : req.getAttachmentIds();
        if (__ids != null && !__ids.isEmpty()) {
            try {
                var localDocs = attachmentService.asDocumentsForSession(
                        __ids,
                        sessionIdLong == null ? null : String.valueOf(sessionIdLong));
                if (localDocs != null && !localDocs.isEmpty()) {
                    promptLocalDocs = localDocs;
                    ctxBuilder.localDocs(localDocs);
                }
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("prompt.localDocs", ignore);
            }
        }
        java.util.List<RagEvidenceMetadata> citableEvidence = java.util.Collections.emptyList();
        try {
            if (ragEvidenceAttributionService != null) {
                citableEvidence = ragEvidenceAttributionService.promoteForPrompt(
                        finalQuery,
                        promptWebDocs,
                        promptVectorDocs,
                        promptLocalDocs,
                        queryDomain,
                        lastAnswer != null && !lastAnswer.isBlank());
                ctxBuilder.evidence(citableEvidence);
            }
        } catch (Throwable ex) {
            TraceStore.put("rag.evidence.promotion.failSoft", true);
            TraceStore.put("rag.evidence.promotion.exception", "evidence_promotion_failed");
            citableEvidence = java.util.Collections.emptyList();
        }
        var ctx = ctxBuilder.build();
        String ragSupportRoleLabel = ctx.learningRole() == null ? "ANONYMOUS" : ctx.learningRole().trainingRagLabel();

        // (Safety) Mirror the *actual* evidence lists used in the prompt back into
        // TraceStore
        // so the UI can display the same values even if upstream lists were altered.
        try {
            TraceStore.put("finalWebTopK", ctx.web());
            TraceStore.put("finalVectorTopK", ctx.rag());
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.finalEvidenceMirror", ignore);
        }
        // (F) Prompt build boundary observability: record minimal composition meta.
        try {
            int webCount = (ctx.web() != null) ? ctx.web().size() : 0;
            int ragCount = (ctx.rag() != null) ? ctx.rag().size() : 0;
            int localDocsCount = (ctx.localDocs() != null) ? ctx.localDocs().size() : 0;
            int evidenceCount = (ctx.evidence() != null) ? ctx.evidence().size() : 0;
            String mem = ctx.memory();
            boolean memPresent = (mem != null && !mem.isBlank());
            TraceStore.put("prompt.webCount", webCount);
            TraceStore.put("prompt.ragCount", ragCount);
            TraceStore.put("prompt.localDocsCount", localDocsCount);
            TraceStore.put("prompt.citableEvidenceCount", evidenceCount);
            TraceStore.put("prompt.memoryPresent", memPresent);
            TraceStore.put("prompt.memoryLen", (mem != null) ? mem.length() : 0);
            TraceStore.put("prompt.learningRole", ragSupportRoleLabel);
            TraceStore.put("prompt.learningSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
            TraceStore.put("prompt.learningSummaryPresent",
                    ctx.learningContextSummary() != null && !ctx.learningContextSummary().isBlank());
            TraceStore.put("prompt.learningSourceTags", ragSupportContext.sourceTags());
            String learningDegradedReason = ragSupportContext.degradedReason();
            if (learningDegradedReason == null || learningDegradedReason.isBlank()) {
                learningDegradedReason = learningDegradedReason(ctx.learningSignals());
            }
            String safeLearningDegradedReason = SafeRedactor.traceLabelOrFallback(learningDegradedReason, "unknown");
            TraceStore.put("prompt.learningDegraded", !learningDegradedReason.isBlank());
            if (!learningDegradedReason.isBlank()) {
                TraceStore.put("prompt.learningDegradedReason", safeLearningDegradedReason);
            }
            TraceStore.put("prompt.ragSupport.role", ragSupportRoleLabel);
            TraceStore.put("prompt.ragSupport.signalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
            TraceStore.put("prompt.ragSupport.summaryPresent",
                    ctx.learningContextSummary() != null && !ctx.learningContextSummary().isBlank());
            TraceStore.put("prompt.ragSupport.sourceTags", ragSupportContext.sourceTags());
            TraceStore.put("prompt.ragSupport.degraded", !learningDegradedReason.isBlank());
            if (!learningDegradedReason.isBlank()) {
            TraceStore.put("prompt.ragSupport.degradedReason", safeLearningDegradedReason);
            }
            TraceStore.put("prompt.mode.verbosity", vp.hint());
            TraceStore.put("prompt.intent", SafeRedactor.diagnosticValue("prompt.intent", ctx.intent(), 160));
            TraceStore.put("prompt.domain", SafeRedactor.diagnosticValue("prompt.domain", ctx.domain(), 160));
            if (ctx.answerMode() != null)
                TraceStore.put("prompt.answerMode", String.valueOf(ctx.answerMode()));
            if (ctx.visionMode() != null)
                TraceStore.put("prompt.visionMode", String.valueOf(ctx.visionMode()));
            if (ctx.memoryMode() != null)
                TraceStore.put("prompt.memoryMode", String.valueOf(ctx.memoryMode()));
            TraceStore.put("prompt.sectionSpec.count", (ctx.sectionSpec() != null) ? ctx.sectionSpec().size() : 0);
            java.util.Map<String, Object> pev = new java.util.LinkedHashMap<>();
            pev.put("seq", TraceStore.nextSequence("prompt.events"));
            pev.put("ts", java.time.Instant.now().toString());
            pev.put("step", "PromptBuilder.build.enter");
            pev.put("webCount", webCount);
            pev.put("ragCount", ragCount);
            pev.put("localDocsCount", localDocsCount);
            pev.put("citableEvidenceCount", evidenceCount);
            pev.put("memoryPresent", memPresent);
            pev.put("learningRole", ragSupportRoleLabel);
            pev.put("learningSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
            pev.put("learningSourceTags", ragSupportContext.sourceTags());
            pev.put("learningDegraded", !learningDegradedReason.isBlank());
            if (!learningDegradedReason.isBlank()) {
                pev.put("learningDegradedReason", safeLearningDegradedReason);
            }
            pev.put("ragSupportRole", ragSupportRoleLabel);
            pev.put("ragSupportSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
            pev.put("ragSupportSourceTags", ragSupportContext.sourceTags());
            pev.put("ragSupportDegraded", !learningDegradedReason.isBlank());
            if (!learningDegradedReason.isBlank()) {
            pev.put("ragSupportDegradedReason", safeLearningDegradedReason);
            }
            pev.put("verbosity", vp.hint());
            if (ctx.intent() != null)
                pev.put("intent", SafeRedactor.diagnosticValue("intent", ctx.intent(), 160));
            if (ctx.domain() != null)
                pev.put("domain", SafeRedactor.diagnosticValue("domain", ctx.domain(), 160));
            TraceStore.append("prompt.events", pev);
        } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.contextTrace", ignore); }

        // PromptBuilder媛 而⑦뀓?ㅽ듃 蹂몃Ц怨??쒖뒪???몄뒪?몃윮?섏쓣 遺꾨━ ?앹꽦
        long promptBuildStartedNs = System.nanoTime();
        String ctxText = promptBuilder.build(ctx);
        String instrTxt = promptBuilder.buildInstructions(ctx);
        TraceStore.put("chatWorkflow.promptBuilderUsed", true);
        if (gctx != null && gctx.planBool("promptBuilder.required", false)) {
            try {
                TraceStore.put("prompt.builder.required", true);
                TraceStore.put("prompt.builder.required.enforced", ctxText != null);
            } catch (Throwable ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("prompt.builderRequired", ignore);
            }
        }
        long promptBuildMs = Math.max(0L, (System.nanoTime() - promptBuildStartedNs) / 1_000_000L);
        // (F) Prompt build boundary observability: store hashes/lengths and emit a
        // DebugEvent.
        try {
            TraceStore.put("prompt.ctx.len", (ctxText != null) ? ctxText.length() : 0);
            TraceStore.put("prompt.instr.len", (instrTxt != null) ? instrTxt.length() : 0);
            if (instrTxt != null && !instrTxt.isBlank()) {
                TraceStore.put("prompt.instr.sha1", TextUtils.sha1(instrTxt));
            }
            if (ctxText != null && !ctxText.isBlank()) {
                // Avoid hashing the full evidence body; hash only a prefix for a stable
                // template fingerprint.
                int cap = Math.min(2048, ctxText.length());
                TraceStore.put("prompt.ctx.prefix.sha1", TextUtils.sha1(ctxText.substring(0, cap)));
            }
            int webCount = (ctx.web() != null) ? ctx.web().size() : 0;
            int ragCount = (ctx.rag() != null) ? ctx.rag().size() : 0;
            int localDocsCount = (ctx.localDocs() != null) ? ctx.localDocs().size() : 0;
            int evidenceCount = (ctx.evidence() != null) ? ctx.evidence().size() : 0;
            int sourceDiversity = 0;
            if (webCount > 0) sourceDiversity++;
            if (ragCount > 0) sourceDiversity++;
            if (localDocsCount > 0) sourceDiversity++;
            if (ctx.memory() != null && !ctx.memory().isBlank()) sourceDiversity++;
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("queryHash", SafeRedactor.hash12(finalQuery));
            input.put("queryLen", safeLen(finalQuery));
            input.put("requestedTopK", webCount + ragCount + localDocsCount);
            input.put("mode", "prompt_builder");
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("returnedCount", webCount + ragCount + localDocsCount);
            output.put("afterFilterCount", webCount + ragCount + localDocsCount);
            output.put("selectedCount", evidenceCount);
            output.put("promotedCount", evidenceCount);
            output.put("stageMs", promptBuildMs);
            output.put("sourceDiversity", sourceDiversity);
            Map<String, Object> control = new LinkedHashMap<>();
            control.put("action", "promote");
            control.put("applied", true);
            control.put("reasonCode", "prompt_build");
            emitRagPipelineEvent("prompt", "prompt_build", "complete", "ChatWorkflow.promptBuild",
                    "ok", input, output, Map.of(), control);
        } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.buildPipelineEvent", ignore); }
        try {
            com.example.lms.metrics.FaithfulnessPromptTracePublisher.publishBeforeLlm(
                    finalQuery,
                    useWeb,
                    useRag,
                    ctx.web() == null ? 0 : ctx.web().size(),
                    ctx.rag() == null ? 0 : ctx.rag().size(),
                    ctx.evidence() == null ? 0 : ctx.evidence().size(),
                    Math.max(1, keepN),
                    Boolean.TRUE.equals(TraceStore.get("fallback.webOnly"))
                            || Boolean.TRUE.equals(TraceStore.get("rerank.fallback")));
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("faithfulness.promptTrace", ignore);
        }
        if (debugEventStore != null) {
            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("webCount", (ctx.web() != null) ? ctx.web().size() : 0);
                dd.put("ragCount", (ctx.rag() != null) ? ctx.rag().size() : 0);
                dd.put("localDocsCount", (ctx.localDocs() != null) ? ctx.localDocs().size() : 0);
                dd.put("citableEvidenceCount", (ctx.evidence() != null) ? ctx.evidence().size() : 0);
                dd.put("memoryPresent", ctx.memory() != null && !ctx.memory().isBlank());
                dd.put("learningRole", ragSupportRoleLabel);
                dd.put("learningSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
                dd.put("learningSummaryPresent",
                        ctx.learningContextSummary() != null && !ctx.learningContextSummary().isBlank());
                dd.put("learningSourceTags", ragSupportContext.sourceTags());
                String debugLearningDegradedReason = ragSupportContext.degradedReason();
                if (debugLearningDegradedReason == null || debugLearningDegradedReason.isBlank()) {
                    debugLearningDegradedReason = learningDegradedReason(ctx.learningSignals());
                }
                String safeDebugLearningDegradedReason = SafeRedactor.traceLabelOrFallback(debugLearningDegradedReason, "unknown");
                dd.put("learningDegraded", !debugLearningDegradedReason.isBlank());
                if (!debugLearningDegradedReason.isBlank()) {
                    dd.put("learningDegradedReason", safeDebugLearningDegradedReason);
                }
                dd.put("ragSupportRole", ragSupportRoleLabel);
                dd.put("ragSupportSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
                dd.put("ragSupportSummaryPresent",
                        ctx.learningContextSummary() != null && !ctx.learningContextSummary().isBlank());
                dd.put("ragSupportSourceTags", ragSupportContext.sourceTags());
                dd.put("ragSupportDegraded", !debugLearningDegradedReason.isBlank());
                if (!debugLearningDegradedReason.isBlank()) {
                    dd.put("ragSupportDegradedReason", safeDebugLearningDegradedReason);
                }
                dd.put("verbosity", vp.hint());
                dd.put("intent", SafeRedactor.diagnosticValue("intent", ctx.intent(), 160));
                dd.put("domain", SafeRedactor.diagnosticValue("domain", ctx.domain(), 160));
                dd.put("answerMode", (ctx.answerMode() != null) ? String.valueOf(ctx.answerMode()) : null);
                dd.put("visionMode", (ctx.visionMode() != null) ? String.valueOf(ctx.visionMode()) : null);
                dd.put("memoryMode", (ctx.memoryMode() != null) ? String.valueOf(ctx.memoryMode()) : null);
                dd.put("instrLen", (instrTxt != null) ? instrTxt.length() : 0);
                dd.put("ctxLen", (ctxText != null) ? ctxText.length() : 0);
                dd.put("instrSha1", (instrTxt != null && !instrTxt.isBlank()) ? TextUtils.sha1(instrTxt) : "");
                dd.put("queryLen", (finalQuery != null) ? finalQuery.length() : 0);
                dd.put("querySha1", (finalQuery != null) ? TextUtils.sha1(finalQuery) : "");
                debugEventStore.emit(
                        DebugProbeType.PROMPT,
                        DebugEventLevel.INFO,
                        "prompt.built",
                        "PromptBuilder.build(ctx) executed (prompt composition boundary).",
                        "ChatWorkflow.promptBuild",
                        dd,
                        null);
            } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.builtDebugEvent", ignore); }
        }
        // (湲곗〈 異쒕젰 ?뺤콉怨?蹂묓빀 - ?뱀뀡 媛뺤젣 ??
        // The output policy is now derived by the prompt orchestrator. Manual
        // string concatenation via StringBuilder/String.format has been removed
        // to comply with the prompt composition rules. A non-empty output
        // policy would be appended here if required; at present the policy
        // section is left blank to allow the PromptBuilder to manage all
        // contextual guidance.
        try {
            com.example.lms.resilience.RagFailureBlackboxService.projectCurrentTrace(
                    "ChatWorkflow.promptBuild.postProjection");
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("promptBuild.postProjection", ignore);
        }
        String outputPolicy = "";
        String unifiedCtx = ctxText; // 而⑦뀓?ㅽ듃??蹂꾨룄 System 硫붿떆吏濡?

        // ?? 3) 紐⑤뜽 ?쇱슦???곸꽭??由ъ뒪???섎룄) ???????????????????????
        ChatModel model = modelRouter.route(
                intent,
                detectRisk(userQuery), // "HIGH"|"LOW"|etc. (湲곗〈 ?ы띁)
                vp.hint(), // brief|standard|deep|ultra
                vp.targetTokenBudgetOut(), // 異쒕젰 ?좏겙 ?덉궛 ?뚰듃
                effectiveRequestedModel);

        final String resolvedModelName = modelRouter.resolveModelName(model);
        if (OpenAiTokenParamCompat.usesMaxCompletionTokens(resolvedModelName)) {
            outputPolicy = buildOutputLengthPolicy(resolvedModelName, vp.hint(), answerMode, vp.targetTokenBudgetOut());
        }

        // ?? 4) 硫붿떆吏 援ъ꽦(異쒕젰?뺤콉 ?ы븿) ????????????????????????????
        var msgs = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        // IMPORTANT: instruction/trait/system policies must be injected BEFORE the raw
        // context.
        // Otherwise the model may follow the context formatting first and drift from
        // the template.
        if (org.springframework.util.StringUtils.hasText(instrTxt)) {
            msgs.add(dev.langchain4j.data.message.SystemMessage.from(instrTxt));
        }

        // ??1) Plan/Request level extra system snippets (traits + systemPrompt)
        if (promptAssetService != null) {
            String requestedSystemPrompt = llmReq.getSystemPrompt();
            String extraSys = promptAssetService.resolveSystemPromptText(requestedSystemPrompt);
            if (!org.springframework.util.StringUtils.hasText(extraSys)
                    && org.springframework.util.StringUtils.hasText(requestedSystemPrompt)) {
                traceRejectedPublicSystemPrompt(requestedSystemPrompt);
            }
            String traitSys = promptAssetService.renderTraits(llmReq.getTraits());
            if (org.springframework.util.StringUtils.hasText(extraSys)) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(extraSys));
            }
            if (org.springframework.util.StringUtils.hasText(traitSys)) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(traitSys));
            }
        }
        if (org.springframework.util.StringUtils.hasText(outputPolicy)) {
            msgs.add(dev.langchain4j.data.message.SystemMessage.from(outputPolicy));
        }

        // Sensitive topic: add extra privacy boundary right before evidences.
        // (Avoid injecting this into creative/explore calls to reduce unintended
        // constraints.)
        try {
            gctx = GuardContextHolder.get();
            if (gctx != null && (gctx.isSensitiveTopic() || gctx.planBool("privacy.boundary.enforce", false))) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(PRIVACY_BOUNDARY_SYS));
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.privacyBoundaryMessage", ignore); }

        // Context (evidence) should come last among system messages.
        msgs.add(dev.langchain4j.data.message.SystemMessage.from(unifiedCtx));

        // ???ъ슜??吏덈Ц
        msgs.add(dev.langchain4j.data.message.UserMessage.from(finalQuery));
        recordCostZone(
                "prompt_context",
                resolvedModelName,
                estimateChatMessageChars(msgs),
                0,
                false,
                "ChatWorkflow.promptToModel");

        // ?? 5) ?⑥씪 ?몄텧 ??珥덉븞 ?????????????????????????????????????
        // 紐⑤뜽 ?쇱슦?낆쓣 留덉튇 ?? ?ㅼ젣 chat() ?몄텧 諛붾줈 吏곸쟾
        throwIfCancelled(sessionIdLong); // ??異붽?

        // 紐⑤뜽紐낆쓣 癒쇱? ?댁꽍?섏뿬 諛깆뿏?쒕퀎 釉뚮젅?댁빱 ???앹꽦
        final String breakerKey = NightmareKeys.chatDraftKey(resolvedModelName);

        // ??chat:draft ?쒗궥???ㅽ뵂?섏뼱 ?덉쑝硫?LLM ?몄텧 ?놁씠 利앷굅 湲곕컲?쇰줈 ?고쉶
        if (nightmareBreaker != null) {
            try {
                nightmareBreaker.checkOpenOrThrow(breakerKey);
            } catch (NightmareBreaker.OpenCircuitException oce) {
                ChatWorkflowTraceSuppressions.traceSuppressed("chat.draftBreakerOpen", oce); if (irregularityProfiler != null) {
                    irregularityProfiler.markHighRisk(GuardContextHolder.get(), "chat_open");
                }
                return NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModelName, useRag, topDocs, vectorDocs,
                        composeEvidenceFallback(finalQuery, topDocs, vectorDocs, queryDomain.isLowRisk()));
            }
        }

        String draft;
        // Expose evidence presence for retry fast-bailout decisions.
        try {
            int evidenceCount = 0;
            if (topDocs != null)
                evidenceCount += topDocs.size();
            if (vectorDocs != null)
                evidenceCount += vectorDocs.size();
            TraceStore.put("chat.evidence.count", evidenceCount);
            TraceStore.put("chat.evidence.present", evidenceCount > 0);
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("chat.evidenceTrace", ignore); }
        long started = System.nanoTime();
        try {
            ChatRequestDto finalReq = applyFinalAnswerSamplingOverrides(llmReq);
            draft = ensembleFinalAnswerService.tryGenerate(ctx, sessionIdLong)
                    .orElseGet(() -> callWithRetry(model, msgs, finalReq));
            if (nightmareBreaker != null) {
                long ms = (System.nanoTime() - started) / 1_000_000L;
                nightmareBreaker.recordSuccess(breakerKey, ms);
            }
        } catch (CancellationException ce) {
            log.info("[Chat] cancelled. sessionHash={}", SafeRedactor.hashValue(String.valueOf(sessionIdLong)));
            return ChatResult.of("?붿껌??痍⑥냼?섏뿀?듬땲??", "cancelled", useRag);
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("llm.chatDraft", e); String resolvedModel = resolvedModelName;

            LlmConfigurationException cfg = unwrapLlmConfigurationException(e);
            if (cfg != null) {
                String userMsg = cfg.getUserMessage();
                if (userMsg == null || userMsg.isBlank()) {
                    userMsg = "?좑툘 LLM ?ㅼ젙 ?ㅻ쪟濡??붿껌??泥섎━?????놁뒿?덈떎. (愿由ъ옄: 紐⑤뜽/?붾뱶?ъ씤???ㅼ젙 ?뺤씤)";
                }
                TraceStore.put("llm.config.code", cfg.getCode());
                TraceStore.put("llm.config.modelHash", SafeRedactor.hashValue(cfg.getModel()));
                TraceStore.put("llm.config.endpointHost", hostOf(cfg.getEndpoint()));
                TraceStore.put("llm.config.endpointHash", SafeRedactor.hashValue(cfg.getEndpoint()));
                log.error("[LLM_CONFIG] code={} modelHash={} endpointHost={} endpointHash={}{}",
                        cfg.getCode(), SafeRedactor.hashValue(cfg.getModel()), hostOf(cfg.getEndpoint()), SafeRedactor.hashValue(cfg.getEndpoint()), LogCorrelation.suffix());
                String usedModel = (cfg.getModel() != null && !cfg.getModel().isBlank()) ? cfg.getModel()
                        : resolvedModel;
                return ChatResult.of(userMsg, usedModel + ":fail:" + cfg.getCode(), useRag);
            }

            if (nightmareBreaker != null) {
                NightmareBreaker.FailureKind kind = NightmareBreaker.classify(e);
                nightmareBreaker.recordFailure(breakerKey, kind, e, finalQueryTraceContext(finalQuery));
            }
            if (irregularityProfiler != null) {
                irregularityProfiler.markHighRisk(GuardContextHolder.get(), "chat_failed");
            }

            LlmFastBailoutException fastBail = unwrapFastBail(e);

            if (fastBail != null) {
                try {
                    String fastBailReason = String.valueOf(fastBail.getMessage()).toLowerCase(java.util.Locale.ROOT);
                    if (fastBailReason.contains("upstream")) TraceStore.put("llm.fastBailUpstream5xx", true); else if (fastBailReason.contains("blank")) TraceStore.put("llm.fastBailBlankResponse", true); else TraceStore.put("llm.fastBailTimeout", true);
                    TraceStore.put("llm.fastBailTimeout.timeoutHits", fastBail.getTimeoutHits());
                    TraceStore.put("llm.fastBailTimeout.attempt", fastBail.getAttempt());
                    TraceStore.put("llm.fastBailTimeout.maxAttempts", fastBail.getMaxAttempts());
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.fastBailTrace", ignore); }

                log.warn(
                        "[LLM_FAST_BAIL_TIMEOUT] degrade-to-evidence. sessionHash={}, modelHash={}, timeoutHits={} attempt={}/{}",
                        SafeRedactor.hashValue(String.valueOf(sessionIdLong)), SafeRedactor.hashValue(resolvedModel), fastBail.getTimeoutHits(), fastBail.getAttempt(),
                        fastBail.getMaxAttempts());
            } else {
                log.error("[LLM] unavailable after retries. sessionHash={}, modelHash={} err={}",
                        SafeRedactor.hashValue(String.valueOf(sessionIdLong)), SafeRedactor.hashValue(resolvedModel), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            }

            // ??(UAW: Bypass Routing) LLM ?ㅽ뙣 ?? 利앷굅媛 ?덉쑝硫?evidence 湲곕컲 ?듬??쇰줈 sidetrain
            return NoEvidenceChatFallback.orEvidenceFallback(finalQuery, resolvedModel, useRag, topDocs, vectorDocs,
                    composeEvidenceFallback(finalQuery, topDocs, vectorDocs, queryDomain.isLowRisk()));
        }

        boolean verifyAnswer = shouldVerify(unifiedCtx, llmReq, sig);
        if (verifyAnswer) {
            recordCostZone(
                    "cross_verify",
                    resolvedModelName,
                    safeLen(finalQuery) + safeLen(unifiedCtx) + safeLen(memoryCtx) + safeLen(draft),
                    1,
                    false,
                    "ChatWorkflow.verifier");
        }
        String verified = verifyAnswer
                ? verifier.verify(
                        finalQuery,
                        /* context */ unifiedCtx,
                        /* memory */ memoryCtx,
                        draft,
                        resolvedModelName,
                        isFollowUpQuery(finalQuery, lastAnswer))
                : draft;

        // ??Evidence-aware Guard: ensure entity coverage before expansion.
        // When evidence snippets are available, verify that the answer mentions key
        // entities from the evidence. If
        // insufficient coverage is detected, the guard will regenerate the answer using
        // a higher-tier model via
        // modelRouter.route(). This is executed on the verified draft prior to any
        // expansion.
        // =========================================================================
        // [SECTION 4] Quality gate and final answer chain
        // Future extraction candidate: QualityGateChain.
        // =========================================================================
        if ((useWeb || useRag) && env != null) {
            try {
                java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs = new java.util.ArrayList<>();
                int evidIndex = 1;
                if (useWeb && topDocs != null) {
                    for (var c : topDocs) {
                        String docUrl = extractUrlOrFallback(c, evidIndex, false);
                        evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(docUrl, safeTitle(c), safeSnippet(c)));
                        evidIndex++;
                    }
                }
                if (useRag && vectorDocs != null) {
                    for (var c : vectorDocs) {
                        String docUrl = extractUrlOrFallback(c, evidIndex, true);
                        evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(docUrl, safeTitle(c), safeSnippet(c)));
                        evidIndex++;
                    }
                }
                if (!evidenceDocs.isEmpty()) {
                    var guard = evidenceAwareGuard;

                    // 1) 珥덉븞 而ㅻ쾭由ъ? 蹂댁젙 (湲곗〈 ensureCoverage 濡쒖쭅 ?좎?)
                    var coverageRes = guard.ensureCoverage(verified, evidenceDocs,
                            s -> modelRouter.route("PAIRING", "HIGH", vp.hint(), 2048, effectiveRequestedModelFinal),
                            new RouteSignal(0.3, 0, 0.2, 0, null, null, 2048, null, "evidence-guard"),
                            2);
                    if (coverageRes.regeneratedText() != null) {
                        verified = coverageRes.regeneratedText();
                    }

                    // 2) ?쒖꽑1/?쒖꽑2 GuardAction 湲곕컲 理쒖쥌 ?먮떒
                    final String draftBeforeGuard = verified;
                    try {
                        com.example.lms.resilience.RagFailureBlackboxService.projectCurrentTrace(
                                "ChatWorkflow.evidenceGuard.preDecision");
                    } catch (Throwable ignore) {
                        ChatWorkflowTraceSuppressions.traceSuppressed("evidenceGuard.preDecisionProjection", ignore);
                    }
                    EvidenceAwareGuard.GuardDecision decision = guard.guardWithEvidence(draftBeforeGuard, evidenceDocs,
                            2,
                            visionMode);

                    // [TRACE] Record guard outcome in a structured form (fail-soft).
                    try {
                        TraceStore.put("guard.action", (decision != null && decision.action() != null)
                                ? decision.action().name()
                                : "");
                        if (decision != null && decision.action() != null
                                && decision.action().name().equals("REWRITE")) {
                            TraceStore.put("guard.degradedToEvidence", true);
                            TraceStore.put("answer.mode", "EVIDENCE_ONLY");
                        }
                        String actionName = (decision != null && decision.action() != null)
                                ? decision.action().name()
                                : "UNKNOWN";
                        int selectedEvidenceCount = decision != null && decision.evidenceList() != null
                                ? decision.evidenceList().size()
                                : evidenceDocs.size();
                        boolean blocked = "REWRITE".equals(actionName) || "BLOCK".equals(actionName);
                        Map<String, Object> input = new LinkedHashMap<>();
                        input.put("queryHash", SafeRedactor.hash12(finalQuery));
                        input.put("queryLen", safeLen(finalQuery));
                        input.put("requestedTopK", evidenceDocs.size());
                        input.put("mode", "evidence_guard");
                        Map<String, Object> output = new LinkedHashMap<>();
                        output.put("returnedCount", evidenceDocs.size());
                        output.put("afterFilterCount", selectedEvidenceCount);
                        output.put("selectedCount", selectedEvidenceCount);
                        output.put("promotedCount", selectedEvidenceCount);
                        output.put("sourceDiversity", evidenceDocs.isEmpty() ? 0 : 1);
                        Map<String, Object> failure = new LinkedHashMap<>();
                        if (blocked) {
                            failure.put("reasonCode", actionName.toLowerCase(Locale.ROOT));
                            failure.put("failureClass", "evidence_guard");
                            failure.put("exceptionType", "None");
                        }
                        Map<String, Object> control = new LinkedHashMap<>();
                        control.put("action", blocked ? "block" : "promote");
                        control.put("applied", true);
                        control.put("reasonCode", actionName.toLowerCase(Locale.ROOT));
                        emitRagPipelineEvent("guard", "evidence_guard", "complete",
                                "ChatWorkflow.evidenceGuard", blocked ? "blocked" : "ok",
                                input, output, failure, control);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.outcomeTrace", ignore);
        }

                    switch (decision.action()) {
                        case ALLOW -> {
                            // ?쒖꽑1: ?듬? ?ъ슜 + 硫붾え由?媛뺥솕 ?덉슜
                            verified = decision.finalDraft();
                        }
                        case ALLOW_NO_MEMORY -> {
                            // ?쒖꽑2: ?듬? ?ъ슜, 硫붾え由?媛뺥솕 湲덉?
                            String out = decision.finalDraft();
                            // If this is a citations-detour case, try a one-shot cheap web retry to recover
                            // citations.
                            try {
                                String recovered = tryDetourCheapRetry(finalQuery, queryDomain, metaHints,
                                        sessionIdLong, visionMode, evidenceDocs, draftBeforeGuard, model, llmReq,
                                        breakerKey);
                                if (recovered != null && !recovered.isBlank()) {
                                    out = recovered;
                                }
                            } catch (Exception ignore) {
                                ChatWorkflowTraceSuppressions.traceSuppressed("guard.detourCheapRetry", ignore);
                            }
                            verified = out;
                            log.debug("[ChatService] GuardAction: ALLOW_NO_MEMORY (Vision 2)");
                        }
                        case REWRITE -> {
                            // Prompt 臾몄옄?댁쓣 吏곸젒 議곕┰?섏? ?딅뒗??
                            // Evidence 湲곕컲 ?듬? 而댄룷?濡??ъ옉?깊븳??
                            log.debug("[ChatWorkflow] GuardAction: REWRITE -> evidence-only answer composer");
                            verified = composeEvidenceOnlyAnswer(evidenceDocs, finalQuery);
                        }
                        case BLOCK -> {
                            // ?듬? 李⑤떒: STRIKE/?뺤텞/?고쉶 ?곹솴?대㈃ '?덉쟾??????듬?'?쇰줈 ?섎졃
                            if (sig.bypassMode() || sig.strikeMode() || sig.compressionMode()
                                    || (hints != null && hints.isBypassMode())) {
                                verified = bypassRoutingService.renderSafeAlternative(
                                        finalQuery,
                                        decision.evidenceList(),
                                        queryDomain.isLowRisk(),
                                        sig);
                                log.debug("[ChatService] GuardAction: BLOCK -> BypassRouting ({})", sig.modeLabel());
                            } else {
                                // 湲곕낯: guard媛 留뚮뱺 safe draft ?좎?
                                verified = decision.finalDraft();
                                log.debug("[ChatService] GuardAction: BLOCK -> Guard finalDraft");
                            }
                        }
                        default -> {
                            // no-op
                        }
                    }

                    // 3) ?쒖꽑1 ?꾩슜 硫붾え由?媛뺥솕 (利앷굅 ?ㅻ땲??湲곕컲)
                    try {
                        memorySvc.reinforceFromGuardDecision(sessionKey, finalQuery, decision, memoryMode);
                    } catch (Exception ex) {
                        log.debug("[ChatService] reinforceFromGuardDecision failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
                    }

                    // 4) [FAIL-SAFE] 理쒖쥌 ?묐떟 吏곸쟾 寃利?
                    if (evidenceDocs != null
                            && !evidenceDocs.isEmpty()
                            && com.example.lms.service.guard.EvidenceAwareGuard.looksNoEvidenceTemplate(verified)) {
                        recordFinalRescueSilentFailure(finalQuery, breakerKey, evidenceDocs.size(),
                                "evidence_guard_no_info_with_evidence");
                        log.error("[RESCUE] Final output is still 'No Info' despite evidence! Forcing fallback.");
                        verified = guard.degradeToEvidenceList(evidenceDocs);
                    }
                }
            } catch (Exception e) {
                // Ignore guard failures to avoid breaking the chat flow
                log.debug("[guard] evidence-aware coverage failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            }
        }

        // ?쇄뼹??[RESCUE LOGIC PHASE 2] 理쒖쥌 ?뚰뵾 ?듬? 媛뺤젣 ?꾪솚 ?쇄뼹??
        boolean hasAnyEvidence = (useWeb && topDocs != null && !topDocs.isEmpty())
                || (useRag && vectorDocs != null && !vectorDocs.isEmpty());
        if (hasAnyEvidence && isDefinitiveFailure(verified)) {
            long rescueNo = rescueCount.incrementAndGet();
            int rescueEvidenceCount = (useWeb && topDocs != null ? topDocs.size() : 0)
                    + (useRag && vectorDocs != null ? vectorDocs.size() : 0);
            String rescueQueryHash = SafeRedactor.hashValue(finalQuery);
            recordFinalRescueSilentFailure(finalQuery, breakerKey, rescueEvidenceCount,
                    "definitive_failure_with_evidence");
            log.info("[Rescue]#{} redacted queryHash={} queryLen={}",
                    rescueNo,
                    rescueQueryHash,
                    finalQuery == null ? 0 : finalQuery.length());
            log.info("[Rescue]#{}, visionMode={}, ?듬???'?뺣낫 遺議? ?⑦꽩?쇰줈 ?먮퀎?섏뿀?쇰굹 利앷굅媛 議댁옱??"
                    + "(useWeb={}, topDocs={}, useRag={}, vectorDocs={}). EvidenceComposer濡?媛뺤젣 ?꾪솚?⑸땲?? (queryHash={})",
                    rescueNo,
                    visionMode,
                    useWeb, (topDocs != null ? topDocs.size() : 0),
                    useRag, (vectorDocs != null ? vectorDocs.size() : 0),
                    rescueQueryHash);

            java.util.List<com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc> rescueDocs = new java.util.ArrayList<>();
            try {
                int _idx = 1;
                if (useWeb && topDocs != null) {
                    for (var c : topDocs) {
                        rescueDocs.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                extractUrlOrFallback(c, _idx, false),
                                safeTitle(c),
                                safeSnippet(c)));
                        _idx++;
                    }
                }
                if (useRag && vectorDocs != null) {
                    for (var c : vectorDocs) {
                        rescueDocs.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                extractUrlOrFallback(c, _idx, true),
                                safeTitle(c),
                                safeSnippet(c)));
                        _idx++;
                    }
                }

                boolean lowRisk = isLowRiskDomain(rescueDocs);
                verified = evidenceAnswerComposer.compose(finalQuery, rescueDocs, lowRisk);
                if (verified != null) {
                    log.debug("[Rescue]#{} 利앷굅 湲곕컲 ?듬? ?앹꽦 ?꾨즺 (length={})", rescueNo, verified.length());
                }
            } catch (Exception e) {
                log.warn("[Rescue]#{} EvidenceComposer ?ㅽ뙣, Evidence 由ъ뒪?몃줈 Fallback ?쒕룄: {}", rescueNo, String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                // Fallback: 理쒖냼??利앷굅 紐⑸줉?대씪??蹂댁뿬二쇨린
                try {
                    com.example.lms.service.guard.EvidenceAwareGuard guard = evidenceAwareGuard;
                    verified = guard.degradeToEvidenceList(rescueDocs);
                } catch (Exception e2) {
                    // 理쒖쥌 Fallback
                    log.warn("[Rescue]#{} Evidence 由ъ뒪???앹꽦???ㅽ뙣: {}", rescueNo, String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e2)), String.valueOf(e2).length()));
                    verified = "寃??寃곌낵媛 議댁옱?섎굹 ?듬? ?앹꽦???ㅽ뙣?덉뒿?덈떎. ?ㅼ떆 ?쒕룄??二쇱꽭??";
                }
            }
        } else if (!hasAnyEvidence && visionMode == VisionMode.FREE) {
            // 利앷굅媛 ?녿뒗 寃쎌슦 FREE 紐⑤뱶?먯꽌??異붿륫/李쎌옉???섏? ?딄퀬 紐낆떆?곸쑝濡?'?뺣낫 ?놁쓬'?쇰줈 ?묐떟
            if (isDefinitiveFailure(verified)) {
                verified = "?뺣낫 ?놁쓬";
            }
        }
        // ?꿎뼯??[END RESCUE LOGIC] ?꿎뼯??

        // ?? 6) 湲몄씠 寃利???議곌굔遺 1???뺤옣 ???????????????????????????
        String out = verified;
        // ??Weak-draft suppression: if output still looks empty/"?뺣낫 ?놁쓬", degrade to
        // evidence list instead of leaking
        try {
            if (com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(out)) {
                boolean hasWebEvidence = topDocs != null && !topDocs.isEmpty();
                boolean hasVectorEvidence = vectorDocs != null && !vectorDocs.isEmpty();
                if (hasWebEvidence || hasVectorEvidence) {
                    java.util.List<com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc> _ev = new java.util.ArrayList<>();
                    int _i = 1;
                    if (hasWebEvidence) {
                        for (var d : topDocs) {
                            String docUrl = extractUrlOrFallback(d, _i, false);
                            _ev.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                    docUrl,
                                    safeTitle(d),
                                    safeSnippet(d)));
                            _i++;
                        }
                    }
                    if (hasVectorEvidence) {
                        for (var d : vectorDocs) {
                            String docUrl = extractUrlOrFallback(d, _i, true);
                            _ev.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                    docUrl,
                                    safeTitle(d),
                                    safeSnippet(d)));
                            _i++;
                        }
                    }
                    boolean lowRisk = isLowRiskDomain(_ev);
                    try {
                        out = evidenceAnswerComposer.compose(finalQuery, _ev, lowRisk);
                    } catch (Exception composerError) {
                        log.debug("[guard] evidence composer failed, falling back to evidence list: {}",
                                String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(composerError)), String.valueOf(composerError).length()));
                        out = evidenceAwareGuard.degradeToEvidenceList(_ev);
                    }
                } else {
                    out = "異⑸텇??利앷굅瑜?李얠? 紐삵뻽?듬땲?? ??援ъ껜?곸씤 ?ㅼ썙?쒕굹 留λ씫???뚮젮二쇱떆硫??뺥솗?꾧? ?щ씪媛묐땲??";
                }
            }
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("answer.guardRecovery", ignore);
        }

        if (lengthVerifier.isShort(out, vp.minWordCount())) {
            out = Optional.ofNullable(answerExpander.expandWithLc(out, vp, model)).orElse(out);
        }

        // Evidence-aware regeneration guard (legacy) removed: the pipeline either
        // rewrites using evidence-only answers
        // or expands with the configured answerExpander.
        // [Dual-Vision] View2 2李??⑥뒪
        // - 湲곕낯: (GAME/SUBCULTURE)?먯꽌留?free idea
        // - projection_agent.v1: GENERAL源뚯? ?뺤옣 + merge + final polish
        if (visionMode != VisionMode.STRICT && (riskLevel == null || !"HIGH".equals(riskLevel))) {
            boolean allowProjectionAgent = projectionPipeline
                    && projectionPlan != null
                    && queryDomain != null
                    && queryDomain.isLowRisk()
                    && answerMode != AnswerMode.FACT;

            if (allowProjectionAgent) {
                try {
                    String creative = generateProjectionDraftFromPlan(
                            finalQuery,
                            out, // strictAnswer
                            ctxText,
                            vp,
                            llmReq,
                            projectionPlan);

                    if (StringUtils.hasText(creative)) {
                        if (projectionMergeService != null) {
                            // merge() config瑜??쒖슜?섏?留? mergeDualView??2?몄옄留?諛쏆쑝誘濡?湲곕낯 援ы쁽 ?ъ슜
                            out = projectionMergeService.mergeDualView(out, creative);
                            if (freeIdeaCount != null) {
                                freeIdeaCount.incrementAndGet();
                            }

                            // Final answer pass (projection.final)
                            out = finalizeProjectionAnswerFromPlan(
                                    finalQuery,
                                    out,
                                    vp,
                                    llmReq,
                                    projectionPlan);
                        } else {
                            TraceStore.put("prompt.projection.creative.skipped", "no_merge_service");
                            log.debug("[ProjectionAgent] creative section suppressed: projectionMergeService=null");
                        }
                    }
                } catch (Exception e) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("projection.view2.pipeline", e); log.debug("[ProjectionAgent] View2 pipeline failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                }
            } else {
                boolean lowRiskDomain = (queryDomain == QueryDomain.GAME || queryDomain == QueryDomain.SUBCULTURE);
                if (lowRiskDomain && answerMode != AnswerMode.FACT) {
                    try {
                        String creative = generateFreeIdeaDraft(
                                finalQuery,
                                out, // strictAnswer
                                ctxText,
                                modelRouter,
                                vp,
                                effectiveRequestedModel);
                        if (StringUtils.hasText(creative)) {
                            if (projectionMergeService != null) {
                                out = projectionMergeService.mergeDualView(out, creative);
                                if (freeIdeaCount != null) {
                                    freeIdeaCount.incrementAndGet();
                                }
                                log.debug("[DualVision] View2 creative section merged (length={})", creative.length());
                            } else {
                                TraceStore.put("prompt.dualvision.creative.skipped", "no_merge_service");
                                log.debug("[DualVision] creative section suppressed: projectionMergeService=null");
                            }
                        }
                    } catch (Exception e) {
                        ChatWorkflowTraceSuppressions.traceSuppressed("dualVision.view2.generation", e); log.debug("[DualVision] View2 generation failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                    }
                }
            }
        }

        // ?? 7) ?꾩쿂由?媛뺥솕/由ы꽩 ??????????????????????????????????????
        // (??긽 ??? - ?명꽣?됲꽣 + 湲곗〈 媛뺥솕 濡쒖쭅 蹂묓뻾 ?덉슜

        // [Dual-Vision] 硫붾え由???μ? STRICT ?듬?留?(verified 湲곗?)
        String strictAnswerForMemory = verified;

        if (visionMode == VisionMode.FREE) {
            log.info("[DualVision] View 2 (Free) active. Skipping Long-term Memory Save.");
        } else {
            try {
                // 癒쇱? ?숈뒿???명꽣?됲꽣???꾨떖?섏뿬 援ъ“?붾맂 吏???숈뒿???섑뻾?⑸땲??
                learningWriteInterceptor.ingest(sessionKey, userQuery, strictAnswerForMemory, /* score */ 0.5);
            } catch (Throwable ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("memory.learningWriteInterceptor", ignore);
            }
            try {
                memoryWriteInterceptor.save(sessionKey, userQuery, strictAnswerForMemory, /* score */ 0.5);
            } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("memory.writeInterceptor", ignore); }
            // ?댄빐 ?붿빟 諛?湲곗뼲 ?명꽣?됲꽣: 寃利??뺤옣??理쒖쥌 ?듬???援ъ“???붿빟?섏뿬 ??ν븯怨?SSE濡??꾩넚
            try {
                understandAndMemorizeInterceptor.afterVerified(
                        sessionKey,
                        userQuery,
                        strictAnswerForMemory,
                        req.isUnderstandingEnabled());
            } catch (Throwable ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("memory.understandAndMemorize", ignore);
            }
            reinforce(sessionKey, userQuery, strictAnswerForMemory, visionMode, guardProfile, memoryMode);
        }
        // ???ㅼ젣 紐⑤뜽紐낆쑝濡?蹂닿퀬 (?ㅽ뙣 ???덉쟾 ?대갚)
        String modelUsed;
        try {
            modelUsed = modelRouter.resolveModelName(model);
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("model.resolveName", e); modelUsed = String.format("lc:%s", getModelName(model));
        }
        // ?? Evidence reference appendix: map [W1]/[V2] markers to real sources ??
        // ?듬? 蹂몃Ц??[W2] 媛숈? 留덉빱媛 ?⑥븘?덉?留?異쒖쿂 紐⑸줉???녿뒗 寃쎌슦瑜?蹂댁셿?쒕떎.
        try {
            if (ragEvidenceAttributionService != null) {
                if (citableEvidence != null && !citableEvidence.isEmpty()) {
                    out = ragEvidenceAttributionService.appendFinalEvidenceAppendix(out, citableEvidence);
                } else {
                    TraceStore.put("rag.evidence.appendix.skipped", "no_promoted_evidence");
                }
            } else {
                out = appendEvidenceReferencesIfNeeded(out, topDocs, vectorDocs);
            }

            // EMPTY ANSWER GUARD: avoid silent SSE (no tokens) when LLM returns blank.
            // If final output is empty, fall back to evidence-only composer (if any) to
            // ensure a visible response.
            out = emptyAnswerGuard(out, finalQuery, topDocs, vectorDocs, ragEvidenceAttributionService, citableEvidence);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("evidence.appendix", ignore);
        }

        // ?? Needle probe outcome reward (does needle evidence actually contribute?) ??
        if (needleExecuted && needleContributionEvaluator != null) {
            try {
                NeedleContribution contrib = needleContributionEvaluator.evaluate(
                        needleDocsForReward,
                        needleUrls,
                        topDocs,
                        needleBeforeSignals,
                        needleAfterSignals);
                TraceStore.put("probe.needle.executed", true);
                TraceStore.put("probe.needle.contribution.docsAdded", contrib.docsAdded());
                TraceStore.put("probe.needle.contribution.docsUsedInTopN", contrib.docsUsedInTopN());
                TraceStore.put("probe.needle.contribution.qualityDelta", contrib.qualityDelta());
                TraceStore.put("probe.needle.contribution.triggered", contrib.triggered());
                TraceStore.put("probe.needle.contribution.effective", contrib.isEffective());

                if (needleOutcomeRewarder != null) {
                    double reward = needleOutcomeRewarder.computeReward(contrib);
                    TraceStore.put("probe.needle.reward", reward);
                }
            } catch (Exception e) {
                log.debug("[NeedleProbeReward] {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            }
        }

        // 利앷굅 吏묓빀 ?뺣━
        java.util.LinkedHashSet<String> evidence = new java.util.LinkedHashSet<>();
        if (useWeb && !topDocs.isEmpty())
            evidence.add("WEB");
        if (useRag && !vectorDocs.isEmpty())
            evidence.add("RAG");
        if (memoryCtx != null && !memoryCtx.isBlank())
            evidence.add("MEMORY");
        boolean ragUsed = evidence.contains("WEB") || evidence.contains("RAG");
        Map<String, Object> answerInput = new LinkedHashMap<>();
        answerInput.put("queryHash", SafeRedactor.hash12(finalQuery));
        answerInput.put("queryLen", safeLen(finalQuery));
        answerInput.put("requestedTopK", evidence.size());
        answerInput.put("mode", "final_answer");
        Map<String, Object> answerOutput = new LinkedHashMap<>();
        answerOutput.put("returnedCount", evidence.size());
        answerOutput.put("afterFilterCount", evidence.size());
        answerOutput.put("selectedCount", (out != null && !out.isBlank()) ? 1 : 0);
        answerOutput.put("promotedCount", citableEvidence == null ? 0 : citableEvidence.size());
        answerOutput.put("sourceDiversity", evidence.size());
        Map<String, Object> answerFailure = new LinkedHashMap<>();
        if (out == null || out.isBlank()) {
            answerFailure.put("reasonCode", "silent_failure");
            answerFailure.put("failureClass", "silent_failure");
            answerFailure.put("exceptionType", "None");
        }
        Map<String, Object> answerControl = new LinkedHashMap<>();
        answerControl.put("action", (out != null && !out.isBlank()) ? "promote" : "fail_soft_fallback");
        answerControl.put("applied", true);
        answerControl.put("reasonCode", (out != null && !out.isBlank()) ? "final_answer" : "silent_failure");
        emitRagPipelineEvent("answer", "final_answer", "complete", "ChatWorkflow.finalAnswer",
                (out != null && !out.isBlank()) ? "ok" : "fallback",
                answerInput, answerOutput, answerFailure, answerControl);
        clearCancel(sessionIdLong); // ??異붽?

        return ChatResult.of(out, modelUsed, ragUsed,
                java.util.Collections.unmodifiableSet(evidence),
                citableEvidence == null ? java.util.List.of() : citableEvidence);
    } // ??硫붿꽌???? ?먥쁾??諛섎뱶???ル뒗 以묎큵???뺤씤

    /**
     * Final safety net for the chat pipeline: prevent 'silent empty' answers.
     *
     * <p>
     * When the final answer becomes blank (e.g., LLM transient EMPTY/blank), SSE
     * streaming
     * emits no tokens and the client may appear frozen. This guard ensures we
     * always
     * return a non-empty response by stepping down to an evidence-only answer (when
     * available).
     */
    // =========================================================================
    // [SECTION 3] Retrieval/evidence processing
    // Future extraction candidate: RetrievalResultProcessor.
    // =========================================================================
    private String emptyAnswerGuard(
            String out,
            String finalQuery,
            java.util.List<dev.langchain4j.rag.content.Content> topDocs,
            java.util.List<dev.langchain4j.rag.content.Content> vectorDocs,
            RagEvidenceAttributionService attributionService,
            java.util.List<RagEvidenceMetadata> citableEvidence) {
        if (out != null && !out.trim().isEmpty()) {
            return out;
        }

        try {
            TraceStore.put("chat.emptyAnswerGuard.triggered", true);
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("emptyAnswerGuard.triggerTrace", ignore);
        }

        java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs = new java.util.ArrayList<>();

        int idx = 1;
        if (topDocs != null) {
            for (dev.langchain4j.rag.content.Content c : topDocs) {
                if (c == null)
                    continue;
                if (evidenceDocs.size() >= 8)
                    break;
                String url = extractUrlOrFallback(c, idx, false);
                evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, safeTitle(c), safeSnippet(c)));
                idx++;
            }
        }

        idx = 1;
        if (vectorDocs != null) {
            for (dev.langchain4j.rag.content.Content c : vectorDocs) {
                if (c == null)
                    continue;
                if (evidenceDocs.size() >= 8)
                    break;
                String url = extractUrlOrFallback(c, idx, true);
                evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, safeTitle(c), safeSnippet(c)));
                idx++;
            }
        }

        if (!evidenceDocs.isEmpty()) {
            try {
                TraceStore.put("chat.emptyAnswerGuard.evidenceDocs", evidenceDocs.size());
            } catch (Throwable ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("emptyAnswerGuard.evidenceDocsTrace", ignore);
            }

            String fb = composeEvidenceOnlyAnswer(evidenceDocs, finalQuery == null ? "" : finalQuery);
            if (fb != null && !fb.isBlank()) {
                try {
                    if (attributionService != null) {
                        if (citableEvidence != null && !citableEvidence.isEmpty()) {
                            fb = attributionService.appendFinalEvidenceAppendix(fb, citableEvidence);
                        } else {
                            TraceStore.put("chat.emptyAnswerGuard.appendixSkipped", "no_promoted_evidence");
                        }
                    } else {
                        fb = appendEvidenceReferencesIfNeeded(fb,
                                topDocs == null ? java.util.List.of() : topDocs,
                                vectorDocs == null ? java.util.List.of() : vectorDocs);
                    }
                } catch (Throwable ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("emptyAnswerGuard.appendixTrace", ignore);
                }

                try {
                    TraceStore.put("chat.emptyAnswerGuard.fallback", "evidence_only");
                } catch (Throwable ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("emptyAnswerGuard.fallbackTrace", ignore);
                }
                return fb;
            }
        }

        try {
            TraceStore.put("chat.emptyAnswerGuard.fallback", "composer_blank");
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("emptyAnswerGuard.finalFallbackTrace", ignore);
        }

        return "?듬? ?앹꽦 以?鍮??묐떟??諛쒖깮?덉뒿?덈떎. 紐⑤뜽???쇱떆?곸쑝濡?遺덉븞?뺥븯嫄곕굹, 寃?됰맂 洹쇨굅媛 遺議깊븷 ???덉뒿?덈떎. 吏덈Ц??議곌툑 諛붽퓭???ㅼ떆 ?쒕룄??二쇱꽭??";
    }

    /**
     * EvidenceAwareGuard媛 REWRITE瑜??붿껌?덉쓣 ?? LLM???ы샇異쒗븯吏 ?딄퀬
     * ?대? ?섏쭛??evidence(snippets)留뚯쑝濡?蹂댁닔?곸씤 ?듬???援ъ꽦?⑸땲??
     * <p>
     * - Guard媛 "利앷굅 而ㅻ쾭由ъ? 遺議????먮떒??寃쎌슦?먮쭔 ?ъ슜
     * - ?꾪뿕?꾧? ??? ?꾨찓??寃뚯엫/?꾪궎/而ㅻ??덊떚 ???먯꽌??臾멸뎄瑜??꾪솕
     */
    private String composeEvidenceOnlyAnswer(java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs,
            String query) {
        try {
            boolean lowRisk = isLowRiskDomain(evidenceDocs);
            if (evidenceAnswerComposer == null) {
                // Should not happen (DI), but fail-soft.
                return "寃?됰맂 ?먮즺瑜?諛뷀깢?쇰줈 ?뺣━?덉쑝?? ?듬? 而댄룷?媛 ?놁뼱 ?붿빟??援ъ꽦?섏? 紐삵뻽?듬땲??";
            }
            return evidenceAnswerComposer.compose(query, evidenceDocs, lowRisk);
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("evidenceOnly.compose", e); return "寃??寃곌낵媛 異⑸텇?섏? ?딆븘 ?듬???援ъ꽦?섍린 ?대졄?듬땲??";
        }
    }

    /**
     * Guard detour媛 insufficient citations濡??⑥뼱吏?耳?댁뒪???쒗빐??
     * user ?ъ쭏臾??놁씠 citationMin??梨꾩슦湲??꾪븳 "cheap retry"瑜?1???쒕룄?⑸땲??
     *
     * ?꾨왂:
     * - finalQuery??site: ?뚰듃瑜?1媛?遺숈뿬 webSearchRetriever瑜???踰????몄텧
     * - ?덈줈??EvidenceDoc瑜??⑹퀜 citationMin??留뚯”?섎㈃ evidence-only ?듬??쇰줈 利됱떆 蹂듭썝
     * - ?ㅽ뙣?섎㈃ null (湲곗〈 detour 硫붿떆吏 ?좎?)
     */

    private String tryDetourCheapRetry(
            String finalQuery,
            QueryDomain queryDomain,
            Map<String, Object> metaHints,
            long sessionIdLong,
            VisionMode visionMode,
            List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs,
            String draftBeforeGuard,
            ChatModel model,
            ChatRequestDto llmReq,
            String breakerKey) {

        String detourReason = (String) TraceStore.get("guard.detour");
        if (!"insufficient_citations".equals(detourReason)) {
            return null;
        }
        TraceStore.put("rag.critic.triggered", true);
        TraceStore.put("rag.critic.reason", SafeRedactor.traceLabelOrFallback(detourReason, "unknown"));
        TraceStore.put("rag.critic.retry.count", 0);

        int needAtLeast = 2; // default when minCitations is unknown
        try {
            Object req = TraceStore.get("guard.minCitations.required");
            if (req instanceof Number n && n.intValue() > 0) {
                needAtLeast = n.intValue();
            }
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.requiredCitationsTrace", ignore);
        }
        try {
            com.example.lms.service.guard.GuardContext gctx0 = com.example.lms.service.guard.GuardContextHolder.get();
            Integer mc = (gctx0 != null ? gctx0.getMinCitations() : null);
            if (mc != null && mc > 0) {
                needAtLeast = mc;
            }
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.contextCitationsTrace", ignore);
        }

        boolean forceEscalate = false;
        try {
            Object forced = TraceStore.get("guard.detour.forceEscalate");
            boolean forcedFlag = (forced instanceof Boolean b) ? b
                    : "true".equalsIgnoreCase(String.valueOf(forced));
            Object trig0 = TraceStore.get("web.failsoft.starvationFallback.trigger");
            if (trig0 == null || String.valueOf(trig0).isBlank()) {
                trig0 = TraceStore.get("starvationFallback.trigger");
            }
            boolean belowMin = QueryTypeHeuristics.isBelowMinCitationsTrigger(trig0);
            com.example.lms.service.guard.GuardContext gctx = com.example.lms.service.guard.GuardContextHolder.get();
            String uq = (gctx != null && gctx.getUserQuery() != null && !gctx.getUserQuery().isBlank())
                    ? gctx.getUserQuery()
                    : finalQuery;
            boolean ctxEntity = (gctx != null && gctx.isEntityQuery());
            boolean heurEntity = QueryTypeHeuristics.looksLikeEntityQuery(uq);
            boolean heurDef = QueryTypeHeuristics.isDefinitional(uq);

            String by = forcedFlag
                    ? "forcedFlag"
                    : (ctxEntity ? "ctx.entityQuery" : (heurDef ? "heur.definitional" : "heur.entity"));

            forceEscalate = forcedFlag || (belowMin && (ctxEntity || heurEntity || heurDef));
            TraceStore.put("guard.detour.cheapRetry.forceEscalate", forceEscalate);
            TraceStore.put("guard.detour.cheapRetry.forceEscalate.by", by);
            TraceStore.put("guard.detour.cheapRetry.forceEscalate.trigger", String.valueOf(trig0));
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.forceEscalateTrace", ignore);
        }

        if (evidenceDocs == null) {
            return null;
        }
        TraceStore.put("rag.critic.docs.before", evidenceDocs.size());
        if (!forceEscalate && evidenceDocs.size() >= needAtLeast) {
            return null;
        }

        final List<String> sites = chooseDetourRetrySites(finalQuery, queryDomain);
        if (sites.isEmpty()) {
            TraceStore.put("guard.detour.cheapRetry.skip", "no_sites");
            // [PATCH src111_merge15/merge15] Even without site hints, entity/definitional
            // queries can still be regenerated from the existing evidence (if any) to avoid
            // returning an evidence-list detour only.
            if (forceEscalate && evidenceDocs != null && !evidenceDocs.isEmpty()) {
                String regen = tryDetourCheapRetryLlmRegen(finalQuery, draftBeforeGuard, evidenceDocs,
                        model, llmReq, breakerKey, queryDomain, true);
                if (regen != null && !regen.isBlank()) {
                    TraceStore.put("guard.detour.cheapRetry.output", "llm_regen_forced");
                    return regen;
                }
                // ForceEscalate mode: if we can't regen, keep the current draft (avoid evidence-only collapse).
                TraceStore.put("guard.detour.cheapRetry.output", "keep_draft_forced");
                return null;
            }
            return null;
        }
        TraceStore.put("guard.detour.cheapRetry.sites", String.join(",", sites));

        final int totalBudgetMs = (int) Math.max(150, this.detourCheapRetryWebBudgetMs);
        final int topK = Math.max(1, this.detourCheapRetryWebTopK);
        final int maxToAdd = Math.max(1, this.detourCheapRetryMaxAddedDocs);

        int before = evidenceDocs.size();
        int addedTotal = 0;

        final boolean combineSitesWithOr = shouldDetourCheapRetryCombineSitesWithOr(finalQuery, sites);
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr", combineSitesWithOr);

        if (combineSitesWithOr && sites.size() > 1) {
            String retryQuery = finalQuery + " " + buildSiteOrClause(sites);
            addedTotal += runDetourSearchAttempt(retryQuery, metaHints, sessionIdLong, visionMode,
                    evidenceDocs, topK, totalBudgetMs, maxToAdd - addedTotal);
        } else {
            final int perAttemptBudgetMs = Math.max(150, totalBudgetMs / Math.max(1, sites.size()));
            for (String site : sites) {
                if (addedTotal >= maxToAdd || evidenceDocs.size() >= needAtLeast) {
                    break;
                }
                String retryQuery = finalQuery + " site:" + site;
                int added = runDetourSearchAttempt(retryQuery, metaHints, sessionIdLong, visionMode,
                        evidenceDocs, topK, perAttemptBudgetMs, maxToAdd - addedTotal);
                addedTotal += added;
            }
        }

        TraceStore.put("guard.detour.cheapRetry.addedDocs", Math.max(0, evidenceDocs.size() - before));
        TraceStore.put("rag.critic.retry.count", 1);
        TraceStore.put("rag.critic.docs.after", evidenceDocs.size());
        boolean reachedMin = evidenceDocs.size() >= needAtLeast;
        if (reachedMin) {
            TraceStore.put("guard.detour.cheapRetry.recovered", true);
        }

        // [PATCH src111_merge15/merge15] If forceEscalate is active (entity/definitional + BELOW_MIN_CITATIONS),
        // attempt an evidence-grounded LLM regen even if we couldn't reach the citation minimum. This keeps
        // the response from collapsing into an evidence-list-only detour.
        if (reachedMin || (forceEscalate && evidenceDocs != null && !evidenceDocs.isEmpty())) {
            String regen = tryDetourCheapRetryLlmRegen(finalQuery, draftBeforeGuard, evidenceDocs,
                    model, llmReq, breakerKey, queryDomain, forceEscalate);
            if (regen != null && !regen.isBlank()) {
                TraceStore.put("guard.detour.cheapRetry.output", forceEscalate ? "llm_regen_forced" : "llm_regen");
                return regen;
            }

            if (forceEscalate) {
                // ForceEscalate mode: if we can't regen, keep the current draft (allow citation-poor draft).
                TraceStore.put("guard.detour.cheapRetry.output", "keep_draft_forced");
                return null;
            }

            TraceStore.put("guard.detour.cheapRetry.output", "evidence_only");
            return composeEvidenceOnlyAnswer(evidenceDocs, finalQuery);
        }

        return null;
    }

    private int runDetourSearchAttempt(
            String retryQuery,
            Map<String, Object> baseMetaHints,
            long sessionIdLong,
            VisionMode visionMode,
            List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs,
            int webTopK,
            int webBudgetMs,
            int maxToAdd) {

        if (maxToAdd <= 0) {
            return 0;
        }

        Map<String, Object> md = new HashMap<>();
        if (baseMetaHints != null) {
            md.putAll(baseMetaHints);
        }
        md.put("useWeb", "true");
        md.put("webTopK", webTopK);
        md.put("webBudgetMs", webBudgetMs);
        md.putIfAbsent("siteFilter.minDocsToSkipSearch", Math.min(webTopK, Math.max(1, maxToAdd)));

        List<Content> docs;
        try {
            TraceStore.inc("guard.detour.cheapRetry.web.calls");
            docs = webSearchRetriever.retrieve(QueryUtils.buildQuery(retryQuery, sessionIdLong, null, md));
        } catch (Exception e) {
            TraceStore.put("guard.detour.cheapRetry.web.error", "cheap_retry_web_failed");
            return 0;
        }
        if (docs == null || docs.isEmpty()) {
            return 0;
        }

        Set<String> existingIds = evidenceDocs.stream()
                .map(EvidenceAwareGuard.EvidenceDoc::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int added = 0;
        for (Content d : docs) {
            if (added >= maxToAdd) {
                break;
            }
            if (d == null || d.textSegment() == null) {
                continue;
            }
            var meta = d.textSegment().metadata();
            String docId = (meta != null) ? meta.getString("docId") : null;
            String url = (meta != null) ? meta.getString("url") : null;
            String id = (docId != null && !docId.isBlank()) ? docId : url;
            if (id == null || id.isBlank()) {
                continue;
            }
            if (existingIds.contains(id)) {
                continue;
            }
            String title = (meta != null) ? meta.getString("title") : null;
            String snippet = d.textSegment().text();
            if (snippet != null && snippet.length() > 800) {
                snippet = snippet.substring(0, 800) + "...";
            }
            evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(id, title, snippet));
            existingIds.add(id);
            added++;
        }
        return added;
    }

    private List<String> chooseDetourRetrySites(String query, QueryDomain queryDomain) {
        final int maxSites = Math.max(1, this.detourCheapRetryMaxSites);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        String primary = chooseDetourRetrySite(query, queryDomain);
        if (primary != null && !primary.isBlank()) {
            out.add(normalizeSiteHint(primary));
        }

        String q = (query == null) ? "" : query;
        String qLower = q.toLowerCase(Locale.ROOT);
        boolean hasKorean = q.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);

        // Intent-specific high-signal sites
        boolean genshin = q.contains("?먯떊") || qLower.contains("genshin");
        if (genshin) {
            out.add("hoyolab.com");
            out.add("hoyoverse.com");
        }

        if (queryDomain == QueryDomain.GAME || queryDomain == QueryDomain.SUBCULTURE) {
            out.add("namu.wiki");
            out.add("wikipedia.org");
        } else if (queryDomain == QueryDomain.STUDY) {
            out.add("docs.oracle.com");
            out.add("developer.mozilla.org");
            out.add("docs.spring.io");
            out.add("github.com");
        } else if (queryDomain == QueryDomain.GENERAL) {
            out.add("wikipedia.org");
            out.add("terms.naver.com");
        }

        if (hasKorean) {
            out.add("terms.naver.com");
            out.add("namu.wiki");
        }
        out.add("wikipedia.org");

        // Configured site hints (lowest priority)
        for (String s : parseCsv(this.detourCheapRetrySiteHintsCsv)) {
            out.add(normalizeSiteHint(s));
        }

        return out.stream()
                .filter(s -> s != null && !s.isBlank())
                .limit(maxSites)
                .collect(Collectors.toList());
    }

    /**
     * Decide whether to combine multiple "site:" hints using a single
     * {@code (site:a OR site:b)} clause.
     *
     * <p>
     * We support explicit override via
     * {@code guard.detour.cheap-retry.combine-sites-with-or}.
     * When not explicitly configured, we choose an "auto" default based on provider
     * capability and
     * query language (Hangul tends to route through Naver where boolean OR behavior
     * is less reliable).
     */
    private boolean shouldDetourCheapRetryCombineSitesWithOr(String finalQuery, List<String> sites) {
        if (sites == null || sites.size() < 2) {
            return false;
        }

        // If the operator explicitly set the property, honor it.
        try {
            if (this.env != null && this.env.containsProperty("guard.detour.cheap-retry.combine-sites-with-or")) {
                TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.mode", "explicit");
                return this.detourCheapRetryCombineSitesWithOr;
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.combineSitesModeTrace", ignore);
        }

        final boolean hasHangul = finalQuery != null && finalQuery.matches(".*[\\uAC00-\\uD7A3].*");
        boolean providerSupportsOr = false;
        try {
            providerSupportsOr = this.webSearchProvider != null && this.webSearchProvider.supportsSiteOrSyntax();
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("guard.detour.providerSupportsOr", ignore); providerSupportsOr = false;
        }

        final boolean auto = !hasHangul && providerSupportsOr;
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.mode", "auto");
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.auto", auto);
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.hasHangul", hasHangul);
        TraceStore.put("guard.detour.cheapRetry.combineSitesWithOr.providerSupportsOr", providerSupportsOr);
        return auto;
    }

    private String buildSiteOrClause(List<String> sites) {
        List<String> cleaned = sites.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .map(s -> s.startsWith("site:") ? s.substring("site:".length()) : s)
                .map(s -> "site:" + s)
                .distinct()
                .collect(Collectors.toList());

        if (cleaned.isEmpty()) {
            return "";
        }
        if (cleaned.size() == 1) {
            return cleaned.get(0);
        }
        return "(" + String.join(" OR ", cleaned) + ")";
    }

    private static String normalizeSiteHint(String site) {
        if (site == null) {
            return "";
        }
        String s = site.trim();
        if (s.startsWith("site:")) {
            s = s.substring("site:".length());
        }
        s = s.replace("https://", "").replace("http://", "");
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] parts = csv.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String s = p.trim();
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private String tryDetourCheapRetryLlmRegen(
            String finalQuery,
            String draftBeforeGuard,
            List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs,
            ChatModel model,
            ChatRequestDto llmReq,
            String breakerKey,
            QueryDomain queryDomain, boolean forceEscalate) {

        if (!this.detourCheapRetryRegenLlmEnabled) {
            if (!forceEscalate) {
                return null;
            }
            if (!this.detourForceEscalateRegenLlmEnabled) {
                TraceStore.put("guard.detour.cheapRetry.regen.skip", "forceEscalate_regen_disabled");
                return null;
            }
        }
        if (model == null || llmReq == null) {
            TraceStore.put("guard.detour.cheapRetry.regen.skip", "no_model");
            return null;
        }

        var gctx = GuardContextHolder.get();
        if (gctx != null && gctx.isStrikeMode()) {
            TraceStore.put("guard.detour.cheapRetry.regen.skip", "strike_mode");
            return null;
        }
        // Even when we forceEscalate over degradation, keep high-risk queries gated.
        if (forceEscalate && gctx != null && gctx.isHighRiskQuery()) {
            TraceStore.put("guard.detour.cheapRetry.regen.skip", "forceEscalate_but_high_risk_query");
            return null;
        }
        if (this.detourCheapRetryRegenLlmOnlyIfLowRisk && !forceEscalate) {
            if (queryDomain != null && !queryDomain.isLowRisk()) {
                TraceStore.put("guard.detour.cheapRetry.regen.skip", "non_low_risk_domain");
                return null;
            }
            if (gctx != null && gctx.isHighRiskQuery()) {
                TraceStore.put("guard.detour.cheapRetry.regen.skip", "high_risk_query");
                return null;
            }
        }

        String evidenceBlock = buildEvidenceListForPrompt(evidenceDocs, 8, 480);

        String system;
        String user;
        if (forceEscalate) {
            system = """
                    ??븷: ?뱀떊? '洹쇨굅 湲곕컲 ?듬? ?묒꽦湲??낅땲??
                    紐⑺몴: ?ъ슜??吏덈Ц?????'?듭떖 ?듬?'???묒꽦?섎릺, ?꾨옒 '洹쇨굅 紐⑸줉'???덈뒗 ?뺣낫留??ъ슜?섏꽭??

                    洹쒖튃:
                    - 洹쇨굅 紐⑸줉???녿뒗 ?덈줈???ъ떎/?섏튂/?좎쭨瑜?異붽??섏? 留덉꽭??
                    - 遺덊솗?ㅽ븯嫄곕굹 洹쇨굅媛 遺議깊븳 遺遺꾩? ??젣?섍굅??'洹쇨굅 遺議??쇰줈 ?쒖떆?섏꽭??
                    - 媛?臾몄옣/??ぉ ?앹뿉 洹쇨굅 踰덊샇瑜?[n] ?뺤떇?쇰줈 ?몃씪???몄슜?섏꽭?? (n? 洹쇨굅 紐⑸줉 踰덊샇)
                    - 異쒕젰? 媛꾧껐?섍쾶: (1) ?듭떖 ?듬? (2) 二쇱슂 ?ъ씤??遺덈┸)
                    - 理쒖쥌 ?듬?留?異쒕젰?섏꽭?? (?ㅻ챸/?ш낵/硫뷀? 肄붾찘??湲덉?)
                    """;

            user = """
                    ?ъ슜??吏덈Ц:
                    %s

                    李멸퀬 珥덉븞(?덈떎硫?:
                    %s

                    洹쇨굅 紐⑸줉:
                    %s

                    ?붿껌: ??洹쇨굅 紐⑸줉留??ъ슜??理쒖쥌 ?듬????묒꽦??二쇱꽭??
                    """.formatted(finalQuery, (draftBeforeGuard == null ? "" : draftBeforeGuard), evidenceBlock);
        } else {
            system = """
                    ??븷: ?뱀떊? '珥덉븞 ?몄쭛湲??낅땲??
                    紐⑺몴: ?꾨옒 '珥덉븞'??理쒕????좎??섎㈃?? ?쒓났??'洹쇨굅 紐⑸줉'留??ъ슜???ъ떎???뺤씤/?섏젙?섍퀬 ?몄슜???쎌엯?섏꽭??

                    洹쒖튃:
                    - 洹쇨굅 紐⑸줉???녿뒗 ?덈줈???ъ떎/?섏튂/?좎쭨瑜?異붽??섏? 留덉꽭??
                    - 遺덊솗?ㅽ븯嫄곕굹 洹쇨굅媛 遺議깊븳 遺遺꾩? ??젣?섍굅??'洹쇨굅 遺議??쇰줈 ?쒖떆?섏꽭??
                    - 媛?臾몄옣/??ぉ ?앹뿉 洹쇨굅 踰덊샇瑜?[n] ?뺤떇?쇰줈 ?몃씪???몄슜?섏꽭?? (n? 洹쇨굅 紐⑸줉 踰덊샇)
                    - 珥덉븞????臾몃떒/紐⑸줉 援ъ“瑜?媛?ν븳 ???좎??섏꽭??
                    - 理쒖쥌 ?듬?留?異쒕젰?섏꽭?? (?ㅻ챸/?ш낵/硫뷀? 肄붾찘??湲덉?)
                    """;

            user = """
                    ?ъ슜??吏덈Ц:
                    %s

                    珥덉븞:
                    %s

                    洹쇨굅 紐⑸줉:
                    %s

                    ?붿껌: ??珥덉븞??湲곕컲?쇰줈 理쒖쥌 ?듬????묒꽦??二쇱꽭??
                    """.formatted(finalQuery, (draftBeforeGuard == null ? "" : draftBeforeGuard), evidenceBlock);
        }

        List<ChatMessage> msgs = buildPostOrchestrationPromptMessages(
                "guard.detour.cheapRetry.regen", system, user);

        ChatRequestDto regenReq = llmReq.toBuilder()
                .temperature(this.detourCheapRetryRegenLlmTemperature)
                .maxTokens(this.detourCheapRetryRegenLlmMaxTokens)
                .build();

        if (nightmareBreaker != null) {
            try {
                nightmareBreaker.checkOpenOrThrow(breakerKey);
            } catch (Exception e) {
                TraceStore.put("guard.detour.cheapRetry.regen.skip", "nightmare_open");
                return null;
            }
        }

        try {
            TraceStore.inc("guard.detour.cheapRetry.regen.calls");
            long st = System.currentTimeMillis();
            ChatModel regenModel = model;
            if (dynamicChatModelFactory != null) {
                regenModel = dynamicChatModelFactory.lc(
                        regenReq.getModel(),
                        this.detourCheapRetryRegenLlmTemperature,
                        null,
                        this.detourCheapRetryRegenLlmMaxTokens);
            }
            String out = regenModel.chat(msgs).aiMessage().text();
            long latencyMs = System.currentTimeMillis() - st;
            TraceStore.put("guard.detour.cheapRetry.regen.ms", latencyMs);
            if (nightmareBreaker != null) {
                nightmareBreaker.recordSuccess(breakerKey, latencyMs);
            }
            if (out == null || out.isBlank()) {
                return null;
            }
            return out.trim();
        } catch (Exception e) {
            TraceStore.put("guard.detour.cheapRetry.regen.error", "cheap_retry_regen_failed");
            if (nightmareBreaker != null) {
                NightmareBreaker.FailureKind kind = NightmareBreaker.classify(e);
                nightmareBreaker.recordFailure(breakerKey, kind, e, "detour_cheap_retry_regen");
            }
            return null;
        }
    }

    private String buildEvidenceListForPrompt(List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs, int maxDocs,
            int maxSnippetChars) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (EvidenceAwareGuard.EvidenceDoc ev : evidenceDocs) {
            if (ev == null) {
                continue;
            }
            if (i > maxDocs) {
                break;
            }
            String id = ev.id() == null ? "" : ev.id().trim();
            String title = ev.title() == null ? "" : ev.title().trim();
            String snippet = ev.snippet() == null ? "" : ev.snippet().trim();
            if (snippet.length() > maxSnippetChars) {
                snippet = snippet.substring(0, maxSnippetChars) + "...";
            }

            sb.append('[').append(i).append("] ");
            sb.append(title.isBlank() ? "(no title)" : title);
            if (!id.isBlank()) {
                sb.append(" ??").append(id);
            }
            if (!snippet.isBlank()) {
                sb.append("\n    ").append(snippet);
            }
            sb.append("\n");
            i++;
        }
        return sb.toString();
    }

    private String chooseDetourRetrySite(String query, QueryDomain queryDomain) {
        java.util.List<String> sites = new java.util.ArrayList<>();

        // (1) 寃뚯엫 ?꾨찓?몄씠硫?officialDomains瑜??곗꽑 ?꾨낫濡?
        if (queryDomain == QueryDomain.GAME && officialDomainsCsv != null && !officialDomainsCsv.isBlank()) {
            for (String s : officialDomainsCsv.split(",")) {
                if (s == null) {
                    continue;
                }
                String t = s.trim();
                if (t.isBlank()) {
                    continue;
                }
                // site:???꾨찓?멸퉴吏留??덉슜 (path???쒓굅)
                int slash = t.indexOf('/');
                if (slash > 0) {
                    t = t.substring(0, slash);
                }
                // ?덈Т ?쇰컲?곸씤 ?꾨찓???뚯뀥? ?쒖쇅 (?꾩슂 ??config濡?異붽?)
                if (t.startsWith("youtube.") || t.equals("youtube.com") || t.equals("x.com")
                        || t.equals("twitter.com")) {
                    continue;
                }
                if (t.contains(".")) {
                    sites.add(t);
                }
            }
        }

        // (2) 湲곕낯 ?꾨낫(?ㅼ젙媛?
        for (String s : detourCheapRetrySiteHintsCsv.split(",")) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (!t.isBlank()) {
                // site: prefix媛 ?ㅼ뼱?ㅻ㈃ ?쒓굅
                if (t.startsWith("site:")) {
                    t = t.substring("site:".length());
                }
                sites.add(t);
            }
        }

        // 湲곕낯媛?蹂닿컯 (config媛 鍮꾩뿀嫄곕굹 ?ㅽ깉?먯씪 ??
        if (sites.isEmpty()) {
            sites.add("wikipedia.org");
            sites.add("namu.wiki");
            sites.add("hoyolab.com");
        }

        String q = (query == null ? "" : query);
        boolean hasHangul = q.matches(".*[\uAC00-\uD7A3].*");
        boolean genshin = q.contains("?먯떊") || q.toLowerCase().contains("genshin");

        // ?곗꽑?쒖쐞: (?먯떊/寃뚯엫) -> (?쒓?) -> (?곷Ц)
        if (genshin) {
            for (String s : sites) {
                if (s.contains("hoyolab") || s.contains("hoyoverse")) {
                    return s;
                }
            }
        }

        if (hasHangul) {
            for (String s : sites) {
                if (s.contains("namu.wiki")) {
                    return s;
                }
            }
        }

        for (String s : sites) {
            if (s.contains("wikipedia.org")) {
                return s;
            }
        }

        // fallback: 泥??꾨낫
        return sites.get(0);
    }

    /**
     * ?몄뀡 ID(Object) ??Long 蹂?? "123" ?뺥깭留?Long, 洹몄쇅??null.
     */

    /**
     * Extract URL from document metadata for EvidenceAwareGuard domain detection.
     * Falls back to index-based ID if no URL/source found.
     *
     * @param doc    RAG/web content document (may contain metadata such as "url" or
     *               "source")
     * @param index  fallback numeric index when metadata is missing
     * @param vector true if this is a vector/RAG document (uses "vector:" prefix)
     * @return actual URL if available, otherwise fallback index-based string
     */
    private static final Pattern URL_IN_TEXT = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);
    // Answer-side evidence marker: [W1], [V2], [D3]
    private static final Pattern EVIDENCE_MARKER_PATTERN = Pattern.compile("\\[(W|V|D)(\\d+)\\]");

    /**
     * Needle 2-pass merge: extract a canonical URL if possible; otherwise return
     * null.
     *
     * <p>
     * Unlike extractUrlOrFallback(...), this helper never returns an index-based
     * fallback
     * because we use it for URL-based de-duplication.
     */
    private static String needleExtractUrlOrNull(dev.langchain4j.rag.content.Content doc) {
        if (doc == null) {
            return null;
        }
        try {
            var segment = doc.textSegment();
            if (segment == null) {
                return null;
            }
            try {
                var metadata = segment.metadata();
                if (metadata != null) {
                    String url = metadata.getString("url");
                    if (url == null || url.isBlank()) {
                        url = metadata.getString("source");
                    }
                    if (url != null && !url.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(url);
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.sourceUrlMetadata", ignore); }

            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    String href = HtmlTextUtil.extractFirstHref(text);
                    if (href != null && !href.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(href);
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.sourceUrlHref", ignore); }

            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    java.util.regex.Matcher m = URL_IN_TEXT.matcher(text);
                    if (m.find()) {
                        return HtmlTextUtil.normalizeUrl(m.group(1));
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.sourceUrlRegex", ignore); }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.sourceUrlOuter", ignore); }
        return null;
    }

    private static java.util.List<String> safeHashList(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String value : values) {
            String hash = SafeRedactor.hash12(value);
            if (hash != null && !hash.isBlank()) {
                out.add(hash);
            }
            if (out.size() >= 16) {
                break;
            }
        }
        return java.util.List.copyOf(out);
    }

    private static void addPrefetchDiagnostics(Map<String, Object> metaHints,
                                               String query,
                                               java.util.List<String> snippets) {
        if (metaHints == null) {
            return;
        }
        metaHints.put("prefetch.web.queryHash", SafeRedactor.hashValue(query));
        metaHints.put("prefetch.web.queryLength", query == null ? 0 : query.length());
        metaHints.put("prefetch.web.queryTokenBucket", queryTokenBucket(query));
        metaHints.put("prefetch.web.snippetCount", snippets == null ? 0 : snippets.size());
        metaHints.put("prefetch.web.snippetHash12", safeHashList(snippets));
    }

    private static String queryTokenBucket(String query) {
        if (query == null || query.isBlank()) {
            return "0";
        }
        int tokens = query.trim().split("\\s+").length;
        if (tokens <= 4) {
            return "1-4";
        }
        if (tokens <= 12) {
            return "5-12";
        }
        if (tokens <= 32) {
            return "13-32";
        }
        return "33+";
    }

    /**
     * Needle 2-pass merge: combine (topDocs + needleDocs + fused) and deduplicate
     * by URL
     * (preferred) or short normalized text key. Order matters because we cap the
     * rerank candidates.
     */
    private static java.util.List<dev.langchain4j.rag.content.Content> mergeNeedleCandidates(
            java.util.List<dev.langchain4j.rag.content.Content> topDocs,
            java.util.List<dev.langchain4j.rag.content.Content> needleDocs,
            java.util.List<dev.langchain4j.rag.content.Content> fused,
            int maxPool) {

        java.util.LinkedHashMap<String, dev.langchain4j.rag.content.Content> uniq = new java.util.LinkedHashMap<>();
        int cap = maxPool > 0 ? maxPool : Integer.MAX_VALUE;
        int needleCount = needleDocs == null ? 0 : needleDocs.size();
        int reserve = needleCount <= 0 ? 0
                : Math.min(needleCount, Math.max(1, cap == Integer.MAX_VALUE ? needleCount : cap / 4));

        needleAddSome(uniq, topDocs, 1, cap);
        needleAddSome(uniq, needleDocs, reserve, cap);
        needleAddAll(uniq, topDocs, cap);
        needleAddAll(uniq, fused, cap);
        needleAddAll(uniq, needleDocs, cap);

        java.util.ArrayList<dev.langchain4j.rag.content.Content> out = new java.util.ArrayList<>(uniq.values());
        if (maxPool > 0 && out.size() > maxPool) {
            return out.subList(0, maxPool);
        }
        return out;
    }

    private static void needleAddAll(
            java.util.LinkedHashMap<String, dev.langchain4j.rag.content.Content> uniq,
            java.util.List<dev.langchain4j.rag.content.Content> list,
            int maxPool) {
        needleAddSome(uniq, list, Integer.MAX_VALUE, maxPool);
    }

    private static void needleAddSome(
            java.util.LinkedHashMap<String, dev.langchain4j.rag.content.Content> uniq,
            java.util.List<dev.langchain4j.rag.content.Content> list,
            int sourceLimit,
            int maxPool) {
        if (uniq == null || list == null || list.isEmpty()) {
            return;
        }
        if (sourceLimit == 0) {
            return;
        }
        int added = 0;
        for (dev.langchain4j.rag.content.Content c : list) {
            if (c == null || c.textSegment() == null || c.textSegment().text() == null) {
                continue;
            }
            String text = c.textSegment().text();
            if (text.isBlank()) {
                continue;
            }
            String url = needleExtractUrlOrNull(c);
            String key;
            if (url != null && !url.isBlank()) {
                key = "url:" + url;
            } else {
                String t = text.strip();
                if (t.length() > 160) {
                    t = t.substring(0, 160);
                }
                key = "txt:" + t;
            }
            if (!uniq.containsKey(key)) {
                uniq.put(key, c);
                added++;
            }
            if (sourceLimit > 0 && added >= sourceLimit) {
                return;
            }
            if (maxPool > 0 && uniq.size() >= maxPool) {
                return;
            }
        }
    }

    private static double retainedDropRatio(
            java.util.List<dev.langchain4j.rag.content.Content> before,
            java.util.List<dev.langchain4j.rag.content.Content> after) {
        if (before == null || before.isEmpty()) {
            return 0.0d;
        }
        if (after == null || after.isEmpty()) {
            return 1.0d;
        }
        java.util.HashSet<String> afterKeys = new java.util.HashSet<>();
        for (int i = 0; i < after.size(); i++) {
            String key = stableNeedleKey(after.get(i), i);
            if (key != null && !key.isBlank()) {
                afterKeys.add(key);
            }
        }
        int retained = 0;
        for (int i = 0; i < before.size(); i++) {
            String key = stableNeedleKey(before.get(i), i);
            if (key != null && afterKeys.contains(key)) {
                retained++;
            }
        }
        double ratio = 1.0d - (retained / (double) Math.max(1, before.size()));
        return Math.max(0.0d, Math.min(1.0d, ratio));
    }

    private static String stableNeedleKey(dev.langchain4j.rag.content.Content doc, int index) {
        String url = needleExtractUrlOrNull(doc);
        if (url != null && !url.isBlank()) {
            return "url:" + url;
        }
        try {
            String text = (doc != null && doc.textSegment() != null && doc.textSegment().text() != null)
                    ? doc.textSegment().text()
                    : "";
            text = text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (text.length() > 220) {
                text = text.substring(0, 220);
            }
            return text.isBlank() ? "idx:" + index : "txt:" + text;
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("evidence.keyForContent", ignore); return "idx:" + index;
        }
    }

    private static String extractUrlOrFallback(dev.langchain4j.rag.content.Content doc, int index, boolean vector) {
        String fallback = vector ? "vector:" + index : String.valueOf(index);
        if (doc == null) {
            return fallback;
        }
        try {
            var segment = doc.textSegment();
            if (segment == null) {
                return fallback;
            }
            try {
                var metadata = segment.metadata();
                if (metadata != null) {
                    // LangChain4j 1.0.1: Metadata#get(...) is not available ??use getString(...)
                    String url = metadata.getString("url");
                    if (url == null || url.isBlank()) {
                        url = metadata.getString("source");
                    }
                    if (url != null && !url.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(url);
                    }
                }
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("evidence.urlMetadata", ignore);
            }

            // 1.5) If body contains an HTML anchor ("- <a href=...>..."), prefer href.
            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    String href = HtmlTextUtil.extractFirstHref(text);
                    if (href != null && !href.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(href);
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("evidence.displayHref", ignore); }

            // 2) Fallback: parse URL directly from the text body/header
            // (e.g., "[title | provider | https://... ]")
            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    java.util.regex.Matcher m = URL_IN_TEXT.matcher(text);
                    if (m.find()) {
                        return HtmlTextUtil.normalizeUrl(m.group(1));
                    }
                }
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("evidence.urlText", ignore);
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("evidence.urlOuter", ignore);
        }
        return fallback;
    }

    private static java.util.Locale guessLocaleForNeedle(String text) {
        if (text == null || text.isBlank()) {
            return java.util.Locale.ROOT;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\uAC00' && ch <= '\uD7A3') {
                return java.util.Locale.KOREAN;
            }
        }
        return java.util.Locale.ENGLISH;
    }

    private static String extractHttpUrlOrNull(dev.langchain4j.rag.content.Content doc) {
        if (doc == null) {
            return null;
        }
        try {
            var seg = doc.textSegment();
            if (seg == null) {
                return null;
            }
            String raw = null;
            try {
                var md = seg.metadata();
                if (md != null) {
                    raw = md.getString("url");
                    if (raw == null || raw.isBlank()) {
                        raw = md.getString("source");
                    }
                }
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("extractHttpUrl.metadata", ignore); }
            if (raw == null || raw.isBlank()) {
                raw = seg.text();
            }
            if (raw == null || raw.isBlank()) {
                return null;
            }
            java.util.regex.Matcher m = URL_IN_TEXT.matcher(raw);
            if (m.find()) {
                return HtmlTextUtil.normalizeUrl(m.group(1));
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("extractHttpUrl.outer", ignore); }
        return null;
    }

    private static java.util.Set<String> collectNormalizedUrls(
            java.util.List<dev.langchain4j.rag.content.Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (dev.langchain4j.rag.content.Content c : docs) {
            String u = extractHttpUrlOrNull(c);
            if (u != null && !u.isBlank()) {
                out.add(u);
            }
        }
        return out;
    }

    private static java.util.List<dev.langchain4j.rag.content.Content> mergeDedupeByUrlThenText(
            java.util.List<dev.langchain4j.rag.content.Content> base,
            java.util.List<dev.langchain4j.rag.content.Content> extra,
            int cap) {

        java.util.LinkedHashMap<String, dev.langchain4j.rag.content.Content> merged = new java.util.LinkedHashMap<>();
        java.util.List<dev.langchain4j.rag.content.Content> first = (base == null ? java.util.List.of() : base);
        java.util.List<dev.langchain4j.rag.content.Content> second = (extra == null ? java.util.List.of() : extra);

        java.util.function.Function<dev.langchain4j.rag.content.Content, String> keyFn = c -> {
            String url = extractHttpUrlOrNull(c);
            if (url != null && !url.isBlank()) {
                return "u:" + url;
            }
            try {
                String t = (c != null && c.textSegment() != null && c.textSegment().text() != null)
                        ? c.textSegment().text()
                        : "";
                t = t.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
                if (t.length() > 220) {
                    t = t.substring(0, 220);
                }
                return "t:" + t;
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("content.dedupeKey", ignore); return "t:";
            }
        };

        for (dev.langchain4j.rag.content.Content c : first) {
            merged.putIfAbsent(keyFn.apply(c), c);
            if (cap > 0 && merged.size() >= cap) {
                break;
            }
        }
        if (cap <= 0 || merged.size() < cap) {
            for (dev.langchain4j.rag.content.Content c : second) {
                merged.putIfAbsent(keyFn.apply(c), c);
                if (cap > 0 && merged.size() >= cap) {
                    break;
                }
            }
        }
        return new java.util.ArrayList<>(merged.values());
    }

    private static int countDocsWithAnyUrlInSet(
            java.util.List<dev.langchain4j.rag.content.Content> docs,
            java.util.Set<String> urlSet) {
        if (docs == null || docs.isEmpty() || urlSet == null || urlSet.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (dev.langchain4j.rag.content.Content c : docs) {
            String u = extractHttpUrlOrNull(c);
            if (u != null && urlSet.contains(u)) {
                count++;
            }
        }
        return count;
    }

    /**
     * ?듬? ?띿뒪?몄뿉 ?ы븿??[W1]/[V2]/[D3] ?몄슜 留덉빱瑜??ㅼ젣 URL/?쒕ぉ 紐⑸줉?쇰줈 "李멸퀬 ?먮즺" ?뱀뀡??遺숈씤??
     *
     * <ul>
     * <li>留덉빱媛 ?대? ?덉?留?"李멸퀬 ?먮즺" 紐⑸줉???녿뒗 寃쎌슦(=?ㅼ??ㅽ듃?덉씠???꾨씫)瑜?蹂댁셿</li>
     * <li>留덉빱媛 ?녿뜑?쇰룄 evidence媛 ?덉쑝硫??곸쐞 1~3媛쒕? ?몄텧(怨쇰룄??湲몄씠 諛⑹?)</li>
     * <li>UI媛 留덉빱瑜?蹂꾨룄濡??뚮뜑留곹븯?붾씪???щ엺??吏곸젒 ?뺤씤 媛?ν븳 理쒖냼 異쒖쿂 留듯븨???쒓났</li>
     * </ul>
     */
    private static String appendEvidenceReferencesIfNeeded(
            String answer,
            java.util.List<dev.langchain4j.rag.content.Content> webDocs,
            java.util.List<dev.langchain4j.rag.content.Content> vectorDocs) {

        if (answer == null || answer.isBlank()) {
            return answer;
        }

        String trimmed = answer.trim();
        if (trimmed.isEmpty()) {
            return answer;
        }

        // Avoid double-append (idempotent)
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("### 李멸퀬 ?먮즺") || lower.contains("### sources")) {
            return answer;
        }

        boolean hasWeb = webDocs != null && !webDocs.isEmpty();
        boolean hasVec = vectorDocs != null && !vectorDocs.isEmpty();
        if (!hasWeb && !hasVec) {
            return answer;
        }

        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = EVIDENCE_MARKER_PATTERN.matcher(trimmed);
        while (m.find()) {
            used.add(m.group(1) + m.group(2));
        }

        int maxLines = used.isEmpty() ? 3 : 12;
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.Set<String> seenUrls = new java.util.LinkedHashSet<>();

        // Web sources
        if (hasWeb) {
            for (int i = 0; i < webDocs.size() && lines.size() < maxLines; i++) {
                int idx = i + 1;
                String marker = "W" + idx;
                if (!used.isEmpty() && !used.contains(marker)) {
                    continue;
                }

                dev.langchain4j.rag.content.Content d = webDocs.get(i);
                String url = extractUrlOrFallback(d, idx, false);
                if (url == null || url.isBlank() || !seenUrls.add(url)) {
                    continue;
                }

                String title = safeTitle(d);
                if (title != null) {
                    title = title.replaceAll("\\s+", " ").trim();
                }

                lines.add((title == null || title.isBlank())
                        ? ("- [" + marker + "] " + url)
                        : ("- [" + marker + "] " + title + " ??" + url));
            }
        }

        // Vector sources
        if (hasVec && lines.size() < maxLines) {
            for (int i = 0; i < vectorDocs.size() && lines.size() < maxLines; i++) {
                int idx = i + 1;
                String marker = "V" + idx;
                if (!used.isEmpty() && !used.contains(marker)) {
                    continue;
                }

                dev.langchain4j.rag.content.Content d = vectorDocs.get(i);
                String url = extractUrlOrFallback(d, idx, true);
                if (url == null || url.isBlank() || !seenUrls.add(url)) {
                    continue;
                }

                String title = safeTitle(d);
                if (title != null) {
                    title = title.replaceAll("\\s+", " ").trim();
                }

                lines.add((title == null || title.isBlank())
                        ? ("- [" + marker + "] " + url)
                        : ("- [" + marker + "] " + title + " ??" + url));
            }
        }

        // If markers exist but nothing matched (index mismatch etc.), show the top
        // source as a fail-soft reference.
        if (lines.isEmpty()) {
            if (hasWeb) {
                dev.langchain4j.rag.content.Content d = webDocs.get(0);
                String url = extractUrlOrFallback(d, 1, false);
                if (url != null && !url.isBlank()) {
                    String title = safeTitle(d);
                    if (title != null) {
                        title = title.replaceAll("\\s+", " ").trim();
                    }
                    lines.add((title == null || title.isBlank())
                            ? ("- [W1] " + url)
                            : ("- [W1] " + title + " ??" + url));
                }
            } else if (hasVec) {
                dev.langchain4j.rag.content.Content d = vectorDocs.get(0);
                String url = extractUrlOrFallback(d, 1, true);
                if (url != null && !url.isBlank()) {
                    String title = safeTitle(d);
                    if (title != null) {
                        title = title.replaceAll("\\s+", " ").trim();
                    }
                    lines.add((title == null || title.isBlank())
                            ? ("- [V1] " + url)
                            : ("- [V1] " + title + " ??" + url));
                }
            }
        }

        if (lines.isEmpty()) {
            return answer;
        }

        StringBuilder sb = new StringBuilder(trimmed);
        sb.append("\n\n---\n### 李멸퀬 ?먮즺\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static Long parseNumericSessionId(Object raw) {
        if (raw == null)
            return null;
        String s = String.valueOf(raw).trim();
        return s.matches("\\d+") ? Long.valueOf(s) : null;
    }

    // ------------------------------------------------------------------------

    private static String buildOutputPolicy(VerbosityProfile vp, List<String> sections) {
        // Output policies are now derived by the PromptOrchestrator. Returning an empty
        // string here delegates all guidance to the orchestrator and avoids manual
        // concatenation of policy instructions.
        return "";
    }

    // (??젣) loadMemoryContext(/* ... */) - MemoryHandler濡??쇱썝??

    /* ????????????????????????? BACKWARD-COMPAT ????????????????????????? */

    /**
     * (?명솚?? ?몃? 而⑦뀓?ㅽ듃 ?놁씠 ?ъ슜?섎뜕 湲곗〈 ?쒓렇?덉쿂
     */

    /* ---------- ?몄쓽 one-shot ---------- */
    public ChatResult ask(String userMsg) {
        // Internal convenience entry-point: do not force the DTO's default model.
        // Let routing/config pick the best available default.
        return continueChat(ChatRequestDto.builder()
                .message(userMsg)
                .model("")
                .build());
    }

    // MERGE_HOOK:PROJ_AGENT::ORCH_STAGE_POLICY_CLAMP_IMPL
    private void applyStagePolicyClamp(OrchestrationSignals sig,
            OrchestrationHints hints,
            java.util.Map<String, Object> metaHints,
            boolean nightmareMode,
            boolean auxHardDown) {
        if (stagePolicy == null || !stagePolicy.isEnabled() || sig == null || hints == null) {
            return;
        }

        String mode = sig.modeLabel();

        // Retrieval toggles
        hints.setAllowWeb(stagePolicy.isStageEnabled(OrchStageKeys.RETRIEVAL_WEB, mode, hints.isAllowWeb()));
        hints.setAllowRag(stagePolicy.isStageEnabled(OrchStageKeys.RETRIEVAL_VECTOR, mode, hints.isAllowRag()));

        // Hard gates remain hard: do NOT re-enable if nightmare/auxHardDown.
        boolean safe = !nightmareMode && !auxHardDown;
        hints.setEnableSelfAsk(
                safe && stagePolicy.isStageEnabled(OrchStageKeys.RETRIEVAL_SELF_ASK, mode, hints.isEnableSelfAsk()));
        hints.setEnableAnalyze(
                safe && stagePolicy.isStageEnabled(OrchStageKeys.RETRIEVAL_ANALYZE, mode, hints.isEnableAnalyze()));
        hints.setEnableCrossEncoder(safe
                && stagePolicy.isStageEnabled(OrchStageKeys.RERANK_CROSS_ENCODER, mode, hints.isEnableCrossEncoder()));

        if (metaHints != null) {
            metaHints.put("stagePolicy.enabled", "true");
            metaHints.put("stagePolicy.mode", mode);
            metaHints.put("allowWeb", String.valueOf(hints.isAllowWeb()));
            metaHints.put("allowRag", String.valueOf(hints.isAllowRag()));
            metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
            metaHints.put("enableAnalyze", String.valueOf(hints.isEnableAnalyze()));
            metaHints.put("enableCrossEncoder", String.valueOf(hints.isEnableCrossEncoder()));
        }
    }

    // 寃利??щ? 寃곗젙 ?ы띁
    // MERGE_HOOK:PROJ_AGENT::ORCH_VERIFY_STAGE_POLICY
    private boolean shouldVerify(String joinedContext, com.example.lms.dto.ChatRequestDto req,
            OrchestrationSignals sig) {
        boolean hasContext = org.springframework.util.StringUtils.hasText(joinedContext);
        Boolean flag = (req != null ? req.isUseVerification() : null); // null 媛??
        boolean enabled = (flag == null) ? verificationEnabled : Boolean.TRUE.equals(flag);
        if (!hasContext || !enabled) {
            return false;
        }

        // Skip in STRIKE/BYPASS to avoid extra expensive calls.
        if (sig != null && (sig.strikeMode() || sig.bypassMode())) {
            return false;
        }

        if (stagePolicy != null && stagePolicy.isEnabled()) {
            String mode = (sig != null ? sig.modeLabel() : "NORMAL");
            if (!stagePolicy.isStageEnabled(OrchStageKeys.VERIFY_FACT, mode, true)) {
                return false;
            }
        }

        return true;
    }

    /*
     * Legacy fallback pipelines (OpenAI-Java / alternate LC message builders) were
     * removed.
     */

    private static String truncate(String text, int max) {
        if (text == null || text.isBlank())
            return "";
        if (max <= 0)
            return "";
        if (text.length() <= max)
            return text;
        return text.substring(0, max);
    }

    /**
     * Apply plan overrides intended for the final answer stage only.
     *
     * <p>
     * We intentionally do NOT apply these overrides inside
     * {@link #callWithRetry(ChatModel, List, ChatRequestDto)} because that method
     * is also reused by creative/exploration steps.
     * </p>
     */
    private ChatRequestDto applyFinalAnswerSamplingOverrides(ChatRequestDto base) {
        var gctx = GuardContextHolder.get();
        if (gctx == null) {
            return base;
        }

        // Detect presence (null means no override). Fail-soft numeric parsing is done
        // inside GuardContext.
        Double ovTemp = gctx.planDouble("llm.answer.temperature");
        Double ovTopP = gctx.planDouble("llm.answer.top_p");
        if (ovTopP == null) {
            ovTopP = gctx.planDouble("llm.answer.topP");
        }
        Integer ovMaxTokens = null;
        if (gctx.getPlanOverride("llm.answer.max_tokens") != null) {
            int v = gctx.planInt("llm.answer.max_tokens", -1);
            if (v > 0)
                ovMaxTokens = v;
        } else if (gctx.getPlanOverride("llm.answer.maxTokens") != null) {
            int v = gctx.planInt("llm.answer.maxTokens", -1);
            if (v > 0)
                ovMaxTokens = v;
        }

        Double ovFreq = gctx.planDouble("llm.answer.frequency_penalty");
        Double ovPres = gctx.planDouble("llm.answer.presence_penalty");

        boolean changed = (ovTemp != null) || (ovTopP != null) || (ovMaxTokens != null)
                || (ovFreq != null) || (ovPres != null);
        if (!changed) {
            return base;
        }

        try {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            if (ovTemp != null)
                m.put("temperature", ovTemp);
            if (ovTopP != null)
                m.put("topP", ovTopP);
            if (ovMaxTokens != null)
                m.put("maxTokens", ovMaxTokens);
            if (ovFreq != null)
                m.put("frequencyPenalty", ovFreq);
            if (ovPres != null)
                m.put("presencePenalty", ovPres);
            if (!m.isEmpty()) {
                TraceStore.put("llm.answer.overrides", m);
                log.debug("[SamplingOverrides] answer overrides applied: {}", m);
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("llm.answerOverridesTrace", ignore);
        }

        ChatRequestDto.Builder b = (base != null) ? base.toBuilder() : ChatRequestDto.builder();
        if (ovTemp != null)
            b.temperature(ovTemp);
        if (ovTopP != null)
            b.topP(ovTopP);
        if (ovMaxTokens != null)
            b.maxTokens(ovMaxTokens);
        if (ovFreq != null)
            b.frequencyPenalty(ovFreq);
        if (ovPres != null)
            b.presencePenalty(ovPres);
        return b.build();
    }

    /**
     * Apply exploration-stage caps (e.g. clamp temperature) without affecting
     * final answer sampling.
     */
    private ChatRequestDto applyExploreSamplingCaps(ChatRequestDto base) {
        var gctx = GuardContextHolder.get();
        if (gctx == null) {
            return base;
        }
        Double cap = gctx.planDouble("llm.explore.temperature.max");
        if (cap == null) {
            return base;
        }
        double cur = (base != null && base.getTemperature() != null) ? base.getTemperature() : defaultTemp;
        if (cur <= cap) {
            return base;
        }
        ChatRequestDto.Builder b = (base != null) ? base.toBuilder() : ChatRequestDto.builder();
        b.temperature(cap);
        return b.build();
    }

    private int estimateChatMessageChars(List<? extends ChatMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return 0;
        }
        long total = 0L;
        for (ChatMessage msg : msgs) {
            if (msg != null) {
                total += String.valueOf(msg).length();
            }
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static int estimateTokensFromChars(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(chars / 4.0d));
    }

    private static int safeLen(String value) {
        return value == null ? 0 : value.length();
    }

    private static void emitRagPipelineEvent(
            String phase,
            String stage,
            String step,
            String component,
            String status,
            Map<String, Object> input,
            Map<String, Object> output,
            Map<String, Object> failure,
            Map<String, Object> control) {
        try {
            OrchEventEmitter.ragEvent(
                    "rag.pipeline",
                    phase,
                    stage,
                    step,
                    component,
                    status,
                    input,
                    output,
                    failure,
                    control);
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("rag.pipelineEvent", ignore);
        }
    }

    private void recordCostZone(String zone, String model, int inputChars, int attempt,
            boolean retryOrFallback, String where) {
        String safeZone = (zone == null || zone.isBlank()) ? "unknown" : zone.trim().replaceAll("[^a-zA-Z0-9_.-]+", "_");
        int chars = Math.max(0, inputChars);
        int tokens = estimateTokensFromChars(chars);
        try {
            TraceStore.put("cost.zone." + safeZone + ".inputChars", chars);
            TraceStore.put("cost.zone." + safeZone + ".approxInputTokens", tokens);
            TraceStore.put("cost.zone." + safeZone + ".modelHash", SafeRedactor.hashValue(model));
            TraceStore.put("cost.zone." + safeZone + ".attempt", attempt);
            TraceStore.put("cost.zone." + safeZone + ".retryOrFallback", retryOrFallback);
            TraceStore.inc("cost.zone." + safeZone + ".calls");
            TraceStore.append("cost.zone.events", Map.of(
                    "zone", safeZone,
                    "modelHash", SafeRedactor.hashValue(model),
                    "inputChars", chars,
                    "approxInputTokens", tokens,
                    "attempt", attempt,
                    "retryOrFallback", retryOrFallback));
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("cost.zoneTrace", ignore);
        }
        if (debugEventStore != null && tokens >= Math.max(1, costTraceWarnInputTokens)) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("zone", safeZone);
                data.put("modelHash", SafeRedactor.hashValue(model));
                data.put("inputChars", chars);
                data.put("approxInputTokens", tokens);
                data.put("attempt", attempt);
                data.put("retryOrFallback", retryOrFallback);
                debugEventStore.emit(
                        DebugProbeType.ORCHESTRATION,
                        DebugEventLevel.WARN,
                        "cost.zone." + safeZone + ".high_input",
                        "High estimated LLM input token burn",
                        where,
                        data,
                        null);
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("cost.zoneDebugEvent", ignore);
            }
        }
    }

    private String callWithRetry(ChatModel model,
            List<dev.langchain4j.data.message.ChatMessage> msgs,
            ChatRequestDto dto) {
        if (model == null) {
            throw new IllegalStateException("ChatModel is not configured");
        }

        String requestedModel = (dto != null && dto.getModel() != null) ? dto.getModel().trim() : null;
        if (requestedModel != null && requestedModel.isBlank()) {
            requestedModel = null;
        }
        requestedModel = localSafeModelOrNull(requestedModel, "requested");

        String routedModel = (modelRouter == null) ? null : modelRouter.resolveModelName(model);
        if (routedModel != null) {
            routedModel = routedModel.trim();
            if (routedModel.isBlank() || "unknown".equalsIgnoreCase(routedModel)) {
                routedModel = null;
            }
            // Guard: some routers fall back to class simpleName. Treat it as invalid.
            if (routedModel != null) {
                String cls = model.getClass().getSimpleName();
                if (routedModel.equals(cls)) {
                    routedModel = null;
                }
                if (routedModel != null && !routedModel.isEmpty()
                        && Character.isUpperCase(routedModel.charAt(0))
                        && routedModel.matches("[A-Za-z0-9_$]+")) {
                    routedModel = null;
                }
            }
        }
        routedModel = localSafeModelOrNull(routedModel, "routed");

        String resolved = requestedModel;
        if (resolved == null || resolved.isBlank()) {
            resolved = routedModel;
        }
        if (resolved == null || resolved.isBlank()) {
            resolved = defaultModel;
        }
        if (resolved == null || resolved.isBlank()) {
            resolved = ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL;
        }
        final int callTimeoutSeconds = dto == null
                ? llmTimeoutSeconds
                : RequestedModelTimeoutPolicy.timeoutSeconds(dto.getModel(), resolved, llmTimeoutSeconds,
                        requestedModelTimeoutSeconds);
        if (isLocalProvider() && ModelCapabilities.isRemoteLookingModelId(resolved)
                && (dynamicChatModelFactory == null || !dynamicChatModelFactory.canServeQuietly(resolved))) {
            log.warn("[AWX2AF2][model-policy] ignored {} modelHash={} provider={} reason=local_provider",
                    "resolved", SafeRedactor.hashValue(resolved), llmProvider);
            try {
                TraceStore.put("llm.model.policy.blocked", true);
                TraceStore.put("llm.model.policy.blocked.stage", "resolved");
                TraceStore.put("llm.model.policy.blocked.modelHash", SafeRedactor.hashValue(resolved));
                TraceStore.put("llm.model.policy.blocked.provider", safeProviderName());
                TraceStore.put("llm.model.policy.blocked.reason", "local_provider_remote_model");
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.modelPolicyBlockedResolved", ignore); }
            resolved = localFallbackModelId();
        }

        try {
            TraceStore.put("llm.call.modelHash", SafeRedactor.hashValue(resolved));
            TraceStore.put("llm.call.inputChars", estimateChatMessageChars(msgs));
            TraceStore.put("llm.call.approxInputTokens", estimateTokensFromChars(estimateChatMessageChars(msgs)));
            TraceStore.put("llm.call.maxAttempts", llmMaxAttempts + 1);
            if (requestedModel != null) {
                TraceStore.put("llm.call.model.requestedHash", SafeRedactor.hashValue(requestedModel));
            }
            if (routedModel != null) {
                TraceStore.put("llm.call.model.routedHash", SafeRedactor.hashValue(routedModel));
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.callModelTrace", ignore); }

        ChatModel modelForCall = model;
        if (dto != null && dynamicChatModelFactory != null) {
            try {
                modelForCall = dynamicChatModelFactory.lcWithTimeout(
                        resolved,
                        dto.getTemperature(),
                        dto.getTopP(),
                        dto.getFrequencyPenalty(),
                        dto.getPresencePenalty(),
                        dto.getMaxTokens(),
                        callTimeoutSeconds);
            } catch (IllegalStateException guard) {
                // Fail-soft: ProviderGuard(?? OpenAI ???놁쓬)濡??숈쟻 ?ъ깮?깆씠 ?ㅽ뙣?섎㈃ ?먮낯 紐⑤뜽 ?좎?
                log.warn("[ChatWorkflow] dynamic model rebuild blocked: reason={} originalModelHash={} originalModelLength={}",
                        SafeRedactor.safeMessage(guard.getMessage(), 180), SafeRedactor.hashValue(resolved), resolved == null ? 0 : resolved.length());
                modelForCall = model;
            }
        }

        // Endpoint-compat preflight: completion-only models shouldn't hit
        // /v1/chat/completions.
        if (openAiFallbackToCompletions && OpenAiEndpointCompatibility.isLikelyCompletionsOnlyModelId(resolved)) {
            String baseUrlUsed = safeTraceString("llm.factory.baseUrl");
            boolean local = safeTraceBool("llm.factory.local");
            try {
                TraceStore.put("llm.endpoint.compat.preflight", "completions");
                log.warn("[LLM_ENDPOINT_COMPAT] preflight modelHash={} -> /v1/completions", SafeRedactor.hashValue(resolved));
                recordCostZone(
                        "llm.endpoint_preflight",
                        resolved,
                        estimateChatMessageChars(msgs),
                        1,
                        true,
                        "ChatWorkflow.endpointCompatPreflight");
                String out = callCompletionsFallback(resolved, msgs, dto, baseUrlUsed, local);
                recordModelSuccess(resolved, OpenAiEndpointCompatibility.Endpoint.COMPLETIONS);
                return out;
            } catch (Exception pre) {
                recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.COMPLETIONS, reasonFromException(pre));
                log.warn("[LLM_ENDPOINT_COMPAT] preflight /v1/completions failed; will try chat. modelHash={} err={}",
                        SafeRedactor.hashValue(resolved), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(pre)), String.valueOf(pre).length()));
            }
        }

        Throwable last = null;

        // Retry budget: prevent pathological timeout accumulation.
        final long startedAtMs = System.currentTimeMillis();
        boolean ep = false; // ?꾩떆 蹂?? final ?ы븷???ㅻ쪟 諛⑹?
        try {
            Object p = TraceStore.get("chat.evidence.present");
            Object c = TraceStore.get("chat.evidence.count");
            boolean pBool = p != null && Boolean.parseBoolean(String.valueOf(p));
            int cInt = 0;
            if (c != null) {
                try {
                    cInt = Integer.parseInt(String.valueOf(c));
                } catch (NumberFormatException ignore) {
                    ChatWorkflowTraceSuppressions.traceSuppressed("chat.evidenceCountParse", ignore); cInt = 0;
                }
            }
            ep = pBool || cInt > 0;
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("chat.evidencePresentTrace", ignore);
        }
        final boolean evidencePresent = ep;
        final long budgetMs = (llmRetryMaxTotalMs > 0)
                ? llmRetryMaxTotalMs
                : Math.max(1500L, (long) callTimeoutSeconds * 1000L + llmBackoffMs + 250L);
        int timeoutHits = 0, upstream5xxHits = 0, blankHits = 0;
        boolean selfHealed = false;
        boolean modelHealed = false;
        int callInputChars = estimateChatMessageChars(msgs);
        for (int attempt = 0; attempt <= llmMaxAttempts; attempt++) {
            try {
                recordCostZone(
                        "llm.draft",
                        resolved,
                        callInputChars,
                        attempt + 1,
                        attempt > 0,
                        "ChatWorkflow.callWithRetry");
                dev.langchain4j.data.message.AiMessage ai = TimedChatModelCaller.chat(
                        modelForCall,
                        msgs,
                        Duration.ofSeconds(callTimeoutSeconds),
                        "chat_draft",
                        resolved);
                String out = ai == null ? "" : (ai.text() == null ? "" : ai.text());
                recordModelSuccess(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);
                return out;
            } catch (Exception e) {
                // Model-not-found / endpoint mismatch??鍮꾩씪?쒖쟻 ??利됱떆 fail-fast (+ endpoint-compat
                // failover)
                dev.langchain4j.exception.ModelNotFoundException mnfe = unwrapModelNotFound(e);
                String hintMsg = (mnfe != null ? mnfe.getMessage() : e.getMessage());

                boolean hintCompletions = OpenAiEndpointCompatibility.isChatEndpointMismatchMessage(hintMsg);
                boolean hintResponses = OpenAiEndpointCompatibility.isResponsesEndpointSuggestionMessage(hintMsg);
                boolean chatEndpointMissing = OpenAiEndpointCompatibility
                        .isChatCompletionsEndpointMissingMessage(hintMsg);

                if (hintCompletions || hintResponses || chatEndpointMissing) {
                    recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                            chatEndpointMissing ? "chat_endpoint_missing" : "endpoint_mismatch");
                    String summary = OpenAiEndpointCompatibility.summarizeForLog(hintMsg, 240);
                    String baseUrlUsed = safeTraceString("llm.factory.baseUrl");
                    boolean local = safeTraceBool("llm.factory.local");

                    try {
                        TraceStore.put("llm.endpoint.compat.mismatch", true);
                        TraceStore.put("llm.endpoint.compat.hint",
                                hintCompletions ? "completions"
                                        : (hintResponses ? "responses" : "chat_endpoint_missing"));
                        TraceStore.put("llm.endpoint.compat.detail", summary);
                        if (baseUrlUsed != null) {
                            TraceStore.put("llm.endpoint.compat.baseUrlHost", hostOf(baseUrlUsed));
                            TraceStore.put("llm.endpoint.compat.baseUrlHash", SafeRedactor.hashValue(baseUrlUsed));
                        }
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatMismatchTrace", ignore); }

                    String hint = hintCompletions ? "/v1/completions"
                            : (hintResponses ? "/v1/responses" : "missing /v1/chat/completions");
                    log.warn("[LLM_ENDPOINT_COMPAT] detected modelHash={} primary=/v1/chat/completions hint={} detail={}{}",
                            SafeRedactor.hashValue(resolved), hint, summary, LogCorrelation.suffix());

                    String out = hintCompletions
                            ? tryEndpointCompatFallback(resolved, msgs, dto, baseUrlUsed, local, "completions",
                                    "responses")
                            : tryEndpointCompatFallback(resolved, msgs, dto, baseUrlUsed, local, "responses",
                                    "completions");

                    if (out != null) {
                        return out;
                    }

                    String attempted = safeTraceString("llm.endpoint.compat.attempted");
                    String userMsg = OpenAiEndpointCompatibility.userFacingEndpointMismatch(resolved);
                    if (attempted != null && !attempted.isBlank()) {
                        userMsg = userMsg + "\n- ?쒕룄: " + attempted;
                    }
                    userMsg = userMsg + "\n- 愿由ъ옄: " + LogCorrelation.suffix().trim();

                    throw new LlmConfigurationException(
                            "MODEL_ENDPOINT_MISMATCH",
                            userMsg,
                            resolved,
                            "/v1/chat/completions",
                            (mnfe != null ? mnfe : e));
                }

                if (mnfe != null) {
                    recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                            "model_not_found");
                    String raw = mnfe.getMessage();
                    String summary = OpenAiEndpointCompatibility.summarizeForLog(raw, 240);
                    log.warn("[LLM] non-retryable (model not found). modelHash={} detail={}{}",
                            SafeRedactor.hashValue(resolved), SafeRedactor.safeMessage(summary, 180), LogCorrelation.suffix());
                    String userMsg = OpenAiEndpointCompatibility.userFacingModelNotFound(resolved)
                            + "\n- 愿由ъ옄: " + LogCorrelation.suffix().trim();
                    throw new LlmConfigurationException(
                            "MODEL_NOT_FOUND",
                            userMsg,
                            resolved,
                            null,
                            mnfe);
                }

                last = e;
                log.warn("[LLM] attempt {}/{} failed: {}", attempt + 1, llmMaxAttempts + 1, String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));

                // Self-heal: unsupported param 媛먯? ??max_tokens / temperature ?? ?덉쟾 ?뚮씪誘명꽣濡?1???ъ떆??
                boolean unsupportedMaxTokens = OpenAiTokenParamCompat.isUnsupportedMaxTokens(e);
                boolean unsupportedSampling = OpenAiTokenParamCompat.isUnsupportedSampling(e);
                if (!selfHealed && (unsupportedMaxTokens || unsupportedSampling)) {
                    selfHealed = true;
                    try {
                        String healModelId = resolved;
                        if (healModelId == null || healModelId.isBlank()) {
                            healModelId = (defaultModel == null || defaultModel.isBlank()) ? ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL
                                    : defaultModel;
                        }
                        if (dynamicChatModelFactory == null) {
                            throw new IllegalStateException("dynamicChatModelFactory is null");
                        }

                        // ?덉쟾 ?뚮씪誘명꽣濡??щ퉴?? temperature/topP/maxTokens 紐⑤몢 null(?쒕쾭 湲곕낯媛??ъ슜)
                        ChatModel healed = dynamicChatModelFactory.lcWithTimeout(
                                healModelId, null, null, null, null, null, callTimeoutSeconds);
                        recordCostZone(
                                "llm.self_heal",
                                healModelId,
                                callInputChars,
                                attempt + 1,
                                true,
                                "ChatWorkflow.callWithRetry");
                        dev.langchain4j.data.message.AiMessage ai = TimedChatModelCaller.chat(
                                healed,
                                msgs,
                                Duration.ofSeconds(callTimeoutSeconds),
                                "chat_self_heal",
                                healModelId);
                        String out = ai == null ? "" : (ai.text() == null ? "" : ai.text());
                        recordModelSuccess(healModelId, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);
                        return out;
                    } catch (Exception healEx) {
                        recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS,
                                reasonFromException(healEx));
                        last = healEx;
                        log.warn("[LLM] self-heal retry failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(healEx)), String.valueOf(healEx).length()));
                    }
                }

                // Non-retryable / configuration errors should stop the retry loop early.
                com.example.lms.llm.LlmErrorClassifier.Result cls = com.example.lms.llm.LlmErrorClassifier.classify(e);
                try {
                    TraceStore.put("llm.error.code", cls.code());
                    TraceStore.put("llm.error.retryable", cls.retryable());
                    if (cls.statusCode() != null) {
                        TraceStore.put("llm.error.statusCode", cls.statusCode());
                    }
                    TraceStore.put("llm.error.message", cls.shortMessage());
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.errorClassifierTrace", ignore); }

                boolean isTimeout = "TIMEOUT".equals(cls.code()), isUpstream5xx = "UPSTREAM_5XX".equals(cls.code()), isBlankResponse = "BLANK_RESPONSE".equals(cls.code());
                if (isTimeout) { recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "timeout"); timeoutHits++; }
                if (isUpstream5xx) { recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "upstream_5xx"); upstream5xxHits++; }
                if (isBlankResponse) { recordModelFailure(resolved, OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS, "blank_response"); blankHits++; }

                int minFastBailTimeoutHits = Math.max(1, llmFastBailoutMinTimeoutHitsWithEvidence);
                boolean fastBailTimeout = isTimeout && ((!evidencePresent && timeoutHits >= 1)
                        || (evidencePresent && llmFastBailoutOnTimeoutWithEvidence
                                && timeoutHits >= minFastBailTimeoutHits));
                if (fastBailTimeout) { log.warn("[LLM_FAST_BAIL_TIMEOUT] evidencePresent={} timeoutHits={} attempt={}/{}{}", evidencePresent, timeoutHits, attempt, llmMaxAttempts, LogCorrelation.suffix()); throw new LlmFastBailoutException("LLM timeout fast-bail", e, timeoutHits, attempt, llmMaxAttempts); }
                if (isUpstream5xx && !evidencePresent && upstream5xxHits >= 1) { log.warn("[LLM_FAST_BAIL_UPSTREAM_5XX] evidencePresent={} upstream5xxHits={} attempt={}/{}{}", evidencePresent, upstream5xxHits, attempt, llmMaxAttempts, LogCorrelation.suffix()); throw new LlmFastBailoutException("LLM upstream fast-bail", e, upstream5xxHits, attempt, llmMaxAttempts); }
                if (isBlankResponse && !evidencePresent && blankHits >= 1) { log.warn("[LLM_FAST_BAIL_BLANK_RESPONSE] evidencePresent={} blankHits={} attempt={}/{}{}", evidencePresent, blankHits, attempt, llmMaxAttempts, LogCorrelation.suffix()); throw new LlmFastBailoutException("LLM blank response fast-bail", e, blankHits, attempt, llmMaxAttempts); }

                long elapsedMs = System.currentTimeMillis() - startedAtMs;
                if (budgetMs > 0 && elapsedMs > budgetMs) {
                    LlmRetryBudgetTrace.emit(elapsedMs, budgetMs, attempt, llmMaxAttempts, cls.code()); log.warn("[LLM_RETRY_BUDGET_EXCEEDED] elapsedMs={} budgetMs={} attempt={}/{} code={}{}",
                            elapsedMs, budgetMs, attempt, llmMaxAttempts, cls.code(), LogCorrelation.suffix());
                    throw new RuntimeException("LLM retry budget exceeded", e);
                }

                // Self-heal for "model is required": try once with configured default model +
                // safe params.
                if (!modelHealed && "MODEL_REQUIRED".equals(cls.code()) && dynamicChatModelFactory != null) {
                    modelHealed = true;
                    try {
                        String fallbackModel = (defaultModel == null || defaultModel.isBlank()) ? ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL
                                : defaultModel;
                        ChatModel healed = dynamicChatModelFactory.lcWithTimeout(
                                fallbackModel, null, null, null, null, null, callTimeoutSeconds);
                        recordCostZone(
                                "llm.model_required_heal",
                                fallbackModel,
                                callInputChars,
                                attempt + 1,
                                true,
                                "ChatWorkflow.callWithRetry");
                        dev.langchain4j.data.message.AiMessage ai = TimedChatModelCaller.chat(
                                healed,
                                msgs,
                                Duration.ofSeconds(callTimeoutSeconds),
                                "chat_model_required_heal",
                                fallbackModel);
                        return ai == null ? "" : (ai.text() == null ? "" : ai.text());
                    } catch (Exception healEx) {
                        last = healEx;
                        log.warn("[LLM] model-required self-heal retry failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(healEx)), String.valueOf(healEx).length()));
                    }
                }

                if (!cls.retryable()) {
                    log.warn("[LLM] non-retryable ({}). stop retries: {}", cls.code(), cls.shortMessage());
                    throw new RuntimeException("LLM non-retryable: " + cls.code() + " - " + cls.shortMessage(), e);
                }
                if (attempt >= llmMaxAttempts) {
                    break;
                }
                try {
                    Thread.sleep(llmBackoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("LLM call interrupted", ie);
                }
            }
        }

        throw new RuntimeException("LLM unavailable after retries", last);
    }

    private static List<Content> filterPromptEligibleSelfAsk(List<Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return docs;
        }
        List<Content> out = new ArrayList<>(docs.size());
        int gated = 0;
        int kept = 0;
        int removed = 0;
        for (Content doc : docs) {
            if (!isGrandasManagedPromptCandidate(doc)) {
                out.add(doc);
                kept++;
                continue;
            }
            gated++;
            if (isPromptEligible(doc)) {
                out.add(doc);
                kept++;
            } else {
                removed++;
            }
        }
        try {
            TraceStore.put("selfask.laneGate.promptFilter.gatedCount", gated);
            TraceStore.put("selfask.laneGate.promptFilter.keptCount", kept);
            TraceStore.put("selfask.laneGate.promptFilter.removedCount", removed);
            TraceStore.put("selfask.laneGate.promptEligibleCount", Math.max(0, gated - removed));
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("selfask.promptFilterTrace", ignore);
        }
        return removed == 0 ? docs : out;
    }

    private static boolean isGrandasManagedPromptCandidate(Content doc) {
        if (doc == null || doc.textSegment() == null) {
            return false;
        }
        Map<String, Object> metadata = com.example.lms.util.MetadataUtils.toMap(doc.textSegment().metadata());
        if (metadata.containsKey("selfask_lane_gate_enabled")) {
            return true;
        }
        if (metadata.containsKey("grandas_managed")) {
            return true;
        }
        String stage = String.valueOf(metadata.getOrDefault("retrieval_stage", ""));
        return stage.startsWith("selfask") && metadata.containsKey("promptEligible");
    }

    private static boolean isPromptEligible(Content doc) {
        if (doc == null || doc.textSegment() == null) {
            return false;
        }
        Map<String, Object> metadata = com.example.lms.util.MetadataUtils.toMap(doc.textSegment().metadata());
        Object value = metadata.get("promptEligible");
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String safeTraceString(String key) {
        try {
            Object v = TraceStore.get(key);
            return (v == null) ? null : String.valueOf(v);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("trace.safeString", ignore); return null;
        }
    }

    private static String hostOf(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("trace.hostOf", ignore); return null;
        }
    }

    private static String learningDegradedReason(List<LearningSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return "";
        }
        String degradedKind = LearningSignalKind.CONTEXT_DEGRADED.value();
        for (LearningSignal signal : signals) {
            if (signal != null && degradedKind.equals(signal.kind())) {
                return signal.value() == null ? "" : signal.value();
            }
        }
        return "";
    }

    private static Double safeTraceDouble(String key) {
        try {
            Object v = TraceStore.get(key);
            if (v == null) {
                return null;
            }
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            String s = String.valueOf(v).trim();
            return s.isBlank() ? null : Double.valueOf(s);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("trace.safeDouble", ignore); return null;
        }
    }

    private static boolean safeTraceBool(String key) {
        try {
            Object v = TraceStore.get(key);
            if (v == null)
                return false;
            if (v instanceof Boolean b)
                return b;
            return Boolean.parseBoolean(String.valueOf(v));
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("trace.safeBool", ignore); return false;
        }
    }

    private boolean isLocalProvider() {
        return "local".equalsIgnoreCase(safeProviderName());
    }

    private String safeProviderName() {
        return llmProvider == null ? "" : llmProvider.trim();
    }

    private String localSafeModelOrNull(String modelId, String stage) {
        if (!isLocalProvider() || modelId == null || modelId.isBlank()) {
            return modelId;
        }
        if (!ModelCapabilities.isRemoteLookingModelId(modelId)) return modelId;
        if (dynamicChatModelFactory != null && dynamicChatModelFactory.canServeQuietly(modelId)) return modelId;
        log.warn("[AWX2AF2][model-policy] ignored {} modelHash={} provider={} reason=local_provider",
                stage, SafeRedactor.hashValue(modelId), safeProviderName());
        try {
            TraceStore.put("llm.model.policy.blocked", true);
            TraceStore.put("llm.model.policy.blocked.stage", stage);
            TraceStore.put("llm.model.policy.blocked.modelHash", SafeRedactor.hashValue(modelId));
            TraceStore.put("llm.model.policy.blocked.provider", safeProviderName());
            TraceStore.put("llm.model.policy.blocked.reason", "local_provider_remote_model");
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.modelPolicyBlockedHelper", ignore); }
        return null;
    }

    private String localFallbackModelId() {
        String fallback = (defaultModel == null || defaultModel.isBlank()) ? ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL : defaultModel.trim();
        return ModelCapabilities.isRemoteLookingModelId(fallback) ? ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL : fallback;
    }

    /**
     * Endpoint-compat failover ladder.
     *
     * <p>
     * Order is controlled by the caller (hint-first). Each endpoint is attempted at
     * most once, and
     * failures are recorded into {@link TraceStore} to avoid "silent" no-output
     * states.
     * </p>
     */
    private String tryEndpointCompatFallback(String modelId,
            List<ChatMessage> msgs,
            ChatRequestDto dto,
            String baseUrlUsed,
            boolean local,
            String... order) {
        ArrayList<String> attempted = new ArrayList<>();
        if (order == null || order.length == 0) {
            return null;
        }

        for (String ep : order) {
            if (ep == null) {
                continue;
            }
            String epl = ep.trim().toLowerCase(java.util.Locale.ROOT);
            if (epl.isBlank()) {
                continue;
            }

            if ("responses".equals(epl) && !openAiFallbackToResponses) {
                continue;
            }
            if ("completions".equals(epl) && !openAiFallbackToCompletions) {
                continue;
            }

            attempted.add(epl);
            try {
                TraceStore.put("llm.endpoint.compat.attempted", String.join("->", attempted));
                TraceStore.put("llm.endpoint.compat.attempt.now", epl);
            } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatAttemptedTrace", ignore); }

            try {
                if ("responses".equals(epl)) {
                    log.warn("[LLM_ENDPOINT_COMPAT] trying /v1/responses modelHash={}{}",
                            SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                    String out = callResponsesFallback(modelId, msgs, dto, baseUrlUsed, local);
                    try {
                        TraceStore.put("llm.endpoint.compat.healedBy", "responses");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatHealedResponses", ignore); }
                    recordModelSuccess(modelId, OpenAiEndpointCompatibility.Endpoint.RESPONSES);
                    log.warn("[LLM_ENDPOINT_COMPAT] healed via /v1/responses modelHash={}{}",
                            SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                    return out;
                }
                if ("completions".equals(epl)) {
                    log.warn("[LLM_ENDPOINT_COMPAT] trying /v1/completions modelHash={}{}",
                            SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                    String out = callCompletionsFallback(modelId, msgs, dto, baseUrlUsed, local);
                    try {
                        TraceStore.put("llm.endpoint.compat.healedBy", "completions");
                    } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatHealedCompletions", ignore); }
                    recordModelSuccess(modelId, OpenAiEndpointCompatibility.Endpoint.COMPLETIONS);
                    log.warn("[LLM_ENDPOINT_COMPAT] healed via /v1/completions modelHash={}{}",
                            SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                    return out;
                }
            } catch (Exception ex) {
                ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatAttempt", ex); recordEndpointCompatFailure(epl, modelId, ex);
            }
        }

        try {
            TraceStore.put("llm.endpoint.compat.attempted", String.join("->", attempted));
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.endpointCompatAttemptedFinal", ignore); }
        return null;
    }

    private void recordEndpointCompatFailure(String endpoint, String modelId, Exception ex) {
        try {
            String ep = (endpoint == null ? "unknown" : endpoint);
            String msg = OpenAiEndpointCompatibility.summarizeForLog(ex.getMessage(), 260);
            TraceStore.put("llm.endpoint.compat." + ep + ".errorHash", SafeRedactor.hashValue(msg));
            TraceStore.put("llm.endpoint.compat." + ep + ".errorLength", msg == null ? 0 : msg.length());
            recordModelFailure(modelId, endpointFromString(ep), reasonFromException(ex));

            if (ex instanceof WebClientResponseException w) {
                TraceStore.put("llm.endpoint.compat." + ep + ".status", w.getRawStatusCode());
                String rawBody = w.getResponseBodyAsString();
                TraceStore.put("llm.endpoint.compat." + ep + ".bodyHash", SafeRedactor.hashValue(rawBody));
                TraceStore.put("llm.endpoint.compat." + ep + ".bodyLength", rawBody == null ? 0 : rawBody.length());
                String ra = w.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                if (ra != null && !ra.isBlank()) {
                    TraceStore.put("llm.endpoint.compat." + ep + ".retryAfter", ra);
                }

                if (openAiEndpointCompatDebug || log.isDebugEnabled()) {
                    log.warn("[LLM_ENDPOINT_COMPAT] /v1/{} failed status={} modelHash={} bodyHash={} bodyLength={}{}",
                            ep, w.getRawStatusCode(), SafeRedactor.hashValue(modelId), SafeRedactor.hashValue(rawBody), rawBody == null ? 0 : rawBody.length(), LogCorrelation.suffix());
                } else {
                    log.warn("[LLM_ENDPOINT_COMPAT] /v1/{} failed status={} modelHash={}{}",
                            ep, w.getRawStatusCode(), SafeRedactor.hashValue(modelId), LogCorrelation.suffix());
                }
                return;
            }

            log.warn("[LLM_ENDPOINT_COMPAT] /v1/{} failed modelHash={} err={}{}",
                    ep, SafeRedactor.hashValue(modelId), SafeRedactor.safeMessage(msg, 180), LogCorrelation.suffix());
        } catch (Exception ignore) {
            // Do not let diagnostics crash the workflow
            log.warn("[LLM_ENDPOINT_COMPAT] fallback failed modelHash={} ex={}{}",
                    SafeRedactor.hashValue(modelId), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()), LogCorrelation.suffix());
        }
    }

    /**
     * When a selected model is not compatible with /v1/chat/completions, try
     * /v1/completions once
     * to avoid the "silent/empty" failure mode. This is a best-effort compatibility
     * path.
     */
    private String callCompletionsFallback(String modelId, List<ChatMessage> msgs, ChatRequestDto dto,
            String baseUrlUsed, boolean local) throws Exception {
        String base = OpenAiCompatBaseUrl.sanitize(
                (baseUrlUsed != null && !baseUrlUsed.isBlank())
                        ? baseUrlUsed
                        : env.getProperty("llm.base-url-openai",
                                env.getProperty("llm.openai.base-url", "https://api.openai.com/v1")));

        String apiKey = local ? keyResolver.resolveLocalApiKeyStrict() : keyResolver.resolveOpenAiApiKeyStrict();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key for completions fallback (local=" + local + ")");
        }

        String completionsFallbackPrompt = OpenAiEndpointCompatibility.toCompletionsPrompt(msgs);

        Map<String, Object> payload = OpenAiEndpointCompatibility.completionsPayload(
                modelId,
                completionsFallbackPrompt,
                dto != null ? dto.getMaxTokens() : null,
                dto != null ? dto.getTemperature() : null,
                dto != null ? dto.getTopP() : null,
                dto != null ? dto.getFrequencyPenalty() : null,
                dto != null ? dto.getPresencePenalty() : null);

        String url = base + "/completions";

        String json = openaiWebClient
                .post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(llmTimeoutSeconds));

        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Empty response from /v1/completions fallback");
        }

        JsonNode root = OPENAI_COMPAT_MAPPER.readTree(json);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode c0 = choices.get(0);
            String text = c0.path("text").asText(null);
            if (text != null && !text.isBlank())
                return text.trim();

            // Some OpenAI-compatible gateways may still return chat-like payloads.
            String alt = c0.path("message").path("content").asText(null);
            if (alt != null && !alt.isBlank())
                return alt.trim();
        }

        String err = root.path("error").path("message").asText(null);
        if (err != null && !err.isBlank()) {
            throw new IllegalStateException("Completion fallback failed errorHash=" + SafeRedactor.hashValue(err) + " errorLength=" + err.length());
        }
        throw new IllegalStateException("Unexpected response shape from /v1/completions fallback");
    }

    /**
     * /v1/responses fallback for OpenAI "Responses API" or compatible gateways.
     *
     * <p>
     * Best-effort: payload is intentionally minimal, and output parsing accepts
     * multiple
     * response shapes to support OpenAI-compatible servers.
     * </p>
     */
    private String callResponsesFallback(String modelId, List<ChatMessage> msgs, ChatRequestDto dto,
            String baseUrlUsed, boolean local) throws Exception {

        String base = OpenAiCompatBaseUrl.sanitize(
                (baseUrlUsed != null && !baseUrlUsed.isBlank())
                        ? baseUrlUsed
                        : env.getProperty("llm.base-url-openai",
                                env.getProperty("llm.openai.base-url", "https://api.openai.com/v1")));

        String apiKey = local ? keyResolver.resolveLocalApiKeyStrict() : keyResolver.resolveOpenAiApiKeyStrict();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key for /v1/responses fallback (local=" + local + ")");
        }

        OpenAiResponsesChatModel responsesModel = new OpenAiResponsesChatModel(
                base,
                apiKey,
                modelId,
                Math.max(1_000L, (long) llmTimeoutSeconds * 1000L));
        dev.langchain4j.data.message.AiMessage ai = responsesModel.chat(msgs).aiMessage();
        String out = ai == null ? "" : (ai.text() == null ? "" : ai.text().trim());
        if (out.isBlank()) {
            throw new IllegalStateException("Empty response from /v1/responses fallback");
        }
        if (out.contains("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH") || out.contains("ROUTE_RESPONSES")) {
            throw new IllegalStateException("Responses fallback failed: "
                    + OpenAiEndpointCompatibility.summarizeForLog(out, 160));
        }
        return out;
    }

    private void recordModelSuccess(String modelId, OpenAiEndpointCompatibility.Endpoint endpoint) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        try {
            modelRuntimeHealthTracker.recordSuccess(safeProviderName(), modelId, endpoint);
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("modelRuntimeHealth.success", ignore); }
    }

    private void recordModelFailure(String modelId, OpenAiEndpointCompatibility.Endpoint endpoint, String reason) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        try {
            modelRuntimeHealthTracker.recordFailure(safeProviderName(), modelId, endpoint, reason);
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("modelRuntimeHealth.failure", ignore); }
    }

    private static OpenAiEndpointCompatibility.Endpoint endpointFromString(String endpoint) {
        if (endpoint == null) {
            return OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS;
        }
        String ep = endpoint.trim().toLowerCase(java.util.Locale.ROOT);
        if ("responses".equals(ep)) {
            return OpenAiEndpointCompatibility.Endpoint.RESPONSES;
        }
        if ("completions".equals(ep)) {
            return OpenAiEndpointCompatibility.Endpoint.COMPLETIONS;
        }
        if ("blocked".equals(ep)) {
            return OpenAiEndpointCompatibility.Endpoint.BLOCKED;
        }
        return OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS;
    }

    private static String reasonFromException(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        if (ex instanceof WebClientResponseException w) {
            return "http_" + w.getRawStatusCode();
        }
        String msg = ex.getMessage();
        if (OpenAiEndpointCompatibility.isChatEndpointMismatchMessage(msg)
                || OpenAiEndpointCompatibility.isCompletionsEndpointMismatchMessage(msg)
                || OpenAiEndpointCompatibility.isResponsesEndpointSuggestionMessage(msg)) {
            return "endpoint_mismatch";
        }
        String simple = ex.getClass().getSimpleName();
        return simple == null || simple.isBlank() ? "unknown" : simple;
    }

    private static dev.langchain4j.exception.ModelNotFoundException unwrapModelNotFound(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof dev.langchain4j.exception.ModelNotFoundException) {
                return (dev.langchain4j.exception.ModelNotFoundException) cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static LlmFastBailoutException unwrapFastBail(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof LlmFastBailoutException) {
                return (LlmFastBailoutException) cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static LlmConfigurationException unwrapLlmConfigurationException(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 12) {
            if (cur instanceof LlmConfigurationException cfg) {
                return cfg;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private void reinforceAssistantAnswerWithProfile(String sessionKey,
            String query,
            String answer,
            double contextualScore,
            com.example.lms.strategy.StrategySelectorService.Strategy chosen,
            VisionMode visionMode,
            GuardProfile guardProfile,
            MemoryMode memoryMode) {
        // [FUTURE_TECH FIX] ?ㅼ젙??OFF硫?ASSISTANT ?듬? ?κ린媛뺥솕 ?먯껜瑜?湲덉?
        if (!enableAssistantReinforcement) {
            return;
        }
        // [FUTURE_TECH FIX] 理쒖떊/誘몄텧???쒗뭹 吏덉쓽??猷⑤㉧/?좎텧 ?듬????κ린 硫붾え由ъ뿉 ?ㅼ뿼?섎뒗 寃껋쓣 諛⑹?
        if (latestTechEnabled && isLatestTechQuery(query)) {
            log.info("[FutureTech] Skipping memory reinforcement to prevent rumor contamination.");
            return;
        }
        if (memoryMode != null && !memoryMode.isWriteEnabled()) {
            log.debug("[MemoryMode] {} -> write disabled, skip reinforcement for sessionHash={}", memoryMode, SafeRedactor.hashValue(String.valueOf(sessionKey)));
            return;
        }
        if (!StringUtils.hasText(answer) || "?뺣낫 ?놁쓬".equals(answer.trim())) {
            return;
        }
        if (visionMode == VisionMode.FREE) {
            // ?쒖꽑2(PRO_FREE) 紐⑤뱶: 硫붾え由?媛뺥솕/??μ쓣 ?섑뻾?섏? ?딆뒿?덈떎.
            return;
        }
        /*
         * 湲곗〈?먮뒗 怨좎젙??媛먯뇿 媛以묒튂(?? 0.18)瑜??곸슜?덉뒿?덈떎. ?댁젣??
         * MLCalibrationUtil???듯빐 ?숈쟻?쇰줈 蹂댁젙??媛믪쓣 ?ъ슜?⑸땲??
         * ?꾩옱 援ы쁽?먯꽌??吏덈Ц 臾몄옄??湲몄씠瑜?嫄곕━ d 濡?媛꾩＜?섏뿬
         * 蹂댁젙媛믪쓣 怨꾩궛?⑸땲?? ?ㅼ젣 ?섍꼍?먯꽌??吏덉쓽??以묒슂?꾨굹 ?ㅻⅨ
         * 嫄곕━ 痢≪젙媛믪쓣 ?낅젰?섏뿬 ?붿슧 ?뺢탳??媛以묒튂瑜??살쓣 ???덉뒿?덈떎.
         */
        double d = (query != null ? query.length() : 0);
        boolean add = true;
        double score = com.example.lms.util.MLCalibrationUtil.finalCorrection(
                d, mlAlpha, mlBeta, mlGamma, mlD0, mlMu, mlLambda, add);

        // ML 蹂댁젙媛믨낵 而⑦뀓?ㅽ듃 ?ㅼ퐫???덉땐(0.5:0.5)
        double normalizedScore = Math.max(0.0, Math.min(1.0, 0.5 * score + 0.5 * contextualScore));

        MemoryGateProfile profile = decideMemoryGateProfile(visionMode, guardProfile);

        try {
            memorySvc.reinforceWithSnippet(sessionKey, query, answer, "ASSISTANT", normalizedScore, profile,
                    memoryMode);
        } catch (Throwable t) {
            log.debug("[Memory] reinforceWithSnippet ?ㅽ뙣: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(t)), String.valueOf(t).length()));
        }
    }

    private void reinforceAssistantAnswer(String sessionKey,
            String query,
            String answer,
            double contextualScore,
            com.example.lms.strategy.StrategySelectorService.Strategy chosen,
            MemoryMode memoryMode) {
        // 湲곕낯 寃쎈줈: VisionMode/GuardProfile ?뺣낫瑜??????놁쑝誘濡?
        // 蹂댁닔?곸씤 STRICT / STRICT 議고빀?쇰줈 硫붾え由?寃뚯씠???꾨줈?뚯씪???곸슜?쒕떎.
        reinforceAssistantAnswerWithProfile(sessionKey, query, answer, contextualScore, chosen,
                VisionMode.STRICT, GuardProfile.STRICT, memoryMode);
    }

    // Legacy overload for backward-compatibility (assumes FULL memory mode)
    private void reinforceAssistantAnswer(String sessionKey,
            String query,
            String answer,
            double contextualScore,
            com.example.lms.strategy.StrategySelectorService.Strategy chosen) {
        reinforceAssistantAnswer(sessionKey, query, answer, contextualScore, chosen, MemoryMode.FULL);
    }

    /** ?몄뀡 ???뺢퇋???좏떥 */
    private static String extractSessionKey(ChatRequestDto req) {
        return Optional.ofNullable(req.getSessionId())
                .map(String::valueOf)
                .map(s -> s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-" + s : s))
                .orElse(UUID.randomUUID().toString());
    }

    // 湲곗〈 ?몄텧遺(3-?몄옄)????섏쐞?명솚???꾪븳 ?ㅻ쾭濡쒕뱶
    private void reinforceAssistantAnswer(String sessionKey, String query, String answer) {
        // 湲곕낯媛? 而⑦뀓?ㅽ듃 ?먯닔 0.5, ?꾨왂 ?뺣낫???꾩쭅 ?놁쑝誘濡?null
        reinforceAssistantAnswer(sessionKey, query, answer, 0.5, null, MemoryMode.FULL);
    }

    /** ?꾩냽 吏덈Ц(?붾줈?? 媛먯?: 留덉?留??듬? 議댁옱 + ?⑦꽩 湲곕컲 */
    private static boolean isFollowUpQuery(String q, String lastAnswer) {
        if (q == null || q.isBlank())
            return false;
        if (lastAnswer != null && !lastAnswer.isBlank())
            return true;
        String s = q.toLowerCase(java.util.Locale.ROOT).trim();
        return s.matches("^(??議곌툑|醫)\\s*?먯꽭??*")
                || s.matches(".*?먯꽭??\s*留먰빐以?*")
                || s.matches(".*?덉떆(??瑜?\\s*?????댁꽌)?\\s*以?*")
                || s.matches("^??\s+洹몃젃(寃?吏).*")
                || s.matches(".*洹쇨굅(??媛)\\s*萸???吏).*")
                || s.matches("^(tell me more|more details|give me an example|why is that).*");
    }

    /** Called by /api/chat/cancel */
    public void cancelSession(Long sessionId) {
        if (sessionId == null)
            return;
        cancelFlags.computeIfAbsent(sessionId, id -> new AtomicBoolean(false)).set(true);
    }

    private boolean isCancelled(Long sessionId) {
        AtomicBoolean f = (sessionId == null) ? null : cancelFlags.get(sessionId);
        return f != null && f.get();
    }

    private void clearCancel(Long sessionId) {
        if (sessionId != null)
            cancelFlags.remove(sessionId);
    }

    private void throwIfCancelled(Long sessionId) {
        if (isCancelled(sessionId)) {
            clearCancel(sessionId);
            throw new CancellationException("cancelled by client");
        }
        throwIfAutolearnPreempted();
    }

    private void throwIfAutolearnPreempted() {
        var ctx = GuardContextHolder.get();
        if (ctx == null || !ctx.planBool("uaw.autolearn", false)) {
            return;
        }
        long deadlineNanos = ctx.planLong("uaw.autolearn.deadlineNanos", 0L);
        if (deadlineNanos > 0L && System.nanoTime() > deadlineNanos) {
            TraceStore.put("uaw.autolearn.preempted", "deadline");
            throw new CancellationException("uaw autolearn deadline exceeded");
        }
        Object supplier = ctx.getPlanOverride("uaw.autolearn.preemptionSupplier");
        if (supplier instanceof java.util.function.BooleanSupplier preempted) {
            boolean abort;
            try {
                abort = preempted.getAsBoolean();
            } catch (Exception ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("uaw.autolearn.preemptionSupplier", ignore); abort = false;
            }
            if (abort) {
                TraceStore.put("uaw.autolearn.preempted", "user_returned");
                throw new CancellationException("uaw autolearn preempted");
            }
        }
    }

    private static String safeTitle(dev.langchain4j.rag.content.Content c) {
        if (c == null)
            return "(?쒕ぉ ?놁쓬)";
        try {
            var seg = c.textSegment();
            if (seg != null) {
                // 1) Prefer explicit metadata title when available
                try {
                    var md = seg.metadata();
                    if (md != null) {
                        String t = md.getString("title");
                        if (t != null && !t.isBlank()) {
                            return truncate(HtmlTextUtil.stripAndCollapse(t), 80);
                        }
                    }
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeTitleMetadata", ignore); }

                // 2) Parse the first line / header format: "[title | provider | url]"
                try {
                    String text = seg.text();
                    if (text != null) {
                        String line1 = text.strip();
                        if (!line1.isEmpty()) {
                            line1 = line1.split("\\r?\\n", 2)[0].strip();
                            if (line1.startsWith("[") && line1.contains("]")) {
                                String inside = line1.substring(1, line1.indexOf(']'));
                                String[] parts = inside.split("\\s*\\|\\s*");
                                if (parts.length > 0 && !parts[0].isBlank()) {
                                    return truncate(HtmlTextUtil.stripAndCollapse(parts[0]), 80);
                                }
                            }
                            // Common web snippet format: "- <a href=...>TITLE</a>: DESC"
                            String aText = HtmlTextUtil.extractAnchorText(line1);
                            if (aText != null && !aText.isBlank()) {
                                return truncate(aText, 80);
                            }
                            return truncate(HtmlTextUtil.stripAndCollapse(line1), 80);
                        }
                    }
                } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeTitleHeader", ignore); }
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeTitleOuter", ignore); }
        try {
            String s = String.valueOf(c);
            if (s != null && !s.isBlank())
                return truncate(s, 80);
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeTitleFallback", ignore); }
        return "(?쒕ぉ ?놁쓬)";
    }

    private static String safeSnippet(dev.langchain4j.rag.content.Content c) {
        if (c == null)
            return "";
        try {
            var seg = c.textSegment();
            if (seg != null && seg.text() != null) {
                String t = seg.text();
                if (t != null) {
                    String s = t.strip();
                    if (!s.isEmpty()) {
                        // Drop a bracket header line if present
                        String[] lines = s.split("\\r?\\n", 2);
                        if (lines.length == 2) {
                            String first = lines[0].strip();
                            if (first.startsWith("[") && first.contains("]")) {
                                s = lines[1].strip();
                            }
                        }
                        String after = HtmlTextUtil.afterAnchor(s);
                        return truncate(HtmlTextUtil.stripAndCollapse(after), 160);
                    }
                }
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("content.safeSnippet", ignore); }
        return "";
    }

    /**
     * Build a compact hint string from web and vector evidences so that
     * high-tier regeneration models cannot ignore retrieved context.
     * This is intentionally short to stay within token budgets.
     */
    private String buildEvidenceHint(
            java.util.List<dev.langchain4j.rag.content.Content> web,
            java.util.List<dev.langchain4j.rag.content.Content> vector) {

        StringBuilder sb = new StringBuilder();

        if (web != null && !web.isEmpty()) {
            sb.append("??寃??寃곌낵:\n");
            int limit = Math.min(5, web.size());
            for (int i = 0; i < limit; i++) {
                sb.append("[W").append(i + 1).append("] ")
                        .append(safeSnippet(web.get(i)))
                        .append("\n");
            }
        }

        if (vector != null && !vector.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("踰≫꽣 寃??寃곌낵:\n");
            int limit = Math.min(3, vector.size());
            for (int i = 0; i < limit; i++) {
                sb.append("[V").append(i + 1).append("] ")
                        .append(safeSnippet(vector.get(i)))
                        .append("\n");
            }
        }

        return sb.toString();
    }

    // ??硫붿꽌?? LLM ?놁씠??珥덉븞??留뚮뱾 ???덈뒗 ?덉쟾???泥?(媛꾨떒 ?대━?ㅽ떛/?뱀뀡 ?쒗뵆由?

    /**
     * (UAW: Bypass Routing) LLM ?앹꽦 ?ㅽ뙣/?ㅽ뵂 ?? 利앷굅媛 ?덉쑝硫?deterministic composer濡??고쉶.
     */
    private String composeEvidenceFallback(String query,
            java.util.List<Content> topDocs,
            java.util.List<Content> vectorDocs,
            boolean lowRisk) {
        var rescueDocs = new java.util.ArrayList<EvidenceAwareGuard.EvidenceDoc>();
        int idx = 1;

        if (topDocs != null) {
            for (var c : topDocs) {
                String url = extractUrlOrFallback(c, idx, false);
                String title = safeTitle(c);
                String snippet = safeSnippet(c);
                rescueDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, title, snippet));
                if (idx++ >= 6)
                    break;
            }
        }
        if (vectorDocs != null) {
            for (var c : vectorDocs) {
                String url = extractUrlOrFallback(c, idx, true);
                String title = safeTitle(c);
                String snippet = safeSnippet(c);
                rescueDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, title, snippet));
                if (idx++ >= 10)
                    break;
            }
        }

        if (rescueDocs.isEmpty()) {
            return "?쇱떆?곸쑝濡??듬????앹꽦?????놁뒿?덈떎. ?좎떆 ???ㅼ떆 ?쒕룄?댁＜?몄슂.";
        }

        try {
            return evidenceAnswerComposer.compose(query, rescueDocs, lowRisk);
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalRescue.compose", e); return evidenceAwareGuard.degradeToEvidenceList(rescueDocs);
        }
    }

    private void recordFinalRescueSilentFailure(String finalQuery,
            String breakerKey,
            int evidenceCount,
            String reason) {
        String safeReason = (reason == null || reason.isBlank()) ? "final_rescue" : reason;
        String queryHash = SafeRedactor.hashValue(finalQuery);
        int queryLen = finalQuery == null ? 0 : finalQuery.length();
        int count = Math.max(0, evidenceCount);
        try {
            TraceStore.put("nightmare.finalRescue.used", true);
            TraceStore.put("nightmare.finalRescue.reason", safeReason);
            TraceStore.put("nightmare.finalRescue.evidenceCount", count);
            TraceStore.put("nightmare.finalRescue.queryHash", queryHash);
            TraceStore.put("nightmare.finalRescue.queryLen", queryLen);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalRescue.trace", ignore);
        }
        try {
            if (nightmareBreaker != null) {
                String key = (breakerKey == null || breakerKey.isBlank()) ? NightmareKeys.CHAT_DRAFT : breakerKey;
                String context = "component=ChatWorkflow;stage=finalRescue;evidenceCount=" + count
                        + ";queryLen=" + queryLen;
                nightmareBreaker.recordSilentFailure(key, context, safeReason);
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("finalRescue.breaker", ignore);
        }
    }

    private static String finalQueryTraceContext(String finalQuery) {
        return "finalQueryHash=" + SafeRedactor.hashValue(finalQuery)
                + ";finalQueryLen=" + (finalQuery == null ? 0 : finalQuery.length());
    }

    private String webOnlyDraft(String query, String ctx) {
        var title = "?붿빟 珥덉븞(LLM-OFF)";
        var bullet = (ctx == null || ctx.isBlank()) ? "- context none" : "- context summary available";
        return title + "\n" + bullet + "\n- 吏덉쓽: " + query;
    }

    private List<ChatMessage> buildPostOrchestrationPromptMessages(String stage, String system, String user) {
        PromptContext ctx = PromptContext.builder()
                .systemInstruction(system == null ? "" : system)
                .userQuery(user == null ? "" : user)
                .build();
        String built = promptBuilder.build(ctx);
        try {
            TraceStore.put("prompt.postOrchestration.usesPromptBuilder", true);
            TraceStore.put("prompt.postOrchestration.stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
            } catch (Throwable ignore) {
                ChatWorkflowTraceSuppressions.traceSuppressed("prompt.builderRequired", ignore);
            }
        return List.of(UserMessage.from(built));
    }

    private static void appendSystemBlock(StringBuilder target, String block) {
        if (target == null || block == null || block.isBlank()) {
            return;
        }
        if (!target.isEmpty() && target.charAt(target.length() - 1) != '\n') {
            target.append('\n');
        }
        target.append(block.strip()).append("\n");
    }

    /**
     * [Dual-Vision] View2 Free-Idea 珥덉븞 ?앹꽦
     * STRICT ?듬? ?댄썑, ??꾪뿕 ?꾨찓?몄뿉?쒕쭔 ?몄텧
     */
    private String generateFreeIdeaDraft(
            String userQuery,
            String strictAnswer,
            String ctxText,
            ModelRouter modelRouter,
            com.example.lms.service.verbosity.VerbosityProfile vp,
            String requestedModel) {
        // Free-Idea??紐⑤뜽 ?좏깮 (?⑤룄 ??
        ChatModel creativeModel = modelRouter.route(
                "FREE_IDEA",
                "LOW", // 由ъ뒪????쾶 媛뺤젣
                "deep",
                vp != null ? vp.targetTokenBudgetOut() : 2048,
                requestedModel);
        String sys = """
                You are Jammini's View2 (Free-Idea mode).
                - The strict answer has already been generated.
                - Your job is to propose CREATIVE, SPECULATIVE ideas,
                  alternative angles, or story-style elaborations.
                - Mark clearly that this part is '異붿륫/鍮꾧났???꾩씠?붿뼱'.
                - Do NOT contradict hard facts from strict answer.
                - ?듬?? ?쒓뎅?대줈, 吏㏃? ?⑤씫 2~3媛??대궡.
                """;
        String user = """
                [USER QUESTION]
                %s
                [STRICT ANSWER]
                %s
                [OPTIONAL CONTEXT SUMMARY]
                %s
                """.formatted(userQuery, strictAnswer, truncate(ctxText, 1500));
        List<ChatMessage> msgs = buildPostOrchestrationPromptMessages(
                "projection.free-idea.legacy", sys, user);
        try {
            return creativeModel.chat(msgs).aiMessage().text();
        } catch (Exception e) {
            log.debug("[FreeIdea] creative draft failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            return null;
        }
    }

    /**
     * projection_agent.v1.yaml: View2 (free projection) branch.
     *
     * This is a plan-driven variant of
     * {@link #generateFreeIdeaDraft(String, String, String, ModelRouter, VerbosityProfile, String)}
     * that supports plan-defined model/traits/maxTokens.
     */
    private String generateProjectionDraftFromPlan(
            String userQuery,
            String strictAnswer,
            String ctxText,
            VerbosityProfile vp,
            ChatRequestDto baseReq,
            com.example.lms.service.rag.plan.ProjectionAgentPlanSpec planSpec) {
        if (planSpec == null || planSpec.viewFreeProjection() == null)
            return null;
        var cfg = planSpec.viewFreeProjection();

        String resolvedModel = null;
        if (planModelResolver != null) {
            resolvedModel = planModelResolver.resolveRequestedModel(cfg.model());
        }

        // If the plan says "auto"/blank, let the router pick.
        String requestedModel = (resolvedModel == null || resolvedModel.isBlank()
                || "auto".equalsIgnoreCase(resolvedModel))
                        ? ""
                        : resolvedModel;

        int tokenHint = (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                ? cfg.maxTokens()
                : Math.max(512, vp.targetTokenBudgetOut());

        var creativeModel = modelRouter.route(
                "FREE_IDEA",
                detectRisk(userQuery),
                vp.hint(),
                tokenHint,
                requestedModel);

        ChatRequestDto stepReq = baseReq;
        try {
            if (baseReq != null) {
                var b = baseReq.toBuilder();
                if (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                    b.maxTokens(cfg.maxTokens());
                if (cfg.traits() != null && !cfg.traits().isEmpty())
                    b.traits(cfg.traits());
                if (StringUtils.hasText(resolvedModel) && !"auto".equalsIgnoreCase(resolvedModel))
                    b.model(resolvedModel);
                stepReq = b.build();
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("projection.freeIdea.stepRequest", ignore); stepReq = baseReq;
        }

        String clippedCtx = truncate(ctxText, 1800);

        StringBuilder system = new StringBuilder("""
                ?덈뒗 '??踰덉㎏ ?쒖젏(View2)'?먯꽌 ?듯븯???꾨줈?앹뀡 ?먯씠?꾪듃?대떎.

                - ?꾨옒 'STRICT ANSWER'??洹쇨굅 湲곕컲 1李??듬??대떎.
                - ?덈뒗 洹??듬???諛뷀깢?쇰줈 **?먯깋??媛???꾩씠?붿뼱/?쒕굹由ъ삤**瑜??쒖븞?쒕떎.
                - ?? ?ъ떎泥섎읆 ?⑥젙?섏? 留먭퀬, 遺덊솗?ㅽ븯硫?遺덊솗?ㅽ븯?ㅺ퀬 ?쒖떆?쒕떎.
                - ?꾪뿕/?섎즺/踰뺣쪧/湲덉쟾 ??怨좎쐞???곸뿭?먯꽌???덉쟾??踰붿쐞?먯꽌 ?쇰컲濡좎쑝濡쒕쭔 留먰븯怨?
                  ?꾨Ц 議곗뼵???泥댄븯吏 ?딅뒗?ㅺ퀬 紐낆떆?쒕떎.
                - 異쒕젰? ?쒓뎅?대줈.
                """);

        if (promptAssetService != null) {
            String traitSys = promptAssetService.renderTraits(cfg.traits());
            if (StringUtils.hasText(traitSys)) {
                appendSystemBlock(system, traitSys);
            }
        }

        String user = """
                [QUESTION]
                %s

                [STRICT ANSWER]
                %s

                [OPTIONAL CONTEXT SUMMARY]
                %s
                """.formatted(userQuery, strictAnswer, clippedCtx);
        List<ChatMessage> msgs = buildPostOrchestrationPromptMessages(
                "projection.free-idea.plan", system.toString(), user);

        try {
            ChatRequestDto exploreReq = applyExploreSamplingCaps(stepReq);
            return callWithRetry(creativeModel, msgs, exploreReq).trim();
        } catch (Exception e) {
            ChatWorkflowTraceSuppressions.traceSuppressed("projection.freeIdea.draft", e); log.debug("[ProjectionAgent] free projection draft failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            return null;
        }
    }

    /**
     * projection_agent.v1.yaml: Final pass (polish merged answer).
     */
    private String finalizeProjectionAnswerFromPlan(
            String userQuery,
            String mergedAnswer,
            VerbosityProfile vp,
            ChatRequestDto baseReq,
            com.example.lms.service.rag.plan.ProjectionAgentPlanSpec planSpec) {
        if (planSpec == null || planSpec.finalAnswer() == null)
            return mergedAnswer;
        var cfg = planSpec.finalAnswer();

        String resolvedModel = null;
        if (planModelResolver != null) {
            resolvedModel = planModelResolver.resolveRequestedModel(cfg.model());
        }
        String requestedModel = (resolvedModel == null || resolvedModel.isBlank()
                || "auto".equalsIgnoreCase(resolvedModel))
                        ? ""
                        : resolvedModel;

        int tokenHint = (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                ? cfg.maxTokens()
                : Math.max(768, vp.targetTokenBudgetOut());

        var finalModel = modelRouter.route(
                "FINAL_ANSWER",
                detectRisk(userQuery),
                vp.hint(),
                tokenHint,
                requestedModel);

        ChatRequestDto stepReq = baseReq;
        try {
            if (baseReq != null) {
                var b = baseReq.toBuilder();
                if (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                    b.maxTokens(cfg.maxTokens());
                if (StringUtils.hasText(cfg.systemPrompt()))
                    b.systemPrompt(cfg.systemPrompt());
                if (StringUtils.hasText(resolvedModel) && !"auto".equalsIgnoreCase(resolvedModel))
                    b.model(resolvedModel);
                stepReq = b.build();
            }
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("projection.final.stepRequest", ignore); stepReq = baseReq;
        }

        StringBuilder system = new StringBuilder();

        if (promptAssetService != null) {
            String sys = promptAssetService.resolveTrustedSystemPromptText(cfg.systemPrompt());
            if (StringUtils.hasText(sys)) {
                appendSystemBlock(system, sys);
            }
        }

        // Output length policy helps a bit for OpenAI-like models.
        try {
            String outputPolicy = buildOutputLengthPolicy(resolvedModel, vp.hint(), AnswerMode.ALL_ROUNDER,
                    vp.targetTokenBudgetOut());
            if (StringUtils.hasText(outputPolicy)) {
                appendSystemBlock(system, outputPolicy);
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.outputPolicyAppend", ignore); }

        // Sensitive topic: add extra privacy boundary for the final polish step.
        try {
            var gctx = GuardContextHolder.get();
            if (gctx != null && (gctx.isSensitiveTopic() || gctx.planBool("privacy.boundary.enforce", false))) {
                appendSystemBlock(system, PRIVACY_BOUNDARY_SYS);
            }
        } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.privacyBoundaryAppend", ignore); }

        String user = """
                ?ъ슜??吏덈Ц:
                %s

                ?꾨옒??1李??⑹꽦 寃곌낵?대떎. 以묐났???쒓굅?섍퀬, 洹쇨굅 湲곕컲 遺遺꾩쓣 ?곗꽑?섎ŉ,
                媛??異붿젙? 紐낇솗??援щ텇?댁꽌 理쒖쥌 ?듬????묒꽦?대씪.

                [MERGED ANSWER]
                %s
                """.formatted(userQuery, mergedAnswer);
        List<ChatMessage> msgs = buildPostOrchestrationPromptMessages(
                "projection.final", system.toString(), user);

        try {
            ChatRequestDto finalReq = applyFinalAnswerSamplingOverrides(stepReq);
            String out = callWithRetry(finalModel, msgs, finalReq);
            return (out == null || out.isBlank()) ? mergedAnswer : out.trim();
        } catch (Exception e) {
            log.debug("[ProjectionAgent] final answer polish failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
            return mergedAnswer;
        }
    }

    /**
     * [Dual-Vision] VisionMode / GuardProfile 湲곕컲 硫붾え由?寃뚯씠???꾨줈?뚯씪 寃곗젙
     */
    private MemoryGateProfile decideMemoryGateProfile(VisionMode visionMode, GuardProfile guardProfile) {
        if (visionMode == VisionMode.FREE) {
            // FREE 紐⑤뱶?먯꽌???먯튃?곸쑝濡?硫붾え由???μ쓣 ?섏? ?딅뒗??
            // 留뚯빟 ??ν븳?ㅻ㈃ 媛???꾪솕???꾨줈?뚯씪???ъ슜?쒕떎.
            return MemoryGateProfile.RELAXED;
        }

        if (visionMode == VisionMode.STRICT) {
            return (guardProfile == GuardProfile.STRICT)
                    ? MemoryGateProfile.HARD
                    : MemoryGateProfile.BALANCED;
        }

        // HYBRID
        return switch (guardProfile) {
            case STRICT -> MemoryGateProfile.HARD;
            case SUBCULTURE -> MemoryGateProfile.RELAXED;
            default -> MemoryGateProfile.BALANCED;
        };
    }

    private static void traceRejectedPublicSystemPrompt(String requestedSystemPrompt) {
        if (!StringUtils.hasText(requestedSystemPrompt)) {
            return;
        }
        String trimmed = requestedSystemPrompt.trim();
        String reason = (trimmed.contains("\n") || trimmed.matches(".*\\s+.*"))
                ? "literal_public_rejected"
                : "asset_not_found";
        try {
            TraceStore.put("prompt.systemPrompt.rejected", true);
            TraceStore.put("prompt.systemPrompt.rejectedReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            TraceStore.put("prompt.systemPrompt.rejectedHash12", SafeRedactor.hash12(trimmed));
            TraceStore.put("prompt.systemPrompt.rejectedLength", trimmed.length());
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("prompt.systemPromptRejectionTrace", ignore);
        }
    }

    private boolean isLowRiskDomain(java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs) {
        if (evidenceDocs == null || evidenceDocs.isEmpty()) {
            return false;
        }
        for (EvidenceAwareGuard.EvidenceDoc doc : evidenceDocs) {
            if (doc == null || doc.id() == null) {
                continue;
            }
            String lower = doc.id().toLowerCase();
            if (lower.contains("namu.wiki")
                    || lower.contains("tistory.com")
                    || lower.contains("gamedot.org")
                    || lower.contains("inven.co.kr")
                    || lower.contains("fandom.com")
                    || lower.contains("hoyolab.com")
                    || lower.contains("arca.live")
                    || lower.contains("ruliweb.com")
                    || lower.contains("ruliweb.co.kr")) {
                return true;
            }
        }
        return false;
    }

    /**
     * LLM/Guard媛 留뚮뱾?대궦 理쒖쥌 ?띿뒪?멸?
     * ?ъ떎??"?뺣낫 ?놁쓬/?먮즺 遺議? 瑜섏쓽 ?ㅽ뙣 ?쒗뵆由우씤吏 ?먮퀎?쒕떎.
     *
     * - 愿??: ?κ린 硫붾え由???? ?꾩옱 Evidence濡쒕씪???듯빐???섎뒗吏 ?щ?瑜?媛瑜대뒗 湲곗?.
     */

    /**
     * InfoFailurePatterns? ?숈씪??湲곗??쇰줈
     * "?뺣낫 ?놁쓬/利앷굅 遺議?瑜??뚰뵾???듬???媛뺥븯寃??먯젙.
     *
     * EvidenceAwareGuard, PromptBuilder??洹쒖튃怨??섎??곸쑝濡??쇱튂?쒖폒
     * Guard/Prompt/Service ?덉씠?닿? ?숈씪??failure 媛쒕뀗???ъ슜?섍쾶 ?쒕떎.
     */
    private boolean isDefinitiveFailure(String text) {
        return InfoFailurePatterns.looksLikeFailure(text);
    }
    /**
     * Map a detected domain label into one of the 9 UI tiles.
     * This is used as a stable hint for alias correction and retrieval routing.
     */
    private static String mapDomainToTile(String domain) {
        if (domain == null || domain.isBlank())
            return "misc";
        String d = domain.toUpperCase(java.util.Locale.ROOT);
        if (d.contains("GENSHIN") || d.contains("GAME") || d.contains("SUBCULTURE"))
            return "games";
        if (d.contains("STUDY") || d.contains("EDU") || d.contains("EMPLOY") || d.contains("TRAIN"))
            return "tech";
        if (d.contains("FINANCE") || d.contains("STOCK") || d.contains("ECON"))
            return "finance";
        if (d.contains("LAW") || d.contains("REGULATION"))
            return "law";
        if (d.contains("HEALTH") || d.contains("MED"))
            return "health";
        if (d.contains("SCI") || d.contains("MATH"))
            return "science";
        if (d.contains("MEDIA") || d.contains("NEWS") || d.contains("CULTURE"))
            return "media";
        if (d.contains("ANIMAL") || d.contains("LIVING") || d.contains("PET"))
            return "animals";
        if (d.contains("TECH") || d.contains("IT") || d.contains("DEV") || d.contains("CODE") || d.contains("DEVICE"))
            return "tech";
        return "misc";
    }

    /**
     * GPT-5/o-series泥섎읆 transport-level ?좏겙 ?쒗븳 ?뚮씪誘명꽣媛 臾댁떆/嫄곕??????덈뒗 紐⑤뜽?먯꽌,
     * ?꾨＼?꾪듃 ?뺤콉?쇰줈 異쒕젰 湲몄씠/援ъ“瑜?媛뺤젣?쒕떎.
     *
     * <p>
     * AnswerMode/verbosityHint蹂꾨줈 ?뱀뀡 ?덉궛????珥섏킌?섍쾶 李⑤벑 ?곸슜?쒕떎.
     */
    private static String buildOutputLengthPolicy(String modelName,
            String verbosityHint,
            AnswerMode answerMode,
            Integer targetTokensOut) {
        final int tok = (targetTokensOut == null || targetTokensOut <= 0) ? 1024 : targetTokensOut;
        final String vh = (verbosityHint == null || verbosityHint.isBlank()) ? "standard"
                : verbosityHint.trim().toLowerCase(Locale.ROOT);
        final AnswerMode am = (answerMode == null) ? AnswerMode.ALL_ROUNDER : answerMode;

        final boolean isOSeries = OpenAiTokenParamCompat.isOSeriesModel(modelName);
        final boolean isGpt5 = OpenAiTokenParamCompat.isGpt5Family(modelName);
        final String modelTag = isOSeries ? "o-series" : (isGpt5 ? "gpt-5.*" : "openai");

        // --- Base preset by verbosityHint ---
        int hardCharCap;
        int summaryMinLines;
        int summaryMaxLines;
        int coreMinLines;
        int coreMaxLines;
        int evidenceBullets;
        int explainBullets;
        int nextBullets;

        switch (vh) {
            case "brief" -> {
                hardCharCap = 1300;
                summaryMinLines = 2;
                summaryMaxLines = 3;
                coreMinLines = 4;
                coreMaxLines = 7;
                evidenceBullets = 3;
                explainBullets = 4;
                nextBullets = 2;
            }
            case "deep" -> {
                hardCharCap = 2700;
                summaryMinLines = 3;
                summaryMaxLines = 5;
                coreMinLines = 8;
                coreMaxLines = 14;
                evidenceBullets = 7;
                explainBullets = 10;
                nextBullets = 4;
            }
            case "ultra" -> {
                hardCharCap = 3600;
                summaryMinLines = 3;
                summaryMaxLines = 6;
                coreMinLines = 10;
                coreMaxLines = 18;
                evidenceBullets = 9;
                explainBullets = 14;
                nextBullets = 5;
            }
            default -> { // standard
                hardCharCap = 1900;
                summaryMinLines = 2;
                summaryMaxLines = 4;
                coreMinLines = 6;
                coreMaxLines = 10;
                evidenceBullets = 5;
                explainBullets = 7;
                nextBullets = 3;
            }
        }

        // Token budget -> char cap scaling (Korean chars per token is variable; keep
        // conservative)
        int scaledCharCap = (int) Math.round(tok * 1.8);
        if (scaledCharCap < 900)
            scaledCharCap = 900;
        // Prefer the stricter of the two caps.
        hardCharCap = Math.min(hardCharCap, scaledCharCap);

        // --- AnswerMode adjustments ---
        switch (am) {
            case FACT -> {
                evidenceBullets = Math.min(12, evidenceBullets + 2);
                explainBullets = Math.max(3, explainBullets - 1);
            }
            case BALANCED -> {
                evidenceBullets = Math.min(12, evidenceBullets + 1);
            }
            case CREATIVE -> {
                evidenceBullets = Math.max(1, evidenceBullets - 2);
                explainBullets = Math.min(18, explainBullets + 2);
                nextBullets = Math.min(8, nextBullets + 1);
            }
            default -> {
                // ALL_ROUNDER: no changes
            }
        }

        // --- o-series adjustments (more aggressive compression; these models tend to
        // be verbose) ---
        if (isOSeries) {
            hardCharCap = (int) Math.round(hardCharCap * 0.85);
            explainBullets = Math.max(3, explainBullets - 2);
            coreMaxLines = Math.max(coreMinLines, coreMaxLines - 2);
        }

        // Final clamps
        if (hardCharCap < 800)
            hardCharCap = 800;
        if (evidenceBullets < 0)
            evidenceBullets = 0;

        String extraRules = "";
        if (am == AnswerMode.FACT) {
            extraRules = "- FACT 紐⑤뱶: ?듭떖 二쇱옣?먮뒗 媛?ν븳 ??[W#]/[V#] 洹쇨굅 留덉빱瑜?遺숈씠怨? 洹쇨굅媛 ?쏀븯硫?'異붿젙/?뺤씤 ?꾩슂'濡?紐낆떆.\n";
        } else if (am == AnswerMode.CREATIVE) {
            extraRules = "- CREATIVE 紐⑤뱶: 異붿륫/?꾩씠?붿뼱??'(異붿륫)' ?먮뒗 '(?꾩씠?붿뼱)'濡??쇰꺼留? 怨쇱옣/?ν솴??湲덉?.\n";
        }

        return """
                ### OUTPUT LENGTH POLICY (prompt-enforced)
                - model-group: %s
                - profile: verbosity=%s, answerMode=%s, targetTokensOut=%d
                - Hard cap: ~%d Korean characters. If you exceed, compress aggressively.
                - Keep the same section order. If short on space: preserve '?붿빟' and '洹쇨굅' first.
                - Do NOT output internal chain-of-thought. Output final answer only.
                - Section budgets (tight):
                  1) ?붿빟: %d~%d以?
                  2) ?듭떖 ?듬?: %d~%d以?
                  3) 洹쇨굅(Evidence): 理쒕? %d媛?bullet (媛?bullet 1以?
                  4) 異붽? ?ㅻ챸/鍮꾧탳: 理쒕? %d媛?bullet
                  5) ?ㅼ쓬 ?④퀎: 理쒕? %d媛?bullet
                - Avoid filler. No long preambles. No repetition.
                %s
                """.formatted(modelTag, vh, am.name(), tok,
                hardCharCap, summaryMinLines, summaryMaxLines, coreMinLines, coreMaxLines,
                evidenceBullets, explainBullets, nextBullets, extraRules);
    }

    private static boolean shouldUseAnalyzeWeb(Map<String, Object> metaHints) {
        if (metaHints == null) {
            return false;
        }
        try {
            Object sm = metaHints.get("searchMode");
            String mode = (sm == null ? "" : sm.toString());
            if ("OFF".equalsIgnoreCase(mode)) {
                return false;
            }
            if ("FORCE_LIGHT".equalsIgnoreCase(mode)) {
                return false;
            }
            if ("FORCE_DEEP".equalsIgnoreCase(mode)) {
                return true;
            }

            Object ea = metaHints.get("enableAnalyze");
            if (ea == null) {
                // AUTO + no flag -> default to false (stay cheap)
                return false;
            }
            if (ea instanceof Boolean b) {
                return b;
            }
            String s = ea.toString();
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        } catch (Exception ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("selfask.planBool", ignore); return false;
        }
    }

}
