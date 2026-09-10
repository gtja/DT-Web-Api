package com.jingansi.autel.gateway.autel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.live.AutelLivePort;
import com.jingansi.autel.gateway.live.LiveChannel;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** SkyCC 设备、视频 REST 调用。 */
@Component
@Slf4j
public class AutelRestClient implements AutelLivePort {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AutelGatewayProperties properties;
    private final AutelTokenProvider tokenProvider;

    public AutelRestClient(@Qualifier("autelRestHttpClient") OkHttpClient httpClient,
                           ObjectMapper objectMapper,
                           AutelGatewayProperties properties,
                           AutelTokenProvider tokenProvider) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public JsonNode getLiveStatus(String dockSn, String aircraftSn) {
        HttpUrl url = url("/api/business/device/online/liveStatus")
                .addQueryParameter("masterDevSn", dockSn)
                .addQueryParameter("slaveDevSn", aircraftSn)
                .build();
        return execute(new Request.Builder().url(url)
                .header("Content-Type", "application/x-www-form-urlencoded").get(), null, true);
    }

    @Override
    public JsonNode getCapacity(String sourceSn) {
        return execute(new Request.Builder()
                .url(url("/api/manage/manage/api/v1/live/streams/capacity/" + sourceSn).build())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .get(), null, true);
    }

    @Override
    public JsonNode start(LiveChannel channel, String pushUrl, int urlType, int quality) {
        return requireActive(postLive("/api/manage/manage/api/v1/live/streams/start",
                channel, pushUrl, urlType, quality), "开流");
    }

    @Override
    public JsonNode switchStream(LiveChannel channel, String pushUrl, int urlType, int quality) {
        return requireActive(postLive("/api/manage/manage/api/v1/live/streams/switch",
                channel, pushUrl, urlType, quality), "切流");
    }

    @Override
    public void stop(LiveChannel channel, int urlType, int quality) {
        postLive("/api/manage/manage/api/v1/live/streams/stop",
                channel, "", urlType, quality);
    }

    private JsonNode postLive(String path,
                              LiveChannel channel,
                              String pushUrl,
                              int urlType,
                              int quality) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("url_type", urlType);
        body.put("url", pushUrl);
        body.put("video_id", channel.getVideoId());
        body.put("video_quality", quality);
        // 机库按联调要求使用 wide；video_id 保留目录中的完整值（包括 normal-0）。
        String videoType = channel.getSourceSn().equals(properties.getDevices().getDockSn())
                ? "wide" : apiVideoType(channel.getVideoType());
        body.put("video_type", videoType);
        Request request = new Request.Builder()
                .url(url(path).build())
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        return execute(request.newBuilder(), body.toString(), true);
    }

    private static JsonNode requireActive(JsonNode response, String action) {
        JsonNode data = response.path("data");
        if (data.has("active") && !data.path("active").asBoolean()) {
            throw new AutelApiException("SkyCC " + action + "响应 active=false");
        }
        return response;
    }

    private JsonNode execute(Request.Builder requestBuilder, String requestBody, boolean retry401) {
        String token = tokenProvider.getToken();
        Request.Builder authenticated = requestBuilder.header("Authorization", token);
        String tenantId = properties.getSkycc().getTenantId();
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            authenticated.header("tenant-Id", tenantId);
        }
        Request request = authenticated.build();
        long start = System.currentTimeMillis();
        log.info("SkyCC 请求 method={} url={} body={}",
                request.method(), request.url(), requestBody == null ? "-" : requestBody);
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            log.info("SkyCC 响应 method={} url={} httpCode={} elapsedMs={} body={}",
                    request.method(), request.url(), response.code(),
                    System.currentTimeMillis() - start, responseBody);
            if (response.code() == 401 && retry401) {
                tokenProvider.invalidate(token);
                return execute(request.newBuilder().removeHeader("Authorization"), requestBody, false);
            }
            if (!response.isSuccessful()) {
                throw new AutelApiException("SkyCC 接口调用失败，HTTP " + response.code(),
                        response.code(), null);
            }
            JsonNode root = objectMapper.readTree(responseBody);
            validateBusinessResponse(root);
            return root;
        } catch (IOException error) {
            throw new AutelApiException("调用 SkyCC 接口失败", error);
        }
    }

    private void validateBusinessResponse(JsonNode root) {
        String code = root.path("code").asText("0");
        if ((root.has("succ") && !root.path("succ").asBoolean())
                || !("0".equals(code) || "OK".equalsIgnoreCase(code))) {
            throw new AutelApiException(root.path("msg").asText("SkyCC 业务接口失败"), null, code);
        }
    }

    private HttpUrl.Builder url(String path) {
        return HttpUrl.get(properties.getSkycc().getBaseUrl().resolve(path).toString()).newBuilder();
    }

    private static String apiVideoType(String videoType) {
        return "nightvision".equalsIgnoreCase(videoType) ? "NightVision" : videoType;
    }
}
