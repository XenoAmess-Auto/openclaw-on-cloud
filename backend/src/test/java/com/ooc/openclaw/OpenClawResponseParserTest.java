package com.ooc.openclaw;

import com.ooc.entity.ChatRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenClawResponseParser 单元测试
 */
class OpenClawResponseParserTest {

    private OpenClawResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new OpenClawResponseParser();
    }

    @Test
    void parseToolCalls_WithStandardFormat_ShouldReturnTools() {
        // Given
        String content = """
                Hello, I can help you with that.
                
                **Tools used:**
                - `memory_search`: Search user memory
                - `read`: Read a file
                
                Let me know if you need more help.
                """;

        // When
        List<ChatRoom.Message.ToolCall> result = parser.parseToolCalls(content);

        // Then
        assertEquals(2, result.size());
        assertEquals("memory_search", result.get(0).getName());
        assertEquals("Search user memory", result.get(0).getDescription());
        assertEquals("read", result.get(1).getName());
        assertEquals("completed", result.get(0).getStatus());
    }

    @Test
    void parseToolCalls_WithChineseFormat_ShouldReturnTools() {
        // Given
        String content = """
                **使用的工具：**
                - `web_search`: 搜索网页
                - `exec`: 执行命令
                """;

        // When
        List<ChatRoom.Message.ToolCall> result = parser.parseToolCalls(content);

        // Then
        assertEquals(2, result.size());
        assertEquals("web_search", result.get(0).getName());
        assertEquals("搜索网页", result.get(0).getDescription());
    }

    @Test
    void parseToolCalls_WithNoToolsSection_ShouldReturnEmptyList() {
        // Given
        String content = "Hello, I can help you with that.";

        // When
        List<ChatRoom.Message.ToolCall> result = parser.parseToolCalls(content);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void parseToolCalls_WithNullContent_ShouldReturnEmptyList() {
        // When
        List<ChatRoom.Message.ToolCall> result = parser.parseToolCalls(null);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void detectToolsFromContent_WithCommonTools_ShouldDetect() {
        // Given
        String content = """
                I will use memory_search to find the information,
                then use write to save the file.
                """;

        // When
        List<ChatRoom.Message.ToolCall> result = parser.detectToolsFromContent(content);

        // Then
        assertTrue(result.size() >= 2);
        assertTrue(result.stream().anyMatch(t -> t.getName().equals("memory_search")));
        assertTrue(result.stream().anyMatch(t -> t.getName().equals("write")));
    }

    @Test
    void enrichToolCallsWithDetails_WithDetailsSection_ShouldEnrich() {
        // Given
        String content = """
                **Tools used:**
                - `read`: Read file
                
                **Tool details:**
                - `read`:
                ```java
                public class Test {}
                ```
                """;
        
        List<ChatRoom.Message.ToolCall> toolCalls = List.of(
                ChatRoom.Message.ToolCall.builder()
                        .id("1")
                        .name("read")
                        .description("Read file")
                        .status("completed")
                        .build()
        );

        // When
        List<ChatRoom.Message.ToolCall> result = parser.enrichToolCallsWithDetails(content, toolCalls);

        // Then
        assertNotNull(result.get(0).getResult());
        assertTrue(result.get(0).getResult().contains("public class Test"));
    }

    @Test
    void parseSseLine_WithValidEvent_ShouldParse() {
        // Given
        String line = """
                {"type":"content_block_delta","delta":{"text":"Hello"}}
                """;

        // When
        OpenClawResponseParser.OpenClawStreamEvent event = parser.parseSseLine(line);

        // Then
        assertNotNull(event);
        assertEquals("content_block_delta", event.type());
        assertEquals("Hello", event.content());
    }

    @Test
    void parseSseLine_WithDoneEvent_ShouldReturnCompleted() {
        // Given
        String line = "data: [DONE]";

        // When
        OpenClawResponseParser.OpenClawStreamEvent event = parser.parseSseLine(line);

        // Then
        assertNotNull(event);
        assertTrue(event.completed());
    }

    @Test
    void parseSseLine_WithInvalidJson_ShouldReturnNull() {
        // Given
        String line = "not valid json";

        // When
        OpenClawResponseParser.OpenClawStreamEvent event = parser.parseSseLine(line);

        // Then
        assertNull(event);
    }

    @Test
    void parseSseLine_WithNullLine_ShouldReturnNull() {
        // When
        OpenClawResponseParser.OpenClawStreamEvent event = parser.parseSseLine(null);

        // Then
        assertNull(event);
    }

    @Test
    void enrichToolCallsWithDetails_WithNullToolCalls_ShouldReturnNull() {
        // When
        List<ChatRoom.Message.ToolCall> result = parser.enrichToolCallsWithDetails("content", null);

        // Then
        assertNull(result);
    }
}
