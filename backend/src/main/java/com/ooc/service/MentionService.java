package com.ooc.service;

import com.ooc.entity.*;
import com.ooc.repository.MentionRecordRepository;
import com.ooc.repository.UserMentionSettingsRepository;
import com.ooc.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MentionService {

    private final MentionRecordRepository mentionRecordRepository;
    private final UserMentionSettingsRepository settingsRepository;
    private final UserService userService;
    private final ChatRoomService chatRoomService;
    private final ChatWebSocketHandler chatWebSocketHandler;

    // @username 或 @"复杂昵称" 的正则
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([\\w\\u4e00-\\u9fa5]+|\"[^\"]+\")");
    private static final Pattern MENTION_ALL_PATTERN = Pattern.compile("@(all|everyone|所有人)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MENTION_HERE_PATTERN = Pattern.compile("@(here|在线)", Pattern.CASE_INSENSITIVE);

    /**
     * 解析消息中的@提及
     */
    public MentionParseResult parseMentions(String content, String roomId) {
        Set<ChatRoom.Message.Mention> mentions = new HashSet<>();
        boolean mentionAll = false;
        boolean mentionHere = false;

        // 检查@所有人
        if (MENTION_ALL_PATTERN.matcher(content).find()) {
            mentionAll = true;
        }

        // 检查@在线
        if (MENTION_HERE_PATTERN.matcher(content).find()) {
            mentionHere = true;
        }

        // 解析具体用户@（如果不是@所有人）
        if (!mentionAll) {
            Matcher matcher = MENTION_PATTERN.matcher(content);
            Set<String> mentionedNames = new HashSet<>();

            while (matcher.find()) {
                String name = matcher.group(1).replace("\"", "");
                mentionedNames.add(name);
            }

            // 查询房间成员并匹配
            Optional<ChatRoom> roomOpt = chatRoomService.getChatRoom(roomId);
            if (roomOpt.isPresent()) {
                ChatRoom room = roomOpt.get();
                for (String memberId : room.getMemberIds()) {
                    try {
                        User user = userService.getUserById(memberId);
                        if (user != null && mentionedNames.contains(user.getNickname())) {
                            mentions.add(ChatRoom.Message.Mention.builder()
                                    .userId(user.getId())
                                    .userName(user.getNickname())
                                    .build());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to get user info for member {}: {}", memberId, e.getMessage());
                    }
                }
            }
        }

        return MentionParseResult.builder()
                .mentions(new ArrayList<>(mentions))
                .mentionAll(mentionAll)
                .mentionHere(mentionHere)
                .build();
    }

    /**
     * 创建@提及记录并发送通知
     */
    public void processMentions(ChatRoom.Message message, String roomId, String roomName) {
        // 检查是否是 OpenClaw 消息
        if (message.isFromOpenClaw()) {
            return;
        }

        User mentioner = null;
        try {
            mentioner = userService.getUserById(message.getSenderId());
        } catch (Exception e) {
            log.warn("Failed to get mentioner info: {}", e.getMessage());
        }

        final User finalMentioner = mentioner;

        // 处理@所有人
        if (message.isMentionAll()) {
            Optional<ChatRoom> roomOpt = chatRoomService.getChatRoom(roomId);
            roomOpt.ifPresent(room -> {
                for (String memberId : room.getMemberIds()) {
                    if (!memberId.equals(message.getSenderId())) {
                        createMentionRecord(message, memberId, roomId, roomName, finalMentioner);
                    }
                }
            });
            return;
        }

        // 处理具体用户@
        if (message.getMentions() != null) {
            for (ChatRoom.Message.Mention mention : message.getMentions()) {
                createMentionRecord(message, mention.getUserId(), roomId, roomName, finalMentioner);
            }
        }
    }

    private void createMentionRecord(ChatRoom.Message message, String mentionedUserId,
                                     String roomId, String roomName, User mentioner) {
        // 检查用户设置
        UserMentionSettings settings = getOrCreateSettings(mentionedUserId);

        // 检查是否屏蔽了该用户
        if (mentioner != null && settings.getBlockedUserIds().contains(mentioner.getId())) {
            return;
        }

        // 检查是否屏蔽了该房间
        if (settings.getMutedRoomIds().contains(roomId)) {
            return;
        }

        // 检查免打扰
        if (isInDoNotDisturb(settings)) {
            return;
        }

        // 检查@所有人设置
        if (message.isMentionAll() && !settings.isNotifyOnMentionAll()) {
            return;
        }

        // 检查频率限制（同一发送者在5分钟内@同一用户超过3次）
        Instant fiveMinutesAgo = Instant.now().minusSeconds(300);
        List<MentionRecord> recentMentions = mentionRecordRepository
                .findRecentMentionsByUser(message.getSenderId(), mentionedUserId, fiveMinutesAgo);
        if (recentMentions.size() >= 3) {
            log.warn("Mention rate limit exceeded for sender {} to user {}",
                    message.getSenderId(), mentionedUserId);
            return;
        }

        // 创建记录
        MentionRecord record = MentionRecord.builder()
                .messageId(message.getId())
                .mentionedUserId(mentionedUserId)
                .mentionedUserName(getUserNickname(mentionedUserId))
                .mentionerUserId(message.getSenderId())
                .mentionerUserName(message.getSenderName())
                .roomId(roomId)
                .roomName(roomName)
                .messageContent(message.getContent().substring(0, Math.min(200, message.getContent().length())))
                .isRead(false)
                .build();

        mentionRecordRepository.save(record);
        log.debug("Created mention record: {}", record.getId());

        // 发送实时 WebSocket 通知
        if (settings.isNotifyOnMention()) {
            chatWebSocketHandler.sendMentionNotification(
                    mentionedUserId,
                    roomId,
                    roomName,
                    message.getSenderName(),
                    message.getContent()
            );
        }

        // 推送通知（后续可接入 FCM/APNs）
        if (settings.isPushNotification()) {
            log.debug("Push notification would be sent to user {} (not implemented)", mentionedUserId);
        }
    }

    private boolean isInDoNotDisturb(UserMentionSettings settings) {
        if (!settings.isDoNotDisturb()) {
            return false;
        }

        if (settings.getDndStartTime() == null || settings.getDndEndTime() == null) {
            return true;
        }

        LocalTime now = LocalTime.now(ZoneId.systemDefault());
        LocalTime start = LocalTime.parse(settings.getDndStartTime());
        LocalTime end = LocalTime.parse(settings.getDndEndTime());

        if (start.isBefore(end)) {
            return !now.isBefore(start) && !now.isAfter(end);
        } else {
            // 跨午夜情况
            return !now.isBefore(start) || !now.isAfter(end);
        }
    }

    private String getUserNickname(String userId) {
        try {
            User user = userService.getUserById(userId);
            return user != null ? user.getNickname() : userId;
        } catch (Exception e) {
            return userId;
        }
    }

    public UserMentionSettings getOrCreateSettings(String userId) {
        return settingsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserMentionSettings settings = UserMentionSettings.builder()
                            .userId(userId)
                            .notifyOnMention(true)
                            .notifyOnMentionAll(true)
                            .emailNotification(false)
                            .pushNotification(true)
                            .doNotDisturb(false)
                            .mutedRoomIds(new HashSet<>())
                            .blockedUserIds(new HashSet<>())
                            .updatedAt(Instant.now())
                            .build();
                    return settingsRepository.save(settings);
                });
    }

    public UserMentionSettings updateSettings(String userId, UserMentionSettings newSettings) {
        UserMentionSettings settings = getOrCreateSettings(userId);
        settings.setNotifyOnMention(newSettings.isNotifyOnMention());
        settings.setNotifyOnMentionAll(newSettings.isNotifyOnMentionAll());
        settings.setEmailNotification(newSettings.isEmailNotification());
        settings.setPushNotification(newSettings.isPushNotification());
        settings.setDoNotDisturb(newSettings.isDoNotDisturb());
        settings.setDndStartTime(newSettings.getDndStartTime());
        settings.setDndEndTime(newSettings.getDndEndTime());
        if (newSettings.getMutedRoomIds() != null) {
            settings.setMutedRoomIds(newSettings.getMutedRoomIds());
        }
        if (newSettings.getBlockedUserIds() != null) {
            settings.setBlockedUserIds(newSettings.getBlockedUserIds());
        }
        settings.setUpdatedAt(Instant.now());
        return settingsRepository.save(settings);
    }

    public List<MentionRecord> getUnreadMentions(String userId) {
        return mentionRecordRepository.findByMentionedUserIdAndIsReadFalse(userId);
    }

    public Page<MentionRecord> getMentionHistory(String userId, Pageable pageable) {
        return mentionRecordRepository.findByMentionedUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public long getUnreadCount(String userId) {
        return mentionRecordRepository.countByMentionedUserIdAndIsReadFalse(userId);
    }

    public void markAsRead(String mentionId) {
        mentionRecordRepository.findById(mentionId).ifPresent(record -> {
            record.setRead(true);
            record.setReadAt(Instant.now());
            mentionRecordRepository.save(record);
        });
    }

    public void markAllAsRead(String userId) {
        List<MentionRecord> unread = mentionRecordRepository.findByMentionedUserIdAndIsReadFalse(userId);
        for (MentionRecord record : unread) {
            record.setRead(true);
            record.setReadAt(Instant.now());
        }
        mentionRecordRepository.saveAll(unread);
    }

    @lombok.Data
    @lombok.Builder
    public static class MentionParseResult {
        private List<ChatRoom.Message.Mention> mentions;
        private boolean mentionAll;
        private boolean mentionHere;
    }
}
