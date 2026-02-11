package com.ooc.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class AuthResponse {
    private String token;
    private String userId;
    private String username;
    private String email;
    private String avatar;
    private Set<String> roles;
}
