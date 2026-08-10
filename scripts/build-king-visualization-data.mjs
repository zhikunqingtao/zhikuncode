#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const assetRoot = join(repoRoot, 'docs/case-studies/assets/king');
const outputPath = join(assetRoot, 'visualization-data.json');
const verification = JSON.parse(readFileSync(join(assetRoot, 'verification.json'), 'utf8'));
const provenance = JSON.parse(readFileSync(join(assetRoot, 'provenance.json'), 'utf8'));
const releaseAssets = JSON.parse(readFileSync(join(assetRoot, 'release-assets.json'), 'utf8'));
const storyboard = JSON.parse(readFileSync(join(assetRoot, 'video-storyboards.json'), 'utf8'));
const interactions = JSON.parse(readFileSync(join(assetRoot, 'db/interaction-requests-20260809-0130-0701.json'), 'utf8'));
const activities = JSON.parse(readFileSync(join(assetRoot, 'db/activities-20260809-0130-0701.json'), 'utf8'));
const appLogPath = join(assetRoot, 'logs/app-session-20260809-0130-0701.public.log');
const observabilityPath = join(assetRoot, 'logs/observability-events-20260809-0130-0701.jsonl');
const billPath = join(assetRoot, 'bill/request_log_part_0001.csv');

const sha256Buffer = (value) => createHash('sha256').update(value).digest('hex');
const sha256File = (path) => sha256Buffer(readFileSync(path));
const text = (path) => readFileSync(path, 'utf8');
const lines = (value) => value.split(/\r?\n/).filter((line, index, all) => line.length || index < all.length - 1);
const short = (value, length = 10) => String(value || '').slice(0, length);
const round = (value, digits = 3) => Number(Number(value).toFixed(digits));

function walk(root) {
  const result = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) result.push(...walk(path));
    else result.push(path);
  }
  return result;
}

function parseCsv(value) {
  const rows = [];
  let row = [], field = '', quoted = false;
  for (let i = 0; i < value.length; i += 1) {
    const ch = value[i];
    if (ch === '"' && quoted && value[i + 1] === '"') { field += '"'; i += 1; }
    else if (ch === '"') quoted = !quoted;
    else if (ch === ',' && !quoted) { row.push(field); field = ''; }
    else if ((ch === '\n' || ch === '\r') && !quoted) {
      if (ch === '\r' && value[i + 1] === '\n') i += 1;
      row.push(field); field = '';
      if (row.some((cell) => cell.length)) rows.push(row);
      row = [];
    } else field += ch;
  }
  if (field.length || row.length) { row.push(field); rows.push(row); }
  const header = rows.shift().map((cell) => cell.replace(/^\uFEFF/, ''));
  return rows.map((cells) => Object.fromEntries(header.map((name, index) => [name, cells[index] ?? ''])));
}

function timeMs(localTimestamp) {
  const normalized = localTimestamp.includes('T') ? localTimestamp : localTimestamp.replace(' ', 'T');
  return Date.parse(normalized.match(/[+-]\d\d:\d\d$/) ? normalized : `${normalized}+08:00`);
}

function minuteKey(timestamp) {
  return timestamp.slice(11, 16);
}

function fact(id, value, source, pointer, note = '') {
  return { id, value, source, pointer, note };
}

const codeRoot = join(assetRoot, 'code');
const firstPartyPaths = [join(codeRoot, 'index.html'), ...walk(join(codeRoot, 'src')).filter((path) => path.endsWith('.js')), join(codeRoot, 'start.command')];
const domainFor = (path) => {
  const rel = relative(codeRoot, path);
  if (rel === 'index.html' || rel === 'start.command') return 'entry';
  if (rel.startsWith('src/game/')) return 'game';
  if (rel.startsWith('src/world/')) return 'world';
  if (rel.startsWith('src/engine/')) return 'engine';
  if (rel.startsWith('src/ui/')) return 'ui';
  if (rel === 'src/config.js') return 'config';
  if (rel === 'src/main.js') return 'main';
  return 'utils';
};
const codeFiles = firstPartyPaths.map((path) => {
  const value = text(path);
  return {
    path: relative(codeRoot, path),
    domain: domainFor(path),
    lines: lines(value).length,
    bytes: statSync(path).size,
    sha256: sha256File(path),
  };
}).sort((a, b) => b.lines - a.lines || a.path.localeCompare(b.path));
const snapshotFiles = walk(codeRoot).filter((path) => !basename(path).startsWith('.')).map((path) => ({
  path: relative(codeRoot, path),
  bytes: statSync(path).size,
  lines: ['.js', '.html', '.command', '.txt'].includes(extname(path)) ? lines(text(path)).length : 0,
  sha256: sha256File(path),
  domain: domainFor(path),
}));
const imports = [];
for (const file of codeFiles.filter((item) => item.path.startsWith('src/'))) {
  const source = text(join(codeRoot, file.path));
  const patterns = [/\b(?:import|export)\s+(?:[^'";]*?\s+from\s+)?['"]([^'"]+)['"]/g, /\bimport\s*\(\s*['"]([^'"]+)['"]\s*\)/g];
  for (const pattern of patterns) {
    for (const match of source.matchAll(pattern)) {
      if (!match[1].startsWith('.')) continue;
      const target = relative(codeRoot, resolve(dirname(join(codeRoot, file.path)), match[1]));
      imports.push({ source: file.path, target: target.endsWith('.js') ? target : `${target}.js`, specifier: match[1] });
    }
  }
}

const observability = lines(text(observabilityPath)).map((line, lineIndex) => {
  const split = line.indexOf(' ');
  return { line: lineIndex + 1, timestamp: line.slice(0, split), ...JSON.parse(line.slice(split + 1)) };
});
const llmStarts = observability.filter((event) => event.eventType === 'llm_call_started');
const llmCompleted = observability.filter((event) => event.eventType === 'llm_call_completed');
const llmFailed = observability.filter((event) => event.eventType === 'llm_call_failed');
const subagentStarted = observability.filter((event) => event.eventType === 'subagent_started');
const subagentTerminals = observability.filter((event) => ['subagent_completed', 'subagent_failed'].includes(event.eventType));
const sessionToRun = new Map(verification.logs.traceability.mappings.map((item) => [item.childSessionId, item.childRunId]));
const workerNames = ['R01 地图地基', 'R02 战斗核心', 'R03 AI野区', 'R04 堵路修复', 'R05 终局僵持', 'R06 基地门径', 'R07 UI音效', 'R08 视觉表现', 'R09 性能修复', 'R10 QA终验'];
const workerDomains = ['world/map/models', 'state/skills/shop', 'ai/spawner', 'ai/pathing', 'state/crystal', 'ai/gate-route', 'hud/screens/audio', 'models/vfx/hud', 'materials/DOM', 'browser/acceptance'];
const workers = subagentStarted.map((start, index) => {
  const terminal = subagentTerminals.find((event) => event.data.childSessionId === start.data.childSessionId);
  const runId = sessionToRun.get(start.data.childSessionId);
  const completed = llmCompleted.filter((event) => event.data.sessionId === start.data.childSessionId);
  const failed = llmFailed.filter((event) => event.data.sessionId === start.data.childSessionId);
  const durationMs = terminal?.data.durationMs ?? 0;
  let semanticTerminal = terminal?.data.status || 'unknown';
  if (terminal?.eventType === 'subagent_completed' && durationMs >= 1_799_000) semanticTerminal = 'deadline';
  else if (terminal?.eventType === 'subagent_completed') semanticTerminal = 'natural';
  return {
    id: `R${String(index + 1).padStart(2, '0')}`,
    label: workerNames[index],
    domain: workerDomains[index],
    sessionId: start.data.childSessionId,
    runId,
    parentRunId: start.runId,
    start: start.timestamp,
    end: terminal?.timestamp,
    durationMs,
    terminal: semanticTerminal,
    recordedTerminalEvent: terminal?.eventType,
    promptLength: start.data.promptLength,
    promptFingerprint: start.data.promptFingerprint,
    llmCompleted: completed.length,
    llmFailed: failed.length,
    inputTokens: completed.reduce((sum, event) => sum + Number(event.data.inputTokens || 0), 0),
    outputTokens: completed.reduce((sum, event) => sum + Number(event.data.outputTokens || 0), 0),
  };
});
const rootRun = {
  id: 'ROOT', label: '协调者 Root Run', sessionId: verification.logs.traceability.rootSessionId,
  runId: verification.logs.traceability.rootRunId,
  start: llmStarts.find((event) => event.data.sessionId === verification.logs.traceability.rootSessionId)?.timestamp,
  end: llmCompleted.filter((event) => event.data.sessionId === verification.logs.traceability.rootSessionId).at(-1)?.timestamp,
  llmCompleted: llmCompleted.filter((event) => event.data.sessionId === verification.logs.traceability.rootSessionId).length,
  llmFailed: llmFailed.filter((event) => event.data.sessionId === verification.logs.traceability.rootSessionId).length,
  inputTokens: llmCompleted.filter((event) => event.data.sessionId === verification.logs.traceability.rootSessionId).reduce((sum, event) => sum + Number(event.data.inputTokens || 0), 0),
  outputTokens: llmCompleted.filter((event) => event.data.sessionId === verification.logs.traceability.rootSessionId).reduce((sum, event) => sum + Number(event.data.outputTokens || 0), 0),
  terminal: 'completed-window',
};

const appLines = lines(text(appLogPath));
const appEvents = appLines.map((line, index) => ({ line: index + 1, text: line, timestamp: line.match(/^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})/)?.[1] })).filter((event) => event.timestamp);
const minuteLogCounts = new Map();
const minuteComponents = new Map();
const componentRules = [
  ['QueryEngine', /\.engine\.QueryEngine/], ['ContextCascade', /\.engine\.ContextCascade/],
  ['ToolPipeline', /ToolExecutionPipeline|StreamingToolExecutor/], ['SubAgent', /SubAgent|tool\.agent/],
  ['WebBrowser', /WebBrowser|python.*requestId=/i], ['Checkpoint', /CheckpointService/],
  ['MCP', /McpClientManager|SseHealthChecker/], ['AtomicWrite', /AtomicFileWriter/],
];
for (const event of appEvents) {
  const minute = minuteKey(event.timestamp);
  minuteLogCounts.set(minute, (minuteLogCounts.get(minute) || 0) + 1);
  if (!minuteComponents.has(minute)) minuteComponents.set(minute, Object.fromEntries(componentRules.map(([name]) => [name, 0])));
  for (const [name, rule] of componentRules) if (rule.test(event.text)) minuteComponents.get(minute)[name] += 1;
}
const contextPoints = appEvents.filter((event) => event.text.includes('event=context_cascade_evaluation')).map((event) => ({
  line: event.line,
  timestamp: event.timestamp,
  id: event.text.match(/contextEvalId=([^ ]+)/)?.[1],
  tokensBefore: Number(event.text.match(/tokensBefore=(\d+)/)?.[1] || 0),
  tokensAfter: Number(event.text.match(/tokensAfter=(\d+)/)?.[1] || 0),
  threshold: Number(event.text.match(/threshold=(\d+)/)?.[1] || 0),
  collapseExecuted: event.text.includes('collapseExecuted=true'),
  charsFreed: Number(event.text.match(/charsFreed=(\d+)/)?.[1] || 0),
}));
const controlEvents = appEvents.filter((event) => /Atomic write successful|Checkpoint saved|connection lost|Successfully reconnected/.test(event.text)).map((event) => ({
  line: event.line,
  timestamp: event.timestamp,
  type: event.text.includes('Atomic write successful') ? 'atomic-write' : event.text.includes('Checkpoint saved') ? 'checkpoint' : event.text.includes('connection lost') ? 'mcp-loss' : 'mcp-reconnect',
}));

const billRows = parseCsv(text(billPath)).map((row) => ({
  requestId: row['请求 ID'], model: row['模型'], timestamp: row['时间'],
  inputTokens: Number(row['输入 Tokens']), outputTokens: Number(row['输出 Tokens']), cachedTokens: Number(row['Cached Tokens']),
})).sort((a, b) => timeMs(a.timestamp) - timeMs(b.timestamp));
const hourlyBill = [];
for (const row of billRows) {
  const hour = row.timestamp.slice(11, 13);
  let bucket = hourlyBill.find((item) => item.hour === hour);
  if (!bucket) { bucket = { hour, requests: 0, inputTokens: 0, outputTokens: 0, cachedTokens: 0, nonCachedTokens: 0 }; hourlyBill.push(bucket); }
  bucket.requests += 1; bucket.inputTokens += row.inputTokens; bucket.outputTokens += row.outputTokens; bucket.cachedTokens += row.cachedTokens; bucket.nonCachedTokens += row.inputTokens - row.cachedTokens;
}

const screenshotRoot = join(assetRoot, 'screenshots');
const screenshots = walk(screenshotRoot).filter((path) => path.endsWith('.png')).map((path) => {
  const file = basename(path);
  const epoch = Number(file.match(/_(\d{13})\.png$/)?.[1] || 0);
  return {
    path: `assets/king/${relative(assetRoot, path)}`,
    sourcePath: relative(assetRoot, path),
    bytes: statSync(path).size,
    sha256: sha256File(path),
    timestampFromFilename: epoch ? new Date(epoch).toISOString() : null,
    timestampBasis: epoch ? 'FILENAME_METADATA_ONLY' : 'REPORT_CAPTION_OR_DIRECTORY_STAGE',
    stage: relative(screenshotRoot, path).split('/')[0],
  };
}).sort((a, b) => (a.timestampFromFilename || a.path).localeCompare(b.timestampFromFilename || b.path));
const duplicateScreenshotGroups = [...Map.groupBy(screenshots, (item) => item.sha256).entries()].filter(([, items]) => items.length > 1).map(([digest, items]) => ({ sha256: digest, paths: items.map((item) => item.path) }));

const interactionRows = interactions.records.map((record, index) => {
  const prompt = JSON.parse(record.prompt_json);
  const response = JSON.parse(record.response_json);
  const created = Date.parse(record.created_at);
  const decided = Date.parse(record.decided_at || record.updated_at);
  const responded = Date.parse(record.responded_at || record.updated_at);
  return {
    id: `Q${index + 1}`,
    interactionId: record.interaction_id,
    runId: record.run_id,
    question: prompt.question,
    options: prompt.options.map((option) => ({ label: option.label, description: option.description })),
    answer: response.answer || response.value || response.selected || record.response_json,
    createdAt: record.created_at,
    decidedAt: record.decided_at || record.updated_at,
    respondedAt: record.responded_at || record.updated_at,
    decisionMs: decided - created,
    responseMs: responded - created,
  };
});

const toolDistribution = Object.entries(verification.tools.distribution).map(([tool, count]) => ({ tool, count })).sort((a, b) => b.count - a.count);
const activityTool = (record) => String(record.id || '').match(/^([A-Za-z]+)/)?.[1] || 'Unknown';
const activityToolTypes = Object.entries(Object.groupBy(activities.records, activityTool))
  .map(([tool, records]) => ({ tool, count: records.length }))
  .sort((a, b) => b.count - a.count || a.tool.localeCompare(b.tool));
const activitySummaries = activities.records.map((record) => ({
  id: record.id,
  tool: activityTool(record),
  summary: record.summary,
  timestamp: record.timestamp,
  duration: record.duration,
}));
const repositoryEvidenceAssets = walk(assetRoot)
  .filter((path) => !path.endsWith('SHA256SUMS.txt') && !path.endsWith('svg-layout-audit.json'))
  .filter((path) => !/^videos\/[^/]+\.mp4$/.test(relative(assetRoot, path)))
  .map((path) => ({
    path: relative(assetRoot, path), bytes: statSync(path).size, sha256: sha256File(path),
    category: relative(assetRoot, path).split('/')[0],
    classification: relative(assetRoot, path).includes('post-window') ? 'outside-window' : 'repository',
    availability: 'IN_REPOSITORY',
  }));
const releaseEvidenceAssets = releaseAssets.assets.map((asset) => ({
  path: `release/${asset.releaseAssetName}`,
  originalPath: asset.originalPath,
  originalName: asset.original,
  bytes: asset.originalMedia.sizeBytes,
  sha256: asset.originalSha256,
  category: 'release-originals',
  classification: 'release-original',
  availability: asset.releaseAvailability,
  plannedReleaseUrl: asset.plannedReleaseUrl,
}));
const evidenceAssets = [...repositoryEvidenceAssets, ...releaseEvidenceAssets];

const facts = {};
const addFact = (record) => { facts[record.id] = record; return record.id; };
addFact(fact('fact.code.firstPartyFiles', verification.code.firstPartyFiles, 'verification.json', '/code/firstPartyFiles'));
addFact(fact('fact.code.firstPartyLines', verification.code.firstPartyLines, 'verification.json', '/code/firstPartyLines'));
addFact(fact('fact.code.modules', verification.code.moduleGraph.sourceModules, 'verification.json', '/code/moduleGraph/sourceModules'));
addFact(fact('fact.code.importEdges', verification.code.moduleGraph.relativeImportEdges, 'verification.json', '/code/moduleGraph/relativeImportEdges'));
addFact(fact('fact.run.total', 11, 'verification.json', '/logs/traceability/runs', '1 root + 10 workers'));
addFact(fact('fact.run.workers', 10, 'verification.json', '/logs/traceability/workerParentMappings'));
addFact(fact('fact.llm.started', verification.logs.llmAudit.started, 'verification.json', '/logs/llmAudit/started'));
addFact(fact('fact.llm.completed', verification.logs.llmAudit.completed, 'verification.json', '/logs/llmAudit/completed'));
addFact(fact('fact.llm.failed', verification.logs.llmAudit.failed, 'verification.json', '/logs/llmAudit/failed'));
addFact(fact('fact.tools.total', verification.tools.total, 'verification.json', '/tools/total'));
addFact(fact('fact.tools.success', verification.logs.toolLifecycle.errorFalse, 'verification.json', '/logs/toolLifecycle/errorFalse'));
addFact(fact('fact.tools.error', verification.logs.toolLifecycle.errorTrue, 'verification.json', '/logs/toolLifecycle/errorTrue'));
addFact(fact('fact.browser.calls', verification.logs.toolIdentity.webBrowserCompositeKeys, 'verification.json', '/logs/toolIdentity/webBrowserCompositeKeys'));
addFact(fact('fact.media.screenshots', verification.screenshots.files, 'verification.json', '/screenshots/files'));
addFact(fact('fact.media.uniqueScreenshots', verification.screenshots.uniqueContent, 'verification.json', '/screenshots/uniqueContent'));
addFact(fact('fact.logs.lines', verification.logs.appLines, 'verification.json', '/logs/appLines'));
addFact(fact('fact.logs.blocks', verification.logs.mergeProvenance.timestampBlocks, 'verification.json', '/logs/mergeProvenance/timestampBlocks'));
addFact(fact('fact.bill.requests', verification.bill.requests, 'verification.json', '/bill/requests'));
addFact(fact('fact.bill.inputTokens', verification.bill.inputTokens, 'verification.json', '/bill/inputTokens'));
addFact(fact('fact.bill.cachedTokens', verification.bill.cachedTokens, 'verification.json', '/bill/cachedTokens'));
addFact(fact('fact.bill.cachePercent', verification.bill.cacheRatioPercent, 'verification.json', '/bill/cacheRatioPercent'));
addFact(fact('fact.context.evaluations', contextPoints.length, 'public application log', 'context_cascade_evaluation'));
addFact(fact('fact.context.collapses', contextPoints.filter((point) => point.collapseExecuted).length, 'public application log', 'collapseExecuted=true'));
addFact(fact('fact.controls.atomic', controlEvents.filter((event) => event.type === 'atomic-write').length, 'public application log', 'AtomicFileWriter'));
addFact(fact('fact.controls.checkpoint', controlEvents.filter((event) => event.type === 'checkpoint').length, 'public application log', 'CheckpointService'));
addFact(fact('fact.controls.reconnectPairs', verification.logs.executionControls.mcpReconnects.pairedReconnects, 'verification.json', '/logs/executionControls/mcpReconnects/pairedReconnects'));

for (const file of codeFiles) addFact(fact(`fact.file.${file.path.replaceAll('/', '.').replaceAll(/[^a-zA-Z0-9_.-]/g, '-')}.lines`, file.lines, file.path, 'physical lines'));
for (const item of toolDistribution) addFact(fact(`fact.tool.${item.tool}`, item.count, 'verification.json', `/tools/distribution/${item.tool}`));
for (const bucket of hourlyBill) {
  addFact(fact(`fact.bill.hour.${bucket.hour}.requests`, bucket.requests, 'provider bill CSV', `hour=${bucket.hour}`));
  addFact(fact(`fact.bill.hour.${bucket.hour}.input`, bucket.inputTokens, 'provider bill CSV', `hour=${bucket.hour}`));
}

const output = {
  schemaVersion: 2,
  caseId: 'zhikuncode-king-20260809',
  generatedAt: provenance.generatedAt,
  evidenceWindow: provenance.developmentWindow,
  timeBoundaries: {
    evidenceWindowStart: '2026-08-09T01:30:00+08:00',
    evidenceWindowEndExclusive: '2026-08-09T07:01:00+08:00',
    evidenceWindowDurationMs: 19_860_000,
    evidenceWindowLabel: '5小时31分证据过滤窗口',
    firstLlmRequest: llmStarts[0]?.timestamp,
    lastBillRequest: billRows.at(-1)?.timestamp,
    requestAnchorDurationMs: timeMs(billRows.at(-1)?.timestamp) - Date.parse(llmStarts[0]?.timestamp),
    requestAnchorLabel: '5小时29分17秒首末请求锚点跨度',
  },
  sourceHashes: {
    publicApplicationLog: sha256File(appLogPath),
    observability: sha256File(observabilityPath),
    bill: sha256File(billPath),
    interactions: sha256File(join(assetRoot, 'db/interaction-requests-20260809-0130-0701.json')),
    activities: sha256File(join(assetRoot, 'db/activities-20260809-0130-0701.json')),
  },
  facts,
  code: { files: codeFiles, snapshotFiles, imports, domains: verification.code.productComplexity.sourceLineDomains, product: verification.code.productComplexity, runtime: verification.code.runtimePipeline, ai: verification.code.aiTopology, presentation: verification.code.presentationPipeline },
  execution: {
    rootRun,
    workers,
    traceability: verification.logs.traceability,
    llmAudit: verification.logs.llmAudit,
    toolLifecycle: verification.logs.toolLifecycle,
    toolDistribution,
    controls: verification.logs.executionControls,
    contextPoints,
    controlEvents,
    observabilityMinutes: [...Map.groupBy(observability, (event) => minuteKey(event.timestamp)).entries()].map(([minute, events]) => ({ minute, total: events.length, types: Object.fromEntries(Object.entries(Object.groupBy(events, (event) => event.eventType)).map(([type, records]) => [type, records.length])) })),
  },
  logs: {
    minuteDensity: [...minuteLogCounts.entries()].map(([minute, count]) => ({ minute, count, components: minuteComponents.get(minute) })),
    merge: provenance.logs.mergeProvenance,
    redaction: verification.logs.publicRedaction,
    maximumSilentGapSeconds: 37.890,
  },
  bill: { totals: verification.bill, rows: billRows, hourly: hourlyBill },
  database: { session: verification.database, interactions: interactionRows, activities: activities.records, activityToolTypes, activitySummaries },
  media: {
    screenshots, duplicateScreenshotGroups,
    videos: releaseAssets.assets,
    storyboard: storyboard.videos,
  },
  evidenceAssets,
  evidenceAssetSummary: {
    repositoryFiles: repositoryEvidenceAssets.length,
    releaseOriginals: releaseEvidenceAssets.length,
    totalTraceableAssets: evidenceAssets.length,
  },
  boundaries: {
    product: '多系统耦合不等于商业成熟度、代码质量、平衡性或长期稳定性。',
    runtime: '单案例证明本次执行链可重建，不证明调度最优或平台普遍成功率。',
    trust: '主要材料仍处于同一本地信任域；SHA-256只证明相对冻结清单的一致性。',
  },
};

if (output.code.files.length !== 19 || output.code.imports.length !== 39) throw new Error(`Code reconstruction mismatch ${output.code.files.length}/${output.code.imports.length}`);
if (output.execution.workers.length !== 10 || contextPoints.length !== 878) throw new Error(`Execution reconstruction mismatch ${output.execution.workers.length}/${contextPoints.length}`);
if (output.logs.minuteDensity.reduce((sum, item) => sum + item.count, 0) !== 38626) throw new Error('Public log timestamp-block reconstruction mismatch');
if (billRows.length !== 877 || screenshots.length !== 43 || duplicateScreenshotGroups.length !== 1) throw new Error('Bill/media reconstruction mismatch');

writeFileSync(outputPath, `${JSON.stringify(output, null, 2)}\n`);
console.log(JSON.stringify({ output: outputPath, facts: Object.keys(facts).length, codeFiles: codeFiles.length, imports: imports.length, workers: workers.length, contextPoints: contextPoints.length, billRows: billRows.length, screenshotFiles: screenshots.length, evidenceAssets: evidenceAssets.length }, null, 2));
