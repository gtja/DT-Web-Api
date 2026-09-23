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
import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
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
    // 仅由 autel-live 单线程访问；STOP 使用 START 时绑定的真实通道，不重新查道通。
    private final Map<DeviceTarget, String> liveVideoIds = new EnumMap<>(DeviceTarget.class);
    private FollowSession followSession;
    private long followedRevision = -1;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "autel-live");
        thread.setDaemon(false);
        return thread;
    });

    @PostConstruct
    public void startFollowing() {
        // 与 START/STOP 共用线程，合并高频 OSD，避免切换和停流相互竞争。
        executor.scheduleWithFixedDelay(this::followAircraft, 2, 2, TimeUnit.SECONDS);
    }

    void followAircraft() {
        FollowSession session = followSession;
        if (session == null) {
            return;
        }
        try {
            long revision = catalog.aircraftLiveRevision();
            if (revision == followedRevision
                    && streamRelay.isRunning(session.source.getVideoId(), session.pushUrl)) {
                return;
            }
            PlaybackSource next = catalog.playbackSource(properties.getDevices().getAircraftSn(), "live");
            if (!session.source.getVideoId().equals(next.getVideoId())
                    || !streamRelay.isRunning(session.source.getVideoId(), session.pushUrl)) {
                log.info("飞机镜头跟随切换 oldVideoId={} newVideoId={} preferredVideoId={}",
                        session.source.getVideoId(), next.getVideoId(), catalog.preferredAircraftVideoId());
                streamRelay.stop(session.source.getVideoId());
                try {
                    streamRelay.start(next.getVideoId(), next.getUrl(), session.pushUrl);
                } catch (Exception failure) {
                    // 新流启动失败尝试恢复原输入，保留会话供下一轮重试。
                    if (!Thread.currentThread().isInterrupted()) {
                        try {
                            streamRelay.start(session.source.getVideoId(), session.source.getUrl(), session.pushUrl);
                        } catch (Exception rollbackFailure) {
                            failure.addSuppressed(rollbackFailure);
                        }
                    }
                    throw failure;
                }
                followSession = new FollowSession(next, session.pushUrl);
                liveVideoIds.put(DeviceTarget.AIRCRAFT, next.getVideoId());
            }
            String preferred = catalog.preferredAircraftVideoId();
            // 当前镜头暂不可拉取时使用广角等可用流，同时继续重试目标镜头。
            followedRevision = preferred == null || preferred.equals(next.getVideoId()) ? revision : -1;
        } catch (Exception failure) {
            log.warn("飞机镜头跟随失败，将重试 reason={}", safeMessage(failure));
        }
    }

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
            if (videoId.isEmpty()) {
                throw new IllegalArgumentException("videoId 不能为空");
            }
            String sourceSn = target == DeviceTarget.DOCK
                    ? properties.getDevices().getDockSn() : properties.getDevices().getAircraftSn();
            boolean businessId = "live".equalsIgnoreCase(videoId);
            String sourceVideoId = businessId ? liveVideoIds.get(target) : videoId;
            if (sourceVideoId != null && !sourceVideoId.startsWith(sourceSn + "/")) {
                throw new IllegalArgumentException("videoId 不属于当前设备");
            }
            if ("START".equals(action)) {
                sourceVideoId = start(target, header, input, sourceSn, videoId);
                if (businessId) {
                    liveVideoIds.put(target, sourceVideoId);
                }
            } else {
                if (target == DeviceTarget.AIRCRAFT && followSession != null
                        && (businessId || sourceVideoId.equals(followSession.source.getVideoId()))) {
                    followSession = null;
                }
                if (sourceVideoId != null) {
                    streamRelay.stop(sourceVideoId);
                    liveVideoIds.remove(target, sourceVideoId);
                }
            }
            log.info("JASmart live 通道解析 target={} action={} videoId={} sourceVideoId={}",
                    target, action, videoId, sourceVideoId);
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

    private String start(DeviceTarget target,
                         MessageHeader header,
                         Map<String, Object> input,
                         String sourceSn, String videoId) throws Exception {
        String mediaVideoId = text(input.get("streamId"));
        if (mediaVideoId.isEmpty()) {
            mediaVideoId = "live";
        }
        PushTarget pushTarget = mediaServerPort.requestPushTarget(target, header, mediaVideoId)
                .get(properties.getLive().getOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        boolean businessId = "live".equalsIgnoreCase(videoId);
        String boundVideoId = businessId ? liveVideoIds.get(target) : videoId;
        // 复用前重新确认 OSD 跟随通道，无法确定时广角兜底。
        PlaybackSource source = null;
        if (businessId && target == DeviceTarget.AIRCRAFT) {
            try {
                source = catalog.playbackSource(sourceSn, videoId);
            } catch (RuntimeException error) {
                if (boundVideoId != null && streamRelay.isRunning(boundVideoId, pushTarget.getUrl())) {
                    log.warn("SkyCC 选流失败，继续复用已运行通道 videoId={} reason={}",
                            boundVideoId, error.getMessage());
                    return boundVideoId;
                }
                throw error;
            }
        }
        if (boundVideoId != null && streamRelay.isRunning(boundVideoId, pushTarget.getUrl())
                && (source == null || boundVideoId.equals(source.getVideoId()))) {
            if (businessId && target == DeviceTarget.AIRCRAFT && source != null) {
                followSession = new FollowSession(source, pushTarget.getUrl());
                followedRevision = -1;
            }
            cancelFollowingForExplicitStart(target, businessId, boundVideoId, pushTarget.getUrl());
            log.info("视频已在转推 videoId={} pushUrl={}", boundVideoId, pushTarget.getUrl());
            return boundVideoId;
        }
        if (source == null) {
            source = catalog.playbackSource(sourceSn, videoId);
        }
        log.info("JASmart live 拉流通道 target={} videoId={} sourceVideoId={}",
                target, videoId, source.getVideoId());
        if (businessId && boundVideoId != null && !boundVideoId.equals(source.getVideoId())) {
            streamRelay.stop(boundVideoId);
        }
        streamRelay.start(source.getVideoId(), source.getUrl(), pushTarget.getUrl());
        if (businessId && target == DeviceTarget.AIRCRAFT) {
            followSession = new FollowSession(source, pushTarget.getUrl());
            followedRevision = -1;
        }
        cancelFollowingForExplicitStart(target, businessId, source.getVideoId(), pushTarget.getUrl());
        return source.getVideoId();
    }

    private void cancelFollowingForExplicitStart(DeviceTarget target, boolean businessId,
                                                String videoId, String pushUrl) {
        if (target == DeviceTarget.AIRCRAFT && !businessId && followSession != null
                && (videoId.equals(followSession.source.getVideoId()) || pushUrl.equals(followSession.pushUrl))) {
            followSession = null;
        }
    }

    @RequiredArgsConstructor
    private static final class FollowSession {
        private final PlaybackSource source;
        private final String pushUrl;
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
