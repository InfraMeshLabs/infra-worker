package koboolean.example.service;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.ChatOptions;
import com.inframesh.node.dto.ChatResponse;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.dto.Usage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerService {

    private final ChatModel chatModel;

    public ChatResponse invoke(WorkerRequest request) {
        log.debug("Handling invoke request, sessionId={}", request.sessionId());

        org.springframework.ai.chat.model.ChatResponse response = chatModel.call(toPrompt(request));

        Generation generation = response.getResult();
        String content = generation != null ? generation.getOutput().getText() : null;

        return new ChatResponse(content, toUsage(response));
    }

    private Prompt toPrompt(WorkerRequest request) {
        List<Message> messages = request.messages().stream()
                .map(this::toMessage)
                .toList();

        return new Prompt(messages, toChatOptions(request.options()));
    }

    private Message toMessage(ChatMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
            // This module does not support Tool Calling; see worker-tool-example for that.
            case TOOL -> new AssistantMessage(message.content());
        };
    }

    private org.springframework.ai.chat.prompt.ChatOptions toChatOptions(ChatOptions options) {
        if (options == null) {
            return null;
        }

        return org.springframework.ai.chat.prompt.ChatOptions.builder()
                .temperature(options.temperature())
                .maxTokens(options.maxTokens())
                .topP(options.topP())
                .build();
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
