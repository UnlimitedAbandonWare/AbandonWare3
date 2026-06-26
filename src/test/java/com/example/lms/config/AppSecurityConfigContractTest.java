package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSecurityConfigContractTest {

    private static final Path SOURCE = Path.of("main/java/com/example/lms/config/AppSecurityConfig.java");

    @Test
    void noCatchAllPermitAllChainRemains() throws Exception {
        String source = Files.readString(SOURCE);

        assertEquals(1, occurrences(source, "anyRequest().permitAll()"));
        assertTrue(source.contains("http.securityMatcher(\"/api/probe/**\", \"/internal/probe/**\")"));
        assertFalse(source.contains("LOWEST_PRECEDENCE"));
        assertEquals(1, occurrences(source, ".securityMatcher(\"/**\")"));
        assertTrue(source.contains(".rememberMe("));
        assertTrue(source.contains(".addFilterBefore(adminTokenGuardFilter, UsernamePasswordAuthenticationFilter.class)"));
    }

    @Test
    void settingsAdminRulePrecedesPublicMatchers() throws Exception {
        String source = Files.readString(SOURCE);

        int settingsRule = source.indexOf("\"/api/settings\"");
        int routerRule = source.indexOf("\"/api/router\"");
        int diagnosticsGet = source.indexOf("\"/api/diagnostics/**\"");
        int datasetPost = source.indexOf("requestMatchers(HttpMethod.POST, \"/internal/dataset/**\").permitAll()");
        int publicChat = source.indexOf("\"/api/chat/**\"");
        int authenticatedFallback = source.indexOf(".anyRequest().authenticated()");

        assertTrue(settingsRule > 0);
        assertTrue(routerRule > settingsRule);
        assertTrue(diagnosticsGet > routerRule);
        assertTrue(datasetPost > diagnosticsGet);
        assertTrue(publicChat > datasetPost);
        assertTrue(publicChat > settingsRule);
        assertTrue(authenticatedFallback > publicChat);
        assertTrue(source.contains(").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/router\", \"/api/router/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/internal/soak\", \"/internal/soak/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/internal/autoevolve\", \"/internal/autoevolve/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/internal/nn\", \"/internal/nn/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/flows\", \"/flows/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/admin\", \"/admin/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/admin/fine-tuning\", \"/api/admin/fine-tuning/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/internal/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/learning/gemini\", \"/api/learning/gemini/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(\"/api/integrations/check\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/v1/tasks/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/rag/probe\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/nova/outbox/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/train\", \"/api/train/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/translate/train\", \"/api/translate/train-now\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/webhooks/channel\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/messages/trigger\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/api/diagnostics/**\").hasRole(\"ADMIN\")"));
        assertTrue(source.contains("requestMatchers(HttpMethod.GET, \"/api/diagnostics/**\").permitAll()"));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/internal/dataset/**\").permitAll()"));
    }

    @Test
    void harmonyDashboardAndScoreApiArePublicReadOnlyObservabilitySurfaces() throws Exception {
        String source = Files.readString(SOURCE);

        int harmonyApi = source.indexOf("\"/api/harmony/**\"");
        int faithfulnessMetric = source.indexOf("\"/api/metrics/faithfulness\"");
        int harmonyPage = source.indexOf("\"/harmony\"");
        int authenticatedFallback = source.indexOf(".anyRequest().authenticated()");

        assertTrue(harmonyApi > 0);
        assertTrue(faithfulnessMetric > harmonyApi);
        assertTrue(harmonyPage > faithfulnessMetric);
        assertTrue(authenticatedFallback > harmonyPage);
        assertFalse(source.contains(".requestMatchers(\"/api/**\").permitAll()"));
    }

    @Test
    void harmonyPostRoutesAreNotCsrfIgnoredBecauseTheSurfaceIsReadOnly() throws Exception {
        String source = Files.readString(SOURCE);
        int csrfStart = source.lastIndexOf(".csrf(csrf -> csrf");
        int csrfEnd = source.indexOf(".addFilterBefore", csrfStart);
        String csrfBlock = source.substring(csrfStart, csrfEnd);

        assertFalse(csrfBlock.contains("\"/api/harmony/**\""));
        assertFalse(csrfBlock.contains("\"/harmony\""));
    }

    @Test
    void internalDatasetAndAgentReportPostsAreCsrfIgnoredButNotCatchAllPublicAuth() throws Exception {
        String source = Files.readString(SOURCE);
        int csrfStart = source.indexOf(".csrf(csrf -> csrf");
        int csrfEnd = source.indexOf(".addFilterBefore", csrfStart);
        String csrfBlock = source.substring(csrfStart, csrfEnd);

        assertTrue(csrfBlock.contains("\"/internal/dataset/**\""));
        assertTrue(csrfBlock.contains("\"/api/agent/report/**\""));
        assertTrue(source.contains("requestMatchers(HttpMethod.POST, \"/internal/dataset/**\").permitAll()"));
        assertFalse(source.contains(".requestMatchers(\"/internal/**\").permitAll()"));
        assertFalse(source.contains(".requestMatchers(\"/**\").permitAll()"));
    }

    @Test
    void probeEndpointsReachControllerTokenGateWithoutFrameworkCsrfBlock() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("probeSecurityFilterChain(HttpSecurity http)"));
        assertTrue(source.contains("http.securityMatcher(\"/api/probe/**\", \"/internal/probe/**\")"));
        assertTrue(source.contains(".csrf(csrf -> csrf.disable())"));
        assertTrue(source.contains(".anyRequest().permitAll()"));
        String probeController = Files.readString(Path.of("main/java/com/example/lms/probe/SearchProbeController.java"));
        assertTrue(probeController.contains("@Value(\"${probe.admin-token:}\") String adminToken"));
        assertTrue(probeController.contains("@RequestHeader(value = \"X-Probe-Token\""));
        assertFalse(source.contains(".requestMatchers(\"/api/**\").permitAll()"));
    }

    @Test
    void servletSecurityFilterChainsAreServletOnly() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;"));
        assertMethodPreambleContains(source,
                "public SecurityFilterChain probeSecurityFilterChain",
                "@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)");
        assertMethodPreambleContains(source,
                "public SecurityFilterChain defaultSecurityFilterChain",
                "@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)");
    }

    @Test
    void adminAuthenticationProviderIsChainLocalNotGlobalBean() throws Exception {
        String source = Files.readString(SOURCE);

        assertFalse(source.contains("@Bean\n    public DaoAuthenticationProvider adminAuthProvider"));
        assertTrue(source.contains("private DaoAuthenticationProvider adminAuthProvider("));
        assertTrue(source.contains("AdminDetailsServiceImpl adminDetailsService"));
        assertTrue(source.contains("PasswordEncoder passwordEncoder"));
        assertTrue(source.contains(".authenticationProvider(adminAuthProvider(adminDetailsService::loadUserByUsername, passwordEncoder))"));
    }

    @Test
    void adminDetailsServiceDoesNotPublishSecondGlobalUserDetailsService() throws Exception {
        String source = Files.readString(SOURCE);
        String adminDetailsService = Files.readString(Path.of("main/java/com/example/lms/service/AdminDetailsServiceImpl.java"));

        assertFalse(adminDetailsService.contains("implements UserDetailsService"));
        assertTrue(adminDetailsService.contains("public UserDetails loadUserByUsername(String username)"));
        assertTrue(source.contains(".authenticationProvider(adminAuthProvider(adminDetailsService::loadUserByUsername, passwordEncoder))"));
        assertTrue(source.contains(".userDetailsService(adminDetailsService::loadUserByUsername)"));
    }

    @Test
    void adminTokenMvcGuardCoversInternalPaths() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/AdminTokenGuardWebMvcConfig.java"));

        assertTrue(source.contains("\"/internal/**\""));
        assertTrue(source.contains("\"/internal/nn/**\""));
        assertTrue(source.contains("\"/flows/**\""));
        assertTrue(source.contains("\"/messages/trigger\""));
        assertTrue(source.contains("\"/admin/**\""));
        assertTrue(source.contains("\"/api/admin/fine-tuning/**\""));
        assertTrue(source.contains("\"/api/internal/**\""));
        assertTrue(source.contains("\"/api/learning/gemini/**\""));
        assertTrue(source.contains("\"/api/diagnostics/**\""));
        assertTrue(source.contains("\"/agent/db-context\""));
        assertTrue(source.contains("\"/agent/db-context/**\""));
        assertTrue(source.contains("\"/api/router\""));
        assertTrue(source.contains("\"/api/router/**\""));
        assertTrue(source.contains("\"/api/integrations/check\""));
    }

    @Test
    void publicOperationalWriteRoutesRequireAdminRoleBeforeBroadPublicMatchers() throws Exception {
        String source = Files.readString(SOURCE);

        int tasksAdmin = source.indexOf("requestMatchers(HttpMethod.POST, \"/v1/tasks/**\").hasRole(\"ADMIN\")");
        int ragProbeAdmin = source.indexOf("requestMatchers(HttpMethod.POST, \"/api/rag/probe\").hasRole(\"ADMIN\")");
        int outboxAdmin = source.indexOf("requestMatchers(HttpMethod.POST, \"/api/nova/outbox/**\").hasRole(\"ADMIN\")");
        int trainAdmin = source.indexOf("requestMatchers(HttpMethod.POST, \"/api/train\", \"/api/train/**\").hasRole(\"ADMIN\")");
        int translateAdmin = source.indexOf("requestMatchers(HttpMethod.POST, \"/api/translate/train\", \"/api/translate/train-now\").hasRole(\"ADMIN\")");
        int ragPublic = source.indexOf("\"/api/rag/**\"");
        int chatPublic = source.indexOf("\"/api/chat/**\"");
        int authenticatedFallback = source.indexOf(".anyRequest().authenticated()");

        assertTrue(tasksAdmin > 0);
        assertTrue(ragProbeAdmin > tasksAdmin);
        assertTrue(outboxAdmin > ragProbeAdmin);
        assertTrue(trainAdmin > outboxAdmin);
        assertTrue(translateAdmin > trainAdmin);
        assertTrue(ragProbeAdmin < ragPublic);
        assertTrue(outboxAdmin < authenticatedFallback);
        assertTrue(translateAdmin < authenticatedFallback);
        assertTrue(chatPublic > outboxAdmin);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void assertMethodPreambleContains(String source, String methodSignature, String expected) {
        int method = source.indexOf(methodSignature);
        assertTrue(method > 0, methodSignature);
        String preamble = source.substring(Math.max(0, method - 240), method);
        assertTrue(preamble.contains(expected), expected);
    }
}
