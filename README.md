# Infra Worker

> The distributed AI execution runtime of InfraMesh.

Infra Worker is the execution runtime of the InfraMesh platform.

It executes execution plans received from Infra Router or directly from external applications and performs AI inference using one or more AI providers.

Unlike the Console and Router, the Worker is responsible only for execution. It never performs orchestration, planning, or routing decisions.

Workers are independently deployable, horizontally scalable, and can run anywhere—from a local laptop to large GPU clusters.

---

# Overview

InfraMesh separates management, orchestration, and execution into independent components.

```
                 +----------------------+
                 |    Infra Console     |
                 |    Control Plane     |
                 +----------+-----------+
                            |
                     Configuration
                            |
                 +----------v-----------+
                 |    Infra Router      |
                 | AI Orchestration     |
                 +----------+-----------+
                            |
                    Execution Plans
                            |
        +-------------------+-------------------+
        |                                       |
+-------v--------+                     +--------v--------+
| Infra Worker A |                     | Infra Worker B  |
+-------+--------+                     +--------+--------+
        |                                       |
        ▼                                       ▼
 OpenAI / Gemini / Ollama / Claude / Custom AI
```

Each component has a single responsibility.

- Console manages infrastructure.
- Router builds execution plans.
- Workers execute those plans.

The Worker never decides how a request should be executed.

It simply executes the execution plan provided by the Router.

---

# Responsibilities

Infra Worker is responsible for:

- Executing execution plans
- Performing AI inference
- Managing AI provider connections
- Streaming AI responses
- Reporting health status
- Advertising runtime capabilities
- Managing local AI resources
- Executing custom AI integrations

Workers never perform orchestration or routing.

---

# Core Concepts

## Independent Runtime

Every Worker is a standalone runtime.

A Worker can:

- Connect to an Infra Router
- Receive requests directly
- Run completely independently
- Be deployed anywhere

No Router is required for a Worker to operate.

---

## AI Provider Integration

Workers communicate directly with AI providers.

Supported providers may include:

- OpenAI
- Anthropic Claude
- Google Gemini
- Ollama
- Azure OpenAI
- OpenRouter
- Local LLMs
- Custom AI Services

Each Worker may support one or many providers.

---

# Extensibility

Infra Worker does not enforce a specific AI framework.

Organizations are free to choose the implementation that best fits their environment.

Examples include:

- Spring AI
- LangChain
- Ollama
- llama.cpp
- Custom REST APIs
- Enterprise AI Platforms

```
               Infra Worker
                      │
      ┌───────────────┼────────────────┐
      │               │                │
      ▼               ▼                ▼
 Spring AI      Ollama Client    Custom Provider
      │               │                │
      ▼               ▼                ▼
  OpenAI API      Local LLM      Internal AI
```

InfraMesh defines the communication protocol.

How inference is executed is entirely up to the Worker implementation.

---

# Runtime Philosophy

Infra Worker is **not a Java library**.

It is an executable runtime that participates in the InfraMesh network.

Organizations may:

- Use the default Worker runtime
- Connect existing AI services
- Extend Worker capabilities
- Build custom Worker implementations
- Deploy multiple Workers with different specializations

Workers are intentionally simple.

They focus entirely on execution, while orchestration and planning remain the responsibility of the Router.

---

# Standalone Deployment

Workers can operate independently.

```
Application
      │
      ▼
Infra Worker
      │
      ▼
OpenAI
```

Or as part of a distributed AI infrastructure.

```
Application
      │
      ▼
Infra Router
      │
      ▼
Infra Worker
      │
      ▼
OpenAI
```

This allows organizations to adopt InfraMesh incrementally.

---

# Worker Capabilities

Every Worker advertises its capabilities.

Example:

```json
{
  "workerId": "worker-01",
  "groups": [
    "coding",
    "review"
  ],
  "providers": [
    "openai"
  ],
  "models": [
    "gpt-5",
    "gpt-4.1"
  ],
  "streaming": true,
  "maxConcurrency": 16
}
```

Routers use these capabilities when building execution plans and selecting the most appropriate Workers.

---

# Authentication

Workers authenticate using API Keys issued by Infra Console.

Supported authentication modes:

- API_KEY
- NONE (trusted network)

Authentication is configurable for secure deployments.

---

# Health Reporting

Workers periodically publish runtime information.

Examples include:

- Online status
- CPU usage
- Memory usage
- GPU utilization
- Queue length
- Active requests
- Supported models
- Runtime version

This information enables intelligent execution planning and Worker selection.

---

# Streaming

Workers support both execution modes.

## Blocking

```
Request

↓

Inference

↓

Complete Response
```

## Streaming

```
Request

↓

Inference

↓

Token

↓

Token

↓

Token
```

Streaming responses are forwarded immediately as tokens are generated.

---

# Getting Started

Typical deployment options include:

### Standalone AI Server

```
Application

↓

Infra Worker

↓

OpenAI
```

### Distributed AI Infrastructure

```
Application

↓

Infra Router

↓

Execution Plan

↓

Infra Worker

↓

OpenAI
```

### Custom AI Runtime

```
Infra Worker

↓

Your AI Service
```

Workers can be deployed as:

- Docker containers
- Kubernetes workloads
- Standalone servers
- Edge devices
- Local development environments

---

# Design Goals

Infra Worker follows several core principles.

- Protocol First
- Execution Focused
- Independent Deployment
- Executable Runtime
- AI Provider Agnostic
- Framework Agnostic
- Vendor Neutral
- Streaming First
- Horizontally Scalable
- Extensible

---

# Future Features

Planned capabilities include:

- GPU-aware execution
- Automatic model discovery
- Dynamic capability updates
- Runtime plugin system
- Multi-GPU scheduling
- Local model management
- Secure sandbox execution
- Auto registration
- Performance metrics
- Distributed caching

---

# Related Projects

| Project | Description |
|----------|-------------|
| infra-console | Control Plane |
| infra-router | AI Orchestration Engine |
| infra-worker | AI Execution Runtime |

---

# License

Apache License 2.0
