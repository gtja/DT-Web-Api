package com.jingansi.autel.gateway.live;

import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 一条视频一个 FFmpeg 进程；默认复制视频并禁用音频，可配置 H.264 转码。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FfmpegStreamRelay {
    private final AutelGatewayProperties properties;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private boolean closed;

    public boolean isRunning(String videoId, String pushUrl) {
        Session session = sessions.get(videoId);
        return session != null && session.pushUrl.equals(pushUrl) && session.process.isAlive()
                && session.ready.isDone() && !session.ready.isCompletedExceptionally();
    }

    public synchronized void start(String videoId, String sourceUrl, String pushUrl) throws Exception {
        if (closed) {
            throw new IllegalStateException("转推服务已关闭");
        }
        List<String> command = command(sourceUrl, pushUrl);
        if (isRunning(videoId, pushUrl)) {
            return;
        }
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (!entry.getKey().equals(videoId) && entry.getValue().pushUrl.equals(pushUrl)
                    && entry.getValue().process.isAlive()) {
                throw new IllegalStateException("该推流地址已被另一视频通道占用，请使用不同 streamId");
            }
        }
        stop(videoId);
        log.info("FFmpeg 启动 videoId={} command={}", videoId, command);
        Session session = new Session(launch(command), pushUrl);
        sessions.put(videoId, session);
        Thread reader = new Thread(() -> readOutput(videoId, session), "ffmpeg-" + videoId.replace('/', '-'));
        reader.setDaemon(true);
        reader.start();
        try {
            session.ready.get(properties.getLive().getRelayStartTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!session.process.isAlive()) {
                throw new IllegalStateException("FFmpeg 在启动阶段退出: " + session.lastError);
            }
            log.info("FFmpeg 转推成功 videoId={} pushUrl={} transcode={}",
                    videoId, pushUrl, properties.getLive().isTranscode());
        } catch (Exception error) {
            stop(videoId);
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (error instanceof TimeoutException) {
                throw new IllegalStateException("FFmpeg 启动超时，未检测到输出视频帧: " + session.lastError, error);
            }
            throw error;
        }
    }

    public synchronized void stop(String videoId) {
        Session session = sessions.remove(videoId);
        if (session == null) {
            log.info("FFmpeg 无需停流 videoId={}，当前无转推进程", videoId);
            return;
        }
        session.stopping = true;
        session.ready.completeExceptionally(new IllegalStateException("转推已停止"));
        terminate(session.process);
        log.info("FFmpeg 转推已停止 videoId={}", videoId);
    }

    private void readOutput(String videoId, Session session) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                session.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.matches("[a-zA-Z0-9_]+=.*")) {
                    if (line.startsWith("frame=")) {
                        try {
                            if (Long.parseLong(line.substring(6).trim()) > 0) {
                                session.ready.complete(null);
                            }
                        } catch (NumberFormatException ignored) {
                            // 非法进度数据不视为开流成功。
                        }
                    }
                    log.debug("FFmpeg 进度 videoId={} {}", videoId, line);
                } else {
                    if (!line.trim().isEmpty()) {
                        session.lastError = line.length() > 1200 ? line.substring(0, 1200) : line;
                    }
                    log.info("FFmpeg videoId={} {}", videoId, line);
                }
            }
            int exitCode = session.process.waitFor();
            if (!session.stopping) {
                log.error("FFmpeg 转推中断 videoId={} exitCode={} detail={}，下次 START 将重新获取源地址并启动",
                        videoId, exitCode, session.lastError);
            }
            session.ready.completeExceptionally(new IllegalStateException(
                    "FFmpeg 未能启动转推，exitCode=" + exitCode + ": " + session.lastError));
        } catch (Exception error) {
            session.ready.completeExceptionally(error);
            if (!session.stopping) {
                log.error("FFmpeg 输出读取失败 videoId={}", videoId, error);
            }
        } finally {
            terminate(session.process);
            sessions.remove(videoId, session);
        }
    }

    List<String> command(String sourceUrl, String pushUrl) {
        String sourceScheme = requireScheme(sourceUrl, Set.of("rtmp", "rtmps", "rtsp", "http", "https"), "源地址");
        requireScheme(pushUrl, Set.of("rtmp", "rtmps"), "推流地址");
        if (sourceUrl.equals(pushUrl)) {
            throw new IllegalArgumentException("源地址与推流地址不能相同");
        }
        AutelGatewayProperties.Live config = properties.getLive();
        String timeout = String.valueOf(config.getRelayIoTimeout().toMillis() * 1000L);
        List<String> args = new ArrayList<>(List.of(config.getFfmpegPath(), "-hide_banner", "-nostdin",
                "-loglevel", "info", "-nostats", "-stats_period", "0.5", "-progress", "pipe:1",
                "-rw_timeout", timeout));
        if ("rtsp".equals(sourceScheme)) {
            args.addAll(List.of("-rtsp_transport", "tcp"));
        }
        args.addAll(List.of("-i", sourceUrl, "-rw_timeout", timeout));
        if (config.isTranscode()) {
            args.addAll(List.of("-vcodec", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
                    "-pix_fmt", "yuv420p", "-g", "50"));
        } else {
            args.addAll(List.of("-vcodec", "copy"));
        }
        args.addAll(List.of("-an", "-f", "flv", pushUrl));
        return args;
    }

    Process launch(List<String> command) throws IOException {
        // 不经过 shell，URL 中的 & 等字符作为单个参数原样传递。
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static String requireScheme(String url, Set<String> schemes, String name) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !schemes.contains(scheme)) {
            throw new IllegalArgumentException("FFmpeg " + name + "协议不支持");
        }
        return scheme;
    }

    private static void terminate(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        new ArrayList<>(sessions.keySet()).forEach(this::stop);
    }

    @RequiredArgsConstructor
    private static final class Session {
        private final Process process;
        private final String pushUrl;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private volatile boolean stopping;
        private volatile String lastError = "尚无 FFmpeg 输出";
    }
}
