package com.example.lms.security;

import com.example.lms.config.ConfigValueGuards;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional operational guardrail for admin pages and diagnostics endpoints.
 *
 * <p>Supported presentation channels are intentionally narrow:</p>
 * <ul>
 *   <li>Header: {@code X-Admin-Token}</li>
 *   <li>Header: {@code X-Owner-Token}</li>
 *   <li>Cookie: {@code aw-admin-token} (HttpOnly)</li>
 * </ul>
 */
@Component
public class AdminTokenGuardInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";
    public static final String OWNER_HEADER = "X-Owner-Token";
    public static final String COOKIE_NAME = "aw-admin-token";

    @Value("${domain.allowlist.admin-token:}")
    private String expectedToken;

    @Value("${llm.owner-token:${LLM_OWNER_TOKEN:}}")
    private String ownerToken;

    @Value("${domain.allowlist.admin-token.required:${DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED:false}}")
    private boolean tokenRequired;

    @SuppressWarnings("unused")
    @Value("${domain.allowlist.admin-token.allow-query:${DOMAIN_ALLOWLIST_ADMIN_TOKEN_ALLOW_QUERY:false}}")
    private boolean allowQueryToken;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        boolean strict = tokenRequired || hasProfile(activeProfiles, "prod");
        List<String> expected = configuredTokens();
        if (expected.isEmpty()) {
            if (strict) {
                deny(req, res);
                return false;
            }
            return true;
        }

        String presented = extractPresentedToken(req);
        if (!constantTimeEqualsAny(expected, presented)) {
            deny(req, res);
            return false;
        }

        trySetCookie(req, res, presented);
        return true;
    }

    private static String extractPresentedToken(HttpServletRequest req) {
        if (req == null) return "";

        String header = req.getHeader(HEADER);
        if (header != null && !header.isBlank()) return header.trim();

        header = req.getHeader(OWNER_HEADER);
        if (header != null && !header.isBlank()) return header.trim();

        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie != null && COOKIE_NAME.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) return value.trim();
                }
            }
        }

        return "";
    }

    private static void deny(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (res == null) return;

        String uri = safePath(req);
        boolean api = uri.startsWith("/api/");

        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());

        if (api) {
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"error\":\"admin token required\",\"hint\":\"set X-Admin-Token or X-Owner-Token header\"}");
            return;
        }

        res.setContentType(MediaType.TEXT_HTML_VALUE);
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>");
        sb.append("<title>Admin token required</title>");
        sb.append("<style>");
        sb.append("body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:24px;}");
        sb.append("code{background:#f4f4f4;padding:2px 6px;border-radius:4px;}");
        sb.append(".box{max-width:880px;}");
        sb.append("</style>");
        sb.append("</head><body><div class=\"box\">");
        sb.append("<h2>Admin token required</h2>");
        sb.append("<p>This environment is configured with <code>domain.allowlist.admin-token</code>.</p>");
        sb.append("<p>To access admin/debug pages, present the token via one of the following:</p>");
        sb.append("<ul>");
        sb.append("<li><code>X-Admin-Token: &lt;token&gt;</code> header</li>");
        sb.append("<li><code>X-Owner-Token: &lt;token&gt;</code> header</li>");
        sb.append("<li><code>").append(COOKIE_NAME).append("=&lt;token&gt;</code> cookie</li>");
        sb.append("</ul>");
        sb.append("</div></body></html>");
        res.getWriter().write(sb.toString());
    }

    private static void trySetCookie(HttpServletRequest req, HttpServletResponse res, String token) {
        if (req == null || res == null) return;
        if (token == null || token.isBlank()) return;

        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie != null && COOKIE_NAME.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && value.trim().equals(token.trim())) {
                        return;
                    }
                }
            }
        }

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token.trim())
                .httpOnly(true)
                .secure(req.isSecure())
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ofHours(24))
                .build();

        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String safePath(HttpServletRequest req) {
        if (req == null) return "";
        String uri = req.getRequestURI();
        if (uri == null) return "";
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isBlank() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aa = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (aa.length != bb.length) return false;
        int result = 0;
        for (int i = 0; i < aa.length; i++) {
            result |= aa[i] ^ bb[i];
        }
        return result == 0;
    }

    private List<String> configuredTokens() {
        List<String> tokens = new ArrayList<>();
        if (!ConfigValueGuards.isMissing(expectedToken)) {
            tokens.add(expectedToken.trim());
        }
        if (!ConfigValueGuards.isMissing(ownerToken)) {
            tokens.add(ownerToken.trim());
        }
        return tokens;
    }

    public boolean hasConfiguredToken() {
        return !configuredTokens().isEmpty();
    }

    public boolean isPresentedTokenAuthorized(HttpServletRequest req) {
        return constantTimeEqualsAny(configuredTokens(), extractPresentedToken(req));
    }

    private static boolean constantTimeEqualsAny(List<String> expected, String presented) {
        if (expected == null || expected.isEmpty()) {
            return false;
        }
        for (String token : expected) {
            if (constantTimeEquals(token, presented)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProfile(String profiles, String expected) {
        if (profiles == null || expected == null) {
            return false;
        }
        for (String token : profiles.split(",")) {
            if (expected.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }
}
