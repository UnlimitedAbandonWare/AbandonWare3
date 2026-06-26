package com.example.lms.llm.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llm.gateway")
public class LlmGatewayProperties {

    public enum Enforcement {
        OBSERVE,
        ENFORCE
    }

    private boolean enabled = true;
    private Enforcement enforcement = Enforcement.OBSERVE;
    private int minRouteScore = 55;
    private boolean throwOnEmptyFailure = false;
    private Probe probe = new Probe();
    private Cloud cloud = new Cloud();
    private SpecRegistry specRegistry = new SpecRegistry();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Enforcement getEnforcement() {
        return enforcement;
    }

    public void setEnforcement(Enforcement enforcement) {
        this.enforcement = enforcement == null ? Enforcement.OBSERVE : enforcement;
    }

    public boolean isEnforce() {
        return enabled && enforcement == Enforcement.ENFORCE;
    }

    public int getMinRouteScore() {
        return minRouteScore;
    }

    public void setMinRouteScore(int minRouteScore) {
        this.minRouteScore = minRouteScore;
    }

    public boolean isThrowOnEmptyFailure() {
        return throwOnEmptyFailure;
    }

    public void setThrowOnEmptyFailure(boolean throwOnEmptyFailure) {
        this.throwOnEmptyFailure = throwOnEmptyFailure;
    }

    public Probe getProbe() {
        return probe;
    }

    public void setProbe(Probe probe) {
        this.probe = probe == null ? new Probe() : probe;
    }

    public Cloud getCloud() {
        return cloud;
    }

    public void setCloud(Cloud cloud) {
        this.cloud = cloud == null ? new Cloud() : cloud;
    }

    public SpecRegistry getSpecRegistry() {
        return specRegistry;
    }

    public void setSpecRegistry(SpecRegistry specRegistry) {
        this.specRegistry = specRegistry == null ? new SpecRegistry() : specRegistry;
    }

    public static class Probe {
        private boolean enabled = true;
        private long timeoutMs = 1500L;
        private long ttlMs = 30000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getTtlMs() {
            return ttlMs;
        }

        public void setTtlMs(long ttlMs) {
            this.ttlMs = ttlMs;
        }
    }

    public static class Cloud {
        private boolean enabled = false;
        private String routeKey = "api3";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRouteKey() {
            return routeKey;
        }

        public void setRouteKey(String routeKey) {
            this.routeKey = routeKey;
        }
    }

    public static class SpecRegistry {
        private boolean enabled = true;
        private String path = "./data/model-spec-registry.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
