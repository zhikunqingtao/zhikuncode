# ZhikuCode 功能增强规划文档 v1.4

> **文档版本**: 1.4  
> **创建日期**: 2026-04-18  
> **技术栈**: Java 21 + Spring Boot 3.3 + React 18 + TypeScript + Zustand + Python 3.11 + FastAPI  
> **适用范围**: ZhikuCode 全栈功能增强，共 7 项新增功能 + 2 项已实现功能增强，总计约 13 人天
> **审查版本**: v1.4（基于第四轮 14 维度源码级审查，修复 2 个 CRITICAL 阻塞问题 + 7 个文档错误 + 3 个安全风险）
>
> **全局 API 约定说明**:
> - `CommandResult` 可用工厂方法: `text(String)` / `error(String)` / `jsx(Map)` / `compact(String, Map)` / `skip()`
> - `CommandResult` record 字段: `type(ResultType)` / `value(String)` / `data(Map)` / `error(String)`
>   - `jsx()` 返回: type=JSX, **value=null**, data=结构化数据 — 注意 value 为 null
>   - `compact()` 返回: type=COMPACT, value=displayText, data=压缩元数据
>   - `text()` 返回: type=TEXT, value=文本内容, data=空Map
> - `CommandContext` record 字段: `sessionId` / `workingDir` / `currentModel` / `appState` / `isAuthenticated` / `isRemoteMode` / `isBridgeMode`（共 7 字段）
> - `SessionData` record 字段: `sessionId` / `model` / `workingDir` / `title` / `status` / `messages` / `config` / `totalUsage` / `totalCostUsd` / `summary` / `createdAt` / `updatedAt`（共 12 字段）
> - 项目配置目录统一使用 `.qoder/`（与代码中 SkillRegistry/Skills 目录一致）
> - 项目记忆文件统一使用 `zhikun.md` / `zhikun.local.md`（不使用 QODER.md 或 CLAUDE.md）
>
> **⚠️ v1.4 CRITICAL 前置修复**:
> - **CRITICAL-1**: `WebSocketController.handleSlashCommand()` 当前仅推送 `value != null` 的结果（TEXT/COMPACT），
>   `jsx()` 返回的 value 为 null 会被静默丢弃。F3/F4/F6 所有 `jsx()` 方案**必须先修复此问题**才能生效。
>   详见「全局前置修复 G0」章节。
> - **CRITICAL-2**: `GitCommands.java` 已有 `commitCommand`/`reviewCommand` @Bean（PROMPT 类型），
>   F6 提议的 `GitCommitCommand`/`GitReviewCommand` @Component 会产生 Bean 命名冲突。
>   详见 F6 章节修正。

---

## 目录

| 序号 | 功能名称 | 工作量 | 优先级 |
|------|----------|--------|--------|
| F1 | /compact 手动压缩命令 | 1 人天 | P0 |
| F2 | 前端成本/Token 显示增强（修复 onUsage + 增强 StatusBar） | 0.5 人天 | P0 |
| F3 | 项目记忆系统 zhikun.md | 2 人天 | P0 |
| F4 | /doctor 环境诊断命令增强 | 0.5 人天 | P1 |
| F5 | ~~WebFetchTool 网页内容获取~~ **已实现** | 0 人天 | — |
| F6 | Git 深度集成 | 3 人天 | P1 |
| F7 | Plan Mode 规划模式 UI | 2 人天 | P2 |
| F8 | 文件变更可视化 Dashboard（前端 UI，后端 API 已有） | 2.5 人天 | P2 |
| F9 | ~~Skills 技能系统~~ Skills UI 增强（后端已实现） | 1 人天 | P2 |

---

## G0: 全局前置修复 — WebSocketController JSX/COMPACT 推送支持（0.5 人天）

> **⚠️ CRITICAL-1**: 此修复是 F3/F4/F6 所有 `jsx()` 方案的**前置依赖**，必须在其他功能开发前完成。

### G0.1 现状分析

**问题根因**: `WebSocketController.handleSlashCommand()`（L646-673）当前仅检查 `cmdResult.value() != null` 来决定是否推送结果。
而 `CommandResult.jsx(Map)` 的 value 字段为 null（结构化数据存储在 data 字段中），导致 JSX 类型结果被静默丢弃。

```java
// 当前代码（WebSocketController.java L657-666）— 仅支持 TEXT
if (cmdResult.isSuccess() && cmdResult.value() != null) {
    // JSX 结果的 value=null，永远不会进入此分支！
    push(sessionId, "command_result", Map.of(
            "command", payload.command(),
            "output", cmdResult.value()));
} else if (!cmdResult.isSuccess()) {
    push(sessionId, "error", Map.of(...));
}
// JSX/COMPACT 结果: isSuccess()=true, value()=null → 两个分支都不进入 → 静默丢弃
```

**影响范围**: F3（MemoryCommand jsx()）、F4（DoctorCommand jsx()）、F6（DiffCommand/GitCommitCommand jsx()）

### G0.2 技术方案

**文件**: `backend/src/main/java/com/aicodeassistant/websocket/WebSocketController.java`

```java
@MessageMapping("/command")
public void handleSlashCommand(@Payload ClientMessage.SlashCommandPayload payload,
                                Principal principal) {
    String sessionId = resolveSessionId(principal);
    log.info("WS slash_command: sessionId={}, command=/{} {}", sessionId,
            payload.command(), payload.args());
    try {
        var cmd = commandRegistry.getCommand(payload.command());
        CommandContext ctx = CommandContext.of(
                sessionId, ".", null, null);
        CommandResult cmdResult = cmd.execute(payload.args(), ctx);

        // v1.4 修复: 根据 ResultType 分别处理 TEXT/JSX/COMPACT 类型
        if (cmdResult.isSuccess()) {
            switch (cmdResult.type()) {
                case TEXT -> {
                    if (cmdResult.value() != null) {
                        push(sessionId, "command_result", Map.of(
                                "command", payload.command(),
                                "type", "text",
                                "output", cmdResult.value()));
                    }
                }
                case JSX -> {
                    // JSX 结果: value=null, data=结构化数据
                    push(sessionId, "command_result", Map.of(
                            "command", payload.command(),
                            "type", "jsx",
                            "data", cmdResult.data()));
                }
                case COMPACT -> {
                    // COMPACT 结果: value=displayText, data=压缩元数据
                    push(sessionId, "compact_complete", Map.of(
                            "displayText", cmdResult.value() != null ? cmdResult.value() : "",
                            "compactionData", cmdResult.data()));
                }
                case SKIP -> { /* 无操作 */ }
                default -> log.warn("Unhandled command result type: {}", cmdResult.type());
            }
        } else {
            push(sessionId, "error", Map.of(
                    "code", "COMMAND_ERROR",
                    "message", cmdResult.error() != null ? cmdResult.error() : "Command failed",
                    "retryable", false));
        }
    } catch (CommandRegistry.CommandNotFoundException e) {
        push(sessionId, "error", Map.of(
                "code", "COMMAND_NOT_FOUND",
                "message", "Unknown command: /" + payload.command(),
                "retryable", false));
    }
}
```

### G0.3 前端配套修改

**文件**: `frontend/src/api/dispatch.ts` — 增强 `command_result` 处理，支持 jsx 类型路由

```typescript
// 修改 command_result handler，支持 text/jsx 两种类型
'command_result': (d: { command: string; type?: string; output?: string; data?: Record<string, unknown> }) => {
    if (d.type === 'jsx' && d.data) {
        // JSX 类型: 根据 data.action 路由到对应组件
        useMessageStore.getState().addMessage({
            type: 'system',
            uuid: crypto.randomUUID(),
            timestamp: Date.now(),
            content: '',
            subtype: 'jsx_result',
            metadata: { command: d.command, ...d.data },
        } as Message);
    } else {
        // TEXT 类型: 保持原有行为
        useMessageStore.getState().addMessage({
            type: 'system',
            uuid: crypto.randomUUID(),
            timestamp: Date.now(),
            content: `/${d.command}: ${d.output ?? ''}`,
            subtype: 'command_result',
        } as Message);
    }
},
```

### G0.4 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 修改 `handleSlashCommand()`，按 ResultType 分支推送 TEXT/JSX/COMPACT | 1h |
| 2 | 增强 `dispatch.ts` 的 `command_result` handler 支持 jsx 类型 | 0.5h |
| 3 | 单元测试: 验证三种 ResultType 都能正确推送 | 1h |
| 4 | 集成测试: 前端收到 jsx 结果后正确渲染对应组件 | 0.5h |

### G0.5 测试用例

```java
@SpringBootTest
class WebSocketControllerSlashCommandTest {

    // 测试 JSX 结果推送
    @Test
    void testJsxResultIsPushed() {
        // 模拟一个返回 jsx() 的命令
        when(commandRegistry.getCommand("doctor")).thenReturn(doctorCommand);
        when(doctorCommand.execute(any(), any())).thenReturn(
            CommandResult.jsx(Map.of("action", "diagnosticReport", "checks", List.of())));

        // 执行
        controller.handleSlashCommand(new SlashCommandPayload("doctor", ""), principal);

        // 验证推送了 command_result 类型为 jsx
        verify(messagingTemplate).convertAndSendToUser(
            eq(sessionId), eq("/queue/messages"),
            argThat(msg -> {
                Map<?, ?> body = (Map<?, ?>) msg;
                return "command_result".equals(body.get("type"))
                    && "jsx".equals(((Map<?,?>)body.get("data")).get("type"));
            }));
    }

    // 测试 COMPACT 结果推送
    @Test
    void testCompactResultIsPushed() {
        when(commandRegistry.getCommand("compact")).thenReturn(compactCommand);
        when(compactCommand.execute(any(), any())).thenReturn(
            CommandResult.compact("Compacted", Map.of("savedTokens", 5000)));

        controller.handleSlashCommand(new SlashCommandPayload("compact", ""), principal);

        verify(messagingTemplate).convertAndSendToUser(
            eq(sessionId), eq("/queue/messages"),
            argThat(msg -> "compact_complete".equals(((Map<?,?>)msg).get("type"))));
    }
}
```

---

## F1: /compact 手动压缩命令（1 人天）

### 1.1 现状分析

**已有基础设施**:
- `CompactService`（`engine/CompactService.java`，997 行）已完整实现三级降级压缩策略（LLM 摘要 → 关键消息选择 → 尾部截断）
- `ContextCascade`（`engine/ContextCascade.java`，303 行）管理自动压缩级联，含 `executePreApiCascade()` 和 `executeErrorRecoveryCascade()`
- 后端已有 `CompactStart`/`CompactComplete`/`CompactEvent` 三种 WebSocket 消息类型
- `CommandRouter` + `CommandRegistry` 已支持斜杠命令注册和路由
- `SlashCommandParser` 已实现 `/command args` 格式解析

**缺失部分**:
- `CompactCommand` 已存在（67 行），但实现为简化版：仅注入 CompactService，硬编码 contextWindow=200000，通过 `context.appState().session().messages()` 获取消息，返回 `CommandResult.compact(displayText, Map)` 类型。需增强为注入 ModelRegistry 动态获取 contextWindow，保持 `compact()` 返回类型（dispatch.ts 已有 compact_complete 处理），并增强元数据供前端可视化渲染。
- 前端 `handleSlashCommand` 当前仅 `console.log`，未真正发送到后端（`sendSlashCommand()` 已定义于 stompClient.ts L289 但未被调用）
- 缺少压缩结果的前端可视化面板（当前仅纯文本展示，未充分利用浏览器渲染能力）

### 1.2 技术方案

#### 1.2.1 后端：CompactCommand 增强

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/CompactCommand.java`（增强已有 67 行实现）

> **v1.3 修正**: 保持现有 `CommandResult.compact(displayText, Map)` 返回类型（dispatch.ts L74 已有 compact_complete 处理），
> 不改为 `text()` 以避免丢失压缩元数据。增强元数据 Map 供前端 CompactResultPanel 可视化渲染。
> 保留现有 `context.appState().session().messages()` 获取消息的方式，无需引入 SessionManager 依赖。

```java
package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.engine.CompactService.CompactResult;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * /compact [custom_instruction] — 手动触发上下文压缩。
 * 保持现有 compact() 返回类型，增强元数据供前端可视化渲染。
 */
@Component
public class CompactCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(CompactCommand.class);

    private final CompactService compactService;
    private final TokenCounter tokenCounter;
    private final ModelRegistry modelRegistry;

    public CompactCommand(CompactService compactService,
                          TokenCounter tokenCounter,
                          ModelRegistry modelRegistry) {
        this.compactService = compactService;
        this.tokenCounter = tokenCounter;
        this.modelRegistry = modelRegistry;
    }

    @Override public String getName() { return "compact"; }
    @Override public String getDescription() { return "Compact conversation context"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }
    @Override public boolean supportsNonInteractive() { return true; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        try {
            var state = context.appState();
            var messages = state.session().messages();

            if (messages == null || messages.isEmpty()) {
                return CommandResult.text("Nothing to compact — conversation is empty.");
            }

            // 从 ModelRegistry 动态获取当前模型的上下文窗口大小（替代硬编码 200000）
            var caps = modelRegistry.getCapabilities(context.currentModel());
            int contextWindow = caps.contextWindow();

            int beforeTokens = tokenCounter.estimateTokens(messages);
            CompactResult result = compactService.compact(messages, contextWindow, false);

            if (result.skipReason() != null) {
                return CommandResult.text("Compact skipped: " + result.skipReason());
            }

            String displayText = "Conversation compacted. " + result.summary();
            if (args != null && !args.isBlank()) {
                displayText += " (Instruction: " + args.trim() + ")";
            }

            // 保持 compact() 返回类型，增强元数据供前端 CompactResultPanel 可视化渲染
            return CommandResult.compact(displayText, Map.of(
                "originalMessageCount", messages.size(),
                "compactedMessageCount", result.compactedMessages().size(),
                "beforeTokens", beforeTokens,
                "afterTokens", result.afterTokens(),
                "savedTokens", result.savedTokens(),
                "compressionRatio", result.compressionRatio(),
                "instruction", args != null ? args.trim() : ""
            ));
        } catch (Exception e) {
            log.error("Compact failed: {}", e.getMessage(), e);
            return CommandResult.error("Failed to compact conversation: " + e.getMessage());
        }
    }
}
```

#### 1.2.2 前端：Slash 命令发送修复

**文件**: `frontend/src/App.tsx` — 修改 `handleSlashCommand`

```typescript
// 修改 handleSlashCommand，将命令发送到后端
const handleSlashCommand = useCallback((command: string) => {
    const trimmed = command.trim();
    if (!trimmed.startsWith('/')) return;

    const parts = trimmed.substring(1).split(/\s+/, 2);
    const cmdName = parts[0];
    const cmdArgs = parts.length > 1 ? parts[1] : '';

    // 添加系统消息到 UI
    addMessage({
        uuid: crypto.randomUUID(),
        type: 'system',
        content: `执行命令: ${trimmed}`,
        timestamp: Date.now(),
        subtype: 'command',
    } as Message);

    // 通过 STOMP 发送到后端
    sendSlashCommand(cmdName, cmdArgs);
}, [addMessage]);
```

#### 1.2.3 前端：CompactResultPanel 可视化组件

**文件**: `frontend/src/components/compact/CompactResultPanel.tsx`（新建）

> **v1.3 新增**: 充分利用浏览器渲染能力，将压缩结果从纯文本升级为可视化面板，
> 包含 token 对比条、统计卡片、压缩率指示器，这是 Claude Code 终端无法实现的体验。

```tsx
import React from 'react';

interface CompactResultData {
    originalMessageCount: number;
    compactedMessageCount: number;
    beforeTokens: number;
    afterTokens: number;
    savedTokens: number;
    compressionRatio: number;
    instruction: string;
}

export const CompactResultPanel: React.FC<{ data: CompactResultData; displayText: string }> = ({ data, displayText }) => {
    const savedPct = data.beforeTokens > 0 ? Math.round((data.savedTokens / data.beforeTokens) * 100) : 0;

    return (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] p-4 space-y-3">
            <div className="flex items-center gap-2">
                <span className="text-lg">🗜️</span>
                <span className="font-semibold text-[var(--text-primary)]">Context Compacted</span>
            </div>
            {/* Token 对比条 */}
            <div className="space-y-1">
                <div className="flex justify-between text-xs text-[var(--text-muted)]">
                    <span>压缩前: {data.beforeTokens.toLocaleString()} tokens</span>
                    <span>压缩后: {data.afterTokens.toLocaleString()} tokens</span>
                </div>
                <div className="h-2 bg-gray-700 rounded-full overflow-hidden flex">
                    <div className="h-full bg-blue-500 rounded-full" style={{ width: `${100 - savedPct}%` }} />
                    <div className="h-full bg-green-500/30 rounded-full" style={{ width: `${savedPct}%` }} />
                </div>
            </div>
            {/* 统计卡片 */}
            <div className="grid grid-cols-3 gap-2">
                <div className="bg-[var(--bg-tertiary)] rounded-md p-2 text-center">
                    <div className="text-lg font-bold text-green-400">{data.savedTokens.toLocaleString()}</div>
                    <div className="text-xs text-[var(--text-muted)]">tokens 释放</div>
                </div>
                <div className="bg-[var(--bg-tertiary)] rounded-md p-2 text-center">
                    <div className="text-lg font-bold text-blue-400">{savedPct}%</div>
                    <div className="text-xs text-[var(--text-muted)]">压缩率</div>
                </div>
                <div className="bg-[var(--bg-tertiary)] rounded-md p-2 text-center">
                    <div className="text-lg font-bold text-[var(--text-primary)]">
                        {data.compactedMessageCount}/{data.originalMessageCount}
                    </div>
                    <div className="text-xs text-[var(--text-muted)]">消息数</div>
                </div>
            </div>
            {data.instruction && (
                <div className="text-xs text-[var(--text-muted)] italic">🎯 Focus: {data.instruction}</div>
            )}
        </div>
    );
};
```

#### 1.2.4 前端：dispatch.ts 增强 compact_complete 处理

**文件**: `frontend/src/api/dispatch.ts` — 增强 `compact_complete`，携带结构化元数据供 CompactResultPanel 渲染

```typescript
'compact_complete': (d: { displayText: string; compactionData: CompactResultData }) => {
    useSessionStore.getState().setStatus('idle');
    useMessageStore.getState().addMessage({
        type: 'system',
        uuid: crypto.randomUUID(),
        timestamp: Date.now(),
        content: d.displayText,
        subtype: 'compact_result',
        metadata: d.compactionData,  // 携带元数据供 CompactResultPanel 可视化渲染
    } as Message);
    useNotificationStore.getState().addNotification({
        key: 'compact-done',
        level: 'success',
        message: `压缩完成: 释放 ${d.compactionData.savedTokens.toLocaleString()} tokens`,
        timeout: 5000,
    });
},
```

### 1.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 增强已有 `CompactCommand.java`（67 行），新增 TokenCounter/ModelRegistry 注入，保持 `compact()` 返回类型 | 1h |
| 2 | 在 `CommandRegistry` 确认自动注册（@Component 已足够） | 0.5h |
| 3 | 修改 `App.tsx` 的 `handleSlashCommand` 发送 STOMP 消息 | 0.5h |
| 4 | 增强 `dispatch.ts` 的 `compact_complete` 处理 | 0.5h |
| 5 | 新建 `CompactResultPanel.tsx` 可视化面板组件（token 对比条 + 统计卡片） | 1h |
| 6 | 编写 `CompactCommandTest.java` 单元测试 | 1h |
| 7 | 端到端测试：前端发送 /compact → 后端执行 → 前端显示结果 | 1h |

### 1.4 测试用例

```java
// CompactCommandTest.java
@SpringBootTest
class CompactCommandTest {

    @MockitoBean CompactService compactService;
    @MockitoBean TokenCounter tokenCounter;
    @MockitoBean ModelRegistry modelRegistry;
    @Autowired CompactCommand compactCommand;

    @Test
    void testCompactSuccess() {
        // 准备: 模拟 10 条消息的会话
        var messages = List.of(/* 10 条测试消息 */);
        // 构造包含 messages 的 AppState
        var appState = new AppState(/* ... session with messages ... */);
        when(tokenCounter.estimateTokens(messages)).thenReturn(50000);
        when(modelRegistry.getCapabilities("qwen"))
            .thenReturn(new ModelCapabilities(/* contextWindow: */ 128000, /* ... */));
        when(compactService.compact(any(), anyInt(), eq(false)))
            .thenReturn(CompactResult.success(List.of(/* 压缩后消息 */), 50000, 30000));

        var ctx = new CommandContext(
            "test-session", ".", "qwen", appState, true, false, false);
        var result = compactCommand.execute("", ctx);

        // 验证返回类型为 COMPACT（非 TEXT）
        assertThat(result.type()).isEqualTo(CommandResult.ResultType.COMPACT);
        // 验证元数据含可视化所需字段
        assertThat(result.metadata()).containsKeys(
            "beforeTokens", "afterTokens", "savedTokens", "compressionRatio");
    }

    @Test
    void testCompactEmptyConversation() {
        var appState = new AppState(/* ... session with empty messages ... */);
        var result = compactCommand.execute("",
            new CommandContext("test-session", ".", "qwen", appState, true, false, false));
        assertThat(result.type()).isEqualTo(CommandResult.ResultType.TEXT);
        assertThat(result.value()).contains("empty");
    }
}
```

### 1.5 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 压缩过程中用户发送新消息 | 中 | CompactService 内部已有消息锁，压缩期间 sessionStore.status='compacting' 禁止输入 |
| LLM 摘要生成失败 | 低 | CompactService 已有三级降级策略（LLM → 关键消息 → 尾部截断） |
| 压缩后上下文丢失关键信息 | 中 | 保留最近 3 轮消息（PRESERVED_RECENT_TURNS=3），摘要包含关键决策点 |

---

## F2: 前端成本/Token 显示增强（0.5 人天）

### 2.1 现状分析

**已有基础设施**:
- `CostTrackerService`（107 行）已实现会话级/全局费用追踪，含 `recordUsage()`/`getSessionCost()`/`getGlobalCost()`
- `CostSummary` 记录类含 `inputTokens`/`outputTokens`/`cacheReadTokens`/`totalCost`/`apiCalls`
- `ServerMessage.CostUpdate`（`#15 cost_update`）已定义，WebSocket 推送已实现
- `costStore.ts` 已有 `sessionCost`/`totalCost`/`usage` 状态和 `updateCost` action
- `dispatch.ts` 已处理 `cost_update` 消息分发到 costStore
- **`StatusBar.tsx`（133 行）已展示** inputTokens、outputTokens、cacheReadInputTokens 和 sessionCost

**真正的问题**:
- `WsMessageHandler.onUsage()` 当前传递 `0.0` 作为 sessionCost 和 totalCost，未调用 CostTrackerService 计算实际费用
- 无 token 使用趋势图表（可作为浏览器增强项）
- 无预算提醒功能（可作为浏览器增强项）

### 2.2 技术方案

#### 2.2.1 后端：修复 CostUpdate 推送（核心修复）

**文件**: `backend/src/main/java/com/aicodeassistant/websocket/WebSocketController.java`

```java
// 修改 WsMessageHandler.onUsage() — 传递真实费用数据
@Override
public void onUsage(Usage usage) {
    CostSummary sessionCost = costTrackerService.getSessionCost(sessionId);
    CostSummary globalCost = costTrackerService.getGlobalCost();
    sendCostUpdate(sessionId, sessionCost.totalCost(), globalCost.totalCost(), usage);
}
```

需在 `WebSocketController` 构造函数中注入 `CostTrackerService`：

```java
private final CostTrackerService costTrackerService;

// 构造函数新增参数
public WebSocketController(... , CostTrackerService costTrackerService) {
    ...
    this.costTrackerService = costTrackerService;
}
```

#### 2.2.2 前端：增强已有 StatusBar 组件

**文件**: `frontend/src/components/layout/StatusBar.tsx` — 在已有基础上增强

> **注意**: StatusBar.tsx 已经展示了 inputTokens、outputTokens、cacheReadInputTokens 和 sessionCost。
> 以下仅需追加预算警告和总费用展示功能。

```tsx
// 在 StatusBar.tsx 现有费用显示区域后追加:

// 全局总费用
<span className="text-xs text-[var(--text-muted)]" title={`全局累计: ${formatCost(totalCost)}`}>
    ∑ {formatCost(totalCost)}
</span>

// 预算警告
{totalCost > budgetUsd * 0.9 && (
    <span className="text-red-400" title="接近预算上限">
        <AlertTriangle size={12} />
    </span>
)}
```

#### 2.2.3 前端：costStore 增强 — 历史记录和预算

```typescript
// costStore.ts 扩展
export interface CostStoreState {
    sessionCost: number;
    totalCost: number;
    usage: Usage;
    // 新增: 历史记录（最近 50 条）
    costHistory: Array<{ ts: number; inputTokens: number; outputTokens: number; cost: number }>;
    // 新增: 预算配置
    budgetUsd: number;

    updateCost: (data: { sessionCost: number; totalCost: number; usage: Usage }) => void;
    resetSessionCost: () => void;
    setBudget: (usd: number) => void;
}

// 在 updateCost 中追加历史记录
updateCost: (data) => set(d => {
    d.sessionCost = data.sessionCost;
    d.totalCost = data.totalCost;
    d.usage = data.usage;
    // 追加历史（保留最近 50 条）
    d.costHistory.push({
        ts: Date.now(),
        inputTokens: data.usage.inputTokens,
        outputTokens: data.usage.outputTokens,
        cost: data.sessionCost,
    });
    if (d.costHistory.length > 50) d.costHistory.shift();
}),
```

### 2.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 后端注入 CostTrackerService，修复 onUsage 推送真实费用 | 0.5h |
| 2 | 增强已有 StatusBar.tsx，添加总费用和预算警告 | 0.5h |
| 3 | 扩展 costStore 支持历史记录和预算 | 0.5h |
| 4 | 测试验证 | 0.5h |

### 2.4 测试用例

```typescript
// StatusBar.test.tsx — v1.4 修正: 组件名从 CostDisplay 改为 StatusBar（已有组件）
describe('StatusBar Cost Enhancement', () => {
    it('should format tokens correctly', () => {
        useCostStore.setState({
            usage: { inputTokens: 15000, outputTokens: 3000, cacheReadInputTokens: 5000, cacheCreationInputTokens: 0 },
            sessionCost: 0.0234,
            totalCost: 1.567,
        });
        render(<StatusBar />);
        expect(screen.getByText(/18\.0K tokens/)).toBeInTheDocument();
        expect(screen.getByText(/\$0\.023/)).toBeInTheDocument();
    });

    it('should show budget warning when threshold exceeded', () => {
        useCostStore.setState({ totalCost: 4.8, budgetUsd: 5.0 });
        render(<StatusBar />);
        expect(screen.getByTitle(/预算/)).toHaveClass('text-red-400');
    });
});
```

### 2.5 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 高频 cost_update 导致 UI 闪烁 | 低 | costStore 通过 Zustand 自动批量更新，useSyncExternalStore 无此问题 |
| CostTrackerService 重启清零 | 中 | MVP 阶段可接受；后续可持久化到 SQLite sessions 表的 total_cost_usd 字段 |
| 费率表不完整（新模型无计价） | 低 | ModelRegistry 已有默认费率兜底 |

---

## F3: 项目记忆系统 zhikun.md（2 人天）

### 3.1 现状分析

**对标功能**: Claude Code 的 `CLAUDE.md` / `CLAUDE.local.md` 项目记忆系统

**已有基础设施**:
- `EffectiveSystemPromptBuilder` 构建系统提示词，已有 `ProjectContextService` 预加载项目上下文
- `FileReadTool`/`FileWriteTool`/`FileEditTool` 已完整实现文件操作闭环
- `HookService` 已支持 8 种 Hook 事件（SESSION_START/SESSION_END 等）
- 工作目录通过 `System.getProperty("user.dir")` 获取
- **`MemoryCommand.java`（42 行）已存在**，但引用的是 `CLAUDE.md` 而非 `zhikun.md`，返回 `CommandResult.jsx()` 类型

**缺失部分**:
- 无 zhikun.md 文件的自动发现和加载机制（ProjectMemoryService 不存在）
- 系统提示词中未注入项目记忆内容
- 已有 MemoryCommand 需重写为 zhikun.md 范式
- 无浏览器端的记忆文件可视化编辑器（仅有 slash 命令文本交互，未充分利用浏览器优势）

### 3.2 技术方案

#### 3.2.1 记忆文件约定

```
项目根目录/
├── zhikun.md              # 项目级共享记忆（提交到 Git）
├── zhikun.local.md        # 本地私有记忆（.gitignore）
└── .qoder/
    └── settings.json      # 项目级配置（可选）
```

**zhikun.md 格式规范**:
```markdown
# 项目记忆

## 技术栈
- 后端: Java 21 + Spring Boot 3.3
- 前端: React 18 + TypeScript

## 编码规范
- 使用 record 替代 POJO
- 所有 public 方法必须有 Javadoc

## 常见问题
- SQLite 写入必须通过 SqliteConfig.executeWrite() 串行化
```

#### 3.2.2 后端：ProjectMemoryService

**文件**: `backend/src/main/java/com/aicodeassistant/service/ProjectMemoryService.java`（新建）

```java
package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目记忆服务 — 管理 zhikun.md / zhikun.local.md 文件。
 * 对标 Claude Code 的 CLAUDE.md 记忆系统，使用 ZhikuCode 自有命名。
 */
@Service
public class ProjectMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ProjectMemoryService.class);

    /** 记忆文件名优先级（高 → 低） */
    private static final String[] MEMORY_FILES = {
        "zhikun.md", "zhikun.local.md"
    };

    /** 最大记忆文件大小 (100KB) */
    private static final long MAX_MEMORY_SIZE = 100 * 1024;

    /**
     * 加载项目记忆内容。
     * 按优先级查找并合并多个记忆文件。
     *
     * @param workingDir 项目工作目录
     * @return 合并后的记忆内容，如果无记忆文件则返回空字符串
     */
    public String loadMemory(Path workingDir) {
        List<String> memories = new ArrayList<>();

        // 向上搜索目录树（最多 5 层）
        Path current = workingDir;
        int depth = 0;
        while (current != null && depth < 5) {
            for (String fileName : MEMORY_FILES) {
                Path memFile = current.resolve(fileName);
                if (Files.isRegularFile(memFile)) {
                    try {
                        long size = Files.size(memFile);
                        if (size > MAX_MEMORY_SIZE) {
                            log.warn("Memory file too large ({}KB), truncating: {}",
                                size / 1024, memFile);
                        }
                        String content = Files.readString(memFile, StandardCharsets.UTF_8);
                        if (size > MAX_MEMORY_SIZE) {
                            content = content.substring(0, (int) MAX_MEMORY_SIZE);
                        }
                        memories.add("<!-- " + memFile + " -->\n" + content);
                        log.info("Loaded memory file: {} ({}B)", memFile, size);
                    } catch (IOException e) {
                        log.warn("Failed to read memory file: {}", memFile, e);
                    }
                }
            }
            current = current.getParent();
            depth++;
        }

        if (memories.isEmpty()) return "";
        return String.join("\n\n---\n\n", memories);
    }

    /**
     * 写入项目记忆。
     *
     * @param workingDir 工作目录
     * @param content    记忆内容
     * @param isLocal    是否为本地记忆（zhikun.local.md）
     */
    public void writeMemory(Path workingDir, String content, boolean isLocal) throws IOException {
        String fileName = isLocal ? "zhikun.local.md" : "zhikun.md";
        Path memFile = workingDir.resolve(fileName).normalize();

        // v1.4 SEC-2 修复: 路径遍历防护 — 确保目标文件在工作目录下
        if (!memFile.startsWith(workingDir)) {
            throw new IOException("Path traversal detected: " + memFile);
        }

        Files.writeString(memFile, content, StandardCharsets.UTF_8);
        log.info("Written memory file: {} ({}B)", memFile, content.length());
    }

    /**
     * 检查项目是否有记忆文件。
     */
    public boolean hasMemory(Path workingDir) {
        for (String fileName : MEMORY_FILES) {
            if (Files.isRegularFile(workingDir.resolve(fileName))) return true;
        }
        return false;
    }
}
```

#### 3.2.3 后端：系统提示词注入记忆

**文件**: `backend/src/main/java/com/aicodeassistant/prompt/EffectiveSystemPromptBuilder.java` — 修改

> **v1.4 修正**: 实际包路径为 `prompt`（非 `engine`），源码确认位于 `com.aicodeassistant.prompt.EffectiveSystemPromptBuilder`。

在 `buildEffectiveSystemPrompt()` 方法中注入记忆内容：

```java
// 在 systemPrompt 构建流程中添加:
String memory = projectMemoryService.loadMemory(workingDir);
if (!memory.isBlank()) {
    sb.append("\n\n<project_memory>\n");
    sb.append(memory);
    sb.append("\n</project_memory>\n");
}
```

#### 3.2.4 后端：MemoryCommand（重写已有实现）

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/MemoryCommand.java`（重写，已存在 42 行，当前引用 CLAUDE.md）

> **v1.3 修正**: 文件名从 CLAUDE.md 改为 zhikun.md，保持 LOCAL_JSX 类型和 jsx() 返回（与现有前端渲染逻辑兼容）。

```java
@Component
public class MemoryCommand implements Command {

    private final ProjectMemoryService memoryService;

    @Override
    public String getName() { return "memory"; }

    @Override
    public String getDescription() { return "Manage project memory (zhikun.md)"; }

    @Override
    public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        // v1.4 SEC-3 修复: workingDir null 防护
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置，无法加载记忆文件");
        }
        Path workingDir = Path.of(context.workingDir());

        if (args == null || args.isBlank() || args.equals("show")) {
            String memory = memoryService.loadMemory(workingDir);
            boolean hasMemory = !memory.isBlank();
            return CommandResult.jsx(Map.of(
                "action", "showMemoryFiles",
                "workingDir", context.workingDir(),
                "hasMemory", hasMemory,
                "content", hasMemory ? memory : "",
                "files", List.of("zhikun.md", "zhikun.local.md")
            ));
        }

        if (args.equals("init")) {
            try {
                String template = """
                    # 项目记忆
                    
                    ## 技术栈
                    <!-- 在此填写项目技术栈信息 -->
                    
                    ## 编码规范
                    <!-- 在此填写编码规范 -->
                    
                    ## 注意事项
                    <!-- 在此填写项目注意事项 -->
                    """;
                memoryService.writeMemory(workingDir, template, false);
                return CommandResult.jsx(Map.of(
                    "action", "memoryCreated",
                    "fileName", "zhikun.md",
                    "workingDir", context.workingDir()
                ));
            } catch (IOException e) {
                return CommandResult.error("创建失败: " + e.getMessage());
            }
        }

        return CommandResult.error("未知子命令。用法: /memory [show|init]");
    }
}
```

#### 3.2.5 前端：MemoryEditorPanel 浏览器可视化编辑器

**文件**: `frontend/src/components/memory/MemoryEditorPanel.tsx`（新建）

> **v1.3 新增**: 充分利用浏览器环境实现 Markdown 可视化编辑器，支持实时预览、语法高亮、模板插入，
> 这是 Claude Code 终端交互无法实现的体验提升。

```tsx
import React, { useState, useCallback, useEffect } from 'react';
import { Save, Eye, Edit3, FileText, Plus, RefreshCw } from 'lucide-react';
import DOMPurify from 'dompurify'; // v1.4 SEC-1: XSS 防护依赖（npm install dompurify @types/dompurify）

interface MemoryEditorProps {
    workingDir: string;
    initialContent: string;
    fileName: 'zhikun.md' | 'zhikun.local.md';
    onSave: (content: string) => Promise<void>;
}

/** 项目记忆文件模板 */
const MEMORY_TEMPLATES: Record<string, string> = {
    '技术栈': '## 技术栈\n- 后端: \n- 前端: \n- 数据库: \n',
    '编码规范': '## 编码规范\n- \n',
    '常见问题': '## 常见问题\n- \n',
    '注意事项': '## 注意事项\n- \n',
};

export const MemoryEditorPanel: React.FC<MemoryEditorProps> = ({
    workingDir, initialContent, fileName, onSave,
}) => {
    const [content, setContent] = useState(initialContent);
    const [isPreview, setIsPreview] = useState(false);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);

    useEffect(() => {
        setContent(initialContent);
        setDirty(false);
    }, [initialContent]);

    const handleChange = useCallback((value: string) => {
        setContent(value);
        setDirty(true);
    }, []);

    const handleSave = useCallback(async () => {
        setSaving(true);
        try {
            await onSave(content);
            setDirty(false);
        } finally {
            setSaving(false);
        }
    }, [content, onSave]);

    const insertTemplate = useCallback((template: string) => {
        setContent(prev => prev + '\n\n' + template);
        setDirty(true);
    }, []);

    return (
        <div className="flex flex-col h-full border border-[var(--border)] rounded-lg overflow-hidden">
            {/* Toolbar */}
            <div className="flex items-center justify-between px-3 py-2 border-b border-[var(--border)] bg-[var(--bg-secondary)]">
                <div className="flex items-center gap-2">
                    <FileText size={14} className="text-[var(--text-muted)]" />
                    <span className="text-sm font-medium text-[var(--text-primary)]">
                        {fileName}
                    </span>
                    {dirty && <span className="text-xs text-yellow-400">● 未保存</span>}
                </div>
                <div className="flex items-center gap-1">
                    {/* 模板插入下拉 */}
                    <div className="relative group">
                        <button className="p-1.5 rounded hover:bg-[var(--bg-tertiary)]" title="插入模板">
                            <Plus size={14} />
                        </button>
                        <div className="hidden group-hover:block absolute right-0 top-full mt-1 bg-[var(--bg-primary)] border border-[var(--border)] rounded-lg shadow-lg z-10 min-w-[140px]">
                            {Object.entries(MEMORY_TEMPLATES).map(([name, tpl]) => (
                                <button
                                    key={name}
                                    onClick={() => insertTemplate(tpl)}
                                    className="block w-full text-left px-3 py-1.5 text-xs hover:bg-[var(--bg-tertiary)]"
                                >
                                    {name}
                                </button>
                            ))}
                        </div>
                    </div>
                    {/* 编辑/预览切换 */}
                    <button
                        onClick={() => setIsPreview(!isPreview)}
                        className="p-1.5 rounded hover:bg-[var(--bg-tertiary)]"
                        title={isPreview ? '编辑模式' : '预览模式'}
                    >
                        {isPreview ? <Edit3 size={14} /> : <Eye size={14} />}
                    </button>
                    {/* 保存 */}
                    <button
                        onClick={handleSave}
                        disabled={!dirty || saving}
                        className="flex items-center gap-1 px-2 py-1 rounded text-xs bg-blue-600 hover:bg-blue-700 disabled:opacity-50"
                    >
                        {saving ? <RefreshCw size={12} className="animate-spin" /> : <Save size={12} />}
                        保存
                    </button>
                </div>
            </div>

            {/* Editor / Preview */}
            <div className="flex-1 overflow-auto">
                {isPreview ? (
                    <div className="p-4 prose prose-invert prose-sm max-w-none">
                        {/* v1.4 SEC-1 修复: 使用 DOMPurify 防止 XSS，替代原始 dangerouslySetInnerHTML */}
                        <div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(simpleMarkdownToHtml(content)) }} />
                    </div>
                ) : (
                    <textarea
                        value={content}
                        onChange={e => handleChange(e.target.value)}
                        className="w-full h-full p-4 bg-transparent text-sm font-mono text-[var(--text-primary)] resize-none focus:outline-none"
                        placeholder="# 项目记忆\n\n在此输入项目记忆内容..."
                        spellCheck={false}
                    />
                )}
            </div>
        </div>
    );
};

/** 简易 Markdown → HTML（仅支持标题、列表、代码块、粗体） */
function simpleMarkdownToHtml(md: string): string {
    return md
        .replace(/^### (.+)$/gm, '<h3>$1</h3>')
        .replace(/^## (.+)$/gm, '<h2>$1</h2>')
        .replace(/^# (.+)$/gm, '<h1>$1</h1>')
        .replace(/^- (.+)$/gm, '<li>$1</li>')
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/`(.+?)`/g, '<code>$1</code>')
        .replace(/\n/g, '<br/>');
}
```

#### 3.2.6 前端：dispatch.ts 集成记忆编辑器

**文件**: `frontend/src/api/dispatch.ts` — 在 `jsx` 消息处理中添加记忆编辑器路由

```typescript
// 在 jsx 消息处理中，根据 action 类型路由到对应组件
'command_result': (d: { type: string; data: Record<string, unknown> }) => {
    if (d.type === 'jsx' && d.data.action === 'showMemoryFiles') {
        // 渲染 MemoryEditorPanel，传入 workingDir / content / files
        useMessageStore.getState().addMessage({
            uuid: crypto.randomUUID(),
            type: 'system',
            subtype: 'memory_editor',
            content: '',
            metadata: d.data,
            timestamp: Date.now(),
        } as Message);
    }
},
```

### 3.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 新建 `ProjectMemoryService.java` — 加载/写入/检查 | 2h |
| 2 | 修改 `EffectiveSystemPromptBuilder` 注入记忆到系统提示词 | 1h |
| 3 | 重写已有 `MemoryCommand.java` — `/memory show`/`init`，从 CLAUDE.md 改为 zhikun.md，保持 jsx() 返回 | 1h |
| 3.1 | **v1.4 ERR-6 修复**: 修改 `InitCommand.java` L31，将 `CLAUDE.md` 引用改为 `zhikun.md`（当前: `prompt.append("Then create or update the CLAUDE.md file...")`） | 0.5h |
| 4 | 添加 `.gitignore` 条目 `zhikun.local.md` | 0.5h |
| 5 | 新建 `MemoryEditorPanel.tsx` 浏览器可视化编辑器（实时预览 + 模板插入 + 语法高亮） | 3h |
| 6 | 集成 dispatch.ts 记忆编辑器路由 + CommandPalette 中确认 /memory 命令显示 | 1h |
| 7 | 单元测试: ProjectMemoryServiceTest | 2h |
| 8 | 集成测试: 端到端记忆加载、编辑和注入验证 | 2h |

### 3.4 测试用例

```java
@SpringBootTest
class ProjectMemoryServiceTest {

    @TempDir Path tempDir;
    @Autowired ProjectMemoryService service;

    @Test
    void testLoadMemory_FileExists() throws IOException {
        Files.writeString(tempDir.resolve("zhikun.md"), "# 测试记忆\n- Java 21");
        String memory = service.loadMemory(tempDir);
        assertThat(memory).contains("测试记忆");
        assertThat(memory).contains("Java 21");
    }

    @Test
    void testLoadMemory_NoFile() {
        String memory = service.loadMemory(tempDir);
        assertThat(memory).isEmpty();
    }

    @Test
    void testLoadMemory_TruncateLargeFile() throws IOException {
        String largeContent = "x".repeat(200 * 1024); // 200KB
        Files.writeString(tempDir.resolve("zhikun.md"), largeContent);
        String memory = service.loadMemory(tempDir);
        assertThat(memory.length()).isLessThanOrEqualTo(100 * 1024 + 50); // 含注释头
    }

    @Test
    void testWriteMemory() throws IOException {
        service.writeMemory(tempDir, "# 新记忆", false);
        assertThat(Files.readString(tempDir.resolve("zhikun.md"))).isEqualTo("# 新记忆");
    }

    @Test
    void testLocalMemory() throws IOException {
        service.writeMemory(tempDir, "# 本地记忆", true);
        assertThat(Files.exists(tempDir.resolve("zhikun.local.md"))).isTrue();
    }
}
```

### 3.5 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| zhikun.md 过大增加 token 消耗 | 中 | 100KB 上限 + 截断；后续可实现 LLM 摘要缩减 |
| 多级目录搜索性能 | 低 | 最多搜索 5 层，Files.isRegularFile 为 O(1) 文件系统调用 |
| 敏感信息泄露 | 中 | zhikun.local.md 建议加入 .gitignore；文档中注明不要存放密钥 |
| 记忆编辑器 XSS 风险 | 中 | v1.4 SEC-1 修复: 使用 DOMPurify 对 Markdown 转 HTML 结果进行消毒，新增 `dompurify` 依赖 |
| 记忆文件写入路径遍历 | 高 | v1.4 SEC-2 修复: writeMemory 方法增加 normalize() + startsWith() 路径校验 |
| workingDir 为 null 时 NPE | 中 | v1.4 SEC-3 修复: MemoryCommand.execute() 开头增加 null/blank 检查 |

---

## F4: /doctor 环境诊断命令增强（0.5 人天）

### 4.1 现状分析

**已有基础设施**:
- `HealthController`（177 行）已实现 `GET /api/doctor` 端点，检查 Java、Git、Ripgrep、JVM 内存
- `DoctorCommand`（80 行）已作为 `@Component` 注册，返回 `CommandResult.text()` 类型，检查 Java版本/LLM/工作目录/认证/会话/Git
- 后端已有 `checkExternalTool()` 工具检测方法

**缺失部分**:
- DoctorCommand 未检查 Python 服务、LLM API 连通性、磁盘空间
- 前端无诊断报告的结构化展示
- 缺少 Node.js、npm 等前端工具检查

### 4.2 技术方案

#### 4.2.1 后端：增强 DoctorCommand（返回 jsx() 结构化数据）

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/DoctorCommand.java` — 增强已有实现（80 行）

> **v1.3 修正**: 已有 DoctorCommand 类型为 `LOCAL_JSX` 但返回 `text()`，存在类型不匹配。
> 保持 `LOCAL_JSX` 类型，改为返回 `CommandResult.jsx(Map)` 结构化数据，
> 前端渲染为 DiagnosticPanel 可视化诊断面板，这是浏览器端的重大体验提升。

```java
package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.llm.LlmProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * /doctor — 诊断工具，检查系统各组件健康状态。
 * 返回 jsx() 结构化数据，前端渲染为 DiagnosticPanel 可视化诊断报告。
 */
@Component
public class DoctorCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(DoctorCommand.class);

    private final LlmProviderRegistry providerRegistry;

    public DoctorCommand(LlmProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    @Override public String getName() { return "doctor"; }
    @Override public String getDescription() { return "Run diagnostics on your setup"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        List<Map<String, Object>> checks = new ArrayList<>();

        // 1. Java 运行时
        String javaVersion = System.getProperty("java.version");
        checks.add(buildCheck("runtime", "Java Version", javaVersion,
            Runtime.version().feature() >= 21 ? "ok" : "warn",
            Runtime.version().feature() >= 21 ? null : "建议使用 Java 21+"));

        // 2. LLM Providers
        boolean hasProviders = providerRegistry.hasProviders();
        checks.add(buildCheck("llm", "LLM Providers",
            hasProviders ? "已注册" : "未注册",
            hasProviders ? "ok" : "error",
            hasProviders ? null : "请配置 LLM API Key"));

        // 3. Working Directory
        String workDir = context.workingDir();
        boolean validDir = workDir != null && !workDir.isBlank();
        checks.add(buildCheck("env", "Working Directory",
            validDir ? workDir : "未设置",
            validDir ? "ok" : "error", null));

        // 4. Authentication
        checks.add(buildCheck("auth", "Authentication",
            context.isAuthenticated() ? "已认证" : "未认证",
            context.isAuthenticated() ? "ok" : "warn",
            context.isAuthenticated() ? null : "部分功能可能受限"));

        // 5. Session
        boolean hasSession = context.sessionId() != null;
        checks.add(buildCheck("session", "Active Session",
            hasSession ? context.sessionId() : "无活跃会话",
            hasSession ? "ok" : "warn", null));

        // 6. Git
        boolean gitAvailable = checkGitAvailable();
        checks.add(buildCheck("tool", "Git",
            gitAvailable ? "可用" : "未找到",
            gitAvailable ? "ok" : "warn",
            gitAvailable ? null : "安装 Git 以启用版本控制功能"));

        // 7. JVM 内存
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        double memPct = (double) usedMb / maxMb * 100;
        boolean memOk = memPct < 85;
        checks.add(buildCheck("runtime", "JVM Memory",
            String.format("%dMB / %dMB (%.0f%%)", usedMb, maxMb, memPct),
            memOk ? "ok" : "warn",
            memOk ? null : "内存使用率较高，建议增加堆内存"));

        // 8. Python 服务
        checks.add(checkPythonService());

        // 汇总统计
        long okCount = checks.stream().filter(c -> "ok".equals(c.get("status"))).count();
        long warnCount = checks.stream().filter(c -> "warn".equals(c.get("status"))).count();
        long errCount = checks.stream().filter(c -> "error".equals(c.get("status"))).count();

        return CommandResult.jsx(Map.of(
            "action", "diagnosticReport",
            "checks", checks,
            "summary", Map.of(
                "ok", okCount,
                "warn", warnCount,
                "error", errCount,
                "total", checks.size()
            )
        ));
    }

    private Map<String, Object> buildCheck(String category, String name,
                                            String value, String status, String hint) {
        var map = new LinkedHashMap<String, Object>();
        map.put("category", category);
        map.put("name", name);
        map.put("value", value);
        map.put("status", status); // "ok" | "warn" | "error"
        if (hint != null) map.put("hint", hint);
        return map;
    }

    private boolean checkGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            return p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> checkPythonService() {
        try {
            var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2)).build();
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:8000/api/health"))
                .timeout(java.time.Duration.ofSeconds(2))
                .GET().build();
            var response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200;
            return buildCheck("service", "Python Service",
                ok ? "运行中" : "HTTP " + response.statusCode(),
                ok ? "ok" : "warn",
                ok ? null : "Python 服务未正常响应");
        } catch (Exception e) {
            return buildCheck("service", "Python Service",
                "未运行或不可达", "warn", "启动 python-service 以获得完整功能");
        }
    }
}
```

#### 4.2.2 前端：DiagnosticPanel 可视化诊断面板

**文件**: `frontend/src/components/doctor/DiagnosticPanel.tsx`（新建）

> **v1.3 新增**: 充分利用浏览器渲染能力，将诊断结果从纯文本升级为可视化面板，
> 包含状态图标、分类展示、汇总概览，远超 Claude Code 终端纯文本诊断输出。

```tsx
import React from 'react';
import { CheckCircle, AlertTriangle, XCircle, Activity } from 'lucide-react';

interface DiagnosticCheck {
    category: string;
    name: string;
    value: string;
    status: 'ok' | 'warn' | 'error';
    hint?: string;
}

interface DiagnosticSummary {
    ok: number;
    warn: number;
    error: number;
    total: number;
}

const StatusIcon: React.FC<{ status: DiagnosticCheck['status'] }> = ({ status }) => {
    switch (status) {
        case 'ok': return <CheckCircle size={14} className="text-green-400" />;
        case 'warn': return <AlertTriangle size={14} className="text-yellow-400" />;
        case 'error': return <XCircle size={14} className="text-red-400" />;
    }
};

const CATEGORY_LABELS: Record<string, string> = {
    runtime: '💻 运行时',
    llm: '🤖 LLM',
    env: '📁 环境',
    auth: '🔐 认证',
    session: '💬 会话',
    tool: '🛠️ 工具',
    service: '⚙️ 服务',
};

export const DiagnosticPanel: React.FC<{ checks: DiagnosticCheck[]; summary: DiagnosticSummary }> = ({
    checks, summary,
}) => {
    // 按 category 分组
    const grouped = checks.reduce((acc, check) => {
        (acc[check.category] ??= []).push(check);
        return acc;
    }, {} as Record<string, DiagnosticCheck[]>);

    const overallStatus = summary.error > 0 ? 'error' : summary.warn > 0 ? 'warn' : 'ok';
    const statusColor = {
        ok: 'text-green-400 border-green-600/30 bg-green-600/5',
        warn: 'text-yellow-400 border-yellow-600/30 bg-yellow-600/5',
        error: 'text-red-400 border-red-600/30 bg-red-600/5',
    }[overallStatus];

    return (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] p-4 space-y-4">
            {/* Header + Summary */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <Activity size={18} className="text-blue-400" />
                    <span className="font-semibold text-[var(--text-primary)]">环境诊断报告</span>
                </div>
                <div className={`flex items-center gap-3 px-3 py-1 rounded-full border ${statusColor}`}>
                    <span className="text-xs">✅ {summary.ok}</span>
                    {summary.warn > 0 && <span className="text-xs">⚠️ {summary.warn}</span>}
                    {summary.error > 0 && <span className="text-xs">❌ {summary.error}</span>}
                </div>
            </div>

            {/* Categorized checks */}
            {Object.entries(grouped).map(([category, items]) => (
                <div key={category}>
                    <div className="text-xs font-medium text-[var(--text-muted)] mb-1.5">
                        {CATEGORY_LABELS[category] ?? category}
                    </div>
                    <div className="space-y-1">
                        {items.map((check) => (
                            <div key={check.name}
                                 className="flex items-center justify-between px-3 py-1.5 rounded-md bg-[var(--bg-tertiary)]">
                                <div className="flex items-center gap-2">
                                    <StatusIcon status={check.status} />
                                    <span className="text-sm text-[var(--text-primary)]">{check.name}</span>
                                </div>
                                <div className="text-right">
                                    <span className="text-xs text-[var(--text-secondary)]">{check.value}</span>
                                    {check.hint && (
                                        <div className="text-xs text-[var(--text-muted)] italic">{check.hint}</div>
                                    )}
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            ))}
        </div>
    );
};
```

### 4.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 增强已有 DoctorCommand，改为 `jsx()` 返回结构化诊断数据，添加 Python 服务、JVM 内存等检查项 | 1.5h |
| 2 | 新建 `DiagnosticPanel.tsx` 可视化诊断面板（状态图标 + 分类展示 + 汇总概览） | 2h |
| 3 | `dispatch.ts` 添加 `diagnosticReport` action 路由到 DiagnosticPanel | 0.5h |
| 4 | 编写单元测试 | 1h |

### 4.4 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 外部工具检测超时 | 低 | 每个检测 3 秒超时，不阻塞主线程 |
| Python 服务探测误报 | 低 | 仅作为 warning，不影响核心功能 |

---

## F5: ~~WebFetchTool 网页内容获取~~ 已实现（0 人天）

### 5.1 审查结论

> **状态: 已完整实现，无需开发。**

`WebFetchTool.java`（358 行）已存在于 `backend/src/main/java/com/aicodeassistant/tool/impl/WebFetchTool.java`，实现特性包括：

| 特性 | 实现状态 |
|------|----------|
| HTTP 请求 | ✅ OkHttpClient（30秒超时） |
| HTML → Markdown | ✅ Jsoup 库（比正则替换更可靠） |
| SSRF 防护 | ✅ 协议阻断（file/ftp/gopher/data/jar） |
| 域名白名单 | ✅ 22 个预审批域名（docs.python.org、github.com 等） |
| 内容截断 | ✅ 100K 字符上限 |
| 响应体限制 | ✅ 10MB HTTP 响应上限 |
| ToolRegistry 注册 | ✅ @Component 自动注册 |
| MicroCompact 白名单 | ✅ 已在 COMPACTABLE_TOOLS 中 |

### 5.2 原文档问题说明

原文档声称“Java 端无 WebFetchTool 实现类”“无纯 HTTP 抓取方案”“无 HTML → Markdown 转换逻辑”——这三条均不成立。已有实现使用 OkHttp + Jsoup 方案，质量优于原文档提议的 java.net.http.HttpClient + 正则替换方案。

### 5.3 可选增强项（未来考虑）

- 浏览器自动化模式：通过 Python 服务 BrowserService 处理 JavaScript 渲染页面
- CSS 选择器支持：提取特定页面区域内容
- 缓存机制：避免重复抓取相同 URL

---

## F6: Git 深度集成（3 人天）

### 6.1 现状分析

**已有基础设施**:
- `GitService`（125 行）提供 `getGitStatus()`/`isGitRepository()`/`execGit()`（private）基础方法
- Python 服务 `git_enhanced_service.py`（84 行）已实现 `diff`/`log`/`blame` 结构化分析
- `CommandRouter` + `CommandRegistry` 已支持 `/command` 注册
- `BashTool` 可执行任意 shell 命令（包括 git），但无语义封装

**缺失部分**:
- **`DiffCommand`（56 行）已存在**，命令名 `diff`，基于 `git diff HEAD` 实现，返回原始 diff 输出。但缺少 `--staged`/`--stat` 支持，无智能截断。
- 无 `/commit`、`/review` 等高层 Git 命令
- 无提交消息生成（AI 生成 commit message）
- 无 Git 暂存区管理
- 无代码审查功能
- **无浏览器端可视化 Git 体验**（可视化 diff、分支图、代码审查面板等）—— 这是最大的技术超越机会

### 6.2 技术方案

#### 6.2.0 前置修复: 删除 GitCommands.java 中冲突的 @Bean 定义

> **⚠️ CRITICAL-2 修复**: `GitCommands.java`（@Configuration）已有 `commitCommand()` 和 `reviewCommand()` @Bean，
> 命令名分别为 `"commit"` 和 `"review"`（PROMPT 类型）。新建的 @Component `GitCommitCommand`/`GitReviewCommand`
> 同样注册名为 `"commit"`/`"review"` 会导致 `CommandRegistry` 命名冲突。
>
> **解决方案**: 删除 `GitCommands.java` 中的 `commitCommand()` 和 `reviewCommand()` @Bean 定义，
> 用新的 @Component 类替代。新类提供更完整的功能（结构化 diff、前端确认、可视化展示）。

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/GitCommands.java`

```java
// v1.4 CRITICAL-2 修复: 删除以下两个 @Bean，用新的 GitCommitCommand/GitReviewCommand @Component 替代
// 已删除:
//   @Bean Command commitCommand()  — 名称 "commit"，PROMPT 类型
//   @Bean Command reviewCommand()  — 名称 "review"，PROMPT 类型
// 保留 GitCommands.java 中其他 @Bean 定义（如有）
```

#### 6.2.1 后端：GitCommitCommand

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/GitCommitCommand.java`（新建）

> **v1.3 修正**: GitCommitCommand 使用 `LOCAL` 类型而非 `PROMPT`。
> PROMPT 类型不应执行副作用（git commit 是不可逆操作）。
> 无参数时返回 `jsx()` 结构化数据供前端 GitCommitPanel 渲染，
> 用户在浏览器中确认 commit message 后再执行提交。

```java
package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.service.GitService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * /commit 命令 — AI 辅助生成 commit message 并提交。
 * 用法:
 *   /commit          — 显示变更概览 + AI 生成建议 commit message（前端确认后提交）
 *   /commit "message" — 直接使用指定 message 提交
 */
@Component
public class GitCommitCommand implements Command {

    private final GitService gitService;

    public GitCommitCommand(GitService gitService) {
        this.gitService = gitService;
    }

    @Override
    public String getName() { return "commit"; }

    @Override
    public String getDescription() { return "AI 辅助 Git 提交"; }

    @Override
    public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        // v1.4 SEC-3 修复: workingDir null 防护
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置");
        }
        Path workDir = Path.of(context.workingDir());

        if (!gitService.isGitRepository(workDir)) {
            return CommandResult.error("当前目录非 Git 仓库");
        }

        // 获取暂存区状态
        String status = gitService.execGitPublic(workDir, "status", "--porcelain");
        if (status == null || status.isBlank()) {
            return CommandResult.text("没有可提交的变更");
        }

        if (args != null && !args.isBlank()) {
            // 用户提供了 commit message，直接提交
            String commitResult = gitService.execGitPublic(workDir, "commit", "-m", args);
            return CommandResult.text("✅ 已提交:\n" + commitResult);
        }

        // 获取暂存区 diff 供前端 GitCommitPanel 可视化渲染
        String stagedDiff = gitService.execGitPublic(workDir, "diff", "--cached", "--stat");
        String detailedDiff = gitService.execGitPublic(workDir, "diff", "--cached");

        // 解析变更文件列表
        List<String> changedFiles = new ArrayList<>();
        for (String line : status.split("\n")) {
            if (line.length() > 3) changedFiles.add(line.substring(3).trim());
        }

        return CommandResult.jsx(Map.of(
            "action", "gitCommitPreview",
            "status", status,
            "stagedDiff", stagedDiff != null ? stagedDiff : "",
            "detailedDiff", truncate(detailedDiff, 5000),
            "changedFiles", changedFiles,
            "fileCount", changedFiles.size()
        ));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(已截断)" : text;
    }
}
```

#### 6.2.2 后端：增强已有 DiffCommand（返回 jsx() 可视化 diff）

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/DiffCommand.java`（重写已有 56 行，添加 --staged/--stat 支持 + jsx() 返回）

> **v1.3 修正**: DiffCommand 改为 `LOCAL_JSX` 类型，返回 `jsx()` 结构化数据，
> 前端渲染为可视化 diff 视图（行级着色、文件分组、统计概览），
> 远超终端原始 diff 输出。

```java
@Component
public class DiffCommand implements Command {

    private final GitService gitService;

    @Override
    public String getName() { return "diff"; }

    @Override
    public String getDescription() { return "显示 Git 差异"; }

    @Override
    public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        // v1.4 SEC-3 修复: workingDir null 防护
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置");
        }
        Path workDir = Path.of(context.workingDir());

        if (!gitService.isGitRepository(workDir)) {
            return CommandResult.error("当前目录非 Git 仓库");
        }

        boolean staged = args != null && args.contains("staged");

        // 获取统计概览和详细 diff
        String[] statArgs = staged
            ? new String[]{"diff", "--cached", "--stat"}
            : new String[]{"diff", "--stat"};
        String[] diffArgs = staged
            ? new String[]{"diff", "--cached"}
            : new String[]{"diff"};

        String stat = gitService.execGitPublic(workDir, statArgs);
        String diff = gitService.execGitPublic(workDir, diffArgs);

        if ((stat == null || stat.isBlank()) && (diff == null || diff.isBlank())) {
            return CommandResult.text("无差异");
        }

        // 返回 jsx() 结构化数据供前端 GitDiffPanel 可视化渲染
        return CommandResult.jsx(Map.of(
            "action", "gitDiffView",
            "staged", staged,
            "stat", stat != null ? stat : "",
            "diff", truncate(diff, 10000),
            "fileCount", stat != null ? stat.lines().count() - 1 : 0
        ));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(已截断)" : text;
    }
}
```

#### 6.2.3 后端：GitReviewCommand

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/GitReviewCommand.java`（新建）

```java
@Component
public class GitReviewCommand implements Command {

    private final GitService gitService;

    @Override
    public String getName() { return "review"; }

    @Override
    public String getDescription() { return "AI 代码审查当前变更"; }

    @Override
    public CommandType getType() { return CommandType.PROMPT; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        // v1.4 SEC-3 修复: workingDir null 防护
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置");
        }
        Path workDir = Path.of(context.workingDir());

        if (!gitService.isGitRepository(workDir)) {
            return CommandResult.error("当前目录非 Git 仓库");
        }

        // 获取完整 diff
        String diff = gitService.execGitPublic(workDir, "diff");
        String stagedDiff = gitService.execGitPublic(workDir, "diff", "--cached");
        String fullDiff = (diff != null ? diff : "") + "\n" + (stagedDiff != null ? stagedDiff : "");

        if (fullDiff.isBlank()) {
            return CommandResult.text("没有待审查的变更");
        }

        String prompt = String.format("""
            请对以下代码变更进行审查，从以下维度评估:
            1. 🐛 Bug 风险
            2. 🔒 安全漏洞
            3. ⚡ 性能问题
            4. 📐 代码规范
            5. 🧪 测试覆盖建议

            ```diff
            %s
            ```
            """, truncate(fullDiff, 8000));

        return CommandResult.text(prompt);
    }
    
    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(已截断)" : text;
    }
}
```

#### 6.2.4 后端：GitService 增强

**文件**: `backend/src/main/java/com/aicodeassistant/service/GitService.java` — 新增 public 方法

```java
/**
 * 执行 Git 命令并返回输出（公开方法，供 Command 调用）。
 *
 * @param workingDir 工作目录
 * @param args       Git 命令参数
 * @return 命令输出，失败返回 null
 */
public String execGitPublic(Path workingDir, String... args) {
    return execGit(workingDir, args);
}
```

#### 6.2.5 前端：GitDiffPanel 可视化 Diff 组件

**文件**: `frontend/src/components/git/GitDiffPanel.tsx`（新建）

> **v1.3 新增**: 这是最大的技术超越机会。利用浏览器环境实现 GitHub/GitLab 级别的
> 代码审查体验，包含可视化 diff、行级着色、文件分组、统计概览，
> 远超 Claude Code 终端的纯文本 diff 输出。

```tsx
import React, { useState } from 'react';
import { FileText, Plus, Minus, ChevronDown, ChevronRight, GitBranch } from 'lucide-react';

interface GitDiffData {
    staged: boolean;
    stat: string;
    diff: string;
    fileCount: number;
}

export const GitDiffPanel: React.FC<{ data: GitDiffData }> = ({ data }) => {
    const [expandedFiles, setExpandedFiles] = useState<Set<string>>(new Set());

    // 解析 diff 按文件分组
    const fileDiffs = parseDiffByFile(data.diff);

    const toggleFile = (path: string) => {
        setExpandedFiles(prev => {
            const next = new Set(prev);
            next.has(path) ? next.delete(path) : next.add(path);
            return next;
        });
    };

    return (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between px-4 py-2 border-b border-[var(--border)]">
                <div className="flex items-center gap-2">
                    <GitBranch size={16} className="text-blue-400" />
                    <span className="font-semibold text-sm text-[var(--text-primary)]">
                        Git Diff {data.staged ? '(Staged)' : '(Working Tree)'}
                    </span>
                </div>
                <span className="text-xs text-[var(--text-muted)]">
                    {data.fileCount} 个文件变更
                </span>
            </div>

            {/* Stat overview */}
            {data.stat && (
                <pre className="px-4 py-2 text-xs font-mono text-[var(--text-secondary)] border-b border-[var(--border)] bg-[var(--bg-tertiary)]">
                    {data.stat}
                </pre>
            )}

            {/* File-by-file diff */}
            <div className="divide-y divide-[var(--border)]">
                {fileDiffs.map(({ path, additions, deletions, lines }) => (
                    <div key={path}>
                        <button
                            onClick={() => toggleFile(path)}
                            className="w-full flex items-center gap-2 px-4 py-2 text-xs hover:bg-[var(--bg-tertiary)] transition-colors"
                        >
                            {expandedFiles.has(path) ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                            <FileText size={12} className="text-[var(--text-muted)]" />
                            <span className="flex-1 text-left font-mono text-[var(--text-primary)]">{path}</span>
                            <span className="text-green-400">+{additions}</span>
                            <span className="text-red-400">-{deletions}</span>
                        </button>
                        {expandedFiles.has(path) && (
                            <div className="bg-[var(--bg-primary)] overflow-x-auto">
                                {lines.map((line, i) => (
                                    <div key={i}
                                         className={`px-4 py-0.5 text-xs font-mono whitespace-pre ${
                                             line.startsWith('+') && !line.startsWith('+++')
                                                 ? 'bg-green-900/20 text-green-300'
                                                 : line.startsWith('-') && !line.startsWith('---')
                                                     ? 'bg-red-900/20 text-red-300'
                                                     : line.startsWith('@@')
                                                         ? 'bg-blue-900/10 text-blue-300'
                                                         : 'text-[var(--text-secondary)]'
                                         }`}
                                    >
                                        {line}
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                ))}
            </div>
        </div>
    );
};

/** 解析 unified diff 按文件分组 */
function parseDiffByFile(diff: string): Array<{
    path: string; additions: number; deletions: number; lines: string[];
}> {
    if (!diff) return [];
    const files: Array<{ path: string; additions: number; deletions: number; lines: string[] }> = [];
    let current: typeof files[0] | null = null;

    for (const line of diff.split('\n')) {
        if (line.startsWith('diff --git')) {
            if (current) files.push(current);
            const match = line.match(/b\/(.+)$/);
            current = { path: match?.[1] ?? 'unknown', additions: 0, deletions: 0, lines: [] };
        } else if (current) {
            current.lines.push(line);
            if (line.startsWith('+') && !line.startsWith('+++')) current.additions++;
            if (line.startsWith('-') && !line.startsWith('---')) current.deletions++;
        }
    }
    if (current) files.push(current);
    return files;
}
```

#### 6.2.6 前端：GitCommitPanel 提交确认组件

**文件**: `frontend/src/components/git/GitCommitPanel.tsx`（新建）

```tsx
import React, { useState } from 'react';
import { GitCommit, Send, FileText } from 'lucide-react';

interface GitCommitData {
    status: string;
    stagedDiff: string;
    changedFiles: string[];
    fileCount: number;
}

export const GitCommitPanel: React.FC<{
    data: GitCommitData;
    onCommit: (message: string) => void;
}> = ({ data, onCommit }) => {
    const [message, setMessage] = useState('');

    return (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] p-4 space-y-3">
            <div className="flex items-center gap-2">
                <GitCommit size={16} className="text-orange-400" />
                <span className="font-semibold text-sm text-[var(--text-primary)]">Git 提交</span>
                <span className="text-xs text-[var(--text-muted)]">{data.fileCount} 个文件变更</span>
            </div>

            {/* 变更文件列表 */}
            <div className="max-h-32 overflow-y-auto space-y-0.5">
                {data.changedFiles.map(file => (
                    <div key={file} className="flex items-center gap-2 text-xs text-[var(--text-secondary)]">
                        <FileText size={10} />
                        <span className="font-mono">{file}</span>
                    </div>
                ))}
            </div>

            {/* Diff stat */}
            {data.stagedDiff && (
                <pre className="text-xs font-mono text-[var(--text-muted)] bg-[var(--bg-tertiary)] rounded p-2 max-h-24 overflow-auto">
                    {data.stagedDiff}
                </pre>
            )}

            {/* Commit message input */}
            <div className="space-y-2">
                <textarea
                    value={message}
                    onChange={e => setMessage(e.target.value)}
                    placeholder="输入 commit message（或留空由 AI 生成）..."
                    className="w-full h-20 p-2 text-sm bg-[var(--bg-primary)] border border-[var(--border)] rounded-md text-[var(--text-primary)] resize-none focus:outline-none focus:border-blue-500"
                />
                <button
                    onClick={() => onCommit(message)}
                    className="flex items-center gap-1 px-3 py-1.5 rounded-md text-xs bg-green-600 hover:bg-green-700 text-white"
                >
                    <Send size={12} />
                    {message ? '提交' : 'AI 生成并提交'}
                </button>
            </div>
        </div>
    );
};
```

### 6.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 0 | **v1.4 CRITICAL-2 前置**: 删除 `GitCommands.java` 中 `commitCommand()`/`reviewCommand()` @Bean，避免命名冲突 | 0.5h |
| 1 | 增强 GitService，暴露 execGitPublic 方法 | 0.5h |
| 2 | 新建 GitCommitCommand（LOCAL 类型，返回 jsx() 供前端确认） | 2h |
| 3 | 重写已有 DiffCommand（LOCAL_JSX 类型，返回 jsx() 可视化 diff） | 1.5h |
| 4 | 新建 GitReviewCommand（PROMPT 类型，AI 代码审查） | 2h |
| 5 | 新建 GitDiffPanel.tsx 可视化 diff 组件（行级着色 + 文件分组） | 3h |
| 6 | 新建 GitCommitPanel.tsx 提交确认组件 | 2h |
| 7 | dispatch.ts 添加 gitDiffView/gitCommitPreview action 路由 | 1h |
| 8 | 前端 CommandPalette 中展示 Git 命令分组 | 0.5h |
| 9 | 编写单元测试 | 3h |
| 10 | 集成测试: 在真实 Git 仓库中测试 | 2h |

### 6.4 测试用例

```java
@SpringBootTest
class GitCommitCommandTest {

    @MockitoBean GitService gitService;
    @Autowired GitCommitCommand command;

    @Test
    void testCommitWithMessage() {
        when(gitService.isGitRepository(any())).thenReturn(true);
        when(gitService.execGitPublic(any(), eq("status"), eq("--porcelain")))
            .thenReturn("M  src/main/java/App.java");
        when(gitService.execGitPublic(any(), eq("commit"), eq("-m"), anyString()))
            .thenReturn("[main abc1234] fix: 修复空指针");

        var ctx = new CommandContext(
            "test", ".", "qwen", null, true, false, false);
        var result = command.execute("fix: 修复空指针", ctx);
        assertThat(result.type()).isEqualTo(CommandResult.ResultType.TEXT);
    }

    @Test
    void testCommitAutoGenerate() {
        when(gitService.isGitRepository(any())).thenReturn(true);
        when(gitService.execGitPublic(any(), eq("status"), eq("--porcelain")))
            .thenReturn("M  src/App.java");
        when(gitService.execGitPublic(any(), eq("diff"), eq("--cached"), eq("--stat")))
            .thenReturn(" src/App.java | 10 +++++-----");

        var result = command.execute("",
            new CommandContext("test", ".", "qwen", null, true, false, false));
        // v1.4 ERR-4 修正: 无参数时返回 jsx() 类型（非 TEXT），供前端 GitCommitPanel 渲染
        assertThat(result.type()).isEqualTo(CommandResult.ResultType.JSX);
        assertThat(result.data()).containsKey("action");
        assertThat(result.data().get("action")).isEqualTo("gitCommitPreview");
    }
}
```

### 6.5 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| Git 命令注入 | 高 | 使用 ProcessBuilder 参数化调用，不拼接字符串；禁止 `--exec` 等危险参数 |
| 大仓库 diff 过大 | 中 | 截断 diff 到 5000-10000 字符；`--stat` 先展示概览；前端文件级折叠展示 |
| 非 Git 仓库误调用 | 低 | 每个命令开头检查 `isGitRepository()` |
| /commit 自动提交安全性 | 中 | LOCAL 类型 + 前端 GitCommitPanel 确认后再执行，用户可审查和编辑 message |
| 命名冲突 | 高 | v1.4 CRITICAL-2 修复: 删除 GitCommands.java 中旧的 commitCommand/reviewCommand @Bean，避免与新 @Component 冲突 |
| workingDir 为 null | 中 | v1.4 SEC-3 修复: 所有 Git 命令 execute() 开头增加 null/blank 检查 |

---

## F7: Plan Mode 规划模式 UI（2 人天）

### 7.1 现状分析

**已有基础设施**:
- `taskStore.ts` 已支持任务管理（addTask/updateTask/removeTask），含 `foregroundedTaskId` 和 `viewingAgentTaskId`
- `ServerMessage.TaskUpdate`（#18）已定义任务状态更新
- `sessionStore.ts` 已有 `status` 字段（idle/streaming/waiting_permission/compacting）
- 后端 `QueryEngine` 支持多轮循环、工具编排

**缺失部分**:
- 无独立的 "Plan Mode" 状态（只读+规划模式 vs 实现模式）
- 无任务分解和步骤跟踪的前端 UI
- 无任务进度可视化面板

### 7.2 技术方案

#### 7.2.1 前端：planStore — 规划模式状态管理

**文件**: `frontend/src/store/planStore.ts`（新建）

```typescript
import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

export interface PlanStep {
    id: string;
    title: string;
    description: string;
    status: 'pending' | 'in_progress' | 'completed' | 'failed';
    substeps?: PlanStep[];
    estimatedMinutes?: number;
    files?: string[];
}

export interface PlanStoreState {
    isPlanMode: boolean;
    planName: string;
    planOverview: string;
    steps: PlanStep[];
    currentStepId: string | null;

    enablePlanMode: (name: string, overview: string) => void;
    disablePlanMode: () => void;
    setSteps: (steps: PlanStep[]) => void;
    updateStepStatus: (stepId: string, status: PlanStep['status']) => void;
    setCurrentStep: (stepId: string | null) => void;
    addStep: (step: PlanStep) => void;
    removeStep: (stepId: string) => void;
}

export const usePlanStore = create<PlanStoreState>()(
    subscribeWithSelector(immer((set) => ({
        isPlanMode: false,
        planName: '',
        planOverview: '',
        steps: [],
        currentStepId: null,

        enablePlanMode: (name, overview) => set(d => {
            d.isPlanMode = true;
            d.planName = name;
            d.planOverview = overview;
        }),
        disablePlanMode: () => set(d => {
            d.isPlanMode = false;
            d.planName = '';
            d.planOverview = '';
            d.steps = [];
            d.currentStepId = null;
        }),
        setSteps: (steps) => set(d => { d.steps = steps; }),
        updateStepStatus: (id, status) => set(d => {
            const step = d.steps.find(s => s.id === id);
            if (step) step.status = status;
        }),
        setCurrentStep: (id) => set(d => { d.currentStepId = id; }),
        addStep: (step) => set(d => { d.steps.push(step); }),
        removeStep: (id) => set(d => {
            d.steps = d.steps.filter(s => s.id !== id);
        }),
    })))
);
```

#### 7.2.2 前端：PlanPanel 组件

**文件**: `frontend/src/components/plan/PlanPanel.tsx`（新建）

```tsx
import React from 'react';
import { usePlanStore, type PlanStep } from '@/store/planStore';
import { CheckCircle, Circle, Loader2, XCircle, FileText, Clock } from 'lucide-react';

const StatusIcon: React.FC<{ status: PlanStep['status'] }> = ({ status }) => {
    switch (status) {
        case 'completed': return <CheckCircle size={16} className="text-green-400" />;
        case 'in_progress': return <Loader2 size={16} className="text-blue-400 animate-spin" />;
        case 'failed': return <XCircle size={16} className="text-red-400" />;
        default: return <Circle size={16} className="text-gray-500" />;
    }
};

export const PlanPanel: React.FC = () => {
    const { isPlanMode, planName, planOverview, steps, currentStepId } = usePlanStore();

    if (!isPlanMode) return null;

    const completed = steps.filter(s => s.status === 'completed').length;
    const total = steps.length;
    const progress = total > 0 ? (completed / total) * 100 : 0;

    return (
        <div className="border-l border-[var(--border)] w-80 bg-[var(--bg-secondary)] flex flex-col">
            {/* Header */}
            <div className="p-3 border-b border-[var(--border)]">
                <h3 className="text-sm font-semibold text-[var(--text-primary)]">
                    📋 {planName}
                </h3>
                <p className="text-xs text-[var(--text-muted)] mt-1">{planOverview}</p>
                {/* Progress bar */}
                <div className="mt-2 h-1.5 bg-gray-700 rounded-full overflow-hidden">
                    <div
                        className="h-full bg-blue-500 rounded-full transition-all duration-300"
                        style={{ width: `${progress}%` }}
                    />
                </div>
                <span className="text-xs text-[var(--text-muted)]">
                    {completed}/{total} 步骤完成
                </span>
            </div>

            {/* Step list */}
            <div className="flex-1 overflow-y-auto p-2 space-y-1">
                {steps.map((step) => (
                    <div
                        key={step.id}
                        className={`p-2 rounded-lg text-sm transition-colors
                            ${step.id === currentStepId
                                ? 'bg-blue-600/10 border border-blue-600/30'
                                : 'hover:bg-[var(--bg-tertiary)]'}`}
                    >
                        <div className="flex items-start gap-2">
                            <StatusIcon status={step.status} />
                            <div className="flex-1 min-w-0">
                                <div className="text-[var(--text-primary)] truncate">
                                    {step.title}
                                </div>
                                {step.description && (
                                    <div className="text-xs text-[var(--text-muted)] mt-0.5 line-clamp-2">
                                        {step.description}
                                    </div>
                                )}
                                {/* Meta info */}
                                <div className="flex items-center gap-2 mt-1">
                                    {step.estimatedMinutes && (
                                        <span className="flex items-center gap-0.5 text-xs text-[var(--text-muted)]">
                                            <Clock size={10} />
                                            {step.estimatedMinutes}min
                                        </span>
                                    )}
                                    {step.files && step.files.length > 0 && (
                                        <span className="flex items-center gap-0.5 text-xs text-[var(--text-muted)]">
                                            <FileText size={10} />
                                            {step.files.length} files
                                        </span>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
};
```

### 7.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 新建 `planStore.ts` 状态管理 | 1h |
| 2 | 新建 `PlanPanel.tsx` 侧边面板组件 | 3h |
| 3 | 集成到 AppLayout，根据 isPlanMode 条件渲染 | 1h |
| 4 | 后端新增 `plan_update` WebSocket 消息类型 | 1h |
| 5 | `dispatch.ts` 添加 plan_update 处理 | 0.5h |
| 6 | 添加 `/plan` 命令切换模式 | 1h |
| 7 | 样式调优和动画 | 1h |
| 8 | 测试验证 | 1.5h |

### 7.4 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 侧边面板在小屏幕上挤压主内容区 | 中 | 添加抽屉模式（Drawer），移动端点击展开/收起 |
| 步骤状态与实际执行不同步 | 中 | 通过 WebSocket task_update 消息实时同步；步骤 ID 与工具调用关联 |
| Plan Mode 下工具执行权限 | 中 | 后端 QueryEngine 需根据 Plan Mode 状态限制工具执行（仅允许只读工具，禁止 FileWrite/FileEdit/BashTool 等） |

---

## F8: 文件变更可视化 Dashboard（2.5 人天）

### 8.1 现状分析

**已有基础设施**:
- `FileHistoryService`（360 行）已实现完整的文件快照管理（trackEdit/saveSnapshot/getSnapshots）
- `FileSnapshotRepository` 已有 SQLite 持久化
- `FileStateCache` 跟踪会话级文件读写状态
- `java-diff-utils` 已集成（FileEditTool 中使用）
- Python 服务 `file_processing.py` 有 `GET /api/files/watch` SSE 文件监听
- **`FileHistoryController.java`（99 行）已存在**，提供三个 REST 端点：
  - `GET /api/sessions/{sessionId}/history/snapshots` — 按 messageId 分组列出快照
  - `POST /api/sessions/{sessionId}/history/rewind` — 回退到指定快照
  - `GET /api/sessions/{sessionId}/history/diff` — 两个快照间的 diff 统计

**缺失部分**:
- 无文件变更历史的可视化前端页面
- 无 diff 对比视图
- 无变更影响分析（哪些文件被修改、修改了多少行）

### 8.2 技术方案

#### 8.2.1 后端：FileHistoryController 已存在

> **注意**: `FileHistoryController.java`（99 行）已存在于 `backend/src/main/java/com/aicodeassistant/controller/FileHistoryController.java`。
> 以下为已有端点摘要，前端直接对接即可，无需新建。
>
> **v1.3 修正**: `SnapshotSummary` record 字段为 `messageId` / `trackedFiles` / `fileCount` / `timestamp`，
> 前端代码必须使用 `snap.trackedFiles`（非 `snap.files`）和 `snap.messageId`（非 `snap.snapshotId`）。

**已有端点**:
- `GET /api/sessions/{sessionId}/history/snapshots` — 返回 `Map<messageId, List<SnapshotSummary>>`
- `POST /api/sessions/{sessionId}/history/rewind` — 接收 `{ messageId, filePaths }`，返回 `RewindResponse`
- `GET /api/sessions/{sessionId}/history/diff?fromMessageId=&toMessageId=` — 返回 `DiffStatsResponse`

**SnapshotSummary record 字段对照**:
```java
// 后端 record 定义（字段名必须与前端一致）
public record SnapshotSummary(
    String messageId,        // ✅ 前端使用 snap.messageId
    List<String> trackedFiles, // ✅ 前端使用 snap.trackedFiles（非 snap.files）
    int fileCount,           // ✅ 前端使用 snap.fileCount
    String timestamp         // ✅ v1.4 修正: 实际类型为 String（非 Instant），源码确认于 FileHistoryController.java L91-92
) {}
```

#### 8.2.2 前端：FileChangesDashboard 组件

**文件**: `frontend/src/components/dashboard/FileChangesDashboard.tsx`（新建）

```tsx
import React, { useEffect, useState } from 'react';
import { FileText, Plus, Minus, Clock, ChevronRight } from 'lucide-react';

interface FileChange {
    filePath: string;
    snapshots: Array<{
        snapshotId: string;
        operation: string;
        timestamp: string;
        sizeBytes: number;
    }>;
}

export const FileChangesDashboard: React.FC<{ sessionId: string }> = ({ sessionId }) => {
    const [changes, setChanges] = useState<Map<string, FileChange['snapshots']>>(new Map());
    const [selectedFile, setSelectedFile] = useState<string | null>(null);
    const [diff, setDiff] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);

    // 对接已有后端 API: /api/sessions/{sessionId}/history/snapshots
    useEffect(() => {
        fetch(`/api/sessions/${sessionId}/history/snapshots`)
            .then(r => r.json())
            .then(data => {
                // data 格式: Map<messageId, List<SnapshotSummary>>
                // 转换为按文件分组的视图
                const fileMap = new Map<string, FileChange['snapshots']>();
                Object.values(data).flat().forEach((snap: any) => {
                    // v1.3 修正: 使用 snap.trackedFiles（匹配后端 SnapshotSummary record 字段名）
                    for (const file of snap.trackedFiles || []) {
                        if (!fileMap.has(file)) fileMap.set(file, []);
                        fileMap.get(file)!.push({
                            snapshotId: snap.messageId, // v1.3 修正: 使用 snap.messageId（非 snapshotId）
                            operation: 'edit',
                            timestamp: snap.timestamp,
                            sizeBytes: 0,
                        });
                    }
                });
                setChanges(fileMap);
                setLoading(false);
            })
            .catch(() => setLoading(false));
    }, [sessionId]);

    // 对接已有后端 API: /api/sessions/{sessionId}/history/diff
    const loadDiff = async (filePath: string, fromMessageId: string) => {
        const resp = await fetch(
            `/api/sessions/${sessionId}/history/diff?fromMessageId=${fromMessageId}&toMessageId=current`);
        const data = await resp.json();
        setDiff(JSON.stringify(data, null, 2));
        setSelectedFile(filePath);
    };

    if (loading) return <div className="p-4 text-sm text-[var(--text-muted)]">加载中...</div>;

    return (
        <div className="flex h-full">
            {/* File list */}
            <div className="w-64 border-r border-[var(--border)] overflow-y-auto">
                <div className="p-3 border-b border-[var(--border)]">
                    <h3 className="text-sm font-semibold">📁 变更文件 ({changes.size})</h3>
                </div>
                {Array.from(changes.entries()).map(([path, snaps]) => (
                    <button
                        key={path}
                        onClick={() => snaps[0] && loadDiff(path, snaps[0].snapshotId)}
                        className={`w-full text-left px-3 py-2 text-xs flex items-center gap-2
                            hover:bg-[var(--bg-tertiary)] transition-colors
                            ${selectedFile === path ? 'bg-blue-600/10 text-blue-300' : 'text-[var(--text-secondary)]'}`}
                    >
                        <FileText size={14} />
                        <span className="truncate flex-1">{path.split('/').pop()}</span>
                        <span className="text-[var(--text-muted)]">{snaps.length}x</span>
                    </button>
                ))}
            </div>

            {/* Diff view */}
            <div className="flex-1 overflow-auto p-4">
                {diff ? (
                    <pre className="text-xs font-mono whitespace-pre-wrap">
                        {diff.split('\n').map((line, i) => (
                            <div
                                key={i}
                                className={`px-2 ${
                                    line.startsWith('+') && !line.startsWith('+++')
                                        ? 'bg-green-900/20 text-green-300'
                                        : line.startsWith('-') && !line.startsWith('---')
                                            ? 'bg-red-900/20 text-red-300'
                                            : 'text-[var(--text-secondary)]'
                                }`}
                            >
                                {line}
                            </div>
                        ))}
                    </pre>
                ) : (
                    <div className="flex items-center justify-center h-full text-[var(--text-muted)]">
                        选择一个文件查看变更
                    </div>
                )}
            </div>
        </div>
    );
};
```

### 8.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 确认已有 FileHistoryController 端点满足需求，补充缺失的查询接口（如按文件分组查询） | 2h |
| 2 | 新建 `FileChangesDashboard.tsx` 主面板，对接已有 API | 6h |
| 3 | 实现 Diff 对比视图（行级着色） | 4h |
| 4 | 添加变更统计概览卡片 | 2h |
| 5 | 集成到主界面（作为可切换面板或路由） | 1h |
| 6 | 响应式适配 | 1h |
| 7 | 前端组件测试 | 2h |

### 8.4 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 快照数据量大 | 中 | 分页加载 + 默认限制 50 条 + 快照内容按需加载 |
| Diff 渲染性能 | 中 | 使用虚拟滚动（react-window）处理大文件 diff |
| 安全：快照内容包含敏感信息 | 低 | 仅限本地访问；快照存储在 .ai-code-assistant/data.db |

---

# F9: Skills 技能系统 — 已实现，UI 增强（1 人天）

> **⚠️ 审查修正**: 原文档提议从零新建 SkillRegistry/SkillCommand（5 人天），但源码审查发现 Skills 系统**已完整实现**。
> 本节已重写为仅聚焦前端技能管理 UI 增强。

### 9.1 现状分析

**已完整实现的后端组件**:

| 组件 | 文件 | 行数 | 功能 |
|------|------|------|------|
| `SkillRegistry` | `backend/.../skill/SkillRegistry.java` | 370 | 技能发现/加载/注册/解析，支持 6 种来源（BUNDLED/MANAGED/USER/PROJECT/PLUGIN/MCP） |
| `SkillTool` | `backend/.../skill/SkillTool.java` | 128 | 作为 Tool 接口实现注册到 ToolRegistry，由 SkillExecutor 执行，供 LLM 调用 |
| `SkillDefinition` | `backend/.../skill/SkillDefinition.java` | 96 | 技能定义 record，含 `name`/`fileName`/`frontmatter(FrontmatterData)`/`content`/`source(SkillSource)`/`filePath` |
| `SkillExecutor` | `backend/.../skill/SkillExecutor.java` | — | 技能执行器，被 SkillTool 调用。不存在独立的 SkillCommand 类 |

**已有功能特性**:
- **6 种技能来源**: BUNDLED（内置）、MANAGED（企业管理）、USER（`~/.qoder/skills/`）、PROJECT（`.qoder/skills/`）、PLUGIN、MCP
- **WatchService 热加载**: 技能文件变更后自动重新加载，无需重启
- **Markdown front-matter 格式**: 支持 name/description/args 定义 + prompt 模板
- **模板渲染**: `{{arg}}` 占位符替换
- **ToolRegistry 集成**: 技能自动注册为可调用工具

**原文档错误点**:
1. 声称"无 Skill 发现和加载机制" — 实际 `SkillRegistry.loadAndRegister()` 已实现 6 种来源扫描
2. 声称"无 Skill 执行引擎" — 实际 `SkillTool` 调用 `SkillExecutor` 执行，已注册为 Tool
3. 声称"无 Skill 管理命令" — 实际技能通过 SkillTool 调用，无独立的 SkillCommand 类
4. 目录路径写成 `.zhikun/skills/` — 实际代码使用 `.qoder/skills/`
5. 提议"新建 SkillRegistry.java" — 该文件已有 370 行完整实现

**真正缺失的部分**:
- 前端无技能浏览/管理 UI（用户只能通过 `/skill` 命令文本交互）
- CommandPalette 中未动态展示可用技能列表
- 无技能执行历史/统计

### 9.2 技术方案

#### 9.2.1 前端：CommandPalette 技能列表集成

在已有的 `CommandPalette.tsx` 中增加技能分组：

```tsx
// 在 CommandPalette.tsx 的命令列表中增加 skills 分组
// 通过 WebSocket 或 REST API 获取已注册技能列表

const [skills, setSkills] = useState<Array<{ name: string; description: string }>>([]);

useEffect(() => {
    // 调用后端 /skill 命令获取技能列表，或新增 REST 端点
    fetch('/api/skills')
        .then(r => r.json())
        .then(setSkills)
        .catch(() => {});
}, []);

// 在命令列表渲染中增加:
{skills.map(skill => (
    <CommandItem
        key={`skill-${skill.name}`}
        icon={<Zap size={14} />}
        label={`/skill ${skill.name}`}
        description={skill.description}
        onSelect={() => executeCommand(`/skill ${skill.name}`)}
    />
))}
```

#### 9.2.2 后端：技能列表 REST 端点（可选）

> 若 CommandPalette 需要独立获取技能列表（而非通过 `/skill` 命令），可新增一个轻量端点：

```java
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;

    @GetMapping
    public List<Map<String, String>> listSkills() {
        return skillRegistry.getAllSkills().stream()
            .map(s -> Map.of(
                "name", s.effectiveName(),
                "description", s.effectiveDescription(),
                "source", s.source().name()
            ))
            .toList();
    }
}
```

### 9.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 确认已有 SkillRegistry API（`getAllSkills()` 返回 `Collection<SkillDefinition>`），决定是否需要新增 REST 端点 | 1h |
| 2 | CommandPalette 集成技能列表展示 | 2h |
| 3 | 创建 2-3 个示例技能文件到 `.qoder/skills/` | 1h |
| 4 | 前端组件测试 | 2h |

### 9.4 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 技能列表加载延迟 | 低 | 技能数量通常 < 20，响应极快 |
| 前端与后端技能格式不一致 | 低 | 直接使用 SkillRegistry 返回的标准格式 |

---

## 附录 A: 工作量汇总

> **⚠️ 审查修正**: F5/F9 已实现（0 天新开发），F2/F4/F8 工作量下调，v1.4 新增 G0 前置修复 0.5 天，总计从 12.5 天调整至 13 天。

| 功能 | 后端 | 前端 | 测试 | 总计 | 审查备注 |
|------|------|------|------|------|----------|
| **G0: JSX/COMPACT 推送修复** | **1h** | **0.5h** | **1h** | **0.5 天** | **v1.4 CRITICAL-1 前置修复，F3/F4/F6 依赖** |
| F1: /compact 命令 | 2h | 1.5h | 2h | **1 天** | |
| F2: 成本/Token 显示 | 0.5h | 1.5h | 1h | **0.5 天** | StatusBar.tsx 已有 UI，修 onUsage bug；v1.4 测试组件名修正为 StatusBar |
| F3: zhikun.md 记忆 | 4.5h | 3.5h | 4h | **2 天** | 重写 MemoryCommand + 浏览器编辑器；v1.4 增加 InitCommand 修复/SEC-1/SEC-2/SEC-3/ERR-1 |
| F4: /doctor 诊断 | 1.5h | 2.5h | 1h | **1 天** | 增强 DoctorCommand；v1.4 修正错别字 |
| F5: WebFetchTool | — | — | — | **0 天** | ✅ 已实现（358 行），无需开发 |
| F6: Git 深度集成 | 6.5h | 6.5h | 5h | **3.5 天** | v1.4 CRITICAL-2 前置删除 @Bean + ERR-4 测试修正 + SEC-3 |
| F7: Plan Mode UI | 2h | 7h | 1.5h | **2 天** | |
| F8: 文件变更 Dashboard | 1h | 10h | 3h | **2.5 天** | 后端已有 FileHistoryController；v1.4 修正 timestamp 类型 |
| F9: Skills UI 增强 | 1h | 2h | 2h | **1 天** | ✅ 后端已实现（370+128+96 行）|
| **合计** | **20.5h** | **25.5h** | **20.5h** | **≈ 13 天** |

## 附录 B: 技术栈依赖矩阵

| 功能 | Java 后端 | React 前端 | Python 服务 | 新依赖 |
|------|-----------|------------|-------------|--------|
| **G0** | **WebSocketController (handleSlashCommand 重写)** | **dispatch.ts (command_result 增强)** | — | 无 |
| F1 | CompactService, CommandRegistry | dispatch.ts, messageStore | — | 无 |
| F2 | CostTrackerService, WsMessageHandler (修 onUsage) | StatusBar.tsx (增强) | — | 无 |
| F3 | MemoryCommand (重写), ProjectMemoryService (新建), **InitCommand (修正)** | MemoryEditorPanel (新建) | — | **dompurify** (SEC-1) |
| F4 | DoctorCommand (增强, jsx() 返回) | DiagnosticPanel (新建) | — | 无 |
| F5 | ✅ WebFetchTool 已实现 | — | — | — |
| F6 | GitService, GitCommitCommand (LOCAL), DiffCommand (LOCAL_JSX), **删除 GitCommands @Bean** | GitDiffPanel, GitCommitPanel (新建) | — | 无 |
| F7 | WebSocket 新消息类型 | 新 Store + 组件 | — | 无 |
| F8 | FileHistoryController (已有) | 新 Dashboard 组件 | — | 可选: react-window |
| F9 | SkillRegistry (已有), 可选 SkillController | CommandPalette 扩展 | — | 无 |

## 附录 C: 实施顺序建议

> **审查修正**: F5 已实现无需排期，F9 仅需 UI 工作，v1.4 新增 G0 前置修复，总周期压缩至 3 周。

```
第 0 周(前置): G0 (WebSocketController JSX/COMPACT 推送修复) — 0.5 天
             ↓ 所有 jsx() 方案的前置依赖，必须首先完成
第 1 周: F1 (/compact) + F4 (/doctor) + F2 (成本显示) + F9 (Skills UI)
         ↓ 基础命令 + 快速见效项（F2/F4/F9 均为增强已有功能）
         ↓ F5 (WebFetchTool) 已实现，跳过
第 2 周: F3 (zhikun.md 记忆 + 浏览器编辑器 + InitCommand 修正) + F6 (Git 集成 + 可视化 diff + 删除旧 @Bean)
         ↓ 核心功能闭环
第 3 周: F7 (Plan Mode) + F8 (文件变更 Dashboard)
         ↓ 高级功能
```

---

> **文档结束** (v1.4 第四轮审查修订版)  
> 所有方案基于 ZhikuCode 现有代码库的**源码级审查**。  
> v1.4 核心修复:  
> - **CRITICAL-1**: WebSocketController.handleSlashCommand 增加 JSX/COMPACT ResultType 分支推送，解除 F3/F4/F6 阻塞；  
> - **CRITICAL-2**: 删除 GitCommands.java 中冲突的 commitCommand/reviewCommand @Bean；  
> - **ERR-1**: EffectiveSystemPromptBuilder 包路径 engine → prompt；  
> - **ERR-2**: SnapshotSummary timestamp 类型 Instant → String；  
> - **ERR-4**: F6 测试断言类型 TEXT → JSX；  
> - **ERR-5**: 错别字 "无活跟会话" → "无活跃会话"；  
> - **ERR-6**: InitCommand.java CLAUDE.md → zhikun.md；  
> - **ERR-7**: F2 测试组件名 CostDisplay → StatusBar；  
> - **SEC-1**: MemoryEditorPanel 增加 DOMPurify XSS 防护；  
> - **SEC-2**: writeMemory 增加路径遍历防护；  
> - **SEC-3**: F3/F6 所有命令增加 workingDir null 防护。  
> 确保与现有架构（CommandRouter/ToolRegistry/WebSocket STOMP/Zustand Store）完全兼容。  
> 审查工具: 14 维度源码级验证（问题存在性/准确性/完整性/可行性/技术栈适配/安全性/必要性/真实性/浏览器适配等）。
