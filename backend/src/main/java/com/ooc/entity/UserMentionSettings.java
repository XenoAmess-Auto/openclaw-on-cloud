package com.ooc.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("id")
    @Id
    private String id;

    @JsonProperty("userId")
    @Indexed(unique = true)
    private String userId;

    @JsonProperty("notifyOnMention")
    @Builder.Default
    private boolean notifyOnMention = true;

    @JsonProperty("notifyOnMentionAll")
    @Builder.Default
    private boolean notifyOnMentionAll = true;

    @JsonProperty("emailNotification")
    @Builder.Default
    private boolean emailNotification = false;

    @JsonProperty("pushNotification")
    @Builder.Default
    private boolean pushNotification = true;

    @JsonProperty("doNotDisturb")
    @Builder.Default
    private boolean doNotDisturb = false;

    @JsonProperty("dndStartTime")
    private String dndStartTime;

    @JsonProperty("dndEndTime")
    private String dndEndTime;

    @JsonProperty("mutedRoomIds")
    @Builder.Default
    private Set<String> mutedRoomIds = new HashSet<>();

    @JsonProperty("blockedUserIds")
    @Builder.Default
    private Set<String> blockedUserIds = new HashSet<>();

    @JsonProperty("updatedAt")
    private Instant updatedAt;
}
