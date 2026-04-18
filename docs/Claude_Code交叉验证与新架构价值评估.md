# Claude Code参考源码交叉验证与新架构价值评估

**生成日期**: 2026-04-18  
**验证范围**: 15个提升方案 vs Claude Code源码实现  
**验证方法**: 源码对比分析（TS vs Java）  

---

## 一、Claude Code引用准确性验证

### 方案3.1 - 流式工具启动

#### 文档描述
- 每收到一个 tool_use block 立即启动执行
- 目标状态：StreamingToolExecutor 在 API 流式接收期间即时调用 session.addTool()

#### 实际情况（Claude Code源码）
- **文件**: `src/services/tools/StreamingToolExecutor.ts` (531行)
- **实现**: 
  - `addTool(block, assistantMessage)` 方法（L76）直接触发工具执行
  - 每个 TrackedTool 在 queue 中等待执行权
  - 并发控制：concurrent-safe 工具可并行，non-concurrent 工具独占（L391）
  - **关键路径**: `addTool()` → TrackedTool 入队 → `processQueue()` → `executeTool()`

#### 偏差评估
✅ **准确**: ZhikuCode 文档基本对标 Claude Code 的流式启动机制。Claude Code 通过 StreamingToolExecutor 的 TrackedTool 队列管理实现流式启动，ZhikuCode 采用类似的 QueryEngine StreamCollector 回调机制。

**关键差异**:
- Claude Code: 异步 Promise 链 + 并发队列管理（`executeTool()` 为 async）
- ZhikuCode: Virtual Thread + CompletableFuture（Java 21 优势）

---

### 方案3.2 - 工具排序与 Prompt Cache

#### 文档描述
- 内建工具在前按名称排序，MCP 工具在后按名称排序
- 缓存断点位置，最大化 Prompt Cache 命中率

#### 实际情况（Claude Code源码）
- **文件**: `src/tools.ts` (390行，L345-367)
- **函数**: `assembleToolPool()` 
- **实现**:
  ```typescript
  const byName = (a: Tool, b: Tool) => a.name.localeCompare(b.name)
  return uniqBy(
    [...builtInTools].sort(byName).concat(allowedMcpTools.sort(byName)),
    'name',
  )
  ```
- **策略**: 分组排序（built-in 前 + MCP 后），组内均按名称排序
- **缓存**: 注释说"cache breakpoint after the last prefix-matched built-in tool"（L355-356），但代码中**无 Caffeine 缓存实现**（Claude Code 为 Node.js TypeScript）

#### 偏差评估
✅ **高度准确**: 排序逻辑完全一致。ZhikuCode 文档 3.2 方案中的 Caffeine 缓存层是对 Claude Code 的合理增强（本地单机 Java）。

**关键差异**:
- Claude Code: 无明确缓存层（Node.js 内存自然缓存）
- ZhikuCode: 需新增 Caffeine 缓存 + cache invalidation 机制

---

### 方案3.3 - contextModifier 机制

#### 文档描述
- 允许工具执行后通过 contextModifier 修改后续工具的上下文
- 仅对非并发安全工具生效

#### 实际情况（Claude Code源码）
- **文件**: `src/services/tools/StreamingToolExecutor.ts` (531行)
- **实现**:
  - TrackedTool 类型定义（L21-32）包含 `contextModifiers?: Array<(context: ToolUseContext) => ToolUseContext>`
  - `executeTool()` L273-294: 累积 contextModifiers 数组
  - L379-380: 从 update 收集 modifier `contextModifiers.push(update.contextModifier.modifyContext)`
  - **关键代码**（L391-395）:
    ```typescript
    if (!tool.isConcurrencySafe && contextModifiers.length > 0) {
      for (const modifier of contextModifiers) {
        this.toolUseContext = modifier(this.toolUseContext)
      }
    }
    ```
  - **规则**: non-concurrent 工具的 modifier 立即应用到 this.toolUseContext，后续工具使用更新后的 context

#### 偏差评估
✅ **完全准确**: ZhikuCode 文档 3.3 方案的设计完全对标 Claude Code 的实现。

**细节匹配**:
- Claude Code 中 contextModifiers 为 array，支持链式应用（L392-394 循环）
- ZhikuCode 文档中改为 AtomicReference 的 CAS 循环，是对 Java 并发的合理调整
- Claude Code L391 的并发安全检查对标 ZhikuCode 的 isConcurrencySafe 限制

---

### 方案4.1 - Swarm 模式

#### 文档描述
- 多个 Teammate Agent 并行执行任务，Leader 协调分配
- Worker 邮箱通信 + 结构化消息协议
- Idle 生命周期管理

#### 实际情况（Claude Code源码）
- **文件**: `src/coordinator/coordinatorMode.ts` (370行)
  - 定义 `getCoordinatorSystemPrompt()` - Swarm/Worker 的系统提示（L116-369）
  - AgentTool 用于派发 worker，定义了 `subagent_type` 参数（L194、L241）
  - 消息格式为 `<task-notification>` XML 块（L147-160）
  - Worker 通过 SendMessage 工具回复 leader（L131）

- **工具集**:
  - `ASYNC_AGENT_ALLOWED_TOOLS` 定义了 worker 可用的工具集合
  - INTERNAL_WORKER_TOOLS（L29-34）：TeamCreate、TeamDelete、SendMessage、SyntheticOutput

- **架构**:
  - Leader（Coordinator）通过 Agent 工具派发 worker
  - Worker 在独立上下文中执行（工具限制、权限降级）
  - 邮箱系统基于 ConcurrentLinkedQueue（Claude Code 的 main.tsx 实现了类似的消息队列）

#### 偏差评估
⚠️ **部分偏差**:
1. Claude Code 基于终端架构，使用 stdio 管道（pane-based）通信
2. ZhikuCode 改为 Virtual Thread (In-Process) + Mailbox 通信（正确适配）
3. 文档中提及的 "Pane-based" 外部进程方案在 Claude Code 中体现为独立进程（out-of-process）

**适配评估**: ✅ ZhikuCode 的 In-Process 实现是对浏览器架构的合理适配，无需 pane 通信开销

---

### 方案4.2 - Coordinator 四阶段编排

#### 文档描述  
- 四阶段工作流：Research → Synthesis → Implementation → Verification
- 阶段工具过滤（Research 阶段限制工具集）
- "永远不要委派理解"原则验证

#### 实际情况（Claude Code源码）
- **文件**: `src/coordinator/coordinatorMode.ts` L196-270
  - 完整的四阶段工作流指令已集成
  - L251-270 明确阐述 "Phases" 和 "What Real Verification Looks Like"
  - L259-280 包含 "Always synthesize" 和反模式警告
  - L280-290 定义了每个阶段的推荐 Agent 类型

- **SubAgentExecutor 中的 AgentDefinition**（ZhikuCode 文档 4.3 提及）:
  - EXPLORE: 受限工具集（L885-888）
  - VERIFICATION: 实际执行命令验证（L889-892）
  - PLAN: 计划生成（L893-896）
  - GENERAL_PURPOSE: 全工具集（L897-899）

#### 偏差评估
✅ **高度准确**: 文档完整引用了 Claude Code 的四阶段工作流和 Agent 类型化系统。

**关键发现**:
- Claude Code L196-270 的工作流指令非常完整，ZhikuCode 可直接引用
- AgentDefinition 的 5 种类型（含 GUIDE）需在 ZhikuCode 中实现为 sealed interface

---

### 方案 5.1 - Context Collapse 增强

#### 文档描述
- 渐进式消息折叠（保留最近 N 条 → 摘要保留 → 骨架化）
- 与 AutoCompact 的互斥协调

#### 实际情况（Claude Code源码）
- **文件**: `src/services/compact/compact.ts` + `src/query.ts`
- 实现: CompactService 提供 LLM 驱动的自动摘要
- Token 折叠相关逻辑在 query.ts 中（L428、L609、L1086 等）
- **关键**: Claude Code 的折叠与 AutoCompact 之间有隐式协调（Token 压力评估）

#### 偏差评估
⚠️ **设计差异较大**:
- Claude Code: 单一 CompactService（LLM 摘要）
- ZhikuCode: 多级折叠（Full → Summary → Skeleton）+ AutoCompact 互斥

**评估**: 这是合理的创新，利用 Java 的灵活性实现渐进式本地折叠，无需 LLM 调用。

---

### 方案 6.1 - MCP 资源发现

#### 文档描述
- `resources/list` 从占位实现升级为完整的资源发现和访问功能
- Caffeine 缓存 + 异步发现

#### 实际情况（Claude Code源码）
- **文件**: `src/services/mcp/client.ts` (116.3KB - 大文件)
- MCP 协议已完整实现，但没有完整的资源发现 UI
- Claude Code 重点是工具和提示词，资源发现是较新的 MCP spec 功能

#### 偏差评估
✅ **准确**: ZhikuCode 在正确地扩展 Claude Code 未完全实现的 MCP 资源功能

---

### 方案 7.1 - Token 预算 nudge UI

#### 文档描述
- Token 预算续写时推送 nudge 消息
- 前端显示 token 预算进度条

#### 实际情况（Claude Code源码）
- **文件**: `src/query/tokenBudget.ts` (94行)
- 完整实现了 BudgetTracker 和 nudge 消息生成
- `ContinueDecision` record 包含 `nudgeMessage` 字段（L24）
- `query.ts` L1319-1326 显示 nudge 消息添加到对话历史

#### 偏差评估
✅ **高度准确**: 核心逻辑完全对标。ZhikuCode 需补充：
- WebSocket 推送层（Claude Code 为终端，无需实时推送）
- 前端 TokenBudgetIndicator 组件

---

## 二、照搬风险评估（逐方案）

### 高风险方案（需要显著调整）

#### 方案 4.1 Swarm - 后端架构风险：**高**

**照搬程度**: 高（但方向正确）

**问题**:
1. Claude Code 使用 pane-based（管道通信）+ stdio
2. ZhikuCode 改为 Virtual Thread + Mailbox
3. **直接照搬的风险**: 如果照搬 Claude Code 的 Worker 隔离策略而不考虑 Java 内存模型，可能导致：
   - Worker 之间的状态泄露（缺少 volatile/AtomicReference）
   - 竞态条件（ConcurrentHashMap 对 iterators 的限制）

**改进建议**: 
✅ 文档已正确应对：LeaderPermissionBridge 使用 CompletableFuture + timeout 的死锁防护

---

#### 方案 5.2 压缩后关键文件重注入 - 文件跟踪风险：**中**

**照搬程度**: 低（创新功能）

**问题**:
- Claude Code 无 KeyFileTracker 概念
- 文件访问历史追踪需考虑：
  - 性能（ConcurrentHashMap 的内存压力）
  - 隐私（用户本地部署，需明确日志策略）

**改进建议**:
- ✅ 文档正确地限制了 `MAX_REINJECT_FILES=5`
- ✅ 定期清理过期追踪数据

---

### 中风险方案（需要部分调整）

#### 方案 3.2 工具排序缓存 - Token 预算相互作用：**中**

**照搬程度**: 低（新增缓存层）

**问题**:
- 工具排序结果缓存会影响 Prompt Cache 命中率计算
- 需确保 PromptCacheBreakDetector 与 ToolRegistry.getEnabledToolsSortedCached() 同步

**改进建议**:
- 缓存 invalidation 需与 MCP 工具加载同步
- ✅ 文档已覆盖 `unregisterByPrefix()` 中的 `invalidateSortedCache()` 调用

---

#### 方案 6.4 MCP 企业策略 - 安全过滤风险：**中**

**照搫程度**: 高（但 Claude Code 无此功能）

**问题**:
- `managedRulesOnly` 语义需明确定义（文档已明确）
- allowlist 为空时的默认行为（文档定义为"全允许"）
- 多层配置合并顺序（ENV < ENTERPRISE < USER < LOCAL）

**改进建议**:
- ✅ 文档正确应对了空集合语义
- ⚠️ 需新增全场景测试（managedRulesOnly + allowlist/denylist 的组合）

---

### 低风险方案（可以安全实施）

#### 方案 3.1 流式工具启动、3.3 contextModifier、7.1 Token预算

**评估**: ✅ **低风险** - 直接对标 Claude Code，调整为 Java/Virtual Thread 即可

---

## 三、新架构优势利用不足的方案

### 1. **方案 3.1 流式工具启动** - MicroMeter 指标缺失

**当前方案**: 微调 Step 3 的 StreamCollector 回调逻辑

**优势未利用**:
- **Virtual Thread 监控**: 应暴露 `zhiku.tool.virtual_threads.active` (Gauge)、`zhiku.tool.execution_time` (Timer) 指标到 `/actuator/metrics`
- **背压控制**: 文档已提到但未完整实施（Semaphore 限流）

**建议补充**:
- 在 `StreamingToolExecutor.processQueue()` 中添加 MicroMeter 埋点
- 通过 `/actuator/prometheus` 暴露给 Prometheus（运维可观测性）

---

### 2. **方案 4.1 Swarm** - WebSocket 实时通知缺失

**当前方案**: 基础的 Worker 状态管理

**优势未利用**:
- **多客户端同步**: 多个浏览器/手机同时连接时，Swarm 状态变更应通过 WebSocket `/queue` 推送
- **实时 Worker 日志**: 前端应实时接收 Worker 的工具执行日志（不仅是最终结果）

**建议补充**:
- 在 `SwarmWorkerRunner.executeWorkerLoop()` 完成后推送 `swarm_worker_complete` 消息
- Worker 执行中每完成一个工具应推送 `swarm_worker_progress` 消息

**新架构价值**: Claude Code 无法支持多端同步 Swarm 进度（终端形态），这是 ZhikuCode 的独特优势。

---

### 3. **方案 5.2 文件重注入** - PathSecurityService 安全防护

**当前方案**: 基础的文件读取 + 路径验证

**优势未利用**:
- **文件权限检查**: 应调用 `PathSecurityService.checkReadPermission(path, workingDirectory)` 防止读取敏感文件
- **离线缓存**: PWA 支持文件离线缓存

**建议补充**:
- ✅ 文档已包含 PathSecurityService 集成（L1874-1878）
- 但需确保 PathSecurityService 的白名单/黑名单配置生效

---

### 4. **方案 6.2 提示词发现** - XSS 防护不完整

**当前方案**: 参数验证 + MCP 服务器调用

**优势未利用**:
- **DOMPurify.sanitize()**: 用户注入的 prompt 参数需 HTML 转义
- **前端组件隔离**: PromptsTab 中的参数表单需防止 prompt injection（用户输入被作为系统提示词的一部分）

**建议补充**:
- 在 `PromptArgsForm.tsx` 中使用 DOMPurify 对用户输入进行转义
- 参数值注入时需二重过滤

---

### 5. **方案 6.3 健康检查** - Spring Actuator HealthIndicator 缺失

**当前方案**: SseHealthChecker + 指数退避重连

**优势未利用**:
- **Actuator 集成**: MCP 连接状态应注册为自定义 `HealthIndicator`，暴露到 `/actuator/health`
- **MicroMeter 指标**: `mcp.connection.latency` (Timer) + `mcp.connection.failures` (Counter)

**建议补充**:
- 新建 `McpConnectionHealthIndicator` 实现 `HealthIndicator` 接口
- 在 `McpClientManager` 中注册指标收集

---

## 四、高价值遗漏功能

### Claude Code 中有但 ZhikuCode 文档未覆盖的功能

#### 1. **TaskTool 套件**（5个工具）

**Claude Code 中的实现**:
```
TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool, TaskStopTool
```

**评估**:
- 这些工具用于 Task 管理（异步工作流控制）
- ZhikuCode 实施计划中的 #5（压缩后关键文件重注入）涉及 `KeyFileTracker`，但未涉及 Task 管理
- **价值**: 支持用户创建多个并行的异步任务，在 Coordinator 模式中很有用

**建议**: 
✅ 已在原始 Claude Code 的 `/tools/` 目录中存在，ZhikuCode 无需创新，可直接移植 Task 工具的权限模型和 UI

---

#### 2. **Skill 工具系统** + `/commit` / `/verify` 等快捷命令

**Claude Code 中的实现**:
```
SkillTool, TaskCreateTool → /commit, /verify, /plan 命令
```

**评估**:
- 允许用户定义自定义快捷工具
- ZhikuCode 文档未涉及

**建议**: 
⚠️ **低优先级** - 可作为 P2/P3 功能添加。如果添加，应通过 Python service 扩展（ZhikuCode 架构包含 FastAPI 服务）。

---

#### 3. **LSPTool** - 语言服务器协议支持

**Claude Code 中的实现**:
- 通过 LSP 连接到 IDE 的语言服务器
- 支持代码补全、跳转定义等

**评估**:
- 提升代码编辑的精度
- ZhikuCode 已有 Monaco 编辑器 + Python LSP 支持基础

**建议**:
✅ **高价值** - 建议作为后续增强项。可参考 Claude Code 的 LSP 集成模式。

---

#### 4. **RemoteTriggerTool** - 远程触发能力

**Claude Code 中的实现**:
- 支持通过 HTTP 远程触发工具执行

**评估**:
- 支持外部系统集成
- ZhikuCode 作为本地部署，此功能价值有限

**建议**:
⚠️ **低优先级** - 如果未来需要企业集成，再考虑实施。

---

#### 5. **WebSearchTool + WebFetchTool** 的高级功能

**Claude Code 中的实现**:
- 网页搜索 + 内容抓取
- 包括代理配置、JavaScript 执行等

**评估**:
- ZhikuCode 已有基础实现
- Claude Code 中的高级特性（代理、JS 执行）未在 ZhikuCode 覆盖

**建议**:
✅ **中优先级** - 可作为工具系统的增强项（方案 3.4 "其他工具增强"）补充。

---

## 五、模型无关性检查

### 文档中的硬编码检查

#### 方案 1.5.2 中的模型别名

**文档内容**:
```yaml
agent.model-aliases:
  haiku: qwen-0.5b
  sonnet: qwen-plus
  opus: qwen-max
```

**审查**:
✅ **完全模型无关** - 通过配置映射实现别名，支持任意 OpenAI 兼容模型

**Claude Code 中的对标**:
- Claude Code 硬编码了 Anthropic 模型（claude-opus, claude-sonnet, claude-haiku）
- ZhikuCode 的做法正确地解耦了模型名称

---

#### 方案 4.2 中的 Agent 类型配置

**文档内容**:
```java
EXPLORE: "haiku"    // 轻量模型
PLAN: null          // 继承主模型
VERIFICATION: null  // 继承主模型
```

**审查**:
✅ **支持模型无关** - 配置中的模型名称通过 `LlmProviderRegistry` 映射，不硬编码 API 格式

---

#### 方案 3.2 中的 Prompt Cache

**文档内容**:
```
cache_control: { type: "ephemeral" }
```

**审查**:
⚠️ **潜在风险** - 这是 Claude API 特定的功能

**文档已注明**: "DashScope/千问当前不支持此特性，方案已备注'不支持时静默忽略'"（L97）

**改进建议**:
✅ 正确处理了兼容性问题，通过 `PromptCacheBreakDetector` 条件应用

---

#### 方案 6.1 MCP 中的协议

**审查**:
✅ **完全标准化** - MCP 是模型无关的标准协议，无任何 Anthropic 硬编码

---

## 六、新架构价值未充分发挥的方案

### 1. **多端访问能力**（维度14.1）

**当前覆盖**:
- 方案 4.2 中提及了阶段指示器（前端组件）
- 方案 6.3 中提及了连接状态图标

**未覆盖**:
- 手机浏览器的 Coordinator 四阶段可视化（步骤条在移动端的适配）
- WebSocket 多标签页状态同步（同一用户的多个浏览器标签页之间的状态同步）

**建议补充**:
- 在各前端组件中补充 TailwindCSS 响应式设计（`sm:`, `md:`, `lg:` 断点）
- 补充 WebSocket 消息类型定义确保多标签页间的状态一致性

---

### 2. **富交互优势**（维度14.3）

**当前覆盖**:
- 基础的 WebSocket 推送机制

**未覆盖**:
- Monaco Editor 中的实时协作编辑（多 Worker 同时编辑同一文件）
- xterm.js 中的彩色输出优化（当前可能未充分利用）

**建议补充**:
- 补充 CRD T（冲突自由复制数据类型）来支持多客户端编辑同步
- 补充 xterm 的色彩主题配置

---

### 3. **Virtual Thread 性能优势**（维度14.5）

**当前覆盖**:
- 基础的 Virtual Thread 使用

**未覆盖**:
- Virtual Thread 栈追踪增强（JEP 446）
- Virtual Thread 性能监控（MicroMeter 指标）

**建议补充**:
- 在关键路径（工具执行、Worker 执行）中添加 MicroMeter Timer
- 通过 `/actuator/prometheus` 暴露性能指标

---

## 七、综合风险总结表

| 方案 | 照搬程度 | 新架构优势利用 | 模型无关性 | 风险等级 | 关键注意事项 |
|------|--------|--------------|----------|--------|-----------|
| 3.1 流式工具启动 | 高 | ⚠️ MicroMeter 缺失 | ✅ 完全无关 | 低 | 补充背压控制和监控指标 |
| 3.2 工具排序 + Cache | 低 | ✅ Caffeine 充分利用 | ✅ 完全无关 | 低 | 缓存失效同步 MCP |
| 3.3 contextModifier | 高 | ✅ CAS 循环适配 Java | ✅ 完全无关 | 低 | 非并发工具限制已明确 |
| 3.4 其他工具增强 | 中 | ✅ YAML 配置化 | ✅ 完全无关 | 低 | LSPTool 集成为后续增强 |
| 4.1 Swarm | 高 | ⚠️ WebSocket 推送不完整 | ✅ 完全无关 | 中 | 补充实时进度推送 |
| 4.2 Coordinator | 高 | ⚠️ 前端可视化部分缺失 | ✅ 完全无关 | 中 | 四阶段指示器响应式设计 |
| 4.3 Agent类型 | 高 | ✅ sealed interface 合理 | ✅ 完全无关 | 低 | AgentStrategyFactory 路由逻辑清晰 |
| 5.1 Context Collapse | 中 | ✅ 多级渐进式创新 | ✅ 完全无关 | 低 | substring 边界修复已覆盖 |
| 5.2 文件重注入 | 低 | ⚠️ PathSecurityService 集成 | ✅ 完全无关 | 中 | 需确保安全检查生效 |
| 6.1 MCP 资源发现 | 低 | ✅ Caffeine 缓存 | ✅ 完全无关 | 中 | API 层工作量被低估 |
| 6.2 提示词 UI | 低 | ⚠️ XSS 防护不完整 | ✅ 完全无关 | 中 | DOMPurify 需补充 |
| 6.3 健康检查 | 中 | ⚠️ Actuator HealthIndicator 缺失 | ✅ 完全无关 | 中 | MicroMeter 指标需新增 |
| 6.4 企业策略 | 低 | ✅ allowlist/denylist 清晰 | ✅ 完全无关 | 中 | managedRulesOnly 全场景测试必须 |
| 7.1 Token 预算 nudge | 高 | ⚠️ WebSocket 推送缺失 | ✅ 完全无关 | 低 | 前端组件需新建 |

---

## 八、主要建议

### 最高优先级（必须修正）

1. **补充 WebSocket 实时通知**（方案 4.1、7.1）
   - 当前文档中缺少完整的 WebSocket 消息类型定义
   - 需明确 ServerMessage 中的新增类型号

2. **前端组件规格完整性**（方案 4.2、6.1、7.1）
   - 当前 UI 组件规格描述不完整
   - 需补充响应式断点和触控适配规范

3. **安全防护闭环**（方案 5.2、6.2）
   - PathSecurityService 和 XSS 防护需确保实施

### 高优先级（强烈建议）

4. **MicroMeter 指标体系**（方案 3.1、6.3）
   - 为运维监控提供完整的可观测性

5. **虚拟线程监控**（方案 3.1、4.1）
   - 充分发挥 Java 21 Virtual Thread 的优势

### 中优先级（可选增强）

6. **工具系统补充**（方案 3.4）
   - LSPTool、WebSearch 高级功能作为后续增强

7. **前端多端优化**（方案 1.5.1）
   - PWA 离线支持（P3）
   - 多标签页状态同步（P2）

---

## 九、结论

### 照搬风险评估

✅ **总体评估**: **低至中等风险**

- **低风险方案**: 3.1, 3.2, 3.3, 7.1 - 这些方案直接对标 Claude Code，仅需语言和框架适配
- **中等风险方案**: 4.1, 5.2, 6.1, 6.4 - 这些方案有部分创新或安全考量需补充
- **无高风险方案** - 核心设计方向正确

### 新架构优势利用评估

⚠️ **总体评估**: **有优势未充分发挥**

**需补充的新架构优势利用**:
1. **WebSocket 实时推送** - 多端同步状态（Swarm、Token预算）
2. **Virtual Thread 监控** - MicroMeter 指标暴露
3. **浏览器富交互** - 响应式前端组件、触控适配
4. **多客户端支持** - 多标签页/多设备状态同步

**已充分利用的新架构优势**:
1. ✅ contextModifier 的 Java CAS 并发模型
2. ✅ 渐进式 Context Collapse 的多级设计
3. ✅ sealed interface + pattern matching 的类型安全
4. ✅ Caffeine 单机缓存满足个人部署场景

### 模型无关性评估

✅ **完全模型无关** - 所有 15 个方案均无 Anthropic/Claude 特定硬编码

**关键配置点**:
- `application.yml` 的 `llm.openai.base-url` 支持任意 OpenAI 兼容端点
- Agent 模型别名完全可配置
- MCP 标准协议本身模型无关

