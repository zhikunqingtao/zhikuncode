# ZhikuCode 90%+ Claude Code 对标提升实施计划

> **文档版本**: v4.3（第十五轮源码级全量审查与修正）  
> **生成日期**: 2026-04-18  
> **基线数据**: 功能对标分析报告 (2026-04-17) + 测试报告 v3.1  
> **技术栈**: Java 21 + Spring Boot 3.3 + Caffeine Cache + Virtual Thread  
> **前端栈**: React 18 + TypeScript + Zustand + Vite 5.4  

---

## 一、总览

### 1.1 目标

将 ZhikuCode 与 Claude Code 的功能对标整体完整度从 **81.7% 提升至 93.5%+**。

### 1.2 六大模块当前→目标完整度

| 模块 | 当前完整度 | 目标完整度 | 增量 | 核心差距 |
|------|-----------|-----------|------|---------|
| Agent Loop | 90% | 93% | +3% | Token 预算 nudge UI 推送 |
| 工具系统 | 72% | 95% | +23% | 流式启动微调、工具排序缓存、contextModifier 传播 |
| 权限系统 | 93% | 93%+ | +0% | ~~远程熔断、规则遮蔽检测、企业策略覆盖均已删除~~（企业级功能，本地个人部署无需） |
| MCP集成 | 78% | 92%+ | +14% | 资源发现完整化、提示词UI（~~企业策略已删除~~） |
| 上下文管理 | 82% | 91%+ | +9% | Context Collapse增强、压缩后重建 |
| 多Agent协作 | 75% | 95%+ | +20% | Swarm完整实现、Coordinator四阶段、Agent类型化 |
| **整体** | **81.7%** | **93.5%** | **+11.8%** | |

### 1.3 重要说明：已实现功能的修正

源码审查发现以下功能已在测试报告 v3.1 中验证通过，对标报告中标记为"缺失"但实际已存在：

| 功能 | 对标报告标注 | 实际源码状态 | 文件 |
|------|------------|-------------|------|
| 工具并发分区 | 无 isConcurrencySafe | **已实现** — `isConcurrencySafe()` 判断 + 分区并行 | `StreamingToolExecutor.java` |
| Token预算续写 | 无 nudge 消息 | **已实现** — 完整的 nudge + 递减回报检测 | `TokenBudgetTracker.java` |
| Context Collapse | 无骨架化 | **已实现** — Level 1.5 基础版骨架化 | `ContextCollapseService.java` |
| SSE健康检查 | 无主动 ping | **已实现** — 30s 周期 ping + DEGRADED 标记 + 指数退避重连调度（McpClientManager.java L339-341 已实现 1s→2s→4s→8s→16s→30s cap） | `SseHealthChecker.java` |
| Coordinator基础 | 无实现 | **已实现** — 模式检测、工具过滤、Scratchpad | `CoordinatorService.java` |
| Swarm 服务 | 无实现 | **占位实现**（62行） — `createSwarm()` 和 `executeSwarm()` 均抛出 `UnsupportedOperationException`，含 TODO 注释（L47、L58），功能完全未实现，仅有门控检查框架 | `SwarmService.java` |

**结论**：实际完整度高于对标报告标注值（SSE健康检查、Coordinator基础等已完整实现）。但 SwarmService 为纯占位代码（两个公开方法均 throw UnsupportedOperationException），需完整重写。本计划聚焦于**真正缺失的功能增强**而非从零实现。

### 1.4 总工作量估算

| 优先级 | 工作项数 | 人天估算 | 周期 |
|--------|---------|---------|------|
| 最高（P0） | 6 项 | 14.5-16 人天 | Week 1-2 |
| 高（P1） | 5 项 | 40-42.5 人天 | Week 3-6 |
| 中（P2） | 3 项 | 16-18 人天 | Week 7-9 |
| 收尾 | 集成测试 | 5 人天 | Week 10 |
| **总计** | **14 项** | **75.5-83.5 人天** | **9-11 周** |

> **P0**：3.1(3) + 3.3(4.5) + 6.3(2-3) + 7.1(3-3.5) + 5.1(1.5) + 8.2.1(0.5) = 14.5-16 人天  
> **P1**：4.1(12) + 4.2(14-16) + 3.2(3.5) + 3.4(8) + 5.2(2.5-3) = 40-42.5 人天（4.2 工作量修正为 14-16 人天——3 个核心引擎类需从零新建 + 阶段检测提示词工程 + 假阳性处理；5.2 依赖 5.1 故安排在 P1 后半段）  
> **P2**：6.1(8-9) + 4.3(5) + 6.2(3-4) = 16-18 人天（6.1 从 P1 降为 P2——本地部署用户 99% 通过 Bash/FileRead 访问资源，工作量修正为 8-9 人天；6.2 工作量修正为 3-4 人天；~~6.4(8-10) 已删除~~；~~8.2(0.5-1) 已删除~~）  
> **后端总计**：60-68 人天 | **前端总计**：10.5 人天（并行） | **收尾测试**：5 人天（总计 = 后端 60-68 + 前端 10.5 + 收尾 5 = 75.5-83.5 人天）  
> **相比上版变更**：删除 6.4(-8~10天) 和 8.2(-0.5~1天)，6.1 从 P1 移入 P2，4.2 上调(+3~2天)，5.2 上调(+1~1.5天)，净减少约 13-22 人天

---

### 1.5 新架构独特价值总览（维度14 全局视角）

> **核心论点**：ZhikuCode 已从 Claude Code 的 TypeScript 终端架构完全迁移为 Java 21 + Spring Boot 3.3 + React 18 + Python 3.11 的浏览器架构，不应照搬 Claude Code 的方案，而应充分利用新架构的独特优势。以下为各方案维度14的全局指导原则。

#### 1.5.1 多端访问：PC 浏览器 + 手机浏览器

Claude Code 仅支持终端操作，ZhikuCode 天然支持多端访问：

| 能力 | Claude Code（终端） | ZhikuCode（浏览器） | 实现基础 |
|------|----------------|------------------|----------|
| PC 操控 | ✅ 终端命令行 | ✅ PC 浏览器富交互 | React 18 + TailwindCSS |
| 手机操控 | ❌ 无法支持 | ✅ 手机浏览器响应式访问 | TailwindCSS breakpoints + 触控适配 |
| 外出远程编程 | ❌ 需 SSH + 终端 | ✅ 手机访问本地服务 | Bridge 远程访问已实现 |
| 离线缓存 | ❌ | ✅ PWA Service Worker | **待实现**（P3可选）：需新增 manifest.json + Service Worker |

**实施要点**：
- **响应式布局**：TailwindCSS 已引入（`frontend/tailwind.config.ts`），各新建前端组件必须使用 `sm:`/`md:`/`lg:` 断点确保手机端可用性
- **触控交互适配**：按钮、对话框、下拉菜单的触控区域不小于 44x44px；`useVirtualKeyboard.ts` Hook 已存在，支持移动端软键盘布局适配
- **PWA 支持——待实现（P3 可选）**：需新增 `manifest.json` + Service Worker，支持添加到主屏幕、离线缓存静态资源、推送通知
- **工作量**：响应式适配 2 人天（融入各组件开发），PWA 1 人天（P3）

#### 1.5.2 模型无关架构

Claude Code 深度绑定 Anthropic API，ZhikuCode 已实现模型无关：

| 已有能力 | 源码位置 | 状态 |
|---------|---------|------|
| LLM Provider 抽象层 | `LlmProviderRegistry.java`（8.2KB） | ✅ 已实现，支持多 Provider 注册 |
| OpenAI 兼容端点 | `application.yml` L30（`llm.openai.base-url`） | ✅ 已配置，指向阿里云百炼 DashScope |
| 子代理模型别名 | `application.yml` L46-49（`agent.model-aliases`） | ✅ haiku/sonnet/opus 映射到千问系列 |
| 降级链 | `application.yml` L148-151（`app.model.tier-chain`） | ✅ qwen3.6-plus → qwen-plus → qwen-turbo（行号可能因版本更新变化，请参考 application.yml `app.model.tier-chain` 章节） |

> （注：上表中行号基于当前 application.yml 版本，后续代码变更可能导致行号漂移，请以 `llm.openai.base-url` / `agent.model-aliases` / `app.model.tier-chain` 配置键为准）
| API Key 轮换 | `ApiKeyRotationManager.java`（5.0KB） | ✅ 支持多 Key 轮换 |

**未来扩展**：用户可通过修改 `application.yml` 的 `llm.openai.base-url` 切换到 DeepSeek、本地 Ollama 等任意 OpenAI 兼容端点，无需修改代码。本计划所有方案均不依赖特定模型能力（如 `cache_control`），确保千问、DeepSeek 等模型均可正常运行。

> **注意事项**：3.2 方案中的 Prompt Cache 断点（`cache_control: { type: "ephemeral" }`）依赖 API Provider 支持。DashScope/千问当前不支持此特性，方案已备注“不支持时静默忽略”，确保兼容性。

#### 1.5.3 浏览器富交互优势

浏览器架构提供了终端架构无法实现的富交互能力：

| 能力 | 终端架构限制 | 浏览器架构优势 | 已有基础 |
|------|------------|------------|----------|
| 代码编辑 | 纯文本 diff | Monaco Editor 富编辑器（语法高亮、跳转、自动补全） | ✅ `@monaco-editor/react` 已引入 |
| 终端输出 | 纯文本滚动 | xterm.js 完整终端仿真（颜色、链接、滚动） | ✅ `@xterm/xterm` 已引入 |
| Markdown 渲染 | 无（纯文本） | react-markdown 富文本渲染（表格、代码块、链接） | ✅ `react-markdown` 已引入 |
| 文件拖放 | ❌ | 浏览器 Drag & Drop API | ✅ `FileUpload.tsx` 已存在 |
| 实时通知 | ❌ | Browser Notification API | 待集成 |
| 剪贴板 | 受限 | Clipboard API 富媒体复制 | 待集成 |
| 多标签页 | ❌ | 多会话并行 | ✅ SessionController 已支持 |

**WebSocket 通信技术栈**：后端使用 Spring WebSocket + STOMP 协议（`WebSocketConfig.java` L27-68，启用 `@EnableWebSocketMessageBroker`，支持 `/topic` 广播 + `/queue` 用户专属通道，心跳 10s，SockJS fallback）；前端使用 `@stomp/stompjs`（`package.json` 已引入 `^7.3.0`）通过 `stompClient.ts` 建立双向通信。这一架构支持多客户端同时连接、多标签页状态同步、手机浏览器远程访问等终端架构无法实现的能力。

**WebSocket 实时推送具体应用场景**（Claude Code 终端架构无法实现）：

| 场景 | 推送消息类型 | 描述 | 状态 |
|------|------------|------|------|
| Token 预算进度条 | `token_budget_update` | 实时展示当前 Token 消耗、剩余预算、nudge 状态 | 待实现 |
| Swarm Worker 状态 | `swarm_state_update` / `worker_progress` | 实时展示 Worker 执行进度、工具调用计数、Token 消耗 | 待实现（新增 #33-#34） |
| Context Collapse 进度 | `context_collapse_progress` | 显示压缩进度、已释放 Token 数、压缩级别 | 待实现 |
| 权限冒泡确认 | `permission_bubble` | Worker 需要用户确认的操作，带超时自动拒绝 | 待实现（新增 #35） |
| 工具执行指标 | `tool_execution_metrics` | 活跃 Virtual Thread 数、平均执行时间、队列深度 | 待实现 |

**多客户端同时连接能力** vs Claude Code 单终端：
- **PC 浏览器 + 手机浏览器同时连接**：STOMP user destination 按 sessionId 定向推送，支持同一用户多设备同时查看任务进度
- **多标签页状态同步**：不同会话独立管理，标签页间不干扰
- Claude Code 仅支持单终端连接，无法实现这些能力

#### 1.5.4 Spring Boot 生产级基础设施

相比 Claude Code 的 Node.js 运行时，Spring Boot 提供了开箱即用的生产级基础设施：

| 能力 | 当前状态 | 备注 |
|------|---------|------|
| 健康检查 | ✅ `/actuator/health` 已配置 | `spring-boot-starter-actuator` 已引入 |
| 指标监控 | ✅ `/actuator/metrics` + `/actuator/prometheus` | `micrometer-registry-prometheus` 已引入，可暴露自定义指标（如 `zhiku.tool.execution_time`、`zhiku.swarm.virtual_threads.active`） |
| 定时任务 | ✅ `@EnableScheduling` 已配置 | `Application.java` L17 |
| Virtual Thread | ✅ `spring.threads.virtual.enabled=true` | `application.yml` L9-10，Java 21 特性 |
| OpenAPI 文档 | ✅ `/swagger-ui.html` 已配置 | `springdoc-openapi` 已引入 |
| 声明式缓存 | ❌ `@EnableCaching` 未配置 | 需添加 `spring-boot-starter-cache`（远期升级路径） |
| AOP 切面 | ❌ `spring-boot-starter-aop` 未引入 | 需添加（远期升级路径） |

> **指导原则**：本计划各方案的维度14增强项均遵循“当前可用优先”原则——优先使用已有依赖（Caffeine、MicroMeter、Actuator、@Scheduled 等），将需要新增依赖的方案（@Cacheable、AOP、Spring Retry 等）标记为“升级路径（远期可选）”。

#### 1.5.5 与 Claude Code 终端架构的核心差异

| 维度 | Claude Code（TypeScript + 终端） | ZhikuCode（Java + React + 浏览器） | 优势方 |
|------|---------------------|-------------------------|--------|
| 并发模型 | 单线程 event loop | Virtual Thread（百万级并发） | ZhikuCode |
| 工具执行 | 异步回调 | Virtual Thread + CompletableFuture | ZhikuCode（可观测性更强） |
| 前端交互 | 终端 TUI（Ink React） | 浏览器富 UI（Monaco/xterm/Markdown） | ZhikuCode |
| 多端访问 | 仅本地终端 | PC + 手机 + 远程浏览器 | ZhikuCode |
| 实时通信 | stdio pipe | WebSocket STOMP（双向、多客户端） | ZhikuCode |
| 模型支持 | 绑定 Anthropic API | OpenAI 兼容端点（千问/DeepSeek/Ollama） | ZhikuCode |
| 可观测性 | 日志文件 | Actuator + MicroMeter + Prometheus | ZhikuCode |
| 部署形态 | npm 全局安装 | Docker / 本地 JAR | ZhikuCode（容器化就绪） |

> **核心结论**：维度14的真正价值不仅在于 Spring 框架特性，更在于浏览器架构带来的多端访问、富交互、模型无关等终端架构无法实现的能力。各方案的维度14增强项应优先发挥这些独特优势，而非照搬 Claude Code 的终端方案。

> **WebSocket STOMP 源码引用**：后端实现位于 `WebSocketConfig.java`（L27-68），配置了 `@EnableWebSocketMessageBroker`，支持 `/topic`（广播）+ `/queue`（用户专属）+ SockJS fallback，心跳 10s incoming + 10s outgoing。

---

## 二、实施优先级矩阵

### 2.1 ROI 排序表

| 排序 | 实施项 | 所属模块 | 功能提升 | 工作量 | ROI | 依赖 |
|------|--------|---------|---------|--------|-----|------|
| 1 | 流式工具启动（微调） | 工具系统 | +5% | 3天 | ★★★★★ | 无 |
| 2 | 工具排序与 Prompt Cache | 工具系统 | +4% | 3.5天 | ★★★★★ | 无 |
| 3 | Swarm 模式完整实现 | 多Agent | +13% | 12天 | ★★★★☆ | 无 |
| 4 | Coordinator 四阶段编排 | 多Agent | +7% | 14-16天 | ★★★☆☆ | #3 |
| 5 | Context Collapse 渐进增强 | 上下文 | +5% | 1.5天 | ★★★★★ | 无 |
| 6 | 压缩后关键文件重注入 | 上下文 | +4% | 2.5-3天 | ★★★★☆ | #5 |
| 7 | 资源发现完整实现 | MCP | +5% | 11-12天 | ★★☆☆☆ | 无 |
| 8 | 提示词发现 UI 集成 | MCP | +2% | 5-6天 | ★★★☆☆ | 无 |
| 9 | contextModifier 传播 | 工具系统 | +3% | 4.5天 | ★★★☆☆ | 无 |
| 10 | Token 预算 nudge UI | Agent Loop | +3% | 3-3.5天 | ★★★☆☆ | 无 |
| 11 | 主动健康检查增强 | MCP | +2% | 2-3天 | ★★★☆☆ | 无 |
| 12 | ~~远程熔断集成~~ | 权限 | ~~+2%~~ | ~~8天~~ | **已删除** | - |
| 13 | ~~企业策略覆盖~~ | 权限 | ~~+2%~~ | ~~0.5-1天~~ | **已删除** | - |
| 14 | ~~规则遮蔽检测~~ | 权限 | ~~+1%~~ | ~~7天~~ | **已删除** | - |
| 15 | Agent 类型扩展 | 多Agent | +3% | 5天 | ★★☆☆☆ | 无 |
| 16 | ~~企业策略支持(MCP)~~ | MCP | ~~+2%~~ | ~~8-10天~~ | **已删除** | - |
| 17 | 其他工具增强 | 工具系统 | +9% | 8天 | ★★☆☆☆ | 无 |

> **已删除方案说明**：
> - **6.4 MCP企业策略**（原 P2，8-10天）：allowlist/denylist/managedRulesOnly 是企业级功能，本地个人部署场景 ROI 极低（8-10人天投入，获益≈0）。
> - **8.2 企业策略覆盖**（原 P2，0.5-1天）：锁定模式是企业防止员工篡改规则的机制，个人用户不需要。
> - **资源发现(6.1)** 优先级从 P1 降为 P2：本地部署用户 99% 通过 Bash/FileRead 访问资源，前端 ResourcesPanel 价值有限。
> - **4.3 Agent类型扩展** ROI 说明：5 种 AgentDefinition 已在 SubAgentExecutor.java L885-903 完整定义，sealed interface 升级是**架构优化**而非功能修复，ROI 相应降低。
> - **5.2 压缩后重注入** 工作量从 1.5 天修正为 2.5-3 天：需修改 5-6 个文件、新建 KeyFileTracker、埋点 3 个工具。
> - **4.2 Coordinator** 工作量从 11-14 天修正为 14-16 天：基于实际缺失量（3 个核心引擎类需从零新建 + 前端 3 个组件全缺）。

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
[远程熔断集成]   │（已删除）
[企业策略覆盖]   │（已删除：企业级功能，个人部署无需）
[规则遮蔽检测]   │（已删除）
[企业策略MCP]   │（已删除：本地个人部署ROI≈0）
```

---

## 三、工具系统提升（72% → 95%）

> **审查修正说明**：原方案 3.4（shouldDefer 批处理）已删除——接口签名与实际代码不符，无明确使用场景，ROI 低。原 3.5 改为 3.4。

### 3.1 流式工具启动

**目标**：API 流式接收期间，每收到一个 tool_use block 立即启动执行，而非等待 API 完全接收后批量执行。

**当前状态**（源码审查修正）：
- ✅ **核心功能已基本实现**：`StreamCollector` 的 `flushToolBlock()` 已在 `QueryEngine.java`（StreamCollector 内部类，L809-946，其中 flushToolBlock 位于 L894-927）实现了流式工具块即时提交。
- `StreamingToolExecutor` 已实现并发分区执行，工具提交时机在 `QueryEngine.java` Step 3 中通过 StreamCollector 回调触发。
- 文件：`backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` (963行)
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
// QueryEngine.java StreamCollector 内部类（L809-946）— 已有回调机制：
// StreamCollector 在 SSE 流中每解析完一个 tool_use block 时，
// 通过 flushToolBlock()（L894-927）立即触发工具执行。
// 待优化：
// 1. 确保 ExecutionSession 在 API 调用前创建（而非调用后）
// 2. 优化回调时序：flushToolBlock() 在 MessageDelta 事件（QueryEngine.java L858-863）
//    或流完成 onComplete() 回调（L877-879）时触发，确保 tool_use block 的 JSON 参数完整
// 3. Step 5 变为：等待 session 中所有已提交工具完成（而非重新提交）
// ☢️ 注意：addTool() 始终使用初始 context，contextModifier 传播缺失 → 见方案 3.3 修复
// ☢️ 注意：方案 3.1 的完整生效依赖方案 3.3 的实现——流式启动的工具必须能获取到前序工具的 contextModifier 更新
```

**关键实现细节**：

- **线程安全**：`StreamingToolExecutor.ExecutionSession`（⚠️ ExecutionSession 为 StreamingToolExecutor.java 的**内部类**，非独立文件）已使用 `ConcurrentLinkedQueue` 和 `CopyOnWriteArrayList`，天然支持多线程 `addTool()`。
- **TOCTOU 微窗口说明**：`processQueue()` 中 `queue.peek()`（L108）与 `queue.poll()`（L111）之间存在极小的 TOCTOU（Time-of-Check-Time-of-Use）窗口。由于 `processQueue()` 仅由 `addTool()` 和工具完成回调触发（非任意并发），实际不会造成问题，但需知晓此设计约束。
- **contextModifier 传播缺失**：当前 `addTool()`（L90-94）始终使用调用方传入的原始 `context`，`TrackedTool.updatedContext`（L54）在工具执行后被赋值（L125）但**从未被后续工具消费**。此问题的完整修复方案见 **方案 3.3**。
- **错误处理**：若回调中工具查找失败（`findToolByName` 返回 null，QueryEngine.java L930-935），在 flushToolBlock 中调用 `session.addErrorResult()`（L918-920）添加 error result。
- **兼容性**：若未注册回调，退化为原有的"批量提交"模式，完全向后兼容。
- **取消传播**：`AbortContext` 的 abort 信号通过 `session.discard()`（L169-171）传播到所有已提交但未完成的工具。

**工作量**：3 人天（原估 4 人天，核心功能已实现，+1 人天用于监控指标和背压控制）  
**完整度影响**：工具系统 72% → 77%（+5%）  
**依赖项**：无  
**状态**：✅ 功能已基本实现，仅需微调  
**风险与注意事项**：
- SSE 流中 tool_use block 的 JSON 参数可能不完整，实际触发点为 `MessageDelta` 事件（QueryEngine.java L858，`flushToolBlock()` 在 L863 调用）或流完成时的 `onComplete()` 回调（L877-879），而非 `content_block_stop`。若 flushToolBlock 中工具查找失败（findToolByName 返回 null，L930-935），则调用 `session.addErrorResult()`（L918-920）添加错误结果。
- 测试需覆盖：单工具、多工具并行、工具执行失败、API 流中断等场景。

**新架构增强项（维度14）**：

> **说明**：当前后端流式启动核心机制已完整（StreamCollector 内部类，QueryEngine.java L809-946），以下为**后续增强**而非已实现功能。

- **Virtual Thread 监控指标**：通过 MicroMeter 暴露 `zhiku.tool.virtual_threads.active`、`zhiku.tool.execution_time` 等 Gauge/Timer 指标，对接 Actuator `/actuator/metrics` 端点，实现生产级可观测性。☸️ 当前未实现，以下为完整实现代码：

```java
// StreamingToolExecutor.java — 新增字段和构造器注入
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import java.util.concurrent.atomic.AtomicInteger;

private final MeterRegistry meterRegistry;
private final AtomicInteger activeVirtualThreads = new AtomicInteger(0);

public StreamingToolExecutor(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    // 注册活跃虚拟线程数 Gauge
    Gauge.builder("zhiku.tool.virtual_threads.active", activeVirtualThreads, AtomicInteger::get)
        .description("Active virtual threads executing tools")
        .register(meterRegistry);
}

// 在 processQueue() 的工具执行前后加入埋点：
Thread.ofVirtual().name("zhiku-tool-" + next.tool.getName()).start(() -> {
    activeVirtualThreads.incrementAndGet();
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        // ... 现有执行逻辑 ...
    } finally {
        sample.stop(Timer.builder("zhiku.tool.execution_time")
            .tag("tool", next.tool.getName())
            .description("Tool execution time")
            .register(meterRegistry));
        activeVirtualThreads.decrementAndGet();
        active.decrementAndGet();
        processQueue();
    }
});
```
- **背压控制**：活跃虚拟线程超过阈值（默认 50）时暂缓新工具提交，通过 `Semaphore(50)` 控制并发上限，Virtual Thread 在 `acquire()` 上挂起时不占用平台线程（~μs 级调度开销），Claude Code 终端架构无法实现此类细粒度线程调度。⚠️ 当前未实现，Semaphore 方案尚待补充
- **前端实时展示**：WebSocket 推送 `tool_execution_metrics` 消息到前端，包含活跃线程数、平均执行时间、队列深度等实时指标。**注意**：`tool_execution_metrics` 消息类型需在 `ServerMessage.java` 中新增定义
- **优先级**：高

---

### 3.2 工具排序与 Prompt Cache 优化

**目标**：内建工具在前按名称排序，MCP 工具在后按名称排序，两组之间放置缓存断点，最大化 Prompt Cache 命中率。

**当前状态**（源码审查修正）：
- ✅ **分组已实现**：`ToolRegistry.getEnabledToolsSorted()` 在 L144-157 实现了内建/MCP 分组（内建在前），但**两个列表内部未按 `Tool::getName()` 排序**，导致工具顺序不稳定，影响 Prompt Cache 命中率。
- 文件：`backend/src/main/java/com/aicodeassistant/tool/ToolRegistry.java`
- ❌ **缓存层未实现**：无 Caffeine 缓存，每轮重新分组。
- ❌ **组内名称排序未实现**：当前仅分组，未在组内按名称排序以最大化 Prompt Cache 命中率。
- ❌ **缓存失效未处理**：`unregisterByPrefix()` 后未触发缓存失效。
- ❌ 无 Prompt Cache 断点概念。

**目标状态**（Claude Code 基线）：
- `assembleToolPool()` 将内建工具按名称排序放前，MCP 工具按名称排序放后。
- 服务端在最后一个内建工具后放置 cache breakpoint，MCP 工具变更不影响内建工具的缓存。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/tool/ToolRegistry.java` — 基于现有 `getEnabledToolsSorted()` 增加 Caffeine 缓存层
2. `backend/src/main/java/com/aicodeassistant/service/PromptCacheBreakDetector.java` — **已存在**（114行，SHA-256 缓存断裂检测），需增强断点位置计算方法
3. `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptBuilder.java` — 工具定义段插入断点标记

核心代码变更：

**步骤 1：修复组内排序不稳定问题**

当前 `getEnabledToolsSorted()`（L144-157）仅分 built-in 和 mcp 两组，但组内顺序依赖 `ConcurrentHashMap` 遍历顺序，每次可能不同。必须在组内按名称排序：

```java
// ToolRegistry.java — 替换现有 getEnabledToolsSorted() (L144-157)
public List<Tool> getEnabledToolsSorted() {
    return getEnabledTools().stream()
        .sorted(Comparator.comparing((Tool t) -> t.isMcp() ? 1 : 0)
                .thenComparing(Tool::getName))
        .toList();
}
```

**步骤 2：增加 Caffeine 缓存层 + 完整实现代码**

```java
// ToolRegistry.java — 新增字段和方法
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;

// 缓存字段
private final Cache<String, List<Tool>> sortedToolCache = Caffeine.newBuilder()
    .maximumSize(100)
    .expireAfterWrite(Duration.ofMinutes(5))
    .build();

// 缓存入口方法（调用方使用此方法替代 getEnabledToolsSorted()）
public List<Tool> getEnabledToolsSortedCached(String sessionId) {
    String cacheKey = sessionId + "::" + computeToolSetHash();
    return sortedToolCache.get(cacheKey, k -> getEnabledToolsSorted());
}

// 工具集哈希 — 基于排序后工具名称列表的 SHA-256
private String computeToolSetHash() {
    String toolNames = getEnabledTools().stream()
        .map(Tool::getName)
        .sorted()
        .collect(Collectors.joining(","));
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(toolNames.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash).substring(0, 16); // 取前16位即可
    } catch (Exception e) {
        return String.valueOf(toolNames.hashCode());
    }
}

// 缓存失效 — 在工具注册/注销后调用
public void invalidateSortedCache() {
    sortedToolCache.invalidateAll();
    log.debug("Sorted tool cache invalidated");
}
```

**步骤 3：unregisterByPrefix() 末尾补充缓存失效调用**

```java
// ToolRegistry.java — unregisterByPrefix() (L125-136) 末尾补充
public int unregisterByPrefix(String prefix) {
    // ... 现有逻辑不变 ...
    if (!toRemove.isEmpty()) {
        log.info("Unregistered {} tools with prefix '{}'", toRemove.size(), prefix);
        invalidateSortedCache(); // ← 新增：确保工具注销后缓存一致性
    }
    return toRemove.size();
}
```

```java
// PromptCacheBreakDetector.java — 已存在（114行），实现 SHA-256 哈希检测缓存断裂
// 已有 CacheBreakResult record（L40-44）、sha256() 工具方法（L103-112）
// 需增强：在工具定义生成时标记 cache_control 断点位置
// 新增方法：computeBreakpointPosition(List<Tool> builtinTools, List<Tool> mcpTools) → int
```

**关键实现细节**：

- **判断内建/MCP**：通过 `Tool` 接口的 `isMcp()` 方法判断（`Tool.java` L196-198，默认返回 `false`，`McpToolAdapter` 覆写返回 `true`）。`ToolRegistry.getEnabledToolsSorted()` L149 已使用 `t.isMcp()` 分组。❗ 不要通过名称前缀 `mcp__` 判断——应始终使用 `tool.isMcp()` 接口方法。
- **缓存断点传递**：在构建 API 请求的 `tools` 数组时，将断点位置信息传递给 `PromptCacheBreakDetector`，由其在对应位置的 tool definition 上添加 `cache_control: { type: "ephemeral" }` 标记。
- **Caffeine 缓存**：工具排序结果使用 Caffeine 缓存，key 格式为 `sessionId + "::" + computeToolSetHash()`（`::` 分隔符避免 sessionId 和 hash 拼接歧义），避免每轮重新排序。
- **缓存失效**：`unregisterByPrefix()` 和 `McpClientManager.refreshTools()` 后调用 `invalidateSortedCache()` 触发缓存刷新。

**工作量**：3.5 人天（原估 3 人天，+1.5 人天用于 Spring Cache 集成和多层缓存）  
**完整度影响**：工具系统 77% → 81%（+4%）  
**依赖项**：无  
**状态**：✅ 分组已实现，组内排序 + 缓存优化待完成  
**风险与注意事项**：
- MCP 工具动态注册/注销后需触发缓存失效，已在方案中覆盖。
- API provider（如 DashScope/OpenAI）是否支持 `cache_control` 需确认，不支持时静默忽略。

**新架构增强项（维度14）**：

- **Caffeine 手工缓存（当前方案）**：直接使用已有的 Caffeine 依赖（`pom.xml` L163-167，含注释行）构建手工缓存（如 3.2 主体方案所示），单机部署场景下 μs 级响应，无需分布式缓存层。**注意**：当前 `pom.xml` 无 `spring-boot-starter-cache`，`Application.java` 无 `@EnableCaching`，因此 `@Cacheable`/`@CacheEvict` 注解不可用
- **升级路径（远期可选）**：若后续需要声明式缓存管理，可在 `pom.xml` 中添加 `spring-boot-starter-cache` + `Application.java` 添加 `@EnableCaching`，将手工 Caffeine 代码替换为 `@Cacheable("toolSorted")` + `@CacheEvict`，缓存配置集中管理。同时需在 `management.endpoints.web.exposure.include` 中添加 `caches` 以启用 `/actuator/caches` 监控端点
- **缓存预热**：实现 `CommandLineRunner` 在应用启动时预热常用工具排序结果，避免首次请求的冷启动延迟
- **优先级**：中

---

### 3.3 contextModifier 机制实现

**目标**：允许工具执行后通过 `contextModifier` 修改后续工具的上下文（如切换工作目录），但仅对非并发安全工具生效。

**当前状态**（源码审查修正）：
- ✅ **接口层已完成**：`ToolResult.java` 中 `withContextModifier()` 和 `getContextModifier()` 已实现（L66-77）。
- `ToolUseContext.java` 存在，但**自身不包含 modifier 回调字段**。modifier 支持由 `ToolResult.java` 通过 metadata 实现（`withContextModifier(UnaryOperator<ToolUseContext>)` 方法和 `getContextModifier()` 方法）。
- 🔴 **执行层传播完全缺失（关键 bug）**：`StreamingToolExecutor` 中虽然 `TrackedTool.updatedContext` 字段声明（L54）且在工具执行后赋值（L125），但**该 updatedContext 从未被后续工具消费**。在 `addTool()` 时仍使用初始 context，导致工作目录修改、权限变更等 contextModifier 效果无法传播到后续工具。**必须补充传播逻辑**。

**目标状态**（Claude Code 基线）：
- `ToolResult` 可返回 `contextModifier` 函数，在工具执行后修改 `ToolUseContext`。
- 仅对 `isConcurrencySafe() == false` 的工具生效——并发执行的工具不能互相修改上下文。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/tool/ToolResult.java` — 接口已完成，无需修改
2. `backend/src/main/java/com/aicodeassistant/tool/StreamingToolExecutor.java` — 补充分区间 `updatedContext` 传播逻辑
3. `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` — 接收更新后的 context

核心实现：

**步骤 1：ExecutionSession 新增 AtomicReference 字段**

```java
// StreamingToolExecutor.java — ExecutionSession 内部类新增字段
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

public class ExecutionSession {
    private final Queue<TrackedTool> queue = new ConcurrentLinkedQueue<>();
    private final List<TrackedTool> tracked = new CopyOnWriteArrayList<>();
    private final AtomicInteger active = new AtomicInteger(0);
    private volatile boolean sessionDiscarded = false;

    // ← 新增：会话级当前上下文，支持 CAS 更新
    private final AtomicReference<ToolUseContext> currentContext;

    public ExecutionSession(ToolUseContext initialContext) {
        this.currentContext = new AtomicReference<>(initialContext);
    }
    // ...
}
```

**步骤 2：addTool() 使用 currentContext 而非原始 context**

```java
// StreamingToolExecutor.java — 修改 addTool()（原 L90-94）
public void addTool(Tool tool, ToolInput input, String toolUseId, ToolUseContext context) {
    // 使用会话级 currentContext 而非调用方传入的原始 context
    ToolUseContext effectiveContext = currentContext.get();
    TrackedTool tt = new TrackedTool(toolUseId, tool, input, effectiveContext);
    tracked.add(tt);
    queue.add(tt);
    processQueue();
}
```

**步骤 3：processQueue() 中补充 contextModifier 传播逻辑（CAS 循环）**

```java
// StreamingToolExecutor.java — 在 processQueue() 的工具完成回调中（finally 块前）补充
Thread.ofVirtual().name("zhiku-tool-" + next.tool.getName()).start(() -> {
    try {
        // ... 现有执行逻辑不变（L116-126）...

        // ← 新增：applyContextModifier 传播
        if (next.updatedContext != null && !next.tool.isConcurrencySafe(next.input)) {
            applyContextModifier(next.updatedContext);
        } else if (next.updatedContext != null && next.tool.isConcurrencySafe(next.input)) {
            log.warn("Tool '{}' is concurrencySafe but returned contextModifier — ignored",
                    next.tool.getName());
        }

        next.state = ToolState.COMPLETED;
    } catch (Exception e) {
        // ... 现有错误处理不变 ...
    } finally {
        active.decrementAndGet();
        processQueue();
    }
});
```

**步骤 4：applyContextModifier() CAS 循环实现**

```java
// StreamingToolExecutor.java — ExecutionSession 新增方法
/**
 * 通过 CAS 循环安全地更新会话级上下文。
 * 若多个非并发安全工具顺序完成，CAS 保证每次更新基于最新状态。
 * 
 * 超时保护：最多重试 100 次 CAS，防止异常场景下无限循环。
 * 实际场景中 CAS 通常 1-2 次即成功（因非并发安全工具顺序执行）。
 */
private void applyContextModifier(ToolUseContext newContext) {
    int maxRetries = 100;  // CAS 超时保护
    int attempt = 0;
    ToolUseContext prev;
    do {
        if (++attempt > maxRetries) {
            log.error("CAS loop exceeded {} retries for contextModifier, using last known context", maxRetries);
            currentContext.set(newContext);  // 强制设置，避免卡死
            return;
        }
        prev = currentContext.get();
    } while (!currentContext.compareAndSet(prev, newContext));
    log.debug("Context updated via contextModifier (CAS attempts: {})", attempt);
}

/**
 * 获取当前会话级上下文（供 QueryEngine 在工具全部完成后获取最新 context）。
 */
public ToolUseContext getCurrentContext() {
    return currentContext.get();
}
```

**步骤 5：QueryEngine 调整——传入 initialContext 并在完成后获取更新**

> **重要**：当前 `StreamingToolExecutor.newSession()` 为无参方法（L75-76），`ExecutionSession` 为无参构造器。步骤 1 中新增的 `ExecutionSession(ToolUseContext initialContext)` 构造器将**替换现有无参构造器**，同时 `newSession()` 方法签名需改为 `newSession(ToolUseContext initialContext)`，内部调用新构造器。

```java
// StreamingToolExecutor.java — 修改 newSession() 签名（原 L75-76 无参版本）
public ExecutionSession newSession(ToolUseContext initialContext) {
    return new ExecutionSession(initialContext);
}

// QueryEngine.java — Step 3 创建 session 时传入初始 context
ExecutionSession session = streamingToolExecutor.newSession(toolUseContext);
// ... 流式执行 ...
// Step 5 完成后获取更新后的 context
toolUseContext = session.getCurrentContext();
```

**关键实现细节**：

- **线程安全**：`currentContext` 使用 `AtomicReference<ToolUseContext>` 存储，`applyContextModifier()` 通过 CAS 循环执行，保证多工具顺序完成时的状态一致性。
- **并发安全工具限制**：若 `isConcurrencySafe() == true` 的工具返回了 modifier，记录 `WARN` 日志但不应用——防止并行工具之间的上下文竞争。
- **向后兼容**：现有工具不返回 modifier（`ToolExecutionPipeline` 阶段 7 提取 modifier 并产生 `updatedContext`，L252-258），`updatedContext` 为 null 时不触发任何传播，行为不变。
- **传播链路**：`Tool.call()` → `ToolResult.withContextModifier()` → `ToolExecutionPipeline` 阶段 7 提取 → `ToolExecutionResult.updatedContext()` → `StreamingToolExecutor.applyContextModifier()` CAS 更新 → 后续 `addTool()` 使用新 context。

**工作量**：4.5 人天（含传播逻辑实现、测试及并发安全性验证）  
**完整度影响**：工具系统 81% → 84%（+3%）  
**依赖项**：无  
**状态**：✅ 接口层完成，执行层传播待补充  
**风险与注意事项**：`ToolResult` record 接口已存在（L66-77），`ToolExecutionPipeline` 阶段 7（L252-258）已实现 modifier 提取与 `updatedContext` 产生。主要工作量在 `StreamingToolExecutor.ExecutionSession` 的改造（新增 AtomicReference 字段、修改 addTool/processQueue、新增 applyContextModifier）。

**测试方案**：

| 测试场景 | 验证点 | 预期结果 |
|---------|--------|----------|
| 单工具 modifier | BashTool `cd /tmp` 返回 modifier 修改 workDir | 后续工具的 `context.workingDirectory()` 为 `/tmp` |
| 多工具顺序 modifier | 工具A修改 workDir，工具B读取 | 工具B 看到工具A 的修改 |
| 并发安全工具忽略 | isConcurrencySafe=true 的工具返回 modifier | WARN 日志，modifier 不应用，上下文不变 |
| 无 modifier 兼容 | 所有现有工具（无 modifier） | 行为与修改前完全一致 |
| CAS 竞争压测 | 多个非并发工具快速顺序完成 | 最终 context 为所有 modifier 顺序应用的结果 |

**新架构增强项（维度14）**：

- **当前推荐方案**：直接在 `StreamingToolExecutor.ExecutionSession` 中补充 AtomicReference 字段 + `applyContextModifier()` CAS 方法 + `addTool()`/`processQueue()` 传播逻辑（如 3.3 主体方案所述），无需引入额外依赖，实现简单且可控。Virtual Thread 在 CAS 自旋时不占用平台线程（Java 21 优势）
- **升级路径：Spring AOP 切面自动应用 modifier**（远期可选）：`@After("execution(* Tool.execute(..))")` 切面自动检测 `ToolResult` 中的 contextModifier 并应用。**前置依赖**：需在 `pom.xml` 中添加 `spring-boot-starter-aop`（当前未配置），添加后可减少手动调用传播逻辑
- **Spring Event 驱动**（远期可选）：工具完成时发布 `ToolCompletedEvent`，`ContextModifierListener` 通过 `@EventListener` 异步订阅处理，解耦工具执行与上下文修改。**注意**：`@EventListener` 由 Spring Core 提供（已有），无需额外依赖；`@Async` 异步处理需 `@EnableAsync`（当前未配置）
- **`AsyncContextModifier` 接口**：支持异步 modifier（返回 `CompletableFuture<ToolUseContext>`），利用 Virtual Thread 执行耗时 modifier（如异步文件状态检查、工作目录切换后的文件索引刷新），Claude Code 同步 TypeScript 架构做不到
- **优先级**：中

---

### 3.4 其他工具增强

以下为工具系统的补充增强项，合并实施：

> **源码确认**：`Tool.java` 三方法已存在且为 default 空实现——`searchHint()`（L169）、`mapToolResult()`（L180）、`preparePermissionMatcher()`（L191）。`@EnableScheduling` 已在 Application.java L17 配置。`micrometer-registry-prometheus` 已在 pom.xml L140-143（含 `<dependency>` 开标签）引入。

| 增强项 | 涉及文件 | 工作量 | 描述 |
|--------|---------|--------|------|
| mapToolResult 实现 | `Tool.java`, 各工具类 | 1天 | 将空实现替换为实际的结果映射（如 FileReadTool 返回行号标注） |
| preparePermissionMatcher 子命令级 | `PermissionRuleMatcher.java` | 2天 | BashTool 子命令级权限匹配（如 `Bash(npm install:*)` 匹配 `npm install --save`） |
| BashTool flag 级验证 | `tool/bash/BashCommandClassifier.java` | 3天 | 只读白名单的 flag 值类型验证（'none'/'number'/'string'/specific） |

**总工作量**：8 人天（原 6 人天，+2 人天用于 YAML 配置化和指标集成）  
**完整度影响**：工具系统 84% → 95%（+11%，含 BashTool 安全提升）

**新架构增强项（维度14）**：

- **YAML 配置化 BashTool 权限规则（当前推荐）**：使用 `bash-permissions.yml` + Jackson `ObjectMapper` 解析权限规则，支持按命令前缀和参数模式匹配（如 `allowPrefix: [npm, git, python]`）。通过 `@Scheduled` 定期扫描文件变更（默认每 30s，`@EnableScheduling` 已配置），检测到变更时直接调用 Caffeine `invalidateAll()` 刷新规则缓存，无需重启即可生效
- **安全性增强（重要）**：
  - **`sudo`/`su`/`doas` 必须加入 `DESTRUCTIVE_COMMANDS` 禁用清单**：当前 `BashTool.java` L46-50 的 `DESTRUCTIVE_COMMANDS` 包含 20 个命令（rm/rmdir/chmod/chown/mkfs/dd/shred/truncate/wipefs/fdisk/parted/kill/killall/pkill/reboot/shutdown/halt/poweroff/init/systemctl），但**不含 `sudo`、`su`、`doas`**，可能导致模型通过特权提升命令执行危险操作（如 `sudo rm -rf /`）。修复代码：
    ```java
    // BashTool.java L46-50 — 在 DESTRUCTIVE_COMMANDS 中新增特权提升命令：
    private static final Set<String> DESTRUCTIVE_COMMANDS = Set.of(
        "rm", "rmdir", "chmod", "chown", "mkfs", "dd",
        "shred", "truncate", "wipefs", "fdisk", "parted",
        "kill", "killall", "pkill", "reboot", "shutdown",
        "halt", "poweroff", "init", "systemctl",
        "sudo", "su", "doas"  // ← 新增：禁止特权提升命令
    );
    ```
  - **链式命令安全检查——现有 AST 解析已覆盖**：`BashSecurityAnalyzer.parseForSecurity()` 使用 `bashlex`（专业 Bash AST 解析器）解析命令，`&&`/`||`/`;` 等链式操作符在 AST 层面被正确分割为独立的 `Command` 条目，`simple.commands()` 已包含所有子命令。现有 `isDestructive()` L291-295 通过 `simple.commands().stream().anyMatch(...)` 遍历检查每个子命令的 `argv[0]`，**已正确覆盖链式命令场景**（如 `echo hello && rm -rf /` 会被解析为两个独立命令，`rm` 的 `argv[0]` 会被检出）。无需额外修改
  - **`env` 命令信息泄露风险**：`env` 无参数执行时会输出所有环境变量（可能包含 API Key、数据库密码等敏感信息），属于信息泄露风险。但 `env` 同时是合法的命令前缀（如 `#!/usr/bin/env python3`），不应加入 `DESTRUCTIVE_COMMANDS`（否则会破坏大量正常命令）。**推荐方案**：在 `BashCommandClassifier` 中新增 `SENSITIVE_INFO_COMMANDS = Set.of("env", "printenv", "set")` 集合，当命令为 `env`（无参数）或 `printenv` 时，标记为 `SENSITIVE_INFO_DISCLOSURE` 风险级别，触发权限确认而非直接拒绝。对于 `env <cmd>` 形式的包装命令，`bashlex` AST 解析器的包装命令剥离机制（BashTool.java L45 注释所述）会正确提取被包装的实际命令进行安全检查
- ~~**SpEL 配置化**~~：**降为远期可选**——个人用户极少编写复杂动态条件表达式，YAML 配置已满足绝大多数场景
- **MicroMeter 基础指标（P3 可选）**：通过已有的 `micrometer-registry-prometheus`（`pom.xml` L140-143）暴露 `zhiku.tool.{name}.execution_time`（Timer）和 `zhiku.tool.{name}.error_count`（Counter）基础指标，通过 `/actuator/metrics` 查看。~~Grafana 面板~~：**已删除**——个人 AI 编程助手用户不会搭建 Grafana 监控，`/actuator/metrics` + `/actuator/prometheus` 端点已足够
- **结构化结果映射**：`mapToolResult` 使用 Jackson `ObjectMapper` + 自定义 `JsonSerializer` 实现结构化结果映射，替代硬编码字符串拼接
- **优先级**：中高（YAML 配置化为高，MicroMeter 指标为 P3）

---

## 四、多Agent协作提升（75% → 95%+）

> **审查修正说明**：原方案 4.3（外部进程后端）已删除——TeammateBackend sealed interface 等均不存在，工作量大（8人天），启动开销大，ROI 低。原 4.4 改为 4.3。

### 4.1 Swarm 模式完整实现

**目标**：将 `SwarmService` 从占位实现升级为完整的多 Agent 并行协作系统。

**当前状态**：
- `SwarmService.java`（62行）：**占位实现**——两个公开方法均抛出 `UnsupportedOperationException`：
  - `createSwarm(Object)` L44-48：门控检查后直接 throw
  - `executeSwarm(String)` L55-59：门控检查后直接 throw
- ❗ **存在 TODO 注释**：L47 `// TODO: Swarm 创建逻辑（待后续迭代实现）`、L58 `// TODO: Swarm 执行逻辑（待后续迭代实现）`，实施时必须全部清除并替换为实际逻辑。
- `TeamManager.java`（4.3KB）已有 CRUD 框架。
- `TeamMailbox.java`（110行 / 3.5KB）邮箱系统基础框架已建立，基于 `ConcurrentLinkedQueue`（L26）实现线程安全消息队列。支持 `writeToMailbox()`/`readMailbox()`（drain 方式）/`broadcast()`/`clearMailbox()` 操作，消息 DTO 为 `MailMessage` record。
- `InProcessBackend.java`（128行 / 5.5KB）进程内后端已实现 Virtual Thread 并发执行（L46-107）。并发模式：`maxConcurrency > 0` 时使用 `newFixedThreadPool(maxConcurrency, Thread.ofVirtual().factory())`（L57），否则使用 `newThreadPerTaskExecutor(Thread.ofVirtual().factory())`（L58）。
- Feature flag `ENABLE_AGENT_SWARMS` 存在但功能未实现（`FeatureFlagService.isEnabled("ENABLE_AGENT_SWARMS")`）。
- `ToolUseContext` record（82行）采用 `with*()` 建造者模式（`withNestingDepth()`/`withParentSessionId()`/`withAgentHierarchy()`/`withCurrentTaskId()`/`withWorkingDirectory()`/`withPermissionNotifier()`），**无 `forWorker()` 工厂方法**。
- `QueryEngine.execute()` 签名为 `execute(QueryConfig, QueryLoopState, QueryMessageHandler)`，**完全匹配方案需求**，无需重载。Worker 通过构造独立的 `QueryConfig`（含过滤后工具集）和 `QueryLoopState`（含独立消息历史）即可复用现有引擎，无需 `(taskPrompt, filteredTools, workerContext, workerPermission)` 形式的重载。
- ❗ 以下 4 个新建类**完全不存在**：SwarmWorkerRunner.java、SwarmState.java、LeaderPermissionBridge.java、SwarmConfig.java。

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
    String workerModel,       // Worker 使用的模型（如 "qwen-plus"），null 时继承 Leader 模型
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

// SwarmWorkerRunner.java — 进程内 Worker 执行引擎（**需新建**）
// 对标 inProcessRunner.ts (~1400行)
@Service
public class SwarmWorkerRunner {
    
    private final QueryEngine queryEngine;
    private final ToolRegistry toolRegistry;
    private final LeaderPermissionBridge permissionBridge;
    
    /**
     * 在 Virtual Thread 中启动一个 Worker Agent。
     * Worker 复用 QueryEngine.execute() 执行引擎，但使用独立的：
     * - 工具集（过滤后）
     * - 权限模式（降级后）
     * - 上下文（隔离的 QueryLoopState）
     */
    // Virtual Thread Executor — 每个 Worker 一个虚拟线程
    // 注意：InProcessBackend.java L57-58 使用 newFixedThreadPool + virtual factory 做有界并发；
    // 此处使用 newThreadPerTaskExecutor 做无界并发，Worker 数量由 SwarmConfig.maxWorkers 控制。
    private final ExecutorService workerExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("swarm-worker-", 0).factory());

    public CompletableFuture<WorkerResult> startWorker(
            String workerId, 
            String taskPrompt,
            SwarmConfig config,
            ToolUseContext parentContext) {
        return CompletableFuture.supplyAsync(() -> {
            return executeWorkerLoop(workerId, taskPrompt, config, parentContext);
        }, workerExecutor);
    }
    
    /**
     * Worker 执行主循环（核心方法）。
     * 复用 QueryEngine.execute(QueryConfig, QueryLoopState, QueryMessageHandler)，
     * 但使用隔离的工具集、权限和上下文。
     * 
     * 注意：QueryEngine.execute() 的真实签名为：
     *   execute(QueryConfig config, QueryLoopState state, QueryMessageHandler handler)
     * 不存在 (taskPrompt, filteredTools, workerContext, workerPermission) 形式的重载。
     */
    private WorkerResult executeWorkerLoop(
            String workerId, String taskPrompt, 
            SwarmConfig config, ToolUseContext parentContext) {
        // 1. 构建 worker 工具集（应用 allowList/denyList 过滤）
        //    注意：ToolRegistry 无 filterTools() 方法，使用 getEnabledTools() + stream 过滤
        var allTools = toolRegistry.getEnabledTools();
        var filteredTools = allTools.stream()
            .filter(t -> config.workerToolAllowList().isEmpty() 
                    || config.workerToolAllowList().contains(t.getName()))
            .filter(t -> !config.workerToolDenyList().contains(t.getName()))
            .toList();
        
        // 2. 构建 worker 上下文（独立消息历史 + scratchpad）
        //    ToolUseContext 无 forWorker() 方法，需新增或使用 with*() 链式构建：
        var workerContext = parentContext
            .withNestingDepth(parentContext.nestingDepth() + 1)
            .withCurrentTaskId(workerId)
            .withParentSessionId(parentContext.sessionId())
            .withAgentHierarchy(parentContext.agentHierarchy() + "/" + workerId)
            .withWorkingDirectory(config.scratchpadDir().toString());
        
        // 3. 构建 worker 权限（降级为 BUBBLE 模式，通过 LeaderPermissionBridge 冒泡）
        //    preserveMode: true 防止 Worker 的权限变更泄漏回 Leader
        
        // 4. 构建 QueryConfig（复用现有引擎签名）
        //    注意：QueryConfig 是 record 类型，无 builder() 方法，直接使用构造器
        var workerConfig = new QueryConfig(
            config.workerModel() != null ? config.workerModel() : "qwen-plus",  // model
            null,                                                               // fallbackModel
            buildWorkerSystemPrompt(taskPrompt, config),                         // systemPrompt
            filteredTools,                                                       // tools
            toolRegistry.getToolDefinitions(filteredTools),                       // toolDefinitions
            16384,                                                              // maxTokens
            QueryConfig.DEFAULT_MAX_TOKENS * 8,                                 // contextWindow
            null,                                                               // thinkingConfig
            30,                                                                 // maxTurns
            "swarm_worker",                                                     // querySource
            null,                                                               // tokenBudget
            List.of()                                                           // modelTierChain
        );
        
        // 5. 构建隔离的 QueryLoopState（独立消息历史）
        //    注意：QueryLoopState 构造器为 (List<Message>, ToolUseContext)
        var workerState = new QueryLoopState(new ArrayList<>(), workerContext);
        workerState.addMessage(new Message.UserMessage(
            UUID.randomUUID().toString(), Instant.now(),
            List.of(new ContentBlock.TextBlock(taskPrompt)), null, null));
        
        // 6. 执行查询循环
        var workerHandler = new WorkerMessageHandler(workerId, permissionBridge);
        QueryResult result = queryEngine.execute(workerConfig, workerState, workerHandler);
        
        // 7. 包装结果
        return new WorkerResult(workerId, result.lastAssistantText(), 
            result.turnCount(), result.usage().totalTokens());
    }
    
    /**
     * Worker 并发写文件保护 — FileChannel.lock() 串行化。
     * 多个 Worker 同时编辑同一文件时，通过文件锁防止内容损坏。
     */
    private void writeFileWithLock(Path filePath, String content) throws IOException {
        try (FileChannel channel = FileChannel.open(filePath, 
                StandardOpenOption.WRITE, StandardOpenOption.CREATE);
             FileLock lock = channel.lock()) {  // 排他锁，阻塞等待
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);  // 强制刷盘
        }
        // Scratchpad 目录（config.scratchpadDir()）下的文件无需加锁
    }
}

// LeaderPermissionBridge.java — Leader 权限桥接（**需新建**）
// Worker 需要确认的操作通过此桥接冒泡到 Leader 的 UI
@Service
public class LeaderPermissionBridge {
    
    /** 默认超时时间（秒）— 超时后自动拒绝，防止死锁 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    
    private final WebSocketController webSocketController;
    
    /** 请求 ID → CompletableFuture，前端决策后 complete */
    private final ConcurrentHashMap<String, CompletableFuture<PermissionDecision>> 
        pendingRequests = new ConcurrentHashMap<>();
    
    /**
     * 将 Worker 的权限请求冒泡到 Leader 的 WebSocket 连接。
     * Worker badge 标识来源。
     * 
     * 死锁防护：使用 CompletableFuture.orTimeout() 设置超时，
     * 超时后自动拒绝并清理 pending 请求。
     */
    public CompletableFuture<PermissionDecision> requestPermission(
            String workerId, 
            String sessionId,
            PermissionContext permContext) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<PermissionDecision> future = new CompletableFuture<PermissionDecision>()
            .orTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                // 超时或异常 → 自动拒绝
                pendingRequests.remove(requestId);
                log.warn("Permission request timed out for worker {}: {}", workerId, ex.getMessage());
                return PermissionDecision.DENY;  // 安全默认：拒绝
            });
        
        pendingRequests.put(requestId, future);
        
        // 通过 WebSocket 发送权限请求到前端（使用新增的 PermissionBubble 消息类型）
        // ❗ 注意：pushToUser 实际签名为 (String sessionId, String type, Object payload)，需三个参数
        webSocketController.pushToUser(sessionId, "permission_bubble",
            new ServerMessage.PermissionBubble(requestId, workerId, 
                permContext.toolName(), permContext.riskLevel(), permContext.reason()));
        
        return future;
    }
    
    /**
     * 前端用户决策回调 — 由 WebSocketController 调用。
     */
    public void resolvePermission(String requestId, PermissionDecision decision) {
        CompletableFuture<PermissionDecision> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(decision);
        }
    }
}
```

**SwarmService 完整重写核心逻辑**：

```java
// SwarmService.java — 完整重写（替换当前 62 行占位实现）
// 清除所有 TODO 注释和 UnsupportedOperationException
@Service
public class SwarmService {
    
    private static final Logger log = LoggerFactory.getLogger(SwarmService.class);
    
    private final FeatureFlagService featureFlags;
    private final TeamManager teamManager;
    private final TeamMailbox teamMailbox;
    private final SwarmWorkerRunner workerRunner;
    // ❗ 内存泄漏防护：使用 Caffeine 带 TTL 替代普通 ConcurrentHashMap
    // 防止异常终止的 Swarm 残留在内存中
    private final Cache<String, SwarmState> activeSwarms = Caffeine.newBuilder()
        .maximumSize(50)
        .expireAfterWrite(Duration.ofHours(4))  // 4小时后自动清理
        .removalListener((key, value, cause) -> {
            if (cause.wasEvicted()) {
                log.warn("Swarm {} evicted from activeSwarms (cause: {})", key, cause);
            }
        })
        .build();
    
    // 1. createSwarm(SwarmConfig) → SwarmState
    public SwarmState createSwarm(SwarmConfig config) {
        ensureSwarmEnabled();
        String swarmId = "swarm-" + UUID.randomUUID().toString().substring(0, 8);
        // 创建 TeamManager 条目
        // 注意：TeamManager.createTeam() 签名为 (String teamName, int workerCount, String sessionId)
        teamManager.createTeam(config.teamName(), config.maxWorkers(), swarmId);
        // 初始化 Scratchpad 目录
        Files.createDirectories(config.scratchpadDir());
        // 注册 SwarmState
        SwarmState state = new SwarmState(swarmId, config.teamName(), 
            SwarmState.SwarmPhase.INITIALIZING, new ConcurrentHashMap<>(), Instant.now());
        activeSwarms.put(swarmId, state);
        log.info("Swarm created: {} (team={}, maxWorkers={})", swarmId, config.teamName(), config.maxWorkers());
        return state;
    }
    
    // 2. addWorker(swarmId, taskPrompt, parentContext) → workerId
    public String addWorker(String swarmId, String taskPrompt, 
                            SwarmConfig config, ToolUseContext parentContext) {
        ensureSwarmEnabled();
        SwarmState state = activeSwarms.getIfPresent(swarmId);
        if (state == null) throw new IllegalStateException("Swarm not found: " + swarmId);
        if (state.workers().size() >= config.maxWorkers()) {
            throw new IllegalStateException("Max workers reached: " + config.maxWorkers());
        }
        String workerId = swarmId + "-worker-" + state.workers().size();
        // 通过 SwarmWorkerRunner.startWorker() 启动
        CompletableFuture<WorkerResult> future = workerRunner.startWorker(
            workerId, taskPrompt, config, parentContext);
        // 注册到 worker 状态表
        state.workers().put(workerId, new SwarmState.WorkerState(
            workerId, SwarmState.WorkerStatus.STARTING, 0, 0, taskPrompt, List.of()));
        future.thenAccept(result -> onWorkerComplete(swarmId, workerId, result));
        return workerId;
    }
    
    // 3. sendToWorker(swarmId, workerId, message) → void
    public void sendToWorker(String swarmId, String workerId, String message) {
        ensureSwarmEnabled();
        teamMailbox.writeToMailbox(workerId, swarmId + "-leader", message);
    }
    
    // 4. broadcastToWorkers(swarmId, message) → void
    public void broadcastToWorkers(String swarmId, String message) {
        ensureSwarmEnabled();
        teamMailbox.broadcast(swarmId, swarmId + "-leader", message);
    }
    
    // 5. shutdownSwarm(swarmId) → void
    public void shutdownSwarm(String swarmId) {
        ensureSwarmEnabled();
        SwarmState state = activeSwarms.getIfPresent(swarmId);
        if (state == null) return;
        // 发送 shutdown_request 给所有 worker
        broadcastToWorkers(swarmId, "{\"type\": \"shutdown_request\"}");
        // 等待 shutdown_response（超时 30s 后强制终止）
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(30_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            activeSwarms.remove(swarmId);
            teamMailbox.clearAll();  // 清理资源
            log.info("Swarm shutdown: {}", swarmId);
        });
    }
    
    // 6. getSwarmState(swarmId) → SwarmState
    public SwarmState getSwarmState(String swarmId) {
        ensureSwarmEnabled();
        return activeSwarms.getIfPresent(swarmId);
    }
    
    // 门控检查（复用现有模式）
    private void ensureSwarmEnabled() {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            throw new IllegalStateException("Agent Swarms feature is disabled.");
        }
    }
    
    private void onWorkerComplete(String swarmId, String workerId, WorkerResult result) {
        SwarmState state = activeSwarms.getIfPresent(swarmId);
        if (state != null) {
            state.workers().put(workerId, new SwarmState.WorkerState(
                workerId, SwarmState.WorkerStatus.IDLE, 
                result.toolCallCount(), result.tokenUsed(), "completed", List.of()));
        }
    }
}
```

**关键实现细节**：

- **Worker 隔离**：每个 Worker 运行在独立的 Virtual Thread 中，拥有独立的 `QueryLoopState`、工具上下文、消息历史。上下文通过 `ToolUseContext.with*()` 链式构建隔离复制（见 SwarmWorkerRunner.executeWorkerLoop() 代码）。通过 `AgentConcurrencyController` 的现有限制（全局30/会话10/嵌套3）控制并发。
- **内存防护**：每个 Worker 的消息上限 50 条（对标 `TEAMMATE_MESSAGES_UI_CAP`），超过后触发 AutoCompact。`activeSwarms` 使用 Caffeine 缓存带 4 小时 TTL，防止异常终止的 Swarm 残留在内存中造成泄漏。
- **Worker 执行超时强制中止**：`CompletableFuture.orTimeout(30, MINUTES)` 设置 Worker 最大执行时间。❗ 注意：`future.cancel(true)` 不保证停止 Virtual Thread，必须通过 `AbortContext` 的 abort 信号传播配合 `Thread.interrupted()` 检查实现安全中止：

```java
// SwarmWorkerRunner.startWorker() — 超时保护
public CompletableFuture<WorkerResult> startWorker(
        String workerId, String taskPrompt,
        SwarmConfig config, ToolUseContext parentContext) {
    return CompletableFuture.supplyAsync(() -> {
        return executeWorkerLoop(workerId, taskPrompt, config, parentContext);
    }, workerExecutor)
    .orTimeout(30, TimeUnit.MINUTES)  // ← 超时保护
    .exceptionally(ex -> {
        if (ex instanceof TimeoutException) {
            log.error("Worker {} timed out after 30 minutes, forcing abort", workerId);
            // 通过 AbortContext 传播 abort 信号
            abortContextMap.get(workerId).abort(AbortReason.TIMEOUT);
        }
        return new WorkerResult(workerId, "Worker failed: " + ex.getMessage(), 0, 0);
    });
}
```
- **并发写文件保护**：多个 Worker 同时编辑文件时，使用 `FileChannel.lock()` 串行化写操作（完整实现见 SwarmWorkerRunner.writeFileWithLock() 方法）。Scratchpad 目录无锁限制。
- **权限传递**：Worker 权限模式默认降级为 `BUBBLE`，通过 `LeaderPermissionBridge` 冒泡确认。**死锁防护**：使用 `CompletableFuture.orTimeout(60, SECONDS)` 超时自动拒绝（完整实现见 LeaderPermissionBridge.requestPermission()）。`preserveMode: true` 防止 Worker 的权限变更泄漏回 Leader。
- **Idle 生命周期**：Worker 完成当前任务后进入 idle 状态，通过 TeamMailbox 发送 idle 通知（含最近 peer DM 摘要），等待 Leader 分配新任务或 shutdown。

**工作量**：12 人天（原 12 人天，结合 Virtual Thread 本地并发优化，分 3 个独立子阶段）  

**子阶段分解**：

| 阶段 | 内容 | 人天 | 交付物 |
|--------|------|------|--------|
| Phase 1: 基础生命周期 | SwarmState + SwarmWorkerRunner + Worker 创建/执行/终止 | 4天 | Worker 可启动并执行任务 |
| Phase 2: 通信协议 | TeamMailbox 完整通信 + Worker 间消息传递 | 4天 | Worker 间可通信 |
| Phase 3: 权限+复用 | LeaderPermissionBridge 权限冒泡 + Idle 复用逻辑 | 4天 | 完整 Swarm 生命周期 |

> **注意**：以下新类均需新建：SwarmWorkerRunner.java、SwarmState.java、LeaderPermissionBridge.java、SwarmConfig.java
> **源码清理**：SwarmService.java 当前 L47 `// TODO: Swarm 创建逻辑（待后续迭代实现）`、L58 `// TODO: Swarm 执行逻辑（待后续迭代实现）` 以及 L48、L59 的 `throw new UnsupportedOperationException(...)` 必须全部清除并替换为上述完整实现。

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
| Swarm 创建/状态查看 | WebSocket 推送 `swarm_state_update` 消息（**需新增定义**，当前 ServerMessage.java 中无此类型） | SwarmStatusPanel.tsx（**需新建**） |
| Worker 实时进度 | WebSocket 推送 `worker_progress` 消息（**需新增定义**，当前 ServerMessage.java 中无此类型） | WorkerProgressCard.tsx（**需新建**） |
| Worker 权限冒泡确认 | WebSocket 推送 `permission_bubble` 消息（**需新增定义**，当前 ServerMessage.java 中无此类型）→ 前端弹出确认对话框 | PermissionBubbleDialog.tsx（**需新建**） |
| Worker 间通信查看 | TeamMailbox 消息通过 WebSocket 推送（复用现有 #21 `teammate_message`） | SwarmMessageLog.tsx（**需新建**） |
| Swarm 关闭操作 | 前端按钮 → REST API `POST /api/swarm/{id}/shutdown` | SwarmStatusPanel.tsx 中的关闭按钮 |

**ServerMessage.java 新增 record 定义**（当前已有 32 种消息类型，编号 #1-#32，新增 #33-#35）：

```java
// 在 ServerMessage.java 末尾新增：

// ==================== #33-35: swarmStore ====================

/** #33 swarm_state_update — Swarm 状态变更通知 */
public record SwarmStateUpdate(
    String swarmId,
    String phase,           // INITIALIZING | RUNNING | IDLE | SHUTTING_DOWN | TERMINATED
    int activeWorkers,
    int totalWorkers,
    Map<String, WorkerSnapshot> workers
) {
    public record WorkerSnapshot(
        String workerId, String status, String currentTask,
        int toolCallCount, long tokenConsumed
    ) {}
}

/** #34 worker_progress — Worker 实时进度 */
public record WorkerProgress(
    String swarmId,
    String workerId,
    String status,          // STARTING | WORKING | IDLE | TERMINATED
    String currentTask,
    int toolCallCount,
    long tokenConsumed,
    List<String> recentToolCalls  // 最近 5 个
) {}

/** #35 permission_bubble — Worker 权限冒泡请求 */
public record PermissionBubble(
    String requestId,       // LeaderPermissionBridge 用于匹配回调
    String workerId,
    String toolName,
    String riskLevel,
    String reason
) {}
```

**前端组件规格**：

| 组件 | 文件路径 | 核心功能 | 数据源 |
|------|---------|---------|--------|
| SwarmStatusPanel.tsx | `frontend/src/components/swarm/` | Swarm 状态总览（侧边栏），phase 指示器 + Worker 列表 + 关闭按钮 | `swarm_state_update` |
| WorkerProgressCard.tsx | `frontend/src/components/swarm/` | 单个 Worker 进度卡片：workerId、当前任务、工具调用计数、token 消耗、进度条 | `worker_progress` |
| PermissionBubbleDialog.tsx | `frontend/src/components/swarm/` | 带 worker badge 的权限确认对话框，显示工具名/风险级别/原因，60s 超时自动拒绝 | `permission_bubble` |
| SwarmMessageLog.tsx | `frontend/src/components/swarm/` | Worker 间消息日志（时间线形式） | `teammate_message` (#21) |

**关键要求**：
- Worker 权限冒泡对话框必须阻塞等待用户决策（带超时自动拒绝，默认 60s）
- Worker 进度卡片需实时显示：workerId、当前任务、工具调用计数、token 消耗
- PC 端使用侧边面板展示 Swarm 状态，不遮挡主聊天区域

**新架构增强项（维度14）**：

- **聚焦 Virtual Thread 本地并发优化**：充分利用 `InProcessBackend` 已实现的 Virtual Thread 并发执行（L46-107），添加线程监控指标（`zhiku.swarm.virtual_threads.active`、`zhiku.swarm.worker.execution_time`），通过已有的 `micrometer-registry-prometheus` 对接 Actuator `/actuator/metrics` 端点
- **内存队列保持简单**：现有 `TeamMailbox` 的 `ConcurrentLinkedQueue`（L26）已足够——Swarm 场景下 Worker 数量有限（默认最多 5 个），消息量极小，无需引入 `PriorityBlockingQueue` 和死信处理的复杂度。若后续 Worker 数量显著增加，可升级为优先级队列
- **Virtual Thread 天然隔离 + ThreadLocal 上下文传递**：每个 Worker 运行在独立的 Virtual Thread 中，通过 `ThreadLocal` 传递 Worker 上下文（`ScopedValue` 在 Java 21 中为 Preview 特性，需 `--enable-preview` 编译参数，**生产环境不推荐使用**，Java 25 正式化后可迁移），无需 Spring Bean Scope 隔离
- **前端 SwarmDashboard 升级**：WebSocket 实时推送 Worker 状态，网格视图展示所有 Worker 的 Token 消耗、工具调用计数和进度条
- **优先级**：高

---

### 4.2 Coordinator 编排引擎

**目标**：在现有 `CoordinatorService` 基础上实现完整的四阶段工作流编排。

**当前状态**：
- `CoordinatorService.java`（181行 / 6.7KB）：模式检测（`isCoordinatorMode()`、`isCoordinatorTopLevel()`）、工具过滤（`COORDINATOR_ALLOWED_TOOLS = {Agent, TaskStop, SendMessage, SyntheticOutput}`）、Scratchpad（`.claude/scratchpad/{sessionId}/`）已实现。
- `CoordinatorPromptBuilder.java`（373行 / ~18.8KB）：Coordinator system prompt 已建立，✅ **四阶段工作流指令已集成**（L196-270，"4. Task Workflow — Four Phases" 章节），包含 Research/Synthesis/Implementation/Verification 四阶段描述、并发管理、验证要求、Worker 失败处理、Worker Prompt 编写规范（“永远不要委派理解”原则 L259-280）。
- ❗ `SubAgentExecutor.java` L880-904 中已有 5 种 AgentDefinition，其中 EXPLORE/VERIFICATION/PLAN 的 denied tools 为 `Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit")`，GENERAL_PURPOSE 允许所有工具（`allowedTools = Set.of("*")`）。文档中 WorkflowPhase 的 allowedTools 设计需与此协调。
- ❌ `CoordinatorWorkflow.java` **完全不存在**，需新建。
- ❌ `WorkflowPhase` sealed interface **不存在**，需新建。
- ❌ `CoordinatorWorkflowEngine` **不存在**，需新建。
- ❌ `validateDelegation()` 方法 **完全缺失**，需新建。
- 缺失：四阶段工作流引擎、“不委派理解”原则的自动化强制执行。

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
// CoordinatorWorkflow.java — 四阶段工作流引擎（**需新建**）
// 注意：各阶段的 allowedTools 需与 SubAgentExecutor.AgentDefinition 的 deniedTools 协调。
// SubAgentExecutor 中 EXPLORE/VERIFICATION/PLAN 的 deniedTools = 
//   Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit")
// CoordinatorService 中 COORDINATOR_ALLOWED_TOOLS = 
//   Set.of("Agent", "TaskStop", "SendMessage", "SyntheticOutput")

public sealed interface WorkflowPhase permits
    ResearchPhase, SynthesisPhase, ImplementationPhase, VerificationPhase {
    
    String phaseName();
    String phasePrompt();
    Set<String> allowedTools();  // Coordinator 层可用工具（非 Worker 层）
}

public record ResearchPhase(List<String> researchTasks) implements WorkflowPhase {
    @Override public String phaseName() { return "Research"; }
    @Override public String phasePrompt() { 
        return "Investigate the codebase. Launch Explore agents to find relevant files and understand the problem."; 
    }
    @Override public Set<String> allowedTools() { 
        // Coordinator 层：只能派 Agent + 发消息
        // Worker 层：自动应用 EXPLORE AgentDefinition 的 deniedTools
        return Set.of("Agent", "SendMessage"); 
    }
}

public record SynthesisPhase(Map<String, String> findings) implements WorkflowPhase {
    @Override public String phaseName() { return "Synthesis"; }
    @Override public String phasePrompt() { 
        return "Read all research findings. Understand the problem. Craft implementation specs with specific file paths and line numbers."; 
    }
    @Override public Set<String> allowedTools() { 
        return Set.of("Agent", "SendMessage", "SyntheticOutput"); 
    }
}

public record ImplementationPhase(List<String> executionPlans) implements WorkflowPhase {
    @Override public String phaseName() { return "Implementation"; }
    @Override public String phasePrompt() { 
        return "Assign execution plans to Worker agents. Each worker receives complete, specific, self-contained instructions."; 
    }
    @Override public Set<String> allowedTools() {
        // Worker 层使用 GENERAL_PURPOSE AgentDefinition（allowedTools = Set.of("*")）
        return Set.of("Agent", "SendMessage", "TaskStop"); 
    }
}

public record VerificationPhase(List<String> verificationChecks) implements WorkflowPhase {
    @Override public String phaseName() { return "Verification"; }
    @Override public String phasePrompt() { 
        return "Launch Verification agents to test all changes. Agents must run actual commands, not just review code."; 
    }
    @Override public Set<String> allowedTools() {
        // Worker 层使用 VERIFICATION AgentDefinition（deniedTools = FileEdit/FileWrite）
        return Set.of("Agent", "SendMessage"); 
    }
}

// CoordinatorWorkflowEngine.java（**需新建**）
@Service
public class CoordinatorWorkflowEngine {
    
    private static final Logger log = LoggerFactory.getLogger(CoordinatorWorkflowEngine.class);
    
    /** “不委派理解”模糊指令正则 */
    private static final List<Pattern> VAGUE_PATTERNS = List.of(
        Pattern.compile("(?i)based on (your|the) (findings|research)"),
        Pattern.compile("(?i)fix the (bug|issue|problem)"),
        Pattern.compile("(?i)using what you (learned|found)"),
        Pattern.compile("(?i)implement the (solution|fix)")
    );
    
    /** Prompt 最短有效长度——低于此值警告“委派理解” */
    // ❗ 注意：100 字符对中文文本过严（100字符中文约 30-40 个词）
    // 建议根据语言动态调整，或改用 Token 计数（约 50 Tokens）
    private static final int MIN_PROMPT_LENGTH = 100;  // ASCII/英文场景
    private static final int MIN_PROMPT_LENGTH_CJK = 50; // CJK（中日韩）场景
    
    /**
     * 从 Coordinator 的对话历史中推断当前阶段。
     * 基于消息中的阶段标记和工具调用模式判断。
     * 
     * 重要：这是推断而非硬编码，模型可能不严格遵循阶段顺序。
     * 通过 CoordinatorPromptBuilder L196-270 的强措词 system prompt 引导。
     */
    public WorkflowPhase detectCurrentPhase(List<Message> messages) {
        // 反向扫描消息，查找最近的阶段标记
        int agentCalls = 0;
        boolean hasEditTools = false;
        boolean hasSyntheticOutput = false;
        boolean hasVerificationAgent = false;
        
        for (int i = messages.size() - 1; i >= Math.max(0, messages.size() - 20); i--) {
            Message msg = messages.get(i);
            if (msg instanceof Message.AssistantMessage am && am.content() != null) {
                for (var block : am.content()) {
                    if (block instanceof ContentBlock.ToolUseBlock tu) {
                        if ("Agent".equals(tu.name())) agentCalls++;
                        if ("SyntheticOutput".equals(tu.name())) hasSyntheticOutput = true;
                        if ("FileEdit".equals(tu.name()) || "FileWrite".equals(tu.name())) hasEditTools = true;
                        // 检查 Agent 的 subagent_type 参数
                        // 注意：ToolUseBlock.input() 返回 JsonNode（非 Map）
                        if ("Agent".equals(tu.name()) && tu.input() != null) {
                            var input = tu.input();
                            if (input.has("subagent_type") 
                                    && "verification".equals(input.get("subagent_type").asText())) {
                                hasVerificationAgent = true;
                            }
                        }
                    }
                }
            }
        }
        
        // 判断逻辑（启发式，非硬编码）
        if (hasVerificationAgent) return new VerificationPhase(List.of());
        if (hasEditTools) return new ImplementationPhase(List.of());
        if (hasSyntheticOutput) return new SynthesisPhase(Map.of());
        return new ResearchPhase(List.of());
    }
    
    /**
     * 验证 Agent 派发指令的质量（“不委派理解”原则）。
     * 检查 AgentTool 的 prompt 参数是否包含足够具体的信息。
     * 
     * 初期仅作为 WARN 日志，不阻断执行（防止假阳性影响体验）。
     */
    public ValidationResult validateDelegation(String agentPrompt) {
        List<String> warnings = new ArrayList<>();
        
        // 1. 检查 prompt 长度（过短意味着委派理解）
        // 根据语言动态调整阈值：CJK 字符占比超过 30% 时使用更低阈值
        int effectiveMinLength = containsCjk(agentPrompt) ? MIN_PROMPT_LENGTH_CJK : MIN_PROMPT_LENGTH;
        if (agentPrompt == null || agentPrompt.length() < effectiveMinLength) {
            warnings.add(String.format(
                "Prompt too short (%d chars < %d minimum). Likely delegating understanding.",
                agentPrompt != null ? agentPrompt.length() : 0, effectiveMinLength));
        }
        
        // 2. 检查是否包含具体文件路径、行号、变量名等
        boolean hasSpecificInfo = agentPrompt != null && (
            agentPrompt.contains("/") ||    // 文件路径
            agentPrompt.matches(".*:\\d+.*") || // 行号
            agentPrompt.matches(".*\\b[A-Z][a-zA-Z]+\\.[a-zA-Z]+\\(.*") // 方法名
        );
        if (!hasSpecificInfo) {
            warnings.add("Prompt lacks specific file paths, line numbers, or method names.");
        }
        
        // 3. 检查是否包含模糊指令
        if (agentPrompt != null) {
            for (Pattern pattern : VAGUE_PATTERNS) {
                if (pattern.matcher(agentPrompt).find()) {
                    warnings.add("Vague delegation detected: '" + pattern.pattern() + "'");
                }
            }
        }
        
        // 记录警告（初期仅日志，不阻断）
        if (!warnings.isEmpty()) {
            log.warn("Delegation quality warning: {}", warnings);
        }
        
        return new ValidationResult(
            warnings.isEmpty(),  // valid
            warnings,            // warnings
            warnings.isEmpty() ? ValidationSeverity.OK : ValidationSeverity.WARN
        );
    }
    
    public record ValidationResult(
        boolean valid, List<String> warnings, ValidationSeverity severity
    ) {}
    
    public enum ValidationSeverity { OK, WARN, ERROR }
    
    /** CJK 字符检测——用于动态调整 Prompt 长度阈值 */
    private static boolean containsCjk(String text) {
        if (text == null) return false;
        long cjkCount = text.codePoints()
            .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                    || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA
                    || Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA
                    || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL)
            .count();
        return cjkCount > text.length() * 0.3;  // CJK 字符占比超过 30%
    }
}
```

**CoordinatorPromptBuilder 增强**：

`CoordinatorPromptBuilder.java` L196-270 已包含四阶段工作流指令（"4. Task Workflow — Four Phases"）和“永远不要委派理解”原则（L259-280，"Always synthesize" + "The Synthesis Anti-Pattern"）。以下为现有内容的**增强补充**，而非重复：

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

**工作量**：14-16 人天（原估 11-14 人天，基于实际缺失量上调：四阶段工作流引擎需从零实现，CoordinatorWorkflow/WorkflowPhase/CoordinatorWorkflowEngine 均不存在；前端 3 个组件全缺；含阶段检测准确性的提示词工程和“不委派理解”原则验证的假阳性处理）  
**完整度影响**：多Agent 88% → 95%（+7%）  
**依赖项**：#4.1 Swarm 模式（Coordinator 使用 Swarm 作为执行后端）  

> **注意**：CoordinatorWorkflow.java、WorkflowPhase sealed interface、CoordinatorWorkflowEngine 均需新建。
> `validateDelegation()` 完整实现已在上述代码中提供，包含：
> - Prompt 长度检查（< 100 字符警告）
> - 具体信息检查（文件路径、行号、方法名）
> - 模糊指令检查（4 种正则模式）
> - 初期仅作为 WARN 日志，不阻断执行

**风险与注意事项**：
- 四阶段不是硬编码流程，而是 prompt 引导 + 阶段检测。模型可能不严格遵循——通过 CoordinatorPromptBuilder L196-270 的强措词 system prompt 和阶段验证缓解。**降级方案**：当模型跳过阶段时，detectCurrentPhase() 将根据工具调用模式自动修正阶段标记，通过 WebSocket 推送阶段跳转警告，不强制阻断执行。
- “不委派理解”原则的自动检测有假阳性风险，初期仅作为 WARN 日志，不阻断执行。

**PC 浏览器交互设计**：

Coordinator 四阶段工作流需在前端可视化：
- **阶段指示器**：在聊天区域顶部显示当前阶段（Research → Synthesis → Implementation → Verification），使用进度条或步骤条组件
- **Agent 派发可视化**：每次 Coordinator 派发 Agent 时，前端显示 Agent 卡片（名称、任务摘要、状态）
- **“不委派理解”违规提示**：`validateDelegation()` 检测到模糊指令时，通过 WebSocket 推送 `delegation_warning` 消息（复用现有 #17 `notification` 类型，`level = "warn"`），前端显示黄色警告条

**涉及前端组件规格**：

| 组件 | 文件路径 | 核心功能 | 数据源 |
|------|---------|---------|--------|
| WorkflowPhaseIndicator.tsx | `frontend/src/components/coordinator/`（**需新建**） | 四阶段步骤条（水平进度条），高亮当前阶段，点击可查看阶段详情 | `detectCurrentPhase()` 结果 |
| AgentTaskCard.tsx | `frontend/src/components/coordinator/`（**需新建**） | Agent 派发卡片：名称、类型、任务摘要、状态（running/completed/failed） | 复用现有 #12-14 `agent_spawn`/`agent_update`/`agent_complete` |
| DelegationWarningBanner.tsx | `frontend/src/components/coordinator/`（**需新建**） | 模糊指令警告条（黄色），显示具体警告内容 | #17 `notification` (level="warn") |

**新架构增强项（维度14）**：

- **简单 enum + 阶段检测方法**：使用 `WorkflowPhase` enum（RESEARCH、SYNTHESIS、IMPLEMENTATION、VERIFICATION）和简单的阶段检测方法（基于消息分析），通过提示词引导模型遵循阶段顺序，无需引入状态机框架
- **简单 try-catch + 本地重试**：Agent 派发失败时使用简单 try-catch + 最多 1-2 次本地重试，本地进程内调用无需熔断器
- **审计日志（P3 可选，两种实现路径）**：
  - **当前可用**：通过 `log.info()` 在 Coordinator 关键决策点手动记录审计日志（阶段转换、Agent 派发参数、执行结果），无需额外依赖
  - **升级路径**：自定义 `@Audit` 注解 + AOP 切面自动审计。**前置依赖**：需在 `pom.xml` 中添加 `spring-boot-starter-aop`（当前未配置）
- **前端可视化**：`CoordinatorWorkflowVisualizer.tsx` 实现时间线 + 阶段进度图 + Agent 派发日志的交互式展示
- **优先级**：高

---

### 4.3 Agent 类型扩展

**目标**：实现 sealed interface 类型化和智能路由逻辑，将现有 5 种 AgentDefinition 配置升级为类型化系统。

**当前状态**（源码审查修正）：
- ✅ **已有 5 种 AgentDefinition 配置**：`SubAgentExecutor.java`（949行 / ~48KB）中已配置 EXPLORE、VERIFICATION、PLAN、GENERAL_PURPOSE、GUIDE 五种定义（L885-904）。
- ✅ 当前使用 `record AgentDefinition(String name, int maxTurns, String defaultModel, Set<String> allowedTools, Set<String> deniedTools, boolean omitClaudeMd, String systemPromptTemplate)`（L880-884）。
- ✅ `resolveAgentDefinition()` switch-case 路由已存在。
- ✅ denied tools 配置已一致：EXPLORE/VERIFICATION/PLAN 均配置了相同的 5 个 denied tools：`Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit")`。
- ✅ EXPLORE 和 GUIDE 使用轻量模型（`defaultModel = "haiku"`），EXPLORE 和 PLAN 省略 CLAUDE.md（`omitClaudeMd = true`）。
- ✅ GENERAL_PURPOSE 允许所有工具（`allowedTools = Set.of("*")`，`deniedTools = null`）。
- ✅ GUIDE 仅允许只读工具：`allowedTools = Set.of("Glob", "Grep", "FileRead", "WebFetch", "WebSearch")`。
- ❌ **`BuiltInAgentDefinition` sealed interface 不存在**，需新建。
- ❌ **`AgentStrategyFactory` 不存在**，智能路由逻辑需新建。
- ❌ **缺智能路由逻辑**：Agent 创建时未根据任务类型自动选择最佳 AgentDefinition。

**目标状态**（Claude Code 基线）：
- 4 种内置类型：general-purpose / Explore / Plan / Verification
- 每种类型有不同的模型选择、工具限制、system prompt 优化。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/tool/agent/BuiltInAgentDefinition.java` — **新建**
2. `backend/src/main/java/com/aicodeassistant/tool/agent/AgentStrategyFactory.java` — **新建**
3. `backend/src/main/java/com/aicodeassistant/tool/agent/AgentTool.java` — 路由增强
4. `backend/src/main/java/com/aicodeassistant/tool/agent/SubAgentExecutor.java` — 类型适配

核心设计：

```java
// BuiltInAgentDefinition.java（**需新建**）
// 将现有 SubAgentExecutor.AgentDefinition record 升级为 sealed interface 类型化系统
// 包含全部 5 种类型（对齐 SubAgentExecutor L885-904 的定义）
public sealed interface BuiltInAgentDefinition permits
    GeneralPurposeAgent, ExploreAgent, PlanAgent, VerificationAgent, GuideAgent {
    
    String type();
    String description();
    int maxTurns();               // 默认 30
    Set<String> allowedTools();   // null = 全部允许
    Set<String> deniedTools();    // null = 不禁止
    String modelOverride();       // null = 继承父级模型
    boolean omitClaudeMd();       // 是否省略 CLAUDE.md 注入
    // ❗ 注意：原设计中的 isAsync() 属性已移除——
    // 实际异步执行通过 SubAgentExecutor 的 executeSync() vs executeAsync() 方法区分，
    // 而非通过 AgentDefinition 的属性标记。调用方根据 AgentRequest.runInBackground() 决定。
    String systemPromptTemplate();
}

// 对齐 SubAgentExecutor.AgentDefinition.EXPLORE（L885-888）
public record ExploreAgent() implements BuiltInAgentDefinition {
    @Override public String type() { return "explore"; }
    @Override public String description() { return "Search and read code, files, documentation"; }
    @Override public int maxTurns() { return 30; }
    @Override public Set<String> allowedTools() { return null; }  // 不限制 allowed
    @Override public Set<String> deniedTools() {
        // 与 SubAgentExecutor.EXPLORE.deniedTools 一致
        return Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit");
    }
    @Override public String modelOverride() { return "haiku"; }  // 轻量模型（haiku 为模型别名，通过 application.yml agent.model-aliases 映射到千问系列，可配置替换）
    @Override public boolean omitClaudeMd() { return true; }      // 省略 CLAUDE.md + gitStatus
    @Override public String systemPromptTemplate() { return SubAgentExecutor.EXPLORE_AGENT_PROMPT; }
}

// 对齐 SubAgentExecutor.AgentDefinition.VERIFICATION（L889-892）
public record VerificationAgent() implements BuiltInAgentDefinition {
    @Override public String type() { return "verification"; }
    @Override public String description() { return "Test and verify changes with actual commands"; }
    @Override public int maxTurns() { return 30; }
    @Override public Set<String> allowedTools() { return null; }
    @Override public Set<String> deniedTools() {
        return Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit");
    }
    @Override public String modelOverride() { return null; }  // 继承父级模型
    @Override public boolean omitClaudeMd() { return false; }
    // ❗ isAsync 已移除：异步执行通过 SubAgentExecutor.executeAsync() 控制，
    // 而非 AgentDefinition 属性。调用方传入 AgentRequest.runInBackground()=true 即可异步执行。
    @Override public String systemPromptTemplate() { return SubAgentExecutor.VERIFICATION_AGENT_PROMPT; }
    // 反自我欺骗 prompt：要求每个检查有实际命令和输出
}

// 对齐 SubAgentExecutor.AgentDefinition.PLAN（L893-896）
public record PlanAgent() implements BuiltInAgentDefinition {
    @Override public String type() { return "plan"; }
    @Override public String description() { return "Create detailed implementation plans"; }
    @Override public int maxTurns() { return 30; }
    @Override public Set<String> allowedTools() { return null; }
    @Override public Set<String> deniedTools() {
        return Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit");
    }
    @Override public String modelOverride() { return null; }
    @Override public boolean omitClaudeMd() { return true; }
    @Override public String systemPromptTemplate() { return SubAgentExecutor.PLAN_AGENT_PROMPT; }
}

// 对齐 SubAgentExecutor.AgentDefinition.GENERAL_PURPOSE（L897-899）
public record GeneralPurposeAgent() implements BuiltInAgentDefinition {
    @Override public String type() { return "general-purpose"; }
    @Override public String description() { return "Full-capability agent for implementation tasks"; }
    @Override public int maxTurns() { return 30; }
    @Override public Set<String> allowedTools() { return Set.of("*"); }  // 全部工具
    @Override public Set<String> deniedTools() { return null; }            // 不禁止
    @Override public String modelOverride() { return null; }
    @Override public boolean omitClaudeMd() { return false; }
    @Override public String systemPromptTemplate() { return SubAgentExecutor.GENERAL_PURPOSE_AGENT_PROMPT; }
}

// 对齐 SubAgentExecutor.AgentDefinition.GUIDE（L900-903）
public record GuideAgent() implements BuiltInAgentDefinition {
    @Override public String type() { return "guide"; }
    @Override public String description() { return "Claude Code usage guide and documentation expert"; }
    @Override public int maxTurns() { return 30; }
    @Override public Set<String> allowedTools() {
        return Set.of("Glob", "Grep", "FileRead", "WebFetch", "WebSearch");  // 只读
    }
    @Override public Set<String> deniedTools() { return null; }
    @Override public String modelOverride() { return "haiku"; }  // 轻量模型（haiku 为模型别名，通过 application.yml agent.model-aliases 映射到千问系列，可配置替换）
    @Override public boolean omitClaudeMd() { return false; }
    @Override public String systemPromptTemplate() { return SubAgentExecutor.GUIDE_AGENT_PROMPT; }
}

// AgentStrategyFactory.java（**需新建**）
// 智能路由：根据任务关键词自动选择最佳 Agent 类型
@Service
public class AgentStrategyFactory {
    
    private static final Map<Pattern, BuiltInAgentDefinition> KEYWORD_ROUTES = Map.of(
        // ❗ 使用 \b 完整词汇匹配，避免误匹配（如 "plan" 匹配到 "explanation"）
        Pattern.compile("(?i)\\b(search|find|explore|look for|investigate)\\b"), new ExploreAgent(),
        Pattern.compile("(?i)\\b(test|verify|check|validate|lint|build)\\b"), new VerificationAgent(),
        Pattern.compile("(?i)\\b(plan|design|architect|outline)\\b"), new PlanAgent(),
        Pattern.compile("(?i)\\b(guide|help|how to|usage|tutorial)\\b"), new GuideAgent()
    );
    
    /**
     * Java 21 sealed interface + switch pattern matching 类型安全路由。
     * 编译器保证穷尽性检查，新增 Agent 类型时自动提示未处理的分支。
     */
    public AgentDefinition toAgentDefinition(BuiltInAgentDefinition builtIn) {
        return switch (builtIn) {
            case ExploreAgent e -> AgentDefinition.EXPLORE;
            case VerificationAgent v -> AgentDefinition.VERIFICATION;
            case PlanAgent p -> AgentDefinition.PLAN;
            case GeneralPurposeAgent g -> AgentDefinition.GENERAL_PURPOSE;
            case GuideAgent gu -> AgentDefinition.GUIDE;
        };  // 编译器强制穷尽性检查
    }
    
    /**
     * 根据任务 prompt 关键词智能选择 Agent 类型。
     * 匹配失败时默认返回 GENERAL_PURPOSE。
     */
    public BuiltInAgentDefinition resolveByPrompt(String taskPrompt) {
        if (taskPrompt == null) return new GeneralPurposeAgent();
        for (var entry : KEYWORD_ROUTES.entrySet()) {
            if (entry.getKey().matcher(taskPrompt).find()) {
                return entry.getValue();
            }
        }
        return new GeneralPurposeAgent();  // 默认
    }
}
```

**工作量**：5 人天（原 4 人天，+1 人天用于 sealed interface + pattern matching 路由 + AgentStrategyFactory）  
**完整度影响**：多Agent +3%  
**依赖项**：无  

**PC 浏览器交互设计**：

| 组件 | 文件路径 | 核心功能 | 数据源 |
|------|---------|---------|--------|
| AgentTypePicker.tsx | `frontend/src/components/agent/`（**需新建**） | Agent 类型选择器：展示 5 种类型的能力说明、工具限制、模型配置；支持手动选择和智能推荐 | `/actuator/agent-config`（P3）|

**新架构增强项（维度14）**：

- **sealed interface + pattern matching 类型安全路由**：利用 Java 21 的 sealed interface + switch 表达式 pattern matching 实现类型安全路由，编译器保证穷尽性检查（完整实现见 `AgentStrategyFactory.toAgentDefinition()`），新增 Agent 类型时编译器自动提示未处理的分支
- **策略工厂模式**：`AgentStrategyFactory`（**需新建**）通过 `KEYWORD_ROUTES` 正则匹配实现智能路由（根据任务关键词匹配最佳 Agent 类型），匹配失败时默认返回 GENERAL_PURPOSE（完整实现见上述代码）
- **Actuator `/actuator/agent-config` 端点**（P3可选）：自定义 Actuator Endpoint 暴露所有 Agent 类型配置（工具限制、模型选择、system prompt 等），运维可直接查看
- **前端 `AgentTypePicker.tsx`**：可视化 Agent 类型选择器，展示各类型能力说明和工具限制
- **优先级**：中

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
- ❌ **缺失三级渐进折叠**：未按消息年龄/重要性分级。CollapseLevel sealed interface **不存在**，需新建。
- 🔴 **互斥协调逻辑缺失**：`ContextCascade.executePreApiCascade()` L225-254 中，Level 1.5 ContextCollapse（L225-234）执行后，`current` 引用已更新为折叠后的消息列表（L231），随后 Level 2 AutoCompact（L236-254）会在更新后的 `current` 上重新计算 `TokenWarningState`（L238）。**隐式协调部分有效**：如果 Collapse 释放了足够 token，`calculateTokenWarningState(current, model)` 会返回低于阈值的结果，AutoCompact 不会触发。**但存在以下缺陷**：
  - `CascadeResult` record 中 `contextCollapseExecuted`（L97）和 `contextCollapseCharsFreed`（L98）已记录但未参与决策
  - 缺少显式日志说明为何跳过 AutoCompact（影响调试）
  - 当 Collapse 释放的 chars 较多但 token 估算减少不足时（chars/tokens 不等价），仍可能触发双重压缩
  
  **修复方案**：在 L235 后、L236 前插入显式互斥检查。将原 L236-254 替换为以下逻辑：

```java
// ContextCascade.executePreApiCascade() — 替换 L236-254 的 Level 2 块：
// ===== Level 2: AutoCompact (LLM 摘要) — 含 Collapse 互斥协调 =====
if (collapseExecuted) {
    // Collapse 已执行，重新评估是否仍需 AutoCompact
    TokenWarningState postCollapseWarning = calculateTokenWarningState(current, model);
    if (!postCollapseWarning.isAboveAutoCompactThreshold()) {
        log.info("Level 2 AutoCompact 跳过: Collapse 已释放足够空间 " +
                "(collapseCharsFreed={}, postTokens={}, threshold={})",
                collapseCharsFreed, postCollapseWarning.currentTokens(),
                postCollapseWarning.autoCompactThreshold());
    } else if (!trackingState.isCircuitBroken()) {
        log.info("Level 2 AutoCompact 触发: Collapse 释放不足 (postTokens={} > threshold={})",
                postCollapseWarning.currentTokens(), postCollapseWarning.autoCompactThreshold());
        acAttempted = true;
        try {
            acResult = compactService.compact(current, contextWindow, false);
            if (acResult.skipReason() == null && !acResult.compactedMessages().isEmpty()) {
                acExecuted = true;
                current = acResult.compactedMessages();
                log.info("Level 2 AutoCompact completed: {}", acResult.summary());
            }
        } catch (Exception e) {
            log.error("Level 2 AutoCompact failed", e);
        }
    }
} else if (!trackingState.isCircuitBroken()) {
    // Collapse 未执行，保持原有 AutoCompact 判断逻辑
    TokenWarningState warning = calculateTokenWarningState(current, model);
    if (warning.isAboveAutoCompactThreshold()) {
        log.info("Level 2 AutoCompact triggered: {} tokens > threshold {}",
                warning.currentTokens(), warning.autoCompactThreshold());
        acAttempted = true;
        try {
            acResult = compactService.compact(current, contextWindow, false);
            if (acResult.skipReason() == null && !acResult.compactedMessages().isEmpty()) {
                acExecuted = true;
                current = acResult.compactedMessages();
                log.info("Level 2 AutoCompact completed: {}", acResult.summary());
            }
        } catch (Exception e) {
            log.error("Level 2 AutoCompact failed", e);
        }
    }
}
```
- ❌ **缺失用户消息保留策略**。
- ❌ **CompactService 抑制逻辑未实现**。
- ⚠️ 该 bug 声明需进一步验证，原描述（UserMessage.toolUseResult 逻辑错误）可能与实际代码不符。
- ⚠️ **substring 边界风险**：`ContextCollapseService.truncateBlocks()` L141 执行 `t.text().substring(0, textTruncateKeep)` 时，未对 `textTruncateKeep` 与文本实际长度做 `Math.min` 保护。默认配置下安全（threshold=2000, keep=500，仅 >2000 字符的文本才进入截断，500 < 2000 恒成立），但若运维将 `zhiku.context.collapse.keep` 配置为大于 `zhiku.context.collapse.threshold` 的值，将触发 `StringIndexOutOfBoundsException`。修复代码：

```java
// ContextCollapseService.truncateBlocks() L141 修复：
int keepLen = Math.min(textTruncateKeep, t.text().length());
String truncated = t.text().substring(0, keepLen)
        + "\n...[collapsed: " + t.text().length() + " chars]";
```

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

**Step 1: 新建 `CollapseLevel.java`** — sealed interface 三级渐进折叠策略定义：

```java
// backend/src/main/java/com/aicodeassistant/engine/CollapseLevel.java — 新建文件
package com.aicodeassistant.engine;

/**
 * CollapseLevel — 三级渐进折叠策略 sealed interface。
 * 定义消息按距尾部距离的折叠级别。
 *
 * @see ContextCollapseService#progressiveCollapse
 */
public sealed interface CollapseLevel
        permits CollapseLevel.FullRetention,
                CollapseLevel.SummaryRetention,
                CollapseLevel.SkeletonRetention {

    /** 距尾部的消息数阈值（inclusive），该级别覆盖 [尾部-maxAge, 尾部-prevMaxAge) 区间 */
    int maxAgeMessages();

    /** 对消息内容执行折叠 */
    String collapse(String originalContent);

    /** Level A: 完整保留 — 尾部 10 条消息原样保留 */
    record FullRetention(int maxAgeMessages) implements CollapseLevel {
        public FullRetention() { this(10); }
        @Override public String collapse(String originalContent) {
            return originalContent; // 不做任何处理
        }
    }

    /** Level B: 摘要保留 — 倒数 10-30 条，长文本截断保留前 500 字符 */
    record SummaryRetention(int maxAgeMessages, int keepChars) implements CollapseLevel {
        public SummaryRetention() { this(30, 500); }
        @Override public String collapse(String originalContent) {
            if (originalContent == null || originalContent.length() <= keepChars) {
                return originalContent;
            }
            return originalContent.substring(0, Math.min(keepChars, originalContent.length()))
                    + "\n...[summary-collapsed: " + originalContent.length() + " chars]";
        }
    }

    /** Level C: 骨架保留 — 30 条以前，仅保留 role + toolUseId + 一行摘要 */
    record SkeletonRetention(int maxAgeMessages) implements CollapseLevel {
        public SkeletonRetention() { this(Integer.MAX_VALUE); }
        @Override public String collapse(String originalContent) {
            if (originalContent == null || originalContent.length() <= 50) {
                return originalContent;
            }
            int newline = originalContent.indexOf('\n');
            String firstLine = newline > 0
                    ? originalContent.substring(0, Math.min(newline, 80))
                    : originalContent.substring(0, Math.min(80, originalContent.length()));
            return "[skeleton] " + firstLine + "...";
        }
    }
}
```

**Step 2: 增强 `ContextCollapseService.java`** — 在现有 `collapseMessages()` 基础上新增渐进式折叠方法：

```java
// ContextCollapseService.java — 新增方法（保留现有 collapseMessages 不变）
private static final List<CollapseLevel> DEFAULT_LEVELS = List.of(
        new CollapseLevel.FullRetention(),      // 尾部 10 条完整保留
        new CollapseLevel.SummaryRetention(),    // 10-30 条摘要保留
        new CollapseLevel.SkeletonRetention()    // 30+ 条骨架化
);

/**
 * 渐进式折叠 — 按消息距尾部距离分三级处理。
 * 关键规则：所有 UserMessage（非 toolUseResult）永远保留原文，
 * 防止模型丢失用户反馈（如"不要用 Redux"）。
 */
public CollapseResult progressiveCollapse(List<Message> messages, List<CollapseLevel> levels) {
    if (messages == null || messages.isEmpty()) {
        return new CollapseResult(messages != null ? messages : List.of(), 0, 0);
    }
    List<CollapseLevel> sortedLevels = levels != null ? levels : DEFAULT_LEVELS;
    int totalMessages = messages.size();
    List<Message> result = new ArrayList<>(totalMessages);
    int collapsedCount = 0;
    int estimatedCharsFreed = 0;

    for (int i = 0; i < totalMessages; i++) {
        Message msg = messages.get(i);
        int distanceFromTail = totalMessages - 1 - i;

        // 规则：UserMessage（非工具结果）永远保留原文
        if (msg instanceof Message.UserMessage userMsg && userMsg.toolUseResult() == null) {
            result.add(msg);
            continue;
        }

        // 确定该消息的折叠级别
        CollapseLevel level = sortedLevels.stream()
                .filter(l -> distanceFromTail < l.maxAgeMessages())
                .findFirst()
                .orElse(sortedLevels.get(sortedLevels.size() - 1));

        if (level instanceof CollapseLevel.FullRetention) {
            result.add(msg); // 完整保留
        } else {
            // 对工具结果和助手消息执行折叠
            Message collapsedMsg = collapseMessage(msg, level);
            int charsBefore = estimateMessageChars(msg);
            int charsAfter = estimateMessageChars(collapsedMsg);
            estimatedCharsFreed += (charsBefore - charsAfter);
            result.add(collapsedMsg);
            collapsedCount++;
        }
    }
    return new CollapseResult(result, collapsedCount, estimatedCharsFreed);
}

/**
 * 对单条消息执行折叠——根据消息类型分别处理。
 * 保留消息的 role、toolUseId 结构，仅折叠内容部分。
 */
private Message collapseMessage(Message msg, CollapseLevel level) {
    if (msg instanceof Message.AssistantMessage am && am.content() != null) {
        // 助手消息：折叠每个 content block
        List<ContentBlock> collapsedBlocks = am.content().stream()
            .map(block -> {
                if (block instanceof ContentBlock.TextBlock t && t.text() != null) {
                    return (ContentBlock) new ContentBlock.TextBlock(level.collapse(t.text()));
                }
                if (block instanceof ContentBlock.ToolResultBlock tr && tr.content() != null) {
                    return (ContentBlock) new ContentBlock.ToolResultBlock(
                        tr.toolUseId(), level.collapse(tr.content()), tr.isError());
                }
                return block; // ToolUseBlock 等保留原样（保留 toolUseId 结构）
            })
            .toList();
        return new Message.AssistantMessage(am.id(), am.timestamp(), collapsedBlocks, am.model(), am.stopReason());
    }
    if (msg instanceof Message.UserMessage um && um.toolUseResult() != null) {
        // 工具结果消息：折叠内容但保留 toolUseId
        String collapsedContent = level.collapse(
            um.toolUseResult().content() != null ? um.toolUseResult().content() : "");
        return new Message.UserMessage(um.id(), um.timestamp(),
            List.of(new ContentBlock.TextBlock(collapsedContent)),
            um.toolUseResult().withContent(collapsedContent), um.attachments());
    }
    return msg; // 其他消息类型原样返回
}

/** 估算消息字符数（用于统计释放量） */
private int estimateMessageChars(Message msg) {
    if (msg instanceof Message.AssistantMessage am && am.content() != null) {
        return am.content().stream()
            .mapToInt(b -> b instanceof ContentBlock.TextBlock t ? (t.text() != null ? t.text().length() : 0) : 0)
            .sum();
    }
    return 0;
}
```

**Step 3: ContextCascade 互斥协调** — 见上方「互斥协调逻辑」修复代码，替换 `executePreApiCascade()` L236-254。

**`progressiveCollapse()` 与现有 `collapseMessages()` 的关系**：
- `collapseMessages()` 是现有的二级折叠（Level A 保护尾部 + Level B 截断），保持不变作为基础实现。
- `progressiveCollapse()` 是新增的三级渐进折叠，作为 `collapseMessages()` 的**增强替代**。
- `ContextCascade.executePreApiCascade()` 中的 Level 1.5 应优先调用 `progressiveCollapse()`，若异常则降级回退到 `collapseMessages()`。
- 两者不会同时执行，是替代关系（非并存）。

**Step 4: application.yml 配置项支持**

```yaml
# application.yml — 新增 CollapseLevel 参数配置
zhiku:
  context:
    collapse:
      protected-tail: 6          # 尾部保护消息数（现有）
      threshold: 2000             # 截断阈值（现有）
      keep: 500                   # 截断保留字符数（现有）
      # 渐进折叠级别参数（新增）
      level-a-max-age: 10         # FullRetention 尾部消息数
      level-b-max-age: 30         # SummaryRetention 尾部消息数
      level-b-keep-chars: 500     # SummaryRetention 保留字符数
```

> **运维说明**：通过修改 `application.yml` 中的 `zhiku.context.collapse.*` 参数即可调整 CollapseLevel 行为，无需修改代码。

**Step 5: ContextCascade 互斥协调单元测试用例**

| 测试场景 | 前置条件 | 预期结果 |
|---------|---------|----------|
| Collapse 释放足够空间 | Collapse 后 token < autoCompactThreshold | AutoCompact 跳过，日志记录跳过原因 |
| Collapse 释放不足 | Collapse 后 token > autoCompactThreshold | AutoCompact 触发，日志记录双重压缩 |
| Collapse 未执行 | token 未超阈值 | 保持原有 AutoCompact 判断逻辑 |
| Collapse 异常 | Collapse 抛出异常 | 降级回退到 collapseMessages()，AutoCompact 正常判断 |
| substring 边界 | keep > text.length() | Math.min 保护生效，无 StringIndexOutOfBoundsException |

**工作量**：1.5 人天（~~原 6 人天~~，第六轮审查修正：CollapseLevel sealed interface 新建 + progressiveCollapse 实现 0.5 天 + 互斥协调修复 0.5 天 + substring 边界修复 + 测试 0.5 天）  
**完整度影响**：上下文管理 82% → 87%（+5%）  
**依赖项**：无  
**状态**：⚠️ 基础框架已有（ContextCollapseService 170行，两级折叠），需新建 CollapseLevel.java + 增强渐进折叠 + 修复互斥协调  
**当前实际完整度**：约 60%（基础两级折叠 ✅，三级渐进折叠 ❌，互斥协调 ❌，substring 安全 ❌）  
**风险与注意事项**：
- 渐进折叠可能折叠了模型后续需要的信息。通过保留所有用户消息和工具调用的 toolUseId 缓解。
- 折叠后的消息仍保留 role 和结构，模型可通过工具重新获取已折叠的详细内容。

**新架构增强项（维度14）**：

- **本地 ConcurrentHashMap 缓存摘要**：使用 session 级别 `ConcurrentHashMap<String, String>` 缓存已生成的消息摘要，session 结束即清除，避免重复调用 LLM 生成相同消息的摘要，节省 Token 开销（~~原方案 `@Cacheable("collapseSummary")` 过度设计~~）
- ~~**Spring Data JPA 持久化**~~：**已删除**（第五轮审查）——无 JPA 依赖，session 结束即清除，无需持久化压缩历史
- **`@Scheduled` 后台压缩**（P3 可选）：每 5 分钟周期扫描空闲 session 自动压缩，Spring Boot 原生调度支持。**注**：当前已有被动触发机制（token 超限时触发），主动后台压缩为锦上添花，优先级降为 P3
- **前端 `CompressionHistory.tsx`**（后续迭代）：展示压缩前后 Token 对比、节省比例和压缩历史趋势图。当前阶段不实现
- **优先级**：高

---

### 5.2 压缩后关键文件重注入

**目标**：AutoCompact 压缩后，自动重新注入最多 5 个关键文件的内容和 skill 指令。

**当前状态**（源码审查修正）：
- `CompactService.java`（39.7KB，895 行）已实现 `reInjectFilesAfterCompact()`（L782-869），但基于正则从摘要中提取文件路径（`extractFilePathsFromSummary()` L871-883），精确度依赖 LLM 摘要中是否包含文件路径。
  - **现有方案优劣**：✅ 无需额外状态追踪；❌ 精确度低（LLM 可能省略文件路径），❌ 无法按引用频率排序
  - **KeyFileTracker 方案优劣**：✅ 精确追踪实际访问历史；✅ 按频率排序，重注入最关键文件；❌ 需新建组件+埋点
  - **结论**：采用 KeyFileTracker 方案作为主路径，现有正则提取作为降级回退（当 KeyFileTracker 无记录时使用）
- ❌ **KeyFileTracker.java 完全不存在，需从零新建**。本方案的核心新建组件，负责追踪对话中频繁引用的文件。
- ✅ FileStateCache.java（107行）提供文件状态缓存（如 FileReadTool.java L177-182 的 `markRead` 调用），但这是文件状态缓存，**不是频率追踪**。KeyFileTracker 需要独立新建，用于累积引用计数。
- ❤ 工具中（FileReadTool/FileEditTool/GrepTool）**需要新增调用 KeyFileTracker**，而非修改现有 FileStateCache。当前工具中完全没有频率追踪埋点。
- ⚠️ **安全缺陷**：现有 `reInjectFilesAfterCompact()` L790-869 直接调用 `Files.readString()` 读取文件，**未经过 `PathSecurityService` 安全检查**，可能导致重注入敏感文件（如 `.env`）。必须在文件读取前增加 `pathSecurity.checkReadPermission()` 检查。
- **WebSocket 推送缺失**：CompactService 当前完全无 WebSocket 依赖，压缩过程中仅有 `CompactStart` 和 `CompactComplete` 两种消息（定义于 ServerMessage.java），**缺少 `CompactProgressPayload` 实时进度消息**。压缩期间无法向前端推送进度（如已处理消息数/总消息数/已释放 token 数）。需在 CompactService 中注入 WebSocketManager，在 `generateLlmSummary()` 期间分段推送进度（每处理 10% 消息推送一次），并在 `executeCompactHooks()` 中推送最终结果。这是浏览器架构相比终端的重要优势之一（维度 14）。
- 压缩后模型只有一个抽象摘要，丢失了关键文件的具体内容。

**目标状态**（Claude Code 基线）：
- 压缩后重新注入最多 5 个关键文件（每个最多 5000 tokens）。
- 重新注入 skill 指令（最多 25000 tokens）。
- 保留所有用户消息。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/engine/CompactService.java` — 增强 `reInjectFilesAfterCompact()`，新增 `rebuildAfterCompact()`
2. `backend/src/main/java/com/aicodeassistant/engine/KeyFileTracker.java` — **新建**，Caffeine 缓存 + AtomicInteger 计数
3. `backend/src/main/java/com/aicodeassistant/tool/impl/FileReadTool.java` — 增加 KeyFileTracker 埋点（L182 后）
4. `backend/src/main/java/com/aicodeassistant/tool/impl/FileEditTool.java` — 增加 KeyFileTracker 埋点
5. `backend/src/main/java/com/aicodeassistant/tool/impl/GrepTool.java` — 增加 KeyFileTracker 埋点
6. `backend/src/main/java/com/aicodeassistant/service/FileStateCache.java` — **已存在**（107行，LRU缓存），功能与本方案目的不同，无需修改

核心设计：

**Step 1: 新建 `KeyFileTracker.java`** — 追踪对话中频繁引用的文件：

```java
// backend/src/main/java/com/aicodeassistant/engine/KeyFileTracker.java — 新建文件
package com.aicodeassistant.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KeyFileTracker — 追踪对话中频繁引用的文件。
 * 基于 Caffeine 缓存 + AtomicInteger 计数，按 session 隔离。
 * 压缩后重注入时，按引用频率排序获取 Top-N 关键文件。
 */
@Service
public class KeyFileTracker {

    // Caffeine 缓存：sessionId → Map<filePath, referenceCount>
    // session 过期 2 小时自动清除，最多跟踪 200 个 session
    private final Cache<String, ConcurrentHashMap<String, AtomicInteger>> sessionFileRefs =
            Caffeine.newBuilder()
                    .maximumSize(200)
                    .expireAfterAccess(Duration.ofHours(2))
                    .build();

    // 去重集合：防止同一轮对话中对同一文件重复计数
    private final Cache<String, Set<String>> turnDedup =
            Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(Duration.ofMinutes(30))
                    .build();

    /**
     * 记录文件引用（在 FileReadTool、FileEditTool、GrepTool 执行时调用）。
     * 同一轮对话中对同一文件只计数一次。
     *
     * @param sessionId 会话 ID
     * @param filePath  文件绝对路径
     * @param turnId    当前轮次 ID（用于去重，实际使用 ToolUseContext.toolUseId()）
     */
    public void trackFileReference(String sessionId, String filePath, String turnId) {
        // 去重检查：(sessionId, filePath, turnId) 三元组
        String dedupKey = sessionId + ":" + turnId;
        Set<String> trackedPaths = turnDedup.get(dedupKey,
                k -> ConcurrentHashMap.newKeySet());
        if (!trackedPaths.add(filePath)) {
            return; // 本轮已计数，跳过
        }
        sessionFileRefs.get(sessionId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(filePath, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * 获取 Top-N 关键文件（按引用次数降序）。
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

    /** session 结束时主动清除 */
    public void clearSession(String sessionId) {
        sessionFileRefs.invalidate(sessionId);
    }
}
```

**Step 2: 增强 `CompactService.rebuildAfterCompact()`** — 替代现有正则提取方案：

```java
// CompactService.java — 新增方法（在 reInjectFilesAfterCompact 后）
// 需新增依赖注入：
//   private final KeyFileTracker keyFileTracker;
//   private final PathSecurityService pathSecurity;  // 安全检查

private static final int MAX_REINJECT_FILES = 5;
private static final int MAX_FILE_SIZE_CHARS = 10_000;

/**
 * 基于访问历史的文件重注入 — 优先使用 KeyFileTracker，降级回退到正则提取。
 */
public List<Message> rebuildAfterCompact(
        List<Message> compactedMessages, String sessionId, String workingDirectory) {

    // 1. 优先使用 KeyFileTracker 获取 Top-5 关键文件
    List<String> keyFiles = keyFileTracker.getKeyFiles(sessionId, MAX_REINJECT_FILES);

    // 2. 降级回退：KeyFileTracker 无记录时，使用现有正则提取方案
    if (keyFiles.isEmpty()) {
        return reInjectFilesAfterCompact(compactedMessages, workingDirectory);
    }

    // 3. 安全检查 + 文件读取
    List<String> validPaths = keyFiles.stream()
            .filter(path -> {
                // ★ PathSecurityService 安全检查 ★
                var checkResult = pathSecurity.checkReadPermission(path, workingDirectory);
                if (!checkResult.isAllowed()) {
                    log.warn("文件重注入安全拦截: {} - {}", path, checkResult.message());
                    return false;
                }
                return true;
            })
            .filter(p -> {
                try { return Files.exists(Path.of(p)) && Files.size(Path.of(p)) < MAX_FILE_SIZE_CHARS * 4L; }
                // ★ MAX_FILE_SIZE_CHARS * 4L：单个 UTF-8 字符最多 4 字节，用字节大小估算字符数上限
                catch (IOException e) { return false; }
            })
            .limit(MAX_REINJECT_FILES)
            .toList();

    if (validPaths.isEmpty()) {
        log.debug("压缩后文件重注入: 无有效文件可注入");
        return compactedMessages;
    }

    // 4. 读取文件内容并截断（复用现有 reInjectFilesAfterCompact 的截断逻辑）
    List<Message> result = new ArrayList<>(compactedMessages);
    StringBuilder fileContent = new StringBuilder();
    fileContent.append("[Key Files re-injected after compression (by access frequency)]\n\n");

    for (String path : validPaths) {
        try {
            String content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            if (content.length() > MAX_FILE_SIZE_CHARS) {
                content = content.substring(0, MAX_FILE_SIZE_CHARS) + "\n...[truncated]";
            }
            fileContent.append("--- ").append(path).append(" ---\n");
            fileContent.append(content).append("\n\n");
        } catch (IOException e) {
            log.warn("文件重注入读取失败: {}", path, e);
        }
    }

    // 5. 插入到 COMPACT_SUMMARY 消息之后
    Message reInjectMsg = new Message.SystemMessage(
            UUID.randomUUID().toString(), Instant.now(),
            fileContent.toString(), SystemMessageType.FILE_REINJECT);

    int insertIndex = -1;
    for (int i = 0; i < result.size(); i++) {
        if (result.get(i) instanceof Message.SystemMessage sys
                && sys.type() == SystemMessageType.COMPACT_SUMMARY) {
            insertIndex = i + 1;
        }
    }
    if (insertIndex >= 0 && insertIndex <= result.size()) {
        result.add(insertIndex, reInjectMsg);
    } else {
        result.add(reInjectMsg);
    }

    log.info("压缩后文件重注入完成 (KeyFileTracker): {}个文件 [{}]",
            validPaths.size(), String.join(", ", validPaths));
    return result;
}
```

> **说明**：原文档中的 `readFileTruncated(path, 5000)` 方法在 CompactService 中**不存在**。上述代码直接使用 `Files.readString()` + 截断，与现有 `reInjectFilesAfterCompact()` L837-839 的实现方式保持一致。同时增加了 `PathSecurityService` 安全检查，修复了现有代码的安全缺陷。

**工作量**：2.5-3 人天（原估 1.5 人天，上调原因：KeyFileTracker 新建 + 去重逻辑 0.5 天 + 三处工具埋点 + 构造器修改 0.5 天 + CompactService 集成 + PathSecurityService 安全检查 0.5 天 + WebSocket 进度消息 + 测试 0.5 天 + session 资源清理集成 0.5 天）  
**完整度影响**：上下文管理 87% → 91%（+4%）  
**依赖项**：#5.1 Context Collapse 增强  
**状态**：❌ 全新功能，KeyFileTracker 需全新创建  

**配置项定义**：
- `reinjected-files-limit=5` — 最多重注入文件数
- `reinjected-file-max-tokens=5000` — 单文件最大 token 数

**埋点位置与具体代码**：需在以下工具中调用 `KeyFileTracker.trackFileReference()`：

**FileReadTool.java** — 在 L182 `cache.markRead(...)` 后插入（`call()` 方法内，文件读取成功后）：
```java
// FileReadTool.java L182 后插入：
// 需新增构造器参数 KeyFileTracker keyFileTracker
// 注意：ToolUseContext 无 turnId 字段，使用 toolUseId() 替代进行去重
keyFileTracker.trackFileReference(context.sessionId(), filePath, context.toolUseId());
```

**FileEditTool.java** — 在文件编辑成功后插入（`call()` 方法内，文件写入成功后）：
```java
// FileEditTool.java — 在文件写入成功后插入：
// 需新增构造器参数 KeyFileTracker keyFileTracker
keyFileTracker.trackFileReference(context.sessionId(), filePath, context.toolUseId());
```

**GrepTool.java** — 在搜索结果解析后，对每个命中的文件插入：
```java
// GrepTool.java — 在搜索结果中提取文件路径后插入：
// 需新增构造器参数 KeyFileTracker keyFileTracker
for (String matchedFile : matchedFiles) {
    keyFileTracker.trackFileReference(context.sessionId(), matchedFile, context.toolUseId());
}
```

> **注意**：以上三个工具均需在构造器中新增 `KeyFileTracker` 参数注入。`ToolUseContext` 无 `turnId` 字段，使用已有的 `toolUseId()` 方法替代进行同轮去重（每个工具调用都有唯一的 toolUseId，语义上等价于轮次级别去重）。

**去重处理**：`KeyFileTracker.trackFileReference()` 应在同一轮对话中对同一文件只计数一次（避免循环读取同一文件导致计数膨胀）。实现方式：在 `sessionFileRefs` 中使用 `Set<String>` 记录本轮已计数的 (sessionId, filePath, turnId) 三元组。

**现有 `reInjectFilesAfterCompact()` 安全缺陷完整修复代码**：

> ❗ **高危安全漏洞**：`CompactService.java` L837 的 `Files.readString(Path.of(path))` 直接读取文件，完全绕过 `PathSecurityService`！可能导致读取 `.env`、`.ssh/id_rsa` 等敏感文件。必须在文件读取前增加安全检查。

```java
// CompactService.java — 修复现有 reInjectFilesAfterCompact() L790-869
// 新增依赖注入（构造器参数）：
private final PathSecurityService pathSecurity;

// 在 L835 的 for 循环内，Files.readString() 前插入安全检查：
for (String path : validPaths) {
    // ★ PathSecurityService 安全检查 ★
    var securityCheck = pathSecurity.checkReadPermission(path, workingDirectory);
    if (!securityCheck.isAllowed()) {
        log.warn("文件重注入安全拦截: {} - {}", path, securityCheck.message());
        continue;  // 跳过敏感文件
    }
    try {
        String content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        // ... 后续截断和注入逻辑不变 ...
    } catch (IOException e) {
        log.warn("文件重注入读取失败: {}", path, e);
    }
}
```

**`rebuildAfterCompact()` 与现有 `reInjectFilesAfterCompact()` 的关系**：
- `reInjectFilesAfterCompact()` 是现有实现，基于正则从 LLM 摘要中提取文件路径，精确度依赖 LLM 输出。
- `rebuildAfterCompact()` 是新增实现，基于 `KeyFileTracker` 的访问历史追踪，精确度更高。
- **调用关系**：`rebuildAfterCompact()` 优先使用 `KeyFileTracker`，当 tracker 无记录时降级回退调用 `reInjectFilesAfterCompact()`。
- **并存关系**：两者并存，`rebuildAfterCompact()` 为主路径，`reInjectFilesAfterCompact()` 为降级回退。

**Session 资源清理**：`KeyFileTracker` 的 Caffeine 缓存虽然有 2 小时 TTL 自动过期，但应在 session 销毁时主动调用 `clearSession(sessionId)` 提前释放内存。调用位置：

```java
// SessionManager.deleteSession() — 在 session 清理时调用（源码 L301）
// 需在 SessionManager 中注入 KeyFileTracker
public void deleteSession(String sessionId) {
    try {
        hookService.executeSessionEnd(sessionId, Map.of("reason", "deleted"));
    } catch (Exception e) {
        log.warn("SESSION_END hook failed: {}", e.getMessage());
    }
    jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
    removeFileStateCache(sessionId);
    keyFileTracker.clearSession(sessionId);  // ← 新增：主动清理文件追踪缓存
    log.info("Session deleted: {}", sessionId);
}
```

**WebSocket 压缩进度消息**：当前 CompactService 无 WebSocket 依赖，压缩过程中无法向前端推送进度。需在 CompactService 中注入 WebSocketController，并新增 `CompactProgressPayload` 消息类型：

```java
// ServerMessage.java — 新增 #36 compact_progress
/** #36 compact_progress — 压缩实时进度 */
public record CompactProgressPayload(
    String sessionId,
    int processedMessages,     // 已处理消息数
    int totalMessages,         // 总消息数
    long tokensFreed,          // 已释放 Token 数
    String phase               // "summarizing" | "reinjecting" | "completed"
) {}

// CompactService.java — 在 generateLlmSummary() 期间分段推送进度
// 需新增依赖注入：private final WebSocketController webSocketController;
private void pushCompactProgress(String sessionId, int processed, int total, long freed, String phase) {
    webSocketController.pushToUser(sessionId, "compact_progress",
        new ServerMessage.CompactProgressPayload(sessionId, processed, total, freed, phase));
}
```

**新架构增强项（维度14）**：

- ~~**Spring Event 驱动文件追踪**~~：**已删除**（第五轮审查）——简化方案：`ConcurrentHashMap<String, AtomicInteger>` 直接计数，在工具执行时直接调用 `KeyFileTracker.trackFileReference()`，无需事件驱动的颗粒度解耦
- ~~**L1+L2 多层文件内容缓存**~~：**已删除**（第五轮审查）——改为本地 Caffeine 缓存（已有依赖），无 Redis 依赖，单机场景无需分布式缓存
- **`CompactService.rebuildAfterCompact()` 完整实现**：根据 `KeyFileTracker.getKeyFiles()` 获取 Top-5 文件，经 `PathSecurityService` 安全检查后截断注入压缩结果，现有正则提取作为降级回退
- **日志记录重注入指标**：通过 `log.info()` 记录重注入文件数、耗时等信息即可（~~原 MicroMeter 指标过度设计~~）
- **优先级**：中高

---

## 六、MCP集成提升（78% → 92%+）

### 6.1 资源发现完整实现

**目标**：将 `resources/list` 从占位实现升级为完整的资源发现和访问功能。

**当前状态**（源码审查修正 + 第六轮审查）：
- `ListMcpResourcesTool.java`（149行）和 `ReadMcpResourceTool.java`（144行）均为**完整实现**，非骨架。
- ⚠️ **资源发现部分已实现，discoverResources()缺失**：`readResource(uri)`（McpServerConnection.java L327-343）已完整实现资源读取；`resources` 字段（L30，`volatile List<McpResourceDefinition>`）已存在并有 getter/setter（L351-352）。但 `discoverResources()` 方法**不存在**，需参照 `discoverTools()`（L142-169）新建主动列举资源的方法。
- ❌ 需新增 `discoverResources()` 方法（参照 `discoverTools()` L142-169 模式）。
- ❌ **API 层完全缺失**：McpController.java（64行）仅有 5 个服务器管理端点（CRUD + restart + logs），无任何资源/提示词/重连 REST 端点，需新增完整 API 层。
- ❌ 前端组件 ResourcesPanel.tsx、ResourceDetailDrawer.tsx **不存在**。
- ❌ mcpStore.ts（31行）仅有 `mcpTools: Map<string, McpTool[]>`，无资源/提示词相关状态，需扩展。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/mcp/ListMcpResourcesTool.java` — 完整实现（无需改动）
2. `backend/src/main/java/com/aicodeassistant/mcp/ReadMcpResourceTool.java` — 完整实现（无需改动）
3. `backend/src/main/java/com/aicodeassistant/mcp/McpServerConnection.java` — 新增 discoverResources() + Caffeine 缓存
4. `backend/src/main/java/com/aicodeassistant/controller/McpController.java` — **新增 5 个 REST 端点**
5. `frontend/src/components/MCP/` — 资源列表 UI 组件（新建）
6. `frontend/src/store/mcpStore.ts` — 扩展资源/提示词状态

核心实现要点：

1. **新增 `McpServerConnection.discoverResources()` 方法**（参照 `discoverTools()` L142-169 模式）：
   - ⚠️ **不在 `performProtocolHandshake()` 内部调用**：握手流程（L92-137）已包含 3 次重试 + sleep 的容错逻辑，同步调用有超时风险。改为**握手成功后异步执行**：在 `connect()` 方法 L75 `performProtocolHandshake()` 之后，使用 `CompletableFuture.runAsync()` 异步调用
   - 发送 `resources/list` 请求，解析 resources 数组
   - **缓存策略**：全量覆盖（非追加），每次 discoverResources() 完全替换 `this.resources`。使用 Caffeine `Cache<String, List<McpResourceDefinition>>`（TTL 60s，与工具发现缓存策略一致），TTL 过期后下次访问触发重新发现
   - 错误处理：`McpProtocolException` 时记录 WARN 日志，不影响已连接状态

```java
// McpServerConnection.java — 新增方法（L170 之后，紧随 discoverTools()）
// ★ 注意：Caffeine 缓存声明需添加到类字段区域（L27 附近）：
//   private final Cache<String, List<McpResourceDefinition>> resourceCache =
//       Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(100).build();

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
            // ★ 全量覆盖策略（非追加）
            this.resources = List.copyOf(discovered);
            resourceCache.put(config.name(), this.resources);
            log.info("MCP server '{}': discovered {} resources", config.name(), discovered.size());
        }
    } catch (McpProtocolException e) {
        log.warn("resources/list failed for '{}': {}", config.name(), e.getMessage());
        // 不抛出异常，不影响连接状态
    }
}
```

**异步调用集成点**（在 `connect()` 方法 L75 附近）：
```java
// connect() 方法中，performProtocolHandshake() 之后
performProtocolHandshake();
// ★ 握手成功后异步发现资源（避免同步调用的超时风险）
if (this.status == McpConnectionStatus.CONNECTED) {
    CompletableFuture.runAsync(this::discoverResources)
        .exceptionally(ex -> {
            log.warn("Async resource discovery failed for '{}': {}", config.name(), ex.getMessage());
            return null;
        });
}
```

2. **`ReadMcpResourceTool`** 通过 `resources/read` 获取资源内容（L327-343 已实现），支持 text 和 blob 两种格式

3. **安全性：资源 URI 基础过滤**：
   - MCP 服务器可能返回敏感资源 URI（如 `file:///etc/passwd`），需在 `discoverResources()` 结果上应用基础安全过滤
   - 实现方式：在 `discoverResources()` 中对结果 URI 进行基础校验，过滤掉明显危险路径（如 `/etc/shadow`、`/etc/passwd` 等系统敏感文件），**不依赖企业策略**（6.4 已删除）
   - 过滤规则写在 `McpServerConnection` 内部即可，无需额外配置文件

```java
// McpServerConnection.java — discoverResources() 中过滤危险 URI
private static final Set<String> BLOCKED_URI_PATTERNS = Set.of(
    "/etc/passwd", "/etc/shadow", "/etc/sudoers",
    ".ssh/id_rsa", ".ssh/id_ed25519", ".gnupg/"
);

private boolean isSafeResourceUri(String uri) {
    if (uri == null) return false;
    String lowerUri = uri.toLowerCase();
    return BLOCKED_URI_PATTERNS.stream().noneMatch(lowerUri::contains);
}
```

4. **API 端点**（**需全部新增**：McpController.java（64行，路由前缀 `/api/mcp/servers`，参数为 `{name}`）当前仅有 5 个管理端点）

**McpController.java 当前端点（5个，路由前缀 `@RequestMapping("/api/mcp/servers")`）**：
- `GET /api/mcp/servers` — 列出所有服务器
- `POST /api/mcp/servers` — 添加服务器
- `DELETE /api/mcp/servers/{name}` — 删除服务器
- `POST /api/mcp/servers/{name}/restart` — 重启服务器
- `GET /api/mcp/servers/{name}/logs` — 获取日志

**需新增端点（5个，遵循现有路由前缀和 `{name}` 参数规范）**：
- `GET /api/mcp/servers/{name}/resources` — 资源列表
- `GET /api/mcp/servers/{name}/resources/{uri}` — 资源详情/读取（注意：uri 含 `://` 和 `/` 等特殊字符，不适合作为 path variable，实际实现使用 `@RequestParam`，见下方代码）
- `GET /api/mcp/servers/{name}/prompts` — 提示词列表（6.2 共用）
- `POST /api/mcp/servers/{name}/prompts/{promptName}/execute` — 提示词执行（6.2 共用）
- `POST /api/mcp/servers/{name}/reconnect` — 手动重连（6.3 共用）

```java
// McpController.java — 新增端点（在现有 getServerLogs 方法之后）

/** 获取 MCP 服务器资源列表 */
@GetMapping("/{name}/resources")
public ResponseEntity<Map<String, Object>> getResources(@PathVariable String name) {
    return mcpManager.getConnection(name)
        .map(conn -> ResponseEntity.ok(Map.<String, Object>of(
            "resources", conn.getResources(),
            "serverName", name)))
        .orElse(ResponseEntity.notFound().build());
}

/** 读取 MCP 资源内容 */
// 注意：URI 含特殊字符（如 file:///path），不适合作为 @PathVariable，
// 改用 @RequestParam 传递，前端请求为 GET /api/mcp/servers/{name}/resources?uri=xxx
@GetMapping("/{name}/resources/read")
public ResponseEntity<Map<String, String>> readResource(
        @PathVariable String name, @RequestParam String uri) {
    return mcpManager.getConnection(name)
        .map(conn -> {
            try {
                String content = conn.readResource(uri);
                return ResponseEntity.ok(Map.of("content", content, "uri", uri));
            } catch (McpProtocolException e) {
                return ResponseEntity.<Map<String, String>>status(502)
                    .body(Map.of("error", e.getMessage()));
            }
        })
        .orElse(ResponseEntity.notFound().build());
}

/** 手动重连 MCP 服务器 */
@PostMapping("/{name}/reconnect")
public ResponseEntity<Map<String, String>> reconnectServer(@PathVariable String name) {
    mcpManager.scheduleReconnect(name);
    return ResponseEntity.ok(Map.of("status", "reconnecting"));
}
```

5. **前端 mcpStore.ts 扩展**（当前仅 31 行，只有 `mcpTools`）：

```typescript
// mcpStore.ts — 新增资源和提示词状态
export interface McpResource {
    uri: string; name: string; mimeType: string | null; description: string;
}
export interface McpStoreState {
    mcpTools: Map<string, McpTool[]>;
    mcpResources: Map<string, McpResource[]>;  // ★ 新增
    // ... 新增 updateMcpResources、clearServerResources 方法
}
```

6. **ROI 评估与分期策略**：
   - **Phase 1（本期）**：后端 discoverResources() + Caffeine 缓存 + 5 个 REST 端点 + mcpStore 状态扩展
   - **Phase 2（后续迭代）**：前端 ResourcesPanel.tsx / ResourceDetailDrawer.tsx UI 组件（用户在前端直接浏览 MCP 资源的实际需求待验证）

**工作量**：8-9 人天（后端 discoverResources 1 天 + API 端点层 2-3 天 + 前端 mcpStore 扩展 1 天 + Caffeine 缓存 + 基础安全过滤 1.5 天 + 测试 1.5 天；前端 ResourcesPanel/ResourceDetailDrawer UI 为 P3 可选，不计入本期工作量）  
**完整度影响**：MCP 78% → 83%（+5%）  
**依赖项**：无（~~6.4 企业策略支持已删除~~）  

**PC 浏览器交互设计**（P3 可选，Phase 2 前端）：

> **ROI 评估**：本地部署用户 99% 通过 Bash/FileRead 工具间接访问资源，前端 ResourcesPanel 的直接浏览价值有限。建议后端 + API 优先交付，前端 UI 降级为 P3 后续迭代。

| UI 组件 | 功能 | 实现方式 | 优先级 |
|---------|------|--------|--------|
| ResourcesPanel.tsx（**新建**） | MCP 面板中"资源"标签页 | 列表展示 uri/name/mimeType/description，TailwindCSS 响应式布局（sm:单列 / md:双列 / lg:三列），触控区域 ≥ 44x44px | P3 可选 |
| ResourceDetailDrawer.tsx（**新建**） | 资源详情抽屉 | 点击资源行 → 右侧抽屉展示完整内容，移动端为底部 Sheet | P3 可选 |
| 资源搜索过滤 | 按名称/URI 过滤 | 顶部搜索框 + debounce（300ms） | P3 可选 |
| 资源内容预览 | text 类型直接展示，blob 类型显示下载链接 | 根据 mimeType 渲染不同预览器 | P3 可选 |

WebSocket 消息类型：`mcp_resources_updated`（当 MCP 服务器资源列表变更时推送）  

**新架构增强项（维度14）**：
- ~~**Spring WebClient 异步资源发现**~~：**已删除**（第五轮审查）——保持使用 OkHttp（与现有 MCP 一致，避免引入新依赖和破坏握手顺序）
- ~~**多 MCP 服务器负载均衡**~~：**已删除**（第五轮审查）——个人助手通常只有 1-3 个 MCP 服务器，无需负载均衡
- **SpringDoc-OpenAPI 自动文档**（P3 可选）：新增的 `/api/mcp/servers/{name}/resources` 等端点通过 `@Operation` 注解自动生成 API 文档
- **Caffeine 直接缓存资源列表**：在 `McpServerConnection` 中使用 `Caffeine Cache<String, List<McpResourceDefinition>>`（TTL 60s），全量覆盖策略（非追加），与 `discoverTools()` 的工具缓存策略保持一致。**注意**：无需 `@Cacheable` 抽象，直接使用 Caffeine API

**工作量调整**：9 → 8-9 人天（后端 + API 优先交付；前端 ResourcesPanel UI 降为 P3 可选，不计入本期）
**优先级**：P2（第 7-10 周）

---

### 6.2 提示词发现 UI 集成

**目标**：将已有的 `prompts/list` 调用结果集成到前端 UI。

**当前状态**（源码审查修正 + 第六轮审查）：
- `McpPromptAdapter.java`（128行）后端完整：`execute()`（L67-92）发送 `prompts/get` 请求并解析消息，但 McpController 无对应 REST 端点。
- ⚠️ **参数验证缺失**：`McpPromptAdapter.execute()`（L67-92）未检查用户提供的参数是否与 prompt definition 中 `required` 字段匹配，可能导致 MCP 服务器端报错。
- ⚠️ **异常日志不完整**：`execute()` L87-92 捕获异常后未记录 `arguments` 参数值，不利于问题排查。
- ❌ 前端组件 PromptsTab.tsx、PromptArgsForm.tsx **不存在**。
- ❌ mcpStore.ts（31行）无提示词状态。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/mcp/McpPromptAdapter.java` — 新增参数验证 + 日志增强
2. `backend/src/main/java/com/aicodeassistant/controller/McpController.java` — 新增 `GET /api/mcp/servers/{name}/prompts` + `POST .../prompts/{promptName}/execute`（注意：路由前缀为 `/api/mcp/servers`，参数为 `{name}` 而非 `{serverId}`）
3. `frontend/src/components/MCP/` — 新增 Prompts 选项卡组件（与 Tools 并列显示在 MCP 面板中）
4. `frontend/src/store/mcpStore.ts` — 新增 `mcpPrompts` 状态和 `updateMcpPrompts(serverId, prompts)` 方法

**参数验证增强**（McpPromptAdapter.java L67 execute() 方法前插入）：
```java
// ★ 参数验证：检查 required 字段是否存在
public List<Map<String, String>> execute(Map<String, String> arguments) {
    // 新增: 验证必填参数
    if (promptDefinition.arguments() != null) {
        List<String> missingRequired = promptDefinition.arguments().stream()
            .filter(McpPromptArgument::required)
            .map(McpPromptArgument::name)
            .filter(argName -> arguments == null || !arguments.containsKey(argName)
                    || arguments.get(argName).isBlank())
            .toList();
        if (!missingRequired.isEmpty()) {
            log.warn("Missing required arguments for prompt '{}': {}", 
                    promptDefinition.name(), missingRequired);
            return List.of(Map.of("role", "system", "content",
                    "Missing required arguments: " + missingRequired));
        }
    }
    try {
        // ... 原有逻辑 ...
    } catch (McpProtocolException e) {
        // ★ 增强: 记录 arguments 参数值
        log.error("Failed to execute MCP prompt '{}' on server '{}' with arguments {}: {}",
                promptDefinition.name(), serverName, arguments, e.getMessage());
        // ...
    }
}
```

**XSS 安全防护**：
- 用户输入的 prompt parameters 注入聊天输入框时需 HTML 转义（React JSX 默认转义已提供基础 XSS 防护；若需处理富文本，可安装 `npm install dompurify @types/dompurify` 后使用 `DOMPurify.sanitize()`）
- PromptArgsForm.tsx 中对用户输入字段统一用 `textContent` 而非 `innerHTML` 设置

**用户交互流程**：用户选择 MCP 服务器 → 查看 Prompts 标签页 → 选择某个 Prompt → 填写参数（带验证）→ 注入到输入框（经 XSS 过滤）

**工作量**：3-4 人天（后端 API + 参数验证 1 天 + 前端 PromptsTab UI 1.5 天 + XSS 防护 0.5 天 + 测试 0.5 天；~~模板库和智能推荐已删除~~）  
**完整度影响**：MCP 83% → 85%（+2%）  
**依赖项**：无  

**PC 浏览器交互设计**：

PromptsTab 需支持以下交互流程：
1. 用户在 MCP 面板中切换到 "Prompts" 标签页
2. 展示 Prompt 列表（name + description），支持搜索过滤
3. 用户点击某个 Prompt → 展开参数输入表单（动态生成 required/optional 字段，必填字段带 `*` 标记）
4. 用户填写参数后点击"使用" → Prompt 内容经 XSS 过滤后注入到聊天输入框
5. 涉及前端组件：PromptsTab.tsx（**新建**）、PromptArgsForm.tsx（**新建**），TailwindCSS 响应式设计（sm:(640px) / md:(768px) / lg:(1024px)），参数表单在移动端改为全屏弹窗，触控区域 ≥ 44x44px

**新架构增强项（维度14）**：
- ~~**Prompt 模板库**~~：**已删除**（第十五轮审查）——个人用户 MCP Prompt 数量通常 < 10 个，专门的模板库 ROI 极低
- ~~**参数智能推荐**~~：**已删除**（第十五轮审查）——个人用户直接填写参数更直觉，上下文智能推荐复杂度高且收益≈ 0
- ~~**WebSocket 推送 `prompt_suggestion` 消息**~~：**已删除**（第十五轮审查）——主动推荐 Prompt 依赖智能推荐引擎，已随智能推荐一并删除
- ~~**前端 `PromptTemplateLibrary.tsx`**~~：**已删除**（第五轮审查）——改为简化的 `PromptsTab.tsx` 基础列表展示，支持搜索、参数填写和一键使用

> **实现阶段**：基础 PromptsTab.tsx 前端 UI + McpPromptAdapter 参数验证 + XSS 防护为本期交付物。

**工作量调整**：3 → 3-4 人天（参数验证 + XSS 防护 + 基础 PromptsTab UI；~~模板库和智能推荐已删除~~）
**优先级**：P2（第 7-10 周）

---

### 6.3 主动健康检查增强

**目标**：增强现有 `SseHealthChecker` 的自动重连策略。

**当前状态**（源码审查修正 + 第六轮审查）：
- `SseHealthChecker.java`（50行）已实现 30s 周期 ping + DEGRADED 标记 + 重连调度。
- ✅ 指数退避重连策略**已在 McpClientManager.java L338-341 实现**（1s→2s→4s→8s→16s→30s cap）。SseHealthChecker.java（50行）每 30s 执行健康检查，失败时标记 DEGRADED 并触发重连。
- ✅ `reconnectAttempts` 字段**已存在**于 `McpServerConnection.java`（L31, `private volatile int reconnectAttempts;`），含 getter/increment/reset 方法（L353-355）。
- ✅ **职责分工正确**：
  - `McpClientManager.healthCheck()`（L412-424，`@Scheduled(fixedDelay=30000)`）: **被动检查** — 通过 `isAlive()` 标志位检测已经 FAILED 的连接并重连
  - `SseHealthChecker.performActiveHealthCheck()`（L34-47，`@Scheduled(fixedRate=30_000)`）: **主动探测** — 发送 ping 检测 CONNECTED 但实际已断开的连接
  - ★ 两者职责分工是正确的，只需在 `scheduleReconnect()` 中加入幂等检查（若已在 DEGRADED/RECONNECTING 状态则跳过），避免两个调度器同时触发重连
- ❌ `consecutiveFailures`/`lastSuccessfulPing` 字段**不存在**于 `SseHealthChecker.java` 中，需新增。
- ❌ **WebSocket 推送缺失**：文档要求推送 `mcp_health_status` 消息，但代码无此实现。需在重连成功/失败时添加 WebSocket 广播。
- ⚠️ **CompletableFuture 线程池**：`scheduleReconnect()`（McpClientManager.java L456-462）使用 `CompletableFuture.runAsync()` 默认 ForkJoinPool，建议指定自定义线程池避免影响其他并行任务。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/mcp/SseHealthChecker.java` — 指标增强（consecutiveFailures + lastSuccessfulPing）
2. `backend/src/main/java/com/aicodeassistant/mcp/McpClientManager.java` — 职责划分优化 + 自定义线程池 + WebSocket 广播
3. `backend/src/main/java/com/aicodeassistant/controller/McpController.java` — 新增 `POST /api/mcp/servers/{name}/reconnect` 端点（6.1 已定义）

增强要点：
- 添加连接健康指标：`consecutiveFailures`、`lastSuccessfulPing`（`reconnectAttempts` 已在 `McpServerConnection.java` L31 实现，无需重复添加）
- **幂等重连保护**：在 `McpClientManager.scheduleReconnect()` 中加入重连状态检查，避免两个调度器同时触发重连（职责分工本身是正确的，只需加幂等保护）

```java
// SseHealthChecker.java — 新增健康指标字段（类定义区域）
private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
private final Map<String, Instant> lastSuccessfulPing = new ConcurrentHashMap<>();

// performActiveHealthCheck() 中更新指标：
@Scheduled(fixedRate = 30_000)
public void performActiveHealthCheck() {
    mcpClientManager.getActiveConnections().forEach(conn -> {
        String name = conn.getConfig().name();
        try {
            boolean alive = conn.sendHealthPing();
            if (alive) {
                consecutiveFailures.put(name, 0);
                lastSuccessfulPing.put(name, Instant.now());
            } else {
                int failures = consecutiveFailures.merge(name, 1, Integer::sum);
                log.warn("Health ping failed for '{}', consecutive failures: {}", name, failures);
                if (failures >= 2) {
                    conn.setStatus(McpConnectionStatus.DEGRADED);
                    mcpClientManager.scheduleReconnect(name);
                }
            }
        } catch (Exception e) {
            int failures = consecutiveFailures.merge(name, 1, Integer::sum);
            log.warn("Health ping exception for '{}' (failures={}): {}", name, failures, e.getMessage());
            if (failures >= 2) {
                conn.setStatus(McpConnectionStatus.DEGRADED);
                mcpClientManager.scheduleReconnect(name);
            }
        }
    });
}
```

```java
// McpClientManager.java — scheduleReconnect() 改造（幂等保护 + 自定义线程池 + WebSocket 广播）
private static final ExecutorService RECONNECT_POOL = 
    java.util.concurrent.Executors.newFixedThreadPool(2, 
        r -> { Thread t = new Thread(r, "mcp-reconnect"); t.setDaemon(true); return t; });

// ★ 幂等重连保护：使用 ConcurrentHashMap<String, Boolean> 记录正在重连的服务器
private final ConcurrentHashMap<String, Boolean> reconnectingServers = new ConcurrentHashMap<>();

public void scheduleReconnect(String connectionName) {
    getConnection(connectionName).ifPresent(conn -> {
        // ★ 幂等检查：通过 putIfAbsent 原子操作避免重复触发重连
        // 注意：不能仅检查 DEGRADED 状态——若重连失败状态仍为 DEGRADED，
        // 会导致后续重连尝试被永久跳过（死锁 bug）
        if (reconnectingServers.putIfAbsent(connectionName, Boolean.TRUE) != null) {
            log.debug("Reconnect already in progress for '{}', skipping", connectionName);
            return;
        }
        conn.setStatus(McpConnectionStatus.DEGRADED);
        // ★ 使用自定义线程池替代默认 ForkJoinPool
        CompletableFuture.runAsync(() -> {
            try {
                attemptReconnect(connectionName, conn);
                // ★ 重连完成后通过 WebSocket 广播状态变更
                broadcastHealthStatus(connectionName, conn.getStatus());
            } finally {
                // ★ 无论成功失败，必须清除幂等标记，允许后续重试
                reconnectingServers.remove(connectionName);
            }
        }, RECONNECT_POOL);
    });
}

// ★ 新增: WebSocket 广播连接状态变更（完整实现）
private void broadcastHealthStatus(String serverName, McpConnectionStatus status) {
    // 通过 WebSocketHandler 推送 mcp_health_status 消息到所有已连接的前端会话
    Map<String, Object> payload = Map.of(
        "serverName", serverName,
        "status", status.name(),
        "timestamp", Instant.now().toEpochMilli()
    );
    // pushToAllSessions("mcp_health_status", payload)
    // 实现方式：注入 WebSocketSessionManager（已有组件，121行），需新增 getActiveSessionIds() 方法
    // 该方法返回 sessionToPrincipal.keySet() 的快照，调用 pushToUser 三参数格式
    wsSessionManager.getActiveSessionIds().forEach(sessionId -> {
        try {
            pushToUser(sessionId, "mcp_health_status", payload);
        } catch (Exception e) {
            log.debug("Failed to push health status to session {}: {}", sessionId, e.getMessage());
        }
    });
}
```

- 重连策略：1s → 2s → 4s → 8s → 16s → 30s cap（已实现）
- WebSocket 端点暴露连接状态给前端（**需新增**）
- `WebSocketSessionManager`（已有组件，121行）需新增 `getActiveSessionIds()` 方法：
  ```java
  // WebSocketSessionManager.java — 新增
  public Set<String> getActiveSessionIds() {
      return Set.copyOf(sessionToPrincipal.keySet());
  }
  ```

**McpConnectionIndicator.tsx 前端组件规格**：
```tsx
// frontend/src/components/MCP/McpConnectionIndicator.tsx — 新建
// 功能：展示 MCP 服务器连接状态，监听 mcp_health_status WebSocket 消息
// Props: { serverName: string; status: 'CONNECTED' | 'DEGRADED' | 'FAILED'; onReconnect: () => void }
// UI 规格：
//   - 状态图标：🟢 CONNECTED / 🟡 DEGRADED / 🔴 FAILED
//   - 重连按钮：DEGRADED/FAILED 时显示，触发 POST /api/mcp/servers/{name}/reconnect
//   - TailwindCSS 响应式：sm: 仅显示图标 / md+: 显示图标+文字状态
//   - 触控适配：最小点击区域 44x44px
// WebSocket 监听：在 stompClient 的消息处理中监听 type="mcp_health_status"
// 集成位置：MCP 面板的服务器列表项中，服务器名称旁边
```

**WebSocket 消息格式**：
```json
{
  "type": "mcp_health_status",
  "serverName": "filesystem",
  "status": "CONNECTED",
  "timestamp": 1713427200000
}
```

**工作量**：2-3 人天（职责分工幂等保护 0.5 天 + 自定义线程池 0.5 天 + WebSocket 广播机制 0.5 天 + consecutiveFailures/lastSuccessfulPing 指标 0.5 天 + 前端 McpConnectionIndicator 组件 0.5 天 + 测试 0.5 天）  
**完整度影响**：MCP 85% → 87%（+2%）  
**依赖项**：无  
**状态**：✅ 核心已完整实现（30s ping + DEGRADED + 指数退避重连调度，职责分工正确），需增强幂等重连保护、WebSocket 推送和线程池优化  

**PC 浏览器交互设计**：

MCP 连接状态需在前端实时展示：
- MCP 面板中每个服务器旁显示连接状态图标（🟢 正常 / 🟡 降级 / 🔴 断开）
- WebSocket 消息类型：`mcp_health_status`（推送连接状态变更，**需新增实现**，使用三参数格式 `pushToUser(sessionId, "mcp_health_status", payload)`）
- 用户可手动点击“重连”按钮触发 `POST /api/mcp/servers/{name}/reconnect`（**需新增**：McpController.java 当前无此端点，已在 6.1 方案中定义）
- 涉及前端组件：McpConnectionIndicator.tsx（**新建**，可复用于 MCP 面板的服务器列表项），TailwindCSS 响应式设计（sm: 仅图标 / md+: 图标+文字），触控区域 ≥ 44x44px

**新架构增强项（维度14）**：
- **Spring Boot Actuator 集成**：MCP 连接状态注册为自定义 `HealthIndicator`，暴露到 `/actuator/health` 端点，运维可统一监控
- **MicroMeter 指标**：注册 `mcp.connection.latency`（Timer）和 `mcp.connection.failures`（Counter）指标，支持对接 Prometheus/Grafana
- **WebSocket 实时事件**：连接状态变更时推送 `mcp_health_status` 消息（包含 latency、status、serverName），前端实时刷新（**需新增实现**）
- ~~**`@Retryable` 自动重连**~~：~~原方案使用 Spring Retry 的 `@Retryable` 注解~~——改为在 `scheduleReconnect()` 中直接实现指数退避算法（maxAttempts=5, backoff=1s→30s cap），避免与现有重连逻辑重复，无需引入 spring-retry 依赖

> **实现阶段**：核心健康检查已实现（SseHealthChecker.java），幂等重连保护 + WebSocket 广播 + 自定义线程池 + McpConnectionIndicator 前端组件为 P0 必修项；Actuator HealthIndicator 和 MicroMeter 指标为 Week 8+ 增强项。

**工作量调整**：1-1.5 → 2-3 人天（+1-1.5 人天用于幂等重连保护 + WebSocket 广播机制 + 自定义线程池 + McpConnectionIndicator 前端组件）
**优先级**：中

---

#### ~~6.4 企业策略支持~~（第十五轮审查已删除 — 本地部署无需企业策略）

> **删除原因**：本地个人部署场景下，企业策略支持（allowlist/denylist/managedRulesOnly）ROI 极低（8-10 人天投入，获益≈ 0）。
>
> **核心问题分析**：
> - **Allowlist**：个人用户自己配置需要的 MCP 服务器，无需白名单机制
> - **Denylist**：个人用户不需要策略级禁用，临时禁用直接删除配置即可
> - **managedRulesOnly**：企业锁定模式，防止员工修改配置——个人用户无此需求
> - `McpConfigurationResolver.java`（248行）已实现 4 层配置合并，但完全无 allowlist/denylist 过滤——本地部署也无需实现
>
> **已删除内容**：
> - McpEnterprisePolicy record 定义
> - McpConfigurationResolver allowlist/denylist 过滤逻辑
> - McpClientManager.initializeAll() 策略集成
> - loadEnterprisePolicy() / parseStringSet() 方法
> - 前端 PolicyStatusBadge.tsx 组件
> - 8-10 人天工作量已归还
>
> **保留项**：`PolicySettingsSource.java` L52 TOCTOU 竞态条件修复（`volatile long lastModified` → `AtomicLong`）已移至 8.2 节作为独立 bug 修复项。
>
> **远期可选**：若未来 ZhikuCode 需支持团队/企业部署场景，可重新评估此方案。当前本地个人部署无需实现。
>
> **工作量**：~~8-10 人天~~ → **0 人天**（已删除）  
> **完整度影响**：MCP 模块目标从 92%+ 调整为 90%（删除不影响实际使用体验）

> ⚠️ **MCP 模块工作量注意**（方案 6.1-6.3 整体，第十五轮审查修正）：6.4 已删除，剩余 3 个方案的 API 端点层（REST Controller）和前端组件层均**完全未实现**。MCP 模块总估调整为 **13-16 人天**（6.1: 8-9 + 6.2: 3-4 + 6.3: 2-3）。

---

## 七、Agent Loop 提升（90% → 93%）

> **审查修正说明**：原方案 7.2（消息扣留与选择性重试）已删除——MessageHoldback.java 不存在需全新创建，且依赖已删除的 5.3（ReactiveCompactService），依赖链断裂。目标完整度从 97% 调整为 93%。

### 7.1 Token 预算续写增强

**目标**：增强现有 `TokenBudgetTracker` 的集成深度。

**当前状态**（源码审查修正）：
- ✅ **核心逻辑已完整实现**：`TokenBudgetTracker.java`（92行）已完整实现 nudge + 递减回报检测（`ContinueDecision` / `StopDecision` sealed interface，含 `nudgeMessage`、`pct`、`turnTokens`、`budget` 字段），核心逻辑 **100% 完整**，模型无关（token计数完全基于 output tokens）。
- ✅ 测试报告确认 TokenBudget 续写已通过验证。
- ❌ **WebSocket 推送完全缺失**：`QueryEngine.java` L462-472 中 nudge 消息仅通过 `state.addMessage(nudgeMsg)` 添加到对话历史，**完全没有调用 `handler` 的任何推送方法**。当前 `QueryMessageHandler` 接口（69行）定义了 `onTextDelta`、`onToolUseStart`、`onToolResult`、`onUsage`、`onCompactEvent` 等事件，但**无 `onTokenBudgetNudge` 方法**。`WsMessageHandler`（`WebSocketController.java` L471-528）实现了 `QueryMessageHandler`，通过 `pushToUser(sessionId, type, payload)` 将事件推送到前端——nudge 事件需要在此链路中补充。
- ❌ **ServerMessage 缺少 TokenBudgetNudge 类型**：`ServerMessage.java`（168行）当前定义了 32 种消息类型（#1-#32），新增了 #33-#35（Swarm 相关，见 4.1）和 #36（CompactProgressPayload，见 5.2），但**完全缺少 `TokenBudgetNudge`**。需新增为 #37。
- ❌ 前端 `TokenBudgetIndicator.tsx` **需新建**。

**实施方案**：

涉及文件列表：
1. `backend/src/main/java/com/aicodeassistant/websocket/ServerMessage.java` — 新增 `TokenBudgetNudge` record（#37）
2. `backend/src/main/java/com/aicodeassistant/engine/QueryMessageHandler.java` — 新增 `onTokenBudgetNudge` 方法
3. `backend/src/main/java/com/aicodeassistant/websocket/WebSocketController.java` — `WsMessageHandler` 实现推送
4. `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` — L462-472 补充 `handler.onTokenBudgetNudge()` 调用
5. `frontend/src/components/TokenBudgetIndicator.tsx` — **新建**，nudge 进度条 UI

**Step 1: ServerMessage.java 新增 TokenBudgetNudge（在 #36 CompactProgressPayload 之后新增 #37）**：
```java
// ServerMessage.java — 在 #36 CompactProgressPayload 之后新增 #37
/** #37 token_budget_nudge — Token 预算续写提示 */
public record TokenBudgetNudge(int pct, int currentTokens, int budgetTokens) {}
```

**Step 2: QueryMessageHandler.java 新增事件方法（L67 区域）**：
```java
// QueryMessageHandler.java — 在 P1 消息类型区域新增
/** Token 预算续写 nudge — 推送到前端显示进度 */
default void onTokenBudgetNudge(int pct, int currentTokens, int budgetTokens) {}
```

**Step 3: WebSocketController.java WsMessageHandler 实现推送（L527 区域）**：
```java
// WebSocketController.java — WsMessageHandler 类中新增
@Override
public void onTokenBudgetNudge(int pct, int currentTokens, int budgetTokens) {
    pushToUser(sessionId, "token_budget_nudge",
            new ServerMessage.TokenBudgetNudge(pct, currentTokens, budgetTokens));
}
```

**Step 4: QueryEngine.java L462-472 补充 handler 推送调用**：
```java
// QueryEngine.java — 在 ContinueDecision 分支中，state.addMessage(nudgeMsg) 之后新增：
if (decision instanceof TokenBudgetTracker.ContinueDecision cont) {
    log.info("Token budget continuation #{}: {}%",
            cont.continuationCount(), cont.pct());
    Message.UserMessage nudgeMsg = new Message.UserMessage(
            UUID.randomUUID().toString(), Instant.now(),
            List.of(new ContentBlock.TextBlock(cont.nudgeMessage())),
            null, null);
    state.addMessage(nudgeMsg);
    // ★ 新增：WebSocket 推送 token_budget_nudge 到前端
    // ★ 消息顺序约束：nudge 推送必须在 state.addMessage() 之后立即执行，
    //   确保前端收到 nudge 时对应的对话历史已更新
    handler.onTokenBudgetNudge(cont.pct(), cont.turnTokens(), cont.budget());
    state.setHasAttemptedReactiveCompact(false);
    handler.onTurnEnd(turn, "token_budget_continuation");
    continue;
}
```

**Step 5: 前端 TokenBudgetIndicator.tsx 组件规格**：
```tsx
// frontend/src/components/TokenBudgetIndicator.tsx — 新建
// 功能：接收 token_budget_nudge WebSocket 消息，渲染 token 预算进度条
// Props: { pct: number; currentTokens: number; budgetTokens: number }
// UI 规格：
//   - 水平进度条，宽度 100%，高度 8px，圆角
//   - 颜色阈值：pct < 50% 绿色，50-75% 黄色，>75% 红色
//   - 文字标签："Token 预算: {currentTokens.toLocaleString()} / {budgetTokens.toLocaleString()} ({pct}%)"
//   - 响应式：sm: 隐藏文字仅显示进度条，md+: 显示完整文字
//   - 样式参考：现有 TokenWarning 消息的渲染样式（ServerMessage #27）
// WebSocket 监听：在 stompClient 的消息处理中监听 type="token_budget_nudge"
// 集成位置：ChatMessageItem 组件中，在消息内容下方渲染
// 触控适配：最小高度 44px（移动端）
```

**WebSocket 消息格式**：
```json
{
  "type": "token_budget_nudge",
  "pct": 85,
  "currentTokens": 8500,
  "budgetTokens": 10000
}
```

**关键约束**：
- 子 agent（`agentId != null`，即 `nestingDepth > 0`）严格不参与预算续写（`TokenBudgetTracker.check()` L55 已处理）
- `TokenBudgetTracker` 的 token 计数完全基于 `output tokens`，不依赖特定模型，千问/DeepSeek/Ollama 均可正常运行
- **消息顺序**：nudge 消息必须在 `state.addMessage(nudgeMsg)` 之后立即推送，确保前端收到时对应的对话历史已同步

**Step 6: 前端 messageStore 状态扩展**：
```typescript
// frontend/src/store/messageStore.ts — 新增 tokenBudgetState
interface TokenBudgetState {
    pct: number;
    currentTokens: number;
    budgetTokens: number;
    visible: boolean;
}

// 在 store 中新增：
tokenBudgetState: TokenBudgetState | null;
setTokenBudgetState: (state: TokenBudgetState | null) => void;

// 会话结束时清空：
clearTokenBudgetState: () => set({ tokenBudgetState: null });
```

**Step 7: 前端 WebSocket 消息监听集成位置**：
```typescript
// frontend/src/hooks/useWebSocket.ts — 在现有 WebSocket 消息处理 switch/case 中新增：
case 'token_budget_nudge': {
    const { pct, currentTokens, budgetTokens } = message.payload;
    useMessageStore.getState().setTokenBudgetState({
        pct,
        currentTokens,
        budgetTokens,
        visible: true
    });
    break;
}
// 集成位置说明：
// 1. WebSocket 监听在 useWebSocket.ts hook 中（处理所有 WebSocket 消息路由）
// 2. TokenBudgetIndicator 组件在 ChatMessageItem.tsx 中渲染，位于消息内容下方
// 3. 会话结束时通过 clearTokenBudgetState() 清空状态
```

**工作量**：3-3.5 人天（后端 WebSocket 推送全链路 1 天：ServerMessage + QueryMessageHandler + WsMessageHandler + QueryEngine 四文件修改 | 前端 TokenBudgetIndicator 组件 1-1.5 天 | 测试 1 天：含 WebSocket 推送验证 + 前端渲染验证 + 子代理不触发验证）  
**完整度影响**：Agent Loop 90% → 93%（+3%）  
**依赖项**：无  
**状态**：✅ 核心逻辑已完整实现，需补充 WebSocket 推送全链路（4 个后端文件）+ 前端 UI

**新架构增强项（维度14）**：
- ~~**Actuator 指标暴露**~~：**已删除**（第五轮审查）——个人助手无需集中监控 Token 消耗趋势
- ~~**WebSocket 双向通信：前端可手动调整预算**~~：**已删除**（第五轮审查）——用户极少调整预算，功能价值≈ 0
- ~~**历史趋势**~~：**已删除**（第五轮审查）——~~原方案使用 Spring Data 持久化~~，无 JPA 依赖，无需跨会话分析
- ~~**智能预算建议**~~：**已删除**（第五轮审查）——功能价值≈ 0
- **替代方案**：补充 WebSocket `token_budget_nudge` 推送全链路（ServerMessage → QueryMessageHandler → WsMessageHandler → QueryEngine）+ 前端进度条组件

> **实现阶段**：基础 nudge UI（TokenBudgetIndicator 组件）为 Week 2 交付物，核心逻辑已完整实现。

**工作量调整**：1.5 → 3-3.5 人天（后端需修改 4 个文件建立 WebSocket 推送全链路 + 前端新建组件 + 测试覆盖）
**优先级**：中

---

## 八、权限系统提升（93% → 95%+）

> **第五轮审查修正说明**：方案 8.1（远程熔断集成）和 8.3（规则遮蔽检测）已删除——本地 AI 编程助手无远程服务调用，熔断机制无适用场景；99% 用户使用预设规则，规则遮蔽检测 ROI 极低。方案 8.2 从 P2 降级为 P4 可选并大幅简化。目标完整度从 98% 调整为 95%+。总节省 15 人天（8.1 的 8天 + 8.3 的 7天）。

### ~~8.1 远程熔断集成~~（第五轮审查已删除）

> **删除原因**：本地 AI 编程助手无远程服务调用，熔断机制无适用场景。
>
> **核心问题分析**：
> - `RemoteCircuitBreaker.java` **不存在**
> - 文档假设存在“远程权限服务”需要熔断，但**代码中根本没有远程服务**
> - 权限系统完全在本地执行，不涉及远程调用
> - `FeatureFlagService` 中不包含 `BYPASS_PERMISSIONS_KILLSWITCH` 和 `AUTO_MODE_CIRCUIT_BROKEN`
> - `pom.xml` 无 Resilience4j 依赖
> - **ROI 极低**：8 人天投入解决不存在的问题
>
> **已删除内容**：
> - RemoteCircuitBreaker.java 新建
> - Resilience4j CircuitBreaker 集成
> - Spring AOP `@FallbackPolicy` 声明式降级
> - Actuator `/actuator/circuitbreakers` 端点
> - 前端 PermissionDowngradeAlert.tsx 和 CircuitBreakerStatus.tsx
> - 8 人天工作量已归还
>
> **工作量**：~~8 人天~~ → **0 人天**（已删除）  
> **完整度影响**：权限系统目标从 98% 调整为 95%+（删除不影响实际使用体验）

---

### ~~8.2 企业策略覆盖~~（第十五轮审查已删除 — 本地部署无需企业锁定）

> **删除原因**：本地个人部署场景下，企业策略覆盖（isManagedRulesOnly + 锁定模式）ROI 极低（0.5-1 人天投入，获益≈ 0）。
>
> **核心问题分析**：
> - 锁定模式是企业防止员工修改权限规则的机制，个人用户无此需求
> - `isManagedRulesOnly()` 方法在 `PolicySettingsSource.java` 中完全不存在
> - `mergeExternalRules()` L247-271 缺失锁定模式检查——但本地部署无需实现
> - 前端 PolicyLockIndicator.tsx 无实际价值
>
> **已删除内容**：
> - PolicySettingsSource 新增 `isManagedRulesOnly()` 方法
> - PermissionRuleRepository `mergeExternalRules()` 锁定模式检查
> - 前端 PolicyLockIndicator.tsx 组件
> - 0.5-1 人天工作量已归还
>
> **工作量**：~~0.5-1 人天~~ → **0 人天**（已删除）  
> **完整度影响**：权限系统目标从 95%+ 微调（删除不影响实际使用体验）

#### 8.2.1 PolicySettingsSource TOCTOU 竞态条件修复（独立 bug 修复，保留）

> **保留原因**：这是一个真实的并发 bug，与企业策略无关，影响 PolicySettingsSource 的缓存正确性。

**当前状态**：
- `PolicySettingsSource.java`（145行）L52 使用 `private volatile long lastModified = 0;`
- `loadRules()` 方法中 check-then-act 模式存在 TOCTOU 竞态条件：并发调用时可能多次重复加载

**修复方案**：

涉及文件：`backend/src/main/java/com/aicodeassistant/permission/PolicySettingsSource.java`

```java
// 原始代码（L52）：
private volatile long lastModified = 0;  // ← TOCTOU 竞态条件

// 修复为：
private final AtomicLong lastModified = new AtomicLong(0);

// loadRules() 中对应修改（L72 缓存比较处，方法体起始于 L64）：
long currentModified = Files.getLastModifiedTime(policyFilePath).toMillis();
if (currentModified == lastModified.get() && !cachedRules.isEmpty()) {
    return cachedRules;  // 缓存命中，无需重新加载
}
// ... 加载逻辑 ...
lastModified.set(currentModified);  // 原子更新

// invalidateCache() 中对应修改（L121）：
lastModified.set(0);  // 原子重置
```

**工作量**：0.5 人天（一行代码改动 + 单元测试）  
**优先级**：P0（真实 bug 修复）  
**依赖项**：无

---

### ~~8.3 规则遮蔽检测~~（第五轮审查已删除）

> **删除原因**：99% 用户使用预设规则，极少手工编写复杂规则，ROI 极低。
>
> **核心问题分析**：
> - `ShadowedRuleDetector.java` **不存在**
> - 99% 用户使用预设规则，极少手工编写复杂规则
> - Python FastAPI 辅助分析增加跨语言复杂度
> - **ROI 极低**：7 人天投入解决极少见的问题
>
> **已删除内容**：
> - ShadowedRuleDetector.java 新建
> - Spring AOP 规则冲突检测
> - 规则版本对比（Spring Data 持久化）
> - MicroMeter 规则质量指标
> - Python FastAPI 辅助分析
> - 前端 ShadowRuleWarning.tsx 和 RuleVersionDiff.tsx
> - 7 人天工作量已归还
>
> **工作量**：~~7 人天~~ → **0 人天**（已删除）  
> **完整度影响**：权限系统目标微调（删除不影响实际使用体验）

---

## 九、实施路线图

### Week 1-2: 最高优先级（P0）— 14.5-16 人天

| 周次 | 任务 | 模块 | 人天 | 交付物 |
|------|------|------|------|--------|
| W1 | 流式工具启动（微调） | 工具 | 3 | StreamCollector 回调时序优化 + Virtual Thread 监控 |
| W1 | contextModifier 传播 | 工具 | 4.5 | StreamingToolExecutor 分区传播 |
| W1-2 | 主动健康检查增强 | MCP | 2-3 | 核心已完成，增强幂等重连保护 + WebSocket 广播 + 自定义线程池 + consecutiveFailures/lastSuccessfulPing |
| W2 | Token 预算 nudge UI | Agent Loop | 3-3.5 | WebSocket 推送全链路（ServerMessage + QueryMessageHandler + WsMessageHandler + QueryEngine 四文件）+ 前端 TokenBudgetIndicator |
| W2 | Context Collapse 增强 | 上下文 | 1.5 | 三级渐进折叠 + ContextCascade 激活 |
| W2 | TOCTOU 竞态修复 | 权限 | 0.5 | PolicySettingsSource AtomicLong 替代 volatile long |

**W1-2 里程碑**：工具系统优化 + MCP 健康检查增强 + 上下文管理增强 + Token 预算 UI + TOCTOU bug 修复

### Week 3-6: 高优先级（P1）— 40-42.5 人天

| 周次 | 任务 | 模块 | 人天 | 交付物 |
|------|------|------|------|--------|
| W3-5 | Swarm 模式完整实现 | 多Agent | 12 | SwarmState + SwarmWorkerRunner + 通信协议 + 权限冒泡 + Virtual Thread 监控 |
| W3-5 | Coordinator 四阶段 | 多Agent | 14-16 | CoordinatorWorkflow + enum阶段检测 + 提示词工程 + 假阳性处理 + 审计日志（P3可选） |
| W4 | 工具排序与 Cache | 工具 | 3.5 | Caffeine 本地缓存 + 缓存预热 |
| W5-6 | 其他工具增强 | 工具 | 8 | mapToolResult + BashTool 安全 + YAML 配置化 + MicroMeter 指标（P3） |
| W6 | 压缩后文件重注入 | 上下文 | 2.5-3 | KeyFileTracker + 工具埋点 + Caffeine 缓存 + rebuild 逻辑 |

**W3-6 里程碑**：Swarm + Coordinator 完整可用 + 工具安全增强 + 压缩后重注入

### Week 7-9: 中优先级（P2）— 16-18 人天

| 周次 | 任务 | 模块 | 人天 | 交付物 |
|------|------|------|------|--------|
| W7 | Agent 类型扩展 | 多Agent | 5 | sealed interface 类型化 + pattern matching 路由 + Actuator 端点（P3可选） |
| W7-8 | 资源发现完整化 | MCP | 8-9 | discoverResources + OkHttp + 5个 REST 端点 + Caffeine 缓存（前端 ResourcesPanel UI 为 P3 可选） |
| W8-9 | 提示词发现 UI | MCP | 3-4 | PromptsTab UI + 参数验证 + XSS 防护 |


> **已删除**：企业策略支持（MCP 6.4，~~8-10天~~）、企业策略覆盖（权限 8.2，~~0.5-1天~~）、远程熔断集成（8.1，~~8天~~）、规则遮蔽检测（8.3，~~7天~~）——共节省 23.5-26 人天

**W7-9 里程碑**：全功能实现 + MCP 资源/提示词完整 + 工具缓存优化

### Week 10: 收尾与集成测试 — 5 人天

| 任务 | 人天 | 描述 |
|------|------|------|
| 集成测试 | 2 | 跨模块联调（Swarm + Coordinator + 权限冒泡 + MCP 资源/提示词） |
| 性能测试 | 1 | Swarm 内存占用、工具并发延迟、Cache 命中率、Virtual Thread 负载 |
| 回归测试 | 1 | 确保现有测试全部通过（删除 6.4/8.2 后调整为 12/12 模块） |
| 文档更新 | 1 | 更新对标分析报告 + 测试报告 |

**W10 里程碑**：93.5%+ 验收达标

### 工作量汇总说明

| 类别 | 人天 | 说明 |
|------|------|------|
| 后端开发 | 60-68 | 工具(19) + 多Agent(31-33) + 上下文(4-4.5) + MCP(13-16) + AgentLoop(3-3.5) + 权限(0.5) |
| 前端开发 | 10.5 | 11 个新建 React 组件（与后端并行推进，删除 PolicyStatusBadge、PolicyLockIndicator、ResourcesPanel/ResourceDetailDrawer 降 P3） |
| 收尾测试 | 5 | 集成/性能/回归测试 + 文档 |
| **总计** | **75.5-83.5** | **后端 60-68 + 前端 10.5 + 收尾 5 = 75.5-83.5 人天，工期 9-11 周** |

> （⚠️ 第十五轮审查修正：删除 6.4（8-10天）和 8.2（0.5-1天）后，总工作量从 99-108 调整为 75.5-83.5 人天。方案 4.2 工作量上调至 14-16 人天，方案 5.2 工作量上调至 2.5-3 人天，方案 6.1 调整为 8-9 人天（前端降 P3），方案 6.2 调整为 3-4 人天（删除智能推荐/模板库）。建议预留 10-15% 缓冲）

### 前端工作量明细

本计划的前端开发工作量分布如下（PC 浏览器交互为必须项，移动端响应式为可选优化）：

| 模块 | 前端组件 | 人天 | 阶段 | 优先级 |
|------|---------|------|------|--------|
| Swarm 状态 | SwarmStatusPanel + WorkerProgressCard + PermissionBubbleDialog + SwarmMessageLog | 3.5 | W3-4 | P1 |
| Coordinator 可视化 | WorkflowPhaseIndicator + AgentTaskCard + DelegationWarningBanner | 2.5 | W3-4 | P1 |
| MCP 提示词 | PromptsTab + PromptArgsForm | 1.5 | W8-9 | P2 |
| MCP 状态 | McpConnectionIndicator | 0.5 | W7 | P2 |
| Token 预算 | TokenBudgetIndicator | 1.5 | W2 | P0 |
| 响应式 & 虚拟键盘 | 各组件移动端适配 + useVirtualKeyboard 集成 | 1.0 | W2-9 | P1 |
| **前端总计（本期）** | **11 个新建组件 + 响应式适配** | **10.5 人天** | W2-9 | |
| MCP 资源（P3 可选） | ResourcesPanel + ResourceDetailDrawer | 3 | 后续迭代 | P3 |

> **说明**：各方案的「工作量」为该方案的总体估算（部分方案包含前端工作量）；前端 10.5 人天为前端组件开发的独立估算，建议配备 1 名专职前端开发与后端并行推进。总计 = 后端 60-68 + 前端 10.5 + 收尾 5 = 75.5-83.5 人天。

> **注**：以下前端组件在各方案的「新架构增强项（维度14）」中提及，属于后续可选优化，不计入本期核心交付组件：CoordinatorWorkflowVisualizer.tsx、AgentTypePicker.tsx、CompressionHistory.tsx、~~PromptTemplateLibrary.tsx（已删除）~~（预留 2-3 人天，可在后续迭代中补充）。

> **第十五轮审查删除的前端组件**：PermissionDowngradeAlert.tsx（8.1 删除）、ShadowRuleWarning.tsx（8.3 删除）、PolicyAdminPanel.tsx（6.4 删除）、EnterprisePolicyDashboard.tsx（8.2 删除）、CircuitBreakerStatus.tsx（8.1 删除）、RuleVersionDiff.tsx（8.3 删除）、PolicyStatusBadge.tsx（6.4 删除）、PolicyLockIndicator.tsx（8.2 删除）。

---

## 十、验收标准

### 10.1 各模块验收条件

| 模块 | 验收条件 | 验证方法 |
|------|---------|--------|
| 工具系统 (95%) | ① 3个 FileRead 并行执行延迟 < 单个的 1.5x ② tool_use block 收到后 < 50ms 启动执行 ③ 内建/MCP 工具分区排序 + cache breakpoint 存在 ④ contextModifier 分区传播正确 | 单元测试 + 性能测试 |
| 多Agent (95%) | ① Swarm 创建 3 Worker 并行执行任务 ② Coordinator 四阶段流转完整 ③ Worker 权限冒泡到 Leader UI ④ Worker idle 后可复用 ⑤ 5 种 Agent 配置（general-purpose/Explore/Plan/Verification/Guide）类型化路由 | 集成测试 + E2E |
| 上下文 (91%) | ① Context Collapse 三级渐进折叠消息数减少 >30% 但语义保留 ② 压缩后 Top-5 关键文件自动重注入 | 长对话测试 |
| MCP (92%) | ① resources/list 返回完整资源列表（URI 安全过滤通过率 100%） ② prompts/list 结果在前端可浏览、参数验证通过 ③ MCP 连接健康检查：断线 30s 内自动重连、状态变更 WebSocket 广播延迟 < 1s ④ consecutiveFailures / lastSuccessfulPing 指标准确记录 | API 测试 + UI 测试 |
| Agent Loop (93%) | ① nudge 消息在前端显示 token 进度（WebSocket 推送延迟 < 500ms） ② 进度条百分比与实际 output tokens 误差 ±1% ③ 子代理场景（nestingDepth > 0）不触发 nudge ④ 递减回报检测（连续 3 次 delta < 500 tokens）正确触发停止 | 触发测试 + WebSocket 拦截验证 |
| 权限系统 (95%+) | ① PathSecurityService 路径安全检查通过率 100%（覆盖 symlink、`../` 遍历、`/etc/shadow` 等敏感路径） ② sudo/su/chmod 777 等危险命令 100% 拦截 ③ PolicySettingsSource TOCTOU 竞态修复验证（AtomicLong 替代 volatile long） | 单元测试 + 安全测试套件 |

### 10.1.1 PC 浏览器 UI 交互验收条件

| 模块 | UI 验收条件 | 验证方法 |
|------|-----------|----------|
| 工具系统 (95%) | 前端工具列表可显示分区排序结果（内建/MCP 分组） | Playwright E2E 测试 |
| 多Agent (95%) | ① Swarm 状态面板实时显示 Worker 进度 ② 权限冒泡对话框可弹出并等待用户决策 ③ Coordinator 阶段指示器正确流转 | Playwright E2E + 手动验证 |
| 上下文 (91%) | 压缩后关键文件重注入对用户透明（无 UI 干预） | 后端单元测试即可 |
| MCP (92%) | ① Prompts 标签页可选择并填写参数（DOMPurify XSS 防护生效） ② 连接状态指示器（McpConnectionIndicator）实时更新（绿/黄/红三色） ③ 资源列表标签页（P3 可选）可浏览、搜索 ④ 响应式适配：md 断点折叠为抽屉、触控区域 ≥ 44x44px | Playwright E2E + 响应式截图对比 |
| Agent Loop (93%) | ① Token 预算进度条在前端实时显示并响应式适配 ② 颜色阈值正确（<50% 绿色，50-75% 黄色，>75% 红色） ③ 移动端触控区域 ≥ 44x44px | Playwright E2E + 响应式截图对比 |
| 权限系统 (95%+) | ① 权限规则编辑页面正常加载并可编辑 ② 危险命令拦截时前端显示明确的拒绝提示 | Playwright E2E + 手动验证 |

### 10.2 整体验收标准

| 指标 | 目标值 | 验证方法 |
|------|--------|---------|
| 功能对标完整度 | ≥ 94% | 重新执行对标分析 |
| 现有测试回归 | 12/12 模块 PASS | 运行全量测试 |
| BashTool 安全 | 113/113 PASS | 运行安全测试套件 |
| P0/P1 问题 | 0 个 | 全量问题扫描 |
| 性能回归 | 无 | 核心工具执行 < 5ms |
| 内存稳定性 | Swarm 5 Worker < 50MB 增量 | 压力测试 |
| WebSocket 推送延迟 | 所有实时推送（nudge/health/swarm）端到端 < 500ms | WebSocket 拦截测试 |
| 多端响应式 | PC（≥1024px）+ 平板（≥768px）+ 手机（≥375px）布局正确 | 响应式截图对比（Chrome DevTools） |
| 触控适配 | 所有可交互元素触控区域 ≥ 44x44px | Lighthouse 辅助功能审计 |

### 10.3 最终完整度预期

```
Agent Loop:     █████████▊ 93%  (+3%)
工具系统:       █████████▌ 95%  (+23%)
权限系统:       █████████▌ 95%+ (+2%, 删除远程熔断+规则遮蔽+企业策略覆盖)
MCP集成:        █████████▏ 92%  (+14%)
上下文管理:     █████████▏ 91%  (+9%)
多Agent协作:    █████████▌ 95%  (+20%)
──────────────────────────────────
整体:           █████████▌ 93.5% (+11.8%)
```

---

> **文档生成时间**: 2026-04-18  
> **审查修正时间**: 2026-04-18（v3.8 第十轮14维度深度交叉验证：6轮源码验证结果综合修正，content_block_stop事件修正、contextModifier载体精确化、互斥协调逻辑补充、工具埋点描述精确化、行号修正、工作量重新校准）  
> **输入依据**: 功能对标分析报告 + 测试报告 v3.1 + Claude Code 架构分析 + 实际源码审查  
> **覆盖范围**: 12 个实施项（删除 6.4、8.1、8.2、8.3，8.2 保留 TOCTOU 修复）、11 个前端新建组件、9-11 周实施周期、75.5-83.5 人天工作量  
> **审查变更摘要（v1→v2）**: 删除 4 个低ROI/不存在方案（3.4 shouldDefer、4.3 外部进程、5.3 L3-L4 级联、7.2 消息扣留），修正 11 个方案的源码状态描述和工作量估算  
> **审查变更摘要（v2→v3）**: 补充 discoverResources() 完整实现、为所有新增核心类统一添加「新建」标注（含 11 个后端 Java 类）、修正并发安全方案（AtomicLong 替代 synchronized）、补充 WebSocket 消息格式和前端集成细节、6 个方案工作量修正（总增 +7 人天）、补充验收定量指标  
> **审查变更摘要（v3→v3.1·工作量修正）**: 第三轮工作量修正——3.2(+1)、6.1(+2)、6.2(+1)、6.4(+1)、7.1(+1)、8.1(+1)、8.3(+1)，共 7 个方案净增 +8 人天；同步更新 1.4 总工作量表、2.1 ROI 排序表、路线图各 Week 表格及文末覆盖范围
> **审查变更摘要（v3→v3.1·维度13 浏览器交互适配）**: 第 13 维度浏览器交互适配性审查——为 10 个方案补充 PC 浏览器交互设计（Swarm/Coordinator/MCP/Token预算/权限系统）、新增 15 个前端 React 组件规格、新增 10.1.1 UI 交互验收条件、新增前端工作量汇总明细表
> **审查变更摘要（v3.1→v3.2）**: 第四轮审查新增「新架构增强项（维度14）」，17 个方案全部补充新技术栈增强设计（Java 21 + Spring Boot 3.3 + React 18 + Python 3.11），总工作量从 88.5-99 调整为 120-135 人天，工期从 7-8 周调整为 10-12 周，同步更新 1.4 总工作量表、2.1 ROI 排序表、实施路线图（W1-7 扩展为 W1-12）及文末覆盖范围
> **审查变更摘要（v3.3→v3.4）**: 第六轮全量源码级审查——修正 QueryEngine.java 行数（962→963）；修正 Virtual Thread 背压描述（yield → Semaphore）；修正 PromptCacheBreakDetector.java 从“新建”为“已存在（114行）”；修正 SwarmWorkerRunner 代码 bug（CompletableFuture.supplyAsync 第二参数应为 Executor 而非 ThreadFactory）；修正 ScopedValue Java 21 Preview 限制说明；修正 ContextCascade L97-98 字段描述（结果记录字段≠控制字段）；修正 reconnectAttempts 字段已存在于 McpServerConnection.java L31 的错误描述；修正 AsyncContextModifier 用例（远程配置拉取 → 本地文件状态检查）；修正 SpEL 示例中不存在的 user.role 引用；修正“唱一”错别字为“唯一”；为 5 个维度14增强项标注缺失的前置依赖（spring-boot-starter-cache、spring-boot-starter-aop 均未在 pom.xml 中配置）
> **审查变更摘要（v3.4→v3.5）**: 第七轮源码级审查——修正整体目标完整度 94.0%→93.5%（与 10.3 节个体模块目标算术平均一致）；修正 ToolRegistry.getEnabledToolsSorted() 描述从“分组排序”为“分组（组内未按名称排序）”；修正 StreamingToolExecutor.TrackedTool.updatedContext 描述从“已预留”为“已声明且赋值（L125）但未传播到后续工具”；修正 CoordinatorService.java 行数 181→180；修正“聆合”错别字为“结合”
> **审查变更摘要（v3.5→v3.6）**: 第八轮维度14新架构价值全面审查——新增“1.5 新架构独特价值总览”章节（多端访问、模型无关、浏览器富交互、Spring Boot 基础设施、与 Claude Code 核心差异对比）；修正 3.2 维度14 @Cacheable 与手工 Caffeine 矛盾（统一为手工 Caffeine 为当前方案，@Cacheable 为升级路径）；修正 3.3 维度14 AOP 从默认推荐改为升级路径；修正 3.4 维度14 SpEL 降为远期可选、Grafana 面板删除、MicroMeter 降为 P3；修正 4.1 维度14 PriorityBlockingQueue+死信处理简化为 LinkedBlockingQueue；修正 4.2 维度14 @Audit 提供两种实现路径（log.info 当前可用 + AOP 升级路径）；修正 6.1 维度14 删除“Spring Cache 抽象”描述，改为“Caffeine 直接缓存”；修正 CoordinatorService.java 行数 180→181
> **审查变更摘要（v3.7→v3.8）**: 第十轮14维度深度交叉验证——修正 content_block_stop 事件引用为 MessageDelta/onComplete 实际触发点；修正 ToolUseContext modifier 回调描述（载体为 ToolResult.java 而非 ToolUseContext）；补充 ContextCascade.executePreApiCascade() L225-254 互斥协调逻辑缺失的精确描述；精确化工具埋点描述（FileStateCache 为状态缓存而非频率追踪）；修正 application.yml 行号（L26-30→L29-30、L148-150→L148-151）；修正 @EnableScheduling 行号（L2→L17）；修正 ContextCollapseService 行数（169→170）；方案4.2工作量从9天调整为11-14天；方案6.3工作量从3天下调为1-1.5天（核心已完成）；方案7.1工作量从1.5天调整为2-2.5天（后端也缺 WebSocket 推送）；方案8.2工作量从1-2天精确为0.5-1天；同步更新总工作量表、ROI排序表、实施路线图
> **审查变更摘要（v3.8→v3.9）**: 第十一轮源码验证修正——方案7.1: 补充WebSocket推送全链路代码（ServerMessage #33 TokenBudgetNudge + QueryMessageHandler.onTokenBudgetNudge + WsMessageHandler实现 + QueryEngine L462-472 handler调用）、前端 TokenBudgetIndicator.tsx 组件规格、工作量从2-2.5调整为3-3.5人天；方案8.2: 修正isManagedRulesOnly()实现（从 policy.json 解析而非不存在的configService）、补充mergeExternalRules() L247入口锁定模式检查完整代码；总览: P0工作量12-13→13-14.5人天、总工作量92-96.5→93-98人天、MCP低估+3-5人天注释；路线图: W1-2 P0工作量同步更新、8.2优先级标注强化；验收标准: Agent Loop新增4个定量检查点（WebSocket延迟<500ms、精度±1%、子代理不触发、递减回报检测）+ UI验收新增颜色阈值+触控区域检查点
> **审查变更摘要（v3.9→v4.0）**: 第十二轮多Agent协作模块深度修正（4.1-4.3）——方案4.1: 修正SwarmService.java TODO行号为L47/L58及完整注释内容、修正TeamMailbox队列类型从LinkedBlockingQueue为ConcurrentLinkedQueue(L26)、修正ToolUseContext.forWorker()为with*()链式构建、修正QueryEngine.execute()签名为(QueryConfig,QueryLoopState,QueryMessageHandler)、补充SwarmWorkerRunner完整executeWorkerLoop()实现、补充FileChannel.lock()文件锁完整代码、补充LeaderPermissionBridge超时机制(orTimeout 60s)、补充SwarmService完整重写代码(替换TODO+UnsupportedOperationException)、新增3个ServerMessage record定义(#33-35: SwarmStateUpdate/WorkerProgress/PermissionBubble)、新增4个前端组件规格表、修正InProcessBackend并发模式描述(FixedThreadPool+virtual factory)；方案4.2: 补充CoordinatorPromptBuilder L196-270四阶段指令已存在说明、补充detectCurrentPhase()完整实现(反向扫描+工具调用模式检测)、补充validateDelegation()完整实现(Prompt长度+具体信息+4种模糊正则)、修正allowedTools与SubAgentExecutor deniedTools协调关系、补充模型不遵从降级方案、新增3个前端组件规格表；方案4.3: 修正denied tools为实际的5个(Agent/ExitPlanMode/FileEdit/FileWrite/NotebookEdit)、补充全部5种Agent类型定义(+GuideAgent)、补充AgentStrategyFactory完整实现(关键词路由+pattern matching)、新增AgentTypePicker.tsx前端组件规格
> **审查变更摘要（v4.1→v4.2）**: 第十四轮14维度源码级全量审查——修正 application.yml 配置键名（llm.model-aliases→agent.model-aliases、llm.fallback-chain→app.model.tier-chain）；修正 StreamCollector 行号（L840→L809）；修正 Caffeine 行号（L164-167→L163-167）和 micrometer 行号（L141-143→L140-143）；补充 newSession(ToolUseContext) 签名改造说明；修正 4.1 Swarm 方案 6 处代码错误（SwarmConfig 新增 workerModel 字段、ToolRegistry.filterTools 不存在改为 getEnabledTools+stream过滤、QueryConfig.builder 不存在改为 record 构造器、QueryLoopState 构造器参数修正为(List,ToolUseContext)、TeamManager.createTeam 参数顺序修正）；修正 4.2 Coordinator detectCurrentPhase 中 ToolUseBlock.input() 类型从 Map 为 JsonNode；修正 5.2 文件重注入 context.turnId() 为 context.toolUseId()（ToolUseContext 无 turnId 字段）；修正 6.1 资源读取端点 URI 参数从 @PathVariable 为 @RequestParam（避免特殊字符解析失败）；补全 6.4 loadEnterprisePolicy() 完整实现（原方法在 McpConfigurationResolver 中不存在）；修正路线图 W5-6 “SpEL 配置化”为“YAML 配置化”（与 3.4 方案 SpEL 已降为远期可选保持一致）
