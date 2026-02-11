package com.ooc.dto;

import lombok.Data;

@Data
public class ChatRoomCreateRequest {
    private String name;
    private String description;
}
