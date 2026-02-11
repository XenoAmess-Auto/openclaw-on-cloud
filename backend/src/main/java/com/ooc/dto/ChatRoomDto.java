package com.ooc.dto;

import com.ooc.entity.ChatRoom;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Set;

@Data
@Builder
public class ChatRoomDto {
    private String id;
    private String name;
    private String description;
    private Set<String> memberIds;
    private String creatorId;
    private Instant createdAt;
    private Instant updatedAt;

    public static ChatRoomDto fromEntity(ChatRoom room) {
        return ChatRoomDto.builder()
                .id(room.getId())
                .name(room.getName())
                .description(room.getDescription())
                .memberIds(room.getMemberIds())
                .creatorId(room.getCreatorId())
                .createdAt(room.getCreatedAt())
                .updatedAt(room.getUpdatedAt())
                .build();
    }
}
