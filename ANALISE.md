# 📊 Análise do Projeto: `spring-ai-ollama-demo`

> Relatório de análise técnica gerado em 01/09/2026 — commit `1a49fad` (branch `main`).

## 1. Visão Geral

Projeto de demonstração **Spring Boot 3.4.5 + Spring AI 1.0.1** que integra LLMs rodando localmente via **Ollama** (`granite4.1:3b`), com opção opcional de nuvem via **Spring AI Alibaba DashScope** (`qwen-plus`). É um repositório de referência/estudo para construir aplicações de IA no ecossistema Java/Spring, cobrindo os principais padrões: chat, streaming, memória, function calling e RAG.

**Stack:**

| Camada | Tecnologia |
|---|---|
| Linguagem | Java 21 (LTS) |
| Framework | Spring Boot 3.4.5 |
| AI SDK | Spring AI 1.0.1 + Spring AI Alibaba 1.0.0.4 |
| Modelo local | Ollama `granite4.1:3b` + `nomic-embed-text` |
| Modelo cloud (opcional) | DashScope `qwen-plus` |
| Vector Store | `SimpleVectorStore` (in-memory) |
| CI | GitHub Actions (`mvn verify`, JDK 21 Temurin) |

## 2. Estrutura e Arquitetura

```
com.example.ai
├── DemoApplication          → @SpringBootApplication padrão
├── config/
│   ├── ChatMemoryConfig     → MessageWindowChatMemory (janela de 20 msgs)
│   ├── PrimaryChatClientConfig → resolve conflito de 2 ChatModels (@Primary Ollama)
│   └── GlobalExceptionHandler  → @RestControllerAdvice com ApiError estruturado
├── chat/
│   ├── SimpleChatController  → GET/POST /ai/chat (síncrono)
│   ├── StreamChatController  → GET/POST /ai/chat/stream (SSE, Flux<String>)
│   ├── MemoryChatController  → /ai/chat/memory + /ai/session (memória por sessionId)
│   └── ToolsChatController   → /ai/chat/tools (function calling)
├── tools/ (DateTimeTools, MathTools) → @Tool anotados
├── rag/
│   ├── RagConfig            → SimpleVectorStore + ingestão de 3 docs locais
│   ├── RagService           → retrieval manual (topK=2) + prompt grounded
│   └── RagController        → /ai/rag e /ai/rag/debug (GET/POST)
├── alibaba/
│   ├── DashScopeEnabledCondition → Condition: key real ≠ "dummy"
│   ├── DashScopeManualConfig     → cria DashScopeChatModel condicionalmente
│   └── AlibabaChatController     → /ai/alibaba/chat e /ai/alibaba/status com fallback
└── api/ (records: ChatRequest, MemoryChatRequest, RagRequest, ApiError)
```

**Endpoints:** `/ai/chat`, `/ai/chat/stream`, `/ai/chat/memory`, `/ai/chat/tools`, `/ai/rag`, `/ai/rag/debug`, `/ai/alibaba/chat`, `/ai/alibaba/status` — todos com GET + POST.

## 3. Pontos Fortes ✅

1. **Resolução elegante de conflito de beans** — O ponto mais sofisticado do projeto. Quando `DASHSCOPE_API_KEY` está setada, existem 2 `ChatModel`s e o `ChatClientAutoConfiguration` falharia com `NoUniqueBeanDefinitionException`. O `PrimaryChatClientConfig` resolve isso com um `@Primary ChatClient.Builder` amarrado ao `ollamaChatModel`, fazendo o auto-config recuar (`@ConditionalOnMissingBean`). Bem documentado em Javadoc.

2. **Condição única de verdade** (`DashScopeEnabledCondition`) — centraliza a lógica "DashScope está habilitado?" e é sincronizada com o dummy `"dummy"` no `application.yml`. O fallback do controller é progressivo: sem key → mensagem instrutiva + resposta Ollama; bean ausente → fallback; erro de API → fallback.

3. **Robustez para CI/local sem Ollama** — `RagConfig` faz a ingestão dentro de try/catch para não derrubar o boot; `RagController` retorna 503 com dica (`ollama pull nomic-embed-text`).

4. **Cobertura de testes direcionada** — 2 testes de integração (`@SpringBootTest` RANDOM_PORT) que travam os comportamentos críticos: fallback com key dummy e coexistência dos 2 modelos com mocks. **Build validado: 5 testes, 0 falhas, BUILD SUCCESS (22,6s).**

5. **Qualidade de API** — records imutáveis, `GlobalExceptionHandler` mapeando `IllegalArgument`→400, `Timeout`→504, genérico→500; streaming com `Flux<String>`/SSE; README completo com badges, roadmap e tabelas de configuração.

## 4. Pontos de Atenção / Melhorias ⚠️

1. **Fragilidade do "dummy" por convenção** — O valor `"dummy"` está replicado em 3 lugares (`application.yml`, `DashScopeEnabledCondition.DUMMY`, comentários). Se alguém setar `DASHSCOPE_API_KEY=dummy` de verdade, o sistema silenciosamente usa fallback. Alternativa: ausência da variável (sem default dummy) + `@ConditionalOnProperty` ou perfil Spring.

2. **`System.err.println` no `RagConfig`** — inconsistente com o resto do projeto que usa SLF4J (`LoggerFactory`). Deveria ser `log.warn(...)`.

3. **Sem segmentação (chunking) nos documentos RAG** — os 3 arquivos `.txt` são ingeridos como documentos inteiros com `topK=2`. Textos longos podem exceder a janela de contexto do `granite4.1:3b` (3B params). Um `TokenTextSplitter` melhoraria a qualidade da recuperação.

4. **Vulnerabilidades leves:**
   - `GlobalExceptionHandler` retorna `ex.getMessage()` bruto ao cliente (pode vazar stack/internals);
   - Não há validação (`@Valid`/`@NotBlank`) nos requests;
   - Endpoints sem autenticação/rate-limit (o próprio README lista isso no roadmap);
   - `ChatMemory` e `SimpleVectorStore` são **in-memory** — reinício perde tudo (aceitável para demo, documentar).

5. **Dependência não gerenciada** — `spring-ai-alibaba-starter-dashscope` tem versão fixada fora do BOM do Spring AI; vale checar compatibilidade 1.0.0.4 × Spring AI 1.0.1 (aparentemente ok, pois os testes passam).

6. **Timeout não configurado** — `GlobalExceptionHandler` trata `TimeoutException`, mas não há timeout explícito no RestClient/WebClient do Ollama; chamadas podem pendurar indefinidamente.

7. **Sem `mvnw` wrapper** — CI e README dependem de Maven instalado; adicionar o wrapper aumentaria reprodutibilidade.

## 5. Validação Executada

- ✅ `mvn test` (Java 21.0.12.1, Maven via SDKMAN): **BUILD SUCCESS — 5 testes, 0 falhas, 0 erros** (`AlibabaFallbackTest`: 3 testes; `AlibabaEnabledTest`: 2 testes).
- Os logs mostraram ingestão RAG funcionando de fato (chamadas ao `EmbeddingModel` bem-sucedidas), indicando Ollama ativo na máquina.

## 6. Conclusão

Projeto **bem estruturado e didático**, com separação limpa de responsabilidades (`chat` / `rag` / `alibaba` / `tools` / `api`), resolução correta dos problemas clássicos de coexistência de múltiplos provedores de LLM no Spring AI, e testes de regressão que travam justamente os cenários críticos. Está pronto para servir de base de referência; os próximos passos naturais (já previstos no README) seriam chunking no RAG, persistência de memória/vector store, autenticação nos endpoints e uso de SLF4J em todos os pontos.

---

## 7. Melhorias Implementadas (01/09/2026)

Itens da seção 4 que foram implementados e validados:

1. **"dummy" removido** — `application.yml` agora usa `api-key: ${DASHSCOPE_API_KEY:}` (vazio quando ausente). A `DashScopeEnabledCondition` e o `AlibabaChatController` consideram DashScope habilitado apenas quando a chave é não-vazia — sem valor sentinela. `AlibabaFallbackTest` atualizado (sem property dummy).
   *Descoberta durante a implementação:* era exatamente por isso que o sentinel existia — a `DashScopeAgentAutoConfiguration` (e as demais auto-configs do starter Alibaba) instancia clientes de API que **exigem chave não-vazia no startup**. Solução: `spring.autoconfigure.exclude` para as 8 auto-configurações do starter em `application.yml` (o demo não as usa — o `DashScopeChatModel` é criado manualmente em `DashScopeManualConfig` e chat/embedding estão fixados no Ollama via `spring.ai.model.*`).

2. **SLF4J no `RagConfig`** — `System.err.println` substituído por `Logger` (log.warn no skip de ingestão; log.info com contagem de chunks).

3. **Chunking no RAG** — `TokenTextSplitter` (spring-ai-commons) divide os 3 documentos em chunks antes da ingestão, melhorando recuperação e respeitando a janela de contexto do modelo local.

4. **Validação de requests** — adicionado `spring-boot-starter-validation`; `@NotBlank` em `ChatRequest`/`MemoryChatRequest`/`RagRequest` e `@Valid` em todos os POSTs (defaults manuais removidos dos controllers; GETs mantêm defaults). Novo handler de `MethodArgumentNotValidException` → 400 com detalhes dos campos. Novo teste: `RequestValidationTest` (4 testes).

5. **Sanitização de erros** — `GlobalExceptionHandler` não expõe mais `ex.getMessage()` ao cliente (400/500/504 retornam mensagens genéricas; detalhes vão para o log do servidor).

6. **Timeouts HTTP no Ollama** — novo `OllamaClientConfig` define um bean `OllamaApi` (a auto-config recua via `@ConditionalOnMissingBean`): connect 10s, read 120s no caminho síncrono (`JdkClientHttpRequestFactory`) e read 180s no streaming (`JdkClientHttpConnector`, sem dependências extras).

7. **Maven Wrapper** — `mvnw`/`mvnw.cmd`/`.mvn/wrapper` adicionados; CI alterado para `./mvnw -B verify`.

**Não implementado (roadmap):** autenticação/rate-limit nos endpoints e gestão do `spring-ai-alibaba` via BOM — permanecem como evolução futura, já documentadas no README.

---

## 8. Melhorias de Médio Prazo Implementadas (01/09/2026)

1. **Observabilidade (Actuator + Prometheus)** — adicionados `spring-boot-starter-actuator` e `micrometer-registry-prometheus`; `management.endpoints.web.exposure.include: health,info,metrics,prometheus`. Endpoint `/actuator/prometheus` exporta métricas JVM/HTTP e as observações de chat do Spring AI automaticamente.
   *Descobertas:* (a) o endpoint só produz `text/plain` — com `Accept: application/json` a requisição falha; adicionado handler de `HttpMediaTypeNotAcceptableException` → 406 no `GlobalExceptionHandler`; (b) **o Boot desabilita a exportação de métricas por padrão em contextos de teste** (`DisableObservabilityContextCustomizer`) — o `ObservabilityTest` exige `@AutoConfigureObservability`.

2. **ChatMemory persistente** — substituído o `MessageWindowChatMemory` in-memory por `MessageWindowChatMemory` + `JdbcChatMemoryRepository` (starter `spring-ai-starter-model-chat-memory-repository-jdbc`), com HSQLDB em modo arquivo (`jdbc:hsqldb:file:./data/chat-memory`) sobrevivendo a restarts.
   *Descobertas:* o starter não tem schema-H2 (plataformas: PostgreSQL, MySQL, MariaDB, SQL Server, HSQLDB) — por isso HSQLDB; o script usa `CREATE TABLE` sem `IF NOT EXISTS`, mas o initializer configura `continueOnError=true`, tornando `initialize-schema: always` seguro em restarts. Teste `PersistentChatMemoryTest` valida as mensagens gravadas na tabela `SPRING_AI_CHAT_MEMORY`.

3. **Persistência do VectorStore** — `SimpleVectorStore.save()/load()` para `./data/vector-store.json` (configurável via `app.rag.persistence-path`): embeddings calculados uma única vez e reutilizados nos boots seguintes. Para produção, o caminho pgvector já documentado no README permanece (exigiria Postgres para validação local).

4. **E2E opcional com Ollama real (Testcontainers)** — `OllamaE2EIT` sobe `ollama/ollama` via `OllamaContainer`, faz pull de `qwen2:0.5b` e exercita a stack HTTP sem mocks. Dupla proteção para não rodar no build padrão/CI: `@Testcontainers(disabledWithoutDocker = true)` + opt-in por `E2E_OLLAMA=true`. Comando: `E2E_OLLAMA=true ./mvnw test -Dtest=OllamaE2EIT -DfailIfNoTests=false`.

**Validação:** suíte completa `./mvnw test` → 12 testes, 0 falhas; E2E real executado localmente (`OllamaE2EIT`) → 3 testes, 0 falhas, com Docker 29.1.3 (requer override `testcontainers.version=1.21.4`, pois o Boot 3.4.5 gerencia 1.20.6, cujo docker-java usa API 1.32 — rejeitada pelo Docker 29). O log do E2E confirmou a persistência do vector store: `[RAG] Loaded persisted vector store from ./data/vector-store.json (33448 bytes)`. `data/` adicionado ao `.gitignore`.

