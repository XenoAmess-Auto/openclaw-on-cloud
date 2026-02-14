#!/usr/bin/env node
/**
 * OOC E2E WebSocket Test - 等待真正回答并检测完整性
 */

const WebSocket = require('ws');
const http = require('http');

const GREEN = '\x1b[32m';
const RED = '\x1b[31m';
const YELLOW = '\x1b[33m';
const NC = '\x1b[0m';

const TEST_USERNAME = 'ooc-test-1771067194';
const TEST_PASSWORD = 'CxVgvs7QQyRFNWUAGlKR/w==';
const WS_URL = 'ws://localhost:8081/ws/chat';

let passed = 0;
let failed = 0;

function logTest(name, status, details) {
    if (status === 'PASS') {
        console.log(`  ${GREEN}✓ ${name}${NC}`);
        passed++;
    } else if (status === 'FAIL') {
        console.log(`  ${RED}✗ ${name}${NC}`);
        failed++;
    } else {
        console.log(`  ${YELLOW}⚠ ${name}${NC}`);
    }
    console.log(`     ${details}`);
}

function login() {
    return new Promise((resolve, reject) => {
        const postData = JSON.stringify({ username: TEST_USERNAME, password: TEST_PASSWORD });
        const req = http.request({
            hostname: 'localhost', port: 8081, path: '/api/auth/login',
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(postData) }
        }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    const parsed = JSON.parse(data);
                    if (parsed.token) resolve(parsed);
                    else reject(new Error('No token'));
                } catch (e) { reject(e); }
            });
        });
        req.on('error', reject);
        req.write(postData);
        req.end();
    });
}

function getOrCreateRoom(token) {
    return new Promise((resolve, reject) => {
        const req = http.request({
            hostname: 'localhost', port: 8081, path: '/api/chat-rooms',
            headers: { 'Authorization': `Bearer ${token}` }
        }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    const rooms = JSON.parse(data);
                    if (rooms.length > 0) resolve(rooms[0]);
                    else {
                        // Create room
                        const postData = JSON.stringify({
                            name: 'E2E Test', description: 'Test room', type: 'PUBLIC'
                        });
                        const req2 = http.request({
                            hostname: 'localhost', port: 8081, path: '/api/chat-rooms',
                            method: 'POST',
                            headers: {
                                'Authorization': `Bearer ${token}`,
                                'Content-Type': 'application/json',
                                'Content-Length': Buffer.byteLength(postData)
                            }
                        }, (res2) => {
                            let data2 = '';
                            res2.on('data', chunk => data2 += chunk);
                            res2.on('end', () => resolve(JSON.parse(data2)));
                        });
                        req2.write(postData);
                        req2.end();
                    }
                } catch (e) { reject(e); }
            });
        });
        req.end();
    });
}

function connectWebSocket(roomId) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(WS_URL);
        const timeout = setTimeout(() => { ws.close(); reject(new Error('Timeout')); }, 10000);
        
        ws.on('open', () => {
            ws.send(JSON.stringify({
                type: 'join', roomId, userId: TEST_USERNAME, userName: 'E2E Test'
            }));
        });
        
        ws.on('message', (data) => {
            try {
                const msg = JSON.parse(data.toString());
                if (msg.type === 'history') {
                    clearTimeout(timeout);
                    resolve(ws);
                }
            } catch (e) {}
        });
        
        ws.on('error', (err) => { clearTimeout(timeout); reject(err); });
    });
}

/**
 * 发送消息并等待真正的 OpenClaw 回答（不是队列状态消息）
 */
function waitForRealResponse(ws, message, maxWaitMs = 60000) {
    return new Promise((resolve) => {
        const responses = [];
        let streamStarted = false;
        let streamEnded = false;
        
        const handler = (data) => {
            try {
                const msg = JSON.parse(data.toString());
                
                if (msg.type === 'stream_start') {
                    streamStarted = true;
                    console.log('    [Event] Stream started');
                }
                
                if (msg.type === 'stream_delta') {
                    process.stdout.write('.');
                }
                
                if (msg.type === 'stream_end') {
                    streamEnded = true;
                    console.log('\n    [Event] Stream ended');
                    if (msg.message) responses.push(msg.message);
                }
                
                if (msg.type === 'message' && msg.message?.fromOpenClaw) {
                    const content = msg.message.content || '';
                    // 过滤队列状态消息，只保留真正的回答
                    if (!content.includes('任务已加入队列') && !content.includes('正在准备处理')) {
                        responses.push(msg.message);
                        console.log(`    [Event] Real response received (${content.length} chars)`);
                    } else {
                        console.log(`    [Event] Queue status: ${content.substring(0, 40)}...`);
                    }
                }
            } catch (e) {}
        };
        
        ws.on('message', handler);
        
        // 发送消息
        console.log(`    Sending: ${message}`);
        ws.send(JSON.stringify({ type: 'message', content: message, attachments: [] }));
        
        // 超时处理
        setTimeout(() => {
            ws.off('message', handler);
            // 返回最后一个非队列状态的真实回答
            const realResponse = responses.length > 0 ? responses[responses.length - 1] : null;
            resolve({ 
                response: realResponse, 
                streamStarted, 
                streamEnded,
                allResponses: responses 
            });
        }, maxWaitMs);
    });
}

async function runTests() {
    console.log('='.repeat(60));
    console.log('  OOC E2E WebSocket Test - 等待真正回答并检测完整性');
    console.log('='.repeat(60));
    console.log();
    
    // Phase 1: Login
    console.log(`${YELLOW}>>> Phase 1: Login${NC}`);
    let token, room, ws;
    try {
        const auth = await login();
        token = auth.token;
        logTest('Login', 'PASS', `User: ${auth.username}`);
    } catch (e) {
        logTest('Login', 'FAIL', e.message);
        process.exit(1);
    }
    
    // Phase 2: Get Room
    console.log();
    console.log(`${YELLOW}>>> Phase 2: Get or Create Room${NC}`);
    try {
        room = await getOrCreateRoom(token);
        logTest('Room', 'PASS', `${room.name} (${room.id})`);
    } catch (e) {
        logTest('Room', 'FAIL', e.message);
        process.exit(1);
    }
    
    // Phase 3: Connect WebSocket
    console.log();
    console.log(`${YELLOW}>>> Phase 3: WebSocket Connection${NC}`);
    try {
        ws = await connectWebSocket(room.id);
        logTest('WebSocket', 'PASS', 'Connected');
    } catch (e) {
        logTest('WebSocket', 'FAIL', e.message);
        process.exit(1);
    }
    
    // Phase 4: Test @openclaw 你是谁
    console.log();
    console.log(`${YELLOW}>>> Phase 4: Test @openclaw 你是谁？${NC}`);
    console.log('  Waiting for real response (max 60s)...');
    
    const identityResult = await waitForRealResponse(ws, '@openclaw 你是谁？', 60000);
    
    if (identityResult.response) {
        const content = identityResult.response.content || '';
        const hasIdentity = content.includes('机器人') || content.includes('助手') || content.includes('OpenClaw');
        const notTruncated = content.length > 50;
        
        logTest('Identity Response', hasIdentity ? 'PASS' : 'FAIL', 
            `Length: ${content.length}, Has identity: ${hasIdentity}, Not truncated: ${notTruncated}`);
        
        console.log();
        console.log('  Content preview:');
        console.log('  ' + '-'.repeat(56));
        content.split('\n').slice(0, 6).forEach(line => {
            console.log('  ' + (line.length > 53 ? line.substring(0, 53) + '...' : line));
        });
        if (content.split('\n').length > 6) console.log('  ...');
        console.log('  ' + '-'.repeat(56));
    } else {
        logTest('Identity Response', 'FAIL', 'No real response received (only queue status)');
    }
    
    // Phase 5: Test @openclaw 天气查询
    console.log();
    console.log(`${YELLOW}>>> Phase 5: Test @openclaw 济宁邹城的温度是？${NC}`);
    console.log('  Waiting for real response (max 90s)...');
    console.log();
    console.log('  完整性检测清单:');
    console.log('    □ 包含温度数值 (如 +22°C)');
    console.log('    □ 包含湿度信息');
    console.log('    □ 包含风速信息');
    console.log('    □ 包含工具调用详情 (curl 命令输出)');
    console.log('    □ 内容未被截断');
    console.log();
    
    const weatherResult = await waitForRealResponse(ws, '@openclaw 济宁邹城的温度是？', 90000);
    
    if (weatherResult.response) {
        const content = weatherResult.response.content || '';
        
        // 完整性检测
        const checks = {
            hasTemp: content.includes('°C') || /\+?\d+°C/.test(content),
            hasHumidity: content.includes('%') || content.includes('湿度'),
            hasWind: content.includes('km/h') || content.includes('风速') || content.includes('↙'),
            hasToolDetails: content.includes('curl') || content.includes('wttr') || weatherResult.response.toolCalls?.length > 0,
            notTruncated: content.length > 100
        };
        
        console.log('  检测结果:');
        console.log(`    温度信息: ${checks.hasTemp ? '✓' : '✗'}`);
        console.log(`    湿度信息: ${checks.hasHumidity ? '✓' : '✗'}`);
        console.log(`    风速信息: ${checks.hasWind ? '✓' : '✗'}`);
        console.log(`    工具详情: ${checks.hasToolDetails ? '✓' : '✗'}`);
        console.log(`    内容完整: ${checks.notTruncated ? '✓' : '✗'} (${content.length} chars)`);
        console.log();
        
        const allPassed = Object.values(checks).every(v => v);
        logTest('Weather Response', allPassed ? 'PASS' : 'FAIL', 
            `Passed: ${Object.values(checks).filter(v => v).length}/5 checks`);
        
        console.log();
        console.log('  Content preview:');
        console.log('  ' + '-'.repeat(56));
        content.split('\n').slice(0, 10).forEach(line => {
            console.log('  ' + (line.length > 53 ? line.substring(0, 53) + '...' : line));
        });
        if (content.split('\n').length > 10) console.log('  ...');
        console.log('  ' + '-'.repeat(56));
    } else {
        logTest('Weather Response', 'FAIL', 'No real response received (only queue status)');
    }
    
    ws.close();
    
    // Summary
    console.log();
    console.log('='.repeat(60));
    if (failed === 0) {
        console.log(`${GREEN}  All tests passed! ✓${NC}`);
    } else {
        console.log(`${RED}  Some tests failed! ✗${NC}`);
    }
    console.log('='.repeat(60));
    console.log(`\nResults: ${passed} passed, ${failed} failed`);
    
    process.exit(failed > 0 ? 1 : 0);
}

runTests();
