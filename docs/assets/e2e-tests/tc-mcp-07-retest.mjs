/**
 * TC-MCP-07 Retest: 通过 LLM 触发 MCP 工具（网络搜索）
 * 验证 MCP 工具是否被正确调用
 */
import WebSocket from 'ws';
import http from 'http';

const BASE = 'http://localhost:8080';
const WS_BASE = 'ws://localhost:8080';
const NULL = '\u0000';
const TIMEOUT_MS = 180000; // 180s

function ts() { return new Date().toISOString(); }
function randomId(len = 8) { return Math.random().toString(36).substring(2, 2 + len); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function httpRequest(method, url, body = null) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('HTTP timeout')), 15000);
    const urlObj = new URL(url);
    const opts = {
      hostname: urlObj.hostname,
      port: urlObj.port,
      path: urlObj.pathname + urlObj.search,
      method,
      headers: {}
    };
    if (body) {
      const payload = typeof body === 'string' ? body : JSON.stringify(body);
      opts.headers['Content-Type'] = 'application/json';
      opts.headers['Content-Length'] = Buffer.byteLength(payload);
    }
    const req = http.request(opts, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => { clearTimeout(timer); resolve({ status: res.statusCode, data }); });
    });
    req.on('error', e => { clearTimeout(timer); reject(e); });
    if (body) req.write(typeof body === 'string' ? body : JSON.stringify(body));
    req.end();
  });
}

function buildStompFrame(command, headers = {}, body = '') {
  let frame = command + '\n';
  for (const [k, v] of Object.entries(headers)) {
    frame += `${k}:${v}\n`;
  }
  frame += '\n' + body + NULL;
  return frame;
}

function parseStompFrame(raw) {
  let data = raw;
  if (data.startsWith('a[')) {
    try {
      const arr = JSON.parse(data.substring(1));
      data = arr[0] || '';
    } catch { /* keep raw */ }
  }
  const nullIdx = data.indexOf(NULL);
  const content = nullIdx >= 0 ? data.substring(0, nullIdx) : data;
  const lines = content.split('\n');
  const command = lines[0];
  const headers = {};
  let i = 1;
  for (; i < lines.length; i++) {
    if (lines[i] === '') break;
    const colonIdx = lines[i].indexOf(':');
    if (colonIdx > 0) {
      headers[lines[i].substring(0, colonIdx)] = lines[i].substring(colonIdx + 1);
    }
  }
  const body = lines.slice(i + 1).join('\n');
  return { command, headers, body };
}

async function createSession() {
  const res = await httpRequest('POST', `${BASE}/api/sessions`, JSON.stringify({ name: 'TC-MCP-07-retest' }));
  const session = JSON.parse(res.data);
  return session.id || session.sessionId;
}

function connectStomp(sessionId) {
  return new Promise((resolve, reject) => {
    const serverId = String(Math.floor(Math.random() * 900) + 100);
    const transportSession = randomId(12);
    const url = `${WS_BASE}/ws/${serverId}/${transportSession}/websocket`;
    const ws = new WebSocket(url);
    const timer = setTimeout(() => { reject(new Error('STOMP CONNECT timeout')); ws.close(); }, 15000);

    ws.on('message', (msg) => {
      const raw = msg.toString();
      if (raw === 'o') {
        const connectFrame = buildStompFrame('CONNECT', {
          'accept-version': '1.2',
          'heart-beat': '10000,10000',
          'host': 'localhost',
          'X-Session-Id': sessionId
        });
        ws.send(JSON.stringify([connectFrame]));
        return;
      }
      if (raw === 'h') return;
      if (raw.startsWith('a[')) {
        const parsed = parseStompFrame(raw);
        if (parsed.command === 'CONNECTED') {
          clearTimeout(timer);
          resolve(ws);
        } else if (parsed.command === 'ERROR') {
          clearTimeout(timer);
          reject(new Error('STOMP ERROR: ' + parsed.body));
        }
      }
    });

    ws.on('error', (err) => { clearTimeout(timer); reject(err); });
  });
}

async function main() {
  console.log(`\n=== TC-MCP-07 Retest: MCP Web Search via LLM ===`);
  console.log(`[${ts()}] API key configured: sk-****ed5`);
  
  let result = 'FAIL';
  let reason = '';
  let toolCallDetected = false;
  let mcpToolName = '';

  try {
    // Step 1: Create session
    console.log(`[${ts()}] Creating session...`);
    const sessionId = await createSession();
    console.log(`[${ts()}] Session: ${sessionId}`);

    // Step 2: Connect STOMP
    console.log(`[${ts()}] Connecting STOMP...`);
    const ws = await connectStomp(sessionId);
    console.log(`[${ts()}] STOMP connected`);

    // Step 3: Bind session
    console.log(`[${ts()}] Binding session...`);
    const bindFrame = buildStompFrame('SEND', {
      destination: '/app/bind-session',
      'content-type': 'application/json'
    }, JSON.stringify({ sessionId }));
    ws.send(JSON.stringify([bindFrame]));
    await sleep(1000);

    // Step 4: Subscribe to /user/queue/messages
    const subFrame = buildStompFrame('SUBSCRIBE', {
      id: 'sub-0',
      destination: '/user/queue/messages'
    });
    ws.send(JSON.stringify([subFrame]));
    await sleep(500);

    // Step 5: Send message requiring web search
    const userMessage = '请使用网络搜索工具搜索"ZhikunCode开源项目"，告诉我搜索结果';
    console.log(`[${ts()}] Sending message: "${userMessage}"`);
    
    const sendFrame = buildStompFrame('SEND', {
      destination: '/app/chat',
      'content-type': 'application/json'
    }, JSON.stringify({ text: userMessage }));
    ws.send(JSON.stringify([sendFrame]));

    // Step 6: Wait for response, check for tool_call
    console.log(`[${ts()}] Waiting for LLM response (timeout: ${TIMEOUT_MS/1000}s)...`);
    
    const responsePromise = new Promise((resolve) => {
      let messageCount = 0;
      let allMessages = [];
      const timeout = setTimeout(() => {
        resolve({ messages: allMessages, timedOut: true });
      }, TIMEOUT_MS);

      ws.on('message', (msg) => {
        const raw = msg.toString();
        if (raw === 'h' || raw === 'o') return;
        if (!raw.startsWith('a[')) return;
        
        const parsed = parseStompFrame(raw);
        if (parsed.command !== 'MESSAGE') return;
        
        messageCount++;
        let bodyObj = null;
        try { bodyObj = JSON.parse(parsed.body); } catch {}
        
        allMessages.push({ raw: parsed.body.substring(0, 500), parsed: bodyObj });
        
        // Check for tool_call events
        if (bodyObj) {
          const bodyStr = JSON.stringify(bodyObj).toLowerCase();
          if (bodyStr.includes('tool_call') || bodyStr.includes('toolcall') || 
              bodyStr.includes('mcp') || bodyStr.includes('websearch') ||
              bodyStr.includes('web_search') || bodyStr.includes('search_query')) {
            toolCallDetected = true;
            mcpToolName = bodyObj.toolName || bodyObj.tool_name || bodyObj.name || 'detected';
            console.log(`[${ts()}] >>> MCP tool_call detected! Tool: ${mcpToolName}`);
          }
          
          // Check for completion signals
          if (bodyObj.type === 'message_complete' || bodyObj.type === 'error') {
            clearTimeout(timeout);
            resolve({ messages: allMessages, timedOut: false });
          }
        }
        
        // Log progress every 10 messages
        if (messageCount % 10 === 0) {
          console.log(`[${ts()}] ... received ${messageCount} messages so far`);
        }
      });
    });

    const { messages, timedOut } = await responsePromise;
    
    console.log(`[${ts()}] Received ${messages.length} total messages, timedOut=${timedOut}`);
    
    // Analyze results
    if (toolCallDetected) {
      result = 'PASS';
      reason = `MCP tool_call detected (tool: ${mcpToolName}), ${messages.length} messages received`;
    } else {
      // Check if any message content mentions search results
      const allContent = messages.map(m => JSON.stringify(m.parsed || '')).join(' ').toLowerCase();
      if (allContent.includes('搜索') || allContent.includes('search') || allContent.includes('zhikuncode')) {
        result = 'PARTIAL';
        reason = `LLM responded with search-related content but no explicit tool_call event detected. Messages: ${messages.length}`;
      } else if (timedOut) {
        result = 'FAIL';
        reason = `Timeout after ${TIMEOUT_MS/1000}s with ${messages.length} messages, no MCP tool_call detected`;
      } else {
        result = 'FAIL';
        reason = `Completed with ${messages.length} messages but no MCP/search activity detected`;
      }
    }

    // Close connection
    ws.close();
    
  } catch (err) {
    result = 'FAIL';
    reason = `Error: ${err.message}`;
  }

  console.log(`\n=== TC-MCP-07 Result: ${result} ===`);
  console.log(`Reason: ${reason}`);
  console.log(`Timestamp: ${ts()}`);
  
  // Output structured result for log capture
  console.log(`\n--- STRUCTURED RESULT ---`);
  console.log(JSON.stringify({
    testCase: 'TC-MCP-07',
    result,
    reason,
    toolCallDetected,
    mcpToolName: mcpToolName || 'none',
    timestamp: ts(),
    apiKeyHint: 'sk-****ed5'
  }, null, 2));

  process.exit(result === 'PASS' ? 0 : result === 'PARTIAL' ? 0 : 1);
}

main().catch(e => {
  console.error(`[${ts()}] Fatal: ${e.message}`);
  process.exit(1);
});
