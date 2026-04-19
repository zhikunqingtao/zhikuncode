# ZhikuCode 功能增强规划文档 v1.6

> **文档版本**: 1.6  
> **创建日期**: 2026-04-18  
> **技术栈**: Java 21 + Spring Boot 3.3 + React 18 + TypeScript + Zustand + Python 3.11 + FastAPI  
> **适用范围**: ZhikuCode 全栈功能增强，共 7 项新增功能 + 2 项已实现功能增强，总计约 15 人天
> **审查版本**: v1.6（基于第六轮源码级审查，修正 Message 类型扩展、SessionManager 方法名、CompactCommand 行数、HookEvent 数量、记忆注入架构、GitService 方法、SkillDefinition 字段类型等 15+ 处源码级错误）
>
> **全局 API 约定说明**:
> - `CommandResult` 可用工厂方法: `text(String)` / `error(String)` / `jsx(Map)` / `compact(String, Map)` / `skip()`
> - `CommandResult` record 字段: `type(ResultType)` / `value(String)` / `data(Map)` / `error(String)`
>   - `jsx()` 返回: type=JSX, **value=null**, data=结构化数据 — 注意 value 为 null
>   - `compact()` 返回: type=COMPACT, value=displayText, data=压缩元数据
>   - `text()` 返回: type=TEXT, value=文本内容, data=空Map
> - `CommandContext` record 字段: `sessionId` / `workingDir` / `currentModel` / `appState` / `isAuthenticated` / `isRemoteMode` / `isBridgeMode`（共 7 字段）
> - `SessionData` record 字段: `sessionId` / `model` / `workingDir` / `title` / `status` / `messages` / `config` / `totalUsage` / `totalCostUsd` / `summary` / `createdAt(Instant)` / `updatedAt(Instant)`（共 12 字段，注意 createdAt/updatedAt 类型为 `java.time.Instant`）
> - `SessionManager` 查询方法: `loadSession(String sessionId)` 返回 `Optional<SessionData>`（非 getSession）
> - 项目配置目录统一使用 `.qoder/`（与代码中 SkillRegistry/Skills 目录一致）
> - 项目记忆文件统一使用 `zhikun.md` / `zhikun.local.md`（不使用 QODER.md 或 CLAUDE.md）
> - **Message 类型扩展**: 前端 `system` 类型 Message 当前无 `metadata` 字段，G0 中需扩展类型定义以支持 JSX 路由
>
> **⚠️ CRITICAL 前置修复**:
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
| F1 | /compact 手动压缩命令 | 1.5 人天 | P0 |
| F2 | 前端成本/Token 显示增强（修复 onUsage + 增强 StatusBar） | 0.5 人天 | P0 |
| F3 | 项目记忆系统 zhikun.md | 2.5 人天 | P0 |
| F4 | /doctor 环境诊断命令增强 | 1 人天 | P1 |
| F5 | ~~WebFetchTool 网页内容获取~~ **已实现** | 0 人天 | — |
| F6 | Git 深度集成 | 3.5 人天 | P1 |
| F7 | Plan Mode 规划模式 UI | 2 人天 | P2 |
| F8 | 文件变更可视化 Dashboard（前端 UI，后端 API 已有） | 2.5 人天 | P2 |
| F9 | ~~Skills 技能系统~~ Skills UI 增强（后端已实现） | 1 人天 | P2 |

---

## G0: 全局前置修复 — WebSocketController JSX/COMPACT 推送支持（0.5 人天）

> **⚠️ CRITICAL-1 阻塞依赖**: 此修复是 **F1（compact_complete 路由）、F3（MemoryCommand jsx()）、F4（DoctorCommand jsx()）、F6（DiffCommand/GitCommitCommand jsx()）** 所有结构化返回方案的**前置阻塞依赖**，必须在其他功能开发前完成。未修复此问题前，所有返回 `jsx()` 或依赖 `compact_complete` 增强字段的命令结果均无法到达前端。

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

        // 根据 ResultType 分别处理 TEXT/JSX/COMPACT 类型
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

**文件**: `frontend/src/api/dispatch.ts`

> **现状**: 当前 `command_result` handler 签名为 `(d: { command: string; output: string })`，仅支持纯文本。
> `compact_complete` handler 签名为 `(d: { summary: string; tokensSaved: number })`，缺少结构化元数据。
> 需要同时增强两个 handler，确保字段与后端 G0.2 推送的 Map 完全匹配。

#### G0.3.1 扩展 Message 类型定义 + 增强 `command_result` handler

> **❗ 前置修改**: 当前 `system` 类型 Message 无 `metadata` 字段（仅有 `subtype`/`errorCode`/`retryable`）。
> 必须先扩展 Message 类型定义，否则所有 `msg.metadata` 引用无法编译。

**文件**: `frontend/src/types/index.ts`

```typescript
// 修改 Message 类型中的 system 分支（约 L13-14）
// 原始:
//   | { type: 'system'; uuid: string; timestamp: number; content: string; subtype?: string;
//       errorCode?: string; retryable?: boolean }
// 修改为:
    | { type: 'system';     uuid: string; timestamp: number; content: string; subtype?: string;
        errorCode?: string; retryable?: boolean;
        metadata?: Record<string, unknown> }  // G0 新增: JSX 命令结果的结构化数据
```

**文件**: `frontend/src/api/dispatch.ts`

```typescript
// 替换现有 command_result handler (dispatch.ts L183-191)
// 后端推送字段: { command, type, output?, data? } — 参见 G0.2 switch 分支
'command_result': (d: { command: string; type: 'text' | 'jsx'; output?: string; data?: Record<string, unknown> }) => {
    if (d.type === 'jsx' && d.data) {
        // JSX 类型: 根据 data.action 路由到对应组件
        // 后端 JSX 分支推送: Map.of("command", cmd, "type", "jsx", "data", cmdResult.data())
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
        // 后端 TEXT 分支推送: Map.of("command", cmd, "type", "text", "output", value)
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

#### G0.3.2 增强 `compact_complete` handler

```typescript
// 替换现有 handleCompactComplete (dispatch.ts L267-276)
// 后端推送字段: Map.of("displayText", ..., "compactionData", ...) — 参见 G0.2 COMPACT 分支
function handleCompactComplete(data: {
    summary?: string; tokensSaved?: number;  // 兼容自动压缩的旧格式
    displayText?: string; compactionData?: Record<string, unknown>;  // 新格式: /compact 手动压缩
}): void {
    useSessionStore.getState().setStatus('idle');
    if (data.compactionData) {
        // 新格式: 来自 /compact 命令，携带可视化元数据
        useMessageStore.getState().addMessage({
            type: 'system',
            uuid: crypto.randomUUID(),
            timestamp: Date.now(),
            content: data.displayText ?? '',
            subtype: 'compact_result',
            metadata: data.compactionData,
        } as Message);
    } else {
        // 旧格式: 来自自动压缩
        useMessageStore.getState().addMessage({
            type: 'system',
            uuid: crypto.randomUUID(),
            timestamp: Date.now(),
            content: `上下文已压缩，节省 ${data.tokensSaved ?? 0} tokens`,
            subtype: 'compact_boundary',
        } as Message);
    }
}
```

#### G0.3.3 修复 `App.tsx` handleSlashCommand

> **现状**: `handleSlashCommand` 仅执行 `console.log('Command:', command)`，未发送到后端。
> `sendSlashCommand()` 已在 `stompClient.ts L289` 定义但未被调用。此修复为 G0 + F1 共用前置。

```typescript
// frontend/src/App.tsx — 替换现有 handleSlashCommand (L85-87)
import { sendSlashCommand } from '@/api/stompClient';

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

    // 通过 STOMP 发送到后端 (/app/command)
    sendSlashCommand(cmdName, cmdArgs);
}, [addMessage]);
```

### G0.4 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 后端: 修改 `WebSocketController.handleSlashCommand()`，按 ResultType 分支推送 TEXT/JSX/COMPACT | 1h |
| 2 | **前端: 扩展 `types/index.ts` 中 system Message 类型，新增 `metadata?: Record<string, unknown>` 字段** | 0.5h |
| 3 | 前端: 增强 `dispatch.ts` 的 `command_result` handler 支持 jsx 类型路由 | 0.5h |
| 4 | 前端: 增强 `dispatch.ts` 的 `handleCompactComplete` 支持新旧两种格式 | 0.5h |
| 5 | 前端: 修复 `App.tsx` handleSlashCommand 调用 `sendSlashCommand()` | 0.5h |
| 6 | 单元测试: 验证三种 ResultType 都能正确推送 + dispatch 路由测试 | 1h |

### G0.5 测试用例

```java
@SpringBootTest
class WebSocketControllerSlashCommandTest {

    @MockitoBean CommandRegistry commandRegistry;
    @MockitoBean SimpMessagingTemplate messagingTemplate;
    @Autowired WebSocketController controller;

    private final String sessionId = "test-session";
    private final Principal principal = () -> sessionId;

    @Test
    void testTextResultIsPushed() {
        Command helpCmd = mock(Command.class);
        when(commandRegistry.getCommand("help")).thenReturn(helpCmd);
        when(helpCmd.execute(any(), any())).thenReturn(CommandResult.text("Available commands..."));

        controller.handleSlashCommand(new SlashCommandPayload("help", ""), principal);

        // 验证推送: { type: "command_result", data: { command: "help", type: "text", output: "..." } }
        verify(messagingTemplate).convertAndSendToUser(
            eq(sessionId), eq("/queue/messages"),
            argThat(msg -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) msg;
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                return "command_result".equals(body.get("type"))
                    && "text".equals(data.get("type"))
                    && "help".equals(data.get("command"));
            }));
    }

    @Test
    void testJsxResultIsPushed() {
        Command doctorCmd = mock(Command.class);
        when(commandRegistry.getCommand("doctor")).thenReturn(doctorCmd);
        when(doctorCmd.execute(any(), any())).thenReturn(
            CommandResult.jsx(Map.of("action", "diagnosticReport", "checks", List.of())));

        controller.handleSlashCommand(new SlashCommandPayload("doctor", ""), principal);

        // 验证推送: { type: "command_result", data: { command: "doctor", type: "jsx", data: {...} } }
        verify(messagingTemplate).convertAndSendToUser(
            eq(sessionId), eq("/queue/messages"),
            argThat(msg -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) msg;
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                return "command_result".equals(body.get("type"))
                    && "jsx".equals(data.get("type"))
                    && data.containsKey("data");
            }));
    }

    @Test
    void testCompactResultIsPushed() {
        Command compactCmd = mock(Command.class);
        when(commandRegistry.getCommand("compact")).thenReturn(compactCmd);
        when(compactCmd.execute(any(), any())).thenReturn(
            CommandResult.compact("Compacted", Map.of("savedTokens", 5000)));

        controller.handleSlashCommand(new SlashCommandPayload("compact", ""), principal);

        // 验证推送: { type: "compact_complete", data: { displayText: "Compacted", compactionData: {...} } }
        verify(messagingTemplate).convertAndSendToUser(
            eq(sessionId), eq("/queue/messages"),
            argThat(msg -> "compact_complete".equals(((Map<?,?>)msg).get("type"))));
    }

    @Test
    void testSkipResultIsNotPushed() {
        Command skipCmd = mock(Command.class);
        when(commandRegistry.getCommand("noop")).thenReturn(skipCmd);
        when(skipCmd.execute(any(), any())).thenReturn(CommandResult.skip());

        controller.handleSlashCommand(new SlashCommandPayload("noop", ""), principal);

        // SKIP 类型不应推送任何消息
        verify(messagingTemplate, never()).convertAndSendToUser(
            anyString(), anyString(), any());
    }
}
```

---

## F1: /compact 手动压缩命令（1.5 人天）

### 1.1 现状分析

**已有基础设施**:
- `CompactService`（`engine/CompactService.java`，997 行）已完整实现三级降级压缩策略（LLM 摘要 → 关键消息选择 → 尾部截断）
- `ContextCascade`（`engine/ContextCascade.java`，303 行）管理自动压缩级联，含 `executePreApiCascade()` 和 `executeErrorRecoveryCascade()`
- 后端已有 `CompactStart`/`CompactComplete`/`CompactEvent` 三种 WebSocket 消息类型
- `CommandRouter` + `CommandRegistry` 已支持斜杠命令注册和路由
- `SlashCommandParser` 已实现 `/command args` 格式解析

**缺失部分**:
- `CompactCommand` 已存在（67 行），但实现为简化版：仅注入 CompactService，硬编码 contextWindow=200000，通过 `context.appState().session().messages()` 获取消息，返回 `CommandResult.compact(displayText, Map)` 类型。需增强为注入 ModelRegistry 动态获取 contextWindow，保持 `compact()` 返回类型（dispatch.ts 已有 compact_complete 处理），并增强元数据供前端可视化渲染。
- 前端 `handleSlashCommand` 当前仅 `console.log`，未真正发送到后端（已在 G0.3.3 中修复，`sendSlashCommand()` 已定义于 stompClient.ts L289）
- 缺少压缩结果的前端可视化面板（当前仅纯文本展示，未充分利用浏览器渲染能力）

> **架构约束说明**: 当前 CompactCommand 通过 `context.appState().session().messages()` 获取会话消息。此方式依赖 `WebSocketController` 构造 `CommandContext` 时传入完整的 `AppState`（含 `SessionState.messages`）。当前后端 `handleSlashCommand` 使用 `CommandContext.of(sessionId, ".", null, null)` 构造上下文，`appState` 为 null，将导致 NPE。需在 G0 修复中确保 `handleSlashCommand` 传入正确的 AppState，或在 CompactCommand 中改用 `SessionManager.loadSession(context.sessionId())` 获取消息。以下方案采用后者，通过注入 SessionManager 解耦对 AppState 的依赖。

### 1.2 技术方案

#### 1.2.1 后端：CompactCommand 增强

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/CompactCommand.java`（增强已有 67 行实现）

> **架构适配说明**: 现有实现通过 `context.appState().session().messages()` 获取消息，但当前 `handleSlashCommand` 构造的 `CommandContext` 中 `appState` 为 null。
> 改为注入 `SessionManager` 通过 `sessionManager.loadSession(context.sessionId())` 获取消息，解除对 AppState 的依赖。
> 注意: SessionManager 的方法名是 `loadSession()`（非 `getSession()`），返回 `Optional<SessionData>`。

```java
package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.engine.CompactService.CompactResult;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * /compact [custom_instruction] — 手动触发上下文压缩。
 * 通过 SessionManager 获取会话消息（而非依赖 AppState），保持 compact() 返回类型。
 */
@Component
public class CompactCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(CompactCommand.class);

    private final CompactService compactService;
    private final TokenCounter tokenCounter;
    private final ModelRegistry modelRegistry;
    private final SessionManager sessionManager;

    public CompactCommand(CompactService compactService,
                          TokenCounter tokenCounter,
                          ModelRegistry modelRegistry,
                          SessionManager sessionManager) {
        this.compactService = compactService;
        this.tokenCounter = tokenCounter;
        this.modelRegistry = modelRegistry;
        this.sessionManager = sessionManager;
    }

    @Override public String getName() { return "compact"; }
    @Override public String getDescription() { return "Compact conversation context"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }
    @Override public boolean supportsNonInteractive() { return true; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        try {
            // 通过 SessionManager 获取会话消息（解耦对 AppState 的依赖）
            var sessionData = sessionManager.loadSession(context.sessionId());
            if (sessionData.isEmpty()) {
                return CommandResult.text("Session not found: " + context.sessionId());
            }
            List<com.aicodeassistant.model.Message> messages = sessionData.get().messages();

            if (messages == null || messages.isEmpty()) {
                return CommandResult.text("Nothing to compact — conversation is empty.");
            }

            // 从 ModelRegistry 动态获取当前模型的上下文窗口大小
            String model = context.currentModel() != null ? context.currentModel() : "default";
            int contextWindow = modelRegistry.getContextWindowForModel(model);

            int beforeTokens = tokenCounter.estimateTokens(messages);
            CompactResult result = compactService.compact(messages, contextWindow, false);

            if (result.skipReason() != null) {
                return CommandResult.text("Compact skipped: " + result.skipReason());
            }

            String displayText = "Conversation compacted. " + result.summary();
            if (args != null && !args.isBlank()) {
                displayText += " (Instruction: " + args.trim() + ")";
            }

            int savedTokens = result.savedTokens(); // CompactResult.savedTokens() = beforeTokens - afterTokens

            // 保持 compact() 返回类型，增强元数据供前端 CompactResultPanel 可视化渲染
            // 字段与 G0.2 COMPACT 分支推送的 compactionData 完全对应
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

> 已在 G0.3.3 中统一修复。`App.tsx` 的 `handleSlashCommand` 现在调用 `sendSlashCommand(cmdName, cmdArgs)` 发送到后端 `/app/command`。

#### 1.2.3 前端：CompactResultPanel 可视化组件

**文件**: `frontend/src/components/compact/CompactResultPanel.tsx`（新建）

> 充分利用浏览器渲染能力，将压缩结果从纯文本升级为可视化面板，
> 包含 token 对比条、统计卡片、压缩率指示器，这是 Claude Code 终端无法实现的体验。
> 数据来源: dispatch.ts compact_complete handler 中的 `data.compactionData` 字段（参见 G0.3.2）。

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

> 已在 G0.3.2 中统一修复。`handleCompactComplete` 现在支持新旧两种格式，
> 来自 `/compact` 命令的新格式携带 `compactionData` 元数据供 CompactResultPanel 渲染。

### 1.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 增强已有 `CompactCommand.java`，新增 TokenCounter/ModelRegistry/SessionManager 注入，改用 SessionManager 获取消息，保持 `compact()` 返回类型 | 1.5h |
| 2 | 在 `CommandRegistry` 确认自动注册（@Component 已足够） | 0.5h |
| 3 | 新建 `CompactResultPanel.tsx` 可视化面板组件（token 对比条 + 统计卡片） | 1h |
| 4 | 编写 `CompactCommandTest.java` 单元测试 | 1h |
| 5 | 端到端测试：前端发送 /compact → 后端执行 → 前端显示结果 | 1h |

### 1.4 测试用例

```java
// CompactCommandTest.java
@SpringBootTest
class CompactCommandTest {

    @MockitoBean CompactService compactService;
    @MockitoBean TokenCounter tokenCounter;
    @MockitoBean ModelRegistry modelRegistry;
    @MockitoBean SessionManager sessionManager;
    @Autowired CompactCommand compactCommand;

    @Test
    void testCompactSuccess() {
        // 准备: 模拟含 10 条消息的会话
        var messages = List.of(/* 10 条测试消息 */);
        var sessionData = new SessionData("test-session", "qwen", ".", null, "idle",
            messages, null, null, 0.0, null, null, null);
        when(sessionManager.loadSession("test-session")).thenReturn(Optional.of(sessionData));
        when(tokenCounter.estimateTokens(messages)).thenReturn(50000);
        when(modelRegistry.getContextWindowForModel("qwen")).thenReturn(128000);
        when(compactService.compact(any(), anyInt(), eq(false)))
            .thenReturn(new CompactResult(List.of(/* 压缩后消息 */), 50000, 30000, 5, 0.4));
            // CompactResult 5参数便捷构造器: (compactedMessages, beforeTokens, afterTokens, compactedMessageCount, compressionRatio)

        var ctx = new CommandContext(
            "test-session", ".", "qwen", null, true, false, false);
        var result = compactCommand.execute("", ctx);

        // 验证返回类型为 COMPACT（非 TEXT）
        assertThat(result.type()).isEqualTo(CommandResult.ResultType.COMPACT);
        // 验证元数据含可视化所需字段
        assertThat(result.data()).containsKeys(
            "beforeTokens", "afterTokens", "savedTokens", "compressionRatio");
    }

    @Test
    void testCompactEmptyConversation() {
        var sessionData = new SessionData("test-session", "qwen", ".", null, "idle",
            List.of(), null, null, 0.0, null, null, null);
        when(sessionManager.loadSession("test-session")).thenReturn(Optional.of(sessionData));
        var result = compactCommand.execute("",
            new CommandContext("test-session", ".", "qwen", null, true, false, false));
        assertThat(result.type()).isEqualTo(CommandResult.ResultType.TEXT);
        assertThat(result.value()).contains("empty");
    }

    @Test
    void testCompactSessionNotFound() {
        when(sessionManager.loadSession("unknown")).thenReturn(Optional.empty());
        var result = compactCommand.execute("",
            new CommandContext("unknown", ".", "qwen", null, true, false, false));
        assertThat(result.type()).isEqualTo(CommandResult.ResultType.TEXT);
        assertThat(result.value()).contains("not found");
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

## F2: 前端成本/Token 显示增强（0.5 人天，**P0 最高 ROI**）

> **⭐ P0 优先级**: 此修复为全项目最高 ROI 方案（核心修复仅 2 行代码，30 分钟完成），建议作为第一个实施项。

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

#### 2.2.1 后端：修复 CostUpdate 推送（核心修复，仅 2 行）

**文件**: `backend/src/main/java/com/aicodeassistant/websocket/WebSocketController.java`

> **核心修复点**: `WsMessageHandler.onUsage()` 当前传递 `0.0` 作为 sessionCost 和 totalCost。
> 仅需替换为调用 `CostTrackerService.getSessionCost()`/`getGlobalCost()` 的真实値。

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

#### 2.2.3 前端：costStore 增强 — 历史记录和预算（Phase 2 可选增强）

> **Phase 2 可选**: 以下 costHistory 和 budgetUsd 扩展为 **可选增强项**，不阻塞核心修复。
> 核心修复（后端 onUsage 2 行 + 前端 StatusBar 添加总费用显示）可在 30 分钟内完成。

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
| 1 | **核心修复**: 后端注入 CostTrackerService，修复 onUsage 推送真实费用（仅 2 行代码） | 0.5h |
| 2 | 增强已有 StatusBar.tsx，添加总费用和预算警告 | 0.5h |
| 3 | （Phase 2 可选）扩展 costStore 支持历史记录和预算 | 0.5h |
| 4 | 测试验证 | 0.5h |

### 2.4 测试用例

```typescript
// StatusBar.test.tsx — 增强已有 StatusBar 组件
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

## F3: 项目记忆系统 zhikun.md（2.5 人天）

### 3.1 现状分析

**对标功能**: Claude Code 的 `CLAUDE.md` / `CLAUDE.local.md` 项目记忆系统

**已有基础设施**:
- `EffectiveSystemPromptBuilder` 构建系统提示词，已有 `ProjectContextService` 预加载项目上下文
- `FileReadTool`/`FileWriteTool`/`FileEditTool` 已完整实现文件操作闭环
- `HookService` 已支持 12 种 Hook 事件（PRE_TOOL_USE/POST_TOOL_USE/USER_PROMPT_SUBMIT/NOTIFICATION/STOP/SESSION_START/SESSION_END/TASK_COMPLETED/TEAMMATE_IDLE/STOP_HOOKS/PRE_COMPACT/POST_COMPACT）
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
        // 入口参数防御
        if (workingDir == null) {
            log.warn("loadMemory called with null workingDir");
            return "";
        }

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
                            content = content.substring(0,
                                Math.min(content.length(), (int) MAX_MEMORY_SIZE));
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
        Path normalizedWorkingDir = workingDir.normalize();
        Path memFile = normalizedWorkingDir.resolve(fileName).normalize();

        // SEC-2 增强: 符号链接防御 + 路径遍历防护
        // 先检查规范化路径，再检查真实路径（防符号链接攻击）
        if (!memFile.startsWith(normalizedWorkingDir)) {
            throw new IOException("Path traversal detected: " + memFile);
        }
        if (Files.exists(memFile)) {
            Path realPath = memFile.toRealPath();
            if (!realPath.startsWith(normalizedWorkingDir.toRealPath())) {
                throw new IOException("Symlink path traversal detected: " + memFile + " -> " + realPath);
            }
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

**文件**: `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptBuilder.java` — 修改

> **正确的注入位置**: 当前记忆加载由 `SystemPromptBuilder.loadMemoryPrompt()`（L1086-1092）通过 `claudeMdLoader.loadMergedContent()` 完成。
> 应将 ProjectMemoryService 注入到 `SystemPromptBuilder`（而非 EffectiveSystemPromptBuilder），
> 在 `loadMemoryPrompt()` 方法中调用 `projectMemoryService.loadMemory(workingDir)` 并封装为 `<project_memory>` 标签。
> EffectiveSystemPromptBuilder 构造函数仅 4 个参数（SystemPromptBuilder, FeatureFlagService, CoordinatorPromptBuilder, CoordinatorService），不应在此处新增参数。

在 `SystemPromptBuilder` 构造函数中新增 `ProjectMemoryService` 注入，修改 `loadMemoryPrompt()` 方法：

```java
// SystemPromptBuilder.java — 新增依赖注入
private final ProjectMemoryService projectMemoryService;

// 构造函数新增参数
public SystemPromptBuilder(... , ProjectMemoryService projectMemoryService) {
    // ...现有赋值...
    this.projectMemoryService = projectMemoryService;
}

// 修改 loadMemoryPrompt() 方法（L1086-1092）
// 原始: 调用 claudeMdLoader.loadMergedContent(workingDir)
// 修改为: 调用 projectMemoryService.loadMemory(workingDir)
private String loadMemoryPrompt(Path workingDir) {
    if (projectMemoryService == null) return "";
    String memory = projectMemoryService.loadMemory(workingDir);
    if (memory == null || memory.isBlank()) return "";
    return "\n\n<project_memory>\n" + memory + "\n</project_memory>\n";
}
```

> **注意**: `ClaudeMdLoader` 原来加载 CLAUDE.md 文件的功能将由 `ProjectMemoryService` 替代（加载 zhikun.md）。
> `ClaudeMdLoader` 的 6 层加载逻辑（用户级 → 项目级 → 本地级 → rules → 父目录）可保留作为参考，但文件名从 CLAUDE.md 改为 zhikun.md。

#### 3.2.4 后端：MemoryCommand（重写已有实现）

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/MemoryCommand.java`（重写，已存在 42 行，当前引用 CLAUDE.md 三处）

> 文件名从 CLAUDE.md 改为 zhikun.md，保持 LOCAL_JSX 类型和 jsx() 返回。
> JSX 结果推送依赖 G0 的 WebSocketController 修复。

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
        // SEC-3: workingDir null 防护
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置，无法加载记忆文件");
        }
        Path workingDir = Path.of(context.workingDir()).normalize();

        if (args == null || args.isBlank() || args.equals("show")) {
            String memory = memoryService.loadMemory(workingDir);
            boolean hasMemory = !memory.isBlank();
            // JSX 结果: 通过 G0 的 WebSocketController 修复推送到前端
            // dispatch.ts command_result handler 根据 type=jsx + data.action=showMemoryFiles 路由到 MemoryEditorPanel
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

> 充分利用浏览器环境实现 Markdown 可视化编辑器，支持实时预览、模板插入。
> **优化点**:
> - 预览使用项目已有的 `react-markdown`（package.json L31）替代手写 `simpleMarkdownToHtml`，避免列表项 `<li>` 缺少 `<ul>` 包装等问题
> - 模板插入下拉菜单改用 click 事件（而非 group-hover:block），解决移动设备无 hover 事件问题
> - 按钮尺寸满足移动端 44x44px 触控规范（min-h-10 min-w-10）
> - 使用 DOMPurify 进行 XSS 防护（SEC-1）
> - 应集成到 `SettingsPanel.tsx` 已有的 Memory Tab（L141-178 的 `MemoryManager` 占位组件）

```tsx
import React, { useState, useCallback, useEffect } from 'react';
import { Save, Eye, Edit3, FileText, Plus, RefreshCw } from 'lucide-react';
import ReactMarkdown from 'react-markdown';  // 项目已有依赖 (package.json L31)
import DOMPurify from 'dompurify'; // SEC-1: XSS 防护依赖（npm install dompurify @types/dompurify）

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
    const [showTemplateMenu, setShowTemplateMenu] = useState(false);

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
                    {/* 模板插入下拉 — 使用 click 而非 hover，确保移动端可用 */}
                    <div className="relative">
                        <button
                            className="p-1.5 min-h-10 min-w-10 rounded hover:bg-[var(--bg-tertiary)] flex items-center justify-center"
                            title="插入模板"
                            onClick={() => setShowTemplateMenu(!showTemplateMenu)}
                        >
                            <Plus size={14} />
                        </button>
                        {showTemplateMenu && (
                            <div className="absolute right-0 top-full mt-1 bg-[var(--bg-primary)] border border-[var(--border)] rounded-lg shadow-lg z-10 min-w-[140px]">
                                {Object.entries(MEMORY_TEMPLATES).map(([name, tpl]) => (
                                    <button
                                        key={name}
                                        onClick={() => { insertTemplate(tpl); setShowTemplateMenu(false); }}
                                        className="block w-full text-left px-3 py-2 min-h-10 text-xs hover:bg-[var(--bg-tertiary)]"
                                    >
                                        {name}
                                    </button>
                                ))}
                            </div>
                        )}
                    </div>
                    {/* 编辑/预览切换 */}
                    <button
                        onClick={() => setIsPreview(!isPreview)}
                        className="p-1.5 min-h-10 min-w-10 rounded hover:bg-[var(--bg-tertiary)] flex items-center justify-center"
                        title={isPreview ? '编辑模式' : '预览模式'}
                    >
                        {isPreview ? <Edit3 size={14} /> : <Eye size={14} />}
                    </button>
                    {/* 保存 */}
                    <button
                        onClick={handleSave}
                        disabled={!dirty || saving}
                        className="flex items-center gap-1 px-2 py-1 min-h-10 rounded text-xs bg-blue-600 hover:bg-blue-700 disabled:opacity-50"
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
                        {/* 使用 react-markdown 替代手写 simpleMarkdownToHtml，避免列表项缺少 <ul> 包装等问题 */}
                        <ReactMarkdown>{DOMPurify.sanitize(content)}</ReactMarkdown>
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
```

#### 3.2.6 前端：集成到 SettingsPanel 和 dispatch.ts

**SettingsPanel 集成**: `frontend/src/components/settings/SettingsPanel.tsx` 已有 Memory Tab（L141-178 的 `MemoryManager` 占位组件），将其替换为 `MemoryEditorPanel` 组件。

**dispatch.ts 集成**: 在 G0.3.1 中已增强的 `command_result` handler 内，JSX 类型消息会通过 `subtype: 'jsx_result'` + `metadata.action` 路由。前端 MessageList 组件根据 `metadata.action` 渲染对应组件：
- `showMemoryFiles` → `MemoryEditorPanel`（记忆文件查看/编辑）
- `memoryCreated` → 创建成功通知

```typescript
// 在 MessageList 或 MessageBubble 组件中，根据 subtype/action 渲染对应组件:
if (msg.subtype === 'jsx_result' && msg.metadata?.action === 'showMemoryFiles') {
    return <MemoryEditorPanel
        workingDir={msg.metadata.workingDir as string}
        initialContent={msg.metadata.content as string}
        fileName="zhikun.md"
        onSave={async (content) => {
            // 通过 REST API 或 STOMP 保存记忆文件
            sendSlashCommand('memory', `save ${content}`);
        }}
    />;
}
if (msg.subtype === 'jsx_result' && msg.metadata?.action === 'memoryCreated') {
    // 记忆文件创建成功通知
    return (
        <div className="p-3 rounded-lg bg-green-600/10 border border-green-600/30 text-sm text-green-300">
            ✅ 已创建 {msg.metadata.fileName as string}，使用 <code>/memory show</code> 查看并编辑。
        </div>
    );
}
```

### 3.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 新建 `ProjectMemoryService.java` — 加载/写入/检查，含 workingDir null 防御和符号链接路径遍历防护 | 2h |
| 2 | 修改 `SystemPromptBuilder.loadMemoryPrompt()` 注入记忆到系统提示词（在 SystemPromptBuilder 中注入 ProjectMemoryService，而非 EffectiveSystemPromptBuilder） | 1h |
| 3 | 重写已有 `MemoryCommand.java` — `/memory show`/`init`，从 CLAUDE.md 改为 zhikun.md，保持 jsx() 返回 | 1h |
| 3.1 | 修改 `InitCommand.java` L31，将 `CLAUDE.md` 引用改为 `zhikun.md` | 0.5h |
| 4 | 添加 `.gitignore` 条目 `zhikun.local.md`（当前 .gitignore 中无此条目，需在文件末尾添加） | 0.5h |
| 5 | 新建 `MemoryEditorPanel.tsx` 浏览器可视化编辑器（react-markdown 预览 + 模板插入 + 移动端适配） | 3h |
| 6 | 替换 `SettingsPanel.tsx` 中的 `MemoryManager` 占位组件为 `MemoryEditorPanel` | 0.5h |
| 7 | dispatch.ts JSX 路由已在 G0 中完成，确认 MessageList 根据 action 渲染对应组件 | 1h |
| 8 | 单元测试: ProjectMemoryServiceTest（含符号链接防御测试） | 2h |
| 9 | 集成测试: 端到端记忆加载、编辑和注入验证 + WebSocket 推送验证 | 2h |

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
        // 内容截断不超过 MAX_MEMORY_SIZE + 注释头
        assertThat(memory.length()).isLessThanOrEqualTo(100 * 1024 + 50);
    }

    @Test
    void testLoadMemory_NullWorkingDir() {
        // loadMemory 应安全处理 null 工作目录
        String memory = service.loadMemory(null);
        assertThat(memory).isEmpty();
    }

    @Test
    void testWriteMemory() throws IOException {
        service.writeMemory(tempDir, "# 新记忆", false);
        assertThat(Files.readString(tempDir.resolve("zhikun.md"))).isEqualTo("# 新记忆");
    }

    @Test
    void testWriteMemory_PathTraversal() {
        // 路径遍历攻击应被拦截
        // 注: 文件名已硬编码为 zhikun.md/zhikun.local.md，路径遍历主要通过 workingDir 构造触发
        // 以下验证 normalize() + startsWith() 防护机制
        Path maliciousDir = tempDir.resolve("../../etc");
        assertThatThrownBy(() -> service.writeMemory(maliciousDir, "hack", false))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("traversal");
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
| zhikun.md 过大增加 token 消耗 | 中 | 100KB 上限 + 截断（含 Math.min 安全截断）；后续可实现 LLM 摘要缩减 |
| 多级目录搜索性能 | 低 | 最多搜索 5 层，Files.isRegularFile 为 O(1) 文件系统调用 |
| 敏感信息泄露 | 中 | zhikun.local.md 建议加入 .gitignore；文档中注明不要存放密钥 |
| 记忆编辑器 XSS 风险 | 中 | SEC-1: 使用 DOMPurify 对 Markdown 内容消毒，新增 `dompurify` 依赖；预览使用 react-markdown 而非 dangerouslySetInnerHTML |
| 记忆文件写入路径遍历 | 高 | SEC-2 增强: writeMemory 方法增加 normalize() + startsWith() 路径校验，并增加 toRealPath() 符号链接防御 |
| workingDir 为 null 时 NPE | 中 | SEC-3: MemoryCommand.execute() 和 ProjectMemoryService.loadMemory() 均增加 null/blank 检查 |
| 移动端触控不友好 | 中 | 模板下拉菜单改用 click 事件；按钮尺寸满足 44x44px 规范 (min-h-10 min-w-10) |

---

## F4: /doctor 环境诊断命令增强（1 人天，**P1 高 ROI**）

### 4.1 现状分析

**已有基础设施**:
- `HealthController`（177 行）已实现 `GET /api/doctor` 端点，检查 Java、Git、Ripgrep、JVM 内存
- `DoctorCommand`（80 行）已作为 `@Component` 注册，类型 `LOCAL_JSX`，但返回 `CommandResult.text()`（类型不匹配），检查 Java版本/LLM/工作目录/认证/会话/Git
- 后端已有 `checkExternalTool()` 工具检测方法

**缺失部分**:
- DoctorCommand 未检查 Python 服务、JVM 内存、磁盘空间
- 前端无诊断报告的结构化展示
- 缺少 Node.js、npm 等前端工具检查

**Claude Code 对标（Doctor 575 行 React 组件）**:
- Claude Code Doctor 在终端以纯文本列表展示诊断结果
- ZhikuCode 浏览器优势：卡片网格布局、Color-coded 状态指示、实时重新检查能力、可导出 HTML/JSON 诊断报告

### 4.2 技术方案

#### 4.2.1 后端：增强 DoctorCommand（返回 jsx() 结构化数据）

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/DoctorCommand.java` — 增强已有实现（80 行）

修复类型不匹配：保持 `LOCAL_JSX` 类型，改为返回 `CommandResult.jsx(Map)` 结构化数据，前端渲染为 DiagnosticPanel 可视化诊断面板，这是浏览器端的重大体验提升。

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

        // 9. 磁盘空间
        java.io.File rootDir = new java.io.File(workDir);
        long freeGb = rootDir.getFreeSpace() / (1024 * 1024 * 1024);
        boolean diskOk = freeGb > 1;
        checks.add(buildCheck("env", "Disk Space",
            String.format("%d GB 可用", freeGb),
            diskOk ? "ok" : "warn",
            diskOk ? null : "磁盘空间不足，建议清理"));

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
        return buildCheck(category, name, value, status, hint, null);
    }

    private Map<String, Object> buildCheck(String category, String name,
                                            String value, String status, String hint,
                                            Map<String, String> actions) {
        var map = new LinkedHashMap<String, Object>();
        map.put("category", category);
        map.put("name", name);
        map.put("value", value);
        map.put("status", status); // "ok" | "warn" | "error"
        if (hint != null) map.put("hint", hint);
        if (actions != null && !actions.isEmpty()) map.put("actions", actions);
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

充分利用浏览器渲染能力，将诊断结果从纯文本升级为可视化面板，包含状态图标、分类展示、汇总概览、一键重新检查、导出报告、操作按钮，远超 Claude Code 终端纯文本诊断输出。

```tsx
import React, { useState } from 'react';
import { CheckCircle, AlertTriangle, XCircle, Activity, RefreshCw, Download } from 'lucide-react';

interface DiagnosticCheck {
    category: string;
    name: string;
    value: string;
    status: 'ok' | 'warn' | 'error';
    hint?: string;
    actions?: Record<string, string>; // e.g. { "编辑配置": "openFile:application.yml" }
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

export const DiagnosticPanel: React.FC<{
    checks: DiagnosticCheck[];
    summary: DiagnosticSummary;
    onRecheck?: () => void;
    onAction?: (actionKey: string, actionValue: string) => void;
}> = ({ checks, summary, onRecheck, onAction }) => {
    const [exporting, setExporting] = useState(false);

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

    // 导出诊断报告为 JSON
    const handleExport = () => {
        setExporting(true);
        const report = {
            timestamp: new Date().toISOString(),
            summary,
            checks,
        };
        const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `diagnostic-report-${new Date().toISOString().slice(0, 10)}.json`;
        a.click();
        URL.revokeObjectURL(url);
        setExporting(false);
    };

    return (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] p-4 space-y-4">
            {/* Header + Summary + Actions */}
            <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                    <Activity size={18} className="text-blue-400" />
                    <span className="font-semibold text-[var(--text-primary)]"> 环境诊断报告</span>
                </div>
                <div className="flex items-center gap-2 flex-wrap">
                    <div className={`flex items-center gap-3 px-3 py-1 rounded-full border ${statusColor}`}>
                        <span className="text-xs">✅ {summary.ok}</span>
                        {summary.warn > 0 && <span className="text-xs">⚠️ {summary.warn}</span>}
                        {summary.error > 0 && <span className="text-xs">❌ {summary.error}</span>}
                    </div>
                    {onRecheck && (
                        <button
                            onClick={onRecheck}
                            className="flex items-center gap-1 px-2 py-1 rounded text-xs bg-blue-600 hover:bg-blue-700 text-white"
                        >
                            <RefreshCw size={12} />
                            重新检查
                        </button>
                    )}
                    <button
                        onClick={handleExport}
                        disabled={exporting}
                        className="flex items-center gap-1 px-2 py-1 rounded text-xs bg-[var(--bg-tertiary)] hover:bg-[var(--bg-primary)] text-[var(--text-secondary)]"
                    >
                        <Download size={12} />
                        导出报告
                    </button>
                </div>
            </div>

            {/* Categorized checks — 网格布局，<768px 单列，≥768px 双列 */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {Object.entries(grouped).map(([category, items]) => (
                    <div key={category} className="space-y-1">
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
                                    <div className="text-right flex items-center gap-2">
                                        <div>
                                            <span className="text-xs text-[var(--text-secondary)]">{check.value}</span>
                                            {check.hint && (
                                                <div className="text-xs text-[var(--text-muted)] italic">{check.hint}</div>
                                            )}
                                        </div>
                                        {check.actions && Object.entries(check.actions).map(([label, action]) => (
                                            <button
                                                key={label}
                                                onClick={() => onAction?.(label, action)}
                                                className="text-xs text-blue-400 hover:text-blue-300 underline"
                                            >
                                                {label}
                                            </button>
                                        ))}
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
};
```

#### 4.2.3 前端：dispatch.ts 路由 diagnosticReport action

**文件**: `frontend/src/utils/dispatch.ts`

在 `command_result` handler 内，根据 `metadata.action === 'diagnosticReport'` 路由到 DiagnosticPanel 组件：

```typescript
// dispatch.ts — diagnosticReport action 路由
case 'diagnosticReport': {
    const checks = msg.metadata.checks as DiagnosticCheck[];
    const summary = msg.metadata.summary as DiagnosticSummary;
    return (
        <DiagnosticPanel
            checks={checks}
            summary={summary}
            onRecheck={() => {
                // 重新发送 /doctor 命令
                sendCommand('/doctor');
            }}
            onAction={(label, action) => {
                // 处理点击跳转修复，如 openFile:application.yml
                if (action.startsWith('openFile:')) {
                    const filePath = action.replace('openFile:', '');
                    sendCommand(`/open ${filePath}`);
                }
            }}
        />
    );
}
```

### 4.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 增强已有 DoctorCommand，改为 `jsx()` 返回结构化诊断数据，添加 Python 服务、JVM 内存、磁盘空间检查，buildCheck 支持 actions 字段 | 1.5h |
| 2 | 新建 `DiagnosticPanel.tsx` 可视化诊断面板（状态图标 + 分类展示 + 汇总概览 + 一键重新检查 + 导出报告 + 移动端响应式布局） | 2h |
| 3 | `dispatch.ts` 添加 `diagnosticReport` action 路由到 DiagnosticPanel，支持 onRecheck 和 onAction 回调 | 0.5h |
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
| 域名白名单 | ✅ 25 个预审批域名（docs.python.org、github.com、fastapi.tiangolo.com 等） |
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

## F6: Git 深度集成（3.5 人天）

### 6.1 现状分析

**已有基础设施**:
- `GitService`（125 行）提供 `getGitStatus()`/`isGitRepository()`/`execGit()`（**private 方法**，第 89 行）基础方法。注意: `execGit()` 是 private 的，外部无法直接调用，F6 必须新增 public 包装方法 `execGitPublic()`。
- Python 服务 `git_enhanced_service.py`（84 行）已实现 `diff`/`log`/`blame` 结构化分析
- `CommandRouter` + `CommandRegistry` 已支持 `/command` 注册
- `BashTool` 可执行任意 shell 命令（包括 git），但无语义封装

**缺失部分**:
- **`DiffCommand`（56 行）已存在**，命令名 `diff`，基于 `git diff HEAD` 实现，返回 TEXT 类型原始 diff 输出。但缺少 `--staged`/`--stat` 支持，无智能截断。
- 无 `/commit`、`/review` 等高层 Git 命令
- 无提交消息生成（AI 生成 commit message）
- 无 Git 暂存区管理
- 无代码审查功能
- **无浏览器端可视化 Git 体验** —— 这是最大的技术超越机会

**浏览器 UI 超越 Claude Code 的关键差异点**:

| 能力 | ZhikuCode 浏览器端 | Claude Code 终端 |
|------|------|------|
| Diff 展示 | Monaco Diff Editor side-by-side 可视化 | 纯文本 unified diff |
| Commit 确认 | 交互式面板 + AI 生成 message | LLM 文本对话 |
| 代码审查 | 5 维度结构化报告（Bug/安全/性能/规范/测试） | 纯文本输出 |

### 6.2 技术方案

#### 6.2.0 前置修复: 删除 GitCommands.java 中冲突的 @Bean 定义

> **⚠️ CRITICAL-2 修复**: `GitCommands.java`（@Configuration）已有 `commitCommand()`（第 19-35 行）和 `reviewCommand()`（第 37-53 行）@Bean，
> 命令名分别为 `"commit"` 和 `"review"`（PROMPT 类型）。新建的 @Component `GitCommitCommand`/`GitReviewCommand`
> 同样注册名为 `"commit"`/`"review"` 会导致 `CommandRegistry` 命名冲突。
>
> **解决方案**: 删除 `GitCommands.java` 中的 `commitCommand()`（第 19-35 行）和 `reviewCommand()`（第 37-53 行）@Bean 定义，
> 用新的 @Component 类替代。新类提供更完整的功能（结构化 diff、前端确认、可视化展示）。
> 保留 GitCommands.java 中其他 @Bean：commitPushPrCommand(L55-70)、branchCommand(L72-90)、
> prCommentsCommand(L92-105)、rewindCommand(L107-122)、securityReviewCommand(L124-140)。

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/GitCommands.java`

```java
// CRITICAL-2 修复: 删除以下两个 @Bean，用新的 GitCommitCommand/GitReviewCommand @Component 替代
//
// 删除第 19-35 行:
//   @Bean Command commitCommand() { ... }  — 名称 "commit"，PROMPT 类型
//
// 删除第 37-53 行:
//   @Bean Command reviewCommand() { ... }  — 名称 "review"，PROMPT 类型
//
// 保留: commitPushPrCommand(@Bean L55-70), branchCommand(@Bean L72-90),
//        prCommentsCommand(@Bean L92-105), rewindCommand(@Bean L107-122),
//        securityReviewCommand(@Bean L124-140)
```

#### 6.2.1 后端：GitCommitCommand

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/GitCommitCommand.java`（新建）

GitCommitCommand 使用 `LOCAL` 类型而非 `PROMPT`。PROMPT 类型不应执行副作用（git commit 是不可逆操作）。无参数时返回 `jsx()` 结构化数据供前端 GitCommitPanel 渲染，用户在浏览器中确认 commit message 后再执行提交。支持 AI 自动生成 commit message（留空 message 时触发）。

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
        // workingDir null 防护
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置");
        }
        Path workDir = Path.of(context.workingDir());

        // 安全性检查：禁止在系统关键目录操作
        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        String workDirStr = normalizedWorkDir.toString();
        if (workDirStr.equals("/") || workDirStr.startsWith("/etc") || workDirStr.startsWith("/usr")) {
            return CommandResult.error("不允许在系统目录中执行 Git 操作");
        }

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

DiffCommand 改为 `LOCAL_JSX` 类型，返回 `jsx()` 结构化数据，前端渲染为可视化 diff 视图（Monaco Diff Editor side-by-side、文件分组、统计概览），远超终端原始 diff 输出。

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
        // workingDir null 防护
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置");
        }
        Path workDir = Path.of(context.workingDir());

        // 安全性检查：禁止在系统关键目录操作
        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        String workDirStr = normalizedWorkDir.toString();
        if (workDirStr.equals("/") || workDirStr.startsWith("/etc") || workDirStr.startsWith("/usr")) {
            return CommandResult.error("不允许在系统目录中执行 Git 操作");
        }

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

审查提示词使用通用表述，不引用特定模型名称，确保兼容千问/DeepSeek/GPT 等任意 LLM 后端。

```java
@Component
public class GitReviewCommand implements Command {

    private final GitService gitService;

    public GitReviewCommand(GitService gitService) {
        this.gitService = gitService;
    }

    @Override
    public String getName() { return "review"; }

    @Override
    public String getDescription() { return "AI 代码审查当前变更"; }

    @Override
    public CommandType getType() { return CommandType.PROMPT; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        // workingDir null 防护
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置");
        }
        Path workDir = Path.of(context.workingDir());

        // 安全性检查：禁止在系统关键目录操作
        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        String workDirStr = normalizedWorkDir.toString();
        if (workDirStr.equals("/") || workDirStr.startsWith("/etc") || workDirStr.startsWith("/usr")) {
            return CommandResult.error("不允许在系统目录中执行 Git 操作");
        }

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

        // 模型无关的审查提示词，不引用特定 LLM 名称
        String prompt = String.format("""
            请对以下代码变更进行审查，从以下维度评估:
            1. 🐛 Bug 风险：空指针、资源泄漏、逻辑错误
            2. 🔒 安全漏洞：注入、越权、敏感数据暴露
            3. ⚡ 性能问题：N+1 查询、内存分配、死循环
            4. 📐 代码规范：命名、结构、重复代码、单一职责
            5. 🧪 测试覆盖建议：缺失的边界场景、回归测试

            对每个发现给出严重级别（高/中/低）和具体修复建议。

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

#### 6.2.4 后端：GitService 增强 — 新增 public 方法

**文件**: `backend/src/main/java/com/aicodeassistant/service/GitService.java` — 新增 public 方法

> **必须新增**: 当前 `GitService.execGit()` 是 `private` 方法（L89），GitCommitCommand/DiffCommand/GitReviewCommand
> 无法直接调用。需新增 `public execGitPublic()` 方法作为外部调用入口。
> 另外需支持可变参数调用（`String... args`），因为 DiffCommand 传入 `new String[]{"diff", "--cached", "--stat"}`。

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

这是最大的技术超越机会。利用浏览器环境实现 GitHub/GitLab 级别的代码审查体验。项目已依赖 `@monaco-editor/react`，可直接使用 Monaco DiffEditor 实现 side-by-side diff view，这是超越 Claude Code 终端纯文本 diff 的关键差异点。

**架构设计**:
- 默认使用文件列表 + 行级着色展示（轻量级）
- 点击单个文件可展开 Monaco DiffEditor side-by-side 视图（高级模式）
- 移动端自动回退到行级着色模式（Monaco 在小屏幕不实用）

```tsx
import React, { useState, lazy, Suspense } from 'react';
import { FileText, Plus, Minus, ChevronDown, ChevronRight, GitBranch, Columns, AlignLeft } from 'lucide-react';

// Monaco DiffEditor 懒加载，避免首屏加载大包
const DiffEditor = lazy(() =>
    import('@monaco-editor/react').then(mod => ({ default: mod.DiffEditor }))
);

interface GitDiffData {
    staged: boolean;
    stat: string;
    diff: string;
    fileCount: number;
}

export const GitDiffPanel: React.FC<{ data: GitDiffData }> = ({ data }) => {
    const [expandedFiles, setExpandedFiles] = useState<Set<string>>(new Set());
    const [useMonaco, setUseMonaco] = useState(false);
    const isMobile = typeof window !== 'undefined' && window.innerWidth < 768;

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
                <div className="flex items-center gap-2">
                    {!isMobile && (
                        <button
                            onClick={() => setUseMonaco(!useMonaco)}
                            className="flex items-center gap-1 px-2 py-1 rounded text-xs bg-[var(--bg-tertiary)] hover:bg-[var(--bg-primary)] text-[var(--text-secondary)]"
                            title={useMonaco ? '切换为行级着色' : '切换为 Side-by-Side Diff'}
                        >
                            {useMonaco ? <AlignLeft size={12} /> : <Columns size={12} />}
                            {useMonaco ? 'Inline' : 'Side-by-Side'}
                        </button>
                    )}
                    <span className="text-xs text-[var(--text-muted)]">
                        {data.fileCount} 个文件变更
                    </span>
                </div>
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
                                {/* Monaco DiffEditor side-by-side 模式（桌面端 + 用户选择） */}
                                {useMonaco && !isMobile ? (
                                    <Suspense fallback={<div className="p-4 text-xs text-[var(--text-muted)]">Loading diff editor...</div>}>
                                        <DiffEditor
                                            height="300px"
                                            theme="vs-dark"
                                            original={extractOriginal(lines)}
                                            modified={extractModified(lines)}
                                            options={{
                                                readOnly: true,
                                                minimap: { enabled: false },
                                                renderSideBySide: true,
                                                fontSize: 12,
                                            }}
                                        />
                                    </Suspense>
                                ) : (
                                    /* 行级着色模式（默认 + 移动端） */
                                    lines.map((line, i) => (
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
                                    ))
                                )}
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

/** 从 diff 行中提取原始文件内容（供 Monaco DiffEditor 使用） */
function extractOriginal(lines: string[]): string {
    return lines
        .filter(l => !l.startsWith('+') || l.startsWith('+++'))
        .filter(l => !l.startsWith('@@') && !l.startsWith('---') && !l.startsWith('+++'))
        .map(l => l.startsWith('-') ? l.slice(1) : l)
        .join('\n');
}

/** 从 diff 行中提取修改后文件内容（供 Monaco DiffEditor 使用） */
function extractModified(lines: string[]): string {
    return lines
        .filter(l => !l.startsWith('-') || l.startsWith('---'))
        .filter(l => !l.startsWith('@@') && !l.startsWith('---') && !l.startsWith('+++'))
        .map(l => l.startsWith('+') ? l.slice(1) : l)
        .join('\n');
}
```

#### 6.2.6 前端：GitCommitPanel 提交确认组件

**文件**: `frontend/src/components/git/GitCommitPanel.tsx`（新建）

```tsx
import React, { useState } from 'react';
import { GitCommit, Send, FileText, RefreshCw, Sparkles } from 'lucide-react';

interface GitCommitData {
    status: string;
    stagedDiff: string;
    detailedDiff: string;
    changedFiles: string[];
    fileCount: number;
}

export const GitCommitPanel: React.FC<{
    data: GitCommitData;
    onCommit: (message: string) => void;
    onGenerateMessage?: () => Promise<string>;
}> = ({ data, onCommit, onGenerateMessage }) => {
    const [message, setMessage] = useState('');
    const [generating, setGenerating] = useState(false);

    // AI 自动生成 commit message（调用 LLM 基于 diff 内容生成）
    const handleGenerate = async () => {
        if (!onGenerateMessage) return;
        setGenerating(true);
        try {
            const generated = await onGenerateMessage();
            setMessage(generated);
        } finally {
            setGenerating(false);
        }
    };

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

            {/* Commit message input + AI 生成 */}
            <div className="space-y-2">
                <textarea
                    value={message}
                    onChange={e => setMessage(e.target.value)}
                    placeholder="输入 commit message（或点击 AI 生成）..."
                    className="w-full h-20 p-2 text-sm bg-[var(--bg-primary)] border border-[var(--border)] rounded-md text-[var(--text-primary)] resize-none focus:outline-none focus:border-blue-500"
                />
                <div className="flex items-center gap-2">
                    {onGenerateMessage && (
                        <button
                            onClick={handleGenerate}
                            disabled={generating}
                            className="flex items-center gap-1 px-3 py-1.5 rounded-md text-xs bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white"
                        >
                            {generating ? <RefreshCw size={12} className="animate-spin" /> : <Sparkles size={12} />}
                            AI 生成 Message
                        </button>
                    )}
                    <button
                        onClick={() => onCommit(message)}
                        disabled={!message.trim()}
                        className="flex items-center gap-1 px-3 py-1.5 rounded-md text-xs bg-green-600 hover:bg-green-700 disabled:opacity-50 text-white"
                    >
                        <Send size={12} />
                        提交
                    </button>
                </div>
            </div>
        </div>
    );
};
```

#### 6.2.7 前端：dispatch.ts 路由 gitDiffView 和 gitCommitPreview action

**文件**: `frontend/src/utils/dispatch.ts`

在 `command_result` handler 内，根据 `metadata.action` 路由到对应的 Git 组件：

```typescript
// dispatch.ts — Git 相关 action 路由
case 'gitDiffView': {
    const diffData = {
        staged: msg.metadata.staged as boolean,
        stat: msg.metadata.stat as string,
        diff: msg.metadata.diff as string,
        fileCount: msg.metadata.fileCount as number,
    };
    return <GitDiffPanel data={diffData} />;
}

case 'gitCommitPreview': {
    const commitData = {
        status: msg.metadata.status as string,
        stagedDiff: msg.metadata.stagedDiff as string,
        detailedDiff: msg.metadata.detailedDiff as string,
        changedFiles: msg.metadata.changedFiles as string[],
        fileCount: msg.metadata.fileCount as number,
    };
    return (
        <GitCommitPanel
            data={commitData}
            onCommit={(message) => {
                // 通过 STOMP 发送 /commit "message" 命令执行实际提交
                sendCommand(`/commit "${message.replace(/"/g, '\\"')}"`);
            }}
            onGenerateMessage={async () => {
                // 调用 LLM 基于 diff 内容生成 commit message
                const response = await fetch('/api/generate-commit-message', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ diff: commitData.detailedDiff }),
                });
                const data = await response.json();
                return data.message;
            }}
        />
    );
}
```

### 6.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 0 | **CRITICAL-2 前置**: 删除 `GitCommands.java` 中 `commitCommand()`(第19-35行)/`reviewCommand()`(第37-53行) @Bean，避免命名冲突 | 0.5h |
| 1 | 增强 GitService，暴露 execGitPublic 方法（包装已有 private execGit） | 0.5h |
| 2 | 新建 GitCommitCommand（LOCAL 类型，含安全性工作目录验证，返回 jsx() 供前端确认） | 2h |
| 3 | 重写已有 DiffCommand（LOCAL_JSX 类型，含安全性检查，返回 jsx() 可视化 diff） | 1.5h |
| 4 | 新建 GitReviewCommand（PROMPT 类型，含安全性检查，模型无关审查提示词） | 2h |
| 5 | 新建 GitDiffPanel.tsx（Monaco DiffEditor side-by-side + 行级着色回退 + 移动端适配） | 3h |
| 6 | 新建 GitCommitPanel.tsx（提交确认 + AI 生成 commit message 功能） | 2h |
| 7 | dispatch.ts 添加 gitDiffView/gitCommitPreview action 路由，完整匹配后端 data 结构 | 1h |
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
        // 无参数时返回 jsx() 类型（非 TEXT），供前端 GitCommitPanel 渲染
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
| 工作目录安全性 | 高 | 所有 Git 命令执行前验证 workDir 不在系统关键目录（/、/etc、/usr），normalize 防止路径穿越 |
| 大仓库 diff 过大 | 中 | 截断 diff 到 5000-10000 字符；`--stat` 先展示概览；前端文件级折叠展示 |
| 非 Git 仓库误调用 | 低 | 每个命令开头检查 `isGitRepository()` |
| /commit 自动提交安全性 | 中 | LOCAL 类型 + 前端 GitCommitPanel 确认后再执行，用户可审查和编辑 message |
| 命名冲突 | 高 | CRITICAL-2 修复: 删除 GitCommands.java 中旧的 commitCommand(第19-35行)/reviewCommand(第37-53行) @Bean |
| workingDir 为 null | 中 | 所有 Git 命令 execute() 开头增加 null/blank 检查 |

---

## F7: Plan Mode 规划模式 UI（2 人天）

> **v1.5 审查修正**: 补充后端 WebSocket `sendPlanUpdate` 推送方法、前端 `dispatch.ts` 路由代码、移动端响应式断点方案、
> PlanPanel checklist 交互和拖拽排序说明，以及 `/plan` 命令后端 `PlanCommand.java` 完整代码。
> 对标 Claude Code Plan Mode，强调 ZhikuCode 内嵌编辑器优势。

### 7.1 现状分析

**已有基础设施**:
- `taskStore.ts` 已支持任务管理（addTask/updateTask/removeTask），含 `foregroundedTaskId` 和 `viewingAgentTaskId`
- `ServerMessage.TaskUpdate`（#18）已定义任务状态更新
- `sessionStore.ts` 已有 `status` 字段（idle/streaming/waiting_permission/compacting）
- 后端 `QueryEngine` 支持多轮循环、工具编排
- `WebSocketController` 已有 25 种 Server→Client 推送方法（L174-383），`dispatch.ts` 已有完整路由表
- 前端已存在 `PermissionMode = 'plan'` 枚举值（`types/index.ts` L322），SettingsPanel 中已支持 plan 模式选择

**缺失部分**:
- 无独立的 `planStore.ts`（前端无 Plan Mode 状态管理）
- 无 `PlanPanel` 组件（无 `plan/` 目录）
- WebSocket 无 `plan_update` 消息类型（后端无 `sendPlanUpdate` 方法）
- `dispatch.ts` 无 `plan_update` 处理器
- 无 `/plan` 斜杠命令和后端 `PlanCommand.java`

**对标 Claude Code Plan Mode**:
- Claude Code 的 Plan Mode 依赖外部编辑器（VS Code/JetBrains）展示规划，用户需在终端和编辑器之间切换
- ZhikuCode 作为浏览器内嵌 IDE，可在同一窗口内展示 PlanPanel 侧边栏 + 代码编辑器 + 对话区，无需窗口切换
- 应充分利用此内嵌优势：plan items 可交互勾选、拖拽排序、实时进度可视化

### 7.2 技术方案

#### 7.2.1 前端：planStore — 规划模式状态管理

**文件**: `frontend/src/store/planStore.ts`（新建）

> Store 设计风格与现有 `taskStore.ts` 完全一致（subscribeWithSelector + immer 中间件）。

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
    checked?: boolean; // checklist 勾选状态（用户手动标记）
}

export interface PlanSnapshot {
    id: string;
    planName: string;
    steps: PlanStep[];
    createdAt: number;
}

export interface PlanStoreState {
    isPlanMode: boolean;
    planName: string;
    planOverview: string;
    steps: PlanStep[];
    currentStepId: string | null;
    history: PlanSnapshot[]; // plan 版本历史快照

    enablePlanMode: (name: string, overview: string) => void;
    disablePlanMode: () => void;
    setSteps: (steps: PlanStep[]) => void;
    updateStepStatus: (stepId: string, status: PlanStep['status']) => void;
    setCurrentStep: (stepId: string | null) => void;
    addStep: (step: PlanStep) => void;
    removeStep: (stepId: string) => void;
    toggleStepChecked: (stepId: string) => void;
    reorderSteps: (fromIndex: number, toIndex: number) => void;
    saveSnapshot: () => void;
    restoreSnapshot: (snapshotId: string) => void;
}

export const usePlanStore = create<PlanStoreState>()(
    subscribeWithSelector(immer((set, get) => ({
        isPlanMode: false,
        planName: '',
        planOverview: '',
        steps: [],
        currentStepId: null,
        history: [],

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
        toggleStepChecked: (id) => set(d => {
            const step = d.steps.find(s => s.id === id);
            if (step) step.checked = !step.checked;
        }),
        reorderSteps: (fromIndex, toIndex) => set(d => {
            const [moved] = d.steps.splice(fromIndex, 1);
            d.steps.splice(toIndex, 0, moved);
        }),
        saveSnapshot: () => set(d => {
            d.history.push({
                id: crypto.randomUUID(),
                planName: d.planName,
                steps: JSON.parse(JSON.stringify(d.steps)),
                createdAt: Date.now(),
            });
        }),
        restoreSnapshot: (snapshotId) => {
            const snapshot = get().history.find(h => h.id === snapshotId);
            if (snapshot) {
                set(d => { d.steps = JSON.parse(JSON.stringify(snapshot.steps)); });
            }
        },
    })))
);
```

#### 7.2.2 后端：WebSocketController 新增 `sendPlanUpdate` 推送方法（#26）

**文件**: `backend/src/main/java/com/aicodeassistant/websocket/WebSocketController.java`

> 在现有 25 种推送方法（L174-383）之后新增第 26 种：

```java
// ───── #26: planStore ─────

/** #26 Plan Mode 状态更新 */
public void sendPlanUpdate(String sessionId, Map<String, Object> planData) {
    push(sessionId, "plan_update", planData);
}
```

#### 7.2.3 前端：dispatch.ts 新增 `plan_update` 处理器

**文件**: `frontend/src/api/dispatch.ts`

> 在现有路由表尾部（`workflow_phase_update` 之后）新增：

```typescript
import { usePlanStore, type PlanStep } from '@/store/planStore';

// 在 handlers 对象中追加:
'plan_update': (d: {
    isPlanMode: boolean;
    planName?: string;
    planOverview?: string;
    steps?: PlanStep[];
    currentStepId?: string;
}) => {
    const store = usePlanStore.getState();
    if (d.isPlanMode !== undefined) {
        d.isPlanMode
            ? store.enablePlanMode(d.planName || '', d.planOverview || '')
            : store.disablePlanMode();
    }
    if (d.steps) store.setSteps(d.steps);
    if (d.currentStepId) store.setCurrentStep(d.currentStepId);
},
```

#### 7.2.4 后端：PlanCommand.java — `/plan` 斜杠命令

**文件**: `backend/src/main/java/com/aicodeassistant/command/impl/PlanCommand.java`（新建）

```java
package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.websocket.WebSocketController;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * /plan 命令 — 切换 Plan Mode 规划模式。
 * 用法:
 *   /plan on [planName]  — 进入规划模式
 *   /plan off            — 退出规划模式
 *   /plan                — 切换当前模式
 */
@Component
public class PlanCommand implements Command {

    private final WebSocketController wsController;

    public PlanCommand(WebSocketController wsController) {
        this.wsController = wsController;
    }

    @Override
    public String getName() { return "plan"; }

    @Override
    public String getDescription() { return "Toggle Plan Mode for step-by-step task planning"; }

    @Override
    public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext ctx) {
        String sessionId = ctx.sessionId();
        String trimmed = (args != null) ? args.trim() : "";

        if (trimmed.startsWith("on")) {
            String planName = trimmed.length() > 2 ? trimmed.substring(3).trim() : "New Plan";
            wsController.sendPlanUpdate(sessionId, Map.of(
                    "isPlanMode", true,
                    "planName", planName,
                    "planOverview", ""
            ));
            return CommandResult.text("Plan Mode enabled: " + planName);
        } else if (trimmed.equals("off")) {
            wsController.sendPlanUpdate(sessionId, Map.of("isPlanMode", false));
            return CommandResult.text("Plan Mode disabled");
        } else {
            // toggle
            wsController.sendPlanUpdate(sessionId, Map.of(
                    "isPlanMode", true,
                    "planName", trimmed.isEmpty() ? "New Plan" : trimmed,
                    "planOverview", ""
            ));
            return CommandResult.text("Plan Mode toggled");
        }
    }
}
```

#### 7.2.5 前端：PlanPanel 组件（含响应式布局 + checklist 交互）

**文件**: `frontend/src/components/plan/PlanPanel.tsx`（新建）

> **响应式断点策略**（ZhikuCode 浏览器内嵌优势）:
> - 桌面端（>=1024px）：侧边栏常驻 `w-80`（320px），与主内容区并列
> - 平板端（768-1023px）：侧边栏折叠为图标条 `w-10`（40px），hover 展开
> - 手机端（<768px）：抽屉模式，底部滑出面板或全屏遮罩

```tsx
import React, { useState, useEffect } from 'react';
import { usePlanStore, type PlanStep } from '@/store/planStore';
import {
    CheckCircle, Circle, Loader2, XCircle,
    FileText, Clock, ChevronLeft, ChevronRight,
    GripVertical, History, CheckSquare, Square
} from 'lucide-react';

const StatusIcon: React.FC<{ status: PlanStep['status'] }> = ({ status }) => {
    switch (status) {
        case 'completed': return <CheckCircle size={16} className="text-green-400" />;
        case 'in_progress': return <Loader2 size={16} className="text-blue-400 animate-spin" />;
        case 'failed': return <XCircle size={16} className="text-red-400" />;
        default: return <Circle size={16} className="text-gray-500" />;
    }
};

/** 响应式断点 hook */
function useBreakpoint() {
    const [bp, setBp] = useState<'mobile' | 'tablet' | 'desktop'>('desktop');
    useEffect(() => {
        const check = () => {
            const w = window.innerWidth;
            setBp(w < 768 ? 'mobile' : w < 1024 ? 'tablet' : 'desktop');
        };
        check();
        window.addEventListener('resize', check);
        return () => window.removeEventListener('resize', check);
    }, []);
    return bp;
}

export const PlanPanel: React.FC = () => {
    const {
        isPlanMode, planName, planOverview, steps,
        currentStepId, toggleStepChecked, history, saveSnapshot
    } = usePlanStore();
    const [collapsed, setCollapsed] = useState(false);
    const [showHistory, setShowHistory] = useState(false);
    const breakpoint = useBreakpoint();

    if (!isPlanMode) return null;

    const completed = steps.filter(s => s.status === 'completed').length;
    const checked = steps.filter(s => s.checked).length;
    const total = steps.length;
    const progress = total > 0 ? (completed / total) * 100 : 0;

    // 手机端：底部抽屉模式
    if (breakpoint === 'mobile') {
        return (
            <div className="fixed inset-x-0 bottom-0 z-40 bg-[var(--bg-secondary)] border-t border-[var(--border)]
                            max-h-[60vh] overflow-y-auto rounded-t-xl shadow-2xl">
                <div className="sticky top-0 bg-[var(--bg-secondary)] p-3 border-b border-[var(--border)]">
                    <div className="w-10 h-1 bg-gray-600 rounded-full mx-auto mb-2" />
                    <h3 className="text-sm font-semibold text-[var(--text-primary)]">
                        📋 {planName} ({completed}/{total})
                    </h3>
                    <div className="mt-2 h-1.5 bg-gray-700 rounded-full overflow-hidden">
                        <div className="h-full bg-blue-500 rounded-full transition-all duration-300"
                             style={{ width: `${progress}%` }} />
                    </div>
                </div>
                <div className="p-2 space-y-1">
                    {steps.map((step) => (
                        <StepItem key={step.id} step={step} currentStepId={currentStepId}
                                  onToggle={() => toggleStepChecked(step.id)} />
                    ))}
                </div>
            </div>
        );
    }

    // 平板端：折叠为图标条
    if (breakpoint === 'tablet' && collapsed) {
        return (
            <div className="border-l border-[var(--border)] w-10 bg-[var(--bg-secondary)] flex flex-col items-center py-2">
                <button onClick={() => setCollapsed(false)}
                        className="p-1 hover:bg-[var(--bg-tertiary)] rounded">
                    <ChevronLeft size={16} />
                </button>
                <div className="mt-2 text-xs text-[var(--text-muted)] writing-mode-vertical">
                    {completed}/{total}
                </div>
            </div>
        );
    }

    // 桌面端 + 平板端展开：侧边栏 w-80
    return (
        <div className="border-l border-[var(--border)] w-80 bg-[var(--bg-secondary)] flex flex-col">
            {/* Header */}
            <div className="p-3 border-b border-[var(--border)]">
                <div className="flex items-center justify-between">
                    <h3 className="text-sm font-semibold text-[var(--text-primary)]">
                        📋 {planName}
                    </h3>
                    <div className="flex items-center gap-1">
                        <button onClick={() => { saveSnapshot(); }}
                                title="Save snapshot"
                                className="p-1 hover:bg-[var(--bg-tertiary)] rounded text-[var(--text-muted)]">
                            <History size={14} />
                        </button>
                        {breakpoint === 'tablet' && (
                            <button onClick={() => setCollapsed(true)}
                                    className="p-1 hover:bg-[var(--bg-tertiary)] rounded text-[var(--text-muted)]">
                                <ChevronRight size={14} />
                            </button>
                        )}
                    </div>
                </div>
                <p className="text-xs text-[var(--text-muted)] mt-1">{planOverview}</p>
                {/* Progress bar */}
                <div className="mt-2 h-1.5 bg-gray-700 rounded-full overflow-hidden">
                    <div className="h-full bg-blue-500 rounded-full transition-all duration-300"
                         style={{ width: `${progress}%` }} />
                </div>
                <div className="flex justify-between mt-1">
                    <span className="text-xs text-[var(--text-muted)]">
                        {completed}/{total} 步骤完成
                    </span>
                    <span className="text-xs text-[var(--text-muted)]">
                        ✅ {checked}/{total} 已勾选
                    </span>
                </div>
            </div>

            {/* Version history toggle */}
            {history.length > 0 && showHistory && (
                <div className="border-b border-[var(--border)] p-2 space-y-1 max-h-32 overflow-y-auto">
                    <div className="text-xs text-[var(--text-muted)] font-medium">版本历史</div>
                    {history.map(snap => (
                        <button key={snap.id}
                                onClick={() => usePlanStore.getState().restoreSnapshot(snap.id)}
                                className="w-full text-left px-2 py-1 text-xs rounded hover:bg-[var(--bg-tertiary)]
                                           text-[var(--text-secondary)]">
                            {snap.planName} — {new Date(snap.createdAt).toLocaleTimeString()}
                        </button>
                    ))}
                </div>
            )}
            {history.length > 0 && (
                <button onClick={() => setShowHistory(!showHistory)}
                        className="text-xs text-blue-400 hover:text-blue-300 px-3 py-1">
                    {showHistory ? '隐藏历史' : `📜 ${history.length} 个版本快照`}
                </button>
            )}

            {/* Step list with drag handle + checklist */}
            <div className="flex-1 overflow-y-auto p-2 space-y-1">
                {steps.map((step) => (
                    <StepItem key={step.id} step={step} currentStepId={currentStepId}
                              onToggle={() => toggleStepChecked(step.id)} showDragHandle />
                ))}
            </div>
        </div>
    );
};

/** 单个步骤条目（复用于桌面端和手机端） */
const StepItem: React.FC<{
    step: PlanStep;
    currentStepId: string | null;
    onToggle: () => void;
    showDragHandle?: boolean;
}> = ({ step, currentStepId, onToggle, showDragHandle }) => (
    <div className={`p-2 rounded-lg text-sm transition-colors
        ${step.id === currentStepId
            ? 'bg-blue-600/10 border border-blue-600/30'
            : 'hover:bg-[var(--bg-tertiary)]'}`}>
        <div className="flex items-start gap-2">
            {showDragHandle && (
                <GripVertical size={14} className="text-gray-600 cursor-grab mt-0.5 flex-shrink-0" />
            )}
            {/* Checklist toggle */}
            <button onClick={onToggle} className="flex-shrink-0 mt-0.5">
                {step.checked
                    ? <CheckSquare size={16} className="text-green-400" />
                    : <Square size={16} className="text-gray-500" />}
            </button>
            <StatusIcon status={step.status} />
            <div className="flex-1 min-w-0">
                <div className={`text-[var(--text-primary)] truncate
                    ${step.checked ? 'line-through opacity-60' : ''}`}>
                    {step.title}
                </div>
                {step.description && (
                    <div className="text-xs text-[var(--text-muted)] mt-0.5 line-clamp-2">
                        {step.description}
                    </div>
                )}
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
);
```

> **拖拽排序说明（可选增强）**: 上述代码中 `GripVertical` 图标作为拖拽把手的占位 UI。
> 实际拖拽功能可通过 `@dnd-kit/sortable` 或 HTML5 Drag & Drop API 实现，
> 调用 `usePlanStore.getState().reorderSteps(fromIndex, toIndex)` 更新顺序。
> 作为 P2 功能的可选增强，初期可仅展示把手图标而不实现拖拽逻辑。

### 7.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 新建 `planStore.ts` 状态管理（含 checklist/reorder/snapshot） | 1.5h |
| 2 | 新建 `PlanPanel.tsx` 侧边面板组件（含三段响应式断点） | 3h |
| 3 | 集成到 AppLayout，根据 isPlanMode 条件渲染 | 1h |
| 4 | 后端 WebSocketController 新增 `sendPlanUpdate`（#26），新建 `PlanCommand.java` | 1.5h |
| 5 | `dispatch.ts` 添加 `plan_update` 处理器 + import planStore | 0.5h |
| 6 | 样式调优、动画、checklist 交互测试 | 1h |
| 7 | 测试验证（桌面/平板/手机三种布局） | 1.5h |

### 7.4 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 侧边面板在小屏幕上挤压主内容区 | 中 | 三段响应式断点（桌面常驻/平板折叠/手机抽屉），见 7.2.5 |
| 步骤状态与实际执行不同步 | 中 | 通过 WebSocket `plan_update` 消息实时同步；步骤 ID 与工具调用关联 |
| Plan Mode 下工具执行权限 | 中 | 后端 QueryEngine 根据 Plan Mode 状态限制工具执行（仅允许只读工具，禁止 FileWrite/FileEdit/BashTool 等） |
| 拖拽排序引入额外依赖 | 低 | 初期仅展示 GripVertical 图标，拖拽功能作为可选增强，需时再引入 @dnd-kit/sortable |
| Plan 版本历史内存占用 | 低 | history 数组仅保留最近 20 个快照，超出自动淘汰最旧记录 |

---

## F8: 文件变更可视化 Dashboard（2.5 人天）

> **v1.5 审查修正**: 统一前端变量名为 `messageId`（消除 `snapshotId` 混用），标注分页/行级 diff 为后续优化项，
> 增加移动端适配方案，明确 react-window 为可选优化。

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

**已有 API 局限性说明**:

| 局限 | 说明 | 建议 |
|------|------|--------|
| 无分页支持 | `listSnapshots` 无 limit/offset 参数，快照量大时响应可能变慢 | 后续优化项：新增 `?limit=50&offset=0` 参数 |
| 无行级 diff 内容 | `getDiffStats` 仅返回统计数据（filesAdded/Modified/Deleted/changedFiles），不返回实际 diff 文本 | 后续优化项：新增 `GET /api/sessions/{id}/history/diff-content` 返回 unified diff 文本 |
| messageId 分组粒度 | 每个 messageId 分组通过 `List.of(...)` 只返回一个 SnapshotSummary，多文件变更时缺少细粒度 | 可选优化：返回每个文件独立的 SnapshotSummary |

### 8.2 技术方案

#### 8.2.1 后端：FileHistoryController 已存在

> `FileHistoryController.java`（99 行）已存在于 `backend/src/main/java/com/aicodeassistant/controller/FileHistoryController.java`。
> 前端直接对接即可，无需新建。

**已有端点**:
- `GET /api/sessions/{sessionId}/history/snapshots` — 返回 `Map<messageId, List<SnapshotSummary>>`
- `POST /api/sessions/{sessionId}/history/rewind` — 接收 `{ messageId, filePaths }`，返回 `RewindResponse(success, restoredFiles, skippedFiles, errors)`
- `GET /api/sessions/{sessionId}/history/diff?fromMessageId=&toMessageId=` — 返回 `DiffStatsResponse(filesAdded, filesModified, filesDeleted, changedFiles)`

**SnapshotSummary record 字段对照**:
```java
// 后端 record 定义（字段名必须与前端一致）
public record SnapshotSummary(
    String messageId,          // ✅ 前端统一使用 snap.messageId
    List<String> trackedFiles, // ✅ 前端使用 snap.trackedFiles（非 snap.files）
    int fileCount,             // ✅ 前端使用 snap.fileCount
    String timestamp           // ✅ 实际类型为 String（非 Instant），源码确认于 FileHistoryController.java L91-92
) {}
```

#### 8.2.2 前端：FileChangesDashboard 组件（含移动端适配）

**文件**: `frontend/src/components/dashboard/FileChangesDashboard.tsx`（新建）

> **移动端适配策略**:
> - 桌面端（>=768px）：左侧文件列表 `w-64` + 右侧 diff 视图并列
> - 手机端（<768px）：Tab 切换模式（"文件列表" / "Diff 视图" 两个 Tab）

```tsx
import React, { useEffect, useState } from 'react';
import { FileText, Clock } from 'lucide-react';

interface SnapshotEntry {
    messageId: string;
    operation: string;
    timestamp: string;
}

export const FileChangesDashboard: React.FC<{ sessionId: string }> = ({ sessionId }) => {
    const [changes, setChanges] = useState<Map<string, SnapshotEntry[]>>(new Map());
    const [selectedFile, setSelectedFile] = useState<string | null>(null);
    const [diffStats, setDiffStats] = useState<object | null>(null);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState<'files' | 'diff'>('files');

    // 对接已有后端 API: /api/sessions/{sessionId}/history/snapshots
    useEffect(() => {
        fetch(`/api/sessions/${sessionId}/history/snapshots`)
            .then(r => r.json())
            .then(data => {
                // data 格式: Map<messageId, List<SnapshotSummary>>
                const fileMap = new Map<string, SnapshotEntry[]>();
                Object.values(data).flat().forEach((snap: any) => {
                    for (const file of snap.trackedFiles || []) {
                        if (!fileMap.has(file)) fileMap.set(file, []);
                        fileMap.get(file)!.push({
                            messageId: snap.messageId,
                            operation: 'edit',
                            timestamp: snap.timestamp,
                        });
                    }
                });
                setChanges(fileMap);
                setLoading(false);
            })
            .catch(() => setLoading(false));
    }, [sessionId]);

    // 对接已有后端 API: /api/sessions/{sessionId}/history/diff
    // 注意: getDiffStats 仅返回统计数据，不返回行级 diff 内容
    const loadDiffStats = async (filePath: string, fromMessageId: string) => {
        const resp = await fetch(
            `/api/sessions/${sessionId}/history/diff?fromMessageId=${fromMessageId}&toMessageId=current`);
        const data = await resp.json();
        setDiffStats(data);
        setSelectedFile(filePath);
        setActiveTab('diff'); // 手机端自动切换到 diff tab
    };

    if (loading) return <div className="p-4 text-sm text-[var(--text-muted)]">加载中...</div>;

    // 手机端: Tab 切换模式
    const isMobile = typeof window !== 'undefined' && window.innerWidth < 768;

    const fileList = (
        <div className={isMobile ? '' : 'w-64 border-r border-[var(--border)]'} >
            <div className="p-3 border-b border-[var(--border)]">
                <h3 className="text-sm font-semibold">📁 变更文件 ({changes.size})</h3>
            </div>
            <div className="overflow-y-auto">
                {Array.from(changes.entries()).map(([path, snaps]) => (
                    <button
                        key={path}
                        onClick={() => snaps[0] && loadDiffStats(path, snaps[0].messageId)}
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
        </div>
    );

    const diffView = (
        <div className="flex-1 overflow-auto p-4">
            {diffStats ? (
                <div className="space-y-3">
                    <h4 className="text-sm font-semibold text-[var(--text-primary)]">
                        📊 {selectedFile} 变更统计
                    </h4>
                    <pre className="text-xs font-mono bg-[var(--bg-tertiary)] p-3 rounded-lg">
                        {JSON.stringify(diffStats, null, 2)}
                    </pre>
                    <p className="text-xs text-[var(--text-muted)]">
                        注意: 当前 API 仅返回变更统计（filesAdded/Modified/Deleted）。
                        行级 diff 内容展示需后续新增专用端点，见 8.1 局限性说明。
                    </p>
                </div>
            ) : (
                <div className="flex items-center justify-center h-full text-[var(--text-muted)]">
                    选择一个文件查看变更统计
                </div>
            )}
        </div>
    );

    if (isMobile) {
        return (
            <div className="flex flex-col h-full">
                {/* Tab bar */}
                <div className="flex border-b border-[var(--border)]">
                    <button onClick={() => setActiveTab('files')}
                            className={`flex-1 py-2 text-xs font-medium text-center
                                ${activeTab === 'files' ? 'text-blue-400 border-b-2 border-blue-400' : 'text-[var(--text-muted)]'}`}>
                        文件列表
                    </button>
                    <button onClick={() => setActiveTab('diff')}
                            className={`flex-1 py-2 text-xs font-medium text-center
                                ${activeTab === 'diff' ? 'text-blue-400 border-b-2 border-blue-400' : 'text-[var(--text-muted)]'}`}>
                        Diff 视图
                    </button>
                </div>
                {activeTab === 'files' ? fileList : diffView}
            </div>
        );
    }

    // 桌面端: 左右并列
    return (
        <div className="flex h-full">
            {fileList}
            {diffView}
        </div>
    );
};
```

### 8.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 确认已有 FileHistoryController 端点满足需求，补充缺失的查询接口（如按文件分组查询） | 2h |
| 2 | 新建 `FileChangesDashboard.tsx` 主面板，对接已有 API，统一使用 `messageId` 变量名 | 6h |
| 3 | 实现变更统计展示（基于 DiffStatsResponse 的统计卡片） | 3h |
| 4 | 移动端 Tab 切换布局适配 | 2h |
| 5 | 集成到主界面（作为可切换面板或路由） | 1h |
| 6 | 前端组件测试 | 2h |

**后续优化项（不计入当前工作量）**:
- 新增行级 diff 内容 API 端点，前端展示行级着色 diff
- `listSnapshots` 新增分页参数 `limit`/`offset`
- 大规模 diff（>1000 行）引入 react-window 虚拟滚动（正常规模原生渲染足够）

### 8.4 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 快照数据量大 | 中 | 前端分页加载（默认 50 条） + 快照内容按需加载；后续后端新增 limit/offset 参数 |
| Diff 渲染性能 | 低 | 正常规模（<1000 行）原生渲染足够；大规模场景可引入 react-window（可选优化，非必须依赖） |
| 安全：快照内容包含敏感信息 | 低 | 仅限本地访问；快照存储在 .ai-code-assistant/data.db |

---

# F9: Skills 技能系统 — 已实现，UI 增强（1 人天）

> **v1.5 审查修正**: 修正来源数量描述为“6 种定义，3 种已实现”，补充 CommandPalette 完整集成代码、
> 技能详情弹窗、`/skill {name}` 执行能力说明。

### 9.1 现状分析

**已完整实现的后端组件**:

| 组件 | 文件 | 行数 | 功能 |
|------|------|------|------|
| `SkillRegistry` | `backend/.../skill/SkillRegistry.java` | 370 | 技能发现/加载/注册/解析，6 种来源定义（其中 3 种已实现加载：BUNDLED/USER/PROJECT；MANAGED/PLUGIN/MCP 预留接口） |
| `SkillTool` | `backend/.../skill/SkillTool.java` | 128 | 作为 Tool 接口实现注册到 ToolRegistry，由 SkillExecutor 执行，供 LLM 调用 |
| `SkillDefinition` | `backend/.../skill/SkillDefinition.java` | 96 | 技能定义 record，含 `name`/`fileName`/`frontmatter(FrontmatterData)`/`content`/`source(SkillSource)`/`filePath`（**注意: filePath 类型为 String，非 Path**） |
| `SkillExecutor` | `backend/.../skill/SkillExecutor.java` | — | 技能执行器，被 SkillTool 调用。不存在独立的 SkillCommand 类 |

**技能来源支持详情**:

| 来源类型 | 状态 | 说明 |
|----------|------|------|
| BUNDLED | ✅ 已实现 | 内置技能，随应用分发 |
| USER | ✅ 已实现 | 用户级技能，存储于 `~/.qoder/skills/` |
| PROJECT | ✅ 已实现 | 项目级技能，存储于 `.qoder/skills/` |
| MANAGED | ⚠️ 预留 | enum 中定义，无加载代码（企业管理场景预留） |
| PLUGIN | ⚠️ 预留 | enum 中定义，无加载代码（插件系统预留） |
| MCP | ⚠️ 预留 | enum 中定义，无加载代码（MCP 集成预留） |

**已有功能特性**:
- **WatchService 热加载**: 技能文件变更后自动重新加载，无需重启
- **Markdown front-matter 格式**: 支持 name/description/args 定义 + prompt 模板
- **模板渲染**: `{{arg}}` 占位符替换
- **ToolRegistry 集成**: 技能自动注册为可调用工具

**真正缺失的部分**:
- 前端无技能浏览/管理 UI（用户只能通过 `/skill` 命令文本交互）
- CommandPalette（177 行，已支持 `cmd.group` 分组）中未动态展示可用技能列表
- 无技能详情弹窗（查看参数、描述、示例）
- 无前端技能执行能力（需通过 stompClient 发送 `/skill {name}` 到后端）
- 无技能执行历史/统计

### 9.2 技术方案

#### 9.2.1 后端：SkillController REST 端点

> SkillController 当前不存在，需新建。代码约 40 行，包含列表和详情两个端点。

**文件**: `backend/src/main/java/com/aicodeassistant/controller/SkillController.java`（新建）

```java
package com.aicodeassistant.controller;

import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;

    public SkillController(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /** 列出所有已注册技能（用于 CommandPalette 动态加载） */
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

    /** 获取单个技能详情（用于技能详情弹窗） */
    @GetMapping("/{name}")
    public Map<String, Object> getSkillDetail(@PathVariable String name) {
        return skillRegistry.getAllSkills().stream()
            .filter(s -> s.effectiveName().equals(name))
            .findFirst()
            .map(s -> Map.<String, Object>of(
                "name", s.effectiveName(),
                "description", s.effectiveDescription(),
                "source", s.source().name(),
                "content", s.content(),
                "filePath", s.filePath() != null ? s.filePath() : ""  // filePath 已是 String 类型，无需 .toString()
            ))
            .orElse(Map.of("error", "Skill not found: " + name));
    }
}
```

#### 9.2.2 前端：CommandPalette 技能列表集成

在已有的 `CommandPalette.tsx`（177 行）的父组件中动态加载技能并合并到命令列表：

```tsx
// 在 CommandPalette 的父组件中（如 ChatInput.tsx 或 App.tsx）
import { useState, useEffect } from 'react';
import type { Command } from '@/types';
import { Zap } from 'lucide-react';

interface SkillItem {
    name: string;
    description: string;
    source: string;
}

// 动态加载技能列表并合并到 commands
const [skills, setSkills] = useState<SkillItem[]>([]);

useEffect(() => {
    fetch('/api/skills')
        .then(r => r.json())
        .then((data: SkillItem[]) => setSkills(data))
        .catch(() => {});
}, []);

// 将技能转换为 Command 格式，利用已有的 cmd.group 分组能力
const skillCommands: Command[] = skills.map(s => ({
    name: `skill ${s.name}`,
    description: s.description,
    group: 'Skills',
    hidden: false,
}));

// 合并到 commands prop
const allCommands = [...builtinCommands, ...skillCommands];

// 传入 CommandPalette:
// <CommandPalette commands={allCommands} ... />
```

> CommandPalette 已支持 `cmd.group` 分组渲染（L87-95），技能会自动归类到 "Skills" 分组下。

#### 9.2.3 前端：技能详情弹窗

**文件**: `frontend/src/components/skills/SkillDetailModal.tsx`（新建）

```tsx
import React, { useEffect, useState } from 'react';
import { X, Zap, Play } from 'lucide-react';

interface SkillDetail {
    name: string;
    description: string;
    source: string;
    content: string;
    filePath: string;
}

export const SkillDetailModal: React.FC<{
    skillName: string;
    onClose: () => void;
    onExecute: (name: string) => void;
}> = ({ skillName, onClose, onExecute }) => {
    const [detail, setDetail] = useState<SkillDetail | null>(null);

    useEffect(() => {
        fetch(`/api/skills/${skillName}`)
            .then(r => r.json())
            .then(setDetail)
            .catch(() => {});
    }, [skillName]);

    if (!detail) return null;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
            <div className="bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-2xl
                            w-full max-w-lg mx-4 max-h-[70vh] overflow-hidden flex flex-col"
                 onClick={e => e.stopPropagation()}>
                {/* Header */}
                <div className="p-4 border-b border-[var(--border)] flex items-center justify-between">
                    <div className="flex items-center gap-2">
                        <Zap size={18} className="text-yellow-400" />
                        <h3 className="text-sm font-semibold text-[var(--text-primary)]">{detail.name}</h3>
                        <span className="text-xs px-1.5 py-0.5 rounded bg-gray-700 text-gray-400">{detail.source}</span>
                    </div>
                    <button onClick={onClose} className="p-1 hover:bg-[var(--bg-tertiary)] rounded">
                        <X size={16} />
                    </button>
                </div>
                {/* Body */}
                <div className="flex-1 overflow-y-auto p-4 space-y-3">
                    <p className="text-sm text-[var(--text-secondary)]">{detail.description}</p>
                    <div className="text-xs text-[var(--text-muted)]">文件: {detail.filePath}</div>
                    <pre className="text-xs font-mono bg-[var(--bg-tertiary)] p-3 rounded-lg overflow-x-auto
                                    text-[var(--text-secondary)]">
                        {detail.content}
                    </pre>
                </div>
                {/* Footer */}
                <div className="p-3 border-t border-[var(--border)] flex justify-end">
                    <button onClick={() => onExecute(detail.name)}
                            className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded-lg
                                       bg-blue-600 hover:bg-blue-500 text-white transition-colors">
                        <Play size={12} />
                        执行技能
                    </button>
                </div>
            </div>
        </div>
    );
};
```

#### 9.2.4 技能执行能力

技能执行通过已有的 WebSocket STOMP 通道发送 `/skill {name}` 命令到后端：

```typescript
// 在 SkillDetailModal 或 CommandPalette 的 onSelect 回调中:
const executeSkill = (skillName: string) => {
    // 通过已有的 stompClient 发送斜杠命令
    stompClient.publish({
        destination: '/app/command',
        body: JSON.stringify({ command: 'skill', args: skillName }),
    });
};
```

> 后端 `SkillTool` 已注册到 `ToolRegistry`，前端发送的 `/skill {name}` 命令会通过 `CommandRouter` 路由到对应的 `SkillTool` 执行。
> 执行结果通过 `command_result` 消息类型推送回前端（已在 dispatch.ts 中处理）。

### 9.3 实现步骤

| 步骤 | 内容 | 耗时 |
|------|------|------|
| 1 | 新建 `SkillController.java`（列表 + 详情 两个端点） | 1h |
| 2 | CommandPalette 父组件集成技能列表展示（fetch + commands.concat） | 2h |
| 3 | 新建 `SkillDetailModal.tsx` 技能详情弹窗 | 1.5h |
| 4 | 对接 stompClient 实现 `/skill {name}` 执行能力 | 0.5h |
| 5 | 创建 2-3 个示例技能文件到 `.qoder/skills/` | 0.5h |
| 6 | 前端组件测试 | 1.5h |

### 9.4 风险分析

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 技能列表加载延迟 | 低 | 技能数量通常 < 20，响应极快 |
| 前端与后端技能格式不一致 | 低 | 直接使用 SkillRegistry 返回的标准格式 |
| MANAGED/PLUGIN/MCP 来源未实现 | 低 | 当前仅展示 BUNDLED/USER/PROJECT 来源的技能，其他来源待后续版本实现 |

---

## 附录 A: 工作量汇总

| 功能 | 后端 | 前端 | 测试 | 总计 | 审查备注 |
|------|------|------|------|------|----------|
| **G0: JSX/COMPACT 推送修复** | **1h** | **0.5h** | **1h** | **0.5 天** | **CRITICAL-1 前置修复，F3/F4/F6 依赖** |
| F1: /compact 命令 | 2.5h | 1h | 2h | **1.5 天** | 改用 SessionManager 获取消息；前端 dispatch 已在 G0 统一修复 |
| F2: 成本/Token 显示 | 0.5h | 1.5h | 1h | **0.5 天** | **P0 最高 ROI**；核心修复仅 2 行；costHistory/budgetUsd 为 Phase 2 可选 |
| F3: zhikun.md 记忆 | 5h | 4h | 4h | **2.5 天** | 含移动端适配、符号链接防御、react-markdown、SettingsPanel 集成、WebSocket 集成 |
| F4: /doctor 诊断 | 1.5h | 2.5h | 1h | **1 天** | 增强 DoctorCommand |
| F5: WebFetchTool | — | — | — | **0 天** | ✅ 已实现（358 行），无需开发 |
| F6: Git 深度集成 | 6.5h | 6.5h | 5h | **3.5 天** | CRITICAL-2 前置删除 @Bean + 测试修正 + 安全防护 |
| F7: Plan Mode UI | 2.5h | 6.5h | 1.5h | **2 天** | 含 WebSocket #26 sendPlanUpdate、PlanCommand、三段响应式断点、checklist/拖拽排序、版本快照 |
| F8: 文件变更 Dashboard | 1h | 10h | 3h | **2.5 天** | 后端已有 FileHistoryController；移动端 Tab 切换布局；行级 diff/分页为后续优化项 |
| F9: Skills UI 增强 | 1.5h | 4h | 1.5h | **1 天** | ✅ 后端已实现（370+128+96 行）；新增 SkillController + SkillDetailModal + 执行能力 |
| **合计** | **22h** | **37h** | **20h** | **≈ 15 人天** |

## 附录 B: 技术栈依赖矩阵

| 功能 | Java 后端 | React 前端 | Python 服务 | 新依赖 |
|------|-----------|------------|-------------|--------|
| **G0** | **WebSocketController (handleSlashCommand 重写)** | **dispatch.ts (command_result 增强), types/index.ts (Message 类型扩展 metadata)** | — | 无 |
| F1 | CompactService, CommandRegistry | dispatch.ts, messageStore | — | 无 |
| F2 | CostTrackerService, WsMessageHandler (修 onUsage) | StatusBar.tsx (增强) | — | 无 |
| F3 | MemoryCommand (重写), ProjectMemoryService (新建), **SystemPromptBuilder (修改 loadMemoryPrompt)**, **InitCommand (修正)** | MemoryEditorPanel (新建), **SettingsPanel (集成)** | — | **dompurify** (XSS 防护), react-markdown (已有) |
| F4 | DoctorCommand (增强, jsx() 返回) | DiagnosticPanel (新建) | — | 无 |
| F5 | ✅ WebFetchTool 已实现 | — | — | — |
| F6 | GitService (**新增 execGitPublic 公开方法**), GitCommitCommand (LOCAL), DiffCommand (LOCAL_JSX), **删除 GitCommands @Bean** | GitDiffPanel, GitCommitPanel (新建) | — | 无 |
| F7 | WebSocketController (#26 sendPlanUpdate), **PlanCommand (新建)** | planStore (新建), PlanPanel (新建), dispatch.ts (plan_update) | — | 可选: @dnd-kit/sortable (拖拽排序) |
| F8 | FileHistoryController (已有) | FileChangesDashboard (新建) | — | 可选: react-window (大规模 diff 虚拟滚动) |
| F9 | SkillRegistry (已有), **SkillController (新建)** | CommandPalette (扩展), **SkillDetailModal (新建)** | — | 无 |

## 附录 C: 实施顺序建议

> **推荐顺序**: F2 → G0 → F4 → F3 → F1 → F6 → F7 → F8 → F9
>
> **策略说明**:
> - **F2 可独立最先实施**：P0 最高 ROI，核心仅 2 行修复，不依赖 G0
> - **G0 是第一个阻塞项**：F1/F3/F4/F6 所有 jsx() 方案的前置依赖
> - **F5 已实现无需排期，F9 仅需 UI 工作**
> - 总周期压缩至 3 周

```
第 0 周(前置):  F2 (成本显示 0.5天 — 可独立实施) + G0 (JSX/COMPACT 推送修复 0.5天)
             ↓ G0 完成后解除 F1/F3/F4/F6 阻塞
第 1 周:     F4 (/doctor 1天) + F3 (zhikun.md 记忆 2.5天) + F1 (/compact 1.5天)
             ↓ 核心命令 + 快速见效项
             ↓ F5 (WebFetchTool) 已实现，跳过
第 2 周:     F6 (Git 集成 3.5天) + F9 (Skills UI 1天)
             ↓ 核心功能闭环
第 3 周:     F7 (Plan Mode 2天) + F8 (文件变更 Dashboard 2.5天)
             ↓ 高级功能
```

---

> **文档结束** (v1.6 第六轮审查修订版)  
> 所有方案基于 ZhikuCode 现有代码库的**源码级审查**。  
> v1.6 核心修正:  
> - **全局**: 扩展 Message 类型新增 `metadata` 字段（解决前端 system Message 无 metadata 导致 JSX 路由无法编译问题）；  
> - **全局**: 统一 `SessionManager.loadSession()`（非 `getSession()`）方法名，返回 `Optional<SessionData>`；  
> - **全局**: `SessionData.createdAt`/`updatedAt` 类型为 `Instant`（非 String）；  
> - **F1**: CompactCommand 实际 67 行（非 47 行）；  
> - **F3**: 记忆注入位置修正为 SystemPromptBuilder.loadMemoryPrompt()（非 EffectiveSystemPromptBuilder）；HookEvent 事件数为 12 种（非 8 种）；添加 .gitignore 条目说明；  
> - **F6**: GitService.execGit() 为 private 方法，明确需新增 public 包装方法 execGitPublic()；  
> - **F9**: SkillDefinition.filePath 为 String 类型（非 Path），删除多余的 .toString() 调用；  
> - **附录 B**: 更新依赖矩阵反映以上修正。  
> 确保与现有架构（CommandRouter/ToolRegistry/WebSocket STOMP/Zustand Store）完全兼容。  
> 审查工具: 14 维度源码级验证（问题存在性/准确性/完整性/可行性/技术栈适配/安全性/必要性/真实性/浏览器适配等）。
