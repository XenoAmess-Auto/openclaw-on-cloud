package com.ooc.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_mention_settings")
public class UserMentionSettings {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    @Builder.Default
    private boolean notifyOnMention = true;

    @Builder.Default
    private boolean notifyOnMentionAll = true;

    @Builder.Default
    private boolean emailNotification = false;

    @Builder.Default
    private boolean pushNotification = true;

    @Builder.Default
    private boolean doNotDisturb = false;

    // 免打扰时段
    private String dndStartTime; // "22:00"
    private String dndEndTime;   // "08:00"

    @Builder.Default
    private Set<String> mutedRoomIds = new HashSet<>();

    @Builder.Default
    private Set<String> blockedUserIds = new HashSet<>();

    private Instant updatedAt;
}
