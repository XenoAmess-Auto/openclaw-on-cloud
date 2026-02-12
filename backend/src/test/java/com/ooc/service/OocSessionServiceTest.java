package com.ooc.service;

import com.ooc.entity.OocSession;
import com.ooc.openclaw.OpenClawPluginService;
import com.ooc.repository.OocSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OocSessionServiceTest {

    @Mock
    private OocSessionRepository oocSessionRepository;

    @Mock
    private OpenClawPluginService openClawPluginService;

    @InjectMocks
    private OocSessionService oocSessionService;

    @Test
    void getOrCreateSession_WithExistingSession_ShouldReturnSession() {
        // Given
        OocSession existingSession = OocSession.builder()
                .id("session-123")
                .chatRoomId("room-123")
                .chatRoomName("Test Room")
                .archived(false)
                .build();
        when(oocSessionRepository.findByChatRoomIdAndArchivedFalse("room-123"))
                .thenReturn(Optional.of(existingSession));

        // When & Then
        StepVerifier.create(oocSessionService.getOrCreateSession("room-123", "Test Room"))
                .assertNext(session -> {
                    assertThat(session.getId()).isEqualTo("session-123");
                    assertThat(session.isArchived()).isFalse();
                })
                .verifyComplete();

        verify(oocSessionRepository, never()).save(any());
    }

    @Test
    void getOrCreateSession_WithNoExistingSession_ShouldCreateNewSession() {
        // Given
        when(oocSessionRepository.findByChatRoomIdAndArchivedFalse("room-123"))
                .thenReturn(Optional.empty());
        when(oocSessionRepository.save(any(OocSession.class))).thenAnswer(invocation -> {
            OocSession session = invocation.getArgument(0);
            session.setId("new-session-123");
            return session;
        });

        // When & Then
        StepVerifier.create(oocSessionService.getOrCreateSession("room-123", "Test Room"))
                .assertNext(session -> {
                    assertThat(session.getChatRoomId()).isEqualTo("room-123");
                    assertThat(session.getChatRoomName()).isEqualTo("Test Room");
                    assertThat(session.isArchived()).isFalse();
                })
                .verifyComplete();

        verify(oocSessionRepository).save(any(OocSession.class));
    }

    @Test
    void addMessage_WithExistingSession_ShouldAddMessage() {
        // Given
        OocSession session = OocSession.builder()
                .id("session-123")
                .chatRoomId("room-123")
                .messages(new ArrayList<>())
                .build();
        OocSession.SessionMessage message = OocSession.SessionMessage.builder()
                .senderName("Test User")
                .content("Hello World")
                .build();
        when(oocSessionRepository.findByChatRoomIdAndArchivedFalse("room-123"))
                .thenReturn(Optional.of(session));
        when(oocSessionRepository.save(any(OocSession.class))).thenReturn(session);

        // When
        OocSession result = oocSessionService.addMessage("room-123", message);

        // Then
        assertThat(result.getMessages()).hasSize(1);
        assertThat(result.getMessageCount()).isEqualTo(1);
    }

    @Test
    void addMessage_WithMoreThan100Messages_ShouldCompactMessages() {
        // Given
        List<OocSession.SessionMessage> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            messages.add(OocSession.SessionMessage.builder()
                    .content("Message " + i)
                    .build());
        }
        OocSession session = OocSession.builder()
                .id("session-123")
                .chatRoomId("room-123")
                .messages(messages)
                .build();
        OocSession.SessionMessage newMessage = OocSession.SessionMessage.builder()
                .content("New Message")
                .build();
        when(oocSessionRepository.findByChatRoomIdAndArchivedFalse("room-123"))
                .thenReturn(Optional.of(session));
        when(oocSessionRepository.save(any(OocSession.class))).thenReturn(session);

        // When
        OocSession result = oocSessionService.addMessage("room-123", newMessage);

        // Then - service keeps last 50 messages after compacting (truncates to 50)
        assertThat(result.getMessages()).hasSize(50);
    }

    @Test
    void addMessage_WithNoActiveSession_ShouldThrowException() {
        // Given
        OocSession.SessionMessage message = OocSession.SessionMessage.builder()
                .content("Hello")
                .build();
        when(oocSessionRepository.findByChatRoomIdAndArchivedFalse("room-123"))
                .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> oocSessionService.addMessage("room-123", message))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("No active session found");
    }

    @Test
    void getSessionHistory_ShouldReturnSessions() {
        // Given
        OocSession session1 = OocSession.builder().id("session-1").chatRoomId("room-123").build();
        OocSession session2 = OocSession.builder().id("session-2").chatRoomId("room-123").build();
        when(oocSessionRepository.findByChatRoomIdOrderByCreatedAtDesc("room-123"))
                .thenReturn(List.of(session1, session2));

        // When
        List<OocSession> result = oocSessionService.getSessionHistory("room-123");

        // Then
        assertThat(result).hasSize(2);
    }

    @Test
    void archiveSession_WithExistingSession_ShouldArchive() {
        // Given
        OocSession session = OocSession.builder()
                .id("session-123")
                .archived(false)
                .build();
        when(oocSessionRepository.findById("session-123")).thenReturn(Optional.of(session));
        when(oocSessionRepository.save(any(OocSession.class))).thenAnswer(invocation -> {
            OocSession s = invocation.getArgument(0);
            s.setArchived(true);
            return s;
        });

        // When
        OocSession result = oocSessionService.archiveSession("session-123");

        // Then
        assertThat(result.isArchived()).isTrue();
    }

    @Test
    void copySession_WithExistingSession_ShouldCreateCopy() {
        // Given
        OocSession original = OocSession.builder()
                .id("session-123")
                .chatRoomId("room-123")
                .chatRoomName("Original Room")
                .summary("Summary text")
                .messages(new ArrayList<>())
                .messageCount(0)
                .archived(false)
                .build();
        when(oocSessionRepository.findById("session-123")).thenReturn(Optional.of(original));
        when(oocSessionRepository.save(any(OocSession.class))).thenAnswer(invocation -> {
            OocSession s = invocation.getArgument(0);
            s.setId("copied-session-123");
            return s;
        });

        // When
        OocSession result = oocSessionService.copySession("session-123", "new-room-123");

        // Then
        assertThat(result.getId()).isEqualTo("copied-session-123");
        assertThat(result.getChatRoomId()).isEqualTo("new-room-123");
        assertThat(result.getChatRoomName()).contains("(Copy)");
        assertThat(result.isArchived()).isFalse();
    }

    @Test
    void summarizeAndCompact_ShouldClearMessagesAndSetSummary() {
        // Given
        List<OocSession.SessionMessage> messages = new ArrayList<>();
        messages.add(OocSession.SessionMessage.builder()
                .senderName("User")
                .content("Hello")
                .build());
        OocSession session = OocSession.builder()
                .id("session-123")
                .chatRoomId("room-123")
                .messages(messages)
                .messageCount(1)
                .build();

        when(openClawPluginService.summarizeSession(any())).thenReturn(Mono.just("Summary text"));
        when(oocSessionRepository.save(any(OocSession.class))).thenReturn(session);

        // When & Then
        StepVerifier.create(oocSessionService.summarizeAndCompact(session))
                .assertNext(result -> {
                    assertThat(result.getSummary()).isEqualTo("Summary text");
                    assertThat(result.getMessages()).isEmpty();
                    assertThat(result.getMessageCount()).isEqualTo(0);
                })
                .verifyComplete();
    }
}
