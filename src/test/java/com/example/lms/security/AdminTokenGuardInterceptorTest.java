package com.example.lms.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminTokenGuardInterceptorTest {

    @Test
    void prodWithoutConfiguredTokenDenies() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("", "", false, false, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void wrongTokenDenies() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "local");
        MockHttpServletRequest request = request("/api/settings/model");
        request.addHeader(AdminTokenGuardInterceptor.HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void adminTokenHeaderAccepts() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        request.addHeader(AdminTokenGuardInterceptor.HEADER, "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void ownerTokenHeaderAccepts() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("", "owner-secret", false, false, "prod");
        MockHttpServletRequest request = request("/model-settings");
        request.addHeader(AdminTokenGuardInterceptor.OWNER_HEADER, "owner-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void cookieTokenAccepts() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, false, "prod");
        MockHttpServletRequest request = request("/model-settings");
        request.setCookies(new Cookie(AdminTokenGuardInterceptor.COOKIE_NAME, "admin-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void prodQueryTokenDoesNotAuthenticate() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, true, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        request.setParameter("adminToken", "admin-secret");
        request.setParameter("token", "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void localQueryTokenDoesNotAuthenticateEvenWhenEnabled() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, true, "local");
        MockHttpServletRequest request = request("/api/settings/model");
        request.setParameter("adminToken", "admin-secret");
        request.setParameter("token", "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void ownerKeyHeaderDoesNotAuthenticate() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("", "owner-secret", false, false, "prod");
        MockHttpServletRequest request = request("/api/settings/model");
        request.addHeader("X-Owner-Key", "owner-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void denialHintDoesNotAdvertiseQueryToken() throws Exception {
        AdminTokenGuardInterceptor interceptor = interceptor("admin-secret", "", false, true, "local");
        MockHttpServletRequest request = request("/admin/debug");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertFalse(response.getContentAsString().contains("adminToken"));
        assertFalse(response.getContentAsString().contains("?token"));
    }

    private static AdminTokenGuardInterceptor interceptor(String adminToken,
                                                         String ownerToken,
                                                         boolean tokenRequired,
                                                         boolean allowQueryToken,
                                                         String activeProfiles) {
        AdminTokenGuardInterceptor interceptor = new AdminTokenGuardInterceptor();
        ReflectionTestUtils.setField(interceptor, "expectedToken", adminToken);
        ReflectionTestUtils.setField(interceptor, "ownerToken", ownerToken);
        ReflectionTestUtils.setField(interceptor, "tokenRequired", tokenRequired);
        ReflectionTestUtils.setField(interceptor, "allowQueryToken", allowQueryToken);
        ReflectionTestUtils.setField(interceptor, "activeProfiles", activeProfiles);
        return interceptor;
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
