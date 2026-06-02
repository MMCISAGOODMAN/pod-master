package com.podmaster.service;

import com.podmaster.common.PageResult;
import com.podmaster.util.K8sClientUtil;
import com.podmaster.util.LogUtils;
import com.podmaster.util.PodUtils;
import com.podmaster.vo.PodDetailVO;
import com.podmaster.vo.PodVO;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pod 基础管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PodService {

    private final K8sClientUtil k8sClientUtil;

    public List<String> listNamespaces() {
        k8sClientUtil.ensureConnected();
        KubernetesClient client = k8sClientUtil.getClient();
        return client.namespaces().list().getItems().stream()
                .map(ns -> ns.getMetadata().getName())
                .sorted()
                .collect(Collectors.toList());
    }

    public PageResult<PodVO> listPods(String namespace, String keyword, int page, int pageSize) {
        k8sClientUtil.ensureConnected();
        KubernetesClient client = k8sClientUtil.getClient();
        List<Pod> pods;
        if (namespace == null || namespace.isBlank() || "all".equalsIgnoreCase(namespace)) {
            pods = client.pods().inAnyNamespace().list().getItems();
        } else {
            pods = client.pods().inNamespace(namespace).list().getItems();
        }

        String kw = keyword != null ? keyword.trim().toLowerCase() : "";
        List<PodVO> all = pods.stream()
                .filter(p -> kw.isEmpty() || p.getMetadata().getName().toLowerCase().contains(kw))
                .map(this::toPodVO)
                .sorted(Comparator.comparing(PodVO::getNamespace).thenComparing(PodVO::getName))
                .collect(Collectors.toList());

        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(all.size(), from + pageSize);
        List<PodVO> pageList = from >= all.size() ? Collections.emptyList() : all.subList(from, to);
        return new PageResult<>(pageList, all.size(), page, pageSize);
    }

    public PodDetailVO getPodDetail(String namespace, String name) {
        Pod pod = k8sClientUtil.getClient().pods().inNamespace(namespace).withName(name).get();
        if (pod == null) {
            throw new IllegalArgumentException("Pod not found: " + namespace + "/" + name);
        }
        return toPodDetailVO(pod);
    }

    public String getPodLogs(String namespace, String name, int tailLines) {
        int lines = tailLines > 0 ? tailLines : 500;
        return LogUtils.tailLog(
                k8sClientUtil.getClient().pods().inNamespace(namespace).withName(name),
                lines);
    }

    public void restartPod(String namespace, String name) {
        KubernetesClient client = k8sClientUtil.getClient();
        Pod pod = client.pods().inNamespace(namespace).withName(name).get();
        if (pod == null) {
            throw new IllegalArgumentException("Pod not found");
        }
        client.pods().inNamespace(namespace).withName(name).delete();
        log.info("Pod restarted (deleted): {}/{}", namespace, name);
    }

    public void deletePod(String namespace, String name) {
        boolean deleted = k8sClientUtil.getClient().pods()
                .inNamespace(namespace).withName(name).delete().size() > 0;
        if (!deleted) {
            throw new IllegalArgumentException("Pod not found or already deleted");
        }
        log.info("Pod deleted: {}/{}", namespace, name);
    }

    public void batchDeletePods(List<Map<String, String>> pods) {
        for (Map<String, String> p : pods) {
            deletePod(p.get("namespace"), p.get("name"));
        }
    }

    public void batchRestartPods(List<Map<String, String>> pods) {
        for (Map<String, String> p : pods) {
            restartPod(p.get("namespace"), p.get("name"));
        }
    }

    public List<Pod> listAllPods(String namespace) {
        KubernetesClient client = k8sClientUtil.getClient();
        if (namespace == null || namespace.isBlank() || "all".equalsIgnoreCase(namespace)) {
            return client.pods().inAnyNamespace().list().getItems();
        }
        return client.pods().inNamespace(namespace).list().getItems();
    }

    private PodVO toPodVO(Pod pod) {
        PodStatus status = pod.getStatus();
        return PodVO.builder()
                .name(pod.getMetadata().getName())
                .namespace(pod.getMetadata().getNamespace())
                .status(resolveDisplayStatus(pod))
                .phase(PodUtils.getPhase(pod))
                .nodeName(pod.getSpec() != null ? pod.getSpec().getNodeName() : null)
                .podIp(status != null ? status.getPodIP() : null)
                .restartCount(PodUtils.getRestartCount(pod))
                .startTime(status != null && status.getStartTime() != null
                        ? status.getStartTime() : null)
                .businessDomain(PodUtils.resolveBusinessDomain(pod))
                .labels(pod.getMetadata().getLabels() != null
                        ? pod.getMetadata().getLabels() : Collections.emptyMap())
                .cpuUsage(String.format("%.2f", estimateCpuUsage(pod)))
                .memoryUsage(String.format("%.0fMi", estimateMemoryUsageMi(pod)))
                .abnormal(PodUtils.isAbnormal(pod))
                .abnormalReason(PodUtils.getAbnormalReason(pod))
                .build();
    }

    private PodDetailVO toPodDetailVO(Pod pod) {
        PodStatus status = pod.getStatus();
        List<PodDetailVO.ContainerInfo> containers = new ArrayList<>();
        if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
            Map<String, ContainerStatus> statusMap = new HashMap<>();
            if (status != null && status.getContainerStatuses() != null) {
                for (ContainerStatus cs : status.getContainerStatuses()) {
                    statusMap.put(cs.getName(), cs);
                }
            }
            for (Container c : pod.getSpec().getContainers()) {
                ContainerStatus cs = statusMap.get(c.getName());
                containers.add(PodDetailVO.ContainerInfo.builder()
                        .name(c.getName())
                        .image(c.getImage())
                        .ready(cs != null && Boolean.TRUE.equals(cs.getReady()))
                        .restartCount(cs != null ? cs.getRestartCount() : 0)
                        .state(resolveContainerState(cs))
                        .build());
            }
        }

        List<PodDetailVO.ConditionInfo> conditions = new ArrayList<>();
        if (status != null && status.getConditions() != null) {
            for (PodCondition pc : status.getConditions()) {
                conditions.add(PodDetailVO.ConditionInfo.builder()
                        .type(pc.getType())
                        .status(pc.getStatus())
                        .reason(pc.getReason())
                        .message(pc.getMessage())
                        .build());
            }
        }

        ResourceHolder res = aggregateResources(pod);
        return PodDetailVO.builder()
                .name(pod.getMetadata().getName())
                .namespace(pod.getMetadata().getNamespace())
                .status(resolveDisplayStatus(pod))
                .phase(PodUtils.getPhase(pod))
                .nodeName(pod.getSpec() != null ? pod.getSpec().getNodeName() : null)
                .podIp(status != null ? status.getPodIP() : null)
                .hostIp(status != null ? status.getHostIP() : null)
                .restartCount(PodUtils.getRestartCount(pod))
                .startTime(status != null ? status.getStartTime() : null)
                .businessDomain(PodUtils.resolveBusinessDomain(pod))
                .labels(pod.getMetadata().getLabels())
                .annotations(pod.getMetadata().getAnnotations())
                .containers(containers)
                .conditions(conditions)
                .cpuRequest(res.cpuRequest)
                .memoryRequest(res.memoryRequest)
                .cpuLimit(res.cpuLimit)
                .memoryLimit(res.memoryLimit)
                .build();
    }

    private String resolveDisplayStatus(Pod pod) {
        if (PodUtils.isAbnormal(pod)) {
            return "Failed";
        }
        String phase = PodUtils.getPhase(pod);
        if ("Running".equals(phase)) {
            return "Running";
        }
        if ("Pending".equals(phase)) {
            return "Pending";
        }
        if ("Succeeded".equals(phase)) {
            return "Succeeded";
        }
        return phase;
    }

    private String resolveContainerState(ContainerStatus cs) {
        if (cs == null || cs.getState() == null) {
            return "Unknown";
        }
        if (cs.getState().getRunning() != null) {
            return "Running";
        }
        if (cs.getState().getWaiting() != null) {
            return "Waiting:" + cs.getState().getWaiting().getReason();
        }
        if (cs.getState().getTerminated() != null) {
            return "Terminated:" + cs.getState().getTerminated().getReason();
        }
        return "Unknown";
    }

    private double estimateCpuUsage(Pod pod) {
        double base = PodUtils.sumContainerCpuRequest(pod);
        int restarts = PodUtils.getRestartCount(pod);
        double jitter = (Math.abs(pod.getMetadata().getName().hashCode()) % 50) / 100.0;
        if (PodUtils.isAbnormal(pod)) {
            return Math.min(base * 0.3 + jitter, base);
        }
        return Math.min(base * (0.5 + jitter) + restarts * 0.05, base * 1.2);
    }

    private double estimateMemoryUsageMi(Pod pod) {
        double gb = PodUtils.sumContainerMemoryGb(pod);
        double mi = gb * 1024;
        int restarts = PodUtils.getRestartCount(pod);
        if (PodUtils.isAbnormal(pod)) {
            return mi * 0.9;
        }
        return mi * (0.6 + (restarts * 0.05));
    }

    private ResourceHolder aggregateResources(Pod pod) {
        ResourceHolder holder = new ResourceHolder();
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return holder;
        }
        double cpuReq = 0, memReq = 0, cpuLim = 0, memLim = 0;
        for (Container c : pod.getSpec().getContainers()) {
            if (c.getResources() != null) {
                if (c.getResources().getRequests() != null) {
                    Quantity cpu = c.getResources().getRequests().get("cpu");
                    Quantity mem = c.getResources().getRequests().get("memory");
                    if (cpu != null) {
                        cpuReq += PodUtils.parseCpuCores(cpu.getAmount() + format(cpu.getFormat()));
                    }
                    if (mem != null) {
                        memReq += PodUtils.parseMemoryGb(mem.getAmount() + format(mem.getFormat()));
                    }
                }
                if (c.getResources().getLimits() != null) {
                    Quantity cpu = c.getResources().getLimits().get("cpu");
                    Quantity mem = c.getResources().getLimits().get("memory");
                    if (cpu != null) {
                        cpuLim += PodUtils.parseCpuCores(cpu.getAmount() + format(cpu.getFormat()));
                    }
                    if (mem != null) {
                        memLim += PodUtils.parseMemoryGb(mem.getAmount() + format(mem.getFormat()));
                    }
                }
            }
        }
        holder.cpuRequest = cpuReq > 0 ? String.format("%.2f cores", cpuReq) : "N/A";
        holder.memoryRequest = memReq > 0 ? String.format("%.2f Gi", memReq) : "N/A";
        holder.cpuLimit = cpuLim > 0 ? String.format("%.2f cores", cpuLim) : "N/A";
        holder.memoryLimit = memLim > 0 ? String.format("%.2f Gi", memLim) : "N/A";
        return holder;
    }

    private String format(String f) {
        return f != null ? f : "";
    }

    private static class ResourceHolder {
        String cpuRequest = "N/A";
        String memoryRequest = "N/A";
        String cpuLimit = "N/A";
        String memoryLimit = "N/A";
    }
}
