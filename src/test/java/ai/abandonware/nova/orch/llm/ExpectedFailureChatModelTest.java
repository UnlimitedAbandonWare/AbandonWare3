package ai.abandonware.nova.orch.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExpectedFailureChatModelTest {

    @Test
    void expectedFailureCanBeConsumedThroughStreamingChatModel() {
        ExpectedFailureChatModel model = new ExpectedFailureChatModel("expected failure", "hash:abc");
        StreamingChatModel streaming = assertInstanceOf(StreamingChatModel.class, model);
        AtomicReference<String> partial = new AtomicReference<>();
        AtomicReference<ChatResponse> complete = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        streaming.chat(List.<ChatMessage>of(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                partial.set(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                complete.set(completeResponse);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
            }
        });

        assertEquals("expected failure", partial.get());
        assertEquals("expected failure", complete.get().aiMessage().text());
        assertNull(error.get());
    }
}
