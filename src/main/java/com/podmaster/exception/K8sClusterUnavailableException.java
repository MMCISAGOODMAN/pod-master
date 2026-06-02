package com.podmaster.exception;

/**
 * K8s 集群不可用时抛出
 */
public class K8sClusterUnavailableException extends RuntimeException {

    public K8sClusterUnavailableException(String message) {
        super(message);
    }

    public K8sClusterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
