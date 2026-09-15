package com.jingansi.autel.gateway.live;

import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 可选的真实 FFmpeg 回环测试，不访问天穹或上层平台。 */
@EnabledIfSystemProperty(named = "ffmpeg.smoke", matches = "true")
class FfmpegRelaySmokeTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void relaysHttpFlvIntoLocalRtmpAndStops(boolean transcode) throws Exception {
        String ffmpeg = System.getProperty("ffmpeg.path", "ffmpeg");
        Path source = directory.resolve("source.flv");
        Path recorded = directory.resolve("recorded.flv");
        Path generatorLog = directory.resolve("generator.log");
        Process generator = new ProcessBuilder(ffmpeg, "-hide_banner", "-f", "lavfi", "-i",
                "testsrc2=size=160x120:rate=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "30", "-c:a", "aac", "-c:v", "libx264",
                "-preset", "ultrafast", "-tune", "zerolatency", "-f", "flv", source.toString())
                .redirectErrorStream(true).redirectOutput(generatorLog.toFile()).start();
        try {
            assertThat(generator.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(generator.exitValue()).withFailMessage(Files.readString(generatorLog)).isZero();
        } finally {
            generator.destroyForcibly();
        }

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String destination = "rtmp://127.0.0.1:" + port + "/live/test";
        Path receiverLog = directory.resolve("receiver.log");
        Process receiver = new ProcessBuilder(ffmpeg, "-hide_banner", "-listen", "1", "-i", destination,
                "-c", "copy", "-f", "flv", recorded.toString())
                .redirectErrorStream(true).redirectOutput(receiverLog.toFile()).start();
        AutelGatewayProperties properties = new AutelGatewayProperties();
        properties.getLive().setFfmpegPath(ffmpeg);
        properties.getLive().setTranscode(transcode);
        properties.getLive().setRelayStartTimeout(Duration.ofSeconds(15));
        FfmpegStreamRelay relay = new FfmpegStreamRelay(properties);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "video/x-flv")
                    .setBody(new Buffer().write(Files.readAllBytes(source)))
                    .throttleBody(4096, 100, TimeUnit.MILLISECONDS));
            server.start();
            // 仅等待本地测试接收端绑定监听端口，不访问真实设备。
            Thread.sleep(500);
            assertThat(receiver.isAlive()).withFailMessage(Files.readString(receiverLog)).isTrue();

            relay.start("TEST/CAM/normal-0", server.url("/source.flv?token=a&pid=b").toString(), destination);
            assertThat(relay.isRunning("TEST/CAM/normal-0", destination)).isTrue();
            Thread.sleep(1500);
            relay.stop("TEST/CAM/normal-0");
            assertThat(relay.isRunning("TEST/CAM/normal-0", destination)).isFalse();
            assertThat(receiver.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(recorded).exists();
            assertThat(Files.size(recorded)).isGreaterThan(1000);

            // 完整解码接收到的视频，验证不仅是建立了 RTMP 连接。
            Path decodeLog = directory.resolve("decode.log");
            Process decoder = new ProcessBuilder(ffmpeg, "-hide_banner", "-xerror", "-i", recorded.toString(),
                    "-map", "0:v:0", "-f", "null", "-")
                    .redirectErrorStream(true).redirectOutput(decodeLog.toFile()).start();
            try {
                assertThat(decoder.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(decoder.exitValue()).withFailMessage(Files.readString(decodeLog)).isZero();
                assertThat(Files.readString(decodeLog)).contains("Video: h264", "frame=")
                        .doesNotContain("Audio:");
            } finally {
                decoder.destroyForcibly();
            }
        } finally {
            relay.close();
            receiver.destroyForcibly();
            receiver.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
