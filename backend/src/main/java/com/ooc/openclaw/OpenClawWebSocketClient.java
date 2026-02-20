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
import java.util.concurrent.atomic.AtomicBoolean;
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

    // 请求锁: sessionId -> AtomicBoolean，防止同一个session并发请求覆盖handler
    private final Map<String, AtomicBoolean> requestLocks = new ConcurrentHashMap<>();

    // 请求超时管理: sessionId -> ScheduledFuture
    private final Map<String, ScheduledFuture<?>> requestTimeouts = new ConcurrentHashMap<>();

    // 超时调度器
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(1);

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
        // 获取或创建该session的请求锁
        AtomicBoolean lock = requestLocks.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));

        // 尝试获取锁，如果该session已有请求在处理，返回错误
        if (!lock.compareAndSet(false, true)) {
            log.warn("[OpenClaw WS] Session {} already has a request in progress, rejecting new request", sessionId);
            handler.onError("SESSION_BUSY: Session is already processing a request. Please wait for it to complete.");
            return;
        }
        log.info("[OpenClaw WS] Acquired lock for session {}, proceeding with request", sessionId);

        try {
            // 检查或创建连接
            WebSocketSession session = getOrCreateSession(sessionId);
            if (session == null) {
                lock.set(false); // 释放锁
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

                // 启动超时定时器（仅当超时时间 > 0 时）
                int timeoutSeconds = properties.getRequestTimeoutSeconds();
                if (timeoutSeconds > 0) {
                    int timeoutMs = timeoutSeconds * 1000;
                    ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
                        log.warn("[OpenClaw WS] Request timeout for session {}, forcing lock release", sessionId);
                        ResponseHandler timeoutHandler = responseHandlers.remove(sessionId);
                        if (timeoutHandler != null) {
                            try {
                                timeoutHandler.onError("REQUEST_TIMEOUT: Request timed out after " + timeoutSeconds + "s");
                            } finally {
                                AtomicBoolean timeoutLock = requestLocks.get(sessionId);
                                if (timeoutLock != null) {
                                    timeoutLock.set(false);
                                    log.info("[OpenClaw WS] Lock released for session {} after timeout", sessionId);
                                }
                            }
                        }
                        requestTimeouts.remove(sessionId);
                    }, timeoutMs, TimeUnit.MILLISECONDS);
                    requestTimeouts.put(sessionId, timeoutTask);
                } else {
                    log.info("[OpenClaw WS] No timeout set for session {} (timeoutSeconds=0)", sessionId);
                }

            } catch (IOException e) {
                log.error("[OpenClaw WS] Failed to send message", e);
                responseHandlers.remove(sessionId);
                lock.set(false); // 释放锁
                handler.onError("Failed to send message: " + e.getMessage());
            }
        } catch (Exception e) {
            lock.set(false); // 确保异常时释放锁
            throw e;
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

        // 从 contentBlocks 中提取文本和图片
        StringBuilder messageBuilder = new StringBuilder();
        List<Map<String, Object>> attachments = new ArrayList<>();
        
        if (contentBlocks != null && !contentBlocks.isEmpty()) {
            for (Map<String, Object> block : contentBlocks) {
                String blockType = (String) block.get("type");
                if ("text".equals(blockType)) {
                    String text = (String) block.get("text");
                    if (text != null) {
                        messageBuilder.append(text).append("\n\n");
                    }
                } else if ("image_url".equals(blockType)) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> imageUrl = (Map<String, String>) block.get("image_url");
                    if (imageUrl != null) {
                        String url = imageUrl.get("url");
                        if (url != null && url.startsWith("data:")) {
                            // 解析 data URL: data:image/png;base64,xxx
                            int commaIndex = url.indexOf(',');
                            if (commaIndex > 0) {
                                String header = url.substring(5, commaIndex); // 去掉 "data:"
                                String base64Content = url.substring(commaIndex + 1);
                                
                                // 解析 MIME type
                                String mimeType = "image/png";
                                if (header.contains("image/jpeg")) {
                                    mimeType = "image/jpeg";
                                } else if (header.contains("image/gif")) {
                                    mimeType = "image/gif";
                                } else if (header.contains("image/webp")) {
                                    mimeType = "image/webp";
                                }
                                
                                Map<String, Object> attachment = new HashMap<>();
                                attachment.put("type", "image");
                                attachment.put("mimeType", mimeType);
                                attachment.put("fileName", "image." + mimeType.split("/")[1]);
                                attachment.put("content", base64Content);
                                attachments.add(attachment);
                                
                                log.info("[OpenClaw WS] Added image attachment: mimeType={}, contentLength={}", 
                                        mimeType, base64Content.length());
                            }
                        }
                    }
                }
            }
        }
        
        // 如果没有内容，使用原始 message
        String finalMessage = messageBuilder.length() > 0 ? 
                messageBuilder.toString().trim() : 
                (message != null ? message : "");
        
        params.put("message", finalMessage);
        
        // 添加附件（如果有）
        if (!attachments.isEmpty()) {
            params.put("attachments", attachments);
            log.info("[OpenClaw WS] Sending message with {} attachments, message length: {}", 
                    attachments.size(), finalMessage.length());
        } else {
            log.info("[OpenClaw WS] Sending message (no attachments), length: {}", finalMessage.length());
        }

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
        // 跟踪已发送 start 事件的工具调用（OpenClaw 有时会跳过 start 直接发 update/result）
        private final Set<String> startedToolCalls = ConcurrentHashMap.newKeySet();

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
                    String runId = payload.path("runId").asText();
                    log.info("[OpenClaw WS] Agent run completed: {}", runId);

                    // 取消超时定时器
                    ScheduledFuture<?> completedTimeout = requestTimeouts.remove(sessionId);
                    if (completedTimeout != null) {
                        completedTimeout.cancel(false);
                    }

                    ResponseHandler completedHandler = responseHandlers.remove(sessionId);
                    if (completedHandler != null) {
                        try {
                            completedHandler.onComplete();
                        } finally {
                            // 释放请求锁
                            AtomicBoolean lock = requestLocks.get(sessionId);
                            if (lock != null) {
                                lock.set(false);
                                log.info("[OpenClaw WS] Lock released for session {} after agent.run.completed (runId={})", sessionId, runId);
                            }
                        }
                    }
                    break;
                case "agent.run.failed":
                    String error = payload.path("error").asText("Unknown error");
                    log.error("[OpenClaw WS] Agent run failed: {}", error);

                    // 取消超时定时器
                    ScheduledFuture<?> failedTimeout = requestTimeouts.remove(sessionId);
                    if (failedTimeout != null) {
                        failedTimeout.cancel(false);
                    }

                    ResponseHandler failedHandler = responseHandlers.remove(sessionId);
                    if (failedHandler != null) {
                        try {
                            failedHandler.onError(error);
                        } finally {
                            // 释放请求锁
                            AtomicBoolean lock = requestLocks.get(sessionId);
                            if (lock != null) {
                                lock.set(false);
                            }
                        }
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
                log.info("[OpenClaw WS] Chat completed event received for session {} (state={})", sessionId, state);
                // 某些情况下 OpenClaw 不发送 agent.run.completed，但 chat 事件显示完成
                // 这里也需要触发完成逻辑
                triggerCompletionIfNotAlreadyDone();
            }

            // 注意：不处理 chat 事件的内容，因为 agent 事件的 assistant 流已经处理了增量内容
            // 同时处理 chat 事件的内容会导致重复（chat 发送累积内容，agent 发送增量内容）
        }

        private void triggerCompletionIfNotAlreadyDone() {
            // 检查是否已经有完成或失败事件被处理
            if (!responseHandlers.containsKey(sessionId)) {
                log.debug("[OpenClaw WS] Handler already removed for session {}, skipping duplicate completion", sessionId);
                return;
            }

            log.info("[OpenClaw WS] Triggering completion for session {} from chat event", sessionId);

            // 取消超时定时器
            ScheduledFuture<?> chatTimeout = requestTimeouts.remove(sessionId);
            if (chatTimeout != null) {
                chatTimeout.cancel(false);
            }

            ResponseHandler handler = responseHandlers.remove(sessionId);
            if (handler != null) {
                try {
                    handler.onComplete();
                } finally {
                    // 释放请求锁
                    AtomicBoolean lock = requestLocks.get(sessionId);
                    if (lock != null) {
                        lock.set(false);
                        log.info("[OpenClaw WS] Lock released for session {} after chat completion", sessionId);
                    }
                }
            }
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
                    // OpenClaw 发送的是 delta（增量）和 text（累积），不是 content
                    String delta = data.path("delta").asText(null);
                    String text = data.path("text").asText(null);
                    
                    log.debug("[OpenClaw WS] Assistant event: delta={}, text={}", 
                            delta != null ? delta.length() : 0,
                            text != null ? text.length() : 0);
                    
                    if (delta != null && !delta.isEmpty()) {
                        handler.onTextChunk(delta);
                    } else if (text != null && !text.isEmpty()) {
                        // 如果没有 delta 但有 text，发送 text（可能是累积文本）
                        log.info("[OpenClaw WS] Using text field instead of delta: {} chars", text.length());
                        handler.onTextChunk(text);
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

            // 如果是 update 或 result 阶段但没有收到 start 事件，先补发 start
            if (!"start".equals(phase) && !startedToolCalls.contains(toolCallId)) {
                log.warn("[OpenClaw WS] Received tool {} without start event for {}, synthesizing start", phase, toolCallId);
                // 解析参数（如果有的话）
                JsonNode argsNode = data.path("args");
                Map<String, Object> args = new HashMap<>();
                if (argsNode.isObject()) {
                    argsNode.fields().forEachRemaining(entry -> {
                        args.put(entry.getKey(), entry.getValue());
                    });
                }
                handler.onToolStart(toolName, toolCallId, args);
                startedToolCalls.add(toolCallId);
            }

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
                    startedToolCalls.add(toolCallId);
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
                    startedToolCalls.remove(toolCallId); // 清理
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
