# Infra Worker

> The distributed AI inference runtime of InfraMesh.

Infra Worker is the execution engine of the InfraMesh platform.

It executes AI inference requests from Infra Router or external applications and returns responses to the caller.

Unlike the Console and Router, the Worker is the only component responsible for communicating with AI providers.

Workers are independently deployable, horizontally scalable, and can run anywhere—from a local laptop to large GPU clusters.

---

# Overview

InfraMesh separates infrastructure management, request routing, and inference execution into independent components.

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
                 |    Routing Layer     |
                 +----------+-----------+
                            |
                  Inference Requests
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

Workers execute AI inference.

They never perform routing or infrastructure management.

---

# Responsibilities

Infra Worker is responsible for:

- Executing AI inference
- Managing AI provider connections
- Streaming AI responses
- Reporting health status
- Advertising supported capabilities
- Managing local AI resources
- Executing custom inference logic

Workers never perform request routing.

---

# Core Concepts

## Independent Runtime

Every Worker is a standalone server.

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

Each Worker can support one or many providers.

---

# Extensibility

Infra Worker does not force any AI framework.

Users are free to choose their preferred implementation.

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

How AI inference is implemented is completely up to the Worker implementation.

---

# Runtime Philosophy

Infra Worker is **not a Java library**.

It is an executable runtime that provides the infrastructure required to participate in the InfraMesh network.

Users may:

- Use the default Worker runtime
- Connect their own AI implementation
- Extend the Worker with additional capabilities
- Build a completely custom Worker
- Deploy multiple Workers with different AI providers

The Worker acts as an execution engine rather than an AI framework.

---

# Standalone Deployment

Workers can operate completely independently.

```
Application
      │
      ▼
Infra Worker
      │
      ▼
OpenAI
```

Or as part of a distributed infrastructure.

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

Organizations can adopt InfraMesh gradually without deploying every component.

---

# Worker Capabilities

Every Worker advertises its capabilities.

Example:

```json
{
  "workerId": "worker-01",
  "providers": [
    "openai",
    "ollama"
  ],
  "models": [
    "gpt-5",
    "gpt-4.1",
    "llama3"
  ],
  "streaming": true,
  "maxConcurrency": 16
}
```

Routers use these capabilities when selecting the best Worker.

---

# Authentication

Workers authenticate using API Keys issued by Infra Console.

Supported authentication modes:

- API_KEY
- NONE (trusted network)

Authentication is configurable for secure deployments.

---

# Health Reporting

Workers periodically report runtime information.

Examples include:

- Online status
- CPU usage
- Memory usage
- GPU utilization
- Queue length
- Active requests
- Supported models
- Runtime version

This information enables intelligent routing.

---

# Streaming

Workers support both execution modes.

## Blocking

```
Request
    │
    ▼
Inference
    │
    ▼
Complete Response
```

## Streaming

```
Request
    │
    ▼
Inference
    │
    ▼
Token
Token
Token
Token
```

Streaming responses are forwarded immediately without buffering the entire response.

---

# Getting Started

Typical deployment options include:

### Standalone AI Server

```
Application
        │
        ▼
Infra Worker
        │
        ▼
OpenAI
```

### Distributed AI Infrastructure

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

### Custom AI Runtime

```
Infra Worker
       │
       ▼
Your AI Service
```

Workers can be packaged as:

- Docker containers
- Kubernetes workloads
- Standalone servers
- Edge devices
- Local development environments

---

# Design Goals

Infra Worker follows several core principles.

- Independent Deployment
- Executable Runtime
- AI Provider Agnostic
- Framework Agnostic
- Protocol First
- Vendor Neutral
- Lightweight
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
| infra-router | AI Request Router |
| infra-worker | AI Worker Runtime |

---

# License

Apache License 2.0
