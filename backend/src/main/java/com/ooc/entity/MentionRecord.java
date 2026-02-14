package com.ooc.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("id")
    @Id
    private String id;

    @JsonProperty("messageId")
    @Indexed
    private String messageId;

    @JsonProperty("mentionedUserId")
    @Indexed
    private String mentionedUserId;

    @JsonProperty("mentionedUserName")
    private String mentionedUserName;

    @JsonProperty("mentionerUserId")
    @Indexed
    private String mentionerUserId;

    @JsonProperty("mentionerUserName")
    private String mentionerUserName;

    @JsonProperty("roomId")
    @Indexed
    private String roomId;

    @JsonProperty("roomName")
    private String roomName;

    @JsonProperty("messageContent")
    private String messageContent;

    @JsonProperty("isRead")
    @Builder.Default
    private boolean isRead = false;

    @JsonProperty("readAt")
    private Instant readAt;

    @JsonProperty("createdAt")
    @CreatedDate
    private Instant createdAt;
}
