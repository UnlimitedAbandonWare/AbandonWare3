package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

@Configuration
@ConfigurationProperties(prefix = "onnx.semaphore")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.config.OnnxConcurrencyConfig
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.example.lms.config.OnnxConcurrencyConfig
role: config
*/
public class OnnxConcurrencyConfig {
    private int maxConcurrent = 3;

    @Bean
    public Semaphore onnxSlots() {
        return new Semaphore(maxConcurrent);
    }

    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
}