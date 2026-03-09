package com.ooc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ooc.entity.ChatRoom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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

    // 用于保护 session 注册/移除操作的全局锁
    private final Object sessionLock = new Object();

    /**
     * 注册房间会话（由 ChatWebSocketHandler 调用）
     * 会先确保 session 从所有其他房间移除，防止串房间
     * 使用同步锁确保线程安全
     */
    public void registerRoomSession(String roomId, WebSocketSession session) {
        synchronized (sessionLock) {
            // 先确保 session 不在任何其他房间（防止串房间）
            removeSessionFromAllRoomsInternal(session);

            roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
            log.info("[SessionRegistry] Registered session {} to room {}", session.getId(), roomId);

            // 记录当前 session 的所有房间（用于调试）
            logSessionRooms(session, roomId);
        }
    }

    /**
     * 记录 session 当前所在的房间（调试用）
     */
    private void logSessionRooms(WebSocketSession session, String currentRoomId) {
        if (!log.isDebugEnabled()) return;

        List<String> rooms = new ArrayList<>();
        for (Map.Entry<String, Set<WebSocketSession>> entry : roomSessions.entrySet()) {
            if (entry.getValue().contains(session)) {
                rooms.add(entry.getKey());
            }
        }
        log.debug("[SessionRegistry] Session {} is now in rooms: {} (current: {})",
                session.getId(), rooms, currentRoomId);
    }

    /**
     * 从所有房间移除指定 session（内部方法，调用方必须持有 sessionLock）
     */
    private void removeSessionFromAllRoomsInternal(WebSocketSession session) {
        Iterator<Map.Entry<String, Set<WebSocketSession>>> iterator = roomSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Set<WebSocketSession>> entry = iterator.next();
            String otherRoomId = entry.getKey();
            Set<WebSocketSession> sessions = entry.getValue();
            if (sessions.remove(session)) {
                log.info("[SessionRegistry] Removed session {} from room {} before re-registering",
                        session.getId(), otherRoomId);
                // 如果房间空了，安全地移除该房间条目
                if (sessions.isEmpty()) {
                    iterator.remove();
                    log.debug("[SessionRegistry] Removed empty room: {}", otherRoomId);
                }
            }
        }
    }

    /**
     * 从所有房间移除指定 session（公共方法，使用同步锁）
     */
    public void removeSessionFromAllRooms(WebSocketSession session) {
        synchronized (sessionLock) {
            removeSessionFromAllRoomsInternal(session);
        }
    }

    /**
     * 移除房间会话（由 ChatWebSocketHandler 调用）
     */
    public void removeRoomSession(String roomId, WebSocketSession session) {
        synchronized (sessionLock) {
            Set<WebSocketSession> sessions = roomSessions.get(roomId);
            if (sessions != null) {
                boolean removed = sessions.remove(session);
                if (removed) {
                    log.info("[SessionRegistry] Removed session {} from room {}", session.getId(), roomId);
                }
                if (sessions.isEmpty()) {
                    roomSessions.remove(roomId);
                    log.debug("[SessionRegistry] Removed empty room: {}", roomId);
                }
            }
        }
    }

    /**
     * 移除会话（当连接关闭时）- 从所有房间移除
     */
    public void removeSession(WebSocketSession session) {
        synchronized (sessionLock) {
            removeSessionFromAllRoomsInternal(session);
        }
    }

    /**
     * 广播消息到房间
     */
    public void broadcastToRoom(String roomId, WebSocketMessage message, WebSocketSession... exclude) {
        // 确保消息包含 roomId
        message.setRoomId(roomId);

        Set<WebSocketSession> excludeSet = new HashSet<>(java.util.Arrays.asList(exclude));

        // 获取该房间的 sessions 快照（避免在迭代时修改）
        Set<WebSocketSession> sessions;
        synchronized (sessionLock) {
            sessions = roomSessions.getOrDefault(roomId, Collections.emptySet());
            // 创建副本以避免在发送过程中被修改
            sessions = new HashSet<>(sessions);
        }

        if (sessions.isEmpty()) {
            log.debug("[Broadcast] No WebSocket sessions for room: {}", roomId);
            return;
        }

        // 严格检查：确保这些 session 确实只在这个房间
        Set<WebSocketSession> validSessions = new HashSet<>();
        for (WebSocketSession s : sessions) {
            if (excludeSet.contains(s)) {
                continue;
            }
            if (!s.isOpen()) {
                log.debug("[Broadcast] Skipping closed session {}", s.getId());
                continue;
            }

            // 双重检查：验证 session 是否确实只注册在当前房间
            Set<String> sessionRooms = getSessionRooms(s);
            if (sessionRooms.size() > 1) {
                log.error("[CROSS-ROOM ALERT] Session {} is registered in multiple rooms: {}. " +
                          "Fixing by removing from all rooms except current target: {}",
                        s.getId(), sessionRooms, roomId);
                // 修复：从其他房间移除
                fixCrossRoomSession(s, roomId);
            }

            validSessions.add(s);
        }

        if (validSessions.isEmpty()) {
            log.debug("[Broadcast] No valid sessions to broadcast to room: {}", roomId);
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(message);
            int sentCount = 0;
            for (WebSocketSession s : validSessions) {
                try {
                    s.sendMessage(new TextMessage(payload));
                    sentCount++;
                } catch (IOException e) {
                    log.error("[Broadcast] Failed to send message to session {}: {}", s.getId(), e.getMessage());
                }
            }
            log.info("[Broadcast] Message type='{}' broadcasted to {}/{} sessions in room {}",
                    message.getType(), sentCount, validSessions.size(), roomId);
        } catch (Exception e) {
            log.error("[Broadcast] Failed to serialize message: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取 session 当前注册的所有房间（公共方法，供外部验证使用）
     */
    public Set<String> getSessionRooms(WebSocketSession session) {
        Set<String> rooms = new HashSet<>();
        synchronized (sessionLock) {
            for (Map.Entry<String, Set<WebSocketSession>> entry : roomSessions.entrySet()) {
                if (entry.getValue().contains(session)) {
                    rooms.add(entry.getKey());
                }
            }
        }
        return rooms;
    }

    /**
     * 修复串房间的 session，只保留目标房间的注册
     */
    private void fixCrossRoomSession(WebSocketSession session, String keepRoomId) {
        synchronized (sessionLock) {
            for (Map.Entry<String, Set<WebSocketSession>> entry : roomSessions.entrySet()) {
                String roomId = entry.getKey();
                if (!roomId.equals(keepRoomId)) {
                    Set<WebSocketSession> sessions = entry.getValue();
                    if (sessions.remove(session)) {
                        log.info("[CROSS-ROOM FIX] Removed session {} from room {}", session.getId(), roomId);
                        if (sessions.isEmpty()) {
                            roomSessions.remove(roomId);
                        }
                    }
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

    /**
     * 获取房间的所有 WebSocket 会话（用于外部验证）
     */
    public Set<WebSocketSession> getRoomSessions(String roomId) {
        Set<WebSocketSession> sessions = roomSessions.getOrDefault(roomId, Collections.emptySet());
        // 返回副本以避免外部修改
        return new HashSet<>(sessions);
    }
}
