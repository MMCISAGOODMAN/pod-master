package com.podmaster.service;

import com.podmaster.vo.LogDiagnosisVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 日志故障诊断服务：基于规则引擎 + 模式匹配识别常见 K8s/应用故障
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogDiagnosisService {

    private final PodService podService;

    private static final List<FaultPattern> FAULT_PATTERNS = List.of(
            new FaultPattern("OOM", "critical",
                    "(?i)(outofmemory|oom|killed process|java\\.lang\\.OutOfMemoryError)",
                    "内存溢出 (OOM)",
                    List.of(
                            suggestion("扩容内存 Limit/Request", "将 Pod memory limit 提升 50%-100%，检查 JVM -Xmx 配置", "high"),
                            suggestion("排查内存泄漏", "使用 heap dump 分析对象占用，检查缓存/连接池未释放", "high"),
                            suggestion("启用 HPA 自动扩缩", "基于 memory 指标配置 HorizontalPodAutoscaler", "medium")
                    )),
            new FaultPattern("NullPointer", "error",
                    "(?i)(NullPointerException|null pointer|NPE at)",
                    "空指针异常",
                    List.of(
                            suggestion("修复空值校验", "在报错堆栈对应行增加 null check 或 Optional 包装", "high"),
                            suggestion("补充单元测试", "针对空参场景编写测试用例防止回归", "medium")
                    )),
            new FaultPattern("DatabaseTimeout", "error",
                    "(?i)(timeout|connection refused|too many connections|SQLTimeoutException|Communications link failure)",
                    "数据库连接/超时",
                    List.of(
                            suggestion("检查数据库连通性", "验证 Service/Endpoint 是否正常，网络策略是否放行", "high"),
                            suggestion("优化连接池", "调整 maxPoolSize、connectionTimeout，避免连接泄漏", "high"),
                            suggestion("排查慢 SQL", "开启慢查询日志，优化索引与 SQL 语句", "medium")
                    )),
            new FaultPattern("ServiceOffline", "critical",
                    "(?i)(connection reset|no route to host|503|502|upstream connect error|service unavailable|UnknownHostException)",
                    "下游服务不可用",
                    List.of(
                            suggestion("检查目标 Pod 状态", "确认下游 Service 后端 Pod 是否 Running", "high"),
                            suggestion("验证 DNS 解析", "在 Pod 内 nslookup 目标 Service 名称", "high"),
                            suggestion("检查就绪探针", "readinessProbe 失败会导致 Service 摘除实例", "medium")
                    )),
            new FaultPattern("CrashLoop", "critical",
                    "(?i)(CrashLoopBackOff|Back-off restarting|container.*terminated|exit code [1-9])",
                    "CrashLoop 崩溃循环",
                    List.of(
                            suggestion("查看上次崩溃日志", "kubectl logs --previous 获取退出前日志", "high"),
                            suggestion("检查启动命令与配置", "确认环境变量、ConfigMap、Secret 挂载正确", "high"),
                            suggestion("调整 livenessProbe", "避免启动慢导致探针误杀", "medium")
                    )),
            new FaultPattern("ImagePull", "warning",
                    "(?i)(ImagePullBackOff|ErrImagePull|pull access denied)",
                    "镜像拉取失败",
                    List.of(
                            suggestion("检查镜像名称与 tag", "确认镜像仓库地址、版本 tag 存在", "high"),
                            suggestion("配置 imagePullSecrets", "私有仓库需挂载拉取凭证", "high")
                    )),
            new FaultPattern("DiskFull", "warning",
                    "(?i)(no space left|disk full|write.*error)",
                    "磁盘空间不足",
                    List.of(
                            suggestion("清理日志与临时文件", "挂载 emptyDir 或配置 log rotation", "high"),
                            suggestion("扩容 PVC", "持久卷空间不足时需扩容 StorageClass", "medium")
                    ))
    );

    public LogDiagnosisVO diagnose(String namespace, String name, int tailLines) {
        String rawLogs = podService.getPodLogs(namespace, name, tailLines > 0 ? tailLines : 1000);
        return analyzeLogs(namespace, name, rawLogs);
    }

    LogDiagnosisVO analyzeLogs(String namespace, String name, String rawLogs) {
        if (rawLogs == null || rawLogs.isBlank()) {
            return LogDiagnosisVO.builder()
                    .podName(name)
                    .namespace(namespace)
                    .summary("暂无日志数据")
                    .faultType("Unknown")
                    .severity("info")
                    .logLines(Collections.emptyList())
                    .errorStacks(Collections.emptyList())
                    .suggestions(Collections.emptyList())
                    .diagnosed(false)
                    .build();
        }

        String[] lines = rawLogs.split("\n");
        List<LogDiagnosisVO.LogLine> logLines = new ArrayList<>();
        List<String> errorStacks = new ArrayList<>();
        String detectedFault = "Healthy";
        String severity = "info";
        List<LogDiagnosisVO.Suggestion> suggestions = new ArrayList<>();
        StringBuilder stackBuilder = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String level = classifyLogLevel(line);
            logLines.add(LogDiagnosisVO.LogLine.builder()
                    .lineNumber(i + 1)
                    .content(line)
                    .level(level)
                    .timestamp(System.currentTimeMillis() - (lines.length - i) * 1000L)
                    .build());

            if ("error".equals(level) || "warning".equals(level)) {
                if (line.contains("at ") || line.contains("Caused by:") || line.contains("\tat")) {
                    stackBuilder.append(line).append("\n");
                } else if (stackBuilder.length() > 0) {
                    errorStacks.add(stackBuilder.toString().trim());
                    stackBuilder = new StringBuilder();
                }
            }

            for (FaultPattern pattern : FAULT_PATTERNS) {
                if (Pattern.compile(pattern.regex).matcher(line).find()) {
                    if (severityRank(pattern.severity) >= severityRank(severity)) {
                        detectedFault = pattern.faultType;
                        severity = pattern.severity;
                        suggestions = new ArrayList<>(pattern.suggestions);
                    }
                }
            }
        }

        if (stackBuilder.length() > 0) {
            errorStacks.add(stackBuilder.toString().trim());
        }

        if ("Healthy".equals(detectedFault)) {
            long errorCount = logLines.stream().filter(l -> "error".equals(l.getLevel())).count();
            if (errorCount > 0) {
                detectedFault = "GeneralError";
                severity = "warning";
                suggestions = List.of(
                        suggestion("人工审查错误日志", "发现 " + errorCount + " 条错误日志，建议逐条排查", "medium")
                );
            }
        }

        String summary = buildSummary(detectedFault, severity, errorStacks.size());

        return LogDiagnosisVO.builder()
                .podName(name)
                .namespace(namespace)
                .summary(summary)
                .faultType(detectedFault)
                .severity(severity)
                .logLines(logLines)
                .errorStacks(errorStacks.stream().distinct().limit(5).toList())
                .suggestions(suggestions)
                .diagnosed(!"Healthy".equals(detectedFault))
                .build();
    }

    private String classifyLogLevel(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("error") || lower.contains("exception") || lower.contains("fatal")
                || lower.contains("oom") || lower.contains("panic")) {
            return "error";
        }
        if (lower.contains("warn") || lower.contains("warning")) {
            return "warning";
        }
        return "info";
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "critical" -> 3;
            case "error" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
    }

    private String buildSummary(String faultType, String severity, int stackCount) {
        if ("Healthy".equals(faultType)) {
            return "日志分析未发现明显故障模式，Pod 运行正常";
        }
        FaultPattern matched = FAULT_PATTERNS.stream()
                .filter(p -> p.faultType.equals(faultType))
                .findFirst()
                .orElse(null);
        String desc = matched != null ? matched.description : faultType;
        return String.format("AI 诊断结果：[%s] %s，严重级别 %s，提取 %d 处错误堆栈",
                faultType, desc, severity, stackCount);
    }

    private static LogDiagnosisVO.Suggestion suggestion(String title, String desc, String priority) {
        return LogDiagnosisVO.Suggestion.builder()
                .title(title)
                .description(desc)
                .priority(priority)
                .build();
    }

    private record FaultPattern(
            String faultType,
            String severity,
            String regex,
            String description,
            List<LogDiagnosisVO.Suggestion> suggestions
    ) {}
}
