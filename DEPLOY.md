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

**必须使用 `vite preview`**（`serve` 不支持 API 代理）：

```bash
# 停止旧进程
pkill -9 -f "vite preview" 2>/dev/null || true
sleep 2

# 启动 (必须在 frontend 目录下运行，读取 vite.config.ts 代理配置)
cd /path/to/openclaw-on-cloud/frontend
nohup npx vite preview --port 3000 --host > /tmp/frontend.log 2>&1 &
echo "Frontend started"
```

**注意：** 如果在 `dist/` 目录下运行 `vite preview`，代理配置不会生效，导致 API 请求 404。

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
cd frontend
# 注意：vite preview 必须在 frontend 目录运行，才能读取 vite.config.ts 中的代理配置
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

### 前端 API 地址配置（关键）

**问题：** 从外部 IP 访问时登录失败，浏览器控制台显示连接 `localhost:8081` 失败。

**原因：** 
- 开发环境使用 `/api` 相对路径，通过 Vite 代理到后端
- 生产环境不能硬编码 `http://localhost:8081`，因为用户从外部访问时无法连接到你的 localhost

**解决方案：**
前端使用 `window.location.hostname` 动态检测当前 host：

```typescript
// src/api/client.ts
const hostname = window.location.hostname
const backendHost = hostname === 'localhost' ? 'localhost' : hostname
const baseURL = `http://${backendHost}:8081/api`
```

这样访问 `http://23.94.174.102:3000` 时，前端会自动连接 `http://23.94.174.102:8081/api`。

---

### 为什么不能使用 `serve` 部署前端

`serve` 是纯静态文件服务器，**不支持 API 代理**。前端请求 `/api` 会 404。

必须使用 `vite preview`，它会读取 `vite.config.ts` 中的代理配置：

```javascript
// vite.config.ts
server: {
  proxy: {
    '/api': { target: 'http://localhost:8081', changeOrigin: true },
    '/ws': { target: 'ws://localhost:8081', ws: true }
  }
}
```

---

### 浏览器缓存问题

修改后如果仍看到旧行为，可能是浏览器缓存了 JS 文件。

**强制刷新：**
- Windows/Linux: `Ctrl + F5` 或 `Ctrl + Shift + R`
- macOS: `Cmd + Shift + R`

**或清除缓存后刷新：**
Chrome DevTools → Network → Disable cache → 刷新页面

---

### 验证部署是否成功

```bash
# 1. 检查后端运行状态
curl -s http://localhost:8081/api/chat-rooms -H "Authorization: Bearer test" -w " %{http_code}\n"
# 期望输出: 403 (正常，token 无效)

# 2. 检查前端是否能代理 API 请求
curl -s http://localhost:3000/api/auth/public-key | head -5
# 期望输出: {"publicKey":"-----BEGIN PUBLIC KEY-----..."}

# 3. 检查 WebSocket 端口
ss -tlnp | grep -E "(3000|8081)"
# 期望看到两个端口都在监听
```

---

### 从外部 IP 访问的注意事项

如果用户通过 `http://<服务器IP>:3000` 访问：

1. **确保后端绑定到 0.0.0.0**（Spring Boot 默认）
2. **确保防火墙开放 3000 和 8081 端口**
3. **前端代码必须使用 `window.location.hostname`** 而不是 `localhost`

验证外部访问：
```bash
# 从其他机器测试
curl -s http://<服务器IP>:8081/api/auth/public-key
curl -s http://<服务器IP>:3000/api/auth/public-key
```

---

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
