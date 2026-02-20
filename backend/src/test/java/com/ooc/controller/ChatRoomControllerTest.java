package com.ooc.controller;

import com.ooc.entity.ChatRoom;
import com.ooc.entity.User;
import com.ooc.security.JwtTokenProvider;
import com.ooc.service.ChatRoomService;
import com.ooc.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ChatRoomController 集成测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ChatRoomControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ChatRoomService chatRoomService;

    @MockBean
    private UserService userService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private User mockUser;
    private ChatRoom mockRoom;

    @BeforeEach
    void setUp() {
        mockUser = User.builder()
                .id("user-123")
                .username("testuser")
                .nickname("Test User")
                .avatar("/avatars/test.png")
                .build();

        mockRoom = ChatRoom.builder()
                .id("room-123")
                .name("Test Room")
                .description("Test Description")
                .creatorId("user-123")
                .createdAt(Instant.now())
                .members(List.of("user-123"))
                .build();

        when(userService.getUserByUsername(anyString())).thenReturn(mockUser);
    }

    @Test
    @WithMockUser(username = "testuser")
    void getChatRooms_ShouldReturnRooms() {
        // Given
        when(chatRoomService.getUserChatRooms(anyString())).thenReturn(List.of(mockRoom));

        // When & Then
        webTestClient.get()
                .uri("/api/chat-rooms")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ChatRoom.class)
                .hasSize(1)
                .contains(mockRoom);
    }

    @Test
    @WithMockUser(username = "testuser")
    void getChatRoom_WithValidId_ShouldReturnRoom() {
        // Given
        when(chatRoomService.getChatRoom("room-123")).thenReturn(Optional.of(mockRoom));

        // When & Then
        webTestClient.get()
                .uri("/api/chat-rooms/room-123")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatRoom.class)
                .isEqualTo(mockRoom);
    }

    @Test
    @WithMockUser(username = "testuser")
    void getChatRoom_WithInvalidId_ShouldReturnNotFound() {
        // Given
        when(chatRoomService.getChatRoom("invalid-id")).thenReturn(Optional.empty());

        // When & Then
        webTestClient.get()
                .uri("/api/chat-rooms/invalid-id")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockUser(username = "testuser")
    void createChatRoom_WithValidData_ShouldReturnCreatedRoom() {
        // Given
        ChatRoomController.CreateRoomRequest request = new ChatRoomController.CreateRoomRequest(
                "New Room", "Description", List.of()
        );
        when(chatRoomService.createChatRoom(anyString(), anyString(), anyString(), any()))
                .thenReturn(mockRoom);

        // When & Then
        webTestClient.post()
                .uri("/api/chat-rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatRoom.class)
                .isEqualTo(mockRoom);
    }

    @Test
    @WithMockUser(username = "testuser")
    void getMessages_WithValidRoomId_ShouldReturnMessages() {
        // Given
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id("msg-1")
                .senderId("user-123")
                .senderName("Test User")
                .content("Hello")
                .timestamp(Instant.now())
                .build();
        
        mockRoom.setMessages(List.of(message));
        when(chatRoomService.getChatRoom("room-123")).thenReturn(Optional.of(mockRoom));

        // When & Then
        webTestClient.get()
                .uri("/api/chat-rooms/room-123/messages")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ChatRoom.Message.class)
                .hasSize(1);
    }

    @Test
    @WithMockUser(username = "testuser")
    void joinRoom_WithValidId_ShouldReturnSuccess() {
        // Given
        when(chatRoomService.joinChatRoom("room-123", "user-123")).thenReturn(true);

        // When & Then
        webTestClient.post()
                .uri("/api/chat-rooms/room-123/join")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true);
    }

    @Test
    @WithMockUser(username = "testuser")
    void leaveRoom_WithValidId_ShouldReturnSuccess() {
        // Given
        when(chatRoomService.leaveChatRoom("room-123", "user-123")).thenReturn(true);

        // When & Then
        webTestClient.post()
                .uri("/api/chat-rooms/room-123/leave")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true);
    }

    @Test
    @WithMockUser(username = "testuser")
    void dismissRoom_WithCreatorRole_ShouldReturnSuccess() {
        // Given
        when(chatRoomService.dismissChatRoom("room-123", "user-123")).thenReturn(true);

        // When & Then
        webTestClient.post()
                .uri("/api/chat-rooms/room-123/dismiss")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true);
    }
}
