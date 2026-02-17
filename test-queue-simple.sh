#!/bin/bash

# 实际测试 OOC 任务队列
set -e

BASE_URL="http://localhost:8081"
USERNAME="ooc-test-1771067194"
PASSWORD="CxVgvs7QQyRFNWUAGlKR/w=="

echo "=== OOC 任务队列实际测试 ==="

# 1. 登录
LOGIN_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")

TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then
    echo "❌ 登录失败"
    exit 1
fi
echo "✅ 登录成功"

# 2. 获取聊天室
ROOMS_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/chat-rooms" \
  -H "Authorization: Bearer ${TOKEN}")
ROOM_ID=$(echo "$ROOMS_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$ROOM_ID" ]; then
    echo "❌ 未找到聊天室"
    exit 1
fi
echo "✅ 找到聊天室: $ROOM_ID"

# 3. 发送第一条 @openclaw 消息
echo ""
echo "[1/5] 发送第一条 @openclaw 消息..."
MSG1_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/chat-rooms/${ROOM_ID}/messages" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"content\":\"@openclaw 你是谁？（测试1）\",\"attachments\":[]}")
echo "第一条消息响应: $MSG1_RESPONSE"

# 4. 立即发送第二条
echo ""
echo "[2/5] 发送第二条 @openclaw 消息（5秒后）..."
sleep 5
MSG2_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/chat-rooms/${ROOM_ID}/messages" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"content\":\"@openclaw 你是谁？（测试2）\",\"attachments\":[]}")
echo "第二条消息响应: $MSG2_RESPONSE"

# 5. 检查消息数量
echo ""
echo "[3/5] 检查消息..."
sleep 2
MESSAGES_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/chat-rooms/${ROOM_ID}/messages" \
  -H "Authorization: Bearer ${TOKEN}")

# 统计 fromOpenClaw 消息数量
OPENCLAW_COUNT=$(echo "$MESSAGES_RESPONSE" | grep -o '"fromOpenClaw":true' | wc -l)
echo "OpenClaw 回复数量: $OPENCLAW_COUNT"

# 显示最近的消息
echo ""
echo "最近的消息:"
echo "$MESSAGES_RESPONSE" | grep -o '"senderName":"[^"]*"' | tail -10

echo ""
echo "=== 测试完成 ==="
echo "如果有多个 fromOpenClaw 消息，说明队列在工作"
