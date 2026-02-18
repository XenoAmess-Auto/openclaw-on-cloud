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
import org.springframework.beans.factory.annotation.Value;
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
 * Kimi AI Plugin Service
 * 适配 Kimi Code API 调用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KimiPluginService {

    private final UserRepository userRepository;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    // 从配置文件注入配置（优先级高于数据库配置）
    @Value("${kimi.api-key:}")
    private String configApiKey;

    @Value("${kimi.model:kimi-k2.5}")
    private String configModel;

    @Value("${kimi.gateway-url:https://api.moonshot.cn}")
    private String configGatewayUrl;

    // Kimi API 默认配置
    private static final String KIMI_API_BASE_URL = "https://api.moonshot.cn";
    private static final String KIMI_MODEL = "kimi-k2.5";

    // 内存中的会话状态管理
    private final Map<String, KimiSessionState> sessionStates = new ConcurrentHashMap<>();

    /**
     * Kimi 会话状态
     */
    @lombok.Data
    @lombok.Builder
    public static class KimiSessionState {
        private String sessionId;
        private String instanceName;
        private Instant createdAt;
        private Instant lastActivity;
        private List<Map<String, Object>> messageHistory;
    }

    /**
     * 获取 Kimi 机器人用户配置
     */
    private Optional<BotUserConfig> getBotConfig() {
        return userRepository.findAll().stream()
                .filter(User::isBot)
                .filter(User::isEnabled)
                .filter(u -> "kimi".equals(u.getBotType()))
                .map(User::getBotConfig)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * 获取 Kimi 机器人用户
     */
    private Optional<User> getBotUser() {
        return userRepository.findAll().stream()
                .filter(User::isBot)
                .filter(User::isEnabled)
                .filter(u -> "kimi".equals(u.getBotType()))
                .findFirst();
    }

    /**
     * 获取 API Key（优先使用配置文件）
     */
    private String getApiKey() {
        // 优先使用配置文件中的 API Key
        if (configApiKey != null && !configApiKey.isBlank()) {
            log.debug("Using Kimi API Key from configuration");
            return configApiKey;
        }
        // 回退到数据库配置
        return getBotConfig()
                .map(BotUserConfig::getApiKey)
                .filter(key -> key != null && !key.isBlank())
                .orElse("");
    }

    /**
     * 获取 Gateway URL（用于自定义端点，优先使用配置文件）
     */
    private String getGatewayUrl() {
        // 优先使用配置文件中的 Gateway URL
        if (configGatewayUrl != null && !configGatewayUrl.isBlank()) {
            return configGatewayUrl;
        }
        // 回退到数据库配置
        return getBotConfig()
                .map(BotUserConfig::getGatewayUrl)
                .filter(url -> url != null && !url.isBlank())
                .orElse(KIMI_API_BASE_URL);
    }

    /**
     * 获取模型名称（优先使用配置文件）
     */
    private String getModel() {
        if (configModel != null && !configModel.isBlank()) {
            return configModel;
        }
        return KIMI_MODEL;
    }

    /**
     * 获取系统提示词
     */
    private String getSystemPrompt() {
        return getBotConfig()
                .map(BotUserConfig::getSystemPrompt)
                .filter(prompt -> prompt != null && !prompt.isBlank())
                .orElse("你是 Kimi，一个由 Moonshot AI 训练的大型语言模型。");
    }

    /**
     * 获取机器人用户名
     */
    public String getBotUsername() {
        return getBotUser()
                .map(User::getUsername)
                .filter(name -> name != null && !name.isBlank())
                .orElse("kimi");
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
        KimiSessionState state = sessionStates.get(sessionId);
        if (state == null) return false;

        // 检查是否超时（30分钟）
        long inactiveDuration = Instant.now().toEpochMilli() - state.getLastActivity().toEpochMilli();
        return inactiveDuration < 30 * 60 * 1000; // 30 minutes
    }

    private WebClient getWebClient() {
        return webClientBuilder.baseUrl(getGatewayUrl()).build();
    }

    /**
     * 创建新的 Kimi 会话
     */
    public Mono<KimiSession> createSession(String instanceName, List<Map<String, Object>> context) {
        String sessionId = UUID.randomUUID().toString();

        KimiSessionState state = KimiSessionState.builder()
                .sessionId(sessionId)
                .instanceName(instanceName)
                .createdAt(Instant.now())
                .lastActivity(Instant.now())
                .messageHistory(new ArrayList<>())
                .build();

        // 添加系统提示词作为第一条消息
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", getSystemPrompt());
        state.getMessageHistory().add(systemMsg);

        // 添加上下文消息
        if (context != null) {
            state.getMessageHistory().addAll(context);
        }

        sessionStates.put(sessionId, state);

        log.info("Created Kimi session: {}", sessionId);
        return Mono.just(new KimiSession(sessionId, instanceName, Instant.now()));
    }

    /**
     * 发送消息到 Kimi 并获取流式回复
     */
    public Flux<StreamEvent> sendMessageStream(String sessionId, String message,
            List<com.ooc.websocket.Attachment> attachments,
            String userId, String userName, String roomName) {

        String apiKey = getApiKey();
        if (apiKey.isBlank()) {
            return Flux.error(new RuntimeException("Kimi API Key not configured"));
        }

        // 获取或创建会话状态
        KimiSessionState state = sessionStates.get(sessionId);
        if (state == null) {
            return Flux.error(new RuntimeException("Session not found: " + sessionId));
        }

        // 构建消息内容块
        List<Map<String, Object>> contentBlocks = new ArrayList<>();

        // 添加文本内容
        String formattedMessage = String.format("[%s群] 用户%s说: %s",
                roomName != null ? roomName : "聊天", userName, message);

        Map<String, Object> textBlock = new HashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", formattedMessage);
        contentBlocks.add(textBlock);

        // 添加用户消息到历史
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", formattedMessage);
        state.getMessageHistory().add(userMsg);

        // 更新最后活动时间
        state.setLastActivity(Instant.now());

        // 构建请求
        Map<String, Object> request = new HashMap<>();
        request.put("model", getModel());
        request.put("messages", state.getMessageHistory());
        request.put("stream", true);
        request.put("temperature", 0.7);

        log.info("Sending streaming request to Kimi API: sessionId={}, messageLength={}",
                sessionId, formattedMessage.length());

        // 存储完整响应以便添加到历史
        StringBuilder fullResponse = new StringBuilder();

        return getWebClient().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(line -> {
                    log.debug("Kimi SSE line: {}", line);

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
                            JsonNode choices = jsonNode.get("choices");

                            if (choices != null && choices.isArray() && choices.size() > 0) {
                                JsonNode delta = choices.get(0).get("delta");

                                if (delta != null && delta.has("content")) {
                                    String content = delta.get("content").asText();
                                    if (content != null && !content.isEmpty()) {
                                        fullResponse.append(content);
                                        return Flux.just(new StreamEvent("message", content, null, null, null, false));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("Failed to parse Kimi SSE data: {}", data, e);
                        }
                    }

                    return Flux.empty();
                })
                .onErrorResume(error -> {
                    log.error("Kimi API error", error);
                    return Flux.just(new StreamEvent("error", error.getMessage(), null, null, null, true));
                })
                .concatWith(Flux.just(new StreamEvent("done", null, null, null, null, true)));
    }

    /**
     * 发送消息到 Kimi（非流式）
     */
    public Mono<KimiResponse> sendMessage(String sessionId, String message,
            List<ChatRoom.Message.Attachment> attachments, String userId, String userName) {

        String apiKey = getApiKey();
        if (apiKey.isBlank()) {
            return Mono.error(new RuntimeException("Kimi API Key not configured"));
        }

        // 获取或创建会话状态
        KimiSessionState state = sessionStates.get(sessionId);
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

        // 构建请求
        Map<String, Object> request = new HashMap<>();
        request.put("model", getModel());
        request.put("messages", state.getMessageHistory());
        request.put("stream", false);
        request.put("temperature", 0.7);

        log.info("Sending request to Kimi API: sessionId={}, messageLength={}",
                sessionId, message.length());

        return getWebClient().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .map(response -> {
                    String content = response.choices().get(0).message().content();

                    // 添加助手回复到历史
                    Map<String, Object> assistantMsg = new HashMap<>();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("content", content);
                    state.getMessageHistory().add(assistantMsg);

                    log.info("Received Kimi response ({} chars)", content.length());

                    return new KimiResponse(
                            UUID.randomUUID().toString(),
                            content,
                            Instant.now(),
                            true
                    );
                })
                .doOnError(error -> log.error("Kimi API error", error));
    }

    /**
     * 关闭会话
     */
    public Mono<Void> closeSession(String sessionId) {
        sessionStates.remove(sessionId);
        log.info("Closed Kimi session: {}", sessionId);
        return Mono.empty();
    }

    /**
     * 获取会话状态
     */
    public KimiSessionState getSessionState(String sessionId) {
        return sessionStates.get(sessionId);
    }

    // 响应记录
    public record ChatCompletionResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage
    ) {
        public record Choice(
                int index,
                Message message,
                String finish_reason
        ) {}

        public record Message(
                String role,
                String content
        ) {}

        public record Usage(
                int prompt_tokens,
                int completion_tokens,
                int total_tokens
        ) {}
    }

    public record KimiSession(String sessionId, String instanceName, Instant createdAt) {}
    public record KimiResponse(String messageId, String content, Instant timestamp, boolean completed) {}

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
