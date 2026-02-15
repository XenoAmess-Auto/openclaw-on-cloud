#!/bin/bash
# Quick test for @openclaw 你好

BACKEND_URL="http://localhost:8081"
TEST_USER="ooc-test-1771067194"
TEST_PASS="CxVgvs7QQyRFNWUAGlKR/w=="

echo "=== OOC E2E Test: @openclaw 你好 ==="
echo ""

# 1. Login with plaintext fallback (RSA may fail)
echo "[1/4] 登录获取 token..."
LOGIN_RES=$(curl -s -X POST "${BACKEND_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${TEST_USER}\",\"password\":\"${TEST_PASS}\"}")
TOKEN=$(echo "$LOGIN_RES" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
echo "✓ Token obtained: ${TOKEN:0:30}..."

# 2. Get chat rooms
echo "[2/4] 获取聊天室列表..."
ROOMS_RES=$(curl -s -H "Authorization: Bearer ${TOKEN}" "${BACKEND_URL}/api/chat-rooms")
ROOM_ID=$(echo "$ROOMS_RES" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)
echo "✓ Room ID: $ROOM_ID"

# 3. Send @openclaw 你好
echo "[3/4] 发送消息: @openclaw 你好"
SEND_RES=$(curl -s -X POST "${BACKEND_URL}/api/chat-rooms/${ROOM_ID}/messages" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "@openclaw 你好",
    "type": "TEXT",
    "mentions": ["openclaw"]
  }')
echo "✓ Message sent: $SEND_RES"

# 4. Wait and check response
echo "[4/4] 等待 OpenClaw 响应 (5秒)..."
sleep 5

MESSAGES_RES=$(curl -s -H "Authorization: Bearer ${TOKEN}" "${BACKEND_URL}/api/chat-rooms/${ROOM_ID}/messages")

# Extract latest message info
LATEST_SENDER=$(echo "$MESSAGES_RES" | grep -o '"senderName":"[^"]*' | tail -1 | cut -d'"' -f4)
LATEST_CONTENT=$(echo "$MESSAGES_RES" | grep -o '"content":"[^"]*' | tail -1 | cut -d'"' -f4)

echo ""
echo "=== 测试结果 ==="
echo "最新消息发送者: ${LATEST_SENDER:-N/A}"
echo "消息内容长度: ${#LATEST_CONTENT}"
echo ""
echo "消息内容预览:"
echo "${LATEST_CONTENT:-'(空内容)'}" | head -5
echo ""

if [ "$LATEST_SENDER" = "OpenClaw" ] && [ ${#LATEST_CONTENT} -gt 10 ]; then
    echo "✅ 测试通过: OpenClaw 返回了完整回复"
elif [ "$LATEST_SENDER" = "OpenClaw" ]; then
    echo "⚠️  OpenClaw 回复了但内容较短，可能不完整"
else
    echo "⚠️  最新消息不是来自 OpenClaw，可能还在处理中"
fi
