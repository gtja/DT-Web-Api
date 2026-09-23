package com.jingansi.autel.gateway.live;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;

/** OSD 是多路开流状态；仅唯一活跃通道或唯一状态变化可以作为跟随依据。 */
final class AircraftLiveStatus {
    private Map<String, String> previous = new LinkedHashMap<>();
    private String preferred;
    private long timestamp;
    private long revision;

    synchronized void update(JsonNode statuses, String aircraftSn, long eventTime) {
        if (!statuses.isArray() || (eventTime > 0 && eventTime < timestamp)) {
            return;
        }
        Map<String, String> active = new LinkedHashMap<>();
        for (JsonNode status : statuses) {
            String id = status.path("video_id").asText("");
            if (id.startsWith(aircraftSn + "/") && status.path("status").asInt(-1) == 1
                    && status.path("error_status").asInt(0) == 0) {
                active.put(id, status.path("video_type").asText(""));
            }
        }
        List<String> changed = new ArrayList<>();
        if (!previous.isEmpty()) {
            active.forEach((id, type) -> {
                if (!Objects.equals(previous.get(id), type)) {
                    changed.add(id);
                }
            });
        }
        String next = preferred;
        if (active.size() == 1) {
            next = active.keySet().iterator().next();
        } else if (changed.size() == 1) {
            next = changed.get(0);
        } else if (next == null || !active.containsKey(next) || changed.size() > 1) {
            next = null;
        }
        if (!active.equals(previous) || !Objects.equals(next, preferred)) {
            revision++;
        }
        previous = active;
        preferred = next;
        timestamp = Math.max(timestamp, eventTime);
    }

    synchronized String preferred() { return preferred; }
    synchronized long revision() { return revision; }

    synchronized void clear() {
        previous.clear();
        preferred = null;
        timestamp = 0;
        revision++;
    }
}
