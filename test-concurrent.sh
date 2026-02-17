#!/bin/bash

# OOC 并发消息测试脚本
# 测试同时发送多条 @openclaw 消息，验证队列是否正常处理

set -e

BASE_URL="http://localhost:8081"
WS_URL="ws://localhost:8081/ws/chat"
USERNAME="ooc-test-1771067194"
PASSWORD="CxVgvs7QQyRFNWUAGlKR/w=="
ROOM_ID=""

echo "=========================================="
echo "OOC 并发消息测试 - 队列丢弃问题检测"
echo "=========================================="

# 1. 登录获取 token
echo ""
echo "[1/5] 登录获取 token..."
LOGIN_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")

TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "❌ 登录失败: $LOGIN_RESPONSE"
    exit 1
fi
echo "✅ 登录成功，获取到 token"

# 2. 获取聊天室列表
echo ""
echo "[2/5] 获取聊天室列表..."
ROOMS_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/chat-rooms" \
  -H "Authorization: Bearer ${TOKEN}")

ROOM_ID=$(echo "$ROOMS_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$ROOM_ID" ]; then
    echo "❌ 未找到聊天室，请先创建"
    exit 1
fi
echo "✅ 找到聊天室: $ROOM_ID"

# 3. 获取历史消息数量（基准）
echo ""
echo "[3/5] 获取当前消息数量..."
MESSAGES_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/chat-rooms/${ROOM_ID}/messages" \
  -H "Authorization: Bearer ${TOKEN}")

INITIAL_COUNT=$(echo "$MESSAGES_RESPONSE" | grep -o '"id":"' | wc -l)
echo "当前消息数量: $INITIAL_COUNT"

# 4. 连接 WebSocket 并发送 5 条并发消息
echo ""
echo "[4/5] 连接 WebSocket 并发送 5 条并发 @openclaw 消息..."

# 创建 WebSocket 连接并发送消息的 expect 脚本
cat > /tmp/ws_test.exp << 'EOF'
#!/usr/bin/expect -f

set ws_url [lindex $argv 0]
set room_id [lindex $argv 1]
set username [lindex $argv 2]
set timeout 30

spawn websocat "$ws_url"

# 等待连接建立
sleep 1

# 发送 join 消息
send "{\"type\":\"join\",\"roomId\":\"$room_id\",\"userName\":\"$username\"}\r\n"

# 等待加入确认
sleep 1

# 发送 5 条 @openclaw 消息
send "{\"type\":\"message\",\"content\":\"@openclaw 你是谁？（测试消息 1/5）\"}\r\n"
sleep 0.1
send "{\"type\":\"message\",\"content\":\"@openclaw 你是谁？（测试消息 2/5）\"}\r\n"
sleep 0.1
send "{\"type\":\"message\",\"content\":\"@openclaw 你是谁？（测试消息 3/5）\"}\r\n"
sleep 0.1
send "{\"type\":\"message\",\"content\":\"@openclaw 你是谁？（测试消息 4/5）\"}\r\n"
sleep 0.1
send "{\"type\":\"message\",\"content\":\"@openclaw 你是谁？（测试消息 5/5）\"}\r\n"

# 等待响应（30秒应该足够）
sleep 30

send "{\"type\":\"leave\"}\r\n"
sleep 1

close
EOF

chmod +x /tmp/ws_test.exp

# 检查是否有 websocat
if ! command -v websocat &> /dev/null; then
    echo "正在安装 websocat..."
    curl -L https://github.com/vi/websocat/releases/download/v1.11.0/websocat.x86_64-unknown-linux-musl -o /tmp/websocat
    chmod +x /tmp/websocat
    export PATH="/tmp:$PATH"
fi

echo "发送 5 条并发消息到 WebSocket..."
/tmp/ws_test.exp "$WS_URL" "$ROOM_ID" "$USERNAME" 2>&1 | tee /tmp/ws_output.log || true

# 5. 等待并检查消息数量
echo ""
echo "[5/5] 等待 10 秒后检查消息数量..."
sleep 10

MESSAGES_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/chat-rooms/${ROOM_ID}/messages" \
  -H "Authorization: Bearer ${TOKEN}")

FINAL_COUNT=$(echo "$MESSAGES_RESPONSE" | grep -o '"id":"' | wc -l)
echo "最终消息数量: $FINAL_COUNT"

# 计算新增的消息数量
NEW_MESSAGES=$((FINAL_COUNT - INITIAL_COUNT))
echo ""
echo "=========================================="
echo "测试结果"
echo "=========================================="
echo "初始消息数: $INITIAL_COUNT"
echo "最终消息数: $FINAL_COUNT"
echo "新增消息数: $NEW_MESSAGES"
echo ""

# 分析结果
# 预期：5 条用户消息 + 5 条 OpenClaw 回复 = 10 条新增消息
# 如果少于 10 条，说明有消息被丢弃

if [ "$NEW_MESSAGES" -ge 10 ]; then
    echo "✅ 通过：所有消息都被正确处理"
elif [ "$NEW_MESSAGES" -ge 8 ]; then
    echo "⚠️ 部分通过：可能丢失了部分消息或回复"
else
    echo "❌ 失败：大量消息被丢弃，队列处理有问题"
fi

# 统计 fromOpenClaw=true 的消息数量
echo ""
echo "OpenClaw 回复统计:"
echo "$MESSAGES_RESPONSE" | grep -o '"fromOpenClaw":true' | wc -l | xargs echo "fromOpenClaw=true 的消息数:"

echo ""
echo "测试完成"
