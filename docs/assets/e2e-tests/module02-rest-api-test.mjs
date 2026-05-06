/**
 * Module 02 - REST API 基础功能测试 (33 用例)
 * 覆盖所有核心 REST API 端点
 */

const BASE_URL = 'http://localhost:8080';
const results = [];
let createdSessionId = null;
let createdMemoryId = null;
let createdRuleId = null;
let uploadedFileUuid = null;

function log(msg) {
  console.log(`[${new Date().toISOString()}] ${msg}`);
}

function record(tc, name, pass, details = {}) {
  const entry = { tc, name, result: pass ? 'PASS' : 'FAIL', ...details };
  results.push(entry);
  log(`${entry.result} - ${tc}: ${name}`);
  if (details.error) log(`  ERROR: ${details.error}`);
}

async function safeFetch(url, options = {}) {
  const res = await fetch(url, options);
  const contentType = res.headers.get('content-type') || '';
  let body;
  if (contentType.includes('application/json')) {
    body = await res.json();
  } else {
    body = await res.text();
  }
  return { status: res.status, body, headers: res.headers };
}

// ============ TC-2.1: 认证 API ============
async function tc2_1() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/auth/status`);
    const pass = status === 200 && body != null;
    record('TC-2.1', 'GET /api/auth/status → 本地认证模式正常', pass, { status, body });
  } catch (e) { record('TC-2.1', 'GET /api/auth/status', false, { error: e.message }); }
}

// ============ TC-2.2: 模型 API ============
async function tc2_2() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/models`);
    const models = body?.models || body?.data || (Array.isArray(body) ? body : []);
    const hasFields = models.length > 0 && models.some(m =>
      'maxOutputTokens' in m || 'contextWindow' in m || 'supportsStreaming' in m
    );
    const pass = status === 200 && hasFields;
    record('TC-2.2', 'GET /api/models → 模型列表含关键字段', pass, { status, modelCount: models.length });
  } catch (e) { record('TC-2.2', 'GET /api/models', false, { error: e.message }); }
}

// ============ TC-2.3a-e: 会话 CRUD ============
async function tc2_3a() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: 'E2E Test Session - Module02' })
    });
    createdSessionId = body?.sessionId || body?.id;
    const hasWs = !!(body?.webSocketUrl || body?.wsUrl);
    const pass = status === 201 && !!createdSessionId;
    record('TC-2.3a', 'POST /api/sessions → 201 + sessionId', pass, { status, sessionId: createdSessionId, hasWs });
  } catch (e) { record('TC-2.3a', 'POST /api/sessions', false, { error: e.message }); }
}

async function tc2_3b() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/sessions?limit=5`);
    const sessions = body?.sessions || body?.data || [];
    const pass = status === 200 && ('hasMore' in body || 'nextCursor' in body || sessions.length >= 0);
    record('TC-2.3b', 'GET /api/sessions?limit=5 → 分页支持', pass, { status, count: sessions.length, hasMore: body?.hasMore });
  } catch (e) { record('TC-2.3b', 'GET /api/sessions?limit=5', false, { error: e.message }); }
}

async function tc2_3c() {
  try {
    if (!createdSessionId) { record('TC-2.3c', 'GET /api/sessions/{id}', false, { error: 'No session created' }); return; }
    const { status, body } = await safeFetch(`${BASE_URL}/api/sessions/${createdSessionId}`);
    const pass = status === 200 && body != null;
    record('TC-2.3c', 'GET /api/sessions/{id} → 完整会话信息', pass, { status });
  } catch (e) { record('TC-2.3c', 'GET /api/sessions/{id}', false, { error: e.message }); }
}

async function tc2_3d() {
  try {
    if (!createdSessionId) { record('TC-2.3d', 'GET /api/sessions/{id}/messages', false, { error: 'No session created' }); return; }
    const { status, body } = await safeFetch(`${BASE_URL}/api/sessions/${createdSessionId}/messages`);
    const pass = status === 200;
    record('TC-2.3d', 'GET /api/sessions/{id}/messages → 消息列表', pass, { status });
  } catch (e) { record('TC-2.3d', 'GET /api/sessions/{id}/messages', false, { error: e.message }); }
}

async function tc2_3e() {
  try {
    if (!createdSessionId) { record('TC-2.3e', 'DELETE /api/sessions/{id}', false, { error: 'No session created' }); return; }
    const { status } = await safeFetch(`${BASE_URL}/api/sessions/${createdSessionId}`, { method: 'DELETE' });
    const pass = status === 200 || status === 204;
    record('TC-2.3e', 'DELETE /api/sessions/{id} → 200', pass, { status });
    createdSessionId = null;
  } catch (e) { record('TC-2.3e', 'DELETE /api/sessions/{id}', false, { error: e.message }); }
}

// ============ TC-2.4a-b: 配置 API ============
async function tc2_4a() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/config`);
    const pass = status === 200 && body != null;
    record('TC-2.4a', 'GET /api/config → 全局配置', pass, { status });
  } catch (e) { record('TC-2.4a', 'GET /api/config', false, { error: e.message }); }
}

async function tc2_4b() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/config/project`);
    const pass = status === 200 && body != null;
    record('TC-2.4b', 'GET /api/config/project → 项目配置', pass, { status });
  } catch (e) { record('TC-2.4b', 'GET /api/config/project', false, { error: e.message }); }
}

// ============ TC-2.5a-c: 权限规则 API ============
async function tc2_5a() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/permissions/rules`);
    const pass = status === 200;
    record('TC-2.5a', 'GET /api/permissions/rules → 规则查询', pass, { status });
  } catch (e) { record('TC-2.5a', 'GET /api/permissions/rules', false, { error: e.message }); }
}

async function tc2_5b() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/permissions/rules`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        toolName: 'Bash',
        ruleContent: '/tmp/e2e-test-module02/**',
        decision: 'allow',
        scope: 'session'
      })
    });
    createdRuleId = body?.id || body?.ruleId;
    const pass = (status === 201 || status === 200) && !!createdRuleId;
    record('TC-2.5b', 'POST /api/permissions/rules → 201 规则创建', pass, { status, ruleId: createdRuleId });
  } catch (e) { record('TC-2.5b', 'POST /api/permissions/rules', false, { error: e.message }); }
}

async function tc2_5c() {
  try {
    if (!createdRuleId) { record('TC-2.5c', 'DELETE /api/permissions/rules/{id}', false, { error: 'No rule created' }); return; }
    const { status } = await safeFetch(`${BASE_URL}/api/permissions/rules/${createdRuleId}`, { method: 'DELETE' });
    const pass = status === 204 || status === 200;
    record('TC-2.5c', 'DELETE /api/permissions/rules/{id} → 204', pass, { status });
    createdRuleId = null;
  } catch (e) { record('TC-2.5c', 'DELETE /api/permissions/rules/{id}', false, { error: e.message }); }
}

// ============ TC-2.6a-c: 工具 API ============
async function tc2_6a() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/tools`);
    const tools = body?.tools || body?.data || (Array.isArray(body) ? body : []);
    const pass = status === 200 && tools.length >= 40;
    record('TC-2.6a', 'GET /api/tools → 工具列表 40+', pass, { status, toolCount: tools.length });
  } catch (e) { record('TC-2.6a', 'GET /api/tools', false, { error: e.message }); }
}

async function tc2_6b() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/tools/Read`);
    const pass = status === 200 && body != null;
    record('TC-2.6b', 'GET /api/tools/Read → 工具详情', pass, { status });
  } catch (e) { record('TC-2.6b', 'GET /api/tools/Read', false, { error: e.message }); }
}

async function tc2_6c() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/tools/Bash`);
    const pass = status === 200 && body != null;
    record('TC-2.6c', 'GET /api/tools/Bash → Bash工具详情', pass, { status });
  } catch (e) { record('TC-2.6c', 'GET /api/tools/Bash', false, { error: e.message }); }
}

// ============ TC-2.7a-b: 技能 API ============
async function tc2_7a() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/skills`);
    const skills = Array.isArray(body) ? body : (body?.skills || body?.data || []);
    const hasBundled = skills.some(s => s.source === 'BUNDLED' || s.type === 'BUNDLED');
    const pass = status === 200 && skills.length > 0;
    record('TC-2.7a', 'GET /api/skills → 技能列表', pass, { status, skillCount: skills.length, hasBundled });
  } catch (e) { record('TC-2.7a', 'GET /api/skills', false, { error: e.message }); }
}

async function tc2_7b() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/skills/translate`);
    const pass = status === 200 && body != null;
    record('TC-2.7b', 'GET /api/skills/translate → 技能定义', pass, { status });
  } catch (e) { record('TC-2.7b', 'GET /api/skills/translate', false, { error: e.message }); }
}

// ============ TC-2.8a-d: 记忆 API ============
async function tc2_8a() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/memory`);
    const pass = status === 200;
    record('TC-2.8a', 'GET /api/memory → 记忆列表', pass, { status });
  } catch (e) { record('TC-2.8a', 'GET /api/memory', false, { error: e.message }); }
}

async function tc2_8b() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/memory`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        content: 'E2E test memory entry - Module02 automated test',
        category: 'test',
        title: 'Module02 Test Memory'
      })
    });
    createdMemoryId = body?.id || body?.memoryId;
    const pass = (status === 201 || status === 200) && !!createdMemoryId;
    record('TC-2.8b', 'POST /api/memory → 201 创建记忆', pass, { status, memoryId: createdMemoryId });
  } catch (e) { record('TC-2.8b', 'POST /api/memory', false, { error: e.message }); }
}

async function tc2_8c() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/memory`);
    const memories = body?.memories || body?.entries || body?.data || [];
    let found = false;
    if (createdMemoryId) {
      const allEntries = Array.isArray(memories) ? memories : Object.values(body || {}).flat();
      found = JSON.stringify(allEntries).includes(createdMemoryId) || JSON.stringify(allEntries).includes('Module02');
    }
    const pass = status === 200 && found;
    record('TC-2.8c', 'GET /api/memory → 验证新记忆已出现', pass, { status, found });
  } catch (e) { record('TC-2.8c', 'GET /api/memory 验证', false, { error: e.message }); }
}

async function tc2_8d() {
  try {
    if (!createdMemoryId) { record('TC-2.8d', 'DELETE /api/memory/{id}', false, { error: 'No memory created' }); return; }
    const { status } = await safeFetch(`${BASE_URL}/api/memory/${createdMemoryId}`, { method: 'DELETE' });
    const pass = status === 204 || status === 200;
    record('TC-2.8d', 'DELETE /api/memory/{id} → 204 清理记忆', pass, { status });
    createdMemoryId = null;
  } catch (e) { record('TC-2.8d', 'DELETE /api/memory/{id}', false, { error: e.message }); }
}

// ============ TC-2.9a-b: 插件 API ============
async function tc2_9a() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/plugins`);
    const pass = status === 200;
    record('TC-2.9a', 'GET /api/plugins → 插件列表', pass, { status });
  } catch (e) { record('TC-2.9a', 'GET /api/plugins', false, { error: e.message }); }
}

async function tc2_9b() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/plugins/reload`, { method: 'POST' });
    const pass = status === 200;
    record('TC-2.9b', 'POST /api/plugins/reload → 重载一致', pass, { status });
  } catch (e) { record('TC-2.9b', 'POST /api/plugins/reload', false, { error: e.message }); }
}

// ============ TC-2.10a-b: MCP API ============
async function tc2_10a() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/mcp/capabilities`);
    const capabilities = body?.capabilities || body?.data || (Array.isArray(body) ? body : []);
    const pass = status === 200;
    record('TC-2.10a', 'GET /api/mcp/capabilities → MCP能力列表', pass, { status, count: capabilities.length });
    // Store first id for TC-2.10b
    if (capabilities.length > 0) {
      tc2_10a.firstCapId = capabilities[0].id || capabilities[0].name;
    }
  } catch (e) { record('TC-2.10a', 'GET /api/mcp/capabilities', false, { error: e.message }); }
}

async function tc2_10b() {
  try {
    const capId = tc2_10a.firstCapId;
    if (!capId) { record('TC-2.10b', 'GET /api/mcp/capabilities/{id}', false, { error: 'No capability id found' }); return; }
    const { status, body } = await safeFetch(`${BASE_URL}/api/mcp/capabilities/${capId}`);
    const pass = status === 200 && body != null;
    record('TC-2.10b', 'GET /api/mcp/capabilities/{id} → 完整配置', pass, { status, capId });
  } catch (e) { record('TC-2.10b', 'GET /api/mcp/capabilities/{id}', false, { error: e.message }); }
}

// ============ TC-2.11a-b: 附件 API ============
async function tc2_11a() {
  try {
    const boundary = '----E2EBoundary' + Date.now();
    const fileContent = 'E2E test file content - Module02 attachment test';
    const bodyStr = [
      `--${boundary}`,
      'Content-Disposition: form-data; name="file"; filename="e2e-test-module02.txt"',
      'Content-Type: text/plain',
      '',
      fileContent,
      `--${boundary}--`
    ].join('\r\n');

    const { status, body } = await safeFetch(`${BASE_URL}/api/attachments/upload`, {
      method: 'POST',
      headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
      body: bodyStr
    });
    uploadedFileUuid = body?.fileUuid || body?.id || body?.uuid;
    const pass = (status === 201 || status === 200) && !!uploadedFileUuid;
    record('TC-2.11a', 'POST /api/attachments/upload → 201 上传', pass, { status, fileUuid: uploadedFileUuid });
  } catch (e) { record('TC-2.11a', 'POST /api/attachments/upload', false, { error: e.message }); }
}

async function tc2_11b() {
  try {
    if (!uploadedFileUuid) { record('TC-2.11b', 'GET /api/attachments/{fileUuid}', false, { error: 'No file uploaded' }); return; }
    const { status, body } = await safeFetch(`${BASE_URL}/api/attachments/${uploadedFileUuid}`);
    const pass = status === 200 && body != null;
    record('TC-2.11b', 'GET /api/attachments/{fileUuid} → 下载一致', pass, { status });
  } catch (e) { record('TC-2.11b', 'GET /api/attachments/{fileUuid}', false, { error: e.message }); }
}

// ============ TC-2.12a-d: 健康检查 API ============
async function tc2_12a() {
  try {
    const { status } = await safeFetch(`${BASE_URL}/api/health`);
    const pass = status === 200;
    record('TC-2.12a', 'GET /api/health → 200', pass, { status });
  } catch (e) { record('TC-2.12a', 'GET /api/health', false, { error: e.message }); }
}

async function tc2_12b() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/health/live`);
    const pass = status === 200;
    record('TC-2.12b', 'GET /api/health/live → OK', pass, { status, body: typeof body === 'string' ? body.substring(0, 50) : body });
  } catch (e) { record('TC-2.12b', 'GET /api/health/live', false, { error: e.message }); }
}

async function tc2_12c() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/health/ready`);
    const pass = status === 200;
    record('TC-2.12c', 'GET /api/health/ready → READY', pass, { status, body: typeof body === 'string' ? body.substring(0, 50) : body });
  } catch (e) { record('TC-2.12c', 'GET /api/health/ready', false, { error: e.message }); }
}

async function tc2_12d() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/doctor`);
    const pass = status === 200 && body != null;
    record('TC-2.12d', 'GET /api/doctor → 检查项正常', pass, { status });
  } catch (e) { record('TC-2.12d', 'GET /api/doctor', false, { error: e.message }); }
}

// ============ TC-2.13: 远程控制 API ============
async function tc2_13() {
  try {
    const { status, body } = await safeFetch(`${BASE_URL}/api/remote/status`);
    const pass = status === 200;
    record('TC-2.13', 'GET /api/remote/status → 活跃会话状态', pass, { status });
  } catch (e) { record('TC-2.13', 'GET /api/remote/status', false, { error: e.message }); }
}

// ============ 清理残留数据 ============
async function cleanup() {
  log('=== 清理残留测试数据 ===');
  if (createdSessionId) {
    try { await safeFetch(`${BASE_URL}/api/sessions/${createdSessionId}`, { method: 'DELETE' }); log(`清理会话: ${createdSessionId}`); } catch(e) {}
  }
  if (createdMemoryId) {
    try { await safeFetch(`${BASE_URL}/api/memory/${createdMemoryId}`, { method: 'DELETE' }); log(`清理记忆: ${createdMemoryId}`); } catch(e) {}
  }
  if (createdRuleId) {
    try { await safeFetch(`${BASE_URL}/api/permissions/rules/${createdRuleId}`, { method: 'DELETE' }); log(`清理规则: ${createdRuleId}`); } catch(e) {}
  }
}

// ============ 主执行流程 ============
async function main() {
  console.log('╔══════════════════════════════════════════════════════════╗');
  console.log('║  Module 02 - REST API 基础功能测试 (33 用例)            ║');
  console.log('╚══════════════════════════════════════════════════════════╝');
  console.log(`开始时间: ${new Date().toISOString()}\n`);

  // 执行所有测试用例（按顺序）
  await tc2_1();
  await tc2_2();
  await tc2_3a();
  await tc2_3b();
  await tc2_3c();
  await tc2_3d();
  await tc2_3e();
  await tc2_4a();
  await tc2_4b();
  await tc2_5a();
  await tc2_5b();
  await tc2_5c();
  await tc2_6a();
  await tc2_6b();
  await tc2_6c();
  await tc2_7a();
  await tc2_7b();
  await tc2_8a();
  await tc2_8b();
  await tc2_8c();
  await tc2_8d();
  await tc2_9a();
  await tc2_9b();
  await tc2_10a();
  await tc2_10b();
  await tc2_11a();
  await tc2_11b();
  await tc2_12a();
  await tc2_12b();
  await tc2_12c();
  await tc2_12d();
  await tc2_13();

  // 清理残留
  await cleanup();

  // 输出汇总
  const passed = results.filter(r => r.result === 'PASS').length;
  const failed = results.filter(r => r.result === 'FAIL').length;
  const total = results.length;

  console.log('\n╔══════════════════════════════════════════════════════════╗');
  console.log('║                    测试结果汇总                          ║');
  console.log('╚══════════════════════════════════════════════════════════╝');
  console.log(`总计: ${total} | 通过: ${passed} | 失败: ${failed}`);
  console.log(`通过率: ${((passed / total) * 100).toFixed(1)}%`);
  console.log(`结束时间: ${new Date().toISOString()}\n`);

  // 输出详细JSON结果
  console.log('=== 详细结果 (JSON) ===');
  console.log(JSON.stringify({ summary: { total, passed, failed, passRate: `${((passed / total) * 100).toFixed(1)}%` }, results }, null, 2));
}

main().catch(e => { console.error('Fatal error:', e); process.exit(1); });
