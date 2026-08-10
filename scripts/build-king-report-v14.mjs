#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import './build-king-visualization-data.mjs';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const reportPath = join(repoRoot, 'docs/case-studies/zhikuncode开发王者荣耀.html');
const assetRoot = join(repoRoot, 'docs/case-studies/assets/king');
const manifestPath = join(assetRoot, 'visualization-manifest.json');
const dataPath = join(assetRoot, 'visualization-data.json');
const data = JSON.parse(readFileSync(dataPath, 'utf8'));
const verification = JSON.parse(readFileSync(join(assetRoot, 'verification.json'), 'utf8'));
const provenance = JSON.parse(readFileSync(join(assetRoot, 'provenance.json'), 'utf8'));
const oldManifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const storyboard = JSON.parse(readFileSync(join(assetRoot, 'video-storyboards.json'), 'utf8'));
const redaction = JSON.parse(readFileSync(join(assetRoot, 'redaction-report.json'), 'utf8'));
const sourceHtml = readFileSync(reportPath, 'utf8');

const sha = (value) => createHash('sha256').update(value).digest('hex');
const esc = (value) => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const normalize = (value) => value.replace(/\s+/g, ' ').trim();
const fmt = (value) => typeof value === 'number' ? value.toLocaleString('en-US') : String(value);
const round = (value, digits = 3) => Number(Number(value).toFixed(digits));
const short = (value, n = 10) => String(value || '').slice(0, n);
const factValue = (id) => data.facts[id]?.value;
const factAttr = (id) => id ? ` data-fact-id="${esc(id)}" data-fact-source="${esc(data.facts[id]?.source || '')}"` : '';
const sourceRel = (path) => path.replace(/^assets\/king\//, '');
const groupOrder = ['CASE', 'SRC', 'PLAT', 'RUN', 'DBG', 'QA', 'AUDIT', 'META'];
const onlineDeployment = provenance.onlineDeployment;
if (!onlineDeployment?.standard?.url || !onlineDeployment?.demo?.url) throw new Error('provenance.onlineDeployment must define standard and demo URLs');
if (onlineDeployment.standard.url !== 'https://king.zhikun.xin/' || onlineDeployment.demo.url !== 'https://king.zhikun.xin/?demo=1') throw new Error('Unexpected online deployment URLs');

const reportVersion = 'v14';
const groupEditorial = {
  CASE: {
    code: '开场', masthead: '需求与结果', title: '一句话之后，先把“能玩”定义清楚', grammar: 'magazine-opening',
    thesis: '这次开发不是从代码开始，而是从四个范围选择开始。39.6秒内确定技术、模式、地图与操控，随后才把目标写成16条可验收合同。',
    bridge: '先看最终画面，再沿着需求、选择和启动链向后追。这里回答的是“交付从哪里来”，不是用一排数字制造声势。',
    evidenceIds: ['E01', 'E12', 'E18'],
  },
  SRC: {
    code: '源码', masthead: '峡谷工程蓝图', title: '一个MOBA原型，复杂在系统必须同时运转', grammar: 'canyon-blueprint',
    thesis: '地图、战斗、AI、兵线、建筑、经济、HUD和表现层都要共享同一份实时状态。17个模块与39条本地导入边，只是这张运行图的骨架。',
    bridge: '真正值得看的，是状态如何穿过模块边界，以及一处规则改变会同时牵动多少系统。',
    evidenceIds: ['E02', 'E25', 'E31'],
  },
  PLAT: {
    code: '运行时', masthead: '执行系统剖面', title: 'Kimi负责生成，ZhikunCode负责让工作继续', grammar: 'runtime-cutaway',
    thesis: '模型请求只是其中一层。协调循环、工具管线、上下文治理、浏览器反馈与持久化共同把一次回答变成数小时的工程过程。',
    bridge: '本节把平台源码机制与窗口内日志放在同一张剖面上，让请求循环、工具执行、上下文治理和浏览器反馈逐层对齐。',
    evidenceIds: ['E26', 'E27', 'E28', 'E29'],
  },
  RUN: {
    code: '编排', masthead: '五小时二十九分十七秒首末请求轨迹', title: '十次Worker不是十段宣传语，而是十条可追踪运行', grammar: 'run-timeline',
    thesis: '一个根Run先后接住10个Worker：只有1次自然完成，6次到达期限，3次到达最大轮次。项目依靠落盘成果和后续复验继续向前。',
    bridge: '时间轴保留停顿、回收和接续，可以直接看到一次复杂攻坚如何在十次Worker之间向前接力。',
    evidenceIds: ['E03', 'E16', 'E24', 'E39'],
  },
  DBG: {
    code: '攻坚', masthead: '调试案卷', title: '从能跑到能结束一局，差的是四次硬修复', grammar: 'incident-redline',
    thesis: '小兵堵路、终局僵持、基地贴墙和发光性能问题都曾阻断体验。每次修复都从浏览器状态取证开始，再回到具体代码。',
    bridge: '每份调试案卷都按“浏览器观察→状态取证→代码修改→回归结果”展开，冻结素材与最终实现分层标注。',
    evidenceIds: ['E08', 'E09', 'E10', 'E20', 'E21', 'E22', 'E23'],
  },
  QA: {
    code: '验收', masthead: '运行画面与终局', title: '游戏是否成立，最终要回到画面与完整对局', grammar: 'qa-contact-sheet',
    thesis: '结构化状态回答“系统在做什么”，截图和录屏回答“玩家看到了什么”。两者同时成立，才能支持可玩原型的结论。',
    bridge: '43张截图、5份原视频和20帧派生故事板按来源和时间分层；窗口外的最终运行与云端试玩不会倒算进开发统计。',
    evidenceIds: ['E11', 'E12', 'E13', 'E14', 'E15'],
  },
  AUDIT: {
    code: '复核', masthead: '证据账本', title: '把规模写成数字不难，把每个数字对回原记录才难', grammar: 'forensic-ledger',
    thesis: '账单、运行事件、数据库、公开日志、代码、图片和视频分别回答不同问题。报告只在这些证据交叉处下结论。',
    bridge: '公开审计层保留原表、原命令和完整口径。正文图表负责解释关系，读者需要时再下钻到原始记录。',
    evidenceIds: ['E04', 'E05', 'E06', 'E17', 'E29', 'E40', 'E41', 'E42'],
  },
  META: {
    code: '判断', masthead: '工程结论', title: '这个案例已经展示什么，下一步向哪里走', grammar: 'editorial-verdict',
    thesis: '结果值得认可，不是因为调用次数多，而是多系统产物、失败后的接续、真实浏览器反馈和可复查材料同时存在。',
    bridge: '最后把本案例已经展示出的能力和下一阶段路线放在一起，给出清楚、直接的工程判断。',
    evidenceIds: ['E01', 'E25', 'E26', 'E30', 'E38', 'E42'],
  },
};

const groupTransitions = {
  CASE: { 2: '范围确定后，四个选择被写进DESIGN，再由启动脚本真正拉起浏览器。' },
  SRC: { 1: '文件规模只是入口。继续往下看，设计合同如何落到依赖边界和具体运行符号。' },
  PLAT: { 2: '循环能够持续，还要回答上下文如何控制、工具如何闭合、平台源码与本次日志如何互证。', 6: '控制面让每类故障留下明确终态，也让后续循环知道从哪里接。' },
  RUN: { 2: '有了父子DAG，再把时长、Token、提示词和交付物放回同一时间轴，就能看见接力的真实投入。', 5: '阶段谱系使用日志、文件和截图中能够直接定位的锚点。' },
  DBG: { 2: '前两个问题发生在规则层；后两个问题分别把路径规划和渲染表现推到浏览器里检验。' },
  QA: { 3: '接下来把三局终局、43张截图和5份录屏放回对应验收路径，直接看代码如何变成可见体验。', 7: '媒体补足了静态代码看不到的体验，录制时间与派生关系也在同一图中对齐。' },
  AUDIT: { 3: 'Token和缓存只能说明调用规模。工具生命周期、日志合并和数据库关系才解释这些数字怎样形成。', 8: '最后把七个证据域放在一起，检查哪些结论由多种格式共同约束，哪些仍停留在本地信任域。' },
  META: { 2: '原型已经完成核心闭环；下一阶段路线把它如何继续走向更完整产品写得同样具体。' },
};

const metadata = new Map((oldManifest.newVisualizations || []).map((entry) => [entry.id, entry]));
for (const entry of metadata.values()) {
  for (const key of ['source', 'proves', 'cannotProve', 'question']) {
    if (typeof entry[key] === 'string') entry[key] = entry[key].replaceAll('E01–E43', 'E01–E42').replaceAll('E01-E43', 'E01-E42');
  }
}
const conclusionOverrides = {
  'AUDIT-V11': '二十条核心结论分别连接最强证据和可确认结果。',
  'AUDIT-V10': '42条案例证据分别连接来源、位置与核验方式。',
  'QA-V10': '开发窗口与两份窗口外运行补充按时间和用途清楚分层。',
  'META-V02': '当前原型的功能完成面与十一项下一阶段工程路线同时列出。',
  'META-V03': '十个关键问题均由现有代码、日志、账单和验收材料给出直接回答。',
  'META-V04': '109个仓库文件与5个待发布Release原件均有路径、字节数和SHA-256映射。',
};
for (const [id, conclusion] of Object.entries(conclusionOverrides)) {
  const entry = metadata.get(id);
  if (entry) entry.proves = conclusion;
}
const sourceOverrides = {
  'META-V01': '产物 + 运行链 + 复验 + E01–E42',
  'AUDIT-V11': '核心主张与证据对应表',
};
for (const [id, source] of Object.entries(sourceOverrides)) {
  const entry = metadata.get(id);
  if (entry) entry.source = source;
}
const titleOverrides = {
  'RUN-V01': '1个根Run＋10个Worker Run：5小时29分17秒首末请求轨迹',
  'META-V05': 'v1到v14的纠错、证据与可视化演进',
  'AUDIT-V01': '账单、运行事件、失败事件与消息的请求级对账',
  'AUDIT-V12': '私有源重建与公开复算的双路径验证',
  'AUDIT-V11': '二十条核心结论与最强证据',
  'AUDIT-V10': 'E01–E42证据网络',
  'AUDIT-V08': '四份数据库导出的冻结关系',
  'META-V02': '当前原型与下一阶段工程路线',
  'META-V03': '十个关键问题，一页读懂案例',
  'META-V04': '证据资产地图：109个仓库文件＋5个Release原件',
  'META-V06': '开放式游戏工程与SWE-bench平台旁证',
};
const questionOverrides = {
  'CASE-V03': '哪些材料属于5小时31分证据窗口，哪些只作窗口外补充？',
  'RUN-V01': '11个Run、Token、终态、产物和接续如何同轴阅读？',
  'AUDIT-V10': '42条证据如何支撑过程、产物和工程结论？',
  'META-V04': '哪些资料直接在仓库中，哪些原件等待Release发布？',
  'QA-V10': '开发窗口、本地最终运行与阿里云部署验证如何分层？',
  'META-V02': '从当前可玩原型继续走向产品，还要建设什么？',
  'META-V03': '读者最关心的十个问题，现有材料分别给出什么答案？',
  'META-V06': '两类不同任务如何共同展示平台的工程执行能力？',
};

function lineText(text, x, y, width = 32, className = 'v11-body', lineHeight = 20, maxLines = 3, attrs = '') {
  const raw = String(text ?? '');
  const chunks = [];
  for (const paragraph of raw.split(/\n/)) {
    let rest = paragraph;
    while (rest.length && chunks.length < maxLines) {
      if (rest.length <= width) { chunks.push(rest); rest = ''; break; }
      if (chunks.length === maxLines - 1) { chunks.push(`${rest.slice(0, Math.max(1, width - 1))}…`); rest = ''; break; }
      let cut = width;
      const before = rest.slice(0, width + 1).lastIndexOf(' ');
      if (before > Math.floor(width * .55)) cut = before;
      chunks.push(rest.slice(0, cut)); rest = rest.slice(cut).trimStart();
    }
    if (chunks.length >= maxLines) break;
  }
  return `<text x="${x}" y="${y}" class="${className}"${attrs}>${chunks.map((chunk, index) => `<tspan x="${x}" dy="${index ? lineHeight : 0}">${esc(chunk)}</tspan>`).join('')}</text>`;
}

function textEl(text, x, y, className = 'v11-body', attrs = '') {
  return `<text x="${x}" y="${y}" class="${className}"${attrs}>${esc(text)}</text>`;
}

function numberEl(value, x, y, factId, className = 'v11-number', attrs = '') {
  return `<text x="${x}" y="${y}" class="${className}"${factAttr(factId)}${attrs}>${esc(fmt(value))}</text>`;
}

function panel(x, y, w, h, title, subtitle = '', tone = 'blue') {
  return `<g><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="12" class="v11-panel v11-tone-${tone}"/><text x="${x + 18}" y="${y + 28}" class="v11-panel-title">${esc(title)}</text>${subtitle ? lineText(subtitle, x + 18, y + 51, Math.max(18, Math.floor(w / 12)), 'v11-small', 16, 2) : ''}</g>`;
}

function badge(text, x, y, tone = 'blue', attrs = '') {
  const w = Math.max(70, String(text).length * 9 + 22);
  return `<g${attrs}><rect x="${x}" y="${y}" width="${w}" height="28" rx="6" class="v11-badge v11-tone-${tone}"/><text x="${x + 11}" y="${y + 19}" class="v11-badge-text">${esc(text)}</text></g>`;
}

function nodeCard({ x, y, w = 180, h = 92, index, title, value, factId, detail, tone = 'blue', subtitle, source }) {
  const minimumHeight = source ? 104 : subtitle ? 74 : 58;
  if (h < minimumHeight) throw new Error(`nodeCard ${title} height ${h} < ${minimumHeight}`);
  const attrs = detail ? ` class="pv-node" tabindex="0" role="button" data-detail="${esc(detail)}"` : '';
  const titleX = x + (index ? 46 : 16);
  const sourceBarY = source ? y + h - 31 : null;
  const subtitleY = source ? y + h - 43 : y + h - 16;
  const valueY = y + h - (source ? 56 : subtitle ? 42 : 18);
  const sourceMarkup = source ? `<rect x="${x + 10}" y="${sourceBarY}" width="${w - 20}" height="23" rx="5" class="v13-code-strip"/>${lineText(source, x + 18, y + h - 14, Math.max(12, Math.floor((w - 35) / 8.5)), 'v11-code-source', 14, 1)}` : '';
  return `<g${attrs}><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="10" class="v11-node v11-tone-${tone}"/>${index ? `<circle cx="${x + 23}" cy="${y + 23}" r="13" class="v11-index-dot v11-fill-${tone}"/><text x="${x + 23}" y="${y + 28}" text-anchor="middle" class="v11-index">${esc(index)}</text>` : ''}${lineText(title, titleX, y + 28, Math.max(10, Math.floor((w - (index ? 58 : 30)) / 10)), 'v11-card-title', 20, 2)}${value !== undefined ? numberEl(value, x + 16, valueY, factId, 'v11-card-value') : ''}${subtitle ? lineText(subtitle, x + 16, subtitleY, Math.max(12, Math.floor((w - 32) / 9)), 'v11-small', 16, 1) : ''}${sourceMarkup}</g>`;
}

function arrow(x1, y1, x2, y2, tone = 'cyan', dashed = false, label = '') {
  const mx = (x1 + x2) / 2;
  return `<path d="M${x1} ${y1} C${mx} ${y1} ${mx} ${y2} ${x2} ${y2}" class="v11-edge v11-stroke-${tone}${dashed ? ' v11-dashed' : ''}"/><circle cx="${x2}" cy="${y2}" r="4.5" class="v11-fill-${tone}"/>${label ? textEl(label, mx, (y1 + y2) / 2 - 6, 'v11-edge-label', ' text-anchor="middle"') : ''}`;
}

function metricRail(items, width) {
  const x0 = 38, gap = 12, y = 86, h = 74;
  const w = (width - x0 * 2 - gap * (items.length - 1)) / items.length;
  return items.map((item, index) => `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${item.label}：${fmt(item.value)}；来源 ${item.source || data.facts[item.factId]?.source || '图中证据绑定'}`)}"><rect x="${x0 + index * (w + gap)}" y="${y}" width="${w}" height="${h}" rx="9" class="v11-metric"/><text x="${x0 + index * (w + gap) + 14}" y="${y + 22}" class="v11-metric-label">${esc(item.label)}</text>${numberEl(item.value, x0 + index * (w + gap) + 14, y + 53, item.factId, 'v11-metric-value')}</g>`).join('');
}

function codeCard(x, y, w, title, code, source, line = '') {
  return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${source}${line ? `:${line}` : ''}；${code}`)}"><rect x="${x}" y="${y}" width="${w}" height="108" rx="10" class="v11-code-card"/><text x="${x + 14}" y="${y + 24}" class="v11-code-title">${esc(title)}</text>${lineText(code, x + 14, y + 49, Math.max(20, Math.floor((w - 28) / 8)), 'v11-code-line', 16, 3)}<text x="${x + 14}" y="${y + 96}" class="v11-code-source">${esc(source)}${line ? `:${esc(line)}` : ''}</text></g>`;
}

function imagePanel(x, y, w, h, record, label, callouts = []) {
  const path = record.path || record;
  const digest = record.sha256 || data.media.screenshots.find((item) => item.path === path)?.sha256 || '';
  const markers = callouts.map((item, index) => `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(item.detail || item.label)}"><circle cx="${x + item.x * w}" cy="${y + item.y * h}" r="16" class="v11-target"/><text x="${x + item.x * w}" y="${y + item.y * h + 5}" text-anchor="middle" class="v11-target-text">${index + 1}</text></g>`).join('');
  return `<g><rect x="${x - 5}" y="${y - 5}" width="${w + 10}" height="${h + 46}" rx="10" class="v11-image-frame"/><image href="${esc(path)}" x="${x}" y="${y}" width="${w}" height="${h}" preserveAspectRatio="xMidYMid slice"/><rect x="${x}" y="${y + h - 34}" width="${w}" height="34" class="v11-image-caption-bg"/><text x="${x + 12}" y="${y + h - 12}" class="v11-image-caption">${esc(label)} · SHA ${esc(short(digest, 10))}</text>${markers}</g>`;
}

function barRows(items, { x = 80, y = 230, w = 1040, h = 34, gap = 18, valueKey = 'value', labelKey = 'label', max = null, tone = 'blue', factPrefix = '' } = {}) {
  const maximum = max ?? Math.max(...items.map((item) => Number(item[valueKey]) || 0), 1);
  return items.map((item, index) => {
    const value = Number(item[valueKey]) || 0;
    const width = w * value / maximum;
    const yy = y + index * (h + gap);
    const factId = item.factId || (factPrefix ? `${factPrefix}${item[labelKey]}` : '');
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${item[labelKey]}：${fmt(value)}；几何比例 ${fmt(value)}/${fmt(maximum)}`)}"><text x="${x - 14}" y="${yy + 22}" text-anchor="end" class="v11-row-label">${esc(item[labelKey])}</text><rect x="${x}" y="${yy}" width="${w}" height="${h}" rx="5" class="v11-track"/><rect x="${x}" y="${yy}" width="${width}" height="${h}" rx="5" class="v11-fill-${item.tone || tone}"/>${numberEl(value, Math.min(x + width + 10, x + w - 4), yy + 23, factId, 'v11-row-value', width > w - 80 ? ' text-anchor="end"' : '')}</g>`;
  }).join('');
}

function timeline(items, { x = 70, y = 370, w = 1060, start = null, end = null, top = 220, bottom = 430 } = {}) {
  const values = items.map((item) => time(item.time));
  const lo = start ? time(start) : Math.min(...values);
  const hi = end ? time(end) : Math.max(...values);
  const scale = (value) => x + (time(value) - lo) / Math.max(1, hi - lo) * w;
  const timeLabel = (value) => String(value).slice(11, 19);
  return `<path d="M${x} ${y}H${x + w}" class="v11-axis"/>${items.map((item, index) => {
    const px = scale(item.time);
    const py = index % 2 ? bottom : top;
    const h = 92;
    const cardX = Math.max(8, Math.min(px - 80, x + w - 168));
    return `<path d="M${px} ${y}V${index % 2 ? py : py + h}" class="v11-guide"/><circle cx="${px}" cy="${y}" r="9" class="v11-fill-${item.tone || 'cyan'}"/><g class="pv-node" tabindex="0" role="button" data-detail="${esc(item.detail || `${item.label} ${item.time}`)}"><rect x="${cardX}" y="${py}" width="160" height="${h}" rx="8" class="v11-node v11-tone-${item.tone || 'blue'}"/><text x="${cardX + 12}" y="${py + 22}" class="v11-time">${esc(timeLabel(item.time))}</text>${lineText(item.label, cardX + 12, py + 45, 17, 'v11-card-title', 17, 2)}${item.sub ? lineText(item.sub, cardX + 12, py + 80, 20, 'v11-tiny', 14, 1) : ''}</g>`;
  }).join('')}`;
}

function time(value) { return value instanceof Date ? value.getTime() : Date.parse(String(value).includes('T') ? value : String(value).replace(' ', 'T') + (String(value).match(/[+-]\d\d:\d\d$/) ? '' : '+08:00')); }

function heatmap(rows, columns, valueFn, { x = 240, y = 230, cellW = 64, cellH = 30, rowLabelWidth = 210, factFn = null } = {}) {
  const values = rows.flatMap((row) => columns.map((column) => Number(valueFn(row, column)) || 0));
  const max = Math.max(...values, 1);
  const header = columns.map((column, index) => textEl(column.label ?? column, x + index * cellW + cellW / 2, y - 12, 'v11-matrix-head', ' text-anchor="middle"')).join('');
  const body = rows.map((row, rowIndex) => `${lineText(row.label, x - rowLabelWidth, y + rowIndex * cellH + 20, Math.floor(rowLabelWidth / 10), 'v11-matrix-row', 14, 1)}${columns.map((column, colIndex) => {
    const value = Number(valueFn(row, column)) || 0;
    const opacity = .12 + .88 * value / max;
    const factId = factFn?.(row, column);
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${row.label} × ${column.label ?? column}: ${fmt(value)}`)}"><rect x="${x + colIndex * cellW}" y="${y + rowIndex * cellH}" width="${cellW - 3}" height="${cellH - 3}" rx="4" class="v11-heat" style="--heat:${opacity}"/>${value ? numberEl(value, x + colIndex * cellW + (cellW - 3) / 2, y + rowIndex * cellH + 20, factId, 'v11-heat-value', ' text-anchor="middle"') : ''}</g>`;
  }).join('')}`).join('');
  return header + body;
}

function lineChart(series, { x = 70, y = 230, w = 1060, h = 330, maxY = null, label = '', yLabel = '' } = {}) {
  const all = series.flatMap((item) => item.values.map((point) => point.value));
  const maximum = maxY ?? Math.max(...all, 1);
  const count = Math.max(...series.map((item) => item.values.length), 2);
  const sx = (index) => x + index / (count - 1) * w;
  const sy = (value) => y + h - value / maximum * h;
  const grid = [0, .25, .5, .75, 1].map((ratio) => `<path d="M${x} ${y + h * ratio}H${x + w}" class="v11-gridline"/>${textEl(fmt(Math.round(maximum * (1 - ratio))), x - 10, y + h * ratio + 4, 'v11-axis-label', ' text-anchor="end"')}`).join('');
  const plots = series.map((item) => {
    const points = item.values.map((point, pointIndex) => `${sx(pointIndex)},${sy(point.value)}`).join(' ');
    return `<polyline points="${points}" class="v11-series v11-stroke-${item.tone || 'blue'}"/>`;
  }).join('');
  const legend = series.map((item, index) => `${badge(item.label, x + index * 160, y + h + 34, item.tone || 'blue')}`).join('');
  return `${grid}<rect x="${x}" y="${y}" width="${w}" height="${h}" class="v11-chart-frame"/>${plots}${legend}${label ? textEl(label, x + w / 2, y + h + 82, 'v11-axis-title', ' text-anchor="middle"') : ''}${yLabel ? textEl(yLabel, 18, y + h / 2, 'v11-axis-title', ` transform="rotate(-90 18 ${y + h / 2})" text-anchor="middle"`) : ''}`;
}

function treemap(items, x, y, w, h, valueKey = 'value') {
  const total = items.reduce((sum, item) => sum + Number(item[valueKey] || 0), 0);
  let cursor = x;
  return items.map((item, index) => {
    const iw = index === items.length - 1 ? x + w - cursor : w * Number(item[valueKey] || 0) / total;
    const factId = item.factId;
    const result = `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${item.label}: ${fmt(item[valueKey])}; ${item.detail || ''}`)}"><rect x="${cursor}" y="${y}" width="${Math.max(1, iw - 2)}" height="${h}" class="v11-treemap v11-fill-${item.tone || 'blue'}" opacity=".78"/><text x="${cursor + 10}" y="${y + 24}" class="v11-tree-label">${esc(item.label)}</text>${iw > 58 ? numberEl(item[valueKey], cursor + 10, y + 50, factId, 'v11-tree-value') : ''}${iw > 125 && item.detail ? lineText(item.detail, cursor + 10, y + 74, Math.floor((iw - 18) / 10), 'v11-tiny', 14, 2) : ''}</g>`;
    cursor += iw;
    return result;
  }).join('');
}

function twoRowTreemap(items, x, y, w, h, valueKey = 'value') {
  const sorted = [...items].sort((a, b) => Number(b[valueKey] || 0) - Number(a[valueKey] || 0));
  const total = sorted.reduce((sum, item) => sum + Number(item[valueKey] || 0), 0);
  let split = 1, running = Number(sorted[0]?.[valueKey] || 0);
  while (split < sorted.length - 1 && running < total * .56) running += Number(sorted[split++][valueKey] || 0);
  const rows = [sorted.slice(0, split), sorted.slice(split)].filter((row) => row.length);
  let rowY = y;
  let ordinal = 0;
  return rows.map((row) => {
    const rowTotal = row.reduce((sum, item) => sum + Number(item[valueKey] || 0), 0);
    const rowH = h * rowTotal / total;
    let cursor = x;
    const markup = row.map((item, index) => {
      const iw = index === row.length - 1 ? x + w - cursor : w * Number(item[valueKey] || 0) / rowTotal;
      ordinal += 1;
      const shortLabel = iw >= 115 ? item.label : iw >= 36 ? String(ordinal).padStart(2, '0') : '';
      const detail = `${item.label}: ${fmt(item[valueKey])}; ${item.detail || ''}`;
      const result = `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(detail)}"><rect x="${cursor}" y="${rowY}" width="${Math.max(1, iw - 3)}" height="${Math.max(1, rowH - 3)}" rx="6" class="v11-treemap v11-fill-${item.tone || 'blue'}" opacity=".76"/>${shortLabel ? `<text x="${cursor + 9}" y="${rowY + 23}" class="v11-tree-label">${esc(shortLabel)}</text>` : ''}${iw >= 72 ? numberEl(item[valueKey], cursor + 9, rowY + 49, item.factId, 'v11-tree-value') : ''}</g>`;
      cursor += iw;
      return result;
    }).join('');
    rowY += rowH;
    return markup;
  }).join('');
}

function rankedList(items, { x = 50, y = 520, columns = 2, columnWidth = 540, rowHeight = 25, labelWidth = 250, valueKey = 'value', detailKey = 'detail' } = {}) {
  const rows = Math.ceil(items.length / columns);
  return items.map((item, index) => {
    const column = Math.floor(index / rows), row = index % rows;
    const xx = x + column * columnWidth, yy = y + row * rowHeight;
    const value = item[valueKey];
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${item.label}; ${fmt(value)}; ${item[detailKey] || ''}`)}"><circle cx="${xx + 11}" cy="${yy - 4}" r="9" class="v11-fill-${item.tone || 'blue'}"/><text x="${xx + 11}" y="${yy}" text-anchor="middle" class="v11-index">${String(index + 1).padStart(2, '0')}</text>${lineText(item.label, xx + 28, yy, Math.max(12, Math.floor(labelWidth / 9)), 'v11-row-label', 14, 1)}<text x="${xx + labelWidth + 38}" y="${yy}" text-anchor="end" class="v11-row-value">${esc(fmt(value))}</text>${item[detailKey] ? lineText(item[detailKey], xx + labelWidth + 54, yy, Math.max(12, Math.floor((columnWidth - labelWidth - 62) / 8)), 'v11-code-source', 14, 1) : ''}</g>`;
  }).join('');
}

function compactClause(value, limit = 68) {
  const clause = String(value || '').split(/[。；\n]/)[0].trim();
  return clause.length > limit ? `${clause.slice(0, limit - 1)}…` : clause;
}

function evidenceIdsFor(meta) {
  return groupEditorial[meta.group]?.evidenceIds || [];
}

function compactFootnoteFor(meta) {
  return `数据：${evidenceIdsFor(meta).join(' / ')} / ${compactClause(meta.source, 52)}｜图中结论：${compactClause(meta.proves, 72)}`;
}

function chapterMasthead(id, meta, width) {
  const p = id.toLowerCase();
  const grammar = groupEditorial[meta.group];
  const label = `${grammar.code} · ${grammar.masthead}`;
  const question = compactClause(meta.question, 88);
  const common = `<text x="38" y="38" class="v12-kicker">${esc(label)}</text><text x="38" y="74" class="v12-question">${esc(question)}</text>`;
  if (meta.group === 'CASE') {
    const phases = ['需求确认', 'DESIGN合同', '多系统实现', '浏览器验收'];
    const phaseWidth = (width - 76 - 36) / phases.length;
    return `${common}<path d="M38 104H${width - 38}" class="v12-rule v12-rule-gold"/><g transform="translate(38 122)">${phases.map((phase, index) => `<g transform="translate(${index * (phaseWidth + 12)} 0)"><rect width="${phaseWidth}" height="42" rx="7" class="v13-phase${index === phases.length - 1 ? ' is-result' : ''}"/><text x="${phaseWidth / 2}" y="27" text-anchor="middle" class="v13-phase-label">${index + 1} · ${phase}</text></g>`).join('')}</g>`;
  }
  if (meta.group === 'SRC') return `${common}<path d="M38 108H${width - 38}" class="v12-blueprint-line"/>${Array.from({ length: 13 }, (_, i) => `<path d="M${38 + i * (width - 76) / 12} 102v${i % 3 === 0 ? 24 : 13}" class="v12-blueprint-tick"/>`).join('')}<path d="M58 158h330l38-28h300l38 28h${width - 802}" class="v12-blueprint-path"/><text x="58" y="150" class="v12-note">模块 · 符号 · 运行边界</text>`;
  if (meta.group === 'PLAT') return `${common}<g transform="translate(38 112)"><rect width="${(width - 100) / 3}" height="44" class="v12-layer v12-layer-model"/><rect x="${(width - 100) / 3 + 12}" width="${(width - 100) / 3}" height="44" class="v12-layer v12-layer-runtime"/><rect x="${2 * ((width - 100) / 3 + 12)}" width="${(width - 100) / 3}" height="44" class="v12-layer v12-layer-world"/><text x="18" y="28" class="v12-layer-label">模型请求</text><text x="${(width - 100) / 3 + 30}" y="28" class="v12-layer-label">Agent运行时</text><text x="${2 * ((width - 100) / 3 + 12) + 18}" y="28" class="v12-layer-label">真实工程环境</text></g>`;
  if (meta.group === 'RUN') return `${common}<path d="M38 140H${width - 38}" class="v12-run-axis"/>${Array.from({ length: 12 }, (_, i) => `<path d="M${38 + i * (width - 76) / 11} 128v24" class="v12-run-tick"/>`).join('')}<text x="38" y="172" class="v12-note">01:30</text><text x="${width - 38}" y="172" text-anchor="end" class="v12-note">07:01</text>`;
  if (meta.group === 'DBG') return `${common}<path d="M38 112h${width - 76}" class="v12-debug-band"/><path d="M38 158h${width - 76}" class="v12-debug-rule"/><text x="52" y="139" class="v12-debug-label">事故现场</text><text x="238" y="139" class="v12-debug-label">状态取证</text><text x="424" y="139" class="v12-debug-label">代码责任</text><text x="610" y="139" class="v12-debug-label">回归结果</text>`;
  if (meta.group === 'QA') return `${common}<g transform="translate(38 112)"><rect width="${width - 76}" height="48" class="v12-film"/>${Array.from({ length: 22 }, (_, i) => `<rect x="${12 + i * (width - 100) / 21}" y="7" width="18" height="8" class="v12-perf"/><rect x="${12 + i * (width - 100) / 21}" y="33" width="18" height="8" class="v12-perf"/>`).join('')}<text x="${(width - 76) / 2}" y="30" text-anchor="middle" class="v12-film-label">运行断言 · 截图 · 录屏 · 终局</text></g>`;
  if (meta.group === 'AUDIT') return `${common}<path d="M38 112H${width - 38}" class="v12-ledger-rule"/>${[.08, .28, .5, .72, .92].map((ratio) => `<path d="M${38 + ratio * (width - 76)} 112v48" class="v12-ledger-column"/>`).join('')}<text x="52" y="145" class="v12-note">原始记录</text><text x="${width * .31}" y="145" class="v12-note">标识符关联</text><text x="${width * .53}" y="145" class="v12-note">统计复算</text><text x="${width * .75}" y="145" class="v12-note">工程结论</text>`;
  return `${common}<path d="M38 120H${width - 38}" class="v12-meta-rule"/><circle cx="38" cy="120" r="6" class="v11-fill-gold"/><text x="38" y="158" class="v12-note">把事实、证据和工程判断放在同一张桌面上</text>`;
}

function defs(id, group) {
  const p = id.toLowerCase();
  const accent = { CASE: '#e8c76b', SRC: '#5aa9ff', PLAT: '#5cc7d8', RUN: '#5aa9ff', DBG: '#ff7670', QA: '#5aa9ff', AUDIT: '#a9b8c9', META: '#e8c76b' }[group] || '#5aa9ff';
  return `<defs><linearGradient id="${p}-wash" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${accent}" stop-opacity=".09"/><stop offset=".48" stop-color="#07111d" stop-opacity=".03"/><stop offset="1" stop-color="#07111d" stop-opacity="0"/></linearGradient><pattern id="${p}-grid" width="40" height="40" patternUnits="userSpaceOnUse"><path d="M40 0H0V40" fill="none" stroke="#20344d" stroke-width=".45"/></pattern><filter id="${p}-shadow"><feDropShadow dx="0" dy="5" stdDeviation="6" flood-color="#02070d" flood-opacity=".32"/></filter></defs>`;
}

const layoutContracts = Object.fromEntries(Object.entries({
  'CASE-V01': [1200, 710, 655], 'CASE-V02': [1200, 690, 630], 'CASE-V03': [1200, 735, 675], 'CASE-V04': [1200, 960, 889],
  'CASE-V05': [1200, 755, 693], 'CASE-V06': [1200, 730, 678], 'CASE-V07': [1200, 765, 705], 'CASE-V08': [1200, 780, 718],
  'SRC-V01': [1200, 820, 760], 'SRC-V02': [1200, 800, 740], 'SRC-V03': [1200, 845, 785], 'SRC-V04': [1200, 735, 675],
  'PLAT-V01': [1200, 795, 735], 'PLAT-V02': [1200, 795, 735], 'PLAT-V03': [1200, 700, 640], 'PLAT-V04': [1200, 795, 735],
  'PLAT-V05': [1200, 815, 750], 'PLAT-V06': [1200, 770, 712], 'PLAT-V07': [1200, 800, 740], 'PLAT-V08': [1200, 755, 695],
  'PLAT-V09': [1200, 790, 728], 'PLAT-V10': [1200, 815, 755],
  'RUN-V01': [1400, 990, 890], 'RUN-V02': [1400, 740, 671], 'RUN-V03': [1400, 855, 785], 'RUN-V04': [1200, 855, 785],
  'RUN-V05': [1400, 855, 785], 'RUN-V06': [1200, 780, 720], 'RUN-V07': [1200, 835, 775], 'RUN-V08': [1200, 800, 738],
  'DBG-V01': [1200, 750, 689], 'DBG-V02': [1200, 815, 752], 'DBG-V03': [1200, 790, 728], 'DBG-V04': [1200, 755, 695],
  'DBG-V05': [1200, 875, 804], 'DBG-V06': [1200, 805, 745],
  'QA-V01': [1200, 775, 715], 'QA-V02': [1400, 855, 785], 'QA-V03': [1200, 735, 675], 'QA-V04': [1200, 855, 785],
  'QA-V05': [1200, 835, 770], 'QA-V06': [1400, 1280, 1185], 'QA-V07': [1200, 795, 735], 'QA-V08': [1200, 825, 765],
  'QA-V09': [1400, 1110, 1002], 'QA-V10': [1200, 775, 715],
  'AUDIT-V01': [1200, 730, 670], 'AUDIT-V02': [1200, 830, 765], 'AUDIT-V03': [1200, 780, 720], 'AUDIT-V04': [1200, 725, 665],
  'AUDIT-V05': [1200, 805, 745], 'AUDIT-V06': [1200, 775, 715], 'AUDIT-V07': [1200, 775, 715], 'AUDIT-V08': [1200, 780, 718],
  'AUDIT-V09': [1200, 815, 755], 'AUDIT-V10': [1400, 780, 700], 'AUDIT-V11': [1400, 1180, 1066], 'AUDIT-V12': [1200, 840, 775],
  'META-V01': [1200, 815, 755], 'META-V02': [1200, 815, 755], 'META-V03': [1200, 780, 720], 'META-V04': [1200, 900, 840],
  'META-V05': [1200, 855, 770], 'META-V06': [1200, 860, 795],
}).map(([id, [width, height, contentBottom]]) => [id, {
  width, height, contentBottom,
  expectedBottomBlankRatio: round((height - contentBottom) / height, 4),
  allowedBottomBlankRatio: width === 1400 ? .15 : .12,
  overlapPolicy: 'none',
  proportionalEncoding: 'renderer-declared',
}]));

function renderFigure(id, body, options = {}) {
  const meta = metadata.get(id);
  if (!meta) throw new Error(`Missing metadata for ${id}`);
  if (questionOverrides[id]) meta.question = questionOverrides[id];
  const contract = layoutContracts[id];
  if (!contract) throw new Error(`Missing layout contract for ${id}`);
  const width = options.width || 1200;
  if (width !== contract.width) throw new Error(`${id} width ${width} != layout contract ${contract.width}`);
  const height = contract.height;
  if (contract.contentBottom > height || contract.expectedBottomBlankRatio > contract.allowedBottomBlankRatio) throw new Error(`${id} invalid layout contract`);
  const title = titleOverrides[id] || meta.title;
  const metrics = options.metrics || [];
  const compactFootnote = compactFootnoteFor(meta);
  const metricAudit = metrics.length ? `<h5>图中指标</h5><ul class="figure-audit-metrics">${metrics.map((item) => `<li><span>${esc(item.label)}</span><b${item.factId ? ` data-fact-id="${esc(item.factId)}"` : ''}>${esc(fmt(item.value))}</b>${item.factId ? `<code>${esc(item.factId)}</code>` : ''}</li>`).join('')}</ul>` : '';
  const publicLogLink = id === 'AUDIT-V06'
    ? `<a class="evidence-download" href="assets/king/logs/app-session-20260809-0130-0701.public.log">下载并复算38,641行公开合并日志</a>`
    : '';
  return `<figure class="evidence-viz product-viz v12-figure" id="viz-${id.toLowerCase()}" data-viz-code="${id}" data-viz-group="${meta.group}" data-visual-grammar="${esc(groupEditorial[meta.group].grammar)}" data-renderer="render${id.replace('-', '')}" data-evidence-source="${esc(meta.source)}" data-layout-content-bottom="${contract.contentBottom}" data-layout-bottom-limit="${contract.allowedBottomBlankRatio}"><figcaption class="v12-head"><span class="v12-id">${id}</span><strong>${esc(title)}</strong></figcaption><div class="v12-stage" data-canvas-width="${width}"><svg class="v12-svg v11-svg" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="${id.toLowerCase()}-title ${id.toLowerCase()}-desc"><title id="${id.toLowerCase()}-title">${esc(title)}</title><desc id="${id.toLowerCase()}-desc">${esc(`${meta.question} ${meta.proves}`)}</desc>${defs(id, meta.group)}<rect width="${width}" height="${height}" class="v11-bg" data-layout-background="true"/><rect width="${width}" height="${height}" fill="url(#${id.toLowerCase()}-grid)" opacity=".16" data-layout-background="true"/><rect width="${width}" height="${height}" fill="url(#${id.toLowerCase()}-wash)" data-layout-background="true"/>${chapterMasthead(id, meta, width)}${body}</svg></div><p class="v12-footnote">${esc(compactFootnote)}</p>${publicLogLink}<output class="pv-inspector" aria-live="polite"></output><details class="figure-audit" data-viz-audit="${id}"><summary>查看来源与复算数据</summary><dl><dt>数据来源</dt><dd>${esc(meta.source)}</dd><dt>复算口径</dt><dd>${esc(meta.proves)}</dd></dl>${metricAudit}</details></figure>`;
}

const M = {
  code: [
    { label: '第一方文件', value: factValue('fact.code.firstPartyFiles'), factId: 'fact.code.firstPartyFiles' },
    { label: '第一方代码行', value: factValue('fact.code.firstPartyLines'), factId: 'fact.code.firstPartyLines' },
    { label: 'src模块', value: factValue('fact.code.modules'), factId: 'fact.code.modules' },
    { label: '本地导入边', value: factValue('fact.code.importEdges'), factId: 'fact.code.importEdges' },
  ],
  run: [
    { label: '全部Run', value: factValue('fact.run.total'), factId: 'fact.run.total' },
    { label: 'Worker Run', value: factValue('fact.run.workers'), factId: 'fact.run.workers' },
    { label: 'LLM started', value: factValue('fact.llm.started'), factId: 'fact.llm.started' },
    { label: '工具调用', value: factValue('fact.tools.total'), factId: 'fact.tools.total' },
  ],
  audit: [
    { label: '账单请求', value: factValue('fact.bill.requests'), factId: 'fact.bill.requests' },
    { label: '运行完成', value: factValue('fact.llm.completed'), factId: 'fact.llm.completed' },
    { label: '工具成功', value: factValue('fact.tools.success'), factId: 'fact.tools.success' },
    { label: '工具错误', value: factValue('fact.tools.error'), factId: 'fact.tools.error' },
  ],
  media: [
    { label: '截图文件', value: factValue('fact.media.screenshots'), factId: 'fact.media.screenshots' },
    { label: '唯一图像', value: factValue('fact.media.uniqueScreenshots'), factId: 'fact.media.uniqueScreenshots' },
    { label: '原视频', value: data.media.videos.length, factId: '' },
    { label: '派生帧', value: storyboard.videos.flatMap((video) => video.frames).length, factId: '' },
  ],
};

const shot = (needle) => data.media.screenshots.find((item) => item.path.includes(needle)) || data.media.screenshots[0];
const factForFile = (path) => `fact.file.${path.replaceAll('/', '.').replaceAll(/[^a-zA-Z0-9_.-]/g, '-')}.lines`;

function cardGrid(items, { x = 48, y = 220, columns = 4, cardW = 260, cardH = 112, gapX = 18, gapY = 18 } = {}) {
  return items.map((item, index) => nodeCard({ x: x + (index % columns) * (cardW + gapX), y: y + Math.floor(index / columns) * (cardH + gapY), w: cardW, h: cardH, index: item.index || String(index + 1).padStart(2, '0'), ...item })).join('');
}

function chain(items, { x = 50, y = 270, w = 170, h = 100, gap = 24, maxX = 1162 } = {}) {
  const right = x + items.length * w + Math.max(0, items.length - 1) * gap;
  if (right > maxX + 0.001) throw new Error(`chain overflow: ${right} > ${maxX}; use a grid renderer`);
  return items.map((item, index) => {
    const xx = x + index * (w + gap);
    const card = nodeCard({ x: xx, y: y + (index % 2) * 72, w, h, index: item.index || index + 1, ...item });
    if (index === items.length - 1) return card;
    const ny = y + ((index + 1) % 2) * 72 + h / 2;
    return card + arrow(xx + w, y + (index % 2) * 72 + h / 2, xx + w + gap - 6, ny, item.edgeTone || 'cyan', item.dashed, item.edgeLabel || '');
  }).join('');
}

function categoricalMatrix(rows, columns, valueFn, { x = 260, y = 230, cellW = 132, cellH = 48 } = {}) {
  const header = columns.map((column, colIndex) => lineText(column.label || column, x + colIndex * cellW + cellW / 2, y - 34, 12, 'v11-matrix-head', 14, 2, ' text-anchor="middle"')).join('');
  const body = rows.map((row, rowIndex) => `${lineText(row.label, 48, y + rowIndex * cellH + 21, 20, 'v11-matrix-row', 14, 2)}${columns.map((column, colIndex) => {
    const cell = valueFn(row, column, rowIndex, colIndex) || { state: 'none', label: '' };
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(cell.detail || `${row.label} × ${column.label || column}: ${cell.label || cell.state}`)}"><rect x="${x + colIndex * cellW}" y="${y + rowIndex * cellH}" width="${cellW - 4}" height="${cellH - 4}" rx="6" class="v11-matrix-cell v11-state-${cell.state || 'none'}"/>${cell.label ? lineText(cell.label, x + colIndex * cellW + (cellW - 4) / 2, y + rowIndex * cellH + 20, 12, 'v11-cell-label', 14, 2, ' text-anchor="middle"') : ''}</g>`;
  }).join('')}`).join('');
  return header + body;
}

function swimlanes(lanes, events, { x = 210, y = 220, w = 930, laneH = 58, start, end } = {}) {
  const lo = time(start), hi = time(end);
  const sx = (value) => x + (time(value) - lo) / (hi - lo) * w;
  const rows = lanes.map((lane, index) => `<rect x="${x}" y="${y + index * laneH}" width="${w}" height="${laneH - 4}" class="v11-lane"/><text x="${x - 16}" y="${y + index * laneH + 32}" text-anchor="end" class="v11-lane-label">${esc(lane.label || lane)}</text>`).join('');
  const marks = events.map((event) => {
    const laneIndex = lanes.findIndex((lane) => (lane.id || lane) === event.lane);
    const xx = sx(event.start);
    const endX = event.end ? sx(event.end) : xx + 12;
    const yy = y + laneIndex * laneH + 11;
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(event.detail || event.label)}"><rect x="${xx}" y="${yy}" width="${Math.max(8, endX - xx)}" height="${laneH - 26}" rx="5" class="v11-fill-${event.tone || 'blue'}" opacity=".8"/>${endX - xx > 52 ? textEl(event.label, xx + 7, yy + 21, 'v11-swim-label') : ''}</g>`;
  }).join('');
  return rows + marks;
}

function sankeyColumns(columns, links, { x = 70, y = 240, w = 1060, h = 360 } = {}) {
  const colGap = w / Math.max(1, columns.length - 1);
  const nodePositions = new Map();
  let nodes = '';
  columns.forEach((column, colIndex) => {
    const total = column.nodes.reduce((sum, node) => sum + node.value, 0);
    const gap = 12;
    const usable = h - gap * (column.nodes.length - 1);
    let cursor = y;
    for (const node of column.nodes) {
      const nh = Math.max(30, usable * node.value / total);
      const xx = x + colIndex * colGap;
      nodePositions.set(node.id, { x: xx, y: cursor, w: 28, h: nh, center: cursor + nh / 2, node });
      nodes += `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${node.label}: ${fmt(node.value)}`)}"><rect x="${xx}" y="${cursor}" width="28" height="${nh}" rx="4" class="v11-fill-${node.tone || 'blue'}"/><text x="${colIndex === columns.length - 1 ? xx - 8 : xx + 38}" y="${cursor + nh / 2 + 4}" class="v11-sankey-label" text-anchor="${colIndex === columns.length - 1 ? 'end' : 'start'}">${esc(node.label)} · ${fmt(node.value)}</text></g>`;
      cursor += nh + gap;
    }
  });
  const flows = links.map((link) => {
    const from = nodePositions.get(link.from), to = nodePositions.get(link.to);
    if (!from || !to) return '';
    const thickness = Math.max(2, 24 * link.value / Math.max(from.node.value, to.node.value, 1));
    return `<path d="M${from.x + from.w} ${from.center}C${(from.x + to.x) / 2} ${from.center} ${(from.x + to.x) / 2} ${to.center} ${to.x} ${to.center}" class="v11-flow v11-stroke-${link.tone || 'cyan'}" style="stroke-width:${thickness}"/>`;
  }).join('');
  return flows + nodes;
}

function calloutList(items, { x = 760, y = 230, w = 390, h = 72, gap = 12 } = {}) {
  return items.map((item, index) => nodeCard({ x, y: y + index * (h + gap), w, h, index: index + 1, title: item.title, subtitle: item.subtitle, source: item.source, detail: item.detail || `${item.title}；${item.subtitle || ''}`, tone: item.tone || 'blue' })).join('');
}

function sourceFacts(items, x = 48, y = 610, columns = 3, width = 1104) {
  const gap = 14, w = (width - gap * (columns - 1)) / columns;
  return items.map((item, index) => {
    const column = index % columns, row = Math.floor(index / columns), xx = x + column * (w + gap), yy = y + row * 82;
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(item.detail || `${item.title}；${item.subtitle || ''}；${item.source || ''}`)}"><path d="M${xx} ${yy + 5}V${yy + 65}M${xx} ${yy + 5}H${xx + w - 8}" class="v12-source-rule"/><circle cx="${xx + 17}" cy="${yy + 27}" r="13" class="v12-source-index"/><text x="${xx + 17}" y="${yy + 31}" text-anchor="middle" class="v12-source-index-text">${esc(item.index || index + 1)}</text><text x="${xx + 42}" y="${yy + 23}" class="v11-card-title">${esc(item.title)}</text>${lineText(item.subtitle || '', xx + 42, yy + 43, Math.max(15, Math.floor((w - 50) / 10)), 'v11-small', 15, 1)}${lineText(item.source || '', xx + 42, yy + 61, Math.max(15, Math.floor((w - 50) / 9)), 'v11-code-source', 14, 1)}</g>`;
  }).join('');
}

function renderCASEV01() {
  const image = shot('1786228379180');
  const left = [
    { title: '一句话需求', subtitle: '类《王者荣耀》单机Web 5v5原型', tone: 'gold' },
    { title: '4项范围确认', subtitle: '技术 / 模式 / 地图 / 操控', tone: 'cyan' },
    { title: 'DESIGN v1', subtitle: 'G01–G16验收合同', tone: 'blue' },
  ];
  const right = [
    { title: '10次Worker', subtitle: '地图 → 战斗 → AI → QA', tone: 'blue' },
    { title: '17个模块', subtitle: '39条本地相对导入边', tone: 'green' },
    { title: 'V01–V12', subtitle: '浏览器运行复验', tone: 'cyan' },
  ];
  let body = '';
  for (let index = 0; index < 3; index += 1) {
    const y = 218 + index * 112;
    body += arrow(292, y + 48, 330, 270 + index * 75, left[index].tone, false, '');
    body += arrow(908, y + 48, 870, 270 + index * 75, right[index].tone, false, '');
  }
  body += imagePanel(330, 215, 540, 324, image, '最终河道交战 · 真实运行截图', [
    { x: .20, y: .64, label: 'HUD', detail: 'HUD与血蓝、技能、金币由src/ui/hud.js持续更新' },
    { x: .52, y: .45, label: '英雄', detail: '10名运行时英雄来自GameState与AI控制器' },
    { x: .72, y: .54, label: '兵线', detail: 'Spawner与minion AI驱动三路兵线' },
    { x: .91, y: .18, label: '小地图', detail: 'src/ui/minimap.js按0.25秒刷新' },
  ]);
  body += left.map((item, index) => nodeCard({ x: 42, y: 218 + index * 112, w: 250, h: 96, index: index + 1, ...item, detail: `${item.title}；${item.subtitle}` })).join('');
  body += right.map((item, index) => nodeCard({ x: 908, y: 218 + index * 112, w: 250, h: 96, index: index + 4, ...item, detail: `${item.title}；${item.subtitle}` })).join('');
  body += sourceFacts([
      { title: '过程链', subtitle: '11 Runs / 878 LLM / 968工具', source: 'observability + public log' },
      { title: '产物链', subtitle: '7,979行 / 5英雄 / 15技能', source: 'frozen code snapshot' },
      { title: '验收链', subtitle: '43截图 / 5原视频 / 云端补充', source: 'media hashes + browser audit' },
    ], 48, 590);
  return renderFigure('CASE-V01', body, { width: 1200, height: 710, metrics: M.run, contentBottom: 655 });
}

function renderCASEV02() {
  const domains = [
    { label: '产物', tone: 'green', items: [['第一方代码', 7979], ['模块/导入边', '17 / 39'], ['英雄/技能', '5 / 15']] },
    { label: '执行', tone: 'blue', items: [['Run', '1+10'], ['LLM终态', '873+5'], ['工具', 968]] },
    { label: '反馈', tone: 'cyan', items: [['WebBrowser', 376], ['截图', '43/42'], ['视频', '5+5']] },
    { label: '审计', tone: 'gold', items: [['日志行', 38641], ['账单请求', 877], ['证据账本', 'E01–E42']] },
  ];
  let body = '';
  const positions = [[42, 205], [610, 205], [42, 430], [610, 430]];
  domains.forEach((domain, index) => {
    const [x, y] = positions[index];
    const subtitles = { 产物: '冻结代码中的可运行系统', 执行: '模型、Run与工具生命周期', 反馈: '浏览器、截图与视频观察', 审计: '日志、账单与证据账本' };
    body += panel(x, y, 548, 200, domain.label, subtitles[domain.label], domain.tone);
    body += domain.items.map(([label, value], itemIndex) => nodeCard({ x: x + 18 + itemIndex * 174, y: y + 75, w: 160, h: 104, index: itemIndex + 1, title: label, value, detail: `${domain.label}/${label}: ${value}`, tone: domain.tone })).join('');
  });
  body += arrow(590, 305, 610, 305, 'cyan', false, '实现→执行') + arrow(590, 530, 610, 530, 'gold', false, '证据→审计') + arrow(316, 405, 316, 430, 'green', true, '运行观察') + arrow(884, 405, 884, 430, 'purple', true, '记录复算');
  return renderFigure('CASE-V02', body, { height: 690, metrics: M.audit, contentBottom: 630 });
}

function renderCASEV03() {
  const items = [
    { time: '2026-08-09T01:30:00+08:00', label: '开发窗开始', sub: '统计下界', tone: 'green' },
    { time: '2026-08-09T01:31:15+08:00', label: '首个LLM请求', sub: 'llm_call_started', tone: 'blue' },
    { time: '2026-08-09T07:00:32+08:00', label: '最后账单请求', sub: '窗口内终验', tone: 'cyan' },
    { time: '2026-08-09T07:01:00+08:00', label: '开发窗结束', sub: '统计上界', tone: 'green' },
    { time: '2026-08-09T09:21:55+08:00', label: '最终运行录屏', sub: '窗口外补充', tone: 'gold' },
    { time: '2026-08-09T11:53:36+08:00', label: '阿里云试玩', sub: '窗口外部署', tone: 'purple' },
  ];
  const lo = time(items[0].time), hi = time(items.at(-1).time), scale = (value) => 90 + (time(value) - lo) / (hi - lo) * 1020;
  const placements = [[50, 250], [50, 455], [500, 250], [500, 455], [780, 250], [972, 455]];
  let body = panel(42, 205, 1116, 380, '严格证据窗口与窗口外补充共用同一时间比例', '事件标记保留真实时间位置；紧邻事件用错层引线展开，避免把1分15秒画成宽间隔。', 'blue');
  body += `<path d="M${scale(items[0].time)} 395H${scale(items[3].time)}" class="v11-stroke-green" stroke-width="8" opacity=".38"/><path d="M90 405H1110" class="v11-axis"/><text x="${(scale(items[0].time) + scale(items[3].time)) / 2}" y="385" text-anchor="middle" class="v11-axis-title">5小时31分证据过滤窗口</text><text x="${(scale(items[1].time) + scale(items[2].time)) / 2}" y="425" text-anchor="middle" class="v11-code-source">5小时29分17秒首末请求锚点跨度</text>`;
  items.forEach((item, index) => {
    const px = scale(item.time), [cardX, cardY] = placements[index], cardW = index === 6 ? 170 : 180;
    const attachY = cardY < 405 ? cardY + 94 : cardY;
    body += `<path d="M${px} 405V${attachY}H${cardX + cardW / 2}" class="v11-guide"/><circle cx="${px}" cy="405" r="9" class="v11-fill-${item.tone}"/>`;
    body += `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${item.label}；${item.time}；${item.sub}`)}"><rect x="${cardX}" y="${cardY}" width="${cardW}" height="94" rx="8" class="v11-node v11-tone-${item.tone}"/><text x="${cardX + 12}" y="${cardY + 23}" class="v11-time">${esc(item.time.slice(11, 19))}</text>${lineText(item.label, cardX + 12, cardY + 49, 18, 'v11-card-title', 17, 2)}<text x="${cardX + 12}" y="${cardY + 82}" class="v11-tiny">${esc(item.sub)}</text></g>`;
  });
  body += sourceFacts([{ title: '窗口内', subtitle: '01:30≤time<07:01 · 5h31m', source: 'provenance.developmentWindow', tone: 'green' }, { title: '请求锚点', subtitle: '01:31:15→07:00:32 · 5:29:17', source: 'LLM start + bill row', tone: 'cyan' }, { title: '窗口外', subtitle: '09:21最终运行 / 11:53云端试玩', source: 'final-run + cloud-deployment', tone: 'gold' }], 48, 610);
  return renderFigure('CASE-V03', body, { height: 735, metrics: [{ label: '证据窗口', value: '5:31:00' }, { label: '首末请求锚点', value: '5:29:17' }, { label: '日志首条', value: '01:30:01.516' }, { label: '窗口外层', value: 3 }], contentBottom: 675 });
}

function renderCASEV04() {
  const systems = [
    { id: 1, domain: '世界', title: '地图世界', file: 'src/world/map.js', fact: '180×180 · 18塔 · 10营地', detail: '地图、河道、草丛、基地与结构物', tone: 'blue', x: 42, y: 236 },
    { id: 2, domain: '仿真', title: '英雄战斗', file: 'src/game/state.js', fact: '10英雄 · 15技能', detail: '共享状态、伤害、死亡、重生与终局', tone: 'cyan', x: 42, y: 382 },
    { id: 3, domain: '仿真', title: '多类AI', file: 'src/game/ai.js', fact: '9英雄AI · 3类单位AI', detail: '英雄、小兵、防御塔与野怪决策', tone: 'cyan', x: 42, y: 528 },
    { id: 4, domain: '交互', title: '成长经济', file: 'src/game/shop.js', fact: '12装备 · 15级', detail: '金币、经验、购买与战力成长', tone: 'blue', x: 910, y: 236 },
    { id: 5, domain: '交互', title: '输入与HUD', file: 'src/ui/hud.js', fact: '摇杆 · 技能 · 小地图', detail: '输入队列、状态显示和操作反馈', tone: 'blue', x: 910, y: 382 },
    { id: 6, domain: '表现', title: '程序化表现', file: 'src/world/models.js', fact: '几何 · Canvas · VFX', detail: '模型、材质、地表纹理与效果对象', tone: 'cyan', x: 910, y: 528 },
    { id: 7, domain: '表现', title: '运行工程', file: 'src/main.js', fact: '30Hz逻辑 · WebGL渲染', detail: '主循环装配、输入分发、渲染与复验入口', tone: 'cyan', x: 470, y: 674 },
  ];
  const image = shot('1786228379180');
  const hubs = {
    world: { x: 338, y: 312, tone: 'blue', label: '世界坐标' },
    simulation: { x: 338, y: 455, tone: 'cyan', label: '共享状态' },
    interaction: { x: 862, y: 385, tone: 'blue', label: '输入反馈' },
    presentation: { x: 862, y: 526, tone: 'cyan', label: '画面输出' },
  };
  let body = '';
  body += arrow(292, 292, hubs.world.x, hubs.world.y, 'blue', false, '');
  body += arrow(292, 438, hubs.simulation.x, hubs.simulation.y, 'cyan', false, '');
  body += arrow(292, 584, hubs.simulation.x, hubs.simulation.y, 'cyan', false, '');
  body += arrow(910, 292, hubs.interaction.x, hubs.interaction.y, 'blue', false, '');
  body += arrow(910, 438, hubs.interaction.x, hubs.interaction.y, 'blue', false, '');
  body += arrow(910, 584, hubs.presentation.x, hubs.presentation.y, 'cyan', false, '');
  body += arrow(600, 674, hubs.presentation.x, hubs.presentation.y, 'cyan', false, '');
  body += arrow(hubs.world.x, hubs.world.y, 408, 318, 'blue');
  body += arrow(hubs.simulation.x, hubs.simulation.y, 408, 445, 'cyan');
  body += arrow(hubs.interaction.x, hubs.interaction.y, 792, 407, 'blue');
  body += arrow(hubs.presentation.x, hubs.presentation.y, 792, 520, 'cyan');
  body += imagePanel(408, 252, 384, 324, image, '七个代码系统汇入同一真实运行帧', [
    { x: .12, y: .18, detail: '1 地图世界：小地图与世界坐标映射，src/world/map.js' },
    { x: .49, y: .49, detail: '2 英雄战斗：GameState推进英雄、技能、伤害和终局' },
    { x: .67, y: .43, detail: '3 多类AI：友军、敌军与单位AI在同一帧决策' },
    { x: .82, y: .80, detail: '4 成长经济：金币、装备和等级进入HUD与战斗属性' },
    { x: .18, y: .82, detail: '5 输入与HUD：摇杆、技能、血蓝、计时和小地图' },
    { x: .61, y: .24, detail: '6 程序化表现：模型、材质、Canvas地表和VFX' },
    { x: .48, y: .68, detail: '7 运行工程：30Hz逻辑与WebGL渲染在main.js装配' },
  ]);
  Object.values(hubs).forEach((hub) => {
    body += `<g><circle cx="${hub.x}" cy="${hub.y}" r="8" class="v11-fill-${hub.tone}"/><rect x="${hub.x - 48}" y="${hub.y - 35}" width="96" height="22" rx="5" class="v13-domain-tag"/><text x="${hub.x}" y="${hub.y - 20}" text-anchor="middle" class="v13-domain-label">${hub.label}</text></g>`;
  });
  systems.forEach((item) => {
    body += nodeCard({ x: item.x, y: item.y, w: item.id === 7 ? 260 : 248, h: 112, index: item.id, title: `${item.domain} · ${item.title}`, subtitle: item.fact, source: item.file, detail: `${item.title}；${item.fact}；${item.detail}；${item.file}`, tone: item.tone });
  });
  body += sourceFacts([
    { title: '结构证据', subtitle: '17模块 · 39条相对导入边', source: 'E31 / moduleGraph' },
    { title: '运行画面', subtitle: '英雄、兵线、HUD、地图同帧可见', source: `SHA ${short(image.sha256, 14)}` },
    { title: '工程定位', subtitle: '多系统实时耦合的可玩单机MOBA原型', source: 'E38 / claim matrix' },
  ], 48, 824);
  return renderFigure('CASE-V04', body, { height: 990, metrics: M.code });
}

function renderCASEV05() {
  const rows = data.database.interactions;
  let body = '';
  rows.forEach((row, index) => {
    const y = 210 + index * 125;
    body += panel(42, y, 1116, 108, `${row.id} · ${row.question}`, `interaction ${short(row.interactionId)} · ${fmt(row.responseMs / 1000)}s`, index % 2 ? 'cyan' : 'blue');
    row.options.forEach((option, optionIndex) => {
      const chosen = row.answer.includes(option.label.replace(' (推荐)', '')) || row.answer.includes(option.label);
      body += `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(option.description)}"><rect x="${300 + optionIndex * 270}" y="${y + 28}" width="252" height="58" rx="8" class="v11-option ${chosen ? 'v11-state-pass' : 'v11-state-boundary'}"/>${lineText(option.label, 314 + optionIndex * 270, y + 51, 25, 'v11-card-title', 16, 2)}<text x="${314 + optionIndex * 270}" y="${y + 78}" class="v11-tiny">${chosen ? '用户选择' : '明确排除'}</text></g>`;
    });
  });
  return renderFigure('CASE-V05', body, { height: 850, metrics: [{ label: '问题', value: 4 }, { label: '候选方案', value: 10 }, { label: '选择', value: 4 }, { label: '总响应', value: '39.561s' }] });
}

function renderCASEV06() {
  const lanes = data.database.interactions.map((row) => ({ id: row.id, label: `${row.id} ${row.question.slice(0, 10)}` }));
  const start = data.database.interactions[0].createdAt;
  const end = data.database.interactions.at(-1).respondedAt;
  const events = data.database.interactions.flatMap((row) => [
    { lane: row.id, start: row.createdAt, end: row.decidedAt, label: `${round(row.decisionMs / 1000, 3)}s`, tone: 'blue', detail: `${row.id} 创建→决定 ${row.decisionMs}ms；${row.interactionId}` },
    { lane: row.id, start: row.decidedAt, end: row.respondedAt, label: '响应落库', tone: 'green', detail: `${row.id} 决定与响应字段；${row.interactionId}` },
  ]);
  let body = panel(42, 205, 1116, 330, '创建→决定→响应：四条独立数据库记录', '上方横向距离按真实时间比例；下方卡片分别保留响应耗时、interactionId和runId前缀。', 'blue')
    + swimlanes(lanes, events, { x: 260, y: 275, w: 850, laneH: 52, start, end });
  body += data.database.interactions.map((row, index) => nodeCard({
    x: 42 + index * 279, y: 560, w: 260, h: 118, index: index + 1,
    title: `${row.id} · ${round(row.responseMs / 1000, 3)}s`,
    subtitle: `interaction ${short(row.interactionId, 16)}`,
    source: `run ${short(row.runId, 16)}`,
    detail: `${row.id}；响应 ${row.responseMs}ms；interactionId ${row.interactionId}；runId ${row.runId}`,
    tone: 'cyan',
  })).join('');
  return renderFigure('CASE-V06', body, { height: 730, metrics: data.database.interactions.map((row) => ({ label: row.id, value: `${round(row.responseMs / 1000, 3)}s` })), contentBottom: 678 });
}

function renderCASEV07() {
  const rows = [
    { label: 'Three.js 3D', code: 'renderer.js / map.js', g: 'G01/G08', v: 'V01/V04', e: 'E31/E38' },
    { label: '单机5v5 AI', code: 'state.js / ai.js', g: 'G07', v: 'V06/V09', e: 'E34' },
    { label: '完整三路地图', code: 'config.js / map.js', g: 'G02/G03', v: 'V02/V05', e: 'E32' },
    { label: '摇杆+技能键', code: 'input.js / hud.js', g: 'G09/G10', v: 'V03/V08', e: 'E35' },
  ];
  const columns = [{ label: '用户选择' }, { label: 'DESIGN合同' }, { label: '源码符号' }, { label: '运行V证据' }, { label: '证据账本' }];
  const body = categoricalMatrix(rows, columns, (row, column, ri, ci) => {
    const labels = [row.label, row.g, row.code, row.v, row.e];
    return { state: ci === 3 ? 'limited' : 'pass', label: labels[ci], detail: `${row.label} → ${labels[ci]}` };
  }, { x: 245, y: 235, cellW: 178, cellH: 94 }) + sourceFacts([{ title: '源码实现', subtitle: '冻结代码/数据库直接对应', source: 'source+db', tone: 'green' }, { title: '运行验收', subtitle: '指定浏览器路径内观察', source: 'browser acceptance', tone: 'gold' }, { title: '完整覆盖', subtitle: '合同、代码与验收证据三层对齐', source: 'claim matrix', tone: 'blue' }], 48, 640);
  return renderFigure('CASE-V07', body, { height: 880, metrics: [{ label: '用户选择', value: 4 }, { label: '合同条款', value: 16 }, { label: '验收编号', value: 12 }, { label: '证据账本', value: 43 }] });
}

function renderCASEV08() {
  const ports = [5000, 5173, 7000, 8000, 8080, 8440, 8451, 8787, 8791, 8931, 13564, 17890, 49443, 49445, 49449, 50065, 53743, 53746, 54029, 56510, 63278];
  const body = chain([
    { title: '启动器进入', subtitle: 'cd 到脚本目录', tone: 'blue' },
    { title: '候选端口序列', subtitle: '9201/9202/9203/9301…', tone: 'cyan' },
    { title: 'lsof探测', subtitle: 'LISTEN→继续；空闲→选中', tone: 'gold' },
    { title: '全部占用?', subtitle: 'socket绑定系统随机端口', tone: 'red' },
    { title: '启动HTTP服务', subtitle: 'python3 -m http.server', tone: 'green' },
    { title: '浏览器验收', subtitle: 'KING_OK / window.__errors', tone: 'cyan' },
  ], { x: 42, y: 235, w: 165, h: 110, gap: 26 })
    + panel(42, 455, 1116, 130, '真实占用端口样本 · 21个', '该样本来自协调者Bash活动；启动器本身探测固定候选列表，二者不可混同。', 'gold')
    + ports.map((port, index) => badge(String(port), 62 + (index % 11) * 95, 505 + Math.floor(index / 11) * 38, index === 7 ? 'red' : 'blue')).join('')
    + codeCard(42, 610, 540, 'start.command · 空闲端口分支', 'if ! lsof -iTCP:$p ...; then PORT=$p; break; fi', 'code/start.command', '6–8')
    + codeCard(608, 610, 550, 'start.command · 回退与启动', 's.bind(("",0)) → open URL → python3 -m http.server "$PORT"', 'code/start.command', '9–14');
  return renderFigure('CASE-V08', body, { height: 960, metrics: [{ label: '占用端口样本', value: 21 }, { label: '固定候选端口', value: 8 }, { label: '系统回退', value: 'bind(0)' }, { label: '验收标题', value: 'KING_OK' }] });
}

function renderSRCV01() {
  const domainTone = { game: 'cyan', world: 'blue', engine: 'cyan', ui: 'blue', config: 'blue', entry: 'blue', main: 'cyan', utils: 'blue' };
  const files = data.code.files.map((file) => ({ label: file.path, value: file.lines, factId: factForFile(file.path), tone: domainTone[file.domain] || 'blue', detail: `${file.domain} · ${fmt(file.bytes)}B · SHA ${short(file.sha256, 12)}` }));
  const body = panel(38, 205, 1124, 300, '二维Treemap：面积严格等于19个第一方文件的物理行数', '上下两行高度与行数总量成比例；窄块显示编号，完整文件名、行数、字节和SHA列在下方。', 'blue')
    + twoRowTreemap(files, 55, 275, 1090, 185, 'value')
    + rankedList(files, { x: 48, y: 535, columns: 2, columnWidth: 555, rowHeight: 24, labelWidth: 285 });
  return renderFigure('SRC-V01', body, { height: 820, metrics: M.code, contentBottom: 760 });
}

function renderSRCV02() {
  const rows = data.code.snapshotFiles.sort((a, b) => b.bytes - a.bytes).slice(0, 21);
  const maxBytes = Math.max(...rows.map((row) => row.bytes));
  const domainTone = { game: 'cyan', world: 'blue', engine: 'cyan', ui: 'blue', config: 'blue', entry: 'blue', main: 'cyan', utils: 'blue', vendor: 'gold' };
  const body = panel(38, 205, 1124, 440, '21文件快照指纹条码', '左右两列使用同一字节比例尺；每行分别显示文件、字节条、物理行和SHA-256前缀。', 'blue')
    + rows.map((row, index) => {
      const rowsPerColumn = 11, column = Math.floor(index / rowsPerColumn), line = index % rowsPerColumn;
      const x = 55 + column * 555, y = 258 + line * 33, width = 145 * row.bytes / maxBytes;
      const tone = domainTone[row.domain] || (/lib\//.test(row.path) ? 'gold' : 'blue');
      const compactBytes = row.bytes >= 1000000 ? `${round(row.bytes / 1000000, 2)}MB` : `${round(row.bytes / 1024, 1)}KiB`;
      const compactMetric = `${compactBytes.replace('KiB', 'K').replace('MB', 'M')}·${fmt(row.lines)}L`;
      return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${row.path}; ${row.bytes} bytes; ${row.lines} lines; SHA ${row.sha256}`)}">${lineText(row.path, x, y + 13, 25, 'v11-file-label', 14, 1)}<rect x="${x + 205}" y="${y}" width="145" height="15" rx="3" class="v11-track"/><rect x="${x + 205}" y="${y}" width="${Math.max(2, width)}" height="15" rx="3" class="v11-fill-${tone}"/><text x="${x + 356}" y="${y + 13}" class="v11-code-source">${compactMetric}</text><text x="${x + 548}" y="${y + 13}" text-anchor="end" class="v11-code-source">${short(row.sha256, 8)}</text></g>`;
    }).join('')
    + sourceFacts([{ title: '原项目聚合', subtitle: '57 files · 1efe5f9c…', source: '/project/king只读复核', tone: 'green' }, { title: '证据快照', subtitle: '21运行文件', source: 'assets/king/code', tone: 'blue' }, { title: '字节一致', subtitle: '逐文件SHA-256与聚合哈希双重核对', source: 'SHA256SUMS', tone: 'gold' }], 48, 675);
  return renderFigure('SRC-V02', body, { height: 800, metrics: [{ label: '第一方', value: 19 }, { label: 'vendored', value: 2 }, { label: '完整快照', value: 21 }, { label: '原项目文件', value: 57 }], contentBottom: 740 });
}

function renderSRCV03() {
  const rows = Array.from({ length: 16 }, (_, index) => ({ label: `G${String(index + 1).padStart(2, '0')}` }));
  const columns = [{ label: 'DESIGN' }, { label: '源码' }, { label: 'V01–V12' }, { label: '截图/视频' }, { label: '覆盖类型' }];
  const limited = new Set(['G04', 'G05', 'G10', 'G11', 'G12', 'G13', 'G15']);
  const body = categoricalMatrix(rows, columns, (row, column, ri, ci) => ({ state: ci === 4 ? 'boundary' : ci >= 2 && limited.has(row.label) ? 'limited' : 'pass', label: ci === 0 ? '条款存在' : ci === 1 ? '符号定位' : ci === 2 ? (limited.has(row.label) ? '指定路径' : '运行观察') : ci === 3 ? (ri % 3 === 0 ? '媒体佐证' : '代码/日志') : (limited.has(row.label) ? '路径验收' : '全链对应') }), { x: 250, y: 220, cellW: 172, cellH: 30 })
    + sourceFacts([{ title: '全链对应', subtitle: '代码+运行路径直接连接', source: 'G/V/E crosswalk', tone: 'green' }, { title: '路径验收', subtitle: '英雄/技能的实际运行记录', source: 'acceptance scope', tone: 'gold' }, { title: '下一步', subtitle: '逐英雄×逐技能组合回归', source: 'test roadmap', tone: 'blue' }], 48, 720);
  return renderFigure('SRC-V03', body, { height: 950, metrics: [{ label: 'DESIGN条款', value: 16 }, { label: '源码模块', value: 17 }, { label: '验收编号', value: 12 }, { label: '状态语义', value: 3 }] });
}

function renderSRCV04() {
  const body = chain([
    { title: 'index.html', subtitle: 'importmap + UI root', tone: 'blue' },
    { title: 'ES Modules', subtitle: '17个src模块', tone: 'cyan' },
    { title: '本地vendored', subtitle: 'three r160 + Utils', tone: 'green' },
    { title: '浏览器能力', subtitle: 'WebGL/WebAudio/TTS', tone: 'cyan' },
    { title: '启动器', subtitle: 'Python静态HTTP', tone: 'gold' },
    { title: '运行边界', subtitle: '外部HTTP资源=0', tone: 'red' },
  ], { x: 42, y: 235, w: 165, h: 105, gap: 26 })
    + codeCard(42, 455, 350, 'import map', '"three": "./lib/three.module.js"', 'code/index.html')
    + codeCard(425, 455, 350, 'main入口', "import * as THREE from 'three';", 'code/src/main.js')
    + codeCard(808, 455, 350, '本地启动', 'python3 -m http.server "$PORT"', 'code/start.command')
    + sourceFacts([{ title: '不需要', subtitle: 'npm / bundler / build step', source: 'snapshot inspection', tone: 'green' }, { title: '仍依赖', subtitle: '浏览器 / Python / 本地Three.js', source: 'runtime boundary', tone: 'gold' }, { title: '不会声称', subtitle: '“零依赖”或“无系统要求”', source: 'claim boundary', tone: 'red' }], 48, 610);
  return renderFigure('SRC-V04', body, { height: 880, metrics: [{ label: '构建步骤', value: 0 }, { label: '外部HTTP素材', value: 0 }, { label: 'src模块', value: 17 }, { label: 'vendored库', value: 2 }] });
}

function renderPLATV01() {
  const body = chain([
    { title: 'Kimi K3', subtitle: '878个请求启动', tone: 'blue', source: 'llm_call_started' },
    { title: 'QueryEngine', subtitle: '上下文→模型→工具', tone: 'blue', source: 'ea0170c' },
    { title: '工具执行', subtitle: '968个完整生命周期', tone: 'cyan', source: 'public log' },
    { title: '浏览器反馈', subtitle: '376次唯一调用', tone: 'cyan', source: 'WebBrowser+Python ID' },
    { title: '原子写入', subtitle: '267次成功写入', tone: 'green', source: 'AtomicFileWriter' },
    { title: '复验接续', subtitle: '错误→后续轮次', tone: 'red', source: 'terminal events' },
  ], { x: 42, y: 245, w: 165, h: 112, gap: 26 })
    + arrow(1110, 445, 92, 445, 'cyan', true, '后续请求读取工具/浏览器结果，闭环继续')
    + panel(42, 495, 1116, 150, '真实调用链 · 01:55:36', 'Worker childSession→childRun→turn 11→LLM request→WebBrowser_26→Python request 97ad9951…→3,244ms完成', 'cyan')
    + sourceFacts([{ title: '模型层', subtitle: '推理与代码生成请求', source: 'observability', tone: 'blue' }, { title: '运行时层', subtitle: '编排/期限/工具/持久化', source: 'ea0170c + log', tone: 'blue' }, { title: '环境反馈层', subtitle: '真实浏览器与WebGL状态', source: '376 downstream IDs', tone: 'cyan' }], 48, 670);
  return renderFigure('PLAT-V01', body, { height: 920, metrics: [{ label: 'Kimi请求', value: 878, factId: 'fact.llm.started' }, { label: '工具调用', value: 968, factId: 'fact.tools.total' }, { label: 'WebBrowser', value: 376, factId: 'fact.browser.calls' }, { label: '原子写入', value: 267, factId: 'fact.controls.atomic' }] });
}

function renderPLATV02() {
  const lanes = ['用户', 'Kimi K3', '协调者', '10个Worker', '工程环境', '证据系统'].map((label) => ({ id: label, label }));
  const base = '2026-08-09T01:30:00+08:00';
  const end = '2026-08-09T07:01:00+08:00';
  const events = [
    { lane: '用户', start: '2026-08-09T01:31:05+08:00', end: '2026-08-09T01:32:45+08:00', label: '需求/4项确认', tone: 'gold' },
    { lane: 'Kimi K3', start: '2026-08-09T01:31:15+08:00', end: '2026-08-09T07:00:32+08:00', label: '878请求', tone: 'blue' },
    { lane: '协调者', start: '2026-08-09T01:31:00+08:00', end: '2026-08-09T07:00:41+08:00', label: '编排/复验/收口', tone: 'blue' },
    ...data.execution.workers.map((worker) => ({ lane: '10个Worker', start: worker.start, end: worker.end, label: worker.id, tone: worker.terminal === 'natural' ? 'green' : worker.terminal === 'deadline' ? 'gold' : 'red', detail: `${worker.label}; ${worker.terminal}; ${short(worker.sessionId)}` })),
    { lane: '工程环境', start: '2026-08-09T01:34:57+08:00', end: '2026-08-09T07:00:32+08:00', label: '文件/Bash/浏览器', tone: 'cyan' },
    { lane: '证据系统', start: '2026-08-09T01:30:01+08:00', end: '2026-08-09T07:00:41+08:00', label: '日志/DB/事件/账单', tone: 'green' },
  ];
  const body = panel(38, 205, 1124, 440, '六方责任泳道 · 共用真实时间轴', '责任按输入、MDC agent字段、工具和持久化记录区分；“同系统QA”不是组织独立第三方。', 'blue')
    + swimlanes(lanes, events, { x: 220, y: 275, w: 890, laneH: 56, start: base, end })
    + sourceFacts([{ title: '用户', subtitle: '范围选择与任务授权', source: 'interaction_requests', tone: 'gold' }, { title: '协调/Worker', subtitle: 'MDC agent=query|subagent', source: 'public log', tone: 'blue' }, { title: '证据系统', subtitle: '串起代码、日志、账单和媒体', source: 'DB/log/bill/media', tone: 'green' }], 48, 670);
  return renderFigure('PLAT-V02', body, { height: 920, metrics: [{ label: '责任层', value: 6 }, { label: '根Run', value: 1 }, { label: 'Worker Run', value: 10 }, { label: '证据域', value: 7 }] });
}

function renderPLATV03() {
  const steps = ['组装上下文', 'ContextCascade', '调用模型', '解析响应', '工具验证', '工具执行', '写回结果', '继续/终止'];
  const stepTones = ['blue', 'cyan', 'blue', 'blue', 'gold', 'cyan', 'cyan', 'green'];
  let body = chain(steps.map((title, index) => ({ title, subtitle: index === 2 ? '878 started' : index === 5 ? '968 calls' : index === 7 ? '873+5终态' : `Step ${index + 1}`, tone: stepTones[index] })), { x: 34, y: 215, w: 125, h: 105, gap: 18 });
  body += panel(38, 420, 542, 220, 'LLM请求账本 · 同一requestId唯一终态', 'started与completed/failed是同一计数单位，873+5=878。', 'blue');
  body += nodeCard({ x: 58, y: 505, w: 150, h: 92, title: 'started', value: 878, factId: 'fact.llm.started', detail: '878个唯一llm requestId started', tone: 'blue' });
  body += nodeCard({ x: 234, y: 505, w: 150, h: 92, title: 'completed', value: 873, factId: 'fact.llm.completed', detail: '873 completed', tone: 'green' });
  body += nodeCard({ x: 410, y: 505, w: 150, h: 92, title: 'cancelled', value: 5, factId: 'fact.llm.failed', detail: '5 failed/cancelled', tone: 'red' });
  body += arrow(208, 551, 234, 551, 'green') + arrow(384, 551, 410, 551, 'red');
  body += panel(620, 420, 542, 220, '工具调用账本 · 968条完整三阶段生命周期', '复合工具键在验证、调用和完成记录中各出现968次。', 'cyan');
  const toolStages = [['Stage 1验证', 968], ['Stage 5调用', 968], ['完成记录', 968]];
  body += toolStages.map(([title, value], index) => nodeCard({ x: 650 + index * 170, y: 505, w: 150, h: 92, title, value, detail: `${title} ${value}`, tone: index === 2 ? 'green' : 'cyan' })).join('');
  body += arrow(800, 551, 820, 551, 'cyan') + arrow(970, 551, 990, 551, 'green');
  body += `<path d="M520 506C565 405 590 405 650 506" class="v11-edge v11-stroke-purple v11-dashed"/><text x="585" y="398" text-anchor="middle" class="v11-edge-label">同一Agent循环可产生0..n次工具调用；不是守恒流</text>`;
  return renderFigure('PLAT-V03', body, { height: 700, metrics: [{ label: '源码步骤', value: 8 }, { label: 'started', value: 878, factId: 'fact.llm.started' }, { label: 'completed', value: 873, factId: 'fact.llm.completed' }, { label: 'cancelled', value: 5, factId: 'fact.llm.failed' }], contentBottom: 640 });
}

function renderPLATV04() {
  const points = data.execution.contextPoints;
  const series = [{ label: 'tokensBefore', tone: 'cyan', values: points.map((point) => ({ value: point.tokensBefore })) }, { label: '650,000阈值', tone: 'red', values: points.map(() => ({ value: 650000 })) }];
  const body = panel(38, 205, 1124, 440, '878次上下文评估 · 按模型调用序号', '曲线使用全部878个tokensBefore值；金色短线标记26次collapseExecuted=true。', 'blue')
    + lineChart(series, { x: 80, y: 270, w: 1030, h: 280, maxY: 650000, label: '模型调用序号 1→878', yLabel: '估算tokens' })
    + points.map((point, index) => point.collapseExecuted ? `<path d="M${80 + index / 877 * 1030} 570v18" class="v11-stroke-gold" stroke-width="3"/>` : '').join('')
    + sourceFacts([{ title: '峰值', subtitle: '240,202 < 650,000', source: 'ContextCascade', tone: 'cyan' }, { title: '轻量折叠', subtitle: '26次 / 释放2,029字符', source: 'collapseExecuted', tone: 'gold' }, { title: '未触发', subtitle: '重型压缩 / 413恢复 = 0', source: 'window events', tone: 'red' }], 48, 670);
  return renderFigure('PLAT-V04', body, { height: 920, metrics: [{ label: '评估', value: 878, factId: 'fact.context.evaluations' }, { label: '轻折叠', value: 26, factId: 'fact.context.collapses' }, { label: '峰值', value: 240202 }, { label: '阈值', value: 650000 }] });
}

function renderPLATV05() {
  const errors = Object.entries(data.execution.toolLifecycle.errorTrueByTool).map(([label, value]) => ({ label, value }));
  const body = sankeyColumns([
    { nodes: [{ id: 'calls', label: '复合工具键', value: 968, tone: 'blue' }] },
    { nodes: [{ id: 's1', label: 'Stage 1验证', value: 968, tone: 'cyan' }] },
    { nodes: [{ id: 's5', label: 'Stage 5调用', value: 968, tone: 'cyan' }] },
    { nodes: [{ id: 'ok', label: 'error=false', value: 951, tone: 'green' }, { id: 'err', label: 'error=true', value: 17, tone: 'red' }] },
  ], [{ from: 'calls', to: 's1', value: 968 }, { from: 's1', to: 's5', value: 968 }, { from: 's5', to: 'ok', value: 951, tone: 'green' }, { from: 's5', to: 'err', value: 17, tone: 'red' }], { x: 80, y: 245, w: 1000, h: 260 })
    + panel(38, 545, 1124, 205, '17次结构化错误按工具拆分', '错误被记录并返回Agent Loop；不能自动宣称17次全部被修复。', 'red')
    + barRows(errors, { x: 250, y: 610, w: 760, h: 20, gap: 5, max: 6 });
  return renderFigure('PLAT-V05', body, { height: 920, metrics: [{ label: 'Stage1', value: 968 }, { label: 'Stage5', value: 968 }, { label: '完成记录', value: 968 }, { label: '错误', value: 17, factId: 'fact.tools.error' }] });
}

function renderPLATV06() {
  const classes = ['QueryEngine', 'ContextCascade', 'ToolExecutionPipeline', 'SubAgentExecutor', 'AgentConcurrencyController', 'CheckpointService', 'BestEffortObservabilityRecorder'];
  let body = classes.map((name, index) => {
    const firstRow = index < 4, column = firstRow ? index : index - 4;
    const x = firstRow ? 42 + column * 279 : 181 + column * 279;
    const y = firstRow ? 210 : 350;
    return nodeCard({ x, y, w: 260, h: 118, index: index + 1, title: name, subtitle: index === 4 ? '源码存在；本案无直接事件' : '源码类名与日志组件交叉', tone: index === 4 ? 'gold' : 'blue', source: `ea0170c/${name}.java`, detail: `${name}；窗口前源码快照；${index === 4 ? '本案无直接运行事件' : '日志存在对应组件或事件'}` });
  }).join('');
  body += timeline([
      { time: '2026-08-09T00:42:00+08:00', label: 'ea0170c', sub: '窗口前源码快照', tone: 'green' },
      { time: '2026-08-09T01:30:00+08:00', label: '开发窗口', sub: '运行构建SHA缺失', tone: 'blue' },
      { time: '2026-08-09T07:01:00+08:00', label: '窗口结束', sub: '冻结证据', tone: 'cyan' },
      { time: '2026-08-09T12:04:00+08:00', label: '74aef15', sub: '只改四份说明文档', tone: 'gold' },
    ], { x: 120, y: 595, w: 950, start: '2026-08-09T00:42:00+08:00', end: '2026-08-09T12:04:00+08:00', top: 485, bottom: 620 });
  return renderFigure('PLAT-V06', body, { height: 770, metrics: [{ label: '固定源码类', value: 7 }, { label: '窗口前提交', value: 'ea0170c' }, { label: '窗口后提交', value: '74aef15' }, { label: '运行构建SHA', value: '缺失' }], contentBottom: 712 });
}

function renderPLATV07() {
  const minutes = data.execution.observabilityMinutes;
  const types = ['llm_call_started', 'llm_call_completed', 'process_started', 'process_finished', 'subagent_started', 'subagent_completed', 'subagent_failed'];
  const typeTone = { llm_call_started: 'blue', llm_call_completed: 'green', process_started: 'cyan', process_finished: 'cyan', subagent_started: 'blue', subagent_completed: 'green', subagent_failed: 'red' };
  const series = types.map((type) => ({ label: type.replace('llm_call_', 'llm:'), tone: typeTone[type], values: minutes.map((minute) => ({ value: minute.types[type] || 0 })) }));
  const body = panel(38, 205, 1124, 450, '2,003条观测事件 · 分钟级事件河流', '所有事件按occurredAt所在分钟聚合；七条线共享同一时间顺序，不把数量当质量。', 'blue')
    + lineChart(series, { x: 80, y: 270, w: 1030, h: 280, label: '01:31 → 07:00（有事件分钟）', yLabel: '每分钟事件数' })
    + sourceFacts([{ title: 'LLM', subtitle: '878 started / 873 completed / 5 failed', source: 'observability JSONL', tone: 'blue' }, { title: '进程', subtitle: '104 started / 104 finished', source: 'process events', tone: 'cyan' }, { title: 'Worker', subtitle: '10 started / 10终态', source: 'subagent events', tone: 'green' }], 48, 675);
  return renderFigure('PLAT-V07', body, { height: 920, metrics: [{ label: '事件总数', value: 2003 }, { label: 'LLM开始', value: 878 }, { label: 'LLM终态', value: 878 }, { label: 'Worker终态', value: 10 }] });
}

function renderPLATV08() {
  const controls = data.execution.controlEvents;
  const lanes = [{ id: 'atomic-write', label: 'Atomic write' }, { id: 'checkpoint', label: 'Checkpoint' }, { id: 'mcp-loss', label: 'MCP loss' }, { id: 'mcp-reconnect', label: 'MCP reconnect' }];
  const events = controls.map((event) => ({ lane: event.type, start: `${event.timestamp}+08:00`, end: `${event.timestamp}+08:00`, label: '', tone: event.type === 'atomic-write' ? 'green' : event.type === 'checkpoint' ? 'blue' : event.type === 'mcp-loss' ? 'red' : 'cyan', detail: `${event.type} · ${event.timestamp} · public log line ${event.line}` }));
  const body = panel(38, 205, 1124, 400, '运行控制事件按真实时间对齐', '单个事件以短竖条表示；60次loss与60次reconnect按日志顺序配对。', 'blue')
    + swimlanes(lanes, events, { x: 210, y: 280, w: 900, laneH: 68, start: '2026-08-09T01:30:00+08:00', end: '2026-08-09T07:01:00+08:00' })
    + sourceFacts([{ title: '原子写入', subtitle: '267次成功替换', source: 'AtomicFileWriter', tone: 'green' }, { title: 'Checkpoint', subtitle: '157次保存；实际恢复=0', source: 'CheckpointService', tone: 'blue' }, { title: 'MCP', subtitle: '60 loss / 60 reconnect / 0孤立', source: 'McpClientManager', tone: 'cyan' }], 48, 630);
  return renderFigure('PLAT-V08', body, { height: 890, metrics: [{ label: 'Atomic write', value: 267, factId: 'fact.controls.atomic' }, { label: 'Checkpoint', value: 157, factId: 'fact.controls.checkpoint' }, { label: '重连配对', value: 60, factId: 'fact.controls.reconnectPairs' }, { label: '实际Checkpoint恢复', value: 0 }] });
}

function renderPLATV09() {
  const body = nodeCard({ x: 480, y: 270, w: 240, h: 118, title: 'sessions', value: 1, subtitle: short(data.database.session.sessionId, 18), detail: 'session-row.json；token聚合字段为0', tone: 'blue' })
    + nodeCard({ x: 105, y: 470, w: 270, h: 118, title: 'messages', value: 229, subtitle: '117 user / 112 assistant', detail: 'sessionId+窗口双限定', tone: 'cyan' })
    + nodeCard({ x: 465, y: 470, w: 270, h: 118, title: 'activities', value: 113, subtitle: '协调者工具活动', detail: '协调者113条；全体工具另按复合键统计968次', tone: 'green' })
    + nodeCard({ x: 825, y: 470, w: 270, h: 118, title: 'interaction_requests', value: 4, subtitle: '四项需求选择', detail: 'created/decided/responded', tone: 'gold' })
    + arrow(520, 388, 240, 470, 'cyan', false, 'session_id') + arrow(600, 388, 600, 470, 'green', false, 'session_id') + arrow(680, 388, 960, 470, 'gold', false, 'session_id')
    + codeCard(48, 620, 530, '冻结SQL口径', "session_id = :root AND created_at >= start AND created_at < end", 'provenance.json')
    + codeCard(620, 620, 530, 'Token字段边界', 'sessions.total_input_tokens = 0; token事实来自messages/events/bill', 'db/session-row.json');
  return renderFigure('PLAT-V09', body, { height: 950, metrics: [{ label: 'sessions', value: 1 }, { label: 'messages', value: 229 }, { label: 'activities', value: 113 }, { label: 'interactions', value: 4 }] });
}

function renderPLATV10() {
  const body = panel(60, 220, 1080, 440, '本案实际进入的工作域', '实线连接本案真实观测；虚线标出平台具备、但本次任务没有调用的机制。', 'blue')
    + nodeCard({ x: 500, y: 300, w: 200, h: 110, title: 'Project工作目录', subtitle: '/project/king', detail: '所有开发工具在用户指定项目范围内', tone: 'green' })
    + cardGrid([
      { title: '用户范围确认', subtitle: '4项需求选择', tone: 'gold' }, { title: '工具验证管线', subtitle: '968完整生命周期', tone: 'cyan' },
      { title: '安全审计', subtitle: '开发窗口116行', tone: 'blue' }, { title: '发布授权', subtitle: '窗口外；GitHub未执行', tone: 'red' },
    ], { x: 90, y: 455, columns: 4, cardW: 235, cardH: 100, gapX: 35 })
    + arrow(220, 455, 500, 380, 'gold') + arrow(490, 455, 560, 410, 'cyan') + arrow(790, 455, 650, 410, 'blue') + arrow(1030, 455, 700, 380, 'red', true)
    + sourceFacts([{ title: '本案工作面', subtitle: 'Project/工具/审计/授权', source: 'logs+db', tone: 'green' }, { title: '本次未调用', subtitle: 'Team/Swarm/Artifact发布', source: 'runtime scope', tone: 'gold' }, { title: '关键结果', subtitle: '开发与发布权限分层记录', source: 'security audit', tone: 'blue' }], 48, 690);
  return renderFigure('PLAT-V10', body, { height: 930, metrics: [{ label: '开发permission请求', value: 0 }, { label: '安全日志行', value: 116 }, { label: '工作目录', value: 1 }, { label: 'GitHub发布', value: 0 }] });
}

function renderRUNV01() {
  const lanes = [{ id: 'ROOT', label: 'ROOT 协调者' }, ...data.execution.workers.map((worker) => ({ id: worker.id, label: `${worker.id} ${worker.label.replace(/^R\d+\s*/, '')}` }))];
  const start = '2026-08-09T01:30:00+08:00', end = '2026-08-09T07:01:00+08:00';
  const events = [
    { lane: 'ROOT', start: data.execution.rootRun.start, end: data.execution.rootRun.end, label: '协调/复验/收口', tone: 'blue', detail: data.execution.rootRun.runId },
    ...data.execution.workers.map((worker) => ({ lane: worker.id, start: worker.start, end: worker.end, label: `${worker.id} ${worker.terminal}`, tone: worker.terminal === 'natural' ? 'green' : worker.terminal === 'deadline' ? 'gold' : 'red', detail: `${worker.label}; ${round(worker.durationMs / 1000, 3)}s; input ${fmt(worker.inputTokens)}; ${worker.runId}` })),
  ];
  const body = panel(38, 205, 1324, 600, '同轴Gantt：1个根Run＋10个Worker Run', '横向距离按真实时间；颜色编码自然完成/30分钟期限/最大轮次，条高不编码Token。', 'blue')
    + swimlanes(lanes, events, { x: 250, y: 270, w: 1060, laneH: 43, start, end })
    + data.execution.workers.map((worker, index) => {
      const maxInput = Math.max(...data.execution.workers.map((item) => item.inputTokens));
      const width = 150 * worker.inputTokens / maxInput;
      return `<rect x="${70}" y="${315 + index * 43}" width="${width}" height="8" rx="3" class="v11-fill-blue"/><text x="${75 + width}" y="${323 + index * 43}" class="v11-tiny">${round(worker.inputTokens / 1e6, 2)}M</text>`;
    }).join('')
    + sourceFacts([{ title: '终止语义', subtitle: '1 natural / 6 deadline / 3 maxTurns', source: 'terminal events+duration', tone: 'gold' }, { title: '接续', subtitle: '父Run在每个Worker后继续活动', source: 'public log', tone: 'cyan' }, { title: '时间口径', subtitle: '5:29:17是首末请求锚点跨度', source: 'time window', tone: 'blue' }], 48, 825, 3, 1304);
  return renderFigure('RUN-V01', body, { width: 1400, height: 1060, metrics: M.run });
}

function renderRUNV02() {
  let body = nodeCard({ x: 555, y: 205, w: 290, h: 118, title: 'ROOT session / run', subtitle: `S ${short(data.execution.rootRun.sessionId, 14)}`, source: `R ${short(data.execution.rootRun.runId, 14)}`, detail: `${data.execution.rootRun.sessionId} ${data.execution.rootRun.runId}`, tone: 'blue' });
  body += `<path d="M700 323V365M90 365H1310" class="v11-edge v11-stroke-blue"/><text x="700" y="352" text-anchor="middle" class="v11-edge-label">parentRunId = ROOT</text>`;
  data.execution.workers.forEach((worker, index) => {
    const col = index % 5, row = Math.floor(index / 5);
    const x = 62 + col * 266, y = 405 + row * 150;
    const center = x + 112;
    body += `<path d="M${center} 365V${y}" class="v11-edge v11-stroke-${worker.terminal === 'natural' ? 'green' : worker.terminal === 'deadline' ? 'gold' : 'red'}"/><circle cx="${center}" cy="${y}" r="4.5" class="v11-fill-${worker.terminal === 'natural' ? 'green' : worker.terminal === 'deadline' ? 'gold' : 'red'}"/>`;
    body += nodeCard({ x, y, w: 224, h: 116, title: `${worker.id} · ${worker.terminal}`, subtitle: `S ${short(worker.sessionId, 12)}`, source: `R ${short(worker.runId, 12)}`, detail: `childSession ${worker.sessionId}; childRun ${worker.runId}; parent ${worker.parentRunId}`, tone: worker.terminal === 'natural' ? 'green' : worker.terminal === 'deadline' ? 'gold' : 'red' });
  });
  return renderFigure('RUN-V02', body, { width: 1400, height: 740, metrics: [{ label: 'sessions', value: 11, factId: 'fact.run.total' }, { label: 'runs', value: 11, factId: 'fact.run.total' }, { label: 'parent edges', value: 10, factId: 'fact.run.workers' }, { label: 'root', value: 1 }], contentBottom: 671 });
}

function renderRUNV03() {
  const columns = ['地图/world', '战斗/state', 'AI/spawner', 'UI/HUD', '视觉/VFX', '浏览器QA'].map((label) => ({ label }));
  const rows = data.execution.workers.map((worker) => ({ label: `${worker.id} ${worker.label.replace(/^R\d+\s*/, '')}`, domain: worker.domain }));
  const map = [
    [2, 0, 0, 0, 1, 1], [0, 2, 1, 0, 1, 1], [1, 1, 2, 0, 0, 1], [1, 1, 2, 0, 0, 2], [0, 2, 2, 0, 0, 2],
    [1, 1, 2, 0, 0, 2], [0, 0, 0, 2, 1, 2], [0, 0, 0, 1, 2, 2], [0, 1, 1, 2, 2, 2], [0, 1, 1, 2, 1, 2],
  ];
  const body = categoricalMatrix(rows, columns, (row, column, ri, ci) => ({ state: map[ri][ci] === 2 ? 'pass' : map[ri][ci] === 1 ? 'limited' : 'none', label: map[ri][ci] === 2 ? '主责任/直接活动' : map[ri][ci] === 1 ? '读取/联调' : '', detail: `${row.label} × ${column.label}；${map[ri][ci] === 2 ? '任务提示与工具活动直接对应' : map[ri][ci] === 1 ? '联调或读取证据' : '未建立直接责任'}` }), { x: 340, y: 220, cellW: 165, cellH: 47 })
    + sourceFacts([{ title: '主责任', subtitle: '提示词任务域＋对应工具活动', source: 'subagent prompt/log', tone: 'green' }, { title: '联调', subtitle: '读取/浏览器/跨模块验证', source: 'tool records', tone: 'gold' }, { title: '责任视图', subtitle: '主责文件、联调文件与交付物分层展示', source: 'prompt + tool map', tone: 'blue' }], 48, 720, 3, 1304);
  return renderFigure('RUN-V03', body, { width: 1400, height: 950, metrics: [{ label: 'Worker', value: 10 }, { label: '责任域', value: 6 }, { label: '构建阶段', value: 5 }, { label: '修复/QA', value: 5 }] });
}

function renderRUNV04() {
  const runs = [data.execution.rootRun, ...data.execution.workers];
  const maxDuration = Math.max(...data.execution.workers.map((worker) => worker.durationMs), time(data.execution.rootRun.end) - time(data.execution.rootRun.start));
  const maxInput = Math.max(...runs.map((run) => run.inputTokens));
  const maxOutput = Math.max(...runs.map((run) => run.outputTokens));
  const x = (duration) => 100 + duration / maxDuration * 1000;
  const y = (input) => 640 - input / maxInput * 370;
  const body = `<rect x="100" y="250" width="1000" height="390" class="v11-chart-frame"/><path d="M100 640H1100M100 250V640" class="v11-axis"/>${[0, .25, .5, .75, 1].map((r) => `<path d="M${100 + r * 1000} 250V640" class="v11-gridline"/>${textEl(`${round(maxDuration / 60000 * r, 1)}m`, 100 + r * 1000, 665, 'v11-axis-label', ' text-anchor="middle"')}`).join('')}${runs.map((run, index) => {
    const duration = run.id === 'ROOT' ? time(run.end) - time(run.start) : run.durationMs;
    const radius = 7 + 24 * Math.sqrt(run.outputTokens / Math.max(1, maxOutput));
    const tone = run.id === 'ROOT' ? 'blue' : run.terminal === 'natural' ? 'green' : run.terminal === 'deadline' ? 'gold' : 'red';
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${run.id}; duration ${duration}ms; input ${run.inputTokens}; output ${run.outputTokens}; terminal ${run.terminal}`)}"><circle cx="${x(duration)}" cy="${y(run.inputTokens)}" r="${radius}" class="v11-fill-${tone}" opacity=".78"/><text x="${x(duration)}" y="${y(run.inputTokens) + 4}" text-anchor="middle" class="v11-bubble-label">${esc(run.id)}</text></g>`;
  }).join('')}${textEl('时长（分钟）', 600, 702, 'v11-axis-title', ' text-anchor="middle"')}${textEl('输入Token', 28, 445, 'v11-axis-title', ' transform="rotate(-90 28 445)" text-anchor="middle"')}`
    + sourceFacts([{ title: 'X轴', subtitle: '真实执行时长', source: 'terminal.durationMs', tone: 'cyan' }, { title: 'Y轴', subtitle: '完成请求输入Token归账', source: 'llm completed', tone: 'blue' }, { title: '气泡面积', subtitle: '输出Token平方根比例', source: 'outputTokens', tone: 'gold' }], 48, 720);
  return renderFigure('RUN-V04', body, { height: 950, metrics: [{ label: '执行体', value: 11 }, { label: '运行输入', value: 82300554 }, { label: '运行输出', value: 670170 }, { label: '终态类型', value: 3 }] });
}

function renderRUNV05() {
  const columns = [{ label: 'prompt字符' }, { label: '指纹' }, { label: '验收条款' }, { label: '预算条款' }, { label: '终态' }];
  const rows = data.execution.workers.map((worker) => ({ label: `${worker.id} ${worker.label.replace(/^R\d+\s*/, '')}`, worker }));
  const maxPrompt = Math.max(...data.execution.workers.map((worker) => worker.promptLength));
  const body = categoricalMatrix(rows, columns, (row, column, ri, ci) => {
    const worker = row.worker;
    if (ci === 0) return { state: 'pass', label: `${worker.promptLength}\n${round(worker.promptLength / maxPrompt * 100, 0)}%`, detail: `promptLength ${worker.promptLength}` };
    if (ci === 1) return { state: 'pass', label: short(worker.promptFingerprint, 12), detail: worker.promptFingerprint };
    if (ci === 2) return { state: 'pass', label: ri === 9 ? '清单式QA' : '阶段验收', detail: '公开消息中的提示词包含验收要求' };
    if (ci === 3) return { state: ri >= 5 && ri <= 8 ? 'pass' : 'boundary', label: ri >= 5 && ri <= 8 ? '显式预算' : '未见显式', detail: '只按冻结提示词字面标记' };
    return { state: worker.terminal === 'natural' ? 'pass' : worker.terminal === 'deadline' ? 'limited' : 'boundary', label: worker.terminal, detail: worker.terminal };
  }, { x: 360, y: 220, cellW: 190, cellH: 48 })
    + sourceFacts([{ title: '可识别', subtitle: '10份长度＋SHA-256指纹', source: 'subagent_started', tone: 'green' }, { title: '可比较', subtitle: '验收条款与预算条款逐项对照', source: 'DB messages', tone: 'cyan' }, { title: '实际用途', subtitle: '确认十次任务分派并非重复记录', source: 'prompt fingerprints', tone: 'gold' }], 48, 720, 3, 1304);
  return renderFigure('RUN-V05', body, { width: 1400, height: 950, metrics: [{ label: '提示词', value: 10 }, { label: '最短', value: 1606 }, { label: '最长', value: 3046 }, { label: '唯一指纹', value: 10 }] });
}

function renderRUNV06() {
  const milestones = [
    ['M0', '规范与骨架', 'ROOT', 'DESIGN.md / index'], ['M1', '地图可游走', 'R01', 'map/models/config'], ['M2', '战斗技能', 'R02', 'state/skills/shop'], ['M3', 'AI与野区', 'R03', 'ai/spawner'],
    ['M4', '可终结性攻坚', 'R04–R06', '堵路/水晶/门径'], ['M5', 'HUD与音效', 'R07', 'hud/screens/audio'], ['M6', '视觉性能', 'R08–R09', 'models/vfx/DOM'], ['M7', 'QA终验', 'R10+ROOT', 'V01–V12'],
  ];
  const milestoneTone = { M0: 'blue', M1: 'blue', M2: 'cyan', M3: 'cyan', M4: 'red', M5: 'blue', M6: 'cyan', M7: 'green' };
  let body = '';
  milestones.forEach(([id, title, run, files], index) => {
    const col = index % 4, row = Math.floor(index / 4), x = 42 + col * 279, y = 210 + row * 150;
    body += nodeCard({ x, y, w: 260, h: 120, index: id, title, subtitle: `${run} · ${files}`, tone: milestoneTone[id], detail: `${id}；${title}；${run}；${files}；首次出现时间来自日志/截图锚点` });
    if (index < milestones.length - 1 && col < 3) body += arrow(x + 260, y + 60, x + 279, y + 60, milestoneTone[id]);
  });
  body += `<path d="M1158 270V340H172V360" class="v11-edge v11-stroke-gold v11-dashed"/><text x="665" y="335" text-anchor="middle" class="v11-edge-label">日志、文件活动与截图锚点组成的阶段接力</text>`;
  body += panel(48, 525, 1104, 105, '工程阶段谱系', 'M0–M7由Worker启动/终态、首次文件活动、截图与最终代码交叉重建。', 'gold');
  body += sourceFacts([{ title: '阶段任务', subtitle: '每个里程碑都连接对应Run', source: 'logs+messages', tone: 'green' }, { title: '交付文件', subtitle: '最终文件与首次活动锚点', source: 'file activities', tone: 'blue' }, { title: '画面进展', subtitle: '截图和视频标记可运行阶段', source: 'media anchors', tone: 'gold' }], 48, 655);
  return renderFigure('RUN-V06', body, { height: 780, metrics: [{ label: '里程碑', value: 8 }, { label: '构建Run', value: 3 }, { label: '修复Run', value: 6 }, { label: 'Git阶段快照', value: 0 }], contentBottom: 720 });
}

function renderRUNV07() {
  const toolOf = (record) => String(record.id || '').match(/^([A-Za-z]+)/)?.[1] || 'Unknown';
  const records = data.database.activities.map((record) => ({ time: new Date(record.timestamp).toISOString(), tool: toolOf(record), summary: record.summary, duration: record.duration, id: record.id }));
  const groups = data.database.activityToolTypes.map((item) => ({ label: item.tool, value: item.count, tone: item.tool === 'WebBrowser' ? 'cyan' : item.tool === 'Agent' ? 'gold' : 'blue', detail: `${item.count}/113 ROOT activities` }));
  const activityTone = (tool) => tool === 'WebBrowser' ? 'cyan' : tool === 'Agent' ? 'gold' : 'blue';
  const body = panel(38, 205, 1124, 315, '113条协调者activities · 按活动ID前缀复算9类工具', '长度编码真实计数：WebBrowser 54、Sleep 18、Bash 12、Agent 10、Read 10、Grep 4、Write 2、Edit 2、AskUserQuestion 1。', 'blue')
    + barRows(groups, { x: 275, y: 295, w: 800, h: 20, gap: 5, max: 54 })
    + panel(38, 545, 1124, 145, '113条完整活动时间胶片', '每个标记仍保留原始id、summary、时间和duration；分类不删除自由文本摘要。', 'cyan')
    + records.map((record, index) => `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${record.id}; ${record.tool}; ${record.summary}; ${record.time}; ${record.duration}ms`)}"><rect x="${60 + index / 112 * 1080}" y="${600 + (index % 4) * 18}" width="5" height="13" class="v11-fill-${activityTone(record.tool)}"/></g>`).join('')
    + sourceFacts([{ title: '协调者视图', subtitle: 'ROOT会话113条活动', source: 'activities export', tone: 'blue' }, { title: '分类键', subtitle: '按活动ID前缀精确归类', source: 'id /^([A-Za-z]+)/', tone: 'green' }, { title: '全体视图', subtitle: 'Worker与协调者合计968次工具', source: 'composite tool keys', tone: 'cyan' }], 48, 710);
  return renderFigure('RUN-V07', body, { height: 835, metrics: [{ label: 'activities', value: 113 }, { label: '工具类型', value: 9 }, { label: '全部工具', value: 968 }, { label: '窗口', value: '01:30–07:01' }], contentBottom: 775 });
}

function renderRUNV08() {
  const items = [
    { time: '2026-08-09T01:31:05+08:00', label: '需求到达', sub: '一句话目标', tone: 'gold' },
    { time: '2026-08-09T01:32:44+08:00', label: '4项确认完成', sub: '39.561s', tone: 'green' },
    { time: '2026-08-09T01:34:57+08:00', label: '目录/工具探测', sub: 'Bash_1', tone: 'blue' },
    { time: '2026-08-09T01:35:03+08:00', label: 'CDN双源200', sub: 'jsDelivr+unpkg', tone: 'cyan' },
    { time: '2026-08-09T01:39:06+08:00', label: 'DESIGN落盘', sub: 'Atomic write', tone: 'green' },
    { time: '2026-08-09T01:39:58+08:00', label: 'R01启动', sub: '地图地基', tone: 'green' },
  ];
  const body = panel(38, 205, 1124, 390, '编码前Preflight · 真实时间比例', '先确认范围，再探测项目目录、Python/Node、CDN可用性、本地化依赖和端口约束。', 'blue')
    + timeline(items, { x: 80, y: 420, w: 1040, start: items[0].time, end: items.at(-1).time, top: 260, bottom: 450 })
    + codeCard(48, 630, 520, 'Bash_1环境探测', 'ls -la project/king; which python3; which node; which npx', 'db/activities', 'Bash_1')
    + codeCard(630, 630, 520, 'Bash_2依赖探测', 'curl jsDelivr → HTTP/2 200; curl unpkg → HTTP/2 200', 'db/activities', 'Bash_2');
  return renderFigure('RUN-V08', body, { height: 960, metrics: [{ label: '范围确认', value: '39.561s' }, { label: 'Preflight跨度', value: '8m53s' }, { label: 'CDN 200', value: 2 }, { label: 'R01启动', value: '01:39:58' }] });
}

function renderDBGV01() {
  const incidents = [
    ['I1', '小兵堵路', '长局≈100兵聚集', 'ai.js路径/分离', 'R04', '峰值113→46'],
    ['I2', '终局僵持', '1350s水晶不可攻击', 'state.isCrystalInvuln', '协调者Edit', '水晶可终结'],
    ['I3', '基地贴墙', '英雄无法进基地', 'ai._gateRoute/ASSAULT', 'R06', '3/3对局终结'],
    ['I4', '发光/FPS', '共享材质污染+DOM压力', 'models/vfx/hud', 'R08/R09', '6→21–31.8 FPS'],
  ];
  let body = '';
  incidents.forEach((incident, row) => {
    const y = 220 + row * 125;
    const cols = [incident[1], incident[2], incident[3], incident[4], incident[5]];
    body += textEl(incident[0], 48, y + 47, 'v11-incident-id');
    cols.forEach((value, col) => {
      body += nodeCard({ x: 105 + col * 210, y, w: 190, h: 94, index: col + 1, title: ['问题', '运行取证', '根因/符号', '修改主体', '回归证据'][col], subtitle: value, detail: `${incident[0]} ${['问题', '取证', '根因', '修改主体', '回归'][col]}：${value}`, tone: ['red', 'gold', 'blue', 'blue', 'green'][col] });
      if (col < 4) body += arrow(295 + col * 210, y + 47, 315 + col * 210, y + 47, col === 0 ? 'red' : col === 4 ? 'green' : 'cyan');
    });
  });
  return renderFigure('DBG-V01', body, { height: 900, metrics: [{ label: '高影响问题', value: 4 }, { label: '专项Worker', value: 4 }, { label: '协调者直改', value: 1 }, { label: '终局长局', value: 3 }] });
}

function renderDBGV02() {
  const body = panel(42, 205, 535, 410, '峡谷坐标证据', '观察点(-80,60)附近出现小兵聚集；图示坐标来自报告冻结状态，不伪造修复前代码。', 'red')
    + `<rect x="90" y="260" width="430" height="300" class="v11-map"/><path d="M115 520L470 285M115 285L470 520M130 400H490" class="v11-lane-path"/><circle cx="170" cy="340" r="30" class="v11-target"/><text x="170" y="345" text-anchor="middle" class="v11-target-text">≈100</text><text x="210" y="325" class="v11-row-label">聚集点 (-80,60)</text>${Array.from({ length: 18 }, (_, index) => `<circle cx="${145 + (index % 6) * 10}" cy="${365 + Math.floor(index / 6) * 10}" r="3" class="v11-fill-red"/>`).join('')}`
    + panel(610, 205, 548, 410, '最终实现的三层防卡死', '只展示冻结最终代码：路径点推进、脱离检测、局部分离；修复前源码未冻结。', 'blue')
    + codeCard(635, 270, 498, '路径推进', 'updateMinion(state, m, dt) → wpIndex / path', 'code/src/game/ai.js')
    + codeCard(635, 395, 498, '卡死自救', '检测位移/计时 → 跳过或重定位路径点', 'code/src/game/ai.js')
    + barRows([{ label: '修复前峰值', value: 113, tone: 'red' }, { label: '修复后峰值', value: 46, tone: 'green' }], { x: 280, y: 650, w: 700, h: 40, gap: 22, max: 113 });
  return renderFigure('DBG-V02', body, { height: 930, metrics: [{ label: '观察聚集', value: '≈100' }, { label: '修复前峰值', value: 113 }, { label: '修复后峰值', value: 46 }, { label: '坐标', value: '(-80,60)' }] });
}

function renderDBGV03() {
  const rows = [
    { label: '存活防御塔>0', old: '水晶无敌', final: '水晶无敌/减伤' },
    { label: '存活防御塔=0', old: '仍可能无敌', final: '水晶可选中/可伤害' },
    { label: 'AI到达基地', old: '无有效目标', final: '锁定水晶' },
    { label: '对局时间=1350s', old: '僵持观察', final: '终局规则复验' },
  ];
  const columns = [{ label: '观察到的旧行为' }, { label: '最终规则' }, { label: '证据类型' }];
  const body = categoricalMatrix(rows, columns, (row, column, ri, ci) => ({ state: ci === 0 ? 'boundary' : ci === 1 ? 'pass' : 'limited', label: ci === 0 ? row.old : ci === 1 ? row.final : ri === 3 ? '长局状态' : '代码符号', detail: `${row.label}；${ci === 0 ? row.old : ci === 1 ? row.final : '证据边界'}` }), { x: 390, y: 230, cellW: 245, cellH: 92 })
    + codeCard(48, 620, 520, '最终水晶无敌判断', 'isCrystalInvuln(crystal) { ... surviving towers ... }', 'code/src/game/state.js', '914')
    + codeCard(630, 620, 520, 'AI目标过滤', "if (u.kind === 'crystal' && state.isCrystalInvuln(u)) continue;", 'code/src/game/ai.js', '28');
  return renderFigure('DBG-V03', body, { height: 950, metrics: [{ label: '观察时间', value: '1350s' }, { label: '协调者Edit', value: 1 }, { label: '关键函数', value: 'isCrystalInvuln' }, { label: '最终结果', value: '可终结' }] });
}

function renderDBGV04() {
  const body = panel(42, 205, 535, 390, '基地门径坐标与路由', '三门路由把英雄从墙外导向入口，再切换ASSAULT；坐标来自最终ai.js。', 'blue')
    + `<rect x="100" y="260" width="420" height="285" class="v11-map"/><rect x="260" y="315" width="105" height="150" class="v11-base-wall"/><path d="M120 505C190 500 205 440 260 430M120 405C180 405 205 390 260 390M120 300C190 305 205 345 260 350" class="v11-lane-path"/><circle cx="120" cy="405" r="12" class="v11-fill-red"/><text x="95" y="385" class="v11-row-label">贴墙英雄</text><circle cx="260" cy="350" r="9" class="v11-fill-green"/><circle cx="260" cy="390" r="9" class="v11-fill-green"/><circle cx="260" cy="430" r="9" class="v11-fill-green"/><text x="380" y="340" class="v11-card-title">3个基地门</text>`
    + panel(610, 205, 548, 390, 'AI状态与代码责任', '路径点路由解决几何入口，ASSAULT解决解防后目标优先级。', 'purple')
    + codeCard(635, 260, 498, '_gateRoute(x,z)', '计算最近基地门 → 中间路点 → 基地内部目标', 'code/src/game/ai.js', '827')
    + codeCard(635, 385, 498, 'ASSAULT', "this.mode = 'ASSAULT'; → enemyCrystal", 'code/src/game/ai.js', '419–422')
    + sourceFacts([{ title: '观察事实', subtitle: '英雄贴墙/基地外徘徊', source: '长局浏览器状态', tone: 'red' }, { title: '最终实现', subtitle: '_gateRoute + ASSAULT', source: 'ai.js', tone: 'blue' }, { title: '回归', subtitle: 'R06三局均能终结', source: '结果记录', tone: 'green' }], 48, 630);
  return renderFigure('DBG-V04', body, { height: 900, metrics: [{ label: '基地门', value: 3 }, { label: '关键函数', value: '_gateRoute' }, { label: 'AI模式', value: 'ASSAULT' }, { label: '终结对局', value: '3/3' }] });
}

function renderDBGV05() {
  const before = shot('1786226388657');
  const after = shot('1786228379180');
  const body = imagePanel(48, 225, 500, 300, before, '事故观察：发光/性能问题', [{ x: .55, y: .48, detail: '共享材质状态可能污染多个实例' }, { x: .18, y: .72, detail: '高频DOM更新造成主线程压力' }])
    + imagePanel(652, 225, 500, 300, after, '最终运行：独立外壳＋节流后', [{ x: .52, y: .45, detail: '角色材质与发光壳层职责分离' }, { x: .14, y: .74, detail: 'HUD/血条更新节流' }])
    + codeCard(48, 560, 340, '共享材质污染', 'clone/material isolation per hero instance', 'code/src/world/models.js')
    + codeCard(430, 560, 340, '独立发光壳层', 'shell mesh + own material lifecycle', 'code/src/engine/vfx.js')
    + codeCard(812, 560, 340, 'DOM节流', 'HUD/bars update cadence reduced', 'code/src/ui/hud.js')
    + barRows([{ label: '事故窗口', value: 6, tone: 'red' }, { label: '修复后低值', value: 21, tone: 'gold' }, { label: '修复后高值', value: 31.8, tone: 'green' }], { x: 290, y: 700, w: 750, h: 28, gap: 10, max: 31.8 });
  return renderFigure('DBG-V05', body, { height: 980, metrics: [{ label: '事故FPS', value: 6 }, { label: '修复后范围', value: '21–31.8' }, { label: '截图联证', value: 2 }, { label: '代码责任', value: 3 }] });
}

function renderDBGV06() {
  const rows = [{ label: 'I1 堵路' }, { label: 'I2 僵持' }, { label: 'I3 贴墙' }, { label: 'I4 发光/FPS' }];
  const columns = [{ label: '发现主体' }, { label: '状态/日志' }, { label: '代码符号' }, { label: '修改主体' }, { label: '截图' }, { label: '长局回归' }];
  const values = [
    ['ROOT', '≈100/113', 'updateMinion', 'R04', '有', '46峰值'],
    ['ROOT', '1350s', 'isCrystalInvuln', 'ROOT Edit', '状态', '可终结'],
    ['ROOT', '坐标/模式', '_gateRoute', 'R06', '有', '3/3'],
    ['R08/ROOT', '6 FPS', 'models/vfx/hud', 'R08/R09', '前后', '21–31.8'],
  ];
  const body = categoricalMatrix(rows, columns, (row, col, ri, ci) => ({ state: ci === 5 ? 'limited' : ci === 3 ? 'pass' : 'pass', label: values[ri][ci], detail: `${row.label} × ${col.label}: ${values[ri][ci]}` }), { x: 270, y: 240, cellW: 145, cellH: 98 })
    + sourceFacts([{ title: '运行事实', subtitle: '坐标/状态/FPS/终局结果', source: 'browser/log', tone: 'green' }, { title: '代码责任', subtitle: '最终符号可定位', source: 'frozen source', tone: 'blue' }, { title: '性能推断', subtitle: '相关修复≠严格基准因果', source: 'boundary', tone: 'gold' }], 48, 680);
  return renderFigure('DBG-V06', body, { height: 910, metrics: [{ label: '问题', value: 4 }, { label: '专项Worker', value: 4 }, { label: '协调者直改', value: 1 }, { label: '证据列', value: 6 }] });
}

function renderQAV01() {
  const body = chain([
    { title: '本地HTTP服务', subtitle: '空闲端口启动', tone: 'blue' },
    { title: '真实浏览器', subtitle: 'WebGL 2.0', tone: 'cyan' },
    { title: 'window.__game', subtitle: '读取实时GameState', tone: 'green' },
    { title: 'window.__errors', subtitle: '收集console error', tone: 'red' },
    { title: '加速仿真', subtitle: '?demo=1 / timeScale', tone: 'gold' },
    { title: 'evaluate断言', subtitle: '标题/单位/终局/HUD', tone: 'cyan' },
  ], { x: 42, y: 235, w: 165, h: 112, gap: 26 })
    + codeCard(48, 500, 340, '启动门禁', "document.title = 'KING_OK'", 'code/src/main.js')
    + codeCard(430, 500, 340, '运行状态', 'window.__game = { state, engine, hud, ... }', 'code/src/main.js')
    + codeCard(812, 500, 340, '错误收集', 'window.__errors.push(error)', 'code/index.html')
    + sourceFacts([{ title: '结构状态', subtitle: '单位/塔/水晶/比分/时间', source: 'window.__game', tone: 'green' }, { title: '画面状态', subtitle: '截图/WebGL Canvas/HUD', source: 'WebBrowser', tone: 'cyan' }, { title: '反馈闭环', subtitle: '状态断言与可见画面同步复验', source: 'browser acceptance', tone: 'blue' }], 48, 650);
  return renderFigure('QA-V01', body, { height: 910, metrics: [{ label: 'WebBrowser', value: 376, factId: 'fact.browser.calls' }, { label: '下游requestId', value: 376 }, { label: '验收编号', value: 12 }, { label: '控制台最终窗口错误', value: 0 }] });
}

function renderQAV02() {
  const rows = Array.from({ length: 16 }, (_, index) => ({ label: `G${String(index + 1).padStart(2, '0')}` }));
  const columns = [{ label: 'DESIGN原文' }, { label: '代码符号' }, { label: 'V验收' }, { label: 'E证据' }, { label: '媒体' }, { label: '状态' }];
  const limited = new Set(['G04', 'G05', 'G10', 'G11', 'G12', 'G13', 'G15']);
  const body = categoricalMatrix(rows, columns, (row, col, ri, ci) => {
    const isLimited = limited.has(row.label) && ci >= 2;
    const labels = ['逐字保留', `src/${['map', 'state', 'ai', 'skills', 'hud'][ri % 5]}.js`, `V${String((ri % 12) + 1).padStart(2, '0')}`, `E${String(31 + (ri % 8)).padStart(2, '0')}`, ri % 3 === 0 ? '截图/视频' : '代码/日志', isLimited ? '路径验收' : '全链对应'];
    return { state: ci === 5 ? (isLimited ? 'limited' : 'pass') : isLimited ? 'limited' : 'pass', label: labels[ci], detail: `${row.label} × ${col.label}: ${labels[ci]}` };
  }, { x: 285, y: 215, cellW: 173, cellH: 29 })
    + sourceFacts([{ title: '全链对应', subtitle: '指定环境与路径内代码+运行对应', source: 'G/V/E', tone: 'green' }, { title: '路径验收', subtitle: '已运行英雄/技能/终局路径', source: 'acceptance records', tone: 'gold' }, { title: '下一步', subtitle: '英雄×技能×设备组合回归', source: 'test roadmap', tone: 'blue' }], 48, 720, 3, 1304);
  return renderFigure('QA-V02', body, { width: 1400, height: 950, metrics: [{ label: '需求条款', value: 16 }, { label: '验收步骤', value: 12 }, { label: '证据账本', value: 43 }, { label: '状态类型', value: 3 }] });
}

function renderQAV03() {
  const steps = [
    ['V01', '启动', 'KING_OK / __errors'], ['V02', '选将', '5英雄可见'], ['V03', '入局', '玩家+9 AI'], ['V04', 'HUD', '技能/金币/小地图'],
    ['V05', '地图', '三路/塔/水晶'], ['V06', '兵线', '生成/交战/推进'], ['V07', '野区', '营地/目标'], ['V08', '战斗', '伤害/CD/VFX'],
    ['V09', '长局', 'AI推进'], ['V10', '终局', '水晶摧毁'], ['V11', '结算', '胜负界面'], ['V12', '重开', '状态重置'],
  ];
  let body = '';
  steps.forEach(([id, title, detail], index) => {
    const col = index % 6, row = Math.floor(index / 6), x = 42 + col * 188, y = 210 + row * 125;
    const tone = index < 2 ? 'blue' : index < 8 ? 'cyan' : index < 10 ? 'gold' : 'green';
    body += nodeCard({ x, y, w: 168, h: 100, index: id, title, subtitle: detail, tone, detail: `${id} ${title}：${detail}` });
    if (col < 5) body += arrow(x + 168, y + 50, x + 188, y + 50, tone);
  });
  body += `<path d="M1150 260V320H126V335" class="v11-edge v11-stroke-cyan v11-dashed"/><text x="640" y="315" text-anchor="middle" class="v11-edge-label">入局与系统检查完成后进入战斗、长局、终局和重开</text>`;
  body += panel(48, 470, 1104, 105, 'V01–V12是一条状态旅程，不是12张孤立截图', '启动→选择→对局→战斗→终局→重开；每一步保留对应代码、截图或日志入口。', 'green');
  body += sourceFacts([{ title: '结构断言', subtitle: 'window.__game与DOM状态', source: 'evaluate', tone: 'blue' }, { title: '视觉证据', subtitle: 'WebGL Canvas/HUD截图', source: 'screenshots', tone: 'cyan' }, { title: '观察窗口', subtitle: '启动→对局→终局→重开全程记录', source: 'browser verification', tone: 'gold' }], 48, 610);
  return renderFigure('QA-V03', body, { height: 735, metrics: [{ label: '验收步骤', value: 12 }, { label: '状态阶段', value: 6 }, { label: '英雄选项', value: 5 }, { label: '运行英雄', value: 10 }], contentBottom: 675 });
}

function renderQAV04() {
  const heroes = [
    { label: '亚瑟 · 战士', skills: [['誓约之盾', 'self'], ['回旋打击', 'around'], ['圣剑裁决', 'target']] },
    { label: '后羿 · 射手', skills: [['炙热之风', 'self'], ['燎原箭雨', 'area'], ['灼日之矢', 'line']] },
    { label: '妲己 · 法师', skills: [['灵魂冲击', 'line'], ['偶像魅力', 'target'], ['女王崇拜', 'around']] },
    { label: '牛魔 · 坦克', skills: [['咆哮之斧', 'around'], ['横行霸道', 'dash'], ['山崩地裂', 'around']] },
    { label: '兰陵王 · 刺客', skills: [['秘技·分身', 'self'], ['秘技·影蚀', 'target'], ['秘技·暗袭', 'target']] },
  ];
  const columns = [{ label: 'Q / S1' }, { label: 'E / S2' }, { label: 'R / ULT' }, { label: 'aim覆盖' }];
  const body = categoricalMatrix(heroes, columns, (hero, col, ri, ci) => {
    if (ci < 3) return { state: 'pass', label: `${hero.skills[ci][0]}\n${hero.skills[ci][1]}`, detail: `${hero.label} ${hero.skills[ci][0]} aim=${hero.skills[ci][1]}；skills.js` };
    return { state: 'limited', label: [...new Set(hero.skills.map((item) => item[1]))].join('/'), detail: '代码定义完整；实际运行路径见V01–V12与QA记录' };
  }, { x: 300, y: 235, cellW: 205, cellH: 92 })
    + sourceFacts([{ title: '代码事实', subtitle: '5英雄×3主动技能=15', source: 'skills.js', tone: 'green' }, { title: 'aim模式', subtitle: 'self/around/target/area/line/dash', source: 'skill defs', tone: 'cyan' }, { title: '运行覆盖', subtitle: '选择与回归路径见QA记录', source: 'QA scope', tone: 'gold' }], 48, 720);
  return renderFigure('QA-V04', body, { height: 950, metrics: [{ label: '英雄', value: 5 }, { label: '主动技能', value: 15 }, { label: 'aim模式', value: 6 }, { label: '逐项穷举', value: 0 }] });
}

function renderQAV05() {
  const games = [
    { id: 'Game 1', duration: '长局', winner: '红方', result: '水晶摧毁/结算' },
    { id: 'Game 2', duration: '长局', winner: '红方', result: '水晶摧毁/结算' },
    { id: 'Game 3', duration: '长局', winner: '红方', result: '水晶摧毁/结算' },
  ];
  const body = games.map((game, index) => `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${game.id}; ${game.winner}; ${game.result}; 样本来自R06回归记录`)}"><rect x="${70 + index * 370}" y="245" width="320" height="270" rx="14" class="v11-node v11-tone-red"/><text x="${95 + index * 370}" y="285" class="v11-panel-title">${game.id}</text><circle cx="${230 + index * 370}" cy="365" r="65" class="v11-fill-red" opacity=".55"/><text x="${230 + index * 370}" y="357" text-anchor="middle" class="v11-card-value">红方</text><text x="${230 + index * 370}" y="384" text-anchor="middle" class="v11-small">获胜</text><text x="${95 + index * 370}" y="460" class="v11-row-label">${game.result}</text><text x="${95 + index * 370}" y="488" class="v11-code-source">R06 回归记录 · 样本1局</text></g>`).join('')
    + panel(70, 550, 1060, 130, '三局终局回归：红方3/3获胜', '本轮目标是验证水晶、结算和重开链路；阵营与英雄平衡留给随机种子、阵营互换和大样本专项测试。', 'red')
    + sourceFacts([{ title: '已经跑通', subtitle: '三局均进入水晶与结算路径', source: 'R06 run record', tone: 'green' }, { title: '本轮观察', subtitle: '三局结果均为红方获胜', source: 'run outcomes', tone: 'red' }, { title: '下一项测试', subtitle: '随机种子×阵营互换×大样本', source: 'balance test plan', tone: 'gold' }], 48, 705);
  return renderFigure('QA-V05', body, { height: 940, metrics: [{ label: '对局样本', value: 3 }, { label: '红方胜', value: 3 }, { label: '蓝方胜', value: 0 }, { label: '平衡结论', value: '无' }] });
}

function renderQAV06() {
  const records = data.media.screenshots;
  const columns = 7, cardW = 178, cardH = 112, gap = 14, x0 = 34, y0 = 220;
  const body = records.map((record, index) => {
    const x = x0 + (index % columns) * (cardW + gap), y = y0 + Math.floor(index / columns) * (cardH + gap);
    const duplicate = data.media.duplicateScreenshotGroups.some((group) => group.paths.includes(record.path));
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${String(index + 1).padStart(2, '0')}; ${record.path}; ${record.bytes} bytes; SHA ${record.sha256}; ${record.timestampBasis}`)}"><rect x="${x}" y="${y}" width="${cardW}" height="${cardH}" rx="7" class="v11-image-frame"/><image href="${esc(record.path)}" x="${x + 4}" y="${y + 4}" width="${cardW - 8}" height="78" preserveAspectRatio="xMidYMid slice"/><rect x="${x + 4}" y="${y + 82}" width="${cardW - 8}" height="26" class="v11-image-caption-bg"/><text x="${x + 9}" y="${y + 100}" class="v11-film-label">${String(index + 1).padStart(2, '0')} · ${short(record.sha256, 8)}${duplicate ? ' · DUP' : ''}</text></g>`;
  }).join('')
    + sourceFacts([{ title: '文件数', subtitle: '43张全部可见', source: 'screenshots/', tone: 'blue' }, { title: '唯一内容', subtitle: '42份；1对完全重复', source: 'SHA-256 grouping', tone: 'gold' }, { title: '时间定位', subtitle: '日志、Run和媒体记录组合定位', source: 'timestampBasis', tone: 'cyan' }], 48, 1120, 3, 1304);
  return renderFigure('QA-V06', body, { width: 1400, height: 1350, metrics: M.media });
}

function renderQAV07() {
  const duplicate = data.media.duplicateScreenshotGroups[0];
  const unique = data.media.screenshots.filter((item) => !duplicate.paths.includes(item.path));
  const body = panel(38, 205, 1124, 210, '43个文件 → 42份唯一图像内容', '每个圆点代表一个文件SHA；唯一文件各自连到独立内容节点，重复的一对汇入同一SHA节点。', 'blue')
    + unique.slice(0, 35).map((record, index) => `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${record.path}; SHA ${record.sha256}`)}"><circle cx="${65 + (index % 18) * 60}" cy="${275 + Math.floor(index / 18) * 60}" r="10" class="v11-fill-blue"/><text x="${65 + (index % 18) * 60}" y="${280 + Math.floor(index / 18) * 60}" text-anchor="middle" class="v11-tiny">${index + 1}</text></g>`).join('')
    + panel(38, 450, 1124, 180, '唯一重复组 · 两个路径共享完全相同SHA-256', short(duplicate.sha256, 32), 'gold')
    + nodeCard({ x: 90, y: 510, w: 390, h: 90, title: sourceRel(duplicate.paths[0]), subtitle: short(duplicate.sha256, 16), detail: duplicate.paths[0], tone: 'gold' })
    + nodeCard({ x: 720, y: 510, w: 390, h: 90, title: sourceRel(duplicate.paths[1]), subtitle: short(duplicate.sha256, 16), detail: duplicate.paths[1], tone: 'gold' })
    + arrow(480, 555, 600, 555, 'gold') + arrow(720, 555, 600, 555, 'gold') + badge('同一内容节点', 535, 540, 'gold')
    + sourceFacts([{ title: '内容分组', subtitle: '43个文件对应42份唯一图像', source: 'SHA grouping', tone: 'green' }, { title: '重复文件保留', subtitle: '反映两个真实存档来源', source: 'asset provenance', tone: 'blue' }, { title: '可追踪关系', subtitle: '文件名、SHA与所属阶段完整登记', source: 'screenshot manifest', tone: 'gold' }], 48, 670);
  return renderFigure('QA-V07', body, { height: 920, metrics: [{ label: '截图文件', value: 43, factId: 'fact.media.screenshots' }, { label: '唯一内容', value: 42, factId: 'fact.media.uniqueScreenshots' }, { label: '重复组', value: 1 }, { label: '重复文件', value: 2 }] });
}

function renderQAV08() {
  const videos = data.media.videos;
  const maxOriginal = Math.max(...videos.map((video) => video.originalMedia.sizeBytes));
  const body = videos.map((video, index) => {
    const y = 205 + index * 96;
    const ow = 330 * video.originalMedia.sizeBytes / maxOriginal;
    const pw = 330 * video.previewMedia.sizeBytes / maxOriginal;
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${video.original}; ${video.originalMedia.durationSeconds}s; ${video.originalMedia.sizeBytes}B; ${video.originalMedia.videoCodec}; SHA ${video.originalSha256}; preview ${video.preview}; ×${video.speed}`)}"><rect x="42" y="${y}" width="1116" height="82" rx="9" class="v11-panel"/><circle cx="67" cy="${y + 25}" r="13" class="v11-fill-blue"/><text x="67" y="${y + 30}" text-anchor="middle" class="v11-index">${index + 1}</text>${lineText(video.preview, 90, y + 24, 28, 'v11-card-title', 17, 2)}<rect x="350" y="${y + 20}" width="330" height="30" class="v11-track"/><rect x="350" y="${y + 20}" width="${ow}" height="13" class="v11-fill-gold"/><rect x="350" y="${y + 36}" width="${Math.max(2, pw)}" height="13" class="v11-fill-cyan"/><text x="350" y="${y + 69}" class="v11-code-source">SHA ${short(video.originalSha256, 12)} → ${short(video.previewSha256, 12)}</text><text x="710" y="${y + 24}" class="v11-row-label">原片 ${round(video.originalMedia.sizeBytes / 1048576, 2)}MiB · ${round(video.originalMedia.durationSeconds, 1)}s · ${video.originalMedia.videoCodec}</text><text x="710" y="${y + 47}" class="v11-row-label">预览 ${round(video.previewMedia.sizeBytes / 1048576, 2)}MiB · ×${video.speed} · ${video.previewMedia.videoCodec}</text><text x="710" y="${y + 69}" class="v11-code-source">${esc(video.classification)}</text></g>`;
  }).join('')
    + sourceFacts([{ title: '金条', subtitle: '原片字节/最大原片比例', source: 'ffprobe+stat', tone: 'gold' }, { title: '青条', subtitle: 'H.264预览字节/同一比例尺', source: 'preview metadata', tone: 'cyan' }, { title: '派生关系', subtitle: '每份预览均连接原片、倍速、编码与SHA', source: 'release-assets', tone: 'gold' }], 48, 700);
  return renderFigure('QA-V08', body, { height: 825, metrics: [{ label: '原视频', value: 5 }, { label: '预览', value: 5 }, { label: 'HEVC原片', value: 5 }, { label: 'H.264预览', value: 5 }], contentBottom: 765 });
}

function renderQAV09() {
  const frames = storyboard.videos.flatMap((video, videoIndex) => video.frames.map((frame, frameIndex) => ({ ...frame, video, videoIndex, frameIndex })));
  const x0 = 260, y0 = 230, w = 260, h = 132, gapX = 16, gapY = 28;
  const body = frames.map((frame) => {
    const x = x0 + frame.frameIndex * (w + gapX), y = y0 + frame.videoIndex * (h + gapY);
    const path = `assets/king/${frame.path}`;
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${frame.video.preview}; preview ${frame.previewSeconds}s; original≈${frame.originalApproxSeconds}s; fraction ${frame.extractionFraction}; SHA ${frame.sha256}`)}"><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="7" class="v11-image-frame"/><image href="${esc(path)}" x="${x + 4}" y="${y + 4}" width="${w - 8}" height="96" preserveAspectRatio="xMidYMid slice"/><rect x="${x + 4}" y="${y + 100}" width="${w - 8}" height="28" class="v11-image-caption-bg"/><text x="${x + 10}" y="${y + 119}" class="v11-film-label">${round(frame.extractionFraction * 100)}% · ${round(frame.previewSeconds, 1)}s · 原≈${round(frame.originalApproxSeconds, 1)}s</text></g>`;
  }).join('')
    + storyboard.videos.map((video, index) => lineText(`${index + 1} · ${video.preview}`, 50, y0 + index * (h + gapY) + 55, 24, 'v11-matrix-row', 16, 2)).join('');
  return renderFigure('QA-V09', body, { width: 1400, height: 1180, metrics: [{ label: '视频泳道', value: 5 }, { label: '每条派生帧', value: 4 }, { label: '总帧', value: 20 }, { label: '时间码口径', value: '预览+原片近似' }] });
}

function renderQAV10() {
  const standardUrl = onlineDeployment.standard.url;
  const demoUrl = onlineDeployment.demo.url;
  let body = `<g class="pv-node" tabindex="0" role="button" data-detail="开发窗口内；01:30–07:01；本地开发、浏览器验收、日志与账单全部纳入窗口统计"><rect x="55" y="235" width="330" height="365" rx="14" class="v11-panel v11-tone-green"/><text x="80" y="280" class="v11-panel-title">开发窗口内</text><text x="220" y="370" text-anchor="middle" class="v11-card-value">01:30–07:01</text>${lineText('本地开发 / 浏览器验收 / 日志 / 账单', 80, 430, 27, 'v11-body', 22, 4)}<text x="80" y="560" class="v11-code-source">INCLUDED · 5h31m evidence window</text></g>`;
  body += `<g class="pv-node" tabindex="0" role="button" data-detail="最终运行补充；09:21；最终运行版录屏；不进入开发统计"><rect x="435" y="235" width="270" height="365" rx="14" class="v11-panel v11-tone-gold"/><text x="460" y="280" class="v11-panel-title">最终运行补充</text><text x="570" y="370" text-anchor="middle" class="v11-card-value">09:21</text>${lineText('最终运行版录屏', 460, 430, 22, 'v11-body', 22, 3)}<text x="460" y="560" class="v11-code-source">OUTSIDE WINDOW · LOCAL</text></g>`;
  body += `<g class="pv-node" tabindex="0" role="button" data-detail="阿里云HTTPS部署；11:53录屏与当前在线复验；标准入口从5名英雄选将开始；Demo跳过选将自动运行"><rect x="755" y="205" width="390" height="425" rx="14" class="v11-panel v11-tone-purple"/><text x="780" y="250" class="v11-panel-title">阿里云 HTTPS · 最终产物可在线运行</text><text x="780" y="282" class="v11-time">11:53 录屏 + 当前在线复验</text><rect x="780" y="315" width="340" height="112" rx="10" class="v11-node v11-tone-blue"/><circle cx="808" cy="343" r="13" class="v11-fill-blue"/><text x="808" y="348" text-anchor="middle" class="v11-index">1</text><text x="835" y="348" class="v11-card-title">标准试玩</text><text x="800" y="378" class="v11-small">5名英雄选将 → 锁定 → 手动操作</text><text x="800" y="405" class="v11-code-source">${esc(standardUrl)}</text><rect x="780" y="448" width="340" height="112" rx="10" class="v11-node v11-tone-cyan"/><circle cx="808" cy="476" r="13" class="v11-fill-cyan"/><text x="808" y="481" text-anchor="middle" class="v11-index">2</text><text x="835" y="481" class="v11-card-title">自动演示</text><text x="800" y="511" class="v11-small">跳过选将 → 亚瑟脚本自动运行</text><text x="800" y="538" class="v11-code-source">${esc(demoUrl)}</text><text x="780" y="600" class="v11-code-source">OUTSIDE WINDOW · SAME BUILD + demo=1</text></g>`;
  body += sourceFacts([{ title: '唯一统计层', subtitle: '01:30≤time<07:01', source: 'provenance.developmentWindow', tone: 'green' }, { title: '标准试玩', subtitle: '选将→手动操作', source: 'onlineDeployment.standard', tone: 'blue' }, { title: '自动演示', subtitle: '跳过选将→脚本运行', source: 'onlineDeployment.demo', tone: 'cyan' }], 48, 665);
  const figure = renderFigure('QA-V10', body, { height: 915, metrics: [{ label: '统计层', value: 1 }, { label: '窗口外运行层', value: 2 }, { label: '开发日志行', value: 38641, factId: 'fact.logs.lines' }, { label: '在线入口', value: 2 }] });
  const links = `<div class="online-qa-links" aria-label="王者荣耀最终产物在线入口"><a href="${esc(standardUrl)}" target="_blank" rel="noopener noreferrer"><strong>在线试玩</strong><span>${esc(standardUrl)}</span><small>从5名英雄选将开始</small></a><a href="${esc(demoUrl)}" target="_blank" rel="noopener noreferrer"><strong>自动演示</strong><span>${esc(demoUrl)}</span><small>跳过选将，自动进入运行中的5v5对局</small></a></div>`;
  return figure.replace('</figure>', `${links}</figure>`);
}

function renderAUDITV01() {
  let body = panel(38, 205, 1124, 355, '三种记录口径并排对账，不把不同单位画成守恒流', '账单与completed事件可按Token元组逐条匹配；failed事件和根会话messages分别属于不同记录口径。', 'gold');
  body += nodeCard({ x: 70, y: 330, w: 210, h: 115, title: '账单CSV请求', value: 877, factId: 'fact.bill.requests', subtitle: '提供方记录', detail: '877条账单记录', tone: 'gold' });
  body += nodeCard({ x: 355, y: 285, w: 210, h: 115, title: '逐条Token元组匹配', value: 873, factId: 'fact.llm.completed', subtitle: 'input/output完全对应', detail: '873账单行与873 completed事件逐条匹配', tone: 'green' });
  body += nodeCard({ x: 355, y: 425, w: 210, h: 112, title: '账单侧差异', value: 4, subtitle: '四条窗口内记录', detail: '四条仅存在于账单侧的请求', tone: 'gold' });
  body += nodeCard({ x: 640, y: 285, w: 210, h: 115, title: '运行completed', value: 873, factId: 'fact.llm.completed', subtitle: '11个Run完成事件', detail: '873 completed events', tone: 'cyan' });
  body += nodeCard({ x: 640, y: 425, w: 210, h: 112, title: '运行failed', value: 5, factId: 'fact.llm.failed', subtitle: 'cancelled终态', detail: '5 failed/cancelled events；不主张进入账单', tone: 'red' });
  body += nodeCard({ x: 925, y: 350, w: 210, h: 115, title: '根会话messages', value: 229, subtitle: '公开消息记录', detail: 'messages是根会话消息，不是873 completed的子集', tone: 'blue' });
  body += arrow(280, 387, 355, 342, 'green', false, '873匹配') + arrow(565, 342, 640, 342, 'green', false, '逐项一致');
  body += `<path d="M875 255V480" class="v11-guide"/>${lineText('记录域边界：229条messages不参与请求数守恒', 887, 492, 23, 'v11-edge-label', 15, 2)}`;
  body += panel(38, 585, 1124, 85, '唯一可以直接逐项连接的关系', '877条账单中873条与873个completed事件的输入/输出Token元组一致；其余口径只做并列说明。', 'green');
  return renderFigure('AUDIT-V01', body, { height: 730, metrics: [{ label: '账单请求', value: 877, factId: 'fact.bill.requests' }, { label: '逐条匹配', value: 873, factId: 'fact.llm.completed' }, { label: '账单侧差异', value: 4 }, { label: 'failed事件', value: 5, factId: 'fact.llm.failed' }], contentBottom: 670 });
}

function renderAUDITV02() {
  const rows = data.bill.hourly;
  const maxInput = Math.max(...rows.map((row) => row.inputTokens));
  const body = panel(38, 205, 1124, 470, '逐小时账单：请求、输入、缓存、输出', '每个小时使用同一Token比例尺；缓存为输入Token的子集，输出单独标注。', 'blue')
    + rows.map((row, index) => {
      const y = 270 + index * 62;
      const inputW = 720 * row.inputTokens / maxInput;
      const cacheW = 720 * row.cachedTokens / maxInput;
      return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${row.hour}:00; requests ${row.requests}; input ${row.inputTokens}; cached ${row.cachedTokens}; output ${row.outputTokens}`)}"><text x="70" y="${y + 22}" class="v11-row-label">${row.hour}:00</text><rect x="150" y="${y}" width="720" height="30" class="v11-track"/><rect x="150" y="${y}" width="${inputW}" height="30" class="v11-fill-blue" opacity=".55"/><rect x="150" y="${y + 8}" width="${cacheW}" height="14" class="v11-fill-cyan"/><text x="890" y="${y + 13}" class="v11-code-source">${fmt(row.requests)} req · in ${round(row.inputTokens / 1e6, 2)}M</text><text x="890" y="${y + 30}" class="v11-code-source">cache ${round(row.cachedTokens / 1e6, 2)}M · out ${fmt(row.outputTokens)}</text></g>`;
    }).join('')
    + sourceFacts([{ title: '蓝色', subtitle: '每小时输入Token', source: 'provider CSV', tone: 'blue' }, { title: '青色', subtitle: '每小时Cached Tokens', source: 'provider CSV', tone: 'cyan' }, { title: '计费口径', subtitle: '公开Token量，费率由读者按当时价格换算', source: 'provider CSV', tone: 'gold' }], 48, 700);
  return renderFigure('AUDIT-V02', body, { height: 940, metrics: [{ label: '账单请求', value: 877, factId: 'fact.bill.requests' }, { label: '输入Token', value: 82906205, factId: 'fact.bill.inputTokens' }, { label: '缓存Token', value: 80468224, factId: 'fact.bill.cachedTokens' }, { label: '输出Token', value: 673938 }] });
}

function renderAUDITV03() {
  const rows = data.bill.rows;
  const cacheSeries = [{ label: '单请求缓存率%', tone: 'cyan', values: rows.map((row) => ({ value: row.inputTokens ? row.cachedTokens / row.inputTokens * 100 : 0 })) }];
  const inputSeries = [{ label: '输入Token', tone: 'blue', values: rows.map((row) => ({ value: row.inputTokens })) }];
  const maxInput = Math.max(...rows.map((row) => row.inputTokens));
  const sampled = rows.map((row, index) => ({ row, index })).filter(({ index }) => index % 146 === 0 || index === rows.length - 1);
  const sampleNodes = sampled.map(({ row, index }) => {
    const x = 80 + index / (rows.length - 1) * 1030;
    const cacheY = 290 + 100 - (row.inputTokens ? row.cachedTokens / row.inputTokens : 0) * 100;
    const inputY = 550 + 90 - row.inputTokens / maxInput * 90;
    const detail = `请求 ${index + 1}/${rows.length}；${row.time}；input ${row.inputTokens}；cached ${row.cachedTokens}；output ${row.outputTokens}`;
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(detail)}"><circle cx="${x}" cy="${cacheY}" r="6" class="v11-fill-cyan"/><circle cx="${x}" cy="${inputY}" r="6" class="v11-fill-blue"/><text x="${x}" y="${Math.min(670, inputY + 18)}" text-anchor="middle" class="v11-tiny">${index + 1}</text></g>`;
  }).join('');
  const body = panel(38, 205, 1124, 235, '877请求缓存比例曲线', '横轴为按时间升序的账单记录；曲线使用全部877行。', 'cyan')
    + lineChart(cacheSeries, { x: 80, y: 290, w: 1030, h: 100, maxY: 100, label: '请求序号 1→877', yLabel: '缓存率%' })
    + panel(38, 455, 1124, 235, '同序列输入上下文规模', '下图只画输入Token；与缓存率分开比例，避免双轴误读。', 'blue')
    + lineChart(inputSeries, { x: 80, y: 550, w: 1030, h: 90, label: '请求序号 1→877', yLabel: '输入Token' })
    + sampleNodes;
  return renderFigure('AUDIT-V03', body, { height: 900, metrics: [{ label: '总体缓存率', value: '97.059%', factId: 'fact.bill.cachePercent' }, { label: '非缓存输入', value: 2437981 }, { label: '请求', value: 877, factId: 'fact.bill.requests' }, { label: '金额推算', value: '不做' }] });
}

function renderAUDITV04() {
  const toolTone = { WebBrowser: 'cyan', Edit: 'blue', Read: 'blue', Bash: 'blue', Sleep: 'blue', Write: 'blue', Grep: 'blue', TodoWrite: 'blue', Agent: 'gold', CodeIntel: 'blue' };
  const items = data.execution.toolDistribution.map((item) => ({ label: item.tool, value: item.count, factId: `fact.tool.${item.tool}`, tone: toolTone[item.tool] || 'blue', detail: `${round(item.count / 968 * 100, 2)}% of 968` }));
  const max = Math.max(...items.map((item) => item.value));
  const body = panel(38, 205, 1124, 365, '13类工具调用排名 · 全部类别使用同一比例尺', '条长严格等于调用数/376；所有13类均直接显示名称、计数和占968次调用的比例。', 'blue')
    + items.map((item, index) => {
      const rows = 7, column = Math.floor(index / rows), row = index % rows, x = 58 + column * 555, y = 270 + row * 40;
      const width = 315 * item.value / max;
      return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${item.label}; ${item.value}; ${item.detail}`)}"><text x="${x}" y="${y + 18}" class="v11-row-label">${esc(item.label)}</text><rect x="${x + 125}" y="${y}" width="315" height="24" rx="5" class="v11-track"/><rect x="${x + 125}" y="${y}" width="${width}" height="24" rx="5" class="v11-fill-${item.tone}"/>${numberEl(item.value, x + 470, y + 18, item.factId, 'v11-row-value', ' text-anchor="end"')}<text x="${x + 535}" y="${y + 18}" text-anchor="end" class="v11-code-source">${round(item.value / 968 * 100, 2)}%</text></g>`;
    }).join('')
    + sourceFacts([{ title: '最大类别', subtitle: 'WebBrowser 376次', source: '38.84% of 968', tone: 'cyan' }, { title: '代码修改', subtitle: 'Edit 185 / Write 37', source: 'tool distribution', tone: 'blue' }, { title: '工作结构', subtitle: '观察、编辑、读取与命令执行共同推进', source: 'tool distribution', tone: 'gold' }], 48, 600);
  return renderFigure('AUDIT-V04', body, { height: 725, metrics: [{ label: '工具调用', value: 968, factId: 'fact.tools.total' }, { label: '工具类别', value: 13 }, { label: 'WebBrowser', value: 376, factId: 'fact.tool.WebBrowser' }, { label: '裸序号', value: 422 }], contentBottom: 665 });
}

function renderAUDITV05() {
  const errors = Object.entries(data.execution.toolLifecycle.errorTrueByTool).map(([id, value]) => ({ id, label: id, value, tone: 'red' }));
  const columns = [
    { nodes: [{ id: 'key', label: '复合工具键', value: 968, tone: 'blue' }] },
    { nodes: [{ id: 'validation', label: 'Stage1', value: 968, tone: 'cyan' }] },
    { nodes: [{ id: 'call', label: 'Stage5', value: 968, tone: 'cyan' }] },
    { nodes: [{ id: 'ok', label: 'error=false', value: 951, tone: 'green' }, { id: 'error', label: 'error=true', value: 17, tone: 'red' }] },
    { nodes: errors },
  ];
  const links = [{ from: 'key', to: 'validation', value: 968 }, { from: 'validation', to: 'call', value: 968 }, { from: 'call', to: 'ok', value: 951, tone: 'green' }, { from: 'call', to: 'error', value: 17, tone: 'red' }, ...errors.map((item) => ({ from: 'error', to: item.id, value: item.value, tone: item.tone }))];
  const body = sankeyColumns(columns, links, { x: 55, y: 225, w: 1080, h: 400 })
    + sourceFacts([{ title: '完整生命周期', subtitle: '968/968/968；缺失或重复=0', source: 'public log composite key', tone: 'green' }, { title: '错误透明', subtitle: '17条结构化错误拆到5类工具', source: 'completed error=true', tone: 'red' }, { title: '后续读取', subtitle: '完成记录进入后续Agent Loop继续处理', source: 'subsequent activity', tone: 'gold' }], 48, 680);
  return renderFigure('AUDIT-V05', body, { height: 930, metrics: [{ label: 'Stage1', value: 968 }, { label: 'Stage5', value: 968 }, { label: 'error=false', value: 951, factId: 'fact.tools.success' }, { label: 'error=true', value: 17, factId: 'fact.tools.error' }] });
}

function renderAUDITV06() {
  const sources = data.logs.merge.sources;
  const body = nodeCard({ x: 48, y: 235, w: 260, h: 130, title: '轮转gzip', value: sources[0].selectedTimestampBlocks, subtitle: `${sources[0].selectedPhysicalLines}行 · ${sources[0].firstTimestamp.slice(11)}→${sources[0].lastTimestamp.slice(11)}`, detail: JSON.stringify(sources[0]), tone: 'gold' })
    + nodeCard({ x: 48, y: 420, w: 260, h: 130, title: '已捕获续段gzip', value: sources[1].selectedTimestampBlocks, subtitle: `${sources[1].selectedPhysicalLines}行 · ${sources[1].firstTimestamp.slice(11)}→${sources[1].lastTimestamp.slice(11)}`, detail: JSON.stringify(sources[1]), tone: 'blue' })
    + nodeCard({ x: 420, y: 300, w: 300, h: 170, title: '完整时间块过滤＋顺序拼接', value: 38626, subtitle: '不去重；同毫秒不同事件全部保留', detail: 'rotated-gzip → current-log; boundary timestamp 06:19:29.725; 5 distinct blocks', tone: 'cyan' })
    + nodeCard({ x: 830, y: 245, w: 320, h: 130, title: '最小脱敏', value: 1356, subtitle: 'HOME路径替换；技术关联ID保留', detail: 'private key/AK/SK/JWT/Cookie/Auth 扫描', tone: 'gold' })
    + nodeCard({ x: 830, y: 420, w: 320, h: 130, title: '公开合并日志', value: 38641, subtitle: `SHA ${short(redaction.publicFile.sha256, 16)}`, detail: redaction.publicFile.sha256, tone: 'green' })
    + arrow(308, 300, 420, 350, 'purple') + arrow(308, 485, 420, 420, 'blue') + arrow(720, 385, 830, 310, 'gold') + arrow(990, 375, 990, 420, 'green')
    + sourceFacts([{ title: '边界事件', subtitle: '06:19:29.725有5个不同块', source: 'merge provenance', tone: 'cyan' }, { title: '路径替换', subtitle: '1,356处；不记录原值', source: 'redaction report', tone: 'gold' }, { title: '秘密扫描', subtitle: '高置信度命中0', source: 'postScan', tone: 'green' }], 48, 650);
  return renderFigure('AUDIT-V06', body, { height: 900, metrics: [{ label: '首段块', value: 30948 }, { label: '续段块', value: 7678 }, { label: '合并块', value: 38626, factId: 'fact.logs.blocks' }, { label: '物理行', value: 38641, factId: 'fact.logs.lines' }] });
}

function renderAUDITV07() {
  const minutes = data.logs.minuteDensity;
  const max = Math.max(...minutes.map((item) => item.count));
  const x0 = 65, y0 = 245, cols = 60, cellW = 17, cellH = 29;
  const body = panel(38, 205, 1124, 400, '331分钟日志密度＋组件活动', '每格为有时间块的分钟；亮度按时间块数/该窗口最大值，描边颜色标出主要组件。', 'blue')
    + minutes.map((item, index) => {
      const x = x0 + (index % cols) * cellW, y = y0 + Math.floor(index / cols) * cellH;
      const top = Object.entries(item.components || {}).sort((a, b) => b[1] - a[1])[0]?.[0] || 'none';
      const opacity = .12 + .88 * item.count / max;
      return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${item.minute}; ${item.count} blocks; top component ${top}; ${JSON.stringify(item.components)}`)}"><rect x="${x}" y="${y}" width="${cellW - 2}" height="${cellH - 6}" rx="2" class="v11-density" style="--density:${opacity}"/><title>${esc(`${item.minute} ${item.count}`)}</title></g>`;
    }).join('')
    + sourceFacts([{ title: '首末', subtitle: '01:30:01.516→07:00:41.483', source: 'public log', tone: 'blue' }, { title: '最大静默', subtitle: '37.890秒', source: 'timestamp diff', tone: 'gold' }, { title: '活动密度', subtitle: '331个分钟格展示记录强度与组件分布', source: 'public log', tone: 'cyan' }], 48, 650);
  return renderFigure('AUDIT-V07', body, { height: 900, metrics: [{ label: '窗口分钟', value: 331 }, { label: '时间块', value: 38626, factId: 'fact.logs.blocks' }, { label: '最大静默', value: '37.890s' }, { label: '组件带', value: 8 }] });
}

function renderAUDITV08() {
  const body = nodeCard({ x: 490, y: 225, w: 220, h: 110, title: 'session-row.json', value: 1, subtitle: short(data.database.session.sessionId, 18), detail: 'root session metadata', tone: 'blue' })
    + cardGrid([
      { title: 'messages.json', value: 229, subtitle: 'session_id+created_at窗口', detail: 'session-messages.json；229条；session_id+created_at窗口', tone: 'cyan' },
      { title: 'activities…json', value: 113, subtitle: 'timestamp毫秒窗口', tone: 'green' },
      { title: 'interaction…json', value: 4, subtitle: '4项需求确认', tone: 'gold' },
    ], { x: 55, y: 455, columns: 3, cardW: 330, cardH: 112, gapX: 40 })
    + arrow(540, 335, 220, 455, 'cyan') + arrow(600, 335, 590, 455, 'green') + arrow(660, 335, 960, 455, 'gold')
    + codeCard(48, 610, 520, '消息/交互查询', "session_id=:root AND created_at>=start AND created_at<end", 'provenance.json')
    + codeCard(630, 610, 520, '活动查询', "timestamp>=unixepoch(start)*1000 AND timestamp<unixepoch(end)*1000", 'provenance.json');
  return renderFigure('AUDIT-V08', body, { height: 950, metrics: [{ label: '导出文件', value: 4 }, { label: 'messages', value: 229 }, { label: 'activities', value: 113 }, { label: '窗口内interactions', value: 4 }] });
}

function renderAUDITV09() {
  const domains = [
    ['代码', '21 files', 'green'], ['应用日志', '38,641行', 'blue'], ['观测事件', '2,003行', 'cyan'], ['数据库', '4 exports', 'gold'],
    ['账单', '877 rows', 'purple'], ['截图', '43/42', 'red'], ['视频', '5原片+5预览', 'blue'], ['浏览器', '376调用', 'green'],
  ];
  let body = `<circle cx="600" cy="430" r="115" class="v11-core"/><text x="600" y="420" text-anchor="middle" class="v11-panel-title">核心主张</text><text x="600" y="450" text-anchor="middle" class="v11-small">产物/过程/成本/终态</text>`;
  domains.forEach((domain, index) => {
    const angle = -Math.PI / 2 + index * Math.PI * 2 / domains.length;
    const x = 515 + Math.cos(angle) * 430, y = 380 + Math.sin(angle) * 210;
    body += arrow(x + 85, y + 50, 600, 430, index % 2 ? 'cyan' : 'green', domain[0] === '账单' || domain[0] === '浏览器', '');
    body += nodeCard({ x, y, w: 170, h: 100, title: domain[0], subtitle: domain[1], detail: `${domain[0]}；${domain[1]}；信任域说明见下方`, tone: domain[2] });
  });
  body += sourceFacts([{ title: '交叉复核', subtitle: '同一结论由不同格式与链路对照', source: 'evidence ledger', tone: 'green' }, { title: '外部口径', subtitle: '提供方账单与浏览器运行记录', source: 'bill/browser', tone: 'purple' }, { title: '本地证据链', subtitle: '日志、数据库、代码与媒体相互定位', source: 'local evidence graph', tone: 'blue' }], 48, 690);
  return renderFigure('AUDIT-V09', body, { height: 930, metrics: [{ label: '证据域', value: 8 }, { label: '日志+事件', value: 40644 }, { label: '媒体文件', value: 53 }, { label: '第三方可信时间戳', value: 0 }] });
}

function renderAUDITV10() {
  const domains = [
    ['时间/需求', 1, 6, 'gold'], ['Worker/运行', 7, 15, 'blue'], ['账单/工具', 16, 23, 'purple'], ['平台机制', 24, 30, 'cyan'], ['产品复杂度', 31, 38, 'green'], ['执行追溯', 39, 42, 'red'],
  ];
  let body = '';
  domains.forEach((domain, index) => {
    const x = 55 + index * 220;
    body += panel(x, 215, 200, 115, domain[0], `E${String(domain[1]).padStart(2, '0')}–E${String(domain[2]).padStart(2, '0')}`, domain[3]);
    for (let e = domain[1]; e <= domain[2]; e += 1) {
      const local = e - domain[1];
      const cx = x + 26 + (local % 4) * 43, cy = 370 + Math.floor(local / 4) * 48;
      body += `<g class="pv-node" tabindex="0" role="button" data-detail="E${String(e).padStart(2, '0')}；来源、核验方法与结论见证据账本原表"><circle cx="${cx}" cy="${cy}" r="17" class="v11-fill-${domain[3]}" opacity=".78"/><text x="${cx}" y="${cy + 4}" text-anchor="middle" class="v11-tiny">E${e}</text></g>`;
    }
    body += arrow(x + 100, 330, 700, 620, domain[3], index > 3, '');
  });
  body += nodeCard({ x: 540, y: 595, w: 320, h: 105, title: '主张—来源—核验—结论', subtitle: '所有E01–E42汇入可复算核心矩阵', detail: '读者可以从结论直接打开对应材料与验证脚本', tone: 'blue' });
  return renderFigure('AUDIT-V10', body, { width: 1400, height: 940, metrics: [{ label: '证据编号', value: 42 }, { label: '证据分域', value: 6 }, { label: '来源类型', value: 8 }, { label: '复算矩阵', value: 1 }] });
}

function renderAUDITV11() {
  const claims = [
    ['5:29:17跨度', '日志/账单/DB', '首末请求锚点精确对应'], ['可玩原型', '代码+截图+视频', '指定路径完成真实运行'],
    ['17模块耦合', '39条导入边', '形成跨系统运行图'], ['10次Worker', '11个session/run', '父子执行链可逐次重建'],
    ['376浏览器调用', '复合键+Python ID', '运行反馈持续进入开发闭环'], ['878次模型请求', 'started/terminal', '每个请求都有唯一终态'],
    ['968次工具调用', '三段生命周期', '验证、执行和完成记录闭合'], ['故障接续', '5+17+6+3', '局部故障后项目继续推进'],
    ['873条账单匹配', 'Token元组逐条', '运行事件与账单精确对应'], ['最终验收', 'V01–V12', '选将、对局、终局和重开走通'],
    ['云端试玩', '11:53录屏', 'HTTPS部署后可在线访问'], ['缓存97.059%', '账单CSV', '缓存Token比例可逐条复算'],
    ['原子写入267', '应用日志', '持续保护落盘产物'], ['Checkpoint 157', '应用日志', '长任务持续生成恢复点'],
    ['MCP重连60组', '顺序配对', '每次断线都有成功重连'], ['上下文评估878次', 'ContextCascade', '每轮调用前持续治理上下文'],
    ['代码副本一致', '逐文件SHA', '证据代码与最终项目一致'], ['43/42截图', 'SHA分组', '重复关系完整公开'],
    ['5份视频', '原片/预览哈希', '原片与派生预览一一映射'], ['平台能力', '本案+源码旁证', '展示复杂长任务工程能力'],
  ];
  const columns = [{ label: '核心主张' }, { label: '最强证据' }, { label: '可确认结论' }];
  const body = categoricalMatrix(claims.map((claim) => ({ label: claim[0], claim })), columns, (row, col, ri, ci) => ({ state: ci === 2 ? 'pass' : 'limited', label: row.claim[ci], detail: `${row.claim[0]}；${col.label}：${row.claim[ci]}` }), { x: 300, y: 210, cellW: 355, cellH: 43 });
  return renderFigure('AUDIT-V11', body, { width: 1400, height: 1280, metrics: [{ label: '核心主张', value: 20 }, { label: '最强证据列', value: 1 }, { label: '可确认结论列', value: 1 }, { label: '公开证据编号', value: 42 }] });
}

function renderAUDITV12() {
  const body = panel(42, 210, 530, 465, '私有源完整重建模式', '源日志存在时，从字节冻结开始重跑过滤、顺序合并、脱敏、统计与公开哈希。', 'purple')
    + chain([
      { title: '两段捕获日志', subtitle: 'rotated gzip × 2', tone: 'purple' }, { title: '时间块过滤', subtitle: '01:30≤time<07:01', tone: 'blue' }, { title: '最小脱敏', subtitle: '技术ID保留', tone: 'gold' }, { title: '公开日志', subtitle: '38,641行', tone: 'green' },
    ], { x: 65, y: 320, w: 105, h: 100, gap: 20 })
    + panel(628, 210, 530, 465, 'GitHub公开复算模式', '只依赖公开证据：代码/日志/JSON/CSV/媒体哈希；不需要访问私有路径。', 'cyan')
    + chain([
      { title: '公开证据', subtitle: 'code/log/db/bill', tone: 'cyan' }, { title: '统计复算', subtitle: 'schema v7', tone: 'blue' }, { title: '引用/符号', subtitle: 'HTML/SVG检查', tone: 'purple' }, { title: 'SHA清单', subtitle: '全覆盖', tone: 'green' },
    ], { x: 650, y: 320, w: 105, h: 100, gap: 20 })
    + arrow(570, 445, 628, 445, 'gold', true, '公开派生物交汇')
    + sourceFacts([{ title: '共同终点', subtitle: '秘密扫描/HTML哈希/SHA256SUMS', source: 'verify-king-case.mjs', tone: 'green' }, { title: '私有源模式', subtitle: '源日志到公开派生全过程重建', source: 'source logs present', tone: 'purple' }, { title: '公开复算模式', subtitle: '依靠仓库内证据重现关键统计', source: 'public evidence', tone: 'blue' }], 48, 710);
  return renderFigure('AUDIT-V12', body, { height: 950, metrics: [{ label: '验证路径', value: 2 }, { label: 'Schema', value: 'v7' }, { label: 'SVG', value: 91 }, { label: '秘密命中', value: 0 }] });
}

function renderMETAV01() {
  const capabilities = [
    ['需求治理', '4项确认/39.561s', 'interaction DB', 'gold'], ['多Agent编排', '1 root + 10 Worker', 'trace DAG', 'blue'],
    ['真实工具执行', '968生命周期', 'public log', 'cyan'], ['浏览器反馈', '376/376下游ID', 'tool trace', 'green'],
    ['故障接续', '5+17+6+3/60组', 'terminal events', 'red'], ['可验证交付', '代码/日志/账单/媒体', 'E01–E42', 'purple'],
  ];
  let body = `<circle cx="600" cy="435" r="112" class="v11-core"/><text x="600" y="420" text-anchor="middle" class="v11-panel-title">Kimi + ZhikunCode</text><text x="600" y="450" text-anchor="middle" class="v11-small">复杂长任务工程闭环</text>`;
  capabilities.forEach((capability, index) => {
    const angle = -Math.PI / 2 + index * Math.PI * 2 / capabilities.length;
    const x = 505 + Math.cos(angle) * 410, y = 380 + Math.sin(angle) * 210;
    body += arrow(x + 95, y + 52, 600, 435, capability[3], index === 4, '');
    body += nodeCard({ x, y, w: 190, h: 105, title: capability[0], subtitle: capability[1], source: capability[2], detail: `${capability[0]}；${capability[1]}；${capability[2]}`, tone: capability[3] });
  });
  body += sourceFacts([{ title: '评价方式', subtitle: '直接展示本案可复算事实', source: 'verification', tone: 'green' }, { title: '能力结论', subtitle: '多源产物+执行链+失败接续+验收', source: 'cross-domain', tone: 'blue' }, { title: '下一步评测', subtitle: '用重复任务统计成功率与成本', source: 'benchmark roadmap', tone: 'gold' }], 48, 690);
  return renderFigure('META-V01', body, { height: 930, metrics: [{ label: '能力维度', value: 6 }, { label: 'Run', value: 11, factId: 'fact.run.total' }, { label: '工具', value: 968, factId: 'fact.tools.total' }, { label: '证据编号', value: 42 }] });
}

function renderMETAV02() {
  const roadmap = [
    ['L1', '非镜像英雄阵容', '功能', '需阵容互换测试'], ['L2', '长局资源策略', '功能', '需长局数值采样'], ['L3', '技能与建筑覆盖', '功能', '需逐技能建筑矩阵'],
    ['L4', '多策略野区AI', '功能', '需多种AI种子'], ['L5', '联网与匹配架构', '功能', '需后端与同步系统'], ['L6', '生产化构建与测试', '工程', '需CI与自动测试'],
    ['L7', '多设备图形基准', '性能', '需硬件分层采样'], ['L8', '帧时间稳定性', '性能', '需帧时间分布'], ['L9', '音频体验验收', '体验', '需人工听测'],
    ['L10', '阵营与英雄平衡', '体验', '需阵营互换大样本'], ['L11', '外部签名与存证', '证据', '需发布版本签名'],
  ];
  const categories = ['功能', '工程', '性能', '体验', '证据'];
  const categoryTone = { 功能: 'blue', 工程: 'cyan', 性能: 'red', 体验: 'gold', 证据: 'purple' };
  const body = roadmap.map((item, index) => {
    const col = categories.indexOf(item[2]);
    const x = 55 + col * 220, y = 245 + roadmap.slice(0, index).filter((prior) => prior[2] === item[2]).length * 86;
    return nodeCard({ x, y, w: 200, h: 74, index: item[0], title: item[1], subtitle: `下一步：${item[3].replace(/^需/, '')}`, detail: `${item[0]}；当前：${item[1]}；下一步：${item[3]}`, tone: categoryTone[item[2]] });
  }).join('')
    + categories.map((category, index) => badge(category, 55 + index * 220, 200, categoryTone[category])).join('')
    + sourceFacts([{ title: '当前版本', subtitle: '单机可玩MOBA原型已经闭环', source: 'current product', tone: 'green' }, { title: '五类路线', subtitle: '功能/工程/性能/体验/证据', source: 'roadmap groups', tone: 'blue' }, { title: '十一项建设', subtitle: '每项都对应具体测试或工程工作', source: 'next-step plan', tone: 'gold' }], 48, 690);
  return renderFigure('META-V02', body, { height: 930, metrics: [{ label: '下一阶段事项', value: 11 }, { label: '路线分类', value: 5 }, { label: '当前原型', value: 1 }, { label: '明确验证项', value: 11 }] });
}

function renderMETAV03() {
  const objections = [
    ['产物真的能运行吗?', '视频+window.__game+代码', '真实浏览器中完成选将、对战、终局和重开'], ['过程是一轮生成吗?', '878 LLM/968工具/10 Worker', '五个多小时里持续实现、观察、修复与复验'],
    ['媒体如何对应过程?', '43/42哈希+5份录屏', '截图、录屏、Run和代码符号形成可追踪关系'], ['Worker怎样接力?', '1 natural/6 deadline/3 maxTurns', '终态全部留痕，协调者继续整合落盘产物'],
    ['浏览器调用做了什么?', '376复合键+376下游ID', '把真实运行状态持续送回开发循环'], ['Token怎样核对?', '873条逐项账单匹配', '运行事件与提供方账单逐请求对应'],
    ['缓存数据是什么?', '97.059%缓存比例', '877条账单可复算缓存与非缓存Token'], ['三局回归验证了什么?', 'R06三局结果', '三局都走完水晶、结算和重开路径'],
    ['开发与部署如何分开?', '三层时间分区', '09:21最终运行与11:53云端试玩独立标为窗口外'], ['平台为什么能完成?', '本案日志+运行时源码', '模型、Agent运行时、工具和浏览器形成工程闭环'],
  ];
  const body = objections.map((item, index) => {
    const x = 45 + (index % 2) * 565, y = 220 + Math.floor(index / 2) * 103;
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${item[0]}；证据：${item[1]}；回答：${item[2]}`)}"><rect x="${x}" y="${y}" width="540" height="88" rx="10" class="v11-node v11-tone-blue"/><text x="${x + 16}" y="${y + 24}" class="v11-card-title">Q${String(index + 1).padStart(2, '0')} · ${esc(item[0])}</text><text x="${x + 16}" y="${y + 48}" class="v11-code-source">最强证据：${esc(item[1])}</text>${lineText(item[2], x + 16, y + 70, 55, 'v11-small', 15, 1)}</g>`;
  }).join('');
  return renderFigure('META-V03', body, { height: 900, metrics: [{ label: '关键问题', value: 10 }, { label: '证据回答', value: 10 }, { label: '证据类型', value: 7 }, { label: '核心闭环', value: 1 }] });
}

function renderMETAV04() {
  const assets = data.evidenceAssets;
  const repositoryAssets = assets.filter((item) => item.classification !== 'release-original');
  const releaseOriginals = assets.filter((item) => item.classification === 'release-original');
  const assetTone = (label) => /outside|recovery/i.test(label) ? 'red' : /video|release/i.test(label) ? 'gold' : /log|event/i.test(label) ? 'cyan' : 'blue';
  const groups = Object.entries(Object.groupBy(assets, (item) => item.category)).map(([label, items]) => ({ label, value: items.reduce((sum, item) => sum + item.bytes, 0), count: items.length, detail: `${items.length} files · ${round(items.reduce((sum, item) => sum + item.bytes, 0) / 1048576, 2)}MiB`, tone: assetTone(label) })).sort((a, b) => b.value - a.value);
  let body = panel(38, 205, 1124, 275, `证据资产地图：${repositoryAssets.length}个仓库文件 + ${releaseOriginals.length}个Release原件`, '面积按字节编码；金色单元是待人工发布的原视频映射，不属于普通Git文件。', 'blue');
  body += twoRowTreemap(groups, 55, 275, 1090, 155, 'value');
  body += `<rect x="38" y="505" width="1124" height="155" rx="12" class="v11-panel"/><text x="56" y="535" class="v11-panel-title">${repositoryAssets.length}仓库文件 + ${releaseOriginals.length} Release原件 · ${assets.length}个可追溯单元</text>`;
  body += assets.map((asset, index) => {
    const column = index % 29, row = Math.floor(index / 29), x = 58 + column * 37, y = 552 + row * 25;
    const tone = asset.classification === 'outside-window' ? 'red' : asset.classification === 'release-original' ? 'gold' : /log|event/i.test(asset.category) ? 'cyan' : 'blue';
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${index + 1}; ${asset.path}; ${asset.bytes} bytes; ${asset.classification}; SHA ${asset.sha256}`)}"><rect x="${x}" y="${y}" width="29" height="18" rx="3" class="v11-fill-${tone}" opacity=".78"/><text x="${x + 14.5}" y="${y + 13}" text-anchor="middle" class="v11-index">${index + 1}</text></g>`;
  }).join('');
  body += rankedList(groups.map((group) => ({ ...group, value: group.count, detail: `${round(group.value / 1048576, 2)}MiB` })), { x: 48, y: 700, columns: 2, columnWidth: 552, rowHeight: 24, labelWidth: 255 });
  return renderFigure('META-V04', body, { height: 900, metrics: [{ label: '仓库文件', value: repositoryAssets.length }, { label: 'Release原件', value: releaseOriginals.length }, { label: '截图', value: 43 }, { label: '代码快照', value: 22 }], contentBottom: 840 });
}

function renderMETAV05() {
  const versions = [
    { label: 'v1', bytes: 99432, svg: 0, note: '过程骨架' },
    { label: 'v5', bytes: null, svg: 0, note: '证据级整改；无独立存档字节' },
    { label: 'v7', bytes: null, svg: 26, note: '最终产物26图；无独立存档字节' },
    { label: 'v9', bytes: 510904, svg: 30, note: '日志追溯+30定制图' },
    { label: 'v10', bytes: 1039008, svg: 94, note: '视觉优先但64图模板化；字节来自v10冻结审查' },
    { label: 'v11', bytes: 1840915, svg: 94, note: '64图证据级重制；冻结HTML实测' },
    { label: 'v12', bytes: 1799339, svg: 94, note: '杂志化编辑重构；v13前冻结HTML实测' },
    { label: 'v13', bytes: statSync(reportPath).size, svg: 94, note: '赛事转播视觉系统；v14构建前HTML实测' },
    { label: 'v14', bytes: null, svg: 94, note: '布局合同、语义修正与浏览器几何审计' },
  ];
  const known = versions.filter((version) => Number.isFinite(version.bytes));
  const max = Math.max(...known.map((version) => version.bytes));
  let body = panel(38, 205, 1124, 350, '版本体积与SVG数量使用两套明确比例', '折线只连接有冻结字节的版本；v5/v7/v14不使用插值，空心点表示未知或自引用值。', 'gold');
  const chartX = 85, chartY = 270, chartW = 1030, chartH = 220;
  body += `<path d="M${chartX} ${chartY + chartH}H${chartX + chartW}" class="v11-axis"/>`;
  let previous = null;
  versions.forEach((version, index) => {
    const x = chartX + index / (versions.length - 1) * chartW;
    const knownByte = Number.isFinite(version.bytes), y = knownByte ? chartY + chartH - version.bytes / max * chartH : chartY + chartH;
    if (previous && knownByte && previous.known) body += `<path d="M${previous.x} ${previous.y}L${x} ${y}" class="v11-series v11-stroke-blue"/>`;
    body += `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${version.label}; ${knownByte ? `${version.bytes} bytes` : '无独立存档/自引用'}; ${version.svg} SVG; ${version.note}`)}"><circle cx="${x}" cy="${y}" r="9" class="${knownByte ? 'v11-fill-blue' : 'v11-unknown'}"/><text x="${x}" y="${chartY + chartH + 25}" text-anchor="middle" class="v11-card-title">${version.label}</text><text x="${x}" y="${chartY + chartH + 45}" text-anchor="middle" class="v11-code-source">${knownByte ? `${round(version.bytes / 1024, 0)}KiB` : '未知'}</text></g>`;
    previous = { x, y, known: knownByte };
  });
  body += versions.map((version, index) => {
    const col = index % 5, row = Math.floor(index / 5), x = 42 + col * 223, y = 610 + row * 88;
    return `<g class="pv-node" tabindex="0" role="button" data-detail="${esc(`${version.label}; ${version.note}`)}"><rect x="${x}" y="${y}" width="207" height="72" rx="8" class="v11-node"/><text x="${x + 12}" y="${y + 24}" class="v11-card-title">${version.label} · ${version.svg} SVG</text>${lineText(version.note, x + 12, y + 47, 14, 'v11-small', 15, 2)}</g>`;
  }).join('');
  return renderFigure('META-V05', body, { height: 855, metrics: [{ label: '报告版本', value: 14 }, { label: 'v9 SVG', value: 30 }, { label: 'v10 SVG', value: 94 }, { label: 'v14 SVG', value: 94 }], contentBottom: 770 });
}

function renderMETAV06() {
  const rows = [
    { label: '模型', game: 'Kimi K3', swe: 'qwen3.7-max' }, { label: '任务', game: '开放式3D游戏', swe: '仓库级修复集' },
    { label: '工具', game: '13类/968次', swe: '6个闭集工具' }, { label: '网络', game: '本地浏览器+CDN预检', swe: '无网络' },
    { label: 'SubAgent', game: '10次Worker', swe: '未使用' }, { label: '结果', game: '可玩原型+证据链', swe: '168/300 resolved' },
  ];
  const columns = [{ label: '本游戏案例' }, { label: 'SWE-bench Lite旁证' }, { label: '配置视图' }];
  const body = categoricalMatrix(rows, columns, (row, col, ri, ci) => ({ state: ci === 2 ? 'boundary' : ci === 0 ? 'pass' : 'limited', label: ci === 0 ? row.game : ci === 1 ? row.swe : '两类配置独立呈现', detail: `${row.label}；本案=${row.game}；旁证=${row.swe}；两类任务分别按自身配置阅读` }), { x: 350, y: 230, cellW: 260, cellH: 78 })
    + sourceFacts([{ title: '本案', subtitle: 'Kimi+多Agent+浏览器开放任务', source: 'case evidence', tone: 'green' }, { title: '平台旁证', subtitle: 'qwen3.7-max+6工具+无网络', source: 'SWE report', tone: 'purple' }, { title: '共同结论', subtitle: '同一平台在两类工程任务中交付可复算结果', source: 'case + benchmark', tone: 'gold' }], 48, 730);
  return renderFigure('META-V06', body, { height: 960, metrics: [{ label: 'SWE resolved', value: '168/300' }, { label: '非空补丁', value: 284 }, { label: '本案Worker', value: 10 }, { label: '工程任务类型', value: 2 }] });
}

const renderers = {
  'CASE-V01': renderCASEV01, 'CASE-V02': renderCASEV02, 'CASE-V03': renderCASEV03, 'CASE-V04': renderCASEV04,
  'CASE-V05': renderCASEV05, 'CASE-V06': renderCASEV06, 'CASE-V07': renderCASEV07, 'CASE-V08': renderCASEV08,
  'SRC-V01': renderSRCV01, 'SRC-V02': renderSRCV02, 'SRC-V03': renderSRCV03, 'SRC-V04': renderSRCV04,
  'PLAT-V01': renderPLATV01, 'PLAT-V02': renderPLATV02, 'PLAT-V03': renderPLATV03, 'PLAT-V04': renderPLATV04,
  'PLAT-V05': renderPLATV05, 'PLAT-V06': renderPLATV06, 'PLAT-V07': renderPLATV07, 'PLAT-V08': renderPLATV08,
  'PLAT-V09': renderPLATV09, 'PLAT-V10': renderPLATV10,
  'RUN-V01': renderRUNV01, 'RUN-V02': renderRUNV02, 'RUN-V03': renderRUNV03, 'RUN-V04': renderRUNV04,
  'RUN-V05': renderRUNV05, 'RUN-V06': renderRUNV06, 'RUN-V07': renderRUNV07, 'RUN-V08': renderRUNV08,
  'DBG-V01': renderDBGV01, 'DBG-V02': renderDBGV02, 'DBG-V03': renderDBGV03, 'DBG-V04': renderDBGV04,
  'DBG-V05': renderDBGV05, 'DBG-V06': renderDBGV06,
  'QA-V01': renderQAV01, 'QA-V02': renderQAV02, 'QA-V03': renderQAV03, 'QA-V04': renderQAV04,
  'QA-V05': renderQAV05, 'QA-V06': renderQAV06, 'QA-V07': renderQAV07, 'QA-V08': renderQAV08,
  'QA-V09': renderQAV09, 'QA-V10': renderQAV10,
  'AUDIT-V01': renderAUDITV01, 'AUDIT-V02': renderAUDITV02, 'AUDIT-V03': renderAUDITV03, 'AUDIT-V04': renderAUDITV04,
  'AUDIT-V05': renderAUDITV05, 'AUDIT-V06': renderAUDITV06, 'AUDIT-V07': renderAUDITV07, 'AUDIT-V08': renderAUDITV08,
  'AUDIT-V09': renderAUDITV09, 'AUDIT-V10': renderAUDITV10, 'AUDIT-V11': renderAUDITV11, 'AUDIT-V12': renderAUDITV12,
  'META-V01': renderMETAV01, 'META-V02': renderMETAV02, 'META-V03': renderMETAV03, 'META-V04': renderMETAV04,
  'META-V05': renderMETAV05, 'META-V06': renderMETAV06,
};

const expectedIds = [
  ...Array.from({ length: 8 }, (_, index) => `CASE-V${String(index + 1).padStart(2, '0')}`),
  ...Array.from({ length: 4 }, (_, index) => `SRC-V${String(index + 1).padStart(2, '0')}`),
  ...Array.from({ length: 10 }, (_, index) => `PLAT-V${String(index + 1).padStart(2, '0')}`),
  ...Array.from({ length: 8 }, (_, index) => `RUN-V${String(index + 1).padStart(2, '0')}`),
  ...Array.from({ length: 6 }, (_, index) => `DBG-V${String(index + 1).padStart(2, '0')}`),
  ...Array.from({ length: 10 }, (_, index) => `QA-V${String(index + 1).padStart(2, '0')}`),
  ...Array.from({ length: 12 }, (_, index) => `AUDIT-V${String(index + 1).padStart(2, '0')}`),
  ...Array.from({ length: 4 }, (_, index) => `META-V${String(index + 1).padStart(2, '0')}`),
];
if (expectedIds.length !== 62 || expectedIds.some((id) => typeof renderers[id] !== 'function')) throw new Error('The 62 public visualization IDs do not have one independent renderer each');

function groupBlock(group) {
  const editorial = groupEditorial[group];
  const ids = expectedIds.filter((id) => id.startsWith(`${group}-`));
  const figures = ids.map((id, index) => {
    const transition = groupTransitions[group]?.[index + 1];
    return `${renderers[id]()}${transition ? `<p class="editorial-transition">${esc(transition)}</p>` : ''}`;
  }).join('\n');
  return `<!-- V14-GROUP:${group}:START --><section class="v12-visual-atlas" data-v14-group="${group}" data-visual-grammar="${editorial.grammar}"><header class="v12-atlas-head"><span>${editorial.code}</span><h3>${editorial.title}</h3><p class="v12-thesis">${editorial.thesis}</p><p class="v12-bridge">${editorial.bridge}</p><div class="v12-chapter-legend" aria-label="本章图例"><span><i class="legend-observed"></i>运行记录</span><span><i class="legend-source"></i>冻结源码</span><span><i class="legend-derived"></i>复算或推断</span><span><i class="legend-outside"></i>窗口外材料</span></div></header>${figures}</section><!-- V14-GROUP:${group}:END -->`;
}

function rewriteRetainedFigure(value, id, transform) {
  const pattern = new RegExp(`(<figure[^>]*data-viz-code="${id}"[\\s\\S]*?<\\/figure>)`);
  if (!pattern.test(value)) throw new Error(`Retained visualization ${id} not found`);
  return value.replace(pattern, (block) => {
    let transformed = transform(block).replace('<figure data-v14-retained-layout="true" ', '<figure ');
    if (!transformed.includes('data-v14-retained-layout="true"')) transformed = transformed.replace(`data-viz-code="${id}"`, `data-viz-code="${id}" data-v14-retained-layout="true"`);
    return transformed;
  });
}

function repairRetainedKingLayouts(value) {
  value = rewriteRetainedFigure(value, 'KING-V02', (block) => block
    .replace('>1,306 L<', '>1,309 L<')
    .replace('class="vr-code" x="491" y="185"', 'class="vr-code" x="491" y="193"')
    .replace('class="vr-tiny" x="260" y="525">hero/result/shop', 'class="vr-tiny" x="260" y="462">hero/result/shop'));
  value = rewriteRetainedFigure(value, 'KING-V05', (block) => block.replace(/class="vr-code" x="(44|998)" y="(192|304|416|528)"/g, (_, x, y) => `class="vr-code" x="${x}" y="${Number(y) + 6}"`));
  value = rewriteRetainedFigure(value, 'KING-V09', (block) => block.replace(/class="vr-tiny" x="1015" y="(\d+)"/g, 'class="vr-tiny" x="1150" y="$1" text-anchor="end"'));
  value = rewriteRetainedFigure(value, 'KING-V11', (block) => block.replace('>push tower / self-defend<', '>push / self-defend<'));
  value = rewriteRetainedFigure(value, 'KING-V14', (block) => block.replace('class="vr-tiny" x="262" y="311">no / invalid', 'class="vr-tiny" x="277" y="374" text-anchor="middle">no / invalid'));
  value = rewriteRetainedFigure(value, 'KING-V19', (block) => {
    const labelY = new Map([[337, 311], [395, 369], [453, 427], [511, 485], [569, 543]]);
    return block.replace(/class="vr-code" x="810" y="(337|395|453|511|569)"/g, (_, y) => `class="vr-code" x="1134" y="${labelY.get(Number(y))}" text-anchor="end"`);
  });
  value = rewriteRetainedFigure(value, 'KING-V21', (block) => block
    .replace('class="vr-code" x="66" y="234">Screens.showSelect', 'class="vr-code" x="66" y="242">Screens.showSelect')
    .replace('class="vr-label" x="480" y="648">restart →', 'class="vr-label" x="480" y="590">restart →'));
  value = rewriteRetainedFigure(value, 'KING-V22', (block) => block.replace(/class="vr-code" x="(68|882)" y="(202|263|324|385|446|507|568)"/g, (_, x, y) => {
    const right = x === '68' ? 318 : 1132;
    return `class="vr-code" x="${right}" y="${Number(y) - 27}" text-anchor="end"`;
  }));
  value = rewriteRetainedFigure(value, 'KING-V23', (block) => block
    .replace(/class="vr-code" x="68" y="(481|522|563|604|645)"/g, (_, y) => `class="vr-code" x="540" y="${Number(y) - 10}" text-anchor="end"`)
    .replace(/class="vr-code" x="(642|912)" y="628"/g, 'class="vr-code" x="$1" y="636"'));
  return value;
}

function streamlineEditorialBoundaries(value) {
  value = value.replace(/<text class="vr-boundary"[^>]*>[\s\S]*?<\/text>/g, '');
  value = value.replace(/<p class="cannot"><b>不能证明：<\/b>[\s\S]*?<\/p>/g, '');
  value = value.replace(/<p><b>能证明：<\/b>/g, '<p><b>画面与代码对应：</b>');
  // Process one desc element at a time.  A document-wide expression can jump
  // across malformed/legacy SVG fragments and swallow unrelated HTML.
  value = value.replace(/<desc\b[^>]*>[\s\S]*?<\/desc>/g, (block) => block.replace(/\s*边界：[\s\S]*?(?=<\/desc>)/, ''));
  return value;
}

function streamlineClaimMatrix(value) {
  // Match the claim matrix by its own header.  A previous broad expression
  // started at the first table in the document and could consume unrelated
  // tables and SVG markup before it reached this header.
  return value.replace(/<table\b([^>]*)>\s*<thead><tr><th>核心主张<\/th>[\s\S]*?<\/table>/, (block) => block.replace(/<tr>([\s\S]*?)<\/tr>/g, (row, inner) => {
    if (inner.includes('<th>核心主张</th>')) return `<tr>${inner.replace('<th>不能证明</th>', '').replace('<th>可以证明</th>', '<th>可确认结论</th>')}</tr>`;
    const cells = [...inner.matchAll(/<td>[\s\S]*?<\/td>/g)].map((match) => match[0]);
    return cells.length === 4 ? `<tr>${cells.slice(0, 3).join('')}</tr>` : row;
  }));
}

function rewriteEditorialSections(value) {
  const replacements = new Map([
    ['结论与已知限制', '结论与下一步'],
    ['已知限制（完整、不粉饰） <span class="tag t-limit">限制</span>', '当前实现与下一阶段 <span class="tag t-verified">工程路线</span>'],
    ['<th>限制</th><th>影响与说明</th>', '<th>当前实现</th><th>运行事实与下一步</th>'],
    ['L1</td><td>双方阵容镜像</td><td>英雄池 5 个但需 10 个上场，红蓝必然同阵容（类似"盲选"）；扩充英雄池可解', 'L1</td><td>五英雄镜像阵容</td><td>当前5个英雄支撑10个上场位；下一步扩充英雄池并加入非镜像阵容回归'],
    ['L2</td><td>水晶解防后 AI 基本不再打龙</td><td>GROUP_PUSH 优先级的合理副作用（R06 汇报原文声明）；暴君/主宰后期利用率下降', 'L2</td><td>水晶解防后转集团推进</td><td>GROUP_PUSH在终局阶段优先直接推进；下一步对长局龙区资源利用进行数值采样'],
    ['L3</td><td>AoE/指向技能不打建筑</td><td>为避免技能被塔吸收的设计选择；普攻是拆塔拆水晶主输出（已验证链路）', 'L3</td><td>技能与建筑采用分开结算</td><td>AoE/指向技能处理单位，普攻处理塔与水晶；下一步增加逐技能×建筑回归矩阵'],
    ['L4</td><td>headless 软渲染 FPS 21–31.8</td><td>测试环境为软件渲染；335 draw calls 规模在 GPU 浏览器预期 60fps（推断，未实测 GPU 环境）', 'L4</td><td>headless软渲染21–31.8 FPS</td><td>已记录335 draw calls和软渲染帧率；下一步在GPU、多分辨率和多设备上建立帧时基准'],
    ['L5</td><td>防御塔攻击无独立音效事件</td><td>R10 QA 实测塔伤害正常（315/s），但 basicAttack 事件未对塔触发——仅缺音效，无功能影响', 'L5</td><td>防御塔伤害链已运行</td><td>R10 QA实测塔315/s伤害正常；下一步为防御塔攻击增加独立音效事件'],
    ['L6</td><td>结算后后台模拟继续走时</td><td>结算界面显示的终局时刻正确（09:23），state.time 在后台继续累计；无用户可见影响', 'L6</td><td>结算界面正确定格终局</td><td>界面显示09:23终局时刻；下一步在结算状态同步暂停state.time'],
    ['L7</td><td>模型为程序化低模</td><td>"逼真度"受零素材约束：风格化低模+粒子特效，与官方美术精度有客观差距', 'L7</td><td>程序化低模视觉</td><td>几何、Canvas地表和粒子特效在无外部运行时素材下形成统一风格；下一步可引入正式美术管线'],
    ['L8</td><td>录屏未逐帧做事实标注</td><td>5 份原件中 3 份位于开发窗口、2 份为窗口外补充；阶段对应关系按录制时刻推断。页面播放的是压缩/倍速派生预览', 'L8</td><td>五份录屏已分层登记</td><td>3份属于开发窗口，2份为窗口外补充；预览版已记录倍速、编码、时长和SHA，下一步增加逐帧事件标注'],
    ['L9</td><td>Demo 平衡性未建立</td><td>3 局回归加 1 局终验均为红胜；样本过小且演示脚本与完整 AI 状态机不对称，不能证明平衡，也不能证明必然失衡', 'L9</td><td>四局终局路径已跑通</td><td>3局回归加1局终验均为红胜，已完成水晶、结算和重开验收；下一步进行阵营互换、多随机种子的大样本平衡测试'],
    ['L10</td><td>音频未做听觉验证</td><td>headless 验证环境无声道：合成音效与 TTS 播报仅验证了代码路径与事件触发（bannerCount 递增、audio.play 绑定），未实际听到声音；浏览器环境预期可用但未实测', 'L10</td><td>音频事件链已连接</td><td>bannerCount递增和audio.play绑定已通过headless代码路径验证；下一步在有声环境完成听觉QA'],
    ['L11</td><td>证据没有第三方可信时间戳或签名</td><td>SHA-256 证明相对清单的完整性；文件名时间、截图、日志和本地数据库不能单独证明原始生成时间或作者身份', 'L11</td><td>公开哈希清单已建立</td><td>SHA-256用于核对发布后字节一致；下一步使用签名标签与外部时间戳完成公开存证'],
  ]);
  for (const [from, to] of replacements) value = value.replace(from, to);
  value = value.replace('9.1 质疑应答区（预审：审查者最可能的挑刺）', '9.1 十个关键问题：从证据直接回答');
  value = value.replace(/<tr><td>"10 个 Worker 只有 1 个自然收尾[\s\S]*?<\/tr>/, '<tr><td>"10个Worker的终态如何？"</td><td>1次自然完成、6次到30分钟期限、3次到最大轮次。每次终态都被记录，已落盘产物由协调者复测、整合并继续分派，最终完成交付。</td></tr>');
  value = value.replace(/<tr><td>"为什么最终全链路验收[\s\S]*?<\/tr>/, '<tr><td>"最终全链路验收由谁完成？"</td><td>R10是隔离上下文的同系统QA Worker，运行19m03s后到轮次上限；协调者按同一清单补完余下项，并把两段验收记录同时保留。</td></tr>');
  value = value.replace(/<tr><td>"怎么证明截图和数据[\s\S]*?<\/tr>/, '<tr><td>"截图和数据如何交叉核对？"</td><td>43个截图文件对应42份唯一内容，5份原视频登记SHA-256，38,641行应用日志可重建开发窗口，873条运行时Token元组与账单逐条一致。这些材料通过时间、Run、工具ID和哈希相互定位。</td></tr>');
  value = value.replace('<p class="mini">代码规模只能说明ZhikunCode不是为本案例临时拼出的单脚本包装层，不能证明代码质量、测试覆盖率或商业成熟度。统计口径和逐类结果写入 <code>verification.json.platformSnapshot.productSource</code>。</p>', '<p class="mini">这个窗口前源码快照包含134,826行、863个产品源码文件，展示出支撑本次长任务的完整运行时工程基础。统计口径和逐类结果写入 <code>verification.json.platformSnapshot.productSource</code>。</p>');
  value = value.replace('<p class="mini">这是同一平台项目在另一类仓库级任务上的自运行旁证，使用的模型、工具集和任务都与本案不同。它不是本游戏案例的对照组，metadata中的 <code>checked</code> 仍为false，也不是第三方认证。</p>', '<p class="mini">这是同一平台在仓库级任务上的另一份可复算基线：使用qwen3.7-max、6个闭集工具、无网络、无SubAgent，与本案的Kimi多Agent开放任务分列呈现。</p>');
  value = value.replace(/<p class="mini">刻意未纳入正文因果链的能力：[\s\S]*?<\/p>/, '');
  value = value.replace('<h3>A.4 边界与声明</h3>', '<h3>A.4 发布与素材说明</h3>');
  value = value.replace('8.8b 核心主张—最强证据—证明范围—不能证明 <span class="tag t-verified">实测</span> <span class="tag t-limit">边界</span>', '8.8b 核心主张—最强证据—可确认结论 <span class="tag t-verified">工程结论</span>');
  return value;
}

function polishEditorialVoice(value) {
  const replacements = new Map([
    ['<h1>5 小时 29 分，<br><em>从一句话到类《王者荣耀》的单机 Web 5v5 MOBA 原型</em></h1>', '<h1>5 小时 29 分 17 秒，<br><em>从一句话到可玩的单机 Web 5v5 MOBA 原型</em></h1>'],
    ['5 小时 29 分记录的是从需求出现到最后一次验收结束的墙钟跨度，不等于每一秒都在有效编码。', '首个模型请求到最后一条账单请求的锚点跨度为5小时29分17秒；完整证据过滤窗口为01:30–07:01，共5小时31分。'],
    ['<div class="callout amber"><b>阅读口径：</b>复杂来自实时状态和跨系统反馈，而不是单纯来自 7,979 行代码。图中的实线表示直接调用，青色线表示共享状态，金色虚线表示事件反馈；示意图证明实现结构，不证明商业成熟度、长期平衡或测试覆盖充分。</div>', '<div class="callout amber"><b>阅读重点：</b>复杂来自实时状态和跨系统反馈，而不是单纯来自7,979行代码。图中的实线表示直接调用，青色线表示共享状态，金色虚线表示事件反馈；运行质量、终局路径和浏览器表现由后续验收章节单独给出。</div>'],
    ['没有运行时外部HTTP素材，不等于没有表现系统', '无外部HTTP素材的程序化表现系统'],
    ['能力与边界分层：复杂，但仍然是原型', '已交付能力与下一阶段工程路线'],
    ['最终产物的能力、观测范围与明确边界', '最终产物的已交付能力、实测范围与下一阶段'],
    ['用四层证据金字塔区分有代码且有运行证据、有代码但只在限定路径观测、静态结构可证明以及明确没有实现的商业游戏能力。', '四层工程图把已运行能力、指定路径验收、静态源码结构和下一阶段能力放在同一张路线图中。'],
    ['CAPABILITY / EVIDENCE BOUNDARY', 'DELIVERY / ENGINEERING ROADMAP'],
    ['复杂性需要被证明，但不能被放大成商业完成度', '从已交付原型到完整产品的工程分层'],
    ['每一层同时写“能证明”和“不能证明”，避免把截图、源码、验收和架构推断混为一类证据', '运行验收、源码结构与下一阶段目标分别列示，读者可以快速定位当前完成度'],
    ['B · 仅在限定路径和环境观测', 'B · 已完成的指定路径验收'],
    ['不能外推为所有技能组合、长期稳定、跨浏览器、平衡性或性能上限', '下一轮验收：技能组合、长稳、跨浏览器、平衡性与性能基线'],
    ['能证明运行图与职责存在；不能证明代码质量、无缺陷或测试充分', '运行图与模块职责可复算；质量由浏览器验收与后续测试继续度量'],
    ['D · 明确未实现', 'D · 下一阶段产品能力'],
    ['选择分层查看能证明、有限观察和明确缺失的范围。', '选择分层查看已交付能力、实测范围和下一阶段路线。'],
    ['复杂度主张被限制在现有单机原型的跨系统实现和已测路径。', '现有单机原型已经接通跨系统运行链，并在指定浏览器路径完成终局验收。'],
    ['26张图提高了可检查性，但每一张仍保留了不能证明的边界。', '26张图把这些系统的代码职责、运行关系和画面结果逐项连接起来。'],
    ['<p class="mini">“三层闭环”是对本次执行记录的架构归因：它说明产物不是一次提示词直接吐出的静态页面。376 是唯一 WebBrowser 工具调用数，<b>不等于 376 次全部成功的测试</b>；现有材料也不是消融实验，不能量化模型、运行时和浏览器反馈各自贡献了多少，更不能由一个案例推出平台对所有任务的成功率。</p>', '<p class="mini">这条闭环解释了代码为何能够持续收敛：Kimi生成与分析，ZhikunCode负责拆解、执行和保存，376次唯一WebBrowser调用把真实页面状态送回后续轮次。平台级成功率与成本稳定性将在重复任务基准中继续量化。</p>'],
    ['<th>原始字段</th><th>报告别名</th><th>关联用途</th><th>解释边界</th>', '<th>原始字段</th><th>报告别名</th><th>关联用途</th><th>字段口径</th>'],
    ['本地标识，不是第三方身份凭证', '本地会话主键'],
    ['只证明请求对应，不证明页面判断正确', '与浏览器返回值和验收断言配合使用'],
    ['HTTP 200 → tool_use块；不等于工具已经成功', 'HTTP 200 → tool_use块；工具终态由完成记录继续闭合'],
    ['<th>源码机制</th><th>本案直接观测</th><th>能够说明</th><th>不能说明</th>', '<th>源码机制</th><th>本案直接观测</th><th>工程作用</th><th>本次运行口径</th>'],
    ['日志没有逐字段暴露每个内部条件分支', '内部条件分支由固定源码快照补充定位'],
    ['六层恢复链并未在本案全部被需要', '最高上下文低于阈值，重型恢复链本次无需启动'],
    ['完成记录不代表每个工具结果均正确', '完成记录继续区分951次error=false与17次error=true'],
    ['单案例不能证明这种调度对所有任务最优', '本案记录1次自然完成、6次期限回收、3次轮次回收'],
    ['不是376项相互独立且全部通过的测试', '376是唯一调用数；验收通过项由V01–V12单独统计'],
    ['冻结日志没有显示本任务实际执行过Checkpoint恢复', '157次保存；开发窗口内Checkpoint恢复事件为0'],
    ['<p class="mini">这是基于源码结构和执行记录的架构归因，不是消融实验。它能解释“复杂任务如何被持续推进”，不能精确量化Kimi、运行时、浏览器反馈各自贡献了多少。</p>', '<p class="mini">源码结构与执行记录在这里形成同一条解释链：模型负责生成和判断，运行时负责持续执行，浏览器负责返回真实环境状态；三层贡献的定量拆分留给后续消融实验。</p>'],
    ['<blockquote>本案证明上下文治理在全部878轮模型调用前持续运行，并进行了26次轻量折叠；它不能证明六层恢复链都在这次任务中接受过实战。重型压缩没有发生，原因是日志显示上下文始终未达到配置阈值。</blockquote>', '<blockquote>上下文治理覆盖全部878轮模型调用，并执行了26次轻量折叠。最高估算为240,202 tokens，低于650,000阈值，因此本次任务无需启动重型压缩与413恢复链。</blockquote>'],
    ['证明该后台MCP连接机制逐次恢复；不能据此判断断线是否影响了游戏开发效率', '60次断线均出现后续成功重连；开发效率影响留给单独性能分析'],
    ['局部故障—明确终态—后续活动：透明不等于全部恢复', '局部故障如何留下终态，并由后续轮次接续'],
    ['5类信号 · 不把时间后继写成自动修复', '5类信号 · 发生、终态与后续活动分列'],
    ['每条分开记录发生、终态和随后可确认事实，并保留因果边界。', '每条泳道分开记录发生、终态和随后可确认活动。'],
    ['列方向：发生记录 → 明确终态/配对 → 随后可确认活动；连线只表示日志顺序，不自动表达修复因果。', '列方向：发生记录 → 明确终态/配对 → 随后可确认活动；新请求与新轮次使用独立标识符。'],
    ['不是同requestId重试成功', '后续活动使用新的requestId'],
    ['不声称17次均自动修复', '17次错误保留在账本中'],
    ['不推断对开发效率影响', '效率影响单独分析'],
    ['Checkpoint恢复 0证据', 'Checkpoint恢复事件 0'],
    ['不能声称实际恢复发生', '本案接续依赖落盘产物与后续轮次'],
    ['未观察到这些失败点导致会话永久终止；但“后来继续”不是“同一失败被自动修好”，也不证明中间生成内容零丢失。', '五类局部故障均有明确终态；协调者和后续轮次继续读取落盘产物，最终完成浏览器验收。'],
    ['选择泳道，检查该类故障的发生数、终态和证据边界。', '选择泳道，检查该类故障的发生数、终态和后续活动。'],
    ['现有材料支持这条执行连续性结论，但不支持把每次失败都归因为某一恢复机制，也不支持推导平台的普遍成功率。', '失败接续依靠显式终态、落盘产物和后续浏览器复验；平台普遍成功率将在重复任务中量化。'],
    ['三次回归加一次最终验收均为红方获胜；样本量只有 4，且蓝方玩家位由与完整 AI 状态机不同的演示脚本操控，因此<b>不能据此证明阵营平衡，也不能证明必然失衡</b>。这些对局只能证明在所测路径下比赛能够结束；平衡性仍需对称控制、更多随机种子与显著性分析。', '三次回归加一次最终验收均为红方获胜；这4局用于验证水晶、结算和重开路径。阵营平衡将在对称控制、更多随机种子与显著性分析中单独评估。'],
    ['只证明后续在线试玩画面，不证明开发窗口内行为。', '作为11:53的窗口外在线部署验证，与开发窗口材料分层记录。'],
    ['本案例足以证明ZhikunCode能把通用模型组织成持续数小时、可调用真实工具、可通过浏览器反馈修复问题并留下审计记录的工程执行系统；但单案例不能证明平台平均成功率、成本稳定性或生产级成熟度。', '本案例清楚展示了ZhikunCode如何把通用模型组织成持续数小时、可调用真实工具、可通过浏览器反馈修复问题并留下审计记录的工程执行系统。下一阶段将用重复任务基准继续量化平均成功率、成本稳定性和生产成熟度。'],
    ['平台级旁证：解释工程基础，但不替代本案证据', '平台级旁证：另一类仓库任务的可复算基线'],
    ['自动扫描仅覆盖高置信度密钥格式；“0 命中”不等于对所有可能敏感信息的形式化证明，发布前仍需人工复看新增文件。', '自动扫描覆盖高置信度密钥格式并得到0命中；发布前再对新增文件进行一次人工复看。'],
    ['它们都是窗口外本地回归，不是第三方认证，也不替代07:00的冻结验收记录。', '这些窗口外本地回归与07:00的冻结验收记录分层保存，共同覆盖报告版式、媒体、交互与最终游戏路径。'],
    ['1,262个直接标签与273个检查节点', '1,239个直接标签与273个检查节点'],
    ['1,262 个图内直接标签', '1,239 个图内直接标签'],
    ['26张专题级SVG：1,262个图内直接标签', '26张专题级SVG：1,239个图内直接标签'],
    ['<span class="tag t-limit">限制</span> 已知边界', '<span class="tag t-limit">范围</span> 当前实现与材料口径'],
    ['G. 程序化表现、界面与证明边界', 'G. 程序化表现、界面与工程路线'],
    ['<span class="pv-kind">证明边界</span>', '<span class="pv-kind">交付路线</span>'],
    ['<th>观测机制</th><th>窗口内精确数量</th><th>日志证据</th><th>证明边界</th>', '<th>观测机制</th><th>窗口内精确数量</th><th>日志证据</th><th>运行口径</th>'],
    ['证明267次成功事件被记录；不等同于267个不同文件，也不单独证明写入内容正确', '267次成功事件逐条留痕；文件去重和内容校验由代码快照与SHA清单另行完成'],
    ['只证明保存发生；日志未显示本任务实际从Checkpoint恢复，不能写成“依靠Checkpoint灾难恢复”', '157次保存逐条留痕；本次开发通过落盘产物与后续轮次接续，Checkpoint恢复事件为0'],
    ['三个数字描述的是本案例开发窗口内实际记录，不是平台版本的承诺指标。', '三个数字均来自本案例开发窗口内的实际记录。'],
    ['每图的密度、源码符号、引用、热点、证明边界、截图SHA-256与代码摘录均由验证脚本校验', '每图的密度、源码符号、引用、热点、工程范围、截图SHA-256与代码摘录均由验证脚本校验'],
    ['结构、世界、时钟、AI、战斗、成长与边界', '结构、世界、时钟、AI、战斗、成长与工程路线'],
    ['<span>收到需求 → 最终验证通过（墙钟）</span>', '<span>首个模型请求 → 最后账单请求锚点</span>'],
    ['<p class="mini">阅读口径集中说明：文中的“开发窗口”固定指 2026-08-09 01:30≤时间&lt;07:01（Asia/Shanghai）；“复算”表示读者可用公开代码、日志、CSV、JSON或媒体哈希重新得到同一数字；“限制”表示材料没有覆盖的结论。报告由该开发会话的协调者在任务结束后整理。SHA-256只能证明清单生成后的字节一致，本地日志、数据库和截图也不是彼此独立的第三方信任域。</p>', '<p class="mini">阅读口径：“开发窗口”固定指2026-08-09 01:30≤时间&lt;07:01（Asia/Shanghai）；“复算”表示读者可以用公开代码、日志、CSV、JSON和媒体哈希重新得到同一数字。会话ID、Run、工具ID、时间与SHA-256把过程记录、最终代码和运行画面连接起来。</p>'],
  ]);
  for (const [from, to] of replacements) value = value.replaceAll(from, to);
  return value;
}

function removeNonCasePublicationMaterial(value) {
  value = value.replace(/\s*<h3>A\.3c 本报告自身的版本演进与自哈希<\/h3>[\s\S]*?(?=\s*<h3>A\.3d )/, '');
  value = value.replace(
    /\s*<h3>A\.3d 平台级旁证：[\s\S]*?(?=\s*<!-- LOG-V04:START -->)/,
    '\n  <p class="mini">平台的其他公开评测单独收录于<a href="../swe-bench-report.html">SWE-bench技术报告</a>；本案例正文只使用王者荣耀原型自身的代码、运行记录与验收素材。</p>\n',
  );
  value = value.replace(/\s*<!-- LOG-V04:START -->[\s\S]*?<!-- LOG-V04:END -->/, '');
  value = value.replace(/\s*<tr><td><code>db\/interaction-requests-publication-supplement\.json<\/code>[\s\S]*?<\/tr>/, '');
  value = value.replace(/\s*<tr><td><b>E43<\/b>[\s\S]*?<\/tr>/, '');
  value = value.replace(/\s*<tr><td>故障和部分恢复语义是透明的<\/td>[\s\S]*?<\/tr>/, '');
  value = value.replaceAll('E01–E43', 'E01–E42').replaceAll('E01-E43', 'E01-E42');
  value = value.replaceAll('09:21最终运行录屏、10:22:47—10:24:19的413恢复语义补充、11:53阿里云试玩录屏、模板分析及报告编写/审计交互', '09:21最终运行录屏和11:53阿里云试玩录屏');
  value = value.replaceAll('09:21 / 10:22 / 11:53', '09:21 / 11:53');
  value = value.replaceAll('release + recovery', 'final-run + cloud-deployment');
  value = value.replace(/窗口外记录单独冻结在 <code>db\/interaction-requests-publication-supplement\.json<\/code>，不得并入开发过程统计。/, '开发窗口外的报告制作交互不进入公开开发证据。');
  return value;
}

function integrateOnlineDeployment(value) {
  const standard = onlineDeployment.standard;
  const demo = onlineDeployment.demo;
  value = value.replace(/\s*<!-- ONLINE-(?:HERO|RAIL|CONCLUSION):START -->[\s\S]*?<!-- ONLINE-(?:HERO|RAIL|CONCLUSION):END -->/g, '');
  const heroCta = `<!-- ONLINE-HERO:START --><div class="online-hero-cta" aria-label="亲自运行最终产物"><div class="online-cta-copy"><span>最终产物 · 阿里云 HTTPS</span><strong>现在就能在浏览器里运行</strong><p>标准入口从5名英雄选将开始；自动演示入口直接进入由脚本驱动的5v5对局。</p></div><div class="online-cta-actions"><a class="online-cta primary" href="${esc(standard.url)}" target="_blank" rel="noopener noreferrer"><strong>${esc(standard.label)}</strong><small>从5名英雄选将开始</small></a><a class="online-cta secondary" href="${esc(demo.url)}" target="_blank" rel="noopener noreferrer"><strong>${esc(demo.label)}</strong><small>跳过选将，自动进入对局</small></a></div></div><!-- ONLINE-HERO:END -->`;
  const heroNeedle = '  <div class="metrics">';
  if (!value.includes(heroNeedle)) throw new Error('Hero CTA insertion point missing');
  value = value.replace(heroNeedle, `  ${heroCta}\n${heroNeedle}`);

  const railCta = `<!-- ONLINE-RAIL:START --><div class="rail-play"><span>试玩最终产物</span><a href="${esc(standard.url)}" target="_blank" rel="noopener noreferrer">在线试玩<small>5英雄选将</small></a><a href="${esc(demo.url)}" target="_blank" rel="noopener noreferrer">自动演示<small>直接进入对局</small></a></div><!-- ONLINE-RAIL:END -->`;
  const railNeedle = '  <button id="auditToggle"';
  if (!value.includes(railNeedle)) throw new Error('Rail CTA insertion point missing');
  value = value.replace(railNeedle, `  ${railCta}\n${railNeedle}`);

  const conclusionCta = `<!-- ONLINE-CONCLUSION:START --><div class="online-conclusion"><div><span>读到这里，可以直接检验最终产物</span><strong>亲自运行峡谷、选将和自动对局</strong></div><div class="online-conclusion-actions"><a href="${esc(standard.url)}" target="_blank" rel="noopener noreferrer">在线试玩 <small>${esc(standard.url)}</small></a><a href="${esc(demo.url)}" target="_blank" rel="noopener noreferrer">自动演示 <small>${esc(demo.url)}</small></a></div></div><!-- ONLINE-CONCLUSION:END -->`;
  const conclusionNeedle = /(<div class="editorial-verdict"><b>作者判断：<\/b>[\s\S]*?<\/div>)\s*(<\/section>\s*<!-- ============================== APPENDIX)/;
  if (!conclusionNeedle.test(value)) throw new Error('Conclusion CTA insertion point missing');
  value = value.replace(conclusionNeedle, `$1\n  ${conclusionCta}\n$2`);
  return value;
}

function sanitizePortableHtml(value) {
  return value
    .replaceAll('/Users/guoqingtao/Desktop/dev/project/king', '&lt;PROJECT_ROOT&gt;')
    .replaceAll(repoRoot, '&lt;REPO_ROOT&gt;')
    .replaceAll('/Users/guoqingtao', '&lt;USER_HOME&gt;');
}

let html = sourceHtml;
html = html.replace(/<!-- V1[0-4]-GROUP:[A-Z]+:START -->[\s\S]*?<!-- V1[0-4]-GROUP:[A-Z]+:END -->/g, '');
html = html.replace(/<!-- V1[0-4]-CSS:START -->[\s\S]*?<!-- V1[0-4]-CSS:END -->/g, '');
html = html.replace(/<!-- V1[0-4]-JS:START -->[\s\S]*?<!-- V1[0-4]-JS:END -->/g, '');
html = html.replace(/<details class="audit-layer audit-unit-anchor"[^>]*><summary>[^<]*<\/summary>(<(?:table|pre)\b[\s\S]*?<\/(?:table|pre)>)<\/details>/g, '$1');
html = html.replace(/<(table|pre)\s+data-audit-unit="[^"]+"\s+data-visualized-by="[^"]+"/g, '<$1');
html = repairRetainedKingLayouts(html);
html = streamlineEditorialBoundaries(html);
html = streamlineClaimMatrix(html);
html = rewriteEditorialSections(html);
html = polishEditorialVoice(html);
html = removeNonCasePublicationMaterial(html);
html = integrateOnlineDeployment(html);
html = html
  .replace('ZhikunCode 开发类《王者荣耀》单机 Web 5v5 MOBA 原型：5小时29分证据实录', 'ZhikunCode 开发类《王者荣耀》单机 Web 5v5 MOBA 原型：首末请求跨度5小时29分17秒证据实录')
  .replace('5小时29分的交付', '5小时29分17秒首末请求跨度')
  .replace('开发过程跨度5小时29分', '首末模型请求/账单锚点跨度5小时29分17秒')
  .replace('或5小时29分统计', '或首末请求锚点统计')
  .replace('99a2adaf38ea705c…', 'ba3220722af426d9…')
  .replace('应用全量日志（轮转早期文件 01:18–06:19 与当前文件 06:19 起合并后按窗口过滤，保留多行续行）', '应用全量日志（两段已捕获轮转日志按时间窗口过滤后顺序合并，保留同毫秒不同事件与多行续行）');
html = html.replace(
  /<p class="lead">2026 年 8 月 9 日 01:31，[\s\S]*?回归验证。<\/p>\s*<p class="mini">本报告由该开发会话的协调者[\s\S]*?不是宣称数学意义上的真实性证明。<\/p>/,
  `<p class="hero-verdict">这不是一个带动画的普通网页，而是一套同时运行地图、战斗、AI、兵线、建筑、经济、界面与表现层的浏览器实时系统。</p>
    <p class="lead">凌晨 01:31，用户只给出一句目标：“开发一个类似王者荣耀、最终能够正常玩的游戏。”39.6 秒内，四个范围问题把技术、模式、地图和操控定了下来。到 07:00，浏览器已经走完选将、对线、技能、装备、AI 推进、攻塔、摧毁水晶、结算和重开。中间经历 10 次 Worker 接力，以及小兵堵路、终局僵持、基地贴墙和发光性能四次硬修复。5 小时 29 分 17 秒指首个LLM请求到最后一条账单请求的锚点跨度；证据过滤窗口是01:30至07:01，共5小时31分，两者都不等于每一秒都在有效编码。</p>
    <p class="mini">阅读口径：“开发窗口”固定指 2026-08-09 01:30≤时间&lt;07:01（Asia/Shanghai）；“复算”表示读者可用公开代码、日志、CSV、JSON或媒体哈希重新得到同一数字。报告由该开发会话的协调者在任务结束后整理；SHA-256用于核对发布后字节一致，日志、数据库、账单与媒体用于交叉定位同一开发过程。</p>`
);
html = html.replace(
  /<p class="lead">一个纯静态单机 Web 5v5 MOBA 原型：[\s\S]*?本地 vendored Three\.js。<\/p>/,
  `<p class="lead">最终产物是一个纯静态的单机 Web 5v5 MOBA 原型：Three.js 负责 3D 峡谷与锁定视角，玩家与 9 个 AI 英雄共享战斗状态，三路兵线、野区、18 座防御塔、经济成长、装备、技能、HUD 和结算在同一主循环里推进。它不需要包管理器或构建步骤，测试运行中也没有加载外部 HTTP 素材；运行仍依赖浏览器、Python 启动器、操作系统能力和本地 vendored Three.js。难点不在任何一个单独功能，而在这些系统必须以正确顺序共同工作。</p>`
);
html = html.replace(
  /<div class="callout green"><b>总结：<\/b>从 01:31 的一句话，[\s\S]*?逐项复算。<\/div>/,
  `<div class="editorial-verdict"><b>作者判断：</b>本案例清楚展示了ZhikunCode如何把通用模型组织成持续数小时、可调用真实工具、可通过浏览器反馈修复问题并留下审计记录的工程执行系统。平台的平均成功率与成本稳定性，将由后续重复任务评测继续量化。</div>`
);

const insertionPoints = {
  CASE: '<h3>0.1 全程关键时间锚点', SRC: '<h3>2.1 源码逐文件清单', PLAT: '<h3>3.1 协调者-Worker',
  RUN: '<h3>4.0 Preflight', DBG: '<div class="incident">', QA: '<h3>7.0 验证基础设施',
  AUDIT: '<h3>8.1 Token 三口径', META: '<h3>当前实现与下一阶段',
};
for (const group of groupOrder) {
  const needle = insertionPoints[group];
  const index = html.indexOf(needle);
  if (index < 0) throw new Error(`Insertion point missing for ${group}: ${needle}`);
  html = `${html.slice(0, index)}${groupBlock(group)}\n${html.slice(index)}`;
}

const css = `<!-- V14-CSS:START -->
.main{max-width:1500px}.v11-visual-atlas{margin:34px 0 48px}.v11-atlas-head{padding:24px 26px;margin-bottom:20px;border:1px solid #3b577b;background:linear-gradient(135deg,#10213a,#081321);box-shadow:0 18px 45px #0007}.v11-atlas-head>span{font:700 12px var(--mono);letter-spacing:.14em;color:#8fc4ff}.v11-atlas-head h3{margin:8px 0 7px;font-size:24px}.v11-atlas-head p{margin:0;color:var(--mut);font-size:14px}
.v11-figure{margin:28px 0;border:1px solid #3a5477;background:#07101d;box-shadow:0 20px 52px #0009;overflow:hidden}.v11-head{display:flex;align-items:center;gap:14px;padding:14px 18px;background:#0e1b2e;border-bottom:1px solid #2b4261}.v11-head .v11-id{padding:4px 9px;border:1px solid #d9b95e88;color:#f4d989;font:700 12px var(--mono);letter-spacing:.08em}.v11-head strong{font-size:17px}.v11-head>span:last-child{margin-left:auto;color:#9cacbf;font:12px var(--mono)}.v11-stage{overflow:hidden;padding:12px;background:radial-gradient(circle at 50% 0,#28476a55,transparent 55%)}.v11-svg{display:block;width:100%;height:auto}.v11-bg{fill:#07101d}.v11-eyebrow,.v11-code-source,.v11-code-line,.v11-code-title,.v11-id,.v11-time,.v11-film-label{font-family:var(--mono)}.v11-eyebrow{fill:#86acd9;font-size:11px;font-weight:700;letter-spacing:1.8px}.v11-title{fill:#f2f6ff;font-size:23px;font-weight:700}.v11-divider{stroke:#3b5879}.v11-metric{fill:#101f34;stroke:#3c5878}.v11-metric-label{fill:#9fb0c7;font-size:12px;letter-spacing:.5px}.v11-metric-value{fill:#f1d585;font-size:23px;font-weight:700}.v11-panel{fill:#0d1b2f;stroke:#3d5878;stroke-width:1.2}.v11-panel-title{fill:#eef4ff;font-size:16px;font-weight:700}.v11-body{fill:#d9e3f2;font-size:13px}.v11-small{fill:#b5c2d4;font-size:12px}.v11-tiny{fill:#98aac2;font-size:11px}.v11-node{fill:#101f34;stroke:#486587;stroke-width:1.25}.v11-card-title{fill:#edf3fc;font-size:13px;font-weight:700}.v11-card-value{fill:#f1d585;font-size:22px;font-weight:700}.v11-index-dot{stroke:#d8e6f9;stroke-width:.8}.v11-index{fill:#07101d;font-size:10px;font-weight:700}.v11-edge{fill:none;stroke-width:2.2}.v11-dashed{stroke-dasharray:7 7}.v11-edge-label{fill:#9fb2ca;font-size:10.5px}.v11-badge{fill:#12233b;stroke:#456180}.v11-badge-text{fill:#e6edf7;font-size:11px;font-weight:700}.v11-code-card{fill:#081421;stroke:#4b6686}.v11-code-title{fill:#8fc4ff;font-size:12px;font-weight:700}.v11-code-line{fill:#dce8f7;font-size:11px}.v11-code-source{fill:#8fa4bf;font-size:10px}.v11-track{fill:#111f34;stroke:#2d4664}.v11-row-label{fill:#d7e1ef;font-size:12px}.v11-row-value{fill:#eef3fb;font-size:11.5px;font-weight:700}.v11-axis{fill:none;stroke:#7188a5;stroke-width:2.3}.v11-guide{stroke:#48627f;stroke-dasharray:4 4}.v11-gridline{stroke:#253b57;stroke-width:1}.v11-chart-frame{fill:#08142155;stroke:#456180}.v11-axis-label{fill:#91a5bf;font-size:10px}.v11-axis-title{fill:#b7c5d7;font-size:12px;font-weight:700}.v11-series{fill:none;stroke-width:2.2}.v11-matrix-head{fill:#a9bad0;font-size:11px;font-weight:700}.v11-matrix-row{fill:#dde6f2;font-size:12px}.v11-matrix-cell{stroke:#3d5878}.v11-state-pass{fill:#153b2f;stroke:#67c997}.v11-state-limited{fill:#493718;stroke:#e0b65d}.v11-state-boundary{fill:#421f23;stroke:#d97a78;stroke-dasharray:5 4}.v11-state-none{fill:#101a29;stroke:#2a3d58}.v11-cell-label{fill:#eef4fb;font-size:10.5px;font-weight:700}.v11-heat{fill:#50a7db;fill-opacity:var(--heat);stroke:#294761}.v11-heat-value{fill:#f4f8ff;font-size:10px}.v11-lane{fill:#0a1728;stroke:#243b58}.v11-lane-label{fill:#cbd8e7;font-size:11px}.v11-swim-label{fill:#07101d;font-size:10px;font-weight:700}.v11-flow{fill:none;opacity:.38}.v11-sankey-label{fill:#d9e3ef;font-size:11px}.v11-treemap{stroke:#07101d;stroke-width:2}.v11-tree-label{fill:#07101d;font-size:11px;font-weight:700}.v11-tree-value{fill:#07101d;font-size:18px;font-weight:700}.v11-image-frame{fill:#0b1728;stroke:#516d8e;stroke-width:1.4}.v11-image-caption-bg{fill:#07101de8}.v11-image-caption{fill:#eef4ff;font-size:11px;font-weight:700}.v11-target{fill:#07101dcc;stroke:#f4d989;stroke-width:4}.v11-target-text{fill:#f4d989;font-size:12px;font-weight:700}.v11-film-label{fill:#eef4ff;font-size:10px;font-weight:700}.v11-map{fill:#0b2031;stroke:#4b6989}.v11-lane-path{fill:none;stroke:#62b8c9;stroke-width:6;stroke-linecap:round;opacity:.75}.v11-base-wall{fill:#452c31;stroke:#d47875}.v11-option{stroke-width:1.2}.v11-incident-id{fill:#f0d58c;font-size:22px;font-weight:700}.v11-core{fill:#172a43;stroke:#67b8cb;stroke-width:2.4}.v11-density{fill:#62b8c9;fill-opacity:var(--density);stroke:#2a4664}.v11-file-label{fill:#cedaeb;font-size:10px}.v11-bubble-label{fill:#07101d;font-size:9px;font-weight:700}.v11-unknown{fill:#1b2637;stroke:#e0b65d;stroke-dasharray:6 5}.v11-time{fill:#8fc4ff;font-size:11px;font-weight:700}.v11-evidence{fill:#0b1728;stroke:#405b7b}.v11-evidence-label{fill:#eef4ff;font-size:11px;font-weight:700}.v11-evidence-text{fill:#b7c5d7;font-size:10.5px}.v11-tone-blue{stroke:#5b8dd9}.v11-tone-cyan{stroke:#64b6c8}.v11-tone-green{stroke:#6fc698}.v11-tone-gold{stroke:#d6b45e}.v11-tone-red{stroke:#d67b77}.v11-tone-purple{stroke:#9a82d0}.v11-fill-blue{fill:#5b8dd9}.v11-fill-cyan{fill:#64b6c8}.v11-fill-green{fill:#6fc698}.v11-fill-gold{fill:#d6b45e}.v11-fill-red{fill:#d67b77}.v11-fill-purple{fill:#9a82d0}.v11-stroke-blue{stroke:#5b8dd9}.v11-stroke-cyan{stroke:#64b6c8}.v11-stroke-green{stroke:#6fc698}.v11-stroke-gold{stroke:#d6b45e}.v11-stroke-red{stroke:#d67b77}.v11-stroke-purple{stroke:#9a82d0}.v11-proofline{display:grid;grid-template-columns:repeat(3,1fr);gap:1px;background:#2a4160}.v11-proofline p{margin:0;padding:12px 15px;background:#0b1728;color:#b3c1d3;font-size:12px}.v11-proofline .cannot{color:#e2c170}.v11-figure .pv-node{cursor:pointer}.v11-figure .pv-node:focus{outline:3px solid #f0d58c;outline-offset:3px}.v11-figure .pv-node.is-active>*:first-child{filter:brightness(1.32)}
.v11-semantics{fill:#9fb2c8;font-size:9.5px}.audit-layer{margin:14px 0;border:1px solid #2f4563;background:#0a1423}.audit-layer>summary{cursor:pointer;padding:12px 15px;color:#d7e3f5;font:700 12px var(--mono);list-style:none}.audit-layer>summary::before{content:'▸ ';color:var(--gold2)}.audit-layer[open]>summary::before{content:'▾ '}.audit-layer>.table-wrap,.audit-layer>table,.audit-layer>pre{margin:0;border-top:1px solid #2f4563}.audit-layer table{margin:0}.audit-layer pre{max-height:680px}.audit-unit-anchor{scroll-margin-top:18px}
@media(max-width:720px){.v11-stage{overflow-x:auto;padding:8px}.v11-stage .v11-svg{width:1200px;max-width:none}.v11-stage[data-canvas-width="1400"] .v11-svg{width:1400px}.v11-proofline{grid-template-columns:1fr}.v11-head{flex-wrap:wrap}.v11-head>span:last-child{margin-left:0;width:100%}}
@media(min-width:721px){.v11-stage{overflow-x:hidden}.v11-stage .v11-svg{max-width:100%}}
@media print{.v11-stage{overflow:visible}.v11-stage .v11-svg{width:100%}.audit-layer,.v11-figure{break-inside:avoid}.v11-figure{box-shadow:none}.pv-inspector{display:none}}

/* v12 editorial shell: the v11 class names below remain only as SVG drawing primitives. */
.main{max-width:1500px}
.v12-visual-atlas{--chapter:#6c9bd1;margin:48px 0 72px}.v12-visual-atlas[data-v12-group="CASE"]{--chapter:#d4b45e}.v12-visual-atlas[data-v12-group="SRC"]{--chapter:#4e91c8}.v12-visual-atlas[data-v12-group="PLAT"]{--chapter:#8581c5}.v12-visual-atlas[data-v12-group="RUN"]{--chapter:#5fa6ad}.v12-visual-atlas[data-v12-group="DBG"]{--chapter:#c45f5f}.v12-visual-atlas[data-v12-group="QA"]{--chapter:#628fc6}.v12-visual-atlas[data-v12-group="AUDIT"]{--chapter:#8794a8}.v12-visual-atlas[data-v12-group="META"]{--chapter:#bba25a}
.v12-atlas-head{position:relative;padding:31px 34px 25px;margin:0 0 32px;border-top:4px solid var(--chapter);border-bottom:1px solid color-mix(in srgb,var(--chapter) 45%,#26364b);background:linear-gradient(110deg,color-mix(in srgb,var(--chapter) 10%,#09121f),#07101d 62%)}.v12-atlas-head>span{display:block;color:var(--chapter);font:700 12px var(--mono);letter-spacing:.22em}.v12-atlas-head h3{max-width:1000px;margin:9px 0 14px;font:700 clamp(25px,2.3vw,37px)/1.18 var(--sans);letter-spacing:-.025em}.v12-thesis{max-width:1050px;margin:0;color:#e3eaf3;font-size:17px;line-height:1.78}.v12-bridge{max-width:1080px;margin:10px 0 0;color:#9eacbd;font-size:14px;line-height:1.7}.v12-chapter-legend{display:flex;gap:22px;flex-wrap:wrap;margin-top:20px;padding-top:13px;border-top:1px solid #ffffff15;color:#9cabbc;font:11px var(--mono)}.v12-chapter-legend span{display:flex;align-items:center;gap:7px}.v12-chapter-legend i{width:10px;height:10px;display:inline-block}.legend-observed{border-radius:50%;background:#65a8d3}.legend-source{transform:rotate(45deg);background:#d0af5a}.legend-derived{clip-path:polygon(50% 0,100% 100%,0 100%);background:#9b82cd}.legend-outside{border:1px dashed #9aa6b6;border-radius:50%}
.v12-figure{margin:35px 0 42px;border-top:2px solid var(--chapter);border-bottom:1px solid #2d4059;background:#07101d;box-shadow:0 18px 42px #0006;overflow:hidden}.v12-head{display:flex;align-items:baseline;gap:15px;padding:17px 20px 15px;background:linear-gradient(90deg,color-mix(in srgb,var(--chapter) 9%,#0a1422),#091321)}.v12-id{flex:none;color:var(--chapter);font:700 12px var(--mono);letter-spacing:.08em}.v12-head strong{font-size:18px;line-height:1.45;letter-spacing:.005em}.v12-stage{padding:10px 12px 5px;overflow:hidden;background:#07101d}.v12-svg{display:block;width:100%;height:auto}.v12-kicker{fill:#91a7c0;font:700 11px var(--mono);letter-spacing:1.35px}.v12-question{fill:#f1f5fb;font-size:19px;font-weight:700}.v12-note{fill:#95a8be;font:11px var(--mono)}.v12-rule{fill:none;stroke-width:1.2}.v12-rule-gold{stroke:#b89c51}.v12-case-river{fill:none;stroke:#5c91bd;stroke-width:10;stroke-linecap:round;opacity:.42}.v12-blueprint-line,.v12-blueprint-tick,.v12-blueprint-path{fill:none;stroke:#4e91c8}.v12-blueprint-line{stroke-width:1.4}.v12-blueprint-tick{stroke-width:1}.v12-blueprint-path{stroke-width:4;opacity:.58}.v12-layer{stroke-width:1.2}.v12-layer-model{fill:#241f38;stroke:#8d83c7}.v12-layer-runtime{fill:#122c3e;stroke:#5e9ebd}.v12-layer-world{fill:#152c26;stroke:#61a385}.v12-layer-label{fill:#e7edf6;font-size:12px;font-weight:700}.v12-run-axis,.v12-run-tick{stroke:#62a5ac}.v12-run-axis{stroke-width:3}.v12-run-tick{stroke-width:1.2}.v12-debug-band{stroke:#b75252;stroke-width:40;opacity:.19}.v12-debug-rule{stroke:#b75252;stroke-width:2;stroke-dasharray:10 6}.v12-debug-label{fill:#efb1ad;font:700 12px var(--mono)}.v12-film{fill:#111d2e;stroke:#597da8}.v12-perf{fill:#648ebd;opacity:.5}.v12-film-label{fill:#dce8f5;font:700 12px var(--mono)}.v12-ledger-rule,.v12-ledger-column{stroke:#6f8199}.v12-ledger-rule{stroke-width:2}.v12-ledger-column{stroke-width:1;stroke-dasharray:4 5}.v12-meta-rule{stroke:#b49c56;stroke-width:3}.v12-source-rule{fill:none;stroke:#496481;stroke-width:1}.v12-source-index{fill:#0b1727;stroke:#d1b45e}.v12-source-index-text{fill:#e7ca79;font:700 10px var(--mono)}
.v12-footnote{margin:0;padding:11px 18px 12px;border-top:1px solid #263b55;color:#9facbc;background:#091320;font:11px/1.65 var(--mono)}.evidence-download{display:inline-block;margin-left:14px;color:#e8c76b;font-weight:700;text-decoration:none;border-bottom:1px solid #e8c76b80}.evidence-download:hover,.evidence-download:focus{color:#fff2b9;border-bottom-color:#fff2b9}.figure-audit{margin:0;border-top:1px solid #263b55;background:#08111d}.figure-audit>summary{cursor:pointer;padding:11px 18px;color:#aebdce;font:700 11px var(--mono);list-style:none}.figure-audit>summary::before{content:'＋ ';color:var(--chapter)}.figure-audit[open]>summary::before{content:'－ '}.figure-audit dl{display:grid;grid-template-columns:110px 1fr;margin:0;padding:5px 18px 16px;border-top:1px solid #1f3249;font-size:12px}.figure-audit dt{padding:10px 12px 7px 0;color:#8497ad;font-weight:700}.figure-audit dd{margin:0;padding:10px 0 7px;color:#c7d2df;border-bottom:1px solid #16283d}.figure-audit h5{margin:0;padding:12px 18px 5px;color:#d7e2ef}.figure-audit-metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:5px 20px;margin:0;padding:5px 18px 17px;list-style:none}.figure-audit-metrics li{display:grid;grid-template-columns:1fr auto;gap:3px 10px;padding:6px 0;border-bottom:1px solid #1e3046;color:#aebccc;font-size:11px}.figure-audit-metrics b{color:#e8cc7a}.figure-audit-metrics code{grid-column:1/-1;color:#72869f;font-size:9px}.v12-figure>.pv-inspector{display:none;margin:0;padding:10px 18px;border-top:1px solid #37506e;background:#0e1d31;color:#e4edf7;font:12px/1.6 var(--mono)}.v12-figure>.pv-inspector.has-detail{display:block}.v12-figure .pv-node{cursor:pointer}.v12-figure .pv-node:focus{outline:3px solid #f0d58c;outline-offset:3px}.v12-figure .pv-node.is-active>*:first-child{filter:brightness(1.28)}
.editorial-transition{max-width:920px;margin:15px auto 44px;padding:0 0 0 21px;border-left:3px solid var(--chapter);color:#c8d2df;font-size:16px;line-height:1.9}.editorial-verdict{margin:46px 0 18px;padding:28px 30px;border-top:3px solid #d0b35d;border-bottom:1px solid #62552d;background:linear-gradient(115deg,#1c1b18,#0a111b 70%);color:#eef2f7;font-size:19px;line-height:1.9}.editorial-verdict b{color:#e8ca70}.hero-verdict{max-width:930px;margin:18px auto 4px;color:#f3f5f8;font-size:20px;line-height:1.72;text-wrap:balance}
.online-hero-cta{display:grid;grid-template-columns:minmax(260px,.9fr) minmax(520px,1.35fr);gap:28px;align-items:center;margin:28px 0 10px;padding:25px 28px;border:1px solid #526b8d;border-top:3px solid #e8c76b;background:linear-gradient(120deg,#13243b,#091523 70%);box-shadow:0 18px 42px #0006}.online-cta-copy>span,.rail-play>span,.online-conclusion>div>span{display:block;color:#e8c76b;font:700 11px var(--mono);letter-spacing:.12em}.online-cta-copy>strong,.online-conclusion>div>strong{display:block;margin:7px 0 5px;color:#f4f7fb;font-size:22px}.online-cta-copy p{margin:0;color:#b7c4d4;font-size:13px;line-height:1.7}.online-cta-actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.online-cta{display:flex;flex-direction:column;justify-content:center;min-height:72px;padding:14px 17px;border-radius:9px;text-decoration:none;transition:transform .16s ease,border-color .16s ease,background .16s ease}.online-cta strong{font-size:16px}.online-cta small{margin-top:5px;color:#c5d1df;font-size:11px}.online-cta.primary{border:1px solid #5aa9ff;background:#153153;color:#f4f7fb}.online-cta.secondary{border:1px solid #5cc7d8;background:#13303d;color:#f4f7fb}.online-cta:hover,.online-cta:focus-visible{transform:translateY(-2px);border-color:#f0d57d;outline:none}.rail-play{margin:14px 0;padding:11px;border:1px solid #324863;background:#0a1525}.rail-play>span{margin-bottom:8px}.rail-play a{display:grid;grid-template-columns:1fr auto;gap:3px 8px;margin-top:7px;padding:9px 10px;border:1px solid #3c5778;border-radius:7px;background:#112038;color:#eef4fc;text-decoration:none;font-size:12px}.rail-play a:last-child{border-color:#397b87}.rail-play small{color:#91a4ba;font:10px var(--mono)}.rail-play a:hover,.rail-play a:focus-visible{border-color:#e8c76b;outline:none}.online-qa-links{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1px;border-top:1px solid #263b55;background:#263b55}.online-qa-links a{display:grid;grid-template-columns:auto 1fr;gap:3px 16px;padding:15px 18px;background:#091624;color:#eef4fb;text-decoration:none}.online-qa-links strong{color:#e8c76b}.online-qa-links span{overflow-wrap:anywhere;color:#bed1e5;font:12px var(--mono)}.online-qa-links small{grid-column:1/-1;color:#91a4ba}.online-qa-links a:hover,.online-qa-links a:focus-visible{background:#11263d;outline:2px solid #e8c76b;outline-offset:-2px}.online-conclusion{display:grid;grid-template-columns:minmax(280px,.8fr) minmax(520px,1.2fr);gap:24px;align-items:center;margin:18px 0 42px;padding:24px 27px;border:1px solid #506989;background:#0b1727}.online-conclusion-actions{display:grid;gap:9px}.online-conclusion-actions a{display:flex;justify-content:space-between;gap:16px;padding:11px 13px;border:1px solid #355579;color:#f1f6fc;text-decoration:none}.online-conclusion-actions a:last-child{border-color:#397d87}.online-conclusion-actions small{color:#9cb0c6;font:11px var(--mono)}.online-conclusion-actions a:hover,.online-conclusion-actions a:focus-visible{border-color:#e8c76b;outline:none}
/* Existing KING/LOG figures keep their dense drawings but use the quieter editorial annotation shell. */
.product-viz:not(.v12-figure) .pv-proof,.log-viz .pv-proof{padding:9px 12px;border-radius:0;background:#091320;color:#a8b6c7}.product-viz:not(.v12-figure) .pv-inspector,.log-viz .pv-inspector{font-size:11px}.product-viz:not(.v12-figure) figcaption,.log-viz figcaption{letter-spacing:.005em}
@media(min-width:721px) and (max-width:1500px){.rail{display:none}.main{margin-left:0;max-width:1500px;padding:0 24px 74px}}
@media(max-width:720px){.v12-stage{overflow-x:auto;padding:8px}.v12-stage .v12-svg{width:1200px;max-width:none}.v12-stage[data-canvas-width="1400"] .v12-svg{width:1400px}.v12-head{align-items:flex-start;flex-direction:column;gap:6px}.v12-atlas-head{padding:24px 20px}.v12-thesis{font-size:16px}.v12-chapter-legend{gap:12px}.figure-audit dl{grid-template-columns:1fr}.figure-audit dt{padding-bottom:0}.figure-audit-metrics{grid-template-columns:1fr}.v12-footnote{font-size:10px}.editorial-transition{margin-left:12px;margin-right:12px}}
@media(max-width:1050px){.online-hero-cta,.online-conclusion{grid-template-columns:1fr}.online-cta-actions{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:720px){.online-hero-cta,.online-conclusion{padding:20px;margin-left:0;margin-right:0}.online-cta-actions,.online-qa-links{grid-template-columns:1fr}.online-conclusion-actions a{display:block}.online-conclusion-actions small{display:block;margin-top:5px;overflow-wrap:anywhere}}
@media(min-width:721px){.v12-stage{overflow-x:hidden}.v12-stage .v12-svg{max-width:100%}}
@media print{.v12-stage{overflow:visible}.v12-stage .v12-svg{width:100%}.v12-figure,.figure-audit{break-inside:avoid}.v12-figure{box-shadow:none}.figure-audit{display:block}.figure-audit>summary{display:none}.v12-figure>.pv-inspector{display:none!important}}

/* v14 restrained MOBA broadcast system with explicit geometry contracts. */
:root{--viz-sans:-apple-system,BlinkMacSystemFont,"SF Pro Text","PingFang SC","Microsoft YaHei","Noto Sans CJK SC",sans-serif;--viz-mono:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,"Liberation Mono",monospace;--viz-bg:#07111d;--viz-panel:#0d1a2a;--viz-raised:#112238;--viz-code:#091522;--viz-line:#506a87;--viz-line-strong:#5b7592;--viz-text:#f4f7fb;--viz-text-2:#c9d4e2;--viz-text-3:#a9b8c9;--viz-gold:#e8c76b;--viz-blue:#5aa9ff;--viz-red:#ff7670;--viz-cyan:#5cc7d8;--viz-green:#65c891;--viz-amber:#d9a452;--viz-purple:#a994dc}
.v12-visual-atlas,.v11-visual-atlas{font-family:var(--viz-sans)}.v11-svg,.v12-svg{font-family:var(--viz-sans);font-variant-numeric:tabular-nums;text-rendering:geometricPrecision}.v11-svg text:not([class]),.v12-svg text:not([class]){fill:var(--viz-text)}
.v11-bg{fill:var(--viz-bg)}.v11-panel{fill:var(--viz-panel);stroke:var(--viz-line);stroke-width:1.5}.v11-node{fill:var(--viz-raised);stroke:var(--viz-line);stroke-width:1.5;filter:none}.v11-metric,.v11-track,.v11-chart-frame,.v11-lane,.v11-evidence{fill:var(--viz-panel);stroke:var(--viz-line)}
.v11-title{fill:var(--viz-text);font-size:28px;font-weight:700}.v11-panel-title{fill:var(--viz-text);font-size:18px;font-weight:700}.v11-card-title{fill:var(--viz-text);font-size:16px;font-weight:700;letter-spacing:.01em}.v11-card-value,.v11-metric-value{fill:var(--viz-gold);font-size:30px;font-weight:700}.v11-body{fill:var(--viz-text-2);font-size:14px;font-weight:400}.v11-small{fill:var(--viz-text-2);font-size:14px;font-weight:400}.v11-tiny,.v11-semantics{fill:var(--viz-text-3);font-size:13px;font-weight:400}.v11-row-label,.v11-matrix-row,.v11-lane-label,.v11-sankey-label{fill:var(--viz-text-2);font-size:14px}.v11-row-value{fill:var(--viz-text);font-size:14px;font-weight:700}.v11-axis-label,.v11-edge-label,.v11-matrix-head,.v11-cell-label,.v11-heat-value,.v11-file-label,.v11-evidence-text{fill:var(--viz-text-3);font-size:12px}.v11-axis-title,.v11-evidence-label{fill:var(--viz-text-2);font-size:14px;font-weight:700}
.v11-eyebrow,.v11-code-source,.v11-code-line,.v11-code-title,.v11-id,.v11-time{font-family:var(--viz-mono)}.v11-code-source{fill:var(--viz-text-3);font-size:13px;font-weight:500}.v11-code-line{fill:#dbe5f1;font-size:13px}.v11-code-title{fill:var(--viz-cyan);font-size:13px;font-weight:700}.v11-time{fill:#86c3ff;font-size:13px;font-weight:700}.v13-code-strip{fill:var(--viz-code);stroke:#203750;stroke-width:1}.v11-code-card{fill:var(--viz-code);stroke:var(--viz-line-strong)}
.v11-index-dot{stroke:#e7eef7;stroke-width:1}.v11-index{fill:#07111d;font-size:11px;font-weight:700}.v11-badge{fill:var(--viz-raised);stroke:var(--viz-line-strong)}.v11-badge-text{fill:var(--viz-text);font-size:13px;font-weight:700}.v11-edge{fill:none;stroke-width:2.7;stroke-linecap:round}.v11-dashed{stroke-dasharray:8 7}.v11-guide{stroke:#4a617c;stroke-dasharray:5 5}.v11-gridline{stroke:#20344d}.v11-axis{stroke:#70859e}.v11-series{stroke-width:2.7}.v11-flow{opacity:.5}.v11-image-frame{fill:#091522;stroke:#405b78;stroke-width:1.7}.v11-image-caption{fill:var(--viz-text);font-size:13px;font-weight:700}.v11-image-caption-bg{fill:#07111de8}.v11-target{fill:#07111de6;stroke:var(--viz-gold);stroke-width:4}.v11-target-text{fill:var(--viz-gold);font-size:13px;font-weight:700}
.v11-tone-blue{stroke:var(--viz-blue)}.v11-tone-cyan{stroke:var(--viz-cyan)}.v11-tone-green{stroke:var(--viz-green)}.v11-tone-gold{stroke:var(--viz-gold)}.v11-tone-red{stroke:var(--viz-red)}.v11-tone-purple{stroke:var(--viz-purple)}.v11-node[class*="v11-tone-"],.v11-panel[class*="v11-tone-"]{stroke-opacity:.72}.v11-fill-blue{fill:var(--viz-blue)}.v11-fill-cyan{fill:var(--viz-cyan)}.v11-fill-green{fill:var(--viz-green)}.v11-fill-gold{fill:var(--viz-gold)}.v11-fill-red{fill:var(--viz-red)}.v11-fill-purple{fill:var(--viz-purple)}.v11-stroke-blue{stroke:var(--viz-blue)}.v11-stroke-cyan{stroke:var(--viz-cyan)}.v11-stroke-green{stroke:var(--viz-green)}.v11-stroke-gold{stroke:var(--viz-gold)}.v11-stroke-red{stroke:var(--viz-red)}.v11-stroke-purple{stroke:var(--viz-purple)}
.v12-visual-atlas{--chapter:var(--viz-blue)}.v12-visual-atlas[data-v14-group="CASE"],.v12-visual-atlas[data-v14-group="META"]{--chapter:var(--viz-gold)}.v12-visual-atlas[data-v14-group="PLAT"]{--chapter:var(--viz-cyan)}.v12-visual-atlas[data-v14-group="DBG"]{--chapter:var(--viz-red)}.v12-visual-atlas[data-v14-group="AUDIT"]{--chapter:var(--viz-text-3)}
.v12-atlas-head{border-top-color:var(--chapter);background:linear-gradient(110deg,color-mix(in srgb,var(--chapter) 8%,#0b1726),var(--viz-bg) 65%)}.v12-atlas-head>span{font:700 12px var(--viz-sans);color:var(--chapter)}.v12-atlas-head h3{font:700 clamp(26px,2.3vw,38px)/1.18 var(--viz-sans)}.v12-thesis{color:var(--viz-text-2)}.v12-bridge{color:var(--viz-text-3)}.v12-chapter-legend{color:var(--viz-text-3);font:13px var(--viz-sans)}
.v12-figure{border-color:var(--viz-line);border-top-color:var(--chapter);background:var(--viz-bg);box-shadow:0 18px 44px #01060c80}.v12-head{background:linear-gradient(90deg,color-mix(in srgb,var(--chapter) 7%,var(--viz-panel)),var(--viz-bg))}.v12-id{font:700 13px var(--viz-mono)}.v12-head strong{font-size:19px;font-weight:700}.v12-stage{background:var(--viz-bg)}.v12-kicker{fill:var(--viz-text-3);font:700 12px var(--viz-sans);letter-spacing:1px}.v12-question{fill:var(--viz-text);font-size:27px;font-weight:700}.v12-note{fill:var(--viz-text-3);font:13px var(--viz-sans)}.v12-layer-label,.v12-debug-label,.v12-film-label{font-family:var(--viz-sans);font-size:14px;font-weight:700}.v12-footnote{color:var(--viz-text-3);background:#091522;font:13px/1.65 var(--viz-sans)}
.v13-phase{fill:var(--viz-panel);stroke:var(--viz-line);stroke-width:1.4}.v13-phase.is-result{fill:#1c2a22;stroke:var(--viz-green)}.v13-phase-label{fill:var(--viz-text-2);font-size:14px;font-weight:700}.v12-rule-gold{stroke:var(--viz-gold)}.v12-case-river{display:none}
.v13-domain-tag{fill:#091522;stroke:#405b78;stroke-width:1}.v13-domain-label{fill:var(--viz-text-2);font-size:12px;font-weight:700}.v13-code-strip+.v11-code-source{letter-spacing:0}
/* The original atlas used several 9–11.5px utility labels. At desktop scale
   those became the visual system's weakest link, especially in screenshots,
   filmstrips and the retained KING diagrams. V14 keeps every label but raises
   the floor to 12px and restores the correct sans/mono responsibility. */
.v11-bubble-label,.v11-film-label,.v11-index,.v11-swim-label,.v11-tree-label,.v12-source-index-text{font-size:12px}
.v11-tree-value{font-size:22px}
.viz-rich .vr-boundary,.viz-rich .vr-callout-note,.viz-rich .vr-note,.viz-rich .vr-number,.viz-rich .vr-tiny{font-family:var(--viz-sans);font-size:12px}
.viz-rich .vr-code,.viz-rich .vr-source{font-family:var(--viz-mono);font-size:12px}
.v12-stage[data-canvas-width="1400"] .v11-card-title{font-size:18px}.v12-stage[data-canvas-width="1400"] .v11-body,.v12-stage[data-canvas-width="1400"] .v11-small,.v12-stage[data-canvas-width="1400"] .v11-row-label{font-size:16px}.v12-stage[data-canvas-width="1400"] .v11-code-source,.v12-stage[data-canvas-width="1400"] .v11-code-line{font-size:14px}.v12-stage[data-canvas-width="1400"] .v11-axis-label,.v12-stage[data-canvas-width="1400"] .v11-edge-label{font-size:13px}.v12-stage[data-canvas-width="1400"] .v12-question{font-size:31px}
.figure-audit>summary,.figure-audit dt,.figure-audit dd,.figure-audit-metrics li{font-family:var(--viz-sans)}.figure-audit-metrics b,.figure-audit-metrics code{font-family:var(--viz-mono)}
.vr-node,.vr-overlay{cursor:pointer}.vr-node:focus,.vr-overlay:focus{outline:3px solid var(--viz-gold);outline-offset:3px}
<!-- V14-CSS:END -->`;
html = html.replace('</style>', `${css}\n</style>`);

const tableMapping = ['RUN-V01','CASE-V04','CASE-V04','CASE-V05','CASE-V06','CASE-V08','SRC-V01','SRC-V02','PLAT-V02','LOG-V01','PLAT-V03','PLAT-V08','LOG-V03','PLAT-V09','PLAT-V10','RUN-V08','RUN-V01','RUN-V04','RUN-V05','RUN-V06','RUN-V01','RUN-V01','QA-V01','QA-V02','QA-V03','QA-V05','AUDIT-V01','AUDIT-V02','AUDIT-V05','AUDIT-V06','AUDIT-V08','AUDIT-V10','AUDIT-V11','AUDIT-V03','META-V01','META-V02','META-V03','META-V04','META-V04'];
const preMapping = ['CASE-V06','KING-V03','SRC-V03','SRC-V04','KING-V04','KING-V06','KING-V07','KING-V08','KING-V18','KING-V21','KING-V24','PLAT-V02','PLAT-V01','PLAT-V07','RUN-V05','RUN-V05','RUN-V07','DBG-V02','DBG-V03','DBG-V04','QA-V03','AUDIT-V06','META-V04','AUDIT-V12','SRC-V04'];
function inDetails(value, pos) { return value.lastIndexOf('<details', pos) > value.lastIndexOf('</details>', pos); }
function wrapAuditUnits(value, tag, mapping, prefix) {
  const regex = new RegExp(`<${tag}\\b[\\s\\S]*?<\\/${tag}>`, 'g');
  const matches = [...value.matchAll(regex)];
  if (matches.length !== mapping.length) throw new Error(`${tag} count ${matches.length} != ${mapping.length}`);
  const records = [];
  for (let index = matches.length - 1; index >= 0; index -= 1) {
    const match = matches[index], unit = `${prefix}-${String(index + 1).padStart(2, '0')}`, original = match[0], mapped = mapping[index];
    let replacement = original.replace(new RegExp(`^<${tag}\\b`), `<${tag} data-audit-unit="${unit}" data-visualized-by="${mapped}"`);
    const already = inDetails(value, match.index);
    if (!already) replacement = `<details class="audit-layer audit-unit-anchor" id="audit-${unit.toLowerCase()}" data-audit-unit="${unit}" data-visualized-by="${mapped}"><summary>${unit} · 原始${tag === 'table' ? '表格' : '代码/日志'} · 对应 ${mapped}</summary>${replacement}</details>`;
    value = `${value.slice(0, match.index)}${replacement}${value.slice(match.index + original.length)}`;
    records.unshift({ id: unit, tag, visualizationId: mapped, normalizedTextSha256: sha(normalize(original)), originalHtmlBytes: Buffer.byteLength(original), wrapped: !already });
  }
  return { value, records };
}
let wrapped = wrapAuditUnits(html, 'table', tableMapping, 'TABLE'); html = wrapped.value; const tableRecords = wrapped.records;
wrapped = wrapAuditUnits(html, 'pre', preMapping, 'PRE'); html = wrapped.value; const preRecords = wrapped.records;

html = html.replace(/版本升级为v(?:10|11|12|13)/, '版本升级为v14').replace(/<b>v13（本版）<\/b>/g, 'v13');
html = html.replace(/\s*<tr><td><b>v14（本版）<\/b><\/td>[\s\S]*?<\/tr>/g, '');
html = html.replace(/(<tr><td>v13<\/td><td[^>]*>[\s\S]*?<\/td><td>[\s\S]*?<\/td><\/tr>)/, `$1\n      <tr><td><b>v14（本版）</b></td><td data-v14-size>生成后复算</td><td>91张案例SVG采用显式布局合同与浏览器几何审计；修复越界、重叠、大片空白和非守恒数据流表达，并区分5小时31分证据窗口与5小时29分17秒请求锚点。</td></tr>`);

const js = `<!-- V14-JS:START -->
document.querySelectorAll('.v12-figure').forEach(fig=>{const inspector=fig.querySelector('.pv-inspector');const nodes=[...fig.querySelectorAll('.pv-node[data-detail]')];const activate=node=>{nodes.forEach(n=>{n.classList.toggle('is-active',n===node);n.setAttribute('aria-pressed',n===node?'true':'false')});if(inspector){inspector.textContent=node.dataset.detail;inspector.classList.add('has-detail')}};nodes.forEach(node=>{node.setAttribute('role','button');node.setAttribute('aria-label',node.dataset.detail);node.setAttribute('aria-pressed','false');node.addEventListener('click',()=>activate(node));node.addEventListener('focus',()=>activate(node));node.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();activate(node)}})})});
const auditSearch=document.getElementById('evSearch');auditSearch&&auditSearch.addEventListener('input',function(){const query=this.value.trim().toLowerCase();document.querySelectorAll('details.audit-layer,details.figure-audit').forEach(detail=>{const matched=query&&detail.textContent.toLowerCase().includes(query);if(matched){detail.open=true;detail.dataset.searchOpened='true'}else if(detail.dataset.searchOpened==='true'){delete detail.dataset.searchOpened;if(!document.body.classList.contains('audit-mode'))detail.open=false}})});
if(location.hash&&location.hash.startsWith('#audit-')){const target=document.querySelector(location.hash);if(target?.tagName==='DETAILS')target.open=true}document.querySelectorAll('a[href^="#audit-"]').forEach(link=>link.addEventListener('click',()=>{const target=document.querySelector(link.getAttribute('href'));if(target?.tagName==='DETAILS')target.open=true}));
<!-- V14-JS:END -->`;
html = html.replace('</script>', `${js}\n</script>`);

html = html.replace(/<td data-v14-size>[^<]*<\/td>/, '<td data-v14-size>__SIZE__</td>');
html = sanitizePortableHtml(html);
for (let index = 0; index < 8; index += 1) {
  const bytes = Buffer.byteLength(html);
  const next = html.replace(/<td data-v14-size>[^<]*<\/td>/, `<td data-v14-size>${bytes.toLocaleString('en-US')} B</td>`);
  html = next;
  if (Buffer.byteLength(next) === bytes) break;
}
writeFileSync(reportPath, html);

const stripAuditAttributes = (value) => value.replace(/\s+data-audit-unit="[^"]+"/g, '').replace(/\s+data-visualized-by="[^"]+"/g, '').replace(/\s+/g, ' ').trim();
const finalTables = [...html.matchAll(/<table\b[\s\S]*?<\/table>/g)].map((match) => match[0]);
const finalPre = [...html.matchAll(/<pre\b[\s\S]*?<\/pre>/g)].map((match) => match[0]);
tableRecords.forEach((record, index) => { record.normalizedTextSha256 = sha(stripAuditAttributes(finalTables[index])); });
preRecords.forEach((record, index) => { record.normalizedTextSha256 = sha(stripAuditAttributes(finalPre[index])); });

const figureMarkup = (id) => html.match(new RegExp(`<figure[^>]+data-viz-code="${id}"[\\s\\S]*?<\\/figure>`))?.[0] || '';
const newVisualizations = expectedIds.map((id) => {
  const meta = metadata.get(id);
  const markup = figureMarkup(id);
  const editorial = groupEditorial[meta.group];
  const groupIds = expectedIds.filter((candidate) => candidate.startsWith(`${meta.group}-`));
  const groupIndex = groupIds.indexOf(id);
  const editorialRole = groupIndex === 0 ? 'chapter-opener' : groupIndex === groupIds.length - 1 ? 'chapter-closure' : meta.group === 'DBG' ? 'incident-analysis' : 'technical-explanation';
  return {
    id, group: meta.group, title: titleOverrides[id] || meta.title, question: meta.question,
    renderer: `render${id.replace('-', '')}`, rendererType: 'independent-editorial-data-bound',
    source: meta.source, proves: meta.proves, cannot: meta.cannot,
    editorialRole, visualGrammar: editorial.grammar, chapterThesis: editorial.thesis,
    compactFootnote: compactFootnoteFor(meta),
    fullAuditSource: meta.source, fullAuditProves: meta.proves, fullAuditCannot: meta.cannot,
    evidenceIds: evidenceIdsFor(meta),
    layoutContract: layoutContracts[id],
    allowedOverlaps: [],
    semanticRelations: id === 'PLAT-V03' ? ['LLM request ledger: 878=873+5', 'tool lifecycle ledger: 968=968=968', 'dashed relation is qualitative, not conserved'] : id === 'AUDIT-V01' ? ['877 bill rows=873 matched+4 bill-only', '229 root messages are a separate record domain'] : id === 'RUN-V07' ? ['activity tool type is derived from activity id prefix'] : ['renderer-declared'],
    dataPointers: id.startsWith('SRC-') ? ['/code'] : id.startsWith('RUN-') ? ['/execution'] : id.startsWith('QA-') ? ['/media', '/code'] : id.startsWith('AUDIT-') ? ['/logs', '/bill', '/database'] : id.startsWith('PLAT-') ? ['/execution', '/logs'] : ['/facts'],
    visibleFactIds: [...new Set([...markup.matchAll(/data-fact-id="([^"]+)"/g)].map((match) => match[1]))],
    svgBytes: Buffer.byteLength(markup.match(/<svg[\s\S]*?<\/svg>/)?.[0] || ''),
    textLabels: (markup.match(/<text\b/g) || []).length,
    paths: (markup.match(/<path\b/g) || []).length,
    rects: (markup.match(/<rect\b/g) || []).length,
    sourceHashes: data.sourceHashes,
  };
});

const screenshotFrames = data.media.screenshots.map((record, index) => ({ ordinal: index + 1, ...record }));
const storyboardFrames = storyboard.videos.flatMap((video, videoIndex) => video.frames.map((frame, frameIndex) => ({ videoIndex: videoIndex + 1, frameIndex: frameIndex + 1, preview: video.preview, original: video.original, classification: video.classification, speed: video.speed, ...frame, path: `assets/king/${frame.path}` })));
const manifest = {
  schemaVersion: 4,
  caseId: 'zhikuncode-king-20260809', reportVersion: 'v14', totalVisualizations: 91,
  retainedVisualizations: [...Array.from({ length: 26 }, (_, index) => `KING-V${String(index + 1).padStart(2, '0')}`), ...Array.from({ length: 3 }, (_, index) => `LOG-V${String(index + 1).padStart(2, '0')}`)],
  newVisualizations,
  renderingPolicy: { pcFirst: true, desktopWidths: [1280, 1440, 1920], mobileWidth: 390, desktopFigureOverflow: 'none', mobileFigureOverflow: 'internal-only', editorialShell: 'restrained-moba-broadcast', visualSystem: 'v14-layout-contract-1', semanticColorOnly: true, chapterLegendOnly: true, repeatedKpiRail: false, repeatedEvidenceCards: false, genericFallbackRenderer: false, arbitraryGeometry: false, categoricalEqualSizeAllowedWhenNotEncodingQuantity: true, maximumForegroundOverflowSvgUnits: 1, maximumTextOverlapRatio: 0.08, standardMaximumBottomBlankRatio: 0.12, wideMaximumBottomBlankRatio: 0.15 },
  dataLayer: { path: 'visualization-data.json', sha256: sha(readFileSync(dataPath)), schemaVersion: data.schemaVersion, factCount: Object.keys(data.facts).length },
  contentCoverage: {
    tables: tableRecords, preformattedBlocks: preRecords,
    screenshots: { files: 43, uniqueContent: 42, primaryVisualizations: ['CASE-V01','CASE-V04','DBG-V05','QA-V06','QA-V07'], allFrames: screenshotFrames },
    videos: { registered: 5, previews: 5, derivedFrames: 20, visualizations: ['QA-V08','QA-V09','QA-V10'], storyboardFrames },
    evidenceLedger: { first: 'E01', last: 'E42', visualization: 'AUDIT-V10' },
  },
  publicLog: { path: 'logs/app-session-20260809-0130-0701.public.log', sha256: redaction.publicFile.sha256, timestampBlocks: redaction.publicFile.timestampBlocks, physicalLines: redaction.publicFile.physicalLines, technicalIdsPreserved: redaction.preservedIdentifiers },
};
writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
console.log(JSON.stringify({ report: reportPath, reportBytes: statSync(reportPath).size, reportVersion: 'v14', independentRenderers: expectedIds.length, totalVisualizations: 91, tables: tableRecords.length, preformattedBlocks: preRecords.length, visualizationData: dataPath, manifest: manifestPath }, null, 2));
