package com.jingansi.autel.gateway.live;

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
        when(catalog.playbackUrl("DOCK", ID)).thenReturn(SOURCE);
        when(media.requestPushTarget(any(), any(), anyString())).thenReturn(
                CompletableFuture.completedFuture(new PushTarget(PUSH, "live")));
        handler = new LiveCommandHandler(catalog, relay, media, LiveChannelCatalogTest.properties());
    }

    @AfterEach
    void tearDown() { handler.close(); }

    @Test
    void shouldRequestMediaThenFetchSourceAndRelayBeforeReplying() throws Exception {
        JASmartThingServiceReply reply = invoke("START");
        assertThat(success(reply)).isEqualTo(Map.of("code", "OK", "message", "OK"));
        InOrder order = inOrder(catalog, media, relay, reply);
        order.verify(media).requestPushTarget(DeviceTarget.DOCK, header, "live");
        order.verify(relay).isRunning(ID, PUSH);
        order.verify(catalog).playbackUrl("DOCK", ID);
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
        when(catalog.playbackUrl("AIR", id)).thenReturn(SOURCE);
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
        when(catalog.playbackUrl("DOCK", ID)).thenThrow(new AutelApiException("目标通道尚未开流"));
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
