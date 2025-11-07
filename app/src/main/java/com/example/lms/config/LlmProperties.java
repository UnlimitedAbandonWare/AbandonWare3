package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the local LLM router.  This class binds to the
 * {@code llm.*} hierarchy defined in {@code application.yml}.  When running
 * in local-preferred mode the router will attempt to use the configured
 * local endpoints before falling back to a remote provider.
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** The routing mode: local-preferred, remote-only or local-only. */
    private String mode = "local-preferred";
    /** Router level configuration. */
    private Router router = new Router();
    /** List of local backends. */
    private List<Backend> local = new ArrayList<>();
    /** Fallback configuration. */
    private Fallback fallback = new Fallback();

    public String getMode() {
        return mode;
    }
    public void setMode(String mode) {
        this.mode = mode;
    }
    public Router getRouter() {
        return router;
    }
    public void setRouter(Router router) {
        this.router = router;
    }
    public List<Backend> getLocal() {
        return local;
    }
    public void setLocal(List<Backend> local) {
        this.local = local;
    }
    public Fallback getFallback() {
        return fallback;
    }
    public void setFallback(Fallback fallback) {
        this.fallback = fallback;
    }

    public static class Router {
        private String policy = "capacity_first";
        private int timeoutMs = 30000;
        private boolean stream = true;
        private int minTokens = 64;
        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public boolean isStream() { return stream; }
        public void setStream(boolean stream) { this.stream = stream; }
        public int getMinTokens() { return minTokens; }
        public void setMinTokens(int minTokens) { this.minTokens = minTokens; }
    }

    public static class Backend {
        private String id;
        private String baseUrl;
        private String model;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class Fallback {
        private OpenAi openai = new OpenAi();
        public OpenAi getOpenai() { return openai; }
        public void setOpenai(OpenAi openai) { this.openai = openai; }
        public static class OpenAi {
            private boolean enabled = false;
            private String baseUrl;
            private String model;
            private String apiKey;
            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        }
    }
}