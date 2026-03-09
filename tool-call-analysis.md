// 诊断脚本：检查 OOC 平台工具调用展示问题

## 问题描述
OOC 平台无法正常展示 OpenClaw 原生工具调用

## 代码分析

### 后端 (ChatWebSocketHandler.java)
1. `executeOpenClawTask` 创建流式消息时初始化空的 `toolCalls` 列表
2. `handleOpenClawStreamEvent` 处理 `tool_start` 和 `tool_result` 事件
3. 广播事件到前端：type 为 "tool_start" 或 "tool_result"

### 前端 (chat.ts)
1. `handleMessage` 处理 WebSocket 消息
2. `tool_start` 事件：查找消息并添加新工具调用
3. `tool_result` 事件：更新工具调用状态为 completed
4. `stream_end` 事件：保留现有的 toolCalls（如果后端没有发送）

### 前端 (MessageItem.vue)
1. 当 `message.isToolCall && message.toolCalls?.length` 为真时显示工具调用
2. 显示工具名称、描述和状态

## 发现的问题

### 问题 1: tool_start 中的 toolCalls 合并逻辑可能重复
```typescript
const existingToolCalls = updatedMsg.toolCalls || []
const newToolCalls = data.message.toolCalls || []
updatedMsg.toolCalls = [...existingToolCalls, ...newToolCalls]
```
如果同一个工具调用被多次广播，会重复添加。

### 问题 2: 状态值可能不匹配
前端: `'running' | 'completed' | 'error'`
后端: `'running' | 'completed'`

### 问题 3: stream_end 处理中可能覆盖 toolCalls
虽然代码尝试保留现有的 toolCalls，但 `data.message` 可能会覆盖它。

## 修复方案

### 1. 修复 chat.ts 中的 tool_start 处理
需要检查 toolCallId 是否已存在，避免重复添加：

```typescript
case 'tool_start':
{
  const index = messages.value.findIndex(m => m.id === data.message.id)
  if (index !== -1) {
    const updatedMsg = { ...messages.value[index] }
    const newToolCall = data.message.toolCalls?.[0]
    if (newToolCall) {
      const existingToolCalls = updatedMsg.toolCalls || []
      // 检查是否已存在相同的 toolCallId
      const exists = existingToolCalls.some(tc => tc.id === newToolCall.id)
      if (!exists) {
        updatedMsg.toolCalls = [...existingToolCalls, newToolCall]
        updatedMsg.isToolCall = true
        messages.value.splice(index, 1, updatedMsg)
        console.log('[WebSocket] tool_start - added tool call:', newToolCall.name)
      } else {
        console.log('[WebSocket] tool_start - tool call already exists:', newToolCall.id)
      }
    }
  } else {
    console.warn('[WebSocket] tool_start - message not found:', data.message.id)
  }
}
break
```

### 2. 修复 chat.ts 中的 tool_result 处理
确保正确更新现有工具调用的状态：

```typescript
case 'tool_result':
{
  const index = messages.value.findIndex(m => m.id === data.message.id)
  if (index !== -1) {
    const updatedMsg = { ...messages.value[index] }
    // 后端发送的是完整的 toolCalls 列表
    updatedMsg.toolCalls = data.message.toolCalls || []
    updatedMsg.isToolCall = updatedMsg.toolCalls.length > 0
    messages.value.splice(index, 1, updatedMsg)
    console.log('[WebSocket] tool_result - updated tool calls:', updatedMsg.toolCalls?.length)
  } else {
    console.warn('[WebSocket] tool_result - message not found:', data.message.id)
  }
}
break
```

### 3. 修复 chat.ts 中的 stream_end 处理
确保不覆盖已有的 toolCalls：

```typescript
case 'stream_end':
{
  console.log('[WebSocket] stream_end - received message:', {
    id: data.message?.id,
    contentLength: data.message?.content?.length,
    isToolCall: data.message?.isToolCall,
    toolCallsCount: data.message?.toolCalls?.length
  })
  const index = messages.value.findIndex(m => m.id === data.message.id)
  if (index !== -1) {
    const existingMsg = messages.value[index]
    // 合并消息数据，保留现有的 toolCalls（如果后端没有发送）
    const mergedMessage = { ...data.message }
    
    // 保留 toolCalls（如果后端没有发送）
    if ((!data.message.toolCalls || data.message.toolCalls.length === 0) 
        && existingMsg.toolCalls && existingMsg.toolCalls.length > 0) {
      mergedMessage.toolCalls = existingMsg.toolCalls
      mergedMessage.isToolCall = true
      console.log('[WebSocket] stream_end - preserved existing toolCalls:', existingMsg.toolCalls.length)
    }
    
    // 保留 replyToMessageId
    if (!data.message.replyToMessageId && existingMsg.replyToMessageId) {
      mergedMessage.replyToMessageId = existingMsg.replyToMessageId
    }
    
    messages.value.splice(index, 1, mergedMessage)
    console.log('[WebSocket] stream_end - message replaced at index:', index)
  } else {
    messages.value.push(data.message)
    console.log('[WebSocket] stream_end - message appended:', data.message.id)
  }
  deduplicateMessages()
}
break
```

### 4. 更新前端 ToolCall 类型定义
添加缺失的状态值：

```typescript
export interface ToolCall {
  id: string
  name: string
  description?: string
  status: 'pending' | 'running' | 'completed' | 'failed' | 'error'
  result?: string
  timestamp: string
  position?: number
}
```

### 5. 更新 MessageItem.vue 中的状态文本映射
```typescript
function getToolStatusText(status?: string): string {
  const statusMap: Record<string, string> = {
    pending: '等待中',
    running: '执行中',
    completed: '已完成',
    failed: '失败',
    error: '错误'
  }
  return statusMap[status || ''] || status || '未知'
}
```

## 测试步骤

1. 在 OOC 平台中 @OpenClaw 并询问需要工具调用的问题（如 "查看当前目录的文件"）
2. 观察工具调用是否正确显示
3. 检查浏览器控制台是否有错误日志
4. 检查后端日志中 `tool_start` 和 `tool_result` 事件是否正常发送

## 关键日志检查

后端日志中应该出现：
- "Tool call started for task {}: id={}, name={}, position={}"
- "Tool result received for task {}: toolCallId={}"
- "Updated tool call {} to completed status"

前端控制台应该出现：
- "[WebSocket] tool_start - added tool call: {toolName}"
- "[WebSocket] tool_result - updated tool calls: {count}"
- "[WebSocket] stream_end - preserved existing toolCalls: {count}"（如果后端没有发送 toolCalls）
