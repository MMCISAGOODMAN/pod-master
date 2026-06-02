package com.podmaster.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 业务流量拓扑 VO
 */
@Data
@Builder
public class TopologyVO {

    private List<DomainGroup> domains;
    private List<ServiceEdge> edges;
    private List<AlertPod> alertPods;

    @Data
    @Builder
    public static class DomainGroup {
        private String domain;
        private List<TopologyNode> pods;
    }

    @Data
    @Builder
    public static class TopologyNode {
        private String id;
        private String name;
        private String namespace;
        private String status;
        private String businessDomain;
        private double cpuUsage;
        private double memoryUsage;
        private int restartCount;
        private boolean abnormal;
        private List<String> recentErrorLogs;
        private List<String> upstreamIds;
        private List<String> downstreamIds;
    }

    @Data
    @Builder
    public static class ServiceEdge {
        private String sourceId;
        private String targetId;
        private String sourceService;
        private String targetService;
        private double qps;
        private double errorRate;
        private boolean hasError;
    }

    @Data
    @Builder
    public static class AlertPod {
        private String podId;
        private String name;
        private String namespace;
        private String alertType;
        private String message;
    }
}
