# Phase 1 五大任务快速参考指南

## 文档入口

### 📋 完整规格文档
**文件**：`Phase1_精确代码规格.md`（2362行）
- Task 1：流式工具启动 + contextModifier 传播（3-4人天）
- Task 2：Context Collapse渐进增强 + 关键文件重注入（2.5-3人天）
- Task 3：MCP主动健康检查增强（2-3人天）
- Task 4：Token预算续写WebSocket推送（3-3.5人天）
- Task 5：PolicySettingsSource TOCTOU竞态修复（0.5人天）

### 📊 研究报告
**文件**：`Phase1_研究报告.md`（645行）
- 研究方法和成果总结
- 关键发现和缺陷分析
- 实现约束和测试建议
- 执行优先级建议

---

## 快速导航

### Task 1: 流式工具启动 + contextModifier 传播 (3-4人天)

**修改文件**：2个
- `StreamingToolExecutor.java` (196行)
- `QueryEngine.java` (962行)

**关键改造**：
1. ExecutionSession 新增有参构造器（接收 ToolUseContext）
2. 新增 AtomicReference<ToolUseContext> currentContext 字段
3. 新增 applyContextModifier() CAS 循环方法
4. 修改 addTool() 使用 currentContext.get()
5. QueryEngine 补充 session.getCurrentContext() 调用

**代码规格位置**：Phase1_精确代码规格.md - Task 1 - 精确改造规格

**核心约束**：
- CAS 最多重试 100 次
- 并发安全工具返回 modifier 时 WARN 日志但不应用
- newSession(context) 必须在 Step 3 调用前执行

---

### Task 2: Context Collapse 渐进增强 + 关键文件重注入 (2.5-3人天)

**新建文件**：2个
- `CollapseLevel.java` - sealed interface（三级折叠策略）
- `KeyFileTracker.java` - Caffeine缓存+计数统计

**修改文件**：6个
- `ContextCollapseService.java` - 修复substring边界+新增progressiveCollapse()
- `ContextCascade.java` - 互斥协调逻辑（L236-254）
- `CompactService.java` - PathSecurityService检查+rebuildAfterCompact()
- `FileReadTool.java`、`FileEditTool.java`、`GrepTool.java` - KeyFileTracker埋点
- `SessionManager.java` - clearSession()调用

**代码规格位置**：Phase1_精确代码规格.md - Task 2 - 精确改造规格

**核心约束**：
- Math.min 保护 substring 边界（L141）
- progressiveCollapse 中 UserMessage 永不折叠
- PathSecurityService 检查必须在文件读取前
- rebuildAfterCompact 优先使用 KeyFileTracker，降级正则提取

---

### Task 3: MCP 主动健康检查增强 (2-3人天)

**修改文件**：3个
- `SseHealthChecker.java` - 新增 consecutiveFailures/lastSuccessfulPing 指标
- `McpClientManager.java` - 幂等重连保护+自定义线程池+WebSocket推送
- `WebSocketSessionManager.java` - 新增 getActiveSessionIds()

**代码规格位置**：Phase1_精确代码规格.md - Task 3 - 精确改造规格

**核心约束**：
- reconnectingServers 标记必须在 finally 中清除
- consecutiveFailures ≥ 2 次触发 DEGRADED
- RECONNECT_POOL 固定 2 个线程
- WebSocket 推送必须在 attemptReconnect() 完成后

---

### Task 4: Token 预算续写 WebSocket 推送 (3-3.5人天)

**修改文件**（后端）：4个
- `ServerMessage.java` - 新增 #37 TokenBudgetNudge record
- `QueryMessageHandler.java` - 新增 onTokenBudgetNudge() 方法
- `WebSocketController.java` - WsMessageHandler 实现推送
- `QueryEngine.java` (L462-472) - 补充 handler 调用

**修改文件**（前端）：2个 + 新建 1个
- `messageStore.ts` - 新增 tokenBudgetState
- `useWebSocket.ts` - 新增 token_budget_nudge case
- `TokenBudgetIndicator.tsx` - **新建**（进度条UI）

**代码规格位置**：Phase1_精确代码规格.md - Task 4 - 精确改造规格

**核心约束**：
- nudge 推送必须在 state.addMessage() 之后立即执行
- 颜色阈值：<50% 绿，50-75% 黄，>75% 红
- 会话结束时必须调用 clearTokenBudgetState()

---

### Task 5: PolicySettingsSource TOCTOU 竞态修复 (0.5人天)

**修改文件**：1个
- `PolicySettingsSource.java` (145行) - 4处改用 AtomicLong

**改造点**：
- L52: volatile long lastModified → final AtomicLong lastModified
- L72: lastModified → lastModified.get()
- L90: lastModified = → lastModified.set()
- L121: lastModified = 0 → lastModified.set(0)

**代码规格位置**：Phase1_精确代码规格.md - Task 5 - 精确改造规格

**核心约束**：
- 必须是 final AtomicLong（非 volatile）
- 所有读写改用 .get()/.set()（无例外）

---

## 执行顺序建议

```
Week 1-2 (P0 优先级)
├─ Task 5 (0.5人天) - TOCTOU 竞态修复 【最简单，无依赖】
├─ Task 1 (3-4人天) - 流式启动 + contextModifier 传播
└─ Task 3 (2-3人天) - MCP 健康检查增强

Week 2-3 (P1 优先级)
├─ Task 2 (2.5-3人天) - Context Collapse + KeyFileTracker【最复杂，新建2个文件】
└─ Task 4 (3-3.5人天) - Token 预算 WebSocket 推送【前端组件新建】
```

**总耗时**：11.5-14 人天（含测试）

---

## 文件修改清单

### 新建文件（2个）
```
backend/src/main/java/com/aicodeassistant/engine/CollapseLevel.java
backend/src/main/java/com/aicodeassistant/engine/KeyFileTracker.java
frontend/src/components/TokenBudgetIndicator.tsx
```

### 后端修改（15个文件）
```
backend/src/main/java/com/aicodeassistant/
├─ engine/
│  ├─ QueryEngine.java (962行)
│  ├─ ContextCollapseService.java (170行)
│  ├─ ContextCascade.java (300+行)
│  ├─ QueryMessageHandler.java (69行)
│  └─ TokenBudgetTracker.java (92行) ✅ 无需修改，已完整
├─ tool/
│  ├─ StreamingToolExecutor.java (196行)
│  ├─ impl/FileReadTool.java
│  ├─ impl/FileEditTool.java
│  └─ impl/GrepTool.java
├─ mcp/
│  ├─ SseHealthChecker.java (50行)
│  └─ McpClientManager.java (450+行)
├─ websocket/
│  ├─ ServerMessage.java (168行)
│  └─ WebSocketController.java
├─ permission/
│  └─ PolicySettingsSource.java (145行)
└─ manager/
   └─ SessionManager.java
```

### 前端修改（3个文件）
```
frontend/src/
├─ store/messageStore.ts
├─ hooks/useWebSocket.ts
└─ components/TokenBudgetIndicator.tsx (新建)
```

---

## 安全性缺陷修复清单

| 缺陷 | 位置 | 修复方案 |
|------|------|---------|
| TOCTOU竞态 | PolicySettingsSource L52 | volatile → final AtomicLong |
| 文件重注入安全漏洞 | CompactService L837 | 添加 PathSecurityService.checkReadPermission() |
| substring边界 | ContextCollapseService L141 | Math.min(keep, text.length()) |
| 幂等重连死锁 | McpClientManager.scheduleReconnect | finally中清除标记 |

---

## 关键API变更

### Task 1
```java
// 新API
ExecutionSession newSession(ToolUseContext initialContext)
ToolUseContext session.getCurrentContext()
void ExecutionSession.applyContextModifier(ToolUseContext newContext)
```

### Task 2
```java
// 新API
public sealed interface CollapseLevel { ... }
CollapseResult progressiveCollapse(List<Message>, List<CollapseLevel>)
void KeyFileTracker.trackFileReference(String, String, String)
List<String> KeyFileTracker.getKeyFiles(String, int)
```

### Task 3
```java
// 新API
Set<String> WebSocketSessionManager.getActiveSessionIds()
void McpClientManager.broadcastHealthStatus(String, McpConnectionStatus)
```

### Task 4
```java
// 新API record
record ServerMessage.TokenBudgetNudge(int pct, int currentTokens, int budgetTokens)
void QueryMessageHandler.onTokenBudgetNudge(int, int, int)
```

### Task 5
```java
// API不变，仅实现方式变更
// 改用 AtomicLong.get()/set() 替代直接访问
```

---

## 测试检查清单

- [ ] Task 1: 单工具 modifier 应用验证
- [ ] Task 1: 多工具顺序 modifier 验证
- [ ] Task 1: 并发安全工具忽略 modifier 验证
- [ ] Task 2: progressiveCollapse 三级折叠正确性
- [ ] Task 2: KeyFileTracker 去重逻辑
- [ ] Task 2: 文件重注入安全拦截
- [ ] Task 2: substring 边界保护
- [ ] Task 3: MCP ping 失败触发 DEGRADED
- [ ] Task 3: 幂等标记清除（无死锁）
- [ ] Task 3: WebSocket 广播所有会话
- [ ] Task 4: Token 预算 nudge 推送
- [ ] Task 4: 前端进度条颜色变化
- [ ] Task 4: WebSocket 消息顺序
- [ ] Task 5: 并发 loadRules() 无重复加载

---

## 常见问题

### Q1: Task 2 中 progressiveCollapse 何时调用？
**A**: 在 ContextCascade.executePreApiCascade() 的 Level 1.5 阶段调用，作为 collapseMessages() 的增强替代（不是并存）。

### Q2: KeyFileTracker 的去重机制如何工作？
**A**: 使用 (sessionId, turnId) 作为去重 key，turnDedup 缓存记录本轮已计数的文件，防止同一轮对话中同一文件多次计数。

### Q3: Task 3 中 RECONNECT_POOL 为何使用 2 个线程？
**A**: 考虑到单机场景通常有 3-5 个 MCP 连接，2 个线程足以并行重连，避免资源浪费。

### Q4: Task 4 的 nudge 推送为什么必须在 state.addMessage() 之后？
**A**: 保证前端收到 nudge 时，对应的对话历史消息已经同步到状态，UI 可正确渲染完整上下文。

### Q5: Task 5 为何不能用 volatile long？
**A**: volatile 仅保证可见性，不保证原子性。多线程竞争时，read-check-write 之间仍存在 TOCTOU 窗口。AtomicLong.compareAndSet() 才能保证原子操作。

---

## 性能指标

| Task | 指标 | 预期结果 |
|------|------|---------|
| Task 1 | 首工具延迟 | <50ms（从收到即启动） |
| Task 2 | progressiveCollapse | <100ms（1000消息） |
| Task 2 | KeyFileTracker查询 | <10ms（Caffeine O(1)） |
| Task 3 | MCP ping延迟 | <5s（定期30s） |
| Task 4 | nudge推送延迟 | <100ms |
| Task 5 | 缓存命中 | >99%（文件不频繁修改） |

---

## 生成日期
2026-04-18

## 文档版本
v1.0 - Phase 1 精确规格首版发布
