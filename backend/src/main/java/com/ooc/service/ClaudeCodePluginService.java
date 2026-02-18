package com.ooc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ooc.entity.BotUserConfig;
import com.ooc.entity.ChatRoom;
import com.ooc.entity.User;
import com.ooc.repository.UserRepository;
import com.ooc.websocket.Attachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claude Code Plugin Service
 * 适配 Claude Code API (Anthropic API) 调用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeCodePluginService {

    private final UserRepository userRepository;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    // Claude API 默认配置
    private static final String CLAUDE_API_BASE_URL = "https://api.anthropic.com";
    private static final String CLAUDE_MODEL = "claude-3-5-sonnet-20241022";
    private static final String CLAUDE_API_VERSION = "2023-06-01";

    // 内存中的会话状态管理
    private final Map<String, ClaudeSessionState> sessionStates = new ConcurrentHashMap<>();

    /**
     * Claude 会话状态
     */
    @lombok.Data
    @lombok.Builder
    public static class ClaudeSessionState {
        private String sessionId;
        private String instanceName;
        private Instant createdAt;
        private Instant lastActivity;
        private List<Map<String, Object>> messageHistory;
    }

    /**
     * 获取 Claude Code 机器人用户配置
     */
    private Optional<BotUserConfig> getBotConfig() {
        return userRepository.findAll().stream()
                .filter(User::isBot)
                .filter(User::isEnabled)
                .filter(u -> "claude-code".equals(u.getBotType()))
                .map(User::getBotConfig)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * 获取 Claude Code 机器人用户
     */
    private Optional<User> getBotUser() {
        return userRepository.findAll().stream()
                .filter(User::isBot)
                .filter(User::isEnabled)
                .filter(u -> "claude-code".equals(u.getBotType()))
                .findFirst();
    }

    /**
     * 获取 API Key
     */
    private String getApiKey() {
        return getBotConfig()
                .map(BotUserConfig::getApiKey)
                .filter(key -> key != null && !key.isBlank())
                .orElse("");
    }

    /**
     * 获取 Gateway URL（用于自定义端点）
     */
    private String getGatewayUrl() {
        return getBotConfig()
                .map(BotUserConfig::getGatewayUrl)
                .filter(url -> url != null && !url.isBlank())
                .orElse(CLAUDE_API_BASE_URL);
    }

    /**
     * 获取系统提示词
     */
    private String getSystemPrompt() {
        return getBotConfig()
                .map(BotUserConfig::getSystemPrompt)
                .filter(prompt -> prompt != null && !prompt.isBlank())
                .orElse("You are Claude, a helpful AI assistant made by Anthropic.");
    }

    /**
     * 获取机器人用户名
     */
    public String getBotUsername() {
        return getBotUser()
                .map(User::getUsername)
                .filter(name -> name != null && !name.isBlank())
                .orElse("claude");
    }

    /**
     * 获取机器人头像 URL
     */
    public String getBotAvatarUrl() {
        return getBotUser()
                .map(User::getAvatar)
                .orElse(null);
    }

    /**
     * 检查机器人是否启用
     */
    public boolean isBotEnabled() {
        return getBotUser().isPresent();
    }

    /**
     * 检查会话是否存活
     */
    public boolean isSessionAlive(String sessionId) {
        ClaudeSessionState state = sessionStates.get(sessionId);
        if (state == null) return false;

        // 检查是否超时（30分钟）
        long inactiveDuration = Instant.now().toEpochMilli() - state.getLastActivity().toEpochMilli();
        return inactiveDuration < 30 * 60 * 1000; // 30 minutes
    }

    private WebClient getWebClient() {
        return webClientBuilder.baseUrl(getGatewayUrl()).build();
    }

    /**
     * 创建新的 Claude 会话
     */
    public Mono<ClaudeSession> createSession(String instanceName, List<Map<String, Object>> context) {
        String sessionId = UUID.randomUUID().toString();

        ClaudeSessionState state = ClaudeSessionState.builder()
                .sessionId(sessionId)
                .instanceName(instanceName)
                .createdAt(Instant.now())
                .lastActivity(Instant.now())
                .messageHistory(new ArrayList<>())
                .build();

        // 添加上下文消息
        if (context != null) {
            state.getMessageHistory().addAll(context);
        }

        sessionStates.put(sessionId, state);

        log.info("Created Claude Code session: {}", sessionId);
        return Mono.just(new ClaudeSession(sessionId, instanceName, Instant.now()));
    }

    /**
     * 发送消息到 Claude 并获取流式回复
     */
    public Flux<StreamEvent> sendMessageStream(String sessionId, String message,
            List<com.ooc.websocket.Attachment> attachments,
            String userId, String userName, String roomName) {

        String apiKey = getApiKey();
        if (apiKey.isBlank()) {
            return Flux.error(new RuntimeException("Claude API Key not configured"));
        }

        // 获取或创建会话状态
        ClaudeSessionState state = sessionStates.get(sessionId);
        if (state == null) {
            return Flux.error(new RuntimeException("Session not found: " + sessionId));
        }

        // 构建用户消息内容
        String formattedMessage = String.format("[%s群] 用户%s说: %s",
                roomName != null ? roomName : "聊天", userName, message);

        // 添加用户消息到历史
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", formattedMessage);
        state.getMessageHistory().add(userMsg);

        // 更新最后活动时间
        state.setLastActivity(Instant.now());

        // 构建 Anthropic API 请求
        Map<String, Object> request = new HashMap<>();
        request.put("model", CLAUDE_MODEL);
        request.put("system", getSystemPrompt());
        request.put("messages", state.getMessageHistory());
        request.put("stream", true);
        request.put("max_tokens", 4096);

        log.info("Sending streaming request to Claude API: sessionId={}, messageLength={}",
                sessionId, formattedMessage.length());

        // 存储完整响应以便添加到历史
        StringBuilder fullResponse = new StringBuilder();

        return getWebClient().post()
                .uri("/v1/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header("anthropic-version", CLAUDE_API_VERSION)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(line -> {
                    log.debug("Claude SSE line: {}", line);

                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);

                        if ("[DONE]".equals(data)) {
                            // 流结束，添加助手回复到历史
                            if (fullResponse.length() > 0) {
                                Map<String, Object> assistantMsg = new HashMap<>();
                                assistantMsg.put("role", "assistant");
                                assistantMsg.put("content", fullResponse.toString());
                                state.getMessageHistory().add(assistantMsg);
                            }
                            return Flux.just(new StreamEvent("done", null, null, null, null, true));
                        }

                        try {
                            JsonNode jsonNode = objectMapper.readTree(data);
                            String type = jsonNode.has("type") ? jsonNode.get("type").asText() : "";

                            // 处理内容块增量
                            if ("content_block_delta".equals(type)) {
                                JsonNode delta = jsonNode.get("delta");
                                if (delta != null && delta.has("text")) {
                                    String content = delta.get("text").asText();
                                    if (content != null && !content.isEmpty()) {
                                        fullResponse.append(content);
                                        return Flux.just(new StreamEvent("message", content, null, null, null, false));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("Failed to parse Claude SSE data: {}", data, e);
                        }
                    }

                    return Flux.empty();
                })
                .onErrorResume(error -> {
                    log.error("Claude API error", error);
                    return Flux.just(new StreamEvent("error", error.getMessage(), null, null, null, true));
                })
                .concatWith(Flux.just(new StreamEvent("done", null, null, null, null, true)));
    }

    /**
     * 发送消息到 Claude（非流式）
     */
    public Mono<ClaudeResponse> sendMessage(String sessionId, String message,
            List<ChatRoom.Message.Attachment> attachments, String userId, String userName) {

        String apiKey = getApiKey();
        if (apiKey.isBlank()) {
            return Mono.error(new RuntimeException("Claude API Key not configured"));
        }

        // 获取或创建会话状态
        ClaudeSessionState state = sessionStates.get(sessionId);
        if (state == null) {
            return Mono.error(new RuntimeException("Session not found: " + sessionId));
        }

        // 添加用户消息到历史
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userName + ": " + message);
        state.getMessageHistory().add(userMsg);

        // 更新最后活动时间
        state.setLastActivity(Instant.now());

        // 构建 Anthropic API 请求
        Map<String, Object> request = new HashMap<>();
        request.put("model", CLAUDE_MODEL);
        request.put("system", getSystemPrompt());
        request.put("messages", state.getMessageHistory());
        request.put("stream", false);
        request.put("max_tokens", 4096);

        log.info("Sending request to Claude API: sessionId={}, messageLength={}",
                sessionId, message.length());

        return getWebClient().post()
                .uri("/v1/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header("anthropic-version", CLAUDE_API_VERSION)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(MessageResponse.class)
                .map(response -> {
                    // 提取文本内容
                    String content = response.content().stream()
                            .filter(c -> "text".equals(c.type()))
                            .map(MessageResponse.Content::text)
                            .findFirst()
                            .orElse("");

                    // 添加助手回复到历史
                    Map<String, Object> assistantMsg = new HashMap<>();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("content", content);
                    state.getMessageHistory().add(assistantMsg);

                    log.info("Received Claude response ({} chars)", content.length());

                    return new ClaudeResponse(
                            UUID.randomUUID().toString(),
                            content,
                            Instant.now(),
                            true
                    );
                })
                .doOnError(error -> log.error("Claude API error", error));
    }

    /**
     * 关闭会话
     */
    public Mono<Void> closeSession(String sessionId) {
        sessionStates.remove(sessionId);
        log.info("Closed Claude session: {}", sessionId);
        return Mono.empty();
    }

    /**
     * 获取会话状态
     */
    public ClaudeSessionState getSessionState(String sessionId) {
        return sessionStates.get(sessionId);
    }

    // 响应记录
    public record MessageResponse(
            String id,
            String type,
            String role,
            String model,
            List<Content> content,
            Usage usage
    ) {
        public record Content(
                String type,
                String text
        ) {}

        public record Usage(
                int input_tokens,
                int output_tokens
        ) {}
    }

    public record ClaudeSession(String sessionId, String instanceName, Instant createdAt) {}
    public record ClaudeResponse(String messageId, String content, Instant timestamp, boolean completed) {}

    /**
     * 流式响应事件
     */
    public record StreamEvent(
            String type,
            String content,
            String toolName,
            String toolInput,
            String messageId,
            boolean completed
    ) {}
}
