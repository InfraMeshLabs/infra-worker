# worker-tool-example

> A reference InfraMesh Worker demonstrating **Tool Calling** on top of the `infra-node` SDK.

`worker-tool-example` shows how a Worker can advertise a Console-supplied tool list to an AI
runtime, forward whatever tool calls the model decides to make back to the Console, and later
resume the conversation once the Agent Client (e.g. OpenCode) has executed those tools and
returned results. It also supports plain chat with no tools involved, and — where the AI
runtime allows it — streaming tool calls.

**This Worker never executes tools itself.** `read_file`, `edit_file`, `shell`, and any other
tool implementation belongs to the Agent Client that issued the request. This module only
translates between the InfraMesh protocol and the AI runtime's own tool-calling wire format.

---

## Module purpose

| | |
|---|---|
| Purpose | Tool Calling example Worker (plus plain chat) |
| Web stack | Spring WebFlux (Netty) |
| AI runtime | Spring AI `ChatModel` — Ollama by default |
| SDK dependency | `libs/inframesh-node-1.0.0.jar` (JAR only, no `infra-node` source dependency) |
| Default port | `8083` |

It sits alongside the other two example Workers, each demonstrating a different concern:

```text
worker-stream-example  -> Netty / Streaming
worker-webmvc-example  -> Tomcat / WebMVC
worker-tool-example    -> Tool Calling (general chat + tool calls + tool results)
```

## Why WebFlux

Tool Calling and the web stack are independent decisions, but this module bases its stack on
`worker-stream-example` rather than `worker-webmvc-example` because:

- The requirements cover general chat, non-streaming Tool Calling, and (where possible)
  **streaming** Tool Calling. `worker-webmvc-example` has no `/stream` endpoint at all, so
  covering streaming would mean rebuilding a reactive pipeline anyway.
- `inframesh-node-1.0.0.jar` models streaming tool calls as a first-class concept
  (`ChatStreamResponse.toolCallDeltas` / `ToolCallDelta`), which maps naturally onto a
  `Flux` pipeline.
- Reusing the `worker-stream-example` structure means this module exposes both `/invoke`
  (`Mono<ChatResponse>`) and `/stream` (`Flux<ChatStreamResponse>`) exactly like that module,
  satisfying "general chat + tool calling" without inventing a new endpoint shape.

## inframesh-node-1.0.0.jar dependency

Same convention as the other two modules — a local JAR file reference, not a project
dependency on `infra-node` sources:

```gradle
dependencies {
    implementation files("${rootProject.projectDir}/libs/inframesh-node-1.0.0.jar")
}
```

Health (`GET /api/v1/health`) and API key authentication (`X-Infra-Api-Key`) under
`/api/v1/**` are provided automatically by the SDK's `NodeAutoConfiguration` — there is no
Worker-side code for either.

## SDK types used

This module uses the InfraMesh common DTOs directly; it does **not** define its own tool
types (no `WorkerTool`, `WorkerToolCall`, `OpenAiTool`, etc.):

- `com.inframesh.node.dto.worker.WorkerRequest` (`messages`, `options`, `tools`, `toolChoice`)
- `com.inframesh.node.dto.ChatMessage` (including `toolCalls` / `toolCallId` for `ASSISTANT`
  / `TOOL` role messages)
- `com.inframesh.node.dto.ToolDefinition`, `ToolCall`, `ToolCallDelta`, `ToolChoice`
- `com.inframesh.node.dto.ChatResponse`, `ChatStreamResponse`, `Usage`
- `com.inframesh.node.enums.ChatRole`, `FinishReason`, `ToolChoiceMode`

`ToolCall.arguments()` / `ToolDefinition.parameters()` are
`tools.jackson.databind.JsonNode` — the Jackson 3 (`tools.jackson`) library bundled inside the
SDK jar itself, used here only for JSON <-> string conversion when talking to Spring AI.

## AI runtime configuration

Same environment shape as the other examples:

```yaml
server:
  port: 8083
infra:
  node:
    api-key: <UUID issued by Infra Console>
spring:
  ai:
    model:
      chat: ollama
    ollama:
      base-url: http://localhost:11434
      chat:
        model: <model>       # must support tool calling, e.g. a model tagged "tools"
        temperature: 0.7
```

Any Ollama model advertising the `tools` capability (check with `ollama show <model>` or the
`/api/tags` capabilities field) works. Models without that capability will simply never emit
`tool_calls`, degrading to plain chat.

## Run

```bash
./gradlew :worker-tool-example:bootRun
```

## Console connection

Identical to the other Workers — register the module's port and API key in Infra Console, or
call it directly for local testing:

```bash
curl -H "X-Infra-Api-Key: <api-key>" http://localhost:8083/api/v1/health
```

## Request/response flow

### 1. General chat (no tools)

```text
USER
 -> Worker -> Model -> ASSISTANT
```

```bash
curl -X POST http://localhost:8083/api/v1/invoke \
  -H "Content-Type: application/json" \
  -H "X-Infra-Api-Key: <api-key>" \
  -d '{
        "messages": [
          {"role": "USER", "content": "Say hello in exactly 3 words."}
        ],
        "options": {"temperature": 0.7}
      }'
```

```json
{"message":"Hello there, how are you?","toolCalls":[],"finishReason":"STOP","usage":{"promptTokens":24,"completionTokens":8,"totalTokens":32}}
```

### 2. Tool Calling

```text
USER + tools
 -> Worker -> Model -> tool_calls -> Worker -> Console
```

```bash
curl -X POST http://localhost:8083/api/v1/invoke \
  -H "Content-Type: application/json" \
  -H "X-Infra-Api-Key: <api-key>" \
  -d '{
        "messages": [
          {"role": "USER", "content": "What is the current weather in Seoul? Use the get_weather tool."}
        ],
        "tools": [
          {
            "name": "get_weather",
            "description": "Get the current weather for a given city",
            "parameters": {
              "type": "object",
              "properties": {"city": {"type": "string", "description": "City name"}},
              "required": ["city"]
            }
          }
        ],
        "toolChoice": {"mode": "AUTO"}
      }'
```

```json
{"message":"","toolCalls":[{"id":"call_mamrr293","name":"get_weather","arguments":{"city":"Seoul"}}],"finishReason":"TOOL_CALLS","usage":{"promptTokens":83,"completionTokens":178,"totalTokens":261}}
```

The Worker never calls `get_weather` — it only forwards the model's intent to call it.

### 3. Tool Result follow-up

```text
ASSISTANT tool_calls
 -> Agent executes the tool
 -> TOOL message
 -> Console -> Worker -> Model -> final ASSISTANT response
```

```bash
curl -X POST http://localhost:8083/api/v1/invoke \
  -H "Content-Type: application/json" \
  -H "X-Infra-Api-Key: <api-key>" \
  -d '{
        "messages": [
          {"role": "USER", "content": "What is the current weather in Seoul? Use the get_weather tool."},
          {"role": "ASSISTANT", "content": "", "toolCalls": [{"id": "call_mamrr293", "name": "get_weather", "arguments": {"city": "Seoul"}}]},
          {"role": "TOOL", "toolCallId": "call_mamrr293", "content": "{\"city\":\"Seoul\",\"tempC\":24,\"condition\":\"Sunny\"}"}
        ],
        "tools": [
          {
            "name": "get_weather",
            "description": "Get the current weather for a given city",
            "parameters": {
              "type": "object",
              "properties": {"city": {"type": "string", "description": "City name"}},
              "required": ["city"]
            }
          }
        ]
      }'
```

```json
{"message":"The current weather in Seoul is Sunny and 24°C.","toolCalls":[],"finishReason":"STOP","usage":{"promptTokens":126,"completionTokens":167,"totalTokens":293}}
```

### 4. Streaming (including Streaming Tool Calling)

```bash
curl -N -X POST http://localhost:8083/api/v1/stream \
  -H "Content-Type: application/json" \
  -H "X-Infra-Api-Key: <api-key>" \
  -d '{
        "messages": [
          {"role": "USER", "content": "What is the weather in Busan? Use the get_weather tool."}
        ],
        "tools": [
          {
            "name": "get_weather",
            "description": "Get the current weather for a given city",
            "parameters": {
              "type": "object",
              "properties": {"city": {"type": "string", "description": "City name"}},
              "required": ["city"]
            }
          }
        ]
      }'
```

The final `ChatStreamResponse` chunk carries the completed tool call(s) in `toolCallDeltas`,
`finishReason: "TOOL_CALLS"`, and `usage`:

```json
{"content":"","toolCallDeltas":[{"index":0,"id":"call_icao6qjh","name":"get_weather","argumentsDelta":"{\"city\":\"Busan\"}"}],"finishReason":"TOOL_CALLS","usage":{"promptTokens":82,"completionTokens":185,"totalTokens":267}}
```

**Note:** Ollama does not stream tool call arguments incrementally the way OpenAI-style APIs
do — it emits each tool call as one complete object in a single chunk rather than as
character-level argument deltas. `ToolCallDelta.argumentsDelta` therefore carries the whole
arguments payload at once here; the delta shape from the SDK is still honored so a Console
built against true incremental deltas continues to work unmodified against other runtimes.

## Known simplifications

- **Tool result identity**: `ChatMessage(role=TOOL)` in the InfraMesh protocol only carries
  `toolCallId` + `content` (OpenAI-style). Ollama's native tool-result message keys results by
  tool *name*, not call id. This module reuses the call id as the name when building the
  Ollama request; for single-tool-call turns (the common case) this is transparent, but it is
  a known gap if a runtime strictly validates the tool name.
- **`toolChoice`**: Ollama has no API-level way to *force* a tool call the way OpenAI's
  `tool_choice: required` / `{"type": "function", ...}` does. `toolChoice.mode == NONE` is
  honored precisely (the tool definitions are withheld from the request so the model cannot
  call them); `AUTO`, `REQUIRED`, and `TOOL` are all treated as "make the tools available" —
  the actual decision to call one is still up to the model.
