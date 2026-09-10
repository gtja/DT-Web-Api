package com.jingansi.autel.gateway.live;

import com.jingansi.autel.gateway.autel.AutelApiException;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import com.jingansi.smart.common.ErrorInfo;
import com.jingansi.smart.common.MessageHeader;
import com.jingansi.smart.listener.JASmartThingServiceReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 上层 live 能力：向 media_server 申请 RTMP 地址，再下发给 SkyCC 开流。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveCommandHandler {

    private final LiveChannelCatalog catalog;
    private final AutelLivePort autelLivePort;
    private final MediaServerPort mediaServerPort;
    private final AutelGatewayProperties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "autel-live");
        thread.setDaemon(false);
        return thread;
    });

    public void handle(DeviceTarget target,
                       MessageHeader header,
                       Map<String, Object> request,
                       JASmartThingServiceReply reply) {
        Map<String, Object> input = request == null ? Collections.emptyMap() : request;
        log.info("JASmart live 请求 target={} tid={} bid={} body={}",
                target, header == null ? null : header.getTid(),
                header == null ? null : header.getBid(), input);
        try {
            executor.execute(() -> execute(target, header, input, reply));
        } catch (RejectedExecutionException error) {
            reply.error(error("直播服务已停止"));
        }
    }

    private void execute(DeviceTarget target,
                         MessageHeader header,
                         Map<String, Object> input,
                         JASmartThingServiceReply reply) {
        String action = text(input.get("action")).toUpperCase(Locale.ROOT);
        String videoId = text(input.get("videoId"));
        try {
            if (!("START".equals(action) || "STOP".equals(action))) {
                throw new IllegalArgumentException("action 只支持 START 或 STOP");
            }
            if (videoId.isEmpty() || "live".equalsIgnoreCase(videoId)) {
                throw new IllegalArgumentException("videoId 必须填写 videoList 中的完整通道 ID");
            }
            String sourceSn = target == DeviceTarget.DOCK
                    ? properties.getDevices().getDockSn() : properties.getDevices().getAircraftSn();
            if (!videoId.startsWith(sourceSn + "/")) {
                throw new IllegalArgumentException("videoId 不属于当前设备");
            }
            LiveChannel channel = catalog.resolve(target, videoId);
            if ("START".equals(action)) {
                start(target, header, input, channel);
            } else {
                stop(channel);
            }
            Map<String, Object> result = result("OK", "OK");
            log.info("JASmart live 成功 target={} action={} videoId={} result={}",
                    target, action, videoId, result);
            reply.complete(result);
        } catch (Exception error) {
            String message = safeMessage(error);
            log.error("JASmart live 失败 target={} action={} videoId={} reason={}",
                    target, action, videoId, message, error);
            reply.error(error(message));
        }
    }

    private void start(DeviceTarget target,
                       MessageHeader header,
                       Map<String, Object> input,
                       LiveChannel channel) throws Exception {
        String mediaVideoId = text(input.get("streamId"));
        if (mediaVideoId.isEmpty()) {
            mediaVideoId = "live";
        }
        PushTarget pushTarget = mediaServerPort.requestPushTarget(target, header, mediaVideoId)
                .get(properties.getLive().getOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        log.info("SkyCC 开始推流 videoId={} url={}", channel.getVideoId(), pushTarget.getUrl());
        autelLivePort.start(channel, pushTarget.getUrl(),
                properties.getLive().getUrlType(), properties.getLive().getQuality());
    }

    private void stop(LiveChannel channel) {
        log.info("SkyCC 停止推流 videoId={}", channel.getVideoId());
        try {
            autelLivePort.stop(channel,
                    properties.getLive().getUrlType(), properties.getLive().getQuality());
        } catch (AutelApiException error) {
            if (!(error.isLiveNotStarted() || error.isDeviceOffline())) {
                throw error;
            }
            log.info("SkyCC 视频已经停止或设备离线，按停流成功处理 videoId={}", channel.getVideoId());
        }
    }

    private static Map<String, Object> result(String code, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("message", message);
        return result;
    }

    private static ErrorInfo error(String message) {
        return ErrorInfo.builder().code(-1).message(message).build();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.util.concurrent.CompletionException)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? "直播操作失败" : message;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
