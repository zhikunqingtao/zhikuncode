#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
const caseHtmlPath = join(repoRoot, 'docs/case-studies/zhikuncode开发王者荣耀.html');
const assetRoot = join(repoRoot, 'docs/case-studies/assets/king');
const sourceProject = resolve(repoRoot, '../../project/king');
const sourceDatabase = join(repoRoot, 'backend/.ai-code-assistant/data.db');
const localUserName = repoRoot.match(/^\/Users\/([^/]+)/)?.[1] ?? null;
const localUserHome = localUserName ? `/Users/${localUserName}` : null;
const platformCommit = 'ea0170ce0a2e4412874b2dce1200502bd9a81a48';
const documentationCommit = '74aef152fb25cfd3501ac2cf495832746aedd8f6';
const platformSourceFiles = [
  'backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java',
  'backend/src/main/java/com/aicodeassistant/engine/ContextCascade.java',
  'backend/src/main/java/com/aicodeassistant/tool/ToolExecutionPipeline.java',
  'backend/src/main/java/com/aicodeassistant/tool/agent/SubAgentExecutor.java',
  'backend/src/main/java/com/aicodeassistant/tool/agent/AgentConcurrencyController.java',
  'backend/src/main/java/com/aicodeassistant/tool/agent/CheckpointService.java',
  'backend/src/main/java/com/aicodeassistant/observability/BestEffortObservabilityRecorder.java',
];
const writeMode = process.argv.includes('--write');
const errors = [];

function fail(message) { errors.push(message); }
function assert(condition, message) { if (!condition) fail(message); }
function text(path) { return readFileSync(path, 'utf8'); }
function json(path) { return JSON.parse(text(path)); }
function sha256(path) { return createHash('sha256').update(readFileSync(path)).digest('hex'); }
function lineCount(path) { const value = text(path); return value ? value.split('\n').length - (value.endsWith('\n') ? 1 : 0) : 0; }
function sanitizePublicString(value) {
  let sanitized = value
    .replaceAll(sourceProject, '<PROJECT_ROOT>')
    .replaceAll(repoRoot, '<REPO_ROOT>');
  if (localUserHome) sanitized = sanitized.replaceAll(localUserHome, '<USER_HOME>');
  if (localUserName) sanitized = sanitized.replaceAll(localUserName, '<LOCAL_USER>');
  return sanitized;
}
function sanitizePublicValue(value) {
  if (typeof value === 'string') return sanitizePublicString(value);
  if (Array.isArray(value)) return value.map(sanitizePublicValue);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, sanitizePublicValue(item)]));
  return value;
}
function publicMessageRow(row) {
  const result = sanitizePublicValue(row);
  if (typeof row.content_json !== 'string') return result;
  try {
    const blocks = JSON.parse(row.content_json);
    result.content_json = JSON.stringify(sanitizePublicValue(Array.isArray(blocks) ? blocks.filter((block) => block?.type !== 'thinking') : blocks));
  } catch { result.content_json = sanitizePublicString(row.content_json); }
  return result;
}
function git(args, options = {}) {
  return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...options }).trimEnd();
}

const previousVerificationPath = join(assetRoot, 'verification.json');
const previousVerification = existsSync(previousVerificationPath) ? json(previousVerificationPath) : {};
const previousLogVisualizations = new Map((previousVerification.logVisualizations || []).map((entry) => [entry.id, entry]));
const previousProductVisualizations = new Map((previousVerification.productVisualizations || []).map((entry) => [entry.id, entry]));

function gitCommit(commit) {
  const [hash, shortHash, authoredAt, subject] = git(['show', '--no-patch', '--format=%H%n%h%n%aI%n%s', commit]).split('\n');
  return { hash, shortHash, authoredAt, subject };
}

function gitGrepLineCount(commit, pathspecs) {
  const output = git(['grep', '-I', '-n', '-e', '^', commit, '--', ...pathspecs]);
  return output ? output.split('\n').length : 0;
}

function analyzePlatformSnapshot() {
  const before = gitCommit(platformCommit);
  const after = gitCommit(documentationCommit);
  const changedFiles = git(['diff', '--name-only', `${platformCommit}..${documentationCommit}`]).split('\n').filter(Boolean);
  const expectedChangedFiles = [
    'README.md',
    'docs/ZhikunCode-Architecture.html',
    'docs/ZhikunCode-Capability-Overview.html',
    'docs/index.html',
  ];
  const sourceFiles = platformSourceFiles.map((path) => {
    const beforeBytes = execFileSync('git', ['show', `${platformCommit}:${path}`], { cwd: repoRoot, maxBuffer: 16 * 1024 * 1024 });
    const afterBytes = execFileSync('git', ['show', `${documentationCommit}:${path}`], { cwd: repoRoot, maxBuffer: 16 * 1024 * 1024 });
    const className = basename(path, '.java');
    return {
      path,
      className,
      sha256: createHash('sha256').update(beforeBytes).digest('hex'),
      declaredClassFound: beforeBytes.toString('utf8').includes(`class ${className}`),
      unchangedThroughDocumentationCommit: beforeBytes.equals(afterBytes),
    };
  });
  const queryEngineSource = git(['show', `${platformCommit}:backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java`]);
  const queryEngineNumberedSteps = new Set([...queryEngineSource.matchAll(/Step ([1-8])\b/g)].map((match) => Number(match[1]))).size;
  const tracked = git(['ls-tree', '-r', '--name-only', platformCommit]).split('\n').filter(Boolean);
  const scaleGroups = {
    java: {
      files: tracked.filter((path) => path.startsWith('backend/src/main/java/') && path.endsWith('.java')).length,
      lines: gitGrepLineCount(platformCommit, [':(glob)backend/src/main/java/**/*.java']),
    },
    react: {
      files: tracked.filter((path) => path.startsWith('frontend/src/') && /\.(?:ts|tsx|css)$/.test(path)).length,
      lines: gitGrepLineCount(platformCommit, [':(glob)frontend/src/**/*.ts', ':(glob)frontend/src/**/*.tsx', ':(glob)frontend/src/**/*.css']),
    },
    pythonServiceAndCli: {
      files: tracked.filter((path) => (path.startsWith('python-service/src/') || path.startsWith('python-service/cli/')) && path.endsWith('.py')).length,
      lines: gitGrepLineCount(platformCommit, [':(glob)python-service/src/**/*.py', ':(glob)python-service/cli/**/*.py']),
    },
  };
  const productSource = {
    files: Object.values(scaleGroups).reduce((sum, group) => sum + group.files, 0),
    lines: Object.values(scaleGroups).reduce((sum, group) => sum + group.lines, 0),
    groups: scaleGroups,
    countingRule: 'backend/src/main/java/**/*.java + frontend/src/**/*.{ts,tsx,css} + python-service/{src,cli}/**/*.py at ea0170c',
  };
  assert(before.authoredAt < '2026-08-09T01:30:00+08:00', `Platform snapshot is not before the evidence window: ${before.authoredAt}`);
  assert(after.authoredAt > '2026-08-09T07:01:00+08:00', `Documentation commit is not after the evidence window: ${after.authoredAt}`);
  assert(JSON.stringify(changedFiles) === JSON.stringify(expectedChangedFiles), `Unexpected files changed in documentation commit: ${changedFiles.join(', ')}`);
  assert(sourceFiles.every((file) => file.unchangedThroughDocumentationCommit), 'One or more cited runtime source files changed in the documentation commit');
  assert(sourceFiles.every((file) => file.declaredClassFound), 'One or more cited runtime source files do not declare the named class');
  assert(queryEngineNumberedSteps === 8, `QueryEngine numbered step markers ${queryEngineNumberedSteps} != 8`);
  assert(productSource.files === 863 && productSource.lines === 134826, `Platform product source scale ${productSource.lines}/${productSource.files} != 134826/863`);
  assert(scaleGroups.java.files === 622 && scaleGroups.java.lines === 93118, 'Java platform scale differs');
  assert(scaleGroups.react.files === 209 && scaleGroups.react.lines === 33642, 'React platform scale differs');
  assert(scaleGroups.pythonServiceAndCli.files === 32 && scaleGroups.pythonServiceAndCli.lines === 8066, 'Python platform scale differs');
  return {
    nearestCommitBeforeWindow: before,
    documentationCommitAfterWindow: after,
    documentationCommitChangedFiles: changedFiles,
    runtimeBuildIdRecordedInCaseLogs: null,
    versionAttribution: 'TIME_CORRELATED_SOURCE_SNAPSHOT_NOT_BINARY_ATTESTATION',
    queryEngineNumberedSteps,
    citedRuntimeSourceFiles: sourceFiles,
    productSource,
  };
}

function walkFiles(root) {
  if (!existsSync(root)) return [];
  const result = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) result.push(...walkFiles(path));
    else if (entry.isFile()) result.push(path);
  }
  return result.sort();
}

function analyzeModuleGraph(root) {
  const modules = walkFiles(root).filter((path) => extname(path) === '.js');
  const edges = [];
  const importPattern = /\b(?:import|export)\s+(?:[^'\"]*?\s+from\s+)?['\"]([^'\"]+)['\"]/g;
  for (const sourcePath of modules) {
    for (const match of text(sourcePath).matchAll(importPattern)) {
      const specifier = match[1];
      if (!specifier.startsWith('.')) continue;
      const targetPath = resolve(dirname(sourcePath), specifier);
      edges.push({
        source: relative(root, sourcePath),
        target: relative(root, targetPath),
        kind: targetPath.startsWith(`${root}/`) ? 'first-party' : 'vendored',
      });
    }
  }
  return {
    sourceModules: modules.length,
    relativeImportEdges: edges.length,
    firstPartyEdges: edges.filter((edge) => edge.kind === 'first-party').length,
    vendoredEdges: edges.filter((edge) => edge.kind === 'vendored').length,
  };
}

function countMatches(value, pattern) {
  return [...value.matchAll(pattern)].length;
}

function sourceBlock(value, startMarker, endMarker) {
  const start = value.indexOf(startMarker);
  const end = value.indexOf(endMarker, start + startMarker.length);
  assert(start >= 0 && end > start, `Cannot locate source block ${startMarker} ... ${endMarker}`);
  return start >= 0 && end > start ? value.slice(start, end) : '';
}

function parseNumber(value, pattern, label) {
  const parsed = Number(value.match(pattern)?.[1]);
  assert(Number.isFinite(parsed), `Cannot parse ${label}`);
  return parsed;
}

function assertOrdered(value, tokens, label) {
  let cursor = -1;
  for (const token of tokens) {
    const next = value.indexOf(token, cursor + 1);
    assert(next > cursor, `${label} is missing or misorders ${token}`);
    cursor = next;
  }
}

function analyzeProductCode(codeRoot) {
  const source = (path) => text(join(codeRoot, path));
  const config = source('src/config.js');
  const skills = source('src/game/skills.js');
  const shop = source('src/game/shop.js');
  const main = source('src/main.js');
  const state = source('src/game/state.js');
  const ai = source('src/game/ai.js');
  const vfx = source('src/engine/vfx.js');
  const minimap = source('src/ui/minimap.js');

  const domainLines = (directory) => walkFiles(join(codeRoot, 'src', directory))
    .filter((path) => extname(path) === '.js')
    .reduce((sum, path) => sum + lineCount(path), 0);
  const sourceLineDomains = {
    game: domainLines('game'),
    world: domainLines('world'),
    engine: domainLines('engine'),
    ui: domainLines('ui'),
    config: lineCount(join(codeRoot, 'src/config.js')),
    main: lineCount(join(codeRoot, 'src/main.js')),
    utils: lineCount(join(codeRoot, 'src/utils.js')),
    entryAndLauncher: lineCount(join(codeRoot, 'index.html')) + lineCount(join(codeRoot, 'start.command')),
  };
  sourceLineDomains.srcTotal = sourceLineDomains.game + sourceLineDomains.world + sourceLineDomains.engine
    + sourceLineDomains.ui + sourceLineDomains.config + sourceLineDomains.main + sourceLineDomains.utils;
  sourceLineDomains.firstPartyTotal = sourceLineDomains.srcTotal + sourceLineDomains.entryAndLauncher;

  const heroesBlock = sourceBlock(config, 'export const HEROES = {', '\n};');
  const minionsBlock = sourceBlock(config, 'export const MINIONS = {', '\n};');
  const monstersBlock = sourceBlock(config, 'export const MONSTERS = {', '\n};');
  const itemsBlock = sourceBlock(shop, 'export const ITEMS = {', '\n};');
  const towersBlock = sourceBlock(config, '  TOWERS_BLUE: {', '  TOWER_R:');
  const brushesBlock = sourceBlock(config, '  BRUSHES: [', '  BRUSH_W:');
  const jungleBlock = sourceBlock(config, '  JUNGLE_BLUE: {', '  CAMP_R:');
  const roles = [...sourceBlock(config, 'export const ROLES = [', '];').matchAll(/'([^']+)'/g)].map((match) => match[1]);
  const heroModes = [...new Set([...ai.matchAll(/'([A-Z][A-Z_]+)'/g)].map((match) => match[1]))].sort();
  const aimModes = [...new Set([...skills.matchAll(/\baim:\s*'([^']+)'/g)].map((match) => match[1]))].sort();
  const regularCamps = countMatches(jungleBlock, /\{\s*x:/g) * 2;
  const objectiveCamps = countMatches(source('src/game/spawner.js'), /id:\s*'(?:tyrant|overlord)'/g);
  const baseTeamLoop = source('src/world/map.js').match(/for \(const team of \[([^\]]+)\]\)/)?.[1] ?? '';
  const baseTeamCount = countMatches(baseTeamLoop, /TEAM\.(?:BLUE|RED)/g);

  const productComplexity = {
    sourceLineDomains,
    mapTopology: {
      worldSize: parseNumber(config, /\bSIZE:\s*(\d+)/, 'MAP.SIZE'),
      lanes: countMatches(sourceBlock(config, '  LANES: {', '  // 蓝方防御塔位'), /^    (?:mid|top|bot):/gm),
      towers: countMatches(towersBlock, /\[\s*-?\d+\s*,\s*-?\d+\s*\]/g) * 2,
      brushes: countMatches(brushesBlock, /\{\s*x:/g),
      camps: regularCamps + objectiveCamps,
      regularCamps,
      objectiveCamps,
      crystals: baseTeamCount,
      fountains: baseTeamCount,
      baseGates: roles.filter((role) => role !== 'bot2' && role !== 'jungle').length * 2,
      trees: parseNumber(config, /TREE_COUNT:\s*(\d+)/, 'MAP.TREE_COUNT'),
      rocks: parseNumber(config, /ROCK_COUNT:\s*(\d+)/, 'MAP.ROCK_COUNT'),
    },
    combat: {
      heroDefinitions: countMatches(heroesBlock, /^  [a-z][a-z0-9]*:\s*\{/gm),
      runtimeHeroes: 10,
      aiHeroes: parseNumber(main, /装配\s*(\d+)\s*个 AI 英雄/, 'AI hero count'),
      activeSkills: countMatches(skills, /^    (?:s1|s2|ult):\s*\{/gm),
      aimModes,
      items: countMatches(itemsBlock, /^  \w+:\s*\{/gm),
      equipmentSlots: parseNumber(shop, /new Array\((\d+)\)\.fill\(null\)/, 'equipment slots'),
      minionTypes: countMatches(minionsBlock, /^  \w+:\s*\{/gm),
      monsterTypes: countMatches(monstersBlock, /^  \w+:\s*\{/gm),
    },
    progression: {
      maxLevel: parseNumber(config, /MAX_LEVEL\s*=\s*(\d+)/, 'MAX_LEVEL'),
      firstWaveSeconds: parseNumber(config, /FIRST:\s*(\d+)/, 'WAVE.FIRST'),
      waveIntervalSeconds: parseNumber(config, /INTERVAL:\s*(\d+)/, 'WAVE.INTERVAL'),
      passiveGoldStartSeconds: parseNumber(config, /PASSIVE_GOLD_START:\s*(\d+)/, 'ECON.PASSIVE_GOLD_START'),
      tyrantFirstSeconds: parseNumber(config, /TYRANT_FIRST:\s*(\d+)/, 'JUNGLE.TYRANT_FIRST'),
      overlordFirstSeconds: parseNumber(config, /OVERLORD_FIRST:\s*(\d+)/, 'JUNGLE.OVERLORD_FIRST'),
      minionGrowthStartSeconds: parseNumber(config, /MINION_GROWTH\s*=\s*\{\s*START:\s*(\d+)/, 'MINION_GROWTH.START'),
    },
  };

  const mainOrderTokens = [
    'input.consumeActions()', 'state.update(LOOP.STEP', 'state.animate(dt)', 'minimap.update(state, dt)',
    'vfx.update(dt)', 'mapData.update(dt, elapsed)', 'engine.update(dt)', 'engine.render()', 'hud.update(engine.camera)',
  ];
  const stateOrderTokens = [
    'this.spawner.update(dt)', 'this.skills.update(dt)', 'this._updateBrush()', '// ---- 玩家控制 ----',
    'for (const ai of this.aiHeroes)', '// ---- 单位 AI / 计时 / 回复 ----', '// ---- 泉水 ----',
    'this._separate()', '// ---- 静态碰撞 / 边界 / 模型同步 ----', '// 清理已消亡单位',
  ];
  assertOrdered(sourceBlock(main, '  function frame(now) {', '\n  window.addEventListener'), mainOrderTokens, 'main frame pipeline');
  assertOrdered(sourceBlock(state, '  update(dt, moveVec) {', '  /** 兰陵王分身'), stateOrderTokens, 'GameState update pipeline');
  const runtimePipeline = {
    logicHz: Math.round(1 / parseNumber(config, /STEP:\s*1\s*\/\s*(\d+)/, 'LOOP.STEP denominator')),
    maxSubsteps: parseNumber(config, /MAX_SUB:\s*(\d+)/, 'LOOP.MAX_SUB'),
    mainOrder: ['input', 'fixed-state-update', 'animation', 'minimap', 'vfx', 'map-animation', 'camera', 'render', 'hud'],
    gameStateOrder: ['spawner', 'skills', 'brush', 'player-actions', 'hero-ai', 'unit-ai', 'fountains', 'separation', 'collision-and-model-sync', 'purge'],
  };
  runtimePipeline.logicHz = parseNumber(config, /STEP:\s*1\s*\/\s*(\d+)/, 'LOOP.STEP denominator');

  const aiTopology = {
    runtimeHeroes: productComplexity.combat.runtimeHeroes,
    aiHeroControllers: productComplexity.combat.aiHeroes,
    roles,
    heroModes,
    specializedAiTypes: [
      ai.includes('class HeroAI') ? 'hero' : null,
      ai.includes('function updateMinion') || ai.includes('export function updateMinion') ? 'minion' : null,
      ai.includes('function updateTower') || ai.includes('export function updateTower') ? 'tower' : null,
      ai.includes('function updateMonster') || ai.includes('export function updateMonster') ? 'monster' : null,
    ].filter(Boolean),
  };

  const presentationPipeline = {
    groundTextureSize: parseNumber(config, /GROUND_TEX:\s*(\d+)/, 'MAP.GROUND_TEX'),
    proceduralChannels: [
      source('src/world/map.js').includes('new THREE.CanvasTexture') ? 'canvas-textures' : null,
      source('src/world/models.js').includes('new THREE.Group') ? 'three-models' : null,
      vfx.includes('MAX_PARTICLES') ? 'vfx-pools' : null,
      source('src/engine/audio.js').includes('AudioContext') ? 'webaudio' : null,
      source('src/engine/audio.js').includes('speechSynthesis') ? 'speech-synthesis' : null,
    ].filter(Boolean),
    vfxPools: {
      particles: parseNumber(vfx, /MAX_PARTICLES\s*=\s*(\d+)/, 'MAX_PARTICLES'),
      tracers: parseNumber(vfx, /TRACER_POOL\s*=\s*(\d+)/, 'TRACER_POOL'),
      shockwaves: parseNumber(vfx, /SHOCK_POOL\s*=\s*(\d+)/, 'SHOCK_POOL'),
      pillars: parseNumber(vfx, /PILLAR_POOL\s*=\s*(\d+)/, 'PILLAR_POOL'),
      shells: parseNumber(vfx, /SHELL_POOL\s*=\s*(\d+)/, 'SHELL_POOL'),
    },
    minimapRefreshSeconds: parseNumber(minimap, /REFRESH\s*=\s*([\d.]+)/, 'minimap REFRESH'),
  };

  return { productComplexity, runtimePipeline, aiTopology, presentationPipeline };
}

function aggregateTree(root, ignoredNames = new Set(['.DS_Store'])) {
  const rows = walkFiles(root)
    .filter((path) => !ignoredNames.has(basename(path)))
    .map((path) => `${sha256(path)}  ${relative(root, path)}`);
  return { files: rows.length, digest: createHash('sha256').update(`${rows.join('\n')}\n`).digest('hex') };
}

function filterTimestampBlocks(inputs, timestampPattern, startInclusive, endExclusive) {
  const output = [];
  let keep = false;
  for (const input of inputs) {
    const chunks = input.match(/[^\n]*\n|[^\n]+$/g) ?? [];
    for (const chunk of chunks) {
      const line = chunk.endsWith('\n') ? chunk.slice(0, -1) : chunk;
      const timestamp = line.match(timestampPattern)?.[1];
      if (timestamp) keep = timestamp >= startInclusive && timestamp < endExclusive;
      if (keep) output.push(chunk);
    }
  }
  return output.join('');
}

function parseCsv(input) {
  const rows = [];
  let row = [], field = '', quoted = false;
  for (let i = 0; i < input.length; i += 1) {
    const char = input[i];
    if (quoted) {
      if (char === '"' && input[i + 1] === '"') { field += '"'; i += 1; }
      else if (char === '"') quoted = false;
      else field += char;
    } else if (char === '"') quoted = true;
    else if (char === ',') { row.push(field); field = ''; }
    else if (char === '\n') { row.push(field.replace(/\r$/, '')); rows.push(row); row = []; field = ''; }
    else field += char;
  }
  if (field || row.length) { row.push(field); rows.push(row); }
  const headers = rows.shift().map((value) => value.replace(/^\uFEFF/, ''));
  return rows.filter((values) => values.some(Boolean)).map((values) => Object.fromEntries(headers.map((header, i) => [header, values[i] ?? ''])));
}

function multiset(values) {
  const result = new Map();
  for (const value of values) result.set(value, (result.get(value) ?? 0) + 1);
  return result;
}

function subtractMultiset(left, right) {
  const remaining = new Map(left);
  for (const [key, count] of right) remaining.set(key, (remaining.get(key) ?? 0) - count);
  return [...remaining.entries()].flatMap(([key, count]) => Array(Math.max(0, count)).fill(key));
}

function parseObservability() {
  return text(join(assetRoot, 'logs/observability-events-20260809-0130-0701.jsonl'))
    .trim().split('\n').map((line, index) => {
      const start = line.indexOf('{');
      if (start < 0) throw new Error(`Observability line ${index + 1} has no JSON object`);
      return { timestamp: line.slice(0, start).trim(), ...JSON.parse(line.slice(start)) };
    });
}

function ffprobe(path) {
  const parsed = JSON.parse(execFileSync('ffprobe', [
    '-v', 'error', '-show_entries', 'format=duration,size:stream=codec_name,codec_type,width,height', '-of', 'json', path,
  ], { encoding: 'utf8' }));
  const video = parsed.streams.find((stream) => stream.codec_type === 'video');
  return {
    durationSeconds: Number(parsed.format.duration), sizeBytes: Number(parsed.format.size),
    videoCodec: video?.codec_name, width: video?.width, height: video?.height,
  };
}

const billRows = parseCsv(text(join(assetRoot, 'bill/request_log_part_0001.csv')));
const bill = {
  requests: billRows.length,
  inputTokens: billRows.reduce((sum, row) => sum + Number(row['输入 Tokens']), 0),
  outputTokens: billRows.reduce((sum, row) => sum + Number(row['输出 Tokens']), 0),
  cachedTokens: billRows.reduce((sum, row) => sum + Number(row['Cached Tokens']), 0),
};
bill.nonCachedTokens = bill.inputTokens - bill.cachedTokens;
bill.cacheRatio = bill.cachedTokens / bill.inputTokens;
assert(bill.requests === 877, `Bill request count ${bill.requests} != 877`);
assert(bill.inputTokens === 82906205, `Bill input ${bill.inputTokens} != 82906205`);
assert(bill.outputTokens === 673938, `Bill output ${bill.outputTokens} != 673938`);
assert(bill.cachedTokens === 80468224, `Bill cached ${bill.cachedTokens} != 80468224`);
assert(bill.nonCachedTokens === 2437981, `Bill non-cached ${bill.nonCachedTokens} != 2437981`);

const observability = parseObservability();
const eventCounts = {};
for (const event of observability) eventCounts[event.eventType] = (eventCounts[event.eventType] ?? 0) + 1;
const completed = observability.filter((event) => event.eventType === 'llm_call_completed');
const runtime = {
  events: observability.length,
  eventCounts,
  inputTokens: completed.reduce((sum, event) => sum + Number(event.data.inputTokens), 0),
  outputTokens: completed.reduce((sum, event) => sum + Number(event.data.outputTokens), 0),
};
assert(observability.length === 2003, `Observability lines ${observability.length} != 2003`);
assert(eventCounts.llm_call_started === 878, 'llm_call_started != 878');
assert(eventCounts.llm_call_completed === 873, 'llm_call_completed != 873');
assert(eventCounts.llm_call_failed === 5, 'llm_call_failed != 5');
assert(eventCounts.subagent_started === 10, 'subagent_started != 10');
assert(eventCounts.subagent_completed === 7, 'subagent_completed != 7');
assert(eventCounts.subagent_failed === 3, 'subagent_failed != 3');
assert(runtime.inputTokens === 82300554, `Runtime input ${runtime.inputTokens} != 82300554`);
assert(runtime.outputTokens === 670170, `Runtime output ${runtime.outputTokens} != 670170`);

const llmStartedEvents = observability.filter((event) => event.eventType === 'llm_call_started');
const llmTerminalEvents = observability.filter((event) => event.eventType === 'llm_call_completed' || event.eventType === 'llm_call_failed');
const llmStartedByRequest = Object.groupBy(llmStartedEvents, (event) => event.data.requestId);
const llmTerminalByRequest = Object.groupBy(llmTerminalEvents, (event) => event.data.requestId);
const llmStartedIds = Object.keys(llmStartedByRequest);
const llmTerminalIds = Object.keys(llmTerminalByRequest);
const missingLlmTerminalIds = llmStartedIds.filter((requestId) => !llmTerminalByRequest[requestId]);
const orphanLlmTerminalIds = llmTerminalIds.filter((requestId) => !llmStartedByRequest[requestId]);
const duplicateLlmTerminalIds = llmTerminalIds.filter((requestId) => llmTerminalByRequest[requestId].length !== 1);
const completedDurations = completed.map((event) => Number(event.data.durationMs)).sort((a, b) => a - b);
const completedDurationTotal = completedDurations.reduce((sum, value) => sum + value, 0);
const nearestRank = (values, percentile) => values[Math.ceil(values.length * percentile) - 1];
const durationHistogram = [
  { label: '<5s', minInclusiveMs: 0, maxExclusiveMs: 5000, requests: completedDurations.filter((value) => value < 5000).length },
  { label: '5–10s', minInclusiveMs: 5000, maxExclusiveMs: 10000, requests: completedDurations.filter((value) => value >= 5000 && value < 10000).length },
  { label: '10–30s', minInclusiveMs: 10000, maxExclusiveMs: 30000, requests: completedDurations.filter((value) => value >= 10000 && value < 30000).length },
  { label: '30–60s', minInclusiveMs: 30000, maxExclusiveMs: 60000, requests: completedDurations.filter((value) => value >= 30000 && value < 60000).length },
  { label: '60–120s', minInclusiveMs: 60000, maxExclusiveMs: 120000, requests: completedDurations.filter((value) => value >= 60000 && value < 120000).length },
  { label: '≥120s', minInclusiveMs: 120000, maxExclusiveMs: null, requests: completedDurations.filter((value) => value >= 120000).length },
];
const failedLlmRequests = observability.filter((event) => event.eventType === 'llm_call_failed').map((event) => ({
  requestId: event.data.requestId,
  sessionId: event.data.sessionId,
  runId: event.data.runId,
  turn: Number(event.data.turn),
  errorType: event.data.errorType,
  statusCode: Number(event.data.statusCode),
  attemptCount: Number(event.data.attemptCount),
  durationMs: Number(event.data.durationMs),
}));
const llmAudit = {
  started: llmStartedEvents.length,
  completed: completed.length,
  failed: failedLlmRequests.length,
  uniqueStartedRequestIds: llmStartedIds.length,
  uniqueTerminalRequestIds: llmTerminalIds.length,
  missingTerminalIds: missingLlmTerminalIds.length,
  orphanTerminalIds: orphanLlmTerminalIds.length,
  duplicateTerminalIds: duplicateLlmTerminalIds.length,
  failedRequests: failedLlmRequests,
  completedDurationMs: {
    count: completedDurations.length,
    total: completedDurationTotal,
    mean: completedDurationTotal / completedDurations.length,
    median: completedDurations[Math.floor(completedDurations.length / 2)],
    p95NearestRank: nearestRank(completedDurations, 0.95),
    maximum: completedDurations.at(-1),
    histogram: durationHistogram,
  },
};
assert(llmAudit.started === 878 && llmAudit.completed === 873 && llmAudit.failed === 5, 'LLM audit totals are not 878/873/5');
assert(llmAudit.uniqueStartedRequestIds === 878 && llmAudit.uniqueTerminalRequestIds === 878, 'LLM request ids are not unique at start and terminal');
assert(llmAudit.missingTerminalIds === 0 && llmAudit.orphanTerminalIds === 0 && llmAudit.duplicateTerminalIds === 0, 'LLM request ledger is not perfectly closed');
assert(failedLlmRequests.every((request) => request.errorType === 'cancelled' && request.statusCode === 0 && request.attemptCount === 1), 'The five failed LLM requests do not share the documented cancelled/0/1 terminal semantics');
assert(completedDurationTotal === 16005982, `Completed LLM duration total ${completedDurationTotal} != 16005982`);
assert(llmAudit.completedDurationMs.mean === 18334.45819014891, `Completed LLM duration mean ${llmAudit.completedDurationMs.mean} differs`);
assert(llmAudit.completedDurationMs.median === 7652, `Completed LLM duration median ${llmAudit.completedDurationMs.median} != 7652`);
assert(llmAudit.completedDurationMs.p95NearestRank === 61546, `Completed LLM duration P95 ${llmAudit.completedDurationMs.p95NearestRank} != 61546`);
assert(llmAudit.completedDurationMs.maximum === 745910, `Completed LLM duration maximum ${llmAudit.completedDurationMs.maximum} != 745910`);
assert(JSON.stringify(durationHistogram.map((bin) => bin.requests)) === JSON.stringify([209, 339, 232, 46, 27, 20]), `Unexpected LLM duration histogram: ${durationHistogram.map((bin) => bin.requests).join('/')}`);

const billTokenSet = multiset(billRows.map((row) => `${row['输入 Tokens']}/${row['输出 Tokens']}`));
const runtimeTokenSet = multiset(completed.map((event) => `${event.data.inputTokens}/${event.data.outputTokens}`));
const billOnlyKeys = subtractMultiset(billTokenSet, runtimeTokenSet);
const billOnlyRows = billRows.filter((row) => {
  const key = `${row['输入 Tokens']}/${row['输出 Tokens']}`;
  const index = billOnlyKeys.indexOf(key);
  if (index < 0) return false;
  billOnlyKeys.splice(index, 1);
  return true;
}).sort((a, b) => a['时间'].localeCompare(b['时间']));
assert(billOnlyRows.length === 4, `Bill-only rows ${billOnlyRows.length} != 4`);
assert(billOnlyRows.reduce((sum, row) => sum + Number(row['输入 Tokens']), 0) === 605651, 'Bill-only input delta != 605651');
assert(billOnlyRows.reduce((sum, row) => sum + Number(row['输出 Tokens']), 0) === 3768, 'Bill-only output delta != 3768');
assert(billOnlyRows.map((row) => row['时间'].slice(11)).join(',') === '02:09:59,03:17:41,03:52:34,06:08:41', 'Unexpected bill-only timestamps');

const messages = json(join(assetRoot, 'db/session-messages.json'));
const roles = Object.groupBy(messages, (message) => message.role);
const messageStats = {
  rows: messages.length,
  user: roles.user?.length ?? 0,
  assistant: roles.assistant?.length ?? 0,
  inputTokens: messages.reduce((sum, message) => sum + Number(message.input_tokens), 0),
  outputTokens: messages.reduce((sum, message) => sum + Number(message.output_tokens), 0),
};
assert(messageStats.rows === 229, `Message rows ${messageStats.rows} != 229`);
assert(messageStats.user === 117 && messageStats.assistant === 112, 'Message role counts differ');
assert(messageStats.inputTokens === 7386868 && messageStats.outputTokens === 58959, 'Message token totals differ');

const session = json(join(assetRoot, 'db/session-row.json'));
assert(session.id === 'b8f86099-452d-4ba6-89c2-c3fee8f4b422', 'Session id differs');
assert(session.working_dir === '<PROJECT_ROOT>', 'Public session export does not contain the portable working_dir placeholder');
assert(session.total_input_tokens === 0 && session.total_output_tokens === 0, 'Session aggregate token fields changed from frozen source');
const activities = json(join(assetRoot, 'db/activities-20260809-0130-0701.json'));
const interactions = json(join(assetRoot, 'db/interaction-requests-20260809-0130-0701.json'));
assert(activities.rowCount === 113 && activities.records.length === 113, 'Activity export count != 113');
assert(interactions.rowCount === 4 && interactions.records.length === 4, 'Interaction export count != 4');

const firstPartyFiles = [join(assetRoot, 'code/index.html'), join(assetRoot, 'code/start.command')]
  .concat(walkFiles(join(assetRoot, 'code/src')).filter((path) => extname(path) === '.js'));
const code = { firstPartyFiles: firstPartyFiles.length, firstPartyLines: firstPartyFiles.reduce((sum, path) => sum + lineCount(path), 0) };
assert(code.firstPartyFiles === 19, `First-party files ${code.firstPartyFiles} != 19`);
assert(code.firstPartyLines === 7979, `First-party lines ${code.firstPartyLines} != 7979`);
code.moduleGraph = analyzeModuleGraph(join(assetRoot, 'code/src'));
assert(code.moduleGraph.sourceModules === 17, `Source modules ${code.moduleGraph.sourceModules} != 17`);
assert(code.moduleGraph.relativeImportEdges === 39, `Relative import edges ${code.moduleGraph.relativeImportEdges} != 39`);
assert(code.moduleGraph.firstPartyEdges === 38, `First-party import edges ${code.moduleGraph.firstPartyEdges} != 38`);
assert(code.moduleGraph.vendoredEdges === 1, `Vendored import edges ${code.moduleGraph.vendoredEdges} != 1`);
Object.assign(code, analyzeProductCode(join(assetRoot, 'code')));
assert(JSON.stringify(code.productComplexity.sourceLineDomains) === JSON.stringify({
  game: 2989, world: 1570, engine: 1278, ui: 1172, config: 362, main: 186, utils: 88,
  entryAndLauncher: 334, srcTotal: 7645, firstPartyTotal: 7979,
}), `Product source domain lines differ: ${JSON.stringify(code.productComplexity.sourceLineDomains)}`);
assert(JSON.stringify(code.productComplexity.mapTopology) === JSON.stringify({
  worldSize: 180, lanes: 3, towers: 18, brushes: 14, camps: 10, regularCamps: 8,
  objectiveCamps: 2, crystals: 2, fountains: 2, baseGates: 6, trees: 120, rocks: 46,
}), `Map topology differs: ${JSON.stringify(code.productComplexity.mapTopology)}`);
assert(code.productComplexity.combat.heroDefinitions === 5, 'Hero definition count != 5');
assert(code.productComplexity.combat.runtimeHeroes === 10 && code.productComplexity.combat.aiHeroes === 9, 'Runtime hero topology differs');
assert(code.productComplexity.combat.activeSkills === 15, 'Active skill count != 15');
assert(JSON.stringify(code.productComplexity.combat.aimModes) === JSON.stringify(['area', 'around', 'dash', 'line', 'self', 'target']), 'Skill aim modes differ');
assert(code.productComplexity.combat.items === 12 && code.productComplexity.combat.equipmentSlots === 6, 'Item or equipment slot count differs');
assert(code.productComplexity.combat.minionTypes === 4 && code.productComplexity.combat.monsterTypes === 5, 'Unit type count differs');
assert(code.runtimePipeline.logicHz === 30 && code.runtimePipeline.maxSubsteps === 5, 'Runtime loop frequency or substep cap differs');
assert(code.runtimePipeline.mainOrder.length === 9 && code.runtimePipeline.gameStateOrder.length === 10, 'Runtime pipeline phase count differs');
assert(code.aiTopology.aiHeroControllers === 9 && code.aiTopology.specializedAiTypes.length === 4, 'AI topology differs');
assert(JSON.stringify(code.aiTopology.roles) === JSON.stringify(['top', 'mid', 'bot', 'bot2', 'jungle']), 'AI roles differ');
assert(JSON.stringify(code.aiTopology.heroModes) === JSON.stringify(['ASSAULT', 'DEFEND', 'GROUP_PUSH', 'JUNGLE', 'LANE', 'OBJECTIVE', 'PUSH', 'RETREAT', 'TEAMFIGHT']), 'Hero AI modes differ');
assert(code.presentationPipeline.groundTextureSize === 2048, 'Ground texture size != 2048');
assert(JSON.stringify(code.presentationPipeline.vfxPools) === JSON.stringify({ particles: 1024, tracers: 48, shockwaves: 12, pillars: 10, shells: 12 }), 'VFX pools differ');
assert(code.presentationPipeline.minimapRefreshSeconds === 0.25, 'Minimap refresh interval != 0.25s');

const codeMismatches = [];
let sourceAggregateResult = null;
let sourceRuntimeFileSetMatches = null;
if (existsSync(sourceProject)) {
  for (const evidencePath of walkFiles(join(assetRoot, 'code'))) {
    const rel = relative(join(assetRoot, 'code'), evidencePath);
    if (rel === 'lib/THREE-LICENSE.txt') continue;
    const sourcePath = join(sourceProject, rel);
    if (!existsSync(sourcePath) || sha256(sourcePath) !== sha256(evidencePath)) codeMismatches.push(rel);
  }
  const sourceRuntimeFiles = walkFiles(sourceProject)
    .map((path) => relative(sourceProject, path))
    .filter((path) => basename(path) !== '.DS_Store')
    .filter((path) => path === 'index.html' || path === 'start.command' || path.startsWith('src/') || path.startsWith('lib/'))
    .sort();
  const evidenceRuntimeFiles = walkFiles(join(assetRoot, 'code'))
    .map((path) => relative(join(assetRoot, 'code'), path))
    .filter((path) => path !== 'lib/THREE-LICENSE.txt')
    .sort();
  sourceRuntimeFileSetMatches = JSON.stringify(sourceRuntimeFiles) === JSON.stringify(evidenceRuntimeFiles);
  assert(sourceRuntimeFileSetMatches, 'Evidence code snapshot does not cover the complete source runtime file set');
  sourceAggregateResult = aggregateTree(sourceProject);
  assert(sourceAggregateResult.files === 57 && sourceAggregateResult.digest === '1efe5f9c31e07a5e4cf3e874b3c30ba25498c0d5d1c39c23bcb3c791c3270ab4', 'Original king project aggregate changed');
}
assert(codeMismatches.length === 0, `Evidence code differs from original: ${codeMismatches.join(', ')}`);
code.evidenceMatchesSourceRuntime = existsSync(sourceProject) ? codeMismatches.length === 0 && sourceRuntimeFileSetMatches : null;
code.sourceProjectAggregate = sourceAggregateResult;

let databaseExportsMatchSource = null;
if (existsSync(sourceDatabase)) {
  const query = (sql) => {
    const output = execFileSync('sqlite3', ['-json', sourceDatabase, sql], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    return output.trim() ? JSON.parse(output) : [];
  };
  const liveSession = query(`SELECT * FROM sessions WHERE id='${session.id}'`);
  const liveMessages = query(`SELECT * FROM messages WHERE session_id='${session.id}' AND created_at >= '2026-08-08T17:30:00Z' AND created_at < '2026-08-08T23:01:00Z' ORDER BY seq_num, created_at, id`);
  const liveActivities = query(`SELECT * FROM activities WHERE session_id='${session.id}' AND timestamp >= unixepoch('2026-08-08T17:30:00Z')*1000 AND timestamp < unixepoch('2026-08-08T23:01:00Z')*1000 ORDER BY timestamp, id`);
  const liveInteractions = query(`SELECT * FROM interaction_requests WHERE session_id='${session.id}' AND created_at >= '2026-08-08T17:30:00Z' AND created_at < '2026-08-08T23:01:00Z' ORDER BY created_at, interaction_id`);
  databaseExportsMatchSource = JSON.stringify(sanitizePublicValue(liveSession[0])) === JSON.stringify(session)
    && JSON.stringify(liveMessages.map(publicMessageRow)) === JSON.stringify(messages)
    && JSON.stringify(sanitizePublicValue(liveActivities)) === JSON.stringify(activities.records)
    && JSON.stringify(sanitizePublicValue(liveInteractions)) === JSON.stringify(interactions.records);
  assert(databaseExportsMatchSource, 'Public database exports do not match the live source after the documented publication transform');
}

const appLogPath = join(assetRoot, 'logs/app-session-20260809-0130-0701.public.log');
const securityLogPath = join(assetRoot, 'logs/security-audit-20260809-0130-0701.log');
assert(lineCount(appLogPath) === 38641, `App log lines ${lineCount(appLogPath)} != 38641`);
assert(lineCount(securityLogPath) === 116, `Security log lines ${lineCount(securityLogPath)} != 116`);
const appLogLines = text(appLogPath).split('\n');
const mdcPattern = /^([^ ]+ [^ ]+)\s+\S+\s+\[[^\]]*\]\s+\[sid=([^ ]+) rid=([^ ]+) prid=([^ ]+) agent=([^ ]+) turn=([^ ]+) tool=([^ ]+) llm=([^\]]+)\]/;
const mdcRows = appLogLines.map((line, lineIndex) => {
  const match = line.match(mdcPattern);
  if (!match) return null;
  return {
    line: lineIndex + 1,
    timestamp: match[1],
    sessionId: match[2],
    runId: match[3],
    parentRunId: match[4],
    agentRole: match[5],
    turn: match[6] === '-' ? null : Number(match[6]),
    toolUseId: match[7] === '-' ? null : match[7],
    llmRequestId: match[8] === '-' ? null : match[8],
    raw: line,
  };
}).filter(Boolean);
const traceSessions = [...new Set(mdcRows.map((row) => row.sessionId).filter((value) => value !== '-'))].sort();
const traceRuns = [...new Set(mdcRows.map((row) => row.runId).filter((value) => value !== '-'))].sort();
const workerMappingMap = new Map();
for (const row of mdcRows.filter((value) => value.agentRole === 'subagent' && value.parentRunId !== '-')) {
  workerMappingMap.set(`${row.sessionId}|${row.runId}|${row.parentRunId}`, {
    childSessionId: row.sessionId,
    childRunId: row.runId,
    parentRunId: row.parentRunId,
  });
}
const workerParentMappings = [...workerMappingMap.values()].sort((a, b) => a.childSessionId.localeCompare(b.childSessionId));
const rootMdcRows = mdcRows.filter((row) => row.agentRole === 'query' && row.parentRunId === '-' && row.sessionId !== '-' && row.runId !== '-');
const traceability = {
  mdcRows: mdcRows.length,
  sessions: traceSessions.length,
  runs: traceRuns.length,
  workerParentMappings: workerParentMappings.length,
  rootSessionId: [...new Set(rootMdcRows.map((row) => row.sessionId))][0],
  rootRunId: [...new Set(rootMdcRows.map((row) => row.runId))][0],
  mappings: workerParentMappings,
  roleSource: 'MDC agent field',
  observabilityAgentTypeRoleWarning: 'observability data.agentType=general-purpose is not used to distinguish coordinator and SubAgent roles',
};
assert(traceability.mdcRows === 38626, `MDC-bearing rows ${traceability.mdcRows} != 38626`);
assert(traceability.sessions === 11 && traceability.runs === 11 && traceability.workerParentMappings === 10, `Trace graph ${traceability.sessions}/${traceability.runs}/${traceability.workerParentMappings} != 11/11/10`);
assert(traceability.rootSessionId === 'b8f86099-452d-4ba6-89c2-c3fee8f4b422', `Unexpected root session ${traceability.rootSessionId}`);
assert(traceability.rootRunId === 'eb2e9ba2-6975-4c83-9e83-8eebcb7f1b10', `Unexpected root run ${traceability.rootRunId}`);
const contextEvaluationLines = appLogLines.filter((line) => line.includes('com.aicodeassistant.engine.ContextCascade - event=context_cascade_evaluation'));
const contextNumber = (line, field) => Number(line.match(new RegExp(`${field}=(\\d+)`))?.[1] ?? 0);
const contextIds = contextEvaluationLines.map((line) => line.match(/contextEvalId=([^ ]+)/)?.[1]).filter(Boolean);
const contextThresholds = [...new Set(contextEvaluationLines.map((line) => contextNumber(line, 'threshold')))];
const collapseOutcomeLines = appLogLines.filter((line) => line.includes('com.aicodeassistant.engine.ContextCollapseService - event=context_collapse_candidates'));
const collapseOutcomes = {};
for (const line of collapseOutcomeLines) {
  const outcome = line.match(/outcome=([^ ]+)/)?.[1];
  if (outcome) collapseOutcomes[outcome] = (collapseOutcomes[outcome] ?? 0) + 1;
}
const contextGovernance = {
  evaluations: contextEvaluationLines.length,
  uniqueEvaluationIds: new Set(contextIds).size,
  llmStartedEvents: eventCounts.llm_call_started,
  collapseAttempted: contextEvaluationLines.filter((line) => line.includes('collapseAttempted=true')).length,
  collapseExecuted: contextEvaluationLines.filter((line) => line.includes('collapseExecuted=true')).length,
  charsFreed: contextEvaluationLines.reduce((sum, line) => sum + contextNumber(line, 'charsFreed'), 0),
  maximumTokensBefore: Math.max(...contextEvaluationLines.map((line) => contextNumber(line, 'tokensBefore'))),
  maximumTokensAfter: Math.max(...contextEvaluationLines.map((line) => contextNumber(line, 'tokensAfter'))),
  thresholds: contextThresholds,
  aboveThresholdEvents: contextEvaluationLines.filter((line) => line.includes('aboveThreshold=true')).length,
  autoCompactAttempted: contextEvaluationLines.filter((line) => line.includes('autoCompactAttempted=true')).length,
  autoCompactExecuted: contextEvaluationLines.filter((line) => line.includes('autoCompactExecuted=true')).length,
  collapseDrainObserved: appLogLines.filter((line) => /\bCollapseDrain\b/.test(line)).length,
  reactiveCompactObserved: appLogLines.filter((line) => /\bReactiveCompact\b/.test(line)).length,
  payloadTooLargeRecoveryObserved: appLogLines.filter((line) => /recoverFromPayloadTooLarge|payload[_ -]?too[_ -]?large/i.test(line)).length,
  collapseCandidateEvents: collapseOutcomeLines.length,
  collapseOutcomes,
};
assert(contextGovernance.evaluations === 878, `Context evaluations ${contextGovernance.evaluations} != 878`);
assert(contextGovernance.uniqueEvaluationIds === 878, `Unique context evaluation ids ${contextGovernance.uniqueEvaluationIds} != 878`);
assert(contextGovernance.evaluations === contextGovernance.llmStartedEvents, 'Context evaluations do not match llm_call_started events');
assert(contextGovernance.collapseAttempted === 878, `Context collapse attempts ${contextGovernance.collapseAttempted} != 878`);
assert(contextGovernance.collapseExecuted === 26, `Context collapses executed ${contextGovernance.collapseExecuted} != 26`);
assert(contextGovernance.charsFreed === 2029, `Context collapse chars freed ${contextGovernance.charsFreed} != 2029`);
assert(contextGovernance.maximumTokensBefore === 240202 && contextGovernance.maximumTokensAfter === 240202, 'Maximum observed context token estimate differs');
assert(JSON.stringify(contextGovernance.thresholds) === JSON.stringify([650000]), `Unexpected context thresholds: ${contextGovernance.thresholds.join(', ')}`);
assert(contextGovernance.aboveThresholdEvents === 0 && contextGovernance.autoCompactAttempted === 0 && contextGovernance.autoCompactExecuted === 0, 'Heavy context compaction unexpectedly activated');
assert(contextGovernance.collapseDrainObserved === 0 && contextGovernance.reactiveCompactObserved === 0 && contextGovernance.payloadTooLargeRecoveryObserved === 0, 'A heavy recovery layer was observed in the case log');
assert(contextGovernance.collapseCandidateEvents === 878, `Context collapse candidate events ${contextGovernance.collapseCandidateEvents} != 878`);
assert(JSON.stringify(contextGovernance.collapseOutcomes) === JSON.stringify({ NO_CANDIDATE: 50, NO_GAIN: 802, POSITIVE_ONLY: 26 }), `Unexpected collapse outcomes: ${JSON.stringify(contextGovernance.collapseOutcomes)}`);

const toolLifecycleRecords = new Map();
const lifecycleRecord = (key, tool) => {
  if (!toolLifecycleRecords.has(key)) toolLifecycleRecords.set(key, { tool, validation: 0, call: 0, completed: 0, error: null });
  return toolLifecycleRecords.get(key);
};
for (const line of appLogLines) {
  if (!line.includes('com.aicodeassistant.tool.ToolExecutionPipeline - ')) continue;
  const context = line.match(/\[sid=([^ ]+) rid=([^ ]+).*?tool=([A-Za-z]+_[0-9]+) /);
  if (!context) continue;
  const [, sid, rid, toolId] = context;
  const tool = toolId.match(/^([A-Za-z]+)_/)?.[1];
  const record = lifecycleRecord(`${sid}:${rid}:${toolId}`, tool);
  if (line.includes('(stage 1: validation)')) record.validation += 1;
  if (line.includes('(stage 5: call)')) record.call += 1;
  const completedMatch = line.match(/Tool ([A-Za-z]+) completed in \d+ms \(error=(true|false)\)/);
  if (completedMatch) {
    record.completed += 1;
    record.error = completedMatch[2] === 'true';
  }
}
const lifecycleValues = [...toolLifecycleRecords.values()];
const errorByTool = {};
for (const record of lifecycleValues.filter((value) => value.error === true)) errorByTool[record.tool] = (errorByTool[record.tool] ?? 0) + 1;
const toolLifecycle = {
  uniqueCalls: toolLifecycleRecords.size,
  stage1Validation: lifecycleValues.reduce((sum, record) => sum + record.validation, 0),
  stage5Call: lifecycleValues.reduce((sum, record) => sum + record.call, 0),
  completed: lifecycleValues.reduce((sum, record) => sum + record.completed, 0),
  completeThreeEventLifecycles: lifecycleValues.filter((record) => record.validation === 1 && record.call === 1 && record.completed === 1).length,
  incompleteOrDuplicateLifecycles: lifecycleValues.filter((record) => record.validation !== 1 || record.call !== 1 || record.completed !== 1).length,
  errorFalse: lifecycleValues.filter((record) => record.error === false).length,
  errorTrue: lifecycleValues.filter((record) => record.error === true).length,
  errorFalsePercent: Number((lifecycleValues.filter((record) => record.error === false).length / lifecycleValues.length * 100).toFixed(3)),
  errorTrueByTool: errorByTool,
};
assert(toolLifecycle.uniqueCalls === 968, `Tool lifecycle calls ${toolLifecycle.uniqueCalls} != 968`);
assert(toolLifecycle.stage1Validation === 968 && toolLifecycle.stage5Call === 968 && toolLifecycle.completed === 968, 'Tool lifecycle stage totals are not 968/968/968');
assert(toolLifecycle.completeThreeEventLifecycles === 968 && toolLifecycle.incompleteOrDuplicateLifecycles === 0, 'One or more tool lifecycles are incomplete or duplicated');
assert(toolLifecycle.errorFalse === 951 && toolLifecycle.errorTrue === 17, `Tool completion results ${toolLifecycle.errorFalse}/${toolLifecycle.errorTrue} != 951/17`);
assert(JSON.stringify(toolLifecycle.errorTrueByTool) === JSON.stringify({ Edit: 6, WebBrowser: 5, Agent: 3, CodeIntel: 1, Bash: 2 }), `Unexpected tool error distribution: ${JSON.stringify(toolLifecycle.errorTrueByTool)}`);
const compositeToolKeys = [...toolLifecycleRecords.keys()];
const bareToolIds = [...new Set(compositeToolKeys.map((key) => key.split(':').at(-1)))];
const browserCompositeToolKeys = compositeToolKeys.filter((key) => key.split(':').at(-1).startsWith('WebBrowser_'));
const browserDownstreamByTool = new Map();
for (const row of mdcRows) {
  if (!row.toolUseId?.startsWith('WebBrowser_') || !row.raw.includes('Python POST request:')) continue;
  const downstreamRequestId = row.raw.match(/requestId=([^, ]+)/)?.[1];
  if (!downstreamRequestId) continue;
  const key = `${row.sessionId}:${row.runId}:${row.toolUseId}`;
  if (!browserDownstreamByTool.has(key)) browserDownstreamByTool.set(key, []);
  browserDownstreamByTool.get(key).push(downstreamRequestId);
}
const downstreamRequestIds = [...browserDownstreamByTool.values()].flat();
const traceExample = {
  observedAt: '2026-08-09 01:55:36.270',
  rootSessionId: traceability.rootSessionId,
  rootRunId: traceability.rootRunId,
  workerSessionId: 'subagent-agent-64f62e42',
  workerRunId: 'b11d33bb-4d08-477b-88fa-977ddfba67c9',
  parentRunId: traceability.rootRunId,
  turn: 11,
  llmRequestId: 'llm-72c74176-a7d6-49e0-adb2-7f16dc9a1045',
  toolUseId: 'WebBrowser_26',
  downstreamRequestId: '97ad9951-84cb-4b7a-8c5e-a1458a3c2c9f',
  endpoint: '/api/browser/navigate',
  completedInMs: 3244,
  error: false,
};
const toolIdentity = {
  compositeKey: 'sessionId+runId+toolUseId',
  compositeToolKeys: compositeToolKeys.length,
  bareToolIds: bareToolIds.length,
  collisionsAvoidedByCompositeKey: compositeToolKeys.length - bareToolIds.length,
  webBrowserCompositeKeys: browserCompositeToolKeys.length,
  webBrowserPythonPostRecords: downstreamRequestIds.length,
  uniquePythonRequestIds: new Set(downstreamRequestIds).size,
  browserToolsWithExactlyOneDownstreamRequest: [...browserDownstreamByTool.values()].filter((ids) => ids.length === 1).length,
  browserToolsMissingDownstreamRequest: browserCompositeToolKeys.filter((key) => !browserDownstreamByTool.has(key)).length,
  browserToolsWithDuplicateDownstreamRequests: [...browserDownstreamByTool.values()].filter((ids) => ids.length !== 1).length,
  traceExample,
};
assert(toolIdentity.compositeToolKeys === 968 && toolIdentity.bareToolIds === 422, `Tool identity ${toolIdentity.compositeToolKeys}/${toolIdentity.bareToolIds} != 968/422`);
assert(toolIdentity.webBrowserCompositeKeys === 376 && toolIdentity.webBrowserPythonPostRecords === 376 && toolIdentity.uniquePythonRequestIds === 376, `Browser downstream identity ${toolIdentity.webBrowserCompositeKeys}/${toolIdentity.webBrowserPythonPostRecords}/${toolIdentity.uniquePythonRequestIds} != 376/376/376`);
assert(toolIdentity.browserToolsWithExactlyOneDownstreamRequest === 376 && toolIdentity.browserToolsMissingDownstreamRequest === 0 && toolIdentity.browserToolsWithDuplicateDownstreamRequests === 0, 'WebBrowser calls are not in a one-to-one relation with downstream Python request ids');
for (const [field, value] of Object.entries(traceExample)) {
  if (['rootSessionId', 'rootRunId', 'parentRunId'].includes(field)) continue;
  assert(appLogLines.some((line) => line.includes(String(value))), `Trace example field is absent from frozen app log: ${field}=${value}`);
}
const executionControls = {
  atomicWriteSuccesses: appLogLines.filter((line) => line.includes('com.aicodeassistant.tool.impl.AtomicFileWriter - Atomic write successful:')).length,
  checkpointSaves: appLogLines.filter((line) => line.includes('com.aicodeassistant.tool.agent.CheckpointService - Checkpoint saved:')).length,
  mcpReconnects: {
    server: 'zhipu-websearch',
    connectionLosses: 0,
    successfulReconnects: 0,
    pairedReconnects: 0,
    unpairedEvents: 0,
  },
};
let pendingMcpLosses = 0;
let orphanMcpReconnects = 0;
let maximumPendingMcpLosses = 0;
for (const line of appLogLines) {
  if (line.includes('MCP server zhipu-websearch connection lost')) {
    executionControls.mcpReconnects.connectionLosses += 1;
    pendingMcpLosses += 1;
    maximumPendingMcpLosses = Math.max(maximumPendingMcpLosses, pendingMcpLosses);
  }
  if (line.includes('MCP server zhipu-websearch reconnected successfully')) {
    executionControls.mcpReconnects.successfulReconnects += 1;
    if (pendingMcpLosses > 0) {
      pendingMcpLosses -= 1;
      executionControls.mcpReconnects.pairedReconnects += 1;
    } else {
      orphanMcpReconnects += 1;
    }
  }
}
executionControls.mcpReconnects.unpairedEvents = pendingMcpLosses + orphanMcpReconnects;
assert(executionControls.atomicWriteSuccesses === 267, `Atomic write successes ${executionControls.atomicWriteSuccesses} != 267`);
assert(executionControls.checkpointSaves === 157, `Checkpoint saves ${executionControls.checkpointSaves} != 157`);
assert(executionControls.mcpReconnects.connectionLosses === 60, `MCP connection losses ${executionControls.mcpReconnects.connectionLosses} != 60`);
assert(executionControls.mcpReconnects.successfulReconnects === 60, `MCP successful reconnects ${executionControls.mcpReconnects.successfulReconnects} != 60`);
assert(executionControls.mcpReconnects.pairedReconnects === 60, `MCP reconnect pairs ${executionControls.mcpReconnects.pairedReconnects} != 60`);
assert(executionControls.mcpReconnects.unpairedEvents === 0, `MCP unpaired events ${executionControls.mcpReconnects.unpairedEvents} != 0`);
assert(maximumPendingMcpLosses === 1, `MCP reconnect sequence contains overlapping losses (max pending ${maximumPendingMcpLosses})`);
const deadlineTerminationEvents = observability.filter((event) => event.eventType === 'run_termination_summary' && event.data.reason === 'deadline_exceeded');
const maxTurnSubagentEvents = observability.filter((event) => event.eventType === 'subagent_failed' && event.data.status === 'max_turns');
const failedLlmWithLaterGlobalStart = failedLlmRequests.filter((request) => {
  const failureIndex = observability.findIndex((event) => event.eventType === 'llm_call_failed' && event.data.requestId === request.requestId);
  return observability.slice(failureIndex + 1).some((event) => event.eventType === 'llm_call_started');
}).length;
const failureContinuation = {
  llm: {
    failed: failedLlmRequests.length,
    errorType: 'cancelled',
    statusCode: 0,
    attemptCount: 1,
    failedRequestsWithLaterGlobalLlmStart: failedLlmWithLaterGlobalStart,
    sameRequestIdRetryClaimed: false,
  },
  tools: { errorTrue: toolLifecycle.errorTrue, errorTrueByTool: toolLifecycle.errorTrueByTool },
  workers: {
    started: eventCounts.subagent_started,
    naturalCompletions: eventCounts.subagent_completed - deadlineTerminationEvents.length,
    deadlineReclaims: deadlineTerminationEvents.length,
    maxTurnsReclaims: maxTurnSubagentEvents.length,
  },
  mcp: executionControls.mcpReconnects,
  causalRecoveryInferred: false,
  boundary: 'Later activity is a temporal continuation observation, not proof that a specific failure was automatically repaired or that no intermediate generation was lost.',
};
assert(failureContinuation.llm.failedRequestsWithLaterGlobalLlmStart === 5, `Only ${failureContinuation.llm.failedRequestsWithLaterGlobalLlmStart}/5 failed LLM requests have later global LLM activity`);
assert(failureContinuation.workers.naturalCompletions === 1 && failureContinuation.workers.deadlineReclaims === 6 && failureContinuation.workers.maxTurnsReclaims === 3, `Worker terminal semantics differ: ${JSON.stringify(failureContinuation.workers)}`);
const localTimestamp = /^(2026-08-09 \d{2}:\d{2}:\d{2}\.\d{3})/;
for (const [name, path] of [['app', appLogPath], ['security', securityLogPath]]) {
  const timestamps = text(path).split('\n').map((line) => line.match(localTimestamp)?.[1]).filter(Boolean);
  assert(timestamps.every((value) => value >= '2026-08-09 01:30:00.000' && value < '2026-08-09 07:01:00.000'), `${name} log contains out-of-window timestamps`);
}
assert(observability.every((event) => event.timestamp >= '2026-08-09T01:30:00' && event.timestamp < '2026-08-09T07:01:00'), 'Observability contains out-of-window timestamps');

const sourceAppGzip = join(repoRoot, 'log/app-2026-08-09-1.log.gz');
const sourceAppCurrent = join(repoRoot, 'log/app.log');
const logSourceRebuild = { app: null, security: null, observability: null };
const archivedCurrentCandidates = [
  sourceAppCurrent,
  join(repoRoot, 'log/app-2026-08-09-2.log.gz'),
].filter((path, index, paths) => existsSync(path) && paths.indexOf(path) === index);
let appSourceResolution = null;
if (existsSync(sourceAppGzip) && archivedCurrentCandidates.length) {
  const rotatedSource = gunzipSync(readFileSync(sourceAppGzip)).toString('utf8');
  for (const candidate of archivedCurrentCandidates) {
    const candidateSource = candidate.endsWith('.gz')
      ? gunzipSync(readFileSync(candidate)).toString('utf8')
      : text(candidate);
    const rebuiltRaw = filterTimestampBlocks([
      rotatedSource,
      candidateSource,
    ], /^(2026-08-09 \d{2}:\d{2}:\d{2}\.\d{3})/, '2026-08-09 01:30:00.000', '2026-08-09 07:01:00.000');
    let rebuilt = rebuiltRaw;
    if (localUserHome) rebuilt = rebuilt.replaceAll(localUserHome, '<USER_HOME>');
    if (localUserName) rebuilt = rebuilt.replaceAll(localUserName, '<LOCAL_USER>');
    if (createHash('sha256').update(rebuilt).digest('hex') === sha256(appLogPath)) {
      appSourceResolution = {
        captureIdentity: relative(repoRoot, candidate),
        resolvedPath: relative(repoRoot, candidate),
        rotationAware: candidate !== sourceAppCurrent,
        selectedFragmentSha256: createHash('sha256').update(filterTimestampBlocks([
          candidateSource,
        ], /^(2026-08-09 \d{2}:\d{2}:\d{2}\.\d{3})/, '2026-08-09 01:30:00.000', '2026-08-09 07:01:00.000')).digest('hex'),
      };
      break;
    }
  }
  logSourceRebuild.app = Boolean(appSourceResolution);
  assert(logSourceRebuild.app, 'Public app log cannot be deterministically rebuilt and minimally redacted from the captured source or its byte-equivalent rotated archive');
}
for (const [key, sourceName, frozenPath, timestampPattern, start, end] of [
  ['security', 'log/security-audit.log', securityLogPath, /^(2026-08-09 \d{2}:\d{2}:\d{2}\.\d{3})/, '2026-08-09 01:30:00.000', '2026-08-09 07:01:00.000'],
  ['observability', 'log/observability-events.log', join(assetRoot, 'logs/observability-events-20260809-0130-0701.jsonl'), /^(2026-08-09T\d{2}:\d{2}:\d{2}\.\d{3})/, '2026-08-09T01:30:00.000', '2026-08-09T07:01:00.000'],
]) {
  const sourcePath = join(repoRoot, sourceName);
  if (existsSync(sourcePath)) {
    const rebuilt = filterTimestampBlocks([text(sourcePath)], timestampPattern, start, end);
    logSourceRebuild[key] = createHash('sha256').update(rebuilt).digest('hex') === sha256(frozenPath);
    assert(logSourceRebuild[key], `Frozen ${sourceName} cannot be deterministically rebuilt from its source log`);
  }
}

const provenance = json(join(assetRoot, 'provenance.json'));
const outsideWindowRecovery = null;
assert(!provenance.logs?.outsideWindowSupplements?.length, 'Public provenance must not register unrelated outside-window recovery material');
const onlineDeployment = provenance.onlineDeployment;
const standardOnlineUrl = 'https://king.zhikun.xin/';
const demoOnlineUrl = 'https://king.zhikun.xin/?demo=1';
assert(onlineDeployment?.classification === 'OUTSIDE_DEVELOPMENT_WINDOW_ALIYUN_HTTPS_DEPLOYMENT', 'Online deployment classification differs');
assert(onlineDeployment?.deploymentRelationship === 'SAME_STATIC_BUILD_WITH_RUNTIME_QUERY_PARAMETER', 'Online deployment relationship differs');
assert(onlineDeployment?.standard?.url === standardOnlineUrl && onlineDeployment?.demo?.url === demoOnlineUrl, 'Online deployment URLs differ');
assert(onlineDeployment?.standard?.expectedHeroCards === 5 && onlineDeployment?.standard?.expectedTitle === 'KING_OK' && onlineDeployment?.demo?.expectedTitle === 'KING_OK', 'Online deployment expected results differ');
const demoSourceBindings = new Map((onlineDeployment?.sourceBindings ?? []).map((binding) => [binding.path, binding.symbols]));
for (const [path, symbols] of [
  ['code/src/main.js', ["params.get('demo') === '1'", 'state.enableDemo()']],
  ['code/src/game/state.js', ['enableDemo()', '_demoControl(dt)', '_demoSkills(p, target)']],
]) {
  assert(JSON.stringify(demoSourceBindings.get(path)) === JSON.stringify(symbols), `Online deployment source binding differs for ${path}`);
  const sourceValue = text(join(assetRoot, path));
  for (const symbol of symbols) assert(sourceValue.includes(symbol), `Demo behavior symbol not found in ${path}: ${symbol}`);
}

const toolKeys = new Set();
for (const line of text(appLogPath).split('\n')) {
  const match = line.match(/sid=([^\s]+) rid=([^\s]+).*?tool=([A-Za-z]+)_([0-9]+)/);
  if (match) toolKeys.add(`${match[1]}:${match[2]}:${match[3]}_${match[4]}`);
}
const toolCounts = {};
for (const key of toolKeys) {
  const type = key.match(/:([A-Za-z]+)_\d+$/)?.[1];
  if (type) toolCounts[type] = (toolCounts[type] ?? 0) + 1;
}
const expectedTools = { WebBrowser: 376, Edit: 185, Read: 162, Bash: 102, Sleep: 61, Write: 37, Grep: 20, TodoWrite: 11, Agent: 10, Snip: 1, Glob: 1, CodeIntel: 1, AskUserQuestion: 1 };
assert(toolKeys.size === 968, `Unique tool calls ${toolKeys.size} != 968`);
assert(JSON.stringify(Object.fromEntries(Object.entries(toolCounts).sort())) === JSON.stringify(Object.fromEntries(Object.entries(expectedTools).sort())), 'Tool distribution differs from expected');

const platformSnapshot = analyzePlatformSnapshot();
const sweBenchRoot = join(repoRoot, 'docs/swe-bench/20260525');
const sweBenchResults = json(join(sweBenchRoot, 'results.json'));
const sweBenchPredictions = text(join(sweBenchRoot, 'all_preds.jsonl')).trim().split('\n').map((line) => JSON.parse(line));
const sweBenchMetadata = text(join(sweBenchRoot, 'metadata.yaml'));
const sweBenchReport = text(join(repoRoot, 'docs/swe-bench-report.html'));
const externalBaseline = {
  name: 'SWE-bench Lite',
  relationshipToCase: 'SEPARATE_SELF_RUN_PLATFORM_BASELINE_NOT_A_CASE_CONTROL_GROUP',
  evaluation: 'official SWE-bench Lite harness (self-run artifacts)',
  totalInstances: sweBenchResults.total_instances,
  submittedInstances: sweBenchResults.submitted_instances,
  resolvedInstances: sweBenchResults.resolved_instances,
  resolveRatePercent: Number((sweBenchResults.resolved_instances / sweBenchResults.total_instances * 100).toFixed(1)),
  nonEmptyPatches: sweBenchPredictions.filter((prediction) => String(prediction.model_patch ?? '').trim()).length,
  patchGenerationRatePercent: Number((sweBenchPredictions.filter((prediction) => String(prediction.model_patch ?? '').trim()).length / sweBenchPredictions.length * 100).toFixed(1)),
  model: 'qwen3.7-max',
  tools: ['Read', 'Edit', 'Write', 'Bash', 'Grep', 'Glob'],
  networkAccess: false,
  subAgentDelegation: false,
  metadataCheckedByExternalMaintainer: false,
  evidence: [
    'docs/swe-bench/20260525/results.json',
    'docs/swe-bench/20260525/all_preds.jsonl',
    'docs/swe-bench/20260525/metadata.yaml',
    'docs/swe-bench-report.html',
  ],
};
assert(externalBaseline.totalInstances === 300 && externalBaseline.submittedInstances === 300, 'SWE-bench instance totals differ');
assert(externalBaseline.resolvedInstances === 168 && externalBaseline.resolveRatePercent === 56.0, 'SWE-bench resolved result differs');
assert(externalBaseline.nonEmptyPatches === 284 && externalBaseline.patchGenerationRatePercent === 94.7, 'SWE-bench patch generation result differs');
assert(sweBenchPredictions.length === 300, `SWE-bench prediction rows ${sweBenchPredictions.length} != 300`);
assert(sweBenchMetadata.includes('qwen3.7-max'), 'SWE-bench metadata model differs');
for (const statement of ['closed set of six tools', 'no internet access', 'no sub-agent delegation']) {
  assert(sweBenchReport.includes(statement), `SWE-bench report is missing configuration statement: ${statement}`);
}

const screenshots = walkFiles(join(assetRoot, 'screenshots')).filter((path) => extname(path).toLowerCase() === '.png');
const screenshotHashes = screenshots.map(sha256);
const screenshotsStats = { files: screenshots.length, uniqueContent: new Set(screenshotHashes).size };
assert(screenshotsStats.files === 43, `Screenshot files ${screenshotsStats.files} != 43`);
assert(screenshotsStats.uniqueContent === 42, `Unique screenshots ${screenshotsStats.uniqueContent} != 42`);

const browserVerification = json(join(assetRoot, 'browser-verification.json'));
assert(browserVerification.schemaVersion === 10 && browserVerification.reportVersion === 'v15', 'Final browser verification must be the current v15 schema 10 snapshot');
assert(browserVerification.classification === 'OUTSIDE_DEVELOPMENT_WINDOW_CURRENT_REPORT_BROWSER_VERIFICATION', 'Browser verification classification differs');
assert(browserVerification.report?.sha256 === sha256(caseHtmlPath) && browserVerification.report?.bytes === statSync(caseHtmlPath).size, 'Browser verification is not bound to the current HTML SHA/size');
assert(JSON.stringify(browserVerification.viewports?.map((entry) => [entry.width, entry.height])) === JSON.stringify([[1280, 720], [1440, 900], [1920, 1080], [390, 844]]), 'Browser verification viewport set differs');
for (const viewport of browserVerification.viewports ?? []) {
  assert(viewport.figureCount === 91 && viewport.svgCount === 91, `${viewport.width}x${viewport.height} browser figure count differs`);
  assert(viewport.pageOverflowPx === 0 && viewport.consoleErrors === 0, `${viewport.width}x${viewport.height} browser page/console regression failed`);
  if (viewport.width >= 1280) assert(viewport.stageOverflowIds?.length === 0, `${viewport.width}x${viewport.height} contains SVG-stage overflow`);
}
assert(browserVerification.content?.figures === 91 && browserVerification.content?.svgs === 91, 'Browser content figure totals differ');
assert(browserVerification.content?.evidenceLedgerRows === 42 && browserVerification.content?.claimBoundaryRows === 19, 'Browser evidence/claim row counts differ');
assert(browserVerification.content?.tables === 39 && browserVerification.content?.preformattedBlocks === 25, 'Browser table/preformatted counts differ');
assert(browserVerification.content?.details === browserVerification.content?.openDetails && browserVerification.content?.details > 0, 'Browser verification does not show the complete audit corpus open by default');
assert(Object.values(browserVerification.interactions ?? {}).every((value) => value === true), 'Browser interaction regression failed');
assert(browserVerification.media?.failedHtmlImages === 0 && browserVerification.media?.failedSvgImages === 0, 'Browser image loading regression failed');
assert(browserVerification.media?.previewVideos === 5 && browserVerification.media?.previewVideosReady === 5 && browserVerification.media?.previewVideoErrors === 0, 'Browser video readiness differs');
assert(browserVerification.semantics?.platV09PublicationNode === false, 'PLAT-V09 browser snapshot still contains a publication supplement node');
assert(JSON.stringify(browserVerification.semantics?.platV09Entities) === JSON.stringify(['sessions', 'messages', 'activities', 'interaction_requests']), 'PLAT-V09 browser entity list differs');
assert(browserVerification.onlineDeployment?.classification === onlineDeployment.classification, 'Browser online deployment classification differs');
assert(browserVerification.onlineDeployment?.standard?.url === standardOnlineUrl, 'Browser standard online URL differs');
assert(browserVerification.onlineDeployment?.standard?.title === 'KING_OK', 'Browser standard online title differs');
assert(browserVerification.onlineDeployment?.standard?.heroSelectContainer === true && browserVerification.onlineDeployment?.standard?.heroCards === 5, 'Browser standard hero selection result differs');
assert(browserVerification.onlineDeployment?.standard?.consoleErrors === 0 && browserVerification.onlineDeployment?.standard?.resourceErrors === 0, 'Browser standard online page has errors');
assert(browserVerification.onlineDeployment?.demo?.url === demoOnlineUrl, 'Browser Demo online URL differs');
assert(browserVerification.onlineDeployment?.demo?.title === 'KING_OK', 'Browser Demo online title differs');
assert(browserVerification.onlineDeployment?.demo?.heroSelectContainer === false && browserVerification.onlineDeployment?.demo?.timerAdvanced === true, 'Browser Demo auto-start result differs');
assert(browserVerification.onlineDeployment?.demo?.canvasCount >= 2, 'Browser Demo canvas result differs');
assert(browserVerification.onlineDeployment?.demo?.consoleErrors === 0 && browserVerification.onlineDeployment?.demo?.resourceErrors === 0, 'Browser Demo online page has errors');

const releaseAssets = json(join(assetRoot, 'release-assets.json'));
assert(releaseAssets.assets.length === 5, 'Release asset mapping must contain five originals');
assert(releaseAssets.schemaVersion === 2, 'Release asset mapping schema must be v2');
assert(releaseAssets.publicationStatus === 'PENDING_USER_APPROVAL', 'Release mapping must remain pending until user approval');
assert(releaseAssets.assets.filter((asset) => asset.classification === 'DEVELOPMENT_WINDOW').length === 3, 'Expected three development-window recordings');
assert(releaseAssets.assets.filter((asset) => asset.classification.startsWith('OUTSIDE_DEVELOPMENT_WINDOW_')).length === 2, 'Expected two outside-window recording supplements');
const previewStats = [];
let locallyVerifiedOriginals = 0;
const expectedReleaseAssetNames = [
  'king-development-013111-original.mp4',
  'king-gameplay-054310-original.mp4',
  'king-visual-fix-060909-original.mp4',
  'king-final-run-092155-original.mp4',
  'king-cloud-demo-115336-original.mp4',
];
assert(JSON.stringify(releaseAssets.assets.map((asset) => asset.releaseAssetName)) === JSON.stringify(expectedReleaseAssetNames), 'Release ASCII asset names differ');
for (const asset of releaseAssets.assets) {
  const original = join(assetRoot, asset.originalPath);
  if (existsSync(original)) {
    locallyVerifiedOriginals += 1;
    assert(sha256(original) === asset.originalSha256, `Original video hash mismatch ${asset.originalPath}`);
  }
  if (asset.previewPath) {
    const preview = join(assetRoot, asset.previewPath);
    const media = ffprobe(preview);
    previewStats.push({ path: asset.previewPath, ...media });
    assert(sha256(preview) === asset.previewSha256, `Preview video hash mismatch ${asset.previewPath}`);
    assert(media.videoCodec === 'h264', `Preview is not H.264: ${asset.previewPath}`);
    assert(media.width === 960 && media.height === 540, `Preview is not 960x540: ${asset.previewPath}`);
    assert(media.sizeBytes < 45 * 1024 * 1024, `Preview exceeds 45 MiB: ${asset.previewPath}`);
    assert(asset.releaseAvailability === 'PENDING_RELEASE_CREATION' && asset.plannedReleaseUrl === `https://github.com/zhikunqingtao/zhikuncode/releases/download/king-evidence-v1/${asset.releaseAssetName}`, `Release URL state is inconsistent for ${asset.original}`);
    assert(!Object.hasOwn(asset, 'releaseUrl'), `Obsolete releaseUrl remains for ${asset.original}`);
  }
}
assert(previewStats.length === 5, `Preview video count ${previewStats.length} != 5`);

const html = text(caseHtmlPath);
const ids = [...html.matchAll(/(?:^|\s)id="([^"]+)"/g)].map((match) => match[1]);
const duplicateIds = [...new Set(ids.filter((id, index) => ids.indexOf(id) !== index))];
assert(duplicateIds.length === 0, `Duplicate HTML ids: ${duplicateIds.join(', ')}`);
const logFigureHtml = [...html.matchAll(/<figure class="log-viz"[\s\S]*?<\/figure>/g)].map((match) => match[0]);
const expectedLogFigureIds = ['LOG-V01', 'LOG-V03', 'LOG-V02'];
const logFigureIds = logFigureHtml.map((figure) => figure.match(/data-viz-code="([^"]+)"/)?.[1]).filter(Boolean);
assert(JSON.stringify(logFigureIds) === JSON.stringify(expectedLogFigureIds), `Log figure document sequence differs: ${logFigureIds.join(', ')}`);
const logVisualizations = logFigureHtml.map((figure, index) => {
  const id = logFigureIds[index];
  const svgMarkup = figure.match(/<svg\b[\s\S]*?<\/svg>/)?.[0] ?? '';
  const viewBox = svgMarkup.match(/viewBox="0 0 (\d+) (\d+)"/)?.slice(1).map(Number) ?? [];
  const title = svgMarkup.match(/<title\b[^>]*>([\s\S]*?)<\/title>/)?.[1]?.trim() ?? '';
  const description = svgMarkup.match(/<desc\b[^>]*>([\s\S]*?)<\/desc>/)?.[1]?.trim() ?? '';
  const proofScope = figure.match(/<p><b>(?:画面与代码对应|能证明)：<\/b>([\s\S]*?)<\/p>/)?.[1]?.replace(/<[^>]+>/g, '').trim() ?? '';
  const cannotProve = previousLogVisualizations.get(id)?.cannotProve ?? '';
  const textLabels = countMatches(svgMarkup, /<text\b/g);
  const interactiveNodes = countMatches(svgMarkup, /class="pv-node\b/g);
  const svgBytes = Buffer.byteLength(svgMarkup);
  assert(countMatches(figure, /<svg\b/g) === 1, `${id} does not contain exactly one SVG`);
  assert(Boolean(title) && Boolean(description), `${id} lacks an SVG title or description`);
  assert(viewBox[0] === 1200 && viewBox[1] >= 790, `${id} does not use the 1200px PC-first audit canvas`);
  assert(svgBytes >= 6500 && textLabels >= 35 && interactiveNodes >= 5, `${id} is too sparse: ${svgBytes} bytes, ${textLabels} labels, ${interactiveNodes} nodes`);
  assert(Boolean(proofScope) && Boolean(cannotProve), `${id} lacks visible proof scope or machine guardrail`);
  assert(svgMarkup.includes('data-viz-rich="true"'), `${id} is not marked source-rich`);
  return { id, evidenceType: figure.match(/data-evidence="([^"]+)"/)?.[1] ?? null, title, description, proofScope, cannotProve, richness: { svgBytes, textLabels, interactiveNodes, viewBox } };
});
const logVisualizationRichness = {
  figures: logVisualizations.length,
  totalSvgBytes: logVisualizations.reduce((sum, item) => sum + item.richness.svgBytes, 0),
  totalTextLabels: logVisualizations.reduce((sum, item) => sum + item.richness.textLabels, 0),
  totalInteractiveNodes: logVisualizations.reduce((sum, item) => sum + item.richness.interactiveNodes, 0),
  pcFirstCanvases: logVisualizations.filter((item) => item.richness.viewBox[0] === 1200).length,
  generatedBy: 'scripts/enhance-king-log-viz.mjs',
};
assert(logVisualizationRichness.figures === 3 && logVisualizationRichness.pcFirstCanvases === 3, 'Log visualization count or PC-first canvas count differs');
const productFigureHtml = [...html.matchAll(/<figure class="product-viz"[\s\S]*?<\/figure>/g)].map((match) => match[0]);
const expectedProductFigureIds = Array.from({ length: 26 }, (_, index) => `KING-V${String(index + 1).padStart(2, '0')}`);
const productFigureIds = productFigureHtml.map((figure) => figure.match(/data-viz-code="([^"]+)"/)?.[1]).filter(Boolean);
assert(JSON.stringify(productFigureIds) === JSON.stringify(expectedProductFigureIds), `Product figure sequence differs: ${productFigureIds.join(', ')}`);
const productSourceFiles = {
  'KING-V01': ['src/main.js', 'src/game/state.js', 'src/ui/hud.js'],
  'KING-V02': ['src/main.js', 'src/game/state.js', 'src/game/ai.js', 'src/game/skills.js', 'src/game/spawner.js', 'src/game/shop.js', 'src/world/map.js', 'src/world/models.js', 'src/engine/vfx.js', 'src/ui/hud.js', 'src/ui/minimap.js'],
  'KING-V03': ['src/main.js', 'src/game/state.js'],
  'KING-V04': ['src/main.js', 'src/config.js'],
  'KING-V05': ['src/config.js', 'src/world/map.js'],
  'KING-V06': ['src/config.js', 'src/world/map.js', 'src/engine/renderer.js'],
  'KING-V07': ['src/config.js', 'src/world/map.js', 'src/game/state.js', 'src/game/ai.js'],
  'KING-V08': ['src/config.js', 'src/game/spawner.js', 'src/game/ai.js', 'src/game/state.js'],
  'KING-V09': ['src/config.js', 'src/main.js'],
  'KING-V10': ['src/game/state.js'],
  'KING-V11': ['src/game/state.js', 'src/game/spawner.js', 'src/game/ai.js'],
  'KING-V12': ['src/config.js', 'src/game/state.js'],
  'KING-V13': ['src/game/ai.js'],
  'KING-V14': ['src/config.js', 'src/game/ai.js', 'src/game/spawner.js'],
  'KING-V15': ['src/game/ai.js', 'src/game/state.js'],
  'KING-V16': ['src/game/skills.js'],
  'KING-V17': ['src/game/skills.js'],
  'KING-V18': ['src/main.js', 'src/game/state.js', 'src/game/skills.js', 'src/engine/vfx.js', 'src/engine/audio.js', 'src/ui/hud.js'],
  'KING-V19': ['src/config.js', 'src/game/state.js', 'src/game/shop.js'],
  'KING-V20': ['src/main.js', 'src/world/models.js', 'src/game/ai.js', 'src/ui/hud.js', 'src/ui/minimap.js', 'src/game/shop.js'],
  'KING-V21': ['src/ui/screens.js', 'src/game/state.js'],
  'KING-V22': ['src/config.js', 'src/game/state.js', 'src/game/shop.js'],
  'KING-V23': ['src/config.js', 'src/game/spawner.js', 'src/game/state.js'],
  'KING-V24': ['src/world/map.js', 'src/world/models.js', 'src/engine/vfx.js', 'src/engine/audio.js'],
  'KING-V25': ['src/main.js', 'src/ui/hud.js', 'src/ui/minimap.js', 'src/ui/screens.js', 'src/game/shop.js'],
  'KING-V26': ['src/config.js', 'src/main.js', 'src/game/state.js'],
};
const screenshotOverlayFigureIds = new Set(['KING-V01', 'KING-V06', 'KING-V07', 'KING-V08', 'KING-V20', 'KING-V25']);
const cleanMarkup = (value) => value.replace(/<[^>]+>/g, '').replace(/&gt;/g, '>').replace(/&lt;/g, '<').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
const productVisualizations = productFigureHtml.map((figure, index) => {
  const id = productFigureIds[index];
  const svgCount = countMatches(figure, /<svg\b/g);
  const svgMarkup = figure.match(/<svg\b[\s\S]*?<\/svg>/)?.[0] ?? '';
  const titleMatch = figure.match(/<title\b[^>]*>([\s\S]*?)<\/title>/);
  const descMatch = figure.match(/<desc\b[^>]*>([\s\S]*?)<\/desc>/);
  const evidenceType = figure.match(/data-evidence="([^"]+)"/)?.[1] ?? null;
  const proofScope = cleanMarkup(figure.match(/<p><b>(?:画面与代码对应|能证明)：<\/b>([\s\S]*?)<\/p>/)?.[1] ?? '');
  const cannotProve = previousProductVisualizations.get(id)?.cannotProve ?? '';
  const screenshotPaths = [...figure.matchAll(/<img\s+[^>]*src="([^"]+)"/g)].map((match) => match[1].replace(/^assets\/king\//, ''));
  const exactCode = figure.match(/data-code-exact="([^"]+)"/)?.[1] ?? null;
  const sourceFiles = productSourceFiles[id] ?? [];
  const svgBytes = Buffer.byteLength(svgMarkup);
  const textLabels = countMatches(svgMarkup, /<text\b/g);
  const interactiveNodes = countMatches(svgMarkup, /class="pv-node\b/g);
  const sourceSymbolLabels = countMatches(svgMarkup, /(?:src\/|\.js(?::|\b)|GameState|HeroAI|SkillSystem|update\(|dealDamage|onUnitDied|queueAction|showResult)/g);
  const flowEdges = countMatches(svgMarkup, /class="vr-edge(?:-|\b)/g);
  const calloutPanels = countMatches(svgMarkup, /class="vr-callout(?:-|\b)/g);
  const sourceRails = countMatches(svgMarkup, /class="vr-source-rail\b/g);
  const viewBox = svgMarkup.match(/viewBox="0 0 (\d+) (\d+)"/)?.slice(1).map(Number) ?? [];
  assert(svgCount === 1, `${id} contains ${svgCount} SVG elements instead of one`);
  assert(Boolean(titleMatch?.[1]?.trim()) && Boolean(descMatch?.[1]?.trim()), `${id} is missing a non-empty SVG title or description`);
  assert(Boolean(evidenceType), `${id} has no data-evidence source type`);
  assert(Boolean(proofScope) && Boolean(cannotProve), `${id} is missing visible proof scope or machine guardrail`);
  assert(sourceFiles.length > 0, `${id} has no registered frozen source files`);
  assert(svgMarkup.includes('data-viz-rich="true"'), `${id} is not marked as a source-rich visualization`);
  assert(svgBytes >= 5500, `${id} SVG is too sparse (${svgBytes} bytes)`);
  assert(textLabels >= 28, `${id} has too few direct labels (${textLabels})`);
  assert(interactiveNodes >= 4, `${id} has too few inspectable nodes (${interactiveNodes})`);
  assert(sourceSymbolLabels >= 4, `${id} has too few visible source symbols (${sourceSymbolLabels})`);
  assert(viewBox[0] === 1200 && viewBox[1] >= 700, `${id} does not use the professional 1200px source-audit canvas`);
  if (screenshotOverlayFigureIds.has(id)) {
    assert(calloutPanels >= 7, `${id} screenshot overlay has too few direct callouts (${calloutPanels})`);
    assert(svgMarkup.includes('vr-target') && svgMarkup.includes('vr-leader'), `${id} screenshot overlay lacks target rings or leader paths`);
  } else {
    assert(sourceRails >= 1, `${id} requires a frozen-source rail`);
  }
  for (const sourceFile of sourceFiles) assert(existsSync(join(assetRoot, 'code', sourceFile)), `${id} source file does not exist: ${sourceFile}`);
  return {
    id,
    evidenceType,
    title: cleanMarkup(titleMatch?.[1] ?? ''),
    description: cleanMarkup(descMatch?.[1] ?? ''),
    screenshotPaths,
    screenshotSha256: screenshotPaths.map((path) => sha256(join(assetRoot, path))),
    sourceFiles,
    codeSymbolOrExcerpt: exactCode,
    proofScope,
    cannotProve,
    richness: { svgBytes, textLabels, interactiveNodes, sourceSymbolLabels, flowEdges, calloutPanels, sourceRails, viewBox },
  };
});
assert(productVisualizations.length === 26, `Product visualization count ${productVisualizations.length} != 26`);
const productVisualizationRichness = {
  figures: productVisualizations.length,
  totalSvgBytes: productVisualizations.reduce((sum, item) => sum + item.richness.svgBytes, 0),
  totalTextLabels: productVisualizations.reduce((sum, item) => sum + item.richness.textLabels, 0),
  totalInteractiveNodes: productVisualizations.reduce((sum, item) => sum + item.richness.interactiveNodes, 0),
  totalSourceSymbolLabels: productVisualizations.reduce((sum, item) => sum + item.richness.sourceSymbolLabels, 0),
  screenshotOverlays: screenshotOverlayFigureIds.size,
  professionalCanvasCount: productVisualizations.filter((item) => item.richness.viewBox[0] === 1200 && item.richness.viewBox[1] >= 700).length,
  generatedBy: 'scripts/enhance-king-product-viz.mjs',
};
assert(productVisualizationRichness.professionalCanvasCount === 26, 'Not every product visualization uses the professional source-audit canvas');
assert(productVisualizationRichness.totalTextLabels === 1239, `Product SVG label total ${productVisualizationRichness.totalTextLabels} != 1239`);
assert(productVisualizationRichness.totalInteractiveNodes === 273, `Product inspectable node total ${productVisualizationRichness.totalInteractiveNodes} != 273`);
assert(html.includes('1,239个直接标签与273个检查节点'), 'PC-first source-rich browser summary is missing');
assert(!html.includes('FINAL_SIZE') && !html.includes('__SIZE__') && !html.includes('生成后复算'), 'Report contains an unresolved size placeholder');
const svgReferences = [...html.matchAll(/url\(#([^\)]+)\)/g)].map((match) => match[1]);
for (const reference of svgReferences) assert(ids.includes(reference), `Undefined SVG url reference: #${reference}`);

const codeImageBindings = [
  { id: 'KING-V01', screenshots: [{ path: 'screenshots/screenshot_b8f86099-452d-4ba6-89c2-c3fee8f4b422_1786228379180.png', sha256: 'bf7fb809ccebfba622f07c39cb00f3151ca145aa911037ccd6ec1b9ac4c0f504' }], sourceFile: 'src/main.js', exactCode: 'state = new GameState({ scene: engine.scene, mapData, vfx });' },
  { id: 'KING-V06', screenshots: [{ path: 'screenshots/phase1-map/shot6_overview_river_center.png', sha256: '815a9c23b57fd624d56d92b49d6f70825054fafdeafd3a6c660d931e45401ec3' }], sourceFile: 'src/world/map.js', exactCode: 'const tex = new THREE.CanvasTexture(cv);' },
  { id: 'KING-V07', screenshots: [{ path: 'screenshots/phase1-map/shot5_enemy_base_crystal.png', sha256: 'f6e4a5de7193a850fa58736e2132d009c0e674d44810798e04878145a1c71f86' }], sourceFile: 'src/game/state.js', exactCode: 'isCrystalInvuln(crystal) {' },
  { id: 'KING-V08', screenshots: [{ path: 'screenshots/phase1-map/shot3_river_pit_brush.png', sha256: '28ef594d935e3d81543cdfec9743e2cfd07e3f8f8d6de7a302fd16d9de136bd9' }], sourceFile: 'src/game/spawner.js', exactCode: "this.objectives = this.camps.filter(c => c.id === 'tyrant' || c.id === 'overlord');" },
  { id: 'KING-V20', screenshots: [{ path: 'screenshots/screenshot_b8f86099-452d-4ba6-89c2-c3fee8f4b422_1786228379180.png', sha256: 'bf7fb809ccebfba622f07c39cb00f3151ca145aa911037ccd6ec1b9ac4c0f504' }], sourceFile: 'src/main.js', exactCode: 'hud.onSkill = (slot) => state.queueAction(slot);' },
  { id: 'KING-V21', screenshots: [{ path: 'screenshots/screenshot_qa_1786228500285.png', sha256: '3f317e81814fe3de79a048d23a245ecb3151519863becd1a400e7eb055c60cf6' }, { path: 'screenshots/screenshot_qa_1786229287687.png', sha256: '0ad8494dc876a46ffda9d0a23143e874b1af3d2059bcd5485db5330ec43b010d' }], sourceFile: 'src/ui/screens.js', exactCode: 'showResult() {' },
  { id: 'KING-V25', screenshots: [{ path: 'screenshots/screenshot_b8f86099-452d-4ba6-89c2-c3fee8f4b422_1786225061947.png', sha256: '42a4cfe4fe0f6e71fe76b764a024789e53184f6fae2371276644855928c4ed13' }], sourceFile: 'src/main.js', exactCode: 'minimap.update(state, dt);' },
];
assert(codeImageBindings.length === 7, `Code-image binding count ${codeImageBindings.length} != 7`);
for (const binding of codeImageBindings) {
  const figure = productFigureHtml.find((value) => value.includes(`data-viz-code="${binding.id}"`)) ?? '';
  assert(figure.includes(`data-code-file="${binding.sourceFile}"`), `${binding.id} does not declare ${binding.sourceFile}`);
  assert(text(join(assetRoot, 'code', binding.sourceFile)).includes(binding.exactCode), `${binding.id} exact code is absent from ${binding.sourceFile}`);
  for (const screenshot of binding.screenshots) {
    const screenshotPath = join(assetRoot, screenshot.path);
    assert(figure.includes(`assets/king/${screenshot.path}`), `${binding.id} does not reference ${screenshot.path}`);
    assert(existsSync(screenshotPath) && sha256(screenshotPath) === screenshot.sha256, `${binding.id} screenshot hash differs: ${screenshot.path}`);
  }
}
const visualizationManifest = json(join(assetRoot, 'visualization-manifest.json'));
const visualizationData = json(join(assetRoot, 'visualization-data.json'));
const svgLayoutAudit = json(join(assetRoot, 'svg-layout-audit.json'));
assert(visualizationManifest.schemaVersion === 5 && visualizationManifest.reportVersion === 'v15', 'Visualization manifest version differs');
assert(visualizationData.schemaVersion === 2 && visualizationData.caseId === 'zhikuncode-king-20260809', 'Visualization data schema/case differs');
assert(svgLayoutAudit.schemaVersion === 3 && svgLayoutAudit.reportVersion === 'v15' && svgLayoutAudit.caseId === 'zhikuncode-king-20260809', 'SVG layout audit schema/report differs');
assert(svgLayoutAudit.report?.sha256 === sha256(caseHtmlPath) && svgLayoutAudit.report?.bytes === statSync(caseHtmlPath).size, 'SVG layout audit is not bound to the current HTML');
assert(svgLayoutAudit.summary.figures === 91 && svgLayoutAudit.figures.length === 91 && new Set(svgLayoutAudit.figures.map((entry) => entry.id)).size === 91, 'SVG layout audit does not cover 91 unique figures');
assert(svgLayoutAudit.summary.foregroundOverflowFigures === 0 && svgLayoutAudit.summary.textCollisionFigures === 0 && svgLayoutAudit.summary.excessiveBottomBlankFigures === 0 && svgLayoutAudit.summary.allMeasured === true, 'SVG layout audit contains an unresolved issue');
for (const entry of svgLayoutAudit.figures) {
  assert(Object.values(entry.overflow).every((value) => value <= 1), `${entry.id} exceeds the foreground overflow threshold`);
  assert(entry.textCollisionCount === 0, `${entry.id} contains a text collision`);
  const blankLimit = entry.viewBox.width >= 1400 ? .15 : .12;
  assert(entry.blankRatios.bottom <= blankLimit, `${entry.id} bottom blank ratio ${entry.blankRatios.bottom} exceeds ${blankLimit}`);
  assert(entry.manualReview === 'measured' && entry.measurement === 'DIRECT_CHROMIUM_DOM_GEOMETRY', `${entry.id} lacks direct browser geometry`);
}
assert(visualizationManifest.totalVisualizations === 91, `Visualization manifest total ${visualizationManifest.totalVisualizations} != 91`);
assert(visualizationManifest.retainedVisualizations.length === 29 && visualizationManifest.newVisualizations.length === 62, 'Retained/new visualization split differs');
assert(visualizationData.code.files.length === 19 && visualizationData.code.imports.length === 39, 'Visualization code reconstruction differs');
assert(visualizationData.execution.workers.length === 10 && visualizationData.execution.contextPoints.length === 878, 'Visualization execution reconstruction differs');
assert(visualizationData.bill.rows.length === 877 && visualizationData.media.screenshots.length === 43, 'Visualization bill/media reconstruction differs');
assert(visualizationData.logs.minuteDensity.reduce((sum, item) => sum + item.count, 0) === 38626, 'Visualization minute-density total differs');
assert(visualizationData.timeBoundaries.evidenceWindowDurationMs === 19860000 && visualizationData.timeBoundaries.evidenceWindowLabel === '5小时31分证据过滤窗口', 'Evidence-window duration/label differs');
assert(visualizationData.timeBoundaries.requestAnchorDurationMs === 19756777 && visualizationData.timeBoundaries.requestAnchorLabel === '5小时29分17秒首末请求锚点跨度', 'Request-anchor duration/label differs');
assert(JSON.stringify(visualizationData.database.activityToolTypes) === JSON.stringify([{ tool: 'WebBrowser', count: 54 }, { tool: 'Sleep', count: 18 }, { tool: 'Bash', count: 12 }, { tool: 'Agent', count: 10 }, { tool: 'Read', count: 10 }, { tool: 'Grep', count: 4 }, { tool: 'Edit', count: 2 }, { tool: 'Write', count: 2 }, { tool: 'AskUserQuestion', count: 1 }]), 'Coordinator activity type reconstruction differs');
assert(visualizationData.database.activityToolTypes.reduce((sum, entry) => sum + entry.count, 0) === 113 && visualizationData.database.activitySummaries.length === 113, 'Coordinator activity coverage differs');
assert(visualizationData.code.files.find((file) => file.path === 'src/game/state.js')?.lines === 1309, 'state.js visualization line count differs');
assert(visualizationData.code.files.find((file) => file.path === 'src/game/ai.js')?.lines === 920, 'ai.js visualization line count differs');
assert(visualizationData.code.files.find((file) => file.path === 'src/world/map.js')?.lines === 840, 'map.js visualization line count differs');
assert(visualizationData.code.files.find((file) => file.path === 'src/world/models.js')?.lines === 730, 'models.js visualization line count differs');
assert(visualizationData.code.files.find((file) => file.path === 'src/ui/hud.js')?.lines === 525, 'hud.js visualization line count differs');
assert(visualizationData.code.files.find((file) => file.path === 'src/game/skills.js')?.lines === 485, 'skills.js visualization line count differs');
assert(visualizationManifest.renderingPolicy.genericFallbackRenderer === false && visualizationManifest.renderingPolicy.arbitraryGeometry === false, 'V14 rendering policy permits fallback or arbitrary geometry');
assert(visualizationManifest.renderingPolicy.editorialShell === 'moba-broadcast-engineering-magazine' && visualizationManifest.renderingPolicy.visualSystem === 'v15-editorial-spread-1' && visualizationManifest.renderingPolicy.semanticColorOnly === true && visualizationManifest.renderingPolicy.chapterLegendOnly === true, 'V15 visual-system policy differs');
assert(visualizationManifest.renderingPolicy.auditDefaultOpen === true && visualizationManifest.renderingPolicy.reducedMotion === true && visualizationManifest.renderingPolicy.contentReductionAllowed === false, 'V15 audit/motion/content policy differs');
assert(visualizationManifest.renderingPolicy.repeatedKpiRail === false && visualizationManifest.renderingPolicy.repeatedEvidenceCards === false, 'V14 permits the removed repeated dashboard shell');
const allFigureIds = [...html.matchAll(/data-viz-code="([^"]+)"/g)].map((match) => match[1]);
assert(allFigureIds.length === 91 && new Set(allFigureIds).size === 91, `HTML visualization count/uniqueness differs: ${allFigureIds.length}/${new Set(allFigureIds).size}`);
const rendererNames = visualizationManifest.newVisualizations.map((entry) => entry.renderer);
assert(new Set(rendererNames).size === 62 && rendererNames.every((name) => /^render(?:CASE|SRC|PLAT|RUN|DBG|QA|AUDIT|META)V\d{2}$/.test(name)), 'The 62 public SVGs do not use 62 independent named renderers');
for (const entry of visualizationManifest.newVisualizations) {
  const figure = html.match(new RegExp(`<figure[^>]*data-viz-code="${entry.id}"[\\s\\S]*?<\\/figure>`))?.[0] ?? '';
  assert(Boolean(figure), `Missing v15 visualization ${entry.id}`);
  assert(countMatches(figure, /<svg\b/g) === 1, `${entry.id} does not contain exactly one SVG`);
  assert(/<title\b[^>]*>[^<]+<\/title>/.test(figure) && /<desc\b[^>]*>[^<]+<\/desc>/.test(figure), `${entry.id} lacks title/desc`);
  assert(countMatches(figure, /class="v12-footnote"/g) === 1 && countMatches(figure, /class="figure-audit"/g) === 1, `${entry.id} lacks one compact footnote or one audit layer`);
  const footnote = cleanMarkup(figure.match(/<p class="v12-footnote">([\s\S]*?)<\/p>/)?.[1] ?? '');
  const audit = figure.match(/<details[^>]*class="figure-audit"[\s\S]*?<\/details>/)?.[0] ?? '';
  const auditValues = [...audit.matchAll(/<dd>([\s\S]*?)<\/dd>/g)].map((match) => cleanMarkup(match[1]));
  assert(footnote === entry.compactFootnote, `${entry.id} compact footnote differs from manifest`);
  assert(JSON.stringify(auditValues) === JSON.stringify([entry.fullAuditSource, entry.fullAuditProves]), `${entry.id} visible audit text differs from manifest`);
  assert(entry.source === entry.fullAuditSource && entry.proves === entry.fullAuditProves && entry.cannot === entry.fullAuditCannot, `${entry.id} audit aliases lost source text`);
  assert(!figure.includes('完整限制') && !figure.includes('限制：') && !figure.includes(entry.fullAuditCannot), `${entry.id} repeats a machine guardrail in the reading layer`);
  assert(Boolean(entry.editorialRole) && Boolean(entry.visualGrammar) && Boolean(entry.chapterThesis) && Array.isArray(entry.evidenceIds) && entry.evidenceIds.length > 0, `${entry.id} lacks v15 editorial metadata`);
  assert(entry.layoutContract?.width > 0 && entry.layoutContract?.height > 0 && entry.layoutContract?.contentBottom > 0, `${entry.id} lacks a v14 layout contract`);
  assert(entry.layoutContract.contentBottom <= entry.layoutContract.height && entry.layoutContract.allowedBottomBlankRatio === (entry.layoutContract.width === 1400 ? .15 : .12), `${entry.id} layout contract is inconsistent`);
  assert(Array.isArray(entry.allowedOverlaps) && entry.allowedOverlaps.length === 0 && Array.isArray(entry.semanticRelations) && entry.semanticRelations.length > 0, `${entry.id} overlap/semantic declarations differ`);
  assert(countMatches(figure, /class="pv-node\b/g) >= 1, `${entry.id} has no keyboard-inspectable evidence node`);
  assert(figure.includes(`data-renderer="${entry.renderer}"`), `${entry.id} renderer declaration differs`);
  for (const factId of entry.visibleFactIds) assert(Boolean(visualizationData.facts[factId]), `${entry.id} references missing factId ${factId}`);
  assert(entry.svgBytes > 4500 && entry.textLabels >= 14, `${entry.id} evidence density is unexpectedly low (${entry.svgBytes} bytes/${entry.textLabels} labels)`);
}
const sortedSvgBytes = visualizationManifest.newVisualizations.map((entry) => entry.svgBytes).sort((a, b) => a - b);
const sortedTextLabels = visualizationManifest.newVisualizations.map((entry) => entry.textLabels).sort((a, b) => a - b);
assert(sortedSvgBytes[32] >= 9000, `V14 median SVG bytes ${sortedSvgBytes[32]} fell below the editorial-density floor`);
assert(sortedTextLabels[32] >= 35, `V14 median text labels ${sortedTextLabels[32]} fell below the editorial-density floor`);
const v14BuilderSource = text(join(repoRoot, 'scripts/build-king-report-v14.mjs'));
const v15BuilderSource = text(join(repoRoot, 'scripts/build-king-report-v15.mjs'));
for (const forbiddenGeneratorPattern of ['360+((index*97)%390)', '(col+row)%4!==3', 'entry.metrics[index%4]', 'index*200']) {
  assert(!v14BuilderSource.includes(forbiddenGeneratorPattern), `Arbitrary v10 generator formula remains: ${forbiddenGeneratorPattern}`);
}
const htmlWithoutFigureAudits = html.replace(/<details[^>]*class="figure-audit"[\s\S]*?<\/details>/g, '');
assert(!htmlWithoutFigureAudits.includes('DATA-BOUND EVIDENCE VISUAL'), 'V13 visible report still contains the batch-template eyebrow');
assert(!htmlWithoutFigureAudits.includes('图中数字、节点和媒体均绑定机器可读证据'), 'V13 visible report still contains the repeated inspector prompt');
assert(!html.includes('class="v11-proofline"') && !html.includes('V10-GROUP:') && !html.includes('V11-GROUP:') && !html.includes('v10-type-'), 'Removed v10/v11 visualization shell remains in the report');
assert(!v14BuilderSource.match(/function renderFigure[\s\S]*?\n}\n\nconst M/)?.[0].includes('metricRail(metrics'), 'V14 renderFigure revived the repeated KPI rail');
assert(new Set(visualizationManifest.newVisualizations.map((entry) => entry.visualGrammar)).size === 8, 'V14 does not expose eight distinct visual grammars');
assert(new Set(visualizationManifest.newVisualizations.map((entry) => entry.chapterThesis)).size === 8, 'V14 chapter theses are not distinct');
assert(countMatches(html, /class="v12-atlas-head"/g) === 8 && countMatches(html, /class="v12-chapter-legend"/g) === 8, 'V14 chapter introduction or chapter-level legend count differs');
assert(/<body[^>]*data-report-version="v15"/.test(html) && html.includes('class="v15-topbar"') && html.includes('class="v15-hero-media"'), 'V15 editorial shell is missing');
assert(countMatches(html, /data-v15-role="[^"]+"/g) === 91 && countMatches(html, /data-v15-motion="reveal"/g) === 91, 'V15 figure roles or reveal markers differ');
assert(countMatches(html, /<details\b/g) === countMatches(html, /<details\b[^>]*\bopen\b/g), 'V15 must expose the complete audit corpus by default');
assert(v15BuilderSource.includes('@media(prefers-reduced-motion:reduce)') && v15BuilderSource.includes('contentReductionAllowed: false'), 'V15 reduced-motion or zero-content-reduction policy is missing');

function relativeLuminance(hex) {
  const channels = hex.match(/[0-9a-f]{2}/gi).map((value) => Number.parseInt(value, 16) / 255).map((value) => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
  return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
}
function contrastRatio(foreground, background) {
  const a = relativeLuminance(foreground), b = relativeLuminance(background);
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
}
const v14Theme = { background: '#07111d', panel: '#0d1a2a', raised: '#112238', text: '#f4f7fb', secondary: '#c9d4e2', code: '#a9b8c9', line: '#506a87', gold: '#e8c76b', blue: '#5aa9ff', red: '#ff7670', cyan: '#5cc7d8', green: '#65c891', purple: '#a994dc' };
assert(contrastRatio(v14Theme.text, v14Theme.raised) >= 4.5 && contrastRatio(v14Theme.secondary, v14Theme.panel) >= 4.5 && contrastRatio(v14Theme.code, v14Theme.raised) >= 4.5, 'V14 text palette falls below WCAG 4.5:1');
assert(contrastRatio(v14Theme.line, v14Theme.panel) >= 3 && ['gold', 'blue', 'red', 'cyan', 'green', 'purple'].every((key) => contrastRatio(v14Theme[key], v14Theme.background) >= 3), 'V14 graphical palette falls below 3:1');
assert(!v14BuilderSource.match(/font-weight:(?:750|800|850|900)|font:(?:750|800|850|900) /), 'V14 uses unsupported synthetic font weights');
assert(v14BuilderSource.includes('.v11-card-title{fill:var(--viz-text);font-size:16px') && v14BuilderSource.includes('.v11-code-source{fill:var(--viz-text-3);font-size:13px') && v14BuilderSource.includes('.v12-question{fill:var(--viz-text);font-size:27px'), 'V14 typography scale differs');
assert(v14BuilderSource.includes('.v11-bubble-label,.v11-film-label,.v11-index,.v11-swim-label,.v11-tree-label,.v12-source-index-text{font-size:12px}') && v14BuilderSource.includes('.viz-rich .vr-code,.viz-rich .vr-source{font-family:var(--viz-mono);font-size:12px}'), 'V14 did not raise retained utility/code labels to the 12px floor');
assert(!v14BuilderSource.match(/tone:\s*\[[^\]]+\]\[index\s*%/), 'V14 still assigns node colors by rotating index');
assert(!html.match(/class="v11-code(?:\s|\")/), 'Undefined legacy v11-code class remains in HTML');
const allSvgMarkup = [...html.matchAll(/<svg\b[\s\S]*?<\/svg>/g)].map((match) => match[0]).join('\n');
const svgClassUses = new Set([...allSvgMarkup.matchAll(/class="([^"]+)"/g)].flatMap((match) => match[1].split(/\s+/)).filter(Boolean));
const definedCssClasses = new Set([...html.matchAll(/\.([A-Za-z_][A-Za-z0-9_-]*)/g)].map((match) => match[1]));
const undefinedSvgClasses = [...svgClassUses].filter((className) => !definedCssClasses.has(className));
assert(undefinedSvgClasses.length === 0, `Undefined SVG CSS classes: ${undefinedSvgClasses.join(', ')}`);
const unstyledSvgText = [...allSvgMarkup.matchAll(/<text\b(?![^>]*(?:class|fill|style)=)[^>]*>/g)].map((match) => match[0]);
assert(unstyledSvgText.length === 0, `SVG text without explicit class/fill: ${unstyledSvgText.length}`);
const caseV04Figure = html.match(/<figure[^>]*data-viz-code="CASE-V04"[\s\S]*?<\/figure>/)?.[0] ?? '';
assert(countMatches(caseV04Figure, /class="v13-code-strip"/g) === 7 && countMatches(caseV04Figure, /class="v11-target"/g) === 7 && countMatches(caseV04Figure, /class="v13-domain-tag"/g) === 4, 'CASE-V04 lacks seven code strips/targets or four convergence domains');
assert(caseV04Figure.includes('width="384" height="324"') && !caseV04Figure.includes('v12-case-river'), 'CASE-V04 media enlargement or decorative-curve removal differs');
const platV03Figure = html.match(/<figure[^>]*data-viz-code="PLAT-V03"[\s\S]*?<\/figure>/)?.[0] ?? '';
const auditV01Figure = html.match(/<figure[^>]*data-viz-code="AUDIT-V01"[\s\S]*?<\/figure>/)?.[0] ?? '';
const runV07Figure = html.match(/<figure[^>]*data-viz-code="RUN-V07"[\s\S]*?<\/figure>/)?.[0] ?? '';
assert(!platV03Figure.includes('class="v11-flow"') && platV03Figure.includes('873+5=878') && platV03Figure.includes('968条完整三阶段生命周期') && platV03Figure.includes('不是守恒流'), 'PLAT-V03 ledger semantics differ');
assert(!auditV01Figure.includes('class="v11-flow"') && !auditV01Figure.includes('Sankey') && auditV01Figure.includes('873匹配') && auditV01Figure.includes('记录域边界'), 'AUDIT-V01 record-lane semantics differ');
for (const token of ['WebBrowser', 'Sleep', 'Bash', 'Agent', 'Read', 'Grep', 'Write', 'Edit', 'AskUserQuestion']) assert(runV07Figure.includes(token), `RUN-V07 omits ${token}`);
assert(html.includes('5小时31分证据过滤窗口') && html.includes('5小时29分17秒首末请求锚点跨度'), 'Report does not preserve both time-boundary labels');
const visualDesignAudit = { system: 'v14-layout-contract-1', typography: { sans: 'system', mono: 'system', minimumVisibleLabel: 12, cardTitle: 16, body: 14, code: 13, question: 27 }, contrast: { textOnRaised: Number(contrastRatio(v14Theme.text, v14Theme.raised).toFixed(3)), codeOnRaised: Number(contrastRatio(v14Theme.code, v14Theme.raised).toFixed(3)), lineOnPanel: Number(contrastRatio(v14Theme.line, v14Theme.panel).toFixed(3)) }, undefinedSvgClasses, unstyledSvgText: unstyledSvgText.length, caseV04: { codeStrips: 7, targets: 7, convergenceDomains: 4, imageSize: [384, 324] }, layoutAudit: svgLayoutAudit.summary };
const qaV06Figure = html.match(/<figure[^>]*data-viz-code="QA-V06"[\s\S]*?<\/figure>/)?.[0] ?? '';
const qaV09Figure = html.match(/<figure[^>]*data-viz-code="QA-V09"[\s\S]*?<\/figure>/)?.[0] ?? '';
assert(countMatches(qaV06Figure, /<image\b/g) === 43, 'QA-V06 must visibly include all 43 screenshot files');
assert(countMatches(qaV09Figure, /<image\b/g) === 20, 'QA-V09 must visibly include all 20 derived storyboard frames');
assert(visualizationManifest.contentCoverage.screenshots.allFrames.length === 43, 'Screenshot manifest does not enumerate 43 frames');
for (const frame of visualizationManifest.contentCoverage.screenshots.allFrames) {
  const framePath = join(repoRoot, 'docs/case-studies', frame.path);
  assert(existsSync(framePath) && sha256(framePath) === frame.sha256, `Screenshot filmstrip hash differs: ${frame.path}`);
  assert(qaV06Figure.includes(`href="${frame.path}"`), `QA-V06 omits screenshot ${frame.path}`);
}
assert(visualizationManifest.contentCoverage.videos.storyboardFrames.length === 20, 'Video manifest does not enumerate 20 storyboard frames');
for (const frame of visualizationManifest.contentCoverage.videos.storyboardFrames) {
  assert(qaV09Figure.includes(`href="${frame.path}"`), `QA-V09 omits storyboard frame ${frame.path}`);
}
const stripAuditAttributes = (value) => value
  .replace(/\s+data-audit-unit="[^"]+"/g, '')
  .replace(/\s+data-visualized-by="[^"]+"/g, '')
  .replace(/\s+/g, ' ').trim();
for (const [tag, records] of [['table', visualizationManifest.contentCoverage.tables], ['pre', visualizationManifest.contentCoverage.preformattedBlocks]]) {
  const elements = [...html.matchAll(new RegExp(`<${tag}\\b[\\s\\S]*?<\\/${tag}>`, 'g'))].map((match) => match[0]);
  assert(elements.length === records.length, `${tag} coverage count differs: ${elements.length}/${records.length}`);
  elements.forEach((element, index) => {
    const record = records[index];
    assert(element.includes(`data-audit-unit="${record.id}"`) && element.includes(`data-visualized-by="${record.visualizationId}"`), `${record.id} audit mapping missing`);
    const digest = createHash('sha256').update(stripAuditAttributes(element)).digest('hex');
    assert(digest === record.normalizedTextSha256, `${record.id} normalized content hash differs`);
  });
}
const publicRedaction = json(join(assetRoot, 'redaction-report.json'));
if (appSourceResolution) {
  const capturedCurrent = publicRedaction.sources.find((source) => source.id === 'captured-current-log-rotated-gzip');
  assert(Boolean(capturedCurrent), 'Redaction report does not register the captured continuation source');
  if (capturedCurrent) assert(appSourceResolution.selectedFragmentSha256 === capturedCurrent.selectedSha256, 'Rotation-aware app.log archive is not byte-equivalent to the captured selected fragment');
}
assert(publicRedaction.publicFile.timestampBlocks === 38626 && publicRedaction.publicFile.physicalLines === 38641, 'Public log block/line totals differ');
assert(JSON.stringify(publicRedaction.sources.map((source) => source.selectedTimestampBlocks)) === JSON.stringify([30948, 7678]), 'Public log source block split differs');
assert(publicRedaction.merged.sameBoundaryTimestamp === '2026-08-09 06:19:29.725' && publicRedaction.merged.boundaryBlockCount === 5 && publicRedaction.merged.boundaryBlocksDistinct === true, 'Public log same-millisecond boundary facts differ');
assert(publicRedaction.redactions.find((item) => item.name === 'local-user-home')?.count === 1356, 'Public log home-path redaction count differs');
assert(publicRedaction.redactions.find((item) => item.name === 'local-user-name')?.count === 0, 'Public log standalone local-user redaction count differs');
assert(publicRedaction.databaseExports?.localUserNameRule?.count === 22 && publicRedaction.databaseExports?.localUserNameRule?.derivedVisualizationCopies === 11, 'Database/derived local-user redaction count differs');
assert(publicRedaction.publicArtifactRedactions?.find((item) => item.name === 'local-user-name')?.count === 33, 'Total public-artifact local-user redaction count differs');
assert(Object.values(publicRedaction.postScan).every((value) => value === 0), 'Public log post-redaction scan is not clean');
assert(sha256(appLogPath) === publicRedaction.publicFile.sha256, 'Public app log hash differs from redaction report');
const videoStoryboards = json(join(assetRoot, 'video-storyboards.json'));
const derivedFrames = videoStoryboards.videos.flatMap((video) => video.frames.map((frame) => ({ video: video.original, classification: video.classification, ...frame })));
assert(videoStoryboards.videos.length === 5 && derivedFrames.length === 20, 'Video storyboard count differs');
for (const frame of derivedFrames) {
  const framePath = join(assetRoot, frame.path);
  assert(existsSync(framePath) && sha256(framePath) === frame.sha256, `Storyboard frame hash differs: ${frame.path}`);
  assert(frame.width === 640 && frame.height === 360, `Storyboard frame dimensions differ: ${frame.path}`);
}
for (const evidenceId of Array.from({ length: 12 }, (_, index) => `E${31 + index}`)) {
  assert(html.includes(`<b>${evidenceId}</b>`), `Evidence ledger is missing ${evidenceId}`);
}
for (const required of ['多系统实时耦合', '单机 Web 5v5 MOBA 原型', '下一步进行阵营互换', '下一阶段将用重复任务基准继续量化平均成功率、成本稳定性和生产成熟度']) {
  assert(html.includes(required), `Product engineering conclusion is missing: ${required}`);
}
const headingTexts = [...html.matchAll(/<h3\b[^>]*>([\s\S]*?)<\/h3>/g)]
  .map((match) => match[1].replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim());
const duplicateHeadings = [...new Set(headingTexts.filter((heading, index) => headingTexts.indexOf(heading) !== index))];
assert(duplicateHeadings.length === 0, `Duplicate h3 headings: ${duplicateHeadings.join(' | ')}`);
for (const tag of ['section', 'details', 'table']) {
  const opens = (html.match(new RegExp(`<${tag}\\b`, 'g')) ?? []).length;
  const closes = (html.match(new RegExp(`</${tag}>`, 'g')) ?? []).length;
  assert(opens === closes, `Unbalanced <${tag}> tags: ${opens} open / ${closes} close`);
}
assert(!/<h3\b[^>]*>(?:(?!<\/h3>)[\s\S])*<h3\b/.test(html), 'Malformed nested h3 detected');
assert(!/class="callout green"><b>总结：<\/b><div class="callout green"/.test(html), 'Nested duplicate summary callout detected');
for (const forbidden of ['7 正常收尾', '源码 7,979 行 / 18 文件', '计费等价于只处理了', '同等工作的计费输入将放大约 34 倍', '任何一方造假都会与另外两方矛盾', 'demo 模式胜负无偏向', '共耗时 22 秒', '5.5 小时零打扰', '成果均完整保留', 'PART 3 账本', '（6.2 已用）', '观测事件显示均有重试接续', '无一次因调用失败丢失工作']) {
  assert(!html.includes(forbidden), `Forbidden overclaim/stale statement remains: ${forbidden}`);
}
assert(!html.includes('1,262 个图内直接标签') && !html.includes('1,262个图内直接标签'), 'Stale product SVG label total remains');
for (const required of ['19 个第一方文件', '1 次自然完成 / 6 次期限回收 / 3 次轮次回收', '605,651', '2,437,981', '42 份唯一图像内容', '阿里云在线试玩']) {
  assert(html.includes(required), `Required corrected fact missing: ${required}`);
}
for (const requiredId of ['module-graph-evidence', 'three-layer-loop', 'traceability-evidence', 'runtime-mechanism-map', 'context-governance-evidence', 'platform-snapshot-evidence', 'runtime-control-evidence', 'failure-audit-evidence', 'llm-audit-evidence', 'tool-lifecycle-evidence', 'claim-evidence-boundary', 'trace-field-dictionary']) {
  assert(ids.includes(requiredId), `Required evidence section missing: #${requiredId}`);
}
for (const required of ['39 条本地相对导入边', '376 次 WebBrowser', '267 次成功原子写入', '157 次 Checkpoint 保存', '60 组完整配对', '878 次唯一上下文评估', '2,029 个字符', '240,202', '968 / 968 / 968', '951 次', '17 次', '38,626条MDC行', '11个会话、11个Run', '968个复合工具键', '422个裸工具序号', '376个WebBrowser复合键', '16,005,982ms', '18,334.458 ms', '7,652 ms', '61,546 ms', '745,910 ms']) {
  assert(html.includes(required), `Required evidence statement missing: ${required}`);
}
for (const forbiddenOverclaim of ['六层压缩在本案全部触发', '本案实际从 Checkpoint 恢复', '运行二进制由 ea0170c 密码学证明', 'SWE-bench 是本案的对照组']) {
  assert(!html.includes(forbiddenOverclaim), `Forbidden mechanism overclaim remains: ${forbiddenOverclaim}`);
}
assert(html.includes('376个WebBrowser复合键') && html.includes('376个唯一Python POST'), 'The 376 WebBrowser traceability statement is missing');
assert(!html.includes('class="cannot"') && !html.includes('class="vr-boundary"') && !html.includes('完整限制') && !html.includes('限制：'), 'Repeated defensive figure shell remains visible');
const defensivePhraseCount = countMatches(html, /不能证明|无法推导|不能推导|并不能/g);
assert(defensivePhraseCount === 0, `Visible defensive phrasing remains overused: ${defensivePhraseCount}`);

const anchorRefs = [...html.matchAll(/href="#([^"]+)"/g)].map((match) => match[1]);
for (const ref of anchorRefs) assert(ids.includes(ref), `Broken in-page HTML anchor: #${ref}`);
const localRefs = [...new Set(
  [...html.matchAll(/(?:src|href)="([^"]+)"/g)]
    .map((match) => match[1])
    .filter((ref) => !/^(?:https?:|mailto:|data:|#|javascript:)/.test(ref)),
)];
for (const ref of localRefs) {
  assert(!/^(?:file:|\/Users\/|[A-Za-z]:\\)/.test(ref), `Non-portable local HTML reference: ${ref}`);
  const clean = decodeURIComponent(ref.split(/[?#]/)[0]);
  const target = resolve(dirname(caseHtmlPath), clean);
  assert(existsSync(target), `Broken local HTML reference: ${ref}`);
}

const secretPatterns = [
  ['private key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/],
  ['AWS/Alibaba access key', /\b(?:AKIA|LTAI)[A-Z0-9]{16,}\b/],
  ['provider secret key', /\bsk-[A-Za-z0-9_-]{24,}\b/],
  ['GitHub token', /\b(?:ghp|github_pat)_[A-Za-z0-9_]{20,}\b/],
  ['Slack token', /\bxox[baprs]-[A-Za-z0-9-]{20,}\b/],
  ['JWT', /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/],
  ['Authorization header', /\bAuthorization\s*[:=]\s*(?:Bearer|Basic)\s+/i],
];
const textualFiles = walkFiles(assetRoot).filter((path) => ['.txt', '.md', '.json', '.jsonl', '.csv', '.log', '.html', '.js', '.command'].includes(extname(path).toLowerCase()));
for (const path of [caseHtmlPath, ...textualFiles]) {
  const value = text(path);
  assert(!/\/Users\//.test(value), `Raw local user path remains in public file: ${relative(repoRoot, path)}`);
  if (localUserName) assert(!new RegExp(`\\b${localUserName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`).test(value), `Raw local OS username remains in public file: ${relative(repoRoot, path)}`);
  assert(!/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/i.test(value), `Email address remains in public file: ${relative(repoRoot, path)}`);
  // Reject standalone mainland phone numbers, but do not mistake a decimal
  // coordinate such as `781.15165336374` inside an SVG path for a phone.
  assert(!/(?<![A-Za-z0-9.])1[3-9]\d{9}(?![A-Za-z0-9])/.test(value), `Mainland China phone number remains in public file: ${relative(repoRoot, path)}`);
  for (const [label, pattern] of secretPatterns) assert(!pattern.test(value), `Potential ${label} in ${relative(repoRoot, path)}`);
}
assert(!existsSync(join(assetRoot, 'db/interaction-requests-publication-supplement.json')), 'Publication-stage interaction supplement must not be in the public evidence bundle');
const caseReadme = text(join(repoRoot, 'docs/case-studies/README.md'));
const rootReadme = text(join(repoRoot, 'README.md'));
const publicJsonText = walkFiles(assetRoot).filter((path) => extname(path).toLowerCase() === '.json').map(text).join('\n');
for (const [label, value] of [['HTML', html], ['case README', caseReadme], ['public JSON', publicJsonText]]) {
  assert(!/publication supplement|publication-supplement|窗口外授权/.test(value), `${label} contains the deleted publication supplement`);
  assert(!/E43/.test(value), `${label} contains stale E43 evidence numbering`);
  assert(!/94\s*(?:张|图|个)\s*(?:SVG|数据绑定|证据图)?/i.test(value), `${label} contains the stale 94-figure count`);
}
assert(html.includes('href="assets/king/logs/app-session-20260809-0130-0701.public.log"'), 'HTML does not provide a relative download link to the public merged log');
assert(html.includes('四份数据库导出的冻结关系') && !html.includes('五份数据库导出的冻结关系'), 'Report database-export count is stale');
assert(visualizationData.evidenceAssetSummary?.repositoryFiles === 109 && visualizationData.evidenceAssetSummary?.releaseOriginals === 5 && visualizationData.evidenceAssetSummary?.totalTraceableAssets === 114, 'Evidence asset repository/Release split differs');
assert(html.includes('109个仓库文件') && html.includes('5个Release原件'), 'META-V04 repository/Release split is missing');
assert(messages.every((message) => !String(message.content_json ?? '').includes('"type":"thinking"')), 'Public message export still contains internal thinking blocks');
for (const [label, value] of [['HTML', html], ['root README', rootReadme], ['case README', caseReadme]]) {
  assert(value.includes(standardOnlineUrl), `${label} is missing the exact standard online URL`);
  assert(value.includes(demoOnlineUrl), `${label} is missing the exact Demo online URL`);
  assert(!/https:\/\/king\.zhikun\.xin\/(?:\?demo=1)?(?:，|%EF%BC%8C)/i.test(value), `${label} contains a trailing Chinese comma in an online URL`);
}
const onlineAnchors = [...html.matchAll(/<a\b([^>]*?)href="(https:\/\/king\.zhikun\.xin\/(?:\?demo=1)?)"([^>]*)>([\s\S]*?)<\/a>/g)];
assert(onlineAnchors.filter((match) => match[2] === standardOnlineUrl).length === 4, 'HTML must expose the standard online URL in hero, rail, QA and conclusion');
assert(onlineAnchors.filter((match) => match[2] === demoOnlineUrl).length === 4, 'HTML must expose the Demo online URL in hero, rail, QA and conclusion');
for (const match of onlineAnchors) {
  const attrs = `${match[1]} ${match[3]}`;
  assert(/target="_blank"/.test(attrs) && /rel="noopener noreferrer"/.test(attrs), `Online anchor is missing safe new-window attributes: ${match[2]}`);
  assert(/在线试玩|自动演示/.test(match[4]), `Online anchor lacks descriptive text: ${match[2]}`);
}
assert(html.includes('QA-V10') && html.includes('OUTSIDE WINDOW · SAME BUILD + demo=1'), 'QA-V10 does not preserve the outside-window online deployment classification');

const verification = {
  schemaVersion: 10,
  caseId: 'zhikuncode-king-20260809',
  status: errors.length ? 'FAILED' : 'VERIFIED',
  evidenceWindow: { startInclusive: '2026-08-09T01:30:00+08:00', endExclusive: '2026-08-09T07:01:00+08:00' },
  platformSnapshot,
  code,
  bill: { ...bill, cacheRatioPercent: Number((bill.cacheRatio * 100).toFixed(3)), physicalLines: lineCount(join(assetRoot, 'bill/request_log_part_0001.csv')) },
  runtime: { ...runtime, exactlyMatchedCompletedRequests: completed.length, billOnlyRows: billOnlyRows.map((row) => ({ time: row['时间'], inputTokens: Number(row['输入 Tokens']), outputTokens: Number(row['输出 Tokens']) })) },
  database: { sessionId: session.id, sessionAggregateTokenFields: { input: session.total_input_tokens, output: session.total_output_tokens }, messages: messageStats, activities: activities.rowCount, interactionRequests: interactions.rowCount, frozenExportsMatchSource: databaseExportsMatchSource },
  logs: { appLines: lineCount(appLogPath), observabilityLines: observability.length, securityLines: lineCount(securityLogPath), deterministicallyRebuiltFromSource: logSourceRebuild, appSourceResolution, mergeProvenance: publicRedaction.merged, publicRedaction: { policy: publicRedaction.policy, publicFile: publicRedaction.publicFile, redactions: publicRedaction.redactions, postScan: publicRedaction.postScan }, traceability, toolIdentity, llmAudit, failureContinuation, contextGovernance, toolLifecycle, executionControls },
  tools: { total: toolKeys.size, distribution: toolCounts },
  externalBaseline,
  screenshots: screenshotsStats,
  productVisualizations,
  productVisualizationRichness,
  logVisualizations,
  logVisualizationRichness,
  codeImageBindings,
  reportContentCoverage: visualizationManifest.contentCoverage,
  visualizationCoverage: {
    reportVersion: visualizationManifest.reportVersion,
    visualDesign: visualDesignAudit,
    total: visualizationManifest.totalVisualizations,
    retained: visualizationManifest.retainedVisualizations.length,
    added: visualizationManifest.newVisualizations.length,
    independentRenderers: rendererNames.length,
    medianSvgBytes: sortedSvgBytes[32],
    medianTextLabels: sortedTextLabels[32],
    manifestSchemaVersion: visualizationManifest.schemaVersion,
    manifestSha256: sha256(join(assetRoot, 'visualization-manifest.json')),
    editorial: {
      visualGrammars: [...new Set(visualizationManifest.newVisualizations.map((entry) => entry.visualGrammar))],
      chapterTheses: [...new Set(visualizationManifest.newVisualizations.map((entry) => entry.chapterThesis))],
      compactFootnotes: visualizationManifest.newVisualizations.filter((entry) => Boolean(entry.compactFootnote)).length,
      adjacentAuditLayers: countMatches(html, /class="figure-audit"/g),
      visibleBatchTemplateEyebrows: countMatches(htmlWithoutFigureAudits, /DATA-BOUND EVIDENCE VISUAL/g),
      repeatedKpiRail: visualizationManifest.renderingPolicy.repeatedKpiRail,
    },
    visualizationData: {
      schemaVersion: visualizationData.schemaVersion,
      sha256: sha256(join(assetRoot, 'visualization-data.json')),
      facts: Object.keys(visualizationData.facts).length,
      codeFiles: visualizationData.code.files.length,
      importEdges: visualizationData.code.imports.length,
      contextPoints: visualizationData.execution.contextPoints.length,
      minuteBuckets: visualizationData.logs.minuteDensity.length,
      hourlyBillBuckets: visualizationData.bill.hourly.length,
      runs: 1 + visualizationData.execution.workers.length,
      evidenceAssets: visualizationData.evidenceAssets.length,
    },
    svgLayoutAudit: {
      schemaVersion: svgLayoutAudit.schemaVersion,
      sha256: sha256(join(assetRoot, 'svg-layout-audit.json')),
      viewport: svgLayoutAudit.viewport,
      thresholds: svgLayoutAudit.thresholds,
      summary: svgLayoutAudit.summary,
    },
  },
  videoDerivedFrames: { videos: videoStoryboards.videos.length, frames: derivedFrames.length, classification: videoStoryboards.classification, items: derivedFrames },
  evidenceDomainGraph: { domains: ['code', 'application-log', 'observability', 'security-log', 'database', 'provider-bill', 'screenshots', 'videos', 'browser-verification'], visualizationId: 'AUDIT-V09', trustBoundary: 'Most sources remain local and are not mutually independent third-party attestations.' },
  videos: { originalsRegistered: releaseAssets.assets.length, originalsLocallyVerified: locallyVerifiedOriginals, previews: previewStats },
  html: { bytes: statSync(caseHtmlPath).size, sha256: sha256(caseHtmlPath), localReferences: localRefs.length, duplicateIds },
  secretScan: { filesScanned: textualFiles.length + 1, highConfidenceMatches: 0 },
  failures: errors,
};

function manifestEligible(path) {
  const rel = relative(assetRoot, path);
  if (rel === 'SHA256SUMS.txt') return false;
  if (/^videos\/[^/]+\.mp4$/.test(rel)) return false;
  return true;
}

function generateManifest() {
  return walkFiles(assetRoot).filter(manifestEligible)
    .map((path) => `${sha256(path)}  ${relative(assetRoot, path)}`).join('\n') + '\n';
}

function verifyManifest() {
  const manifestPath = join(assetRoot, 'SHA256SUMS.txt');
  if (!existsSync(manifestPath)) return ['Missing SHA256SUMS.txt'];
  const failures = [];
  const listed = [];
  for (const line of text(manifestPath).trim().split('\n')) {
    const match = line.match(/^([a-f0-9]{64})  (.+)$/);
    if (!match) { failures.push(`Malformed manifest line: ${line}`); continue; }
    const path = join(assetRoot, match[2]);
    listed.push(match[2]);
    if (!existsSync(path)) failures.push(`Missing manifest file: ${match[2]}`);
    else if (sha256(path) !== match[1]) failures.push(`Hash mismatch: ${match[2]}`);
  }
  const eligible = walkFiles(assetRoot).filter(manifestEligible).map((path) => relative(assetRoot, path));
  if (JSON.stringify([...listed].sort()) !== JSON.stringify(eligible)) failures.push('SHA256SUMS.txt does not exactly cover every repository evidence file');
  return failures;
}

if (writeMode) {
  if (errors.length) {
    console.error(JSON.stringify(verification, null, 2));
    process.exit(1);
  }
  writeFileSync(join(assetRoot, 'verification.json'), `${JSON.stringify(verification, null, 2)}\n`);
  writeFileSync(join(assetRoot, 'zhikuncode开发王者荣耀.html.sha256'), `${sha256(caseHtmlPath)}  ../../zhikuncode开发王者荣耀.html\n`);
  writeFileSync(join(assetRoot, 'SHA256SUMS.txt'), generateManifest());
}

for (const failure of verifyManifest()) fail(failure);
if (errors.length) {
  console.error(`King case verification failed (${errors.length}):`);
  for (const error of errors) console.error(`- ${error}`);
  process.exit(1);
}
console.log(JSON.stringify({ status: 'VERIFIED', platformSnapshot: { commit: platformSnapshot.nearestCommitBeforeWindow.shortHash, productSource: platformSnapshot.productSource }, code, bill, runtime: { completed: completed.length, inputTokens: runtime.inputTokens, outputTokens: runtime.outputTokens, billOnlyRows: billOnlyRows.length }, messages: messageStats, traceability: { sessions: traceability.sessions, runs: traceability.runs, workerParentMappings: traceability.workerParentMappings }, tools: { total: toolKeys.size, bareToolIds: toolIdentity.bareToolIds, webBrowserDownstream: `${toolIdentity.webBrowserCompositeKeys}/${toolIdentity.uniquePythonRequestIds}`, lifecycle: toolLifecycle }, llmAudit: { started: llmAudit.started, completed: llmAudit.completed, failed: llmAudit.failed, missing: llmAudit.missingTerminalIds, orphan: llmAudit.orphanTerminalIds, duplicate: llmAudit.duplicateTerminalIds, completedDurationMs: llmAudit.completedDurationMs }, contextGovernance, executionControls, externalBaseline: { resolved: `${externalBaseline.resolvedInstances}/${externalBaseline.totalInstances}`, nonEmptyPatches: externalBaseline.nonEmptyPatches }, screenshots: screenshotsStats, videos: { originals: releaseAssets.assets.length, previews: previewStats.length }, secrets: 0 }, null, 2));
