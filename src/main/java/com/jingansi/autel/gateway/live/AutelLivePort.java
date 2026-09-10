package com.jingansi.autel.gateway.live;

import com.fasterxml.jackson.databind.JsonNode;

/** 道通直播 REST 接口，便于单元测试替换。 */
public interface AutelLivePort {

    JsonNode getLiveStatus(String dockSn, String aircraftSn);

    JsonNode getCapacity(String sourceSn);

    JsonNode start(LiveChannel channel, String pushUrl, int urlType, int quality);

    JsonNode switchStream(LiveChannel channel, String pushUrl, int urlType, int quality);

    void stop(LiveChannel channel, int urlType, int quality);
}
