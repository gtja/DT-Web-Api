package com.jingansi.autel.gateway.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/** 网关配置：只保留当前接入链路实际使用的参数。 */
@Data
@ConfigurationProperties(prefix = "autel.gateway")
public class AutelGatewayProperties {

    private boolean enabled;
    private Skycc skycc = new Skycc();
    private Devices devices = new Devices();
    private Jasmart jasmart = new Jasmart();
    private Live live = new Live();

    public void validateForStartup() {
        require(skycc.username, "autel.gateway.skycc.username");
        require(skycc.password, "autel.gateway.skycc.password");
        require(devices.dockSn, "autel.gateway.devices.dock-sn");
        require(devices.aircraftSn, "autel.gateway.devices.aircraft-sn");
        require(jasmart.username, "autel.gateway.jasmart.username");
        require(jasmart.password, "autel.gateway.jasmart.password");
        require(jasmart.dock.productKey, "autel.gateway.jasmart.dock.product-key");
        require(jasmart.dock.deviceId, "autel.gateway.jasmart.dock.device-id");
        require(jasmart.aircraft.productKey, "autel.gateway.jasmart.aircraft.product-key");
        require(jasmart.aircraft.deviceId, "autel.gateway.jasmart.aircraft.device-id");
        requireScheme(skycc.baseUrl, "https", "SkyCC REST 地址");
        requireScheme(skycc.websocketUrl, "wss", "SkyCC WebSocket 地址");
        requireMqttEndpoint(jasmart.endpoint);

        String protocol = live.protocol.trim().toUpperCase(Locale.ROOT);
        if (!"RTMP".equals(protocol) || live.urlType != 1) {
            throw new IllegalStateException("当前直播链路只支持 RTMP（url-type=1）");
        }
        if (live.quality < 0 || live.quality > 4 || live.catalogRefreshIntervalMs <= 0L) {
            throw new IllegalStateException("直播画质或视频目录刷新周期配置错误");
        }
        live.protocol = protocol;
    }

    private static void require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("缺少配置 " + name);
        }
    }

    private static void requireScheme(URI uri, String scheme, String name) {
        if (uri == null || uri.getHost() == null || !scheme.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException(name + "必须使用 " + scheme + "://");
        }
    }

    private static void requireMqttEndpoint(URI uri) {
        String scheme = uri == null ? null : uri.getScheme();
        if (uri == null || uri.getHost() == null
                || !("tcp".equalsIgnoreCase(scheme) || "ssl".equalsIgnoreCase(scheme))) {
            throw new IllegalStateException("JASmart MQTT 地址必须使用 tcp:// 或 ssl://");
        }
    }

    @Data
    public static class Skycc {
        private URI baseUrl = URI.create("https://skycc.autelrobotics.cn");
        private URI websocketUrl = URI.create("wss://skycc.autelrobotics.cn/api/cc-websocket/ws/webSocket");
        private String username;
        @ToString.Exclude
        private String password;
        private String tenantId;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(20);
        private Duration tokenRefreshSkew = Duration.ofMinutes(5);
        private Duration websocketPingInterval = Duration.ofSeconds(25);
    }

    @Data
    public static class Devices {
        private String dockSn;
        private String aircraftSn;
    }

    @Data
    public static class Jasmart {
        private URI endpoint = URI.create("tcp://127.0.0.1:1883");
        private String username;
        @ToString.Exclude
        private String password;
        private Duration connectTimeout = Duration.ofSeconds(15);
        private long topologyCheckIntervalMs = 5_000L;
        private Identity dock = new Identity();
        private Identity aircraft = new Identity();
    }

    @Data
    public static class Identity {
        private String productKey;
        private String deviceId;
        @ToString.Exclude
        private String deviceSecret = "";
    }

    @Data
    public static class Live {
        private String protocol = "RTMP";
        private int urlType = 1;
        private int quality = 3;
        private Duration operationTimeout = Duration.ofSeconds(30);
        private long catalogRefreshIntervalMs = 60_000L;
    }
}
