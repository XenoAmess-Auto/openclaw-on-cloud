package com.ooc.openclaw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenClaw Gateway WebSocket 客户端
 * 用于建立与 OpenClaw Gateway 的 WebSocket 连接，接收原生工具事件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenClawWebSocketClient {

    private final OpenClawProperties properties;
    private final ObjectMapper objectMapper;

    // 消息ID生成器
    private final AtomicInteger messageIdGenerator = new AtomicInteger(0);

    // 会话管理: sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // 响应处理器: sessionId -> ResponseHandler
    private final Map<String, ResponseHandler> responseHandlers = new ConcurrentHashMap<>();

    // 连接配置
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    /**
     * 响应处理器接口
     */
    public interface ResponseHandler {
        void onTextChunk(String text);
        void onToolStart(String toolName, String toolCallId, Map<String, Object> args);
        void onToolUpdate(String toolCallId, Object partialResult);
        void onToolResult(String toolCallId, Object result, boolean isError);
        void onComplete();
        void onError(String error);
    }

    /**
     * 发送消息到 OpenClaw Gateway
     */
    public void sendMessage(String sessionId, String message, List<Map<String, Object>> contentBlocks,
                           ResponseHandler handler) {
        // 检查或创建连接
        WebSocketSession session = getOrCreateSession(sessionId);
        if (session == null) {
            handler.onError("Failed to establish WebSocket connection to OpenClaw Gateway");
            return;
        }

        // 注册响应处理器
        responseHandlers.put(sessionId, handler);

        // 构建并发送 chat.send 请求
        try {
            String request = buildChatSendRequest(sessionId, message, contentBlocks);
            log.info("[OpenClaw WS] Sending chat.send: sessionId={}, messageLength={}",
                    sessionId, message != null ? message.length() : 0);
            session.sendMessage(new TextMessage(request));
        } catch (IOException e) {
            log.error("[OpenClaw WS] Failed to send message", e);
            handler.onError("Failed to send message: " + e.getMessage());
        }
    }

    /**
     * 获取或创建 WebSocket 会话
     */
    private WebSocketSession getOrCreateSession(String sessionId) {
        WebSocketSession existing = sessions.get(sessionId);
        if (existing != null && existing.isOpen()) {
            return existing;
        }

        return createNewSession(sessionId);
    }

    /**
     * 创建新的 WebSocket 连接
     */
    private WebSocketSession createNewSession(String sessionId) {
        try {
            // 配置 WebSocket 容器以支持大消息 (16MB 缓冲区)
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxBinaryMessageBufferSize(16 * 1024 * 1024);
            container.setDefaultMaxTextMessageBufferSize(16 * 1024 * 1024);

            StandardWebSocketClient client = new StandardWebSocketClient(container);

            WebSocketHandler handler = new OpenClawGatewayHandler(sessionId);

            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add("Origin", properties.getGatewayUrl().replace("ws://", "http://").replace("wss://", "https://"));

            // 构建 WebSocket URL
            String wsUrl = properties.getGatewayUrl();
            if (wsUrl.startsWith("http://")) {
                wsUrl = wsUrl.replace("http://", "ws://");
            } else if (wsUrl.startsWith("https://")) {
                wsUrl = wsUrl.replace("https://", "wss://");
            }

            // 配置任务执行器
            client.setTaskExecutor(new org.springframework.core.task.SimpleAsyncTaskExecutor("openclaw-ws-"));

            log.info("[OpenClaw WS] Connecting to {}...", wsUrl);

            ListenableFuture<WebSocketSession> future = client.doHandshake(handler, headers,
                    new org.springframework.web.util.UriTemplate(wsUrl).expand());

            WebSocketSession session = future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // 发送 connect handshake
            sendConnectHandshake(session, sessionId);

            sessions.put(sessionId, session);
            log.info("[OpenClaw WS] Session created: {}", sessionId);

            return session;

        } catch (Exception e) {
            log.error("[OpenClaw WS] Failed to create session: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 发送连接握手
     */
    private void sendConnectHandshake(WebSocketSession session, String sessionId) throws IOException {
        Map<String, Object> connectReq = new HashMap<>();
        connectReq.put("type", "req");
        connectReq.put("id", "conn-" + messageIdGenerator.incrementAndGet());
        connectReq.put("method", "connect");

        Map<String, Object> params = new HashMap<>();
        params.put("minProtocol", 3);
        params.put("maxProtocol", 3);

        Map<String, Object> clientInfo = new HashMap<>();
        clientInfo.put("id", "webchat");  // OpenClaw 要求必须是 "webchat"
        clientInfo.put("version", "1.0.0");
        clientInfo.put("platform", "java");
        clientInfo.put("mode", "webchat");
        params.put("client", clientInfo);

        params.put("role", "operator");
        params.put("scopes", Arrays.asList("operator.read", "operator.write", "operator.admin"));
        params.put("caps", Arrays.asList("tool-events")); // ⭐ 关键：启用工具事件
        params.put("auth", Map.of("token", properties.getApiKey()));
        params.put("locale", "zh-CN");
        params.put("userAgent", "ooc-backend/1.0.0");

        connectReq.put("params", params);

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(connectReq)));
        log.info("[OpenClaw WS] Connect handshake sent for session: {}", sessionId);
    }

    /**
     * 构建 chat.send 请求
     */
    private String buildChatSendRequest(String sessionId, String message,
                                       List<Map<String, Object>> contentBlocks) throws JsonProcessingException {
        Map<String, Object> request = new HashMap<>();
        request.put("type", "req");
        request.put("id", "msg-" + messageIdGenerator.incrementAndGet());
        request.put("method", "chat.send");

        Map<String, Object> params = new HashMap<>();
        params.put("sessionKey", "ooc-" + sessionId);
        params.put("message", message);
        params.put("deliver", false);
        params.put("idempotencyKey", UUID.randomUUID().toString());

        request.put("params", params);

        return objectMapper.writeValueAsString(request);
    }

    /**
     * 关闭会话
     */
    public void closeSession(String sessionId) {
        WebSocketSession session = sessions.remove(sessionId);
        if (session != null) {
            try {
                session.close();
                log.info("[OpenClaw WS] Session closed: {}", sessionId);
            } catch (IOException e) {
                log.warn("[OpenClaw WS] Error closing session: {}", sessionId, e);
            }
        }
        responseHandlers.remove(sessionId);
    }

    /**
     * WebSocket 消息处理器
     */
    private class OpenClawGatewayHandler extends TextWebSocketHandler {
        private final String sessionId;
        private boolean connected = false;

        public OpenClawGatewayHandler(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            log.info("[OpenClaw WS] Connection established: {}", sessionId);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            try {
                String payload = message.getPayload();
                log.debug("[OpenClaw WS] Raw message: {}", payload.substring(0, Math.min(200, payload.length())));
                
                JsonNode msg = objectMapper.readTree(payload);
                String type = msg.path("type").asText("unknown");

                switch (type) {
                    case "res":
                        handleResponse(msg);
                        break;
                    case "event":
                        handleEvent(msg);
                        break;
                    default:
                        log.debug("[OpenClaw WS] Unknown message type: {}", type);
                }
            } catch (Exception e) {
                log.error("[OpenClaw WS] Error handling message", e);
            }
        }

        private void handleResponse(JsonNode msg) {
            String id = msg.path("id").asText();
            boolean ok = msg.path("ok").asBoolean(false);

            if (!ok) {
                String error = msg.path("error").asText("Unknown error");
                log.error("[OpenClaw WS] Error response: {}", error);
                ResponseHandler handler = responseHandlers.get(sessionId);
                if (handler != null) {
                    handler.onError(error);
                }
                return;
            }

            JsonNode payload = msg.path("payload");
            if ("hello-ok".equals(payload.path("type").asText())) {
                connected = true;
                log.info("[OpenClaw WS] Connected successfully: protocol={}",
                        payload.path("protocol").asInt());
            }
        }

        private void handleEvent(JsonNode msg) {
            String event = msg.path("event").asText();
            JsonNode payload = msg.path("payload");

            switch (event) {
                case "agent":
                    handleAgentEvent(payload);
                    break;
                case "chat":
                    handleChatEvent(payload);
                    break;
                case "agent.run.started":
                    log.debug("[OpenClaw WS] Agent run started: {}", payload.path("runId").asText());
                    break;
                case "agent.run.completed":
                    log.debug("[OpenClaw WS] Agent run completed: {}", payload.path("runId").asText());
                    ResponseHandler handler = responseHandlers.get(sessionId);
                    if (handler != null) {
                        handler.onComplete();
                    }
                    break;
                case "agent.run.failed":
                    String error = payload.path("error").asText("Unknown error");
                    log.error("[OpenClaw WS] Agent run failed: {}", error);
                    ResponseHandler h = responseHandlers.get(sessionId);
                    if (h != null) {
                        h.onError(error);
                    }
                    break;
                default:
                    log.debug("[OpenClaw WS] Event: {}", event);
            }
        }

        private void handleChatEvent(JsonNode payload) {
            ResponseHandler handler = responseHandlers.get(sessionId);
            if (handler == null) {
                return;
            }

            // 调试：打印完整的 payload 结构
            log.debug("[OpenClaw WS] Chat event payload: {}", payload);

            // 检查是否是完成状态 (state: "final" 表示完成)
            String state = payload.path("state").asText("");
            boolean isComplete = payload.path("complete").asBoolean(false) || "final".equals(state);
            if (isComplete) {
                log.debug("[OpenClaw WS] Chat completed, calling onComplete()");
                handler.onComplete();
            }
            
            // 注意：不处理 chat 事件的内容，因为 agent 事件的 assistant 流已经处理了增量内容
            // 同时处理 chat 事件的内容会导致重复（chat 发送累积内容，agent 发送增量内容）
        }

        private void handleAgentEvent(JsonNode payload) {
            String stream = payload.path("stream").asText();
            JsonNode data = payload.path("data");

            ResponseHandler handler = responseHandlers.get(sessionId);
            if (handler == null) {
                return;
            }

            switch (stream) {
                case "assistant":
                    String content = data.path("content").asText(null);
                    if (content != null && !content.isEmpty()) {
                        handler.onTextChunk(content);
                    }
                    break;

                case "tool":
                    handleToolEvent(data, handler);
                    break;

                case "lifecycle":
                    // 生命周期事件，忽略
                    break;

                default:
                    log.debug("[OpenClaw WS] Unknown stream type: {}", stream);
            }
        }

        private void handleToolEvent(JsonNode data, ResponseHandler handler) {
            String phase = data.path("phase").asText();
            String toolName = data.path("name").asText();
            String toolCallId = data.path("toolCallId").asText();

            switch (phase) {
                case "start":
                    // 解析参数
                    JsonNode argsNode = data.path("args");
                    Map<String, Object> args = new HashMap<>();
                    if (argsNode.isObject()) {
                        argsNode.fields().forEachRemaining(entry -> {
                            args.put(entry.getKey(), entry.getValue());
                        });
                    }
                    log.info("[OpenClaw WS] Tool start: {} ({})", toolName, toolCallId);
                    handler.onToolStart(toolName, toolCallId, args);
                    break;

                case "update":
                    Object partialResult = data.path("partialResult");
                    handler.onToolUpdate(toolCallId, partialResult);
                    break;

                case "result":
                    Object result = data.path("result");
                    boolean isError = data.path("isError").asBoolean(false);
                    log.info("[OpenClaw WS] Tool result: {} (error={})", toolName, isError);
                    handler.onToolResult(toolCallId, result, isError);
                    break;

                default:
                    log.warn("[OpenClaw WS] Unknown tool phase: {}", phase);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            log.info("[OpenClaw WS] Connection closed: {} (status={})", sessionId, status);
            sessions.remove(sessionId);

            if (!status.equals(CloseStatus.NORMAL)) {
                ResponseHandler handler = responseHandlers.get(sessionId);
                if (handler != null) {
                    handler.onError("WebSocket connection closed unexpectedly");
                }
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("[OpenClaw WS] Transport error: {}", sessionId, exception);
            ResponseHandler handler = responseHandlers.get(sessionId);
            if (handler != null) {
                handler.onError("WebSocket transport error: " + exception.getMessage());
            }
        }
    }
}
