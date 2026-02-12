package com.ooc.controller;

import com.ooc.dto.ChatRoomCreateRequest;
import com.ooc.dto.ChatRoomDto;
import com.ooc.dto.MemberDto;
import com.ooc.entity.ChatRoom;
import com.ooc.entity.User;
import com.ooc.service.ChatRoomService;
import com.ooc.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        
        List<MemberDto> members = room.getMemberIds().stream()
                .map(userId -> {
                    try {
                        User user = userService.getUserById(userId);
                        return MemberDto.fromEntity(user, room.getCreatorId());
                    } catch (Exception e) {
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

    private String getUserIdFromAuth(Authentication authentication) {
        return authentication.getName();
    }
}
