package com.example.lms.service.vector;

import com.example.lms.service.VectorMetaKeys;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentChunkingServiceTest {

    @Test
    void splitCreatesPhysicalOverlapWithoutTextRepair() throws Exception {
        DocumentChunkingService service = new DocumentChunkingService();
        set(service, "enabled", true);
        set(service, "chunkSizeChars", 128);
        set(service, "overlapChars", 12);
        set(service, "minSplitChars", 129);

        String text = "A".repeat(128) + "B".repeat(128) + "C".repeat(32);
        List<DocumentChunkingService.Chunk> chunks =
                service.split(text, Map.of(VectorMetaKeys.META_DOC_ID, "doc-1"));

        assertEquals(3, chunks.size());
        assertEquals(128, chunks.get(0).text().length());
        assertTrue(chunks.get(1).text().startsWith(chunks.get(0).text().substring(116)));
        assertEquals(0, chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_INDEX));
        assertEquals(3, chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_COUNT));
        assertEquals(12, chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_OVERLAP));
        assertEquals("doc-1#0", chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_ID));
    }

    @Test
    void splitPrefersParagraphBoundaryWhenAvailable() throws Exception {
        DocumentChunkingService service = new DocumentChunkingService();
        set(service, "enabled", true);
        set(service, "chunkSizeChars", 180);
        set(service, "overlapChars", 20);
        set(service, "minSplitChars", 181);

        String first = "alpha paragraph. ".repeat(10);
        String second = "beta paragraph. ".repeat(10);
        String text = first + "\n\n" + second;

        List<DocumentChunkingService.Chunk> chunks =
                service.split(text, Map.of(VectorMetaKeys.META_DOC_ID, "doc-2"));

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).text().endsWith("\n\n"));
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
