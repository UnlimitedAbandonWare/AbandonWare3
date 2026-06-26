package com.example.lms.service.answer;

import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.service.verbosity.VerbosityProfile;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AnswerExpanderServiceTest {

    @Test
    void evidenceSnippetsAreRedactedBeforePromptBuilderBoundary() {
        String rawKey = "sk-" + "abcdefghijklmnopqrstuvwxyz" + "123456";
        RecordingPromptBuilder promptBuilder = new RecordingPromptBuilder();
        AnswerExpanderService service = new AnswerExpanderService(promptBuilder);
        VerbosityProfile profile = new VerbosityProfile(
                "standard", 5, 200, "dev", "inline", List.of("요약"));

        String expanded = service.expandWithLc(
                "기존 초안입니다.",
                profile,
                new StaticModel("확장된 답변입니다. 충분한 길이입니다."),
                List.of("provider evidence " + rawKey));

        assertNotNull(expanded);
        assertFalse(promptBuilder.lastUserQuery.contains(rawKey));
    }

    private static final class RecordingPromptBuilder implements PromptBuilder {
        private String lastUserQuery = "";

        @Override
        public String build(List<PromptContext> contexts, String question) {
            PromptContext ctx = contexts.get(0);
            lastUserQuery = ctx.userQuery();
            return lastUserQuery;
        }
    }

    private record StaticModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }
}
