package com.ooc.service;

import com.ooc.entity.ChatRoom;
import com.ooc.repository.ChatRoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @InjectMocks
    private ChatRoomService chatRoomService;

    @Test
    void createChatRoom_WithValidData_ShouldCreateRoom() {
        // Given
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(invocation -> {
            ChatRoom room = invocation.getArgument(0);
            room.setId("room-123");
            return room;
        });

        // When
        ChatRoom result = chatRoomService.createChatRoom("Test Room", "Description", "user-123");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("room-123");
        assertThat(result.getName()).isEqualTo("Test Room");
        assertThat(result.getDescription()).isEqualTo("Description");
        assertThat(result.getCreatorId()).isEqualTo("user-123");
        assertThat(result.getMemberIds()).contains("user-123");
    }

    @Test
    void getChatRoom_WithExistingRoom_ShouldReturnRoom() {
        // Given
        ChatRoom room = ChatRoom.builder()
                .id("room-123")
                .name("Test Room")
                .build();
        when(chatRoomRepository.findById("room-123")).thenReturn(Optional.of(room));

        // When
        Optional<ChatRoom> result = chatRoomService.getChatRoom("room-123");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Test Room");
    }

    @Test
    void getUserChatRooms_ShouldReturnUserRooms() {
        // Given
        ChatRoom room1 = ChatRoom.builder().id("room-1").name("Room 1").build();
        ChatRoom room2 = ChatRoom.builder().id("room-2").name("Room 2").build();
        when(chatRoomRepository.findByMemberIdsContaining("user-123"))
                .thenReturn(Arrays.asList(room1, room2));

        // When
        List<ChatRoom> result = chatRoomService.getUserChatRooms("user-123");

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(ChatRoom::getName).contains("Room 1", "Room 2");
    }

    @Test
    void addMember_WithExistingRoom_ShouldAddMember() {
        // Given
        ChatRoom room = ChatRoom.builder()
                .id("room-123")
                .name("Test Room")
                .memberIds(new java.util.HashSet<>(java.util.Set.of("creator-123")))
                .build();
        when(chatRoomRepository.findById("room-123")).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(room);

        // When
        ChatRoom result = chatRoomService.addMember("room-123", "new-user-123");

        // Then
        assertThat(result.getMemberIds()).contains("creator-123", "new-user-123");
    }

    @Test
    void addMember_WithNonExistingRoom_ShouldThrowException() {
        // Given
        when(chatRoomRepository.findById("unknown")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> chatRoomService.addMember("unknown", "user-123"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Chat room not found");
    }

    @Test
    void removeMember_WithExistingRoom_ShouldRemoveMember() {
        // Given
        ChatRoom room = ChatRoom.builder()
                .id("room-123")
                .name("Test Room")
                .memberIds(new java.util.HashSet<>(java.util.Set.of("creator-123", "user-123")))
                .build();
        when(chatRoomRepository.findById("room-123")).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(room);

        // When
        ChatRoom result = chatRoomService.removeMember("room-123", "user-123");

        // Then
        assertThat(result.getMemberIds()).contains("creator-123");
        assertThat(result.getMemberIds()).doesNotContain("user-123");
    }

    @Test
    void addMessage_WithExistingRoom_ShouldAddMessage() {
        // Given
        ChatRoom room = ChatRoom.builder()
                .id("room-123")
                .name("Test Room")
                .messages(new ArrayList<>())
                .build();
        ChatRoom.Message message = ChatRoom.Message.builder()
                .senderId("user-123")
                .senderName("Test User")
                .content("Hello World")
                .timestamp(Instant.now())
                .build();
        when(chatRoomRepository.findById("room-123")).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(room);

        // When
        ChatRoom result = chatRoomService.addMessage("room-123", message);

        // Then
        assertThat(result.getMessages()).hasSize(1);
        assertThat(result.getMessages().get(0).getContent()).isEqualTo("Hello World");
    }

    @Test
    void addMessage_WithMoreThan1000Messages_ShouldKeepLatest1000() {
        // Given
        List<ChatRoom.Message> messages = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            messages.add(ChatRoom.Message.builder()
                    .content("Message " + i)
                    .build());
        }
        ChatRoom room = ChatRoom.builder()
                .id("room-123")
                .messages(messages)
                .build();
        ChatRoom.Message newMessage = ChatRoom.Message.builder()
                .content("New Message")
                .build();
        when(chatRoomRepository.findById("room-123")).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(room);

        // When
        ChatRoom result = chatRoomService.addMessage("room-123", newMessage);

        // Then
        assertThat(result.getMessages()).hasSize(1000);
        assertThat(result.getMessages().get(999).getContent()).isEqualTo("New Message");
    }

    @Test
    void deleteChatRoom_ShouldDeleteRoom() {
        // Given
        doNothing().when(chatRoomRepository).deleteById("room-123");

        // When
        chatRoomService.deleteChatRoom("room-123");

        // Then
        verify(chatRoomRepository).deleteById("room-123");
    }
}
