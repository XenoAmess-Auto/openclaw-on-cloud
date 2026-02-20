#!/bin/bash
# OOC 自动部署脚本
# 使用 screen 启动前后端服务

set -e

echo "=== OOC Auto Deploy ==="

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查 screen 是否安装
if ! command -v screen &> /dev/null; then
    echo -e "${RED}Error: screen is not installed${NC}"
    echo "Install with: sudo apt-get install screen"
    exit 1
fi

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 1. 提交未保存的更改
echo -e "${YELLOW}[1/5] Checking for uncommitted changes...${NC}"
if [[ -n $(git status --porcelain 2>/dev/null) ]]; then
    echo "Found uncommitted changes, committing..."
    git add -A
    git commit -m "auto: deploy update $(date '+%Y-%m-%d %H:%M:%S')" || true
    git push || echo "Warning: push failed"
else
    echo "No uncommitted changes"
fi

# 2. 构建后端
echo -e "${YELLOW}[2/5] Building backend...${NC}"
cd backend
mvn package -DskipTests -q
echo -e "${GREEN}Backend built successfully${NC}"
cd ..

# 3. 构建前端
echo -e "${YELLOW}[3/5] Building frontend...${NC}"
cd frontend
pnpm install --frozen-lockfile
pnpm build
echo -e "${GREEN}Frontend built successfully${NC}"
cd ..

# 4. 部署后端
echo -e "${YELLOW}[4/5] Deploying backend...${NC}"

# 检查端口 8081 是否被占用
PORT_8081_PID=$(lsof -ti :8081 2>/dev/null || echo "")
if [ -n "$PORT_8081_PID" ]; then
    echo "Warning: Port 8081 is already in use by PID(s): $PORT_8081_PID"
    echo "Killing existing processes..."
    kill -9 $PORT_8081_PID 2>/dev/null || true
    sleep 2
fi

# 停止旧的后端 screen 会话
screen -S ooc-backend -X quit 2>/dev/null || true
sleep 1

# 确认端口已释放
if lsof -Pi :8081 -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo -e "${RED}Error: Port 8081 is still in use after cleanup${NC}"
    echo "Manual intervention required: sudo fuser -k 8081/tcp"
    exit 1
fi

# 启动新的后端 screen 会话 (使用正确的 S3 端点)
screen -dmS ooc-backend bash -c 'export S3_ENDPOINT=http://23.94.174.102:32000; export S3_CDN_URL=http://23.94.174.102:32000; java -jar backend/target/ooc-backend-0.1.1.jar --server.port=8081'
echo -e "${GREEN}Backend deployed in screen session 'ooc-backend'${NC}"

# 5. 部署前端
echo -e "${YELLOW}[5/5] Deploying frontend...${NC}"
# 停止旧的前端 screen 会话
screen -S ooc-frontend -X quit 2>/dev/null || true
sleep 1
# 启动新的前端 screen 会话
cd frontend
screen -dmS ooc-frontend bash -c 'pnpm preview --port 3000'
cd ..
echo -e "${GREEN}Frontend deployed in screen session 'ooc-frontend'${NC}"

# 等待服务启动
sleep 3

# 健康检查
echo ""
echo -e "${YELLOW}Health Check:${NC}"

BACKEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/api/health 2>/dev/null || echo "unreachable")
FRONTEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:3000 2>/dev/null || echo "unreachable")

if [ "$BACKEND_STATUS" == "403" ] || [ "$BACKEND_STATUS" == "200" ]; then
    echo -e "Backend: ${GREEN}OK${NC} (HTTP $BACKEND_STATUS)"
else
    echo -e "Backend: ${RED}FAILED${NC} (HTTP $BACKEND_STATUS)"
fi

if [ "$FRONTEND_STATUS" == "200" ]; then
    echo -e "Frontend: ${GREEN}OK${NC} (HTTP $FRONTEND_STATUS)"
else
    echo -e "Frontend: ${RED}FAILED${NC} (HTTP $FRONTEND_STATUS)"
fi

# 显示 screen 会话
echo ""
echo -e "${YELLOW}Active screen sessions:${NC}"
screen -ls | grep ooc || echo "No ooc sessions found"

echo ""
echo -e "${GREEN}=== Deploy Complete ===${NC}"
echo "Frontend: http://localhost:3000"
echo "Backend:  http://localhost:8081"
echo ""
echo "Commands:"
echo "  screen -r ooc-backend   # 查看后端日志"
echo "  screen -r ooc-frontend  # 查看前端日志"
echo "  screen -ls              # 列出所有会话"
