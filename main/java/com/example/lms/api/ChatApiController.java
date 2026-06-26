package com.example.lms.api;

import java.util.Optional;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.dto.LearningContextMetadata;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.service.rag.chain.impl.ChainRunner;
import com.example.lms.service.AdaptiveTranslationService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.guard.SensitiveTopicDetector;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.service.trace.DebugCopilotService;
import com.example.lms.trace.SearchTraceConsoleLogger;
import com.example.lms.trace.FailureTagNormalizer;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import com.example.lms.trace.TraceSnapshotStore;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.SettingsService;
import com.example.lms.service.TranslationService;
import com.example.lms.service.AttachmentService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import reactor.util.context.Context;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.Disposable;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.scheduler.Schedulers;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.rag.content.Content;

import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.web.OwnerKeyResolver;
import com.example.lms.planning.artplate.MoEGate;
import com.example.lms.planning.artplate.ArtPlate;
import com.example.lms.planning.ComplexityScore;
import com.example.lms.planning.StrategyTelemetry;
import com.example.lms.orchestration.WorkflowOrchestrator;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.plan.PlanHints;
import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.rag.model.QueryDomain;
// [HARDENING]

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatApiController {
    private static final Logger log = LoggerFactory.getLogger(ChatApiController.class);
    // ===== constants =====
    private static final String FALLBACK_MODEL = ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL;
    private static final String KEY_DEFAULT_MODEL = SettingsService.KEY_OPENAI_MODEL;
    private static final String DEFAULT_MODEL_WAIT_STATUS_CODE = "waiting_for_default_model";
    private static final String DEFAULT_MODEL_WAIT_STATUS_MESSAGE =
            "기본 모델 응답을 기다리는 중입니다. 시간이 걸릴 수 있어요. 답변이 도착하면 이어서 전송합니다.";

    // ??? 嶺뚮∥?? ?熬곣뱿遊????츩(?筌뤾쑬????????
    private static final String MODEL_META_PREFIX = "?MODEL?";
    // FE ?筌뤾쑵?????녹맠
    private static final String EXPOSE_HEADERS = "X-Model-Used,X-RAG-Used,X-User,X-Session-Owner,X-Session-Id,X-Request-Id,X-Trace-Snapshot-Id";

    // When false (default) the API will not include traceHtml in /state
    // responses unless explicitly requested via the debug parameter.
    @org.springframework.beans.factory.annotation.Value("${abandonware.web.trace.expose:false}")
    private boolean exposeTrace;

    // ===== services =====
    private final ChatHistoryService historyService;
    private final ChatService chatService;
    private final AdaptiveTranslationService adaptiveService;
    private final SettingsService settingsService;
    private final TranslationService translationService;

    /**
     * Low-level Naver search service used for trace HTML rendering and
     * compatibility helpers. The actual web search / fallback logic is
     * delegated to {@link WebSearchProvider}.
     */
    private final NaverSearchService searchService;

    /**
     * High-level web search provider that encapsulates Naver ??Brave
     * fallback logic. Controllers and orchestrators should use this
     * abstraction instead of talking to concrete engines directly.
     */
    private final WebSearchProvider webSearchProvider;

    private final SensitiveTopicDetector sensitiveTopicDetector;

    // Planner Nexus (plan auto-select) + plan YAML hint applier
    private final WorkflowOrchestrator workflowOrchestrator;
    private final PlanHintApplier planHintApplier;

    /**
     * Search trace HTML builder used to render the "?롪틵?????λ닔?? UI with a
     * split view: (A) raw web snippets and (B) final TopK context.
     */
    private final TraceHtmlBuilder traceHtmlBuilder;
    private final DebugCopilotService debugCopilotService;
    private final SearchTraceConsoleLogger searchTraceConsoleLogger;

    // In-memory snapshot store (optional; fail-soft in minimal builds)
    @Autowired(required = false)
    private TraceSnapshotStore traceSnapshotStore;

    @Autowired(required = false)
    private com.example.lms.service.MemoryReinforcementService memoryReinforcementService;

    @Autowired(required = false)
    private com.example.lms.diag.RetrievalDiagnosticsCollector retrievalDiagnosticsCollector;

    @Autowired(required = false)
    private com.example.lms.debug.DebugEventTracePromotionService debugEventTracePromotionService;
    /**
     * Location service for intent detection and personalised location
     * responses. Injected to allow early interception of "where am I"
     * queries before invoking the language model or performing any
     * network searches.
     */
    private final com.example.lms.location.LocationService locationService;
    // [HARDENING] hybrid retriever for curated traces
    private final HybridRetriever hybridRetriever;

    /**
     * Attachment service used to resolve uploaded files into documents. This
     * field is injected via constructor thanks to {@link RequiredArgsConstructor}.
     */
    private final AttachmentService attachmentService;

    /**
     * Emitter used to push additional events such as understanding summaries
     * to the client over SSE. The controller registers and unregisters a
     * sink per session to receive asynchronous events from downstream
     * services. This bean is optional so that the application can run
     * without the understanding feature enabled.
     */
    private final ChatStreamEmitter chatStreamEmitter;
    private final ObjectMapper objectMapper;

    /**
     * Runner for the lightweight pre-processing chain. This is injected via
     * constructor thanks to {@link RequiredArgsConstructor}. The chain
     * combines location interception, attachment context injection and image
     * prompt grounding. It is executed prior to the main chat logic to
     * allow immediate responses (e.g. personalised location) and meta data
     * enrichment without interfering with the core chat flow.
     */
    private final ChainRunner chainRunner;

    /**
     * Registry of active chat runs. Used to support SSE replay on reconnection and
     * to track running sessions. Each run stores a replay sink allowing
     * multiple subscribers to join an in-flight generation without spawning
     * duplicate tasks.
     */
    private final com.example.lms.service.chat.ChatRunRegistry runRegistry;

    private final com.example.lms.web.ClientOwnerKeyResolver ownerKeyResolver;
    // === Default RAG toggle ===
    // Use server-side default when the client does not explicitly set useRag.
    // This property is defined in application.yml under chat.defaults.useRag and
    // defaults to true.
    @org.springframework.beans.factory.annotation.Value("${chat.defaults.useRag:true}")
    private boolean defaultUseRag;

    @org.springframework.beans.factory.annotation.Value("${memory.summary.shadow-vector-enabled:true}")
    private boolean sessionSummaryShadowVectorEnabled;

    @org.springframework.beans.factory.annotation.Value("${memory.summary.shadow-vector-score:0.72}")
    private double sessionSummaryShadowVectorScore;

    // ???? ?怨뺣깹???롪틵???嶺뚮ㅄ維獄??リ옇???泥??熬곣뫁夷???逾? ????
    /**
     * Master toggle for accumulation mode. When false the controller will
     * ignore any accumulation hints supplied by the client. Defaults to
     * disabled to avoid unintentional broad crawling.
     */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.enabled:false}")
    private boolean accumulationEnabled;

    /** Default provider top-k when accumulation mode is enabled. */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.web-top-k:30}")
    private int accumulationTopK;

    /**
     * Relatedness cutoff applied in accumulation mode. A lower value admits
     * more pages into the aggregated context.
     */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.min-relatedness:0.35}")
    private double accumulationMinRel;

    /** Page content fetch timeout (ms) when accumulation mode is enabled. */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.per-page-ms:4500}")
    private int accumulationPerPageMs;

    /**
     * Comma-separated list of provider IDs to prefer when accumulation mode is
     * active. When empty the handler uses all configured providers.
     */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.providers:}")
    private String accumulationProvidersCsv;

    /**
     * Cancel the currently running chat streaming for the given session. This
     * endpoint can be
     * invoked by the client when the user clicks a "Stop generation" button to
     * terminate long
     * running operations. The current implementation delegates to
     * {@link ChatService#cancelSession(Long)}
     * which performs best-effort cancellation of any in-flight tasks. This method
     * always returns
     * HTTP 200 OK regardless of whether there was an active stream to cancel.
     *
     * @param sessionId the session identifier to cancel; may be {@code null}
     * @return 200 OK
     */
    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel(@RequestParam(required = false) Long sessionId,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       Authentication authentication) {
        Long resolvedSessionId = firstNonNull(sessionId, longFromBody(body, "sessionId"));
        if (resolvedSessionId != null) {
            try {
                ChatSession session = historyService.getSessionWithMessages(resolvedSessionId);
                if (session != null && !canAccessSession(session, authentication)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            } catch (Exception e) {
                log.warn("Failed to authorize /cancel for sessionHash={}: {}",
                        SafeRedactor.hashValue(String.valueOf(resolvedSessionId)), errorSummary(e));
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }
        try {
            chatService.cancelSession(resolvedSessionId);
            // Mark the run as cancelled in the registry so that it will be evicted
            if (resolvedSessionId != null) {
                try {
                    runRegistry.markCancelled(resolvedSessionId);
                } catch (Throwable ignore) {
                    logSuppressed("cancel.markCancelled");
                }
            }
        } catch (Exception ignore) {
            logSuppressed("cancel.cancelSession");
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Retrieve the current state of a chat session. This endpoint returns whether
     * the session is still running along with the last assistant message,
     * the model used and any trace HTML metadata embedded in the session. It is
     * used by the client to decide whether to attach to an in-flight run when
     * reloading the page. When the session does not exist the returned
     * {@code running} flag will be false and the other values may be null.
     *
     * @param sessionId the session identifier to query
     * @return a JSON map containing running/modelUsed/lastAssistant/traceHtml
     */
    @GetMapping("/state")
    public ResponseEntity<java.util.Map<String, Object>> state(@RequestParam Long sessionId,
            @RequestParam(name = "debug", defaultValue = "false") boolean debug,
            Authentication authentication) {
        boolean running = false;
        try {
            running = (sessionId != null) && runRegistry != null && runRegistry.isRunning(sessionId);
        } catch (Exception ignore) {
            logSuppressed("state.running");
        }
        ChatSession session = null;
        try {
            session = historyService.getSessionWithMessages(sessionId);
        } catch (Exception ignore) {
            logSuppressed("state.sessionLookup");
        }
        if (session != null && !canAccessSession(session, authentication)) {
            java.util.Map<String, Object> denied = new java.util.HashMap<>();
            denied.put("running", running);
            denied.put("traceHtml", null);
            denied.put("error", "SESSION_FORBIDDEN");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(denied);
        }
        var last = (session == null) ? null : historyService.getLastAssistantMessage(sessionId).orElse(null);
        // Extract model and trace metadata from the session history
        String modelUsed = null;
        String traceHtml = null;
        try {
            if (session != null && session.getMessages() != null) {
                for (var m : session.getMessages()) {
                    var c = m.getContent();
                    if (c == null)
                        continue;
                    var mu = ChatModelMetaSupport.extractModelUsed(c);
                    if (mu != null)
                        modelUsed = mu;
                    var th = ChatModelMetaSupport.extractTraceHtml(c);
                    if (th != null)
                        traceHtml = th;
                }
            }
        } catch (Exception ignore) {
            logSuppressed("state.metaExtract");
        }
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        java.util.Map<String, Object> restoredSettings = java.util.Collections.emptyMap();
        try {
            if (session != null) {
                String meta = session.getSessionMeta();
                if (meta != null && !meta.isBlank()) {
                    restoredSettings = objectMapper.readValue(meta, java.util.Map.class);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to restore session_meta in /state for session {}: {}",
                    SafeRedactor.hashValue(String.valueOf(sessionId)), errorSummary(e));
        }

        out.put("running", running);
        out.put("modelUsed", modelUsed);
        out.put("lastAssistant", last);
        out.put("traceHtml", exposeTrace
                ? "traceHtml=" + SafeRedactor.diagnosticText("traceHtml", traceHtml, 12000)
                : null);
        out.put("traceDebugRequested", debug);
        return ResponseEntity.ok(out);
    }

    // === sync chat (blocking) ===
    @PostMapping("/sync")
    public ResponseEntity<ChatResponseDto> chatSync(@RequestBody @Valid ChatRequestDto dto,
            @AuthenticationPrincipal UserDetails principal) {
        if (dto == null) {
            return ResponseEntity.badRequest().body(new ChatResponseDto("bad_request", null, "bad_request", false));
        }
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        String ownerKey = ownerKeyResolver.ownerKey();
        ResponseEntity<ChatResponseDto> denied = ChatSessionAccessGuard.authorize(
                historyService, dto.getSessionId(), username, ownerKey, log);
        if (denied != null) {
            return denied;
        }
        ChatResponseDto body = handleChat(dto, username, null, ownerKey, null, null);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        String modelHdr = (body.getModelUsed() != null && !body.getModelUsed().isBlank())
                ? body.getModelUsed()
                : settingsService.getAllSettings().getOrDefault(KEY_DEFAULT_MODEL, FALLBACK_MODEL);
        ok.header("X-Model-Used", modelHdr);
        if (body.getSessionId() != null) {
            ok.header("X-Session-Id", String.valueOf(body.getSessionId()));
        }
        if (body.isRagUsed())
            ok.header("X-RAG-Used", "true");
        ok.header("X-User", username);
        ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
        return ok.body(body);
    }

    // ===== sync chat =====
    @PostMapping
    public Mono<ResponseEntity<ChatResponseDto>> chat(@RequestBody @Valid ChatRequestDto req,
            @AuthenticationPrincipal UserDetails principal,
            HttpServletRequest request) {
        if (req == null) {
            return Mono.just(ResponseEntity.badRequest().body(new ChatResponseDto("bad_request", null, "bad_request", false)));
        }
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        // Capture the client IP early to avoid IllegalStateException when running on
        // non-request threads. Prefer the X-Forwarded-For header when present.
        String clientIp;
        try {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                clientIp = xff.split(",")[0].trim();
            } else {
                clientIp = request.getRemoteAddr();
            }
        } catch (Exception e) {
            clientIp = "unknown";
            logSuppressed("chat.clientIp");
        }

        // [MoE] ???х뙴?꾨Ь?嶺뚯쉳????熬곣뫖??ownerKey / ?筌뤾쑬?????녹맠 ??ル∥??
        final String preResolvedOwnerKey = ownerKeyResolver.ownerKey();
        final String sessionIdHeader = request.getHeader("X-Session-Id");
        final String conversationIdHeader = request.getHeader("X-Conversation-Id");
        final String requestIdHeader = request.getHeader("X-Request-Id");
        applyRequestSessionId(req, sessionIdHeader, conversationIdHeader);

        // bug_xa: header sessionId?띠럾? ???덈츎??DTO?띠럾? null??????貫?꾥????逾?熬곣뫁逾?筌뤾쑴逾???袁⑥춸 ?????깅さ亦껋깢????낅슣???
        ResponseEntity<ChatResponseDto> denied = ChatSessionAccessGuard.authorize(
                historyService, req.getSessionId(), username, preResolvedOwnerKey, log);
        if (denied != null) {
            return Mono.just(denied);
        }
        final String jamminiMode = request.getHeader("X-Jammini-Mode");
        final String guardLevel = request.getHeader("X-Guard-Level");

        // bug_xa: header???筌뤾쑬??????덈츎??DTO sessionId?띠럾? null?????
        // ??????덈콦???깅턄??嶺뚮∥???꾨뎨????곕츩??ル벣遊??怨뺣깹????븐슙???貫?꾥뚭였寃?????袁⑥춸 ?????덈펲.
        // MERGE_HOOK:PROJ_AGENT::controller_session_attachment_inject
        // 嶺뚳퐘維? 嶺뚯쉶?꾣룇?筌뤾퍓??attachmentIds ???㈑??筌뤾쑬???嶺뚳퐘維??띠럾? ???깅さ嶺????吏??낅슣???+ Fail-soft 嶺뚳퐣瑗??
        if ((req.getAttachmentIds() == null || req.getAttachmentIds().isEmpty())
                && ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(req.getMessage())) {

            try {
                String sid = req.getSessionId() == null ? null : String.valueOf(req.getSessionId());
                if (sid != null && !sid.isBlank()) {
                    var sessionAttachments = attachmentService.findBySession(sid);
                    if (sessionAttachments != null && !sessionAttachments.isEmpty()) {
                        // ?筌뤾쑬??????嶺뚢돦堉? 嶺뚳퐘維? ID??req???낅슣???
                        java.util.List<String> ids = sessionAttachments.stream()
                                .map(com.example.lms.dto.AttachmentDto::id)
                                .collect(java.util.stream.Collectors.toList());
                        req.setAttachmentIds(ids);
                        log.info("[ChatApi] Auto-injected {} attachments from sessionHash={}", ids.size(), SafeRedactor.hashValue(sid));
                    }
                }
            } catch (Exception ignore) {
                logSuppressed("chat.attachments.autoInject");
                // ?筌뤾쑬???브퀗??????덉넮 ??戮?뱺??嶺뚳퐘維? ???⑸츎 ?롪퍒????뿉??띠룄?당쳥???겶? Fail-soft??嶺뚯쉳?듸쭛?
            }

            // ?????嶺뚳퐘維??띠럾? ??怨몃さ嶺??롪퍔???彛???節뗢뵛????怨쀫틮 嶺뚯쉶?꾣룇??怨쀬Ŧ 嶺뚳퐣瑗??
            if (req.getAttachmentIds() == null || req.getAttachmentIds().isEmpty()) {
                String __msg = String.valueOf(req.getMessage());
                log.warn("[ChatApi] Attachment question but no attachments found. messageHash={} messageLength={}",
                        SafeRedactor.hash12(__msg), __msg.length());
                // BAD_REQUEST?????嶺뚯솘? ??袁ぢ??熬곣뫁????怨쀫틮 嶺?嶺뚳퐣瑗?怨レ뿉???ｌ뫒??嶺뚯쉳?듸쭛?
            }
        }

        // If the client omitted useRag, apply the server default.
        if (req.getUseRag() == null) {
            req.setUseRag(defaultUseRag);
        }
        if (req.isUseAdaptive()) {
            return adaptiveService.translate(req.getMessage(), "ko", "en")
                    .map(t -> new ChatResponseDto(t, null, "Adaptive-Translator", false))
                    .map(body -> ResponseEntity.ok()
                            .header("X-Model-Used", "Adaptive-Translator")
                            .header("X-User", username)
                            .header("Access-Control-Expose-Headers", EXPOSE_HEADERS)
                            .body(body));
        }

        final String _username = username;
        final String _clientIp = clientIp;
        final String _ownerKey = preResolvedOwnerKey;
        final String _jamminiMode = jamminiMode;
        final String _guardLevel = guardLevel;
        Mono<ResponseEntity<ChatResponseDto>> mono = Mono.fromCallable(() -> {
            ChatResponseDto body = handleChat(req, _username, _clientIp, _ownerKey, _jamminiMode, _guardLevel);

            // ???? ???揶??筌뤾쑬??嶺뚮씞?뗩뇡?????
            try {
                if (req.getSessionId() == null
                        && req.getAttachmentIds() != null
                        && !req.getAttachmentIds().isEmpty()
                        && body != null && body.getSessionId() != null) {
                    attachmentService.attachToSession(String.valueOf(body.getSessionId()), req.getAttachmentIds());
                }
            } catch (Exception ex) {
                log.debug("Failed to attach uploaded files to new session: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
            }
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            // ?????깆젷 ????嶺뚮ㅄ維??숈춻??놁떳 ?リ옇?▽빳?(??臾먯뱺嶺??ル??, ???껇????????깆젧?띠룆????뿉????揶?
            String modelHdr = (body.getModelUsed() != null && !body.getModelUsed().isBlank())
                    ? body.getModelUsed()
                    : settingsService.getAllSettings().getOrDefault(KEY_DEFAULT_MODEL, FALLBACK_MODEL);
            ok.header("X-Model-Used", modelHdr);
        if (body.getSessionId() != null) {
            ok.header("X-Session-Id", String.valueOf(body.getSessionId()));
        }
            if (body.isRagUsed())
                ok.header("X-RAG-Used", "true");
            ok.header("X-User", username);
            ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
            return ok.body(body);
        });
        // Offload the blocking call to a bounded elastic scheduler and attach a common
        // error handler.
        mono = mono.subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    log.error("[AWX][chat] chat-failed type={} error={}", ex == null ? "unknown" : ex.getClass().getSimpleName(), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
                    String rawError = ex == null ? null : ex.getMessage();
                    String formatted = String.format("Error: errorHash=%s errorLength=%d", SafeRedactor.hashValue(rawError), rawError == null ? 0 : rawError.length());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ChatResponseDto(formatted, null, "error-model", false)));
                });
        // Propagate the client IP in the Reactor context. Downstream components can
        // retrieve this value via Mono.deferContextual if needed.
        return mono.contextWrite(Context.of("clientIp", clientIp));
    }

    // ===== streaming chat (SSE) =====
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chatStream(@RequestBody @Valid ChatRequestDto req,
            @RequestParam(name = "attach", required = false, defaultValue = "false") boolean attach,
            @RequestParam(name = "debug", required = false, defaultValue = "false") boolean debug,
            @AuthenticationPrincipal UserDetails principal,
            HttpServletRequest request) {
        if (req == null) {
            return Flux.just(sse(ChatStreamEvent.error("bad_request")));
        }
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        // Capture the client IP early to avoid IllegalStateException when running on
        // non-request threads. Prefer the X-Forwarded-For header when present.
        String clientIp;
        try {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                clientIp = xff.split(",")[0].trim();
            } else {
                clientIp = request.getRemoteAddr();
            }
        } catch (Exception e) {
            clientIp = "unknown";
            logSuppressed("stream.clientIp");
        }

        // [MoE] ???х뙴?꾨Ь?嶺뚯쉳????熬곣뫖??ownerKey / ?筌뤾쑬?????녹맠 ??ル∥??
        final String preResolvedOwnerKey = ownerKeyResolver.ownerKey();
        final String sessionIdHeader = request.getHeader("X-Session-Id");
        final String conversationIdHeader = request.getHeader("X-Conversation-Id");
        final String requestIdHeader = request.getHeader("X-Request-Id");
        applyRequestSessionId(req, sessionIdHeader, conversationIdHeader);


        ResponseEntity<ChatResponseDto> denied = ChatSessionAccessGuard.authorize(
                historyService, req.getSessionId(), username, preResolvedOwnerKey, log);
        if (denied != null) {
            return Flux.just(sse(ChatStreamEvent.error("session_forbidden")));
        }
        final String jamminiMode = request.getHeader("X-Jammini-Mode");
        final String guardLevel = request.getHeader("X-Guard-Level");

        // bug_xa: header sessionId?띠럾? ???덈츎??DTO sessionId?띠럾? null????? attach/嶺뚮∥???꾨뎨????곕츩??ル벣遊??怨뺣깹????袁⑥춸 ??        // ???깅쾳
        // When attach=true and a valid session is provided, immediately join the
        // existing
        // replay sink instead of spawning a new generation. This supports
        // reconnection after a page refresh or tab restore. Only sessions
        // currently marked as RUNNING in the run registry will be attached.
        if (attach && req.getSessionId() != null && runRegistry != null
                && runRegistry.isRunning(req.getSessionId())) {
            return runRegistry.attach(req.getSessionId());
        }
        // Use a bounded replay sink so that early emissions are not lost when the
        // HTTP layer subscribes a few milliseconds later ("zero-subscriber" race),
        // and to avoid silent token/event drops under bursty emission.
        //
        // This sink fan-outs to both:
        //  - the client SSE subscriber (returned Flux)
        //  - an internal bridge subscriber (to feed ChatRunRegistry for resume/attach)
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink = Sinks.many()
                .replay()
                .limit(4096);

        // Holder for the computed session key so that it can be referenced in finally
        final String[] currentSessionKeyHolder = new String[1];
        // Track current session id to allow cancellation propagation
        final AtomicReference<Long> currentSessionId = new AtomicReference<>(req.getSessionId());

        // Reference to the per-session replay sink. Once the session is
        // initialised the sink is obtained from the ChatRunRegistry. This
        // reference allows completion handlers (e.g. onComplete, onCancel)
        // to emit final events or mark the run as done/cancelled.
        final java.util.concurrent.atomic.AtomicReference<reactor.core.publisher.Sinks.Many<ServerSentEvent<ChatStreamEvent>>> runSinkRef = new java.util.concurrent.atomic.AtomicReference<>();
        // Capture local variables for use within lambda; lambda parameters must be
        // final or effectively final
        final String _username = username;
        final String _clientIp = clientIp;
        final String _jamminiMode = jamminiMode;
        final String _guardLevel = guardLevel;

        // Capture correlation identifiers from the request thread.
        final String __capturedSid = firstNonBlank(MDC.get("sid"), MDC.get("sessionId"), sessionIdHeader, conversationIdHeader);
        String __tmpTrace = firstNonBlank(MDC.get("traceId"), MDC.get("trace"), requestIdHeader);
        if (__tmpTrace == null || __tmpTrace.isBlank()) {
            __tmpTrace = java.util.UUID.randomUUID().toString();
        }
        final String __capturedTrace = __tmpTrace;
        final String __capturedRequestId = firstNonBlank(MDC.get("x-request-id"), requestIdHeader, __capturedTrace);
        final boolean __capturedDbgSearch = SearchTraceConsoleLogger.isRequestEnabled();
        final String __capturedDbgSrc = MDC.get("dbgSearchSrc");
        final String __capturedDbgEngines = MDC.get("dbgSearchBoostEngines");

        final String __httpMethod = request.getMethod();
        final String __httpPath = request.getRequestURI();
        final String __httpQuery = request.getQueryString();
        final String __httpUa = request.getHeader("User-Agent");
        final com.abandonware.ai.addons.budget.TimeBudget __capturedBudget =
                com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        final long __streamStartedNs = System.nanoTime();
        try {
            Long remainingMs = __capturedBudget == null ? null : __capturedBudget.remainingMillis();
            ChatStreamEvent.StatusSignal startedSignal = ChatStreamEvent.StatusSignal.of(
                    "stream", "started", "stream started", remainingMs, 0L, false);
            sink.tryEmitNext(sse(ChatStreamEvent.status(startedSignal)));
            sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                    ChatStreamSignalBuilder.buildTransformerBlocks(
                            java.util.Map.of(), startedSignal, null, null, null, null))));
        } catch (Throwable ignore) {
            logSuppressed("stream.status.started");
        }
        Disposable d = Mono.fromRunnable(() -> {
            if (__capturedBudget != null) {
                com.abandonware.ai.addons.budget.TimeBudgetContext.set(__capturedBudget);
            }
            // ??SSE ???덈콦?源녿닔? boundedElastic????????덈뺄?????ThreadLocal 嶺뚮ㅏ援????낅슣????熬곣뫗??
            try (TraceContext __tc = TraceContext.attach(__capturedSid, __capturedTrace)) {
                try {
                    if (__capturedRequestId != null && !__capturedRequestId.isBlank()) {
                        MDC.put("x-request-id", __capturedRequestId);
                    }
                    if (__capturedDbgSearch) {
                        MDC.put("dbgSearch", "1");
                        if (__capturedDbgSrc != null && !__capturedDbgSrc.isBlank()) {
                            MDC.put("dbgSearchSrc", __capturedDbgSrc);
                        }
                        if (__capturedDbgEngines != null && !__capturedDbgEngines.isBlank()) {
                            MDC.put("dbgSearchBoostEngines", __capturedDbgEngines);
                        }
                    } else {
                        MDC.remove("dbgSearch");
                        MDC.remove("dbgSearchSrc");
                        MDC.remove("dbgSearchBoostEngines");
                    }
                } catch (Throwable ignore) {
                    logSuppressed("stream.mdc.rehydrate");
                }

                traceClear("stream.trace.clear");

                // Rehydrate a minimal envelope so background breadcrumbs can be correlated.
                tracePutIfAbsent("trace.id", SafeRedactor.hashValue(__capturedTrace));
                if (__capturedSid != null && !__capturedSid.isBlank()) tracePutIfAbsent("sid", SafeRedactor.hashValue(__capturedSid));
                if (__httpMethod != null && !__httpMethod.isBlank()) tracePutIfAbsent("http.method", __httpMethod);
                if (__httpPath != null && !__httpPath.isBlank()) tracePutIfAbsent("http.path", SafeRedactor.diagnosticValue("http.path", __httpPath));
                if (__httpQuery != null && !__httpQuery.isBlank()) tracePutIfAbsent("http.query", SafeRedactor.diagnosticValue("http.query", __httpQuery));
                if (__httpUa != null && !__httpUa.isBlank()) tracePutIfAbsent("http.ua", SafeRedactor.diagnosticValue("http.ua", __httpUa));
                com.example.lms.debug.DebugEventTracePromotionService.seedRequestedExternalEvidenceLanes(req.getMessage());
            GuardContext gctx = GuardContext.defaultContext();
            if (_jamminiMode != null && !_jamminiMode.isBlank()) {
                gctx.setHeaderMode(_jamminiMode);
                gctx.setMode(_jamminiMode);
                gctx.setPlanId(_jamminiMode);
                if ("S1".equalsIgnoreCase(_jamminiMode) || "safe".equalsIgnoreCase(_jamminiMode)) {
                    gctx.setMemoryProfile("MEMORY");
                } else if ("S2".equalsIgnoreCase(_jamminiMode)
                        || "brave".equalsIgnoreCase(_jamminiMode)
                        || "free".equalsIgnoreCase(_jamminiMode)
                        || "zero_break".equalsIgnoreCase(_jamminiMode)) {
                    gctx.setMemoryProfile("NONE");
                }
            }
            if (_guardLevel != null && !_guardLevel.isBlank()) {
                gctx.setGuardLevel(_guardLevel);
            }
            if (req != null && req.getMessage() != null) {
                gctx.setEntityQueryFromQuestion(req.getMessage());
				// UAW: propagate raw user query for downstream orchestration/unmasking/autolearn hooks
				gctx.setUserQuery(req.getMessage());
            }
            GuardContextHolder.set(gctx);
            try {
                // 1) ???깆젧 ?곌랜理묌뜮?
                ChatRequestDto dto = mergeWithSettings(req);
                final boolean __hasAttachments = dto.getAttachmentIds() != null && !dto.getAttachmentIds().isEmpty();
                final boolean __looksLikeAttachmentQ =
                        ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(dto.getMessage());
                final boolean __hasDocumentEvidence = __hasAttachments && __looksLikeAttachmentQ;

                // [PATCH] Streaming path should also apply sensitive-topic overrides (fail-soft)
                try {
                    if (sensitiveTopicDetector != null) {
                        sensitiveTopicDetector.applyTo(gctx, dto);
                    }
                } catch (Exception e) {
                    log.debug("[SensitiveTopicDetector] applyTo failed in chatStream: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                }

                // DROP: apply plan selection + guard hints BEFORE web prefetch/search.
                PlanHints __planHints = null;
                boolean __allowWebCap = true;
                boolean __allowRagCap = true;
                try {
                    AnswerMode __am = AnswerMode.fromString(dto.getMode());
                    QueryDomain __qd = (gctx != null && gctx.isSensitiveTopic()) ? QueryDomain.SENSITIVE : QueryDomain.GENERAL;
                    if (workflowOrchestrator != null) {
                        workflowOrchestrator.ensurePlanSelected(gctx, __am, __qd, dto.getMessage(), __hasDocumentEvidence);
                    }
                    if (planHintApplier != null && gctx != null && gctx.getPlanId() != null) {
                        __planHints = planHintApplier.load(gctx.getPlanId());
                        planHintApplier.applyToGuardContext(__planHints, gctx);
                    }
                    __allowWebCap = (__planHints == null || __planHints.allowWeb() != Boolean.FALSE);
                    __allowRagCap = (__planHints == null || __planHints.allowRag() != Boolean.FALSE);
                    TraceStore.put("plan.id.preSearch", (gctx == null ? null : gctx.getPlanId()));
                    TraceStore.put("plan.allowWeb.cap", __allowWebCap);
                    TraceStore.put("plan.allowRag.cap", __allowRagCap);
                } catch (Exception ignorePlan) {
                    logSuppressed("plan.preSearch.stream");
                }

                // === 嶺뚳퐘維? ???쳜????덈콦 ?낅슣??????獄??????吏?OFF ===
                // Compose the message for the call by prepending extracted attachment texts.
                // When a
                // user uploads files and explicitly asks about them (determined via the
                // heuristic),
                // the web search is disabled to avoid leaking the query to external providers.
                // attachments.inline.legacy-prepend-enabled is deprecated/no-op. Attachment evidence
                // flows through ChatWorkflow -> PromptContext.localDocs -> PromptBuilder.build(ctx).
                // Determine the final value of useWebSearch after considering attachments.
                // Explicit true
                // values are honoured when no attachment context question is detected. Null is
                // treated as false.
                boolean __reqUseWeb = Boolean.TRUE.equals(dto.isUseWebSearch());
                boolean __finalUseWeb = (__hasAttachments && __looksLikeAttachmentQ) ? false : __reqUseWeb;
                // Plan cap: allowWeb/allowRag (applied before prefetch/search)
                __finalUseWeb = __finalUseWeb && __allowWebCap;
                final boolean __finalUseRag = __allowRagCap && Boolean.TRUE.equals(dto.isUseRag());

                // 2) ?筌뤾쑬??upsert
                ChatSession session = (req.getSessionId() == null)
                        ? historyService
                                .startNewSession(dto.getMessage(), _username, _clientIp, preResolvedOwnerKey,
                                        dto.getMemoryProfile())
                                .orElseThrow(() -> new IllegalStateException("?筌뤾쑬????諛댁뎽 ???덉넮"))
                        : historyService.getSessionWithMessages(req.getSessionId());
                if (session == null && req.getSessionId() != null) {
                    traceSessionNotFound(req.getSessionId());
                    session = historyService
                            .startNewSession(dto.getMessage(), _username, _clientIp, preResolvedOwnerKey,
                                    dto.getMemoryProfile())
                            .orElseThrow(() -> new IllegalStateException("session recovery failed"));
                }
                // ???? ???揶??筌뤾쑬??嶺뚮씞?뗩뇡?????
                // If a new session was created and attachments are present, map the
                // attachments to this session. Without this association the
                // AttachmentContextHandler (which relies on findBySession) will not
                // return uploaded documents.
                try {
                    if (req.getSessionId() == null
                            && __hasAttachments
                            && session != null && session.getId() != null) {
                        attachmentService.attachToSession(String.valueOf(session.getId()), req.getAttachmentIds());
                    }
                } catch (Exception ex) {
                    log.debug("Failed to attach uploaded files to new session (SSE): {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
                }
                // Propagate real session id so that it can be cancelled later
                if (session != null && session.getId() != null) {
                    currentSessionId.set(session.getId());
                }
                // Initialise the replay sink and bridge events
                if (session != null && session.getId() != null) {
                    // Obtain or create a replay sink for this session. Multiple calls
                    // will return the same sink for in-flight sessions. Store it in
                    // runSinkRef for later completion signalling.
                    reactor.core.publisher.Sinks.Many<ServerSentEvent<ChatStreamEvent>> runSink = runRegistry
                            .startOrGet(session.getId());
                    runSinkRef.set(runSink);
                    // Bridge all events emitted on the unicast sink to the replay sink.
                    sink.asFlux().subscribe(event -> {
                        try {
                            runSink.tryEmitNext(event);
                        } catch (Throwable ignore) {
                            logSuppressed("stream.runSink.forward");
                        }
                    }, err -> {
                        // Propagate an error event into the replay sink. Any downstream
                        // subscribers will receive this before the run is marked done.
                        try {
                            // Use String.format to build the error message instead of concatenation
                            String errMsg = String.format("???댁쾌 errorHash=%s errorLength=%d", SafeRedactor.hashValue(err == null ? null : err.getMessage()), err == null || err.getMessage() == null ? 0 : err.getMessage().length());
                            runSink.tryEmitNext(sse(ChatStreamEvent.error(errMsg)));
                        } catch (Throwable ignore) {
                            logSuppressed("stream.runSink.error");
                        }
                    }, () -> {
                        // When the unicast sink completes, mark this run as done. This
                        // allows subsequent attach attempts to replay the completed
                        // conversation without spawning a new generation.
                        Long doneSessionId = currentSessionId.get();
                        if (doneSessionId != null) {
                            runRegistry.markDone(doneSessionId);
                        }
                    });
                }

                // 2-a) ?筌뤾쑬??????ｌ뫒亦???SSE sink ?繹먮굞夷?
                String sessionKey;
                if (session != null && session.getId() != null) {
                    String s = String.valueOf(session.getId());
                    // Use String.format instead of string concatenation to build the session key
                    sessionKey = s.startsWith("chat-") ? s : (s.matches("\\d+") ? String.format("chat-%s", s) : s);
                } else {
                    sessionKey = java.util.UUID.randomUUID().toString();
                }
                // store for later cleanup
                currentSessionKeyHolder[0] = sessionKey;
                try {
                    chatStreamEmitter.registerSink(sessionKey, sink);
                } catch (Throwable t) {
                    // registration is best-effort; proceed even if it fails
                    log.debug("Failed to register SSE sink: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(t)), String.valueOf(t).length()));
                }


                // [PATCH] Update breadcrumbs to the resolved session id and emit it early.
                try {
                    if (sessionKey != null && !sessionKey.isBlank()) {
                        try {
                            org.slf4j.MDC.put("sid", sessionKey);
                            org.slf4j.MDC.put("sessionId", sessionKey);
                        } catch (Throwable ignoreMdc) {
                            logSuppressed("stream.sessionBreadcrumb.mdc");
                        }
                        try {
                            com.example.lms.search.TraceStore.put("sid", SafeRedactor.hashValue(sessionKey));
                        } catch (Throwable ignoreTrace) {
                            logSuppressed("stream.sessionBreadcrumb.trace");
                        }
                    }
                } catch (Exception ignore) {
                    logSuppressed("stream.sessionBreadcrumb.outer");
                }

                // Emit session id early so SSE clients can persist it even if the stream is interrupted.
                try {
                    if (session != null && session.getId() != null) {
                        sink.tryEmitNext(sse(ChatStreamEvent.sessionReady(session.getId())));
                    }
                } catch (Exception ignore) {
                    logSuppressed("stream.sessionReady");
                }

                if (req.getSessionId() != null) {
                    historyService.appendMessage(session.getId(), "user", dto.getMessage());
                }

                //
                // Run lightweight chain (location intercept / attachment context / image
                // grounding)
                try {
                    String userId = (principal != null ? principal.getUsername() : "anonymous");
                    // Execute the lightweight pre-processing chain. Use the injected
                    // ChatStreamEmitter rather than an undefined variable. Any
                    // exceptions are swallowed to avoid blocking the primary chat flow.
                    chainRunner.run(sessionKey, userId, req.getMessage(), chatStreamEmitter);
                } catch (Exception ignore) {
                    logSuppressed("stream.chainRunner");
                }
                // Emit an initial thought event so the client knows the agent has started
                // processing.
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("嶺뚳퐣瑗?怨?ご???戮곗굚??紐껊퉵??* ... *&#47;")));
                }

                // 3) ??⑤객臾?
                // Broadcast both status and thought updates so that the UI can display the
                // same message in the status line and the thought process panel. Each
                // call to status() is immediately followed by a corresponding call to
                // thought() with the same message to satisfy the requirement of
                // streaming thought events for every step of the agent???work.
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.status("?臾믩닑???釉뚯뫒??繞?* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("?臾믩닑???釉뚯뫒??繞?* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.status("????瑜곷턄??곗뒧????롪틵???繞벿뮻??* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("????瑜곷턄??곗뒧????롪틵???繞벿뮻??* ... *&#47;")));
                }

                // 4) ???롪틵????怨뺣뾼??
                // ???롪틵???? 嶺뚣끉裕뉏펺?useWebSearch ????뗥윜?__finalUseWeb)?띠럾? true???굿??롪틵???嶺뚮ㅄ維獄?쑚泥? OFF?띠럾? ?熬곣뫀鍮????異???臾먮뺄??類ｋ펲.
                // 嶺뚳퐘維? 嶺뚯쉶?꾣룇???롪퍔???__finalUseWeb??false???????롪틵?????節띉????紐꾩끋??類ｋ펲.
                int topKParam = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
                com.example.lms.gptsearch.dto.SearchMode sm = dto.getSearchMode();
                // treat null as AUTO for compatibility
                if (sm == null)
                    sm = com.example.lms.gptsearch.dto.SearchMode.AUTO;
                final boolean allowWeb = __finalUseWeb && sm != com.example.lms.gptsearch.dto.SearchMode.OFF;
                NaverSearchService.SearchResult sr;
                NaverSearchService.SearchTrace rawTrace = null;
                List<String> rawSnips = java.util.Collections.emptyList();
                String traceHtml = null;
                if (allowWeb) {
                    // Signal that we are planning and executing a search
                    if (debug) {
                        sink.tryEmitNext(sse(ChatStreamEvent.status("?롪틵?????ｌ뫓????濡〓뎡/* ... *&#47;")));
                    }
                    // Execute live search with trace enabled (Hybrid provider handles fallback
                    // internally)
                    sr = webSearchProvider.searchWithTrace(dto.getMessage(), topKParam);

                    rawTrace = sr.trace();
                    rawSnips = (sr.snippets() == null) ? java.util.Collections.emptyList() : sr.snippets();

                    if (sr.snippets() == null || sr.snippets().isEmpty()) {
                        log.info("[ChatApi] All search providers failed. RAG-only fallback.");
                    }

                    if (rawTrace != null) {
                        // (A) Raw web snippets are shown immediately.
                        // (B) Final TopK context is added later after the chat workflow finishes.
                        try {
                            traceHtml = traceHtmlBuilder.buildSplitPanel(rawTrace, rawSnips, null, null);
                        } catch (Exception e) {
                            traceHtml = "";
                            logSuppressed("stream.traceHtml.prefetch");
                        }
                        if ((debug || exposeTrace) && traceHtml != null && !traceHtml.isBlank()) {
                            if (debug || exposeTrace) {
                                sink.tryEmitNext(sse(ChatStreamEvent.trace(traceHtml,
                                        ChatStreamSignalBuilder.buildTraceSignal(TraceStore.getAll(), __capturedTrace, __capturedRequestId, currentSessionKeyHolder[0]))));
                            }
                        }
                    }
                } else {
                    // Skip web search entirely and inform the client
                    sr = new NaverSearchService.SearchResult(List.of(), null);
                    if (debug) {
                        sink.tryEmitNext(sse(ChatStreamEvent.status("web search skipped")));
                    }
                }

                final NaverSearchService.SearchResult srFinal = sr;
                // 5) ???筌뤾쑵??
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.status("??瑜곷턄??곗뒧????롪틵?????????????쳜????덈콦 ??뚮봽??* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("??瑜곷턄??곗뒧????롪틵?????????????쳜????덈콦 ??뚮봽??* ... *&#47;")));
                }
                ChatRequestDto dtoForCall = dto.toBuilder()
                        .sessionId(session.getId())
                        .useRag(__finalUseRag)
                        // Override useWebSearch based on attachment heuristic + plan cap
                        .useWebSearch(__finalUseWeb)
                        .build();

                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.status("??? ??諛댁뎽 繞?* ... *&#47;")));
                }
                if (debug) {
                    sink.tryEmitNext(sse(ChatStreamEvent.thought("??? ??諛댁뎽 繞?* ... *&#47;")));
                }
                // ChatWorkflow may change guard flags (officialOnly/domainProfile/minCitations) after this controller
                // prefetches web snippets (e.g. hatches / strictness adjustments). In that case, re-run
                // web search lazily so the evidence path stays consistent.
                final java.util.List<String> __prefetched = (srFinal == null ? java.util.List.of() : srFinal.snippets());
                final boolean __prefetchOfficial = gctx != null && gctx.isOfficialOnly();
                final String __prefetchDomainProfile = (gctx == null ? null : gctx.getDomainProfile());
                final Integer __prefetchMinCitations = (gctx == null ? null : gctx.getMinCitations());

                java.util.function.Function<String, java.util.List<String>> __webSupplier = (q) -> {
                    GuardContext __ctx;
                    try {
                        __ctx = GuardContextHolder.get();
                    } catch (Exception ignore) {
                        __ctx = null;
                        logSuppressed("stream.guardContext.webSupplier");
                    }

                    boolean __nowOfficial = (__ctx != null) ? __ctx.isOfficialOnly() : __prefetchOfficial;
                    String __nowDomainProfile = (__ctx != null) ? __ctx.getDomainProfile() : __prefetchDomainProfile;
                    Integer __nowMinCitations = (__ctx != null) ? __ctx.getMinCitations() : __prefetchMinCitations;

                    if (__nowOfficial == __prefetchOfficial
                            && java.util.Objects.equals(__nowDomainProfile, __prefetchDomainProfile)
                            && java.util.Objects.equals(__nowMinCitations, __prefetchMinCitations)) {
                        return __prefetched;
                    }

                    tracePut("chatApi.web.prefetch.invalidated", true);

                    try {
                        return webSearchProvider.search(q, topKParam);
                    } catch (Exception e) {
                        log.warn("[webSupplier] re-search failed; falling back to prefetched snippets: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                        return __prefetched;
                    }
                };

                // ChatResult is a top-level record (extracted from ChatService).
                emitDefaultModelWaitStatus(sink, __capturedBudget, __streamStartedNs);
                try {
                    debugCopilotService.maybeEnrichTrace();
                    java.util.Map<String, Object> preLlmMeta = TraceStore.getAll();
                    promoteDebugEvents("pre_llm", preLlmMeta, "ChatApiController.stream.preLlm");
                    ChatStreamEvent.TraceSignal preLlmTraceSignal =
                            ChatStreamSignalBuilder.buildTraceSignal(
                                    preLlmMeta, __capturedTrace, __capturedRequestId, currentSessionKeyHolder[0]);
                    ChatStreamEvent.PipelineSnapshot preLlmPipelineSnapshot =
                            ChatStreamSignalBuilder.buildPipelineSnapshot(preLlmMeta, null, null, preLlmTraceSignal);
                    ChatStreamEvent preLlmDebugFxEvent =
                            buildDebugFxEvent(preLlmMeta, preLlmTraceSignal, preLlmPipelineSnapshot);
                    if (preLlmDebugFxEvent != null) {
                        sink.tryEmitNext(sse(preLlmDebugFxEvent));
                        sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                                ChatStreamSignalBuilder.buildTransformerBlocks(
                                        preLlmMeta,
                                        null,
                                        preLlmPipelineSnapshot,
                                        preLlmTraceSignal,
                                        null,
                                        preLlmDebugFxEvent.debugFxSignal()))));
                    }
                } catch (Exception ignore) {
                    logSuppressed("stream.preLlmDebugFx");
                }
                ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);
                String finalText = result.content();

                // Defensive: never stream an empty answer (would render as a blank bubble in the UI).
                // Even if upstream LLM fails or a post-processor trims everything, ensure the client
                // receives a user-visible fallback.
                if (finalText == null || finalText.isBlank()) {
                    tracePut("chatApi.emptyFinalText", true);
                    finalText = "??? ??諛댁뎽 繞?????얜Ŧ堉???꾩룇裕뉑틦???곕????덈펲. ???섎?嶺뚯쉶?꾣룇??怨쀬Ŧ ???곕뻣 ??類ｌ┣???낅슣?섋땻??";
                }

                if (result.evidenceMetadata() != null && !result.evidenceMetadata().isEmpty()) {
                    sink.tryEmitNext(sse(ChatStreamEvent.evidence(result.evidenceMetadata())));
                }

                // (UI) answer.mode for fallback badges
                String answerModeFinal = null;

                // After the workflow finishes, pull the *actual* TopK evidence
                // sets used to build the prompt (web rerank + vector/RAG). This
                // allows the UI to show (A) raw web snippets and (B) final context
                // without re-running retrieval.
                LearningContextMetadata learningContextMeta = LearningContextMetadata.empty();
                ChatStreamEvent.PipelineSnapshot finalPipelineSnapshot = null;
                String traceHtmlForSnapshot = null;
                java.util.Map<String, Object> traceMetaForSnapshot = java.util.Map.of();
                java.util.Map<String, Object> finalTransformerMeta = java.util.Map.of();
                try {
                    // Preserve "enabled" signal: null means disabled, empty list means enabled but
                    // no results.
                    try { debugCopilotService.maybeEnrichTrace(); } catch (Exception ignore) { logSuppressed("debugCopilot.maybeEnrichTrace"); }
                    try { com.example.lms.trace.AblationContributionTracker.finalizeTraceIfNeeded(); } catch (Exception ignore) { logSuppressed("ablation.finalizeTrace"); }
                    java.util.Map<String, Object> extraMeta = TraceStore.getAll();
                    learningContextMeta = LearningContextMetadata.fromTrace(extraMeta);

                    // Capture answer mode (fail-soft). Used for UI fallback badges.
                    try {
                        Object __am = extraMeta.get("answer.mode");
                        if (__am != null) {
                            String __s = String.valueOf(__am).trim();
                            if (!__s.isBlank()) answerModeFinal = __s;
                        }
                        if (answerModeFinal == null || answerModeFinal.isBlank()) {
                            String __mu = result.modelUsed();
                            if (__mu != null && __mu.toLowerCase(java.util.Locale.ROOT).contains("fallback:evidence")) {
                                answerModeFinal = "FALLBACK_EVIDENCE";
                            }
                        }
                    } catch (Exception ignoreMode) {
                        logSuppressed("stream.answerMode");
                    }

                    try {
                        java.util.List<String> failureTags = FailureTagNormalizer.normalize(extraMeta, result.modelUsed(), null);
                        if (failureTags != null && !failureTags.isEmpty()) {
                            extraMeta.put("failureTags", failureTags);
                        }
                    } catch (Exception ignoreTags) {
                        logSuppressed("stream.failureTags");
                    }
                    promoteDebugEvents("final", extraMeta, "ChatApiController.stream.final");
                    ChatStreamEvent.TraceSignal finalTraceSignal =
                            ChatStreamSignalBuilder.buildTraceSignal(extraMeta, __capturedTrace, __capturedRequestId, currentSessionKeyHolder[0]);
                    finalPipelineSnapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(extraMeta, answerModeFinal, null, finalTraceSignal);
                    ChatStreamEvent finalDebugFxEvent =
                            buildDebugFxEvent(extraMeta, finalTraceSignal, finalPipelineSnapshot);
                    if (finalDebugFxEvent != null) {
                        sink.tryEmitNext(sse(finalDebugFxEvent));
                    }
                    ChatStreamEvent.ScoreDeltaSignal finalScoreDeltaSignal = ChatStreamSignalBuilder.buildScoreDeltaSignal(extraMeta);
                    if (finalScoreDeltaSignal != null) {
                        sink.tryEmitNext(sse(ChatStreamEvent.scoreDelta(finalScoreDeltaSignal)));
                    }
                    finalTransformerMeta = java.util.Map.copyOf(extraMeta);
                    sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                            ChatStreamSignalBuilder.buildTransformerBlocks(
                                    extraMeta,
                                    null,
                                    finalPipelineSnapshot,
                                    finalTraceSignal,
                                    finalScoreDeltaSignal,
                                    finalDebugFxEvent == null ? null : finalDebugFxEvent.debugFxSignal()))));
                    boolean finalTraceSignalEmitted = false;
                    java.util.List<Content> finalWebTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalWebTopK"));
                    java.util.List<Content> finalVectorTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalVectorTopK"));

                    // Console diagnostics: dump search trace + planner meta without exposing it to the client
                    try {
                        searchTraceConsoleLogger.maybeLog("stream", rawTrace, rawSnips, finalWebTopK, finalVectorTopK, extraMeta);
                    } catch (Exception ignoreLog) {
                        logSuppressed("stream.searchTraceConsole");
                    }

                    // clear to avoid cross-request contamination
                    TraceStore.clear();

                    if (rawTrace != null) {
                        String finalTraceHtml = traceHtmlBuilder.buildSplitPanel(rawTrace, rawSnips,
                                finalWebTopK,
                                finalVectorTopK,
                                extraMeta);
                        if (finalTraceHtml != null && !finalTraceHtml.isBlank()) {
                            traceHtml = finalTraceHtml;
                            // Emit again: streaming UI will replace the existing panel.
                            if (debug || exposeTrace) {
                                sink.tryEmitNext(sse(ChatStreamEvent.trace(finalTraceHtml, finalTraceSignal, finalPipelineSnapshot)));
                                finalTraceSignalEmitted = true;
                            }

                            java.util.Map<String, Object> snapMeta = new java.util.LinkedHashMap<>(
                                    extraMeta == null ? java.util.Map.of() : extraMeta);
                            snapMeta.put("ui.traceHtml.kind", "splitPanel");
                            snapMeta.put("ui.traceHtml.length", finalTraceHtml.length());
                            traceMetaForSnapshot = snapMeta;
                            traceHtmlForSnapshot = finalTraceHtml;
                        }
                    }
                    if (!finalTraceSignalEmitted && finalTraceSignal != null) {
                        sink.tryEmitNext(sse(ChatStreamEvent.trace(null, finalTraceSignal, finalPipelineSnapshot)));
                    }
                } catch (Exception ignore) {
                    logSuppressed("stream.finalTraceMeta");
                    try {
                        TraceStore.clear();
                    } catch (Exception ignore2) {
                        logSuppressed("stream.finalTraceMeta.clear");
                    }
                }

                // 6) ??ルㅎ荑????덈콦?洹먮맩鍮?嶺?野?
                for (String c : chunk(finalText, 60)) {
                    sink.tryEmitNext(sse(ChatStreamEvent.token(c)));
                }

                // 7) ?筌뤾쑬??????+ 嶺뚮ㅄ維???筌뤾퍔???怨룸츩 嶺뚮∥??
                Long assistantMessageId = historyService.appendMessageReturningId(session.getId(), "assistant", finalText);
                updateRollingSummaryAndMaybePromote(session.getId(), assistantMessageId, req);

                String modelUsedFinal = ChatModelMetaSupport.resolveModelUsed(result.modelUsed(), dto.getModel(), FALLBACK_MODEL);
                boolean streamCancelledFinal = "cancelled".equalsIgnoreCase(ChatModelMetaSupport.safeTrim(result.modelUsed()))
                        || "cancelled".equalsIgnoreCase(ChatModelMetaSupport.safeTrim(modelUsedFinal));

                historyService.appendMessage(session.getId(), "system",
                        String.format("%s%s", MODEL_META_PREFIX, modelUsedFinal));

                Long traceTurnId = ChatTraceSnapshotPointerPersister.persist(
                        session.getId(),
                        "chat.trace_html.final",
                        "SSE",
                        (__httpPath == null ? "/api/chat/stream" : __httpPath),
                        traceMetaForSnapshot,
                        traceHtmlForSnapshot,
                        traceSnapshotStore,
                        historyService,
                        log);
                finalPipelineSnapshot = ChatStreamSignalBuilder.withTraceTurnId(finalPipelineSnapshot, answerModeFinal, traceTurnId);

                // Persist answer.mode + traceTurnId snapshot so that the session list can be
                // restored cross-device and the sidebar can auto-open the exact trace panel.
                try {
                    historyService.updateSessionAnswerModeAndTrace(session.getId(), answerModeFinal, traceTurnId);
                } catch (Exception ignore) {
                    logSuppressed("stream.answerModeTracePersist");
                }

                try {
                    Long remainingMs = __capturedBudget == null ? null : __capturedBudget.remainingMillis();
                    long tookMs = Math.max(0L, (System.nanoTime() - __streamStartedNs) / 1_000_000L);
                    ChatStreamEvent.StatusSignal completeSignal = ChatStreamEvent.StatusSignal.of(
                            "stream",
                            streamCancelledFinal ? "cancelled" : "complete",
                            streamCancelledFinal ? "stream cancelled" : "stream complete",
                            remainingMs,
                            tookMs,
                            streamCancelledFinal);
                    sink.tryEmitNext(sse(ChatStreamEvent.status(completeSignal)));
                    sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                            ChatStreamSignalBuilder.buildTransformerBlocks(
                                    finalTransformerMeta,
                                    completeSignal,
                                    finalPipelineSnapshot,
                                    null,
                                    null,
                                    null))));
                } catch (Throwable ignore) {
                    logSuppressed("stream.status.complete");
                }

                sink.tryEmitNext(sse(ChatStreamEvent.done(
                        modelUsedFinal,
                        result.ragUsed(),
                        session.getId(),
                        answerModeFinal,
                        traceTurnId,
                        learningContextMeta,
                        result.evidenceMetadata(),
                        finalPipelineSnapshot)));
            } catch (Exception ex) {
                log.error("[AWX][chat] stream-failed type={} error={}", ex.getClass().getSimpleName(), String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(ex)), String.valueOf(ex).length()));
                // Avoid direct string concatenation when building error messages
                String errMsg = String.format("???댁쾼 errorHash=%s errorLength=%d", SafeRedactor.hashValue(ex.getMessage()), ex.getMessage() == null ? 0 : ex.getMessage().length());
                try {
                    Long remainingMs = __capturedBudget == null ? null : __capturedBudget.remainingMillis();
                    long tookMs = Math.max(0L, (System.nanoTime() - __streamStartedNs) / 1_000_000L);
                    ChatStreamEvent.StatusSignal errorSignal = ChatStreamEvent.StatusSignal.of(
                            "stream", "error", "stream error", remainingMs, tookMs, false);
                    sink.tryEmitNext(sse(ChatStreamEvent.status(errorSignal)));
                    sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                            ChatStreamSignalBuilder.buildTransformerBlocks(
                                    java.util.Map.of(),
                                    errorSignal,
                                    null,
                                    null,
                                    null,
                                    null))));
                } catch (Throwable ignore) {
                    logSuppressed("stream.status.error");
                }
                sink.tryEmitNext(sse(ChatStreamEvent.error(errMsg)));
            } finally {
                // ?????댁읉??? ??亦???????쳜????덈콦 ?熬곣뫖???꾩렮維?
                GuardContextHolder.clear();
                try {
                    TraceContext.cleanupCurrentThread();
                } catch (Throwable ignore) {
                    logSuppressed("traceContext.cleanup");
                }
                try {
                    if (__capturedBudget != null) {
                        com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
                    }
                } catch (Throwable ignore) {
                    logSuppressed("timeBudget.clear");
                }
                try {
                    if (retrievalDiagnosticsCollector != null) {
                        retrievalDiagnosticsCollector.reset();
                    }
                } catch (Throwable ignore) {
                    logSuppressed("retrievalDiagnostics.reset");
                }
                // ?熬곣뫁???SSE sink ?繹먮굞夷???怨몄젷 ?????덈콦???熬곣뫁??
                try {
                    String sKey = currentSessionKeyHolder[0];
                    if (sKey != null) {
                        chatStreamEmitter.unregisterSink(sKey);
                    }
                } catch (Throwable t) {
                    log.debug("Failed to unregister SSE sink: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(t)), String.valueOf(t).length()));
                }
                sink.tryEmitComplete();
            }
        }
        })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        // Build the response flux with cancellation, error and finalisation hooks. Do
        // not
        // return immediately so that we can attach Reactor context below.
        Flux<ServerSentEvent<ChatStreamEvent>> flux = sink.asFlux()
                .doOnCancel(() -> {
                    // Browser disconnect is a detach, not a user cancel. Keep the
                    // background generation and replay sink alive so attach/resume works.
                    Long sid = currentSessionId.get();
                    // Do not call chatService.cancelSession(sid) here; cancellation
                    // is now explicit via the /api/chat/cancel endpoint. This
                    // preserves in-flight runs for reconnection.
                    try {
                        String sKey = currentSessionKeyHolder[0];
                        if (sKey != null)
                            chatStreamEmitter.unregisterSink(sKey);
                    } catch (Throwable ignore) {
                        logSuppressed("sse.detach.unregisterSink");
                    }
                    try {
                        if (sid != null) {
                            TraceStore.put("chat.sse.detach", true);
                            TraceStore.put("chat.sse.resumePreserved", true);
                        }
                    } catch (Throwable ignore) {
                        logSuppressed("sse.detach.trace");
                    }
                    log.info("SSE stream detached by client (sessionHash={}, resumePreserved=true)", sid == null ? null : SafeRedactor.hashValue(String.valueOf(sid)));
                })
                .doOnError(e -> log.warn("SSE stream error (sessionHash={}): {}",
                        SafeRedactor.hashValue(String.valueOf(currentSessionId.get())), errorSummary(e)))
                .doFinally(sig -> {
                    // In all termination scenarios (CANCEL/ERROR/ON_COMPLETE) ensure that
                    // the per-session SSE sink is unregistered for this subscriber. The
                    // replay sink remains active for attach() until markDone() or
                    // markCancelled() is invoked via the run registry.
                    try {
                        String sKey = currentSessionKeyHolder[0];
                        if (sKey != null)
                            chatStreamEmitter.unregisterSink(sKey);
                    } catch (Throwable ignore) {
                        logSuppressed("sse.finally.unregisterSink");
                    }
                });
        // Attach the captured client IP to the Reactor context to allow downstream
        // components to derive the caller identity on non-request threads.
        return flux.contextWrite(Context.of("clientIp", clientIp));
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            TraceStore.put("chat.api.suppressed.parse.asLong", true);
            TraceStore.put("chat.api.suppressed.parse.asLong.errorType", "invalid_number");
            logSuppressed("parse.asLong");
            return null;
        }
    }

    private static Long longFromBody(Map<String, Object> body, String key) {
        if (body == null || key == null) {
            return null;
        }
        return asLong(body.get(key));
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean canAccessSession(ChatSession session, Authentication authentication) {
        if (session == null) {
            return false;
        }
        if (isAdmin(authentication)) {
            return true;
        }
        String username = authentication != null && authentication.isAuthenticated() ? authentication.getName() : null;
        var owner = session.getAdministrator();
        if (owner != null) {
            return username != null && owner.getUsername().equals(username);
        }
        String currentKey = ownerKeyResolver.ownerKey();
        return session.getOwnerKey() != null && session.getOwnerKey().equals(currentKey);
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static ServerSentEvent<ChatStreamEvent> sse(ChatStreamEvent e) {
        return ServerSentEvent.<ChatStreamEvent>builder(e).event(e.type()).build();
    }

    static ChatStreamEvent buildDebugFxEvent(
            java.util.Map<String, Object> meta,
            ChatStreamEvent.TraceSignal traceSignal,
            ChatStreamEvent.PipelineSnapshot pipelineSnapshot) {
        ChatStreamEvent.DebugFxSignal signal =
                ChatStreamSignalBuilder.buildDebugFxSignal(meta, traceSignal, pipelineSnapshot);
        return signal == null ? null : ChatStreamEvent.debugFx(signal);
    }

    private static void emitDefaultModelWaitStatus(
            Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
            com.abandonware.ai.addons.budget.TimeBudget budget,
            long streamStartedNs) {
        if (sink == null) {
            return;
        }
        try {
            Long remainingMs = budget == null ? null : budget.remainingMillis();
            long tookMs = Math.max(0L, (System.nanoTime() - streamStartedNs) / 1_000_000L);
            ChatStreamEvent.StatusSignal waitSignal = ChatStreamEvent.StatusSignal.of(
                    "llm",
                    DEFAULT_MODEL_WAIT_STATUS_CODE,
                    DEFAULT_MODEL_WAIT_STATUS_MESSAGE,
                    remainingMs,
                    tookMs,
                    false);
            TraceStore.put("chat.stream.defaultModel.waitStatus", true);
            TraceStore.put("chat.stream.defaultModel.waitStatus.code", DEFAULT_MODEL_WAIT_STATUS_CODE);
            java.util.Map<String, Object> waitTransformerMeta = java.util.Map.of(
                    "llm.defaultModel.waitStatus", true,
                    "llm.defaultModel.waitStatus.code", DEFAULT_MODEL_WAIT_STATUS_CODE);
            sink.tryEmitNext(sse(ChatStreamEvent.status(waitSignal)));
            sink.tryEmitNext(sse(ChatStreamEvent.thought(DEFAULT_MODEL_WAIT_STATUS_MESSAGE)));
            sink.tryEmitNext(sse(ChatStreamEvent.transformer(
                    ChatStreamSignalBuilder.buildTransformerBlocks(
                            waitTransformerMeta, waitSignal, null, null, null, null))));
        } catch (Throwable ignore) {
            logSuppressed("stream.status.defaultModelWait");
        }
    }

    private static List<String> chunk(String s, int size) {
        if (s == null)
            return List.of();
        int n = Math.max(1, size);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += n) {
            out.add(s.substring(i, Math.min(s.length(), i + n)));
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static void applyRequestSessionId(ChatRequestDto req, String sessionIdHeader, String conversationIdHeader) {
        if (req == null || req.getSessionId() != null) {
            return;
        }
        Long resolved = normalizeChatSessionId(firstNonBlank(sessionIdHeader, conversationIdHeader));
        if (resolved != null) {
            req.setSessionId(resolved);
        }
    }

    private static Long normalizeChatSessionId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, "chat-", 0, 5)) {
            value = value.substring(5).trim();
        }
        if (!value.matches("\\d+")) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignore) {
            logSuppressed("parse.normalizeChatSessionId");
            return null;
        }
    }

    private static void traceSessionNotFound(Long sessionId) {
        try {
            TraceStore.put("memory.rehydrate.reason", "session_not_found");
            if (sessionId != null) {
                TraceStore.put("memory.rehydrate.sessionHash", SafeRedactor.hash12(String.valueOf(sessionId)));
            }
        } catch (Throwable ignore) {
            logSuppressed("memory.rehydrate.traceSessionNotFound");
        }
    }

    private void updateRollingSummaryAndMaybePromote(
            Long sessionId,
            Long assistantMessageId,
            ChatRequestDto request) {
        if (sessionId == null) {
            return;
        }
        String previousPromotionHash = "";
        try {
            previousPromotionHash = historyService.getConversationMemorySnapshot(sessionId).promotionHash();
        } catch (Exception ignore) {
            logSuppressed("memory.rollingSummary.previousSnapshot");
        }
        try {
            historyService.updateRollingSummary(sessionId, assistantMessageId);
        } catch (Exception ignore) {
            logSuppressed("memory.rollingSummary.update");
            traceShadowVectorQueued(false);
            return;
        }
        boolean queued = false;
        try {
            ChatHistoryService.ConversationMemorySnapshot snapshot =
                    historyService.getConversationMemorySnapshot(sessionId);
            boolean promoted = snapshot.promoted();
            String nextPromotionHash = snapshot.promotionHash() == null ? "" : snapshot.promotionHash();
            boolean changed = promoted && !nextPromotionHash.isBlank()
                    && !java.util.Objects.equals(previousPromotionHash, nextPromotionHash);
            com.example.lms.domain.enums.MemoryMode memoryMode =
                    com.example.lms.domain.enums.MemoryMode.fromString(request == null ? null : request.getMemoryMode());
            if (sessionSummaryShadowVectorEnabled
                    && changed
                    && memoryMode != com.example.lms.domain.enums.MemoryMode.EPHEMERAL
                    && memoryReinforcementService != null
                    && snapshot.hasMemory()) {
                String sessionKey = String.format("chat-%s", sessionId);
                queued = memoryReinforcementService.reinforceSessionSummaryShadow(
                        sessionKey,
                        snapshot.shadowSnippet(),
                        nextPromotionHash,
                        sessionSummaryShadowVectorScore);
            }
        } catch (Exception ignore) {
            logSuppressed("memory.rollingSummary.shadowVector");
            queued = false;
        }
        traceShadowVectorQueued(queued);
    }

    private static void traceShadowVectorQueued(boolean queued) {
        try {
            TraceStore.put("memory.session.shadowVectorQueued", queued);
        } catch (Throwable ignore) {
            logSuppressed("memory.shadowVectorQueued.trace");
        }
    }

    // ===== internal =====
    /**
     * Overload that wires Jammini / guard headers into a GuardContext and exposes
     * it
     * via {@link GuardContextHolder} for downstream services (RAG, search, memory).
     */
    private ChatResponseDto handleChat(ChatRequestDto uiReq,
            String username,
            String clientIp,
            String preResolvedOwnerKey,
            String jamminiMode,
            String guardLevel) {
        GuardContext ctx = GuardContext.defaultContext();
        if (jamminiMode != null && !jamminiMode.isBlank()) {
            ctx.setHeaderMode(jamminiMode);
            ctx.setMode(jamminiMode);
            // Simple plan mapping; can be refined to safe_autorun.v1 / brave.v1 etc.
            ctx.setPlanId(jamminiMode);
            if ("S1".equalsIgnoreCase(jamminiMode) || "safe".equalsIgnoreCase(jamminiMode)) {
                ctx.setMemoryProfile("MEMORY");
            } else if ("S2".equalsIgnoreCase(jamminiMode)
                    || "brave".equalsIgnoreCase(jamminiMode)
                    || "free".equalsIgnoreCase(jamminiMode)
                    || "zero_break".equalsIgnoreCase(jamminiMode)) {
                ctx.setMemoryProfile("NONE");
            }
        }
        if (guardLevel != null && !guardLevel.isBlank()) {
            ctx.setGuardLevel(guardLevel);
        }
        if (uiReq != null && uiReq.getMessage() != null) {
            ctx.setEntityQueryFromQuestion(uiReq.getMessage());
			// UAW: propagate raw user query for downstream orchestration/unmasking/autolearn hooks
			ctx.setUserQuery(uiReq.getMessage());
        }
        try {
            sensitiveTopicDetector.applyTo(ctx, uiReq);
        } catch (Exception ignore) {
            logSuppressed("sensitiveTopic.apply");
        }

        GuardContextHolder.set(ctx);
        try {
            return handleChat(uiReq, username, clientIp, preResolvedOwnerKey);
        } finally {
            GuardContextHolder.clear();
        }
    }

    private ChatResponseDto handleChat(ChatRequestDto uiReq, String username, String clientIp,
            String preResolvedOwnerKey) {
        com.example.lms.debug.DebugEventTracePromotionService.seedRequestedExternalEvidenceLanes(
                uiReq == null ? null : uiReq.getMessage());
        // 0) ?熬곣뫚????쒖굣???LLM ?筌뤾쑵????怨몄쓧???브퀗?쀧뵳???얜Ŧ堉?嶺뚳퐣瑗???類ｋ펲. ?띠룆흮????롪퍔???
        // consent, last location and reverse geocoding are evaluated via
        // LocationService.
        try {
            com.example.lms.location.intent.LocationIntent intent = locationService.detectIntent(uiReq.getMessage());
            if (intent == com.example.lms.location.intent.LocationIntent.WHERE_AM_I) {
                // Resolve the user identifier for the location lookup. Prefer the
                // authenticated principal's username (passed as 'username') and fall back
                // to any identifier encoded in the request if such a property exists.
                String userId = (username != null && !username.isBlank()) ? username : null;
                var msgOpt = locationService.answerWhereAmI(userId);
                if (msgOpt.isPresent()) {
                    // Immediate deterministic response; avoid session creation and web search.
                    return new ChatResponseDto(msgOpt.get(), null, "location:deterministic", false);
                }
                // When the personalised location message cannot be produced (no consent,
                // missing coordinate etc.), continue to the standard flow below.
            }
        } catch (Exception e) {
            // Log but do not interrupt the standard chat flow
            log.debug("handleChat: location interception failed: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
        }

        // 1) ???깆젧 ?곌랜理묌뜮?
        ChatRequestDto dto = mergeWithSettings(uiReq);
        final boolean __hasAttachments = dto.getAttachmentIds() != null && !dto.getAttachmentIds().isEmpty();
        final boolean __looksLikeAttachmentQ =
                ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(dto.getMessage());
        final boolean __hasDocumentEvidence = __hasAttachments && __looksLikeAttachmentQ;

        // DROP: apply plan selection + guard hints BEFORE web prefetch/search.
        PlanHints __planHints = null;
        boolean __allowWebCap = true;
        boolean __allowRagCap = true;
        try {
            GuardContext __gctx = GuardContextHolder.get();
            if (__gctx != null) {
                AnswerMode __am = AnswerMode.fromString(dto.getMode());
                QueryDomain __qd = (__gctx.isSensitiveTopic()) ? QueryDomain.SENSITIVE : QueryDomain.GENERAL;
                if (workflowOrchestrator != null) {
                    workflowOrchestrator.ensurePlanSelected(__gctx, __am, __qd, dto.getMessage(), __hasDocumentEvidence);
                }
                if (planHintApplier != null && __gctx.getPlanId() != null) {
                    __planHints = planHintApplier.load(__gctx.getPlanId());
                    planHintApplier.applyToGuardContext(__planHints, __gctx);
                }
            }
            __allowWebCap = (__planHints == null || __planHints.allowWeb() != Boolean.FALSE);
            __allowRagCap = (__planHints == null || __planHints.allowRag() != Boolean.FALSE);
            TraceStore.put("plan.id.preSearch", (__gctx == null ? null : __gctx.getPlanId()));
            TraceStore.put("plan.allowWeb.cap", __allowWebCap);
            TraceStore.put("plan.allowRag.cap", __allowRagCap);
        } catch (Exception ignorePlan) {
            logSuppressed("plan.preSearch");
        }

        // === 嶺뚳퐘維? ???쳜????덈콦 ?낅슣??????獄??????吏?OFF ===
        // Build a composed message by injecting attachment contents before the user
        // question. When
        // the user specifically references the uploaded file(s) the web search flag is
        // forced
        // off. Use dynamic limits from settings with sensible fallbacks for document
        // count,
        // bytes and character thresholds.
        // attachments.inline.legacy-prepend-enabled is deprecated/no-op. Attachment evidence
        // flows through ChatWorkflow -> PromptContext.localDocs -> PromptBuilder.build(ctx).
        boolean __reqUseWeb = Boolean.TRUE.equals(dto.isUseWebSearch());
        boolean __finalUseWeb = (__hasAttachments && __looksLikeAttachmentQ) ? false : __reqUseWeb;
        // Plan cap: allowWeb/allowRag
        __finalUseWeb = __finalUseWeb && __allowWebCap;
        final boolean __finalUseRag = __allowRagCap && Boolean.TRUE.equals(dto.isUseRag());

        // 2) ?筌뤾쑬??upsert
        ChatSession session = (uiReq.getSessionId() == null)
                ? historyService
                        .startNewSession(dto.getMessage(), username, clientIp, preResolvedOwnerKey,
                                dto.getMemoryProfile())
                        .orElseThrow(() -> new IllegalStateException("?筌뤾쑬????諛댁뎽 ???덉넮"))
                : historyService.getSessionWithMessages(uiReq.getSessionId());

        // [PATCH] ??μ쪠???筌뤾쑬???꾩렮維쀥젆??β돦裕뉐퐲??怨뺣뼺?
        if (session == null && uiReq.getSessionId() != null) {
            traceSessionNotFound(uiReq.getSessionId());
            log.warn("Requested session was not found; creating a new session. sessionHash={}",
                    SafeRedactor.hash12(String.valueOf(uiReq.getSessionId())));
            session = historyService
                    .startNewSession(dto.getMessage(), username, clientIp, preResolvedOwnerKey, dto.getMemoryProfile())
                    .orElseThrow(() -> new IllegalStateException("?筌뤾쑬???곌랜踰????諛댁뎽 ???덉넮"));
        }


        // [PATCH] Ensure MDC/TraceStore session breadcrumbs follow the resolved chat session.
        try {
            if (session != null && session.getId() != null) {
                String __s = String.valueOf(session.getId());
                String __sessionKey = __s.startsWith("chat-") ? __s : (__s.matches("\\d+") ? String.format("chat-%s", __s) : __s);
                try {
                    org.slf4j.MDC.put("sid", __sessionKey);
                    org.slf4j.MDC.put("sessionId", __sessionKey);
                } catch (Throwable ignoreMdc) {
                    logSuppressed("sync.sessionBreadcrumb.mdc");
                }
                try {
                    com.example.lms.search.TraceStore.put("sid", SafeRedactor.hashValue(__sessionKey));
                } catch (Throwable ignoreTrace) {
                    logSuppressed("sync.sessionBreadcrumb.trace");
                }
            }
        } catch (Exception ignore) {
            logSuppressed("sync.sessionBreadcrumb.outer");
        }
        // [Jammini Memory Hook] session metadata merge/persist
        java.util.Map<String, Object> sessionMeta = mergeSessionMetaIntoRequest(session, uiReq);
        try {
            session.setSessionMeta(objectMapper.writeValueAsString(sessionMeta));
        } catch (Exception e) {
            log.warn("Failed to persist session_meta for session {}: {}",
                    SafeRedactor.hashValue(String.valueOf(session.getId())), errorSummary(e));
        }

        // Existing/recovered session: persist the user turn.
        if (session != null && uiReq.getSessionId() != null) {
            historyService.appendMessage(session.getId(), "user", dto.getMessage());
        }

        // 3) ???롪틵???        // ?롪틵???嶺뚮ㅄ維獄??ChatRequestDto.searchMode????臾먰돵 ??戮?꽑??類ｋ펲. OFF??????롪틵???源녿굵 濾곌쑬????⑤슦??
        // FORCE_LIGHT/DEEP??怨뺤┣ 嶺뚣끉裕뉏펺?useWebSearch?띠럾? false??????롪틵???源녿굵 濾곌쑬??????? AUTO 嶺뚮ㅄ維獄??????        // __finalUseWeb???잙갭梨????????類ｋ펲. topK??webTopK ?熬곣뫀援????臾먰돵 嶺뚯솘??筌먲퐢彛??
        boolean performSearch;
        int topKParam = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
        com.example.lms.gptsearch.dto.SearchMode sm = dto.getSearchMode();
        if (sm == null)
            sm = com.example.lms.gptsearch.dto.SearchMode.AUTO;
        switch (sm) {
            case OFF -> performSearch = false;
            case FORCE_LIGHT, FORCE_DEEP -> performSearch = __finalUseWeb;
            case AUTO -> performSearch = __finalUseWeb;
            default -> performSearch = __finalUseWeb;
        }
        NaverSearchService.SearchResult sr = performSearch
                ? webSearchProvider.searchWithTrace(dto.getMessage(), topKParam)
                : new NaverSearchService.SearchResult(List.of(), null);

        // 4) LLM ?筌뤾쑵??
        ChatRequestDto dtoForCall = dto.toBuilder()
                .sessionId(session.getId())
                .useRag(__finalUseRag)
                // Override useWebSearch with the final value after attachment heuristic + plan cap
                .useWebSearch(__finalUseWeb)
                .build();

        // ChatWorkflow may change guard flags (officialOnly/domainProfile/minCitations) after this controller
        // prefetches web snippets. In that case, re-run web search lazily.
        final NaverSearchService.SearchResult __srFinal = sr;
        final java.util.List<String> __prefetched = (__srFinal == null ? java.util.List.of() : __srFinal.snippets());

        GuardContext __prefetchCtx;
        try {
            __prefetchCtx = GuardContextHolder.get();
        } catch (Exception ignore) {
            __prefetchCtx = null;
            logSuppressed("sync.prefetchGuardContext");
        }
        final boolean __prefetchOfficial = __prefetchCtx != null && __prefetchCtx.isOfficialOnly();
        final String __prefetchDomainProfile = (__prefetchCtx == null ? null : __prefetchCtx.getDomainProfile());
        final Integer __prefetchMinCitations = (__prefetchCtx == null ? null : __prefetchCtx.getMinCitations());

        java.util.function.Function<String, java.util.List<String>> __webSupplier = (q) -> {
            GuardContext __ctx;
            try {
                __ctx = GuardContextHolder.get();
            } catch (Exception ignore) {
                __ctx = null;
                logSuppressed("sync.webSupplierGuardContext");
            }
            boolean __nowOfficial = (__ctx != null) ? __ctx.isOfficialOnly() : __prefetchOfficial;
            String __nowDomainProfile = (__ctx != null) ? __ctx.getDomainProfile() : __prefetchDomainProfile;
            Integer __nowMinCitations = (__ctx != null) ? __ctx.getMinCitations() : __prefetchMinCitations;

            if (__nowOfficial == __prefetchOfficial
                    && java.util.Objects.equals(__nowDomainProfile, __prefetchDomainProfile)
                    && java.util.Objects.equals(__nowMinCitations, __prefetchMinCitations)) {
                return __prefetched;
            }

            tracePut("chatApi.web.prefetch.invalidated", true);

            try {
                return webSearchProvider.search(q, topKParam);
            } catch (Exception e) {
                log.warn("[webSupplier] re-search failed; falling back to prefetched snippets: {}", String.format("errorHash=%s errorLength=%d", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()));
                return __prefetched;
            }
        };

        // ChatResult is a top-level record (extracted from ChatService).
        try {
            debugCopilotService.maybeEnrichTrace();
            java.util.Map<String, Object> preLlmMeta = TraceStore.getAll();
            promoteDebugEvents("pre_llm", preLlmMeta, "ChatApiController.sync.preLlm");
        } catch (Exception ignore) {
            logSuppressed("sync.preLlmDebugEvent");
        }
        ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);

        // 5) Persist assistant turn.
        Long assistantMessageId = historyService.appendMessageReturningId(session.getId(), "assistant", result.content());
        updateRollingSummaryAndMaybePromote(session.getId(), assistantMessageId, dtoForCall);

        String modelUsedFinal = ChatModelMetaSupport.resolveModelUsed(result.modelUsed(), dto.getModel(), FALLBACK_MODEL);

        historyService.appendMessage(session.getId(), "system",
                String.format("%s%s", MODEL_META_PREFIX, modelUsedFinal));

        // Pull the evidence sets captured by ChatWorkflow so that the saved trace
        // panel can show "raw web snippets" and "final context" separately.
        // Preserve "enabled" signal: null means disabled, empty list means enabled but
        // no results.
        java.util.Map<String, Object> extraMeta = java.util.Collections.emptyMap();
        String answerModeFinal = null;
        LearningContextMetadata learningContextMeta = LearningContextMetadata.empty();
        java.util.List<Content> finalWebTopK = null;
        java.util.List<Content> finalVectorTopK = null;
        try {
            extraMeta = TraceStore.getAll();
            learningContextMeta = LearningContextMetadata.fromTrace(extraMeta);
            // Capture answer mode (fail-soft). Used for UI fallback badges.
            try {
                Object __am = extraMeta.get("answer.mode");
                if (__am != null) {
                    String __s = String.valueOf(__am).trim();
                    if (!__s.isBlank()) answerModeFinal = __s;
                }
                if (answerModeFinal == null || answerModeFinal.isBlank()) {
                    String __mu = result.modelUsed();
                    if (__mu != null && __mu.toLowerCase(java.util.Locale.ROOT).contains("fallback:evidence")) {
                        answerModeFinal = "FALLBACK_EVIDENCE";
                    }
                }
            } catch (Exception ignoreMode) {
                logSuppressed("sync.answerMode");
            }

            try {
                java.util.List<String> failureTags = FailureTagNormalizer.normalize(extraMeta, result.modelUsed(), null);
                if (failureTags != null && !failureTags.isEmpty()) {
                    extraMeta.put("failureTags", failureTags);
                }
            } catch (Exception ignoreTags) {
                logSuppressed("sync.failureTags");
            }
            promoteDebugEvents("final", extraMeta, "ChatApiController.sync.final");
            finalWebTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalWebTopK"));
            finalVectorTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalVectorTopK"));

            // Console diagnostics: dump search trace + planner meta without exposing it to the client
            try {
                searchTraceConsoleLogger.maybeLog("sync", (sr == null ? null : sr.trace()), (sr == null ? null : sr.snippets()), finalWebTopK, finalVectorTopK, extraMeta);
            } catch (Exception ignoreLog) {
                logSuppressed("sync.searchTraceConsole");
            }
        } catch (Exception ignore) {
            logSuppressed("sync.finalTraceMeta");
        } finally {
            try {
                TraceStore.clear();
            } catch (Exception ignore2) {
                logSuppressed("sync.finalTraceMeta.clear");
            }}

        String traceHtmlForSnapshot = null;
        if (__finalUseWeb && sr.trace() != null) {
            String traceHtml = "";
            try {
                java.util.List<String> rawSnips = (sr.snippets() == null)
                        ? java.util.Collections.emptyList()
                        : sr.snippets();
                traceHtml = traceHtmlBuilder.buildSplitPanel(sr.trace(), rawSnips, finalWebTopK, finalVectorTopK, extraMeta);
            } catch (Exception ignore) {
                traceHtml = "";
                logSuppressed("sync.traceHtml.final");
            }
            if (traceHtml != null && !traceHtml.isBlank()) {
                traceHtmlForSnapshot = traceHtml;
            }
        }

        Long traceTurnId = ChatTraceSnapshotPointerPersister.persist(
                session.getId(),
                "chat.trace_html.final",
                "POST",
                "/api/chat",
                extraMeta,
                traceHtmlForSnapshot,
                traceSnapshotStore,
                historyService,
                log);

        // Persist answer.mode + traceTurnId snapshot for cross-device badges and deterministic trace open.
        try {
            historyService.updateSessionAnswerModeAndTrace(session.getId(), answerModeFinal, traceTurnId);
        } catch (Exception ignore) {
            logSuppressed("sync.answerModeTracePersist");
        }
        ChatStreamEvent.PipelineSnapshot syncPipelineSnapshot =
                ChatStreamSignalBuilder.buildPipelineSnapshot(extraMeta, answerModeFinal, traceTurnId, null);
        // (sync path) 嶺뚯빘鍮볠뤃?????逾?? SSE ?熬곣뫗??sink)??????????類ｋ츎 ??紐꾩끋.
        // ?熬곣뫗????ChatResponseDto??evidence ?熬곣뫀援???怨뺣뼺?????얜Ŧ堉??꾩룆??얜????熬곣뫀堉??琉얠돪??

        // ???? 嶺뚳퐘維? ?β돦裕녽????덉넮 嶺뚮∥?? ????
        // If any attachments failed to load, record a system message noting how many
        // attachments could not be processed. This aids debugging of missing
        // context when some uploaded files were unreadable or absent. The count
        // is computed by comparing the number of requested attachment IDs and the
        // number of documents successfully extracted by AttachmentService.
        try {
            java.util.List<String> __idsForMeta = uiReq.getAttachmentIds();
            if (__idsForMeta != null && !__idsForMeta.isEmpty()) {
                int __total = __idsForMeta.size();
                int __loaded = 0;
                try {
                    var __docsForMeta = attachmentService.asDocumentsForSession(
                            __idsForMeta,
                            session == null || session.getId() == null ? null : String.valueOf(session.getId()));
                    if (__docsForMeta != null)
                        __loaded = __docsForMeta.size();
                } catch (Exception ignore) {
                    logSuppressed("sync.attachmentMeta.extract");
                }
                int __failed = __total - __loaded;
                if (__failed > 0) {
                    String metaMsg = String.format("嶺뚳퐘維? %d??繞?%d???β돦裕녻キ????덉넮", __total, __failed);
                    historyService.appendMessage(session.getId(), "system", metaMsg);
                }
            }
        } catch (Exception ignore) {
            logSuppressed("sync.attachmentMeta");
        }

        return new ChatResponseDto(result.content(), session.getId(), modelUsedFinal, result.ragUsed(),
                answerModeFinal, traceTurnId, learningContextMeta, result.evidenceMetadata(), syncPipelineSnapshot);
    }

        // "lc:OpenAiChatModel:..." ?筌먐븍Ф ?꾩렮維쀥젆?
    // ===== settings merge =====
    private ChatRequestDto mergeWithSettings(ChatRequestDto ui) {
        return ChatRequestSettingsMerger.merge(ui, settingsService.getAllSettings(), defaultUseRag, log);
    }

    // ===== other APIs =====

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    @GetMapping("/sessions")
    public java.util.List<SessionInfo> sessions(
            @AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails principal,
            jakarta.servlet.http.HttpServletRequest request) {
        boolean isAdmin = principal != null && principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        String user = principal != null ? principal.getUsername() : "anonymousUser";
        String clientIp = resolveClientIp(request);

        java.util.List<ChatSession> list;
        if (isAdmin) {
            list = historyService.getAllSessionsForAdmin();
        } else if (historyService instanceof com.example.lms.service.ChatHistoryServiceImpl impl) {
            list = impl.getSessionsForUser(user, clientIp);
        } else {
            list = historyService.getSessionsForUser(user);
        }
        return list.stream()
                .map(s -> new SessionInfo(
                        s.getId(),
                        s.getTitle(),
                        ChatModelMetaSupport.safeTrim(s.getLastAnswerMode()),
                        s.getLastTraceTurnId()))
                .toList();
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable Long id, Authentication authentication) {
        String username = (authentication != null && authentication.isAuthenticated())
                ? authentication.getName()
                : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        ChatSession session = historyService.getSessionWithMessages(id);

        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "action", "RESET_SESSION",
                            "message", "?筌뤾쑬???嶺뚮씭??쭩??琉????鍮??",
                            "error", "SESSION_NOT_FOUND"));
        }

        if (!isAdmin) {
            var owner = session.getAdministrator();
            if (owner == null) {
                // Guest session: allow only when ownerKey matches current request
                String currentKey = ownerKeyResolver.ownerKey();
                if (session.getOwnerKey() == null || !session.getOwnerKey().equals(currentKey)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            } else {
                if (username == null || !owner.getUsername().equals(username)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
        }

        historyService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    /** GET /api/chat/sessions/{id} */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(@PathVariable Long id, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            // Treat as guest (username=null, isAdmin=false)
        }

        String username = authentication != null && authentication.isAuthenticated() ? authentication.getName() : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        ChatSession session = historyService.getSessionWithMessages(id);

        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "action", "RESET_SESSION",
                            "message", "?筌뤾쑬???嶺뚮씭??쭩??琉????鍮??",
                            "error", "SESSION_NOT_FOUND"));
        }

        if (!isAdmin) {
            var owner = session.getAdministrator();
            if (owner == null) {
                // Guest session: allow only when ownerKey matches current request
                String currentKey = ownerKeyResolver.ownerKey();
                if (session.getOwnerKey() == null || !session.getOwnerKey().equals(currentKey)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            } else {
                if (username == null || !owner.getUsername().equals(username)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
        }

        return ChatSessionDetailResponseBuilder.build(
                session,
                username,
                objectMapper,
                settingsService.getAllSettings(),
                exposeTrace,
                log);
    }

    // ===== helpers =====
    private static void tracePut(String key, Object value) {
        try {
            TraceStore.put(key, value);
        } catch (Exception ignore) {
            logSuppressed(key);
        }
    }

    private static void tracePutIfAbsent(String key, Object value) {
        try {
            TraceStore.putIfAbsent(key, value);
        } catch (Exception ignore) {
            logSuppressed(key);
        }
    }

    private static void traceClear(String stage) {
        try {
            TraceStore.clear();
        } catch (Throwable ignore) {
            logSuppressed(stage);
        }
    }

    private void promoteDebugEvents(String phase, java.util.Map<String, Object> traceMeta, String where) {
        try {
            if (debugEventTracePromotionService != null) {
                debugEventTracePromotionService.promoteChatTrace(phase, traceMeta, where);
            }
        } catch (Exception ignore) {
            logSuppressed("debugEvent.promote");
        }
    }

    private static void logSuppressed(String stage) {
        log.debug("[ChatApi] suppressed stage={}", safeTraceStage(stage));
    }

    private static String errorSummary(Throwable error) {
        String message = SafeRedactor.safeMessage(error == null ? null : error.getMessage(), 512);
        return String.format("errorHash=%s errorLength=%d",
                SafeRedactor.hashValue(message),
                message == null ? 0 : message.length());
    }

    private static String safeTraceStage(String stage) {
        String label = SafeRedactor.traceLabel(stage);
        return (label == null || label.isBlank()) ? "unknown" : label;
    }

    // ===== DTO records =====
    public record MessageDto(Long turnId, String role, String content, LocalDateTime timestamp) {
    }

    static Optional<MessageDto> restoreTraceMetaMessage(Long turnId, String content, LocalDateTime timestamp,
            boolean exposeTrace) {
        return ChatTraceMetaMessageRestorer.restore(turnId, content, timestamp, exposeTrace);
    }

    // MERGE_HOOK:PROJ_AGENT::src111_MEMORY
    /**
     * ChatSession.sessionMeta(JSON)??UI ??븐슙??DTO???곌랜理묌뜮???類ｋ펲.
     * - UI?띠럾? 嶺뚮ㅏ援????띠룆??????깅さ嶺?嶺뚮∥????????????(???????濡レ┣ ??⑥ろ맖).
     * - UI?띠럾? null/?リ옇???泥롨첋?뚮턄嶺?嶺뚮∥?? ?띠룆???DTO???낅슣????類ｋ펲.
     */
    private java.util.Map<String, Object> mergeSessionMetaIntoRequest(ChatSession session, ChatRequestDto uiReq) {
        return ChatSessionMetaMerger.merge(objectMapper, session, uiReq, log);
    }
    // MERGE_HOOK END

    public record SessionDetail(Long id, String title, LocalDateTime createdAt, List<MessageDto> messages,
            String modelUsed, java.util.Map<String, Object> settings) {
    }

    public record SessionInfo(Long id, String title, String answerMode, Long lastTraceTurnId) {
    }

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    private String resolveClientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

}
