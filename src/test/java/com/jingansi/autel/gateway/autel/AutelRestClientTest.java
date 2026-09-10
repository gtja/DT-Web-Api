package com.jingansi.autel.gateway.autel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.live.LiveChannel;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutelRestClientTest {

    private MockWebServer server;
    private AutelRestClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        AutelGatewayProperties properties = new AutelGatewayProperties();
        properties.getSkycc().setBaseUrl(server.url("/").uri());
        properties.getSkycc().setTenantId("tenant-a");
        properties.getDevices().setDockSn("DOCK-SN");
        AutelTokenProvider tokenProvider = mock(AutelTokenProvider.class);
        when(tokenProvider.getToken()).thenReturn("raw-access-token");
        client = new AutelRestClient(new OkHttpClient(), new ObjectMapper(), properties, tokenProvider);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void shouldUseDocumentedLiveStatusPathAndRawAuthorizationToken() throws Exception {
        server.enqueue(success("{\"masterLiveStatus\":[],\"slaveLiveStatus\":[]}"));

        client.getLiveStatus("DOCK-SN", "AIR-SN");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo(
                "/api/business/device/online/liveStatus?masterDevSn=DOCK-SN&slaveDevSn=AIR-SN");
        assertThat(request.getHeader("Authorization")).isEqualTo("raw-access-token");
        assertThat(request.getHeader("tenant-Id")).isEqualTo("tenant-a");
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
    }

    @ParameterizedTest
    @CsvSource({"AIR-SN,zoom-0,zoom,zoom", "DOCK-SN,normal-0,normal,wide"})
    void shouldSendSnakeCaseStartPayload(String sn, String videoIndex,
                                        String catalogType, String controlType) throws Exception {
        server.enqueue(success("{\"active\":true}"));
        String videoId = sn + "/CAM/" + videoIndex;
        LiveChannel channel = new LiveChannel(sn, videoId, catalogType,
                "CAM", null, Collections.singletonList(catalogType));

        client.start(channel, "rtmp://media/push/id", 1, 3);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/manage/manage/api/v1/live/streams/start");
        assertThat(request.getBody().readUtf8()).isEqualTo(
                "{\"url_type\":1,\"url\":\"rtmp://media/push/id\","
                        + "\"video_id\":\"" + videoId + "\",\"video_quality\":3,\"video_type\":\"" + controlType + "\"}");
    }

    @Test
    void shouldSendSnakeCaseSwitchPayload() throws Exception {
        server.enqueue(success("{\"active\":true}"));
        LiveChannel channel = new LiveChannel("AIR-SN", "AIR-SN/CAM/nightvision-0", "nightvision",
                "CAM", null, Collections.singletonList("nightvision"));

        client.switchStream(channel, "rtmp://media/push/switched", 1, 2);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/manage/manage/api/v1/live/streams/switch");
        assertThat(request.getBody().readUtf8()).isEqualTo(
                "{\"url_type\":1,\"url\":\"rtmp://media/push/switched\"," +
                        "\"video_id\":\"AIR-SN/CAM/nightvision-0\",\"video_quality\":2," +
                        "\"video_type\":\"NightVision\"}");
    }

    @Test
    void shouldRejectSuccessfulResponseWhenStreamIsStillInactive() {
        server.enqueue(success("{\"active\":false}"));
        LiveChannel channel = new LiveChannel("AIR-SN", "AIR-SN/CAM/zoom-0", "zoom",
                "CAM", null, Collections.singletonList("zoom"));

        assertThatThrownBy(() -> client.start(channel, "rtmp://media/push/id", 1, 3))
                .isInstanceOf(AutelApiException.class)
                .hasMessageContaining("active=false");
    }

    private static MockResponse success(String data) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"0\",\"succ\":true,\"data\":" + data + "}");
    }
}
