package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.jingansi.autel.gateway.telemetry.MappingSupport.copyIntegerInRange;
import static com.jingansi.autel.gateway.telemetry.MappingSupport.copyNumberInRange;
import static com.jingansi.autel.gateway.telemetry.MappingSupport.mapEnum;

@Component
public class DockPropertyMapper implements PropertyMapper {

    @Override
    public Map<String, Object> map(JsonNode source) {
        Map<String, Object> target = new LinkedHashMap<>();
        // 龙鱼机巢属性文档已确认与 001 枚举一致，不能使用另一套流程状态 state。
        copyIntegerInRange(source.get("mode_code"), target, "modeCode", 0, 4);
        copyIntegerInRange(source.get("work_sorties"), target, "jobNumber", 0, 10_000);
        MappingSupport.mapActivationTime(source.get("activation_time"), target);
        copyNumberInRange(source, "height", target, "height", 0D, Double.MAX_VALUE);
        copyNumberInRange(source, "wind_speed", target, "windSpeed", 0D, Float.MAX_VALUE);
        copyIntegerInRange(source.get("rainfall"), target, "rainfall", 0, 3);
        copyIntegerInRange(source.get("supplement_light_state"), target, "supplementLightState", 0, 1);
        copyIntegerInRange(source.get("emergency_stop_state"), target, "emergencyStopState", 0, 1);
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

        copyIntegerInRange(source.path("air_conditioner").get("air_conditioner_state"),
                target, "airConditionerMode", 0, 3);
        // 4~9 是退出/准备过程，001 没有等价状态，不伪装成正在制冷/制热。
        copyIntegerInRange(source.path("wireless_link").get("link_workmode"), target, "linkWorkMode", 0, 1);
        mapEnum(source.path("sub_device").get("device_online_status"),
                target, "droneOpenProcess", new int[][]{{0, 3}, {1, 2}});
        mapEnum(source.path("network_state").get("type"), target, "networkType",
                new int[][]{{1, 0}, {2, 1}});
        mapEnum(source.get("cover_state"), target, "coverState",
                new int[][]{{0, 0}, {1, 2}, {3, 4}});
        mapEnum(source.get("putter_state"), target, "putterState",
                new int[][]{{0, 0}, {1, 2}, {3, 4}});

        mapAlternateLandPoint(source.path("alternate_land_point"), target);
        mapPositionState(source.path("position_state"), target);
        mapChargeState(source.path("drone_charge_state"), target);
        mapFrontendProperties(source, target);
        return target;
    }

    /** 机库页面的额外展示字段，不替换原物模型属性，也不应用于飞机。 */
    private static void mapFrontendProperties(JsonNode source, Map<String, Object> target) {
        Object modeCode = target.get("modeCode");
        if (modeCode != null) {
            // 前端以字符串查枚举并判空；数字 0 会被当成无值，必须发送 "0"。
            target.put("modeDisplay", modeCode.toString());
        }
        // temperature 是舱内温度，environmentTemperature 是舱外温度，不能相互代替。
        copyNumberInRange(source, "temperature", target, "temperature", -Float.MAX_VALUE, Float.MAX_VALUE);
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
        copyIntegerInRange(source.get("is_fixed"), value, "isFixed", 0, 3);
        MappingSupport.integer(source.get("quality"))
                .filter(quality -> quality >= 1 && quality <= 5)
                .ifPresent(quality -> value.put("quality", quality));
        copyIntegerInRange(source.get("gps_number"), value, "gpsNumber", 0, 200);
        copyIntegerInRange(source.get("rtk_number"), value, "rtkNumber", 0, 200);
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
        mapEnum(source.get("state"), value, "state", new int[][]{{0, 1}, {1, 0}});
        MappingSupport.putCompleteStruct(target, "droneChargeState", value,
                "capacityPercent", "state");
    }

}
