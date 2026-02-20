# OOC 生产部署指南

OpenClaw on Cloud (OOC) 生产环境部署文档

## 环境要求

### 服务器配置 (最低)

| 组件 | 配置 | 说明 |
|------|------|------|
| CPU | 2核 | 推荐 4核及以上 |
| 内存 | 4GB | 推荐 8GB 及以上 |
| 磁盘 | 20GB SSD | 根据文件存储需求调整 |
| 网络 | 10Mbps | 推荐 100Mbps |

### 软件依赖

| 软件 | 版本 | 用途 |
|------|------|------|
| Java | 21+ | 后端运行环境 |
| Node.js | 20+ | 前端构建 |
| MongoDB | 6.0+ | 主数据库 |
| Nginx | 1.20+ | 反向代理 |
| Maven | 3.8+ | 后端构建 |
| pnpm | 8+ | 前端包管理 |

## 部署架构

```
Internet
    │
    ▼
┌─────────────────────────────────────────────────────┐
│                   Nginx (443)                        │
│  ┌───────────────────────────────────────────────┐  │
│  │  SSL/TLS 终止                                  │  │
│  │  WebSocket 代理                                │  │
│  │  静态文件服务 (/ → frontend/dist)              │  │
│  │  API 代理 (/api → localhost:8081)              │  │
│  │  WebSocket 代理 (/ws → localhost:8081)         │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
    │
    ├──────────────────┬──────────────────┐
    ▼                  ▼                  ▼
┌──────────┐    ┌──────────┐    ┌──────────────┐
│ Frontend │    │ Backend  │    │   MongoDB    │
│ (3000)   │    │ (8081)   │    │   (27017)    │
│ ↳ dist   │    │ ↳ Java   │    │              │
└──────────┘    └──────────┘    └──────────────┘
```

## 安装步骤

### 1. 安装 Java 21

```bash
# Ubuntu/Debian
wget https://download.java.net/openjdk/jdk21/ri/openjdk-21+35_linux-x64_bin.tar.gz
tar -xzf openjdk-21+35_linux-x64_bin.tar.gz
sudo mv jdk-21 /usr/lib/jvm/
sudo update-alternatives --install /usr/bin/java java /usr/lib/jvm/jdk-21/bin/java 1

# 验证
java -version
```

### 2. 安装 Node.js 20 和 pnpm

```bash
# 使用 NodeSource
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# 安装 pnpm
npm install -g pnpm

# 验证
node -v
pnpm -v
```

### 3. 安装 MongoDB 6.0

```bash
# 添加 MongoDB 仓库
wget -qO - https://www.mongodb.org/static/pgp/server-6.0.asc | sudo apt-key add -
echo "deb [ arch=amd64,arm64 ] https://repo.mongodb.org/apt/ubuntu $(lsb_release -cs)/mongodb-org/6.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-6.0.list
sudo apt-get update

# 安装
sudo apt-get install -y mongodb-org

# 启动
sudo systemctl start mongod
sudo systemctl enable mongod

# 验证
mongosh --eval "db.runCommand({ connectionStatus: 1 })"
```

### 4. 安装 Nginx

```bash
sudo apt-get update
sudo apt-get install -y nginx

# 验证
nginx -v
```

### 5. 克隆代码

```bash
# 选择部署目录
mkdir -p /opt/ooc
cd /opt/ooc

# 克隆代码
git clone https://github.com/XenoAmess-Auto/untar.git .

# 或使用本地代码
# cp -r /path/to/openclaw-on-cloud/* .
```

## 配置

### 1. MongoDB 配置 (生产环境)

```bash
# 启用认证
sudo mongosh
```

```javascript
// 创建管理员用户
use admin
db.createUser({
  user: "admin",
  pwd: "your-strong-password",
  roles: [ { role: "userAdminAnyDatabase", db: "admin" } ]
})

// 创建 ooc 数据库用户
use ooc
db.createUser({
  user: "ooc",
  pwd: "your-ooc-password",
  roles: [ { role: "readWrite", db: "ooc" } ]
})
```

```bash
# 编辑 MongoDB 配置文件启用认证
sudo vim /etc/mongod.conf
```

```yaml
security:
  authorization: enabled

# 绑定到所有接口（如果外部访问需要）
net:
  bindIp: 127.0.0.1  # 或 0.0.0.0（注意防火墙）
```

```bash
sudo systemctl restart mongod
```

### 2. 后端配置

创建生产环境配置文件：

```bash
cd /opt/ooc/backend
vim src/main/resources/application-prod.yml
```

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://ooc:your-ooc-password@localhost:27017/ooc?authSource=ooc

server:
  port: 8081
  compression:
    enabled: true
  
# OpenClaw 配置（如果不使用可省略）
openclaw:
  gateway-url: ${OPENCLAW_GATEWAY_URL:}
  api-key: ${OPENCLAW_API_KEY:}

# Kimi 配置（可选）
kimi:
  api-key: ${KIMI_API_KEY:}
  model: ${KIMI_MODEL:kimi-k2.5}

# Claude 配置（可选）
claude:
  api-key: ${CLAUDE_API_KEY:}
  model: ${CLAUDE_MODEL:claude-3-5-sonnet-20241022}

# 日志
logging:
  level:
    com.ooc: INFO
  file:
    name: /var/log/ooc/application.log
```

### 3. 前端配置

```bash
cd /opt/ooc/frontend
vim .env.production
```

```
VITE_API_BASE_URL=/api
VITE_WS_URL=/ws/chat
```

### 4. Nginx 配置

```bash
sudo vim /etc/nginx/sites-available/ooc
```

```nginx
# HTTP 重定向到 HTTPS
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$server_name$request_uri;
}

# HTTPS 服务
server {
    listen 443 ssl http2;
    server_name your-domain.com;

    # SSL 证书（使用 Let's Encrypt 或其他）
    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
    
    # SSL 优化
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    # 安全头
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    # Gzip 压缩
    gzip on;
    gzip_vary on;
    gzip_types text/plain text/css application/json application/javascript text/xml;

    # 前端静态文件
    location / {
        root /opt/ooc/frontend/dist;
        index index.html;
        try_files $uri $uri/ /index.html;
        
        # 缓存静态资源
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg)$ {
            expires 1y;
            add_header Cache-Control "public, immutable";
        }
    }

    # API 代理
    location /api/ {
        proxy_pass http://localhost:8081/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # 超时设置
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # WebSocket 代理
    location /ws/ {
        proxy_pass http://localhost:8081/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # WebSocket 长连接超时（AI 响应可能较慢）
        proxy_connect_timeout 7d;
        proxy_send_timeout 7d;
        proxy_read_timeout 7d;
    }

    # 文件上传大小限制
    client_max_body_size 50M;
}
```

启用配置：

```bash
sudo ln -s /etc/nginx/sites-available/ooc /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

## SSL 证书 (Let's Encrypt)

```bash
# 安装 Certbot
sudo apt-get install -y certbot python3-certbot-nginx

# 获取证书
sudo certbot --nginx -d your-domain.com

# 自动续期测试
sudo certbot renew --dry-run
```

## 构建与部署

### 1. 后端构建

```bash
cd /opt/ooc/backend

# 使用生产配置构建
mvn clean package -DskipTests -Dspring.profiles.active=prod

# 检查构建结果
ls -la target/ooc-backend-*.jar
```

### 2. 前端构建

```bash
cd /opt/ooc/frontend

# 安装依赖
pnpm install

# 生产构建
pnpm build

# 检查构建结果
ls -la dist/
```

### 3. 使用 Systemd 管理服务

创建后端服务：

```bash
sudo vim /etc/systemd/system/ooc-backend.service
```

```ini
[Unit]
Description=OOC Backend Service
After=network.target mongod.service

[Service]
Type=simple
User=ooc
Group=ooc
WorkingDirectory=/opt/ooc/backend
Environment="SPRING_PROFILES_ACTIVE=prod"
Environment="OPENCLAW_GATEWAY_URL=your-gateway-url"
Environment="OPENCLAW_API_KEY=your-api-key"
ExecStart=/usr/bin/java -jar -Xmx2g target/ooc-backend-0.1.1.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

# 日志
StandardOutput=append:/var/log/ooc/backend.log
StandardError=append:/var/log/ooc/backend-error.log

[Install]
WantedBy=multi-user.target
```

创建用户和日志目录：

```bash
# 创建用户
sudo useradd -r -s /bin/false ooc

# 创建日志目录
sudo mkdir -p /var/log/ooc
sudo chown -R ooc:ooc /var/log/ooc

# 设置权限
sudo chown -R ooc:ooc /opt/ooc
```

启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable ooc-backend
sudo systemctl start ooc-backend

# 查看状态
sudo systemctl status ooc-backend

# 查看日志
sudo journalctl -u ooc-backend -f
```

## 自动部署脚本

创建部署脚本：

```bash
cd /opt/ooc
vim deploy.sh
```

```bash
#!/bin/bash
set -e

echo "🚀 Starting OOC deployment..."

# 拉取最新代码
echo "📥 Pulling latest code..."
git pull origin main

# 构建后端
echo "🔨 Building backend..."
cd /opt/ooc/backend
mvn clean package -DskipTests -Dspring.profiles.active=prod

# 构建前端
echo "🔨 Building frontend..."
cd /opt/ooc/frontend
pnpm install
pnpm build

# 重启后端服务
echo "🔄 Restarting backend service..."
sudo systemctl restart ooc-backend

# 检查服务状态
echo "✅ Checking service status..."
sleep 5
if systemctl is-active --quiet ooc-backend; then
    echo "✅ Backend is running"
else
    echo "❌ Backend failed to start"
    sudo journalctl -u ooc-backend -n 20
    exit 1
fi

echo "🎉 Deployment completed!"
echo "📱 Access your application at: https://your-domain.com"
```

```bash
chmod +x deploy.sh
```

## 监控与维护

### 1. 日志轮转

```bash
sudo vim /etc/logrotate.d/ooc
```

```
/var/log/ooc/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    create 0644 ooc ooc
    sharedscripts
    postrotate
        /bin/kill -HUP $(cat /var/run/syslogd.pid 2> /dev/null) 2> /dev/null || true
    endscript
}
```

### 2. 健康检查脚本

```bash
cd /opt/ooc
vim health-check.sh
```

```bash
#!/bin/bash

# 检查后端
if ! curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1; then
    echo "$(date): Backend is down, restarting..."
    sudo systemctl restart ooc-backend
fi

# 检查 MongoDB
if ! mongosh --eval "db.runCommand({ ping: 1 })" > /dev/null 2>&1; then
    echo "$(date): MongoDB is down!"
    # 发送告警（配置邮件或钉钉通知）
fi

# 检查磁盘空间
DISK_USAGE=$(df / | tail -1 | awk '{print $5}' | sed 's/%//')
if [ "$DISK_USAGE" -gt 80 ]; then
    echo "$(date): Disk usage is ${DISK_USAGE}%!"
fi
```

```bash
chmod +x health-check.sh

# 添加到 cron (每5分钟检查)
echo "*/5 * * * * /opt/ooc/health-check.sh >> /var/log/ooc/health-check.log 2>&1" | sudo crontab -
```

### 3. 备份脚本

```bash
cd /opt/ooc
vim backup.sh
```

```bash
#!/bin/bash

BACKUP_DIR="/backup/ooc"
DATE=$(date +%Y%m%d_%H%M%S)

mkdir -p "$BACKUP_DIR"

# 备份 MongoDB
mongodump --uri="mongodb://ooc:your-ooc-password@localhost:27017/ooc?authSource=ooc" \
    --out="$BACKUP_DIR/mongo_$DATE"

# 压缩
tar -czf "$BACKUP_DIR/backup_$DATE.tar.gz" -C "$BACKUP_DIR" "mongo_$DATE"
rm -rf "$BACKUP_DIR/mongo_$DATE"

# 保留最近 7 天的备份
find "$BACKUP_DIR" -name "backup_*.tar.gz" -mtime +7 -delete

echo "Backup completed: $BACKUP_DIR/backup_$DATE.tar.gz"
```

```bash
chmod +x backup.sh

# 每日凌晨备份
echo "0 2 * * * /opt/ooc/backup.sh >> /var/log/ooc/backup.log 2>&1" | sudo crontab -
```

## 故障排查

### 后端无法启动

```bash
# 查看日志
sudo journalctl -u ooc-backend -n 100

# 检查端口占用
sudo lsof -i :8081

# 检查 MongoDB 连接
mongosh "mongodb://ooc:password@localhost:27017/ooc?authSource=ooc" --eval "db.runCommand({connectionStatus:1})"
```

### WebSocket 连接失败

```bash
# 检查 Nginx 配置
sudo nginx -t

# 检查 WebSocket 代理
curl -i -N \
    -H "Connection: Upgrade" \
    -H "Upgrade: websocket" \
    -H "Host: localhost" \
    http://localhost:8081/ws/chat
```

### 前端 404 错误

```bash
# 检查 dist 目录是否存在
ls -la /opt/ooc/frontend/dist/

# 检查 Nginx 配置中的 root 路径
```

## 安全加固

### 1. 防火墙配置

```bash
# 只允许必要端口
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 80/tcp    # HTTP
sudo ufw allow 443/tcp   # HTTPS
sudo ufw enable
```

### 2. MongoDB 安全

```bash
# 绑定到本地（禁止外部访问）
sudo vim /etc/mongod.conf
# net:
#   bindIp: 127.0.0.1

# 禁用 REST 接口
# setParameter:
#   enableLocalhostAuthBypass: false
```

### 3. 文件权限

```bash
# 限制敏感文件访问
chmod 600 /opt/ooc/backend/src/main/resources/application-prod.yml
chmod 700 /opt/ooc/backup.sh
```

## 性能调优

### JVM 参数

编辑服务文件：

```ini
ExecStart=/usr/bin/java \
    -Xms1g -Xmx2g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -jar target/ooc-backend-0.1.1.jar
```

### MongoDB 优化

```yaml
# /etc/mongod.conf
storage:
  wiredTiger:
    engineConfig:
      cacheSizeGB: 1  # 根据内存调整

operationProfiling:
  slowOpThresholdMs: 100
  mode: slowOp
```

## 更新维护

### 平滑更新流程

```bash
# 1. 通知用户维护时间

# 2. 备份数据
/opt/ooc/backup.sh

# 3. 拉取新代码
cd /opt/ooc && git pull

# 4. 构建
/opt/ooc/deploy.sh

# 5. 验证
 curl -sf https://your-domain.com/api/health

# 6. 监控日志
sudo journalctl -u ooc-backend -f
```
