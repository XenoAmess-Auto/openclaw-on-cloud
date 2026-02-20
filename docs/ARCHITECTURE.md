# OOC 架构设计文档

OpenClaw on Cloud (OOC) 系统架构设计

## 系统概览

OOC 是一个基于 Web 的 AI 助手协作平台，支持多用户实时聊天、AI 机器人集成和流程图编排。

```
┌─────────────────────────────────────────────────────────────┐
│                         前端 (Vue 3)                         │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐ │
│  │   HomeView   │ │ Flowchart    │ │     Settings         │ │
│  │   (聊天)      │ │   (流程图)    │ │    (设置)            │ │
│  └──────────────┘ └──────────────┘ └──────────────────────┘ │
│         │                  │                    │           │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              Pinia Store (状态管理)                    │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │  │
│  │  │  auth    │ │  chat    │ │ flowchart│ │  user    │  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ WebSocket / HTTP
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    后端 (Spring Boot)                        │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              WebSocket (实时通信)                      │  │
│  │         ChatWebSocketHandler (2300+ 行)               │  │
│  └──────────────────────────────────────────────────────┘  │
│                              │                              │
│  ┌──────────────┬─────────────┼─────────────┬──────────────┐ │
│  │              │             │             │              │ │
│  ▼              ▼             ▼             ▼              ▼ │
│ ┌────────┐ ┌────────┐ ┌────────────┐ ┌──────────┐ ┌────────┐│
│ │ REST   │ │ Session│ │  ChatRoom  │ │ Mention  │ │ File   ││
│ │Controller│ │Service │ │  Service   │ │ Service  │ │Service ││
│ └────────┘ └────────┘ └────────────┘ └──────────┘ └────────┘│
│       │           │            │            │         │     │
│       └───────────┴────────────┴────────────┴─────────┘     │
│                              │                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              AI 机器人服务层                          │  │
│  │  ┌────────────┐ ┌──────────┐ ┌──────────────────────┐│  │
│  │  │OpenClaw    │ │  Kimi    │ │   Claude Code        ││  │
│  │  │PluginService│ │PluginService│ │   PluginService    ││  │
│  │  └────────────┘ └──────────┘ └──────────────────────┘│  │
│  └──────────────────────────────────────────────────────┘  │
│                              │                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              数据持久化层                             │  │
│  │  ┌────────────┐ ┌──────────┐ ┌──────────────────────┐│  │
│  │  │  MongoDB   │ │ (Redis)  │ │   File Storage       ││  │
│  │  │ (主数据库)  │ │ (缓存)   │ │   (文件存储)          ││  │
│  │  └────────────┘ └──────────┘ └──────────────────────┘│  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 技术栈

### 前端
- **框架**: Vue 3 + TypeScript
- **构建工具**: Vite
- **状态管理**: Pinia
- **样式**: CSS Variables + Scoped Styles
- **HTTP 客户端**: Axios
- **Markdown**: Marked.js + DOMPurify

### 后端
- **框架**: Spring Boot 3.x
- **语言**: Java 21
- **数据库**: MongoDB
- **WebSocket**: Spring WebSocket
- **反应式编程**: Project Reactor (WebClient)
- **构建工具**: Maven

## 核心模块

### 1. 认证模块 (Auth)

**职责**: 用户认证与授权

**关键组件**:
- `AuthController`: 登录/注册接口
- `JwtTokenProvider`: JWT Token 生成与验证
- `RsaKeyPairGenerator`: RSA 密钥对生成（密码加密）

**安全机制**:
- 密码使用 RSA 加密传输
- JWT Token 用于后续请求认证
- Token 有效期 24 小时

### 2. 聊天室模块 (Chat Room)

**职责**: 聊天室生命周期管理

**关键组件**:
- `ChatRoomController`: REST API
- `ChatRoomService`: 业务逻辑
- `ChatRoomRepository`: 数据访问
- `ChatRoom`: MongoDB 实体

**数据结构**:
```java
class ChatRoom {
    String id;
    String name;
    String description;
    String creatorId;
    Set<String> memberIds;
    List<Message> messages;
    List<OpenClawSession> openClawSessions;
    Instant createdAt;
    Instant updatedAt;
}
```

### 3. WebSocket 模块 (实时通信)

**职责**: 实时消息传输、AI 流式响应

**关键组件**:
- `ChatWebSocketHandler`: 核心 WebSocket 处理器
- `WebSocketConfig`: WebSocket 配置
- `WebSocketMessage`: 消息协议

**消息类型**:
| 类型 | 方向 | 说明 |
|------|------|------|
| `join_room` | C→S | 加入聊天室 |
| `send_message` | C→S | 发送消息 |
| `message` | S→C | 新消息通知 |
| `stream_start` | S→C | 流式响应开始 |
| `stream_delta` | S→C | 流式内容增量 |
| `stream_end` | S→C | 流式响应结束 |
| `tool_call` | S→C | 工具调用通知 |

### 4. AI 机器人插件模块

**职责**: 集成多个 AI 服务提供商

**架构设计**:
```
┌─────────────────────────────────────────┐
│           ChatRoomController            │
│              (消息接收)                  │
└─────────────────┬───────────────────────┘
                  │ 检测 @提及
        ┌─────────┼─────────┐
        ▼         ▼         ▼
┌───────────┐ ┌───────┐ ┌───────────────┐
│ OpenClaw  │ │ Kimi  │ │     Claude    │
│  Service  │ │Service│ │    Service    │
└─────┬─────┘ └───┬───┘ └───────┬───────┘
      │           │             │
      ▼           ▼             ▼
┌─────────────────────────────────────────┐
│          External AI APIs               │
│  ┌─────────────┐ ┌───────────────────┐  │
│  │  OpenClaw   │ │  Anthropic Claude │  │
│  │  Gateway    │ │      API          │  │
│  └─────────────┘ └───────────────────┘  │
└─────────────────────────────────────────┘
```

### 5. 会话管理模块 (OocSession)

**职责**: 维护 AI 对话上下文

**核心功能**:
- 会话创建与归档
- 消息历史管理
- 长会话自动摘要压缩

**自动摘要触发条件**:
- 消息数超过 30 条时触发
- 使用 AI 生成对话摘要
- 保留摘要，丢弃详细消息

### 6. 任务队列模块 (Task Queue)

**职责**: AI 请求排队与调度

**设计模式**: 生产者-消费者

```
┌──────────┐    ┌──────────┐    ┌──────────┐
│  User    │───▶│  Queue   │───▶│ Worker   │
│ Request  │    │(持久化)  │    │(AI Call) │
└──────────┘    └──────────┘    └──────────┘
                                       │
                                       ▼
                              ┌────────────────┐
                              │ WebSocket Push │
                              │ (Stream Resp)  │
                              └────────────────┘
```

**特性**:
- 支持多机器人类型独立队列
- 任务优先级与重新排序
- 持久化保证不丢失

### 7. 流程图模块 (Flowchart)

**职责**: 可视化 AI 工作流编排

**核心概念**:
- **模板 (Template)**: 预定义的工作流蓝图
- **节点 (Node)**: 工作流中的步骤
- **边 (Edge)**: 节点间的连接
- **实例 (Instance)**: 运行的流程图

**节点类型**:
| 类型 | 说明 |
|------|------|
| `start` | 开始节点 |
| `ai-chat` | AI 对话节点 |
| `condition` | 条件判断节点 |
| `action` | 执行动作节点 |
| `end` | 结束节点 |

## 数据流

### 发送消息流程

```
1. 用户输入消息
   │
2. 前端通过 WebSocket 发送
   │
3. ChatWebSocketHandler 接收
   │
4. 保存消息到 MongoDB
   │
5. 广播给房间所有成员
   │
6. 检测是否包含 @机器人
   │
7. 如果是，创建 AI 任务入队
   │
8. AI Worker 处理任务
   │
9. 流式响应通过 WebSocket 推送
   │
10. 前端展示流式内容
```

### AI 响应流程 (流式)

```
┌─────────┐     ┌──────────┐     ┌────────────┐     ┌─────────┐
│  OpenClaw│────▶│  SSE     │────▶│  Parse     │────▶│ WebSocket│
│  Gateway │     │  Stream  │     │  & Forward │     │ Broadcast│
└─────────┘     └──────────┘     └────────────┘     └─────────┘
                                                            │
                              ┌─────────────────────────────┼─────┐
                              ▼                             ▼     ▼
                        ┌──────────┐                 ┌────────┐ ┌────────┐
                        │ stream_start              │ Message│ │ Update │
                        │ (创建占位消息)              │ List   │ │ Content│
                        └──────────┘                 └────────┘ └────────┘
                              │
                        ┌──────────┐
                        │ stream_delta              
                        │ (增量更新)                 
                        └──────────┘
                              │
                        ┌──────────┐
                        │ stream_end                
                        │ (保存最终消息)              
                        └──────────┘
```

## 数据库设计

### MongoDB Collections

#### users
```javascript
{
  _id: ObjectId,
  username: String,
  password: String,  // 加密存储
  nickname: String,
  avatar: String,
  roles: ["ROLE_USER" | "ROLE_ADMIN"],
  bot: Boolean,
  botType: String,  // "openclaw" | "kimi" | "claude-code"
  botConfig: {
    apiKey: String,
    gatewayUrl: String,
    systemPrompt: String
  },
  createdAt: Date,
  updatedAt: Date
}
```

#### chat_rooms
```javascript
{
  _id: String,
  name: String,
  description: String,
  creatorId: String,
  memberIds: [String],
  messages: [
    {
      id: String,
      senderId: String,
      senderName: String,
      senderAvatar: String,
      content: String,
      timestamp: Date,
      isSystem: Boolean,
      isStreaming: Boolean,
      isToolCall: Boolean,
      fromOpenClaw: Boolean,
      toolCalls: [
        {
          id: String,
          name: String,
          description: String,
          status: String,
          timestamp: Date
        }
      ],
      attachments: [
        {
          name: String,
          url: String,
          type: String,
          contentType: String,
          size: Number
        }
      ]
    }
  ],
  openClawSessions: [
    {
      sessionId: String,
      instanceName: String,
      createdAt: Date,
      active: Boolean
    }
  ],
  createdAt: Date,
  updatedAt: Date
}
```

#### ooc_sessions
```javascript
{
  _id: String,
  chatRoomId: String,
  chatRoomName: String,
  messages: [
    {
      id: String,
      senderId: String,
      senderName: String,
      content: String,
      timestamp: Date,
      fromOpenClaw: Boolean
    }
  ],
  summary: String,  // 自动生成的摘要
  messageCount: Number,
  archived: Boolean,
  createdAt: Date,
  updatedAt: Date
}
```

#### mentions
```javascript
{
  _id: String,
  messageId: String,
  chatRoomId: String,
  chatRoomName: String,
  senderId: String,
  senderName: String,
  targetUserId: String,  // 被提及的用户
  content: String,
  read: Boolean,
  createdAt: Date
}
```

#### flowchart_templates
```javascript
{
  _id: String,
  name: String,
  description: String,
  category: String,
  icon: String,
  nodes: [...],  // 流程图节点定义
  edges: [...],  // 连接线定义
  createdBy: String,
  isPublic: Boolean,
  createdAt: Date,
  updatedAt: Date
}
```

## 扩展性设计

### 水平扩展 (未来)

**当前限制**: WebSocket 连接存储在单机内存中

**扩展方案**:
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │     │   Client    │     │   Client    │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │ Load Balancer
               ┌───────────┼───────────┐
               ▼           ▼           ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ Server 1 │ │ Server 2 │ │ Server 3 │
        │ (WebSocket)│ │ (WebSocket)│ │ (WebSocket)│
        └────┬─────┘ └────┬─────┘ └────┬─────┘
             │            │            │
             └────────────┼────────────┘
                          ▼
                    ┌─────────────┐
                    │    Redis    │
                    │  Pub/Sub    │  <── 消息广播
                    └─────────────┘
```

### 插件系统

新的 AI 提供商可以通过实现以下接口接入:

```java
public interface AiPluginService {
    String getBotUsername();
    String getBotAvatarUrl();
    boolean isBotEnabled();
    boolean isSessionAlive(String sessionId);
    
    Mono<? extends AiSession> createSession(String name, List<Map<String, Object>> context);
    Flux<StreamEvent> sendMessageStream(String sessionId, String message, ...);
    Mono<Void> closeSession(String sessionId);
}
```

## 性能优化

### 已实施
1. **消息分页**: 基于游标的分页，避免全量加载
2. **会话摘要**: 长会话自动压缩
3. **流式响应**: AI 响应实时流式传输
4. **连接复用**: WebClient 连接池

### 计划中
1. **Redis 缓存**: 热点数据缓存
2. **消息预加载**: 智能预加载历史消息
3. **WebSocket 集群**: Redis Pub/Sub 支持
4. **MongoDB 索引优化**: 查询性能提升

## 安全设计

### 传输安全
- HTTPS/WSS 强制启用（生产环境）
- 密码 RSA 加密传输
- JWT Token 认证

### 数据安全
- 敏感配置环境变量存储
- API Key 不暴露给前端
- 文件上传类型限制

### 访问控制
- 聊天室创建者拥有管理权限
- 成员只能访问所在聊天室
- 提及通知仅发送给目标用户

## 监控与日志

### 日志规范
```
[INFO]  [ClassName] 操作描述: 关键参数
[WARN]  [ClassName] 警告描述: 原因
[ERROR] [ClassName] 错误描述: 异常信息 + 堆栈
```

### 关键指标
- WebSocket 连接数
- 消息吞吐量
- AI 响应延迟
- 队列积压长度

## 部署架构

```
┌────────────────────────────────────────────────────┐
│                    Nginx                           │
│         (反向代理 + 静态文件 + WebSocket)            │
└──────────────────────┬─────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
┌──────────────┐ ┌──────────┐ ┌──────────────┐
│   Frontend   │ │ Backend  │ │   MongoDB    │
│  (dist/)     │ │ (8081)   │ │   (27017)    │
│   ↳ SPA      │ │ ↳ API    │ │              │
│              │ │ ↳ WebSocket│ │              │
└──────────────┘ └──────────┘ └──────────────┘
```

## 技术债务

1. **ChatWebSocketHandler 过大** (2300+ 行)
   - 计划拆分为多个 Handler
   - 提取响应解析逻辑

2. **前端组件拆分不完整**
   - HomeView.vue 仍较复杂
   - 需要进一步组件化

3. **测试覆盖率不足**
   - 缺少集成测试
   - 需要补充 E2E 测试

## 未来规划

### 短期 (1-2 月)
- [ ] 完善测试覆盖
- [ ] 优化前端组件结构
- [ ] 添加监控告警

### 中期 (3-6 月)
- [ ] Redis 缓存层
- [ ] WebSocket 集群支持
- [ ] 移动端适配优化

### 长期 (6+ 月)
- [ ] RAG 知识库
- [ ] 多租户支持
- [ ] 企业级 SSO
