# Spring AI + Ollama Demo · with Spring AI Alibaba (DashScope)

![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4-6DB33F?logo=spring&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring_AI-1.0.1-green)
![Spring AI Alibaba](https://img.shields.io/badge/Spring_AI_Alibaba-1.0.0.4-FF6A00)
![Ollama](https://img.shields.io/badge/Ollama-granite4.1:3b-000000)
![DashScope](https://img.shields.io/badge/DashScope-qwen--plus-6B54D2)
[![Build](https://img.shields.io/github/actions/workflow/status/tiagouzl/spring-ai-ollama-demo/ci.yml?branch=main&label=CI)](https://github.com/tiagouzl/spring-ai-ollama-demo/actions)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A minimal, production-style **Spring Boot 3** application that integrates **Spring AI** with a **local LLM served by Ollama** — and optionally with **Alibaba DashScope (Qwen)** via **Spring AI Alibaba** — fully offline by default, cloud-ready when you set `DASHSCOPE_API_KEY`.

This project is a clean reference for building AI-agent / LLM applications on the **Java + Spring** ecosystem, widely adopted by enterprises in China via the Spring AI Alibaba ecosystem.

---

## ✨ Highlights

- 📦 **Spring Boot 3.4** + **Spring AI 1.0.1** + **Spring AI Alibaba 1.0.0.4**
- 🏠 **100% local & free by default** — uses Ollama, no API key needed
- ☁️ **Cloud-ready** — flip to Alibaba DashScope (Qwen) by setting `DASHSCOPE_API_KEY`
- ⚡ Powered by the fluent `ChatClient` API (works with both Ollama and DashScope)
- 🔄 **Streaming** responses (Server-Sent Events) with `ChatClient.stream()`
- 💬 **Multi-turn chat** with per-conversation memory (`MessageChatMemoryAdvisor`)
- 🛠 **Function calling / Tool use** with `@Tool` — model calls Java methods (`DateTimeTools`, `MathTools`)
- 🔍 **RAG** with `SimpleVectorStore` + `nomic-embed-text` (local embeddings, no external DB)
- 🧹 Minimal setup — `spring-boot-starter-web`, `spring-ai-starter-model-ollama`, `spring-ai-alibaba-starter-dashscope`
- 🇨🇳 **Spring AI Alibaba** showcase — local Ollama + cloud DashScope in one codebase

---

## 🛠 Tech Stack

| Layer     | Technology                                    |
|-----------|-----------------------------------------------|
| Language  | Java 21 (LTS)                                 |
| Framework | Spring Boot 3.4.5                             |
| AI SDK    | Spring AI 1.0.1 + Spring AI Alibaba 1.0.0.4   |
| Model (local) | Ollama — `granite4.1:3b` + `nomic-embed-text` |
| Model (cloud) | Alibaba DashScope — `qwen-plus` (optional) |
| Vector Store | `SimpleVectorStore` (in-memory, no DB)      |
| Build     | Maven 3.9                                     |

---

## 🚀 Getting Started

### Prerequisites

- **Java 21+** (Temurin recommended)
- **Maven 3.8+**
- **Ollama** installed and running at `http://localhost:11434`

> 💡 If you don't have Java 21, install it via [SDKMAN](https://sdkman.io):
> ```bash
> curl -s "https://get.sdkman.io" | bash
> source "$HOME/.sdkman/bin/sdkman-init.sh"
> sdk install java 21.0.12-tem
> sdk install maven
> ```

### 1. Install Ollama and pull models

```bash
# Install Ollama (Linux):
curl -fsSL https://ollama.com/install.sh | sh

# Start the service:
ollama serve

# Chat model (this project uses granite4.1:3b, 3B params, CPU-friendly):
ollama pull granite4.1:3b
# Embedding model for RAG (274 MB, 768 dims):
ollama pull nomic-embed-text
```

> 🎯 The demo was validated with **`granite4.1:3b`** + **`nomic-embed-text`**. You can swap the chat model in `src/main/resources/application.yml` (e.g. `qwen2.5`, `llama3.2`, `deepseek-r1`).

### 2. Run the application

```bash
# Local-only (Ollama, no API key needed):
mvn spring-boot:run

# With Alibaba DashScope (Qwen) — also keeps Ollama for embeddings/local fallback:
export DASHSCOPE_API_KEY=sk-xxxx
mvn spring-boot:run
# Get your key at https://dashscope.console.aliyun.com/apiKey
```

Spring Boot starts on **port 8080**. Without `DASHSCOPE_API_KEY`, `/ai/alibaba/*` gracefully falls back to Ollama.

### 3. API

All endpoints accept `GET` and return the model's reply in **English** by default (overridable).

#### `/ai/chat` — single, stateless reply

```bash
curl "http://localhost:8080/ai/chat?message=Hello%20from%20Spring%20AI"
```

#### `/ai/chat/stream` — streaming reply (Server-Sent Events)

```bash
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/ai/chat/stream?message=Tell%20me%20a%20short%20story"
```

Returns `text/event-stream` with tokens arriving as individual `data:` events in real time (ideal for UI typewriter effects).

```text
data:1

data:,

data: 

data:2
```

#### `/ai/chat/memory` — multi-turn chat with memory

Memory is grouped per `sessionId` (the last 20 messages are kept). Create a session, then chat:

```bash
SESSION=$(curl -s "http://localhost:8080/ai/session")
echo "session: $SESSION"

curl "http://localhost:8080/ai/chat/memory?sessionId=$SESSION&message=My%20name%20is%20Tiago"
curl "http://localhost:8080/ai/chat/memory?sessionId=$SESSION&message=What%20is%20my%20name%3F"
```

With the same `sessionId`, the model **remembers** the earlier turns.

#### `/ai/chat/tools` — function calling / tool use

The model can call Java methods annotated with `@Tool`. Tools live in [`src/main/java/com/example/ai/tools/`](src/main/java/com/example/ai/tools/):

- `DateTimeTools` — `getCurrentDateTime()`, `getCurrentDate()`, `getCurrentYear()`
- `MathTools` — `add(a,b)`, `multiply(a,b)`, `percentage(value, percent)`

```bash
# Date tool — model calls getCurrentDate() and returns the real system date
curl "http://localhost:8080/ai/chat/tools?message=What%20is%20the%20current%20date%3F%20Use%20the%20tool%20to%20answer."
# → The current date is 2026-09-01.

# Math tool — model calls percentage(200, 15)
curl "http://localhost:8080/ai/chat/tools?message=What%20is%2015%20percent%20of%20200%3F%20Use%20the%20percentage%20tool."
# → 15 percent of 200 is 30.
```

> ⚠️ Tool calling requires a **tool-capable model**. Validated with `granite4.1:3b`. For best results use `qwen2.5`, `llama3.1`, or `deepseek-r1` via `ollama pull <model>` and update `application.yml`.

#### `/ai/rag` — Retrieval-Augmented Generation (RAG)

Answers are grounded in local documents under [`src/main/resources/docs/`](src/main/resources/docs/) (`spring-ai-overview.txt`, `rag-pattern.txt`, `ollama-local.txt`). Documents are embedded via `nomic-embed-text` and stored in an in-memory `SimpleVectorStore`; at query time the top-2 similar chunks are injected into the prompt.

```bash
# Grounded answer — model uses the docs as context
curl "http://localhost:8080/ai/rag?question=What%20is%20Spring%20AI%3F"
# → Spring AI is a framework that simplifies building AI-powered applications...

curl "http://localhost:8080/ai/rag?question=How%20to%20run%20models%20locally%20with%20Ollama%3F"
# → To run models locally with Ollama, you can follow these steps: ollama serve...

# Debug — see which chunks were retrieved (no LLM call)
curl "http://localhost:8080/ai/rag/debug?question=What%20is%20RAG%3F"
```

> 💡 **No external vector DB required** — `SimpleVectorStore` keeps everything in-memory. On CI without Ollama, document ingestion is skipped gracefully and `/ai/rag` falls back to a non-RAG answer.

#### `/ai/alibaba/chat` — Alibaba DashScope (Qwen) via Spring AI Alibaba

Cloud alternative that reuses the same `ChatClient` API but talks to Alibaba DashScope. When `DASHSCOPE_API_KEY` is not set, it **falls back to Ollama** so the endpoint never fails.

```bash
# Check status
curl "http://localhost:8080/ai/alibaba/status"
# → Alibaba DashScope: NOT CONFIGURED ... will fallback to Ollama
# → Alibaba DashScope: CONFIGURED — model: qwen-plus

# Chat via DashScope (requires DASHSCOPE_API_KEY)
export DASHSCOPE_API_KEY=sk-xxxx
curl "http://localhost:8080/ai/alibaba/chat?message=Hello%20from%20Qwen"
# Without key (fallback):
curl "http://localhost:8080/ai/alibaba/chat?message=Hello"
# → [Alibaba DashScope not configured] ... Ollama fallback ...
```

> 🔑 Get your DashScope API key at https://dashscope.console.aliyun.com/apiKey — free tier available. The demo validates that the Spring AI Alibaba starter is wired correctly and that the fallback works on CI without a key.

---

## 📁 Project Structure

```
src/main/
├── java/com/example/ai/
│   ├── DemoApplication.java        # Spring Boot entry point
│   ├── ChatController.java         # REST endpoints (chat, stream, memory, tools, rag)
│   ├── tools/
│   │   ├── DateTimeTools.java      # @Tool — current date/time
│   │   └── MathTools.java          # @Tool — arithmetic
│   ├── rag/
│   │   ├── RagConfig.java          # SimpleVectorStore (ollamaEmbeddingModel) + ingestion
│   │   └── RagService.java         # Manual RAG (topK=2) + debug search
│   └── alibaba/
│       ├── DashScopeManualConfig.java   # Creates DashScopeChatModel when DASHSCOPE_API_KEY set
│       └── AlibabaChatController.java   # /ai/alibaba/chat + /status (fallback to Ollama)
└── resources/
    ├── application.yml             # Ollama + DashScope + vector store config
    └── docs/
        ├── spring-ai-overview.txt  # RAG source doc
        ├── rag-pattern.txt         # RAG source doc
        └── ollama-local.txt        # RAG source doc
```

### ChatController

```java
@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ChatController(ChatClient.Builder builder) {
        this.chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();
        this.chatClient = builder.defaultSystem("You are a helpful, concise assistant.").build();
    }

    // Stateless single reply
    @GetMapping("/ai/chat")
    public String chat(@RequestParam(value = "message", defaultValue = "What is Spring AI?") String message) {
        return chatClient.prompt(message).call().content();
    }

    // Streaming response (SSE)
    @GetMapping("/ai/chat/stream")
    public Flux<String> chatStream(@RequestParam(value = "message", defaultValue = "Tell a short joke") String message) {
        return chatClient.prompt(message).stream().content();
    }

    // Multi-turn with per-conversation memory
    @GetMapping("/ai/chat/memory")
    public String chatMemory(@RequestParam("sessionId") String sessionId,
                             @RequestParam("message") String message) {
        var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(sessionId)
                .build();
        return chatClient.prompt().advisors(memoryAdvisor).user(message).call().content();
    }

    // Function calling — model can invoke Java @Tool methods
    @GetMapping("/ai/chat/tools")
    public String chatWithTools(@RequestParam(value = "message",
            defaultValue = "What is the current date and time? Also, what is 15% of 200?") String message) {
        return chatClient.prompt()
                .tools(new DateTimeTools(), new MathTools())
                .user(message)
                .call()
                .content();
    }

    // RAG — grounded answer from docs/resources/docs/
    @GetMapping("/ai/rag")
    public String rag(@RequestParam(value = "question", defaultValue = "What is Spring AI and how does RAG work?") String question) {
        return ragService.answer(question);
    }

    // RAG debug — retrieved chunks without LLM
    @GetMapping("/ai/rag/debug")
    public List<Document> ragDebug(@RequestParam(value = "question", defaultValue = "What is RAG?") String question) {
        return ragService.debugSearch(question);
    }
}
```

The **`ChatClient`** is auto-configured by the Spring AI starter — one dependency and a couple of properties is all it takes. Memory is handled by **`MessageChatMemoryAdvisor`**, which stores and injects the recent conversation history automatically per `conversationId`.

---

## ⚙️ Configuration

Defined in [`src/main/resources/application.yml`](src/main/resources/application.yml):

```yaml
spring:
  ai:
    model:
      chat: ollama        # ollama | dashscope (both have matchIfMissing=true, must pick one)
      embedding: ollama
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: granite4.1:3b
          temperature: 0.7
      embedding:
        options:
          model: nomic-embed-text
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:dummy}  # dummy allows startup without key; /ai/alibaba/* falls back
      chat:
        options:
          model: qwen-plus
          temperature: 0.7
```

| Property                          | Description                         |
|-----------------------------------|-------------------------------------|
| `spring.ai.ollama.base-url`       | Where the Ollama API server runs    |
| `spring.ai.ollama.chat.options.model` | Which local model to use        |
| `spring.ai.ollama.chat.options.temperature` | Creativity (0–1)       |
| `spring.ai.ollama.embedding.options.model` | Embedding model for RAG |
| `spring.ai.model.chat` | `ollama` (primary) — must pick one due to DashScope/Ollama both `matchIfMissing=true` |
| `spring.ai.model.embedding` | `ollama` (primary) |
| `spring.ai.dashscope.api-key` | DashScope API key (`DASHSCOPE_API_KEY`, `dummy` allows CI) |
| `spring.ai.dashscope.chat.options.model` | DashScope model (`qwen-plus`) |

---

## 🔮 Roadmap / How to extend

This is a clean base. Natural next steps (see the Spring AI Alibaba Agent Framework path):

- ✅ **Streaming** endpoint (SSE) with `ChatClient.stream()`
- ✅ **Multi-turn** chat with conversation memory
- ✅ **Function calling** / **Tool use** with `@Tool`
- ✅ **RAG** with `SimpleVectorStore` + `nomic-embed-text` (manual retrieval, grounded answers)
- ✅ **Spring AI Alibaba** — DashScope (Qwen) via `spring-ai-alibaba-starter-dashscope`, with Ollama fallback
- 🧩 **Agent + Skill** orchestration (Spring AI Alibaba)
- 🔒 OIDC / API-key auth on the endpoints

---

## 📝 Why Java + Spring AI for AI agents?

- **Enterprise-grade**: mature ecosystem, strong typing, Spring dependency injection
- **Widely used in China** via the Spring AI Alibaba framework
- **Local-first option** (via Ollama) for privacy, cost control and offline scenarios

---

## 📄 License

[MIT](LICENSE)

---

**Built to learn and demonstrate the Java + Spring AI ecosystem. Suggestions and PRs welcome!**
