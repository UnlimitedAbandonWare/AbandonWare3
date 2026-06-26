package com.example.lms.plugin.image.jobs;

import com.example.lms.image.ImageMetaHolder;
import com.example.lms.plugin.image.OpenAiImageService;
import com.example.lms.plugin.image.debug.ImageJobDebugAgent;
import com.example.lms.plugin.image.debug.ImageJobDebugLedger;
import com.example.lms.plugin.image.storage.FileSystemImageStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageJobServiceDebugDiagnosticsTest {

    @AfterEach
    void clearMeta() {
        ImageMetaHolder.clear();
    }

    @Test
    void processNextPreservesProviderReasonAndRecordsVerdictSignal() {
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        FileSystemImageStorage storage = mock(FileSystemImageStorage.class);
        ImageJobProperties props = new ImageJobProperties();
        props.setRelayDelayMs(300_000L);
        props.setEtaSamples(10);
        ImageManifestWriter manifestWriter = mock(ImageManifestWriter.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        ImageJobService service = new ImageJobService(jobRepo, imageService, storage, props, manifestWriter);
        ReflectionTestUtils.setField(service, "debugLedger", ledger);

        ImageJob job = new ImageJob();
        job.setId("job-1");
        job.setPrompt("private prompt should stay out of debug output");
        job.setSize("1024x1024");
        job.setStatus(ImageJob.Status.PENDING);
        job.setCreatedAt(Instant.now().minusSeconds(400));

        when(jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING)).thenReturn(job);
        when(imageService.generateImages(anyString(), anyInt(), any())).thenAnswer(invocation -> {
            ImageMetaHolder.put("image.error", "OPENAI_429");
            return List.of();
        });

        service.processNext();

        assertEquals(ImageJob.Status.FAILED, job.getStatus());
        assertEquals("OPENAI_429", job.getReason());
        verify(ledger, atLeastOnce()).record(anyString(), any(ImageJobDebugAgent.class), anyString(),
                anyDouble(), anyDouble(), anyDouble(), anyString(), any(Map.class));
        String dump = job + "";
        assertFalse(dump.contains("OPENAI_API_KEY"));
    }

    @Test
    void processNextRecordsStorageAndManifestSignalsWithoutRawPath() throws Exception {
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        FileSystemImageStorage storage = mock(FileSystemImageStorage.class);
        ImageJobProperties props = new ImageJobProperties();
        props.setEtaSamples(10);
        ImageManifestWriter manifestWriter = mock(ImageManifestWriter.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        ImageJobService service = new ImageJobService(jobRepo, imageService, storage, props, manifestWriter);
        ReflectionTestUtils.setField(service, "debugLedger", ledger);

        ImageJob job = new ImageJob();
        job.setId("job-2");
        job.setPrompt("private prompt");
        job.setSize("1024x1024");
        job.setStatus(ImageJob.Status.PENDING);
        job.setCreatedAt(Instant.now());
        String absolutePath = "C:\\Users\\nninn\\Pictures\\private-output.png";

        when(jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING)).thenReturn(job);
        when(imageService.generateImages(anyString(), anyInt(), any())).thenReturn(List.of("data:image/png;base64,aW1n"));
        when(storage.saveBase64Png(anyString(), anyString()))
                .thenReturn(new FileSystemImageStorage.Stored(absolutePath, "/generated-images/private-output.png"));

        service.processNext();

        assertEquals(ImageJob.Status.SUCCEEDED, job.getStatus());
        verify(ledger, atLeastOnce()).record(anyString(), any(ImageJobDebugAgent.class), anyString(),
                anyDouble(), anyDouble(), anyDouble(), anyString(), any(Map.class));
        assertFalse(String.valueOf(job.getFilePath()).contains(absolutePath));
        assertTrue(String.valueOf(job.getFilePath()).contains("pathHash="));
    }

    @Test
    void processNextFailsUnstoredProviderCandidateWithoutProviderOkVerdict() throws Exception {
        ImageJobRepository jobRepo = mock(ImageJobRepository.class);
        OpenAiImageService imageService = mock(OpenAiImageService.class);
        FileSystemImageStorage storage = mock(FileSystemImageStorage.class);
        ImageJobProperties props = new ImageJobProperties();
        props.setEtaSamples(10);
        ImageManifestWriter manifestWriter = mock(ImageManifestWriter.class);
        ImageJobDebugLedger ledger = mock(ImageJobDebugLedger.class);
        ImageJobService service = new ImageJobService(jobRepo, imageService, storage, props, manifestWriter);
        ReflectionTestUtils.setField(service, "debugLedger", ledger);

        ImageJob job = new ImageJob();
        job.setId("job-unstored-candidate");
        job.setPrompt("private prompt");
        job.setSize("1024x1024");
        job.setStatus(ImageJob.Status.PENDING);
        job.setCreatedAt(Instant.now());

        when(jobRepo.findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status.PENDING)).thenReturn(job);
        when(imageService.generateImages(anyString(), anyInt(), any()))
                .thenReturn(List.of("https://cdn.example.invalid/generated.png"));
        when(storage.downloadToStorage(anyString(), anyString())).thenReturn(null);

        service.processNext();

        assertEquals(ImageJob.Status.FAILED, job.getStatus());
        assertEquals("STORAGE_EMPTY", job.getReason());

        ArgumentCaptor<ImageJobDebugAgent> agentCaptor = ArgumentCaptor.forClass(ImageJobDebugAgent.class);
        ArgumentCaptor<String> stageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(ledger, atLeastOnce()).record(eq("job-unstored-candidate"), agentCaptor.capture(), stageCaptor.capture(),
                anyDouble(), anyDouble(), anyDouble(), reasonCaptor.capture(), any(Map.class));

        boolean providerOk = false;
        boolean failedVerdict = false;
        List<ImageJobDebugAgent> agents = agentCaptor.getAllValues();
        List<String> stages = stageCaptor.getAllValues();
        List<String> reasons = reasonCaptor.getAllValues();
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i) == ImageJobDebugAgent.PROVIDER
                    && "provider.call.end".equals(stages.get(i))
                    && "OK".equals(reasons.get(i))) {
                providerOk = true;
            }
            if (agents.get(i) == ImageJobDebugAgent.VERDICT
                    && "job.failed.unusable_artifact".equals(stages.get(i))
                    && "STORAGE_EMPTY".equals(reasons.get(i))) {
                failedVerdict = true;
            }
        }
        assertFalse(providerOk);
        assertTrue(failedVerdict);
    }
}
