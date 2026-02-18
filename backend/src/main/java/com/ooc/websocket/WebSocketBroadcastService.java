package com.ooc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ooc.entity.ChatRoom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 广播服务
 * 用于向房间广播消息，供其他服务使用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketBroadcastService {

    private final ObjectMapper objectMapper;

    // roomId -> Set<WebSocketSession>
    private final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    /**
     * 注册房间会话（由 ChatWebSocketHandler 调用）
     */
    public void registerRoomSession(String roomId, WebSocketSession session) {
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    /**
     * 移除房间会话（由 ChatWebSocketHandler 调用）
     */
    public void removeRoomSession(String roomId, WebSocketSession session) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
    }

    /**
     * 移除会话（当连接关闭时）
     */
    public void removeSession(WebSocketSession session) {
        for (Set<WebSocketSession> sessions : roomSessions.values()) {
            sessions.remove(session);
        }
    }

    /**
     * 广播消息到房间
     */
    public void broadcastToRoom(String roomId, WebSocketMessage message, WebSocketSession... exclude) {
        Set<WebSocketSession> excludeSet = new HashSet<>(java.util.Arrays.asList(exclude));
        Set<WebSocketSession> sessions = roomSessions.getOrDefault(roomId, Collections.emptySet());

        if (sessions.isEmpty()) {
            log.debug("No WebSocket sessions for room: {}", roomId);
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(message);
            int sentCount = 0;
            for (WebSocketSession s : sessions) {
                if (!excludeSet.contains(s) && s.isOpen()) {
                    try {
                        s.sendMessage(new TextMessage(payload));
                        sentCount++;
                    } catch (IOException e) {
                        log.error("Failed to send message to session", e);
                    }
                }
            }
            log.debug("Broadcasted message to {} sessions in room {}", sentCount, roomId);
        } catch (Exception e) {
            log.error("Failed to serialize message", e);
        }
    }

    /**
     * 广播聊天消息到房间
     */
    public void broadcastChatMessage(String roomId, ChatRoom.Message message) {
        WebSocketMessage wsMessage = WebSocketMessage.builder()
                .type("message")
                .message(message)
                .build();
        broadcastToRoom(roomId, wsMessage);
    }

    /**
     * 获取房间的 WebSocket 会话数
     */
    public int getRoomSessionCount(String roomId) {
        return roomSessions.getOrDefault(roomId, Collections.emptySet()).size();
    }
}
