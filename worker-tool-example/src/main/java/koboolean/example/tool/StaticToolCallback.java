package koboolean.example.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A {@link ToolCallback} that only advertises a tool's schema to the AI runtime.
 * <p>
 * InfraMesh Workers do not execute tools themselves — tool execution belongs to the
 * Agent Client (e.g. OpenCode) that issued the {@code ChatRequest}. This callback exists
 * solely so Spring AI can include the tool definition in the outgoing prompt; its
 * {@link #call(String)} must never be invoked because this Worker calls {@code ChatModel}
 * directly instead of going through Spring AI's tool-execution advisors.
 */
public final class StaticToolCallback implements ToolCallback {

    private final ToolDefinition toolDefinition;

    private StaticToolCallback(ToolDefinition toolDefinition) {
        this.toolDefinition = toolDefinition;
    }

    public static StaticToolCallback of(String name, String description, String inputSchema) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        return new StaticToolCallback(definition);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        throw new UnsupportedOperationException(
                "worker-tool-example does not execute tools; the Agent Client is responsible for tool execution");
    }
}
