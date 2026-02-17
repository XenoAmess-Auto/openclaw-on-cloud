package com.ooc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ooc.entity.ChatRoom;
import com.ooc.entity.OocSession;
import com.ooc.entity.User;
import com.ooc.openclaw.OpenClawPluginService;
import com.ooc.openclaw.OpenClawSessionState;
import com.ooc.service.ChatRoomService;
import com.ooc.service.MentionService;
import com.ooc.service.OocSessionService;
import com.ooc.service.UserService;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatRoomService chatRoomService;
    private final OocSessionService oocSessionService;
    private final OpenClawPluginService openClawPluginService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private MentionService mentionService;

    public ChatWebSocketHandler(ChatRoomService chatRoomService,
                                OocSessionService oocSessionService,
                                OpenClawPluginService openClawPluginService,
                                UserService userService,
                                ObjectMapper objectMapper) {
        this.chatRoomService = chatRoomService;
        this.oocSessionService = oocSessionService;
        this.openClawPluginService = openClawPluginService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    // roomId -> Set<WebSocketSession>
    private final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    // session -> userInfo
    private final Map<WebSocketSession, WebSocketUserInfo> userInfoMap = new ConcurrentHashMap<>();

    // userId -> Set<WebSocketSession> (for notification)
    private final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    // ========== 队列系统 ==========
    // roomId -> 任务队列
    private final Map<String, ConcurrentLinkedQueue<OpenClawTask>> roomTaskQueues = new ConcurrentHashMap<>();
    // roomId -> 是否正在执行任务
    private final Map<String, AtomicBoolean> roomProcessingFlags = new ConcurrentHashMap<>();

    /**
     * OpenClaw 任务
     */
    @lombok.Data
    @lombok.Builder
    private static class OpenClawTask {
        private String taskId;
        private String roomId;
        private String content;
        private List<Attachment> attachments;
        private WebSocketUserInfo userInfo;
        private Instant createdAt;
        private volatile TaskStatus status; // PENDING, PROCESSING, COMPLETED, FAILED

        public enum TaskStatus {
            PENDING, PROCESSING, COMPLETED, FAILED
        }
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
            Set<WebSocketSession> sessions = roomSessions.get(userInfo.getRoomId());
            if (sessions != null) {
                sessions.remove(session);
            }
            // Remove from userSessions
            Set<WebSocketSession> userSess = userSessions.get(userInfo.getUserId());
            if (userSess != null) {
                userSess.remove(session);
                if (userSess.isEmpty()) {
                    userSessions.remove(userInfo.getUserId());
                }
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        WebSocketMessage payload = objectMapper.readValue(message.getPayload(), WebSocketMessage.class);

        switch (payload.getType()) {
            case "join" -> handleJoin(session, payload);
            case "message" -> handleMessage(session, payload);
            case "typing" -> handleTyping(session, payload);
            case "leave" -> handleLeave(session, payload);
        }
    }

    private void handleJoin(WebSocketSession session, WebSocketMessage payload) {
        String roomId = payload.getRoomId();
        String userName = payload.getUserName();
        String clientUserId = payload.getUserId(); // 前端传来的 userId

        // Get user's info from database - 强制使用数据库中的 userId 确保一致性
        String userId;
        String nickname = userName;
        String avatar = null;
        User user = null;

        try {
            user = userService.getUserByUsername(userName);
            userId = user.getId();  // 使用数据库中的 ID，而不是前端传来的

            // 验证前端传来的 userId 与数据库是否一致（用于检测不一致情况）
            if (clientUserId != null && !clientUserId.equals(userId)) {
                log.warn("User {} userId mismatch: client sent {}, but database has {}. Using database value.",
                        userName, clientUserId, userId);
            }

            if (user.getNickname() != null && !user.getNickname().isEmpty()) {
                nickname = user.getNickname();
            }
            avatar = user.getAvatar();
            log.info("User {} joined, userId: {}, avatar: {}", userName, userId, avatar != null ? avatar : "(null)");
        } catch (Exception e) {
            // 数据库查询失败 - 这是一个严重问题，不应该使用 fallback
            log.error("Failed to get user info for {} from database. Client sent userId: {}", userName, clientUserId, e);

            // 尝试通过前端传来的 userId 查询（可能是旧数据存储的是 userId 而不是 username）
            if (clientUserId != null && !clientUserId.isEmpty()) {
                try {
                    user = userService.getUserById(clientUserId);
                    userId = user.getId();
                    if (user.getNickname() != null && !user.getNickname().isEmpty()) {
                        nickname = user.getNickname();
                    }
                    avatar = user.getAvatar();
                    log.info("User {} found by clientUserId: {}, using database userId: {}",
                            userName, clientUserId, userId);
                } catch (Exception e2) {
                    log.error("Failed to get user by clientUserId {} for {}", clientUserId, userName, e2);
                    // 如果两种查询都失败，使用前端传来的 userId 作为最后的 fallback，但记录警告
                    userId = clientUserId;
                    log.warn("Using client-provided userId {} for {} as fallback - this may cause user identity issues",
                            userId, userName);
                }
            } else {
                // 没有前端 userId，生成一个临时 ID（这会导致该会话无法正确识别用户）
                userId = "unknown-" + UUID.randomUUID().toString();
                log.error("No client userId provided for {} and database lookup failed. Using temporary userId: {}",
                        userName, userId);
            }
        }

        WebSocketUserInfo userInfo = WebSocketUserInfo.builder()
                .userId(userId)
                .userName(nickname)
                .roomId(roomId)
                .avatar(avatar)
                .build();

        userInfoMap.put(session, userInfo);
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);

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
                List<ChatRoom.Message> enrichedMessages = recentMessages.stream()
                        .map(msg -> {
                            if (msg.getSenderAvatar() == null || msg.getSenderAvatar().isEmpty()) {
                                try {
                                    // senderId 是 MongoDB 的 userId，不是 username
                                    User msgUser = userService.getUserById(msg.getSenderId());
                                    if (msgUser != null && msgUser.getAvatar() != null) {
                                        return msg.toBuilder().senderAvatar(msgUser.getAvatar()).build();
                                    }
                                } catch (Exception e) {
                                    log.debug("Failed to get avatar for userId: {}", msg.getSenderId());
                                }
                            }
                            return msg;
                        })
                        .toList();

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

    private void handleMessage(WebSocketSession session, WebSocketMessage payload) {
        WebSocketUserInfo userInfo = userInfoMap.get(session);
        if (userInfo == null) return;

        String roomId = userInfo.getRoomId();
        String content = payload.getContent();
        List<Attachment> attachments = payload.getAttachments();
        boolean hasAttachments = attachments != null && !attachments.isEmpty();

        // 检查是否@OpenClaw
        boolean mentionedOpenClaw = content != null && content.toLowerCase().contains("@openclaw");

        // 解析@提及
        MentionService.MentionParseResult mentionResult = mentionService.parseMentions(content != null ? content : "", roomId);

        // 获取房间成员数
        int memberCount = roomSessions.getOrDefault(roomId, Collections.emptySet()).size();

        log.info("Message received: room={}, sender={}, content={}, attachments={}, memberCount={}, mentionedOpenClaw={}, mentions={}",
                roomId, userInfo.getUserName(),
                content != null ? content.substring(0, Math.min(50, content.length())) : "",
                hasAttachments ? attachments.size() : 0,
                memberCount, mentionedOpenClaw, mentionResult.getMentions().size());

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
                String mimeType = att.getMimeType() != null ? att.getMimeType() : "image/png";
                
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
                        .name("image.png")
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
            triggerOpenClaw(roomId, content, attachments, userInfo);
        }
    }

    private boolean shouldTriggerOpenClaw(int memberCount, boolean mentionedOpenClaw) {
        // 只有 @OpenClaw 时才触发回复
        return mentionedOpenClaw;
    }

    private void triggerOpenClaw(String roomId, String content, List<Attachment> attachments, WebSocketUserInfo userInfo) {
        log.info("Adding OpenClaw task to queue for room: {}, content: {}, attachments: {}",
                roomId,
                content != null ? content.substring(0, Math.min(50, content.length())) : "",
                attachments != null ? attachments.size() : 0);

        // 创建任务
        OpenClawTask task = OpenClawTask.builder()
                .taskId(UUID.randomUUID().toString())
                .roomId(roomId)
                .content(content)
                .attachments(attachments)
                .userInfo(userInfo)
                .createdAt(Instant.now())
                .status(OpenClawTask.TaskStatus.PENDING)
                .build();

        // 获取或创建该房间的任务队列
        ConcurrentLinkedQueue<OpenClawTask> queue = roomTaskQueues.computeIfAbsent(roomId, k -> new ConcurrentLinkedQueue<>());
        AtomicBoolean isProcessing = roomProcessingFlags.computeIfAbsent(roomId, k -> new AtomicBoolean(false));

        // 将任务加入队列
        queue.offer(task);

        int queueSize = queue.size();
        log.info("Task {} added to room {} queue. Queue size: {}", task.getTaskId(), roomId, queueSize);

        // 发送排队状态消息
        sendQueueStatusMessage(roomId, task, queueSize - 1); // -1 因为当前任务已经加入队列

        // 尝试启动队列处理（如果当前没有任务在执行）
        tryProcessNextTask(roomId);
    }

    /**
     * 尝试处理队列中的下一个任务
     */
    private void tryProcessNextTask(String roomId) {
        ConcurrentLinkedQueue<OpenClawTask> queue = roomTaskQueues.get(roomId);
        AtomicBoolean isProcessing = roomProcessingFlags.get(roomId);

        if (queue == null || isProcessing == null) {
            return;
        }

        // 使用 CAS 操作确保只有一个线程能开始处理
        if (!isProcessing.compareAndSet(false, true)) {
            log.debug("Room {} is already processing a task, skipping", roomId);
            return;
        }

        OpenClawTask task = queue.poll();
        if (task == null) {
            // 队列为空，重置处理标志
            isProcessing.set(false);
            log.debug("Room {} queue is empty, resetting processing flag", roomId);
            return;
        }

        // 执行任务
        executeTask(task);
    }

    /**
     * 执行 OpenClaw 任务（流式版本）
     */
    private void executeTask(OpenClawTask task) {
        String roomId = task.getRoomId();
        String taskId = task.getTaskId();
        log.info("Executing OpenClaw task {} for room {} (streaming)", taskId, roomId);

        task.setStatus(OpenClawTask.TaskStatus.PROCESSING);

        // 创建流式消息 - senderAvatar 为 null，让前端显示默认机器人头像
        String streamingMessageId = UUID.randomUUID().toString();
        AtomicReference<StringBuilder> contentBuilder = new AtomicReference<>(new StringBuilder());
        AtomicReference<ChatRoom.Message> streamingMessage = new AtomicReference<>(
            ChatRoom.Message.builder()
                .id(streamingMessageId)
                .senderId("openclaw")
                .senderName("OpenClaw")
                .content("")
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(true)
                .toolCalls(new ArrayList<>())
                .build()
        );

        // 保存初始消息到聊天室
        chatRoomService.addMessage(roomId, streamingMessage.get());

        // 广播流式消息开始
        broadcastToRoom(roomId, WebSocketMessage.builder()
                .type("stream_start")
                .roomId(roomId)
                .message(streamingMessage.get())
                .build());

        chatRoomService.getChatRoom(roomId).ifPresentOrElse(room -> {
            String openClawSessionId = room.getOpenClawSessions().stream()
                    .filter(ChatRoom.OpenClawSession::isActive)
                    .findFirst()
                    .map(ChatRoom.OpenClawSession::getSessionId)
                    .orElse(null);

            // 检查会话是否存活
            if (openClawSessionId != null && !openClawPluginService.isSessionAlive(openClawSessionId)) {
                log.info("OpenClaw session {} is not alive, will create new", openClawSessionId);
                openClawSessionId = null;
            }

            final String finalSessionId = openClawSessionId;

            if (finalSessionId == null) {
                // 创建新会话并发送流式消息
                log.info("Creating new OpenClaw session for room: {}", roomId);
                oocSessionService.getOrCreateSession(roomId, room.getName())
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
                                event -> handleStreamEvent(roomId, streamingMessageId, contentBuilder, streamingMessage, event, task),
                                error -> {
                                    log.error("OpenClaw streaming error in task {}", taskId, error);
                                    task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                    handleStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), error.getMessage(), task);
                                    onTaskComplete(roomId);
                                },
                                () -> {
                                    log.info("OpenClaw streaming completed for task {}", taskId);
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    finalizeStreamMessage(roomId, streamingMessageId, contentBuilder.get().toString(), task, streamingMessage.get().getToolCalls());
                                    onTaskComplete(roomId);
                                }
                        );
            } else {
                // 使用现有会话发送流式消息
                log.info("Using existing OpenClaw session: {}", finalSessionId);
                openClawPluginService.sendMessageStream(
                                finalSessionId,
                                task.getContent(),
                                task.getAttachments(),
                                task.getUserInfo().getUserId(),
                                task.getUserInfo().getUserName(),
                                room.getName())
                        .subscribe(
                                event -> handleStreamEvent(roomId, streamingMessageId, contentBuilder, streamingMessage, event, task),
                                error -> {
                                    log.error("OpenClaw streaming error in task {}", taskId, error);
                                    task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                    handleStreamError(roomId, streamingMessageId, contentBuilder.get().toString(), error.getMessage(), task);
                                    onTaskComplete(roomId);
                                },
                                () -> {
                                    log.info("OpenClaw streaming completed for task {}", taskId);
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    finalizeStreamMessage(roomId, streamingMessageId, contentBuilder.get().toString(), task, streamingMessage.get().getToolCalls());
                                    onTaskComplete(roomId);
                                }
                        );
            }
        }, () -> {
            log.error("Chat room not found: {}", roomId);
            task.setStatus(OpenClawTask.TaskStatus.FAILED);
            onTaskComplete(roomId);
        });
    }

    /**
     * 处理流式事件
     */
    private void handleStreamEvent(String roomId, String messageId,
            AtomicReference<StringBuilder> contentBuilder,
            AtomicReference<ChatRoom.Message> streamingMessage,
            OpenClawPluginService.StreamEvent event,
            OpenClawTask task) {

        log.info("Stream event for task {}: type={}, contentLength={}, toolName={}, totalBuilderLength={}",
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

                log.info("Appending content for task {}: newChars={}, totalChars={}",
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
                            .senderId("openclaw")
                            .senderName("OpenClaw")
                            .toolCalls(List.of(toolCall))
                            .isToolCall(true)
                            .fromOpenClaw(true)
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
                                .senderId("openclaw")
                                .senderName("OpenClaw")
                                .toolCalls(currentToolCalls)
                                .isToolCall(true)
                                .fromOpenClaw(true)
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
    private void handleStreamError(String roomId, String messageId, String partialContent, String error, OpenClawTask task) {
        // 更新消息为错误状态 - senderAvatar 为 null，让前端显示默认机器人头像
        ChatRoom.Message errorMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId("openclaw")
                .senderName("OpenClaw")
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
    private void finalizeStreamMessage(String roomId, String messageId, String finalContent, OpenClawTask task, List<ChatRoom.Message.ToolCall> streamingToolCalls) {
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

        log.info("finalizeStreamMessage: parsed {} tool calls for task {}", toolCalls.size(), task.getTaskId());
        if (!toolCalls.isEmpty()) {
            toolCalls.forEach(tc -> log.info("  Tool: {} - result length={}", tc.getName(),
                    tc.getResult() != null ? tc.getResult().length() : 0));
        }

        // 创建最终消息 - senderAvatar 为 null，让前端显示默认机器人头像
        ChatRoom.Message finalMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId("openclaw")
                .senderName("OpenClaw")
                .content(finalContent)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(false)
                .isToolCall(!toolCalls.isEmpty())
                .toolCalls(toolCalls)
                .build();

        // 保存到 OOC 会话
        oocSessionService.addMessage(roomId, OocSession.SessionMessage.builder()
                .id(UUID.randomUUID().toString())
                .senderId("openclaw")
                .senderName("OpenClaw")
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
     * 任务完成后的回调
     */
    private void onTaskComplete(String roomId) {
        log.info("Task completed for room {}, checking queue for next task", roomId);
        AtomicBoolean isProcessing = roomProcessingFlags.get(roomId);
        if (isProcessing != null) {
            isProcessing.set(false);
        }
        // 尝试处理下一个任务
        tryProcessNextTask(roomId);
    }

    /**
     * 发送排队状态消息
     */
    private void sendQueueStatusMessage(String roomId, OpenClawTask task, int position) {
        String statusText = position == 0
                ? "🤖 OpenClaw 任务已加入队列，正在准备处理..."
                : String.format("🤖 OpenClaw 任务已加入队列，当前排第 %d 位...", position + 1);

        // senderAvatar 为 null，让前端显示默认机器人头像
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId("openclaw")
                .senderName("OpenClaw")
                .content(statusText)
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

    /**
     * 发送任务失败消息
     */
    private void sendTaskFailedMessage(String roomId, OpenClawTask task, String error) {
        // senderAvatar 为 null，让前端显示默认机器人头像
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId("openclaw")
                .senderName("OpenClaw")
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
                .senderId("openclaw")
                .senderName("OpenClaw")
                .content(content)
                .timestamp(Instant.now())
                .fromOpenClaw(true)
                .build());

        // 保存到聊天室 - 保留完整的 OpenClaw 响应内容
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId("openclaw")
                .senderName("OpenClaw")
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

        Set<WebSocketSession> sessions = roomSessions.get(userInfo.getRoomId());
        if (sessions != null) {
            sessions.remove(session);
        }

        broadcastToRoom(userInfo.getRoomId(), WebSocketMessage.builder()
                .type("user_left")
                .userId(userInfo.getUserId())
                .userName(userInfo.getUserName())
                .build());
    }

    public void broadcastToRoom(String roomId, WebSocketMessage message, WebSocketSession... exclude) {
        Set<WebSocketSession> excludeSet = new HashSet<>(Arrays.asList(exclude));
        Set<WebSocketSession> sessions = roomSessions.getOrDefault(roomId, Collections.emptySet());

        try {
            String payload = objectMapper.writeValueAsString(message);
            for (WebSocketSession s : sessions) {
                if (!excludeSet.contains(s) && s.isOpen()) {
                    try {
                        s.sendMessage(new TextMessage(payload));
                    } catch (IOException e) {
                        log.error("Failed to send message", e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to serialize message", e);
        }
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
     * 更新用户的头像信息（当用户在设置页面更新头像时调用）
     */
    public void updateUserAvatar(String userId, String newAvatarUrl) {
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

    @lombok.Data
    @lombok.Builder
    public static class WebSocketMessage {
        private String type;
        private String roomId;
        private String userId;
        private String userName;
        private String content;
        private ChatRoom.Message message;
        private List<ChatRoom.Message> messages;
        private List<Attachment> attachments; // 附件列表
        private Boolean hasMore; // 是否还有更多历史消息
    }

    // 附件数据传输对象
    @lombok.Data
    public static class Attachment {
        private String type;      // 类型，如 "image"
        private String mimeType;  // MIME 类型，如 "image/png"
        private String content;   // Base64 编码的内容（不含 data URL 前缀）
        private String url;       // 文件 URL（如 /uploads/xxx.png），优先使用
    }
}
