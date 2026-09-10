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
        JsonNode value = source.get(sourceName);
        if (value != null && value.isValueNode() && !value.isNull()) {
            String text = value.asText();
            if (!text.trim().isEmpty()) {
                target.put(targetName, text);
            }
        }
    }

    static Optional<Number> number(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return Optional.empty();
        }
        if (node.isIntegralNumber()) {
            long value = node.longValue();
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return Optional.of(Integer.valueOf((int) value));
            }
            return Optional.of(Long.valueOf(value));
        }
        if (node.isFloatingPointNumber()) {
            return Optional.of(node.doubleValue());
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
            return Optional.of(value.doubleValue());
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
