# Design: OIDC/JWT resource server com identidade do principal

> Data: 25/09/2026 — v4, após três reviews do utilizador (v2: 6 bloqueadores + 6 lacunas + 3 limitações; v3: ordem de cadeias, filtro de actuator, validador de issuer no modo CI, namespace com `azp` ausente, valores exactos dos 429; v4: cenário operacional único — compose completo, issuer na rede docker, contradição `localhost` resolvida).
> Classificação: **arquitectural** (novo subsistema; altera o contrato de identidade partilhado).

## 1. Objectivo e âmbito

Introduzir autenticação OIDC/JWT real (a dívida explícita do `README.md:460`)
com **Spring Security**, usando o JWT para identificar o **principal** que
faz o pedido.

**Correctivo de âmbito (bloqueador 1):** o fluxo deste ciclo é
`client_credentials` — o `sub` é a conta de serviço do client, **não uma
pessoa**. O objectivo é por isso *"identidade do principal autenticado
(cliente/serviço, ou utilizador num fluxo futuro)"*. Login de utilizador com
`authorization_code` + PKCE está fora de escopo e é um sinal de reavaliação
documentado (§8).

Decisões fixadas com o utilizador:

| Pergunta | Decisão |
|---|---|
| Objectivo | Identidade do principal — `sub`/`azp` do JWT tornam-se o namespace de rate-limit, cache e conversas |
| Emissão/validação | **Keycloak local** (docker-compose) via issuer-uri (discovery/JWKS reais) |
| Fluxo | **Bearer-only** — `Authorization: Bearer <JWT>`; sem UI de login na app |
| Papéis | **Só identidade** — sem authorization/roles neste ciclo |
| API key | Modos mutuamente exclusivos: sem OIDC → comportamento actual; com OIDC → `/ai/**` e métricas exigem JWT; API key fica para actuator em modo default/dev |
| Actuator em modo OIDC | `/actuator/metrics` e `/actuator/prometheus` exigem JWT; `/actuator/health` continua público |
| Audience | Validação obrigatória de `aud`/`azp` contra allow-list configurada + `sub` não-vazio |

## 2. Requisitos

- **R1**: em modo OIDC, `/ai/**` sem JWT válido → **401** + `WWW-Authenticate: Bearer`.
  **Excepção: `OPTIONS` (preflight CORS) não exige token** — senão o preflight
  morre com 401 (o interceptor actual já isenta OPTIONS; a cadeia deve fazer o mesmo).
- **R2**: em modo OIDC, JWT válido autentica; namespace de `ClientIdentity` =
  `fingerprint("jwt:" + issuer + ":" + azp + ":" + sub)` (inclui `azp` para
  que contas de serviço de clients distintos não colidam).
- **R3**: modo default **mantém zero mudança de comportamento**, mas com uma
  ressalva técnica: adicionar o starter activa a auto-configuração de
  segurança do Spring Boot, por isso o repo define **explicitamente** as
  cadeias (§3) — fallback `permitAll` cobre tudo o que não é OIDC, sem CSRF
  e stateless, reproduzindo exactamente o comportamento actual. Os 86 testes
  actuais passam sem alterações e nenhum arranque requer rede.
- **R4**: perfil prod falha ao arrancar se não houver **nem** OIDC
  (enabled + issuer-uri) **nem** API key (`ProdAuthGuard` estendido).
- **R5**: rate-limit continua activo para pedidos JWT, bucket por
  **namespace completo** (`issuer` + `azp` + `sub`, ver R2) + endpoint —
  não apenas `sub`. **Sem promessa de quota distribuída** — o fail-open
  multi-instância do Redis permanece uma limitação explícita (§8).
- **R6**: em modo OIDC, `/actuator/metrics` e `/actuator/prometheus`
  exigem JWT (2ª cadeia, §3); `/actuator/health` público; o
  **registo** do `ActuatorApiKeyFilter` passa a ser condicional a
  `app.oidc.enabled=false` (inerte no modo OIDC — não chega desativá-lo em
  runtime: se `APP_API_KEY` existir, o filtro exigiria key *em adição* ao
  JWT). Coberto por teste: modo OIDC **com** `APP_API_KEY` definida.
- **R7**: validação de token vai além do issuer: `aud` ou `azp` tem de
  intersectar `app.oidc.allowed-audiences` (obrigatório e não-vazio quando
  o modo está activo) e `sub` tem de existir e ser não-vazio.
- **R8**: `CorsConfig` passa a permitir **DELETE** (necessário a
  `DELETE /ai/chat/memory/{sessionId}` em origens configuradas).
- **R9**: os dois cenários de **429 são distinguíveis** pelo campo
  `error` do `ApiError` (que é o único campo de código — `ApiError.java:5`,
  não existe `code`): **`error=rate_limit_exceeded`** (rate-limit) vs
  **`error=llm_bulkhead_full`** (bulkhead LLM).

## 3. Arquitectura

- **Dependência única nova**: `spring-boot-starter-oauth2-resource-server`.
- **Propriedades e mapeamento (bloqueador 3)** — no `application.yml` base:

  ```yaml
  app:
    oidc:
      enabled: false                       # comutador do modo
      issuer-uri: ${OIDC_ISSUER_URI:}      # vazio por omissão
      allowed-audiences: ${OIDC_ALLOWED_AUDIENCES:}
      public-key-location: ""              # só testes CI (decoder local)
  ```

  `OIDC_ISSUER_URI` **não** é binding relajado da propriedade Spring
  `spring.security.oauth2.resourceserver.jwt.issuer-uri` — o mapeamento é
  explícito acima e a **condição de activação do modo é
  `app.oidc.enabled=true`** (`@ConditionalOnProperty`), nunca a presença
  implícita de env vars.
- **`JwtDecoder` próprio** (`@Bean` condicionado a `app.oidc.enabled=true`),
  com **ramos mutuamente exclusivos**:
  - `public-key-location` não-vazio → `NimbusJwtDecoder` com a chave pública
    (**CI, sem rede**). Neste ramo `issuer-uri` continua obrigatório, mas é
    usado **apenas como valor esperado do claim `iss`** na validação — sem
    discovery. Um token com `iss` diferente do `issuer-uri` → 401 (prova que
    o modo CI valida issuer tal como o modo real);
  - sem `public-key-location`, `issuer-uri` não-vazio →
    `JwtDecoders.fromIssuerLocation` (discovery real — E2E/prod);
  - nenhum dos dois → falha de arranque com mensagem clara.
  Em ambos os ramos: `JwtValidators.createDefaultWithIssuer(issuer-uri)` +
  **validador de `aud`/`azp`** (allow-list) + asserção de `sub` não-vazio.
- **Três cadeias de segurança explícitas (bloqueador 2), com `@Order` fixo
  (ajuste v3.1 — sem ordem, o fallback sem matcher captura `/ai/**` e
  actuator antes das cadeias OIDC):**
  1. **`@Order(1)` — OIDC `/ai/**`** — `@ConditionalOnProperty(app.oidc.enabled)`,
     `securityMatcher("/ai/**")`, stateless, CSRF off,
     `oauth2ResourceServer(jwt)` com o decoder acima; `OPTIONS` permitido
     antes da exigência de bearer;
  2. **`@Order(2)` — OIDC actuator** — condicionada ao modo, `securityMatcher`
     só em `/actuator/metrics` e `/actuator/prometheus`, JWT obrigatório;
     `health` não é abrangido;
  3. **`@Order(3)` — fallback** — sempre presente, sem matcher, `permitAll`,
     **CSRF desactivado**, stateless. Apanha apenas o que as cadeias 1–2 não
     agarraram — em modo default é a única cadeia activa e reproduz
     exactamente o comportamento actual (R3).
  Swagger e restantes rotas fora de `/ai/**` ficam no fallback (inalterados).
  Com o `@Order` fixo, a cadeia OIDC ganha sempre a precedência sobre o
  fallback quando o modo está activo.

## 4. Integração de identidade

- **Corrector de tipo (bloqueador 4):** o `SecurityContext` em resource
  server contém **`JwtAuthenticationToken`**, não `Jwt` directamente.
  Detecção: `authentication instanceof JwtAuthenticationToken` (ou
  `authentication.getPrincipal() instanceof Jwt` — o principal *é* o `Jwt`).
  O teste unitário usa a mesma forma.
- **`ClientIdentity.namespaceFor(request)`** — único ponto de alteração:
  - `JwtAuthenticationToken` presente →
    `fingerprint("jwt:" + issuer + ":" + azp + ":" + sub)` (R2);
    **`azp` é opcional na JWT spec** — quando ausente, o segmento é o
    literal `-` (`…:issuer:-:sub`), garantindo namespace determinístico;
  - caso contrário → actual (fingerprint da chave ou IP).
  - O `issuer` entra no fingerprint para que trocar de Keycloak/realmo não colida identidades antigas.
- **Consumidores sem mudança**: `RateLimitInterceptor`, `SimpleChatController`,
  `MemoryChatController` (incl. `DELETE /ai/chat/memory/{sessionId}`).
- **`ApiKeyAuthInterceptor`**: em modo OIDC, autenticação `JwtAuthenticationToken`
  → salta a exigência de `X-API-Key` e aplica só rate-limit; modo default → actual.
- **Herança automática**: rate-limit, cache semântico e conversas por principal —
  sem código novo nesses módulos.

## 5. Erros

| Situação | Resposta |
|---|---|
| `/ai/**` sem/ com token inválido (modo OIDC) | 401 + `WWW-Authenticate: Bearer` |
| Token expirado / assinada por outra chave / `aud`-`azp` fora da allow-list / `sub` vazio | 401 + challenge |
| **`OPTIONS` preflight (modo OIDC)** | **permitido (sem 401)** |
| `/actuator/metrics|/prometheus` sem JWT (modo OIDC) | 401 |
| Erros da app (validação, 405, 409, 503) | inalterados, via `ApiErrorWriter`/`GlobalExceptionHandler` |
| Rate-limit atingido (com ou sem JWT) | 429, `error=rate_limit_exceeded` |
| Bulkhead LLM cheio | 429, `error=llm_bulkhead_full` (R9 — `ApiError` só tem o campo `error`) |

## 6. Plano de testes (TDD)

1. **Unitário `ClientIdentity`**: com `JwtAuthenticationToken` (construída
   sobre um `Jwt` de teste) → namespace `jwt:issuer:azp:sub` estável e
   distinto do IP/chave; **`azp` ausente → namespace com segmento `-`**;
   sem token → actual. (Forma de detecção igual à §4.)
2. **`OidcModeSecurityTest`** (`app.oidc.enabled=true` + `public-key-location`
   + `issuer-uri` como iss esperado, sem rede):
   - sem token → 401; com token válido → 200 e namespace correcto;
   - token expirado → 401; assinado por outra chave → 401;
   - **`iss` ≠ `issuer-uri` → 401** (validação de issuer no modo CI, sem discovery);
   - `aud`/`azp` fora da allow-list → 401; `sub` vazio → 401;
   - `OPTIONS` preflight → **não** 401;
   - **isolamento de DELETE**: sub A não apaga a conversa de sub B
     (jwt-A cria memória; jwt-B faz DELETE → 204 mas memória de A intacta;
     jwt-A DELETE → memória some);
   - actuator: `/actuator/metrics` e `/actuator/prometheus` sem token → 401,
     com token → 200; `/actuator/health` → 200 sempre;
   - **modo OIDC com `APP_API_KEY` definida**: métricas acessíveis com JWT
     apenas (filtro de key inerte — key nem exigida nem suficiente); em
     modo default o mesmo cenário continua a exigir a key (filtro activo);
   - rate-limit: dois subs distintos têm buckets distintos (namespace
     completo issuer+azp+sub).
3. **`ProdAuthGuard`**: prod sem OIDC e sem key → falha; com issuer → arranca.
4. **Regressão / modo default**: 86 testes existentes verdes sem OIDC;
   `CorsConfigTest` passa a incluir DELETE no allow-methods; testes dos dois
   429 afirmam `error=rate_limit_exceeded` vs `error=llm_bulkhead_full` (R9).
5. **E2E Keycloak com discovery real**: perfil opt-in fora da suite padrão
   (mesma filosofia `pom.xml:193-203`) — **teste black-box contra a stack
   do compose** (`docker compose up`), não um app separado no host: pede o
   token à porta publicada (`localhost:8081`), afirma
   `iss == http://keycloak:8080/realms/spring-ai-demo` (== `OIDC_ISSUER_URI`
   do contentor da app) e chama `127.0.0.1:8080/ai/...` com Bearer → 200.
   Cobre discovery real e a coerência hostname/issuer da §7.
   (O decoder local de CI permanece separado — `public-key-location` sem rede.)

## 7. Operacional e documentação

- `docker-compose`: serviços **Keycloak** e **a app** já existentes —
  **`docker compose up` é o cenário único e canónico** (a app corre dentro
  do contentor, `docker-compose.yml:38-73`). O Keycloak ganha `healthcheck`
  e a app ganha `depends_on: condition: service_healthy`. Realm importado de
  `infra/keycloak/realm-export.json` (client `spring-ai-demo`,
  `client_credentials`, **mapper de audience** para `aud=spring-ai-demo`
  coerente com `OIDC_ALLOWED_AUDIENCES`).
- **Hostname/issuer — cenário escolhido (compose completo), coerência por construção:**
  - O 8080 do host está ocupado pela app (`127.0.0.1:8080:8080`); o
    Keycloak publica **`127.0.0.1:8081:8080`**.
  - Opção de hostname da tag pinada do Keycloak (tag fixa, nunca `latest`;
    opção exacta = a documentada por essa tag, ex. `KC_HOSTNAME`) força a
    URL frontend a **`http://keycloak:8080`** — o nome do serviço na rede
    docker —, gerando sempre `iss = http://keycloak:8080/realms/spring-ai-demo`.
  - O contentor da app recebe `APP_OIDC_ENABLED=true` e
    `OIDC_ISSUER_URI=http://keycloak:8080/realms/spring-ai-demo`
    (+ `OIDC_ALLOWED_AUDIENCES=spring-ai-demo`): discovery, `iss` esperado
    e validação coincidem dentro da rede docker.
  - **curl no host** usa as portas publicadas: token em
    `http://localhost:8081/realms/spring-ai-demo/protocol/openid-connect/token`,
    API em `http://127.0.0.1:8080/ai/...`. O `iss` do token é o URL interno —
    o cliente não valida `iss`, só o app valida, e é exactamente esse valor.
  - Correcção de contradição operacional: `OIDC_ISSUER_URI=http://localhost:8080/...`
    **não** serve para a app em contentor (localhost = próprio contentor);
    não existe cenário "app no host" neste design.
- README: fluxo curl — token via `protocol/openid-connect/token`
  (`grant_type=client_credentials`) → chamada `Authorization: Bearer` a `/ai/chat`.
- Docs: `ANALISE.md` §33; relatorio (OIDC riscado); module card
  `docs/superpowers/memory/security.md` actualizado (modo OIDC, cadeias, invariantes).

## 8. Fora de escopo e limitações explícitas

**Fora de escopo:**
- Authorization/papéis; login de utilizador (`authorization_code` + PKCE —
  sinal de reavaliação quando o demo precisar de pessoas e não de serviços);
  `oauth2Login`; UI de gestão de tokens; revogação/rotação activa;
  protecção de swagger; multi-tenancy.

**Limitações que permanecem e ficam documentadas:**
- Rate-limit Redis continua **fail-open em multi-instância** (R5 não promete
  quota distribuída).
- **PromptGuard continua heurístico** e mantém os bypasses já aceites para demo.
- O objectivo final continua **identidade/isolamento**, não authorization/roles.

## 9. Critérios de aceitação

- `./mvnw verify` verde sem Keycloak e sem rede (modo default intacto; starter adicionado mas cadeias explícitas reproduzem o comportamento actual).
- Modo OIDC activado por `app.oidc.enabled=true` + decoder local: R1–R9 verificados por testes (inclui validação de `iss` no modo CI e filtro de actuator inerte quando `APP_API_KEY` coexiste).
- E2E opt-in com Keycloak real valida discovery/issuer de ponta a ponta e afirma `iss == OIDC_ISSUER_URI`.
- `docker compose up` (ollama + keycloak + app) → healthcheck do Keycloak
  verde → fluxo README do token com curl copiado/colado (token `localhost:8081`,
  API `127.0.0.1:8080`); tag e opção de hostname do Keycloak fixadas no
  compose e cobertas pela asserção `iss` do E2E opt-in.
- `git diff --check` limpo; sem segredos reais (chave RSA de teste só para testes).
