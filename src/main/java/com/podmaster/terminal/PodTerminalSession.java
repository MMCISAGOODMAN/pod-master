package com.podmaster.terminal;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pod 交互式终端会话：K8s exec TTY 与 WebSocket 双向桥接
 */
@Slf4j
public class PodTerminalSession implements AutoCloseable {

    private static final int DEFAULT_COLS = 120;
    private static final int DEFAULT_ROWS = 30;

    private final ExecWatch watch;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    public PodTerminalSession(KubernetesClient client, String namespace, String name, String container) {
        Pod pod = client.pods().inNamespace(namespace).withName(name).get();
        if (pod == null) {
            throw new IllegalArgumentException("Pod not found: " + namespace + "/" + name);
        }
        if (!"Running".equals(pod.getStatus().getPhase())) {
            throw new IllegalStateException("Pod 未 Running，当前状态: " + pod.getStatus().getPhase());
        }

        String containerName = resolveContainer(pod, container);
        ensureContainerReady(pod, containerName);

        watch = openExec(client, namespace, name, containerName);
        watch.resize(DEFAULT_COLS, DEFAULT_ROWS);
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "pod-terminal-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    private static String resolveContainer(Pod pod, String container) {
        if (container != null && !container.isBlank()) {
            return container;
        }
        return pod.getSpec().getContainers().get(0).getName();
    }

    private static void ensureContainerReady(Pod pod, String containerName) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return;
        }
        for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
            if (containerName.equals(cs.getName()) && Boolean.FALSE.equals(cs.getReady())) {
                log.warn("Container {} not ready, exec may fail", containerName);
                return;
            }
        }
    }

    /**
     * TTY 模式下 K8s 将 stderr 合并到 stdout，不可同时 redirectingError + withTTY，否则 API 返回 400。
     */
    private static ExecWatch openExec(KubernetesClient client, String namespace, String name, String container) {
        try {
            return exec(client, namespace, name, container, "/bin/sh", "-i");
        } catch (Exception e1) {
            log.debug("exec /bin/sh -i failed, fallback to sh -i: {}", e1.getMessage());
            try {
                return exec(client, namespace, name, container, "sh", "-i");
            } catch (Exception e2) {
                log.debug("exec sh -i failed, fallback to /bin/sh: {}", e2.getMessage());
                return exec(client, namespace, name, container, "/bin/sh");
            }
        }
    }

    private static ExecWatch exec(KubernetesClient client, String namespace, String name,
                                    String container, String... command) {
        return client.pods().inNamespace(namespace).withName(name)
                .inContainer(container)
                .redirectingInput()
                .redirectingOutput()
                .withTTY()
                .exec(command);
    }

    public void attach(WebSocketSession session) {
        executor.submit(() -> pump(session, watch.getOutput()));
    }

    private void pump(WebSocketSession session, InputStream stream) {
        byte[] buf = new byte[4096];
        try {
            int n;
            while (!closed.get() && (n = stream.read(buf)) >= 0) {
                if (n == 0) {
                    continue;
                }
                send(session, new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            if (!closed.get()) {
                log.debug("Terminal stream closed: {}", e.getMessage());
            }
        }
    }

    public void writeInput(String data) throws IOException {
        if (closed.get()) {
            return;
        }
        synchronized (writeLock) {
            watch.getInput().write(data.getBytes(StandardCharsets.UTF_8));
            watch.getInput().flush();
        }
    }

    public void resize(int cols, int rows) {
        if (closed.get() || cols <= 0 || rows <= 0) {
            return;
        }
        try {
            watch.resize(cols, rows);
        } catch (Exception e) {
            log.debug("Terminal resize ignored: {}", e.getMessage());
        }
    }

    private void send(WebSocketSession session, String text) {
        if (closed.get() || !session.isOpen()) {
            return;
        }
        synchronized (writeLock) {
            try {
                session.sendMessage(new TextMessage(text));
            } catch (IOException e) {
                log.debug("WebSocket send failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            watch.close();
        } catch (Exception ignored) {
        }
        executor.shutdownNow();
    }
}
