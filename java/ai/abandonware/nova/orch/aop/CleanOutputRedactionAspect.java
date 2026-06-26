package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            return out;
        }

        Cut cut = cutAtFirstMarker(content);
        if (!cut.applied) {
            return out;
        }

        String cleaned = cut.cleaned == null ? "" : cut.cleaned.trim();
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
            // best-effort
        }

        if (log.isDebugEnabled()) {
            log.debug("CleanOutputRedactionAspect redacted injected trace tail (marker={}, removedChars={})",
                    cut.marker, removedChars);
        }

        return new ChatResult(cleaned, cr.modelUsed(), cr.ragUsed(), cr.evidence());
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
            int idx = content.indexOf(m);
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

        String cleaned = content.substring(0, bestIdx);
        String removed = content.substring(bestIdx);
        String hash = sha1Hex(SafeRedactor.redact(clip(removed, 2048)));

        return new Cut(true, bestMarker, cleaned, hash);
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
            return null;
        }
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
