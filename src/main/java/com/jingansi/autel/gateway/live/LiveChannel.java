package com.jingansi.autel.gateway.live;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Getter
public final class LiveChannel {

    private final String sourceSn;
    private final String videoId;
    private final String videoType;
    private final String cameraIndex;
    private final Integer cameraPosition;
    private final List<String> switchableVideoTypes;
    private final String url;

    public LiveChannel(String sourceSn,
                       String videoId,
                       String videoType,
                       String cameraIndex,
                       Integer cameraPosition,
                       List<String> switchableVideoTypes) {
        this(sourceSn, videoId, videoType, cameraIndex, cameraPosition,
                switchableVideoTypes, "");
    }

    public LiveChannel(String sourceSn,
                       String videoId,
                       String videoType,
                       String cameraIndex,
                       Integer cameraPosition,
                       List<String> switchableVideoTypes,
                       String url) {
        this.sourceSn = Objects.requireNonNull(sourceSn, "sourceSn");
        this.videoId = Objects.requireNonNull(videoId, "videoId");
        this.videoType = Objects.requireNonNull(videoType, "videoType");
        this.cameraIndex = cameraIndex;
        this.cameraPosition = cameraPosition;
        this.switchableVideoTypes = Collections.unmodifiableList(
                new ArrayList<>(switchableVideoTypes == null ? Collections.emptyList() : switchableVideoTypes));
        this.url = url == null ? "" : url.trim();
    }

}
