package com.example.lms.config;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalLlmProcessManager implements BeanFactoryPostProcessor, SmartLifecycle, Ordered, EnvironmentAware {
    private static final Logger log = LoggerFactory.getLogger(LocalLlmProcessManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_OLLAMA_HOST_ENV = "127.0.0.1:11435";

    private Environment env;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean attempted = new AtomicBoolean(false);

    @Value("${local-llm.enabled:true}")
    private boolean enabled;

    @Value("${local-llm.autostart:false}")
    private boolean autostart;

    @Value("${local-llm.ollama-host:127.0.0.1:11435}")
    private String ollamaHost;

    @Value("${local-llm.health-check-url:}")
    private String healthCheckUrl;

    @Value("${local-llm.health-check-timeout:20s}")
    private Duration healthCheckTimeout;

    @Value("${local-llm.health-check-interval:1s}")
    private Duration healthCheckInterval;

    @Value("${local-llm.health-check-attempt-timeout:2s}")
    private Duration healthCheckAttemptTimeout;

    @Value("${local-llm.start-command:}")
    private String startCommand;

    @Value("${local-llm.fail-fast:false}")
    private boolean failFast;

    @Value("${local-llm.warmup.enabled:false}")
    private boolean warmupEnabled;

    @Value("${local-llm.warmup.pull:true}")
    private boolean warmupPull;

    @Value("${local-llm.warmup.show:true}")
    private boolean warmupShow;

    @Value("${local-llm.warmup.embed:true}")
    private boolean warmupEmbed;

    @Value("${local-llm.warmup.model:${embedding.model:qwen3-embedding:4b}}")
    private String warmupModel;

    @Value("${local-llm.warmup.dimensions:${embedding.dimensions:1536}}")
    private int warmupDimensions;

    @Value("${local-llm.warmup.keep-alive:5m}")
    private String warmupKeepAlive;

    @Value("${local-llm.warmup.timeout-ms:120000}")
    private long warmupTimeoutMs;

    public LocalLlmProcessManager() {
    }

    public LocalLlmProcessManager(Environment env) {
        this.env = env;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        loadFromEnvironment();
        start();
    }

    @Override
    public void start() {
        if (!attempted.compareAndSet(false, true)) {
            running.set(true);
            return;
        }

        try {
            logSecretPresence();

            if (!enabled || !autostart) {
                traceStartupSnapshot("skipped", "disabled_or_autostart_false");
                traceWarmupSkipped();
                log.info("[AWX][ollama][startup] enabled={} autostart={} status=skipped", enabled, autostart);
                running.set(true);
                return;
            }

            if (isServiceRunning()) {
                traceStartupSnapshot("already_healthy", "");
                log.info("[AWX][ollama][startup] status=already_healthy healthUrlHost={} healthUrlHash={} healthUrlLength={} host={} hostHash={}",
                        safeUrlHost(effectiveHealthCheckUrl()), safeUrlHash(effectiveHealthCheckUrl()),
                        safeUrlLength(effectiveHealthCheckUrl()), safeOllamaHostForLog(ollamaHost),
                        safeOllamaHostHash(ollamaHost));
                warmupOllama();
                running.set(true);
                return;
            }

            traceStartupSnapshot("health_down", "cmd_start");
            log.warn("[AWX][ollama][startup] status=health_down action=cmd_start healthUrlHost={} healthUrlHash={} healthUrlLength={} host={} hostHash={}",
                    safeUrlHost(effectiveHealthCheckUrl()), safeUrlHash(effectiveHealthCheckUrl()),
                    safeUrlLength(effectiveHealthCheckUrl()), safeOllamaHostForLog(ollamaHost),
                    safeOllamaHostHash(ollamaHost));
            try {
                startLocalLlmProcess();
            } catch (Exception ex) {
                traceSuppressed("ollama.start", ex);
                handleStartupFailure("start_command_failed", ex);
                return;
            }

            if (!waitForHealthCheck()) {
                handleStartupFailure("health_timeout", new IllegalStateException(
                        "Ollama did not become healthy within " + healthCheckTimeout));
                return;
            }

            traceStartupSnapshot("healthy", "");
            log.info("[AWX][ollama][startup] status=healthy healthUrlHost={} healthUrlHash={} healthUrlLength={} timeoutMs={}",
                    safeUrlHost(effectiveHealthCheckUrl()), safeUrlHash(effectiveHealthCheckUrl()),
                    safeUrlLength(effectiveHealthCheckUrl()), healthCheckTimeout.toMillis());
            warmupOllama();
            running.set(true);
        } catch (Exception e) {
            traceSuppressed("ollama.unexpected", e);
            handleStartupFailure("unexpected", e);
        }
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    private boolean isServiceRunning() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(effectiveHealthCheckUrl()).openConnection();
            int timeoutMs = boundedMillis(healthCheckAttemptTimeout, 2000);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.connect();
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            traceSuppressed("ollama.isServiceRunning", e);
            return false;
        }
    }

    private void loadFromEnvironment() {
        enabled = envBool("local-llm.enabled", enabled);
        autostart = envBool("local-llm.autostart", autostart);
        ollamaHost = envString("local-llm.ollama-host", ollamaHost);
        healthCheckUrl = envString("local-llm.health-check-url", healthCheckUrl);
        healthCheckTimeout = envDuration("local-llm.health-check-timeout", healthCheckTimeout);
        healthCheckInterval = envDuration("local-llm.health-check-interval", healthCheckInterval);
        healthCheckAttemptTimeout = envDuration("local-llm.health-check-attempt-timeout", healthCheckAttemptTimeout);
        startCommand = envString("local-llm.start-command", startCommand);
        failFast = envBool("local-llm.fail-fast", failFast);
        warmupEnabled = envBool("local-llm.warmup.enabled", warmupEnabled);
        warmupPull = envBool("local-llm.warmup.pull", warmupPull);
        warmupShow = envBool("local-llm.warmup.show", warmupShow);
        warmupEmbed = envBool("local-llm.warmup.embed", warmupEmbed);
        warmupModel = envString("local-llm.warmup.model",
                envString("embedding.model", warmupModel));
        warmupDimensions = envInt("local-llm.warmup.dimensions",
                envInt("embedding.dimensions", warmupDimensions));
        warmupKeepAlive = envString("local-llm.warmup.keep-alive", warmupKeepAlive);
        warmupTimeoutMs = envLong("local-llm.warmup.timeout-ms", warmupTimeoutMs);
    }

    private void startLocalLlmProcess() throws Exception {
        String command = effectiveStartCommand();
        ProcessBuilder pb;
        if (isWindows()) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder(splitCommand(command));
        }
        pb.redirectErrorStream(true);
        pb.inheritIO();
        pb.start();
        log.info("[AWX][ollama][startup] command=cmd_start launched=true host={} hostHash={} explicitCommand={}",
                safeOllamaHostForLog(ollamaHost), safeOllamaHostHash(ollamaHost), hasText(startCommand));
    }

    private boolean waitForHealthCheck() throws InterruptedException {
        long total = healthCheckTimeout.toMillis();
        long waited = 0L;
        long step = Math.max(100L, healthCheckInterval.toMillis());
        while (waited < total) {
            if (isServiceRunning()) return true;
            Thread.sleep(step);
            waited += step;
        }
        return false;
    }

    private void warmupOllama() {
        if (!warmupEnabled) {
            traceWarmupSkipped();
            log.info("[AWX][ollama][warmup] enabled=false status=skipped");
            return;
        }
        String model = trimToNull(warmupModel);
        if (model == null) {
            traceWarmup("failed", 0, "warmup_model_missing");
            handleStartupFailure("warmup_model_missing", new IllegalStateException("local-llm.warmup.model is blank"));
            return;
        }

        try {
            if (warmupPull) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("stream", false);
                postJson("/api/pull", body, warmupTimeoutMs);
                log.info("[AWX][ollama][warmup] step=pull status=ok modelHash={} modelLength={}",
                        SafeRedactor.hashValue(model), model.length());
            }

            if (warmupShow) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                postJson("/api/show", body, warmupTimeoutMs);
                log.info("[AWX][ollama][warmup] step=show status=ok modelHash={} modelLength={}",
                        SafeRedactor.hashValue(model), model.length());
            }

            if (warmupEmbed) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("input", "ping");
                if (warmupDimensions > 0) {
                    body.put("dimensions", warmupDimensions);
                }
                String keepAlive = trimToNull(warmupKeepAlive);
                if (keepAlive != null) {
                    body.put("keep_alive", keepAlive);
                }
                JsonNode root = postJson("/api/embed", body, warmupTimeoutMs);
                int dim = embeddingDimension(root);
                if (dim <= 0) {
                    throw new IllegalStateException("/api/embed returned no embedding");
                }
                if (warmupDimensions > 0 && dim != warmupDimensions) {
                    throw new IllegalStateException("/api/embed dimension mismatch expected="
                            + warmupDimensions + " actual=" + dim);
                }
                traceWarmup("ok", dim, "");
                log.info("[AWX][ollama][warmup] step=embed status=ok modelHash={} modelLength={} targetDim={} returnedDim={}",
                        SafeRedactor.hashValue(model), model.length(), warmupDimensions, dim);
            }
            if (!warmupEmbed) {
                traceWarmup("ok", 0, "embed_disabled");
            }
        } catch (Exception e) {
            traceWarmup("failed", 0, "warmup_failed");
            traceSuppressed("ollama.warmup", e);
            handleStartupFailure("warmup_failed", e);
        }
    }

    private JsonNode postJson(String path, Map<String, Object> body, long timeoutMs) throws IOException {
        String url = ollamaBaseUrl() + path;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeoutMs)));
        conn.setReadTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeoutMs)));
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = readBounded(stream);
        if (code < 200 || code >= 300) {
            throw new IOException("POST " + safeUrlDiagnostic(url)
                    + " failed status=" + code
                    + " bodyHash=" + bodyHash(response)
                    + " bodyLength=" + bodyLength(response));
        }
        return response.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(response);
    }

    private int embeddingDimension(JsonNode root) {
        JsonNode first = root.path("embeddings").path(0);
        return first.isArray() ? first.size() : 0;
    }

    private void handleStartupFailure(String reason, Exception e) {
        running.set(true);
        traceStartupSnapshot("failed", reason);
        log.error("[AWX][ollama][startup] status=failed reason={} host={} hostHash={} error={}",
                reason, safeOllamaHostForLog(ollamaHost), safeOllamaHostHash(ollamaHost), shortErr(e));
        if (failFast) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Local Ollama startup failed: " + reason, e);
        }
    }

    private void logSecretPresence() {
        log.info("[AWX][runtime-config][keys] OPENAI_API_KEY.present={} LLM_API_KEY.present={} "
                        + "NAVER_CLIENT_ID.present={} NAVER_CLIENT_SECRET.present={} NAVER_KEYS.present={}",
                hasConfiguredValue("OPENAI_API_KEY"),
                hasConfiguredValue("LLM_API_KEY"),
                hasConfiguredValue("NAVER_CLIENT_ID"),
                hasConfiguredValue("NAVER_CLIENT_SECRET"),
                hasConfiguredValue("NAVER_KEYS"));
    }

    private boolean hasConfiguredValue(String key) {
        String value = env == null ? null : env.getProperty(key);
        return value != null && !value.isBlank();
    }

    private String effectiveHealthCheckUrl() {
        String configured = trimToNull(healthCheckUrl);
        return configured != null ? configured : ollamaBaseUrl() + "/api/version";
    }

    private String ollamaBaseUrl() {
        String host = normalizeOllamaHostForEnv(ollamaHost);
        if (host.startsWith("http://") || host.startsWith("https://")) {
            int idx = host.indexOf("/api/");
            return idx >= 0 ? host.substring(0, idx) : stripTrailingSlash(host);
        }
        return "http://" + stripTrailingSlash(host);
    }

    private String effectiveStartCommand() {
        String explicit = trimToNull(startCommand);
        if (explicit != null) {
            return explicit;
        }
        return generatedWindowsStartCommand(ollamaHost);
    }

    private void traceStartupSnapshot(String status, String reason) {
        TraceStore.put("localLlm.startup.enabled", enabled);
        TraceStore.put("localLlm.startup.autostart", autostart);
        TraceStore.put("localLlm.startup.status", SafeRedactor.traceLabelOrFallback(status, "unknown"));
        String safeReason = SafeRedactor.traceLabel(reason);
        if (safeReason != null && !safeReason.isBlank()) {
            TraceStore.put("localLlm.startup.reason", safeReason);
        }
        TraceStore.put("localLlm.startup.host", safeOllamaHostForLog(ollamaHost));
        TraceStore.put("localLlm.startup.hostHash", safeOllamaHostHash(ollamaHost));
        String healthUrl = effectiveHealthCheckUrl();
        TraceStore.put("localLlm.startup.healthUrlHost", safeUrlHost(healthUrl));
        TraceStore.put("localLlm.startup.healthUrlHash", safeUrlHash(healthUrl));
        TraceStore.put("localLlm.startup.healthUrlLength", safeUrlLength(healthUrl));
        TraceStore.put("localLlm.startup.warmupTargetDim", Math.max(0, warmupDimensions));
    }

    private void traceWarmupSkipped() {
        traceWarmup("skipped", 0, "disabled");
    }

    private void traceWarmup(String status, int returnedDim, String reason) {
        TraceStore.put("localLlm.warmup.enabled", warmupEnabled);
        TraceStore.put("localLlm.warmup.status", SafeRedactor.traceLabelOrFallback(status, "unknown"));
        TraceStore.put("localLlm.warmup.modelHash", SafeRedactor.hashValue(warmupModel));
        TraceStore.put("localLlm.warmup.modelLength", warmupModel == null ? 0 : warmupModel.length());
        TraceStore.put("localLlm.warmup.targetDim", Math.max(0, warmupDimensions));
        TraceStore.put("localLlm.warmup.returnedDim", Math.max(0, returnedDim));
        String safeReason = SafeRedactor.traceLabel(reason);
        if (safeReason != null && !safeReason.isBlank()) {
            TraceStore.put("localLlm.warmup.reason", safeReason);
        }
    }

    static String generatedWindowsStartCommand(String host) {
        String h = normalizeOllamaHostForEnv(host);
        return "start \"Ollama " + h + "\" cmd.exe /k \"set OLLAMA_HOST=" + h + "&& ollama serve\"";
    }

    static String normalizeOllamaHostForEnv(String host) {
        String h = trimToNull(host);
        if (h == null) {
            return DEFAULT_OLLAMA_HOST_ENV;
        }
        if (h.startsWith("http://") || h.startsWith("https://")) {
            try {
                URI uri = URI.create(h);
                String uriHost = uri.getHost();
                if (uriHost != null && !uriHost.isBlank()) {
                    String normalizedHost = uriHost.contains(":") && !uriHost.startsWith("[")
                            ? "[" + uriHost + "]"
                            : uriHost;
                    int port = uri.getPort();
                    return safeOllamaHostEnvOrDefault(port >= 0 ? normalizedHost + ":" + port : normalizedHost);
                }
            } catch (Exception ignore) {
                traceSuppressed("ollama.hostUri", ignore);
                // fall through to string cleanup
            }
            h = h.replaceFirst("^https?://", "");
        }
        int slash = h.indexOf('/');
        return safeOllamaHostEnvOrDefault(slash >= 0 ? h.substring(0, slash) : h);
    }

    private static String safeOllamaHostEnvOrDefault(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return DEFAULT_OLLAMA_HOST_ENV;
        }
        boolean hasHostChar = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c)
                    || c == '.'
                    || c == '-'
                    || c == ':'
                    || c == '['
                    || c == ']';
            if (!allowed) {
                return DEFAULT_OLLAMA_HOST_ENV;
            }
            if (Character.isLetterOrDigit(c) || c == '[') {
                hasHostChar = true;
            }
        }
        return hasHostChar ? value : DEFAULT_OLLAMA_HOST_ENV;
    }

    private static String readBounded(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        byte[] buf = stream.readNBytes(4096);
        return new String(buf, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    }

    private static String shortErr(Throwable t) {
        if (t == null) {
            return "";
        }
        String msg = t.getMessage();
        String s = msg == null || msg.isBlank()
                ? "startup_failure messagePresent=false"
                : "startup_failure messagePresent=true messageHash=" + SafeRedactor.hashValue(msg)
                + " messageLength=" + msg.length();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static String bodyHash(String s) {
        return s == null || s.isBlank() ? "" : SafeRedactor.hashValue(s);
    }

    private static int bodyLength(String s) {
        return s == null ? 0 : s.length();
    }

    private static String safeUrlDiagnostic(String url) {
        return "urlHost=" + safeUrlHost(url)
                + " urlHash=" + safeUrlHash(url)
                + " urlLength=" + safeUrlLength(url);
    }

    private static String safeUrlHost(String url) {
        String value = trimToNull(url);
        if (value == null) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            int port = uri.getPort();
            return port > 0 ? host.toLowerCase(java.util.Locale.ROOT) + ":" + port : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignore) {
            traceSuppressed("ollama.urlHost", ignore);
            return "";
        }
    }

    private static String safeUrlHash(String url) {
        String value = trimToNull(url);
        return value == null ? "" : SafeRedactor.hashValue(value);
    }

    private static int safeUrlLength(String url) {
        String value = trimToNull(url);
        return value == null ? 0 : value.length();
    }

    private static String safeOllamaHostForLog(String host) {
        String h = normalizeOllamaHostForEnv(host);
        int at = h.lastIndexOf('@');
        return at >= 0 && at + 1 < h.length() ? h.substring(at + 1) : h;
    }

    private static String safeOllamaHostHash(String host) {
        return SafeRedactor.hashValue(normalizeOllamaHostForEnv(host));
    }

    private static String stripTrailingSlash(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static int boundedMillis(Duration duration, int fallback) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return fallback;
        }
        return (int) Math.min(Integer.MAX_VALUE, duration.toMillis());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = "local_llm_suppressed";
        TraceStore.put("localLlm.suppressed.stage", safeStage);
        TraceStore.put("localLlm.suppressed.errorType", errorType);
        TraceStore.put("localLlm.suppressed." + safeStage, true);
        TraceStore.put("localLlm.suppressed." + safeStage + ".errorType", errorType);
    }

    private boolean envBool(String key, boolean fallback) {
        if (env == null) {
            return fallback;
        }
        Boolean value = env.getProperty(key, Boolean.class);
        return value == null ? fallback : value;
    }

    private int envInt(String key, int fallback) {
        if (env == null) {
            return fallback;
        }
        Integer value = env.getProperty(key, Integer.class);
        return value == null ? fallback : value;
    }

    private long envLong(String key, long fallback) {
        if (env == null) {
            return fallback;
        }
        Long value = env.getProperty(key, Long.class);
        return value == null ? fallback : value;
    }

    private Duration envDuration(String key, Duration fallback) {
        if (env == null) {
            return fallback;
        }
        Duration value = env.getProperty(key, Duration.class);
        return value == null ? fallback : value;
    }

    private String envString(String key, String fallback) {
        if (env == null) {
            return fallback;
        }
        String value = env.getProperty(key);
        return value == null ? fallback : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    static java.util.List<String> splitCommand(String cmd) {
        java.util.List<String> out = new java.util.ArrayList<>();
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
