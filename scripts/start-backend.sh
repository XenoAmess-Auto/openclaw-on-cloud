#!/bin/bash
cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud

# Kill existing
pkill -9 -f "ooc-backend-1.0.0.jar" 2>/dev/null || true
sleep 1

# Start backend
nohup java -jar backend/target/ooc-backend-1.0.0.jar > backend.log 2>&1 &
echo $! > backend.pid
echo "Backend started with PID $(cat backend.pid)"
