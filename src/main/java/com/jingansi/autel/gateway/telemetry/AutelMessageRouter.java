package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import com.jingansi.autel.gateway.jasmart.JASmartGatewayManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/** SkyCC WebSocket 消息分发：OSD 转属性，事件转子设备拓扑。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AutelMessageRouter {

    private final ObjectMapper objectMapper;
    private final DockPropertyMapper dockMapper;
    private final AircraftPropertyMapper aircraftMapper;
    private final JASmartGatewayManager gatewayManager;
    private final AutelGatewayProperties properties;

    public void route(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            String method = envelope.path("method").asText("");
            int deviceKind = envelope.path("deviceKind").asInt(-1);
            String sn = deviceSn(envelope);
            log.info("SkyCC WS 事件 method={} deviceKind={} sn={} gateway={}",
                    method, deviceKind, sn, envelope.path("gateway").asText(""));
            if (!belongsToConfiguredPair(envelope, deviceKind)) {
                log.warn("忽略非本项目设备消息 method={} deviceKind={} sn={}", method, deviceKind, sn);
                return;
            }
            switch (method) {
                case "osd_property":
                    reportOsd(deviceKind, envelope.path("data"));
                    break;
                case "update_topo":
                    updateTopology(deviceKind, envelope.path("data"));
                    break;
                case "device_online":
                    if (deviceKind == 0) {
                        gatewayManager.setAircraftOnline(true);
                    }
                    break;
                case "device_offline":
                    if (deviceKind == 0 || deviceKind == 3) {
                        gatewayManager.setAircraftOnline(false);
                    }
                    break;
                default:
                    log.info("SkyCC WS 事件暂未接入 method={}", method);
            }
        } catch (IOException error) {
            log.warn("忽略无法解析的 SkyCC WS 消息 payload={}", message, error);
        }
    }

    public void connectionLost() {
        log.warn("SkyCC WS 已断开，飞机子设备置为离线");
        gatewayManager.setAircraftOnline(false);
    }

    private void reportOsd(int deviceKind, JsonNode data) {
        if (!data.isObject()) {
            return;
        }
        if (deviceKind == 3) {
            Map<String, Object> mapped = dockMapper.map(data);
            log.info("机场 OSD 转换结果 sn={} data={}", properties.getDevices().getDockSn(), mapped);
            gatewayManager.report(DeviceTarget.DOCK, mapped);
            Map<String, Object> live = aircraftMapper.mapLiveStatus(
                    data.path("live_status"), properties.getDevices().getAircraftSn());
            if (!live.isEmpty()) {
                log.info("飞机直播状态转换结果 sn={} data={}",
                        properties.getDevices().getAircraftSn(), live);
                gatewayManager.report(DeviceTarget.AIRCRAFT, live);
            }
        } else if (deviceKind == 0) {
            gatewayManager.setAircraftOnline(true);
            Map<String, Object> mapped = aircraftMapper.map(data);
            log.info("飞机 OSD 转换结果 sn={} data={}", properties.getDevices().getAircraftSn(), mapped);
            gatewayManager.report(DeviceTarget.AIRCRAFT, mapped);
        }
    }

    private void updateTopology(int deviceKind, JsonNode data) {
        if (deviceKind != 3 || !data.path("sub_devices").isArray()) {
            return;
        }
        boolean online = false;
        for (JsonNode subDevice : data.path("sub_devices")) {
            if (subDevice.path("domain").asInt(-1) == 0
                    && properties.getDevices().getAircraftSn()
                    .equalsIgnoreCase(subDevice.path("sn").asText(""))) {
                online = true;
                break;
            }
        }
        gatewayManager.setAircraftOnline(online);
    }

    private boolean belongsToConfiguredPair(JsonNode envelope, int deviceKind) {
        if (deviceKind != 0 && deviceKind != 3) {
            return false;
        }
        String actualSn = deviceSn(envelope);
        if (!actualSn.isEmpty()) {
            String expected = deviceKind == 3
                    ? properties.getDevices().getDockSn() : properties.getDevices().getAircraftSn();
            return expected.equalsIgnoreCase(actualSn);
        }
        String gateway = envelope.path("gateway").asText("");
        return properties.getDevices().getDockSn().equalsIgnoreCase(gateway)
                || (deviceKind == 0
                && properties.getDevices().getAircraftSn().equalsIgnoreCase(gateway));
    }

    private static String deviceSn(JsonNode envelope) {
        String sn = envelope.path("serialNumber").asText("");
        return sn.isEmpty() ? envelope.path("data").path("sn").asText("") : sn;
    }
}
