package com.jingansi.autel.gateway.autel;

import lombok.Getter;

@Getter
public class AutelApiException extends RuntimeException {

    private final Integer httpStatus;
    private final String businessCode;

    public AutelApiException(String message) {
        this(message, null, null, null);
    }

    public AutelApiException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    public AutelApiException(String message, Integer httpStatus, String businessCode) {
        this(message, httpStatus, businessCode, null);
    }

    private AutelApiException(String message, Integer httpStatus, String businessCode, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.businessCode = businessCode;
    }

    public boolean isUnauthorized() {
        return Integer.valueOf(401).equals(httpStatus);
    }

    public boolean isLiveNotStarted() {
        String text = getMessage() == null ? "" : getMessage().toLowerCase();
        return text.contains("未开播") || text.contains("未直播")
                || text.contains("not started") || text.contains("not live");
    }

    public boolean isDeviceOffline() {
        String text = getMessage() == null ? "" : getMessage().toLowerCase();
        return text.contains("离线") || text.contains("不在线")
                || text.contains("offline") || text.contains("not online");
    }
}
