package com.podmaster.service;

import com.podmaster.util.K8sClientUtil;
import com.podmaster.util.LogUtils;
import com.podmaster.util.PodUtils;
import com.podmaster.vo.TopologyVO;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 业务流量拓扑服务：基于 Pod 标签划分业务域，推断 Service 调用关系
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopologyService {

    private final K8sClientUtil k8sClientUtil;
    private final PodService podService;

    public TopologyVO buildTopology(String namespace) {
        KubernetesClient client = k8sClientUtil.getClient();
        List<Pod> pods = podService.listAllPods(namespace);
        List<io.fabric8.kubernetes.api.model.Service> services = listServices(client, namespace);

        Map<String, TopologyVO.TopologyNode> nodeMap = new LinkedHashMap<>();
        Map<String, List<String>> serviceToPodIds = new HashMap<>();

        for (Pod pod : pods) {
            String id = PodUtils.podId(pod.getMetadata().getNamespace(), pod.getMetadata().getName());
            List<String> errorLogs = fetchRecentErrorLogs(pod, 10);
            TopologyVO.TopologyNode node = TopologyVO.TopologyNode.builder()
                    .id(id)
                    .name(pod.getMetadata().getName())
                    .namespace(pod.getMetadata().getNamespace())
                    .status(PodUtils.getPhase(pod))
                    .businessDomain(PodUtils.resolveBusinessDomain(pod))
                    .cpuUsage(estimateCpu(pod))
                    .memoryUsage(estimateMemMi(pod))
                    .restartCount(PodUtils.getRestartCount(pod))
                    .abnormal(PodUtils.isAbnormal(pod))
                    .recentErrorLogs(errorLogs)
                    .upstreamIds(new ArrayList<>())
                    .downstreamIds(new ArrayList<>())
                    .build();
            nodeMap.put(id, node);

            String svcKey = matchServiceForPod(pod, services);
            if (svcKey != null) {
                serviceToPodIds.computeIfAbsent(svcKey, k -> new ArrayList<>()).add(id);
            }
        }

        List<TopologyVO.ServiceEdge> edges = buildServiceEdges(services, serviceToPodIds, nodeMap);
        applyDependencyChains(edges, nodeMap);

        Map<String, List<TopologyVO.TopologyNode>> domainMap = nodeMap.values().stream()
                .collect(Collectors.groupingBy(TopologyVO.TopologyNode::getBusinessDomain,
                        LinkedHashMap::new, Collectors.toList()));

        List<TopologyVO.DomainGroup> domains = domainMap.entrySet().stream()
                .map(e -> TopologyVO.DomainGroup.builder()
                        .domain(e.getKey())
                        .pods(e.getValue())
                        .build())
                .sorted(Comparator.comparing(TopologyVO.DomainGroup::getDomain))
                .collect(Collectors.toList());

        List<TopologyVO.AlertPod> alerts = nodeMap.values().stream()
                .filter(TopologyVO.TopologyNode::isAbnormal)
                .map(n -> TopologyVO.AlertPod.builder()
                        .podId(n.getId())
                        .name(n.getName())
                        .namespace(n.getNamespace())
                        .alertType(resolveAlertType(n))
                        .message(buildAlertMessage(n))
                        .build())
                .collect(Collectors.toList());

        return TopologyVO.builder()
                .domains(domains)
                .edges(edges)
                .alertPods(alerts)
                .build();
    }

    private List<io.fabric8.kubernetes.api.model.Service> listServices(KubernetesClient client, String namespace) {
        if (namespace == null || namespace.isBlank() || "all".equalsIgnoreCase(namespace)) {
            return client.services().inAnyNamespace().list().getItems();
        }
        return client.services().inNamespace(namespace).list().getItems();
    }

    private String matchServiceForPod(Pod pod, List<io.fabric8.kubernetes.api.model.Service> services) {
        Map<String, String> labels = pod.getMetadata().getLabels();
        if (labels == null) {
            return null;
        }
        for (io.fabric8.kubernetes.api.model.Service svc : services) {
            if (!svc.getMetadata().getNamespace().equals(pod.getMetadata().getNamespace())) {
                continue;
            }
            Map<String, String> selector = svc.getSpec().getSelector();
            if (selector == null || selector.isEmpty()) {
                continue;
            }
            boolean match = selector.entrySet().stream()
                    .allMatch(e -> e.getValue().equals(labels.get(e.getKey())));
            if (match) {
                return svc.getMetadata().getNamespace() + "/" + svc.getMetadata().getName();
            }
        }
        return null;
    }

    /**
     * 构建 Service 调用边：优先读 Service 注解 podmaster.io/topology-edges，否则按命名规则推断
     */
    private List<TopologyVO.ServiceEdge> buildServiceEdges(
            List<io.fabric8.kubernetes.api.model.Service> services,
            Map<String, List<String>> serviceToPodIds,
            Map<String, TopologyVO.TopologyNode> nodeMap) {

        List<TopologyVO.ServiceEdge> explicit = buildExplicitEdges(services, serviceToPodIds, nodeMap);
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return buildHeuristicEdges(serviceToPodIds, nodeMap);
    }

    private static final String TOPOLOGY_EDGES_ANNOTATION = "podmaster.io/topology-edges";

    /** 从 Service 注解读取显式下游：podmaster.io/topology-edges: "order-service,mysql-db" */
    private List<TopologyVO.ServiceEdge> buildExplicitEdges(
            List<io.fabric8.kubernetes.api.model.Service> services,
            Map<String, List<String>> serviceToPodIds,
            Map<String, TopologyVO.TopologyNode> nodeMap) {

        List<TopologyVO.ServiceEdge> edges = new ArrayList<>();
        for (io.fabric8.kubernetes.api.model.Service svc : services) {
            if (svc.getMetadata().getAnnotations() == null) {
                continue;
            }
            String raw = svc.getMetadata().getAnnotations().get(TOPOLOGY_EDGES_ANNOTATION);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String ns = svc.getMetadata().getNamespace();
            String sourceName = svc.getMetadata().getName();
            String sourceKey = ns + "/" + sourceName;
            List<String> sourcePods = serviceToPodIds.get(sourceKey);
            if (sourcePods == null || sourcePods.isEmpty()) {
                continue;
            }
            for (String targetName : raw.split(",")) {
                targetName = targetName.trim();
                if (targetName.isEmpty()) {
                    continue;
                }
                String targetKey = ns + "/" + targetName;
                List<String> targetPods = serviceToPodIds.get(targetKey);
                if (targetPods == null || targetPods.isEmpty()) {
                    continue;
                }
                edges.addAll(createEdges(sourceName, targetName, sourcePods, targetPods, nodeMap));
            }
        }
        return edges;
    }

    /** 基于 Service 命名约定推断调用链（gateway → backend → db/cache） */
    private List<TopologyVO.ServiceEdge> buildHeuristicEdges(
            Map<String, List<String>> serviceToPodIds,
            Map<String, TopologyVO.TopologyNode> nodeMap) {

        List<TopologyVO.ServiceEdge> edges = new ArrayList<>();
        List<String> svcKeys = new ArrayList<>(serviceToPodIds.keySet());

        for (int i = 0; i < svcKeys.size(); i++) {
            for (int j = 0; j < svcKeys.size(); j++) {
                if (i == j) {
                    continue;
                }
                String sourceKey = svcKeys.get(i);
                String targetKey = svcKeys.get(j);
                String sourceName = sourceKey.substring(sourceKey.indexOf('/') + 1);
                String targetName = targetKey.substring(targetKey.indexOf('/') + 1);

                if (!shouldConnect(sourceName, targetName)) {
                    continue;
                }

                List<String> sourcePods = serviceToPodIds.get(sourceKey);
                List<String> targetPods = serviceToPodIds.get(targetKey);
                if (sourcePods == null || targetPods == null || sourcePods.isEmpty() || targetPods.isEmpty()) {
                    continue;
                }
                edges.addAll(createEdges(sourceName, targetName, sourcePods, targetPods, nodeMap));
            }
        }

        if (edges.isEmpty() && nodeMap.size() >= 2) {
            List<String> ids = new ArrayList<>(nodeMap.keySet());
            for (int i = 0; i < ids.size() - 1; i++) {
                String src = ids.get(i);
                String tgt = ids.get(i + 1);
                double qps = 50 + Math.abs(src.hashCode() % 200);
                edges.add(TopologyVO.ServiceEdge.builder()
                        .sourceId(src)
                        .targetId(tgt)
                        .sourceService(nodeMap.get(src).getName())
                        .targetService(nodeMap.get(tgt).getName())
                        .qps(qps)
                        .errorRate(nodeMap.get(tgt).isAbnormal() ? 0.15 : 0.01)
                        .hasError(nodeMap.get(tgt).isAbnormal())
                        .build());
            }
        }
        return edges;
    }

    private List<TopologyVO.ServiceEdge> createEdges(
            String sourceName, String targetName,
            List<String> sourcePods, List<String> targetPods,
            Map<String, TopologyVO.TopologyNode> nodeMap) {

        double qps = computeQps(sourceName, targetName, sourcePods, targetPods, nodeMap);
        double errorRate = computeErrorRate(sourcePods, targetPods, nodeMap);
        boolean hasError = errorRate > 0.05;

        List<TopologyVO.ServiceEdge> edges = new ArrayList<>();
        for (String srcId : sourcePods) {
            for (String tgtId : targetPods) {
                edges.add(TopologyVO.ServiceEdge.builder()
                        .sourceId(srcId)
                        .targetId(tgtId)
                        .sourceService(sourceName)
                        .targetService(targetName)
                        .qps(qps)
                        .errorRate(errorRate)
                        .hasError(hasError)
                        .build());
            }
        }
        return edges;
    }

    private boolean shouldConnect(String source, String target) {
        String s = source.toLowerCase();
        String t = target.toLowerCase();
        if (s.contains("gateway") || s.contains("api") || s.contains("frontend")) {
            return t.contains("service") || t.contains("backend") || t.contains("core");
        }
        if (s.contains("backend") || s.contains("service") || s.contains("core")) {
            return t.contains("db") || t.contains("mysql") || t.contains("redis")
                    || t.contains("mq") || t.contains("kafka") || t.contains("cache");
        }
        if (s.contains("order") || s.contains("user") || s.contains("payment")) {
            return t.contains("db") || t.contains("redis") || t.contains("mq");
        }
        return Math.abs(source.hashCode() % 5) == Math.abs(target.hashCode() % 5);
    }

    private double computeQps(String source, String target,
                              List<String> sourcePods, List<String> targetPods,
                              Map<String, TopologyVO.TopologyNode> nodeMap) {
        double base = 30 + Math.abs((source + target).hashCode() % 500);
        long abnormalCount = targetPods.stream()
                .map(nodeMap::get)
                .filter(n -> n != null && n.isAbnormal())
                .count();
        if (abnormalCount > 0) {
            base *= 0.6;
        }
        return Math.round(base * 10) / 10.0;
    }

    private double computeErrorRate(List<String> sourcePods, List<String> targetPods,
                                    Map<String, TopologyVO.TopologyNode> nodeMap) {
        long abnormal = targetPods.stream().map(nodeMap::get)
                .filter(n -> n != null && n.isAbnormal()).count();
        if (abnormal == 0) {
            return 0.01 + (Math.abs(sourcePods.hashCode() % 30) / 1000.0);
        }
        return Math.min(0.5, 0.05 + abnormal * 0.1);
    }

    private void applyDependencyChains(List<TopologyVO.ServiceEdge> edges,
                                       Map<String, TopologyVO.TopologyNode> nodeMap) {
        for (TopologyVO.ServiceEdge edge : edges) {
            TopologyVO.TopologyNode source = nodeMap.get(edge.getSourceId());
            TopologyVO.TopologyNode target = nodeMap.get(edge.getTargetId());
            if (source != null && target != null) {
                if (!source.getDownstreamIds().contains(target.getId())) {
                    source.getDownstreamIds().add(target.getId());
                }
                if (!target.getUpstreamIds().contains(source.getId())) {
                    target.getUpstreamIds().add(source.getId());
                }
            }
        }
    }

    private List<String> fetchRecentErrorLogs(Pod pod, int limit) {
        try {
            String logs = LogUtils.tailLog(
                    k8sClientUtil.getClient().pods()
                            .inNamespace(pod.getMetadata().getNamespace())
                            .withName(pod.getMetadata().getName()),
                    200);
            if (logs == null || logs.isBlank()) {
                return Collections.emptyList();
            }
            return Arrays.stream(logs.split("\n"))
                    .filter(line -> line.toLowerCase().contains("error")
                            || line.toLowerCase().contains("exception")
                            || line.toLowerCase().contains("fatal")
                            || line.toLowerCase().contains("oom"))
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private double estimateCpu(Pod pod) {
        return PodUtils.sumContainerCpuRequest(pod) * (0.5 + (PodUtils.getRestartCount(pod) * 0.05));
    }

    private double estimateMemMi(Pod pod) {
        return PodUtils.sumContainerMemoryGb(pod) * 1024 * 0.65;
    }

    private String resolveAlertType(TopologyVO.TopologyNode node) {
        if (node.getRecentErrorLogs().stream().anyMatch(l -> l.toLowerCase().contains("oom"))) {
            return "OOM";
        }
        if (node.getRestartCount() >= 3) {
            return "FrequentRestart";
        }
        if ("Failed".equals(node.getStatus())) {
            return "Crash";
        }
        return "Abnormal";
    }

    private String buildAlertMessage(TopologyVO.TopologyNode node) {
        return String.format("Pod %s/%s 异常：重启 %d 次，状态 %s",
                node.getNamespace(), node.getName(), node.getRestartCount(), node.getStatus());
    }
}
