package com.jingansi.autel.gateway;

import com.jingansi.autel.gateway.autel.AutelWebSocketClient;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import com.jingansi.autel.gateway.jasmart.JASmartGatewayManager;
import com.jingansi.autel.gateway.live.LiveChannelCatalog;
import com.jingansi.autel.gateway.live.LiveCommandHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按“JASmart → SkyCC WS → 视频目录”的顺序启动网关。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GatewayRuntime implements ApplicationRunner {

    private final AutelGatewayProperties properties;
    private final JASmartGatewayManager gatewayManager;
    private final LiveCommandHandler liveHandler;
    private final LiveChannelCatalog liveCatalog;
    private final AutelWebSocketClient webSocketClient;
    private volatile boolean started;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("道通网关已禁用");
            return;
        }
        properties.validateForStartup();
        gatewayManager.initialize();
        gatewayManager.registerLiveCapabilities(liveHandler);
        gatewayManager.start();
        webSocketClient.start();
        started = true;
        refreshVideoCatalog();
        log.info("道通天穹 → JASmart 网关启动完成");
    }

    @Scheduled(fixedDelayString = "${autel.gateway.live.catalog-refresh-interval-ms:60000}")
    public void scheduledRefreshVideoCatalog() {
        if (started) {
            refreshVideoCatalog();
        }
    }

    private void refreshVideoCatalog() {
        try {
            liveCatalog.refresh();
            gatewayManager.report(DeviceTarget.DOCK,
                    liveCatalog.modelProperties(DeviceTarget.DOCK));
            gatewayManager.report(DeviceTarget.AIRCRAFT,
                    liveCatalog.modelProperties(DeviceTarget.AIRCRAFT));
        } catch (RuntimeException error) {
            log.error("SkyCC 视频目录刷新/上报失败", error);
        }
    }
}
