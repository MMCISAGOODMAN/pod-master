package com.podmaster.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * K8s 连接与成本配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "k8s")
public class K8sProperties {

    /** 连接模式：local 或 in-cluster */
    private String connectionMode = "local";

    /** 本地 kubeconfig 路径 */
    private String kubeconfigPath;

    /** 默认命名空间 */
    private String defaultNamespace = "default";

    private Cost cost = new Cost();

    @Data
    public static class Cost {
        private double cpuPerCoreDay = 2.5;
        private double memoryPerGbDay = 0.8;
    }
}
