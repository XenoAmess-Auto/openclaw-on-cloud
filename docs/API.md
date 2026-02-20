# OOC API 文档

OpenClaw on Cloud (OOC) RESTful API 文档

## 基础信息

- **Base URL**: `http://localhost:8081/api`
- **认证方式**: JWT Bearer Token
- **Content-Type**: `application/json`

## 认证

### 登录

```http
POST /auth/login
```

**请求体:**
```json
{
  "username": "string",
  "password": "string"  // RSA 加密后的密码
}
```

**响应:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "user": {
    "id": "string",
    "username": "string",
    "nickname": "string",
    "avatar": "string",
    "roles": ["ROLE_USER"]
  }
}
```

### 注册

```http
POST /auth/register
```

**请求体:**
```json
{
  "username": "string",
  "password": "string",
  "nickname": "string"  // 可选
}
```

### 获取 RSA 公钥

```http
GET /auth/public-key
```

## 聊天室

### 创建聊天室

```http
POST /chat-rooms
Authorization: Bearer {token}
```

**请求体:**
```json
{
  "name": "string",
  "description": "string"  // 可选
}
```

### 获取我的聊天室列表

```http
GET /chat-rooms
Authorization: Bearer {token}
```

**响应:**
```json
[
  {
    "id": "string",
    "name": "string",
    "description": "string",
    "creatorId": "string",
    "memberIds": ["string"],
    "createdAt": "2024-01-01T00:00:00Z",
    "updatedAt": "2024-01-01T00:00:00Z"
  }
]
```

### 获取聊天室详情

```http
GET /chat-rooms/{roomId}
Authorization: Bearer {token}
```

### 获取聊天室成员

```http
GET /chat-rooms/{roomId}/members
Authorization: Bearer {token}
```

### 搜索聊天室成员

```http
GET /chat-rooms/{roomId}/members/search?q={query}
Authorization: Bearer {token}
```

### 添加成员

```http
POST /chat-rooms/{roomId}/members?userId={userId}
Authorization: Bearer {token}
```

**权限**: 仅聊天室创建者可添加成员

### 移除成员

```http
DELETE /chat-rooms/{roomId}/members/{userId}
Authorization: Bearer {token}
```

**权限**: 仅聊天室创建者可移除成员

### 删除聊天室

```http
DELETE /chat-rooms/{roomId}
Authorization: Bearer {token}
```

**权限**: 仅聊天室创建者可删除

## 消息

### 获取聊天室消息 (分页)

```http
GET /chat-rooms/{roomId}/messages?page={page}&size={size}&before={timestamp}
Authorization: Bearer {token}
```

**参数:**
- `page`: 页码 (默认 0)
- `size`: 每页大小 (默认 20)
- `before`: 游标时间戳 (ISO 8601 格式)，获取此时间之前的消息

**响应:**
```json
[
  {
    "id": "string",
    "senderId": "string",
    "senderName": "string",
    "senderAvatar": "string",
    "content": "string",
    "timestamp": "2024-01-01T00:00:00Z",
    "isSystem": false,
    "isStreaming": false,
    "isToolCall": false,
    "fromOpenClaw": false,
    "toolCalls": [
      {
        "id": "string",
        "name": "string",
        "description": "string",
        "status": "completed"
      }
    ],
    "attachments": [
      {
        "name": "string",
        "url": "string",
        "type": "string",
        "size": 1024
      }
    ]
  }
]
```

### 发送消息

```http
POST /chat-rooms/{roomId}/messages
Authorization: Bearer {token}
```

**请求体:**
```json
{
  "content": "string",
  "attachments": [
    {
      "name": "string",
      "url": "string",
      "type": "string",
      "size": 1024
    }
  ]
}
```

**说明**: 消息内容中包含 `@openclaw`、`@kimi` 或 `@claude` 将触发对应的 AI 机器人响应。

## 任务队列

### 获取任务队列状态

```http
GET /chat-rooms/{roomId}/queue
Authorization: Bearer {token}
```

**响应:**
```json
{
  "roomId": "string",
  "isProcessing": true,
  "queueSize": 5,
  "tasks": [
    {
      "taskId": "string",
      "status": "PROCESSING",
      "createdAt": "2024-01-01T00:00:00Z",
      "senderName": "string",
      "content": "string...",
      "botType": "openclaw"
    }
  ]
}
```

### 重新排序任务队列

```http
POST /chat-rooms/{roomId}/queue/reorder
Authorization: Bearer {token}
```

**请求体:**
```json
{
  "taskIds": ["task-id-1", "task-id-2", "task-id-3"]
}
```

### 取消任务

```http
DELETE /chat-rooms/{roomId}/queue/{taskId}
Authorization: Bearer {token}
```

## 会话 (Session)

### 获取聊天室会话列表

```http
GET /sessions/chat-room/{roomId}
Authorization: Bearer {token}
```

**响应:**
```json
[
  {
    "id": "string",
    "chatRoomId": "string",
    "chatRoomName": "string",
    "messageCount": 100,
    "summary": "string",
    "archived": false,
    "createdAt": "2024-01-01T00:00:00Z",
    "updatedAt": "2024-01-01T00:00:00Z"
  }
]
```

### 归档会话

```http
POST /sessions/{sessionId}/archive
Authorization: Bearer {token}
```

### 复制会话

```http
POST /sessions/{sessionId}/copy?newChatRoomId={roomId}
Authorization: Bearer {token}
```

## 提及 (@Mentions)

### 获取我的提及列表

```http
GET /mentions
Authorization: Bearer {token}
```

**响应:**
```json
[
  {
    "id": "string",
    "messageId": "string",
    "chatRoomId": "string",
    "chatRoomName": "string",
    "senderId": "string",
    "senderName": "string",
    "content": "string",
    "read": false,
    "createdAt": "2024-01-01T00:00:00Z"
  }
]
```

### 标记提及为已读

```http
POST /mentions/{mentionId}/read
Authorization: Bearer {token}
```

### 标记所有提及为已读

```http
POST /mentions/read-all
Authorization: Bearer {token}
```

## 文件

### 上传文件

```http
POST /files/upload
Authorization: Bearer {token}
Content-Type: multipart/form-data
```

**请求体:**
- `file`: 文件内容

**响应:**
```json
{
  "url": "/api/files/{fileKey}",
  "name": "filename.jpg",
  "size": 1024,
  "contentType": "image/jpeg"
}
```

### 获取文件

```http
GET /files/{fileKey}
```

## 流程图模板

### 获取模板列表

```http
GET /flowchart-templates
Authorization: Bearer {token}
```

### 获取模板详情

```http
GET /flowchart-templates/{templateId}
Authorization: Bearer {token}
```

### 从模板创建流程图

```http
POST /flowchart-templates/{templateId}/instantiate
Authorization: Bearer {token}
```

## WebSocket

### 连接

```
ws://localhost:8081/ws/chat
```

**连接时需要在 URL 中携带 Token:**
```
ws://localhost:8081/ws/chat?token={jwt_token}
```

### 消息格式

**客户端 → 服务器:**
```json
{
  "type": "join_room",
  "roomId": "string"
}
```

```json
{
  "type": "send_message",
  "roomId": "string",
  "content": "string",
  "attachments": []
}
```

```json
{
  "type": "typing",
  "roomId": "string"
}
```

**服务器 → 客户端:**
```json
{
  "type": "message",
  "message": { /* Message Object */ }
}
```

```json
{
  "type": "stream_start",
  "message": { /* Streaming Message Object */ }
}
```

```json
{
  "type": "stream_delta",
  "messageId": "string",
  "delta": "string content"
}
```

```json
{
  "type": "stream_end",
  "message": { /* Final Message Object */ }
}
```

```json
{
  "type": "tool_call",
  "roomId": "string",
  "toolCall": {
    "id": "string",
    "name": "string",
    "status": "running"
  }
}
```

```json
{
  "type": "notification",
  "notification": {
    "type": "mention",
    "message": "string"
  }
}
```

## 错误响应

### 通用错误格式

```json
{
  "timestamp": "2024-01-01T00:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "详细错误信息",
  "path": "/api/chat-rooms"
}
```

### HTTP 状态码

| 状态码 | 含义 |
|--------|------|
| 200 | 成功 |
| 201 | 创建成功 |
| 400 | 请求参数错误 |
| 401 | 未授权 (Token 无效或过期) |
| 403 | 禁止访问 (权限不足) |
| 404 | 资源不存在 |
| 409 | 资源冲突 |
| 500 | 服务器内部错误 |

## 机器人触发指令

在聊天室中发送包含以下提及的消息将触发对应 AI 机器人:

| 提及 | 机器人 | 说明 |
|------|--------|------|
| `@openclaw` | OpenClaw | 主 AI 助手，支持工具调用 |
| `@kimi` | Kimi | Moonshot Kimi AI |
| `@claude` | Claude | Anthropic Claude AI |

**示例:**
```
@openclaw 帮我解释一下这段代码
@kimi 翻译这段文字成英文
@claude 写一个 Python 函数来计算斐波那契数列
```
