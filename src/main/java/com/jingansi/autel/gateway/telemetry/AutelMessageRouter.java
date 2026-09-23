package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import com.jingansi.autel.gateway.jasmart.JASmartGatewayManager;
import com.jingansi.autel.gateway.live.LiveChannelCatalog;
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
    private final LiveChannelCatalog liveCatalog;

    public void route(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            String method = envelope.path("method").asText("");
            int deviceKind = envelope.path("deviceKind").asInt(-1);
            String sn = deviceSn(envelope);
            log.info("SkyCC WS 事件 method={} reporterType={} deviceKind={} reporterSn={} gateway={}",
                    method, reporterType(deviceKind), deviceKind,
                    sn.isEmpty() ? envelope.path("gateway").asText("") : sn,
                    envelope.path("gateway").asText(""));
            if (!belongsToConfiguredPair(envelope, deviceKind)) {
                log.warn("忽略非本项目设备消息 method={} deviceKind={} sn={}", method, deviceKind, sn);
                return;
            }
            switch (method) {
                case "osd_property":
                    logLiveStatus(envelope, deviceKind);
                    liveCatalog.updateAircraftLiveStatus(envelope.path("data").path("live_status"),
                            envelope.path("timestamp").asLong(0));
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
                        liveCatalog.clearAircraftLiveStatus();
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
        liveCatalog.clearAircraftLiveStatus();
        log.warn("SkyCC WS 已断开，飞机子设备置为离线");
        gatewayManager.setAircraftOnline(false);
    }

    private void logLiveStatus(JsonNode envelope, int deviceKind) {
        String reporterSn = deviceSn(envelope);
        if (reporterSn.isEmpty()) {
            reporterSn = envelope.path("gateway").asText("");
        }
        for (JsonNode status : envelope.path("data").path("live_status")) {
            String videoId = status.path("video_id").asText("");
            String videoSn = videoId.contains("/") ? videoId.substring(0, videoId.indexOf('/')) : "";
            String videoDeviceType = properties.getDevices().getAircraftSn().equalsIgnoreCase(videoSn)
                    ? "飞机" : properties.getDevices().getDockSn().equalsIgnoreCase(videoSn) ? "机库" : "未知设备";
            log.info("SkyCC 直播状态 reporterType={} reporterSn={} videoDeviceType={} videoSn={}"
                            + " videoId={} videoType={} status={} errorStatus={} timestamp={}",
                    reporterType(deviceKind), reporterSn, videoDeviceType, videoSn, videoId,
                    status.path("video_type").asText(""), status.path("status").asInt(-1),
                    status.path("error_status").asInt(-1), envelope.path("timestamp").asLong(0));
        }
    }

    private static String reporterType(int deviceKind) {
        switch (deviceKind) {
            case 0: return "飞机";
            case 3: return "机库";
            case 60: return "中继基站";
            default: return "未知设备";
        }
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
            JsonNode subDevice = data.path("sub_device");
            if (properties.getDevices().getAircraftSn()
                    .equalsIgnoreCase(subDevice.path("device_sn").asText(""))) {
                // 当前机型的飞机固件版本由机巢携带，只转发配置绑定的飞机。
                MappingSupport.copyText(subDevice, "firmware_version", live, "firmwareVersion", 64);
            }
            if (!live.isEmpty()) {
                log.info("机巢携带的飞机属性转换结果 sn={} data={}",
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
