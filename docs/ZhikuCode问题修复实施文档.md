# ZhikuCode 问题修复实施文档

> **基准报告**: ZhikuCode 核心功能测试报告 v3 (2026-04-16)  
> **问题总数**: 35 项（P0×6 / P1×8 / P2×7 / P3×4 / 功能扩展×5 / 90%对标补充×4 / 个人增强×1）— 原 41 项中删除 6 项：团队功能 EXT-01(SwarmService)/FEAT-02(TeamCreate/Delete) 不适用于个人使用场景，EXP-01(Fork Prompt Cache) 为 Anthropic 特有 API 不适用于千问/DeepSeek，EXT-02 精简移除 teammate/remote 类型，对标矩阵根据「个人使用+模型无关」约束重新校准  
> **文档目的**: 逐条提供精确到代码行级的修复方案，包含修改文件、修改内容、验证方法  
> **适用场景**: 个人使用（非企业团队），模型无关（千问/DeepSeek 优先，兼容 Anthropic）  
> **新增章节**: 第五章「功能扩展方案」+ 第六章「功能对标综合评估」+ 第八章「对标率差距分析与补充方案」— 基于 Claude Code 对标分析，补充 TaskCreateTool 类型、DreamTask、CascadeResult 增强等缺失功能 + LspService 实现、Plugin 市场、Session 快照、Worktree 工具 + Bridge 增强

---

## 一、P0 — 阻塞级修复（6项）

### P0-01 / P0-06: `McpToolAdapter.isMcp()` 未覆写

> **问题存在性**: ✅ 已确认。`McpToolAdapter.java` L1-147 全文无 `isMcp()` 方法覆写。  
> **必要性/ROI**: ★★★★★ 高。0.5h 修复，影响所有 MCP 工具的分区排序和工具列表稳定性（确保相同工具集总是产生相同顺序的 prompt 文本）。

**问题根因**: `McpToolAdapter` 实现了 `Tool` 接口，但未覆写 `isMcp()` 方法。`Tool.java` L198 定义了 `default boolean isMcp() { return false; }`，导致 `ToolRegistry.getEnabledToolsSorted()` (L144-157) 在分区排序时无法识别 MCP 工具，所有 MCP 工具被错误归入内建工具分区。

**影响链路**: `ToolRegistry.getEnabledToolsSorted()` → `if (t.isMcp())` → 分区排序失效 → 工具列表排序不稳定

**修改文件**: `backend/src/main/java/com/aicodeassistant/mcp/McpToolAdapter.java`

**修改内容**: 在 `shouldDefer()` 方法（L84-L87）之后添加 `isMcp()` 覆写：

```java
@Override
public boolean isMcp() {
    return true;
}
```

**验证方法**:
1. 单元测试: 创建 `McpToolAdapterTest`，断言 `new McpToolAdapter(...).isMcp() == true`
2. 集成验证: 调用 `toolRegistry.getEnabledToolsSorted()`，确认 MCP 工具全部排在列表尾部
3. 回归: 确认 `ToolRegistry.getEnabledToolsSorted()` 返回列表中，前 N 项 `isMcp()=false`，后 M 项 `isMcp()=true`

---

### P0-02: `TaskCreateTool.call()` 执行体为占位实现

> **问题存在性**: ✅ 已确认。L136-140 注释 `// P1 占位: 实际任务执行体将在集成阶段填充`，仅打印日志。  
> **必要性/ROI**: ★★★★★ 高。核心功能完全不可用，属阻塞级缺陷。

**问题根因**: `TaskCreateTool.java` L136-L140 的任务执行体为空 lambda，仅打印日志，7种 taskType（agent/shell/remote_agent/in_process_teammate/local_workflow/monitor_mcp/dream）均无实际执行逻辑。

**影响**: 后台任务创建功能完全不可用，影响子代理执行、shell 监控、工作流自动化。

**修改文件**: `backend/src/main/java/com/aicodeassistant/tool/task/TaskCreateTool.java`

**当前代码快照**（执行时必须核对）:

```java
// 当前构造函数（L34-38）— 仅注入 TaskCoordinator
private final TaskCoordinator taskCoordinator;

public TaskCreateTool(TaskCoordinator taskCoordinator) {
    this.taskCoordinator = taskCoordinator;
}

// 当前占位代码（L136-140）— 待替换
TaskState taskState = taskCoordinator.submit(taskId, sessionId, description, () -> {
    // P1 占位: 实际任务执行体将在集成阶段填充
    log.info("Task {} executing: type={}, prompt={}", taskId, taskType,
            prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);
});

// 当前 import（L1-10）— 需追加
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;
```

**修改方案 — 分阶段实现**:

**阶段一: `agent` 类型（最高优先）**

将 L136-L140 的占位 lambda 替换为按 taskType 分发的执行体：

```java
TaskState taskState = taskCoordinator.submit(taskId, sessionId, description, () -> {
    switch (taskType) {
        case "agent" -> executeAgentTask(taskId, prompt, context);
        case "shell" -> executeShellTask(taskId, prompt, context);
        default -> {
            log.warn("Task type '{}' not yet implemented, task {} skipped", taskType, taskId);
        }
    }
});
```

新增 `executeAgentTask` 私有方法：

> **源码核查**: `AgentRequest` 是定义在 `SubAgentExecutor.java` L403-418 的 record，**完整 8 字段**为 `(String agentId, String prompt, String agentType, String model, IsolationMode isolation, boolean runInBackground, String teamName, boolean fork)`。但 L409-411 提供了**向后兼容的 6 参构造函数** `(agentId, prompt, agentType, model, isolation, runInBackground)`，内部委托为 `this(..., null, false)`。下方代码使用 6 参兼容构造函数。

```java
private void executeAgentTask(String taskId, String prompt, ToolUseContext context) {
    log.info("Executing agent task: {}", taskId);
    // 6参兼容构造: agentId, prompt, agentType, model, isolation, runInBackground
    // agentType 传 null → resolveAgentDefinition 解析为 GENERAL_PURPOSE
    AgentRequest request = new AgentRequest(
            "task-agent-" + taskId,
            prompt,
            null,                      // agentType → 默认 GENERAL_PURPOSE
            null,                      // model → 默认由 agentDef 决定
            SubAgentExecutor.IsolationMode.NONE,  // 不隔离
            false                      // 同步执行
    );
    AgentResult result = subAgentExecutor.executeSync(request, context);
    log.info("Agent task {} completed: status={}", taskId, result.status());
}
```

新增 `executeShellTask` 私有方法：

> **安全性关键**: 禁止直接将 `prompt` 作为 shell 命令执行，否则将完全绕过 BashTool 的 8 层安全链（AST 解析、flag 验证、路径检查等），产生任意命令注入风险（如 `rm -rf /`、`sudo` 等）。必须复用现有 `BashTool` 的安全机制。

> **源码核查**: `ToolUseContext` 是 record 类型（L12），字段 `workingDirectory()` 返回 `String`（非 `Path`），不存在 `workingDir()` 方法。

```java
private void executeShellTask(String taskId, String prompt, ToolUseContext context) {
    log.info("Executing shell task via BashTool: {}", taskId);
    // ★ 安全设计：通过 BashTool 执行，复用完整的 8 层安全链 ★
    // 绝不直接调用 ProcessBuilder("sh", "-c", prompt)，
    // 那样会绕过 AST 解析、命令分类、注入检测等全部安全检查。
    Tool bashTool = toolRegistry.findByNameOptional("Bash").orElse(null);
    if (bashTool == null) {
        log.error("Shell task {} failed: BashTool not found in registry", taskId);
        return;
    }
    ToolInput shellInput = ToolInput.from(Map.of(
            "command", prompt,
            "timeout", 30000
    ));
    ToolResult result = bashTool.call(shellInput, context);
    log.info("Shell task {} completed via BashTool: success={}, outputLen={}",
            taskId, !result.isError(),
            result.content() != null ? result.content().length() : 0);
}
```

**需注入依赖**:
- `SubAgentExecutor subAgentExecutor`（通过构造函数注入）
- `ToolRegistry toolRegistry`（通过构造函数注入，用于通过 `findByNameOptional("Bash")` 获取 BashTool 实例）

修改构造函数：

```java
private final TaskCoordinator taskCoordinator;
private final SubAgentExecutor subAgentExecutor;
private final ToolRegistry toolRegistry;

public TaskCreateTool(TaskCoordinator taskCoordinator,
                      SubAgentExecutor subAgentExecutor,
                      @Lazy ToolRegistry toolRegistry) {
    this.taskCoordinator = taskCoordinator;
    this.subAgentExecutor = subAgentExecutor;
    this.toolRegistry = toolRegistry;
}
```

> 注意: `ToolRegistry` 使用 `@Lazy` 注入以避免循环依赖（TaskCreateTool 注册到 ToolRegistry，ToolRegistry 又被 TaskCreateTool 引用）。

**需新增 import**:
```java
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentRequest;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult;
import org.springframework.context.annotation.Lazy;
import java.util.Map;
```

**阶段二（后续迭代）**: `local_workflow`、`monitor_mcp`、`dream` 类型按需实现（见 EXT-02）。

> **个人使用场景说明**: `remote_agent` 和 `in_process_teammate` 类型属于团队多代理协作功能，个人使用场景不需要，不纳入实施计划。

**验证方法**:
1. 创建 agent 类型任务，验证子 QueryEngine 被正确创建并执行
2. 创建 shell 类型任务，验证命令经过 BashTool 8 层安全链检查后执行
3. 创建 shell 任务且 prompt 含危险命令（如 `rm -rf /`），验证被 BashTool 安全链拦截
4. 创建未实现类型任务，验证日志 warn 输出且不抛异常

---

### P0-03: BashTool 安全体系文档描述与实际代码不符

> **问题存在性**: ✅ 已确认。`PipelineSecurityChecker`/`CommandInjectionDetector`/`BashToolPermissionResolver` 在代码库中不存在。  
> **必要性/ROI**: ★★★☆☆ 中。文档修正，无代码变更，但可消除安全架构认知偏差。

**问题根因**: 文档对标报告中 BashTool 安全描述与实际代码不一致，但 Task 9 实测证明代码实现完整（8 层安全链 113 测试全通过）。

**修改文件**: 相关架构文档（非代码修改）

**修改内容**: 更新文档以反映实际的安全实现。

> **源码核查**: 经验证 `backend/src/main/java/com/aicodeassistant/tool/bash/` 目录下实际存在以下 13 个文件。原文档列举的 `PipelineSecurityChecker`、`CommandInjectionDetector`、`BashToolPermissionResolver` 三个类在代码库中 **不存在**（`search_file` 返回 0 结果），属于虚构。下方修正为实际存在的类清单。

实际安全层级（基于代码库真实文件，分布在 `tool/bash/` 及其子目录 `ast/`、`parser/`）：

**`tool/bash/` 根目录（6 文件）**:
1. `BashSecurityAnalyzer.java` (762行) — 安全分析核心，调用下层验证器
2. `BashCommandClassifier.java` (990行) — 三层验证（56 只读 + 9 正则 + 18 白名单 + Git 12 + GH 20 + Docker 2）
3. `SedValidator.java` (206行) — sed 命令安全验证（只读打印 + 安全替换两种模式）
4. `PathValidator.java` (344行) — 路径安全检查
5. `HeredocExtractor.java` (197行) — heredoc 安全提取
6. `ShellStateManager.java` (120行) — Shell 状态追踪

**`tool/bash/ast/` 子目录（3 文件）**:
7. `BashAstNode.java` (191行) — 16 种 AST 节点类型（+3 辅助 = 19 种 record）
8. `BashTokenType.java` (84行) — Token 类型枚举
9. `ParseForSecurityResult.java` (48行) — 解析结果 DTO

**`tool/bash/parser/` 子目录（4 文件）**:
10. `BashLexer.java` (825行) — 词法分析，20 种 Token 类型
11. `BashParserCore.java` (1117行) — AST 解析（5 层递归 / 50ms 超时 / 50000 节点上限）
12. `BashParser.java` (52行) — 解析器门面，封装 BashParserCore
13. `BashToken.java` (48行) — Token record

**验证方法**: 文档审查确认与 `BashParserGoldenTest`(50) + `BashSecurityAnalyzerTest`(63) 测试用例覆盖的安全层一致。

---

### P0-04: Agent 递归调用文档描述差异

> **问题存在性**: ✅ 已确认。文档与 `SubAgentExecutor.java` 实际并发控制参数存在差异。  
> **必要性/ROI**: ★★☆☆☆ 低。纯文档修正，不影响代码功能。

**修改文件**: 架构文档

**修改内容**: 对照 `SubAgentExecutor.java` 代码更新 Agent 递归调用描述：
- 并发控制: 全局 30 / 会话 10 / 嵌套 3
- `AgentSlot` 实现 `AutoCloseable`
- Fork 模式完整实现
- Coordinator 四阶段工作流

**验证方法**: 文档内容与 `SubAgentExecutor.java` 代码实现一一对应。

---

### P0-05: 权限传递规则文档描述不完整

> **问题存在性**: ✅ 已确认。权限管线 15 步检查点在文档中描述不完整。  
> **必要性/ROI**: ★★☆☆☆ 低。纯文档修正，不影响代码功能。

**修改文件**: 架构文档

**修改内容**: 补充权限传递完整规则：
- 15 步管线检查点
- Step 1f 优先级正确优先于 bypass 模式
- 7 种权限模式全覆盖
- SubAgent 权限继承规则
- 120s 审批超时机制

**验证方法**: 文档描述与权限管线代码 15 步完全匹配。

---

## 二、P1 — 高优先级修复（8项）

### P1-01: 两套 STOMP 客户端实现并行 + 缺心跳超时检测

> **问题存在性**: ✅ 已确认。`stompClient.ts` L98-99 心跳 10000ms，`useWebSocket.ts` L101-102 心跳 4000ms，两套独立 Client 实例。  
> **必要性/ROI**: ★★★★☆ 高。2-3d 工作量，但双客户端是架构隐患，可能导致重复订阅和状态不一致。

**问题根因**: 
- `frontend/src/api/stompClient.ts`: 独立 STOMP 客户端，指数退避 1s→10s，10min 超时
- `frontend/src/hooks/useWebSocket.ts`: React Hook 封装，递增退避 1s→30s
- 两套重连策略参数不一致，心跳超时无检测

**修改方案**:

**前置影响分析**（执行前必读）:

`useWebSocket.ts` 当前导出 3 个 API，被 5 个文件引用，统一后必须保留这些导出或迁移到 `stompClient.ts`：

| 导出 API | 引用文件 | 处理方式 |
|----------|---------|----------|
| `sendToServer(dest, body)` | `PromptInput.tsx` L18, `DialogManager.tsx` L15, `App.tsx` L9, `useWebSocket.test.ts` L2 | 迁移到 `stompClient.ts` 并保留 re-export |
| `isWsConnected()` | `App.tsx` L9, `useWebSocket.test.ts` L2 | 迁移到 `stompClient.ts` 并保留 re-export |
| `useWebSocket()` hook | `AppLayout.tsx` L14 | 改为薄封装，委托 `stompClient.ts` |

> **关键**: 统一后 `useWebSocket.ts` 必须保留上述 3 个导出的 re-export，否则 5 个消费者编译报错。统一完成后可逐步将消费者的 import 路径从 `useWebSocket` 迁移到 `stompClient`。

**步骤 1 — 统一为 `stompClient.ts` 作为唯一 STOMP 层**

修改 `frontend/src/hooks/useWebSocket.ts`，移除独立的 `Client` 创建逻辑，改为调用 `stompClient.ts` 提供的 `createStompClient()`：

```typescript
// useWebSocket.ts — 改造为薄封装
import { createStompClient, disconnectStomp, getStompClient } from '@/api/stompClient';

export function useWebSocket(options: UseWebSocketOptions = {}) {
    useEffect(() => {
        const sessionId = useSessionStore.getState().sessionId;
        const token = useSessionStore.getState().authToken;
        if (sessionId && token) {
            createStompClient(sessionId, token);
        }
        return () => { disconnectStomp(); };
    }, []);
}
```

**步骤 2 — 添加心跳超时检测**

在 `stompClient.ts` 中添加心跳超时检测器：

```typescript
let heartbeatTimer: ReturnType<typeof setTimeout> | null = null;
const HEARTBEAT_TIMEOUT_MS = 30_000; // 3 个心跳周期无消息视为超时

function resetHeartbeatTimer() {
    if (heartbeatTimer) clearTimeout(heartbeatTimer);
    heartbeatTimer = setTimeout(() => {
        console.warn('[WS] Heartbeat timeout, initiating reconnect');
        stompClient?.deactivate();
        // 触发 onWebSocketClose 中的重连逻辑
    }, HEARTBEAT_TIMEOUT_MS);
}
```

在 `onConnect` 回调中启动定时器，在每次收到消息时调用 `resetHeartbeatTimer()`。

**步骤 3 — 统一重连策略参数**

删除 `useWebSocket.ts` 中的 `RECONNECT_DELAYS` 和 `scheduleReconnect()`，统一使用 `stompClient.ts` 的指数退避策略（1s→2s→4s→8s→10s cap，10min 总超时）。

**验证方法**:
1. 断网测试: 断开网络后观察重连行为，确认使用统一的退避策略
2. 心跳超时: 模拟服务端停止心跳，验证 30s 后客户端触发重连
3. E2E: 运行 `playwright.config.ts` 中的 E2E 测试，验证 WebSocket 连接正常

---

### P1-02: SubAgent denied tools 列表不一致

> **问题存在性**: ✅ 已确认。`ToolRegistry.java` L160-163 含 5 个工具（含 `VerifyPlanExecution`），`SubAgentExecutor.java` L335 仅 4 个。  
> **必要性/ROI**: ★★★★★ 高。1h 修复，消除安全策略不一致风险，防止 SubAgent 调用不应访问的工具。

**问题根因**: 
- `ToolRegistry.java` L160-163: `SUB_AGENT_DENIED_TOOLS = Set.of("Agent", "TeamCreate", "TeamDelete", "TaskCreate", "VerifyPlanExecution")` — **5 个**
- `SubAgentExecutor.java` L335: `Set.of("Agent", "TeamCreate", "TeamDelete", "TaskCreate")` — **4 个，缺 `VerifyPlanExecution`**

> **个人使用场景说明**: `TeamCreate`/`TeamDelete` 工具在个人使用场景下不会被创建（见 FEAT-02 已删除），但 denied 列表中保留它们无害（仅作为安全网）。核心问题是两个列表必须保持一致。

**修改文件**: `backend/src/main/java/com/aicodeassistant/tool/agent/SubAgentExecutor.java`

**修改内容**: 将 L335 的 denied 集合对齐 `ToolRegistry`：

```java
Set<String> denied = Set.of("Agent", "TeamCreate", "TeamDelete", "TaskCreate", "VerifyPlanExecution");
```

或者更优方案 — 引用 `ToolRegistry` 的常量，消除重复定义：

```java
// SubAgentExecutor.java
Set<String> denied = toolRegistry.getSubAgentDeniedTools(); // 新增公开方法
```

同时在 `ToolRegistry.java` 中将 `SUB_AGENT_DENIED_TOOLS` 改为公开可访问：

```java
public Set<String> getSubAgentDeniedTools() {
    return SUB_AGENT_DENIED_TOOLS;
}
```

**验证方法**:
1. 创建 SubAgent，调用 `assembleToolPool()`，断言返回列表中不包含 `VerifyPlanExecution`
2. 确认 `ToolRegistry.getSubAgentTools()` 和 `SubAgentExecutor.assembleToolPool()` 的过滤结果一致

---

### P1-03: 前端无 120s 权限审批超时倒计时 UI

> **问题存在性**: ✅ 已确认。`PermissionDialog.tsx` L1-208 全文无 countdown/timer/timeout 相关逻辑。  
> **必要性/ROI**: ★★★☆☆ 中。0.5d 工作量，后端已有 120s 超时机制，前端增加倒计时属 UX 增强。

**问题根因**: `PermissionDialog.tsx` 中无任何超时相关状态和UI元素。

**修改文件**: `frontend/src/components/permission/PermissionDialog.tsx`

**修改内容**: 添加 120s 倒计时状态和显示：

```tsx
// 在组件内部添加
const APPROVAL_TIMEOUT_SECONDS = 120;
const [countdown, setCountdown] = useState(APPROVAL_TIMEOUT_SECONDS);

// ★ 关键：当 toolUseId 变化时重置倒计时（同一组件实例复用场景）
useEffect(() => {
    setCountdown(APPROVAL_TIMEOUT_SECONDS);
}, [request.toolUseId]);

useEffect(() => {
    const timer = setInterval(() => {
        setCountdown(prev => {
            if (prev <= 1) {
                clearInterval(timer);
                // 超时自动拒绝
                onDecision({ toolUseId: request.toolUseId, decision: 'deny', remember: false });
                return 0;
            }
            return prev - 1;
        });
    }, 1000);
    return () => clearInterval(timer);
}, [request.toolUseId, onDecision]);
```

在 Header 区域添加倒计时显示（L123-134 附近）：

```tsx
<span className={`text-xs ${countdown <= 30 ? 'text-red-400' : 'text-gray-500'}`}>
    {Math.floor(countdown / 60)}:{String(countdown % 60).padStart(2, '0')}
</span>
```

**验证方法**:
1. 触发权限审批对话框，观察倒计时从 2:00 开始递减
2. 等待超时或观察最后 30s 文字变红
3. 超时后确认自动执行 deny 决策

---

### P1-04: `SendMessageTool.call()` senderId 硬编码为 `"main"`

> **问题存在性**: ✅ 已确认。L121: `String senderId = "main"; // P1: 从 context 获取 agentId`，代码注释也标记了此为待修复项。  
> **必要性/ROI**: ★★☆☆☆ 低。0.5h 修复，但个人使用场景下主要使用单代理模式，senderId 始终为 `"main"` 实际无感。仅在有子代理场景时有影响，优先级可降低。

**问题根因**: `SendMessageTool.java` L121 硬编码 `String senderId = "main"`，多代理场景下消息来源标识错误。

**修改文件**: `backend/src/main/java/com/aicodeassistant/tool/config/SendMessageTool.java`

**修改内容**:

> **源码核查**: `ToolUseContext` 是 record 类型（`ToolUseContext.java` L12），包含 11 个字段：`workingDirectory`, `sessionId`, `toolUseId`, `onProgress`, `additionalDirs`, `userModified`, `nestingDepth`, `currentTaskId`, `parentSessionId`, `agentHierarchy`, `permissionNotifier`。**不存在 `agentId()` 方法**，直接调用 `context.agentId()` 将导致编译错误。

**方案 A（推荐）— 从 `sessionId` 解析 agentId**:

子代理的 sessionId 格式为 `"subagent-<agentId>"`（见 `SubAgentExecutor.java` L160），可从中提取：

```java
String senderId = extractAgentId(context.sessionId());

// ...

private static String extractAgentId(String sessionId) {
    if (sessionId != null && sessionId.startsWith("subagent-")) {
        return sessionId.substring("subagent-".length());
    }
    return "main";
}
```

**方案 B — 扩展 ToolUseContext record**:

在 `ToolUseContext.java` 中添加 `agentId` 字段，但这会影响所有 11 个 `with*()` 方法和 3 个兼容构造函数的签名，变更面过大，不推荐。

**验证方法**:
1. 在 SubAgent 上下文中调用 SendMessageTool，断言 senderId 为实际 agentId
2. 在主代理上下文中调用，断言 senderId 为 `"main"`
3. 验证 STOMP 通知消息中 `from` 字段正确

---

### P1-05: `SystemPromptSectionCache`(Caffeine) 与 `SystemPromptBuilder.sectionCache`(ConcurrentHashMap) 双缓存并行

> **问题存在性**: ✅ 已确认。`SystemPromptBuilder.java` 全文无 `SystemPromptSectionCache` 引用（grep 0 结果），Caffeine 缓存完全未被使用。  
> **必要性/ROI**: ★★★☆☆ 中。1d 工作量，当前 ConcurrentHashMap 在功能上可正常工作，但缺少 TTL 过期和按 session 隔离能力。

> **注意**: `SystemPromptSectionCache` 的实际包路径为 `com.aicodeassistant.context`（而非 `com.aicodeassistant.prompt`），修改时需确保 import 路径正确。

**问题根因**:
- `SystemPromptSectionCache.java`（Caffeine，TTL 30min，按 session 隔离）已定义但未被 `SystemPromptBuilder` 使用
- `SystemPromptBuilder.java` L66 自行维护 `ConcurrentHashMap<String, String> sectionCache`
- Caffeine 的 TTL、大小限制、统计等高级特性完全未生效

**修改文件**: `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptBuilder.java`

**当前代码快照**（执行时必须核对）:

```java
// 当前字段声明（L57-66）
private final ClaudeMdLoader claudeMdLoader;
private final FeatureFlagService featureFlags;
private final GitService gitService;
private final ConfigService configService;
private final AppStateStore appStateStore;
private final ProjectContextService projectContextService;
private final ToolResultSummarizer toolResultSummarizer;
private final Map<String, String> sectionCache = new ConcurrentHashMap<>();  // L66 — 待替换

// 当前构造函数（L84-98）— 7 参数
public SystemPromptBuilder(ClaudeMdLoader claudeMdLoader,
                           FeatureFlagService featureFlags,
                           GitService gitService,
                           ConfigService configService,
                           AppStateStore appStateStore,
                           ProjectContextService projectContextService,
                           ToolResultSummarizer toolResultSummarizer) {
    this.claudeMdLoader = claudeMdLoader;
    // ... 其余 6 个赋值
}

// volatile 状态字段（L81-82）— P3-09 修复目标
private volatile List<Message> currentMessages;
private volatile int currentContextLimit;
```

**修改方案**: 将 `SystemPromptBuilder` 的 `sectionCache` 替换为注入 `SystemPromptSectionCache`：

```java
// 移除 L66
// private final Map<String, String> sectionCache = new ConcurrentHashMap<>();

// 新增注入
private final SystemPromptSectionCache sectionCache;

// 构造函数添加参数
public SystemPromptBuilder(..., SystemPromptSectionCache sectionCache) {
    ...
    this.sectionCache = sectionCache;
}
```

修改所有 `sectionCache.get(key)` / `sectionCache.put(key, value)` 调用为 Caffeine 模式：

```java
// 全局段示例（不随 session 变化）:
String content = sectionCache.getOrComputeGlobal(
        sectionName,           // String: 段名称
        computedContent.hashCode(),  // int: 内容哈希（用于版本变化检测）
        () -> computeSection() // Supplier<String>: 实际计算逻辑
);

// 会话级段示例（按 session 隔离）:
String content = sectionCache.getOrComputeSession(
        sessionId,             // String: 会话 ID
        sectionName,           // String: 段名称
        computedContent.hashCode(),  // int: 内容哈希
        () -> computeSection() // Supplier<String>: 实际计算逻辑
);
```

修改 `clearSectionCache()` 为委托调用 `sectionCache.clearAll()` 或 `sectionCache.clearSession(sessionId)`。

**验证方法**:
1. 启动后确认 `SystemPromptSectionCache.getStats()` 显示命中/未命中计数正常递增
2. 确认缓存 TTL 生效: 30min 后段内容自动过期重新计算
3. 确认 `/clear` 命令正确清除对应 session 缓存

---

### P1-06: SSE heartbeat 日志级别过高

> **问题存在性**: ✅ 已确认。L279: `log.debug("Received response for unknown request id: {} (may be server keepalive)", id);`  
> **必要性/ROI**: ★★★★☆ 高。0.5h 修复，DEBUG 开启时显著减少日志噪音。

**问题根因**: `McpSseTransport.java` L279 使用 `log.debug` 记录服务器 keepalive 响应，在 DEBUG 日志级别开启时产生大量日志。`SseHealthChecker.java` 每 30s 执行一次健康检查也会产生高频日志。

**修改文件**: 
- `backend/src/main/java/com/aicodeassistant/mcp/McpSseTransport.java`

**修改内容**: 将 L279 的日志级别从 `debug` 降为 `trace`：

```java
log.trace("Received response for unknown request id: {} (may be server keepalive)", id);
```

> **L320 保持 `debug` 不变**: `"McpSseTransport closed: ..."` 是连接关闭的一次性生命周期事件（非高频心跳），对调试连接问题至关重要。降为 `trace` 会在常规 DEBUG 模式下丢失此诊断信息。仅 L279（keepalive）适合降为 trace。

**验证方法**:
1. 设置日志级别为 DEBUG，确认不再输出 keepalive 相关日志
2. 设置日志级别为 TRACE，确认日志正常输出

---

### P1-07: `ContextCollapseService` 级联集成验证与优化

> **源码核查纠正**: 原文档声称 `ContextCollapseService` “仅在 413 错误路径使用”，经验证为**事实错误**。
>
> 实际情况：`ContextCascade.java`（5 层压缩级联统一协调器）L217-224 已将 `ContextCollapseService` 集成为 **Level 1.5**，在正常前置级联路径（`executePreApiCascade()` L181）中，位于 MicroCompact (Level 1) 之后、AutoCompact (Level 2) 之前执行：
>
> ```java
> // ContextCascade.java L217-224
> // ===== Level 1.5: ContextCollapse (骨架化旧消息) =====
> ContextCollapseService.CollapseResult collapseResult =
>         contextCollapseService.collapseMessages(current);
> if (collapseResult.collapsedCount() > 0) {
>     current = collapseResult.messages();
>     log.debug("Level 1.5 ContextCollapse: collapsed {} messages, ~{} chars freed",
>             collapseResult.collapsedCount(), collapseResult.estimatedCharsFreed());
> }
> ```

> **问题存在性**: ✅ 已确认且比原描述更严重。`QueryEngine.java` L199-217 直接调用 `snipService`→`microCompactService`→`tryAutoCompact()`，**完全绕过 `ContextCascade`**，导致 Level 1.5 ContextCollapse 在实际查询循环中从未执行。  
> **必要性/ROI**: ★★★★★ 高。1d 工作量，ContextCollapseService 已实现但在实际查询路径中完全未生效，是实质性 bug。

**实际问题重新定义**: `ContextCollapseService` 已正确接入 `ContextCascade` 的 5 层级联（Level 0 Snip → Level 1 MicroCompact → Level 1.5 ContextCollapse → Level 2 AutoCompact → Level 3-4 错误恢复）。**但 `QueryEngine` 完全绕过 `ContextCascade`，直接调用各压缩服务**：

```java
// QueryEngine.java L199-217 — 当前实现（缺少 ContextCollapse）
// Layer 1: ToolResultBudget (单条工具结果大小裁剪)
state.setMessages(snipService.snipToolResults(state.getMessages(), toolResultBudget));
// Layer 2: MicroCompact (清除旧工具结果内容)
var mcResult = microCompactService.compactMessages(state.getMessages(), MICRO_COMPACT_PROTECTED_TAIL);
// Layer 3: AutoCompact (LLM 摘要)
tryAutoCompact(config, state, handler);
// ✘ 缺少 Level 1.5 ContextCollapse！
```

**修改文件**: `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java`

**当前代码快照**（执行时必须核对）:

```java
// QueryEngine 当前字段注入（L45-61）— 17 个依赖，无 ContextCascade
private final LlmProviderRegistry providerRegistry;
private final CompactService compactService;
private final ApiRetryService apiRetryService;
private final PermissionPipeline permissionPipeline;
private final PermissionRuleRepository permissionRuleRepository;
private final TokenCounter tokenCounter;
private final ObjectMapper objectMapper;
private final StreamingToolExecutor streamingToolExecutor;
private final MessageNormalizer messageNormalizer;
private final HookService hookService;
private final SnipService snipService;
private final MicroCompactService microCompactService;
private final ModelRegistry modelRegistry;
private final ThinkingBudgetCalculator thinkingBudgetCalculator;
private final ModelTierService modelTierService;
private final FileHistoryService fileHistoryService;
private final ToolResultSummarizer toolResultSummarizer;

// QueryEngine 当前构造函数（L74-108）— 17 参数
public QueryEngine(LlmProviderRegistry providerRegistry,
                   CompactService compactService,
                   ApiRetryService apiRetryService,
                   PermissionPipeline permissionPipeline,
                   PermissionRuleRepository permissionRuleRepository,
                   TokenCounter tokenCounter,
                   ObjectMapper objectMapper,
                   StreamingToolExecutor streamingToolExecutor,
                   MessageNormalizer messageNormalizer,
                   HookService hookService,
                   SnipService snipService,
                   MicroCompactService microCompactService,
                   ModelRegistry modelRegistry,
                   ThinkingBudgetCalculator thinkingBudgetCalculator,
                   ModelTierService modelTierService,
                   FileHistoryService fileHistoryService,
                   ToolResultSummarizer toolResultSummarizer) { ... }

// 待替换的压缩级联代码块（L199-217 完整原文）:
// ===== Step 1: 压缩级联 =====
// Layer 1: ToolResultBudget (单条工具结果大小裁剪)
int contextWindow = config.contextWindow() > 0
        ? config.contextWindow()
        : modelRegistry.getContextWindowForModel(currentModel[0]);
int toolResultBudget = (int)(contextWindow * TOOL_RESULT_BUDGET_RATIO * 3.5);
state.setMessages(snipService.snipToolResults(state.getMessages(), toolResultBudget));

// Layer 2: MicroCompact (清除旧工具结果内容)
var mcResult = microCompactService.compactMessages(
        state.getMessages(), MICRO_COMPACT_PROTECTED_TAIL);
if (mcResult.tokensFreed() > 0) {
    state.setMessages(mcResult.messages());
}

// Layer 3: AutoCompact (LLM 摘要)
if (state.isAutoCompactEnabled() && !state.isAutoCompactCircuitBroken()) {
    tryAutoCompact(config, state, handler);
}
```

**修改方案**: 将 `QueryEngine` 的 queryLoop 中的分散压缩调用替换为统一走 `ContextCascade.executePreApiCascade()`。当前 `QueryEngine` 直接注入并调用 `SnipService`、`MicroCompactService`、`CompactService`（L46/55-56），缺少 `ContextCascade` 和 `ContextCollapseService`，必须改为统一走 `contextCascade.executePreApiCascade(messages, model, trackingState)`。

具体改造步骤：

1. 在 `QueryEngine` 中新增注入 `ContextCascade`：
```java
private final ContextCascade contextCascade;
```

2. 在 `QueryLoopState` 中新增辅助方法，将分散的自动压缩状态映射为 `ContextCascade.AutoCompactTrackingState`：
```java
// QueryLoopState.java — 新增方法
public ContextCascade.AutoCompactTrackingState toAutoCompactTrackingState() {
    return new ContextCascade.AutoCompactTrackingState(
            false,                // compactedThisTurn: 每轮开始重置
            turnCount,            // turnCounter: 当前轮次
            null,                 // lastTurnId: QueryLoopState 无对应字段，传 null
            autoCompactFailures   // consecutiveFailures: 连续失败次数
    );
}
```

> **源码核查**: `AutoCompactTrackingState` 是 `ContextCascade.java` L119-124 定义的 record，**完整 4 字段**为 `(boolean compactedThisTurn, int turnCounter, String lastTurnId, int consecutiveFailures)`。`QueryLoopState` 无 `getTrackingState()` 方法（L19-166 全字段不含 `AutoCompactTrackingState`）。自动压缩状态分散在 `autoCompactFailures`(L24)、`turnCount`(L28)、`isAutoCompactCircuitBroken()`(L143) 等字段中。

3. 将 L199-217 的分散调用替换为：
```java
// ===== Step 1: 压缩级联（统一入口） =====
// ★ 保留 isAutoCompactEnabled() 检查：当禁用时，传入 MAX_VALUE 使 circuit breaker 触发跳过 ★
ContextCascade.AutoCompactTrackingState trackingState = state.isAutoCompactEnabled()
        ? state.toAutoCompactTrackingState()
        : new ContextCascade.AutoCompactTrackingState(false, state.getTurnCount(), null, Integer.MAX_VALUE);
ContextCascade.CascadeResult cascadeResult = contextCascade.executePreApiCascade(
        state.getMessages(), currentModel[0], trackingState);
state.setMessages(cascadeResult.messages());

// ===== Step 1b: AutoCompact 状态回写（关键：保持 circuit breaker 正确递进） =====
// CascadeResult 不含 updatedTrackingState，需根据 autoCompactExecuted 手动同步
if (cascadeResult.autoCompactExecuted()) {
    // auto-compact 成功执行 → 重置失败计数
    state.resetAutoCompactFailures();
} else if (state.isAutoCompactEnabled() && !state.isAutoCompactCircuitBroken()) {
    // auto-compact 已启用且未断路，但 cascade 未执行成功 → 可能是异常导致
    // 仅当 autoCompactResult 为 null（表示异常而非阈值未达到）时递增失败计数
    if (cascadeResult.autoCompactResult() == null) {
        // 注意：这里无法精确区分"阈值未达到"和"异常失败"，
        // 因为 CascadeResult 不包含 threshold 判断结果。
        // 保守策略：不递增。若需精确控制，应在 CascadeResult 中增加
        // boolean autoCompactThresholdCrossed 字段。
    }
}
```

> **设计缺陷说明**: `CascadeResult` 当前不含 `updatedTrackingState` 字段，也不含 `autoCompactThresholdCrossed` 标志，导致调用方无法精确区分"阈值未达到（无需操作）"和"达到阈值但执行失败（应递增 failures）"两种情况。建议后续在 `CascadeResult` 中增加 `boolean autoCompactAttempted` 字段以完善状态反馈。

3. 可选移除 `QueryEngine` 中不再需要的 `snipService`、`microCompactService` 直接注入（已被 `ContextCascade` 内部统一管理）。

**验证方法**:
1. 确认 `QueryEngine` 中注入了 `ContextCascade` 并在 queryLoop 中使用
2. 构造长对话场景，在日志中观察到 `Level 1.5 ContextCollapse` 的执行记录
3. 确认 5 层级联按序执行：Snip → MicroCompact → ContextCollapse → AutoCompact

---

### P1-08: 前端 WebSocket 消息解析容错增强

> **问题存在性**: ⚠️ 严重性降级。`useWebSocket.ts` L118-124 已有 try-catch 容错（`console.error` 记录异常），**并非裸 JSON.parse 无任何 fallback**。但仍存在两个小问题：(1) 缺少 `payload.type` 有效性校验，非 ServerMessage 对象可能误入 dispatch；(2) catch 中使用 `console.error` 而非 `console.debug`，正常心跳帧会产生红色报错。建议与 P1-01 合并处理。  
> **必要性/ROI**: ★☆☆☆☆ 低。若 P1-01 统一双客户端后，此处代码将被废弃，此问题自动解决。

**问题根因**: 
- `stompClient.ts` L22-60: `parseMessage()` 已有完善的多层降级解析（空值防御→心跳跳过→JSON直解→STOMP帧提取→尾部JSON提取）
- `useWebSocket.ts` L118-124: 已有 try-catch 容错，但缺少 `payload.type` 有效性校验，且 catch 中使用 `console.error`（应降为 `console.debug`）

**修改文件**: `frontend/src/api/stompClient.ts`

**修改方案**: 

**首要修复——`useWebSocket.ts` L118-124 增强 payload 有效性校验 + 日志级别降级**：

将 `useWebSocket.ts` L118-124 的现有 try-catch 增强（添加 `payload.type` 校验，日志从 `error` 降为 `debug`），或待 P1-01 统一后自动废弃：

```typescript
// useWebSocket.ts L118-126 — 增强 payload 校验 + 日志降级
globalSubscription = client.subscribe(
    '/user/queue/messages',
    (message: IMessage) => {
        try {
            const payload = JSON.parse(message.body);
            if (payload && typeof payload === 'object' && payload.type) {
                dispatch(payload);
            }
        } catch {
            console.debug('[WebSocket] Non-JSON message ignored:', message.body?.substring(0, 80));
        }
    }
);
```

**可选增强——`stompClient.ts` 新增 SockJS 数组帧支持**：

在 `parseMessage()` 的降级 2 之后、尾部 JSON 提取之前，添加 SockJS `a["..."]` 格式解析：

```typescript
// 降级3: SockJS 数组帧 a["..."]
if (raw.startsWith('a[')) {
    try {
        const arr = JSON.parse(raw.substring(1));
        if (Array.isArray(arr) && arr.length > 0) {
            return parseMessage(arr[0]); // 递归解析内层
        }
    } catch { /* fallthrough */ }
}
```

**关键改进**: 
- 新增 `parsed.type` 有效性校验，防止非 ServerMessage 对象误入 dispatch
- 将 catch 中 `console.error` 降为 `console.debug`，避免正常心跳帧产生红色报错
- 可选增加 SockJS `a["..."]` 框架帧解析支持

**验证方法**:
1. E2E 测试: 运行 `npx playwright test`，确认 AI 回复正确渲染
2. 手动测试: 发送消息，观察控制台无 "Failed to parse message" 报错
3. 边界测试: 模拟心跳帧 `\n`、SockJS 帧 `a["..."]` 等，确认正确处理

---

## 三、P2 — 中等优先级修复（7项）

### P2-01: 重连策略在两套 STOMP 客户端间不统一

> **问题存在性**: ✅ 已确认。与 P1-01 相同。  
> **必要性/ROI**: 合并入 P1-01。

**状态**: 合并入 P1-01 统一解决。统一后仅保留 `stompClient.ts` 的指数退避策略。

---

### P2-02: 日志未显示 Virtual Thread 名称

> **问题存在性**: ✅ 已确认。`SubAgentExecutor.java` L247 使用 `Thread.startVirtualThread(() -> {...})`（无名虚拟线程），全代码库共 9 处虚拟线程使用（`SubAgentExecutor` L247、`QueryController` L213、`WebSocketController` L397、`StreamingToolExecutor` L115、`McpAuthTool` L249、`McpCapabilityRegistryService` L146、`SettingsWatcher` L45（已命名）、`PythonProcessManager` L104、`InProcessBackend` L57），其中 `SettingsWatcher` 已使用 `.name("settings-watcher")` 命名，其余 8 处均为无名线程。  
> **必要性/ROI**: ★★★☆☆ 中。1-2h 修复全部 8 处，显著改善并发场景下日志可读性。

**修改文件**: 
- `backend/src/main/java/com/aicodeassistant/tool/agent/SubAgentExecutor.java`（及其余 7 个文件，按同一模式修改）

**修改内容**: 将 L247 的 `Thread.startVirtualThread(...)` 替换为带命名的 Virtual Thread（以 SubAgentExecutor 为例，其余文件按同一模式修改）：

```java
Thread.ofVirtual()
        .name("agent-" + request.agentId())
        .start(() -> {
            // ... 原有逻辑
        });
```

同时确认 `logback-spring.xml` 的日志 pattern 包含线程名 `%thread`。

**验证方法**: 启动异步 SubAgent，观察日志中线程名显示为 `agent-<agentId>`。

---

### P2-03: 主路径使用无序 `getEnabledTools()`

> **问题存在性**: ✅ 已确认。L76-79 直接返回 `.toList()`，无排序。  
> **必要性/ROI**: ★★☆☆☆ 低。0.5h 修复，但实际影响微乎其微——工具列表顺序对 prompt 内容和工具执行无功能性影响，仅影响工具列表稳定性。

**问题根因**: `ToolRegistry.getEnabledTools()` (L76-79) 返回 `toolsByName.values().stream().filter().toList()`，`toolsByName` 为 `ConcurrentHashMap`，迭代顺序不稳定。

**修改文件**: `backend/src/main/java/com/aicodeassistant/tool/ToolRegistry.java`

**修改内容**: 在 `getEnabledTools()` 返回前按名称排序：

```java
public List<Tool> getEnabledTools() {
    return toolsByName.values().stream()
            .filter(Tool::isEnabled)
            .sorted(Comparator.comparing(Tool::getName))
            .toList();
}
```

**影响评估**: `getEnabledToolsSorted()` 内部调用 `getEnabledTools()`，排序后 MCP/内建分区逻辑不受影响（稳定排序保证分区内有序）。

**验证方法**: 多次调用 `getEnabledTools()`，断言返回列表顺序一致。

---

### P2-04: `riskLevel` 判定缺少 `low` 级别

> **问题存在性**: ✅ 已确认。L407-409 仅返回 `"high"` 或 `"medium"`，缺 `"low"`。前端 `RISK_CONFIG` 已支持三级。  
> **必要性/ROI**: ★★★☆☆ 中。1h 修复，改善只读命令的用户体验（如 `cat`/`ls` 不再显示橙色 Medium 警告）。

**现状分析**: 前端 `PermissionDialog.tsx` L24-49 的 `RISK_CONFIG` 已包含 `low`/`medium`/`high` 三级。需确认后端权限系统是否正确返回 `low` 级别。

> **源码核查**: `PermissionRequirement` 枚举（`PermissionRequirement.java`）仅有 3 个值：`NONE`、`ALWAYS_ASK`、`CONDITIONAL`。**不存在 `READ_ONLY` 值**，原文档代码 `PermissionRequirement.READ_ONLY` 将导致编译错误。同时，代码库中**不存在 `RiskLevel` 枚举类型**（grep 0 结果），`BashToolPermissionResolver` 类也不存在。

**修改文件**: `backend/src/main/java/com/aicodeassistant/permission/PermissionPipeline.java`（或权限请求构造处）

**当前代码快照**（执行时必须核对）:

```java
// 当前构造函数（L97-109）— 6 参数，无 BashCommandClassifier
public PermissionPipeline(PermissionRuleMatcher ruleMatcher,
                          PermissionRuleRepository ruleRepository,
                          AutoModeClassifier autoModeClassifier,
                          HookService hookService,
                          SandboxManager sandboxManager,
                          PathSecurityService pathSecurityService) {
    this.ruleMatcher = ruleMatcher;
    // ... 其余 5 个赋值
}
```

**修改方案**:

> **源码核查**: `PermissionPipeline.java` L407-409 的当前实现为：
> ```java
> String riskLevel = BARE_SHELL_PREFIXES.stream()
>         .anyMatch(p -> String.valueOf(input.getOrDefault("command", "")).startsWith(p))
>         ? "high" : "medium";
> ```
> 即只有 `"high"` 和 `"medium"` 两级，缺少前端已支持的 `"low"` 级别。需在现有 BARE_SHELL_PREFIXES 检查之外，增加只读命令的 `"low"` 判定。

修改 `PermissionPipeline.java` L407-409，在 `requestPermission()` 方法中扩展 riskLevel 三级判定：

```java
// 替换 L407-409 的二级判定为三级判定
String commandStr = String.valueOf(input.getOrDefault("command", ""));
String riskLevel;
if (BARE_SHELL_PREFIXES.stream().anyMatch(commandStr::startsWith)) {
    riskLevel = "high";    // 裸 shell 前缀 → 高风险
} else if ("Bash".equals(toolName)) {
    // 通过 BashCommandClassifier.isReadOnlyCommand() 判定
    // ★ 使用 isReadOnlyCommand(L662) 而非 classify().isReadOnly()，
    //   前者增加了 $变量展开检测、花括号展开检测、管道递归安全检查 ★
    riskLevel = bashCommandClassifier.isReadOnlyCommand(commandStr) ? "low" : "medium";
} else {
    riskLevel = "medium";  // 非 Bash 工具默认 medium
}
```

**需注入依赖**: `BashCommandClassifier bashCommandClassifier`（通过构造函数注入）。

**验证方法**:
1. 触发 Bash 只读命令（如 `cat file.txt`）的权限审批，确认对话框显示蓝色 "Low Risk" 标识
2. 触发写入命令（如 `echo x > file`）的权限审批，确认显示橙色 "Medium Risk"
3. 触发裸 shell 前缀命令，确认显示红色 "High Risk"

---


### P2-06: `BashCommandClassifier` 和 `SedValidator` 缺独立单元测试

> **问题存在性**: ✅ 已确认。当前测试仅通过 `BashSecurityAnalyzerTest` 和 `BashParserGoldenTest` 间接覆盖。  
> **必要性/ROI**: ★★★★☆ 高。1-2d 工作量，显著提升安全核心组件的测试覆盖率。

**修改文件**: 
- 新建 `backend/src/test/java/com/aicodeassistant/tool/bash/BashCommandClassifierTest.java`
- 新建 `backend/src/test/java/com/aicodeassistant/tool/bash/SedValidatorTest.java`

**测试覆盖要点**:

**BashCommandClassifierTest**:
- 层 1 只读命令: `cat`, `head`, `tail`, `wc`, `stat` → 判定为只读
- 层 2 正则匹配: `grep -r pattern .`, `find . -name "*.java"` → 判定为只读
- 层 3 白名单验证: `git status`, `docker ps` → 判定为只读
- 非只读命令: `rm -rf /`, `chmod 777`, `curl http://...` → 判定为非只读/UNKNOWN
- 边界: 空命令、超长命令、注入尝试

**SedValidatorTest**:
- Pattern 1 只读打印: `sed -n '1p'`, `sed -n '1,5p'` → 判定为只读
- Pattern 2 安全替换: `sed 's/old/new/g'` → 判定为安全替换
- 危险操作: `sed -i 's/old/new/g' file` (in-place) → 判定为非只读
- 非法 flags: `sed -e 'w /etc/passwd'` → 拒绝

**验证方法**: `mvn test -pl backend -Dtest=BashCommandClassifierTest,SedValidatorTest`

---




### P2-10: OAuth 令牌明文存储

> **问题存在性**: ✅ 已确认。L333-342 直接 `Files.writeString(tokenFile, objectMapper.writeValueAsString(tokenData))`，明文 JSON。  
> **必要性/ROI**: ★★★☆☆ 中。1-2d 工作量。令牌文件存储在本地用户目录，需物理访问机器才能读取。加密提升安全性但非紧迫。

**问题根因**: `McpAuthTool.java` L328-343 将 OAuth token（access_token, refresh_token）以明文 JSON 写入 `~/.claude/mcp-tokens/<hash>.json`。

**修改文件**: `backend/src/main/java/com/aicodeassistant/mcp/McpAuthTool.java`

**修改方案**: 使用 AES-256-GCM 加密存储：

```java
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

private static final int GCM_IV_LENGTH = 12;
private static final int GCM_TAG_LENGTH = 128;

private void storeTokens(OAuthTokens tokens) throws IOException {
    Files.createDirectories(TOKEN_DIR);
    String hash = serverName.hashCode() + "";
    Path tokenFile = TOKEN_DIR.resolve(hash + ".enc"); // .enc 标识加密文件

    Map<String, Object> tokenData = Map.of(
            "server_name", serverName,
            "server_url", serverUrl,
            "access_token", tokens.accessToken(),
            "refresh_token", tokens.refreshToken() != null ? tokens.refreshToken() : "",
            "expires_at", tokens.createdAt().plusSeconds(tokens.expiresIn()).toString(),
            "created_at", tokens.createdAt().toString()
    );

    String plaintext = objectMapper.writeValueAsString(tokenData);
    String encrypted = encryptWithMachineKey(plaintext);
    Files.writeString(tokenFile, encrypted);
    log.info("OAuth tokens stored (encrypted) for server: {}", serverName);
}

private String encryptWithMachineKey(String plaintext) {
    try {
        // 1. 派生密钥：hostname + user.name 的 SHA-256 作为 AES-256 密钥
        String seed = java.net.InetAddress.getLocalHost().getHostName()
                + ":" + System.getProperty("user.name");
        byte[] keyBytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SecretKey key = new SecretKeySpec(keyBytes, "AES");

        // 2. 随机 IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        // 3. AES-256-GCM 加密
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] cipherText = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 4. IV + 密文 → Base64
        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
        return java.util.Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
        throw new RuntimeException("Failed to encrypt token data", e);
    }
}

private String decryptWithMachineKey(String encrypted) {
    try {
        byte[] combined = java.util.Base64.getDecoder().decode(encrypted);
        byte[] iv = java.util.Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        byte[] cipherText = java.util.Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

        String seed = java.net.InetAddress.getLocalHost().getHostName()
                + ":" + System.getProperty("user.name");
        byte[] keyBytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SecretKey key = new SecretKeySpec(keyBytes, "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(cipherText), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
        throw new RuntimeException("Failed to decrypt token data", e);
    }
}
```

**验证方法**:
1. 存储 token 后检查文件内容为加密密文（非可读 JSON）
2. 重新读取 token 并解密，确认内容正确还原
3. 在另一台机器上尝试读取，确认因密钥不同而解密失败

---

### P2-11: 上下文管理 4 个中等问题（含 P2-12/13/14）

> **问题存在性**: ✅ 已确认。L32-38 确实为 `static final` 硬编码常量。  
> **必要性/ROI**: ★★☆☆☆ 低。当前硬编码值工作正常，可配置化属运维便利性优化。指标监控（Micrometer）则有一定价值。

**子问题 1 — 压缩阈值硬编码**

**修改文件**: `backend/src/main/java/com/aicodeassistant/engine/ContextCollapseService.java`

> **源码核查**: 当前 L32-38 使用 `private static final` 常量。`@Value` 注解不能用于 `static final` 字段，需改为实例字段。同时 `@Value` 注入发生在构造后，因此方法中引用这些字段时必须使用实例字段而非静态常量。

**修改内容**: 移除 `static final` 常量，改为 `@Value` 注入的实例字段：

```java
// 移除原有 static final 常量（L32-38）
// private static final int DEFAULT_PROTECTED_TAIL = 6;
// private static final int TEXT_TRUNCATE_THRESHOLD = 2000;
// private static final int TEXT_TRUNCATE_KEEP = 500;

// 替换为可配置实例字段
@Value("${zhiku.context.collapse.protected-tail:6}")
private int configuredProtectedTail;

@Value("${zhiku.context.collapse.text-truncate-threshold:2000}")
private int textTruncateThreshold;

@Value("${zhiku.context.collapse.text-truncate-keep:500}")
private int textTruncateKeep;
```

同步修改 `collapseMessages()` 方法中对 `DEFAULT_PROTECTED_TAIL` 的引用为 `configuredProtectedTail`，`hasLongText()` 和 `truncateBlocks()` 中对 `TEXT_TRUNCATE_THRESHOLD`/`TEXT_TRUNCATE_KEEP` 的引用为实例字段。

**配置文件**: 在 `application.yml` 中添加（可选）：

```yaml
zhiku:
  context:
    collapse:
      protected-tail: 6
      text-truncate-threshold: 2000
      text-truncate-keep: 500
```

**子问题 2 — 缺压缩效果指标**

**修改方案**: 在 `ContextCollapseService` 中添加 Micrometer 指标（使用构造函数注入，与项目其他 Service 保持一致）：

```java
private final MeterRegistry meterRegistry;

private final AtomicLong totalCollapsedMessages = new AtomicLong();
private final AtomicLong totalCharsFreed = new AtomicLong();

// 在构造函数中注入
public ContextCollapseService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
}

@PostConstruct
void initMetrics() {
    Gauge.builder("context.collapse.total_collapsed", totalCollapsedMessages, AtomicLong::get)
         .register(meterRegistry);
    Gauge.builder("context.collapse.total_chars_freed", totalCharsFreed, AtomicLong::get)
         .register(meterRegistry);
}
```

**验证方法**: 
1. 通过 `application.yml` 修改阈值，确认生效
2. 通过 `/actuator/metrics/context.collapse.total_collapsed` 查看压缩统计

---

## 四、P3 — 低优先级修复（4项，原9项保留4项）


### P3-02: `buildApiMessages()` 死代码

> **问题存在性**: ✅ 已确认。`buildApiMessages` 在整个后端代码库中仅在 L786 定义，无任何调用点（grep 仅 1 个结果即定义本身）。  
> **必要性/ROI**: ★★☆☆☆ 低。死代码清理属代码整洁度优化。

**修改文件**: `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java`

**修改内容**: 确认 `buildApiMessages()` (L786-830) 未被调用后，添加 `@Deprecated` 注解或移除。若需保留备用，添加注释说明用途。

---


### P3-06: `SendMessageTool` 分组为 `config` 不一致

> **问题存在性**: ✅ 已确认。L102-104: `return "config";`，与工具的消息发送语义不匹配。  
> **必要性/ROI**: ★☆☆☆☆ 低。分组仅影响工具分类展示，不影响功能。

**修改文件**: `backend/src/main/java/com/aicodeassistant/tool/config/SendMessageTool.java`

**修改内容**: 修改 `getGroup()` 返回值：

```java
@Override
public String getGroup() {
    return "agent"; // 从 "config" 改为 "agent"，与工具语义一致
}
```

---

### P3-07: `output_style` 段为空实现

> **问题存在性**: ✅ 已确认。L1038-1040: `return null;`。  
> **必要性/ROI**: ★★☆☆☆ 低。返回 null 在调用处已被安全处理（跳过 null 段），不影响功能。实现内容或移除占位均可。

**修改文件**: `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptBuilder.java`

**修改内容**: L1038-1040 的 `getOutputStyleSection()` 返回 `null`，有两种处理方式：

**方案 A（推荐）— 实现内容**:
```java
private String getOutputStyleSection() {
    return """
        # Output Style
        - Be concise and direct. Avoid unnecessary preamble or filler.
        - Use code blocks with language tags for all code snippets.
        - Structure long responses with headers and bullet points.
        """;
}
```

**方案 B — 移除占位**:
从动态段列表中移除 `output_style` 的 `MemoizedSection` 定义。

---


### P3-09: volatile 字段存在并发风险

> **问题存在性**: ✅ 已确认。L81-82 两个 volatile 字段，L107-109 `setContextState()` 非原子更新。  
> **必要性/ROI**: ★★☆☆☆ 低。实际发生不一致读取的概率极低（窗口极小），且后果仅是 context 数据略有偏差，不会导致功能失败。但作为并发安全最佳实践值得修复。

**修改文件**: `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptBuilder.java`

> **源码核查**: L81-82 有两个 `volatile` 字段 `currentMessages` 和 `currentContextLimit`，由 `setContextState()` (L107-109) 同时更新。问题在于两个字段的更新不是原子的：读取线程可能看到新的 `currentMessages` 但旧的 `currentContextLimit`。简单替换为两个独立的 `AtomicReference`/`AtomicInteger` 并不能解决这个复合原子性问题。

**修改内容**: 使用不可变记录 + 单一 `AtomicReference` 保证复合原子性：

```java
// 替换 L81-82 的两个 volatile 字段
private record ContextState(List<Message> messages, int contextLimit) {
    static final ContextState EMPTY = new ContextState(List.of(), 0);
}
private final AtomicReference<ContextState> currentContextState =
        new AtomicReference<>(ContextState.EMPTY);

// 替换 L107-109 的 setContextState 方法
public void setContextState(List<Message> messages, int contextLimit) {
    this.currentContextState.set(new ContextState(
            messages != null ? messages : List.of(), contextLimit));
}

// 读取处改为:
ContextState state = currentContextState.get();
List<Message> messages = state.messages();
int contextLimit = state.contextLimit();
```

---

## 五、功能扩展方案 — 对标 Claude Code 缺失功能补充（6项）

> **背景**: 基于 Claude Code 源码深度架构分析与 ZhikuCode 核心功能测试报告 v3 的交叉比对，识别出以下功能域在修复文档原有 30 项中未覆盖但对对标率提升至关重要的功能缺口。  
> **目标**: 将 6 大核心模块平均对标率从 80.3%（当前）→ 87%（修复项后）→ **90%+**（补充功能扩展后）。  
> **方法论**: 每项方案均经过源码核查确认当前实现状态，提供精确到代码行级的实现方案。  
> **个人使用筛选**: EXT-01(SwarmService) 已删除——Swarm 多代理协作属于企业团队功能，个人使用场景不需要。

---

### ~~EXT-01: SwarmService 核心方法实现~~ 【已删除 — 个人使用场景不需要】

> **删除理由**: SwarmService 是多 Agent 团队协作模块（Leader/Worker 角色分工、工作分配策略、交互式通信），属于企业团队功能。当前 `SwarmService.java` 62行全方法抛 `UnsupportedOperationException`，对个人使用无影响。  
> **影响**: 移除此项后对标率预期下降 ~1%，但该百分比反映的是企业团队场景，与个人使用无关。

---

### EXT-02: TaskCreateTool 精简任务类型实现（扩展 P0-02）

> **问题存在性**: ✅ 已确认。P0-02 仅实现 `agent` 和 `shell` 两种类型，剩余任务类型均无实现。  
> **必要性/ROI**: ★★★☆☆ 中。`local_workflow` 和 `monitor_mcp` 可立即实现，`dream` 依赖 EXT-03。  
> **个人使用精简**: 原 5 种类型精简为 3 种——`in_process_teammate` 和 `remote_agent` 属于团队多代理协作功能，个人使用不需要。

**当前代码现状**: `TaskCreateTool.java` L136-140 占位 lambda，`TaskType` 枚举已定义全部 7 种类型。

**修改文件**: `backend/src/main/java/com/aicodeassistant/tool/task/TaskCreateTool.java`

**修改方案 — 在 P0-02 基础上补充 3 种个人使用类型**:

> **源码核查**: `TeamManager.java` L63-82 `dispatchTasks()` 接受 `List<TaskSpec>`，返回 `List<AgentResult>`。`InProcessBackend.java` L46-107 `executeParallel()` 使用 Virtual Thread 并行执行。两者均已完整实现，可直接复用。

在 P0-02 的 `switch` 语句中追加（移除了团队类型 `in_process_teammate` 和 `remote_agent`）：

```java
TaskState taskState = taskCoordinator.submit(taskId, sessionId, description, () -> {
    switch (taskType) {
        case "agent" -> executeAgentTask(taskId, prompt, context);       // P0-02 已实现
        case "shell" -> executeShellTask(taskId, prompt, context);       // P0-02 已实现
        case "local_workflow" -> executeLocalWorkflow(taskId, prompt, context);      // 新增
        case "monitor_mcp" -> executeMonitorMcp(taskId, prompt, context);            // 新增
        case "dream" -> executeDreamTask(taskId, prompt, context);                   // 新增
        default -> log.warn("Task type '{}' not yet implemented, task {} skipped", taskType, taskId);
    }
});
```

> **个人使用说明**: `in_process_teammate` 和 `remote_agent` 属于团队协作功能，已从 switch 中移除。这两种类型在 `TaskType` 枚举中仍然存在，但会落入 `default` 分支并打印 warn 日志。

**新增 `executeLocalWorkflow` 私有方法**（可立即实现）:

```java
/**
 * 本地工作流 — 将 prompt 作为多步骤指令，通过 agent 顺序执行。
 * 与普通 agent 任务的区别: 工作流模式使用限制工具集（禁用 Agent/Team，防止递归派生）。
 */
private void executeLocalWorkflow(String taskId, String prompt, ToolUseContext context) {
    log.info("Executing local_workflow task: {}", taskId);
    AgentRequest request = new AgentRequest(
            "workflow-" + taskId,
            prompt,
            "general-purpose",
            null,
            SubAgentExecutor.IsolationMode.NONE,
            false
    );
    AgentResult result = subAgentExecutor.executeSync(request, context);
    log.info("Workflow task {} completed: status={}", taskId, result.status());
}
```

**新增 `executeMonitorMcp` 私有方法**（可立即实现）:

```java
/**
 * MCP 监控任务 — 周期性检查 MCP 服务器状态。
 * prompt 格式约定: "服务器名称" 或 "serverName:checkIntervalSeconds"。
 */
private void executeMonitorMcp(String taskId, String prompt, ToolUseContext context) {
    log.info("Executing monitor_mcp task: {}", taskId);
    // 解析 prompt 为服务器名称和检查间隔
    String[] parts = prompt.split(":", 2);
    String serverName = parts[0].trim();
    int intervalSeconds = 60;
    if (parts.length > 1) {
        try {
            intervalSeconds = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid interval '{}', using default 60s", parts[1].trim());
        }
    }

    // 周期性检查（在 Virtual Thread 中阻塞等待，直到任务被取消）
    while (!Thread.currentThread().isInterrupted()) {
        try {
            // 通过 BashTool 执行健康检查（复用安全链）
            Tool bashTool = toolRegistry.findByNameOptional("Bash").orElse(null);
            if (bashTool != null) {
                ToolInput checkInput = ToolInput.from(Map.of(
                        "command", "curl -sf http://localhost:8080/actuator/health/mcp-" + serverName,
                        "timeout", 10000));
                ToolResult result = bashTool.call(checkInput, context);
                log.debug("MCP monitor {} check: success={}", serverName, !result.isError());
            }
            Thread.sleep(intervalSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("MCP monitor task {} interrupted", taskId);
            break;
        }
    }
}
```

**新增 `executeDreamTask` 私有方法**（依赖 EXT-03）:

```java
/**
 * 梦境任务 — 后台自动记忆整理。依赖 DreamTaskExecutor（见 EXT-03）。
 */
private void executeDreamTask(String taskId, String prompt, ToolUseContext context) {
    log.info("Executing dream task: {}", taskId);
    if (dreamTaskExecutor == null) {
        log.warn("DreamTaskExecutor not available, dream task {} skipped", taskId);
        return;
    }
    dreamTaskExecutor.execute(taskId, context.sessionId(), context);
}
```

**需新增依赖注入**（在 P0-02 基础上追加）:

```java
private final DreamTaskExecutor dreamTaskExecutor; // EXT-03 实现后注入

public TaskCreateTool(TaskCoordinator taskCoordinator,
                      SubAgentExecutor subAgentExecutor,
                      @Lazy ToolRegistry toolRegistry,
                      @Autowired(required = false) DreamTaskExecutor dreamTaskExecutor) {
    this.taskCoordinator = taskCoordinator;
    this.subAgentExecutor = subAgentExecutor;
    this.toolRegistry = toolRegistry;
    this.dreamTaskExecutor = dreamTaskExecutor;
}
```

> **设计决策**: `DreamTaskExecutor` 使用 `@Autowired(required = false)` 注入，使其为可选依赖。在 EXT-03 实现前，`executeDreamTask()` 会打印 warn 日志并跳过。移除了原有的 `TeamManager` 注入（团队功能不需要）。

**验证方法**:
1. 创建 `local_workflow` 类型任务，确认通过 agent 顺序执行
2. 创建 `dream` 类型任务，确认当 DreamTaskExecutor 不可用时优雅降级
3. 创建 `monitor_mcp` 类型任务，确认周期性检查执行且可通过 `cancelTask()` 中断
4. 创建 `in_process_teammate` 类型任务，确认 warn 日志输出且不抛异常（团队类型已移除）

**预估工作量**: 1-2d（含测试，精简后工作量降低）

---

### EXT-03: DreamTask 自动记忆整理实现

> **问题存在性**: ✅ 已确认。数据模型已完整：`DreamPhase` 枚举（3阶段）、`DreamTaskState` record（5字段）、`TaskType.DREAM` 已定义。但无实际执行器。  
> **必要性/ROI**: ★★★☆☆ 中。Claude Code 的 DreamTask 在空闲时自动运行子 agent 回顾会话并整理记忆文件，是智能化体验的重要组成部分。

**已有数据模型**（源码核查确认）:

```java
// DreamPhase.java — 3 个阶段
public enum DreamPhase { SCANNING, REVIEWING, COMPLETE }

// DreamTaskState.java — 5 个字段
public record DreamTaskState(
    DreamPhase phase,
    int sessionsReviewing,
    List<String> filesTouched,
    int turns,
    Long priorMtime       // ★ 用于 kill() 时回滚锁的 mtime
) {}
```

**新建文件**: `backend/src/main/java/com/aicodeassistant/tool/task/DreamTaskExecutor.java`

**实现方案**:

```java
package com.aicodeassistant.tool.task;

import com.aicodeassistant.model.DreamPhase;
import com.aicodeassistant.model.DreamTaskState;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentRequest;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DreamTaskExecutor — 后台梦境式记忆整理执行器。
 * <p>
 * 对标 Claude Code 的 DreamTask（src/tasks/DreamTask.ts）：
 * 系统在空闲时自动运行子 agent，回顾最近会话并整理 CLAUDE.md 记忆文件。
 * <p>
 * 三阶段执行：
 * <ol>
 *   <li>SCANNING: 扫描最近会话，识别需要整理的记忆点</li>
 *   <li>REVIEWING: 通过只读 agent 回顾会话内容，提取关键信息</li>
 *   <li>COMPLETE: 更新 CLAUDE.md 记忆文件</li>
 * </ol>
 *
 * @see <a href="Claude Code src/tasks/DreamTask.ts">DreamTask 原版设计</a>
 */
@Component
public class DreamTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DreamTaskExecutor.class);

    /** Dream 锁文件路径 — 防止并发执行 */
    private static final Path DREAM_LOCK_FILE = Path.of(
            System.getProperty("user.home"), ".claude", "dream.lock");

    /** Dream 最小间隔（秒）— 距离上次 Dream 完成至少 1 小时 */
    private static final long DREAM_MIN_INTERVAL_SECONDS = 3600;

    /** Dream agent 最大轮次 */
    private static final int MAX_DREAM_TURNS = 10;

    private final SubAgentExecutor subAgentExecutor;

    public DreamTaskExecutor(SubAgentExecutor subAgentExecutor) {
        this.subAgentExecutor = subAgentExecutor;
    }

    /**
     * 执行 Dream 任务。
     *
     * @param taskId    任务 ID
     * @param sessionId 会话 ID
     * @param context   工具上下文
     */
    public void execute(String taskId, String sessionId, ToolUseContext context) {
        // 1. 锁检查 — 防止并发 Dream
        if (!acquireDreamLock()) {
            log.info("Dream task {} skipped: another dream is running or interval not met", taskId);
            return;
        }

        DreamTaskState state = new DreamTaskState(
                DreamPhase.SCANNING, 0, new ArrayList<>(), 0, readLockMtime());

        try {
            // 2. SCANNING 阶段 — 通过只读 agent 扫描最近会话
            log.info("Dream task {} phase: SCANNING", taskId);
            AgentRequest scanRequest = new AgentRequest(
                    "dream-scan-" + taskId,
                    DREAM_SCAN_PROMPT,
                    "Explore",            // 使用 Explore agent（只读，便宜模型）
                    null,
                    SubAgentExecutor.IsolationMode.NONE,
                    false
            );
            AgentResult scanResult = subAgentExecutor.executeSync(scanRequest, context);

            // 3. REVIEWING 阶段 — 提取关键记忆点
            state = new DreamTaskState(DreamPhase.REVIEWING, 1,
                    state.filesTouched(), state.turns() + 1, state.priorMtime());
            log.info("Dream task {} phase: REVIEWING", taskId);

            AgentRequest reviewRequest = new AgentRequest(
                    "dream-review-" + taskId,
                    DREAM_REVIEW_PROMPT + "\n\nScan results:\n" + scanResult.result(),
                    "general-purpose",
                    null,
                    SubAgentExecutor.IsolationMode.NONE,
                    false
            );
            AgentResult reviewResult = subAgentExecutor.executeSync(reviewRequest, context);

            // 4. COMPLETE 阶段 — 更新锁 mtime
            state = new DreamTaskState(DreamPhase.COMPLETE, 1,
                    state.filesTouched(), state.turns() + 1, state.priorMtime());
            updateDreamLockMtime();

            log.info("Dream task {} completed: {} turns, files touched: {}",
                    taskId, state.turns(), state.filesTouched());

        } catch (Exception e) {
            // ★ 关键: 失败时回滚锁的 mtime，让下次会话可以重试 ★
            if (state.priorMtime() != null) {
                rollbackLockMtime(state.priorMtime());
            }
            log.error("Dream task {} failed, lock mtime rolled back", taskId, e);
        }
    }

    // ===== 锁管理 =====

    private boolean acquireDreamLock() {
        try {
            Files.createDirectories(DREAM_LOCK_FILE.getParent());
            if (Files.exists(DREAM_LOCK_FILE)) {
                long lastModified = Files.getLastModifiedTime(DREAM_LOCK_FILE)
                        .toInstant().getEpochSecond();
                long now = Instant.now().getEpochSecond();
                if (now - lastModified < DREAM_MIN_INTERVAL_SECONDS) {
                    return false; // 间隔未达到
                }
            }
            Files.writeString(DREAM_LOCK_FILE, Instant.now().toString());
            return true;
        } catch (IOException e) {
            log.warn("Failed to acquire dream lock: {}", e.getMessage());
            return false;
        }
    }

    private Long readLockMtime() {
        try {
            if (Files.exists(DREAM_LOCK_FILE)) {
                return Files.getLastModifiedTime(DREAM_LOCK_FILE).toMillis();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void updateDreamLockMtime() {
        try {
            Files.setLastModifiedTime(DREAM_LOCK_FILE, FileTime.from(Instant.now()));
        } catch (IOException e) {
            log.warn("Failed to update dream lock mtime: {}", e.getMessage());
        }
    }

    /** ★ 关键设计: kill() 时回滚 mtime，让下次会话可重试 ★ */
    private void rollbackLockMtime(Long priorMtime) {
        try {
            Files.setLastModifiedTime(DREAM_LOCK_FILE,
                    FileTime.fromMillis(priorMtime));
            log.debug("Dream lock mtime rolled back to {}", priorMtime);
        } catch (IOException e) {
            log.warn("Failed to rollback dream lock mtime: {}", e.getMessage());
        }
    }

    // ===== Dream Agent Prompts =====

    private static final String DREAM_SCAN_PROMPT = """
            You are a memory organization agent. Your task is to scan recent session history
            and identify key learnings, patterns, and decisions that should be preserved
            in the project's CLAUDE.md memory file.
            
            Focus on:
            - User preferences and coding style decisions
            - Project-specific patterns and conventions discovered
            - Important architectural decisions made during the session
            - Recurring issues and their solutions
            - File paths and structures that are frequently referenced
            
            Output a structured list of memory candidates with importance ratings.
            """;

    private static final String DREAM_REVIEW_PROMPT = """
            You are a memory consolidation agent. Based on the scan results below,
            update the project's CLAUDE.md file to incorporate new learnings.
            
            Rules:
            - Do NOT duplicate existing entries
            - Merge related items into concise entries
            - Remove outdated or superseded entries
            - Keep the file organized by category
            - Maximum file size: 5000 characters
            """;
}
```

**验证方法**:
1. 手动触发 dream 任务，确认三阶段执行（SCANNING→REVIEWING→COMPLETE）
2. 在锁间隔内再次触发，确认被跳过
3. 模拟失败，确认锁 mtime 被回滚
4. 等待锁过期后再次触发，确认可正常执行

**预估工作量**: 3-5d（含 prompt 调优和测试）

---

### EXT-04: CascadeResult 增强 — 补充 autoCompactAttempted 字段

> **问题存在性**: ✅ 已确认。`ContextCascade.java` L89-113 的 `CascadeResult` record 缺少 `autoCompactAttempted` 字段，导致调用方（P1-07 修复后的 QueryEngine）无法区分“阈值未达到（无需操作）”和“达到阈值但执行失败（应递增 failures）”。  
> **必要性/ROI**: ★★★★☆ 高。P1-07 修复文档已明确指出此设计缺陷，修复后 circuit breaker 状态机才能精确工作。

**当前 CascadeResult 定义**（L89-113）:

```java
public record CascadeResult(
        List<Message> messages,
        int originalTokens,
        int finalTokens,
        boolean snipExecuted,
        int snipTokensFreed,
        boolean microCompactExecuted,
        int microCompactTokensFreed,
        boolean autoCompactExecuted,          // ★ 仅表示“成功执行”
        CompactService.CompactResult autoCompactResult
) { ... }
```

**修改文件**: `backend/src/main/java/com/aicodeassistant/engine/ContextCascade.java`

**修改内容 — 扩展 CascadeResult record**:

将 L89-113 替换为:

```java
/**
 * 级联执行结果 — 记录每层的执行情况。
 * ★ v2 增强: 新增 autoCompactAttempted + contextCollapseExecuted ★
 */
public record CascadeResult(
        List<Message> messages,
        int originalTokens,
        int finalTokens,
        boolean snipExecuted,
        int snipTokensFreed,
        boolean microCompactExecuted,
        int microCompactTokensFreed,
        boolean contextCollapseExecuted,      // 新增: Level 1.5 是否执行
        int contextCollapseCharsFreed,        // 新增: Level 1.5 释放字符数
        boolean autoCompactAttempted,          // 新增: ★ 是否达到阈值并尝试执行
        boolean autoCompactExecuted,
        CompactService.CompactResult autoCompactResult
) {
    public int totalTokensFreed() {
        return originalTokens - finalTokens;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder("ContextCascade: ");
        sb.append(originalTokens).append(" \u2192 ").append(finalTokens).append(" tokens");
        if (snipExecuted) sb.append(", Snip: -").append(snipTokensFreed);
        if (microCompactExecuted) sb.append(", MicroCompact: -").append(microCompactTokensFreed);
        if (contextCollapseExecuted) sb.append(", Collapse: -").append(contextCollapseCharsFreed).append("chars");
        if (autoCompactAttempted && !autoCompactExecuted) sb.append(", AutoCompact: ATTEMPTED_FAILED");
        if (autoCompactExecuted && autoCompactResult != null)
            sb.append(", AutoCompact: ").append(autoCompactResult.summary());
        return sb.toString();
    }
}
```

**同步修改 `executePreApiCascade()` 方法（L181-255）**:

在 Level 1.5 和 Level 2 之间追加状态追踪变量，并更新终态构造:

```java
// 在现有变量声明后追加：
boolean collapseExecuted = false;
int collapseCharsFreed = 0;
boolean acAttempted = false;    // ★ 新增: 是否尝试了 AutoCompact

// Level 1.5 修改：追踪 collapse 状态
ContextCollapseService.CollapseResult collapseResult =
        contextCollapseService.collapseMessages(current);
if (collapseResult.collapsedCount() > 0) {
    collapseExecuted = true;
    collapseCharsFreed = collapseResult.estimatedCharsFreed();
    current = collapseResult.messages();
    log.debug("Level 1.5 ContextCollapse: collapsed {} messages, ~{} chars freed",
            collapseResult.collapsedCount(), collapseCharsFreed);
}

// Level 2 修改：追踪 acAttempted
if (!trackingState.isCircuitBroken()) {
    TokenWarningState warning = calculateTokenWarningState(current, model);
    if (warning.isAboveAutoCompactThreshold()) {
        acAttempted = true;  // ★ 标记“已尝试”
        log.info("Level 2 AutoCompact triggered: {} tokens > threshold {}",
                warning.currentTokens(), warning.autoCompactThreshold());
        try {
            acResult = compactService.compact(current, contextWindow, false);
            if (acResult.skipReason() == null && !acResult.compactedMessages().isEmpty()) {
                acExecuted = true;
                current = acResult.compactedMessages();
            }
        } catch (Exception e) {
            log.error("Level 2 AutoCompact failed", e);
        }
    }
}

// 终态构造修改:
CascadeResult result = new CascadeResult(current, originalTokens, finalTokens,
        snipExecuted, snipTokensFreed, mcExecuted, mcTokensFreed,
        collapseExecuted, collapseCharsFreed,    // 新增
        acAttempted,                               // 新增
        acExecuted, acResult);
```

**P1-07 状态回写简化**（影响 `QueryEngine.java`）:

增强后，P1-07 修复文档中的状态回写逻辑可简化为:

```java
// 替换 P1-07 中复杂的状态回写逻辑
if (cascadeResult.autoCompactExecuted()) {
    state.resetAutoCompactFailures();
} else if (cascadeResult.autoCompactAttempted()) {
    // ★ 现在可以精确区分: 尝试了但失败 → 递增 failures
    state.incrementAutoCompactFailures();
}
// autoCompactAttempted=false 表示阈值未达到，无需操作
```

**验证方法**:
1. 单元测试: 构造阈值未达到场景，断言 `autoCompactAttempted=false`
2. 单元测试: 构造阈值达到但执行失败场景，断言 `autoCompactAttempted=true, autoCompactExecuted=false`
3. 单元测试: 构造成功场景，断言 `autoCompactAttempted=true, autoCompactExecuted=true`
4. 集成测试: 确认 `summary()` 输出包含 collapse 和 attempted 信息

**预估工作量**: 0.5-1d

---

### EXT-05: SystemPromptBuilder 统一使用 SystemPromptSectionCache（扩展 P1-05）

> **问题存在性**: ✅ 已确认。P1-05 已解决“双缓存并行”问题，但未进一步利用 `SystemPromptSectionCache` 的全局/会话二级缓存架构来优化静态段的跨会话缓存复用。  
> **必要性/ROI**: ★★★☆☆ 中。Claude Code 的 `SYSTEM_PROMPT_DYNAMIC_BOUNDARY` 将 prompt 分为全局缓存域（`scope: 'global'` 跨用户共享）和会话级缓存域。ZhikuCode 的 `SystemPromptSectionCache` 已有 `getOrComputeGlobal()` 和 `getOrComputeSession()` 两套 API，但 `SystemPromptBuilder` 未利用此区分。

**当前代码现状**:

```java
// SystemPromptSectionCache.java 已具备的 API（L62-100）:
getOrComputeGlobal(sectionName, contentHash, compute)    // 跨会话共享
getOrComputeSession(sessionId, sectionName, contentHash, compute) // 会话级隔离

// SystemPromptBuilder.java L66 — 当前使用无差别的 ConcurrentHashMap:
private final Map<String, String> sectionCache = new ConcurrentHashMap<>();
```

**修改文件**: `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptBuilder.java`

**修改方案 — 在 P1-05 基础上进一步区分全局/会话缓存域**:

> **段分类规则**（基于源码核查 L133-201）:
> - **全局段**（不随会话变化）: `INTRO_SECTION`, `SYSTEM_SECTION`, `DOING_TASKS_SECTION`, `ACTIONS_SECTION`, `session_guidance`, `token_budget`, `frc`, `numeric_length_anchors`
> - **会话级段**（随会话变化）: `memory`(CLAUDE.md), `env_info`, `language`, `mcp_instructions`, `scratchpad`, `summarize_tool_results`, `project_context`, `ant_specific_guidance`

将 P1-05 中的统一 Caffeine 调用细化为全局/会话两种调用模式:

```java
// 全局段示例（如 INTRO_SECTION、DOING_TASKS_SECTION）:
String content = sectionCache.getOrComputeGlobal(
        "INTRO_SECTION",
        computeIntroSection().hashCode(),
        this::computeIntroSection
);

// 会话级段示例（如 memory、env_info）:
String content = sectionCache.getOrComputeSession(
        sessionId,
        "memory",
        claudeMdContent.hashCode(),
        () -> buildMemorySection(claudeMdContent)
);
```

**需传递 sessionId**: 在 `buildSystemPrompt()` 方法签名中新增 `String sessionId` 参数（或从 `AppStateStore` 获取当前 sessionId）。

**验证方法**:
1. 启动后创建两个会话，确认全局段缓存命中率 > 0（第二个会话复用第一个的全局段）
2. 确认会话级段在不同会话间独立缓存
3. `clearSession(sessionId)` 仅清除对应会话缓存，全局段不受影响

**预估工作量**: 1d（与 P1-05 合并实施）

---

### EXT-06: 斜杠命令系统补齐评估

> **源码核查结果**: ZhikuCode 已实现 **19 个**斜杠命令（StatusCommand、ResumeCommand、DiffCommand、ModelCommand、RetryCommand、DoctorCommand、HelpCommand、ExitCommand、LogoutCommand、ConfigCommand、AllowedToolsCommand、SessionCommand、PermissionsCommand、CostCommand、ClearCommand、MemoryCommand、InitCommand、LoginCommand、CompactCommand），命令框架完整（CommandRegistry 249行、CommandRouter 100行、模糊匹配建议）。  
> **与 Claude Code 对比**: Claude Code 有 90+ 斜杠命令，ZhikuCode 19 个 ≈ 21% 覆盖率。但 Claude Code 的很多命令是终端 UI 特有的（如 `/vim`、`/theme`、`/keybindings`等），在 Web 架构下不适用。  
> **必要性/ROI**: ★★☆☆☆ 低。核心命令（/compact、/clear、/model、/cost、/permissions、/diff、/memory）已全部实现，剩余差距主要是工具性命令（如 `/rewind`、`/export`、`/symbols` 等）。

**建议补充的命令**（按优先级）:

| 优先级 | 命令 | 说明 | 估工 |
|---------|------|------|------|
| 高 | `/rewind` | 文件修改撤销（基于 updateFileHistoryState） | 1-2d |
| 高 | `/tasks` | 查看后台任务列表和状态 | 0.5d |
| 中 | `/agents` | 查看当前活跃 agent 和其状态 | 0.5d |
| 中 | `/export` | 导出会话历史为 Markdown/JSON | 1d |
| 低 | `/symbols` | 栅格化展示当前项目符号索引 | 1d |

> **备注**: `REMOTE_SAFE_COMMANDS`（L39-47）和 `BRIDGE_SAFE_COMMANDS`（L52-58）已预定义了 `/tasks`、`/agents`、`/symbols`、`/export` 等命令名，说明这些命令在设计时已被规划，只是尚未实现。

**预估总工作量**: 3-5d（全部 5 个命令）

---

## 六、功能对标综合评估 — 修复 + FEAT/EXP 补充方案全景分析（个人使用场景）

> **评估方法**: 基于 Claude Code 源码深度架构分析，对 ZhikuCode 全部功能域进行源码级验证。  
> **约束条件**: 个人使用（非企业团队）、模型无关（千问/DeepSeek 优先）。团队协作功能（Swarm/Team工具）和 Anthropic 特有优化（Prompt Cache）已从对标矩阵中移除。

### 6.1 核心能力域对标矩阵

| # | 功能域 | Claude Code 基线 | ZhikuCode 实现 | 完成度 | 修复方案覆盖 | 对标影响 |
|---|--------|------------------|---------------|--------|-------------|----------|
| 1 | Agent Loop (QueryEngine) | 2层循环 | 8步主循环 1046行 + 虚拟线程 | **95%** | P0/P1已覆盖 | ±0 |
| 2 | 工具系统 (52个工具) | ~44工具 | 40内建+12MCP=52, ToolExecutionPipeline 7阶段 | **95%** | P0-01/06已覆盖 | ±0 |
| 3 | BashTool 安全 | 18文件~5K行 | BashSecurityAnalyzer+Classifier 8层, 113测试 | **98%** | P0-03文档对齐 | ±0 |
| 4 | 权限系统 | 24文件~5K行 | 7模式/15步管线, AutoModeClassifier 597行 | **92%** | P1-03/P2-04已覆盖 | ±0 |
| 5 | 流式工具执行 | StreamingToolExecutor 530行 | **StreamingToolExecutor.java 196行** + 虚拟线程 | **95%** | — (已完整) | ±0 |
| 6 | 上下文压缩 | 4层压缩 | **5层级联**: Snip→MicroCompact→ContextCollapse→AutoCompact→ErrorRecovery | **92%** | P1-07/EXT-04/05 | ±0 |
| 7 | 文件历史/回退 | updateFileHistoryState + /rewind | **FileHistoryService 361行** + UndoCommandHandler 119行 | **90%** | — (已完整) | ±0 |
| 8 | 工具结果预算 | contentReplacementState | TOOL_RESULT_BUDGET_RATIO(0.3) + SnipService + MicroCompact | **88%** | P2-11已覆盖 | ±0 |
| 9 | 沙箱管理 | SandboxManager | **SandboxManager.java 262行** + Docker隔离 | **85%** | — (已完整) | ±0 |
| 10 | 子代理系统 | AgentTool ~6K行 | AgentTool 243行 + SubAgentExecutor + 并发控制 | **75%** | — | -0.5% |
| 11 | **LSP 代码智能** | LSPTool 核心差异化 | 框架100%, **LspService 42行全占位** | **15%** | **FEAT-01** (+3%) | -3% |
| 12 | **Plugin 市场** | 完整插件生态 | PluginManager生命周期完整, 无市场发现 | **60%** | **FEAT-03** (+1.5%) | -1.5% |
| 13 | **Session 快照** | 完整快照/恢复/导出 | SessionManager基础管理 | **40%** | **FEAT-04** (+0.5%) | -0.5% |
| 14 | Worktree 隔离 | worktree支持 | WorktreeManager 158行完整 | **90%** | **FEAT-05** (+0.3%) | -0.3% |
| 15 | 记忆系统 | Memory 完整 | MemdirService + MemoryTool 完整 | **90%** | — (已完整) | ±0 |
| 16 | 思维配置 | thinking模式 | ThinkingConfig sealed 3模式 | **95%** | — (已完整) | ±0 |
| 17 | Token预算 | tokenBudget.ts | TokenBudgetTracker 92行完整 | **95%** | — (已完整) | ±0 |
| 18 | Hook 系统 | hooks | HookService 完整 | **90%** | — (已完整) | ±0 |
| 19 | MCP/OAuth | MCP client 3.3K行 | McpAuthTool OAuth PKCE完整 | **85%** | P2-10已覆盖 | ±0 |
| 20 | 命令系统 | 90+斜杠命令 | ~20+命令 (核心已覆盖) | **65%** | **EXT-06** | -1% |
| 21 | **Bridge/Remote** | 12,613行完整远程控制 | BridgeServer 396行基础框架 | **30%** | **EXP-02** (+1%) | -1% |

> **说明**: 原矩阵中的 "Swarm 协作"(#12)、"Team 工具暴露"(#13)、"Fork Prompt Cache"(#23) 已移除——前两者属团队功能（个人使用不需要），后者为 Anthropic 特有 API（模型无关不适用）。

### 6.2 对标率阶段评估

| 阶段 | 完成内容 | 累计对标率 | 增量 | 说明 |
|------|---------|-----------|------|------|
| 当前基线 | — | **80.3%** | — | 测试报告 v3 实测 |
| Phase 1-4 | 修复项 (P0~P3 + EXT-02~06) | **~86%** | +5.7% | TaskCreateTool类型/命令补齐等 |
| Phase 5 | FEAT-01/03/04/05 (LSP/Plugin/Session/Worktree) | **~91.5%** | +5.5% | 最大单项: LSP +3% |
| Phase 6 | EXP-02 (Bridge增强) | **~92.5%** | +1% | Headless 模式 |

> **结论**: 在个人使用+模型无关约束下，移除团队功能(EXT-01/FEAT-02)和 Anthropic 特有优化(EXP-01)后，预计最终对标率可达 **~92.5%**。剩余 7.5% 差距主要来自子代理系统复杂度、Bridge 完整度等，属于长期迭代项。

### 6.3 已验证无缺口的功能域（前一轮怀疑项）

以下功能域在本轮源码验证中确认**已完整实现**，无需额外修复：

| 怀疑缺口 | 验证结果 | 关键文件 | 完成度 |
|----------|---------|---------|--------|
| 流式工具执行 | ✅ 完全实现 | `StreamingToolExecutor.java` (196行) | 95% |
| 文件历史/回退 | ✅ 完全实现 | `FileHistoryService.java` (361行) + `UndoCommandHandler.java` (119行) | 90% |
| 工具结果预算 | ✅ 完全实现 | `SnipService.java` + `MicroCompactService.java` + `ContextCollapseService.java` (159行) | 88% |
| 沙箱管理 | ✅ 完全实现 | `SandboxManager.java` (262行) + Docker 隔离 | 85% |
| 上下文折叠 | ✅ 完全实现 | `ContextCollapseService.java` (159行) + `ContextCascade.java` (303行) | 92% |
| 自动模式分类 | ✅ 完全实现 | `AutoModeClassifier.java` (597行), 两阶段(Quick+Thinking) | 88% |

> **重要发现**: ZhikuCode 的上下文管理实际上比 Claude Code 更先进——实现了 **5层压缩级联** (Snip→MicroCompact→ContextCollapse→AutoCompact→ErrorRecovery)，而 Claude Code 为 4层。StreamingToolExecutor 使用 Java 虚拟线程实现更简洁高效的并发调度。

### 6.4 剩余功能缺口与补充方案映射

| 缺口 | 当前状态 | 修复方案 | 预计提升 | 工作量 |
|------|---------|---------|---------|--------|
| LSP 代码智能 | LspService 全占位 | **FEAT-01** (第八章) | +3% | 3-5d |
| Plugin 市场 | 无市场发现 | **FEAT-03** (第八章) | +1.5% | 2-3d |
| Bridge/Remote | 基础框架(30%) | **EXP-02** (新增) | +1% | 2-3d |
| 命令系统覆盖 | 20+ vs 90+ | **EXT-06** (第五章) | +1% | 3-5d |
| Session 快照 | 基础管理 | **FEAT-04** (第八章) | +0.5% | 1-2d |
| Worktree 工具 | 无独立Tool类 | **FEAT-05** (第八章) | +0.3% | 0.5d |

> **已从缺口列表移除**: Swarm 协作（团队功能）、Team 工具暴露（团队功能）、Fork Prompt Cache（Anthropic 特有）。

---

## 八、达成90%对标率差距分析与补充方案（个人使用场景）

> **背景**: 执行上述修复项（P0-P3 + EXT-02~06）后，预计对标率约 **86%**。
> 差距核心原因：以下模块实际完成度不足。
>
> | 模块 | 当前实际状态 | 对标影响 |
> |--------|--------------|----------|
> | LSP 代码智能 | 工具框架 100%，**LspService 全部 6 个方法返回空/占位** | -3% |
> | Plugin 市场 | PluginManager/Loader/Extension 完整，**无市场发现与安装** | -1.5% |
> | Session 持久化 | SessionManager 基础，**无完整快照/恢复/导出** | -0.5% |

---

### FEAT-01: LspService 完整实现（对标影响 +3%） {#feat-01}

**问题识别**: `LspService.java`（42行）全部 6 个方法返回空结果或占位字符串，`LSPServerInstance.java` L64-77 的 `sendRequest()` 返回硬编码模拟数据。**LSPTool 的 9 种操作全部不可用**。

**影响**: Claude Code 的 LSPTool 是代码智能的核心差异化能力（go-to-definition, find-references, hover, call-hierarchy），且 ZhikuCode 已有完整的上层框架（LSPTool 9操作 + LSPServerConfig 5语言 + LSPServerManager 多实例路由），只缺底层通信。

**修改文件**: `backend/src/main/java/com/aicodeassistant/lsp/LspService.java`, `LSPServerInstance.java`

**当前代码快照**:

```java
// LspService.java (L18-41) — 全部 6 个方法占位
public Object getDefinition(String filePath, int line, int column) {
    return "LSP service not yet implemented — definition lookup unavailable";
}
public List<?> getReferences(String filePath, int line, int column) {
    return Collections.emptyList();
}
// ... 其余 4 个方法同样返回空/占位

// LSPServerInstance.java L64-77 — sendRequest 返回模拟数据
public Map<String, Object> sendRequest(String method, Map<String, Object> params) {
    // P1 占位: 返回模拟结果
    return Map.of("method", method, "server", config.name(),
                  "status", "placeholder",
                  "message", "LSP server integration pending");
}

// LSPServerInstance.java L35-41 — start() 无实际进程启动
public void start() {
    // P1: ProcessBuilder + stdin/stdout JSON-RPC
    this.running = true;  // 仅置位，无真实进程
}
```

**修改方案 — LSPServerInstance JSON-RPC 实现**:

```java
// === LSPServerInstance.java 重写 ===

private Process lspProcess;
private OutputStream stdin;
private BufferedReader stdout;
private final AtomicInteger requestIdCounter = new AtomicInteger(1);
private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

public void start() {
    log.info("Starting LSP server: {} ({})", config.name(), config.command());
    List<String> cmd = new ArrayList<>();
    cmd.add(config.command());
    cmd.addAll(config.args());
    
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(false);
    this.lspProcess = pb.start();
    this.stdin = lspProcess.getOutputStream();
    this.stdout = new BufferedReader(new InputStreamReader(lspProcess.getInputStream()));
    this.running = true;
    
    // 启动读取线程 (Virtual Thread)
    Thread.startVirtualThread(this::readResponses);
    
    // 发送 initialize 请求
    sendRequest("initialize", Map.of(
        "processId", ProcessHandle.current().pid(),
        "rootUri", "file://" + System.getProperty("user.dir"),
        "capabilities", Map.of()
    ));
    sendNotification("initialized", Map.of());
    this.lastActivity = Instant.now();
}

public Map<String, Object> sendRequest(String method, Map<String, Object> params) {
    if (!running || lspProcess == null) {
        throw new IllegalStateException("LSP server " + config.name() + " is not running");
    }
    int id = requestIdCounter.getAndIncrement();
    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pendingRequests.put(id, future);
    
    // 构建 JSON-RPC 消息
    ObjectNode rpc = MAPPER.createObjectNode();
    rpc.put("jsonrpc", "2.0");
    rpc.put("id", id);
    rpc.put("method", method);
    rpc.set("params", MAPPER.valueToTree(params));
    
    String json = rpc.toString();
    String message = "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + json;
    
    synchronized (stdin) {
        try {
            stdin.write(message.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            pendingRequests.remove(id);
            throw new RuntimeException("Failed to send LSP request", e);
        }
    }
    
    try {
        JsonNode result = future.get(config.requestTimeoutMs(), TimeUnit.MILLISECONDS);
        return MAPPER.convertValue(result, Map.class);
    } catch (TimeoutException e) {
        pendingRequests.remove(id);
        return Map.of("error", "LSP request timed out: " + method);
    } catch (Exception e) {
        return Map.of("error", "LSP request failed: " + e.getMessage());
    }
}

private void readResponses() {
    try {
        while (running && lspProcess.isAlive()) {
            // 解析 Content-Length header
            String header = stdout.readLine();
            if (header == null) break;
            if (!header.startsWith("Content-Length:")) continue;
            int contentLength = Integer.parseInt(header.substring(16).trim());
            stdout.readLine(); // 空行
            
            char[] body = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                read += stdout.read(body, read, contentLength - read);
            }
            
            JsonNode response = MAPPER.readTree(new String(body));
            if (response.has("id")) {
                int id = response.get("id").asInt();
                CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                if (future != null) {
                    future.complete(response.has("result") ? response.get("result") : response);
                }
            }
            // 通知类消息 (diagnostics 等) 可缓存供后续查询
        }
    } catch (Exception e) {
        if (running) log.warn("LSP response reader error: {}", config.name(), e);
    }
}

public void stop() {
    this.running = false;
    if (lspProcess != null && lspProcess.isAlive()) {
        try {
            sendRequest("shutdown", Map.of());
            sendNotification("exit", Map.of());
            lspProcess.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            lspProcess.destroyForcibly();
        }
    }
}
```

```java
// === LspService.java 重写 — 委托 LSPServerManager ===
@Service
public class LspService {
    private final LSPServerManager serverManager;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    public LspService(LSPServerManager serverManager) {
        this.serverManager = serverManager;
    }
    
    public Object getDefinition(String filePath, int line, int column) {
        serverManager.openFile(filePath);
        Map<String, Object> result = serverManager.sendRequest(filePath,
            "textDocument/definition", buildTextDocumentPosition(filePath, line, column));
        return result != null ? result : "No definition found";
    }
    
    public List<?> getReferences(String filePath, int line, int column) {
        serverManager.openFile(filePath);
        Map<String, Object> result = serverManager.sendRequest(filePath,
            "textDocument/references", buildReferenceParams(filePath, line, column));
        return result != null ? extractLocations(result) : Collections.emptyList();
    }
    
    // ... 其余 4 个方法同理委托
    
    private Map<String, Object> buildTextDocumentPosition(String file, int line, int col) {
        return Map.of("textDocument", Map.of("uri", "file://" + file),
                      "position", Map.of("line", line - 1, "character", col - 1));
    }
}
```

**验证**: `curl -X POST /api/lsp/definition -d '{"file":"/path/to/file.java","line":42,"column":10}'` 返回真实位置而非占位符

**预估工作量**: 3-5d（ProcessBuilder + JSON-RPC 协议 + 5语言服务器测试）

**依赖**: 无（LSPTool + LSPServerConfig + LSPServerManager 均已就绪）

---

### ~~FEAT-02: TeamCreateTool / TeamDeleteTool 独立工具~~ 【已删除 — 个人使用场景不需要】 {#feat-02}

> **删除理由**: TeamCreateTool/TeamDeleteTool 是多 Agent 团队协作的入口，允许 LLM 主动创建/销毁团队。个人使用场景下不需要团队功能，`TeamManager` CRUD 已完整实现但无需通过 Tool 类暴露给 LLM。  
> **影响**: `ToolRegistry.SUB_AGENT_DENIED_TOOLS` 中引用的 `"TeamCreate"`/`"TeamDelete"` 名称将继续作为安全网保留，不影响功能。  
> **原对标影响**: +1%（企业团队场景），个人使用场景下对标率无意义。

---

### FEAT-03: Plugin 市场注册与发现（对标影响 +1.5%） {#feat-03}

**问题识别**: `PluginManager`（110+行）已完整实现插件加载/注册/卸载/热重载，`PluginExtension` 接口定义了命令/工具/钩子/MCP 服务器注册。但**无插件市场发现和远程安装机制**——当前只能加载本地 JAR/内置插件。Claude Code 拥有完整的 officialMarketplace（91KB）+ installedPluginsManager（40KB）+ 自动更新。

**影响**: 插件是可扩展性的核心，无市场 = 无生态。

**修改文件**: 新建 `backend/src/main/java/com/aicodeassistant/plugin/PluginMarketplace.java`, `PluginInstaller.java`

**修改方案**:

```java
// === PluginMarketplace.java (新建) ===
@Service
public class PluginMarketplace {
    
    private static final Logger log = LoggerFactory.getLogger(PluginMarketplace.class);
    
    /** 内置插件注册表 (MVP: 本地 JSON 文件) */
    private static final String REGISTRY_PATH = "configuration/plugins/registry.json";
    
    /** 注册表缓存 */
    private volatile List<PluginManifest> registry = List.of();
    
    public record PluginManifest(
        String name, String version, String description,
        String author, String downloadUrl, String checksum,
        List<String> tags, String minApiVersion
    ) {}
    
    /** 加载插件注册表 */
    @PostConstruct
    public void loadRegistry() {
        try {
            Path registryPath = Path.of(REGISTRY_PATH);
            if (Files.exists(registryPath)) {
                registry = MAPPER.readValue(registryPath.toFile(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, PluginManifest.class));
                log.info("Plugin marketplace loaded: {} plugins available", registry.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load plugin registry: {}", e.getMessage());
        }
    }
    
    /** 搜索插件 */
    public List<PluginManifest> search(String query) {
        String q = query.toLowerCase();
        return registry.stream()
            .filter(p -> p.name().toLowerCase().contains(q)
                      || p.description().toLowerCase().contains(q)
                      || p.tags().stream().anyMatch(t -> t.toLowerCase().contains(q)))
            .toList();
    }
    
    /** 列出所有可用插件 */
    public List<PluginManifest> listAll() { return List.copyOf(registry); }
}

// === PluginInstaller.java (新建) ===
@Service
public class PluginInstaller {
    
    private final PluginManager pluginManager;
    private final PluginMarketplace marketplace;
    private static final Path PLUGIN_DIR = Path.of("plugins/");
    
    public PluginInstaller(PluginManager pluginManager, PluginMarketplace marketplace) {
        this.pluginManager = pluginManager;
        this.marketplace = marketplace;
    }
    
    /** 从市场安装插件 */
    public String install(String pluginName) {
        var manifest = marketplace.search(pluginName).stream()
            .filter(p -> p.name().equalsIgnoreCase(pluginName)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + pluginName));
        
        // 下载 JAR 到 plugins/ 目录
        Path jarPath = PLUGIN_DIR.resolve(manifest.name() + "-" + manifest.version() + ".jar");
        // MVP: 支持 file:// 和 http:// 协议
        downloadFile(manifest.downloadUrl(), jarPath);
        
        // 重新加载插件
        pluginManager.reloadPlugins();
        return "Plugin '%s' v%s installed successfully".formatted(manifest.name(), manifest.version());
    }
}
```

**验证**: `/plugin install <name>` 命令可从注册表发现并安装插件

**预估工作量**: 2-3d

**依赖**: PluginManager（已完整）

---

### FEAT-04: Session 快照与完整恢复（对标影响 +0.5%） {#feat-04}

**问题识别**: `SessionController` 有 `POST /api/sessions/{id}/resume` 端点，但 `SessionManager` 的快照/恢复能力有限，缺少完整的会话历史消息序列化和持久化。Claude Code 支持 `--resume` 参数完整恢复上次会话（含所有消息 + 工具调用结果）。

**修改文件**: `SessionManager.java`, `SessionController.java`

**修改方案要点**:

1. **会话快照序列化**: 每轮对话结束后，将 `QueryLoopState.getMessages()` 序列化为 JSON 并存储到 `~/.zhikucode/sessions/{sessionId}.json`
2. **Resume 端点增强**: `POST /api/sessions/{id}/resume` 从快照文件恢复消息序列，重建 QueryLoopState
3. **/export 命令**: 将会话导出为 Markdown/JSON 格式（补充 EXT-06 中 `/export` 命令的实现细节）

```java
// SessionManager 新增方法
public void saveSnapshot(String sessionId, List<Message> messages) {
    Path snapshotDir = Path.of(System.getProperty("user.home"), ".zhikucode", "sessions");
    Files.createDirectories(snapshotDir);
    Path file = snapshotDir.resolve(sessionId + ".json");
    MAPPER.writeValue(file.toFile(), Map.of(
        "sessionId", sessionId,
        "timestamp", Instant.now().toString(),
        "messageCount", messages.size(),
        "messages", messages
    ));
}

public Optional<List<Message>> loadSnapshot(String sessionId) {
    Path file = Path.of(System.getProperty("user.home"), ".zhikucode", "sessions", sessionId + ".json");
    if (!Files.exists(file)) return Optional.empty();
    JsonNode root = MAPPER.readTree(file.toFile());
    return Optional.of(MAPPER.convertValue(root.get("messages"),
        MAPPER.getTypeFactory().constructCollectionType(List.class, Message.class)));
}
```

**验证**: 启动时传入 `--resume <sessionId>` 能恢复历史对话

**预估工作量**: 1-2d

**依赖**: 无

---

### FEAT-05: EnterWorktreeTool / ExitWorktreeTool 独立工具（对标影响 +0.5%） {#feat-05}

**问题识别**: `WorktreeManager`（158行）已完整实现 git worktree 创建/合并/销毁，`AgentTool` 支持 `isolation: "worktree"` 参数。但当前 **只能通过 Agent 工具隐式使用**，不支持用户主动进入/退出 worktree 隔离环境。Claude Code 有独立的 EnterWorktreeTool/ExitWorktreeTool 允许用户显式切换。

**修改文件**: 新建 `backend/src/main/java/com/aicodeassistant/tool/agent/EnterWorktreeTool.java`, `ExitWorktreeTool.java`

**修改方案要点**:

```java
// === EnterWorktreeTool.java (新建) ===
@Component
public class EnterWorktreeTool implements Tool {
    private final WorktreeManager worktreeManager;
    private final SessionManager sessionManager;
    
    @Override public String getName() { return "EnterWorktree"; }
    @Override public String getDescription() {
        return "Enter an isolated git worktree for safe experimentation. " +
               "Changes can be merged back or discarded when exiting.";
    }
    
    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String purpose = input.getString("purpose", "experiment");
        Path worktreePath = worktreeManager.createWorktree("user-" + context.sessionId());
        // 切换会话工作目录
        sessionManager.setWorkingDirectory(context.sessionId(), worktreePath.toString());
        return ToolResult.success("Entered worktree at: " + worktreePath +
            "\nAll file operations now target the isolated copy. Use ExitWorktree to return.");
    }
}

// === ExitWorktreeTool.java (新建) ===
@Component
public class ExitWorktreeTool implements Tool {
    private final WorktreeManager worktreeManager;
    private final SessionManager sessionManager;
    
    @Override public String getName() { return "ExitWorktree"; }
    
    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        boolean merge = input.getBoolean("merge", true);
        Path worktreePath = Path.of(context.workingDirectory());
        
        if (merge && worktreeManager.hasChanges(worktreePath)) {
            worktreeManager.mergeBack(worktreePath);
        }
        worktreeManager.removeWorktree(worktreePath);
        // 恢复原始工作目录
        sessionManager.restoreWorkingDirectory(context.sessionId());
        return ToolResult.success(merge ? "Worktree changes merged and cleaned up."
                                        : "Worktree discarded.");
    }
}
```

**验证**: LLM 可通过 `EnterWorktree` 进入隔离环境试验，完成后 `ExitWorktree` 合并或丢弃

**预估工作量**: 0.5d（WorktreeManager 已完整实现）

**依赖**: 无

---

### 90% 对标率工作量汇总（个人使用场景）

| 编号 | 内容 | 对标提升 | 估工 | 依赖 |
|------|------|----------|------|------|
| FEAT-01 | LspService JSON-RPC 实现 | +3% | 3-5d | 无 |
| FEAT-03 | Plugin 市场注册与发现 | +1.5% | 2-3d | PluginManager |
| FEAT-04 | Session 快照与恢复 | +0.5% | 1-2d | 无 |
| FEAT-05 | EnterWorktree/ExitWorktree | +0.5% | 0.5d | WorktreeManager |
| **总计** | | **+5.5%** | **7-10d** | |

> 基础修复项后预计 ~86% + FEAT 补充 +5.5% = **~91.5%**  
> 注：原 FEAT-02(TeamCreate/Delete) 已删除（个人使用不需要团队工具）

---

### ~~EXP-01: Fork Subagent Prompt Cache 优化~~ 【已删除 — Anthropic 特有 API，不适用于千问/DeepSeek】 {#exp-01}

> **删除理由**: Prompt Cache 是 Anthropic API 特有功能（`cache_control` 标记），千问和 DeepSeek 的 API 不支持此机制。在模型无关架构下，实现 fork 时复用父对话 prompt cache 前缀的优化无法泛化。  
> **替代方案**: 如果未来千问/DeepSeek 支持类似的缓存机制，可通过 `LlmProvider` 抽象层按模型类型条件启用。当前不纳入实施计划。  
> **原对标影响**: +0.5%（性能优化，仅 Anthropic 模型受益）。

---

### EXP-02: Bridge/Remote Mode 增强（对标影响 +1%） {#exp-02}

**问题识别**: `BridgeServer.java`（396行）实现了基础框架（环境注册/注销、会话创建/关闭、消息路由、权限转发），但仅为 Claude Code Bridge 模块（12,613行）的 **~3%** 实现。

**个人使用场景精简**: 个人用户最有价值的是 **Headless 模式**（纯 API 驱动，无需 UI），可用于脚本自动化、CI/CD 集成。多会话并行和复杂远程环境管理属企业功能，优先级降低。

**修改文件**: `backend/src/main/java/com/aicodeassistant/bridge/BridgeServer.java`, 新建 `HeadlessController.java`

**修复方案**（精简版）:

```java
// 1. Headless 模式 (REST API 驱动) — 个人使用核心价值
@RestController
@RequestMapping("/api/headless")
public class HeadlessController {
    @PostMapping("/query")
    public ResponseEntity<StreamingResponseBody> query(@RequestBody HeadlessRequest request) {
        // 无 UI，纯 API 调用 QueryEngine
    }
}

// 2. V2 协议扩展 (file_change/env_sync)
public BridgeMessage handleMessageV2(BridgeMessage message) {
    return switch (message.type()) {
        case "tool_use"      -> routeToolUse(message);
        case "tool_result"   -> routeToolResult(message);
        case "permission"    -> forwardPermission(message);
        case "file_change"   -> syncFileChange(message);
        default              -> BridgeMessage.error("Unknown type: " + message.type());
    };
}
```

**验证方法**: 
- `curl -X POST http://localhost:8080/api/headless/query -d '{"prompt":"hello"}'` 验证 headless 响应

**预估工作量**: 2-3d（精简后降低）

---

### 92.5% 对标率工作量汇总（含 EXP 补充，个人使用场景）

| 编号 | 内容 | 对标提升 | 估工 | 依赖 |
|------|------|----------|------|------|
| FEAT-01 | LspService JSON-RPC 实现 | +3% | 3-5d | 无 |
| FEAT-03 | Plugin 市场注册与发现 | +1.5% | 2-3d | PluginManager |
| FEAT-04 | Session 快照与恢复 | +0.5% | 1-2d | 无 |
| FEAT-05 | EnterWorktree/ExitWorktree | +0.5% | 0.5d | WorktreeManager |
| **EXP-02** | **Bridge Headless 模式** | **+1%** | **2-3d** | BridgeServer |
| **总计** | | **+6.5%** | **9-14d** | |

> 基础修复项后 ~86% + FEAT 补充 +5.5% + EXP 补充 +1% = **~92.5%**  
> 注：原 EXP-01(Fork Prompt Cache) 已删除（Anthropic 特有 API，千问/DeepSeek 不支持），原 FEAT-02(TeamCreate/Delete) 已删除（团队功能）

---

| 优先级 | 推荐立即实施（ROI ★★★★+） | 可推迟实施（ROI ★★以下） |
|---------|------|------|
| **P0** | P0-01/06 (isMcp), P0-02 (TaskCreateTool) | P0-03/04/05 (纯文档) |
| **P1** | P1-01 (STOMP统一), P1-02 (denied tools), **P1-07 (ContextCascade)** | P1-03 (UX增强), P1-04 (单代理无感), P1-05 (缓存工作正常), P1-08 (合并P1-01) |
| **P2** | P2-06 (单元测试) | — |
| **P3** | — | — |
| **EXT** | **EXT-04 (CascadeResult)** | EXT-02 (依赖P0-02), EXT-03 (独立), EXT-05 (依赖P1-05), EXT-06 (低优) |

> **特别说明**: P1-07 在本轮审查中确认为比原描述更严重的实质性 bug——`QueryEngine` 完全绕过 `ContextCascade`，导致已实现的 Level 1.5 ContextCollapse 在实际查询中从未执行，建议提升至 **P0 级别**。
>
> **功能扩展说明**: EXT-02~06 为基于 Claude Code 对标分析新增的功能扩展方案（原 EXT-01 SwarmService 已删除——个人使用不需要团队协作）。

---

## 七、修复优先级路线图

```
Phase 1 — Week 1-2 (P0 阻塞修复):
├── P0-01/06: McpToolAdapter.isMcp() 覆写 .................. 0.5h
├── P0-02: TaskCreateTool 执行体实现 (agent/shell) ......... 2-3d
└── P0-03/04/05: 文档同步更新 ............................. 1d

Phase 2 — Week 2-4 (P1 高优修复 + 核心功能扩展):
├── P1-01: WebSocket 双客户端统一 + 心跳超时 .............. 2-3d
├── P1-02: SubAgent denied tools 对齐 ..................... 1h
├── P1-03: 前端 120s 权限审批倒计时 ...................... 0.5d
├── P1-04: SendMessageTool senderId 修复 .................. 0.5h
├── P1-05: SystemPrompt 双缓存统一 ....................... 1d
├── P1-06: SSE heartbeat 日志级别调整 .................... 0.5h
├── P1-07: ContextCollapseService 接入正常级联 ............ 1d
├── P1-08: 前端 WebSocket 消息解析修复 ................... 1d
├── EXT-04: CascadeResult 增强 (autoCompactAttempted) ...... 0.5-1d
└── EXT-05: SystemPromptBuilder 全局/会话缓存优化 ......... 1d (合并P1-05)

Phase 3 — Month 1-2 (P2 中等修复 + 任务类型扩展):
├── EXT-02: TaskCreateTool 精简任务类型 .................. 1-2d  (依赖P0-02)
├── P2-02: Virtual Thread 命名 ........................... 0.5h
├── P2-03: getEnabledTools() 排序 ........................ 0.5h
├── P2-04: riskLevel LOW 判定 ............................ 1h
├── P2-06: BashCommandClassifier/SedValidator 单元测试 ..... 1-2d
├── P2-10: OAuth 令牌加密存储 ............................ 1-2d
└── P2-11: 上下文管理可配置+可观测 ....................... 1d

Phase 4 — Month 2-3 (高级功能扩展 + 低优修复):
├── EXT-03: DreamTask 自动记忆整理 ...................... 3-5d
├── EXT-06: 斜杠命令补齐 (/rewind/tasks/agents) ........... 3-5d
└── P3-02/06/07/09: 代码整洁度优化 ....................... 1-2d

Phase 5 — Month 3-4 (91.5% 对标补充方案):
├── FEAT-01: LspService JSON-RPC 实现 .................... 3-5d  ★ 最大单项提升
├── FEAT-03: Plugin 市场注册与发现 ........................ 2-3d
├── FEAT-04: Session 快照与完整恢复 ...................... 1-2d
└── FEAT-05: EnterWorktree/ExitWorktree 独立工具 ......... 0.5d

Phase 6 — Month 4-5 (92.5% 对标优化方案):
└── EXP-02: Bridge Headless 模式增强 ..................... 2-3d
```

### 执行依赖链（必须按顺序执行）

```
╔══════════════════════════════════════════════════════════════════╗
║  EXT-04 (CascadeResult 增强)  ────→  P1-07 (ContextCascade 接入)  ║
║  │ 新增 autoCompactAttempted     │ 使用新字段做状态回写        ║
╠══════════════════════════════════════════════════════════════════╣
║  P0-02 (TaskCreateTool agent+shell) ─→  EXT-02 (剩余 3 种类型)   ║
║  │ 基础 switch 框架              │ workflow/monitor/dream        ║
╠══════════════════════════════════════════════════════════════════╣
║  P1-05 (双缓存统一)      ─────→  EXT-05 (全局/会话缓存优化)   ║
║  │ 替换 ConcurrentHashMap     │ 区分 global/session 缓存域     ║
╠══════════════════════════════════════════════════════════════════╣
║  P1-01 (STOMP 统一)    ──‼ 影响─→  P1-08 (自动废弃，无需单独执行)    ║
║  │                       P2-01 (已合并，无需单独执行)      ║
╚══════════════════════════════════════════════════════════════════╝

★ 独立可并行执行（无前置依赖）:
  P0-01/06, P0-03/04/05, P1-02, P1-03, P1-04, P1-06,
  P2-02, P2-03, P2-04, P2-06, P2-10, P2-11,
  P3-02, P3-06, P3-07, P3-09, EXT-03, EXT-06,
  FEAT-05
```

### 对标率预期提升

| 阶段 | 完成内容 | 预期对标率 | 提升幅度 |
|------|---------|-----------|--------|
| 当前基线 | — | 80.3% | — |
| Phase 1 | P0 阻塞修复 (6项) | 84% | +3.7% |
| Phase 2 | P1 高优修复 + EXT-04/05 | 85.5% | +1.5% |
| Phase 3 | P2 + TaskCreateTool 精简类型扩展 | 86% | +0.5% |
| Phase 4 | DreamTask + 命令补齐 + P3 | 86.5% | +0.5% |
| **Phase 5** | **FEAT-01/03/04/05 (LSP/Plugin/Session/Worktree)** | **~91.5%** | **+5%** |
| **Phase 6** | **EXP-02 (Bridge Headless 增强)** | **~92.5%** | **+1%** |

---

> **文档生成时间**: 2026 年 4 月 16 日  
> **基于报告**: ZhikuCode 核心功能测试报告 v3  
> **适用场景**: 个人使用（非企业团队），模型无关（千问/DeepSeek 优先，兼容 Anthropic）  
> **覆盖问题**: 28/37 (76%) — 9 个低 ROI 项已移除（P2-05/07/08/09, P3-01/03-05/08）  
> **功能扩展**: 5 项（EXT-02~06）— 基于 Claude Code 对标分析新增，覆盖 TaskCreateTool 精简类型、DreamTask、CascadeResult 增强、Prompt Cache 优化、斜杠命令补齐（原 EXT-01 SwarmService 已删除——团队功能）  
> **91.5% 对标补充**: 4 项（FEAT-01/03/04/05）— LspService 实现、Plugin 市场、Session 快照、Worktree 独立工具（原 FEAT-02 TeamCreate/Delete 已删除——团队功能）  
> **92.5% 对标优化**: 1 项（EXP-02）— Bridge Headless 模式增强（原 EXP-01 Fork Prompt Cache 已删除——Anthropic 特有 API）  
> **累计审查**: 16 轮 / 15 维度 / 60+n 处修正
>
> **第十六轮审查内容** (12维度源码交叉验证，3处):
> 1. **P2 章节标题计数修正**: “三、P2 — 中等优先级修复（9项）” → “7项”，与文档头部 P2×7 保持一致（实际包含 P2-01/02/03/04/06/10/11 共 7 个小节）
> 2. **EXT-06 行数微调**: CommandRegistry 250→249行、CommandRouter 101→100行（经 `wc -l` 精确验证）
> 3. **EXT-02 executeMonitorMcp 异常处理增强**: `Integer.parseInt(parts[1].trim())` 无异常处理，非数字输入将抛 NumberFormatException 崩溃监控任务，添加 try-catch 安全降级为默认 60s
>
> **第十六轮验证确认无误项** (9项保持原文不变):
> - P0-05 权限管线 15 步: 经 `PermissionPipeline.java` 源码逐步核查，实际包含 Step 1a−1j + Step 2a/2b + Step 3 = 15 个标记检查点，文档描述正确
> - P2-02 虚拟线程 9 处: 经 `grep` 全代码库搜索，确认 9 个位置全部存在，SettingsWatcher 已命名“settings-watcher”
> - P0-04 并发参数: AgentConcurrencyController 确认全局 30 / 会话 10 / 嵌套 3
> - P0-03 Bash 安全文件行数: 13 个文件全部经 `wc -l` 验证，行数完全匹配
> - EXT-06 斜杠命令 19 个: impl/ 目录下 19 个独立 Command 文件全部存在
> - P0-02 AgentRequest 6参构造函数: 经 SubAgentExecutor.java L409-411 确认存在，参数类型正确
> - P0-02 toolRegistry.findByNameOptional("Bash"): 经 ToolRegistry.java L64 确认方法存在
> - P2-04 BashCommandClassifier.isReadOnlyCommand(): 经 BashCommandClassifier.java L662 确认方法存在
> - 安全性审查: 文档代码片段无 rm -rf/sudo/env 等危险命令，EXT-02 curl 通过 BashTool 安全链执行，P2-10 AES-256-GCM 加密方案合理
>
> **第十五轮审查内容** (个人使用场景适配 + 模型无关化，8处):
> 1. **删除 EXT-01 SwarmService**: 多 Agent 团队协作模块，属企业功能，个人使用不需要（~240行代码删除）
> 2. **删除 FEAT-02 TeamCreate/TeamDelete**: 团队工具入口，个人使用不需要（~96行删除）
> 3. **删除 EXP-01 Fork Prompt Cache**: Anthropic 特有 `cache_control` API，千问/DeepSeek 不支持（~37行删除）
> 4. **精简 EXT-02**: 移除 `in_process_teammate`/`remote_agent` 两种团队任务类型，保留 3 种（workflow/monitor/dream），工作量 2-3d→1-2d
> 5. **精简 EXP-02**: 聚焦 Headless 模式（个人使用核心价值），移除多会话/远程管理企业功能，工作量 3-5d→2-3d
> 6. **措辞模型无关化**: 全文 "prompt cache命中率" → "工具列表稳定性"（千问/DeepSeek 无 prompt cache 概念）
> 7. **第六章对标矩阵精简**: 24→21 行，移除 Swarm协作/Team工具/Fork Cache 三行，对标率 94.5%→92.5%
> 8. **路线图/依赖链/统计全面校准**: 移除已删除项，重算各 Phase 时间线和百分比
>
> **第十四轮审查内容** (4处):
> 1. **新增第六章**: 「功能对标综合评估」— 24个核心能力域对标矩阵、分阶段对标率评估、已验证无缺口功能域确认、剩余缺口与方案映射
> 2. **新增 EXP-01/02**: Fork Subagent Prompt Cache 优化（+0.5%）+ Bridge/Remote Mode 增强（+1%），将最终对标率从 93% 提升至 94.5%
> 3. **源码验证关键发现**: StreamingToolExecutor(196行/95%)、FileHistoryService(361行/90%)、ContextCollapseService(159行/92%)、SandboxManager(262行/85%)、AutoModeClassifier(597行/88%) 均已完整实现，不构成对标缺口
> 4. **路线图更新**: 新增 Phase 6（EXP-01~02，4-7d），累计预期从 ~93% 提升至 ~94.5%
>
> **第十三轮审查内容** (5处):
> 1. **对标率修正**: 原 Phase 3 声称 90%、Phase 4 声称 92% 过于乐观，基于源码实际完成度核查，修正为 Phase 4 = 87.5%，新增 Phase 5 = ~93%
> 2. **新增第八章**: 「90% 对标率差距分析与补充方案」— 5 项 FEAT 方案覆盖 LSP、Team 工具、Plugin 市场、Session 快照、Worktree 独立工具
> 3. **核心发现**: LspService.java 42行全部占位（单项影响 -3%）、TeamCreate/TeamDeleteTool 不存在但被 SUB_AGENT_DENIED_TOOLS 引用、Plugin 无市场发现机制、WorktreeManager 完整但无用户端工具
> 4. **工具清单核实**: ZhikuCode 实际 38 个工具（含 TodoWrite/Sleep/Brief/REPL/LSP/Skill/PowerShell/Config/ToolSearch/Cron/Monitor 等）、77 个命令、完整 Plugin 架构、完整 Worktree 实现
> 5. **路线图更新**: 新增 Phase 5（7-11d）覆盖 FEAT-01~05，累计预期从 87.5% 提升至 ~93%
>
> **第十二轮审查修正内容** (6处):
> 1. **CompactResult.summary() 事实纠正**: 第十一轮错误声称 `CompactService.CompactResult` 不存在 `summary()` 方法，经源码核查 `CompactService.java` L148-151 确认该方法**确实存在**。EXT-04 `summary()` 代码已恢复使用 `autoCompactResult.summary()` 而非手动格式化
> 2. **P3 节标题计数修正**: “5项，原7项保留5项” → “4项，原9项保留4项”（实际仅含 P3-02/06/07/09 四项，原 P3 共 9 项其中 5 项已移除）
> 3. **P1-07 标题级别修正**: `## P1-07` → `### P1-07`，与其他 P1 项保持一致
> 4. **文档头部计数修正**: 问题总数 36→34，P3×5→P3×9（原始报告计数），功能扩展×5→×6，低 ROI 移除数 7→9（实际移除 P2-05/07/08/09 + P3-01/03/04/05/08 = 9 项）
> 5. **文档尾部覆盖率修正**: 30/37(81%) → 28/37(76%)，低 ROI 移除数 7→9，与头部保持一致
> 6. **4维度全量核查**: 对全部 34 项修复方案基于最新源码进行问题存在性/修复必要性/可行性/技术栈适配四维度审查，确认其余方案均与源码一致、可行、技术栈兼容
>
 **第十一轮审查修正内容** (4处):
> 1. **P0-03 行数回滚**: 第十轮错误地将 BashCommandClassifier.java 990→991、SedValidator.java 206→207，经 `wc -l` 二次验证实际为 990 行和 206 行，回滚为正确值
> 2. **EXT-01 行号与 TODO 注释修正**: createSwarm 行号 L44-48→L44-49、executeSwarm L55-59→L55-60（方法体含 TODO 注释各占一行）；当前代码展示补回实际源码中存在的 `// TODO:` 注释（第十轮误称"移除 TODO 与源码对齐"，实际源码中 TODO 仍存在）
> 3. ~~**EXT-04 编译错误修复**~~: **第十二轮修正**: 第十一轮错误声称 `CompactService.CompactResult` 不存在 `summary()` 方法，经源码核查 `CompactService.java` L148-151 确认 `summary()` 方法**确实存在**（返回格式化压缩统计字符串）。当前 `ContextCascade.java` L110 已正确调用 `autoCompactResult.summary()`。EXT-04 的 `summary()` 方法已恢复使用 `autoCompactResult.summary()` 而非手动格式化
> 4. **EXT-01 问题存在性描述增强**: 补充"方法体内含 TODO 注释"说明，与实际源码完全对齐
>
> **第十轮审查修正内容** (5处):
> 1. **P2-02 虚拟线程计数修正**: 原文档声称"共4处无名虚拟线程"，源码核查确认 SubAgentExecutor 仅 L247 一处，全代码库共9处（其中 SettingsWatcher 已命名，其余8处未命名），修复工作量从 0.5h 调整为 1-2h
> 2. **EXT-01 当前代码描述修正**: 原文档"L44-60全部方法"不准确，实际仅有 createSwarm 和 executeSwarm 两个方法，无 destroySwarm
> 3. **EXT-01 NPE 空指针修复**: `AgentResult.result()` 可能为 null（record 字段），原代码 3 处直接调用 `.contains()` 可引发 NPE，提取为 `isWorkerFailed()` 工具方法统一空安全判定
> 4. **P0-03 行数微调**: BashCommandClassifier.java 行数与 SedValidator.java 行数核查（后被第十一轮回滚）
> 5. **文档 typo 修正**: 2处"依趖"→"依赖"（EXT-02 L1482、评估表 EXT 行）
>
> **第九轮审查新增维度**:
> - 维度13 对标完整性: 基于 Claude Code 源码深度架构分析，识别 6 项修复文档未覆盖但对对标率提升至关重要的功能缺口
>
> **第九轮审查新增内容** (6处):
> 1. **EXT-01 SwarmService 核心实现**: 从 P2-07（已移除）重新纳入并升级，基于已有 TeamManager+InProcessBackend 实现 3 个核心方法（createSwarm/executeSwarm/destroySwarm）
> 2. **EXT-02 TaskCreateTool 完整类型**: 在 P0-02 基础上补充 in_process_teammate/remote_agent/local_workflow/monitor_mcp/dream 5 种类型执行体
> 3. **EXT-03 DreamTask 自动记忆整理**: 新建 DreamTaskExecutor，三阶段执行(SCANNING→REVIEWING→COMPLETE)，包含锁管理和 mtime 回滚机制
> 4. **EXT-04 CascadeResult 增强**: 新增 autoCompactAttempted/contextCollapseExecuted 字段，解决 P1-07 状态回写无法精确区分“阈值未达到”和“执行失败”的设计缺陷
> 5. **EXT-05 Prompt Cache 全局/会话优化**: 在 P1-05 基础上进一步区分全局段/会话级段缓存域，复用 SystemPromptSectionCache 已有的二级架构
> 6. **EXT-06 斜杠命令补齐评估**: 源码核查确认已有 19 个命令，核心命令已全部实现，建议补充 /rewind/tasks/agents/export/symbols 5 个工具性命令
> - 维度11 必要性/ROI: 为每个修复方案添加了★评级，**已删除 7 个低 ROI 项**（P2-05/07/08/09, P3-01/03-05/08）
> - 维度12 问题存在性: 逐项源码核查确认
>
> **第五轮审查修正内容** (3处):
> 1. **P1-07 重大升级**: 原描述"确认是否绕过"→确认 QueryEngine L199-217 **完全绕过** ContextCascade，补充了实际代码证据和具体改造步骤
> 2. **P1-08 问题重定位**: stompClient.ts parseMessage 已有完善防御，真正漏洞在 useWebSocket.ts L120 裸 JSON.parse
> 3. **P1-05 包路径修正**: SystemPromptSectionCache 实际包路径为 `com.aicodeassistant.context`
>
> **第六轮审查修正内容** (3处):
> 1. **P1-07 编译错误修复**: `state.getTrackingState()` 不存在于 `QueryLoopState`（L19-166 全字段无 `AutoCompactTrackingState`），新增 `toAutoCompactTrackingState()` 辅助方法映射分散状态
> 2. **P0-03 子目录路径修正**: 13 个 Bash 安全文件实际分布在 `tool/bash/`(6) + `tool/bash/ast/`(3) + `tool/bash/parser/`(4) 三层目录，而非全平级
> 3. **P2-04 安全 API 升级**: `classify().isReadOnly()` 改为 `isReadOnlyCommand()`(L662)，后者增加 $变量展开、花括号展开、管道递归安全检查
>
> **第七轮审查修正内容** (2处):
> 1. **P1-07 AutoCompactTrackingState 4字段修正**: record 有 4 个字段 `(compactedThisTurn, turnCounter, lastTurnId, consecutiveFailures)`，原代码仅传 3 参且类型映射错误（第3参 boolean 传给 String 字段），导致编译错误
> 2. **P1-08 事实纠正**: `useWebSocket.ts` L118-124 已有 try-catch 容错，并非"裸 JSON.parse 无任何 fallback"，严重性降级为 payload.type 校验缺失 + console.error 日志级别过高
>
> **第八轮审查修正内容** (2处):
> 1. **P1-07 autoCompact 状态回写补全**: 原代码仅 `state.setMessages(cascadeResult.messages())`，未根据 `cascadeResult.autoCompactExecuted()` 更新 `state.autoCompactFailures`（circuit breaker 永远无法正确递进）。补充状态回写逻辑 + `isAutoCompactEnabled()` 检查 + CascadeResult 设计缺陷说明
> 2. **P1-06 L320 关闭日志保持 debug**: `"McpSseTransport closed"` 是一次性连接关闭事件（非高频心跳），降为 trace 会丢失诊断信息。删除 L320→trace 的错误建议，仅保留 L279（keepalive）降级
