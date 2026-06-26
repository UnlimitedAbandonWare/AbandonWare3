package com.example.lms.service;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.file.FileIngestionService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentServiceSessionOwnershipTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionBoundAttachmentCannotBeLoadedOrReattachedByAnotherSession() throws Exception {
        AttachmentService service = new AttachmentService(null, new FileIngestionService());
        Path file = Files.createTempFile("attachment-session-", ".txt");
        Files.writeString(file, "owned attachment text");
        try {
            Map<String, AttachmentDto> repo = (Map<String, AttachmentDto>) ReflectionTestUtils.getField(service, "repo");
            repo.put("att-owned", new AttachmentDto(
                    "att-owned",
                    "owned.txt",
                    Files.size(file),
                    "text/plain",
                    file.toString()));

            service.attachToSession("session-a", List.of("att-owned"));
            service.attachToSession("session-b", List.of("att-owned"));

            assertEquals(1, service.findBySession("session-a").size());
            assertTrue(service.findBySession("session-b").isEmpty());
            assertEquals(1, service.asDocumentsForSession(List.of("att-owned"), "session-a").size());
            assertTrue(service.asDocumentsForSession(List.of("att-owned"), "session-b").isEmpty());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionBoundAttachmentCannotBeDeletedByAnotherSession() throws Exception {
        AttachmentService service = new AttachmentService(null, new FileIngestionService());
        Path file = Files.createTempFile("attachment-delete-session-", ".txt");
        Files.writeString(file, "owned attachment text");
        try {
            Map<String, AttachmentDto> repo = (Map<String, AttachmentDto>) ReflectionTestUtils.getField(service, "repo");
            repo.put("att-delete-owned", new AttachmentDto(
                    "att-delete-owned",
                    "owned.txt",
                    Files.size(file),
                    "text/plain",
                    file.toString()));

            service.attachToSession("session-owner", List.of("att-delete-owned"));

            assertTrue(service.find("att-delete-owned").isPresent());
            assertTrue(service.findBySession("session-owner").stream()
                    .anyMatch(dto -> "att-delete-owned".equals(dto.id())));

            assertEquals(false, service.deleteForSession("att-delete-owned", "session-other"));
            assertTrue(service.find("att-delete-owned").isPresent());

            assertEquals(true, service.deleteForSession("att-delete-owned", "session-owner"));
            assertTrue(service.find("att-delete-owned").isEmpty());
            assertTrue(service.findBySession("session-owner").isEmpty());
            Map<String, List<String>> sessionIndex =
                    (Map<String, List<String>>) ReflectionTestUtils.getField(service, "sessionIndex");
            assertTrue(sessionIndex == null || !sessionIndex.containsKey("session-owner"));
            assertEquals(Boolean.TRUE, TraceStore.get("attachment.delete.ok"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
