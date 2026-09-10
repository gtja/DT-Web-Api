package com.jingansi.autel.gateway.autel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/** SkyCC 第三方登录及 token 缓存。 */
@Component
@Slf4j
public class AutelTokenProvider {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AutelGatewayProperties properties;
    private volatile TokenState tokenState;

    public AutelTokenProvider(@Qualifier("autelRestHttpClient") OkHttpClient httpClient,
                              ObjectMapper objectMapper,
                              AutelGatewayProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public synchronized String getToken() {
        if (tokenState != null && tokenState.isUsable()) {
            return tokenState.value;
        }
        tokenState = login();
        return tokenState.value;
    }

    public synchronized void invalidate(String expectedToken) {
        if (tokenState != null && tokenState.value.equals(expectedToken)) {
            tokenState = null;
            log.info("SkyCC token 已失效，下次请求将重新登录");
        }
    }

    private TokenState login() {
        log.info("SkyCC 登录开始 username={}", properties.getSkycc().getUsername());
        JsonNode encryptData = data(execute(new Request.Builder()
                .url(url("/api/encryptCode"))
                .header("Content-Type", "application/json")
                .get().build(), "encryptCode"));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", encrypt(properties.getSkycc().getUsername(), requiredText(encryptData, "publicKey")));
        body.put("password", encrypt(properties.getSkycc().getPassword(), requiredText(encryptData, "publicKey")));
        body.put("uuid", requiredText(encryptData, "uuid"));

        Request request = new Request.Builder()
                .url(url("/api/manage/auth/thirdLogin"))
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        JsonNode loginData = data(execute(request, "thirdLogin"));
        String token = requiredText(loginData, "access_token");
        long expiresMinutes = longValue(loginData, "expires_in", 720L);
        Instant refreshAt = Instant.now().plus(expiresMinutes, ChronoUnit.MINUTES)
                .minus(properties.getSkycc().getTokenRefreshSkew());
        log.info("SkyCC 登录成功，tokenRefreshAt={}", refreshAt);
        return new TokenState(token, refreshAt);
    }

    private JsonNode execute(Request request, String name) {
        long start = System.currentTimeMillis();
        log.info("SkyCC 登录请求 name={} method={} url={}", name, request.method(), request.url());
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            log.info("SkyCC 登录响应 name={} httpCode={} elapsedMs={}",
                    name, response.code(), System.currentTimeMillis() - start);
            if (!response.isSuccessful()) {
                throw new AutelApiException("SkyCC 登录请求失败，HTTP " + response.code(), response.code(), null);
            }
            JsonNode root = objectMapper.readTree(body);
            validateBusinessResponse(root);
            return root;
        } catch (IOException error) {
            throw new AutelApiException("调用 SkyCC 登录接口失败", error);
        }
    }

    private String url(String path) {
        return properties.getSkycc().getBaseUrl().resolve(path).toString();
    }

    private static void validateBusinessResponse(JsonNode root) {
        String code = root.path("code").asText("0");
        if ((root.has("succ") && !root.path("succ").asBoolean())
                || !("0".equals(code) || "OK".equalsIgnoreCase(code))) {
            throw new AutelApiException(root.path("msg").asText("SkyCC 登录失败"), null, code);
        }
    }

    private static JsonNode data(JsonNode root) {
        return root.has("data") && root.get("data").isObject() ? root.get("data") : root;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new AutelApiException("SkyCC 响应缺少字段 " + field);
        }
        return value;
    }

    private static long longValue(JsonNode node, String field, long defaultValue) {
        try {
            return node.hasNonNull(field) ? Long.parseLong(node.get(field).asText()) : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    static String encrypt(String plaintext, String encodedPublicKey) {
        try {
            String normalized = encodedPublicKey
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(normalized)));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return Base64.getEncoder().encodeToString(
                    cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new AutelApiException("SkyCC 登录凭据 RSA 加密失败", error);
        }
    }

    private static final class TokenState {
        private final String value;
        private final Instant refreshAt;

        private TokenState(String value, Instant refreshAt) {
            this.value = value;
            this.refreshAt = refreshAt;
        }

        private boolean isUsable() {
            return Instant.now().isBefore(refreshAt);
        }
    }
}
