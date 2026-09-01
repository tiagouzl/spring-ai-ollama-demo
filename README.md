# Spring AI + Ollama Demo

![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4-6DB33F?logo=spring&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring_AI-1.0.0-green)
![Ollama](https://img.shields.io/badge/Ollama-granite4.1:3b-000000)
[![Build](https://img.shields.io/github/actions/workflow/status/tiagouzl/spring-ai-ollama-demo/ci.yml?branch=main&label=CI)](https://github.com/tiagouzl/spring-ai-ollama-demo/actions)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A minimal, production-style **Spring Boot 3** application that integrates **Spring AI** with a **local LLM served by Ollama** — no cloud API key required, fully offline and free.

This project is a clean reference for building AI-agent / LLM applications on the **Java + Spring** ecosystem, which is widely adopted by enterprises in China (Spring AI Alibaba ecosystem).

---

## ✨ Highlights

- 📦 **Spring Boot 3.4** + **Spring AI 1.0.0 (GA)**
- 🏠 **100% local & free** — uses Ollama, no OpenAI/DashScope key needed
- ⚡ Powered by the fluent `ChatClient` API
- 🔄 **Streaming** responses (Server-Sent Events) with `ChatClient.stream()`
- 💬 **Multi-turn chat** with per-conversation memory (`MessageChatMemoryAdvisor`)
- 🛠 **Function calling / Tool use** with `@Tool` — model calls Java methods (`DateTimeTools`, `MathTools`)
- 🧹 Minimal, dependency-light setup (only `spring-boot-starter-web` + `spring-ai-starter-model-ollama`)
- 🇨🇳 Aligned with the **Spring AI Alibaba Agent Framework** learning path

---

## 🛠 Tech Stack

| Layer     | Technology                                    |
|-----------|-----------------------------------------------|
| Language  | Java 21 (LTS)                                 |
| Framework | Spring Boot 3.4.5                             |
| AI SDK    | Spring AI 1.0.0 (GA)                          |
| Model     | Ollama — `granite4.1:3b` (local, free)        |
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

### 1. Install Ollama and pull a model

```bash
# Install Ollama (Linux):
curl -fsSL https://ollama.com/install.sh | sh

# Start the service:
ollama serve

# Pull a small, local model (this project uses granite4.1:3b):
ollama pull granite4.1:3b
```

> 🎯 The demo was validated with **`granite4.1:3b`** (3.4B params, runs on CPU). You can swap it for any model in `src/main/resources/application.yml` (e.g. `qwen2.5`, `llama3.2`, `deepseek-r1`).

### 2. Run the application

```bash
mvn spring-boot:run
```

Spring Boot starts on **port 8080**.

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

---

## 📁 Project Structure

```
src/main/
├── java/com/example/ai/
│   ├── DemoApplication.java        # Spring Boot entry point
│   ├── ChatController.java         # REST endpoints using ChatClient
│   └── tools/
│       ├── DateTimeTools.java      # @Tool — current date/time
│       └── MathTools.java          # @Tool — arithmetic
└── resources/
    └── application.yml             # Ollama + model configuration
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
}
```

The **`ChatClient`** is auto-configured by the Spring AI starter — one dependency and a couple of properties is all it takes. Memory is handled by **`MessageChatMemoryAdvisor`**, which stores and injects the recent conversation history automatically per `conversationId`.

---

## ⚙️ Configuration

Defined in [`src/main/resources/application.yml`](src/main/resources/application.yml):

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: granite4.1:3b
          temperature: 0.7
```

| Property                          | Description                         |
|-----------------------------------|-------------------------------------|
| `spring.ai.ollama.base-url`       | Where the Ollama API server runs    |
| `spring.ai.ollama.chat.options.model` | Which local model to use        |
| `spring.ai.ollama.chat.options.temperature` | Creativity (0–1)       |

---

## 🔮 Roadmap / How to extend

This is a clean base. Natural next steps (see the Spring AI Alibaba Agent Framework path):

- ✅ **Streaming** endpoint (SSE) with `ChatClient.stream()`
- ✅ **Multi-turn** chat with conversation memory
- ✅ **Function calling** / **Tool use** with `@Tool`
- 🔍 **RAG** with a vector store
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
