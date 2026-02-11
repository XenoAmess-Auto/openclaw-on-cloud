#!/bin/bash
set -e

cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud

# Kill existing processes
pkill -f "ooc-backend-1.0.0.jar" 2>/dev/null || true
pkill -f "vite" 2>/dev/null || true

sleep 2

# Start backend with Java 25
export JAVA_HOME=/tmp/jdk-25.0.2+10
export PATH="$JAVA_HOME/bin:$PATH"

echo "Starting backend on port 8081..."
nohup java -jar backend/build/libs/ooc-backend-1.0.0.jar > backend.log 2>&1 &
echo $! > backend.pid

sleep 3

# Check if backend started
if ps -p $(cat backend.pid) > /dev/null 2>&1; then
    echo "Backend started successfully (PID: $(cat backend.pid))"
else
    echo "Backend failed to start, check backend.log"
    exit 1
fi

# Start frontend
echo "Starting frontend on port 3000..."
cd frontend
nohup pnpm dev > ../frontend.log 2>&1 &
echo $! > ../frontend.pid
cd ..

sleep 2

# Check if frontend started
if ps -p $(cat frontend.pid) > /dev/null 2>&1; then
    echo "Frontend started successfully (PID: $(cat frontend.pid))"
else
    echo "Frontend may have failed, check frontend.log"
fi

echo ""
echo "Services deployed:"
echo "  Backend:  http://0.0.0.0:8081"
echo "  Frontend: http://0.0.0.0:3000"
echo ""
echo "Logs:"
echo "  Backend:  tail -f backend.log"
echo "  Frontend: tail -f frontend.log"
