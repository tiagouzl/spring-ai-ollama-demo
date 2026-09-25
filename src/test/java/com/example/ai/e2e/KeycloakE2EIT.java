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
 * Black-box E2E against the docker-compose stack (spec 6.5/7):
 *   APP_OIDC_ENABLED=true docker compose up -d
 *   E2E_KEYCLOAK=1 ./mvnw -B verify -Dtest=KeycloakE2EIT -Dsurefire.failIfNoSpecifiedTests=false
 * Never part of the default build (surefire excludes e2e classes).
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

        // 2. iss coherence: token carries the docker-network issuer (spec 7).
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
