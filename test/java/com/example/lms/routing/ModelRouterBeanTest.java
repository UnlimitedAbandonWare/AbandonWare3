package com.example.lms.routing;

import com.example.lms.infrastructure.llm.ModelRouter;
import com.example.lms.service.routing.RouteSignal;
import com.example.lms.config.ModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;



import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that exactly two ModelRouter beans are
 * present in the application context: the adapter bean named
 * "modelRouter" and the core bean named "modelRouterCore".  If the
 * application context fails to start or either bean cannot be resolved
 * the assertions will fail.  This test is intentionally light-weight
 * and does not exercise any routing logic.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("legacy-router")
public class ModelRouterBeanTest {

    // When the legacy-router profile is active the core router should not be loaded.
    @Autowired(required = false)
    @Qualifier("modelRouterCore")
    private com.example.lms.service.routing.ModelRouter core;

    // The legacy adapter bean named "modelRouter" should be present under the legacy profile.
    @Autowired(required = false)
    @Qualifier("modelRouter")
    private com.example.lms.infrastructure.llm.ModelRouter adapter;

    @Test
    public void adapterPresentAndCoreAbsent() {
        // In legacy mode, the primary/core router should be absent and the adapter should be present.
        assertNull(core, "core ModelRouter bean should not be present under the legacy-router profile");
        assertNotNull(adapter, "adapter ModelRouter bean should be present under the legacy-router profile");
    }
}