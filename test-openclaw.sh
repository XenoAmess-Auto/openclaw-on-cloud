#!/bin/bash

# OOC OpenClaw 测试脚本
# 测试 @openclaw 消息是否能正常收到回复

BASE_URL="http://localhost:8081"
USERNAME="ooc-test-1771067194"
PASSWORD="CxVgvs7QQyRFNWUAGlKR/w=="

echo "=== OOC OpenClaw 测试 ==="
echo ""

# 1. 登录获取 token
echo "[1/4] 登录获取 token..."
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" 2>/dev/null)

TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "❌ 登录失败"
    exit 1
fi
echo "✅ 登录成功"

# 2. 获取聊天室列表
echo ""
echo "[2/4] 获取聊天室列表..."
ROOMS_RESPONSE=$(curl -s -X GET "$BASE_URL/api/chat-rooms" \
  -H "Authorization: Bearer $TOKEN" 2>/dev/null)

ROOM_ID=$(echo "$ROOMS_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
ROOM_NAME=$(echo "$ROOMS_RESPONSE" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$ROOM_ID" ]; then
    echo "❌ 没有可用的聊天室"
    exit 1
fi
echo "✅ 找到聊天室: $ROOM_NAME (ID: $ROOM_ID)"

# 3. 发送 @openclaw 消息
echo ""
echo "[3/4] 发送 @openclaw 测试消息..."
TEST_MESSAGE="@openclaw 你好啊?"
SEND_RESPONSE=$(curl -s -X POST "$BASE_URL/api/chat-rooms/$ROOM_ID/messages" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"content\":\"$TEST_MESSAGE\"}" 2>/dev/null)

MSG_ID=$(echo "$SEND_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$MSG_ID" ]; then
    echo "❌ 发送消息失败"
    exit 1
fi
echo "✅ 消息已发送 (ID: $MSG_ID)"

# 4. 等待并检查 OpenClaw 响应
echo ""
echo "[4/4] 等待 OpenClaw 响应 (最多等待 30 秒)..."

for i in {1..30}; do
    sleep 1
    
    # 获取最新消息
    MESSAGES=$(curl -s -X GET "$BASE_URL/api/chat-rooms/$ROOM_ID/messages" \
      -H "Authorization: Bearer $TOKEN" 2>/dev/null)
    
    # 提取 OpenClaw 的最新回复内容
    # 查找 senderId 为 openclaw 的消息，提取 content 字段
    CONTENT=$(echo "$MESSAGES" | grep -o '"senderId":"openclaw"[^}]*"content":"[^"]*"' | tail -1 | sed 's/.*"content":"\([^"]*\)".*/\1/')
    
    if [ -n "$CONTENT" ]; then
        echo ""
        echo "=== 测试结果 ==="
        echo ""
        
        # 检查是否是 "无回复"
        if echo "$CONTENT" | grep -q "无回复"; then
            echo "❌ 测试失败: 显示 '(OpenClaw 无回复)'"
            exit 1
        fi
        
        echo "✅ 测试通过: OpenClaw 正常回复"
        echo "   回复长度: ${#CONTENT} 字符"
        echo "   回复内容: $CONTENT"
        exit 0
    fi
    
    echo -n "."
done

echo ""
echo "❌ 测试失败: 30 秒内未收到 OpenClaw 响应"
exit 1
