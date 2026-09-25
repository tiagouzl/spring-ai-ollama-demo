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
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
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
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            // Load the matching public key from the paired PEM so the JWK is complete.
            try (var pubIn = TestTokens.class.getClassLoader().getResourceAsStream("oidc/test-public.pem")) {
                if (pubIn == null) {
                    throw new IllegalStateException("Missing oidc/test-public.pem — generate it (plan Task 1 Step 1)");
                }
                String pubPem = new String(pubIn.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", "");
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pubPem)));
                return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("oidc-test-key").build();
            }
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
