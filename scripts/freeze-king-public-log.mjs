#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const assetRoot = join(repoRoot, 'docs/case-studies/assets/king');
const capturedContinuation = join(repoRoot, 'log/app-2026-08-09-2.log.gz');
const sourceDefinitions = [
  { id: 'rotated-gzip', path: join(repoRoot, 'log/app-2026-08-09-1.log.gz'), compressed: true },
  existsSync(capturedContinuation)
    ? { id: 'captured-current-log-rotated-gzip', path: capturedContinuation, compressed: true }
    : { id: 'current-log', path: join(repoRoot, 'log/app.log'), compressed: false },
];
const outputPath = join(assetRoot, 'logs/app-session-20260809-0130-0701.public.log');
const reportPath = join(assetRoot, 'redaction-report.json');
const provenancePath = join(assetRoot, 'provenance.json');
const start = '2026-08-09 01:30:00.000';
const end = '2026-08-09 07:01:00.000';
const timestampPattern = /^(2026-08-09 \d{2}:\d{2}:\d{2}\.\d{3})/;
const localUserName = repoRoot.match(/^\/Users\/([^/]+)/)?.[1] ?? null;
const localUserHome = localUserName ? `/Users/${localUserName}` : null;

function digest(value) {
  return createHash('sha256').update(value).digest('hex');
}

function splitTimestampBlocks(value) {
  const lines = value.match(/[^\n]*\n|[^\n]+$/g) ?? [];
  const blocks = [];
  let current = null;
  for (const chunk of lines) {
    const line = chunk.endsWith('\n') ? chunk.slice(0, -1) : chunk;
    const timestamp = line.match(timestampPattern)?.[1] ?? null;
    if (timestamp) {
      if (current) blocks.push(current);
      current = { timestamp, chunks: [chunk] };
    } else if (current) {
      current.chunks.push(chunk);
    }
  }
  if (current) blocks.push(current);
  return blocks;
}

function segmentInfo(definition) {
  if (!existsSync(definition.path)) throw new Error(`Missing source log: ${definition.path}`);
  const sourceBytes = readFileSync(definition.path);
  const sourceText = definition.compressed ? gunzipSync(sourceBytes).toString('utf8') : sourceBytes.toString('utf8');
  const blocks = splitTimestampBlocks(sourceText).filter((block) => block.timestamp >= start && block.timestamp < end);
  const segment = blocks.map((block) => block.chunks.join('')).join('');
  const stats = statSync(definition.path);
  return {
    id: definition.id,
    sourcePath: definition.path.replace(`${repoRoot}/`, ''),
    compressed: definition.compressed,
    capturedSizeBytes: sourceBytes.length,
    capturedMtimeMs: Number(stats.mtimeMs.toFixed(3)),
    capturedSha256: digest(sourceBytes),
    selectedTimestampBlocks: blocks.length,
    selectedPhysicalLines: (segment.match(/\n/g) ?? []).length,
    firstTimestamp: blocks.at(0)?.timestamp ?? null,
    lastTimestamp: blocks.at(-1)?.timestamp ?? null,
    selectedSha256: digest(segment),
    blocks,
    segment,
  };
}

const sources = sourceDefinitions.map(segmentInfo);
const expectedSourceBlocks = [30948, 7678];
if (JSON.stringify(sources.map((source) => source.selectedTimestampBlocks)) !== JSON.stringify(expectedSourceBlocks)) {
  throw new Error(`Refusing to overwrite the frozen public log with incomplete sources: ${sources.map((source) => source.selectedTimestampBlocks).join('/')} != ${expectedSourceBlocks.join('/')}`);
}
const rawMerged = sources.map((source) => source.segment).join('');
const rawBlocks = sources.flatMap((source) => source.blocks);
const blockHashes = sources.map((source) => new Set(source.blocks.map((block) => digest(block.chunks.join('')))));
const identicalCrossSourceBlocks = [...blockHashes[0]].filter((hash) => blockHashes[1].has(hash)).length;
const sameBoundaryTimestamp = sources[0].lastTimestamp === sources[1].firstTimestamp ? sources[0].lastTimestamp : null;
const boundaryBlocks = sameBoundaryTimestamp
  ? rawBlocks.filter((block) => block.timestamp === sameBoundaryTimestamp).map((block) => digest(block.chunks.join('')))
  : [];
if (rawBlocks.length !== 38626 || (rawMerged.match(/\n/g) ?? []).length !== 38641) {
  throw new Error(`Refusing to publish incomplete merged log: blocks=${rawBlocks.length}, lines=${(rawMerged.match(/\n/g) ?? []).length}`);
}

const redactions = [];
let publicLog = rawMerged;
function replaceLiteral(name, literal, replacement) {
  const pieces = publicLog.split(literal);
  const count = Math.max(0, pieces.length - 1);
  if (count) publicLog = pieces.join(replacement);
  redactions.push({ name, count, replacement });
}
if (localUserHome) replaceLiteral('local-user-home', localUserHome, '<USER_HOME>');
else redactions.push({ name: 'local-user-home', count: 0, replacement: '<USER_HOME>' });
replaceLiteral('local-user-name', localUserName ?? '__NO_LOCAL_USER__', '<LOCAL_USER>');

const secretRules = [
  ['private-key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----[\s\S]*?-----END (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/g],
  ['aliyun-or-aws-access-key', /\b(?:AKIA|LTAI)[A-Z0-9]{16,}\b/g],
  ['provider-secret-key', /\bsk-[A-Za-z0-9_-]{24,}\b/g],
  ['github-token', /\b(?:ghp|github_pat)_[A-Za-z0-9_]{20,}\b/g],
  ['slack-token', /\bxox[baprs]-[A-Za-z0-9-]{20,}\b/g],
  ['jwt', /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/g],
  ['authorization-bearer', /\bAuthorization\s*[:=]\s*Bearer\s+[A-Za-z0-9._~+\/-]+=*/gi],
  ['cookie-header', /\b(?:Cookie|Set-Cookie)\s*:\s*[^\r\n]+/gi],
  ['named-password', /\b(?:password|passwd|pwd|secret)\s*[=:]\s*[^,\s}\]]+/gi],
];
for (const [name, pattern] of secretRules) {
  let index = 0;
  publicLog = publicLog.replace(pattern, () => `[REDACTED:${name}#${String(++index).padStart(2, '0')}]`);
  redactions.push({ name, count: index, replacement: `[REDACTED:${name}#NN]` });
}

const postScan = {
  highConfidenceSecrets: secretRules.reduce((count, [, pattern]) => count + (publicLog.match(new RegExp(pattern.source, pattern.flags))?.length ?? 0), 0),
  emailAddresses: publicLog.match(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi)?.length ?? 0,
  mainlandChinaPhoneNumbers: publicLog.match(/(?<![A-Za-z0-9.])1[3-9]\d{9}(?![A-Za-z0-9])/g)?.length ?? 0,
  authorizationHeaders: publicLog.match(/\bAuthorization\s*[:=]/gi)?.length ?? 0,
  originalHomePaths: localUserHome && publicLog.includes(localUserHome) ? 1 : 0,
  originalLocalUserNames: localUserName && publicLog.includes(localUserName) ? 1 : 0,
};
if (Object.values(postScan).some((value) => value !== 0)) throw new Error(`Public log privacy scan failed: ${JSON.stringify(postScan)}`);

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, publicLog);
const databaseExportPaths = [
  'db/session-row.json',
  'db/session-messages.json',
  'db/activities-20260809-0130-0701.json',
  'db/interaction-requests-20260809-0130-0701.json',
];
const placeholderCount = (relativePath) => {
  const path = join(assetRoot, relativePath);
  if (!existsSync(path)) return 0;
  return readFileSync(path, 'utf8').match(/<LOCAL_USER>/g)?.length ?? 0;
};
const databaseLocalUserReplacements = databaseExportPaths.reduce((sum, relativePath) => sum + placeholderCount(relativePath), 0);
const derivedVisualizationReplacements = placeholderCount('db/activities-20260809-0130-0701.json');
const report = {
  schemaVersion: 1,
  caseId: 'zhikuncode-king-20260809',
  policy: 'MINIMAL_REDACTION_PRESERVE_TECHNICAL_TRACE_IDS',
  developmentWindow: { startInclusiveLocal: start, endExclusiveLocal: end, timeZone: 'Asia/Shanghai' },
  mergeOrder: sources.map((source) => source.id),
  sources: sources.map(({ blocks, segment, ...source }) => source),
  merged: {
    timestampBlocks: rawBlocks.length,
    physicalLines: (rawMerged.match(/\n/g) ?? []).length,
    firstTimestamp: rawBlocks.at(0)?.timestamp ?? null,
    lastTimestamp: rawBlocks.at(-1)?.timestamp ?? null,
    rawMergedSha256: digest(rawMerged),
    identicalCrossSourceBlocks,
    sameBoundaryTimestamp,
    boundaryBlockCount: boundaryBlocks.length,
    boundaryBlocksDistinct: new Set(boundaryBlocks).size === boundaryBlocks.length,
  },
  redactions,
  publicArtifactRedactions: [{
    name: 'local-user-name',
    replacement: '<LOCAL_USER>',
    count: databaseLocalUserReplacements + derivedVisualizationReplacements,
    scope: '22 occurrences in four database exports plus 11 deterministic copies in visualization-data.json',
  }],
  databaseExports: {
    paths: databaseExportPaths,
    localUserNameRule: {
      name: 'local-user-name',
      replacement: '<LOCAL_USER>',
      count: databaseLocalUserReplacements,
      derivedVisualizationCopies: derivedVisualizationReplacements,
    },
  },
  preservedIdentifiers: ['sessionId', 'runId', 'parentRunId', 'llmRequestId', 'toolUseId', 'downstreamRequestId'],
  publicFile: {
    path: 'logs/app-session-20260809-0130-0701.public.log',
    sizeBytes: Buffer.byteLength(publicLog),
    physicalLines: (publicLog.match(/\n/g) ?? []).length,
    timestampBlocks: (publicLog.match(/^2026-08-09 /gm) ?? []).length,
    sha256: digest(publicLog),
  },
  postScan,
};
writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);

const provenance = existsSync(provenancePath) ? JSON.parse(readFileSync(provenancePath, 'utf8')) : {};
provenance.schemaVersion = 2;
provenance.logs = {
  ...(provenance.logs ?? {}),
  rule: 'Keep complete timestamp blocks with 2026-08-09 01:30:00.000 <= timestamp < 2026-08-09 07:01:00.000 Asia/Shanghai; preserve continuation lines; concatenate the first rotated segment before the captured continuation segment; do not deduplicate.',
  mergeProvenance: report,
  publicFile: report.publicFile,
  redactionReport: 'redaction-report.json',
};
provenance.logs.frozenFiles = (provenance.logs.frozenFiles ?? [])
  .filter((item) => item.path !== 'logs/app-session-20260809-0130-0701.log' && item.path !== report.publicFile.path);
provenance.logs.frozenFiles.unshift({
  path: report.publicFile.path,
  lines: report.publicFile.physicalLines,
  timestampBlocks: report.publicFile.timestampBlocks,
  sha256: report.publicFile.sha256,
  derivation: 'FILTERED_FROM_TWO_SOURCE_LOGS_THEN_MINIMALLY_REDACTED',
});
writeFileSync(provenancePath, `${JSON.stringify(provenance, null, 2)}\n`);

console.log(JSON.stringify({
  output: report.publicFile,
  sourceBlocks: sources.map((source) => source.selectedTimestampBlocks),
  boundary: { timestamp: sameBoundaryTimestamp, count: boundaryBlocks.length, distinct: report.merged.boundaryBlocksDistinct },
  redactions,
  postScan,
}, null, 2));
