package com.jingansi.autel.gateway.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveChannelCatalogTest {

    @Test
    void shouldSelectFirstActiveCapacityChannelRegardlessOfResponseOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode capacity = mapper.readTree("{\"data\":["
                + "{\"video_id\":\"OTHER/CAM/normal-0\",\"active\":true,\"live_streams\":[{\"type\":\"rtmp\",\"url\":\"rtmp://wrong/live\"}]},"
                + "{\"video_id\":\"DOCK/10165-0-1/normal-0\",\"active\":true,\"live_streams\":[{\"type\":\"rtmp\",\"url\":\"rtmp://autel/second\"}]},"
                + "{\"video_id\":\"DOCK/10165-0-0/normal-0\",\"active\":true,\"live_streams\":[{\"type\":\"rtmp\",\"url\":\"rtmp://autel/first\"}]}]}");
        FakeAutelLivePort port = new FakeAutelLivePort(mapper.createObjectNode(), capacity);
        LiveChannelCatalog catalog = new LiveChannelCatalog(port, properties());

        assertThat(catalog.playbackSource("DOCK", "live"))
                .isEqualTo(new PlaybackSource("DOCK/10165-0-0/normal-0", "rtmp://autel/first"));
        assertThat(port.capacityRequests).isEqualTo(1);
        assertThat(port.statusRequests).isZero();
        assertThat(catalog.playbackSource("DOCK", "DOCK/10165-0-1/normal-0"))
                .isEqualTo(new PlaybackSource("DOCK/10165-0-1/normal-0", "rtmp://autel/second"));
    }

    @Test
    void shouldSkipInactiveChannelsForBusinessIdButNeverFallbackForExplicitId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode capacity = mapper.readTree("{\"data\":["
                + "{\"video_id\":\"DOCK/10165-0-0/normal-0\",\"active\":false,\"live_streams\":[{\"type\":\"rtmp\",\"url\":\"rtmp://autel/first\"}]},"
                + "{\"video_id\":\"DOCK/10165-0-1/normal-0\",\"active\":true,\"live_streams\":[{\"type\":\"rtmp\",\"url\":\"rtmp://autel/second\"}]}]}");
        LiveChannelCatalog catalog = new LiveChannelCatalog(new FakeAutelLivePort(mapper.createObjectNode(), capacity), properties());
        assertThat(catalog.playbackSource("DOCK", "live"))
                .isEqualTo(new PlaybackSource("DOCK/10165-0-1/normal-0", "rtmp://autel/second"));
        assertThatThrownBy(() -> catalog.playbackSource("DOCK", "DOCK/10165-0-0/normal-0"))
                .hasMessageContaining("尚未开流");
    }

    @Test
    void shouldReportNoActiveChannelsAndRejectOtherDevices() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String data : new String[]{"[]", "null", "[{\"video_id\":\"DOCK/CAM/ir-0\",\"active\":false}]",
                "[{\"video_id\":\"OTHER/CAM/zoom-0\",\"active\":true}]"}) {
            FakeAutelLivePort port = new FakeAutelLivePort(mapper.createObjectNode(), mapper.readTree("{\"data\":" + data + "}"));
            LiveChannelCatalog catalog = new LiveChannelCatalog(port, properties());
            assertThatThrownBy(() -> catalog.playbackSource("DOCK", "live"))
                    .hasMessageContaining("没有已开流通道");
            assertThat(port.starts + port.switches + port.stops).isZero();
        }
        FakeAutelLivePort port = new FakeAutelLivePort(mapper.createObjectNode(), mapper.createObjectNode());
        assertThatThrownBy(() -> new LiveChannelCatalog(port, properties()).playbackSource("DOCK", "OTHER/CAM/zoom-0"))
                .hasMessageContaining("当前设备");
        assertThat(port.capacityRequests).isZero();
    }

    @Test
    void shouldRejectBusinessIdAtPlaybackBoundaryAndDistinguishMissingChannel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode capacity = mapper.readTree("{\"data\":[{\"video_id\":\"DOCK/OTHER/normal-0\",\"active\":true}]}");
        LiveChannelCatalog catalog = new LiveChannelCatalog(
                new FakeAutelLivePort(mapper.createObjectNode(), capacity), properties());
        assertThatThrownBy(() -> catalog.playbackUrl("DOCK", "live"))
                .hasMessageContaining("真实通道 ID");
        assertThatThrownBy(() -> catalog.playbackUrl("DOCK", "DOCK/CAM/normal-0"))
                .hasMessageContaining("未返回目标通道");
        assertThatThrownBy(() -> catalog.resolve(DeviceTarget.DOCK, "live"))
                .hasMessageContaining("未找到视频通道");
    }

    @Test
    void shouldSelectExactActiveChannelPlaybackUrlAndPreferRtmp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode capacity = mapper.readTree("{\"data\":["
                + "{\"video_id\":\"DOCK/OTHER/normal-0\",\"active\":true,\"live_streams\":["
                + "{\"type\":\"rtmp\",\"url\":\"rtmp://wrong/live\"}]},"
                + "{\"video_id\":\"DOCK/CAM/normal-0\",\"active\":true,\"url\":\"rtmp://autel/publish\",\"live_streams\":["
                + "{\"type\":\"flv\",\"url\":\"https://autel/live.flv\"},"
                + "{\"type\":\"rtmp\",\"videoId\":\"OTHER/CAM/normal-0\",\"url\":\"rtmp://wrong/live\"},"
                + "{\"type\":\"rtmp\",\"videoId\":\"DOCK/CAM/normal-0\",\"url\":\"rtmp://autel/play?token=a&pid=b\"}]}]}");
        LiveChannelCatalog catalog = new LiveChannelCatalog(new FakeAutelLivePort(mapper.createObjectNode(), capacity), properties());
        assertThat(catalog.playbackUrl("DOCK", "DOCK/CAM/normal-0"))
                .isEqualTo("rtmp://autel/play?token=a&pid=b");
    }

    @Test
    void shouldUseHttpFlvWhenRtmpPlaybackIsAbsent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode capacity = mapper.readTree("{\"data\":[{\"video_id\":\"DOCK/CAM/normal-0\",\"active\":true,\"live_streams\":["
                + "{\"type\":\"rtmp\",\"url\":\"file:///tmp/not-a-stream\"},"
                + "{\"type\":\"flv\",\"url\":\"https://autel/live.flv?token=a&pid=b\"}]}]}");
        LiveChannelCatalog catalog = new LiveChannelCatalog(new FakeAutelLivePort(mapper.createObjectNode(), capacity), properties());
        assertThat(catalog.playbackUrl("DOCK", "DOCK/CAM/normal-0"))
                .isEqualTo("https://autel/live.flv?token=a&pid=b");
    }

    @Test
    void shouldRejectInactiveChannelAndNotFallBackToPublishUrl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (boolean active : new boolean[]{false, true}) {
            JsonNode capacity = mapper.readTree("{\"data\":[{\"video_id\":\"DOCK/CAM/normal-0\",\"active\":" + active
                    + ",\"url\":\"rtmp://autel/publish\"}]}");
            FakeAutelLivePort port = new FakeAutelLivePort(mapper.createObjectNode(), capacity);
            LiveChannelCatalog catalog = new LiveChannelCatalog(port, properties());
            assertThatThrownBy(() -> catalog.playbackUrl("DOCK", "DOCK/CAM/normal-0"))
                    .hasMessageContaining(active ? "没有可转推" : "尚未开流");
            assertThat(port.starts + port.stops + port.switches).isZero();
        }
    }

    @Test
    void shouldBuildVideoListFromLiveStatusWithoutHardCodedCameraIndexes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode status = objectMapper.readTree("{\"code\":\"0\",\"succ\":true,\"data\":{" +
                "\"masterLiveStatus\":[{" +
                "\"video_id\":\"DOCK/10165-0-0/normal-0\",\"video_type\":\"normal\"," +
                "\"camera_index\":\"10165-0-0\",\"camera_position\":0," +
                "\"switchable_video_types\":[\"normal\"]}]," +
                "\"slaveLiveStatus\":[{" +
                "\"video_id\":\"AIR/10732-0-0/zoom-0\",\"video_type\":\"zoom\"," +
                "\"camera_index\":\"10732-0-0\"," +
                "\"switchable_video_types\":[\"zoom\",\"ir\",\"wide\"]}]}}" );
        AutelGatewayProperties properties = properties();
        FakeAutelLivePort port = new FakeAutelLivePort(status, objectMapper.readTree("{\"data\":[]}"));
        LiveChannelCatalog catalog = new LiveChannelCatalog(port, properties);

        catalog.refresh();

        assertThat(catalog.resolve(DeviceTarget.DOCK, "DOCK/10165-0-0/normal-0").getVideoId())
                .isEqualTo("DOCK/10165-0-0/normal-0");
        assertThat(catalog.resolve(DeviceTarget.AIRCRAFT, "AIR/10732-0-0/zoom-0").getVideoId())
                .isEqualTo("AIR/10732-0-0/zoom-0");
        Map<String, Object> dockProperty = catalog.videoListProperty(DeviceTarget.DOCK);
        Map<?, ?> dockItem = (Map<?, ?>) ((java.util.List<?>) dockProperty.get("videoList")).get(0);
        assertThat(dockItem.get("videoId")).isEqualTo("live");
        assertThat(dockItem.get("videoTypes")).isInstanceOf(Map.class);
        Map<String, Object> aircraftProperty = catalog.videoListProperty(DeviceTarget.AIRCRAFT);
        Map<?, ?> aircraftItem = (Map<?, ?>) ((java.util.List<?>) aircraftProperty.get("videoList")).get(0);
        assertThat(aircraftItem.get("videoId")).isEqualTo("live");
        assertThat(aircraftItem.get("videoTypes")).isInstanceOf(java.util.List.class);
    }

    @Test
    void shouldPreserveCapacityUrlOnActiveChannel() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode capacity = objectMapper.readTree("{\"data\":[{" +
                "\"video_id\":\"AIR/CAM/zoom-0\",\"video_type\":\"zoom\"," +
                "\"camera_index\":\"CAM\",\"active\":true," +
                "\"url\":\"rtmp://media/push/current\"}]}" );
        FakeAutelLivePort port = new FakeAutelLivePort(
                objectMapper.readTree("{\"data\":{}}"), capacity);
        LiveChannelCatalog catalog = new LiveChannelCatalog(port, properties());
        LiveChannel requested = new LiveChannel("AIR", "AIR/CAM/zoom-0", "zoom",
                "CAM", null, Collections.singletonList("zoom"));

        LiveChannel active = catalog.activeChannels(requested).get(0);

        assertThat(active.getUrl()).isEqualTo("rtmp://media/push/current");
    }

    static AutelGatewayProperties properties() {
        AutelGatewayProperties properties = new AutelGatewayProperties();
        properties.getDevices().setDockSn("DOCK");
        properties.getDevices().setAircraftSn("AIR");
        return properties;
    }

    static final class FakeAutelLivePort implements AutelLivePort {
        private final JsonNode status;
        private final JsonNode capacity;
        String startedUrl;
        String switchedUrl;
        String switchedVideoId;
        String switchedVideoType;
        String stoppedVideoId;
        int starts;
        int switches;
        int stops;
        int capacityRequests;
        int statusRequests;

        FakeAutelLivePort(JsonNode status, JsonNode capacity) {
            this.status = status;
            this.capacity = capacity;
        }

        @Override
        public JsonNode getLiveStatus(String dockSn, String aircraftSn) { statusRequests++; return status; }
        @Override
        public JsonNode getCapacity(String sourceSn) { capacityRequests++; return capacity; }
        @Override
        public JsonNode start(LiveChannel channel, String pushUrl, int urlType, int quality) {
            starts++;
            startedUrl = pushUrl;
            setActive(channel, true, pushUrl);
            return capacity;
        }
        @Override
        public JsonNode switchStream(LiveChannel channel, String pushUrl, int urlType, int quality) {
            switches++;
            switchedVideoType = channel.getVideoType();
            switchedUrl = pushUrl;
            switchedVideoId = channel.getVideoId();
            JsonNode data = capacity.path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    if (item.isObject()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("active", false);
                    }
                }
            }
            setActive(channel, true, pushUrl);
            return capacity;
        }
        @Override
        public void stop(LiveChannel channel, int urlType, int quality) {
            stops++;
            stoppedVideoId = channel.getVideoId();
            setActive(channel, false, null);
        }

        private void setActive(LiveChannel channel, boolean active, String url) {
            JsonNode data = capacity.path("data");
            if (!data.isArray()) {
                return;
            }
            for (JsonNode item : data) {
                if (channel.getVideoId().equals(item.path("video_id").asText()) && item.isObject()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("active", active);
                    if (url != null) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("url", url);
                    }
                }
            }
        }
    }
}
