package com.example.lms.llm;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Guard helpers for local OpenAI-compatible gateways such as Ollama/vLLM.
 */
public final class LocalLlmGatewaySecurity {

    public static final String DEFAULT_OWNER_TOKEN_HEADER = "X-Owner-Token";

    private LocalLlmGatewaySecurity() {
    }

    public static Map<String, String> ownerTokenHeaders(String headerName, String ownerToken) {
        String token = trimToNull(ownerToken);
        if (!hasUsableRemoteSecret(token)) {
            return Map.of();
        }
        return Map.of(safeHeaderName(headerName), token);
    }

    /**
     * Backward-compatible loopback-only check. Remote gateway headers must use the
     * allowlist-aware overload.
     */
    public static boolean shouldAttachOwnerToken(String baseUrl) {
        return isLoopbackBaseUrl(baseUrl);
    }

    public static boolean shouldAttachOwnerToken(String baseUrl, String allowedHostsCsv) {
        if (isKnownExternalProviderBaseUrl(baseUrl)) {
            return false;
        }
        return isLoopbackBaseUrl(baseUrl) || isAllowedHost(baseUrl, allowedHostsCsv);
    }

    public static boolean isKnownExternalProviderBaseUrl(String baseUrl) {
        String host = endpointHost(baseUrl);
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("api.openai.com")
                || h.endsWith(".openai.com")
                || h.equals("api.groq.com")
                || h.endsWith(".groq.com")
                || h.equals("api.cerebras.ai")
                || h.endsWith(".cerebras.ai")
                || h.equals("api.openrouter.ai")
                || h.endsWith(".openrouter.ai")
                || h.equals("openrouter.ai")
                || h.equals("opencode.ai")
                || h.endsWith(".opencode.ai")
                || h.equals("api.anthropic.com")
                || h.endsWith(".anthropic.com")
                || h.equals("generativelanguage.googleapis.com")
                || h.endsWith(".googleapis.com")
                || h.equals("ollama.com")
                || h.endsWith(".ollama.com");
    }

    public static void assertLocalGatewayEndpointAllowed(
            String baseUrl,
            boolean allowPrivateRemote,
            String allowedHostsCsv,
            boolean requireAuthForRemote,
            String apiKey,
            String ownerToken) {

        if (isKnownExternalProviderBaseUrl(baseUrl)) {
            return;
        }
        assertLocalProviderEndpointAllowed(
                "local",
                baseUrl,
                allowPrivateRemote,
                allowedHostsCsv,
                requireAuthForRemote,
                apiKey,
                ownerToken);
    }

    public static void assertLocalProviderEndpointAllowed(
            String provider,
            String baseUrl,
            boolean allowPrivateRemote,
            String allowedHostsCsv,
            boolean requireAuthForRemote,
            String apiKey,
            String ownerToken) {

        if (!"local".equalsIgnoreCase(trimToEmpty(provider))) {
            return;
        }
        String url = trimToNull(baseUrl);
        if (url == null || isLoopbackBaseUrl(url)) {
            return;
        }

        String host = endpointHost(url);
        if (!allowPrivateRemote) {
            throw new IllegalStateException(
                    "ProviderGuard: remote local LLM endpoint disabled (host=" + safeHost(host)
                            + ", set llm.provider-guard.allow-private-remote=true and allowlist the host)");
        }
        if (!isAllowedHost(url, allowedHostsCsv)) {
            throw new IllegalStateException(
                    "ProviderGuard: remote local LLM host is not allowlisted (host=" + safeHost(host) + ")");
        }
        if (requireAuthForRemote && !hasUsableRemoteSecret(apiKey) && !hasUsableRemoteSecret(ownerToken)) {
            throw new IllegalStateException(
                    "ProviderGuard: remote local LLM requires a usable proxy bearer token or owner token (host="
                            + safeHost(host) + ")");
        }
    }

    public static boolean hasUsableRemoteSecret(String value) {
        String v = trimToNull(value);
        if (v == null) {
            return false;
        }
        return !ConfigValueGuards.isMissing(v);
    }

    public static boolean isLoopbackBaseUrl(String baseUrl) {
        String host = endpointHost(baseUrl);
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h)
                || "127.0.0.1".equals(h)
                || h.startsWith("127.")
                || "::1".equals(h)
                || "0:0:0:0:0:0:0:1".equals(h);
    }

    public static String endpointHost(String baseUrl) {
        URI uri = parseUri(baseUrl);
        if (uri == null) {
            return "";
        }
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    static boolean isAllowedHost(String baseUrl, String allowedHostsCsv) {
        String host = endpointHost(baseUrl);
        if (host == null || host.isBlank()) {
            return false;
        }
        URI uri = parseUri(baseUrl);
        int port = uri == null ? -1 : uri.getPort();
        String hostOnly = host.toLowerCase(Locale.ROOT);
        String hostPort = port > 0 ? hostOnly + ":" + port : hostOnly;

        String csv = trimToNull(allowedHostsCsv);
        if (csv == null) {
            return false;
        }
        for (String raw : csv.split(",")) {
            String candidate = trimToNull(raw);
            if (candidate == null) {
                continue;
            }
            String normalized = normalizeAllowedHost(candidate);
            if (hostOnly.equals(normalized) || hostPort.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeAllowedHost(String raw) {
        URI uri = parseUri(raw);
        if (uri != null && uri.getHost() != null) {
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static URI parseUri(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            String candidate = value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*") ? value : "http://" + value;
            return URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            TraceStore.put("llm.localGateway.suppressed.stage", "parseUri");
            TraceStore.put("llm.localGateway.suppressed.errorType", "invalid_url");
            TraceStore.put("llm.localGateway.suppressed.parseUri", true);
            TraceStore.put("llm.localGateway.suppressed.parseUri.errorType", "invalid_url");
            return null;
        }
    }

    private static String safeHeaderName(String headerName) {
        String h = trimToNull(headerName);
        if (h == null) {
            return DEFAULT_OWNER_TOKEN_HEADER;
        }
        if (!h.matches("[A-Za-z0-9-]{1,64}")) {
            return DEFAULT_OWNER_TOKEN_HEADER;
        }
        String lower = h.toLowerCase(Locale.ROOT);
        if ("authorization".equals(lower) || "cookie".equals(lower) || "set-cookie".equals(lower)) {
            return DEFAULT_OWNER_TOKEN_HEADER;
        }
        return h;
    }

    private static String safeHost(String host) {
        String h = trimToNull(host);
        return h == null ? "(unknown)" : h;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
