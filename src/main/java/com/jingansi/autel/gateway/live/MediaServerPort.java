package com.jingansi.autel.gateway.live;

import com.jingansi.autel.gateway.domain.DeviceTarget;
import com.jingansi.smart.common.MessageHeader;

import java.util.concurrent.CompletableFuture;

public interface MediaServerPort {

    CompletableFuture<PushTarget> requestPushTarget(DeviceTarget target,
                                                    MessageHeader sourceHeader,
                                                    String mediaVideoId);
}

