package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 用上传的原始物模型快照校验输出；不依赖开发机 Downloads 或在线服务。 */
class PropertyModelContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldMatchOriginalDockModelWithExplicitFrontendExtensions() throws Exception {
        Map<String, Object> output = new DockPropertyMapper().map(resource("/telemetry/dock-osd.json"));
        assertThat(output).containsEntry("modeCode", 0).containsEntry("airConditionerMode", 1)
                .containsEntry("jobNumber", 15).containsEntry("droneOpenProcess", 2)
                .containsEntry("modeDisplay", "0").containsEntry("temperature", 25.8D);
        // 原始模型快照保持不变；仅允许用户指定的两个前端扩展，其他未知字段仍报错。
        ArrayNode fields = resource("/thing-model/dock.json").withArray("properties");
        fields.addAll((ArrayNode) resource("/thing-model/dock-frontend-extensions.json").path("properties"));
        validateObject(json.valueToTree(output), fields, "dock");
    }

    @Test
    void shouldMatchAircraftModelWithExplicitFrontendExtensionsAndPreserveOriginalRestrictions() throws Exception {
        Map<String, Object> output = new AircraftPropertyMapper().map(resource("/telemetry/aircraft-osd.json"));
        assertThat(output).containsEntry("gimbalYaw", -46.46D).containsEntry("totalFlightSorties", 256)
                .containsEntry("thermalCurrentPaletteStyle", 6)
                .containsEntry("electricity", 77).containsEntry("altitude", -0.3D)
                .containsEntry("uavYaw", -46.46D).containsEntry("flyTime", 0L).containsEntry("flyDistance", 0)
                .doesNotContainKeys("battery", "positionState", "heightLimit", "distanceLimitStatus", "zoomFactor", "totalFlightTime",
                        "modeDisplay", "temperature");
        ArrayNode fields = resource("/thing-model/aircraft.json").withArray("properties");
        fields.addAll((ArrayNode) resource("/thing-model/aircraft-frontend-extensions.json").path("properties"));
        validateObject(json.valueToTree(output), fields, "aircraft");
    }

    @Test
    void shouldAuditEveryPropertyExactlyOnce() throws Exception {
        String audit = Files.readString(Path.of("docs/property-mapping.md"));
        for (String device : new String[]{"dock", "aircraft"}) {
            String section = audit.split("<!-- " + device + " -->")[1].split("<!-- end -->")[0];
            Matcher matcher = Pattern.compile("(?m)^\\| `([^`]+)` ").matcher(section);
            Map<String, Integer> rows = new LinkedHashMap<>();
            while (matcher.find()) {
                rows.merge(matcher.group(1), 1, Integer::sum);
            }
            JsonNode properties = resource("/thing-model/" + device + ".json").path("properties");
            assertThat(rows).hasSize(properties.size());
            for (JsonNode property : properties) {
                assertThat(rows).containsEntry(property.path("identifier").asText(), 1);
            }
        }
    }

    private JsonNode resource(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(name)) {
            assertThat(input).as(name).isNotNull();
            return json.readTree(input);
        }
    }

    private void validateObject(JsonNode value, JsonNode fields, String path) {
        assertThat(value.isObject()).as(path).isTrue();
        Map<String, JsonNode> schema = new LinkedHashMap<>();
        for (JsonNode field : fields) {
            String name = field.path("identifier").asText();
            schema.put(name, field);
            if ("true".equals(field.path("required").asText())) {
                assertThat(value.has(name)).as(path + "." + name + " required").isTrue();
            }
        }
        value.fields().forEachRemaining(entry -> {
            assertThat(schema).as(path).containsKey(entry.getKey());
            JsonNode field = schema.get(entry.getKey());
            validate(entry.getValue(), field.has("dataType") ? field.path("dataType") : field,
                    path + "." + entry.getKey());
        });
    }

    private void validate(JsonNode value, JsonNode type, String path) {
        JsonNode specs = type.path("specs");
        switch (type.path("type").asText()) {
            case "struct":
                validateObject(value, specs, path);
                break;
            case "enum":
                assertThat(specs.has(value.asText())).as(path + "=" + value).isTrue();
                break;
            case "text":
                assertThat(value.isTextual()).as(path).isTrue();
                if (specs.has("length")) {
                    assertThat(value.asText().length()).as(path).isLessThanOrEqualTo(specs.path("length").asInt());
                }
                break;
            case "array":
                assertThat(value.isArray()).as(path).isTrue();
                assertThat(value.size()).as(path).isLessThanOrEqualTo(specs.path("size").asInt());
                value.forEach(item -> validate(item, specs.path("item"), path + "[]"));
                break;
            default:
                assertThat(value.isNumber()).as(path).isTrue();
                assertThat(Double.isFinite(value.doubleValue())).as(path).isTrue();
                if ("int".equals(type.path("type").asText())) {
                    assertThat(value.isIntegralNumber() && value.canConvertToInt()).as(path).isTrue();
                }
                for (String bound : new String[]{"min", "max"}) {
                    if (specs.hasNonNull(bound) && !specs.path(bound).asText().isEmpty()) {
                        int comparison = value.decimalValue().compareTo(new BigDecimal(specs.path(bound).asText()));
                        assertThat("min".equals(bound) ? comparison >= 0 : comparison <= 0).as(path + " " + bound).isTrue();
                    }
                }
        }
    }
}
