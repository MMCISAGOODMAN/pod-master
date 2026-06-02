package com.podmaster.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Pod 列表展示 VO
 */
@Data
@Builder
public class PodVO {

    private String name;
    private String namespace;
    private String status;
    private String phase;
    private String nodeName;
    private String podIp;
    private int restartCount;
    private String startTime;
    private String businessDomain;
    private Map<String, String> labels;
    private String cpuUsage;
    private String memoryUsage;
    private boolean abnormal;
    private String abnormalReason;
}
