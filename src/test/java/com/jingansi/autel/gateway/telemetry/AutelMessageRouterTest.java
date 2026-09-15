package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import com.jingansi.autel.gateway.jasmart.JASmartGatewayManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyMap;

class AutelMessageRouterTest {

    @Test
    void shouldForwardFirmwareOnlyForBoundAircraftFromDockOsd() {
        JASmartGatewayManager gatewayManager = mock(JASmartGatewayManager.class);
        router(gatewayManager).route("{\"method\":\"osd_property\",\"deviceKind\":3,\"serialNumber\":\"DOCK\","
                + "\"data\":{\"sub_device\":{\"device_sn\":\"AIR\",\"firmware_version\":\"12.1.3.7\"}}}");
        verify(gatewayManager).report(DeviceTarget.AIRCRAFT, Map.of("firmwareVersion", "12.1.3.7"));
        verify(gatewayManager, never()).setAircraftOnline(true);

        JASmartGatewayManager other = mock(JASmartGatewayManager.class);
        router(other).route("{\"method\":\"osd_property\",\"deviceKind\":3,\"serialNumber\":\"DOCK\","
                + "\"data\":{\"sub_device\":{\"device_sn\":\"OTHER\",\"firmware_version\":\"wrong\"}}}");
        verify(other, never()).report(eq(DeviceTarget.AIRCRAFT), anyMap());
    }

    @Test
    void shouldDropTelemetryWithoutAnyDeviceIdentity() {
        JASmartGatewayManager gatewayManager = mock(JASmartGatewayManager.class);
        AutelMessageRouter router = router(gatewayManager);

        router.route("{\"method\":\"osd_property\",\"deviceKind\":3," +
                "\"data\":{\"wind_speed\":2.5}}");

        verifyNoInteractions(gatewayManager);
    }

    @Test
    void shouldReportDockOsdDirectlyToGateway() {
        JASmartGatewayManager gatewayManager = mock(JASmartGatewayManager.class);
        AutelMessageRouter router = router(gatewayManager);

        router.route("{\"method\":\"osd_property\",\"deviceKind\":3," +
                "\"serialNumber\":\"DOCK\",\"data\":{\"wind_speed\":2.5}}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(gatewayManager).report(eq(DeviceTarget.DOCK), data.capture());
        assertThat(data.getValue()).containsEntry("windSpeed", 2.5D);
    }

    @Test
    void shouldSendFrontendExtensionsOnlyToDock() {
        JASmartGatewayManager dockGateway = mock(JASmartGatewayManager.class);
        router(dockGateway).route("{\"method\":\"osd_property\",\"deviceKind\":3,\"serialNumber\":\"DOCK\","
                + "\"data\":{\"mode_code\":0,\"temperature\":25.8}}");
        verify(dockGateway).report(DeviceTarget.DOCK,
                Map.of("modeCode", 0, "modeDisplay", "0", "temperature", 25.8D));
        verify(dockGateway, never()).report(eq(DeviceTarget.AIRCRAFT), anyMap());

        JASmartGatewayManager aircraftGateway = mock(JASmartGatewayManager.class);
        router(aircraftGateway).route("{\"method\":\"osd_property\",\"deviceKind\":0,\"serialNumber\":\"AIR\","
                + "\"data\":{\"mode_code\":0,\"temperature\":25.8}}");
        verify(aircraftGateway).report(DeviceTarget.AIRCRAFT, Map.of("modeCode", 0));
        verify(aircraftGateway, never()).report(eq(DeviceTarget.DOCK), anyMap());
    }

    @Test
    void shouldKeepAircraftFrontendFieldsOffDockReports() {
        String payload = "\"data\":{\"height\":10,\"battery\":{\"capacity_percent\":77},\"attitude_head\":45}}";
        JASmartGatewayManager dockGateway = mock(JASmartGatewayManager.class);
        router(dockGateway).route("{\"method\":\"osd_property\",\"deviceKind\":3,\"serialNumber\":\"DOCK\"," + payload);
        verify(dockGateway).report(DeviceTarget.DOCK, Map.of("height", 10));
        verify(dockGateway, never()).report(eq(DeviceTarget.AIRCRAFT), anyMap());

        JASmartGatewayManager aircraftGateway = mock(JASmartGatewayManager.class);
        router(aircraftGateway).route("{\"method\":\"osd_property\",\"deviceKind\":0,\"serialNumber\":\"AIR\"," + payload);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(aircraftGateway).report(eq(DeviceTarget.AIRCRAFT), data.capture());
        assertThat(data.getValue()).containsEntry("electricity", 77).containsEntry("altitude", 10)
                .containsEntry("uavYaw", 45).doesNotContainKeys("modeDisplay", "temperature");
        verify(aircraftGateway, never()).report(eq(DeviceTarget.DOCK), anyMap());
    }

    @Test
    void shouldSetAircraftOnlineAndReportAircraftOsdDirectly() {
        JASmartGatewayManager gatewayManager = mock(JASmartGatewayManager.class);
        AutelMessageRouter router = router(gatewayManager);

        router.route("{\"method\":\"osd_property\",\"deviceKind\":0,\"gateway\":\"DOCK\"," +
                "\"data\":{\"sn\":\"AIR\",\"horizontal_speed\":2.5}}");

        verify(gatewayManager).setAircraftOnline(true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(gatewayManager).report(eq(DeviceTarget.AIRCRAFT), data.capture());
        assertThat(data.getValue()).containsEntry("horizontalSpeed", 2.5D);
    }

    @Test
    void shouldRouteAircraftLiveQualityFromDockOsd() {
        JASmartGatewayManager gatewayManager = mock(JASmartGatewayManager.class);
        AutelMessageRouter router = router(gatewayManager);

        router.route("{\"method\":\"osd_property\",\"deviceKind\":3," +
                "\"serialNumber\":\"DOCK\",\"data\":{\"live_status\":[" +
                "{\"video_id\":\"AIR/CAM/zoom-0\",\"status\":1,\"video_quality\":2}]}}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(gatewayManager).report(eq(DeviceTarget.AIRCRAFT), data.capture());
        assertThat(data.getValue()).containsEntry("videoQuality", 3);
    }

    @Test
    void shouldRejectExplicitOtherAircraftEvenWhenGatewayMatchesDock() {
        JASmartGatewayManager gatewayManager = mock(JASmartGatewayManager.class);
        AutelMessageRouter router = router(gatewayManager);

        router.route("{\"method\":\"osd_property\",\"deviceKind\":0,\"gateway\":\"DOCK\"," +
                "\"data\":{\"sn\":\"OTHER\",\"horizontal_speed\":2.5}}");

        verifyNoInteractions(gatewayManager);
    }

    @Test
    void shouldForwardAircraftOnlineAndOfflineEvents() {
        JASmartGatewayManager gatewayManager = mock(JASmartGatewayManager.class);
        AutelMessageRouter router = router(gatewayManager);

        router.route("{\"method\":\"device_online\",\"deviceKind\":0," +
                "\"serialNumber\":\"AIR\",\"data\":{}}");
        router.route("{\"method\":\"device_offline\",\"deviceKind\":0," +
                "\"serialNumber\":\"AIR\",\"data\":{}}");

        verify(gatewayManager).setAircraftOnline(true);
        verify(gatewayManager).setAircraftOnline(false);
    }

    @Test
    void shouldSetAircraftOnlineFromDockTopology() {
        JASmartGatewayManager gatewayManager = mock(JASmartGatewayManager.class);
        AutelMessageRouter router = router(gatewayManager);

        router.route("{\"method\":\"update_topo\",\"deviceKind\":3,\"gateway\":\"DOCK\"," +
                "\"data\":{\"sub_devices\":[{\"domain\":0,\"sn\":\"AIR\"}]}}");

        verify(gatewayManager).setAircraftOnline(true);
    }

    @Test
    void shouldSetAircraftOfflineWhenWebsocketIsLost() {
        JASmartGatewayManager gatewayManager = mock(JASmartGatewayManager.class);
        AutelMessageRouter router = router(gatewayManager);

        router.connectionLost();

        verify(gatewayManager).setAircraftOnline(false);
    }

    private static AutelMessageRouter router(JASmartGatewayManager gatewayManager) {
        AutelGatewayProperties properties = new AutelGatewayProperties();
        properties.getDevices().setDockSn("DOCK");
        properties.getDevices().setAircraftSn("AIR");
        return new AutelMessageRouter(
                new ObjectMapper(), new DockPropertyMapper(), new AircraftPropertyMapper(),
                gatewayManager, properties);
    }
}
