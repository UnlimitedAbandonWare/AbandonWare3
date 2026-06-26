package com.example.lms.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerKeyBootstrapFilterTest {
    private static final String OWNER_UUID = "22222222-2222-4222-8222-222222222222";

    @Test
    void newOwnerCookieEmitsSingleSetCookieHeader() throws Exception {
        OwnerKeyBootstrapFilter filter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat-ui.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        var headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        String header = headers.get(0);
        assertTrue(header.contains(OwnerKeyBootstrapFilter.OWNER_KEY + "="));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
    }

    @Test
    void existingOwnerCookieRefreshEmitsSingleSetCookieHeader() throws Exception {
        OwnerKeyBootstrapFilter filter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat-ui.html");
        request.setCookies(new Cookie(OwnerKeyBootstrapFilter.OWNER_KEY, OWNER_UUID));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        var headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        assertTrue(headers.get(0).contains(OwnerKeyBootstrapFilter.OWNER_KEY + "=" + OWNER_UUID));
    }

    @Test
    void unsafeOwnerCookieIsRotated() throws Exception {
        OwnerKeyBootstrapFilter filter = new OwnerKeyBootstrapFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat-ui.html");
        request.setCookies(new Cookie(OwnerKeyBootstrapFilter.OWNER_KEY, "existing-owner"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        var headers = response.getHeaders("Set-Cookie");
        assertEquals(1, headers.size());
        String header = headers.get(0);
        assertTrue(header.contains(OwnerKeyBootstrapFilter.OWNER_KEY + "="));
        assertTrue(header.matches(".*" + OwnerKeyBootstrapFilter.OWNER_KEY
                + "=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*"));
        assertTrue(!header.contains("existing-owner"));
    }
}
