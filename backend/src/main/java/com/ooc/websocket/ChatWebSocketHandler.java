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

        // 发送历史消息
        chatRoomService.getChatRoom(roomId).ifPresent(room -> {
            try {
                WebSocketMessage historyMsg = WebSocketMessage.builder()
                        .type("history")
                        .messages(room.getMessages())
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
                log.info("Processing attachment: type={}, mimeType={}, contentLength={}",
                        typeUpper, mimeType, att.getContent() != null ? att.getContent().length() : 0);
                messageAttachments.add(ChatRoom.Message.Attachment.builder()
                        .id(UUID.randomUUID().toString())
                        .type(typeUpper)
                        .contentType(mimeType)
                        .name("image.png")
                        .url("data:" + mimeType + ";base64," + att.getContent())
                        .size(att.getContent() != null ? att.getContent().length() * 3 / 4 : 0)
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
     * 执行 OpenClaw 任务
     */
    private void executeTask(OpenClawTask task) {
        String roomId = task.getRoomId();
        log.info("Executing OpenClaw task {} for room {}", task.getTaskId(), roomId);

        task.setStatus(OpenClawTask.TaskStatus.PROCESSING);

        // 发送开始处理消息
        sendTaskStartedMessage(roomId, task);

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
                        .flatMap(newSession -> {
                            chatRoomService.updateOpenClawSession(roomId, newSession.sessionId());
                            log.info("OpenClaw session created: {}", newSession.sessionId());
                            return openClawPluginService.sendMessage(
                                    newSession.sessionId(), task.getContent(), task.getAttachments(),
                                    task.getUserInfo().getUserId(), task.getUserInfo().getUserName());
                        })
                        .subscribe(
                                response -> {
                                    log.info("OpenClaw response received for task {}: {}",
                                            task.getTaskId(),
                                            response.content().substring(0, Math.min(50, response.content().length())));
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    handleOpenClawResponse(roomId, response);
                                    onTaskComplete(roomId);
                                },
                                error -> {
                                    log.error("OpenClaw error in task {} create flow", task.getTaskId(), error);
                                    task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                    sendTaskFailedMessage(roomId, task, error.getMessage());
                                    onTaskComplete(roomId);
                                }
                        );
            } else {
                log.info("Using existing OpenClaw session: {}", finalSessionId);
                openClawPluginService.sendMessage(finalSessionId, task.getContent(), task.getAttachments(),
                                task.getUserInfo().getUserId(), task.getUserInfo().getUserName())
                        .subscribe(
                                response -> {
                                    log.info("OpenClaw response received for task {}: {}",
                                            task.getTaskId(),
                                            response.content().substring(0, Math.min(50, response.content().length())));
                                    task.setStatus(OpenClawTask.TaskStatus.COMPLETED);
                                    handleOpenClawResponse(roomId, response);
                                    onTaskComplete(roomId);
                                },
                                error -> {
                                    log.error("OpenClaw error in task {} send flow", task.getTaskId(), error);
                                    task.setStatus(OpenClawTask.TaskStatus.FAILED);
                                    sendTaskFailedMessage(roomId, task, error.getMessage());
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
     * 发送任务开始处理消息
     */
    private void sendTaskStartedMessage(String roomId, OpenClawTask task) {
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId("openclaw")
                .senderName("OpenClaw")
                .content("🤖 OpenClaw 正在处理任务...")
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

        // 解析 **Tools used:** 部分
        if (content.contains("**Tools used:**")) {
            int toolsStart = content.indexOf("**Tools used:**");
            int toolsEnd = content.length();

            // 找到 Tools used 部分的结束位置（下一个空行或内容结束）
            String[] lines = content.substring(toolsStart).split("\n", -1);
            int lineCount = 0;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                // 如果遇到空行，说明 Tools used 部分结束
                if (line.trim().isEmpty()) {
                    // 继续跳过所有连续的空行
                    int j = i + 1;
                    while (j < lines.length && lines[j].trim().isEmpty()) {
                        j++;
                    }
                    // 计算实际字符位置
                    toolsEnd = toolsStart;
                    for (int k = 0; k < j; k++) {
                        toolsEnd += lines[k].length() + 1; // +1 for \n
                    }
                    break;
                }
                // 如果行不以 - 开头且不是工具调用行，说明 Tools used 部分结束
                if (!line.trim().startsWith("- ") && !line.trim().isEmpty()) {
                    int j = i;
                    while (j < lines.length && lines[j].trim().isEmpty()) {
                        j++;
                    }
                    toolsEnd = toolsStart;
                    for (int k = 0; k < j; k++) {
                        toolsEnd += lines[k].length() + 1;
                    }
                    break;
                }
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

            // 移除 Tools used 部分，只保留实际回复内容
            String beforeTools = content.substring(0, toolsStart).trim();
            String afterTools = toolsEnd < content.length() ? content.substring(toolsEnd).trim() : "";
            content = beforeTools + (beforeTools.isEmpty() || afterTools.isEmpty() ? "" : "\n\n") + afterTools;
        }

        // 解析代码块作为工具结果
        if (content.contains("```") && !toolCalls.isEmpty()) {
            int codeStart = content.indexOf("```");
            int codeEnd = content.indexOf("```", codeStart + 3);
            if (codeEnd != -1) {
                String codeBlock = content.substring(codeStart, codeEnd + 3);
                // 将第一个代码块关联到第一个工具
                if (!toolCalls.isEmpty()) {
                    ChatRoom.Message.ToolCall firstTool = toolCalls.get(0);
                    firstTool.setResult(codeBlock);
                }
            }
        }

        // 保存 OpenClaw 回复到 OOC 会话
        oocSessionService.addMessage(roomId, OocSession.SessionMessage.builder()
                .id(UUID.randomUUID().toString())
                .senderId("openclaw")
                .senderName("OpenClaw")
                .content(content)
                .timestamp(Instant.now())
                .fromOpenClaw(true)
                .build());

        // 保存到聊天室
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

    private void broadcastToRoom(String roomId, WebSocketMessage message, WebSocketSession... exclude) {
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
    }

    // 附件数据传输对象
    @lombok.Data
    public static class Attachment {
        private String type;      // 类型，如 "image"
        private String mimeType;  // MIME 类型，如 "image/png"
        private String content;   // Base64 编码的内容（不含 data URL 前缀）
    }
}
