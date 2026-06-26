package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.jobs.JobService;
import com.example.lms.integrations.n8n.N8nNotifier;
import com.example.lms.service.ChatService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * Thin REST facade exposing simplified task APIs for synchronous and
 * asynchronous question answering. These endpoints are intended for
 * consumption by external orchestrators such as n8n and rely on the
 * underlying {@link ChatService} for the heavy lifting. Job state is
 * persisted in {@link JobService} to enable polling and callbacks.
 */
@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
public class TasksApiController {
    private static final Logger log = LoggerFactory.getLogger(TasksApiController.class);

    private final ChatService chatService;
    private final JobService jobService;
    private final N8nNotifier notifier;

    /**
     * Handle a synchronous ask request. The message is delegated to
     * {@link ChatService#continueChat(ChatRequestDto)} and the response
     * returned directly. Errors result in a 500 status with a generic
     * message and no sensitive details.
     *
     * @param req the task request payload
     * @return the assistant response or an error
     */
    @PostMapping("/ask")
    public ResponseEntity<ChatResponseDto> askSync(@RequestBody TaskAskRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponseDto("bad_request", null, null, false));
        }
        if (missingMessage(req)) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponseDto("bad_request", null, "missing_message", false));
        }
        try {
            ChatRequestDto chatReq = toChatRequest(req);
            var result = chatService.continueChat(chatReq);
            ChatResponseDto dto = new ChatResponseDto(
                    result.content(),
                    chatReq.getSessionId(),
                    result.modelUsed(),
                    result.ragUsed());
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            if (result.modelUsed() != null && !result.modelUsed().isBlank()) {
                ok.header("X-Model-Used", result.modelUsed());
            }
            if (result.ragUsed()) {
                ok.header("X-RAG-Used", "true");
            }
            return ok.body(dto);
        } catch (Exception e) {
            log.warn("[TasksApi] askSync failed errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)),
                    messageLength(e));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ChatResponseDto("정보 없음", null, null, false));
        }
    }

    /**
     * Submit an asynchronous ask request. The request is recorded in the
     * job store and executed on a background thread. When complete the
     * supplied callback URL is invoked with the task result. A 202
     * response containing the task identifier is returned immediately.
     *
     * @param req the task request
     * @return an accepted response containing the new task identifier
     */
    @PostMapping("/ask/async")
    public ResponseEntity<Map<String, String>> askAsync(@RequestBody TaskAskRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "code", "missing_request"));
        }
        if (missingMessage(req)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "code", "missing_message"));
        }
        String jobId = jobService.enqueue(
                "task_ask",
                req,
                null,
                (req.sid() == null ? null : String.valueOf(req.sid())));
        if (jobId == null || jobId.isBlank()) {
            traceAsyncJobEnqueueFailed(req);
            log.warn("[TasksApi] async enqueue returned empty job id");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "job_enqueue_failed"));
        }
        // Launch background execution
        jobService.executeAsync(jobId, () -> {
            ChatRequestDto chatReq = toChatRequest(req);
            var result = chatService.continueChat(chatReq);
            return new ChatResponseDto(
                    result.content(),
                    chatReq.getSessionId(),
                    result.modelUsed(),
                    result.ragUsed());
        }, res -> {
            // If a callback was provided notify the remote URL
            if (req.callbackUrl() != null && !req.callbackUrl().isBlank()) {
                try {
                    notifier.notify(req.callbackUrl(), callbackPayload(jobId, res));
                } catch (Exception ex) {
                    log.warn("[TasksApi] callback notification failed errorHash={} errorLength={}",
                            com.example.lms.trace.SafeRedactor.hashValue(messageOf(ex)),
                            messageLength(ex));
                }
            }
        });
        return ResponseEntity.accepted().body(Map.of("taskId", jobId));
    }

    private static Map<String, Object> callbackPayload(String jobId, ChatResponseDto res) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", safeText(jobId));
        payload.put("content", res == null ? "" : safeText(res.getContent()));
        payload.put("modelUsed", res == null ? "" : safeText(res.getModelUsed()));
        payload.put("ragUsed", res != null && res.isRagUsed());
        return payload;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }

    private static void traceAsyncJobEnqueueFailed(TaskAskRequest req) {
        TraceStore.put("api.tasks.async.jobEnqueueFailed", true);
        TraceStore.put("api.tasks.async.skipped.reason", "job_enqueue_failed");
        TraceStore.put("api.tasks.async.messageLength",
                req == null || req.message() == null ? 0 : req.message().length());
        TraceStore.put("api.tasks.async.sessionHash",
                req == null || req.sid() == null ? "" : SafeRedactor.hashValue(String.valueOf(req.sid())));
        TraceStore.put("api.tasks.async.hasCallback",
                req != null && req.callbackUrl() != null && !req.callbackUrl().isBlank());
    }

    private static boolean missingMessage(TaskAskRequest req) {
        return req.message() == null || req.message().isBlank();
    }

    /**
     * Convert the lightweight task ask request into the richer ChatRequestDto
     * used by the core chat service. Only a subset of fields are mapped;
     * missing values fall back to the defaults defined by the DTO builder.
     *
     * @param req the task request
     * @return a new ChatRequestDto
     */
    private ChatRequestDto toChatRequest(TaskAskRequest req) {
        ChatRequestDto.Builder builder = ChatRequestDto.builder()
                .message(req.message())
                .model(req.model() != null ? req.model() : null)
                .sessionId(req.sid());
        // Use wrapper types for booleans to allow null (unspecified) values
        if (req.useRag() != null)
            builder.useRag(req.useRag());
        if (req.useWebSearch() != null)
            builder.useWebSearch(req.useWebSearch());
        return builder.build();
    }

    /**
     * Request body for the /v1/tasks/ask endpoints. This record captures
     * only the minimal inputs required by the tasks API. Additional
     * options can be added in future revisions without impacting
     * compatibility.
     *
     * @param message      the user query
     * @param history      ignored for now; reserved for future use
     * @param useRag       whether retrieval should be used (nullable)
     * @param useWebSearch whether live web search should be used (nullable)
     * @param sid          optional session identifier
     * @param model        optional model identifier
     * @param callbackUrl  optional callback URL for async requests
     */
    public record TaskAskRequest(
            String message,
            java.util.List<Object> history,
            Boolean useRag,
            Boolean useWebSearch,
            Long sid,
            String model,
            String callbackUrl) {
    }
}
