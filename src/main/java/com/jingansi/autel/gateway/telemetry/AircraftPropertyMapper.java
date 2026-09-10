package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class AircraftPropertyMapper implements PropertyMapper {

    private static final Pattern PAYLOAD_INDEX = Pattern.compile("\\d+-\\d+-\\d+");

    @Override
    public Map<String, Object> map(JsonNode source) {
        Map<String, Object> target = new LinkedHashMap<>();
        MappingSupport.copyNumber(source, "horizontal_speed", target, "horizontalSpeed");
        MappingSupport.copyNumber(source, "vertical_speed", target, "verticalSpeed");
        // 002 当前把 float 最小值写成 -1.4E-45，实际等价于只接受非负坐标。
        copyNumberInRange(source, "longitude", target, "longitude", 0D, Float.MAX_VALUE);
        copyNumberInRange(source, "latitude", target, "latitude", 0D, Float.MAX_VALUE);
        // 道通 height 是椭球绝对高，002 的 height 是相对高，不能直接映射。
        MappingSupport.copyNumber(source, "elevation", target, "elevation");
        MappingSupport.copyNumber(source, "attitude_pitch", target, "attitudePitch");
        MappingSupport.copyNumber(source, "attitude_roll", target, "attitudeRoll");
        MappingSupport.copyNumber(source, "home_distance", target, "homeDistance");
        MappingSupport.copyNumber(source, "wind_speed", target, "windSpeed");
        // 002 把 int 类型 totalFlightTime 的 max 写成 1.17549e-038，任何正常正整数都会越界。
        // 在物模型修正前不发送，避免上层因单字段非法拒绝整包属性。
        MappingSupport.integer(source.get("night_lights_state"))
                .filter(value -> value == 0 || value == 1)
                .ifPresent(value -> target.put("nightLightsState", value));
        MappingSupport.integer(source.get("height_limit"))
                .filter(value -> value >= 20 && value <= 1500)
                .ifPresent(value -> target.put("heightLimit", value));

        MappingSupport.number(source.get("attitude_head"))
                .ifPresent(value -> target.put("attitudeHead", (int) Math.round(value.doubleValue())));
        mapActivationTime(source.get("activation_time"), target);
        MappingSupport.integer(source.get("mode_code"))
                .filter(value -> value >= 0 && value <= 16)
                .ifPresent(value -> target.put("modeCode", value));

        mapDistanceLimit(source.path("distance_limit_status"), target);
        mapObstacleAvoidance(source.path("obstacle_avoidance"), target);
        // 002 的 battery.landingPower 为必填，但道通没有可信对应字段；避免上报不完整 struct。
        mapPositionState(source.path("position_state"), target);
        mapSingleCameraAndPayload(source, target);
        return target;
    }

    /** live_status 实际由机巢 OSD 携带；只在唯一飞机码流正在直播时上报画质。 */
    public Map<String, Object> mapLiveStatus(JsonNode liveStatus, String aircraftSn) {
        Map<String, Object> result = new LinkedHashMap<>();
        Integer quality = null;
        if (!liveStatus.isArray()) {
            return result;
        }
        for (JsonNode item : liveStatus) {
            if (item.path("status").asInt(0) != 1
                    || !item.path("video_id").asText("").startsWith(aircraftSn + "/")) {
                continue;
            }
            Integer mapped = mapVideoQuality(item.path("video_quality").asInt(-1));
            if (mapped == null || quality != null) {
                return new LinkedHashMap<>();
            }
            quality = mapped;
        }
        if (quality != null) {
            result.put("videoQuality", quality);
        }
        return result;
    }

    private static Integer mapVideoQuality(int quality) {
        switch (quality) {
            case 0: return 0;
            case 1: return 1;
            case 2: return 3;
            case 3: return 4;
            default: return null;
        }
    }

    private static void mapActivationTime(JsonNode source, Map<String, Object> target) {
        MappingSupport.number(source).ifPresent(number -> {
            long value = number.longValue();
            long seconds = value >= 1_000_000_000_000L ? value / 1000L : value;
            if (seconds >= 0L && seconds <= Integer.MAX_VALUE) {
                target.put("activationTime", (int) seconds);
            }
        });
    }

    private static void mapDistanceLimit(JsonNode source, Map<String, Object> target) {
        if (!source.isObject()) {
            return;
        }
        Map<String, Object> value = MappingSupport.struct();
        MappingSupport.integer(source.get("state"))
                .filter(state -> state == 0 || state == 1)
                .ifPresent(state -> value.put("state", state));
        MappingSupport.integer(source.get("distance_limit"))
                .filter(distance -> distance >= 15 && distance <= 8000)
                .ifPresent(distance -> value.put("distanceLimit", distance));
        MappingSupport.putCompleteStruct(target, "distanceLimitStatus", value,
                "state", "distanceLimit");
    }

    private static void mapObstacleAvoidance(JsonNode source, Map<String, Object> target) {
        if (!source.isObject()) {
            return;
        }
        Map<String, Object> value = MappingSupport.struct();
        copyBinaryEnum(source, "horizon", value, "horizon");
        copyBinaryEnum(source, "upside", value, "upside");
        copyBinaryEnum(source, "downside", value, "downside");
        MappingSupport.putCompleteStruct(target, "obstacleAvoidance", value,
                "horizon", "upside", "downside");
    }

    private static void mapPositionState(JsonNode source, Map<String, Object> target) {
        if (!source.isObject()) {
            return;
        }
        Map<String, Object> value = MappingSupport.struct();
        MappingSupport.integer(source.get("is_fixed"))
                .filter(state -> state >= 0 && state <= 3)
                .ifPresent(state -> value.put("isFixed", state));
        MappingSupport.integer(source.get("quality"))
                .filter(quality -> quality >= 1 && quality <= 5)
                .ifPresent(quality -> value.put("quality", quality));
        copyIntegerInRange(source.get("gps_number"), value, "gpsNumber", 10_000L);
        copyIntegerInRange(source.get("rtk_number"), value, "rtkNumber", 10_000L);
        MappingSupport.putCompleteStruct(target, "positionState", value,
                "isFixed", "quality", "gpsNumber", "rtkNumber");
    }

    private static void mapSingleCameraAndPayload(JsonNode source, Map<String, Object> target) {
        JsonNode cameras = source.path("cameras");
        if (cameras.isArray() && cameras.size() == 1) {
            JsonNode camera = cameras.get(0);
            MappingSupport.integer(camera.get("camera_mode"))
                    .filter(mode -> mode == 0 || mode == 1)
                    .ifPresent(mode -> target.put("cameraMode", mode));
            MappingSupport.number(camera.get("zoom_factor"))
                    .filter(value -> Double.isFinite(value.doubleValue()))
                    .filter(value -> value.doubleValue() >= 2D && value.doubleValue() <= 2_000D)
                    .ifPresent(value -> target.put("zoomFactor", value));
        }

        Optional<String> payloadIndex = singlePayloadIndex(source.path("payloads"));
        if (!payloadIndex.isPresent()) {
            return;
        }
        JsonNode payloads = source.path("payloads");
        JsonNode payload = payloads.get(0);
        MappingSupport.copyText(payload, "sn", target, "cameraSn");
        JsonNode dynamicPayload = source.path(payloadIndex.get());
        if (dynamicPayload.isObject()) {
            copyNumberInRange(dynamicPayload, "gimbal_pitch", target,
                    "gimbalPitch", -180D, 180D);
            copyNumberInRange(dynamicPayload, "gimbal_yaw", target,
                    "gimbalYaw", -180D, 180D);
        }
    }

    private static Optional<String> singlePayloadIndex(JsonNode payloads) {
        if (payloads.isArray() && payloads.size() == 1) {
            String index = payloads.get(0).path("payload_index").asText("");
            if (PAYLOAD_INDEX.matcher(index).matches()) {
                return Optional.of(index);
            }
        }
        return Optional.empty();
    }

    private static void copyBinaryEnum(JsonNode source,
                                       String sourceName,
                                       Map<String, Object> target,
                                       String targetName) {
        MappingSupport.integer(source.get(sourceName))
                .filter(value -> value == 0 || value == 1)
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
