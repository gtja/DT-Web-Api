package com.jingansi.autel.gateway.telemetry;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface PropertyMapper {

    Map<String, Object> map(JsonNode source);
}

