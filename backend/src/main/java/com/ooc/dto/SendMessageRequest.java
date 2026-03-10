package com.ooc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ooc.entity.ChatRoom;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SendMessageRequest {
    private String content;
    private List<ChatRoom.Message.Attachment> attachments = new ArrayList<>();
}
