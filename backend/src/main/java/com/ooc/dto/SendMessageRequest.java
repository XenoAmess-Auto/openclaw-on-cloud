package com.ooc.dto;

import com.ooc.entity.ChatRoom;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SendMessageRequest {
    private String content;
    private List<ChatRoom.Message.Attachment> attachments = new ArrayList<>();
}
