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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveCommandHandlerTest {
    private static final String ID = "DOCK/10165-0-0/normal-0";
    private static final String PUSH = "rtmp://our-media/app/live";
    private final AutelLivePort autel = mock(AutelLivePort.class);
    private final LiveChannelCatalog catalog = mock(LiveChannelCatalog.class);
    private final MediaServerPort media = mock(MediaServerPort.class);
    private final MessageHeader header = MessageHeader.builder().tid("tid-test").bid("bid-test").build();
    private final LiveChannel channel = new LiveChannel("DOCK", ID, "normal", "10165-0-0",
            null, Collections.emptyList());
    private LiveCommandHandler handler;

    @BeforeEach
    void setUp() {
        when(catalog.resolve(DeviceTarget.DOCK, ID)).thenReturn(channel);
        when(media.requestPushTarget(any(), any(), anyString())).thenReturn(
                CompletableFuture.completedFuture(new PushTarget(PUSH, "live")));
        handler = new LiveCommandHandler(catalog, autel, media, LiveChannelCatalogTest.properties());
    }

    @AfterEach
    void tearDown() { handler.close(); }

    @Test
    void shouldRequestMediaServerThenStartAndReplyOnlyStatus() {
        JASmartThingServiceReply reply = invoke("START");
        assertThat(success(reply)).isEqualTo(Map.of("code", "OK", "message", "OK"));
        InOrder order = inOrder(catalog, media, autel, reply);
        order.verify(catalog).resolve(DeviceTarget.DOCK, ID);
        order.verify(media).requestPushTarget(DeviceTarget.DOCK, header, "live");
        order.verify(autel).start(channel, PUSH, 1, 3);
        order.verify(reply).complete(anyMap());
        verifyNoMoreInteractions(autel);
    }

    @Test
    void shouldUseRequestedStreamIdAndRepeatTheDirectStartFlow() {
        Map<String, Object> request = Map.of("action", "START", "videoId", ID,
                "streamId", "stream-7", "protocol", "WS_FLV");
        success(invoke(DeviceTarget.DOCK, request));
        success(invoke(DeviceTarget.DOCK, request));
        verify(media, times(2)).requestPushTarget(DeviceTarget.DOCK, header, "stream-7");
        verify(autel, times(2)).start(channel, PUSH, 1, 3);
        verifyNoMoreInteractions(autel);
    }

    @Test
    void shouldStartAircraftThroughItsOwnMediaServer() {
        String aircraftId = "AIR/CAM/zoom-0";
        LiveChannel aircraft = new LiveChannel("AIR", aircraftId, "zoom", "CAM", null, Collections.emptyList());
        when(catalog.resolve(DeviceTarget.AIRCRAFT, aircraftId)).thenReturn(aircraft);
        success(invoke(DeviceTarget.AIRCRAFT, Map.of("action", "START", "videoId", aircraftId)));
        verify(media).requestPushTarget(DeviceTarget.AIRCRAFT, header, "live");
        verify(autel).start(aircraft, PUSH, 1, 3);
        verifyNoMoreInteractions(autel);
    }

    @Test
    void shouldNotStartWhenMediaServerFails() {
        when(media.requestPushTarget(any(), any(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("media unavailable")));
        JASmartThingServiceReply reply = invoke("START");
        verify(reply, timeout(2000)).error(argThat(e -> e.getMessage().contains("media unavailable")));
        verify(reply, never()).complete(anyMap());
        verifyNoInteractions(autel);
    }

    @Test
    void shouldReportAutelFailureWithoutSwitchOrStopFallback() {
        when(autel.start(channel, PUSH, 1, 3)).thenThrow(
                new AutelApiException("Please acquire flight control first."));
        JASmartThingServiceReply reply = invoke("START");
        verify(reply, timeout(2000)).error(argThat(e -> e.getMessage().contains("flight control")));
        verify(reply, never()).complete(anyMap());
        verify(autel).start(channel, PUSH, 1, 3);
        verifyNoMoreInteractions(autel);
    }

    @Test
    void shouldStopWithoutMediaServerOrLocalStartHistory() {
        assertThat(success(invoke("STOP"))).isEqualTo(Map.of("code", "OK", "message", "OK"));
        verify(autel).stop(channel, 1, 3);
        verifyNoMoreInteractions(autel);
        verifyNoInteractions(media);
    }

    @Test
    void shouldTreatAlreadyStoppedAsSuccessful() {
        doThrow(new AutelApiException("Live not started")).when(autel).stop(channel, 1, 3);
        assertThat(success(invoke("STOP"))).containsEntry("code", "OK");
        verifyNoInteractions(media);
    }

    @Test
    void shouldRejectOtherDeviceBeforeCallingAutel() {
        JASmartThingServiceReply reply = invoke(DeviceTarget.DOCK,
                Map.of("action", "START", "videoId", "OTHER/CAM/normal-0"));
        verify(reply, timeout(2000)).error(argThat(e -> e.getMessage().contains("不属于")));
        verifyNoInteractions(autel, media, catalog);
    }

    @Test
    void shouldRejectUnknownAction() {
        JASmartThingServiceReply reply = invoke("PAUSE");
        verify(reply, timeout(2000)).error(argThat(e -> e.getMessage().contains("START 或 STOP")));
        verifyNoInteractions(autel, media, catalog);
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
