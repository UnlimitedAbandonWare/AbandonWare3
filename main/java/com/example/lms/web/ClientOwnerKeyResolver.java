package com.example.lms.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;




@Component
public class ClientOwnerKeyResolver {

    private static final System.Logger LOG = System.getLogger(ClientOwnerKeyResolver.class.getName());
    private static final String NO_REQUEST_OWNER_KEY = "system:no-request";

    private HttpServletRequest request;

    public ClientOwnerKeyResolver() {
    }

    @Autowired(required = false)
    public ClientOwnerKeyResolver(HttpServletRequest request) {
        this.request = request;
    }

    /** Compute or retrieve stable ownerKey for current request. */
    public String ownerKey() {
        HttpServletRequest currentRequest = request;
        if (currentRequest == null) {
            return NO_REQUEST_OWNER_KEY;
        }

        // 1) ownerKey cookie. Public X-Owner-Key headers are not trusted because
        // browsers and external clients can spoof them.
        String cookieVal = OwnerKeyBootstrapFilter.usableOwnerKey(readCookie(currentRequest, OwnerKeyBootstrapFilter.OWNER_KEY));
        if (cookieVal != null) return cookieVal;

        // 2) gid cookie (compatibility path)
        String gid = usableGid(readCookie(currentRequest, "gid"));
        if (gid != null) return "gid:" + gid;

        // 3) Fallback: IP + UA hash (do not store raw PII)
        String ip = firstForwardedIpOrRemoteAddr(currentRequest);
        String ua = Optional.ofNullable(currentRequest.getHeader("User-Agent")).orElse("");
        if (ua.length() > 120) ua = ua.substring(0, 120);
        String raw = (ip == null ? "" : ip) + "|" + ua;
        String digest = sha256(raw);
        if (digest != null) return "ipua:" + digest;

        // 4) Random
        return UUID.randomUUID().toString();
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                String v = trimToNull(c.getValue());
                if (v != null) return v;
            }
        }
        return null;
    }

    private String firstForwardedIpOrRemoteAddr(HttpServletRequest request) {
        String xff = trimToNull(request.getHeader("X-Forwarded-For"));
        if (xff != null) {
            int idx = xff.indexOf(',');
            return (idx > 0 ? xff.substring(0, idx) : xff).trim();
        }
        return trimToNull(request.getRemoteAddr());
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String usableGid(String gid) {
        if (gid == null) {
            return null;
        }
        try {
            return UUID.fromString(gid).toString();
        } catch (IllegalArgumentException ignored) {
            LOG.log(System.Logger.Level.DEBUG, "Client owner gid cookie rejected");
            return null;
        }
    }

    private String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "Client owner fallback hash unavailable");
            return null;
        }
    }
}
