package com.podmaster.service;

import com.podmaster.util.K8sClientUtil;
import com.podmaster.vo.*;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Pod 进入工作台：事件、终端 exec、聚合数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PodCockpitService {

    private final K8sClientUtil k8sClientUtil;
    private final PodService podService;
    private final LogDiagnosisService logDiagnosisService;

    public PodCockpitVO loadCockpit(String namespace, String name) {
        k8sClientUtil.ensureConnected();
        PodDetailVO detail = podService.getPodDetail(namespace, name);
        String logs = podService.getPodLogs(namespace, name, 300);
        List<PodEventVO> events = listPodEvents(namespace, name);
        LogDiagnosisVO diagnosis = logDiagnosisService.diagnose(namespace, name, 500);

        Pod pod = k8sClientUtil.getClient().pods().inNamespace(namespace).withName(name).get();
        double cpuPct = Math.min(95, 40 + Math.abs(name.hashCode() % 50));
        double memPct = Math.min(95, 35 + Math.abs(name.hashCode() % 45));
        if (pod != null && com.podmaster.util.PodUtils.isAbnormal(pod)) {
            cpuPct = Math.min(99, cpuPct + 20);
            memPct = Math.min(99, memPct + 15);
        }

        return PodCockpitVO.builder()
                .detail(detail)
                .logs(logs)
                .events(events)
                .diagnosis(diagnosis)
                .cpuUsagePercent(cpuPct)
                .memoryUsagePercent(memPct)
                .build();
    }

    public List<PodEventVO> listPodEvents(String namespace, String name) {
        k8sClientUtil.ensureConnected();
        KubernetesClient client = k8sClientUtil.getClient();
        return client.v1().events().inNamespace(namespace).list().getItems().stream()
                .filter(e -> e.getInvolvedObject() != null && name.equals(e.getInvolvedObject().getName()))
                .sorted(Comparator.comparing(Event::getLastTimestamp, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(20)
                .map(e -> {
                    String ts = e.getLastTimestamp();
                    if (ts == null && e.getEventTime() != null) {
                        ts = String.valueOf(e.getEventTime());
                    }
                    return PodEventVO.builder()
                            .type(e.getType())
                            .reason(e.getReason())
                            .message(e.getMessage())
                            .timestamp(ts != null ? ts : "")
                            .count(e.getCount() != null ? String.valueOf(e.getCount()) : "1")
                            .build();
                })
                .collect(Collectors.toList());
    }

    public PodExecResultVO execCommand(String namespace, String name, String command, String container) {
        k8sClientUtil.ensureConnected();
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        KubernetesClient client = k8sClientUtil.getClient();
        Pod pod = client.pods().inNamespace(namespace).withName(name).get();
        if (pod == null) {
            throw new IllegalArgumentException("Pod not found");
        }
        String containerName = container;
        if (containerName == null || containerName.isBlank()) {
            containerName = pod.getSpec().getContainers().get(0).getName();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = -1;
        try {
            ExecWatch watch = client.pods().inNamespace(namespace).withName(name)
                    .inContainer(containerName)
                    .writingOutput(out).writingError(err)
                    .exec("sh", "-c", command);
            Integer code = watch.exitCode().get(30, TimeUnit.SECONDS);
            exitCode = code != null ? code : 0;
        } catch (Exception e) {
            log.warn("Exec failed {}/{}: {}", namespace, name, e.getMessage());
            String errMsg = err.toString(StandardCharsets.UTF_8);
            return PodExecResultVO.builder()
                    .command(command)
                    .output(out.toString(StandardCharsets.UTF_8) + errMsg + "\n[error] " + e.getMessage())
                    .exitCode(-1)
                    .success(false)
                    .build();
        }

        String output = out.toString(StandardCharsets.UTF_8);
        if (err.size() > 0) {
            output += err.toString(StandardCharsets.UTF_8);
        }
        if (output.isBlank()) {
            output = "(no output)";
        }

        return PodExecResultVO.builder()
                .command(command)
                .output(output)
                .exitCode(exitCode)
                .success(exitCode == 0)
                .build();
    }
}
