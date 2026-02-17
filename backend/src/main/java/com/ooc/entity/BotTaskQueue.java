package com.ooc.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * 机器人任务队列实体 - 用于持久化 OpenClaw/Kimi/Claude 任务队列
 * 确保服务重启后任务不会丢失
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "bot_task_queues")
@CompoundIndex(name = "room_bot_status_idx", def = "{'roomId': 1, 'botType': 1, 'status': 1}")
public class BotTaskQueue {

    @JsonProperty("id")
    @Id
    private String id;

    @JsonProperty("taskId")
    @Indexed
    private String taskId;

    @JsonProperty("roomId")
    @Indexed
    private String roomId;

    @JsonProperty("botType")
    @Indexed
    private BotType botType;

    @JsonProperty("content")
    private String content;

    @JsonProperty("attachments")
    private List<Attachment> attachments;

    @JsonProperty("userInfo")
    private UserInfo userInfo;

    @JsonProperty("sourceMessageId")
    private String sourceMessageId;

    @JsonProperty("status")
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    @JsonProperty("position")
    private Integer position;

    @JsonProperty("createdAt")
    @CreatedDate
    private Instant createdAt;

    @JsonProperty("updatedAt")
    @LastModifiedDate
    private Instant updatedAt;

    /**
     * 机器人类型
     */
    public enum BotType {
        OPENCLAW,
        KIMI,
        CLAUDE
    }

    /**
     * 任务状态
     */
    public enum TaskStatus {
        PENDING,      // 待处理
        PROCESSING,   // 处理中
        COMPLETED,    // 已完成
        FAILED,       // 失败
        CANCELLED     // 已取消
    }

    /**
     * 附件信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attachment {
        @JsonProperty("type")
        private String type;

        @JsonProperty("mimeType")
        private String mimeType;

        @JsonProperty("url")
        private String url;

        @JsonProperty("content")
        private String content;
    }

    /**
     * 用户信息（简化版，用于任务队列）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        @JsonProperty("userId")
        private String userId;

        @JsonProperty("userName")
        private String userName;

        @JsonProperty("roomId")
        private String roomId;

        @JsonProperty("avatar")
        private String avatar;
    }
}
