package com.ooc.controller;

import com.ooc.dto.ChatRoomCreateRequest;
import com.ooc.dto.ChatRoomDto;
import com.ooc.dto.MemberDto;
import com.ooc.dto.SendMessageRequest;
import com.ooc.entity.ChatRoom;
import com.ooc.entity.OocSession;
import com.ooc.entity.User;
import com.ooc.openclaw.OpenClawPluginService;
import com.ooc.service.ChatRoomService;
import com.ooc.service.OocSessionService;
import com.ooc.service.UserService;
import com.ooc.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;
    private final UserService userService;
    private final OpenClawPluginService openClawPluginService;
    private final OocSessionService oocSessionService;
    private final ChatWebSocketHandler webSocketHandler;

    @PostMapping
    public ResponseEntity<ChatRoomDto> createChatRoom(
            @RequestBody ChatRoomCreateRequest request,
            Authentication authentication) {
        String userId = getUserIdFromAuth(authentication);
        ChatRoom room = chatRoomService.createChatRoom(request.getName(), request.getDescription(), userId);
        return ResponseEntity.ok(ChatRoomDto.fromEntity(room));
    }

    @GetMapping
    public ResponseEntity<List<ChatRoomDto>> getMyChatRooms(Authentication authentication) {
        String userId = getUserIdFromAuth(authentication);
        List<ChatRoom> rooms = chatRoomService.getUserChatRooms(userId);
        return ResponseEntity.ok(rooms.stream().map(ChatRoomDto::fromEntity).collect(Collectors.toList()));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<ChatRoomDto> getChatRoom(@PathVariable String roomId) {
        return chatRoomService.getChatRoom(roomId)
                .map(room -> ResponseEntity.ok(ChatRoomDto.fromEntity(room)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{roomId}/members")
    public ResponseEntity<List<MemberDto>> getChatRoomMembers(@PathVariable String roomId) {
        ChatRoom room = chatRoomService.getChatRoom(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));

        log.info("Getting members for room: {}, memberIds: {}, creatorId: {}", roomId, room.getMemberIds(), room.getCreatorId());

        // Ensure memberIds is not null and creator is included
        Set<String> memberIds = room.getMemberIds() != null ? new HashSet<>(room.getMemberIds()) : new HashSet<>();
        if (room.getCreatorId() != null) {
            memberIds.add(room.getCreatorId());
        }

        log.info("Processing {} memberIds for room: {}", memberIds.size(), roomId);

        List<MemberDto> members = memberIds.stream()
                .map(userId -> {
                    try {
                        // memberIds stores username, not userId
                        User user = userService.getUserByUsername(userId);
                        return MemberDto.fromEntity(user, room.getCreatorId());
                    } catch (Exception e) {
                        log.warn("Failed to get user by username: {}", userId, e);
                        return null;
                    }
                })
                .filter(member -> member != null)
                .collect(Collectors.toList());

        log.info("Returning {} members for room: {}", members.size(), roomId);
        return ResponseEntity.ok(members);
    }

    @GetMapping("/{roomId}/members/search")
    public ResponseEntity<List<MemberDto>> searchChatRoomMembers(
            @PathVariable String roomId,
            @RequestParam String q) {
        ChatRoom room = chatRoomService.getChatRoom(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));

        Set<String> memberIds = room.getMemberIds() != null ? new HashSet<>(room.getMemberIds()) : new HashSet<>();
        if (room.getCreatorId() != null) {
            memberIds.add(room.getCreatorId());
        }

        String query = q.toLowerCase();
        List<MemberDto> members = memberIds.stream()
                .map(userId -> {
                    try {
                        User user = userService.getUserByUsername(userId);
                        // 匹配用户名或昵称
                        if (user.getUsername().toLowerCase().contains(query) ||
                            (user.getNickname() != null && user.getNickname().toLowerCase().contains(query))) {
                            return MemberDto.fromEntity(user, room.getCreatorId());
                        }
                        return null;
                    } catch (Exception e) {
                        log.warn("Failed to get user by username: {}", userId, e);
                        return null;
                    }
                })
                .filter(member -> member != null)
                .collect(Collectors.toList());

        return ResponseEntity.ok(members);
    }

    @PostMapping("/{roomId}/members")
    public ResponseEntity<ChatRoomDto> addMember(
            @PathVariable String roomId,
            @RequestParam String userId,
            Authentication authentication) {
        // Check if current user is creator
        ChatRoom room = chatRoomService.getChatRoom(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));
        String currentUserId = getUserIdFromAuth(authentication);

        if (!room.getCreatorId().equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        ChatRoom updatedRoom = chatRoomService.addMember(roomId, userId);
        return ResponseEntity.ok(ChatRoomDto.fromEntity(updatedRoom));
    }

    @DeleteMapping("/{roomId}/members/{userId}")
    public ResponseEntity<ChatRoomDto> removeMember(
            @PathVariable String roomId,
            @PathVariable String userId,
            Authentication authentication) {
        // Check if current user is creator
        ChatRoom room = chatRoomService.getChatRoom(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));
        String currentUserId = getUserIdFromAuth(authentication);

        if (!room.getCreatorId().equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Cannot remove creator
        if (userId.equals(room.getCreatorId())) {
            return ResponseEntity.badRequest().build();
        }

        ChatRoom updatedRoom = chatRoomService.removeMember(roomId, userId);
        return ResponseEntity.ok(ChatRoomDto.fromEntity(updatedRoom));
    }

    @DeleteMapping("/{roomId}")
    public ResponseEntity<Void> deleteChatRoom(
            @PathVariable String roomId,
            Authentication authentication) {
        ChatRoom room = chatRoomService.getChatRoom(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));
        String currentUserId = getUserIdFromAuth(authentication);

        if (!room.getCreatorId().equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        chatRoomService.deleteChatRoom(roomId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{roomId}/messages")
    public ResponseEntity<List<ChatRoom.Message>> getChatRoomMessages(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String before) {
        ChatRoom room = chatRoomService.getChatRoom(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));

        List<ChatRoom.Message> messages = room.getMessages();
        if (messages == null || messages.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // 基于时间戳的游标分页
        if (before != null && !before.isEmpty()) {
            try {
                Instant beforeTimestamp = Instant.parse(before);
                // 找到第一个 timestamp < before 的消息索引
                int startIndex = -1;
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if (messages.get(i).getTimestamp() != null && 
                        messages.get(i).getTimestamp().isBefore(beforeTimestamp)) {
                        startIndex = i;
                        break;
                    }
                }
                
                if (startIndex < 0) {
                    return ResponseEntity.ok(List.of());
                }
                
                // 从 startIndex 往前取 size 条（更旧的消息）
                int endIndex = Math.max(0, startIndex - size + 1);
                List<ChatRoom.Message> pagedMessages = messages.subList(endIndex, startIndex + 1);
                return ResponseEntity.ok(pagedMessages);
            } catch (Exception e) {
                log.error("Failed to parse before timestamp: {}", before, e);
            }
        }

        // 默认分页（简单的内存分页，返回最新的消息）
        int start = page * size;
        int end = Math.min(start + size, messages.size());

        if (start >= messages.size()) {
            return ResponseEntity.ok(List.of());
        }

        // 返回倒序（最新消息在前）
        List<ChatRoom.Message> pagedMessages = messages.subList(
                Math.max(0, messages.size() - end),
                Math.max(0, messages.size() - start)
        );

        return ResponseEntity.ok(pagedMessages);
    }

    @PostMapping("/{roomId}/messages")
    public ResponseEntity<ChatRoom.Message> sendMessage(
            @PathVariable String roomId,
            @RequestBody SendMessageRequest request,
            Authentication authentication) {
        String userId = getUserIdFromAuth(authentication);
        User user = userService.getUserByUsername(userId);

        // 创建消息
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(userId)
                .senderName(user.getNickname() != null ? user.getNickname() : user.getUsername())
                .senderAvatar(user.getAvatar())
                .content(request.getContent())
                .timestamp(Instant.now())
                .openclawMentioned(request.getContent() != null && request.getContent().toLowerCase().contains("@openclaw"))
                .fromOpenClaw(false)
                .isSystem(false)
                .isToolCall(false)
                .isStreaming(false)
                .build();

        chatRoomService.addMessage(roomId, message);

        // 触发 OpenClaw 处理（如果消息包含 @openclaw）
        if (message.isOpenclawMentioned()) {
            log.info("@openclaw mentioned in message, triggering OpenClaw processing for room: {}", roomId);
            // 使用 CompletableFuture 异步执行，避免阻塞 HTTP 响应
            java.util.concurrent.CompletableFuture.runAsync(() -> triggerOpenClaw(roomId, message))
                    .exceptionally(ex -> {
                        log.error("OpenClaw processing failed for room: {}", roomId, ex);
                        return null;
                    });
        }

        return ResponseEntity.ok(message);
    }

    /**
     * 异步触发 OpenClaw 处理
     */
    private void triggerOpenClaw(String roomId, ChatRoom.Message message) {
        try {
            log.info("Starting async OpenClaw processing for room: {}, message: {}", roomId, message.getId());
            
            // 获取房间信息
            ChatRoom room = chatRoomService.getChatRoom(roomId)
                    .orElseThrow(() -> new RuntimeException("Chat room not found: " + roomId));
            
            String roomName = room.getName() != null ? room.getName() : "聊天室";
            String userId = message.getSenderId();
            String userName = message.getSenderName();
            String content = message.getContent();
            
            // 获取或创建 OOC 会话（同步等待）
            OocSession oocSession = oocSessionService.getOrCreateSession(roomId, roomName).block();
            if (oocSession == null) {
                log.error("Failed to get or create OOC session for room: {}", roomId);
                return;
            }
            
            // 获取或创建 OpenClaw 会话
            String openClawSessionId = room.getOpenClawSessions().stream()
                    .filter(ChatRoom.OpenClawSession::isActive)
                    .findFirst()
                    .map(ChatRoom.OpenClawSession::getSessionId)
                    .orElse(null);
            
            if (openClawSessionId != null && !openClawPluginService.isSessionAlive(openClawSessionId)) {
                log.info("OpenClaw session {} is not alive, will create new", openClawSessionId);
                openClawSessionId = null;
            }
            
            // 如果需要，创建新会话
            if (openClawSessionId == null) {
                log.info("Creating new OpenClaw session for room: {}", roomId);
                
                // 检查是否需要总结
                if (oocSession.getMessages().size() > 30) {
                    oocSessionService.summarizeAndCompact(oocSession).block();
                }
                
                // 转换上下文
                List<Map<String, Object>> context = convertToContext(oocSession);
                
                // 创建会话
                OpenClawPluginService.OpenClawSession newSession = 
                        openClawPluginService.createSession("ooc-" + roomId, context).block();
                
                if (newSession == null) {
                    log.error("Failed to create OpenClaw session for room: {}", roomId);
                    return;
                }
                
                openClawSessionId = newSession.sessionId();
                chatRoomService.updateOpenClawSession(roomId, openClawSessionId);
                log.info("Created new OpenClaw session: {}", openClawSessionId);
            }
            
            // 创建流式消息占位
            String responseMessageId = UUID.randomUUID().toString();
            StringBuilder responseBuilder = new StringBuilder();

            ChatRoom.Message streamingMsg = ChatRoom.Message.builder()
                    .id(responseMessageId)
                    .senderId("openclaw")
                    .senderName("OpenClaw")
                    .content("")
                    .timestamp(Instant.now())
                    .openclawMentioned(false)
                    .fromOpenClaw(true)
                    .isStreaming(true)
                    .toolCalls(new ArrayList<>())
                    .build();
            chatRoomService.addMessage(roomId, streamingMsg);

            // 广播 stream_start 事件，通知前端显示流式消息
            webSocketHandler.broadcastToRoom(roomId, ChatWebSocketHandler.WebSocketMessage.builder()
                    .type("stream_start")
                    .message(streamingMsg)
                    .build());
            log.info("Broadcasted stream_start for message: {} in room: {}", responseMessageId, roomId);

            // 发送流式请求并收集响应
            final String finalSessionId = openClawSessionId;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            
            openClawPluginService.sendMessageStreamWithRoomAttachments(finalSessionId, content, message.getAttachments(), userId, userName)
                    .doOnNext(event -> {
                        if ("message".equals(event.type()) && event.content() != null) {
                            responseBuilder.append(event.content());
                        }
                    })
                    .doOnError(error -> {
                        log.error("OpenClaw streaming error for room: {}", roomId, error);
                        String errorContent = responseBuilder + "\n\n[错误: " + error.getMessage() + "]";
                        saveOpenClawResponse(roomId, responseMessageId, errorContent, oocSession);
                        latch.countDown();
                    })
                    .doOnComplete(() -> {
                        String finalContent = responseBuilder.toString();
                        if (finalContent.isEmpty()) {
                            finalContent = "*(OpenClaw 无回复)*";
                        }
                        saveOpenClawResponse(roomId, responseMessageId, finalContent, oocSession);
                        latch.countDown();
                    })
                    .subscribe();
            
            // 等待流完成（最多60秒）
            try {
                if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("OpenClaw streaming timeout for room: {}", roomId);
                    String timeoutContent = responseBuilder.toString();
                    if (timeoutContent.isEmpty()) {
                        timeoutContent = "*(OpenClaw 响应超时)*";
                    } else {
                        timeoutContent += "\n\n[响应超时，部分内容可能未加载完成]";
                    }
                    saveOpenClawResponse(roomId, responseMessageId, timeoutContent, oocSession);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("OpenClaw streaming interrupted for room: {}", roomId);
            }
                    
        } catch (Exception e) {
            log.error("Failed to process OpenClaw request for room: {}", roomId, e);
        }
    }
    
    /**
     * 保存 OpenClaw 响应
     */
    private void saveOpenClawResponse(String roomId, String messageId, String content, OocSession oocSession) {
        // 解析工具调用
        List<ChatRoom.Message.ToolCall> toolCalls = parseToolCalls(content);
        
        // 保存到 OOC 会话
        oocSessionService.addMessage(roomId, OocSession.SessionMessage.builder()
                .id(UUID.randomUUID().toString())
                .senderId("openclaw")
                .senderName("OpenClaw")
                .content(content)
                .timestamp(Instant.now())
                .fromOpenClaw(true)
                .build());
        
        // 保存最终消息
        ChatRoom.Message finalMsg = ChatRoom.Message.builder()
                .id(messageId)
                .senderId("openclaw")
                .senderName("OpenClaw")
                .content(content)
                .timestamp(Instant.now())
                .openclawMentioned(false)
                .fromOpenClaw(true)
                .isStreaming(false)
                .isToolCall(!toolCalls.isEmpty())
                .toolCalls(toolCalls)
                .build();
        chatRoomService.updateMessage(roomId, finalMsg);
        
        // 广播 WebSocket stream_end 事件，通知前端更新消息
        webSocketHandler.broadcastToRoom(roomId, ChatWebSocketHandler.WebSocketMessage.builder()
                .type("stream_end")
                .message(finalMsg)
                .build());
        
        log.info("OpenClaw response saved for room: {}, content length: {}, toolCalls: {}", 
                roomId, content.length(), toolCalls.size());
    }
    
    /**
     * 将 OOC 会话转换为 OpenClaw 上下文
     */
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
    
    /**
     * 从内容中解析工具调用
     */
    private List<ChatRoom.Message.ToolCall> parseToolCalls(String content) {
        List<ChatRoom.Message.ToolCall> toolCalls = new ArrayList<>();
        
        if (content == null || !content.contains("**Tools used:**")) {
            return toolCalls;
        }
        
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
        
        return toolCalls;
    }

    private String getUserIdFromAuth(Authentication authentication) {
        return authentication.getName();
    }
}
