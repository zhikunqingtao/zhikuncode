#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// V14 remains the data-bound SVG renderer. V15 is the editorial composition
// layer: it keeps every figure and audit unit, then changes pacing, material,
// hierarchy and motion without touching the underlying evidence geometry.
await import('./build-king-report-v14.mjs');

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const reportPath = join(repoRoot, 'docs/case-studies/zhikuncode开发王者荣耀.html');
const assetRoot = join(repoRoot, 'docs/case-studies/assets/king');
const manifestPath = join(assetRoot, 'visualization-manifest.json');
const sha256 = (value) => createHash('sha256').update(value).digest('hex');

let html = readFileSync(reportPath, 'utf8');
html = html
  .replace(/\n?<!-- V15-CSS:START -->[\s\S]*?<!-- V15-CSS:END -->\n?/g, '\n')
  .replace(/\n?\/\* V15-CSS:START \*\/[\s\S]*?\/\* V15-CSS:END \*\/\n?/g, '\n')
  .replace(/\n?<!-- V15-JS:START -->[\s\S]*?<!-- V15-JS:END -->\n?/g, '\n')
  .replace(/\n?<!-- V15-TOPBAR:START -->[\s\S]*?<!-- V15-TOPBAR:END -->\n?/g, '\n')
  .replace(/\n?<!-- V15-HERO-MEDIA:START -->[\s\S]*?<!-- V15-HERO-MEDIA:END -->\n?/g, '\n')
  .replace(/\sdata-v15-role="[^"]*"/g, '')
  .replace(/\sdata-v15-motion="[^"]*"/g, '')
  .replace(/document\.getElementById\('auditToggle'\)\.addEventListener\('click',function\(\)\{[\s\S]*?\n\}\);\n/, '')
  .replace(/<body[^>]*>/, '<body class="audit-mode v15-audit-open motion-enabled" data-report-version="v15">');

const topbar = `<!-- V15-TOPBAR:START -->
<div class="v15-topbar" aria-label="章节导航与阅读控制">
  <a class="v15-topbar-brand" href="#top"><i></i><span>ZHIKUNCODE</span><b>王者工程实录</b></a>
  <nav aria-label="章节快速导航">
    <a href="#part-0">交付</a><a href="#part-product">游戏系统</a><a href="#part-arch">运行时</a><a href="#part-3">编排</a><a href="#part-4">攻坚</a><a href="#part-5">验收</a><a href="#part-6">审计</a><a href="#part-7">结论</a>
  </nav>
  <button id="motionToggle" type="button" aria-pressed="false">暂停动效</button>
</div>
<!-- V15-TOPBAR:END -->`;
html = html.replace('<div id="progress"></div>', `<div id="progress"></div>\n${topbar}`);

const heroMedia = `<!-- V15-HERO-MEDIA:START -->
<div class="v15-hero-media" aria-label="最终产物云端运行监看窗">
  <div class="v15-broadcast-top"><span><i></i> CLOUD DEMO · LIVE BUILD</span><b>KING_OK</b></div>
  <div class="v15-hero-screen" id="v15HeroMediaSlot">
    <img src="assets/king/videos/storyboard-frames/05-02.jpg" alt="阿里云在线试玩录屏派生帧" loading="eager">
    <div class="v15-scanline" aria-hidden="true"></div>
    <div class="v15-team-score" aria-hidden="true"><span>BLUE</span><b>5 v 5</b><span>RED</span></div>
    <div class="v15-screen-corners" aria-hidden="true"></div>
  </div>
  <div class="v15-broadcast-bottom"><span>11:53 云端部署验证 · 窗口外材料</span><strong>选将 / 对线 / AI推进 / 技能 / HUD</strong></div>
</div>
<!-- V15-HERO-MEDIA:END -->`;
html = html.replace('  <!-- ONLINE-HERO:START -->', `  ${heroMedia}\n  <!-- ONLINE-HERO:START -->`);

const roles = {
  hero: ['CASE-V01','CASE-V04','KING-V01','KING-V05','KING-V12','KING-V16','KING-V20','KING-V25','PLAT-V01','PLAT-V03','RUN-V01','DBG-V01','DBG-V05','QA-V03','QA-V06','QA-V09','AUDIT-V01','AUDIT-V10','META-V01'],
  split: ['CASE-V05','CASE-V06','SRC-V03','SRC-V04','PLAT-V06','PLAT-V09','RUN-V02','RUN-V03','DBG-V02','DBG-V03','DBG-V04','QA-V08','QA-V10','AUDIT-V06','AUDIT-V08','AUDIT-V12','META-V03','META-V06'],
  filmstrip: ['QA-V06','QA-V07','QA-V08','QA-V09'],
  incident: ['DBG-V01','DBG-V02','DBG-V03','DBG-V04','DBG-V05','DBG-V06'],
  ledger: ['AUDIT-V01','AUDIT-V02','AUDIT-V03','AUDIT-V04','AUDIT-V05','AUDIT-V06','AUDIT-V07','AUDIT-V08','AUDIT-V09','AUDIT-V10','AUDIT-V11','AUDIT-V12'],
};
const roleFor = (id) => Object.entries(roles).filter(([, ids]) => ids.includes(id)).map(([role]) => role).join(' ') || 'standard';
html = html.replace(/data-viz-code="([A-Z]+-V\d{2})"/g, (_, id) => `data-viz-code="${id}" data-v15-role="${roleFor(id)}" data-v15-motion="reveal"`);

// The complete audit corpus is visible by default. The global button still
// allows readers to collapse it temporarily without deleting any material.
html = html.replace(/<details(?![^>]*\bopen\b)([^>]*)>/g, '<details open$1>');
html = html.replace('▸ 展开完整底稿模式', '▾ 收起完整底稿模式');

const css = `/* V15-CSS:START */
/* V15 · MOBA broadcast × engineering magazine */
x-v15-marker{}
html{
  --v15-ink:#060a12;--v15-ink-2:#09111d;--v15-paper:#0d1725;--v15-paper-2:#111e2e;
  --v15-white:#f7f9fc;--v15-silver:#c7d2df;--v15-muted:#8ea0b5;
  --v15-gold:#e7c66b;--v15-gold-soft:#8d7540;--v15-blue:#5da8ff;--v15-red:#ff7068;
  --v15-cyan:#58c4d5;--v15-green:#66c996;--v15-purple:#a58bd7;
  --v15-display:var(--disp);--v15-text:var(--disp);
}
html{scroll-behavior:smooth;background:var(--v15-ink)}
html,body{max-width:100%;overflow-x:clip}
body{background:
  radial-gradient(ellipse at 78% 0,#183a6238 0,transparent 34rem),
  linear-gradient(90deg,#050912 0,#07101b 47%,#050a13 100%);color:var(--v15-white)}
body:before{content:"";position:fixed;inset:0;pointer-events:none;z-index:-1;opacity:.18;background-image:linear-gradient(#ffffff05 1px,transparent 1px),linear-gradient(90deg,#ffffff04 1px,transparent 1px);background-size:48px 48px;mask-image:linear-gradient(to bottom,#000,transparent 76%)}
.main{max-width:1500px;padding-left:clamp(24px,3.4vw,58px);padding-right:clamp(24px,3.4vw,58px)}
.section{position:relative;isolation:isolate}.section>.part-intro{position:relative}
.section>.part-intro:after{content:attr(data-watermark);position:absolute;right:0;top:-30px;color:#fff;opacity:.025;font:700 180px/1 var(--v15-display);pointer-events:none}

/* Mid-width desktops keep navigation instead of losing the rail entirely. */
.v15-topbar{position:sticky;top:0;z-index:90;display:none;align-items:center;gap:18px;min-height:54px;margin:0;padding:0 22px;border-bottom:1px solid #61769366;background:#07111dee;backdrop-filter:blur(16px) saturate(1.3);box-shadow:0 10px 30px #0007}
.v15-topbar-brand{display:flex;align-items:center;gap:8px;color:var(--v15-white);text-decoration:none;white-space:nowrap}.v15-topbar-brand i{width:12px;height:12px;border:2px solid var(--v15-gold);transform:rotate(45deg)}.v15-topbar-brand span{color:var(--v15-gold);font:700 11px var(--mono);letter-spacing:.14em}.v15-topbar-brand b{font-size:12px}
.v15-topbar nav{display:flex;gap:3px;overflow:hidden;flex:1}.v15-topbar nav a{padding:8px 9px;border-radius:4px;color:#aebccc;text-decoration:none;font-size:12px;white-space:nowrap}.v15-topbar nav a:hover,.v15-topbar nav a:focus-visible{background:#1a2c43;color:#fff;outline:none}
.v15-topbar button,#motionToggle{border:1px solid #526984;background:#101e30;color:#d7e0eb;padding:8px 11px;border-radius:4px;font:700 11px var(--mono);cursor:pointer}

/* Cover spread */
#top{display:grid;grid-template-columns:minmax(0,1.08fr) minmax(430px,.92fr);gap:20px 36px;min-height:100vh;padding-top:22px}
#top>.hero-record{grid-column:1/-1}#top>.part-intro{grid-column:1;grid-row:2/4;align-self:center;max-width:none}
#top h1{font:700 clamp(52px,4.6vw,72px)/1.01 var(--v15-display);letter-spacing:-.05em;text-wrap:balance;margin-top:25px}#top h1 em{color:transparent;background:linear-gradient(96deg,#f3d782 4%,#dce6f2 53%,#86b8ff);background-clip:text;-webkit-background-clip:text}
#top .hero-verdict{margin-left:0;max-width:750px;font-size:21px;line-height:1.68}#top .lead{max-width:820px;font-size:16px;line-height:1.95;color:#c6d0de}
.v15-hero-media{grid-column:2;grid-row:2;align-self:end;overflow:hidden;border:1px solid #60799a;border-top:3px solid var(--v15-gold);background:#07101b;box-shadow:0 30px 80px #000b,0 0 70px #3979bd22;transform:perspective(1400px) rotateY(-1.3deg);transform-origin:right center}
.v15-broadcast-top,.v15-broadcast-bottom{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 14px;background:#0d1928;color:#aebdce;font:700 11px var(--mono);letter-spacing:.04em}.v15-broadcast-top i{display:inline-block;width:7px;height:7px;margin-right:7px;border-radius:50%;background:var(--v15-red);box-shadow:0 0 12px var(--v15-red)}.v15-broadcast-top b{color:var(--v15-green)}.v15-broadcast-bottom{align-items:flex-start;border-top:1px solid #2d415b}.v15-broadcast-bottom strong{color:#e7edf5;text-align:right}
.v15-hero-screen{position:relative;aspect-ratio:16/9;overflow:hidden;background:#050a12}.v15-hero-screen>img,.v15-hero-screen>video{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}.v15-hero-screen>video{z-index:2}.v15-hero-screen:after{content:"";position:absolute;inset:0;z-index:4;pointer-events:none;background:linear-gradient(90deg,#4b96ff18,transparent 35%,transparent 65%,#ff6e6816),linear-gradient(0deg,#03070d99,transparent 35%)}
.v15-scanline{position:absolute;z-index:5;left:0;right:0;height:2px;top:-5%;background:linear-gradient(90deg,transparent,#9ee7ffcc,transparent);box-shadow:0 0 18px #6dd9ff;animation:v15-scan 4.8s linear infinite;pointer-events:none}.v15-team-score{position:absolute;z-index:6;top:12px;left:50%;transform:translateX(-50%);display:flex;gap:8px;align-items:center;padding:6px 9px;background:#07101dcc;border:1px solid #8ca1b866;color:#dbe5f1;font:700 10px var(--mono)}.v15-team-score span:first-child{color:var(--v15-blue)}.v15-team-score span:last-child{color:var(--v15-red)}.v15-screen-corners{position:absolute;inset:12px;z-index:6;border:1px solid #ffffff1f;clip-path:polygon(0 0,18% 0,18% 2px,2px 2px,2px 18%,0 18%,0 0,100% 0,100% 18%,calc(100% - 2px) 18%,calc(100% - 2px) 2px,82% 2px,82% 0,100% 0,100% 100%,82% 100%,82% calc(100% - 2px),calc(100% - 2px) calc(100% - 2px),calc(100% - 2px) 82%,100% 82%,100% 100%,0 100%,0 82%,2px 82%,2px calc(100% - 2px),18% calc(100% - 2px),18% 100%)}
#top>.online-hero-cta{grid-column:2;grid-row:3;display:block;margin:0;padding:18px 20px;background:linear-gradient(125deg,#162943,#0a1625);box-shadow:0 20px 50px #0007}.online-cta-copy>strong{font-size:19px}.online-cta-actions{margin-top:14px}
#top>.metrics,#top>.grid2,#top>p.mini{grid-column:1/-1}.metrics{border-top:1px solid #a98d4c66;border-bottom:1px solid #42536a;background:linear-gradient(90deg,#161c24,#0a1421)}.metric{position:relative;background:transparent;border:0}.metric:after{content:"";position:absolute;right:0;top:18px;bottom:18px;width:1px;background:#ffffff17}.metric:last-child:after{display:none}.metric strong{font-size:29px;color:var(--v15-gold)}

/* Chapter materials and magazine pacing */
.v12-visual-atlas{position:relative;display:grid;grid-template-columns:repeat(12,minmax(0,1fr));column-gap:20px;margin:74px 0 108px;padding:0 0 36px}.v12-visual-atlas>*{grid-column:1/-1}.v12-visual-atlas:before{content:"";position:absolute;inset:-24px -18px 0;z-index:-1;pointer-events:none;border-radius:4px;background:var(--chapter-surface,#09131f);box-shadow:inset 0 1px #ffffff08}
.v12-visual-atlas[data-v14-group="CASE"]{--chapter-surface:radial-gradient(circle at 85% 2%,#59471d40,transparent 28%),linear-gradient(#0c1420,#07101a)}
.v12-visual-atlas[data-v14-group="SRC"]{--chapter-surface:linear-gradient(#071827,#07121f);background-image:linear-gradient(#70a7dc0b 1px,transparent 1px),linear-gradient(90deg,#70a7dc0b 1px,transparent 1px);background-size:32px 32px}
.v12-visual-atlas[data-v14-group="PLAT"]{--chapter-surface:radial-gradient(circle at 18% 4%,#7448a51d,transparent 32%),linear-gradient(#0a1723,#07101b)}
.v12-visual-atlas[data-v14-group="RUN"]{--chapter-surface:linear-gradient(120deg,#071923,#08121d)}
.v12-visual-atlas[data-v14-group="DBG"]{--chapter-surface:radial-gradient(circle at 92% 3%,#9e27251c,transparent 30%),linear-gradient(#160d13,#080f18)}
.v12-visual-atlas[data-v14-group="QA"]{--chapter-surface:linear-gradient(#0b111a,#07101a)}
.v12-visual-atlas[data-v14-group="AUDIT"]{--chapter-surface:linear-gradient(115deg,#141516,#0a1119 62%)}
.v12-visual-atlas[data-v14-group="META"]{--chapter-surface:radial-gradient(circle at 50% 0,#8a6b2524,transparent 34%),linear-gradient(#12130f,#080d14)}
.v12-atlas-head{min-height:230px;margin:0 0 42px;padding:46px 48px 38px;border-top-width:1px;border-left:5px solid var(--chapter);background:linear-gradient(112deg,color-mix(in srgb,var(--chapter) 13%,#111d2d),transparent 72%)}.v12-atlas-head>span{font-size:13px}.v12-atlas-head h3{max-width:1120px;margin-top:14px;font-size:clamp(34px,3.2vw,50px);letter-spacing:-.04em}.v12-thesis{max-width:1080px;font-size:18px}.v12-bridge{max-width:1060px;font-size:15px}
.v12-figure{position:relative;margin:30px 0 54px;border:0;border-top:1px solid color-mix(in srgb,var(--chapter) 72%,#fff);background:#07111d;box-shadow:0 26px 65px #0008,0 1px 0 #ffffff0b;overflow:clip}.v12-figure:before{content:"";position:absolute;inset:0;z-index:0;pointer-events:none;background:linear-gradient(135deg,color-mix(in srgb,var(--chapter) 5%,transparent),transparent 38%)}.v12-figure>*{position:relative}.v12-head{padding:20px 24px;background:linear-gradient(90deg,color-mix(in srgb,var(--chapter) 12%,#101c2c),#08111d)}.v12-head strong{font-size:21px}.v12-id{font-size:12px;border:1px solid color-mix(in srgb,var(--chapter) 55%,transparent);padding:4px 7px}.v12-stage{padding:16px 18px 8px}.v12-footnote{padding:13px 22px;font-size:12px;background:#0b1522}
.v12-figure[data-v15-role~="hero"]{margin-top:48px;box-shadow:0 36px 90px #000b,0 0 0 1px color-mix(in srgb,var(--chapter) 34%,transparent)}.v12-figure[data-v15-role~="hero"] .v12-head{padding-top:24px;padding-bottom:22px}.v12-figure[data-v15-role~="hero"] .v12-head strong{font-size:24px}
.v12-figure[data-v15-role~="filmstrip"]{background:#070b12}.v12-figure[data-v15-role~="filmstrip"] .v12-stage{padding:22px;background:linear-gradient(#05080d,#0b121d)}
.v12-figure[data-v15-role~="incident"]{border-left:3px solid #8d3435}.v12-figure[data-v15-role~="incident"] .v12-head{background:linear-gradient(90deg,#30151b,#0a121d 66%)}
.v12-figure[data-v15-role~="ledger"]{background:#101417}.v12-figure[data-v15-role~="ledger"] .v12-head{background:linear-gradient(90deg,#24231d,#0d141d)}
.editorial-transition{margin-top:34px;margin-bottom:52px;padding:16px 24px;border-left:0;border-top:1px solid color-mix(in srgb,var(--chapter) 65%,transparent);border-bottom:1px solid #ffffff10;background:linear-gradient(90deg,color-mix(in srgb,var(--chapter) 8%,#0b1522),transparent);font-size:17px}

/* Expanded audit pages are designed, not merely exposed. */
details{scroll-margin-top:76px}.audit-layer,.figure-audit{border:0;border-top:1px solid #435269;background:linear-gradient(120deg,#0d1622,#09111b);box-shadow:inset 4px 0 color-mix(in srgb,var(--chapter,var(--v15-gold)) 55%,transparent)}
.audit-layer>summary,.figure-audit>summary{position:sticky;top:54px;z-index:5;padding:13px 18px;background:#0e1927f2;backdrop-filter:blur(12px);font-family:var(--v15-text);font-size:12px;letter-spacing:.02em}.audit-layer[open]>summary,.figure-audit[open]>summary{color:#e9d28e}
.audit-layer table,.figure-audit table{font-size:13px}.audit-layer thead th{position:sticky;top:97px;z-index:4;background:#152236}.audit-layer td,.audit-layer th{border-color:#2b3d54;padding:10px 12px}.audit-layer tbody tr:nth-child(even){background:#ffffff025}.audit-layer pre{max-height:none;border-radius:0;background:#07111b;box-shadow:inset 4px 0 #3d698c;color:#d8e4f1;line-height:1.72}.figure-audit dl{grid-template-columns:140px 1fr}.figure-audit-metrics{grid-template-columns:repeat(3,minmax(0,1fr))}
#auditToggle{border-radius:4px!important;background:#171d22!important;border-color:#8b7440!important}

/* Reveal and broadcast motion. */
.motion-enabled .v12-figure,.motion-enabled .v12-atlas-head,.motion-enabled .section>.part-intro{opacity:.01;transform:translateY(34px);transition:opacity .72s cubic-bezier(.2,.7,.2,1),transform .72s cubic-bezier(.2,.7,.2,1)}
.motion-enabled #top>.part-intro{opacity:1;transform:none}
.motion-enabled .is-revealed{opacity:1;transform:none}.motion-enabled .v12-figure.is-revealed{transition-duration:.82s}.motion-enabled .v15-trace{stroke-dasharray:var(--trace-length);stroke-dashoffset:var(--trace-length);animation:v15-trace 1.25s .24s ease-out forwards}
@keyframes v15-scan{0%{top:-5%;opacity:0}8%{opacity:.85}90%{opacity:.45}100%{top:105%;opacity:0}}@keyframes v15-trace{to{stroke-dashoffset:0}}
body.motion-paused *,body.motion-paused *:before,body.motion-paused *:after{animation-play-state:paused!important;transition:none!important}.motion-paused .v12-figure,.motion-paused .v12-atlas-head,.motion-paused .section>.part-intro{opacity:1;transform:none}

@media(min-width:1601px){.v12-figure[data-v15-role~="split"]{display:grid;grid-template-columns:minmax(0,1fr) 310px;align-items:start}.v12-figure[data-v15-role~="split"]>.v12-head{grid-column:1/-1}.v12-figure[data-v15-role~="split"]>.v12-stage{grid-column:1;grid-row:2/6}.v12-figure[data-v15-role~="split"]>.v12-footnote,.v12-figure[data-v15-role~="split"]>.pv-inspector,.v12-figure[data-v15-role~="split"]>.figure-audit,.v12-figure[data-v15-role~="split"]>.online-qa-links{grid-column:2}.v12-figure[data-v15-role~="split"]>.figure-audit{align-self:stretch;border-left:1px solid #354a64}.v12-figure[data-v15-role~="split"]>.online-qa-links{grid-template-columns:1fr}}
@media(min-width:721px) and (max-width:1600px){.v15-topbar{display:flex}.main{padding-top:0}.audit-layer>summary,.figure-audit>summary{top:54px}}
@media(max-width:1100px){#top{grid-template-columns:1fr;min-height:0}#top>.part-intro,#top>.v15-hero-media,#top>.online-hero-cta{grid-column:1;grid-row:auto}.v15-hero-media{transform:none}.v15-topbar nav{display:none}}
@media(max-width:720px){.v15-topbar{display:flex;margin:0;padding:0 12px}.v15-topbar-brand b{display:none}.v15-topbar nav{display:none}.main{padding-left:16px;padding-right:16px}#top{padding-top:12px}#top h1{font-size:43px;line-height:1.06}.v12-visual-atlas{display:block;margin-top:52px;margin-bottom:72px}.v12-atlas-head{min-height:0;padding:30px 22px}.v12-atlas-head h3{font-size:33px}.v12-figure{margin-bottom:42px}.figure-audit-metrics{grid-template-columns:1fr}.audit-layer>summary,.figure-audit>summary{top:54px}}
@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}.motion-enabled .v12-figure,.motion-enabled .v12-atlas-head,.motion-enabled .section>.part-intro{opacity:1;transform:none;transition:none}.v15-scanline,.v15-trace{animation:none!important}}
@media print{.v15-topbar,.v15-scanline,.v15-team-score,.v15-screen-corners{display:none!important}#top{display:block;min-height:0}.v15-hero-media{transform:none;box-shadow:none}.motion-enabled .v12-figure,.motion-enabled .v12-atlas-head,.motion-enabled .section>.part-intro{opacity:1;transform:none}.audit-layer>summary,.figure-audit>summary,.audit-layer thead th{position:static}.v12-visual-atlas:before{display:none}}
/* V15-CSS:END */`;
const styleEnd = html.lastIndexOf('</style>');
if (styleEnd < 0) throw new Error('Report style end not found');
html = `${html.slice(0, styleEnd)}${css}\n${html.slice(styleEnd)}`;

const js = `<!-- V15-JS:START -->
(() => {
  const root = document.body;
  const motionButton = document.getElementById('motionToggle');
  const auditButton = document.getElementById('auditToggle');
  const allAudits = [...document.querySelectorAll('details')];
  root.classList.add('audit-mode','v15-audit-open','motion-enabled');
  allAudits.forEach(detail => { detail.open = true; });
  if (auditButton) {
    auditButton.textContent = '▾ 收起完整底稿模式';
    auditButton.addEventListener('click', () => {
      const willOpen = !root.classList.contains('audit-mode');
      root.classList.toggle('audit-mode', willOpen);
      root.classList.toggle('v15-audit-open', willOpen);
      allAudits.forEach(detail => { detail.open = willOpen; });
      auditButton.textContent = willOpen ? '▾ 收起完整底稿模式' : '▸ 展开完整底稿模式';
    });
  }

  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const revealTargets = [...document.querySelectorAll('.section>.part-intro,.v12-atlas-head,.v12-figure')];
  const reveal = element => {
    element.classList.add('is-revealed');
    if (!reduceMotion && !root.classList.contains('motion-paused')) {
      [...element.querySelectorAll('path.v11-edge,path.v12-blueprint-path,path.v11-series')].slice(0, 24).forEach(path => {
        try { const length = Math.ceil(path.getTotalLength()); path.style.setProperty('--trace-length', length); path.classList.add('v15-trace'); } catch {}
      });
    }
  };
  if ('IntersectionObserver' in window && !reduceMotion) {
    const observer = new IntersectionObserver(entries => entries.forEach(entry => { if (entry.isIntersecting) { reveal(entry.target); observer.unobserve(entry.target); } }), { rootMargin: '0px 0px -8% 0px', threshold: .06 });
    revealTargets.forEach(target => observer.observe(target));
  } else revealTargets.forEach(reveal);

  const cloudVideo = document.querySelector('video[src*="05-阿里云在线试玩-2x.mp4"]');
  const heroSlot = document.getElementById('v15HeroMediaSlot');
  if (cloudVideo && heroSlot) {
    const origin = cloudVideo.parentElement;
    const marker = document.createElement('a');
    marker.className = 'v15-video-relocated'; marker.href = '#top'; marker.textContent = '云端试玩预览正在卷首监看窗播放 ↑';
    origin.insertBefore(marker, cloudVideo);
    cloudVideo.removeAttribute('controls'); cloudVideo.muted = true; cloudVideo.loop = true; cloudVideo.playsInline = true; cloudVideo.autoplay = true; cloudVideo.preload = 'metadata';
    heroSlot.appendChild(cloudVideo);
    if (!reduceMotion) cloudVideo.play().catch(() => {});
    const videoObserver = new IntersectionObserver(entries => entries.forEach(entry => {
      if (entry.isIntersecting && !root.classList.contains('motion-paused') && !reduceMotion) cloudVideo.play().catch(() => {}); else cloudVideo.pause();
    }), { threshold: .08 });
    videoObserver.observe(heroSlot);
  }
  if (motionButton) motionButton.addEventListener('click', () => {
    const paused = root.classList.toggle('motion-paused');
    motionButton.setAttribute('aria-pressed', paused ? 'true' : 'false');
    motionButton.textContent = paused ? '继续动效' : '暂停动效';
    document.querySelectorAll('video').forEach(video => paused ? video.pause() : (video === cloudVideo && video.play().catch(() => {})));
    if (!paused) revealTargets.forEach(target => { if (target.getBoundingClientRect().top < innerHeight) reveal(target); });
  });
})();
<!-- V15-JS:END -->`;
const scriptEnd = html.lastIndexOf('</script>');
if (scriptEnd < 0) throw new Error('Report script end not found');
html = `${html.slice(0, scriptEnd)}${js}\n${html.slice(scriptEnd)}`;

// Keep the generated artifact diff-clean without changing any visible content.
html = html.replace(/[ \t]+$/gm, '');
writeFileSync(reportPath, html);

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
manifest.schemaVersion = 5;
manifest.reportVersion = 'v15';
manifest.renderingPolicy = {
  ...manifest.renderingPolicy,
  editorialShell: 'moba-broadcast-engineering-magazine',
  visualSystem: 'v15-editorial-spread-1',
  auditDefaultOpen: true,
  motion: 'viewport-reveal-broadcast-trace',
  reducedMotion: true,
  externalRuntimeAssets: false,
  contentReductionAllowed: false,
};
for (const entry of manifest.newVisualizations) {
  entry.presentationRole = roleFor(entry.id);
  entry.spreadGroup = entry.id.split('-')[0];
  entry.chapterMaterial = {
    CASE:'broadcast-gold',SRC:'canyon-blueprint',PLAT:'runtime-cutaway',RUN:'teal-run-track',DBG:'incident-redline',QA:'contact-sheet',AUDIT:'graphite-ledger',META:'obsidian-verdict',
  }[entry.group];
  entry.motionSequence = 'reveal-once';
  entry.desktopComposition = entry.presentationRole.includes('split') ? 'full-until-1600-then-8x4' : 'full-width';
  entry.auditDefaultOpen = true;
  entry.contentPreservationHash = sha256(`${entry.source}\n${entry.proves}\n${entry.cannot || ''}`);
}
manifest.v15 = {
  heroMedia: 'videos/previews/05-阿里云在线试玩-2x.mp4',
  heroPoster: 'videos/storyboard-frames/05-02.jpg',
  navigation: 'sticky-midwidth-and-rail-wide',
  auditUnitsDefaultOpen: manifest.contentCoverage.tables.length + manifest.contentCoverage.preformattedBlocks.length,
  preservedCounts: { visualizations: 91, tables: 39, preformattedBlocks: 25, screenshots: 43, storyboardFrames: 20, previewVideos: 5 },
};
writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

console.log(JSON.stringify({
  report: reportPath,
  reportBytes: statSync(reportPath).size,
  reportVersion: 'v15',
  visualizations: 91,
  tables: 39,
  preformattedBlocks: 25,
  manifestSchemaVersion: 5,
}, null, 2));
