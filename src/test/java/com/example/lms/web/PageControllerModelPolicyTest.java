package com.example.lms.web;

import com.example.lms.entity.CurrentModel;
import com.example.lms.entity.ModelEntity;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.service.ModelSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PageControllerModelPolicyTest {

    @Test
    @SuppressWarnings("unchecked")
    void localProviderIgnoresRemoteCurrentModelAndFallsBackToLocalDefault() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService modelSettingsService = mock(ModelSettingsService.class);
        PageController controller = new PageController(modelRepo, currentRepo, modelSettingsService);
        ReflectionTestUtils.setField(controller, "uiDefaultModel", "gpt-5.5");
        ReflectionTestUtils.setField(controller, "backendDefaultModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "localChatModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "llmProvider", "local");
        ReflectionTestUtils.setField(controller, "allowRemoteModelSelection", false);

        when(modelRepo.findAll()).thenReturn(new ArrayList<>(List.of(
                model("gemma4:26b"),
                model("gpt-5.5-pro"))));
        when(currentRepo.findById(1L)).thenReturn(Optional.of(current("gpt-5.5-pro")));

        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("chat-ui", controller.chatUi(model, null));
        assertEquals("gemma4:26b", model.getAttribute("currentModel"));
        assertEquals("gemma4:26b", model.getAttribute("defaultModel"));
        assertEquals("local", model.getAttribute("llmProvider"));

        List<ModelEntity> models = (List<ModelEntity>) model.getAttribute("models");
        assertFalse(models.stream().anyMatch(m -> "gpt-5.5-pro".equals(m.getModelId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void localProviderAllowsRemoteCurrentModelWhenOpenAiKeyConfigured() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService modelSettingsService = mock(ModelSettingsService.class);
        PageController controller = new PageController(modelRepo, currentRepo, modelSettingsService);
        ReflectionTestUtils.setField(controller, "uiDefaultModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "backendDefaultModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "localChatModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "llmProvider", "local");
        ReflectionTestUtils.setField(controller, "allowRemoteModelSelection", false);
        ReflectionTestUtils.setField(controller, "openAiApiKey", com.example.lms.test.SecretFixtures.openAiKey());

        when(modelRepo.findAll()).thenReturn(new ArrayList<>(List.of(
                model("gemma4:26b"),
                model("gpt-5.5"))));
        when(currentRepo.findById(1L)).thenReturn(Optional.of(current("gpt-5.5")));

        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("chat-ui", controller.chatUi(model, null));
        assertEquals("gpt-5.5", model.getAttribute("currentModel"));
        assertEquals(true, model.getAttribute("allowRemoteModelSelection"));

        List<ModelEntity> models = (List<ModelEntity>) model.getAttribute("models");
        assertTrue(models.stream().anyMatch(m -> "gpt-5.5".equals(m.getModelId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void localProviderAddsCuratedOpenAiChatModelsWhenOpenAiKeyConfigured() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService modelSettingsService = mock(ModelSettingsService.class);
        PageController controller = new PageController(modelRepo, currentRepo, modelSettingsService);
        ReflectionTestUtils.setField(controller, "uiDefaultModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "backendDefaultModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "localChatModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "llmProvider", "local");
        ReflectionTestUtils.setField(controller, "allowRemoteModelSelection", false);
        ReflectionTestUtils.setField(controller, "openAiApiKey", com.example.lms.test.SecretFixtures.openAiKey());

        when(modelRepo.findAll()).thenReturn(new ArrayList<>(List.of(model("gemma4:26b"))));
        when(currentRepo.findById(1L)).thenReturn(Optional.of(current("gemma4:26b")));

        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("chat-ui", controller.chatUi(model, null));

        List<ModelEntity> models = (List<ModelEntity>) model.getAttribute("models");
        assertTrue(models.stream().anyMatch(m -> "gpt-5.5".equals(m.getModelId())));
        assertTrue(models.stream().anyMatch(m -> "gpt-5.4-mini".equals(m.getModelId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void localProviderAddsConfiguredStrongLocalChatModelsEvenWhenDbIsSparse() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService modelSettingsService = mock(ModelSettingsService.class);
        PageController controller = new PageController(modelRepo, currentRepo, modelSettingsService);
        ReflectionTestUtils.setField(controller, "uiDefaultModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "backendDefaultModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "localChatModel", "gemma4:26b");
        ReflectionTestUtils.setField(controller, "localChatModels",
                "qwen3:30b,qwen3-coder:30b,gemma3:27b,qwen3-embedding:4b");
        ReflectionTestUtils.setField(controller, "llmProvider", "local");
        ReflectionTestUtils.setField(controller, "allowRemoteModelSelection", false);

        when(modelRepo.findAll()).thenReturn(new ArrayList<>(List.of(model("gemma4:26b"))));
        when(currentRepo.findById(1L)).thenReturn(Optional.of(current("gemma4:26b")));

        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("chat-ui", controller.chatUi(model, null));

        List<ModelEntity> models = (List<ModelEntity>) model.getAttribute("models");
        assertTrue(models.stream().anyMatch(m -> "qwen3:30b".equals(m.getModelId())));
        assertTrue(models.stream().anyMatch(m -> "qwen3-coder:30b".equals(m.getModelId())));
        assertTrue(models.stream().anyMatch(m -> "gemma3:27b".equals(m.getModelId())));
        assertFalse(models.stream().anyMatch(m -> "qwen3-embedding:4b".equals(m.getModelId())));
    }

    @Test
    void modelSettingsRejectsRemoteModelInLocalProviderBeforeDbWrite() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService service = new ModelSettingsService(modelRepo, currentRepo);
        ReflectionTestUtils.setField(service, "llmProvider", "local");
        ReflectionTestUtils.setField(service, "allowRemoteModelSelection", false);
        ReflectionTestUtils.setField(service, "endpointCompatSaveGuardMode", "BLOCK");

        assertThrows(IllegalArgumentException.class, () -> service.changeCurrentModel("gpt-5.5-pro"));
        verifyNoInteractions(modelRepo, currentRepo);
    }

    @Test
    void modelSettingsRejectsRemoteModelInLocalProviderWhenFlagEnabledWithoutOpenAiKey() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService service = new ModelSettingsService(modelRepo, currentRepo);
        ReflectionTestUtils.setField(service, "llmProvider", "local");
        ReflectionTestUtils.setField(service, "allowRemoteModelSelection", true);
        ReflectionTestUtils.setField(service, "endpointCompatSaveGuardMode", "BLOCK");

        assertThrows(IllegalArgumentException.class, () -> service.changeCurrentModel("gpt-5.5-pro"));
        verifyNoInteractions(modelRepo, currentRepo);
    }

    @Test
    void modelSettingsAllowsCuratedOpenAiModelInLocalProviderWhenOpenAiKeyConfigured() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService service = new ModelSettingsService(modelRepo, currentRepo);
        ReflectionTestUtils.setField(service, "llmProvider", "local");
        ReflectionTestUtils.setField(service, "allowRemoteModelSelection", false);
        ReflectionTestUtils.setField(service, "endpointCompatSaveGuardMode", "BLOCK");
        ReflectionTestUtils.setField(service, "openAiApiKey", com.example.lms.test.SecretFixtures.openAiKey());

        when(modelRepo.existsById("gpt-5.5")).thenReturn(false);
        when(currentRepo.findById(1L)).thenReturn(Optional.empty());

        service.changeCurrentModel("gpt-5.5");

        verify(currentRepo).save(any(CurrentModel.class));
        verify(modelRepo, never()).save(any(ModelEntity.class));
    }

    @Test
    void modelSettingsAllowsConfiguredLocalModelBeforeDbWrite() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService service = new ModelSettingsService(modelRepo, currentRepo);
        ReflectionTestUtils.setField(service, "llmProvider", "local");
        ReflectionTestUtils.setField(service, "allowRemoteModelSelection", false);
        ReflectionTestUtils.setField(service, "endpointCompatSaveGuardMode", "BLOCK");
        ReflectionTestUtils.setField(service, "localChatModels", "qwen3:30b,qwen3-coder:30b");

        when(modelRepo.existsById("qwen3:30b")).thenReturn(false);
        when(currentRepo.findById(1L)).thenReturn(Optional.empty());

        service.changeCurrentModel("qwen3:30b");

        verify(currentRepo).save(any(CurrentModel.class));
        verify(modelRepo, never()).save(any(ModelEntity.class));
    }

    @Test
    void pageControllerModelPolicyLogsDoNotWriteRawModelIdentifiers() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/web/PageController.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("ignored current_model={}"));
        assertFalse(source.contains("current={} default={}"));
        assertTrue(source.contains("ignored currentModelHash={} currentModelLength={}"));
        assertTrue(source.contains("currentHash={} currentLength={} defaultHash={} defaultLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(currentModelId)"));
        assertTrue(source.contains("SafeRedactor.hashValue(defaultModel)"));
        assertFalse(source.contains("'\" + modelId + \"'"));
        assertFalse(source.contains("redirectAttributes.addFlashAttribute(\"errorMessage\", e.getMessage());"));
        assertFalse(source.contains("log.error(\"Failed to save model settings\", e);"));
        assertTrue(source.contains("SafeRedactor.hashValue(modelId)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("Failed to save model settings type={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e))"));
        assertTrue(source.contains("messageLength(e)"));
    }

    @Test
    void modelSettingsTemplateShowsBackendPolicyScalars() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/model-settings.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-default-model-text"));
        assertTrue(html.contains("data-llm-provider-text"));
        assertTrue(html.contains("data-remote-model-selection-text"));
        assertTrue(html.contains("th:text=\"${defaultModel} ?: 'Not set'\""));
        assertTrue(html.contains("th:text=\"${llmProvider} ?: 'Not set'\""));
        assertTrue(html.contains("th:text=\"${allowRemoteModelSelection ? 'enabled' : 'disabled'}\""));
    }

    @Test
    void modelSettingsShowsEmbeddingRuntimeDiagnosticsScalars() throws Exception {
        String html = Files.readString(Path.of("main/resources/templates/model-settings.html"), StandardCharsets.UTF_8);
        String js = Files.readString(Path.of("main/resources/static/js/model-strategy.js"), StandardCharsets.UTF_8);

        assertTrue(html.contains("data-embedding-runtime-diagnostics=\"true\""));
        assertTrue(html.contains("id=\"embeddingRuntimeStatus\""));
        assertTrue(html.contains("id=\"embeddingTargetDim\""));
        assertTrue(html.contains("id=\"embeddingRawProviderDim\""));
        assertTrue(html.contains("id=\"embeddingNormalizationMode\""));
        assertTrue(html.contains("id=\"embeddingFastFail\""));
        assertTrue(html.contains("id=\"embeddingFailurePressure\""));
        assertTrue(html.contains("id=\"embeddingHealthMode\""));
        assertTrue(html.contains("id=\"embeddingBackupAvailable\""));
        assertTrue(js.contains("function renderEmbeddingDiagnostics(snapshot)"));
        assertTrue(js.contains("fetch(\"/api/diagnostics/embedding\""));
        assertTrue(js.contains("setEmbeddingDiagnosticText(\"embeddingTargetDim\", ollama.targetDim"));
        assertTrue(js.contains("setEmbeddingDiagnosticText(\"embeddingRawProviderDim\", ollama.rawProviderDim"));
        assertTrue(js.contains("setEmbeddingDiagnosticText(\"embeddingNormalizationMode\", ollama.normalizationMode"));
        assertTrue(js.contains("setEmbeddingDiagnosticText(\"embeddingFastFail\", ollama.fastFailEnabled"));
        assertTrue(js.contains("failureStreak=${embeddingDiagnosticText(ollama.failureStreak, \"0\")}") );
        assertTrue(js.contains("tripCount=${embeddingDiagnosticText(ollama.tripCount, \"0\")}") );
        assertTrue(js.contains("skipRemainingMs=${embeddingDiagnosticText(ollama.skipRemainingMs, \"0\")}") );
        assertFalse(js.contains("endpointHost"));
        assertFalse(js.contains("healthLastError"));
        assertFalse(js.contains("lastLocalError"));
    }

    private static ModelEntity model(String id) {
        ModelEntity model = new ModelEntity();
        model.setModelId(id);
        model.setOwner("test");
        return model;
    }

    private static CurrentModel current(String id) {
        CurrentModel current = new CurrentModel();
        current.setId(1L);
        current.setModelId(id);
        return current;
    }
}
