package com.jingansi.autel.gateway.jasmart;

import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import com.jingansi.autel.gateway.live.LiveCommandHandler;
import com.jingansi.autel.gateway.live.MediaServerPort;
import com.jingansi.autel.gateway.live.PushTarget;
import com.jingansi.smart.DefaultJASmartGatewayClient;
import com.jingansi.smart.JASmartClient;
import com.jingansi.smart.JASmartClientOptions;
import com.jingansi.smart.JASmartSubDeviceClient;
import com.jingansi.smart.common.ErrorInfo;
import com.jingansi.smart.common.MessageHeader;
import com.jingansi.smart.listener.JASmartServiceCallbackImpl;
import com.jingansi.smart.listener.JASmartThingServiceReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** JASmart 网关：机场为网关设备，飞机为子设备。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JASmartGatewayManager implements MediaServerPort {

    private final AutelGatewayProperties properties;
    private final ExecutorService messageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService replyExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService gatewayExecutor = Executors.newSingleThreadExecutor();

    private volatile DefaultJASmartGatewayClient gatewayClient;
    private volatile JASmartSubDeviceClient aircraftClient;
    private volatile boolean aircraftOnline;
    private volatile Boolean sentAircraftOnline;
    private volatile boolean previouslyConnected;

    public synchronized void initialize() {
        if (gatewayClient != null) {
            return;
        }
        AutelGatewayProperties.Jasmart config = properties.getJasmart();
        AutelGatewayProperties.Identity dock = config.getDock();
        JASmartClientOptions options = JASmartClientOptions.builder()
                .messageExecutor(messageExecutor)
                .replyExecutor(replyExecutor)
                .gatewayExecutor(gatewayExecutor)
                .build();
        gatewayClient = new DefaultJASmartGatewayClient(
                config.getEndpoint().toString(), dock.getProductKey(), dock.getDeviceId(),
                dock.getDeviceSecret(), properties.getDevices().getDockSn(),
                config.getUsername(), config.getPassword(), options);
        AutelGatewayProperties.Identity aircraft = config.getAircraft();
        aircraftClient = gatewayClient.addSubDevice(
                aircraft.getProductKey(), aircraft.getDeviceId(), properties.getDevices().getAircraftSn());
        log.info("JASmart 客户端初始化完成 endpoint={} dock={} aircraft={}",
                config.getEndpoint(), dock.getDeviceId(), aircraft.getDeviceId());
    }

    public void registerLiveCapabilities(LiveCommandHandler handler) {
        requireInitialized();
        gatewayClient.setServiceHandle("live", callback(DeviceTarget.DOCK, handler));
        aircraftClient.setServiceHandle("live", callback(DeviceTarget.AIRCRAFT, handler));
        log.info("JASmart live 能力注册完成，机场与飞机共用同一拉流逻辑");
    }

    public synchronized void start() {
        requireInitialized();
        log.info("JASmart MQTT 开始连接 endpoint={}", properties.getJasmart().getEndpoint());
        gatewayClient.start();
        waitForConnection();
        if (!gatewayClient.isConnected()) {
            throw new IllegalStateException("JASmart MQTT 连接超时");
        }
        sentAircraftOnline = null;
        syncAircraftState();
        updateTopology();
        previouslyConnected = true;
        log.info("JASmart MQTT 连接成功");
    }

    public synchronized boolean report(DeviceTarget target, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return true;
        }
        if (!isConnected()) {
            log.warn("JASmart 属性丢弃：MQTT 未连接 target={} data={}", target, data);
            return false;
        }
        if (target == DeviceTarget.AIRCRAFT && !aircraftOnline) {
            log.warn("JASmart 飞机属性丢弃：飞机离线 data={}", data);
            return false;
        }
        Map<String, Object> reportData = withDeviceSn(target, data);
        try {
            log.info("JASmart 属性上报 target={} data={}", target, reportData);
            targetClient(target).thingPropertyPost(reportData);
            return true;
        } catch (RuntimeException error) {
            log.error("JASmart 属性上报失败 target={} data={}", target, reportData, error);
            return false;
        }
    }

    Map<String, Object> withDeviceSn(DeviceTarget target, Map<String, Object> data) {
        Map<String, Object> reportData = new LinkedHashMap<>(data);
        reportData.put("sn", target == DeviceTarget.DOCK
                ? properties.getDevices().getDockSn() : properties.getDevices().getAircraftSn());
        return reportData;
    }

    public synchronized void setAircraftOnline(boolean online) {
        boolean changed = aircraftOnline != online;
        aircraftOnline = online;
        if (changed) {
            log.info("飞机拓扑状态更新 online={} aircraftSn={}",
                    online, properties.getDevices().getAircraftSn());
        }
        syncAircraftState();
    }

    public boolean isAircraftOnline() {
        return aircraftOnline;
    }

    @Scheduled(fixedDelayString = "${autel.gateway.jasmart.topology-check-interval-ms:5000}")
    public synchronized void maintainConnection() {
        if (gatewayClient == null || !gatewayClient.isConnected()) {
            previouslyConnected = false;
            sentAircraftOnline = null;
            return;
        }
        if (!previouslyConnected) {
            log.info("JASmart MQTT 已重连，重新同步子设备拓扑");
            sentAircraftOnline = null;
            syncAircraftState();
            updateTopology();
            previouslyConnected = true;
        }
    }

    @Override
    public CompletableFuture<PushTarget> requestPushTarget(DeviceTarget target,
                                                           MessageHeader header,
                                                           String mediaVideoId) {
        CompletableFuture<PushTarget> future = new CompletableFuture<>();
        if (!isConnected()) {
            future.completeExceptionally(new IllegalStateException("JASmart MQTT 未连接"));
            return future;
        }
        Map<String, Object> request = new HashMap<>();
        request.put("protocol", properties.getLive().getProtocol());
        request.put("videoId", mediaVideoId);
        request.put("type", "ACTIVE");
        log.info("JASmart media_server 请求 target={} tid={} bid={} data={}",
                target, header == null ? null : header.getTid(),
                header == null ? null : header.getBid(), request);
        try {
            targetClient(target).platformServiceInvoke(header, "media_server", request,
                    new JASmartThingServiceReply() {
                        @Override
                        public void complete(Map<String, Object> response) {
                            log.info("JASmart media_server 响应 target={} data={}", target, response);
                            try {
                                future.complete(parsePushTarget(response, mediaVideoId));
                            } catch (RuntimeException error) {
                                future.completeExceptionally(error);
                            }
                        }

                        @Override
                        public void error(ErrorInfo errorInfo) {
                            String message = errorInfo == null ? "media_server 调用失败" : errorInfo.getMessage();
                            log.error("JASmart media_server 失败 target={} reason={}", target, message);
                            future.completeExceptionally(new IllegalStateException(message));
                        }
                    });
        } catch (RuntimeException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private void syncAircraftState() {
        if (!isConnected() || Objects.equals(sentAircraftOnline, aircraftOnline)) {
            return;
        }
        try {
            if (aircraftOnline) {
                log.info("JASmart 子设备上线 aircraftSn={}", properties.getDevices().getAircraftSn());
                gatewayClient.subDeviceOnline(properties.getDevices().getAircraftSn());
            } else {
                log.info("JASmart 子设备下线 aircraftSn={}", properties.getDevices().getAircraftSn());
                gatewayClient.subDeviceOffline(properties.getDevices().getAircraftSn());
            }
            sentAircraftOnline = aircraftOnline;
        } catch (RuntimeException error) {
            sentAircraftOnline = null;
            log.error("JASmart 子设备状态同步失败 online={}", aircraftOnline, error);
        }
    }

    private void updateTopology() {
        try {
            log.info("JASmart 全量拓扑上报 aircraftOnline={}", aircraftOnline);
            gatewayClient.updateTopo();
        } catch (RuntimeException error) {
            log.error("JASmart 全量拓扑上报失败", error);
        }
    }

    private JASmartServiceCallbackImpl callback(DeviceTarget target, LiveCommandHandler handler) {
        return new JASmartServiceCallbackImpl() {
            @Override
            public void process(MessageHeader header,
                                Map<String, Object> data,
                                JASmartThingServiceReply reply) {
                handler.handle(target, header, data, reply);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static PushTarget parsePushTarget(Map<String, Object> response, String fallbackStreamId) {
        if (response == null) {
            throw new IllegalStateException("media_server 返回为空");
        }
        Map<String, Object> data = response.get("data") instanceof Map
                ? (Map<String, Object>) response.get("data") : response;
        String code = String.valueOf(data.getOrDefault("code", response.get("code")));
        if (!("OK".equalsIgnoreCase(code) || "0".equals(code))) {
            throw new IllegalStateException(String.valueOf(data.getOrDefault("message", "media_server 返回失败")));
        }
        String url = String.valueOf(data.getOrDefault("url", "")).trim();
        if (url.isEmpty()) {
            throw new IllegalStateException("media_server 未返回推流地址");
        }
        String streamId = String.valueOf(data.getOrDefault("streamId", fallbackStreamId));
        return new PushTarget(url, streamId);
    }

    private JASmartClient targetClient(DeviceTarget target) {
        return target == DeviceTarget.DOCK ? gatewayClient : aircraftClient;
    }

    public boolean isConnected() {
        return gatewayClient != null && gatewayClient.isConnected();
    }

    private void waitForConnection() {
        long deadline = System.nanoTime() + properties.getJasmart().getConnectTimeout().toNanos();
        while (!gatewayClient.isConnected() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void requireInitialized() {
        Objects.requireNonNull(gatewayClient, "JASmart 网关尚未初始化");
    }

    @PreDestroy
    public synchronized void close() {
        if (gatewayClient != null) {
            try {
                if (gatewayClient.isConnected()) {
                    gatewayClient.subDeviceOffline(properties.getDevices().getAircraftSn());
                }
            } catch (RuntimeException error) {
                log.warn("JASmart 子设备下线失败", error);
            }
            try {
                gatewayClient.removeSubDevice(properties.getDevices().getAircraftSn());
            } catch (RuntimeException error) {
                log.warn("JASmart 子设备移除失败", error);
            }
            try {
                gatewayClient.release();
                log.info("JASmart 网关连接已关闭");
            } catch (RuntimeException error) {
                log.warn("JASmart 网关连接释放失败", error);
            }
        }
        gatewayClient = null;
        aircraftClient = null;
        messageExecutor.shutdownNow();
        replyExecutor.shutdownNow();
        gatewayExecutor.shutdownNow();
    }
}
