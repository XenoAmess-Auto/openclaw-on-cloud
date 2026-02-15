#!/bin/bash
# E2E Weather Test - @openclaw 济宁邹城的温度是？

set -e

API_BASE="http://localhost:8081/api"
USERNAME="ooc-test-1771067194"
PASSWORD="CxVgvs7QQyRFNWUAGlKR/w=="

echo "=== OOC E2E Test: @openclaw 济宁邹城的温度是？ ==="
echo ""

# 1. Login
echo "[1/5] 登录获取令牌..."
LOGIN_RESPONSE=$(curl -s -X POST "$API_BASE/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
USER_ID=$(echo "$LOGIN_RESPONSE" | grep -o '"userId":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "❌ 登录失败"
    exit 1
fi
echo "✅ 登录成功 (User: $USER_ID)"
echo ""

# 2. Get chat room
echo "[2/5] 获取聊天室..."
ROOMS_RESPONSE=$(curl -s -X GET "$API_BASE/chat-rooms" -H "Authorization: Bearer $TOKEN")
ROOM_ID=$(echo "$ROOMS_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$ROOM_ID" ]; then
    echo "❌ 未找到聊天室"
    exit 1
fi
echo "✅ 聊天室: $ROOM_ID"
echo ""

# 3. Send test message
echo "[3/5] 发送消息: @openclaw 济宁邹城的温度是？"
SEND_RESPONSE=$(curl -s -X POST "$API_BASE/chat-rooms/$ROOM_ID/messages" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"content":"@openclaw 济宁邹城的温度是？"}')
MSG_ID=$(echo "$SEND_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$MSG_ID" ]; then
    echo "❌ 消息发送失败"
    echo "响应: $SEND_RESPONSE"
    exit 1
fi
echo "✅ 消息已发送 (ID: $MSG_ID)"
echo ""

# 4. Wait for OpenClaw response
echo "[4/5] 等待 OpenClaw 响应 (约 15-20 秒)..."
for i in {1..20}; do
    sleep 1
    echo -n "."
done
echo ""
echo ""

# 5. Check messages
echo "[5/5] 检查最新消息..."
MESSAGES_RESPONSE=$(curl -s -X GET "$API_BASE/chat-rooms/$ROOM_ID/messages?page=0&size=5" \
    -H "Authorization: Bearer $TOKEN")

# Check for OpenClaw response
if echo "$MESSAGES_RESPONSE" | grep -q "fromOpenClaw.*true"; then
    echo "✅ 检测到 OpenClaw 回复"
    
    # Extract content (simplified parsing)
    OPENCLAW_MSG=$(echo "$MESSAGES_RESPONSE" | grep -o '"content":"[^"]*"' | head -1)
    CONTENT=$(echo "$OPENCLAW_MSG" | sed 's/"content":"//;s/"$//')
    
    if [ -n "$CONTENT" ] && [ "$CONTENT" != "*(OpenClaw 无回复)*" ]; then
        CONTENT_LEN=${#CONTENT}
        echo "✅ 回复内容非空 (长度: $CONTENT_LEN 字符)"
        
        # Check for temperature info
        if echo "$CONTENT" | grep -qi "温度\|°c\|weather\|wttr\|℃"; then
            echo "✅ 回复包含温度信息"
        else
            echo "⚠️ 回复可能不包含温度信息"
        fi
        
        # Check for tool call info
        if echo "$CONTENT" | grep -q "Tools used\|curl\|exec"; then
            echo "✅ 回复包含工具调用信息"
        else
            echo "⚠️ 回复可能不包含工具调用详情"
        fi
        
        echo ""
        echo "=== OpenClaw 回复内容预览 ==="
        echo "$CONTENT" | head -c 500
        echo ""
        echo "..."
        echo ""
        echo "✅ E2E 测试通过!"
    else
        echo "❌ 收到空回复或无回复占位符"
        exit 1
    fi
else
    echo "❌ 未检测到 OpenClaw 回复"
    echo "消息列表: $MESSAGES_RESPONSE"
    exit 1
fi
