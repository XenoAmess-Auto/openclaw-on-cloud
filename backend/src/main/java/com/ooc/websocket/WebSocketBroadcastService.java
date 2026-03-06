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
     * 会先确保 session 从所有其他房间移除，防止串房间
     */
    public void registerRoomSession(String roomId, WebSocketSession session) {
        // 先确保 session 不在任何其他房间（防止串房间）
        removeSessionFromAllRooms(session);
        
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.debug("Registered session {} to room {}", session.getId(), roomId);
    }

    /**
     * 从所有房间移除指定 session
     */
    private void removeSessionFromAllRooms(WebSocketSession session) {
        for (Map.Entry<String, Set<WebSocketSession>> entry : roomSessions.entrySet()) {
            String otherRoomId = entry.getKey();
            Set<WebSocketSession> sessions = entry.getValue();
            if (sessions.remove(session)) {
                log.debug("Removed session {} from room {} before registering to new room", 
                        session.getId(), otherRoomId);
                // 如果房间空了，清理该房间条目
                if (sessions.isEmpty()) {
                    roomSessions.remove(otherRoomId);
                }
            }
        }
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
        // 确保消息包含 roomId
        message.setRoomId(roomId);
        
        Set<WebSocketSession> excludeSet = new HashSet<>(java.util.Arrays.asList(exclude));
        Set<WebSocketSession> sessions = roomSessions.getOrDefault(roomId, Collections.emptySet());

        if (sessions.isEmpty()) {
            log.debug("No WebSocket sessions for room: {}", roomId);
            return;
        }

        // 检查是否有 session 同时存在于多个房间（串房间检测）
        if (log.isDebugEnabled()) {
            checkForCrossRoomSessions(roomId, sessions);
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
     * 检查指定 session 集合中是否有 session 同时存在于其他房间（用于调试串房间问题）
     */
    private void checkForCrossRoomSessions(String roomId, Set<WebSocketSession> sessions) {
        for (WebSocketSession session : sessions) {
            for (Map.Entry<String, Set<WebSocketSession>> entry : roomSessions.entrySet()) {
                String otherRoomId = entry.getKey();
                if (!otherRoomId.equals(roomId) && entry.getValue().contains(session)) {
                    log.warn("CROSS-ROOM DETECTED: Session {} exists in both room {} and room {}", 
                            session.getId(), roomId, otherRoomId);
                }
            }
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
