package com.podmaster.service;

import com.podmaster.config.K8sProperties;
import com.podmaster.util.PodUtils;
import com.podmaster.vo.AlertVO;
import com.podmaster.vo.CostVO;
import io.fabric8.kubernetes.api.model.Pod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 资源成本统计与异常告警服务
 */
@Service
@RequiredArgsConstructor
public class CostAlertService {

    private final PodService podService;
    private final K8sProperties k8sProperties;

    public CostVO calculateCost(String namespace) {
        List<Pod> pods = podService.listAllPods(namespace);
        Map<String, List<Pod>> byDomain = pods.stream()
                .collect(Collectors.groupingBy(PodUtils::resolveBusinessDomain));

        double cpuPrice = k8sProperties.getCost().getCpuPerCoreDay();
        double memPrice = k8sProperties.getCost().getMemoryPerGbDay();

        List<CostVO.DomainCost> domainCosts = new ArrayList<>();
        double totalCost = 0;

        for (Map.Entry<String, List<Pod>> entry : byDomain.entrySet()) {
            String domain = entry.getKey();
            List<Pod> domainPods = entry.getValue();
            double cpuCores = domainPods.stream().mapToDouble(PodUtils::sumContainerCpuRequest).sum();
            double memoryGb = domainPods.stream().mapToDouble(PodUtils::sumContainerMemoryGb).sum();
            double dailyCost = cpuCores * cpuPrice + memoryGb * memPrice;
            totalCost += dailyCost;

            List<CostVO.PodCostItem> topPods = domainPods.stream()
                    .map(p -> {
                        double c = PodUtils.sumContainerCpuRequest(p);
                        double m = PodUtils.sumContainerMemoryGb(p);
                        double cost = c * cpuPrice + m * memPrice;
                        return CostVO.PodCostItem.builder()
                                .name(p.getMetadata().getName())
                                .namespace(p.getMetadata().getNamespace())
                                .cpuCores(Math.round(c * 100) / 100.0)
                                .memoryGb(Math.round(m * 1000) / 1000.0)
                                .dailyCost(Math.round(cost * 100) / 100.0)
                                .build();
                    })
                    .sorted(Comparator.comparingDouble(CostVO.PodCostItem::getDailyCost).reversed())
                    .limit(5)
                    .collect(Collectors.toList());

            domainCosts.add(CostVO.DomainCost.builder()
                    .domain(domain)
                    .podCount(domainPods.size())
                    .cpuCores(Math.round(cpuCores * 100) / 100.0)
                    .memoryGb(Math.round(memoryGb * 1000) / 1000.0)
                    .dailyCost(Math.round(dailyCost * 100) / 100.0)
                    .costPercent(0)
                    .topPods(topPods)
                    .build());
        }

        final double finalTotal = totalCost;
        domainCosts.forEach(d -> d.setCostPercent(
                finalTotal > 0 ? Math.round(d.getDailyCost() / finalTotal * 1000) / 10.0 : 0));

        domainCosts.sort(Comparator.comparingDouble(CostVO.DomainCost::getDailyCost).reversed());

        return CostVO.builder()
                .totalDailyCost(Math.round(totalCost * 100) / 100.0)
                .currency("CNY")
                .domainCosts(domainCosts)
                .build();
    }

    public List<AlertVO> detectAlerts(String namespace) {
        List<Pod> pods = podService.listAllPods(namespace);
        List<AlertVO> alerts = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Pod pod : pods) {
            String ns = pod.getMetadata().getNamespace();
            String name = pod.getMetadata().getName();
            int restarts = PodUtils.getRestartCount(pod);

            if ("Failed".equals(PodUtils.getPhase(pod))) {
                alerts.add(buildAlert(ns, name, "Crash", "Pod 已崩溃，状态 Failed", "critical", now));
            }
            if (restarts >= 3) {
                alerts.add(buildAlert(ns, name, "FrequentRestart",
                        "Pod 频繁重启，累计 " + restarts + " 次", "critical", now));
            }
            String reason = PodUtils.getAbnormalReason(pod);
            if ("OOMKilled".equals(reason)) {
                alerts.add(buildAlert(ns, name, "OOM", "Pod 因内存溢出被 Kill", "critical", now));
            }
            if ("CrashLoopBackOff".equals(reason)) {
                alerts.add(buildAlert(ns, name, "CrashLoop", "Pod 处于 CrashLoopBackOff", "critical", now));
            }
        }
        return alerts;
    }

    private AlertVO buildAlert(String ns, String name, String type, String msg,
                               String severity, long timestamp) {
        return AlertVO.builder()
                .namespace(ns)
                .podName(name)
                .alertType(type)
                .message(msg)
                .severity(severity)
                .timestamp(timestamp)
                .build();
    }
}
