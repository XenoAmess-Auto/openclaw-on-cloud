package com.ooc.controller;

import com.ooc.dto.ChatRoomCreateRequest;
import com.ooc.dto.ChatRoomDto;
import com.ooc.dto.MemberDto;
import com.ooc.dto.SendMessageRequest;
import com.ooc.entity.ChatRoom;
import com.ooc.entity.User;
import com.ooc.service.ChatRoomService;
import com.ooc.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
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
            @RequestParam(defaultValue = "20") int size) {
        ChatRoom room = chatRoomService.getChatRoom(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));

        List<ChatRoom.Message> messages = room.getMessages();
        if (messages == null) {
            return ResponseEntity.ok(List.of());
        }

        // 分页处理（简单的内存分页）
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
                .openclawMentioned(request.getContent() != null && request.getContent().contains("@openclaw"))
                .fromOpenClaw(false)
                .isSystem(false)
                .isToolCall(false)
                .isStreaming(false)
                .build();

        ChatRoom updatedRoom = chatRoomService.addMessage(roomId, message);
        return ResponseEntity.ok(message);
    }

    private String getUserIdFromAuth(Authentication authentication) {
        return authentication.getName();
    }
}
