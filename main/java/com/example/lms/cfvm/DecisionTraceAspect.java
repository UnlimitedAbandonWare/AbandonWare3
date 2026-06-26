
package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;




@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 65)
@Component
public class DecisionTraceAspect {

    @Around("execution(* *..DynamicRetrievalHandlerChain.*(..))")
    public Object trace(ProceedingJoinPoint pjp) throws Throwable {
        long ts = System.currentTimeMillis();
        try {
            Object res = pjp.proceed();
            log("OK", pjp, System.currentTimeMillis()-ts, null);
            return res;
        } catch (Throwable t) {
            log("ERR", pjp, System.currentTimeMillis()-ts, t.getMessage());
            throw t;
        }
    }

    private void log(String status, ProceedingJoinPoint pjp, long ms, String err) {
        String signature = pjp == null || pjp.getSignature() == null
                ? ""
                : pjp.getSignature().toShortString();
        String safeError = SafeRedactor.safeMessage(err, 180);
        String line = new StringBuilder(160)
                .append("{\"ts\":").append(Instant.now().toEpochMilli())
                .append(",\"status\":").append(json(SafeRedactor.traceLabelOrFallback(status, "unknown")))
                .append(",\"sigHash\":").append(json(SafeRedactor.hashValue(signature)))
                .append(",\"sigLength\":").append(signature.length())
                .append(",\"ms\":").append(ms)
                .append(",\"errPresent\":").append(safeError != null && !safeError.isBlank())
                .append(",\"errHash\":").append(json(SafeRedactor.hashValue(safeError)))
                .append(",\"errLength\":").append(safeError == null ? 0 : safeError.length())
                .append("}\n")
                .toString();
        try {
            Path out = Path.of("cfvm-raw", "records", "trace.ndjson");
            Files.createDirectories(out.getParent());
            Files.writeString(out, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            TraceStore.inc("cfvm.decisionTrace.writeFailure.count");
            TraceStore.put("cfvm.decisionTrace.writeFailure.errorType", "decision_trace_write_failed");
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }
}
