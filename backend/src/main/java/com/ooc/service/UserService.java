package com.ooc.service;

import com.ooc.dto.RegisterRequest;
import com.ooc.dto.UpdateUserRequest;
import com.ooc.entity.User;
import com.ooc.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public User registerUser(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }

        // Use nickname if provided, otherwise use username
        String nickname = request.getNickname();
        if (nickname == null || nickname.isBlank()) {
            nickname = request.getUsername();
        }

        User user = User.builder()
                .username(request.getUsername())
                .nickname(nickname)
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .enabled(true)
                .roles(Set.of("ROLE_USER"))
                .build();

        return userRepository.save(user);
    }

    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public User getUserById(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public User updateUserAvatar(String userId, String avatarUrl) {
        User user = getUserById(userId);
        user.setAvatar(avatarUrl);
        return userRepository.save(user);
    }

    public User updateUser(String username, UpdateUserRequest request) {
        User user = getUserByUsername(username);
        
        // 更新昵称
        if (request.getNickname() != null) {
            String nickname = request.getNickname().trim();
            if (nickname.isEmpty()) {
                nickname = user.getUsername();
            }
            user.setNickname(nickname);
        }
        
        // 更新邮箱
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new RuntimeException("Email already exists");
            }
            user.setEmail(request.getEmail());
        }
        
        // 更新头像
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }
        
        // 修改密码
        if (request.getNewPassword() != null && !request.getNewPassword().isEmpty()) {
            if (request.getCurrentPassword() == null || 
                !passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
                throw new RuntimeException("Current password is incorrect");
            }
            user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        }
        
        return userRepository.save(user);
    }
}
