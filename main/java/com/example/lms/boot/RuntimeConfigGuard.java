package com.example.lms.boot;

import com.example.lms.config.ConfigValueGuards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class RuntimeConfigGuard implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigGuard.class);
    private static final String PREFIX = "[AWX][runtime-config]";
    private static final Set<String> SAFE_ACTUATOR_ENDPOINTS = Set.of("health", "info");
    private static final long MIN_ONNX_MODEL_BYTES = 1024L * 1024L;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Evaluation evaluation = evaluate(environment);
        if (!evaluation.enabled()) {
            return;
        }
        for (Finding finding : evaluation.findings()) {
            log.warn("{} status={} profile={} property={} reason={} classification={}",
                    PREFIX,
                    evaluation.strict() ? "blocked" : "warning",
                    evaluation.profileLabel(),
                    finding.property(),
                    finding.reason(),
                    finding.classification());
        }
        if (evaluation.strict() && !evaluation.findings().isEmpty()) {
            throw new IllegalStateException(PREFIX + " blocked profile=" + evaluation.profileLabel()
                    + " findings=" + summarize(evaluation.findings()));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static Evaluation evaluate(Environment env) {
        boolean enabled = bool(env, "runtime.config.guard.enabled", true);
        Set<String> profiles = profiles(env);
        boolean strict = profiles.contains("prod") || bool(env, "runtime.config.guard.strict", false);
        List<Finding> findings = new ArrayList<>();
        if (!enabled) {
            return new Evaluation(false, strict, profileLabel(profiles), findings);
        }

        checkActuator(env, findings);
        checkDebugDump(env, findings);
        checkCors(env, findings);
        checkSsl(env, findings);
        checkRequiredSecrets(env, findings);
        checkBackgroundWorkloads(env, findings);
        checkOnnx(env, findings);

        return new Evaluation(true, strict, profileLabel(profiles), findings);
    }

    private static void checkActuator(Environment env, List<Finding> findings) {
        Set<String> exposed = csv(prop(env, "management.endpoints.web.exposure.include", "health"));
        if (exposed.contains("*")) {
            findings.add(new Finding("management.endpoints.web.exposure.include", "wildcard_actuator_exposure"));
        }
        for (String endpoint : exposed) {
            if (!SAFE_ACTUATOR_ENDPOINTS.contains(endpoint) && !"*".equals(endpoint)) {
                findings.add(new Finding("management.endpoints.web.exposure.include", "unsafe_actuator_endpoint_" + endpoint));
            }
        }
        if ("always".equalsIgnoreCase(prop(env, "management.endpoint.env.show-values", ""))) {
            findings.add(new Finding("management.endpoint.env.show-values", "env_values_exposed"));
        }
        if (bool(env, "management.endpoint.httptrace.enabled", false)) {
            findings.add(new Finding("management.endpoint.httptrace.enabled", "httptrace_exposed"));
        }
    }

    private static void checkDebugDump(Environment env, List<Finding> findings) {
        if (bool(env, "lms.debug.prompts.dump", false)) {
            findings.add(new Finding("lms.debug.prompts.dump", "prompt_dump_enabled"));
        }
        if (bool(env, "lms.debug.responses.dump", false)) {
            findings.add(new Finding("lms.debug.responses.dump", "response_dump_enabled"));
        }
        if (!bool(env, "lms.debug.mask-secrets", true)) {
            findings.add(new Finding("lms.debug.mask-secrets", "secret_masking_disabled"));
        }
    }

    private static void checkCors(Environment env, List<Finding> findings) {
        boolean credentials = bool(env, "lms.cors.allow-credentials", false);
        Set<String> origins = csv(prop(env, "lms.cors.allowed-origins", ""));
        Set<String> patterns = csv(prop(env, "lms.cors.allowed-origin-patterns", ""));
        if (credentials && (origins.contains("*") || patterns.contains("*"))) {
            findings.add(new Finding("lms.cors.allowed-origin-patterns", "wildcard_cors_with_credentials"));
        }
    }

    private static void checkSsl(Environment env, List<Finding> findings) {
        if (!bool(env, "server.ssl.enabled", false)) {
            return;
        }
        requirePresent(env, findings, "server.ssl.key-store", "ssl_key_store_missing");
        requirePresent(env, findings, "server.ssl.key-store-password", "ssl_key_store_password_missing");
        requirePresent(env, findings, "server.ssl.key-password", "ssl_key_password_missing");
    }

    private static void checkRequiredSecrets(Environment env, List<Finding> findings) {
        requirePresent(env, findings, "spring.datasource.password", "db_password_missing");
        requirePresent(env, findings, "domain.allowlist.admin-token", "admin_token_missing");
        requirePresent(env, findings, "security.admin-secret", "admin_secret_missing");
        requirePresent(env, findings, "security.remember-me-key", "remember_me_key_missing");
        requirePresent(env, findings, "security.bootstrap-admin.password", "bootstrap_admin_password_missing");
        if (bool(env, "probe.search.enabled", false)) {
            requirePresent(env, findings, "probe.admin-token", "probe_admin_token_missing");
        }
    }

    private static void checkOnnx(Environment env, List<Finding> findings) {
        if (!bool(env, "onnx.enabled", false)
                && !bool(env, "zsys.onnx.enabled", false)
                && !bool(env, "abandonware.reranker.onnx.runtime-enabled", false)) {
            return;
        }
        List<String> paths = List.of(
                prop(env, "onnx.model.path.cross-encoder", ""),
                prop(env, "onnx.model-path", ""),
                prop(env, "abandonware.reranker.onnx.model-path", "")
        );
        boolean hasRealPath = paths.stream().anyMatch(RuntimeConfigGuard::usableOnnxModelPath);
        if (!hasRealPath) {
            findings.add(new Finding("onnx.model-path", "placeholder_or_too_small_model"));
        }
    }

    private static void checkBackgroundWorkloads(Environment env, List<Finding> findings) {
        if (bool(env, "train_idle.enabled", false)) {
            findings.add(new Finding("train_idle.enabled", "train_idle_enabled_in_strict_profile"));
        }
        if (bool(env, "autolearn.enabled", false)) {
            findings.add(new Finding("autolearn.enabled", "autolearn_enabled_in_strict_profile"));
        }
        if (bool(env, "uaw.autolearn.enabled", false)) {
            findings.add(new Finding("uaw.autolearn.enabled", "uaw_autolearn_enabled_in_strict_profile"));
        }
        if (bool(env, "local-llm.enabled", false)) {
            findings.add(new Finding("local-llm.enabled", "local_llm_enabled_in_strict_profile"));
        }
        if (bool(env, "local-llm.autostart", false)) {
            findings.add(new Finding("local-llm.autostart", "local_llm_autostart_enabled_in_strict_profile"));
        }
        if (bool(env, "selfask.enabled", false)) {
            findings.add(new Finding("selfask.enabled", "selfask_enabled_in_strict_profile"));
        }
        if (bool(env, "tavily.enabled", false)) {
            findings.add(new Finding("tavily.enabled", "tavily_enabled_in_strict_profile"));
        }
        if (bool(env, "soak.enabled", false)
                && bool(env, "soak.quick-runner.enabled", false)
                && bool(env, "soak.quick-runner.cli", false)
                && bool(env, "soak.quick-runner.exit-after-run", true)) {
            findings.add(new Finding("soak.quick-runner.exit-after-run",
                    "soak_quick_runner_exit_enabled_in_strict_profile"));
        }
    }

    private static void requirePresent(Environment env, List<Finding> findings, String property, String reason) {
        if (ConfigValueGuards.isMissing(prop(env, property, ""))) {
            findings.add(new Finding(property, reason));
        }
    }

    private static boolean missingOrPlaceholderPath(String value) {
        if (ConfigValueGuards.isMissing(value)) {
            return true;
        }
        String s = value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        return s.equals("/models/cross-encoder.onnx")
                || s.equals("file:/opt/models/cross-encoder.onnx")
                || s.equals("classpath:models/your-cross-encoder.onnx")
                || s.equals("models/your-cross-encoder.onnx")
                || s.endsWith("/models/your-cross-encoder.onnx")
                || s.endsWith("/your-cross-encoder.onnx")
                || s.contains("/placeholder/");
    }

    private static boolean usableOnnxModelPath(String value) {
        if (missingOrPlaceholderPath(value)) {
            return false;
        }
        String s = value.trim();
        String normalized = s.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("classpath:")) {
            return true;
        }
        try {
            Path path = normalized.startsWith("file:")
                    ? Path.of(URI.create(s))
                    : Path.of(s);
            return Files.isRegularFile(path) && Files.size(path) >= MIN_ONNX_MODEL_BYTES;
        } catch (Exception ignore) {
            traceSuppressed("runtimeConfig.onnxModelPath", ignore);
            return false;
        }
    }

    private static void traceSuppressed(String stage, Exception failure) {
        if (log.isDebugEnabled()) {
            log.debug("{} suppressed stage={} errorType={}",
                    PREFIX,
                    stage,
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
        }
    }

    private static boolean bool(Environment env, String property, boolean fallback) {
        String value = prop(env, property, "");
        if (value.isBlank()) {
            return fallback;
        }
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("y");
    }

    private static String prop(Environment env, String property, String fallback) {
        String value = env == null ? null : env.getProperty(property);
        return value == null ? fallback : value.trim();
    }

    private static Set<String> profiles(Environment env) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (env != null) {
            Arrays.stream(env.getActiveProfiles()).map(RuntimeConfigGuard::norm).filter(s -> !s.isBlank()).forEach(out::add);
            if (out.isEmpty()) {
                Arrays.stream(env.getDefaultProfiles()).map(RuntimeConfigGuard::norm).filter(s -> !s.isBlank()).forEach(out::add);
            }
        }
        if (out.isEmpty()) {
            out.add("default");
        }
        return out;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String profileLabel(Set<String> profiles) {
        return String.join(",", profiles);
    }

    private static Set<String> csv(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String token : raw.split(",")) {
            String t = norm(token);
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String summarize(List<Finding> findings) {
        return findings.stream()
                .map(f -> f.property() + ":" + f.reason() + ":" + f.classification())
                .distinct()
                .limit(12)
                .reduce((a, b) -> a + "," + b)
                .orElse("none");
    }

    private static String classify(String reason) {
        String r = norm(reason);
        if (r.contains("actuator") || r.contains("env_values") || r.contains("httptrace")) {
            return "actuator-exposure";
        }
        if (r.contains("missing") || r.contains("secret") || r.contains("password") || r.contains("token")
                || r.contains("key_")) {
            return "secret-required";
        }
        if (r.contains("debug") || r.contains("dump")) {
            return "debug-exposure";
        }
        if (r.contains("cors")) {
            return "cors-risk";
        }
        if (r.contains("ssl")) {
            return "ssl-risk";
        }
        if (r.contains("train_idle") || r.contains("autolearn") || r.contains("selfask") || r.contains("tavily")
                || r.contains("soak_quick_runner")) {
            return "background-workload";
        }
        if (r.contains("onnx") || r.contains("local_llm")) {
            return "local-model-risk";
        }
        return "runtime-config-risk";
    }

    record Evaluation(boolean enabled, boolean strict, String profileLabel, List<Finding> findings) {
    }

    record Finding(String property, String reason, String classification) {
        Finding(String property, String reason) {
            this(property, reason, RuntimeConfigGuard.classify(reason));
        }
    }
}
