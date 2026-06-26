package com.example.lms.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class ChatResponseDto {

    private final String content;
    private final Long sessionId;
    private final String modelUsed;
    private final boolean ragUsed;
    private final String answerMode;
    private final Long traceTurnId;
    private final LearningContextMetadata learningContext;
    private final List<RagEvidenceMetadata> evidence;
    private final ChatStreamEvent.PipelineSnapshot pipelineSnapshot;

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed) {
        this(content, sessionId, modelUsed, ragUsed, null);
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, LearningContextMetadata.empty());
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode,
                           LearningContextMetadata learningContext) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, learningContext, List.of());
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode,
                           LearningContextMetadata learningContext,
                           List<RagEvidenceMetadata> evidence) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, null, learningContext, evidence);
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode,
                           Long traceTurnId,
                           LearningContextMetadata learningContext,
                           List<RagEvidenceMetadata> evidence) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, traceTurnId, learningContext, evidence, null);
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode,
                           Long traceTurnId,
                           LearningContextMetadata learningContext,
                           List<RagEvidenceMetadata> evidence,
                           ChatStreamEvent.PipelineSnapshot pipelineSnapshot) {
        this.content = content;
        this.sessionId = sessionId;
        this.modelUsed = modelUsed;
        this.ragUsed = ragUsed;
        this.answerMode = answerMode;
        this.traceTurnId = traceTurnId == null ? null : Math.max(0L, traceTurnId);
        this.learningContext = learningContext == null ? LearningContextMetadata.empty() : learningContext;
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        this.pipelineSnapshot = pipelineSnapshot;
    }
}
