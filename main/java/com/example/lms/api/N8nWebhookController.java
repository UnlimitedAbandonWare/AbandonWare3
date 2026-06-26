package com.example.lms.api;

import com.example.lms.integrations.n8n.SignatureVerifier;
import com.example.lms.jobs.JobService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class N8nWebhookController {

    private final SignatureVerifier verifier;
    private final JobService jobs;

    public N8nWebhookController(@Value("${n8n.webhook.secret:}") String secret, JobService jobs) {
        this.verifier = new SignatureVerifier(secret);
        this.jobs = jobs;
    }

    @PostMapping(path="/hooks/n8n", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> accept(HttpServletRequest request,
                                    @RequestHeader(value="X-Signature", required=false) String sig,
                                    @RequestHeader(value="Idempotency-Key", required=false) String idemKey) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        if (!verifier.verify(body, sig)) {
            traceAcceptSignatureRejected(body.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","INVALID_SIGNATURE"));
        }
        String jobId = jobs.enqueue(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        if (jobId == null || jobId.isBlank()) {
            traceAcceptEnqueueFailed(body.length);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "job_enqueue_failed"));
        }
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @GetMapping(path="/hooks/n8n/{jobId}", produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(@PathVariable String jobId,
                                    @RequestHeader(value="X-Signature", required=false) String sig) {
        String id = jobId == null ? "" : jobId;
        if (!verifier.verify(id.getBytes(StandardCharsets.UTF_8), sig)) {
            traceStatusSignatureRejected(id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","INVALID_SIGNATURE"));
        }
        String jobIdHash = SafeRedactor.hashValue(id);
        String status = jobs.status(jobId);
        String safeStatus = status == null || status.isBlank() ? "UNKNOWN" : status;
        traceStatus(jobIdHash, id.length(), safeStatus);
        return ResponseEntity.ok(Map.of(
                "jobIdHash", jobIdHash == null ? "" : jobIdHash,
                "jobIdLength", id.length(),
                "status", safeStatus
        ));
    }

    private static void traceStatus(String jobIdHash, int jobIdLength, String status) {
        TraceStore.put("api.n8nWebhook.status.jobIdHash", jobIdHash == null ? "" : jobIdHash);
        TraceStore.put("api.n8nWebhook.status.jobIdLength", jobIdLength);
        TraceStore.put("api.n8nWebhook.status.status", SafeRedactor.traceLabelOrFallback(status, "UNKNOWN"));
    }

    private static void traceAcceptEnqueueFailed(int bodyLength) {
        TraceStore.put("api.n8nWebhook.accept.jobEnqueueFailed", true);
        TraceStore.put("api.n8nWebhook.accept.skipped.reason", "job_enqueue_failed");
        TraceStore.put("api.n8nWebhook.accept.bodyLength", Math.max(0, bodyLength));
    }

    private static void traceAcceptSignatureRejected(int bodyLength) {
        TraceStore.put("api.n8nWebhook.accept.signatureRejected", true);
        TraceStore.put("api.n8nWebhook.accept.skipped.reason", "invalid_signature");
        TraceStore.put("api.n8nWebhook.accept.bodyLength", Math.max(0, bodyLength));
    }

    private static void traceStatusSignatureRejected(String jobId) {
        String id = jobId == null ? "" : jobId;
        TraceStore.put("api.n8nWebhook.status.signatureRejected", true);
        TraceStore.put("api.n8nWebhook.status.skipped.reason", "invalid_signature");
        TraceStore.put("api.n8nWebhook.status.jobIdHash", SafeRedactor.hashValue(id));
        TraceStore.put("api.n8nWebhook.status.jobIdLength", id.length());
    }
}
