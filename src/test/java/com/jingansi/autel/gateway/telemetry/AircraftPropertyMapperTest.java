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
                .doesNotContainKeys("modeCode", "zoomFactor", "totalFlightTime")
                .containsEntry("height", 3.25D)
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
        assertThat(struct(result, "storage")).containsEntry("total", 2048).containsEntry("used", 256);
        assertThat(struct(result, "positionState")).hasSize(4);
        assertThat(result).doesNotContainKey("battery");
    }

    @Test
    void shouldEnforceAircraftPositionAndStorageBounds() throws Exception {
        Map<String, Object> boundary = mapper.map(objectMapper.readTree("{" +
                "\"position_state\":{\"is_fixed\":2,\"quality\":5," +
                "\"gps_number\":10000,\"rtk_number\":10000}," +
                "\"storage\":{\"total\":2147483647,\"used\":2147483647}" +
                "}"));

        assertThat(struct(boundary, "positionState"))
                .containsEntry("gpsNumber", 10_000)
                .containsEntry("rtkNumber", 10_000);
        assertThat(struct(boundary, "storage")).containsEntry("total", Integer.MAX_VALUE);

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

    @Test
    void shouldNotTruncateLiveStatusEnums() throws Exception {
        assertThat(mapper.mapLiveStatus(objectMapper.readTree("["
                + "{\"video_id\":\"AIR/CAM/zoom-0\",\"status\":1,\"video_quality\":1.9}]"), "AIR"))
                .isEmpty();
        assertThat(mapper.mapLiveStatus(objectMapper.readTree("["
                + "{\"video_id\":\"AIR/CAM/zoom-0\",\"status\":1.9,\"video_quality\":1}]"), "AIR"))
                .isEmpty();
    }

    @Test
    void shouldMapRealDragonfishPayloadWithoutPayloadList() throws Exception {
        Map<String, Object> result = map("{\"cameras\":[{\"payload_index\":\"10732-0-0\","
                + "\"camera_mode\":0,\"ir_metering_mode\":0}],\"10732-0-0\":{"
                + "\"gimbal_pitch\":0,\"gimbal_yaw\":-46.46,\"thermal_current_palette_style\":4,\"thermal_gain_mode\":2}}");
        assertThat(result).containsEntry("gimbalPitch", 0).containsEntry("gimbalYaw", -46.46D)
                .containsEntry("thermalCurrentPaletteStyle", 6).containsEntry("thermalGainMode", 2)
                .containsEntry("irMeteringMode", "CLOSE");
    }

    @Test
    void shouldResolveStandalonePayloadButNotAmbiguousOrConflictingIndexes() throws Exception {
        assertThat(map("{\"10732-0-0\":{\"gimbal_pitch\":12}}"))
                .containsEntry("gimbalPitch", 12);
        assertThat(map("{\"10732-0-0\":{\"gimbal_pitch\":12},\"10732-0-1\":{\"gimbal_pitch\":34}}"))
                .doesNotContainKey("gimbalPitch");
        assertThat(map("{\"cameras\":[{\"payload_index\":\"10732-0-1\"}],"
                + "\"payloads\":[{\"payload_index\":\"10732-0-0\",\"sn\":\"CAM\"}],"
                + "\"10732-0-0\":{\"gimbal_pitch\":12}}"))
                .doesNotContainKeys("gimbalPitch", "cameraSn");
    }

    @Test
    void shouldMapFlightCounterWithoutUsingCurrentFlightCounters() throws Exception {
        assertThat(map("{\"total_flight_sorties\":256.0,\"fly_turns\":12,"
                + "\"total_armed_time\":50,\"total_armed_distance\":100,\"firmware_version\":\"12.1.3.7\"}"))
                .containsEntry("totalFlightSorties", 256).containsEntry("firmwareVersion", "12.1.3.7")
                .doesNotContainKeys("totalFlightTime", "totalFlightDistance");
        assertThat(map("{\"fly_turns\":256.0}")).containsEntry("totalFlightSorties", 256);
        assertThat(map("{\"total_flight_sorties\":256.5}")).doesNotContainKey("totalFlightSorties");
    }

    @Test
    void shouldRespectWindAndLaserValidity() throws Exception {
        assertThat(map("{\"wind_speed\":3.2,\"wind_speed_valid\":0,\"wind_direction\":5,"
                + "\"wind_direction_valid\":0,\"laser_distance_data\":{\"laser_distance\":100,\"laser_distance_is_valid\":0}}"))
                .doesNotContainKeys("windSpeed", "windDirection", "laserDistance");
        assertThat(map("{\"wind_speed\":3.2,\"wind_speed_valid\":1,\"wind_direction\":5,"
                + "\"wind_direction_valid\":1,\"laser_distance_data\":{\"laser_distance\":100,\"laser_distance_is_valid\":1}}"))
                .containsEntry("windSpeed", 3.2D).containsEntry("windDirection", 5).containsEntry("laserDistance", 100);
    }

    @Test
    void shouldSelectActiveStorageAndRejectImpossibleCapacity() throws Exception {
        assertThat(struct(map("{\"storage\":{\"total\":100,\"used\":10,\"current_used\":0},"
                + "\"sdcard_storage\":{\"total\":200,\"used\":20,\"current_used\":1}}"), "storage"))
                .containsEntry("total", 200).containsEntry("used", 20);
        assertThat(map("{\"storage\":{\"total\":100,\"used\":101}}"))
                .doesNotContainKey("storage");
    }

    @Test
    void shouldMapBatteryOnlyWithActualLandingPower() throws Exception {
        assertThat(struct(map("{\"battery\":{\"capacity_percent\":77,\"remain_flight_time\":4896,"
                + "\"landing_power\":10,\"return_home_power\":20}}"), "battery"))
                .containsEntry("capacityPercent", 77).containsEntry("landingPower", 10);
        assertThat(map("{\"battery\":{\"capacity_percent\":77,\"remain_flight_time\":4896},"
                + "\"serious_low_battery_warning_threshold\":10,\"low_battery_warning_threshold\":20}"))
                .doesNotContainKeys("battery", "returnhomePower");
    }

    @Test
    void shouldMapMeteringStructuresWithoutInventingLensExposureOrInvalidTemperature() throws Exception {
        Map<String, Object> result = map("{\"cameras\":[{\"payload_index\":\"10732-0-0\","
                + "\"zoom_focus_mode\":2,\"exposure_compensation\":1,\"shutter_speed\":\"1/30\",\"ir_metering_mode\":2,"
                + "\"ir_metering_point\":{\"x\":0.5,\"y\":0.4},\"ir_metering_area\":{\"width\":0.5,\"height\":0.2,"
                + "\"aver_temperature\":30,\"min_temperature_point\":{\"x\":0.2,\"y\":0.3,\"temperature\":25}}}]}");
        assertThat(result).containsEntry("irMeteringMode", "AREA").containsEntry("zoomFocusMode", 0)
                .doesNotContainKeys("zoomExposureValue", "wideExposureValue", "zoomShutterSpeed");
        assertThat(struct(result, "irMeteringPoint")).containsEntry("x", 0.5D);
        assertThat(struct(struct(result, "irMeteringArea"), "minTemperaturePoint"))
                .containsEntry("x", 0.2D).doesNotContainKey("temperature");
    }

    @Test
    void shouldRejectMalformedNumericValuesAndPreserveRealZeroCoordinates() throws Exception {
        assertThat(map("{\"longitude\":0,\"latitude\":0,\"horizontal_speed\":\"1e9999\","
                + "\"attitude_head\":1e99,\"activation_time\":1752585313781.5,\"vertical_speed\":9223372036854775808}"))
                .containsEntry("longitude", 0).containsEntry("latitude", 0)
                .doesNotContainKeys("horizontalSpeed", "verticalSpeed", "attitudeHead", "activationTime");
    }

    @Test
    void shouldMapConfirmedLostActionButNotUnspecifiedOutOfControlAction() throws Exception {
        assertThat(map("{\"rc_lost_action\":{\"state\":2}}")).containsEntry("lostAction", 2);
        assertThat(map("{\"rc_lost_action\":1}")).containsEntry("lostAction", 1);
        assertThat(map("{\"out_of_control_action\":0}")).doesNotContainKey("lostAction");
    }

    private Map<String, Object> map(String source) throws Exception {
        return mapper.map(objectMapper.readTree(source));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> struct(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }
}
