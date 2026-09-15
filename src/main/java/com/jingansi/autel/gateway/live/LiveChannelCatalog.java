package com.jingansi.autel.gateway.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.jingansi.autel.gateway.autel.AutelApiException;
import com.jingansi.autel.gateway.config.AutelGatewayProperties;
import com.jingansi.autel.gateway.domain.DeviceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 从 liveStatus 生成前端需要的视频目录，并解析当前直播状态。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveChannelCatalog {
    private static final Set<String> VIDEO_TYPES =
            Set.of("normal", "wide", "zoom", "ir", "nightvision");

    private final AutelLivePort autelLivePort;
    private final AutelGatewayProperties properties;
    private volatile Map<DeviceTarget, List<LiveChannel>> channels = emptyCatalog();

    public synchronized Map<DeviceTarget, List<LiveChannel>> refresh() {
        JsonNode data = autelLivePort.getLiveStatus(
                properties.getDevices().getDockSn(), properties.getDevices().getAircraftSn()).path("data");
        EnumMap<DeviceTarget, List<LiveChannel>> result = new EnumMap<>(DeviceTarget.class);
        result.put(DeviceTarget.DOCK, parse(data.path("masterLiveStatus"),
                properties.getDevices().getDockSn()));
        result.put(DeviceTarget.AIRCRAFT, parse(data.path("slaveLiveStatus"),
                properties.getDevices().getAircraftSn()));
        channels = Collections.unmodifiableMap(result);
        log.info("SkyCC 视频目录刷新完成 dockChannels={} aircraftChannels={}",
                result.get(DeviceTarget.DOCK).size(), result.get(DeviceTarget.AIRCRAFT).size());
        return channels;
    }

    public LiveChannel resolve(DeviceTarget target, String videoId) {
        LiveChannel found = find(target, videoId);
        if (found == null) {
            refresh();
            found = find(target, videoId);
        }
        if (found == null) {
            throw new AutelApiException("未找到视频通道: " + videoId);
        }
        return found;
    }

    /** 每次启动重新获取带鉴权参数的播放地址，绝不把设备的推流 url 当作拉流地址。 */
    public String playbackUrl(String sourceSn, String videoId) {
        if (!videoId.startsWith(sourceSn + "/")) {
            throw new IllegalArgumentException("拉流必须使用当前设备的真实通道 ID");
        }
        return playbackSource(sourceSn, videoId).getUrl();
    }

    /** live 从实际已开流通道中选择；明确的真实 ID 只匹配指定通道。 */
    public PlaybackSource playbackSource(String sourceSn, String videoId) {
        boolean businessId = "live".equalsIgnoreCase(videoId);
        if (!businessId && !videoId.startsWith(sourceSn + "/")) {
            throw new IllegalArgumentException("拉流必须使用当前设备的真实通道 ID");
        }
        JsonNode data = autelLivePort.getCapacity(sourceSn).path("data");
        if (!data.isArray() && !data.isNull() && !data.isMissingNode()) {
            throw new AutelApiException("capacity 返回格式异常");
        }
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode item : data) {
            String id = item.path("video_id").asText();
            if (businessId ? id.startsWith(sourceSn + "/") && item.path("active").asBoolean()
                    : videoId.equals(id)) {
                candidates.add(item);
            }
        }
        if (candidates.isEmpty()) {
            throw new AutelApiException(businessId
                    ? "道通当前设备没有已开流通道，请先在天穹开启直播"
                    : "道通未返回目标通道: " + videoId);
        }
        // 多路均已开流时保持确定顺序；不使用 liveStatus 的可选镜头目录选流。
        candidates.sort(Comparator.comparing(item -> item.path("video_id").asText()));
        for (JsonNode item : candidates) {
            if (!item.path("active").asBoolean()) {
                throw new AutelApiException("道通目标通道尚未开流，请先在天穹开启该通道直播");
            }
            String sourceVideoId = item.path("video_id").asText();
            String url = findPlaybackUrl(item, sourceVideoId);
            if (url != null) {
                return new PlaybackSource(sourceVideoId, url);
            }
        }
        throw new AutelApiException("目标通道已开流，但没有可转推的 RTMP/RTSP/HTTP-FLV 播放地址");
    }

    private String findPlaybackUrl(JsonNode item, String videoId) {
        // 优先 RTMP，其次 RTSP、HTTP-FLV；WEBRTC 地址不能直接交给普通 FFmpeg 拉取。
        for (String type : List.of("rtmp", "rtsp", "flv")) {
            for (JsonNode stream : item.path("live_streams")) {
                if (!type.equalsIgnoreCase(stream.path("type").asText())
                        || (stream.hasNonNull("videoId") && !videoId.equals(stream.path("videoId").asText()))) {
                    continue;
                }
                String url = stream.path("url").asText("").trim();
                try {
                    URI uri = URI.create(url);
                    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                    if (uri.getHost() != null && (("rtmp".equals(type) && Set.of("rtmp", "rtmps").contains(scheme))
                            || ("rtsp".equals(type) && "rtsp".equals(scheme))
                            || ("flv".equals(type) && Set.of("http", "https").contains(scheme)))) {
                        log.info("SkyCC 取得转推源地址 videoId={} type={} url={}", videoId, type, url);
                        return url;
                    }
                } catch (IllegalArgumentException ignored) {
                    // 跳过无效候选地址，继续寻找同一通道的其他协议。
                }
            }
        }
        return null;
    }

    public List<LiveChannel> activeChannels(LiveChannel requested) {
        JsonNode data = autelLivePort.getCapacity(requested.getSourceSn()).path("data");
        if (!data.isArray()) {
            return Collections.emptyList();
        }
        List<LiveChannel> active = new ArrayList<>();
        for (JsonNode item : data) {
            if (!item.path("active").asBoolean(false)) {
                continue;
            }
            String videoId = item.path("video_id").asText("").trim();
            String type = normalizeType(item.path("video_type").asText(""), videoId);
            if (!videoId.startsWith(requested.getSourceSn() + "/") || !VIDEO_TYPES.contains(type)) {
                continue;
            }
            active.add(new LiveChannel(requested.getSourceSn(), videoId, type,
                    item.path("camera_index").asText(""), null, Collections.emptyList(),
                    item.path("url").asText("")));
        }
        return active;
    }

    /** JASmart 物模型的视频目录属性。 */
    public Map<String, Object> modelProperties(DeviceTarget target) {
        List<Map<String, Object>> videoList = new ArrayList<>();
        List<LiveChannel> current = channels.getOrDefault(target, Collections.emptyList());
        for (LiveChannel channel : current) {
            Map<String, Object> video = new LinkedHashMap<>();
            video.put("name", target == DeviceTarget.DOCK
                    ? "机场视频-" + channel.getCameraIndex()
                    : "飞机视频-" + lensName(channel.getVideoType()));
            // 仅上报属性使用 live，内部仍保留道通完整通道 ID。
            video.put("videoId", "live");
            List<String> types = channel.getSwitchableVideoTypes().isEmpty()
                    ? Collections.singletonList(channel.getVideoType())
                    : channel.getSwitchableVideoTypes();
            video.put("videoTypes", target == DeviceTarget.DOCK
                    ? videoType(channel.getVideoType()) : videoTypes(types));
            videoList.add(video);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("videoList", videoList);
        return result;
    }

    public Map<String, Object> videoListProperty(DeviceTarget target) {
        return Collections.singletonMap("videoList", modelProperties(target).get("videoList"));
    }

    public Map<DeviceTarget, List<LiveChannel>> snapshot() {
        return channels;
    }

    private LiveChannel find(DeviceTarget target, String videoId) {
        List<LiveChannel> candidates = channels.getOrDefault(target, Collections.emptyList());
        if (videoId != null && !videoId.trim().isEmpty()) {
            return candidates.stream()
                    .filter(channel -> videoId.equals(channel.getVideoId()))
                    .findFirst().orElse(null);
        }
        return null;
    }

    private static List<LiveChannel> parse(JsonNode array, String sourceSn) {
        if (!array.isArray()) {
            return Collections.emptyList();
        }
        List<LiveChannel> result = new ArrayList<>();
        for (JsonNode item : array) {
            String videoId = item.path("video_id").asText("").trim();
            String type = normalizeType(item.path("video_type").asText(""), videoId);
            if (!videoId.startsWith(sourceSn + "/") || !VIDEO_TYPES.contains(type)) {
                continue;
            }
            List<String> switchableTypes = new ArrayList<>();
            item.path("switchable_video_types").forEach(value -> {
                String candidate = value.asText("").toLowerCase(Locale.ROOT);
                if (VIDEO_TYPES.contains(candidate)) {
                    switchableTypes.add(candidate);
                }
            });
            Integer cameraPosition = item.has("camera_position") && item.get("camera_position").canConvertToInt()
                    ? item.get("camera_position").intValue() : null;
            result.add(new LiveChannel(sourceSn, videoId, type,
                    item.path("camera_index").asText(""), cameraPosition,
                    switchableTypes, item.path("url").asText("")));
        }
        return Collections.unmodifiableList(result);
    }

    private static String normalizeType(String source, String videoId) {
        String type = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if (!type.isEmpty()) {
            return type;
        }
        String[] segments = videoId.split("/", -1);
        if (segments.length != 3) {
            return "";
        }
        int separator = segments[2].indexOf('-');
        return (separator > 0 ? segments[2].substring(0, separator) : segments[2])
                .toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> videoType(String type) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("name", lensName(type));
        result.put("type", type);
        return result;
    }

    private static List<Map<String, String>> videoTypes(List<String> types) {
        List<Map<String, String>> result = new ArrayList<>();
        types.forEach(type -> result.add(videoType(type)));
        return result;
    }

    private static String lensName(String type) {
        if (type == null) { return "默认"; }
        switch (type.toLowerCase(Locale.ROOT)) {
            case "zoom": return "变焦";
            case "wide": return "广角";
            case "ir": return "红外";
            case "nightvision": return "夜视";
            case "normal": return "普通";
            default: return type;
        }
    }

    private static Map<DeviceTarget, List<LiveChannel>> emptyCatalog() {
        EnumMap<DeviceTarget, List<LiveChannel>> result = new EnumMap<>(DeviceTarget.class);
        result.put(DeviceTarget.DOCK, Collections.emptyList());
        result.put(DeviceTarget.AIRCRAFT, Collections.emptyList());
        return Collections.unmodifiableMap(result);
    }
}
