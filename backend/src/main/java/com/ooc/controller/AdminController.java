package com.ooc.controller;

import com.ooc.dto.RegisterRequest;
import com.ooc.entity.User;
import com.ooc.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> getAllUsers() {
        List<User> users = userRepository.findAll();
        List<UserDto> userDtos = users.stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(userDtos);
    }

    @PostMapping("/users")
    public ResponseEntity<UserDto> createUser(@RequestBody CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new RuntimeException("Username already exists");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new RuntimeException("Email already exists");
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .enabled(request.enabled())
                .roles(request.isAdmin() ? Set.of("ROLE_ADMIN", "ROLE_USER") : Set.of("ROLE_USER"))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        User saved = userRepository.save(user);
        return ResponseEntity.ok(toDto(saved));
    }

    @PutMapping("/users/{userId}")
    public ResponseEntity<UserDto> updateUser(
            @PathVariable String userId,
            @RequestBody UpdateUserRequest request) {
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 不能修改 admin 用户
        if ("admin".equals(user.getUsername())) {
            throw new RuntimeException("Cannot modify admin user");
        }

        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.password()));
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        if (request.roles() != null) {
            user.setRoles(request.roles());
        }
        user.setUpdatedAt(Instant.now());

        User saved = userRepository.save(user);
        return ResponseEntity.ok(toDto(saved));
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 不能删除 admin 用户
        if ("admin".equals(user.getUsername())) {
            throw new RuntimeException("Cannot delete admin user");
        }

        userRepository.delete(user);
        return ResponseEntity.ok().build();
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
            Set<String> roles,
            boolean enabled,
            Instant createdAt
    ) {}

    public record CreateUserRequest(
            String username,
            String email,
            String password,
            boolean enabled,
            boolean isAdmin
    ) {}

    public record UpdateUserRequest(
            String email,
            String password,
            Boolean enabled,
            Set<String> roles
    ) {}
}
