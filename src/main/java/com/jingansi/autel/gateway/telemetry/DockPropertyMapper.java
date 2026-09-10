package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class DockPropertyMapper implements PropertyMapper {

    @Override
    public Map<String, Object> map(JsonNode source) {
        Map<String, Object> target = new LinkedHashMap<>();
        copyNumberInRange(source, "height", target, "height", 0D, Double.MAX_VALUE);
        copyNumberInRange(source, "wind_speed", target, "windSpeed", 0D, Float.MAX_VALUE);
        copyRangeEnum(source.get("rainfall"), target, "rainfall", 0, 3);
        copyRangeEnum(source.get("supplement_light_state"), target, "supplementLightState", 0, 1);
        copyRangeEnum(source.get("emergency_stop_state"), target, "emergencyStopState", 0, 1);
        copyNumberInRange(source, "environment_humidity", target,
                "environmentHumidity", 0D, 100D);
        copyNumberInRange(source, "environment_temperature", target,
                "environmentTemperature", 0D, Float.MAX_VALUE);
        MappingSupport.integer(source.get("drone_in_dock"))
                .filter(value -> value == 0 || value == 1)
                .ifPresent(value -> {
                    target.put("droneInDock", value);
                    target.put("isInDock", value);
                });
        copyNumberInRange(source, "longitude", target, "longitude", 0D, 180D);
        copyNumberInRange(source, "latitude", target, "latitude", 0D, 180D);
        copyNumberInRange(source, "humidity", target, "humidity", 0D, 100D);

        mapExactEnum(source.path("air_conditioner").get("air_conditioner_state"),
                target, "airConditionerMode", 0, 0);
        mapTranslatedEnum(source.path("network_state").get("type"), target, "networkType",
                new int[][]{{1, 0}, {2, 1}});
        mapTranslatedEnum(source.get("cover_state"), target, "coverState",
                new int[][]{{0, 0}, {1, 2}, {3, 4}});
        mapTranslatedEnum(source.get("putter_state"), target, "putterState",
                new int[][]{{0, 0}, {1, 2}, {3, 4}});

        mapAlternateLandPoint(source.path("alternate_land_point"), target);
        mapPositionState(source.path("position_state"), target);
        mapChargeState(source.path("drone_charge_state"), target);
        return target;
    }

    private static void mapAlternateLandPoint(JsonNode source, Map<String, Object> target) {
        if (!source.isObject()) {
            return;
        }
        Map<String, Object> value = MappingSupport.struct();
        copyNumberInRange(source, "longitude", value, "longitude", -180D, 180D);
        copyNumberInRange(source, "latitude", value, "latitude", -180D, 180D);
        copyNumberInRange(source, "safe_land_height", value,
                "safeLandHeight", 0D, 5_000D);
        MappingSupport.integer(source.get("is_configured"))
                .filter(configured -> configured == 0 || configured == 1)
                .ifPresent(configured -> value.put("isConfigured", configured));
        MappingSupport.putCompleteStruct(target, "alternateLandPoint", value,
                "longitude", "latitude", "safeLandHeight", "isConfigured");
    }

    private static void mapPositionState(JsonNode source, Map<String, Object> target) {
        if (!source.isObject()) {
            return;
        }
        Map<String, Object> value = MappingSupport.struct();
        copyRangeEnum(source.get("is_fixed"), value, "isFixed", 0, 3);
        MappingSupport.integer(source.get("quality"))
                .filter(quality -> quality >= 1 && quality <= 5)
                .ifPresent(quality -> value.put("quality", quality));
        copyIntegerInRange(source.get("gps_number"), value, "gpsNumber", 200L);
        copyIntegerInRange(source.get("rtk_number"), value, "rtkNumber", 200L);
        MappingSupport.putCompleteStruct(target, "positionState", value,
                "isFixed", "quality", "gpsNumber", "rtkNumber");
    }

    private static void mapChargeState(JsonNode source, Map<String, Object> target) {
        if (!source.isObject()) {
            return;
        }
        Map<String, Object> value = MappingSupport.struct();
        MappingSupport.integer(source.get("capacity_percent"))
                .filter(capacity -> capacity >= 0 && capacity <= 100)
                .ifPresent(capacity -> value.put("capacityPercent", capacity));
        mapTranslatedEnum(source.get("state"), value, "state", new int[][]{{0, 1}, {1, 0}});
        MappingSupport.putCompleteStruct(target, "droneChargeState", value,
                "capacityPercent", "state");
    }

    private static void mapExactEnum(JsonNode source,
                                     Map<String, Object> target,
                                     String targetName,
                                     int sourceValue,
                                     int targetValue) {
        MappingSupport.integer(source).filter(value -> value == sourceValue)
                .ifPresent(ignored -> target.put(targetName, targetValue));
    }

    private static void mapTranslatedEnum(JsonNode source,
                                          Map<String, Object> target,
                                          String targetName,
                                          int[][] mappings) {
        MappingSupport.integer(source).ifPresent(value -> {
            for (int[] mapping : mappings) {
                if (mapping[0] == value) {
                    target.put(targetName, mapping[1]);
                    return;
                }
            }
        });
    }

    private static void copyRangeEnum(JsonNode source,
                                      Map<String, Object> target,
                                      String targetName,
                                      int min,
                                      int max) {
        MappingSupport.integer(source).filter(value -> value >= min && value <= max)
                .ifPresent(value -> target.put(targetName, value));
    }

    private static void copyNumberInRange(JsonNode source,
                                          String sourceName,
                                          Map<String, Object> target,
                                          String targetName,
                                          double min,
                                          double max) {
        MappingSupport.number(source.get(sourceName))
                .filter(value -> Double.isFinite(value.doubleValue()))
                .filter(value -> value.doubleValue() >= min && value.doubleValue() <= max)
                .ifPresent(value -> target.put(targetName, value));
    }

    private static void copyIntegerInRange(JsonNode source,
                                           Map<String, Object> target,
                                           String targetName,
                                           long max) {
        integerInRange(source, max)
                .ifPresent(value -> target.put(targetName, narrowInteger(value)));
    }

    private static Optional<Long> integerInRange(JsonNode source, long max) {
        if (source == null || source.isNull() || !source.isValueNode()) {
            return Optional.empty();
        }
        try {
            long value = new BigDecimal(source.asText()).longValueExact();
            return value >= 0L && value <= max ? Optional.of(value) : Optional.empty();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Number narrowInteger(long value) {
        if (value <= Integer.MAX_VALUE) {
            return Integer.valueOf((int) value);
        }
        return Long.valueOf(value);
    }
}
