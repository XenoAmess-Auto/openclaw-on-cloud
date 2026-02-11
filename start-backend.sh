#!/bin/bash
cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud
export JAVA_HOME=/tmp/jdk-25.0.2+10
export PATH="$JAVA_HOME/bin:$PATH"

# Kill existing
pkill -9 -f "ooc-backend-1.0.0.jar" 2>/dev/null || true
sleep 1

# Start backend
exec java -jar backend/build/libs/ooc-backend-1.0.0.jar > backend.log 2>&1
