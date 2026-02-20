#!/bin/bash

# Restart script for OOC Backend (using screen)

APP_NAME="ooc-backend"
JAR_FILE="target/ooc-backend-0.1.1.jar"

# Kill existing screen session
echo "Stopping $APP_NAME..."
screen -S $APP_NAME -X quit 2>/dev/null || true

# Also kill any remaining java processes for this jar
pkill -9 -f "$JAR_FILE" 2>/dev/null || true
sleep 2

# Check if process is still running
if pgrep -f "$JAR_FILE" > /dev/null; then
    echo "Failed to stop $APP_NAME"
    exit 1
fi

echo "$APP_NAME stopped"

# Start new screen session
echo "Starting $APP_NAME in screen..."
screen -dmS $APP_NAME java -jar "$JAR_FILE" --server.port=8081

# Wait for startup
sleep 5

# Check if process is running
if pgrep -f "$JAR_FILE" > /dev/null; then
    echo "$APP_NAME started successfully (PID: $(pgrep -f "$JAR_FILE"))"
    echo "View logs: screen -r $APP_NAME"
else
    echo "Failed to start $APP_NAME"
    exit 1
fi
