#!/bin/bash
set -e

echo "=== OOC E2E Test: @openclaw 邹城的天气? ==="

# 登录
echo "1. 登录..."
TOKEN=$(curl -s -X POST "http://localhost:8081/api/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"ooc-test-1771067194","password":"CxVgvs7QQyRFNWUAGlKR/w=="}' | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null || true)

if [ -z "$TOKEN" ]; then
    echo "   ❌ 登录失败"
    exit 1
fi
echo "   ✅ Token: ${TOKEN:0:20}..."

# 获取聊天室
echo "2. 获取聊天室..."
ROOM_ID=$(curl -s "http://localhost:8081/api/chat-rooms" -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; data=json.load(sys.stdin); print(data[0].get('id','') if data else '')" 2>/dev/null || true)

if [ -z "$ROOM_ID" ]; then
    echo "   ❌ 获取聊天室失败"
    exit 1
fi
echo "   ✅ Room ID: $ROOM_ID"

# 发送消息
echo "3. 发送消息: @openclaw 邹城的天气?"
SEND_RESULT=$(curl -s -X POST "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"content":"@openclaw 邹城的天气?","type":"TEXT","openclawMentioned":true}')

MSG_ID=$(echo "$SEND_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || true)
if [ -z "$MSG_ID" ]; then
    echo "   ❌ 发送消息失败: $SEND_RESULT"
    exit 1
fi
echo "   ✅ 消息ID: $MSG_ID"

# 等待响应
echo "4. 等待 OpenClaw 响应 (25s)..."
sleep 25

# 检查响应
echo "5. 检查响应..."
curl -s "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" -H "Authorization: Bearer $TOKEN" | python3 << 'PYEOF'
import sys, json

try:
    data = json.load(sys.stdin)
    openclaw_msgs = [m for m in data if m.get("senderName") == "OpenClaw"]
    if openclaw_msgs:
        latest = openclaw_msgs[-1]
        content = latest.get("content", "")
        print(f"✅ 收到 OpenClaw 回复")
        print(f"   内容长度: {len(content)}")
        print(f"   内容预览: {content[:500]}...")
        print(f"   isToolCall: {latest.get('isToolCall')}")
        print(f"   toolCalls数量: {len(latest.get('toolCalls', []))}")
        for tc in latest.get("toolCalls", []):
            print(f"   - 工具: {tc.get('name')}, 状态: {tc.get('status')}")
        
        # 检查是否包含天气信息
        if "温度" in content or "°C" in content or "天气" in content:
            print("\n✅ 回复包含天气信息")
        else:
            print("\n⚠️ 回复可能不包含天气信息")
    else:
        print("❌ 未收到 OpenClaw 回复")
except Exception as e:
    print(f"❌ 解析错误: {e}")
    print(f"原始数据: {sys.stdin.read()[:500]}")
PYEOF

echo ""
echo "=== 测试完成 ==="
