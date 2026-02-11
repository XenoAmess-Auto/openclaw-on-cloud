package com.ooc.openclaw;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * 创建新的 OpenClaw 会话
     */
    public Mono<OpenClawSession> createSession(String instanceName, List<Map<String, Object>> context) {
        Map<String, Object> request = new HashMap<>();
        request.put("instanceName", instanceName);
        request.put("context", context);

        return getWebClient().post()
                .uri("/api/sessions")
                .header("X-API-Key", properties.getApiKey())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OpenClawSession.class)
                .doOnNext(session -> {
                    sessionStates.put(session.sessionId(), 
                        OpenClawSessionState.builder()
                            .sessionId(session.sessionId())
                            .instanceName(instanceName)
                            .createdAt(Instant.now())
                            .lastActivity(Instant.now())
                            .build());
                    log.info("Created OpenClaw session: {}", session.sessionId());
                });
    }

    /**
     * 发送消息到 OpenClaw 会话
     */
    public Mono<OpenClawResponse> sendMessage(String sessionId, String message, String userId, String userName) {
        Map<String, Object> request = new HashMap<>();
        request.put("message", message);
        request.put("userId", userId);
        request.put("userName", userName);

        return getWebClient().post()
                .uri("/api/sessions/{sessionId}/messages", sessionId)
                .header("X-API-Key", properties.getApiKey())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OpenClawResponse.class)
                .doOnNext(response -> {
                    OpenClawSessionState state = sessionStates.get(sessionId);
                    if (state != null) {
                        state.setLastActivity(Instant.now());
                    }
                });
    }

    /**
     * 获取会话状态
     */
    public OpenClawSessionState getSessionState(String sessionId) {
        return sessionStates.get(sessionId);
    }

    /**
     * 关闭会话
     */
    public Mono<Void> closeSession(String sessionId) {
        return getWebClient().delete()
                .uri("/api/sessions/{sessionId}", sessionId)
                .header("X-API-Key", properties.getApiKey())
                .retrieve()
                .toBodilessEntity()
                .doOnNext(v -> {
                    sessionStates.remove(sessionId);
                    log.info("Closed OpenClaw session: {}", sessionId);
                })
                .then();
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

        Map<String, Object> request = new HashMap<>();
        request.put("prompt", prompt.toString());
        request.put("maxTokens", 500);

        return getWebClient().post()
                .uri("/api/summarize")
                .header("X-API-Key", properties.getApiKey())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SummarizeResponse.class)
                .map(SummarizeResponse::summary);
    }

    public record OpenClawSession(String sessionId, String instanceName, Instant createdAt) {}

    public record OpenClawResponse(String messageId, String content, Instant timestamp, boolean completed) {}

    public record SummarizeResponse(String summary) {}
}
