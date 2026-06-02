package com.podmaster.controller;

import com.podmaster.common.PageResult;
import com.podmaster.common.Result;
import com.podmaster.service.CostAlertService;
import com.podmaster.service.LogDiagnosisService;
import com.podmaster.service.PodCockpitService;
import com.podmaster.service.PodService;
import com.podmaster.service.TopologyService;
import com.podmaster.util.K8sClientUtil;
import com.podmaster.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Pod 管理全套 REST API
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PodController {

    private final PodService podService;
    private final TopologyService topologyService;
    private final LogDiagnosisService logDiagnosisService;
    private final CostAlertService costAlertService;
    private final PodCockpitService podCockpitService;
    private final K8sClientUtil k8sClientUtil;

    /** 集群连接状态 */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", k8sClientUtil.isConnected() ? "UP" : "DOWN",
                "k8s", k8sClientUtil.getConnectionInfo()
        ));
    }

    /** 命名空间列表 */
    @GetMapping("/namespaces")
    public Result<List<String>> namespaces() {
        return Result.ok(podService.listNamespaces());
    }

    /** Pod 分页列表 */
    @GetMapping("/pods")
    public Result<PageResult<PodVO>> listPods(
            @RequestParam(defaultValue = "default") String namespace,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(podService.listPods(namespace, keyword, page, pageSize));
    }

    /** Pod 详情 */
    @GetMapping("/pods/{namespace}/{name}")
    public Result<PodDetailVO> podDetail(@PathVariable String namespace, @PathVariable String name) {
        return Result.ok(podService.getPodDetail(namespace, name));
    }

    /** Pod 日志 */
    @GetMapping("/pods/{namespace}/{name}/logs")
    public Result<String> podLogs(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(defaultValue = "500") int tailLines) {
        return Result.ok(podService.getPodLogs(namespace, name, tailLines));
    }

    /** 重启 Pod（删除后由控制器重建） */
    @PostMapping("/pods/{namespace}/{name}/restart")
    public Result<Void> restartPod(@PathVariable String namespace, @PathVariable String name) {
        podService.restartPod(namespace, name);
        return Result.ok();
    }

    /** 删除 Pod */
    @DeleteMapping("/pods/{namespace}/{name}")
    public Result<Void> deletePod(@PathVariable String namespace, @PathVariable String name) {
        podService.deletePod(namespace, name);
        return Result.ok();
    }

    /** 批量重启 */
    @PostMapping("/pods/batch/restart")
    public Result<Void> batchRestart(@RequestBody List<Map<String, String>> pods) {
        podService.batchRestartPods(pods);
        return Result.ok();
    }

    /** 批量删除 */
    @PostMapping("/pods/batch/delete")
    public Result<Void> batchDelete(@RequestBody List<Map<String, String>> pods) {
        podService.batchDeletePods(pods);
        return Result.ok();
    }

    /** 业务流量拓扑 */
    @GetMapping("/topology")
    public Result<TopologyVO> topology(@RequestParam(defaultValue = "all") String namespace) {
        return Result.ok(topologyService.buildTopology(namespace));
    }

    /** AI 日志诊断 */
    @GetMapping("/pods/{namespace}/{name}/diagnose")
    public Result<LogDiagnosisVO> diagnose(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(defaultValue = "1000") int tailLines) {
        return Result.ok(logDiagnosisService.diagnose(namespace, name, tailLines));
    }

    /** 资源成本统计 */
    @GetMapping("/cost")
    public Result<CostVO> cost(@RequestParam(defaultValue = "all") String namespace) {
        return Result.ok(costAlertService.calculateCost(namespace));
    }

    /** 异常告警检测（供语音播报轮询） */
    @GetMapping("/alerts")
    public Result<List<AlertVO>> alerts(@RequestParam(defaultValue = "all") String namespace) {
        return Result.ok(costAlertService.detectAlerts(namespace));
    }

    /** Pod 进入工作台聚合数据 */
    @GetMapping("/pods/{namespace}/{name}/cockpit")
    public Result<PodCockpitVO> cockpit(@PathVariable String namespace, @PathVariable String name) {
        return Result.ok(podCockpitService.loadCockpit(namespace, name));
    }

    /** Pod 事件列表 */
    @GetMapping("/pods/{namespace}/{name}/events")
    public Result<List<PodEventVO>> podEvents(@PathVariable String namespace, @PathVariable String name) {
        return Result.ok(podCockpitService.listPodEvents(namespace, name));
    }

    /** Pod 终端执行命令 */
    @PostMapping("/pods/{namespace}/{name}/exec")
    public Result<PodExecResultVO> exec(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestBody Map<String, String> body) {
        return Result.ok(podCockpitService.execCommand(
                namespace, name, body.get("command"), body.get("container")));
    }
}
