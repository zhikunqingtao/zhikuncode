# APOS Phase 1 端到端测试用例

> **版本**: Phase 1 | **设计日期**: 2026-05-11 | **测试范围**: APOS Activity Stream 全栈功能验证
> **前提条件**: 三端启动（Backend:8080 / Python:8000 / Frontend:5173），Feature Flag `VITE_APOS_ACTIVITY_STREAM=true`
> **Mock 模式**: `VITE_APOS_USE_MOCK=false`（默认关闭），可通过 Feature Flag Panel 手动启用，启用后加载 6 条 Mock Activity 数据用于开发调试

---

## 0 测试用例总览

| 模块 | 用例数 | 优先级 | 编号范围 |
|------|--------|--------|----------|
| A. APOS Tab 基础功能 | 3 | P0 | TC-APOS-001 ~ 003 |
| B. ActivityCard 三层展示 | 4 | P0 | TC-APOS-004 ~ 007 |
| C. SignalBadge 信号系统 | 2 | P0 | TC-APOS-008 ~ 009 |
| D. 信号筛选 | 2 | P1 | TC-APOS-010 ~ 011 |
| E. 批量操作 | 4 | P1 | TC-APOS-012 ~ 015 |
| F. Feature Flag 面板 | 3 | P1 | TC-APOS-016 ~ 018 |
| G. 手机端适配 | 2 | P1 | TC-APOS-019 ~ 020 |
| H. 后端 API | 2 | P0 | TC-APOS-021 ~ 022 |
| I. 数据流转集成 | 2 | P0 | TC-APOS-023 ~ 024 |
| J. 异常场景 | 2 | P2 | TC-APOS-025 ~ 026 |
| K~X. 补充测试用例 | 15 | P0~P2 | TC-APOS-027 ~ 041 |
| Y~Z. APOS 运行时修复验证 | 3 | P0~P1 | TC-APOS-042 ~ 044 |
| AA. Activity 后端持久化 | 8 | P0~P1 | TC-APOS-045 ~ 052 |
| **合计** | **52** | | |

---

## 1 测试环境

| 项目 | 详情 |
|------|------|
| **操作系统** | macOS Darwin 26.4.1 (Apple Silicon) |
| **Node.js** | v22.14.0 + Vite 开发服务器 (5173) |
| **JDK** | Amazon Corretto 21 |
| **浏览器** | Chromium (Playwright) |
| **Frontend** | React + TypeScript + Zustand + Framer Motion |
| **WebSocket** | STOMP 1.2 over SockJS |
| **测试框架** | Playwright (E2E) + curl (REST API) + Node.js ws (WebSocket) |

**APOS Feature Flags（`.env.development` 默认值）：**

| Flag | 默认值 | 说明 |
|------|--------|------|
| `VITE_APOS_ACTIVITY_STREAM` | `true` | 主面板开关 |
| `VITE_APOS_AI_INSIGHT` | `true` | AI 洞察分析（依赖 ACTIVITY_STREAM） |
| `VITE_APOS_BATCH_REVIEW` | `true` | 批量审查（依赖 ACTIVITY_STREAM） |
| `VITE_APOS_RISK_HEATMAP` | `false` | 风险热力图（依赖 AI_INSIGHT） |
| `VITE_APOS_USE_MOCK` | `false` | Mock 数据模式（可选调试工具，默认关闭） |

**Mock 数据集（`mockActivities.ts` 共 6 条）：**

| ID | operationType | summary | signal | verificationStatus |
|----|---------------|---------|--------|--------------------|
| mock-activity-001 | file_edit | 修复 typo in README.md | auto_approve | all_pass |
| mock-activity-002 | refactor | 重构 UserService 认证逻辑 | review_recommended | has_warning |
| mock-activity-003 | command_execute | 数据库迁移脚本 v2.3 | manual_required | pending |
| mock-activity-004 | delete | 删除 AuthController 核心模块 | blocked | has_error |
| mock-activity-005 | dependency | 更新 package.json 依赖版本 | auto_approve | all_pass |
| mock-activity-006 | config_change | 新增 WebSocket verify_progress 消息类型 | review_recommended | all_pass |

---

## 2 测试用例详细设计

### A. APOS Tab 基础功能（P0）

---

#### TC-APOS-001: APOS Activity Tab 在 Sidebar 中正确显示

**优先级**: P0
**前置条件**:
- 三端服务启动完成
- `.env.development` 中 `VITE_APOS_ACTIVITY_STREAM=true`
- 浏览器访问 `http://localhost:5173`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 打开浏览器访问 `http://localhost:5173`，等待页面加载完成 | 页面正常渲染，左侧 Sidebar 可见 | ![初始页面](APOS-TC001-01-sidebar-apos-tab.png) |
| 2 | 观察 Sidebar Tab 导航栏 | 在导航栏末尾出现 "Activity" Tab（使用 Activity 图标），Tab 列表为：会话、任务、文件、序列图、DAG、Git、复杂度、影响分析、API文档、图表生成、代码路径、**Activity** | ![Tab列表](APOS-TC001-02-tab-list.png) |
| 3 | 点击 "Activity" Tab | Tab 高亮（蓝色底部边框），内容区切换为 ActivityStream 面板 | ![Activity面板](APOS-TC001-03-activity-panel.png) |

**通过标准**: Activity Tab 可见、可点击、切换后内容区显示 ActivityStream 组件

---

#### TC-APOS-002: Feature Flag 控制 APOS Tab 显示/隐藏

**优先级**: P0
**前置条件**:
- 三端服务启动完成
- 初始状态 `VITE_APOS_ACTIVITY_STREAM=true`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 访问 `http://localhost:5173`，确认 Activity Tab 可见 | Sidebar Tab 栏中存在 Activity Tab | ![Flag开启](APOS-TC002-01-flag-enabled.png) |
| 2 | 切换到 Activity Tab，找到底部 Feature Flags 面板 | Feature Flags 面板可见，显示 5 个 Flag 开关 | ![Flag面板](APOS-TC002-02-flag-panel.png) |
| 3 | 关闭 "Activity Stream" Flag 开关（点击 toggle） | Activity Stream Toggle 变为灰色（关闭状态），依赖它的子 Flag（AI Insight、Batch Review）自动被禁用并显示 "需要先启用: APOS_ACTIVITY_STREAM" | ![Flag关闭](APOS-TC002-03-flag-disabled.png) |
| 4 | 观察 Sidebar Tab 导航栏 | Activity Tab 从导航栏中消失 | ![Tab消失](APOS-TC002-04-tab-hidden.png) |
| 5 | 重新开启 "Activity Stream" Flag | Activity Tab 重新出现在导航栏中 | ![Tab恢复](APOS-TC002-05-tab-restored.png) |

**通过标准**: Feature Flag `APOS_ACTIVITY_STREAM` 关闭时 Activity Tab 隐藏，开启时显示；级联禁用逻辑正确

---

#### TC-APOS-003: Mock 数据在 APOS_USE_MOCK=true 时手动启用加载

**优先级**: P0
**前置条件**:
- `VITE_APOS_USE_MOCK=false`（默认开发环境已关闭）
- 通过 Feature Flag Panel 手动将 "Use Mock Data" 开关设为启用
- `VITE_APOS_ACTIVITY_STREAM=true`

**说明**: Mock 模式为可选开发辅助工具，默认关闭。生产和开发环境默认使用真实工具调用数据。开发者可通过 Feature Flag Panel 手动启用 Mock 数据进行 UI 调试。

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 进入 Feature Flag Panel，开启 "Use Mock Data" 后刷新页面 `http://localhost:5173` | 页面加载完成，Mock 数据被加载 | — |
| 2 | 点击 Sidebar "Activity" Tab | ActivityStream 面板显示，内容区展示 6 条 Activity 卡片 | ![Mock数据加载](APOS-TC003-01-mock-loaded.png) |
| 3 | 验证列表中第一条（最新）Activity 卡片 | 显示 "新增 WebSocket verify_progress 消息类型"（mock-activity-006，timestamp 最近） | ![第一条Activity](APOS-TC003-02-first-activity.png) |
| 4 | 验证列表中最后一条 Activity 卡片 | 显示 "修复 typo in README.md"（mock-activity-001，timestamp 最早） | ![最后一条Activity](APOS-TC003-03-last-activity.png) |
| 5 | 验证排序 | 6 条 Activity 按时间倒序排列：006 → 005 → 004 → 003 → 002 → 001 | — |

**通过标准**: Mock 模式为可选调试工具，手动启用后加载 6 条数据，按时间倒序排列，卡片内容与 `mockActivities.ts` 定义一致

---

### B. ActivityCard 三层展示（P0）

---

#### TC-APOS-004: L1 紧凑卡片正确展示

**优先级**: P0
**前置条件**:
- Mock 数据已加载（6 条 Activity 可见）
- 当前在 Activity Tab

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 观察第一条 Activity 卡片（mock-activity-006） | 卡片高度为 52px（`h-[52px]`），单行紧凑布局 | ![L1高度](APOS-TC004-01-l1-height.png) |
| 2 | 检查卡片左侧 | 显示 OperationIcon（config_change 类型对应图标） | — |
| 3 | 检查卡片中间区域 | 主文本："新增 WebSocket verify_progress 消息类型"；副文本："3 文件 · 2.1s" | ![L1中间内容](APOS-TC004-02-l1-content.png) |
| 4 | 检查卡片右侧 | 显示黄色 SignalBadge（review_recommended），右侧时间戳（格式如 "Xs ago" / "Xm ago"） | ![L1右侧](APOS-TC004-03-l1-right.png) |
| 5 | 检查 mock-activity-001 卡片（auto_approve） | SignalBadge 为绿色（CheckCircle2 图标），副文本 "1 文件 · 1.2s" | ![L1绿色信号](APOS-TC004-04-l1-green.png) |
| 6 | 检查 mock-activity-004 卡片（blocked） | SignalBadge 为红色（XCircle 图标），主文本 "删除 AuthController 核心模块" | ![L1红色信号](APOS-TC004-05-l1-red.png) |

**通过标准**: 每条 L1 卡片高度 52px，包含操作图标 + 摘要文本 + 文件数/耗时 + SignalBadge + 时间戳

---

#### TC-APOS-005: L1→L2 点击展开

**优先级**: P0
**前置条件**:
- Mock 数据已加载
- 所有卡片处于 L1 折叠状态

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 点击 mock-activity-002 卡片（"重构 UserService 认证逻辑"） | L1 卡片背景变为 `bg-[#2a2a3e]`（展开态），下方展开 L2 详情区域，展开动画持续约 300ms（`duration: 0.3, ease: 'easeOut'`） | ![L2展开动画](APOS-TC005-01-l2-expand.png) |
| 2 | 检查 L2 "受影响文件" 区域 | 显示前 3 个文件：`src/services/UserService.ts`（modified）、`src/services/AuthProvider.ts`（modified）、`src/types/auth.ts`（modified），底部显示 "+1 个文件..." | ![L2文件列表](APOS-TC005-02-l2-files.png) |
| 3 | 检查 L2 操作按钮 | 显示三个按钮：✓ 批准（绿色）、✗ 拒绝（红色）、详情 →（灰色，右对齐） | ![L2按钮](APOS-TC005-03-l2-buttons.png) |
| 4 | 验证展开 mock-activity-002 时其他卡片不展开 | 仅 mock-activity-002 显示 L2 内容，其余卡片保持 L1 状态 | — |

**通过标准**: 点击 L1 展开 L2，动画流畅（300ms easeOut），显示文件列表和操作按钮，单一展开状态互斥

---

#### TC-APOS-006: L2→L3 Portal 全屏浮层

**优先级**: P0
**前置条件**:
- mock-activity-002 已展开至 L2 状态

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 在 L2 中点击 "详情 →" 按钮 | 打开 L3 Portal 全屏浮层，使用 `createPortal` 挂载到 `document.body` | ![L3浮层](APOS-TC006-01-l3-portal.png) |
| 2 | 检查遮罩层 | 半透明黑色遮罩（`bg-black/60 backdrop-blur-sm`）覆盖整个页面 | — |
| 3 | 检查 L3 面板 Header | 显示操作图标 + 标题 "重构 UserService 认证逻辑" + SignalBadge（review_recommended, 黄色, md 尺寸显示标签文字"建议审查"） + 关闭按钮（X） | ![L3 Header](APOS-TC006-02-l3-header.png) |
| 4 | 检查 "变更文件列表" 区域 | 对于文件操作类 Activity（如 refactor）：显示全部 4 个文件及其增删行数：`src/services/UserService.ts` (+45 -30 modified)、`src/services/AuthProvider.ts` (+12 -5 modified)、`src/types/auth.ts` (+8 -3 modified)、`tests/UserService.test.ts` (+20 -10 modified)；**注意**：对于 `command_execute` 类型的 Activity，L3 弹窗显示"命令输出"区域（CommandOutputViewer 终端风格组件）而非"变更文件列表"，详见 TC-APOS-042 | ![L3文件列表](APOS-TC006-03-l3-filelist.png) |
| 5 | 检查 L3 面板 Footer | 显示 "✓ 批准" + "✗ 拒绝" + "关闭" 三个按钮 | ![L3 Footer](APOS-TC006-04-l3-footer.png) |
| 6 | 按键盘 ESC | L3 浮层关闭，body 恢复滚动 | ![ESC关闭](APOS-TC006-05-l3-esc-close.png) |

**通过标准**: L3 Portal 正确渲染，包含变更文件列表完整信息（增删行数+changeType），ESC/关闭按钮均可关闭

---

#### TC-APOS-007: L2 折叠回 L1

**优先级**: P0
**前置条件**:
- mock-activity-002 处于 L2 展开状态

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 再次点击已展开的 mock-activity-002 L1 卡片区域 | L2 区域收起（exit 动画：height→0, opacity→0），卡片恢复 L1 紧凑状态 | ![L2折叠](APOS-TC007-01-l2-collapse.png) |
| 2 | 验证折叠动画 | AnimatePresence exit 动画平滑过渡，无闪烁 | — |
| 3 | 点击另一张卡片（如 mock-activity-004） | 新卡片展开 L2，mock-activity-002 保持 L1 状态 | ![切换卡片](APOS-TC007-02-switch-card.png) |

**通过标准**: 点击已展开卡片可折叠回 L1，切换展开另一张卡片时前者自动折叠

---

### C. SignalBadge 信号系统（P0）

---

#### TC-APOS-008: 四种信号颜色正确显示

**优先级**: P0
**前置条件**:
- Mock 数据已加载（包含 4 种信号类型）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 定位 mock-activity-001（auto_approve） | SignalBadge 绿色（`text-green-400` + `bg-green-500/15`），图标 CheckCircle2 | ![信号绿色](APOS-TC008-01-signal-green.png) |
| 2 | 定位 mock-activity-002（review_recommended） | SignalBadge 黄色（`text-yellow-400` + `bg-yellow-500/15`），图标 Eye | ![信号黄色](APOS-TC008-02-signal-yellow.png) |
| 3 | 定位 mock-activity-003（manual_required） | SignalBadge 蓝色（`text-blue-400` + `bg-blue-500/15`），图标 Hand | ![信号蓝色](APOS-TC008-03-signal-blue.png) |
| 4 | 定位 mock-activity-004（blocked） | SignalBadge 红色（`text-red-400` + `bg-red-500/15`），图标 XCircle | ![信号红色](APOS-TC008-04-signal-red.png) |

**通过标准**: 四种信号分别对应正确颜色（绿/黄/蓝/红）和图标（CheckCircle2/Eye/Hand/XCircle）

---

#### TC-APOS-009: SignalBadge hover 显示 tooltip

**优先级**: P0
**前置条件**:
- Mock 数据已加载

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | Hover 到 mock-activity-001 的 SignalBadge 上 | 显示 tooltip："仅修改文档 typo，无代码逻辑变更"（来自 `activity.insight.summary`） | ![Tooltip绿色](APOS-TC009-01-tooltip-green.png) |
| 2 | Hover 到 mock-activity-002 的 SignalBadge 上 | 显示 tooltip："认证逻辑重构涉及 2 个公开 API，建议审查" | ![Tooltip黄色](APOS-TC009-02-tooltip-yellow.png) |
| 3 | Hover 到 mock-activity-004 的 SignalBadge 上 | 显示 tooltip："删除核心认证模块将导致 12 个测试失败" | ![Tooltip红色](APOS-TC009-03-tooltip-red.png) |
| 4 | 鼠标移出 SignalBadge | tooltip 消失（`opacity-0` 过渡） | — |

**通过标准**: hover 时显示来自 `insight.summary` 的 tooltip 文本，通过浏览器原生 `title` 属性实现（非自定义绝对定位 tooltip，避免 overflow 裁剪问题）

---

### D. 信号筛选（P1）

---

#### TC-APOS-010: 筛选条按 Signal 类型过滤 Activity

**优先级**: P1
**前置条件**:
- Mock 数据已加载（6 条 Activity）
- 筛选栏可见（ActivityStream 顶部）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 观察筛选栏 | 显示 5 个筛选按钮："全部"（默认高亮）、"可放行"、"建议审查"、"需手动"、"已阻止" | ![筛选栏](APOS-TC010-01-filter-bar.png) |
| 2 | 点击 "可放行" 筛选按钮 | 按钮高亮（`bg-blue-600/30 text-blue-300`），列表仅显示 2 条 Activity：mock-activity-001（修复 typo）和 mock-activity-005（更新依赖版本） | ![筛选可放行](APOS-TC010-02-filter-auto-approve.png) |
| 3 | 点击 "已阻止" 筛选按钮 | 列表仅显示 1 条 Activity：mock-activity-004（删除 AuthController 核心模块） | ![筛选已阻止](APOS-TC010-03-filter-blocked.png) |
| 4 | 点击 "建议审查" 筛选按钮 | 列表显示 2 条：mock-activity-002（重构 UserService）和 mock-activity-006（新增 WS 消息类型） | ![筛选建议审查](APOS-TC010-04-filter-review.png) |
| 5 | 点击 "需手动" 筛选按钮 | 列表显示 1 条：mock-activity-003（数据库迁移脚本 v2.3） | ![筛选需手动](APOS-TC010-05-filter-manual.png) |

**通过标准**: 各筛选按钮正确过滤，结果数量和内容与 Mock 数据信号类型一致

---

#### TC-APOS-011: "全部" 筛选显示所有 Activity

**优先级**: P1
**前置条件**:
- 当前处于某个筛选状态（非"全部"）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 当前筛选为 "可放行"（显示 2 条） | 列表仅显示 2 条 Activity | — |
| 2 | 点击 "全部" 筛选按钮 | "全部" 按钮高亮，列表恢复显示全部 6 条 Activity | ![全部筛选](APOS-TC011-01-filter-all.png) |
| 3 | 验证排序 | 6 条按时间倒序排列 | — |

**通过标准**: 点击 "全部" 清除筛选条件，`setFilter({})` 被调用，恢复完整列表

---

### E. 批量操作（P1）

---

#### TC-APOS-012: 进入批量选择模式

**优先级**: P1
**前置条件**:
- Mock 数据已加载
- 当前处于正常浏览模式（非批量模式）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 通过 Feature Flag 确认 `APOS_BATCH_REVIEW=true` | Batch Review 功能已启用 | — |
| 2 | 触发批量选择模式（通过 store 调用 `setBatchMode(true)`，或通过 UI 入口） | ActivityStream 顶部出现 BatchOperationBar，显示 "已选 **0** 项" | ![批量模式](APOS-TC012-01-batch-mode.png) |
| 3 | 验证每个 L1 卡片左侧 | 出现复选框（checkbox），位于 OperationIcon 左侧 | ![复选框](APOS-TC012-02-checkboxes.png) |

**通过标准**: 批量模式启用后，BatchOperationBar 出现，每个 L1 卡片显示复选框

---

#### TC-APOS-013: 单选/全选 Activity

**优先级**: P1
**前置条件**:
- 已进入批量选择模式

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 点击 mock-activity-001 的复选框 | 该卡片被选中（左侧蓝色边框 `border-l-2 border-l-blue-500` + 背景高亮 `bg-[#1e3a5f]/30`），BatchOperationBar 显示 "已选 **1** 项" | ![单选](APOS-TC013-01-single-select.png) |
| 2 | 再次点击同一复选框 | 取消选中，恢复默认样式，BatchOperationBar 显示 "已选 **0** 项" | — |
| 3 | 点击 BatchOperationBar 中的 "全选可操作" 按钮 | 自动选中所有 signal 为 `auto_approve` 或 `review_recommended` 的 Activity（共 4 条：001、002、005、006），BatchOperationBar 显示 "已选 **4** 项" | ![全选可操作](APOS-TC013-02-select-all-safe.png) |
| 4 | 验证 mock-activity-003（manual_required）和 mock-activity-004（blocked）未被选中 | 这两条卡片无选中高亮 | — |

**通过标准**: 单选切换正确，"全选可操作" 仅选中 auto_approve + review_recommended 信号的 Activity

---

#### TC-APOS-014: 批量批准操作

**优先级**: P1
**前置条件**:
- 已选中至少 1 条 Activity

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 选中 mock-activity-001 和 mock-activity-005（两条 auto_approve） | BatchOperationBar 显示 "已选 **2** 项" | — |
| 2 | 点击 "✓ 批量批准" 按钮 | 按钮样式为绿色背景（`bg-green-600/20 text-green-300`），点击后执行批量批准操作 | ![批量批准](APOS-TC014-01-batch-approve.png) |
| 3 | 未选中任何 Activity 时观察按钮状态 | "批量批准" 和 "批量拒绝" 按钮呈禁用状态（`opacity-40 cursor-not-allowed`） | ![按钮禁用](APOS-TC014-02-buttons-disabled.png) |

**通过标准**: 批量批准按钮在有选中时可点击，无选中时呈禁用态

---

#### TC-APOS-015: 批量拒绝操作

**优先级**: P1
**前置条件**:
- 已选中至少 1 条 Activity

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 选中 mock-activity-004（blocked） | BatchOperationBar 显示 "已选 **1** 项" | — |
| 2 | 点击 "✗ 批量拒绝" 按钮 | 按钮样式为红色背景（`bg-red-600/20 text-red-300`），点击后执行批量拒绝操作 | ![批量拒绝](APOS-TC015-01-batch-reject.png) |
| 3 | 点击 "取消选择" 按钮（右对齐） | 清除所有选中状态，退出批量模式，BatchOperationBar 消失，卡片复选框消失 | ![取消选择](APOS-TC015-02-cancel-batch.png) |

**通过标准**: 批量拒绝按钮可用，"取消选择" 退出批量模式并清除选中

---

### F. Feature Flag 面板（P1）

---

#### TC-APOS-016: Feature Flag 面板显示所有 APOS Flag

**优先级**: P1
**前置条件**:
- 当前在 Activity Tab

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 观察 ActivityStream 下方的 Feature Flags 面板 | 面板 Header 显示齿轮图标（Settings2）+ "Feature Flags" 标题 + "重置" 按钮 | ![Flag面板Header](APOS-TC016-01-flag-panel-header.png) |
| 2 | 检查 Flag 列表内容 | 显示 5 个 Flag：Activity Stream、AI Insight、Batch Review、Risk Heatmap、Use Mock Data | ![Flag列表](APOS-TC016-02-flag-list.png) |
| 3 | 验证每个 Flag 项目结构 | 左侧：名称 + 描述文字（10px 灰色字）；右侧：Toggle 开关（蓝色=开启，灰色=关闭） | — |
| 4 | 检查默认状态 | Activity Stream=开、AI Insight=开、Batch Review=开、Risk Heatmap=关、Use Mock Data=开 | ![默认状态](APOS-TC016-03-default-states.png) |

**通过标准**: 面板正确渲染所有 Flag，状态与 `.env.development` 默认值一致

---

#### TC-APOS-017: Feature Flag 开关可交互切换

**优先级**: P1
**前置条件**:
- Feature Flag 面板可见

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 点击 "Use Mock Data" Toggle 开关（当前为开） | Toggle 从蓝色变为灰色（关闭），内部圆点从右移至左 | ![Toggle关闭](APOS-TC017-01-toggle-off.png) |
| 2 | 再次点击 "Use Mock Data" Toggle | Toggle 恢复蓝色（开启） | ![Toggle开启](APOS-TC017-02-toggle-on.png) |
| 3 | 点击 "重置" 按钮 | 所有 Flag 恢复默认值（`APOS_FLAG_DEFAULTS`） | ![重置](APOS-TC017-03-reset.png) |

**通过标准**: Toggle 点击切换状态，重置按钮恢复默认配置

---

#### TC-APOS-018: Feature Flag 依赖关系限制

**优先级**: P1
**前置条件**:
- Feature Flag 面板可见
- 所有 Flag 为默认状态

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 关闭 "Activity Stream" Flag | Activity Stream Toggle 变灰 | — |
| 2 | 观察 "AI Insight" Flag 状态 | AI Insight 自动被禁用（级联禁用），Toggle 变灰且不可点击（`cursor-not-allowed`），显示橙色提示 "需要先启用: APOS_ACTIVITY_STREAM" | ![依赖禁用](APOS-TC018-01-dependency-disabled.png) |
| 3 | 观察 "Batch Review" Flag 状态 | Batch Review 同样被级联禁用，显示 "需要先启用: APOS_ACTIVITY_STREAM" | — |
| 4 | 尝试点击被禁用的 "AI Insight" Toggle | 无响应（`disabled` 属性阻止交互） | ![点击无效](APOS-TC018-02-click-blocked.png) |
| 5 | 重新开启 "Activity Stream" | AI Insight 和 Batch Review 恢复可操作状态（但保持关闭，需手动开启） | ![恢复可操作](APOS-TC018-03-restored.png) |
| 6 | 关闭 "AI Insight" Flag | "Risk Heatmap"（依赖 AI_INSIGHT）被级联禁用 | ![二级级联](APOS-TC018-04-cascade-level2.png) |

**通过标准**: 依赖关系正确执行——禁用父 Flag 时所有依赖子 Flag 自动级联禁用且不可操作

---

### G. 手机端适配（P1）

---

#### TC-APOS-019: 手机端 Bottom Sheet 展示

**优先级**: P1
**前置条件**:
- 浏览器开启设备模拟（iPhone 14 Pro: 393×852）
- Mock 数据已加载

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 浏览器 DevTools 切换至移动端视口（393×852） | 页面自适应移动端布局 | ![移动端](APOS-TC019-01-mobile-viewport.png) |
| 2 | 在 Activity 列表中点击某条 Activity 触发详情查看 | 从底部滑出 MobileBottomSheet（`y: '100%' → y: 0`），弹簧动画（`damping: 25, stiffness: 300`） | ![BottomSheet出现](APOS-TC019-02-bottomsheet-appear.png) |
| 3 | 检查 Bottom Sheet 结构 | 顶部圆角（`rounded-t-2xl`）+ 拖拽手柄（10×4 灰色圆角条）+ Header（标题+SignalBadge+关闭按钮）+ 内容区（确定性验证+启发式分析+受影响文件）+ Footer（批准/拒绝/详情按钮） | ![BottomSheet结构](APOS-TC019-03-bottomsheet-structure.png) |
| 4 | 检查 Bottom Sheet 高度 | 最小高度 60vh，最大高度 90vh | — |
| 5 | 点击遮罩层（Overlay）或关闭按钮 | Bottom Sheet 向下滑出关闭，body 恢复滚动 | ![BottomSheet关闭](APOS-TC019-04-bottomsheet-close.png) |

**通过标准**: 移动端正确使用 MobileBottomSheet 组件替代 L3 Portal，弹簧动画流畅

---

#### TC-APOS-020: Bottom Sheet 拖拽交互

**优先级**: P1
**前置条件**:
- MobileBottomSheet 已打开

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 按住 Bottom Sheet 顶部拖拽手柄，向下拖拽小于 100px | Sheet 跟随手指移动（弹性阻尼 `dragElastic: { top: 0, bottom: 0.6 }`），释放后回弹至原位 | ![拖拽小幅](APOS-TC020-01-drag-small.png) |
| 2 | 向下拖拽超过 100px（`DRAG_CLOSE_THRESHOLD`） | Sheet 关闭（`onDragEnd` 触发 `onClose()`），向下滑出视口 | ![拖拽关闭](APOS-TC020-02-drag-close.png) |
| 3 | 向上拖拽 | 被约束（`dragConstraints: { top: 0 }`），无法向上超出初始位置 | — |

**通过标准**: 向下拖拽超过 100px 阈值关闭 Sheet，小幅拖拽回弹，向上拖拽被约束

---

### H. 后端 API（P0）

---

#### TC-APOS-021: POST /api/verify/run-checks 端点可访问

**优先级**: P0
**前置条件**:
- Backend 服务运行在 8080 端口

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 执行请求：`curl -X POST http://localhost:8080/api/verify/run-checks -H "Content-Type: application/json" -d '{"sessionId":"test-session","operationId":"test-op-001","checks":["typescript","eslint"],"filePaths":["src/App.tsx"],"timeout":10000}'` | 返回 HTTP 200，响应体包含 `operationId`、`status`、`results`（数组）、`totalDuration`、`signal`、`signalReason` | ![API响应](APOS-TC021-01-api-response.png) |
| 2 | 验证响应结构 | `results` 数组中每项包含 `check`（字符串）、`passed`（布尔）、`errors`（数组）、`warnings`（数组）、`duration`（毫秒数） | — |
| 3 | 验证参数缺失时返回 400 | `curl -X POST http://localhost:8080/api/verify/run-checks -H "Content-Type: application/json" -d '{"sessionId":"test"}'` → HTTP 400 | ![400响应](APOS-TC021-02-bad-request.png) |
| 4 | 验证 filePaths 为空时返回 400 | `curl -X POST http://localhost:8080/api/verify/run-checks -H "Content-Type: application/json" -d '{"sessionId":"s","operationId":"o","checks":["typescript"],"filePaths":[]}'` → HTTP 400 | — |

**通过标准**: 端点正常响应，参数校验正确（sessionId/operationId/checks/filePaths 必填），返回结构完整

---

#### TC-APOS-022: 验证结果通过 WebSocket 推送

**优先级**: P0
**前置条件**:
- Backend 和 Frontend 运行中
- WebSocket STOMP 连接已建立

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 建立 WebSocket STOMP 连接并订阅 `/user/queue/messages` | 连接成功，订阅确认 | — |
| 2 | 通过 REST API 调用 `POST /api/verify/run-checks`（使用认证用户） | API 返回 200 + 验证结果 | — |
| 3 | 监听 WebSocket 消息 | 收到类型为 `verification_result` 的消息，包含：`type:"verification_result"`、`sessionId`、`operationId`、`result`（完整 RunChecksResponse）、`timestamp` | ![WS推送](APOS-TC022-01-ws-push.png) |
| 4 | 验证前端 dispatch 处理 | `dispatch.ts` 中 `verification_result` handler 调用 `mapRunChecksResponseToRiskAssessment()` 转换数据，并通过 `useInsightStore.addAssessment()` 存储 | — |

**通过标准**: 验证结果同时通过 HTTP 响应返回 + WebSocket 异步推送，前端 dispatch 正确处理

---

### I. 数据流转集成（P0）

---

#### TC-APOS-023: 工具调用完成后 Activity 自动出现

**优先级**: P0
**前置条件**:
- `VITE_APOS_ACTIVITY_STREAM=true`
- 已建立会话并连接 WebSocket

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 在对话中发送需要工具调用的请求（如"读取 README.md 前5行"） | LLM 触发 Read 工具调用 | — |
| 2 | 等待工具调用完成 | `messageStore.activeToolCalls` 中对应 toolCall 状态变为 `completed` | — |
| 3 | 切换到 Activity Tab | ActivityStream 列表中出现新的 Activity 条目，`operationType` 根据工具名推断（Read → `unknown`，因包含 "read"） | ![新Activity](APOS-TC023-01-new-activity.png) |
| 4 | 验证新 Activity 的 L1 卡片 | summary 显示工具名（如 "Read"），时间戳为当前时间 | — |

**通过标准**: `useAPOSInitialization` Hook 正确监听 `messageStore.activeToolCalls` 变化，完成的工具调用自动转为 Activity

---

#### TC-APOS-024: verification_result 消息正确关联到 Activity

**优先级**: P0
**前置条件**:
- 工具调用已产生 Activity（TC-APOS-023 通过）
- 后端推送 `verification_result` 消息

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 后端完成验证检查并通过 WebSocket 推送 `verification_result` | 前端 `dispatch.ts` 接收消息，调用 `mapRunChecksResponseToRiskAssessment(result)` | — |
| 2 | 验证 insightStore 更新 | `useInsightStore.assessments` Map 中新增一条 assessment，key 为 `operationId` | — |
| 3 | 验证 activityStore insight 关联 | `useAPOSInitialization` 监听 `insightStore.assessments` 变化，调用 `attachInsight(operationId, insight)` 将 assessment 转换后关联到对应 Activity | — |
| 4 | 在 Activity Tab 观察对应卡片 | L1 卡片的 SignalBadge 从 "loading"（灰色旋转）变为具体信号颜色（根据验证结果计算） | ![信号更新](APOS-TC024-01-signal-updated.png) |

**通过标准**: verification_result → insightStore.assessment → activityStore.insight 数据流完整，SignalBadge 实时更新

---

### J. 异常场景（P2）

---

#### TC-APOS-025: ActivityStream 空状态正确展示

**优先级**: P2
**前置条件**:
- `VITE_APOS_USE_MOCK=false`（关闭 Mock 数据）
- 无任何工具调用产生 Activity

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 设置 `VITE_APOS_USE_MOCK=false`，重启前端 | Mock 数据不加载 | — |
| 2 | 点击 Activity Tab | 内容区显示空状态：居中的 Inbox 图标（40px，灰色）+ 文字 "暂无活动记录" | ![空状态](APOS-TC025-01-empty-state.png) |
| 3 | 验证筛选栏仍可见 | 顶部筛选按钮正常显示（全部/可放行/建议审查/需手动/已阻止） | — |

**通过标准**: 无数据时显示空状态占位，不崩溃，筛选栏保持可见

---

#### TC-APOS-026: L3 Portal 遮罩层点击关闭

**优先级**: P2
**前置条件**:
- L3 Portal 浮层已打开

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | L3 Portal 打开状态，点击遮罩层（面板外的半透明黑色区域） | L3 Portal 关闭（`onClick={onClose}` 绑定在 backdrop div 上）| ![遮罩关闭](APOS-TC026-01-backdrop-close.png) |
| 2 | 验证关闭后状态 | body overflow 恢复，`l3ActivityId` 设为 null，页面恢复正常滚动 | — |
| 3 | 点击 L3 面板内部内容区域 | 不触发关闭（面板 `relative z-40` 在遮罩层之上） | — |

**通过标准**: 点击遮罩层关闭浮层，点击面板内部不关闭

---

## 3 通过标准汇总

| 模块 | 用例数 | 通过标准 |
|------|--------|----------|
| A. APOS Tab 基础功能 | 3 | Tab 显示/隐藏/Mock 数据加载 100% 正确 |
| B. ActivityCard 三层展示 | 4 | L1→L2→L3 全链路交互正常，动画流畅无闪烁 |
| C. SignalBadge 信号系统 | 2 | 四色信号映射正确，tooltip 内容匹配 Mock 数据 |
| D. 信号筛选 | 2 | 各筛选结果数量与 Mock 数据一致 |
| E. 批量操作 | 4 | 批量选择/全选/批准/拒绝/取消流程完整 |
| F. Feature Flag 面板 | 3 | 所有 Flag 可切换，依赖关系正确级联 |
| G. 手机端适配 | 2 | BottomSheet 弹出/拖拽/关闭动画正确 |
| H. 后端 API | 2 | run-checks 端点可达，参数校验+WS 推送正常 |
| I. 数据流转集成 | 2 | 工具调用→Activity→Assessment→Signal 全链路贯通 |
| J. 异常场景 | 2 | 空状态渲染正确，Portal 遮罩交互正确 |
| **总计** | **44** | **全部 PASS 方可判定 Phase 1 交付合格** |

---

## 4 截图文件命名规范

格式：`APOS-{测试编号}-{步骤编号}-{描述}.png`

示例：
- `APOS-TC001-01-sidebar-apos-tab.png`
- `APOS-TC004-03-l1-right.png`
- `APOS-TC006-01-l3-portal.png`
- `APOS-TC019-02-bottomsheet-appear.png`

截图存放目录：`docs/test-results/screenshots/apos-phase1/`

---

## 5 附录：关键数据引用

### 5.1 Signal 映射表（来源：`SignalBadge.tsx`）

| Signal 值 | 颜色 | 图标 | 中文标签 |
|-----------|------|------|----------|
| `auto_approve` | green-400 | CheckCircle2 | 自动放行 |
| `review_recommended` | yellow-400 | Eye | 建议审查 |
| `manual_required` | blue-400 | Hand | 需手动处理 |
| `blocked` | red-400 | XCircle | 已阻止 |
| `loading` | gray-400 | Loader2（旋转） | 验证中 |

### 5.2 Feature Flag 依赖关系（来源：`featureFlagStore.ts`）

```
APOS_ACTIVITY_STREAM (根节点，无依赖)
├── APOS_AI_INSIGHT (依赖 ACTIVITY_STREAM)
│   └── APOS_RISK_HEATMAP (依赖 AI_INSIGHT)
├── APOS_BATCH_REVIEW (依赖 ACTIVITY_STREAM)
└── APOS_USE_MOCK (无依赖)
```

### 5.3 后端 API 请求/响应参考

**请求**：`POST /api/verify/run-checks`
```json
{
  "sessionId": "abc-123",
  "operationId": "op-001",
  "checks": ["typescript", "eslint", "test_match"],
  "filePaths": ["src/services/UserService.ts", "src/services/AuthProvider.ts"],
  "timeout": 10000
}
```

**响应**：
```json
{
  "operationId": "op-001",
  "status": "all_pass",
  "results": [
    { "check": "typescript", "passed": true, "errors": [], "warnings": [], "duration": 2340 },
    { "check": "eslint", "passed": true, "errors": [], "warnings": [], "duration": 1560 },
    { "check": "test_match", "passed": true, "errors": [], "warnings": [], "duration": 3200 }
  ],
  "totalDuration": 7100,
  "timestamp": "2026-05-11T10:30:00Z",
  "signal": "auto_approve",
  "signalReason": "全部验证通过"
}
```

### 5.4 WebSocket 推送消息格式

```json
{
  "type": "verification_result",
  "sessionId": "abc-123",
  "operationId": "op-001",
  "result": { /* RunChecksResponse 完整结构 */ },
  "timestamp": "2026-05-11T10:30:01Z"
}
```

---

## 6 补充测试用例

> **补充原因**: 经逐文件交叉审查，以下功能点在原 26 条用例中未覆盖或仅部分覆盖。
> **补充用例编号**: TC-APOS-027 ~ TC-APOS-041（共 15 条）

| 模块 | 用例数 | 优先级 | 编号范围 | 覆盖缺口 |
|------|--------|--------|----------|----------|
| K. computeSignal 规则引擎 | 2 | P0 | TC-APOS-027 ~ 028 | insightStore.computeSignal() 四条规则路径 |
| L. aposAdapters 数据转换 | 1 | P0 | TC-APOS-029 | RunChecksResponse → RiskAssessment 映射 |
| M. performRetention 清理策略 | 1 | P1 | TC-APOS-030 | FIFO + 状态保护 |
| N. VerificationIcon 5 状态 | 1 | P1 | TC-APOS-031 | 5 种验证状态图标+动画 |
| O. OperationIcon 10 类型 | 1 | P1 | TC-APOS-032 | 10 种操作类型完整覆盖 |
| P. 后端超时降级 | 1 | P0 | TC-APOS-033 | 10s 超时 → blocked signal |
| Q. 后端并行执行 | 1 | P1 | TC-APOS-034 | 多 check 并行 + 部分超时 |
| R. verify_progress 消息 | 1 | P1 | TC-APOS-035 | WebSocket 进度推送 |
| S. 响应式断点切换 | 1 | P1 | TC-APOS-036 | useResponsive PC/Tablet/Mobile |
| T. 虚拟滚动性能 | 1 | P2 | TC-APOS-037 | Virtuoso 大量数据 |
| U. L3 Monaco Diff 懒加载 | 1 | P1 | TC-APOS-038 | Suspense + lazy import |
| V. 环境变量初始化 | 1 | P1 | TC-APOS-039 | initializeFlags() 读取 VITE_APOS_* |
| W. useAPOSInitialization 工具类型推断 | 1 | P1 | TC-APOS-040 | inferOperationType() 映射 |
| X. SignalBadge loading 状态 | 1 | P1 | TC-APOS-041 | loading 旋转态 + 动态更新 |
| **补充合计** | **15** | | | |

---

### K. computeSignal 规则引擎（P0）

---

#### TC-APOS-027: computeSignal 四条规则路径 — blocked

**优先级**: P0
**前置条件**:
- 后端返回包含失败检查的 RunChecksResponse
- WebSocket 连接已建立

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 调用 `POST /api/verify/run-checks` 使用一个不存在的 TypeScript 文件路径 `{"sessionId":"s1","operationId":"op-signal-blocked","checks":["typescript"],"filePaths":["src/nonexistent.ts"]}` | 返回 HTTP 200，`results[0].passed=false`，`signal="blocked"` | ![blocked-signal](APOS-TC027-01-blocked-signal.png) |
| 2 | 前端通过 WebSocket 收到 `verification_result`，dispatch 调用 `mapRunChecksResponseToRiskAssessment()` | `computeSignal()` 判定 `deterministic.typeCheck.errorCount > 0`，返回 `{signal: 'blocked', reason: 'TypeScript X 个类型错误'}` | — |
| 3 | 在 Activity Tab 观察对应 Activity 卡片 | SignalBadge 显示红色（blocked），tooltip 显示 "TypeScript X 个类型错误" | ![blocked-badge](APOS-TC027-02-blocked-badge.png) |
| 4 | 验证 `heuristic.truncated=true` 触发 blocked | 发送请求使 check 超时（`timeout:100`），response 中出现 `check:"timeout"`，前端 adapter 设置 `heuristic.truncated=true`，signal=blocked | ![truncated-blocked](APOS-TC027-03-truncated-blocked.png) |

**通过标准**: computeSignal 黑名单规则正确：TypeScript 错误/ESLint 错误/测试失败/截断 → 均产出 `blocked` signal

---

#### TC-APOS-028: computeSignal 四条规则路径 — review_recommended / auto_approve / manual_required

**优先级**: P0
**前置条件**:
- 后端可返回不同检查结果组合

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 构造响应：`results` 中 typescript 通过、eslint 有 2 个 warnings（errors=0）、test_match 通过 | `mapRunChecksResponseToRiskAssessment()` 产生 `deterministic.lint.warningCount=2`，computeSignal 返回 `{signal: 'review_recommended', reason: 'ESLint 2 个警告'}` | ![review-signal](APOS-TC028-01-review-signal.png) |
| 2 | 构造响应：所有 checks 通过（errors=0, warnings=0）、filePaths 影响范围 ≤2 文件 | computeSignal 返回 `{signal: 'auto_approve', reason: '全部验证通过，影响范围可控'}`，对应卡片 SignalBadge 绿色 | ![auto-approve-signal](APOS-TC028-02-auto-approve-signal.png) |
| 3 | 构造响应：所有 checks 通过但无 test_match check（仅 typescript + eslint） | `deterministic.tests.passedCount=0`（因 testResult 不存在则 passed=true 但 passedCount=0），computeSignal 判定 `passedCount===0` → `review_recommended` | ![no-test-signal](APOS-TC028-03-no-test-signal.png) |
| 4 | 构造响应：所有 checks 通过、有 test_match、`indirectImpactCount=5`（通过 heuristic 模拟） | 因前端 adapter 中 `heuristic.indirectImpactCount` 固定为 0（当前实现），signal 走 auto_approve 路径。验证当后端扩展 heuristic 字段后 `indirectImpactCount > 3` → `review_recommended` | — |
| 5 | 验证 manual_required 兜底 | 构造边界场景：typeCheck/lint/tests 均通过，但 `heuristic.affectedApiCount=0, indirectImpactCount=0, passedCount > 0`，且不满足绿灯条件（如 `typeCheck.passed=false` 被修正但未覆盖所有条件）→ fallback 为 `manual_required` | ![manual-signal](APOS-TC028-04-manual-signal.png) |

**通过标准**: computeSignal 函数（`insightStore.ts` L26-48）的 4 条规则路径（blocked/review_recommended/auto_approve/manual_required）均被验证触发

---

### L. aposAdapters 数据转换（P0）

---

#### TC-APOS-029: mapRunChecksResponseToRiskAssessment 完整转换验证

**优先级**: P0
**前置条件**:
- 后端返回包含 typescript/eslint/test_match 三种 check 结果的 RunChecksResponse

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 发送请求：`{"sessionId":"s1","operationId":"op-adapter-test","checks":["typescript","eslint","test_match"],"filePaths":["src/App.tsx"]}` | 返回完整的 RunChecksResponse 结构 | — |
| 2 | 前端 dispatch 调用 `mapRunChecksResponseToRiskAssessment(response)` | 验证转换结果：`deterministic.typeCheck` 从 `results.find(r => r.check === 'typescript')` 提取；`deterministic.lint` 从 eslint 提取；`deterministic.tests` 从 test_match 提取 | ![adapter-transform](APOS-TC029-01-adapter-transform.png) |
| 3 | 验证 `heuristic.filesAffected` 字段 | 从所有 results 的 errors + warnings 中提取唯一 file 路径列表（去重） | — |
| 4 | 验证 `heuristic.truncated` 字段 | 当 results 中存在 `check === 'timeout'` 时为 `true`，否则为 `false` | — |
| 5 | 验证缺失 check 时的 fallback | 若 response.results 中无 typescript check，则 `deterministic.typeCheck.passed` 默认 `true`，`errorCount` 默认 `0` | ![adapter-fallback](APOS-TC029-02-adapter-fallback.png) |

**通过标准**: `aposAdapters.ts` 中 `mapRunChecksResponseToRiskAssessment()` 正确拆分 RunChecksResponse.results 到三维 deterministic 结构 + heuristic 结构，缺失 check 使用安全默认值

---

### M. performRetention 清理策略（P1）

---

#### TC-APOS-030: Activity 超过 maxCount 时 FIFO 清理 + 状态保护

**优先级**: P1
**前置条件**:
- `DEFAULT_RETENTION_CONFIG.maxCount = 200`
- `protectedSignals = ['blocked', 'manual_required']`
- `autoArchiveAfterMs = 1_800_000`（30 分钟）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 通过 activityStore.addActivity() 连续添加 210 条 Activity（其中 5 条 signal=blocked，5 条 signal=manual_required，200 条 signal=auto_approve） | activities Map 中有 210 条记录 | — |
| 2 | 调用 `useActivityStore.getState().performRetention()` | 总数被清理至 ≤ 200 条 | ![retention-cleanup](APOS-TC030-01-retention-cleanup.png) |
| 3 | 验证 blocked/manual_required 条目 | 所有 10 条受保护 Activity（5 blocked + 5 manual_required）仍然存在（`protectedSignals` 保护） | — |
| 4 | 验证非保护条目的 FIFO 删除 | 被删除的 10 条为 timestamp 最早的非保护 Activity（FIFO：先进先出） | — |
| 5 | 验证状态未完成条目的保护 | `status !== 'completed'`（如 `awaiting_review`）的 Activity 也受保护不被清理 | — |
| 6 | 验证 30 分钟内的条目保护 | `now - timestamp < autoArchiveAfterMs` 的条目即使非保护信号也不被清理 | ![retention-time-protect](APOS-TC030-02-retention-time-protect.png) |

**通过标准**: performRetention 正确执行 FIFO 策略，保护 blocked/manual_required 信号 + 未完成状态 + 30 分钟内新条目

---

### N. VerificationIcon 5 状态（P1）

---

#### TC-APOS-031: VerificationIcon 五种验证状态正确渲染

**优先级**: P1
**前置条件**:
- Mock 数据包含不同 `verificationStatus` 的 Activity
- L2 展开状态可见 VerificationIcon

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 展开 mock-activity-001（verificationStatus=`all_pass`）至 L2 或 L3 | VerificationIcon 显示绿色 CheckCircle 图标（`text-green-400`） | ![verify-all-pass](APOS-TC031-01-verify-all-pass.png) |
| 2 | 展开 mock-activity-004（verificationStatus=`has_error`）至 L2 或 L3 | VerificationIcon 显示红色 XCircle 图标（`text-red-400`） | ![verify-has-error](APOS-TC031-02-verify-has-error.png) |
| 3 | 展开 mock-activity-002（verificationStatus=`has_warning`）至 L2 或 L3 | VerificationIcon 显示黄色 AlertTriangle 图标（`text-yellow-400`） | ![verify-has-warning](APOS-TC031-03-verify-has-warning.png) |
| 4 | 展开 mock-activity-003（verificationStatus=`pending`）至 L2 或 L3 | VerificationIcon 显示蓝色 Loader2 图标（`text-blue-400`）+ `animate-spin` 旋转动画 | ![verify-pending](APOS-TC031-04-verify-pending.png) |
| 5 | 构造一条 verificationStatus=`skipped` 的 Activity 并展开 | VerificationIcon 显示灰色 MinusCircle 图标（`text-gray-500`），无动画 | ![verify-skipped](APOS-TC031-05-verify-skipped.png) |

**通过标准**: STATUS_MAP 五种状态（all_pass/has_error/has_warning/pending/skipped）均正确渲染对应图标、颜色和动画

---

### O. OperationIcon 10 类型（P1）

---

#### TC-APOS-032: OperationIcon 十种操作类型图标全覆盖

**优先级**: P1
**前置条件**:
- 可通过 Mock 数据或动态添加 Activity 覆盖所有 OperationType

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 定位 mock-activity-001（operationType=`file_edit`） | L1 卡片左侧显示 FileEdit 图标（`text-blue-400`） | ![op-file-edit](APOS-TC032-01-op-file-edit.png) |
| 2 | 定位 mock-activity-003（operationType=`command_execute`） | Terminal 图标（`text-purple-400`） | ![op-command](APOS-TC032-02-op-command.png) |
| 3 | 定位 mock-activity-002（operationType=`refactor`） | RefreshCw 图标（`text-indigo-400`） | ![op-refactor](APOS-TC032-03-op-refactor.png) |
| 4 | 定位 mock-activity-005（operationType=`dependency`） | Package 图标（`text-yellow-400`） | ![op-dependency](APOS-TC032-04-op-dependency.png) |
| 5 | 定位 mock-activity-006（operationType=`config_change`） | Settings 图标（`text-gray-400`） | ![op-config](APOS-TC032-05-op-config.png) |
| 6 | 定位 mock-activity-004（operationType=`delete`） | Trash2 图标（`text-red-400`） | ![op-delete](APOS-TC032-06-op-delete.png) |
| 7 | 添加 Activity（operationType=`file_create`） | FilePlus 图标（`text-green-400`） | ![op-file-create](APOS-TC032-07-op-file-create.png) |
| 8 | 添加 Activity（operationType=`test_run`） | TestTube 图标（`text-cyan-400`） | ![op-test-run](APOS-TC032-08-op-test-run.png) |
| 9 | 添加 Activity（operationType=`git_commit`） | GitCommit 图标（`text-orange-400`） | ![op-git-commit](APOS-TC032-09-op-git-commit.png) |
| 10 | 添加 Activity（operationType=`unknown`） | HelpCircle 图标（`text-gray-500`） | ![op-unknown](APOS-TC032-10-op-unknown.png) |

**通过标准**: OPERATION_ICON_MAP 中全部 10 种 OperationType 对应图标和颜色渲染正确

---

### P. 后端超时降级（P0）

---

#### TC-APOS-033: 10s 超时后返回 timeout check + blocked signal

**优先级**: P0
**前置条件**:
- Backend 服务运行在 8080 端口
- 构造一个会超时的检查场景

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 发送请求：`curl -X POST http://localhost:8080/api/verify/run-checks -H "Content-Type: application/json" -d '{"sessionId":"s1","operationId":"op-timeout","checks":["typescript","build"],"filePaths":["src/App.tsx"],"timeout":100}'` | 由于 timeout=100ms 极短，至少一个 check 超时 | ![timeout-request](APOS-TC033-01-timeout-request.png) |
| 2 | 检查响应 results 数组 | 已完成的 check 返回正常结果；超时的 check 返回 `{"check":"timeout","passed":false,"errors":[{"file":"","line":0,"column":0,"message":"Check timed out"}],"duration":100}` | ![timeout-result](APOS-TC033-02-timeout-result.png) |
| 3 | 检查响应 signal 字段 | 因存在 `passed=false` 的 check，`computeSignal()` 返回 `signal="blocked"`，`signalReason` 包含 "timeout 检查失败" | ![timeout-signal](APOS-TC033-03-timeout-signal.png) |
| 4 | 前端收到 verification_result 后 adapter 处理 | `mapRunChecksResponseToRiskAssessment()` 中 `response.results.some(r => r.check === 'timeout')` → `heuristic.truncated=true`，再经 `computeSignal()` → `blocked` | — |
| 5 | 验证 `DEFAULT_TIMEOUT_MS = 10_000` 上限 | 发送 `timeout:30000` 的请求，后端取 `Math.min(request.effectiveTimeout(), 10000)` = 10000ms | — |

**通过标准**: 超时 check 标记为 `timeout`+`passed=false`，signal 计算为 `blocked`，前端 `heuristic.truncated=true`

---

### Q. 后端并行执行（P1）

---

#### TC-APOS-034: 多个 check 并行执行验证

**优先级**: P1
**前置条件**:
- Backend 服务运行在 8080 端口

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 发送请求：`{"sessionId":"s1","operationId":"op-parallel","checks":["typescript","eslint","test_match"],"filePaths":["src/App.tsx"],"timeout":10000}` | 返回 HTTP 200 | — |
| 2 | 验证 results 数组 | 包含 3 个 check 结果（typescript、eslint、test_match），每个都有独立的 `duration` 字段 | ![parallel-results](APOS-TC034-01-parallel-results.png) |
| 3 | 验证 totalDuration | `totalDuration` 应小于各 check `duration` 之和（因为并行执行：`CompletableFuture.allOf()`） | ![parallel-duration](APOS-TC034-02-parallel-duration.png) |
| 4 | 验证部分超时场景 | 设置 `timeout:2000`，若某个 check 超时（如 build），其余已完成的 check 结果仍正常返回，超时的标记为 `check:"timeout"` | ![partial-timeout](APOS-TC034-03-partial-timeout.png) |

**通过标准**: 多个 check 通过 `CompletableFuture.supplyAsync()` 并行执行，totalDuration 小于各 duration 之和；部分超时不影响已完成结果

---

### R. verify_progress 消息（P1）

---

#### TC-APOS-035: WebSocket verify_progress 进度推送

**优先级**: P1
**前置条件**:
- WebSocket STOMP 连接已建立
- 订阅 `/user/queue/messages`

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 确认 stompClient.ts 白名单包含 `verify_progress` | `ALLOWED_TYPES` Set 中包含字符串 `'verify_progress'`（第 37 行） | — |
| 2 | 后端推送 `verify_progress` 类型消息（模拟或真实触发） | 前端 WebSocket 接收到消息，不被白名单过滤 | — |
| 3 | 验证 dispatch.ts 中 handler 处理 | `dispatch.ts` 第 348-351 行的 `'verify_progress'` handler 被调用，执行 `console.debug('[APOS] verify_progress:', d.operationId, d.check, d.progress)` | ![progress-dispatch](APOS-TC035-01-progress-dispatch.png) |
| 4 | 验证消息格式 | 消息包含：`type:"verify_progress"`, `sessionId`, `operationId`, `check`（当前正在执行的检查名）, `progress`（0-100 百分比）, `timestamp` | — |

**通过标准**: `verify_progress` 消息通过白名单校验、正确被 dispatch handler 接收处理，不触发错误

---

### S. 响应式断点切换（P1）

---

#### TC-APOS-036: useResponsive Hook 断点判断（PC/Tablet/Mobile）

**优先级**: P1
**前置条件**:
- 浏览器支持 DevTools 设备模拟

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 浏览器视口宽度设为 1280px（PC） | `useResponsive()` 返回 `{isDesktop: true, isTablet: false, isMobile: false}`（断点：`min-width: 1024px`） | ![responsive-desktop](APOS-TC036-01-responsive-desktop.png) |
| 2 | 浏览器视口宽度设为 800px（Tablet） | `useResponsive()` 返回 `{isDesktop: false, isTablet: true, isMobile: false}`（断点：`640px ≤ width ≤ 1023px`） | ![responsive-tablet](APOS-TC036-02-responsive-tablet.png) |
| 3 | 浏览器视口宽度设为 393px（Mobile） | `useResponsive()` 返回 `{isDesktop: false, isTablet: false, isMobile: true}`（断点：`max-width: 639px`） | ![responsive-mobile](APOS-TC036-03-responsive-mobile.png) |
| 4 | PC 模式下点击 Activity 展开 | 使用内联 L2 展开模式（ActivityCardL2 在列表内展开） | — |
| 5 | Mobile 模式下点击 Activity 触发详情 | 使用 MobileBottomSheet 模式替代 L3 Portal | ![responsive-mobile-sheet](APOS-TC036-04-responsive-mobile-sheet.png) |

**通过标准**: `useResponsive` Hook 基于 CSS media query 正确判断 3 种设备类型，UI 根据断点切换展示模式

---

### T. 虚拟滚动性能（P2）

---

#### TC-APOS-037: Virtuoso 虚拟滚动渲染大量 Activity

**优先级**: P2
**前置条件**:
- 通过 activityStore 添加 200 条 Activity 数据

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 向 activityStore 批量添加 200 条 Activity（使用不同时间戳和信号类型） | ActivityStream 面板显示列表，滚动条表示大量内容 | ![virtuoso-200](APOS-TC037-01-virtuoso-200.png) |
| 2 | 检查 DOM 中实际渲染的 Activity 卡片数量 | DOM 中仅渲染可视区域内的卡片（约 10-15 条），而非全部 200 条（Virtuoso `overscan={200}` 像素） | ![virtuoso-dom](APOS-TC037-02-virtuoso-dom.png) |
| 3 | 快速滚动列表 | 滚动流畅无明显卡顿，新进入视口的卡片即时渲染 | — |
| 4 | 滚动到列表底部 | 能正确显示时间最早的 Activity 条目 | — |
| 5 | 在 200 条数据中应用筛选（如 "已阻止"） | 筛选后 Virtuoso 正确重新计算列表高度，仅显示匹配条目 | ![virtuoso-filter](APOS-TC037-03-virtuoso-filter.png) |

**通过标准**: Virtuoso 组件正确实现虚拟滚动，DOM 节点数远小于数据总量，滚动流畅，筛选后重新渲染正常

---

### U. L3 Monaco Diff 懒加载（P1）

---

#### TC-APOS-038: L3 面板 Monaco DiffEditor 懒加载与 Fallback

**优先级**: P1
**前置条件**:
- 存在包含 `originalContent` 和 `modifiedContent` 的 Activity

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 通过 activityStore 添加一条包含 `originalContent: 'const x = 1;'` 和 `modifiedContent: 'const x = 2;'` 的 Activity | Activity 卡片出现在列表中 | — |
| 2 | 展开至 L2，点击 "详情 →" 进入 L3 | L3 Portal 打开，"Diff 预览" section 可见 | ![diff-section](APOS-TC038-01-diff-section.png) |
| 3 | 观察 Diff 区域加载过程 | 首次加载时显示 Suspense fallback（DiffFallback 组件：左右两栏 `<pre>` 标签显示 Original/Modified） | ![diff-fallback](APOS-TC038-02-diff-fallback.png) |
| 4 | 等待 Monaco 加载完成 | fallback 被替换为 Monaco DiffEditor（`@monaco-editor/react` DiffEditor 组件），主题 `vs-dark`，只读模式，并排显示 | ![diff-monaco](APOS-TC038-03-diff-monaco.png) |
| 5 | 验证 Monaco 配置 | `readOnly: true`、`minimap.enabled: false`、`fontSize: 12`、`scrollBeyondLastLine: false`、`renderSideBySide: true` | — |
| 6 | 展开不含 originalContent/modifiedContent 的 Activity 至 L3 | "Diff 预览" section 不显示（条件渲染：`activity.originalContent != null && activity.modifiedContent != null`） | ![diff-hidden](APOS-TC038-04-diff-hidden.png) |

**通过标准**: Monaco DiffEditor 通过 `lazy()` + `Suspense` 懒加载，显示 fallback 过渡态，配置参数正确，无 diff 数据时不渲染

---

### V. 环境变量初始化（P1）

---

#### TC-APOS-039: featureFlagStore 从 VITE_APOS_* 环境变量初始化

**优先级**: P1
**前置条件**:
- `.env.development` 中设置了 APOS 相关环境变量

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 确认 `.env.development` 内容：`VITE_APOS_ACTIVITY_STREAM=true`、`VITE_APOS_AI_INSIGHT=true`、`VITE_APOS_BATCH_REVIEW=true`、`VITE_APOS_RISK_HEATMAP=false`、`VITE_APOS_USE_MOCK=true` | 文件包含 5 个 VITE_APOS_* 变量 | — |
| 2 | 启动前端开发服务器 | Vite 将 VITE_* 变量注入到 `import.meta.env` | — |
| 3 | 打开浏览器 DevTools Console 执行：检查 featureFlagStore 初始状态 | `initializeFlags()` 读取 `import.meta.env.VITE_APOS_ACTIVITY_STREAM === 'true'` → `flags.APOS_ACTIVITY_STREAM = true` | ![env-init](APOS-TC039-01-env-init.png) |
| 4 | 验证 Feature Flag 面板状态与 env 一致 | Activity Stream=开、AI Insight=开、Batch Review=开、Risk Heatmap=关、Use Mock Data=开 | — |
| 5 | 验证 `APOS_FLAG_DEFAULTS` 全部为 `false` | `apos.ts` 第 163-169 行：所有默认值为 `false`，确保环境变量覆盖是唯一的 `true` 来源 | — |
| 6 | 修改 `.env.development` 中 `VITE_APOS_ACTIVITY_STREAM=false` 并重启 | Activity Tab 不在 Sidebar 中显示（Flag 从 env 读取为 false） | ![env-override](APOS-TC039-02-env-override.png) |

**通过标准**: `initializeFlags()` 正确从 `import.meta.env` 读取 `VITE_APOS_*` 变量并覆盖 `APOS_FLAG_DEFAULTS`

---

### W. useAPOSInitialization 工具类型推断（P1）

---

#### TC-APOS-040: inferOperationType 工具名→操作类型映射

**优先级**: P1
**前置条件**:
- APOS 初始化已完成（`useAPOSInitialization` hook 已执行）
- 通过 messageStore 模拟不同工具名的 toolCall 完成

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 触发 toolCall 完成：工具名 `"file_edit"` | 生成 Activity 的 `operationType = 'file_edit'`（匹配 `name.includes('file')` 或 `'edit'` 或 `'write'`） | ![infer-file-edit](APOS-TC040-01-infer-file-edit.png) |
| 2 | 触发 toolCall 完成：工具名 `"CreateFile"` | `operationType = 'file_create'`（匹配 `name.includes('create')` 或 `'new'`） | — |
| 3 | 触发 toolCall 完成：工具名 `"run_in_terminal"` | `operationType = 'command_execute'`（匹配 `'terminal'`） | ![infer-terminal](APOS-TC040-02-infer-terminal.png) |
| 4 | 触发 toolCall 完成：工具名 `"read_file"` | `operationType = 'unknown'`（匹配 `'read'` 路径返回 unknown） | — |
| 5 | 触发 toolCall 完成：工具名 `"vitest_run"` | `operationType = 'test_run'`（匹配 `name.includes('test')`） | — |
| 6 | 触发 toolCall 完成：工具名 `"grep_code"` | `operationType = 'unknown'`（匹配 `'grep'` 路径返回 unknown） | — |
| 7 | 验证重复工具调用不重复处理 | 同一 `toolUseId` 的 toolCall 完成后，`processedToolCallsRef` Set 记录该 ID，不会再次生成 Activity | ![infer-dedup](APOS-TC040-03-infer-dedup.png) |

**通过标准**: `inferOperationType()` 函数（`useAPOSInitialization.ts` L21-29）对常见工具名正确推断 OperationType，`processedToolCallsRef` 防重复

---

### X. SignalBadge loading 状态（P1）

---

#### TC-APOS-041: SignalBadge loading 旋转态及动态更新

**优先级**: P1
**前置条件**:
- 触发工具调用产生新 Activity（尚未收到验证结果）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 发送对话触发工具调用（如写文件），Activity 被添加到 activityStore | 新 Activity 的 `insight` 字段为 `undefined`（尚未关联 assessment） | — |
| 2 | 在 ActivityCardL3 中，signal 取值为 `activity.insight?.signal ?? 'loading'` | L1 卡片的 SignalBadge 显示灰色 Loader2 图标（`text-gray-400`）+ `animate-spin` 旋转动画 | ![signal-loading](APOS-TC041-01-signal-loading.png) |
| 3 | 后端完成验证并推送 `verification_result` | `useAPOSInitialization` 监听 insightStore.assessments 变化，调用 `attachInsight()`，Activity 的 `insight.signal` 被设置为实际值（如 `auto_approve`） | — |
| 4 | 观察 SignalBadge 变化 | 从灰色旋转（loading）平滑过渡为绿色 CheckCircle2（auto_approve），旋转动画消失 | ![signal-updated](APOS-TC041-02-signal-updated.png) |
| 5 | 验证中间态持续时间 | 从工具调用完成到收到 verification_result 期间（数秒），SignalBadge 持续显示 loading 状态 | — |

**通过标准**: SignalBadge 支持 `loading` 第五态（灰色旋转），工具调用完成后→验证结果到达前显示 loading，结果到达后实时更新为实际 signal

---

### Y. L3 弹窗命令输出展示（P0）

---

#### TC-APOS-042: 命令执行类 Activity L3 弹窗完整展示命令输出

**优先级**: P0
**前置条件**:
- 已通过工具调用执行命令（如 `ls -F`、`pwd`）
- Activity 卡片已出现在 ActivityStream 中
- Activity 的 `toolResult` 字段已填充

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 在 ActivityStream 中找到命令执行类 Activity（如"执行 ls -F"），展开 L2 | L2 展开，显示"建议审查"信号标签，无"验证进行中..." spinner（因 verificationStatus='skipped'） | ![cmd-l2](APOS-TC042-01-cmd-l2.png) |
| 2 | 点击 L2 中的"详情 →"按钮 | L3 Portal 全屏浮层打开 | ![cmd-l3-open](APOS-TC042-02-cmd-l3-open.png) |
| 3 | 检查 L3 弹窗内容区 | 显示"命令输出"标题（非"变更文件列表"），下方为终端风格输出区域 | ![cmd-output-section](APOS-TC042-03-cmd-output-section.png) |
| 4 | 检查终端输出组件结构 | 顶部：红绿黄三圆点 + `$ ls -F` 命令文本 + 右侧 EXIT 状态（绿色 "EXIT 0" 或红色 "EXIT ERROR"） | ![cmd-terminal-header](APOS-TC042-04-cmd-terminal-header.png) |
| 5 | 检查终端输出内容 | 深色背景（#1a1b26），等宽字体，显示命令完整输出（如目录列表），白色/灰色文字 | ![cmd-terminal-content](APOS-TC042-05-cmd-terminal-content.png) |
| 6 | 验证错误命令的展示 | 执行一个会报错的命令，L3 弹窗中 EXIT 状态显示红色 "EXIT ERROR"，输出文字为红色 | ![cmd-error-output](APOS-TC042-06-cmd-error-output.png) |
| 7 | 验证长输出截断 | 执行输出超过 200 行的命令，终端区域仅显示前 200 行，底部显示黄色截断提示"... 输出已截断（共 N 行，仅显示前 200 行）" | ![cmd-truncated](APOS-TC042-07-cmd-truncated.png) |

**通过标准**: 命令执行类 Activity 的 L3 弹窗正确显示 CommandOutputViewer 组件，包含命令名、退出码、完整输出内容、截断保护

---

#### TC-APOS-043: 命令执行类 Activity 验证状态自动设为 skipped

**优先级**: P1
**前置条件**:
- 执行命令类工具调用（如 `ls`、`pwd`、`cat` 等）
- Activity 卡片已生成

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 执行命令类工具调用（如 `pwd`），等待 Activity 生成 | ActivityStream 中出现新 Activity 卡片，operationType='command_execute' | ![cmd-activity](APOS-TC043-01-cmd-activity.png) |
| 2 | 展开该 Activity 至 L2 | L2 展开，**不显示**"确定性验证"区域和 spinner（因 verificationStatus='skipped'） | ![cmd-no-spinner](APOS-TC043-02-cmd-no-spinner.png) |
| 3 | 对比文件创建类 Activity（如"创建 4.md"）的 L2 | 文件创建类 Activity 的 L2 显示"确定性验证"区域（verificationStatus='pending' 时显示 spinner，完成后显示结果） | ![file-has-verify](APOS-TC043-03-file-has-verify.png) |
| 4 | 检查命令执行类 Activity 的按钮状态 | 若有 toolResult 且无 decision，批准/拒绝按钮可用；若无 toolResult，按钮置灰 | ![cmd-btn-state](APOS-TC043-04-cmd-btn-state.png) |

**通过标准**: 命令执行类 Activity 不触发确定性验证流程，verificationStatus 直接为 'skipped'，L2 不显示验证 spinner

---

#### TC-APOS-044: L3 弹窗按钮禁用三重判定全类型一致性

**优先级**: P0
**前置条件**:
- 有至少一个文件创建类 Activity 和一个命令执行类 Activity

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 打开文件创建类 Activity 的 L3 弹窗（changedFiles 非空） | 按钮状态根据三重判定：changedFiles 非空 ✓、verificationStatus 非 pending（若已完成/skipped）✓、decision 未定义 ✓ → 按钮可用 | ![file-btn-enabled](APOS-TC044-01-file-btn-enabled.png) |
| 2 | 打开命令执行类 Activity 的 L3 弹窗（toolResult 存在） | 按钮状态：toolResult 存在 ✓、verificationStatus='skipped' ✓、decision 未定义 ✓ → 按钮可用 | ![cmd-btn-enabled](APOS-TC044-02-cmd-btn-enabled.png) |
| 3 | 在 L3 中点击"批准"按钮 | 按钮执行批准，Activity.decision 变为 'approved' | ![approve-action](APOS-TC044-03-approve-action.png) |
| 4 | 再次打开同一 Activity 的 L3 弹窗 | 批准/拒绝按钮已置灰（decision 已定义），不可再次操作 | ![btn-after-decision](APOS-TC044-04-btn-after-decision.png) |
| 5 | 验证 L2 与 L3 按钮状态一致 | L2 卡片的批准/拒绝按钮与 L3 弹窗中的按钮状态完全一致（同时置灰或同时可用） | ![l2-l3-consistent](APOS-TC044-05-l2-l3-consistent.png) |

**通过标准**: 文件类和命令类 Activity 的 L3 弹窗按钮禁用逻辑统一，L2/L3 状态完全同步，decision 做出后不可重复操作

---

## 7 补充后测试用例总览

| 模块 | 用例数 | 优先级 | 编号范围 |
|------|--------|--------|----------|
| A. APOS Tab 基础功能 | 3 | P0 | TC-APOS-001 ~ 003 |
| B. ActivityCard 三层展示 | 4 | P0 | TC-APOS-004 ~ 007 |
| C. SignalBadge 信号系统 | 2 | P0 | TC-APOS-008 ~ 009 |
| D. 信号筛选 | 2 | P1 | TC-APOS-010 ~ 011 |
| E. 批量操作 | 4 | P1 | TC-APOS-012 ~ 015 |
| F. Feature Flag 面板 | 3 | P1 | TC-APOS-016 ~ 018 |
| G. 手机端适配 | 2 | P1 | TC-APOS-019 ~ 020 |
| H. 后端 API | 2 | P0 | TC-APOS-021 ~ 022 |
| I. 数据流转集成 | 2 | P0 | TC-APOS-023 ~ 024 |
| J. 异常场景 | 2 | P2 | TC-APOS-025 ~ 026 |
| **K. computeSignal 规则引擎** | **2** | **P0** | **TC-APOS-027 ~ 028** |
| **L. aposAdapters 数据转换** | **1** | **P0** | **TC-APOS-029** |
| **M. performRetention 清理策略** | **1** | **P1** | **TC-APOS-030** |
| **N. VerificationIcon 5 状态** | **1** | **P1** | **TC-APOS-031** |
| **O. OperationIcon 10 类型** | **1** | **P1** | **TC-APOS-032** |
| **P. 后端超时降级** | **1** | **P0** | **TC-APOS-033** |
| **Q. 后端并行执行** | **1** | **P1** | **TC-APOS-034** |
| **R. verify_progress 消息** | **1** | **P1** | **TC-APOS-035** |
| **S. 响应式断点切换** | **1** | **P1** | **TC-APOS-036** |
| **T. 虚拟滚动性能** | **1** | **P2** | **TC-APOS-037** |
| **U. L3 Monaco Diff 懒加载** | **1** | **P1** | **TC-APOS-038** |
| **V. 环境变量初始化** | **1** | **P1** | **TC-APOS-039** |
| **W. 工具类型推断** | **1** | **P1** | **TC-APOS-040** |
| **X. SignalBadge loading 态** | **1** | **P1** | **TC-APOS-041** |
| **Y. L3 弹窗命令输出展示** | **1** | **P0** | **TC-APOS-042** |
| **Z. 命令执行验证状态跳过** | **1** | **P1** | **TC-APOS-043** |
| **Z+. L3 按钮禁用三重判定** | **1** | **P0** | **TC-APOS-044** |
| **AA. Activity 后端持久化** | **8** | **P0~P1** | **TC-APOS-045 ~ 052** |
| **总计** | **52** | | |

---

### AA. Activity 后端持久化（P0）

---

#### TC-APOS-045: activities 表正确创建并结构完整

**优先级**: P0
**前置条件**:
- 三端服务启动完成
- V005 迁移已执行（Backend 启动时自动运行）
- 可访问 data.db 文件

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 执行 `sqlite3 backend/.ai-code-assistant/data.db ".tables"` | 输出包含 `activities` 表名 | ![tables](APOS-TC045-01-tables-list.png) |
| 2 | 执行 `sqlite3 backend/.ai-code-assistant/data.db "PRAGMA table_info(activities)"` | 输出包含 14 个字段：id, session_id, operation_type, summary, status, timestamp, duration, file_count, decision, tool_result_json, changed_files_json, insight_json, created_at, updated_at | ![schema](APOS-TC045-02-table-schema.png) |
| 3 | 执行 `sqlite3 backend/.ai-code-assistant/data.db ".indices activities"` | 输出包含 `idx_activities_session` | ![index](APOS-TC045-03-index.png) |
| 4 | 执行 `sqlite3 backend/.ai-code-assistant/data.db "SELECT sql FROM sqlite_master WHERE name='activities'"` | DDL 中包含 `REFERENCES sessions(id) ON DELETE CASCADE` 外键约束 | ![fk](APOS-TC045-04-foreign-key.png) |

**通过标准**: activities 表存在，包含 14 个字段，索引 idx_activities_session 存在，外键约束生效

---

#### TC-APOS-046: Activity 创建时通过 STOMP 同步到后端

**优先级**: P0
**前置条件**:
- 三端服务启动完成
- 已建立会话，WebSocket 连接正常
- APOS Tab 已激活

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 在 PromptInput 中输入工具调用命令（如 `创建文件 /tmp/test-activity.ts`）并发送 | 工具调用开始执行，Activity 卡片在 ActivityStream 中生成 | ![trigger](APOS-TC046-01-trigger-tool.png) |
| 2 | 等待 Activity 生成完成（卡片出现） | ActivityStream 显示新的 Activity 卡片，包含正确的 operationType 和 summary | ![activity-card](APOS-TC046-02-activity-card.png) |
| 3 | 执行 `sqlite3 backend/.ai-code-assistant/data.db "SELECT id, session_id, operation_type, summary, status FROM activities ORDER BY timestamp DESC LIMIT 1"` | 最新一条记录的 operation_type 、summary 与前端显示一致 | ![db-verify](APOS-TC046-03-db-record.png) |
| 4 | 对比前端 Activity 卡片上的 id 与数据库中的 id | 两者完全一致 | ![id-match](APOS-TC046-04-id-match.png) |

**通过标准**: 前端生成的 Activity 通过 STOMP `/app/activity-save` 端点成功保存到后端数据库，字段完全一致

---

#### TC-APOS-047: Activity 验证结果通过 STOMP 同步到后端

**优先级**: P0
**前置条件**:
- 已有 Activity 且验证流程完成（insight 已生成）
- 验证 API 正常返回

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 触发文件操作类工具调用（如创建文件），等待 Activity 生成及验证完成 | Activity 卡片生成，SignalBadge 显示最终信号（非 pending） | ![verify-done](APOS-TC047-01-verify-complete.png) |
| 2 | 展开 Activity 至 L2，确认显示验证结果（非 spinner） | L2 展示“确定性验证”区域内容，包含检查项结果 | ![l2-insight](APOS-TC047-02-l2-insight.png) |
| 3 | 执行 `sqlite3 backend/.ai-code-assistant/data.db "SELECT id, insight_json FROM activities ORDER BY timestamp DESC LIMIT 1"` | insight_json 字段非空，包含 JSON 格式的验证结果数据 | ![db-insight](APOS-TC047-03-db-insight.png) |
| 4 | 解析 insight_json 内容，对比前端 L2 显示的验证结果 | JSON 中的 signal、checks 等字段与前端 UI 一致 | ![insight-match](APOS-TC047-04-insight-match.png) |

**通过标准**: 验证完成后，前端通过 STOMP `/app/activity-update` 端点将 insight 同步到后端，insight_json 字段正确更新

---

#### TC-APOS-048: Activity 审批决定通过 STOMP 同步到后端

**优先级**: P0
**前置条件**:
- Activity 已生成且按钮可用（三重判定通过）
- decision 未定义（未做过审批）

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 展开一个 Activity 至 L2，确认批准/拒绝按钮可用 | 按钮未置灰，可点击 | ![btn-enabled](APOS-TC048-01-btn-enabled.png) |
| 2 | 点击“批准”按钮 | Activity 卡片显示批准标记（✓），按钮置灰 | ![approved](APOS-TC048-02-approved.png) |
| 3 | 执行 `sqlite3 backend/.ai-code-assistant/data.db "SELECT id, decision FROM activities ORDER BY timestamp DESC LIMIT 1"` | decision 字段值为 `approved` | ![db-decision-approved](APOS-TC048-03-db-approved.png) |
| 4 | 对另一个 Activity 点击“拒绝”按钮 | Activity 卡片显示拒绝标记（✗） | ![rejected](APOS-TC048-04-rejected.png) |
| 5 | 执行 `sqlite3 backend/.ai-code-assistant/data.db "SELECT id, decision FROM activities WHERE decision='rejected'"` | 对应记录的 decision 字段值为 `rejected` | ![db-decision-rejected](APOS-TC048-05-db-rejected.png) |

**通过标准**: 前端点击批准/拒绝按钮后，通过 STOMP `/app/activity-update` 端点将 decision 同步到后端，数据库字段正确更新

---

#### TC-APOS-049: 会话恢复时 session_restored 推送包含 activities 数据

**优先级**: P0
**前置条件**:
- 后端 activities 表已有数据（当前会话至少有 2 条 Activity）
- 另有一个可切换的会话

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 记录当前会话（Session A）的 Activity 数量和内容 | ActivityStream 显示 N 条 Activity | ![session-a-before](APOS-TC049-01-session-a-before.png) |
| 2 | 从 Sidebar 切换到另一个会话（Session B） | ActivityStream 清空或显示 Session B 的 Activity | ![session-b](APOS-TC049-02-session-b.png) |
| 3 | 切换回 Session A | WebSocket 发送 bind-session 请求，后端推送 session_restored 消息 | ![switch-back](APOS-TC049-03-switch-back.png) |
| 4 | 检查浏览器 DevTools Network 中 WebSocket 消息 | session_restored payload 中包含 `activities` 数组，元素数量与步骤 1 一致 | ![ws-payload](APOS-TC049-04-ws-payload.png) |
| 5 | 检查 ActivityStream 显示 | Activity 卡片完整恢复，包括 operationType、summary、signal、decision 状态 | ![restored](APOS-TC049-05-restored.png) |

**通过标准**: 会话切换后 session_restored 消息包含 activities 数组，前端正确恢复 Activity 数据显示

---

#### TC-APOS-050: 页面刷新后 Activity 数据从后端恢复

**优先级**: P0
**前置条件**:
- 当前会话有 Activity 数据（至少 2 条，其中至少 1 条已有 decision）
- APOS Tab 已激活且卡片可见

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 记录当前 ActivityStream 的 Activity 数量、各卡片 summary、decision 状态 | 截图记录刷新前状态 | ![before-refresh](APOS-TC050-01-before-refresh.png) |
| 2 | 按 F5 刷新页面，等待重新加载完成 | 页面完整重新加载，WebSocket 重新连接 | ![refreshing](APOS-TC050-02-refreshing.png) |
| 3 | 切换到 APOS Tab，检查 ActivityStream | Activity 数量与刷新前一致 | ![after-refresh](APOS-TC050-03-after-refresh.png) |
| 4 | 逐一对比各 Activity 卡片的 summary、operationType、signal | 内容与刷新前完全一致 | ![content-match](APOS-TC050-04-content-match.png) |
| 5 | 检查已有 decision 的 Activity | decision 状态保留（如 'approved' 仍显示批准标记，按钮置灰） | ![decision-preserved](APOS-TC050-05-decision-preserved.png) |

**通过标准**: 页面刷新后 Activity 数据通过 session_restored 从后端完整恢复，包括数量、内容、decision 状态

---

#### TC-APOS-051: 会话删除时 Activity 数据级联删除

**优先级**: P1
**前置条件**:
- 当前会话中有至少 1 条 Activity
- 知道当前会话的 session_id

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 执行 `sqlite3 backend/.ai-code-assistant/data.db "SELECT COUNT(*) FROM activities WHERE session_id='<SESSION_ID>'"` | 返回 > 0（确认有 Activity 数据） | ![before-delete](APOS-TC051-01-before-delete.png) |
| 2 | 在前端删除该会话（点击会话列表的删除按钮） | 会话从列表中消失 | ![delete-session](APOS-TC051-02-delete-session.png) |
| 3 | 执行 `sqlite3 backend/.ai-code-assistant/data.db "SELECT COUNT(*) FROM activities WHERE session_id='<SESSION_ID>'"` | 返回 0（所有关联 Activity 已级联删除） | ![after-delete](APOS-TC051-03-after-delete.png) |
| 4 | 执行 `sqlite3 backend/.ai-code-assistant/data.db "SELECT COUNT(*) FROM sessions WHERE id='<SESSION_ID>'"` | 返回 0（父表记录也已删除） | ![session-deleted](APOS-TC051-04-session-gone.png) |

**通过标准**: 删除会话后，activities 表中该 session_id 的所有记录通过 ON DELETE CASCADE 自动清除

---

#### TC-APOS-052: Activity 后端同步失败时前端不崩溃

**优先级**: P1
**前置条件**:
- WebSocket 连接正常或已断开
- 前端可正常操作

**测试步骤**:

| 步骤 | 操作 | 预期结果 | 截图 |
|------|------|----------|------|
| 1 | 停止后端服务（模拟后端不可用） | 前端 WebSocket 连接断开 | ![backend-down](APOS-TC052-01-backend-down.png) |
| 2 | 在前端 PromptInput 中输入工具调用命令并发送（如通过 Mock 模拟工具调用） | Activity 在前端正常生成，ActivityStream 显示卡片 | ![activity-generated](APOS-TC052-02-activity-generated.png) |
| 3 | 打开浏览器 DevTools Console | Console 中出现 `[ActivityAPI] save failed:` 警告日志，无 TypeError 或未捕获异常 | ![console-warn](APOS-TC052-03-console-warn.png) |
| 4 | 验证前端 UI 状态 | 页面未崩溃，ActivityStream 仍可交互（展开/收起、点击等操作正常） | ![ui-stable](APOS-TC052-04-ui-stable.png) |
| 5 | 重新启动后端服务，等待 WebSocket 自动重连 | WebSocket 重新连接成功 | ![reconnected](APOS-TC052-05-reconnected.png) |
| 6 | 触发新的工具调用，生成新 Activity | 新 Activity 正常同步到后端，数据库可查到新记录 | ![sync-after-reconnect](APOS-TC052-06-sync-restored.png) |

**通过标准**: 后端不可用时前端 Activity 仍正常生成和显示（仅打印 console.warn），重连后新 Activity 可正常同步

---

## 8 覆盖度交叉对照表

| 源码文件 | 核心功能 | 覆盖状态 | 测试用例 |
|----------|----------|----------|----------|
| `types/apos.ts` | OperationType / Signal / VerificationStatus 类型 | ✅ | TC-008, TC-031, TC-032 |
| `types/apos.ts` | SIGNAL_CONFIG 常量 | ✅ | TC-008 |
| `types/apos.ts` | APOS_FLAG_DEFAULTS / APOS_FLAG_DEPENDENCIES | ✅ | TC-016, TC-018, TC-039 |
| `types/apos.ts` | DEFAULT_RETENTION_CONFIG | ✅ | TC-030 |
| `types/apos.ts` | VERIFICATION_CONFIG | ✅ | TC-033 |
| `types/apos.ts` | ActivityData.toolResult 字段 | ✅ | TC-042, TC-043 |
| `store/activityStore.ts` | addActivity / updateActivity / attachInsight | ✅ | TC-003, TC-023, TC-024 |
| `store/activityStore.ts` | toggleSelect / selectAll / selectAllSafe / clearSelection | ✅ | TC-013, TC-015 |
| `store/activityStore.ts` | performRetention | ✅ | TC-030 |
| `store/activityStore.ts` | updateActivity Immer 深层追踪 | ✅ | TC-043（间接） |
| `store/activityStore.ts` | setBatchMode | ✅ | TC-012 |
| `store/insightStore.ts` | addAssessment / updateAssessment | ✅ | TC-022, TC-024 |
| `store/insightStore.ts` | computeSignal() | ✅ | TC-027, TC-028 |
| `store/insightStore.ts` | computeSessionSummary | ✅ | TC-027（间接） |
| `store/featureFlagStore.ts` | setFlag / toggleFlag / resetToDefaults | ✅ | TC-016, TC-017, TC-018 |
| `store/featureFlagStore.ts` | getMissingDependencies / getCascadeDisableTargets | ✅ | TC-018 |
| `store/featureFlagStore.ts` | initializeFlags() | ✅ | TC-039 |
| `services/insightService.ts` | createInsightService (factory) | ✅ | TC-039（间接） |
| `services/mockInsightService.ts` | MockInsightService | ✅ | TC-003（间接） |
| `mocks/apos/mockActivities.ts` | 6 条 Mock 数据 | ✅ | TC-003, TC-004 |
| `utils/aposAdapters.ts` | mapRunChecksResponseToRiskAssessment | ✅ | TC-029 |
| `hooks/useResponsive.ts` | isDesktop / isTablet / isMobile | ✅ | TC-036 |
| `hooks/useAPOSInitialization.ts` | Mock 加载 + toolCall 监听 + assessment 关联 | ✅ | TC-003, TC-023, TC-024, TC-040 |
| `hooks/useAPOSInitialization.ts` | inferOperationType() | ✅ | TC-040 |
| `components/apos/SignalBadge.tsx` | 4 信号 + loading + tooltip | ✅ | TC-008, TC-009, TC-041 |
| `components/apos/VerificationIcon.tsx` | 5 种验证状态 | ✅ | TC-031 |
| `components/apos/OperationIcon.tsx` | 10 种操作图标 | ✅ | TC-032 |
| `components/apos/ActivityCardL1.tsx` | 52px 紧凑卡片 + checkbox | ✅ | TC-004, TC-012 |
| `components/apos/ActivityCardL2.tsx` | framer-motion 动画 + 文件列表 + 操作按钮 | ✅ | TC-005 |
| `components/apos/ActivityCardL3.tsx` | Portal + ESC + 遮罩 + Monaco Diff | ✅ | TC-006, TC-026, TC-038 |
| `components/apos/ActivityCardL3.tsx` | CommandOutputViewer 终端输出组件 | ✅ | TC-042 |
| `components/apos/ActivityStream.tsx` | Virtuoso 虚拟滚动 + 筛选条 + 空状态 | ✅ | TC-010, TC-025, TC-037 |
| `components/apos/BatchOperationBar.tsx` | 全选 + 批准 + 拒绝 + 取消 | ✅ | TC-012 ~ TC-015 |
| `components/apos/FeatureFlagPanel.tsx` | Flag 列表 + 开关 + 依赖 + 重置 | ✅ | TC-016 ~ TC-018 |
| `components/apos/MobileBottomSheet.tsx` | 拖拽 + spring 动画 + ESC + 安全区域 | ✅ | TC-019, TC-020 |
| `api/stompClient.ts` | verification_result + verify_progress 白名单 | ✅ | TC-022, TC-035 |
| `api/dispatch.ts` | 2 条 handler 路由 | ✅ | TC-022, TC-035 |
| `components/layout/Sidebar.tsx` | apos TabType 条件渲染 | ✅ | TC-001, TC-002 |
| `.env.development` / `.env.production` | VITE_APOS_* 变量 | ✅ | TC-039 |
| `package.json` | framer-motion 依赖 | ✅ | TC-005（间接） |
| `App.tsx` | useAPOSInitialization 调用 | ✅ | TC-023 |
| `VerifyController.java` | POST /api/verify/run-checks + 参数校验 | ✅ | TC-021, TC-033 |
| `VerifyCheckService.java` | 并行执行 + 10s 超时 + computeSignal + WS 推送 | ✅ | TC-021, TC-022, TC-033, TC-034 |
| `RunChecksRequest.java` | 请求字段 | ✅ | TC-021 |
| `RunChecksResponse.java` | 响应字段 + 嵌套类型 | ✅ | TC-021, TC-029 |
| `config/database/V005_AddActivitiesTable.java` | activities 表 DDL + 索引 | ✅ | TC-045 |
| `service/ActivityRepository.java` | upsert / findBySessionId / updateDecision / updateInsight | ✅ | TC-046, TC-047, TC-048 |
| `websocket/WebSocketController.java` | /app/activity-save + /app/activity-update 端点 | ✅ | TC-046, TC-047, TC-048 |
| `websocket/WebSocketController.java` | handleBindSession 推送 activities | ✅ | TC-049, TC-050 |
| `api/activityApi.ts` | saveActivity / updateActivityDecision / updateActivityInsight | ✅ | TC-046, TC-047, TC-048 |
| `api/dispatch.ts` | handleSessionRestore activities 恢复 | ✅ | TC-049, TC-050 |
| `store/activityStore.ts` | approveActivity / rejectActivity 后端同步 | ✅ | TC-048 |

---

## 风险修复专项 E2E 测试（TC-APOS-E2E-01 ~ TC-APOS-E2E-10）

> 基于 APOS Phase 1 风险修复实施方案（v2.0）的回归测试矩阵，新增 10 个 E2E 测试用例，覆盖全部 10 个已修复风险点。
> 文档生成日期：2026-05-11

### 覆盖关系矩阵

| 测试编号 | 风险点 | 优先级 | 验证维度 |
|----------|--------|:------:|----------|
| TC-APOS-E2E-01 | #1, #2, #10 | P0 | 完整工具调用流程 + input 回溯更新 |
| TC-APOS-E2E-02 | #5 | P1 | 批量操作实装验证 |
| TC-APOS-E2E-03 | #6 | P2 | Feature Flag 初始化安全性 |
| TC-APOS-E2E-04 | #4, #8 | P1 | 会话切换后数据隔离 |
| TC-APOS-E2E-05 | #10, #3 | P1 | 验证 API 失败与降级 |
| TC-APOS-E2E-06 | #9 | P2 | Signal Badge 未知状态处理 |
| TC-APOS-E2E-07 | #7 | P2 | L2 卡片验证进行中提示 |
| TC-APOS-E2E-08 | #8 | P1 | MobileBottomSheet 实时数据同步 |
| TC-APOS-E2E-09 | 全部 | P0 | 级联故障链完整验证 |
| TC-APOS-E2E-10 | #1, #2, #4 | P1 | 并发工具调用与竞态 |

---

### TC-APOS-E2E-01: 完整工具调用流程（验证 input 回溯更新）

**风险点**: #1 tool_use_input 回溯更新  
**优先级**: P0  

#### 前置条件
- APOS Tab 已激活
- 后端连接正常
- 消息队列清空

#### 测试步骤
1. 打开浏览器开发者工具的 Network/Console 标签
2. 在 PromptInput 中输入命令：`edit /tmp/test.ts` 并发送
3. 等待关键步骤：
   - 第一个 WS 消息：`tool_use_start` (含 toolUseId=T1, toolName='edit', input={}) → 观察 Activity 生成时 fileCount=0
   - 第二个 WS 消息：`tool_use_input` (toolUseId=T1, input={file_path: '/tmp/test.ts'}) → 观察 Activity 自动更新 fileCount=1, summary 更新为含文件路径
   - 第三个 WS 消息：`tool_result` (toolUseId=T1, content='修改完成')
4. 展开 APOS Activity Stream 的对应卡片，验证文件名显示

#### 预期结果
- ✅ Activity 初始状态：summary='编辑', fileCount=0, changedFiles=[]
- ✅ 收到 tool_use_input 后：summary 自动更新为 '编辑 .../test.ts', fileCount=1
- ✅ 日志中出现 `[APOS-DEBUG] Activity backfill-updated:` 消息
- ✅ L2 卡片展开后显示文件 '/tmp/test.ts'

#### 验证的风险点
- #1 tool_use_input 回溯更新机制
- #2 null-safety（input 从 {} 变为有值对象）
- #10 后端连接健壮性

#### 截图验证点
| 截图 | 说明 |
|------|------|
| entry | APOS Tab 激活后的初始状态 |
| params | PromptInput 输入命令前的状态 |
| loading | 消息发送后 Activity 生成中 |
| result | Activity 最终状态（含更新后的文件名） |

---

### TC-APOS-E2E-02: 批量操作实装验证

**风险点**: #5 BatchOperationBar 批量操作实装  
**优先级**: P1  

#### 前置条件
- APOS Tab 激活
- ActivityStream 中至少 3 个 Activity（可通过连续工具调用生成）

#### 测试步骤
1. 在 ActivityStream 中选中 3 个 Activity（点击各卡片的 checkbox）
2. 验证 BatchOperationBar 显示，count=3
3. 点击"批量批准"按钮
4. 逐个验证 3 个 Activity 的 decision 字段变为 'approved'
5. 验证 BatchOperationBar 消失，selectedIds 清空，batchMode=false

#### 预期结果
- ✅ 选择后 BatchOperationBar 出现，显示 "已选 3 个"
- ✅ 点击"批量批准"后，3 个 Activity 的卡片右侧显示 ✓ 批准标记
- ✅ BatchOperationBar 自动收起
- ✅ store 中 selectedIds 为空集

#### 测试变体（TC-APOS-E2E-02-reject）
同上，但点击"批量拒绝"，验证 decision='rejected'

#### 截图验证点
| 截图 | 说明 |
|------|------|
| entry | APOS Tab 初始有多个 Activity |
| params | 选中 3 个后 BatchOperationBar 展示 |
| result | 批量操作完成后各 Activity 显示决策状态 |

---

### TC-APOS-E2E-03: Feature Flag 初始化安全性

**风险点**: #6 Feature Flag 初始化安全  
**优先级**: P2  

#### 前置条件
- 环境变量清理：`unset VITE_APOS_ENABLED VITE_APOS_MOCK_INSIGHTS`
- 重新启动 Frontend dev server
- Chrome DevTools Console 打开

#### 测试步骤
1. 加载应用主页
2. 检查 Console 中是否有 `[FeatureFlag] Failed to read env variables` 警告
3. 检查 APOS Tab 是否仍可见（使用默认值）
4. 切换到左侧 Sidebar，验证 APOS Tab 依然在列表中

#### 预期结果
- ✅ Console 中无 TypeError 异常
- ✅ 若有警告日志，内容为 `[FeatureFlag] Failed to read env variables, using defaults`
- ✅ APOS Tab 使用默认 flag 值（通常为 true）且可见
- ✅ 功能完全可用

#### 截图验证点
| 截图 | 说明 |
|------|------|
| entry | 应用首次加载 |
| result | APOS Tab 显示在 Sidebar 中 |

---

### TC-APOS-E2E-04: 会话切换后数据隔离

**风险点**: #4 会话隔离竞态  
**优先级**: P1  

#### 前置条件
- 至少两个已保存的会话（或通过手动创建）
- APOS 中当前会话有 5 个 Activity

#### 测试步骤
1. 在 Session1 中观察 ActivityStream：记录 Activity ID 集合 Set1
2. 选中其中 2 个 Activity（记录 selected IDs）
3. 展开某个 Activity 到 L3（详情弹窗打开）
4. 从 Sidebar 切换到 Session2
5. 验证 ActivityStream 清空（或显示 Session2 的 Activity）
6. 验证 selectedIds 为空
7. 切换回 Session1，验证原来的 5 个 Activity 恢复，但 selectedIds 仍为空

#### 预期结果
- ✅ 切换到 Session2 后，旧 Activity 完全不显示
- ✅ selectedIds 清空，BatchOperationBar 消失
- ✅ L3 弹窗自动关闭
- ✅ 切换回 Session1，Activity 恢复，但不恢复选中状态

#### 截图验证点
| 截图 | 说明 |
|------|------|
| entry | Session1 的 Activity Stream 初始状态 |
| params | 选中后的状态，BatchOperationBar 显示 |
| loading | 切换会话过程中的过渡状态 |
| result | Session2 的清洁状态 |

---

### TC-APOS-E2E-05: 验证 API 失败与降级

**风险点**: #10 验证 API 错误处理与降级  
**优先级**: P1  

#### 前置条件
- 后端暂停（或 Mock 验证 API 返回 500）
- APOS 中生成一个含 changedFiles 的 Activity

#### 测试步骤
1. 生成一个 Activity，观察其 insight.signal 初始值
2. 模拟验证 API 失败（两种方法）：
   - **方法 A**: 暂停后端，Activity 会尝试调用验证 API → 超时
   - **方法 B**: Mock fetch 让 `/api/verify/run-checks` 返回 HTTP 500
3. 等待 Activity 更新
4. 验证 Activity 的 signal 变为 'manual_required'
5. 验证 L2 卡片显示"验证服务异常，需手动验证"提示信息

#### 预期结果
- ✅ Activity 不陷入 pending 状态
- ✅ signal 自动更新为 'manual_required'
- ✅ summary 包含"验证服务异常"或 HTTP 状态码信息
- ✅ L2 卡片不显示验证结果（因 assessment 为 null），而是显示降级提示

#### 截图验证点
| 截图 | 说明 |
|------|------|
| entry | API 失败前的 Activity 状态 |
| loading | 验证进行中（若有 spinner） |
| result | 降级后的最终状态（signal 与提示文本） |

---

### TC-APOS-E2E-06: Signal Badge 未知状态处理

**风险点**: #9 SignalBadge 完整状态覆盖  
**优先级**: P2  

#### 前置条件
- 前端代码已应用 FALLBACK_CONFIG 修复
- DevTools Console 打开

#### 测试步骤
1. 通过 DevTools Console 直接执行 JavaScript：
```javascript
const store = useActivityStore.getState();
const actId = Array.from(store.activities.keys())[0];
store.updateActivity(actId, {
  insight: { ...store.activities.get(actId).insight, signal: 'unknown_signal' }
});
```
2. 在 ActivityStream 中观察对应 Activity 的 Signal Badge
3. 验证 Badge 显示但不崩溃

#### 预期结果
- ✅ 页面不出现 JavaScript 错误
- ✅ Signal Badge 渲染成功
- ✅ Badge 显示为灰色（fallback 颜色）+ "未知状态" 标签
- ✅ 不显示任何 TypeError

#### 截图验证点
| 截图 | 说明 |
|------|------|
| result | Activity 卡片的 Signal Badge 显示为灰色未知状态 |

---

### TC-APOS-E2E-07: L2 卡片验证进行中提示

**风险点**: #7 L2 卡片验证状态提示  
**优先级**: P2  

#### 前置条件
- 生成一个 Activity 并触发验证
- 验证 API 响应延迟 > 2 秒（可通过 Mock 控制）

#### 测试步骤
1. 生成 Activity 后立即展开 L2 卡片
2. 在验证结果返回前（2 秒内）观察 L2 卡片的"确定性验证"区域
3. 验证显示 Loader2 icon + "验证进行中..." 文本
4. 等待验证结果到达（约 2-3 秒）
5. 验证区域自动显示验证结果，spinner 消失

#### 预期结果
- ✅ L2 展开时，若 assessment 未就绪，显示 loading spinner
- ✅ Loader2 icon 动画旋转
- ✅ 验证完成后自动切换到结果展示
- ✅ 无任何 React warning

#### 截图验证点
| 截图 | 说明 |
|------|------|
| entry | L2 卡片展开，验证未完成时的状态 |
| loading | Spinner 可见，"验证进行中..." 文本 |
| result | 验证完成后的结果显示 |

---

### TC-APOS-E2E-08: MobileBottomSheet 实时数据同步

**风险点**: #8 MobileBottomSheet 状态同步  
**优先级**: P1  

#### 前置条件
- 设备分辨率切换为移动 (375×812) 或使用 DevTools 移动模式
- APOS 中有一个 Activity

#### 测试步骤
1. 点击 Activity 卡片打开 MobileBottomSheet
2. 观察初始状态的 signal、decision 等信息
3. 在 BottomSheet 打开的同时，通过 DevTools Console 更新该 Activity：
```javascript
const store = useActivityStore.getState();
const actId = Array.from(store.activities.keys())[0];
store.updateActivity(actId, { decision: 'approved' });
```
4. 验证 BottomSheet 中的内容实时更新（不需要关闭重新打开）

#### 预期结果
- ✅ BottomSheet 打开后可订阅 store 变化
- ✅ 后台 update 后，BottomSheet 内容立即刷新
- ✅ decision 从 'pending' 变为 'approved'，UI 对应更新

#### 测试变体（TC-APOS-E2E-08-signal）
同上，但更新 signal 或 assessment，验证 badge 和验证结果动态更新

#### 截图验证点
| 截图 | 说明 |
|------|------|
| entry | BottomSheet 打开初始状态 |
| result | 后台更新后的状态（decision 或 signal 改变） |

---

### TC-APOS-E2E-09: 级联故障链完整验证

**风险点**: 全部 10 个风险点  
**优先级**: P0  

#### 前置条件
- 所有 10 个修复已应用
- 三端系统（Backend、Python、Frontend）正常运行

#### 测试步骤
1. **第一阶段**：启用 APOS（Feature Flag），验证 Tab 可见
2. **第二阶段**：执行工具调用，验证 Activity 生成 + input 回溯更新
3. **第三阶段**：观察验证流程，验证 spinner 显示 + API 成功/失败都有正确反馈
4. **第四阶段**：验证结果返回后检查 signal + L2 结果展示
5. **第五阶段**：进行批量操作，验证 decision 更新
6. **第六阶段**：切换会话，验证数据清理
7. **第七阶段**：通过 Signal Badge 展示各种 signal 状态（包括未知状态）

#### 预期结果
- ✅ 完整数据流：工具调用 → Activity → 验证 → 结果 → 决策 → 页面更新
- ✅ 无任何 JavaScript 异常或 React warning
- ✅ 所有降级路径生效（验证失败、输入为空、session 切换等）
- ✅ 跨模块交互正确（messageStore → activityStore → insightStore）

#### 截图验证点
每个关键阶段各一张截图（共 7 张）

---

### TC-APOS-E2E-10: 并发工具调用与竞态

**风险点**: #1, #2, #4（竞态相关）  
**优先级**: P1  

#### 前置条件
- APOS 正常运行
- DevTools Network 标签打开

#### 测试步骤
1. 并发发送 3 个工具调用，间隔 < 100ms：
   - Tool 1: `edit /a.ts`
   - Tool 2: `bash rm /b.ts`
   - Tool 3: `read /c.ts`
2. 快速观察 Network 标签中的 WS 消息：确认 3 个 tool_use_start 在 100ms 内到达
3. 观察 3 个 Activity 同时生成，各自独立：
   - 不同的 toolUseId
   - 不同的 changedFiles
   - 不同的 operationType
4. 当 tool_use_input 消息乱序到达时，验证各 Activity 的 input 不混淆

#### 预期结果
- ✅ 3 个 Activity 并行生成，互不影响
- ✅ 各自的 input、changedFiles 正确关联
- ✅ 回溯更新逻辑只更新对应的 Activity
- ✅ 最终所有 3 个 Activity 数据完整无误

#### 截图验证点
| 截图 | 说明 |
|------|------|
| entry | 发送前的初始状态 |
| loading | 3 个 Activity 同时生成的中间状态 |
| result | 最终状态，3 个 Activity 都数据完整 |

---

## 现有 E2E 测试用例修改说明

> 以下现有测试用例需要在风险修复后进行增强，以覆盖 APOS 相关功能。

### 修改 1: tc-fe-005-tool-result.spec.ts

**修改原因**：原有测试只验证 ToolResultDisplay 的 DOM 渲染，缺少对 APOS Activity 生成的验证。

**修改后新增验证点**：
```typescript
test('should render tool result and generate APOS Activity', async ({ page }) => {
  // 原有：发送工具调用（edit /tmp/test.ts）
  // 原有：验证 ToolResultDisplay 渲染 ✓
  // 新增：验证 APOS ActivityStream 中出现对应 Activity ✓
  // 新增：验证 Activity 的 changedFiles 包含 /tmp/test.ts ✓
  // 新增：验证 Activity 的 operationType = 'file_edit' ✓
});
```

**影响评估**：

| 维度 | 修改前 | 修改后 |
|------|--------|--------|
| 验证点 | 2 个（ToolResult 渲染） | 5 个（+Activity 生成、+文件、+类型） |
| 依赖 Tab | Message List | Message List + APOS Tab |
| 涉及风险点 | 无 | #1, #2, #6 |

---

### 修改 2: frontend-e2e-full.spec.ts

**修改原因**：现有"全量功能"测试未覆盖 APOS Tab，缺少会话隔离和批量操作验证。

**修改后新增步骤**：
- 保持原有"创建会话 → 发送消息 → 工具执行"流程
- 新增：在工具执行后，验证 APOS 中生成了对应 Activity
- 新增：进行批量批准操作
- 新增：切换到另一个会话，验证旧 Activity 消失、selectedIds 清空
- 新增：切换回第一个会话，验证原 Activity 仍存在

**影响评估**：

| 维度 | 修改前 | 修改后 |
|------|--------|--------|
| 测试时长 | ~30s | ~45-50s（+验证链路） |
| 验证点数 | 8 个 | 15 个 |
| 涉及风险点 | TC-FE-005 范围 | +#4（会话隔离）+#5（批量操作） |

---

### 修改 3: tc-fe-003-permission.spec.ts

**修改原因**：权限审批流程（PermissionBubbleDialog）与 APOS Activity 的 decision 更新流程相关。

**修改后新增验证点**：
- 显示权限对话框
- 点击"批准"按钮
- **新增**：验证对应的 Activity 的 decision 变为 'approved'
- **新增**：验证 Activity 的 decisionTimestamp 被更新
- **新增**：在 APOS Tab 中验证 Activity 卡片显示批准标记

---

## 验证链路完整性测试方案

### 端到端数据流转验证拓扑

```
工具调用触发
  ↓
tool_use_start 消息 (WS)
  ↓ [风险点 #1 - input 可能为空]
messageStore.activeToolCalls 更新
  ↓ [风险点 #2 - null-safety]
useAPOSInitialization Hook 监听 (职责 1)
  ↓
Activity 生成 (operationType, summary, changedFiles)
  ↓ [风险点 #4 - 会话隔离]
activityStore.activities Map 插入
  ↓
tool_use_input 消息到达 (晚到/乱序)
  ↓ [风险点 #1 - 回溯更新]
useAPOSInitialization Hook 订阅 (职责 3)
  ↓
Activity 回溯更新 (changedFiles、summary 补全)
  ↓
tool_result 消息到达
  ↓
triggerVerification 异步调用 [风险点 #10]
  ↓
验证 API 调用 /api/verify/run-checks [风险点 #3]
  ↓ [成功路径]
mapRunChecksResponseToRiskAssessment [风险点 #3]
  ↓
insightStore.assessments 更新
  ↓
useAPOSInitialization Hook 监听 (职责 2)
  ↓
Activity.insight 绑定 assessment
  ↓
Signal 计算 (computeSignal)
  ↓
UI 重新渲染
  ↓
[L1] ActivityStream 显示 Activity 卡片 + SignalBadge [风险点 #9]
[L2] 展开显示验证结果 + Loading 状态 [风险点 #7]
[L3] 详情弹窗显示完整信息
[Mobile] BottomSheet 实时更新 [风险点 #8]
批量操作栏显示 [风险点 #5 - onClick 实装]
```

### 验证检查点清单

| 序号 | 检查点 | 对应风险点 | 验证方式 | Pass 判断 |
|:----:|--------|:----------:|----------|-----------|
| 1 | tool_use_start 到达且 input={} | #1 | Network 监听 WS 消息 | 消息包含 toolUseId, toolName, input:{} |
| 2 | messageStore 中已有条目 | #1 | DevTools Console | Map size > 0 |
| 3 | Activity 生成，changedFiles=[] | #1, #2 | UI: ActivityStream 显示新卡片 | 卡片可见，fileCount=0 |
| 4 | tool_use_input 到达 | #1 | Network 监听 WS 消息 | 消息包含 input.file_path |
| 5 | Activity 自动回溯更新 | #1 | 日志: [APOS-DEBUG] Activity backfill-updated | 日志出现 + fileCount 变为 >0 |
| 6 | tool_result 到达 | #1 | Network 监听 WS 消息 | 消息包含 content |
| 7 | triggerVerification 被调用 | #10 | 后台发起 API 请求 | Network 中出现 POST /api/verify/run-checks |
| 8 | API 响应处理 | #3 | 响应成功或降级 | 若成功：assessment 存在；若失败：signal='manual_required' |
| 9 | insight 被关联 | #1, #2 | UI: L2 显示验证结果或 loading | 结果或 spinner 可见 |
| 10 | Signal 计算 | #9 | UI: SignalBadge 显示对应颜色/图标 | Badge 颜色与 signal 匹配 |
| 11 | 批量操作按钮可用 | #5 | UI: 选中后按钮 enabled | 按钮 enabled 且 onClick 可执行 |
| 12 | 会话切换后清理 | #4 | 切换 session 后验证 selectedIds | selectedIds.size === 0 |
