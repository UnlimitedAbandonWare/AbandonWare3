package com.example.lms.routing;

import com.example.lms.service.routing.ModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;



import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify that when the legacy router profile is not active only the core
 * ModelRouter bean is present in the application context.  The adapter
 * bean "modelRouter" should be absent under the default profile.  This
 * test complements {@link ModelRouterBeanTest} which exercises the
 * legacy-router profile.
 */
@SpringBootTest
public class ModelRouterCoreOnlyTest {

    @Autowired(required = false)
    @Qualifier("modelRouterCore")
    private ModelRouter core;

    @Autowired(required = false)
    @Qualifier("modelRouter")
    private Object adapter;

    @Test
    public void onlyCoreBeanPresent() {
        assertNotNull(core, "core ModelRouter bean should be present by default");
        assertNull(adapter, "adapter ModelRouter bean should not be present when legacy-router profile is inactive");
    }
}