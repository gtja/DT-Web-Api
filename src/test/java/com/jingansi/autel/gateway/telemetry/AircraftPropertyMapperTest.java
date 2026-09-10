package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AircraftPropertyMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AircraftPropertyMapper mapper = new AircraftPropertyMapper();

    @Test
    void shouldFilterFieldsWithDifferentMeaningOrOutOfModelRange() throws Exception {
        JsonNode source = objectMapper.readTree("{\n" +
                "  \"height\": 13.523,\n" +
                "  \"elevation\": 3.25,\n" +
                "  \"attitude_head\": -167.78,\n" +
                "  \"activation_time\": 1752585313781,\n" +
                "  \"total_flight_time\": 2145847,\n" +
                "  \"mode_code\": 17,\n" +
                "  \"distance_limit_status\": {\"state\": 1, \"distance_limit\": 100000},\n" +
                "  \"position_state\": {\"quality\": 0, \"is_fixed\": 2, \"gps_number\": 8, \"rtk_number\": 43},\n" +
                "  \"cameras\": [{\"payload_index\":\"10802-0-0\",\"camera_mode\":0,\"zoom_factor\":1}],\n" +
                "  \"payloads\": [{\"payload_index\":\"10802-0-0\",\"sn\":\"CAMERA-SN\"}],\n" +
                "  \"10802-0-0\": {\"gimbal_pitch\":-30.5,\"gimbal_yaw\":88.2}\n" +
                "}");

        Map<String, Object> result = mapper.map(source);

        assertThat(result)
                .doesNotContainKeys("height", "modeCode", "zoomFactor", "totalFlightTime")
                .containsEntry("elevation", 3.25D)
                .containsEntry("attitudeHead", -168)
                .containsEntry("activationTime", 1_752_585_313)
                .containsEntry("cameraMode", 0)
                .containsEntry("cameraSn", "CAMERA-SN")
                .containsEntry("gimbalPitch", -30.5D)
                .containsEntry("gimbalYaw", 88.2D)
                .doesNotContainKeys("distanceLimitStatus", "positionState");
    }

    @Test
    void shouldMapOnlyCompatibleModeCodes() throws Exception {
        assertThat(mapper.map(objectMapper.readTree("{\"mode_code\":16}")))
                .containsEntry("modeCode", 16);
        assertThat(mapper.map(objectMapper.readTree("{\"mode_code\":39}")))
                .doesNotContainKey("modeCode");
    }

    @Test
    void shouldPublishOnlyCompleteRequiredStructsAndOmitIncompleteBattery() throws Exception {
        Map<String, Object> result = mapper.map(objectMapper.readTree("{" +
                "\"distance_limit_status\":{\"state\":1,\"distance_limit\":8000}," +
                "\"obstacle_avoidance\":{\"horizon\":1,\"upside\":0,\"downside\":1}," +
                "\"storage\":{\"total\":2048,\"used\":256}," +
                "\"position_state\":{\"is_fixed\":2,\"quality\":4,\"gps_number\":8,\"rtk_number\":43}," +
                "\"battery\":{\"capacity_percent\":80,\"remain_flight_time\":500}" +
                "}"));

        assertThat(struct(result, "distanceLimitStatus"))
                .containsEntry("state", 1).containsEntry("distanceLimit", 8000);
        assertThat(struct(result, "obstacleAvoidance")).hasSize(3);
        assertThat(result).doesNotContainKey("storage");
        assertThat(struct(result, "positionState")).hasSize(4);
        assertThat(result).doesNotContainKey("battery");
    }

    @Test
    void shouldEnforceAircraftPositionBoundsAndOmitStorageWithUnknownUnit() throws Exception {
        Map<String, Object> boundary = mapper.map(objectMapper.readTree("{" +
                "\"position_state\":{\"is_fixed\":2,\"quality\":5," +
                "\"gps_number\":10000,\"rtk_number\":10000}," +
                "\"storage\":{\"total\":2147483647,\"used\":2147483647}" +
                "}"));

        assertThat(struct(boundary, "positionState"))
                .containsEntry("gpsNumber", 10_000)
                .containsEntry("rtkNumber", 10_000);
        assertThat(boundary).doesNotContainKey("storage");

        Map<String, Object> outOfRange = mapper.map(objectMapper.readTree("{" +
                "\"position_state\":{\"is_fixed\":2,\"quality\":5," +
                "\"gps_number\":10001,\"rtk_number\":10000}," +
                "\"storage\":{\"total\":2147483648,\"used\":1}" +
                "}"));
        assertThat(outOfRange).doesNotContainKeys("positionState", "storage");

    }

    @Test
    void shouldMapAircraftQualityFromDockLiveStatus() throws Exception {
        JsonNode liveStatus = objectMapper.readTree("[" +
                "{\"video_id\":\"DOCK/CAM/normal-0\",\"status\":1,\"video_quality\":1}," +
                "{\"video_id\":\"AIR/CAM/zoom-0\",\"status\":1,\"video_quality\":2}" +
                "]");

        assertThat(mapper.mapLiveStatus(liveStatus, "AIR"))
                .containsEntry("videoQuality", 3);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> struct(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }
}
