package com.podmaster.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 语音告警 VO
 */
@Data
@Builder
public class AlertVO {

    private String podName;
    private String namespace;
    private String alertType;
    private String message;
    private long timestamp;
    private String severity;
}
