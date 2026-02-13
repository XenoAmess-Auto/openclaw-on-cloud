package com.ooc.controller;

import com.ooc.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final ChatWebSocketHandler chatWebSocketHandler;

    @PostMapping("/message")
    public ResponseEntity<Void> sendTestMessage(
            @RequestParam String roomId,
            @RequestBody(required = false) String content) {
        String msgContent = content != null ? content : getDefaultTestContent();
        chatWebSocketHandler.sendSystemMessage(roomId, msgContent);
        log.info("Test message sent to room: {}", roomId);
        return ResponseEntity.ok().build();
    }

    private String getDefaultTestContent() {
        return "# Markdown 渲染测试\\n\\n" +
               "## 代码块测试\\n\\n" +
               "### JavaScript\\n" +
               "```javascript\\n" +
               "function fibonacci(n) {\\n" +
               "  if (n <= 1) return n;\\n" +
               "  return fibonacci(n - 1) + fibonacci(n - 2);\\n" +
               "}\\n" +
               "const result = fibonacci(10);\\n" +
               "console.log(\"F(10) = \" + result);\\n" +
               "```\\n\\n" +
               "### TypeScript\\n" +
               "```typescript\\n" +
               "interface User {\\n" +
               "  id: number;\\n" +
               "  name: string;\\n" +
               "  email: string;\\n" +
               "}\\n\\n" +
               "class UserService {\\n" +
               "  async getUser(id: number): Promise<User> {\\n" +
               "    const response = await fetch(`/api/users/${id}`);\\n" +
               "    return response.json();\\n" +
               "  }\\n" +
               "}\\n" +
               "```\\n\\n" +
               "### Python\\n" +
               "```python\\n" +
               "def quick_sort(arr):\\n" +
               "    if len(arr) <= 1:\\n" +
               "        return arr\\n" +
               "    pivot = arr[len(arr) // 2]\\n" +
               "    left = [x for x in arr if x < pivot]\\n" +
               "    middle = [x for x in arr if x == pivot]\\n" +
               "    right = [x for x in arr if x > pivot]\\n" +
               "    return quick_sort(left) + middle + quick_sort(right)\\n" +
               "```\\n\\n" +
               "### Bash\\n" +
               "```bash\\n" +
               "#!/bin/bash\\n" +
               "echo \"开始部署...\"\\n" +
               "git pull origin main\\n" +
               "pnpm build\\n" +
               "echo \"部署完成!\"\\n" +
               "```\\n\\n" +
               "---\\n\\n" +
               "## 表格测试\\n\\n" +
               "| 功能 | 状态 | 说明 |\\n" +
               "|------|------|------|\\n" +
               "| 代码高亮 | ✅ | highlight.js 集成 |\\n" +
               "| 复制按钮 | ✅ | 悬停显示 |\\n" +
               "| 语言标签 | ✅ | 右上角显示 |\\n\\n" +
               "---\\n\\n" +
               "测试完成！检查代码高亮、复制按钮、语言标签是否正常显示。";
    }
}
