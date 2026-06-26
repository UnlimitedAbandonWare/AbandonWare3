package com.example.lms.service.correction;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryCorrectionServicesTest {

    @Test
    void defaultCorrectionNormalizesMinusSignWithoutRegexFailure() {
        DefaultQueryCorrectionService service = new DefaultQueryCorrectionService();

        assertEquals("alpha-beta", service.correct("alpha−−beta"));
    }

    @Test
    void vectorAliasCorrectionNormalizesMinusSignWithoutRegexFailure() {
        VectorAliasCorrector corrector = new VectorAliasCorrector(0.62, 3);

        assertEquals(Optional.of("alpha-beta"), corrector.correct("alpha−−beta"));
    }
}
