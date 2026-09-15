package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AircraftFrontendPropertiesTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AircraftPropertyMapper mapper = new AircraftPropertyMapper();

    @Test
    void shouldAppendBatteryAltitudeAndAttitudeWithoutReplacingModelFields() throws Exception {
        Map<String, Object> result = map("{\"battery\":{\"capacity_percent\":77,\"remain_flight_time\":4896},"
                + "\"height\":-0.3,\"elevation\":0,\"attitude_head\":-46.46,\"attitude_pitch\":-0.09,\"attitude_roll\":0.47}");
        assertThat(result).containsEntry("electricity", 77).containsEntry("altitude", -0.3D)
                .containsEntry("height", 0).containsEntry("elevation", 0)
                .containsEntry("uavYaw", -46.46D).containsEntry("attitudeHead", -46)
                .containsEntry("uavPitch", -0.09D).containsEntry("attitudePitch", -0.09D)
                .containsEntry("uavRoll", 0.47D).containsEntry("attitudeRoll", 0.47D)
                .doesNotContainKeys("modeDisplay", "temperature", "battery");
        assertThat(struct(struct(result, "cloudApiOsdData"), "battery"))
                .containsEntry("capacity_percent", 77).containsEntry("remain_flight_time", 4896)
                .doesNotContainKeys("landing_power", "return_home_power");
    }

    @Test
    void shouldUseCurrentArmedCountersAndConvertSecondsToMillisecondsWithoutOverflow() throws Exception {
        assertThat(map("{\"total_armed_time\":120,\"total_armed_distance\":123.4,\"total_flight_time\":32871}"))
                .containsEntry("flyTime", 120_000L).containsEntry("flyDistance", 123.4D);
        assertThat(map("{\"total_armed_time\":2147483647}"))
                .containsEntry("flyTime", 2_147_483_647_000L);
        assertThat(map("{\"total_flight_time\":32871,\"total_flight_distance\":50000,\"remain_distance\":6000}"))
                .doesNotContainKeys("flyTime", "flyDistance");
    }

    @Test
    void shouldPreserveZeroAndRejectMissingOrMalformedValues() throws Exception {
        assertThat(map("{\"total_armed_time\":0,\"total_armed_distance\":0,\"battery\":{\"capacity_percent\":0},"
                + "\"height\":0,\"attitude_head\":0,\"position_state\":{\"gps_number\":0,\"quality\":0}}"))
                .containsEntry("flyTime", 0L).containsEntry("flyDistance", 0).containsEntry("electricity", 0)
                .containsEntry("altitude", 0).containsEntry("uavYaw", 0).containsEntry("satelliteNumber", 0);
        assertThat(map("{}")).isEmpty();
        assertThat(map("{\"total_armed_time\":1.5,\"total_armed_distance\":-1,\"battery\":{\"capacity_percent\":101},"
                + "\"height\":\"NaN\",\"attitude_head\":181,\"position_state\":{\"gps_number\":-1}}"))
                .doesNotContainKeys("flyTime", "flyDistance", "electricity", "cloudApiOsdData", "altitude", "uavYaw", "satelliteNumber");
    }

    @Test
    void shouldExposeSatelliteCountsEvenWhenOriginalPositionStructCannotBeSent() throws Exception {
        assertThat(map("{\"position_state\":{\"is_fixed\":3,\"quality\":0,\"gps_number\":8,\"rtk_number\":43}}"))
                .containsEntry("satelliteNumber", 8).containsEntry("rtkSatelliteNumber", 43)
                .doesNotContainKey("positionState");
    }

    @Test
    void shouldMapBatteryPopupFieldsWithoutInventingThresholdsOrLeakingRawPayload() throws Exception {
        Map<String, Object> result = map("{\"battery\":{\"capacity_percent\":77,\"landing_power\":10,\"return_home_power\":20,"
                + "\"vendor_extra\":\"ignore\",\"batteries\":[{\"index\":0,\"loop_times\":10,\"sn\":\"A\"},"
                + "{\"index\":1,\"loop_times\":20,\"sn\":\"B\"}]},\"low_battery_warning_threshold\":30}");
        Map<String, Object> battery = struct(struct(result, "cloudApiOsdData"), "battery");
        assertThat(battery).containsEntry("landing_power", 10).containsEntry("return_home_power", 20)
                .doesNotContainKey("vendor_extra");
        assertThat((List<?>) battery.get("batteries")).hasSize(2);
        assertThat(map("{\"low_battery_warning_threshold\":30,\"serious_low_battery_warning_threshold\":10}"))
                .doesNotContainKey("cloudApiOsdData");
    }

    @Test
    void shouldConvertValidLteSignalAndKeepUnavailableSignalMissing() throws Exception {
        assertThat(map("{\"lte_status\":4,\"lte_signal\":80,\"wireless_link\":{\"sdr_link_state\":1,\"sdr_quality\":4}}"))
                .containsEntry("signalMode", "4G").containsEntry("signalStrength", 4D).containsEntry("sdrStrength", 4);
        assertThat(map("{\"lte_status\":4,\"lte_signal\":0}"))
                .containsEntry("signalStrength", 0D);
        assertThat(map("{\"lte_status\":0,\"lte_signal\":0,\"wireless_link\":{\"sdr_link_state\":0,\"sdr_quality\":5}}"))
                .doesNotContainKeys("signalMode", "signalStrength", "sdrStrength");
        assertThat(map("{\"lte_status\":4,\"lte_signal\":101,\"wireless_link\":{\"fourth_generation_uavQuality\":80}}"))
                .doesNotContainKey("signalStrength");
    }

    @Test
    void shouldNotChangeLinkSelectionControlOrOnlineState() throws Exception {
        assertThat(map("{\"mode_code\":0,\"control_tag\":\"tag\",\"device_online_status\":1,\"links\":[\"auto\"]}"))
                .containsEntry("modeCode", 0)
                .doesNotContainKeys("controlTag", "links", "currentLink", "online", "status", "displayMode");
    }

    private Map<String, Object> map(String source) throws Exception {
        return mapper.map(json.readTree(source));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> struct(Map<String, Object> value, String key) {
        return (Map<String, Object>) value.get(key);
    }
}
