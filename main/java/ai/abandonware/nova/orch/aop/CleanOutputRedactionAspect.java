package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Output-boundary guard: prevents internal trace/diagnostic strings from
 * leaking into user-visible answers.
 *
 * <p>
 * Motivation: TraceStore / probe / diagnostics are valuable for operators, but
 * must not be mixed into
 * the end-user answer. We cut known trace appendix markers and record a safe
 * breadcrumb into TraceStore.
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 5)
public class CleanOutputRedactionAspect {

    private static final Logger log = LoggerFactory.getLogger(CleanOutputRedactionAspect.class);

    private static final String MARKER = "<!-- NOVA_TRACE_INJECTED -->";

    /**
     * Extra “belt & suspenders” cut markers.
     * Keep conservative (low false-positive risk): these strings should never
     * appear in normal answers.
     */
    private static final String[] CUT_MARKERS = new String[] {
            MARKER,
            "TRACE_JSON",
            "TRACE_HTML",
            "SearchTrace{",
            "SearchTrace(",
            "SearchTrace:",
            "StageSnapshot",
            "SoakProbe",
            "nightmare:state"
    };

    private final Environment env;

    public CleanOutputRedactionAspect(Environment env) {
        this.env = env;
    }

    private boolean enabled() {
        return env.getProperty("nova.orch.output.clean.enabled", Boolean.class, true);
    }

    @Around("execution(com.example.lms.service.ChatResult com.example.lms.service.ChatService.ask(..))"
            + " || execution(com.example.lms.service.ChatResult com.example.lms.service.ChatService.continueChat(..))")
    public Object redactUserVisibleOutput(ProceedingJoinPoint pjp) throws Throwable {
        Object out = pjp.proceed();
        if (!enabled()) {
            return out;
        }
        if (!(out instanceof ChatResult cr)) {
            return out;
        }

        String content = cr.content();
        if (content == null || content.isBlank()) {
            // Never emit a blank answer: return a minimal, user-safe fallback.
            String fallback = "The answer body was blank. Please retry the request.";
            try {
                TraceStore.put("orch.output.blank.prevented", true);
                TraceStore.put("orch.output.blank.prevented.reason", "blank_content");
            } catch (Throwable ignored) {
                traceSuppressed("blank.prevented", ignored);
            }
            return new ChatResult(fallback, cr.modelUsed(), cr.ragUsed(), cr.evidence(), cr.evidenceMetadata());
        }

        Cut cut = cutAtFirstMarker(content);
        if (!cut.applied) {
            return out;
        }

        String cleaned = cut.cleaned == null ? "" : cut.cleaned.trim();
        if (cleaned.isBlank()) {
            // Redaction produced an empty string (e.g., marker at the beginning).
            // Prefer a short fallback over an empty response.
            cleaned = "The answer body was removed while cleaning diagnostics. Please retry the request.";
            try {
                TraceStore.put("orch.output.blank.prevented", true);
                TraceStore.put("orch.output.blank.prevented.reason", "diagnostics_removed_empty");
                TraceStore.put("orch.output.blank.prevented.marker", cut.marker);
            } catch (Throwable ignored) {
                traceSuppressed("blank.prevented.marker", ignored);
            }
        }
        if (cleaned.equals(content)) {
            return out;
        }

        long removedChars = Math.max(0, content.length() - cleaned.length());

        try {
            TraceStore.put("orch.output.redaction.applied", true);
            TraceStore.put("orch.output.redaction.marker", cut.marker);
            TraceStore.put("orch.output.redaction.removedChars", removedChars);
            TraceStore.put("orch.output.redaction.removedHash", cut.removedHash);
        } catch (Throwable ignored) {
            traceSuppressed("redaction.applied", ignored);
        }

        if (log.isDebugEnabled()) {
            log.debug("CleanOutputRedactionAspect redacted injected trace tail (marker={}, removedChars={})",
                    cut.marker, removedChars);
        }

        return new ChatResult(cleaned, cr.modelUsed(), cr.ragUsed(), cr.evidence(), cr.evidenceMetadata());
    }

    private static Cut cutAtFirstMarker(String content) {
        if (content == null || content.isBlank()) {
            return Cut.noop();
        }

        int bestIdx = -1;
        String bestMarker = null;
        for (String m : CUT_MARKERS) {
            if (m == null || m.isBlank()) {
                continue;
            }
            int idx = findMarkerIndex(content, m);
            if (idx < 0) {
                continue;
            }
            if (bestIdx < 0 || idx < bestIdx) {
                bestIdx = idx;
                bestMarker = m;
            }
        }

        if (bestIdx < 0) {
            return Cut.noop();
        }

        int after = Math.min(content.length(), bestIdx + bestMarker.length());
        String prefix = content.substring(0, bestIdx);
        String suffix = content.substring(after);

        // Typical case: marker is an appendix tail => keep prefix.
        // Head marker means diagnostics were prepended; do not promote that suffix
        // to the user-visible answer.
        boolean headMarker = prefix.isBlank();
        String cleaned = headMarker ? "" : prefix;
        String removed = headMarker ? content : content.substring(bestIdx);

        String hash = sha1Hex(SafeRedactor.redact(clip(removed, 2048)));
        String markerLabel = headMarker ? (bestMarker + ":head") : bestMarker;

        return new Cut(true, markerLabel, cleaned, hash);
    }

    private static int findMarkerIndex(String content, String marker) {
        if (MARKER.equals(marker)) {
            return content.indexOf(marker);
        }

        int from = 0;
        while (from < content.length()) {
            int idx = content.indexOf(marker, from);
            if (idx < 0) {
                return -1;
            }
            if (isLineStartMarker(content, idx) && hasDiagnosticPayloadShape(content, idx + marker.length())) {
                return idx;
            }
            from = idx + marker.length();
        }
        return -1;
    }

    private static boolean isLineStartMarker(String content, int idx) {
        int p = idx - 1;
        while (p >= 0) {
            char c = content.charAt(p);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
            p--;
        }
        return true;
    }

    private static boolean hasDiagnosticPayloadShape(String content, int afterMarker) {
        int p = afterMarker;
        while (p < content.length() && Character.isWhitespace(content.charAt(p))
                && content.charAt(p) != '\n' && content.charAt(p) != '\r') {
            p++;
        }
        if (p >= content.length() || content.charAt(p) == '\n' || content.charAt(p) == '\r') {
            return true;
        }
        char c = content.charAt(p);
        return c == '{' || c == '[' || c == '(' || c == ':' || c == '=' || c == '"' || c == '\'';
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static String sha1Hex(String s) {
        if (s == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return toHex(dig);
        } catch (Exception e) {
            traceSuppressed("removedHash.sha1", e);
            return null;
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("CleanOutputRedactionAspect trace fallback (stage={} errorHash={} errorLength={})",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                SafeRedactor.hashValue(messageOf(error)), messageLength(error));
    }

    private static String messageOf(Throwable error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private static int messageLength(Throwable error) {
        return messageOf(error).length();
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xff;
            sb.append(Character.forDigit((v >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(v & 0xf, 16));
        }
        return sb.toString();
    }

    private static final class Cut {
        final boolean applied;
        final String marker;
        final String cleaned;
        final String removedHash;

        Cut(boolean applied, String marker, String cleaned, String removedHash) {
            this.applied = applied;
            this.marker = marker;
            this.cleaned = cleaned;
            this.removedHash = removedHash;
        }

        static Cut noop() {
            return new Cut(false, null, null, null);
        }
    }
}
