package com.podmaster.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Pod 详情 VO
 */
@Data
@Builder
public class PodDetailVO {

    private String name;
    private String namespace;
    private String status;
    private String phase;
    private String nodeName;
    private String podIp;
    private String hostIp;
    private int restartCount;
    private String startTime;
    private String businessDomain;
    private Map<String, String> labels;
    private Map<String, String> annotations;
    private List<ContainerInfo> containers;
    private List<ConditionInfo> conditions;
    private String cpuRequest;
    private String memoryRequest;
    private String cpuLimit;
    private String memoryLimit;

    @Data
    @Builder
    public static class ContainerInfo {
        private String name;
        private String image;
        private boolean ready;
        private int restartCount;
        private String state;
    }

    @Data
    @Builder
    public static class ConditionInfo {
        private String type;
        private String status;
        private String reason;
        private String message;
    }
}
