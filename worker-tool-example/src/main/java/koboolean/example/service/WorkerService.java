package koboolean.example.service;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.ChatOptions;
import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.ChatStreamResponse;
import com.inframesh.node.dto.ToolCall;
import com.inframesh.node.dto.ToolCallDelta;
import com.inframesh.node.dto.ToolChoice;
import com.inframesh.node.dto.ToolDefinition;
import com.inframesh.node.dto.Usage;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.enums.FinishReason;
import com.inframesh.node.enums.ToolChoiceMode;
import koboolean.example.tool.StaticToolCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerService {

    private static final String EMPTY_OBJECT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final ChatModel chatModel;
    private final JsonMapper jsonMapper = JsonMapper.shared();

    public Mono<ChatResponse> invoke(WorkerRequest request) {
        log.debug("Handling invoke request, sessionId={}", request.sessionId());

        return Mono.fromCallable(() -> chatModel.call(toPrompt(request)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toChatResponse);
    }

    public Flux<ChatStreamResponse> stream(WorkerRequest request) {
        log.debug("Handling stream request, sessionId={}", request.sessionId());

        return chatModel.stream(toPrompt(request))
                .mapNotNull(this::toChatStreamResponse);
    }

    // ------------------------------------------------------------------
    // Request mapping: InfraMesh WorkerRequest -> Spring AI Prompt
    // ------------------------------------------------------------------

    private Prompt toPrompt(WorkerRequest request) {
        List<Message> messages = request.messages().stream()
                .map(this::toMessage)
                .toList();

        return new Prompt(messages, toChatOptions(request));
    }

    private Message toMessage(ChatMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> toAssistantMessage(message);
            case TOOL -> toToolResponseMessage(message);
        };
    }

    private AssistantMessage toAssistantMessage(ChatMessage message) {
        List<ToolCall> toolCalls = message.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return new AssistantMessage(message.content());
        }

        List<AssistantMessage.ToolCall> springToolCalls = toolCalls.stream()
                .map(toolCall -> new AssistantMessage.ToolCall(
                        toolCall.id(), "function", toolCall.name(), writeArguments(toolCall.arguments())))
                .toList();

        return AssistantMessage.builder()
                .content(message.content() != null ? message.content() : "")
                .toolCalls(springToolCalls)
                .build();
    }

    private ToolResponseMessage toToolResponseMessage(ChatMessage message) {
        // InfraMesh's TOOL message only carries toolCallId + content (OpenAI-style), but
        // Ollama's native tool-result protocol keys results by tool *name* rather than call
        // id. The call id is reused as the name here; this is a known simplification and is
        // documented in the module README.
        ToolResponseMessage.ToolResponse response =
                new ToolResponseMessage.ToolResponse(message.toolCallId(), message.toolCallId(), message.content());

        return ToolResponseMessage.builder()
                .responses(List.of(response))
                .build();
    }

    private org.springframework.ai.chat.prompt.ChatOptions toChatOptions(WorkerRequest request) {
        List<ToolCallback> toolCallbacks = toToolCallbacks(request);
        ChatOptions options = request.options();

        if (toolCallbacks.isEmpty() && options == null) {
            // Leaving Prompt#options null lets OllamaChatModel fall back to its configured
            // default OllamaChatOptions (including the configured model id).
            return null;
        }

        // OllamaChatModel casts Prompt#options directly to its own OllamaChatOptions type
        // (it does not accept the framework-agnostic ChatOptions), so any override has to be
        // built on top of it, carrying over the configured default model id.
        OllamaChatOptions defaults = (OllamaChatOptions) chatModel.getOptions();
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                .model(defaults.getModel())
                .toolCallbacks(toolCallbacks);

        if (options != null) {
            builder.temperature(options.temperature())
                    .maxTokens(options.maxTokens())
                    .topP(options.topP());
        }

        return builder.build();
    }

    private List<ToolCallback> toToolCallbacks(WorkerRequest request) {
        List<ToolDefinition> tools = request.tools();
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        ToolChoice toolChoice = request.toolChoice();
        if (toolChoice != null && toolChoice.mode() == ToolChoiceMode.NONE) {
            // Ollama has no native "forced no-tool" switch; the only reliable way to honor
            // toolChoice=NONE is to withhold the tool definitions entirely.
            return List.of();
        }

        return tools.stream()
                .<ToolCallback>map(definition -> StaticToolCallback.of(
                        definition.name(),
                        definition.description(),
                        toInputSchema(definition.parameters())))
                .toList();
    }

    private String toInputSchema(JsonNode parameters) {
        return (parameters == null || parameters.isNull()) ? EMPTY_OBJECT_SCHEMA : parameters.toString();
    }

    private String writeArguments(JsonNode arguments) {
        return (arguments == null || arguments.isNull()) ? "{}" : arguments.toString();
    }

    // ------------------------------------------------------------------
    // Response mapping: Spring AI ChatResponse -> InfraMesh ChatResponse
    // ------------------------------------------------------------------

    private ChatResponse toChatResponse(org.springframework.ai.chat.model.ChatResponse response) {
        Generation generation = response.getResult();
        AssistantMessage output = generation != null ? generation.getOutput() : null;

        String message = output != null ? output.getText() : null;
        List<ToolCall> toolCalls = output != null ? toToolCalls(output.getToolCalls()) : List.of();

        return new ChatResponse(message, toolCalls, toFinishReason(response), toUsage(response));
    }

    private ChatStreamResponse toChatStreamResponse(org.springframework.ai.chat.model.ChatResponse chunk) {
        Generation generation = chunk.getResult();
        if (generation == null) {
            return null;
        }

        AssistantMessage output = generation.getOutput();
        List<ToolCallDelta> toolCallDeltas = toToolCallDeltas(output.getToolCalls());

        boolean done = generation.getMetadata().getFinishReason() != null || chunk.hasToolCalls();

        return new ChatStreamResponse(
                output.getText(),
                toolCallDeltas,
                done ? toFinishReason(chunk) : null,
                done ? toUsage(chunk) : null
        );
    }

    private List<ToolCall> toToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        return toolCalls.stream()
                .map(toolCall -> new ToolCall(toolCall.id(), toolCall.name(), readArguments(toolCall.arguments())))
                .toList();
    }

    private List<ToolCallDelta> toToolCallDeltas(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        // Ollama emits each tool call as one complete object rather than incremental
        // argument fragments (unlike OpenAI-style delta streaming), so each "delta" here is
        // simply the full call surfaced once, indexed by its position in the response.
        List<ToolCallDelta> deltas = new ArrayList<>(toolCalls.size());
        for (int index = 0; index < toolCalls.size(); index++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(index);
            deltas.add(new ToolCallDelta(index, toolCall.id(), toolCall.name(), toolCall.arguments()));
        }
        return deltas;
    }

    private JsonNode readArguments(String arguments) {
        return (arguments == null || arguments.isBlank()) ? jsonMapper.createObjectNode() : jsonMapper.readTree(arguments);
    }

    private FinishReason toFinishReason(org.springframework.ai.chat.model.ChatResponse response) {
        if (response.hasToolCalls()) {
            return FinishReason.TOOL_CALLS;
        }

        Generation generation = response.getResult();
        String finishReason = generation != null ? generation.getMetadata().getFinishReason() : null;
        if (finishReason == null) {
            return FinishReason.STOP;
        }

        return switch (finishReason.toLowerCase()) {
            case "length" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            case "error" -> FinishReason.ERROR;
            default -> FinishReason.STOP;
        };
    }

    private Usage toUsage(org.springframework.ai.chat.model.ChatResponse response) {
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();

        return new Usage(
                toLong(usage.getPromptTokens()),
                toLong(usage.getCompletionTokens()),
                toLong(usage.getTotalTokens())
        );
    }

    private Long toLong(Integer value) {
        return value != null ? value.longValue() : null;
    }
}
