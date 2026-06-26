package ai.abandonware.nova.orch.llm;

import java.util.List;
import java.util.Set;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * A ChatModel that always returns a precomputed assistant message.
 *
 * <p>Used to keep UX non-blank in "expected failure" scenarios.</p>
 */
public final class ExpectedFailureChatModel implements ChatModel, StreamingChatModel {

    private final String message;
    private final String nameForDebug;

    public ExpectedFailureChatModel(String message, String nameForDebug) {
        this.message = (message == null) ? "" : message;
        this.nameForDebug = (nameForDebug == null) ? "" : nameForDebug;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(message))
                .build();
    }

    @Override
    public void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
        completeStreaming(handler);
    }

    @Override
    public void chat(String userMessage, StreamingChatResponseHandler handler) {
        completeStreaming(handler);
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        completeStreaming(handler);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return ChatRequestParameters.builder().build();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return List.of();
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.OTHER;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return Set.of();
    }

    @Override
    public String toString() {
        return "ExpectedFailureChatModel(" + nameForDebug + ")";
    }

    private void completeStreaming(StreamingChatResponseHandler handler) {
        if (handler == null) {
            return;
        }
        ChatResponse response = chat(List.of());
        handler.onPartialResponse(message);
        handler.onCompleteResponse(response);
    }
}
