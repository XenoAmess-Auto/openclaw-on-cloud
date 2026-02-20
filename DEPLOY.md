# 部署文档

## 常见问题

### 1. 端口占用导致部署失败

**现象**：
```
APPLICATION FAILED TO START
Description:
Web server failed to start. Port 8081 was already in use.
```

**原因**：
- 旧版本后端进程未正确退出，仍占用 8081 端口
- 新部署尝试启动时因端口冲突而失败
- 系统可能同时运行多个 Java 进程

**解决方案**：

**自动处理**（推荐）：
`auto-deploy.sh` 脚本已添加端口冲突检测：
1. 部署前检查 8081 端口占用情况
2. 自动 kill 占用端口的进程
3. 确认端口释放后再启动新服务

**手动处理**：
```bash
# 查看占用 8081 端口的进程
lsof -i :8081

# 强制释放端口
sudo fuser -k 8081/tcp

# 或 kill 指定 PID
kill -9 <PID>
```

**验证**：
```bash
# 确认端口已释放
lsof -i :8081

# 重启服务
./auto-deploy.sh
```

### 2. Screen 会话管理

**查看会话**：
```bash
screen -ls | grep ooc
```

**进入日志会话**：
```bash
screen -r ooc-backend   # 后端日志
screen -r ooc-frontend  # 前端日志
```

**退出日志会话**（不停止服务）：
```bash
Ctrl+A, D
```

**强制停止服务**：
```bash
screen -S ooc-backend -X quit
screen -S ooc-frontend -X quit
```

### 3. 服务健康检查

**后端**：
```bash
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8081/api/auth/login -X POST
```

**前端**：
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:3000
```

### 4. 快速重启

```bash
# 后端
screen -S ooc-backend -X quit 2>/dev/null; sleep 1; screen -dmS ooc-backend java -jar backend/target/ooc-backend-0.1.1.jar --server.port=8081

# 前端
cd frontend && screen -S ooc-frontend -X quit 2>/dev/null; sleep 1; screen -dmS ooc-frontend pnpm preview --port 3000
```
