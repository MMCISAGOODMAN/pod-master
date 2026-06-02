package com.podmaster.util;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Pod 相关工具方法
 */
public final class PodUtils {

    private PodUtils() {
    }

    /** 从标签推断业务域 */
    public static String resolveBusinessDomain(Pod pod) {
        Map<String, String> labels = pod.getMetadata().getLabels();
        if (labels == null) {
            return "default";
        }
        if (labels.containsKey("business-domain")) {
            return labels.get("business-domain");
        }
        if (labels.containsKey("businessDomain")) {
            return labels.get("businessDomain");
        }
        if (labels.containsKey("app.kubernetes.io/part-of")) {
            return labels.get("app.kubernetes.io/part-of");
        }
        if (labels.containsKey("app")) {
            return labels.get("app");
        }
        if (labels.containsKey("tier")) {
            return labels.get("tier");
        }
        return pod.getMetadata().getNamespace();
    }

    public static int getRestartCount(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return 0;
        }
        return pod.getStatus().getContainerStatuses().stream()
                .mapToInt(ContainerStatus::getRestartCount)
                .sum();
    }

    public static String getPhase(Pod pod) {
        return pod.getStatus() != null && pod.getStatus().getPhase() != null
                ? pod.getStatus().getPhase() : "Unknown";
    }

    public static boolean isAbnormal(Pod pod) {
        String phase = getPhase(pod);
        if ("Failed".equals(phase) || "Unknown".equals(phase)) {
            return true;
        }
        if (getRestartCount(pod) >= 3) {
            return true;
        }
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                if (cs.getState() != null && cs.getState().getWaiting() != null) {
                    String reason = cs.getState().getWaiting().getReason();
                    if ("CrashLoopBackOff".equals(reason) || "ImagePullBackOff".equals(reason)
                            || "ErrImagePull".equals(reason) || "OOMKilled".equals(reason)) {
                        return true;
                    }
                }
                if (cs.getState() != null && cs.getState().getTerminated() != null) {
                    String reason = cs.getState().getTerminated().getReason();
                    if ("OOMKilled".equals(reason) || "Error".equals(reason)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static String getAbnormalReason(Pod pod) {
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                if (cs.getState() != null && cs.getState().getWaiting() != null) {
                    return cs.getState().getWaiting().getReason();
                }
                if (cs.getState() != null && cs.getState().getTerminated() != null) {
                    return cs.getState().getTerminated().getReason();
                }
            }
        }
        if (getRestartCount(pod) >= 3) {
            return "FrequentRestarts";
        }
        return getPhase(pod);
    }

    /** 解析 CPU 请求量为核数 */
    public static double parseCpuCores(String cpu) {
        if (cpu == null || cpu.isBlank()) {
            return 0.1;
        }
        if (cpu.endsWith("m")) {
            return new BigDecimal(cpu.replace("m", ""))
                    .divide(BigDecimal.valueOf(1000)).doubleValue();
        }
        return new BigDecimal(cpu).doubleValue();
    }

    /** 解析内存请求量为 GB */
    public static double parseMemoryGb(String memory) {
        if (memory == null || memory.isBlank()) {
            return 0.128;
        }
        if (memory.endsWith("Ki")) {
            return new BigDecimal(memory.replace("Ki", ""))
                    .divide(BigDecimal.valueOf(1024 * 1024)).doubleValue();
        }
        if (memory.endsWith("Mi")) {
            return new BigDecimal(memory.replace("Mi", ""))
                    .divide(BigDecimal.valueOf(1024)).doubleValue();
        }
        if (memory.endsWith("Gi")) {
            return new BigDecimal(memory.replace("Gi", "")).doubleValue();
        }
        return new BigDecimal(memory).divide(BigDecimal.valueOf(1024 * 1024 * 1024)).doubleValue();
    }

    public static double sumContainerCpuRequest(Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return 0.1;
        }
        double total = 0;
        for (Container c : pod.getSpec().getContainers()) {
            if (c.getResources() != null && c.getResources().getRequests() != null) {
                Quantity q = c.getResources().getRequests().get("cpu");
                if (q != null) {
                    total += parseCpuCores(q.getAmount() + (q.getFormat() != null ? q.getFormat() : ""));
                }
            }
        }
        return total > 0 ? total : 0.1;
    }

    public static double sumContainerMemoryGb(Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return 0.128;
        }
        double total = 0;
        for (Container c : pod.getSpec().getContainers()) {
            if (c.getResources() != null && c.getResources().getRequests() != null) {
                Quantity q = c.getResources().getRequests().get("memory");
                if (q != null) {
                    total += parseMemoryGb(q.getAmount() + (q.getFormat() != null ? q.getFormat() : ""));
                }
            }
        }
        return total > 0 ? total : 0.128;
    }

    public static String podId(String namespace, String name) {
        return namespace + "/" + name;
    }
}
