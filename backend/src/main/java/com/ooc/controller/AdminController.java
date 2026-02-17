package com.ooc.controller;

import com.ooc.entity.BotUserConfig;
import com.ooc.entity.User;
import com.ooc.repository.UserRepository;
import com.ooc.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ChatWebSocketHandler chatWebSocketHandler;

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> getAllUsers() {
        // 只返回普通用户，不包含机器人用户
        List<User> users = userRepository.findAll().stream()
                .filter(u -> !u.isBot())
                .toList();
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
                .isBot(false)
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

    /**
     * 发送测试消息到指定房间
     */
    @PostMapping("/rooms/{roomId}/test-message")
    public ResponseEntity<Void> sendTestMessage(
            @PathVariable String roomId,
            @RequestBody TestMessageRequest request) {
        String content = request.content() != null ? request.content() : getDefaultTestContent();
        chatWebSocketHandler.sendSystemMessage(roomId, content);
        log.info("Admin sent test message to room: {}", roomId);
        return ResponseEntity.ok().build();
    }

    private String getDefaultTestContent() {
        return "# Markdown 渲染测试\n\n" +
               "## 代码块测试\n\n" +
               "### JavaScript\n" +
               "```javascript\n" +
               "function fibonacci(n) {\n" +
               "  if (n <= 1) return n;\n" +
               "  return fibonacci(n - 1) + fibonacci(n - 2);\n" +
               "}\n" +
               "const result = fibonacci(10);\n" +
               "console.log(`F(10) = ${result}`);\n" +
               "```\n\n" +
               "### TypeScript\n" +
               "```typescript\n" +
               "interface User {\n" +
               "  id: number;\n" +
               "  name: string;\n" +
               "  email: string;\n" +
               "}\n\n" +
               "class UserService {\n" +
               "  async getUser(id: number): Promise<User> {\n" +
               "    const response = await fetch(`/api/users/${id}`);\n" +
               "    return response.json();\n" +
               "  }\n" +
               "}\n" +
               "```\n\n" +
               "### Python\n" +
               "```python\n" +
               "def quick_sort(arr):\n" +
               "    if len(arr) <= 1:\n" +
               "        return arr\n" +
               "    pivot = arr[len(arr) // 2]\n" +
               "    left = [x for x in arr if x < pivot]\n" +
               "    middle = [x for x in arr if x == pivot]\n" +
               "    right = [x for x in arr if x > pivot]\n" +
               "    return quick_sort(left) + middle + quick_sort(right)\n" +
               "```\n\n" +
               "### Bash\n" +
               "```bash\n" +
               "#!/bin/bash\n" +
               "echo \"开始部署...\"\n" +
               "git pull origin main\n" +
               "pnpm build\n" +
               "echo \"部署完成!\"\n" +
               "```\n\n" +
               "### JSON\n" +
               "```json\n" +
               "{\n" +
               "  \"name\": \"openclaw-on-cloud\",\n" +
               "  \"version\": \"1.0.0\",\n" +
               "  \"dependencies\": {\n" +
               "    \"vue\": \"^3.5.13\",\n" +
               "    \"marked\": \"^17.0.2\"\n" +
               "  }\n" +
               "}\n" +
               "```\n\n" +
               "### SQL\n" +
               "```sql\n" +
               "SELECT u.id, u.username, COUNT(m.id) as msg_count\n" +
               "FROM users u\n" +
               "LEFT JOIN messages m ON u.id = m.user_id\n" +
               "WHERE u.created_at > '2024-01-01'\n" +
               "GROUP BY u.id\n" +
               "ORDER BY msg_count DESC\n" +
               "LIMIT 10;\n" +
               "```\n\n" +
               "---\n\n" +
               "## 表格测试\n\n" +
               "| 功能 | 状态 | 说明 |\n" +
               "|------|------|------|\n" +
               "| 代码高亮 | ✅ | highlight.js 集成 |\n" +
               "| 复制按钮 | ✅ | 悬停显示 |\n" +
               "| 语言标签 | ✅ | 右上角显示 |\n" +
               "| 深色主题 | ✅ | github-dark |\n\n" +
               "---\n\n" +
               "## 列表测试\n\n" +
               "### 无序列表\n" +
               "- 第一项\n" +
               "- 第二项\n" +
               "  - 嵌套项 1\n" +
               "  - 嵌套项 2\n" +
               "- 第三项\n\n" +
               "### 有序列表\n" +
               "1. 第一步\n" +
               "2. 第二步\n" +
               "3. 第三步\n\n" +
               "---\n\n" +
               "## 其他格式\n\n" +
               "> 这是一段引用块\n" +
               "> 可以用来强调重要信息\n\n" +
               "**粗体文本**、*斜体文本*、~~删除线文本~~\n\n" +
               "行内代码测试：`const x = 42`、`npm install`、`docker compose up`\n\n" +
               "---\n\n" +
               "测试完成！检查代码高亮、复制按钮、语言标签是否正常显示。";
    }

    // ==================== 机器人用户管理 API ====================

    /**
     * 获取所有机器人用户
     */
    @GetMapping("/bots")
    public ResponseEntity<List<BotUserDto>> getAllBotUsers() {
        List<User> bots = userRepository.findAll().stream()
                .filter(User::isBot)
                .toList();
        List<BotUserDto> botDtos = bots.stream()
                .map(this::toBotDto)
                .toList();
        return ResponseEntity.ok(botDtos);
    }

    /**
     * 获取启用的 OpenClaw 机器人配置
     */
    @GetMapping("/bots/openclaw")
    public ResponseEntity<BotUserDto> getOpenClawBot() {
        Optional<User> bot = userRepository.findAll().stream()
                .filter(User::isBot)
                .filter(User::isEnabled)
                .filter(u -> "openclaw".equals(u.getBotType()))
                .findFirst();
        
        return ResponseEntity.ok(bot.map(this::toBotDto)
                .orElse(getDefaultOpenClawBotDto()));
    }

    /**
     * 创建或更新 OpenClaw 机器人
     */
    @PostMapping("/bots/openclaw")
    public ResponseEntity<BotUserDto> saveOpenClawBot(@RequestBody BotUserRequest request) {
        Optional<User> existing = userRepository.findAll().stream()
                .filter(User::isBot)
                .filter(u -> "openclaw".equals(u.getBotType()))
                .findFirst();
        
        User bot;
        if (existing.isPresent()) {
            bot = existing.get();
            bot.setUsername(request.username());
            bot.setAvatar(request.avatarUrl());
            if (request.password() != null && !request.password().isBlank()) {
                bot.setPassword(passwordEncoder.encode(request.password()));
            }
            bot.setEnabled(request.enabled());
            
            BotUserConfig config = bot.getBotConfig();
            if (config == null) {
                config = new BotUserConfig();
            }
            config.setGatewayUrl(request.gatewayUrl());
            config.setSystemPrompt(request.systemPrompt());
            if (request.apiKey() != null && !request.apiKey().isBlank()) {
                config.setApiKey(request.apiKey());
            }
            bot.setBotConfig(config);
            bot.setUpdatedAt(Instant.now());
        } else {
            // 检查用户名是否已存在
            if (userRepository.existsByUsername(request.username())) {
                throw new RuntimeException("Username already exists");
            }
            
            BotUserConfig config = BotUserConfig.builder()
                    .gatewayUrl(request.gatewayUrl())
                    .apiKey(request.apiKey())
                    .systemPrompt(request.systemPrompt())
                    .build();
            
            bot = User.builder()
                    .username(request.username())
                    .email(request.username() + "@bot.local")
                    .password(passwordEncoder.encode(request.password() != null ? request.password() : "botpassword123"))
                    .avatar(request.avatarUrl())
                    .enabled(request.enabled())
                    .roles(Set.of("ROLE_USER"))
                    .isBot(true)
                    .botType("openclaw")
                    .botConfig(config)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        }
        
        User saved = userRepository.save(bot);
        log.info("OpenClaw bot saved: username={}, gatewayUrl={}", saved.getUsername(), 
                saved.getBotConfig() != null ? saved.getBotConfig().getGatewayUrl() : null);
        return ResponseEntity.ok(toBotDto(saved));
    }

    /**
     * 获取单个机器人用户详情
     */
    @GetMapping("/bots/{botId}")
    public ResponseEntity<BotUserDto> getBotUser(@PathVariable String botId) {
        User bot = userRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("Bot not found"));
        
        if (!bot.isBot()) {
            throw new RuntimeException("Not a bot user");
        }
        
        return ResponseEntity.ok(toBotDto(bot));
    }

    /**
     * 创建机器人用户
     */
    @PostMapping("/bots")
    public ResponseEntity<BotUserDto> createBotUser(@RequestBody CreateBotUserRequest request) {
        // 检查用户名是否已存在
        if (userRepository.existsByUsername(request.username())) {
            throw new RuntimeException("Username already exists");
        }
        
        BotUserConfig config = BotUserConfig.builder()
                .gatewayUrl(request.gatewayUrl())
                .apiKey(request.apiKey())
                .systemPrompt(request.systemPrompt() != null ? request.systemPrompt() : "You are a helpful assistant.")
                .build();
        
        User bot = User.builder()
                .username(request.username())
                .email(request.username() + "@bot.local")
                .password(passwordEncoder.encode(request.password() != null ? request.password() : "botpassword123"))
                .avatar(request.avatarUrl())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .roles(Set.of("ROLE_USER"))
                .isBot(true)
                .botType(request.botType() != null ? request.botType() : "openclaw")
                .botConfig(config)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        
        User saved = userRepository.save(bot);
        log.info("Bot created: username={}, type={}", saved.getUsername(), saved.getBotType());
        return ResponseEntity.ok(toBotDto(saved));
    }

    /**
     * 更新机器人用户
     */
    @PutMapping("/bots/{botId}")
    public ResponseEntity<BotUserDto> updateBotUser(
            @PathVariable String botId,
            @RequestBody UpdateBotUserRequest request) {
        
        User bot = userRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("Bot not found"));
        
        if (!bot.isBot()) {
            throw new RuntimeException("Not a bot user");
        }
        
        if (request.username() != null && !request.username().equals(bot.getUsername())) {
            if (userRepository.existsByUsername(request.username())) {
                throw new RuntimeException("Username already exists");
            }
            bot.setUsername(request.username());
            bot.setEmail(request.username() + "@bot.local");
        }
        
        if (request.avatarUrl() != null) {
            bot.setAvatar(request.avatarUrl());
        }
        
        if (request.password() != null && !request.password().isBlank()) {
            bot.setPassword(passwordEncoder.encode(request.password()));
        }
        
        if (request.enabled() != null) {
            bot.setEnabled(request.enabled());
        }
        
        BotUserConfig config = bot.getBotConfig();
        if (config == null) {
            config = new BotUserConfig();
        }
        
        if (request.gatewayUrl() != null) {
            config.setGatewayUrl(request.gatewayUrl());
        }
        
        if (request.systemPrompt() != null) {
            config.setSystemPrompt(request.systemPrompt());
        }
        
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            config.setApiKey(request.apiKey());
        }
        
        bot.setBotConfig(config);
        bot.setUpdatedAt(Instant.now());
        
        User saved = userRepository.save(bot);
        log.info("Bot updated: username={}, type={}", saved.getUsername(), saved.getBotType());
        return ResponseEntity.ok(toBotDto(saved));
    }

    /**
     * 删除机器人用户
     */
    @DeleteMapping("/bots/{botId}")
    public ResponseEntity<Void> deleteBotUser(@PathVariable String botId) {
        User bot = userRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("Bot not found"));
        
        if (!bot.isBot()) {
            throw new RuntimeException("Not a bot user");
        }
        
        userRepository.delete(bot);
        log.info("Bot deleted: {} ({})", bot.getUsername(), bot.getBotType());
        return ResponseEntity.ok().build();
    }

    private BotUserDto getDefaultOpenClawBotDto() {
        return new BotUserDto(
                null,
                "openclaw",
                null,
                "http://localhost:18789",
                null,
                "You are a helpful assistant.",
                true,
                "openclaw",
                null,
                null
        );
    }

    private BotUserDto toBotDto(User bot) {
        BotUserConfig config = bot.getBotConfig();
        return new BotUserDto(
                bot.getId(),
                bot.getUsername(),
                bot.getAvatar(),
                config != null ? config.getGatewayUrl() : null,
                config != null && config.getApiKey() != null ? maskApiKey(config.getApiKey()) : null,
                config != null ? config.getSystemPrompt() : null,
                bot.isEnabled(),
                bot.getBotType(),
                bot.getCreatedAt(),
                bot.getUpdatedAt()
        );
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return apiKey;
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    public record TestMessageRequest(String content) {}

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

    public record BotUserDto(
            String id,
            String username,
            String avatarUrl,
            String gatewayUrl,
            String apiKey,
            String systemPrompt,
            boolean enabled,
            String botType,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record BotUserRequest(
            String username,
            String avatarUrl,
            String gatewayUrl,
            String apiKey,
            String systemPrompt,
            String password,
            Boolean enabled
    ) {}

    public record CreateBotUserRequest(
            String username,
            String avatarUrl,
            String gatewayUrl,
            String apiKey,
            String systemPrompt,
            String password,
            Boolean enabled,
            String botType
    ) {}

    public record UpdateBotUserRequest(
            String username,
            String avatarUrl,
            String gatewayUrl,
            String apiKey,
            String systemPrompt,
            String password,
            Boolean enabled
    ) {}
}
