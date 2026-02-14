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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "chat_rooms")
public class ChatRoom {

    @Id
    private String id;

    @Indexed
    private String name;

    private String description;

    @Builder.Default
    private Set<String> memberIds = new HashSet<>();

    private String creatorId;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @Builder.Default
    private List<OpenClawSession> openClawSessions = new ArrayList<>();

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String id;
        private String senderId;
        private String senderName;
        private String senderAvatar;
        private String content;
        private Instant timestamp;
        private boolean openclawMentioned;
        private boolean fromOpenClaw;
        private boolean isSystem;
        @JsonProperty("isToolCall")
        private boolean isToolCall;
        @Builder.Default
        private List<ToolCall> toolCalls = new ArrayList<>();
        @Builder.Default
        private List<Mention> mentions = new ArrayList<>();
        private boolean mentionAll;
        private boolean mentionHere;
        @Builder.Default
        private List<Attachment> attachments = new ArrayList<>();
        
        // 流式消息相关字段
        private boolean isStreaming;
        private boolean delta;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Mention {
            private String userId;
            private String userName;
        }
        
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Attachment {
            private String id;
            private String url;
            private String name;
            private String type;
            private String contentType;
            private long size;
        }
        
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ToolCall {
            private String id;
            private String name;
            private String description;
            private String status; // running, completed, error
            private String result;
            private Instant timestamp;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpenClawSession {
        private String sessionId;
        private String instanceName;
        private Instant startedAt;
        private Instant endedAt;
        private boolean active;
    }
}
