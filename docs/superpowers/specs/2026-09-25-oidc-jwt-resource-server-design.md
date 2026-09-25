# Design: OIDC/JWT resource server com identidade de utilizador

> Data: 25/09/2026 — estado: aprovado em chat (secções §1–§3), pendente de revisão do utilizador.
> Classificação: **arquitectural** (novo subsistema de segurança; altera o contrato de identidade partilhado).

## 1. Objectivo e âmbito

Introduzir autenticação OIDC/JWT real numa app que hoje só tem API key de demo,
usando **Spring Security** (a dívida explícita do `README.md:460`).

Decisões fixadas com o utilizador:

| Pergunta | Decisão |
|---|---|
| Objectivo | Identidade de utilizador real — o `sub` do JWT torna-se o namespace de rate-limit, cache e conversas |
| Emissão/validação | **Keycloak local** (docker-compose) via `issuer-uri` (discovery/JWKS reais) |
| Fluxo | **Bearer-only** — `Authorization: Bearer <JWT>`; sem UI de login na app |
| Papéis | **Só identidade** — sem authorization/roles neste ciclo |
| Coexistência com API key | Modos mutuamente exclusivos: sem issuer → comportamento actual; com issuer → `/ai/**` exige JWT (API key deixa de autenticar `/ai/**`, fica para actuator e dev) |

## 2. Requisitos

- **R1**: com `OIDC_ISSUER_URI` definida, todo o pedido a `/ai/**` sem JWT válido responde **401** com `WWW-Authenticate: Bearer`.
- **R2**: com `OIDC_ISSUER_URI` definida, JWT válido autentica; o namespace de `ClientIdentity` passa a `fingerprint("jwt:" + issuer + ":" + sub)`.
- **R3**: sem `OIDC_ISSUER_URI` (perfil default/testes), **zero mudança** — os 86 testes actuais passam sem alterações e nenhum arranque requer rede.
- **R4**: em perfil prod, o arranque falha se não houver **nem** `OIDC_ISSUER_URI` **nem** API key (`ProdAuthGuard` estendido).
- **R5**: rate-limit continua activo para pedidos JWT (429 mantém-se); bucket por `sub`+endpoint.
- **R6**: actuator, swagger e restantes rotas fora do eixo `/ai/**` mantêm o tratamento actual.

## 3. Arquitectura

- **Dependência única nova**: `spring-boot-starter-oauth2-resource-server` no `pom.xml`.
- **Modos**:
  - *Default* — nenhuma propriedade `spring.security.oauth2.resourceserver.jwt.*` no `application.yml` base → não existe `JwtDecoder` nem cadeia nova; app idêntica à actual.
  - *OIDC* — `OIDC_ISSUER_URI` via env var (nunca no YAML base, porque o `issuer-uri` obriga a discovery em arranque).
- **`SecurityFilterChain`** dedicada:
  - `securityMatcher` apenas `/ai/**`;
  - stateless, CSRF desactivado, sessão `STATELESS`;
  - `oauth2ResourceServer(jwt)` com o decoder derivado de `issuer-uri`;
  - pedidos sem/ com token inválido → 401 do framework (não passam pelo `ApiErrorWriter`).
- Swagger (`/v3/api-docs`, `/swagger-ui`) fica **fora** do matcher (inalterado neste ciclo). Actuator mantém `ActuatorApiKeyFilter`.

## 4. Integração de identidade

- **`ClientIdentity.namespaceFor(request)`** — único ponto de alteração:
  - `Authentication` é `Jwt` → `fingerprint("jwt:" + issuer + ":" + sub)`;
  - caso contrário → actual (fingerprint da chave ou IP).
  - O `issuer` entra no fingerprint para que trocar de Keycloak/realmo não colida identidades antigas.
- **Consumidores sem mudança**: `RateLimitInterceptor`, `SimpleChatController`, `MemoryChatController` (incluindo `DELETE /ai/chat/memory/{sessionId}`) continuam a chamar `namespaceFor`.
- **`ApiKeyAuthInterceptor`**: em modo OIDC, quando a autenticação do `SecurityContext` é `Jwt`, salta a exigência de `X-API-Key` e aplica só o rate-limit; em modo default, comportamento actual.
- **Herança automática**: rate-limit por utilizador, cache semântico por utilizador, conversas/sessões por utilizador — sem código novo nesses módulos.

## 5. Erros

| Situação | Resposta |
|---|---|
| `/ai/**` sem token (modo OIDC) | 401 + `WWW-Authenticate: Bearer` |
| Token expirado / assinado por outra chave | 401 + challenge |
| Erros da app (validação, 405, 409, 429, 503) | inalterados, via `ApiErrorWriter`/`GlobalExceptionHandler` |
| Rate-limit atingido com JWT válido | 429 (interceptor corre depois da cadeia do Spring Security) |

## 6. Plano de testes (TDD)

1. **Unitário `ClientIdentity`**: com `Jwt` no `SecurityContext` → namespace `jwt:issuer:sub` estável, distinto do namespace IP/chave; sem Jwt → comportamento actual.
2. **`OidcModeSecurityTest`** (`@SpringBootTest`, propriedade `spring.security.oauth2.resourceserver.jwt.public-key-location=classpath:…` — sem rede):
   - sem token → 401;
   - token assinado com a chave privada de teste → 200 e namespace correcto;
   - token expirado → 401;
   - token assinado por outra chave → 401;
   - rate-limit por sub observável (pedidos de dois subs distintos têm buckets distintos).
3. **`ProdAuthGuard`**: prod sem issuer nem key → falha ao arrancar; com issuer → arranca.
4. **Regressão**: os 86 testes existentes correm em modo default (sem decoder).

## 7. Operacional e documentação

- `docker-compose`: serviço **Keycloak** + import de realm em `infra/keycloak/realm-export.json` (realm, client `spring-ai-demo`, grant `client_credentials`).
- README: fluxo curl completo — obter token no endpoint `protocol/openid-connect/token` e chamada `Authorization: Bearer` a `/ai/chat`.
- Docs: `ANALISE.md` §33; relatorio (linha OIDC riscada); module card `docs/superpowers/memory/security.md` actualizado com o modo OIDC e invariantes novos.
- E2E contra Keycloak real: perfil opt-in fora da suite padrão (mesma filosofia do `pom.xml:193-203`).

## 8. Fora de escopo

- Authorization/papéis; login de browser (`oauth2Login`); UI de gestão de tokens; revogação/rotação activa (configuração Keycloak recomendada, não código); protecção de swagger; multi-tenancy.

## 9. Critérios de aceitação

- `./mvnw verify` verde sem Keycloak e sem rede (modo default intacto).
- Com issuer/public-key configurado: R1–R6 verificados por testes.
- Keycloak sobe por `docker compose`, README funciona com curl tal como copiado.
- `git diff --check` limpo; sem segredos reais no repositório (chave de teste RSA só para testes).
