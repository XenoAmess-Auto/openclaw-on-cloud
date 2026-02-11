package com.ooc.controller;

import com.ooc.entity.User;
import com.ooc.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository userRepository;

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> getAllUsers() {
        List<User> users = userRepository.findAll();
        List<UserDto> userDtos = users.stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(userDtos);
    }

    @PatchMapping("/users/{userId}/status")
    public ResponseEntity<Void> updateUserStatus(
            @PathVariable String userId,
            @RequestBody Map<String, Boolean> request) {
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        // 不能禁用管理员
        if (user.getRoles().contains("ROLE_ADMIN")) {
            throw new RuntimeException("Cannot modify admin user");
        }
        
        user.setEnabled(request.get("enabled"));
        userRepository.save(user);
        
        return ResponseEntity.ok().build();
    }

    private UserDto toDto(User user) {
        return new UserDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRoles(),
                user.isEnabled(),
                user.getCreatedAt()
        );
    }

    public record UserDto(
            String id,
            String username,
            String email,
            java.util.Set<String> roles,
            boolean enabled,
            java.time.Instant createdAt
    ) {}
}
