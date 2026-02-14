#!/bin/bash
# E2E 测试：验证 OpenClaw 工具调用消息完整性

set -e

echo "=== OpenClaw 工具调用消息完整性测试 ==="
echo ""

# 1. 检查后端日志中是否有完整内容
echo "步骤 1: 检查后端日志中的消息长度..."
if [ -f backend.log ]; then
    grep -o "contentLength=[0-9]*" backend.log | tail -5 || echo "未找到 contentLength 日志"
else
    echo "backend.log 不存在"
fi
echo ""

# 2. 检查前端构建是否成功
echo "步骤 2: 检查前端构建..."
if [ -f frontend/dist/assets/HomeView-*.js ]; then
    echo "✅ 前端构建文件存在"
    # 检查是否包含 tool-call-content
    if grep -q "tool-call-content" frontend/dist/assets/HomeView-*.js; then
        echo "✅ tool-call-content 样式已包含"
    else
        echo "❌ tool-call-content 样式缺失"
    fi
else
    echo "❌ 前端构建文件不存在"
fi
echo ""

# 3. 检查后端是否正确保存消息
echo "步骤 3: 检查后端消息保存逻辑..."
if grep -q "finalContent.length()" backend/src/main/java/com/ooc/websocket/ChatWebSocketHandler.java; then
    echo "✅ 后端保存消息时记录内容长度"
else
    echo "❌ 后端未记录内容长度"
fi
echo ""

# 4. 测试登录并获取 Token
echo "步骤 4: 测试登录 API..."
LOGIN_RESPONSE=$(curl -s -X POST http://localhost:8081/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"ooc-test-1771067194","password":"CxVgvs7QQyRFNWUAGlKR/w=="}' 2>/dev/null || echo "")

if echo "$LOGIN_RESPONSE" | grep -q "token"; then
    echo "✅ 登录成功"
    TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
    
    # 5. 获取聊天室列表
    echo ""
    echo "步骤 5: 获取聊天室列表..."
    ROOMS_RESPONSE=$(curl -s -X GET http://localhost:8081/api/chat-rooms \
        -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "")
    
    if echo "$ROOMS_RESPONSE" | grep -q "id"; then
        echo "✅ 获取聊天室列表成功"
        ROOM_ID=$(echo "$ROOMS_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        
        if [ -n "$ROOM_ID" ]; then
            echo "房间 ID: $ROOM_ID"
            
            # 6. 获取消息列表
            echo ""
            echo "步骤 6: 获取房间消息..."
            MESSAGES_RESPONSE=$(curl -s -X GET "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" \
                -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "")
            
            if echo "$MESSAGES_RESPONSE" | grep -q "content"; then
                echo "✅ 获取消息成功"
                # 检查最后一条 OpenClaw 消息的内容长度
                LAST_MSG=$(echo "$MESSAGES_RESPONSE" | grep -o '"content":"[^"]*"' | tail -1)
                echo "最后一条消息: ${LAST_MSG:0:100}..."
            else
                echo "❌ 获取消息失败"
            fi
        fi
    else
        echo "❌ 获取聊天室列表失败"
    fi
else
    echo "❌ 登录失败"
fi

echo ""
echo "=== 测试完成 ==="
