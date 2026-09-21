package com.jingansi.autel.gateway.jasmart;

import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JASmartGatewayManagerTest {

    private final JASmartGatewayManager manager =
            new JASmartGatewayManager(new AutelGatewayProperties());

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void shouldRememberAircraftPresenceBeforeMqttConnects() {
        manager.setAircraftOnline(true);

        assertThat(manager.isAircraftOnline()).isTrue();
    }

    @Test
    void shouldDropPropertyWhenMqttIsNotConnected() {
        boolean accepted = manager.report(DeviceTarget.DOCK,
                Collections.singletonMap("windSpeed", 2.5D));

        assertThat(accepted).isFalse();
    }

    @Test
    void shouldAddConfiguredSnToEachDevicePropertyReportWithoutChangingSource() {
        AutelGatewayProperties properties = new AutelGatewayProperties();
        properties.getDevices().setDockSn("DOCK-SN");
        properties.getDevices().setAircraftSn("AIRCRAFT-SN");
        JASmartGatewayManager gatewayManager = new JASmartGatewayManager(properties);
        Map<String, Object> source = Map.of("windSpeed", 2.5D, "sn", "UNTRUSTED-SN");
        try {
            assertThat(gatewayManager.withDeviceSn(DeviceTarget.DOCK, source))
                    .containsEntry("windSpeed", 2.5D)
                    .containsEntry("sn", "DOCK-SN");
            assertThat(gatewayManager.withDeviceSn(DeviceTarget.AIRCRAFT, source))
                    .containsEntry("windSpeed", 2.5D)
                    .containsEntry("sn", "AIRCRAFT-SN");
            assertThat(source).containsEntry("sn", "UNTRUSTED-SN");
        } finally {
            gatewayManager.close();
        }
    }
}
