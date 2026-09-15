package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class MappingSupport {

    private MappingSupport() {
    }

    static void copyNumber(JsonNode source, String sourceName, Map<String, Object> target, String targetName) {
        number(source.get(sourceName)).ifPresent(value -> target.put(targetName, value));
    }

    static void copyText(JsonNode source, String sourceName, Map<String, Object> target, String targetName) {
        copyText(source, sourceName, target, targetName, Integer.MAX_VALUE);
    }

    static void copyText(JsonNode source, String sourceName, Map<String, Object> target,
                         String targetName, int maxLength) {
        JsonNode value = source.get(sourceName);
        if (value != null && value.isTextual()) {
            String text = value.asText();
            if (!text.trim().isEmpty() && text.length() <= maxLength) {
                target.put(targetName, text);
            }
        }
    }

    static Optional<Number> number(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return Optional.empty();
        }
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) {
                return Optional.empty();
            }
            long value = node.longValue();
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return Optional.of(Integer.valueOf((int) value));
            }
            return Optional.of(Long.valueOf(value));
        }
        if (node.isFloatingPointNumber()) {
            return Double.isFinite(node.doubleValue()) ? Optional.of(node.doubleValue()) : Optional.empty();
        }
        try {
            BigDecimal value = new BigDecimal(node.asText());
            if (value.scale() <= 0) {
                long integer = value.longValueExact();
                if (integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE) {
                    return Optional.of(Integer.valueOf((int) integer));
                }
                return Optional.of(Long.valueOf(integer));
            }
            return Double.isFinite(value.doubleValue()) ? Optional.of(value.doubleValue()) : Optional.empty();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    static Optional<Integer> integer(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(node.asText()).intValueExact());
        } catch (ArithmeticException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    static Map<String, Object> struct() {
        return new LinkedHashMap<>();
    }

    static void copyNumberInRange(JsonNode source, String from, Map<String, Object> target,
                                  String to, double min, double max) {
        number(source.get(from)).filter(value -> value.doubleValue() >= min && value.doubleValue() <= max)
                .ifPresent(value -> target.put(to, value));
    }

    static void copyIntegerInRange(JsonNode source, Map<String, Object> target,
                                   String name, int min, int max) {
        integer(source).filter(value -> value >= min && value <= max)
                .ifPresent(value -> target.put(name, value));
    }

    static void mapEnum(JsonNode source, Map<String, Object> target, String name, int[][] mappings) {
        integer(source).ifPresent(value -> {
            for (int[] mapping : mappings) {
                if (mapping[0] == value) {
                    target.put(name, mapping[1]);
                    return;
                }
            }
        });
    }

    static void mapActivationTime(JsonNode source, Map<String, Object> target) {
        if (source == null || !source.isValueNode()) {
            return;
        }
        try {
            long value = new BigDecimal(source.asText()).longValueExact();
            long seconds = value >= 1_000_000_000_000L ? value / 1000L : value;
            if (seconds >= 0 && seconds <= Integer.MAX_VALUE) {
                target.put("activationTime", (int) seconds);
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
            // 不截断小数，也不把非法时间默认为 0。
        }
    }

    /** 仅用于文档已确认单位为 KB 的存储信息，不按数值大小猜测字节/KB。 */
    static void mapStorageKb(JsonNode source, Map<String, Object> target) {
        Optional<Integer> total = integer(source.get("total")).filter(value -> value >= 0);
        Optional<Integer> used = integer(source.get("used")).filter(value -> value >= 0);
        if (total.isPresent() && used.isPresent() && used.get() <= total.get()) {
            Map<String, Object> value = struct();
            value.put("total", total.get());
            value.put("used", used.get());
            target.put("storage", value);
        }
    }

    static void putStruct(Map<String, Object> target, String name, Map<String, Object> struct) {
        if (!struct.isEmpty()) {
            target.put(name, struct);
        }
    }

    static void putCompleteStruct(Map<String, Object> target,
                                  String name,
                                  Map<String, Object> struct,
                                  String... requiredFields) {
        for (String requiredField : requiredFields) {
            if (!struct.containsKey(requiredField)) {
                return;
            }
        }
        target.put(name, struct);
    }
}
