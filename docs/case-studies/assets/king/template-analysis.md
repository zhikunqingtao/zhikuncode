# 模板解剖报告：AI 编程工具开发过程实证案例单文件网页

> 分析对象：
> - **A. 吃鸡.html**（990 KB，2,005 行）——`ZhikunCode：从一句话到可玩大逃杀原型（不含视频照片）`，2026-08-04 任务、2026-08-05 生成的增强版。**重点参考对象**。
> - **B. 淘宝.html**（3.5 MB，1,665 行）——`ZhikunCode · 复刻淘宝（内嵌图片版）— 7小时26分完成高保真淘宝买家端复刻`，2026-08-06 任务。
>
> 两份都是"AI 编程工具（ZhikunCode）+ Kimi K3 模型完成一次真实工程委托"的全过程实证单文件网页。共同特征：**单文件、零外链、CSP 自闭合、深色主题、内联 SVG 图表、证据分级标签体系、可展开的审计底稿**。本文按 7 个维度解剖，供"王者荣耀开发实证"新案例直接复用。

---

## 1. 整体结构

### 1.1 吃鸡.html：双层章节体系（narrative-part → section.chapter）

页面是严格的"九部曲 + 模块小节"两级结构。DOM 骨架：

```
<body>
  <a class="skip">                     跳转正文（无障碍）
  <div class="progress" id="progress"> 顶部阅读进度条
  <aside class="rail">                 左侧固定导航栏（brand + nav + rail-actions）
  <main class="main">
    <header class="section hero" id="top">   Hero 区（总标题+总数字+主图）
    <section class="narrative-part" id="part-0">  ┐
      <header class="part-intro"> … </header>     │ 每个 part = 一章
      <section class="section chapter" …> ×N      │ 每个 chapter = 一组材料（X.Y 编号）
      <footer class="part-transition"> … </footer>│ 章末过渡+下一章链接
    </section>                                    ┘ ×9（序 + 01–08）
    <footer>                               页面总页脚
  </main>
  <script> … </script>                   数据记录 + 全部交互
</body>
```

**九个 narrative-part（左侧导航的原样文案）：**

| id | 导航编号 | 标题原文 | 作用 |
|---|---|---|---|
| part-0 | 序 | 这次交付了什么 | 交付锚点（15:42 需求→21:30 交付→21:31 启动）、时间与版本边界、本版交付清单、关键指标工程含义（review-brief）、工程能力概览、难点矩阵、三条阅读路径 |
| part-1 | 01 | 先把一句话问清楚 | 需求治理：从一句话到 18 条验收项；两轮确认与授权原账（Q1–Q7 原账 + 权限决策表）；G1–G18 验收合同矩阵；本版交付与扩展路线 |
| part-2 | 02 | 23 个模块组成的实时游戏 | 产物解剖：复杂系统全景（VIZ-P01 等 18 张下钻图 P01–P18）、23 模块工程架构、合同到源码符号追踪、游戏系统配置、素材供应链与离线运行 |
| part-3 | 03 | ZhikunCode 的工程运行时 | 平台机制：五方协作剖面、三端架构、QueryEngine 八步循环、工具九阶段管道、权限复检、上下文地层、持久化拓扑、浏览器反馈闭环（VIZ-Z 系列） |
| part-4 | 04 | 十个子任务汇入同一个项目 | 执行过程：平台能力→阶段映射、Coordinator 与 10 个子 Agent 泳道甘特、受控多 Agent 运行时、逐 Run 账本（R01–R10）、M0–M3b 产物谱系、文件交接与增量演进 |
| part-5 | 05 | 复杂环境下的持续交付 | 运行韧性：状态控制与持久化、故障隔离与恢复闭环（recovery-card 四类故障）、可观测性与因果边界（460 请求 / 551 工具） |
| part-6 | 06 | 游戏的启动、操控与自动验证 | 验证体系：验证总体架构与启动门禁、动作/钩子/用例/判定（12 类动作 DSL、6 个页面钩子、判定语义）、11 张验收截图、六段过程录屏 |
| part-7 | 07 | 把成本、源码和证据摊开 | 工程审计：Token 与费用复算（¥152.153744）、缓存经济性专题（cache-explainer）、证据账本与血缘（可搜索）、首版源码完整清单（75 文件全哈希）、统计方法与版本边界 |
| part-8 | 08 | 结论：这次任务说明了什么 | 六项能力与直接证据映射、产品化扩展路线、promo-closing 收口 |

每个 `part-intro`（章首）的固定构件：

```html
<header class="part-intro" id="part-1-intro">
  <div class="part-index" aria-hidden="true">01</div>          <!-- 巨型背景序号 -->
  <div class="part-intro-main">
    <div class="part-kicker">工程案例 01 · 4 组材料</div>       <!-- 等宽字 kicker -->
    <h2 id="part-1-title">先把一句话问清楚</h2>
    <p class="part-story">正式写代码前，ZhikunCode 连续发起两轮确认。…</p>
  </div>
  <div class="part-proof-grid" aria-label="关键数字">           <!-- 3 个大数字卡 -->
    <div class="part-proof"><strong>2 轮</strong><span>AskUserQuestion 需求确认</span></div>
    <div class="part-proof"><strong>7 项</strong><span>保留原值的用户选择</span></div>
    <div class="part-proof"><strong>G1–G18</strong><span>带证明层级与验证计划的验收合同</span></div>
  </div>
  <details class="part-limit"><summary>数据说明</summary><p>…</p></details>   <!-- 折叠的口径声明 -->
  <div class="part-nav-row">
    <nav class="part-module-nav">…X.1/X.2/X.3 模块锚链接…</nav>
    <a class="part-lead-link" href="#part-1-lead-viz">查看总览图 →</a>
  </div>
  <nav class="part-deep-nav"><b>继续下钻</b>…</nav>            <!-- 部分章有的二级下钻导航 -->
</header>
```

每个 `section.chapter`（材料组）带一组数据属性构成寻址体系：

```html
<section class="section chapter" id="contract" data-chapter="1.3" data-part="part-1"
         data-module="1.3" data-nav-parent="part-1"
         data-cluster="先把一句话问清楚 / G1–G18 验收合同">
```

**导航/目录设计**（3 层 + 1 模式开关）：
1. **左侧 rail 导航**：`.brand`（菱形 Z 标 + ZHIKUNCODE + 日期）→ `nav a[data-nav]`（序号 span + 标题，9 条）→ `.rail-actions`（`#auditMode`"完整证据模式"切换 + "项目导览"主按钮）。IntersectionObserver 滚动监听自动高亮当前章。
2. **章内模块导航** `.part-module-nav`（X.1 X.2 … 锚点）+ 下钻导航 `.part-deep-nav`。
3. **序章的三条阅读路径** `.reading-routes`："90 秒看结果 / 5 分钟看全流程 / 展开全部证据"。
4. **章末过渡** `footer.part-transition`：一句话承上启下 + 下一章箭头链接。
5. **读者角色入口** `.reader-paths`（项目决策者 / Agent 平台工程师 / 游戏开发者）。
6. `part-6` 内部还有吸顶子导航 `.validation-subnav`、`.product-subnav`（part-2）。

### 1.2 淘宝.html：单级章节体系（section.part + chapter-band）

结构更扁：17 个 `section.part`，没有二级 chapter。章首用 **chapter-band**（大号描边序号 + kicker + 主题句 + 3 个统计卡 + 底纹 pattern-decision/data/product/runs/verify/audit）：

```html
<section class="part chaptered" id="part-1">
  <div class="chapter-band pattern-decision" aria-label="定义任务：01 需求怎么定下来：6条指令、8项选择">
    <div class="chapter-index" aria-hidden="true">01</div>
    <div class="chapter-copy">
      <span class="chapter-kicker">定义任务</span>
      <h2><span class="no">01</span> 需求怎么定下来：6条指令、8项选择</h2>
      <p class="chapter-theme">一句话之后，先把问题定义清楚</p>
      <p class="chapter-lead">用户第一句话就定下了合作方式：拿不准先问…</p>
      <p class="chapter-source-copy">…（仅审计模式显示）</p>
    </div>
    <div class="chapter-stats">
      <div class="chapter-stat"><b>6</b><span>用户消息</span></div>
      <div class="chapter-stat"><b>8</b><span>关键选择</span></div>
      <div class="chapter-stat"><b>4</b><span>抓取授权</span></div>
    </div>
  </div>
  …viz-cluster / h3 小节 / 表格 / details…
</section>
```

**17 个 section.part（含导航分组）：**

| id | 标题原文 | 作用 |
|---|---|---|
| hero（header） | 7 小时 26 分，完成高保真淘宝买家端复刻 | Hero：lead 总述 + 六截图产品舞台（hero-product-stage 错位拼贴）+ hero-route-line（一句话→真实数据→六路由→交易闭环→自动验证）+ 8 个 metric |
| part-0 | 序 这次交付了什么 | 产物总览（六页面交易链 SVG）、五层组成、21:24 最后一次检查表、真实数据凭证（SQLite 样本 pre）、统一时间线全景 |
| part-1 | 01 需求怎么定下来：6条指令、8项选择 | 需求展开图、八项选择表（D1–D8，含 3 次推翻推荐）、六条消息时间线、用户原话 blockquote ×4 |
| part-cx | ◆ 这件事为什么难 | 五件难事泳道图、五区域像素差异对照图（真实/复刻/diff 三联）、复杂度实测数据 |
| part-2 | 02 四批数据是怎么拿到的 | 采集工程：四批次总账、登录态攻防时序、防封号决策流、数据溯源图、mtop 接口清单 |
| part-3 | 03 数据怎样从网页进入SQLite | 三种解析办法、主图来源收敛、数据库/API/金额关系、14 接口表、数据流泳道、四段关键代码 |
| part-4 | 04 六个页面怎样搭起来 | 组件归属表、页面跳转与共享状态、SKU 状态机、端口固定 5200 |
| part-5 | 05 20个Worker怎样分工 | Worker 任务/结果总表、六个典型 Worker、任务切片、三次服务恢复接续 |
| part-arch | ★ ZhikunCode怎样把任务跑完 | 平台机制：五方归因、QueryEngine、工具落地、checkpoint/WIP 交接、Worker 任务包、经验传递、14 次命令拦截、协调者-Worker 架构、Worker 泳道甘特、事故恢复流 |
| part-6 | 06 怎样启动、操作并检查网站 | 像素 diff 方法学、五区域收敛、两条浏览器操作路径、294 条路径检查 |
| part-gate | § G1–G12分别检查了什么 | 验收矩阵表 + 每项原始证据底稿（G1–G12 逐条：数字、断言行、库行） |
| part-7 | 07 12次工程修正及结果 | 12 次修正总账 SVG + 四个关键修正诊断故事板 + incident 卡（现象/根因/修复/教训）+ 原始诊断 pre |
| part-8 | 08 Token、缓存与运行开销 | Token 构成、缓存命中率机制、会话时间轴、20 Worker Token 表、逐小时分布、缓存证据链、工具分布、并行节省 |
| part-ledger | ▤ 代码、提交与里程碑 | 代码目录分布、37 次提交类型、M0–M4 里程碑 |
| part-9 | 09 交付了哪些文件 | 交付面/排除项、9.1 代码与参考 CSS 分账表、9.2 完整提交记录 pre、9.3 如何运行 pre |
| part-10 | 10 截图、录屏与运行材料 | 内嵌图片资源清单（35 载荷/38 路径）、材料索引图、六段录屏时间表、截图画廊（10.1/10.4/10.5/10.6）、素材包报告清单 |
| part-end | 结 回头看，这次做成了什么 | 五列映射（难点→动作→产物→检查→结果）、现场发现沉淀为工程方法、四项能力 audit-bundle、最终结论 callout |
| part-11 | 附 速查附录 | 关键路径 pre、交付范围与运行边界图、已知限制原文（D. ①–⑩） |

**导航**：左侧 `#rail`（二级：nav-main 章 + nav-sub 节）+ `#evidence-toggle`"展开审计底稿"按钮 + `.rail-note`（标签口径图例 + 素材包说明）。

### 1.3 结构差异一句话

- 吃鸡 = **"9 章 × N 材料组"两级目录**，章首 part-intro（问题→答案→关键数字→口径→模块导航），章末 part-transition 串场；信息架构更严谨，像"证据卷宗"。
- 淘宝 = **17 个平级 section + chapter-band 章首横幅**，正文用 `h3` 小节号（1.1/2.3/9.2）组织；阅读节奏更线性，像"图文专题报道"。

---

## 2. 视觉与技术实现

### 2.1 通用技术底座（两者共有）

- **全部 CSS 内联**：吃鸡 11 个 `<style>` 块（基础样式 + 9 个带 id 的分层补丁：`platform-runtime-styles`、`svg-audit-polish`、`narrative-architecture-styles`、`project-review-tone`、`promotion-focus-styles`、`human-editorial-layer`、`version-emphasis-layer`、`cache-economics-style`、`cost-svg-corrections`、`no-media-edition-styles`——后写覆盖先写，形成"版本地层"）；淘宝 1 个大 `<style>`（内部同样有"consolidated desktop design system"二次 `:root` 覆盖）。
- **零外链、CSP 自闭合**：
  - 吃鸡：`<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'none'; media-src 'none'; connect-src 'none'; …">`（**完全无媒体版**）
  - 淘宝：`img-src data:`（允许 base64 图片）、`media-src 'none'`（视频不内嵌）
- **暗色主题 + CSS 变量**、响应式 `@media(max-width)`、打印样式 `@media print`（转浅色、隐藏交互件、强制展开 details）、`prefers-reduced-motion` 降级、`.skip` 无障碍跳链。
- **无第三方库**：图表全部是**手写内联 SVG**（吃鸡 104 个 `<svg>`、淘宝 69 个），不依赖 ECharts/D3。

### 2.2 主题色与字体

**吃鸡.html（"黄铜+墨绿"军事沙盘风）**：

```css
:root{--bg:#080a09;--bg-deep:#050706;--surface:#101411;--surface-2:#151916;--surface-3:#1a201c;
--line:rgba(197,196,187,.15);--line-strong:rgba(197,196,187,.27);
--text:#ebe7dc;--text-soft:#c5c4bb;--muted:#8d958f;--faint:#828a85;
--yellow:#c7a45a;--green:#78a78e;--cyan:#7d9aa0;--orange:#c68a6d;--red:#c4767a;
--brass:#c7a45a;--font-display:-apple-system,…,"PingFang SC",…;--font-mono:"SFMono-Regular",Menlo,Consolas,monospace}
```

**淘宝.html（"淘宝橙"品牌风）**：

```css
:root{--bg:#070a08;--s1:#0c110e;--s2:#121814;--tx:#e8ece7;--txw:#fbfcfa;
--tb:#ff6432;--tb2:#ff9a62;--brass:#d8ae67;--grn:#75ba91;--cyan:#76aeb4;--red:#d87c80;
--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;--display:"SF Pro Display","PingFang SC",…}
```

→ 新案例（王者荣耀）建议换一套品牌色（如王者蓝/金），变量机制照搬。

### 2.3 关键组件类名与 CSS 片段（吃鸡为主）

| 组件 | 类名 | 关键样式原文（摘录） |
|---|---|---|
| 侧边导航 | `.rail` / `.rail nav a` / `.rail nav a.active` | `.rail{position:fixed;inset:0 auto 0 0;width:var(--rail);background:#0b0e0ceb;border-right:1px solid var(--line);backdrop-filter:blur(18px)}` |
| 章节容器 | `.section` / `.section:before` | `.section:not(.hero):before{content:attr(data-chapter);position:absolute;right:max(46px,5vw);top:78px;color:rgba(197,164,93,.18);font:500 52px/1 ui-monospace}` —— 右上角的半透明章节大字 |
| 卡片 | `.card` | `.card{position:relative;padding:26px;border:1px solid var(--line);border-radius:11px;background:var(--surface)}` |
| 指标卡 | `.metrics` / `.metric` | `.metric strong{display:block;font:800 clamp(27px,3.4vw,48px)/1 ui-monospace,monospace;color:var(--yellow);letter-spacing:-.05em}` |
| 证据标签 | `.tag` + `.verified/.observed/.raw/.derived/.limit/.spec` | `.verified,.observed{color:var(--green);border-color:#94b6a366;background:#94b6a311}` 等 6 色语义 |
| 图卡 | `.viz-frame` / `.viz-head` / `.viz-code` / `.viz-kind` / `.viz-stage` / `.viz-proofline` | 分级：`.viz-level-hero`（核心大图）、`.viz-level-analysis`（分析图）、`.viz-level-audit`（审计小图）；域着色：`--viz-accent` + `.viz-domain-requirements{--viz-accent:#c7a45a}` 等 9 域 |
| 表格 | `.table-wrap` + `.audit-table` / `.scope-table` / `.manifest-table` / `.validation-table` / `.profile-table` | `.audit-table th{position:sticky;top:0;background:#1a201c;color:#9ab2b6;font-size:11px}`；首列 sticky：`.table-wrap .audit-table td:first-child{position:sticky;left:0}` |
| 时间轴 | `.timeline-wrap`（横向滚动 SVG 容器）、`.obs-lane`（泳道条）、`.code-row`（代码量条） | `.timeline-wrap svg{min-width:880px}` |
| 代码块 | `.validation-source pre` / `.hash` / `code` | `.validation-source pre{max-height:520px;overflow:auto;padding:16px;background:#080a09;border:1px solid var(--line);font:12px/1.65 var(--mono);white-space:pre}` |
| 截图卡 | `.evidence-shot` + `.shot-open` + `figcaption` + `.lightbox` | `.shot-open img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover}`；`.lightbox{position:fixed;inset:0;z-index:100;background:#050706f5;display:none;place-items:center}.lightbox.open{display:grid}` |
| 视频区 | `.video-layout` / `.clip-tab` / `.video-stage` / `.video-meta` / `.film-strip` / `.film-frame` | `.clip-tab.active{border-color:var(--yellow);background:#c7a45a0b}` |
| 证据账本 | `.ledger-row`（details）/ `.ledger-toolbar` / `.ledger-detail dl` | `.ledger-row summary{display:grid;grid-template-columns:38px 1fr auto minmax(210px,1fr);gap:12px;padding:16px;cursor:pointer}` |
| 折叠审计块 | `.audit-compact`（details）/ `.audit-extra` | `.audit-compact>summary:before{content:"＋"}`；`body:not(.audit-mode) .audit-extra{display:none!important}`（普通模式隐藏底稿） |
| 恢复卡 | `.recovery-grid` / `.recovery-card`（dl: 症状/恢复动作/新证据/适用范围） | `.recovery-card{border-top:3px solid var(--orange)}` |
| 决策原账 | `.raw-ledger-grid` / `.raw-decision`（details）/ `.selected` / `.option-list` | `.raw-decision .selected{border-left:3px solid var(--yellow);background:#c7a45a0d}` |
| 边界横幅 | `.boundary-banner` / `.warning-card` / `.source-note` | `.boundary-banner{border:1px solid #c68a6d77;border-left:5px solid var(--orange);padding:20px 22px}` |
| 章首 | `.part-intro` / `.part-index` / `.part-kicker` / `.part-story` / `.part-proof-grid` / `.part-limit` | `.part-index{position:absolute;right:24px;top:-24px;font:800 112px/1 var(--font-mono);color:rgba(199,164,90,.065)}` |
| 章末过渡 | `.part-transition` | `display:flex;justify-content:space-between;border-top:1px solid rgba(199,164,90,.3)` |
| 无媒体占位 | `.media-removed` / `.media-removed-svg`（本版特有） | `.media-removed{display:flex;flex-direction:column;align-items:center;min-height:190px;border:1px dashed rgba(213,181,109,.38);border-radius:14px;color:#d7c488}` |

**淘宝.html 独有的组件类**：`.chapter-band/.chapter-index/.chapter-stats/.chapter-stat`（章首横幅）、`.viz-cluster/.viz-card(.viz-hero/.viz-core)/.viz-title/.viz-scroll/.viz-svg/.cap/.viz-proof`（图卡体系）、`.timeline/.tl-item(.warn/.good)`（竖向时间线）、`.incident(.fix)`（事故卡）、`.callout(.green/.red)`（提示框）、`.compare`（对照画廊）、`.pill(.ok/.hot/.bad)`、`.audit-bundle`（大底稿 details）、`dialog#lb`（图片放大）、`.hero-product-stage/.hero-shot`（Hero 截图拼贴）、`.embedded-manifest`（内嵌资源清单）。

### 2.4 JS 交互（全部内联、无依赖）

**吃鸡.html（1 个大 `<script>`）**：
1. 顶部进度条：`addEventListener('scroll', …qs('#progress').style.width=…)`。
2. `[data-jump]` 按钮平滑滚动。
3. IntersectionObserver ×3：章节导航高亮（`data-nav-parent`）、`.reveal` 入场动画、part-intro 观察。
4. SVG 交互节点：`.interactive-node` 点击/Enter/空格 → 把 `data-detail` 文本写入同卡 `.focus-detail` 或 `.viz-inspector`（`aria-live="polite"`）；hover/focus 加 `.is-active` 高亮并压暗其他节点（`.viz-node-active`）。
5. **审计模式开关**：`#auditMode` 按钮 toggle `body.audit-mode` —— 控制 `.audit-extra`、`.viz-proofline`、`figure.evidence-shot .limit`、`viz-code` 编号显示、VIZ-26/30 等审计图的显隐（"普通阅读只展示结论与图形；工程底稿模式恢复全部来源、口径和制图说明"）。
6. 证据账本：`#expandAll` 展开/收起全部 `.ledger-row`；`#evidenceSearch` 输入过滤 `[data-search]`；`#permToolFilter` 下拉筛选权限表行。
7. **数据层内嵌**：`window.ReportNarrative`（NarrativePartRecord/EvidenceModuleRecord/ReadingRouteRecord/TransitionRecord）、`window.__REPORT_DATA__`（EvidenceRecord、PhaseRecord、MediaRecord、AcceptanceRecord、RunRecord、InteractionRecord、PermissionRecord、RunProfileRecord、FileEvolutionRecord、SymbolTraceRecord、SourceSnapshotRecord、PlatformMechanismRecord 等 20+ 组冻结 JSON）、`window.__REPORT_VISUALIZATION_DATA__`（每张图的 grammar/claimIds/sourceLocators/limitations）。即"报告数据全部结构化沉淀在 JS 常量里，可机器读取"。

**淘宝.html（3 个 `<script>`）**：
1. **内嵌资源注册表注入**：`<script id="embedded-asset-registry" type="application/json">`（2.9 MB 单行 JSON：`{"payloads":{"a01":{"m":"image/webp","d":"UklGR…"}},"aliases":{…}}`）→ 启动脚本解析后给所有 `[data-asset]` 元素赋 `src`/`href`（img/image/a 三种标签分别处理）。**这是"内嵌图片版"的核心机制**。
2. 进度条 + scroll spy + `.reveal` 入场（.card/.metric/.incident/figure/.chart）。
3. **图片灯箱**：`<dialog id="lb">`，点击 figure 内 img `showModal()` 放大。
4. `#evidence-toggle`：toggle `body.evidence-mode` —— 显示 `.viz-audit`/`.viz-proof`/`.chapter-source-copy`，并展开全部 `details.audit-bundle`。

### 2.5 媒体策略（两版三种形态）

| 形态 | 机制 | 实例 |
|---|---|---|
| 吃鸡.html（无媒体版） | CSP `img-src 'none'`；所有截图/视频位置放 `.media-removed` 虚线占位块（"照片未包含" + 小字说明 + figcaption 保留编号/标题/边界/SHA-256）；SVG 内嵌位置用 `.media-removed-svg` 占位组 | `<div id="shot-0" class="media-removed media-removed-photo" role="note" aria-label="照片未包含在此版本中"><span>照片未包含</span><small>M1 · 海岛鸟瞰：…</small></div>` |
| 淘宝.html（内嵌图片版） | CSP `img-src data:`；图片压缩为 WebP Q86/Q92（diff 图用无损 WebP）→ base64 进 `embedded-asset-registry` JSON → JS 注入 `[data-asset]`；视频不内嵌（`.standalone-video-note` 说明"由用户单独上传"） | `<img data-asset="taobao复刻素材/site-home-hero.png" alt="复刻站首页运行截图"/>` |
| 外链相对路径版（推测的第三形态） | 吃鸡 hero 的 SVG 有 `image[data-media-ref]` 注入逻辑（`document.querySelectorAll('svg.v2-visual image[data-media-ref]')`），说明完整版存在过 `<img id="shot-N" src="…">` + SVG `<image data-media-ref="shot-N">` 的引用形态 | — |

---

## 3. 实证内容组织方式（逐类举证）

### 3.1 开发时间线

两种形态：**SVG 甘特/泳道**（吃鸡 VIZ-14、淘宝"统一时间线全景"）和**表格时间线**（吃鸡 R01–R10 表、淘宝四批次表）。吃鸡 VIZ-14 片段：

```html
<div class="card timeline-wrap" style="margin-top:18px"><figure class="viz-frame viz-level-hero viz-domain-agents" data-viz-code="VIZ-14">
<figcaption class="viz-head"><span class="viz-code">VIZ-14</span><strong>Coordinator 与子 Agent 泳道甘特图</strong><span class="viz-kind">Agent 编排 · 核心图</span></figcaption>
<div class="viz-stage"><svg class="visual legacy-visual" viewBox="0 0 1250 630" role="img" aria-label="Coordinator 与子 Agent 泳道甘特图" …>
<title>Coordinator 与子 Agent 泳道甘特图</title>
<text x="180" y="36" class="title">15:42</text><text x="1130" y="36" class="title">21:31</text>
<path d="M180 75H1180" class="gridline"/>…
<text x="20" y="88" class="value">Coordinator</text>
<rect x="180" y="66" width="1000" height="28" rx="4" fill="#f6c7441b" stroke="#f6c744"/>
<g class="interactive-node" tabindex="0" data-detail="A2 M1：实际启动的子 Agent。">
  <text x="20" y="211">A2 M1 · A7 M2b</text>
  <rect x="405" y="192" width="112" height="28" rx="4" fill="#59d6e633" stroke="#59d6e6"/>
  <path d="M517 184v44" stroke="#ff8b5a"/><text x="513" y="179" text-anchor="end" fill="#ff8b5a">30m</text></g>
…<g><text x="20" y="592">Denied call</text><circle cx="925" cy="582" r="9" class="warn"/>
  <text x="945" y="587" fill="#ff8b5a">19:09 · 授权拒绝，单列</text></g></svg></div>
<div class="viz-proofline"><span><b>来源</b>《和平精英开发app.log》及会话数据库中的 Agent、checkpoint 与文件快照记录</span>
<span><b>验证边界</b>运行次数、时长和工具事件记录执行规模；代码质量与测试覆盖由实现和验证证据分别评估。</span></div></figure>
<div class="focus-detail">时间条按日志中的调用/结束锚点抽象绘制；泳道重叠表示并行执行或 Coordinator 持续运行。</div></div>
```

配套表格（10 次运行时间线）：

```html
<div class="card" style="margin-top:18px"><h3>10 次运行时间线</h3><div class="table-wrap">
<table class="audit-table"><thead><tr><th>别名</th><th>任务</th><th>本地时间</th><th>持续</th><th>终态</th><th>接棒</th></tr></thead>
<tbody><tr><td><b>R01</b></td><td>资产下载与脚手架</td><td>15:56:23 → 16:17:28</td><td>21.08 min</td>
<td><span class="tag verified">正常收尾</span></td><td>→ R02</td></tr>
<tr><td><b>R02</b></td><td>M1 海岛世界与角色</td><td>16:20:03 → 16:50:03</td><td>30.00 min</td>
<td><span class="tag limit">30m 运行边界</span></td><td>→ R03</td></tr>…</tbody></table></div>
<p class="disclaimer">会话 ID 已用 R01–R10 代替；所有 10 个数据库会话状态均为 closed。1 次被拒调用在表外单列。</p></div>
```

### 3.2 AI 与用户的对话

**吃鸡.html**：不做逐句对话引用，而是"确认原账"结构化卡片（`.raw-decision` details，含时间戳、响应耗时、推荐项标记、全部候选）：

```html
<details class="raw-decision" data-search="用什么技术平台开发？ Web 网页版（Three.js 3D）（推荐）…">
<summary><header><div><span class="new-section-mark">Q1 · 15:44:47</span><h3>用什么技术平台开发？</h3></div>
<span class="tag verified">采用推荐</span></header>
<div class="selected"><b>用户选择</b><br>Web 网页版（Three.js 3D）（推荐）</div>
<p class="audit-footnote">响应 8.5 秒 · 推荐：Web 网页版（Three.js 3D）（推荐）</p></summary>
<ul class="option-list">
<li><b>Web 网页版（Three.js 3D）（推荐）</b><br>浏览器打开即玩、无需安装、迭代最快</li>
<li><b>Unity（C#）</b><br>商业手游常见路线，但需要编辑器与手工场景操作</li>
<li><b>Unreal Engine 5</b><br>画质上限高，但引擎和手工编辑成本大</li>
<li><b>Godot 4</b><br>开源轻量，生态相对更小</li></ul></details>
```

**淘宝.html**：用户原话用 `blockquote` + `.who` 时间戳署名：

```html
<h3>1.1 用户原话 <span class="tag t-log">日志</span> <span class="tag t-mem">记忆</span></h3>
<blockquote>从零实现一个像素级仿淘宝的电商网站（仅买家端），尽量用真实淘宝数据，不用 mock：可以 Chrome 抓取，每批抓取前先经我确认——我可以配合扫码登录淘宝，但不要频繁爬取导致我被封号。凡是不确定的方案都要先跟我确认，跟我确认前要同时给我几套独立方案选择并带推荐理由。分批推进，要做真实验证，确保最终交付系统准确可用。
<span class="who">14:02:14 · 用户消息 #1（主任务）</span></blockquote>
<blockquote>继续<span class="who">16:07:43 / 18:54:12 / 18:56:11 · 用户消息 #3/#4/#5（三次 App 重启后的恢复指令）</span></blockquote>
```

### 3.3 工具调用记录

吃鸡.html 工具分布表（VIZ-19 SVG 条图 + 配套表格）：

```html
<div class="card"><div class="table-wrap"><table class="audit-table">
<thead><tr><th>工具</th><th>完成</th><th>成功</th><th>失败</th></tr></thead>
<tbody><tr><td>Bash</td><td>221</td><td>211</td><td class="warn-text">10</td></tr>
<tr><td>Read</td><td>161</td><td>161</td><td class="">0</td></tr>
<tr><td>Edit</td><td>77</td><td>72</td><td class="warn-text">5</td></tr>
<tr><td>Write</td><td>46</td><td>46</td><td class="">0</td></tr>
<tr><td>TodoWrite</td><td>19</td><td>19</td><td class="">0</td></tr>
<tr><td>Agent</td><td>10</td><td>10</td><td class="">0</td></tr>…</tbody></table></div></div>
```

权限决策表（可筛选）：`.filter-row select#permToolFilter` + `tr.permission-row[data-tool]`，列：工具/风险/授权范围/终态/次数/平均决策秒数。

### 3.4 代码证据

**吃鸡.html**：`.audit-compact.validation-source` details，summary 带编号（S01/S02/T01），body 里 `.source-meta`（来源路径 + 是否项目持久文件 + SHA 截断）+ `<pre><code>` 带行号的源码：

```html
<details class="audit-compact validation-source" data-search="run_actions：12 类动作执行器 tools/verify.py:83–123">
<summary><span>S02</span>run_actions：12 类动作执行器</summary>
<div class="audit-compact__body"><div class="source-meta"><span>来源：tools/verify.py:83–123</span><span>项目持久文件</span></div>
<pre><code>  83  def run_actions(page, actions, summary):
  84      for i, a in enumerate(actions):
  85          t = a.get(&quot;type&quot;)
  86          if t == &quot;click&quot;:
  87              page.mouse.click(a.get(&quot;x&quot;, 640), a.get(&quot;y&quot;, 400))
  88          elif t == &quot;key_down&quot;:
  89              page.keyboard.down(a[&quot;key&quot;])…</code></pre></div></details>
```

**淘宝.html**：标题注释 + `<pre>` 原文（带文件：行号锚点）：

```html
<h3>3.1b 四段关键代码 <span class="tag t-verified">直接验证</span></h3>
<p>以下四段代码分别处理JSONP、SSR注入态、抓取中止和SKU联动，均摘自项目源文件。</p>
<h4>① JSONP 剥壳 — server/src/ingest/home.ts:29</h4>
<pre>const a = s.indexOf('('); const b = s.lastIndexOf(')');
const body = a &gt;= 0 &amp;&amp; b &gt; a ? s.slice(a + 1, b) : s;
try { return JSON.parse(body); } catch { return null; }</pre>
```

### 3.5 截图证据

吃鸡.html 截图卡（本版为占位形态，完整版同结构换成 `<button class="shot-open"><img …></button>` + lightbox）：

```html
<div class="gallery" style="margin-top:18px">
<figure class="evidence-shot reveal" data-search="M1 · 海岛鸟瞰 程序化海岛的整体轮廓与地形层次 鸟瞰原始画面；画质专项记录见完整证据模式">
  <div id="shot-0" class="media-removed media-removed-photo" role="note" aria-label="照片未包含在此版本中">
    <span>照片未包含</span><small>M1 · 海岛鸟瞰：程序化海岛的整体轮廓与地形层次</small></div>
  <figcaption><span class="tag verified">截图证据 01</span><h3>M1 · 海岛鸟瞰</h3>
    <p><b>画面直接可见：</b>程序化海岛的整体轮廓与地形层次</p>
    <p class="limit"><b>验证边界：</b>鸟瞰原始画面；画质专项记录见完整证据模式</p>
    <code title="SHA-256">d5a4224621940a45…</code></figcaption></figure>
…</div>
```

淘宝.html 截图（gallery / compare 网格 + 可点击放大 + 图注含来源/尺寸/字节/SHA 截断）：

```html
<figure><img alt="克隆首页" decoding="async" loading="lazy" data-asset="taobao复刻素材/site-home-hero.png"/>
<figcaption><b>/</b> 首页：登录态顶栏 + 主题市场 + 猜你喜欢 99 件
<span style="display:block;margin-top:6px;color:var(--faint);font-size:10px">来源 taobao复刻素材/site-home-hero.png · 1440x900 · 577KB · sha256:9c13205c0cd95736…</span></figcaption></figure>
```

### 3.6 日志证据

直接引用日志/数据库原值，并显式区分口径。吃鸡.html"可观测性泳道"：

```html
<div class="observability-lanes">
<div class="obs-lane"><b>模型提供方</b><div class="railbar" style="width:86%"></div><span>460 次尝试 · 447 成功计费 · 12 次 429</span></div>
<div class="obs-lane"><b>工具执行管线</b><div class="railbar" style="width:96%"></div><span>551 完成 · 534 success · 17 error 终态</span></div>
<div class="obs-lane"><b>持久化</b><div class="railbar" style="width:72%"></div><span>73 DB checkpoint · 78 file snapshot · 118 atomic writes</span></div>
<div class="obs-lane"><b>上下文</b><div class="railbar" style="width:54%"></div><span>8 次 collapseExecuted=true；接棒依据另由 checkpoint 与文件记录呈现</span></div>
<div class="obs-lane"><b>权限</b><div class="railbar" style="width:65%"></div><span>67 个终态 · 66 allow · 1 deny</span></div></div>
```

淘宝.html 的日志证据常放 `details > pre`（如事故#1 的 sqlite 直查输出）。

### 3.7 数据表格

吃鸡.html 合同矩阵（G1–G18，桌面宽表 + 移动端 `contract-details` mini-ledger 折叠替代）：

```html
<div class="table-wrap"><table class="audit-table contract-table">
<thead><tr><th>ID</th><th>验收项</th><th>用户选择</th><th>规格</th><th>模块/符号</th><th>直接证据</th><th>状态</th><th>验证计划</th></tr></thead>
<tbody><tr><td><b>G01</b></td><td>离线 Three.js</td><td>Web 3D / 零安装</td><td>规格 §0</td>
<td><code>index.html · vendor/three</code></td><td>本地 HTTP 资源探测</td>
<td><span class="tag verified">运行观测</span></td><td>多浏览器兼容性：扩展覆盖</td></tr>
<tr><td><b>G02</b></td><td>程序化海岛</td><td>完整吃鸡流程</td><td>规格 §1/§2</td><td><code>world/terrain.js</code></td>
<td>m1_aerial.png</td><td><span class="tag verified">画面可见</span></td><td>运行画面</td></tr>…</tbody></table></div>
```

### 3.8 统计数字

三级呈现：**hero 大数字**（`.metrics>.metric`，锚链到证据账本条目）→ **章首 part-proof/chapter-stat** → **正文标签数字**。吃鸡 hero：

```html
<div class="metrics">
<a class="metric" href="#ev-time"><strong>5:47:53</strong><span>收到需求 → 本版开发交付（墙钟）</span></a>
<a class="metric" href="#ev-loc"><strong>6,860</strong><span>首版快照独立复算（23 个模块）</span></a>
<a class="metric" href="#ev-agent"><strong>10</strong><span>实际启动的子 Agent</span></a>
<a class="metric" href="#ev-cost"><strong>¥152.15</strong><span>任务时间窗归因估算，非支付凭证</span></a></div>
```

费用公式卡（`.cost-big` + `.formula` + `.claim-qualifier` 口径声明）：

```html
<div class="cost-grid"><div class="card"><div class="cost-big">¥152.153744</div><p>任务时间窗归因费用估算</p>
<div class="formula">29,107,712 × ¥2/MTok<br>+ 2,220,851 × ¥20/MTok<br>+ 495,213 × ¥100/MTok<br>= ¥152.153744</div>
<p><span class="tag raw">CSV 原始字段汇总</span> 输入 31,328,563 · 输出 495,213 · 缓存命中 29,107,712</p>
<p class="claim-qualifier">归因假设：指定 CSV 时间窗内成功调用均计入本任务。由于 CSV 无可确定连接本会话的会话 ID，该金额应视为时间窗估算，而非精确会话账单或支付凭证。</p>…</div>
```

---

## 4. 截图/视频引用方式与编号体系

### 4.1 引用方式

- **没有传统 `assets/...` 相对路径**。吃鸡.html 完全无媒体（CSP `img-src 'none'`，0 个 `<img>`）；淘宝.html 用 `data-asset="taobao复刻素材/xxx.png"` 逻辑路径 + 内嵌注册表注入 base64。吃鸡.html 的 SVG 保留了 `image[data-media-ref]` → `document.getElementById(shot-N).src` 的注入机制，说明**完整媒体版**的约定是：HTML 里 `<img id="shot-N" src="相对路径或base64">`，SVG 里 `<image data-media-ref="shot-N">` 复用同一 src。
- **图注**：两者都有丰富图注。吃鸡 `figcaption` = tag（编号）+ h3（标题）+ 「画面直接可见」+ 「验证边界」+ SHA-256 截断；淘宝 `figcaption` = `<b>` 粗标签 + 描述 + 小字（来源路径 · 尺寸 · 字节 · sha256 截断）。
- **视频**：吃鸡用 `.clip-tab[data-clip][data-sha][data-bytes]` 片段目录 + `.video-stage/.video-empty`（无媒体版显示"视频未包含：本版本保留片段目录、时间和哈希，不携带视频文件"）+ 18 个 `.film-frame` 派生帧（"录屏 1 · 33.3s" + 帧 SHA）；淘宝明确排除视频（`.standalone-video-note`：文件名/大小/时长/分辨率/录制时间，"由用户单独上传，本文件不包含播放器、poster或MP4载荷"）。

### 4.2 编号体系（吃鸡.html，极其系统化）

| 体系 | 前缀 | 实例 | 说明 |
|---|---|---|---|
| 图表编号 | `VIZ-NN` | VIZ-01…VIZ-58（主系列）+ VIZ-P01–P18（产物下钻）+ VIZ-Z01–Z18（平台机制）+ VIZ-G01–G10（验证下钻）+ VIZ-C01（成本专题） | 共 104 个 SVG，每个 `figure[data-viz-code]`，`.viz-code` 显示编号；`window.__REPORT_VISUALIZATION_DATA__` 有对应记录 |
| 截图证据 | 截图证据 NN | 01–11（hero 用"截图证据 10 · 验收原图"） | evidence-shot，对应 MediaRecord |
| 录屏 | 录屏 N / clip N | 1、4、6、8、10、12（非连续，保留原始文件编号） | clip-tab data-clip + 吃鸡录屏N.mp4 全量 SHA-256 |
| 派生帧 | 录屏 N · Xs | 18 个（clip×index×second） | film-frame，MediaEventRecord |
| 验收合同 | `G01–G18` | G01 离线 Three.js … G18 结算闭环 | AcceptanceRecord；淘宝版为 G1–G12 |
| 里程碑 | `M0–M3b` | M0 脚手架 / M1 世界 / M2a 枪战 / M2b 搜刮 / M3a 对局 / M3b Bot 终验 | 淘宝版为 M0–M4 |
| 运行 | `R01–R10` | 子 Agent 运行别名（脱敏会话 ID） | RunRecord / RunProfileRecord |
| Agent | A1–A10 | 甘特图泳道别名 | 淘宝版为 W1–W20 + D1/D2 |
| 需求确认 | Q1–Q7 | 两轮七项选择 | InteractionRecord；淘宝版为 D1–D8 决策 + 用户消息 #1–#6 |
| 证据账本 | `ev-*` + E01–E10 + CP/FS/CTX/WR/VF/AST/LIC/FPS/END-01 | ev-time、ev-cost、CP-01… | EvidenceRecord / EnhancedEvidenceRecord |
| 源码清单 | S1/S2 | 23 模块表 + 75 文件全哈希表 | manifest-table |
| 验证源码 | S01–S04（项目持久文件）/ A01–A07（动作 JSON）/ T01–T03（临时脚本） | verify.py 片段、m2b_1_ammo.json、m3b_verify.py | validation-source |
| 工具终态/权限 | PermissionRecord | 67 条（66 allow / 1 deny） | permission-row |
| 故障 | F1–F4（吃鸡 FailureRecord）；#1–#12（淘宝 incident） | 素材/工具/429/到时 | 淘宝 12 次工程修正 |
| 恢复闭环 | 六步框架 | 症状→观测→判断→恢复动作→新证据→最终边界 | VIZ-Z14 |

**哈希校验写法**：
- 行内截断：`<code title="SHA-256">8cf5c861d0ad0ad8…</code>`。
- 全量清单：`details > dl > dt(文件名) dd(code 全哈希)`（媒体）/ `manifest-table`（路径/行数/字节/SHA-256/职责）。
- **聚合摘要指纹**：`<p class="hash">SHA-256(path + NUL + bytes + NUL + file_sha256, sorted) = 53627b8f…</p>` + 说明"该摘要固定交付项目的相对路径、字节数与内容哈希…Git commit 与文件许可证状态不在该摘要的证明范围内"。
- **自引用回避**："最终 HTML SHA-256 在文件外部记录，避免自引用改变字节"。
- 淘宝内嵌版的口径：图注 SHA 指向原始 PNG，清单表同时列出"原始字节/原始 SHA-256/内嵌编码（WebP Q86/92/无损）/内嵌字节"，并标注重复内容去重关系。

---

## 5. 页头页尾

### 5.1 页头（hero）

**吃鸡.html**：

```html
<header class="section hero" id="top">
<div class="hero-record"><span>ZHIKUNCODE · 工程案例档案</span><span>开发档案 / 截止 2026-08-04 21:31:15</span></div>
<div class="hero-grid"><div>
<div class="eyebrow">开发日期 · 2026-08-04 · 离线单机原型</div>
<h1>5 小时 47 分，<br><em>完成大逃杀原型本版交付</em></h1>
<p class="lead">2026 年 8 月 4 日 15:42 收到一句话需求，21:30 完成本版开发交付，21:31 完成启动。期间完成需求澄清、任务拆分、素材处理、跨 Agent 接续、恢复与接续、系统集成和运行验证。5 小时 47 分 53 秒是需求到本版开发交付的墙钟时间，包含确认、下载、等待、重试和工具执行。</p>
<div class="hero-actions"><button class="btn primary" data-jump="part-0">从交付过程开始</button><button class="btn" data-jump="product-depth">查看产物系统剖面</button><button class="btn" data-jump="ledger">查看证据与复算方法</button></div>
<p class="disclaimer">离线单机演示原型；采用自制或第三方通用素材，未使用《和平精英》官方地图、Logo 或美术资源。报告范围截止 2026-08-04 21:31:15。</p></div>
<div class="hero-media-stack">
  <figure class="hero-proof evidence-shot">… hero 主截图（含 tag/标题/画面可见/验证边界/SHA）…</figure>
  <figure class="viz-frame viz-level-hero viz-domain-game" data-viz-code="VIZ-01">… 战术沙盘 SVG + viz-proofline …</figure>
</div></div>
<div class="metrics">…4 个锚链大数字…</div>
</header>
```

**淘宝.html**：`h1`（渐变 em 高亮）+ `p.lead`（一段总数字密度极高的概述）+ `.hero-product-stage`（六张截图错位拼贴 + `.hero-route-line` 路径线）+ 8 个 metric + 合规小字。

### 5.2 免责声明 / 利益声明写法

- 吃鸡 hero：素材合规声明（"未使用《和平精英》官方地图、Logo 或美术资源"）+ 报告范围截止时间。
- 吃鸡 method 章"隐私与安全"卡："不展示 API Key ID、项目 ID、内部账户标识、无关会话、Secret、Authorization 或 Cookie。账本只保留必要来源名与哈希。"
- 淘宝 hero 小字："本项目仅用于个人学习与研究。所有抓取均在用户本人授权、本人账号、本人浏览器内进行，批次均经用户逐批确认，并采取严格限速"。
- 淘宝分享边界 callout（green）："本 HTML 与 <MATERIAL_ROOT> 是对外分享面；浏览器登录状态、profile、Cookie 值、API Key/项目/账户标识和个人路径均不随报告交付…结算截图、订单截图和录屏中的姓名、电话、地址是自动化测试虚构值，不对应真实个人。"
- 归因声明（两者都有"五方协作"图）：不把联合结果全部归因给工具——"用户确定需求与授权，模型负责推理和生成，ZhikunCode 负责长程执行、编排、工具、状态、恢复与验证，真实环境完成最终运行"。

### 5.3 页尾（footer）

**吃鸡.html**：
```html
<footer class="part-transition"><span>本次开发从需求确认、系统实现到启动验证在此收口。</span>
<div class="footer-links"><a href="./zhikuncode-和平精英最终版开发.html">查看最终版本开发报告 →</a><a href="#top">返回报告顶部 ↑</a></div></footer>
<footer><b>ZhikunCode：从一句话需求到可玩的大逃杀原型｜开发版本</b><br>
证据截止：2026-08-04 21:31:15（Asia/Shanghai） · 增强版生成于 2026-08-05 · 单文件离线报告 · 不含视频照片</footer>
```

**淘宝.html**：
```html
<footer style="margin-top:80px;border-top:1px solid var(--line);…font:11.5px/1.9 var(--mono)">
<p>ZHIKUNCODE 工程案例 · 复刻淘宝 · 2026-08-06 14:02→21:30 (+0800) · 会话 76d0cf6b-2fc9-4213-9551-bb57f5960189</p>
<p>证据标签口径：<span class="tag t-verified">实测</span>可复查的产物/脚本/截图 · <span class="tag t-log">日志</span>应用日志与账单 CSV · <span class="tag t-db">数据库</span>SQLite 直查 · <span class="tag t-mem">记忆</span>协调者会话记录（未落日志部分）· <span class="tag t-infer">推断</span> · <span class="tag t-limit">限制</span></p>
</footer>
```

---

## 6. 两个文件的差异点（淘宝.html 相比吃鸡.html 多出/不同的）

| 维度 | 吃鸡.html（重点模板） | 淘宝.html（次要参考） |
|---|---|---|
| 信息架构 | 两级：narrative-part（章）→ section.chapter（材料组 X.Y），data-part/data-module/data-cluster 寻址 | 单级：section.part ×17，h3 小节号（1.1/9.2） |
| 章首 | part-intro：问题驱动的 part-story + part-proof-grid 数字 + part-limit 折叠口径 + 模块导航 + 阅读路径 | chapter-band：描边大序号 + kicker/theme/lead + chapter-stats ×3 + 主题底纹 pattern-* |
| 主题色 | 黄铜/墨绿（--yellow:#c7a45a） | 淘宝橙（--tb:#ff6432），多一组紫 #a989d4、蓝 #5e8cc8 |
| 标签语义 | `.tag` + verified/observed/raw/derived/limit/spec（证据强度） | `.tag` + t-verified/t-log/t-db/t-mem/t-infer/t-limit（**来源类型**：实测/日志/数据库/协调者记忆/推断/限制） |
| 媒体 | 完全无媒体（85 处 media-removed 占位） | **内嵌图片**：embedded-asset-registry JSON（35 载荷/38 路径，WebP 压缩）+ JS 注入 data-asset；视频单独上传不内嵌 |
| 图卡体系 | viz-frame（viz-head/viz-code/viz-kind + viz-stage + viz-inspector + viz-audit-copy + viz-proofline），分级 viz-level-hero/analysis/audit，分域 viz-domain-* 着色 | viz-card（viz-title + viz-scroll + svg.viz-svg + .cap + .viz-proof），viz-hero/viz-core 两级；部分图带 `viz-enhancement-band` 附加信息带 |
| 图表绘制 | 双轨：`legacy-visual`（规则网格小图）+ `viz-redesigned/v2-visual`（1200×680 精细工程图，v-* 类名体系，可交互 interactive-node） | 单轨：`viz-svg viz-dense`（1200×500~1020，直接 fill/stroke 属性），旧图标 `legacy-upgraded` |
| 用户对话 | 结构化"确认原账"raw-decision（Q1–Q7，候选全部保留） | **用户原话 blockquote.who**（带消息编号与时间戳） |
| 事故/修正 | recovery-card（四类故障域）+ 恢复六步闭环框架 | **incident 卡 #1–#12**（现象/根因/修复/教训四行 dl）+ 原始诊断 pre 底稿 + 12 次修正总账 SVG + 诊断故事板 |
| 独有板块 | 验收合同矩阵 G1–G18（8 列宽表+移动端 mini-ledger 替代）、证据账本（可搜索 ledger-row）、源码清单 manifest-table、缓存经济性 cache-explainer、判定语义（VIZ-44/G10"成功信号传播链"） | 数据工程（抓取批次/登录攻防/防封 guard/解析管线）、像素 diff 测量（real/clone/diff 三联 compare）、Git 提交记录（37 次 pre）、Worker 分工（20 Worker 表、任务切片）、交付排除项说明、速查附录（关键路径/已知限制 ①–⑩）、G1–G12 逐项原始证据 |
| 数据沉淀 | window.ReportNarrative + __REPORT_DATA__ + __REPORT_VISUALIZATION_DATA__（20+ 组冻结 JSON，含 grammar/claimIds/limitations） | 无同等级数据层（数据直接写在 HTML/SVG 里） |
| 审计模式 | `body.audit-mode`（"完整证据模式"按钮），控制 audit-extra/viz-proofline/limit 行/VIZ-26/30 显隐 | `body.evidence-mode`（"展开审计底稿"按钮），控制 viz-audit/viz-proof/chapter-source-copy + 展开 audit-bundle |
| 页数感 | 990KB / 104 SVG / 124 details | 3.5MB（含 2.9MB 图片载荷）/ 69 SVG / 40 details / 33 pre |
| 打印 | @media print 完善（含 contract-table↔contract-details 互换） | @media print 基础（强制显示底稿） |

**淘宝独有的可借鉴板块**（吃鸡没有的）：①用户原话直引（blockquote）②逐次工程修正 incident 卡与"原始诊断 pre"③像素 diff 三联对照（real/clone/diff）④Git 提交时间线 pre ⑤内嵌资源清单表（原始字节 vs 内嵌字节双列账）⑥速查附录（关键路径+已知限制清单）⑦"分享包安全边界"绿色 callout ⑧hero 截图拼贴舞台。

---

## 7. 新案例（王者荣耀开发实证）可直接复用的骨架

> 建议**以吃鸡.html 的两级架构 + 证据纪律为主干**，按需吸收淘宝.html 的独有组件。主题色换成王者品牌色（如 --gold:#c9a45a → 保留，加 --king-blue）。编号体系沿用：VIZ-NN / 截图证据 NN / 录屏 N / G01–Gnn / M0–M_n / R01–R_n / Q1–Q_n / ev-* 账本。

按顺序的 section 清单（中文标题可直接改写在括号里的占位内容）：

```
0. Hero（header.hero）
   - hero-record（档案名 + 截止时间）
   - eyebrow（开发日期 · 产物形态）
   - h1：X 小时 X 分，完成王者荣耀可玩原型（em 高亮关键结果）
   - lead：一句话需求 → 交付的时间与范围总述
   - hero-actions：3 个跳转按钮（交付过程 / 产物剖面 / 证据账本）
   - disclaimer：素材合规声明（未使用王者荣耀官方资源）+ 范围截止
   - hero-media-stack：主截图 evidence-shot + VIZ-01 王者峡谷战术沙盘 SVG
   - metrics ×4：墙钟 / 源码行数(锚#ev-loc) / 子Agent数(锚#ev-agent) / 费用(锚#ev-cost)

1. 序 · 这次交付了什么（part-0）
   1.1 时间与版本边界（boundary-banner + proof-pair 覆盖范围/交付锚点）
   1.2 本版交付概览（VIZ 总路径图 + 时间范围尺 + 产物/执行/范围三卡
       + review-brief 关键指标工程含义 + capability-grid 能力概览
       + 难点矩阵 audit-table + reader-paths + 三条阅读路径）

2. 先把一句话问清楚（part-1 · 需求治理）
   2.1 从一句话到工程合同（需求证据链 VIZ + decision-list N 项选择：推荐项/偏离推荐）
   2.2 两轮确认与授权原账（Q1–Qn raw-decision 折叠卡 + 权限决策表 permission-row）
   2.3 G1–Gnn 验收合同（覆盖矩阵 VIZ + contract-table + 移动端 mini-ledger）
   2.4 本版交付与扩展路线（scope-table：已实现/原型化/后续扩展 + 证据强度矩阵）

3. N 个模块组成的实时游戏（part-2 · 产物解剖）
   3.1 复杂系统全景与系统下钻（complexity-metrics + complexity-ribbon + product-subnav
       + VIZ-P01 运行时全景/P02 主循环调度/P03 状态域泳道…逐系统下钻 cluster：
       英雄与操控 / 技能与战斗 / 兵线野怪 / 地图视野 / 装备经济 / 对局流程 / AI / UI / 性能 / 音频）
   3.2 N 模块工程架构（拓扑图 + code-bars 行数分布 + import 依赖图）
   3.3 合同到源码与证据追踪（trace-chain + G 项→文件→符号→运行结果表）
   3.4 游戏系统配置与状态机（地图蓝图 + 配置表 + 状态机图）
   3.5 素材供应链与离线运行（资产表：文件数/字节/来源/树哈希 + 供应链图 + 离线边界图 + 素材合规 warning-card）

4. ZhikunCode 的工程运行时（part-3 · 平台机制）
   4.1 Agent 软件工程运行时（五方协作 VIZ-Z01 + 三端架构 + 当前源码规模 Treemap + QueryEngine 循环）
   4.2 工具与权限控制面（九阶段管道 + 权限复检分支图）
   4.3 上下文治理与持久化（上下文地层 + 持久化拓扑：交互链/运行链/文件链）
   4.4 浏览器与自动化反馈（外部感知闭环 + 平台验证与项目验证两层分工）

5. N 个子任务汇入同一个项目（part-4 · 执行过程）
   5.1 平台能力到阶段与产物（机制→里程碑→系统映射图）
   5.2 Coordinator 与子 Agent 编排（agent-note 数字条 + 泳道甘特 VIZ + 接力链 + 受阻链路图）
   5.3 受控多 Agent 运行时（Run 舱图 + checkpoint 条图）
   5.4 逐 Run 执行账本（R01–R_n 时间线表 + profile-table：checkpoint/工具序号/Turn/Token + 口径隔离 source-note）
   5.5 M0–M_n 产物谱系（里程碑谱系图 + 产物体积分布 + 交付消息与快照复算对账）
   5.6 文件交接与增量演进（快照集中度图 + 交接图 + 文件快照表：路径/快照数/首末时间/操作）

6. 复杂环境下的持续交付（part-5 · 运行韧性）
   6.1 状态控制与持久化（metrics + 工具分布图/表 + 上下文压缩事件 + 持久化数据链）
   6.2 故障隔离与恢复闭环（recovery-card ×N：症状/恢复动作/新证据/适用范围 + 回退路径图 + 恢复总线）
   6.3 可观测性与因果边界（observability-lanes + 异常分类表 + token-triad 三种 Token 口径 + 恢复六步闭环图）

7. 游戏的启动、操控与自动验证（part-6 · 验证体系）
   7.1 验证总体架构与启动门禁（validation-kicker 数字带 + validation-legend 图例
       + 验证架构图 + 验证器执行剖面 + 启动状态机 + 参数表）
   7.2 动作、钩子、用例与判定（动作 DSL 表 + 观测钩子表 + 用例合同故事板
       + validation-source 代码底稿 S01–/A01–/T01– + 判定语义传播链 + 可信度矩阵）
   7.3 N 张验收截图（截图-状态对应图 + evidence-shot 画廊：画面直接可见/验证边界/SHA-256）
   7.4 N 段过程录屏（clip-tab 目录 + 播放器/占位 + 派生帧 film-strip + 时长取证点图 + 关键事件表）

8. 把成本、源码和证据摊开（part-7 · 工程审计）
   8.1 Token 与费用复算（cost-big + formula + 口径 claim-qualifier + Token 构成图
       + 阶段费用表 + 分位数 + 请求密度与 429 + 累计曲线 + 缓存机制专题 cache-explainer）
   8.2 证据账本与血缘（ledger-toolbar 搜索框 + ledger-row ev-* 折叠账 + 扩展账本 E01– + 血缘图 + 类型矩阵 + 媒体 SHA-256 清单）
   8.3 首版源码完整清单（metrics + manifest-table 23模块/75文件全哈希 + 聚合摘要指纹 .hash + CSP 说明卡）
   8.4 统计方法与版本边界（method-grid 纳入范围/版本边界/隐私安全 + 原始需求证据图 + 构建审计闸门图 + 三版本域归因图）

9. 结论：这次任务说明了什么（part-8）
   9.1 能力结论与结果（直接证据清单 claim-list + 产品化扩展路线 warning-card
       + 能力-证据映射 VIZ + promo-closing 收口句 + 五方归因 platform-bridge）
   - 章末 part-transition + footer-links（相关报告互链 + 返回顶部）

10. 页面总 footer：报告名｜版本 · 证据截止时间(时区) · 生成日期 · 单文件离线报告 · 媒体形态说明

尾部 <script>：
   - window.ReportNarrative（章/模块/阅读路径/过渡语记录）
   - window.__REPORT_DATA__（EvidenceRecord/AcceptanceRecord/RunRecord/InteractionRecord/
     PermissionRecord/PhaseRecord/MediaRecord/MediaEventRecord/FileEvolutionRecord/
     SymbolTraceRecord/SourceSnapshotRecord…全部冻结 JSON）
   - window.__REPORT_VISUALIZATION_DATA__（每张图的 grammar/claimIds/sourceLocators/limitations）
   - 交互：progress / data-jump / 3×IntersectionObserver / interactive-node inspector /
     audit-mode 开关 / ledger 搜索+展开 / 筛选器 / SVG media-ref 注入
```

**可选吸收淘宝.html 的组件**：若案例需要——用户原话 blockquote（主任务原文）、incident 卡（逐次调试修正）、Git 提交 pre、速查附录（关键路径 + 已知限制）、内嵌图片版媒体方案（embedded-asset-registry + data-asset，若要求离线自包含且带图）。

---

## 附：两份文件的关键量化指纹

| 指标 | 吃鸡.html | 淘宝.html |
|---|---|---|
| 体积 / 行数 | 990 KB / 2,005 行 | 3.5 MB / 1,665 行（其中 1 行 2.9MB 为图片注册表 JSON） |
| `<style>` / `<script>` | 11 / 1 | 1 / 3 |
| SVG 图 | 104（VIZ 编号 104 个） | 69（viz-hd- 编号 44 个 + viz-title 54 个） |
| details 折叠块 | 124 | 40 |
| `<pre>` 代码块 | 14 | 33 |
| 图片标签 | 0（CSP 禁图） | 45（data-asset 注入）+ SVG image |
| 视频 | 0（保留目录与哈希） | 0（单独上传，standalone-video-note 声明） |
| 验收合同 | G1–G18 | G1–G12 |
| 里程碑 / 运行 | M0–M3b / R01–R10 | M0–M4 / 20 Worker（W/D 别名） |
| 审计模式 | audit-mode（完整证据模式） | evidence-mode（展开审计底稿） |
