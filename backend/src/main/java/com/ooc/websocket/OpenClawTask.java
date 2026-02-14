package com.ooc.websocket;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * OpenClaw 任务
 */
@Data
@Builder
public class OpenClawTask {
    private String taskId;
    private String roomId;
    private String content;
    private List<ChatWebSocketHandler.Attachment> attachments;
    private ChatWebSocketHandler.WebSocketUserInfo userInfo;
    private Instant createdAt;
    private TaskStatus status;

    public enum TaskStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}
