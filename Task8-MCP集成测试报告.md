# Task 8 — MCP 集成测试报告

**测试时间**: 2026-04-12  
**测试环境**: macOS Darwin 26.4, Backend http://localhost:8080, LLM qwen3.6-plus  
**被测版本**: McpClientManager.java (475行) + MCP 模块 28 个文件

---

## 一、MCP 模块完整架构分析（28 个文件职责划分）

### 1.1 核心管理层（3 个文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `McpClientManager.java` | 475 | **中枢管理器** — SmartLifecycle 实现，管理所有 MCP 连接的生命周期、工具注册、健康检查、指数退避重连 |
| `McpConfiguration.java` | 146 | **Spring 配置绑定** — 从 `application.yml` 的 `mcp` 前缀加载服务器配置，转换为 `McpServerConfig` |
| `McpConfigurationResolver.java` | 248 | **4 层配置解析器** — ENV → ENTERPRISE → USER → LOCAL 多来源配置合并 |

### 1.2 传输层（5 个文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `McpTransport.java` | 51 | **传输接口** — 统一抽象 `connect()`、`sendRequest()`、`sendNotification()`、`isConnected()`、`close()` |
| `McpSseTransport.java` | 264 | **SSE 传输** — 基于 OkHttp + EventSource，GET 接收 SSE 流 + POST 发送请求 |
| `McpStdioTransport.java` | 158 | **STDIO 传输** — 子进程 stdin/stdout JSON-RPC 行协议通信 |
| `McpWebSocketTransport.java` | 244 | **WebSocket 传输** — 基于 Java-WebSocket 的全双工 JSON-RPC |
| `McpStreamableHttpTransport.java` | 309 | **Streamable HTTP 传输** — MCP 2025-03-26 规范，支持 JSON/SSE 双模式响应 + Mcp-Session-Id 管理 |

### 1.3 连接与协议层（5 个文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `McpServerConnection.java` | 307 | **连接封装** — 持有 `McpTransport` 实例，传输工厂方法，`callTool()` 传输无关调用 |
| `McpServerConfig.java` | 47 | **配置 record** — `name/type/command/args/env/url/headers/scope` |
| `McpTransportType.java` | 26 | **传输类型枚举** — 8 种：STDIO/SSE/SSE_IDE/HTTP/WS/WS_IDE/SDK/CLAUDEAI_PROXY |
| `McpConnectionStatus.java` | 20 | **连接状态枚举** — CONNECTED/FAILED/NEEDS_AUTH/PENDING/DISABLED |
| `McpConfigScope.java` | 24 | **配置作用域枚举** — LOCAL/USER/PROJECT/DYNAMIC/ENTERPRISE/CLAUDEAI/MANAGED |

### 1.4 JSON-RPC 协议层（3 个文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `JsonRpcMessage.java` | 90 | **JSON-RPC 2.0 消息** — sealed interface: Request/Response/Notification/BatchRequest |
| `JsonRpcError.java` | 68 | **错误对象** — 标准错误码 + MCP 自定义码（-32001 超时, -32002 未初始化） |
| `McpProtocolException.java` | 23 | **协议异常** — JSON-RPC 错误到 Java 异常的转换 |

### 1.5 工具适配层（3 个文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `McpToolAdapter.java` | 147 | **工具适配器** — 包装 MCP 工具为内部 `Tool` 接口，名称格式 `mcp__<server>__<tool>`，1MB 截断保护 |
| `ListMcpResourcesTool.java` | 149 | **资源列表工具** — 列出 MCP 服务器暴露的资源 |
| `ReadMcpResourceTool.java` | 148 | **资源读取工具** — 读取 MCP 资源内容（P1 占位，返回元信息） |

### 1.6 认证与安全层（3 个文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `McpAuthTool.java` | 413 | **OAuth 认证工具** — RFC 9728 + RFC 8414 发现，PKCE (S256)，本地回调服务器 |
| `McpApprovalService.java` | 145 | **信任审批服务** — SHA256 配置 hash，`~/.qoder/mcp-trusted.json` 存储，600 权限 |
| `McpAuthFailureCache.java` | 61 | **认证失败缓存** — 15 分钟 TTL，避免重复认证尝试 |

### 1.7 能力注册表层（2 个文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `McpCapabilityRegistryService.java` | 184 | **注册表服务** — CRUD + 防抖持久化 + 按 domain/toolName 查询 |
| `McpCapabilityDefinition.java` | 58 | **能力定义 record** — id/name/toolName/sseUrl/apiKey/超时/输入输出 Schema |

### 1.8 Prompt 适配（1 个文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `McpPromptAdapter.java` | 128 | **Prompt 适配器** — MCP prompt 模板包装为 slash 命令 `/mcp-{server}-{prompt}` |

### 1.9 MCP Server 端（3 个文件，`server/` 子目录）

| 文件 | 行数 | 职责 |
|------|------|------|
| `McpServerEntrypoint.java` | 154 | **Server REST 入口** — JSON-RPC over HTTP，initialize/ping/shutdown/tools 端点 |
| `McpServerStdioTransport.java` | ~190 | **Server STDIO 传输** — 服务端 stdin/stdout 处理 |
| `McpServerToolHandler.java` | ~120 | **Server 工具处理** — tools/list 和 tools/call 的业务逻辑 |

### 1.10 Controller 层（2 个文件）

| 文件 | 行数 | 职责 |
|------|------|------|
| `McpController.java` | 64 | **服务器管理 API** — `/api/mcp/servers` CRUD + restart + logs |
| `McpCapabilityController.java` | 237 | **能力注册表 API** — `/api/mcp/capabilities` CRUD + toggle + test + invoke + server-tools |

---

## 二、测试用例执行结果

### MCP-01: SSE 传输连接测试

**测试方法**: 检查启动日志 + API 验证 + 实际工具调用

**测试结果**:

| 检查项 | 结果 | 详细 |
|--------|------|------|
| application.yml SSE 配置加载 | ✅ PASS | `Loaded MCP server config: zhipu-websearch (type=SSE, url=https://dashscope.aliyuncs.com/api/v1/mcps/zhipu-websearch/sse)` |
| 信任检查机制 | ✅ PASS（但阻止了连接） | `MCP server not trusted, pending approval: zhipu-websearch` → 状态 `NEEDS_AUTH` |
| 能力注册表自动连接 | ✅ PASS | Wan25Media 从注册表启用，SSE 握手成功：`MCP SSE connected: https://dashscope.aliyuncs.com/api/v1/mcps/Wan25Media/sse` |
| SSE endpoint 协商 | ✅ PASS | `MCP SSE endpoint: https://dashscope.aliyuncs.com/api/v1/mcps/Wan25Media/message?sessionId=165c9d8009a848cf87c52d82da9465bf` |
| 能力测试 API (`/test`) | ✅ PASS | `curl POST /api/mcp/capabilities/mcp_web_search_pro/test` → `{"status": "reachable"}` |
| 工具调用 API (`/invoke`) | ✅ PASS | WebSearch 实际返回天气搜索结果，`connectionType: "temporary"` |
| SSE 连接超时断开 | ✅ PASS（符合预期） | 5 分钟后 `MCP SSE connection closed`，服务端超时关闭正常 |
| SSE 关闭时错误 | ⚠️ INFO | Shutdown 时 `stream was reset: CANCEL` — OkHttp 客户端取消导致，非真正错误 |

**关键发现**: `zhipu-websearch` 因信任检查被阻止直连（`NEEDS_AUTH`），但通过能力注册表的 `/invoke` API 可以以临时连接方式成功调用。信任策略正确但配置不便 — application.yml 配置的服务器无法自动信任。

**API 调用实测**:
```bash
# 查询天气预报 — LLM 未使用 MCP 工具（因 zhipu-websearch 状态 NEEDS_AUTH，未注册到工具列表）
curl -X POST http://localhost:8080/api/query -d '{"prompt":"搜索今天天气","maxTurns":5}'
→ LLM 直接回复"没有天气预报功能"，未触发 MCP 工具

# 直接通过能力 API 调用 — 成功
curl -X POST /api/mcp/capabilities/mcp_web_search_pro/invoke -d '{"arguments":{"search_query":"今天天气预报","count":3}}'
→ 成功返回 10 条搜索结果，包含标题/URL/摘要
```

**判定**: ✅ PASS — SSE 传输层实现完整，连接/请求/断开流程正确

---

### MCP-02: stdio 传输测试

**测试方法**: 代码审计（无 stdio 类型服务器配置可用于实测）

**代码分析结果**:

| 检查项 | 结果 | 详细 |
|--------|------|------|
| McpStdioTransport 实现完整性 | ✅ PASS | 158 行，实现 `McpTransport` 全部方法 |
| 进程创建 | ✅ PASS | `ProcessBuilder` + 环境变量注入 + stderr 不合并 |
| JSON-RPC 行协议 | ✅ PASS | `json + "\n"` 写入 stdin，按行读取 stdout |
| 超时控制 | ✅ PASS | `readResponseWithTimeout()` 轮询 `stdoutReader.ready()` + deadline |
| 进程关闭 | ✅ PASS | `destroy()` → 5s 等待 → `destroyForcibly()` |
| 存活检查 | ✅ PASS | `connected.get() && process != null && process.isAlive()` |
| 不重连策略 | ✅ PASS | `McpClientManager.reconnectFailed()` 中 `if (conn.getConfig().type() == STDIO) return;` |
| 传输工厂集成 | ✅ PASS | `McpServerConnection.createTransport()` → `case STDIO → new McpStdioTransport(config)` |

**缺陷**:
1. **⚠️ P2** — `readResponseWithTimeout()` 使用 `Thread.sleep(10)` 轮询，CPU 占用较高；原版使用 `ExecutorService` + `Future.get(timeout)` 方式
2. **⚠️ P2** — 缺少 stderr 日志输出（`redirectErrorStream(false)` 但没有消费 stderr 流，可能导致缓冲区满阻塞）
3. **⚠️ P3** — 缺少通知处理 — `notificationHandler` 字段存在但 `connect()` 中没有启动独立的 stdout 读取线程来分发通知

**判定**: ✅ PASS（代码审计） — 核心 RPC 通信完整，但 stderr 处理和通知分发存在缺陷

---

### MCP-03: WebSocket 传输测试

**测试方法**: 代码审计（无 WebSocket 类型 MCP 服务配置可用于实测）

**代码分析结果**:

| 检查项 | 结果 | 详细 |
|--------|------|------|
| McpWebSocketTransport 实现完整性 | ✅ PASS | 244 行，基于 `org.java_websocket.client.WebSocketClient` |
| 连接管理 | ✅ PASS | `connectBlocking(10s)` + `setConnectionLostTimeout(30)` |
| 请求/响应匹配 | ✅ PASS | `ConcurrentHashMap<String, CompletableFuture<JsonNode>>` id 匹配 |
| 通知处理 | ✅ PASS | `onMessage()` 中检测 `node.has("method")` → `notificationHandler.accept()` |
| 关闭清理 | ✅ PASS | 关闭 WS + 取消所有 pending requests |
| 传输工厂集成 | ✅ PASS | `case WS, WS_IDE → new McpWebSocketTransport(url.replace("http://","ws://"))` |
| URL 协议转换 | ✅ PASS | 自动将 `http://` → `ws://`、`https://` → `wss://` |
| `onClose` 处理 | ✅ PASS | 通知 `connectFuture` + 取消所有 pending |

**判定**: ✅ PASS（代码审计） — 全双工 WebSocket 传输实现完整

---

### MCP-04: OAuth 2.0 + PKCE 认证测试

**测试方法**: 代码审计（当前无需 OAuth 认证的 MCP 服务可实测）

**OAuth 流程完整性分析**:

| 步骤 | 实现状态 | 详细 |
|------|----------|------|
| 1. OAuth 发现 (RFC 9728) | ✅ 完整 | 先尝试 `/.well-known/oauth-protected-resource`，再回退 `/.well-known/oauth-authorization-server` |
| 2. PKCE 参数生成 | ✅ 完整 | `SecureRandom` 32 字节 → Base64URL，SHA-256 → S256 challenge |
| 3. 授权 URL 构建 | ✅ 完整 | `client_id=mcp-{serverName}`, `response_type=code`, `code_challenge_method=S256` |
| 4. 本地回调服务器 | ✅ 完整 | 虚拟线程启动 `ServerSocket`，5 分钟超时，解析 `GET /callback?code=xxx&state=yyy` |
| 5. 浏览器打开 | ✅ 完整 | `Desktop.getDesktop().browse()` + `open` 命令回退 |
| 6. 令牌交换 | ✅ 完整 | `grant_type=authorization_code` + `code_verifier` POST 到 token endpoint |
| 7. 令牌存储 | ✅ 完整 | `~/.claude/mcp-tokens/{hash}.json` |
| 8. state 校验 | ✅ 完整 | 回调中验证 `expectedState.equals(state)` |

**信任审批流程 (McpApprovalService)**:

| 检查项 | 结果 | 详细 |
|--------|------|------|
| 信任记录存储 | ✅ | `~/.qoder/mcp-trusted.json`，600 权限 |
| 配置 hash 校验 | ✅ | `SHA256(type + command + args + url)`，配置变更需重新审批 |
| 注册表自动信任 | ✅ | `enableFromRegistry()` 中 `approvalService.recordApproval(config, "REGISTRY")` |
| 认证失败缓存 | ✅ | `McpAuthFailureCache` 15 分钟 TTL |

**缺陷**:
1. **⚠️ P2** — 令牌刷新（refresh_token）未实现 — 存储了 refresh_token 但没有刷新逻辑
2. **⚠️ P3** — 令牌存储路径 `~/.claude/mcp-tokens/` 使用 Claude 路径而非 ZhikuCode 自有路径（应为 `~/.qoder/mcp-tokens/`）

**判定**: ✅ PASS（代码审计） — OAuth 2.0 + PKCE 核心流程完整，缺少令牌刷新

---

### MCP-05: McpConfigurationResolver 4 层优先级测试

**测试方法**: 代码审计 + 启动日志验证

**4 层配置来源及优先级**:

| 优先级 | 来源 | 路径/变量 | Scope 枚举 | 状态 |
|--------|------|-----------|------------|------|
| 4（最低） | ENV 环境变量 | `MCP_SERVERS` (JSON 字符串) | DYNAMIC | ✅ 实现 |
| 3 | ENTERPRISE 企业级 | `/etc/ai-code-assistant/mcp.json` | ENTERPRISE | ✅ 实现（文件不存在时跳过） |
| 2 | USER 用户级 | `~/.config/ai-code-assistant/mcp.json` | USER | ✅ 实现（文件不存在时跳过） |
| 1（最高） | LOCAL 项目本地 | `{workingDir}/.ai-code-assistant/mcp.json` | LOCAL | ✅ 实现（workingDir 为空时跳过） |

**配置合并逻辑验证**:
```java
// 按优先级从低到高加载（后加载覆盖先加载同名服务器）
loadFromEnvironment().forEach(c -> merged.put(c.name(), c));    // ENV (最低)
loadFromFile(ENTERPRISE, ...).forEach(c -> merged.put(c.name(), c)); // ENTERPRISE
loadFromFile(USER, ...).forEach(c -> merged.put(c.name(), c));       // USER
loadFromFile(LOCAL, ...).forEach(c -> merged.put(c.name(), c));      // LOCAL (最高)
```

**启动日志验证**:
```
MCP config file not found: /etc/ai-code-assistant/mcp.json
MCP config file not found: /Users/guoqingtao/.config/ai-code-assistant/mcp.json
Resolved 0 MCP server configurations from 0 sources
```
- resolver 返回 0 条（所有配置文件不存在）
- application.yml 作为补充来源加载了 `zhipu-websearch`

**JSON 格式兼容**:
```java
JsonNode servers = root.has("mcpServers") ? root.get("mcpServers") : root;
```
支持 `{ "mcpServers": {...} }` 和 `{ "name": {...} }` 两种格式。

**传输类型自动推断**:
```java
if (serverNode.has("command")) type = STDIO;
else if (serverNode.has("url")) type = switch(typeStr) { "HTTP" → HTTP; "WS" → WS; default → SSE };
```

**判定**: ✅ PASS — 4 层优先级覆盖逻辑正确，合并策略清晰

---

### MCP-06: 运行时工具发现测试

**测试方法**: 启动日志 + API 验证

**工具注册流程**:
1. `McpClientManager.start()` → `initializeAll()`
2. `addServer()` 连接成功后 → `registerToolsFromConnection(conn)`
3. 遍历 `conn.getTools()` → 权限检查 → 注册表描述增强 → `McpToolAdapter` → `toolRegistry.registerDynamic()`
4. 监听 `onToolsChanged()` → 自动重新注册

**启动日志工具列表**:
```
ToolRegistry initialized with 37 tools: [Write, TaskUpdate, ListMcpResources, SyntheticOutput, 
Memory, Config, CronCreate, WebFetch, TaskList, REPL, Edit, ReadMcpResource, Brief, Read, 
Monitor, Grep, TaskOutput, SendMessage, TaskStop, Agent, NotebookEdit, ExitPlanMode, WebSearch, 
TaskGet, LSP, ToolSearch, AskUserQuestion, Bash, Skill, TaskCreate, Sleep, CronDelete, 
WebBrowser, EnterPlanMode, TodoWrite, Glob, CronList]
```

**MCP 工具在列表中的状态**:
- `ListMcpResources` — ✅ 静态注册，可用
- `ReadMcpResource` — ✅ 静态注册，可用
- MCP 动态工具（如 `mcp__zhipu-websearch__webSearchPro`） — ❌ 未出现（因 zhipu-websearch 状态 NEEDS_AUTH）
- Wan25Media 连接成功但工具列表为空 → `tools: []`（SSE 连接后未执行 `tools/list` 初始化）

**关键问题**:
- **🔴 P1 缺陷**: `McpServerConnection.connect()` 建立传输层连接后，**没有发送 `initialize` 请求和 `tools/list` 请求**来获取工具列表
- 对比: `McpCapabilityController.listServerTools()` 中手动执行了 `initialize` + `tools/list`，但 `connect()` 流程本身没有
- 结果: Wan25Media 显示 `CONNECTED` 但 `tools: []`，动态工具无法注册到 ToolRegistry

**判定**: ⚠️ PARTIAL PASS — 工具注册框架完整，但缺少 MCP 协议初始化（initialize + tools/list），导致动态 MCP 工具无法自动注册

---

### MCP-07: MCP 资源访问测试

**测试方法**: 代码审计 + API 调用

**ListMcpResourcesTool**:
- 遍历所有 `getConnectedServers()` → `conn.getResources()` → 格式化输出
- 支持 `server` 参数过滤
- 实际调用返回空（因连接的服务器 resources 列表为空）

**ReadMcpResourceTool**:
- 按 `server` + `uri` 查找资源
- **P1 占位**: 返回资源元信息，注释标注 `[P1 placeholder: actual content reading requires MCP Java SDK integration]`
- 512KB 截断保护

**资源发现的同一问题**: `connect()` 流程未发送 `resources/list` 请求，故 `conn.getResources()` 始终为空。

**判定**: ⚠️ PARTIAL PASS — 工具框架完整，ReadMcpResource 为 P1 占位，资源发现受限于初始化流程缺陷

---

### MCP-08: SmartLifecycle 启停管理测试

**测试方法**: 启动/停止日志验证 + 代码审计

**SmartLifecycle 实现**:

| 检查项 | 结果 | 详细 |
|--------|------|------|
| Phase | ✅ | `getPhase() = 2`（Python 服务之后，FeatureFlagService 之前） |
| `start()` | ✅ | 日志 `McpClientManager starting — initializing MCP connections` |
| `stop()` | ✅ | 日志 `McpClientManager stopping — closing all MCP connections` |
| `isRunning()` | ✅ | 通过 `volatile boolean running` 控制 |
| 初始化顺序 | ✅ | resolver → application.yml → 能力注册表，三阶段串行 |
| 优雅关闭 | ✅ | `shutdown()` 遍历所有连接执行 `close()` + `connections.clear()` |

**健康检查**:
```java
@Scheduled(fixedDelay = 30000)
public void healthCheck() {
    // 检测 CONNECTED 但 !isAlive() → 标记 FAILED
    // FAILED + 非 STDIO → attemptReconnect()
}
```

**重连策略**:

| 参数 | 值 | 验证 |
|------|------|------|
| 最大重试次数 | 5 | `MAX_RECONNECT_ATTEMPTS = 5` |
| 初始退避 | 1s | `INITIAL_BACKOFF_MS = 1000` |
| 最大退避 | 30s | `MAX_BACKOFF_MS = 30_000` |
| 退避公式 | `min(1000 * 2^(n-1), 30000)` | 1s → 2s → 4s → 8s → 16s |
| STDIO 不重连 | ✅ | `if (type == STDIO) return;` |

**启动日志时序**:
```
17:54:31.514 McpClientManager starting
17:54:31.514 MCP config file not found: /etc/...
17:54:31.515 MCP config file not found: ~/.config/...
17:54:31.516 Resolved 0 MCP server configurations
17:54:31.517 Loaded MCP server config: zhipu-websearch (type=SSE)
17:54:31.524 MCP server not trusted, pending approval: zhipu-websearch
17:54:31.525 MCP initialization complete — 1 connections (0 from resolver, 1 from yml)
17:54:31.526 Enabling MCP capability 'mcp_wan25_image_edit' → server 'Wan25Media'
17:54:47.565 MCP server 'Wan25Media' connected via SSE
17:54:47.565 MCP registry: activated 1 capabilities from 3 enabled entries
```

**停止日志**:
```
17:54:17.216 McpClientManager stopping — closing all MCP connections
```

**判定**: ✅ PASS — SmartLifecycle 实现完整，启停顺序正确，健康检查和重连策略合理

---

## 三、4 种传输方式实现状态总结

| 传输类型 | 实现文件 | 行数 | 代码完整度 | 实测状态 | 评级 |
|----------|----------|------|------------|----------|------|
| **SSE** | `McpSseTransport.java` | 264 | ✅ 完整 | ✅ 实测通过（DashScope SSE 连接 + 工具调用成功） | **A** |
| **STDIO** | `McpStdioTransport.java` | 158 | ⚠️ 基本完整 | 📋 代码审计（缺少 stderr 处理和通知线程） | **B** |
| **WebSocket** | `McpWebSocketTransport.java` | 244 | ✅ 完整 | 📋 代码审计（依赖 java-websocket 库） | **A-** |
| **Streamable HTTP** | `McpStreamableHttpTransport.java` | 309 | ✅ 完整 | 📋 代码审计（最新 MCP 规范，Session-Id 管理） | **A-** |

---

## 四、MCP 能力注册表分析

### 4.1 注册表概况

- **文件**: `configuration/mcp/mcp_capability_registry.json`
- **Schema 版本**: 1.0
- **注册能力数量**: 3 个，全部启用
- **更新时间**: 2026-03-11

### 4.2 已注册能力

| ID | 名称 | 工具名 | SSE URL | 域 | 超时 | 视频通话 |
|----|------|--------|---------|-----|------|----------|
| `mcp_wan25_image_gen` | 万相2.5图像生成 | `modelstudio_image_gen_wan25` | `.../Wan25Media/sse` | image_processing | 120s | ❌ |
| `mcp_wan25_image_edit` | 万相2.5图像编辑 | `modelstudio_image_edit_wan25` | `.../Wan25Media/sse` | image_processing | 120s | ❌ |
| `mcp_web_search_pro` | 网络搜索Pro | `webSearchPro` | `.../zhipu-websearch/sse` | web_search | 30s | ✅ |

### 4.3 注册表字段定义

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 唯一标识（`mcp_` 前缀） |
| `name` | string | 中文显示名称 |
| `toolName` | string | MCP Server 上的原始工具名 |
| `sseUrl` | string | MCP Server SSE 端点 |
| `apiKeyConfig` | string | Spring 配置属性名（如 `dashscope.api-key`） |
| `apiKeyDefault` | string | API Key 默认值 |
| `domain` | string | 功能域分类 |
| `category` | string | 固定 `MCP_TOOL` |
| `briefDescription` | string | 简要描述（50字内） |
| `videoCallSummary` | string | 精简描述（15字内） |
| `description` | string | 完整描述 |
| `input` / `output` | object | 参数 Schema |
| `timeoutMs` | integer | 调用超时 |
| `enabled` | boolean | 是否启用 |
| `videoCallEnabled` | boolean | 视频通话场景是否启用 |

### 4.4 注册表与实际实现的对应

- `extractServerKey()` 从 `sseUrl` 路径提取倒数第二段作为服务器 key
  - `https://dashscope.aliyuncs.com/api/v1/mcps/Wan25Media/sse` → `Wan25Media`
  - `https://dashscope.aliyuncs.com/api/v1/mcps/zhipu-websearch/sse` → `zhipu-websearch`
- `McpClientManager.enableFromRegistry()` 从注册表自动构建 `McpServerConfig` 并创建连接
- API Key 解析: 优先从 Spring Environment 读取 `apiKeyConfig` 属性，回退到 `apiKeyDefault`

---

## 五、与原版 Claude Code MCP 系统的对照

| 功能 | Claude Code 原版 | ZhikuCode 实现 | 差异评估 |
|------|------------------|----------------|----------|
| **传输类型** | SSE + STDIO (核心) | SSE + STDIO + WS + Streamable HTTP (4种) | ✅ 超越，多出 WS 和 HTTP |
| **配置层级** | ENV → project → user | ENV → ENTERPRISE → USER → LOCAL → application.yml → Registry | ✅ 更丰富（6 来源） |
| **MCP 协议初始化** | initialize → tools/list → notifications/initialized | ❌ connect() 后未执行 initialize + tools/list | 🔴 关键缺失 |
| **工具发现** | 连接后自动发现并注册 | 框架完整但受初始化缺陷影响 | ⚠️ 部分 |
| **OAuth 2.0** | RFC 9728 + PKCE 完整流程 | ✅ RFC 9728 + RFC 8414 + PKCE | ✅ 对齐 |
| **信任审批** | 首次使用需用户确认 | SHA256 hash + 文件存储 | ✅ 对齐 |
| **重连策略** | 指数退避 + STDIO 不重连 | 指数退避 1-30s + STDIO 不重连 | ✅ 对齐 |
| **资源访问** | resources/list + resources/read | ListMcpResources + ReadMcpResource (P1 占位) | ⚠️ 部分 |
| **Prompt 支持** | prompts/list → slash 命令 | McpPromptAdapter → `/mcp-{server}-{prompt}` | ✅ 对齐 |
| **能力注册表** | 无（原版无此概念） | ✅ 独创：细粒度工具定义 + CRUD API | ✅ 增值创新 |
| **MCP Server 端** | 无（仅客户端） | ✅ REST + STDIO Server 入口 | ✅ 增值创新 |
| **通道权限** | channelPermissions 工具级黑名单 | ✅ `isToolAllowed()` 服务器+工具级 | ✅ 对齐 |
| **SmartLifecycle** | N/A (TypeScript) | ✅ Phase=2 的 Spring 生命周期 | ✅ Java 特有 |

---

## 六、发现的问题和建议

### 🔴 P1 关键问题

| # | 问题 | 影响 | 建议修复 |
|---|------|------|----------|
| 1 | **MCP 协议初始化缺失** — `McpServerConnection.connect()` 建立传输连接后，未发送 `initialize` 请求和 `tools/list` 请求 | Wan25Media 显示 CONNECTED 但 `tools: []`，动态 MCP 工具无法自动注册到 ToolRegistry | 在 `connect()` 成功后增加 `initialize` + `notifications/initialized` + `tools/list` + `resources/list` 调用序列 |
| 2 | **zhipu-websearch 信任问题** — application.yml 配置的服务器不自动信任，导致 `NEEDS_AUTH` 状态 | MCP 网络搜索工具无法通过 LLM 自动调用 | 应对 application.yml 来源的服务器自动信任（类似注册表的 `recordApproval(config, "REGISTRY")`），或在启动时自动为 yml 配置的服务器执行审批 |

### ⚠️ P2 中等问题

| # | 问题 | 影响 | 建议修复 |
|---|------|------|----------|
| 3 | **STDIO stderr 未消费** — `ProcessBuilder.redirectErrorStream(false)` 但未启动 stderr 读取线程 | 子进程 stderr 缓冲区满可能导致进程阻塞 | 添加独立线程消费 stderr 并写入日志 |
| 4 | **STDIO 通知未分发** — `notificationHandler` 存在但无独立 stdout 读取线程 | 无法接收服务端推送的通知（如 `notifications/tools/list_changed`） | 启动独立的 stdout 读取线程，区分响应和通知 |
| 5 | **令牌刷新未实现** — 存储了 `refresh_token` 但没有刷新逻辑 | OAuth 令牌过期后需重新完整认证 | 实现 `refreshAccessToken()` + 过期前自动刷新 |
| 6 | **SSE 连接 5 分钟超时断开** — DashScope SSE 服务端约 5 分钟关闭空闲连接 | 健康检查发现断开后虽会尝试重连，但工具调用窗口有间隙 | 考虑 SSE 心跳或按需连接策略 |
| 7 | **`/mcp` 命令重复注册** — 日志显示 `/mcp` 命令被重复注册 30+ 次 | 资源浪费，日志噪音 | 检查 CommandRegistry 去重逻辑 |

### 💡 P3 改进建议

| # | 建议 | 说明 |
|---|------|------|
| 8 | 令牌存储路径对齐 | `McpAuthTool` 使用 `~/.claude/mcp-tokens/`，应改为 `~/.qoder/mcp-tokens/` |
| 9 | ReadMcpResource 实际实现 | 当前 P1 占位，应发送 `resources/read` JSON-RPC 请求 |
| 10 | 健康检查中 `Thread.sleep()` 阻塞 | `attemptReconnect()` 在 `@Scheduled` 线程中 sleep，可能阻塞其他定时任务 | 改为异步重连 |
| 11 | 能力注册表 API Key 硬编码在 JSON 文件中 | `apiKeyDefault` 字段包含明文 API Key | 建议仅支持 `apiKeyConfig` 引用 + 环境变量 |

---

## 七、测试结论

| 维度 | 评分 | 说明 |
|------|------|------|
| **架构设计** | ⭐⭐⭐⭐⭐ | 28 文件职责清晰，统一传输抽象优秀，超越原版（4 种传输 + Server 端 + 能力注册表） |
| **SSE 传输** | ⭐⭐⭐⭐⭐ | 实测通过，DashScope 连接成功，工具调用返回正确结果 |
| **STDIO 传输** | ⭐⭐⭐⭐ | 核心完整，stderr/通知处理有缺陷 |
| **WebSocket 传输** | ⭐⭐⭐⭐ | 代码审计完整，未实测 |
| **HTTP 传输** | ⭐⭐⭐⭐⭐ | 最新 MCP 规范实现，Session-Id 管理完善 |
| **OAuth 认证** | ⭐⭐⭐⭐ | 完整 PKCE 流程，缺少令牌刷新 |
| **配置优先级** | ⭐⭐⭐⭐⭐ | 4 层 + yml + Registry 共 6 来源，合并逻辑正确 |
| **工具发现** | ⭐⭐⭐ | 框架完整但 P1 缺陷：connect 后未初始化协议 |
| **生命周期管理** | ⭐⭐⭐⭐⭐ | SmartLifecycle + 健康检查 + 指数退避重连 |
| **能力注册表** | ⭐⭐⭐⭐⭐ | 独创增值，CRUD API + 自动连接 + 测试/调用 |

**总体评价**: **4.2/5** — MCP 模块架构设计优秀，传输层抽象超越原版，能力注册表为独创增值。主要障碍是 **connect() 后缺少 MCP 协议初始化序列**（P1），导致动态工具无法自动注册。修复此问题后，MCP 系统将完全可用。
