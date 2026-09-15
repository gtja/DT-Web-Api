package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockPropertyMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DockPropertyMapper mapper = new DockPropertyMapper();

    @Test
    void shouldMapEveryConfirmedDockModeAndAirConditioningMode() throws Exception {
        for (int mode = 0; mode <= 4; mode++) {
            assertThat(mapper.map(objectMapper.readTree("{\"mode_code\":" + mode + ",\"state\":43}")))
                    .containsEntry("modeCode", mode)
                    .containsEntry("modeDisplay", String.valueOf(mode));
        }
        for (int mode = 0; mode <= 3; mode++) {
            assertThat(mapper.map(objectMapper.readTree("{\"air_conditioner\":{\"air_conditioner_state\":" + mode + "}}")))
                    .containsEntry("airConditionerMode", mode);
        }
        assertThat(mapper.map(objectMapper.readTree("{\"mode_code\":5,\"state\":0}")))
                .doesNotContainKeys("modeCode", "modeDisplay");
        for (int mode = 4; mode <= 9; mode++) {
            assertThat(mapper.map(objectMapper.readTree("{\"air_conditioner\":{\"air_conditioner_state\":" + mode + "}}")))
                    .doesNotContainKey("airConditionerMode");
        }
    }

    @Test
    void shouldKeepIndoorAndOutdoorTemperaturesSeparate() throws Exception {
        assertThat(mapper.map(objectMapper.readTree(
                "{\"temperature\":25.8,\"environment_temperature\":27.7,\"mode_code\":0}")))
                .containsEntry("temperature", 25.8D)
                .containsEntry("environmentTemperature", 27.7D)
                .containsEntry("modeCode", 0)
                .containsEntry("modeDisplay", "0");
        assertThat(mapper.map(objectMapper.readTree("{\"temperature\":0}")))
                .containsEntry("temperature", 0);
        assertThat(mapper.map(objectMapper.readTree("{\"temperature\":-5.5}")))
                .containsEntry("temperature", -5.5D);
        assertThat(mapper.map(objectMapper.readTree("{\"environment_temperature\":27.7}")))
                .doesNotContainKeys("temperature", "modeDisplay");
    }

    @Test
    void shouldNotInventFrontendValuesForMissingOrInvalidSource() throws Exception {
        for (String source : new String[]{"{}", "{\"mode_code\":null,\"temperature\":null}",
                "{\"mode_code\":0.5,\"temperature\":\"NaN\"}",
                "{\"mode_code\":4294967296,\"temperature\":\"Infinity\"}",
                "{\"mode_code\":-1,\"temperature\":{\"value\":25}}"}) {
            assertThat(mapper.map(objectMapper.readTree(source)))
                    .doesNotContainKeys("modeCode", "modeDisplay", "temperature");
        }
    }

    @Test
    void shouldMapSortiesLinkModeAndAircraftPowerStateWithoutConfusingOnlineWithInDock() throws Exception {
        Map<String, Object> result = mapper.map(objectMapper.readTree("{\"work_sorties\":15,"
                + "\"wireless_link\":{\"link_workmode\":1},\"sub_device\":{\"device_online_status\":1}}"));
        assertThat(result).containsEntry("jobNumber", 15).containsEntry("linkWorkMode", 1)
                .containsEntry("droneOpenProcess", 2).doesNotContainKeys("droneInDock", "isInDock");
        assertThat(mapper.map(objectMapper.readTree("{\"sub_device\":{\"device_online_status\":0}}")))
                .containsEntry("droneOpenProcess", 3);
    }

    @Test
    void shouldMapOnlySemanticallyCompatibleDockFields() throws Exception {
        JsonNode source = objectMapper.readTree("{\n" +
                "  \"height\": 6.95,\n" +
                "  \"wind_speed\": 0.08,\n" +
                "  \"cover_state\": 1,\n" +
                "  \"putter_state\": 3,\n" +
                "  \"network_state\": {\"type\": 2, \"quality\": 1},\n" +
                "  \"drone_in_dock\": 1,\n" +
                "  \"drone_charge_state\": {\"capacity_percent\": 99, \"state\": 0},\n" +
                "  \"position_state\": {\"is_fixed\": 2, \"quality\": 4, \"gps_number\": 8, \"rtk_number\": 43},\n" +
                "  \"alternate_land_point\": {\"longitude\": 114.2, \"latitude\": 23.0, \"safe_land_height\": 20, \"is_configured\": 1},\n" +
                "  \"air_conditioner\": {\"air_conditioner_state\": 8},\n" +
                "  \"unknown\": 123\n" +
                "}");

        Map<String, Object> result = mapper.map(source);

        assertThat(result)
                .containsEntry("height", 6.95D)
                .containsEntry("windSpeed", 0.08D)
                .containsEntry("coverState", 2)
                .containsEntry("putterState", 4)
                .containsEntry("networkType", 1)
                .containsEntry("droneInDock", 1)
                .containsEntry("isInDock", 1)
                .doesNotContainKeys("networkQuality", "airConditionerMode", "unknown");
        assertThat(struct(result, "droneChargeState"))
                .containsEntry("capacityPercent", 99)
                .containsEntry("state", 1);
        assertThat(struct(result, "positionState"))
                .containsEntry("isFixed", 2)
                .containsEntry("quality", 4)
                .containsEntry("gpsNumber", 8)
                .containsEntry("rtkNumber", 43);
    }

    @Test
    void shouldDropHalfOpenBecauseTargetModelCannotRepresentItsDirection() throws Exception {
        Map<String, Object> result = mapper.map(objectMapper.readTree(
                "{\"cover_state\":2,\"putter_state\":2}"));

        assertThat(result).doesNotContainKeys("coverState", "putterState");
    }

    @Test
    void shouldRejectFractionalAndOverflowValuesForIntegerEnums() throws Exception {
        Map<String, Object> result = mapper.map(objectMapper.readTree(
                "{\"rainfall\":0.9,\"cover_state\":4294967296,\"drone_in_dock\":0.5}"));

        assertThat(result).doesNotContainKeys("rainfall", "coverState", "droneInDock", "isInDock");
    }

    @Test
    void shouldNotPublishStructUntilEveryRequiredChildIsAvailable() throws Exception {
        Map<String, Object> result = mapper.map(objectMapper.readTree(
                "{\"position_state\":{\"is_fixed\":2}," +
                        "\"storage\":{\"total\":1024}," +
                        "\"drone_charge_state\":{\"capacity_percent\":80}}"));

        assertThat(result).doesNotContainKeys("positionState", "storage", "droneChargeState");
    }

    @Test
    void shouldEnforceDockPositionBoundsAndOmitStorageWithUnknownUnit() throws Exception {
        Map<String, Object> boundary = mapper.map(objectMapper.readTree(
                "{\"position_state\":{\"is_fixed\":2,\"quality\":5," +
                        "\"gps_number\":200,\"rtk_number\":200}," +
                        "\"storage\":{\"total\":2147483648,\"used\":2147483648}}"));

        assertThat(struct(boundary, "positionState"))
                .containsEntry("gpsNumber", 200)
                .containsEntry("rtkNumber", 200);
        assertThat(boundary).doesNotContainKey("storage");

        Map<String, Object> outOfRange = mapper.map(objectMapper.readTree(
                "{\"position_state\":{\"is_fixed\":2,\"quality\":5," +
                        "\"gps_number\":201,\"rtk_number\":200}," +
                        "\"storage\":{\"total\":2147483649,\"used\":1}}"));
        assertThat(outOfRange).doesNotContainKeys("positionState", "storage");

    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> struct(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }
}
