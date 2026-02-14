package com.ooc.config;

import com.ooc.entity.User;
import com.ooc.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        String adminUsername = "admin";
        String adminPassword = "admin123";

        // 如果用户存在，删除旧用户重新创建（解决密钥更换后的密码不匹配问题）
        userRepository.findByUsername(adminUsername).ifPresent(existingUser -> {
            log.info("Removing existing admin user to recreate with new encryption key");
            userRepository.delete(existingUser);
        });

        User admin = User.builder()
                .username(adminUsername)
                .email("admin@ooc.local")
                .password(passwordEncoder.encode(adminPassword))
                .enabled(true)
                .roles(Set.of("ROLE_ADMIN", "ROLE_USER"))
                .build();

        userRepository.save(admin);
        log.info("Created default admin user: username={}, password={}", adminUsername, adminPassword);
    }
}
