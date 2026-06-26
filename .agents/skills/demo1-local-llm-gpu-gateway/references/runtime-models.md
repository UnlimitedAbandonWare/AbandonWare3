# Local LLM And Embedding Runtime Map

## Memory-Backed Baseline To Revalidate

- Main/default chat model has been stabilized around `gemma4:26b`.
- Fast/MoE/helper model has been stabilized around `qwen3:8b`.
- Embedding model has been stabilized around `qwen3-embedding:latest`.
- Prior evidence preferred port `11434`; do not assume `11435` without a fresh live check.

These are memory-derived baselines. Verify live state before editing config.

## Key Files

- OpenAI-compatible endpoint handling:
  - `main/java/com/example/lms/llm/OpenAiChatModel.java`
  - `main/java/com/example/lms/llm/OpenAiCompatBaseUrl.java`
  - `main/java/com/example/lms/llm/OpenAiEndpointCompatibility.java`
- Router and local clients:
  - `main/java/com/example/lms/service/llm/LlmRouterService.java`
  - `main/java/com/example/lms/service/llm/LocalOpenAiClient.java`
  - `main/java/com/example/lms/service/llm/LocalVllmClient.java`
  - `main/java/com/example/lms/service/llm/DualGpuScheduler.java`
- Provider/key guard:
  - search for `ProviderGuard`, `KeyResolver`, `ModelGuard`, `LLMProperties`, and `AppSecretsProperties`
- Matryoshka embedding:
  - `main/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingNormalizer.java`
  - `main/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingModelPostProcessor.java`
  - search for `OllamaEmbeddingModel`
- Local model config:
  - `main/resources/application-llm.yaml`
  - `main/resources/application-local-llm.yml`
  - `main/resources/application*.yml`
  - `app/src/main/resources/configs/models.manifest.yaml`

## Safe Probes

```powershell
ollama list
Invoke-RestMethod -Method Get http://127.0.0.1:11434/api/tags
Invoke-RestMethod -Method Post http://127.0.0.1:11434/api/chat -ContentType 'application/json' -Body '{"model":"qwen3:8b","messages":[{"role":"user","content":"ping"}],"stream":false}'
```

Do not include external API keys in probes. If a local OpenAI-compatible endpoint is used, keep it loopback-only unless the user explicitly provides an internal server address.

