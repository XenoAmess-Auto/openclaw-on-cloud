package com.ooc.controller;

import com.ooc.dto.UpdateUserRequest;
import com.ooc.entity.User;
import com.ooc.service.UserService;
import com.ooc.websocket.ChatWebSocketHandler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ChatWebSocketHandler chatWebSocketHandler;

    @GetMapping("/by-username/{username}")
    public ResponseEntity<Map<String, String>> getUserByUsername(@PathVariable String username) {
        User user = userService.getUserByUsername(username);
        return ResponseEntity.ok(Map.of(
            "id", user.getId(),
            "username", user.getUsername(),
            "email", user.getEmail()
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        User user = userService.getUserByUsername(username);
        return ResponseEntity.ok(Map.of(
            "id", user.getId(),
            "username", user.getUsername(),
            "nickname", user.getNickname() != null ? user.getNickname() : user.getUsername(),
            "email", user.getEmail(),
            "avatar", user.getAvatar() != null ? user.getAvatar() : "",
            "roles", user.getRoles(),
            "enabled", user.isEnabled()
        ));
    }

    @PutMapping("/me")
    public ResponseEntity<Map<String, Object>> updateCurrentUser(
            @Valid @RequestBody UpdateUserRequest request,
            Authentication authentication) {
        String username = authentication.getName();
        User updatedUser = userService.updateUser(username, request);
        
        // 如果更新了头像，同步更新 WebSocket 中的 userInfo
        if (request.getAvatar() != null) {
            chatWebSocketHandler.updateUserAvatar(updatedUser.getId(), request.getAvatar());
        }
        
        return ResponseEntity.ok(Map.of(
            "id", updatedUser.getId(),
            "username", updatedUser.getUsername(),
            "nickname", updatedUser.getNickname() != null ? updatedUser.getNickname() : updatedUser.getUsername(),
            "email", updatedUser.getEmail(),
            "avatar", updatedUser.getAvatar() != null ? updatedUser.getAvatar() : "",
            "roles", updatedUser.getRoles()
        ));
    }
}
