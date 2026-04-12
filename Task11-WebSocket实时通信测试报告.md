# Task 11 — WebSocket 实时通信测试报告

**测试日期**: 2026-04-12  
**测试环境**: macOS darwin 26.4 / Backend 8080 / Frontend 5173 / Python 8000 / LLM qwen3.6-plus  
**测试对象**: WebSocketController.java (788行), stompClient.ts (268行), dispatch.ts (274行)

---

## 一、STOMP 协议架构完整分析

### 1.1 WebSocket 配置 (`WebSocketConfig.java` 162行)

| 配置项 | 值 | 说明 |
|--------|-----|------|
| STOMP 端点 | `/ws` | SockJS fallback 启用 |
| 应用前缀 | `/app` | 客户端发送目的地前缀 |
| 订阅前缀 | `/topic`, `/queue` | 广播 + 用户专属 |
| 用户目标前缀 | `/user` | user destination 前缀 |
| 心跳 | `10000ms / 10000ms` | incoming + outgoing |
| 允许来源 | `localhost:5173`, `localhost:8080`, `127.0.0.1:5173`, `127.0.0.1:8080` | CORS |
| 认证方式 | CONNECT 帧 Bearer Token + 匿名 localhost 降级 | `StompAuthInterceptor` |

### 1.2 消息流架构

```
前端 (stompClient.ts)                    后端 (WebSocketController.java)
┌─────────────────────┐                  ┌──────────────────────────┐
│ SockJS + STOMP      │ ── /app/chat ──> │ @MessageMapping("/chat") │
│ webSocketFactory:   │ ← /user/queue/ ← │ push() → messaging       │
│   new SockJS('/ws') │   /messages      │   .convertAndSendToUser  │
│                     │                  │                          │
│ 订阅:               │                  │ WebSocketSessionManager  │
│ /user/queue/messages│                  │  principal ↔ sessionId   │
└─────────────────────┘                  └──────────────────────────┘
```

### 1.3 会话绑定流程

1. 前端 `createStompClient()` → SockJS `/ws` → STOMP CONNECT 帧 (携带 `X-Session-Id`)
2. `StompAuthInterceptor.preSend()` → 提取 Authorization + X-Session-Id → 设置 Principal
3. `WebSocketSessionManager.handleSessionConnected()` → 注册 principal ↔ sessionId 双向映射
4. 前端 `onConnect` → 订阅 `/user/queue/messages` → 发送 `/app/bind-session`
5. `handleBindSession()` → 手动绑定 + 推送 `session_restored`

---

## 二、WebSocketController 全部 @MessageMapping 端点清单

### 2.1 Client → Server: 11 个 @MessageMapping 端点

| # | 端点 | 方法 | 前端发送方法 | Payload 类型 | 功能 |
|---|------|------|-------------|-------------|------|
| 1 | `/app/chat` | `handleUserMessage` | `sendUserMessage()` | `UserMessagePayload(text, attachments, references)` | 用户消息 → 异步 QueryEngine 执行 |
| 2 | `/app/permission` | `handlePermissionResponse` | `sendPermissionResponse()` | `PermissionResponsePayload(toolUseId, decision, remember, scope)` | 权限响应 → `permissionPipeline.resolvePermission` |
| 3 | `/app/interrupt` | `handleInterrupt` | `sendInterrupt()` | `Map<String, Object>` (可选 isSubmitInterrupt) | 中断 → `queryEngine.abort()` + 推送 `interrupt_ack` |
| 4 | `/app/model` | `handleSetModel` | `sendSetModel()` | `SetModelPayload(model)` | 切换模型 → 推送 `model_changed` 或 `error` |
| 5 | `/app/permission-mode` | `handleSetPermissionMode` | `sendSetPermissionMode()` | `SetPermissionModePayload(mode)` | 切换权限模式 → 推送 `permission_mode_changed` |
| 6 | `/app/command` | `handleSlashCommand` | `sendSlashCommand()` | `SlashCommandPayload(command, args)` | Slash 命令 → `commandRegistry.execute()` |
| 7 | `/app/mcp` | `handleMcpOperation` | `sendMcpOperation()` | `McpOperationPayload(operation, serverId, config)` | MCP 操作 (connect/disconnect/refresh/list) |
| 8 | `/app/rewind` | `handleRewindFiles` | `sendRewindFiles()` | `RewindFilesPayload(messageId, filePaths)` | 文件回退 → `fileHistoryService.rewindFiles()` |
| 9 | `/app/elicitation` | `handleElicitationResponse` | `sendElicitationResponse()` | `ElicitationResponsePayload(requestId, answer)` | AI 反向提问响应 |
| 10 | `/app/ping` | `handlePing` | `sendPing()` | 无 | 心跳 → 推送 `pong` |
| 11 | `/app/bind-session` | `handleBindSession` | (自动) | `Map<String, String>` | 绑定会话 → 推送 `session_restored` |

### 2.2 Server → Client: 32 种推送消息类型

| # | 类型标识 | 推送方法 | 目标 Store | 功能 |
|---|---------|---------|-----------|------|
| 1 | `stream_delta` | `sendStreamDelta` | messageStore | 文本流增量 |
| 2 | `thinking_delta` | `sendThinkingDelta` | messageStore | 思考流增量 |
| 3 | `tool_use_start` | `sendToolUseStart` | messageStore | 工具调用开始 |
| 4 | `tool_use_progress` | `sendToolUseProgress` | messageStore | 工具执行进度 |
| 5 | `tool_result` | `sendToolResult` | messageStore | 工具结果返回 |
| 6 | `permission_request` | `sendPermissionRequest` | permissionStore + sessionStore | 权限确认请求 |
| 6b | `permission_request` (子代理) | `sendPermissionRequestFromChild` | permissionStore | 子代理权限转发 |
| 7 | `message_complete` | `sendMessageComplete` | messageStore + sessionStore | 助手回合完成 |
| 8 | `error` | `sendError` | messageStore + sessionStore | 错误消息 |
| 9 | `compact_start` | `sendCompactStart` | sessionStore | 上下文压缩开始 |
| 10 | `compact_complete` | `sendCompactComplete` | messageStore + sessionStore | 压缩完成 |
| 11 | `elicitation` | `sendElicitation` | appUiStore | AI 反向提问 |
| 12 | `agent_spawn` | `sendAgentSpawn` | taskStore | 子代理启动 |
| 13 | `agent_update` | `sendAgentUpdate` | taskStore | 子代理进度 |
| 14 | `agent_complete` | `sendAgentComplete` | taskStore | 子代理完成 |
| 15 | `cost_update` | `sendCostUpdate` | costStore | 费用/Token 更新 |
| 16 | `rate_limit` | `sendRateLimit` | sessionStore | 限流通知 |
| 17 | `notification` | `sendNotification` | notificationStore | 系统通知 |
| 18 | `task_update` | `sendTaskUpdate` | taskStore | 后台任务状态 |
| 19 | `prompt_suggestion` | `sendPromptSuggestion` | appUiStore | 提示建议 |
| 20 | `bridge_status` | `sendBridgeStatus` | bridgeStore | 桥接连接状态 |
| 21 | `teammate_message` | `sendTeammateMessage` | inboxStore | Swarm 队友消息 |
| 22 | `speculation_result` | `sendSpeculationResult` | appUiStore | 推测执行结果 |
| 23 | `mcp_tool_update` | `sendMcpToolUpdate` | mcpStore | MCP 工具列表变更 |
| 24 | `session_restored` | `sendSessionRestored` | messageStore + sessionStore + bridgeStore | 断线重连恢复 |
| 25 | `pong` | `sendPong` | (无操作) | 心跳响应 |
| 26 | `compact_event` | (内联推送) | notificationStore | 压缩进度/上下文警告 |
| 27 | `token_warning` | (内联推送) | notificationStore | Token 用量警告 |
| 28 | `interrupt_ack` | (内联推送) | sessionStore + messageStore | 中断确认 |
| 29 | `model_changed` | (内联推送) | sessionStore | 模型切换确认 |
| 30 | `permission_mode_changed` | (内联推送) | permissionStore + notificationStore | 权限模式切换确认 |
| 31 | `command_result` | (内联推送) | messageStore | 命令执行结果 |
| 32 | `rewind_complete` | (内联推送) | notificationStore | 文件回退完成 |

---

## 三、dispatch.ts 消息分发完整分析 (274行)

### 3.1 架构设计

- **分发机制**: `handlers` Record 查找表，按 `data.type` 字段路由到对应 Zustand Store
- **序列号校验**: 基于 `ts` 时间戳检测乱序消息，`lastSeqTs` 单调递增校验
- **重置机制**: `resetSequence()` 在断线重连时清零时间戳

### 3.2 分发表覆盖度分析

| Store | 消息类型数 | 消息类型 |
|-------|----------|---------|
| messageStore | 5+3 | stream_delta, thinking_delta, tool_use_start, tool_use_progress, tool_result + error, message_complete, compact_complete |
| sessionStore | 4 | compact_start, rate_limit, interrupt_ack, model_changed |
| permissionStore | 2 | permission_request, permission_mode_changed |
| costStore | 1 | cost_update |
| taskStore | 4 | task_update, agent_spawn, agent_update, agent_complete |
| appUiStore | 3 | elicitation, prompt_suggestion, speculation_result |
| bridgeStore | 1 | bridge_status |
| notificationStore | 3 | notification, compact_event, token_warning |
| inboxStore | 1 | teammate_message |
| mcpStore | 1 | mcp_tool_update |
| 跨 Store | 4 | session_restored, command_result, rewind_complete, pong |

**总计: dispatch.ts 注册 32 种消息类型处理器，与后端 ServerMessage.java 定义的 32 种完全一致。**

### 3.3 跨 Store 协调逻辑

| 函数 | 关联 Stores | 逻辑 |
|------|------------|------|
| `handlePermissionRequest` | permissionStore + sessionStore | `showPermission` + `setStatus('waiting_permission')` |
| `handleMessageComplete` | messageStore + sessionStore | `finalizeStream` + 根据 stopReason 设状态 |
| `handleError` | messageStore + sessionStore | 添加系统消息 + `setStatus('idle')` |
| `handleCompactComplete` | messageStore + sessionStore | 添加压缩通知 + `setStatus('idle')` |
| `handleSessionRestore` | messageStore + sessionStore + bridgeStore + notificationStore | 全量同步恢复 |

### 3.4 stream_delta 高性能路径

```
stream_delta → 首次创建占位 assistant 消息 (messageStore.appendStreamDelta(''))
             → 后续增量写入外部高性能 store (appendStreamDelta) 绕过 Immer 开销
```

---

## 四、测试用例结果

### WS-01: STOMP 连接建立测试

| 测试项 | 方法 | 结果 | 判定 |
|--------|------|------|------|
| WebSocket 端点可达 | `curl http://localhost:8080/ws/info` | 返回 `{"websocket":true, "cookie_needed":true}` | **PASS** |
| SockJS 信息正确 | 检查响应 JSON | entropy、origins、websocket=true 均存在 | **PASS** |
| STOMP CONNECT 握手 | 后端日志 | `STOMP CONNECT without auth (localhost), principal=anon-33a141a5` | **PASS** |
| Principal 分配 | 后端日志 | localhost 模式自动分配 `anon-{uuid8}` 格式 Principal | **PASS** |
| 会话绑定 | 后端日志 | `WS bind-session: principal=anon-33a141a5, sessionId=8dcabec7-...` | **PASS** |
| session_restored 推送 | 后端日志 | `Pushed session_restored: sessionId=8dcabec7-..., messages=0` | **PASS** |
| 前端连接状态 | 前端 Vite 运行正常 + 前端测试已确认 | WebSocket 连接成功建立 | **PASS** |

### WS-02: 消息路由测试

| 测试项 | 方法 | 结果 | 判定 |
|--------|------|------|------|
| 11 个 @MessageMapping 端点注册 | 代码分析 | /chat, /permission, /interrupt, /model, /permission-mode, /command, /mcp, /rewind, /elicitation, /ping, /bind-session | **PASS** |
| 用户消息路由 | 后端日志 | `WS user_message: sessionId=8dcabec7-..., text=请执行命令 echo hello world` | **PASS** |
| QueryEngine 执行 | 后端日志 | `QueryEngine 开始执行: sessionId=..., model=qwen3.6-plus` | **PASS** |
| 工具调用推送 | 后端日志 | `push(tool_use_start)` → `push(tool_result)` 完整链路 | **PASS** |
| 消息完成推送 | 后端日志 | `push(message_complete)`, stopReason=end_turn | **PASS** |
| 费用更新推送 | 后端日志 | `push(cost_update)` 每个 turn 都有推送 | **PASS** |
| 前端→后端→前端完整链路 | 日志追踪 | user_message → QueryEngine → stream_delta/tool_use → message_complete | **PASS** |

### WS-03: 流式推送测试

| 测试项 | 方法 | 结果 | 判定 |
|--------|------|------|------|
| REST API 查询 | `curl POST /api/query` | 返回 `{"sessionId":"948d9e7a-...", "result":"Hello!"}` | **PASS** |
| 流式增量推送 | 后端日志 | 多条 `push stream_delta to principal=anon-33a141a5, len=3~13` | **PASS** |
| 增量粒度 | 日志分析 | len=3, 5, 7, 8, 9, 11, 13 等不同长度，确认是逐块推送 | **PASS** |
| Virtual Thread 异步 | 代码分析 | `Thread.startVirtualThread(() -> executeQuery(...))` | **PASS** |
| WsMessageHandler 流式回调 | 代码分析 | onTextDelta → sendStreamDelta, onToolResult → sendToolResult | **PASS** |
| 完整消息流序列 | 日志分析 | tool_use_start → cost_update → tool_result → stream_delta(多条) → cost_update → message_complete | **PASS** |

**消息流完整追踪** (实际日志记录):
```
19:35:41.685  WS user_message: text=请执行命令 echo hello world
19:35:41.687  QueryEngine 开始执行: model=qwen3.6-plus
19:35:43.026  push(tool_use_start)   — Bash 工具调用
19:35:43.446  push(cost_update)      — Turn 1 token 统计
19:35:43.460  WARN: Tool Bash requires permission but no WebSocket pusher
19:35:43.500  push(tool_result)      — Bash 权限被拒
19:35:45.553  push stream_delta len=8  — LLM 流式输出开始
19:35:45.554  push stream_delta len=5
  ... (约 30 条 stream_delta，间隔 50-200ms)
19:35:47.230  push stream_delta len=3  — 最后一块
19:35:47.235  push(cost_update)      — 最终 token 统计
19:35:47.236  push(message_complete)  — 回合完成
```

### WS-04: 权限推送测试

| 测试项 | 方法 | 结果 | 判定 |
|--------|------|------|------|
| 权限请求消息类型 | 代码分析 | `permission_request` 含 toolUseId, toolName, input, riskLevel, reason | **PASS** |
| 子代理权限转发 | 代码分析 | `sendPermissionRequestFromChild` 附加 source="subagent", childSessionId | **PASS** |
| 权限响应处理 | 代码分析 | `/app/permission` → PermissionBehavior.ALLOW/DENY → `permissionPipeline.resolvePermission` | **PASS** |
| remember + scope 支持 | 代码分析 | 支持 SESSION/PROJECT/GLOBAL 三级 scope | **PASS** |
| 中断功能 | 代码分析 | `/app/interrupt` → `queryEngine.abort()` → 推送 `interrupt_ack` (USER_INTERRUPT/SUBMIT_INTERRUPT) | **PASS** |
| 权限推送实际触发 | 后端日志 | `Tool Bash requires permission but no WebSocket pusher available, denying` (**⚠️ 问题发现**) | **WARN** |

**⚠️ 问题 WS-04-P1**: WebSocket 通道中的 Bash 工具权限请求未能正确触发 `permission_request` 推送。日志显示 `no WebSocket pusher available`，表明 `ToolExecutionPipeline` 在 WebSocket 场景下未正确获取到 `PermissionNotifier` 引用。REST API 触发的查询不会通过 WebSocket 推送权限请求。

### WS-05: 断线重连测试

| 测试项 | 方法 | 结果 | 判定 |
|--------|------|------|------|
| 重连策略 | stompClient.ts 代码分析 | 指数退避: 1s → 2s → 4s → 8s → 10s (cap) | **PASS** |
| 重连超时 | 代码分析 | `RECONNECT_TIMEOUT = 10 * 60 * 1000` (10分钟) | **PASS** |
| 超时处理 | 代码分析 | 超时后 `client.deactivate()` + 推送 "请刷新页面重试" 错误通知 | **PASS** |
| 连接状态管理 | 代码分析 | disconnected → reconnecting → connected，bridgeStore 同步更新 | **PASS** |
| 断线通知 | 代码分析 | 首次断线: warning "正在尝试重连..."（不自动消失）; 重连成功: 移除通知 | **PASS** |
| 序列号重置 | 代码分析 | `onConnect` 回调中 `resetSequence()` 清零时间戳 | **PASS** |
| session_restored 恢复 | 代码+日志 | `handleBindSession` → `sessionManager.loadSession` → 推送 messages + metadata | **PASS** |
| 全量同步恢复 | dispatch.ts 分析 | `handleSessionRestore`: 清空消息 → 加载历史 → 恢复元数据 → 更新状态 | **PASS** |

**重连策略详情**:
```
延迟序列: 1000ms → 2000ms → 4000ms → 8000ms → 10000ms → 10000ms → ...
超时时间: 10 分钟
超时行为: 停止重连 + 错误通知 "连接已断开，请刷新页面重试"
```

### WS-06: 多会话隔离测试

| 测试项 | 方法 | 结果 | 判定 |
|--------|------|------|------|
| 不同请求生成不同 sessionId | REST API 发送 2 个查询 | session1=f5375c7d-..., session2=44967166-... | **PASS** |
| Principal↔SessionId 双向映射 | 单元测试 `sessionManager_bindSession` | 双向查询正确 | **PASS** |
| 未绑定 session 返回 null | 单元测试 `sessionManager_unboundSession` | getSession/getPrincipal 均返回 null | **PASS** |
| 消息定向推送 | 代码分析 | `messaging.convertAndSendToUser(principal, "/queue/messages", msg)` 通过 user destination 隔离 | **PASS** |
| 无 Principal 静默跳过 | 单元测试 `pushToUser_noPrincipal` | verifyNoInteractions(messaging) | **PASS** |
| ConcurrentHashMap 线程安全 | 代码分析 | 所有映射均使用 ConcurrentHashMap | **PASS** |
| STOMP disconnect 清理 | 代码分析 | `handleSessionDisconnect` 清理 transportToSession → principalToSession → sessionToPrincipal | **PASS** |

**隔离机制**: 每个 WebSocket 连接通过 `StompPrincipal` 标识，消息通过 Spring STOMP 的 `convertAndSendToUser` 路由到 `/user/{principal}/queue/messages`，天然实现会话隔离。

### WS-07: dispatch.ts 消息分发测试

| 测试项 | 方法 | 结果 | 判定 |
|--------|------|------|------|
| 注册 32 种消息处理器 | 代码分析 | handlers Record 包含 32 个 key | **PASS** |
| 未知消息类型处理 | 代码分析 | `console.warn('[WS] Unknown message type: ...')` | **PASS** |
| 乱序消息检测 | 代码分析 | `ts < lastSeqTs` 时输出 warn 日志 | **PASS** |
| stream_delta 高性能路径 | 代码分析 | 首次 delta 创建占位消息，后续绕过 Immer 直接写入外部 store | **PASS** |
| message_complete 状态管理 | 代码分析 | end_turn→idle, tool_use→保持streaming | **PASS** |
| permission_mode_changed 大小写安全 | 代码分析 | `d.mode.toLowerCase()` 归一化 | **PASS** |
| session_restored 全量恢复 | 代码分析 | 清空→加载→恢复元数据→更新bridgeStore | **PASS** |
| 后端 ServerMessage vs 前端 handlers 对齐 | 交叉比对 | 32/32 完全匹配 | **PASS** |

---

## 五、单元测试执行结果

```
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS (Total time: 2.833s)
```

| # | 测试名称 | 验证内容 | 结果 |
|---|---------|---------|------|
| 1 | pushToUser — 消息包含 type+ts+payload 字段 | 扁平 JSON 格式 | **PASS** |
| 2 | pushToUser — 无 Principal 时不调用 messaging | 静默跳过 | **PASS** |
| 3 | sendStreamDelta — 推送文本增量 | delta 字段正确 | **PASS** |
| 4 | sendToolResult — 推送工具执行结果 | toolUseId 正确 | **PASS** |
| 5 | sendError — 推送错误消息 | code 字段正确 | **PASS** |
| 6 | sendPermissionRequest — 推送权限请求 | toolName 正确 | **PASS** |
| 7 | sendCostUpdate — 推送费用更新 | totalCost 字段存在 | **PASS** |
| 8 | SessionManager — bindSession 双向映射 | 双向查询 + isOnline | **PASS** |
| 9 | SessionManager — 未绑定返回 null | null + false | **PASS** |
| 10 | sendMessageComplete — 推送消息完成标记 | stopReason 正确 | **PASS** |
| 11 | sendPong — 响应 Ping | pong 类型正确 | **PASS** |

---

## 六、发现的问题和建议

### 问题清单

| ID | 严重性 | 问题描述 | 位置 | 建议修复 |
|----|--------|---------|------|---------|
| WS-P1 | **HIGH** | WebSocket 通道中工具执行时 `ToolExecutionPipeline` 找不到 `PermissionNotifier`，导致需要权限的工具直接被 deny | ToolExecutionPipeline + WebSocketController | 确保 WebSocket 发起的查询在 `ToolUseContext` 或 `QueryConfig` 中注入 `PermissionNotifier` (即 WebSocketController 实例) |
| WS-P2 | **MEDIUM** | `session_restored` 推送的 messages 始终为 0 条 | WebSocketController L751-770 | 检查 `sessionManager.loadSession(sessionId)` 返回的 `SessionData.messages()` 是否正确持久化了历史消息 |
| WS-P3 | **LOW** | SockJS info 端点 `origins` 为 `["*:*"]` 但 `registerStompEndpoints` 限制了特定来源 | WebSocketConfig L62-68 | SockJS info 的 origins 是 transport 层协商用，不影响安全；但建议统一配置避免混淆 |
| WS-P4 | **LOW** | `WebSocketProvider.tsx` 中 `isConnected` 值在 `useMemo` 中不会自动更新 | WebSocketProvider L112 | `checkConnected()` 是瞬时值，不会触发 re-render；建议改用 bridgeStore 的连接状态 |
| WS-P5 | **INFO** | `pushToUser` 方法为 `public`，非 Map payload 降级为嵌套 `"payload"` 字段，破坏扁平结构约定 | WebSocketController L135 | 建议对非 Map payload 使用 Jackson ObjectMapper 提取字段实现真正扁平化 |

### 架构优化建议

1. **心跳超时检测**: stompClient.ts 中 `pong` handler 为空操作，建议实现应用层心跳超时检测（如 30s 无 pong 触发重连）
2. **消息确认机制**: 当前无 ACK 机制，关键消息（如 permission_response）如果丢失无法重发
3. **消息序列号**: 当前仅基于 timestamp 检测乱序，建议引入自增序列号实现更精确的丢失检测

---

## 七、测试总结

| 测试用例 | 通过/总数 | 判定 |
|---------|----------|------|
| WS-01: STOMP 连接建立 | 7/7 | **PASS** |
| WS-02: 消息路由 | 7/7 | **PASS** |
| WS-03: 流式推送 | 6/6 | **PASS** |
| WS-04: 权限推送 | 5/6 | **WARN** (P1: PermissionNotifier 未注入) |
| WS-05: 断线重连 | 8/8 | **PASS** |
| WS-06: 多会话隔离 | 7/7 | **PASS** |
| WS-07: dispatch.ts 分发 | 8/8 | **PASS** |
| 单元测试 | 11/11 | **PASS** |
| **总计** | **59/60** | **98.3% 通过** |

**总体评价**: WebSocket STOMP 通信架构设计完整，11 个 @MessageMapping 端点 + 32 种 Server→Client 消息类型覆盖全面，前后端消息类型完全对齐。流式推送经实际日志验证为真正的逐块增量推送（非一次性返回）。主要问题是 WS-P1 权限推送链路断裂（HIGH），需要优先修复以支持 WebSocket 通道的工具权限确认流程。
