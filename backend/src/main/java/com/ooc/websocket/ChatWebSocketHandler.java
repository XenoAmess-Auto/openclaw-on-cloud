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
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatRoomService chatRoomService;
    private final OocSessionService oocSessionService;
    private final OpenClawPluginService openClawPluginService;
    private final UserService userService;
    private final MentionService mentionService;
    private final ObjectMapper objectMapper;

    // roomId -> Set<WebSocketSession>
    private final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    // session -> userInfo
    private final Map<WebSocketSession, WebSocketUserInfo> userInfoMap = new ConcurrentHashMap<>();

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
        String userId = payload.getUserId();
        String userName = payload.getUserName();

        // Get user's nickname from database
        String nickname = userName;
        try {
            User user = userService.getUserByUsername(userName);
            if (user.getNickname() != null && !user.getNickname().isEmpty()) {
                nickname = user.getNickname();
            }
        } catch (Exception e) {
            log.warn("Failed to get user nickname for {}", userName);
        }

        WebSocketUserInfo userInfo = WebSocketUserInfo.builder()
                .userId(userId)
                .userName(nickname)
                .roomId(roomId)
                .build();

        userInfoMap.put(session, userInfo);
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);

        // 发送历史消息（只发送最新的10条）
        chatRoomService.getChatRoom(roomId).ifPresent(room -> {
            try {
                List<ChatRoom.Message> allMessages = room.getMessages();
                List<ChatRoom.Message> recentMessages = allMessages;
                
                // 只取最近10条消息
                if (allMessages != null && allMessages.size() > 10) {
                    recentMessages = allMessages.subList(allMessages.size() - 10, allMessages.size());
                }
                
                WebSocketMessage historyMsg = WebSocketMessage.builder()
                        .type("history")
                        .messages(recentMessages)
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
                .content(content != null ? content : "")
                .timestamp(Instant.now())
                .openclawMentioned(mentionedOpenClaw)
                .fromOpenClaw(false)
                .mentions(mentionResult.getMentions())
                .mentionAll(mentionResult.isMentionAll())
                .mentionHere(mentionResult.isMentionHere())
                .attachments(messageAttachments)
                .build();

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

        // 创建流式消息
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
                                    task.getUserInfo().getUserName());
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
                                task.getUserInfo().getUserName())
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

            log.info("Tool call started for task {}: id={}, name={}", task.getTaskId(), toolId, toolName);

            // 创建工具调用记录
            ChatRoom.Message.ToolCall toolCall = ChatRoom.Message.ToolCall.builder()
                    .id(toolId)
                    .name(toolName)
                    .description(toolInput)
                    .status("running")
                    .timestamp(Instant.now())
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
        // 更新消息为错误状态
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

        // 创建最终消息
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

        if (content == null || !content.contains("**Tools used:**")) {
            return toolCalls;
        }

        if (content.contains("**Tools used:**")) {
            int toolsStart = content.indexOf("**Tools used:**");
            int toolsEnd = content.length();

            int searchStart = toolsStart + "**Tools used:**".length();
            int nextDoubleNewline = content.indexOf("\n\n", searchStart);

            if (nextDoubleNewline != -1) {
                toolsEnd = nextDoubleNewline;
            }

            String toolsSection = content.substring(toolsStart, Math.min(toolsEnd, content.length()));
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

    @lombok.Data
    @lombok.Builder
    public static class WebSocketUserInfo {
        private String userId;
        private String userName;
        private String roomId;
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
