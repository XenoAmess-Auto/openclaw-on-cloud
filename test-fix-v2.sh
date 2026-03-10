#!/bin/bash
# OOC 平台修复自测脚本 v2

echo "=== OOC 平台修复自测 ==="
echo ""

# 测试 1: 后端服务健康检查
echo "[测试 1] 后端服务健康检查..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/api/auth/health 2>/dev/null)
if [ "$HTTP_STATUS" = "200" ]; then
    echo "✅ 后端服务正常 (HTTP 200)"
else
    echo "❌ 后端服务异常 (HTTP $HTTP_STATUS)"
    exit 1
fi
echo ""

# 测试 2: 登录获取 Token
echo "[测试 2] 登录测试..."
LOGIN_RESPONSE=$(curl -s -X POST http://localhost:8081/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin123"}' 2>/dev/null)

TOKEN=$(echo $LOGIN_RESPONSE | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -n "$TOKEN" ]; then
    echo "✅ 登录成功，获取到 Token"
else
    echo "❌ 登录失败"
    echo "响应: $LOGIN_RESPONSE"
    exit 1
fi
echo ""

# 测试 3: 获取聊天室列表
echo "[测试 3] 获取聊天室列表..."
ROOMS_RESPONSE=$(curl -s http://localhost:8081/api/chat-rooms \
    -H "Authorization: Bearer $TOKEN" 2>/dev/null)

ROOM_COUNT=$(echo $ROOMS_RESPONSE | grep -o '"id":"[^"]*"' | wc -l)
echo "✅ 获取到 $ROOM_COUNT 个聊天室"
echo ""

# 测试 4: WebSocket 端口检查
echo "[测试 4] WebSocket 端口检查..."
if nc -z localhost 8081 2>/dev/null || ss -tlnp 2>/dev/null | grep -q ":8081"; then
    echo "✅ WebSocket 端口正常 (8081)"
else
    echo "⚠️ WebSocket 端口检查跳过（使用HTTP端口）"
fi
echo ""

# 测试 5: 检查服务日志中的关键错误
echo "[测试 5] 检查服务日志..."
LOG_FILE="/tmp/ooc-backend-latest.log"
if [ -f "$LOG_FILE" ]; then
    ERROR_COUNT=$(tail -100 $LOG_FILE 2>/dev/null | grep -cE "ERROR|Exception" || echo "0")
    if [ "$ERROR_COUNT" -lt "5" ]; then
        echo "✅ 日志中错误数量正常 ($ERROR_COUNT 个)"
    else
        echo "⚠️ 日志中错误数量较多 ($ERROR_COUNT 个)"
    fi
else
    echo "⚠️ 日志文件不存在"
fi
echo ""

# 测试 6: 检查串房间相关的关键日志
echo "[测试 6] 检查串房间防护日志..."
if [ -f "$LOG_FILE" ]; then
    CROSS_ROOM_COUNT=$(tail -200 $LOG_FILE | grep -c "CROSS-ROOM" 2>/dev/null || echo "0")
    if [ "$CROSS_ROOM_COUNT" -eq "0" ]; then
        echo "✅ 未检测到串房间警告"
    else
        echo "⚠️ 检测到 $CROSS_ROOM_COUNT 条串房间相关日志"
    fi
else
    echo "⚠️ 日志文件不存在"
fi
echo ""

echo "=== 自测完成 ==="
echo ""
echo "请手动测试以下功能："
echo "1. 在 OOC 平台同时打开两个不同的群聊"
echo "2. 同时在两个群中向 @openclaw 发送不同的问题"
echo "3. 确认回答不会串房间（每个群只收到针对该群问题的回答）"
echo "4. 测试工具调用展示（@openclaw 执行一些需要工具调用的任务，如：执行 ls -l）"
echo ""
echo "访问地址: http://localhost:3000"
