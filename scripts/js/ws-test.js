const WebSocket = require('ws');

// Parse arguments
const args = process.argv.slice(2);
const roomId = args[0];
const token = args[1];
const message = args[2] || '@openclaw 你好';

if (!roomId || !token) {
    console.error('Usage: node ws-test.js <roomId> <token> [message]');
    process.exit(1);
}

// Get user info from token (decode JWT payload)
function decodeToken(token) {
    try {
        const base64Payload = token.split('.')[1];
        const payload = Buffer.from(base64Payload, 'base64').toString('utf8');
        return JSON.parse(payload);
    } catch (e) {
        return { sub: 'unknown', nickname: 'E2E Test' };
    }
}

const userInfo = decodeToken(token);
const userId = userInfo.sub || userInfo.userId || 'e2e-test';
const userName = userInfo.nickname || userInfo.username || 'E2E Test';

console.log(`Connecting to WebSocket...`);
console.log(`Room: ${roomId}`);
console.log(`User: ${userName} (${userId})`);
console.log(`Message: ${message}`);

const ws = new WebSocket('ws://localhost:8081/ws/chat');

let messageReceived = false;
let openClawResponseReceived = false;

timeoutId = setTimeout(() => {
    console.error('Timeout: No response received within 30 seconds');
    ws.close();
    process.exit(1);
}, 30000);

ws.on('open', () => {
    console.log('WebSocket connected');
    
    // Send join message
    const joinMsg = {
        type: 'join',
        roomId: roomId,
        userId: userId,
        userName: userName
    };
    ws.send(JSON.stringify(joinMsg));
    console.log('Sent join message');
    
    // Wait a bit then send the test message
    setTimeout(() => {
        const msg = {
            type: 'message',
            content: message,
            attachments: []
        };
        ws.send(JSON.stringify(msg));
        console.log('Sent test message');
    }, 1000);
});

ws.on('message', (data) => {
    try {
        const msg = JSON.parse(data.toString());
        console.log(`Received: ${msg.type}`);
        
        if (msg.type === 'history') {
            console.log(`History loaded: ${msg.messages?.length || 0} messages`);
        } else if (msg.type === 'message') {
            messageReceived = true;
            const m = msg.message;
            console.log(`Message from ${m.senderName}: ${m.content?.substring(0, 50)}...`);
            
            if (m.fromOpenClaw) {
                openClawResponseReceived = true;
                console.log('✓ OpenClaw response received!');
                console.log(`Content length: ${m.content?.length || 0} chars`);
                console.log(`Is tool call: ${m.isToolCall || false}`);
                
                // Wait a bit to see if there are more responses, then exit
                setTimeout(() => {
                    ws.close();
                    if (openClawResponseReceived) {
                        console.log('\n✓ Test passed: OpenClaw responded');
                        process.exit(0);
                    } else {
                        console.log('\n✗ Test failed: No OpenClaw response');
                        process.exit(1);
                    }
                }, 3000);
            }
        } else if (msg.type === 'stream_start') {
            console.log('Stream started');
        } else if (msg.type === 'stream_delta') {
            process.stdout.write('.');
        } else if (msg.type === 'stream_end') {
            console.log('\nStream ended');
            openClawResponseReceived = true;
        }
    } catch (e) {
        console.error('Failed to parse message:', e.message);
    }
});

ws.on('error', (err) => {
    console.error('WebSocket error:', err.message);
    clearTimeout(timeoutId);
    process.exit(1);
});

ws.on('close', () => {
    console.log('WebSocket closed');
    clearTimeout(timeoutId);
    if (!messageReceived) {
        console.error('No messages received');
        process.exit(1);
    }
});
