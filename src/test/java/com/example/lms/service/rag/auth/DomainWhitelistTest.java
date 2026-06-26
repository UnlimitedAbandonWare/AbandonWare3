package com.example.lms.service.rag.auth;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainWhitelistTest {

    @Test
    void allowlistMatchesExactHostOrSubdomainButNotSuffixCollision() {
        DomainWhitelist whitelist = new DomainWhitelist();
        whitelist.setEnableDomainFilter(true);
        whitelist.setDomainAllowlist(List.of("openai.com"));

        assertTrue(whitelist.isOfficial("https://openai.com/docs"));
        assertTrue(whitelist.isOfficial("https://platform.openai.com/docs"));
        assertFalse(whitelist.isOfficial("https://notopenai.com/phish"));
    }

    @Test
    void invalidUrlHostParseLeavesRedactedTraceBreadcrumb() {
        DomainWhitelist whitelist = new DomainWhitelist();

        TraceStore.clear();
        String host = whitelist.extractHost("http://[raw-domain-secret");

        assertNull(host);
        assertTrue((Boolean) TraceStore.get("web.domainWhitelist.extractHost.failed"));
        assertTrue(TraceStore.get("web.domainWhitelist.extractHost.errorType") instanceof String);
        assertFalse(TraceStore.getAll().toString().contains("raw-domain-secret"));
    }
}
