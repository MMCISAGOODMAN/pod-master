package com.podmaster.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 业务域资源成本 VO
 */
@Data
@Builder
public class CostVO {

    private double totalDailyCost;
    private String currency;
    private List<DomainCost> domainCosts;

    @Data
    @Builder
    public static class DomainCost {
        private String domain;
        private int podCount;
        private double cpuCores;
        private double memoryGb;
        private double dailyCost;
        private double costPercent;
        private List<PodCostItem> topPods;
    }

    @Data
    @Builder
    public static class PodCostItem {
        private String name;
        private String namespace;
        private double dailyCost;
        private double cpuCores;
        private double memoryGb;
    }
}
