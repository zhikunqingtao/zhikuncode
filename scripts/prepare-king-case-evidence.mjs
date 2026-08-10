#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
const assetRoot = join(repoRoot, 'docs/case-studies/assets/king');
const dbPath = join(repoRoot, 'backend/.ai-code-assistant/data.db');
const sourceProject = resolve(repoRoot, '../../project/king');
const sourceVideoDir = join(dirname(sourceProject), '过程录屏');
const sessionId = 'b8f86099-452d-4ba6-89c2-c3fee8f4b422';
const windowStartUtc = '2026-08-08T17:30:00Z';
const windowEndUtc = '2026-08-08T23:01:00Z';
const plannedReleaseBase = 'https://github.com/zhikunqingtao/zhikuncode/releases/download/king-evidence-v1';
const localUserName = repoRoot.match(/^\/Users\/([^/]+)/)?.[1] ?? null;
const localUserHome = localUserName ? `/Users/${localUserName}` : null;

function sha256(path) {
  const hash = createHash('sha256');
  hash.update(readFileSync(path));
  return hash.digest('hex');
}

function writeJson(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

const publicPathReplacements = [
  [sourceProject, '<PROJECT_ROOT>'],
  [repoRoot, '<REPO_ROOT>'],
  ...(localUserHome ? [[localUserHome, '<USER_HOME>']] : []),
];

function sanitizePublicString(value) {
  let result = value;
  for (const [from, to] of publicPathReplacements) result = result.replaceAll(from, to);
  if (localUserName) result = result.replaceAll(localUserName, '<LOCAL_USER>');
  return result;
}

function sanitizePublicValue(value) {
  if (typeof value === 'string') return sanitizePublicString(value);
  if (Array.isArray(value)) return value.map(sanitizePublicValue);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, sanitizePublicValue(item)]));
  }
  return value;
}

function publicMessageRow(row) {
  const result = sanitizePublicValue(row);
  if (typeof row.content_json !== 'string') return result;
  try {
    const blocks = JSON.parse(row.content_json);
    if (!Array.isArray(blocks)) return result;
    result.content_json = JSON.stringify(sanitizePublicValue(blocks.filter((block) => block?.type !== 'thinking')));
  } catch {
    result.content_json = sanitizePublicString(row.content_json);
  }
  return result;
}

function query(sql) {
  const output = execFileSync('sqlite3', ['-json', dbPath, sql], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  return output.trim() ? JSON.parse(output) : [];
}

function ffprobe(path) {
  const output = execFileSync('ffprobe', [
    '-v', 'error', '-show_entries',
    'format=duration,size:stream=codec_name,codec_type,width,height',
    '-of', 'json', path,
  ], { encoding: 'utf8' });
  const parsed = JSON.parse(output);
  const video = parsed.streams.find((stream) => stream.codec_type === 'video');
  const audio = parsed.streams.find((stream) => stream.codec_type === 'audio');
  return {
    durationSeconds: Number(Number(parsed.format.duration).toFixed(3)),
    sizeBytes: Number(parsed.format.size),
    videoCodec: video?.codec_name ?? null,
    width: video?.width ?? null,
    height: video?.height ?? null,
    audioCodec: audio?.codec_name ?? null,
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

function aggregateTree(root, ignoredNames = new Set(['.DS_Store'])) {
  const rows = walkFiles(root)
    .filter((path) => !ignoredNames.has(basename(path)))
    .map((path) => `${sha256(path)}  ${relative(root, path)}`);
  return {
    files: rows.length,
    digest: createHash('sha256').update(`${rows.join('\n')}\n`).digest('hex'),
  };
}

if (!existsSync(dbPath)) throw new Error(`Missing source database: ${dbPath}`);

const sessionRows = query(`SELECT * FROM sessions WHERE id='${sessionId}'`);
if (sessionRows.length !== 1) throw new Error(`Expected one session row, found ${sessionRows.length}`);

const messages = query(`
  SELECT * FROM messages
  WHERE session_id='${sessionId}'
    AND created_at >= '${windowStartUtc}'
    AND created_at < '${windowEndUtc}'
  ORDER BY seq_num, created_at, id
`);
const activities = query(`
  SELECT * FROM activities
  WHERE session_id='${sessionId}'
    AND timestamp >= unixepoch('${windowStartUtc}') * 1000
    AND timestamp < unixepoch('${windowEndUtc}') * 1000
  ORDER BY timestamp, id
`);
const interactions = query(`
  SELECT * FROM interaction_requests
  WHERE session_id='${sessionId}'
    AND created_at >= '${windowStartUtc}'
    AND created_at < '${windowEndUtc}'
  ORDER BY created_at, interaction_id
`);
const publicMessages = messages.map(publicMessageRow);
const publicActivities = sanitizePublicValue(activities);
const publicInteractions = sanitizePublicValue(interactions);

writeJson(join(assetRoot, 'db/session-row.json'), sanitizePublicValue(sessionRows[0]));
writeJson(join(assetRoot, 'db/session-messages.json'), publicMessages);
writeJson(join(assetRoot, 'db/activities-20260809-0130-0701.json'), {
  schemaVersion: 2,
  sessionId,
  window: { startInclusive: windowStartUtc, endExclusive: windowEndUtc, timeZone: 'Asia/Shanghai' },
  publicationTransform: 'LOCAL_PATHS_REPLACED_WITH_PORTABLE_PLACEHOLDERS',
  rowCount: publicActivities.length,
  records: publicActivities,
});
writeJson(join(assetRoot, 'db/interaction-requests-20260809-0130-0701.json'), {
  schemaVersion: 2,
  sessionId,
  window: { startInclusive: windowStartUtc, endExclusive: windowEndUtc, timeZone: 'Asia/Shanghai' },
  publicationTransform: 'LOCAL_PATHS_REPLACED_WITH_PORTABLE_PLACEHOLDERS',
  rowCount: publicInteractions.length,
  records: publicInteractions,
});
const obsoletePublicationSupplement = join(assetRoot, 'db/interaction-requests-publication-supplement.json');
if (existsSync(obsoletePublicationSupplement)) unlinkSync(obsoletePublicationSupplement);

const videoDefinitions = [
  {
    original: '钉钉录屏_2026-08-09 013111.mp4', preview: '01-开发过程-4x.mp4', speed: 4,
    releaseAssetName: 'king-development-013111-original.mp4',
    classification: 'DEVELOPMENT_WINDOW', captureTimeFromFilename: '2026-08-09T01:31:11+08:00',
  },
  {
    original: '钉钉录屏_2026-08-09 054310.mp4', preview: '02-玩法开发-2x.mp4', speed: 2,
    releaseAssetName: 'king-gameplay-054310-original.mp4',
    classification: 'DEVELOPMENT_WINDOW', captureTimeFromFilename: '2026-08-09T05:43:10+08:00',
  },
  {
    original: '钉钉录屏_2026-08-09 060909.mp4', preview: '03-视觉修复-1x.mp4', speed: 1,
    releaseAssetName: 'king-visual-fix-060909-original.mp4',
    classification: 'DEVELOPMENT_WINDOW', captureTimeFromFilename: '2026-08-09T06:09:09+08:00',
  },
  {
    original: '钉钉录屏_2026-08-09 092155（最终运行版）.mp4', preview: '04-最终运行-4x.mp4', speed: 4,
    releaseAssetName: 'king-final-run-092155-original.mp4',
    classification: 'OUTSIDE_DEVELOPMENT_WINDOW_FINAL_RUN_SUPPLEMENT', captureTimeFromFilename: '2026-08-09T09:21:55+08:00',
  },
  {
    original: '阿里云在线试玩录屏.mp4', preview: '05-阿里云在线试玩-2x.mp4', speed: 2,
    releaseAssetName: 'king-cloud-demo-115336-original.mp4',
    classification: 'OUTSIDE_DEVELOPMENT_WINDOW_CLOUD_DEPLOYMENT_SUPPLEMENT', captureTimeFromFilename: '2026-08-09T11:53:36+08:00',
  },
];

mkdirSync(join(assetRoot, 'videos'), { recursive: true });
for (const item of videoDefinitions) {
  const source = join(sourceVideoDir, item.original);
  const bundled = join(assetRoot, 'videos', item.original);
  if (!existsSync(source)) throw new Error(`Missing source video: ${source}`);
  if (!existsSync(bundled)) copyFileSync(source, bundled);
  if (sha256(source) !== sha256(bundled)) throw new Error(`Bundled video differs from source: ${item.original}`);
}

const releaseAssets = videoDefinitions.map((item) => {
  const originalPath = join(assetRoot, 'videos', item.original);
  const previewPath = join(assetRoot, 'videos/previews', item.preview);
  return {
    ...item,
    originalPath: `videos/${item.original}`,
    originalSha256: sha256(originalPath),
    originalMedia: ffprobe(originalPath),
    previewPath: existsSync(previewPath) ? `videos/previews/${item.preview}` : null,
    previewSha256: existsSync(previewPath) ? sha256(previewPath) : null,
    previewMedia: existsSync(previewPath) ? ffprobe(previewPath) : null,
    releaseAvailability: 'PENDING_RELEASE_CREATION',
    plannedReleaseUrl: `${plannedReleaseBase}/${item.releaseAssetName}`,
  };
});
writeJson(join(assetRoot, 'release-assets.json'), {
  schemaVersion: 2,
  publicationStatus: 'PENDING_USER_APPROVAL',
  intendedReleaseTag: 'king-evidence-v1',
  plannedReleasePage: 'https://github.com/zhikunqingtao/zhikuncode/releases/tag/king-evidence-v1',
  captureTimeBasis: 'FILENAME_METADATA_ONLY_NOT_A_TRUSTED_TIMESTAMP',
  note: 'Original HEVC files are audit masters. Preview files are derived H.264/AAC assets and are not byte-equivalent to the originals.',
  assets: releaseAssets,
});

const sourceLogs = [
  'log/app-2026-08-09-1.log.gz',
  'log/app-2026-08-09-2.log.gz',
  'log/security-audit.log',
  'log/observability-events.log',
].map((path) => {
  const absolute = join(repoRoot, path);
  return { path, exists: existsSync(absolute), sha256: existsSync(absolute) ? sha256(absolute) : null };
});

writeJson(join(assetRoot, 'provenance.json'), {
  schemaVersion: 1,
  caseId: 'zhikuncode-king-20260809',
  generatedAt: new Date().toISOString(),
  sessionId,
  timeZone: 'Asia/Shanghai',
  developmentWindow: {
    startInclusiveLocal: '2026-08-09T01:30:00+08:00',
    endExclusiveLocal: '2026-08-09T07:01:00+08:00',
    startInclusiveUtc: windowStartUtc,
    endExclusiveUtc: windowEndUtc,
  },
  sourceProject: {
    sourcePathAtCapture: '<PROJECT_ROOT>',
    excluded: ['.DS_Store'],
    aggregate: aggregateTree(sourceProject),
    evidenceCopyPath: 'code',
  },
  database: {
    sourcePath: relative(repoRoot, dbPath),
    note: 'The live database is mutable. Public exports are frozen by session id and development-window bounds.',
    queries: {
      session: `SELECT * FROM sessions WHERE id='${sessionId}'`,
      messages: `session_id='${sessionId}' AND created_at >= '${windowStartUtc}' AND created_at < '${windowEndUtc}' ORDER BY seq_num, created_at, id`,
      activities: `session_id='${sessionId}' AND timestamp >= unixepoch('${windowStartUtc}')*1000 AND timestamp < unixepoch('${windowEndUtc}')*1000`,
      interactions: `session_id='${sessionId}' AND created_at >= '${windowStartUtc}' AND created_at < '${windowEndUtc}'`,
    },
    frozenCounts: { messages: messages.length, activities: activities.length, interactionRequests: interactions.length },
    publicExport: {
      localPathPolicy: 'Replace the source project, repository root, user home, and local OS account name with <PROJECT_ROOT>, <REPO_ROOT>, <USER_HOME>, and <LOCAL_USER>.',
      localUserPlaceholder: '<LOCAL_USER>',
      messageRows: publicMessages.length,
      thinkingBlocksRemoved: messages.reduce((sum, row) => {
        try { return sum + JSON.parse(row.content_json).filter((block) => block?.type === 'thinking').length; }
        catch { return sum; }
      }, 0),
      retainedBlockTypes: [...new Set(publicMessages.flatMap((row) => {
        try { return JSON.parse(row.content_json).map((block) => block?.type).filter(Boolean); }
        catch { return []; }
      }))].sort(),
    },
  },
  logs: {
    sourceFiles: sourceLogs,
    rule: 'Keep timestamp blocks whose first timestamp is >= 2026-08-09 01:30:00 and < 2026-08-09 07:01:00 Asia/Shanghai; continuation lines stay with their timestamp block.',
    frozenFiles: [
      { path: 'logs/app-session-20260809-0130-0701.log', lines: 38641 },
      { path: 'logs/observability-events-20260809-0130-0701.jsonl', lines: 2003 },
      { path: 'logs/security-audit-20260809-0130-0701.log', lines: 116 },
    ],
  },
  videos: {
    sourceDirectoryAtCapture: '<USER_HOME>/Desktop/dev/project/过程录屏',
    originalPolicy: 'Original recordings are copied byte-for-byte and kept as audit masters.',
    previewPolicy: 'Public browser previews may be renamed, accelerated, scaled, padded, and transcoded; release-assets.json maps each preview to its original SHA-256.',
  },
  trustLimitations: [
    'SHA-256 proves integrity relative to the manifest, not the original capture time or authorship.',
    'Filename timestamps and filesystem birth times are metadata, not trusted timestamps.',
    'Application logs, observability logs, and SQLite exports are local sources in a shared trust domain.',
    'The provider bill CSV is a separate exported source but carries no provider cryptographic signature in this bundle.',
  ],
});

console.log(`Prepared public evidence exports: messages=${publicMessages.length}, activities=${activities.length}, interactions=${interactions.length}`);
console.log(`Source project aggregate: ${aggregateTree(sourceProject).digest}`);
