package com.jingansi.autel.gateway.autel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AutelTokenProviderTest {

    @Test
    void shouldEncryptLoginValueUsingX509RsaPkcs1() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String encodedPublicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        String encrypted = AutelTokenProvider.encrypt("credential-value", encodedPublicKey);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        String decrypted = new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)),
                StandardCharsets.UTF_8);
        assertThat(decrypted).isEqualTo("credential-value");
    }

    @Test
    void shouldLoginOnceAndReuseToken() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(json("{\"code\":\"0\",\"succ\":true,\"data\":{" +
                    "\"publicKey\":\"" + publicKey + "\",\"uuid\":\"uuid-1\"}}"));
            server.enqueue(json("{\"code\":\"0\",\"succ\":true,\"data\":{" +
                    "\"access_token\":\"token-value\",\"expires_in\":\"720\"}}"));
            AutelGatewayProperties properties = new AutelGatewayProperties();
            properties.getSkycc().setBaseUrl(server.url("/").uri());
            properties.getSkycc().setUsername("sky-user");
            properties.getSkycc().setPassword("sky-password");
            AutelTokenProvider provider = new AutelTokenProvider(
                    new OkHttpClient(), new ObjectMapper(), properties);

            assertThat(provider.getToken()).isEqualTo("token-value");
            assertThat(provider.getToken()).isEqualTo("token-value");

            RecordedRequest encryptRequest = server.takeRequest();
            RecordedRequest loginRequest = server.takeRequest();
            assertThat(encryptRequest.getPath()).isEqualTo("/api/encryptCode");
            assertThat(encryptRequest.getHeader("Content-Type")).isEqualTo("application/json");
            assertThat(loginRequest.getPath()).isEqualTo("/api/manage/auth/thirdLogin");
            assertThat(loginRequest.getBody().readUtf8()).doesNotContain("sky-password");
            assertThat(server.getRequestCount()).isEqualTo(2);
        } finally {
            server.shutdown();
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
