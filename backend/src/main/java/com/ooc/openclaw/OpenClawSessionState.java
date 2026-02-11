package com.ooc.openclaw;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class OpenClawSessionState {
    private String sessionId;
    private String instanceName;
    private Instant createdAt;
    private Instant lastActivity;
    private String chatRoomId;
    private int messageCount;
}
