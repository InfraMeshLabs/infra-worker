package koboolean.example.service;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.ToolChoice;
import com.inframesh.node.dto.ToolDefinition;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.enums.FinishReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerServiceTest {

    @Mock
    private ChatModel chatModel;

    private WorkerService workerService;

    private WorkerRequest requestWithTools(String sessionId) {
        JsonMapper jsonMapper = JsonMapper.shared();
        return new WorkerRequest(
                sessionId,
                List.of(ChatMessage.user("What's the weather in Seoul? Use the get_weather tool.")),
                null,
                List.of(new ToolDefinition(
                        "get_weather",
                        "Get the current weather for a given city",
                        jsonMapper.readTree("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"))),
                ToolChoice.AUTO,
                null);
    }

    private org.springframework.ai.chat.model.ChatResponse toolCallResponse() {
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"Seoul\"}");
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();

        Generation generation = new Generation(assistantMessage,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build());

        Usage usage = org.mockito.Mockito.mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(10);
        when(usage.getCompletionTokens()).thenReturn(5);
        when(usage.getTotalTokens()).thenReturn(15);

        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();

        return new org.springframework.ai.chat.model.ChatResponse(List.of(generation), metadata);
    }

    @Test
    void invoke_withSessionIdAndTools_toolCallingUnaffected() {
        org.springframework.ai.chat.model.ChatResponse aiResponse = toolCallResponse();
        when(chatModel.getOptions()).thenReturn(OllamaChatOptions.builder().model("test-model").build());
        when(chatModel.call(any(Prompt.class))).thenReturn(aiResponse);

        workerService = new WorkerService(chatModel);

        ChatResponse response = workerService.invoke(requestWithTools("session-123")).block();

        assertThat(response).isNotNull();
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("get_weather");
    }

    @Test
    void invoke_withoutSessionIdAndTools_toolCallingUnaffected() {
        org.springframework.ai.chat.model.ChatResponse aiResponse = toolCallResponse();
        when(chatModel.getOptions()).thenReturn(OllamaChatOptions.builder().model("test-model").build());
        when(chatModel.call(any(Prompt.class))).thenReturn(aiResponse);

        workerService = new WorkerService(chatModel);

        ChatResponse response = workerService.invoke(requestWithTools(null)).block();

        assertThat(response).isNotNull();
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.toolCalls()).hasSize(1);
    }
}
