# APOS Phase 2 端到端测试用例

> **版本**: Phase 2 | **设计日期**: 2026-05-12 | **测试范围**: APOS Phase 2 全栈功能验证（变更影响全景、Pipeline 视图、异常检测引擎、DAG 增强、资源消耗、推送通知、手机端响应式、MobileBottomSheet、Feature Flag 扩展、Phase 1 回归、三端集成）
> **前提条件**: 三端启动（Backend:8080 / Python:8000 / Frontend:5173）
> **数据来源**: 真实工具调用生成的 Activity 数据（禁止使用 Mock）

---

## 0 测试用例总览

| 模块 | 用例数 | 优先级 | 编号范围 |
|------|--------|--------|----------|
| A. ChangeImpactPanel - 变更影响全景 | 6 | P0 | TC-APOS2-001 ~ 006 |
| B. Pipeline 视图 - Agent 进度可视化 | 5 | P0 | TC-APOS2-007 ~ 011 |
| C. Agent 协作关系图 - DAG 增强 | 4 | P1 | TC-APOS2-012 ~ 015 |
| D. 资源消耗展示 | 3 | P1 | TC-APOS2-016 ~ 018 |
| E. 异常检测引擎 | 6 | P0 | TC-APOS2-019 ~ 024 |
| F. 推送通知集成 | 4 | P1 | TC-APOS2-025 ~ 028 |
| G. 手机端响应式布局 | 5 | P1 | TC-APOS2-029 ~ 033 |
| H. MobileBottomSheet | 4 | P1 | TC-APOS2-034 ~ 037 |
| I. Feature Flag 控制 | 5 | P0 | TC-APOS2-038 ~ 042 |
| J. Phase 1 功能回归 | 4 | P0 | TC-APOS2-043 ~ 046 |
| K. 三端集成验证 | 4 | P0 | TC-APOS2-047 ~ 050 |
| **合计** | **50** | | |

---

## 1 测试环境

| 项目 | 详情 |
|------|------|
| **操作系统** | macOS Darwin 26.4.1 (Apple Silicon) |
| **Node.js** | v22.14.0 + Vite 开发服务器 (5173) |
| **JDK** | Amazon Corretto 21 |
| **浏览器** | Chromium (Playwright) |
| **Frontend** | React + TypeScript + Zustand + Framer Motion + @xyflow/react |
| **WebSocket** | STOMP 1.2 over SockJS |
| **测试框架** | Playwright (E2E) + curl (REST API) + Node.js ws (WebSocket) |

**APOS Phase 2 Feature Flags（`types/apos.ts` APOS_FLAG_DEFAULTS）：**

| Flag | 默认值 | 说明 | 依赖 |
|------|--------|------|------|
| `APOS_ACTIVITY_STREAM` | `true` | 主面板开关 | — |
| `APOS_AI_INSIGHT` | `true` | AI 洞察分析 | APOS_ACTIVITY_STREAM |
| `APOS_BATCH_REVIEW` | `true` | 批量审查 | APOS_ACTIVITY_STREAM |
| `APOS_RISK_HEATMAP` | `false` | 风险热力图 | APOS_AI_INSIGHT |
| `APOS_CHANGE_IMPACT` | `true` | 变更影响全景面板 | APOS_ACTIVITY_STREAM |
| `APOS_AGENT_PIPELINE` | `true` | Agent Pipeline 多 Worker 可视化 | APOS_ACTIVITY_STREAM |
| `APOS_ANOMALY_ALERT` | `true` | 异常告警面板 | APOS_AGENT_PIPELINE |
| `APOS_MOBILE_STATUS` | `true` | 移动端底部状态栏 | APOS_ACTIVITY_STREAM |

**异常检测引擎规则参数（`AnomalyDetectionEngine.ts`）：**

| 规则 | ruleId | 阈值 | severity |
|------|--------|------|----------|
| 循环检测 | `loop_detection` | 最近 10 次调用中 50%+ 重复（`uniqueKeys.size < recent.length * 0.5`，最少 4 条） | error |
| 卡死检测 | `stall_detection` | 超过 60s 无工具调用（`60_000ms`） | critical |
| 连续失败 | `error_cascade` | 最近 5 次中 3+ 错误（最少 3 条） | error |

**冷却期**: 30 秒固定冷却（`anomalyStore.ts` `30_000ms`）

**响应式断点（`useResponsive.ts`）：**

| 断点 | 条件 |
|------|------|
| Desktop | `min-width: 1024px` |
| Tablet | `min-width: 768px` and `max-width: 1023px` |
| Mobile | `max-width: 767px` |

---

## 2 测试用例详细设计

### A. ChangeImpactPanel - 变更影响全景（P0）

---

#### TC-APOS2-001: ChangeImpactPanel 空状态展示

**优先级**: P0
**前置条件**:
- 三端服务启动完成
- Feature Flag `APOS_CHANGE_IMPACT=true`（默认）
- 当前会话无任何代码变更操作
- 浏览器访问 `http://localhost:5173`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 切换到 Activity Tab，展开 "变更影响全景" 折叠区域（点击 `▶ 变更影响全景`） | 折叠区域展开，`▶` 变为旋转 90° 状态 | ![空状态](APOS2-TC001-01-empty.png) |
| 2 | 检查面板内容 | 显示空状态图标（文件图标，`opacity-40`）+ 主文本 "暂无变更影响数据" + 副文本 "当会话产生代码变更后，此处将展示聚合分析" | ![空状态详情](APOS2-TC001-02-empty-detail.png) |

**通过标准**: 无数据时显示正确的空状态占位符，文案与代码一致

---

#### TC-APOS2-002: ChangeImpactPanel 风险摘要卡片

**优先级**: P0
**前置条件**:
- 在聊天窗口中执行真实工具调用产生代码变更（例：发送 "修改 README.md 的标题" 等实际编辑任务，至少产生 3 次文件编辑 Activity）
- Activity 数据已通过 WebSocket 推送至前端

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 切换到 Activity Tab，展开 "变更影响全景" | 面板展示聚合后的变更数据 | ![有数据](APOS2-TC002-01-data.png) |
| 2 | 检查顶部 Risk Summary 区域 | 2×2 或 4 列网格布局（`grid grid-cols-2 sm:grid-cols-4`），显示 4 张摘要卡片 | ![摘要卡片](APOS2-TC002-02-summary.png) |
| 3 | 检查 "总文件数" 卡片 | 默认边框色（`border-[var(--border)]`），显示变更文件总数 | — |
| 4 | 检查 "高风险" 卡片 | 红色系边框（`border-red-500/30 text-red-400`），显示 `danger` + `warning` 级别文件数 | — |
| 5 | 检查 "测试缺口" 卡片 | 黄色系边框（`border-yellow-500/30 text-yellow-400`），显示 `testCoverageGap=true` 的文件数 | — |
| 6 | 检查 "间接影响" 卡片 | 蓝色系边框（`border-blue-500/30 text-blue-400`），显示所有文件的 `indirectImpacts` 总数 | — |

**通过标准**: 4 张 SummaryCard 分别显示总文件数、高风险数、测试缺口数、间接影响数，颜色样式正确

---

#### TC-APOS2-003: FileChangeItem 文件列表展示

**优先级**: P0
**前置条件**:
- 已有多个文件变更数据（至少 2 个文件被修改）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 查看文件列表区域 | 每个文件显示为一个 `FileChangeItem` 卡片，按 riskLevel 降序排列（danger → warning → review → safe） | ![文件列表](APOS2-TC003-01-filelist.png) |
| 2 | 检查文件变更类型图标 | added 显示绿色 `+`（`text-green-400`）、modified 显示橙色 `~`（`text-orange-400`）、deleted 显示红色 `-`（`text-red-400`） | — |
| 3 | 检查文件路径显示 | 路径超过 3 级时截断为 `…/最后三级`（如 `…/src/components/App.tsx`），鼠标悬停显示完整路径（title 属性） | — |
| 4 | 检查 touchCount 徽章 | 当文件被修改超过 1 次时（`touchCount > 1`），显示 `×N` 徽章（灰色背景） | ![touchCount](APOS2-TC003-02-touchcount.png) |
| 5 | 检查增删行数 | 每个文件下方显示绿色 `+N`（additions）和红色 `-N`（deletions） | — |
| 6 | 检查 testCoverageGap 标签 | 缺少测试覆盖的文件显示红色标签 "缺少测试覆盖"（`bg-red-500/20 text-red-300`） | — |

**通过标准**: 文件列表正确展示变更类型、路径、touchCount、增删行数和测试覆盖标签

---

#### TC-APOS2-004: FileChangeItem 风险等级背景色

**优先级**: P0
**前置条件**:
- 文件列表中包含不同 riskLevel 的文件

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 观察 riskLevel=danger 的文件（touchCount >= 3 或含 high severity 间接影响） | 卡片背景为 `bg-red-500/10 border-red-500/30`（红色系） | ![danger](APOS2-TC004-01-danger.png) |
| 2 | 观察 riskLevel=warning 的文件（touchCount >= 2 或含间接影响） | 卡片背景为 `bg-yellow-500/10 border-yellow-500/30`（黄色系） | — |
| 3 | 观察 riskLevel=review 的文件（增删行数 > 50） | 卡片背景为 `bg-blue-500/10 border-blue-500/30`（蓝色系） | — |
| 4 | 观察 riskLevel=safe 的文件 | 卡片背景为 `bg-transparent border-[var(--border)]`（默认） | — |

**通过标准**: 4 种 riskLevel 对应 4 种背景/边框颜色，与代码 `RISK_BG` 映射一致

---

#### TC-APOS2-005: FileChangeItem 间接影响展开/收起

**优先级**: P1
**前置条件**:
- 至少一个文件具有 `indirectImpacts.length > 0`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 找到有间接影响的文件 | 文件卡片底部显示蓝色链接 "▸ 间接影响 (N)"（N 为间接影响数量） | ![间接影响收起](APOS2-TC005-01-collapsed.png) |
| 2 | 点击 "▸ 间接影响 (N)" | 展开间接影响列表，按钮变为 "▾ 间接影响 (N)"；列表中每项显示文件路径 + 原因 | ![间接影响展开](APOS2-TC005-02-expanded.png) |
| 3 | 检查间接影响严重性颜色 | `high` → 红色圆点（`bg-red-400`）、`medium` → 黄色圆点（`bg-yellow-400`）、`low` → 蓝色圆点（`bg-blue-400`） | — |
| 4 | 再次点击 "▾ 间接影响 (N)" | 列表收起，回到折叠状态 | — |
| 5 | 验证点击间接影响区域不触发文件选择 | 点击展开/收起按钮时，`e.stopPropagation()` 阻止冒泡，不触发 `onClick` | — |

**通过标准**: 间接影响列表可展开/收起，严重性颜色映射正确，点击不冒泡到父元素

---

#### TC-APOS2-006: ChangeImpactPanel 风险等级计算逻辑验证

**优先级**: P0
**前置条件**:
- 通过真实操作产生不同条件的文件变更数据

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 对同一文件执行 3 次以上编辑操作 | 该文件 `touchCount >= 3`，riskLevel 显示为 `danger`（红色） | ![danger验证](APOS2-TC006-01-danger.png) |
| 2 | 对同一文件执行 2 次编辑操作 | 该文件 `touchCount >= 2`，riskLevel 显示为 `warning`（黄色） | — |
| 3 | 对文件执行大量代码修改（增删行数 > 50 行） | 若 `touchCount < 2` 且无间接影响，riskLevel 显示为 `review`（蓝色） | — |
| 4 | 对文件执行微小修改（增删行数 <= 50 行，touchCount=1） | riskLevel 显示为 `safe`（默认） | — |

**通过标准**: 风险等级计算与 `changeImpactStore.ts` 中的规则一致（touchCount >= 3 → danger, >= 2 → warning, additions+deletions > 50 → review, 其他 → safe）

---

### B. Pipeline 视图 - Agent 进度可视化（P0）

---

#### TC-APOS2-007: AgentPipelineView 空状态展示

**优先级**: P0
**前置条件**:
- Feature Flag `APOS_AGENT_PIPELINE=true`（默认）
- 无活跃 Swarm 实例

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 切换到 Activity Tab，展开 "Agent Pipeline" 折叠区域 | 折叠区域展开 | ![Pipeline空](APOS2-TC007-01-empty.png) |
| 2 | 检查面板内容 | 标题 "Agent Pipeline"；空状态显示 🔗 图标 + "No active Swarm" + "Pipeline will appear when a Swarm is running" | ![Pipeline空详情](APOS2-TC007-02-empty-detail.png) |

**通过标准**: 无 Swarm 时显示正确的空状态，文案与代码一致

---

#### TC-APOS2-008: AgentPipelineView 创建 Swarm 后展示 Worker 节点

**优先级**: P0
**前置条件**:
- 后端 Feature Flag `ENABLE_AGENT_SWARMS=true`
- 通过 API 创建 Swarm 实例

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 执行 `curl -X POST http://localhost:8080/api/swarm -H 'Content-Type: application/json' -d '{"teamName":"test-team","maxWorkers":3,"sessionId":"<当前会话ID>"}'` | 返回 200 OK，包含 `swarmId`、`teamName`、`phase`、`maxWorkers` | — |
| 2 | 在前端展开 "Agent Pipeline" 区域 | 标题 "Agent Pipeline" 下显示 Worker 节点网格 | ![Pipeline有数据](APOS2-TC008-01-workers.png) |
| 3 | 检查 Worker 节点卡片 | 每个节点为 `PipelineNode` 组件，圆角卡片（`rounded-lg border-2`），显示 workerId + 状态图标 | — |
| 4 | 验证网格布局 | 布局为 `grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3`（响应式） | — |

**通过标准**: 创建 Swarm 后 Pipeline 视图正确显示 Worker 节点

---

#### TC-APOS2-009: PipelineNode 状态图标和边框颜色映射

**优先级**: P0
**前置条件**:
- 有活跃 Swarm 且 Worker 处于不同状态

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 观察 STARTING 状态的 Worker | 图标 ⏳，边框 `border-yellow-400` | ![STARTING](APOS2-TC009-01-starting.png) |
| 2 | 观察 WORKING 状态的 Worker | 图标 🔄，边框 `border-blue-500`；显示进度条（蓝色，`bg-blue-500`）和步骤文本 "N/M steps" + "X%" | ![WORKING](APOS2-TC009-02-working.png) |
| 3 | 观察 IDLE 状态的 Worker | 图标 ⏸，边框 `border-gray-400` | — |
| 4 | 观察 TERMINATED(completed) 状态的 Worker | 图标 ✅，边框 `border-gray-500` | — |
| 5 | 观察 TERMINATED(error) 状态的 Worker | 图标 ❌，边框 `border-red-500`，底部显示红色错误消息 `⚠️ <errorMessage>` | ![TERMINATED-error](APOS2-TC009-03-error.png) |
| 6 | 观察 TERMINATED(aborted) 状态的 Worker | 图标 ⚫，边框 `border-gray-500` | — |

**通过标准**: 6 种 Worker 状态（STARTING/WORKING/IDLE/TERMINATED×3）图标和边框颜色均与代码映射一致

---

#### TC-APOS2-010: PipelineNode 进度条和步骤描述

**优先级**: P0
**前置条件**:
- 有 Worker 处于 WORKING 状态且具有 `progressPercent` 数据

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 观察 WORKING 状态 Worker 的进度条 | 进度条可见（灰色背景 `bg-gray-200 dark:bg-gray-700`，蓝色填充 `bg-blue-500`），宽度对应 `progressPercent` 百分比 | ![进度条](APOS2-TC010-01-progress.png) |
| 2 | 检查步骤计数文本 | 左侧显示 "N/M steps"（`completedSteps/totalSteps`），右侧显示 "X%"（`progressPercent`） | — |
| 3 | 检查当前步骤描述 | 进度条下方显示 `currentStepDescription` 文本，超长截断（`truncate`） | — |
| 4 | 验证进度条范围限制 | `progressPercent` 被限制在 0-100 之间（`Math.min(100, Math.max(0, ...))`） | — |

**通过标准**: WORKING 状态下进度条、步骤计数、步骤描述均正确展示

---

#### TC-APOS2-011: AgentPipelineView Workers 等待状态

**优先级**: P1
**前置条件**:
- Swarm 已创建但 Workers 列表为空

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 查看 Pipeline 区域 | 标题 "Agent Pipeline" 可见；内容区域显示 ⏳ 图标 + "Waiting for workers to start..." | ![等待中](APOS2-TC011-01-waiting.png) |

**通过标准**: Swarm 存在但 Workers 为空时显示等待提示

---

### C. Agent 协作关系图 - DAG 增强（P1）

---

#### TC-APOS2-012: AgentDAGChart 空状态展示

**优先级**: P1
**前置条件**:
- 无 Agent 任务正在运行
- 已切换到 DAG 可视化 Tab

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 切换到 DAG 视图 Tab | 页面显示 DAG 视图 | ![DAG空状态](APOS2-TC012-01-empty.png) |
| 2 | 检查空状态内容 | 显示 Network 图标 + "暂无 Agent 任务" + "当 Agent 协作任务开始时，DAG 将自动显示" | — |

**通过标准**: 无 Agent 任务时 DAG 视图展示正确的空状态

---

#### TC-APOS2-013: AgentDAGChart 三种边类型渲染

**优先级**: P1
**前置条件**:
- 有多个 Agent 任务正在运行
- 存在 Mailbox 通信事件

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 查看 DAG 图中的边 | 显式依赖边（`explicit_dependency`）为蓝色实线（`#3B82F6`），`strokeWidth: 1.5` | ![边类型](APOS2-TC013-01-edges.png) |
| 2 | 检查 Mailbox 通信边 | Mailbox 通信边（`mailbox_communication`）为绿色实线（`#10B981`），运行中时有动画 (`animated`)，显示数据大小标签（如 "1024B"） | — |
| 3 | 检查时间推断边 | 时间推断边（`time_inferred`）为灰色虚线（`#9CA3AF`，`strokeDasharray: '5,5'`），`strokeWidth: 1`，无动画 | — |

**通过标准**: 三种边类型颜色、线型、动画效果与 `EDGE_STYLES` 映射一致

---

#### TC-APOS2-014: AgentDAGChart 工具栏操作

**优先级**: P1
**前置条件**:
- DAG 图中有节点和边

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 点击工具栏的方向切换按钮（`ArrowRightFromLine` / `ArrowDownFromLine`） | DAG 布局在 TB（从上到下）和 LR（从左到右）之间切换，自动 fitView | ![方向切换](APOS2-TC014-01-direction.png) |
| 2 | 点击 "适应视图" 按钮（`Maximize2`） | DAG 自动缩放适应视口，`padding: 0.2` | — |
| 3 | 点击全屏按钮 | DAG 容器进入全屏模式，按钮变为退出全屏图标（`Minimize2`） | — |
| 4 | 点击节点 | 右侧弹出 NodeDetailPanel（宽 `w-72`），显示 Agent 名称、类型、状态、描述、进度、结果 | ![节点详情](APOS2-TC014-02-detail.png) |
| 5 | 点击 NodeDetailPanel 关闭按钮 | 面板关闭 | — |

**通过标准**: 工具栏方向切换、fitView、全屏、节点详情面板均正常工作

---

#### TC-APOS2-015: AgentDAGChart 超过 20 节点自动摘要

**优先级**: P2
**前置条件**:
- Agent 任务 + Swarm Worker 节点总数 > 20

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 查看 DAG 图 | 仅显示前 20 个节点，最后添加一个摘要提示节点 | ![摘要节点](APOS2-TC015-01-summary.png) |
| 2 | 检查摘要提示节点内容 | 节点名称为 "+N more"（N = 实际节点数 - 20），描述为 "共 X 个节点，已折叠显示" | — |
| 3 | 检查边过滤 | 仅显示连接到已展示节点的边，折叠节点的边不渲染 | — |

**通过标准**: 超过 20 个节点时自动折叠为摘要视图

---

### D. 资源消耗展示（P1）

---

#### TC-APOS2-016: CostStore 会话费用实时更新

**优先级**: P1
**前置条件**:
- 三端服务运行中
- 当前会话中发送过消息（产生 token 消耗）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 发送一条消息给 AI 并等待回复 | AI 正常回复 | — |
| 2 | 检查费用显示区域 | 通过 WebSocket `cost_update` 消息推送的 `sessionCost`、`totalCost` 和 `usage`（inputTokens、outputTokens、cacheReadInputTokens、cacheCreationInputTokens）数据正确展示 | ![费用数据](APOS2-TC016-01-cost.png) |
| 3 | 验证 token 数据非零 | `inputTokens > 0`，`outputTokens > 0`（使用过缓存时 `cacheReadInputTokens > 0`） | — |

**通过标准**: 会话费用通过 WebSocket 实时推送并正确展示

---

#### TC-APOS2-017: Swarm Worker 资源消耗追踪

**优先级**: P1
**前置条件**:
- 有活跃 Swarm 且 Worker 正在工作

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 查看 Pipeline 视图中的 Worker 节点 | Worker 完成任务后的日志显示 `(N tools, M tokens)` 格式的资源消耗 | ![Worker资源](APOS2-TC017-01-worker-cost.png) |
| 2 | 通过 API 查询 Swarm 状态 `GET /api/swarm/{swarmId}` | 返回每个 Worker 的 `toolCallCount` 和 `tokenConsumed` 数据 | — |

**通过标准**: Worker 级别资源消耗（工具调用数、token 消耗）可追踪并展示

---

#### TC-APOS2-018: 费用重置操作

**优先级**: P2
**前置条件**:
- 当前会话有费用数据

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 切换到新会话 | 新会话创建成功 | — |
| 2 | 检查费用数据 | `sessionCost` 重置为 0，`usage` 中所有 token 计数归零，`totalCost` 保持累计值不变 | — |

**通过标准**: 会话切换时 `sessionCost` 和 `usage` 正确重置，`totalCost` 保持累计

---

### E. 异常检测引擎（P0）

---

#### TC-APOS2-019: AnomalyAlertPanel 空状态展示

**优先级**: P0
**前置条件**:
- Feature Flag `APOS_ANOMALY_ALERT=true`（默认），且 `APOS_AGENT_PIPELINE=true`
- 无活跃异常事件

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 展开 "Agent Pipeline" 区域 | AnomalyAlertPanel 嵌套在 AgentPipelineView 下方 | ![告警空状态](APOS2-TC019-01-empty.png) |
| 2 | 检查告警面板 | 标题 "Anomaly Alerts"（无计数徽章）；空状态显示 ✅ 图标 + "运行正常" + "No anomalies detected" | — |

**通过标准**: 无异常时显示正确的空状态，标题无计数徽章

---

#### TC-APOS2-020: 循环检测规则触发（loop_detection）

**优先级**: P0
**前置条件**:
- 有活跃 Swarm，Worker 正在工作
- Worker 最近 10 次工具调用中有 50%+ 使用相同的 toolName + paramsHash

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 等待 Worker 产生重复工具调用模式 | AnomalyDetectionEngine 检测到 `uniqueKeys.size < recent.length * 0.5` | — |
| 2 | 检查 AnomalyAlertPanel | 标题旁出现红色计数徽章（`bg-red-500 text-white`），显示活跃异常数量 | ![循环检测](APOS2-TC020-01-loop.png) |
| 3 | 检查异常卡片 | 图标 🔄（`loop_detection`），severity 为 `error`（黄色底），Worker 名称可见，消息包含 "检测到重复调用模式（最近10次中50%+参数相同）" | ![卡片详情](APOS2-TC020-02-card.png) |
| 4 | 检查 severity 标签 | 显示 "ERROR" 文字（`anomaly.severity.toUpperCase()`），黄色背景（`bg-yellow-100 text-yellow-700`） | — |

**通过标准**: 循环检测正确触发，图标、severity、消息与 AnomalyDetectionEngine 代码一致

---

#### TC-APOS2-021: 卡死检测规则触发（stall_detection）

**优先级**: P0
**前置条件**:
- 有活跃 Swarm，Worker 超过 60 秒无任何工具调用

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 等待 Worker 超过 60 秒无活动 | AnomalyDetectionEngine 检测到 `(now - lastRecord.timestamp) > 60_000` | — |
| 2 | 检查异常卡片 | 图标 ⏳（`stall_detection`），severity 为 `critical`（红色底），消息包含 "超过60s无任何工具调用活动" | ![卡死检测](APOS2-TC021-01-stall.png) |
| 3 | 检查 severity 标签 | 显示 "CRITICAL" 文字，红色背景（`bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300`） | — |
| 4 | 检查卡片背景 | critical severity → `bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800` | — |

**通过标准**: 卡死检测正确触发，critical 级别颜色和消息与代码一致

---

#### TC-APOS2-022: 连续失败检测规则触发（error_cascade）

**优先级**: P0
**前置条件**:
- 有活跃 Swarm，Worker 最近 5 次工具调用中有 3 次以上状态为 `error`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 等待 Worker 产生连续错误 | AnomalyDetectionEngine 检测到 `recent.slice(-5)` 中 `status === 'error'` 的数量 >= 3 | — |
| 2 | 检查异常卡片 | 图标 ❌（`error_cascade`），severity 为 `error`，消息包含 "连续失败（最近5次中N次错误）" | ![连续失败](APOS2-TC022-01-cascade.png) |

**通过标准**: 连续失败检测正确触发，错误计数和消息与代码一致

---

#### TC-APOS2-023: 异常告警 - 中止 Worker 操作

**优先级**: P0
**前置条件**:
- AnomalyAlertPanel 中有至少一个活跃异常

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 点击异常卡片中的 "中止 Worker" 按钮（红色） | 按钮文字变为 "中止中..."（`disabled:opacity-50`），发送 POST 请求到 `/api/swarm/{swarmId}/worker/{workerId}/abort` | ![中止中](APOS2-TC023-01-aborting.png) |
| 2 | 等待请求完成 | 该异常从 `activeAnomalies` 中移除，移入 `resolvedHistory`；异常卡片消失 | — |
| 3 | 验证后端状态 | Worker 状态变为 TERMINATED，`anomaly_events` 表中记录该事件（`rule_id='worker_abort'`） | — |
| 4 | 验证中止请求体 | 请求体包含 `{ "reason": "user_abort", "triggeredBy": "anomaly_alert" }` | — |

**通过标准**: 中止操作正确发送 API 请求，前端状态更新，后端持久化事件

---

#### TC-APOS2-024: 异常告警 - 忽略操作和冷却期

**优先级**: P0
**前置条件**:
- AnomalyAlertPanel 中有活跃异常

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 点击异常卡片中的 "忽略" 按钮（灰色） | 该异常从 `activeAnomalies` 中移除，resolution 为 `'dismiss'`，异常卡片消失 | ![忽略操作](APOS2-TC024-01-dismiss.png) |
| 2 | 验证冷却期机制 | 30 秒内同一 `workerId:ruleId` 组合的异常不会重复触发（`isInCooldown` 返回 true） | — |
| 3 | 等待 30 秒后验证 | 冷却期结束后，若条件仍满足则异常重新触发 | — |

**通过标准**: 忽略操作正确移除异常，30 秒冷却期机制有效

---

### F. 推送通知集成（P1）

---

#### TC-APOS2-025: NotificationService 初始化与权限请求

**优先级**: P1
**前置条件**:
- 浏览器支持 Notification API

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 打开页面，检查控制台日志 | `notificationService.init()` 正确检测 `'Notification' in window` 和当前权限状态 | — |
| 2 | 触发权限请求（如点击通知开关或首次异常） | 浏览器弹出 "允许通知" 对话框（仅在 `permissionState === 'default'` 时） | ![权限请求](APOS2-TC025-01-permission.png) |
| 3 | 允许通知权限 | `canNotify` 返回 `true` | — |
| 4 | 拒绝通知权限 | `canNotify` 返回 `false`，后续通知降级为 Toast 消息 | — |

**通过标准**: 权限状态正确管理，允许后可发送原生通知，拒绝后降级 Toast

---

#### TC-APOS2-026: 异常检测触发推送通知

**优先级**: P1
**前置条件**:
- 通知权限已授予（`granted`）
- 有活跃 Swarm 产生异常

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 触发 critical/error 级别的异常 | 浏览器弹出原生通知 | ![推送通知](APOS2-TC026-01-notification.png) |
| 2 | 检查通知标题 | 格式为 `[CRITICAL] 卡死检测` 或 `[ERROR] 循环检测`（ruleNameMap 映射：`loop_detection` → "循环检测"、`stall_detection` → "卡死检测"、`error_cascade` → "连续失败"） | — |
| 3 | 检查通知正文 | 格式为 `{workerName}: {message}` | — |
| 4 | 检查通知 tag | 格式为 `anomaly-{workerId}-{ruleId}`（同 tag 通知合并） | — |
| 5 | 点击通知 | 浏览器窗口获取焦点（`window.focus()`），通知关闭 | — |

**通过标准**: critical/error 级别异常正确触发浏览器原生通知，标题/正文/tag 与代码一致

---

#### TC-APOS2-027: 验证结果触发推送通知

**优先级**: P1
**前置条件**:
- 通知权限已授予
- 验证服务产生 blocked 或 manual_required 信号

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 代码变更触发验证检查（TypeScript/ESLint/Vitest） | 验证完成后前端收到 `verification_result` WebSocket 消息 | — |
| 2 | 若信号为 `blocked` | 推送通知标题 "[验证] 阻塞"，正文为 `signalReason` | ![阻塞通知](APOS2-TC027-01-blocked.png) |
| 3 | 若信号为 `manual_required` | 推送通知标题 "[验证] 需人工审查"，正文为 `signalReason` | — |
| 4 | 若信号为 `auto_approve` 或 `review_recommended` | 不触发推送通知 | — |

**通过标准**: 仅 blocked/manual_required 信号触发推送通知，其他信号不触发

---

#### TC-APOS2-028: 推送通知 Toast 降级

**优先级**: P2
**前置条件**:
- 通知权限被拒绝（`denied`）或浏览器不支持 Notification API

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 触发异常推送通知 | 不弹出原生通知，降级为应用内 Toast 消息 | ![Toast降级](APOS2-TC028-01-toast.png) |
| 2 | 检查 Toast 消息内容 | 消息格式 `{title}: {body}`，级别为 `warning`，自动消失时间 8000ms（`timeout: 8000`） | — |
| 3 | 检查 Toast 消息 key | 格式为 `push-{timestamp}` | — |

**通过标准**: 通知不可用时正确降级为 Toast 消息

---

### G. 手机端响应式布局（P1）

---

#### TC-APOS2-029: 响应式断点检测

**优先级**: P1
**前置条件**:
- 使用 Playwright 或浏览器 DevTools 模拟不同屏幕宽度

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 设置视口宽度为 1200px | `isDesktop=true`，`isTablet=false`，`isMobile=false` | — |
| 2 | 设置视口宽度为 900px | `isDesktop=false`，`isTablet=true`，`isMobile=false` | — |
| 3 | 设置视口宽度为 600px | `isDesktop=false`，`isTablet=false`，`isMobile=true`（`max-width: 767px`） | — |
| 4 | 设置视口宽度为 767px（临界值） | `isMobile=true`（767px <= 767px） | — |
| 5 | 设置视口宽度为 768px（临界值） | `isMobile=false`，`isTablet=true`（768px >= 768px） | — |

**通过标准**: 三个断点在临界值处正确切换

---

#### TC-APOS2-030: MobileStatusBar 底部固定展示

**优先级**: P1
**前置条件**:
- Feature Flag `APOS_MOBILE_STATUS=true`（默认）
- 视口宽度 ≤ 767px（mobile 模式）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 在手机模式下查看页面底部 | 底部固定状态条可见（`fixed bottom-0 left-0 right-0 z-50`），背景 `bg-[#12121a]/95 backdrop-blur-sm` | ![底部状态条](APOS2-TC030-01-statusbar.png) |
| 2 | 无活跃 Swarm 时检查左侧 | 显示 "无活动 Pipeline" 文字 | — |
| 3 | 有活跃 Swarm 时检查左侧 | 显示 `MobilePipelineSummary` 组件（Worker 状态圆点 + "N/M 完成" 文字） | ![Pipeline摘要](APOS2-TC030-02-summary.png) |
| 4 | 检查右侧异常计数 | 有异常时显示红色徽章（`bg-red-500/20 text-red-300`）+ AlertTriangle 图标 + 数量 | — |
| 5 | 检查展开箭头 | 右侧显示 `ChevronUp` 图标（16px） | — |

**通过标准**: MobileStatusBar 固定在底部，正确展示 Pipeline 摘要和异常计数

---

#### TC-APOS2-031: MobilePipelineSummary Worker 状态圆点

**优先级**: P1
**前置条件**:
- 有活跃 Swarm 且 Workers 处于不同状态

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 查看 MobilePipelineSummary | 每个 Worker 显示为一个 `w-2.5 h-2.5` 圆点，间距 `gap-1.5` | ![状态圆点](APOS2-TC031-01-dots.png) |
| 2 | 检查 STARTING Worker | 灰色圆点（`bg-gray-400`） | — |
| 3 | 检查 WORKING Worker | 蓝色圆点 + 脉冲动画（`bg-blue-500 animate-pulse`） | — |
| 4 | 检查 IDLE Worker | 黄色圆点（`bg-yellow-400`） | — |
| 5 | 检查 TERMINATED(completed) Worker | 绿色圆点（`bg-green-500`） | — |
| 6 | 检查 TERMINATED(error) Worker | 红色圆点（`bg-red-500`） | — |
| 7 | 检查进度文本 | 圆点右侧显示 "N/M 完成" 文字（completed 数 / 总 worker 数） | — |

**通过标准**: Worker 状态圆点颜色映射正确，WORKING 有脉冲动画

---

#### TC-APOS2-032: MobileStatusBar 展开高风险文件列表

**优先级**: P1
**前置条件**:
- 有变更影响数据

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 点击底部状态条 | 展开面板弹出（`fixed bottom-[44px] left-0 right-0 z-40`），显示 "高风险文件" 标题 | ![展开面板](APOS2-TC032-01-expanded.png) |
| 2 | 检查展开面板内容 | `MobileImpactList` 组件，显示 Top 5 高风险文件（按 riskLevel 降序 → touchCount 降序排列） | — |
| 3 | 检查文件列表项 | 每项包含风险色标（圆点）、文件名（仅最后一级）、touchCount（×N） | — |
| 4 | 检查风险色标 | danger → `bg-red-500`、warning → `bg-yellow-500`、review → `bg-blue-400`、safe → `bg-green-500` | — |
| 5 | 有超过 5 个文件时检查 | 底部显示 "查看全部 (N)" 按钮 | — |
| 6 | 再次点击状态条 | 展开面板收起 | — |

**通过标准**: 展开/收起交互正确，Top 5 文件排序和风险色标与代码一致

---

#### TC-APOS2-033: MobileImpactList 空状态

**优先级**: P2
**前置条件**:
- 无变更影响数据

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 展开 MobileStatusBar | 文件列表区域显示 "暂无变更影响数据"（居中灰色文字，`min-h-[44px]`） | ![空列表](APOS2-TC033-01-empty.png) |

**通过标准**: 无数据时展示正确的空状态

---

### H. MobileBottomSheet（P1）

---

#### TC-APOS2-034: MobileBottomSheet 打开和关闭

**优先级**: P1
**前置条件**:
- 视口宽度 ≤ 767px（mobile 模式）
- 有 Activity 数据

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 点击 Activity 卡片 | MobileBottomSheet 从底部滑入（`y: '100%' → 0`，spring 动画：`damping: 25, stiffness: 300`），覆盖层 `bg-black/60` | ![打开动画](APOS2-TC034-01-open.png) |
| 2 | 检查拖拽手柄 | 顶部显示居中圆角手柄条（`w-10 h-1 rounded-full bg-gray-500`） | — |
| 3 | 检查 Header | 显示 activity.summary 标题 + SignalBadge + 关闭按钮（X 图标，`aria-label="关闭"`） | — |
| 4 | 点击关闭按钮 | Sheet 向下滑出关闭，`body` 恢复滚动 | — |
| 5 | 按 ESC 键 | Sheet 关闭 | — |
| 6 | 点击遮罩层 | Sheet 关闭 | — |

**通过标准**: MobileBottomSheet 打开/关闭动画流畅，三种关闭方式（按钮/ESC/遮罩）均有效

---

#### TC-APOS2-035: MobileBottomSheet 下拉关闭

**优先级**: P1
**前置条件**:
- MobileBottomSheet 已打开

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 向下拖拽 Sheet 面板（触摸或鼠标） | 面板跟随手指/鼠标移动 | — |
| 2 | 拖拽距离超过 100px（`DRAG_CLOSE_THRESHOLD = 100`） | 松手后 Sheet 自动滑出关闭 | ![下拉关闭](APOS2-TC035-01-drag.png) |
| 3 | 拖拽距离不足 100px | 松手后 Sheet 弹回原位（`dragElastic: { top: 0, bottom: 0.6 }`） | — |

**通过标准**: 下拉距离 > 100px 触发关闭，< 100px 回弹

---

#### TC-APOS2-036: MobileBottomSheet 内容展示

**优先级**: P1
**前置条件**:
- MobileBottomSheet 已打开，Activity 有 assessment 数据

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 检查 "确定性验证" 区域 | 标题 "确定性验证"（大写），3 列网格显示 tsc / eslint / test 状态（VerificationIcon + 结果文字） | ![确定性验证](APOS2-TC036-01-deterministic.png) |
| 2 | 检查 "启发式分析" 区域 | 标题 "启发式分析"（大写），显示 "影响 API: N" + "间接文件: N" + "置信度: 高/低" | — |
| 3 | 检查 "受影响文件" 区域 | 标题 "受影响文件"（大写），最多显示前 5 个文件路径，文件类型徽章颜色（direct=红、indirect=黄、potential=蓝） | — |
| 4 | 超过 5 个文件时 | 底部显示 "+N 个文件..." 提示 | — |

**通过标准**: Sheet 内容三个区域（确定性验证、启发式分析、受影响文件）正确展示

---

#### TC-APOS2-037: MobileBottomSheet 操作按钮状态

**优先级**: P1
**前置条件**:
- MobileBottomSheet 已打开

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 对未决策的 Activity 查看 Footer | 显示 "批准"（绿色）+ "拒绝"（红色）+ "详情"（灰色，右对齐） | ![未决策](APOS2-TC037-01-pending.png) |
| 2 | 对 auto_approve 信号的 Activity 查看 Footer | 显示 "已自动放行"（灰色带 Check 图标），无批准/拒绝按钮 | — |
| 3 | 对已批准的 Activity 查看 Footer | 显示 "已批准 ✓"（绿色半透明，`bg-green-600/10 text-green-400/70`） | — |
| 4 | 对已拒绝的 Activity 查看 Footer | 显示 "已拒绝 ✗"（红色半透明，`bg-red-600/10 text-red-400/70`） | — |
| 5 | 点击 "批准" 按钮 | Activity decision 变为 `approved`，按钮状态更新为 "已批准 ✓" | — |
| 6 | 点击 "详情" 按钮 | 触发 `onViewDetails` 回调 | — |

**通过标准**: 四种决策状态（未决策/auto_approve/approved/rejected）按钮显示正确

---

### I. Feature Flag 控制（P0）

---

#### TC-APOS2-038: Phase 2 Feature Flag 面板展示

**优先级**: P0
**前置条件**:
- Activity Tab 已打开

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 滚动到 Feature Flags 面板 | 面板可见，Header 显示 Settings2 图标 + "Feature Flags" 标题 + "重置" 按钮 | ![Feature Flag面板](APOS2-TC038-01-panel.png) |
| 2 | 检查 Phase 2 新增 Flag 列表 | 完整列出 8 个 Flag：Activity Stream、AI Insight、Batch Review、Risk Heatmap、**Change Impact**、**Agent Pipeline**、**Anomaly Alert**、**Mobile Status** | ![Flag列表](APOS2-TC038-02-list.png) |
| 3 | 检查默认状态 | Change Impact=true（开）、Agent Pipeline=true（开）、Anomaly Alert=true（开）、Mobile Status=true（开）、Risk Heatmap=false（关） | — |
| 4 | 检查 Flag 描述 | Change Impact："变更影响全景面板（需先启用活动流）"、Agent Pipeline："Agent Pipeline 多 Worker 可视化（需先启用活动流）"、Anomaly Alert："异常告警面板（需先启用 Agent Pipeline）"、Mobile Status："移动端底部状态栏（需先启用活动流）" | — |

**通过标准**: 8 个 Feature Flag 名称、描述、默认值与 `FeatureFlagPanel.tsx` 和 `APOS_FLAG_DEFAULTS` 一致

---

#### TC-APOS2-039: Feature Flag 依赖级联关闭

**优先级**: P0
**前置条件**:
- 所有 Flag 处于默认状态

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 关闭 "Agent Pipeline" Flag | Agent Pipeline toggle 变灰 | ![关闭Pipeline](APOS2-TC039-01-disable-pipeline.png) |
| 2 | 检查 "Anomaly Alert" Flag | Anomaly Alert 自动被禁用（`opacity-50`），toggle 不可点击（`disabled`），显示 "需要先启用: APOS_AGENT_PIPELINE"（amber 色） | ![级联禁用](APOS2-TC039-02-cascade.png) |
| 3 | 关闭 "Activity Stream" Flag | Activity Stream toggle 变灰 | — |
| 4 | 检查所有依赖 Flag | AI Insight、Batch Review、Change Impact、Agent Pipeline、Mobile Status 全部被禁用并显示 "需要先启用: APOS_ACTIVITY_STREAM" | ![全部禁用](APOS2-TC039-03-all-disabled.png) |
| 5 | Anomaly Alert | 同时显示 "需要先启用: APOS_AGENT_PIPELINE"（因 Agent Pipeline 也被禁用） | — |

**通过标准**: 依赖级联关闭逻辑与 `APOS_FLAG_DEPENDENCIES` 定义一致

---

#### TC-APOS2-040: Feature Flag 控制 ChangeImpactPanel 显示/隐藏

**优先级**: P0
**前置条件**:
- 初始状态 `APOS_CHANGE_IMPACT=true`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 在 ActivityStream 中检查 | "变更影响全景" 折叠区域可见（`<details>` 元素） | ![变更影响可见](APOS2-TC040-01-visible.png) |
| 2 | 关闭 "Change Impact" Flag | "变更影响全景" 折叠区域消失 | ![变更影响隐藏](APOS2-TC040-02-hidden.png) |
| 3 | 重新开启 "Change Impact" Flag | "变更影响全景" 折叠区域重新显示 | — |

**通过标准**: `APOS_CHANGE_IMPACT` Flag 控制 ChangeImpactPanel 的显示/隐藏

---

#### TC-APOS2-041: Feature Flag 控制 Pipeline + Anomaly 联动

**优先级**: P0
**前置条件**:
- `APOS_AGENT_PIPELINE=true`，`APOS_ANOMALY_ALERT=true`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 在 ActivityStream 中检查 | "Agent Pipeline" 折叠区域可见，其内部包含 AgentPipelineView + AnomalyAlertPanel | ![Pipeline+Anomaly](APOS2-TC041-01-both.png) |
| 2 | 关闭 "Anomaly Alert" Flag（保持 Agent Pipeline 开启） | "Agent Pipeline" 折叠区域仍可见，但内部仅显示 AgentPipelineView，AnomalyAlertPanel 消失 | ![仅Pipeline](APOS2-TC041-02-pipeline-only.png) |
| 3 | 关闭 "Agent Pipeline" Flag | 整个 "Agent Pipeline" 折叠区域消失 | — |

**通过标准**: Pipeline 和 Anomaly 的显示由各自 Flag 独立控制，Anomaly 嵌套在 Pipeline 内部

---

#### TC-APOS2-042: Feature Flag 重置功能

**优先级**: P1
**前置条件**:
- 已修改部分 Flag 状态

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 关闭多个 Flag（如 Change Impact、Agent Pipeline） | Flag 状态已改变 | — |
| 2 | 点击 "重置" 按钮 | 所有 Flag 恢复到 `APOS_FLAG_DEFAULTS` 默认值（ACTIVITY_STREAM=true, AI_INSIGHT=true, BATCH_REVIEW=true, RISK_HEATMAP=false, CHANGE_IMPACT=true, AGENT_PIPELINE=true, ANOMALY_ALERT=true, MOBILE_STATUS=true） | ![重置后](APOS2-TC042-01-reset.png) |

**通过标准**: 重置按钮正确恢复所有 Flag 到默认值

---

### J. Phase 1 功能回归（P0）

---

#### TC-APOS2-043: Phase 1 Activity 三层展示回归

**优先级**: P0
**前置条件**:
- 通过真实工具调用产生 Activity 数据
- 所有 Phase 2 Feature Flag 保持默认值

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 切换到 Activity Tab | ActivityStream 正常渲染，Activity 列表可见 | ![Activity回归](APOS2-TC043-01-list.png) |
| 2 | 检查 L1 卡片 | 操作图标、摘要文本、文件数/耗时、SignalBadge、时间戳均正常显示 | — |
| 3 | 点击 L1 卡片展开至 L2 | 展开动画流畅，显示文件列表和操作按钮（批准/拒绝/详情） | — |
| 4 | 点击 "详情 →" 打开 L3 Portal | 全屏浮层正确渲染，ESC 可关闭 | — |
| 5 | Phase 2 新增面板不干扰 Phase 1 功能 | ChangeImpactPanel 和 AgentPipelineView 折叠区域在 Activity 列表上方，不影响列表滚动和操作 | — |

**通过标准**: Phase 1 三层展示功能在 Phase 2 新增组件下正常工作

---

#### TC-APOS2-044: Phase 1 信号筛选回归

**优先级**: P0
**前置条件**:
- Activity 数据包含不同 signal 类型

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 检查筛选栏 | 显示 5 个筛选按钮：全部、可放行、建议审查、需手动、已阻止 | ![筛选栏](APOS2-TC044-01-filter.png) |
| 2 | 点击 "建议审查" 筛选 | 仅显示 signal=review_recommended 的 Activity，按钮高亮 | — |
| 3 | 点击 "全部" | 恢复显示所有 Activity | — |

**通过标准**: Phase 1 信号筛选功能不受 Phase 2 影响

---

#### TC-APOS2-045: Phase 1 批量操作回归

**优先级**: P1
**前置条件**:
- Feature Flag `APOS_BATCH_REVIEW=true`
- 有多条 Activity 数据

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 进入批量模式 | BatchOperationBar 出现在筛选栏下方 | — |
| 2 | 选择多条 Activity | 被选中的 Activity 显示 checkbox 勾选状态 | — |
| 3 | 批量批准 | 选中的 Activity 全部变为 approved 状态 | — |

**通过标准**: 批量操作功能正常

---

#### TC-APOS2-046: Phase 1 确定性验证回归

**优先级**: P0
**前置条件**:
- 通过真实文件编辑产生 Activity

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 观察 Activity 的 Signal | 验证 Signal 计算正确：TypeScript 错误 → blocked、ESLint 错误 → blocked、测试失败 → blocked、有 API 影响 → review_recommended、全通过且影响小 → auto_approve | ![Signal验证](APOS2-TC046-01-signal.png) |
| 2 | 检查 verify_progress WebSocket 消息 | 前端收到逐文件的验证进度推送，`insightStore.verifyProgress` 状态更新 | — |
| 3 | 检查 verification_result WebSocket 消息 | 最终验证结果包含 `signal`、`signalReason`、`overallStatus`、`duration`、`fileCount`、`timestamp` | — |

**通过标准**: Phase 2 验证服务与 Phase 1 信号体系兼容，verify_progress 和 verification_result 推送正常

---

### K. 三端集成验证（P0）

---

#### TC-APOS2-047: WebSocket verify_progress 逐文件推送

**优先级**: P0
**前置条件**:
- 三端服务运行中
- 执行代码变更触发验证

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 发送编辑请求产生文件变更 | 后端 `VerifyCheckService.executeChecks()` 开始执行 | — |
| 2 | 监听 WebSocket 消息 | 收到 `verify_progress` 类型消息，payload 包含 `filePath`、`completed`（当前序号）、`total`（总文件数）、`result`（单文件检查结果） | ![verify进度](APOS2-TC047-01-progress.png) |
| 3 | 验证推送顺序 | 文件按顺序逐个推送，`completed` 从 1 递增到 `total` | — |
| 4 | 验证前端状态更新 | `insightStore.verifyProgress` Map 中对应文件状态更新为 `'running'` → `'completed'` 或 `'failed'` | — |

**通过标准**: 后端逐文件推送验证进度，前端实时接收并更新状态

---

#### TC-APOS2-048: 后端 Swarm Worker 中止 API 集成

**优先级**: P0
**前置条件**:
- 有活跃 Swarm 和 Worker
- `ENABLE_AGENT_SWARMS=true`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 执行 `curl -X POST http://localhost:8080/api/swarm/{swarmId}/worker/{workerId}/abort -H 'Content-Type: application/json' -d '{"reason":"user_abort","triggeredBy":"anomaly_alert"}'` | 返回 200 OK，body 包含 `workerId`、`status: "aborted"`、`message` | — |
| 2 | 验证 Worker 状态 | 通过 `GET /api/swarm/{swarmId}` 确认该 Worker 状态为 `TERMINATED` | — |
| 3 | 验证异常事件持久化 | 数据库 `anomaly_events` 表中新增一条记录：`rule_id='worker_abort'`、`severity='high'`、`message` 包含原因 | — |
| 4 | Swarm 不存在时 | 返回 404 Not Found | — |
| 5 | Worker 不存在时 | 返回 404 Not Found | — |

**通过标准**: Worker 中止 API 正确执行，状态变更、事件持久化均验证通过

---

#### TC-APOS2-049: 后端 Feature Flag 前置检查

**优先级**: P0
**前置条件**:
- 后端 `ENABLE_AGENT_SWARMS=false`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 执行 `curl http://localhost:8080/api/swarm` | 返回 403 Forbidden，body 包含 `{"error": "Agent Swarms feature is disabled"}` | — |
| 2 | 执行 `curl -X POST http://localhost:8080/api/swarm -H 'Content-Type: application/json' -d '{"teamName":"test"}'` | 返回 403 Forbidden | — |
| 3 | 执行 `curl -X POST http://localhost:8080/api/swarm/xxx/worker/yyy/abort -H 'Content-Type: application/json' -d '{"reason":"test"}'` | 返回 403 Forbidden | — |

**通过标准**: `ENABLE_AGENT_SWARMS=false` 时所有 Swarm API 端点返回 403

---

#### TC-APOS2-050: WorkerStateTracker 工具调用历史和重复检测

**优先级**: P0
**前置条件**:
- 有活跃 Swarm Worker 正在执行任务

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | Worker 执行工具调用 | `WorkerStateTracker.recordToolCall()` 记录到 Deque | — |
| 2 | 查询最近调用 `getRecentToolCalls(workerId)` | 返回不可变列表，最多 10 条（`MAX_RECENT_TOOL_CALLS = 10`），超过时自动移除最旧记录 | — |
| 3 | 验证重复检测 `detectRepetition(workerId, threshold)` | 相同 `toolName:paramsHash` 出现次数 >= threshold 时返回 `true` | — |
| 4 | Worker 终止后 `clearWorker(workerId)` | 该 Worker 的追踪数据完全清除 | — |

**通过标准**: WorkerStateTracker 正确维护最近 10 条记录，重复检测和清除逻辑正确

---

## 3 附录

### 3.1 异常检测规则参考表

| 规则 | ruleId | 检测条件 | severity | 消息模板 |
|------|--------|----------|----------|----------|
| 循环检测 | `loop_detection` | 最近 10 次调用中 `uniqueKeys.size < recent.length * 0.5`，最少 4 条记录 | error | "Worker {workerId} 检测到重复调用模式（最近10次中50%+参数相同）" |
| 卡死检测 | `stall_detection` | `(now - lastRecord.timestamp) > 60_000`，至少有 1 条记录 | critical | "Worker {workerId} 超过60s无任何工具调用活动" |
| 连续失败 | `error_cascade` | 最近 5 次中 `status === 'error'` 的数量 >= 3，最少 3 条记录 | error | "Worker {workerId} 连续失败（最近5次中N次错误）" |

### 3.2 Signal 计算规则（前后端一致）

| 优先级 | 条件 | Signal | Reason |
|--------|------|--------|--------|
| 1 | TypeScript errorCount > 0 | blocked | TypeScript 编译错误 |
| 2 | ESLint errorCount > 0 | blocked | ESLint 错误 |
| 3 | Vitest failedCount > 0 | blocked | 测试失败 |
| 4 | heuristic.truncated = true | blocked | 启发式分析不可用 |
| 5 | affectedApiCount > 0 | review_recommended | 影响 API 接口 |
| 6 | indirectImpactCount > 3 | review_recommended | 间接影响范围大 |
| 7 | 无匹配测试（no_tests） | review_recommended | 缺少测试覆盖 |
| 8 | ESLint warningCount > 0 | review_recommended | 存在 lint 警告 |
| 9 | 全通过 + indirectImpact ≤ 2 + affectedApi = 0 | auto_approve | 全部验证通过，影响范围小 |
| 10 | 其他情况 | manual_required | 需要人工审查 |

### 3.3 Feature Flag 依赖关系图

```
APOS_ACTIVITY_STREAM (根)
├── APOS_AI_INSIGHT
│   └── APOS_RISK_HEATMAP
├── APOS_BATCH_REVIEW
├── APOS_CHANGE_IMPACT
├── APOS_AGENT_PIPELINE
│   └── APOS_ANOMALY_ALERT
└── APOS_MOBILE_STATUS
```

### 3.4 后端 API 端点清单

| 方法 | 路径 | 功能 | 前置检查 |
|------|------|------|----------|
| POST | `/api/swarm` | 创建 Swarm | ENABLE_AGENT_SWARMS |
| GET | `/api/swarm` | 列出活跃 Swarm | ENABLE_AGENT_SWARMS |
| GET | `/api/swarm/{swarmId}` | 获取 Swarm 状态 | ENABLE_AGENT_SWARMS |
| POST | `/api/swarm/{swarmId}/shutdown` | 关闭 Swarm | ENABLE_AGENT_SWARMS |
| POST | `/api/swarm/{swarmId}/force-stop` | 强制停止 Swarm | ENABLE_AGENT_SWARMS |
| POST | `/api/swarm/{swarmId}/worker/{workerId}/abort` | 中止 Worker | ENABLE_AGENT_SWARMS |
| POST | `/api/swarm/permission/{requestId}` | 处理权限冒泡 | ENABLE_AGENT_SWARMS |

### 3.5 WebSocket 消息类型

| 消息类型 | 方向 | 用途 |
|----------|------|------|
| `verify_progress` | Backend → Frontend | 逐文件验证进度推送 |
| `verification_result` | Backend → Frontend | 最终验证结果推送 |
| `swarm_state_update` | Backend → Frontend | Swarm 状态变更推送 |
| `worker_progress` | Backend → Frontend | Worker 进度更新推送 |
| `permission_bubble` | Backend → Frontend | Worker 权限冒泡请求 |
| `cost_update` | Backend → Frontend | 费用/Token 消耗推送 |
