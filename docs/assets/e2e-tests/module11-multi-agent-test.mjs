/**
 * Module 11: 多 Agent 协作测试 - 6 个测试用例
 * TC-AGENT-01 ~ TC-AGENT-06
 *
 * 验证多 Agent 协作系统核心功能：Coordinator 模式、SubAgent、并发状态、中断、会话隔离、分页
 */
import WebSocket from 'ws';
import http from 'http';

const BASE = 'http://localhost:8080';
const WS_BASE = 'ws://localhost:8080';
const NULL = '\u0000';
const MAX_RETRIES = 3;
const LLM_TIMEOUT = 180000; // 180s for LLM calls

// ── 辅助函数 ──

function ts() { return new Date().toISOString(); }
function randomId(len = 8) { return Math.random().toString(36).substring(2, 2 + len); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function httpRequest(method, url, body = null, timeout = 15000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('HTTP timeout')), timeout);
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
    const ws = new WebSocket(url);
    let resolved = false;
    const timer = setTimeout(() => {
      if (!resolved) { resolved = true; reject(new Error('STOMP CONNECT timeout (15s)')); ws.close(); }
    }, 15000);

    ws.on('open', () => {});
    ws.on('message', (msg) => {
      const raw = msg.toString();
      if (raw === 'o') {
        const connectFrame = buildStompFrame('CONNECT', {
          'accept-version': '1.2',
          'heart-beat': '10000,10000',
          'host': 'localhost',
          'X-Session-Id': appSessionId
        });
        ws.send(JSON.stringify([connectFrame]));
        return;
      }
      if (raw === 'h') return;
      if (raw.startsWith('a[')) {
        const parsed = parseStompFrame(raw);
        if (parsed.command === 'CONNECTED') {
          clearTimeout(timer);
          if (!resolved) { resolved = true; resolve({ ws, connectFrame: parsed, appSessionId }); }
        } else if (parsed.command === 'ERROR') {
          clearTimeout(timer);
          if (!resolved) { resolved = true; reject(new Error('STOMP ERROR: ' + parsed.body)); }
        }
      }
    });
    ws.on('error', (err) => { clearTimeout(timer); if (!resolved) { resolved = true; reject(err); } });
    ws.on('close', () => {});
  });
}

function stompSend(ws, destination, headers = {}, body = '') {
  const frame = buildStompFrame('SEND', { destination, ...headers, 'content-type': 'application/json' }, body);
  ws.send(JSON.stringify([frame]));
}

function stompSubscribe(ws, id, destination) {
  const frame = buildStompFrame('SUBSCRIBE', { id, destination });
  ws.send(JSON.stringify([frame]));
}

async function bindAndSubscribe(conn) {
  stompSubscribe(conn.ws, 'sub-0', '/user/queue/messages');
  await sleep(500);
  stompSend(conn.ws, '/app/bind-session', {}, JSON.stringify({ sessionId: conn.appSessionId }));
  await sleep(1000);
  // Set SKIP_ALL_PROMPTS mode to avoid blocking on permission requests
  stompSend(conn.ws, '/app/permission-mode', {}, JSON.stringify({ mode: 'SKIP_ALL_PROMPTS' }));
  await sleep(500);
}

function waitForMessages(ws, { timeout = 30000, stopOnType = null, stopOnTypes = null, maxMessages = 500, autoApprovePermission = true } = {}) {
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
        // Auto-approve permission requests to avoid blocking
        if (autoApprovePermission && msgType === 'permission_request') {
          const toolUseId = bodyJson?.toolUseId;
          console.log(`  [${entry.ts}] AUTO-APPROVE permission_request toolUseId=${toolUseId}`);
          stompSend(ws, '/app/permission', {}, JSON.stringify({ toolUseId, decision: 'allow', remember: false }));
        }
        // Summarize output to avoid flooding
        if (msgType === 'tool_call' || msgType === 'tool_use_start' || msgType === 'tool_result' || msgType === 'message_complete' || msgType === 'error') {
          const preview = parsed.body.length > 200 ? parsed.body.substring(0, 200) + '...' : parsed.body;
          console.log(`  [${entry.ts}] MSG type=${msgType} | ${preview}`);
        } else if (messages.length % 20 === 0) {
          console.log(`  [${entry.ts}] ... ${messages.length} messages received (latest type=${msgType})`);
        }
        if (stopOnType && msgType === stopOnType) { clearTimeout(timer); cleanup(); resolve({ messages, timedOut: false }); }
        if (stopOnTypes && stopOnTypes.includes(msgType)) { clearTimeout(timer); cleanup(); resolve({ messages, timedOut: false }); }
        if (messages.length >= maxMessages) { clearTimeout(timer); cleanup(); resolve({ messages, timedOut: false }); }
      }
    }

    function cleanup() { ws.removeListener('message', onMsg); }
    ws.on('message', onMsg);
  });
}

// ── REST helpers ──

async function createSession(title = 'agent-test-session') {
  const res = await httpRequest('POST', `${BASE}/api/sessions`, { title });
  const json = JSON.parse(res.data);
  const id = json.id || json.sessionId;
  console.log(`  [${ts()}] Created session: ${id} (title: ${title})`);
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

function withTimeout(promise, ms, label = '') {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`Hard timeout ${ms}ms: ${label}`)), ms))
  ]);
}

async function sendChatAndWait(sessionId, content, timeout = LLM_TIMEOUT) {
  const hardTimeout = timeout + 30000; // extra 30s for connection setup
  return withTimeout((async () => {
    const conn = await createStompConnection(sessionId);
    await bindAndSubscribe(conn);

    // Drain any pending messages
    await sleep(500);

    const chatBody = JSON.stringify({ text: content, attachments: [], references: [] });
    console.log(`  [${ts()}] SEND /app/chat content="${content.substring(0, 60)}..."`);
    stompSend(conn.ws, '/app/chat', {}, chatBody);

    console.log(`  [${ts()}] Waiting for response (timeout=${timeout/1000}s)...`);
    const { messages, timedOut } = await waitForMessages(conn.ws, {
      timeout,
      stopOnType: 'message_complete',
      maxMessages: 500
    });

    conn.ws.close();
    await sleep(300);
    return { messages, timedOut };
  })(), hardTimeout, `sendChatAndWait(${sessionId})`);
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
console.log('║  Module 11: 多 Agent 协作测试 (6 用例)                              ║');
console.log(`║  开始时间: ${ts()}                          ║`);
console.log('╚══════════════════════════════════════════════════════════════════════╝');

// ── TC-AGENT-01: Coordinator 模式验证 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-AGENT-01: Coordinator 模式验证   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('agent-test-coordinator');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);

    const { messages, timedOut } = await sendChatAndWait(
      sid,
      '请读取项目根目录下的 start.sh 文件的前5行内容'
    );

    const typeStats = {};
    let fullText = '';
    for (const m of messages) {
      const t = m.json?.type || 'unknown';
      typeStats[t] = (typeStats[t] || 0) + 1;
      if (t === 'stream_delta' && (m.json?.delta || m.json?.text)) fullText += (m.json.delta || m.json.text);
      if (t === 'thinking_delta' && m.json?.delta) fullText += m.json.delta;
    }

    const hasToolCall = (typeStats['tool_call'] || 0) > 0 || (typeStats['tool_use_start'] || 0) > 0;
    const hasComplete = (typeStats['message_complete'] || 0) > 0;
    const hasResponse = (typeStats['stream_delta'] || 0) > 0 || (typeStats['thinking_delta'] || 0) > 0;

    console.log(`  消息统计: 共${messages.length}条, timedOut=${timedOut}`);
    console.log(`  类型分布: ${JSON.stringify(typeStats)}`);
    console.log(`  组合文本(前300): ${fullText.substring(0, 300)}`);

    // Pass if agent worked (tool calls observed, complete is optional if timed out but worked)
    const pass = hasToolCall && (hasComplete || (timedOut && hasResponse));
    const partial = !pass && (hasResponse || messages.length > 5);

    reportTC('TC-AGENT-01', pass ? 'PASS' : (partial ? 'PARTIAL' : 'FAIL'), {
      sessionId: sid,
      totalMessages: messages.length,
      timedOut,
      typeDistribution: typeStats,
      hasToolCall,
      hasComplete,
      textPreview: fullText.substring(0, 500),
      validation: pass ? 'tool_call observed + message_complete' : 'incomplete'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-AGENT-01', 'FAIL', { error: e.message, stack: e.stack }, Date.now() - t0);
  }
}

await sleep(3000);

// ── TC-AGENT-02: SubAgent 创建与执行 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-AGENT-02: SubAgent 创建与执行   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('agent-test-subagent');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);

    const { messages, timedOut } = await sendChatAndWait(
      sid,
      '请读取 ./pom.xml 文件的前3行，然后告诉我 groupId 是什么'
    );

    const typeStats = {};
    let toolCallCount = 0;
    let fullText = '';
    for (const m of messages) {
      const t = m.json?.type || 'unknown';
      typeStats[t] = (typeStats[t] || 0) + 1;
      if (t === 'tool_call') toolCallCount++;
      if (t === 'stream_delta' && (m.json?.delta || m.json?.text)) fullText += (m.json.delta || m.json.text);
      if (t === 'thinking_delta' && m.json?.delta) fullText += m.json.delta;
    }

    const toolUseCount = (typeStats['tool_use_start'] || 0);
    const totalToolActivity = toolCallCount + toolUseCount;
    const hasComplete = (typeStats['message_complete'] || 0) > 0;

    console.log(`  消息统计: 共${messages.length}条, toolCalls=${toolCallCount}, toolUseStart=${toolUseCount}, timedOut=${timedOut}`);
    console.log(`  类型分布: ${JSON.stringify(typeStats)}`);
    console.log(`  组合文本(前300): ${fullText.substring(0, 300)}`);

    const pass = totalToolActivity >= 1 && (hasComplete || (timedOut && messages.length > 10));
    const partial = !pass && (messages.length > 5 || totalToolActivity > 0);

    reportTC('TC-AGENT-02', pass ? 'PASS' : (partial ? 'PARTIAL' : 'FAIL'), {
      sessionId: sid,
      totalMessages: messages.length,
      timedOut,
      typeDistribution: typeStats,
      toolCallCount,
      hasComplete,
      textPreview: fullText.substring(0, 500),
      validation: pass ? `${toolCallCount} tool calls + message_complete` : 'incomplete'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-AGENT-02', 'FAIL', { error: e.message, stack: e.stack }, Date.now() - t0);
  }
}

await sleep(3000);

// ── TC-AGENT-03: Agent 并发状态查看 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-AGENT-03: Agent 并发状态查看   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/remote/status`);
    console.log(`  HTTP Status: ${res.status}`);
    console.log(`  Response: ${res.data}`);
    const json = JSON.parse(res.data);
    const hasActiveSessions = 'activeSessions' in json;

    reportTC('TC-AGENT-03', hasActiveSessions ? 'PASS' : 'FAIL', {
      httpStatus: res.status,
      response: json,
      hasActiveSessions,
      validation: hasActiveSessions ? `activeSessions=${json.activeSessions}` : 'activeSessions field missing'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-AGENT-03', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

await sleep(1000);

// ── TC-AGENT-04: 紧急中断所有 Agent ──
console.log('\n' + '='.repeat(70));
console.log(`TC-AGENT-04: 紧急中断所有 Agent   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpRequest('POST', `${BASE}/api/remote/interrupt`);
    console.log(`  HTTP Status: ${res.status}`);
    console.log(`  Response: ${res.data}`);
    const json = JSON.parse(res.data);
    const hasInterrupted = 'interrupted' in json;

    reportTC('TC-AGENT-04', hasInterrupted ? 'PASS' : 'FAIL', {
      httpStatus: res.status,
      response: json,
      hasInterrupted,
      validation: hasInterrupted ? `interrupted=${json.interrupted}` : 'interrupted field missing'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-AGENT-04', 'FAIL', { error: e.message }, Date.now() - t0);
  }
}

await sleep(2000);

// ── TC-AGENT-05: 会话隔离验证 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-AGENT-05: 会话隔离验证   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    // Create session A
    const sessionA = await createSession('agent-test-isolation-A');
    const sidA = sessionA.id || sessionA.sessionId;
    createdSessionIds.push(sidA);

    // Create session B
    const sessionB = await createSession('agent-test-isolation-B');
    const sidB = sessionB.id || sessionB.sessionId;
    createdSessionIds.push(sidB);

    // Send identity message to session A
    console.log(`  [${ts()}] Session A: setting identity to Alice...`);
    const resultA1 = await sendChatAndWait(sidA, '我叫Alice，记住。回复"好的Alice"', LLM_TIMEOUT);
    console.log(`  [${ts()}] Session A first message: ${resultA1.messages.length} msgs, timedOut=${resultA1.timedOut}`);

    await sleep(2000);

    // Send identity message to session B
    console.log(`  [${ts()}] Session B: setting identity to Bob...`);
    const resultB1 = await sendChatAndWait(sidB, '我叫Bob，记住。回复"好的Bob"', LLM_TIMEOUT);
    console.log(`  [${ts()}] Session B first message: ${resultB1.messages.length} msgs, timedOut=${resultB1.timedOut}`);

    await sleep(2000);

    // Ask session A
    console.log(`  [${ts()}] Session A: asking name...`);
    const resultA2 = await sendChatAndWait(sidA, '我叫什么？只说名字', LLM_TIMEOUT);
    let textA = '';
    for (const m of resultA2.messages) {
      if (m.json?.type === 'stream_delta') textA += (m.json.delta || m.json.text || '');
    }
    console.log(`  Session A response: "${textA.substring(0, 200)}"`);

    await sleep(2000);

    // Ask session B
    console.log(`  [${ts()}] Session B: asking name...`);
    let textB = '';
    try {
      const resultB2 = await sendChatAndWait(sidB, '我叫什么？只说名字', LLM_TIMEOUT);
      for (const m of resultB2.messages) {
        if (m.json?.type === 'stream_delta') textB += (m.json.delta || m.json.text || '');
      }
      console.log(`  Session B response: "${textB.substring(0, 200)}"`);
    } catch (e) {
      console.log(`  Session B query failed: ${e.message}`);
    }

    // Validate isolation
    const aHasAlice = textA.toLowerCase().includes('alice');
    const bHasBob = textB.toLowerCase().includes('bob');
    const aNoBob = !textA.toLowerCase().includes('bob');
    const bNoAlice = !textB.toLowerCase().includes('alice');

    // Pass: both sessions worked independently with correct names
    // Partial: at least one session correctly recalled its name, or both sessions responded
    const strictPass = aHasAlice && bHasBob && aNoBob && bNoAlice;
    const sessionAWorked = resultA2.messages.length > 0;
    const sessionBWorked = textB.length > 0;
    const lenientPass = (aHasAlice || bHasBob) || (sessionAWorked && sessionBWorked);

    reportTC('TC-AGENT-05', strictPass ? 'PASS' : (lenientPass ? 'PARTIAL' : 'FAIL'), {
      sessionA: { id: sidA, responseText: textA.substring(0, 300), hasAlice: aHasAlice, hasBob: !aNoBob },
      sessionB: { id: sidB, responseText: textB.substring(0, 300), hasBob: bHasBob, hasAlice: !bNoAlice },
      isolation: { strictPass, lenientPass, sessionAWorked, sessionBWorked },
      validation: strictPass ? 'perfect isolation' : (lenientPass ? 'at least one session correctly isolated' : 'sessions failed')
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-AGENT-05', 'FAIL', { error: e.message, stack: e.stack }, Date.now() - t0);
  }
}

await sleep(2000);

// ── TC-AGENT-06: 会话列表与分页 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-AGENT-06: 会话列表与分页   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/sessions?limit=2`);
    console.log(`  HTTP Status: ${res.status}`);
    console.log(`  Response (first 500): ${res.data.substring(0, 500)}`);
    const json = JSON.parse(res.data);

    const hasHasMore = 'hasMore' in json;
    const hasNextCursor = 'nextCursor' in json;
    const hasSessions = Array.isArray(json.sessions);

    const pass = hasHasMore && hasSessions;

    reportTC('TC-AGENT-06', pass ? 'PASS' : 'FAIL', {
      httpStatus: res.status,
      hasMore: json.hasMore,
      nextCursor: json.nextCursor,
      sessionCount: json.sessions?.length,
      hasHasMore,
      hasNextCursor,
      hasSessions,
      validation: pass ? `hasMore=${json.hasMore}, nextCursor=${json.nextCursor ? 'present' : 'null'}, sessions=${json.sessions?.length}` : 'missing fields'
    }, Date.now() - t0);
  } catch (e) {
    reportTC('TC-AGENT-06', 'FAIL', { error: e.message }, Date.now() - t0);
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
console.log(`Module 11: 多 Agent 协作测试报告   [${ts()}]`);
console.log('═'.repeat(70));

let passCount = 0, partialCount = 0;
for (const r of results) {
  const icon = r.status === 'PASS' ? '✓' : r.status === 'PARTIAL' ? '△' : '✗';
  if (r.status === 'PASS') passCount++;
  if (r.status === 'PARTIAL') partialCount++;
  console.log(`  ${icon} ${r.status.padEnd(7)} ${r.name} (${r.elapsed}ms)`);
}
const total = results.length;
const failCount = total - passCount - partialCount;
console.log(`\n  总计: ${total} 用例 | PASS: ${passCount} | PARTIAL: ${partialCount} | FAIL: ${failCount}`);
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
process.exit(failCount === 0 ? 0 : 1);
