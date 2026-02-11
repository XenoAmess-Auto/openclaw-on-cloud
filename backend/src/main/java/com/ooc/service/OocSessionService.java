package com.ooc.service;

import com.ooc.entity.OocSession;
import com.ooc.openclaw.OpenClawPluginService;
import com.ooc.repository.OocSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OocSessionService {

    private final OocSessionRepository oocSessionRepository;
    private final OpenClawPluginService openClawPluginService;

    public Mono<OocSession> getOrCreateSession(String chatRoomId, String chatRoomName) {
        return Mono.fromCallable(() -> 
            oocSessionRepository.findByChatRoomIdAndArchivedFalse(chatRoomId)
                .orElseGet(() -> createNewSession(chatRoomId, chatRoomName))
        );
    }

    private OocSession createNewSession(String chatRoomId, String chatRoomName) {
        OocSession session = OocSession.builder()
                .id(UUID.randomUUID().toString())
                .chatRoomId(chatRoomId)
                .chatRoomName(chatRoomName)
                .messages(new ArrayList<>())
                .messageCount(0)
                .archived(false)
                .build();
        return oocSessionRepository.save(session);
    }

    public OocSession addMessage(String chatRoomId, OocSession.SessionMessage message) {
        OocSession session = oocSessionRepository.findByChatRoomIdAndArchivedFalse(chatRoomId)
                .orElseThrow(() -> new RuntimeException("No active session found"));
        
        session.getMessages().add(message);
        session.setMessageCount(session.getMessages().size());
        
        // 限制消息数量
        if (session.getMessages().size() > 100) {
            // 保留最新的 50 条，旧的会被总结
            int removeCount = session.getMessages().size() - 50;
            session.setMessages(new ArrayList<>(session.getMessages().subList(removeCount, session.getMessages().size())));
        }
        
        return oocSessionRepository.save(session);
    }

    public Mono<OocSession> summarizeAndCompact(OocSession session) {
        // 转换消息格式用于总结
        List<Map<String, String>> messagesForSummary = new ArrayList<>();
        for (OocSession.SessionMessage msg : session.getMessages()) {
            Map<String, String> m = new HashMap<>();
            m.put("sender", msg.getSenderName());
            m.put("content", msg.getContent());
            messagesForSummary.add(m);
        }

        return openClawPluginService.summarizeSession(messagesForSummary)
                .map(summary -> {
                    session.setSummary(summary);
                    // 清空详细消息，只保留摘要
                    session.getMessages().clear();
                    session.setMessageCount(0);
                    return oocSessionRepository.save(session);
                })
                .doOnError(e -> log.error("Failed to summarize session", e))
                .onErrorReturn(session);
    }

    public List<OocSession> getSessionHistory(String chatRoomId) {
        return oocSessionRepository.findByChatRoomIdOrderByCreatedAtDesc(chatRoomId);
    }

    public OocSession archiveSession(String sessionId) {
        return oocSessionRepository.findById(sessionId).map(session -> {
            session.setArchived(true);
            return oocSessionRepository.save(session);
        }).orElseThrow(() -> new RuntimeException("Session not found"));
    }

    public OocSession copySession(String sessionId, String newChatRoomId) {
        OocSession original = oocSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        
        OocSession copy = OocSession.builder()
                .id(UUID.randomUUID().toString())
                .chatRoomId(newChatRoomId)
                .chatRoomName(original.getChatRoomName() + " (Copy)")
                .summary(original.getSummary())
                .messages(new ArrayList<>(original.getMessages()))
                .messageCount(original.getMessageCount())
                .archived(false)
                .build();
        
        return oocSessionRepository.save(copy);
    }
}
