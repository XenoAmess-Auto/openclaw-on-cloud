package com.ooc.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ooc_sessions")
public class OocSession {

    @Id
    private String id;

    @Indexed
    private String chatRoomId;

    private String chatRoomName;

    private String summary;

    @Builder.Default
    private List<SessionMessage> messages = new ArrayList<>();

    private int messageCount;

    private boolean archived;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionMessage {
        private String id;
        private String senderId;
        private String senderName;
        private String content;
        private Instant timestamp;
        private boolean fromOpenClaw;
    }
}
