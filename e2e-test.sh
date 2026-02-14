#!/bin/bash
set -e

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

REPORT_FILE="test-report-e2e-$(date +%Y-%m-%d).md"

# Initialize report
echo "# OOC 平台端到端测试报告 - $(date +%Y-%m-%d)" > "$REPORT_FILE"
echo "" >> "$REPORT_FILE"
echo "## 测试概要" >> "$REPORT_FILE"
echo "- **测试时间**: $(date -u +"%Y-%m-%d %H:%M UTC")" >> "$REPORT_FILE"
echo "- **测试范围**: 全功能端到端测试" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

FAILED=0
test_results=()

# Helper function to log test results
log_test() {
    local name="$1"
    local status="$2"
    local details="$3"
    
    if [ "$status" = "PASS" ]; then
        echo -e "  ${GREEN}✓ $name${NC}"
        test_results+=("| $name | ✅ 通过 | $details |")
    else
        echo -e "  ${RED}✗ $name${NC}"
        test_results+=("| $name | ❌ 失败 | $details |")
        FAILED=1
    fi
}

echo "========================================"
echo "  OpenClaw on Cloud - E2E Test Suite"
echo "========================================"
echo ""

# ============================================
# 1. 后端测试
# ============================================
echo -e "${YELLOW}>>> 阶段 1: 后端编译测试...${NC}"

cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud/backend

# 编译测试
echo "  [1/3] 编译后端..."
if mvn clean compile -q -DskipTests > /dev/null 2>&1; then
    log_test "后端编译" "PASS" "mvn compile 成功"
else
    log_test "后端编译" "FAIL" "mvn compile 失败"
fi

# 单元测试
echo "  [2/3] 运行单元测试..."
if mvn test -q -Dtest=UserServiceTest,ChatRoomServiceTest,OocSessionServiceTest > /dev/null 2>&1; then
    log_test "单元测试" "PASS" "UserService, ChatRoomService, OocSessionService"
else
    log_test "单元测试" "FAIL" "部分测试失败"
fi

# 打包测试
echo "  [3/3] 打包后端..."
if mvn package -q -DskipTests > /dev/null 2>&1; then
    log_test "后端打包" "PASS" "JAR 构建成功"
else
    log_test "后端打包" "FAIL" "JAR 构建失败"
fi

# 验证 JAR 存在
if [ -f "target/ooc-backend-1.0.0.jar" ]; then
    JAR_SIZE=$(du -h target/ooc-backend-1.0.0.jar | cut -f1)
    log_test "JAR 文件验证" "PASS" "文件大小: $JAR_SIZE"
else
    log_test "JAR 文件验证" "FAIL" "JAR 文件不存在"
fi

echo ""

# ============================================
# 2. 前端测试
# ============================================
echo -e "${YELLOW}>>> 阶段 2: 前端构建测试...${NC}"
cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud/frontend

# 依赖检查
echo "  [1/4] 检查依赖..."
if [ -d "node_modules" ]; then
    log_test "依赖安装" "PASS" "node_modules 存在"
else
    echo "    安装依赖中..."
    if pnpm install > /dev/null 2>&1; then
        log_test "依赖安装" "PASS" "pnpm install 成功"
    else
        log_test "依赖安装" "FAIL" "pnpm install 失败"
    fi
fi

# TypeScript 类型检查
echo "  [2/4] TypeScript 类型检查..."
if pnpm vue-tsc --noEmit > /dev/null 2>&1; then
    log_test "TypeScript 类型检查" "PASS" "无类型错误"
else
    log_test "TypeScript 类型检查" "FAIL" "发现类型错误"
fi

# ESLint 检查
echo "  [3/4] ESLint 检查..."
LINT_OUTPUT=$(pnpm lint 2>&1 || true)
if echo "$LINT_OUTPUT" | grep -q "error"; then
    log_test "ESLint 检查" "FAIL" "发现 lint 错误"
else
    log_test "ESLint 检查" "PASS" "无 lint 错误"
fi

# 构建测试
echo "  [4/4] 构建前端..."
if pnpm build > /dev/null 2>&1; then
    log_test "前端构建" "PASS" "dist/ 生成成功"
else
    log_test "前端构建" "FAIL" "构建失败"
fi

echo ""

# ============================================
# 3. 服务健康检查
# ============================================
echo -e "${YELLOW}>>> 阶段 3: 服务健康检查...${NC}"

# 检查后端服务
echo "  [1/2] 检查后端服务..."
BACKEND_PID=$(pgrep -f "ooc-backend-1.0.0.jar" || true)
if [ -n "$BACKEND_PID" ]; then
    log_test "后端服务运行状态" "PASS" "PID: $BACKEND_PID"
    
    # 健康检查
    if curl -s http://localhost:8081/actuator/health > /dev/null 2>&1; then
        log_test "后端健康检查" "PASS" "Actuator health 正常"
    else
        log_test "后端健康检查" "WARN" "Actuator 不可用 (可能未启用)"
    fi
else
    log_test "后端服务运行状态" "FAIL" "服务未运行"
fi

# 检查前端服务
echo "  [2/2] 检查前端服务..."
# 检测方式1: 检查3000端口是否监听
FRONTEND_PID=$(lsof -ti:3000 2>/dev/null || pgrep -f "pnpm preview" || pgrep -f "vite preview" || pgrep -f "vite" || true)
if [ -n "$FRONTEND_PID" ]; then
    log_test "前端服务运行状态" "PASS" "PID: $FRONTEND_PID"
else
    log_test "前端服务运行状态" "FAIL" "服务未运行"
fi

echo ""

# ============================================
# 4. API 端点测试
# ============================================
echo -e "${YELLOW}>>> 阶段 4: API 端点测试...${NC}"

# 获取公钥并加密密码
echo "  获取公钥并加密密码..."
PUBLIC_KEY_RESPONSE=$(curl -s http://localhost:8081/api/auth/public-key 2>/dev/null || echo "")
PUBLIC_KEY=$(echo "$PUBLIC_KEY_RESPONSE" | grep -o '"publicKey":"[^"]*"' | cut -d'"' -f4 | sed 's/\\n/\n/g' || true)

if [ -n "$PUBLIC_KEY" ]; then
    # 使用 Node.js 加密密码
    ENCRYPTED_PASSWORD=$(node -e "
const crypto = require('crypto');
const publicKey = \`$PUBLIC_KEY\`;
try {
    const encrypted = crypto.publicEncrypt(publicKey, Buffer.from('admin'));
    console.log(encrypted.toString('base64'));
} catch(e) {
    console.error('Encrypt error:', e.message);
    process.exit(1);
}
" 2>&1)
    
    if [ $? -eq 0 ] && [ -n "$ENCRYPTED_PASSWORD" ]; then
        log_test "密码加密" "PASS" "成功使用 RSA 公钥加密密码"
    else
        log_test "密码加密" "WARN" "加密失败，将尝试明文密码"
        ENCRYPTED_PASSWORD="admin"
    fi
else
    log_test "获取公钥" "WARN" "无法获取公钥，将尝试明文密码"
    ENCRYPTED_PASSWORD="admin"
fi

# 获取访问令牌
echo "  获取访问令牌..."
# E2E 测试专用账号
E2E_USERNAME="ooc-test-1771067194"
E2E_PASSWORD="CxVgvs7QQyRFNWUAGlKR/w=="

# 将密码转义，处理特殊字符
ESCAPED_PASSWORD=$(echo "$E2E_PASSWORD" | sed 's/"/\\"/g; s/\//\\\//g')
TOKEN_RESPONSE=$(curl -s -X POST http://localhost:8081/api/auth/login \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$E2E_USERNAME\",\"password\":\"$ESCAPED_PASSWORD\"}" 2>/dev/null || echo "")

TOKEN=$(echo "$TOKEN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4 || true)

if [ -n "$TOKEN" ]; then
    log_test "登录 API" "PASS" "成功获取访问令牌"
    
    # 测试聊天室列表 API
    echo "  测试聊天室列表..."
    ROOMS_RESPONSE=$(curl -s -X GET http://localhost:8081/api/chat-rooms \
        -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "")
    
    if echo "$ROOMS_RESPONSE" | grep -q "id"; then
        log_test "聊天室列表 API" "PASS" "正常返回数据"
        
        # 提取第一个聊天室 ID
        ROOM_ID=$(echo "$ROOMS_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
        
        if [ -n "$ROOM_ID" ]; then
            # 测试获取消息 API
            echo "  测试获取消息..."
            MESSAGES_RESPONSE=$(curl -s -X GET "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" \
                -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "")
            
            if [ -n "$MESSAGES_RESPONSE" ]; then
                log_test "消息列表 API" "PASS" "正常返回数据"
            else
                log_test "消息列表 API" "FAIL" "返回空数据"
            fi
            
            # 测试获取成员 API
            echo "  测试获取成员..."
            MEMBERS_RESPONSE=$(curl -s -X GET "http://localhost:8081/api/chat-rooms/$ROOM_ID/members" \
                -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "")
            
            if [ -n "$MEMBERS_RESPONSE" ]; then
                log_test "成员列表 API" "PASS" "正常返回数据"
            else
                log_test "成员列表 API" "FAIL" "返回空数据"
            fi
        fi
    else
        log_test "聊天室列表 API" "FAIL" "返回数据异常"
    fi
    
    # 测试 @提及 API
    echo "  测试 @提及 API..."
    MENTIONS_RESPONSE=$(curl -s -X GET http://localhost:8081/api/mentions \
        -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "")
    
    if [ -n "$MENTIONS_RESPONSE" ]; then
        log_test "@提及列表 API" "PASS" "正常返回数据"
    else
        log_test "@提及列表 API" "FAIL" "返回空数据"
    fi
else
    log_test "登录 API" "FAIL" "无法获取访问令牌"
fi

echo ""

# ============================================
# 5. OpenClaw 端到端测试 - 核心测试
# ============================================
echo -e "${YELLOW}>>> 阶段 5: OpenClaw 端到端测试 (核心)...${NC}"

if [ -n "$TOKEN" ] && [ -n "$ROOM_ID" ]; then
    
    # 测试用例 1: 基本消息发送
    echo "  [测试 1/3] 基本消息发送..."
    TEST_MSG="测试消息 $(date +%s)"
    SEND_RESPONSE=$(curl -s -X POST "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"content\":\"$TEST_MSG\"}" 2>/dev/null || echo "")
    
    if echo "$SEND_RESPONSE" | grep -q "id"; then
        log_test "基本消息发送" "PASS" "消息发送成功"
    else
        log_test "基本消息发送" "FAIL" "消息发送失败"
    fi
    
    # 测试用例 2: @openclaw 触发测试
    echo "  [测试 2/4] @openclaw 触发测试..."
    echo -e "    ${BLUE}⚠ 注意: 此测试需要人工验证前端显示${NC}"
    
    # 发送 @openclaw 消息
    OPENCLAW_RESPONSE=$(curl -s -X POST "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"content":"@openclaw 你好"}' 2>/dev/null || echo "")
    
    if echo "$OPENCLAW_RESPONSE" | grep -q "id"; then
        log_test "@openclaw 触发" "PASS" "消息发送成功，任务已加入队列"
        echo -e "    ${YELLOW}⚡ 请在前端观察 OpenClaw 响应是否完整显示${NC}"
    else
        log_test "@openclaw 触发" "FAIL" "消息发送失败"
    fi
    
    # 测试用例 3: @openclaw 你是谁 - 重点测试
    echo ""
    echo -e "  ${YELLOW}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "  ${YELLOW}║ [测试 3/4] 🔍 关键测试: @openclaw 你是谁？                 ║${NC}"
    echo -e "  ${YELLOW}╚════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "  测试步骤:"
    echo "    1. 在前端页面打开聊天室"
    echo "    2. 发送消息: @openclaw 你是谁？"
    echo "    3. 观察响应是否完整显示在前端"
    echo ""
    
    # 发送测试消息
    IDENTITY_RESPONSE=$(curl -s -X POST "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"content":"@openclaw 你是谁？"}' 2>/dev/null || echo "")
    
    if echo "$IDENTITY_RESPONSE" | grep -q "id"; then
        log_test "身份询问测试" "PASS" "消息已发送，等待 OpenClaw 响应"
        
        echo ""
        echo -e "  ${GREEN}✓ 测试消息已发送!${NC}"
        echo ""
        echo "  验证清单:"
        echo "    □ OpenClaw 是否返回了回复（非 '*(OpenClaw 无回复)*'）"
        echo "    □ 回复内容是否包含身份介绍（如 '机器人助手'）"
        echo "    □ 回复内容是否完整显示，没有截断"
        echo "    □ 工具调用信息是否正确渲染（如果有）"
        echo "    □ 消息样式是否正常（非工具调用消息样式）"
        echo ""
        echo -e "  ${YELLOW}⏳ 等待 5 秒让 OpenClaw 处理...${NC}"
        sleep 5
        
        # 检查最新消息
        echo "  检查最新消息..."
        LATEST_MESSAGES=$(curl -s -X GET "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages?page=0&size=5" \
            -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "")
        
        # 检查是否有 OpenClaw 的回复
        if echo "$LATEST_MESSAGES" | grep -q "fromOpenClaw.*true"; then
            # 提取 OpenClaw 回复内容
            OPENCLAW_CONTENT=$(echo "$LATEST_MESSAGES" | grep -o '"content":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
            
            if [ -n "$OPENCLAW_CONTENT" ] && [ "$OPENCLAW_CONTENT" != "*(OpenClaw 无回复)*" ]; then
                CONTENT_LEN=${#OPENCLAW_CONTENT}
                log_test "OpenClaw 响应完整性" "PASS" "收到回复，长度: $CONTENT_LEN 字符"
                
                # 检查关键内容
                if echo "$OPENCLAW_CONTENT" | grep -qi "机器人\|助手\|openclaw"; then
                    log_test "OpenClaw 响应内容" "PASS" "回复包含身份信息"
                else
                    log_test "OpenClaw 响应内容" "WARN" "回复可能不包含预期身份内容"
                fi
            else
                log_test "OpenClaw 响应完整性" "FAIL" "收到空回复或无回复占位符"
            fi
        else
            log_test "OpenClaw 响应检测" "WARN" "未检测到 OpenClaw 回复（可能仍在处理）"
        fi
    else
        log_test "身份询问测试" "FAIL" "消息发送失败"
    fi

    # 测试用例 4: @openclaw 天气查询 - 重点测试工具调用输出完整性
    echo ""
    echo -e "  ${YELLOW}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "  ${YELLOW}║ [测试 4/4] 🌤️ 关键测试: @openclaw 济宁邹城的温度是？       ║${NC}"
    echo -e "  ${YELLOW}║     测试工具调用消息的完整输出显示                         ║${NC}"
    echo -e "  ${YELLOW}╚════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "  测试步骤:"
    echo "    1. 在前端页面打开聊天室"
    echo "    2. 发送消息: @openclaw 济宁邹城的温度是？"
    echo "    3. 观察响应是否包含完整的天气信息（温度、湿度、风速等）"
    echo ""
    
    # 发送测试消息
    WEATHER_RESPONSE=$(curl -s -X POST "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"content":"@openclaw 济宁邹城的温度是？"}' 2>/dev/null || echo "")
    
    if echo "$WEATHER_RESPONSE" | grep -q "id"; then
        log_test "天气查询测试" "PASS" "消息已发送，等待 OpenClaw 响应"
        
        echo ""
        echo -e "  ${GREEN}✓ 测试消息已发送!${NC}"
        echo ""
        echo "  验证清单:"
        echo "    □ OpenClaw 是否返回了回复（非 '*(OpenClaw 无回复)*'）"
        echo "    □ 回复内容是否包含温度信息"
        echo "    □ 回复内容是否包含湿度、风速等详细信息"
        echo "    □ 工具调用详情是否正确显示（curl 命令及输出）"
        echo "    □ 回复内容是否完整显示，没有被截断"
        echo ""
        echo -e "  ${YELLOW}⏳ 等待 8 秒让 OpenClaw 处理（天气查询需要调用外部 API）...${NC}"
        sleep 8
        
        # 检查最新消息
        echo "  检查最新消息..."
        LATEST_MESSAGES=$(curl -s -X GET "http://localhost:8081/api/chat-rooms/$ROOM_ID/messages?page=0&size=5" \
            -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "")
        
        # 检查是否有 OpenClaw 的回复
        if echo "$LATEST_MESSAGES" | grep -q "fromOpenClaw.*true"; then
            # 提取 OpenClaw 回复内容
            OPENCLAW_CONTENT=$(echo "$LATEST_MESSAGES" | grep -o '"content":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
            
            if [ -n "$OPENCLAW_CONTENT" ] && [ "$OPENCLAW_CONTENT" != "*(OpenClaw 无回复)*" ]; then
                CONTENT_LEN=${#OPENCLAW_CONTENT}
                log_test "天气查询响应完整性" "PASS" "收到回复，长度: $CONTENT_LEN 字符"
                
                # 检查关键内容
                if echo "$OPENCLAW_CONTENT" | grep -qi "温度\|°c\|weather\|wttr"; then
                    log_test "天气查询响应内容" "PASS" "回复包含温度信息"
                else
                    log_test "天气查询响应内容" "WARN" "回复可能不包含温度信息"
                fi
                
                # 检查是否包含工具调用信息
                if echo "$OPENCLAW_CONTENT" | grep -q "Tools used\|curl"; then
                    log_test "工具调用详情" "PASS" "回复包含工具调用信息"
                else
                    log_test "工具调用详情" "WARN" "回复可能不包含工具调用详情"
                fi
            else
                log_test "天气查询响应完整性" "FAIL" "收到空回复或无回复占位符"
            fi
        else
            log_test "天气查询响应检测" "WARN" "未检测到 OpenClaw 回复（可能仍在处理）"
        fi
    else
        log_test "天气查询测试" "FAIL" "消息发送失败"
    fi
    
else
    echo -e "  ${RED}跳过 OpenClaw 测试: 未获取到有效令牌或聊天室${NC}"
fi

echo ""

# ============================================
# 6. 生成报告
# ============================================
echo -e "${YELLOW}>>> 生成测试报告...${NC}"

# 写入测试结果表格
echo "" >> "$REPORT_FILE"
echo "## 详细测试结果" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"
echo "| 测试项 | 状态 | 详情 |" >> "$REPORT_FILE"
echo "|--------|------|------|" >> "$REPORT_FILE"

for result in "${test_results[@]}"; do
    echo "$result" >> "$REPORT_FILE"
done

# 添加 OpenClaw 响应检查清单
cat >> "$REPORT_FILE" << 'EOF'

## OpenClaw 响应完整性检查清单

### 测试 1: @openclaw 你是谁？

#### 基本显示
- [ ] OpenClaw 返回了非空回复（不是 "*(OpenClaw 无回复)*"）
- [ ] 回复内容包含身份信息（如 "机器人助手"）
- [ ] 回复内容没有被截断或截断显示

#### 格式渲染
- [ ] Markdown 格式正确渲染（如 **粗体**）
- [ ] 代码块如果有的话正确显示
- [ ] 工具调用信息（如果有）正确渲染为卡片样式

#### 消息类型
- [ ] 非工具调用消息显示为普通消息样式
- [ ] 工具调用消息显示为工具调用卡片样式

#### 网络层面
- [ ] WebSocket stream_start 事件正常接收
- [ ] WebSocket stream_delta 事件正常接收
- [ ] WebSocket stream_end 事件正常接收
- [ ] 消息最终正确保存到数据库

### 测试 2: @openclaw 济宁邹城的温度是？

#### 工具调用输出完整性（关键测试）
- [ ] OpenClaw 返回了非空回复（不是 "*(OpenClaw 无回复)*"）
- [ ] 回复内容包含温度信息（如 "+23°C"）
- [ ] 回复内容包含湿度信息（如 "100%"）
- [ ] 回复内容包含风速信息
- [ ] 工具调用详情（curl 命令及输出）正确显示
- [ ] **回复内容没有被截断** - 这是此测试的重点

#### 对比验证
- [ ] OOC 平台显示的内容与 OpenClaw web 页面一致
- [ ] 没有丢失工具调用的详细输出
- [ ] 工具调用卡片样式正确

EOF

# 总结
echo "" >> "$REPORT_FILE"
echo "## 总结" >> "$REPORT_FILE"
if [ $FAILED -eq 0 ]; then
    echo "" >> "$REPORT_FILE"
    echo "**所有测试通过 ✅**" >> "$REPORT_FILE"
else
    echo "" >> "$REPORT_FILE"
    echo "**部分测试失败 ❌**" >> "$REPORT_FILE"
fi
echo "" >> "$REPORT_FILE"
echo "*报告生成时间: $(date -u +"%Y-%m-%d %H:%M UTC")*" >> "$REPORT_FILE"

echo "  报告已保存到: $REPORT_FILE"

# ============================================
# 总结
# ============================================
echo ""
echo "========================================"
if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}  所有测试通过! ✓${NC}"
else
    echo -e "${RED}  部分测试失败! ✗${NC}"
fi
echo "========================================"
echo ""
echo "测试报告: $REPORT_FILE"
echo ""

exit $FAILED
