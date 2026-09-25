# OIDC/JWT Resource Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real-identity OIDC/JWT mode (Keycloak, bearer-only, `client_credentials`) that turns the JWT `issuer+azp+sub` into the client namespace used by rate-limit, semantic cache and conversations, without changing default-mode behaviour.

**Architecture:** Spring Security resource server with three explicitly ordered filter chains (`@Order(1)` OIDC `/ai/**`, `@Order(2)` OIDC actuator metrics/prometheus, `@Order(3)` always-on `permitAll` fallback), an app-owned `JwtDecoder` bean gated by `app.oidc.enabled` validating issuer + `aud`/`azp` allow-list + non-empty `sub`, and a single identity seam in `ClientIdentity.namespaceFor`. Keycloak runs pinned in docker-compose; CI tests use a committed RSA test keypair (no network).

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, `spring-boot-starter-oauth2-resource-server`, Nimbus JOSE (already on classpath), JUnit 5 + AssertJ + `TestRestTemplate` + `@MockitoBean`, docker-compose + Keycloak (pinned tag).

**Spec:** `docs/superpowers/specs/2026-09-25-oidc-jwt-resource-server-design.md` (v4 — the plan argues from it; read it first. Requirements R1–R9 live there.)

## Global Constraints

- Never break default mode: with `app.oidc.enabled` absent/false the app must behave byte-for-byte as today (R3); the existing 86 tests must stay green at every task gate.
- `./mvnw verify` is the gate after every task: 0 failures, JaCoCo floors line ≥0.65 / branch ≥0.54, no `Rule violated`.
- `git diff --check` clean at every commit; no real secrets (the RSA keypair is a test-only fixture, documented as such; the Keycloak demo client secret is a demo credential like the existing `changeme` DB password).
- **Every Commit step requires the user's explicit authorization first (session standing rule) — pause and ask; never push without it.**
- Security-filter chain order is load-bearing: `@Order(1)` OIDC `/ai/**`, `@Order(2)` OIDC actuator, `@Order(3)` fallback. Never remove an `@Order`.
- Comment style: English, only for non-obvious intent (matches repo convention).
- Property names exactly: `app.oidc.enabled`, `app.oidc.issuer-uri`, `app.oidc.allowed-audiences`, `app.oidc.public-key-location`. Env mapping: `OIDC_ISSUER_URI`, `OIDC_ALLOWED_AUDIENCES`, `APP_OIDC_ENABLED` (relaxed binding for the boolean; the two URLs are explicit `${...}` mappings in `application.yml` — required by spec §3).

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` (modify) | add `spring-boot-starter-oauth2-resource-server` |
| `src/main/resources/application.yml` (modify) | `app.oidc.*` block under existing `app:` |
| `src/main/java/com/example/ai/security/OidcSecurityConfig.java` (create) | decoder bean + three `SecurityFilterChain` beans |
| `src/main/java/com/example/ai/security/ClientIdentity.java` (modify) | JWT branch in `namespaceFor` |
| `src/main/java/com/example/ai/security/ApiKeyAuthInterceptor.java` (modify) | skip key requirement when already JWT-authenticated |
| `src/main/java/com/example/ai/security/ActuatorApiKeyFilter.java` (modify) | `@ConditionalOnProperty` inertness in OIDC mode |
| `src/main/java/com/example/ai/security/ProdAuthGuard.java` (modify) | fail-fast accepts OIDC as alternative to API key |
| `src/main/java/com/example/ai/security/RateLimitInterceptor.java` (modify) | R9: error `rate_limit_exceeded` |
| `src/main/java/com/example/ai/config/GlobalExceptionHandler.java` (modify) | R9: error `llm_bulkhead_full` |
| `src/main/java/com/example/ai/config/CorsConfig.java` (modify) | R8: allow `DELETE` |
| `src/test/resources/oidc/test-{private,public}.pem` (create) | test-only RSA fixture |
| `src/test/java/com/example/ai/security/TestTokens.java` (create) | signed-JWT factory shared by all OIDC tests |
| `src/test/java/com/example/ai/security/OidcJwtDecoderTest.java` (create) | decoder validation unit tests |
| `src/test/java/com/example/ai/security/OidcModeSecurityTest.java` (create) | HTTP: auth required, bearer works, OPTIONS, 401 matrix |
| `src/test/java/com/example/ai/security/OidcAuthzAndRateLimitTest.java` (create) | interceptor skip, per-namespace buckets, DELETE isolation |
| `src/test/java/com/example/ai/security/OidcActuatorSecurityTest.java` + `OidcActuatorKeyCoexistenceTest.java` (create) | R6 chains + filter inertness |
| `src/test/java/com/example/ai/security/ProdAuthGuardTest.java` (modify) | OIDC alternatives |
| `src/test/java/com/example/ai/config/CorsConfigTest.java` (modify) | DELETE preflight |
| `src/test/java/com/example/ai/security/ErrorCodes429Test.java` (create) | R9 exact error fields |
| `src/test/java/com/example/ai/e2e/KeycloakE2EIT.java` (create) | opt-in black-box compose E2E |
| `docker-compose.yml` (modify) + `infra/keycloak/realm-export.json` (create) | pinned Keycloak, healthcheck, realm |
| `README.md`, `ANALISE.md`, `relatorio.md`, `docs/superpowers/memory/security.md` (modify) | docs (Task 12) |

---

### Task 1: Dependency, OIDC properties, and the validating `JwtDecoder`

**Files:**
- Modify: `pom.xml` (`<dependencies>` block, next to the other `spring-boot-starter-*` entries)
- Modify: `src/main/resources/application.yml` (insert the `oidc:` block after the existing `auth:` block, before `rate-limit:` — currently lines ~89–99)
- Create: `src/main/java/com/example/ai/security/OidcSecurityConfig.java`
- Create: `src/test/resources/oidc/test-private.pem`, `src/test/resources/oidc/test-public.pem`
- Create: `src/test/java/com/example/ai/security/TestTokens.java`
- Create: `src/test/java/com/example/ai/security/OidcJwtDecoderTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `OidcSecurityConfig.createDecoder(String issuerUri, String publicKeyLocation, String allowedAudiences, ResourceLoader)` → `JwtDecoder` (throws `IllegalStateException` on misconfiguration); bean `JwtDecoder` when `app.oidc.enabled=true`; `TestTokens.token(Map<String,Object> overrides)` → signed RS256 JWT string (override value `null` removes the claim; `exp`/`iat` overrides are `java.util.Date`), `TestTokens.tokenSignedByOtherKey(Map)` for signature-failure tests; constants `TestTokens.ISSUER` (`https://test-issuer.local/realms/test`), `TestTokens.AUDIENCE` (`spring-ai-demo`).

- [ ] **Step 1: Generate the test-only RSA keypair**

```bash
mkdir -p src/test/resources/oidc
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/test/resources/oidc/test-private.pem
openssl pkey -in src/test/resources/oidc/test-private.pem -pubout -out src/test/resources/oidc/test-public.pem
```

- [ ] **Step 2: Write the failing decoder test**

```java
package com.example.ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the OIDC decoder contract (spec R7 + §3): issuer validation, aud/azp
 * allow-list, non-empty sub — in the CI mode (local public key, no network).
 */
class OidcJwtDecoderTest {

    private static final String ISSUER = TestTokens.ISSUER;

    private JwtDecoder decoder() {
        return OidcSecurityConfig.createDecoder(ISSUER, "classpath:oidc/test-public.pem",
                "spring-ai-demo", new DefaultResourceLoader());
    }

    @Test
    void validTokenDecodes() {
        Jwt jwt = decoder().decode(TestTokens.token(Map.of()));
        assertThat(jwt.getSubject()).isEqualTo("user-1");
        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
    }

    @Test
    void wrongIssuerRejected() {
        assertThatThrownBy(() -> decoder().decode(
                TestTokens.token(Map.of("iss", "https://evil.example/realms/test"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void audAndAzpOutsideAllowListRejected() {
        assertThatThrownBy(() -> decoder().decode(
                TestTokens.token(Map.of("aud", List.of("other-api"), "azp", "other-client"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void azpAloneMatchingAllowListIsAccepted() {
        assertThat(decoder().decode(
                TestTokens.token(Map.of("aud", List.of("other-api")))).getSubject())
                .isEqualTo("user-1");
    }

    @Test
    void emptyOrMissingSubRejected() {
        assertThatThrownBy(() -> decoder().decode(TestTokens.token(Map.of("sub", ""))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder().decode(TestTokens.token(Map.of("sub", null))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenRejected() {
        assertThatThrownBy(() -> decoder().decode(
                TestTokens.token(Map.of("exp", new Date(System.currentTimeMillis() - 60_000)))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedByAnotherKeyRejected() {
        assertThatThrownBy(() -> decoder().decode(TestTokens.tokenSignedByOtherKey(Map.of())))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void misconfigurationsFailFastAtStartup() {
        assertThatThrownBy(() -> OidcSecurityConfig.createDecoder(
                "", "classpath:oidc/test-public.pem", "spring-ai-demo", new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri");
        assertThatThrownBy(() -> OidcSecurityConfig.createDecoder(
                ISSUER, "classpath:oidc/test-public.pem", "  ", new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-audiences");
        assertThatThrownBy(() -> OidcSecurityConfig.createDecoder(
                "", "", "spring-ai-demo", new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 3: Write `TestTokens`**

```java
package com.example.ai.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only RS256 token factory. The keypair in src/test/resources/oidc is a
 * throwaway fixture (equivalent of a committed test password) — never reuse it
 * outside tests. Claim overrides of {@code null} remove the claim entirely.
 */
final class TestTokens {

    static final String ISSUER = "https://test-issuer.local/realms/test";
    static final String AUDIENCE = "spring-ai-demo";

    private static final RSAKey SIGNING_KEY = loadSigningKey();
    private static final RSAKey OTHER_KEY = generateOtherKey();

    private TestTokens() {
    }

    static String token(Map<String, Object> overrides) {
        return sign(defaultClaims(overrides), SIGNING_KEY);
    }

    static String tokenSignedByOtherKey(Map<String, Object> overrides) {
        return sign(defaultClaims(overrides), OTHER_KEY);
    }

    private static Map<String, Object> defaultClaims(Map<String, Object> overrides) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", "user-1");
        claims.put("azp", AUDIENCE);
        claims.put("aud", List.of(AUDIENCE));
        claims.put("iat", new Date(System.currentTimeMillis() - 60_000));
        claims.put("exp", new Date(System.currentTimeMillis() + 300_000));
        overrides.forEach((key, value) -> {
            if (value == null) {
                claims.remove(key);
            } else {
                claims.put(key, value);
            }
        });
        return claims;
    }

    private static String sign(Map<String, Object> claims, RSAKey key) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
            claims.forEach(builder::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
            jwt.sign(new RSASSASigner(key.toPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign test token", e);
        }
    }

    private static RSAKey loadSigningKey() {
        try (var in = TestTokens.class.getClassLoader().getResourceAsStream("oidc/test-private.pem")) {
            if (in == null) {
                throw new IllegalStateException("Missing oidc/test-private.pem — generate it (plan Task 1 Step 1)");
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            return new RSAKey.Builder(key).keyID("oidc-test-key").build();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read oidc/test-private.pem", e);
        }
    }

    private static RSAKey generateOtherKey() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate wrong-key RSA pair", e);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./mvnw -B test -Dtest=OidcJwtDecoderTest`
Expected: compilation FAIL — `OidcSecurityConfig` does not exist.

- [ ] **Step 5: Add the dependency and the `app.oidc` properties**

`pom.xml` — inside the existing `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

`application.yml` — insert after the `auth:` block (after `required: false`, before `rate-limit:`):

```yaml
  oidc:
    # OIDC resource-server mode (spec docs/superpowers/specs/2026-09-25-oidc-jwt-resource-server-design.md).
    # When true, Spring Security validates Bearer JWTs on /ai/** and the
    # sensitive actuator endpoints (OidcSecurityConfig); identity derives from
    # issuer+azp+sub (ClientIdentity). Default false = exact current behaviour.
    enabled: false
    # Discovery URL for real mode. Mapped explicitly from OIDC_ISSUER_URI —
    # relaxed binding does NOT map that env var to this property by itself.
    issuer-uri: ${OIDC_ISSUER_URI:}
    # aud/azp allow-list; startup fails when enabled=true and this is empty.
    allowed-audiences: ${OIDC_ALLOWED_AUDIENCES:}
    # Test-only local decoder (no network): PEM public key; issuer-uri above
    # is then used solely as the expected iss claim. Never set in deployments.
    public-key-location: ""
```

- [ ] **Step 6: Write `OidcSecurityConfig` (decoder only — chains arrive in Tasks 3/6)**

```java
package com.example.ai.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The OIDC mode (spec §3): an app-owned {@link JwtDecoder} plus explicitly
 * ordered security chains. Nothing here activates unless app.oidc.enabled=true,
 * so default mode keeps the exact current behaviour (R3).
 */
@Configuration
public class OidcSecurityConfig {

    @Bean
    @ConditionalOnProperty(name = "app.oidc.enabled", havingValue = "true")
    JwtDecoder oidcJwtDecoder(
            @Value("${app.oidc.issuer-uri:}") String issuerUri,
            @Value("${app.oidc.public-key-location:}") String publicKeyLocation,
            @Value("${app.oidc.allowed-audiences:}") String allowedAudiences,
            ResourceLoader resourceLoader) {
        return createDecoder(issuerUri, publicKeyLocation, allowedAudiences, resourceLoader);
    }

    /**
     * Builds the decoder for both modes: public-key-location = CI mode
     * (no network; issuer-uri is only the expected iss), otherwise discovery
     * via issuer-uri. Always validates issuer + aud/azp allow-list + non-empty sub.
     */
    static JwtDecoder createDecoder(String issuerUri, String publicKeyLocation,
                                    String allowedAudiences, ResourceLoader resourceLoader) {
        boolean localKey = publicKeyLocation != null && !publicKeyLocation.isBlank();
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(localKey
                    ? "app.oidc.issuer-uri must be set when app.oidc.public-key-location is used (expected iss claim)"
                    : "app.oidc.enabled=true requires app.oidc.issuer-uri (discovery) or app.oidc.public-key-location (tests)");
        }
        Set<String> allowed = parseAllowList(allowedAudiences);
        NimbusJwtDecoder decoder;
        if (localKey) {
            decoder = NimbusJwtDecoder.withPublicKey(readPemPublicKey(resourceLoader, publicKeyLocation)).build();
        } else {
            decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
        }
        decoder.setJwtValidator(validator(issuerUri, allowed));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, Set<String> allowed) {
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
        return new DelegatingOAuth2TokenValidator<>(defaults, token -> {
            String sub = token.getSubject();
            if (sub == null || sub.isBlank()) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "sub claim must be non-empty", null));
            }
            List<String> aud = token.getAudience();
            String azp = token.getClaimAsString("azp");
            boolean matched = (aud != null && aud.stream().anyMatch(allowed::contains))
                    || (azp != null && allowed.contains(azp));
            if (!matched) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "token aud/azp not in app.oidc.allowed-audiences", null));
            }
            return OAuth2TokenValidatorResult.success();
        });
    }

    private static Set<String> parseAllowList(String csv) {
        if (csv == null) {
            throw new IllegalStateException("app.oidc.allowed-audiences is required when OIDC mode is enabled");
        }
        Set<String> allowed = Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        if (allowed.isEmpty()) {
            throw new IllegalStateException("app.oidc.allowed-audiences is required when OIDC mode is enabled");
        }
        return allowed;
    }

    private static RSAPublicKey readPemPublicKey(ResourceLoader resourceLoader, String location) {
        try {
            String pem = new String(resourceLoader.getResource(location)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read app.oidc.public-key-location: " + location, e);
        }
    }
}
```

Note: `JwtIssuerValidator`/`JwtTimestampValidator`/`JwtValidators` imports — `JwtValidators.createDefaultWithIssuer` already wires both; drop the two unused imports if the compiler/linter flags them.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -B test -Dtest=OidcJwtDecoderTest`
Expected: PASS (10 tests), then full gate `./mvnw -B verify` → 96+ tests green (86 + 10), JaCoCo floors OK.

- [ ] **Step 8: Commit** (ask user for authorization first)

```bash
git add pom.xml src/main/resources/application.yml src/main/java/com/example/ai/security/OidcSecurityConfig.java src/test/resources/oidc/ src/test/java/com/example/ai/security/TestTokens.java src/test/java/com/example/ai/security/OidcJwtDecoderTest.java
git commit -m "feat: OIDC decoder with issuer, aud/azp and sub validation (R7)"
```

---

### Task 2: `ClientIdentity` namespace from the JWT

**Files:**
- Modify: `src/main/java/com/example/ai/security/ClientIdentity.java` (`namespaceFor`)
- Modify: `src/test/java/com/example/ai/security/ClientIdentityUnitTest.java` (add tests; keep the 4 existing green)

**Interfaces:**
- Consumes: `JwtAuthenticationToken` (from the new starter), `ClientIdentity.fingerprint(String)` (existing, package-private).
- Produces: `ClientIdentity.namespaceFor(request)` → `fingerprint("jwt:<issuer>:<azp-or-dash>:<sub>")` when the thread's `SecurityContext` holds a `JwtAuthenticationToken`; unchanged attribute/IP behaviour otherwise (spec §4/R2).

- [ ] **Step 1: Write the failing tests** (append to `ClientIdentityUnitTest`)

```java
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void jwtNamespaceIsIssuerAzpSubFingerprint() {
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "RS256")
                .issuer("https://issuer.example/realms/x")
                .claim("azp", "client-a")
                .subject("user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest request = new MockHttpServletRequest();

        String namespace = ClientIdentity.namespaceFor(request);

        assertThat(namespace)
                .isEqualTo(ClientIdentity.fingerprint("jwt:https://issuer.example/realms/x:client-a:user-1"))
                .isNotEqualTo(ClientIdentity.namespaceFor(requestWithoutContext()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void jwtWithoutAzpUsesLiteralDash() {
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "RS256")
                .issuer("https://issuer.example/realms/x")
                .subject("user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThat(ClientIdentity.namespaceFor(new MockHttpServletRequest()))
                .isEqualTo(ClientIdentity.fingerprint("jwt:https://issuer.example/realms/x:-:user-1"));
    }

    private MockHttpServletRequest requestWithoutContext() {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        return request;
    }
```

Add imports: `org.springframework.security.core.context.SecurityContextHolder`, `org.springframework.security.oauth2.jwt.Jwt`, `org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken`, `org.junit.jupiter.api.AfterEach` (and `MockHttpServletRequest` if not already imported — the existing class already uses it).

Evaluation order is deterministic: the `isNotEqualTo(...)` argument runs after `requestWithoutContext()` clears the `SecurityContext`, so it compares the JWT namespace against the plain IP namespace.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -B test -Dtest=ClientIdentityUnitTest`
Expected: FAIL — `namespaceFor` returns the IP fingerprint for the JWT context (no JWT branch yet).

- [ ] **Step 3: Implement the JWT branch**

In `ClientIdentity.namespaceFor`, prepend:

```java
    public static String namespaceFor(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Jwt jwt = jwtAuthentication.getToken();
            String issuer = jwt.getIssuer() == null ? "-" : jwt.getIssuer().toString();
            String azp = jwt.getClaimAsString("azp");
            String azpSegment = (azp == null || azp.isBlank()) ? "-" : azp;
            String sub = jwt.getSubject() == null ? "-" : jwt.getSubject();
            return fingerprint("jwt:" + issuer + ":" + azpSegment + ":" + sub);
        }
        Object authenticated = request.getAttribute(ATTRIBUTE);
        // ... existing attribute + IP code unchanged
```

Add imports: `org.springframework.security.core.context.SecurityContextHolder`, `org.springframework.security.oauth2.jwt.Jwt`, `org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -B test -Dtest=ClientIdentityUnitTest` → PASS; then `./mvnw -B verify` → full gate green.

- [ ] **Step 5: Commit** (ask user for authorization first)

```bash
git add src/main/java/com/example/ai/security/ClientIdentity.java src/test/java/com/example/ai/security/ClientIdentityUnitTest.java
git commit -m "feat: derive client namespace from JWT issuer+azp+sub (R2)"
```

---

### Task 3: OIDC chains for `/ai/**` + always-on fallback, bearer auth, OPTIONS

**Files:**
- Modify: `src/main/java/com/example/ai/security/OidcSecurityConfig.java` (add two chain beans)
- Create: `src/test/java/com/example/ai/security/OidcModeSecurityTest.java`

**Interfaces:**
- Consumes: decoder bean (Task 1), `TestTokens` (Task 1).
- Produces: `oidcAiSecurityFilterChain` `@Order(1)` (matcher `/ai/**`, OPTIONS permitted, JWT required), `fallbackSecurityFilterChain` `@Order(3)` (everything else `permitAll`, CSRF off, stateless).

- [ ] **Step 1: Write the failing test**

```java
package com.example.ai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the OIDC mode of /ai/** (spec R1): bearer required with a Bearer
 * challenge, valid tokens pass, and CORS preflight is exempt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.oidc.enabled=true",
        "app.oidc.public-key-location=classpath:oidc/test-public.pem",
        "app.oidc.issuer-uri=" + TestTokens.ISSUER,
        "app.oidc.allowed-audiences=spring-ai-demo"
})
class OidcModeSecurityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    void missingTokenIsRejectedWith401BearerChallenge() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/session", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void validBearerTokenIsAccepted() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/session", HttpMethod.GET,
                new HttpEntity<>(bearer(TestTokens.token(Map.of()))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();
    }

    @Test
    void corsPreflightIsNotRejectedWith401() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/chat", HttpMethod.OPTIONS,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -B test -Dtest=OidcModeSecurityTest`
Expected: FAIL — no chain requires auth (200 without token), because the starter's default is overridden only once our chains exist (today no `SecurityFilterChain` bean → Boot default secures everything with generated password — assert the actual failure: either 401-with-basic-challenge or context failure; whatever it is, `missingTokenIsRejectedWith401BearerChallenge` will fail on the `Bearer` challenge check).

- [ ] **Step 3: Add the chain beans to `OidcSecurityConfig`**

```java
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "app.oidc.enabled", havingValue = "true")
    SecurityFilterChain oidcAiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/ai/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/ai/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain fallbackSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
```

Add imports: `org.springframework.http.HttpMethod`, `org.springframework.security.config.annotation.web.builders.HttpSecurity`, `org.springframework.security.config.Customizer`, `org.springframework.security.config.http.SessionCreationPolicy`, `org.springframework.security.web.SecurityFilterChain`, `org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer`, `org.springframework.core.annotation.Order`.

Why the fallback exists (comment in code):

```java
    /**
     * Always-on fallback (spec §3 chain 3): catches every request the
     * conditional OIDC chains don't match, and in default mode is the ONLY
     * chain — permitAll + CSRF off + stateless reproduces the pre-Spring-Security
     * behaviour exactly (R3). Never remove its @Order(3).
     */
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -B test -Dtest=OidcModeSecurityTest` → PASS (3 tests).

- [ ] **Step 5: Run the full regression (critical — default mode must be unchanged)**

Run: `./mvnw -B verify`
Expected: all previous tests green. If any default-mode test fails with a Spring Security error (CSRF, 401, redirect), the fallback chain is misconfigured — fix it, do NOT modify the failing default-mode test.

- [ ] **Step 6: Commit** (ask user for authorization first)

```bash
git add src/main/java/com/example/ai/security/OidcSecurityConfig.java src/test/java/com/example/ai/security/OidcModeSecurityTest.java
git commit -m "feat: ordered OIDC + fallback security chains for /ai/** (R1, R3)"
```

---

### Task 4: Token failure modes → 401 over HTTP

**Files:**
- Modify: `src/test/java/com/example/ai/security/OidcModeSecurityTest.java`

**Interfaces:**
- Consumes: `TestTokens.token`/`tokenSignedByOtherKey` (Task 1), chains (Task 3).
- Produces: no production code; locks spec §6.2's 401 matrix end-to-end.

- [ ] **Step 1: Add the failing test methods**

```java
    private ResponseEntity<String> sessionWith(String token) {
        return rest.exchange("http://localhost:" + port + "/ai/session",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
    }

    @Test
    void rejectedTokensAnswer401() {
        assertThat(sessionWith(TestTokens.token(Map.of(
                "exp", new java.util.Date(System.currentTimeMillis() - 60_000)))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sessionWith(TestTokens.tokenSignedByOtherKey(Map.of()))).getStatusCode()
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sessionWith(TestTokens.token(Map.of(
                "iss", "https://evil.example/realms/test")))).getStatusCode()
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sessionWith(TestTokens.token(Map.of(
                "aud", java.util.List.of("other-api"), "azp", "other-client")))).getStatusCode()
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sessionWith(TestTokens.token(Map.of("sub", ""))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sessionWith(TestTokens.token(Map.of("sub", null))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
```

- [ ] **Step 2: Run to verify it fails first is unnecessary if Task 1–3 shipped correctly — run it anyway to confirm the matrix**

Run: `./mvnw -B test -Dtest=OidcModeSecurityTest`
Expected: PASS (4 tests) — if any case returns 200, the decoder validator wiring is broken: debug `OidcSecurityConfig.validator` first. If this is genuinely red before the step (e.g. you reordered tasks), write it before fixing anything (TDD order preserved).

- [ ] **Step 3: Commit** (ask user for authorization first)

```bash
git add src/test/java/com/example/ai/security/OidcModeSecurityTest.java
git commit -m "test: lock HTTP 401 matrix for expired, forged, wrong-issuer, wrong-aud and empty-sub tokens"
```

---

### Task 5: Interceptor skips the key for JWT, per-namespace buckets, DELETE isolation

**Files:**
- Modify: `src/main/java/com/example/ai/security/ApiKeyAuthInterceptor.java` (`preHandle`)
- Create: `src/test/java/com/example/ai/security/OidcAuthzAndRateLimitTest.java`

**Interfaces:**
- Consumes: chains (Task 3), `ClientIdentity` JWT namespace (Task 2), `DELETE /ai/chat/memory/{sessionId}` (existing, commit `9dcf92f`).
- Produces: interceptor returns `true` without `X-API-Key` when `SecurityContext` holds `JwtAuthenticationToken`; rate-limit buckets keyed by the full JWT namespace; conversation deletion isolated per JWT principal.

- [ ] **Step 1: Write the failing test**

```java
package com.example.ai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks OIDC-mode authorization behaviour (spec §4/§5/R5): the API-key
 * interceptor accepts an already-JWT-authenticated request, rate-limit
 * buckets are per JWT principal, and one principal cannot delete another's
 * conversation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.oidc.enabled=true",
        "app.oidc.public-key-location=classpath:oidc/test-public.pem",
        "app.oidc.issuer-uri=" + TestTokens.ISSUER,
        "app.oidc.allowed-audiences=spring-ai-demo",
        "app.auth.api-key=local-test-key",
        "app.rate-limit.requests-per-minute=3"
})
class OidcAuthzAndRateLimitTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    private final List<String> promptsSeen = new ArrayList<>();

    @BeforeEach
    void stubModel() {
        promptsSeen.clear();
        when(ollamaChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            var prompt = inv.getArgument(0, Prompt.class);
            promptsSeen.add(prompt.getInstructions().stream()
                    .map(org.springframework.ai.chat.messages.Message::getText)
                    .reduce("", (a, b) -> a + "\n" + b));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("stub reply"))));
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String tokenFor(String sub) {
        return TestTokens.token(Map.of("sub", sub));
    }

    @Test
    void jwtAuthenticatedRequestDoesNotNeedTheApiKey() {
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/session", HttpMethod.GET,
                new HttpEntity<>(bearer(tokenFor("user-a"))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void apiKeyAloneIsNotEnoughInOidcMode() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "local-test-key");
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/session", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rateLimitBucketsArePerJwtPrincipal() {
        String tokenA = tokenFor("user-a");
        for (int i = 0; i < 3; i++) {
            assertThat(rest.exchange("http://localhost:" + port + "/ai/session",
                    HttpMethod.GET, new HttpEntity<>(bearer(tokenA)), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> fourth = rest.exchange("http://localhost:" + port + "/ai/session",
                HttpMethod.GET, new HttpEntity<>(bearer(tokenA)), String.class);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // A different principal still has a full bucket.
        assertThat(rest.exchange("http://localhost:" + port + "/ai/session",
                HttpMethod.GET, new HttpEntity<>(bearer(tokenFor("user-b"))), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void principalCannotDeleteAnotherPrincipalsConversation() {
        String sessionId = UUID.randomUUID().toString();
        HttpHeaders chat = bearer(tokenFor("user-a"));
        chat.setContentType(MediaType.APPLICATION_JSON);

        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory", HttpMethod.POST,
                new HttpEntity<>("{\"sessionId\":\"" + sessionId + "\",\"message\":\"keep me secret\"}", chat),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // user-b deletes: 204 (idempotent) but user-a's history survives.
        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory/" + sessionId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(tokenFor("user-b"))), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory", HttpMethod.POST,
                new HttpEntity<>("{\"sessionId\":\"" + sessionId + "\",\"message\":\"still there\"}", chat),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promptsSeen.get(promptsSeen.size() - 1)).contains("keep me secret");

        // The owner deletes: history is gone from the next prompt.
        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory/" + sessionId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(tokenFor("user-a"))), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange("http://localhost:" + port + "/ai/chat/memory", HttpMethod.POST,
                new HttpEntity<>("{\"sessionId\":\"" + sessionId + "\",\"message\":\"now gone\"}", chat),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promptsSeen.get(promptsSeen.size() - 1)).doesNotContain("keep me secret");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -B test -Dtest=OidcAuthzAndRateLimitTest`
Expected: `jwtAuthenticatedRequestDoesNotNeedTheApiKey` FAILS (interceptor demands `X-API-Key` because `app.auth.api-key` is set → 401). The other three should already pass (chains don't check keys; namespace derives from JWT; DELETE is namespace-scoped) — if any fails, fix Task 2/3 wiring first.

- [ ] **Step 3: Implement the interceptor skip**

In `ApiKeyAuthInterceptor.preHandle`, immediately after the OPTIONS early-return:

```java
        // OIDC mode: the security chain already authenticated this request as a
        // JWT — the API key is not an additional requirement (spec R6: modes are
        // exclusive; rate limiting still runs in the next interceptor).
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken) {
            return true;
        }
```

Add imports: `org.springframework.security.core.context.SecurityContextHolder`, `org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -B test -Dtest=OidcAuthzAndRateLimitTest` → PASS (4 tests); `./mvnw -B verify` → full gate green.

- [ ] **Step 5: Commit** (ask user for authorization first)

```bash
git add src/main/java/com/example/ai/security/ApiKeyAuthInterceptor.java src/test/java/com/example/ai/security/OidcAuthzAndRateLimitTest.java
git commit -m "feat: accept JWT-authenticated requests without API key; per-principal buckets and DELETE isolation"
```

---

### Task 6: Actuator OIDC chain + inert `ActuatorApiKeyFilter`

**Files:**
- Modify: `src/main/java/com/example/ai/security/OidcSecurityConfig.java` (add `@Order(2)` chain)
- Modify: `src/main/java/com/example/ai/security/ActuatorApiKeyFilter.java` (add `@ConditionalOnProperty`)
- Create: `src/test/java/com/example/ai/security/OidcActuatorSecurityTest.java`
- Create: `src/test/java/com/example/ai/security/OidcActuatorKeyCoexistenceTest.java`

**Interfaces:**
- Consumes: decoder bean (Task 1), chains (Task 3).
- Produces: `oidcActuatorSecurityFilterChain` `@Order(2)` matching `/actuator/metrics`, `/actuator/metrics/**`, `/actuator/prometheus`; `ActuatorApiKeyFilter` absent when `app.oidc.enabled=true`.

- [ ] **Step 1: Write the failing tests**

`OidcActuatorSecurityTest.java`:

```java
package com.example.ai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks spec R6 in OIDC mode: metrics and prometheus require a Bearer JWT,
 * health stays public, and no API key is involved.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.oidc.enabled=true",
        "app.oidc.public-key-location=classpath:oidc/test-public.pem",
        "app.oidc.issuer-uri=" + TestTokens.ISSUER,
        "app.oidc.allowed-audiences=spring-ai-demo"
})
class OidcActuatorSecurityTest {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    void metricsWithoutTokenIs401() {
        ResponseEntity<String> response = rest.exchange("/actuator/metrics",
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void metricsWithTokenIs200() {
        ResponseEntity<String> response = rest.exchange("/actuator/metrics",
                HttpMethod.GET, new HttpEntity<>(bearer(TestTokens.token(Map.of()))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void prometheusWithTokenIs200AndHealthStaysPublic() {
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("warm")))));
        rest.getForObject("/ai/chat?message=warm", String.class); // records http metrics (mirrors ObservabilityTest)

        HttpHeaders scrape = bearer(TestTokens.token(Map.of()));
        scrape.setAccept(List.of(MediaType.TEXT_PLAIN));
        ResponseEntity<String> prometheus = rest.exchange("/actuator/prometheus",
                HttpMethod.GET, new HttpEntity<>(scrape), String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody()).contains("http_server_requests_seconds_count");

        ResponseEntity<String> health = rest.exchange("/actuator/health",
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("UP");
    }
}
```

`OidcActuatorKeyCoexistenceTest.java` (spec R6 — OIDC + `APP_API_KEY` both set):

```java
package com.example.ai.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R6: with OIDC mode enabled the API-key filter must be inert — a JWT
 * alone reaches metrics, and a key alone does not (modes are exclusive).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.oidc.enabled=true",
        "app.oidc.public-key-location=classpath:oidc/test-public.pem",
        "app.oidc.issuer-uri=" + TestTokens.ISSUER,
        "app.oidc.allowed-audiences=spring-ai-demo",
        "app.auth.api-key=local-test-key"
})
class OidcActuatorKeyCoexistenceTest {

    @Autowired
    private TestRestTemplate rest;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void jwtAloneReachesMetrics() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.token(Map.of()));
        ResponseEntity<String> response = rest.exchange("/actuator/metrics",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void apiKeyAloneDoesNotReachMetrics() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "local-test-key");
        ResponseEntity<String> response = rest.exchange("/actuator/metrics",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -B test -Dtest='OidcActuatorSecurityTest,OidcActuatorKeyCoexistenceTest'`
Expected: FAIL — `metricsWithoutTokenIs401` gets 200 (no chain guards actuator; filter is inert or key-less-open), `metricsWithTokenIs200` may pass coincidentally, `jwtAloneReachesMetrics` FAILS if the filter still demands the key.

- [ ] **Step 3: Add the `@Order(2)` chain and make the filter conditional**

`OidcSecurityConfig` (between the `@Order(1)` and `@Order(3)` beans):

```java
    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "app.oidc.enabled", havingValue = "true")
    SecurityFilterChain oidcActuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
```

`ActuatorApiKeyFilter` — replace `@Component` with:

```java
@Component
@ConditionalOnProperty(name = "app.oidc.enabled", havingValue = "false", matchIfMissing = true)
```

Add import `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`. Update the class javadoc with one line: `In OIDC mode this filter is not registered — the OIDC actuator chain (order 2) protects these endpoints instead (spec R6).`

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -B test -Dtest='OidcActuatorSecurityTest,OidcActuatorKeyCoexistenceTest'` → PASS (5 tests).

- [ ] **Step 5: Full regression — default-mode actuator tests must stay green**

Run: `./mvnw -B verify`
Expected: `ObservabilityTest` and the API-key actuator tests green (filter still registered in default mode, fallback chain permits, filter enforces the key as before).

- [ ] **Step 6: Commit** (ask user for authorization first)

```bash
git add src/main/java/com/example/ai/security/OidcSecurityConfig.java src/main/java/com/example/ai/security/ActuatorApiKeyFilter.java src/test/java/com/example/ai/security/OidcActuatorSecurityTest.java src/test/java/com/example/ai/security/OidcActuatorKeyCoexistenceTest.java
git commit -m "feat: JWT-protect metrics/prometheus in OIDC mode; API-key filter inert there (R6)"
```

---

### Task 7: `ProdAuthGuard` accepts OIDC as alternative

**Files:**
- Modify: `src/main/java/com/example/ai/security/ProdAuthGuard.java`
- Modify: `src/test/java/com/example/ai/security/ProdAuthGuardTest.java`

**Interfaces:**
- Consumes: `app.oidc.enabled` + `app.oidc.issuer-uri` properties (Task 1).
- Produces: prod fail-fast passes when `app.auth.required=true` AND (`app.auth.api-key` set OR (`app.oidc.enabled=true` AND non-empty `app.oidc.issuer-uri`)) — spec R4.

- [ ] **Step 1: Add the failing tests** (append to `ProdAuthGuardTest`)

```java
    @Test
    void requiredWithOidcIssuerStartsWithoutApiKey() {
        runner.withPropertyValues(
                        "app.auth.required=true",
                        "app.oidc.enabled=true",
                        "app.oidc.issuer-uri=https://idp.example/realms/demo")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void requiredWithOidcEnabledButNoIssuerStillFails() {
        runner.withPropertyValues("app.auth.required=true", "app.oidc.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("OIDC_ISSUER_URI");
                });
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -B test -Dtest=ProdAuthGuardTest`
Expected: `requiredWithOidcIssuerStartsWithoutApiKey` FAILS (guard ignores OIDC); `requiredWithOidcEnabledButNoIssuerStillFails` fails on the message assertion (old message lacks `OIDC_ISSUER_URI`).

- [ ] **Step 3: Implement**

```java
    private final boolean required;
    private final String apiKey;
    private final boolean oidcEnabled;
    private final String issuerUri;

    public ProdAuthGuard(@Value("${app.auth.required:false}") boolean required,
                         @Value("${app.auth.api-key:}") String apiKeys,
                         @Value("${app.oidc.enabled:false}") boolean oidcEnabled,
                         @Value("${app.oidc.issuer-uri:}") String issuerUri) {
        this.required = required;
        this.apiKey = apiKeys == null ? "" : apiKeys.trim();
        this.oidcEnabled = oidcEnabled;
        this.issuerUri = issuerUri == null ? "" : issuerUri.trim();
    }

    @PostConstruct
    void requireApiKeyWhenAuthIsMandatory() {
        if (!required) {
            return;
        }
        boolean oidcConfigured = oidcEnabled && !issuerUri.isEmpty();
        if (ApiKeyAuthInterceptor.parseKeys(apiKey).isEmpty() && !oidcConfigured) {
            throw new IllegalStateException(
                    "app.auth.required=true but neither an API key (APP_API_KEY) nor OIDC "
                            + "(APP_OIDC_ENABLED + OIDC_ISSUER_URI) is configured.");
        }
    }
```

Keep the existing javadoc, appending: `OIDC (app.oidc.enabled + app.oidc.issuer-uri) satisfies the requirement too (spec R4).`
Note: the existing test asserts `hasMessageContaining("APP_API_KEY")` — the new message still contains it ✓.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -B test -Dtest=ProdAuthGuardTest` → PASS (5 tests); `./mvnw -B verify` gate green.

- [ ] **Step 5: Commit** (ask user for authorization first)

```bash
git add src/main/java/com/example/ai/security/ProdAuthGuard.java src/test/java/com/example/ai/security/ProdAuthGuardTest.java
git commit -m "feat: prod fail-fast accepts OIDC as alternative to API key (R4)"
```

---

### Task 8: CORS allows DELETE

**Files:**
- Modify: `src/main/java/com/example/ai/config/CorsConfig.java:46` (`allowedMethods`)
- Modify: `src/test/java/com/example/ai/config/CorsConfigTest.java` (append a method to `CorsWildcardConfiguredTest`)

**Interfaces:**
- Consumes: existing `DELETE /ai/chat/memory/{sessionId}` route.
- Produces: preflight `Access-Control-Allow-Methods` includes `DELETE` when CORS is configured (R8).

- [ ] **Step 1: Add the failing test** (append to `CorsWildcardConfiguredTest`)

```java
    @Test
    void deleteMethodIsAllowedInPreflight() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://evil.example");
        headers.set("Access-Control-Request-Method", "DELETE");

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/ai/chat/memory/some-session",
                HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Methods"))
                .contains("DELETE");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -B test -Dtest=CorsWildcardConfiguredTest`
Expected: FAIL — `Access-Control-Allow-Methods` (if present) lacks `DELETE`.

- [ ] **Step 3: Implement**

```java
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -B test -Dtest='CorsConfigTest,CorsWildcardConfiguredTest,CorsSpecificOriginTest'` → PASS; `./mvnw -B verify` gate green.

- [ ] **Step 5: Commit** (ask user for authorization first)

```bash
git add src/main/java/com/example/ai/config/CorsConfig.java src/test/java/com/example/ai/config/CorsConfigTest.java
git commit -m "fix: allow DELETE in CORS preflight for the conversation-delete route (R8)"
```

---

### Task 9: Distinguish the two 429s

**Files:**
- Modify: `src/main/java/com/example/ai/security/RateLimitInterceptor.java:157` (`error` argument)
- Modify: `src/main/java/com/example/ai/config/GlobalExceptionHandler.java` (`handleLlmBusy`)
- Create: `src/test/java/com/example/ai/security/ErrorCodes429Test.java`

**Interfaces:**
- Consumes: `ApiError.error` is the only code field (`ApiError.java:5`).
- Produces: `error="rate_limit_exceeded"` (interceptor path) and `error="llm_bulkhead_full"` (exception path) — spec R9.

- [ ] **Step 1: Write the failing test**

```java
package com.example.ai.security;

import com.example.ai.api.ApiError;
import com.example.ai.config.GlobalExceptionHandler;
import com.example.ai.config.LlmBulkheadFullException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R9: the two 429 sources must be distinguishable via ApiError.error —
 * there is no separate `code` field (ApiError.java:5).
 */
class ErrorCodes429Test {

    @Test
    void bulkhead429UsesLlmBulkheadFullError() {
        var handler = new GlobalExceptionHandler();
        var request = new ServletWebRequest(new MockHttpServletRequest("POST", "/ai/chat"));

        ResponseEntity<ApiError> response =
                handler.handleLlmBusy(new LlmBulkheadFullException(4), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("llm_bulkhead_full");
    }

    @Test
    void rateLimit429UsesRateLimitExceededError() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var request = new MockHttpServletRequest("GET", "/ai/chat");
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        ApiErrorWriter.write(response, objectMapper, 429,
                "rate_limit_exceeded", "Too many requests", request);

        assertThat(response.getStatus()).isEqualTo(429);
        ApiError body = objectMapper.readValue(response.getContentAsByteArray(), ApiError.class);
        assertThat(body.error()).isEqualTo("rate_limit_exceeded");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -B test -Dtest=ErrorCodes429Test`
Expected: FAIL — bulkhead returns `error="Too Many Requests"`; rate-limit test passes only if Step 3 already applied (it asserts the *writer argument*, so first failure is the bulkhead one; write the rate-limit HTTP-path change in Step 3 and assert its real source there: after implementing, optionally also extend `RateLimitTest.thirdRequestWithinWindowIsRejectedWith429` with `assertThat(third.getBody()).contains("\"error\":\"rate_limit_exceeded\"")`).

- [ ] **Step 3: Implement both changes**

`GlobalExceptionHandler.handleLlmBusy` — first argument of `build(...)`:

```java
        return build(HttpStatus.TOO_MANY_REQUESTS, "llm_bulkhead_full",
                "The model is at capacity (app.llm.max-concurrent). Retry shortly.", request);
```

`RateLimitInterceptor` (`reject`, ~line 157) — the `error` argument of `ApiErrorWriter.write(...)`:

```java
        ApiErrorWriter.write(response, objectMapper, 429, "rate_limit_exceeded",
                "Too many requests", request);
```

Keep the human-readable `message` unchanged (the existing `RateLimitTest` asserts `contains("Too many requests")` ✓). Also append to `RateLimitTest.thirdRequestWithinWindowIsRejectedWith429`, after the existing 429 assertion:

```java
        assertThat(third.getBody()).contains("\"error\":\"rate_limit_exceeded\"");
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -B test -Dtest='ErrorCodes429Test,RateLimitTest,LlmBulkheadTest'` → PASS; `./mvnw -B verify` gate green (grep for other assertions of the old strings first: `grep -rn '"Too Many Requests"' src/test` must be empty).

- [ ] **Step 5: Commit** (ask user for authorization first)

```bash
git add src/main/java/com/example/ai/security/RateLimitInterceptor.java src/main/java/com/example/ai/config/GlobalExceptionHandler.java src/test/java/com/example/ai/security/ErrorCodes429Test.java src/test/java/com/example/ai/security/RateLimitTest.java
git commit -m "feat: distinguish 429 error codes rate_limit_exceeded vs llm_bulkhead_full (R9)"
```

---

### Task 10: Keycloak in docker-compose + realm export + README flow

**Files:**
- Modify: `docker-compose.yml`
- Create: `infra/keycloak/realm-export.json`
- Modify: `README.md` (OIDC section)

**Interfaces:**
- Consumes: app env contract `APP_OIDC_ENABLED`, `OIDC_ISSUER_URI`, `OIDC_ALLOWED_AUDIENCES` (Tasks 1/7).
- Produces: runnable `APP_OIDC_ENABLED=true docker compose up` stack; curl-able token flow on `localhost:8081`; demo client `spring-ai-demo` with `client_credentials` + audience `spring-ai-demo`.

- [ ] **Step 1: Resolve and pin the Keycloak tag**

```bash
docker pull quay.io/keycloak/keycloak:latest >/dev/null && docker run --rm quay.io/keycloak/keycloak:latest version
```

Take the printed `Version x.y.z` and pin `image: quay.io/keycloak/keycloak:x.y.z` in the compose file (never `latest`). Then confirm the hostname option of that exact version:

```bash
docker run --rm quay.io/keycloak/keycloak:x.y.z start-dev --help 2>&1 | grep -i hostname
```

Primary candidate: `KC_HOSTNAME` (set to the full frontend URL). If that version documents a dedicated frontend-URL option instead, use it. The E2E in Task 11 is the authoritative check (`iss` assertion).

- [ ] **Step 2: Write the realm export** — `infra/keycloak/realm-export.json`

```json
{
  "realm": "spring-ai-demo",
  "enabled": true,
  "displayName": "Spring AI demo",
  "sslRequired": "external",
  "clients": [
    {
      "clientId": "spring-ai-demo",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": false,
      "secret": "demo-client-secret",
      "serviceAccountsEnabled": true,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false,
      "protocolMappers": [
        {
          "name": "audience-spring-ai-demo",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-audience-mapper",
          "consentRequired": false,
          "config": {
            "included.client.audience": "spring-ai-demo",
            "id.token.claim": "false",
            "access.token.claim": "true"
          }
        }
      ]
    }
  ]
}
```

(Demo credential, same class as the existing `changeme` Postgres password — add a JSON comment is impossible, so note it in the compose file instead.)

- [ ] **Step 3: Add the `keycloak` service and wire the app env** — `docker-compose.yml`

Add after the `ollama-pull` service:

```yaml
  # OIDC mode (spec 2026-09-25): identity provider. Runs always (start-dev is
  # cheap); the app only switches to it with APP_OIDC_ENABLED=true. The demo
  # client secret in infra/keycloak/realm-export.json is a demo credential,
  # like the changeme Postgres password below.
  keycloak:
    image: quay.io/keycloak/keycloak:x.y.z   # pinned in Step 1
    container_name: spring-ai-keycloak
    command: start-dev --import-realm
    environment:
      KC_HEALTH_ENABLED: "true"
      # Frontend URL forced to the docker-network name so token iss, discovery
      # and the app's OIDC_ISSUER_URI are identical (spec §7); curl reaches the
      # token endpoint through the published 8081 port. Adjust to the exact
      # option the pinned version documents (Step 1).
      KC_HOSTNAME: http://keycloak:8080
      KC_BOOTSTRAP_ADMIN_USERNAME: ${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KC_BOOTSTRAP_ADMIN_PASSWORD:-admin}
    ports:
      # 8080 is taken by the app (127.0.0.1:8080:8080 below).
      - "127.0.0.1:8081:8080"
    volumes:
      - ./infra/keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json:ro
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000 || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 40s
    restart: unless-stopped
```

In the `app` service `environment:` block, add:

```yaml
      # OIDC mode: flip to true to require Bearer JWTs on /ai/** + metrics.
      APP_OIDC_ENABLED: ${APP_OIDC_ENABLED:-false}
      OIDC_ISSUER_URI: ${OIDC_ISSUER_URI:-http://keycloak:8080/realms/spring-ai-demo}
      OIDC_ALLOWED_AUDIENCES: ${OIDC_ALLOWED_AUDIENCES:-spring-ai-demo}
```

And extend the app `depends_on` (keep `ollama-pull`, add):

```yaml
      keycloak:
        condition: service_healthy
```

- [ ] **Step 4: Manual verification of the stack**

```bash
APP_OIDC_ENABLED=true docker compose up -d --build
docker compose ps   # keycloak healthy, app running
# token via the published host port
curl -s -X POST "http://localhost:8081/realms/spring-ai-demo/protocol/openid-connect/token" \
  -u spring-ai-demo:demo-client-secret -d grant_type=client_credentials
# → JSON with access_token; decode it (base64 middle part) and confirm
#   "iss":"http://keycloak:8080/realms/spring-ai-demo" and aud/azp spring-ai-demo
# API without token → 401; with token → 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/ai/session            # 401
TOKEN=$(... as above, extract with sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/ai/session  # 200
# default mode still fine
APP_OIDC_ENABLED=false docker compose up -d   # → /ai/session 200 without token
```

Expected: all four checks as commented. If `iss` differs from `http://keycloak:8080/...`, the hostname option from Step 1 is wrong for this version — fix it in compose and re-run (do not change the app env).

- [ ] **Step 5: README — add the OIDC section** (after the API-key documentation; also update the roadmap bullet at ~line 460 from "Full OIDC / JWT auth via Spring Security" to a done-state note linking here)

```markdown
### OIDC / JWT mode (real principal identity)

Optional: every `/ai/**` request and the metrics endpoints then require a
Bearer JWT; the `issuer+azp+sub` of the token becomes the client namespace
(rate-limit buckets, semantic cache, conversation memory) instead of the API
key or IP. Default mode (no `APP_OIDC_ENABLED`) is unchanged.

```bash
APP_OIDC_ENABLED=true docker compose up -d
TOKEN=$(curl -s -X POST "http://localhost:8081/realms/spring-ai-demo/protocol/openid-connect/token" \
  -u spring-ai-demo:demo-client-secret -d grant_type=client_credentials \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8080/ai/chat?message=Hello"
```

Properties: `app.oidc.enabled` / `app.oidc.issuer-uri` (`OIDC_ISSUER_URI`) /
`app.oidc.allowed-audiences` (`OIDC_ALLOWED_AUDIENCES`). The prod profile
fails fast when neither OIDC nor an API key is configured. E2E against the
real stack: `E2E_KEYCLOAK=1 ./mvnw -B verify -Dtest=KeycloakE2EIT -Dsurefire.failIfNoSpecifiedTests=false`
(never part of the default build). See the design spec
`docs/superpowers/specs/2026-09-25-oidc-jwt-resource-server-design.md`.
```

- [ ] **Step 6: Tear down and run the full gate**

```bash
docker compose down
./mvnw -B verify && git diff --check
```

Expected: green (the compose change affects no tests).

- [ ] **Step 7: Commit** (ask user for authorization first)

```bash
git add docker-compose.yml infra/keycloak/realm-export.json README.md
git commit -m "feat: pinned Keycloak service, realm export and README OIDC curl flow"
```

---

### Task 11: Opt-in black-box E2E against the compose stack

**Files:**
- Create: `src/test/java/com/example/ai/e2e/KeycloakE2EIT.java`

**Interfaces:**
- Consumes: running compose stack from Task 10 with `APP_OIDC_ENABLED=true`; surefire already excludes `**/e2e/**` (`pom.xml:199-203`).
- Produces: `-Dtest=KeycloakE2EIT` + `E2E_KEYCLOAK=1` gated test asserting `iss`, 401/200 behaviour end-to-end (spec §6.5, §7).

- [ ] **Step 1: Write the E2E test**

```java
package com.example.ai.e2e;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box E2E against the docker-compose stack (spec §6.5/§7):
 *   APP_OIDC_ENABLED=true docker compose up -d
 *   E2E_KEYCLOAK=1 ./mvnw -B verify -Dtest=KeycloakE2EIT -Dsurefire.failIfNoSpecifiedTests=false
 * Never part of the default build (surefire excludes **/e2e/**).
 * Asserts the hostname/issuer coherence: token iss == the app's
 * OIDC_ISSUER_URI (http://keycloak:8080/realms/spring-ai-demo).
 */
class KeycloakE2EIT {

    private static final String TOKEN_URL =
            "http://localhost:8081/realms/spring-ai-demo/protocol/openid-connect/token";
    private static final String EXPECTED_ISSUER = "http://keycloak:8080/realms/spring-ai-demo";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\":\"([^\"]+)\"");

    @Test
    void bearerFlowAgainstComposeStack() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("E2E_KEYCLOAK")),
                "Set E2E_KEYCLOAK=1 with APP_OIDC_ENABLED=true docker compose up");
        HttpClient client = HttpClient.newHttpClient();

        // 1. Token via the published host port.
        String basic = Base64.getEncoder().encodeToString("spring-ai-demo:demo-client-secret".getBytes());
        HttpResponse<String> tokenResponse = client.send(HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResponse.statusCode()).isEqualTo(200);
        var matcher = ACCESS_TOKEN.matcher(tokenResponse.body());
        assertThat(matcher.find()).isTrue();
        String token = matcher.group(1);

        // 2. iss coherence: token carries the docker-network issuer (spec §7).
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).contains("\"iss\":\"" + EXPECTED_ISSUER + "\"");
        assertThat(payload).contains("spring-ai-demo"); // aud or azp

        // 3. API: no token 401, bearer 200.
        var noToken = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:8080/ai/session")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);
        var withToken = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:8080/ai/session"))
                .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(withToken.statusCode()).isEqualTo(200);

        // 4. Metrics protected, health public (R6).
        assertThat(client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:8080/actuator/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:8080/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
}
```

- [ ] **Step 2: Run it against the live stack**

```bash
APP_OIDC_ENABLED=true docker compose up -d
E2E_KEYCLOAK=1 ./mvnw -B test -Dtest=KeycloakE2EIT -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. Then without the env gate it must skip: `./mvnw -B test -Dtest=KeycloakE2EIT -Dsurefire.failIfNoSpecifiedTests=false` → test skipped (assumption), and default `./mvnw -B verify` never compiles it into the run (excluded).

- [ ] **Step 3: Tear down**

```bash
docker compose down
```

- [ ] **Step 4: Commit** (ask user for authorization first)

```bash
git add src/test/java/com/example/ai/e2e/KeycloakE2EIT.java
git commit -m "test: opt-in black-box E2E for compose OIDC stack (iss + 401/200 + actuator)"
```

---

### Task 12: Docs and final gate

**Files:**
- Modify: `ANALISE.md` (append §33)
- Modify: `relatorio.md` (strike the OIDC line in §6/P2)
- Modify: `docs/superpowers/memory/security.md` (OIDC-mode section in the module card)
- Modify: `README.md` (only if Task 10 left the roadmap bullet unfinished)

**Interfaces:**
- Consumes: everything from Tasks 1–11.
- Produces: documented delivery; final `./mvnw verify` evidence.

- [ ] **Step 1: Append `ANALISE.md` §33** (after §32)

```markdown
---

## 33. Modo OIDC/JWT com identidade do principal (25/09/2026)

Fecha a dívida do README ("Full OIDC / JWT auth via Spring Security") —
spec `docs/superpowers/specs/2026-09-25-oidc-jwt-resource-server-design.md` (v4).

- **Modo por propriedade**: `app.oidc.enabled=true` (+ `app.oidc.issuer-uri`,
  `app.oidc.allowed-audiences`) activa resource-server; default = exactamente
  o comportamento anterior (3 cadeias com `@Order` fixo: OIDC `/ai/**`,
  OIDC actuator, fallback `permitAll` stateless sem CSRF).
- **Identidade**: namespace = SHA-256 de `jwt:<issuer>:<azp|-:<sub>` em
  `ClientIdentity` — rate-limit, cache semântico e conversas passam a ser por
  principal; o interceptor de API key aceita JWTs (modos mutuamente exclusivos).
- **Validação**: issuer + allow-list `aud`/`azp` + `sub` não-vazio; decoder
  próprio (`public-key-location` em CI sem rede, discovery no E2E).
- **Actuator**: metrics/prometheus exigem JWT no modo OIDC; health público;
  `ActuatorApiKeyFilter` inerte nesse modo.
- **Operacional**: Keycloak pinado no compose (healthcheck, issuer na rede
  docker `http://keycloak:8080`, porta 8081 publicada), realm em
  `infra/keycloak/realm-export.json`, E2E black-box opt-in `KeycloakE2EIT`.
- **R1–R9** cobertos pelos testes; a contagem final de `./mvnw verify` é copiada
  da evidência fresca em Task 12 Step 4, sem número inventado.
```

- [ ] **Step 2: Strike the relatorio line**

```bash
grep -n 'OIDC' relatorio.md
```

Replace the OIDC bullet with the established style: `~~OIDC/JWT por usuário (Spring Security, namespace por \`sub\`/\`tenant\`).~~ — feito (identidade do principal via OIDC/JWT; ver \`docs/superpowers/specs/2026-09-25-oidc-jwt-resource-server-design.md\` e \`ANALISE.md\` §33).`

- [ ] **Step 3: Update the memory module card** — append to `docs/superpowers/memory/security.md` after "## Invariants" (and bump `last_verified_commit` after commit):

```markdown
- **OIDC mode** (`app.oidc.enabled=true`): three ordered chains
  (`@Order 1` `/ai/**` → `2` metrics/prometheus → `3` fallback `permitAll`,
  CSRF off, stateless); namespace = `jwt:issuer:azp|-:sub`; API key and JWT
  are mutually exclusive credentials; `ActuatorApiKeyFilter` is not registered;
  `ProdAuthGuard` accepts `OIDC_ISSUER_URI` instead of a key.
```

- [ ] **Step 4: Fill in the real test count in §33's last bullet**

Run: `./mvnw -B verify` → copy the exact `Tests run: N` into §33 (never a guessed number).

- [ ] **Step 5: Final gate**

```bash
./mvnw -B verify && git diff --check && git status --short
```

Expected: all green, no uncommitted files besides this commit's.

- [ ] **Step 6: Commit** (ask user for authorization first)

```bash
git add ANALISE.md relatorio.md docs/superpowers/memory/security.md README.md
git commit -m "docs: record OIDC/JWT mode (ANALISE 33, relatorio P2, security memory card)"
```

---

## Self-Review Record

- **Spec coverage:** R1 → Tasks 3/4 (OPTIONS + 401); R2 → Task 2; R3 → Task 3 Step 5 regression + fallback chain; R4 → Task 7; R5 → Task 5 (full-namespace buckets; distributed fail-open stays a documented limitation, §8 of spec — no code); R6 → Tasks 6; R7 → Task 1; R8 → Task 8; R9 → Task 9; §6 tests all placed; §7 compose/E2E → Tasks 10/11; §8 limitations → documented in Task 12 (memory card already lists PromptGuard/Redis limitations — verify they remain after the edit).
- **Placeholders:** none — the only deferred value is the Keycloak tag/hostname option, resolved by Task 10 Step 1's deterministic procedure and verified by Task 11's `iss` assertion (spec v4 requirement).
- **Type consistency:** `createDecoder(String,String,String,ResourceLoader)`, `TestTokens.token(Map)`, property names and chain names are used identically across tasks; `LlmBulkheadFullException(int)` matches the production constructor; `ApiErrorWriter.write` signature matches.
