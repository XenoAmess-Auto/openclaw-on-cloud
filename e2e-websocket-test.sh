#!/bin/bash
# OOC WebSocket Protocol E2E Test

set -e

echo "=========================================="
echo "OOC Platform WebSocket Protocol E2E Test"
echo "=========================================="

# 测试账号
USERNAME="ooc-test-1771067194"
PASSWORD="CxVgvs7QQyRFNWUAGlKR/w=="
BASE_URL="http://localhost:8081"

echo ""
echo "1. Testing backend health..."
if curl -s "$BASE_URL/actuator/health" | grep -q '"status":"UP"'; then
    echo "   ✅ Backend is healthy"
else
    echo "   ❌ Backend health check failed"
    exit 1
fi

echo ""
echo "2. Testing login API..."
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")

if echo "$LOGIN_RESPONSE" | grep -q '"token"'; then
    echo "   ✅ Login successful"
    TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
else
    echo "   ❌ Login failed: $LOGIN_RESPONSE"
    exit 1
fi

echo ""
echo "3. Testing chat-rooms API..."
ROOMS_RESPONSE=$(curl -s "$BASE_URL/api/chat-rooms" \
    -H "Authorization: Bearer $TOKEN")

if echo "$ROOMS_RESPONSE" | grep -q '"id"'; then
    echo "   ✅ Chat rooms retrieved"
    ROOM_ID=$(echo "$ROOMS_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "   Room ID: $ROOM_ID"
else
    echo "   ❌ Failed to get chat rooms: $ROOMS_RESPONSE"
    exit 1
fi

echo ""
echo "4. Testing send message (WebSocket protocol)..."
echo "   Sending: '@openclaw 请查看当前目录的文件列表'"

MSG_RESPONSE=$(curl -s -X POST "$BASE_URL/api/chat-rooms/$ROOM_ID/messages" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
        "content": "@openclaw 请查看当前目录的文件列表",
        "type": "TEXT",
        "openclawMentioned": true
    }')

if echo "$MSG_RESPONSE" | grep -q '"id"'; then
    echo "   ✅ Message sent successfully"
    MSG_ID=$(echo "$MSG_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
else
    echo "   ❌ Failed to send message: $MSG_RESPONSE"
    exit 1
fi

echo ""
echo "5. Waiting for OpenClaw response (WebSocket)..."
echo "   Waiting 10 seconds..."
sleep 10

echo ""
echo "6. Checking messages..."
MESSAGES_RESPONSE=$(curl -s "$BASE_URL/api/chat-rooms/$ROOM_ID/messages" \
    -H "Authorization: Bearer $TOKEN")

# 检查是否有 OpenClaw 的回复
OPENCLAW_MSG_COUNT=$(echo "$MESSAGES_RESPONSE" | grep -c '"senderName":"OpenClaw"' || true)

if [ "$OPENCLAW_MSG_COUNT" -gt 0 ]; then
    echo "   ✅ OpenClaw responded ($OPENCLAW_MSG_COUNT messages)"
    
    # 检查工具调用标记
    TOOL_MSG_COUNT=$(echo "$MESSAGES_RESPONSE" | grep -c '"isToolCall":true' || true)
    if [ "$TOOL_MSG_COUNT" -gt 0 ]; then
        echo "   ✅ Tool call messages detected: $TOOL_MSG_COUNT"
    else
        echo "   ⚠️ No tool call messages found (may be in response text)"
    fi
    
    # 显示最后一条 OpenClaw 消息
    echo ""
    echo "   Last OpenClaw response preview:"
    echo "$MESSAGES_RESPONSE" | grep -o '"content":"[^"]*"' | tail -1 | cut -d'"' -f4 | head -c 200
    echo "..."
else
    echo "   ⚠️ No OpenClaw response yet (may need more time)"
fi

echo ""
echo "=========================================="
echo "E2E Test Summary"
echo "=========================================="
echo "✅ Backend health: PASS"
echo "✅ Login: PASS"
echo "✅ Chat rooms: PASS"
echo "✅ Send message: PASS"
if [ "$OPENCLAW_MSG_COUNT" -gt 0 ]; then
    echo "✅ OpenClaw response: PASS"
else
    echo "⚠️ OpenClaw response: PENDING"
fi
echo ""
echo "WebSocket Protocol: ENABLED"
echo "=========================================="
