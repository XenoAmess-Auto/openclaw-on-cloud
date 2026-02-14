package com.ooc.controller;

import com.ooc.dto.AuthRequest;
import com.ooc.dto.AuthResponse;
import com.ooc.dto.RegisterRequest;
import com.ooc.entity.User;
import com.ooc.security.JwtTokenProvider;
import com.ooc.security.RsaKeyProvider;
import com.ooc.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserService userService;
    private final RsaKeyProvider rsaKeyProvider;

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> getPublicKey() {
        return ResponseEntity.ok(Map.of("publicKey", rsaKeyProvider.getPublicKeyBase64()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        // 解密密码
        String decryptedPassword = rsaKeyProvider.decrypt(request.getPassword());
        
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), decryptedPassword));

        String token = jwtTokenProvider.generateToken(authentication);
        User user = userService.getUserByUsername(request.getUsername());

        return ResponseEntity.ok(AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .roles(user.getRoles())
                .build());
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        // 解密密码
        String decryptedPassword = rsaKeyProvider.decrypt(request.getPassword());
        RegisterRequest decryptedRequest = new RegisterRequest();
        decryptedRequest.setUsername(request.getUsername());
        decryptedRequest.setNickname(request.getNickname());
        decryptedRequest.setEmail(request.getEmail());
        decryptedRequest.setPassword(decryptedPassword);
        
        User user = userService.registerUser(decryptedRequest);
        
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(user.getUsername(), decryptedPassword));
        String token = jwtTokenProvider.generateToken(authentication);

        return ResponseEntity.ok(AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .roles(user.getRoles())
                .build());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.substring(7);
        String username = jwtTokenProvider.extractUsername(token);
        User user = userService.getUserByUsername(username);
        
        // 这里简化处理，实际应该重新生成token
        return ResponseEntity.ok(AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .roles(user.getRoles())
                .build());
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        String username = authentication.getName();
        User user = userService.getUserByUsername(username);
        String token = jwtTokenProvider.generateToken(authentication);

        return ResponseEntity.ok(AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .roles(user.getRoles())
                .build());
    }
}
