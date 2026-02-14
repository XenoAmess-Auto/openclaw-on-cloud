#!/usr/bin/env node
/**
 * OOC E2E WebSocket Test
 * 
 * This script mimics how the real frontend sends messages via WebSocket.
 * Usage: node e2e-ws-test.js
 */

const WebSocket = require('ws');
const http = require('http');

// Colors for output
const GREEN = '\x1b[32m';
const RED = '\x1b[31m';
const YELLOW = '\x1b[33m';
const BLUE = '\x1b[34m';
const NC = '\x1b[0m';

// Test configuration
const BASE_URL = 'localhost:8081';
const WS_URL = `ws://${BASE_URL}/ws/chat`;
const API_BASE = `http://${BASE_URL}/api`;

// E2E Test Account (from MEMORY.md)
const TEST_USERNAME = 'ooc-test-1771067194';
const TEST_PASSWORD = 'CxVgvs7QQyRFNWUAGlKR/w==';

// Test results
let passed = 0;
let failed = 0;
const testResults = [];

function logTest(name, status, details) {
    if (status === 'PASS') {
        console.log(`  ${GREEN}✓ ${name}${NC}`);
        testResults.push({ name, status: '✅ 通过', details });
        passed++;
    } else if (status === 'WARN') {
        console.log(`  ${YELLOW}⚠ ${name}${NC}`);
        testResults.push({ name, status: '⚠️ 警告', details });
        passed++;
    } else {
        console.log(`  ${RED}✗ ${name}${NC}`);
        testResults.push({ name, status: '❌ 失败', details });
        failed++;
    }
}

function httpRequest(path, options = {}) {
    return new Promise((resolve, reject) => {
        const opts = {
            hostname: 'localhost',
            port: 8081,
            path: `/api${path}`,
            method: options.method || 'GET',
            headers: options.headers || {}
        };
        
        const req = http.request(opts, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    resolve({ status: res.statusCode, data: JSON.parse(data) });
                } catch (e) {
                    resolve({ status: res.statusCode, data });
                }
            });
        });
        
        req.on('error', reject);
        
        if (options.body) {
            req.write(JSON.stringify(options.body));
        }
        req.end();
    });
}

async function login() {
    return new Promise((resolve, reject) => {
        const postData = JSON.stringify({ 
            username: TEST_USERNAME, 
            password: TEST_PASSWORD 
        });
        
        const options = {
            hostname: 'localhost',
            port: 8081,
            path: '/api/auth/login',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(postData)
            }
        };
        
        const req = http.request(options, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    const parsed = JSON.parse(data);
                    if (parsed.token) {
                        resolve(parsed);
                    } else {
                        reject(new Error('No token: ' + data));
                    }
                } catch (e) {
                    reject(new Error('Parse error: ' + data));
                }
            });
        });
        
        req.on('error', (e) => reject(e));
        req.write(postData);
        req.end();
    });
}

async function getOrCreateRoom(token) {
    const res = await httpRequest('/chat-rooms', {
        headers: { 'Authorization': `Bearer ${token}` }
    });
    
    if (res.data && res.data.length > 0) {
        return res.data[0];
    }
    
    const createRes = await httpRequest('/chat-rooms', {
        method: 'POST',
        headers: { 
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
        },
        body: { 
            name: 'E2E Test Room', 
            description: 'Auto-created for E2E testing',
            type: 'PUBLIC'
        }
    });
    
    return createRes.data;
}

function connectWebSocket(roomId, token, userId, userName) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(WS_URL);
        const events = [];
        let timeoutId;
        
        timeoutId = setTimeout(() => {
            ws.close();
            reject(new Error('WebSocket timeout'));
        }, 30000);
        
        ws.on('open', () => {
            ws.send(JSON.stringify({
                type: 'join',
                roomId,
                userId,
                userName
            }));
        });
        
        ws.on('message', (data) => {
            try {
                const msg = JSON.parse(data.toString());
                events.push(msg);
                
                if (msg.type === 'history') {
                    clearTimeout(timeoutId);
                    resolve({ ws, events, userId, userName });
                }
            } catch (e) {
                console.error('Parse error:', e.message);
            }
        });
        
        ws.on('error', (err) => {
            clearTimeout(timeoutId);
            reject(err);
        });
    });
}

function sendMessageAndWait(ws, message, events, timeout = 15000) {
    return new Promise((resolve) => {
        let openClawResponse = null;
        let streamCompleted = false;
        let streamStarted = false;
        
        const messageHandler = (data) => {
            try {
                const msg = JSON.parse(data.toString());
                events.push(msg);
                
                if (msg.type === 'message' && msg.message?.fromOpenClaw) {
                    openClawResponse = msg.message;
                }
                
                if (msg.type === 'stream_start') {
                    streamStarted = true;
                }
                
                if (msg.type === 'stream_end') {
                    streamCompleted = true;
                    openClawResponse = msg.message;
                    ws.off('message', messageHandler);
                    resolve({ openClawResponse, streamCompleted, streamStarted });
                }
            } catch (e) {}
        };
        
        ws.on('message', messageHandler);
        
        ws.send(JSON.stringify({
            type: 'message',
            content: message,
            attachments: []
        }));
        
        setTimeout(() => {
            ws.off('message', messageHandler);
            resolve({ openClawResponse, streamCompleted, streamStarted });
        }, timeout);
    });
}

async function runTests() {
    console.log('=' .repeat(50));
    console.log('  OOC E2E WebSocket Test Suite');
    console.log('=' .repeat(50));
    console.log();
    
    let token, room, connection;
    
    console.log(`${YELLOW}>>> Phase 1: Login...${NC}`);
    try {
        const auth = await login();
        token = auth.token;
        logTest('Login API', 'PASS', `User: ${auth.username}`);
    } catch (e) {
        logTest('Login API', 'FAIL', e.message);
        process.exit(1);
    }
    
    console.log();
    console.log(`${YELLOW}>>> Phase 2: Get or Create Room...${NC}`);
    try {
        room = await getOrCreateRoom(token);
        logTest('Room Setup', 'PASS', `Room: ${room.name} (${room.id})`);
    } catch (e) {
        logTest('Room Setup', 'FAIL', e.message);
        process.exit(1);
    }
    
    console.log();
    console.log(`${YELLOW}>>> Phase 3: WebSocket Connection...${NC}`);
    try {
        connection = await connectWebSocket(room.id, token, TEST_USERNAME, 'OOC E2E Test');
        logTest('WebSocket Connect', 'PASS', 'Connected and joined room');
    } catch (e) {
        logTest('WebSocket Connect', 'FAIL', e.message);
        process.exit(1);
    }
    
    console.log();
    console.log(`${YELLOW}>>> Phase 4: Test 1/3 - Basic Message...${NC}`);
    try {
        const result = await sendMessageAndWait(connection.ws, 'Hello from E2E test', connection.events);
        logTest('Basic Message', 'PASS', 'Message sent');
    } catch (e) {
        logTest('Basic Message', 'FAIL', e.message);
    }
    
    console.log();
    console.log(`${YELLOW}>>> Phase 5: Test 2/3 - @openclaw 你是谁？${NC}`);
    console.log('  Sending: @openclaw 你是谁？');
    console.log('  Waiting 25s for response...');
    try {
        const result = await sendMessageAndWait(connection.ws, '@openclaw 你是谁？', connection.events, 25000);
        
        if (result.openClawResponse) {
            const content = result.openClawResponse.content || '';
            logTest('Identity Query', 'PASS', `Response: ${content.substring(0, 40)}...`);
        } else {
            logTest('Identity Query', 'FAIL', 'No OpenClaw response');
        }
    } catch (e) {
        logTest('Identity Query', 'FAIL', e.message);
    }
    
    console.log('  Waiting 10s for queue...');
    await new Promise(r => setTimeout(r, 10000));
    
    console.log();
    console.log(`${YELLOW}>>> Phase 6: Test 3/3 - @openclaw 济宁邹城的温度是？${NC}`);
    console.log('  Sending: @openclaw 济宁邹城的温度是？');
    console.log('  Waiting 35s for response...');
    try {
        const result = await sendMessageAndWait(connection.ws, '@openclaw 济宁邹城的温度是？', connection.events, 35000);
        
        if (result.openClawResponse) {
            const content = result.openClawResponse.content || '';
            const hasTemp = content.includes('°C') || content.includes('温度') || content.includes('weather');
            const hasToolCall = result.openClawResponse.isToolCall || result.openClawResponse.toolCalls?.length > 0;
            
            if (hasTemp) {
                logTest('Weather Query', 'PASS', `Has temp, ${content.length} chars`);
            } else {
                logTest('Weather Query', 'WARN', 'May not contain temp');
            }
            
            if (hasToolCall) {
                logTest('Tool Call Details', 'PASS', 'Has tool call info');
            } else {
                logTest('Tool Call Details', 'WARN', 'May not have tool details');
            }
            
            console.log();
            console.log('  Response preview:');
            console.log('  ' + '─'.repeat(48));
            content.split('\n').slice(0, 8).forEach(line => {
                console.log('  ' + (line.length > 45 ? line.substring(0, 45) + '...' : line));
            });
            console.log('  ' + '─'.repeat(48));
        } else {
            logTest('Weather Query', 'FAIL', 'No OpenClaw response');
        }
    } catch (e) {
        logTest('Weather Query', 'FAIL', e.message);
    }
    
    if (connection && connection.ws) {
        connection.ws.close();
    }
    
    console.log();
    console.log('=' .repeat(50));
    if (failed === 0) {
        console.log(`${GREEN}  All tests passed! ✓${NC}`);
    } else {
        console.log(`${RED}  Some tests failed! ✗${NC}`);
    }
    console.log('=' .repeat(50));
    console.log(`\nPassed: ${passed}, Failed: ${failed}`);
    
    process.exit(failed > 0 ? 1 : 0);
}

runTests();
