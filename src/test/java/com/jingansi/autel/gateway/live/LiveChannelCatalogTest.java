package com.jingansi.autel.gateway.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveChannelCatalogTest {

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

        assertThat(catalog.resolve(DeviceTarget.AIRCRAFT, "AIR/10732-0-0/zoom-0").getVideoId())
                .isEqualTo("AIR/10732-0-0/zoom-0");
        Map<String, Object> dockProperty = catalog.videoListProperty(DeviceTarget.DOCK);
        Map<?, ?> dockItem = (Map<?, ?>) ((java.util.List<?>) dockProperty.get("videoList")).get(0);
        assertThat(dockItem.get("videoTypes")).isInstanceOf(Map.class);
        Map<String, Object> aircraftProperty = catalog.videoListProperty(DeviceTarget.AIRCRAFT);
        Map<?, ?> aircraftItem = (Map<?, ?>) ((java.util.List<?>) aircraftProperty.get("videoList")).get(0);
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

        FakeAutelLivePort(JsonNode status, JsonNode capacity) {
            this.status = status;
            this.capacity = capacity;
        }

        @Override
        public JsonNode getLiveStatus(String dockSn, String aircraftSn) { return status; }
        @Override
        public JsonNode getCapacity(String sourceSn) { return capacity; }
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
