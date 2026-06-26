package com.example.lms.dependency;

import ai.onnxruntime.OrtEnvironment;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import net.sourceforge.tess4j.Tesseract;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDependencyContractTest {

    @Test
    void runtimeRagDependenciesAreCompileTimeContracts() {
        assertEquals("Tesseract", Tesseract.class.getSimpleName());
        assertEquals("OrtEnvironment", OrtEnvironment.class.getSimpleName());
        assertTrue(Driver.class.isInterface());
        assertTrue(ContentRetriever.class.isInterface());
        assertEquals("Query", Query.class.getSimpleName());
    }
}
