package com.ooc.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("id")
    @Id
    private String id;

    @JsonProperty("chatRoomId")
    @Indexed
    private String chatRoomId;

    @JsonProperty("chatRoomName")
    private String chatRoomName;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("messages")
    @Builder.Default
    private List<SessionMessage> messages = new ArrayList<>();

    @JsonProperty("messageCount")
    private int messageCount;

    @JsonProperty("archived")
    private boolean archived;

    @JsonProperty("createdAt")
    @CreatedDate
    private Instant createdAt;

    @JsonProperty("updatedAt")
    @LastModifiedDate
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionMessage {
        @JsonProperty("id")
        private String id;

        @JsonProperty("senderId")
        private String senderId;

        @JsonProperty("senderName")
        private String senderName;

        @JsonProperty("content")
        private String content;

        @JsonProperty("timestamp")
        private Instant timestamp;

        @JsonProperty("fromOpenClaw")
        private boolean fromOpenClaw;
    }
}
