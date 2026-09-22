# 📊 Análise do Projeto: `spring-ai-ollama-demo`

> Relatório de análise técnica gerado em 01/09/2026 — commit `1a49fad` (branch `main`). Estendido em 04/09/2026 até o commit `23a84da` com as rodadas 9-11 (segurança, RAG, Docker/OpenAPI/output estruturado, cache semântico e BOM Alibaba).

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
│   ├── ChatMemoryConfig         → ChatMemory persistente (JdbcChatMemoryRepository/HSQLDB, janela de 20 msgs)
│   ├── PrimaryChatClientConfig  → resolve conflito de 2 ChatModels (@Primary Ollama)
│   ├── OllamaClientConfig       → OllamaApi com timeouts HTTP explícitos (10s/120s/180s)
│   ├── CorsConfig               → CORS global para /ai/** (app.cors.allowed-origins)
│   └── GlobalExceptionHandler   → @RestControllerAdvice com ApiError estruturado
├── chat/
│   ├── SimpleChatController       → GET/POST /ai/chat (prompt guard + cache semântico)
│   ├── StreamChatController       → GET/POST /ai/chat/stream (SSE, Flux<String>)
│   ├── MemoryChatController       → /ai/chat/memory + /ai/session (memória por sessionId)
│   ├── ToolsChatController        → /ai/chat/tools (function calling)
│   └── StructuredChatController   → /ai/chat/structured (output tipado via ChatClient.entity)
├── cache/
│   └── SemanticCache           → cache semântico opt-in e fail-safe (similaridade por cosseno, TTL)
├── tools/ (DateTimeTools, MathTools) → @Tool anotados
├── rag/
│   ├── RagConfig            → SimpleVectorStore + TokenTextSplitter + persistência em disco
│   ├── RagService           → retrieval manual (topK=2, threshold de similaridade) + DTO debug
│   └── RagController        → /ai/rag e /ai/rag/debug (GET/POST, 503 sanitizado)
├── alibaba/
│   ├── DashScopeEnabledCondition → fonte única de verdade (api-key não-vazia)
│   ├── DashScopeManualConfig     → cria DashScopeChatModel só quando a key está setada
│   └── AlibabaChatController     → /ai/alibaba/chat e /ai/alibaba/status (falha real → 502, não mascarada)
├── security/
│   ├── ApiKeyAuthInterceptor  → auth opt-in X-API-Key (401)
│   ├── ActuatorApiKeyFilter   → mesmo guard para /actuator/metrics + /actuator/prometheus (filtro Servlet, 401)
│   ├── RateLimitInterceptor   → rate limit token-bucket por cliente (429)
│   ├── PromptGuard            → blocklist de prompt injection (400)
│   ├── ApiSecurityConfig      → registra os interceptors em /ai/**
│   └── ApiErrorWriter         → escreve ApiError JSON a partir dos interceptors/filtro
└── api/ (records: ChatRequest, MemoryChatRequest, RagRequest, TopicSentiment, RagDebugDocument, ApiError)
```

**Endpoints:** `/ai/chat`, `/ai/chat/stream`, `/ai/chat/memory`, `/ai/chat/tools`, `/ai/chat/structured`, `/ai/rag`, `/ai/rag/debug`, `/ai/alibaba/chat`, `/ai/alibaba/status` — chat/RAG/structured com GET + POST; `/ai/session` e `/ai/alibaba/status` são GET-only. OpenAPI em `/v3/api-docs` + UI em `/swagger-ui.html`.

## 3. Pontos Fortes ✅

1. **Resolução elegante de conflito de beans** — O ponto mais sofisticado do projeto. Quando `DASHSCOPE_API_KEY` está setada, existem 2 `ChatModel`s e o `ChatClientAutoConfiguration` falharia com `NoUniqueBeanDefinitionException`. O `PrimaryChatClientConfig` resolve isso com um `@Primary ChatClient.Builder` amarrado ao `ollamaChatModel`, fazendo o auto-config recuar (`@ConditionalOnMissingBean`). Bem documentado em Javadoc.

2. **Condição única de verdade** (`DashScopeEnabledCondition`) — centraliza a lógica "DashScope está habilitado?" (api-key não-vazia, sem sentinela) e o controller delega para `isEnabled(apiKey)`. O fallback do controller é progressivo: sem key → mensagem instrutiva + resposta Ollama; bean ausente → 503; erro de API → 502 (sem mascarar em 200).

3. **Robustez para CI/local sem Ollama** — `RagConfig` faz a ingestão dentro de try/catch para não derrubar o boot; `RagController` retorna 503 com dica (`ollama pull nomic-embed-text`).

4. **Cobertura de testes direcionada** — 2 testes de integração (`@SpringBootTest` RANDOM_PORT) que travam os comportamentos críticos: fallback com key dummy e coexistência dos 2 modelos com mocks. **Build validado: 5 testes, 0 falhas, BUILD SUCCESS (22,6s).** *(Valores da análise original — a suíte atual tem 27 testes, ver §9-11.)*

5. **Qualidade de API** — records imutáveis, `GlobalExceptionHandler` mapeando `IllegalArgument`→400, `Timeout`→504, genérico→500; streaming com `Flux<String>`/SSE; README completo com badges, roadmap e tabelas de configuração.

## 4. Pontos de Atenção / Melhorias ⚠️

> **Nota:** itens históricos da análise original — todos foram endereçados nas rodadas 7-11 (ver seções correspondentes).

1. **Fragilidade do "dummy" por convenção** — O valor `"dummy"` está replicado em 3 lugares (`application.yml`, `DashScopeEnabledCondition.DUMMY`, comentários). Se alguém setar `DASHSCOPE_API_KEY=dummy` de verdade, o sistema silenciosamente usa fallback. Alternativa: ausência da variável (sem default dummy) + `@ConditionalOnProperty` ou perfil Spring.

2. **`System.err.println` no `RagConfig`** — inconsistente com o resto do projeto que usa SLF4J (`LoggerFactory`). Deveria ser `log.warn(...)`.

3. **Sem segmentação (chunking) nos documentos RAG** — os 3 arquivos `.txt` são ingeridos como documentos inteiros com `topK=2`. Textos longos podem exceder a janela de contexto do `granite4.1:3b` (3B params). Um `TokenTextSplitter` melhoraria a qualidade da recuperação.

4. **Vulnerabilidades leves:**
   - `GlobalExceptionHandler` retorna `ex.getMessage()` bruto ao cliente (pode vazar stack/internals);
   - Não há validação (`@Valid`/`@NotBlank`) nos requests;
   - Endpoints sem autenticação/rate-limit (o próprio README lista isso no roadmap);
   - `ChatMemory` e `SimpleVectorStore` eram **in-memory** (reinício perdia tudo) — desde a rodada 8 ambos persistem em `./data/` (HSQLDB + JSON).

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

---

## 9. Endurecimento de Segurança e Qualidade de API (04/09/2026)

Rodada de correções baseada na revisão cruzada de duas análises externas do repositório. As análises originais estavam desatualizadas (descreviam o commit `1a49fad`); os itens ainda válidos foram implementados nesta rodada:

1. **Threshold de similaridade no RAG** — `RagService` agora aplica `SearchRequest.similarityThreshold()` com `app.rag.similarity-threshold` (default `0.5`, cosseno). Abaixo do threshold, a pergunta é respondida **sem** recuperação (`[Note: no relevant context found...]`) em vez de forçar contexto irrelevante no prompt (que causava respostas alucinadas).

2. **`/ai/rag/debug` sem vazamento de API interna** — novo record público `RagDebugDocument(id, text, score, metadata)`; o endpoint não expõe mais `org.springframework.ai.document.Document` (se o Spring AI mudar essa classe, a API pública não quebra).

3. **Erro 503 do RAG sanitizado** — `RagController` não expõe mais `e.getClass().getSimpleName()`/`e.getMessage()`; loga o detalhe no servidor e retorna mensagem fixa com dica acionável.

4. **CORS global** — novo `CorsConfig` (`WebMvcConfigurer`) libera `/ai/**` para origens configuráveis via `app.cors.allowed-origins` (default `*`; estreitar em produção).

5. **Deduplicação do DashScope** — `DashScopeEnabledCondition.isEnabled(apiKey)` virou fonte única de verdade; `matches()` e `AlibabaChatController` delegam a ela (a regra não pode mais divergir entre condição e controller).

6. **Autenticação por API key (opt-in)** — `security/ApiKeyAuthInterceptor`: quando `app.auth.api-key` (ou `APP_API_KEY`) está definida, todo `/ai/**` exige header `X-API-Key` → 401 estruturado. Vazia = endpoints abertos (padrão demo).

7. **Rate limiting** — `security/RateLimitInterceptor`: janela fixa por cliente (header `X-API-Key` se presente, senão IP), `app.rate-limit.requests-per-minute` (default 60, `<=0` desabilita) → 429 estruturado. Em memória (por instância); para multi-instância usar Redis/Bucket4j. Autenticação roda **antes** do rate limit (401 não consome cota).

8. **Guarda contra prompt injection** — `security/PromptGuard`: blocklist case-insensitive configurável (`app.prompt-guard.blocked-phrases`) rejeitada com 400 antes do modelo, aplicada em todos os pontos de entrada (chat simples, stream, memory, tools, RAG, Alibaba). Documentado como heurística — não substitui uma camada real de guardrails.

9. **Erro do DashScope não é mais mascarado em HTTP 200** — falha real na chamada à API → **502** (sem fallback silencioso; detalhe só no log, APM enxerga a falha); key setada mas bean ausente → 503. O fallback de "não configurado" (sem key) permanece 200 — comportamento documentado e testado.

**Decisão de design:** interceptors leves sem novas dependências (sem Spring Security/Bucket4j) para manter o demo autocontido; o caminho de produção (Spring Security/OIDC, rate limit compartilhado) está documentado no README.

**Validação:** suíte completa `./mvnw test` → **21 testes, 0 falhas** (novos: `ApiKeyAuthTest` 3, `RateLimitTest` 1, `RequestValidationTest` +1, `AlibabaEnabledTest` +1, `RagEndpointTest` 3); E2E real `E2E_OLLAMA=true ./mvnw test -Dtest=OllamaE2EIT -DfailIfNoTests=false` → **3 testes, 0 falhas** contra Ollama real em container.

**Não implementado (roadmap):** OIDC/JWT via Spring Security completo, guardrails dedicados (ex. NeMo), rate limit compartilhado entre instâncias, gestão do `spring-ai-alibaba` via BOM, output estruturado e cache semântico — permanecem como evolução futura documentada no README.

---

## 10. Roadmap: Docker Compose, OpenAPI e Output Estruturado (04/09/2026)

1. **Output estruturado** — novo endpoint `/ai/chat/structured` (GET/POST): a resposta do LLM é parseada no record tipado `TopicSentiment(topic, sentiment, rating)` via `ChatClient.call().entity(Class)` (JSON Schema gerado automaticamente pelo Spring AI). É o padrão para APIs tipadas em cima de LLMs.

2. **OpenAPI/Swagger** — adicionado `springdoc-openapi-starter-webmvc-ui` **2.8.14** (pinned: 2.8.15+ quebra o startup no Boot 3.4.x com "Invalid mapping pattern detected: /swagger-ui/**/*swagger-initializer.js" — springdoc#3210): spec em `/v3/api-docs` e UI interativa em `/swagger-ui.html`, gerados automaticamente dos controllers. Fora do escopo do guard `/ai/**`, então docs ficam públicas.

3. **Docker Compose** — novo `Dockerfile` multi-stage (build Maven → runtime Temurin 21 JRE, jar final enxuto) e `docker-compose.yml` com três serviços: `ollama` (healthcheck), `ollama-pull` (baixa `granite4.1:3b` + `nomic-embed-text` uma vez, `service_completed_successfully` como gate) e `app` (aponta para `http://ollama:11434` via `SPRING_AI_OLLAMA_BASEURL`, monta `./data:/app/data` para persistir memória/vector store, `APP_API_KEY` opcional). `.dockerignore` exclui `target/`, `.git/`, `data/`.

**Validação:** suíte completa `./mvnw test` → **25 testes, 0 falhas** (novos: `StructuredChatTest` 2, `OpenApiDocsTest` 2); `docker compose config` validado.

**Não implementado (roadmap):** agentes Spring AI Alibaba (Agent+Skill), OIDC/JWT via Spring Security completo, guardrails dedicados, rate limit compartilhado e cache semântico — documentados no README.

---

## 11. Cache Semântico e Gestão do Alibaba via BOM (04/09/2026)

1. **Cache semântico opt-in** — novo `cache/SemanticCache` (@Component, `app.cache.semantic.enabled=false` por padrão): antes de chamar o modelo, a mensagem é embedada e comparada por cosseno (`similarity-threshold` 0.95, texto idêntico ≈ 1.0) com respostas anteriores; hit → resposta em cache (economia de tokens/latência). TTL (`ttl-seconds` 3600) e teto (`max-entries` 1000) com evicção lazy. **Fail-safe por design**: qualquer erro de embedding só faz bypass do cache (nunca quebra o chat). Integrado ao `/ai/chat` (GET/POST). Em memória, por instância — para multi-instância usar Redis.

2. **Alibaba gerenciado por BOM próprio** — `pom.xml` agora importa `com.alibaba.cloud.ai:spring-ai-alibaba-bom:1.0.0.4` no `dependencyManagement` (mesma versão da propriedade `spring-ai-alibaba.version`); o starter `spring-ai-alibaba-starter-dashscope` perdeu a versão fixada e passa a ser gerenciado como o resto das dependências.

**Validação:** suíte completa `./mvnw test` → **27 testes, 0 falhas** (novos: `SemanticCacheTest` 2, com `@DirtiesContext` por teste para isolar o cache compartilhado e `@MockBean VectorStore` para não gravar um vector store fake no `./data`).

**Não implementado (roadmap):** agentes Spring AI Alibaba (Agent+Skill), OIDC/JWT via Spring Security completo, guardrails dedicados e rate limit compartilhado entre instâncias — documentados no README.



---

## 12. Correção do Cache Semântico e Verificação do Threshold RAG (12/09/2026)

Revisão de código do estado atual (commit pós-rodada 11). Dois pontos foram investigados; um era bug real e foi corrigido, o outro era suspeita e foi **descartado** após verificação.

1. **Bug real no `SemanticCache` — chave textual em vez de id opaco.** O cache usava o **texto da mensagem** como chave do `ConcurrentHashMap`, mas a busca é por similaridade de cosseno sobre os embeddings. Consequência: perguntas semanticamente equivalentes com frases diferentes ("Qual a capital da França?" vs. "Me diz a capital francesa") ocupavam **slots distintos** e nunca compartilhavam um hit — o cache crescia com duplicatas semânticas até o teto `max-entries`. Correção: chave passa a ser um **id sequencial** (`AtomicLong`), coerente com a semântica do cache; a comparação por cosseno permanece no `lookup`.

2. **Bug secundário descoberto durante a correção — tie-break no `lookup`.** Com dois embeddings idênticos (cosseno 1.0 para ambos), o loop usava `if (similarity > best)` estrito: a **primeira** entrada definia `best = 1.0` e nenhuma outra com `1.0` conseguia superá-la. Resultado: em empate, a resposta mais **antiga** vencia e ficava congelada (o consumidor nunca reescrevia, pois o `lookup` acertava). Correção: empates agora são desempatados pelo `createdAt` mais recente — a resposta mais atual vence.

3. **Evicção de expirados na leitura.** Entradas com TTL vencido eram apenas *puladas* no `lookup`, removidas só quando `store` cruzava `max-entries`. Agora são removidas a cada `lookup` (`removeIf`), evitando retenção de respostas obsoletas em instâncias ociosas.

4. **Semântica do `similarity-threshold` do RAG — verificada, suspeita descartada.** Havia a hipótese de que o score do `SimpleVectorStore` fosse *distância* (e não *similaridade*), o que inverteria o significado do `app.rag.similarity-threshold: 0.5`. Verificação feita em duas frentes:
   - **Bytecode** (jar `spring-ai-vector-store-1.0.1`): `doSimilaritySearch` calcula `EmbeddingMath.cosineSimilarity(query, doc)` e filtra por `score >= similarityThreshold` (`lambda$doSimilaritySearch$1`), ordenando por score decrescente. Portanto: **cosseno** (faixa [-1, 1], 1.0 = idêntico) e threshold = **similaridade mínima**. O comentário do `application.yml` está correto.
   - **Empírico** (`nomic-embed-text` via API do Ollama, docs reais): perguntas relevantes → cosseno **0.70–0.77**; irrelevantes → **0.30–0.39**. O threshold 0.5 separa limpo, com folga em ambos os lados. Nada a corrigir.
   - *Nota para o futuro:* outros vector stores do Spring AI (ex. pgvector) usam **distância** e threshold "≤ distância máxima" — daí a confusão possível. No `SimpleVectorStore` a semântica é a inversa.

**Validação:** suíte completa `./mvnw test` → **31 testes, 0 falhas** (novo: `SemanticCacheUnitTest` 4, unitário sem Spring, cobrindo chave opaca, tie-break e ausência de dependência do texto). Os 27 testes anteriores seguem verdes.

---

## 13. Revisão Geral e Endurecimento de CORS/Auth (12/09/2026)

Exploração de todo o código-fonte (`com.example.ai.*`) + suíte de testes após o commit `a81cba3`
(extração do system prompt compartilhado em `ChatPrompts.DEFAULT`). Estado de saúde confirmado:
`./mvnw test` → **31 testes, 0 falhas, BUILD SUCCESS**. Um bug latente e dois pontos de
endurecimento foram corrigidos; um descompasso de documentação foi sincronizado.

1. **Bug real — preflight CORS bloqueado pelos interceptors de segurança.** `ApiSecurityConfig`
   registra `ApiKeyAuthInterceptor` e `RateLimitInterceptor` em `/ai/**` **sem** exclusão por método.
   Quando `app.auth.api-key` está configurado, o *preflight* `OPTIONS` do navegador (enviado **sem**
   o header `X-API-Key`) caía no `ApiKeyAuthInterceptor` e recebia **401**, quebrando o acesso via
   browser — justamente o caminho de produção que o README promove. Estava mascarado porque a chave
   default é vazia. Correção: ambos os interceptors agora retornam `true` para `OPTIONS` (o preflight
   não carrega o modelo nem consome quota). Testes novos `optionsPreflightIsNotBlockedByAuth`
   (ApiKeyAuthTest) e `optionsPreflightIsNotRateLimited` (RateLimitTest) travam o comportamento.

2. **Comparação constant-time da API key.** `ApiKeyAuthInterceptor` usava `String.equals` para
   comparar a chave configurada com o header — vulnerável a ataque de temporização. Trocado por
   `MessageDigest.isEqual(byte[], byte[])` (laço independente de conteúdo) via `constantTimeEquals(...)`.
   O `null` do header é tratado antes. Sem impacto nos testes existentes.

3. **Mapa de rate-limit com evicção de janelas antigas.** `RateLimitInterceptor.windows`
   (`ConcurrentHashMap<String, Window>`) crescia sem limite — uma entrada por cliente distinto que
   já chamou a API, retida para sempre. Agora, a cada `preHandle`, entradas de minutos anteriores
   (`windowStartMillis < windowStart` da requisição atual) são removidas via `removeIf` (seguro em
   `ConcurrentHashMap`). Não altera a semântica da janela fixa nem o limite.

4. **Sincronização de documentação (doc drift).** O commit `a81cba3` extraiu o system prompt em
   `ChatPrompts.DEFAULT`, mas o README ainda mostrava o literal inline `"You are a helpful, concise
   assistant."` (snippet do `SimpleChatController` e diagrama de arquitetura). Ambos atualizados para
   referenciar a constante. `ANALISE.md` ganha esta seção 13.

**Validação:** suíte completa `./mvnw test` → **33 testes, 0 falhas** (novos: `ApiKeyAuthTest`
+1, `RateLimitTest` +1). O `OPTIONS` é ignorado pelos guardas mesmo com `app.auth.api-key` e
`app.rate-limit.requests-per-minute` baixos; GET/POST continuam exigindo a chave e respeitando o limite.

---

## 14. Refinamentos aceitos: CORS credentials, Rate-limit (token-bucket) e versionamento do Vector Store (12/09/2026)

Implementação das três sugestões de evolução levantadas na revisão da seção 13:

1. **CORS — `allowCredentials` explícito e seguro.** `CorsConfig` agora inspeciona se o
   `allowed-origins` é wildcard (`*`): com `*`, credenciais ficam **desligadas** (a spec CORS
   proíbe a combinação `*` + credentials, que seria rejeitada de qualquer forma); com origens
   **concretas**, credenciais ficam **ligadas** (caso o operador queira requisições com cookie/
   auth header). Antes, o `allowCredentials` não era definido (default `false`), o que funcionava
   mas silenciava a intenção e era uma armadilha caso alguém "ligasse credenciais" sem notar o
   conflito com `*`. Testes novos `CorsConfigTest` (wildcard → sem header de credentials) e
   `CorsSpecificOriginTest` (origem fixa → `Access-Control-Allow-Credentials: true`) travam o comportamento.

2. **Rate limit — fixed-window → token-bucket em memória.** `RateLimitInterceptor` trocou a janela
   fixa alinhada ao relógio por um **token bucket** por cliente: capacidade = `requests-per-minute`
   tokens, reabastecidos continuamente a `N/60` por segundo, 1 token consumido por requisição. Isso
   elimina a falha clássica da janela fixa (cliente enviava a cota toda antes da borda e outra vez
   depois — até 2× o limite em rajada curta). A evicção de clientes ociosos (10 min) foi mantida
   para não deixar o mapa crescer sem limite. O caminho multi-instância (Redis/Bucket4j) **permanece
   documentado no README**, sem acoplar dependência ao demo autocontido. `RateLimitTest` segue verde
   (3ª requisição no mesmo minuto → 429).

3. **Vector store versionado pelo modelo de embedder.** `RagConfig` agora carimba o store persistido
   (`./data/vector-store.json`) com o nome do modelo de embedding num sidecar `vector-store.json.embedder`
   (lido de `spring.ai.ollama.embedding.options.model`). Se o modelo mudar — ou o cache for de antes
   dessa versão (sem meta) — o `load` é ignorado e a re-ingestão ocorre, evitando responder contra
   vetores obsoletos/incompatíveis com o embedder atual.

**Validação:** suíte completa `./mvnw test` → **35 testes, 0 falhas** (novos: `CorsConfigTest` 1,
`CorsSpecificOriginTest` 1). `RateLimitTest`, `ApiKeyAuthTest` e os demais 31 testes seguem verdes.

---

## 15. Proteção do Actuator, PromptGuard sem falso-positivo e OLLAMA_HOST no compose (13/09/2026)

Revisão externa + correção no commit `0175d76` (após a seção 14). Três itens aplicados, um
documentado como aceito:

1. **Actuator sensível atrás da API key — via filtro Servlet, não interceptor.**
   `ApiSecurityConfig` protegia só `/ai/**`; `/actuator/metrics` e `/actuator/prometheus`
   seguiam públicos mesmo com `APP_API_KEY` configurada. A primeira tentativa (adicionar os
   paths ao `addInterceptors`) **não funcionou e o teste provou**: `/actuator/metrics` seguia
   200 sem key. Diagnóstico confirmado com teste de sondagem — os endpoints do Actuator são
   servidos pelo `WebMvcEndpointHandlerMapping` próprio do Boot, que nunca recebe os
   interceptors registrados via `WebMvcConfigurer` (o chain dele contém só
   `SkipPathExtensionContentNegotiation`). Correção: `security/ActuatorApiKeyFilter`
   (`OncePerRequestFilter`, `@Component`), escopado em `shouldNotFilter` a
   `/actuator/metrics*` e `/actuator/prometheus`; `/actuator/health` e `/actuator/info`
   seguem públicos para probes de LB/k8s. Filtro roda no nível de Servlet, antes de qualquer
   roteamento — cobre por construção, independente de handler mapping. Reaproveita
   `ApiKeyAuthInterceptor.constantTimeEquals` (visibilidade relaxada de `private` para
   package-private; implementação única, sem duplicação). Achado lateral: o 401 que
   `/actuator/prometheus` retornava no contexto de teste era falso-positivo de fallthrough
   (o endpoint Prometheus nem estava registrado sem `@AutoConfigureObservability`; em
   produção, com export habilitado, o interceptor também não o alcançaria). Testes novos no
   `ApiKeyAuthTest`: 401 sem key em metrics/prometheus + health 200 aberta, e 200 com key
   válida (o filtro deixa o tráfego legítimo passar).

2. **PromptGuard — `"you are now"` removido do default.** A entrada gerava falso-positivo em
   frases legítimas (*"you are now free to configure the timeout"*). Removido de
   `PromptGuard.DEFAULT_BLOCKED_PHRASES` **e** de `application.yml` (que sobrescreve o
   default — mudar só o Java não teria efeito em runtime), com o motivo documentado nos dois
   lugares; reativável via `app.prompt-guard.blocked-phrases`. Teste existente
   (`promptInjectionPatternIsRejectedWith400`, via "Ignore previous instructions") segue verde.

3. **`docker-compose.yml` — `OLLAMA_HOST` no `ollama-pull`.** O bug apontado na primeira
   revisão: o container sobrescreve o entrypoint (sem `ollama serve`), então `ollama pull`
   tentava falar com um daemon local inexistente em vez do container `ollama`. Agora com
   `OLLAMA_HOST: http://ollama:11434`; validado com `docker compose config`.

4. **Aceito e documentado (não corrigido): `sessionId` como bearer token sem dono.**
   Comentário `ponytail:` no `MemoryChatController` — auth por API key é global, não amarra
   sessões ao chamador. Aceitável para demo (UUIDs não-adivinháveis); vincular ao chamador
   se o projeto um dia guardar dado sensível. Ficam também para próxima passada: rate limit
   no actuator e docs públicas (`/v3/api-docs`, `/swagger-ui.html` — decisão já registrada
   na seção 10).

**Validação:** suíte completa `./mvnw test` → **37 testes, 0 falhas** (novos: +2 no
`ApiKeyAuthTest`); `docker compose config` OK. README sincronizado (árvore `security/`,
seção de auth, tabela `app.auth.api-key`, seção Observability).

---

## 16. Slice alta-prioridade: perfis dev/prod, fail-fast auth, Retry-After, limites, probes, non-root

Primeira execução do plano de endurecimento para produção (sem novas features de IA):

1. **Perfis** — `application-dev.yml` (Ollama localhost, CORS `*`, auth opt-in) e
   `application-prod.yml` (`OLLAMA_BASE_URL` via env, `app.auth.required=true`,
   `app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS}` sem default = falha explícita
   se ausente, bloco Postgres comentado como template).
2. **`ProdAuthGuard`** — `@PostConstruct` aborta o boot quando `required=true` sem
   key. Travado por `ProdAuthGuardTest` (3 testes, `ApplicationContextRunner`).
3. **`Retry-After: 60`** no 429 (janela de refill) — asserção fundida no
   `RateLimitTest` existente (teste separado dividiria o bucket em memória e
   quebraria por ordem de execução).
4. **Limites de tamanho** — `@Size(max=4000)` em message/question, `@Size(max=128)`
   em sessionId (nativo Bean Validation, sem dependência nova).
5. **Probes** — `probes.enabled` + liveness/readiness em `application.yml`;
   sub-paths de health seguem públicos (o filtro só guarda metrics/prometheus).
6. **Docker** — runtime como `appuser` (uid 1000, compatível com `./data` do host),
   `HEALTHCHECK` em `/actuator/health` (curl instalado), healthcheck do compose.

**Validação:** `./mvnw test` → **44 testes, 0 falhas**; `docker compose config` OK.

---

## 17. RAG com fontes + métricas da aplicação (sem deps novas)

Segundo slice do plano (observabilidade + RAG, só código):

1. **`/ai/rag` retorna fontes** — novo record `api/RagAnswer(answer, sources)`;
   `RagService.answerWithSources` devolve a resposta + o metadata `source`
   (distinto) dos chunks usados — vazio quando sem contexto relevante. O 503
   sanitizado virou `RagAnswer` com mensagem fixa (JSON consistente). O `source`
   já era carimbado na ingestão (`RagConfig.toDocument`), faltava só expô-lo.
2. **Três situações já diferenciadas** (confirmado, sem mudança): sem contexto →
   resposta + nota; contexto sem resposta → system prompt manda dizer "don't
   know"; falha técnica → 503 com dica acionável.
3. **Meters custom** (Micrometer já no classpath): `app.rag.questions/empty`,
   `app.cache.semantic.lookup{result=hit|miss|bypass}`,
   `app.security.ratelimit.rejected`, `app.security.promptguard.rejected` —
   nomes fixos, sem labels de prompt/usuário (sem risco de cardinalidade).
4. **Testes:** `MetricsTest` (contexto isolado, contagens exatas),
   `ragAnswerIncludesSources`, hit/miss/bypass no `SemanticCacheUnitTest`
   (helper passou a injetar `SimpleMeterRegistry`), counter do 429 no
   `RateLimitTest`, `app_cache_semantic_lookup_total` no scrape do
   `ObservabilityTest`.

**Validação:** `./mvnw test` → **48 testes, 0 falhas**.

---

## 18. Postgres + pgvector opt-in (perfil prod de verdade)

Terceiro slice (persistência externa, sem quebrar dev/test):

1. **Deps** — `postgresql` (runtime) + `spring-ai-starter-vector-store-pgvector`;
   auto-config do starter **excluída** em `application.yml` (criaria um segundo
   bean `VectorStore` e falharia no HSQLDB).
2. **`app.rag.store: simple|pgvector`** — `RagConfig` monta `PgVectorStore`
   manualmente (HNSW + cosseno, `initializeSchema=true`, ingestão só com tabela
   vazia = restarts idempotentes); `chunkedDocs` extraído e compartilhado.
   Chat memory no Postgres é automática (mesmo starter JDBC, schema por plataforma).
3. **Prod/compose** — `application-prod.yml` com datasource sem defaults (fail
   fast) + `store: pgvector`; compose ganha serviço `db` (`pgvector:pg17`,
   volume `pgdata`, healthcheck `pg_isready`).
4. **Testes** — `RagDefaultStoreTest` trava o default (`SimpleVectorStore`);
   `PgVectorE2EIT` (opt-in `E2E_PG`, mesmo gate duplo do Ollama) valida o wiring
   real: bean `PgVectorStore`, tabela `vector_store` e `spring_ai_chat_memory`.

**Validação:** `./mvnw test` → **49 testes, 0 falhas**; E2E real
`E2E_PG=true ./mvnw test -Dtest=PgVectorE2EIT` → **2 testes, 0 falhas** (41s,
tabela criada, ingestão pulada sem Ollama como desenhado).

---

## 19. Rate limit distribuído via Redis (fail-open)

Quarto slice (escala horizontal do guard):

1. **Backend opt-in** — `app.rate-limit.store: memory|redis`
   (`spring-boot-starter-data-redis`, Lettuce lazy); modo `redis` executa o
   mesmo token bucket num script Lua atômico (retorna `{allowed, retry}` —
   `Retry-After` passa a ser exato, não mais o teto de 60s).
2. **Fail-open** — qualquer exceção do Redis cai no bucket em memória (disponi-
   bilidade > rigor), com warn no log; travado por `RedisFallbackTest`
   (store=redis contra porta fechada → 200).
3. **Achado real** — o starter registra um health indicator do Redis que
   derrubava `/actuator/health` (503) sem servidor. Desligado de propósito
   (`management.health.redis.enabled=false`): Redis aqui não é dependência de
   serving, e outage dele jamais pode virar probe.
4. **Prod/compose** — `store: ${RATE_LIMIT_STORE:redis}` + `spring.data.redis`
   sem host default (fail fast); serviço `redis` (`redis:7-alpine`, `redisdata`).
5. **Testes** — `RedisRateLimitE2EIT` (opt-in `E2E_REDIS`, container real:
   2×200 + 429 com Retry-After numérico ≥ 1).

**Validação:** `./mvnw test` → **52 testes, 0 falhas**; E2E real
`E2E_REDIS=true ./mvnw test -Dtest=RedisRateLimitE2EIT` → **1 teste, 0 falhas**.

---

## 20. Rotação de API keys (sem breaking)

Quinto slice (opera a auth sem trocar de sistema):

1. **`app.auth.api-key` vira lista** — comma-separated, blanks ignorados;
   match-any constant-time em `ApiKeyAuthInterceptor.parseKeys/matchesAny`,
   reutilizado pelo `ActuatorApiKeyFilter` e pelo `ProdAuthGuard` (que exige
   ≥ 1 chave quando `required=true`). Chave única sem vírgula funciona igual.
2. **Rotação = adicionar, migrar, remover** — sem endpoint novo, sem formato
   novo; rate-limit por cliente continua por valor do header (buckets por key).
3. **Testes** — `ApiKeyRotationTest` (contexto isolado `old-key,new-key`):
   ambas aceitas em `/ai/**` e `/actuator/metrics`, desconhecida/ausente 401.

**Validação:** `./mvnw test` → **56 testes, 0 falhas**.

---

## 21. Cache semântico distribuído via Redis (fail-open)

Sexto slice (último sem decisão de produto pendente):

1. **Backend opt-in** — `app.cache.semantic.store: memory|redis`; modo `redis`
   grava hashes `semcache:{id}` (embedding base64 + resposta + timestamp, TTL
   na chave, id via `INCR`) e o lookup escolhe o mais próximo por cosseno com
   o mesmo tie-break de atualidade do modo memória.
2. **Fail-safe preservado** — qualquer exceção (Redis fora, entrada corrompida,
   dimensão divergente) faz bypass: o modelo é chamado normalmente. Entradas
   ilegíveis são deletadas; teto `max-entries` limpa como no modo memória.
3. **Testes** — helper unitário atualizado (modo `memory`, template nulo);
   `SemanticCacheRedisE2EIT` (opt-in `E2E_REDIS`, container real: 2ª pergunta
   igual servida do Redis, modelo chamado 1×).

**Validação:** `./mvnw test` → **56 testes, 0 falhas**; E2E reais
`E2E_REDIS=true ./mvnw test -Dtest='SemanticCacheRedisE2EIT,RedisRateLimitE2EIT'` →
**2 testes, 0 falhas**.

---

## 22. Decisões de escopo (fim do plano)

Itens restantes avaliados e **adiados por decisão explícita** (custo > benefício
sem consumidor externo ou história real):

1. **ProblemDetail (RFC 9457)** — `ApiError` atual já é estruturado e nunca vaza
   internas; migrar quebraria todos os endpoints/testes sem ganho funcional.
2. **Versionamento `/api/v1`** — sem consumidor externo, versionar é custo puro.
3. **Multi-tenant** — exige identidade de tenant (header? key? JWT?); o
   isolamento por `sessionId` já cobre o escopo demo.
4. **Reranking** — exige modelo (DashScope cloud, excluído de propósito, ou
   local fraco sem ganho claro).
5. **Eval de respostas** — exige critério/golden set que só existe com uso real.

Plano de endurecimento **concluído**: perfis dev/prod, fail-fast auth, CORS
restrito, Retry-After, limites, probes, imagem non-root, Postgres + pgvector,
Redis (rate-limit + cache), rotação de keys, fontes no RAG, meters custom —
tudo commitado, testado (56 testes) e com E2E reais verdes (Ollama, PG, Redis).

---

## 23. CI em tiers + cobertura travada + scan (pós-plano)

1. **JaCoCo** — `prepare-agent` + `report` + `check` no `verify`; piso medido
   no dia (LINE 67%, BRANCH 54%), trava em 60%/45% com margem (sobe o mínimo
   quando a cobertura real subir).
2. **Tiers** — `unit` (8 testes, sem Spring/Docker:
   `-Dtest='*UnitTest,ProdAuthGuardTest'`) → `integration` (`verify` completo);
   E2E excluídos do default também via surefire (`**/e2e/**`, além do gate por
   env que já existia).
3. **Scan sem segredo** — Trivy FS (HIGH/CRITICAL → SARIF no code scanning) +
   Dependabot (maven/docker/actions, semanal). OWASP Dependency-Check ficou de
   fora de propósito: exige NVD API key; entra quando houver o secret.
4. **Limpeza de contagem** — os "56 testes" anteriores incluíam XMLs stale de
   runs E2E isolados; suite real: **50 testes, 0 falhas, 0 pulos**.

**Validação:** `./mvnw -B verify` → **BUILD SUCCESS** (50 testes + gate JaCoCo
verde); tier unit → 8/8 verde.
