#!/bin/bash

# Restart script for OOC Backend

APP_NAME="ooc-backend"
JAR_FILE="target/ooc-backend-0.1.0.jar"
LOG_FILE="backend.log"

# Kill existing process
echo "Stopping $APP_NAME..."
pkill -9 -f "$JAR_FILE" 2>/dev/null || true
pkill -9 -f "build/libs/ooc-backend-1.0.0.jar" 2>/dev/null || true
sleep 2

# Check if process is still running
if pgrep -f "$JAR_FILE" > /dev/null; then
    echo "Failed to stop $APP_NAME"
    exit 1
fi

echo "$APP_NAME stopped"

# Start new process
echo "Starting $APP_NAME..."
nohup java -jar "$JAR_FILE" > "$LOG_FILE" 2>&1 &

# Wait for startup
sleep 5

# Check if process is running
if pgrep -f "$JAR_FILE" > /dev/null; then
    echo "$APP_NAME started successfully (PID: $(pgrep -f "$JAR_FILE"))"
else
    echo "Failed to start $APP_NAME"
    exit 1
fi
