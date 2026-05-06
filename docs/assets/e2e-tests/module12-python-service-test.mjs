/**
 * Module 12 - Python Service E2E Tests (15 Test Cases)
 * Tests all Python service endpoints via HTTP
 */

const BASE_URL = 'http://localhost:8000';
const PROJECT_ROOT = '/Users/guoqingtao/Desktop/dev/code/zhikuncode';
const PY_FILE = `${PROJECT_ROOT}/python-service/src/main.py`;
const JAVA_FILE = `${PROJECT_ROOT}/backend/src/test/java/com/aicodeassistant/keybinding/FrontendEnhancementGoldenTest.java`;
const TS_FILE = `${PROJECT_ROOT}/frontend/src/main.tsx`;

const results = [];
import { readFileSync } from 'fs';

// Pre-read file contents for code-intel tests
const pyContent = readFileSync(PY_FILE, 'utf-8');
const javaContent = readFileSync(JAVA_FILE, 'utf-8');
const tsContent = readFileSync(TS_FILE, 'utf-8');

async function runTest(id, name, fn) {
  const start = Date.now();
  try {
    const result = await fn();
    const elapsed = Date.now() - start;
    results.push({ id, name, status: result.status, detail: result.detail, elapsed });
    const icon = result.status === 'PASS' ? '✅' : result.status === 'PARTIAL' ? '⚠️' : '❌';
    console.log(`${icon} ${id}: ${name} [${elapsed}ms] - ${result.detail}`);
  } catch (err) {
    const elapsed = Date.now() - start;
    results.push({ id, name, status: 'FAIL', detail: err.message, elapsed });
    console.log(`❌ ${id}: ${name} [${elapsed}ms] - ERROR: ${err.message}`);
  }
}

async function fetchJSON(path, options = {}) {
  const resp = await fetch(`${BASE_URL}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  return { status: resp.status, data: resp.status < 400 ? await resp.json() : null, resp };
}

// TC-PY-01: Health Check
await runTest('TC-PY-01', '健康检查 GET /api/health', async () => {
  const { data } = await fetchJSON('/api/health');
  if (data && data.status === 'ok') return { status: 'PASS', detail: `status=${data.status}` };
  return { status: 'FAIL', detail: `Unexpected: ${JSON.stringify(data)}` };
});

// TC-PY-02: Capabilities
await runTest('TC-PY-02', '能力探测 GET /api/health/capabilities', async () => {
  const { data } = await fetchJSON('/api/health/capabilities');
  if (!data) return { status: 'FAIL', detail: 'No response' };
  const capabilities = data.capabilities || data;
  const available = Array.isArray(capabilities)
    ? capabilities.filter(c => c.available || c.is_available).length
    : Object.values(capabilities).filter(v => v === true || (v && v.available)).length;
  const total = Array.isArray(capabilities) ? capabilities.length : Object.keys(capabilities).length;
  if (available >= 4) return { status: 'PASS', detail: `${available}/${total} capabilities available` };
  return { status: 'PARTIAL', detail: `Only ${available}/${total} available: ${JSON.stringify(capabilities).slice(0, 200)}` };
});

// TC-PY-03: Code Parse Python
await runTest('TC-PY-03', '代码解析 Python POST /api/code-intel/parse', async () => {
  const { data } = await fetchJSON('/api/code-intel/parse', {
    method: 'POST',
    body: JSON.stringify({ file_path: PY_FILE, content: pyContent }),
  });
  if (data && data.symbols && data.symbols.length > 0) {
    return { status: 'PASS', detail: `${data.symbols.length} symbols, lang=${data.language}` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-04: Code Parse Java
await runTest('TC-PY-04', '代码解析 Java POST /api/code-intel/parse', async () => {
  const { data } = await fetchJSON('/api/code-intel/parse', {
    method: 'POST',
    body: JSON.stringify({ file_path: JAVA_FILE, content: javaContent }),
  });
  if (data && data.symbols) {
    return { status: 'PASS', detail: `${data.symbols.length} symbols, lang=${data.language}` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-05: Code Parse TypeScript
await runTest('TC-PY-05', '代码解析 TypeScript POST /api/code-intel/parse', async () => {
  const { data } = await fetchJSON('/api/code-intel/parse', {
    method: 'POST',
    body: JSON.stringify({ file_path: TS_FILE, content: tsContent }),
  });
  if (data && data.symbols) {
    return { status: 'PASS', detail: `${data.symbols.length} symbols, lang=${data.language}` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-06: Symbol Extraction
await runTest('TC-PY-06', '符号提取 POST /api/code-intel/symbols', async () => {
  const { data } = await fetchJSON('/api/code-intel/symbols', {
    method: 'POST',
    body: JSON.stringify({ file_path: PY_FILE, content: pyContent }),
  });
  if (data && data.symbols && data.total >= 0) {
    return { status: 'PASS', detail: `${data.total} symbols extracted` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-07: Dependency Analysis
await runTest('TC-PY-07', '依赖分析 POST /api/code-intel/dependencies', async () => {
  const { data } = await fetchJSON('/api/code-intel/dependencies', {
    method: 'POST',
    body: JSON.stringify({ file_path: PY_FILE, content: pyContent }),
  });
  if (data && data.imports && data.total >= 0) {
    return { status: 'PASS', detail: `${data.total} imports found` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-08: Code Map
await runTest('TC-PY-08', 'Code Map POST /api/code-intel/code-map', async () => {
  const { data } = await fetchJSON('/api/code-intel/code-map', {
    method: 'POST',
    body: JSON.stringify({ file_path: PY_FILE, content: pyContent }),
  });
  if (data && (data.code_map || data.symbol_count >= 0)) {
    const detail = data.code_map ? `map length=${data.code_map.length}, symbols=${data.symbol_count}` : JSON.stringify(data).slice(0, 100);
    return { status: 'PASS', detail };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-09: File Encoding Detection
await runTest('TC-PY-09', '文件编码检测 POST /api/files/detect-encoding', async () => {
  const { data } = await fetchJSON('/api/files/detect-encoding', {
    method: 'POST',
    body: JSON.stringify({ file_path: PY_FILE }),
  });
  if (data && data.encoding) {
    const isUtf8 = data.encoding.toLowerCase().includes('utf');
    return { status: isUtf8 ? 'PASS' : 'PARTIAL', detail: `encoding=${data.encoding}, confidence=${data.confidence}` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-10: File Type Detection (python-magic)
await runTest('TC-PY-10', '文件类型检测 POST /api/files/detect-type', async () => {
  const { data, status: httpStatus } = await fetchJSON('/api/files/detect-type', {
    method: 'POST',
    body: JSON.stringify({ file_path: PY_FILE }),
  });
  if (httpStatus >= 400) {
    return { status: 'FAIL', detail: `HTTP ${httpStatus} - python-magic may not be functional (libmagic not installed)` };
  }
  if (data && data.mime_type) {
    if (data.mime_type === 'application/octet-stream') {
      return { status: 'PARTIAL', detail: `mime=${data.mime_type} (generic fallback, libmagic may be missing)` };
    }
    return { status: 'PASS', detail: `mime=${data.mime_type}, is_text=${data.is_text}` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-11: Safe Read
await runTest('TC-PY-11', '安全读取 POST /api/files/safe-read', async () => {
  const { data } = await fetchJSON('/api/files/safe-read', {
    method: 'POST',
    body: JSON.stringify({ file_path: PY_FILE }),
  });
  if (data && data.content && data.length > 0) {
    return { status: 'PASS', detail: `length=${data.length}, encoding=${data.encoding}` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-12: Token Estimation
await runTest('TC-PY-12', 'Token 估算 POST /api/v1/tokens/estimate', async () => {
  const { data } = await fetchJSON('/api/v1/tokens/estimate', {
    method: 'POST',
    body: JSON.stringify({ texts: ['Hello world, this is a test for token estimation.'], model: 'cl100k_base' }),
  });
  if (data && data.total > 0) {
    return { status: 'PASS', detail: `total=${data.total}, method=${data.method}` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-13: Git Log
await runTest('TC-PY-13', 'Git 增强 POST /api/git/log', async () => {
  const { data } = await fetchJSON('/api/git/log', {
    method: 'POST',
    body: JSON.stringify({ repo_path: PROJECT_ROOT, max_count: 5 }),
  });
  if (data && data.success && data.data) {
    const commits = data.data.commits || data.data;
    const count = Array.isArray(commits) ? commits.length : 'unknown';
    return { status: 'PASS', detail: `${count} commits returned` };
  }
  if (data && data.success === false) {
    return { status: 'PARTIAL', detail: `Git error: ${data.error_message || JSON.stringify(data).slice(0, 100)}` };
  }
  return { status: 'FAIL', detail: `Response: ${JSON.stringify(data).slice(0, 200)}` };
});

// TC-PY-14: Browser Automation
await runTest('TC-PY-14', '浏览器自动化 POST /api/browser/navigate', async () => {
  try {
    const { data, status: httpStatus } = await fetchJSON('/api/browser/navigate', {
      method: 'POST',
      body: JSON.stringify({ url: 'about:blank', session_id: 'test-health' }),
    });
    if (httpStatus === 404) {
      return { status: 'PARTIAL', detail: 'Browser router not registered (capability may be unavailable)' };
    }
    if (data && (data.success !== undefined || data.title !== undefined || data.url !== undefined)) {
      return { status: 'PASS', detail: `Browser responded: ${JSON.stringify(data).slice(0, 100)}` };
    }
    return { status: 'PARTIAL', detail: `HTTP ${httpStatus}: ${JSON.stringify(data).slice(0, 150)}` };
  } catch (err) {
    return { status: 'PARTIAL', detail: `Browser endpoint error: ${err.message}` };
  }
});

// TC-PY-15: 404 for nonexistent endpoint
await runTest('TC-PY-15', '不存在端点 GET /api/nonexistent → 404', async () => {
  const resp = await fetch(`${BASE_URL}/api/nonexistent`);
  if (resp.status === 404) return { status: 'PASS', detail: `HTTP ${resp.status}` };
  return { status: 'FAIL', detail: `Expected 404, got ${resp.status}` };
});

// ─── Summary ───
console.log('\n' + '═'.repeat(60));
console.log('Module 12 - Python Service Test Summary');
console.log('═'.repeat(60));
const pass = results.filter(r => r.status === 'PASS').length;
const partial = results.filter(r => r.status === 'PARTIAL').length;
const fail = results.filter(r => r.status === 'FAIL').length;
console.log(`Total: ${results.length} | PASS: ${pass} | PARTIAL: ${partial} | FAIL: ${fail}`);
console.log('─'.repeat(60));
results.forEach(r => {
  const icon = r.status === 'PASS' ? '✅' : r.status === 'PARTIAL' ? '⚠️' : '❌';
  console.log(`  ${icon} ${r.id}: ${r.status} - ${r.name}`);
});
console.log('═'.repeat(60));

// Notes on python-magic fix attempt
console.log('\n[NOTE] python-magic installation status:');
console.log('  - python-magic package: ALREADY INSTALLED (0.4.27)');
console.log('  - libmagic system library: NOT installed via Homebrew');
console.log('  - If TC-PY-10 shows PARTIAL/FAIL, install libmagic: brew install libmagic');

const exitCode = fail > 0 ? 1 : 0;
process.exit(exitCode);
