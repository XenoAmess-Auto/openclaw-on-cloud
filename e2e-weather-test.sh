#!/bin/bash

# OOC E2E Test: Weather Query
# Test case: @openclaw 济宁邹城的温度是？

set -e

BACKEND_URL="http://localhost:8081"
USERNAME="ooc-test-1771067194"
PASSWORD="CxVgvs7QQyRFNWUAGlKR/w=="

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo "OOC E2E Test: Weather Query"
echo "Test: @openclaw 济宁邹城的温度是？"
echo "=========================================="

# 1. Login
echo -e "\n${YELLOW}[1/4] Logging in...${NC}"
LOGIN_RESPONSE=$(curl -s -X POST "${BACKEND_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")
TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo -e "${RED}❌ Login failed${NC}"
    echo "Response: $LOGIN_RESPONSE"
    exit 1
fi
echo -e "${GREEN}✅ Login successful${NC}"

# 2. Get chat rooms
echo -e "\n${YELLOW}[2/4] Getting chat rooms...${NC}"
ROOMS_RESPONSE=$(curl -s -X GET "${BACKEND_URL}/api/chat-rooms" \
  -H "Authorization: Bearer ${TOKEN}")
ROOM_ID=$(echo "$ROOMS_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$ROOM_ID" ]; then
    echo -e "${RED}❌ No chat room found${NC}"
    echo "Response: $ROOMS_RESPONSE"
    exit 1
fi
echo -e "${GREEN}✅ Found chat room: ${ROOM_ID}${NC}"

# 3. Send message
echo -e "\n${YELLOW}[3/4] Sending message: @openclaw 济宁邹城的温度是？...${NC}"
MESSAGE_RESPONSE=$(curl -s -X POST "${BACKEND_URL}/api/chat-rooms/${ROOM_ID}/messages" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"content":"@openclaw 济宁邹城的温度是？"}')

echo "Message sent. Response: $MESSAGE_RESPONSE"

# 4. Wait and check for OpenClaw response
echo -e "\n${YELLOW}[4/4] Waiting for OpenClaw response (10s)...${NC}"
sleep 10

# Get messages
echo "Fetching messages..."
MESSAGES_RESPONSE=$(curl -s -X GET "${BACKEND_URL}/api/chat-rooms/${ROOM_ID}/messages" \
  -H "Authorization: Bearer ${TOKEN}")

# Check for OpenClaw response
if echo "$MESSAGES_RESPONSE" | grep -q "温度\|天气\|°C\|摄氏度"; then
    echo -e "${GREEN}✅ OpenClaw responded with weather information${NC}"
    echo ""
    echo "Last few messages:"
    echo "$MESSAGES_RESPONSE" | grep -o '"content":"[^"]*"' | tail -5
    exit 0
else
    echo -e "${RED}❌ No weather information found in response${NC}"
    echo "Messages response:"
    echo "$MESSAGES_RESPONSE" | head -c 2000
    exit 1
fi
