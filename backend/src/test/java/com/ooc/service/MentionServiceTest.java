package com.ooc.service;

import com.ooc.entity.*;
import com.ooc.repository.MentionRecordRepository;
import com.ooc.repository.UserMentionSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MentionServiceTest {

    @Mock
    private MentionRecordRepository mentionRecordRepository;

    @Mock
    private UserMentionSettingsRepository settingsRepository;

    @Mock
    private UserService userService;

    @Mock
    private ChatRoomService chatRoomService;

    @InjectMocks
    private MentionService mentionService;

    private static final String USER_ID = "user123";
    private static final String USER_NICKNAME = "TestUser";
    private static final String ROOM_ID = "room123";

    @BeforeEach
    void setUp() {
    }

    @Test
    void parseMentions_WithUserMention_ShouldParseCorrectly() {
        // Given
        String content = "Hello @\"TestUser\" how are you?";
        
        ChatRoom room = ChatRoom.builder()
                .id(ROOM_ID)
                .memberIds(new HashSet<>(Arrays.asList(USER_ID)))
                .build();
        
        User user = User.builder()
                .id(USER_ID)
                .nickname(USER_NICKNAME)
                .build();
        
        when(chatRoomService.getChatRoom(ROOM_ID)).thenReturn(Optional.of(room));
        when(userService.getUserById(USER_ID)).thenReturn(user);

        // When
        MentionService.MentionParseResult result = mentionService.parseMentions(content, ROOM_ID);

        // Then
        assertThat(result.getMentions()).hasSize(1);
        assertThat(result.getMentions().get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(result.getMentions().get(0).getUserName()).isEqualTo(USER_NICKNAME);
        assertThat(result.isMentionAll()).isFalse();
    }

    @Test
    void parseMentions_WithMentionAll_ShouldDetectMentionAll() {
        // Given
        String content = "Hello @all this is important!";

        // When
        MentionService.MentionParseResult result = mentionService.parseMentions(content, ROOM_ID);

        // Then
        assertThat(result.isMentionAll()).isTrue();
        assertThat(result.getMentions()).isEmpty();
    }

    @Test
    void parseMentions_WithMentionHere_ShouldDetectMentionHere() {
        // Given
        String content = "Hello @here urgent!";

        // When
        MentionService.MentionParseResult result = mentionService.parseMentions(content, ROOM_ID);

        // Then
        assertThat(result.isMentionHere()).isTrue();
    }

    @Test
    void parseMentions_WithOpenClawMessage_ShouldSkipProcessing() {
        // Given
        ChatRoom.Message message = ChatRoom.Message.builder()
                .id("msg123")
                .content("Hello @all")
                .senderId("openclaw")
                .fromOpenClaw(true)
                .mentionAll(true)
                .build();

        // When
        mentionService.processMentions(message, ROOM_ID, "Test Room");

        // Then - no interactions with repositories
        verifyNoInteractions(mentionRecordRepository);
    }

    @Test
    void getOrCreateSettings_WithNewUser_ShouldCreateDefaultSettings() {
        // Given
        when(settingsRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(settingsRepository.save(any(UserMentionSettings.class))).thenAnswer(invocation -> {
            UserMentionSettings settings = invocation.getArgument(0);
            settings.setId("settings123");
            return settings;
        });

        // When
        UserMentionSettings result = mentionService.getOrCreateSettings(USER_ID);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.isNotifyOnMention()).isTrue();
        assertThat(result.isNotifyOnMentionAll()).isTrue();
        assertThat(result.isPushNotification()).isTrue();
        verify(settingsRepository).save(any(UserMentionSettings.class));
    }

    @Test
    void getOrCreateSettings_WithExistingUser_ShouldReturnExistingSettings() {
        // Given
        UserMentionSettings existingSettings = UserMentionSettings.builder()
                .id("settings123")
                .userId(USER_ID)
                .notifyOnMention(true)
                .build();
        
        when(settingsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existingSettings));

        // When
        UserMentionSettings result = mentionService.getOrCreateSettings(USER_ID);

        // Then
        assertThat(result).isEqualTo(existingSettings);
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void getUnreadMentions_ShouldReturnUnreadRecords() {
        // Given
        MentionRecord record1 = MentionRecord.builder().id("r1").isRead(false).build();
        MentionRecord record2 = MentionRecord.builder().id("r2").isRead(false).build();
        
        when(mentionRecordRepository.findByMentionedUserIdAndIsReadFalse(USER_ID))
                .thenReturn(Arrays.asList(record1, record2));

        // When
        List<MentionRecord> result = mentionService.getUnreadMentions(USER_ID);

        // Then
        assertThat(result).hasSize(2);
    }

    @Test
    void getUnreadCount_ShouldReturnCount() {
        // Given
        when(mentionRecordRepository.countByMentionedUserIdAndIsReadFalse(USER_ID))
                .thenReturn(5L);

        // When
        long result = mentionService.getUnreadCount(USER_ID);

        // Then
        assertThat(result).isEqualTo(5);
    }

    @Test
    void markAsRead_WithExistingRecord_ShouldMarkRead() {
        // Given
        String mentionId = "mention123";
        MentionRecord record = MentionRecord.builder()
                .id(mentionId)
                .isRead(false)
                .build();
        
        when(mentionRecordRepository.findById(mentionId)).thenReturn(Optional.of(record));
        when(mentionRecordRepository.save(any(MentionRecord.class))).thenReturn(record);

        // When
        mentionService.markAsRead(mentionId);

        // Then
        assertThat(record.isRead()).isTrue();
        assertThat(record.getReadAt()).isNotNull();
        verify(mentionRecordRepository).save(record);
    }

    @Test
    void markAllAsRead_ShouldMarkAllUnreadAsRead() {
        // Given
        MentionRecord record1 = MentionRecord.builder().id("r1").isRead(false).build();
        MentionRecord record2 = MentionRecord.builder().id("r2").isRead(false).build();
        
        when(mentionRecordRepository.findByMentionedUserIdAndIsReadFalse(USER_ID))
                .thenReturn(Arrays.asList(record1, record2));
        when(mentionRecordRepository.saveAll(anyList())).thenReturn(Arrays.asList(record1, record2));

        // When
        mentionService.markAllAsRead(USER_ID);

        // Then
        assertThat(record1.isRead()).isTrue();
        assertThat(record2.isRead()).isTrue();
        verify(mentionRecordRepository).saveAll(anyList());
    }

    @Test
    void updateSettings_ShouldUpdateAndSaveSettings() {
        // Given
        UserMentionSettings existingSettings = UserMentionSettings.builder()
                .id("settings123")
                .userId(USER_ID)
                .notifyOnMention(true)
                .build();
        
        UserMentionSettings newSettings = UserMentionSettings.builder()
                .notifyOnMention(false)
                .notifyOnMentionAll(false)
                .emailNotification(true)
                .doNotDisturb(true)
                .dndStartTime("22:00")
                .dndEndTime("08:00")
                .build();
        
        when(settingsRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existingSettings));
        when(settingsRepository.save(any(UserMentionSettings.class))).thenReturn(existingSettings);

        // When
        UserMentionSettings result = mentionService.updateSettings(USER_ID, newSettings);

        // Then
        assertThat(result.isNotifyOnMention()).isFalse();
        assertThat(result.isEmailNotification()).isTrue();
        assertThat(result.isDoNotDisturb()).isTrue();
    }
}
