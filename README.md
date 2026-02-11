# OpenClaw on Cloud

## 项目结构

```
openclaw-on-cloud/
├── backend/          # Java 25 + Spring Boot + MongoDB
├── frontend/         # Vue 3 + TypeScript + pnpm
├── .github/          # CI/CD 配置
├── docker-compose.yml
└── README.md
```

## 快速开始

### 本地开发

**启动 MongoDB:**
```bash
docker run -d -p 27017:27017 --name ooc-mongo mongo:7
```

**启动后端:**
```bash
cd backend
./mvnw spring-boot:run
```

**启动前端:**
```bash
cd frontend
pnpm install
pnpm dev
```

### Docker 部署

```bash
docker compose up -d
```

## 核心功能

- **用户系统**: 注册/登录/权限管理
- **聊天室**: 创建/加入/离开
- **OpenClaw 集成**: 
  - 私聊自动触发
  - 群聊 @openclaw 触发
  - 会话自动保存/恢复/总结
- **移动端适配**: 响应式设计

## API 文档

| Endpoint | Method | Description |
|----------|--------|-------------|
| /api/auth/login | POST | 登录 |
| /api/auth/register | POST | 注册 |
| /api/chat-rooms | GET | 获取聊天室列表 |
| /api/chat-rooms | POST | 创建聊天室 |
| /ws/chat | WebSocket | 聊天连接 |

## 环境变量

复制 `.env.example` 为 `.env` 并配置：

- `JWT_SECRET`: JWT 密钥
- `MONGODB_URI`: MongoDB 连接字符串
- `OPENCLAW_URL`: OpenClaw 网关地址
- `OPENCLAW_API_KEY`: OpenClaw API 密钥

## License

Apache License 2.0
