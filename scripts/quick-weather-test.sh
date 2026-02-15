#!/bin/bash
# Quick Weather Test for OOC

TOKEN=$(curl -s -X POST "http://localhost:8081/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"ooc-test-1771067194","password":"CxVgvs7QQyRFNWUAGlKR/w=="}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

echo "Token: ${TOKEN:0:20}..."

# Get rooms
ROOMS=$(curl -s -X GET "http://localhost:8081/api/chat-rooms" -H "Authorization: Bearer $TOKEN")
ROOM_ID=$(echo "$ROOMS" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Room ID: $ROOM_ID"

# Send weather query
echo "Sending: @openclaw 济宁邹城的温度是？"
MSG_RESPONSE=$(curl -s -X POST "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"@openclaw 济宁邹城的温度是？"}')
echo "Message response: $MSG_RESPONSE"

# Wait for response
echo ""
echo "Waiting 15s for OpenClaw to respond..."
sleep 15

# Get messages
echo ""
echo "Fetching messages..."
MSGS=$(curl -s -X GET "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" \
  -H "Authorization: Bearer $TOKEN")

# Show last 3 messages
echo ""
echo "Last 3 messages:"
echo "$MSGS" | python3 -c "import sys,json; d=json.load(sys.stdin); [print(f\"[{m['senderName']}] {m['content'][:200]}\") for m in d[-3:]]" 2>/dev/null || echo "$MSGS" | tail -c 1000
