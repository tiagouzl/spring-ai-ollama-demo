package com.example.ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIdentityUnitTest {

    @Test
    void authenticatedNamespaceIsFingerprintAndNeverTheRawApiKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        ClientIdentity.authenticate(request, "top-secret-api-key");

        String namespace = ClientIdentity.namespaceFor(request);

        assertThat(namespace)
                .hasSize(64)
                .doesNotContain("top-secret-api-key")
                .isEqualTo(ClientIdentity.fingerprint("key:top-secret-api-key"));
    }

    @Test
    void differentApiKeysProduceDifferentNamespaces() {
        MockHttpServletRequest first = requestAuthenticatedWith("key-one");
        MockHttpServletRequest second = requestAuthenticatedWith("key-two");

        assertThat(ClientIdentity.namespaceFor(first))
                .isNotEqualTo(ClientIdentity.namespaceFor(second));
    }

    @Test
    void anonymousNamespaceUsesRemoteAddressFingerprint() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");

        assertThat(ClientIdentity.namespaceFor(request))
                .hasSize(64)
                .isEqualTo(ClientIdentity.fingerprint("ip:192.0.2.10"));
    }

    @Test
    void conversationIdIsJdbcSafeAndScopedToClient() {
        String first = ClientIdentity.conversationId("client-a", "shared-session");
        String second = ClientIdentity.conversationId("client-b", "shared-session");

        assertThat(first).hasSize(36);
        assertThat(first).isNotEqualTo(second);
    }

    private static MockHttpServletRequest requestAuthenticatedWith(String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        ClientIdentity.authenticate(request, apiKey);
        return request;
    }
}
