package com.ooc.service;

import com.ooc.entity.ChatRoom;
import com.ooc.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;

    public ChatRoom createChatRoom(String name, String description, String creatorId) {
        Set<String> memberIds = new HashSet<>();
        memberIds.add(creatorId);
        ChatRoom room = ChatRoom.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .description(description)
                .creatorId(creatorId)
                .memberIds(memberIds)
                .build();
        return chatRoomRepository.save(room);
    }

    public Optional<ChatRoom> getChatRoom(String roomId) {
        return chatRoomRepository.findById(roomId)
                .map(this::ensureCreatorInMembers);
    }

    private ChatRoom ensureCreatorInMembers(ChatRoom room) {
        // Ensure memberIds is not null
        if (room.getMemberIds() == null) {
            room.setMemberIds(new HashSet<>());
        }
        // Ensure creator is always in memberIds (for old data compatibility)
        if (room.getCreatorId() != null && !room.getMemberIds().contains(room.getCreatorId())) {
            room.getMemberIds().add(room.getCreatorId());
            return chatRoomRepository.save(room);
        }
        return room;
    }

    public List<ChatRoom> getUserChatRooms(String userId) {
        return chatRoomRepository.findByMemberIdsContaining(userId);
    }

    public ChatRoom addMember(String roomId, String userId) {
        return chatRoomRepository.findById(roomId).map(room -> {
            room.getMemberIds().add(userId);
            return chatRoomRepository.save(room);
        }).orElseThrow(() -> new RuntimeException("Chat room not found"));
    }

    public ChatRoom removeMember(String roomId, String userId) {
        return chatRoomRepository.findById(roomId).map(room -> {
            room.getMemberIds().remove(userId);
            return chatRoomRepository.save(room);
        }).orElseThrow(() -> new RuntimeException("Chat room not found"));
    }

    public ChatRoom addMessage(String roomId, ChatRoom.Message message) {
        return chatRoomRepository.findById(roomId).map(room -> {
            room.getMessages().add(message);
            // 限制历史消息数量，保留最新的 1000 条
            if (room.getMessages().size() > 1000) {
                room.setMessages(room.getMessages().subList(room.getMessages().size() - 1000, room.getMessages().size()));
            }
            return chatRoomRepository.save(room);
        }).orElseThrow(() -> new RuntimeException("Chat room not found"));
    }

    /**
     * 更新消息（用于流式消息更新）
     */
    public ChatRoom updateMessage(String roomId, ChatRoom.Message updatedMessage) {
        return chatRoomRepository.findById(roomId).map(room -> {
            List<ChatRoom.Message> messages = room.getMessages();
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getId().equals(updatedMessage.getId())) {
                    messages.set(i, updatedMessage);
                    break;
                }
            }
            return chatRoomRepository.save(room);
        }).orElseThrow(() -> new RuntimeException("Chat room not found"));
    }

    public void updateOpenClawSession(String roomId, String sessionId) {
        chatRoomRepository.findById(roomId).ifPresent(room -> {
            // 标记旧的为不活跃
            room.getOpenClawSessions().forEach(s -> {
                if (s.isActive()) {
                    s.setActive(false);
                    s.setEndedAt(Instant.now());
                }
            });
            // 添加新的
            room.getOpenClawSessions().add(ChatRoom.OpenClawSession.builder()
                    .sessionId(sessionId)
                    .instanceName("ooc-" + roomId)
                    .startedAt(Instant.now())
                    .active(true)
                    .build());
            chatRoomRepository.save(room);
        });
    }

    public void deleteChatRoom(String roomId) {
        chatRoomRepository.deleteById(roomId);
    }
}
