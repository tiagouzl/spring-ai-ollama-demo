# Spring AI + Ollama Demo

A minimal, production-style **Spring Boot 3** application that integrates **Spring AI** with a **local LLM served by Ollama** — no cloud API key required, fully offline and free.

This project is a clean reference for building AI-agent / LLM applications on the **Java + Spring** ecosystem, which is widely adopted by enterprises in China (Spring AI Alibaba ecosystem).

---

## ✨ Highlights

- 📦 **Spring Boot 3.4** + **Spring AI 1.0.0 (GA)**
- 🏠 **100% local & free** — uses Ollama, no OpenAI/DashScope key needed
- ⚡ Simple `ChatClient` API with a single REST endpoint
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

### 3. Call the chat endpoint

```bash
curl "http://localhost:8080/ai/chat?message=Hello%20from%20Spring%20AI"
```

**Response:** the model's local reply.

---

## 📁 Project Structure

```
src/main/
├── java/com/example/ai/
│   ├── DemoApplication.java     # Spring Boot entry point
│   └── ChatController.java      # REST endpoint using ChatClient
└── resources/
    └── application.yml          # Ollama + model configuration
```

### ChatController

```java
@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/ai/chat")
    public String chat(@RequestParam(value = "message", defaultValue = "O que é Spring AI?") String message) {
        return chatClient.prompt(message).call().content();
    }
}
```

The **`ChatClient`** is auto-configured by the Spring AI starter — one dependency and a couple of properties is all it takes.

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

- ➕ **Streaming** endpoint (SSE) with `ChatClient.stream()`
- 💬 **Multi-turn** chat with conversation memory
- 🛠 **Function calling** / **Tool use**
- 🔍 **RAG** with a vector store
- 🧩 **Agent + Skill** orchestration (Spring AI Alibaba)

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
