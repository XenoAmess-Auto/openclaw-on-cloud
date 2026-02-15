package com.ooc.openclaw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ooc.entity.ChatRoom;
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
     * 读取 /uploads/ 路径的文件并转为 data URL
     */
    private String readFileToDataUrl(String url, String mimeType) {
        try {
            // 提取文件名
            String filename = url.substring(url.lastIndexOf("/") + 1);
            String oocBasePath = System.getProperty("user.dir");
            
            // 首先尝试在当前工作目录下查找（向后兼容）
            java.nio.file.Path filePath = java.nio.file.Paths.get(oocBasePath, "uploads", filename);
            
            // 如果找不到，尝试在父目录查找（后端运行在 backend/ 子目录的情况）
            if (!java.nio.file.Files.exists(filePath)) {
                filePath = java.nio.file.Paths.get(oocBasePath, "..", "uploads", filename).normalize();
            }
            
            if (!java.nio.file.Files.exists(filePath)) {
                log.warn("File not found: {} (tried {} and parent)", filename, oocBasePath);
                return null;
            }
            
            byte[] fileBytes = java.nio.file.Files.readAllBytes(filePath);
            
            // 压缩图片以减少请求体大小
            byte[] compressedBytes = compressImageIfNeeded(fileBytes, mimeType);
            if (compressedBytes != null) {
                fileBytes = compressedBytes;
                log.info("Compressed image from {} to {} bytes ({}% reduction)", 
                    java.nio.file.Files.size(filePath), fileBytes.length,
                    Math.round((1.0 - (double)fileBytes.length / java.nio.file.Files.size(filePath)) * 100));
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
        
        // 添加系统消息 - 要求包含工具调用信息
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", """
            You are OpenClaw, a helpful AI assistant. Be concise and direct in your responses.

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
        request.put("tool_choice", "auto"); // Enable tool calling

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
            List<ChatRoom.Message.Attachment> attachments, String userId, String userName) {
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
        return sendMessageStreamInternal(sessionId, message, convertedAttachments, userId, userName);
    }

    /**
     * 发送消息到 OpenClaw 并获取流式回复
     */
    public Flux<StreamEvent> sendMessageStream(String sessionId, String message,
            List<ChatWebSocketHandler.Attachment> attachments, String userId, String userName) {
        return sendMessageStreamInternal(sessionId, message, attachments, userId, userName);
    }

    /**
     * 发送消息到 OpenClaw 并获取流式回复（内部实现）
     * 
     * 注意：OpenClaw 的 OpenAI 兼容层只支持文本内容，不支持 image_url 类型的 content block。
     * 所以我们将图片 URL 嵌入到文本中，让 OpenClaw 可以通过 web_fetch 工具获取图片内容。
     */
    private Flux<StreamEvent> sendMessageStreamInternal(String sessionId, String message,
            List<ChatWebSocketHandler.Attachment> attachments, String userId, String userName) {

        String processedMessage = convertUploadsPath(message);
        
        // 构建纯文本消息（OpenClaw 的 OpenAI 兼容层只支持纯文本）
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append(userName).append(": ").append(processedMessage != null ? processedMessage : "");

        log.info("[sendMessageStream] Processing {} attachments", attachments != null ? attachments.size() : 0);

        // 收集图片 URL，嵌入到文本中
        List<String> imageUrls = new ArrayList<>();
        if (attachments != null && !attachments.isEmpty()) {
            for (ChatWebSocketHandler.Attachment att : attachments) {
                log.info("[sendMessageStream] Attachment: type={}, mimeType={}, url={}",
                        att.getType(), att.getMimeType(), att.getUrl());

                if ("image".equalsIgnoreCase(att.getType())) {
                    String imageUrl = att.getUrl();
                    if (imageUrl != null && !imageUrl.isEmpty()) {
                        // 将相对路径转换为完整 URL
                        if (imageUrl.startsWith("/uploads/")) {
                            imageUrl = "http://localhost:8081" + imageUrl;
                        }
                        imageUrls.add(imageUrl);
                        log.info("[sendMessageStream] Added image URL: {}", imageUrl);
                    }
                }
            }
        }
        
        // 如果有图片，在消息末尾添加图片 URL
        if (!imageUrls.isEmpty()) {
            messageBuilder.append("\n\n[图片附件]:\n");
            for (int i = 0; i < imageUrls.size(); i++) {
                messageBuilder.append("图片 ").append(i + 1).append(": ").append(imageUrls.get(i)).append("\n");
            }
            messageBuilder.append("\n请使用 web_fetch 工具获取图片内容并分析。");
        }

        String finalMessage = messageBuilder.toString();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", """
            You are OpenClaw, a helpful AI assistant. Be concise and direct in your responses.

            IMPORTANT: When you use tools (read, write, edit, exec, web_search, weather, etc.), you MUST include detailed
            tool call information in your response in this format:

            [Your actual response summary here - this will be shown to the user as the main message content]

            ---

            **Tools used:**
            - `tool_name`: brief description

            **Tool details:**
            - `tool_name`:
              ```
              <tool output content here>
              ```

            For `read` tool: include the file content you read.
            For `exec` tool: include the command output.
            For `web_search` tool: include the search results.
            For `weather` queries: use exec with curl to wttr.in, e.g., `curl -s "wttr.in/LOCATION?format=3"`
            For `web_fetch` tool: include the fetched content.
            For other tools: include the relevant output data.
            
            When user provides image URLs, use the `web_fetch` tool to fetch and analyze them.
            """);
        messages.add(systemMsg);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        // 使用纯文本格式（OpenClaw 的 OpenAI 兼容层不支持 content blocks 数组）
        userMsg.put("content", finalMessage);
        messages.add(userMsg);

        Map<String, Object> request = new HashMap<>();
        request.put("model", "openclaw:main");
        request.put("messages", messages);
        request.put("user", sessionId);
        request.put("stream", true);
        
        // Enable tool calling - let OpenClaw use available tools
        request.put("tool_choice", "auto");

        log.info("Sending streaming request to OpenClaw: sessionId={}, messageLength={}, imageCount={}",
                sessionId,
                finalMessage.length(),
                imageUrls.size());

        return getWebClient().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, "text/event-stream")
                .bodyValue(request)
                .exchangeToFlux(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToFlux(String.class)
                                .flatMap(line -> {
                                    // SSE 响应可能包含多行 data: 事件，需要按行分割
                                    return Flux.fromArray(line.split("\n"))
                                            .map(String::trim)
                                            .filter(l -> !l.isEmpty());
                                })
                                .doOnNext(line -> log.info("SSE raw line: {}", line.substring(0, Math.min(100, line.length()))))
                                .flatMap(this::parseSseLine)
                                .doOnNext(event -> log.info("Parsed event: type={}, content={}", event.type(),
                                        event.content() != null ? event.content().substring(0, Math.min(50, event.content().length())) : "null"))
                                .onErrorResume(error -> {
                                    // 区分真正的错误和正常的连接关闭
                                    String errorMsg = error.getMessage();
                                    boolean isPrematureClose = errorMsg != null && (
                                        errorMsg.contains("premature close") ||
                                        errorMsg.contains("connection reset") ||
                                        errorMsg.contains("Connection reset") ||
                                        errorMsg.contains("broken pipe") ||
                                        errorMsg.contains("Broken pipe")
                                    );
                                    boolean isTimeout = errorMsg != null && (
                                        errorMsg.contains("timeout") ||
                                        errorMsg.contains("Timeout")
                                    );

                                    if (isPrematureClose) {
                                        log.warn("SSE connection closed prematurely (client disconnected or network issue): {}", errorMsg);
                                    } else if (isTimeout) {
                                        log.warn("SSE stream timeout (OpenClaw tool execution may have taken too long): {}", errorMsg);
                                    } else if (errorMsg == null) {
                                        log.warn("SSE stream ended (possibly normal completion or null error)");
                                    } else {
                                        log.error("SSE stream error: {}", errorMsg, error);
                                    }
                                    return Flux.empty();
                                });
                    } else {
                        return response.createError()
                                .flatMapMany(error -> Flux.error(new RuntimeException("OpenClaw API error: " + response.statusCode())));
                    }
                })
                .doOnNext(event -> {
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
                .doOnError(error -> log.error("OpenClaw streaming API error", error));
    }

    /**
     * 解析 SSE 行
     */
    private Mono<StreamEvent> parseSseLine(String line) {
        if (line == null || line.isEmpty()) {
            return Mono.empty();
        }

        String data;
        if (line.startsWith("data:")) {
            data = line.substring(5).trim();
        } else {
            // 处理没有 data: 前缀的情况（bodyToFlux 可能已经处理了 SSE 格式）
            data = line.trim();
        }

        if ("[DONE]".equals(data)) {
            return Mono.just(new StreamEvent("done", null, null, null, null, true));
        }

        try {
            JsonNode root = objectMapper.readTree(data);

            // 调试日志 - 记录原始数据
            log.info("SSE raw data: {}", data.substring(0, Math.min(200, data.length())));

            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText("Unknown error");
                log.error("SSE error: {}", errorMsg);
                return Mono.just(new StreamEvent("error", errorMsg, null, null, null, true));
            }

            // 尝试多种可能的字段路径获取内容
            String content = null;

            // 1. 标准 OpenAI 格式: choices[0].delta.content
            if (root.has("choices") && root.path("choices").isArray()) {
                JsonNode choices = root.path("choices");
                if (choices.size() > 0) {
                    JsonNode firstChoice = choices.get(0);
                    JsonNode delta = firstChoice.path("delta");
                    content = delta.path("content").asText(null);

                    // 检查工具调用
                    JsonNode toolCalls = delta.path("tool_calls");
                    if (toolCalls != null && !toolCalls.isMissingNode() && toolCalls.isArray() && toolCalls.size() > 0) {
                        JsonNode firstToolCall = toolCalls.get(0);
                        String toolId = firstToolCall.path("id").asText(null);
                        String toolType = firstToolCall.path("type").asText("function");
                        JsonNode function = firstToolCall.path("function");
                        String toolName = function.path("name").asText(null);
                        String toolArguments = function.path("arguments").asText(null);

                        log.info("SSE tool_call detected: id={}, name={}, args={}",
                                toolId, toolName,
                                toolArguments != null ? toolArguments.substring(0, Math.min(100, toolArguments.length())) : "null");

                        // 如果是新工具调用（有 id），发送 tool_start 事件
                        if (toolId != null && !toolId.isEmpty() && toolName != null && !toolName.isEmpty()) {
                            return Mono.just(new StreamEvent("tool_start", null, toolName, toolArguments, toolId, false));
                        }
                        // 如果是工具参数更新（没有 id，只有 arguments），发送 tool_delta 事件
                        else if (toolArguments != null && !toolArguments.isEmpty()) {
                            return Mono.just(new StreamEvent("tool_delta", toolArguments, null, null, null, false));
                        }
                    }

                    // 检查 finish_reason
                    String finishReason = firstChoice.path("finish_reason").asText(null);
                    boolean isDone = "stop".equals(finishReason) || "tool_calls".equals(finishReason);

                    log.info("SSE OpenAI format - content: {}, finishReason: {}",
                            content != null ? "present(" + content.length() + " chars)" : "null",
                            finishReason);

                    if (content != null && !content.isEmpty()) {
                        return Mono.just(new StreamEvent("message", content, null, null, null, isDone));
                    } else if (isDone) {
                        return Mono.just(new StreamEvent("done", null, null, null, null, true));
                    }
                }
            }

            // 2. 直接 content 字段 (有些格式直接在根级别)
            if (content == null && root.has("content")) {
                content = root.path("content").asText(null);
                log.info("SSE root content format - content: {}",
                        content != null ? "present(" + content.length() + " chars)" : "null");
                if (content != null && !content.isEmpty()) {
                    return Mono.just(new StreamEvent("message", content, null, null, null, false));
                }
            }

            // 3. message 字段
            if (content == null && root.has("message")) {
                JsonNode message = root.path("message");
                if (message.has("content")) {
                    content = message.path("content").asText(null);
                    log.info("SSE message.content format - content: {}",
                            content != null ? "present(" + content.length() + " chars)" : "null");
                    if (content != null && !content.isEmpty()) {
                        return Mono.just(new StreamEvent("message", content, null, null, null, false));
                    }
                }
            }

            // 4. text 或 textDelta 字段
            if (content == null && root.has("text")) {
                content = root.path("text").asText(null);
                log.info("SSE text format - content: {}",
                        content != null ? "present(" + content.length() + " chars)" : "null");
                if (content != null && !content.isEmpty()) {
                    return Mono.just(new StreamEvent("message", content, null, null, null, false));
                }
            }

            if (content == null && root.has("textDelta")) {
                content = root.path("textDelta").asText(null);
                log.info("SSE textDelta format - content: {}",
                        content != null ? "present(" + content.length() + " chars)" : "null");
                if (content != null && !content.isEmpty()) {
                    return Mono.just(new StreamEvent("message", content, null, null, null, false));
                }
            }

            // 5. 检查是否有 done/completed 标记
            if (root.has("done") && root.path("done").asBoolean(false)) {
                log.info("SSE done flag detected");
                return Mono.just(new StreamEvent("done", null, null, null, null, true));
            }

            if (root.has("completed") && root.path("completed").asBoolean(false)) {
                log.info("SSE completed flag detected");
                return Mono.just(new StreamEvent("done", null, null, null, null, true));
            }

            log.warn("SSE unrecognized format: {}", data.substring(0, Math.min(200, data.length())));
            return Mono.empty();
        } catch (Exception e) {
            log.warn("Failed to parse SSE line: {}", line.substring(0, Math.min(100, line.length())), e);
            return Mono.empty();
        }
    }
}