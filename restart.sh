#!/bin/bash
set -e

cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud

# Kill existing
pkill -9 -f "ooc-backend-1.0.0.jar" 2>/dev/null || true
pkill -9 -f vite 2>/dev/null || true
sleep 1

# Start backend
export JAVA_HOME=/tmp/jdk-25.0.2+10
export PATH="$JAVA_HOME/bin:$PATH"

echo "Starting backend on 0.0.0.0:8081..."
nohup java -jar backend/build/libs/ooc-backend-1.0.0.jar > backend.log 2>&1 &
echo $! > backend.pid
sleep 3

# Start frontend (binds to 0.0.0.0 via vite.config.ts)
echo "Starting frontend on 0.0.0.0:3000..."
cd frontend
nohup pnpm dev > ../frontend.log 2>&1 &
echo $! > ../frontend.pid

sleep 2
echo "Done"
