package com.ooc.websocket;

import com.ooc.entity.ChatRoom;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * WebSocket 消息传输对象
 */
@Data
@Builder
public class WebSocketMessage {
    private String type;
    private String roomId;
    private String userId;
    private String userName;
    private String content;
    private ChatRoom.Message message;
    private List<ChatRoom.Message> messages;
    private List<com.ooc.websocket.Attachment> attachments; // 附件列表
    private Boolean hasMore; // 是否还有更多历史消息
}
