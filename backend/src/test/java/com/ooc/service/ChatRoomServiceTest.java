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
import java.util.HashSet;
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

    private static final String USER_ID = "user123";
    private static final String ROOM_ID = "room123";

    @BeforeEach
    void setUp() {
    }

    @Test
    void createChatRoom_WithValidData_ShouldCreateRoom() {
        // Given
        String name = "Test Room";
        String description = "Test Description";
        
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(invocation -> {
            ChatRoom room = invocation.getArgument(0);
            room.setId(ROOM_ID);
            return room;
        });

        // When
        ChatRoom result = chatRoomService.createChatRoom(name, description, USER_ID);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(ROOM_ID);
        assertThat(result.getName()).isEqualTo(name);
        assertThat(result.getDescription()).isEqualTo(description);
        assertThat(result.getCreatorId()).isEqualTo(USER_ID);
        assertThat(result.getMemberIds()).contains(USER_ID);
        
        verify(chatRoomRepository).save(any(ChatRoom.class));
    }

    @Test
    void getChatRoom_WithExistingRoom_ShouldReturnRoom() {
        // Given
        ChatRoom room = ChatRoom.builder()
                .id(ROOM_ID)
                .name("Test Room")
                .creatorId(USER_ID)
                .memberIds(new HashSet<>(Arrays.asList(USER_ID)))
                .build();
        
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        // When
        Optional<ChatRoom> result = chatRoomService.getChatRoom(ROOM_ID);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Test Room");
    }

    @Test
    void getChatRoom_WithNonExistingRoom_ShouldReturnEmpty() {
        // Given
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

        // When
        Optional<ChatRoom> result = chatRoomService.getChatRoom(ROOM_ID);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void addMember_WithExistingRoom_ShouldAddMember() {
        // Given
        ChatRoom room = ChatRoom.builder()
                .id(ROOM_ID)
                .name("Test Room")
                .memberIds(new HashSet<>(Arrays.asList(USER_ID)))
                .build();
        
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(room);

        // When
        ChatRoom result = chatRoomService.addMember(ROOM_ID, "newUser");

        // Then
        assertThat(result.getMemberIds()).contains("newUser", USER_ID);
        verify(chatRoomRepository).save(room);
    }

    @Test
    void addMember_WithNonExistingRoom_ShouldThrowException() {
        // Given
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> chatRoomService.addMember(ROOM_ID, "newUser"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Chat room not found");
    }

    @Test
    void removeMember_WithExistingRoom_ShouldRemoveMember() {
        // Given
        ChatRoom room = ChatRoom.builder()
                .id(ROOM_ID)
                .name("Test Room")
                .memberIds(new HashSet<>(Arrays.asList(USER_ID, "otherUser")))
                .build();
        
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(room);

        // When
        ChatRoom result = chatRoomService.removeMember(ROOM_ID, "otherUser");

        // Then
        assertThat(result.getMemberIds()).contains(USER_ID);
        assertThat(result.getMemberIds()).doesNotContain("otherUser");
    }

    @Test
    void addMessage_WithExistingRoom_ShouldAddMessage() {
        // Given
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id("msg123")
                .content("Test message")
                .senderId(USER_ID)
                .timestamp(Instant.now())
                .build();
        
        ChatRoom room = ChatRoom.builder()
                .id(ROOM_ID)
                .name("Test Room")
                .messages(new ArrayList<>())
                .memberIds(new HashSet<>(Arrays.asList(USER_ID)))
                .build();
        
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(room);

        // When
        ChatRoom result = chatRoomService.addMessage(ROOM_ID, message);

        // Then
        assertThat(result.getMessages()).hasSize(1);
        assertThat(result.getMessages().get(0).getContent()).isEqualTo("Test message");
    }

    @Test
    void updateMessage_WithExistingMessage_ShouldUpdateContent() {
        // Given
        String messageId = "msg123";
        ChatRoom.Message existingMessage = ChatRoom.Message.builder()
                .id(messageId)
                .content("Old content")
                .senderId(USER_ID)
                .timestamp(Instant.now())
                .build();
        
        ChatRoom.Message updatedMessage = ChatRoom.Message.builder()
                .id(messageId)
                .content("Updated content")
                .senderId(USER_ID)
                .timestamp(Instant.now())
                .build();
        
        ChatRoom room = ChatRoom.builder()
                .id(ROOM_ID)
                .name("Test Room")
                .messages(new ArrayList<>(Arrays.asList(existingMessage)))
                .memberIds(new HashSet<>(Arrays.asList(USER_ID)))
                .build();
        
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(room);

        // When
        ChatRoom result = chatRoomService.updateMessage(ROOM_ID, updatedMessage);

        // Then
        assertThat(result.getMessages().get(0).getContent()).isEqualTo("Updated content");
    }

    @Test
    void deleteChatRoom_ShouldDeleteRoom() {
        // When
        chatRoomService.deleteChatRoom(ROOM_ID);

        // Then
        verify(chatRoomRepository).deleteById(ROOM_ID);
    }

    @Test
    void getUserChatRooms_ShouldReturnUserRooms() {
        // Given
        ChatRoom room1 = ChatRoom.builder().id("room1").name("Room 1").build();
        ChatRoom room2 = ChatRoom.builder().id("room2").name("Room 2").build();
        
        when(chatRoomRepository.findByMemberIdsContaining(USER_ID))
                .thenReturn(Arrays.asList(room1, room2));

        // When
        var result = chatRoomService.getUserChatRooms(USER_ID);

        // Then
        assertThat(result).hasSize(2);
    }
}
