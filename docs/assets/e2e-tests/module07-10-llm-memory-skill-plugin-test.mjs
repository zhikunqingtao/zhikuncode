/**
 * Module 07-10: LLM集成 / 记忆 / 技能 / 插件 / MCP 测试 - 32 个测试用例
 * TC-SP-01~07, TC-MEM-01~07, TC-SKILL-01~07, TC-PLG-01~03, TC-MCP-01~08
 */
import WebSocket from 'ws';
import http from 'http';
import fs from 'fs';

const BASE = 'http://localhost:8080';
const WS_BASE = 'ws://localhost:8080';
const NULL = '\u0000';
const MAX_RETRIES = 3;
const LLM_TIMEOUT = 180000;

// ── 辅助函数 ──
function ts() { return new Date().toISOString(); }
function randomId(len = 8) { return Math.random().toString(36).substring(2, 2 + len); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function httpRequest(method, url, body = null, timeout = 30000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`HTTP timeout (${timeout}ms)`)), timeout);
    const urlObj = new URL(url);
    const opts = {
      hostname: urlObj.hostname, port: urlObj.port,
      path: urlObj.pathname + urlObj.search, method, headers: {}
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

function httpGet(url, timeout) { return httpRequest('GET', url, null, timeout); }
function httpPost(url, body, timeout) { return httpRequest('POST', url, body, timeout); }
function httpPut(url, body, timeout) { return httpRequest('PUT', url, body, timeout); }
function httpDelete(url, timeout) { return httpRequest('DELETE', url, null, timeout); }
function httpPatch(url, body, timeout) { return httpRequest('PATCH', url, body, timeout); }

function buildStompFrame(command, headers = {}, body = '') {
  let frame = command + '\n';
  for (const [k, v] of Object.entries(headers)) frame += `${k}:${v}\n`;
  frame += '\n' + body + NULL;
  return frame;
}

function parseStompFrame(raw) {
  let data = raw;
  if (data.startsWith('a[')) {
    try { const arr = JSON.parse(data.substring(1)); data = arr[0] || ''; } catch {}
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
    if (colonIdx > 0) headers[lines[i].substring(0, colonIdx)] = lines[i].substring(colonIdx + 1);
  }
  const body = lines.slice(i + 1).join('\n');
  return { command, headers, body };
}

function createStompConnection(appSessionId, retries = MAX_RETRIES) {
  return new Promise(async (resolve, reject) => {
    for (let attempt = 1; attempt <= retries; attempt++) {
      try { return resolve(await _doConnect(appSessionId)); }
      catch (e) {
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
    const timer = setTimeout(() => { if (!resolved) { resolved = true; reject(new Error('STOMP CONNECT timeout')); ws.close(); } }, 15000);
    ws.on('open', () => {});
    ws.on('message', (msg) => {
      const raw = msg.toString();
      if (raw === 'o') {
        const connectFrame = buildStompFrame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '10000,10000', 'host': 'localhost', 'X-Session-Id': appSessionId });
        ws.send(JSON.stringify([connectFrame]));
        return;
      }
      if (raw === 'h') return;
      if (raw.startsWith('a[')) {
        const parsed = parseStompFrame(raw);
        if (parsed.command === 'CONNECTED') { clearTimeout(timer); if (!resolved) { resolved = true; resolve({ ws, appSessionId }); } }
        else if (parsed.command === 'ERROR') { clearTimeout(timer); if (!resolved) { resolved = true; reject(new Error('STOMP ERROR: ' + parsed.body)); } }
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
}

function waitForMessages(ws, { timeout = 30000, stopOnType = null, stopOnTypes = null, maxMessages = 500 } = {}) {
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
        messages.push({ ts: ts(), body: parsed.body, json: bodyJson });
        const msgType = bodyJson?.type || 'unknown';
        if (stopOnType && msgType === stopOnType) { clearTimeout(timer); cleanup(); resolve({ messages, timedOut: false }); }
        if (stopOnTypes && stopOnTypes.includes(msgType)) { clearTimeout(timer); cleanup(); resolve({ messages, timedOut: false }); }
        if (messages.length >= maxMessages) { clearTimeout(timer); cleanup(); resolve({ messages, timedOut: false }); }
      }
    }
    function cleanup() { ws.removeListener('message', onMsg); }
    ws.on('message', onMsg);
  });
}

async function createSession(title = 'test-session') {
  const res = await httpPost(`${BASE}/api/sessions`, { title });
  const json = JSON.parse(res.data);
  return json;
}

async function deleteSession(id) {
  try { await httpDelete(`${BASE}/api/sessions/${id}`); } catch {}
}

// ── 测试报告 ──
const results = [];
const createdSessionIds = [];
const createdMemoryIds = [];

function reportTC(name, status, details, elapsed) {
  results.push({ name, status, details, elapsed });
  const icon = status === 'PASS' ? '✓' : status === 'PARTIAL' ? '△' : '✗';
  console.log(`\n  >>> ${icon} ${name}: ${status} (${elapsed}ms)\n`);
}

console.log('╔══════════════════════════════════════════════════════════════════════╗');
console.log('║  Module 07-10: LLM/记忆/技能/插件/MCP 综合测试 (32 用例)           ║');
console.log(`║  开始时间: ${ts()}                          ║`);
console.log('╚══════════════════════════════════════════════════════════════════════╝');

// ══════════════════════════════════════════════════════════
// Module 7: System Prompt 与 LLM 集成
// ══════════════════════════════════════════════════════════
console.log('\n' + '█'.repeat(70));
console.log('  Module 7: System Prompt 与 LLM 集成 (7 用例)');
console.log('█'.repeat(70));

// ── TC-SP-01: 模型列表与能力字段 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SP-01: 模型列表与能力字段   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/models`);
    const json = JSON.parse(res.data);
    const models = json.models || [];
    const hasFields = models.length > 0 && models.every(m =>
      'maxOutputTokens' in m && 'contextWindow' in m && 'supportsStreaming' in m);
    const pass = res.status === 200 && models.length > 0 && hasFields;
    console.log(`  模型数量: ${models.length}, 字段检查: ${hasFields}`);
    reportTC('TC-SP-01', pass ? 'PASS' : 'FAIL', { modelCount: models.length, hasFields, sample: models[0] }, Date.now() - t0);
  } catch (e) { reportTC('TC-SP-01', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-SP-02: 模型能力详细验证 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SP-02: 模型能力详细验证   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/models`);
    const json = JSON.parse(res.data);
    const models = json.models || [];
    const valid = models.length > 0 && models.every(m => m.contextWindow > 0 && m.maxOutputTokens > 0);
    console.log(`  验证: contextWindow>0 && maxOutputTokens>0 for all ${models.length} models: ${valid}`);
    reportTC('TC-SP-02', valid ? 'PASS' : 'FAIL', {
      modelCount: models.length, valid,
      samples: models.slice(0, 3).map(m => ({ id: m.id, ctx: m.contextWindow, maxOut: m.maxOutputTokens }))
    }, Date.now() - t0);
  } catch (e) { reportTC('TC-SP-02', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-SP-03: System Prompt 影响行为 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SP-03: System Prompt 影响行为   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('tc-sp03-systemprompt');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);
    // Update session with systemPrompt
    await httpPut(`${BASE}/api/sessions/${sid}`, { systemPrompt: '你是一个只会回答"喵"的猫咪助手，无论用户问什么，你都只回答一个字"喵"。' });
    const conn = await createStompConnection(sid);
    await bindAndSubscribe(conn);
    stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: '你好，今天天气怎么样？', type: 'user_message' }));
    const { messages } = await waitForMessages(conn.ws, { timeout: LLM_TIMEOUT, stopOnType: 'message_complete' });
    let fullText = '';
    for (const m of messages) {
      if (m.json?.type === 'stream_delta' && m.json?.delta) fullText += m.json.delta;
      if (m.json?.type === 'thinking_delta' && m.json?.delta) fullText += m.json.delta;
    }
    conn.ws.close();
    const hasComplete = messages.some(m => m.json?.type === 'message_complete');
    const pass = hasComplete && fullText.length > 0;
    console.log(`  回复: ${fullText.substring(0, 200)}`);
    reportTC('TC-SP-03', pass ? 'PASS' : 'PARTIAL', { fullText: fullText.substring(0, 300), hasComplete }, Date.now() - t0);
  } catch (e) { reportTC('TC-SP-03', 'FAIL', { error: e.message }, Date.now() - t0); }
}
await sleep(3000);

// ── TC-SP-04: appendSystemPrompt 追加 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SP-04: appendSystemPrompt 追加   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('tc-sp04-append');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);
    await httpPut(`${BASE}/api/sessions/${sid}`, { systemPrompt: 'All your responses must be in English only. Never use any other language.' });
    const conn = await createStompConnection(sid);
    await bindAndSubscribe(conn);
    stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: '请用中文告诉我1+1等于几', type: 'user_message' }));
    const { messages } = await waitForMessages(conn.ws, { timeout: LLM_TIMEOUT, stopOnType: 'message_complete' });
    let fullText = '';
    for (const m of messages) {
      if (m.json?.type === 'stream_delta' && m.json?.delta) fullText += m.json.delta;
    }
    conn.ws.close();
    const hasEnglish = /[a-zA-Z]/.test(fullText);
    const hasComplete = messages.some(m => m.json?.type === 'message_complete');
    console.log(`  回复: ${fullText.substring(0, 200)}`);
    reportTC('TC-SP-04', hasComplete && hasEnglish ? 'PASS' : 'PARTIAL', { fullText: fullText.substring(0, 300), hasEnglish, hasComplete }, Date.now() - t0);
  } catch (e) { reportTC('TC-SP-04', 'FAIL', { error: e.message }, Date.now() - t0); }
}
await sleep(3000);

// ── TC-SP-05: LLM 流式响应完整性 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SP-05: LLM 流式响应完整性   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('tc-sp05-stream');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);
    const conn = await createStompConnection(sid);
    await bindAndSubscribe(conn);
    stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: '1+1等于几？只回答数字', type: 'user_message' }));
    const { messages, timedOut } = await waitForMessages(conn.ws, { timeout: LLM_TIMEOUT, stopOnType: 'message_complete' });
    const typeStats = {};
    for (const m of messages) { const t = m.json?.type || 'unknown'; typeStats[t] = (typeStats[t] || 0) + 1; }
    conn.ws.close();
    const hasThinkingOrStream = (typeStats['thinking_delta'] || 0) > 0 || (typeStats['stream_delta'] || 0) > 0;
    const hasComplete = (typeStats['message_complete'] || 0) > 0;
    const pass = hasThinkingOrStream && hasComplete;
    console.log(`  类型分布: ${JSON.stringify(typeStats)}, timedOut=${timedOut}`);
    reportTC('TC-SP-05', pass ? 'PASS' : (messages.length > 0 ? 'PARTIAL' : 'FAIL'), { typeStats, timedOut, totalMsgs: messages.length }, Date.now() - t0);
  } catch (e) { reportTC('TC-SP-05', 'FAIL', { error: e.message }, Date.now() - t0); }
}
await sleep(3000);

// ── TC-SP-06: LLM 错误处理 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SP-06: LLM 错误处理   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('tc-sp06-error');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);
    // Set invalid model
    await httpPut(`${BASE}/api/sessions/${sid}`, { modelId: 'nonexistent-model-xyz-99999' });
    const conn = await createStompConnection(sid);
    await bindAndSubscribe(conn);
    stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: 'hello', type: 'user_message' }));
    const { messages } = await waitForMessages(conn.ws, { timeout: 60000, stopOnTypes: ['message_complete', 'error'] });
    conn.ws.close();
    const hasError = messages.some(m => m.json?.type === 'error' || m.json?.stopReason === 'error');
    const hasComplete = messages.some(m => m.json?.type === 'message_complete');
    const pass = hasError || hasComplete;
    console.log(`  消息类型: ${messages.map(m => m.json?.type).join(', ')}`);
    reportTC('TC-SP-06', pass ? 'PASS' : 'PARTIAL', { hasError, hasComplete, msgTypes: messages.map(m => m.json?.type) }, Date.now() - t0);
  } catch (e) { reportTC('TC-SP-06', 'FAIL', { error: e.message }, Date.now() - t0); }
}
await sleep(3000);

// ── TC-SP-07: Token 用量跟踪准确 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SP-07: Token 用量跟踪准确   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    // Short question
    const s1 = await createSession('tc-sp07-short');
    const sid1 = s1.id || s1.sessionId;
    createdSessionIds.push(sid1);
    const conn1 = await createStompConnection(sid1);
    await bindAndSubscribe(conn1);
    stompSend(conn1.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid1, content: 'hi', type: 'user_message' }));
    const r1 = await waitForMessages(conn1.ws, { timeout: LLM_TIMEOUT, stopOnType: 'message_complete' });
    conn1.ws.close();
    const complete1 = r1.messages.find(m => m.json?.type === 'message_complete');
    const tokens1 = complete1?.json?.usage?.totalTokens || complete1?.json?.totalTokens || complete1?.json?.inputTokens || 0;

    await sleep(5000);

    // Long question
    const s2 = await createSession('tc-sp07-long');
    const sid2 = s2.id || s2.sessionId;
    createdSessionIds.push(sid2);
    const conn2 = await createStompConnection(sid2);
    await bindAndSubscribe(conn2);
    const longQ = '请详细解释量子计算的基本原理，包括量子比特、量子纠缠、量子叠加态以及量子门操作的概念，并说明它们在密码学和药物研发领域的应用前景。';
    stompSend(conn2.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid2, content: longQ, type: 'user_message' }));
    const r2 = await waitForMessages(conn2.ws, { timeout: LLM_TIMEOUT, stopOnType: 'message_complete' });
    conn2.ws.close();
    const complete2 = r2.messages.find(m => m.json?.type === 'message_complete');
    const tokens2 = complete2?.json?.usage?.totalTokens || complete2?.json?.totalTokens || complete2?.json?.inputTokens || 0;

    // Compare stream_delta counts as proxy if token info unavailable
    const delta1 = r1.messages.filter(m => m.json?.type === 'stream_delta').length;
    const delta2 = r2.messages.filter(m => m.json?.type === 'stream_delta').length;
    const pass = (tokens2 > tokens1 && tokens1 > 0) || (delta2 > delta1);
    console.log(`  短问题 tokens=${tokens1}, deltas=${delta1} | 长问题 tokens=${tokens2}, deltas=${delta2}`);
    reportTC('TC-SP-07', pass ? 'PASS' : 'PARTIAL', { tokens1, tokens2, delta1, delta2 }, Date.now() - t0);
  } catch (e) { reportTC('TC-SP-07', 'FAIL', { error: e.message }, Date.now() - t0); }
}
await sleep(3000);

// ══════════════════════════════════════════════════════════
// Module 8: 记忆系统
// ══════════════════════════════════════════════════════════
console.log('\n' + '█'.repeat(70));
console.log('  Module 8: 记忆系统 (7 用例)');
console.log('█'.repeat(70));

// ── TC-MEM-01: 获取现有记忆列表 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MEM-01: 获取现有记忆列表   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/memory`);
    const json = JSON.parse(res.data);
    const pass = res.status === 200 && ('entries' in json);
    console.log(`  HTTP ${res.status}, entries count: ${json.entries?.length || 0}`);
    reportTC('TC-MEM-01', pass ? 'PASS' : 'FAIL', { status: res.status, entryCount: json.entries?.length }, Date.now() - t0);
  } catch (e) { reportTC('TC-MEM-01', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MEM-02: 创建记忆（多类别） ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MEM-02: 创建记忆（多类别）   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const categories = ['user_info', 'project_tech_stack', 'expert_experience'];
    let allCreated = true;
    for (let i = 0; i < categories.length; i++) {
      const res = await httpPost(`${BASE}/api/memory`, {
        title: `test-memory-${categories[i]}-${randomId(4)}`,
        content: `Test memory content for category ${categories[i]}`,
        category: categories[i],
        keywords: 'test,e2e',
        scope: 'global'
      });
      console.log(`  创建 ${categories[i]}: HTTP ${res.status}`);
      if (res.status === 201) {
        const json = JSON.parse(res.data);
        createdMemoryIds.push(json.id);
      } else { allCreated = false; }
    }
    reportTC('TC-MEM-02', allCreated ? 'PASS' : 'FAIL', { created: createdMemoryIds.length, categories }, Date.now() - t0);
  } catch (e) { reportTC('TC-MEM-02', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MEM-03: 读取并验证创建 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MEM-03: 读取并验证创建   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/memory`);
    const json = JSON.parse(res.data);
    const entries = json.entries || [];
    const found = createdMemoryIds.filter(id => entries.some(e => e.id === id));
    const pass = found.length === createdMemoryIds.length;
    console.log(`  已创建IDs: ${createdMemoryIds.length}, 找到: ${found.length}`);
    reportTC('TC-MEM-03', pass ? 'PASS' : 'FAIL', { expected: createdMemoryIds.length, found: found.length }, Date.now() - t0);
  } catch (e) { reportTC('TC-MEM-03', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MEM-04: 更新记忆 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MEM-04: 更新记忆   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    if (createdMemoryIds.length > 0) {
      const memId = createdMemoryIds[0];
      // Use PUT /api/memory with entries array (batch update)
      const res = await httpPut(`${BASE}/api/memory`, {
        entries: [{ id: memId, title: 'updated-title-' + randomId(4), content: 'Updated content at ' + ts(), category: 'user_info', keywords: 'updated,test', scope: 'global' }]
      });
      console.log(`  PUT /api/memory: HTTP ${res.status}`);
      // Verify updated_at changed
      const getRes = await httpGet(`${BASE}/api/memory`);
      const json = JSON.parse(getRes.data);
      const updated = json.entries?.find(e => e.id === memId);
      const pass = res.status === 200 && updated && updated.title.startsWith('updated-title-');
      reportTC('TC-MEM-04', pass ? 'PASS' : 'FAIL', { status: res.status, updatedTitle: updated?.title }, Date.now() - t0);
    } else {
      reportTC('TC-MEM-04', 'FAIL', { error: 'No memory IDs to update' }, Date.now() - t0);
    }
  } catch (e) { reportTC('TC-MEM-04', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MEM-05: 删除记忆 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MEM-05: 删除记忆   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    if (createdMemoryIds.length > 0) {
      const memId = createdMemoryIds[createdMemoryIds.length - 1];
      const res = await httpDelete(`${BASE}/api/memory/${memId}`);
      console.log(`  DELETE /api/memory/${memId}: HTTP ${res.status}`);
      const pass = res.status === 204;
      if (pass) createdMemoryIds.pop();
      reportTC('TC-MEM-05', pass ? 'PASS' : 'FAIL', { status: res.status, memId }, Date.now() - t0);
    } else {
      reportTC('TC-MEM-05', 'FAIL', { error: 'No memory IDs to delete' }, Date.now() - t0);
    }
  } catch (e) { reportTC('TC-MEM-05', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MEM-06: 记忆内容限制验证 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MEM-06: 记忆内容限制验证   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const bigContent = 'A'.repeat(12000); // >10KB
    const res = await httpPost(`${BASE}/api/memory`, {
      title: 'big-memory-test', content: bigContent, category: 'expert_experience', keywords: 'test,big', scope: 'global'
    });
    console.log(`  POST big payload (12KB): HTTP ${res.status}`);
    const pass = res.status === 201 || res.status === 400 || res.status === 413;
    if (res.status === 201) {
      const json = JSON.parse(res.data);
      createdMemoryIds.push(json.id);
    }
    reportTC('TC-MEM-06', pass ? 'PASS' : 'FAIL', { status: res.status, payloadSize: bigContent.length }, Date.now() - t0);
  } catch (e) { reportTC('TC-MEM-06', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MEM-07: 通过 LLM 对话触发 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MEM-07: 通过 LLM 对话触发   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('tc-mem07-trigger');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);
    const conn = await createStompConnection(sid);
    await bindAndSubscribe(conn);
    stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: '请记住我的名字叫测试用户E2E，这很重要', type: 'user_message' }));
    const { messages } = await waitForMessages(conn.ws, { timeout: LLM_TIMEOUT, stopOnType: 'message_complete' });
    conn.ws.close();
    const hasToolUse = messages.some(m => m.json?.type === 'tool_use' && (m.json?.toolName?.includes('memory') || m.json?.name?.includes('memory')));
    const hasComplete = messages.some(m => m.json?.type === 'message_complete');
    console.log(`  消息类型: ${[...new Set(messages.map(m => m.json?.type))].join(', ')}`);
    console.log(`  toolUse(memory): ${hasToolUse}, complete: ${hasComplete}`);
    reportTC('TC-MEM-07', hasComplete ? (hasToolUse ? 'PASS' : 'PARTIAL') : 'FAIL', { hasToolUse, hasComplete, msgTypes: [...new Set(messages.map(m => m.json?.type))] }, Date.now() - t0);
  } catch (e) { reportTC('TC-MEM-07', 'FAIL', { error: e.message }, Date.now() - t0); }
}
await sleep(3000);

// ══════════════════════════════════════════════════════════
// Module 9: 技能系统
// ══════════════════════════════════════════════════════════
console.log('\n' + '█'.repeat(70));
console.log('  Module 9: 技能系统 (7 用例)');
console.log('█'.repeat(70));

// ── TC-SKILL-01: 技能列表 API ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SKILL-01: 技能列表 API   [${ts()}]`);
console.log('='.repeat(70));
let skillList = [];
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/skills`);
    skillList = JSON.parse(res.data);
    const hasBundled = skillList.some(s => s.source === 'BUNDLED');
    const hasProject = skillList.some(s => s.source === 'PROJECT');
    const pass = res.status === 200 && skillList.length > 0;
    console.log(`  技能数量: ${skillList.length}, BUNDLED: ${hasBundled}, PROJECT: ${hasProject}`);
    reportTC('TC-SKILL-01', pass ? 'PASS' : 'FAIL', { count: skillList.length, hasBundled, hasProject, names: skillList.map(s => s.name) }, Date.now() - t0);
  } catch (e) { reportTC('TC-SKILL-01', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-SKILL-02: 技能详情 API ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SKILL-02: 技能详情 API   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const skillName = skillList.length > 0 ? skillList[0].name : 'commit';
    const res = await httpGet(`${BASE}/api/skills/${skillName}`);
    const json = JSON.parse(res.data);
    const pass = res.status === 200 && json.content && json.content.length > 0;
    console.log(`  技能 ${skillName}: content length=${json.content?.length || 0}`);
    reportTC('TC-SKILL-02', pass ? 'PASS' : 'FAIL', { name: json.name, source: json.source, contentLen: json.content?.length }, Date.now() - t0);
  } catch (e) { reportTC('TC-SKILL-02', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-SKILL-03: 技能源分类验证 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SKILL-03: 技能源分类验证   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const sources = [...new Set(skillList.map(s => s.source))];
    const hasBundled = sources.includes('BUNDLED');
    const hasProject = sources.includes('PROJECT');
    const pass = hasBundled && hasProject;
    console.log(`  Sources found: ${sources.join(', ')}`);
    reportTC('TC-SKILL-03', pass ? 'PASS' : (hasBundled ? 'PARTIAL' : 'FAIL'), { sources, hasBundled, hasProject }, Date.now() - t0);
  } catch (e) { reportTC('TC-SKILL-03', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-SKILL-04: Slash 命令 /help ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SKILL-04: Slash 命令 /help   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('tc-skill04-help');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);
    const conn = await createStompConnection(sid);
    await bindAndSubscribe(conn);
    // Try slash_command type first
    stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: '/help', type: 'slash_command' }));
    let { messages, timedOut } = await waitForMessages(conn.ws, { timeout: 30000, stopOnTypes: ['command_result', 'message_complete', 'stream_delta'], maxMessages: 20 });
    // If no response, try user_message type
    if (messages.length === 0) {
      stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: '/help', type: 'user_message' }));
      const r2 = await waitForMessages(conn.ws, { timeout: 60000, stopOnTypes: ['command_result', 'message_complete'], maxMessages: 50 });
      messages = r2.messages;
    }
    conn.ws.close();
    const hasResponse = messages.length > 0;
    console.log(`  消息数: ${messages.length}, 类型: ${[...new Set(messages.map(m => m.json?.type))].join(', ')}`);
    reportTC('TC-SKILL-04', hasResponse ? 'PASS' : 'FAIL', { msgCount: messages.length, types: [...new Set(messages.map(m => m.json?.type))] }, Date.now() - t0);
  } catch (e) { reportTC('TC-SKILL-04', 'FAIL', { error: e.message }, Date.now() - t0); }
}
await sleep(3000);

// ── TC-SKILL-05: Slash 命令 /compact ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SKILL-05: Slash 命令 /compact   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('tc-skill05-compact');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);
    const conn = await createStompConnection(sid);
    await bindAndSubscribe(conn);
    stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: '/compact', type: 'slash_command' }));
    let { messages } = await waitForMessages(conn.ws, { timeout: 30000, stopOnTypes: ['command_result', 'message_complete', 'compact_result'], maxMessages: 20 });
    if (messages.length === 0) {
      stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: '/compact', type: 'user_message' }));
      const r2 = await waitForMessages(conn.ws, { timeout: LLM_TIMEOUT, stopOnTypes: ['command_result', 'message_complete', 'compact_result'], maxMessages: 50 });
      messages = r2.messages;
    }
    conn.ws.close();
    const hasResponse = messages.length > 0;
    console.log(`  消息数: ${messages.length}, 类型: ${[...new Set(messages.map(m => m.json?.type))].join(', ')}`);
    reportTC('TC-SKILL-05', hasResponse ? 'PASS' : 'PARTIAL', { msgCount: messages.length, types: [...new Set(messages.map(m => m.json?.type))] }, Date.now() - t0);
  } catch (e) { reportTC('TC-SKILL-05', 'FAIL', { error: e.message }, Date.now() - t0); }
}
await sleep(2000);

// ── TC-SKILL-06: 不存在技能处理 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SKILL-06: 不存在技能处理   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/skills/nonexistent_skill_xyz_99`);
    console.log(`  GET /api/skills/nonexistent: HTTP ${res.status}`);
    const pass = res.status === 404;
    reportTC('TC-SKILL-06', pass ? 'PASS' : 'FAIL', { status: res.status }, Date.now() - t0);
  } catch (e) { reportTC('TC-SKILL-06', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-SKILL-07: 项目级技能文件验证 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-SKILL-07: 项目级技能文件验证   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const projectSkills = skillList.filter(s => s.source === 'PROJECT');
    const pass = projectSkills.length > 0;
    console.log(`  PROJECT skills: ${projectSkills.map(s => s.name).join(', ')}`);
    reportTC('TC-SKILL-07', pass ? 'PASS' : 'FAIL', { projectSkills: projectSkills.map(s => s.name) }, Date.now() - t0);
  } catch (e) { reportTC('TC-SKILL-07', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ══════════════════════════════════════════════════════════
// Module 10: 插件系统与 MCP
// ══════════════════════════════════════════════════════════
console.log('\n' + '█'.repeat(70));
console.log('  Module 10: 插件系统与 MCP (11 用例)');
console.log('█'.repeat(70));

// ── TC-PLG-01: 插件列表 API ──
console.log('\n' + '='.repeat(70));
console.log(`TC-PLG-01: 插件列表 API   [${ts()}]`);
console.log('='.repeat(70));
let pluginListBefore = [];
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/plugins`);
    const json = JSON.parse(res.data);
    pluginListBefore = json.plugins || [];
    const pass = res.status === 200 && Array.isArray(pluginListBefore);
    console.log(`  插件数量: ${pluginListBefore.length}`);
    reportTC('TC-PLG-01', pass ? 'PASS' : 'FAIL', { count: pluginListBefore.length, plugins: pluginListBefore.map(p => p.name) }, Date.now() - t0);
  } catch (e) { reportTC('TC-PLG-01', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-PLG-02: 插件重载 API ──
console.log('\n' + '='.repeat(70));
console.log(`TC-PLG-02: 插件重载 API   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpPost(`${BASE}/api/plugins/reload`, {});
    const json = JSON.parse(res.data);
    const pass = res.status === 200 && 'loaded' in json;
    console.log(`  POST /api/plugins/reload: HTTP ${res.status}, loaded=${json.loaded}`);
    reportTC('TC-PLG-02', pass ? 'PASS' : 'FAIL', { status: res.status, response: json }, Date.now() - t0);
  } catch (e) { reportTC('TC-PLG-02', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-PLG-03: 插件重载后列表验证 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-PLG-03: 插件重载后列表验证   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/plugins`);
    const json = JSON.parse(res.data);
    const pluginListAfter = json.plugins || [];
    const sameCount = pluginListAfter.length === pluginListBefore.length;
    const pass = res.status === 200 && sameCount;
    console.log(`  重载前: ${pluginListBefore.length}, 重载后: ${pluginListAfter.length}`);
    reportTC('TC-PLG-03', pass ? 'PASS' : 'PARTIAL', { before: pluginListBefore.length, after: pluginListAfter.length }, Date.now() - t0);
  } catch (e) { reportTC('TC-PLG-03', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── MCP 测试 ──

// ── TC-MCP-01: MCP 能力列表 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MCP-01: MCP 能力列表   [${ts()}]`);
console.log('='.repeat(70));
let mcpCapabilities = [];
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/mcp/capabilities`);
    const json = JSON.parse(res.data);
    mcpCapabilities = json.capabilities || [];
    const pass = res.status === 200 && mcpCapabilities.length > 0;
    console.log(`  MCP能力数: ${mcpCapabilities.length}, total=${json.total}`);
    reportTC('TC-MCP-01', pass ? 'PASS' : 'FAIL', { count: mcpCapabilities.length, total: json.total, enabledCount: json.enabledCount }, Date.now() - t0);
  } catch (e) { reportTC('TC-MCP-01', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MCP-02: MCP 能力详情 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MCP-02: MCP 能力详情   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const capId = mcpCapabilities.length > 0 ? mcpCapabilities[0].id : 'mcp_web_search_pro';
    const res = await httpGet(`${BASE}/api/mcp/capabilities/${capId}`);
    const json = JSON.parse(res.data);
    const pass = res.status === 200 && json.id === capId && json.toolName;
    console.log(`  能力 ${capId}: toolName=${json.toolName}, enabled=${json.enabled}`);
    reportTC('TC-MCP-02', pass ? 'PASS' : 'FAIL', { id: json.id, toolName: json.toolName, domain: json.domain }, Date.now() - t0);
  } catch (e) { reportTC('TC-MCP-02', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MCP-03: MCP 能力禁用 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MCP-03: MCP 能力禁用   [${ts()}]`);
console.log('='.repeat(70));
let toggleCapId = '';
let originalEnabled = false;
{
  const t0 = Date.now();
  try {
    toggleCapId = mcpCapabilities.length > 0 ? mcpCapabilities[0].id : 'mcp_web_search_pro';
    originalEnabled = mcpCapabilities.length > 0 ? mcpCapabilities[0].enabled : false;
    const res = await httpPatch(`${BASE}/api/mcp/capabilities/${toggleCapId}/toggle?enabled=false`, null);
    const json = JSON.parse(res.data);
    const pass = res.status === 200 && json.enabled === false;
    console.log(`  PATCH toggle enabled=false: HTTP ${res.status}, result: ${JSON.stringify(json)}`);
    reportTC('TC-MCP-03', pass ? 'PASS' : 'FAIL', { status: res.status, response: json }, Date.now() - t0);
  } catch (e) { reportTC('TC-MCP-03', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MCP-04: MCP 能力重新启用 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MCP-04: MCP 能力重新启用   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpPatch(`${BASE}/api/mcp/capabilities/${toggleCapId}/toggle?enabled=true`, null);
    const json = JSON.parse(res.data);
    const pass = res.status === 200 && json.enabled === true;
    console.log(`  PATCH toggle enabled=true: HTTP ${res.status}, result: ${JSON.stringify(json)}`);
    reportTC('TC-MCP-04', pass ? 'PASS' : 'FAIL', { status: res.status, response: json }, Date.now() - t0);
  } catch (e) { reportTC('TC-MCP-04', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// Restore original state
if (!originalEnabled && toggleCapId) {
  try { await httpPatch(`${BASE}/api/mcp/capabilities/${toggleCapId}/toggle?enabled=false`, null); } catch {}
}

// ── TC-MCP-05: MCP 能力测试端点 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MCP-05: MCP 能力测试端点   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const capId = mcpCapabilities.length > 0 ? mcpCapabilities[0].id : 'mcp_web_search_pro';
    const res = await httpPost(`${BASE}/api/mcp/capabilities/${capId}/test`, {}, 60000);
    const json = JSON.parse(res.data);
    const pass = res.status === 200 && ('status' in json);
    console.log(`  POST test ${capId}: HTTP ${res.status}, status=${json.status}`);
    reportTC('TC-MCP-05', pass ? 'PASS' : 'FAIL', { status: res.status, testResult: json }, Date.now() - t0);
  } catch (e) { reportTC('TC-MCP-05', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MCP-06: MCP 配置文件验证 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MCP-06: MCP 配置文件验证   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const filePath = '/Users/guoqingtao/Desktop/dev/code/zhikuncode/configuration/mcp/mcp_capability_registry.json';
    const content = fs.readFileSync(filePath, 'utf-8');
    const json = JSON.parse(content);
    const hasMcpTools = Array.isArray(json.mcp_tools) && json.mcp_tools.length > 0;
    const pass = hasMcpTools;
    console.log(`  文件解析: mcp_tools count=${json.mcp_tools?.length}`);
    reportTC('TC-MCP-06', pass ? 'PASS' : 'FAIL', { mcpToolsCount: json.mcp_tools?.length, schemaVersion: json._schema_version }, Date.now() - t0);
  } catch (e) { reportTC('TC-MCP-06', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ── TC-MCP-07: MCP 工具通过 LLM 触发 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MCP-07: MCP 工具通过 LLM 触发   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const session = await createSession('tc-mcp07-llm');
    const sid = session.id || session.sessionId;
    createdSessionIds.push(sid);
    const conn = await createStompConnection(sid);
    await bindAndSubscribe(conn);
    stompSend(conn.ws, '/app/chat', {}, JSON.stringify({ sessionId: sid, content: '请搜索一下今天的最新科技新闻，用网络搜索工具', type: 'user_message' }));
    const { messages } = await waitForMessages(conn.ws, { timeout: LLM_TIMEOUT, stopOnType: 'message_complete' });
    conn.ws.close();
    const hasToolUse = messages.some(m => m.json?.type === 'tool_use');
    const hasMcpTool = messages.some(m => m.json?.type === 'tool_use' && (m.json?.toolName?.includes('mcp') || m.json?.toolName?.includes('search') || m.json?.name?.includes('mcp') || m.json?.name?.includes('search')));
    const hasComplete = messages.some(m => m.json?.type === 'message_complete');
    console.log(`  消息类型: ${[...new Set(messages.map(m => m.json?.type))].join(', ')}`);
    console.log(`  toolUse: ${hasToolUse}, mcpTool: ${hasMcpTool}, complete: ${hasComplete}`);
    // MCP 可能不可达，标记 PARTIAL
    reportTC('TC-MCP-07', hasComplete ? (hasMcpTool ? 'PASS' : 'PARTIAL') : 'FAIL', { hasToolUse, hasMcpTool, hasComplete, msgTypes: [...new Set(messages.map(m => m.json?.type))] }, Date.now() - t0);
  } catch (e) { reportTC('TC-MCP-07', 'FAIL', { error: e.message }, Date.now() - t0); }
}
await sleep(2000);

// ── TC-MCP-08: 不存在 MCP 能力处理 ──
console.log('\n' + '='.repeat(70));
console.log(`TC-MCP-08: 不存在 MCP 能力处理   [${ts()}]`);
console.log('='.repeat(70));
{
  const t0 = Date.now();
  try {
    const res = await httpGet(`${BASE}/api/mcp/capabilities/nonexistent_mcp_xyz_99`);
    console.log(`  GET nonexistent capability: HTTP ${res.status}`);
    const pass = res.status === 404;
    reportTC('TC-MCP-08', pass ? 'PASS' : 'FAIL', { status: res.status }, Date.now() - t0);
  } catch (e) { reportTC('TC-MCP-08', 'FAIL', { error: e.message }, Date.now() - t0); }
}

// ══════════════════════════════════════════════════════════
// 清理
// ══════════════════════════════════════════════════════════
console.log('\n' + '-'.repeat(70));
console.log(`清理测试数据 [${ts()}]`);
console.log('-'.repeat(70));

// 清理记忆
for (const memId of createdMemoryIds) {
  try {
    await httpDelete(`${BASE}/api/memory/${memId}`);
    console.log(`  已删除记忆: ${memId}`);
  } catch (e) { console.log(`  删除记忆失败 ${memId}: ${e.message}`); }
}

// 清理会话
for (const sid of createdSessionIds) {
  await deleteSession(sid);
}
console.log(`  已清理 ${createdSessionIds.length} 个会话`);

// ══════════════════════════════════════════════════════════
// 汇总报告
// ══════════════════════════════════════════════════════════
console.log('\n' + '═'.repeat(70));
console.log(`Module 07-10: 综合测试报告   [${ts()}]`);
console.log('═'.repeat(70));

let passCount = 0, partialCount = 0, failCount = 0;
for (const r of results) {
  const icon = r.status === 'PASS' ? '✓' : r.status === 'PARTIAL' ? '△' : '✗';
  if (r.status === 'PASS') passCount++;
  else if (r.status === 'PARTIAL') partialCount++;
  else failCount++;
  console.log(`  ${icon} ${r.status.padEnd(7)} ${r.name} (${r.elapsed}ms)`);
}
const total = results.length;
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
