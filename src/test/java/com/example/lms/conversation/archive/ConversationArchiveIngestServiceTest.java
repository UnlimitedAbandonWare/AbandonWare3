package com.example.lms.conversation.archive;

import ai.abandonware.nova.orch.trace.OrchTrace;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ConversationArchiveIngestServiceTest {

    private VectorStoreService vectorStoreService;
    private ConversationArchiveIngestService service;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        vectorStoreService = mock(VectorStoreService.class);
        service = new ConversationArchiveIngestService(vectorStoreService, provider(null));
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void rejectsNonZipUpload() {
        MockMultipartFile txt = new MockMultipartFile(
                "files",
                "ConversationExport.txt",
                "text/plain",
                "Alice : hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.ingest(List.of(txt), "sid-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unsupported_archive_type");
        verifyNoInteractions(vectorStoreService);
    }

    @Test
    void zipWithConversationTxtReturnsTreeAndSummaryOnly() throws Exception {
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_room.txt", """
                2026. 5. 27. AM 9:10, Alice : Roadmap user@example.com 010-1234-5678
                2026. 5. 27. AM 9:11, Bot : Summary: project archive
                2026. 5. 27. AM 9:12, Bob : Link https://github.com/example/repo
                2026. 5. 27. AM 9:13, Noise : <html><body><script>alert('body')</script></body></html>
                """);

        ConversationArchiveIngestReport report = service.ingest(List.of(zip), "session-raw");

        assertThat(report.ok()).isTrue();
        assertThat(report.tree()).containsExactly("ConversationExport_room.txt");
        assertThat(report.counts())
                .containsEntry("human_message", 1)
                .containsEntry("bot_summary", 1)
                .containsEntry("link_artifact", 1)
                .containsEntry("quarantined", 1);
        assertThat(report.ingestedCount()).isGreaterThanOrEqualTo(2);
        assertThat(report.toString())
                .doesNotContain("Roadmap", "user@example.com", "010-1234-5678", "alert('body')");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptedChunksCallVectorEnqueueWithSafeMetadata() throws Exception {
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_room.txt", """
                2026. 5. 27. AM 9:10, Alice : Roadmap user@example.com 010-1234-5678
                2026. 5. 27. AM 9:11, Bob : Link https://github.com/example/repo
                """);

        service.ingest(List.of(zip), "sid-1");

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService, atLeastOnce()).enqueue(anyString(), anyString(), textCaptor.capture(), metaCaptor.capture());

        assertThat(textCaptor.getAllValues().toString())
                .contains("***@***", "********")
                .doesNotContain("user@example.com", "010-1234-5678");
        assertThat(metaCaptor.getAllValues())
                .anySatisfy(meta -> {
                    assertThat(meta).containsEntry(VectorMetaKeys.META_SOURCE_TAG, "CONVERSATION_ARCHIVE");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_DOC_TYPE, "MEMORY");
                })
                .anySatisfy(meta -> {
                    assertThat(meta).containsEntry(VectorMetaKeys.META_SOURCE_TAG, "CONVERSATION_ARCHIVE");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_DOC_TYPE, "KB");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_VERIFIED, "true");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_KB_DOMAIN, "conversation_archive");
                    assertThat(meta).containsKey(VectorMetaKeys.META_SCOPE_ANCHOR_KEY);
                });
    }

    @Test
    void quarantinedChunksAreSkipped() throws Exception {
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_noise.txt", """
                2026. 5. 27. AM 9:10, Noise : <html><body><script>alert('body')</script></body></html>
                2026. 5. 27. AM 9:11, Noise : aaa aaa aaa aaa aaa
                """);

        ConversationArchiveIngestReport report = service.ingest(List.of(zip), "sid-1");

        assertThat(report.ingestedCount()).isZero();
        assertThat(report.counts()).containsEntry("quarantined", 2);
        verify(vectorStoreService, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void traceAndOrchEventsAreSanitized() throws Exception {
        TraceStore.put("traceId", "trace-raw");
        TraceStore.put("requestId", "request-raw");
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_room.txt", """
                2026. 5. 27. AM 9:10, Alice : Secret roadmap user@example.com
                """);

        service.ingest(List.of(zip), "session-raw");

        assertThat(TraceStore.get("conversation.archive.humanMessageCount")).isEqualTo(1);
        Object events = TraceStore.get(OrchTrace.TRACE_KEY_EVENTS_V1);
        assertThat(String.valueOf(events))
                .contains("rag.ingest", "sessionIdHash")
                .doesNotContain("Secret roadmap", "user@example.com", "session-raw", "trace-raw", "request-raw");
    }

    private static MockMultipartFile zipFile(String fileName, String entryName, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(body.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MockMultipartFile("files", fileName, "application/zip", out.toByteArray());
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
