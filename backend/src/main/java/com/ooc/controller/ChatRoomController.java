package com.ooc.controller;

import com.ooc.dto.ChatRoomCreateRequest;
import com.ooc.dto.ChatRoomDto;
import com.ooc.entity.ChatRoom;
import com.ooc.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

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

    @PostMapping("/{roomId}/members")
    public ResponseEntity<ChatRoomDto> addMember(
            @PathVariable String roomId,
            @RequestParam String userId) {
        ChatRoom room = chatRoomService.addMember(roomId, userId);
        return ResponseEntity.ok(ChatRoomDto.fromEntity(room));
    }

    @DeleteMapping("/{roomId}/members/{userId}")
    public ResponseEntity<ChatRoomDto> removeMember(
            @PathVariable String roomId,
            @PathVariable String userId) {
        ChatRoom room = chatRoomService.removeMember(roomId, userId);
        return ResponseEntity.ok(ChatRoomDto.fromEntity(room));
    }

    @DeleteMapping("/{roomId}")
    public ResponseEntity<Void> deleteChatRoom(@PathVariable String roomId) {
        chatRoomService.deleteChatRoom(roomId);
        return ResponseEntity.ok().build();
    }

    private String getUserIdFromAuth(Authentication authentication) {
        return authentication.getName();
    }
}
