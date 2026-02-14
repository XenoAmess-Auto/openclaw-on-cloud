#!/bin/bash
set -e

BACKEND_URL="http://localhost:8081"
TEST_USER="ooc-test-1771067194"
TEST_PASS="CxVgvs7QQyRFNWUAGlKR/w=="

echo "=== OOC E2E Test: @openclaw 你好 ==="
echo "Test started at: $(date -Iseconds)"
echo ""

# Login
LOGIN_RES=$(curl -s -X POST "${BACKEND_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${TEST_USER}\",\"password\":\"${TEST_PASS}\"}")
TOKEN=$(echo "$LOGIN_RES" | grep -o '"token":"[^"]*' | cut -d'"' -f4)
echo "✓ Login successful"

# Get room
ROOMS_RES=$(curl -s -H "Authorization: Bearer ${TOKEN}" "${BACKEND_URL}/api/chat-rooms")
ROOM_ID=$(echo "$ROOMS_RES" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)
echo "✓ Room ID: $ROOM_ID"

# Send test message
echo ""
echo "[1/3] Sending: @openclaw 你好"
MSG_RES=$(curl -s -X POST "${BACKEND_URL}/api/chat-rooms/${ROOM_ID}/messages" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "@openclaw 你好",
    "type": "TEXT",
    "mentions": ["openclaw"]
  }')
MSG_ID=$(echo "$MSG_RES" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)
echo "✓ Message sent, ID: $MSG_ID"

# Wait for response
echo ""
echo "[2/3] Waiting for OpenClaw response (8 seconds)..."
sleep 8

# Check messages
echo ""
echo "[3/3] Checking messages..."
MESSAGES_RES=$(curl -s -H "Authorization: Bearer ${TOKEN}" "${BACKEND_URL}/api/chat-rooms/${ROOM_ID}/messages")

# Find OpenClaw's response (the most recent one)
LATEST_OPENCLAW=$(echo "$MESSAGES_RES" | grep -o '{"id":"[^}]*"senderName":"OpenClaw"[^}]*"isStreaming":false[^}]*}' | tail -1)

echo ""
echo "=== Test Results ==="
echo ""

if [ -z "$LATEST_OPENCLAW" ]; then
    echo "❌ FAIL: No completed OpenClaw message found"
    echo "Raw messages (last 500 chars):"
    echo "${MESSAGES_RES: -500}"
    exit 1
fi

CONTENT=$(echo "$LATEST_OPENCLAW" | grep -o '"content":"[^"]*' | cut -d'"' -f4)
IS_STREAMING=$(echo "$LATEST_OPENCLAW" | grep -o '"isStreaming":[^,}]*' | cut -d':' -f2)

echo "OpenClaw Message Found:"
echo "  - Streaming Complete: $IS_STREAMING"
echo "  - Content Length: ${#CONTENT}"
echo "  - Content Preview:"
echo ""
echo "    ${CONTENT:0:200}"
if [ ${#CONTENT} -gt 200 ]; then
    echo "    ... (${#CONTENT} chars total)"
fi

echo ""

# Validation
PASS=true
if [ -z "$CONTENT" ] || [ "$CONTENT" = "*(OpenClaw 无回复)*" ]; then
    echo "❌ FAIL: Empty or placeholder response"
    PASS=false
elif [ ${#CONTENT} -lt 10 ]; then
    echo "⚠️  WARNING: Response very short (${#CONTENT} chars)"
fi

if [ "$IS_STREAMING" != "false" ]; then
    echo "⚠️  WARNING: Message still in streaming state"
fi

if [ "$PASS" = true ]; then
    echo ""
    echo "✅ TEST PASSED: OpenClaw responded successfully"
    echo "   Response length: ${#CONTENT} characters"
    exit 0
else
    echo ""
    echo "❌ TEST FAILED"
    exit 1
fi
