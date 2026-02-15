#!/bin/bash
set -e

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "========================================"
echo "  OpenClaw on Cloud - Self Test Suite"
echo "========================================"
echo ""

# Track failures
FAILED=0

# Test Backend
echo -e "${YELLOW}>>> Testing Backend...${NC}"
cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud/backend

# Compile backend
echo "  [1/4] Compiling backend..."
if mvn clean compile -q -DskipTests; then
    echo -e "  ${GREEN}✓ Backend compilation successful${NC}"
else
    echo -e "  ${RED}✗ Backend compilation failed${NC}"
    FAILED=1
fi

# Run unit tests
echo "  [2/4] Running unit tests..."
if mvn test -q -Dtest=UserServiceTest,ChatRoomServiceTest,OocSessionServiceTest 2>&1; then
    echo -e "  ${GREEN}✓ Unit tests passed${NC}"
else
    echo -e "  ${RED}✗ Some unit tests failed${NC}"
    FAILED=1
fi

# Package backend
echo "  [3/4] Packaging backend..."
if mvn package -q -DskipTests; then
    echo -e "  ${GREEN}✓ Backend packaging successful${NC}"
else
    echo -e "  ${RED}✗ Backend packaging failed${NC}"
    FAILED=1
fi

# Check JAR exists
echo "  [4/4] Verifying JAR artifact..."
if [ -f "target/ooc-backend-1.0.0.jar" ]; then
    echo -e "  ${GREEN}✓ JAR artifact exists${NC}"
else
    echo -e "  ${RED}✗ JAR artifact not found${NC}"
    FAILED=1
fi

echo ""

# Test Frontend
echo -e "${YELLOW}>>> Testing Frontend...${NC}"
cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud/frontend

# Install dependencies (if needed)
echo "  [1/4] Checking dependencies..."
if [ ! -d "node_modules" ]; then
    echo "  Installing dependencies..."
    pnpm install > /dev/null 2>&1
fi
echo -e "  ${GREEN}✓ Dependencies ready${NC}"

# Type check
echo "  [2/4] Running TypeScript type check..."
if pnpm vue-tsc --noEmit 2>&1; then
    echo -e "  ${GREEN}✓ Type check passed${NC}"
else
    echo -e "  ${RED}✗ Type check failed${NC}"
    FAILED=1
fi

# Lint check
echo "  [3/4] Running ESLint..."
if pnpm lint 2>&1 | grep -q "error"; then
    echo -e "  ${RED}✗ Lint errors found${NC}"
    FAILED=1
else
    echo -e "  ${GREEN}✓ Lint check passed${NC}"
fi

# Build frontend
echo "  [4/4] Building frontend..."
if pnpm build 2>&1 | tail -5 | grep -q "built"; then
    echo -e "  ${GREEN}✓ Frontend build successful${NC}"
else
    echo -e "  ${RED}✗ Frontend build failed${NC}"
    FAILED=1
fi

echo ""

# Summary
echo "========================================"
if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}  All tests passed! ✓${NC}"
    echo ""
    echo "  Backend JAR: backend/target/ooc-backend-1.0.0.jar"
    echo "  Frontend dist: frontend/dist/"
else
    echo -e "${RED}  Some tests failed! ✗${NC}"
fi
echo "========================================"

exit $FAILED
