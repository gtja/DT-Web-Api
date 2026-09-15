package com.jingansi.autel.gateway.live;

import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class FfmpegStreamRelayTest {
    private static final String SOURCE = "https://autel.example/live.flv?token=a&pid=b";
    private static final String PUSH = "rtmp://media.example/live/test";
    private final AutelGatewayProperties properties = new AutelGatewayProperties();
    private final FfmpegStreamRelay relay = spy(new FfmpegStreamRelay(properties));

    @AfterEach
    void close() {
        relay.close();
    }

    @Test
    void matchesVerifiedVideoOnlyCommandAndPreservesSignedUrlAsOneArgument() {
        assertThat(relay.command(SOURCE, PUSH))
                .containsSequence("-i", SOURCE)
                .endsWith("-vcodec", "copy", "-an", "-f", "flv", PUSH)
                .doesNotContain("-map", "-c:a", "aac", "-ar", "-ac", "-flvflags");
    }

    @Test
    void supportsH264TranscodingAndRtspOverTcp() {
        properties.getLive().setTranscode(true);
        assertThat(relay.command("rtsp://autel.example/live", PUSH))
                .containsSequence("-rtsp_transport", "tcp")
                .containsSequence("-vcodec", "libx264")
                .containsSequence("-pix_fmt", "yuv420p")
                .endsWith("-an", "-f", "flv", PUSH);
    }

    @Test
    void rejectsUnsupportedUrlsAndSelfRelay() {
        assertThatThrownBy(() -> relay.command("file:///etc/passwd", PUSH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relay.command(SOURCE, "https://media.example/live"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relay.command(PUSH, PUSH))
                .hasMessageContaining("不能相同");
    }

    @Test
    void waitsForFramesReusesRunningProcessAndStopsIdempotently() throws Exception {
        StubProcess process = new StubProcess("frame=1\nprogress=continue\n", true);
        doReturn(process).when(relay).launch(anyList());

        relay.start("DOCK/CAM/normal-0", SOURCE, PUSH);
        assertThat(relay.isRunning("DOCK/CAM/normal-0", PUSH)).isTrue();
        relay.start("DOCK/CAM/normal-0", SOURCE, PUSH);
        verify(relay, times(1)).launch(anyList());

        relay.stop("DOCK/CAM/normal-0");
        relay.stop("DOCK/CAM/normal-0");
        assertThat(process.isAlive()).isFalse();
        assertThat(relay.isRunning("DOCK/CAM/normal-0", PUSH)).isFalse();
    }

    @Test
    void timeoutKillsProcessWithoutOutputFrames() throws Exception {
        properties.getLive().setRelayStartTimeout(Duration.ofMillis(100));
        StubProcess process = new StubProcess("frame=0\n", true);
        doReturn(process).when(relay).launch(anyList());

        assertThatThrownBy(() -> relay.start("DOCK/CAM/normal-0", SOURCE, PUSH))
                .hasMessageContaining("启动超时");
        assertThat(process.isAlive()).isFalse();
        assertThat(relay.isRunning("DOCK/CAM/normal-0", PUSH)).isFalse();
    }

    @Test
    void exitBeforeFramesReturnsFfmpegError() throws Exception {
        doReturn(new StubProcess("Connection refused\n", false)).when(relay).launch(anyList());
        assertThatThrownBy(() -> relay.start("DOCK/CAM/normal-0", SOURCE, PUSH))
                .hasRootCauseMessage("FFmpeg 未能启动转推，exitCode=1: Connection refused");
    }

    @Test
    void rejectsDestinationCollisionAndClosesAllProcesses() throws Exception {
        StubProcess dock = new StubProcess("frame=1\n", true);
        StubProcess aircraft = new StubProcess("frame=1\n", true);
        doReturn(dock, aircraft).when(relay).launch(anyList());
        relay.start("DOCK/CAM/normal-0", SOURCE, PUSH);
        assertThatThrownBy(() -> relay.start("AIR/CAM/zoom-0", SOURCE, PUSH))
                .hasMessageContaining("另一视频通道占用");
        assertThat(dock.isAlive()).isTrue();

        relay.start("AIR/CAM/zoom-0", SOURCE, PUSH + "2");
        relay.close();
        assertThat(dock.isAlive()).isFalse();
        assertThat(aircraft.isAlive()).isFalse();
        assertThatThrownBy(() -> relay.start("DOCK/CAM/normal-0", SOURCE, PUSH))
                .hasMessageContaining("已关闭");
    }

    /** 模拟持续直播的输出：初始日志读完后阻塞，直到进程被停止。 */
    private static final class StubProcess extends Process {
        private final CountDownLatch exited;
        private final InputStream output;

        private StubProcess(String lines, boolean alive) {
            exited = new CountDownLatch(alive ? 1 : 0);
            output = new SequenceInputStream(
                    new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)),
                    new InputStream() {
                        @Override
                        public int read() throws IOException {
                            try {
                                exited.await();
                                return -1;
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IOException(error);
                            }
                        }
                    });
        }

        @Override
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }

        @Override
        public InputStream getInputStream() { return output; }

        @Override
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }

        @Override
        public int waitFor() throws InterruptedException { exited.await(); return 1; }

        @Override
        public int exitValue() {
            if (isAlive()) {
                throw new IllegalThreadStateException();
            }
            return 1;
        }

        @Override
        public void destroy() { exited.countDown(); }

        @Override
        public boolean isAlive() { return exited.getCount() > 0; }
    }
}
