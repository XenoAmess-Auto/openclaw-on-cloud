#!/bin/bash
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}  OOC Auto Deploy Script${NC}"
echo -e "${YELLOW}========================================${NC}"
echo ""

# Deploy Backend
echo -e "${YELLOW}>>> Deploying Backend...${NC}"
cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud/backend

echo "  [1/3] Compiling..."
if mvn clean compile -q -DskipTests; then
    echo -e "  ${GREEN}✓ Compilation successful${NC}"
else
    echo -e "  ${RED}✗ Compilation failed${NC}"
    exit 1
fi

echo "  [2/3] Packaging..."
if mvn package -q -DskipTests; then
    echo -e "  ${GREEN}✓ Packaging successful${NC}"
else
    echo -e "  ${RED}✗ Packaging failed${NC}"
    exit 1
fi

echo "  [3/3] Restarting service..."
if [ -f "../backend.pid" ]; then
    OLD_PID=$(cat ../backend.pid 2>/dev/null)
    if [ -n "$OLD_PID" ]; then
        kill $OLD_PID 2>/dev/null || true
        sleep 2
    fi
fi

nohup java -jar target/ooc-backend-0.1.1.jar > ../backend.log 2>&1 &
NEW_PID=$!
echo $NEW_PID > ../backend.pid

# Wait for backend to start
sleep 5
if curl -s http://localhost:8081/actuator/health > /dev/null; then
    echo -e "  ${GREEN}✓ Backend started (PID: $NEW_PID)${NC}"
else
    echo -e "  ${RED}✗ Backend failed to start${NC}"
    exit 1
fi

echo ""

# Deploy Frontend
echo -e "${YELLOW}>>> Deploying Frontend...${NC}"
cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud/frontend

echo "  [1/2] Building..."
if pnpm build 2>/dev/null | grep -q "built"; then
    echo -e "  ${GREEN}✓ Build successful${NC}"
else
    echo -e "  ${RED}✗ Build failed${NC}"
    exit 1
fi

echo "  [2/2] Restarting service..."
# Kill existing vite processes
pkill -9 -f "vite" 2>/dev/null || true
sleep 2

nohup pnpm dev --port 3000 > frontend.log 2>&1 &
sleep 3

if curl -s -o /dev/null -w "%{http_code}" http://localhost:3000 | grep -q "200"; then
    echo -e "  ${GREEN}✓ Frontend started on port 3000${NC}"
else
    echo -e "  ${RED}✗ Frontend failed to start${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Deployment Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  Backend: http://localhost:8081"
echo "  Frontend: http://localhost:3000"
echo ""
