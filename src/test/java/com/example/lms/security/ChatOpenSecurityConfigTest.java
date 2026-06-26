package com.example.lms.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatOpenSecurityConfigTest {

    @Test
    void defaultCorsDoesNotPublishWildcardCredentials() {
        ChatOpenSecurityConfig config = new ChatOpenSecurityConfig();
        ReflectionTestUtils.setField(config, "corsAllowCredentials", false);
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "");
        ReflectionTestUtils.setField(config, "corsAllowedOriginPatterns", "");

        CorsConfiguration cors = cors(config);

        assertFalse(Boolean.TRUE.equals(cors.getAllowCredentials()));
        assertNull(cors.getAllowedOrigins());
        assertNull(cors.getAllowedOriginPatterns());
    }

    @Test
    void explicitCorsOriginsAreBounded() {
        ChatOpenSecurityConfig config = new ChatOpenSecurityConfig();
        ReflectionTestUtils.setField(config, "corsAllowCredentials", true);
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "https://admin.example.test");
        ReflectionTestUtils.setField(config, "corsAllowedOriginPatterns", "https://*.example.test");

        CorsConfiguration cors = cors(config);

        assertTrue(Boolean.TRUE.equals(cors.getAllowCredentials()));
        assertEquals("https://admin.example.test", cors.getAllowedOrigins().get(0));
        assertEquals("https://*.example.test", cors.getAllowedOriginPatterns().get(0));
    }

    @Test
    void actuatorCsrfBypassIsLimitedToHealthAndInfo() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/security/ChatOpenSecurityConfig.java"));
        String csrfBlock = source.substring(source.indexOf(".csrf(csrf -> csrf"), source.indexOf(".authorizeHttpRequests"));

        assertTrue(source.contains("AntPathRequestMatcher.antMatcher(\"/actuator/health\")"));
        assertTrue(source.contains("AntPathRequestMatcher.antMatcher(\"/actuator/info\")"));
        assertTrue(source.contains(".requestMatchers(AntPathRequestMatcher.antMatcher(\"/actuator/**\")).denyAll()"));
        assertFalse(csrfBlock.contains("AntPathRequestMatcher.antMatcher(\"/actuator/**\")"));
    }

    private static CorsConfiguration cors(ChatOpenSecurityConfig config) {
        CorsConfigurationSource source = config.corsConfigurationSource();
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/chat"));
    }
}
