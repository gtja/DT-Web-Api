package com.jingansi.autel.gateway.live;

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
 * 上层 live 能力：申请 RTMP 地址，Java 管理 FFmpeg 将道通视频转推至上层媒体服务器。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveCommandHandler {

    private final LiveChannelCatalog catalog;
    private final FfmpegStreamRelay streamRelay;
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
            if ("START".equals(action)) {
                start(target, header, input, sourceSn, videoId);
            } else {
                streamRelay.stop(videoId);
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
                       String sourceSn, String videoId) throws Exception {
        String mediaVideoId = text(input.get("streamId"));
        if (mediaVideoId.isEmpty()) {
            mediaVideoId = "live";
        }
        PushTarget pushTarget = mediaServerPort.requestPushTarget(target, header, mediaVideoId)
                .get(properties.getLive().getOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (streamRelay.isRunning(videoId, pushTarget.getUrl())) {
            log.info("视频已在转推 videoId={} pushUrl={}", videoId, pushTarget.getUrl());
            return;
        }
        String sourceUrl = catalog.playbackUrl(sourceSn, videoId);
        streamRelay.start(videoId, sourceUrl, pushTarget.getUrl());
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
