package com.example.lms.config;

import com.example.lms.service.llm.ReactiveLlmClient;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds the {@code llm.backends} section of {@code application.yml}
 * into a list of backend descriptors.  This configuration is
 * independent of the existing {@link LlmProperties} bean and is
 * specifically used by the reactive LLM client and scheduler.  Each
 * backend may specify a tensor parallel size; when omitted the
 * default value of 1 is used.
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
@Getter
@Setter
public class LlmConfig {
    private List<Backend> backends = new ArrayList<>();

    @Getter
    @Setter
    public static class Backend implements ReactiveLlmClient.Backend {
        private String id;
        private String baseUrl;
        private String model;
        /**
         * The tensor parallel size of this backend.  When null the
         * default size of 1 is assumed.
         */
        private Integer tensorParallelSize;

        @Override
        public String id() {
            return id;
        }

        @Override
        public String baseUrl() {
            return baseUrl;
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public int tensorParallelSize() {
            return tensorParallelSize == null ? 1 : tensorParallelSize;
        }
    }
}