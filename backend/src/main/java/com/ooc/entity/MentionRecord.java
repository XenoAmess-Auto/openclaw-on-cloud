package com.ooc.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mention_records")
@CompoundIndex(name = "user_room_idx", def = "{'mentionedUserId': 1, 'roomId': 1}")
@CompoundIndex(name = "user_unread_idx", def = "{'mentionedUserId': 1, 'isRead': 1, 'createdAt': -1}")
public class MentionRecord {

    @Id
    private String id;

    @Indexed
    private String messageId;

    @Indexed
    private String mentionedUserId;

    private String mentionedUserName;

    @Indexed
    private String mentionerUserId;

    private String mentionerUserName;

    @Indexed
    private String roomId;

    private String roomName;

    private String messageContent;

    @Builder.Default
    private boolean isRead = false;

    private Instant readAt;

    @CreatedDate
    private Instant createdAt;
}
