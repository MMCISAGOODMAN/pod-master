package com.podmaster.util;

import com.podmaster.config.K8sProperties;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabric8 K8s 客户端工具类，支持 kubeconfig 与集群内 ServiceAccount 两种连接方式
 */
@Slf4j
@Component
public class K8sClientUtil {

    private final KubernetesClient client;
    private final K8sProperties properties;

    public K8sClientUtil(K8sProperties properties) {
        this.properties = properties;
        this.client = createClient();
        log.info("K8s client initialized, mode={}, master={}",
                properties.getConnectionMode(), safeMasterUrl());
    }

    private String safeMasterUrl() {
        try {
            return String.valueOf(client.getMasterUrl());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private KubernetesClient createClient() {
        try {
            String mode = properties.getConnectionMode();
            if ("in-cluster".equalsIgnoreCase(mode)) {
                return new KubernetesClientBuilder().build();
            }
            String kubeconfigPath = properties.getKubeconfigPath();
            if (kubeconfigPath != null && Files.exists(Path.of(kubeconfigPath))) {
                String content = Files.readString(Path.of(kubeconfigPath));
                Config config = Config.fromKubeconfig(content);
                return new KubernetesClientBuilder().withConfig(config).build();
            }
            log.warn("kubeconfig not found at {}, fallback to default config", kubeconfigPath);
            return new KubernetesClientBuilder().build();
        } catch (Exception e) {
            log.warn("K8s client init failed, using default client: {}", e.getMessage());
            return new KubernetesClientBuilder().build();
        }
    }

    public KubernetesClient getClient() {
        return client;
    }

    /** 执行 K8s 操作前检查集群连通性 */
    public void ensureConnected() {
        if (!isConnected()) {
            throw new com.podmaster.exception.K8sClusterUnavailableException(
                    "K8s 集群未连接。请启动 Docker Desktop 并启用 Kubernetes（Settings → Kubernetes → Enable）");
        }
    }

    public boolean isConnected() {
        try {
            client.namespaces().list();
            return true;
        } catch (Exception e) {
            log.debug("K8s cluster connection check failed: {}", e.getMessage());
            return false;
        }
    }

    public String getConnectionInfo() {
        try {
            var nodes = client.nodes().list().getItems();
            String nodeCount = String.valueOf(nodes.size());
            var version = client.getKubernetesVersion();
            return String.format("connected, k8s version=%s, nodes=%s, master=%s",
                    version.getGitVersion(), nodeCount, client.getMasterUrl());
        } catch (Exception e) {
            try {
                int nsCount = client.namespaces().list().getItems().size();
                return String.format("connected (limited), namespaces=%d, master=%s",
                        nsCount, client.getMasterUrl());
            } catch (Exception ex) {
                return "disconnected: " + e.getMessage();
            }
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
