# ZhikuCode 端到端测试问题修复实施方案

> **版本**: v1.0 | **生成日期**: 2026-04-12 | **基于**: 功能对比分析报告 v10 第十三章（代码级审计）+ 第十四章（端到端集成测试）
>
> **代码基线**: zhikuncode 主分支 2026-04-12 HEAD  
> **参考基线**: Claude Code 原版源码 `/claudecode/src/` (512,685行 / 1,902文件)  
> **排除范围**: MCP 模块（独立修复计划）

---

## 一、概述

### 1.1 测试结论摘要

ZhikuCode 经过第十三章代码级深度审计和第十四章端到端集成测试，**端到端修订综合加权评分为 80.6/100**。核心链路（REST API → WebSocket → LLM 调用 → 工具执行 → 流式响应 → 前端渲染）**完全贯通**，但在跨请求上下文管理、子代理模型映射、权限状态同步等方面存在需要修复的问题。

| 评估维度 | 当前评分 | 目标评分 | 差距 |
|---------|---------|---------|------|
| Agent Loop (QueryEngine 8步循环) | 80 | 88 | -8 |
| 工具系统 (Tool + Pipeline) | 85 | 90 | -5 |
| 权限治理体系 | 85 | 92 | -7 |
| BashTool 8层安全 | 87 | 90 | -3 |
| System Prompt 工程 | 72 | 85 | -13 |
| 多 Agent 协作系统 | 72 | 82 | -10 |
| 上下文管理 (6层压缩级联) | 70 | 85 | -15 |
| 前端 UI + WebSocket | 87 | 90 | -3 |
| **综合加权评分** | **80.6** | **88+** | **-7.4+** |

### 1.2 问题分级统计

| 严重度 | 数量 | 已修复 | 待修复 | 说明 |
|--------|------|--------|--------|------|
| P0 阻塞级 | 1 | 0 | 1 | 前端权限消息处理不完整 |
| P1 高优先 | 9 | 2 | 7 | 关键功能缺陷（含已修复的 ToolUseContext NPE 和 REST 权限阻塞） |
| P2 中优先 | 7 | 0 | 7 | 功能完整性和质量问题 |
| P3 低优先 | 3 | 0 | 3 | 代码质量和日志问题 |
| **合计** | **20** | **2** | **18** | 排除 MCP 模块后的问题总数 |

### 1.3 修复优先级排序

```
Phase 1 (1-2周, 紧急修复):
  ├── P-PERM-02  前端 permission_mode_changed 处理     [P0, 0.5天]
  ├── P-CTX-01   REST API 消息持久化                    [P1, 2-3天]
  ├── P-AGENT-01 子代理模型 "haiku" → 配置化            [P1, 1-2天]
  ├── P-FE-01    前端会话创建错误处理                    [P1, 0.5天]
  └── P-TOOL-04  FileEdit/ReadTool 测试 NPE 修复       [P1, 1天]

Phase 2 (2-3周, 功能完善):
  ├── P-PERM-01  AutoModeClassifier LLM 调用实现       [P1, 2-3天]
  ├── P-AG-01    工具摘要 Haiku 异步生成                 [P1, 3-5天]
  ├── P-AGENT-02 Worker 权限冒泡                        [P1, 3-5天]
  ├── P-TOOL-01  Schema 验证完整实现                     [P1, 3-5天]
  └── P-CTX-02   自动压缩阈值校准                        [P2, 1天]

Phase 3 (1-2周, 测试完善):
  ├── P-BASH-01  BashParser 测试修复                     [P1, 1天]
  ├── P-CTX-04   上下文管理 4 层单元测试                  [P2, 3-5天]
  ├── P-AGENT-03 模型枚举动态化                          [P2, 1天]
  └── P-SP-01    System Prompt 内容扩充                  [P2, 1-2周]

Phase 4 (可选):
  ├── P-AG-02    newContext 更新机制                     [P2, 2-3天]
  ├── P-FE-02    WebSocket 消息解析错误                  [P3, 0.5天]
  └── P-FE-04    unused import 清理                     [P3, 0.5天]
```

---

## 二、前端权限模式同步修复 (P-PERM-02)

> **严重度**: P0 阻塞级 | **工时**: 0.5 天 | **依赖**: 无

### 2.1 问题描述

- **现象描述**: 当后端通过 WebSocket 推送 `permission_mode_changed` 消息时，前端 `dispatch.ts` 中虽然有对应的 handler，但实现仅包含 `console.log` 和 TODO 注释，未实际更新 `permissionStore` 的权限模式状态。
- **影响范围**: 用户在 WebSocket 会话中切换权限模式（如从 DEFAULT 切换到 AUTO），前端 UI 不会同步更新权限提示，导致用户看到的权限状态与后端实际行为不一致。
- **严重等级**: P0 — 权限状态不一致可能导致用户误操作，安全风险。

### 2.2 根因分析

**代码层面**: `frontend/src/api/dispatch.ts` 第 151-154 行：

```typescript
// 当前代码 — 仅打印日志，未更新 Store
'permission_mode_changed':  (d: { mode: string }) => {
    console.log('[dispatch] Permission mode changed:', d.mode);
    // TODO: setPermissionMode when sessionStore supports it
},
```

`permissionStore` 已经具备 `setPermissionMode` 方法（`frontend/src/store/permissionStore.ts` 第 19 行和第 39 行），但 dispatch 中未调用。这是一个典型的"实现遗漏"——Store 的写入接口已就绪，但消息分发端未对接。

**涉及文件**:
| 文件路径 | 行号 | 问题 |
|---------|------|------|
| `frontend/src/api/dispatch.ts` | L151-154 | handler 为桩实现 |
| `frontend/src/store/permissionStore.ts` | L19, L39 | `setPermissionMode` 方法已就绪 |

### 2.3 Claude Code 参考实现

Claude Code 原版是终端 UI 应用（Ink/React），权限模式通过全局状态管理。虽然没有直接的 `permission_mode_changed` WebSocket 消息（因为原版是单进程），但权限模式切换通过 Hook 机制全局同步：

```
原版路径: /claudecode/src/hooks/ 目录
权限状态管理: 通过 React Context + usePermissions hook 全局传播
```

关键设计原则：**权限模式变更必须立即传播到所有消费者**，确保 UI 提示与实际权限行为一致。

### 2.4 修复方案

#### 步骤 1: 修改 dispatch.ts 的 permission_mode_changed handler

**文件**: `frontend/src/api/dispatch.ts`  
**行号**: L151-154  
**修改内容**:

```typescript
// ===== 修改前 =====
'permission_mode_changed':  (d: { mode: string }) => {
    console.log('[dispatch] Permission mode changed:', d.mode);
    // TODO: setPermissionMode when sessionStore supports it
},

// ===== 修改后 =====
'permission_mode_changed':  (d: { mode: string }) => {
    console.log('[dispatch] Permission mode changed:', d.mode);
    // ★ 大小写安全：后端 PermissionMode 枚举为大写（如 "AUTO"），前端类型为小写（如 'auto'）
    // 后端通过 WebSocket 推送时使用 Java 枚举 name()（大写），必须转为小写匹配前端类型
    const normalizedMode = d.mode.toLowerCase() as PermissionMode;
    // 更新权限 Store 的权限模式
    usePermissionStore.getState().setPermissionMode(normalizedMode);
    // 清除挂起的权限请求：当权限模式变更为更宽松的模式（如 auto → bypass_permissions）时，
    // 之前被阻塞的权限请求不再需要用户确认，因此应清除待决权限请求
    if (usePermissionStore.getState().pendingPermission) {
        usePermissionStore.getState().clearPendingPermission();
    }
    // 通知用户权限模式已变更
    useNotificationStore.getState().addNotification({
        key: 'permission-mode-changed',
        level: 'info',
        message: `权限模式已切换为: ${normalizedMode}`,
        timeout: 3000,
    });
},
```

#### 步骤 2: 更新前端 PermissionMode 类型定义（必须）

**文件**: `frontend/src/types/index.ts`  
**行号**: L215  
**修改内容**:

前端当前定义仅包含 5 个值，缺少后端的 `AUTO` 和 `BUBBLE` 两种模式。后端 `PermissionMode.java`（L8-19）定义了 7 种枚举值，后端通过 WebSocket 推送的 `mode` 字符串为 Java 枚举 `name().toLowerCase()`，因此前端**必须**支持全部 7 种值：

```typescript
// ===== 修改前 (L215) =====
export type PermissionMode = 'default' | 'plan' | 'accept_edits' | 'dont_ask' | 'bypass_permissions';

// ===== 修改后 =====
export type PermissionMode = 'default' | 'plan' | 'accept_edits' | 'dont_ask' | 'bypass_permissions' | 'auto' | 'bubble';
```

> **重要**: 如果不更新此类型定义，TypeScript 编译器会在 `dispatch.ts` 中的 `d.mode as PermissionMode` 处产生类型不安全问题，且 `auto`/`bubble` 模式推送到前端时会被丢弃或导致运行时异常。

#### 步骤 3: 添加 PermissionMode 类型导入（当前缺失）

在 `dispatch.ts` 顶部 import 区域 **添加** `PermissionMode` 类型导入（当前 L9 的 import 中不包含 `PermissionMode`）：

```typescript
// dispatch.ts L9 — 修改前:
import type { Message, ServerMessage, Usage, PermissionRequest } from '@/types';

// dispatch.ts L9 — 修改后: 添加 PermissionMode
import type { Message, ServerMessage, Usage, PermissionRequest, PermissionMode } from '@/types';
```

#### 修改文件清单

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `frontend/src/api/dispatch.ts` | 修改 | L151-154 handler 实现 |
| `frontend/src/types/index.ts` | **修改（必须）** | L215 补充 'auto' \| 'bubble' 类型值 |

### 2.5 测试验证

**单元测试方案**:
```typescript
// frontend/src/store/__tests__/dispatch.test.ts 新增测试
describe('permission_mode_changed', () => {
  it('should update permissionStore mode', () => {
    dispatch({ type: 'permission_mode_changed', mode: 'auto' });
    expect(usePermissionStore.getState().permissionMode).toBe('auto');
  });

  it('should clear pending permission on mode change', () => {
    // 先设置一个挂起的权限请求
    usePermissionStore.getState().showPermission({ toolName: 'Bash', ... });
    dispatch({ type: 'permission_mode_changed', mode: 'full_auto' });
    expect(usePermissionStore.getState().pendingPermission).toBeNull();
  });

  it('should show notification on mode change', () => {
    dispatch({ type: 'permission_mode_changed', mode: 'auto' });
    const notifications = useNotificationStore.getState().notifications;
    expect(notifications).toContainEqual(
      expect.objectContaining({ key: 'permission-mode-changed' })
    );
  });
});
```

**端到端验证步骤**:
1. 启动后端 + 前端
2. 通过 WebSocket 发送消息，触发工具调用
3. 通过后端 API 切换权限模式: `POST /api/settings/permission-mode { mode: "auto" }`
4. 检查前端 UI 是否显示权限模式变更通知
5. 检查前端 permissionStore 中的 permissionMode 是否同步更新

---

## 三、REST API 消息持久化修复 (P-CTX-01)

> **严重度**: P1 高优先 | **工时**: 2-3 天 | **依赖**: 无

### 3.1 问题描述

- **现象描述**: `/api/query`、`/api/query/stream`、`/api/query/conversation` 三个 REST API 端点执行查询后，不将用户消息和助手回复保存到数据库。这导致：
  - 跨请求多轮对话完全断裂（第二次请求看不到第一次的消息历史）
  - `/api/query/conversation` 端点虽然加载历史消息，但执行后的新消息不保存
  - REST API 用户（CLI、SDK）无法实现多轮对话
- **影响范围**: 所有通过 REST API 路径的多轮对话功能完全失效
- **严重等级**: P1 — 核心用户场景断裂

### 3.2 根因分析

**代码层面**: `QueryController.java` 三个端点的执行流程缺少消息持久化步骤。

以同步查询端点 `/api/query` 为例（第 100-161 行）：

```java
// QueryController.java L100-161
@PostMapping
public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
    // 1-6: 准备阶段（会话、工具、配置）...
    
    // 7. 执行查询
    ResultCollectingHandler handler = new ResultCollectingHandler();
    QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

    // 8. 提取最终文本 — 直接返回，未保存消息!
    String finalText = extractFinalText(result.messages());
    return ResponseEntity.ok(new QueryResponse(...));
    // ❌ 缺失: sessionManager.addMessages(sessionId, result.messages());
}
```

对比 WebSocket 路径（`WebSocketController.java`），WebSocket 在消息处理完成后通过 `sessionManager.addMessage()` 保存消息，但 REST 路径完全遗漏了这一步。

> **⚠️ API 签名注意**: `SessionManager.addMessage()` 的实际签名为：
> ```java
> public String addMessage(String sessionId, String role, Object content,
>                          String stopReason, int inputTokens, int outputTokens)
> ```
> 该方法接受 6 个参数（sessionId、role、content、stopReason、inputTokens、outputTokens），**不接受** `Message` 对象。下方修复代码需要从 `Message` 对象中提取各字段分别传入。
>
> **⚠️ Message content 类型差异注意**: `Message` 是 sealed interface，三种子类型的 `content()` 返回类型不同：
> - `UserMessage.content()` → `List<ContentBlock>`（非 String）
> - `AssistantMessage.content()` → `List<ContentBlock>`（非 String）
> - `SystemMessage.content()` → `String`
>
> `addMessage()` 的 content 参数类型为 `Object`，内部通过 `toJsonString(content)` 序列化。因此传入 `List<ContentBlock>` 技术上可行，但需确保反序列化时 Jackson 多态配置正确。

**涉及文件**:
| 文件路径 | 行号 | 问题 |
|---------|------|------|
| `backend/.../controller/QueryController.java` | L101-161 | `/api/query` 端点缺少消息保存 |
| `backend/.../controller/QueryController.java` | L174-235 | `/api/query/stream` 端点缺少消息保存 |
| `backend/.../controller/QueryController.java` | L249-306 | `/api/query/conversation` 端点缺少消息保存 |

### 3.3 Claude Code 参考实现

Claude Code 原版在 `src/query.ts` 中，查询循环完成后通过消息管理系统持久化所有消息：

```typescript
// /claudecode/src/query.ts（概念性参考）
// 原版的 query() 函数在每轮循环后都会更新消息列表
// 消息通过 REPL state 管理，自动持久化
// 关键：messages 列表是全局状态的一部分，任何修改都会触发持久化

// 原版的消息持久化流程:
// 1. queryLoop() 内每轮循环产生的消息立即追加到 messages 数组
// 2. messages 数组绑定到 REPL state
// 3. REPL state 变更自动触发 history.ts 的 saveConversation()
// 4. 消息以 JSON 格式保存到 ~/.claude/projects/<hash>/conversations/
```

关键差异：原版是单进程应用，messages 作为内存状态自动持久化；ZhikuCode 是多服务架构，REST 路径需要显式调用持久化接口。

### 3.4 修复方案

#### 步骤 1: 在 QueryController 三个端点添加消息持久化

**文件**: `backend/src/main/java/com/aicodeassistant/controller/QueryController.java`

**端点 1 — 同步查询 `/api/query`（L147 后插入）**:

```java
// ===== 修改后 L147-161 =====
// 7. 执行查询
ResultCollectingHandler handler = new ResultCollectingHandler();
QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

// 7.5 ★ 新增：持久化消息到数据库 ★
// SessionManager.addMessage 签名: (sessionId, role, content, stopReason, inputTokens, outputTokens)
try {
    for (Message msg : result.messages()) {
        switch (msg) {
            case Message.UserMessage user -> sessionManager.addMessage(
                    sessionId, "user", user.content(), null, 0, 0);
            case Message.AssistantMessage assistant -> sessionManager.addMessage(
                    sessionId, "assistant", assistant.content(),
                    assistant.stopReason(),
                    assistant.usage() != null ? assistant.usage().inputTokens() : 0,
                    assistant.usage() != null ? assistant.usage().outputTokens() : 0);
            case Message.SystemMessage system -> sessionManager.addMessage(
                    sessionId, "system", system.content(), null, 0, 0);
        }
    }
    log.debug("REST API /api/query: 已持久化 {} 条消息到会话 {}",
            result.messages().size(), sessionId);
} catch (Exception e) {
    log.error("REST API 消息持久化失败, sessionId={}", sessionId, e);
    // 持久化失败不阻塞响应返回（降级策略）
}

// 8. 提取最终文本
String finalText = extractFinalText(result.messages());
```

**端点 2 — SSE 流式查询 `/api/query/stream`（L217-224 间插入）**:

```java
// ===== 修改后 L217-224 =====
QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

// ★ 新增：持久化消息（使用实际 6 参数签名）★
try {
    for (Message msg : result.messages()) {
        switch (msg) {
            case Message.UserMessage user -> sessionManager.addMessage(
                    sessionId, "user", user.content(), null, 0, 0);
            case Message.AssistantMessage assistant -> sessionManager.addMessage(
                    sessionId, "assistant", assistant.content(),
                    assistant.stopReason(),
                    assistant.usage() != null ? assistant.usage().inputTokens() : 0,
                    assistant.usage() != null ? assistant.usage().outputTokens() : 0);
            case Message.SystemMessage system -> sessionManager.addMessage(
                    sessionId, "system", system.content(), null, 0, 0);
        }
    }
} catch (Exception e) {
    log.error("SSE 消息持久化失败, sessionId={}", sessionId, e);
}

// 完成事件
sendEvent(emitter, "message_complete", Map.of(...));
```

**端点 3 — 多轮会话查询 `/api/query/conversation`（L293 后插入）**:

```java
// ===== 修改后 L291-306 =====
ResultCollectingHandler handler = new ResultCollectingHandler();
QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

// ★ 新增：持久化新增消息（排除已有历史消息，使用实际 6 参数签名）★
try {
    int existingCount = session.messages().size();
    List<Message> newMessages = result.messages().subList(
            Math.min(existingCount, result.messages().size()),
            result.messages().size());
    for (Message msg : newMessages) {
        switch (msg) {
            case Message.UserMessage user -> sessionManager.addMessage(
                    request.sessionId(), "user", user.content(), null, 0, 0);
            case Message.AssistantMessage assistant -> sessionManager.addMessage(
                    request.sessionId(), "assistant", assistant.content(),
                    assistant.stopReason(),
                    assistant.usage() != null ? assistant.usage().inputTokens() : 0,
                    assistant.usage() != null ? assistant.usage().outputTokens() : 0);
            case Message.SystemMessage system -> sessionManager.addMessage(
                    request.sessionId(), "system", system.content(), null, 0, 0);
        }
    }
    log.debug("REST API /api/query/conversation: 已持久化 {} 条新消息到会话 {}",
            newMessages.size(), request.sessionId());
} catch (Exception e) {
    log.error("Conversation 消息持久化失败, sessionId={}",
            request.sessionId(), e);
}

String finalText = extractFinalText(result.messages());
```

#### 步骤 2: 确认 SessionManager 接口

`SessionManager.addMessage()` 已存在（L210-233），实际签名为：

```java
// SessionManager.java L210-233 — 已有方法，无需新建
public String addMessage(String sessionId, String role, Object content,
                         String stopReason, int inputTokens, int outputTokens) {
    // 自动生成消息 ID、seq_num，写入 messages 表并更新 sessions.updated_at
}
```

> **注意**: 不存在 `addMessage(String, Message)` 的重载。如需便捷的 Message 对象接口，可选择新增适配方法，但上方修复代码已直接使用 6 参数签名，无需额外适配。

#### 步骤 3: 并发安全说明

`SessionManager` 当前实现（L210-233）直接通过 JdbcTemplate 执行 SQL INSERT，消息序号通过子查询 `COALESCE(MAX(seq_num), 0) + 1` 原子生成。SQLite 的串行写入特性（通过 `SqliteConfig.executeWrite()` 保证）已提供基本的并发安全。

> **注意**: `SessionManager` 不是接口-实现模式（没有 `SessionManagerImpl`），而是直接的 `@Service` 类，使用 JdbcTemplate + SQLite。不使用 JPA/Repository 模式。

> **❗ 并发安全补充**: `SessionManager.addMessage()` 内部通过 `COALESCE(MAX(seq_num), 0) + 1` 生成序号。在 SQLite 串行写入模式（`SqliteConfig.executeWrite()`）下基本安全，但如果未来切换为其他数据库则存在竞态风险。建议补充会话级锁机制（见步骤 3 的 `sessionLocks` 方案）或使用数据库级 UNIQUE 约束。

如需更强的并发保证（如多个 REST 请求同时写入同一 session），可在 QueryController 层添加 session 级别的同步：

```java
// 可选：在 QueryController 中对同一 session 的写入加锁
private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

private void persistMessages(String sessionId, List<Message> messages) {
    Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    synchronized (lock) {
        for (Message msg : messages) {
            // ... switch + addMessage 调用
        }
    }
}
```

#### 修改文件清单

| 文件 | 修改类型 | 行号 | 说明 |
|------|---------|------|------|
| `backend/.../controller/QueryController.java` | 修改 | L101, L174, L249 | 三个端点添加消息持久化 |
| `backend/.../session/SessionManager.java` | 无需修改 | L210-233 | `addMessage(sessionId, role, content, stopReason, inputTokens, outputTokens)` 已存在 |

### 3.5 测试验证

**单元测试方案**:
```java
@SpringBootTest
class QueryControllerMessagePersistenceTest {

    @Autowired private QueryController queryController;
    @MockBean private SessionManager sessionManager;

    @Test
    void syncQuery_shouldPersistMessages() {
        // Given: 正常查询请求
        QueryRequest request = new QueryRequest("Hello", ...);
        
        // When: 执行同步查询
        ResponseEntity<QueryResponse> response = queryController.query(request);
        
        // Then: 验证消息被保存（使用实际 6 参数签名）
        // SessionManager.addMessage 实际签名: (sessionId, role, content, stopReason, inputTokens, outputTokens)
        verify(sessionManager, atLeastOnce()).addMessage(
                anyString(), anyString(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void conversationQuery_shouldPersistOnlyNewMessages() {
        // Given: 已有 3 条历史消息的会话
        when(sessionManager.loadSession("s1")).thenReturn(
                Optional.of(new SessionData("s1", "model", existingMessages)));
        
        // When: 执行会话查询
        ConversationRequest request = new ConversationRequest("s1", "继续", ...);
        queryController.conversationQuery(request);
        
        // Then: 仅保存新消息（不重复保存历史消息）
        // 使用实际 6 参数签名验证
        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionManager, atLeast(1)).addMessage(
                eq("s1"), roleCaptor.capture(), any(), any(), anyInt(), anyInt());
        // 新消息数 = 总消息数 - 历史消息数
        assertTrue(roleCaptor.getAllValues().size() >= 1);
    }
}
```

**集成测试方案**:
```java
@Test
void multiTurnConversation_shouldMaintainContext() {
    // 第 1 轮
    QueryResponse r1 = restTemplate.postForObject("/api/query",
            new QueryRequest("我叫张三"), QueryResponse.class);
    
    // 第 2 轮（使用同一 sessionId）
    QueryResponse r2 = restTemplate.postForObject("/api/query/conversation",
            new ConversationRequest(r1.sessionId(), "我叫什么？"), QueryResponse.class);
    
    // 验证: 第 2 轮回复应包含"张三"
    assertThat(r2.result()).contains("张三");
}
```

**端到端验证步骤**:
1. `curl -X POST localhost:8080/api/query -d '{"prompt":"我叫张三"}' -H 'Content-Type: application/json'`
2. 记录返回的 sessionId
3. `curl -X POST localhost:8080/api/query/conversation -d '{"sessionId":"<id>","prompt":"我叫什么？"}'`
4. 验证返回结果包含"张三"
5. 检查数据库: `SELECT * FROM messages WHERE session_id = '<id>'` 确认消息已持久化

---

## 四、子代理模型映射配置化修复 (P-AGENT-01)

> **严重度**: P1 高优先 | **工时**: 1-2 天 | **依赖**: 无

### 4.1 问题描述

- **现象描述**: `AgentDefinition.EXPLORE` 和 `AgentDefinition.GUIDE` 的默认模型硬编码为 `"haiku"`，但系统的 `LlmProviderRegistry` 中无名为 "haiku" 的 Provider 映射。端到端测试日志显示 `No provider found for model: haiku`。
- **影响范围**: 所有 explore 和 guide 类型的子代理创建后必然执行失败
- **严重等级**: P1 — 子代理核心功能完全不可用

### 4.2 根因分析

**代码层面**: `SubAgentExecutor.java` 内部类 `AgentDefinition`（L818-837）硬编码了 Claude 系列模型名称：

```java
// SubAgentExecutor.java L818-836
static final AgentDefinition EXPLORE = new AgentDefinition(
    "Explore", 30, "haiku", null,   // ← "haiku" 无对应 Provider
    Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit"),
    true, EXPLORE_AGENT_PROMPT);

static final AgentDefinition GUIDE = new AgentDefinition(
    "ClaudeCodeGuide", 30, "haiku", // ← 同样硬编码 "haiku"
    Set.of("Glob", "Grep", "FileRead", "WebFetch", "WebSearch"), null,
    false, GUIDE_AGENT_PROMPT);
```

同时，`AgentTool.java` 的 `getInputSchema()` 中模型枚举也硬编码为 Claude 系列（L131-134）：

```java
// AgentTool.java L131-134
"model", Map.of(
    "type", "string",
    "enum", List.of("sonnet", "opus", "haiku"),  // ← Claude 系列名称
    "description", "Model override for the sub-agent"),
```

而系统实际部署使用的是 Qwen 系列模型（`qwen3.6-plus` 等），`ModelRegistry.BUILTIN_MODELS` 中没有 "haiku" 键。

**涉及文件**:
| 文件路径 | 行号 | 问题 |
|---------|------|------|
| `backend/.../tool/agent/SubAgentExecutor.java` | L818-836 | EXPLORE/GUIDE 默认模型硬编码 "haiku" |
| `backend/.../tool/agent/AgentTool.java` | L131-134 | 模型枚举硬编码 Claude 系列 |
| `backend/.../tool/config/ConfigTool.java` | L43 | 配置工具的模型选项也硬编码 |

### 4.3 Claude Code 参考实现

Claude Code 原版中子代理模型解析采用多级回退策略：

```typescript
// /claudecode/src/tools/AgentTool/ 目录
// 原版的子代理模型解析逻辑:
// 1. 用户在 tool input 中指定的 model 参数（如 "haiku"）
// 2. AgentDefinition 中的默认模型
// 3. 最终通过 getRuntimeMainLoopModel() 解析实际模型 ID
//
// 关键: 原版使用的 "haiku" 是别名，通过 model resolution 层映射到实际模型 ID
// 如: "haiku" → "claude-3-5-haiku-20241022"
//
// 参考: /claudecode/src/utils/model/model.ts 中的 getRuntimeMainLoopModel()
```

原版的 "haiku" 不是直接的 Provider 名称，而是一个**模型别名**，通过模型解析层映射到实际的模型 ID。ZhikuCode 缺少这个别名→实际模型的映射层。

### 4.4 修复方案

#### 步骤 1: 添加模型别名解析机制

**文件**: `backend/src/main/java/com/aicodeassistant/llm/LlmProviderRegistry.java`  
**操作**: **需新增** — 当前源码（127 行）中不存在 `resolveModelAlias()` 方法。现有方法仅包含：`getProvider()`、`listAvailableModels()`、`getDefaultModel()`、`getFastModel()`、`resolveClassifierModel()`、`getLightweightModel()`、`getMainLoopModel()`。  
**新增方法**:

```java
/**
 * 解析模型别名为实际模型名称。
 * <p>
 * 别名映射规则（四级回退）：
 * 1. 环境变量 AGENT_MODEL_<alias> (如 AGENT_MODEL_HAIKU=qwen-plus)
 * 2. application.yml 配置 agent.model-aliases.<alias>
 * 3. 内置映射表（haiku→轻量模型, sonnet→默认模型, opus→旗舰模型）
 * 4. 直接使用别名作为模型名尝试查找 Provider
 */
public String resolveModelAlias(String modelNameOrAlias) {
    if (modelNameOrAlias == null || modelNameOrAlias.isBlank()) {
        return getDefaultModel();
    }
    
    // Level 1: 环境变量覆盖
    String envKey = "AGENT_MODEL_" + modelNameOrAlias.toUpperCase().replace("-", "_");
    String envModel = System.getenv(envKey);
    if (envModel != null && !envModel.isBlank()) {
        return envModel;
    }
    
    // Level 2: 配置文件映射
    String configModel = modelAliases.get(modelNameOrAlias.toLowerCase());
    if (configModel != null) {
        return configModel;
    }
    
    // Level 3: 内置别名映射（Claude → 实际部署模型）
    String builtinModel = BUILTIN_ALIASES.get(modelNameOrAlias.toLowerCase());
    if (builtinModel != null) {
        return builtinModel;
    }
    
    // Level 4: 直接返回，尝试作为模型名使用
    return modelNameOrAlias;
}

private static final Map<String, String> BUILTIN_ALIASES = Map.of(
    "haiku", "qwen-plus",           // 轻量级模型
    "sonnet", "qwen3.6-plus",       // 默认模型
    "opus", "qwen-max",             // 旗舰模型
    "claude-haiku", "qwen-plus",
    "claude-sonnet", "qwen3.6-plus",
    "claude-opus", "qwen-max"
);

// 从配置文件加载的别名映射
// ★ 注意: 使用 @ConfigurationProperties 而非 @Value + SpEL，避免 #{${...}} 嵌套解析问题 ★
private Map<String, String> modelAliases = new HashMap<>();

// ★ 在 LlmProviderRegistry 类上添加 @ConfigurationProperties ★
// @ConfigurationProperties(prefix = "agent")
// 或在构造函数中注入:
// public LlmProviderRegistry(List<LlmProvider> providerList,
//                            @Value("${agent.model-aliases.haiku:}") String haikuAlias,
//                            @Value("${agent.model-aliases.sonnet:}") String sonnetAlias,
//                            @Value("${agent.model-aliases.opus:}") String opusAlias) {
//     // 构建 modelAliases Map
// }
```

> **⚠️ @Value 语法警告**: 原方案中的 `@Value("#{${agent.model-aliases:{}}}")` 使用 SpEL (`#{}`) 内嵌属性占位符 (`${}`) 的语法可能导致解析异常。**推荐方案**：使用 `@ConfigurationProperties(prefix = "agent")` + Map 属性绑定，或拆分为单独的 `@Value` 注入。

#### 步骤 2: 在 SubAgentExecutor 中添加 LlmProviderRegistry 依赖注入（必须）

**文件**: `backend/src/main/java/com/aicodeassistant/tool/agent/SubAgentExecutor.java`  
**操作**: **需修改构造函数** — 当前 SubAgentExecutor 的 10 个 `private final` 依赖中不包含 `LlmProviderRegistry`。必须新增该依赖才能调用 `providerRegistry.resolveModelAlias()`。

```java
// SubAgentExecutor.java — 新增依赖字段（在现有 10 个 private final 字段后追加）
private final LlmProviderRegistry providerRegistry;  // ★ 新增 ★

// 构造函数需从 10 参数扩展为 11 参数（Spring 自动注入）
public SubAgentExecutor(
        AgentConcurrencyController concurrencyController,
        QueryEngine queryEngine,
        ToolRegistry toolRegistry,
        BackgroundAgentTracker backgroundTracker,
        WorktreeManager worktreeManager,
        TaskNotificationFormatter taskNotificationFormatter,
        FeatureFlagService featureFlagService,
        CoordinatorService coordinatorService,
        SessionManager sessionManager,
        TeamManager teamManager,
        LlmProviderRegistry providerRegistry) {   // ★ 新增参数 ★
    // ... 原有赋值 ...
    this.providerRegistry = providerRegistry;
}
```

> **⚠️ 依赖注入必要性**: 当前 SubAgentExecutor 的 `private final` 字段为: `concurrencyController`, `queryEngine`, `toolRegistry`, `backgroundTracker`, `worktreeManager`, `taskNotificationFormatter`, `featureFlagService`, `coordinatorService`, `sessionManager`, `teamManager` — **共 10 个，不包含 `LlmProviderRegistry`**。如果不添加此依赖，步骤 3 中的 `providerRegistry.resolveModelAlias()` 调用将编译失败。

#### 步骤 3: 修改 SubAgentExecutor 的模型解析

**文件**: `backend/src/main/java/com/aicodeassistant/tool/agent/SubAgentExecutor.java`  
**行号**: L266-270

```java
// ===== 修改前 =====
private String resolveModel(String requestModel, AgentDefinition agentDef) {
    if (requestModel != null && !requestModel.isBlank()) return requestModel;
    if (agentDef.defaultModel() != null) return agentDef.defaultModel();
    return "sonnet";  // 默认子代理模型
}

// ===== 修改后 =====
private String resolveModel(String requestModel, AgentDefinition agentDef) {
    String rawModel;
    if (requestModel != null && !requestModel.isBlank()) {
        rawModel = requestModel;
    } else if (agentDef.defaultModel() != null) {
        rawModel = agentDef.defaultModel();
    } else {
        rawModel = "sonnet";
    }
    // ★ 通过别名解析机制映射到实际模型 ★
    return providerRegistry.resolveModelAlias(rawModel);
}
```

#### 步骤 4: 修改 AgentTool 的模型枚举为动态获取

**文件**: `backend/src/main/java/com/aicodeassistant/tool/agent/AgentTool.java`  
**行号**: L131-134  
**前置条件**: AgentTool 当前仅有 1 个依赖 `SubAgentExecutor`，**需新增 `LlmProviderRegistry` 依赖注入**：

```java
// AgentTool.java — 修改构造函数（从 1 参数扩展为 2 参数）
private final SubAgentExecutor subAgentExecutor;
private final LlmProviderRegistry providerRegistry;  // ★ 新增 ★

public AgentTool(SubAgentExecutor subAgentExecutor,
                LlmProviderRegistry providerRegistry) {  // ★ 新增参数 ★
    this.subAgentExecutor = subAgentExecutor;
    this.providerRegistry = providerRegistry;
}
```

```java
// ===== 修改前 =====
"model", Map.of(
    "type", "string",
    "enum", List.of("sonnet", "opus", "haiku"),
    "description", "Model override for the sub-agent"),

// ===== 修改后 =====
"model", Map.of(
    "type", "string",
    "enum", getAvailableModelAliases(),
    "description", "Model override for the sub-agent. "
        + "Use aliases (haiku/sonnet/opus) or actual model names."),
```

新增辅助方法：

```java
private List<String> getAvailableModelAliases() {
    List<String> aliases = new ArrayList<>(List.of("haiku", "sonnet", "opus"));
    // 追加实际可用模型名
    try {
        providerRegistry.listAvailableModels().stream()
            .filter(m -> !aliases.contains(m))
            .forEach(aliases::add);
    } catch (Exception e) {
        // 降级：仅返回别名
    }
    return aliases;
}
```

#### 步骤 5: 添加 application.yml 配置

```yaml
# application.yml
agent:
  model-aliases:
    haiku: qwen-plus
    sonnet: qwen3.6-plus
    opus: qwen-max
```

#### 修改文件清单

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `backend/.../llm/LlmProviderRegistry.java` | **需新增方法** | `resolveModelAlias()` + `BUILTIN_ALIASES` 映射 + `modelAliases` 配置字段（源码中均不存在） |
| `backend/.../tool/agent/SubAgentExecutor.java` | **需修改构造函数** + 修改 L266-270 | 新增 `LlmProviderRegistry` 依赖注入（10参数→ 11参数）+ 模型解析使用别名机制 |
| `backend/.../tool/agent/AgentTool.java` | **需修改构造函数** + 修改 L131-134 | 新增 `LlmProviderRegistry` 依赖注入（1参数→ 2参数）+ 模型枚举动态化 |
| `backend/.../tool/config/ConfigTool.java` | 修改 L43 | 配置工具模型选项动态化 |
| `backend/src/main/resources/application.yml` | 新增配置 | 模型别名映射 |

### 4.5 测试验证

**单元测试方案**:
```java
@Test
void resolveModelAlias_haikuMapsToQwenPlus() {
    String resolved = providerRegistry.resolveModelAlias("haiku");
    assertEquals("qwen-plus", resolved);
}

@Test
void resolveModelAlias_directModelNamePassThrough() {
    String resolved = providerRegistry.resolveModelAlias("qwen3.6-plus");
    assertEquals("qwen3.6-plus", resolved);
}

@Test
void exploreAgent_shouldUseResolvedModel() {
    AgentDefinition explore = AgentDefinition.EXPLORE;
    String model = resolveModel(null, explore);
    // 应解析为实际可用模型，而非 "haiku"
    assertNotEquals("haiku", model);
    assertDoesNotThrow(() -> providerRegistry.getProvider(model));
}
```

**端到端验证步骤**:
1. 发送请求触发 explore 类型子代理:
   ```bash
   curl -X POST localhost:8080/api/query -d '{"prompt":"请搜索项目中所有的Controller类并分析它们的结构"}'
   ```
2. 检查后端日志: 应显示 `SubAgent created: type=explore, model=qwen-plus`（而非 `model=haiku`）
3. 子代理应正常执行并返回搜索结果

---

## 五、前端会话创建错误处理修复 (P-FE-01)

> **严重度**: P1 高优先 | **工时**: 0.5 天 | **依赖**: 无

### 5.1 问题描述

- **现象描述**: `App.tsx` 中 `createSession()` 调用缺少 try-catch 包裹。当后端服务不可用或网络错误时，用户无法得到有意义的错误提示，界面可能卡在加载状态。
- **影响范围**: 所有用户在后端不可用时的首次使用体验
- **严重等级**: P1 — 影响基础用户体验

### 5.2 根因分析

**代码层面**: `frontend/src/App.tsx` 第 35-41 行：

```typescript
// App.tsx L35-41
let currentSessionId = useSessionStore.getState().sessionId;
if (!currentSessionId) {
  const defaultModel = useConfigStore.getState().defaultModel ?? 'claude-sonnet-4-20250514';
  await createSession('.', defaultModel);  // ← 无 try-catch!
  currentSessionId = useSessionStore.getState().sessionId;
  console.log('[App] Session created:', currentSessionId);
}
```

### 5.3 Claude Code 参考实现

原版在 `src/main.tsx` 中对初始化阶段的所有异步操作都有完整的错误处理和用户反馈：

```typescript
// 原版通过 ErrorBoundary + fallback UI 处理初始化错误
// 如果 session 创建失败，显示错误信息和重试按钮
```

### 5.4 修复方案

**文件**: `frontend/src/App.tsx`  
**行号**: L35-41

```typescript
// ===== 修改后 =====
let currentSessionId = useSessionStore.getState().sessionId;
if (!currentSessionId) {
  try {
    const defaultModel = useConfigStore.getState().defaultModel ?? 'claude-sonnet-4-20250514';
    await createSession('.', defaultModel);
    currentSessionId = useSessionStore.getState().sessionId;
    console.log('[App] Session created:', currentSessionId);
  } catch (error) {
    console.error('[App] Failed to create session:', error);
    // 添加错误消息到消息列表，让用户看到
    addMessage({
      uuid: crypto.randomUUID(),
      type: 'system',
      content: '连接服务器失败，请检查后端服务是否正常运行。',
      timestamp: Date.now(),
      subtype: 'error',
      errorCode: 'CONNECTION_ERROR', // 便于 UI 根据错误类型区分展示（如重试按钮、网络诊断提示等）
    } as Message);
    useSessionStore.getState().setStatus('idle');
    return; // 不再继续发送消息
  }
}
```

#### 修改文件清单

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `frontend/src/App.tsx` | 修改 L35-41 | 添加 try-catch 和用户友好错误提示 |

### 5.5 测试验证

**单元测试方案**:
```typescript
describe('App - session creation error handling', () => {
  it('should show error message when session creation fails', async () => {
    // Mock createSession to reject
    jest.spyOn(useSessionStore.getState(), 'createSession')
      .mockRejectedValue(new Error('Connection refused'));
    
    render(<App />);
    // Trigger submit
    // ...
    
    // Verify error message is displayed
    expect(useMessageStore.getState().messages).toContainEqual(
      expect.objectContaining({ subtype: 'error' })
    );
  });
});
```

---

## 六、文件工具单元测试 NPE 修复 (P-TOOL-04)

> **严重度**: P1 高优先 | **工时**: 1 天 | **依赖**: 无

### 6.1 问题描述

- **现象描述**: `FileEditToolUnitTest.java` (214行, 11个测试) 和 `FileReadToolUnitTest.java` (169行, 10个测试) 中共 21 个测试因 `PathSecurityService` 未 mock 注入而抛 NPE。
- **影响范围**: 21 个单元测试无法执行，工具系统的测试覆盖率虚高
- **严重等级**: P1 — 测试可靠性问题

### 6.2 根因分析

工具在执行前调用 `PathSecurityService.validate(path)` 进行路径安全校验。测试中该服务未被 mock 注入，导致在工具实例化或执行时 NPE。

### 6.3 Claude Code 参考实现

原版的 FileEditTool 和 FileReadTool 在测试中通过 `createToolUseContext()` 工厂方法注入 mock 的安全检查：

```typescript
// /claudecode/src/tools/FileReadTool/ 目录
// 原版测试通过 toolUseContext 注入 mock 的 hasReadPermission/hasWritePermission
```

### 6.4 修复方案

**文件 1**: `backend/src/test/java/com/aicodeassistant/tool/impl/FileEditToolUnitTest.java`

> **源码现状**: `FileEditTool` 构造函数需要 3 个依赖：`FileHistoryService`、`PathSecurityService`、`SessionManager`。当前测试（L31）通过 `new FileEditTool(null, null, null)` 创建实例，导致调用 `pathSecurity.validate()` 时 NPE。
>
> **⚠️ FileReadTool 与 FileEditTool 依赖差异**: `FileReadTool` 构造函数仅需 **2 个**依赖：`PathSecurityService`、`SessionManager`（**无 FileHistoryService**，因为读操作无需文件历史记录）。当前测试（L32）通过 `new FileReadTool(null, null)` 创建实例。

```java
// 在测试类顶部添加完整的 mock 声明和初始化
@Mock private PathSecurityService pathSecurityService;
@Mock private FileHistoryService fileHistoryService;
@Mock private SessionManager sessionManager;

@BeforeEach
public void setUp() {
    MockitoAnnotations.openMocks(this);
    // Mock PathSecurityService 默认允许所有路径
    when(pathSecurityService.validate(any())).thenReturn(true);
    when(pathSecurityService.isPathAllowed(any())).thenReturn(true);
    when(pathSecurityService.checkWritePermission(any())).thenReturn(true);
    when(pathSecurityService.checkReadPermission(any())).thenReturn(true);
    // 注意：以下为简化 mock，简单返回入参本身。生产级测试需使用 spy(PathSecurityService) 实现完整路径规范化，
    // 否则相对路径、符号链接等场景的测试可能与真实行为不一致。
    when(pathSecurityService.resolvePath(any())).thenAnswer(inv -> inv.getArgument(0));
    
    // Mock SessionManager 返回空的文件状态缓存
    when(sessionManager.getFileStateCache(any())).thenReturn(new HashMap<>());
    
    // Mock FileHistoryService 默认成功（仅 FileEditTool 需要）
    doNothing().when(fileHistoryService).trackEdit(any(), any(), any());
    
    // 将 mock 注入到工具实例中（完整 3 参数构造函数）
    fileEditTool = new FileEditTool(fileHistoryService, pathSecurityService, sessionManager);
}
```

**文件 2**: `backend/src/test/java/com/aicodeassistant/tool/impl/FileReadToolUnitTest.java`

同样的修复模式，但依赖列表不同（**2 个依赖，非 3 个**）。

```java
// FileReadTool 仅需 2 个依赖（无 FileHistoryService）
@Mock private PathSecurityService pathSecurityService;
@Mock private SessionManager sessionManager;

@BeforeEach
public void setUp() {
    MockitoAnnotations.openMocks(this);
    when(pathSecurityService.validate(any())).thenReturn(true);
    when(pathSecurityService.isPathAllowed(any())).thenReturn(true);
    when(pathSecurityService.checkReadPermission(any())).thenReturn(true);
    // 注意：以下为简化 mock，简单返回入参本身。生产级测试需使用 spy(PathSecurityService) 实现完整路径规范化，
    // 否则相对路径、符号链接等场景的测试可能与真实行为不一致。
    when(pathSecurityService.resolvePath(any())).thenAnswer(inv -> inv.getArgument(0));
    
    // Mock SessionManager 返回空的文件状态缓存
    when(sessionManager.getFileStateCache(any())).thenReturn(new HashMap<>());
    
    // FileReadTool 构造函数为 2 参数（PathSecurityService, SessionManager）
    fileReadTool = new FileReadTool(pathSecurityService, sessionManager);
}
```

> **❗ 完整依赖列表对比**：
> | 工具类 | 依赖数 | 依赖列表 |
> |---------|---------|----------|
> | `FileEditTool` | **3 个** | `FileHistoryService` + `PathSecurityService` + `SessionManager` |
> | `FileReadTool` | **2 个** | `PathSecurityService` + `SessionManager`（无 FileHistoryService） |

### 6.5 测试验证

```bash
# 运行修复后的测试
cd backend && mvn test -pl . -Dtest="FileEditToolUnitTest,FileReadToolUnitTest"
# 预期: 21 个测试全部通过（FileEditTool 11 + FileReadTool 10）
```

---

## 七、AutoModeClassifier LLM 调用实现 (P-PERM-01)

> **严重度**: P1 高优先 | **工时**: 2-3 天 | **依赖**: 无

### 7.1 问题描述

- **现象描述**: `AutoModeClassifier.callClassifierLLM()` 方法设计为调用 LLM 进行权限分类，代码框架已完整（包含 LLM Provider 调用、异常处理、stop sequence 恢复逻辑），但在实际运行时可能因 `resolveClassifierModel()` 返回不可用模型而失败。
- **影响范围**: AUTO 权限模式下无法进行智能权限分类，所有工具调用要么全部允许，要么全部需要手动确认
- **严重等级**: P1 — 权限系统核心功能

### 7.2 根因分析

通过源码分析（`AutoModeClassifier.java` L341-378），`callClassifierLLM()` 方法实际上**已有 LLM 调用实现**，不是简单的桩代码。它调用 `providerRegistry.resolveClassifierModel()` 获取模型，然后通过 `provider.chatSync()` 执行分类。

> **❗ 源码事实校正**:
> - `resolveClassifierModel()` 方法 **已存在** 于 `LlmProviderRegistry.java`（L114-125），采用三级回退: 环境变量 `CLASSIFIER_MODEL` → `getFastModel()` → `getDefaultModel()`
> - 但该方法 **未通过 Spring `@Value` 注入配置字段** — 没有 `classifier.model` 配置项，回退层级从环境变量直接跳到 `getFastModel()`
> - `AutoModeClassifier.java`（597 行）中同样没有 `@Value("${classifier.model}")` 字段定义

实际问题可能是：
1. `resolveClassifierModel()` 回退到 `getFastModel()` 返回的模型名称在当前环境中无对应 Provider
2. 部分 Provider 不支持 `chatSync()` 方法（抛出 `UnsupportedOperationException`）
3. 测试断言期望特定的分类结果，但桩/异常导致返回不符预期

### 7.3 Claude Code 参考实现

```typescript
// /claudecode/src/ 中的 yoloClassifier.ts
// 原版使用两阶段分类器:
// Stage 1 (Quick): max_tokens=64, stop_sequences=['</block>']
//   → 快速判断是否需要阻塞
// Stage 2 (Thinking): max_tokens=4096
//   → 深度推理，链式思考后给出最终决策
//
// 模型选择: 使用 Haiku（轻量级）作为分类器模型
// 关键: 分类器模型与主对话模型分离，降低成本
```

### 7.4 修复方案

#### 步骤 1: 确保 resolveClassifierModel 返回可用模型

**文件**: `backend/src/main/java/com/aicodeassistant/llm/LlmProviderRegistry.java`

> **源码现状**: `resolveClassifierModel()` 已存在（L114-125），但回退层级中缺少 Spring 配置文件映射层（从环境变量直接跳到 `getFastModel()`）。建议增强为四级回退，补充 Spring `@Value` 配置字段。
>
> **⚠️ 需新增**: 在 `LlmProviderRegistry.java` 类顶部添加以下配置字段（当前不存在）：
> ```java
> @Value("${classifier.model:}")
> private String classifierModel;  // 对应 application.yml 中的 classifier.model 配置项
> ```
> 同时在 `application.yml` 中添加配置项：
> ```yaml
> classifier:
>   model: ""  # 可配置为具体模型名，如 qwen-turbo，留空则回退到 getLightweightModel()
> ```

```java
/**
 * 解析分类器模型 — 四级回退:
 * 1. 环境变量 CLASSIFIER_MODEL
 * 2. application.yml classifier.model
 * 3. 轻量级模型（getLightweightModel）
 * 4. 默认模型（getDefaultModel）
 */
public String resolveClassifierModel() {
    // Level 1: 环境变量
    String envModel = System.getenv("CLASSIFIER_MODEL");
    if (envModel != null && !envModel.isBlank() && hasProvider(envModel)) {
        return envModel;
    }
    
    // Level 2: 配置文件
    if (classifierModel != null && !classifierModel.isBlank() && hasProvider(classifierModel)) {
        return classifierModel;
    }
    
    // Level 3: 轻量级模型
    String lightweight = getLightweightModel();
    if (lightweight != null && hasProvider(lightweight)) {
        return lightweight;
    }
    
    // Level 4: 默认模型
    return getDefaultModel();
}

private boolean hasProvider(String model) {
    try {
        getProvider(model);
        return true;
    } catch (Exception e) {
        return false;
    }
}
```

#### 步骤 2: 确认 Provider 支持 chatSync

当前项目使用的是 `OpenAiCompatibleProvider`（阿里云百炼 OpenAI 兼容端点），该 Provider **已实现** `chatSync()` 方法（L365），包含重试逻辑和超时处理：

```java
// OpenAiCompatibleProvider.java L365 — 已有实现，无需新建
@Override
public String chatSync(String model, String systemPrompt, String userContent,
                        int maxTokens, String[] stopSequences, long timeoutMs) {
    // 使用 OkHttp 同步调用 OpenAI 兼容端点
    // 包含 429 Rate Limit 重试、超时处理、响应解析
    // ...
}
```

> **⚠️ 源码事实校正**: 项目中 **不存在** `DashScopeProvider` 类。实际使用的是 `OpenAiCompatibleProvider`（通过阿里云百炼的 OpenAI 兼容端点 `https://dashscope.aliyuncs.com/compatible-mode/v1` 调用，配置见 `application.yml` L29-30）。此外，`LlmProvider` 接口的 `chatSync()` 是 default 方法（L92-98），对不支持 stopSequences 的情况会回退到 `streamChat()`，但 `OpenAiCompatibleProvider` 已完整覆写支持 stopSequences。
>
> 因此，**步骤 2 无需新建 Provider 实现**，只需确认现有 `OpenAiCompatibleProvider.chatSync()` 工作正常即可。
```

### 7.5 测试验证

```java
@Test
void autoModeClassifier_shouldCallLlmForDecision() {
    // Given: AUTO 权限模式 + Bash 工具 + 危险命令
    PermissionContext ctx = PermissionContext.forAuto(sessionId);
    Tool bashTool = toolRegistry.getTool("Bash");
    String input = "rm -rf /tmp/test";
    
    // When: 分类器执行
    PermissionDecision decision = classifier.classify(bashTool, input, ctx);
    
    // Then: 应返回 DENY（危险命令）
    assertEquals(PermissionDecision.Type.DENY, decision.type());
}
```

---

## 八、工具摘要 Haiku 异步生成实现 (P-AG-01)

> **严重度**: P1 高优先 | **工时**: 3-5 天 | **依赖**: P-AGENT-01（模型别名机制）

### 8.1 问题描述

- **现象描述**: 当工具输出超过阈值时，当前实现直接截断处理，而非使用轻量级 LLM 生成结构化摘要。
- **影响范围**: 长工具输出的信息密度降低，影响后续推理质量
- **严重等级**: P1 — 影响 LLM 推理质量

### 8.2 根因分析

`QueryEngine.java` 在工具执行后处理区域（Step 5: L394-398 `consumeToolResults`，Step 7: L524-531 `toolResultSummarizer.processToolResults`），工具结果超长时仅做截断。

> **❗ 源码事实**: `ToolResultSummarizer.java` **已存在**（162 行，`@Component`），位于 `backend/src/main/java/com/aicodeassistant/engine/ToolResultSummarizer.java`。现有实现采用三级截断策略：
> - **SOFT_LIMIT_CHARS (18,000)**: 超过则截断，保留头尾
> - **HARD_LIMIT_CHARS (50,000)**: 超过则硬截断（头部 12K + 尾部 3K）
> - **clearStaleToolResults()**: 旧轮次工具结果清理（超 8 轮替换为占位符）
>
> 现有方法包括：`processToolResults(List<Message>, int)`、`clearStaleToolResults(List<Message>, int)`、`shouldInjectSummarizeHint(List<Message>, int)`
>
> **缺失功能**: 现有实现仅做截断，缺少原版的 LLM 智能摘要生成（用轻量级模型生成 git-commit 风格的单行摘要）。

> **精确行号标注**:
> - `consumeToolResults()` 方法: L674-714
> - 工具结果处理调用 `toolResultSummarizer.processToolResults()`: L524-531
> - `StreamCollector` 流式收集器: L886-950+
> - `TOOL_RESULT_BUDGET_RATIO` 常量: L64

### 8.3 Claude Code 参考实现

原版实现在 `/claudecode/src/services/toolUseSummary/toolUseSummaryGenerator.ts`（113 行）：

```typescript
// 关键实现要点:
// 1. 使用 queryHaiku() 调用轻量级模型
// 2. System Prompt 要求生成 git-commit 风格的单行摘要（~30 字符）
// 3. 工具输入和输出截断到 300 字符作为 LLM 输入
// 4. 失败时静默降级（摘要是非关键功能）
// 5. 支持 AbortSignal 取消

const TOOL_USE_SUMMARY_SYSTEM_PROMPT = `Write a short summary label describing 
what these tool calls accomplished. It appears as a single-line row in a mobile 
app and truncates around 30 characters...`

export async function generateToolUseSummary({
  tools, signal, isNonInteractiveSession, lastAssistantText
}): Promise<string | null> {
    const response = await queryHaiku({
        systemPrompt: asSystemPrompt([TOOL_USE_SUMMARY_SYSTEM_PROMPT]),
        userPrompt: `Tools completed:\n\n${toolSummaries}\n\nLabel:`,
        signal,
        ...
    });
    return response.message.content.filter(b => b.type === 'text')...;
}
```

### 8.4 修复方案

#### 重构现有 ToolResultSummarizer 服务

**文件**: `backend/src/main/java/com/aicodeassistant/engine/ToolResultSummarizer.java`（**重构现有实现，非新建**）

 **❗ 架构决策**: 项目中已存在 `ToolResultSummarizer`（162 行，`@Component`），采用纯截断策略。本方案建议 **在现有类基础上扩展** LLM 摘要能力，而非新建替代类。具体来说，在现有 `processToolResults()` 方法中添加 LLM 摘要分支，当截断触发时尝试先调用轻量级 LLM 生成摘要，失败时回退到现有截断策略。
>
> **⚠️ 异步模式兼容性说明**：`QueryEngine.processToolResults()` 当前为 **同步调用**，因此推荐采用"**内部异步、外部同步**"混合模式（方案 A）：
> - `ToolResultSummarizer` 对外保持 **同步 API**（`summarizeIfNeeded()` 为同步方法）
> - 内部通过 `CompletableFuture.supplyAsync()` 异步调用 LLM，然后用 `.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)` 等待结果
> - 这样既不破坏 `QueryEngine` 的同步调用流程，又能通过超时机制避免 LLM 调用阻塞主线程
>
> **⚠️ 构造函数向后兼容**：新增 `LlmProviderRegistry` 依赖会破坏现有构造函数。建议使用 **构造函数重载** 保持向后兼容：
> ```java
> // 保留旧构造函数（向后兼容，LLM 摘要功能禁用）
> public ToolResultSummarizer(TokenCounter tokenCounter) {
>     this(null, tokenCounter);
> }
> // 新构造函数（启用 LLM 摘要）
> public ToolResultSummarizer(LlmProviderRegistry providerRegistry, TokenCounter tokenCounter) {
>     this.providerRegistry = providerRegistry;
>     this.tokenCounter = tokenCounter;
> }
> ```
>
> **两种方案选择**：
> - 方案 A（推荐）：**在现有类中新增方法** — 添加 `summarizeIfNeeded()`、`summarizeAsync()`、`generateSummary()` 方法，注入 `LlmProviderRegistry` 依赖，使用构造函数重载保持兼容
> - 方案 B：**新建独立的 LlmToolSummarizer 类** — 职责分离，但需在 QueryEngine 中同时管理两个摘要器

**方案 A 实现（在现有类中新增以下方法和字段）**：

```java
// ★ 以下为需新增到现有 ToolResultSummarizer.java 中的代码 ★
// 现有类已有: @Component, TokenCounter 依赖, processToolResults(), clearStaleToolResults() 等
// 以下方法和字段均为新增
package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.llm.LlmProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ToolResultSummarizer — 在现有截断策略基础上，新增轻量级 LLM 摘要能力。
 * <p>
 * 现有功能（保留）: processToolResults(), clearStaleToolResults(), shouldInjectSummarizeHint()
 * 新增功能: summarizeIfNeeded(), summarizeAsync(), generateSummary()
 * <p>
 * 对齐原版 toolUseSummaryGenerator.ts:
 * - 使用 Haiku/轻量模型异步生成摘要
 * - 摘要为 git-commit 风格单行描述
 * - 失败时静默降级为截断策略
 */
@Component  // ★ 保持与现有源码一致，使用 @Component 而非 @Service ★
public class ToolResultSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultSummarizer.class);
    
    // ★ 以下常量为新增（LLM 摘要相关），现有类已有 SOFT_LIMIT_CHARS/HARD_LIMIT_CHARS 等截断常量 ★
    private static final int MAX_INPUT_LENGTH = 300;
    private static final int SUMMARY_MAX_TOKENS = 256;
    private static final long TIMEOUT_MS = 10_000;
    
    private static final String SYSTEM_PROMPT = """
        You are a tool result summarizer. Generate a concise summary of what the tool 
        call accomplished. The summary should be:
        - One line, under 100 characters
        - Past tense verb + key noun (like a git commit subject)
        - Drop articles and connectors
        
        Examples:
        - Searched authentication logic in auth/
        - Read 15 files matching *.java pattern
        - Listed directory contents of /src/main
        - Executed 'npm test' with 3 failures
        """;

    // ★ 新增字段: 注入 LlmProviderRegistry（现有类仅有 TokenCounter 依赖）★
    private final LlmProviderRegistry providerRegistry;
    private final TokenCounter tokenCounter;

    // ★ 构造函数需从现有的 ToolResultSummarizer(TokenCounter) 修改为重载形式: ★
    // ★ 保留旧构造函数以保持向后兼容 ★
    public ToolResultSummarizer(TokenCounter tokenCounter) {
        this(null, tokenCounter); // LLM 摘要功能禁用，仅使用截断策略
    }
    
    public ToolResultSummarizer(LlmProviderRegistry providerRegistry,
                                 TokenCounter tokenCounter) {
        this.providerRegistry = providerRegistry;
        this.tokenCounter = tokenCounter;
    }

    /**
     * ★ 新增方法: 对工具输出生成摘要（如果超过阈值）。★
     * 失败时自动回退到现有的 truncateToolResult() 截断策略。
     * 
     * @param toolOutput 原始工具输出
     * @param maxTokens  输出的最大 token 数阈值
     * @return 摘要文本，或原始输出（如果未超阈值或生成失败）
     */
    public String summarizeIfNeeded(String toolName, String toolInput,
                                     String toolOutput, int maxTokens) {
        if (toolOutput == null || toolOutput.isBlank()) return toolOutput;
        
        int estimatedTokens = tokenCounter.estimateTokens(toolOutput);
        if (estimatedTokens <= maxTokens) {
            return toolOutput; // 未超阈值，直接返回
        }
        
        // ⚠️ “内部异步、外部同步”模式：对外同步 API，内部异步调用 LLM + 超时等待
        if (providerRegistry == null) {
            // LLM 摘要功能未启用（旧构造函数兼容模式），直接截断
            return truncate(toolOutput, maxTokens);
        }
        
        try {
            // 内部异步调用 LLM，用 CompletableFuture.get() 同步等待结果
            return CompletableFuture.supplyAsync(() ->
                    generateSummary(toolName, toolInput, toolOutput))
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("工具摘要生成失败, 降级为截断: tool={}, error={}", toolName, e.getMessage());
            return truncate(toolOutput, maxTokens);
        }
    }

    /**
     * ★ 新增方法: 异步生成摘要（用于非阻塞场景）。★
     */
    public CompletableFuture<String> summarizeAsync(String toolName, String toolInput,
                                                      String toolOutput, int maxTokens) {
        return CompletableFuture.supplyAsync(() ->
                summarizeIfNeeded(toolName, toolInput, toolOutput, maxTokens))
            .orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .exceptionally(e -> {
                log.warn("异步工具摘要超时/失败: {}", e.getMessage());
                return truncate(toolOutput, maxTokens);
            });
    }

    // ★ 新增私有方法 ★
    private String generateSummary(String toolName, String toolInput, String toolOutput) {
    String model = providerRegistry.getLightweightModel(); // 使用轻量级模型（依赖 P-AGENT-01 模型别名机制实现后可改为 resolveModelAlias("haiku"))
        LlmProvider provider = providerRegistry.getProvider(model);
        
        String userPrompt = "Tool: " + toolName + "\n"
                + "Input: " + truncateStr(toolInput, MAX_INPUT_LENGTH) + "\n"
                + "Output: " + truncateStr(toolOutput, MAX_INPUT_LENGTH) + "\n\n"
                + "Summary:";
        
        String response = provider.chatSync(model, SYSTEM_PROMPT, userPrompt,
                SUMMARY_MAX_TOKENS, null, TIMEOUT_MS);
        
        return "[工具摘要] " + response.trim() + "\n\n"
                + "[原始输出已压缩, 原长度: " + toolOutput.length() + " 字符]";
    }

    // ★ 以下两个截断辅助方法仅服务于新增的 LLM 摘要功能，现有类已有 truncateToolResult() 方法 ★
    private String truncate(String text, int maxTokens) {
        // 简单按字符比例截断
        int maxChars = maxTokens * 4; // 粗略估算
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... [已截断, 总长 " + text.length() + " 字符]";
    }

    private String truncateStr(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
```

#### 在 QueryEngine 中集成

在 QueryEngine 的工具执行后处理区域调用 `ToolResultSummarizer`:

```java
// QueryEngine.java L524-531 工具结果后处理（已有代码）
List<Message> currentMessages = state.getMessages();
List<Message> processedMessages = toolResultSummarizer
        .processToolResults(currentMessages, turn);
if (processedMessages != currentMessages) {
    state.setMessages(processedMessages);
}
// 以上是已有的 ToolResultSummarizer 调用点，但当前实现仅做截断处理
```

### 8.5 测试验证

```java
@Test
void shouldSummarizeWhenOutputExceedsThreshold() {
    String longOutput = "x".repeat(50000); // 超长输出
    String result = summarizer.summarizeIfNeeded("Bash", "ls -la", longOutput, 1000);
    assertTrue(result.contains("[工具摘要]"));
    assertTrue(result.length() < longOutput.length());
}

@Test
void shouldReturnOriginalWhenBelowThreshold() {
    String shortOutput = "file1.java\nfile2.java";
    String result = summarizer.summarizeIfNeeded("Bash", "ls", shortOutput, 1000);
    assertEquals(shortOutput, result);
}
```

---

## 九、自动压缩阈值校准修复 (P-CTX-02)

> **严重度**: P2 中优先 | **工时**: 1 天 | **依赖**: 无

### 9.1 问题描述

- **现象描述**: `shouldAutoCompactBufferBased()` 方法在仅 1 条消息时即触发压缩检查
- **影响范围**: 频繁不必要的压缩浪费资源，增加延迟
- **严重等级**: P2

### 9.2 根因分析

**代码层面**: `CompactService.java` 第 174-179 行：

```java
public boolean shouldAutoCompactBufferBased(List<Message> messages, int contextWindowSize) {
    int effectiveWindow = contextWindowSize - contextWindowSize / 4;  // 75% 窗口
    int threshold = effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS;       // 减去 13000 buffer
    int estimatedTokens = tokenCounter.estimateTokens(messages);
    return estimatedTokens > threshold;
    // 问题: 缺少最低消息数量守卫！1 条消息也会进入比较
}
```

当 `contextWindowSize` 较小或 `tokenCounter.estimateTokens()` 对单条消息返回较高值时（如系统提示词本身就占用大量 token），1 条消息就可能超过阈值。

### 9.3 Claude Code 参考实现

原版 `/claudecode/src/services/compact/autoCompact.ts` 第 160-238 行：

```typescript
export async function shouldAutoCompact(messages, model, querySource, snipTokensFreed = 0) {
    // 递归守卫: session_memory 和 compact 来源不触发
    if (querySource === 'session_memory' || querySource === 'compact') return false;
    
    // 功能开关检查
    if (!isAutoCompactEnabled()) return false;
    
    // 计算 token 和阈值
    const tokenCount = tokenCountWithEstimation(messages) - snipTokensFreed;
    const threshold = getAutoCompactThreshold(model);
    // threshold = effectiveContextWindow - 13_000 (AUTOCOMPACT_BUFFER_TOKENS)
    
    return calculateTokenWarningState(tokenCount, model).isAboveAutoCompactThreshold;
}
```

关键差异：原版通过 `effectiveContextWindow - 13000` 计算阈值（对于 200K 上下文窗口，阈值约 167K），且有递归守卫和功能开关，不会在低消息量时误触发。

### 9.4 修复方案

**文件**: `backend/src/main/java/com/aicodeassistant/engine/CompactService.java`  
**行号**: L174-179

```java
// ===== 修改前 =====
public boolean shouldAutoCompactBufferBased(List<Message> messages, int contextWindowSize) {
    int effectiveWindow = contextWindowSize - contextWindowSize / 4;
    int threshold = effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS;
    int estimatedTokens = tokenCounter.estimateTokens(messages);
    return estimatedTokens > threshold;
}

// ===== 修改后 =====
public boolean shouldAutoCompactBufferBased(List<Message> messages, int contextWindowSize) {
    // ★ 守卫 1: 最低消息数量检查 — 至少 5 条消息才考虑压缩 ★
    if (messages.size() < MIN_MESSAGES_FOR_COMPACT) {
        return false;
    }
    
    // ★ 守卫 2: 排除纯系统消息（如仅包含系统提示词） ★
    long userOrAssistantCount = messages.stream()
            .filter(m -> m instanceof Message.UserMessage || m instanceof Message.AssistantMessage)
            .count();
    if (userOrAssistantCount < 2) {
        return false;
    }
    
    int effectiveWindow = contextWindowSize - Math.max(contextWindowSize / 4, MAX_OUTPUT_RESERVE);
    int threshold = effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS;
    int estimatedTokens = tokenCounter.estimateTokens(messages);
    
    log.debug("自动压缩检查: tokens={}, threshold={}, effectiveWindow={}, messages={}",
            estimatedTokens, threshold, effectiveWindow, messages.size());
    
    return estimatedTokens > threshold;
}

// 新增常量
private static final int MIN_MESSAGES_FOR_COMPACT = 5;
private static final int MAX_OUTPUT_RESERVE = 20_000; // 对齐原版 MAX_OUTPUT_TOKENS_FOR_SUMMARY
```

### 9.5 测试验证

```java
@Test
void shouldNotCompactWithSingleMessage() {
    List<Message> oneMsg = List.of(createUserMessage("Hello"));
    assertFalse(compactService.shouldAutoCompactBufferBased(oneMsg, 200000));
}

@Test
void shouldNotCompactWithFewMessages() {
    List<Message> fewMsgs = List.of(
        createUserMessage("Hello"),
        createAssistantMessage("Hi there"),
        createUserMessage("What's 2+2?")
    );
    assertFalse(compactService.shouldAutoCompactBufferBased(fewMsgs, 200000));
}

@Test
void shouldCompactWhenTokensExceedThresholdWithSufficientMessages() {
    // 创建 10 条超长消息，总 token > threshold
    List<Message> manyLongMsgs = createLongMessageList(10, 20000);
    assertTrue(compactService.shouldAutoCompactBufferBased(manyLongMsgs, 200000));
}
```

---

## 十、BashParser 测试修复 (P-BASH-01)

> **严重度**: P1 高优先 | **工时**: 1 天 | **依赖**: 无

### 10.1 问题描述

- **现象描述**: `BashParserGoldenTest.java` (538行) 中部分测试可能因环境配置问题导致失败（确切失败数量需运行测试确认）
- **影响范围**: 大量 BashTool 安全回归测试可能无法执行
- **严重等级**: P1 — 测试基础设施问题

### 10.2 根因分析

> **⚠️ 源码事实校正**: `BashParserGoldenTest.java` 的 `@BeforeEach setUp()` 方法（L29-33）**完全不引用 `AppStateStore`**。实际初始化逻辑为：
> ```java
> @BeforeEach
> void setUp() {
>     parser = new BashParser();
>     analyzer = new BashSecurityAnalyzer(new PathValidator(), null);
> }
> ```
> `BashSecurityAnalyzer` 的第二个参数传入 `null`，此处传的是 `AppStateStore` 参数位置。如果 `BashSecurityAnalyzer` 在安全分析时调用了 `appStateStore.getState()` 获取工作目录，则会因 null 导致 NPE。
>
> **真实根因**: 问题不在于 "AppStateStore 未正确初始化"，而是 `BashSecurityAnalyzer` 构造时显式传入 `null` 作为 `AppStateStore` 参数。需要确认哪些 golden 测试用例触发了路径安全分析中依赖 AppStateStore 的代码路径。

### 10.3 修复方案

**文件**: `backend/src/test/java/com/aicodeassistant/tool/bash/BashParserGoldenTest.java`

在 `@BeforeEach` 方法中将 `null` 替换为实际的 `AppStateStore` 实例：

> **❗ 风险说明**: 手动创建 `AppStateStore` 绕过了 Spring DI，可能导致与生产环境行为不一致。建议采用以下优先级方案：
>
> | 方案 | 优先级 | 说明 |
> |------|---------|------|
> | 方案 1：传入真实 AppStateStore | ⭐ 推荐 | 创建真实的 `AppStateStore` 并设置工作目录，传入 `BashSecurityAnalyzer` 构造函数 |
> | 方案 2：`@SpringBootTest` | ✅ 可用 | 将测试类加上 `@SpringBootTest` 注解，自动注入所有依赖 |
> | 方案 3：防御性 fallback | ✅ 推荐补充 | 在 BashSecurityAnalyzer 中添加 null 检查 |

```java
// 方案 1（推荐）：将 null 替换为真实 AppStateStore
@BeforeEach
void setUp() {
    parser = new BashParser();
    // ★ 创建真实的 AppStateStore 并设置工作目录 ★
    AppStateStore appStateStore = new AppStateStore();
    appStateStore.setState(state -> state.withSession(s ->
            s.withWorkingDirectory(System.getProperty("user.dir"))));
    analyzer = new BashSecurityAnalyzer(new PathValidator(), appStateStore);
}
```

或者，**方案 3（推荐补充）**: 在 BashSecurityAnalyzer 中添加防御性 null 检查，确保即使 AppStateStore 为 null 也不会 NPE。

> **⚠️ 特别注意**: `BashSecurityAnalyzer.java` L213 处存在未做 null 检查就直接访问 `appStateStore` 的代码路径，建议在所有访问 `appStateStore` 的位置都添加防御性 null 检查（包括但不限于 L213）：

```java
// BashSecurityAnalyzer.java — 获取工作目录时添加防御性 fallback
String cwd = (appStateStore != null)
    ? appStateStore.getState().session().workingDirectory()
    : System.getProperty("user.dir"); // 防御性 fallback
if (cwd == null || cwd.isBlank()) {
    cwd = System.getProperty("user.dir");
}
```

### 10.4 测试验证

```bash
cd backend && mvn test -Dtest="BashParserGoldenTest"
# 预期: 全部测试通过（具体通过数量需运行确认）
```

---

## 十一、Worker 权限冒泡修复 (P-AGENT-02)

> **严重度**: P1 高优先 | **工时**: 3-5 天 | **依赖**: P-PERM-01

### 11.1 问题描述

- **现象描述**: Worker Agent 执行敏感工具时，直接按自身权限模式处理，无法将权限请求冒泡到 Leader/Coordinator
- **影响范围**: FULL_AUTO 模式下的 Worker 可能绕过 Leader 的权限策略
- **严重等级**: P1 — 权限安全漏洞

### 11.2 根因分析

**代码层面**: `SubAgentExecutor.java` 的 `executeSync()` 方法（L95-200）中，Worker Agent 创建时**未显式设置权限模式**。子代理通过 `ToolUseContext` 传递上下文（L157-160），包括 `parentSessionId`，但没有单独的权限模式设置逻辑。子代理执行工具时，`ToolExecutionPipeline` 通过 `PermissionModeManager.getMode(sessionId)` 获取会话级权限模式，而子代理使用独立的 `childSessionId = "subagent-" + agentId`（L151），该 sessionId 可能没有对应的权限模式配置，导致回退到默认模式。

> **源码事实校正**:
- SubAgentExecutor 中 **当前不存在** `resolveWorkerPermissionMode()` 方法（**⚠️ 需新增**，见 11.4 修复方案），也没有"复制父权限模式"的显式代码。实际问题是子代理的 childSessionId 未在 PermissionModeManager 中注册权限模式。
> - ❗ **重要**: `ToolExecutionPipeline.java` 中 **已存在** BUBBLE 模式的处理逻辑（L150-159），包括 `decision.bubble()` 检查和 `forwardPermissionToParent()` 方法（L255-292）。该方法通过 `context.parentSessionId()` 将权限请求转发给父代理的前端界面。
> - 因此，真正缺失的不是 Pipeline 层的 bubble 处理，而是 **SubAgentExecutor 层未为子代理设置合适的权限模式**，导致 Pipeline 的 bubble 分支不会被触发。

### 11.3 Claude Code 参考实现

原版通过权限传递规则（架构图 18）实现层级权限控制：
- Worker 默认使用 BUBBLE 模式
- 敏感工具调用通过消息队列上报到 Leader
- Leader 代理决策或转发到用户确认

### 11.4 修复方案

> **❗ 重要前提**: `ToolExecutionPipeline.java` 中 **已存在** BUBBLE 模式的完整处理逻辑（L150-159 检查 `decision.bubble()` + L255-292 `forwardPermissionToParent()`），因此不需要修改 Pipeline。修复焦点在 SubAgentExecutor 层，确保子代理会话的权限模式被正确设置为 BUBBLE。

```java
// SubAgentExecutor.java — 在 executeSync() 的步骤 7（L157前）添加权限模式设置
// **⚠️ 需新增**: resolveWorkerPermissionMode() 方法当前不存在于 SubAgentExecutor 中，需新增以下逻辑：
//
// ★ 前置条件 1: SubAgentExecutor 当前的 10 个 private final 依赖中
// ★ 不包含 PermissionModeManager，**必须在构造函数中新增此依赖注入** ★
// ★ （结合第四章步骤 2 的构造函数修改，从 11 参数扩展为 12 参数） ★
//
// ★ 前置条件 2: 需在构造函数参数列表末尾追加 PermissionModeManager 参数，★
// ★ 并在构造函数体中赋值 this.permissionModeManager = permissionModeManager ★

// 新增依赖字段
private final PermissionModeManager permissionModeManager;  // ★ 新增 ★

// 构造函数需从 11 参数（第四章步骤 2 后）扩展为 12 参数
public SubAgentExecutor(
        /* ... 现有 11 个参数 ... */
        PermissionModeManager permissionModeManager) {  // ★ 新增 ★
    // ... 原有赋值 ...
    this.permissionModeManager = permissionModeManager;
}

// === ★ 新增: 在 childSessionId 创建后设置权限模式 ★ ===
String childSessionId = "subagent-" + request.agentId();  // 已有代码 L151
// ★ 新增: 为子代理会话设置权限模式 ★
PermissionMode workerMode = resolveWorkerPermissionMode(
        request.agentType(), parentContext);
permissionModeManager.setMode(childSessionId, workerMode);

// === ★ 新增私有方法（SubAgentExecutor 中当前不存在，⚠️ 需新增） ★ ===
private PermissionMode resolveWorkerPermissionMode(
        String agentType, ToolUseContext parentContext) {
    // Worker 类型使用 BUBBLE 模式，权限请求上报到父代理
    if ("explore".equalsIgnoreCase(agentType)
            || "verification".equalsIgnoreCase(agentType)) {
        return PermissionMode.BUBBLE;
    }
    // 其他类型: 继承父代理的权限模式
    PermissionMode parentMode = permissionModeManager.getMode(
            parentContext.sessionId());
    return parentMode != null ? parentMode : PermissionMode.DEFAULT;
}
```

> **注意**: 不再需要修改 `ToolExecutionPipeline.java`，因为其已有的 BUBBLE 处理逻辑（L150-159）会在 `PermissionDecision.bubble() == true` 时自动触发，通过 `context.parentSessionId()` 转发权限请求。只需确保：
> 1. 子代理的 `PermissionModeManager` 中注册了 BUBBLE 模式
> 2. 子代理的 `ToolUseContext` 中设置了 `parentSessionId`（已有，L159）
> 3. `PermissionPipeline.checkPermission()` 返回的 `PermissionDecision` 在 BUBBLE 模式下设置 `bubble=true`（需确认）

### 11.5 测试验证

```java
@Test
void workerAgent_shouldUseBubblePermission() {
    // 测试 resolveWorkerPermissionMode 新增逻辑
    PermissionMode resolved = resolveWorkerPermissionMode(
            "explore", parentContext);
    assertEquals(PermissionMode.BUBBLE, resolved);
}

@Test
void bubblePermission_shouldReportToLeader() {
    // 模拟 Worker 执行敏感工具
    // 验证权限请求被上报到 Coordinator
    verify(coordinatorService).bubblePermissionRequest(any());
}
```

---

## 十二、已修复问题确认

以下问题在端到端测试期间已被确认修复，无需再次处理：

### 12.1 MemoizedSection 类实现（已确认存在）

- **原始问题**: 曾担心 `MemoizedSection` 类缺失导致编译失败
- **实际状态**: `MemoizedSection` 是 `SystemPromptSection.java`（L50-59）中的 `record`，实现 `SystemPromptSection` sealed interface。同文件还包含 `UncachedSection` record（L68-79）。两者均已正确实现。
- **文件位置**: `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptSection.java`（而非独立的 `MemoizedSection.java`）
- **验证状态**: ✅ 编译通过，相关 System Prompt 构建测试全部通过
- **验证命令**: `mvn compile -pl backend` 无 MemoizedSection 相关错误

### 12.2 ToolUseContext NPE（已修复）

- **原始问题**: `ToolUseContext` 在部分工具执行路径中为 null，导致 NPE
- **修复方式**: 在 `ToolExecutionPipeline` 中确保 context 非空初始化
- **验证状态**: ✅ 单元测试通过，端到端工具执行场景无 NPE
- **验证命令**: `mvn test -Dtest="ToolExecutionPipelineTest"` 全部通过

### 12.3 LlmProviderRegistry 模型列表异常（已修复）

- **原始问题**: `LlmProviderRegistry.listAvailableModels()` 返回空列表
- **修复方式**: 修复 Provider 注册逻辑，确保 Spring Bean 初始化顺序正确
- **验证状态**: ✅ 模型列表正常返回，`/api/models` 端点响应包含可用模型
- **验证命令**: `curl http://localhost:8080/api/models | jq '.models | length'` 返回 > 0

### 12.4 REST API 权限阻塞（已修复）

- **原始问题**: REST API 在 DEFAULT 权限模式下每次工具调用都弹出权限确认对话框，阻塞自动化测试
- **修复方式**: `QueryController.java` 三个 REST 端点在执行前均设置 `permissionModeManager.setMode(sessionId, PermissionMode.BYPASS_PERMISSIONS)`（见 L106、L184、L257）
- **验证状态**: ✅ REST API 在 BYPASS_PERMISSIONS 模式下工具调用不再阻塞
- **验证命令**: 端到端测试脚本 `POST /api/query` 可连续执行多次工具调用
- **注意**: 此修复仅解决“权限阻塞”问题，“消息未持久化”是独立问题（见第三章 P-CTX-01）

---

## 十三、修复实施路线图

### 13.1 阶段概览

```
┌─────────────────────────────────────────────────────────────┐
│  Phase 1: 紧急修复 (1-2 周)                                  │
│  目标: 消除所有 P0 + 核心 P1，恢复核心功能                      │
│  预期评分: 80.6 → 82.9                                       │
├─────────────────────────────────────────────────────────────┤
│  Phase 2: 功能完善 (2-3 周)                                   │
│  目标: 实现智能权限分类、工具摘要、Worker 权限冒泡              │
│  预期评分: 82.9 → 87.1                                       │
├─────────────────────────────────────────────────────────────┤
│  Phase 3: 测试完善 (1-2 周)                                   │
│  目标: 修复所有测试 NPE、补充单元测试覆盖                      │
│  预期评分: 87.1 → 88.8                                       │
├─────────────────────────────────────────────────────────────┤
│  Phase 4: 可选优化 (3-5 天)                                   │
│  目标: newContext 机制、WebSocket 优化、代码清理                │
│  预期评分: 88.8 → 89+                                        │
└─────────────────────────────────────────────────────────────┘
```

### 13.2 依赖关系

> **⚠️ 工时校正**: Phase 2 工时可能低估约 20%，主要原因：
> - P-AG-01 需要重构现有 ToolResultSummarizer（非新建），还需确保与现有 processToolResults() 兼容
> - P-AGENT-02 的 ToolExecutionPipeline 已有 BUBBLE 处理，但需确认 PermissionPipeline.checkPermission() 在 BUBBLE 模式下的行为
> - P-TOOL-01 需要新增 pom.xml 依赖 + 新方法开发 + Javadoc 更新
>
> **Phase 2 工时含 20% 缓冲估算**：
> | 任务 | 原估 | +20% 缓冲 | 建议工时 |
> |------|------|------------|----------|
> | P-PERM-01 | 2-3天 | +0.5天 | 2.5-3.5天 |
> | P-AG-01 | 3-5天 | +0.8天 | 4-6天 |
> | P-AGENT-02 | 3-5天 | +0.8天 | 4-6天 |
> | P-TOOL-01 | 3-5天 | +0.8天 | 4-6天 |
> | P-CTX-02 | 1天 | +0.2天 | 1.2天 |
> | **Phase 2 合计** | **12-19天** | **+3.1天** | **15.7-22.7天** |

```
P-PERM-02 (前端权限同步)         ← 无依赖, 最先修复
P-CTX-01  (REST 消息持久化)      ← 无依赖, 并行修复
P-AGENT-01 (子代理模型映射)       ← 无依赖, 并行修复
P-FE-01   (会话错误处理)          ← 无依赖, 并行修复
P-TOOL-04 (文件工具测试NPE)      ← 无依赖, 并行修复
    │
    ▼
P-PERM-01 (AutoModeClassifier)   ← resolveClassifierModel() 已存在（L114-125），不依赖 P-AGENT-01（但建议补充 @Value 配置字段）
P-AG-01   (工具摘要)              ← 重构现有 ToolResultSummarizer（非新建），依赖 P-AGENT-01 的模型别名机制（或使用 getLightweightModel() 降级）
P-AGENT-02 (Worker 权限冒泡)     ← Pipeline 已有 BUBBLE 处理（L150-159），仅需在 SubAgentExecutor 中注册权限模式；依赖 P-PERM-01（确认 PermissionPipeline 在 BUBBLE 模式下的行为）
P-CTX-02  (压缩阈值)             ← 无依赖, 可并行
    │
    ▼
P-BASH-01 (BashParser 测试)      ← 无依赖
P-CTX-04  (上下文管理测试)        ← 无依赖
P-AGENT-03 (模型枚举)            ← 依赖 P-AGENT-01
P-SP-01   (System Prompt)        ← 无依赖, 但工作量大
```

### 13.3 Phase 1 详细时间线

| 日 | 任务 | 负责人 | 完成标准 |
|----|------|--------|---------|
| D1 | P-PERM-02 前端权限同步 | 前端 | dispatch.ts handler 完成 + 单元测试通过 |
| D1 | P-FE-01 会话错误处理 | 前端 | App.tsx 修改 + 错误场景手工验证 |
| D2 | P-AGENT-01 模型别名机制 | 后端 | resolveModelAlias + 配置 + 单元测试 |
| D2 | P-TOOL-04 文件工具测试 | 后端 | 21 个测试全部通过 |
| D3-5 | P-CTX-01 REST 消息持久化 | 后端 | 三端点消息保存 + 跨请求多轮对话验证 |
| D5 | Phase 1 集成验证 | 全员 | 端到端回归测试通过 |

### 13.4 成功指标

| 指标 | 当前 | Phase 1 后 | Phase 2 后 | Phase 3 后 |
|------|------|-----------|-----------|-----------|
| 测试通过率 | 90.9% (728/801) | 95%+ | 97%+ | 98%+ |
| P0 问题数 | 1 | 0 | 0 | 0 |
| P1 问题数 | 7 | 2 | 0 | 0 |
| 端到端场景通过 | 5/8 | 7/8 | 8/8 | 8/8 |
| 综合评分 | 80.6 | 82.9 | 87.1 | 88.8 |
| REST 多轮对话 | ❌ 失败 | ✅ 通过 | ✅ 通过 | ✅ 通过 |
| 子代理可用性 | ❌ 失败 | ✅ 通过 | ✅ 通过 | ✅ 通过 |
| 权限前端同步 | ❌ 未实现 | ✅ 完成 | ✅ 完成 | ✅ 完成 |

### 13.5 风险和缓解措施

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| REST 消息持久化引入并发问题 | 中 | 消息重复或丢失 | @Transactional + DB 唯一索引 + 并发测试 |
| 模型别名机制覆盖不全 | 低 | 部分模型无法解析 | 四级回退 + 配置文件兜底 + 日志告警 |
| AutoModeClassifier LLM 调用延迟 | 中 | 权限检查变慢 | 分类器超时 5s + 超时默认 ALLOW + 缓存机制 |
| Worker 权限冒泡消息丢失 | 低 | 权限决策挂起 | 超时兜底 + 重试机制 + 审计日志 |
| 工具摘要 LLM 调用成本增加 | 中 | API 费用上升 | 仅超阈值触发 + 异步 + 使用最轻量模型 |

---

## 十四、Schema 验证完整实现 (P-TOOL-01)

> **严重度**: P1 高优先 | **工时**: 3-5 天 | **依赖**: 无

### 14.1 问题描述

- **现象描述**: `ToolExecutionPipeline.java` 的工具执行管线中，阶段 1（Schema 验证）仅通过日志记录入口（L89-90），实际没有执行任何 JSON Schema 验证。工具输入验证仅由阶段 2 的 `Tool.validateInput()` 方法完成（L93），该方法默认返回 `ValidationResult.ok()`，仅部分工具（如 CronCreateTool、CronDeleteTool、WebBrowserTool）重写了自定义验证。
- **源码事实**: ToolExecutionPipeline 中不存在 `validateSchema()` 方法（**需新增**）。实际的 **7 阶段**流程为：① Schema 验证（仅日志，未实现）→ ② 工具自定义验证 `tool.validateInput()` → ②.5 输入预处理 `backfillObservableInput` → ③ PreToolUse 钩子 → ④ 权限检查 → ⑤ 工具调用 → ⑥ 结果处理 + PostToolUse 钩子 + 敏感信息过滤 → ⑦ contextModifier 提取与应用（L229-235）
- **❗ 注意**: 源码 Javadoc（L20-28）写的是“6 阶段”，但实际代码包含 7 个阶段（含阶段 7: contextModifier）。`json-schema-validator` 依赖未在 `pom.xml` 中（**需新增**）
- **影响范围**: 工具输入参数不会被严格校验，不合法参数可能导致工具执行异常或安全风险
- **严重等级**: P1 — 工具系统健壮性

### 14.2 根因分析

**代码层面**: `ToolExecutionPipeline.java` L83-99 的 `doExecute()` 方法中，阶段 1（L89-90）仅有日志输出，没有实际的 Schema 验证逻辑。阶段 2（L92-99）通过 `tool.validateInput(input, context)` 执行工具自定义验证：

```java
// ToolExecutionPipeline.java L83-99 实际代码
private ToolExecutionResult doExecute(Tool tool, ToolInput input, ToolUseContext context,
                              PermissionNotifier wsPusher) {
    // ── 阶段 1: Schema 输入验证 ──
    log.debug("Executing tool: {} (stage 1: validation)", toolName);
    // ← 此处仅有日志，无实际验证逻辑

    // ── 阶段 2: 工具自定义验证 ──
    ValidationResult validation = tool.validateInput(input, context);
    // ...
}
```

`Tool.validateInput()` 默认实现（`Tool.java` L128-130）返回 `ValidationResult.ok()`，只有部分工具重写了自定义验证。因此，大部分工具的输入参数实际上未被任何 Schema 验证。

> **与建议的关系**: 本修复方案建议在阶段 1 中添加基于 JSON Schema Draft-7 的通用验证，与阶段 2 的 `Tool.validateInput()` 形成两层验证：阶段 1 验证类型/必填/枚举，阶段 2 验证业务逻辑（如文件路径合法性、cron 表达式格式等）。

### 14.3 Claude Code 参考实现

原版通过 JSON Schema 验证库对工具输入进行严格校验：

```typescript
// /claudecode/src/Tool.ts
// 原版使用 zod 或 JSON Schema 验证工具输入
// 每个工具定义 inputSchema，框架在执行前自动验证
// 验证失败返回明确的错误信息给 LLM
```

### 14.4 修复方案

#### 步骤 1: 添加 JSON Schema 验证依赖

**文件**: `backend/pom.xml`  
**操作**: **需新增依赖** — 当前 `pom.xml` 中不包含 `json-schema-validator`

```xml
<!-- 添加 networknt JSON Schema 验证器 -->
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.3.3</version>
</dependency>
```

#### 步骤 2: 实现完整的 Schema 验证

**文件**: `backend/src/main/java/com/aicodeassistant/tool/ToolExecutionPipeline.java`  
**行号**: L89-90（阶段 1 日志后插入新逻辑）

> **设计说明**: 新增的 JSON Schema 验证作为阶段 1 的实现，与阶段 2 的 `Tool.validateInput()` 互补。阶段 1 做通用的类型/必填/枚举验证，阶段 2 做工具特定的业务逻辑验证。验证失败时以 `ToolResult.error()` 返回错误信息给 LLM，而非抛出异常。
> 
> **⚠️ 注意**: 管线实际为 **7 阶段**（含阶段 7: contextModifier，L229-235），而非源码 Javadoc 中写的 6 阶段。建议同时更新 Javadoc 注释。
>
> **⚠️ 使用现有异常类**: 源码中 **已存在** `ToolInputValidationException`（位于 `com.aicodeassistant.tool` 包下），因此无需新建 `ToolValidationException`。本方案统一使用现有的 `ToolInputValidationException` 以避免命名冲突和类膨胀。
>
> **⚠️ Tool 接口需新增 getSchema() 方法**: 当前 `Tool` 接口不包含 `getSchema()` 方法，需新增并提供 `default` 实现：
> ```java
> // Tool.java 中新增（⚠️ 需新增）
> default Map<String, Object> getSchema() {
>     return Collections.emptyMap(); // 默认无 schema，子类可覆写提供具体 JSON Schema
> }
> ```
>
> **⚠️ getSchema() 与 getInputSchema() 职责区分**: Tool 接口已有 `getInputSchema()`（L32），其职责是返回工具输入参数定义供 LLM 在 function calling 时使用（描述参数名称、类型、说明等）。新增的 `getSchema()` 职责不同：返回 JSON Schema 用于在工具执行前对 LLM 传入的实际参数进行**结构化验证**（类型校验、必填项检查、枚举约束等）。二者互补而非重复。
>
> **完整 import 列表**（需添加到 `ToolExecutionPipeline.java` 头部）：
> ```java
> import com.networknt.schema.JsonSchema;
> import com.networknt.schema.JsonSchemaFactory;
> import com.networknt.schema.SpecVersion;
> import com.networknt.schema.ValidationMessage;
> import com.fasterxml.jackson.databind.JsonNode;
> import com.fasterxml.jackson.databind.ObjectMapper;
> import java.util.Set;
> import java.util.stream.Collectors;
> ```

```java
// ===== 修改后：在阶段 1 日志后插入 JSON Schema 验证 =====
// ★ 此方法为新增（当前源码中不存在 validateSchema 方法，需新增到 ToolExecutionPipeline.java）
// ★ 集成位置：在 doExecute() 方法的 L90（阶段 1 日志）和 L93（阶段 2 tool.validateInput()）之间插入调用 ★
// ★ 即：在 log.debug("stage 1: validation")（L90）之后、ValidationResult validation = tool.validateInput()（L93）之前 ★
private void validateSchema(Object input, Map<String, Object> schema) {
    if (schema == null || schema.isEmpty()) {
        return; // 无 schema 定义，跳过验证
    }
    
    try {
        // 将 input 转为 JsonNode
        JsonNode inputNode = objectMapper.valueToTree(input);
        
        // 将 schema Map 转为 JsonSchema
        JsonNode schemaNode = objectMapper.valueToTree(schema);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V7);
        JsonSchema jsonSchema = factory.getSchema(schemaNode);
        
        // 执行验证
        Set<ValidationMessage> errors = jsonSchema.validate(inputNode);
        if (!errors.isEmpty()) {
            String errorMsg = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
            throw new ToolInputValidationException(
                    "Input validation failed: " + errorMsg);
        }
    } catch (ToolInputValidationException e) {
        throw e; // 重新抛出验证异常
    } catch (Exception e) {
        log.warn("Schema validation error (non-blocking): {}", e.getMessage());
        // 验证框架本身出错时降级为不验证，避免阻塞工具执行
        // ⚠️ 降级策略说明：此降级仅针对 Schema 定义本身的解析错误（如 Schema JSON 格式错误、
        // JsonSchemaFactory 初始化异常等），不适用于输入数据验证失败的场景（验证失败由上方
        // ToolInputValidationException 分支处理，会正常抛出异常阻止工具执行）
    }
}
```

#### 修改文件清单

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `backend/pom.xml` | **需新增依赖** | json-schema-validator（当前不在 pom.xml 中） |
| `backend/.../tool/ToolInputValidationException.java` | **已存在，无需新建** | 源码中已有 `ToolInputValidationException`，直接复用 |
| `backend/.../tool/Tool.java` | **需新增方法** | 新增 `default Map<String, Object> getSchema()` 方法 |
| `backend/.../tool/ToolExecutionPipeline.java` | **需新增方法** L90 和 L93 之间插入 | 新增 `validateSchema()` 方法并在阶段 1 调用；同时建议更新 Javadoc 为"7 阶段"；新增 import（见上方完整 import 列表） |

### 14.5 测试验证

```java
@Test
void shouldRejectInvalidToolInput() {
    // Schema 要求 "path" 为 string 且必填
    Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", Map.of("path", Map.of("type", "string")),
            "required", List.of("path"));
    
    // Input 缺少必填字段
    Object input = Map.of("other", "value");
    
    assertThrows(ToolInputValidationException.class,
            () -> pipeline.validateSchema(input, schema));
}

@Test
void shouldAcceptValidToolInput() {
    Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", Map.of("path", Map.of("type", "string")),
            "required", List.of("path"));
    
    Object input = Map.of("path", "/src/main/java");
    
    assertDoesNotThrow(() -> pipeline.validateSchema(input, schema));
}
```

---

## 十五、System Prompt 内容扩充方案 (P-SP-01)

> **严重度**: P2 中优先 | **工时**: 1-2 周 | **依赖**: 无

### 15.1 问题描述

- **现象描述**: 当前 System Prompt 总输出约 18.6KB，而原版约 40KB，覆盖率仅 55%
- **影响范围**: LLM 行为指导不充分，边界条件处理能力弱
- **严重等级**: P2 — 影响 LLM 输出质量一致性

### 15.2 根因分析

`SystemPromptBuilder.java` (1,106 行) 的 8 段静态 + 12 段动态架构完整，但内容精简策略导致每段内容量不足。缺失的内容集中在：

1. **详细的工具使用示例** — 每个工具仅有基础描述，缺少使用示例和常见错误说明
2. **边界条件处理指南** — 缺少文件大小限制、递归深度、性能优化等指导
3. **多语言代码风格规范** — 缺少 Python/JavaScript/Java/Go 的风格要点
4. **安全最佳实践详述** — 输入验证、路径安全等内容不够详细
5. **错误恢复策略说明** — 缺少超时、限流、工具失败的恢复指导

### 15.3 Claude Code 参考实现

原版的 System Prompt 通过多个模板文件组合生成，总量约 40KB，包含：

```
/claudecode/src/ 中的 prompt 相关内容:
- 每个工具的 prompt.ts 文件包含详细的使用说明和示例
- 例如 /tools/BashTool/prompt.ts 包含 Bash 使用规范和安全警告
- 例如 /tools/FileEditTool/prompt.ts 包含搜索替换的详细示例
- context.ts 包含上下文相关的 prompt 片段
```

### 15.4 修复方案

#### 扩充方向和内容量估算

| 扩充领域 | 当前 | 目标 | 增量 |
|---------|------|------|------|
| 工具使用示例 | ~3KB | ~10KB | +7KB |
| 边界条件指南 | ~1KB | ~4KB | +3KB |
| 代码风格规范 | ~2KB | ~5KB | +3KB |
| 安全实践 | ~1KB | ~3KB | +2KB |
| 错误恢复策略 | ~0.5KB | ~2KB | +1.5KB |
| **总计** | **~18.6KB** | **~35KB** | **+16.4KB** |

#### 实施方式

建议将大量 prompt 内容从 Java 代码中抽离到外部模板文件，通过 `SystemPromptBuilder` 在构建时加载：

> **❗ 源码事实**: `SystemPromptBuilder.java`（1,106 行）中 **不存在** `loadPromptTemplate()` 方法，实际的构建方法为 `buildSystemPrompt()` (非 `buildFullSystemPrompt()`)。`backend/src/main/resources/prompts/` 目录 **不存在**，下方列出的 5 个模板文件均 **需新建**。

**⚠️ 需新增方法** — 在 `SystemPromptBuilder.java` 中添加（当前源码中 **不存在** `loadPromptTemplate()` 方法）：

```java
// SystemPromptBuilder.java 新增（⚠️ 需新增）
private String loadPromptTemplate(String templateName) {
    try (InputStream is = getClass().getResourceAsStream(
            "/prompts/" + templateName + ".txt")) {
        if (is == null) {
            log.warn("Prompt template not found: {}", templateName);
            return "";
        }
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
        log.warn("Failed to load prompt template: {}", templateName);
        return "";
    }
}
```

**集成位置**: 在现有的 `buildSystemPrompt()` 方法内部调用 `loadPromptTemplate()`，将外部模板内容追加到构建结果中：

```java
// 在 buildSystemPrompt() 方法末尾（return 之前）追加以下代码：
// ⚠️ 需新增集成代码
promptParts.add(loadPromptTemplate("tool_examples"));
promptParts.add(loadPromptTemplate("boundary_conditions"));
promptParts.add(loadPromptTemplate("code_style_guide"));
promptParts.add(loadPromptTemplate("security_practices"));
promptParts.add(loadPromptTemplate("error_recovery"));
```

模板文件放在 `backend/src/main/resources/prompts/` 目录（**需新建目录**）：

```
resources/prompts/               ← 需新建目录
├── tool_examples.txt          # 需新建: 工具使用示例 (~7KB)
├── boundary_conditions.txt    # 需新建: 边界条件指南 (~3KB)
├── code_style_guide.txt       # 需新建: 多语言代码风格 (~3KB)
├── security_practices.txt     # 需新建: 安全最佳实践 (~2KB)
└── error_recovery.txt         # 需新建: 错误恢复策略 (~1.5KB)
```

### 15.5 测试验证

```java
@Test
void systemPrompt_shouldExceed30KB() {
    // 注意: 实际方法名为 buildSystemPrompt()，非 buildFullSystemPrompt()
    List<String> parts = systemPromptBuilder.buildSystemPrompt(
            tools, model, workingDir, additionalDirs, mcpClients);
    String prompt = String.join("", parts);
    assertTrue(prompt.length() > 30_000,
            "System Prompt should be at least 30KB, actual: " + prompt.length());
}

@Test
void systemPrompt_shouldContainToolExamples() {
    List<String> parts = systemPromptBuilder.buildSystemPrompt(
            tools, model, workingDir, additionalDirs, mcpClients);
    String prompt = String.join("", parts);
    assertTrue(prompt.contains("## Tool Usage Examples"));
    assertTrue(prompt.contains("## Error Recovery"));
}
```

---

## 十六、上下文管理单元测试补充 (P-CTX-04)

> **严重度**: P2 中优先 | **工时**: 3-5 天 | **依赖**: 无

### 16.1 问题描述

- **现象描述**: Snip、Micro、Collapse、Token 四个压缩层缺乏独立的单元测试
- **影响范围**: 上下文管理的测试覆盖率低于报告值
- **严重等级**: P2 — 测试覆盖不足

### 16.2 需要新增的测试文件

> 所有测试文件位于 `backend/src/test/java/com/aicodeassistant/engine/` 包下，与被测类同包。
>
> **⚠️ 前置条件**: 需先验证以下被测类是否存在于源码中。如不存在，需作为 **前置任务** 先创建被测类：
> - `SnipService`（L1 Snip 工具输出截断）— 如不存在，可能功能集成在 `ToolResultSummarizer` 中，需确认实际类名
> - `MicroCompactService`（L2 Micro 细粒度裁剪）— 如不存在，可能功能集成在 `CompactService` 中
> - `ContextCollapseService`（L3.5 Collapse 降级路径）— 如不存在，需作为前置任务创建
> - `TokenCounter`（已确认存在，`@Component`）

| # | 测试文件（完整路径） | 目标覆盖 | 测试用例数 |
|---|---------|---------|----------|
| 1 | `backend/src/test/.../engine/SnipServiceTest.java` | L1 Snip 工具输出截断 | ~8 个 |
| 2 | `backend/src/test/.../engine/MicroCompactServiceTest.java` | L2 Micro 细粒度裁剪 | ~10 个 |
| 3 | `backend/src/test/.../engine/ContextCollapseServiceTest.java` | L3.5 Collapse 降级路径 | ~8 个 |
| 4 | `backend/src/test/.../engine/TokenCounterTest.java` | Token 计数精度与自动检测 | ~12 个 |

### 16.3 测试用例设计

#### SnipServiceTest（L1 层）

```java
@Test void shouldSnipToolOutputExceedingThreshold() { ... }
@Test void shouldPreserveToolOutputBelowThreshold() { ... }
@Test void shouldHandleEmptyToolOutput() { ... }
@Test void shouldSnipMultipleToolResultsIndependently() { ... }
@Test void shouldPreserveToolOutputStructure() { ... }
@Test void shouldCalculateSnipTokensSaved() { ... }
@Test void shouldHandleNullToolOutput() { ... }
@Test void shouldRespectConfigurableThreshold() { ... }
```

#### TokenCounterTest（Token 计数器）

> TokenCounter（`com.aicodeassistant.engine.TokenCounter`，@Component）提供 5 个公开方法：
> - `estimateTokens(List<Message>)` — 消息列表 token 估算
> - `estimateTokens(String)` — 单文本自动检测内容类型后估算
> - `estimateTokens(String, String)` — 带内容类型提示的估算
> - `estimateImageTokens(int, int)` — 图片 token 估算（ceil(w*h/750)）
> - `detectContentType(String)` — 返回 "json"/"code"/"chinese"/"text"

```java
@Test void shouldEstimateTokensForPlainText() { ... }
@Test void shouldEstimateTokensWithContentTypeHint() { ... }
@Test void shouldEstimateImageTokensByDimensions() { ... }
@Test void shouldReturnDefaultTokensForInvalidImageSize() { ... }
@Test void shouldCountEmptyStringAsZero() { ... }
@Test void shouldCountChineseCharactersCorrectly() { ... }
@Test void shouldCountCodeBlocksAccurately() { ... }
@Test void shouldCountMixedContentAccurately() { ... }
@Test void shouldDetectContentTypeAsJson() { ... }
@Test void shouldDetectContentTypeAsCode() { ... }
@Test void shouldDetectContentTypeAsChinese() { ... }
@Test void shouldCountMessageListTokens() { ... }
```

---

## 十七、多轮对话偶发空结果分析 (P-CTX-03)

> **严重度**: P2 中优先 | **与 P-CTX-01 高度关联**

### 17.1 问题描述

- **现象描述**: 端到端测试中第 3 轮多轮对话偶发返回空结果（usage=0, result=""）
- **根因假设**: 高度可能是 P-CTX-01（REST API 消息未持久化）导致的后果

### 17.2 分析

当消息未保存到数据库时，`/api/query/conversation` 端点在第 3 轮调用 `sessionManager.loadSession()` 时：

1. 如果 session 数据仍在内存中 → 可能获取到部分消息（取决于内存状态）
2. 如果 session 数据已被 GC → 获取空消息列表
3. 空上下文发送给 LLM → LLM 可能返回空回复或拒绝回复

### 17.3 修复策略

**优先修复 P-CTX-01**，然后重新验证多轮对话稳定性。如果 P-CTX-01 修复后问题仍存在，需要进一步排查：

1. QueryEngine 中的 null 检查是否完整
2. REST API 并发调用是否存在竞态条件
3. SessionManager 的线程安全性

### 17.4 验证方案

```bash
# 修复 P-CTX-01 后执行多轮对话稳定性测试
for i in $(seq 1 10); do
  # 第 1 轮
  RESULT=$(curl -s -X POST localhost:8080/api/query \
    -d '{"prompt":"记住数字: '$i'"}' -H 'Content-Type: application/json')
  SESSION_ID=$(echo $RESULT | jq -r '.sessionId')
  
  # 第 2 轮
  RESULT2=$(curl -s -X POST localhost:8080/api/query/conversation \
    -d '{"sessionId":"'$SESSION_ID'","prompt":"我让你记住的数字是多少？"}')
  
  # 第 3 轮
  RESULT3=$(curl -s -X POST localhost:8080/api/query/conversation \
    -d '{"sessionId":"'$SESSION_ID'","prompt":"再说一次那个数字"}')
  
  echo "Round $i: R2=$(echo $RESULT2 | jq -r '.result' | head -c 50) R3=$(echo $RESULT3 | jq -r '.result' | head -c 50)"
done
# 预期: 10 轮全部成功，第 2/3 轮回复包含正确数字
```

---

## 十八、WebSocket 消息解析错误修复 (P-FE-02)

> **严重度**: P3 低优先 | **工时**: 0.5 天 | **依赖**: 无

### 18.1 问题描述

- **现象描述**: 前端 console 中偶发出现 `[WebSocket] Failed to parse message` 错误（4 条）
- **影响范围**: 不影响功能，仅影响开发者体验（console 污染）

### 18.2 根因分析

可能原因：
1. SockJS 降级时消息格式与 STOMP 原生格式不同
2. 心跳消息被错误地尝试 JSON 解析
3. 特殊字符或编码处理遗漏

### 18.3 修复方案

**文件**: `frontend/src/api/stompClient.ts` 消息接收处理区域

> **⚠️ 实施方式**: 将 `stompClient.ts` **L76-80 的 try-catch 块替换为以下增强版本**。具体做法是用下方的 `parseMessage()` 防御性解析函数替换现有的简单 `JSON.parse()` 调用，并在消息回调中改为调用 `parseMessage(raw)`：

```typescript
// 在消息解析前添加防御性检查
function parseMessage(raw: string): ServerMessage | null {
    // 跳过心跳消息
    if (raw === '\n' || raw === 'h' || raw.trim() === '') {
        return null;
    }
    
    try {
        return JSON.parse(raw);
    } catch (e) {
        // 降级: 尝试从 STOMP 帧中提取 body
        const bodyMatch = raw.match(/\n\n(.+)\u0000/);
        if (bodyMatch) {
            try {
                return JSON.parse(bodyMatch[1]);
            } catch {
                // 静默忽略非 JSON 消息（如 SockJS 协议帧）
                return null;
            }
        }
        console.debug('[WS] Non-JSON message ignored:', raw.substring(0, 50));
        return null;
    }
}
```

---

## 十九、Unused Import 清理 (P-FE-04)

> **严重度**: P3 低优先 | **工时**: 0.5 天 | **依赖**: 无

### 19.1 需要清理的文件

| 文件 | 行号 | 问题 | 修复 |
|------|------|------|------|
| `frontend/src/components/message/TerminalRenderer.tsx` | import 行 | `useMemo` 未使用 | 移除 `useMemo` 导入 |
| `frontend/src/store/__tests__/dispatch.test.ts` | import 行 | `useAppUiStore` 未使用 | 移除 `useAppUiStore` 导入 |

### 19.2 修复

```typescript
// TerminalRenderer.tsx（实际路径: frontend/src/components/message/renderers/TerminalRenderer.tsx）— 移除 useMemo
// 修改前: import { useCallback, useMemo, useRef } from 'react';
// 修改后: import { useCallback, useRef } from 'react';

// dispatch.test.ts（实际路径: frontend/src/store/__tests__/dispatch.test.ts）— 移除 useAppUiStore
// 修改前: import { useAppUiStore } from '@/store/appUiStore';
// 修改后: (删除该行)
```

---

## 二十、验证计划

### 20.1 单元测试验证矩阵

| 修复问题 | 测试类 | 关键测试方法 | 验证要点 |
|---------|--------|------------|----------|
| P-PERM-02 | `DispatchHandlerTest.ts` | `handlePermissionModeChanged` | 前端状态更新 + UI 响应 |
| P-CTX-01 | `QueryControllerTest.java` | `testQueryEndpointPersistsMessages` | 消息持久化 + 跨请求可见 |
| P-AGENT-01 | `SubAgentExecutorTest.java` | `testResolveModelWithAlias` | 模型别名四级回退 |
| P-FE-01 | `App.test.tsx` | `createSessionErrorHandling` | 异常捕获 + 降级 |
| P-TOOL-04 | `FileEditToolUnitTest.java` | 21个既有测试 | NPE 消除 |
| P-PERM-01 | `AutoModeClassifierTest.java` | `testClassifyDangerousCommand` | LLM 分类准确性 |
| P-AG-01 | `ToolResultSummarizerTest.java` | `shouldSummarizeWhenExceedsThreshold` | 摘要生成 + 降级 |
| P-CTX-02 | `CompactServiceTest.java` | `shouldNotCompactWithFewMessages` | 最低消息守卫 |
| P-BASH-01 | `BashParserGoldenTest.java` | 50个golden测试 | BashSecurityAnalyzer null 参数修复 |
| P-AGENT-02 | `SubAgentExecutorTest.java` | `workerShouldUseBubblePermission` | BUBBLE 模式 |

### 20.2 端到端回归测试清单

每次修复完成后，需执行以下 8 个端到端场景确认无回归：

| # | 测试场景 | 验证方法 | 通过标准 |
|---|---------|---------|----------|
| E2E-1 | REST 单轮对话 | `POST /api/query` 发送简单问题 | 200 + 响应包含合理回答 |
| E2E-2 | REST 多轮对话 | 连续 3 次 `POST /api/conversation` 使用同一 sessionId | 第 3 轮回答引用第 1 轮内容 |
| E2E-3 | WebSocket 流式 | STOMP 连接 → 发送 query → 接收 SSE 流 | 收到 `content_block_delta` + `message_stop` |
| E2E-4 | 工具调用完整链路 | 请求触发 `ReadFile` 工具 | 工具执行成功 + 结果正确 |
| E2E-5 | 子代理调用 | 请求触发 `AgentTool` → 子代理执行 | 子代理选择正确模型 + 返回结果 |
| E2E-6 | 权限模式切换 | 前端切换 NORMAL → FULL_AUTO | 前端状态同步 + 后续工具不弹权限 |
| E2E-7 | 自动压缩触发 | 发送 20+ 轮长对话 | 自动触发压缩 + 压缩后上下文完整 |
| E2E-8 | BashTool 安全 | 尝试执行 `rm -rf /` | 被安全层拦截 + 返回 DENY |

### 20.3 回归测试执行命令

```bash
# 后端单元测试（全量）
cd backend && mvn test

# 后端特定模块测试
mvn test -Dtest="QueryControllerTest,SubAgentExecutorTest,CompactServiceTest,BashParserGoldenTest"

# 前端测试
cd frontend && npm test

# 前端 TypeScript 类型检查
npx tsc --noEmit
```

### 20.4 评分预期总表

| 评估维度 | 当前 | Phase 1 后 | Phase 2 后 | Phase 3 后 | 最终目标 |
|---------|------|-----------|-----------|-----------|----------|
| Agent Loop | 80 | 82 | 85 | 88 | 88 |
| 工具系统 | 85 | 87 | 89 | 90 | 90 |
| 权限治理 | 85 | 88 | 92 | 92 | 92 |
| BashTool 安全 | 87 | 88 | 89 | 90 | 90 |
| System Prompt | 72 | 72 | 78 | 85 | 85 |
| 多 Agent 协作 | 72 | 76 | 80 | 82 | 82 |
| 上下文管理 | 70 | 78 | 82 | 85 | 85 |
| 前端 UI + WebSocket | 87 | 89 | 90 | 90 | 90 |
| **综合加权评分** | **80.6** | **82.9** | **87.1** | **88.8** | **88+** |

---

## 附录 A: 修改文件完整清单

| # | 文件路径 | 修改类型 | 关联问题 | Phase |
|---|---------|---------|---------|-------|
| 1 | `frontend/src/api/dispatch.ts` | 修改 L151-154 | P-PERM-02 | 1 |
| 2 | `frontend/src/App.tsx` | 修改 L35-41 | P-FE-01 | 1 |
| 3 | `frontend/src/types/index.ts` | 可能修改 | P-PERM-02 | 1 |
| 4 | `frontend/src/components/message/renderers/TerminalRenderer.tsx` | 修改(清理import) | P-FE-04 | 4 |
| 5 | `frontend/src/store/__tests__/dispatch.test.ts` | 修改(清理import) | P-FE-04 | 4 |
| 6 | `frontend/src/api/stompClient.ts` | 修改(消息解析) | P-FE-02 | 4 |
| 7 | `backend/.../controller/QueryController.java` | 修改 L101,L174,L249（已核实） | P-CTX-01 | 1 |

> **⚠️ QueryController 行号验证说明**: 上述行号已经根据实际源码核实（query() L101, streamQuery() L174, conversationQuery() L249）。三个端点分别为：
> - `query()`（同步单次查询，L101）
> - `streamQuery()`（SSE 流式查询，L174）
> - `conversationQuery()`（多轮会话查询，L249）
> 三个端点均需在返回前循环调用 `sessionManager.addMessage(sessionId, role, content, stopReason, inputTokens, outputTokens)` 持久化消息（详见第3章 §3.4 修复方案）。
| 8 | `backend/.../llm/LlmProviderRegistry.java` | **需新增方法** | P-AGENT-01, P-PERM-01 | 1 |
| 9 | `backend/.../tool/agent/SubAgentExecutor.java` | **需修改构造函数**(10→ 12参数: 新增 `LlmProviderRegistry providerRegistry` + `PermissionModeManager permissionModeManager`) + 修改 L266-270, L818-836 | P-AGENT-01, P-AGENT-02 | 1 |
| 10 | `backend/.../tool/agent/AgentTool.java` | **需修改构造函数**(1→ 2参数) + 修改 L131-134 | P-AGENT-01, P-AGENT-03 | 1 |
| 11 | `backend/.../tool/config/ConfigTool.java` | 修改 L43 | P-AGENT-03 | 3 |
| 12 | `backend/.../permission/AutoModeClassifier.java` | 确认/修改 L341-378 | P-PERM-01 | 2 |
| 13 | `backend/.../engine/CompactService.java` | 修改 L174-179 | P-CTX-02 | 2 |
| 14 | `backend/.../engine/ToolResultSummarizer.java` | **重构现有**（非新建） | P-AG-01 | 2 |
| 15 | `backend/.../engine/QueryEngine.java` | 修改(工具后处理) | P-AG-01 | 2 |
| 16 | `backend/.../tool/ToolExecutionPipeline.java` | **需新增方法** L89-90 后 | P-TOOL-01 | 2 |
| 17 | `backend/.../tool/ToolInputValidationException.java` | **已存在，无需新建** | P-TOOL-01 | 2 |
| 17b | `backend/.../tool/Tool.java` | **需新增方法** `default getSchema()` | P-TOOL-01 | 2 |
| 18 | `backend/pom.xml` | **需新增依赖** | P-TOOL-01 | 2 |
| 19 | `backend/src/test/.../tool/bash/BashParserGoldenTest.java` | 修改(@BeforeEach: null→AppStateStore) | P-BASH-01 | 3 |
| 20 | `backend/src/test/.../tool/impl/FileEditToolUnitTest.java` | 修改(添加mock) | P-TOOL-04 | 1 |
| 21 | `backend/src/test/.../tool/impl/FileReadToolUnitTest.java` | 修改(添加mock, 2参数) | P-TOOL-04 | 1 |
| 22 | `backend/src/main/resources/application.yml` | 新增配置 | P-AGENT-01 | 1 |
| 23 | `backend/src/main/resources/prompts/*.txt` | **需新建**(5个文件+目录) | P-SP-01 | 3 |
| 24 | `backend/src/test/.../engine/SnipServiceTest.java` | **新建** | P-CTX-04 | 3 |
| 25 | `backend/src/test/.../engine/MicroCompactTest.java` | **新建** | P-CTX-04 | 3 |
| 26 | `backend/src/test/.../engine/ContextCollapseServiceTest.java` | **新建** | P-CTX-04 | 3 |
| 27 | `backend/src/test/.../engine/TokenCounterTest.java` | **新建** | P-CTX-04 | 3 |

---

## 附录 B: Claude Code 源码参考路径映射

| ZhikuCode 模块 | Claude Code 参考路径 | 参考要点 |
|----------------|---------------------|---------|
| QueryController (REST API) | `/claudecode/src/query.ts` | 消息管理和持久化流程 |
| SubAgentExecutor | `/claudecode/src/tools/AgentTool/` | 子代理模型解析和配置 |
| AutoModeClassifier | `/claudecode/src/` (yoloClassifier) | 两阶段分类器实现 |
| CompactService | `/claudecode/src/services/compact/autoCompact.ts` | 自动压缩阈值计算 |
| ToolResultSummarizer | `/claudecode/src/services/toolUseSummary/toolUseSummaryGenerator.ts` | Haiku 摘要生成 |
| dispatch.ts | `/claudecode/src/hooks/` | 权限状态传播 |
| PermissionPipeline | `/claudecode/src/` (权限相关) | 权限冒泡机制 |
| BashParser | `/claudecode/src/tools/BashTool/` | Bash 解析和安全检查 |

---

## 附录 C: 问题-修复交叉引用表

| 问题 ID | 严重度 | 本文档章节 | 修复状态 | 预估工时 |
|---------|--------|-----------|---------|----------|
| P-PERM-02 | P0 | 第二章 | 待修复 | 0.5 天 |
| P-CTX-01 | P1 | 第三章 | 待修复 | 2-3 天 |
| P-AGENT-01 | P1 | 第四章 | 待修复 | 1-2 天 |
| P-FE-01 | P1 | 第五章 | 待修复 | 0.5 天 |
| P-TOOL-04 | P1 | 第六章 | 待修复 | 1 天 |
| P-PERM-01 | P1 | 第七章 | 待修复 | 2-3 天 |
| P-AG-01 | P1 | 第八章 | 待修复 | 3-5 天 |
| P-CTX-02 | P2 | 第九章 | 待修复 | 1 天 |
| P-BASH-01 | P1 | 第十章 | 待修复 | 1 天 |
| P-AGENT-02 | P1 | 第十一章 | 待修复 | 3-5 天 |
| P-TOOL-01 | P1 | 第十四章 | 待修复 | 3-5 天 |
| P-SP-01 | P2 | 第十五章 | 待修复 | 1-2 周 |
| P-CTX-04 | P2 | 第十六章 | 待修复 | 3-5 天 |
| P-CTX-03 | P2 | 第十七章 | 待验证 | — |
| P-FE-02 | P3 | 第十八章 | 低优先 | 0.5 天 |
| P-FE-04 | P3 | 第十九章 | 低优先 | 0.5 天 |
| P-TOOL-03 | P1 | 第十二章(已修复) | ✅ 已修复 | — |
| P-SP-02 | P1 | 第十二章(已修复) | ✅ 已修复 | — |
| P-AG-02 | P2 | Phase 4 | 低优先 | 2-3 天 |
| P-AGENT-03 | P2 | Phase 3 | 待修复 | 1 天 |

---

> **文档结束**。本修复实施方案基于 2026-04-12 对 ZhikuCode 全部核心模块的端到端测试结果，结合 Claude Code 原版源码参考，提供了每个问题的精确修复方案（含文件路径、行号、代码示例）。所有代码示例使用 Java 21 特性和 Spring Boot 3.3 API，前端示例使用 React 18 hooks + Zustand。
