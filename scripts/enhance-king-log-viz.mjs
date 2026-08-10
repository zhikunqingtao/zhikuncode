#!/usr/bin/env node

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const reportPath = resolve(repoRoot, 'docs/case-studies/zhikuncode开发王者荣耀.html');
let html = readFileSync(reportPath, 'utf8');

function markerBlock(id, content) {
  return `<!-- ${id}:START -->\n${content.trim()}\n<!-- ${id}:END -->`;
}

function upsertBefore(id, anchor, content) {
  const block = markerBlock(id, content);
  const pattern = new RegExp(`<!-- ${id}:START -->[\\s\\S]*?<!-- ${id}:END -->`);
  if (pattern.test(html)) html = html.replace(pattern, block);
  else {
    if (!html.includes(anchor)) throw new Error(`Anchor for ${id} not found: ${anchor}`);
    html = html.replace(anchor, `${block}\n${anchor}`);
  }
}

function replaceRange(id, startAnchor, endAnchor, content) {
  const block = markerBlock(id, content);
  const markerPattern = new RegExp(`<!-- ${id}:START -->[\\s\\S]*?<!-- ${id}:END -->`);
  if (markerPattern.test(html)) {
    html = html.replace(markerPattern, block);
    return;
  }
  const start = html.indexOf(startAnchor);
  const end = html.indexOf(endAnchor, start + startAnchor.length);
  if (start < 0 || end < 0) throw new Error(`Range for ${id} not found`);
  html = `${html.slice(0, start)}${block}\n${html.slice(end)}`;
}

const css = `
.log-viz{margin:24px 0;border:1px solid var(--line2);border-radius:14px;overflow:hidden;background:linear-gradient(180deg,#0e1729,#080d17)}
.log-viz .pv-head{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:13px 16px;border-bottom:1px solid var(--line);background:#111b31}
.log-viz .pv-code{font:800 11px var(--mono);letter-spacing:.08em;color:var(--gold2);border:1px solid #d3b36a55;border-radius:999px;padding:3px 9px}
.log-viz .pv-head strong{font-size:15px;color:var(--tx)}
.log-viz .pv-kind{margin-left:auto;font:11px var(--mono);color:var(--mut)}
.log-viz-stage{overflow:hidden;padding:14px;background:radial-gradient(circle at 50% 12%,#22355655,transparent 54%)}
.log-viz .pv-svg{display:block;width:100%;height:auto;min-width:0;max-height:none}
.log-viz .pv-proof{grid-template-columns:1fr 1fr}
.log-viz .pv-node:focus{outline:2px solid var(--gold2);outline-offset:3px}
.log-viz .log-axis{stroke:#405a80;stroke-width:1}.log-viz .log-grid{stroke:#263a58;stroke-width:1;stroke-dasharray:3 6}
.log-viz .log-ledger-ok{fill:#153327;stroke:#7cc79b;stroke-width:1.5}.log-viz .log-ledger-fail{fill:#381d28;stroke:#e06c75;stroke-width:1.5}
.log-viz .log-observed{fill:#102846;stroke:#5b8dd9;stroke-width:1.4}.log-viz .log-terminal{fill:#123125;stroke:#7cc79b;stroke-width:1.4}.log-viz .log-boundary{fill:#38271a;stroke:#d3b36a;stroke-width:1.4}
@media(max-width:560px){.log-viz-stage{overflow-x:auto;padding:8px}.log-viz .pv-svg{width:1200px;max-width:none;min-width:1200px}.log-viz .pv-proof{grid-template-columns:1fr}.log-viz .pv-kind{margin-left:0;width:100%}}
@media print{.log-viz-stage{overflow:visible}.log-viz .pv-svg{width:100%;min-width:0}.log-viz .pv-inspector{display:none}.log-viz{break-inside:avoid}}
`;
upsertBefore('LOG-VIZ-CSS', '.footer{margin-top:70px', css);

const logV01 = `
<h3 id="traceability-evidence">3.1bb 标识符级追溯：从根 Run 到 Python 请求的七层血缘链 <span class="tag t-log">日志实测</span> <span class="tag t-verified">复合键</span></h3>
<p>冻结应用日志不是靠时间猜测归属。MDC 字段把协调者、Worker、轮次、模型请求、工具调用与下游请求串成一条可重建链；下面使用 <b>01:55:36</b> 的真实 <code>WebBrowser_26</code> 调用，不使用示意ID。</p>
<div class="table-wrap"><table id="trace-field-dictionary">
  <thead><tr><th>原始字段</th><th>报告别名</th><th>关联用途</th><th>解释边界</th></tr></thead>
  <tbody>
    <tr><td><code>sid</code></td><td>sessionId</td><td>区分根会话与10个Worker子会话</td><td>本地标识，不是第三方身份凭证</td></tr>
    <tr><td><code>rid</code></td><td>runId</td><td>区分一次Agent Loop执行</td><td>同一会话可随时间出现不同Run</td></tr>
    <tr><td><code>prid</code></td><td>parentRunId</td><td>把10个子Run直接连回根Run</td><td><code>-</code>表示该日志行没有父Run</td></tr>
    <tr><td><code>agent=query|subagent</code></td><td>执行角色</td><td>区分协调者与Worker</td><td>角色来自MDC；观测事件的<code>agentType=general-purpose</code>不用于角色判定</td></tr>
    <tr><td><code>turn</code></td><td>Agent轮次</td><td>把模型与工具放回具体循环轮次</td><td>轮次不是质量评分</td></tr>
    <tr><td><code>llm</code> / <code>data.requestId</code></td><td>llmRequestId</td><td>配对LLM started与唯一终态</td><td>失败后新requestId不是同一请求重试</td></tr>
    <tr><td>MDC <code>tool</code> / 正文<code>toolUseId</code></td><td>toolUseId</td><td>与sessionId、runId组成工具复合键</td><td>裸序号会跨Run复用</td></tr>
    <tr><td>Python日志<code>requestId</code></td><td>downstreamRequestId</td><td>连接Java工具与Python浏览器服务</td><td>只证明请求对应，不证明页面判断正确</td></tr>
  </tbody>
</table></div>
<figure class="log-viz" data-viz-code="LOG-V01" data-evidence="frozen-app-log">
  <div class="pv-head"><span class="pv-code">LOG-V01</span><strong>一次真实调用的七层血缘链</strong><span class="pv-kind">38,626条MDC行 · 01:55:36实录</span></div>
  <div class="log-viz-stage"><svg class="pv-svg viz-rich" data-viz-rich="true" viewBox="0 0 1200 790" role="img" aria-labelledby="log-v01-title log-v01-desc">
    <title id="log-v01-title">LOG-V01 一次真实WebBrowser调用的七层标识符血缘链</title>
    <desc id="log-v01-desc">从根会话与根Run，经Worker子会话与子Run、Turn 11、LLM请求、WebBrowser工具、Python请求，到3244毫秒完成记录。右侧解释复合键与字段来源。</desc>
    <defs><marker id="log-v01-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10Z" fill="#6bb8c9"/></marker></defs>
    <rect class="vr-frame" x="18" y="18" width="1164" height="754" rx="12"/>
    <text class="vr-title" x="40" y="52">01:55:36 · WebBrowser_26 / navigate · 标识符级血缘</text>
    <text class="vr-subtitle" x="40" y="76">每一层都来自同一冻结日志行的MDC或正文键值；箭头表示字段直接关联，不是时间近邻猜测。</text>
    <rect class="vr-panel-muted" x="40" y="96" width="740" height="584" rx="10"/>
    <text class="vr-section" x="62" y="126">SEVEN-LEVEL TRACE</text>
    <path class="vr-edge-data" d="M142 172V623" marker-end="url(#log-v01-arrow)"/>
    <g class="pv-node" tabindex="0" data-detail="L1 根上下文：sid=b8f86099…，rid=eb2e9ba2…；协调者角色由MDC agent=query给出。"><circle class="vr-panel-gold" cx="142" cy="174" r="28"/><text class="vr-value" x="133" y="181">1</text><text class="vr-label" x="194" y="164">ROOT SESSION + ROOT RUN</text><text class="vr-code" x="194" y="185">sid b8f86099-452d-4ba6-89c2-c3fee8f4b422</text><text class="vr-code" x="194" y="203">rid eb2e9ba2-6975-4c83-9e83-8eebcb7f1b10 · agent=query</text></g>
    <g class="pv-node" tabindex="0" data-detail="L2 Worker：childSession=subagent-agent-64f62e42，childRun=b11d33bb…，prid直接指向根Run eb2e9ba2…。"><circle class="vr-panel-blue" cx="142" cy="250" r="28"/><text class="vr-value" x="133" y="257">2</text><text class="vr-label" x="194" y="238">WORKER SESSION + CHILD RUN</text><text class="vr-code" x="194" y="259">sid subagent-agent-64f62e42 · rid b11d33bb-4d08-477b-88fa-977ddfba67c9</text><text class="vr-code" x="194" y="277">prid eb2e9ba2-6975-4c83-9e83-8eebcb7f1b10 · agent=subagent</text></g>
    <g class="pv-node" tabindex="0" data-detail="L3 Agent Loop轮次为11；turn只定位执行序列，不表示测试编号或质量。"><circle class="vr-panel-cyan" cx="142" cy="326" r="28"/><text class="vr-value" x="133" y="333">3</text><text class="vr-label" x="194" y="320">TURN 11</text><text class="vr-note" x="194" y="341">QueryEngine循环位置 · 01:55:32.269开始模型流</text></g>
    <g class="pv-node" tabindex="0" data-detail="L4 LLM请求：llm-72c74176…；该ID同时存在于后续工具MDC中。"><circle class="vr-panel-green" cx="142" cy="402" r="28"/><text class="vr-value" x="133" y="409">4</text><text class="vr-label" x="194" y="394">LLM REQUEST</text><text class="vr-code" x="194" y="417">llm-72c74176-a7d6-49e0-adb2-7f16dc9a1045 · kimi-k3</text><text class="vr-note" x="194" y="436">HTTP 200 → tool_use块；不等于工具已经成功</text></g>
    <g class="pv-node" tabindex="0" data-detail="L5 工具复合键：subagent-agent-64f62e42 + b11d33bb… + WebBrowser_26；stage1与stage5都可定位。"><circle class="vr-panel-gold" cx="142" cy="478" r="28"/><text class="vr-value" x="133" y="485">5</text><text class="vr-label" x="194" y="470">TOOL USE</text><text class="vr-code" x="194" y="493">WebBrowser_26 · stage 1 validation → stage 5 call</text><text class="vr-note" x="194" y="512">复合键 sid + rid + toolUseId；裸WebBrowser_26会在其他Run复用</text></g>
    <g class="pv-node" tabindex="0" data-detail="L6 下游请求：Java PythonCapabilityAwareClient POST /api/browser/navigate，requestId=97ad9951…。"><circle class="vr-panel-blue" cx="142" cy="554" r="28"/><text class="vr-value" x="133" y="561">6</text><text class="vr-label" x="194" y="546">JAVA → PYTHON REQUEST</text><text class="vr-code" x="194" y="569">POST /api/browser/navigate · requestId 97ad9951-84cb-4b7a-8c5e-a1458a3c2c9f</text><text class="vr-note" x="194" y="588">attempt=1 · bodyLength=124 · bodyFingerprint=608925d6…</text></g>
    <g class="pv-node" tabindex="0" data-detail="L7 工具完成：01:55:39.514，3244ms，error=false；这证明工具管线闭合，不自动证明浏览器观察结论正确。"><circle class="vr-panel-green" cx="142" cy="630" r="28"/><text class="vr-value" x="133" y="637">7</text><text class="vr-label" x="194" y="622">TERMINAL RECORD</text><text class="vr-code" x="194" y="645">Tool WebBrowser completed in 3244ms (error=false)</text><text class="vr-note" x="194" y="664">01:55:39.514 · ToolExecutionPipeline</text></g>
    <rect class="vr-panel" x="804" y="96" width="354" height="240" rx="10"/>
    <text class="vr-section" x="826" y="126">GLOBAL TRACE GRAPH</text>
    <g class="pv-node" tabindex="0" data-detail="冻结窗口内共有11个非空sessionId、11个非空runId、10条Worker childRun→parentRun映射。"><text class="vr-value" x="826" y="166">11 sessions</text><text class="vr-value" x="826" y="202">11 runs</text><text class="vr-value" x="826" y="238">10 parent-child edges</text><text class="vr-note" x="826" y="270">10个Worker均有独立childSession + childRun</text><text class="vr-note" x="826" y="291">全部prid直接指向根Run eb2e9ba2…</text><text class="vr-boundary" x="826" y="318">agent角色以MDC为准</text></g>
    <rect class="vr-panel" x="804" y="354" width="354" height="326" rx="10"/>
    <text class="vr-section" x="826" y="384">WHY COMPOSITE KEYS MATTER</text>
    <g class="pv-node" tabindex="0" data-detail="968个sid+rid+toolUseId复合键只对应422个裸toolUseId；有546次序号复用，裸序号不能唯一定位工具调用。"><text class="vr-value" x="826" y="426">968</text><text class="vr-label" x="904" y="426">复合工具键</text><text class="vr-value" x="826" y="466">422</text><text class="vr-label" x="904" y="466">裸工具序号</text><rect class="vr-metric-track" x="826" y="488" width="300" height="18" rx="4"/><rect class="vr-metric-fill" x="826" y="488" width="131" height="18" rx="4"/><text class="vr-note" x="826" y="529">裸序号唯一性仅422 / 968</text><text class="vr-boundary" x="826" y="552">复合键避免546次潜在碰撞</text></g>
    <g class="pv-node" tabindex="0" data-detail="376个WebBrowser复合键分别对应一个唯一Python requestId：376/376，缺失0，重复0。"><text class="vr-value" x="826" y="594">376 ↔ 376</text><text class="vr-label" x="826" y="620">WebBrowser复合键 ↔ Python requestId</text><text class="vr-note" x="826" y="644">missing 0 · duplicate 0 · unique 376</text><text class="vr-boundary" x="826" y="668">对应关系≠376项测试全部成功</text></g>
    <rect class="vr-source-rail" x="40" y="700" width="1118" height="52" rx="8"/>
    <text class="vr-section" x="58" y="722">FROZEN SOURCE</text><text class="vr-source" x="188" y="722">logs/app-session-20260809-0130-0701.log · lines 1202–1226 · verification.json.logs.traceability / toolIdentity</text>
    <text class="vr-boundary" x="58" y="742">BOUNDARY  标识符证明执行链可重建；不证明Worker质量、浏览器判断正确或多Agent优于单Agent。</text>
  </svg></div>
  <div class="pv-inspector" aria-live="polite">点击或聚焦任一层，可查看该字段如何把真实调用串回根Run。</div>
  <div class="pv-proof"><p><b>能证明：</b>冻结窗口可重建11个会话、11个Run、10条父子关系；此浏览器调用可从根Run追到唯一Python请求与终态。</p><p class="cannot"><b>不能证明：</b>标识符关联不评价Worker产出质量，不把376次调用变成376项成功测试，也不证明调度策略最优。</p></div>
</figure>`;
upsertBefore('LOG-V01', '<h3 id="runtime-mechanism-map">', logV01);

const logV03 = `
<h3 id="failure-audit-evidence">3.2d 局部故障—明确终态—后续活动：透明不等于全部恢复 <span class="tag t-log">日志实测</span> <span class="tag t-limit">因果边界</span></h3>
<figure class="log-viz" data-viz-code="LOG-V03" data-evidence="app-log+observability">
  <div class="pv-head"><span class="pv-code">LOG-V03</span><strong>局部故障、终态与接续证据泳道</strong><span class="pv-kind">5类信号 · 不把时间后继写成自动修复</span></div>
  <div class="log-viz-stage"><svg class="pv-svg viz-rich" data-viz-rich="true" viewBox="0 0 1200 820" role="img" aria-labelledby="log-v03-title log-v03-desc">
    <title id="log-v03-title">LOG-V03 局部故障、终态和后续活动泳道</title>
    <desc id="log-v03-desc">五条泳道分别展示模型取消、工具结构化错误、Worker期限和轮次终止、MCP断线重连、原子写入与Checkpoint。每条分开记录发生、终态和随后可确认事实，并保留因果边界。</desc>
    <defs><marker id="log-v03-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10Z" fill="#6d82a7"/></marker></defs>
    <rect class="vr-frame" x="18" y="18" width="1164" height="784" rx="12"/>
    <text class="vr-title" x="40" y="52">失败不是被剪掉的噪声：每类事件有独立终态</text>
    <text class="vr-subtitle" x="40" y="76">列方向：发生记录 → 明确终态/配对 → 随后可确认活动；连线只表示日志顺序，不自动表达修复因果。</text>
    <text class="vr-section" x="52" y="118">SIGNAL</text><text class="vr-section" x="236" y="118">OBSERVED FAILURE / INTERRUPTION</text><text class="vr-section" x="612" y="118">RECORDED TERMINAL</text><text class="vr-section" x="902" y="118">LATER OBSERVATION</text>
    <line class="log-axis" x1="214" y1="130" x2="214" y2="650"/><line class="log-axis" x1="590" y1="130" x2="590" y2="650"/><line class="log-axis" x1="880" y1="130" x2="880" y2="650"/>
    <g class="pv-node" tabindex="0" data-detail="模型层：5个llm_call_failed均为cancelled/statusCode=0/attemptCount=1；失败requestId没有后续completed终态。"><rect class="vr-lane-bg" x="40" y="138" width="1118" height="92" rx="8"/><text class="vr-label" x="56" y="168">LLM</text><text class="vr-value" x="56" y="198">5 cancelled</text><rect class="vr-panel-red" x="236" y="154" width="330" height="60" rx="8"/><text class="vr-label" x="252" y="178">5 × llm_call_failed</text><text class="vr-code" x="252" y="199">cancelled · status 0 · attempt 1</text><path class="vr-edge" d="M566 184H606" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-gold" x="612" y="154" width="244" height="60" rx="8"/><text class="vr-label" x="628" y="178">878 = 873 + 5</text><text class="vr-note" x="628" y="199">每个requestId恰有一个终态</text><path class="vr-edge" d="M856 184H896" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-blue" x="902" y="154" width="236" height="60" rx="8"/><text class="vr-label" x="918" y="178">后续出现新requestId</text><text class="vr-note" x="918" y="199">不是同requestId重试成功</text></g>
    <g class="pv-node" tabindex="0" data-detail="工具层：968个完成记录中17个error=true；分布Edit6、WebBrowser5、Agent3、Bash2、CodeIntel1。"><rect class="vr-lane-bg" x="40" y="240" width="1118" height="92" rx="8"/><text class="vr-label" x="56" y="270">TOOLS</text><text class="vr-value" x="56" y="300">17 errors</text><rect class="vr-panel-red" x="236" y="256" width="330" height="60" rx="8"/><text class="vr-label" x="252" y="280">Edit 6 · Browser 5 · Agent 3</text><text class="vr-code" x="252" y="301">Bash 2 · CodeIntel 1</text><path class="vr-edge" d="M566 286H606" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-green" x="612" y="256" width="244" height="60" rx="8"/><text class="vr-label" x="628" y="280">968 completion records</text><text class="vr-note" x="628" y="301">951 false · 17 true</text><path class="vr-edge" d="M856 286H896" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-blue" x="902" y="256" width="236" height="60" rx="8"/><text class="vr-label" x="918" y="280">后续Loop继续调用</text><text class="vr-note" x="918" y="301">不声称17次均自动修复</text></g>
    <g class="pv-node" tabindex="0" data-detail="Worker层：10次启动的实际终止语义为1自然完成、6次deadline回收、3次maxTurns回收；不能写成7次正常结束。"><rect class="vr-lane-bg" x="40" y="342" width="1118" height="92" rx="8"/><text class="vr-label" x="56" y="372">WORKERS</text><text class="vr-value" x="56" y="402">10 runs</text><rect class="vr-panel-gold" x="236" y="358" width="330" height="60" rx="8"/><text class="vr-label" x="252" y="382">6 deadline · 3 maxTurns</text><text class="vr-code" x="252" y="403">9次由运行边界回收</text><path class="vr-edge" d="M566 388H606" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-green" x="612" y="358" width="244" height="60" rx="8"/><text class="vr-label" x="628" y="382">1 natural / 6 / 3</text><text class="vr-note" x="628" y="403">终止原因可按childRun归属</text><path class="vr-edge" d="M856 388H896" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-blue" x="902" y="358" width="236" height="60" rx="8"/><text class="vr-label" x="918" y="382">协调者另起续作</text><text class="vr-note" x="918" y="403">使用已落盘产物继续</text></g>
    <g class="pv-node" tabindex="0" data-detail="MCP层：zhipu-websearch断线60次、成功重连60次，按原始顺序60组完整配对，孤立或未闭合事件0。"><rect class="vr-lane-bg" x="40" y="444" width="1118" height="92" rx="8"/><text class="vr-label" x="56" y="474">MCP</text><text class="vr-value" x="56" y="504">60 losses</text><rect class="vr-panel-red" x="236" y="460" width="330" height="60" rx="8"/><text class="vr-label" x="252" y="484">zhipu-websearch connection lost</text><text class="vr-code" x="252" y="505">60次 · 无重叠pending</text><path class="vr-edge" d="M566 490H606" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-green" x="612" y="460" width="244" height="60" rx="8"/><text class="vr-label" x="628" y="484">60 reconnect pairs</text><text class="vr-note" x="628" y="505">orphan 0 · unclosed 0</text><path class="vr-edge" d="M856 490H896" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-muted" x="902" y="460" width="236" height="60" rx="8"/><text class="vr-label" x="918" y="484">连接机制恢复</text><text class="vr-note" x="918" y="505">不推断对开发效率影响</text></g>
    <g class="pv-node" tabindex="0" data-detail="持久化层：267次Atomic write successful和157次Checkpoint saved。只证明事件发生，本案未观察到Checkpoint恢复。"><rect class="vr-lane-bg" x="40" y="546" width="1118" height="92" rx="8"/><text class="vr-label" x="56" y="576">STATE</text><text class="vr-value" x="56" y="606">267 / 157</text><rect class="vr-panel-blue" x="236" y="562" width="330" height="60" rx="8"/><text class="vr-label" x="252" y="586">267 Atomic write successful</text><text class="vr-code" x="252" y="607">157 Checkpoint saved</text><path class="vr-edge" d="M566 592H606" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-green" x="612" y="562" width="244" height="60" rx="8"/><text class="vr-label" x="628" y="586">保存事件持续存在</text><text class="vr-note" x="628" y="607">中间产物可被后续读取</text><path class="vr-edge" d="M856 592H896" marker-end="url(#log-v03-arrow)"/><rect class="vr-panel-muted" x="902" y="562" width="236" height="60" rx="8"/><text class="vr-label" x="918" y="586">Checkpoint恢复 0证据</text><text class="vr-note" x="918" y="607">不能声称实际恢复发生</text></g>
    <rect class="vr-panel-gold" x="40" y="662" width="1118" height="78" rx="10"/>
    <text class="vr-section" x="60" y="688">AUDIT CONCLUSION</text><text class="vr-label" x="60" y="713">未观察到这些失败点导致会话永久终止；但“后来继续”不是“同一失败被自动修好”，也不证明中间生成内容零丢失。</text>
    <rect class="vr-source-rail" x="40" y="752" width="1118" height="32" rx="8"/><text class="vr-source" x="58" y="773">SOURCE  observability-events + app-session log · verification.json.logs.failureContinuation · causalRecoveryInferred=false</text>
  </svg></div>
  <div class="pv-inspector" aria-live="polite">选择泳道，检查该类故障的发生数、终态和证据边界。</div>
  <div class="pv-proof"><p><b>能证明：</b>故障、错误和运行边界没有被成功叙事抹去；终态可以分类并复算，之后仍有全局模型调用、续作和最终验收活动。</p><p class="cannot"><b>不能证明：</b>时间上的后续活动不自动构成修复因果；不能声称失败请求同ID重试成功、所有错误都自动修复或无中间内容损失。</p></div>
</figure>`;
upsertBefore('LOG-V03', '<h3>3.3 持久化拓扑', logV03);

const logV02 = `
<h3 id="llm-audit-evidence">8.3 878个LLM请求的双向账本与耗时审计 <span class="tag t-log">日志实测</span> <span class="tag t-verified">requestId闭合</span></h3>
<p>验证脚本以 <code>data.requestId</code> 建立开始账和终态账，而不是只数成功响应：878个唯一started requestId全部且仅有一个终态，873个completed、5个failed；缺失、孤立和重复终态均为0。</p>
<figure class="log-viz" data-viz-code="LOG-V02" data-evidence="observability-events">
  <div class="pv-head"><span class="pv-code">LOG-V02</span><strong>878个LLM请求的双向账本</strong><span class="pv-kind">requestId闭合 · nearest-rank P95</span></div>
  <div class="log-viz-stage"><svg class="pv-svg viz-rich" data-viz-rich="true" viewBox="0 0 1200 790" role="img" aria-labelledby="log-v02-title log-v02-desc">
    <title id="log-v02-title">LOG-V02 878个LLM请求终态闭合与873个完成请求耗时分布</title>
    <desc id="log-v02-desc">上半部展示878个started按requestId闭合为873 completed和5 failed，缺失、孤立、重复均为零。下半部展示六档耗时直方图及均值、中位数、P95、最大值。</desc>
    <defs><marker id="log-v02-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10Z" fill="#6bb8c9"/></marker></defs>
    <rect class="vr-frame" x="18" y="18" width="1164" height="754" rx="12"/>
    <text class="vr-title" x="40" y="52">REQUEST LEDGER · started = completed + failed</text><text class="vr-subtitle" x="40" y="76">配对键：observability data.requestId；耗时只统计873个completed事件的durationMs。</text>
    <g class="pv-node" tabindex="0" data-detail="开始账：878条llm_call_started，requestId全部唯一。"><rect class="log-observed" x="60" y="108" width="250" height="118" rx="12"/><text class="vr-section" x="82" y="137">START BOOK</text><text class="vr-value" x="82" y="181">878</text><text class="vr-label" x="154" y="181">llm_call_started</text><text class="vr-note" x="82" y="207">unique requestId 878</text></g>
    <path class="vr-edge-data" d="M310 167H408" marker-end="url(#log-v02-arrow)"/>
    <g class="pv-node" tabindex="0" data-detail="完成账：873条llm_call_completed；这873条的输入/输出token元组可与账单873行逐条匹配。"><rect class="log-ledger-ok" x="424" y="98" width="298" height="96" rx="12"/><text class="vr-section" x="446" y="128">COMPLETED TERMINAL</text><text class="vr-value" x="446" y="166">873</text><text class="vr-note" x="526" y="166">input/output token元组逐条可对账</text></g>
    <g class="pv-node" tabindex="0" data-detail="失败账：5条llm_call_failed，均为cancelled、statusCode=0、attemptCount=1；不声称这些失败请求进入账单。"><rect class="log-ledger-fail" x="424" y="206" width="298" height="96" rx="12"/><text class="vr-section" x="446" y="236">FAILED TERMINAL</text><text class="vr-value" x="446" y="274">5</text><text class="vr-note" x="500" y="260">cancelled · status 0</text><text class="vr-note" x="500" y="280">attemptCount 1</text></g>
    <path class="vr-edge-data" d="M310 167H370V254H408" marker-end="url(#log-v02-arrow)"/>
    <rect class="vr-panel" x="756" y="98" width="382" height="204" rx="12"/>
    <text class="vr-section" x="780" y="128">BIDIRECTIONAL INTEGRITY CHECK</text>
    <g class="pv-node" tabindex="0" data-detail="完整性检查：started 878、terminal 878，missing 0、orphan 0、duplicate 0。"><text class="vr-value" x="780" y="168">878 ↔ 878</text><text class="vr-label" x="780" y="194">unique start IDs ↔ unique terminal IDs</text><text class="vr-label" x="780" y="230">missing</text><text class="vr-value" x="880" y="230">0</text><text class="vr-label" x="944" y="230">orphan</text><text class="vr-value" x="1034" y="230">0</text><text class="vr-label" x="780" y="268">duplicate terminal</text><text class="vr-value" x="944" y="268">0</text></g>
    <text class="vr-section" x="60" y="346">COMPLETED DURATION DISTRIBUTION · milliseconds · n=873</text>
    <line class="log-axis" x1="92" y1="636" x2="766" y2="636"/><line class="log-axis" x1="92" y1="372" x2="92" y2="636"/>
    <line class="log-grid" x1="92" y1="570" x2="766" y2="570"/><line class="log-grid" x1="92" y1="504" x2="766" y2="504"/><line class="log-grid" x1="92" y1="438" x2="766" y2="438"/><line class="log-grid" x1="92" y1="372" x2="766" y2="372"/>
    <text class="vr-tiny" x="54" y="640">0</text><text class="vr-tiny" x="42" y="574">100</text><text class="vr-tiny" x="42" y="508">200</text><text class="vr-tiny" x="42" y="442">300</text><text class="vr-tiny" x="42" y="376">400</text>
    <g class="pv-node" tabindex="0" data-detail="耗时小于5秒：209个完成请求。"><rect class="vr-panel-blue" x="116" y="498" width="78" height="138"/><text class="vr-value" x="137" y="488">209</text><text class="vr-note" x="136" y="660">&lt;5s</text></g>
    <g class="pv-node" tabindex="0" data-detail="耗时5秒至10秒：339个完成请求，是数量最多的一档。"><rect class="vr-panel-cyan" x="224" y="412" width="78" height="224"/><text class="vr-value" x="245" y="402">339</text><text class="vr-note" x="234" y="660">5–10s</text></g>
    <g class="pv-node" tabindex="0" data-detail="耗时10秒至30秒：232个完成请求。"><rect class="vr-panel-blue" x="332" y="483" width="78" height="153"/><text class="vr-value" x="353" y="473">232</text><text class="vr-note" x="336" y="660">10–30s</text></g>
    <g class="pv-node" tabindex="0" data-detail="耗时30秒至60秒：46个完成请求。"><rect class="vr-panel-gold" x="440" y="606" width="78" height="30"/><text class="vr-value" x="461" y="596">46</text><text class="vr-note" x="444" y="660">30–60s</text></g>
    <g class="pv-node" tabindex="0" data-detail="耗时60秒至120秒：27个完成请求。"><rect class="vr-panel-gold" x="548" y="618" width="78" height="18"/><text class="vr-value" x="569" y="608">27</text><text class="vr-note" x="548" y="660">60–120s</text></g>
    <g class="pv-node" tabindex="0" data-detail="耗时大于等于120秒：20个完成请求；最大745910毫秒。"><rect class="vr-panel-red" x="656" y="623" width="78" height="13"/><text class="vr-value" x="677" y="613">20</text><text class="vr-note" x="663" y="660">≥120s</text></g>
    <rect class="vr-panel" x="792" y="350" width="346" height="310" rx="12"/>
    <text class="vr-section" x="816" y="380">RECOMPUTED LATENCY</text>
    <g class="pv-node" tabindex="0" data-detail="873个completed durationMs总和为16,005,982毫秒。"><text class="vr-label" x="816" y="420">总耗时</text><text class="vr-value" x="966" y="420">16,005,982 ms</text></g>
    <g class="pv-node" tabindex="0" data-detail="算术平均为18,334.458毫秒。"><text class="vr-label" x="816" y="466">均值</text><text class="vr-value" x="966" y="466">18,334.458 ms</text></g>
    <g class="pv-node" tabindex="0" data-detail="排序后第437个值即中位数7,652毫秒。"><text class="vr-label" x="816" y="512">中位数</text><text class="vr-value" x="966" y="512">7,652 ms</text></g>
    <g class="pv-node" tabindex="0" data-detail="nearest-rank规则：ceil(873×0.95)=830，第830个排序值为61,546毫秒。"><text class="vr-label" x="816" y="558">P95 nearest-rank</text><text class="vr-value" x="966" y="558">61,546 ms</text></g>
    <g class="pv-node" tabindex="0" data-detail="最大completed durationMs为745,910毫秒。"><text class="vr-label" x="816" y="604">最大值</text><text class="vr-value" x="966" y="604">745,910 ms</text></g>
    <text class="vr-boundary" x="816" y="640">延迟不与其他平台横比；Token多不等于质量高</text>
    <rect class="vr-source-rail" x="40" y="696" width="1118" height="56" rx="8"/>
    <text class="vr-section" x="58" y="719">FROZEN SOURCE</text><text class="vr-source" x="188" y="719">logs/observability-events-20260809-0130-0701.jsonl · verification.json.logs.llmAudit</text>
    <text class="vr-boundary" x="58" y="741">BOUNDARY  5次failed不进入873条completed账单匹配主张；新requestId接续不是同requestId重试。</text>
  </svg></div>
  <div class="pv-inspector" aria-live="polite">选择账本节点、柱形或统计量，查看复算口径和边界。</div>
  <div class="pv-proof"><p><b>能证明：</b>878个started requestId各自有且只有一个终态；873个完成请求耗时可由公开事件逐条复算。</p><p class="cannot"><b>不能证明：</b>Token或耗时越大质量越高；5次failed进入提供方账单；本案延迟优于其他平台；失败请求以同一ID重试成功。</p></div>
</figure>`;
replaceRange('LOG-V02', '<h3>8.3 LLM 调用延迟', '<h3>8.4 工具调用分布', logV02);

const logV04 = `
<h3 id="outside-recovery-evidence">A.3e 窗口外413部分恢复：压缩完成≠Run恢复成功 <span class="tag t-log">窗口外日志</span> <span class="tag t-limit">不进入开发统计</span></h3>
<p>以下材料冻结自持续增长的 <code>log/app.log</code>，时间为<b>10:22:47.539—10:24:19.141</b>，晚于07:01开发窗口。它只用于解释日志如何区分“恢复步骤完成”和“整个Run恢复成功”，绝不并入游戏开发的878次调用、968次工具或首末请求锚点统计。</p>
<figure class="log-viz" data-viz-code="LOG-V04" data-evidence="outside-window-app-log">
  <div class="pv-head"><span class="pv-code">LOG-V04</span><strong>窗口外413部分恢复</strong><span class="pv-kind">OUTSIDE WINDOW · 90行 · SHA-256冻结</span></div>
  <div class="log-viz-stage"><svg class="pv-svg viz-rich" data-viz-rich="true" viewBox="0 0 1200 820" role="img" aria-labelledby="log-v04-title log-v04-desc">
    <title id="log-v04-title">LOG-V04 07:01之后的413上下文压缩与Run最终失败</title>
    <desc id="log-v04-desc">时间线从130592 tokens开始，经413 prompt_too_long、摘要超时、候选拒绝、尾部截断、compact_complete、上下文降至70375，再到400 tool_call_id错误和stopReason error。底部明确上下文缩减成功但Run没有恢复成功。</desc>
    <defs><marker id="log-v04-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10Z" fill="#d3b36a"/></marker></defs>
    <rect class="vr-frame" x="18" y="18" width="1164" height="784" rx="12"/>
    <rect class="vr-panel-red" x="40" y="36" width="1118" height="58" rx="10"/><text class="vr-section" x="60" y="61">STRICT SEPARATION</text><text class="vr-label" x="224" y="63">10:22:47—10:24:19 · 晚于07:01 · developmentStatisticsIncluded=false</text><text class="vr-note" x="60" y="84">本图是恢复语义附录，不是王者荣耀开发窗口内触发过重型恢复的证据。</text>
    <line class="vr-edge-event" x1="90" y1="178" x2="1118" y2="178" marker-end="url(#log-v04-arrow)"/>
    <g class="pv-node" tabindex="0" data-detail="10:22:47.539 Turn34开始；10:22:47.550 ContextCascade估算tokensBefore=130592、tokensAfter=130592。"><circle class="vr-panel-blue" cx="110" cy="178" r="22"/><text class="vr-section" x="70" y="130">10:22:47.539</text><rect class="vr-panel-blue" x="48" y="212" width="164" height="98" rx="8"/><text class="vr-label" x="62" y="238">Turn 34</text><text class="vr-value" x="62" y="266">130,592</text><text class="vr-note" x="62" y="289">tokens unchanged</text></g>
    <g class="pv-node" tabindex="0" data-detail="10:22:47.572日志原文：触发反应式压缩 (413 prompt_too_long)。"><circle class="vr-panel-red" cx="250" cy="178" r="22"/><text class="vr-section" x="218" y="130">47.572</text><rect class="vr-panel-red" x="226" y="212" width="180" height="98" rx="8"/><text class="vr-label" x="242" y="238">HTTP 413</text><text class="vr-code" x="242" y="263">prompt_too_long</text><text class="vr-note" x="242" y="287">触发反应式压缩</text></g>
    <g class="pv-node" tabindex="0" data-detail="CompactService使用qwen3.7-plus尝试对322条消息生成4096 token摘要；约90秒后chatSync IO timeout。"><circle class="vr-panel-gold" cx="410" cy="178" r="22"/><text class="vr-section" x="374" y="130">47.577</text><rect class="vr-panel-gold" x="420" y="212" width="190" height="98" rx="8"/><text class="vr-label" x="436" y="238">LLM摘要开始</text><text class="vr-code" x="436" y="263">qwen3.7-plus</text><text class="vr-note" x="436" y="287">322 messages → target 4096</text></g>
    <g class="pv-node" tabindex="0" data-detail="10:24:17.581摘要失败；候选130592→130604没有减少token，被拒绝。"><circle class="vr-panel-red" cx="590" cy="178" r="22"/><text class="vr-section" x="548" y="130">10:24:17.581</text><rect class="vr-panel-red" x="624" y="212" width="190" height="98" rx="8"/><text class="vr-label" x="640" y="238">摘要超时</text><text class="vr-code" x="640" y="263">130,592 → 130,604</text><text class="vr-note" x="640" y="287">候选拒绝：没有净减少</text></g>
    <g class="pv-node" tabindex="0" data-detail="10:24:17.609降级策略为尾部截断：保留最近消息，丢弃最早消息。"><circle class="vr-panel-gold" cx="750" cy="178" r="22"/><text class="vr-section" x="720" y="130">17.609</text><rect class="vr-panel-gold" x="828" y="212" width="142" height="98" rx="8"/><text class="vr-label" x="842" y="238">降级</text><text class="vr-value" x="842" y="266">尾部截断</text><text class="vr-note" x="842" y="289">保留最近消息</text></g>
    <g class="pv-node" tabindex="0" data-detail="10:24:17.610出现push(compact_complete)和CompactMetrics Recovery success，compressionRatio=0.4611078779，latencyMs=90037。"><circle class="vr-panel-green" cx="890" cy="178" r="22"/><text class="vr-section" x="858" y="130">17.610</text><rect class="vr-panel-green" x="984" y="212" width="154" height="98" rx="8"/><text class="vr-label" x="998" y="238">compact_complete</text><text class="vr-code" x="998" y="263">ratio 0.4611</text><text class="vr-note" x="998" y="287">latency 90,037ms</text></g>
    <text class="vr-section" x="48" y="362">NEXT TURN · THE DISTINCTION THAT MATTERS</text>
    <g class="pv-node" tabindex="0" data-detail="Turn35在10:24:17.614重新评估时tokensBefore=70375，说明上下文缩减步骤确实完成。"><rect class="vr-panel-green" x="48" y="384" width="306" height="130" rx="10"/><text class="vr-label" x="68" y="414">STEP RESULT · CONTEXT REDUCED</text><text class="vr-value" x="68" y="454">130,592 → 70,375</text><text class="vr-note" x="68" y="480">Turn35 context_cascade_evaluation</text><text class="vr-code" x="68" y="500">compact_complete + "Recovery success"</text></g>
    <path class="vr-edge-feedback" d="M354 448H438" marker-end="url(#log-v04-arrow)"/>
    <g class="pv-node" tabindex="0" data-detail="10:24:19.124下一次模型HTTP响应400，错误为Invalid request: tool_call_id is not found。"><rect class="vr-panel-red" x="454" y="384" width="306" height="130" rx="10"/><text class="vr-label" x="474" y="414">NEXT MODEL REQUEST · HTTP 400</text><text class="vr-value" x="474" y="454">tool_call_id</text><text class="vr-note" x="474" y="480">Invalid request: … is not found</text><text class="vr-code" x="474" y="500">10:24:19.124</text></g>
    <path class="vr-edge-feedback" d="M760 448H844" marker-end="url(#log-v04-arrow)"/>
    <g class="pv-node" tabindex="0" data-detail="10:24:19.141 QueryEngine完成，stopReason=error；因此runRecovered=false。"><rect class="vr-panel-red" x="860" y="384" width="278" height="130" rx="10"/><text class="vr-label" x="880" y="414">RUN TERMINAL</text><text class="vr-value" x="880" y="454">stopReason=error</text><text class="vr-note" x="880" y="480">authoritative session recovery required</text><text class="vr-code" x="880" y="500">runRecovered = false</text></g>
    <rect class="vr-panel-green" x="48" y="548" width="530" height="92" rx="10"/><text class="vr-section" x="68" y="576">TRUE</text><text class="vr-label" x="136" y="576">上下文缩减动作完成</text><text class="vr-value" x="68" y="612">−60,217 tokens estimate</text><text class="vr-note" x="342" y="612">130,592 → 70,375</text>
    <rect class="vr-panel-red" x="608" y="548" width="530" height="92" rx="10"/><text class="vr-section" x="628" y="576">FALSE</text><text class="vr-label" x="706" y="576">整个Run恢复成功</text><text class="vr-value" x="628" y="612">HTTP 400 → stopReason=error</text>
    <rect class="vr-panel-gold" x="48" y="660" width="1090" height="62" rx="10"/><text class="vr-label" x="68" y="688">日志里的“Recovery success”是CompactMetrics对压缩步骤的命名；报告必须结合下一请求和Run终态，不能把它改写成业务恢复成功。</text><text class="vr-boundary" x="68" y="708">没有使用源日志不存在的字面事件名 llm_recovery_attempted / context_compacted。</text>
    <rect class="vr-source-rail" x="48" y="738" width="1090" height="44" rx="8"/><text class="vr-source" x="64" y="758">SOURCE  logs/app-post-window-413-20260809-102247-102419.log · 90 lines · SHA-256 41b53b3c3dde…</text><text class="vr-boundary" x="64" y="775">BOUNDARY  outside 01:30≤time&lt;07:01; excluded from every development-window aggregate.</text>
  </svg></div>
  <div class="pv-inspector" aria-live="polite">选择时间点，查看日志原文对应的步骤、终态和严格边界。</div>
  <div class="pv-proof"><p><b>能证明：</b>平台日志能区分413触发、摘要超时、尾部截断、上下文缩减、下一请求失败和Run最终error。</p><p class="cannot"><b>不能证明：</b>该重型恢复发生在王者荣耀开发窗口；压缩步骤完成不等于整个Run恢复；单个窗口外案例不代表平台普遍恢复率。</p></div>
</figure>`;
// LOG-V04 was an unrelated post-window recovery example. It is intentionally
// excluded from the public king case report and must not be reinserted by this
// legacy enhancer.

html = html.replace("document.querySelectorAll('.product-viz').forEach(fig=>{", "document.querySelectorAll('.product-viz,.log-viz').forEach(fig=>{");
const v9SizePattern = /(<tr><td>v9（本版）<\/td><td>)(?:FINAL_SIZE|[\d,]+ B)(<\/td>)/;
if (!v9SizePattern.test(html)) throw new Error('v9 size row not found');
html = html.replace(v9SizePattern, (_match, prefix, suffix) => `${prefix}0 B${suffix}`);
for (let attempt = 0; attempt < 8; attempt += 1) {
  const size = `${Buffer.byteLength(html).toLocaleString('en-US')} B`;
  const current = html.match(v9SizePattern)?.[0].match(/<td>([\d,]+ B)<\/td>$/)?.[1];
  if (current === size) break;
  html = html.replace(v9SizePattern, `$1${size}$2`);
}
writeFileSync(reportPath, html);
console.log(`Enhanced log visualizations in ${reportPath}`);
