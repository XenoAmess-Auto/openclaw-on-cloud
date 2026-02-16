# OOC (OpenClaw on Cloud) 部署文档

## 环境要求

- Java 21+
- Node.js 20+ (with pnpm)
- MongoDB
- OpenClaw Gateway (运行在其他机器或本地)

## 服务地址

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端 | http://localhost:3000 | 静态文件服务 |
| 后端 | http://localhost:8081 | Spring Boot 应用 |
| API | http://localhost:8081/api | REST API |
| WebSocket | ws://localhost:8081/ws/chat | 实时消息 |

---

## 后端部署

### 1. 构建

```bash
cd backend
mvn clean package -DskipTests
mkdir -p build/libs
cp target/ooc-backend-1.0.0.jar build/libs/
```

### 2. 启动

```bash
# 停止旧进程
pkill -9 -f "ooc-backend-1.0.0.jar" 2>/dev/null || true
sleep 2

# 启动
nohup java -jar build/libs/ooc-backend-1.0.0.jar --server.port=8081 > /tmp/ooc-backend.log 2>&1 &
echo "Started with PID: $!"
```

### 3. 验证

```bash
# 检查端口
ss -tlnp | grep 8081

# 健康检查 (返回 403 表示正常，token 无效)
curl -s http://localhost:8081/api/chat-rooms -H "Authorization: Bearer test" -w " %{http_code}\n"

# 查看日志
tail -20 /tmp/ooc-backend.log
```

---

## 前端部署

### 1. 构建

```bash
cd frontend
pnpm install
pnpm build
```

### 2. 启动

使用 `vite preview` (支持代理配置)：

```bash
# 停止旧进程
pkill -9 -f "vite preview" 2>/dev/null || true
sleep 2

# 启动 (使用 vite preview 读取 vite.config.ts 代理配置)
cd dist
nohup npx vite preview --port 3000 --host > /tmp/frontend.log 2>&1 &
echo "Frontend started"
```

### 3. 验证

```bash
# 检查端口
ss -tlnp | grep 3000

# 访问测试
curl -s http://localhost:3000 | head -5
```

---

## 一键部署脚本

```bash
#!/bin/bash
set -e

echo "=== OOC 部署脚本 ==="

# 1. 后端部署
echo "[1/4] 构建后端..."
cd backend
mvn clean package -DskipTests -q
mkdir -p build/libs
cp target/ooc-backend-1.0.0.jar build/libs/

echo "[2/4] 启动后端..."
pkill -9 -f "ooc-backend-1.0.0.jar" 2>/dev/null || true
sleep 2
nohup java -jar build/libs/ooc-backend-1.0.0.jar --server.port=8081 > /tmp/ooc-backend.log 2>&1 &
echo "Backend PID: $!"

# 等待后端启动
sleep 6
if ss -tlnp | grep -q 8081; then
    echo "✓ 后端启动成功 (port 8081)"
else
    echo "✗ 后端启动失败"
    exit 1
fi

# 2. 前端部署
echo "[3/4] 构建前端..."
cd ../frontend
pnpm build

echo "[4/4] 启动前端..."
pkill -9 -f "vite preview" 2>/dev/null || true
sleep 2
cd dist
nohup npx vite preview --port 3000 --host > /tmp/frontend.log 2>&1 &
echo "Frontend PID: $!"

# 等待前端启动
sleep 3
if ss -tlnp | grep -q 3000; then
    echo "✓ 前端启动成功 (port 3000)"
else
    echo "✗ 前端启动失败"
    exit 1
fi

echo ""
echo "=== 部署完成 ==="
echo "前端: http://localhost:3000"
echo "后端: http://localhost:8081"
echo ""
echo "查看日志:"
echo "  后端: tail -f /tmp/ooc-backend.log"
echo "  前端: tail -f /tmp/frontend.log"
```

---

## 常见问题

### 端口被占用

```bash
# 查看占用 8081 的进程
ss -tlnp | grep 8081

# 强制释放
fuser -k 8081/tcp 2>/dev/null || true
```

### 后端启动失败

```bash
# 查看日志
tail -50 /tmp/ooc-backend.log

# 常见原因:
# - 端口占用
# - MongoDB 连接失败
# - RSA 密钥文件缺失 (backend/rsa/)
```

### 前端 API 请求失败

- 生产环境前端直接访问 `localhost:8081`，确保后端已启动
- 检查浏览器开发者工具 Network 面板

---

## 文件位置

| 文件 | 路径 |
|------|------|
| 后端 JAR | `backend/build/libs/ooc-backend-1.0.0.jar` |
| 前端构建 | `frontend/dist/` |
| 后端日志 | `/tmp/ooc-backend.log` |
| 前端日志 | `/tmp/frontend.log` |

---

## Git 提交规范

每次修改后执行：

```bash
git add -A
git commit -m "type: description

- detail 1
- detail 2"
git push
```

**提交类型:**
- `feat`: 新功能
- `fix`: 修复
- `refactor`: 重构
- `docs`: 文档
- `chore`: 构建/工具
