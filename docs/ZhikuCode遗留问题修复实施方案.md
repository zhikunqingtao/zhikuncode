# ZhikuCode 遗留问题修复实施方案

| 属性 | 值 |
|------|------|
| **文档版本** | v1.3 |
| **编制日期** | 2026-04-15 |
| **编制依据** | ZhikuCode核心功能测试报告_v3.md 第八章（L668-894） |
| **文档状态** | 全部 17 项已完成：6 项首轮完成 + 4 项降级为 BashTool 覆盖 + 7 项本轮执行完成 |
| **适用范围** | ZhikuCode 全栈（Java 后端 / React 前端 / Python 服务） |

### 执行记录

| 日期 | 执行项 | 状态 | 补充说明 |
|------|---------|------|----------|
| 2026-04-15 | ERR-1: McpSseTransport.close() 资源释放 | ✅ 已完成 | 按方案执行，新增 httpClient 关闭逻辑 |
| 2026-04-15 | ERR-2: Coordinator 条件统一 | ✅ 已完成 | 新增 isCoordinatorTopLevel() + EffectiveSystemPromptBuilder 调用替换 |
| 2026-04-15 | ERR-3: SSE 断连检测 + 重连 | ⚠️ 部分完成 | readTimeout 90s + disconnectCallback 字段 + SseHealthChecker + scheduleReconnect + @EnableScheduling 已完成；发现 2 处遗留缺陷 |
| 2026-04-15 | ERR-3 缺陷 1: sendNotification 吞异常 | ✅ 已完成 | 新增 sendHealthPing() 方法链（McpTransport→McpSseTransport→McpServerConnection→SseHealthChecker），返回 boolean 替代 try-catch |
| 2026-04-15 | ERR-3 缺陷 2: disconnectCallback 未接线 | ✅ 已完成 | McpServerConnection.connect() 中为 SSE transport 注入 disconnectCallback，onFailure 时标记 FAILED |
| 2026-04-15 | INC-2: Swarm 门控模板 | ✅ 已完成 | SwarmService + SwarmController 新建，包含完整门控和占位实现 |
| 2026-04-15 | UNI-6: Classifier 接入 Pipeline | ✅ 已完成 | evaluateClassifierRules() 完整实现 + isHighRiskCommand() |
| 2026-04-15 | INC-3: permissionMode 接入 | ✅ 已完成 | QueryController 三处统一修复 |
| 2026-04-15 | INC-4: WebSocket 死代码清理 | ✅ 已完成 | 删除 WebSocketProvider.tsx + 清理 barrel export |
| 2026-04-15 | UNI-7: 方案修正 | 📝 方案修正 | 原方案“新建 useTheme Hook”错误 — 实际 ThemeProvider+ThemePicker+configStore 已完整实现，真正 bug 在 SettingsPanel 本地 ThemePicker 使用 useState |
| 2026-04-15 | Phase 3 方案精简 | 📝 方案重构 | 原 5 域 ~8 人天精简为 GIT_ENHANCED 单域 + Java Tool 桥接 ~2 人天；UNI-1~4 降级为 BashTool 覆盖（v1.2→v1.3） |
| 2026-04-15 | INC-1: Playwright 浏览器二进制部署 | ✅ 已完成 | Dockerfile 添加 playwright install chromium --with-deps；requirements.txt 添加安装提示注释 |
| 2026-04-15 | UNI-5: GIT_ENHANCED 全链路实现 | ✅ 已完成 | Python 端 git_enhanced.py + git_enhanced_service.py；Java 端 GitTool.java + application.yml GIT_ENHANCED_TOOL |
| 2026-04-15 | INC-5: RESOURCE_MONITOR 配置 | ✅ 已完成 | application.yml 新增 RESOURCE_MONITOR 配置项；MonitorTool 改进禁用提示消息 |
| 2026-04-15 | INC-6: 健康检查端点标准化 | ✅ 已完成 | management 段补全 base-path + show-details 配置 |
| 2026-04-15 | INC-7: 敏感数据过滤文档同步 | ✅ 已完成 | SensitiveDataFilter Javadoc "6 种"→6"10 种"；架构文档追加敏感数据过滤段落 |
| 2026-04-15 | UNI-7: 前端暗色主题修复 | ✅ 已完成 | SettingsPanel 删除本地 ThemePicker，改用全局 ThemePicker 组件 |
| 2026-04-15 | CR-1: Git 增强错误返回协议统一 | ✅ 已完成 | Python 端 HTTPException→GitResponse(success/error_code/error_message)；Java 端 call() 区分能力缺失与业务错误 |
| 2026-04-15 | CR-2: Java Schema 补充 blame ref 参数 | ✅ 已完成 | GitTool.getInputSchema() 新增 ref 属性声明 |
| 2026-04-15 | CR-3: capabilities.py min_versions 包名修正 | ✅ 已完成 | min_versions key 从 "git" 改为 "GitPython"，使版本探测生效 |

---

## 第一章：文档概述

### 1.1 文档目的

本文档针对《ZhikuCode核心功能测试报告_v3》第八章列出的 **17 项遗留问题**，提供系统性修复方案。其中 4 项 P2 能力域（UNI-1~4）经战略评估后降级为 BashTool 覆盖（详见 §5.1），其余 13 项包含根因分析、修复步骤、涉及文件、代码片段和验证方法，确保修复方案可直接执行落地。

### 1.2 问题总览表

| 编号 | 名称 | 类别 | 严重级别 | 所属模块 | 修复阶段 | 状态 |
|------|------|------|----------|----------|----------|------|
| ERR-3 | SSE 健康检查无效 + 断连回调未接线 | 实现缺陷 | P1 | MCP 集成 | Phase 1 | ✅ 已完成 |
| INC-1 | Python BROWSER_AUTOMATION 浏览器二进制未部署 | 实现不完整 | P2 | Python 服务 | Phase 2 | ✅ 已完成 |
| UNI-1 | Python SECURITY 安全扫描能力域 | 完全未实现 | P2 | Python 服务 | — | 降级为 BashTool 覆盖 |
| UNI-2 | Python CODE_QUALITY 代码质量分析能力域 | 完全未实现 | P2 | Python 服务 | — | 降级为 BashTool 覆盖 |
| UNI-3 | Python VISUALIZATION 可视化生成能力域 | 完全未实现 | P2 | Python 服务 | — | 降级为 BashTool 覆盖 |
| UNI-4 | Python DOC_GENERATION 文档生成能力域 | 完全未实现 | P2 | Python 服务 | — | 降级为 BashTool 覆盖 |
| UNI-5 | Python GIT_ENHANCED Git增强能力域 + Java Tool 桥接 | 完全未实现 | P2 | Python 服务 + Java 后端 | Phase 3 | ✅ 已完成 |
| INC-5 | RESOURCE_MONITOR 功能门控关闭 | 实现不完整 | P3 | 工具系统 | Phase 4 | ✅ 已完成 |
| INC-6 | 健康检查端点非标准 | 实现不完整 | P3 | 基础设施 | Phase 4 | ✅ 已完成 |
| INC-7 | 敏感数据过滤文档与实现不一致 | 实现不完整 | P3 | 文档 | Phase 4 | ✅ 已完成 |
| UNI-7 | 前端暗色主题设置未接入主题系统 | 实现缺陷 | P3 | 前端 | Phase 4 | ✅ 已完成 |

> **已完成并移除的 6 项**：ERR-1（MCP SSE 资源释放）、ERR-2（Coordinator 条件统一）、INC-2（Swarm 门控）、UNI-6（Classifier 接入 Pipeline）、INC-3（permissionMode 接入）、INC-4（WebSocket 死代码清理）

### 1.3 修复范围

- **修复目标**：消除全部 P0/P1 问题，补全 P2 核心功能（GIT_ENHANCED 单域 + Java Tool 桥接），清理 P3 技术债务
- **降级处理**：UNI-1~4 四个 P2 能力域降级为 BashTool 兜底覆盖，不单独实现（详见 §5.1 战略决策依据）
- **不在范围内**：功能新增（超出测试报告范围的需求）、性能优化、架构重构

---

## 第二章：修复策略与实施排期

### 2.1 四阶段修复计划

| 阶段 | 定位 | 工时预估 | 问题编号 | 关键目标 |
|------|------|----------|----------|----------|
| **Phase 1** | 紧急补修 | ~1 人天 | ERR-3 | 修复 SseHealthChecker 无效问题 + disconnectCallback 接线 |
| **Phase 2** | 重要/本迭代 | ~1 人天 | INC-1 | Playwright 浏览器二进制部署 |
| **Phase 3** | GIT_ENHANCED + Java Tool 桥接 | ~2 人天 | UNI-5 | GIT_ENHANCED 单域实现 + Java Tool 桥接全链路验证；UNI-1~4 降级为 BashTool 覆盖 |
| **Phase 4** | 优化/技术债务 | ~3 人天 | INC-5, INC-6, INC-7, UNI-7 | 配置优化、文档同步、前端增强 |

**总预估工时：~7 人天**（已完成 ~8 人天，原 Phase 3 精简节省 ~6 人天）

### 2.2 依赖关系

```
Phase 1 (修复 ERR-3 遗留缺陷，无外部依赖)
  └── ERR-3 (SseHealthChecker 无效 + disconnectCallback 未接线) ─── 独立修复

Phase 2 (独立)
  └── INC-1 (Playwright 二进制) ─── 独立修复

Phase 3 (GIT_ENHANCED 单域 + Java Tool 全链路)
  ├── UNI-5 Python 端 (routers/git_enhanced.py + services/git_enhanced_service.py) ─── gitpython 已安装
  ├── UNI-5-Bridge Java 端 (GitTool.java + application.yml) ─── 参照 WebBrowserTool 模式
  └── UNI-1~4 降级为 BashTool 覆盖 ─── 无代码工作，仅文档记录

Phase 4 (可并行，低优先级)
  ├── INC-5 / INC-6 / INC-7 / UNI-7 ─── 全部独立
```

### 2.3 资源分配建议

| 角色 | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|------|---------|---------|---------|----------|
| 后端开发 | ERR-3 | — | UNI-5-Bridge (GitTool.java) | INC-5, INC-6 |
| 前端开发 | — | — | — | UNI-7 |
| Python 开发 | — | INC-1 | UNI-5 Python 端 | — |
| 文档 | — | — | — | INC-7 |

---

## 第三章：Phase 1 — 紧急补修

### 3.1 ERR-3：SseHealthChecker 主动 ping 无效 + disconnectCallback 未接线（P1）

**已完成部分**（上一轮执行）：
- `McpSseTransport` 和 `McpServerConnection` 的 readTimeout 已从 `Duration.ZERO` 改为 `Duration.ofSeconds(90)`
- `McpSseTransport` 已新增 `disconnectCallback` 字段、`setDisconnectCallback()` setter、`onFailure` 中调用回调
- `SseHealthChecker.java` 已创建（每 30s 主动 ping）
- `McpClientManager.scheduleReconnect()` 已实现
- `Application.java` 已添加 `@EnableScheduling`

**遗留缺陷 1：SseHealthChecker 主动 ping 永远不触发重连**

**修复方案**：

`SseHealthChecker.performActiveHealthCheck()` 通过 `connection.sendNotification("notifications/ping", null)` 发送 ping，期望在网络异常时捕获 `Exception` 触发重连。但底层调用链完全吞掉了异常：

```java
// McpSseTransport.java L193-213 — 当前实现
public void sendNotification(String method, Object params) {
    try {
        // ... 构建并发送 HTTP 请求
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Notification '{}' failed: HTTP {}", method, response.code());
                // ★ 问题：HTTP 非 2xx 仅 log.warn，不抛异常
            }
        }
    } catch (Exception e) {
        log.warn("Failed to send notification '{}': {}", method, e.getMessage());
        // ★ 问题：网络异常也被吞掉，不向上传递
    }
}
```

`McpServerConnection.sendNotification()` (L245-247) 直接委托，也不抛异常：

```java
public void sendNotification(String method, Object params) {
    if (transport != null) { transport.sendNotification(method, params); }
}
```

因此 `SseHealthChecker` 的 `catch (Exception e)` **永远不会被触发**，`DEGRADED` 标记和 `scheduleReconnect()` 永远不会执行。

**遗留缺陷 2：disconnectCallback 定义了 setter 但从未被上层调用**

`McpSseTransport.setDisconnectCallback(Runnable callback)` (L81-83) 在 `onFailure` 中调用回调，但全局没有任何代码调用 `setDisconnectCallback()` 注入回调。SSE 层 `onFailure → disconnectCallback` 的触发链路是“悬空”的。

**修复方案**：

**Step 1 — 在 `McpSseTransport` 中新增 `sendHealthPing()` 方法**（不修改现有 `sendNotification` 契约）：

```java
// McpSseTransport.java — 新增方法（添加在 sendNotification 之后）
/**
 * 健康探测 — 发送 ping 通知并返回连接是否正常。
 * 与 sendNotification 不同：HTTP 非 2xx 或网络异常时返回 false。
 */
public boolean sendHealthPing() {
    if (!connected.get()) return false;
    try {
        JsonRpcMessage.Notification notification =
                new JsonRpcMessage.Notification("notifications/ping", null);
        String json = objectMapper.writeValueAsString(notification);
        String targetUrl = sessionEndpoint != null ? sessionEndpoint : postUrl;
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(targetUrl)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Health ping failed: HTTP {}", response.code());
                return false;
            }
            return true;
        }
    } catch (Exception e) {
        log.warn("Health ping exception: {}", e.getMessage());
        return false;
    }
}
```

**Step 2 — 在 `McpTransport` 接口中添加 `sendHealthPing()` default 方法**（保持向后兼容）：

```java
// McpTransport.java — 新增 default 方法
/**
 * 健康探测 — 子类可覆盖以提供传输层特定的健康检测。
 * 默认返回连接状态。
 */
default boolean sendHealthPing() {
    return isConnected();
}
```

**Step 3 — 在 `McpServerConnection` 中新增 `sendHealthPing()` 委托方法**：

```java
// McpServerConnection.java — 新增方法（添加在 sendNotification 之后）
/**
 * 发送健康检测 ping — 返回 boolean 表示连接是否活跃。
 * 仅供 SseHealthChecker 使用。
 */
public boolean sendHealthPing() {
    if (transport == null) return false;
    try {
        return transport.sendHealthPing();
    } catch (Exception e) {
        return false;
    }
}
```

**Step 4 — 修改 `SseHealthChecker.performActiveHealthCheck()` 使用 `sendHealthPing()`**：

```java
// SseHealthChecker.java — performActiveHealthCheck() 修复
@Scheduled(fixedRate = 30_000, initialDelay = 30_000)
public void performActiveHealthCheck() {
    for (McpServerConnection connection : mcpClientManager.listConnections()) {
        if (connection.getStatus() != McpConnectionStatus.CONNECTED) {
            continue;
        }
        // ★ 修复：使用返回 boolean 的 sendHealthPing() 替代会吞异常的 sendNotification()
        if (!connection.sendHealthPing()) {
            log.warn("Active ping failed for '{}', marking as DEGRADED",
                    connection.getName());
            connection.setStatus(McpConnectionStatus.DEGRADED);
            mcpClientManager.scheduleReconnect(connection.getName());
        }
    }
}
```

**Step 5 — 在 `McpServerConnection.connect()` 中接线 `disconnectCallback`**：

```java
// McpServerConnection.java — connect() 方法内，创建 transport 之后添加
// 在 this.transport = createTransport(config); 并通过 null 检查之后、transport.connect() 之前：
if (transport instanceof McpSseTransport sseTransport) {
    sseTransport.setDisconnectCallback(() -> {
        log.warn("SSE disconnect callback triggered for '{}'", config.name());
        this.status = McpConnectionStatus.FAILED;
    });
}
```

> **设计决策**：回调仅设置状态为 `FAILED`，由 `McpClientManager.healthCheck()` 在下一个 30s 周期检测到 `FAILED` 状态并触发 `attemptReconnect()`。这避免了在 SSE 事件回调线程中直接执行重连的线程安全问题。

**涉及文件**：
| 文件 | 修改内容 |
|------|----------|
| `backend/.../mcp/McpSseTransport.java` | **新增** `sendHealthPing()` 方法（返回 boolean，不吞异常） |
| `backend/.../mcp/McpTransport.java` | **新增** `sendHealthPing()` default 方法 |
| `backend/.../mcp/McpServerConnection.java` | **新增** `sendHealthPing()` 委托方法；`connect()` 中为 SSE transport 注入 `disconnectCallback` |
| `backend/.../mcp/SseHealthChecker.java` | 将 `sendNotification()` 替换为 `sendHealthPing()` |

**验证方法**：
1. 停止 MCP SSE 服务端 → 验证 SseHealthChecker 在 30s 内检测到 `sendHealthPing()` 返回 false → 标记 DEGRADED → scheduleReconnect
2. 模拟 SSE 事件流异常 → 验证 `onFailure` → `disconnectCallback` → 状态变为 FAILED → healthCheck 触发 attemptReconnect
3. 长时间运行测试（>10min）→ 验证双路径（主动 ping + 被动回调）均能正确触发重连

**回归风险**：低。新增方法，不修改现有 `sendNotification` 语义，`McpTransport.sendHealthPing()` 使用 default 方法保持向后兼容。

---

## 第四章：Phase 2 — 重要修复

### 4.1 INC-1：Python BROWSER_AUTOMATION 浏览器二进制未部署（P2）

**问题回顾**：`playwright` Python 包已安装，路由和服务代码已完整实现（`routers/browser.py` 204 行），但 `playwright install` 未执行。

**修复方案**：

Step 1 — 在 Dockerfile 中添加浏览器安装步骤：

```dockerfile
# Dockerfile — Python 服务部分
RUN pip install playwright && playwright install chromium --with-deps
```

Step 2 — 在开发环境部署脚本中添加：

```bash
# 开发环境初始化脚本
cd python-service
pip install -r requirements.txt
python -m playwright install chromium
```

Step 3 — 更新 `requirements.txt` 添加注释说明：

```
playwright>=1.40.0  # 安装后需执行: python -m playwright install chromium
```

**涉及文件**：
| 文件 | 修改内容 |
|------|----------|
| `Dockerfile` | 添加 `playwright install chromium --with-deps` |
| `python-service/requirements.txt` | 添加安装提示注释 |

**验证方法**：
1. 执行 `python -m playwright install chromium` → 验证无报错
2. 调用 `GET /api/health/capabilities` → 验证 `BROWSER_AUTOMATION.available=true`
3. 调用 `POST /api/browser/screenshot` 端点 → 验证返回截图数据

**回归风险**：低。仅部署层变更，不修改业务代码。

---

## 第五章：Phase 3 — GIT_ENHANCED 单域 + Java Tool 全链路（精简方案）

> **方案变更说明**：原方案规划 5 个 P2 能力域全部实现（~8 人天），经战略评估后精简为 **GIT_ENHANCED 单域 + Java Tool 桥接全链路**（~2 人天）。变更原因详见 §5.1 决策依据。

### 5.1 战略决策依据

原 Phase 3 规划存在三个关键问题，导致投入产出比严重偏低：

**问题一：架构鸿沟 — 方案缺失 Java Tool 桥接层**

zhikuncode 的 LLM 工具调用链路为：
```
LLM → ToolRegistry.findByName() → Tool.call() → PythonCapabilityAwareClient → Python 端点
```
原方案仅覆盖 Python 端（路由 + 服务），**完全缺失 Java 端 Tool 实现**。没有 Java Tool 的 Python 端点是"孤岛代码"——LLM 无法触达。项目中唯一的 Java→Python 桥接先例是 `WebBrowserTool.java` → `PythonCapabilityAwareClient` → `/api/browser/*`。

**问题二：BashTool 已覆盖 80%+ 功能**

Java 后端的 `BashTool`（§3.2.3）可通过 shell 命令直接执行 P2 域的核心能力：
| 能力域 | BashTool 等效命令 | 覆盖度 |
|--------|-------------------|--------|
| SECURITY | `bandit -f json <file>` | ~90% |
| CODE_QUALITY | `pylint --output-format=json <file>` | ~85% |
| VISUALIZATION | LLM 直接生成 Mermaid/SVG 代码 | ~70% |
| DOC_GENERATION | LLM 直接生成 Markdown | ~90% |
| GIT_ENHANCED | `git diff`/`git log`/`git blame` | ~60% |

GIT_ENHANCED 是 BashTool 覆盖度最低的域 — `git log --stat --format=...` 的结构化输出能力有限，语义化 diff 分析、跨分支文件变更追踪等高级场景需要 gitpython 的 Python API 才能高效实现。

**问题三：优先级与痛点错配**

测试报告 §7.3 将 P2 能力域列为**长期**优先级。当前真正的瓶颈是：
- P1 MCP SSE 稳定性（ERR-3 遗留 2 缺陷）
- P2 Playwright 部署（INC-1，~0.5 天即可激活 511 行已有代码）
- 多 Agent 协作覆盖率仅 60%

**最终决策**：

| 能力域 | 决策 | 理由 |
|--------|------|------|
| **GIT_ENHANCED** | **实施**（含 Java Tool 全链路） | BashTool 覆盖最低；gitpython 已安装零依赖成本；作为 Java→Python 桥接模式的标准化模板 |
| SECURITY | 降级为 BashTool 覆盖 | `bandit` 命令行覆盖 90%，LLM 可通过 BashTool 直接调用 |
| CODE_QUALITY | 降级为 BashTool 覆盖 | `pylint`/`radon` 命令行覆盖 85%，且 tree-sitter 已有 AST 基础可扩展 |
| VISUALIZATION | 降级为 BashTool 覆盖 | LLM 可直接生成 Mermaid/SVG，Python 端无不可替代优势 |
| DOC_GENERATION | 降级为 BashTool 覆盖 | LLM 自身即为最佳文档生成工具 |

---

### 5.2 UNI-5：GIT_ENHANCED Python 端实现

**所需依赖**：`gitpython==3.1.43`（**已安装**在 venv 中，零依赖成本）

**注册流程**（已预置，无需修改）：
- `capabilities.py` L79-86 已注册 `GIT_ENHANCED` 的 `CapabilityInfo`（含 `git` 包检测 + `git` 二进制检测）
- `main.py` 的 `ROUTER_PREFIX_MAP` 已包含 `"git_enhanced": "/api/git"` 映射
- 只需创建路由文件和服务文件，启动时自动注册

**端点设计**：
| 端点 | 方法 | 功能 | 前缀 |
|------|------|------|------|
| `/diff` | POST | 语义化 diff 分析（文件级变更统计 + 详细 diff） | `/api/git` |
| `/log` | POST | 结构化 commit 日志（含文件列表、作者、时间） | `/api/git` |
| `/blame` | POST | 文件 blame 信息（逐行归属分析） | `/api/git` |

**核心实现 — 路由层**：

```python
# python-service/src/routers/git_enhanced.py
"""
Git Enhanced Router — Git 增强能力域路由
"""
import logging
from typing import Optional
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Git Enhanced"])

# ── Pydantic 请求/响应模型 ──
class DiffRequest(BaseModel):
    repo_path: str = Field(..., description="Git 仓库路径")
    ref1: str = Field(default="HEAD~1", description="起始引用")
    ref2: str = Field(default="HEAD", description="结束引用")

class DiffResponse(BaseModel):
    summary: str = Field(description="变更统计摘要")
    detailed: str = Field(description="详细 diff 内容")
    files_changed: int = Field(description="变更文件数")

class LogRequest(BaseModel):
    repo_path: str = Field(..., description="Git 仓库路径")
    max_count: int = Field(default=20, ge=1, le=100, description="最大条目数")
    branch: Optional[str] = Field(default=None, description="分支名")

class LogEntry(BaseModel):
    sha: str
    message: str
    author: str
    date: str
    files: list[str]

class LogResponse(BaseModel):
    commits: list[LogEntry]
    total: int

class BlameRequest(BaseModel):
    repo_path: str = Field(..., description="Git 仓库路径")
    file_path: str = Field(..., description="文件相对路径")
    ref: str = Field(default="HEAD", description="引用")

class BlameLine(BaseModel):
    line_no: int
    sha: str
    author: str
    date: str
    content: str

class BlameResponse(BaseModel):
    file_path: str
    lines: list[BlameLine]
    total_lines: int

# ── Service 延迟初始化 ──
_service = None

def _get_service():
    global _service
    if _service is None:
        from services.git_enhanced_service import GitEnhancedService
        _service = GitEnhancedService()
    return _service

# ── 路由端点 ──
@router.post("/diff", response_model=DiffResponse)
async def git_diff(request: DiffRequest):
    try:
        svc = _get_service()
        return svc.semantic_diff(request.repo_path, request.ref1, request.ref2)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Git diff failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/log", response_model=LogResponse)
async def git_log(request: LogRequest):
    try:
        svc = _get_service()
        return svc.enhanced_log(request.repo_path, request.max_count, request.branch)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Git log failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/blame", response_model=BlameResponse)
async def git_blame(request: BlameRequest):
    try:
        svc = _get_service()
        return svc.file_blame(request.repo_path, request.file_path, request.ref)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Git blame failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))
```

**核心实现 — 服务层**：

```python
# python-service/src/services/git_enhanced_service.py
"""
Git Enhanced Service — gitpython 封装的 Git 增强分析服务
"""
import git
import os
import logging
from typing import Optional

logger = logging.getLogger(__name__)

class GitEnhancedService:
    """Git 增强服务 — 基于 gitpython 提供结构化 Git 分析能力"""

    # 禁止访问的路径黑名单
    _UNSAFE_PATHS = frozenset(['/', '/root', '/etc', '/var', '/usr'])

    def _validate_repo_path(self, repo_path: str) -> str:
        """路径安全校验 — 防止路径穿越和访问敏感目录"""
        real_path = os.path.realpath(repo_path)
        # 禁止访问系统根目录和用户根目录
        if real_path in self._UNSAFE_PATHS or real_path == os.path.expanduser('~'):
            raise ValueError(f"Unsafe repo path: {repo_path}")
        if not os.path.isdir(real_path):
            raise ValueError(f"Not a directory: {repo_path}")
        # 验证是否为有效 Git 仓库
        git_dir = os.path.join(real_path, '.git')
        if not os.path.isdir(git_dir):
            raise ValueError(f"Not a git repository: {repo_path}")
        return real_path

    def semantic_diff(self, repo_path: str, ref1: str = "HEAD~1", ref2: str = "HEAD") -> dict:
        """语义化 diff 分析 — 返回变更统计 + 详细 diff"""
        safe_path = self._validate_repo_path(repo_path)
        repo = git.Repo(safe_path)
        diff = repo.git.diff(ref1, ref2, stat=True)
        detailed = repo.git.diff(ref1, ref2)
        return {
            "summary": diff,
            "detailed": detailed,
            "files_changed": len(repo.commit(ref2).diff(ref1))
        }

    def enhanced_log(self, repo_path: str, max_count: int = 20,
                     branch: Optional[str] = None) -> dict:
        """结构化 commit 日志 — 返回含文件列表的详细日志"""
        safe_path = self._validate_repo_path(repo_path)
        repo = git.Repo(safe_path)
        rev = branch or 'HEAD'
        commits = [
            {
                "sha": c.hexsha[:8],
                "message": c.message.strip(),
                "author": str(c.author),
                "date": c.committed_datetime.isoformat(),
                "files": list(c.stats.files.keys())
            }
            for c in repo.iter_commits(rev=rev, max_count=max_count)
        ]
        return {"commits": commits, "total": len(commits)}

    def file_blame(self, repo_path: str, file_path: str, ref: str = "HEAD") -> dict:
        """文件 blame — 逐行归属分析"""
        safe_path = self._validate_repo_path(repo_path)
        repo = git.Repo(safe_path)
        blame_data = repo.blame(ref, file_path)
        lines = []
        line_no = 1
        for commit, content_lines in blame_data:
            for content in content_lines:
                lines.append({
                    "line_no": line_no,
                    "sha": commit.hexsha[:8],
                    "author": str(commit.author),
                    "date": commit.committed_datetime.isoformat(),
                    "content": content if isinstance(content, str) else content.decode('utf-8', errors='replace')
                })
                line_no += 1
        return {
            "file_path": file_path,
            "lines": lines,
            "total_lines": len(lines)
        }
```

**Python 端涉及文件**：
| 文件 | 操作 | 说明 |
|------|------|------|
| `python-service/src/routers/git_enhanced.py` | 新建 | 路由层：3 个端点 + Pydantic 模型 |
| `python-service/src/services/git_enhanced_service.py` | 新建 | 服务层：gitpython 封装 + 路径安全校验 |

> **注意**：`requirements.txt` 无需修改 — `gitpython==3.1.43` 已在 L32 安装。

---

### 5.3 UNI-5-Bridge：GitTool.java — Java Tool 桥接实现

> **这是原方案完全缺失的关键环节**。没有 Java Tool 桥接，Python 端点无法被 LLM 调用。本节以 `WebBrowserTool.java` 为模板，实现 `GitTool.java` 的完整桥接。

**桥接架构**：
```
LLM 调用 "Git" 工具
  → ToolRegistry.findByName("Git")
    → GitTool.call(input, context)
      → pythonClient.callIfAvailable("GIT_ENHANCED", "/api/git/{action}", body, GitResponse.class)
        → Python FastAPI /api/git/{action}
          → GitEnhancedService.semantic_diff() / enhanced_log() / file_blame()
```

**核心实现**：

```java
// backend/src/main/java/com/aicodeassistant/tool/impl/GitTool.java
package com.aicodeassistant.tool.impl;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * GitTool — Git 增强工具（Java→Python→gitpython 架构）。
 *
 * <p>通过 Python gitpython 提供语义化 diff、结构化日志、逐行 blame 等能力。
 * 双重门控：feature flag + Python GIT_ENHANCED 能力域。</p>
 *
 * <p>桥接模式参照 WebBrowserTool.java 实现。</p>
 */
@Component
public class GitTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GitTool.class);

    private final PythonCapabilityAwareClient pythonClient;
    private final FeatureFlagService featureFlags;
    private final ObjectMapper objectMapper;
    private static final String CAPABILITY = "GIT_ENHANCED";
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "diff", "log", "blame");

    public GitTool(PythonCapabilityAwareClient pythonClient,
                   FeatureFlagService featureFlags,
                   ObjectMapper objectMapper) {
        this.pythonClient = pythonClient;
        this.featureFlags = featureFlags;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() { return "Git"; }

    @Override
    public String getDescription() {
        return "Git enhanced analysis tool for semantic diff, structured commit log, "
             + "and line-by-line blame. Provides richer output than raw git commands.";
    }

    @Override
    public String prompt() {
        return """
                Git enhanced analysis tool powered by gitpython.
                Use this tool when you need structured Git analysis beyond raw git commands:
                - "diff": Semantic diff analysis with file-level change statistics
                - "log": Structured commit log with per-commit file lists
                - "blame": Line-by-line attribution for a specific file
                
                The repo_path parameter should be an absolute path to the git repository.
                For diff/log, you can specify git refs (branches, tags, commit SHAs).
                
                Prefer BashTool for simple git operations (status, add, commit, push).
                Use this tool only for analysis operations that benefit from structured output.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("action", "repo_path"),
                "properties", Map.ofEntries(
                        Map.entry("action", Map.of(
                                "type", "string",
                                "enum", List.copyOf(ALLOWED_ACTIONS),
                                "description", "Git action: diff, log, or blame")),
                        Map.entry("repo_path", Map.of(
                                "type", "string",
                                "description", "Absolute path to the git repository")),
                        Map.entry("ref1", Map.of(
                                "type", "string",
                                "description", "Start reference for diff (default: HEAD~1)")),
                        Map.entry("ref2", Map.of(
                                "type", "string",
                                "description", "End reference for diff (default: HEAD)")),
                        Map.entry("file_path", Map.of(
                                "type", "string",
                                "description", "File path for blame (relative to repo root)")),
                        Map.entry("max_count", Map.of(
                                "type", "integer",
                                "description", "Max entries for log (default: 20, max: 100)")),
                        Map.entry("branch", Map.of(
                                "type", "string",
                                "description", "Branch name for log (default: current HEAD)"))
                )
        );
    }

    @Override
    public String getGroup() { return "read"; }

    @Override
    public boolean isEnabled() {
        return featureFlags.isEnabled("GIT_ENHANCED_TOOL")
                && pythonClient.isCapabilityAvailable(CAPABILITY);
    }

    @Override
    public boolean isReadOnly(ToolInput input) { return true; }

    @Override
    public boolean isConcurrencySafe(ToolInput input) { return true; }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;  // 只读工具无需权限确认
    }

    @Override
    public ValidationResult validateInput(ToolInput input, ToolUseContext context) {
        String action = input.getString("action", null);
        if (action == null || !ALLOWED_ACTIONS.contains(action)) {
            return ValidationResult.invalid("INVALID_ACTION",
                    "Action must be one of: " + ALLOWED_ACTIONS);
        }
        String repoPath = input.getString("repo_path", null);
        if (repoPath == null || repoPath.isBlank()) {
            return ValidationResult.invalid("MISSING_REPO_PATH",
                    "repo_path is required");
        }
        if ("blame".equals(action)) {
            String filePath = input.getString("file_path", null);
            if (filePath == null || filePath.isBlank()) {
                return ValidationResult.invalid("MISSING_FILE_PATH",
                        "file_path is required for blame action");
            }
        }
        return ValidationResult.ok();
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String action = input.getString("action");
        Map<String, Object> body = new HashMap<>(input.getRawData());

        Optional<GitResponse> resp = pythonClient.callIfAvailable(
                CAPABILITY, "/api/git/" + action, body, GitResponse.class);

        if (resp.isEmpty()) {
            return ToolResult.error(
                    "Git enhanced analysis unavailable. "
                    + "Ensure gitpython is installed and GIT_ENHANCED capability is active.");
        }
        try {
            return ToolResult.success(objectMapper.writeValueAsString(resp.get()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ToolResult.error("Failed to serialize git response: " + e.getMessage());
        }
    }

    /** Python 端通用响应 — 透传 JSON 结构 */
    record GitResponse(Map<String, Object> data) {
        // Jackson 自动将 Python 返回的 JSON 平铺反序列化
    }
}
```

**Feature Flag 配置**：

```yaml
# backend/src/main/resources/application.yml — 在 features.flags 下新增
features:
  flags:
    GIT_ENHANCED_TOOL: ${GIT_ENHANCED_TOOL:true}  # 默认开启（只读工具，无安全风险）
```

**Java 端涉及文件**：
| 文件 | 操作 | 说明 |
|------|------|------|
| `backend/src/main/java/com/aicodeassistant/tool/impl/GitTool.java` | **新建** | Java→Python 桥接 Tool（参照 WebBrowserTool 模式） |
| `backend/src/main/resources/application.yml` | 修改 | `features.flags` 新增 `GIT_ENHANCED_TOOL: true` |

> **设计决策**：GitTool 的 `getPermissionRequirement()` 返回 `NONE`（无需用户确认），因为 diff/log/blame 均为只读操作，不修改仓库状态。这与 BashTool 的 `CONDITIONAL` 形成对比 — BashTool 的 `git` 命令可能执行写操作（push/reset），需逐条检查。

---

### 5.4 UNI-1~4：降级策略 — BashTool 覆盖 + 未来扩展路线

UNI-1（SECURITY）、UNI-2（CODE_QUALITY）、UNI-3（VISUALIZATION）、UNI-4（DOC_GENERATION）降级为 **BashTool 兜底覆盖**，不单独实现 Python 端点和 Java Tool。

**降级方案说明**：

| 编号 | 能力域 | BashTool 等效用法 | 不实现理由 |
|------|--------|-------------------|------------|
| UNI-1 | SECURITY | LLM 通过 BashTool 执行 `pip install bandit && bandit -f json -r <path>` | bandit CLI 覆盖 90%，结构化 JSON 输出已满足 LLM 解析需求 |
| UNI-2 | CODE_QUALITY | LLM 通过 BashTool 执行 `pylint --output-format=json <file>` 或 `radon cc -j <file>` | CLI 工具链成熟，tree-sitter 已有 AST 基础可未来扩展 |
| UNI-3 | VISUALIZATION | LLM 直接在对话中生成 Mermaid 图表语法或 SVG 代码 | LLM 本身具备图表生成能力，Python matplotlib 端点价值极低 |
| UNI-4 | DOC_GENERATION | LLM 直接生成 Markdown/HTML 文档 | LLM 本身即为最佳文档生成工具，Python Jinja2 端点无不可替代优势 |

**未来扩展路线**（当 BashTool 覆盖不足时按需激活）：

1. **CODE_QUALITY 扩展**（优先级：中）：基于已有 `tree_sitter_service.py` 的 AST 引擎，扩展圈复杂度分析和死代码检测端点，零新依赖成本
2. **CODE_INTEL 增强**（优先级：中）：利用已安装的 `rope==1.13.0`，在 `code_intel.py` 路由中新增重构端点（rename/extract_method），增强 LLM 的代码编辑能力
3. **SECURITY 专项**（优先级：低）：当 LLM 需要深度安全分析（如依赖链漏洞追踪）时，再实施 Python 端结构化 API

> **capabilities.py 和 main.py 无需修改** — P2 域的 `CapabilityInfo` 注册和 `ROUTER_PREFIX_MAP` 映射保持现状。当未来需要激活某个域时，只需创建对应的 `routers/{domain}.py` + `services/{domain}_service.py` + `backend/.../tool/impl/{Domain}Tool.java`，即可自动注册生效。

---

### 5.5 验证方法

**Python 端验证**：
1. 重启 Python 服务 → 观察日志确认 `✓ 能力域 [Git 增强] 已就绪`
2. 调用 `GET /api/health/capabilities` → 验证 `GIT_ENHANCED.available=true`
3. 调用 `POST /api/git/diff` → 验证返回结构化 diff 数据
4. 调用 `POST /api/git/log` → 验证返回结构化 commit 日志
5. 调用 `POST /api/git/blame` → 验证返回逐行归属数据

**Java Tool 桥接验证**：
6. 启动后端 → 观察日志确认 `ToolRegistry initialized with N tools: [..., Git, ...]`
7. LLM 对话中发送 "分析一下最近的 git 提交记录" → 验证 LLM 成功调用 Git 工具并返回结构化结果
8. 关闭 Python 服务 → 验证 `GitTool.isEnabled()` 返回 `false`，LLM 自动降级为 BashTool 执行 `git log`

**回归验证**：
9. 执行 `pytest`（Python 端）→ 验证无回归
10. 执行 `mvn test`（Java 端）→ 验证无回归

**回归风险**：低。Python 端为新增路由（不修改已有代码）；Java 端为新增 @Component（Spring 自动发现注册，不修改 ToolRegistry）。

---

## 第六章：Phase 4 — 优化与技术债务清理

### 6.1 INC-5：RESOURCE_MONITOR 功能门控关闭（P3）

**问题回顾**：`MonitorTool.java` 已完整实现，但 `RESOURCE_MONITOR` 开关当前未在 `application.yml` 中配置（需新增），导致 ToolRegistry 跳过注册。

**修复方案**：

**方案选择**：在 `application.yml` 中新增 `RESOURCE_MONITOR` 配置项，默认 `false`（设计决策合理——不影响核心功能），但提供明确的启用指南。

```yaml
# application.yml — 在 features.flags 下新增 RESOURCE_MONITOR 配置项
features:
  flags:
    # RESOURCE_MONITOR: 系统资源监控工具 (MonitorTool)
    # 默认关闭。启用后 LLM 可调用 MonitorTool 获取 CPU/内存/磁盘信息
    # 可通过环境变量 RESOURCE_MONITOR=true 启用
    RESOURCE_MONITOR: ${RESOURCE_MONITOR:false}
```

同时在 `MonitorTool.java` 的 `call()` 方法中改进错误消息：

```java
// MonitorTool.java L84 — 改进提示（call() 方法内）
if (!featureFlagService.isEnabled("RESOURCE_MONITOR")) {
    return ToolResult.error(
        "MonitorTool is disabled. Set environment variable RESOURCE_MONITOR=true "
        + "or update features.flags.RESOURCE_MONITOR in application.yml to enable.");
}
```

**涉及文件**：
| 文件 | 修改内容 |
|------|----------|
| `backend/src/main/resources/application.yml` | 在 `features.flags` 下 **新增** `RESOURCE_MONITOR` 配置项（当前不存在），设为环境变量可控 |
| `backend/.../tool/impl/MonitorTool.java` | `call()` 方法 L84 改进禁用提示消息 |

---

### 6.2 INC-6：健康检查端点标准化（P3）

**问题回顾**：测试报告称 `application.yml` 未配置 management endpoints 暴露，但**实际检查发现 L154-158 已配置**：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info,prometheus
```

**修复方案**：

Step 1 — 验证配置是否生效（可能被其他配置覆盖）：

```bash
# 启动服务后验证
curl http://localhost:8080/actuator/health
# 预期返回: {"status":"UP",...}
```

Step 2 — 确认安全配置不会拦截：

> **已验证**：`SecurityConfig.java` L50-51 配置了 `.anyRequest().permitAll()`，所有路径（包括 `/actuator/**`）均已放行，无需额外添加 requestMatchers。如果 `/actuator/health` 仍不可访问，问题在于 management 配置而非安全拦截。

Step 3 — 补全 management 配置，确保自定义 `/api/health` 与标准 `/actuator/health` 共存（不删除自定义端点，保持向后兼容）：

```yaml
# application.yml — 添加 base-path 配置确保标准路径可访问
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info,prometheus
      base-path: /actuator  # 显式声明（Spring Boot 默认值）
  endpoint:
    health:
      show-details: when-authorized  # 授权用户可看详情
```

**涉及文件**：
| 文件 | 修改内容 |
|------|----------|
| `backend/src/main/resources/application.yml` | 添加 `base-path` 和 `show-details` 配置 |

**验证方法**：
1. `curl localhost:8080/actuator/health` → 返回 `{"status":"UP"}`
2. `curl localhost:8080/api/health` → 返回自定义健康信息（保持兼容）
3. `curl localhost:8080/actuator/metrics` → 返回 metrics 列表

---

### 6.3 INC-7：敏感数据过滤文档与实现不一致（P3）

**问题回顾**：`SensitiveDataFilter.java` L26-47 实际实现了 **10 种**过滤模式，但架构文档仅标注 6 种。

**修复方案**：

1. 更新 `SensitiveDataFilter.java` L11 的 Javadoc（6 种 → 10 种）：

```java
// SensitiveDataFilter.java — L11 Javadoc 修正
// 修复前: * 支持 6 种模式匹配：
// 修复后:
/**
 * 支持 10 种敏感信息模式检测与自动脱敏：
 * 1. OpenAI API Key (sk-...)
 * 2. AWS Access Key (AKIA...)
 * 3. GitHub Personal Token (ghp_...)
 * 4. GitLab Personal Token (glpat-...)
 * 5. Anthropic API Key (sk-ant-...)
 * 6. Slack Token (xox...)
 * 7. 通用 key=value 格式 (api_key, secret, password, token, auth, credential)
 * 8. JWT Token (eyJ...)
 * 9. PEM 私钥头
 * 10. 数据库连接串 (mongodb/postgres/mysql/redis)
 */
```

2. 在 `docs/Claude_Code源码深度架构分析.md` 中 **新增** 敏感数据过滤段落（当前文档中不存在独立的敏感数据过滤描述）：

```markdown
### 敏感数据过滤器（SensitiveDataFilter）

支持 **10 种**敏感信息模式检测与自动脱敏（`***REDACTED***`）：

| # | 模式 | 正则描述 | 示例 |
|---|------|----------|------|
| 1 | OpenAI API Key | `sk-[a-zA-Z0-9]{20,}` | `sk-abc123...` |
| 2 | AWS Access Key ID | `AKIA[0-9A-Z]{16}` | `AKIAIOSFODNN7EXAMPLE` |
| 3 | GitHub Personal Access Token | `ghp_[a-zA-Z0-9]{36}` | `ghp_xxxx...` |
| 4 | GitLab Personal Access Token | `glpat-[a-zA-Z0-9_-]{20,}` | `glpat-xxxx...` |
| 5 | Anthropic API Key | `sk-ant-[a-zA-Z0-9_-]{20,}` | `sk-ant-xxxx...` |
| 6 | Slack Token | `xox[bpsar]-[a-zA-Z0-9-]{10,}` | `xoxb-xxxx...` |
| 7 | 通用凭证（key=value） | `api_key\|secret\|password\|token\|auth\|credential` | `api_key=xxx` |
| 8 | JWT Token | `eyJ...` 三段式 | `eyJhbGciOi...` |
| 9 | PEM 私钥头 | `-----BEGIN ... PRIVATE KEY-----` | RSA/EC/DSA/OPENSSH |
| 10 | 数据库连接串 | `mongodb\|postgres\|mysql\|redis://...` | `postgres://user:pass@host` |
```

**涉及文件**：
| 文件 | 修改内容 |
|------|----------|
| `backend/.../security/SensitiveDataFilter.java` | L11 Javadoc 修正：“6 种模式”→“10 种模式”，补全模式列表 |
| `docs/Claude_Code源码深度架构分析.md` | **新增**敏感数据过滤段落（当前文档无此独立章节），完整列出 10 种模式 |

---

### 6.4 UNI-7：前端暗色主题设置未接入主题系统（P3）

**问题回顾**：项目已拥有完整的主题系统：
- `ThemeProvider.tsx`（82行）— 已在 `main.tsx` L9-11 包裹 `<App/>`
- `ThemePicker.tsx`（110行）— 使用 `useConfigStore().setTheme()` 写入全局状态
- `configStore.ts`（176行）— Zustand + persist 中间件，`setTheme()`/`resetTheme()` 已实现
- `globals.css` L62-106 — 完整的 `.dark` CSS 变量定义（26+ 个变量）
- `tailwind.config.ts` — `darkMode: 'class'`

**真正 Bug**：`SettingsPanel.tsx` L98-121 定义了一个**本地** `ThemePicker` 函数，使用 `useState` 管理主题状态，而非导入已有的 `@/components/theme/ThemePicker` 组件。导致用户在设置面板中切换主题时，仅修改本地 `useState`，不会写入 `configStore`，因此刷新后主题丢失、且不触发 `ThemeProvider` 的 DOM class 切换。

```tsx
// SettingsPanel.tsx L97-121 — 当前问题代码
/** 主题切换 */
function ThemePicker() {
  const [theme, setTheme] = useState<'light' | 'dark' | 'system'>('system');
  // ★ 问题：本地 useState，不写入 configStore，不触发 ThemeProvider
  return (
    <div className="space-y-4">
      <h3 className="text-lg font-semibold">Theme</h3>
      <div className="flex gap-2">
        {(['light', 'dark', 'system'] as const).map((t) => (
          <button key={t} ... onClick={() => setTheme(t)}>
            {t.charAt(0).toUpperCase() + t.slice(1)}
          </button>
        ))}
      </div>
    </div>
  );
}
```

**修复方案**：

Step 1 — 在 `SettingsPanel.tsx` 顶部添加导入：

```tsx
// SettingsPanel.tsx — 添加导入
import { ThemePicker } from '@/components/theme/ThemePicker';
```

Step 2 — 删除 `SettingsPanel.tsx` L97-121 的本地 `ThemePicker` 函数定义（整个 `function ThemePicker() { ... }` 块）。

删除后，文件中引用 `<ThemePicker />` 的地方将自动解析为导入的 `@/components/theme/ThemePicker`，该组件已通过 `useConfigStore().setTheme()` 正确写入全局状态，支持：
- 主题模式切换（light/dark/system）
- 强调色选择（6 种预设色）
- 字体大小调整
- persist 中间件自动持久化到 localStorage
- BroadcastChannel 跨 Tab 同步

**涉及文件**：
| 文件 | 修改内容 |
|------|----------|
| `frontend/src/components/settings/SettingsPanel.tsx` | 删除 L97-121 本地 `ThemePicker` 函数，添加 `import { ThemePicker } from '@/components/theme/ThemePicker'` |

> **无需修改**：`ThemeProvider.tsx`、`ThemePicker.tsx`、`configStore.ts`、`globals.css`、`main.tsx`、`App.tsx` — 均已正确实现。

**验证方法**：
1. 打开设置面板 → 切换主题为 Dark → 验证页面立即切换为暗色模式
2. 刷新页面 → 验证主题状态持久化（configStore persist → localStorage）
3. 设置 "system" 模式 → 修改系统偏好 → 验证自动跟随
4. 打开新 Tab → 验证 BroadcastChannel 跨 Tab 同步
5. `npm run build` → 验证编译无错误

**回归风险**：极低。仅删除本地重复定义，改用已完整实现的全局组件。

---

## 第七章：验证与回归测试计划

### 7.1 Phase 1 验证清单

| 验证项 | 方法 | 通过标准 |
|--------|------|----------|
| ERR-3：sendHealthPing 有效性 | 停止 MCP SSE 服务端，等待 30s | SseHealthChecker 检测到 ping 失败，标记 DEGRADED，触发 scheduleReconnect |
| ERR-3：disconnectCallback 接线 | 模拟 SSE 事件流异常 | onFailure → disconnectCallback → 状态 FAILED → healthCheck 触发 attemptReconnect |
| ERR-3：双路径长时间运行 | 10 分钟长时间测试 | 主动 ping + 被动回调均能正确触发重连 |

### 7.2 Phase 2 验证清单

| 验证项 | 方法 | 通过标准 |
|--------|------|----------|
| INC-1：浏览器可用 | `python -m playwright install chromium` + `/api/health/capabilities` | BROWSER_AUTOMATION.available=true |
| INC-1：端点功能 | `POST /api/browser/screenshot` | 返回截图数据 |

### 7.3 Phase 3 验证清单

| 验证项 | 方法 | 通过标准 |
|--------|------|----------|
| UNI-1~5：能力域可用 | `/api/health/capabilities` | UNI-5 GIT_ENHANCED available=true；UNI-1~4 已降级为 BashTool 覆盖 |
| UNI-5：端点功能 | `/api/git/diff`, `/api/git/log`, `/api/git/blame` | 返回正确数据，无 500 错误 |
| 整体回归 | `pytest` | 所有已有测试通过 |

### 7.4 Phase 4 验证清单

| 验证项 | 方法 | 通过标准 |
|--------|------|----------|
| INC-5：环境变量可控 | `RESOURCE_MONITOR=true` 启动 | MonitorTool 注册成功 |
| INC-6：Actuator 端点 | `curl /actuator/health` | 返回 200 + status:UP |
| INC-7：文档准确性 | 人工审查 | 10 种模式完整列出 |
| UNI-7：暗色主题 | 设置面板切换主题 + 刷新 | 主题切换即时生效、刷新后持久化、跨 Tab 同步 |

### 7.5 回归测试范围

- **后端**：`mvn test`（全量单元测试 + 集成测试）
- **前端**：`npm run build` + `npm run test` + Playwright E2E
- **Python**：`pytest` + 各能力域端点冒烟测试

---

## 第八章：风险评估与应急预案

### 8.1 修复风险评估矩阵

| 问题编号 | 修改范围 | 影响面 | 风险等级 | 关键风险点 |
|----------|----------|--------|----------|------------|
| ERR-3 | 4 个文件新增方法 + 接线 | SSE 连接稳定性 | **低** | 新增方法，不修改现有 sendNotification 语义 |
| INC-1 | Dockerfile + 注释 | 部署层 | **低** | 仅部署变更，不修改业务代码 |
| UNI-1~5 | 新建 2 个 Python 文件 + 1 个 Java 文件 | Python 服务 + Java 工具 | **低** | 独立模块，不影响已有功能；UNI-1~4 降级为 BashTool 覆盖 |
| INC-5 | 1 个配置文件 + 1 处提示 | 工具注册 | **极低** | 仅新增配置项，默认 false |
| INC-6 | 配置文件补全 | Actuator 端点 | **极低** | 显式声明默认值 |
| INC-7 | Javadoc + 文档 | 无代码影响 | **极低** | 纯文档变更 |
| UNI-7 | 删除本地重复定义 + 添加 import | 前端设置面板 | **极低** | 改用已完整实现的全局组件 |

### 8.2 回滚策略

| 阶段 | 回滚方式 | 回滚时间 |
|------|----------|----------|
| Phase 1 | Git revert 对应 commit | < 5 分钟 |
| Phase 2 | Git revert + 确认编译通过 | < 10 分钟 |
| Phase 3 | 删除新建文件 + revert requirements.txt | < 5 分钟 |
| Phase 4 | Git revert | < 5 分钟 |

### 8.3 应急预案

**ERR-3 修复后 SSE 频繁断连**：
- 症状：sendHealthPing() 返回 false 导致误判为 DEGRADED
- 应急：revert SseHealthChecker 修改，暂时禁用主动 ping
- 根因排查：确认 MCP 服务端是否正确响应 notifications/ping

**ERR-3 disconnectCallback 误触发**：
- 症状：SSE 临时网络抖动导致 onFailure 误触发，状态频繁切换为 FAILED
- 应急：在 disconnectCallback 中添加去抖逻辑（连续 2 次失败才标记 FAILED）
- 根因排查：检查 OkHttp SSE 的 onFailure 触发条件

**Phase 3 Python 依赖冲突**：
- 症状：新安装的包与已有包版本冲突
- 应急：使用 `pip install --no-deps` 隔离安装，或创建独立虚拟环境
- 根因排查：执行 `pip check` 检测冲突

---

> **文档结束** — 本文档原始覆盖 17 项遗留问题，全部已完成：6 项首轮完成（ERR-1/2, INC-2/3/4, UNI-6），4 项降级为 BashTool 覆盖（UNI-1~4），7 项本轮执行完成（ERR-3 遗留 2 缺陷 + INC-1 + UNI-5 + INC-5/6/7 + UNI-7）。
