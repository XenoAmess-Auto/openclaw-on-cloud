package com.ooc.openclaw;

import com.ooc.entity.ChatRoom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OpenClaw 响应解析器
 * 负责解析 OpenClaw SSE 响应中的工具调用信息
 */
@Slf4j
@Component
public class OpenClawResponseParser {

    // 常见工具名称列表
    private static final String[] COMMON_TOOLS = {
        "memory_search", "read", "write", "edit", "exec", 
        "web_search", "web_fetch", "weather", "browser", 
        "canvas", "nodes", "cron", "message", "gateway",
        "sessions_spawn", "tts", "github", "gh", "ordercli",
        "openhue", "sonoscli", "eightctl", "gifgrep", "gemini",
        "blogwatcher", "blucli", "healthcheck", "himalaya",
        "nano-pdf", "obsidian", "openai-whisper", "skill-creator",
        "songsee", "video-frames", "wacli", "1password", "gog"
    };

    /**
     * 解析内容中的工具调用信息
     * 
     * @param content OpenClaw 返回的完整内容
     * @return 工具调用列表
     */
    public List<ChatRoom.Message.ToolCall> parseToolCalls(String content) {
        List<ChatRoom.Message.ToolCall> toolCalls = new ArrayList<>();

        if (content == null) {
            log.debug("parseToolCalls: content is null");
            return toolCalls;
        }

        // 检查是否有 Tools used 部分（支持多种格式）
        String toolsMarker = "**Tools used:**";
        int toolsStart = content.indexOf(toolsMarker);

        if (toolsStart == -1) {
            // 尝试其他可能的格式
            toolsMarker = "Tools used:";
            toolsStart = content.indexOf(toolsMarker);
        }
        
        // 尝试中文格式
        if (toolsStart == -1) {
            toolsMarker = "**使用的工具：**";
            toolsStart = content.indexOf(toolsMarker);
        }

        if (toolsStart == -1) {
            log.debug("parseToolCalls: no Tools used section found in content of length {}", content.length());
            // 尝试从内容中直接检测工具调用（备选方案）
            return detectToolsFromContent(content);
        }

        log.info("parseToolCalls: found Tools used section at index {}", toolsStart);

        int toolsEnd = content.length();
        int searchStart = toolsStart + toolsMarker.length();

        // 查找 Tools used 部分的结束位置（下一个双换行或章节标题）
        int nextDoubleNewline = content.indexOf("\n\n", searchStart);
        int nextSection = content.indexOf("**", searchStart);

        if (nextDoubleNewline != -1 && (nextSection == -1 || nextDoubleNewline < nextSection)) {
            toolsEnd = nextDoubleNewline;
        } else if (nextSection != -1) {
            toolsEnd = nextSection;
        }

        String toolsSection = content.substring(toolsStart, Math.min(toolsEnd, content.length()));
        log.debug("parseToolCalls: tools section length = {}", toolsSection.length());
        log.debug("parseToolCalls: tools section content: {}", toolsSection);

        String[] toolLines = toolsSection.split("\n");

        for (String line : toolLines) {
            line = line.trim();
            // 支持多种格式：- `tool_name`: description 或 - tool_name: description
            if (line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ")) {
                String toolName = null;
                String description = "";

                // 尝试格式：- `tool_name`: description
                if (line.contains("`")) {
                    int nameStart = line.indexOf("`") + 1;
                    int nameEnd = line.indexOf("`", nameStart);
                    if (nameEnd > nameStart) {
                        toolName = line.substring(nameStart, nameEnd);
                        int descStart = line.indexOf(":", nameEnd);
                        if (descStart != -1 && descStart + 1 < line.length()) {
                            description = line.substring(descStart + 1).trim();
                        }
                    }
                } else {
                    // 尝试格式：- tool_name: description
                    int colonIndex = line.indexOf(":");
                    int spaceAfterPrefix = line.indexOf(" ");
                    if (spaceAfterPrefix > 0 && colonIndex > spaceAfterPrefix) {
                        toolName = line.substring(spaceAfterPrefix + 1, colonIndex).trim();
                        if (toolName.contains(" ") && !toolName.matches("[a-z_]+")) {
                            // 如果名称包含空格且不像工具名，可能不是有效的工具名
                            toolName = null;
                        } else {
                            description = line.substring(colonIndex + 1).trim();
                        }
                    }
                }

                if (toolName != null && !toolName.isEmpty()) {
                    // 清理工具名（去除可能的标点符号）
                    toolName = toolName.replaceAll("[：:]$", "").trim();
                    log.info("parseToolCalls: found tool '{}' with description '{}'", toolName, description);
                    toolCalls.add(ChatRoom.Message.ToolCall.builder()
                            .id(UUID.randomUUID().toString())
                            .name(toolName)
                            .description(description)
                            .status("completed")
                            .timestamp(Instant.now())
                            .build());
                }
            }
        }

        log.info("parseToolCalls: total {} tools found", toolCalls.size());
        return toolCalls;
    }

    /**
     * 从内容中直接检测工具调用（备选方案）
     * 用于当标准格式解析失败时
     *
     * @param content OpenClaw 返回的内容
     * @return 检测到的工具调用列表
     */
    public List<ChatRoom.Message.ToolCall> detectToolsFromContent(String content) {
        List<ChatRoom.Message.ToolCall> toolCalls = new ArrayList<>();

        if (content == null) {
            return toolCalls;
        }

        String lowerContent = content.toLowerCase();

        for (String toolName : COMMON_TOOLS) {
            // 检查内容中是否包含工具名称（作为独立单词）
            // 使用简单的字符串包含检查，避免正则复杂性问题
            String lowerToolName = toolName.toLowerCase();
            if (containsWord(lowerContent, lowerToolName)) {
                // 检查是否已经添加过
                boolean alreadyAdded = toolCalls.stream()
                    .anyMatch(tc -> tc.getName().equalsIgnoreCase(toolName));
                if (!alreadyAdded) {
                    log.info("detectToolsFromContent: detected tool '{}' from content", toolName);
                    toolCalls.add(ChatRoom.Message.ToolCall.builder()
                            .id(UUID.randomUUID().toString())
                            .name(toolName)
                            .description("从消息内容中检测到的工具调用")
                            .status("completed")
                            .timestamp(Instant.now())
                            .build());
                }
            }
        }

        if (!toolCalls.isEmpty()) {
            log.info("detectToolsFromContent: total {} tools detected", toolCalls.size());
        }
        return toolCalls;
    }

    /**
     * 检查字符串中是否包含完整的单词
     */
    private boolean containsWord(String text, String word) {
        int index = text.indexOf(word);
        while (index != -1) {
            boolean startValid = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
            boolean endValid = index + word.length() >= text.length()
                    || !Character.isLetterOrDigit(text.charAt(index + word.length()));
            if (startValid && endValid) {
                return true;
            }
            index = text.indexOf(word, index + 1);
        }
        return false;
    }

    /**
     * 从内容中解析 Tool details 并填充到工具调用中
     * 
     * @param content OpenClaw 返回的完整内容
     * @param toolCalls 已有的工具调用列表
     * @return 填充了详细信息的工具调用列表
     */
    public List<ChatRoom.Message.ToolCall> enrichToolCallsWithDetails(String content, 
            List<ChatRoom.Message.ToolCall> toolCalls) {
        if (content == null || toolCalls == null || toolCalls.isEmpty()) {
            return toolCalls;
        }

        // 查找 **Tool details:** 部分
        String detailsMarker = "**Tool details:**";
        int detailsStart = content.indexOf(detailsMarker);

        if (detailsStart == -1) {
            // 尝试其他可能的格式
            detailsMarker = "Tool details:";
            detailsStart = content.indexOf(detailsMarker);
        }
        
        // 尝试中文格式
        if (detailsStart == -1) {
            detailsMarker = "**工具详情：**";
            detailsStart = content.indexOf(detailsMarker);
        }
        
        // 尝试从 Tools used 部分之后查找工具详情
        if (detailsStart == -1) {
            // 如果没有明确的 Tool details 标记，尝试从 Tools used 部分之后解析
            String toolsMarker = "**Tools used:**";
            int toolsStart = content.indexOf(toolsMarker);
            if (toolsStart == -1) {
                toolsMarker = "Tools used:";
                toolsStart = content.indexOf(toolsMarker);
            }
            if (toolsStart != -1) {
                // 从 Tools used 之后开始查找工具详情
                detailsStart = toolsStart;
                detailsMarker = toolsMarker;
            }
        }

        if (detailsStart == -1) {
            log.debug("enrichToolCallsWithDetails: no Tool details section found");
            return toolCalls;
        }

        log.info("enrichToolCallsWithDetails: found Tool details section at index {}", detailsStart);

        // 提取 Tool details 部分（到下一个 ** 章节或文件结束）
        int detailsContentStart = detailsStart + detailsMarker.length();
        int nextSection = content.indexOf("**", detailsContentStart);
        int detailsEnd = (nextSection != -1) ? nextSection : content.length();
        String detailsSection = content.substring(detailsContentStart, detailsEnd).trim();
        
        log.debug("enrichToolCallsWithDetails: details section length = {}", detailsSection.length());

        // 为每个工具调用查找对应的详细输出
        for (ChatRoom.Message.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.getName();
            if (toolName == null || toolName.isEmpty()) continue;

            // 查找工具名开头的行（支持多种格式）
            String[] possibleHeaders = {
                "- `" + toolName + "`:",
                "- " + toolName + ":",
                "• `" + toolName + "`:",
                "• " + toolName + ":",
                "`" + toolName + "`",
                toolName + ":"
            };
            
            int toolHeaderIndex = -1;
            String matchedHeader = null;
            
            for (String header : possibleHeaders) {
                toolHeaderIndex = detailsSection.indexOf(header);
                if (toolHeaderIndex != -1) {
                    matchedHeader = header;
                    break;
                }
            }

            if (toolHeaderIndex == -1) continue;

            // 找到工具内容开始的位置（工具名之后）
            int contentStart = toolHeaderIndex + matchedHeader.length();
            // 跳过冒号和空白
            while (contentStart < detailsSection.length() &&
                   (detailsSection.charAt(contentStart) == ':' ||
                    Character.isWhitespace(detailsSection.charAt(contentStart)))) {
                contentStart++;
            }

            // 查找下一个工具的开始位置
            String remaining = detailsSection.substring(contentStart);
            String[] lines = remaining.split("\n");
            StringBuilder toolResult = new StringBuilder();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                // 检查是否是下一个工具的开始
                if (i > 0 && (line.startsWith("- `") || line.startsWith("- ") ||
                    line.startsWith("• `") || line.startsWith("• "))) {
                    // 这可能是下一个工具，停止收集
                    boolean isNextTool = false;
                    for (ChatRoom.Message.ToolCall otherTool : toolCalls) {
                        if (otherTool != toolCall && otherTool.getName() != null) {
                            String otherName = otherTool.getName();
                            if (line.contains("`" + otherName + "`") || 
                                line.startsWith("- " + otherName + ":") ||
                                line.startsWith("• " + otherName + ":")) {
                                isNextTool = true;
                                break;
                            }
                        }
                    }
                    if (isNextTool) break;
                }
                if (i > 0) toolResult.append("\n");
                toolResult.append(line);
            }

            String result = toolResult.toString().trim();
            if (!result.isEmpty()) {
                // 如果结果包含代码块，提取代码块内容
                if (result.contains("```")) {
                    int codeStart = result.indexOf("```");
                    int codeEnd = result.indexOf("```", codeStart + 3);
                    if (codeEnd > codeStart) {
                        // 提取代码块内容（包括语言标识）
                        String codeBlock = result.substring(codeStart, codeEnd + 3);
                        toolCall.setResult(codeBlock);
                    } else {
                        toolCall.setResult(result);
                    }
                } else {
                    toolCall.setResult(result);
                }
                log.info("enrichToolCallsWithDetails: set result for tool '{}' (length={})",
                        toolName, result.length());
            }
        }

        return toolCalls;
    }

    /**
     * 解析 SSE 事件行
     * 
     * @param line SSE 数据行
     * @return 解析后的 OpenClawStreamEvent，如果无法解析则返回 null
     */
    public OpenClawStreamEvent parseSseLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        try {
            // line 可能直接是 JSON 数据（WebClient 已剥离 "data: " 前缀）
            // 也可能仍然包含 "data: " 前缀
            String jsonData = line;
            if (line.startsWith("data: ")) {
                jsonData = line.substring(6);
            }

            // 检查流结束标记
            if ("[DONE]".equals(jsonData.trim())) {
                return new OpenClawStreamEvent("done", null, null, null, null, true);
            }

            // 尝试解析为 JSON
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(jsonData);

            String eventType = node.has("type") ? node.get("type").asText() : "unknown";
            String content = null;
            String toolName = null;
            String toolInput = null;
            String messageId = node.has("id") ? node.get("id").asText() : null;

            // 处理不同类型的消息
            if (node.has("choices") && node.get("choices").isArray() && node.get("choices").size() > 0) {
                com.fasterxml.jackson.databind.JsonNode choice = node.get("choices").get(0);

                if (choice.has("delta")) {
                    com.fasterxml.jackson.databind.JsonNode delta = choice.get("delta");

                    // 处理内容增量
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        content = delta.get("content").asText();
                    }

                    // 处理工具调用
                    if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
                        com.fasterxml.jackson.databind.JsonNode toolCalls = delta.get("tool_calls");
                        for (com.fasterxml.jackson.databind.JsonNode tc : toolCalls) {
                            if (tc.has("function")) {
                                com.fasterxml.jackson.databind.JsonNode function = tc.get("function");
                                if (function.has("name")) {
                                    toolName = function.get("name").asText();
                                }
                                if (function.has("arguments")) {
                                    toolInput = function.get("arguments").asText();
                                }
                            }
                        }
                    }
                }
            }

            // 处理直接 delta 格式（如 content_block_delta 类型）
            if (content == null && node.has("delta")) {
                com.fasterxml.jackson.databind.JsonNode delta = node.get("delta");
                if (delta.has("text") && !delta.get("text").isNull()) {
                    content = delta.get("text").asText();
                }
            }

            return new OpenClawStreamEvent(eventType, content, toolName, toolInput, messageId, false);
        } catch (Exception e) {
            log.debug("Failed to parse SSE line: {}", line, e);
            return null;
        }
    }

    /**
     * OpenClaw 流式事件
     */
    public record OpenClawStreamEvent(
            String type,
            String content,
            String toolName,
            String toolInput,
            String messageId,
            boolean completed
    ) {}
}
