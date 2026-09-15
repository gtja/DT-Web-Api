package com.jingansi.autel.gateway.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutelGatewayPropertiesTest {

    @Test
    void shouldAcceptTheSupportedRtmpConfiguration() {
        AutelGatewayProperties properties = validProperties();

        assertThatCode(properties::validateForStartup).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectProtocolAndUrlTypeThatCannotUseTheSwitchApi() {
        AutelGatewayProperties properties = validProperties();
        properties.getLive().setProtocol("RTSP");
        properties.getLive().setUrlType(2);

        assertThatThrownBy(properties::validateForStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RTMP");
    }

    @Test
    void shouldRequireSecureSkyccTransport() {
        AutelGatewayProperties properties = validProperties();
        properties.getSkycc().setBaseUrl(URI.create("http://skycc.example.com"));

        assertThatThrownBy(properties::validateForStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    void shouldRequireFfmpegExecutableAndPositiveTimeouts() {
        AutelGatewayProperties properties = validProperties();
        properties.getLive().setFfmpegPath(" ");
        assertThatThrownBy(properties::validateForStartup).hasMessageContaining("ffmpeg-path");

        properties.getLive().setFfmpegPath("ffmpeg");
        properties.getLive().setRelayStartTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validateForStartup).hasMessageContaining("必须大于 0");

        properties.getLive().setRelayStartTimeout(Duration.ofSeconds(20));
        properties.getLive().setRelayIoTimeout(Duration.ofSeconds(-1));
        assertThatThrownBy(properties::validateForStartup).hasMessageContaining("必须大于 0");
    }

    private static AutelGatewayProperties validProperties() {
        AutelGatewayProperties properties = new AutelGatewayProperties();
        properties.getSkycc().setUsername("skycc-user");
        properties.getSkycc().setPassword("skycc-password");
        properties.getDevices().setDockSn("DOCK");
        properties.getDevices().setAircraftSn("AIR");
        properties.getJasmart().setUsername("mqtt-user");
        properties.getJasmart().setPassword("mqtt-password");
        properties.getJasmart().getDock().setProductKey("00000000001");
        properties.getJasmart().getDock().setDeviceId("dock-id");
        properties.getJasmart().getAircraft().setProductKey("00000000002");
        properties.getJasmart().getAircraft().setDeviceId("aircraft-id");
        return properties;
    }
}
