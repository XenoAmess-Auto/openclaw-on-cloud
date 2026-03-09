#!/bin/bash
# 串房间问题验证脚本
# 测试场景：模拟两个不同房间的并发消息

set -e

echo "=== 串房间问题验证测试 ==="
echo ""

BASE_URL="http://localhost:8081"
WS_URL="ws://localhost:8081/ws/chat"

# 获取 token
echo "[1/6] 登录获取 token..."
TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"ooc-test-1771067194","password":"CxVgvs7QQyRFNWUAGlKR/w=="}' | \
  grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "登录失败"
    exit 1
fi
echo "Token 获取成功"
echo ""

# 获取聊天室列表
echo "[2/6] 获取聊天室列表..."
ROOMS=$(curl -s "$BASE_URL/api/chat-rooms" \
  -H "Authorization: Bearer $TOKEN")

# 提取前两个房间ID
ROOM1_ID=$(echo "$ROOMS" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
ROOM2_ID=$(echo "$ROOMS" | grep -o '"id":"[^"]*"' | head -2 | tail -1 | cut -d'"' -f4)

if [ -z "$ROOM1_ID" ] || [ -z "$ROOM2_ID" ]; then
    echo "需要至少两个聊天室进行测试"
    exit 1
fi

echo "Room 1 ID: $ROOM1_ID"
echo "Room 2 ID: $ROOM2_ID"
echo ""

# 获取用户名
USERNAME=$(curl -s "$BASE_URL/api/user/profile" \
  -H "Authorization: Bearer $TOKEN" | \
  grep -o '"username":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Username: $USERNAME"
echo ""

# 创建测试消息文件
echo "[3/6] 准备测试..."

cat > /tmp/room1_test.json <<EOF
{"type":"join","roomId":"$ROOM1_ID","userName":"$USERNAME"}
EOF

cat > /tmp/room2_test.json <<EOF
{"type":"join","roomId":"$ROOM2_ID","userName":"$USERNAME"}
EOF

cat > /tmp/msg_room1.json <<EOF
{"type":"message","content":"@openclaw 测试消息来自Room1 [$(date +%s)]"}
EOF

cat > /tmp/msg_room2.json <<EOF
{"type":"message","content":"@openclaw 测试消息来自Room2 [$(date +%s)]"}
EOF

echo "准备完成"
echo ""

echo "[4/6] 启动 WebSocket 测试..."
echo ""
echo "注意：此测试需要手动在浏览器中验证"
echo ""
echo "请按以下步骤操作："
echo ""
echo "1. 打开浏览器 Tab 1，访问: http://localhost:3000"
echo "2. 登录并进入 Room 1 (ID: $ROOM1_ID)"
echo "3. 打开浏览器 Tab 2，访问: http://localhost:3000"
echo "4. 登录并进入 Room 2 (ID: $ROOM2_ID)"
echo ""
echo "5. 在 Tab 1 发送: @openclaw 我是谁"
echo "6. 在 Tab 2 发送: @openclaw 现在几点"
echo ""
echo "7. 观察两个房间的回复是否串房："
echo "   - Tab 1 应该只收到 '我是谁' 的回复"
echo "   - Tab 2 应该只收到 '现在几点' 的回复"
echo ""
echo "按 Ctrl+C 结束此脚本"
echo ""

# 保持脚本运行，方便查看日志
while true; do
    echo "$(date '+%H:%M:%S') - 服务运行中..."
    sleep 10
done
