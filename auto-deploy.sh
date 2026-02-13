#!/bin/bash
set -e

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PROJECT_DIR="/home/xenoamess/.openclaw/workspace/openclaw-on-cloud"
FRONTEND_DIR="$PROJECT_DIR/frontend"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  OOC Auto Deploy (Frontend Only)${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

cd "$PROJECT_DIR"

# 检查是否有未提交的更改
if [ -n "$(git status --porcelain)" ]; then
    echo -e "${YELLOW}>>> 检测到未提交的更改，自动提交...${NC}"
    
    # 添加所有更改
    git add -A
    
    # 获取提交信息（使用默认信息或时间戳）
    COMMIT_MSG="auto: update $(date '+%Y-%m-%d %H:%M:%S')"
    
    # 提交
    git commit -m "$COMMIT_MSG"
    echo -e "${GREEN}✓ 已提交: $COMMIT_MSG${NC}"
    
    # 推送到远程
    echo -e "${YELLOW}>>> 推送到远程仓库...${NC}"
    if git push; then
        echo -e "${GREEN}✓ 推送成功${NC}"
    else
        echo -e "${RED}✗ 推送失败${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}✓ 没有未提交的更改${NC}"
fi

echo ""

# 构建前端
echo -e "${YELLOW}>>> 构建前端...${NC}"
cd "$FRONTEND_DIR"

# 先检查依赖
if [ ! -d "node_modules" ]; then
    echo "  安装依赖..."
    pnpm install
fi

# 构建
echo "  执行构建..."
if pnpm build 2>&1 | grep -q "built"; then
    echo -e "${GREEN}✓ 构建成功${NC}"
else
    echo -e "${RED}✗ 构建失败${NC}"
    exit 1
fi

echo ""

# 重启前端服务
echo -e "${YELLOW}>>> 重启前端服务 (port 3000)...${NC}"

# 查找并杀死现有的前端进程
FRONTEND_PID=$(lsof -t -i:3000 2>/dev/null || true)
if [ -n "$FRONTEND_PID" ]; then
    echo "  停止现有服务 (PID: $FRONTEND_PID)..."
    kill "$FRONTEND_PID" 2>/dev/null || true
    sleep 2
fi

# 确保没有其他 vite 进程
pkill -9 -f "vite.*3000" 2>/dev/null || true
sleep 1

# 启动新的前端服务
echo "  启动前端服务..."
cd "$FRONTEND_DIR"
nohup pnpm dev --port 3000 > frontend.log 2>&1 &
NEW_PID=$!
echo $NEW_PID > "$PROJECT_DIR/frontend.pid"

# 等待服务启动
sleep 3

# 检查服务是否成功启动
if curl -s -o /dev/null -w "%{http_code}" http://localhost:3000 | grep -q "200\|401\|403"; then
    echo -e "${GREEN}✓ 前端服务已启动 (PID: $NEW_PID)${NC}"
    echo -e "${GREEN}  访问: http://localhost:3000${NC}"
else
    echo -e "${RED}✗ 前端服务启动失败${NC}"
    echo "  查看日志: tail -f $FRONTEND_DIR/frontend.log"
    exit 1
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  部署完成!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  前端: http://localhost:3000"
echo "  日志: tail -f $FRONTEND_DIR/frontend.log"
echo ""
