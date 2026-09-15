package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.jingansi.autel.gateway.telemetry.MappingSupport.copyIntegerInRange;
import static com.jingansi.autel.gateway.telemetry.MappingSupport.copyNumberInRange;
import static com.jingansi.autel.gateway.telemetry.MappingSupport.mapEnum;

@Component
public class AircraftPropertyMapper implements PropertyMapper {

    private static final Pattern PAYLOAD_INDEX = Pattern.compile("\\d+-\\d+-\\d+");

    @Override
    public Map<String, Object> map(JsonNode source) {
        Map<String, Object> target = new LinkedHashMap<>();
        MappingSupport.copyText(source, "firmware_version", target, "firmwareVersion", 64);
        MappingSupport.copyText(source, "control_source", target, "controlSource");
        JsonNode sorties = source.has("total_flight_sorties")
                ? source.get("total_flight_sorties") : source.get("fly_turns");
        copyIntegerInRange(sorties, target, "totalFlightSorties", 0, Integer.MAX_VALUE);
        MappingSupport.copyNumber(source, "horizontal_speed", target, "horizontalSpeed");
        MappingSupport.copyNumber(source, "vertical_speed", target, "verticalSpeed");
        // 002 当前把 float 最小值写成 -1.4E-45，实际等价于只接受非负坐标。
        copyNumberInRange(source, "longitude", target, "longitude", 0D, Float.MAX_VALUE);
        copyNumberInRange(source, "latitude", target, "latitude", 0D, Float.MAX_VALUE);
        // 上层 height、elevation 均为相对高，统一取 elevation；不用道通的绝对高 height。
        MappingSupport.copyNumber(source, "elevation", target, "elevation");
        copyNumberInRange(source, "elevation", target, "height", -100D, 1000D);
        MappingSupport.copyNumber(source, "attitude_pitch", target, "attitudePitch");
        MappingSupport.copyNumber(source, "attitude_roll", target, "attitudeRoll");
        MappingSupport.copyNumber(source, "home_distance", target, "homeDistance");
        if (validWhenPresent(source, "wind_speed_valid")) {
            MappingSupport.copyNumber(source, "wind_speed", target, "windSpeed");
        }
        if (validWhenPresent(source, "wind_direction_valid")) {
            copyIntegerInRange(source.get("wind_direction"), target, "windDirection", 1, 8);
        }
        // 002 把 int 类型 totalFlightTime 的 max 写成 1.17549e-038，任何正常正整数都会越界。
        // 在物模型修正前不发送，避免上层因单字段非法拒绝整包属性。
        MappingSupport.integer(source.get("night_lights_state"))
                .filter(value -> value == 0 || value == 1)
                .ifPresent(value -> target.put("nightLightsState", value));
        MappingSupport.integer(source.get("height_limit"))
                .filter(value -> value >= 20 && value <= 1500)
                .ifPresent(value -> target.put("heightLimit", value));

        MappingSupport.number(source.get("attitude_head"))
                .filter(value -> value.doubleValue() >= -180 && value.doubleValue() <= 180)
                .ifPresent(value -> target.put("attitudeHead", (int) Math.round(value.doubleValue())));
        MappingSupport.mapActivationTime(source.get("activation_time"), target);
        MappingSupport.integer(source.get("mode_code"))
                .filter(value -> value >= 0 && value <= 16)
                .ifPresent(value -> target.put("modeCode", value));

        mapDistanceLimit(source.path("distance_limit_status"), target);
        mapObstacleAvoidance(source.path("obstacle_avoidance"), target);
        mapBattery(source.path("battery"), target);
        // 飞机属性文档明确 storage / sdcard_storage 为 KB；选择明确正在使用的存储。
        JsonNode storage = source.path("storage");
        JsonNode sdcard = source.path("sdcard_storage");
        if (MappingSupport.integer(sdcard.get("current_used")).filter(value -> value == 1).isPresent()
                && !MappingSupport.integer(storage.get("current_used")).filter(value -> value == 1).isPresent()) {
            storage = sdcard;
        }
        MappingSupport.mapStorageKb(storage, target);
        JsonNode lostAction = source.path("rc_lost_action");
        copyIntegerInRange(lostAction.isObject() ? lostAction.get("state") : lostAction,
                target, "lostAction", 0, 2);
        mapPositionState(source.path("position_state"), target);
        mapSingleCameraAndPayload(source, target);
        JsonNode laser = source.path("laser_distance_data");
        if (MappingSupport.integer(laser.get("laser_distance_is_valid")).filter(value -> value == 1).isPresent()) {
            copyNumberInRange(laser, "laser_distance", target, "laserDistance", 0, 10_000);
        }
        AircraftFrontendProperties.append(source, target);
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
            if (!MappingSupport.integer(item.get("status")).filter(value -> value == 1).isPresent()
                    || !item.path("video_id").asText("").startsWith(aircraftSn + "/")) {
                continue;
            }
            Integer mapped = MappingSupport.integer(item.get("video_quality"))
                    .map(AircraftPropertyMapper::mapVideoQuality).orElse(null);
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

    private static boolean validWhenPresent(JsonNode source, String name) {
        return !source.has(name) || MappingSupport.integer(source.get(name))
                .filter(value -> value == 1).isPresent();
    }

    /** 有 landing_power 才能满足原物模型；不拿严重低电量告警阈值冒充强降所需电量。 */
    private static void mapBattery(JsonNode source, Map<String, Object> target) {
        Map<String, Object> value = MappingSupport.struct();
        copyIntegerInRange(source.get("capacity_percent"), value, "capacityPercent", 0, 100);
        copyIntegerInRange(source.get("remain_flight_time"), value, "remainFlightTime", 0, 1_000_000);
        copyIntegerInRange(source.get("landing_power"), value, "landingPower", 0, 100);
        MappingSupport.putCompleteStruct(target, "battery", value,
                "capacityPercent", "remainFlightTime", "landingPower");
        copyIntegerInRange(source.get("return_home_power"), target, "returnhomePower", 0, 100);
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
        copyIntegerInRange(source.get("gps_number"), value, "gpsNumber", 0, 10_000);
        copyIntegerInRange(source.get("rtk_number"), value, "rtkNumber", 0, 10_000);
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
            mapCamera(camera, target);
        }

        Optional<String> payloadIndex = singlePayloadIndex(source);
        if (!payloadIndex.isPresent()) {
            return;
        }
        JsonNode payloads = source.path("payloads");
        if (payloads.isArray() && payloads.size() == 1) {
            MappingSupport.copyText(payloads.get(0), "sn", target, "cameraSn", 1024);
        }
        JsonNode dynamicPayload = source.path(payloadIndex.get());
        if (dynamicPayload.isObject()) {
            copyNumberInRange(dynamicPayload, "gimbal_pitch", target,
                    "gimbalPitch", -180D, 180D);
            copyNumberInRange(dynamicPayload, "gimbal_yaw", target,
                    "gimbalYaw", -180D, 180D);
            copyIntegerInRange(dynamicPayload.get("thermal_gain_mode"), target, "thermalGainMode", 1, 2);
            // 道通 4=铁红，而上层 6=铁红；不能直接转发数字枚举。
            mapEnum(dynamicPayload.get("thermal_current_palette_style"), target, "thermalCurrentPaletteStyle",
                    new int[][]{{0, 0}, {4, 6}, {5, 11}, {30, 0}, {31, 1}, {32, 5}, {33, 13},
                            {34, 6}, {35, 11}, {36, 8}, {38, 3}, {39, 2}});
        }
    }

    private static Optional<String> singlePayloadIndex(JsonNode source) {
        Set<String> indexes = new HashSet<>();
        for (String name : new String[]{"payloads", "cameras"}) {
            JsonNode list = source.path(name);
            if (list.isArray()) {
                if (list.size() > 1) {
                    return Optional.empty();
                }
                for (JsonNode item : list) {
                    String index = item.path("payload_index").asText("");
                    if (!PAYLOAD_INDEX.matcher(index).matches()) {
                        return Optional.empty();
                    }
                    indexes.add(index);
                }
            }
        }
        // SkyCC 实际只有 cameras 和动态负载对象；也兼容独立的负载增量上报。
        source.fieldNames().forEachRemaining(name -> {
            if (PAYLOAD_INDEX.matcher(name).matches() && source.path(name).isObject()) {
                indexes.add(name);
            }
        });
        return indexes.size() == 1 ? Optional.of(indexes.iterator().next()) : Optional.empty();
    }

    private static void mapCamera(JsonNode camera, Map<String, Object> target) {
        // AF 没有区分 AFS/AFC，只有 MF 能无歧义映射。
        mapEnum(camera.get("zoom_focus_mode"), target, "zoomFocusMode", new int[][]{{2, 0}});
        MappingSupport.integer(camera.get("ir_metering_mode")).filter(value -> value >= 0 && value <= 2)
                .ifPresent(value -> target.put("irMeteringMode", new String[]{"CLOSE", "POINT", "AREA"}[value]));
        MappingSupport.putStruct(target, "irMeteringPoint", temperaturePoint(camera.path("ir_metering_point"), null));
        JsonNode area = camera.path("ir_metering_area");
        Map<String, Object> mapped = temperaturePoint(area, null);
        copyNumberInRange(area, "width", mapped, "width", 0, 1);
        copyNumberInRange(area, "height", mapped, "height", 0, 1);
        copyNumberInRange(area, "aver_temperature", mapped, "averTemperature", 0, 100);
        // 002 的最低温度上限误写为 1，修正物模型前不发送超范围温度。
        MappingSupport.putStruct(mapped, "minTemperaturePoint", temperaturePoint(area.path("min_temperature_point"), 1D));
        MappingSupport.putStruct(mapped, "maxTemperaturePoint", temperaturePoint(area.path("max_temperature_point"), 100D));
        MappingSupport.putStruct(target, "irMeteringArea", mapped);
    }

    private static Map<String, Object> temperaturePoint(JsonNode source, Double temperatureMax) {
        Map<String, Object> value = MappingSupport.struct();
        copyNumberInRange(source, "x", value, "x", 0, 1);
        copyNumberInRange(source, "y", value, "y", 0, 1);
        if (temperatureMax != null) {
            copyNumberInRange(source, "temperature", value, "temperature", 0, temperatureMax);
        }
        return value;
    }

    private static void copyBinaryEnum(JsonNode source,
                                       String sourceName,
                                       Map<String, Object> target,
                                       String targetName) {
        MappingSupport.integer(source.get(sourceName))
                .filter(value -> value == 0 || value == 1)
                .ifPresent(value -> target.put(targetName, value));
    }

}
