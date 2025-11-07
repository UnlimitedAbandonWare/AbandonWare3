package com.abandonware.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "secrets")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.config.AppSecretsProperties
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.config.AppSecretsProperties
role: config
*/
public class AppSecretsProperties {
    private String upstashApiKey;
    private String naverClientId;
    private String naverClientSecret;
    private String otlpEndpoint;

    public String getUpstashApiKey() { return upstashApiKey; }
    public void setUpstashApiKey(String v) { this.upstashApiKey = v; }

    public String getNaverClientId() { return naverClientId; }
    public void setNaverClientId(String v) { this.naverClientId = v; }

    public String getNaverClientSecret() { return naverClientSecret; }
    public void setNaverClientSecret(String v) { this.naverClientSecret = v; }

    public String getOtlpEndpoint() { return otlpEndpoint; }
    public void setOtlpEndpoint(String v) { this.otlpEndpoint = v; }
}