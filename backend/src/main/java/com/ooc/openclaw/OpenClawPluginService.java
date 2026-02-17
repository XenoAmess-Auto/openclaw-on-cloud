package com.ooc.openclaw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ooc.config.FileProperties;
import com.ooc.entity.BotUserConfig;
import com.ooc.entity.ChatRoom;
import com.ooc.entity.User;
import com.ooc.repository.UserRepository;
import com.ooc.storage.StorageProvider;
import com.ooc.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenClawPluginService {

    // 图片压缩配置 - 限制最大尺寸以减少请求体大小
    private static final int MAX_IMAGE_WIDTH = 1024;
    private static final int MAX_IMAGE_HEIGHT = 1024;
    private static final int MAX_IMAGE_SIZE_MB = 1; // 压缩后最大 1MB
    private static final float JPEG_QUALITY = 0.85f;

    private final OpenClawProperties properties;
    private final UserRepository userRepository;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final OpenClawWebSocketClient webSocketClient;
    private final FileProperties fileProperties;
    private final StorageProvider storageProvider;

    // 内存中的会话状态管理
    private final Map<String, OpenClawSessionState> sessionStates = new ConcurrentHashMap<>();

    /**
     * 获取 OpenClaw 机器人用户配置
     */
    private Optional<BotUserConfig> getBotConfig() {
        return userRepository.findAll().stream()
                .filter(User::isBot)
                .filter(User::isEnabled)
                .filter(u -> "openclaw".equals(u.getBotType()))
                .map(User::getBotConfig)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * 获取 OpenClaw 机器人用户
     */
    private Optional<User> getBotUser() {
        return userRepository.findAll().stream()
                .filter(User::isBot)
                .filter(User::isEnabled)
                .filter(u -> "openclaw".equals(u.getBotType()))
                .findFirst();
    }

    /**
     * 获取 Gateway URL，优先使用机器人配置
     */
    private String getGatewayUrl() {
        return getBotConfig()
                .map(BotUserConfig::getGatewayUrl)
                .filter(url -> url != null && !url.isBlank())
                .orElse(properties.getGatewayUrl());
    }

    /**
     * 获取 API Key，优先使用机器人配置
     */
    private String getApiKey() {
        return getBotConfig()
                .map(BotUserConfig::getApiKey)
                .filter(key -> key != null && !key.isBlank())
                .orElse(properties.getApiKey());
    }

    /**
     * 获取机器人用户名
     */
    public String getBotUsername() {
        return getBotUser()
                .map(User::getUsername)
                .filter(name -> name != null && !name.isBlank())
                .orElse("openclaw");
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
     * 获取系统提示词
     */
    private String getSystemPrompt() {
        return getBotConfig()
                .map(BotUserConfig::getSystemPrompt)
                .filter(prompt -> prompt != null && !prompt.isBlank())
                .orElse("You are a helpful assistant.");
    }

    private WebClient getWebClient() {
        return webClientBuilder.baseUrl(getGatewayUrl()).build();
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
     * 读取上传的文件并转为 data URL
     * 支持本地存储和 S3 存储
     */
    private String readFileToDataUrl(String url, String mimeType) {
        try {
            // 提取文件名（key）
            String filename = url.substring(url.lastIndexOf("/") + 1);

            byte[] fileBytes;

            // 检查存储类型
            if ("s3".equalsIgnoreCase(storageProvider.getStorageType())) {
                // S3 存储：使用 StorageProvider 读取文件
                log.info("[S3] Reading file from S3: {}", filename);
                try (InputStream inputStream = storageProvider.getInputStream(filename)) {
                    fileBytes = inputStream.readAllBytes();
                }
            } else {
                // 本地存储：使用配置的 uploadDir 作为基础路径
                String uploadDir = fileProperties.getUploadDir();
                java.nio.file.Path filePath = java.nio.file.Paths.get(uploadDir, filename);

                // 如果找不到，尝试从 URL 中提取完整路径
                if (!java.nio.file.Files.exists(filePath)) {
                    // 尝试直接使用 url 作为相对路径
                    filePath = java.nio.file.Paths.get(url.substring(1)); // 去掉开头的 /
                    if (!filePath.isAbsolute()) {
                        filePath = java.nio.file.Paths.get(System.getProperty("user.dir")).resolve(filePath);
                    }
                }

                // 向后兼容：尝试在工作目录下查找
                if (!java.nio.file.Files.exists(filePath)) {
                    String oocBasePath = System.getProperty("user.dir");
                    filePath = java.nio.file.Paths.get(oocBasePath, "uploads", filename);
                }

                // 再尝试父目录
                if (!java.nio.file.Files.exists(filePath)) {
                    String oocBasePath = System.getProperty("user.dir");
                    filePath = java.nio.file.Paths.get(oocBasePath, "..", "uploads", filename).normalize();
                }

                if (!java.nio.file.Files.exists(filePath)) {
                    log.warn("File not found: {} (tried uploadDir: {})", filename, fileProperties.getUploadDir());
                    return null;
                }

                log.info("Reading file from: {}", filePath);
                fileBytes = java.nio.file.Files.readAllBytes(filePath);
            }

            // 压缩图片以减少请求体大小
            byte[] compressedBytes = compressImageIfNeeded(fileBytes, mimeType);
            if (compressedBytes != null) {
                fileBytes = compressedBytes;
                log.info("Compressed image to {} bytes ({}% reduction)",
                    fileBytes.length,
                    Math.round((1.0 - (double)fileBytes.length / fileBytes.length) * 100));
            }

            String base64 = java.util.Base64.getEncoder().encodeToString(fileBytes);

            // 使用提供的 mimeType，如果没有则根据文件扩展名推断
            String contentType = mimeType;
            if (contentType == null || contentType.isEmpty()) {
                contentType = "image/png"; // 默认
                if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
                    contentType = "image/jpeg";
                } else if (filename.endsWith(".gif")) {
                    contentType = "image/gif";
                } else if (filename.endsWith(".webp")) {
                    contentType = "image/webp";
                }
            }

            return "data:" + contentType + ";base64," + base64;
        } catch (Exception e) {
            log.error("Failed to read file to data URL: {}", url, e);
            return null;
        }
    }

    /**
     * 压缩图片以限制文件大小
     * @param imageBytes 原始图片字节
     * @param mimeType MIME类型
     * @return 压缩后的图片字节，如果不需要压缩则返回null
     */
    private byte[] compressImageIfNeeded(byte[] imageBytes, String mimeType) {
        try {
            // 如果已经小于1MB，不需要压缩
            if (imageBytes.length <= MAX_IMAGE_SIZE_MB * 1024 * 1024) {
                return null;
            }
            
            log.info("Image size {} bytes exceeds limit, compressing...", imageBytes.length);
            
            // 读取图片
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (originalImage == null) {
                log.warn("Could not read image for compression");
                return null;
            }
            
            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();
            
            // 计算缩放比例
            double scale = Math.min(
                (double) MAX_IMAGE_WIDTH / originalWidth,
                (double) MAX_IMAGE_HEIGHT / originalHeight
            );
            
            // 如果图片尺寸在限制内且文件大小超限，尝试降低质量
            if (scale >= 1.0) {
                scale = 1.0;
            }
            
            int newWidth = (int) (originalWidth * scale);
            int newHeight = (int) (originalHeight * scale);
            
            log.info("Resizing image from {}x{} to {}x{}", originalWidth, originalHeight, newWidth, newHeight);
            
            // 创建缩放后的图片
            Image scaledImage = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
            BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = resizedImage.createGraphics();
            g2d.drawImage(scaledImage, 0, 0, null);
            g2d.dispose();
            
            // 使用JPEG格式压缩，调整质量
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            
            // 尝试不同的压缩质量
            float quality = JPEG_QUALITY;
            byte[] result = null;
            
            while (quality >= 0.3f) {
                outputStream.reset();
                
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(resizedImage, null, null), param);
                }
                writer.dispose();
                
                result = outputStream.toByteArray();
                
                if (result.length <= MAX_IMAGE_SIZE_MB * 1024 * 1024) {
                    log.info("Compressed image to {} bytes with quality {}", result.length, quality);
                    break;
                }
                
                quality -= 0.1f;
            }
            
            if (result != null && result.length < imageBytes.length) {
                return result;
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("Failed to compress image", e);
            return null;
        }
    }

    /**
     * 从完整路径读取文件并转为 data URL
     */
    private String readFileToDataUrlFromFullPath(String fullPath, String mimeType) {
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get(fullPath);
            
            if (!java.nio.file.Files.exists(filePath)) {
                log.warn("File not found: {}", filePath);
                return null;
            }
            
            byte[] fileBytes = java.nio.file.Files.readAllBytes(filePath);
            
            // 压缩图片以减少请求体大小
            byte[] compressedBytes = compressImageIfNeeded(fileBytes, mimeType);
            if (compressedBytes != null) {
                fileBytes = compressedBytes;
                log.info("Compressed image from {} to {} bytes", 
                    java.nio.file.Files.size(filePath), fileBytes.length);
            }
            
            String base64 = java.util.Base64.getEncoder().encodeToString(fileBytes);
            
            // 提取文件名
            String filename = filePath.getFileName().toString();
            
            // 使用提供的 mimeType，如果没有则根据文件扩展名推断
            String contentType = mimeType;
            if (contentType == null || contentType.isEmpty()) {
                contentType = "image/png"; // 默认
                if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
                    contentType = "image/jpeg";
                } else if (filename.endsWith(".gif")) {
                    contentType = "image/gif";
                } else if (filename.endsWith(".webp")) {
                    contentType = "image/webp";
                }
            }
            
            return "data:" + contentType + ";base64," + base64;
        } catch (Exception e) {
            log.error("Failed to read file to data URL: {}", fullPath, e);
            return null;
        }
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
                if ("image".equalsIgnoreCase(att.getType())) {
                    String imageDataUrl = null;
                    
                    // 优先使用 URL（可能是 /uploads/xxx.png 或完整 URL）
                    if (att.getUrl() != null && !att.getUrl().isEmpty()) {
                        String url = att.getUrl();
                        if (url.startsWith("/uploads/")) {
                            // 相对路径 /uploads/xxx.png，需要读取文件并转为 base64
                            imageDataUrl = readFileToDataUrl(url, att.getMimeType());
                        } else if (url.contains("/uploads/")) {
                            // 完整路径包含 /uploads/，提取文件名并读取
                            imageDataUrl = readFileToDataUrlFromFullPath(url, att.getMimeType());
                        } else if (url.startsWith("data:")) {
                            // 已经是 data URL，直接使用
                            imageDataUrl = url;
                        } else {
                            // 其他 URL，直接使用（假设是 http/https）
                            imageDataUrl = url;
                        }
                    } else if (att.getContent() != null && !att.getContent().isEmpty()) {
                        // 使用 base64 内容构造 data URL
                        imageDataUrl = "data:" + att.getMimeType() + ";base64," + att.getContent();
                    }
                    
                    if (imageDataUrl != null) {
                        Map<String, Object> imageBlock = new HashMap<>();
                        imageBlock.put("type", "image_url");
                        Map<String, String> imageUrl = new HashMap<>();
                        imageUrl.put("url", imageDataUrl);
                        imageBlock.put("image_url", imageUrl);
                        contentBlocks.add(imageBlock);
                    }
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
        
        // 添加系统消息 - 合并数据库配置的工具格式要求
        String basePrompt = getSystemPrompt();
        String toolFormatInstructions = """
            
            IMPORTANT: When you use tools (read, write, edit, exec, etc.), you MUST include detailed
            tool call information in your response in this format:

            **Tools used:**
            - `tool_name`: brief description

            **Tool details:**
            - `tool_name`:
              ```
              <tool output content here>
              ```

            ---

            Then provide your actual response summary above the separator.

            For `read` tool: include the file content you read.
            For `exec` tool: include the command output.
            For other tools: include the relevant output data.
            """;
        
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", basePrompt + toolFormatInstructions);
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
        request.put("tool_choice", "auto"); // Enable tool calling

        log.info("Sending multimodal request to OpenClaw: sessionId={}, textLength={}, imageCount={}", 
                sessionId, 
                processedMessage != null ? processedMessage.length() : 0,
                attachments != null ? attachments.size() : 0);

        return getWebClient().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .map(response -> {
                    String content = response.choices().get(0).message().content();
                    log.info("Received OpenClaw response ({} chars): {}", content.length(),
                            content.substring(0, Math.min(200, content.length())));
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
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .map(response -> response.choices().get(0).message().content())
                .doOnError(error -> log.error("Summarize error", error));
    }

    /**
     * 发送消息到 OpenClaw 并获取流式回复（内部实现）- WebSocket 版本
     *
     * 使用 WebSocket 协议连接到 OpenClaw Gateway，接收原生工具事件
     */
    private Flux<StreamEvent> sendMessageStreamInternal(String sessionId, String message,
            List<ChatWebSocketHandler.Attachment> attachments, String userId, String userName, String roomName) {

        String processedMessage = convertUploadsPath(message);

        log.info("[sendMessageStream] Processing {} attachments", attachments != null ? attachments.size() : 0);

        // 构建多模态内容块列表
        List<Map<String, Object>> contentBlocks = new ArrayList<>();

        // 添加文本内容块，格式: [群名群] 用户xxx说: 内容
        if (processedMessage != null && !processedMessage.isEmpty()) {
            Map<String, Object> textBlock = new HashMap<>();
            textBlock.put("type", "text");
            String formattedMessage = String.format("[%s群] 用户%s说: %s",
                    roomName != null ? roomName : "未知", userName, processedMessage);
            textBlock.put("text", formattedMessage);
            contentBlocks.add(textBlock);
        }

        // 处理图片附件
        int imageCount = 0;
        if (attachments != null && !attachments.isEmpty()) {
            for (ChatWebSocketHandler.Attachment att : attachments) {
                log.info("[sendMessageStream] Processing attachment: type={}, mimeType={}, url={}",
                        att.getType(), att.getMimeType(), att.getUrl());

                if ("image".equalsIgnoreCase(att.getType())) {
                    String imageDataUrl = null;
                    String url = att.getUrl();

                    if (url != null && !url.isEmpty()) {
                        if (url.startsWith("/uploads/")) {
                            // 读取文件并转为 base64 data URL
                            imageDataUrl = readFileToDataUrl(url, att.getMimeType());
                        } else if (url.contains("/uploads/")) {
                            // 完整路径包含 /uploads/，提取文件名并读取
                            imageDataUrl = readFileToDataUrlFromFullPath(url, att.getMimeType());
                        } else if (url.startsWith("data:")) {
                            // 已经是 data URL，直接使用
                            imageDataUrl = url;
                        }
                    }

                    if (imageDataUrl != null) {
                        Map<String, Object> imageBlock = new HashMap<>();
                        imageBlock.put("type", "image_url");
                        Map<String, String> imageUrl = new HashMap<>();
                        imageUrl.put("url", imageDataUrl);
                        imageBlock.put("image_url", imageUrl);
                        contentBlocks.add(imageBlock);
                        imageCount++;
                        log.info("[sendMessageStream] Added image block, data URL length: {}", imageDataUrl.length());
                    } else {
                        log.warn("[sendMessageStream] Failed to process image: {}", url);
                    }
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

        // 构建系统提示词 - 使用数据库配置
        String systemPrompt = getSystemPrompt() + " When using tools, format: **Tools used:** - tool_name. **Tool details:** - tool_name: ```output```";

        log.info("Sending WebSocket request to OpenClaw: sessionId={}, textBlocks={}, imageBlocks={}",
                sessionId, contentBlocks.size() - imageCount, imageCount);

        // 使用 WebSocket 客户端发送消息
        return Flux.create(sink -> {
            webSocketClient.sendMessage(sessionId, processedMessage, contentBlocks,
                    new OpenClawWebSocketClient.ResponseHandler() {
                        private final StringBuilder fullContent = new StringBuilder();

                        @Override
                        public void onTextChunk(String text) {
                            fullContent.append(text);
                            sink.next(new StreamEvent("message", text, null, null, null, false));
                        }

                        @Override
                        public void onToolStart(String toolName, String toolCallId, Map<String, Object> args) {
                            log.info("[OpenClaw WS] Tool start: {} ({})", toolName, toolCallId);
                            sink.next(new StreamEvent("tool_start", null, toolName, args != null ? args.toString() : "", toolCallId, false));
                        }

                        @Override
                        public void onToolUpdate(String toolCallId, Object partialResult) {
                            // 可选：处理工具执行中的更新
                            log.debug("[OpenClaw WS] Tool update: {}", toolCallId);
                        }

                        @Override
                        public void onToolResult(String toolCallId, Object result, boolean isError) {
                            log.info("[OpenClaw WS] Tool result: {} (error={})", toolCallId, isError);
                            String resultStr = result != null ? result.toString() : "";
                            sink.next(new StreamEvent("tool_result", resultStr, null, null, toolCallId, false));
                        }

                        @Override
                        public void onComplete() {
                            log.info("[OpenClaw WS] Stream completed");
                            sink.next(new StreamEvent("done", null, null, null, null, true));
                            sink.complete();

                            // 更新会话状态
                            updateSessionState(sessionId);
                        }

                        @Override
                        public void onError(String error) {
                            log.error("[OpenClaw WS] Stream error: {}", error);
                            sink.next(new StreamEvent("error", error, null, null, null, true));
                            sink.complete();
                        }
                    });
        });
    }

    private void updateSessionState(String sessionId) {
        OpenClawSessionState state = sessionStates.computeIfAbsent(sessionId, k ->
            OpenClawSessionState.builder()
                    .sessionId(sessionId)
                    .instanceName("ooc-" + sessionId)
                    .createdAt(Instant.now())
                    .lastActivity(Instant.now())
                    .build()
        );
        state.setLastActivity(Instant.now());
    }

    /**
     * 关闭会话时同时关闭 WebSocket 连接
     */
    public Mono<Void> closeSession(String sessionId) {
        sessionStates.remove(sessionId);
        webSocketClient.closeSession(sessionId);
        log.info("Closed OpenClaw session: {}", sessionId);
        return Mono.empty();
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

    /**
     * 发送消息到 OpenClaw 并获取流式回复（使用 ChatRoom.Message.Attachment）
     */
    public Flux<StreamEvent> sendMessageStreamWithRoomAttachments(String sessionId, String message,
            List<ChatRoom.Message.Attachment> attachments, String userId, String userName, String roomName) {
        // 转换为 ChatWebSocketHandler.Attachment
        List<ChatWebSocketHandler.Attachment> convertedAttachments = new ArrayList<>();
        if (attachments != null && !attachments.isEmpty()) {
            for (ChatRoom.Message.Attachment att : attachments) {
                ChatWebSocketHandler.Attachment converted = new ChatWebSocketHandler.Attachment();
                converted.setType(att.getType());
                converted.setMimeType(att.getContentType());
                converted.setUrl(att.getUrl());
                // content 字段在 ChatRoom.Message.Attachment 中不存在，保持 null
                convertedAttachments.add(converted);
            }
        }
        return sendMessageStreamInternal(sessionId, message, convertedAttachments, userId, userName, roomName);
    }

    /**
     * 发送消息到 OpenClaw 并获取流式回复
     */
    public Flux<StreamEvent> sendMessageStream(String sessionId, String message,
            List<ChatWebSocketHandler.Attachment> attachments, String userId, String userName, String roomName) {
        return sendMessageStreamInternal(sessionId, message, attachments, userId, userName, roomName);
    }
}
