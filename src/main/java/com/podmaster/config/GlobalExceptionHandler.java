package com.podmaster.config;

import com.podmaster.common.Result;
import com.podmaster.exception.K8sClusterUnavailableException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String K8S_UNAVAILABLE_MSG =
            "K8s 集群未连接。请启动 Docker Desktop 并启用 Kubernetes（Settings → Kubernetes → Enable）";

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.fail(400, e.getMessage());
    }

    @ExceptionHandler(K8sClusterUnavailableException.class)
    public Result<Void> handleK8sUnavailable(K8sClusterUnavailableException e) {
        log.warn("K8s unavailable: {}", e.getMessage());
        return Result.fail(503, e.getMessage());
    }

    @ExceptionHandler(KubernetesClientException.class)
    public Result<Void> handleKubernetesClient(KubernetesClientException e) {
        log.warn("K8s API error: {}", rootMessage(e));
        return Result.fail(503, K8S_UNAVAILABLE_MSG);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        if (isConnectionRefused(e)) {
            log.warn("K8s connection refused: {}", e.getMessage());
            return Result.fail(503, K8S_UNAVAILABLE_MSG);
        }
        log.error("Unhandled exception", e);
        return Result.fail("Internal error: " + e.getMessage());
    }

    private boolean isConnectionRefused(Throwable e) {
        while (e != null) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Connection refused") || msg.contains("Failed to connect"))) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : e.getMessage();
    }
}
