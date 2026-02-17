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

    @JsonProperty("id")
    @Id
    private String id;

    @JsonProperty("name")
    @Indexed
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("memberIds")
    @Builder.Default
    private Set<String> memberIds = new HashSet<>();

    @JsonProperty("creatorId")
    private String creatorId;

    @JsonProperty("messages")
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @JsonProperty("openClawSessions")
    @Builder.Default
    private List<OpenClawSession> openClawSessions = new ArrayList<>();

    @JsonProperty("createdAt")
    @CreatedDate
    private Instant createdAt;

    @JsonProperty("updatedAt")
    @LastModifiedDate
    private Instant updatedAt;

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        @JsonProperty("id")
        private String id;

        @JsonProperty("senderId")
        private String senderId;

        @JsonProperty("senderName")
        private String senderName;

        @JsonProperty("senderAvatar")
        private String senderAvatar;

        @JsonProperty("content")
        private String content;

        @JsonProperty("timestamp")
        private Instant timestamp;

        @JsonProperty("openclawMentioned")
        private boolean openclawMentioned;

        @JsonProperty("fromOpenClaw")
        private boolean fromOpenClaw;

        @JsonProperty("isSystem")
        private boolean isSystem;

        @JsonProperty("isToolCall")
        private boolean isToolCall;

        @JsonProperty("toolCalls")
        @Builder.Default
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private List<ToolCall> toolCalls = new ArrayList<>();

        @JsonProperty("mentions")
        @Builder.Default
        private List<Mention> mentions = new ArrayList<>();

        @JsonProperty("mentionAll")
        private boolean mentionAll;

        @JsonProperty("mentionHere")
        private boolean mentionHere;

        @JsonProperty("attachments")
        @Builder.Default
        private List<Attachment> attachments = new ArrayList<>();

        @JsonProperty("isStreaming")
        private boolean isStreaming;

        @JsonProperty("delta")
        private boolean delta;

        @JsonProperty("replyToMessageId")
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String replyToMessageId;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Mention {
            @JsonProperty("userId")
            private String userId;

            @JsonProperty("userName")
            private String userName;
        }
        
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Attachment {
            @JsonProperty("id")
            private String id;

            @JsonProperty("url")
            private String url;

            @JsonProperty("name")
            private String name;

            @JsonProperty("type")
            private String type;

            @JsonProperty("contentType")
            private String contentType;

            @JsonProperty("size")
            private long size;
        }
        
        @Data
        @Builder(toBuilder = true)
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ToolCall {
            @JsonProperty("id")
            private String id;

            @JsonProperty("name")
            private String name;

            @JsonProperty("description")
            private String description;

            @JsonProperty("status")
            private String status;

            @JsonProperty("result")
            private String result;

            @JsonProperty("timestamp")
            private Instant timestamp;

            @JsonProperty("position")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            private Integer position;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpenClawSession {
        @JsonProperty("sessionId")
        private String sessionId;

        @JsonProperty("instanceName")
        private String instanceName;

        @JsonProperty("startedAt")
        private Instant startedAt;

        @JsonProperty("endedAt")
        private Instant endedAt;

        @JsonProperty("active")
        private boolean active;
    }
}
