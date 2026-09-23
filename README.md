# InfraMesh Worker Example

> Reference implementations for building AI inference Worker nodes for the InfraMesh distributed AI inference network.

`infra-worker` provides reference implementations showing how to build **Worker nodes** using the `infra-node` SDK.

A Worker is responsible for executing AI inference using a configured AI runtime or provider.

This repository demonstrates blocking, streaming, and tool-capable Worker implementations and can be used as a starting point for building custom InfraMesh Workers.

---

# Overview

InfraMesh separates platform management, routing decisions, and inference execution into independent responsibilities.

```text
                       Client
                          │
                          ▼
                 +------------------+
                 |  Infra Console   |
                 |  Control Plane   |
                 |  + Orchestration |
                 +---------+--------+
                           |
                    Routing Strategy
                           |
              +------------+------------+
              |                         |
              | Router-based            | Built-in
              | Routing                 | Routing
              ▼                         |
      +---------------+                 |
      | Infra Router  |                 |
      | Worker Select |                 |
      +-------+-------+                 |
              |                         |
              | workerId                |
              +------------+------------+
                           |
                           ▼
                  +----------------+
                  |  Infra Worker  |
                  |   Inference    |
                  +-------+--------+
                          |
                          ▼
                     AI Runtime
```

Each component has a clear responsibility:

- **Console** manages the platform, determines eligible Workers, applies routing strategies, and orchestrates inference requests.
- **Router** optionally selects a Worker when routing is delegated to a Router node.
- **Worker** performs the actual AI inference.
- **infra-node** defines the common SDK, DTOs, health contracts, authentication support, and node integration specifications.

A Router is not required for every Worker invocation.

With built-in routing strategies, Console can select and invoke a Worker directly.

The Worker does **not perform routing decisions**.

It receives an inference request from an authorized caller and executes it using its configured AI runtime.

---

# infra-node Dependency

Worker implementations should depend on the **InfraMesh Node SDK (`infra-node`)**.

The SDK provides common components and contracts required to participate in the InfraMesh network, including:

- `WorkerRequest`
- Worker response contracts
- Chat messages and options
- Tool-related request and response contracts
- Node health contracts
- CPU and memory monitoring
- NVIDIA GPU monitoring
- API key authentication
- Servlet and reactive integrations
- Common node configuration

During local development, the SDK can be included as a JAR dependency.

```gradle
dependencies {
    implementation files(
        "${rootProject.projectDir}/libs/infra-node-1.0.0.jar"
    )
}
```

Custom Worker implementations should use the contracts provided by `infra-node` rather than defining incompatible request, response, health, or authentication specifications.

```text
                    infra-node
                        │
                Worker Protocol
                        │
              ┌─────────┴─────────┐
              │                   │
              ▼                   ▼
           Console             Worker
```

The installed `infra-node` version is the source of truth for the Worker protocol.

---

# Worker Responsibilities

An InfraMesh Worker is responsible for:

- Receiving inference requests
- Executing AI inference
- Connecting to AI runtimes or providers
- Translating InfraMesh requests into runtime-specific requests
- Translating runtime responses into InfraMesh responses
- Returning completed inference responses
- Streaming responses when supported
- Forwarding tool-related information when supported
- Reporting node health
- Reporting runtime resource information
- Exposing runtime and model capabilities

Workers do **not**:

- Select other Workers
- Perform routing
- Decide routing strategies
- Discover other Workers
- Invoke Router nodes
- Manage Teams or Organizations
- Access Console infrastructure state
- Manage InfraMesh infrastructure

Those responsibilities remain outside the Worker.

---

# Worker Interface

A Worker implementation can expose the following capabilities:

```text
Worker
├── Health
│
├── Invoke
│
└── Stream (optional)
```

`health` and `invoke` form the basic Worker integration.

`stream` can additionally be implemented when the Worker runtime and application stack support streaming inference.

Conceptually:

```text
Console / Authorized Client
           │
           ▼
        Worker
           │
    ┌──────┼──────┐
    │      │      │
    ▼      ▼      ▼
 Health  Invoke  Stream
```

---

# Health

Workers expose runtime health information through the common health contract provided by `infra-node`.

```http
GET /api/v1/health
```

Infra Console can periodically call this endpoint to determine whether the Worker is available and collect runtime information.

```text
Infra Console
      │
      │ GET /api/v1/health
      ▼
    Worker
      │
      ▼
NodeHealthResponse
```

Depending on the current `infra-node` health contract and runtime environment, health information may include:

- Node status
- Framework information
- Uptime
- Active requests
- Queue state
- Latency information
- Throughput information
- Error information
- CPU information and utilization
- Memory information and utilization
- GPU information and utilization
- GPU memory information

Workers without an NVIDIA GPU are also supported.

In environments where NVIDIA GPU information cannot be collected, GPU information may be absent or empty according to the common health contract.

CPU-only Workers are valid InfraMesh Workers.

---

# WorkerRequest

Inference requests use the Worker request contract defined by `infra-node`.

```java
com.inframesh.node.dto.worker.WorkerRequest
```

The exact fields are defined by the installed `infra-node` version.

Conceptually, a Worker request can contain:

```text
WorkerRequest
├── Messages
├── Chat Options
├── Tool Definitions
├── Tool Choice
└── Other inference information
```

Worker implementations should not redefine this protocol using local DTOs such as:

```text
CustomWorkerRequest
RuntimeRequest
WorkerChatRequest
```

unless those classes are strictly internal runtime adapters.

The common `WorkerRequest` should remain the boundary contract between InfraMesh and the Worker.

---

# Invoke

Blocking inference is performed through:

```http
POST /api/v1/invoke
```

A typical execution flow is:

```text
WorkerRequest
       │
       ▼
     Worker
       │
       ▼
Runtime Adapter
       │
       ▼
  AI Runtime
       │
       ▼
 AI Inference
       │
       ▼
InfraMesh Response
```

The Worker receives the InfraMesh request, converts it into the format expected by its configured AI runtime, performs inference, and converts the runtime result back into the common InfraMesh response contract.

Conceptually:

```text
InfraMesh Protocol
       │
       ▼
Worker Adapter
       │
       ▼
Runtime Protocol
       │
       ▼
AI Runtime
```

This separation allows the Worker contract to remain independent from the underlying runtime.

---

# Streaming

Workers may optionally provide streaming inference through:

```http
POST /api/v1/stream
```

Streaming Workers return generated content incrementally rather than waiting for the complete inference result.

```text
WorkerRequest
       │
       ▼
     Worker
       │
       ▼
  AI Runtime
       │
       ├── Chunk
       ├── Chunk
       ├── Chunk
       └── Chunk
       │
       ▼
Streaming Response
```

Streaming support belongs to the **Worker**, not the Router.

For a streaming inference request:

```text
Client
   │
   ▼
Console
   │
   │ Determine streaming-capable
   │ Worker candidates
   ▼
Routing
   │
   ▼
Selected Worker
   │
   │ /api/v1/stream
   ▼
AI Runtime
   │
   ▼
Streaming Response
```

If a Router node participates in selection, the Router still uses its normal non-streaming invoke contract.

```text
Console
   │
   │ RoutingRequest
   ▼
Router /api/v1/invoke
   │
   │ workerId
   ▼
Console
   │
   │ WorkerRequest
   ▼
Worker /api/v1/stream
```

The Router never proxies the streaming response.

---

# AI Runtime Integration

InfraMesh does not require Workers to use a specific AI framework, runtime, or provider.

A Worker acts as an adapter between the InfraMesh Worker protocol and an AI runtime.

```text
                    Infra Worker
                         │
          ┌──────────────┼──────────────┐
          │              │              │
          ▼              ▼              ▼
      Spring AI       Custom          Direct
                       Client        Integration
          │              │              │
          ▼              ▼              ▼
       Ollama        AI Service        vLLM
```

Possible integrations include:

- Spring AI
- Ollama
- OpenAI-compatible APIs
- vLLM
- Custom REST APIs
- Local LLM runtimes
- Enterprise AI platforms
- Custom model runtimes

The actual inference implementation remains application-specific.

---

# Runtime Protocol Independence

Workers should isolate runtime-specific protocols from the InfraMesh protocol.

For example:

```text
Console
   │
   │ WorkerRequest
   ▼
Worker
   │
   │ Runtime-specific mapping
   ▼
OpenAI / Ollama / vLLM / Custom Protocol
   │
   ▼
AI Runtime
```

A Worker using an OpenAI-compatible runtime does not mean that the Worker itself must expose an OpenAI-compatible API.

The Worker can continue exposing the InfraMesh Worker contract while translating internally to the runtime protocol.

This keeps external client compatibility separate from Worker implementation details.

---

# OpenAI-Compatible Clients

External protocol compatibility is handled before the request reaches the Worker.

For example:

```text
OpenAI-Compatible Client
          │
          │ OpenAI Protocol
          ▼
       Console
          │
          │ InfraMesh Protocol
          ▼
       Worker
          │
          │ Runtime Protocol
          ▼
      AI Runtime
```

Therefore, a Worker does **not** need to expose:

```text
/v1/chat/completions
```

simply to support OpenAI-compatible clients.

Console can translate the external request into the InfraMesh Worker contract.

The Worker remains focused on inference execution.

---

# Independent Worker

A Worker does not require a Router in order to execute inference.

The normal InfraMesh flow may use built-in Console routing:

```text
Application
     │
     ▼
Infra Console
     │
     │ Built-in Routing
     ▼
   Worker
     │
     ▼
 AI Runtime
```

Or custom Router-based routing:

```text
Application
     │
     ▼
Infra Console
     │
     ▼
   Router
     │
     │ workerId
     ▼
Infra Console
     │
     ▼
   Worker
     │
     ▼
 AI Runtime
```

A Worker can also be invoked directly by an authorized client when standalone operation is desired.

```text
Application
     │
     ▼
   Worker
     │
     ▼
 AI Runtime
```

The Worker contract remains the same regardless of how the Worker was selected.

---

# Authentication

Workers can authenticate requests using node API keys issued and managed through Infra Console.

```http
X-Infra-Api-Key: <NODE_API_KEY>
```

Supported authentication modes may include:

```text
API_KEY
NONE
```

`NONE` should only be used in trusted environments.

When API key authentication is enabled, protected Worker endpoints use the node authentication mechanism provided by `infra-node`.

Typical protected endpoints include:

```text
GET  /api/v1/health
POST /api/v1/invoke
POST /api/v1/stream
```

Callers must provide the appropriate Worker API key when accessing protected endpoints.

Example:

```bash
curl \
  -H "X-Infra-Api-Key: <api-key>" \
  http://localhost:8082/api/v1/health
```

---

# Example Modules

This repository contains multiple reference Worker implementations built using `infra-node`.

| Module | Stack | Default Port | Purpose |
|---|---|---:|---|
| `worker-webmvc-example` | Spring MVC / Tomcat | `8082` | Blocking inference |
| `worker-stream-example` | Reactive / Netty | `8081` | Streaming inference |
| `worker-tool-example` | Reactive | `8083` | Tool-capable inference |

Each module demonstrates a different Worker integration pattern.

---

# Blocking Worker

`worker-webmvc-example` demonstrates a traditional blocking Worker.

```text
POST /api/v1/invoke
        │
        ▼
   Spring MVC
        │
        ▼
 Runtime Adapter
        │
        ▼
    ChatModel
        │
        ▼
Complete Response
```

This example is useful for runtimes where the complete inference response is returned at once.

The application runs using the Servlet stack and can use Tomcat as its embedded web server.

---

# Streaming Worker

`worker-stream-example` demonstrates a reactive streaming Worker.

```text
POST /api/v1/stream
        │
        ▼
 Reactive Server
        │
        ▼
 Runtime Adapter
        │
        ▼
    ChatModel
        │
        ▼
 Streaming Response
```

This example is useful for AI runtimes capable of returning generated content incrementally.

The streaming example can use a reactive server such as Netty rather than requiring the Servlet stack.

---

# Tool Calling Worker

`worker-tool-example` demonstrates a Worker capable of forwarding tool-related information between InfraMesh and an AI runtime.

Conceptually:

```text
WorkerRequest
(messages, tools, toolChoice)
        │
        ▼
worker-tool-example
        │
        ▼
Runtime Adapter
        │
        ▼
AI Runtime
        │
        ▼
message / tool_calls
```

The Worker translates:

```text
InfraMesh ToolDefinition
        ↕
Runtime Tool Schema
```

and:

```text
Runtime ToolCall
        ↕
InfraMesh ToolCall
```

The Worker does **not execute application tools itself**.

When the runtime returns a tool call:

```text
AI Runtime
    │
    ▼
Tool Call
    │
    ▼
Worker
    │
    ▼
Console / Agent Client
```

the actual tool execution remains the responsibility of the Agent or application layer responsible for that tool.

After tool execution, the resulting tool message can be included in a subsequent inference request.

This keeps the Worker focused on protocol translation and AI inference rather than application-specific tool execution.

---

# Tool Calling and Streaming

A Worker may support tool calling together with streaming when the underlying runtime supports it.

Conceptually:

```text
WorkerRequest
      │
      ▼
Worker /stream
      │
      ▼
AI Runtime
      │
      ├── Content chunks
      │
      └── Tool call information
      │
      ▼
InfraMesh Streaming Response
```

The exact representation of tool calls and streaming events should follow the current `infra-node` protocol.

Custom Workers should not introduce incompatible tool-call formats.

---

# Request Information Preservation

Workers should preserve information supported by the current `WorkerRequest` contract when translating requests to an AI runtime.

For example, if supported by the current protocol:

```text
Messages
Options
Tools
Tool Choice
Tool Results
Other inference metadata
```

should not be silently discarded by the Worker adapter.

If an underlying runtime does not support a requested capability, the Worker should handle that limitation explicitly rather than silently changing the meaning of the request.

---

# Getting Started

## 1. Requirements

The example projects require:

- JDK 21+
- Gradle
- `infra-node` SDK
- A supported AI runtime

The provided Spring AI examples may use Ollama during local development.

For local Ollama development:

```bash
ollama serve
```

Make sure the configured model is available locally.

For example:

```bash
ollama pull <model>
```

---

## 2. Add infra-node

Place the SDK JAR under the repository-level `libs` directory:

```text
infra-worker
├── libs
│   └── infra-node-1.0.0.jar
│
├── worker-webmvc-example
├── worker-stream-example
└── worker-tool-example
```

Then reference it from the Worker module:

```gradle
dependencies {
    implementation files(
        "${rootProject.projectDir}/libs/infra-node-1.0.0.jar"
    )
}
```

Using `rootProject.projectDir` is important in a multi-module Gradle project because a relative `libs/...` path would otherwise be resolved from the individual subproject.

When repository-based distribution becomes available, this dependency can be replaced with the corresponding repository dependency.

---

## 3. Configure Worker

Example `application.yml`:

```yaml
server:
  port: 8082

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
        model: <model>
        temperature: 0.7
```

The API key should correspond to the Worker registered in Infra Console.

Configuration for the actual AI runtime is Worker implementation-specific.

---

## 4. Run

Blocking example:

```bash
./gradlew :worker-webmvc-example:bootRun
```

Streaming example:

```bash
./gradlew :worker-stream-example:bootRun
```

Tool-capable example:

```bash
./gradlew :worker-tool-example:bootRun
```

---

## 5. Check Health

```bash
curl \
  -H "X-Infra-Api-Key: <api-key>" \
  http://localhost:8082/api/v1/health
```

A successful response confirms that the Worker is running and accessible through the InfraMesh node contract.

---

## 6. Invoke

Use the request structure defined by the current `WorkerRequest` contract.

For a simple chat request, conceptually:

```bash
curl -X POST http://localhost:8082/api/v1/invoke \
  -H "Content-Type: application/json" \
  -H "X-Infra-Api-Key: <api-key>" \
  -d '{
        "messages": [
          {
            "role": "USER",
            "content": "Hello"
          }
        ],
        "options": {
          "temperature": 0.7
        }
      }'
```

The exact supported request fields should follow the installed `infra-node` version.

---

# Building a Custom Worker

The examples in this repository are intended to be used as references.

A custom Worker implementation should generally:

1. Add the `infra-node` dependency.
2. Configure InfraMesh node authentication.
3. Expose the health contract provided by `infra-node`.
4. Implement `/api/v1/invoke`.
5. Accept the common `WorkerRequest`.
6. Translate the request into the target runtime protocol.
7. Execute inference using the desired AI runtime.
8. Convert the runtime result into the InfraMesh response contract.
9. Optionally implement `/api/v1/stream` when streaming is supported.
10. Preserve supported tool and request information.

Conceptually:

```text
                    infra-node
                        │
                Worker Contracts
                        │
          ┌─────────────┴─────────────┐
          │                           │
          ▼                           ▼

   Example Worker               Custom Worker

          │                           │
          └─────────────┬─────────────┘
                        │
                        ▼

                    InfraMesh
```

The Worker does not need to use Spring AI.

The important requirement is to follow the **InfraMesh Worker contract defined by `infra-node`**.

---

# Servlet and Reactive Workers

`infra-node` supports Worker implementations using different web application stacks.

For example:

```text
Blocking Worker
    │
    └── Spring MVC / Tomcat


Streaming Worker
    │
    └── Reactive / Netty
```

Use the stack appropriate for the Worker implementation.

Avoid unnecessarily combining Servlet and reactive web stacks in the same Worker application.

Worker behavior should remain compatible with the same InfraMesh protocol regardless of the underlying server stack.

---

# GPU Monitoring

`infra-node` can collect NVIDIA GPU information when supported by the environment.

For NVIDIA environments, GPU information may include:

```text
GPU
├── Index
├── Name
├── Usage
├── Total Memory
├── Used Memory
└── Available Memory
```

The exact representation follows the current `infra-node` health contract.

On environments without an NVIDIA GPU or `nvidia-smi`, such as many local macOS development environments, the Worker can operate normally without NVIDIA GPU information.

CPU-only Workers are valid InfraMesh Workers.

---

# Runtime and Capability Information

Workers may expose runtime and capability information that Console can use for monitoring and routing.

This may include:

```text
Runtime
├── Framework
├── Framework Version
└── Uptime

Load
├── Active Requests
├── Queue
├── Latency
├── Throughput
└── Error Information

Hardware
├── CPU
├── Memory
├── GPU
└── VRAM

Capabilities
├── Models
├── Provider / Runtime
├── Streaming
└── Tool Support
```

Console can use this information to determine Worker eligibility and build routing candidates.

When Router-based routing is used, relevant Worker information can then be represented as `WorkerCandidate` and supplied to the Router.

The Worker itself does not create `WorkerCandidate`.

---

# Deployment

Workers are independently deployable.

Possible deployment environments include:

- Docker containers
- Kubernetes workloads
- GPU servers
- CPU-only servers
- Edge environments
- Local development machines

Different Workers may use different hardware, models, frameworks, and inference runtimes while participating in the same InfraMesh network.

```text
                     InfraMesh

        ┌───────────────┼───────────────┐
        │               │               │
        ▼               ▼               ▼

    Worker A         Worker B         Worker C

    GPU Server       CPU Server       Local Node

      vLLM            Custom           Ollama
```

This heterogeneous execution model is one of the primary goals of InfraMesh.

---

# Fat JAR Development Dependency

During development, `infra-node` may be distributed as a local JAR rather than through a Maven-compatible repository.

Use:

```gradle
implementation files(
    "${rootProject.projectDir}/libs/infra-node-1.0.0.jar"
)
```

Avoid defining duplicate versions of SDK contracts inside Worker modules.

Once `infra-node` is distributed through a Maven-compatible repository, dependency metadata and transitive dependencies can be managed through Gradle normally.

---

# Design Goals

Infra Worker follows several core principles:

- **Execution Focused** — Workers execute AI inference rather than making routing decisions.
- **Protocol First** — Worker implementations follow the `infra-node` contract.
- **Runtime Adapter** — Workers bridge InfraMesh requests and runtime-specific protocols.
- **Runtime Independent** — Different inference runtimes can participate in InfraMesh.
- **Framework Agnostic** — Worker implementations are not restricted to Spring AI.
- **Vendor Neutral** — InfraMesh does not require a specific AI provider.
- **Independent Deployment** — Workers can be deployed separately from Console and Router.
- **Heterogeneous Compute** — Both GPU and CPU Workers are supported.
- **Streaming Capable** — Workers may support streaming when appropriate.
- **Tool Capable** — Workers may forward tool-related information without owning application tool execution.
- **Horizontally Scalable** — Multiple Workers can provide inference capacity.
- **Extensible** — Organizations can implement custom Worker integrations.

---

# Component Responsibilities

```text
Console
────────────────────────────────
Platform management
Organizations / Teams
Node management
Worker eligibility
Routing strategy
Router delegation
Worker invocation
Streaming orchestration


Router
────────────────────────────────
RoutingRequest analysis
WorkerCandidate analysis
Worker selection
RoutingResponse generation


Worker
────────────────────────────────
WorkerRequest handling
Runtime protocol translation
AI inference
Invoke
Streaming
Tool information forwarding
Health / runtime reporting


infra-node
────────────────────────────────
Common SDK
Protocol DTOs
Router contracts
Worker contracts
Health contracts
Authentication integration
Node integration
```

---

# Related Projects

| Project | Description |
|---|---|
| `infra-console` | InfraMesh control plane and inference orchestration layer |
| `infra-node` | Core SDK and common contracts for Router and Worker nodes |
| `infra-router` | Reference implementation for custom Router nodes |
| `infra-worker` | Reference Worker implementations |

---

# Status

`infra-worker` is currently under active development.

Worker contracts, DTOs, configuration properties, example implementations, and runtime integrations may evolve as InfraMesh develops.

The corresponding `infra-node` version should be treated as the source of truth for Worker communication contracts.

---

# License

Apache License 2.0
