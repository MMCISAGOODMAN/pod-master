package com.podmaster.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.podmaster.util.K8sClientUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pod 交互式终端 WebSocket 处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PodTerminalHandler extends TextWebSocketHandler {

    private final K8sClientUtil k8sClientUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, PodTerminalSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        String namespace = params.getFirst("namespace");
        String name = params.getFirst("name");
        String container = params.getFirst("container");

        if (namespace == null || name == null) {
            session.sendMessage(new TextMessage("\r\n\u001b[31m缺少 namespace 或 name 参数\u001b[0m\r\n"));
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        try {
            k8sClientUtil.ensureConnected();
            PodTerminalSession terminal = new PodTerminalSession(
                    k8sClientUtil.getClient(), namespace, name, container);
            terminal.attach(session);
            sessions.put(session.getId(), terminal);
            log.info("Terminal connected: {}/{}", namespace, name);
        } catch (Exception e) {
            log.warn("Terminal connect failed: {}/{} - {}", namespace, name, rootMessage(e));
            session.sendMessage(new TextMessage("\r\n\u001b[31m终端连接失败: " + rootMessage(e) + "\u001b[0m\r\n"));
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null && !msg.isBlank() ? msg : cur.getClass().getSimpleName();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        PodTerminalSession terminal = sessions.get(session.getId());
        if (terminal == null) {
            return;
        }
        String payload = message.getPayload();
        if (payload.startsWith("{")) {
            JsonNode node = objectMapper.readTree(payload);
            String type = node.path("type").asText("stdin");
            if ("resize".equals(type)) {
                terminal.resize(node.path("cols").asInt(80), node.path("rows").asInt(24));
                return;
            }
            if (node.has("data")) {
                terminal.writeInput(node.get("data").asText());
                return;
            }
        }
        terminal.writeInput(payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PodTerminalSession terminal = sessions.remove(session.getId());
        if (terminal != null) {
            terminal.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Terminal transport error: {}", exception.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }
}
