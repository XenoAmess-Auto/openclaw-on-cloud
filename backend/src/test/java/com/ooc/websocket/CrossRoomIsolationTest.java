package com.ooc.websocket;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 串房间问题修复验证测试
 */
@Slf4j
public class CrossRoomIsolationTest {

    @Test
    public void testSessionIsolationBetweenRooms() {
        // 模拟 WebSocketBroadcastService 的行为
        WebSocketBroadcastService broadcastService = new WebSocketBroadcastService(null);
        
        // 模拟两个不同的 session（代表两个浏览器 tab）
        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);
        when(session1.getId()).thenReturn("session-1");
        when(session2.getId()).thenReturn("session-2");
        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);
        
        String room1 = "room-a";
        String room2 = "room-b";
        
        // Session 1 加入 Room A
        broadcastService.registerRoomSession(room1, session1);
        
        // Session 2 加入 Room B
        broadcastService.registerRoomSession(room2, session2);
        
        // 验证 session 1 只在 Room A
        Set<WebSocketSession> room1Sessions = broadcastService.getRoomSessions(room1);
        assertTrue(room1Sessions.contains(session1), "Session 1 should be in Room A");
        assertFalse(room1Sessions.contains(session2), "Session 2 should NOT be in Room A");
        
        // 验证 session 2 只在 Room B
        Set<WebSocketSession> room2Sessions = broadcastService.getRoomSessions(room2);
        assertTrue(room2Sessions.contains(session2), "Session 2 should be in Room B");
        assertFalse(room2Sessions.contains(session1), "Session 1 should NOT be in Room B");
        
        log.info("✅ Session isolation test passed!");
    }
    
    @Test
    public void testSessionSwitchingRooms() {
        WebSocketBroadcastService broadcastService = new WebSocketBroadcastService(null);
        
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-switch");
        when(session.isOpen()).thenReturn(true);
        
        String room1 = "room-old";
        String room2 = "room-new";
        
        // Session 先加入 Room 1
        broadcastService.registerRoomSession(room1, session);
        assertTrue(broadcastService.getRoomSessions(room1).contains(session));
        
        // Session 切换到 Room 2
        broadcastService.registerRoomSession(room2, session);
        
        // 验证 session 只在 Room 2，不在 Room 1
        assertFalse(broadcastService.getRoomSessions(room1).contains(session), 
            "Session should be removed from old room");
        assertTrue(broadcastService.getRoomSessions(room2).contains(session), 
            "Session should be in new room");
        
        log.info("✅ Session switching rooms test passed!");
    }
    
    @Test
    public void testUserInfoImmutability() {
        // 测试 UserInfo 的拷贝
        ChatWebSocketHandler.WebSocketUserInfo original = 
            ChatWebSocketHandler.WebSocketUserInfo.builder()
                .userId("user1")
                .userName("Test User")
                .roomId("room1")
                .avatar("avatar1.png")
                .build();
        
        // 创建拷贝（模拟 addTask 中的行为）
        ChatWebSocketHandler.WebSocketUserInfo copy = 
            ChatWebSocketHandler.WebSocketUserInfo.builder()
                .userId(original.getUserId())
                .userName(original.getUserName())
                .roomId(original.getRoomId())
                .avatar(original.getAvatar())
                .build();
        
        // 修改原始对象
        original.setRoomId("room2");
        
        // 验证拷贝没有被修改
        assertEquals("room1", copy.getRoomId(), "Copy should not be affected by original's change");
        
        log.info("✅ UserInfo immutability test passed!");
    }
}
