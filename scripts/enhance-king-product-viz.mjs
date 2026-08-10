#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const repo = resolve(import.meta.dirname, '..');
const reportPath = resolve(repo, 'docs/case-studies/zhikuncode开发王者荣耀.html');

const esc = (value) => String(value)
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const prefix = (id) => id.toLowerCase().replace('king-', 'k').replace('-', '');
const defs = (id) => {
  const p = prefix(id);
  return `<defs>
    <pattern id="${p}-grid" width="24" height="24" patternUnits="userSpaceOnUse"><path d="M24 0H0V24" fill="none" stroke="#20334f" stroke-width=".7"/></pattern>
    <marker id="${p}-arrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0 0L9 4.5 0 9Z" fill="#6d82a7"/></marker>
    <marker id="${p}-data" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0 0L9 4.5 0 9Z" fill="#6bb8c9"/></marker>
    <marker id="${p}-event" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0 0L9 4.5 0 9Z" fill="#d3b36a"/></marker>
    <filter id="${p}-glow" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="4" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
  </defs>`;
};
const svg = (id, title, desc, body, { height = 760, overlay = false } = {}) => {
  const p = prefix(id);
  const cls = overlay ? 'viz-rich vr-overlay' : 'pv-svg viz-rich';
  return `<svg class="${cls}" data-viz-rich="true" viewBox="0 0 1200 ${height}" role="img" aria-labelledby="${p}-title ${p}-desc"><title id="${p}-title">${esc(title)}</title><desc id="${p}-desc">${esc(desc)}</desc>${defs(id)}${overlay ? '' : `<rect width="1200" height="${height}" fill="#07101d"/><rect width="1200" height="${height}" fill="url(#${p}-grid)" opacity=".36"/>`}${body}</svg>`;
};
const node = ({ x, y, w, h, title, lines = [], code = '', type = 'blue', detail = '', tag = '', value = '' }) => {
  const panel = { blue: 'vr-panel-blue', red: 'vr-panel-red', gold: 'vr-panel-gold', green: 'vr-panel-green', cyan: 'vr-panel-cyan', muted: 'vr-panel-muted' }[type] ?? 'vr-panel';
  const textX = x + 16;
  const compact = h < 80;
  const tagY = y + (compact ? 14 : 18);
  const titleY = y + (tag ? (compact ? 31 : 38) : (compact ? 17 : 27));
  const lineStart = y + (tag ? (compact ? 47 : 56) : (compact ? 34 : 53));
  const lineStep = compact ? 15 : 17;
  const codeY = y + h - (compact ? 7 : 12);
  const lineMarkup = lines.map((line, index) => `<text class="${index === 0 ? 'vr-note' : 'vr-tiny'}" x="${textX}" y="${lineStart + index * lineStep}">${esc(line)}</text>`).join('');
  return `<g class="pv-node vr-node" tabindex="0" data-detail="${esc(detail || `${code || title}：${lines.join('；')}`)}"><rect class="vr-panel ${panel}" x="${x}" y="${y}" width="${w}" height="${h}" rx="10"/>${tag ? `<text class="vr-section" x="${textX}" y="${tagY}">${esc(tag)}</text>` : ''}<text class="vr-label" x="${textX}" y="${titleY}">${esc(title)}</text>${value ? `<text class="vr-value" x="${x + w - 16}" y="${y + 28}" text-anchor="end">${esc(value)}</text>` : ''}${lineMarkup}${code ? `<text class="vr-code" x="${textX}" y="${codeY}">${esc(code)}</text>` : ''}</g>`;
};
const edge = (id, d, { type = 'normal', label = '', x = 0, y = 0, arrow = true } = {}) => {
  const p = prefix(id);
  const cls = { normal: 'vr-edge', data: 'vr-edge-data', event: 'vr-edge-event', feedback: 'vr-edge-feedback', muted: 'vr-edge-muted' }[type];
  const marker = arrow ? ` marker-end="url(#${p}-${type === 'data' ? 'data' : type === 'event' ? 'event' : 'arrow'})"` : '';
  return `<path class="${cls}"${marker} d="${d}"/>${label ? `<text class="vr-tiny" x="${x}" y="${y}">${esc(label)}</text>` : ''}`;
};
const metric = (x, y, label, value, max, width = 250, color = '#5b8dd9') => `<g><text class="vr-note" x="${x}" y="${y}">${esc(label)}</text><text class="vr-value" x="${x + width}" y="${y}" text-anchor="end">${esc(value)}</text><rect class="vr-metric-track" x="${x}" y="${y + 10}" width="${width}" height="10" rx="5"/><rect x="${x}" y="${y + 10}" width="${Math.max(2, width * Number(value.toString().replaceAll(',', '')) / max).toFixed(1)}" height="10" rx="5" fill="${color}"/></g>`;
const sourceRail = (sources, boundary, y = 704) => `<g><rect class="vr-source-rail" x="24" y="${y}" width="1152" height="42" rx="8"/><text class="vr-section" x="42" y="${y + 17}">FROZEN SOURCE</text><text class="vr-source" x="160" y="${y + 17}">${esc(sources.join('  ·  '))}</text><text class="vr-boundary" x="42" y="${y + 34}">BOUNDARY  ${esc(boundary)}</text></g>`;
const titleBand = (eyebrow, title, subtitle) => `<g><text class="vr-section" x="28" y="30">${esc(eyebrow)}</text><text class="vr-title" x="28" y="56">${esc(title)}</text><text class="vr-subtitle" x="28" y="77">${esc(subtitle)}</text><path class="vr-divider" d="M28 91H1172"/></g>`;
const callout = ({ n, x, y, w = 238, targetX, targetY, title, code, note, tone = 'gold', observed = true }) => {
  const cls = tone === 'blue' ? 'vr-callout-blue' : tone === 'red' ? 'vr-callout-red' : 'vr-callout';
  const leader = tone === 'blue' ? 'vr-leader-blue' : 'vr-leader';
  const startX = x < targetX ? x + w : x;
  const startY = y + 34;
  const midX = (startX + targetX) / 2;
  return `<g class="pv-node vr-node" tabindex="0" data-detail="${esc(`${code}：${note}${observed ? '；截图直接可见与代码职责已分层标记。' : '；该规则来自源码，不由单帧截图独立证明。'}`)}"><path class="${leader}" d="M${startX} ${startY}H${midX}V${targetY}H${targetX}"/><circle class="${tone === 'red' ? 'vr-target-red' : tone === 'blue' ? 'vr-target-blue' : 'vr-target'}" cx="${targetX}" cy="${targetY}" r="17"/><circle cx="${targetX}" cy="${targetY}" r="5" fill="${tone === 'blue' ? '#8fc1ff' : tone === 'red' ? '#ff9eaa' : '#f1d78c'}"/><rect class="${cls}" x="${x}" y="${y}" width="${w}" height="69" rx="8"/><circle cx="${x + 17}" cy="${y + 17}" r="10" fill="${observed ? '#7cc79b' : '#d3b36a'}"/><text class="vr-number" x="${x + 17}" y="${y + 21}" text-anchor="middle">${n}</text><text class="vr-callout-title" x="${x + 34}" y="${y + 19}">${esc(title)}</text><text class="vr-callout-code" x="${x + 12}" y="${y + 40}">${esc(code)}</text><text class="vr-callout-note" x="${x + 12}" y="${y + 57}">${esc(note)}</text></g>`;
};
const overlaySvg = (id, title, desc, callouts, legend = '● 绿点=画面直接可见   ● 金点=源码规则对应，不由单帧独立证明') => svg(id, title, desc, `${callouts.map(callout).join('')}<g><rect x="352" y="742" width="496" height="34" rx="7" fill="#06101bdd" stroke="#405a80"/><text class="vr-callout-note" x="374" y="764">${esc(legend)}</text></g>`, { height: 800, overlay: true });

const figures = {};

figures['KING-V01'] = overlaySvg('KING-V01', '最终产物全景：一帧画面中的实时系统', '九个源码标注把截图中的小地图、英雄、兵线、血条、技能、经济、特效与固定步长状态联系起来。', [
  { n:1,x:18,y:28,targetX:110,targetY:115,title:'战术压缩视图',code:'ui/minimap.js:update',note:'200px Canvas · 0.25s节流',tone:'blue' },
  { n:2,x:18,y:112,targetX:638,targetY:332,title:'五名可见英雄',code:'models.js:createHeroModel',note:'程序化轮廓+阵营圈',tone:'gold' },
  { n:3,x:18,y:196,targetX:630,targetY:442,title:'三路兵线交战',code:'ai.js:updateMinion',note:'索敌·脱离·推进',tone:'blue' },
  { n:4,x:18,y:280,targetX:610,targetY:245,title:'3D世界血条',code:'hud.js:updateWorldBars',note:'world→screen DOM投影',tone:'red' },
  { n:5,x:944,y:28,targetX:641,targetY:380,title:'共享战场状态',code:'state.js:GameState',note:'单位·战斗·经济·终局',tone:'gold',observed:false },
  { n:6,x:944,y:112,targetX:1160,targetY:660,title:'技能与普攻输入',code:'hud→main→queueAction',note:'指针矢量在30Hz消费',tone:'blue' },
  { n:7,x:944,y:196,targetX:650,targetY:386,title:'粒子与弹道反馈',code:'vfx.js:update / burst',note:'对象池每帧复用',tone:'red' },
  { n:8,x:944,y:280,targetX:646,targetY:758,title:'等级金币与装备',code:'state.js + shop.js + hud.js',note:'收益反向改写面板',tone:'blue' },
  { n:9,x:944,y:364,targetX:820,targetY:520,title:'双时钟调度',code:'main.js:loop',note:'30Hz逻辑 + rAF渲染',tone:'gold',observed:false },
]);

figures['KING-V02'] = svg('KING-V02', '十二个运行子系统的数据、命令与反馈图', '区分顶层装配、共享状态、固定逻辑、逐帧表现和界面回调，并直接标出冻结源码符号。', `${titleBand('RUNTIME COUPLING','12个子系统不是并排页面——它们共享同一场战斗','实线=调用  青线=状态数据  虚线=事件/反馈；节点可聚焦查看冻结源码职责')}
  ${node({x:475,y:105,w:250,h:92,title:'main.js 装配根',tag:'BOOT + LOOP',lines:['boot() 创建顶层对象','loop() 分流逻辑/渲染'],code:'src/main.js:26–151',type:'gold',detail:'main.js创建Renderer、Input、Map、VFX、HUD、Minimap、Screens、Audio和GameState，并驱动双时钟主循环。'})}
  ${node({x:455,y:260,w:290,h:118,title:'GameState 事实中心',tag:'SHARED STATE',lines:['units / towers / crystals / time','damage / death / economy / winner'],code:'game/state.js:GameState',type:'red',value:'1,306 L'})}
  ${node({x:35,y:115,w:205,h:82,title:'Input',lines:['键盘·指针·摇杆'],code:'engine/input.js',type:'blue'})}
  ${node({x:35,y:242,w:205,h:82,title:'HUD',lines:['血条·CD·技能矢量'],code:'ui/hud.js',type:'cyan'})}
  ${node({x:35,y:369,w:205,h:82,title:'Minimap',lines:['world→200px Canvas'],code:'ui/minimap.js',type:'cyan'})}
  ${node({x:35,y:496,w:205,h:82,title:'Screens',lines:['选将·商店·结算'],code:'ui/screens.js',type:'blue'})}
  ${node({x:270,y:470,w:205,h:82,title:'HeroAI',lines:['9名AI·9种mode'],code:'game/ai.js:HeroAI',type:'red'})}
  ${node({x:500,y:470,w:205,h:82,title:'SkillSystem',lines:['15技能·弹道·Buff'],code:'game/skills.js',type:'red'})}
  ${node({x:730,y:470,w:205,h:82,title:'Spawner / Shop',lines:['兵线营地·金币装备'],code:'spawner.js / shop.js',type:'green'})}
  ${node({x:960,y:115,w:205,h:82,title:'Renderer',lines:['scene·camera·WebGL'],code:'engine/renderer.js',type:'blue'})}
  ${node({x:960,y:242,w:205,h:82,title:'Map',lines:['180×180·碰撞·环境'],code:'world/map.js',type:'green'})}
  ${node({x:960,y:369,w:205,h:82,title:'Models',lines:['英雄·小兵·野怪'],code:'world/models.js',type:'green'})}
  ${node({x:960,y:496,w:205,h:82,title:'VFX / Audio',lines:['对象池·WebAudio·TTS'],code:'vfx.js / audio.js',type:'gold'})}
  ${edge('KING-V02','M240 155H475',{label:'move vector / input',x:290,y:147})}${edge('KING-V02','M240 283H455',{type:'event',label:'queueAction',x:300,y:275})}${edge('KING-V02','M455 324H240V410',{type:'data',label:'state snapshot',x:260,y:400})}${edge('KING-V02','M455 345H240V537',{type:'data',label:'hero/result/shop',x:260,y:525})}${edge('KING-V02','M555 378V470',{label:'ai.update(dt)',x:565,y:430})}${edge('KING-V02','M625 378V470',{label:'skills.update(dt)',x:636,y:430})}${edge('KING-V02','M700 378L820 470',{label:'spawn/economy',x:745,y:420})}${edge('KING-V02','M725 151H960',{label:'render frame',x:800,y:143})}${edge('KING-V02','M745 295H960',{type:'data',label:'colliders / environment',x:785,y:286})}${edge('KING-V02','M745 335L960 410',{type:'data',label:'syncModel',x:820,y:360})}${edge('KING-V02','M745 350L960 537',{type:'event',label:'events → feedback',x:820,y:445})}
  <g><rect class="vr-source-rail" x="270" y="590" width="665" height="75" rx="9"/><text class="vr-section" x="290" y="612">COUPLING CONTRACT</text><text class="vr-note" x="290" y="636">main负责生命周期；GameState保有玩法真值；UI和表现层从状态读取并通过回调写入命令。</text><text class="vr-code" x="290" y="657">new GameState(...)  ·  state.update(LOOP.STEP, move)  ·  minimap.update(state, dt)</text></g>
  ${sourceRail(['src/main.js','src/game/state.js','src/game/ai.js','src/game/skills.js','src/ui/hud.js'], '连接数与运行职责不等于模块边界一定最优或测试充分。',704)}`);

figures['KING-V03'] = svg('KING-V03', '17个模块、39条本地相对导入边的完整拓扑', '按engine、game、world和ui分组展示17个模块，并把main.js的10条顶层依赖与state.js的7条玩法汇合边单独标出。', `${titleBand('STATIC MODULE GRAPH','17模块 / 39条相对导入边','38条连接第一方src模块；1条指向vendored BufferGeometryUtils；裸导入 three 不计入')}
  <g><rect class="vr-panel vr-panel-gold" x="472" y="104" width="256" height="80" rx="12"/><text class="vr-section" x="492" y="128">COMPOSITION ROOT</text><text class="vr-title" x="492" y="154">main.js</text><text class="vr-value" x="704" y="154" text-anchor="end">10 edges</text><text class="vr-code" x="492" y="174">boot() / startGame() / loop()</text></g>
  <g><rect class="vr-panel vr-panel-blue" x="28" y="220" width="250" height="330" rx="12"/><text class="vr-section" x="48" y="245">ENGINE · 4</text>${[['renderer.js',270],['input.js',326],['audio.js',382],['vfx.js',438]].map(([n,y])=>node({x:48,y,w:210,h:44,title:n,code:`src/engine/${n}`,type:'blue',lines:[]})).join('')}</g>
  <g><rect class="vr-panel vr-panel-red" x="300" y="220" width="300" height="400" rx="12"/><text class="vr-section" x="320" y="245">GAME · 5</text>${node({x:320,y:268,w:260,h:72,title:'state.js',tag:'7 EDGES',lines:['skills·spawner·ai·shop'],code:'GameState',type:'gold'})}${[['ai.js',354],['skills.js',410],['spawner.js',466],['shop.js',522]].map(([n,y])=>node({x:320,y,w:260,h:44,title:n,code:`src/game/${n}`,type:'red',lines:[]})).join('')}</g>
  <g><rect class="vr-panel vr-panel-green" x="622" y="220" width="250" height="250" rx="12"/><text class="vr-section" x="642" y="245">WORLD · 2</text>${node({x:642,y:270,w:210,h:64,title:'map.js',lines:['Canvas·几何·碰撞'],code:'buildMap()',type:'green'})}${node({x:642,y:354,w:210,h:64,title:'models.js',lines:['英雄·单位·动画'],code:'createHeroModel()',type:'green'})}</g>
  <g><rect class="vr-panel vr-panel-cyan" x="894" y="220" width="278" height="300" rx="12"/><text class="vr-section" x="914" y="245">UI · 3 + ROOT · 3</text>${[['hud.js',270],['minimap.js',326],['screens.js',382],['config.js',438],['utils.js',494]].map(([n,y],i)=>node({x:914,y,w:238,h:44,title:n,code:i<3?`src/ui/${n}`:`src/${n}`,type:i<3?'cyan':'muted',lines:[]})).join('')}</g>
  ${['renderer','input','audio','vfx','map','models','hud','minimap','screens','config'].map((_,i)=>{const targets=[[278,292],[278,348],[278,404],[278,460],[622,302],[622,386],[894,302],[894,358],[894,414],[894,470]][i];return edge('KING-V03',`M${500+i*20} 184 C${500+i*20} 202 ${targets[0]} 202 ${targets[0]} ${targets[1]}`,{type:i>5?'data':'muted',arrow:false});}).join('')}
  ${edge('KING-V03','M450 268C450 210 600 210 600 184',{type:'event',label:'玩法核心再汇合 config / utils / models / skills / spawner / ai / shop',x:320,y:208,arrow:false})}
  <g><rect class="vr-source-rail" x="28" y="642" width="1144" height="46" rx="8"/><text class="vr-section" x="48" y="660">EDGE ACCOUNT</text><text class="vr-value" x="180" y="666">39</text><text class="vr-note" x="225" y="665">relative imports</text><text class="vr-value" x="410" y="666">38</text><text class="vr-note" x="455" y="665">first-party</text><text class="vr-value" x="630" y="666">1</text><text class="vr-note" x="655" y="665">vendored edge</text><text class="vr-code" x="855" y="665">scripts/verify-king-case.mjs</text></g>
  ${sourceRail(['src/**/*.js 静态解析','import/export ... from','side-effect import'], '导入图证明跨模块结构，不证明低耦合、无循环依赖、测试覆盖率或代码质量。',704)}`);

figures['KING-V04'] = svg('KING-V04', '7,979行第一方代码的领域、模块与职责分布', '面积表示领域规模，条形表示17个JavaScript模块的行数，并把入口index.html与start.command单独列示。', `${titleBand('SOURCE COMPOSITION','7,979行不是一个大文件，而是分散在玩法、世界、引擎与界面的运行图','19个第一方文件口径：index.html + 17个src/*.js + start.command')}
  <g class="pv-node" tabindex="0" data-detail="game领域5模块2,989行：state 1,309、ai 920、skills 485、spawner 158、shop 117。" transform="translate(28 110)"><rect class="vr-panel vr-panel-red" width="445" height="230" rx="12"/><text class="vr-section" x="22" y="28">GAME · 37.5%</text><text class="vr-value" x="22" y="64">2,989 lines</text><text class="vr-note" x="22" y="91">state 1,309 · ai 920 · skills 485</text><text class="vr-note" x="22" y="113">spawner 158 · shop 117</text><path class="vr-divider" d="M22 132H423"/>${metric(22,160,'state.js','1,309',1309,360,'#e06c75')}${metric(22,204,'ai.js','920',1309,360,'#d06a76')}</g>
  <g class="pv-node" tabindex="0" data-detail="world领域2模块1,570行：map 840、models 730。" transform="translate(493 110)"><rect class="vr-panel vr-panel-green" width="310" height="230" rx="12"/><text class="vr-section" x="22" y="28">WORLD · 19.7%</text><text class="vr-value" x="22" y="64">1,570 lines</text>${metric(22,115,'map.js','840',840,260,'#7cc79b')}${metric(22,168,'models.js','730',840,260,'#5fae82')}</g>
  <g class="pv-node" tabindex="0" data-detail="engine领域4模块1,278行：vfx 836、renderer 134、input 139、audio 169。" transform="translate(823 110)"><rect class="vr-panel vr-panel-blue" width="349" height="230" rx="12"/><text class="vr-section" x="22" y="28">ENGINE · 16.0%</text><text class="vr-value" x="22" y="64">1,278 lines</text><text class="vr-note" x="22" y="92">vfx 836 · renderer 134</text><text class="vr-note" x="22" y="114">input 139 · audio 169</text>${metric(22,150,'vfx.js','836',836,299,'#5b8dd9')}${metric(22,198,'others','442',836,299,'#405f8d')}</g>
  <g class="pv-node" tabindex="0" data-detail="ui领域3模块1,172行：hud 525、screens 418、minimap 229。" transform="translate(28 360)"><rect class="vr-panel vr-panel-cyan" width="445" height="235" rx="12"/><text class="vr-section" x="22" y="28">UI · 14.7%</text><text class="vr-value" x="22" y="62">1,172 lines</text>${metric(22,105,'hud.js','525',525,390,'#6bb8c9')}${metric(22,151,'screens.js','418',525,390,'#55a6b6')}${metric(22,197,'minimap.js','229',525,390,'#3f8392')}</g>
  <g class="pv-node" tabindex="0" data-detail="根层逻辑3模块636行：config 362、main 186、utils 88。" transform="translate(493 360)"><rect class="vr-panel vr-panel-gold" width="310" height="235" rx="12"/><text class="vr-section" x="22" y="28">ROOT LOGIC · 8.0%</text><text class="vr-value" x="22" y="62">636 lines</text><text class="vr-note" x="22" y="91">config 362 · main 186 · utils 88</text>${metric(22,126,'config.js','362',362,260,'#d3b36a')}${metric(22,174,'main.js','186',362,260,'#a58b4f')}</g>
  <g class="pv-node" tabindex="0" data-detail="入口与启动器334行：index.html 321、start.command 13；vendored Three.js不计入第一方行数。" transform="translate(823 360)"><rect class="vr-panel vr-panel-muted" width="349" height="235" rx="12"/><text class="vr-section" x="22" y="28">ENTRY + LAUNCHER</text><text class="vr-value" x="22" y="62">334 lines</text><text class="vr-note" x="22" y="91">index.html 321 · start.command 13</text><text class="vr-code" x="22" y="126">python3 -m http.server</text><path class="vr-divider" d="M22 148H327"/><text class="vr-note" x="22" y="178">vendored Three.js不纳入第一方行数</text><text class="vr-boundary" x="22" y="208">行数表示规模，不表示质量</text></g>
  ${sourceRail(['wc -l 固定口径','17个src/*.js','index.html','start.command'], '行数不能独立证明可维护性、测试覆盖、算法品质或商业完成度。',704)}`);

figures['KING-V05'] = svg('KING-V05', '180×180峡谷的可执行坐标蓝图', '按config.js中的真实坐标绘制三路、对角河道、18座塔、10个营地、14片草丛、2座水晶、2个泉水和6个基地门。', `${titleBand('WORLD COORDINATE BLUEPRINT','一张地图同时约束移动、索敌、视野、攻防顺序和AI导航','世界坐标 x,z ∈ [-90,90]；绘图为等比例策略蓝图，不是游戏截图')}
  <g transform="translate(245 108)"><rect x="0" y="0" width="710" height="510" rx="14" fill="#0b1b20" stroke="#506b7f"/><path class="vr-map-river" d="M18 492L692 18"/><path class="vr-map-lane" d="M62 448L355 255L648 62"/><path class="vr-map-lane" d="M62 448L42 330L42 90L175 42L500 42L648 62"/><path class="vr-map-lane" d="M62 448L180 468L500 468L668 335L668 170L648 62"/>
  <g>${[140,216,280,430,495,560].map((v,i)=>`<circle class="${i<3?'vr-map-tower-blue':'vr-map-tower-red'}" cx="${v}" cy="${510-v*.68}" r="8"/>`).join('')}${[[42,90],[42,170],[42,250],[175,468],[260,468],[350,468]].map(([x,y])=>`<circle class="vr-map-tower-blue" cx="${x}" cy="${y}" r="8"/>`).join('')}${[[668,170],[668,250],[668,335],[500,42],[415,42],[330,42]].map(([x,y])=>`<circle class="vr-map-tower-red" cx="${x}" cy="${y}" r="8"/>`).join('')}</g>
  <g>${[[235,190],[475,320],[125,265],[585,245],[260,400],[450,110],[355,190],[355,320],[168,340],[540,170]].map(([x,y],i)=>`<circle cx="${x}" cy="${y}" r="${i>7?13:9}" fill="${i>7?'#d3b36a':'#7cc79b'}" stroke="#dff5e7"/><text class="vr-tiny" x="${x+13}" y="${y-8}">${i>7?(i===8?'暴君':'主宰'):'C'+(i+1)}</text>`).join('')}</g>
  <g>${[[180,175],[260,155],[310,215],[400,295],[530,360],[565,420],[75,245],[630,275],[155,440],[555,70],[275,470],[435,40],[55,370],[655,140]].map(([x,y],i)=>`<rect class="vr-map-brush" x="${x}" y="${y}" width="22" height="10" rx="4" transform="rotate(${i%3?0:35} ${x+11} ${y+5})"/>`).join('')}</g>
  <circle cx="62" cy="448" r="24" fill="#5b8dd9" stroke="#b7d5ff"/><circle cx="648" cy="62" r="24" fill="#e06c75" stroke="#ffd0d4"/><circle cx="28" cy="482" r="12" fill="#7cc79b"/><circle cx="682" cy="28" r="12" fill="#7cc79b"/><text class="vr-label" x="82" y="480">BLUE BASE</text><text class="vr-label" x="510" y="30">RED BASE</text>
  <g fill="#f1d78c">${[[92,430],[76,404],[105,456],[620,80],[636,106],[605,54]].map(([x,y])=>`<path d="M${x-5} ${y}L${x+5} ${y-5}V${y+5}Z"/>`).join('')}</g></g>
  ${node({x:28,y:112,w:190,h:92,title:'3 LANES',lines:['mid 3节点','top/bot 各6节点'],code:'MAP.LANES',type:'gold',value:'3'})}
  ${node({x:28,y:224,w:190,h:92,title:'STRUCTURES',lines:['18塔·2水晶·2泉水','6个基地门'],code:'TOWERS_BLUE + mirror',type:'blue',value:'28'})}
  ${node({x:28,y:336,w:190,h:92,title:'VISION',lines:['14草丛','攻击破隐1s'],code:'MAP.BRUSHES',type:'green',value:'14'})}
  ${node({x:28,y:448,w:190,h:92,title:'SCENERY',lines:['120棵树·46块岩石','2048²地表纹理'],code:'TREE_COUNT / ROCK_COUNT',type:'cyan'})}
  ${node({x:982,y:112,w:190,h:92,title:'JUNGLE',lines:['8普通营地','2中立目标'],code:'Spawner.camps',type:'green',value:'10'})}
  ${node({x:982,y:224,w:190,h:92,title:'COLLISION',lines:['活动边界±86','塔/水晶/围墙'],code:'MAP.PLAY_BOUND',type:'red'})}
  ${node({x:982,y:336,w:190,h:92,title:'BASE GATES',lines:['3路交点动态求解','基地内外绕门'],code:'_baseGates()',type:'gold',value:'6'})}
  ${node({x:982,y:448,w:190,h:92,title:'RIVER',lines:['z = -x','宽14·长262'],code:'RIVER_WIDTH/LEN',type:'cyan'})}
  ${sourceRail(['src/config.js:MAP','src/config.js:BASE_GATES','src/world/map.js:buildMap'], '坐标表证明空间拓扑与实体数量，不证明路线设计、视觉可读性或阵营公平性已完成调优。',704)}`);

figures['KING-V06'] = overlaySvg('KING-V06', '程序化地图：由配置、Canvas纹理、Three.js几何和碰撞体合成的WebGL场景', '八个标注区分截图可见地物与源码生成责任。', [
  {n:1,x:16,y:20,targetX:575,targetY:615,title:'2048² Canvas地表',code:'map.js:createGroundTexture',note:'草地/土路/基地石台',tone:'blue'},
  {n:2,x:16,y:100,targetX:830,targetY:400,title:'对角河道',code:'MAP.RIVER_WIDTH = 14',note:'水面网格+波纹纹理',tone:'blue'},
  {n:3,x:16,y:180,targetX:890,targetY:270,title:'龙坑几何',code:'map.js:createPit',note:'石环/符文/碎石',tone:'gold'},
  {n:4,x:16,y:260,targetX:465,targetY:180,title:'塔与静态碰撞',code:'createTower + colliders',note:'几何与玩法半径共存',tone:'red'},
  {n:5,x:946,y:20,targetX:980,targetY:185,title:'120树 + 46岩石',code:'TREE_COUNT / ROCK_COUNT',note:'确定性伪随机散布',tone:'gold',observed:false},
  {n:6,x:946,y:100,targetX:990,targetY:315,title:'14片草丛',code:'map.js:createBrush',note:'可见模型+视野区域',tone:'blue'},
  {n:7,x:946,y:180,targetX:700,targetY:540,title:'实时环境更新',code:'mapData.update(dt,elapsed)',note:'河水/环境逐帧动画',tone:'gold',observed:false},
  {n:8,x:946,y:260,targetX:640,targetY:480,title:'渲染器合成',code:'renderer.js:render()',note:'scene + camera + WebGL',tone:'red',observed:false},
]);

figures['KING-V07'] = overlaySvg('KING-V07', '基地攻防：画面背后的塔序、水晶守卫、围墙门与AI防卡死', '七个标注将敌方基地截图与config/state/ai/map中的攻防规则联系起来。', [
  {n:1,x:16,y:22,targetX:875,targetY:300,title:'水晶实体',code:'state.js:crystals[team]',note:'HP 10000 · ARMOR/MRES 100',tone:'red'},
  {n:2,x:16,y:104,targetX:875,targetY:300,title:'解除无敌条件',code:'isCrystalInvuln()',note:'一路清空 或 存活塔≤3',tone:'gold',observed:false},
  {n:3,x:16,y:186,targetX:875,targetY:350,title:'梯度塔守卫',code:'TOWER_GUARD_PER = .08',note:'受伤下限30% · 脱战回血',tone:'red',observed:false},
  {n:4,x:16,y:268,targetX:735,targetY:255,title:'26段基地围墙',code:'WALL_SEGMENTS = 26',note:'三路缺口半角15°',tone:'blue'},
  {n:5,x:946,y:22,targetX:620,targetY:620,title:'三门导航',code:'BASE_GATES[team]',note:'基地内外切换先经门点',tone:'blue',observed:false},
  {n:6,x:946,y:104,targetX:690,targetY:580,title:'硬卡死换门',code:'HeroAI._moveTo()',note:'2s检测·临时禁用当前门',tone:'gold',observed:false},
  {n:7,x:946,y:186,targetX:1005,targetY:405,title:'泉水高危区',code:'FOUNTAIN.ENEMY_DPS',note:'2000真实伤害/s',tone:'red',observed:false},
]);

figures['KING-V08'] = overlaySvg('KING-V08', '野区、龙坑与草丛视野的共同生态', '标注暴君、主宰、普通营地、leash回营、草丛显隐和AI对中立资源的安全判断。', [
  {n:1,x:16,y:20,targetX:795,targetY:345,title:'中立目标坑',code:'Spawner.objectives',note:'tyrant + overlord',tone:'gold'},
  {n:2,x:16,y:100,targetX:750,targetY:345,title:'暴君收益',code:'TYRANT_TEAM_GOLD/EXP',note:'全队+150金/+100经验',tone:'gold',observed:false},
  {n:3,x:16,y:180,targetX:840,targetY:345,title:'主宰收益',code:'OVERLORD_WAVE_MULT',note:'下波三路兵×1.8',tone:'red',observed:false},
  {n:4,x:16,y:260,targetX:985,targetY:230,title:'草丛显隐',code:'state.js:targetable()',note:'同草丛/破隐1s',tone:'blue'},
  {n:5,x:946,y:20,targetX:470,targetY:610,title:'河道战略通道',code:'MAP.RIVER_WIDTH',note:'连接双方野区与两龙坑',tone:'blue'},
  {n:6,x:946,y:100,targetX:1020,targetY:420,title:'8个普通营地',code:'Spawner.camps',note:'双方红/蓝/2小野',tone:'gold',observed:false},
  {n:7,x:946,y:180,targetX:680,targetY:420,title:'野怪脱离返场',code:'updateMonster()',note:'leash 9 · 回营满血',tone:'red',observed:false},
  {n:8,x:946,y:260,targetX:800,targetY:345,title:'AI安全开龙',code:'HeroAI._findObjective()',note:'血量+附近敌方英雄检查',tone:'gold',observed:false},
]);

figures['KING-V09'] = svg('KING-V09', '双时钟主循环：30Hz确定逻辑与逐帧渲染并行', '展示requestAnimationFrame、dt封顶、accumulator、最大5次补步、GameState固定更新与表现层逐帧更新的完整分工。', `${titleBand('DETERMINISTIC SIMULATION LOOP','帧率可变，但战斗规则用固定步长推进','main.js:loop(ts) 使逻辑与渲染同居一帧，又不把数值结果绑死在显示器帧率上')}
  <g><rect class="vr-panel vr-panel-blue" x="28" y="112" width="1144" height="116" rx="12"/><text class="vr-section" x="48" y="138">WALL CLOCK / requestAnimationFrame</text>${node({x:50,y:152,w:205,h:58,title:'ts → dt',lines:['min(0.25, Δt)'],code:'main.js:124',type:'blue'})}${node({x:285,y:152,w:205,h:58,title:'DEMO SPEED',lines:['acc += dt × speed'],code:'demo ? 4 : 1',type:'gold'})}${node({x:520,y:152,w:205,h:58,title:'ACCUMULATOR',lines:['累积未消费逻辑时间'],code:'acc',type:'cyan'})}${node({x:755,y:152,w:190,h:58,title:'SUBSTEPS',lines:['while acc ≥ 1/30'],code:'sub < 5',type:'red'})}${node({x:975,y:152,w:175,h:58,title:'DROP BACKLOG',lines:['防止死亡螺旋'],code:'MAX_SUB=5',type:'muted'})}</g>
  ${edge('KING-V09','M255 181H285')}${edge('KING-V09','M490 181H520',{type:'data'})}${edge('KING-V09','M725 181H755',{type:'data'})}${edge('KING-V09','M945 181H975',{type:'feedback'})}
  <g><rect class="vr-panel vr-panel-red" x="28" y="258" width="720" height="310" rx="12"/><text class="vr-section" x="48" y="284">FIXED LOGIC CLOCK · 30Hz</text><text class="vr-value" x="714" y="286" text-anchor="end">STEP = 0.0333s</text>
  ${node({x:52,y:310,w:205,h:74,title:'Input snapshot',lines:['moveVec + attackHeld'],code:'input.getMoveVector()',type:'blue'})}${node({x:286,y:310,w:205,h:74,title:'GameState.update',lines:['10阶段玩法更新'],code:'state.update(STEP, move)',type:'red'})}${node({x:520,y:310,w:204,h:74,title:'Simulation facts',lines:['time·units·damage·winner'],code:'shared state',type:'gold'})}
  ${edge('KING-V09','M257 347H286',{type:'data'})}${edge('KING-V09','M491 347H520',{type:'event'})}
  <g transform="translate(52 422)"><rect class="vr-code-line" width="672" height="118" rx="9"/><text class="vr-code" x="18" y="27">while (acc &gt;= LOOP.STEP &amp;&amp; sub &lt; LOOP.MAX_SUB) {</text><text class="vr-code" x="42" y="51">state.update(LOOP.STEP, input.getMoveVector());</text><text class="vr-code" x="42" y="75">acc -= LOOP.STEP; sub++;</text><text class="vr-code" x="18" y="99">}</text></g></g>
  <g><rect class="vr-panel vr-panel-green" x="772" y="258" width="400" height="310" rx="12"/><text class="vr-section" x="792" y="284">VARIABLE RENDER CLOCK · EVERY FRAME</text>
  ${[['state.animate(dt)','model.userData.update'],['minimap.update(state,dt)','0.25s内部节流'],['vfx.update(dt)','对象池动画'],['mapData.update(dt,elapsed)','环境动画'],['engine.update(dt)','相机跟随'],['engine.render()','WebGL合成'],['hud.update()','DOM状态投影']].map(([a,b],i)=>`<g><circle cx="804" cy="${317+i*34}" r="7" fill="#7cc79b"/><text class="vr-label" x="822" y="${321+i*34}">${a}</text><text class="vr-tiny" x="1015" y="${321+i*34}">${b}</text></g>`).join('')}</g>
  <g><rect class="vr-panel vr-panel-gold" x="96" y="594" width="1008" height="80" rx="11"/><text class="vr-section" x="118" y="618">WHY THIS IS HARDER THAN ANIMATION</text><text class="vr-note" x="118" y="644">渲染帧可丢，但技能CD、兵线、AI、伤害和金币必须按稳定时钟推进；卡顿时最多补5步，避免无限追帧。</text><text class="vr-code" x="118" y="662">requestAnimationFrame(loop) · LOOP.STEP=1/30 · LOOP.MAX_SUB=5 · dt≤0.25</text></g>
  ${sourceRail(['src/config.js:LOOP','src/main.js:loop','src/game/state.js:update'], '该图证明实现了固定步长与补步上限，不等于已做跨帧率确定性对比、性能压测或网络同步。',704)}`);

const updateStages = [
  ['01','Spawner','兵线·营地·被动金','spawner.update(dt)','green'],
  ['02','SkillSystem','弹道·延迟·Buff','skills.update(dt)','red'],
  ['03','Brush visibility','草丛进出·破隐','updateBrushState()','cyan'],
  ['04','Player actions','移动·普攻·动作队列','_actionQueue','blue'],
  ['05','Hero AI ×9','撤退·回防·团战·推进','ai.update(dt)','red'],
  ['06','Unit AI','小兵·塔·野怪·分身','updateMinion/Tower/Monster','gold'],
  ['07','Fountain','友方回复·敌方真伤','FOUNTAIN','green'],
  ['08','Separation','单位间软推挤','_separate()','cyan'],
  ['09','Collision sync','静态碰撞·边界·模型','colliders + syncModel','blue'],
  ['10','Cleanup','清理_purge单位','units.filter','muted'],
];
figures['KING-V10'] = svg('KING-V10', 'GameState.update()的十阶段严格顺序', '以固定逻辑tick为单位，按生成、技能、视野、玩家、英雄AI、单位AI、泉水、分离、碰撞同步和清理的顺序更新。', `${titleBand('GAMESTATE PIPELINE','每个33.3ms逻辑片段都要把十类子系统按固定顺序推进','顺序本身是行为契约：生成的单位可在同tick被AI读取，死亡后在末尾统一清理')}
  <g>${updateStages.map((s,i)=>{const col=i%5,row=Math.floor(i/5),x=28+col*230,y=112+row*190;const next=i<9?edge('KING-V10',row===Math.floor((i+1)/5)?`M${x+212} ${y+55}H${x+230}`:`M${x+106} ${y+98}V${y+150}H${28+((i+1)%5)*230+106}V${y+190}`,{type:i===4?'event':'data',label:i===4?'AI writes actions':'',x:x+105,y:y+132}):'';return node({x,y,w:212,h:98,title:`${s[0]} · ${s[1]}`,lines:[s[2]],code:s[3],type:s[4],detail:`GameState.update第${i+1}阶段：${s[2]}；冻结源码符号 ${s[3]}。`})+next;}).join('')}</g>
  <g><rect class="vr-panel vr-panel-gold" x="28" y="520" width="1144" height="150" rx="12"/><text class="vr-section" x="50" y="545">ORDER-SENSITIVE CONSEQUENCES</text>
  <g transform="translate(50 566)">${[['技能延迟效果','先于AI决策结算'],['玩家动作队列','在固定tick统一消费'],['英雄AI','先于小兵/塔/野怪'],['泉水伤害','可引发死亡/奖励链'],['碰撞同步','在位移之后修正'],['延迟清理','避免迭代中移除']].map(([a,b],i)=>`<g transform="translate(${(i%3)*360} ${Math.floor(i/3)*48})"><circle cx="8" cy="10" r="6" fill="#d3b36a"/><text class="vr-label" x="24" y="14">${a}</text><text class="vr-note" x="150" y="14">${b}</text></g>`).join('')}</g></g>
  ${sourceRail(['src/game/state.js:1121–1303'], '阶段图证明存在顺序化更新管线，不证明每个边界条件均有单元测试或没有时序缺陷。',704)}`);

figures['KING-V11'] = svg('KING-V11', '英雄、小兵、野怪与建筑的单位生命周期', '用四条并行生命线展示生成、移动、索敌、攻击、受伤、死亡、奖励、重生或清理的差异。', `${titleBand('UNIT LIFECYCLE MATRIX','同一Unit抽象，四种不同的终止语义','英雄需要重生；小兵和召唤物需要清理；野怪按营地刷新；建筑死亡会改写攻防规则')}
  <g>${[['HERO','createHero','HeroAI / player','performAttack / skills','onUnitDied','8+2×level 重生','blue'],['MINION','spawnWave','updateMinion','push tower / self-defend','_purge=true','下一波30s','cyan'],['MONSTER','spawnCamp','updateMonster','aggro / leash / return','camp.respawnAt','普通60s·龙单独','green'],['STRUCTURE','constructor','updateTower / static','tier/invuln/aggro','disable collider','塔不重生·水晶终局','red']].map((row,r)=>{const y=118+r*128;const xs=[28,218,408,598,788,978];return `<g><rect class="vr-lane-bg" x="20" y="${y-16}" width="1160" height="108" rx="10"/><text class="vr-section" x="36" y="${y+8}">${row[0]}</text>${row.slice(1,6).map((v,i)=>node({x:xs[i]+(i===0?58:0),y:y+20,w:i===0?150:174,h:58,title:v,lines:[],code:['spawn','act','combat','death','next'][i],type:row[6],detail:`${row[0]}生命周期阶段${i+1}：${v}。`})).join('')}${edge('KING-V11',`M${xs[0]+208} ${y+49}H${xs[1]}`,{type:'data'})}${edge('KING-V11',`M${xs[1]+174} ${y+49}H${xs[2]}`,{type:'event'})}${edge('KING-V11',`M${xs[2]+174} ${y+49}H${xs[3]}`,{type:'feedback'})}${edge('KING-V11',`M${xs[3]+174} ${y+49}H${xs[4]}`,{type:'event'})}</g>`;}).join('')}</g>
  <g><rect class="vr-panel vr-panel-gold" x="170" y="638" width="860" height="46" rx="8"/><text class="vr-note" x="190" y="666">Unit.die()只是入口；GameState.onUnitDied()根据kind分发金币、经验、Buff、塔奖励、重生计时或winner。</text></g>
  ${sourceRail(['src/game/state.js:Unit','src/game/state.js:onUnitDied','src/game/spawner.js','src/game/ai.js'], '生命周期分支证明不同单位具有差异化终止语义，不证明内存泄漏、所有重生边界或奖励归属均无缺陷。',704)}`);

figures['KING-V12'] = svg('KING-V12', '1名玩家 + 9名AI的5v5阵容装配', '展示双方上路、中路、下路双人和打野五个角色，以及玩家英雄选择后的镜像英雄池限制。', `${titleBand('ROSTER ASSEMBLY','一场比赛同时维护10名英雄，其中9名由独立HeroAI控制','setupAI()创建蓝方4名队友AI和红方5名敌方AI；玩家占用蓝方一个角色位')}
  <g><rect class="vr-panel vr-panel-blue" x="28" y="112" width="558" height="470" rx="14"/><text class="vr-section" x="50" y="140">BLUE TEAM · PLAYER + 4 AI</text><text class="vr-value" x="548" y="142" text-anchor="end">5</text>${[['TOP','牛魔','top','AI'],['MID','妲己','mid','AI'],['BOT CARRY','后羿','bot','AI'],['BOT SUPPORT','亚瑟','bot2','AI'],['JUNGLE','兰陵王','jungle','PLAYER / AI']].map((r,i)=>node({x:52,y:164+i*76,w:510,h:62,title:`${r[0]} · ${r[1]}`,lines:[`role=${r[2]}  controller=${r[3]}`],code:`setupAI('blue', ...)`,type:i===4?'gold':'blue',detail:`蓝方${r[0]}角色，英雄${r[1]}，路线角色${r[2]}，控制器${r[3]}。`})).join('')}</g>
  <g><rect class="vr-panel vr-panel-red" x="614" y="112" width="558" height="470" rx="14"/><text class="vr-section" x="636" y="140">RED TEAM · 5 AI</text><text class="vr-value" x="1134" y="142" text-anchor="end">5</text>${[['TOP','牛魔','top'],['MID','妲己','mid'],['BOT CARRY','后羿','bot'],['BOT SUPPORT','亚瑟','bot2'],['JUNGLE','兰陵王','jungle']].map((r,i)=>node({x:638,y:164+i*76,w:510,h:62,title:`${r[0]} · ${r[1]}`,lines:[`role=${r[2]}  controller=HeroAI`],code:`setupAI('red', ...)`,type:'red',detail:`红方${r[0]}角色由HeroAI控制，英雄${r[1]}，路线角色${r[2]}。`})).join('')}</g>
  <g><rect class="vr-panel vr-panel-gold" x="140" y="608" width="920" height="72" rx="10"/><text class="vr-section" x="162" y="631">MIRRORED HERO POOL LIMIT</text><text class="vr-note" x="162" y="654">双方使用同一组5人英雄池；这能支撑职业分工与技能差异，但不是10个不同英雄，也不是阵容系统。</text><text class="vr-code" x="162" y="673">HEROES = { arthur, houyi, daji, niumo, lanlingwang }</text></g>
  ${sourceRail(['src/config.js:HEROES','src/game/state.js:setupAI','src/main.js:startGame'], '队列证明运行时创建10名英雄与9个AI控制器，不证明10个独特英雄、阵容BP或联网玩家存在。',704)}`);

const heroModes = [
  ['01','LOCK / RECALL','回城引导或控制状态立即短路返回','u.channel / isLocked()','muted'],
  ['02','RETREAT','HP &lt; 30%；安全时回城，泉水回到85%','RETREAT_HP / RETURN_HP','red'],
  ['03','HEAL','低血且近3s参战，恢复术可用','castHeal()','green'],
  ['04','DEFEND','己方塔近4s被攻；水晶被攻全员回防','_findDefense()','blue'],
  ['05','ASSAULT','敌水晶解防且已进基地，强攻水晶','isCrystalInvuln()','gold'],
  ['06','TEAMFIGHT','15半径内≥3英雄近4s交战','_findTeamfight()','red'],
  ['07','GROUP_PUSH','水晶解防且己方存活≥2，集团推进','_shouldGroupPush()','gold'],
  ['08','OBJECTIVE','血量门槛+龙坑附近无敌方英雄','_findObjective()','green'],
  ['09','LANE / JUNGLE','默认分路跟兵或最近邻营地路线','_lane() / _jungle()','cyan'],
];
figures['KING-V13'] = svg('KING-V13', '英雄AI的九层优先级状态机', '按HeroAI.update中的真实判定顺序展示锁定、撤退、恢复、回防、强攻、团战、集团推进、中立目标和默认分路/打野。', `${titleBand('HERO AI PRIORITY MACHINE','不是“走向最近敌人”，而是一条会被高优先级短路截断的决策链','每个AI英雄每个固定tick评估生存、防守、终局、团战、目标和分路条件')}
  <g>${heroModes.map((m,i)=>{const col=i%3,row=Math.floor(i/3),x=28+col*382,y=112+row*165;return node({x,y,w:356,h:126,title:`${m[0]} · ${m[1]}`,lines:[m[2]],code:m[3],type:m[4],detail:`HeroAI.update优先级${m[0]}：${m[2]}；命中后通常return，阻断低优先级模式。`})+(i<8?edge('KING-V13',col<2?`M${x+356} ${y+63}H${x+382}`:`M${x+178} ${y+126}V${y+145}H${28+178}V${y+165}`,{type:i<4?'feedback':'event',label:i===3?'priority fallthrough':'',x:x+160,y:y+150}):'');}).join('')}</g>
  <g><rect class="vr-panel vr-panel-muted" x="28" y="612" width="1144" height="72" rx="10"/><text class="vr-section" x="50" y="635">TARGETING SUBROUTINE</text><text class="vr-code" x="50" y="658">_selectTarget(radius): score = distance / (hero ? 1.5 : 1) → 排除建筑/非反击野怪/敌方泉水 → 越塔时检查己方兵线掩护</text><text class="vr-boundary" x="50" y="677">规则稀疏但可执行；未使用机器学习、搜索树或长期策略训练。</text></g>
  ${sourceRail(['src/game/ai.js:HeroAI.update','src/config.js:AI_CFG'], '状态机证明9名AI英雄按多条件决策，不证明AI接近真人水平、没有循环抖动或能处理任意阵容。',704)}`);

figures['KING-V14'] = svg('KING-V14', '小兵AI：三路路径、索敌脱离、推塔和防卡死', '展示一只小兵在生成、路径偏移、索敌、攻击、脱离、路点到达、塔后超级兵和主宰强化之间的决策。', `${titleBand('MINION DECISION PIPELINE','兵线是一个持续生成、持续索敌、持续推进的自治系统','每30s双方三路同时出兵；每只小兵都保持lane、pathIndex、target、laneOffset和卡死观测状态')}
  <g><rect class="vr-panel vr-panel-green" x="28" y="112" width="1144" height="100" rx="12"/><text class="vr-section" x="48" y="136">WAVE CONSTRUCTION</text>${[['3刀兵','WAVE.MELEE=3'],['2法师兵','WAVE.MAGE=2'],['每3波+1炮车','CANNON_EVERY=3'],['一路三塔全破','+1 super'],['主宰奖励','next wave ×1.8']].map(([a,b],i)=>node({x:48+i*222,y:150,w:204,h:46,title:a,lines:[],code:b,type:i<2?'green':i===4?'gold':'cyan'})).join('')}</g>
  <g><rect class="vr-panel vr-panel-cyan" x="28" y="238" width="760" height="358" rx="12"/><text class="vr-section" x="48" y="262">updateMinion(unit,state,dt)</text>
  ${node({x:50,y:282,w:210,h:76,title:'01 有效目标？',lines:['alive·targetable·leash≤14'],code:'MINION_LEASH_R',type:'cyan'})}${node({x:294,y:282,w:210,h:76,title:'02 重新索敌',lines:['aggro radius = 8'],code:'nearestEnemy()',type:'red'})}${node({x:538,y:282,w:220,h:76,title:'03 射程内攻击',lines:['1/aspeed · face target'],code:'performAttack()',type:'red'})}
  ${node({x:50,y:398,w:210,h:76,title:'04 跟随路点',lines:['laneOffset ≤ 2.5'],code:'path[pathIndex]',type:'blue'})}${node({x:294,y:398,w:210,h:76,title:'05 到达判定',lines:['distance ≤ 7'],code:'MINION_WP_ARRIVE_R',type:'gold'})}${node({x:538,y:398,w:220,h:76,title:'06 终点策略',lines:['3.5内自卫，否则水晶'],code:'MINION_SELF_DEFEND_R',type:'gold'})}
  ${edge('KING-V14','M260 320H294',{type:'feedback',label:'no / invalid',x:262,y:311})}${edge('KING-V14','M504 320H538',{type:'event',label:'target',x:506,y:311})}${edge('KING-V14','M648 358V386H155V398',{type:'data',label:'out of range → move',x:350,y:380})}${edge('KING-V14','M260 436H294',{type:'data'})}${edge('KING-V14','M504 436H538',{type:'event'})}
  <g><rect class="vr-code-line" x="50" y="506" width="708" height="68" rx="8"/><text class="vr-code" x="68" y="530">stuck window: 3s · moved &lt; 1 → pathIndex++ / nudge</text><text class="vr-note" x="68" y="552">路点到达半径从塔碰撞尺寸推导，并增加轨道偏移与卡死检测，避免兵线在拐角塔堆积。</text></g></g>
  <g><rect class="vr-panel vr-panel-gold" x="812" y="238" width="360" height="358" rx="12"/><text class="vr-section" x="834" y="262">STRUCTURE CONSEQUENCES</text>${node({x:834,y:284,w:316,h:72,title:'炮车 / 超级兵对塔×2',lines:['towerMult = 2'],code:'config.js:MINIONS',type:'gold'})}${node({x:834,y:376,w:316,h:72,title:'一路破塔 → 超级兵',lines:['laneCleared(team,lane)'],code:'spawner.js',type:'red'})}${node({x:834,y:468,w:316,h:72,title:'12:00后时间成长',lines:['HP/AD 每分钟+8%'],code:'MINION_GROWTH',type:'green'})}</g>
  ${sourceRail(['src/game/ai.js:updateMinion','src/game/spawner.js:spawnWave','src/config.js:MINIONS/WAVE'], '图示证明兵线不是动画摆设，不证明路径寻路在所有碰撞场景无卡死或兵线节奏已平衡。',704)}`);

figures['KING-V15'] = svg('KING-V15', '四类AI与全局规则的多智能体拼图', '对比英雄、小兵、防御塔和野怪四套不同行为规则，并展示草丛、泉水、建筑无敌和共享伤害结算如何跨类型影响决策。', `${titleBand('MULTI-AGENT RULE TOPOLOGY','四套专用AI在同一GameState中竞争目标、空间和伤害结果','“AI”指可执行规则控制器，不指机器学习模型')}
  ${node({x:430,y:288,w:340,h:150,title:'GameState',tag:'SHARED WORLD',lines:['targetable / nearestEnemy / dealDamage','units / colliders / time / events'],code:'game/state.js',type:'gold',value:'1 truth'})}
  ${node({x:28,y:112,w:330,h:130,title:'HeroAI × 9',tag:'STRATEGIC',lines:['9 modes·组团·打龙·买装','越塔掩护·基地门导航'],code:'HeroAI.update()',type:'red'})}
  ${node({x:842,y:112,w:330,h:130,title:'MinionAI × N',tag:'LANE',lines:['路点·索敌·脱离·推塔','炮车·超级兵·卡死修正'],code:'updateMinion()',type:'cyan'})}
  ${node({x:28,y:492,w:330,h:130,title:'TowerAI × 18',tag:'DEFENSE',lines:['兵线优先·英雄仇恨窗口','对同一英雄连击累加'],code:'updateTower()',type:'blue'})}
  ${node({x:842,y:492,w:330,h:130,title:'MonsterAI',tag:'JUNGLE',lines:['3.5主动范围·9距离leash','回营满血·营地刷新'],code:'updateMonster()',type:'green'})}
  ${edge('KING-V15','M358 205L430 315',{type:'event',label:'move / cast / attack',x:350,y:270})}${edge('KING-V15','M842 205L770 315',{type:'event',label:'lane target',x:760,y:270})}${edge('KING-V15','M358 557L430 410',{type:'event',label:'aggro / projectile',x:340,y:470})}${edge('KING-V15','M842 557L770 410',{type:'event',label:'aggro / leash',x:765,y:470})}
  <g><rect class="vr-panel vr-panel-muted" x="390" y="112" width="420" height="132" rx="12"/><text class="vr-section" x="410" y="137">CROSS-CUTTING RULES</text>${[['草丛','targetable'],['泉水','heal / true dmg'],['建筑无敌','tier / crystal'],['软推挤','_separate']].map(([a,b],i)=>`<g transform="translate(${410+(i%2)*190} ${153+Math.floor(i/2)*39})"><circle cx="6" cy="6" r="5" fill="#d3b36a"/><text class="vr-label" x="18" y="10">${a}</text><text class="vr-code" x="90" y="10">${b}</text></g>`).join('')}</g>
  <g><rect class="vr-panel vr-panel-red" x="390" y="486" width="420" height="136" rx="12"/><text class="vr-section" x="410" y="512">ONE SETTLEMENT POINT</text><text class="vr-value" x="410" y="544">dealDamage()</text><text class="vr-note" x="410" y="568">护甲/魔抗·护盾·水晶守卫·死亡</text><text class="vr-code" x="410" y="592">source → target → onUnitDied → reward/event</text></g>
  ${sourceRail(['src/game/ai.js','src/game/state.js','src/config.js:AI_CFG'], '四类规则系统的共享状态证明多智能体交互，不证明战术智能达到商业MOBA水平。',704)}`);

const skills = [
  ['亚瑟','战士','誓约之盾','self','8s','移速+30%·强化普攻·沉默','回旋打击','around','10s','3s环绕DoT·r=4','圣剑裁决','target','40s','跃击·已损生命12%'],
  ['后羿','射手','炙热之风','self','8s','4s三连普攻·每箭60%AD','燎原箭雨','area','10s','r=6·0.4s延迟·减速','灼日之矢','line','40s','range=60·距离眩晕'],
  ['妲己','法师','灵魂冲击','line','8s','range=11·直线弹道','偶像魅力','target','10s','range=10·追踪+眩晕','女王崇拜','around','40s','5团狐火自动寻敌'],
  ['牛魔','坦克','咆哮之斧','around','8s','r=3.5·范围减速','横行霸道','dash','10s','range=8·冲锋击退','山崩地裂','around','40s','r=5·击飞+自护盾'],
  ['兰陵王','刺客','秘技·分身','self','8s','分身追击3次·60%AD','秘技·影蚀','target','10s','range=9·标记二段伤害','秘技·暗袭','target','40s','range=9·突进至目标身后'],
];
figures['KING-V16'] = svg('KING-V16', '五英雄×三主动技能的十五条战斗路径', '对每个技能直接标出名称、瞄准模式、基础冷却和关键效果，展示五个职业的不同实现路径。', `${titleBand('SKILL IMPLEMENTATION MATRIX','15项技能不是15个同形按钮：它们展开为弹道、延迟区域、Buff、位移、分身和控制','基础CD：S1=8s、S2=10s、Ult=40s；大招每级-3s；技能基础伤害每级+12%')}
  <g>${skills.map((r,ri)=>{const y=110+ri*112;return `<g><rect class="vr-lane-bg" x="24" y="${y}" width="1152" height="98" rx="10"/><text class="vr-section" x="42" y="${y+22}">${r[0]} · ${r[1]}</text>${[0,1,2].map(si=>{const o=2+si*4,x=190+si*326;return node({x,y:y+12,w:306,h:74,title:`${['Q','E','R'][si]} · ${r[o]}`,lines:[`${r[o+1]} · ${r[o+2]}`,r[o+3]],code:`SKILLS.${['arthur','houyi','daji','niumo','lanlingwang'][ri]}.${['s1','s2','ult'][si]}`,type:['blue','cyan','gold'][si],detail:`${r[0]}${r[o]}：瞄准模式${r[o+1]}，基础冷却${r[o+2]}，关键效果${r[o+3]}。`});}).join('')}</g>`;}).join('')}</g>
  <g><rect class="vr-panel vr-panel-muted" x="160" y="675" width="880" height="28" rx="7"/><text class="vr-tiny" x="180" y="694">COMMON: mana 50/60/100 · scale = base×(1+12%×(skillLevel-1)) + ratio×AD/AP · cooldown affected by blueBuff CDR</text></g>
  ${sourceRail(['src/game/skills.js:SKILLS','src/config.js:SKILL_COMMON'], '技能矩阵证明15项定义与差异化cast路径存在，不证明15项技能均已在每种目标组合下逐项运行验证。',718)}`, {height:774});

figures['KING-V17'] = svg('KING-V17', '六种技能瞄准模型与空间校验', '展示self、around、target、area、line和dash六类瞄准几何，并标出15项技能的分布、范围、目标选择与HUD指针输入。', `${titleBand('AIMING GEOMETRY','一个技能按钮背后至少有六种空间语义','HUD把点按/拖拽转成slot+direction；SkillSystem.cast再根据def.aim解析施法者、单位、地点、方向或位移')}
  <g>${[
    ['self','3','亚Q·后Q·兰Q','无空间目标；自身Buff/分身','◎','blue'],
    ['around','4','亚E·妲R·牛Q/R','以施法者为圆心；r=3.5/4/5/10','◉','green'],
    ['target','4','亚R·妲E·兰E/R','可锁定敌方单位；range=8–10','⊙','red'],
    ['area','1','后E','12距离地点；r=6；0.4s延迟','◌','gold'],
    ['line','2','后R·妲Q','方向弹道；range=60/11','⟶','cyan'],
    ['dash','1','牛E','方向位移8；路径击退','➶','blue'],
  ].map((a,i)=>{const col=i%3,row=Math.floor(i/3),x=28+col*382,y=112+row*244;return node({x,y,w:356,h:210,title:`${a[0]} · ${a[1]}`,tag:'SKILLS',lines:[a[2],a[3]],code:`def.aim === '${a[0]}'`,type:a[5],detail:`${a[0]}瞄准模型：${a[2]}；${a[3]}。`})+`<text x="${x+178}" y="${y+142}" text-anchor="middle" style="font-size:52px;fill:#f1d78c">${a[4]}</text><text class="vr-tiny" x="${x+178}" y="${y+184}" text-anchor="middle">HUD vector → SkillSystem.cast → range / targetable</text>`;}).join('')}</g>
  <g><rect class="vr-panel vr-panel-gold" x="108" y="614" width="984" height="66" rx="10"/><text class="vr-section" x="130" y="637">AUTO-AIM FALLBACK</text><text class="vr-note" x="130" y="660">点按没有拖拽方向时，根据瞄准类型选取最近合法目标或面向方向；超范围、死亡、不可见目标在cast中拒绝。</text><text class="vr-code" x="130" y="676">hud._endAim() → state.queueAction({skill,dir}) → skills.cast(caster,slot,targetOpt)</text></g>
  ${sourceRail(['src/game/skills.js:SKILLS/cast','src/ui/hud.js:aim handlers','src/game/state.js:targetable'], '六类几何语义证明技能不是统一放大数值，不证明触控手感、自动瞄准准确率或所有边界已充分测试。',704)}`);

figures['KING-V18'] = svg('KING-V18', '一次技能施放跨越十个代码环节', '从键盘或指针输入、HUD瞄准、动作队列、状态门控、SkillSystem资源检查，到弹道、延迟、Buff、伤害和多通道反馈。', `${titleBand('END-TO-END SKILL TRANSACTION','从人机交互到伤害数值，再回到画面、声音和HUD','命令在固定tick消费；施放前失败是结构化分支，而不是直接播放动画')}
  <g>${[
    ['01','INPUT','pointer / Q E R','input.js + hud.js','blue'],['02','AIM','slot + direction','HUD._endAim()','blue'],['03','QUEUE','动作进固定tick','state.queueAction','gold'],['04','STATE GATE','alive / lock / recall','tryCastSkill','gold'],['05','CASTABLE','level / CD / mana','SkillSystem.cast','red'],
    ['06','TARGET','aim / range / visibility','targetable','cyan'],['07','EXECUTE','buff / projectile / delay','def.cast','red'],['08','SETTLE','AD/AP → resistance','dealDamage','red'],['09','EVENT','skillCast / death','EventBus.emit','gold'],['10','FEEDBACK','VFX / Audio / HUD','update/render','green']
  ].map((s,i)=>{const row=Math.floor(i/5),col=i%5,x=28+col*230,y=112+row*210;return node({x,y,w:210,h:116,title:`${s[0]} · ${s[1]}`,lines:[s[2]],code:s[3],type:s[4],detail:`技能事务第${s[0]}环节${s[1]}：${s[2]}；对应${s[3]}。`})+(i<9?edge('KING-V18',col<4?`M${x+210} ${y+58}H${x+230}`:`M${x+105} ${y+116}V${y+160}H${28+105}V${y+210}`,{type:i<2?'data':i>6?'event':'normal'}):'');}).join('')}</g>
  <g><rect class="vr-panel vr-panel-muted" x="28" y="555" width="550" height="128" rx="10"/><text class="vr-section" x="50" y="580">EARLY-EXIT BRANCHES</text><text class="vr-note" x="50" y="605">• 技能未加点 • cooldown &gt; 0 • mana不足</text><text class="vr-note" x="50" y="627">• stun / silence / knockup • 施法者死亡</text><text class="vr-note" x="50" y="649">• target失效/超范围/不可见 • 对局over</text><text class="vr-code" x="50" y="670">return false → HUD保留当前状态</text></g>
  <g><rect class="vr-panel vr-panel-green" x="602" y="555" width="570" height="128" rx="10"/><text class="vr-section" x="624" y="580">VISIBLE CONSEQUENCES</text>${[['vfx','弹道/预警/冲击波'],['audio','音调/噪声/播报'],['hud','CD遮罩/血条/飘字'],['model','朝向/位移/发光壳']].map(([a,b],i)=>`<g transform="translate(${624+(i%2)*275} ${600+Math.floor(i/2)*34})"><circle cx="6" cy="6" r="5" fill="#7cc79b"/><text class="vr-label" x="18" y="10">${a}</text><text class="vr-note" x="72" y="10">${b}</text></g>`).join('')}</g>
  ${sourceRail(['src/ui/hud.js','src/main.js','src/game/state.js','src/game/skills.js','src/engine/vfx.js','src/engine/audio.js'], '调用链证明技能跨输入、状态、数值和表现，不证明每个技能分支均已被自动化覆盖。',704)}`);

figures['KING-V19'] = svg('KING-V19', '战斗结算：从技能面板值到死亡、奖励与终局', '展示AD/AP缩放、暴击与建筑倍率、护甲魔抗、水晶守卫、护盾、控制、死亡分发和金币经验统计的统一流水线。', `${titleBand('COMBAT SETTLEMENT','伤害不是“扣一个HP”，而是会改写控制、隐身、仇恨、经济、等级、重生和胜负的事件','GameState.dealDamage()是统一结算点；onUnitDied()再根据unit.kind分发后果')}
  <g><rect class="vr-panel vr-panel-blue" x="28" y="112" width="1144" height="112" rx="12"/><text class="vr-section" x="48" y="137">DAMAGE INPUTS</text>${[['普攻','AD·攻速·暴击'],['技能','base·level·AD/AP ratio'],['状态','DoT·mark·empower'],['单位倍率','炮车/超级兵对塔×2'],['终结倍率','已损生命12%']].map(([a,b],i)=>node({x:48+i*222,y:153,w:204,h:54,title:a,lines:[b],code:['performAttack','scale()','buff update','towerMult','arthur.ult'][i],type:i===1?'red':i===4?'gold':'blue'})).join('')}</g>
  <g><rect class="vr-panel vr-panel-red" x="28" y="250" width="720" height="356" rx="12"/><text class="vr-section" x="48" y="276">dealDamage(source,target,amount,opts)</text>
  ${node({x:50,y:300,w:205,h:78,title:'01 目标门控',lines:['alive·targetable·building invuln'],code:'isBuildingInvuln()',type:'red'})}${node({x:280,y:300,w:205,h:78,title:'02 类型与抗性',lines:['AD→armor·AP→mres'],code:'100/(100+resist)',type:'gold'})}${node({x:510,y:300,w:215,h:78,title:'03 特殊修正',lines:['crit·towerMult·guard'],code:'opts / target.kind',type:'cyan'})}
  ${node({x:50,y:418,w:205,h:78,title:'04 护盾吸收',lines:['shield先于HP'],code:'target.shield',type:'green'})}${node({x:280,y:418,w:205,h:78,title:'05 状态副作用',lines:['combatT·assist mark·reveal'],code:'lastCombatT / marks',type:'red'})}${node({x:510,y:418,w:215,h:78,title:'06 可见反馈',lines:['floatText·VFX·hit event'],code:'vfx / events',type:'blue'})}
  ${edge('KING-V19','M255 339H280',{type:'data'})}${edge('KING-V19','M485 339H510',{type:'data'})}${edge('KING-V19','M617 378V400H152V418',{type:'event'})}${edge('KING-V19','M255 457H280',{type:'feedback'})}${edge('KING-V19','M485 457H510',{type:'event'})}
  <g><rect class="vr-code-line" x="50" y="520" width="675" height="62" rx="8"/><text class="vr-code" x="68" y="545">actual = amount × 100 / (100 + max(-80,resist))</text><text class="vr-note" x="68" y="568">target.hp -= max(0, actual - shieldAbsorb) → hp ≤ 0 ? onUnitDied(target, source)</text></g></g>
  <g><rect class="vr-panel vr-panel-gold" x="772" y="250" width="400" height="356" rx="12"/><text class="vr-section" x="794" y="276">onUnitDied(unit,killer)</text>${[['MINION','补刀金·范围经验·_purge'],['MONSTER','金/经验·红蓝BUFF·龙奖励'],['HERO','200击杀·80助攻·KDA·重生'],['TOWER','全队+200金·关闭碰撞'],['CRYSTAL','endGame(winner)·结算']].map((r,i)=>node({x:794,y:294+i*58,w:356,h:50,title:r[0],lines:[r[1]],code:['kind=minion','kind=monster','kind=hero','kind=tower','kind=crystal'][i],type:i===4?'red':i===2?'gold':'green',detail:`${r[0]}死亡分支：${r[1]}。`})).join('')}</g>
  <g><rect class="vr-panel vr-panel-muted" x="120" y="632" width="960" height="52" rx="9"/><text class="vr-note" x="142" y="655">控制/Buff状态：stun·silence·knockup·knockback·slow·stealth·dot·mark·empower·multishot·aura_dot·foxfire·dash</text><text class="vr-code" x="142" y="675">skills.js Buff update ⇄ state.js dealDamage / Unit.addBuff / Unit.isLocked</text></g>
  ${sourceRail(['src/game/state.js:dealDamage/onUnitDied','src/game/skills.js:scale/Buffs','src/config.js:ECON/CRYSTAL_CFG'], '结算图证明战斗结果跨越状态、经济与终局，不证明穿透、韧性等商业数值体系完整或平衡。',704)}`);

figures['KING-V20'] = overlaySvg('KING-V20', '河道交战的画面—代码联证图', '十个直接标注把画面中的战术信息、世界实体、AI决策、状态投影和输入回调连到冻结源码。', [
  {n:1,x:14,y:16,targetX:110,targetY:112,title:'小地图视图',code:'minimap.js:update/render',note:'地形·塔·单位·相机框',tone:'blue'},
  {n:2,x:14,y:94,targetX:640,targetY:22,title:'比分与计时',code:'state.score / state.time',note:'固定tick累计同步',tone:'blue'},
  {n:3,x:14,y:172,targetX:640,targetY:330,title:'英雄模型组合',code:'models.js:createHeroModel',note:'身体·武器·阵营圈',tone:'gold'},
  {n:4,x:14,y:250,targetX:626,targetY:438,title:'兵线双方交战',code:'ai.js:updateMinion',note:'索敌·攻击·推进',tone:'blue'},
  {n:5,x:14,y:328,targetX:612,targetY:247,title:'世界血条投影',code:'hud.js:updateWorldBars',note:'3D position → screen DOM',tone:'red'},
  {n:6,x:948,y:16,targetX:660,targetY:385,title:'HeroAI决策',code:'ai.js:HeroAI.update',note:'团战/回防/集团推进',tone:'gold',observed:false},
  {n:7,x:948,y:94,targetX:676,targetY:402,title:'伤害与控制',code:'state.js:dealDamage',note:'抗性·护盾·死亡分发',tone:'red',observed:false},
  {n:8,x:948,y:172,targetX:700,targetY:365,title:'命中特效对象池',code:'vfx.js:burst/tracer',note:'弹道·环·飘字',tone:'red'},
  {n:9,x:948,y:250,targetX:1145,targetY:660,title:'技能动作队列',code:'hud→main→queueAction',note:'Q/E/R方向在30Hz消费',tone:'blue'},
  {n:10,x:948,y:328,targetX:655,targetY:762,title:'经济成长面板',code:'shop.js + hud.js',note:'等级·金币·KDA·6格装备',tone:'blue'},
], '● 绿=截图直接可见   ● 金=冻结源码推导   实线=画面实体   虚线=行为规则');

figures['KING-V21'] = svg('KING-V21', '从选将到水晶结算再回到新对局的状态机', '展示选择英雄、建立GameState、分路发育、资源争夺、团战、推塔、水晶死亡、结算表和重新开始的完整控制流。', `${titleBand('MATCH STATE MACHINE','可玩闭环不是“能走动”，而是从入口到终局再回到入口','上方两张冻结图像分别观测选将和结算；中段路径由代码、对局录屏与验收记录共同支撑')}
  <g><rect class="vr-panel vr-panel-blue" x="28" y="110" width="210" height="470" rx="12"/><text class="vr-section" x="48" y="136">ENTRY</text>${node({x:50,y:158,w:166,h:88,title:'选将界面',lines:['5张英雄卡','定位·技能摘要'],code:'Screens.showSelect',type:'blue'})}${node({x:50,y:274,w:166,h:88,title:'锁定英雄',lines:['onSelect(heroId)'],code:'startGame(heroId)',type:'gold'})}${node({x:50,y:390,w:166,h:128,title:'建立世界',lines:['Map·VFX·GameState','1 player + 9 AI','HUD·Minimap·Screens'],code:'new GameState(...)',type:'green'})}</g>
  <g><rect class="vr-panel vr-panel-red" x="270" y="110" width="660" height="470" rx="12"/><text class="vr-section" x="290" y="136">RUNNING MATCH · state.over = false</text>
  ${[['01 对线','三路兵线·补刀·发育'],['02 野区','8营地·红蓝BUFF'],['03 资源','8:00暴君·10:00主宰'],['04 团战','回防·支援·死亡重生'],['05 推塔','T1→T2→T3·超级兵'],['06 强攻水晶','解防·守卫减伤·泉水']].map((a,i)=>node({x:294+(i%2)*312,y:158+Math.floor(i/2)*118,w:286,h:92,title:a[0],lines:[a[1]],code:['Spawner/HeroAI','Spawner.camps','_findObjective','_findTeamfight','isTowerInvuln','isCrystalInvuln'][i],type:i<2?'cyan':i<4?'red':'gold',detail:`对局中段${a[0]}：${a[1]}。`})).join('')}
  ${edge('KING-V21','M238 345H270',{type:'event',label:'start',x:241,y:336})}</g>
  <g><rect class="vr-panel vr-panel-gold" x="962" y="110" width="210" height="470" rx="12"/><text class="vr-section" x="982" y="136">TERMINAL + RESTART</text>${node({x:984,y:158,w:166,h:88,title:'水晶死亡',lines:['kind=crystal'],code:'onUnitDied',type:'red'})}${node({x:984,y:274,w:166,h:88,title:'写入胜方',lines:['over=true·winner=team'],code:'endGame()',type:'gold'})}${node({x:984,y:390,w:166,h:88,title:'结算表',lines:['KDA·补刀·等级'],code:'Screens.showResult()',type:'blue'})}${node({x:984,y:500,w:166,h:58,title:'再来一局',lines:['reload / restart'],code:'restart button',type:'green'})}${edge('KING-V21','M930 345H962',{type:'event',label:'winner',x:932,y:336})}</g>
  <path class="vr-edge-feedback" marker-end="url(#kv21-arrow)" d="M1067 580C1067 652 133 652 133 580"/><text class="vr-label" x="480" y="648">restart → 新的GameState，不复用上一局单位状态</text>
  <g><rect class="vr-panel vr-panel-muted" x="270" y="600" width="660" height="78" rx="10"/><text class="vr-section" x="290" y="624">EVIDENCE BINDING</text><text class="vr-note" x="290" y="647">选将图像：screenshot_qa_1786228500285.png  ·  结算图像：screenshot_qa_1786229287687.png</text><text class="vr-code" x="290" y="667">state.js:endGame → main.js:state.events.on('gameOver') → screens.js:showResult</text></g>
  ${sourceRail(['src/ui/screens.js','src/main.js:startGame','src/game/state.js:endGame'], '首尾图像和终局代码证明入口/结算存在，两张图本身不能独立证明中间所有路径。',704)}`);

figures['KING-V22'] = svg('KING-V22', '经济与成长：一次战斗如何改变下一次战斗', '展示补兵、英雄击杀、助攻、破塔、暴君主宰和被动收入，如何转换为金币经验、等级技能和12件装备，再反馈到推进。', `${titleBand('ECONOMY FEEDBACK LOOP','战斗结果被持久化为英雄成长，成长又改变下一次结算','这个正反馈回路是MOBA“发育→滚雪球→终局”的基础，也是最需平衡的部分')}
  <g><rect class="vr-panel vr-panel-green" x="28" y="112" width="330" height="500" rx="12"/><text class="vr-section" x="50" y="138">INCOME EVENTS</text>${[['小兵补刀','36 / 42 / 90 / 120金'],['英雄击杀','killer +200金/+200经验'],['助攻','助攻者+80金'],['破塔','全队每人+200金'],['暴君','全队+150金/+100经验'],['主宰','下波兵×1.8'],['被动金','2:00后 2金/s']].map((a,i)=>node({x:52,y:158+i*61,w:282,h:51,title:a[0],lines:[a[1]],code:['MINIONS','HERO_KILL','ASSIST','TOWER_TEAM','TYRANT','OVERLORD','PASSIVE'][i],type:i<4?'green':'gold',detail:`${a[0]}收益：${a[1]}。`})).join('')}</g>
  <g><rect class="vr-panel vr-panel-cyan" x="385" y="112" width="430" height="500" rx="12"/><text class="vr-section" x="407" y="138">PERSISTENT HERO STATE</text>${node({x:410,y:165,w:380,h:92,title:'Gold',lines:['购买时扣除·上限未设'],code:'giveGold / Shop.buy',type:'gold'})}${node({x:410,y:282,w:380,h:92,title:'Experience → Level 1–15',lines:['半径14共享·14段EXP_TABLE'],code:'giveExp / levelUp',type:'cyan'})}${node({x:410,y:399,w:380,h:92,title:'Skill levels',lines:['1级自带S1·4/8/12大招'],code:'SKILL_ORDER',type:'blue'})}${node({x:410,y:516,w:380,h:72,title:'Stats',lines:['HP/MP/AD/AP/armor/mres'],code:'HEROES.growth',type:'red'})}</g>
  <g><rect class="vr-panel vr-panel-gold" x="842" y="112" width="330" height="500" rx="12"/><text class="vr-section" x="864" y="138">12 ITEMS / 6 SLOTS</text>${[['基础攻击','铁剑300·攻速匕400'],['基础法术/防御','法典500·布甲/斗篷300'],['移速','神速之靴300'],['高级物理','破军2200·无尽2100'],['高级法术','博学者之怒2300'],['高级防御','红莲/魔女1800'],['续航','霸者2000·HP+1500']].map((a,i)=>node({x:866,y:158+i*61,w:282,h:51,title:a[0],lines:[a[1]],code:'ITEMS',type:i<3?'cyan':'gold',detail:`装备组${a[0]}：${a[1]}。`})).join('')}</g>
  ${edge('KING-V22','M358 360H385',{type:'event',label:'reward',x:360,y:350})}${edge('KING-V22','M815 360H842',{type:'data',label:'buy',x:817,y:350})}<path class="vr-edge-feedback" marker-end="url(#kv22-arrow)" d="M1007 612C1007 684 193 684 193 612"/><text class="vr-label" x="446" y="678">战斗力提升 → 更快清线/推塔/抢目标 → 更多收益</text>
  ${sourceRail(['src/config.js:ECON/EXP_TABLE/HEROES','src/game/state.js:giveGold/giveExp/levelUp','src/game/shop.js:ITEMS/Shop'], '回路证明经济数字会改写战斗面板，不证明装备价格、经验曲线、阵营经济或滚雪球强度已平衡。',704)}`);

figures['KING-V23'] = svg('KING-V23', '一局比赛中并行推进的长短时间系统', '展示首波兵线、周期出兵、被动金、暴君、主宰、小兵成长，以及技能CD、回城、Buff、召唤师技能、重生和多杀窗口。', `${titleBand('MATCH TIME SYSTEM','一个time值同时驱动出兵、收入、资源、成长、重生和冷却','时间系统并行，并不是简单按顺序播放的演示时间轴')}
  <g><rect class="vr-panel vr-panel-blue" x="28" y="112" width="1144" height="270" rx="12"/><text class="vr-section" x="48" y="138">LONG MATCH CLOCK · seconds</text><path class="vr-edge" marker-end="url(#kv23-arrow)" d="M72 260H1135"/>${[['00:00','建立英雄/建筑','state.time=0'],['00:10','首波兵线','WAVE.FIRST'],['00:30+','每30s出兵','WAVE.INTERVAL'],['02:00','被动金2/s','PASSIVE_GOLD'],['08:00','暴君首刷','TYRANT_FIRST'],['10:00','主宰首刷','OVERLORD_FIRST'],['12:00+','小兵每分钟+8%','MINION_GROWTH']].map((a,i)=>{const x=60+i*160,y=i%2?274:154;return `<g class="pv-node" tabindex="0" data-detail="${a[1]}：${a[2]}。"><path class="vr-edge-muted" d="M${x+72} 260V${i%2?274:242}"/><rect class="vr-panel ${i>3?'vr-panel-gold':'vr-panel-blue'}" x="${x}" y="${y}" width="144" height="74" rx="9"/><text class="vr-value" x="${x+14}" y="${y+26}">${a[0]}</text><text class="vr-note" x="${x+14}" y="${y+48}">${a[1]}</text><text class="vr-code" x="${x+14}" y="${y+66}">${a[2]}</text></g>`;}).join('')}</g>
  <g><rect class="vr-panel vr-panel-red" x="28" y="408" width="550" height="270" rx="12"/><text class="vr-section" x="50" y="434">SHORT COMBAT TIMERS</text>${[['S1 / S2 / ULT','8 / 10 / 40s，大招每级-3s'],['回城','8s引导；受伤打断'],['恢复 / 闪现','90s / 120s'],['红蓝BUFF','60s；红灼烧2ticks'],['多杀 / 团灭','8s窗口 / 存活数检查']].map((a,i)=>node({x:52,y:454+i*41,w:502,h:34,title:a[0],lines:[],code:a[1],type:i<2?'red':'gold',detail:`${a[0]}计时：${a[1]}。`})).join('')}</g>
  <g><rect class="vr-panel vr-panel-green" x="602" y="408" width="570" height="270" rx="12"/><text class="vr-section" x="624" y="434">RESPAWN / RECOVERY TIMERS</text>${node({x:626,y:456,w:522,h:72,title:'英雄重生',lines:['respawnAt = time + 8 + 2×level','1级10s → 15级38s'],code:'RESPAWN.BASE/PER_LEVEL',type:'red'})}${node({x:626,y:546,w:252,h:94,title:'野怪刷新',lines:['普通60s','暴君180s·主宰240s'],code:'JUNGLE.RESPAWN',type:'green'})}${node({x:896,y:546,w:252,h:94,title:'脱战回复',lines:['英雄5s后0.6%/s','水晶5s后1%/s'],code:'lastCombatT',type:'blue'})}</g>
  ${sourceRail(['src/config.js:WAVE/ECON/JUNGLE/RESPAWN','src/game/spawner.js:update','src/game/state.js:update'], '时间轴证明多个计时器在同一对局并行，不证明节奏与商业《王者荣耀》当前规则一致或已平衡。',704)}`);

figures['KING-V24'] = svg('KING-V24', '程序化表现管线：地表、模型、特效与声音如何被实时造出来', '展示Canvas 2D地表纹理、Three.js程序化模型、对象池VFX、WebAudio和speechSynthesis如何与WebGL、DOM和Canvas UI合成。', `${titleBand('PROCEDURAL PRESENTATION PIPELINE','没有运行时外部HTTP素材，不等于没有表现系统','地图、角色、特效和声音都由本地代码与vendored Three.js在浏览器合成')}
  <g><rect class="vr-panel vr-panel-gold" x="350" y="105" width="500" height="88" rx="12"/><text class="vr-section" x="372" y="130">INPUT ASSETS</text><text class="vr-title" x="372" y="157">配置数据 + 简化几何 + 程序化材质</text><text class="vr-code" x="372" y="179">config.js · map.js · models.js · vfx.js · audio.js</text></g>
  ${node({x:28,y:238,w:270,h:220,title:'Canvas MAP',tag:'2048² TEXTURE',lines:['草地/土路/河床/石台','河水波纹/龙坑符文','CanvasTexture → plane'],code:'map.js:createGroundTexture',type:'green',value:'2048²'})}
  ${node({x:318,y:238,w:270,h:220,title:'MODEL FACTORY',tag:'THREE.JS GEOMETRY',lines:['5英雄·4小兵·5野怪','身体/头饰/武器/阵营圈','userData.update 驱动动作'],code:'models.js:create*Model',type:'blue'})}
  ${node({x:608,y:238,w:270,h:220,title:'VFX POOLS',tag:'OBJECT REUSE',lines:['particles 1024 · tracers 48','shock 12 · pillar 10 · shell 12','burst/ring/beam/arrow/glow'],code:'vfx.js:VFX',type:'red',value:'1,106'})}
  ${node({x:898,y:238,w:274,h:220,title:'AUDIO',tag:'SYNTHESIS',lines:['oscillator tone · noise · arp','WebAudio攻击/技能音','speechSynthesis中文播报'],code:'audio.js:AudioEngine',type:'cyan'})}
  ${edge('KING-V24','M470 193L163 238',{type:'data'})}${edge('KING-V24','M555 193L453 238',{type:'data'})}${edge('KING-V24','M645 193L743 238',{type:'data'})}${edge('KING-V24','M730 193L1035 238',{type:'data'})}
  <g><rect class="vr-panel vr-panel-gold" x="100" y="500" width="1000" height="118" rx="12"/><text class="vr-section" x="122" y="525">BROWSER COMPOSITOR</text><text class="vr-value" x="122" y="555">WebGL Scene</text><text class="vr-note" x="270" y="555">+  DOM HUD / world bars / banners</text><text class="vr-note" x="590" y="555">+  Canvas minimap</text><text class="vr-note" x="800" y="555">+  WebAudio / TTS</text><path class="vr-divider" d="M122 570H1078"/><text class="vr-code" x="122" y="594">engine.render() · hud.update() · minimap.update() · vfx.update() · audio.play()/speak()</text></g>
  <g><rect class="vr-panel vr-panel-muted" x="170" y="640" width="860" height="45" rx="8"/><text class="vr-boundary" x="192" y="666">依赖边界：浏览器·本地vendored Three.js·系统语音·Python静态服务器；因此不可称为“零依赖”。</text></g>
  ${sourceRail(['src/world/map.js','src/world/models.js','src/engine/vfx.js','src/engine/audio.js','src/ui/hud.js'], '该图证明主要运行时视听内容由代码生成，不证明工业级美术、动作、音频品质或无第三方依赖。',704)}`);

figures['KING-V25'] = overlaySvg('KING-V25', 'HUD画面—代码联证：十一类信息与输入同步', '十一个直接标注将小地图、比分、时间、世界血条、生命法力、等级金币、装备、商店、技能、召唤师技能、回城和普攻连接到数据源。', [
  {n:1,x:14,y:14,targetX:108,targetY:112,title:'小地图',code:'minimap.update(state,dt)',note:'200px·0.25s节流',tone:'blue'},
  {n:2,x:14,y:90,targetX:640,targetY:22,title:'比分 / 计时',code:'state.score / state.time',note:'固定tick事实',tone:'blue'},
  {n:3,x:14,y:166,targetX:660,targetY:366,title:'世界血条',code:'hud.updateWorldBars',note:'3D坐标投影DOM',tone:'red'},
  {n:4,x:14,y:242,targetX:470,targetY:760,title:'英雄面板',code:'hud.update()',note:'等级·HP/MP·KDA·金币',tone:'blue'},
  {n:5,x:14,y:318,targetX:670,targetY:760,title:'6格装备栏',code:'Shop.slots → HUD',note:'购买后立即刷新属性',tone:'gold'},
  {n:6,x:950,y:14,targetX:836,targetY:760,title:'商店入口',code:'screens.js:showShop',note:'12件装备+推荐购买',tone:'gold'},
  {n:7,x:950,y:90,targetX:1115,targetY:615,title:'Q / E / R技能',code:'HUD._bindAimButton',note:'CD遮罩·蓝耗·拖拽方向',tone:'red'},
  {n:8,x:950,y:166,targetX:1028,targetY:714,title:'恢复 / 闪现',code:'onHeal / onFlash',note:'90s / 120s独立CD',tone:'blue'},
  {n:9,x:950,y:242,targetX:960,targetY:746,title:'回城',code:'state.tryRecall()',note:'8s引导·受伤中断',tone:'gold'},
  {n:10,x:950,y:318,targetX:1195,targetY:710,title:'普攻按钮',code:'state.setAttackHeld',note:'按住追击·统一结算',tone:'red'},
  {n:11,x:950,y:394,targetX:640,targetY:430,title:'飘字 / 播报',code:'hud.floatText / banner',note:'damage·heal·level·kill',tone:'blue'},
], '绿点=画面可见元素  金点=交互/状态规则  HUD不是静态皮肤：它同时读取状态并写入命令');

figures['KING-V26'] = svg('KING-V26', '最终产物的能力、观测范围与明确边界', '用四层证据金字塔区分有代码且有运行证据、有代码但只在限定路径观测、静态结构可证明以及明确没有实现的商业游戏能力。', `${titleBand('CAPABILITY / EVIDENCE BOUNDARY','复杂性需要被证明，但不能被放大成商业完成度','每一层同时写“能证明”和“不能证明”，避免把截图、源码、验收和架构推断混为一类证据')}
  ${node({x:80,y:112,w:1040,h:132,title:'A · 代码 + 运行证据共同支持',tag:'STRONGEST CLAIM',lines:['单机3D峡谷·10名英雄·三路兵线·塔/水晶·技能/装备·HUD/小地图','选将→对局→水晶胜负→结算/重开；最终观察窗口无控制台错误'],code:'code snapshot + screenshots + recordings + acceptance',type:'green',value:'implemented'})}
  ${node({x:140,y:270,w:920,h:116,title:'B · 仅在限定路径和环境观测',tag:'BOUNDED RUNTIME',lines:['4个英雄回归·4局自动演示·12件商店物品·指定浏览器与观察窗口','不能外推为所有技能组合、长期稳定、跨浏览器、平衡性或性能上限'],code:'browser-verification.json + QA screenshots',type:'gold',value:'observed'})}
  ${node({x:200,y:412,w:800,h:106,title:'C · 静态源码结构可复算',tag:'STRUCTURAL EVIDENCE',lines:['17模块·39导入边·7,979行·180×180地图·15技能·9英雄AI·对象池','能证明运行图与职责存在；不能证明代码质量、无缺陷或测试充分'],code:'verify-king-case.mjs + frozen code',type:'blue',value:'reproducible'})}
  ${node({x:260,y:544,w:680,h:116,title:'D · 明确未实现',tag:'OUT OF SCOPE',lines:['联网多人·房间匹配·帧同步/重连·账号云存档·反作弊·服务器扩缩','工业级美术动作音频·海量英雄·长期数值、赛事和运营体系'],code:'accurate label: 单机Web 5v5 MOBA原型',type:'red',value:'not built'})}
  <path class="vr-edge-data" d="M600 244V270M600 386V412M600 518V544"/>
  ${sourceRail(['src/config.js','src/main.js','src/game/state.js','E31–E38','verification.json schema v4'], '26张图能说明跨系统实现的复杂度，不能把原型变成商业《王者荣耀》，也不证明平台对其他任务的平均成功率。',704)}`);

let report = readFileSync(reportPath, 'utf8');
for (const [id, replacement] of Object.entries(figures)) {
  const figurePattern = new RegExp(`(<figure class="product-viz"[^>]*data-viz-code="${id}"[\\s\\S]*?<\\/figure>)`);
  const match = report.match(figurePattern);
  if (!match) throw new Error(`Missing figure ${id}`);
  const svgCount = (match[1].match(/<svg\b/g) ?? []).length;
  if (svgCount !== 1) throw new Error(`${id} expected exactly one SVG, found ${svgCount}`);
  const updated = match[1].replace(/<svg\b[\s\S]*?<\/svg>/, replacement);
  report = report.replace(match[1], updated);
}

if (Object.keys(figures).length !== 26) throw new Error(`Expected 26 diagrams, found ${Object.keys(figures).length}`);
writeFileSync(reportPath, report);
console.log(JSON.stringify({ reportPath, diagrams: Object.keys(figures).length, bytes: Buffer.byteLength(report) }, null, 2));
