package com.example.lms.search.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveSearchQueryVariantsTest {

    @Test
    void keepsBaseQueryFirstAndBounded() {
        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu",
                List.of("alpha beta"),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.BRAVE,
                        true,
                        3,
                        3500,
                        3500,
                        700,
                        true,
                        false,
                        false));

        assertEquals("alpha beta", plan.queries().get(0));
        assertTrue(plan.queries().size() <= 3);
        assertEquals("recall-policy", plan.triggerReason());
        assertTrue(plan.perCallMs() >= 700);
    }

    @Test
    void slicesCompoundQueryOnlyWhenAdaptiveIsEnabled() {
        String query = "First release note changed the API. Second sentence explains migration. Third sentence asks for examples.";

        AdaptiveSearchQueryVariants.Plan enabled = AdaptiveSearchQueryVariants.plan(
                query,
                List.of(query),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        4,
                        4500,
                        4500,
                        600,
                        false,
                        false,
                        false));

        AdaptiveSearchQueryVariants.Plan disabled = AdaptiveSearchQueryVariants.plan(
                query,
                List.of(query),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        false,
                        4,
                        4500,
                        4500,
                        600,
                        false,
                        false,
                        false));

        assertEquals("compound-query", enabled.triggerReason());
        assertTrue(enabled.sliceCount() > 0 || enabled.expansionCount() > 0);
        assertEquals("disabled", disabled.triggerReason());
        assertEquals(1, disabled.queries().size());
    }

    @Test
    void shortSuccessfulQueryStaysBaseOnly() {
        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                "spring boot",
                List.of("spring boot"),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.BRAVE,
                        true,
                        3,
                        3500,
                        3500,
                        700,
                        false,
                        false,
                        false));

        assertEquals("base-only", plan.triggerReason());
        assertEquals(1, plan.queries().size());
        assertFalse(plan.enabled() && plan.variants().size() > 0);
    }
}
