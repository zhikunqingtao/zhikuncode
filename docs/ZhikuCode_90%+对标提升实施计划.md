# ZhikuCode 90%+ Claude Code 对标提升实施计划

> **文档版本**: v3.1（第三轮审查修正版，新增浏览器交互适配）  
> **生成日期**: 2026-04-18  
> **基线数据**: 功能对标分析报告 (2026-04-17) + 测试报告 v3.1  
> **技术栈**: Java 21 + Spring Boot 3.3 + Caffeine Cache + Virtual Thread  
> **前端栈**: React 18 + TypeScript + Zustand + Vite 5.4  

---

## 一、总览

### 1.1 目标

将 ZhikuCode 与 Claude Code 的功能对标整体完整度从 **81.7% 提升至 94%+**。

### 1.2 六大模块当前→目标完整度

| 模块 | 当前完整度 | 目标完整度 | 增量 | 核心差距 |
|------|-----------|-----------|------|---------|
| Agent Loop | 90% | 93% | +3% | Token 预算 nudge UI 推送 |
| 工具系统 | 72% | 95% | +23% | 流式启动微调、工具排序缓存、contextModifier 传播 |
| 权限系统 | 93% | 98% | +5% | 远程熔断、企业策略、规则遮蔽检测 |
| MCP集成 | 78% | 92%+ | +14% | 资源发现完整化、提示词UI、企业策略 |
| 上下文管理 | 82% | 91%+ | +9% | Context Collapse增强、压缩后重建 |
| 多Agent协作 | 75% | 95%+ | +20% | Swarm完整实现、Coordinator四阶段、Agent类型化 |
| **整体** | **81.7%** | **94.0%** | **+12.3%** | |

### 1.3 重要说明：已实现功能的修正

源码审查发现以下功能已在测试报告 v3.1 中验证通过，对标报告中标记为"缺失"但实际已存在：

| 功能 | 对标报告标注 | 实际源码状态 | 文件 |
|------|------------|-------------|------|
| 工具并发分区 | 无 isConcurrencySafe | **已实现** — `isConcurrencySafe()` 判断 + 分区并行 | `StreamingToolExecutor.java` |
| Token预算续写 | 无 nudge 消息 | **已实现** — 完整的 nudge + 递减回报检测 | `TokenBudgetTracker.java` |
| Context Collapse | 无骨架化 | **已实现** — Level 1.5 基础版骨架化 | `ContextCollapseService.java` |
| SSE健康检查 | 无主动 ping | **已实现** — 30s 周期 + 指数退避重连 | `SseHealthChecker.java` |
| Coordinator基础 | 无实现 | **已实现** — 模式检测、工具过滤、Scratchpad | `CoordinatorService.java` |

**结论**：实际完整度高于对标报告标注值。本计划聚焦于**真正缺失的功能增强**而非从零实现。

### 1.4 总工作量估算

| 优先级 | 工作项数 | 人天估算 | 周期 |
|--------|---------|---------|------|
| 最高（P0） | 5 项 | 17-19 人天 | Week 1-2 |
| 高（P1） | 5 项 | 31-35 人天 | Week 3-4 |
| 中（P2） | 5 项 | 35.5-40 人天 | Week 5-6 |
| 收尾 | 集成测试 | 5 人天 | Week 7 |
| **总计** | **16 项** | **88.5-99 人天** | **7-8 周** |

---

## 二、实施优先级矩阵

### 2.1 ROI 排序表

| 排序 | 实施项 | 所属模块 | 功能提升 | 工作量 | ROI | 依赖 |
|------|--------|---------|---------|--------|-----|------|
| 1 | 流式工具启动（微调） | 工具系统 | +5% | 2天 | ★★★★★ | 无 |
| 2 | 工具排序与 Prompt Cache | 工具系统 | +4% | 2天 | ★★★★★ | 无 |
| 3 | Swarm 模式完整实现 | 多Agent | +13% | 12天 | ★★★★☆ | 无 |
| 4 | Coordinator 四阶段编排 | 多Agent | +7% | 8天 | ★★★★☆ | #3 |
| 5 | Context Collapse 渐进增强 | 上下文 | +5% | 4天 | ★★★★☆ | 无 |
| 6 | 压缩后关键文件重注入 | 上下文 | +4% | 4天 | ★★★★☆ | #5 |
| 7 | 资源发现完整实现 | MCP | +5% | 7天 | ★★★☆☆ | 无 |
| 8 | 提示词发现 UI 集成 | MCP | +2% | 3天 | ★★★☆☆ | 无 |
| 9 | contextModifier 传播 | 工具系统 | +3% | 2天 | ★★★☆☆ | 无 |
| 10 | Token 预算 nudge UI | Agent Loop | +3% | 3天 | ★★★☆☆ | 无 |
| 11 | 远程熔断集成 | 权限 | +2% | 6天 | ★★☆☆☆ | 无 |
| 12 | 企业策略覆盖 | 权限 | +2% | 3天 | ★★☆☆☆ | 无 |
| 13 | 规则遮蔽检测 | 权限 | +1% | 5天 | ★★☆☆☆ | 无 |
| 14 | Agent 类型扩展 | 多Agent | +3% | 4天 | ★★☆☆☆ | 无 |
| 15 | 企业策略支持(MCP) | MCP | +2% | 5天 | ★★☆☆☆ | #12 |
| 16 | 其他工具增强 | 工具系统 | +9% | 6天 | ★★☆☆☆ | 无 |

### 2.2 依赖关系图

```
[流式工具启动] ─┐
[工具排序Cache] ─┤─→ [contextModifier传播]
                 │
[Swarm完整实现] ─┤─→ [Coordinator四阶段]
                 │
[Token预算nudge] │(独立)
                 │
[ContextCollapse增强] ─→ [压缩后重建]
                 │
[资源发现完整化] │(独立)
[提示词发现UI]   │(独立)
[Agent类型扩展]  │(独立)
                 │
[远程熔断集成]   │─→ [企业策略覆盖] ─→ [企业策略MCP]
[规则遮蔽检测]   │(独立)
```

---

## 三、工具系统提升（72% → 95%）

> **审查修正说明**：原方案 3.4（shouldDefer 批处理）已删除——接口签名与实际代码不符，无明确使用场景，ROI 低。原 3.5 改为 3.4。

### 3.1 流式工具启动

**目标**：API 流式接收期间，每收到一个 tool_use block 立即启动执行，而非等待 API 完全接收后批量执行。

**当前状态**（源码审查修正）：
- ✅ **核心功能已基本实现**：`StreamCollector` 的 `flushToolBlock()` 已在 `QueryEngine.java`（StreamCollector 内部类，约 L894 附近）实现了流式工具块即时提交。
- `StreamingToolExecutor` 已实现并发分区执行，工具提交时机在 `QueryEngine.java` Step 3 中通过 StreamCollector 回调触发。
- 文件：`backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` (962行)
- 文件：`backend/src/main/java/com/aicodeassistant/tool/StreamingToolExecutor.java` (196行)
- ❗ `StreamingResponseHandler.java` **不存在**，实际回调机制通过 `StreamCollector` 实现。

**目标状态**（Claude Code 基线）：
- `StreamingToolExecutor.ts` 在 API 流式接收期间，每解析出一个 `tool_use` content block 就立即调用 `session.addTool()`，工具在 API 还在接收其他 block 时已经开始执行。
- 减少首个工具的等待时间：从“等待所有 block 接收完毕”变为“收到即执行”。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` — 微调 Step 3 的 StreamCollector 回调逻辑
2. `backend/src/main/java/com/aicodeassistant/tool/StreamingToolExecutor.java` — 优化 `onToolBlockReady` 回调时序

> **注意**：原方案引用的 `StreamingResponseHandler.java` 不存在，实际回调机制已通过现有的 `StreamCollector.flushToolBlock()` 实现。

核心优化点：

```java
// QueryEngine.java StreamCollector 内部类（约 L894 附近）— 已有回调机制：
// StreamCollector 在 SSE 流中每解析完一个 tool_use block 时，
// 通过 flushToolBlock() 立即触发工具执行。
// 待优化：
// 1. 确保 ExecutionSession 在 API 调用前创建（而非调用后）
// 2. 优化回调时序：确保在 content_block_stop 事件时才触发，避免不完整 JSON
// 3. Step 5 变为：等待 session 中所有已提交工具完成（而非重新提交）
```

**关键实现细节**：

- **线程安全**：`StreamingToolExecutor.ExecutionSession` 已使用 `ConcurrentLinkedQueue` 和 `CopyOnWriteArrayList`，天然支持多线程 `addTool()`。
- **错误处理**：若回调中工具查找失败（`findTool` 返回 null），在 session 中添加 error result（已有 `addErrorResult` 方法）。
- **兼容性**：若未注册回调，退化为原有的“批量提交”模式，完全向后兼容。
- **取消传播**：`AbortContext` 的 abort 信号通过 `session.discard()` 传播到所有已提交但未完成的工具。

**工作量**：2 人天（原估 4 人天，核心功能已实现，仅需微调）  
**完整度影响**：工具系统 72% → 77%（+5%）  
**依赖项**：无  
**状态**：✅ 功能已基本实现，仅需微调  
**风险与注意事项**：
- SSE 流中 tool_use block 的 JSON 参数可能不完整，必须在 `content_block_stop` 事件时才触发回调。
- 测试需覆盖：单工具、多工具并行、工具执行失败、API 流中断等场景。

---

### 3.2 工具排序与 Prompt Cache 优化

**目标**：内建工具在前按名称排序，MCP 工具在后按名称排序，两组之间放置缓存断点，最大化 Prompt Cache 命中率。

**当前状态**（源码审查修正）：
- ✅ **排序已实现**：`ToolRegistry.getEnabledToolsSorted()` 已在 L144-157 实现内建工具和 MCP 工具的分组排序。
- 文件：`backend/src/main/java/com/aicodeassistant/tool/ToolRegistry.java`
- ❌ **缓存层未实现**：无 Caffeine 缓存，每轮重新排序。
- ❌ **缓存失效未处理**：`unregisterByPrefix()` 后未触发缓存失效。
- ❌ 无 Prompt Cache 断点概念。

**目标状态**（Claude Code 基线）：
- `assembleToolPool()` 将内建工具按名称排序放前，MCP 工具按名称排序放后。
- 服务端在最后一个内建工具后放置 cache breakpoint，MCP 工具变更不影响内建工具的缓存。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/tool/ToolRegistry.java` — 基于现有 `getEnabledToolsSorted()` 增加 Caffeine 缓存层
2. `backend/src/main/java/com/aicodeassistant/service/PromptCacheBreakDetector.java` — **新建**，断点计算
3. `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptBuilder.java` — 工具定义段插入断点标记

核心代码变更：

```java
// ToolRegistry.java — 基于现有 getEnabledToolsSorted() 增加缓存层
// 注意：排序逻辑已在 getEnabledToolsSorted() (L144-157) 实现，
// 不需要新建 PartitionedToolSet record，直接复用现有接口。

// 新增：Caffeine 缓存层
private final Cache<String, List<Tool>> sortedToolCache = Caffeine.newBuilder()
    .maximumSize(100)
    .expireAfterWrite(Duration.ofMinutes(5))
    .build();

public List<Tool> getEnabledToolsSortedCached(String sessionId) {
    String cacheKey = sessionId + "_" + computeToolSetHash();
    return sortedToolCache.get(cacheKey, k -> getEnabledToolsSorted(sessionId));
}

// 缓存失效：在 unregisterByPrefix() 和 MCP 工具变更后调用
public void invalidateSortedCache() {
    sortedToolCache.invalidateAll();
}

// PromptCacheBreakDetector.java — 在工具定义生成时标记断点
public record CacheBreakpoint(int position, String reason) {}
```

**关键实现细节**：

- **判断内建/MCP**：MCP 工具名称以 `mcp__` 前缀开头（`McpToolAdapter` 命名规范），直接通过前缀判断。
- **缓存断点传递**：在构建 API 请求的 `tools` 数组时，将断点位置信息传递给 `PromptCacheBreakDetector`，由其在对应位置的 tool definition 上添加 `cache_control: { type: "ephemeral" }` 标记。
- **Caffeine 缓存**：工具排序结果使用 Caffeine 缓存，key 为 `sessionId + toolSetHash`，避免每轮重新排序。
- **缓存失效**：`unregisterByPrefix()` 和 `McpClientManager.refreshTools()` 后调用 `invalidateSortedCache()` 触发缓存刷新。

**工作量**：2 人天（原估 3 人天，排序已实现，仅需缓存层+断点）  
**完整度影响**：工具系统 77% → 81%（+4%）  
**依赖项**：无  
**状态**：✅ 排序已实现，缓存优化待完成  
**风险与注意事项**：
- MCP 工具动态注册/注销后需触发缓存失效，已在方案中覆盖。
- API provider（如 DashScope/OpenAI）是否支持 `cache_control` 需确认，不支持时静默忽略。

---

### 3.3 contextModifier 机制实现

**目标**：允许工具执行后通过 `contextModifier` 修改后续工具的上下文（如切换工作目录），但仅对非并发安全工具生效。

**当前状态**（源码审查修正）：
- ✅ **接口层已完成**：`ToolResult.java` 中 `withContextModifier()` 和 `getContextModifier()` 已实现（L66-77）。
- `ToolUseContext.java` 存在且支持 modifier 回调。
- ❌ **执行层传播未实现**：`StreamingToolExecutor` 中 `TrackedTool.updatedContext` 字段已预留（L54），但分区间传播逻辑未实现。

**目标状态**（Claude Code 基线）：
- `ToolResult` 可返回 `contextModifier` 函数，在工具执行后修改 `ToolUseContext`。
- 仅对 `isConcurrencySafe() == false` 的工具生效——并发执行的工具不能互相修改上下文。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/tool/ToolResult.java` — 接口已完成，无需修改
2. `backend/src/main/java/com/aicodeassistant/tool/StreamingToolExecutor.java` — 补充分区间 `updatedContext` 传播逻辑
3. `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` — 接收更新后的 context

核心实现：

```java
// StreamingToolExecutor — 补充分区间 modifier 传播
// 在 processQueue() 中，当一个非并发安全工具完成且有 contextModifier 时：
// 1. 调用 ToolResult.getContextModifier().apply(currentContext)
// 2. 将结果写入 TrackedTool.updatedContext（L54 已预留字段）
// 3. 更新 session 级别的 currentContext
// 4. 后续队列中的工具使用更新后的 context
// 并发安全工具的 modifier 被忽略（日志警告）
```

**关键实现细节**：

- **线程安全**：`currentContext` 使用 `AtomicReference<ToolUseContext>` 存储，modifier 的 apply 在 CAS 循环中执行。
- **并发安全工具限制**：若 `isConcurrencySafe() == true` 的工具返回了 modifier，记录 `WARN` 日志但不应用——防止并行工具之间的上下文竞争。
- **向后兼容**：现有工具不返回 modifier（返回 null），行为不变。

**工作量**：2 人天（原估 3 人天，接口层已完成，仅需执行层传播）  
**完整度影响**：工具系统 81% → 84%（+3%）  
**依赖项**：无  
**状态**：✅ 接口层完成，执行层传播待补充  
**风险与注意事项**：`ToolResult` record 接口已存在，无需修改现有工具代码。仅需在 `StreamingToolExecutor.processQueue()` 中补充 5-10 行传播逻辑。

---

### 3.4 其他工具增强

以下为工具系统的补充增强项，合并实施：

| 增强项 | 涉及文件 | 工作量 | 描述 |
|--------|---------|--------|------|
| mapToolResult 实现 | `Tool.java`, 各工具类 | 1天 | 将空实现替换为实际的结果映射（如 FileReadTool 返回行号标注） |
| preparePermissionMatcher 子命令级 | `PermissionRuleMatcher.java` | 2天 | BashTool 子命令级权限匹配（如 `Bash(npm install:*)` 匹配 `npm install --save`） |
| BashTool flag 级验证 | `bash/BashCommandClassifier.java` | 3天 | 只读白名单的 flag 值类型验证（'none'/'number'/'string'/specific） |

**总工作量**：6 人天  
**完整度影响**：工具系统 86% → 95%（+9%，含 BashTool 安全提升）

---

## 四、多Agent协作提升（75% → 95%+）

> **审查修正说明**：原方案 4.3（外部进程后端）已删除——TeammateBackend sealed interface 等均不存在，工作量大（8人天），启动开销大，ROI 低。原 4.4 改为 4.3。

### 4.1 Swarm 模式完整实现

**目标**：将 `SwarmService` 从占位实现升级为完整的多 Agent 并行协作系统。

**当前状态**：
- `SwarmService.java`（62行）：所有方法为占位，抛出 `UnsupportedOperationException`。
- `TeamManager.java` 已有 CRUD 框架。
- `TeamMailbox.java` 邮箱系统框架已建立。
- `InProcessBackend.java` 进程内后端已实现基础结构。
- Feature flag `ENABLE_AGENT_SWARMS` 存在但功能未实现。

**目标状态**（Claude Code 基线）：
- Swarm = 多个 Teammate Agent 并行执行任务，Leader 协调分配。
- 两种后端：In-process（Virtual Thread）和 Pane-based（外部进程）。
- Teammate 邮箱通信 + 结构化消息协议。
- Idle 生命周期管理。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/coordinator/SwarmService.java` — 完整重写
2. `backend/src/main/java/com/aicodeassistant/coordinator/InProcessBackend.java` — 增强
3. `backend/src/main/java/com/aicodeassistant/coordinator/TeamMailbox.java` — 完整通信协议
4. `backend/src/main/java/com/aicodeassistant/coordinator/TeamManager.java` — Swarm 集成
5. `backend/src/main/java/com/aicodeassistant/coordinator/SwarmWorkerRunner.java` — **新建**
6. `backend/src/main/java/com/aicodeassistant/coordinator/SwarmState.java` — **新建**
7. `backend/src/main/java/com/aicodeassistant/coordinator/LeaderPermissionBridge.java` — **新建**
8. `backend/src/main/java/com/aicodeassistant/model/SwarmConfig.java` — **新建**

核心数据结构设计：

```java
// SwarmConfig.java — Swarm 配置
public record SwarmConfig(
    String teamName,
    int maxWorkers,           // 默认 5
    SwarmBackendType backend, // IN_PROCESS | EXTERNAL_PROCESS
    List<String> workerToolAllowList,
    List<String> workerToolDenyList,
    Path scratchpadDir
) {
    public enum SwarmBackendType { IN_PROCESS, EXTERNAL_PROCESS }
}

// SwarmState.java — Swarm 运行时状态
public record SwarmState(
    String swarmId,
    String teamName,
    SwarmPhase phase,
    Map<String, WorkerState> workers,      // workerId → state
    Instant createdAt
) {
    public enum SwarmPhase { INITIALIZING, RUNNING, IDLE, SHUTTING_DOWN, TERMINATED }
    
    public record WorkerState(
        String workerId,
        WorkerStatus status,
        int toolCallCount,
        long tokenConsumed,
        String currentTask,
        List<String> recentToolCalls    // 最近 5 个
    ) {}
    
    public enum WorkerStatus { STARTING, WORKING, IDLE, TERMINATED }
}

// SwarmWorkerRunner.java — 进程内 Worker 执行引擎
// 对标 inProcessRunner.ts (~1400行)
@Service
public class SwarmWorkerRunner {
    
    /**
     * 在 Virtual Thread 中启动一个 Worker Agent。
     * Worker 复用 QueryEngine.queryLoop() 执行引擎，但使用独立的：
     * - 工具集（过滤后）
     * - 权限模式（降级后）
     * - 上下文（隔离）
     */
    public CompletableFuture<WorkerResult> startWorker(
            String workerId, 
            String taskPrompt,
            SwarmConfig config,
            ToolUseContext parentContext) {
        return CompletableFuture.supplyAsync(() -> {
            // 1. 构建 worker 上下文（工具过滤、权限降级）
            // 2. 启动 queryLoop 执行
            // 3. 返回结果或 idle 通知
            return executeWorkerLoop(workerId, taskPrompt, config, parentContext);
        }, Thread.ofVirtual().name("swarm-worker-" + workerId).factory());
    }
    
    /**
     * Worker 执行主循环（核心方法）。
     * 复用 QueryEngine.queryLoop() 但使用隔离的工具集、权限和上下文。
     */
    private WorkerResult executeWorkerLoop(
            String workerId, String taskPrompt, 
            SwarmConfig config, ToolUseContext parentContext) {
        // 1. 构建 worker 工具集（应用 allowList/denyList 过滤）
        var filteredTools = toolRegistry.filterTools(
            config.workerToolAllowList(), config.workerToolDenyList());
        // 2. 构建 worker 上下文（独立消息历史 + scratchpad）
        var workerContext = ToolUseContext.forWorker(parentContext, config.scratchpadDir());
        // 3. 构建 worker 权限（降级为 BUBBLE 模式）
        var workerPermission = PermissionMode.BUBBLE;
        // 4. 执行查询循环（复用现有引擎）
        var result = queryEngine.execute(taskPrompt, filteredTools, workerContext, workerPermission);
        // 5. 包装结果
        return new WorkerResult(workerId, result.response(), result.toolCallCount(), result.tokenUsed());
    }
}

// LeaderPermissionBridge.java — Leader 权限桥接
// Worker 需要确认的操作通过此桥接冒泡到 Leader 的 UI
@Service
public class LeaderPermissionBridge {
    
    /**
     * 将 Worker 的权限请求冒泡到 Leader 的 WebSocket 连接。
     * Worker badge 标识来源。
     */
    public CompletableFuture<PermissionDecision> requestPermission(
            String workerId, 
            String sessionId,
            PermissionContext permContext) {
        // 通过 WebSocket 发送权限请求到前端
        // 前端显示带 worker badge 的权限对话框
        // 等待用户决策后返回
    }
}
```

**SwarmService 完整重写核心逻辑**：

```java
@Service
public class SwarmService {
    
    // 1. createSwarm(SwarmConfig) → SwarmState
    //    - 创建 TeamManager 条目
    //    - 初始化 Scratchpad 目录
    //    - 注册 SwarmState 到 AppState
    
    // 2. addWorker(swarmId, taskPrompt) → workerId
    //    - 检查 maxWorkers 限制
    //    - 通过 SwarmWorkerRunner.startWorker() 启动
    //    - 注册到 worker 状态表
    
    // 3. sendToWorker(swarmId, workerId, message) → void
    //    - 通过 TeamMailbox 发送消息
    //    - 支持纯文本和结构化消息
    
    // 4. broadcastToWorkers(swarmId, message) → void
    //    - 群发消息给所有活跃 worker
    
    // 5. shutdownSwarm(swarmId) → void
    //    - 发送 shutdown_request 给所有 worker
    //    - 等待 shutdown_response（超时 30s 后强制终止）
    //    - 清理资源
    
    // 6. getSwarmState(swarmId) → SwarmState
    //    - 返回当前状态快照
}
```

**关键实现细节**：

- **Worker 隔离**：每个 Worker 运行在独立的 Virtual Thread 中，拥有独立的 `QueryLoopState`、工具上下文、消息历史。通过 `AgentConcurrencyController` 的现有限制（全局30/会话10/嵌套3）控制并发。
- **内存防护**：每个 Worker 的消息上限 50 条（对标 `TEAMMATE_MESSAGES_UI_CAP`），超过后触发 AutoCompact。
- **并发冲突**：多个 Worker 同时编辑文件时，使用文件锁（`FileChannel.lock()`）串行化写操作。Scratchpad 目录无锁限制。
- **权限传递**：Worker 权限模式默认降级为 `BUBBLE`，通过 `LeaderPermissionBridge` 冒泡确认。`preserveMode: true` 防止 Worker 的权限变更泄漏回 Leader。
- **Idle 生命周期**：Worker 完成当前任务后进入 idle 状态，通过 TeamMailbox 发送 idle 通知（含最近 peer DM 摘要），等待 Leader 分配新任务或 shutdown。

**工作量**：12 人天（分 3 个独立子阶段）  

**子阶段分解**：

| 阶段 | 内容 | 人天 | 交付物 |
|--------|------|------|--------|
| Phase 1: 基础生命周期 | SwarmState + SwarmWorkerRunner + Worker 创建/执行/终止 | 4天 | Worker 可启动并执行任务 |
| Phase 2: 通信协议 | TeamMailbox 完整通信 + Worker 间消息传递 | 4天 | Worker 间可通信 |
| Phase 3: 权限+复用 | LeaderPermissionBridge 权限冒泡 + Idle 复用逻辑 | 4天 | 完整 Swarm 生命周期 |

> **注意**：以下新类均需新建：SwarmWorkerRunner.java、SwarmState.java、LeaderPermissionBridge.java、SwarmConfig.java
> **源码清理**：SwarmService.java 当前 L47-48、L58-59 存在 TODO 注释（`// TODO: Implement`），实施时需全部清除并替换为实际逻辑。

**完整度影响**：多Agent 75% → 88%（+13%）  
**依赖项**：无  
**风险与注意事项**：
- 内存消耗：每个 Worker 的上下文约占 2-5MB，5 个 Worker 约 10-25MB。需监控并设置告警。
- 死锁风险：Worker 间通过 TeamMailbox 通信，Leader 通过 Bridge 处理权限，需确保不会出现循环等待。
- 测试：需验证 Worker 创建/执行/idle/shutdown 完整生命周期，以及并发冲突解决。

**PC 浏览器交互设计**：

Swarm 相关的用户交互需在 React 前端完整实现：

| 交互场景 | 实现方式 | 涉及前端组件 |
|---------|---------|-------------|
| Swarm 创建/状态查看 | WebSocket 推送 `swarm_state_update` 消息 | SwarmStatusPanel.tsx（**新建**） |
| Worker 实时进度 | WebSocket 推送 `worker_progress` 消息 | WorkerProgressCard.tsx（**新建**） |
| Worker 权限冒泡确认 | WebSocket 推送 `permission_bubble` 消息 → 前端弹出确认对话框 | PermissionBubbleDialog.tsx（**新建**） |
| Worker 间通信查看 | TeamMailbox 消息通过 WebSocket 推送 | SwarmMessageLog.tsx（**新建**） |
| Swarm 关闭操作 | 前端按钮 → REST API `POST /api/swarm/{id}/shutdown` | SwarmStatusPanel.tsx 中的关闭按钮 |

**关键要求**：
- Worker 权限冒泡对话框必须阻塞等待用户决策（带超时自动拒绝，默认 60s）
- Worker 进度卡片需实时显示：workerId、当前任务、工具调用计数、token 消耗
- PC 端使用侧边面板展示 Swarm 状态，不遮挡主聊天区域

---

### 4.2 Coordinator 编排引擎

**目标**：在现有 `CoordinatorService` 基础上实现完整的四阶段工作流编排。

**当前状态**：
- `CoordinatorService.java`（181行）：模式检测、工具过滤、Scratchpad 已实现。
- `CoordinatorPromptBuilder.java`（18.8KB）：Coordinator system prompt 已建立。
- 缺失：四阶段工作流引擎、"不委派理解"原则的强制执行。

**目标状态**（Claude Code 基线）：
- 四阶段工作流：Research → Synthesis → Implementation → Verification
- Coordinator 不直接操作文件，只通过 AgentTool 派发工人。
- "永远不要委派理解"原则。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/coordinator/CoordinatorService.java` — 增强
2. `backend/src/main/java/com/aicodeassistant/coordinator/CoordinatorWorkflow.java` — **新建**
3. `backend/src/main/java/com/aicodeassistant/coordinator/CoordinatorPromptBuilder.java` — 增强
4. `backend/src/main/java/com/aicodeassistant/coordinator/ResultAggregator.java` — 增强

核心设计：

```java
// CoordinatorWorkflow.java — 四阶段工作流引擎
public sealed interface WorkflowPhase permits
    ResearchPhase, SynthesisPhase, ImplementationPhase, VerificationPhase {
    
    String phaseName();
    String phasePrompt();
    Set<String> allowedTools();  // 每阶段可用工具不同
}

public record ResearchPhase(List<String> researchTasks) implements WorkflowPhase {
    @Override public String phaseName() { return "Research"; }
    @Override public Set<String> allowedTools() { 
        return Set.of("Agent", "SendMessage"); // Research 只能派 Agent 搜索
    }
}

public record SynthesisPhase(Map<String, String> findings) implements WorkflowPhase {
    @Override public String phaseName() { return "Synthesis"; }
    @Override public Set<String> allowedTools() { 
        return Set.of("Agent", "SendMessage", "SyntheticOutput"); 
    }
}

public record ImplementationPhase(List<String> executionPlans) implements WorkflowPhase {
    @Override public String phaseName() { return "Implementation"; }
    @Override public Set<String> allowedTools() {
        return Set.of("Agent", "SendMessage", "TaskStop"); 
    }
}

public record VerificationPhase(List<String> verificationChecks) implements WorkflowPhase {
    @Override public String phaseName() { return "Verification"; }
    @Override public Set<String> allowedTools() {
        return Set.of("Agent", "SendMessage"); // 验证 Agent 只读
    }
}

@Service
public class CoordinatorWorkflowEngine {
    
    /**
     * 从 Coordinator 的对话历史中推断当前阶段。
     * 基于消息中的阶段标记和工具调用模式判断。
     */
    public WorkflowPhase detectCurrentPhase(List<Message> messages) {
        // 分析消息中的 phase markers
        // Research: 大量 Agent 调用 + 搜索类工具
        // Synthesis: SyntheticOutput 调用 + 汇总描述
        // Implementation: Agent 调用 + 编辑类工具
        // Verification: Agent 调用 + 只读工具
    }
    
    /**
     * 验证 Agent 派发指令的质量（"不委派理解"原则）。
     * 检查 AgentTool 的 prompt 参数是否包含足够具体的信息。
     */
    public ValidationResult validateDelegation(String agentPrompt) {
        // 检查 prompt 长度（过短意味着委派理解）
        // 检查是否包含具体文件路径、行号、变量名等
        // 检查是否包含模糊指令（"based on findings", "fix the bug"）
    }
}
```

**CoordinatorPromptBuilder 增强**：

在现有 Coordinator system prompt 中追加四阶段工作流指令：

```
## 四阶段工作流

你必须按以下阶段顺序执行复杂任务：

### Phase 1: Research（调研）
- 派出 Explore Agent 搜索相关代码和文件
- 每个搜索任务用独立的 Agent 并行执行
- 收集所有发现到 Scratchpad

### Phase 2: Synthesis（综合）
- 汇总所有 Research 发现
- 生成精确的执行计划（具体到文件路径、行号、修改内容）
- **核心原则：永远不要委派理解**
  - 坏：Agent({ prompt: "Based on your findings, fix the auth bug" })
  - 好：Agent({ prompt: "Fix null pointer in src/auth/validate.ts:42. 
         The user field on Session is undefined when sessions expire..." })

### Phase 3: Implementation（实施）
- 将执行计划分配给 Worker Agent
- 每个 Worker 收到完整、具体、自含的指令
- 监控 Worker 进度，处理冲突

### Phase 4: Verification（验证）
- 派出 Verification Agent 检查所有修改
- 验证 Agent 必须实际执行命令（测试、lint、build）
- 不接受"代码看起来正确"这样的验证
```

**工作量**：8 人天  
**完整度影响**：多Agent 88% → 95%（+7%）  
**依赖项**：#4.1 Swarm 模式（Coordinator 使用 Swarm 作为执行后端）  

> **注意**：CoordinatorWorkflow.java 和 WorkflowPhase 需新建，CoordinatorWorkflowEngine 需新建。
> `validateDelegation()` 实现细节：
> - 检查 AgentTool prompt 参数长度（过短意味着委派理解）
> - 检查是否包含具体文件路径、行号、变量名等
> - 检查是否包含模糊指令（"based on findings", "fix the bug"）
> - 初期仅作为 WARN 日志，不阻断执行

**风险与注意事项**：
- 四阶段不是硬编码流程，而是 prompt 引导 + 阶段检测。模型可能不严格遵循——通过 system prompt 的强措辞和阶段验证缓解。
- “不委派理解”原则的自动检测有假阳性风险，初期仅作为 WARN 日志，不阻断执行。

**PC 浏览器交互设计**：

Coordinator 四阶段工作流需在前端可视化：
- **阶段指示器**：在聊天区域顶部显示当前阶段（Research → Synthesis → Implementation → Verification），使用进度条或步骤条组件
- **Agent 派发可视化**：每次 Coordinator 派发 Agent 时，前端显示 Agent 卡片（名称、任务摘要、状态）
- **“不委派理解”违规提示**：`validateDelegation()` 检测到模糊指令时，通过 WebSocket 推送 `delegation_warning` 消息，前端显示黄色警告条
- 涉及前端组件：WorkflowPhaseIndicator.tsx（**新建**）、AgentTaskCard.tsx（**新建**）

---

### 4.3 Agent 类型扩展

**目标**：实现 sealed interface 类型化和智能路由逻辑，将现有 5 种 AgentDefinition 配置升级为类型化系统。

**当前状态**（源码审查修正）：
- ✅ **已有 5 种 AgentDefinition 配置**：`SubAgentExecutor.java`（45.5KB）中已配置 EXPLORE、VERIFICATION、PLAN、GENERAL_PURPOSE、GUIDE 五种定义。
- ✅ denied tools 配置已一致：EXPLORE/VERIFICATION/PLAN 均配置了 5 个相同的 denied tools。
- ❌ **缺 sealed interface 类型化**：5 种定义仅为配置数据，未实现类型化行为分化。
- ❌ **缺智能路由逻辑**：Agent 创建时未根据任务类型自动选择最佳 AgentDefinition。

**目标状态**（Claude Code 基线）：
- 4 种内置类型：general-purpose / Explore / Plan / Verification
- 每种类型有不同的模型选择、工具限制、system prompt 优化。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/tool/agent/BuiltInAgentDefinition.java` — **新建**
2. `backend/src/main/java/com/aicodeassistant/tool/agent/AgentTool.java` — 路由增强
3. `backend/src/main/java/com/aicodeassistant/tool/agent/SubAgentExecutor.java` — 类型适配

核心设计：

```java
// BuiltInAgentDefinition.java
public sealed interface BuiltInAgentDefinition permits
    GeneralPurposeAgent, ExploreAgent, PlanAgent, VerificationAgent {
    
    String type();
    String description();
    Set<String> allowedTools();
    Set<String> deniedTools();
    String modelOverride();       // null = inherit parent
    boolean isAsync();
    String systemPromptOverride();
}

public record ExploreAgent() implements BuiltInAgentDefinition {
    @Override public String type() { return "explore"; }
    @Override public Set<String> allowedTools() {
        return Set.of("FileRead", "Grep", "Glob", "Bash"); // 只读工具
    }
    @Override public Set<String> deniedTools() {
        return Set.of("FileEdit", "FileWrite", "Agent"); // 禁止写和递归
    }
    @Override public String modelOverride() { return null; } // 可配置为轻量模型
    @Override public boolean isAsync() { return false; }
    // 优化：省略 CLAUDE.md 和 gitStatus 注入，节省 token
}

public record VerificationAgent() implements BuiltInAgentDefinition {
    @Override public String type() { return "verification"; }
    @Override public Set<String> deniedTools() {
        return Set.of("FileEdit", "FileWrite"); // 只读（项目目录），可写 /tmp
    }
    @Override public boolean isAsync() { return true; } // 总是异步运行
    // 反自我欺骗 prompt：要求每个检查有实际命令和输出
}
```

**工作量**：4 人天  
**完整度影响**：多Agent +3%  
**依赖项**：无  

---

## 五、上下文管理提升（82% → 91%+）

> **审查修正说明**：原方案 5.3（高级压缩级联 L3-L4）已删除——ReactiveCompactService 不存在，仅在极端场景触发，Level 4 激进削减用户体验损害大，ROI 低。目标完整度从 95% 调整为 91%。

### 5.1 Context Collapse 渐进增强

**目标**：将现有基础 Context Collapse 升级为渐进式细节折叠，支持与 AutoCompact 的互斥协调。

**当前状态**（源码审查修正）：
- ✅ **框架已完善**：`ContextCollapseService.java`（170行）已实现基础版，已集成到 ContextCascade Level 1.5。
- ✅ 现有两级：
  - Level A：保护尾部 N 条消息
  - Level B：长文本截断 + 工具结果 `[collapsed]` 替换
- ❌ **缺失三级渐进折叠**：未按消息年龄/重要性分级。
- ❌ **缺失与 AutoCompact 互斥协调**。
- ❌ **缺失用户消息保留策略**。

**目标状态**（Claude Code 基线）：
- 渐进式消息折叠：最近消息保留原文 → 较早消息保留摘要 → 更早的只保留关键事实。
- 与 AutoCompact 互斥：启用 Collapse 时，proactive AutoCompact 被抑制。
- 保留所有用户消息（防止丢失用户反馈）。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/engine/ContextCollapseService.java` — 渐进折叠增强
2. `backend/src/main/java/com/aicodeassistant/engine/ContextCascade.java` — 互斥协调
3. `backend/src/main/java/com/aicodeassistant/engine/CompactService.java` — AutoCompact 抑制
4. `backend/src/main/java/com/aicodeassistant/engine/CollapseLevel.java` — **新建**，sealed interface 定义三级折叠策略

核心增强设计：

```java
// ContextCollapseService.java — 渐进式折叠
public sealed interface CollapseLevel permits
    FullRetention, SummaryRetention, SkeletonRetention {
    int maxAgeMessages();  // 距离尾部的消息数阈值
}

// 三级渐进策略：
// Level A: 尾部 10 条 → 完整保留（现有 protectedTail）
// Level B: 倒数 10-30 条 → 长文本截断（现有 truncate 逻辑）
// Level C: 30 条以前 → 骨架化（role + toolUseId + 一行摘要）

public CollapseResult progressiveCollapse(List<Message> messages, int tokenBudget) {
    // 从尾部向头部遍历，按距离分级处理
    // 关键保留规则：所有 UserMessage（非工具结果）永远保留原文
    // 这防止模型丢失用户的反馈（如"不要用 Redux"）
}

// ContextCascade.java — 互斥协调
// 在级联触发逻辑中：
// if (contextCollapseEnabled && collapseResult.freedTokens > requiredTokens) {
//     skipAutoCompact = true;  // Collapse 已释放足够空间，抑制 AutoCompact
// }
```

**工作量**：4 人天（原估 5 人天，框架已完善，功能扩展）  
**完整度影响**：上下文管理 82% → 87%（+5%）  
**依赖项**：无  
**状态**：✅ 框架完善，功能扩展  
**风险与注意事项**：
- 渐进折叠可能折叠了模型后续需要的信息。通过保留所有用户消息和工具调用的 toolUseId 缓解。
- 折叠后的消息仍保留 role 和结构，模型可通过工具重新获取已折叠的详细内容。

---

### 5.2 压缩后关键文件重注入

**目标**：AutoCompact 压缩后，自动重新注入最多 5 个关键文件的内容和 skill 指令。

**当前状态**（源码审查修正）：
- `CompactService.java`（39.7KB）实现了 AutoCompact LLM 摘要，但压缩后不重新注入任何内容。
- ❌ **KeyFileTracker 完全不存在**，需作为全新功能实现。
- 压缩后模型只有一个抽象摘要，丢失了关键文件的具体内容。

**目标状态**（Claude Code 基线）：
- 压缩后重新注入最多 5 个关键文件（每个最多 5000 tokens）。
- 重新注入 skill 指令（最多 25000 tokens）。
- 保留所有用户消息。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/engine/CompactService.java` — 增加重建阶段
2. `backend/src/main/java/com/aicodeassistant/engine/KeyFileTracker.java` — **新建**
3. `backend/src/main/java/com/aicodeassistant/service/FileStateCache.java` — **需确认是否存在，若不存在则新建**，关键文件状态缓存

核心设计：

```java
// KeyFileTracker.java — 追踪对话中频繁引用的文件
@Service
public class KeyFileTracker {
    
    // Caffeine 缓存：sessionId → Map<filePath, referenceCount>
    private final Cache<String, ConcurrentHashMap<String, AtomicInteger>> sessionFileRefs;
    
    /**
     * 记录文件引用（在 FileReadTool、FileEditTool 执行时调用）
     */
    public void trackFileReference(String sessionId, String filePath) {
        sessionFileRefs.get(sessionId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(filePath, k -> new AtomicInteger(0))
            .incrementAndGet();
    }
    
    /**
     * 获取 Top-N 关键文件（按引用次数排序）
     */
    public List<String> getKeyFiles(String sessionId, int maxCount) {
        var refs = sessionFileRefs.getIfPresent(sessionId);
        if (refs == null) return List.of();
        return refs.entrySet().stream()
            .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                Comparator.comparingInt(AtomicInteger::get)).reversed())
            .limit(maxCount)
            .map(Map.Entry::getKey)
            .toList();
    }
}

// CompactService.java — 压缩后重建
// 在 autoCompact() 方法末尾追加：
private List<Message> rebuildAfterCompact(
        List<Message> compactedMessages, String sessionId) {
    // 1. 获取 Top-5 关键文件
    var keyFiles = keyFileTracker.getKeyFiles(sessionId, 5);
    
    // 2. 读取每个文件内容（最多 5000 tokens）
    var fileContents = keyFiles.stream()
        .map(path -> readFileTruncated(path, 5000))
        .filter(Objects::nonNull)
        .toList();
    
    // 3. 构建重注入消息
    if (!fileContents.isEmpty()) {
        String injection = "## Key Files (re-injected after compression)\n\n"
            + fileContents.stream()
                .map(fc -> "### " + fc.path() + "\n```\n" + fc.content() + "\n```")
                .collect(Collectors.joining("\n\n"));
        // 作为 system 消息注入
        compactedMessages.add(Message.system(injection));
    }
    
    return compactedMessages;
}
```

**工作量**：4 人天  
**完整度影响**：上下文管理 87% → 91%（+4%）  
**依赖项**：#5.1 Context Collapse 增强  
**状态**：❌ 全新功能，KeyFileTracker 需全新创建  

**配置项定义**：
- `reinjected-files-limit=5` — 最多重注入文件数
- `reinjected-file-max-tokens=5000` — 单文件最大 token 数

**埋点位置**：需在以下工具中调用 `KeyFileTracker.trackFileReference()`：
- `FileReadTool` — 每次读取文件时记录
- `FileEditTool` — 每次编辑文件时记录
- `GrepTool` — 每次搜索命中的文件时记录

**去重处理**：`KeyFileTracker.trackFileReference()` 应在同一轮对话中对同一文件只计数一次（避免循环读取同一文件导致计数膨胀）。实现方式：在 `sessionFileRefs` 中使用 `Set<String>` 记录本轮已计数的 (sessionId, filePath, turnId) 三元组。

---

## 六、MCP集成提升（78% → 92%+）

### 6.1 资源发现完整实现

**目标**：将 `resources/list` 从占位实现升级为完整的资源发现和访问功能。

**当前状态**（源码审查修正）：
- `ListMcpResourcesTool.java`（4.9KB）和 `ReadMcpResourceTool.java`（4.4KB）存在但为骨架。
- ❌ **资源发现未实现**：`McpServerConnection.getResources()` 返回 `List.of()`（L351），无实际发现逻辑。
- ❌ 需新增 `discoverResources()` 方法（参照 `discoverTools()` L142-169 模式）。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/mcp/ListMcpResourcesTool.java` — 完整实现
2. `backend/src/main/java/com/aicodeassistant/mcp/ReadMcpResourceTool.java` — 完整实现
3. `backend/src/main/java/com/aicodeassistant/mcp/McpServerConnection.java` — 资源缓存
4. `frontend/src/components/` — 资源列表 UI 组件

核心实现要点：

1. **新增 `McpServerConnection.discoverResources()` 方法**（参照 `discoverTools()` L142-169 模式）：
   - 在 `performProtocolHandshake()` 末尾（L92-137 之后）调用 `discoverResources()`
   - 发送 `resources/list` 请求，解析 resources 数组
   - 缓存到 `this.resources` 字段（Caffeine, TTL 60s）
   - 错误处理：`McpProtocolException` 时记录 WARN 日志，不中断握手

```java
// McpServerConnection.java — 新增方法（L170 之后）
public void discoverResources() {
    try {
        JsonNode result = transport.sendRequest("resources/list", Map.of(), DEFAULT_REQUEST_TIMEOUT_MS);
        if (result != null && result.has("resources") && result.get("resources").isArray()) {
            List<McpResourceDefinition> discovered = new ArrayList<>();
            for (JsonNode resNode : result.get("resources")) {
                String uri = resNode.path("uri").asText(null);
                String name = resNode.path("name").asText("");
                String mimeType = resNode.path("mimeType").asText(null);
                String description = resNode.path("description").asText("");
                if (uri != null) {
                    discovered.add(new McpResourceDefinition(uri, name, mimeType, description));
                }
            }
            this.resources = List.copyOf(discovered);
            log.info("MCP server '{}': discovered {} resources", config.name(), discovered.size());
        }
    } catch (McpProtocolException e) {
        log.warn("resources/list failed for '{}': {}", config.name(), e.getMessage());
    }
}
```

2. **`ReadMcpResourceTool`** 通过 `resources/read` 获取资源内容，支持 text 和 blob 两种格式
3. **前端**：在 MCP 面板中增加“资源”标签页，展示已发现的资源列表
4. **API 端点**：新增 `GET /api/mcp/{serverId}/resources` 返回资源列表

**工作量**：7 人天（后端 discoverResources + API 端点 3 天 + 前端 ResourcesPanel UI 3 天 + 测试 1 天）  
**完整度影响**：MCP 78% → 83%（+5%）  
**依赖项**：无  

**PC 浏览器交互设计**：

| UI 组件 | 功能 | 实现方式 |
|---------|------|--------|
| ResourcesPanel.tsx（**新建**） | MCP 面板中"资源"标签页 | 列表展示 uri/name/mimeType/description |
| ResourceDetailDrawer.tsx（**新建**） | 资源详情抽屉 | 点击资源行 → 右侧抽屉展示完整内容 |
| 资源搜索过滤 | 按名称/URI 过滤 | 顶部搜索框 + debounce |
| 资源内容预览 | text 类型直接展示，blob 类型显示下载链接 | 根据 mimeType 渲染不同预览器 |

WebSocket 消息类型：`mcp_resources_updated`（当 MCP 服务器资源列表变更时推送）  

---

### 6.2 提示词发现 UI 集成

**目标**：将已有的 `prompts/list` 调用结果集成到前端 UI。

**当前状态**：
- `McpPromptAdapter.java`（4.7KB）已实现 prompts/list 调用。
- 前端无对应 UI 展示。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/controller/McpController.java` — 新增 `GET /api/mcp/{serverId}/prompts` 端点，返回 `List<{name, description, arguments}>`
2. `frontend/src/components/MCP/` — 新增 Prompts 选项卡组件（与 Tools 并列显示在 MCP 面板中）
3. `frontend/src/store/mcpStore.ts` — 新增 `addPrompts(serverId, prompts)` 状态管理方法

**用户交互流程**：用户选择 MCP 服务器 → 查看 Prompts 标签页 → 选择某个 Prompt → 注入到输入框

**工作量**：3 人天（后端 API 0.5 天 + 前端 PromptsTab UI 2 天 + 测试 0.5 天）  
**完整度影响**：MCP 83% → 85%（+2%）  
**依赖项**：无  

**PC 浏览器交互设计**：

PromptsTab 需支持以下交互流程：
1. 用户在 MCP 面板中切换到 "Prompts" 标签页
2. 展示 Prompt 列表（name + description），支持搜索过滤
3. 用户点击某个 Prompt → 展开参数输入表单（动态生成 required/optional 字段）
4. 用户填写参数后点击"使用" → Prompt 内容注入到聊天输入框
5. 涉及前端组件：PromptsTab.tsx（**新建**）、PromptArgsForm.tsx（**新建**）

---

### 6.3 主动健康检查增强

**目标**：增强现有 `SseHealthChecker` 的自动重连策略。

**当前状态**：
- `SseHealthChecker.java`（50行）已实现 30s 周期 ping + DEGRADED 标记 + 重连调度。
- 缺失：指数退避重连策略细化、连接状态监控指标。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/mcp/SseHealthChecker.java` — 指标增强
2. `backend/src/main/java/com/aicodeassistant/mcp/McpClientManager.java` — 重连策略细化

增强要点：
- 添加连接健康指标：`consecutiveFailures`、`lastSuccessfulPing`、`reconnectAttempts`
- 重连策略：1s → 2s → 4s → 8s → 16s → 30s cap
- WebSocket 端点暴露连接状态给前端

**工作量**：1.5 人天（SseHealthChecker 核心已完整实现，仅需指数退避细化 + 指标暴露）  
**完整度影响**：MCP 85% → 87%（+2%）  
**依赖项**：无  
**状态**：✅ 核心已实现（30s ping + DEGRADED + 重连调度），增强项为指数退避和指标端点  

**PC 浏览器交互设计**：

MCP 连接状态需在前端实时展示：
- MCP 面板中每个服务器旁显示连接状态图标（🟢 正常 / 🟡 降级 / 🔴 断开）
- WebSocket 消息类型：`mcp_health_status`（推送连接状态变更）
- 用户可手动点击"重连"按钮触发 `POST /api/mcp/{serverId}/reconnect`
- 涉及前端组件：McpConnectionIndicator.tsx（**新建**）

---

### 6.4 企业策略支持

**目标**：MCP 服务器的 allowlist/denylist 过滤 + `allowManagedPermissionRulesOnly` 配置。

**当前状态**（源码审查修正）：
- `McpConfigurationResolver.java` 支持 4 层配置合并但无企业策略过滤。
- `PolicySettingsSource.java` 存在但未集成到 MCP 层。
- ❌ 需新增 MCP allowlist/denylist 过滤（而非“已支持”）。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/mcp/McpConfigurationResolver.java` — 新增 allowlist/denylist 过滤
2. `backend/src/main/java/com/aicodeassistant/mcp/McpClientManager.java` — 过滤集成
3. `backend/src/main/java/com/aicodeassistant/permission/PolicySettingsSource.java` — 修复 `loadRules()` 竞态条件（使用 `AtomicLong` 替代 `long lastModified` 比较，避免 `synchronized` 的性能开销）

```java
// McpConfigurationResolver.java — 企业策略过滤
public record McpEnterprisePolicy(
    Set<String> allowlist,    // 允许的 MCP 服务器名称，空 = 全允许
    Set<String> denylist,     // 禁止的 MCP 服务器名称
    boolean managedRulesOnly  // true 时只加载策略中的服务器配置
) {}

// 在 resolveConfigurations() 中应用过滤：
// 1. 如果 managedRulesOnly=true，忽略所有非策略来源的配置
// 2. denylist 匹配的服务器被移除
// 3. 如果 allowlist 非空，只保留匹配的服务器
```

**工作量**：5 人天（后端策略过滤 2 天 + 前端策略状态 UI 2 天 + 测试 1 天）  
**完整度影响**：MCP 87% → 92%+（+5%）  
**依赖项**：#8.2 企业策略覆盖（共享 PolicySettingsSource）  
**风险等级**：MEDIUM — 会影响所有 MCP 服务器加载流程，需完整集成测试覆盖 managedRulesOnly/allowlist/denylist 三种模式  

**PC 浏览器交互设计**：

企业策略影响需在前端可见：
- MCP 面板中被 denylist 禁用的服务器显示"企业策略已禁用"灰色标签，不可操作
- allowlist 模式下，未列入白名单的服务器不显示在面板中
- WebSocket 消息类型：`mcp_policy_update`（策略变更时推送，前端刷新列表）
- 涉及前端组件：PolicyStatusBadge.tsx（**新建**）

---

## 七、Agent Loop 提升（90% → 93%）

> **审查修正说明**：原方案 7.2（消息扣留与选择性重试）已删除——MessageHoldback.java 不存在需全新创建，且依赖已删除的 5.3（ReactiveCompactService），依赖链断裂。目标完整度从 97% 调整为 93%。

### 7.1 Token 预算续写增强

**目标**：增强现有 `TokenBudgetTracker` 的集成深度。

**当前状态**（源码审查修正）：
- ✅ **核心逻辑已完整实现**：`TokenBudgetTracker.java`（92行）已完整实现 nudge + 递减回报检测。
- ✅ 测试报告确认 TokenBudget 续写已通过验证。
- ❌ **仅缺前端 UI**：nudge 消息未通过 WebSocket 推送到前端展示。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` — nudge 消息 WebSocket 推送
2. `frontend/src/components/` — nudge 消息 UI 提示

增强要点：
- 当 `ContinueDecision` 触发时，通过 WebSocket 推送 `TOKEN_BUDGET_NUDGE` 消息到前端
- 前端显示 token 使用进度条（当前百分比 / 预算）
- 确保子 agent（`agentId != null`）严格不参与预算续写

**WebSocket 消息格式**：
```json
{
  "type": "token_budget_nudge",
  "pct": 85,
  "currentTokens": 8500,
  "budgetTokens": 10000
}
```

**前端集成位置**：在 ChatMessageItem 组件中接收 `token_budget_nudge` 消息，渲染为进度条提示（样式参考现有 RateLimitWarning 组件）。

**工作量**：3 人天（后端 WebSocket 推送集成 1 天 + 前端 TokenBudgetIndicator 组件 1.5 天 + 测试 0.5 天）  
**完整度影响**：Agent Loop 90% → 93%（+3%）  
**依赖项**：无  
**状态**：✅ 核心逻辑已完整实现，仅需前端 nudge 消息推送

---

## 八、权限系统提升（93% → 98%）

### 8.1 远程熔断集成

**目标**：实现基于 Feature Flag 的远程权限降级能力。

**当前状态**：
- `FeatureFlagService` 已存在，支持运行时 flag 切换。
- 无 bypassPermissions 远程禁用机制。
- 无 auto 模式分类器远程熔断。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/permission/RemoteCircuitBreaker.java` — **新建**
2. `backend/src/main/java/com/aicodeassistant/permission/PermissionPipeline.java` — 集成熔断检查
3. `backend/src/main/java/com/aicodeassistant/config/FeatureFlagService.java` — 远程刷新

核心设计：

```java
// RemoteCircuitBreaker.java
@Service
public class RemoteCircuitBreaker {
    
    private final FeatureFlagService featureFlags;
    
    /**
     * 检查 bypass 模式是否被远程禁用。
     * Feature flag: BYPASS_PERMISSIONS_KILLSWITCH
     */
    public boolean isBypassKilled() {
        return featureFlags.isEnabled("BYPASS_PERMISSIONS_KILLSWITCH");
    }
    
    /**
     * 检查 auto 模式分类器是否被远程熔断。
     * Feature flag: AUTO_MODE_CIRCUIT_BROKEN
     */
    public boolean isAutoModeCircuitBroken() {
        return featureFlags.isEnabled("AUTO_MODE_CIRCUIT_BROKEN");
    }
    
    /**
     * 获取降级后的权限模式。
     */
    public PermissionMode getEffectiveMode(PermissionMode requested) {
        if (requested == PermissionMode.BYPASS_PERMISSIONS && isBypassKilled()) {
            return PermissionMode.DEFAULT;  // 降级为默认模式
        }
        if (requested == PermissionMode.AUTO && isAutoModeCircuitBroken()) {
            return PermissionMode.DEFAULT;  // 降级为默认模式
        }
        return requested;
    }
}
```

**工作量**：6 人天（后端 RemoteCircuitBreaker + FeatureFlag 集成 3 天 + 前端降级通知 UI 2 天 + 测试 1 天）  
**完整度影响**：权限系统 93% → 95%（+2%）  
**依赖项**：无  
**风险等级**：HIGH — 涉及权限系统降级，必须有完整集成测试覆盖 bypass/auto 模式被远程禁用时正确降级至 DEFAULT  

**PC 浏览器交互设计**：

权限降级需在前端实时通知用户：
- WebSocket 消息类型：`permission_downgrade`（推送降级事件）
- 前端权限设置面板中显示"⚠️ 权限模式已被远程降级为 DEFAULT"的橙色警告条
- 降级原因通过 tooltip 或展开详情说明
- 涉及前端组件：PermissionDowngradeAlert.tsx（**新建**）

---

### 8.2 企业策略覆盖

**目标**：支持企业管理员通过 `PolicySettingsSource` 强制覆盖权限规则。

**当前状态**：
- `PolicySettingsSource.java`（4.9KB）已存在。
- `PermissionRuleRepository.java`（10.1KB）支持三来源规则（全局/项目/会话）。
- 缺失：`allowManagedPermissionRulesOnly` 锁定模式。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/permission/PolicySettingsSource.java` — 锁定模式
2. `backend/src/main/java/com/aicodeassistant/permission/PermissionRuleRepository.java` — 策略优先

```java
// PolicySettingsSource.java — 新增锁定模式
public boolean isManagedRulesOnly() {
    return configService.getBoolean("policy.allowManagedPermissionRulesOnly", false);
}

// PermissionRuleRepository.java — 加载时检查
public List<PermissionRule> loadEffectiveRules(String sessionId) {
    if (policySettingsSource.isManagedRulesOnly()) {
        // 锁定模式：只加载策略规则
        return policySettingsSource.getRules();
    }
    // 正常模式：合并所有来源（策略优先级最高）
    return mergeRules(globalRules, projectRules, sessionRules, policyRules);
}
```

**工作量**：3 人天  
**完整度影响**：权限系统 95% → 97%（+2%）  
**依赖项**：无  

**PC 浏览器交互设计**：

锁定状态需在前端权限设置面板可见：
- `managedRulesOnly` 启用时，设置面板中"自定义规则"区域灰显并显示"企业策略已锁定权限规则"提示
- 用户仍可查看规则列表（只读），但无法新增/修改/删除
- 涉及前端组件：PolicyLockIndicator.tsx（**新建**）

---

### 8.3 规则遮蔽检测

**目标**：检测矛盾的权限规则并在 UI 中警告用户。

**当前状态**：无冲突检测机制。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/permission/ShadowedRuleDetector.java` — **新建**
2. `backend/src/main/java/com/aicodeassistant/permission/PermissionRuleRepository.java` — 集成检测
3. `frontend/src/components/settings/` — 规则冲突警告 UI

核心设计：

```java
// ShadowedRuleDetector.java
@Service  
public class ShadowedRuleDetector {
    
    public record ShadowWarning(
        PermissionRule shadowedRule,   // 被遮蔽的规则
        PermissionRule shadowingRule,  // 遮蔽它的规则
        String reason                 // 人类可读的说明
    ) {}
    
    /**
     * 检测规则集中的遮蔽关系。
     * 例：deny: ["Bash"] 会遮蔽 allow: ["Bash(ls:*)"]
     * 因为 deny 在管线中先于 allow 检查。
     */
    public List<ShadowWarning> detectShadows(List<PermissionRule> rules) {
        var warnings = new ArrayList<ShadowWarning>();
        // 对每对规则检查：
        // 1. deny 规则遮蔽同工具的 allow 规则
        // 2. 宽泛规则遮蔽窄规则（Bash 遮蔽 Bash(ls:*)）
        // 3. 不同来源的同名规则冲突
        return warnings;
    }
}
```

**工作量**：5 人天（后端遮蔽检测算法 2 天 + 前端冲突警告 UI 2 天 + 测试 1 天）  
**完整度影响**：权限系统 97% → 98%（+1%）  
**依赖项**：无  
**优先级建议**：建议从 P2 后移至 Week 6+，用户价值中等（仅为警告提示），ROI 较低  

**PC 浏览器交互设计**：

规则冲突需在前端权限设置面板警告：
- SettingsPanel 中"权限规则"区域底部显示冲突检测结果
- 冲突规则用橙色高亮，附带"遮蔽原因"说明和"修复建议"链接
- 用户可点击"自动修复"执行推荐的规则调整
- 涉及前端组件：ShadowRuleWarning.tsx（**新建**）

---

## 九、实施路线图

### Week 1-2: 最高优先级（P0）

| 周次 | 任务 | 模块 | 人天 | 交付物 |
|------|------|------|------|--------|
| W1 | 流式工具启动（微调） | 工具 | 2 | StreamCollector 回调时序优化 |
| W1 | 工具排序与 Cache | 工具 | 3 | Caffeine 缓存层 + cache breakpoint |
| W1-2 | Swarm 核心实现 (Phase 1) | 多Agent | 4 | SwarmState + SwarmWorkerRunner + Worker 生命周期 |
| W2 | Context Collapse 增强 | 上下文 | 4 | 三级渐进折叠 + AutoCompact 互斥 |
| W2 | Token 预算 nudge UI | Agent Loop | 3 | WebSocket nudge 推送 + 前端 TokenBudgetIndicator |

**W1-2 里程碑**：工具并发性能提升 + Swarm 基础可用 + 上下文管理增强

### Week 3-4: 高优先级（P1）

| 周次 | 任务 | 模块 | 人天 | 交付物 |
|------|------|------|------|--------|
| W3 | Swarm 完善 (Phase 2+3) | 多Agent | 8 | 通信协议 + 权限冒泡 + idle 复用 |
| W3 | Coordinator 四阶段 | 多Agent | 8 | CoordinatorWorkflow + 阶段 prompt |
| W3 | 压缩后文件重注入 | 上下文 | 4 | KeyFileTracker + rebuild 逻辑 |
| W4 | 资源发现完整化 | MCP | 7 | discoverResources + ResourcesPanel UI |
| W4 | contextModifier 传播 | 工具 | 2 | StreamingToolExecutor 分区传播 |

**W3-4 里程碑**：Coordinator 编排可用 + MCP 资源完整 + 上下文重建

### Week 5-6: 中优先级（P2）

| 周次 | 任务 | 模块 | 人天 | 交付物 |
|------|------|------|------|--------|
| W5 | 远程熔断 + 企业策略 | 权限 | 9 | RemoteCircuitBreaker + PolicySettingsSource + 降级通知 UI |
| W5 | 规则遮蔽检测 | 权限 | 5 | ShadowedRuleDetector + ShadowRuleWarning UI |
| W5 | Agent 类型扩展 | 多Agent | 4 | sealed interface 类型化 + 路由逻辑 |
| W6 | 其他工具增强 | 工具 | 6 | mapToolResult + BashTool 安全 |
| W6 | 提示词UI + 企业MCP | MCP | 8 | PromptsTab UI + PolicyStatusBadge + allowlist/denylist |

**W5-6 里程碑**：全功能实现 + 企业级特性

### Week 7: 收尾与集成测试

| 任务 | 人天 | 描述 |
|------|------|------|
| 集成测试 | 2 | 跨模块联调（Swarm + Coordinator + 权限冒泡） |
| 性能测试 | 1 | Swarm 内存占用、工具并发延迟、Cache 命中率 |
| 回归测试 | 1 | 确保现有 14/14 模块测试全部通过 |
| 文档更新 | 1 | 更新对标分析报告 + 测试报告 |

**W7 里程碑**：95%+ 验收达标

### 前端工作量明细

本计划的前端开发工作量分布如下（PC 浏览器交互为必须项，移动端响应式为可选优化）：

| 模块 | 前端组件 | 人天 | 阶段 |
|------|---------|------|------|
| Swarm 状态 | SwarmStatusPanel + WorkerProgressCard + PermissionBubbleDialog | 3 | W3-4 |
| Coordinator 可视化 | WorkflowPhaseIndicator + AgentTaskCard | 2 | W3-4 |
| MCP 资源 | ResourcesPanel + ResourceDetailDrawer | 3 | W4 |
| MCP 提示词 | PromptsTab + PromptArgsForm | 2 | W6 |
| MCP 状态 | McpConnectionIndicator + PolicyStatusBadge | 1 | W5 |
| Token 预算 | TokenBudgetIndicator | 1.5 | W2 |
| 权限 UI | PermissionDowngradeAlert + PolicyLockIndicator + ShadowRuleWarning | 2.5 | W5-6 |
| **前端总计** | **15 个新建组件** | **15 人天** | W2-6 |

> **说明**：前端工作量已包含在各方案人天估算中，此处为汇总视图。建议配备 1 名专职前端开发与后端并行推进。

---

## 十、验收标准

### 10.1 各模块验收条件

| 模块 | 验收条件 | 验证方法 |
|------|---------|--------|
| 工具系统 (95%) | ① 3个 FileRead 并行执行延迟 < 单个的 1.5x ② tool_use block 收到后 < 50ms 启动执行 ③ 内建/MCP 工具分区排序 + cache breakpoint 存在 ④ contextModifier 分区传播正确 | 单元测试 + 性能测试 |
| 多Agent (95%) | ① Swarm 创建 3 Worker 并行执行任务 ② Coordinator 四阶段流转完整 ③ Worker 权限冒泡到 Leader UI ④ Worker idle 后可复用 ⑤ 5 种 Agent 配置（general-purpose/Explore/Plan/Verification/Guide）类型化路由 | 集成测试 + E2E |
| 上下文 (91%) | ① Context Collapse 三级渐进折叠消息数减少 >30% 但语义保留 ② 压缩后 Top-5 关键文件自动重注入 | 长对话测试 |
| MCP (92%) | ① resources/list 返回完整资源列表 ② prompts/list 结果在前端可浏览 ③ 企业 denylist 有效过滤 | API 测试 + UI 测试 |
| Agent Loop (93%) | ① nudge 消息在前端显示 token 进度 | 触发测试 |
| 权限系统 (98%) | ① bypass 模式被远程 killswitch 降级 ② 矛盾规则 UI 显示警告 ③ managedRulesOnly 锁定模式生效 | 配置测试 |

### 10.1.1 PC 浏览器 UI 交互验收条件

| 模块 | UI 验收条件 | 验证方法 |
|------|-----------|----------|
| 工具系统 (95%) | 前端工具列表可显示分区排序结果（内建/MCP 分组） | Playwright E2E 测试 |
| 多Agent (95%) | ① Swarm 状态面板实时显示 Worker 进度 ② 权限冒泡对话框可弹出并等待用户决策 ③ Coordinator 阶段指示器正确流转 | Playwright E2E + 手动验证 |
| 上下文 (91%) | 压缩后关键文件重注入对用户透明（无 UI 干预） | 后端单元测试即可 |
| MCP (92%) | ① 资源列表标签页可浏览、搜索、查看详情 ② Prompts 标签页可选择并填写参数 ③ 连接状态图标实时更新 ④ 被禁用服务器显示策略标签 | Playwright E2E + 手动验证 |
| Agent Loop (93%) | Token 预算进度条在前端实时显示并响应式适配 | Playwright E2E |
| 权限系统 (98%) | ① 远程降级时显示橙色警告条 ② 锁定模式下规则编辑区灰显 ③ 冲突规则橙色高亮 + 修复建议 | Playwright E2E + 手动验证 |

### 10.2 整体验收标准

| 指标 | 目标值 | 验证方法 |
|------|--------|---------|
| 功能对标完整度 | ≥ 94% | 重新执行对标分析 |
| 现有测试回归 | 14/14 PASS | 运行全量测试 |
| BashTool 安全 | 113/113 PASS | 运行安全测试套件 |
| P0/P1 问题 | 0 个 | 全量问题扫描 |
| 性能回归 | 无 | 核心工具执行 < 5ms |
| 内存稳定性 | Swarm 5 Worker < 50MB 增量 | 压力测试 |

### 10.3 最终完整度预期

```
Agent Loop:     █████████▊ 93%  (+3%)
工具系统:       █████████▌ 95%  (+23%)
权限系统:       █████████▊ 98%  (+5%)
MCP集成:        █████████▏ 92%  (+14%)
上下文管理:     █████████▏ 91%  (+9%)
多Agent协作:    █████████▌ 95%  (+20%)
──────────────────────────────────
整体:           █████████▌ 94.0% (+12.3%)
```

---

> **文档生成时间**: 2026-04-18  
> **审查修正时间**: 2026-04-18（v3.1 三轮 13 维度审查后修正，新增浏览器交互适配性维度）  
> **输入依据**: 功能对标分析报告 + 测试报告 v3.1 + Claude Code 架构分析 + 实际源码审查  
> **覆盖范围**: 16 个实施项、15 个前端新建组件、7-8 周实施周期、88.5-99 人天工作量  
> **审查变更摘要（v1→v2）**: 删除 4 个低ROI/不存在方案（3.4 shouldDefer、4.3 外部进程、5.3 L3-L4 级联、7.2 消息扣留），修正 11 个方案的源码状态描述和工作量估算  
> **审查变更摘要（v2→v3）**: 补充 discoverResources() 完整实现、为所有新增核心类统一添加「新建」标注（含 11 个后端 Java 类）、修正并发安全方案（AtomicLong 替代 synchronized）、补充 WebSocket 消息格式和前端集成细节、6 个方案工作量修正（总增 +7 人天）、补充验收定量指标  
> **审查变更摘要（v3→v3.1）**: 第三轮工作量修正——3.2(+1)、6.1(+2)、6.2(+1)、6.4(+1)、7.1(+1)、8.1(+1)、8.3(+1)，共 7 个方案净增 +8 人天；同步更新 1.4 总工作量表、2.1 ROI 排序表、路线图各 Week 表格及文末覆盖范围  
> **审查变更摘要（v3→v3.1）**: 第 13 维度浏览器交互适配性审查——为 10 个方案补充 PC 浏览器交互设计（Swarm/Coordinator/MCP/Token预算/权限系统）、新增 15 个前端 React 组件规格、新增 10.1.1 UI 交互验收条件、新增前端工作量汇总明细表6 个方案工作量上调（总增 +7 人天，累计 88.5-99 人天）
