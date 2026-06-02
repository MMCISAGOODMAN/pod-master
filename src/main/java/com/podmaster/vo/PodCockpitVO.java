package com.podmaster.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Pod 进入工作台聚合数据
 */
@Data
@Builder
public class PodCockpitVO {

    private PodDetailVO detail;
    private String logs;
    private List<PodEventVO> events;
    private LogDiagnosisVO diagnosis;
    private double cpuUsagePercent;
    private double memoryUsagePercent;
}
