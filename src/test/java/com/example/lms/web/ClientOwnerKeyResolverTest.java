package com.example.lms.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientOwnerKeyResolverTest {

    @Test
    void noServletRequestFallsBackToSystemOwnerKey() {
        ClientOwnerKeyResolver resolver = new ClientOwnerKeyResolver();

        assertEquals("system:no-request", resolver.ownerKey());
    }

    @Test
    void springContextWithoutServletRequestFallsBackToSystemOwnerKey() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ClientOwnerKeyResolver.class);
            context.refresh();

            assertEquals("system:no-request", context.getBean(ClientOwnerKeyResolver.class).ownerKey());
        }
    }

    @Test
    void ownerKeyCookieStillWinsWhenRequestIsPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(
                OwnerKeyBootstrapFilter.OWNER_KEY,
                "22222222-2222-4222-8222-222222222222"));
        ClientOwnerKeyResolver resolver = new ClientOwnerKeyResolver(request);

        assertEquals("22222222-2222-4222-8222-222222222222", resolver.ownerKey());
    }
}
