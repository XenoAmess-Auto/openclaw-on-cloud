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

    // 已处理的事件序列号，用于防止重复处理
    // key: runId, value: 最后处理的 seq 号
    private final Map<String, Integer> processedEvents = new ConcurrentHashMap<>();
    // 事件去重缓存过期时间（毫秒）
    private static final long EVENT_DEDUP_EXPIRY_MS = 300000; // 5分钟

    // 延迟清理的调度器
    private final Map<String, ScheduledFuture<?>> delayedCleanups = new ConcurrentHashMap<>();
    // 延迟清理时间（毫秒）- 给子代理足够的时间发送事件
    private static final long DELAYED_CLEANUP_MS = 15000; // 15秒

    // 每个会话的序列号计数器（当 OpenClaw Gateway 的 seq 缺失时使用）
    // key: sessionId, value: 下一个序列号
    private final Map<String, AtomicInteger> sessionSeqCounters = new ConcurrentHashMap<>();

    /**
     * 响应处理器接口
     */
    public interface ResponseHandler {
        void onTextChunk(String text, int seq);
        void onToolStart(String toolName, String toolCallId, Map<String, Object> args, int seq);
        void onToolUpdate(String toolCallId, Object partialResult, int seq);
        void onToolResult(String toolCallId, Object result, boolean isError, int seq);
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
            log.info("[OpenClaw WS] Registered handler for session: {}, current handlers count: {}", 
                    sessionId, responseHandlers.size());

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
            container.setDefaultMaxBinaryMessageBufferSize(200 * 1024 * 1024);
            container.setDefaultMaxTextMessageBufferSize(200 * 1024 * 1024);

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
        // 使用正确的 session key 格式: agent:{agentId}:{rest}
        // 确保不同房间的 session 被正确隔离
        // sessionId 格式: ooc-{roomId}
        // sessionKey 格式: agent:main:{sessionId}
        params.put("sessionKey", "agent:main:" + sessionId);

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
     * 从 sessionKey 中提取 session ID
     * sessionKey 格式: agent:main:{sessionId}
     */
    private String extractSessionIdFromSessionKey(String sessionKey) {
        if (sessionKey != null && sessionKey.startsWith("agent:main:")) {
            return sessionKey.substring("agent:main:".length());
        }
        return sessionKey;
    }

    /**
     * 从 session ID 中提取 room ID
     * sessionId 格式: ooc-{roomId}
     */
    private String extractRoomIdFromSessionId(String sessionId) {
        if (sessionId != null && sessionId.startsWith("ooc-")) {
            return sessionId.substring(4); // 去掉 "ooc-" 前缀
        }
        return sessionId;
    }

    /**
     * 查找 handler，支持 room-based 匹配
     * 1. 首先尝试精确匹配
     * 2. 如果失败，尝试使用 room-based session ID 匹配
     */
    private ResponseHandler findHandler(String sessionKeyOrId) {
        String sessionId = extractSessionIdFromSessionKey(sessionKeyOrId);

        log.debug("[OpenClaw WS] findHandler: input={}, extracted sessionId={}, registered handlers={}",
                sessionKeyOrId, sessionId, responseHandlers.keySet());

        // 1. 精确匹配
        ResponseHandler handler = responseHandlers.get(sessionId);
        if (handler != null) {
            log.debug("[OpenClaw WS] Found handler by exact match: {}", sessionId);
            return handler;
        }

        // 2. 尝试 room-based 匹配
        String roomId = extractRoomIdFromSessionId(sessionId);
        if (roomId != null) {
            String roomBasedSessionId = "ooc-" + roomId;
            handler = responseHandlers.get(roomBasedSessionId);
            if (handler != null) {
                log.debug("[OpenClaw WS] Found handler using room-based session ID: {} -> {}",
                        sessionId, roomBasedSessionId);
                return handler;
            }
        }

        log.warn("[OpenClaw WS] Handler not found for: {} (extracted: {}), available handlers: {}",
                sessionKeyOrId, sessionId, responseHandlers.keySet());
        return null;
    }
    
    /**
     * 通过 room ID 查找 handler
     * 当 OpenClaw Gateway 返回不同的 session ID 时使用
     */
    private ResponseHandler findHandlerByRoomId(String roomId) {
        if (roomId == null) return null;
        
        String roomBasedSessionId = "ooc-" + roomId;
        ResponseHandler handler = responseHandlers.get(roomBasedSessionId);
        if (handler != null) {
            log.debug("[OpenClaw WS] Found handler by room ID: {}", roomId);
            return handler;
        }
        return null;
    }

    /**
     * 获取下一个序列号（当 OpenClaw Gateway 的 seq 缺失时使用）
     */
    private int getNextSeq(String sessionId) {
        return sessionSeqCounters.computeIfAbsent(sessionId, k -> new AtomicInteger(0))
                .getAndIncrement();
    }

    /**
     * WebSocket 消息处理器
     */
    private class OpenClawGatewayHandler extends TextWebSocketHandler {
        private final String sessionId;
        private boolean connected = false;
        // 跟踪已发送 start 事件的工具调用（OpenClaw 有时会跳过 start 直接发 update/result）
        private final Set<String> startedToolCalls = ConcurrentHashMap.newKeySet();
        // 本地序列号计数器（当 Gateway 的 seq 缺失时使用）
        private final AtomicInteger localSeqCounter = new AtomicInteger(0);

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

            // 从 payload 中提取 sessionKey，用于查找 handler
            String sessionKeyFromPayload = payload.path("sessionKey").asText(null);
            if (sessionKeyFromPayload != null) {
                // 提取 session ID 从 sessionKey (格式: agent:main:{sessionId})
                String extractedSessionId = extractSessionIdFromSessionKey(sessionKeyFromPayload);
                // 如果提取的 session ID 与当前 handler 的 session ID 不同，更新查找
                if (!extractedSessionId.equals(sessionId)) {
                    log.debug("[OpenClaw WS] Session mismatch - handler: {}, payload: {}, extracted: {}",
                            sessionId, sessionKeyFromPayload, extractedSessionId);
                }
            }

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

                    // 从 payload 中提取 sessionKey
                    String completedSessionKey = payload.path("sessionKey").asText(null);
                    String completedSessionId = completedSessionKey != null ?
                            extractSessionIdFromSessionKey(completedSessionKey) : sessionId;

                    // 注意：不要在这里立即清理 handler，因为可能有子代理还在运行
                    // 真正的完成应该在 chat 事件的 state=final 时处理
                    // 这里只记录完成状态，不清理资源
                    log.debug("[OpenClaw WS] Agent run {} completed for session {}, waiting for chat final event", 
                            runId, completedSessionId);
                    break;
                case "agent.run.failed":
                    String error = payload.path("error").asText("Unknown error");
                    log.error("[OpenClaw WS] Agent run failed: {}", error);

                    // 从 payload 中提取 sessionKey
                    String failedSessionKey = payload.path("sessionKey").asText(null);
                    String failedSessionId = failedSessionKey != null ?
                            extractSessionIdFromSessionKey(failedSessionKey) : sessionId;

                    // 取消超时定时器
                    ScheduledFuture<?> failedTimeout = requestTimeouts.remove(failedSessionId);
                    if (failedTimeout != null) {
                        failedTimeout.cancel(false);
                    }

                    // 延迟清理 handler，给子代理足够的时间发送事件（即使失败也可能有子代理输出）
                    if (!delayedCleanups.containsKey(failedSessionId)) {
                        ScheduledFuture<?> cleanupTask = timeoutScheduler.schedule(() -> {
                            ResponseHandler failedHandler = responseHandlers.remove(failedSessionId);
                            if (failedHandler != null) {
                                try {
                                    failedHandler.onError(error);
                                } finally {
                                    AtomicBoolean lock = requestLocks.get(failedSessionId);
                                    if (lock != null) {
                                        lock.set(false);
                                        log.info("[OpenClaw WS] Lock released for session {} after delayed agent.run.failed", failedSessionId);
                                    }
                                }
                            }
                            delayedCleanups.remove(failedSessionId);
                        }, DELAYED_CLEANUP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                        delayedCleanups.put(failedSessionId, cleanupTask);
                        log.info("[OpenClaw WS] Scheduled delayed cleanup for failed session {} (delay={}ms)", failedSessionId, DELAYED_CLEANUP_MS);
                    }
                    break;
                default:
                    log.debug("[OpenClaw WS] Event: {}", event);
            }
        }

        private void handleChatEvent(JsonNode payload) {
            // 从 payload 中提取 sessionKey 并使用 findHandler 查找 handler
            String sessionKeyFromPayload = payload.path("sessionKey").asText(null);
            
            ResponseHandler handler = null;
            if (sessionKeyFromPayload != null) {
                handler = findHandler(sessionKeyFromPayload);
            }
            
            // 如果通过 sessionKey 找不到 handler，尝试使用 WebSocket handler 的 sessionId
            if (handler == null) {
                handler = responseHandlers.get(sessionId);
                if (handler != null) {
                    log.info("[OpenClaw WS] Found handler for chat event using WebSocket sessionId: {}", sessionId);
                }
            }
            
            // 如果还找不到，尝试使用所有已注册的 handler（当只有一个时）
            // 这是因为 OpenClaw 可能返回不同的 session ID
            if (handler == null && responseHandlers.size() == 1) {
                Map.Entry<String, ResponseHandler> entry = responseHandlers.entrySet().iterator().next();
                handler = entry.getValue();
                log.info("[OpenClaw WS] Found handler for chat event using fallback (only one registered): {}", entry.getKey());
            }
            
            // 如果还是找不到，且是 subagent 事件，尝试使用任何已注册的 handler
            if (handler == null && sessionKeyFromPayload != null && 
                    extractSessionIdFromSessionKey(sessionKeyFromPayload).startsWith("subagent:")) {
                if (!responseHandlers.isEmpty()) {
                    Map.Entry<String, ResponseHandler> entry = responseHandlers.entrySet().iterator().next();
                    handler = entry.getValue();
                    log.info("[OpenClaw WS] Found handler for subagent chat event using main session: {}", entry.getKey());
                }
            }

            if (handler == null) {
                log.debug("[OpenClaw WS] No handler found for chat event, sessionKey: {}, wsSessionId: {}, registered handlers: {}", 
                        sessionKeyFromPayload, sessionId, responseHandlers.keySet());
                return;
            }

            // 调试：打印完整的 payload 结构
            log.debug("[OpenClaw WS] Chat event payload: {}", payload);

            // 检查是否是完成状态 (state: "final" 表示完成)
            String state = payload.path("state").asText("");
            boolean isComplete = payload.path("complete").asBoolean(false) || "final".equals(state);
            if (isComplete) {
                String targetSessionId = sessionKeyFromPayload != null ?
                        extractSessionIdFromSessionKey(sessionKeyFromPayload) : sessionId;
                log.info("[OpenClaw WS] Chat completed event received for session {} (state={})", targetSessionId, state);
                // 某些情况下 OpenClaw 不发送 agent.run.completed，但 chat 事件显示完成
                // 这里也需要触发完成逻辑
                triggerCompletionIfNotAlreadyDone(targetSessionId);
            }

            // 注意：不处理 chat 事件的内容，因为 agent 事件的 assistant 流已经处理了增量内容
            // 同时处理 chat 事件的内容会导致重复（chat 发送累积内容，agent 发送增量内容）
        }

        private void triggerCompletionIfNotAlreadyDone() {
            triggerCompletionIfNotAlreadyDone(sessionId);
        }

        private void triggerCompletionIfNotAlreadyDone(String targetSessionId) {
            // 检查是否已经有完成或失败事件被处理
            if (!responseHandlers.containsKey(targetSessionId)) {
                log.debug("[OpenClaw WS] Handler already removed for session {}, skipping duplicate completion", targetSessionId);
                return;
            }

            // 检查是否已经有延迟清理在调度中
            if (delayedCleanups.containsKey(targetSessionId)) {
                log.debug("[OpenClaw WS] Delayed cleanup already scheduled for session {}, skipping", targetSessionId);
                return;
            }

            log.info("[OpenClaw WS] Scheduling delayed completion for session {} (delay={}ms)", targetSessionId, DELAYED_CLEANUP_MS);

            // 取消超时定时器
            ScheduledFuture<?> chatTimeout = requestTimeouts.remove(targetSessionId);
            if (chatTimeout != null) {
                chatTimeout.cancel(false);
            }

            // 延迟清理 handler，给子代理足够的时间发送事件
            ScheduledFuture<?> cleanupTask = timeoutScheduler.schedule(() -> {
                log.info("[OpenClaw WS] Executing delayed cleanup for session {}", targetSessionId);
                
                ResponseHandler handler = responseHandlers.remove(targetSessionId);
                if (handler != null) {
                    try {
                        handler.onComplete();
                    } finally {
                        // 释放请求锁
                        AtomicBoolean lock = requestLocks.get(targetSessionId);
                        if (lock != null) {
                            lock.set(false);
                            log.info("[OpenClaw WS] Lock released for session {} after delayed completion", targetSessionId);
                        }
                    }
                } else {
                    // 即使 handler 为 null，也要释放锁
                    AtomicBoolean lock = requestLocks.get(targetSessionId);
                    if (lock != null) {
                        lock.set(false);
                        log.info("[OpenClaw WS] Lock released for session {} (handler was null)", targetSessionId);
                    }
                }
                delayedCleanups.remove(targetSessionId);
            }, DELAYED_CLEANUP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            
            delayedCleanups.put(targetSessionId, cleanupTask);
        }

        private void handleAgentEvent(JsonNode payload) {
            String stream = payload.path("stream").asText();
            JsonNode data = payload.path("data");

            // 从 payload 中提取 sessionKey 并使用 findHandler 查找 handler
            String sessionKeyFromPayload = payload.path("sessionKey").asText(null);
            
            // 事件去重：基于 runId + seq 防止重复处理
            String runId = payload.path("runId").asText(null);
            int seq = payload.path("seq").asInt(-1);
            if (runId != null && seq >= 0) {
                String eventKey = runId + ":" + seq;
                Integer lastProcessed = processedEvents.get(runId);
                if (lastProcessed != null && seq <= lastProcessed) {
                    log.debug("[OpenClaw WS] Skipping duplicate event: runId={}, seq={} (last processed: {})", 
                            runId, seq, lastProcessed);
                    return;
                }
                processedEvents.put(runId, seq);
                // 清理过期的去重记录（简单实现：当 map 过大时清理）
                if (processedEvents.size() > 1000) {
                    processedEvents.clear();
                }
            }
            
            log.debug("[OpenClaw WS] Agent event received: stream={}, sessionKeyFromPayload={}, runId={}, seq={}",
                    stream, sessionKeyFromPayload, runId, seq);

            // 如果 seq 缺失或为负数，使用本地递增计数器
            if (seq < 0) {
                seq = localSeqCounter.getAndIncrement();
                log.debug("[OpenClaw WS] Using local seq: {} (Gateway seq was missing)", seq);
            }

            ResponseHandler handler = null;
            String targetSessionId = null;
            
            if (sessionKeyFromPayload != null) {
                handler = findHandler(sessionKeyFromPayload);
                targetSessionId = extractSessionIdFromSessionKey(sessionKeyFromPayload);
            }
            
            // 如果通过 sessionKey 找不到 handler，尝试使用 WebSocket handler 的 sessionId
            if (handler == null) {
                handler = responseHandlers.get(sessionId);
                if (handler != null) {
                    targetSessionId = sessionId;
                    log.info("[OpenClaw WS] Found handler using WebSocket sessionId: {}", sessionId);
                }
            }
            
            // 如果还是找不到，且 targetSessionId 是 subagent 格式，尝试从 sessionKey 中提取 room ID
            // 然后找到对应房间的 handler
            if (handler == null && targetSessionId != null && targetSessionId.startsWith("subagent:")) {
                // 从 WebSocket handler 的 sessionId 中提取 room ID（格式: ooc-{roomId}）
                String webSocketRoomId = extractRoomIdFromSessionId(sessionId);
                if (webSocketRoomId != null) {
                    String roomBasedSessionId = "ooc-" + webSocketRoomId;
                    handler = responseHandlers.get(roomBasedSessionId);
                    if (handler != null) {
                        log.info("[OpenClaw WS] Found handler for subagent event using room-based session: room={}, subagent={}", 
                                webSocketRoomId, targetSessionId);
                    }
                }
                
                // 如果还找不到，尝试使用任何已注册的 handler（只有当没有其他选择时）
                if (handler == null && !responseHandlers.isEmpty()) {
                    // 只有当 handlers 数量很少时才使用 fallback（避免串房间）
                    if (responseHandlers.size() <= 2) {
                        Map.Entry<String, ResponseHandler> entry = responseHandlers.entrySet().iterator().next();
                        handler = entry.getValue();
                        log.info("[OpenClaw WS] Found handler for subagent using fallback: main={}, subagent={}", 
                                entry.getKey(), targetSessionId);
                    } else {
                        log.warn("[OpenClaw WS] Multiple handlers registered ({}), cannot safely route subagent event. " +
                                "This may indicate a cross-room issue.", responseHandlers.size());
                    }
                }
            }
            
            if (targetSessionId == null) {
                targetSessionId = sessionKeyFromPayload != null ? 
                        extractSessionIdFromSessionKey(sessionKeyFromPayload) : sessionId;
            }

            log.info("[OpenClaw WS] Agent event: stream={}, hasHandler={}, targetSession={}, sessionKeyFromPayload={}, wsSessionId={}",
                    stream, handler != null, targetSessionId, sessionKeyFromPayload, sessionId);

            if (handler == null) {
                log.warn("[OpenClaw WS] No handler for session {}, dropping agent event (stream={}). Registered handlers: {}", 
                        targetSessionId, stream, responseHandlers.keySet());
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

                    // 优先使用 delta（增量内容），忽略 text（累积内容）以避免重复
                    // delta 和 text 不应该同时使用，因为它们代表相同的内容
                    // 注意：runId + seq 的去重逻辑已经在前面处理过了
                    if (delta != null && !delta.isEmpty()) {
                        handler.onTextChunk(delta, seq);
                        log.debug("[OpenClaw WS] Sent delta: {} chars, seq={}", delta.length(), seq);
                    }
                    // 注意：不再使用 text 字段，因为它包含的是累积内容
                    // 使用 text 会导致内容被重复追加（delta 追加了增量，text 又追加了累积）
                    break;

                case "tool":
                    handleToolEvent(data, handler, seq);
                    break;

                case "lifecycle":
                    // 生命周期事件，忽略
                    break;

                default:
                    log.debug("[OpenClaw WS] Unknown stream type: {}", stream);
            }
        }

        private void handleToolEvent(JsonNode data, ResponseHandler handler, int seq) {
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
                handler.onToolStart(toolName, toolCallId, args, seq);
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
                    log.info("[OpenClaw WS] Tool start: {} ({}) seq={}", toolName, toolCallId, seq);
                    handler.onToolStart(toolName, toolCallId, args, seq);
                    startedToolCalls.add(toolCallId);
                    break;

                case "update":
                    Object partialResult = data.path("partialResult");
                    handler.onToolUpdate(toolCallId, partialResult, seq);
                    break;

                case "result":
                    Object result = data.path("result");
                    boolean isError = data.path("isError").asBoolean(false);
                    log.info("[OpenClaw WS] Tool result: {} (error={}) seq={}", toolName, isError, seq);
                    handler.onToolResult(toolCallId, result, isError, seq);
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

            // 取消超时定时器
            ScheduledFuture<?> closedTimeout = requestTimeouts.remove(sessionId);
            if (closedTimeout != null) {
                closedTimeout.cancel(false);
            }

            // 清理序列号计数器
            sessionSeqCounters.remove(sessionId);

            if (!status.equals(CloseStatus.NORMAL)) {
                ResponseHandler handler = responseHandlers.remove(sessionId);
                if (handler != null) {
                    try {
                        handler.onError("WebSocket connection closed unexpectedly");
                    } finally {
                        // 释放请求锁
                        AtomicBoolean lock = requestLocks.get(sessionId);
                        if (lock != null) {
                            lock.set(false);
                            log.info("[OpenClaw WS] Lock released for session {} after connection closed", sessionId);
                        }
                    }
                }
            } else {
                // 正常关闭也需要释放锁
                AtomicBoolean lock = requestLocks.get(sessionId);
                if (lock != null) {
                    lock.set(false);
                    log.info("[OpenClaw WS] Lock released for session {} after normal close", sessionId);
                }
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("[OpenClaw WS] Transport error: {}", sessionId, exception);

            // 取消超时定时器
            ScheduledFuture<?> errorTimeout = requestTimeouts.remove(sessionId);
            if (errorTimeout != null) {
                errorTimeout.cancel(false);
            }

            ResponseHandler handler = responseHandlers.remove(sessionId);
            if (handler != null) {
                try {
                    handler.onError("WebSocket transport error: " + exception.getMessage());
                } finally {
                    // 释放请求锁
                    AtomicBoolean lock = requestLocks.get(sessionId);
                    if (lock != null) {
                        lock.set(false);
                        log.info("[OpenClaw WS] Lock released for session {} after transport error", sessionId);
                    }
                }
            }
        }
    }
}
