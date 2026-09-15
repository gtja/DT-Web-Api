package com.jingansi.autel.gateway.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingansi.autel.gateway.autel.AutelApiException;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import com.jingansi.smart.common.MessageHeader;
import com.jingansi.smart.listener.JASmartThingServiceReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveCommandHandlerTest {
    private static final String ID = "DOCK/10165-0-0/normal-0";
    private static final String PUSH = "rtmp://our-media/app/live";
    private static final String SOURCE = "https://autel/video.flv?token=test&pid=1";
    private final FfmpegStreamRelay relay = mock(FfmpegStreamRelay.class);
    private final LiveChannelCatalog catalog = mock(LiveChannelCatalog.class);
    private final MediaServerPort media = mock(MediaServerPort.class);
    private final MessageHeader header = MessageHeader.builder().tid("tid-test").bid("bid-test").build();
    private LiveCommandHandler handler;

    @BeforeEach
    void setUp() {
        when(catalog.playbackSource("DOCK", "live")).thenReturn(new PlaybackSource(ID, SOURCE));
        when(catalog.playbackSource("DOCK", ID)).thenReturn(new PlaybackSource(ID, SOURCE));
        when(media.requestPushTarget(any(), any(), anyString())).thenReturn(
                CompletableFuture.completedFuture(new PushTarget(PUSH, "live")));
        handler = new LiveCommandHandler(catalog, relay, media, LiveChannelCatalogTest.properties());
    }

    @AfterEach
    void tearDown() { handler.close(); }

    @Test
    void shouldTranslateBusinessIdBeforePullingAndKeepMediaIdUnchanged() throws Exception {
        assertThat(success(invoke(DeviceTarget.DOCK, Map.of("action", "START", "videoId", "live"))))
                .isEqualTo(Map.of("code", "OK", "message", "OK"));
        verify(catalog).playbackSource("DOCK", "live");
        verify(catalog, never()).resolve(any(), anyString());
        verify(media).requestPushTarget(DeviceTarget.DOCK, header, "live");
        verify(relay).start(ID, SOURCE, PUSH);
        verify(catalog, never()).playbackUrl(anyString(), eq("live"));
        verify(relay, never()).start(eq("live"), anyString(), anyString());
    }

    @Test
    void shouldReuseAndStopBoundChannelWithoutResolvingItAgain() throws Exception {
        success(invoke(DeviceTarget.DOCK, Map.of("action", "START", "videoId", "live")));
        clearInvocations(catalog, media, relay);
        when(catalog.playbackSource("DOCK", "live")).thenThrow(new IllegalStateException("道通已离线"));
        when(relay.isRunning(ID, PUSH)).thenReturn(true);

        success(invoke(DeviceTarget.DOCK, Map.of("action", "START", "videoId", "live")));
        verifyNoInteractions(catalog);
        verify(relay, never()).start(anyString(), anyString(), anyString());
        clearInvocations(media);
        success(invoke(DeviceTarget.DOCK, Map.of("action", "STOP", "videoId", "live")));
        verify(relay).stop(ID);
        verifyNoInteractions(catalog, media);
    }

    @Test
    void shouldKeepDockAndAircraftBusinessBindingsSeparate() throws Exception {
        String aircraftId = "AIR/CAM/zoom-0";
        when(catalog.playbackSource("AIR", "live")).thenReturn(new PlaybackSource(aircraftId, SOURCE));
        success(invoke(DeviceTarget.DOCK, Map.of("action", "START", "videoId", "live")));
        success(invoke(DeviceTarget.AIRCRAFT, Map.of("action", "START", "videoId", "live")));
        verify(relay).start(ID, SOURCE, PUSH);
        verify(relay).start(aircraftId, SOURCE, PUSH);

        success(invoke(DeviceTarget.DOCK, Map.of("action", "STOP", "videoId", "live")));
        verify(relay).stop(ID);
        verify(relay, never()).stop(aircraftId);
        success(invoke(DeviceTarget.AIRCRAFT, Map.of("action", "STOP", "videoId", "live")));
        verify(relay).stop(aircraftId);
    }

    @Test
    void shouldNotBindBusinessIdWhenRelayStartFails() throws Exception {
        doThrow(new IllegalStateException("FFmpeg 启动失败")).when(relay).start(ID, SOURCE, PUSH);
        JASmartThingServiceReply reply = invoke(DeviceTarget.DOCK, Map.of("action", "START", "videoId", "live"));
        verify(reply, timeout(2000)).error(any());
        clearInvocations(catalog, relay, media);

        success(invoke(DeviceTarget.DOCK, Map.of("action", "STOP", "videoId", "live")));
        verifyNoInteractions(catalog, relay, media);
    }

    @Test
    void shouldStopUnboundBusinessIdWithoutCallingExternalServices() {
        success(invoke(DeviceTarget.DOCK, Map.of("action", "STOP", "videoId", "live")));
        verifyNoInteractions(catalog, relay, media);
    }

    @Test
    void shouldUseExplicitSecondChannelWithoutResolvingDefault() throws Exception {
        String secondId = "DOCK/10165-0-1/normal-0";
        when(catalog.playbackSource("DOCK", secondId)).thenReturn(new PlaybackSource(secondId, SOURCE));
        success(invoke(DeviceTarget.DOCK, Map.of("action", "START", "videoId", secondId)));
        verify(catalog, never()).resolve(any(), anyString());
        verify(catalog).playbackSource("DOCK", secondId);
        verify(relay).start(secondId, SOURCE, PUSH);
    }

    @Test
    void shouldRequestMediaThenFetchSourceAndRelayBeforeReplying() throws Exception {
        JASmartThingServiceReply reply = invoke("START");
        assertThat(success(reply)).isEqualTo(Map.of("code", "OK", "message", "OK"));
        InOrder order = inOrder(catalog, media, relay, reply);
        order.verify(media).requestPushTarget(DeviceTarget.DOCK, header, "live");
        order.verify(relay).isRunning(ID, PUSH);
        order.verify(catalog).playbackSource("DOCK", ID);
        order.verify(relay).start(ID, SOURCE, PUSH);
        order.verify(reply).complete(anyMap());
        verify(catalog, never()).resolve(any(), anyString());
    }

    @Test
    void shouldReuseRunningRelayForTheSameTargetUrl() throws Exception {
        when(relay.isRunning(ID, PUSH)).thenReturn(true);
        success(invoke(DeviceTarget.DOCK, Map.of("action", "START", "videoId", ID, "streamId", "stream-7")));
        verify(media).requestPushTarget(DeviceTarget.DOCK, header, "stream-7");
        verifyNoInteractions(catalog);
        verify(relay, never()).start(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRelayAircraftThroughItsOwnMediaServer() throws Exception {
        String id = "AIR/CAM/zoom-0";
        when(catalog.playbackSource("AIR", id)).thenReturn(new PlaybackSource(id, SOURCE));
        success(invoke(DeviceTarget.AIRCRAFT, Map.of("action", "START", "videoId", id)));
        verify(media).requestPushTarget(DeviceTarget.AIRCRAFT, header, "live");
        verify(relay).start(id, SOURCE, PUSH);
    }

    @Test
    void shouldNotRelayWhenMediaServerFails() {
        when(media.requestPushTarget(any(), any(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("media unavailable")));
        JASmartThingServiceReply reply = invoke("START");
        verify(reply, timeout(2000)).error(argThat(e -> e.getMessage().contains("media unavailable")));
        verify(reply, never()).complete(anyMap());
        verifyNoInteractions(relay, catalog);
    }

    @Test
    void shouldFailIfAutelHasNoLiveSource() throws Exception {
        when(catalog.playbackSource("DOCK", ID)).thenThrow(new AutelApiException("目标通道尚未开流"));
        JASmartThingServiceReply reply = invoke("START");
        verify(reply, timeout(2000)).error(argThat(e -> e.getMessage().contains("尚未开流")));
        verify(reply, never()).complete(anyMap());
        verify(relay, never()).start(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReportRelayFailureWithoutSuccessfulReply() throws Exception {
        doThrow(new IllegalStateException("FFmpeg 启动超时")).when(relay).start(ID, SOURCE, PUSH);
        JASmartThingServiceReply reply = invoke("START");
        verify(reply, timeout(2000)).error(argThat(e -> e.getMessage().contains("FFmpeg")));
        verify(reply, never()).complete(anyMap());
    }

    @Test
    void shouldStopOnlyTheLocalRelayWithoutCallingAutelOrMediaServer() {
        assertThat(success(invoke("STOP"))).isEqualTo(Map.of("code", "OK", "message", "OK"));
        verify(relay).stop(ID);
        verifyNoMoreInteractions(relay);
        verifyNoInteractions(media, catalog);
    }

    @Test
    void shouldRelayActualAircraftZoomStreamEvenWhenCatalogOffersIrFirstAndTypeIsWide() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode status = mapper.readTree("{\"data\":{\"slaveLiveStatus\":["
                + "{\"video_id\":\"AIR/10732-0-0/ir-0\",\"video_type\":\"ir\"},"
                + "{\"video_id\":\"AIR/10732-0-0/wide-0\",\"video_type\":\"wide\"},"
                + "{\"video_id\":\"AIR/10732-0-0/zoom-0\",\"video_type\":\"zoom\"}]}}");
        JsonNode capacity = mapper.readTree("{\"code\":\"0\",\"succ\":true,\"data\":[{"
                + "\"url_type\":4,\"video_id\":\"AIR/10732-0-0/zoom-0\",\"active\":true,\"video_type\":\"wide\","
                + "\"url\":\"http://autel/index/api/whip\",\"uav_live_url\":\"http://autel/index/api/whip\","
                + "\"live_streams\":[{\"type\":\"flv\",\"url\":\"https://autel/zoom.flv\"},"
                + "{\"type\":\"rtmp\",\"videoId\":\"AIR/10732-0-0/zoom-0\","
                + "\"url\":\"rtmp://autel/zoom?token=test&userName=test&pid=test\"}]}]}");
        LiveChannelCatalogTest.FakeAutelLivePort port = new LiveChannelCatalogTest.FakeAutelLivePort(status, capacity);
        LiveChannelCatalog actualCatalog = new LiveChannelCatalog(port, LiveChannelCatalogTest.properties());
        actualCatalog.refresh();
        handler.close();
        handler = new LiveCommandHandler(actualCatalog, relay, media, LiveChannelCatalogTest.properties());

        success(invoke(DeviceTarget.AIRCRAFT, Map.of("action", "START", "videoId", "live")));

        verify(relay).start("AIR/10732-0-0/zoom-0", "rtmp://autel/zoom?token=test&userName=test&pid=test", PUSH);
        verify(relay, never()).start(eq("AIR/10732-0-0/ir-0"), anyString(), anyString());
        assertThat(port.capacityRequests).isEqualTo(1);
        assertThat(port.statusRequests).isEqualTo(1);
        assertThat(port.starts + port.switches + port.stops).isZero();
        JsonNode properties = mapper.valueToTree(actualCatalog.modelProperties(DeviceTarget.AIRCRAFT));
        for (JsonNode video : properties.path("videoList")) {
            assertThat(video.path("videoId").asText()).isEqualTo("live");
        }
        success(invoke(DeviceTarget.AIRCRAFT, Map.of("action", "STOP", "videoId", "live")));
        verify(relay).stop("AIR/10732-0-0/zoom-0");
        assertThat(port.capacityRequests).isEqualTo(1);
    }

    @Test
    void shouldReselectActiveChannelAfterRelayStopsAndBindStopToNewChannel() throws Exception {
        success(invoke(DeviceTarget.DOCK, Map.of("action", "START", "videoId", "live")));
        clearInvocations(relay, catalog);
        String nextId = "DOCK/10165-0-1/normal-0";
        when(catalog.playbackSource("DOCK", "live")).thenReturn(new PlaybackSource(nextId, SOURCE));

        success(invoke(DeviceTarget.DOCK, Map.of("action", "START", "videoId", "live")));

        InOrder order = inOrder(catalog, relay);
        order.verify(relay).isRunning(ID, PUSH);
        order.verify(catalog).playbackSource("DOCK", "live");
        order.verify(relay).stop(ID);
        order.verify(relay).start(nextId, SOURCE, PUSH);
        success(invoke(DeviceTarget.DOCK, Map.of("action", "STOP", "videoId", "live")));
        verify(relay).stop(nextId);
    }

    @Test
    void shouldRejectOtherDeviceBeforeCallingExternalServices() {
        JASmartThingServiceReply reply = invoke(DeviceTarget.DOCK,
                Map.of("action", "START", "videoId", "OTHER/CAM/normal-0"));
        verify(reply, timeout(2000)).error(argThat(e -> e.getMessage().contains("不属于")));
        verifyNoInteractions(relay, media, catalog);
    }

    private JASmartThingServiceReply invoke(String action) {
        return invoke(DeviceTarget.DOCK, Map.of("action", action, "videoId", ID, "protocol", "WS_FLV"));
    }

    private JASmartThingServiceReply invoke(DeviceTarget target, Map<String, Object> request) {
        JASmartThingServiceReply reply = mock(JASmartThingServiceReply.class);
        handler.handle(target, header, request, reply);
        return reply;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> success(JASmartThingServiceReply reply) {
        ArgumentCaptor<Map<String, Object>> result = ArgumentCaptor.forClass(Map.class);
        verify(reply, timeout(2000)).complete(result.capture());
        verify(reply, never()).error(any());
        return result.getValue();
    }
}
