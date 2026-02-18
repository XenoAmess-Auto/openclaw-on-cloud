package com.ooc.service;

import com.ooc.entity.ChatRoom;
import com.ooc.entity.User;
import com.ooc.repository.UserRepository;
import com.ooc.websocket.WebSocketBroadcastService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Flowbot 机器人服务
 * 负责 flowbot 用户的创建和管理，以及发送流程图相关消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowbotService {

    public static final String FLOWBOT_USERNAME = "flowbot";
    public static final String FLOWBOT_NICKNAME = "Flowbot";
    public static final String FLOWBOT_AVATAR = "🤖";

    private final UserRepository userRepository;
    private final ChatRoomService chatRoomService;
    private final WebSocketBroadcastService broadcastService;

    private User flowbotUser;

    @PostConstruct
    public void init() {
        // 应用启动时确保 flowbot 用户存在
        this.flowbotUser = getOrCreateFlowbotUser();
    }

    /**
     * 获取或创建 flowbot 用户
     */
    public synchronized User getOrCreateFlowbotUser() {
        if (this.flowbotUser != null) {
            return this.flowbotUser;
        }

        return userRepository.findByUsername(FLOWBOT_USERNAME)
                .orElseGet(() -> {
                    log.info("Creating flowbot user...");
                    User bot = User.builder()
                            .id(UUID.randomUUID().toString())
                            .username(FLOWBOT_USERNAME)
                            .nickname(FLOWBOT_NICKNAME)
                            .email("flowbot@ooc.local")
                            .password(UUID.randomUUID().toString()) // 随机密码，无法登录
                            .avatar(FLOWBOT_AVATAR)
                            .enabled(true)
                            .isBot(true)
                            .botType("flowbot")
                            .roles(Set.of("ROLE_BOT"))
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    User saved = userRepository.save(bot);
                    log.info("Flowbot user created: {}", saved.getId());
                    return saved;
                });
    }

    /**
     * 发送流程图开始执行消息
     */
    public ChatRoom.Message sendFlowchartStarted(String roomId, String templateName) {
        User bot = getOrCreateFlowbotUser();
        log.info("[FlowbotService] Sending flowchart started message to room {}: {}", roomId, templateName);

        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(bot.getId())
                .senderName(bot.getNickname())
                .senderAvatar(bot.getAvatar())
                .content("▶️ 开始执行流程图：" + templateName)
                .timestamp(Instant.now())
                .isSystem(true)
                .build();

        chatRoomService.addMessage(roomId, message);
        
        // 广播到 WebSocket
        broadcastService.broadcastChatMessage(roomId, message);
        
        log.info("[FlowbotService] Sent flowchart started message to room {}: {}", roomId, templateName);

        return message;
    }

    /**
     * 发送流程图执行结果消息
     *
     * @param roomId       房间ID
     * @param templateName 模板名称
     * @param finalOutput  最终输出结果
     * @param allVariables 所有变量（用于展开显示）
     * @return 发送的消息
     */
    public ChatRoom.Message sendFlowchartCompleted(String roomId, String templateName,
                                                    String finalOutput,
                                                    java.util.Map<String, Object> allVariables) {
        User bot = getOrCreateFlowbotUser();

        // 构建消息内容
        StringBuilder content = new StringBuilder();
        content.append("✅ 流程图执行完成：").append(templateName).append("\n\n");

        if (finalOutput != null && !finalOutput.isEmpty()) {
            content.append("**结果：**\n");
            content.append(finalOutput);
        } else {
            content.append("**结果：** *(无输出)*");
        }

        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(bot.getId())
                .senderName(bot.getNickname())
                .senderAvatar(bot.getAvatar())
                .content(content.toString())
                .timestamp(Instant.now())
                .isSystem(true)
                // 使用 attachments 字段存储变量数据，前端会特殊处理
                .attachments(buildVariableAttachments(allVariables))
                .build();

        chatRoomService.addMessage(roomId, message);

        // 广播到 WebSocket
        broadcastService.broadcastChatMessage(roomId, message);

        log.info("[Flowbot] Sent flowchart completed message to room {}: {}, variables={}",
                roomId, templateName, allVariables != null ? allVariables.size() : 0);

        return message;
    }

    /**
     * 发送流程图执行失败消息
     */
    public ChatRoom.Message sendFlowchartFailed(String roomId, String templateName, String error) {
        User bot = getOrCreateFlowbotUser();

        String content = "❌ 流程图执行失败：" + templateName + "\n\n**错误：**\n" + error;

        ChatRoom.Message message = ChatRoom.Message.builder()
                .id(UUID.randomUUID().toString())
                .senderId(bot.getId())
                .senderName(bot.getNickname())
                .senderAvatar(bot.getAvatar())
                .content(content)
                .timestamp(Instant.now())
                .isSystem(true)
                .build();

        chatRoomService.addMessage(roomId, message);

        // 广播到 WebSocket
        broadcastService.broadcastChatMessage(roomId, message);

        log.info("[Flowbot] Sent flowchart failed message to room {}: {}", roomId, templateName);

        return message;
    }

    /**
     * 将变量转换为附件格式存储
     * 前端会识别这种特殊格式的附件并渲染为可展开/折叠的变量列表
     */
    private java.util.List<ChatRoom.Message.Attachment> buildVariableAttachments(
            java.util.Map<String, Object> variables) {
        java.util.List<ChatRoom.Message.Attachment> attachments = new java.util.ArrayList<>();

        if (variables == null || variables.isEmpty()) {
            return attachments;
        }

        // 创建一个特殊的附件用于存储变量数据
        ChatRoom.Message.Attachment varsAttachment = ChatRoom.Message.Attachment.builder()
                .id("flowchart-variables")
                .name("流程图变量")
                .type("FLOWCHART_VARIABLES")
                .contentType("application/json")
                .url("data:application/json;base64," + encodeVariables(variables))
                .size(0)
                .build();

        attachments.add(varsAttachment);
        return attachments;
    }

    /**
     * 将变量编码为 base64 JSON
     */
    private String encodeVariables(java.util.Map<String, Object> variables) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(variables);
            return java.util.Base64.getEncoder().encodeToString(json.getBytes());
        } catch (Exception e) {
            log.error("Failed to encode variables", e);
            return "";
        }
    }

    /**
     * 获取 flowbot 用户ID
     */
    public String getFlowbotUserId() {
        return getOrCreateFlowbotUser().getId();
    }
}
