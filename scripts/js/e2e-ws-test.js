#!/usr/bin/env node
/**
 * OOC E2E WebSocket Test - 等待真正回答并检测完整性
 * 
 * 关键点：
 * 1. "任务已加入队列"是状态消息，不是真正的回答
 * 2. 真正的回答是 stream_start → stream_delta → stream_end 之后的消息
 * 3. 必须等待 stream_end 才能确认收到了完整回答
 */

const WebSocket = require('ws');
const http = require('http');

const GREEN = '\x1b[32m';
const RED = '\x1b[31m';
const YELLOW = '\x1b[33m';
const BLUE = '\x1b[34m';
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
 * 发送消息并等待完整的 OpenClaw 回答（通过 stream_end 确认）
 */
function waitForCompleteResponse(ws, message, maxWaitMs = 120000) {
    return new Promise((resolve) => {
        let streamStarted = false;
        let streamEnded = false;
        let finalMessage = null;
        let contentBuffer = '';
        const startTime = Date.now();
        
        console.log(`\n    [Test] Sending: ${message}`);
        
        const handler = (data) => {
            try {
                const msg = JSON.parse(data.toString());
                const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
                
                // 队列状态消息（不是真正的回答）
                if (msg.type === 'message' && msg.message?.fromOpenClaw) {
                    const content = msg.message.content || '';
                    if (content.includes('任务已加入队列') || content.includes('正在准备处理')) {
                        console.log(`    [${elapsed}s] Queue status: ${content.substring(0, 40)}...`);
                        return; // 忽略队列状态消息
                    }
                }
                
                // 真正的流式回答开始
                if (msg.type === 'stream_start') {
                    streamStarted = true;
                    console.log(`    [${elapsed}s] ✓ Stream started (OpenClaw is responding)`);
                }
                
                // 流式内容片段
                if (msg.type === 'stream_delta' && msg.message?.content) {
                    contentBuffer += msg.message.content;
                    process.stdout.write('.');
                }
                
                // 流式回答结束 - 这是真正的完整回答
                if (msg.type === 'stream_end') {
                    streamEnded = true;
                    finalMessage = msg.message;
                    console.log(`\n    [${elapsed}s] ✓ Stream ended`);
                    console.log(`    [${elapsed}s] ✓ Total content: ${contentBuffer.length} chars`);
                    
                    ws.off('message', handler);
                    resolve({ 
                        success: true, 
                        message: finalMessage, 
                        content: contentBuffer,
                        streamStarted, 
                        streamEnded,
                        elapsedMs: Date.now() - startTime
                    });
                }
                
            } catch (e) {}
        };
        
        ws.on('message', handler);
        
        // 发送消息
        ws.send(JSON.stringify({ type: 'message', content: message, attachments: [] }));
        
        // 超时处理
        setTimeout(() => {
            ws.off('message', handler);
            const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
            console.log(`\n    [${elapsed}s] ✗ Timeout waiting for stream_end`);
            resolve({ 
                success: false, 
                message: finalMessage, 
                content: contentBuffer,
                streamStarted, 
                streamEnded,
                elapsedMs: Date.now() - startTime
            });
        }, maxWaitMs);
    });
}

async function runTests() {
    console.log('='.repeat(70));
    console.log('  OOC E2E WebSocket Test - 等待真正回答并检测完整性');
    console.log('='.repeat(70));
    console.log();
    console.log('  说明：此测试会等待 OpenClaw 的完整流式回答（stream_end）');
    console.log('       队列状态消息（"任务已加入队列"）会被忽略');
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
    console.log('  等待完整回答（max 120s）...');
    
    const identityResult = await waitForCompleteResponse(ws, '@openclaw 你是谁？', 120000);
    
    if (identityResult.success && identityResult.content.length > 0) {
        const content = identityResult.content;
        const hasIdentity = content.includes('机器人') || content.includes('助手') || content.includes('OpenClaw');
        
        logTest('Identity Response', hasIdentity ? 'PASS' : 'WARN', 
            `Length: ${content.length}, Has identity: ${hasIdentity}, Time: ${(identityResult.elapsedMs/1000).toFixed(1)}s`);
        
        console.log();
        console.log('  Content preview:');
        console.log('  ' + '-'.repeat(66));
        content.split('\n').slice(0, 8).forEach(line => {
            console.log('  ' + (line.length > 63 ? line.substring(0, 63) + '...' : line));
        });
        if (content.split('\n').length > 8) console.log('  ...');
        console.log('  ' + '-'.repeat(66));
    } else {
        logTest('Identity Response', 'FAIL', 
            `Stream started: ${identityResult.streamStarted}, Stream ended: ${identityResult.streamEnded}, Content: ${identityResult.content.length} chars`);
    }
    
    // Phase 5: Test @openclaw 天气查询
    console.log();
    console.log(`${YELLOW}>>> Phase 5: Test @openclaw 济宁邹城的温度是？${NC}`);
    console.log('  等待完整回答（max 180s）...');
    console.log();
    console.log('  完整性检测清单:');
    console.log('    □ 包含温度数值 (如 +22°C)');
    console.log('    □ 包含湿度信息 (如 100%)');
    console.log('    □ 包含风速信息 (如 ↙4km/h)');
    console.log('    □ 包含工具调用详情 (curl 命令输出)');
    console.log('    □ 内容未被截断 (显示完整)');
    console.log();
    
    const weatherResult = await waitForCompleteResponse(ws, '@openclaw 济宁邹城的温度是？', 180000);
    
    if (weatherResult.success && weatherResult.content.length > 0) {
        const content = weatherResult.content;
        
        // 完整性检测
        const checks = {
            hasTemp: content.includes('°C') || /\+?\d+°C/.test(content),
            hasHumidity: content.includes('%') || content.includes('湿度'),
            hasWind: content.includes('km/h') || content.includes('风速') || content.includes('↙'),
            hasToolDetails: content.includes('curl') || content.includes('wttr') || content.includes('```'),
            notTruncated: content.length > 100 && !content.includes('...')
        };
        
        console.log('  检测结果:');
        console.log(`    温度信息: ${checks.hasTemp ? '✓' : '✗'}`);
        console.log(`    湿度信息: ${checks.hasHumidity ? '✓' : '✗'}`);
        console.log(`    风速信息: ${checks.hasWind ? '✓' : '✗'}`);
        console.log(`    工具详情: ${checks.hasToolDetails ? '✓' : '✗'}`);
        console.log(`    内容完整: ${checks.notTruncated ? '✓' : '✗'} (${content.length} chars)`);
        console.log();
        
        const allPassed = checks.hasTemp && checks.hasHumidity && checks.hasWind && checks.hasToolDetails;
        logTest('Weather Response', allPassed ? 'PASS' : 'FAIL', 
            `Passed: ${Object.values(checks).filter(v => v).length}/5 checks, Time: ${(weatherResult.elapsedMs/1000).toFixed(1)}s`);
        
        console.log();
        console.log('  Content preview:');
        console.log('  ' + '-'.repeat(66));
        content.split('\n').slice(0, 12).forEach(line => {
            console.log('  ' + (line.length > 63 ? line.substring(0, 63) + '...' : line));
        });
        if (content.split('\n').length > 12) console.log('  ...');
        console.log('  ' + '-'.repeat(66));
    } else {
        logTest('Weather Response', 'FAIL', 
            `Stream started: ${weatherResult.streamStarted}, Stream ended: ${weatherResult.streamEnded}, Content: ${weatherResult.content.length} chars`);
    }
    
    ws.close();
    
    // Summary
    console.log();
    console.log('='.repeat(70));
    if (failed === 0) {
        console.log(`${GREEN}  All tests passed! ✓${NC}`);
    } else {
        console.log(`${RED}  Some tests failed! ✗${NC}`);
    }
    console.log('='.repeat(70));
    console.log(`\nResults: ${passed} passed, ${failed} failed`);
    
    process.exit(failed > 0 ? 1 : 0);
}

runTests();
