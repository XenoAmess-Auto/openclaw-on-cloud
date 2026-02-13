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
                messageAttachments.add(ChatRoom.Message.Attachment.builder()
                        .id(UUID.randomUUID().toString())
                        .type(att.getType())
                        .contentType(att.getMimeType())
                        .name("image.png")
                        .url("data:" + att.getMimeType() + ";base64," + att.getContent())
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
        log.info("Triggering OpenClaw for room: {}, content: {}, attachments: {}", 
                roomId, 
                content != null ? content.substring(0, Math.min(50, content.length())) : "", 
                attachments != null ? attachments.size() : 0);

        // 异步处理 OpenClaw 调用
        chatRoomService.getChatRoom(roomId).ifPresentOrElse(room -> {
            String openClawSessionId = room.getOpenClawSessions().stream()
                    .filter(ChatRoom.OpenClawSession::isActive)
                    .findFirst()
                    .map(ChatRoom.OpenClawSession::getSessionId)
                    .orElse(null);

            // 检查会话是否存活
            if (openClawSessionId != null && !openClawPluginService.isSessionAlive(openClawSessionId)) {
                // 会话已死，需要恢复
                log.info("OpenClaw session {} is not alive, will create new", openClawSessionId);
                openClawSessionId = null;
            }

            final String finalSessionId = openClawSessionId;

            if (finalSessionId == null) {
                log.info("Creating new OpenClaw session for room: {}", roomId);
                // 需要创建新会话，先获取或创建 OOC 会话记忆
                oocSessionService.getOrCreateSession(roomId, room.getName())
                        .flatMap(oocSession -> {
                            // 如果需要总结，先总结
                            if (oocSession.getMessages().size() > 30) {
                                return oocSessionService.summarizeAndCompact(oocSession)
                                        .thenReturn(oocSession);
                            }
                            return reactor.core.publisher.Mono.just(oocSession);
                        })
                        .flatMap(oocSession -> {
                            // 创建 OpenClaw 会话，带上 OOC 上下文
                            List<Map<String, Object>> context = convertToContext(oocSession);
                            log.info("Creating OpenClaw session with {} context messages", context.size());
                            return openClawPluginService.createSession("ooc-" + roomId, context);
                        })
                        .flatMap(newSession -> {
                            // 更新房间的 OpenClaw 会话
                            chatRoomService.updateOpenClawSession(roomId, newSession.sessionId());
                            log.info("OpenClaw session created: {}", newSession.sessionId());
                            // 发送消息
                            return openClawPluginService.sendMessage(
                                    newSession.sessionId(), content, attachments, userInfo.getUserId(), userInfo.getUserName());
                        })
                        .subscribe(
                                response -> {
                                    log.info("OpenClaw response received: {}", response.content().substring(0, Math.min(50, response.content().length())));
                                    handleOpenClawResponse(roomId, response);
                                },
                                error -> log.error("OpenClaw error in create flow", error)
                        );
            } else {
                log.info("Using existing OpenClaw session: {}", finalSessionId);
                // 会话存活，直接发送消息
                openClawPluginService.sendMessage(finalSessionId, content, attachments, userInfo.getUserId(), userInfo.getUserName())
                        .subscribe(
                                response -> {
                                    log.info("OpenClaw response received: {}", response.content().substring(0, Math.min(50, response.content().length())));
                                    handleOpenClawResponse(roomId, response);
                                },
                                error -> log.error("OpenClaw error in send flow", error)
                        );
            }
        }, () -> log.error("Chat room not found: {}", roomId));
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
            int toolsEnd = content.indexOf("\n\n", toolsStart);
            if (toolsEnd == -1) {
                toolsEnd = content.length();
            }
            String toolsSection = content.substring(toolsStart, toolsEnd);
            
            // 解析每个工具调用
            String[] lines = toolsSection.split("\n");
            for (String line : lines) {
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
            content = content.substring(0, toolsStart).trim() + content.substring(toolsEnd).trim();
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
