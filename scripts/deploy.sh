#!/bin/bash
set -e

cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud

# Kill existing processes
pkill -f "ooc-backend" 2>/dev/null || true
pkill -f "vite" 2>/dev/null || true

sleep 1

# Start backend
echo "Starting backend on port 8081..."
nohup java -jar backend/build/libs/ooc-backend-1.0.0.jar > backend.log 2>&1 &
echo $! > backend.pid

sleep 5

# Start frontend
echo "Starting frontend on port 3000..."
cd frontend
nohup pnpm dev > ../frontend.log 2>&1 &
echo $! > ../frontend.pid
cd ..

echo "Services started!"
echo "Backend: http://0.0.0.0:8081"
echo "Frontend: http://0.0.0.0:3000"
