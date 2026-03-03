package com.ooc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ooc.entity.ChatRoom;
import com.ooc.entity.OocSession;
import com.ooc.entity.User;
import com.ooc.openclaw.OpenClawPluginService;
import com.ooc.openclaw.OpenClawSessionState;
import com.ooc.service.AvatarCacheService;
import com.ooc.service.ChatRoomService;
import com.ooc.service.ClaudeCodePluginService;
import com.ooc.service.KimiPluginService;
import com.ooc.service.MentionService;
import com.ooc.service.OocSessionService;
import com.ooc.service.PersistentTaskQueueService;
import com.ooc.service.UserService;
import com.ooc.entity.BotTaskQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatRoomService chatRoomService;
    private final OocSessionService oocSessionService;
    private final OpenClawPluginService openClawPluginService;
    private final KimiPluginService kimiPluginService;
    private final ClaudeCodePluginService claudeCodePluginService;
    private final UserService userService;
    private final AvatarCacheService avatarCacheService;
    private final ObjectMapper objectMapper;
    private final PersistentTaskQueueService taskQueueService;
    private final com.ooc.service.flowchart.FlowchartTaskQueueIntegration flowchartTaskQueueIntegration;
    private final WebSocketBroadcastService broadcastService;

    @Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private MentionService mentionService;

    // session -> userInfo
    private final Map<WebSocketSession, WebSocketUserInfo> userInfoMap = new ConcurrentHashMap<>();

    // userId -> Set<WebSocketSession> (for notification)
    private final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    // ========== 队列系统 ==========
    // 使用 PersistentTaskQueueService 进行持久化队列管理

    /**
     * OpenClaw 任务
     */
    @lombok.Data
    @lombok.Builder
    public static class OpenClawTask {
        private String taskId;
        private String roomId;
        private String content;
        private List<com.ooc.websocket.Attachment> attachments;
        private WebSocketUserInfo userInfo;
        private Instant createdAt;
        private volatile TaskStatus status; // PENDING, PROCESSING, COMPLETED, FAILED
        private String sourceMessageId; // 原始用户消息ID，用于replyTo

        @com.fasterxml.jackson.annotation.JsonProperty("taskType")
        @lombok.Builder.Default
        private TaskType taskType = TaskType.CHAT; // CHAT 或 FLOWCHART

        private String flowchartInstanceId; // 流程图实例ID（当 taskType 为 FLOWCHART 时有效）

        public enum TaskStatus {
            PENDING, PROCESSING, COMPLETED, FAILED
        }

        public enum TaskType {
            CHAT,      // 普通对话任务
            FLOWCHART  // 流程图任务
        }
    }

    /**
     * 服务启动时注册任务处理器
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        // 注册 OpenClaw 任务处理器
        taskQueueService.registerTaskProcessor(BotTaskQueue.BotType.OPENCLAW, this::executeOpenClawTask);
        // 注册 Kimi 任务处理器
        taskQueueService.registerTaskProcessor(BotTaskQueue.BotType.KIMI, this::executeKimiTask);
        // 注册 Claude 任务处理器
        taskQueueService.registerTaskProcessor(BotTaskQueue.BotType.CLAUDE, this::executeClaudeTask);
        log.info("Task processors registered for OPENCLAW, KIMI, and CLAUDE");
    }

    /**
     * 获取指定房间的 OpenClaw 任务队列状态
     */
    public java.util.List<OpenClawTask> getRoomTaskQueue(String roomId) {
        return taskQueueService.getRoomTaskQueue(roomId, BotTaskQueue.BotType.OPENCLAW);
    }

    /**
     * 获取指定房间指定机器人类型的任务队列状态
     */
    public java.util.List<OpenClawTask> getRoomTaskQueue(String roomId, BotTaskQueue.BotType botType) {
        return taskQueueService.getRoomTaskQueue(roomId, botType);
    }

    /**
     * 获取指定房间所有机器人类型的任务队列
     */
    public Map<String, java.util.List<OpenClawTask>> getRoomAllTaskQueues(String roomId) {
        return taskQueueService.getRoomAllTaskQueues(roomId);
    }

    /**
     * 重新排序任务队列
     * @param roomId 房间ID
     * @param taskIds 新的任务ID顺序列表
     * @return 是否成功
     */
    public boolean reorderTaskQueue(String roomId, java.util.List<String> taskIds) {
        return taskQueueService.reorderQueue(roomId, BotTaskQueue.BotType.OPENCLAW, taskIds);
    }

    /**
     * 取消指定任务
     * @param roomId 房间ID
     * @param taskId 任务ID
     * @return 是否成功取消
     */
    public boolean cancelTask(String roomId, String taskId) {
        return taskQueueService.cancelTask(roomId, taskId, BotTaskQueue.BotType.OPENCLAW);
    }

    /**
     * 获取指定房间是否正在处理 OpenClaw 任务
     */
    public boolean isRoomProcessing(String roomId) {
        return taskQueueService.isRoomProcessing(roomId, BotTaskQueue.BotType.OPENCLAW);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket closed: {}", session.getId());
        WebSocketUserInfo userInfo = userInfoMap.remove(session);
        if (userInfo != null) {
            // Remove from userSessions
            Set<WebSocketSession> userSess = userSessions.get(userInfo.getUserId());
            if (userSess != null) {
                userSess.remove(session);
                if (userSess.isEmpty()) {
                    userSessions.remove(userInfo.getUserId());
                }
            }
            // 从广播服务移除（唯一会话管理源）
            broadcastService.removeRoomSession(userInfo.getRoomId(), session);
        }
        // 从广播服务移除会话（所有房间）
        broadcastService.removeSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payloadText = message.getPayload();
        WebSocketMessage payload = objectMapper.readValue(payloadText, WebSocketMessage.class);

        // 调试：记录 message 类型的原始 payload
        if ("message".equals(payload.getType())) {
            log.info("[WebSocket Raw] Received message payload: {}",
                    payloadText.length() > 500 ? payloadText.substring(0, 500) + "..." : payloadText);
            log.info("[WebSocket Parsed] attachments: {}",
                    payload.getAttachments() != null ? payload.getAttachments().size() : "null");
        }

        switch (payload.getType()) {
            case "join" -> handleJoin(session, payload);
            case "message" -> handleMessage(session, payload);
            case "typing" -> handleTyping(session, payload);
            case "leave" -> handleLeave(session, payload);
            case "ping" -> handlePing(session, payload);
        }
    }

    private void handleJoin(WebSocketSession session, WebSocketMessage payload) {
        String roomId = payload.getRoomId();
        String userName = payload.getUserName();

        // 强制从数据库获取用户ID，最多重试3次
        User user = getUserFromDatabaseWithRetry(userName);
        
        if (user == null) {
            // 无法从数据库获取用户信息，拒绝连接
            log.error("Failed to get user {} from database after retries, rejecting connection", userName);
            try {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("User not found in database"));
            } catch (IOException e) {
                log.error("Failed to close session", e);
            }
            return;
        }

        String userId = user.getId();
        String nickname = user.getNickname() != null && !user.getNickname().isEmpty()
                ? user.getNickname() : userName;

        // 始终从数据库获取最新头像，避免缓存中的旧值（包括null）导致头像无法更新
        String avatar = user.getAvatar();
        if (avatar != null && !avatar.isEmpty()) {
            // 将最新头像存入缓存（覆盖可能存在的旧值）
            avatarCacheService.putAvatarInCache(userId, avatar);
        } else {
            // 如果数据库中没有头像，从缓存尝试获取（可能之前有上传过）
            avatar = avatarCacheService.getAvatarFromCache(userId);
        }
        log.info("User {} joined, userId: {}, avatar: {}, fromCache: {}",
                userName, userId, avatar != null ? "(present)" : "(null)",
                avatarCacheService.isAvatarCached(userId));

        WebSocketUserInfo userInfo = WebSocketUserInfo.builder()
                .userId(userId)
                .userName(nickname)
                .roomId(roomId)
                .avatar(avatar)
                .build();

        // 检查该 session 是否已经在其他房间，如果是则先清理旧房间关联
        WebSocketUserInfo oldUserInfo = userInfoMap.get(session);
        if (oldUserInfo != null && !oldUserInfo.getRoomId().equals(roomId)) {
            log.info("User {} switching from room {} to room {}, cleaning up old room association",
                    userName, oldUserInfo.getRoomId(), roomId);
            broadcastService.removeRoomSession(oldUserInfo.getRoomId(), session);
        }

        userInfoMap.put(session, userInfo);
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);

        // 注册到广播服务（唯一会话管理源）
        broadcastService.registerRoomSession(roomId, session);

        // 发送历史消息（只发送最新的10条）
        chatRoomService.getChatRoom(roomId).ifPresent(room -> {
            try {
                List<ChatRoom.Message> allMessages = room.getMessages();
                List<ChatRoom.Message> recentMessages = allMessages;

                // 只取最近10条消息
                if (allMessages != null && allMessages.size() > 10) {
                    recentMessages = allMessages.subList(allMessages.size() - 10, allMessages.size());
                }

                // 为历史消息补充头像信息（旧消息可能没有保存 senderAvatar）
                // 使用缓存避免重复查询数据库
                List<ChatRoom.Message> enrichedMessages = recentMessages.stream()
                        .map(msg -> {
                            if (msg.getSenderAvatar() == null || msg.getSenderAvatar().isEmpty()) {
                                String senderId = msg.getSenderId();
                                
                                // 先尝试从缓存获取头像
                                String cachedAvatar = avatarCacheService.getAvatarFromCache(senderId);
                                if (cachedAvatar != null) {
                                    return msg.toBuilder().senderAvatar(cachedAvatar).build();
                                }
                                
                                // 缓存未命中，从数据库查询
                                try {
                                    User msgUser = userService.getUserById(senderId);
                                    if (msgUser != null && msgUser.getAvatar() != null) {
                                        // 将头像存入缓存
                                        avatarCacheService.putAvatarInCache(senderId, msgUser.getAvatar());
                                        return msg.toBuilder().senderAvatar(msgUser.getAvatar()).build();
                                    }
                                } catch (Exception e) {
                                    log.debug("Failed to get avatar for userId: {}", senderId);
                                }
                            }
                            return msg;
                        })
                        .toList();

                log.info("Enriched {} messages with avatars from cache (cache size: {})", 
                        enrichedMessages.size(), avatarCacheService.getCacheSize());

                WebSocketMessage historyMsg = WebSocketMessage.builder()
                        .type("history")
                        .messages(enrichedMessages)
                        .hasMore(allMessages != null && allMessages.size() > 10)
                        .build();
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(historyMsg)));
            } catch (IOException e) {
                log.error("Failed to send history", e);
            }
        });

        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("user_joined")
                .userId(userId)
                .userName(nickname)
                .build());
    }

    /**
     * 从数据库获取用户信息，失败时重试
     * @param userName 用户名
     * @return User对象，如果所有重试都失败则返回null
     */
    private User getUserFromDatabaseWithRetry(String userName) {
        final int MAX_RETRIES = 3;
        final long RETRY_DELAY_MS = 100;
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return userService.getUserByUsername(userName);
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("Failed to get user {} from database (attempt {}/{}), retrying...", 
                            userName, attempt, MAX_RETRIES);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    log.error("Failed to get user {} from database after {} attempts", userName, MAX_RETRIES, e);
                }
            }
        }
        return null;
    }

    private void handleMessage(WebSocketSession session, WebSocketMessage payload) {
        WebSocketUserInfo userInfo = userInfoMap.get(session);
        if (userInfo == null) return;

        String roomId = userInfo.getRoomId();
        String content = payload.getContent();
        List<com.ooc.websocket.Attachment> attachments = payload.getAttachments();
        boolean hasAttachments = attachments != null && !attachments.isEmpty();

        // 检查是否@OpenClaw（使用配置的机器人用户名）
        String openclawUsername = openClawPluginService.getBotUsername();
        boolean mentionedOpenClaw = content != null &&
                content.toLowerCase().contains("@" + openclawUsername.toLowerCase());

        // 检查是否@Kimi（使用配置的机器人用户名）
        String kimiUsername = kimiPluginService.getBotUsername();
        boolean mentionedKimi = content != null &&
                content.toLowerCase().contains("@" + kimiUsername.toLowerCase());

        // 检查是否@Claude（使用配置的机器人用户名）
        String claudeUsername = claudeCodePluginService.getBotUsername();
        boolean mentionedClaude = content != null &&
                content.toLowerCase().contains("@" + claudeUsername.toLowerCase());

        // 解析@提及
        MentionService.MentionParseResult mentionResult = mentionService.parseMentions(content != null ? content : "", roomId);

        // 获取房间成员数（从广播服务获取）
        int memberCount = broadcastService.getRoomSessionCount(roomId);

        log.info("Message received: room={}, sender={}, content={}, attachments={}, memberCount={}, mentionedOpenClaw={}, mentionedKimi={}, mentionedClaude={}, mentions={}",
                roomId, userInfo.getUserName(),
                content != null ? content.substring(0, Math.min(50, content.length())) : "",
                hasAttachments ? attachments.size() : 0,
                memberCount, mentionedOpenClaw, mentionedKimi, mentionedClaude, mentionResult.getMentions().size());

        // 打印附件详情
        if (hasAttachments) {
            for (int i = 0; i < attachments.size(); i++) {
                Attachment att = attachments.get(i);
                log.info("Attachment [{}]: type={}, mimeType={}, url={}",
                        i, att.getType(), att.getMimeType(), att.getUrl());
            }
        }

        // 获取房间名称
        String roomName = chatRoomService.getChatRoom(roomId)
                .map(ChatRoom::getName)
                .orElse("聊天室");

        // 转换附件
        List<ChatRoom.Message.Attachment> messageAttachments = new ArrayList<>();
        if (attachments != null) {
            for (Attachment att : attachments) {
                // 将类型转换为大写以保持一致性
                String typeUpper = att.getType() != null ? att.getType().toUpperCase() : "FILE";
                String mimeType = att.getMimeType() != null ? att.getMimeType() : "application/octet-stream";
                
                String url;
                long size;
                
                // 优先使用 URL（文件路径），如果没有则使用 base64 内容
                if (att.getUrl() != null && !att.getUrl().isEmpty()) {
                    url = att.getUrl();
                    size = 0; // URL 方式不计算大小
                    log.info("Processing attachment: type={}, mimeType={}, url={}", typeUpper, mimeType, url);
                } else if (att.getContent() != null && !att.getContent().isEmpty()) {
                    url = "data:" + mimeType + ";base64," + att.getContent();
                    size = att.getContent().length() * 3 / 4;
                    log.info("Processing attachment: type={}, mimeType={}, contentLength={}",
                            typeUpper, mimeType, att.getContent().length());
                } else {
                    log.warn("Attachment has neither url nor content, skipping");
                    continue;
                }
                
                messageAttachments.add(ChatRoom.Message.Attachment.builder()
                        .id(UUID.randomUUID().toString())
                        .type(typeUpper)
                        .contentType(mimeType)
                        .name(url.substring(url.lastIndexOf("/") + 1))
                        .url(url)
                        .size(size)
                        .build());
            }
        }

        // 保存消息到聊天室
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(userInfo.getUserId())
                .senderName(userInfo.getUserName())
                .senderAvatar(userInfo.getAvatar())
                .content(content != null ? content : "")
                .timestamp(Instant.now())
                .openclawMentioned(mentionedOpenClaw)
                .fromOpenClaw(false)
                .mentions(mentionResult.getMentions())
                .mentionAll(mentionResult.isMentionAll())
                .mentionHere(mentionResult.isMentionHere())
                .attachments(messageAttachments)
                .build();

        log.info("Message built - sender: {}, senderAvatar: {}", 
                userInfo.getUserName(), 
                userInfo.getAvatar() != null ? userInfo.getAvatar() : "(null)");

        chatRoomService.addMessage(roomId, message);

        // 处理@提及（创建通知记录）
        mentionService.processMentions(message, roomId, roomName);

        // 广播消息
        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("message")
                .message(message)
                .build());

        // 决定是否触发 OpenClaw
        boolean shouldTriggerOpenClaw = shouldTriggerOpenClaw(memberCount, mentionedOpenClaw);
        log.info("OpenClaw trigger decision: shouldTrigger={}, memberCount={}, mentionedOpenClaw={}",
                shouldTriggerOpenClaw, memberCount, mentionedOpenClaw);

        if (shouldTriggerOpenClaw) {
            triggerOpenClaw(roomId, content, attachments, userInfo, message.getId());
        }

        // 决定是否触发 Kimi
        boolean shouldTriggerKimi = shouldTriggerKimi(memberCount, mentionedKimi);
        log.info("Kimi trigger decision: shouldTrigger={}, memberCount={}, mentionedKimi={}",
                shouldTriggerKimi, memberCount, mentionedKimi);

        if (shouldTriggerKimi) {
            triggerKimi(roomId, content, attachments, userInfo, message.getId());
        }

        // 决定是否触发 Claude Code
        boolean shouldTriggerClaude = shouldTriggerClaude(memberCount, mentionedClaude);
        log.info("Claude trigger decision: shouldTrigger={}, memberCount={}, mentionedClaude={}",
                shouldTriggerClaude, memberCount, mentionedClaude);

        if (shouldTriggerClaude) {
            triggerClaude(roomId, content, attachments, userInfo, message.getId());
        }
    }

    private boolean shouldTriggerOpenClaw(int memberCount, boolean mentionedOpenClaw) {
        // 只有 @OpenClaw 时才触发回复
        if (!mentionedOpenClaw) {
            return false;
        }
        // 检查机器人是否启用
        return openClawPluginService.isBotEnabled();
    }

    private boolean shouldTriggerKimi(int memberCount, boolean mentionedKimi) {
        // 只有 @Kimi 时才触发回复
        if (!mentionedKimi) {
            return false;
        }
        // 检查机器人是否启用
        return kimiPluginService.isBotEnabled();
    }

    private boolean shouldTriggerClaude(int memberCount, boolean mentionedClaude) {
        // 只有 @Claude 时才触发回复
        if (!mentionedClaude) {
            return false;
        }
        // 检查机器人是否启用
        return claudeCodePluginService.isBotEnabled();
    }


    private void triggerKimi(String roomId, String content, List<Attachment> attachments, WebSocketUserInfo userInfo, String sourceMessageId) {
        log.info("Adding Kimi task to queue for room: {}, content: {}, attachments: {}",
                roomId,
                content != null ? content.substring(0, Math.min(50, content.length())) : "",
                attachments != null ? attachments.size() : 0);

        // 添加任务到持久化队列
        String taskId = taskQueueService.addTask(roomId, content, attachments, userInfo, sourceMessageId, BotTaskQueue.BotType.KIMI);
        int queueSize = taskQueueService.getQueueSize(roomId, BotTaskQueue.BotType.KIMI);

        log.info("Kimi task {} added to room {} queue. Queue size: {}", taskId, roomId, queueSize);

        // 发送排队状态消息
        sendKimiQueueStatusMessage(roomId, taskId, sourceMessageId, queueSize - 1);

        // 尝试启动队列处理
        taskQueueService.tryProcessNext(roomId, BotTaskQueue.BotType.KIMI);
    }

    /**
     * 执行 Kimi 任务（流式版本）
     */
    private void executeKimiTask(OpenClawTask task) {
        String roomId = task.getRoomId();
        String taskId = task.getTaskId();
        log.info("Executing Kimi task {} for room {} (streaming)", taskId, roomId);

        task.setStatus(OpenClawTask.TaskStatus.PROCESSING);

        // 获取机器人配置
        String botUsername = kimiPluginService.getBotUsername();
        String botAvatarUrl = kimiPluginService.getBotAvatarUrl();

        // 创建流式消息 - 使用配置的机器人用户名和头像
        String streamingMessageId = UUID.randomUUID().toString();
        AtomicReference<StringBuilder> contentBuilder = new AtomicReference<>(new StringBuilder());
        AtomicReference<ChatRoom.Message> streamingMessage = new AtomicReference<>(
            ChatRoom.Message.builder()
                .id(streamingMessageId)
                .senderId(kimiPluginService.getBotUsername())
                .senderName(kimiPluginService.getBotUsername())
                .senderAvatar(botAvatarUrl)
                .content("")
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(true)
                .toolCalls(new ArrayList<>())
                .replyToMessageId(task.getSourceMessageId())
                .build()
        );

        try {
            // 保存初始消息到聊天室
            chatRoomService.addMessage(roomId, streamingMessage.get());

            // 广播流式消息开始
            ChatRoom.Message msg = streamingMessage.get();
            log.info("[Kimi] Broadcasting stream_start with replyToMessageId: {} (task sourceMessageId: {})",
                    msg.getReplyToMessageId(), task.getSourceMessageId());
            broadcastToRoom(roomId, WebSocketMessage.builder()
                    .type("stream_start")
                    .roomId(roomId)
                    .message(msg)
                    .build());
        } catch (Exception e) {
            log.error("Failed to initialize Kimi streaming message for task {}: {}", taskId, e.getMessage(), e);
            task.setStatus(OpenClawTask.TaskStatus.FAILED);
            taskQueueService.markTaskFailed(taskId);
            taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.KIMI, taskId);
            return;
        }

        chatRoomService.getChatRoom(roomId).ifPresentOrElse(room -> {
            try {
                String kimiSessionId = room.getOpenClawSessions() != null ?
                    room.getOpenClawSessions().stream()
                            .filter(ChatRoom.OpenClawSession::isActive)
                            .findFirst()
                            .map(ChatRoom.OpenClawSession::getSessionId)
                            .orElse(null) : null;

            // 检查会话是否存活
            if (kimiSessionId != null && !kimiPluginService.isSessionAlive(kimiSessionId)) {
                log.info("Kimi session {} is not alive, will create new", kimiSessionId);
                kimiSessionId = null;
            }

            final String finalSessionId = kimiSessionId;

            if (finalSessionId == null) {
                // 创建新会话并发送流式消息
                log.info("Creating new Kimi session for room: {}", roomId);
                reactor.core.Disposable subscription = oocSessionService.getOrCreateSession(roomId, room.getName())
                        .flatMap(oocSession -> {
                            if (oocSession.getMessages().size() > 30) {
                                return oocSessionService.summarizeAndCompact(oocSession)
                                        .thenReturn(oocSession);
                            }
                            return reactor.core.publisher.Mono.just(oocSession);
                        })
                        .flatMap(oocSession -> {
                            List<Map<String, Object>> context = convertToContext(oocSession);
                            log.info("Creating Kimi session with {} context messages", context.size());
                            return kimiPluginService.createSession("ooc-" + roomId, context);
                        })
                        .flatMapMany(newSession -> {
                            chatRoomService.updateOpenClawSession(roomId, newSession.sessionId());
                            log.info("Kimi session created: {}", newSession.sessionId());
                            return kimiPluginService.sendMessageStream(
                                    newSession.sessionId(),
                                    task.getContent(),
                                    task.getAttachments(),
                                    task.getUserInfo().getUserId(),
                                    task.getUserInfo().getUserName(),
                                    room.getName());
                        })
                        .subscribe(
                                event -> handleKimiStreamEvent(roomId, streamingMessageId, contentBuilder, streamingMessage, event, task),
                                error -> {
                                    log.error("Kimi streaming error in task {}", taskId, error);
                                    task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                    taskQueueService.markTaskFailed(taskId);
                                    handleKimiStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), error.getMessage(), task);
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.KIMI, taskId);
                                },
                                () -> {
                                    log.info("Kimi streaming completed for task {}", taskId);
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    taskQueueService.markTaskCompleted(taskId);
                                    finalizeKimiStreamMessage(roomId, streamingMessageId, contentBuilder.get().toString(), task);
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.KIMI, taskId);
                                }
                        );
                taskQueueService.registerTaskSubscription(taskId, subscription);
            } else {
                // 使用现有会话发送流式消息
                log.info("Using existing Kimi session: {}", finalSessionId);
                reactor.core.Disposable subscription = kimiPluginService.sendMessageStream(
                                finalSessionId,
                                task.getContent(),
                                task.getAttachments(),
                                task.getUserInfo().getUserId(),
                                task.getUserInfo().getUserName(),
                                room.getName())
                        .subscribe(
                                event -> handleKimiStreamEvent(roomId, streamingMessageId, contentBuilder, streamingMessage, event, task),
                                error -> {
                                    log.error("Kimi streaming error in task {}", taskId, error);
                                    task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                    taskQueueService.markTaskFailed(taskId);
                                    handleKimiStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), error.getMessage(), task);
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.KIMI, taskId);
                                },
                                () -> {
                                    log.info("Kimi streaming completed for task {}", taskId);
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    taskQueueService.markTaskCompleted(taskId);
                                    finalizeKimiStreamMessage(roomId, streamingMessageId, contentBuilder.get().toString(), task);
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.KIMI, taskId);
                                }
                        );
                taskQueueService.registerTaskSubscription(taskId, subscription);
            }
            } catch (Exception e) {
                log.error("Error in Kimi task execution for task {}: {}", taskId, e.getMessage(), e);
                task.setStatus(OpenClawTask.TaskStatus.FAILED);
                taskQueueService.markTaskFailed(taskId);
                handleKimiStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), e.getMessage(), task);
                taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.KIMI, taskId);
            }
        }, () -> {
            log.error("Chat room not found: {}", roomId);
            task.setStatus(OpenClawTask.TaskStatus.FAILED);
            taskQueueService.markTaskFailed(taskId);
            taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.KIMI, taskId);
        });
    }

    /**
     * 处理 Kimi 流式事件
     */
    private void handleKimiStreamEvent(String roomId, String messageId,
            AtomicReference<StringBuilder> contentBuilder,
            AtomicReference<ChatRoom.Message> streamingMessage,
            KimiPluginService.StreamEvent event,
            OpenClawTask task) {

        log.debug("Kimi stream event for task {}: type={}, contentLength={}",
                task.getTaskId(),
                event.type(),
                event.content() != null ? event.content().length() : 0);

        if ("message".equals(event.type())) {
            if (event.content() != null && !event.content().isEmpty()) {
                // 追加内容
                contentBuilder.get().append(event.content());
                String currentContent = contentBuilder.get().toString();

                log.debug("Appending Kimi content for task {}: newChars={}, totalChars={}",
                        task.getTaskId(),
                        event.content().length(), currentContent.length());

                // 更新消息内容
                ChatRoom.Message updatedMsg = streamingMessage.get().toBuilder()
                        .content(currentContent)
                        .build();
                streamingMessage.set(updatedMsg);

                // 广播增量更新
                broadcastToRoom(roomId, WebSocketMessage.builder()
                        .type("stream_delta")
                        .roomId(roomId)
                        .message(ChatRoom.Message.builder()
                                .id(messageId)
                                .content(event.content())
                                .delta(true)
                                .replyToMessageId(task.getSourceMessageId())
                                .build())
                        .build());
            }
        } else if ("done".equals(event.type())) {
            log.info("Kimi stream done event received for task {}", task.getTaskId());
        } else if ("error".equals(event.type())) {
            log.error("Kimi stream error for task {}: {}", task.getTaskId(), event.content());
        }
    }

    /**
     * 处理 Kimi 流式错误
     */
    private void handleKimiStreamError(String roomId, String messageId, String partialContent, String error, OpenClawTask task) {
        ChatRoom.Message errorMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId(kimiPluginService.getBotUsername())
                .senderName(kimiPluginService.getBotUsername())
                .content(partialContent + "\n\n[错误: " + error + "]")
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(false)
                .build();

        chatRoomService.updateMessage(roomId, errorMsg);

        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("stream_end")
                .roomId(roomId)
                .message(errorMsg)
                .build());
    }

    /**
     * 完成 Kimi 流式消息
     */
    private void finalizeKimiStreamMessage(String roomId, String messageId, String finalContent, OpenClawTask task) {
        log.info("Finalizing Kimi stream message for task {}: contentLength={}, isNull={}, isEmpty={}",
                task.getTaskId(),
                finalContent != null ? finalContent.length() : -1,
                finalContent == null,
                finalContent != null ? finalContent.isEmpty() : "N/A");

        if (finalContent == null || finalContent.isEmpty()) {
            log.warn("Kimi stream message finalized with empty content for task {}, setting placeholder text", task.getTaskId());
            finalContent = "*(Kimi 无回复)*";
        }

        ChatRoom.Message finalMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId(kimiPluginService.getBotUsername())
                .senderName(kimiPluginService.getBotUsername())
                .content(finalContent)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(false)
                .replyToMessageId(task.getSourceMessageId())
                .build();

        oocSessionService.addMessage(roomId, OocSession.SessionMessage.builder()
                .id(UUID.randomUUID().toString())
                .senderId(kimiPluginService.getBotUsername())
                .senderName(kimiPluginService.getBotUsername())
                .content(finalContent)
                .timestamp(Instant.now())
                .fromOpenClaw(true)
                .build());

        chatRoomService.updateMessage(roomId, finalMsg);

        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("stream_end")
                .roomId(roomId)
                .message(finalMsg)
                .build());

        log.info("Kimi stream message finalized for task {}, content length: {}",
                task.getTaskId(), finalContent.length());
    }

    /**
     * 发送 Kimi 排队状态消息
     */
    private void sendKimiQueueStatusMessage(String roomId, String taskId, String sourceMessageId, int position) {
        String statusText = position == 0
                ? "🤖 Kimi 任务已加入队列，正在准备处理..."
                : String.format("🤖 Kimi 任务已加入队列，当前排第 %d 位...", position + 1);

        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(kimiPluginService.getBotUsername())
                .senderName(kimiPluginService.getBotUsername())
                .content(statusText)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .replyToMessageId(sourceMessageId)
                .build();

        chatRoomService.addMessage(roomId, message);
        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("message")
                .message(message)
                .build());
    }

    // ========== Claude Code 任务处理 ==========

    private void triggerClaude(String roomId, String content, List<Attachment> attachments, WebSocketUserInfo userInfo, String sourceMessageId) {
        log.info("Adding Claude task to queue for room: {}, content: {}, attachments: {}",
                roomId,
                content != null ? content.substring(0, Math.min(50, content.length())) : "",
                attachments != null ? attachments.size() : 0);

        // 添加任务到持久化队列
        String taskId = taskQueueService.addTask(roomId, content, attachments, userInfo, sourceMessageId, BotTaskQueue.BotType.CLAUDE);
        int queueSize = taskQueueService.getQueueSize(roomId, BotTaskQueue.BotType.CLAUDE);

        log.info("Claude task {} added to room {} queue. Queue size: {}", taskId, roomId, queueSize);

        // 发送排队状态消息
        sendClaudeQueueStatusMessage(roomId, taskId, sourceMessageId, queueSize - 1);

        // 尝试启动队列处理
        taskQueueService.tryProcessNext(roomId, BotTaskQueue.BotType.CLAUDE);
    }

    /**
     * 执行 Claude 任务（流式版本）
     */
    private void executeClaudeTask(OpenClawTask task) {
        String roomId = task.getRoomId();
        String taskId = task.getTaskId();

        log.info("Executing Claude task {} for room {} (streaming)", taskId, roomId);

        task.setStatus(OpenClawTask.TaskStatus.PROCESSING);

        // 获取机器人信息
        String botUsername = claudeCodePluginService.getBotUsername();
        String botAvatarUrl = claudeCodePluginService.getBotAvatarUrl();

        // 创建流式消息占位符
        String streamingMessageId = UUID.randomUUID().toString();
        ChatRoom.Message streamingMessage = ChatRoom.Message.builder()
                .id(streamingMessageId)
                .senderId(claudeCodePluginService.getBotUsername())
                .senderName(claudeCodePluginService.getBotUsername())
                .senderAvatar(botAvatarUrl)
                .content("") // 初始为空
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(true) // 标记为流式消息
                .replyToMessageId(task.getSourceMessageId())
                .build();

        try {
            // 保存到数据库并广播
            chatRoomService.addMessage(roomId, streamingMessage);
            log.info("[Claude] Broadcasting stream_start with replyToMessageId: {} (task sourceMessageId: {})",
                    streamingMessage.getReplyToMessageId(), task.getSourceMessageId());
            broadcastToRoom(roomId, WebSocketMessage.builder()
                    .type("stream_start")
                    .roomId(roomId)
                    .message(streamingMessage)
                    .build());
        } catch (Exception e) {
            log.error("Failed to initialize Claude streaming message for task {}: {}", taskId, e.getMessage(), e);
            task.setStatus(OpenClawTask.TaskStatus.FAILED);
            taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.CLAUDE, taskId);
            return;
        }

        // 使用 AtomicReference 来累积内容
        AtomicReference<StringBuilder> contentBuilder = new AtomicReference<>(new StringBuilder());

        try {
            // 获取或创建会话
            Optional<ChatRoom> roomOpt = chatRoomService.getChatRoom(roomId);
            if (roomOpt.isEmpty()) {
                throw new RuntimeException("Room not found: " + roomId);
            }

            ChatRoom room = roomOpt.get();

            // 检查现有会话
            String claudeSessionId = room.getOpenClawSessions() != null ?
                    room.getOpenClawSessions().stream()
                            .filter(ChatRoom.OpenClawSession::isActive)
                            .findFirst()
                            .map(ChatRoom.OpenClawSession::getSessionId)
                            .orElse(null)
                    : null;

            if (claudeSessionId != null && !claudeCodePluginService.isSessionAlive(claudeSessionId)) {
                log.info("Claude session {} is not alive, will create new", claudeSessionId);
                claudeSessionId = null;
            }

            final String finalSessionId = claudeSessionId;

            // 获取房间名称
            String roomName = chatRoomService.getChatRoom(roomId)
                    .map(ChatRoom::getName)
                    .orElse("聊天室");

            if (finalSessionId == null) {
                // 创建新会话
                log.info("Creating new Claude session for room: {}", roomId);

                // 使用 oocSessionService 获取或创建会话并构建上下文
                reactor.core.Disposable subscription = oocSessionService.getOrCreateSession("ooc-" + roomId, roomId)
                        .flatMap(oocSession -> {
                            if (oocSession.getMessages().size() > 30) {
                                return oocSessionService.summarizeAndCompact(oocSession)
                                        .thenReturn(oocSession);
                            }
                            return reactor.core.publisher.Mono.just(oocSession);
                        })
                        .flatMap(oocSession -> {
                            List<Map<String, Object>> context = convertToContext(oocSession);
                            log.info("Creating Claude session with {} context messages", context.size());
                            return claudeCodePluginService.createSession("ooc-" + roomId, context);
                        })
                        .flatMapMany(newSession -> {
                            chatRoomService.updateOpenClawSession(roomId, newSession.sessionId());
                            log.info("Claude session created: {}", newSession.sessionId());
                            return claudeCodePluginService.sendMessageStream(
                                    newSession.sessionId(),
                                    task.getContent(),
                                    task.getAttachments(),
                                    task.getUserInfo().getUserId(),
                                    task.getUserInfo().getUserName(),
                                    roomName
                            );
                        })
                        .subscribe(
                                event -> handleClaudeStreamEvent(roomId, streamingMessageId, contentBuilder, streamingMessage, event, task),
                                error -> {
                                    log.error("Claude streaming error in task {}", taskId, error);
                                    task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                    taskQueueService.markTaskFailed(taskId);
                                    handleClaudeStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), error.getMessage(), task);
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.CLAUDE, taskId);
                                },
                                () -> {
                                    log.info("Claude streaming completed for task {}", taskId);
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    taskQueueService.markTaskCompleted(taskId);
                                    finalizeClaudeStreamMessage(roomId, streamingMessageId, contentBuilder.get().toString(), task);
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.CLAUDE, taskId);
                                }
                        );
                taskQueueService.registerTaskSubscription(taskId, subscription);
            } else {
                // 使用现有会话
                log.info("Using existing Claude session: {}", finalSessionId);
                reactor.core.Disposable subscription = claudeCodePluginService.sendMessageStream(
                                finalSessionId,
                                task.getContent(),
                                task.getAttachments(),
                                task.getUserInfo().getUserId(),
                                task.getUserInfo().getUserName(),
                                roomName
                        )
                        .subscribe(
                                event -> handleClaudeStreamEvent(roomId, streamingMessageId, contentBuilder, streamingMessage, event, task),
                                error -> {
                                    log.error("Claude streaming error in task {}", taskId, error);
                                    task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                    taskQueueService.markTaskFailed(taskId);
                                    handleClaudeStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), error.getMessage(), task);
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.CLAUDE, taskId);
                                },
                                () -> {
                                    log.info("Claude streaming completed for task {}", taskId);
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    taskQueueService.markTaskCompleted(taskId);
                                    finalizeClaudeStreamMessage(roomId, streamingMessageId, contentBuilder.get().toString(), task);
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.CLAUDE, taskId);
                                }
                        );
                taskQueueService.registerTaskSubscription(taskId, subscription);
            }
        } catch (Exception e) {
            log.error("Error executing Claude task {}", taskId, e);
            task.setStatus(OpenClawTask.TaskStatus.FAILED);
            taskQueueService.markTaskFailed(taskId);
            taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.CLAUDE, taskId);
        }
    }

    /**
     * 处理 Claude 流式事件
     */
    private void handleClaudeStreamEvent(String roomId, String messageId,
                                          AtomicReference<StringBuilder> contentBuilder,
                                          ChatRoom.Message streamingMessage,
                                          ClaudeCodePluginService.StreamEvent event,
                                          OpenClawTask task) {

        log.debug("Claude stream event for task {}: type={}, contentLength={}",
                task.getTaskId(), event.type(),
                event.content() != null ? event.content().length() : 0);

        switch (event.type()) {
            case "message" -> {
                if (event.content() != null) {
                    contentBuilder.get().append(event.content());

                    log.debug("Appending Claude content for task {}: newChars={}, totalChars={}",
                            task.getTaskId(), event.content().length(), contentBuilder.get().length());

                    // 创建增量更新消息
                    ChatRoom.Message deltaMessage = ChatRoom.Message.builder()
                            .id(messageId)
                            .senderId(claudeCodePluginService.getBotUsername())
                            .senderName(claudeCodePluginService.getBotUsername())
                            .senderAvatar(claudeCodePluginService.getBotAvatarUrl())
                            .content(event.content()) // 只发送增量内容
                            .timestamp(Instant.now())
                            .openclawMentioned(false)
                            .fromOpenClaw(true)
                            .isStreaming(true)
                            .delta(true) // 标记为增量更新
                            .replyToMessageId(task.getSourceMessageId())
                            .build();

                    // 广播增量更新
                    broadcastToRoom(roomId, WebSocketMessage.builder()
                            .type("stream_delta")
                            .roomId(roomId)
                            .message(deltaMessage)
                            .build());
                }
            }
            case "done" -> log.info("Claude stream done event received for task {}", task.getTaskId());
            case "error" -> log.error("Claude stream error for task {}: {}", task.getTaskId(), event.content());
        }
    }

    /**
     * 处理 Claude 流式错误
     */
    private void handleClaudeStreamError(String roomId, String messageId, String partialContent, String error, OpenClawTask task) {
        ChatRoom.Message errorMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId(claudeCodePluginService.getBotUsername())
                .senderName(claudeCodePluginService.getBotUsername())
                .senderAvatar(claudeCodePluginService.getBotAvatarUrl())
                .content(partialContent + "\n\n*(Claude 回复出错: " + error + " )*")
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(false)
                .build();

        chatRoomService.updateMessage(roomId, errorMsg);

        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("stream_end")
                .roomId(roomId)
                .message(errorMsg)
                .build());
    }

    /**
     * 完成 Claude 流式消息
     */
    private void finalizeClaudeStreamMessage(String roomId, String messageId, String finalContent, OpenClawTask task) {
        log.info("Finalizing Claude stream message for task {}: contentLength={}, isNull={}, isEmpty={}",
                task.getTaskId(),
                finalContent != null ? finalContent.length() : 0,
                finalContent == null,
                finalContent != null && finalContent.isEmpty());

        // 如果内容为空，设置一个占位符
        if (finalContent == null || finalContent.isEmpty()) {
            log.warn("Claude stream message finalized with empty content for task {}, setting placeholder text", task.getTaskId());
            finalContent = "*(Claude 无回复)*";
        }

        ChatRoom.Message finalMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId(claudeCodePluginService.getBotUsername())
                .senderName(claudeCodePluginService.getBotUsername())
                .senderAvatar(claudeCodePluginService.getBotAvatarUrl())
                .content(finalContent)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(false)
                .replyToMessageId(task.getSourceMessageId())
                .build();

        ChatRoom.Message saveMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId(claudeCodePluginService.getBotUsername())
                .senderName(claudeCodePluginService.getBotUsername())
                .senderAvatar(claudeCodePluginService.getBotAvatarUrl())
                .content(finalContent)
                .timestamp(Instant.now())
                .fromOpenClaw(true)
                .toolCalls(null)
                .build();

        chatRoomService.updateMessage(roomId, saveMsg);

        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("stream_end")
                .roomId(roomId)
                .message(finalMsg)
                .build());

        log.info("Claude stream message finalized for task {}, content length: {}",
                task.getTaskId(), finalContent.length());
    }

    /**
     * 发送 Claude 排队状态消息
     */
    private void sendClaudeQueueStatusMessage(String roomId, String taskId, String sourceMessageId, int position) {
        String statusText = position == 0
                ? "🤖 Claude 任务已加入队列，正在准备处理..."
                : String.format("🤖 Claude 任务已加入队列，当前排第 %d 位...", position + 1);

        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(claudeCodePluginService.getBotUsername())
                .senderName(claudeCodePluginService.getBotUsername())
                .content(statusText)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .replyToMessageId(sourceMessageId)
                .build();

        chatRoomService.addMessage(roomId, message);
        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("message")
                .message(message)
                .build());
    }

    private void triggerOpenClaw(String roomId, String content, List<Attachment> attachments, WebSocketUserInfo userInfo, String sourceMessageId) {
        log.info("Adding OpenClaw task to queue for room: {}, content: {}, attachments: {}",
                roomId,
                content != null ? content.substring(0, Math.min(50, content.length())) : "",
                attachments != null ? attachments.size() : 0);

        // 添加任务到持久化队列
        String taskId = taskQueueService.addTask(roomId, content, attachments, userInfo, sourceMessageId, BotTaskQueue.BotType.OPENCLAW);
        int queueSize = taskQueueService.getQueueSize(roomId, BotTaskQueue.BotType.OPENCLAW);

        log.info("OpenClaw task {} added to room {} queue. Queue size: {}", taskId, roomId, queueSize);

        // 发送排队状态消息
        sendQueueStatusMessage(roomId, taskId, sourceMessageId, queueSize - 1);

        // 尝试启动队列处理
        taskQueueService.tryProcessNext(roomId, BotTaskQueue.BotType.OPENCLAW);
    }

    /**
     * 执行 OpenClaw 任务（流式版本）
     */
    private void executeOpenClawTask(OpenClawTask task) {
        String roomId = task.getRoomId();
        String taskId = task.getTaskId();
        
        // 检查是否是流程图任务
        if (task.getTaskType() == OpenClawTask.TaskType.FLOWCHART) {
            log.info("Delegating flowchart task {} to FlowchartTaskQueueIntegration (instanceId={})", 
                    taskId, task.getFlowchartInstanceId());
            flowchartTaskQueueIntegration.executeFlowchartTask(task);
            return;
        }
        
        log.info("Executing OpenClaw task {} for room {} (streaming)", taskId, roomId);

        task.setStatus(OpenClawTask.TaskStatus.PROCESSING);

        // 获取机器人配置
        String botUsername = openClawPluginService.getBotUsername();
        String botAvatarUrl = openClawPluginService.getBotAvatarUrl();

        // 创建流式消息 - 使用配置的机器人用户名和头像
        String streamingMessageId = UUID.randomUUID().toString();
        AtomicReference<StringBuilder> contentBuilder = new AtomicReference<>(new StringBuilder());
        AtomicReference<ChatRoom.Message> streamingMessage = new AtomicReference<>(
            ChatRoom.Message.builder()
                .id(streamingMessageId)
                .senderId(openClawPluginService.getBotUsername())
                .senderName(openClawPluginService.getBotUsername())
                .senderAvatar(botAvatarUrl)
                .content("")
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(true)
                .toolCalls(new ArrayList<>())
                .replyToMessageId(task.getSourceMessageId())
                .build()
        );

        try {
            // 保存初始消息到聊天室
            chatRoomService.addMessage(roomId, streamingMessage.get());

            // 广播流式消息开始
            ChatRoom.Message msg = streamingMessage.get();
            log.info("[OpenClaw] Broadcasting stream_start with replyToMessageId: {} (task sourceMessageId: {})",
                    msg.getReplyToMessageId(), task.getSourceMessageId());
            broadcastToRoom(roomId, WebSocketMessage.builder()
                    .type("stream_start")
                    .roomId(roomId)
                    .message(msg)
                    .build());
        } catch (Exception e) {
            log.error("Failed to initialize streaming message for task {}: {}", taskId, e.getMessage(), e);
            task.setStatus(OpenClawTask.TaskStatus.FAILED);
            taskQueueService.markTaskFailed(taskId);
            taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.OPENCLAW, taskId);
            return;
        }

        chatRoomService.getChatRoom(roomId).ifPresentOrElse(room -> {
            try {
                String openClawSessionId = room.getOpenClawSessions() != null ?
                    room.getOpenClawSessions().stream()
                            .filter(ChatRoom.OpenClawSession::isActive)
                            .findFirst()
                            .map(ChatRoom.OpenClawSession::getSessionId)
                            .orElse(null) : null;

            // 检查会话是否存活
            if (openClawSessionId != null && !openClawPluginService.isSessionAlive(openClawSessionId)) {
                log.info("OpenClaw session {} is not alive, will create new", openClawSessionId);
                openClawSessionId = null;
            }

            final String finalSessionId = openClawSessionId;

            if (finalSessionId == null) {
                // 创建新会话并发送流式消息
                log.info("Creating new OpenClaw session for room: {}", roomId);
                reactor.core.Disposable subscription = oocSessionService.getOrCreateSession(roomId, room.getName())
                        .flatMap(oocSession -> {
                            if (oocSession.getMessages().size() > 30) {
                                return oocSessionService.summarizeAndCompact(oocSession)
                                        .thenReturn(oocSession);
                            }
                            return reactor.core.publisher.Mono.just(oocSession);
                        })
                        .flatMap(oocSession -> {
                            List<Map<String, Object>> context = convertToContext(oocSession);
                            log.info("Creating OpenClaw session with {} context messages", context.size());
                            return openClawPluginService.createSession("ooc-" + roomId, context);
                        })
                        .flatMapMany(newSession -> {
                            chatRoomService.updateOpenClawSession(roomId, newSession.sessionId());
                            log.info("OpenClaw session created: {}", newSession.sessionId());
                            return openClawPluginService.sendMessageStream(
                                    newSession.sessionId(),
                                    task.getContent(),
                                    task.getAttachments(),
                                    task.getUserInfo().getUserId(),
                                    task.getUserInfo().getUserName(),
                                    room.getName());
                        })
                        .subscribe(
                                event -> handleOpenClawStreamEvent(roomId, streamingMessageId, contentBuilder, streamingMessage, event, task),
                                error -> {
                                    log.error("OpenClaw streaming error in task {}", taskId, error);
                                    // 检查是否是 SESSION_BUSY 错误，如果是则重新入队
                                    if (error.getMessage() != null && error.getMessage().contains("SESSION_BUSY")) {
                                        log.warn("Task {} received SESSION_BUSY, will requeue and retry", taskId);
                                        task.setStatus(OpenClawTask.TaskStatus.PENDING);
                                        taskQueueService.markTaskFailed(taskId); // 标记当前任务失败
                                        // 重新添加任务到队列
                                        taskQueueService.addTask(roomId, task.getContent(), task.getAttachments(), 
                                                task.getUserInfo(), task.getSourceMessageId(), BotTaskQueue.BotType.OPENCLAW);
                                        // 延迟一点再试
                                        try {
                                            Thread.sleep(500);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.OPENCLAW, taskId);
                                    } else {
                                        task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                        taskQueueService.markTaskFailed(taskId);
                                        handleOpenClawStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), error.getMessage(), task);
                                        taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.OPENCLAW, taskId);
                                    }
                                },
                                () -> {
                                    log.info("OpenClaw streaming completed for task {}", taskId);
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    taskQueueService.markTaskCompleted(taskId);
                                    finalizeOpenClawStreamMessage(roomId, streamingMessageId, contentBuilder.get().toString(), task, streamingMessage.get().getToolCalls());
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.OPENCLAW, taskId);
                                }
                        );
                // 注册订阅以便后续取消
                taskQueueService.registerTaskSubscription(taskId, subscription);
            } else {
                // 使用现有会话发送流式消息
                log.info("Using existing OpenClaw session: {}", finalSessionId);
                reactor.core.Disposable subscription = openClawPluginService.sendMessageStream(
                                finalSessionId,
                                task.getContent(),
                                task.getAttachments(),
                                task.getUserInfo().getUserId(),
                                task.getUserInfo().getUserName(),
                                room.getName())
                        .subscribe(
                                event -> handleOpenClawStreamEvent(roomId, streamingMessageId, contentBuilder, streamingMessage, event, task),
                                error -> {
                                    log.error("OpenClaw streaming error in task {}", taskId, error);
                                    task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                    taskQueueService.markTaskFailed(taskId);
                                    handleOpenClawStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), error.getMessage(), task);
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.OPENCLAW, taskId);
                                },
                                () -> {
                                    log.info("OpenClaw streaming completed for task {}", taskId);
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    taskQueueService.markTaskCompleted(taskId);
                                    finalizeOpenClawStreamMessage(roomId, streamingMessageId, contentBuilder.get().toString(), task, streamingMessage.get().getToolCalls());
                                    taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.OPENCLAW, taskId);
                                }
                        );
                // 注册订阅以便后续取消
                taskQueueService.registerTaskSubscription(taskId, subscription);
            }
            } catch (Exception e) {
                log.error("Error in OpenClaw task execution for task {}: {}", taskId, e.getMessage(), e);
                task.setStatus(OpenClawTask.TaskStatus.FAILED);
                taskQueueService.markTaskFailed(taskId);
                handleOpenClawStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), e.getMessage(), task);
                taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.OPENCLAW, taskId);
            }
        }, () -> {
            log.error("Chat room not found: {}", roomId);
            task.setStatus(OpenClawTask.TaskStatus.FAILED);
            taskQueueService.markTaskFailed(taskId);
            taskQueueService.onTaskComplete(roomId, BotTaskQueue.BotType.OPENCLAW, taskId);
        });
    }

    /**
     * 处理 OpenClaw 流式事件
     */
    private void handleOpenClawStreamEvent(String roomId, String messageId,
            AtomicReference<StringBuilder> contentBuilder,
            AtomicReference<ChatRoom.Message> streamingMessage,
            OpenClawPluginService.StreamEvent event,
            OpenClawTask task) {

        // 检查任务是否被取消
        if (taskQueueService.isTaskCancelled(task.getTaskId())) {
            log.info("Task {} has been cancelled, stopping stream processing", task.getTaskId());
            return;
        }

        log.info("OpenClaw stream event for task {}: type={}, contentLength={}, toolName={}, totalBuilderLength={}",
                task.getTaskId(),
                event.type(),
                event.content() != null ? event.content().length() : 0,
                event.toolName(),
                contentBuilder.get().length());

        if ("message".equals(event.type())) {
            if (event.content() != null && !event.content().isEmpty()) {
                // 追加内容
                contentBuilder.get().append(event.content());
                String currentContent = contentBuilder.get().toString();

                log.info("Appending OpenClaw content for task {}: newChars={}, totalChars={}",
                        task.getTaskId(),
                        event.content().length(), currentContent.length());

                // 更新消息内容
                ChatRoom.Message updatedMsg = streamingMessage.get().toBuilder()
                        .content(currentContent)
                        .build();
                streamingMessage.set(updatedMsg);

                // 广播增量更新
                broadcastToRoom(roomId, WebSocketMessage.builder()
                        .type("stream_delta")
                        .roomId(roomId)
                        .message(ChatRoom.Message.builder()
                                .id(messageId)
                                .content(event.content())
                                .delta(true)
                                .replyToMessageId(task.getSourceMessageId())
                                .build())
                        .build());
            } else {
                log.warn("Received empty content in message event for task {}", task.getTaskId());
            }
        } else if ("tool_start".equals(event.type())) {
            // 新工具调用开始
            String toolId = event.messageId() != null ? event.messageId() : UUID.randomUUID().toString();
            String toolName = event.toolName() != null ? event.toolName() : "unknown";
            String toolInput = event.toolInput() != null ? event.toolInput() : "";

            // 记录工具调用在内容中的位置
            int position = contentBuilder.get().length();

            log.info("Tool call started for task {}: id={}, name={}, position={}", task.getTaskId(), toolId, toolName, position);

            // 创建工具调用记录
            ChatRoom.Message.ToolCall toolCall = ChatRoom.Message.ToolCall.builder()
                    .id(toolId)
                    .name(toolName)
                    .description(toolInput)
                    .status("running")
                    .timestamp(Instant.now())
                    .position(position)
                    .build();

            // 添加到当前消息的工具调用列表
            List<ChatRoom.Message.ToolCall> currentToolCalls = new ArrayList<>(streamingMessage.get().getToolCalls());
            currentToolCalls.add(toolCall);

            ChatRoom.Message updatedMsg = streamingMessage.get().toBuilder()
                    .toolCalls(currentToolCalls)
                    .isToolCall(true)
                    .build();
            streamingMessage.set(updatedMsg);

            // 广播工具调用开始事件
            broadcastToRoom(roomId, WebSocketMessage.builder()
                    .type("tool_start")
                    .roomId(roomId)
                    .message(ChatRoom.Message.builder()
                            .id(messageId)
                            .senderId(openClawPluginService.getBotUsername())
                            .senderName(openClawPluginService.getBotUsername())
                            .toolCalls(List.of(toolCall))
                            .isToolCall(true)
                            .fromOpenClaw(true)
                            .replyToMessageId(task.getSourceMessageId())
                            .build())
                    .build());

        } else if ("tool_delta".equals(event.type())) {
            // 工具参数更新（可选，如果需要实时更新参数）
            log.debug("Tool delta for task {}: {}", task.getTaskId(), event.content());

        } else if ("tool_result".equals(event.type())) {
            // 工具执行完成 - 更新工具调用状态
            String toolCallId = event.messageId();
            String result = event.content();
            
            log.info("Tool result received for task {}: toolCallId={}", task.getTaskId(), toolCallId);
            
            // 更新消息中的工具调用状态
            List<ChatRoom.Message.ToolCall> currentToolCalls = new ArrayList<>(streamingMessage.get().getToolCalls());
            boolean found = false;
            
            for (int i = 0; i < currentToolCalls.size(); i++) {
                ChatRoom.Message.ToolCall tc = currentToolCalls.get(i);
                if (tc.getId().equals(toolCallId)) {
                    currentToolCalls.set(i, tc.toBuilder()
                            .status("completed")
                            .result(result)
                            .build());
                    found = true;
                    log.info("Updated tool call {} to completed status", tc.getName());
                    break;
                }
            }
            
            if (found) {
                ChatRoom.Message updatedMsg = streamingMessage.get().toBuilder()
                        .toolCalls(currentToolCalls)
                        .build();
                streamingMessage.set(updatedMsg);
                
                // 广播工具调用完成事件到前端
                broadcastToRoom(roomId, WebSocketMessage.builder()
                        .type("tool_result")
                        .roomId(roomId)
                        .message(ChatRoom.Message.builder()
                                .id(messageId)
                                .senderId(openClawPluginService.getBotUsername())
                                .senderName(openClawPluginService.getBotUsername())
                                .toolCalls(currentToolCalls)
                                .isToolCall(true)
                                .fromOpenClaw(true)
                                .replyToMessageId(task.getSourceMessageId())
                                .build())
                        .build());
            } else {
                log.warn("Tool result received but toolCallId {} not found in message {}", toolCallId, messageId);
            }

        } else if ("done".equals(event.type())) {
            // 流结束，在 onComplete 中处理
            log.info("Stream done event received for task {}", task.getTaskId());
        } else if ("error".equals(event.type())) {
            log.error("Stream error for task {}: {}", task.getTaskId(), event.content());
        }
    }

    /**
     * 处理流式错误
     */
    private void handleOpenClawStreamError(String roomId, String messageId, String partialContent, String error, OpenClawTask task) {
        // 更新消息为错误状态 - senderAvatar 为 null，让前端显示默认机器人头像
        ChatRoom.Message errorMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId(openClawPluginService.getBotUsername())
                .senderName(openClawPluginService.getBotUsername())
                .content(partialContent + "\n\n[错误: " + error + "]")
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(false)
                .build();

        chatRoomService.updateMessage(roomId, errorMsg);

        // 广播错误完成
        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("stream_end")
                .roomId(roomId)
                .message(errorMsg)
                .build());
    }

    /**
     * 完成流式消息
     */
    private void finalizeOpenClawStreamMessage(String roomId, String messageId, String finalContent, OpenClawTask task, List<ChatRoom.Message.ToolCall> streamingToolCalls) {
        // 详细日志：记录内容状态以便诊断
        log.info("Finalizing stream message for task {}: contentLength={}, isNull={}, isEmpty={}, isBlank={}, toolCalls={}",
                task.getTaskId(),
                finalContent != null ? finalContent.length() : -1,
                finalContent == null,
                finalContent != null ? finalContent.isEmpty() : "N/A",
                finalContent != null ? finalContent.isBlank() : "N/A",
                streamingToolCalls != null ? streamingToolCalls.size() : 0);

        // 如果内容为空，设置为提示文本
        if (finalContent == null || finalContent.isEmpty()) {
            log.warn("Stream message finalized with empty content for task {}, setting placeholder text", task.getTaskId());
            finalContent = "*(OpenClaw 无回复)*";
        } else if (finalContent.isBlank()) {
            // 内容只包含空白字符，保留原始内容但记录警告
            log.warn("Stream message finalized with blank content (whitespace only) for task {}, content will be preserved", task.getTaskId());
        }

        // 使用流式过程中收集的工具调用（如果有），否则从内容解析
        List<ChatRoom.Message.ToolCall> toolCalls = (streamingToolCalls != null && !streamingToolCalls.isEmpty())
                ? streamingToolCalls
                : parseToolCalls(finalContent);

        // 从内容中解析 Tool details 并填充到工具调用中
        toolCalls = enrichToolCallsWithDetails(finalContent, toolCalls);

        log.info("finalizeOpenClawStreamMessage: parsed {} tool calls for task {}", toolCalls.size(), task.getTaskId());
        if (!toolCalls.isEmpty()) {
            toolCalls.forEach(tc -> log.info("  Tool: {} - result length={}", tc.getName(),
                    tc.getResult() != null ? tc.getResult().length() : 0));
        }

        // 创建最终消息 - senderAvatar 为 null，让前端显示默认机器人头像
        ChatRoom.Message finalMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId(openClawPluginService.getBotUsername())
                .senderName(openClawPluginService.getBotUsername())
                .content(finalContent)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(false)
                .isToolCall(!toolCalls.isEmpty())
                .toolCalls(toolCalls)
                .replyToMessageId(task.getSourceMessageId())
                .build();

        // 保存到 OOC 会话
        oocSessionService.addMessage(roomId, OocSession.SessionMessage.builder()
                .id(UUID.randomUUID().toString())
                .senderId(openClawPluginService.getBotUsername())
                .senderName(openClawPluginService.getBotUsername())
                .content(finalContent)
                .timestamp(Instant.now())
                .fromOpenClaw(true)
                .build());

        // 更新聊天室消息
        System.out.println("DEBUG: About to update message " + messageId + " with content length: " + finalContent.length());
        chatRoomService.updateMessage(roomId, finalMsg);
        System.out.println("DEBUG: Message update called for " + messageId);

        // 广播流结束
        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("stream_end")
                .roomId(roomId)
                .message(finalMsg)
                .build());

        // 发送 over 消息
        sendOverMessage(roomId, task.getSourceMessageId());

        log.info("Stream message finalized for task {}, content length: {}, toolCalls: {}",
                task.getTaskId(), finalContent.length(), toolCalls.size());
    }

    /**
     * 从内容中解析工具调用
     */
    private List<ChatRoom.Message.ToolCall> parseToolCalls(String content) {
        List<ChatRoom.Message.ToolCall> toolCalls = new ArrayList<>();

        if (content == null) {
            log.debug("parseToolCalls: content is null");
            return toolCalls;
        }

        // 检查是否有 Tools used 部分（支持多种格式）
        String toolsMarker = "**Tools used:**";
        int toolsStart = content.indexOf(toolsMarker);

        if (toolsStart == -1) {
            // 尝试其他可能的格式
            toolsMarker = "Tools used:";
            toolsStart = content.indexOf(toolsMarker);
        }
        
        // 尝试中文格式
        if (toolsStart == -1) {
            toolsMarker = "**使用的工具：**";
            toolsStart = content.indexOf(toolsMarker);
        }

        if (toolsStart == -1) {
            log.debug("parseToolCalls: no Tools used section found in content of length {}", content.length());
            // 尝试从内容中直接检测工具调用（备选方案）
            return detectToolsFromContent(content);
        }

        log.info("parseToolCalls: found Tools used section at index {}", toolsStart);

        int toolsEnd = content.length();
        int searchStart = toolsStart + toolsMarker.length();

        // 查找 Tools used 部分的结束位置（下一个双换行或章节标题）
        int nextDoubleNewline = content.indexOf("\n\n", searchStart);
        int nextSection = content.indexOf("**", searchStart);

        if (nextDoubleNewline != -1 && (nextSection == -1 || nextDoubleNewline < nextSection)) {
            toolsEnd = nextDoubleNewline;
        } else if (nextSection != -1) {
            toolsEnd = nextSection;
        }

        String toolsSection = content.substring(toolsStart, Math.min(toolsEnd, content.length()));
        log.debug("parseToolCalls: tools section length = {}", toolsSection.length());
        log.debug("parseToolCalls: tools section content: {}", toolsSection);

        String[] toolLines = toolsSection.split("\n");

        for (String line : toolLines) {
            line = line.trim();
            // 支持多种格式：- `tool_name`: description 或 - tool_name: description
            if (line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ")) {
                String toolName = null;
                String description = "";

                // 尝试格式：- `tool_name`: description
                if (line.contains("`")) {
                    int nameStart = line.indexOf("`") + 1;
                    int nameEnd = line.indexOf("`", nameStart);
                    if (nameEnd > nameStart) {
                        toolName = line.substring(nameStart, nameEnd);
                        int descStart = line.indexOf(":", nameEnd);
                        if (descStart != -1 && descStart + 1 < line.length()) {
                            description = line.substring(descStart + 1).trim();
                        }
                    }
                } else {
                    // 尝试格式：- tool_name: description
                    int colonIndex = line.indexOf(":");
                    int spaceAfterPrefix = line.indexOf(" ");
                    if (spaceAfterPrefix > 0 && colonIndex > spaceAfterPrefix) {
                        toolName = line.substring(spaceAfterPrefix + 1, colonIndex).trim();
                        if (toolName.contains(" ") && !toolName.matches("[a-z_]+")) {
                            // 如果名称包含空格且不像工具名，可能不是有效的工具名
                            toolName = null;
                        } else {
                            description = line.substring(colonIndex + 1).trim();
                        }
                    }
                }

                if (toolName != null && !toolName.isEmpty()) {
                    // 清理工具名（去除可能的标点符号）
                    toolName = toolName.replaceAll("[：:]$", "").trim();
                    log.info("parseToolCalls: found tool '{}' with description '{}'", toolName, description);
                    toolCalls.add(ChatRoom.Message.ToolCall.builder()
                            .id(UUID.randomUUID().toString())
                            .name(toolName)
                            .description(description)
                            .status("completed")
                            .timestamp(Instant.now())
                            .build());
                }
            }
        }

        log.info("parseToolCalls: total {} tools found", toolCalls.size());
        return toolCalls;
    }
    
    /**
     * 从内容中直接检测工具调用（备选方案）
     * 用于当标准格式解析失败时
     */
    private List<ChatRoom.Message.ToolCall> detectToolsFromContent(String content) {
        List<ChatRoom.Message.ToolCall> toolCalls = new ArrayList<>();
        
        // 常见工具名称列表
        String[] commonTools = {
            "memory_search", "read", "write", "edit", "exec", 
            "web_search", "web_fetch", "weather", "browser", 
            "canvas", "nodes", "cron", "message", "gateway",
            "sessions_spawn", "tts", "github", "gh", "ordercli",
            "openhue", "sonoscli", "eightctl", "gifgrep", "gemini",
            "blogwatcher", "blucli", "healthcheck", "himalaya",
            "nano-pdf", "obsidian", "openai-whisper", "skill-creator",
            "songsee", "video-frames", "wacli", "1password", "gog"
        };
        
        for (String toolName : commonTools) {
            // 检查内容中是否包含工具名称（作为独立单词）
            String pattern = "\\b" + toolName + "\\b";
            if (content.toLowerCase().matches(".*" + pattern + ".*")) {
                // 检查是否已经添加过
                boolean alreadyAdded = toolCalls.stream()
                    .anyMatch(tc -> tc.getName().equalsIgnoreCase(toolName));
                if (!alreadyAdded) {
                    log.info("detectToolsFromContent: detected tool '{}' from content", toolName);
                    toolCalls.add(ChatRoom.Message.ToolCall.builder()
                            .id(UUID.randomUUID().toString())
                            .name(toolName)
                            .description("从消息内容中检测到的工具调用")
                            .status("completed")
                            .timestamp(Instant.now())
                            .build());
                }
            }
        }
        
        if (!toolCalls.isEmpty()) {
            log.info("detectToolsFromContent: total {} tools detected", toolCalls.size());
        }
        return toolCalls;
    }

    /**
     * 从内容中解析 Tool details 并填充到工具调用中
     */
    private List<ChatRoom.Message.ToolCall> enrichToolCallsWithDetails(String content, List<ChatRoom.Message.ToolCall> toolCalls) {
        if (content == null || toolCalls == null || toolCalls.isEmpty()) {
            return toolCalls;
        }

        // 查找 **Tool details:** 部分
        String detailsMarker = "**Tool details:**";
        int detailsStart = content.indexOf(detailsMarker);

        if (detailsStart == -1) {
            // 尝试其他可能的格式
            detailsMarker = "Tool details:";
            detailsStart = content.indexOf(detailsMarker);
        }
        
        // 尝试中文格式
        if (detailsStart == -1) {
            detailsMarker = "**工具详情：**";
            detailsStart = content.indexOf(detailsMarker);
        }
        
        // 尝试从 Tools used 部分之后查找工具详情
        if (detailsStart == -1) {
            // 如果没有明确的 Tool details 标记，尝试从 Tools used 部分之后解析
            String toolsMarker = "**Tools used:**";
            int toolsStart = content.indexOf(toolsMarker);
            if (toolsStart == -1) {
                toolsMarker = "Tools used:";
                toolsStart = content.indexOf(toolsMarker);
            }
            if (toolsStart != -1) {
                // 从 Tools used 之后开始查找工具详情
                detailsStart = toolsStart;
                detailsMarker = toolsMarker;
            }
        }

        if (detailsStart == -1) {
            log.debug("enrichToolCallsWithDetails: no Tool details section found");
            return toolCalls;
        }

        log.info("enrichToolCallsWithDetails: found Tool details section at index {}", detailsStart);

        // 提取 Tool details 部分（到下一个 ** 章节或文件结束）
        int detailsContentStart = detailsStart + detailsMarker.length();
        int nextSection = content.indexOf("**", detailsContentStart);
        int detailsEnd = (nextSection != -1) ? nextSection : content.length();
        String detailsSection = content.substring(detailsContentStart, detailsEnd).trim();
        
        log.debug("enrichToolCallsWithDetails: details section length = {}", detailsSection.length());

        // 为每个工具调用查找对应的详细输出
        for (ChatRoom.Message.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.getName();
            if (toolName == null || toolName.isEmpty()) continue;

            // 查找工具名开头的行（支持多种格式）
            String[] possibleHeaders = {
                "- `" + toolName + "`:",
                "- " + toolName + ":",
                "• `" + toolName + "`:",
                "• " + toolName + ":",
                "`" + toolName + "`",
                toolName + ":"
            };
            
            int toolHeaderIndex = -1;
            String matchedHeader = null;
            
            for (String header : possibleHeaders) {
                toolHeaderIndex = detailsSection.indexOf(header);
                if (toolHeaderIndex != -1) {
                    matchedHeader = header;
                    break;
                }
            }

            if (toolHeaderIndex == -1) continue;

            // 找到工具内容开始的位置（工具名之后）
            int contentStart = toolHeaderIndex + matchedHeader.length();
            // 跳过冒号和空白
            while (contentStart < detailsSection.length() &&
                   (detailsSection.charAt(contentStart) == ':' ||
                    Character.isWhitespace(detailsSection.charAt(contentStart)))) {
                contentStart++;
            }

            // 查找下一个工具的开始位置
            String remaining = detailsSection.substring(contentStart);
            String[] lines = remaining.split("\n");
            StringBuilder toolResult = new StringBuilder();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                // 检查是否是下一个工具的开始
                if (i > 0 && (line.startsWith("- `") || line.startsWith("- ") ||
                    line.startsWith("• `") || line.startsWith("• "))) {
                    // 这可能是下一个工具，停止收集
                    boolean isNextTool = false;
                    for (ChatRoom.Message.ToolCall otherTool : toolCalls) {
                        if (otherTool != toolCall && otherTool.getName() != null) {
                            String otherName = otherTool.getName();
                            if (line.contains("`" + otherName + "`") || 
                                line.startsWith("- " + otherName + ":") ||
                                line.startsWith("• " + otherName + ":")) {
                                isNextTool = true;
                                break;
                            }
                        }
                    }
                    if (isNextTool) break;
                }
                if (i > 0) toolResult.append("\n");
                toolResult.append(line);
            }

            String result = toolResult.toString().trim();
            if (!result.isEmpty()) {
                // 如果结果包含代码块，提取代码块内容
                if (result.contains("```")) {
                    int codeStart = result.indexOf("```");
                    int codeEnd = result.indexOf("```", codeStart + 3);
                    if (codeEnd > codeStart) {
                        // 提取代码块内容（包括语言标识）
                        String codeBlock = result.substring(codeStart, codeEnd + 3);
                        toolCall.setResult(codeBlock);
                    } else {
                        toolCall.setResult(result);
                    }
                } else {
                    toolCall.setResult(result);
                }
                log.info("enrichToolCallsWithDetails: set result for tool '{}' (length={})",
                        toolName, result.length());
            }
        }

        return toolCalls;
    }

    /**
     * 发送排队状态消息
     */
    private void sendQueueStatusMessage(String roomId, String taskId, String sourceMessageId, int position) {
        String statusText = position == 0
                ? "🤖 OpenClaw 任务已加入队列，正在准备处理..."
                : String.format("🤖 OpenClaw 任务已加入队列，当前排第 %d 位...", position + 1);

        // senderAvatar 为 null，让前端显示默认机器人头像
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(openClawPluginService.getBotUsername())
                .senderName(openClawPluginService.getBotUsername())
                .content(statusText)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .replyToMessageId(sourceMessageId)
                .build();

        chatRoomService.addMessage(roomId, message);
        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("message")
                .message(message)
                .build());
    }

    /**
     * 发送任务失败消息
     */
    private void sendTaskFailedMessage(String roomId, OpenClawTask task, String error) {
        // senderAvatar 为 null，让前端显示默认机器人头像
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(openClawPluginService.getBotUsername())
                .senderName(openClawPluginService.getBotUsername())
                .content("❌ 任务执行失败: " + (error != null ? error : "未知错误"))
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .build();

        chatRoomService.addMessage(roomId, message);
        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("message")
                .message(message)
                .build());
    }

    private List<Map<String, Object>> convertToContext(OocSession session) {
        List<Map<String, Object>> context = new ArrayList<>();
        if (session.getSummary() != null && !session.getSummary().isEmpty()) {
            Map<String, Object> summaryMsg = new HashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "【历史会话摘要】" + session.getSummary());
            context.add(summaryMsg);
        }
        for (OocSession.SessionMessage msg : session.getMessages()) {
            Map<String, Object> ctxMsg = new HashMap<>();
            ctxMsg.put("role", msg.isFromOpenClaw() ? "assistant" : "user");
            ctxMsg.put("content", msg.getSenderName() + ": " + msg.getContent());
            ctxMsg.put("timestamp", msg.getTimestamp());
            context.add(ctxMsg);
        }
        return context;
    }

    private void handleOpenClawResponse(String roomId, OpenClawPluginService.OpenClawResponse response) {
        String content = response.content();
        List<ChatRoom.Message.ToolCall> toolCalls = new ArrayList<>();

        // 解析 **Tools used:** 部分来构建工具调用列表
        if (content.contains("**Tools used:**")) {
            int toolsStart = content.indexOf("**Tools used:**");
            int toolsEnd = content.length();

            // 找到 Tools used 部分的结束位置（下一个空行或内容结束）
            int searchStart = toolsStart + "**Tools used:**".length();
            int nextDoubleNewline = content.indexOf("\n\n", searchStart);

            if (nextDoubleNewline != -1) {
                toolsEnd = nextDoubleNewline;
            }

            String toolsSection = content.substring(toolsStart, Math.min(toolsEnd, content.length()));

            // 解析每个工具调用
            String[] toolLines = toolsSection.split("\n");
            for (String line : toolLines) {
                line = line.trim();
                if (line.startsWith("- `") && line.contains("`")) {
                    int nameStart = line.indexOf("`") + 1;
                    int nameEnd = line.indexOf("`", nameStart);
                    if (nameEnd > nameStart) {
                        String toolName = line.substring(nameStart, nameEnd);
                        String description = "";
                        int descStart = line.indexOf(":", nameEnd);
                        if (descStart != -1 && descStart + 1 < line.length()) {
                            description = line.substring(descStart + 1).trim();
                        }

                        toolCalls.add(ChatRoom.Message.ToolCall.builder()
                                .id(UUID.randomUUID().toString())
                                .name(toolName)
                                .description(description)
                                .status("completed")
                                .timestamp(Instant.now())
                                .build());
                    }
                }
            }
        }

        // 保存 OpenClaw 回复到 OOC 会话（保存完整内容）
        oocSessionService.addMessage(roomId, OocSession.SessionMessage.builder()
                .id(UUID.randomUUID().toString())
                .senderId(openClawPluginService.getBotUsername())
                .senderName(openClawPluginService.getBotUsername())
                .content(content)
                .timestamp(Instant.now())
                .fromOpenClaw(true)
                .build());

        // 保存到聊天室 - 保留完整的 OpenClaw 响应内容
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(openClawPluginService.getBotUsername())
                .senderName(openClawPluginService.getBotUsername())
                .content(content)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .toolCalls(toolCalls)
                .isToolCall(!toolCalls.isEmpty())
                .build();

        chatRoomService.addMessage(roomId, message);

        // 广播 OpenClaw 回复
        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("message")
                .message(message)
                .build());

        // 发送 over 消息（旧代码路径，replyToMessageId 为 null）
        sendOverMessage(roomId, null);
    }

    private void handleTyping(WebSocketSession session, WebSocketMessage payload) {
        WebSocketUserInfo userInfo = userInfoMap.get(session);
        if (userInfo == null) return;

        broadcastToRoom(userInfo.getRoomId(), WebSocketMessage.builder()
                .type("typing")
                .userId(userInfo.getUserId())
                .userName(userInfo.getUserName())
                .build(), session);
    }

    private void handleLeave(WebSocketSession session, WebSocketMessage payload) {
        WebSocketUserInfo userInfo = userInfoMap.remove(session);
        if (userInfo == null) return;

        // 从广播服务移除（唯一会话管理源）
        broadcastService.removeRoomSession(userInfo.getRoomId(), session);

        broadcastToRoom(userInfo.getRoomId(), WebSocketMessage.builder()
                .type("user_left")
                .userId(userInfo.getUserId())
                .userName(userInfo.getUserName())
                .build());
    }

    private void handlePing(WebSocketSession session, WebSocketMessage payload) {
        // 心跳检测，回复 pong
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            }
        } catch (IOException e) {
            log.debug("Failed to send pong to session {}", session.getId());
        }
    }

    public void broadcastToRoom(String roomId, WebSocketMessage message, WebSocketSession... exclude) {
        // 统一使用广播服务发送消息
        broadcastService.broadcastToRoom(roomId, message, exclude);
    }

    /**
     * 发送系统消息到指定房间（用于测试）
     */
    public void sendSystemMessage(String roomId, String content) {
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId("system")
                .senderName("System")
                .content(content)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(false)
                .build();

        chatRoomService.addMessage(roomId, message);

        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("message")
                .message(message)
                .build());
    }

    /**
     * 发送 over 消息，标记 OpenClaw 回复完成
     */
    private void sendOverMessage(String roomId, String replyToMessageId) {
        ChatRoom.Message overMsg = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(openClawPluginService.getBotUsername())
                .senderName(openClawPluginService.getBotUsername())
                .content("over")
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .replyToMessageId(replyToMessageId)
                .build();

        chatRoomService.addMessage(roomId, overMsg);

        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("message")
                .message(overMsg)
                .build());

        log.info("Sent over message to room {} with replyToMessageId: {}", roomId, replyToMessageId);
    }

    /**
     * 更新用户的头像信息（当用户在设置页面更新头像时调用）
     */
    public void updateUserAvatar(String userId, String newAvatarUrl) {
        // 更新缓存中的头像
        if (newAvatarUrl != null && !newAvatarUrl.isEmpty()) {
            avatarCacheService.putAvatarInCache(userId, newAvatarUrl);
        } else {
            avatarCacheService.removeAvatarFromCache(userId);
        }
        
        Set<WebSocketSession> sessions = userSessions.get(userId);
        String roomId = null;
        
        if (sessions != null && !sessions.isEmpty()) {
            int updatedCount = 0;
            for (WebSocketSession session : sessions) {
                WebSocketUserInfo userInfo = userInfoMap.get(session);
                if (userInfo != null) {
                    userInfo.setAvatar(newAvatarUrl);
                    roomId = userInfo.getRoomId();  // 记录用户所在的房间
                    updatedCount++;
                }
            }
            log.info("Updated avatar for user {} in {} WebSocket session(s)", userId, updatedCount);
            
            // 广播头像更新事件给房间内的其他用户
            if (roomId != null) {
                broadcastToRoom(roomId, WebSocketMessage.builder()
                        .type("user_avatar_updated")
                        .userId(userId)
                        .content(newAvatarUrl)
                        .build());
                log.info("Broadcasted avatar update for user {} to room {}", userId, roomId);
            }
        } else {
            log.debug("User {} is not online, avatar update will apply on next connection", userId);
        }
    }

    /**
     * 发送通知到指定用户
     */
    public void sendNotification(String userId, NotificationMessage notification) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("User {} is not online, notification will not be sent", userId);
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(notification);
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    try {
                        s.sendMessage(new TextMessage(payload));
                        log.debug("Sent notification to user {}", userId);
                    } catch (IOException e) {
                        log.error("Failed to send notification to user {}", userId, e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to serialize notification", e);
        }
    }

    /**
     * 发送提及通知
     */
    public void sendMentionNotification(String userId, String roomId, String roomName, String mentionerName, String messageContent) {
        sendNotification(userId, NotificationMessage.builder()
                .type("mention_notification")
                .roomId(roomId)
                .roomName(roomName)
                .mentionerName(mentionerName)
                .content(messageContent.substring(0, Math.min(200, messageContent.length())))
                .timestamp(Instant.now())
                .build());
    }

    @lombok.Data
    @lombok.Builder
    public static class NotificationMessage {
        private String type;
        private String roomId;
        private String roomName;
        private String mentionerName;
        private String content;
        private Instant timestamp;
    }

    @lombok.Data
    @lombok.Builder
    public static class WebSocketUserInfo {
        private String userId;
        private String userName;
        private String roomId;
        private String avatar;
    }
}
