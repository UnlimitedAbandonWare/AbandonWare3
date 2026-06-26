package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestSettingsMergerTest {

    @Test
    void nullableUiSamplingAndRetrievalFlagsFallBackToSettings() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .sessionId(7L)
                .message("hello")
                .temperature(null)
                .topP(null)
                .frequencyPenalty(null)
                .presencePenalty(null)
                .model(null)
                .useRag(null)
                .useWebSearch(null)
                .searchMode(SearchMode.FORCE_LIGHT)
                .webProviders(List.of("NAVER"))
                .officialSourcesOnly(true)
                .webTopK(4)
                .precisionSearch(true)
                .precisionTopK(3)
                .accumulation(true)
                .roleScope(List.of("OFFICIAL"))
                .domainProfile("official")
                .attachmentIds(List.of("att-1"))
                .polish(true)
                .build();
        ui.setUseWebSearch(null);
        Map<String, String> settings = new HashMap<>();
        settings.put(SettingsService.KEY_OPENAI_MODEL, "settings-model");
        settings.put(SettingsService.KEY_TEMPERATURE, "0.4");
        settings.put(SettingsService.KEY_TOP_P, "0.8");
        settings.put(SettingsService.KEY_FREQUENCY_PENALTY, "0.1");
        settings.put(SettingsService.KEY_PRESENCE_PENALTY, "0.2");
        settings.put("chat.defaults.useWebSearch", "true");

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                settings,
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertEquals(7L, merged.getSessionId());
        assertEquals("settings-model", merged.getModel());
        assertEquals(0.4, merged.getTemperature());
        assertEquals(0.8, merged.getTopP());
        assertEquals(0.1, merged.getFrequencyPenalty());
        assertEquals(0.2, merged.getPresencePenalty());
        assertTrue(merged.getUseRag());
        assertTrue(merged.getUseWebSearch());
        assertEquals(SearchMode.FORCE_LIGHT, merged.getSearchMode());
        assertEquals(List.of("NAVER"), merged.getWebProviders());
        assertEquals(List.of("att-1"), merged.getAttachmentIds());
    }

    @Test
    void explicitUiValuesOverrideSettingsAndWebSearchExplicitForcesEnabled() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .message("hello")
                .model("ui-model")
                .temperature(0.3)
                .topP(0.7)
                .frequencyPenalty(0.0)
                .presencePenalty(0.0)
                .useRag(false)
                .useWebSearch(true)
                .build();
        Map<String, String> settings = new HashMap<>();
        settings.put(SettingsService.KEY_OPENAI_MODEL, "settings-model");
        settings.put("chat.defaults.useWebSearch", "false");

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                settings,
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertEquals("ui-model", merged.getModel());
        assertEquals(0.3, merged.getTemperature());
        assertFalse(merged.getUseRag());
        assertTrue(merged.getUseWebSearch());
    }

    @Test
    void explicitFalseWebSearchSurvivesSettingsDefaultAndExplicitFlag() {
        ChatRequestDto ui = ChatRequestDto.builder()
                .message("hello")
                .useRag(true)
                .useWebSearch(null)
                .build();
        ui.setUseWebSearch(false);
        Map<String, String> settings = new HashMap<>();
        settings.put("chat.defaults.useWebSearch", "true");

        ChatRequestDto merged = ChatRequestSettingsMerger.merge(
                ui,
                settings,
                true,
                LoggerFactory.getLogger(ChatRequestSettingsMergerTest.class));

        assertTrue(ui.isWebSearchExplicit());
        assertFalse(merged.getUseWebSearch());
    }
}
