#!/bin/bash
BACKEND_URL="http://localhost:8081"
TEST_USER="ooc-test-1771067194"
TEST_PASS="CxVgvs7QQyRFNWUAGlKR/w=="

echo "=== OOC E2E Test: @openclaw 你是谁？ ==="
echo "Test started at: $(date -Iseconds)"
echo ""

# Login
LOGIN_RES=$(curl -s -X POST "${BACKEND_URL}/api/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"${TEST_USER}\",\"password\":\"${TEST_PASS}\"}")
TOKEN=$(echo "$LOGIN_RES" | grep -o '"token":"[^"]*' | cut -d'"' -f4)
echo "✓ Login successful"

# Get room
ROOMS_RES=$(curl -s -H "Authorization: Bearer ${TOKEN}" "${BACKEND_URL}/api/chat-rooms")
ROOM_ID=$(echo "$ROOMS_RES" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)
echo "✓ Room ID: $ROOM_ID"

# Send test message
echo ""
echo "[1/3] Sending: @openclaw 你是谁？"
MSG_RES=$(curl -s -X POST "${BACKEND_URL}/api/chat-rooms/${ROOM_ID}/messages" -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" -d '{"content":"@openclaw 你是谁？","type":"TEXT","mentions":["openclaw"]}')
echo "✓ Message sent"

# Wait for response
echo ""
echo "[2/3] Waiting 15 seconds for OpenClaw response..."
sleep 15

# Check messages
echo ""
echo "[3/3] Checking messages..."
MESSAGES_RES=$(curl -s -H "Authorization: Bearer ${TOKEN}" "${BACKEND_URL}/api/chat-rooms/${ROOM_ID}/messages")

echo "$MESSAGES_RES" > /tmp/messages.json

python3 << 'PYEOF'
import json
with open('/tmp/messages.json') as f:
    data = json.load(f)

msgs = [m for m in data if m.get("senderName") == "OpenClaw"]
print(f"Total OpenClaw messages: {len(msgs)}")
print("")

# Show completed messages first
completed = [m for m in msgs if not m.get("isStreaming")]
if completed:
    print(f"Completed messages: {len(completed)}")
    for m in completed[-2:]:
        content = m.get("content", "")
        print(f"  ✓ ID: {m['id'][:25]}... | Length: {len(content)}")
        print(f"    Content: {content[:150]}..." if len(content) > 150 else f"    Content: {content}")
    print("")

# Show streaming messages
streaming = [m for m in msgs if m.get("isStreaming")]
if streaming:
    print(f"Streaming messages: {len(streaming)}")
    for m in streaming[-3:]:
        content = m.get("content", "")
        print(f"  ⟳ ID: {m['id'][:25]}... | Length: {len(content)}")
PYEOF

echo ""
echo "=== Test Complete ==="