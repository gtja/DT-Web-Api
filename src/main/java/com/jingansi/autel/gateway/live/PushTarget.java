package com.jingansi.autel.gateway.live;

import lombok.Value;

@Value
public class PushTarget {
    String url;
    String streamId;
}
