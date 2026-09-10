package com.jingansi.autel.gateway.jasmart;

import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

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
}
