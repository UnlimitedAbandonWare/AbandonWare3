package com.example.lms.service.answer;

import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.service.verbosity.VerbosityProfile;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;



@Service
public class AnswerExpanderService {
    private static final Logger log = LoggerFactory.getLogger(AnswerExpanderService.class);

    private final PromptBuilder promptBuilder;

    public AnswerExpanderService(PromptBuilder promptBuilder) {
        this.promptBuilder = java.util.Objects.requireNonNull(promptBuilder, "promptBuilder");
    }

    /**
     * Backward-compatible overload used by legacy call sites that do not provide
     * explicit evidence snippets. Delegates to the full variant with an empty list.
     */
    public String expandWithLc(String draft, VerbosityProfile vp, ChatModel model) {
        return expandWithLc(draft, vp, model, java.util.Collections.emptyList());
    }



    private static String buildExpandPrompt(String draft, VerbosityProfile vp, List<String> evidenceSnippets) {
    String sections = (vp.sections() == null || vp.sections().isEmpty())
            ? ""
            : "- Use these section headers (Korean): " + String.join(", ", vp.sections()) + "\n";

    String evidenceBlock = "";
    List<String> safeEvidenceSnippets = safeEvidenceSnippets(evidenceSnippets);
    if (!safeEvidenceSnippets.isEmpty()) {
        evidenceBlock = "\n[검색 결과 기반 정보]\n"
                + String.join("\n", safeEvidenceSnippets)
                + "\n\n위 정보만을 신뢰 가능한 외부 근거로 사용해.\n"
                + "검색 결과에 없는 사실을 '추측해서' 만들지 마.\n\n";
    }

    return """
           You are a Korean technical editor that ONLY restructures existing content.

           HARD RULES:
           - DO NOT add any new facts, entities, character names, places, dates, or numbers not present in the DRAFT or EVIDENCE.
           - DO NOT guess or invent background information to reach the target length.
           - If the DRAFT lacks sufficient detail AND no EVIDENCE is provided, reply EXACTLY: [NO_EVIDENCE]

           ALLOWED:
           - Reorder sentences for better flow
           - Add transition phrases using only info already in DRAFT or EVIDENCE
           - Split into paragraphs or sections
           %s

           Target minimum length: %d Korean words (but quality over quantity - never fabricate).

           ## DRAFT
           %s
           """.formatted(evidenceBlock, Math.max(1, vp.minWordCount()), draft);
}

    private static List<String> safeEvidenceSnippets(List<String> evidenceSnippets) {
        if (evidenceSnippets == null || evidenceSnippets.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.ArrayList<String> safe = new java.util.ArrayList<>(evidenceSnippets.size());
        for (String snippet : evidenceSnippets) {
            String redacted = SafeRedactor.safeMessage(snippet, 1_200);
            if (redacted != null && !redacted.isBlank()) {
                safe.add(redacted);
            }
        }
        return safe;
    }

    public String expandWithLc(String draft, VerbosityProfile vp, ChatModel model, List<String> evidenceSnippets) {
        try {
            PromptContext ctx = PromptContext.builder()
                    .systemInstruction("You are a cautious Korean editor. Restructure only; never invent.")
                    .userQuery(buildExpandPrompt(draft, vp, evidenceSnippets))
                    .build();
            List<ChatMessage> msgs = java.util.List.of(UserMessage.from(promptBuilder.build(ctx)));
            String result = model.chat(msgs).aiMessage().text();

            // [NO_EVIDENCE] 프로토콜 처리
            if (result != null && result.trim().equals("[NO_EVIDENCE]")) {
                return null; // 확장하지 않고 원문 그대로 사용
            }

            // 너무 짧게 요약한 경우도 원문 사용
            if (result != null && result.length() < draft.length() * 0.8) {
                return null;
            }

            return result;
        } catch (Exception e) {
            log.debug("[AnswerExpander] expansion failed errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            return null; // 확장 실패 시 원문 사용
        }
    }
}
