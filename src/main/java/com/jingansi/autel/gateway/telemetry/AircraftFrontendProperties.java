package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.jingansi.autel.gateway.telemetry.MappingSupport.copyIntegerInRange;
import static com.jingansi.autel.gateway.telemetry.MappingSupport.copyNumberInRange;

/** 飞机驾驶舱展示别名：只追加真实数据，不改变原物模型属性和控制状态。 */
final class AircraftFrontendProperties {

    private AircraftFrontendProperties() {
    }

    static void append(JsonNode source, Map<String, Object> target) {
        copyIntegerInRange(source.path("battery").get("capacity_percent"), target, "electricity", 0, 100);
        // 对齐现有 DJI 接入的绝对高字段；源 height 是椭球高，不是相对高，也未做海拔基准转换。
        copyNumberInRange(source, "height", target, "altitude", -Float.MAX_VALUE, Float.MAX_VALUE);
        copyNumberInRange(source, "attitude_pitch", target, "uavPitch", -180, 180);
        copyNumberInRange(source, "attitude_roll", target, "uavRoll", -180, 180);
        copyNumberInRange(source, "attitude_head", target, "uavYaw", -180, 180);

        // FT/FD 为本次打桨统计，不能使用飞机终身累计值；前端 FT 按毫秒格式化。
        MappingSupport.integer(source.get("total_armed_time")).filter(seconds -> seconds >= 0)
                .ifPresent(seconds -> target.put("flyTime", seconds * 1000L));
        copyNumberInRange(source, "total_armed_distance", target, "flyDistance", 0, Double.MAX_VALUE);
        copyIntegerInRange(source.path("position_state").get("gps_number"), target, "satelliteNumber", 0, 10_000);
        copyIntegerInRange(source.path("position_state").get("rtk_number"), target, "rtkSatelliteNumber", 0, 10_000);
        mapBatteryDetails(source.path("battery"), target);
        mapSignal(source, target);
    }

    /** 电池弹窗读取 cloudApiOsdData.battery；只放用到的字段，不透传整包道通数据。 */
    private static void mapBatteryDetails(JsonNode source, Map<String, Object> target) {
        Map<String, Object> battery = MappingSupport.struct();
        copyIntegerInRange(source.get("capacity_percent"), battery, "capacity_percent", 0, 100);
        copyIntegerInRange(source.get("remain_flight_time"), battery, "remain_flight_time", 0, 1_000_000);
        copyIntegerInRange(source.get("landing_power"), battery, "landing_power", 0, 100);
        copyIntegerInRange(source.get("return_home_power"), battery, "return_home_power", 0, 100);
        List<Map<String, Object>> packs = new ArrayList<>();
        if (source.path("batteries").isArray()) {
            for (JsonNode pack : source.path("batteries")) {
                Map<String, Object> value = MappingSupport.struct();
                copyIntegerInRange(pack.get("index"), value, "index", 0, Integer.MAX_VALUE);
                copyIntegerInRange(pack.get("loop_times"), value, "loop_times", 0, Integer.MAX_VALUE);
                copyIntegerInRange(pack.get("capacity_percent"), value, "capacity_percent", 0, 100);
                MappingSupport.copyText(pack, "sn", value, "sn", 128);
                if (!value.isEmpty()) {
                    packs.add(value);
                }
            }
        }
        if (!packs.isEmpty()) {
            battery.put("batteries", packs);
        }
        if (!battery.isEmpty()) {
            target.put("cloudApiOsdData", Map.of("battery", battery));
        }
    }

    /** 只使用飞机本身的信号字段；LTE 百分比折算成前端 0～5 档，不复制机巢信号。 */
    private static void mapSignal(JsonNode source, Map<String, Object> target) {
        Optional<Integer> lteStatus = MappingSupport.integer(source.get("lte_status"));
        if (!source.has("lte_status") || lteStatus.filter(value -> value == 3 || value == 4).isPresent()) {
            MappingSupport.integer(source.get("lte_signal")).filter(value -> value >= 0 && value <= 100)
                    .ifPresent(value -> {
                        target.put("signalMode", "4G");
                        target.put("signalStrength", value / 20D);
                    });
        }
        JsonNode link = source.path("wireless_link");
        if (MappingSupport.integer(link.get("sdr_link_state")).filter(value -> value == 1).isPresent()) {
            copyIntegerInRange(link.get("sdr_quality"), target, "sdrStrength", 0, 5);
        }
    }
}
