package com.example.lms.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.config.Customizer;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;



@Configuration
@EnableWebSecurity
public class ChatOpenSecurityConfig {

    @Value("${lms.cors.allow-credentials:${LMS_CORS_ALLOW_CREDENTIALS:false}}")
    private boolean corsAllowCredentials;

    @Value("${lms.cors.allowed-origins:${LMS_CORS_ALLOWED_ORIGINS:}}")
    private String corsAllowedOrigins;

    @Value("${lms.cors.allowed-origin-patterns:${LMS_CORS_ALLOWED_ORIGIN_PATTERNS:}}")
    private String corsAllowedOriginPatterns;

    @Bean
    @Order(1)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain chatOpenChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(new OrRequestMatcher(
                AntPathRequestMatcher.antMatcher("/"),
                AntPathRequestMatcher.antMatcher("/index"),
                AntPathRequestMatcher.antMatcher("/index.html"),
                // Do not match the login endpoint here; let the main authentication chain handle it
                AntPathRequestMatcher.antMatcher("/error"),
                AntPathRequestMatcher.antMatcher("/favicon.ico"),
                AntPathRequestMatcher.antMatcher("/assets/**"),
                AntPathRequestMatcher.antMatcher("/css/**"),
                AntPathRequestMatcher.antMatcher("/js/**"),
                AntPathRequestMatcher.antMatcher("/images/**"),
                AntPathRequestMatcher.antMatcher("/chat"),
                AntPathRequestMatcher.antMatcher("/chat/**"),
                AntPathRequestMatcher.antMatcher("/chat-ui"),
                AntPathRequestMatcher.antMatcher("/chat-ui.html"),
                AntPathRequestMatcher.antMatcher("/chat-ui/**"),
                AntPathRequestMatcher.antMatcher("/chat:80"),
                AntPathRequestMatcher.antMatcher("/chat:80/**"),
                AntPathRequestMatcher.antMatcher("/actuator/**"),
                AntPathRequestMatcher.antMatcher("/ws/**"),
                AntPathRequestMatcher.antMatcher("/api/chat/**")
            ))
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(
                    AntPathRequestMatcher.antMatcher("/api/chat/**"),
                    AntPathRequestMatcher.antMatcher("/ws/**"),
                    AntPathRequestMatcher.antMatcher("/actuator/health"),
                    AntPathRequestMatcher.antMatcher("/actuator/info")
                )
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    AntPathRequestMatcher.antMatcher("/actuator/health"),
                    AntPathRequestMatcher.antMatcher("/actuator/info")
                ).permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/**")).denyAll()
                .anyRequest().permitAll()
            )
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.ALWAYS))
            .anonymous(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowCredentials(corsAllowCredentials);
        for (String origin : csv(corsAllowedOrigins)) {
            cfg.addAllowedOrigin(origin);
        }
        for (String pattern : csv(corsAllowedOriginPatterns)) {
            cfg.addAllowedOriginPattern(pattern);
        }
        cfg.addAllowedHeader("*");
        cfg.addAllowedMethod("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
