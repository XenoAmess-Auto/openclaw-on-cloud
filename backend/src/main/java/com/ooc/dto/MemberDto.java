package com.ooc.dto;

import com.ooc.entity.User;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Data
@Builder
public class MemberDto {
    private String id;
    private String username;
    private String email;
    private String avatar;
    private boolean isCreator;
    private Instant joinedAt;

    public static MemberDto fromEntity(User user, String creatorId) {
        return MemberDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .isCreator(user.getUsername().equals(creatorId))
                .build();
    }
}
