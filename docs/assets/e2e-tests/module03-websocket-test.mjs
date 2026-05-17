/**
 * Module 03: WebSocket STOMP 实时通信测试 - 8 个测试用例
 * TC-WS-01 ~ TC-WS-08
 * 
 * 验证 WebSocket STOMP 实时通信全链路
 */
import WebSocket from 'ws';
import http from 'http';

const BASE = 'http://localhost:8080';
const WS_BASE = 'ws://localhost:8080';
const NULL = '\u0000';
const MAX_RETRIES = 3;

// ── 辅助函数 ──

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

function httpGet(url) { return httpRequest('GET', url); }

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
  return { command, headers, body, raw: raw.substring(0, 800) };
}

function createStompConnection(appSessionId, retries = MAX_RETRIES) {
  return new Promise(async (resolve, reject) => {
    for (let attempt = 1; attempt <= retries; attempt++) {
      try {
        const result = await _doConnect(appSessionId);
        return resolve(result);
      } catch (e) {
        console.log(`  [${ts()}] Connection attempt ${attempt}/${retries} failed: ${e.message}`);
        if (attempt === retries) return reject(e);
        await sleep(2000);
      }
    }
  });
}

function _doConnect(appSessionId) {
  return new Promise((resolve, reject) => {
    const serverId = String(Math.floor(Math.random() * 900) + 100);
    const transportSession = randomId(12);
    const url = `${WS_BASE}/ws/${serverId}/${transportSession}/websocket`;
    console.log(`  [${ts()}] CONNECT URL: ${url}`);
    const ws = new WebSocket(url);
    let resolved = false;
    const log = [];
    const timer = setTimeout(() => {
      if (!resolved) { resolved = true; reject(new Error('STOMP CONNECT timeout (15s)')); ws.close(); }
    }, 15000);

    ws.on('open', () => { log.push({ ts: ts(), event: 'ws_open' }); });

    ws.on('message', (msg) => {
      const raw = msg.toString();
      if (raw === 'o') {
        log.push({ ts: ts(), event: 'sockjs_open' });
        const connectFrame = buildStompFrame('CONNECT', {
          'accept-version': '1.2',
          'heart-beat': '10000,10000',
          'host': 'localhost',
          'X-Session-Id': appSessionId
        });
        ws.send(JSON.stringify([connectFrame]));
        return;
      }
      if (raw === 'h') { log.push({ ts: ts(), event: 'sockjs_heartbeat' }); return; }
      if (raw.startsWith('a[')) {
        const parsed = parseStompFrame(raw);
        log.push({ ts: ts(), event: 'stomp_frame', command: parsed.command, headers: parsed.headers });
        if (parsed.command === 'CONNECTED') {
          clearTimeout(timer);
          if (!resolved) { resolved = true; resolve({ ws, connectFrame: parsed, log, appSessionId }); }
        } else if (parsed.command === 'ERROR') {
          clearTimeout(timer);
          if (!resolved) { resolved = true; reject(new Error('STOMP ERROR: ' + parsed.body)); }
        }
      }
    });

    ws.on('error', (err) => {
      log.push({ ts: ts(), event: 'ws_error', msg: err.message });
      clearTimeout(timer);
      if (!resolved) { resolved = true; reject(err); }
    });

    ws.on('close', (code, reason) => {
      log.push({ ts: ts(), event: 'ws_close', code, reason: reason?.toString() || '' });
    });
  });
}

function stompSend(ws, destination, headers = {}, body = '') {
  const frame = buildStompFrame('SEND', { destination, ...headers, 'content-type': 'application/json' }, body);
  ws.send(JSON.stringify([frame]));
}

function stompSubscribe(ws, id, destination) {
  const frame = buildStompFrame('SUBSCRIBE', { id, destination });
  ws.send(JSON.stringify([frame]));
  console.log(`  [${ts()}] SUBSCRIBE id=${id} destination=${destination}`);
}

async function bindAndSubscribe(conn) {
  stompSubscribe(conn.ws, 'sub-0', '/user/queue/messages');
  await sleep(500);
  stompSend(conn.ws, '/app/bind-session', {}, JSON.stringify({ sessionId: conn.appSessionId }));
  await sleep(1000);
}

function waitForMessages(ws, { timeout = 30000, stopOnType = null, stopOnTypes = null, maxMessages = 200 } = {}) {
  return new Promise((resolve) => {
    const messages = [];
    const timer = setTimeout(() => { cleanup(); resolve({ messages, timedOut: true }); }, timeout);

    function onMsg(raw) {
      const data = raw.toString();
      if (data === 'h' || data === 'o') return;
      if (!data.startsWith('a[')) return;
      const parsed = parseStompFrame(data);
      if (parsed.command === 'MESSAGE') {
        let bodyJson = null;
        try { bodyJson = JSON.parse(parsed.body); } catch {}
        const entry = { ts: ts(), headers: parsed.headers, body: parsed.body, json: bodyJson };
        messages.push(entry);
        const msgType = bodyJson?.type || 'unknown';
        const preview = parsed.body.length > 150 ? parsed.body.substring(0, 150) + '...' : parsed.body;
        console.log(`  [${entry.ts}] MSG type=${msgType} | ${preview}`);
        if (stopOnType && msgType === stopOnType) { clearTimeout(timer); cleanup(); resolve({ messages, timedOut: false }); }
        if (stopOnTypes && stopOnTypes.includes(msgType)) { clearTimeout(timer); cleanup(); resolve({ messages, timedOut: false }); }
        if (messages.length >= maxMessages) { clearTimeout(timer); cleanup(); resolve({ messages, timedOut: false }); }
      }
    }

    function cleanup() { ws.removeListener('message', onMsg); }
    ws.on('message', onMsg);
  });
}

// ── REST API helpers ──

async function createSession(title = 'ws-test-session') {
  const res = await httpRequest('POST', `${BASE}/api/sessions`, { title });
  const json = JSON.parse(res.data);
  const id = json.id || json.sessionId;
  console.log(`  [${ts()}] Created session: ${id}`);
  return json;
}

async function deleteSession(id) {
  try {
    await httpRequest('DELETE', `${BASE}/api/sessions/${id}`);
    console.log(`  [${ts()}] Deleted session: ${id}`);
  } catch (e) {
    console.log(`  [${ts()}] Failed to delete session ${id}: ${e.message}`);
  }
}

// ══════════════════════════════════════════════════════════
// 测试执行
// ══════════════════════════════════════════════════════════

const results = [];
const createdSessionIds = [];

function reportTC(name, status, details, elapsed) {
  results.push({ name, status, details, elapsed });
  const icon = status === 'PASS' ? '✓' : status === 'PARTIAL' ? '△' : '✗';
  console.log(`\n  >>> ${icon} ${name}: ${status} (${elapsed}ms)\n`);
}

console.log('╔══════════════════════════════════════════════════════════════════════╗');
console.log('║  Module 03: WebSocket STOMP 实时通信测试                            ║');
console.log(`║  开始时间: ${ts()}                          ║`);
console.log('╚══════════════════════════════════════════════════════════════════════╝');

// ── TC-WS-01: SockJS 传输层验证 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-WS-01: SockJS 传输层验证   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const url = `${BASE}/ws/info`;
    console.log(`  请求: GET ${url}`);
    const info = await httpGet(url);
    const infoJson = JSON.parse(info.data);
    console.log(`  HTTP Status: ${info.status}`);
    console.log(`  响应: ${info.data}`);
    const pass = info.status === 200 && infoJson.websocket === true;
    reportTC('TC-WS-01', pass ? 'PASS' : 'FAIL', {
      request: { method: 'GET', url },
      response: { status: info.status, body: infoJson },
      validation: `websocket=${infoJson.websocket} (expected: true)`
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-WS-01', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

await sleep(2000);

// ── TC-WS-02: STOMP 1.2 握手 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-WS-02: STOMP 1.2 握手   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const sessionId = 'tc02-' + randomId(8);
    const conn = await createStompConnection(sessionId);
    const version = conn.connectFrame.headers['version'];
    const heartbeat = conn.connectFrame.headers['heart-beat'];
    console.log(`  CONNECTED: version=${version}, heart-beat=${heartbeat}`);
    conn.ws.close();
    await sleep(300);
    const pass = version === '1.2';
    reportTC('TC-WS-02', pass ? 'PASS' : 'FAIL', {
      connectedFrame: conn.connectFrame,
      version,
      heartbeat,
      validation: `version=${version} (expected 1.2)`
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-WS-02', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

await sleep(2000);

// ── TC-WS-03: 心跳 Ping/Pong ──
console.log('\n' + '='.repeat(70));
console.log(`TC-WS-03: 心跳 Ping/Pong   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const sessionId = 'tc03-' + randomId(8);
    const conn = await createStompConnection(sessionId);
    await bindAndSubscribe(conn);

    console.log(`  [${ts()}] SEND /app/ping`);
    stompSend(conn.ws, '/app/ping', {}, '');
    const { messages, timedOut } = await waitForMessages(conn.ws, { timeout: 10000, stopOnType: 'pong', maxMessages: 10 });
    const pongMsg = messages.find(m => m.json?.type === 'pong');

    conn.ws.close();
    await sleep(300);

    const pass = !!pongMsg;
    reportTC('TC-WS-03', pass ? 'PASS' : 'FAIL', {
      request: { destination: '/app/ping' },
      response: pongMsg ? pongMsg.json : null,
      allMessages: messages.map(m => ({ type: m.json?.type, ts: m.ts })),
      timedOut,
      validation: pass ? 'pong received' : 'no pong response'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-WS-03', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

await sleep(2000);

// ── TC-WS-04: 会话绑定 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-WS-04: 会话绑定   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('ws-test-tc04');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);

    const conn = await createStompConnection(sid);
    stompSubscribe(conn.ws, 'sub-0', '/user/queue/messages');
    await sleep(500);

    console.log(`  [${ts()}] SEND /app/bind-session {sessionId: "${sid}"}`);
    stompSend(conn.ws, '/app/bind-session', {}, JSON.stringify({ sessionId: sid }));

    const { messages, timedOut } = await waitForMessages(conn.ws, {
      timeout: 15000,
      stopOnTypes: ['session_restored', 'session_bound'],
      maxMessages: 20
    });
    const bindMsg = messages.find(m =>
      m.json?.type === 'session_restored' || m.json?.type === 'session_bound'
    );

    conn.ws.close();
    await sleep(300);

    const pass = !!bindMsg;
    reportTC('TC-WS-04', pass ? 'PASS' : 'FAIL', {
      sessionId: sid,
      request: { destination: '/app/bind-session', body: { sessionId: sid } },
      response: bindMsg ? bindMsg.json : null,
      allMessages: messages.map(m => ({ type: m.json?.type, ts: m.ts })),
      timedOut,
      validation: pass ? `${bindMsg.json.type} received` : 'no session_restored/session_bound'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-WS-04', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

await sleep(2000);

// ── TC-WS-05: 聊天消息完整流 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-WS-05: 聊天消息完整流   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('ws-test-tc05');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);

    const conn = await createStompConnection(sid);
    stompSubscribe(conn.ws, 'sub-0', '/user/queue/messages');
    await sleep(500);

    console.log(`  [${ts()}] SEND /app/bind-session`);
    stompSend(conn.ws, '/app/bind-session', {}, JSON.stringify({ sessionId: sid }));
    await waitForMessages(conn.ws, { timeout: 10000, stopOnTypes: ['session_restored', 'session_bound'], maxMessages: 20 });

    const chatBody = JSON.stringify({
      sessionId: sid,
      content: '1+1等于几？请只回答数字',
      type: 'user_message'
    });
    console.log(`  [${ts()}] SEND /app/chat body=${chatBody}`);
    stompSend(conn.ws, '/app/chat', {}, chatBody);

    console.log(`  [${ts()}] 等待消息序列（最多120s, 遇到 message_complete 停止）...`);
    const { messages, timedOut } = await waitForMessages(conn.ws, { timeout: 120000, stopOnType: 'message_complete' });

    const typeStats = {};
    let fullText = '';
    for (const m of messages) {
      const t = m.json?.type || 'unknown';
      typeStats[t] = (typeStats[t] || 0) + 1;
      if (t === 'stream_delta' && m.json?.delta) fullText += m.json.delta;
      if (t === 'stream_delta' && m.json?.text) fullText += m.json.text;
      if (t === 'thinking_delta' && m.json?.delta) fullText += m.json.delta;
    }

    console.log(`\n  消息统计: 共${messages.length}条, timedOut=${timedOut}`);
    console.log(`  类型分布: ${JSON.stringify(typeStats)}`);
    console.log(`  组合文本: ${fullText.substring(0, 300)}`);

    const hasThinkingOrStream = (typeStats['thinking_delta'] || 0) > 0 || (typeStats['stream_delta'] || 0) > 0;
    const hasComplete = (typeStats['message_complete'] || 0) > 0;

    conn.ws.close();
    await sleep(300);

    const pass = hasThinkingOrStream && hasComplete;
    reportTC('TC-WS-05', pass ? 'PASS' : (messages.length > 0 ? 'PARTIAL' : 'FAIL'), {
      sessionId: sid,
      request: { destination: '/app/chat', body: JSON.parse(chatBody) },
      response: {
        totalMessages: messages.length,
        timedOut,
        typeDistribution: typeStats,
        hasThinkingOrStream,
        hasComplete,
        assembledText: fullText.substring(0, 500)
      },
      validation: pass
        ? `thinking/stream_delta(${(typeStats['thinking_delta']||0)+(typeStats['stream_delta']||0)}) → message_complete`
        : 'incomplete sequence'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-WS-05', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

await sleep(2000);

// ── TC-WS-06: 权限模式切换 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-WS-06: 权限模式切换   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('ws-test-tc06');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);

    const conn = await createStompConnection(sid);
    stompSubscribe(conn.ws, 'sub-0', '/user/queue/messages');
    await sleep(500);
    stompSend(conn.ws, '/app/bind-session', {}, JSON.stringify({ sessionId: sid }));
    await sleep(1000);

    const permBody = JSON.stringify({ mode: 'SKIP_ALL_PROMPTS' });
    console.log(`  [${ts()}] SEND /app/permission-mode body=${permBody}`);
    stompSend(conn.ws, '/app/permission-mode', {}, permBody);

    const { messages, timedOut } = await waitForMessages(conn.ws, {
      timeout: 10000,
      stopOnType: 'permission_mode_changed',
      maxMessages: 10
    });
    const modeMsg = messages.find(m => m.json?.type === 'permission_mode_changed');

    conn.ws.close();
    await sleep(300);

    const pass = !!modeMsg;
    reportTC('TC-WS-06', pass ? 'PASS' : 'FAIL', {
      sessionId: sid,
      request: { destination: '/app/permission', body: JSON.parse(permBody) },
      response: modeMsg ? modeMsg.json : null,
      allMessages: messages.map(m => ({ type: m.json?.type, ts: m.ts })),
      timedOut,
      validation: pass ? `permission_mode_changed received` : 'no permission_mode_changed'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-WS-06', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

await sleep(2000);

// ── TC-WS-07: 中断功能 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-WS-07: 中断功能   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('ws-test-tc07');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);

    const conn = await createStompConnection(sid);
    stompSubscribe(conn.ws, 'sub-0', '/user/queue/messages');
    await sleep(500);
    stompSend(conn.ws, '/app/bind-session', {}, JSON.stringify({ sessionId: sid }));
    await sleep(1000);

    const intBody = JSON.stringify({ sessionId: sid });
    console.log(`  [${ts()}] SEND /app/interrupt body=${intBody}`);
    stompSend(conn.ws, '/app/interrupt', {}, intBody);

    const { messages, timedOut } = await waitForMessages(conn.ws, {
      timeout: 10000,
      stopOnType: 'interrupt_ack',
      maxMessages: 10
    });
    const ackMsg = messages.find(m => m.json?.type === 'interrupt_ack');

    conn.ws.close();
    await sleep(300);

    const pass = !!ackMsg;
    reportTC('TC-WS-07', pass ? 'PASS' : 'FAIL', {
      sessionId: sid,
      request: { destination: '/app/interrupt', body: JSON.parse(intBody) },
      response: ackMsg ? ackMsg.json : null,
      allMessages: messages.map(m => ({ type: m.json?.type, ts: m.ts })),
      timedOut,
      validation: pass ? 'interrupt_ack received' : 'no interrupt_ack'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-WS-07', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

await sleep(2000);

// ── TC-WS-08: 断连恢复 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-WS-08: 断连恢复   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('ws-test-tc08');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);

    // First connection
    console.log(`  [${ts()}] 第一次连接: sessionId=${sid}`);
    const conn1 = await createStompConnection(sid);
    await bindAndSubscribe(conn1);

    // Verify first connection with ping
    console.log(`  [${ts()}] 验证第一次连接: SEND /app/ping`);
    stompSend(conn1.ws, '/app/ping', {}, '');
    const r1 = await waitForMessages(conn1.ws, { timeout: 8000, stopOnType: 'pong', maxMessages: 10 });
    const pong1 = r1.messages.some(m => m.json?.type === 'pong');
    console.log(`  第一次 ping/pong: ${pong1 ? 'OK' : 'FAIL'}`);

    // Disconnect
    const disconnectTs = ts();
    console.log(`  [${disconnectTs}] 主动关闭连接 (code=1000)...`);
    conn1.ws.close(1000, 'test-disconnect');
    await sleep(3000);

    // Reconnect
    console.log(`  [${ts()}] 重新连接: sessionId=${sid}`);
    const conn2 = await createStompConnection(sid);
    await bindAndSubscribe(conn2);

    // Verify second connection with ping
    console.log(`  [${ts()}] 验证恢复连接: SEND /app/ping`);
    stompSend(conn2.ws, '/app/ping', {}, '');
    const r2 = await waitForMessages(conn2.ws, { timeout: 8000, stopOnType: 'pong', maxMessages: 10 });
    const pong2 = r2.messages.some(m => m.json?.type === 'pong');
    console.log(`  恢复后 ping/pong: ${pong2 ? 'OK' : 'FAIL'}`);

    conn2.ws.close();
    await sleep(300);

    const pass = pong1 && pong2;
    reportTC('TC-WS-08', pass ? 'PASS' : 'FAIL', {
      sessionId: sid,
      firstConnection: { pingPong: pong1, messages: r1.messages.map(m => m.json?.type) },
      disconnect: { ts: disconnectTs, code: 1000 },
      secondConnection: { pingPong: pong2, messages: r2.messages.map(m => m.json?.type) },
      validation: pass ? 'disconnect → reconnect → ping/pong all OK' : 'recovery failed'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-WS-08', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

// ══════════════════════════════════════════════════════════
// 清理临时会话
// ══════════════════════════════════════════════════════════
console.log('\n' + '-'.repeat(70));
console.log(`清理临时会话 [${ts()}]`);
console.log('-'.repeat(70));
for (const sid of createdSessionIds) {
  await deleteSession(sid);
}

// ══════════════════════════════════════════════════════════
// 汇总报告
// ══════════════════════════════════════════════════════════
console.log('\n' + '═'.repeat(70));
console.log(`Module 03: WebSocket STOMP 实时通信测试报告   [${ts()}]`);
console.log('═'.repeat(70));

let passCount = 0;
for (const r of results) {
  const icon = r.status === 'PASS' ? '✓' : r.status === 'PARTIAL' ? '△' : '✗';
  if (r.status === 'PASS') passCount++;
  console.log(`  ${icon} ${r.status.padEnd(7)} ${r.name} (${r.elapsed}ms)`);
}
const total = results.length;
const failCount = total - passCount;
console.log(`\n  总计: ${total} 用例 | PASS: ${passCount} | FAIL: ${failCount}`);
console.log('═'.repeat(70));

// JSON 详细输出
console.log('\n' + '-'.repeat(70));
console.log('详细测试结果 (JSON):');
console.log('-'.repeat(70));
for (const r of results) {
  console.log(`\n【${r.name}】 ${r.status} (${r.elapsed}ms)`);
  console.log(JSON.stringify(r.details, null, 2));
}

console.log(`\n结束时间: ${ts()}`);
process.exit(passCount === total ? 0 : 1);
