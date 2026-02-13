package com.ooc.openclaw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ooc.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenClawPluginService {

    private final OpenClawProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    // 内存中的会话状态管理
    private final Map<String, OpenClawSessionState> sessionStates = new ConcurrentHashMap<>();

    private WebClient getWebClient() {
        return webClientBuilder.baseUrl(properties.getGatewayUrl()).build();
    }

    /**
     * 检查会话是否存活
     */
    public boolean isSessionAlive(String sessionId) {
        OpenClawSessionState state = sessionStates.get(sessionId);
        if (state == null) return false;
        
        // 检查是否超时
        long inactiveDuration = Instant.now().toEpochMilli() - state.getLastActivity().toEpochMilli();
        return inactiveDuration < properties.getSessionTimeoutMs();
    }

    /**
     * 将消息中的 /uploads/ 路径转换为 OpenClaw 可读取的绝对路径
     */
    private String convertUploadsPath(String message) {
        if (message == null || !message.contains("/uploads/")) {
            return message;
        }
        
        // 获取 ooc 项目的绝对路径
        String oocBasePath = System.getProperty("user.dir");
        // 替换 /uploads/ 为绝对路径
        return message.replace("/uploads/", oocBasePath + "/uploads/");
    }

    /**
     * 发送消息到 OpenClaw 并获取回复（支持附件）
     */
    public Mono<OpenClawResponse> sendMessage(String sessionId, String message, 
            List<ChatWebSocketHandler.Attachment> attachments, String userId, String userName) {
        
        // 转换消息中的 /uploads/ 路径为绝对路径
        String processedMessage = convertUploadsPath(message);
        
        // 构建消息内容块（支持多模态）
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        
        // 添加文本内容块
        if (processedMessage != null && !processedMessage.isEmpty()) {
            Map<String, Object> textBlock = new HashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", userName + ": " + processedMessage);
            contentBlocks.add(textBlock);
        }
        
        // 添加图片附件内容块
        if (attachments != null && !attachments.isEmpty()) {
            for (ChatWebSocketHandler.Attachment att : attachments) {
                if ("image".equals(att.getType()) && att.getContent() != null) {
                    Map<String, Object> imageBlock = new HashMap<>();
                    imageBlock.put("type", "image_url");
                    
                    Map<String, String> imageUrl = new HashMap<>();
                    // 构造 data URL: data:image/png;base64,...
                    String dataUrl = "data:" + att.getMimeType() + ";base64," + att.getContent();
                    imageUrl.put("url", dataUrl);
                    
                    imageBlock.put("image_url", imageUrl);
                    contentBlocks.add(imageBlock);
                }
            }
        }
        
        // 如果没有内容块（纯空消息），添加一个默认文本
        if (contentBlocks.isEmpty()) {
            Map<String, Object> textBlock = new HashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", userName + ": [图片]");
            contentBlocks.add(textBlock);
        }
        
        // 构建消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        
        // 添加系统消息 - 要求包含工具调用信息
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", """
            You are OpenClaw, a helpful AI assistant. Be concise and direct in your responses.
            
            IMPORTANT: When you use tools (read, write, edit, exec, etc.), you MUST include a summary 
            of the tools you used at the beginning of your response in this format:
            
            **Tools used:**
            - `tool_name`: brief description of what it did
            
            For example:
            **Tools used:**
            - `read`: viewed file configuration
            - `exec`: ran git status command
            
            Then provide your actual response.
            """);
        messages.add(systemMsg);
        
        // 添加用户消息（多模态格式）
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", contentBlocks);
        messages.add(userMsg);

        Map<String, Object> request = new HashMap<>();
        request.put("model", "openclaw:main");
        request.put("messages", messages);
        request.put("user", sessionId); // 用于保持会话状态

        log.info("Sending multimodal request to OpenClaw: sessionId={}, textLength={}, imageCount={}", 
                sessionId, 
                processedMessage != null ? processedMessage.length() : 0,
                attachments != null ? attachments.size() : 0);

        return getWebClient().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .map(response -> {
                    String content = response.choices().get(0).message().content();
                    log.info("Received OpenClaw response: {}", content.substring(0, Math.min(50, content.length())));
                    return new OpenClawResponse(
                            UUID.randomUUID().toString(),
                            content,
                            Instant.now(),
                            true
                    );
                })
                .doOnNext(response -> {
                    // 更新会话状态
                    OpenClawSessionState state = sessionStates.computeIfAbsent(sessionId, k ->
                        OpenClawSessionState.builder()
                                .sessionId(sessionId)
                                .instanceName("ooc-" + sessionId)
                                .createdAt(Instant.now())
                                .lastActivity(Instant.now())
                                .build()
                    );
                    state.setLastActivity(Instant.now());
                })
                .doOnError(error -> log.error("OpenClaw API error", error));
    }

    /**
     * 创建新的 OpenClaw 会话（使用 chat completions API 时，会话由 user 字段管理）
     */
    public Mono<OpenClawSession> createSession(String instanceName, List<Map<String, Object>> context) {
        String sessionId = UUID.randomUUID().toString();
        
        OpenClawSessionState state = OpenClawSessionState.builder()
                .sessionId(sessionId)
                .instanceName(instanceName)
                .createdAt(Instant.now())
                .lastActivity(Instant.now())
                .build();
        sessionStates.put(sessionId, state);
        
        log.info("Created OpenClaw session: {}", sessionId);
        return Mono.just(new OpenClawSession(sessionId, instanceName, Instant.now()));
    }

    /**
     * 关闭会话
     */
    public Mono<Void> closeSession(String sessionId) {
        sessionStates.remove(sessionId);
        log.info("Closed OpenClaw session: {}", sessionId);
        return Mono.empty();
    }

    /**
     * 获取会话状态
     */
    public OpenClawSessionState getSessionState(String sessionId) {
        return sessionStates.get(sessionId);
    }

    /**
     * 总结会话内容（调用 LLM）
     */
    public Mono<String> summarizeSession(List<Map<String, String>> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请总结以下对话内容，保留关键信息，压缩为简洁的摘要:\n\n");
        for (Map<String, String> msg : messages) {
            prompt.append(msg.get("sender")).append(": ").append(msg.get("content")).append("\n");
        }

        List<Map<String, String>> apiMessages = new ArrayList<>();
        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt.toString());
        apiMessages.add(userMsg);

        Map<String, Object> request = new HashMap<>();
        request.put("model", "openclaw:main");
        request.put("messages", apiMessages);

        return getWebClient().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .map(response -> response.choices().get(0).message().content())
                .doOnError(error -> log.error("Summarize error", error));
    }

    // OpenAI Chat Completion API 响应记录
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

    public record OpenClawSession(String sessionId, String instanceName, Instant createdAt) {}
    public record OpenClawResponse(String messageId, String content, Instant timestamp, boolean completed) {}
    public record SummarizeResponse(String summary) {}
}