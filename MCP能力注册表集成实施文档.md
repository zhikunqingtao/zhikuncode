# MCP 能力注册表集成实施文档

> 版本: 2.3 | 日期: 2026-04-12 | 状态: 审查修正版（修复 P-1~P-14 共 14 项审查发现）

---

## 一、现状诊断

### 1.1 `mcp_capability_registry.json` 当前状态

- **文件规模**: 6482 行 / 242KB，定义 156 条 MCP 工具（117 个 enabled）
- **代码引用**: **零运行时引用**，全项目仅 `McpWebSearchBackend.java:20` 注释中提及
- **闲置元数据**: 中文描述(briefDescription/description)、timeoutMs、input/output Schema、domain 分类、videoCallEnabled、apiKeyConfig + apiKeyDefault

### 1.2 当前 MCP 工具加载路径（与注册表完全无关）

```
McpConfigurationResolver.resolveAll()       →  4 级文件源 (ENV/ENTERPRISE/USER/LOCAL)
McpConfiguration.toMcpServerConfigs()       →  application.yml (mcp.servers.*)
       ↓
McpClientManager.initializeAll()            →  addServer() → connect() → registerToolsFromConnection()
       ↓
McpServerConnection.getTools()              →  MCP 服务器自报的工具列表（原始英文描述）
       ↓
McpToolAdapter(name, description, inputSchema, connection, originalToolName)
       ↓                                        ↑ 无超时覆盖、无描述增强
ToolRegistry.registerDynamic(adapter)
```

### 1.3 核心差距对照表

| 维度 | 当前实现 | 注册表可提供 | 差距影响 |
|------|----------|-------------|---------|
| 工具描述 | MCP 服务器原始英文描述 | 精细化中文 briefDescription + description | AI 工具选择准确度降低 |
| 超时配置 | `DEFAULT_REQUEST_TIMEOUT_MS = 30_000` (McpServerConnection:49) | 按工具定制 (图像生成 120s, 搜索 30s) | 图像生成类工具超时失败 |
| 启用/禁用 | 仅 `channelPermissions` 粗粒度屏蔽 (McpClientManager:331-336) | 工具级 `enabled` 开关 | 无法细粒度管理 156 个工具 |
| 参数 Schema | 服务器自报 `McpToolDefinition.inputSchema()` | 增强版中文 Schema + example | 参数提示不够友好 |
| API Key 管理 | `application.yml` 硬编码单个 `${LLM_API_KEY}` | `apiKeyConfig` 属性名 + `apiKeyDefault` 回退 | 多 API Key 无法管理 |
| 功能域分类 | 无 | `domain` 字段 (image_processing/web_search/map_navigation...) | 无法分类展示和过滤 |
| 场景过滤 | 无 | `videoCallEnabled` 场景级开关 | 无法按场景启用子集 |

### 1.4 传输层架构缺陷（P7 问题根因）

项目已有 `McpSseTransport`(253行)、`McpStreamableHttpTransport`(293行)、`McpWebSocketTransport`(244行) 三个独立传输类，方法签名**高度一致**：

| 方法 | SSE | HTTP | WS | STDIO (散落在 McpServerConnection) |
|------|-----|------|----|------|
| `sendRequest(method, params, timeoutMs)` → `JsonNode` | ✅ L122 | ✅ L109 | ✅ L101 | `sendRequestAndWait()` 间接 |
| `connect()` → `CompletableFuture<Void>` | ✅ L69 | ✅ L76 | ✅ L70 | `connectStdio()` 无返回值 |
| `isConnected()` → `boolean` | ✅ L236 | ✅ L258 | ✅ L149 | `isAlive()` 不同名 |
| `close()` | ✅ L240 | ✅ L267 | ✅ L154 | 混在 close() 内 |
| `sendNotification(method, params)` | ✅ L170 | ✅ L235 | ✅ L135 | ✅ L253 |
| `setNotificationHandler(Consumer<JsonNode>)` | ✅ L62 | ✅ L69 | ✅ L63 | ❌ |

但它们**没有共同接口**，导致：
- `McpServerConnection.connect()` 充斥 switch 分支 (L68-L81)
- STDIO 通信代码(80+行)散落在 `McpServerConnection` 内
- `McpToolAdapter.call()` 硬编码 STDIO 路径: `sendRequest(String)` + `readResponse()` (L99, L102)
- SSE 连接的 `connect()` 仅标记状态 (L72-L75)，**未创建 McpSseTransport 实例**
- 注册表 156 个 SSE 工具调用时 `stdinWriter == null` → IOException

---

## 二、架构设计

### 2.1 定位：MCP 工具市场 + 配置中枢 + 传输统一

将 `mcp_capability_registry.json` 从静态参考文档升级为 **MCP 工具预定义目录**，同时重构传输层为统一接口：

- **传输统一**: `McpTransport` 接口 + 4 种实现（SSE/HTTP/WS/STDIO），消除所有 switch 分支
- **工具市场**: 用户在前端浏览 156 个预定义工具，一键启用/禁用
- **配置中枢**: 提供 sseUrl、apiKey、timeout 等完整连接参数，用户无需手工填写
- **描述增强**: 用中文描述覆盖 MCP 服务器原始英文描述，提升 AI 工具选择准确度
- **运行时生效**: 启用工具时自动创建 MCP 服务器连接并注册到 ToolRegistry

### 2.2 模块关系图

```
                    ┌─────────────────────────┐
                    │  McpTransport (接口)     │ ← ★ 新增: 统一传输契约
                    ├─────────────────────────┤
                    │  McpSseTransport        │ ← 已有: +implements
                    │  McpStreamableHttp...   │ ← 已有: +implements
                    │  McpWebSocketTransport  │ ← 已有: +implements
                    │  McpStdioTransport      │ ← ★ 新增: 从 McpServerConnection 提取
                    └───────────┬─────────────┘
                                │ (transport 字段)
                    ┌───────────▼─────────────┐
                    │  McpServerConnection    │ ← 重构: 消除 switch，持有单一 transport
                    └───────────┬─────────────┘
                                │
mcp_capability_registry.json    │
       ↓ (@PostConstruct 加载)   │
McpCapabilityRegistryService    │ ← 新增: 注册表加载/查询/持久化
       ↓ (REST API)             │
McpCapabilityController         │ ← 新增: CRUD + 启用/禁用 API
       ↓ (enableFromRegistry)   │     路径: /api/mcp/capabilities
McpClientManager.addServer() ───┘ ← 已有: 连接 MCP 服务器
       ↓ (registerToolsFromConnection)
McpToolAdapter(... enhancedDescription, timeoutMs)  ← 简化: connection.callTool()
       ↓
ToolRegistry.registerDynamic()            ← 已有: 注册到工具池
       ↓
SettingsPanel → MCP Tab                   ← 新增: 前端管理页面
```

---

## 三、详细实施方案

### Task 1: 后端 — McpTransport 统一传输接口

**文件**: `backend/src/main/java/com/aicodeassistant/mcp/McpTransport.java` (新增)

**完整代码**:

```java
package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * MCP 传输层统一接口 — 所有传输类型（SSE/HTTP/WS/STDIO）的公共契约。
 * <p>
 * 每种传输实现负责:
 * <ul>
 *   <li>连接建立与生命周期管理</li>
 *   <li>JSON-RPC 请求/响应的序列化与超时控制</li>
 *   <li>服务端通知的异步接收</li>
 * </ul>
 *
 * @see McpSseTransport
 * @see McpStreamableHttpTransport
 * @see McpWebSocketTransport
 * @see McpStdioTransport
 */
public interface McpTransport extends AutoCloseable {

    /** 建立连接，返回连接就绪的 Future */
    CompletableFuture<Void> connect();

    /**
     * 发送 JSON-RPC 请求并同步等待响应。
     *
     * @param method    JSON-RPC 方法名 (如 "tools/call", "tools/list")
     * @param params    方法参数 (序列化为 JSON)
     * @param timeoutMs 超时毫秒数
     * @return result 字段的 JsonNode
     * @throws McpProtocolException 协议错误或超时
     */
    JsonNode sendRequest(String method, Object params, long timeoutMs) throws McpProtocolException;

    /** 发送 JSON-RPC 通知 — 不期望响应 */
    void sendNotification(String method, Object params);

    /** 连接是否存活 */
    boolean isConnected();

    /** 注册服务端通知处理器 */
    void setNotificationHandler(Consumer<JsonNode> handler);

    /** 关闭连接并释放资源 */
    @Override
    void close();
}
```

**设计说明**:
- 接口方法签名直接从现有 3 个传输类的公共方法提取，**零适配成本**
- `sendRequest()` 返回 `JsonNode`（result 字段），由各传输层内部处理 JSON-RPC 信封
- 继承 `AutoCloseable`，确保资源释放语义一致

---

### Task 2: 后端 — McpStdioTransport 提取 + 现有传输适配

**文件 A**: `backend/src/main/java/com/aicodeassistant/mcp/McpStdioTransport.java` (新增)

从 `McpServerConnection` 中提取 STDIO 通信代码（process, stdinWriter, stdoutReader, readResponseWithTimeout）：

```java
package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MCP STDIO 传输层 — 通过子进程 stdin/stdout 进行 JSON-RPC 行协议通信。
 * <p>
 * 从 McpServerConnection 提取的 STDIO 相关代码，统一为 McpTransport 接口。
 *
 * @see McpTransport
 */
public class McpStdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpStdioTransport.class);

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong requestIdSequence = new AtomicLong(1);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private volatile Process process;
    private volatile BufferedReader stdoutReader;
    private volatile OutputStream stdinWriter;
    private Consumer<JsonNode> notificationHandler;

    public McpStdioTransport(McpServerConfig config) {
        this.command = config.command();
        this.args = config.args() != null ? config.args() : List.of();
        this.env = config.env() != null ? config.env() : Map.of();
    }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(command);
                cmd.addAll(args);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                if (!env.isEmpty()) pb.environment().putAll(env);

                this.process = pb.start();
                this.stdinWriter = process.getOutputStream();
                this.stdoutReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                connected.set(true);
                log.info("STDIO transport connected (pid={})", process.pid());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to start STDIO process: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public JsonNode sendRequest(String method, Object params, long timeoutMs) throws McpProtocolException {
        if (!connected.get()) {
            throw new McpProtocolException(new JsonRpcError(
                    JsonRpcError.SERVER_NOT_INITIALIZED, "STDIO not connected"));
        }
        long id = requestIdSequence.getAndIncrement();
        try {
            JsonRpcMessage.Request request = new JsonRpcMessage.Request(id, method, params);
            String json = objectMapper.writeValueAsString(request);
            stdinWriter.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            stdinWriter.flush();

            String responseLine = readResponseWithTimeout(timeoutMs);
            if (responseLine == null) {
                throw new McpProtocolException(new JsonRpcError(
                        JsonRpcError.REQUEST_TIMEOUT, "STDIO timeout: " + method));
            }
            JsonNode node = objectMapper.readTree(responseLine);
            if (node.has("error") && !node.get("error").isNull()) {
                throw new McpProtocolException(
                        objectMapper.treeToValue(node.get("error"), JsonRpcError.class));
            }
            return node.has("result") ? node.get("result") : null;
        } catch (McpProtocolException e) {
            throw e;
        } catch (IOException e) {
            throw new McpProtocolException("STDIO communication error: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendNotification(String method, Object params) {
        try {
            JsonRpcMessage.Notification notification = new JsonRpcMessage.Notification(method, params);
            String json = objectMapper.writeValueAsString(notification);
            stdinWriter.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            stdinWriter.flush();
        } catch (IOException e) {
            log.warn("Failed to send STDIO notification '{}': {}", method, e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get() && process != null && process.isAlive();
    }

    @Override
    public void setNotificationHandler(Consumer<JsonNode> handler) {
        this.notificationHandler = handler;
    }

    @Override
    public void close() {
        connected.set(false);
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        try { if (stdoutReader != null) stdoutReader.close(); } catch (Exception ignored) {}
        try { if (stdinWriter != null) stdinWriter.close(); } catch (Exception ignored) {}
    }

    /** 带超时的响应读取 — 从原 McpServerConnection.readResponseWithTimeout() 提取 */
    private String readResponseWithTimeout(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (stdoutReader != null && stdoutReader.ready()) {
                return stdoutReader.readLine();
            }
            try { Thread.sleep(10); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
```

**文件 B/C/D**: 现有传输类添加 `implements McpTransport`

```java
// McpSseTransport.java (L27) — 零方法改动
public class McpSseTransport implements McpTransport {

// McpWebSocketTransport.java (L30) — 零方法改动
public class McpWebSocketTransport implements McpTransport {

// McpStreamableHttpTransport.java (L32) — connect() 签名微调
public class McpStreamableHttpTransport implements McpTransport {
    // ★ 新增字段: 缓存 clientCapabilities
    private Map<String, Object> clientCapabilities;

    public void setClientCapabilities(Map<String, Object> caps) {
        this.clientCapabilities = caps;
    }

    /** ★ McpTransport 接口要求的无参 connect() — 委托给原有方法 */
    @Override
    public CompletableFuture<Void> connect() {
        return connect(this.clientCapabilities);  // 委托给原有方法
    }

    // ★ 保留原有 connect(Map) 为 public — 不破坏外部直接调用
    // public CompletableFuture<Void> connect(Map<String, Object> clientCapabilities) { ... }
}
```

**设计说明**:
- `McpStdioTransport` 完整提取 `McpServerConnection` 中的 STDIO 代码
- SSE 和 WS 签名已完全匹配接口，加 `implements` 即可，**零方法改动**
- HTTP 的 `connect(Map)` 拆分为 `setClientCapabilities()` + 无参 `connect()`

---

### Task 3: 后端 — McpServerConnection 传输统一重构

**文件**: `backend/src/main/java/com/aicodeassistant/mcp/McpServerConnection.java` (修改)

**核心变更**: 消除 5 个分散传输字段 + 多个 switch 分支 → 1 个 `McpTransport transport` + 工厂方法

**变更点 1**: 字段精简

```java
// ===== 删除以下字段 (L38-L54) =====
private volatile Process process;                          // → 迁入 McpStdioTransport
private volatile BufferedReader stdoutReader;               // → 迁入 McpStdioTransport
private volatile OutputStream stdinWriter;                  // → 迁入 McpStdioTransport
private volatile McpStreamableHttpTransport httpTransport;   // → 由统一 transport 字段替代
private volatile McpWebSocketTransport wsTransport;         // → 由统一 transport 字段替代
private final AtomicLong requestIdSequence = ...;           // ★ P-13: → 迁入 McpStdioTransport（各 Transport 自持）
private final Map<Long, CompletableFuture<Object>> pendingRequests = ...; // ★ P-13: → 删除（sendRequestAndWait 删除后无用）
private final ObjectMapper objectMapper = ...;              // ★ P-13: → 删除（各 Transport 自持 ObjectMapper）
// ★ P-13: 保留以下常量（callTool/request/connectWithRetry 仍使用）:
//   private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000;  (L48-49)
//   private static final int MAX_RECONNECT_ATTEMPTS = 3;            (L50)

// ===== 替换为 =====
/** ★ 统一传输实例 — 替代原来的 process/stdinWriter/stdoutReader/httpTransport/wsTransport */
private volatile McpTransport transport;
```

**变更点 2**: `connect()` 消除 switch

```java
// ===== 原始代码 (L67-L82, 含 5 个 switch 分支 + 3 个 connect 方法) =====
public void connect() {
    switch (config.type()) {
        case STDIO -> connectStdio();
        case HTTP -> connectHttp();
        case WS -> connectWebSocket();
        case SSE, SSE_IDE -> { this.status = McpConnectionStatus.CONNECTED; }
        default -> { ... }
    }
}

// ===== 修改后 (消除所有 switch + 3 个 private connect 方法) =====
public void connect() {
    try {
        // ★ 重连安全: 关闭旧 transport 防止资源泄漏 (connectWithRetry 场景)
        if (transport != null) {
            try { transport.close(); } catch (Exception ignored) {}
        }
        this.transport = createTransport(config);
        if (transport == null) {
            // 不支持的传输类型 — 容错标记为 CONNECTED (与原始行为一致)
            this.status = McpConnectionStatus.CONNECTED;
            return;
        }
        transport.connect().get(30, TimeUnit.SECONDS);
        this.status = McpConnectionStatus.CONNECTED;
        log.info("MCP server '{}' connected via {}", config.name(), config.type());
    } catch (Exception e) {
        log.error("Failed to connect MCP server '{}': {}", config.name(), e.getMessage());
        this.status = McpConnectionStatus.FAILED;
    }
}

/** 传输工厂 — 根据配置类型创建对应传输实例 */
private static McpTransport createTransport(McpServerConfig config) {
    return switch (config.type()) {
        case STDIO      -> new McpStdioTransport(config);
        case SSE, SSE_IDE -> createSseTransport(config);
        case HTTP        -> new McpStreamableHttpTransport(config.url());
        case WS, WS_IDE  -> new McpWebSocketTransport(
                config.url().replace("http://", "ws://").replace("https://", "wss://"));
        default -> {
            // SDK, CLAUDEAI_PROXY 等尚未实现的传输类型 — 容错处理
            log.warn("Unsupported transport type '{}' for MCP server '{}', no transport created",
                    config.type(), config.name());
            yield null;
        }
    };
}

/** SSE 传输创建 — 处理 URL 格式 + Headers 注入 */
private static McpSseTransport createSseTransport(McpServerConfig config) {
    // McpSseTransport 构造需要 baseUrl (不含 /sse)，它会自动追加 /sse
    String baseUrl = config.url().endsWith("/sse")
            ? config.url().substring(0, config.url().length() - 4)
            : config.url();
    // 注入自定义 headers (Authorization 等)
    okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ZERO)
            .writeTimeout(java.time.Duration.ofSeconds(10));
    if (config.headers() != null && !config.headers().isEmpty()) {
        builder.addInterceptor(chain -> {
            okhttp3.Request.Builder req = chain.request().newBuilder();
            config.headers().forEach(req::header);
            return chain.proceed(req.build());
        });
    }
    return new McpSseTransport(baseUrl, builder.build());
}
```

**变更点 3**: 新增传输无关的 `callTool()` 方法

```java
/**
 * ★ 传输无关的工具调用 — 委托给底层 McpTransport。
 * SSE/HTTP/WS/STDIO 全部通过同一代码路径执行。
 *
 * @param toolName   MCP 工具原始名称
 * @param arguments  工具参数
 * @param timeoutMs  超时 (ms), ≤0 使用默认值
 * @return JSON-RPC result 字段
 * @throws McpProtocolException 通信或协议错误
 */
public JsonNode callTool(String toolName, Map<String, Object> arguments, long timeoutMs)
        throws McpProtocolException {
    if (status != McpConnectionStatus.CONNECTED || transport == null) {
        throw new McpProtocolException(new JsonRpcError(
                JsonRpcError.SERVER_NOT_INITIALIZED,
                "Server '" + config.name() + "' not connected (status: " + status + ")"));
    }
    return transport.sendRequest("tools/call",
            Map.of("name", toolName, "arguments", arguments),
            timeoutMs > 0 ? timeoutMs : DEFAULT_REQUEST_TIMEOUT_MS);
}
```

**变更点 4**: `close()` 和 `isAlive()` 简化

```java
// ===== 原始 close() — 每种传输单独清理 (L151-L180, 30行) =====
// ===== 修改后 (6行) =====
public void close() {
    this.status = McpConnectionStatus.DISABLED;
    this.tools = List.of();
    this.resources = List.of();
    if (transport != null) {
        try { transport.close(); } catch (Exception ignored) {}
    }
}

// ===== 原始 isAlive() — switch 分支 (L332-L340) =====
// ===== 修改后 (1行) =====
public boolean isAlive() {
    return transport != null && transport.isConnected();
}
```

**变更点 5**: 删除已移出的方法

```
删除以下方法（已移入 McpStdioTransport 或由 McpTransport 替代）:
- connectStdio()          (L85-L109)   → McpStdioTransport.connect()
- connectHttp()           (L112-L128)  → McpStreamableHttpTransport.connect()
- connectWebSocket()      (L131-L148)  → McpWebSocketTransport.connect()
- sendRequest(String)     (L185-L191)  → McpStdioTransport.sendRequest()
- readResponse()          (L196-L201)  → McpStdioTransport 内部
- readResponseWithTimeout() (L273-L287) → McpStdioTransport 内部
- sendRequestAndWait()    (L213-L248)  → transport.sendRequest()

★ P-14 修复: 迁移以下 sendRequestAndWait() 内部调用者 → 改用新增的 request() 方法:

  - McpServerConnection.listPrompts() (L399):
      sendRequestAndWait("prompts/list", null)
      → request("prompts/list", null)

  - McpPromptAdapter.execute() (L74):
      connection.sendRequestAndWait("prompts/get", params)
      → connection.request("prompts/get", params)

保留以下方法（改为委托到 transport）:
    public void sendNotification(String method, Object params) {
        if (transport != null) { transport.sendNotification(method, params); }
    }

★ 保留 onToolsChanged() 回调机制（P-10）:
    // onToolsChanged(Runnable) 及其内部 toolsChangedCallback 字段原样保留
    // McpClientManager.registerToolsFromConnection() 依赖此回调
```

**变更点 6**: ★ 新增通用 `request()` 方法（P-11，替代 sendRequestAndWait）

```java
/**
 * ★ P-11: 通用 JSON-RPC 请求 — 替代已删除的 sendRequestAndWait()。
 * 供内部调用 tools/list、resources/list 等 MCP 协议方法。
 */
public JsonNode request(String method, Object params, long timeoutMs) throws McpProtocolException {
    if (transport == null) {
        throw new McpProtocolException(new JsonRpcError(
                JsonRpcError.SERVER_NOT_INITIALIZED,
                "Server '" + config.name() + "' has no transport"));
    }
    return transport.sendRequest(method, params,
            timeoutMs > 0 ? timeoutMs : DEFAULT_REQUEST_TIMEOUT_MS);
}

/** 通用请求 — 使用默认超时 */
public JsonNode request(String method, Object params) throws McpProtocolException {
    return request(method, params, DEFAULT_REQUEST_TIMEOUT_MS);
}
```

**重构影响评估**: 文件从 **434 行** 瘦身到 **~200 行**

---

### Task 4: 后端 — McpCapabilityDefinition 数据模型

**文件**: `backend/src/main/java/com/aicodeassistant/mcp/McpCapabilityDefinition.java` (新增)

**完整代码**:

```java
package com.aicodeassistant.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * MCP 能力定义 — 对应 mcp_capability_registry.json 中的单条工具定义。
 * <p>
 * 提供工具完整元数据: SSE 端点、API Key、超时、描述、输入输出 Schema 等。
 *
 * @see McpCapabilityRegistryService
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpCapabilityDefinition(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("toolName") String toolName,
        @JsonProperty("sseUrl") String sseUrl,
        @JsonProperty("apiKeyConfig") String apiKeyConfig,
        @JsonProperty("apiKeyDefault") String apiKeyDefault,
        @JsonProperty("domain") String domain,
        @JsonProperty("category") String category,
        @JsonProperty("briefDescription") String briefDescription,
        @JsonProperty("videoCallSummary") String videoCallSummary,
        @JsonProperty("description") String description,
        @JsonProperty("input") Map<String, Object> input,
        @JsonProperty("output") Map<String, Object> output,
        @JsonProperty("timeoutMs") int timeoutMs,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("videoCallEnabled") boolean videoCallEnabled
) {
    /** 快捷构造: 切换 enabled 状态 */
    public McpCapabilityDefinition withEnabled(boolean newEnabled) {
        return new McpCapabilityDefinition(
                id, name, toolName, sseUrl, apiKeyConfig, apiKeyDefault,
                domain, category, briefDescription, videoCallSummary, description,
                input, output, timeoutMs, newEnabled, videoCallEnabled);
    }

    /** 从 sseUrl 提取服务器 key (URL path 倒数第二段) */
    public String extractServerKey() {
        if (sseUrl == null) return id;
        try {
            String path = java.net.URI.create(sseUrl).getPath();
            String[] segments = java.util.Arrays.stream(path.split("/"))
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
            return segments.length >= 2 ? segments[segments.length - 2] : id;
        } catch (Exception e) {
            return id;
        }
    }
}
```

---

### Task 5: 后端 — McpCapabilityRegistryService 服务层

**文件**: `backend/src/main/java/com/aicodeassistant/mcp/McpCapabilityRegistryService.java` (新增)

**与 v1.1 的关键差异**:
- **★ P8 修复**: `@Value` 注入 → `McpConfiguration` 构造注入，配置集中化

**完整代码**:

```java
package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 能力注册表服务 — 加载、查询、修改、持久化 mcp_capability_registry.json。
 *
 * @see McpCapabilityDefinition
 * @see McpCapabilityController
 */
@Service
public class McpCapabilityRegistryService {

    private static final Logger log = LoggerFactory.getLogger(McpCapabilityRegistryService.class);

    private final ObjectMapper objectMapper;
    private final Map<String, McpCapabilityDefinition> capabilities = new ConcurrentHashMap<>();

    /** ★ P8 修复: 通过 McpConfiguration 构造注入，而非 @Value 字段注入 */
    private final String registryPath;

    public McpCapabilityRegistryService(ObjectMapper objectMapper, McpConfiguration mcpConfiguration) {
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.registryPath = mcpConfiguration.getCapabilityRegistryPath();
    }

    @PostConstruct
    public void loadRegistry() {
        Path path = Path.of(registryPath);
        if (!Files.exists(path)) {
            log.warn("MCP capability registry not found: {}", registryPath);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            JsonNode mcpTools = root.get("mcp_tools");
            if (mcpTools == null || !mcpTools.isArray()) {
                log.warn("MCP capability registry has no 'mcp_tools' array");
                return;
            }
            int loaded = 0;
            for (JsonNode node : mcpTools) {
                try {
                    McpCapabilityDefinition def = objectMapper.treeToValue(
                            node, McpCapabilityDefinition.class);
                    if (def.id() != null) {
                        capabilities.put(def.id(), def);
                        loaded++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse MCP capability: {}", e.getMessage());
                }
            }
            log.info("MCP capability registry loaded: {} tools from {}", loaded, registryPath);
        } catch (IOException e) {
            log.error("Failed to load MCP capability registry: {}", e.getMessage(), e);
        }
    }

    // ===== 查询接口 =====

    public List<McpCapabilityDefinition> listAll() { return List.copyOf(capabilities.values()); }

    public List<McpCapabilityDefinition> listByDomain(String domain) {
        return capabilities.values().stream().filter(d -> domain.equals(d.domain())).toList();
    }

    public List<McpCapabilityDefinition> listEnabled() {
        return capabilities.values().stream().filter(McpCapabilityDefinition::enabled).toList();
    }

    public Optional<McpCapabilityDefinition> findById(String id) {
        return Optional.ofNullable(capabilities.get(id));
    }

    public Optional<McpCapabilityDefinition> findByToolName(String serverKey, String toolName) {
        return capabilities.values().stream()
                .filter(cap -> toolName.equals(cap.toolName()) && serverKey.equals(cap.extractServerKey()))
                .findFirst();
    }

    public List<String> listDomains() {
        return capabilities.values().stream()
                .map(McpCapabilityDefinition::domain).filter(Objects::nonNull)
                .distinct().sorted().toList();
    }

    public int size() { return capabilities.size(); }

    public long enabledCount() {
        return capabilities.values().stream().filter(McpCapabilityDefinition::enabled).count();
    }

    // ===== 修改接口 =====

    public McpCapabilityDefinition toggleEnabled(String id, boolean enabled) {
        McpCapabilityDefinition existing = capabilities.get(id);
        if (existing == null) throw new IllegalArgumentException("MCP capability not found: " + id);
        McpCapabilityDefinition updated = existing.withEnabled(enabled);
        capabilities.put(id, updated);
        saveToFileAsync();
        log.info("MCP capability '{}' toggled to enabled={}", id, enabled);
        return updated;
    }

    public McpCapabilityDefinition updateCapability(String id, McpCapabilityDefinition updated) {
        if (!capabilities.containsKey(id)) throw new IllegalArgumentException("Not found: " + id);
        capabilities.put(id, updated);
        saveToFileAsync();
        return updated;
    }

    public McpCapabilityDefinition addCapability(McpCapabilityDefinition def) {
        if (capabilities.containsKey(def.id()))
            throw new IllegalArgumentException("Already exists: " + def.id());
        capabilities.put(def.id(), def);
        saveToFileAsync();
        return def;
    }

    public boolean deleteCapability(String id) {
        McpCapabilityDefinition removed = capabilities.remove(id);
        if (removed != null) { saveToFileAsync(); return true; }
        return false;
    }

    // ===== 持久化 (防抖 + 互斥) =====

    private final java.util.concurrent.locks.ReentrantLock saveLock =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.ScheduledExecutorService saveScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    /** ★ P-4 修复: AtomicReference 确保 cancel + schedule 的原子性 */
    private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>
            pendingSave = new java.util.concurrent.atomic.AtomicReference<>();

    private void saveToFileAsync() {
        java.util.concurrent.ScheduledFuture<?> prev = pendingSave.getAndSet(
                saveScheduler.schedule(this::saveToFile, 500,
                        java.util.concurrent.TimeUnit.MILLISECONDS));
        if (prev != null) prev.cancel(false);
    }

    public void saveToFile() {
        Path path = Path.of(registryPath);
        saveLock.lock();
        try {
            ObjectNode root;
            if (Files.exists(path)) {
                root = (ObjectNode) objectMapper.readTree(path.toFile());
            } else {
                root = objectMapper.createObjectNode();
                root.put("_schema_version", "1.0");
            }
            ArrayNode toolsArray = objectMapper.createArrayNode();
            capabilities.values().stream()
                    .sorted(Comparator.comparing(McpCapabilityDefinition::id))
                    .forEach(def -> toolsArray.add(objectMapper.valueToTree(def)));
            root.set("mcp_tools", toolsArray);
            root.put("lastUpdated", java.time.LocalDate.now().toString());
            objectMapper.writeValue(path.toFile(), root);
            log.debug("MCP capability registry saved to {}", registryPath);
        } catch (IOException e) {
            log.error("Failed to save MCP capability registry: {}", e.getMessage(), e);
        } finally {
            saveLock.unlock();
        }
    }
}
```

---

### Task 6: 后端 — McpConfiguration + application.yml 配置更新

**文件 A**: `backend/src/main/java/com/aicodeassistant/mcp/McpConfiguration.java` (修改)

**变更**: 在 `channelPermissions` 字段后新增 `capabilityRegistryPath`

```java
// ===== 在 McpConfiguration.java L43 后新增 =====

/** ★ P8 修复: MCP 能力注册表文件路径 — 集中到 @ConfigurationProperties */
private String capabilityRegistryPath = "configuration/mcp/mcp_capability_registry.json";

public String getCapabilityRegistryPath() {
    return capabilityRegistryPath;
}

public void setCapabilityRegistryPath(String capabilityRegistryPath) {
    this.capabilityRegistryPath = capabilityRegistryPath;
}
```

**P8 修复原理**:
- Spring Boot 松散绑定自动将 YAML `capability-registry-path` → Java `capabilityRegistryPath`
- 默认值只在 Java 字段初始化中定义一处，不在 YAML 和 `@Value` 之间重复
- 所有 MCP 配置统一由 `McpConfiguration` 管理，Spring Actuator `/configprops` 端点可发现
- `McpCapabilityRegistryService` 通过构造注入 `McpConfiguration`，而非 `@Value` 字段注入

**文件 B**: `backend/src/main/resources/application.yml` (修改)

```yaml
# ===== 原始 (L62-L71) =====
mcp:
  servers:
    zhipu-websearch:
      type: SSE
      url: https://dashscope.aliyuncs.com/api/v1/mcps/zhipu-websearch/sse
      headers:
        Authorization: Bearer ${LLM_API_KEY:}
      scope: USER

# ===== 修改后 =====
mcp:
  capability-registry-path: configuration/mcp/mcp_capability_registry.json  # ★ 新增
  servers:
    zhipu-websearch:
      type: SSE
      url: https://dashscope.aliyuncs.com/api/v1/mcps/zhipu-websearch/sse
      headers:
        Authorization: Bearer ${LLM_API_KEY:}
      scope: USER
```

---

### Task 7: 后端 — McpToolAdapter 简化重构

**文件**: `backend/src/main/java/com/aicodeassistant/mcp/McpToolAdapter.java` (修改)

**核心变更**: `call()` 方法从 70 行 STDIO 硬编码 → 40 行传输无关代码

**变更点 1**: 新增字段 + 增强构造函数

```java
// ===== 新增字段 =====
private final String enhancedDescription;  // ★ 注册表中文描述
private final long timeoutMs;              // ★ 注册表超时配置

/** 原有构造函数 — 保持向后兼容 */
public McpToolAdapter(String name, String description, Map<String, Object> inputSchema,
                      McpServerConnection connection, String originalToolName) {
    this(name, description, inputSchema, connection, originalToolName, null, 0);
}

/** ★ 增强构造函数 — 支持描述覆盖和超时覆盖 */
public McpToolAdapter(String name, String description, Map<String, Object> inputSchema,
                      McpServerConnection connection, String originalToolName,
                      String enhancedDescription, long timeoutMs) {
    this.name = name;
    this.description = description;
    this.inputSchema = inputSchema;
    this.connection = connection;
    this.originalToolName = originalToolName;
    this.enhancedDescription = enhancedDescription;
    this.timeoutMs = timeoutMs;
}
```

**变更点 2**: `getDescription()` 优先返回增强描述

```java
@Override
public String getDescription() {
    if (enhancedDescription != null && !enhancedDescription.isEmpty()) {
        return enhancedDescription;
    }
    return description != null ? description : "MCP tool: " + originalToolName;
}
```

**变更点 3**: ★ `call()` 方法根本性简化

```java
// ===== v1.1 方案 (仍依赖 sendRequest/readResponse + CompletableFuture.orTimeout 包装) =====
// 问题: 对 SSE 连接 stdinWriter == null → IOException

// ===== v2.0 方案 (传输无关，P7 从根本上消除) =====
@Override
public ToolResult call(ToolInput input, ToolUseContext context) {
    if (connection.getStatus() != McpConnectionStatus.CONNECTED) {
        return ToolResult.error("MCP server '" + connection.getName()
                + "' is not connected (status: " + connection.getStatus() + ")");
    }

    try {
        // ★ 一行代码，传输无关 — SSE/HTTP/WS/STDIO 全部走同一路径
        JsonNode result = connection.callTool(originalToolName, input.getRawData(), timeoutMs);

        // 解析 MCP 标准 content 数组
        if (result != null && result.has("content")) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : result.get("content")) {
                if ("text".equals(item.path("type").asText())) {
                    sb.append(item.get("text").asText());
                }
            }
            String content = sb.toString();
            if (content.length() > MAX_MCP_RESULT_SIZE) {
                content = content.substring(0, MAX_MCP_RESULT_SIZE)
                        + "\n[Truncated: exceeded " + MAX_MCP_RESULT_SIZE + " chars]";
            }
            return ToolResult.success(content)
                    .withMetadata("mcpServer", connection.getName())
                    .withMetadata("mcpTool", originalToolName);
        }

        String fallback = result != null ? result.toString() : "{}";
        return ToolResult.success(fallback)
                .withMetadata("mcpServer", connection.getName())
                .withMetadata("mcpTool", originalToolName);

    } catch (McpProtocolException e) {
        if (e.getCode() == JsonRpcError.REQUEST_TIMEOUT) {
            log.warn("MCP tool call timed out after {}ms: {} on {}",
                    timeoutMs, originalToolName, connection.getName());
            return ToolResult.error("MCP tool call timed out after " + timeoutMs + "ms");
        }
        log.error("MCP tool call failed: {} on {}", originalToolName, connection.getName(), e);
        return ToolResult.error("MCP error: " + e.getMessage());
    } catch (Exception e) {
        log.error("MCP tool call failed: {} on {}", originalToolName, connection.getName(), e);
        return ToolResult.error("MCP tool call failed: " + e.getMessage());
    }
}
```

**v1.1 vs v2.0 关键对比**:

| 维度 | v1.1 方案 | v2.0 方案 |
|------|----------|----------|
| 工具调用 | `sendRequest()` + `readResponse()` + `CompletableFuture.orTimeout()` | `connection.callTool()` 一行 |
| SSE 支持 | ❌ stdinWriter == null → IOException | ✅ 自动路由到 McpSseTransport |
| 超时机制 | 外部 CompletableFuture 包装 | 各传输层内部原生超时 |
| 传输感知 | 间接感知 | **完全无感知** |
| 代码行数 | ~70 行 | ~40 行 |

---

### Task 8: 后端 — McpClientManager 集成注册表

**文件**: `backend/src/main/java/com/aicodeassistant/mcp/McpClientManager.java` (修改)

**变更点 1**: 新增依赖注入

```java
// ===== 原始代码 (L42-L57) =====
private final McpConfiguration mcpConfiguration;
private final McpConfigurationResolver configurationResolver;
private final ToolRegistry toolRegistry;
private final McpApprovalService approvalService;
private final Map<String, McpServerConnection> connections = new ConcurrentHashMap<>();

public McpClientManager(McpConfiguration mcpConfiguration,
                        McpConfigurationResolver configurationResolver,
                        @Lazy ToolRegistry toolRegistry,
                        McpApprovalService approvalService) { ... }

// ===== 修改后 =====
private final McpConfiguration mcpConfiguration;
private final McpConfigurationResolver configurationResolver;
private final ToolRegistry toolRegistry;
private final McpApprovalService approvalService;
private final McpCapabilityRegistryService registryService;       // ★ 新增
private final org.springframework.core.env.Environment environment; // ★ 新增: 解析 API Key
private final Map<String, McpServerConnection> connections = new ConcurrentHashMap<>();

public McpClientManager(McpConfiguration mcpConfiguration,
                        McpConfigurationResolver configurationResolver,
                        @Lazy ToolRegistry toolRegistry,
                        McpApprovalService approvalService,
                        @Lazy McpCapabilityRegistryService registryService,
                        org.springframework.core.env.Environment environment) {
    this.mcpConfiguration = mcpConfiguration;
    this.configurationResolver = configurationResolver;
    this.toolRegistry = toolRegistry;
    this.approvalService = approvalService;
    this.registryService = registryService;
    this.environment = environment;
}
```

**变更点 2**: `initializeAll()` 追加注册表自动激活

```java
// 在现有 initializeAll() 末尾追加:

// 3. ★ 从能力注册表加载已启用的工具定义，自动创建连接
if (registryService != null && registryService.size() > 0) {
    List<McpCapabilityDefinition> enabledCaps = registryService.listEnabled();
    int registryConnections = 0;
    for (McpCapabilityDefinition cap : enabledCaps) {
        String serverKey = cap.extractServerKey();
        if (!connections.containsKey(serverKey)) {
            try {
                enableFromRegistry(cap);
                registryConnections++;
            } catch (Exception e) {
                log.warn("Failed to enable registry capability '{}': {}", cap.id(), e.getMessage());
            }
        }
    }
    log.info("MCP registry: activated {} capabilities from {} enabled entries",
            registryConnections, enabledCaps.size());
}
```

**变更点 3**: 新增 `enableFromRegistry()` 方法

```java
/**
 * ★ 从能力注册表启用工具 — 自动构建 McpServerConfig 并创建连接。
 * <p>
 * v2.0: McpServerConnection.connect() 内部通过 createTransport() 工厂方法
 * 自动创建 McpSseTransport 实例（含 Headers 注入），P7 从根本上消除。
 */
public McpServerConnection enableFromRegistry(McpCapabilityDefinition def) {
    McpServerConfig config = buildConfigFromRegistry(def);  // ★ 复用公共方法

    log.info("Enabling MCP capability '{}' → server '{}'", def.id(), config.name());

    // ★ P-5 修复: 注册表工具自动信任 — 跳过交互式审批
    if (!approvalService.isTrusted(config)) {
        approvalService.recordApproval(config, "REGISTRY");
        log.info("Auto-trusted registry capability: {}", def.id());
    }

    return addServer(config);
}

/** ★ P-9 新增: 从注册表定义构建 McpServerConfig — 供 enableFromRegistry 和 testCapability 共用 */
public McpServerConfig buildConfigFromRegistry(McpCapabilityDefinition def) {
    String serverKey = def.extractServerKey();
    String apiKey = null;
    if (def.apiKeyConfig() != null) {
        apiKey = environment.getProperty(def.apiKeyConfig());
    }
    if (apiKey == null || apiKey.isEmpty()) {
        apiKey = def.apiKeyDefault();
    }
    Map<String, String> headers = new LinkedHashMap<>();
    if (apiKey != null && !apiKey.isEmpty()) {
        headers.put("Authorization", "Bearer " + apiKey);
    }
    return new McpServerConfig(
            serverKey, McpTransportType.SSE,
            null, List.of(), Map.of(),
            def.sseUrl(), headers, McpConfigScope.DYNAMIC);
}
```

**变更点 4**: `registerToolsFromConnection()` 增强描述和超时

```java
// ===== 原始代码 (L313-L316) =====
McpToolAdapter adapter = new McpToolAdapter(
        "mcp__" + conn.getName() + "__" + mcpTool.name(),
        mcpTool.description(), mcpTool.inputSchema(),
        conn, mcpTool.name());

// ===== 修改后 =====
String enhancedDesc = null;
long customTimeout = 0;
if (registryService != null) {
    var capOpt = registryService.findByToolName(conn.getName(), mcpTool.name());
    if (capOpt.isPresent()) {
        McpCapabilityDefinition cap = capOpt.get();
        enhancedDesc = cap.description();
        customTimeout = cap.timeoutMs();
    }
}
McpToolAdapter adapter = new McpToolAdapter(
        "mcp__" + conn.getName() + "__" + mcpTool.name(),
        mcpTool.description(), mcpTool.inputSchema(),
        conn, mcpTool.name(),
        enhancedDesc, customTimeout);
```

**变更点 5**: 同步修改 `wrapMcpTools()`

```java
private List<Tool> wrapMcpTools(McpServerConnection connection) {
    return connection.getTools().stream()
            .map(mcpTool -> {
                String enhancedDesc = null;
                long customTimeout = 0;
                if (registryService != null) {
                    var capOpt = registryService.findByToolName(
                            connection.getName(), mcpTool.name());
                    if (capOpt.isPresent()) {
                        enhancedDesc = capOpt.get().description();
                        customTimeout = capOpt.get().timeoutMs();
                    }
                }
                return (Tool) new McpToolAdapter(
                        "mcp__" + connection.getName() + "__" + mcpTool.name(),
                        mcpTool.description(), mcpTool.inputSchema(),
                        connection, mcpTool.name(),
                        enhancedDesc, customTimeout);
            })
            .toList();
}
```

---

### Task 9: 后端 — McpCapabilityController REST API

**文件**: `backend/src/main/java/com/aicodeassistant/controller/McpCapabilityController.java` (新增)

**完整代码**:

```java
package com.aicodeassistant.controller;

import com.aicodeassistant.mcp.McpCapabilityDefinition;
import com.aicodeassistant.mcp.McpCapabilityRegistryService;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.mcp.McpConnectionStatus;
import com.aicodeassistant.mcp.McpServerConfig;          // ★ P-12 修复: testCapability 需要此类型
import com.aicodeassistant.mcp.McpServerConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 能力注册表管理 Controller — CRUD + 启用/禁用 + 测试。
 * 路径: /api/mcp/capabilities
 */
@RestController
@RequestMapping("/api/mcp/capabilities")
public class McpCapabilityController {

    private final McpCapabilityRegistryService registryService;
    private final McpClientManager mcpManager;

    public McpCapabilityController(McpCapabilityRegistryService registryService,
                                   McpClientManager mcpManager) {
        this.registryService = registryService;
        this.mcpManager = mcpManager;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listCapabilities(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) Boolean enabled) {
        List<McpCapabilityDefinition> result;
        if (domain != null && !domain.isEmpty()) {
            result = registryService.listByDomain(domain);
        } else if (Boolean.TRUE.equals(enabled)) {
            result = registryService.listEnabled();
        } else {
            result = registryService.listAll();
        }
        return ResponseEntity.ok(Map.of(
                "capabilities", result,
                "total", registryService.size(),
                "enabledCount", registryService.enabledCount()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<McpCapabilityDefinition> getCapability(@PathVariable String id) {
        return registryService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<McpCapabilityDefinition> updateCapability(
            @PathVariable String id, @RequestBody McpCapabilityDefinition updated) {
        try {
            return ResponseEntity.ok(registryService.updateCapability(id, updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggleCapability(
            @PathVariable String id, @RequestParam boolean enabled) {
        try {
            McpCapabilityDefinition def = registryService.toggleEnabled(id, enabled);
            String status;
            if (enabled) {
                McpServerConnection conn = mcpManager.enableFromRegistry(def);
                status = conn.getStatus() == McpConnectionStatus.CONNECTED
                        ? "connected" : conn.getStatus().name().toLowerCase();
            } else {
                // ★ P-8 修复: 同服务器多工具保护 — 仅当无其他启用工具时才移除服务器
                String serverKey = def.extractServerKey();
                boolean otherEnabled = registryService.listEnabled().stream()
                        .anyMatch(c -> !c.id().equals(id) && serverKey.equals(c.extractServerKey()));
                if (!otherEnabled) {
                    mcpManager.removeServer(serverKey);
                }
                status = "disabled";
            }
            return ResponseEntity.ok(Map.of("id", id, "enabled", enabled, "status", status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<McpCapabilityDefinition> addCapability(
            @RequestBody McpCapabilityDefinition def) {
        try {
            return ResponseEntity.status(201).body(registryService.addCapability(def));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Boolean>> deleteCapability(@PathVariable String id) {
        boolean removed = registryService.deleteCapability(id);
        return removed ? ResponseEntity.ok(Map.of("success", true))
                       : ResponseEntity.notFound().build();
    }

    @GetMapping("/domains")
    public ResponseEntity<Map<String, List<String>>> listDomains() {
        return ResponseEntity.ok(Map.of("domains", registryService.listDomains()));
    }

    /** ★ P-9 修复: 使用临时连接测试，不产生持久副作用 */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testCapability(@PathVariable String id) {
        return registryService.findById(id).map(def -> {
            McpServerConnection tempConn = null;
            try {
                McpServerConfig config = mcpManager.buildConfigFromRegistry(def);
                tempConn = new McpServerConnection(config);
                tempConn.connect();
                boolean alive = tempConn.isAlive();
                return ResponseEntity.ok(Map.<String, Object>of(
                        "id", id,
                        "status", alive ? "reachable" : "unreachable",
                        "serverKey", def.extractServerKey()));
            } catch (Exception e) {
                return ResponseEntity.ok(Map.<String, Object>of(
                        "id", id, "status", "error", "error", e.getMessage()));
            } finally {
                if (tempConn != null) tempConn.close();
            }
        }).orElse(ResponseEntity.notFound().build());
    }
}
```

**API 端点汇总**:

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/mcp/capabilities` | 列出所有（支持 `?domain=` `?enabled=` 过滤） |
| `GET` | `/api/mcp/capabilities/{id}` | 获取单个详情 |
| `PUT` | `/api/mcp/capabilities/{id}` | 更新配置 |
| `PATCH` | `/api/mcp/capabilities/{id}/toggle?enabled=true/false` | 启用/禁用 |
| `POST` | `/api/mcp/capabilities` | 新增定义 |
| `DELETE` | `/api/mcp/capabilities/{id}` | 删除定义 |
| `GET` | `/api/mcp/capabilities/domains` | 获取 domain 列表 |
| `POST` | `/api/mcp/capabilities/{id}/test` | 测试连通性 |

---

### Task 10: 前端 — mcpCapabilityStore.ts 状态管理

**文件**: `frontend/src/store/mcpCapabilityStore.ts` (新增)

**完整代码**:

```typescript
import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { subscribeWithSelector } from 'zustand/middleware';

export interface McpCapabilityDefinition {
  id: string;
  name: string;
  toolName: string;
  sseUrl: string;
  apiKeyConfig: string;
  apiKeyDefault?: string;
  domain: string;
  category: string;
  briefDescription: string;
  videoCallSummary?: string;
  description: string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  timeoutMs: number;
  enabled: boolean;
  videoCallEnabled: boolean;
}

export interface McpCapabilityStoreState {
  capabilities: McpCapabilityDefinition[];
  domains: string[];
  activeDomain: string | null;
  loading: boolean;
  total: number;
  enabledCount: number;
  testResults: Record<string, { status: string; error?: string }>;

  loadCapabilities: (domain?: string) => Promise<void>;
  loadDomains: () => Promise<void>;
  setActiveDomain: (domain: string | null) => void;
  toggleCapability: (id: string, enabled: boolean) => Promise<{ status: string }>;
  updateCapability: (id: string, data: McpCapabilityDefinition) => Promise<void>;
  addCapability: (data: McpCapabilityDefinition) => Promise<void>;
  deleteCapability: (id: string) => Promise<void>;
  testCapability: (id: string) => Promise<{ status: string; error?: string }>;
}

export const useMcpCapabilityStore = create<McpCapabilityStoreState>()(
  subscribeWithSelector(immer((set, get) => ({
    capabilities: [],
    domains: [],
    activeDomain: null,
    loading: false,
    total: 0,
    enabledCount: 0,
    testResults: {},

    loadCapabilities: async (domain?: string) => {
      set(d => { d.loading = true; });
      try {
        const params = new URLSearchParams();
        if (domain) params.set('domain', domain);
        const resp = await fetch(`/api/mcp/capabilities?${params}`);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const data = await resp.json();
        set(d => {
          d.capabilities = data.capabilities ?? [];
          d.total = data.total ?? 0;
          d.enabledCount = data.enabledCount ?? 0;
          d.loading = false;
        });
      } catch (e) {
        console.error('[McpCapabilityStore] loadCapabilities failed:', e);
        set(d => { d.loading = false; });
      }
    },

    loadDomains: async () => {
      try {
        const resp = await fetch('/api/mcp/capabilities/domains');
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const data = await resp.json();
        set(d => { d.domains = data.domains ?? []; });
      } catch (e) {
        console.error('[McpCapabilityStore] loadDomains failed:', e);
      }
    },

    setActiveDomain: (domain) => {
      set(d => { d.activeDomain = domain; });
      get().loadCapabilities(domain ?? undefined);
    },

    toggleCapability: async (id, enabled) => {
      try {
        const resp = await fetch(
          `/api/mcp/capabilities/${id}/toggle?enabled=${enabled}`,
          { method: 'PATCH' }
        );
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const result = await resp.json();
        set(d => {
          const idx = d.capabilities.findIndex(c => c.id === id);
          if (idx >= 0) d.capabilities[idx].enabled = enabled;
          d.enabledCount = d.capabilities.filter(c => c.enabled).length;
        });
        return { status: result.status };
      } catch (e) {
        console.error('[McpCapabilityStore] toggleCapability failed:', e);
        return { status: 'error' };
      }
    },

    updateCapability: async (id, data) => {
      try {
        const resp = await fetch(`/api/mcp/capabilities/${id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data),
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const updated = await resp.json();
        set(d => {
          const idx = d.capabilities.findIndex(c => c.id === id);
          if (idx >= 0) d.capabilities[idx] = updated;
        });
      } catch (e) {
        console.error('[McpCapabilityStore] updateCapability failed:', e);
      }
    },

    addCapability: async (data) => {
      try {
        const resp = await fetch('/api/mcp/capabilities', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data),
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const created = await resp.json();
        set(d => { d.capabilities.push(created); d.total++; });
      } catch (e) {
        console.error('[McpCapabilityStore] addCapability failed:', e);
      }
    },

    deleteCapability: async (id) => {
      try {
        const resp = await fetch(`/api/mcp/capabilities/${id}`, { method: 'DELETE' });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        set(d => {
          d.capabilities = d.capabilities.filter(c => c.id !== id);
          d.total--;
        });
      } catch (e) {
        console.error('[McpCapabilityStore] deleteCapability failed:', e);
      }
    },

    testCapability: async (id) => {
      try {
        const resp = await fetch(`/api/mcp/capabilities/${id}/test`, { method: 'POST' });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const result = await resp.json();
        set(d => { d.testResults[id] = result; });
        return result;
      } catch (e) {
        const errResult = { status: 'error', error: String(e) };
        set(d => { d.testResults[id] = errResult; });
        return errResult;
      }
    },
  })))
);
```

**设计说明**: 遵循项目 Store 模式 `zustand` + `immer` + `subscribeWithSelector`，使用原生 `fetch` API

---

### Task 11: 前端 — McpCapabilityPanel 组件

**文件**: `frontend/src/components/settings/McpCapabilityPanel.tsx` (新增)

**完整代码**:

```tsx
import { useEffect, useState, useCallback } from 'react';
import { useMcpCapabilityStore, type McpCapabilityDefinition } from '@/store/mcpCapabilityStore';

const DOMAIN_LABELS: Record<string, string> = {
  image_processing: '图像处理',
  web_search: '网络搜索',
  map_navigation: '地图导航',
  document_processing: '文档处理',
  code_analysis: '代码分析',
  data_analysis: '数据分析',
  communication: '通信协作',
  media_processing: '媒体处理',
  knowledge_base: '知识库',
};

export function McpCapabilityPanel() {
  const {
    capabilities, domains, activeDomain, loading,
    total, enabledCount, testResults,
    loadCapabilities, loadDomains, setActiveDomain,
    toggleCapability, testCapability,
  } = useMcpCapabilityStore();

  const [editingId, setEditingId] = useState<string | null>(null);

  useEffect(() => {
    loadCapabilities();
    loadDomains();
  }, [loadCapabilities, loadDomains]);

  const handleToggle = useCallback(async (id: string, currentEnabled: boolean) => {
    await toggleCapability(id, !currentEnabled);
  }, [toggleCapability]);

  const handleTest = useCallback(async (id: string) => {
    await testCapability(id);
  }, [testCapability]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-semibold">MCP 工具管理</h3>
          <p className="text-sm text-gray-500 mt-1">共 {total} 个工具，已启用 {enabledCount} 个</p>
        </div>
      </div>

      <div className="flex flex-wrap gap-2">
        <button
          className={`px-3 py-1 text-xs rounded-full border transition-colors
            ${activeDomain === null
              ? 'bg-blue-500 text-white border-blue-500'
              : 'border-gray-300 dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700'}`}
          onClick={() => setActiveDomain(null)}
        >全部</button>
        {domains.map((d) => (
          <button key={d}
            className={`px-3 py-1 text-xs rounded-full border transition-colors
              ${activeDomain === d
                ? 'bg-blue-500 text-white border-blue-500'
                : 'border-gray-300 dark:border-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700'}`}
            onClick={() => setActiveDomain(d)}
          >{DOMAIN_LABELS[d] ?? d}</button>
        ))}
      </div>

      {loading ? (
        <div className="text-center text-gray-400 py-8">加载中...</div>
      ) : (
        <div className="space-y-3 max-h-[60vh] overflow-y-auto">
          {capabilities.map((cap) => (
            <CapabilityCard key={cap.id} capability={cap} testResult={testResults[cap.id]}
              onToggle={handleToggle} onTest={handleTest} onEdit={() => setEditingId(cap.id)} />
          ))}
          {capabilities.length === 0 && (
            <div className="text-center text-gray-400 py-8">当前分类下无工具</div>
          )}
        </div>
      )}

      {editingId && <EditDialog capabilityId={editingId} onClose={() => setEditingId(null)} />}
    </div>
  );
}

function CapabilityCard({
  capability: cap, testResult, onToggle, onTest, onEdit,
}: {
  capability: McpCapabilityDefinition;
  testResult?: { status: string; error?: string };
  onToggle: (id: string, enabled: boolean) => void;
  onTest: (id: string) => void;
  onEdit: () => void;
}) {
  return (
    <div className={`p-4 rounded-lg border transition-colors
      ${cap.enabled
        ? 'border-blue-200 bg-blue-50/50 dark:border-blue-800 dark:bg-blue-900/10'
        : 'border-gray-200 dark:border-gray-700'}`}>
      <div className="flex items-start justify-between">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-medium text-sm">{cap.name}</span>
            <span className="text-xs px-2 py-0.5 rounded bg-gray-100 dark:bg-gray-700 text-gray-500">
              {DOMAIN_LABELS[cap.domain] ?? cap.domain}
            </span>
          </div>
          <p className="text-xs text-gray-500 mt-1 line-clamp-2">{cap.briefDescription}</p>
          <div className="flex items-center gap-3 mt-2 text-xs text-gray-400">
            <span>超时: {(cap.timeoutMs / 1000).toFixed(0)}s</span>
            <span>SSE</span>
            <span className="font-mono">{cap.toolName}</span>
          </div>
        </div>
        <button onClick={() => onToggle(cap.id, cap.enabled)}
          className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ml-4 flex-shrink-0
            ${cap.enabled ? 'bg-blue-500' : 'bg-gray-300 dark:bg-gray-600'}`}>
          <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform
            ${cap.enabled ? 'translate-x-6' : 'translate-x-1'}`} />
        </button>
      </div>
      <div className="flex items-center gap-2 mt-3">
        <button onClick={onEdit}
          className="text-xs px-2 py-1 rounded border border-gray-300 dark:border-gray-600
                     hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors">编辑</button>
        <button onClick={() => onTest(cap.id)}
          className="text-xs px-2 py-1 rounded border border-gray-300 dark:border-gray-600
                     hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors">测试</button>
        {testResult && (
          <span className={`text-xs ${testResult.status === 'reachable' ? 'text-green-500' : 'text-red-500'}`}>
            {testResult.status === 'reachable' ? '可达' : testResult.status}
          </span>
        )}
      </div>
    </div>
  );
}

function EditDialog({ capabilityId, onClose }: { capabilityId: string; onClose: () => void }) {
  const { capabilities, updateCapability } = useMcpCapabilityStore();
  const cap = capabilities.find(c => c.id === capabilityId);
  const [formData, setFormData] = useState<McpCapabilityDefinition | null>(cap ?? null);
  if (!formData) return null;

  const handleSave = async () => { await updateCapability(capabilityId, formData); onClose(); };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div className="bg-white dark:bg-gray-800 rounded-lg shadow-xl w-full max-w-lg max-h-[80vh] overflow-y-auto p-6"
        onClick={(e) => e.stopPropagation()}>
        <h3 className="text-lg font-semibold mb-4">编辑 MCP 工具</h3>
        <div className="space-y-3">
          <Field label="名称" value={formData.name}
            onChange={(v) => setFormData({ ...formData, name: v })} />
          <Field label="描述" value={formData.description} multiline
            onChange={(v) => setFormData({ ...formData, description: v })} />
          <Field label="简要描述" value={formData.briefDescription}
            onChange={(v) => setFormData({ ...formData, briefDescription: v })} />
          <Field label="SSE URL" value={formData.sseUrl}
            onChange={(v) => setFormData({ ...formData, sseUrl: v })} />
          <Field label="超时 (ms)" value={String(formData.timeoutMs)}
            onChange={(v) => setFormData({ ...formData, timeoutMs: parseInt(v) || 30000 })} />
          <Field label="API Key Config" value={formData.apiKeyConfig}
            onChange={(v) => setFormData({ ...formData, apiKeyConfig: v })} />
        </div>
        <div className="flex justify-end gap-2 mt-6">
          <button onClick={onClose}
            className="px-4 py-2 text-sm rounded border border-gray-300 dark:border-gray-600">取消</button>
          <button onClick={handleSave}
            className="px-4 py-2 text-sm rounded bg-blue-500 text-white hover:bg-blue-600">保存</button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, value, onChange, multiline }: {
  label: string; value: string; onChange: (v: string) => void; multiline?: boolean;
}) {
  return (
    <div>
      <label className="block text-xs font-medium text-gray-500 mb-1">{label}</label>
      {multiline ? (
        <textarea value={value} onChange={(e) => onChange(e.target.value)}
          className="w-full p-2 text-sm border rounded dark:bg-gray-700 dark:border-gray-600 h-20" />
      ) : (
        <input type="text" value={value} onChange={(e) => onChange(e.target.value)}
          className="w-full p-2 text-sm border rounded dark:bg-gray-700 dark:border-gray-600" />
      )}
    </div>
  );
}
```

---

### Task 12: 前端 — SettingsPanel + API 导出更新

**文件 A**: `frontend/src/components/settings/SettingsPanel.tsx` (修改)

```typescript
// ===== SettingsTab 类型新增 'mcp' =====
type SettingsTab = 'model' | 'theme' | 'permission' | 'memory' | 'keybindings' | 'mcp';

// ===== TABS 数组新增 =====
{ id: 'mcp', label: 'MCP Tools', icon: '🔌' },

// ===== 文件头部新增 import =====
import { McpCapabilityPanel } from './McpCapabilityPanel';

// ===== Tab 内容区新增 =====
{activeTab === 'mcp' && <McpCapabilityPanel />}
```

**文件 B**: `frontend/src/api/index.ts` (修改)

```typescript
// 文件末尾追加导出
export { useMcpCapabilityStore } from '@/store/mcpCapabilityStore';
```

---

## 四、文件变更清单

| 操作 | 文件路径 | 行数估算 |
|------|----------|--------|
| **新增** | `mcp/McpTransport.java` | ~45 行 |
| **新增** | `mcp/McpStdioTransport.java` | ~130 行 |
| **修改** | `mcp/McpSseTransport.java` | +1 行 (implements) |
| **修改** | `mcp/McpStreamableHttpTransport.java` | +10 行 (implements + connect 拆分) |
| **修改** | `mcp/McpWebSocketTransport.java` | +1 行 (implements) |
| **修改** | `mcp/McpServerConnection.java` | 重构: 434行 → ~200行 |
| **新增** | `mcp/McpCapabilityDefinition.java` | ~55 行 |
| **新增** | `mcp/McpCapabilityRegistryService.java` | ~180 行 |
| **修改** | `mcp/McpConfiguration.java` | +10 行 |
| **修改** | `mcp/McpToolAdapter.java` | 重构: call() 70行 → 40行 |
| **修改** | `mcp/McpClientManager.java` | +60 行 |
| **新增** | `controller/McpCapabilityController.java` | ~120 行 |
| **修改** | `resources/application.yml` | +1 行 |
| **新增** | `store/mcpCapabilityStore.ts` | ~180 行 |
| **新增** | `components/settings/McpCapabilityPanel.tsx` | ~250 行 |
| **修改** | `components/settings/SettingsPanel.tsx` | +4 行 |
| **修改** | `api/index.ts` | +3 行 |

**总计**: 新增 ~960 行，修改 ~90 行，删除 ~234 行（McpServerConnection STDIO 代码迁出）

---

## 五、实施顺序与依赖关系

```
Task 1: McpTransport.java (接口，无依赖)
   ↓
Task 2: McpStdioTransport.java + 现有传输类适配 (依赖 Task 1)
   ↓
Task 3: McpServerConnection.java 重构 (依赖 Task 1, 2)
   ↓
Task 4: McpCapabilityDefinition.java (数据模型，无依赖，可与 Task 1-3 并行)
   ↓
Task 6: McpConfiguration.java + application.yml (无依赖，可与 Task 4 并行)
   ↓
Task 5: McpCapabilityRegistryService.java (依赖 Task 4 数据模型 + Task 6 配置字段)
   ↓
Task 7: McpToolAdapter.java 重构 (依赖 Task 3 的 callTool 方法)
   ↓
Task 8: McpClientManager.java 集成 (依赖 Task 4, 5, 7)
   ↓
Task 9: McpCapabilityController.java (依赖 Task 5, 8)
   ↓  (后端完成，前端可开始)
Task 10: mcpCapabilityStore.ts (依赖 Task 9 API)
   ↓
Task 11: McpCapabilityPanel.tsx (依赖 Task 10)
   ↓
Task 12: SettingsPanel.tsx + api/index.ts (依赖 Task 11)
```

---

## 六、风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 156 个工具全部启用导致大量 SSE 连接 | 服务器资源耗尽 | 同一 sseUrl 的工具共享连接（按 serverKey 去重） |
| `apiKeyDefault` 硬编码在 JSON 中 | API Key 泄露 | 生产环境应删除，强制使用 Spring 属性注入 |
| JSON 文件并发写入 | 数据损坏 | ★ `saveToFile()` 使用 ReentrantLock + 防抖 500ms |
| `extractServerKey()` URL 解析 | 服务器名冲突 | ★ 使用 `java.net.URI` 解析 + 过滤空段 |
| SSE 传输下工具调用失败 | 156 个 SSE 工具不可用 | ★ **P7 根本修复**: McpTransport 接口 + McpServerConnection.callTool() |
| @Value 与 @ConfigurationProperties 冲突 | 配置属性分裂 | ★ **P8 修复**: 集中到 McpConfiguration + 构造注入 |
| Spring Boot 启动顺序不确定 | 注册表未加载时 listEnabled() 返回空 | ★ `initializeAll()` 加防御 `registryService.size() > 0` |
| 前端 `enabledCount` 增量计数 | 重复操作导致偏移 | ★ 改用 `filter(c => c.enabled).length` 重新计算 |
| STDIO 代码提取后回归风险 | 现有 STDIO 连接行为变化 | 单元测试覆盖 McpStdioTransport 的 connect/sendRequest/close |
| McpStreamableHttpTransport connect() 签名变更 | 现有 HTTP 服务器配置可能受影响 | 保留原有 connect(Map) 方法，新增无参 connect() 委托 |
