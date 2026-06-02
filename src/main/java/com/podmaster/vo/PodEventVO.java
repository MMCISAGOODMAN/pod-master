package com.podmaster.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Pod 事件 VO
 */
@Data
@Builder
public class PodEventVO {

    private String type;
    private String reason;
    private String message;
    private String timestamp;
    private String count;
}
