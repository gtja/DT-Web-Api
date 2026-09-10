package com.jingansi.autel.gateway.autel;

import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.telemetry.AutelMessageRouter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** SkyCC WebSocket 连接，断开后 5 秒重连。 */
@Component
@Slf4j
public class AutelWebSocketClient {

    private final OkHttpClient httpClient;
    private final AutelGatewayProperties properties;
    private final AutelTokenProvider tokenProvider;
    private final AutelMessageRouter messageRouter;
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "autel-ws-reconnect");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();

    public AutelWebSocketClient(@Qualifier("autelWebSocketHttpClient") OkHttpClient httpClient,
                                AutelGatewayProperties properties,
                                AutelTokenProvider tokenProvider,
                                AutelMessageRouter messageRouter) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.messageRouter = messageRouter;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            connect();
        }
    }

    private void connect() {
        reconnectScheduled.set(false);
        if (!running.get()) {
            return;
        }
        try {
            String token = tokenProvider.getToken();
            if (!running.get()) {
                return;
            }
            Request request = new Request.Builder()
                    .url(properties.getSkycc().getWebsocketUrl().toString())
                    .header("Sec-WebSocket-Protocol", token)
                    .build();
            log.info("SkyCC WS 开始连接 url={}", request.url());
            Listener listener = new Listener(token);
            WebSocket socket = httpClient.newWebSocket(request, listener);
            listener.attach(socket);
        } catch (RuntimeException error) {
            log.error("SkyCC WS 创建失败，5 秒后重连", error);
            disconnected();
        }
    }

    private void disconnected() {
        messageRouter.connectionLost();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            reconnectExecutor.schedule(this::connect, 5L, TimeUnit.SECONDS);
        } catch (RejectedExecutionException error) {
            reconnectScheduled.set(false);
            if (running.get()) {
                log.warn("SkyCC WS 重连任务提交失败", error);
            }
        }
    }

    private final class Listener extends WebSocketListener {
        private final String token;
        private boolean terminal;

        private Listener(String token) {
            this.token = token;
        }

        private synchronized void attach(WebSocket socket) {
            if (terminal || !running.get()) {
                socket.cancel();
                return;
            }
            install(socket);
        }

        @Override
        public synchronized void onOpen(WebSocket socket, Response response) {
            if (terminal || !running.get()) {
                socket.close(1000, "gateway stopped");
                return;
            }
            install(socket);
            log.info("SkyCC WS 连接成功");
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            if (socket != webSocket.get()) {
                return;
            }
            log.info("SkyCC WS 收到消息 payload={}", text);
            messageRouter.route(text);
        }

        @Override
        public synchronized void onClosed(WebSocket socket, int code, String reason) {
            terminal = true;
            boolean current = webSocket.compareAndSet(socket, null) || webSocket.get() == null;
            if (current && running.get()) {
                log.warn("SkyCC WS 已关闭 code={} reason={}", code, reason);
                disconnected();
            }
        }

        @Override
        public synchronized void onFailure(WebSocket socket, Throwable error, Response response) {
            terminal = true;
            if (response != null && response.code() == 401) {
                tokenProvider.invalidate(token);
            }
            boolean current = webSocket.compareAndSet(socket, null) || webSocket.get() == null;
            if (current && running.get()) {
                log.error("SkyCC WS 连接异常，5 秒后重连", error);
                disconnected();
            }
        }

        private void install(WebSocket socket) {
            WebSocket previous = webSocket.getAndSet(socket);
            if (previous != null && previous != socket) {
                previous.cancel();
            }
        }
    }

    @PreDestroy
    public void close() {
        running.set(false);
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.cancel();
        }
        reconnectExecutor.shutdownNow();
        log.info("SkyCC WS 客户端已关闭");
    }
}
