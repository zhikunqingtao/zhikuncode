#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const reportPath = join(repoRoot, 'docs/case-studies/zhikuncode开发王者荣耀.html');
const assetRoot = join(repoRoot, 'docs/case-studies/assets/king');
const browserPath = join(assetRoot, 'browser-verification.json');
const layoutPath = join(assetRoot, 'svg-layout-audit.json');
const reportBytes = readFileSync(reportPath);
const reportHtml = reportBytes.toString('utf8');
const reportSha256 = createHash('sha256').update(reportBytes).digest('hex');
const reportFigureIds = [...reportHtml.matchAll(/data-viz-code="([A-Z]+-V\d{2})"/g)].map((match) => match[1]);

async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

export function updateBrowserVerification(measurement) {
requireCondition(measurement.measurementVersion === 1, 'Unsupported browser measurement version');
requireCondition(measurement.reportSha256 === reportSha256, `Browser measurement targets ${measurement.reportSha256}, current report is ${reportSha256}`);
requireCondition(new Set(reportFigureIds).size === 91 && reportFigureIds.length === 91, 'Current report does not contain 91 unique figures');

const expectedViewports = [[1280, 720], [1440, 900], [1920, 1080], [390, 844]];
requireCondition(
  JSON.stringify(measurement.viewports.map((entry) => [entry.width, entry.height])) === JSON.stringify(expectedViewports),
  'Browser measurement must contain 1280x720, 1440x900, 1920x1080 and 390x844 in that order',
);
for (const viewport of measurement.viewports) {
  requireCondition(viewport.figureCount === 91 && viewport.svgCount === 91, `${viewport.width}x${viewport.height} figure count differs`);
  requireCondition(viewport.pageOverflowPx === 0, `${viewport.width}x${viewport.height} has page overflow`);
  requireCondition(viewport.consoleErrors === 0, `${viewport.width}x${viewport.height} has console errors`);
  if (viewport.width >= 1280) requireCondition(viewport.stageOverflowIds.length === 0, `${viewport.width}x${viewport.height} has SVG-stage overflow`);
}

const primary = measurement.primaryLayout;
requireCondition(primary.width === 1280 && primary.height === 720, 'Primary layout must be measured at 1280x720');
requireCondition(primary.figures.length === 91, 'Primary layout does not cover 91 figures');
requireCondition(JSON.stringify(primary.figures.map((entry) => entry.id)) === JSON.stringify(reportFigureIds), 'Primary layout figure order differs from the current report');
for (const entry of primary.figures) {
  const bottomLimit = entry.viewBox.width >= 1400 ? .15 : .12;
  requireCondition(Object.values(entry.overflow).every((value) => value <= 1), `${entry.id} exceeds the 1-unit viewBox overflow threshold`);
  requireCondition(entry.textCollisionCount === 0, `${entry.id} contains a measured text collision`);
  requireCondition(entry.blankRatios.bottom <= bottomLimit, `${entry.id} bottom blank ratio ${entry.blankRatios.bottom} exceeds ${bottomLimit}`);
}

const interactions = measurement.interactions;
for (const field of ['hotspotClick', 'keyboardEnter', 'keyboardSpace', 'inspectorUpdated', 'searchFilter', 'auditToggle', 'lightbox', 'motionToggle', 'auditDefaultOpen', 'heroMedia']) {
  requireCondition(interactions[field] === true, `Browser interaction failed: ${field}`);
}
const media = measurement.media;
requireCondition(media.failedHtmlImages === 0 && media.failedSvgImages === 0, 'Browser media measurement contains failed images');
requireCondition(media.previewVideos === 5 && media.previewVideosReady === 5 && media.previewVideoErrors === 0, 'Browser video readiness differs');
requireCondition(measurement.content.evidenceLedgerRows === 42 && measurement.content.claimBoundaryRows === 19, 'Evidence/claim row counts differ');
requireCondition(measurement.content.tables === 39 && measurement.content.preformattedBlocks === 25, 'Table/preformatted counts differ');
requireCondition(measurement.content.details === measurement.content.openDetails && measurement.content.details > 0, 'The full audit corpus must be expanded by default');
requireCondition(measurement.semantics.platV09PublicationNode === false, 'PLAT-V09 still contains the deleted publication node');
requireCondition(
  JSON.stringify(measurement.semantics.platV09Entities) === JSON.stringify(['sessions', 'messages', 'activities', 'interaction_requests']),
  'PLAT-V09 entity list differs',
);
const onlineDeployment = measurement.onlineDeployment;
requireCondition(onlineDeployment?.classification === 'OUTSIDE_DEVELOPMENT_WINDOW_ALIYUN_HTTPS_DEPLOYMENT', 'Online deployment classification differs');
requireCondition(onlineDeployment.standard?.url === 'https://king.zhikun.xin/', 'Standard online URL differs');
requireCondition(onlineDeployment.standard?.title === 'KING_OK', 'Standard online title differs');
requireCondition(onlineDeployment.standard?.heroSelectContainer === true && onlineDeployment.standard?.heroCards === 5, 'Standard online hero selection check failed');
requireCondition(onlineDeployment.standard?.consoleErrors === 0 && onlineDeployment.standard?.resourceErrors === 0, 'Standard online page has console/resource errors');
requireCondition(onlineDeployment.demo?.url === 'https://king.zhikun.xin/?demo=1', 'Demo online URL differs');
requireCondition(onlineDeployment.demo?.title === 'KING_OK', 'Demo online title differs');
requireCondition(onlineDeployment.demo?.heroSelectContainer === false && onlineDeployment.demo?.timerAdvanced === true, 'Demo auto-start check failed');
requireCondition(onlineDeployment.demo?.canvasCount >= 2, 'Demo canvas rendering check failed');
requireCondition(onlineDeployment.demo?.consoleErrors === 0 && onlineDeployment.demo?.resourceErrors === 0, 'Demo online page has console/resource errors');

const observedAt = measurement.observedAt || new Date().toISOString();
const thresholds = {
  foregroundOverflowSvgUnits: 1,
  textIntersectionOfSmallerBoxRatio: .08,
  standardBottomBlankRatio: .12,
  complex1400BottomBlankRatio: .15,
};
const layoutFigures = primary.figures.map((entry) => ({
  ...entry,
  manualReview: 'measured',
  measurement: 'DIRECT_CHROMIUM_DOM_GEOMETRY',
}));
const layoutSummary = {
  figures: layoutFigures.length,
  foregroundOverflowFigures: layoutFigures.filter((entry) => Object.values(entry.overflow).some((value) => value > 1)).length,
  textCollisionFigures: layoutFigures.filter((entry) => entry.textCollisionCount > 0).length,
  excessiveBottomBlankFigures: layoutFigures.filter((entry) => entry.blankRatios.bottom > (entry.viewBox.width >= 1400 ? .15 : .12)).length,
  allMeasured: layoutFigures.every((entry) => entry.manualReview === 'measured'),
};
const layoutAudit = {
  schemaVersion: 3,
  caseId: 'zhikuncode-king-20260809',
  reportVersion: 'v15',
  classification: 'OUTSIDE_DEVELOPMENT_WINDOW_DIRECT_BROWSER_LAYOUT_AUDIT',
  observedAt,
  browser: measurement.browser,
  report: { path: 'zhikuncode开发王者荣耀.html', sha256: reportSha256, bytes: reportBytes.length },
  viewport: { width: primary.width, height: primary.height },
  thresholds,
  summary: layoutSummary,
  figures: layoutFigures,
  scope: '在1280×720真实Chromium DOM中逐图测量91张SVG的viewBox边界、文字碰撞与底部留白。',
};
writeFileSync(layoutPath, `${JSON.stringify(layoutAudit, null, 2)}\n`);
const layoutSha256 = createHash('sha256').update(readFileSync(layoutPath)).digest('hex');

const browserVerification = {
  schemaVersion: 10,
  caseId: 'zhikuncode-king-20260809',
  reportVersion: 'v15',
  classification: 'OUTSIDE_DEVELOPMENT_WINDOW_CURRENT_REPORT_BROWSER_VERIFICATION',
  observedAt,
  browser: measurement.browser,
  report: { path: 'zhikuncode开发王者荣耀.html', sha256: reportSha256, bytes: statSync(reportPath).size },
  content: measurement.content,
  viewports: measurement.viewports,
  layoutAudit: { path: 'svg-layout-audit.json', sha256: layoutSha256, summary: layoutSummary },
  interactions,
  media,
  semantics: measurement.semantics,
  onlineDeployment,
  scope: '单一当前v15快照；数据来自真实Chromium页面，并与当前HTML SHA-256绑定；完整审计层默认展开。',
};
writeFileSync(browserPath, `${JSON.stringify(browserVerification, null, 2)}\n`);

const result = {
  browserPath,
  layoutPath,
  reportSha256,
  observedAt,
  viewports: measurement.viewports.map(({ width, height, pageOverflowPx, stageOverflowIds }) => ({ width, height, pageOverflowPx, stageOverflow: stageOverflowIds.length })),
  layoutSummary,
};
console.log(JSON.stringify(result, null, 2));
return result;
}

if (typeof process !== 'undefined' && Array.isArray(process.argv) && resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) {
  const raw = await readStdin();
  requireCondition(raw.trim(), 'Expected a direct-browser measurement JSON document on stdin');
  updateBrowserVerification(JSON.parse(raw));
}
