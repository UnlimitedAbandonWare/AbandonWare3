// src/main/java/com/example/lms/service/ModelSyncService.java
package com.example.lms.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import com.example.lms.entity.ModelEntity;
import com.example.lms.repository.ModelEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Component
@RequiredArgsConstructor
public class ModelSyncService {
    private static final Logger log = LoggerFactory.getLogger(ModelSyncService.class);

    private final ModelEntityRepository modelRepo;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openai.api.url:https://api.openai.com/v1}")
    private String apiUrl;

    // Resolve the API key from configuration or environment.  Prefer
    // `openai.api.key` and fall back to OPENAI_API_KEY only. Do not fall
    // back to other vendor keys (e.g. GROQ_API_KEY) to prevent mismatched
    // credentials.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String apiKey;

    // ※ 중복 호출이 싫으면 이 @PostConstruct는 지우고 스케줄러만 두세요.
    @PostConstruct
    public void init() {
        fetchAndStoreModels();
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void fetchAndStoreModels() {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("🔑 OpenAI API Key 미설정 - application.yml 의 openai.api.key 확인!");
            return;
        }

        String url = apiUrl + "/models";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> req = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
                log.warn("Failed to fetch models: HTTP {}", resp.getStatusCode());
                return;
            }

            JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
            if (!data.isArray()) {
                log.warn("Unexpected response format: {}", data);
                return;
            }

            // 1) 기존 전체 조회 → Map
            List<ModelEntity> existing = modelRepo.findAll();
            Map<String, ModelEntity> existingMap = existing.stream()
                    .collect(Collectors.toMap(ModelEntity::getModelId, e -> e));

            // 2) 업서트 대상 및 신규 ID 수집
            List<ModelEntity> toSave = new ArrayList<>();
            Set<String> fetchedIds = new HashSet<>();

            for (JsonNode node : data) {
                String modelId = node.path("id").asText(null);
                if (modelId == null || modelId.isBlank()) {
                    continue;
                }
                ModelEntity e = existingMap.getOrDefault(modelId, new ModelEntity());
                e.setModelId(modelId);

                long createdTs = node.path("created").asLong(0);
                if (createdTs > 0) {
                    e.setReleaseDate(
                            Instant.ofEpochSecond(createdTs)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                    );
                }

                // Map the model owner.  Use the 'owned_by' field from the API when available.
                try {
                    String owner = node.path("owned_by").asText(null);
                    if (owner != null && !owner.isBlank()) {
                        e.setOwner(owner);
                    } else {
                        // ensure the owner is never null; default to 'openai' when unset
                        if (e.getOwner() == null || e.getOwner().isBlank()) {
                            e.setOwner("openai");
                        }
                    }
                } catch (Exception ignore) {
                    // ignore owner mapping errors but ensure not-null
                    if (e.getOwner() == null || e.getOwner().isBlank()) {
                        e.setOwner("openai");
                    }
                }

                fetchedIds.add(modelId);
                toSave.add(e);
            }

            // 3) 삭제 대상(ID 차집합) 계산 → 일괄 삭제
            List<String> idsToDelete = existingMap.keySet().stream()
                    .filter(id -> !fetchedIds.contains(id))
                    .toList();
            if (!idsToDelete.isEmpty()) {
                modelRepo.deleteAllById(idsToDelete);
            }

            // 4) 일괄 업서트
            if (!toSave.isEmpty()) {
                modelRepo.saveAll(toSave);
            }

            log.info("📦 Fetched={}, Upserted={}, Deleted={}", fetchedIds.size(), toSave.size(), idsToDelete.size());

        } catch (Exception e) {
            log.error("Model sync 실패", e);
        }
    }
}