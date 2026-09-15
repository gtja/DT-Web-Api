package com.jingansi.autel.gateway.live;

import lombok.Value;

/** 同一次 capacity 响应中的真实通道和播放地址。 */
@Value
public class PlaybackSource {
    String videoId;
    String url;
}
