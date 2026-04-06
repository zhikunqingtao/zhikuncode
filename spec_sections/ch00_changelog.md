# AI Code Assistant - 技术规范与实施文档 (SPEC)

> 版本: 1.66.0  
> 日期: 2026-04-05  
> 状态: 第六十六轮修订版（§3.2.3c Bash 解析器黄金测试用例补充 — 50 条精选）  
> 范围: P0 (核心基础) + P1 (重要增强)  
> 设计原则: **模型无关 (Model-Agnostic)** — 不绑定任何特定 LLM 供应商

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 系统架构设计](#2-系统架构设计)
- [3. P0 核心模块详细设计](#3-p0-核心模块详细设计)
- [4. P1 增强模块详细设计](#4-p1-增强模块详细设计)
- [5. 数据模型](#5-数据模型)
- [6. API 接口设计](#6-api-接口设计)
- [7. 数据库设计](#7-数据库设计)
- [8. 前端页面设计](#8-前端页面设计)
- [9. 安全设计](#9-安全设计)
- [10. 部署方案](#10-部署方案)
- [11. 开发路线图](#11-开发路线图)
- [12. 附录](#12-附录)

**v1.67.0 变更日志** (2026-04-05):
> **§12.3 Feature-Flagged 工具参考表补充**
>
> 在 P2 不实现功能清单中新增原版 Feature-Flagged 工具参考表 (15 个工具)，
> 按 Feature Flag 名称索引，标注类型 (Anthropic 内部/测试调试/实验性)、
> 普通用户可用性、P2 可实现性及 Java 等价方案。
> 为 P2 阶段选择性实现提供结构化决策依据。

**v1.66.0 变更日志** (2026-04-05):
> **§3.2.3c Bash 解析器黄金测试用例补充**
>
> 在"步骤 6: 验证"段落之后新增 50 条精选黄金测试用例参考，覆盖解析器 12 个核心语法类别:
> 简单命令(5) / 管道与序列(4) / 重定向(5) / 变量展开(5) / 命令替换(4) /
> 引号与转义(5) / 控制流(6) / 复合结构(4) / 函数定义(2) / Glob与大括号展开(3) /
> 声明命令(3) / 安全边界(4)。
>
> 每条用例标注: 输入命令 / AST 根子节点类型 (对齐 bashParser.ts mk() 调用) /
> parseForSecurity() 预期结果 (simple/too-complex/PARSE_ABORTED) / 简短说明。
> Java 实现者可直接用作单元测试输入，与 TypeScript 版输出逐字段 AST 比对。

**v1.65.0 变更日志** (2026-04-05):
> **Web Browser UI 前端审查 — 对照原版 Ink 终端 UI 完整源码 (src/components/ + src/keybindings/ + src/state/)**
>
> **HIGH (功能缺失/错误) — 6 项**:
> - H-01: §4.8 新增 SCROLL 和 MESSAGE_ACTIONS 两个键盘上下文 (原版 20 种，非 18 种)
> - H-02: §4.8 浏览器冲突键映射表补充 Ctrl+R (页面刷新→替代键) 和 Ctrl+O (打开文件→替代键)
> - H-03: §4.8 浏览器冲突表修正 Ctrl+G: 原版绑定 chat:externalEditor，非"(无)"
> - H-04: §8.3 ContentBlock 新增 `redacted_thinking` 类型 (原版 Message.tsx 明确处理)
> - H-05: §8.2 新增 GroupedToolUseBlock 和 CollapsedReadSearchBlock 组件设计
> - H-06: §4.8 动作总数从 86 更正为 97 (新增 scroll 7 + messageActions 4 种)
>
> **MEDIUM (不准确/不完整) — 7 项**:
> - M-01: §8.3 SessionStore 默认模型从 'gpt-4o' 改为 null (可配置占位符)
> - M-02: §8.3 AppUiStore.promptSuggestion 从 string[] 扩展为完整对象 (含 promptId/shownAt/acceptedAt)
> - M-03: §8.3 前端消息类型新增 `attachment` 顶层类型 (独立附件消息)
> - M-04: §8.2 新增 system 消息子类型处理 (compact_boundary/local_command 等)
> - M-05: §4.8 标注 footer:close 和 confirm:previousField 为保留动作(无默认绑定)
> - M-06: §8.2 PromptInput 功能清单补充 Vim 输入模式和外部编辑器功能
> - M-07: §8.2 ToolCallBlock 渲染器区分 FileWriteTool(全文创建) 和 FileEditTool(patch 编辑)

**v1.64.0 变更日志** (2026-04-05):
> **CLI 接口层源码审查 — 对照 `src/main.tsx` + `src/cli/print.ts` + `src/cli/structuredIO.ts`**
>
> **HIGH 修复 (3 项)**:
> - **F1 §4.21.3 参数补全**: 新增 `--effort` (low/medium/high/max), `--json-schema`, `--fallback-model`,
>   `--fork-session`, `--name`, `--tools`, `--include-partial-messages`, `--system-prompt-file`,
>   `--append-system-prompt-file`, `--input-format` — 共 9 个缺失参数 (对照源码 Commander.js ~50 个参数定义)
> - **F2 §6.1.6a QueryRequest DTO**: 新增 `effort`, `fallbackModel`, `jsonSchema`, `tools`,
>   `forkSession`, `name`, `includePartialMessages` — 7 个字段 (14→22 字段)；更新内部重载方法参数位置
> - **F3 §4.21.4 stream-json**: 补充 `thinking` 事件类型 + `error` 独立事件；
>   `stopReason` 完整取值: end_turn/max_turns/budget_exceeded/max_tokens/stop_sequence
>
> **MEDIUM 修复 (3 项)**:
> - **F4 §4.21.2 退出码**: 移除错误的 exit 5 (超时); maxTurns/budget 达到限制是正常退出 (exit 0);
>   源码 cliOk()/cliError() 仅定义 0 和 1
> - **F5 §6.1.6a ConversationRequest**: 补充 `workingDirectory`, `timeoutSeconds` 字段
> - **F6 §4.21.8 Python CLI**: 修复 `import os` 缺失; 补充 `--system-prompt-file` (Path 类型);
>   新增 EffortLevel 枚举; 请求体同步 9 个新参数; 权限值 `BYPASS_PERMISSIONS` → `BYPASS` (对齐 §3.4.1)
>
> **修改范围**: §4.21.2 (退出码) + §4.21.3 (参数清单) + §4.21.4 (stream-json 事件) +
> §4.21.8 (Python CLI 代码) + §6.1.6a (QueryRequest/ConversationRequest DTO)
> **影响评估**: 纯文档精确性提升 + DTO 字段扩展，无 API 路径变更

**v1.63.0 变更日志** (2026-04-05):
> **P1 CLI 接口层 — Python CLI + QueryController REST/SSE 端点**
>
> **新增 §4.21 CLI 接口层 (P1, ~663 行)**:
> - **§4.21.1 架构概述**: 两层设计 — Python CLI (`aica`) → REST/SSE → Spring Boot QueryController
> - **§4.21.2 CLI 命令设计**: `aica` 命令, 9 种使用场景, 7 个退出码
> - **§4.21.3 CLI 参数清单**: ~25 个参数（对齐源码 Commander.js 定义）
> - **§4.21.4 输入输出协议**: stdin 处理策略, 3 种输出格式 (text/json/stream-json), stderr 元信息分离
> - **§4.21.5 会话管理**: -c/--continue, -r/--resume, --session-id, 本地缓存 cli-sessions.json
> - **§4.21.6 权限处理**: 非交互默认 DONT_ASK (只读允许/写入拒绝), --no-permissions bypass
> - **§4.21.7 第三方工具兼容**: 管道链 (| jq/fzf), CI/CD, MCP, shell 脚本集成
> - **§4.21.8 Python CLI 实现**: Typer + httpx + Rich 完整代码 (~200 行), 项目结构, 依赖声明
> - **§4.21.9 安装与分发**: pip install, Docker, alias, shell 补全, 配置文件
>
> **新增 §6.1.6a QueryController (~386 行)**:
> - **POST /api/query**: 同步查询, 阻塞等待, 返回 QueryResponse
> - **POST /api/query/stream**: SSE 流式查询, SseEmitter + Virtual Thread
> - **POST /api/query/conversation**: 多轮会话查询, 加载历史上下文
> - DTO: QueryRequest (14字段), QueryContext, ConversationRequest, QueryResponse, ToolCallSummary, Usage
>
> **更新 §6.1.8 速查表**: 20→23 端点 (#21-#23 QueryController)
> **更新 Controller 汇总表**: 10→11 Controller, 36→39 端点
> **技术选型**: Python CLI (Typer+httpx+Rich) — 零新增语言, ~200ms 启动, 管道原生支持

**v1.62.0 变更日志** (2026-04-05):
> **系统提示完整文本源码对齐 — §3.1.3 + §3.1.5c 精确更新**
>
> **源码对齐（对照 `src/constants/prompts.ts` 915 行 + `src/constants/cyberRiskInstruction.ts`）**:
> - **§3.1.3 buildSystemPrompt()**: 替换 v1.44.0 不准确的 7 段占位符注释为 §3.1.5c 常量引用
> - **SYSTEM_TEMPLATE**: 补全 hooks 阻塞处理逻辑 ("If you get blocked by a hook...")
> - **DOING_TASKS_TEMPLATE**: 补全 ~10 处截断文本（上下文指令/安全编码/边界验证/抽象准则等）
> - **ACTIONS_TEMPLATE**: 补全完整风险分级原文（+18 行：授权范围/CI-CD/三方上传/根因分析等）
> - **OUTPUT_EFFICIENCY_TEMPLATE**: 补全 "When explaining..." 说明句
> - **ENV_INFO_TEMPLATE**: `<env-info>` XML 标签 → `# Environment` + bullet 格式（对齐源码）
> - **LANGUAGE_TEMPLATE**: 添加 `# Language` 标题 + 完整指令（技术术语保留原文）
> - **SCRATCHPAD_TEMPLATE**: 4 行摘要 → 完整 17 行源码文本（对齐 getScratchpadInstructions）
> - **新增 SUMMARIZE_TOOL_RESULTS**: 独立常量（工具结果摘要提醒）
> - **新增 DEFAULT_AGENT_PROMPT**: 子代理默认系统提示完整文本
> - **记忆段**: 移除不准确的 `<memory>` XML 模板，标注源码实际拼接方式

**v1.61.0 变更日志** (2026-04-05):
> **Bash 技术审查 11 项修复 — 文档精确性提升 + AI 编程助手友好性增强**
>
> **修正 (3 项)**:
> - **F5** [MEDIUM]: ShellStateManager 注释 `env -0` → `export -p` 统一（与 wrapCommand() 代码对齐）
> - **F8** [MEDIUM]: BashTool.call() 超时终止改为 SIGTERM→等2s→SIGKILL 梯度策略（对照源码 ShellCommand.ts）
> - **F3.5** [LOW]: BashTool.call() 新增 CWD 越界重置逻辑（防止 cd 到项目外，对照源码 resetCwdIfOutsideProject）
>
> **补充 (5 项)**:
> - **F3/F9** [MEDIUM]: BashTool 新增后台任务管理与进度推送 Java 适配方案（5 项机制完整说明 + 推荐技术路径）
> - **F4** [LOW]: BashTool 输入 Schema 新增 `run_in_background` 字段定义
> - **F10** [LOW]: BashTool.call() 新增 stderr 合并处理说明（redirectErrorStream=true 语义）
> - **F11** [LOW]: BashCommandClassifier.extractFirstToken() 标注为 SPEC 有意增强（源码无 sudo/env 剥离）
>
> **优化 (3 项)**:
> - **F1** [INFO]: BashTool.isReadOnly() 添加"Java 版比源码更安全"标注（AST 分析替代首 token 查表）
> - **F6** [LOW]: ShellStateManager.wrapCommand() 新增 `trap EXIT` 防御性临时文件清理
> - **F7** [LOW]: BashCommandClassifier.extractFirstToken() 添加 SPEC 增强标注注释
>
> **修改范围**: §3.2.3 BashTool (6 处) + ShellStateManager (2 处) + BashCommandClassifier (2 处)
> **影响评估**: 无 API 变更，纯文档精确性提升和 AI 实现友好性增强

**v1.60.0 变更日志** (2026-04-05):
> **P0/P1 全面审查 G1-G10 问题修复 — 常量统一/签名修正/域范围对齐/交叉引用/语义说明**
>
> **G1-G7 修复（编译阻断 + 常量冲突 + 设计问题，前序修复）**:
> - **G1** [HIGH]: FileReadTool MAX_SIZE_BYTES 统一为 200MB（对齐汇总表 L16870）
> - **G2** [MEDIUM]: FileReadTool DEFAULT_MAX_OUTPUT_TOKENS 统一为 60,000（对齐汇总表 L16871）
> - **G3** [HIGH]: WebFetchTool MAX_CONTENT_CHARS=100,000 / MAX_RESPONSE_BYTES=10MB（对齐汇总表）
> - **G4** [MEDIUM]: 5 处 getIntOptional → getOptionalInt（与 ToolInput 接口定义一致）
> - **G5** [HIGH]: 2 处 tool.call() 5参数残留 → 2参数标准签名（§13547 流程图 + §29638 MCP 代码）
> - **G6** [MEDIUM]: EnterPlanModeTool/ExitPlanModeTool 改为通过 AppState 操作权限模式，ToolUseContext record 保持不可变
> - **G7** [LOW]: 管线流程图删除指向 TypeScript 源码的过期行号标注
>
> **G8 修复（架构矛盾）**:
> - [HIGH] §4.14 header 新增 scope 对齐说明 — P0/P1 仅 CODE_INTEL + FILE_PROCESSING 两域
> - [HIGH] §4.14.2-§4.14.6 五节标题添加 "(⚠️ P2)" 标记 + P2 预留说明
> - [HIGH] §4.14.7a 路由层新增 P0/P1 vs P2 范围说明
> - [HIGH] Tool 对照表新增 "P级" 列，2 个 P1 工具 + 5 个 P2 工具明确标注
>
> **G9 修复（描述分散）**:
> - [MEDIUM] §4.9.2 新增交叉引用 — 分类过程 (Quick+Thinking) 与模型选择 (resolveClassifierModel 四级回退) 的互补关系
>
> **G10 修复（语义说明）**:
> - [LOW] callHeavyWithSse() 注释新增 RFC 7231 §6.5.6 语义说明 — Accept:text/event-stream + 406 属标准内容协商

**v1.59.0 变更日志** (2026-04-05):
> **P1 审计 9 项修复 — API 命名/缺失方法/TaskCoordinator/17 工具 call() 方法体/前端面板/集中索引/Spring 集成**
>
> 基于 v1.58.0 SPEC 的 P1 功能全面审查，发现并修复 9 项问题 (F1-F9):
>
> **F1 — ToolInput API 命名修正 (P0 阻塞)**:
> - NotebookEditTool: `getStringOrDefault` / `getIntOrDefault` → `getString(key, default)` / `getInt(key, default)` (3 处)
> - REPLTool: `getStringOrDefault` → `getString(key, default)` (1 处)
>
> **F2 — ToolInput 补充 getOptionalList 方法 (P0 阻塞)**:
> - 在 §3.2.1a-1 ToolInput 类定义中新增 `getOptionalList(String key, Class<T> elementType)` 泛型方法
> - WebSearchTool B2 修复中已使用此方法，此前 ToolInput 定义缺失
>
> **F3 — 新增 TaskCoordinator 类定义 (P1 高优)**:
> - 新增 §4.1.3a `TaskCoordinator` 服务类 (~150 行)，含 submit/cancelTask/getTask/listTasks
> - 三层中断传播: Thread.interrupt → Cancellable → 递归子任务
> - 超时看门狗 + `@PreDestroy` 资源清理
> - 配套 TaskState/TaskStatus/Cancellable 类型定义
>
> **F4 — 17 个 P1 工具补充完整 call() 方法体 (P1 核心)**:
> - 简单工具 (5): SleepTool / TaskOutputTool / TaskUpdateTool / TaskListTool / TaskGetTool
> - 中等工具 (6): TodoWriteTool / ConfigTool / SendMessageTool / BriefTool / ListMcpResourcesTool / ReadMcpResourceTool
> - 复杂工具 (6): AskUserQuestionTool / SyntheticOutputTool / TaskCreateTool / SkillTool / PowerShellTool / TaskStopTool
> - 所有工具均使用 ToolInput + ToolUseContext 统一签名，与 P0 工具保持一致
>
> **F5 — BagelTool 不存在标注 (P2 文档)**:
> - 明确 `bagelActive` / `bagelPanelVisible` 是 Tungsten 内部子功能状态，非独立工具
>
> **F6 — TungstenTool Web UI 不兼容标注 (P2 文档)**:
> - 标注 TungstenTool 依赖 tmux 面板概念，与 Web UI 不兼容，保持 P2
> - 建议替代方案: 前端内嵌 xterm.js (复用 REPLTool 架构)
>
> **F7 — Swarm 面板前端设计补充 (P2 前端)**:
> - TaskPanel 从 5 行骨架扩展为 ~110 行完整实现
> - 新增 TaskItem 子组件: 状态指示灯 + 代理颜色 + 运行时长 + Token 消耗 + 取消按钮
> - 面板头部: 运行/排队计数，展开详情: 输出预览 + 错误信息
>
> **F8 — FastMode 集中化交叉引用索引 (P2 文档)**:
> - 在 FastMode 最早出现处新增索引表，汇总 7 处分散设计点
>
> **F9 — 性能追踪 Spring Actuator 集成路径 (P2 架构)**:
> - 新增 PerfettoEndpoint (`@Endpoint(id = "perfetto")`) 暴露 /actuator/perfetto
> - 新增 MicrometerTracingBridge: QueryProfiler 指标 → Prometheus (Timer/Counter/DistributionSummary)
> - 补充 application.yml 配置示例

**v1.58.0 变更日志** (2026-04-05):
> **P0 审计 12 项修复 — 接口签名/缺失实现/重复定义/技术违规/安全隐患**
>
> 基于 v1.57.0 SPEC 的 P0 功能全面审查，发现并修复 12 项问题 (5 类):
>
> **A 类 — 接口/类型签名不一致 (5 项)**:
> - **A1 Tool.call() 5参数→2参数**: 统一为 `call(ToolInput, ToolUseContext)`，
>   额外参数 (canUseTool/parentMessage/onProgress) 移入 ToolUseContext
> - **A2 ToolContext→ToolUseContext**: 全文 15 处统一类型名
> - **A3 isDestructive() 无参→有参**: 改为 `isDestructive(ToolInput input)` 与 isReadOnly 一致
> - **A4 PermissionPipeline Map→ToolInput**: checkPermission 参数类型修正
> - **A5 ToolInput API 统一**: 新增 §3.2.1a-1 完整定义，规范三种 API 模式
>   (getString/getString+default/getOptionalString)，修正 GlobTool+GrepTool 调用
>
> **B 类 — 缺失实现体 (2 项)**:
> - **B1 WebFetchTool call()**: 补充完整实现 (OkHttp 请求 + Jsoup DOM→Markdown 转换器)
> - **B2 WebSearchTool call()**: 补充完整实现 (输入验证→搜索后端调用→结果格式化)
>
> **C 类 — 重复/冲突定义 (2 项)**:
> - **C1 BashTool.call() 重复**: 删除与主定义不一致的简化副本
> - **C2 SearchResult 3字段→4字段**: 统一为 (title, url, snippet, content)
>
> **D 类 — 技术可行性 (2 项)**:
> - **D1 WebClient→OkHttp**: 修正 Spring WebClient 引用为 OkHttp/HttpClient
> - **D2 Jsoup Safelist.none()**: 改用 Jsoup.parse() + DOM 遍历自定义转换
>
> **E 类 — 安全隐患 (1 项)**:
> - **E1 eval→heredoc**: ShellStateManager wrapCommand() 改用 heredoc+source 模式，
>   消除 eval 二次展开风险，含动态终止符防御边缘情况

**v1.57.0 变更日志** (2026-04-05):
> **BashTool 5 项安全设计断层修复 — 连接 AST 安全分析与权限系统**
>
> 基于 SPEC 与源码 (bashPermissions.ts L1663-1870 bashToolHasPermission()) 的对比分析，
> 发现 BashTool 类定义与 §3.2.3c AST 安全分析子系统完全脱节。精心设计的安全分析链
> (parseForSecurity → walkProgram → checkSemantics) 没有任何调用入口，形同虚设。
>
> **P0 安全关键修复 (3 项)**:
> - **G1 BashTool.checkPermissions()**: 新增此方法作为 §3.4.3a 权限管线 Step 1c 的入口，
>   调用 BashSecurityAnalyzer.parseForSecurity() → 处理三态结果 (simple/too-complex/parse-unavailable)
>   → 逐子命令匹配权限规则 → 返回 PermissionResult。**修复前此方法不存在，整条 AST 安全分析链无人调用。**
> - **G2 isReadOnly() 首 token 漏洞**: 原实现 `command.split("\\s+")[0]` 导致 `cat file; rm -rf /`
>   被误判为只读。修复后使用 AST 解析提取所有 SimpleCommandNode 逐一检查。
> - **G3 isDestructive() 首 token 漏洞**: 原实现无法检测 `sudo rm -rf /`（首 token 为 sudo）。
>   修复后使用 AST 解析 + checkSemantics 包装命令剥离后检查真实 argv[0]。
>
> **P1 架构完善 (2 项)**:
> - **G4 call() 集成沙箱+状态管理**: 集成 SandboxManager (§9.3 Docker 容器隔离) 和
>   ShellStateManager (§3.2.3c.4 Shell 环境快照)。修复前直接 `ProcessBuilder("bash", "-c", command)`
>   无任何沙箱保护和环境恢复。
> - **G5 三套分类系统统一**: 明确三级降级链 — P0 核心: BashSecurityAnalyzer (AST)
>   → P0 降级: BashCommandClassifier (正则) → P1 可选: Python bashlex。
>   修正 BashCommandClassifier 角色从"P0 唯一实现"到"P0 降级 fallback"，
>   修正 ShellAstAnalyzer 调用链为"P1 第二降级"。
>
> **新增桥接类 (1 项)**:
> - **BashSecurityAnalyzer**: 连接 BashTool.checkPermissions() 与 §3.2.3c AST 安全分析子系统，
>   含 parseForSecurity() 和 checkSemantics() 的完整 Java 实现骨架。

**v1.56.0 变更日志** (2026-04-05):
> **BashTool 解析器 9 项设计缺陷修复 + 2 项核心算法补充**
>
> 基于 SPEC 与源码 (bashParser.ts/ast.ts/bashPermissions.ts) 的深度对比分析，
> 修复所有可能误导 AI 代码生成工具的设计缺陷。
>
> **P0 安全关键修复 (5 项)**:
> - **E1 §3.2.3c ShellNode**: 删除与 BashAstNode 矛盾的 ShellNode sealed interface 定义，统一为唯一权威 AST 层级
> - **E2 §3.2.3c.2 DANGEROUS_TYPES**: 修正错误列表 — for/if/while/subshell/test_command 是已处理类型而非危险类型
> - **E5 §3.2.3c CommandAnalysis**: 删除源码中不存在的 CommandAnalysis record，替换为 ParseForSecurityResult
> - **E6 §3.2.3c 技术路线**: 统一裁决 P0=Java 手写递归下降解析器（唯一主路径），Python bashlex=P1 降级
> - **E8 §3.2.3c.2 EVAL_LIKE_BUILTINS**: 补全遗漏的 let/coproc/mapfile/readarray/bind/alias 等 10 项危险内置命令
>
> **P1 澄清与补全 (4 项)**:
> - **E3 §3.2.3c.1 tree-sitter**: 澄清 BashTool 解析器为纯手写实现，不依赖 tree-sitter 库
> - **E4 §2.4.2 Python 端点**: 重新定位 `/api/bash/analyze` 为 P1 降级路径（非 P0 核心）
> - **E7 §3.2.3c.2 wrapper 列表**: 补全 checkSemantics 包装命令剥离列表 (builtin/bare nice/xargs)
> - **E9 §3.2.3c.2 SPECIAL_VARS**: 区分 Lexer 级 (含@*) 与安全分析级 (不含@*) 两套特殊变量集
>
> **核心算法补充 (2 项)**:
> - **SUP1 §3.2.3c.2 walkArgument()**: 新增完整伪代码 — 10 种节点类型分派、变量解析安全规则
> - **SUP2 §3.2.3c.2 walkVariableAssignment()**: 新增伪代码 — PS4/IFS 守卫、+= 追加语义、scope 更新

**v1.55.0 变更日志** (2026-04-05):
> **24 项交叉验证问题修复 — 覆盖 33 个功能 ID (F2/F3/F4/F5/M 系列)**
>
> **批次 1 — P0 重复定义消除 (6 项)**:
> - **F5-01 §3.1.2 CompactResult**: 旧版 5 字段定义标注废弃，权威 → 7 字段版本
> - **F5-02 §3.1.3 SystemPromptSection**: 删除重复 sealed interface 定义
> - **F5-03 §3.1.2 fallbackKeyMessageSelection**: 删除旧版手动 6 级实现
> - **F5-05 §10.5.4 RemoteAccessSecurityFilter**: 标注引用 §9.1.0a 权威定义
> - **F5-08 §8.5.3 WebSocketMessageHandler**: 标注 class 版本为架构参考
> - **F5-09 §3.4.2 RuleScope**: 枚举迁移至 §3.4.2 权威位置
>
> **批次 2 — P0 中等修复 (2 项)**:
> - **F3-02 §8.2.5 Dialog Launchers**: 重写为 Zustand dialogStore 驱动模式
> - **F3-05 §3.1.1 Provider+API Key**: 添加联合选择决策矩阵
>
> **批次 3 — P0 大型补充 (5 项)**:
> - **F2-01 §3.2.3c BashTool**: 添加完整 EBNF 语法 + sealed interface AST 层级
> - **F2-02 §3.4 YoloClassifier**: 添加 5 个 Few-Shot 示例 + FAIL-CLOSED 降级策略
> - **F2-03 §3.1.2 CompactService**: 添加摘要 token 上限 + 质量评估标准 + 三级降级
> - **F2-09 §8.3 Zustand Stores**: 添加 11 个 Store 的 create() 工厂 + 初始值 + persist 配置
> - **F4-06 §8.3 AppState 映射**: 添加后端→前端完整字段映射表 (30+ 字段)
>
> **批次 4 — P1 修复 (4 项)**:
> - **F5-07/F3-03 §4.1.1d.2 AgentStatus**: register() 补充 sessionId 参数 + withStatus 清理逻辑
> - **F5-04 §2.8.1 GrowthBook**: pom.xml 依赖注释掉对齐 v1.40.0 裁决
> - **F3-04 §4.1.3 STOMP 路径**: /topic/tasks/{taskId} → /topic/session/{sessionId} 统一
> - **F2-07 §4.3 MCP ClientManager**: 补充 SmartLifecycle + 重连 + 关闭 + 工具注册
>
> **批次 5 — 质量修复 (5 项)**:
> - **F2-05 §3.3.2 P0 命令**: 12 个核心命令完整入参/出参/执行流程
> - **F2-06 §3.1.1 ThinkingConfig**: Adaptive 动态预算算法 + thinking block 压缩策略
> - **F2-10 §2.7.1 CSS 主题**: 完整 26 个 CSS 变量 × light/dark 两套值 + prefers-color-scheme
> - **M-12 §11.1 E2E 测试**: Playwright 配置 + 5 个核心场景测试骨架
> - **M-14 §11.1 性能基准**: 18 项量化指标 (前端/流式/工具/WebSocket/内存/移动端)

**v1.54.0 变更日志** (2026-04-05):
> **7 项第三轮审查问题修复 — 4 项实现错误 + 1 项实现不合理 + 2 项信息补充**:
>
> **实现错误修正（4 项）**:
> - **E-01 §8.5.3 dispatch() message_complete 残留 costStore 代码**: 删除 L41655-41661 的 costStore.updateCost 旧代码，与 handleMessageComplete (v1.53.0 U-01) 保持一致，费用由 #15 cost_update 权威推送
> - **E-02 §8.5.1a MessageCompletePayload 残留 cost 字段**: 从接口定义中移除 `cost: number`，与路由表 #7 (`usage, stopReason`) 对齐
> - **E-03 §8.5.1a L43817 注释残留 costStore**: `#7: messageStore + sessionStore + costStore` → `#7: messageStore + sessionStore`
> - **E-04 §8.3 L42540-42557 注释残留幽灵类型**: messageStore 7→5 (删除 tool_input_delta/tool_use_backfill), sessionStore 4→3 (删除 session_status), message_complete 移除 costStore 引用
>
> **实现不合理修复（1 项）**:
> - **U-01 §3.1.5 CompactQualityReport 重复定义**: 删除旧版 4 参数 record 及旧版 validateCompactQuality 方法 (L8974-9007)，仅保留权威 7 参数版本 (L9168-9201)，消除编译期重复类名冲突
>
> **信息补充（2 项）**:
> - **I-01 §4.14.9 PythonCapabilityAwareClient SSE 端点**: 在 Code Quality Router 新增 `/report/stream`、Visualization Router 新增 `/dependency-graph/stream` + `/call-graph/stream` SSE 流式端点，对齐 Java 端 callHeavyWithSse 协议 (event: progress + event: result)；明确 406 降级超时 = HEAVY_READ_TIMEOUT = 120s；Tool 对照表同步更新
> - **I-02 §3.1.3.1 OpenAiSseEventProcessor.processChunk**: 填入完整实现代码（与 OpenAiCompatibleProvider.processChunk 一致），消除空方法体导致实现者无法编译的问题

**v1.53.0 变更日志** (2026-04-05):
> **9 项质量问题修复 — 3 项实现不合理 + 1 项代码错误 + 3 项信息补充 + 1 项技术适配 + 1 项性能优化**:
>
> **实现不合理修复（3 项）**:
> - **U-01 §8.5.3 handleMessageComplete 费用双重计算**: 移除 costStore 本地累加逻辑，totalCost 统一由 `cost_update` (#15) 后端权威推送，消除费用虚高风险；消息路由表 #7 字段从 `usage,cost,stopReason` 简化为 `usage,stopReason`；Store 依赖矩阵同步更新
> - **U-02 §8.3 ConfigStore.loadConfig() 无重试**: 新增 3 次指数退避重试 (300ms→600ms→1200ms) + localStorage 降级缓存完整伪代码，防止后端未就绪时首屏白屏
> - **U-03 §4.14.9 PythonCapabilityAwareClient 120s 阻塞**: 新增 `callHeavyWithSse()` 方法，重型操作 (full-report/architecture/semgrep/scan) 优先走 SSE 流式返回 + 进度回调，406 时降级同步；Tool 对照表 3 个重型 Tool 更新为 `callHeavyWithSse`
>
> **代码错误修正（1 项）**:
> - **E-02 §8.5.4 PermissionRequestManager 推送通道**: 参考实现中 `convertAndSend("/topic/session/")` 改为 `convertAndSendToUser(sessionId, "/queue/messages")`，权限请求和超时通知均使用用户专属队列，避免广播泄露
>
> **信息补充（3 项）**:
> - **N-01 §6.3a.6 ExtractMemories LLM prompt**: 补充完整 extraction prompt 模板（含 5 类提取目标 + 3 个 few-shot 示例 + 文件格式规范 + buildExtractCombinedPrompt 差异说明）
> - **N-02 §6.3a.8 AutoDream consolidation prompt**: 补充 buildConsolidationPrompt 完整 4 阶段 prompt（Orient/Gather/Consolidate/Prune）+ Java 端调用签名，对齐源码 `consolidationPrompt.ts`
> - **N-03 §8.2.6a SettingsPage 5 路由页表单字段**: 补充 General(8)/Model(6)/Permissions(5)/Theme(6)/MCP(8) 共 33 个字段的完整定义表（名称/类型/默认值/校验规则/说明）
>
> **技术适配（1 项）**:
> - **T-01 §4.8.1 键盘绑定浏览器冲突映射**: 新增 17 行完整映射表（冲突键→浏览器行为→原终端动作→Web替代键），含 4 种处理策略分类（preventDefault/条件拦截/替代映射/放弃绑定）+ 8 项验证清单

**v1.52.0 变更日志** (2026-04-05):
> **N-04 Python 依赖冲突矩阵补全**:
>
> - **§2.8.2 requirements.txt 重写**: 补齐 5 个遗漏依赖 (`bashlex`/`rope`/`jedi`/`mcp`/`watchfiles`)，每行标注导入位置，消除 `ModuleNotFoundError` 启动失败
> - **§2.8.2 P0/P1 冲突矩阵**: 新增 8 项经过验证的依赖冲突表（含严重程度、原因、解决方案、验证命令）
> - **§2.8.2 一键验证脚本**: 新增 `verify_python_deps.sh`，覆盖所有 P0/P1 依赖的导入+版本检查
> - **§2.8.2 系统级依赖说明**: 新增 `libmagic` 跨平台安装指引 (macOS/Ubuntu/Alpine/Windows)
> - **§1.4 依赖表补充**: 添加 `bashlex` 到 Python 依赖表
> - **§1.4 锁定策略对齐**: 与 §2.8.2 requirements.txt 统一版本号，补充基础框架 + FILE_PROCESSING + BASH_ANALYSIS 域
> - **§2.8.2 requirements-minimal.txt**: 补充 `tree-sitter-languages` 配对依赖

**v1.51.0 变更日志** (2026-04-05):
> **P0/P1 阻塞项修复 — 8 项（3 项 P0 阻塞 + 4 项 P1 阻塞 + 1 项 P0 附带）**:
>
> **P0 阻塞修复（3 项）**:
> - **E-01/E-04 §8.3 ServerMessage 类型统一**: 删除 3 个幽灵类型 (tool_input_delta/tool_use_backfill/session_status)，统一命名后缀 XxxMessage→XxxPayload，与 §8.5.1a 权威定义一一对应（25 种）
> - **E-02 §8.5.1b/§8.5.2 Client→Server Payload 冲突**: 统一 UserMessagePayload 为 §8.5.2 字段定义 (base64Data/path/Array references)，删除 §8.5.2 重复 TS 代码块改为交叉引用 §8.5.1b
> - **N-02 §4.1.5 BashTool Shell 状态持久化**: 新增 ShellStateManager 实现跨命令环境快照 + CWD 跟踪，对照源码 ShellProvider + bashProvider 的 snapshot sourcing 模式
>
> **P1 阻塞修复（4 项）**:
> - **N-01 §4.1.16 REPLTool pty4j 实际集成**: createInterpreter() 从 ProcessBuilder 替换为 PtyProcess.exec()，含 TERM/COLUMNS 环境变量 + ProcessBuilder 降级路径
> - **N-03 §4.3 MCP stdio 进程生命周期**: 补全 stderr 有界累积（64MB 上限）+ CompletableFuture.orTimeout 连接超时竞争 + SIGTERM→5s→SIGKILL 进程清理
> - **E-03 §8.5.1b McpOperationPayload 合并**: 合并 list_tools(§8.5.1b) + approve(§8.5.2) 为完整枚举 connect|disconnect|restart|list_tools|approve，config 补充 env 字段
> - **T-01 §3.4.3a.2 AutoModeClassifier Provider 适配**: 新增 resolveClassifierModel() 四级回退（env→yml→getLightweightModel→getMainLoopModel），补全 callClassifierApi() 完整实现含 XML 解析 + fail-closed
>
> **P0 附带修复（1 项）**:
> - **E-05 §8.3 TaskStore.clearTasks()**: 补充接口方法声明，消除 SessionSwitchController.clearStores() 引用缺失

**v1.50.0 变更日志** (2026-04-05):
> **可执行性审查阻塞项修复 — 12 项（5 项 P0 阻塞 + 4 项 P1 阻塞 + 3 项质量提升）**:
>
> **P0 阻塞修复（5 项）**:
> - **B1 §3.1.3.1 OpenAI SSE Parser (P0)**: 新增 `OpenAiSseEventProcessor` 独立类，完整处理 `choices[0].delta` + `tool_calls[index]` 增量重组，与 Anthropic `StreamEventProcessor` 并列为双 Provider SSE 解析权威实现
> - **B2 §3.2.3c BashParser (P0→P1)**: 将 P1 阶段 AST 解析器从"手写递归下降 Java 实现"改为"Python bashlex 库实现 + Java HTTP 调用"，新增 Python `/api/bash/parse` 端点
> - **B3 §3.6.1 JSONL→SQLite 醒目警告 (P0)**: 在 §3.6.1 开头添加红色大字警告框，明确"本节仅为源码参考，实际实现必须使用 §7 SQLite"
> - **B4 §8.2.6 AuthGuard 路由守卫 (P0)**: 新增 `AuthGuard` 组件 + `useAuth` Hook，实现三层认证模式路由守卫 + 未认证重定向 + 404 页面
> - **B5 §8.8.2 BottomSheet 组件库选型 (P0)**: 明确使用 `vaul` (Drawer) 作为移动端 BottomSheet 实现，补充 npm 依赖和 snapPoint 配置
>
> **P1 阻塞修复（4 项）**:
> - **B6 §4.3.8 MCP Java SDK Maven 坐标 (P1)**: 补充完整 Maven 依赖声明 `io.modelcontextprotocol.sdk:mcp-spring-webmvc`，确认版本与 Spring Boot 3.3.x 兼容
> - **B7 §4.1.16 REPLTool PTY 库 (P1)**: 明确 `pty4j` 为唯一 PTY 方案，补充 Maven 坐标 `org.jetbrains.pty4j:pty4j:0.12.13` + 平台兼容性矩阵
> - **B8 §9.1 OAuth P1/P2 边界拆分 (P1)**: 明确 OAuth 通用框架(OAuthCrypto/AuthCodeListener/OAuthService)为 P1，Anthropic 端点绑定(OAuthConfig 中的 claude.ai URL)为 P2
> - **B9 §9.1.0a 令牌生命周期管理 (P1)**: 补充令牌旋转策略（宽松模式：90 天有效期 + 启动时检测过期自动重新生成）
>
> **质量提升（3 项）**:
> - **Q2 §2.6.1 Java 不可变性策略 (P0)**: 明确 Java 端使用 `record`（天然不可变）替代 DeepImmutable 概念，前端保持 TypeScript `DeepImmutable<T>` + immer 双层策略
> - **Q4 §10.6.7 CircuitBreaker 并发修复 (P1)**: 替换自定义 CircuitBreaker 为 Resilience4j `resilience4j-circuitbreaker`，消除 CAS 竞态和状态转换 bug
> - **Q5 §10.6.6 X-Forwarded-For 安全加固 (P1)**: 限制 trusted proxy 范围，仅信任 `127.0.0.1` 和 Docker 网桥网段的 X-Forwarded-For 头

**v1.49.0 变更日志** (2026-04-05):
> **交叉验证阻塞项修复 — 12 项（3 项 P0 + 9 项 P1）**:
>
> **P0 阻塞修复（3 项）**:
> - **§6.1.3 权限对话框 (F4-07)**: 新增 `CompletableFuture.orTimeout(60s)` + SQLite `pending_permissions` 持久化 + 断线恢复 `GET /api/permissions/pending` + 前端倒计时
> - **§3.1 API Key 轮换 (F2-03)**: 新增 `ApiKeyRotationManager` (round-robin/random/failover 三策略 + 429 自动切换) + 模型能力矩阵独立表格 (11 模型 × 7 能力维度)
> - **§8.2.6a PromptInput IME (F4-03)**: 新增 `isComposing`/`keyCode===229` 保护, CJK 输入法 Enter 确认候选词不误触提交
>
> **P1 阻塞修复（9 项）**:
> - **§4.1.16 REPLTool (F3-01/F5-01)**: 删除 `createInterpreter()` 中的 `.redirectErrorStream(true)`, 新增双 StreamGobbler 分离 stdout/stderr
> - **§4.1.1c AgentConcurrencyController (F5-03)**: `acquireSlot()` 新增 sessionId 参数 + `ConcurrentHashMap` 会话级并发检查 + AgentSlot 释放时递减
> - **§10.6.7 CircuitBreaker (F3-02)**: `volatile`+`synchronized` → `AtomicReference`+CAS, 新增 `PROBING` 状态, HALF_OPEN→PROBING 仅一个探测线程
> - **§4.1.1d.3 WorktreeManager (F3-04/F5-05)**: `inheritIO()` → 输出捕获; `mergeBack()` 新增 checkout 原始分支 + `execCapture()` 方法
> - **§4.1.1d.2 BackgroundAgentTracker (F3-06)**: 推送路径从 `/topic/agents/{agentId}` 改为 `/topic/session/{sessionId}`, AgentStatus 新增 sessionId
> - **§4.1.5 NotebookEditTool (F3-03)**: 新增 JVM 内路径级 `ReentrantLock` (`ConcurrentHashMap<Path,ReentrantLock>`) 双层锁策略
> - **§4.1.1b.5 InProcessBackend (F4-05)**: `ScopedValue (或 ThreadLocal)` 统一为 `ThreadLocal`, 补充 Preview 特性不可用原因
> - **§4.6.2 PluginExtension (F2-08)**: 新增完整接口定义 (12 个方法签名) + `PluginContext` 接口

**v1.48.0 变更日志** (2026-04-05):
> **交叉验证修复 — 22 项（基于合并分析报告逐条验证 SPEC v1.47.0）**:
>
> **P0 阻塞修复（6 项）**:
> - **§8.5.1b PermissionResponsePayload**: decision 类型对齐为 `'allow' | 'deny' | 'allow_always'`，消除与 §8.5.2 的不一致
> - **§8.5.2 SetPermissionModePayload**: mode 统一为 `'plan' | 'auto' | 'bypassPermissions'`，消除双重命名体系
> - **§8.5.4 Controller records**: PermissionResponsePayload/SetPermissionModePayload 枚举化字段类型
> - **§3.2.1 TrackedTool**: `record` → `class` + volatile state + synchronized setter（record 无法 setState）
> - **§10.4 CircuitBreaker**: onSuccess/onFailure 加 `synchronized` 保护复合状态转换原子性
> - **§3.9.1 CostTracker**: 定价表从硬编码 → `@ConfigurationProperties` + YAML 外化配置
>
> **P1 阻塞修复（4 项）**:
> - **§3.2.3a YoloClassifier**: 补充系统 prompt 模板（classpath 资源 + 占位符替换）
> - **§4.7.2 SkillTool**: 补充 frontmatter YAML Schema 定义（allowed_tools/model_config 等字段）
> - **§9.1.0a Cookie secure**: `secure(false)` → `secure(isHttpsEnabled())`，动态检测 HTTPS 配置
> - **§8.8.4b Page Visibility API**: 新增移动端后台/前台切换处理（暂停心跳 + 重连补偿）
>
> **质量提升修复（12 项）**:
> - **§8.5.4 WebSocket 前端路径**: `/ws/session/${sessionId}` → `/ws`（对齐后端 `registerStompEndpoints("/ws")`）
> - **§8.5.4a 双层心跳**: 补充 STOMP heartbeat + 应用层 ping 协调说明
> - **§8.3.2 flushSync**: 补充性能保护说明（限制使用场景 + startTransition 替代方案）
> - **§8.7.2 OffscreenCanvas**: 补充 Safari/Firefox 降级检测（`typeof OffscreenCanvas`）
> - **§8.7.1 CodeBlock**: 统一 PrismJS 高亮库，消除 shiki 引用歧义
> - **§3.7.2a @include**: 扩展名白名单补充 `.yaml/.yml/.toml/.ini/.cfg/.conf/.properties`
> - **§3.7.2a validateMemoryPath**: 补充路径遍历防护规则 + 大小限制
> - **§3.10.5/3.10.6 章节编号**: 重编避免与 §3.8.4/3.8.5 冲突
> - **§3.8.2 任务输出**: 补充文件格式注释（JSON Lines + 编码约定）
> - **§2.5.3 CompactQualityReport**: 重复定义 → 引用注释
> - **§9.1.0a sessionStore**: 明确为 `ConcurrentHashMap<String, Instant>` 字段声明
> - **§9.3.1 BashSandbox**: 输出截断优化（追加提示 + 提前 break 避免空读 I/O）
> - **§9.5 PlainTextFallbackStorage**: POSIX 文件权限跨平台（Windows ACL 降级）
> - **§9.1.0a RemoteAccessSecurityFilter**: 修复残留重复代码

**v1.47.0 变更日志** (2026-04-04):
> **可实现性审查修复 — 25 项（基于全文严格审查）**:
>
> **类别 2 — 11 项信息补充**:
> - **§2.3 启动流程 (F2-10)**: 补充 SmartLifecycle @Order 精确顺序 + Python 失败降级策略
> - **§3.1.3a LLM 流式通信 (F2-01)**: 补充 OpenAI SSE 事件→统一 LlmStreamEvent 映射说明 + SseFrameParser P2 标注澄清
> - **§3.2.3a/§3.2.3c BashTool (F2-02/F4-01)**: 补充 tree-sitter-bash Java 方案决策引用 + Python 辅助解析路径
> - **§3.4.3a.2 YoloClassifier (F2-03)**: CLASSIFIER_MODEL 改为配置化 `llm.classifier-model` + getLightweightModel() 方法
> - **§3.9.3 GitOperationTracking (F2-04)**: 补充 Git 操作检测正则列表 (6 种命令模式)
> - **§3.11 Elicitation (F2-11)**: 补充前端动态表单渲染方案 (@rjsf/core) + 超时等待 UI 状态
> - **§4.1.4 LSPTool (F2-05)**: 补充 LSP 服务器安装依赖说明 + TextDocumentSyncKind 策略 + 请求级超时 30s
> - **§4.1.16 REPLTool (F2-06)**: 补充 pty4j 库建议 + REPL 会话超时/并发限制说明
> - **§4.3.8 MCP (F2-07)**: 补充 MCP Java SDK Maven 坐标 (io.modelcontextprotocol:mcp-spring-webmvc)
> - **§8.2.4D DiffView (F2-08)**: 补充大文件 diff 限制 (>10000 行前 500 行 + 省略提示)
> - **§8.8.4 PWA (F2-09)**: 补充 vite-plugin-pwa 完整配置 + 缓存策略 + manifest 图标尺寸
>
> **类别 3 — 5 项不合理修正**:
> - **§9.4+§10.6.6 SecurityConfig (F3-01/F5-01)**: §9.4 改为仅 CORS 辅助配置，认证链统一引用 §10.6.6
> - **§9.3.1 validateCommandSubstitutions (F3-02)**: 改为仅 AUTO 模式生效，用户手动确认命令放行
> - **§6.2 WebSocket 环形缓冲区 (F3-03)**: 缓冲区大小改为可配置 (默认 1000，最大 5000) + FullResyncEvent 溢出策略
> - **§8.5.3 StreamDeltaBuffer (F3-04)**: stream_delta 改用 flushSync 直接更新，仅 tool_progress 使用 startTransition
> - **§10.6.6 dynamicAuthorizationManager (F3-05)**: 补充 X-Forwarded-For 检查 + forward-headers-strategy 配置
>
> **类别 4 — 2 项技术适配**:
> - **§3.2.3a BashTool (F4-01)**: 引用 §3.2.3c Java 手写递归下降方案，补充 Python 辅助解析降级路径
> - **§8.2.4B PromptInput (F4-02)**: 补充 VisualViewport resize 事件处理说明
>
> **类别 5 — 7 项矛盾修正**:
> - **§9.4 vs §10.6.6 SecurityConfig (F5-01)**: §9.4 标注"已被 §10.6.6 取代"，保留仅 CORS 配置
> - **§9.1.0 vs §10.5.4 RemoteAccessSecurityFilter (F5-02)**: §9.1.0a 标注权威引用 §10.5.4
> - **§2.8.1 pom.xml (F5-04)**: 统一 Spring Boot 3.3.5 + Spring Security 6.3.4 精确版本
> - **§3.1.5/§3.1.6 autoCompact (F5-05)**: 明确双阈值判断顺序和优先级
> - **§12.1-12.2 映射表 (F5-06)**: 补充路径简写说明
> - **§4.6.1 PluginSourceType (F5-07)**: 确认已修正为 3 种 (LOCAL/MARKETPLACE/BUILTIN)

**v1.46.0 变更日志** (2026-04-04):
> **阻塞P0修复 — 5项**:
> - **§7.4.2 V001/V002 (致命)**: 迁移脚本完全重写对齐 §7.2 权威 Schema；sessions 从 global.db 移至 data.db；所有时间字段 INTEGER→TEXT；字段名统一 (uuid→id, content→content_json, timestamp→created_at)；新增 file_snapshots/tasks 表
> - **§3.2.3c Bash解析器 (高)**: 新增 TS→Java 移植技术方案 — 选型手写递归下降(非ANTLR) + 6步移植计划 + Java sealed interface AST 预览 + 3449条测试用例验证策略
> - **§10.3 start-dev.sh (高)**: 增强启动脚本 — Python venv 自动化 + 完整 JVM 调优参数(ZGC/MetaspaceSize/编码) + 健康检查替代 sleep + DEBUG 远程调试支持 + Python 版本检查
> - **§3.2.3 WebSearchTool (中)**: 补充 Brave Search API 完整 HTTP 请求/响应示例 + 错误处理 + Java 实现要点 + SerpAPI 替代方案
> - **§7.4.2 V001 (中)**: sessions 表从 global.db 移至 data.db (V002)，global.db 仅保留跨项目共享数据
>
> **阻塞P1修复 — 5项**:
> - **§4.1.1 AgentTool (高)**: 删除重复的并发常量(3)和 Semaphore，统一委托给 §4.1.1c AgentConcurrencyController(30)；新增 buildSubagentPrompt 子代理系统提示词构建逻辑
> - **§6.1.5/§12 插件API (高)**: source 字段从 "npm"|"git"|"local" 修正为 "local"|"marketplace"|"builtin"，对齐 §4.6.1 PluginSourceType；§12 附录同步更新为3种
> - **§4.1.4 LSPTool (中)**: 补充 jdtls 完整 LSP initializationOptions (运行时配置/Maven+Gradle导入/代码补全/组织导入等) + 启动命令格式 + 自动检测安装策略
> - **§4.5 IDE桥接 (中)**: 补充 WebView JS SDK 完整 API (AiCodeAssistantBridge 类定义/连接管理/命令执行/文件操作/事件订阅/权限响应/心跳) + BridgeConfig 配置接口
> - **§4.1.3 TaskCreateTool (中)**: 补充 VirtualThread 中断传播到子进程的三层机制 (Thread.interrupt → Cancellable → TaskCoordinator 协调) + 孤儿进程保护
>
> **质量提升 — 5项**:
> - **§8.2.3E DiffView (低)**: 新增 SimpleDiffView 移动端降级规范 (highlight.js + unified diff + 768px 切换阈值)
> - **§8.2.3F TerminalOutput (低)**: 补充 xterm.js React Wrapper 完整生命周期 (mount初始化/content更新/ResizeObserver/dispose清理) + 依赖列表
> - **§8.4 图片上传 (低)**: 新增实现者快速参考决策树 + 关键阈值汇总表 (20MB/5MB/256KB/0.85/2000px)
> - **§8.2.4a CircularBuffer (低)**: sizeCache 从 LinkedHashMap<Integer,Long> 改为 WeakHashMap<T,Long>，修复 identityHashCode 碰撞 + 条目不释放问题
> - **§4.1.1 AgentTool (低)**: 嵌套深度统一为 3 (对齐 §4.1.1c MAX_AGENT_NESTING_DEPTH)

**v1.45.0 变更日志** (2026-04-04):
> **类别 2 — 14 项信息补充**:
> - **§3.2.3d FileEditTool (F2-05)**: 补充模糊匹配全部失败反馈格式 + 多处匹配歧义消解策略
> - **§3.5.1 会话恢复 (F2-11)**: 补充大会话 (10000+ 消息) 分页加载策略 (最近 100 条 + 按需加载)
> - **§4.1.1 AgentTool (F2-04)**: 补充 AgentPoolConfig 资源限制 (maxConcurrentAgents=3, maxDepth=2)
> - **§4.1.3 TaskCreateTool (F2-12)**: 补充 TaskExecutor.cancel() 实现 + TaskResult 1MB 限制
> - **§4.1.5 NotebookEditTool (F2-09)**: 补充 NotebookCellOperation 接口 + nbformat JSON 操作 + FileLock
> - **§4.1.16 REPLTool (F2-08)**: 补充 ReplManager 进程池 (P1 仅支持 Python REPL) + STOMP 流式推送
> - **§4.5 IDE 桥接 (F2-03)**: 补充 WebView JS SDK 规范 + 认证握手流程 + 完整消息类型列表
> - **§4.6.2 插件系统 (F2-01)**: 明确 Java SPI + 本地 JAR 加载 + PluginClassLoader 隔离 + API 版本兼容
> - **§4.7.3 Skill 系统 (F2-02)**: 补充 SkillExecutor.execute() 完整流程 (inline/fork 模式)
> - **§8.2.3 ToolCallBlock (F2-07)**: 补充 14 种渲染器完整 Props 和选择逻辑
> - **§8.2.6 前端路由 (F2-13)**: 补充 hash 模式适配 (IDE WebView 检测 + createHashRouter)
> - **§8.5.2 C→S 消息 (F2-10)**: 补充 10 种消息完整 payload TypeScript 接口
> - **§9.5 安全存储 (F2-06)**: 指定跨平台库 (macOS: security CLI, Linux: secret-tool, Windows: JNA+Advapi32)
> - **§10.3 部署 (F2-14)**: 新增 start-dev.sh 裸机开发模式启动脚本
>
> **类别 3 — 4 项删除/重构**:
> - **§7.4.2 (F3-01)**: 删除 11 个原版迁移脚本，替换为 3 个项目初始化迁移 (Schema/WAL)
> - **§8.2.4G (F3-02)**: 删除 AppState 完整字段参考 (误导实现者)，保留指向 §8.3 的简短说明
> - **§9.3.1 (F3-03)**: 精简 Docker 隔离部分，移除 docker-compose.yml 过度设计
> - **§4.6.1 (F3-04)**: 插件来源从 5 种精简为 3 种 (LOCAL/MARKETPLACE/BUILTIN)，移除 GIT/NPM
>
> **类别 5 — 6 项错误修正**:
> - **§11 P0 验收**: 工具列表修正为 8 执行工具 + 2 模式控制工具
> - **§11 P0 认证**: "JWT cookie" → "localhost 免认证 + API Key 可选"
> - **§6.1.1**: 添加 P2 标注到 login/logout/refresh 端点
> - **§1.2**: 明确 P0 10 工具 = 8 执行 + 2 模式控制
> - **§7.1.1**: HikariCP 5 连接池注释醒目化
> - **§8.3**: ServerMessage 占位定义替换为完整 27 种联合类型
>
> **v1.44.0 变更日志** (2026-04-04):
> - **§2.8 MCP 依赖修正 (F5-01)**: `mcp-spring-webflux` → `mcp-spring-webmvc`，消除与架构裁决 #1 的矛盾
> - **§4.1.10 PowerShellTool (F5-02)**: `@ConditionalOnProperty` → 自定义 `@ConditionalOnWindows` 注解 + `WindowsCondition`
> - **§4.1.16 REPLTool (F5-03)**: 移除 `redirectErrorStream(true)`，分离 stdout/stderr 捕获
> - **§8.2.6a CodeBlock (F5-04)**: 补全 `useState` import；(F3-04) 添加长代码降级策略
> - **§8.2.4a 时序图 (F5-05)**: `useSyncExternalStore` → `Zustand useStore`
> - **§8.5.3 CostStore (F5-06)**: `sessionCost` 覆盖 → 累加修正
> - **§8.3 StompServerMessage (F5-07/F3-06)**: 删除不完整 10 种定义，统一引用 §8.5.1a 完整 25 种 `ServerMessage`
> - **§8.5.4 STOMP 端点 (F5-08)**: `/ws/session/{sessionId}` → `/ws`，sessionId 通过 CONNECT header 传递
> - **§8.2.6a PromptInput (F5-09)**: Ctrl+C 拦截增加 `!window.getSelection()?.toString()` 选中检测
> - **§8.5.3 dispatch (F5-10)**: 从 useCallback 提取为模块级独立函数
> - **§8.2.6a LoginPage (F3-01)**: API Key 改为后端验证 + httpOnly JWT cookie
> - **§8.2.6a ModelSelector (F3-02)**: 硬编码模型列表 → `useQuery('/api/models')` 动态获取
> - **§8.2.4a CircularBuffer (F3-03)**: JSON 序列化估算 → 字段长度近似估算
> - **§8.5.3 useWebSocket (F3-05)**: 全局 `let` 单例 → React Context + Provider 模式
> - **§4.8.1 Web 键盘适配 (F4-01~04)**: 新增 Web 端保留快捷键表 + 和弦超时 500ms + 浏览器冲突映射
> - **§3.1.1a Step 5 (F4-05)**: 补充 Python 工具 HTTP 调用超时/重试策略
> - **§8.2.4a WebSocket (F4-06)**: 补充消息序号 `seq` 字段 + P1 ACK 机制说明
> - **§8.2.4 组件覆盖 (F4-07)**: 新增 29 组件功能覆盖验证表
> - **§4.9 Auto 模式 (F4-08)**: 补充 Quick 阶段 confidence>0.9 优化 + LRU 判定缓存
> - **§3.1 OkHttp SSE (F2-01)**: 新增 `SseStreamParser` 完整实现 (连接池/代理/SSL)
> - **§3.1.6/§3.5 Compact (F2-02)**: 补充触发阈值 80% + 压缩 prompt 模板 + 电路断路器恢复
> - **§3.3 P0 工具 (F2-03)**: 补充 BashTool destructive 分类表 + FileEditTool diff 库 + WebFetchTool Jsoup 策略
> - **§7.2/§3.4 权限持久化 (F2-04)**: 补充匹配优先级 + TTL 清理
> - **§3.5 SystemPrompt (F2-05)**: 补充 7 个静态段完整模板文本
> - **§4.3 MCP stdio (F2-06)**: 补充 ProcessBuilder stdin/stdout 管理 + SSE endpoint 格式
> - **§4.5 IDE 桥接 (F2-07)**: 补充路径选择决策树 + JWT issuer 验证
> - **§4.8.1 快捷键 (F2-08)**: 补充和弦超时 + 浏览器冲突完整映射表
> - **§8.2.4 DiffView (F2-09)**: 补充 Monaco Worker 配置 + 移动端降级
> - **§8.2.4 TerminalOutput (F2-10)**: 补充 xterm.js React 集成 + addons + scrollback
> - **§9 配置 (F2-11)**: 补充深合并策略 + 搜索路径 + 环境变量映射表
> - **§10 Hook (F2-12)**: 补充执行沙箱 + 超时策略
> - **§2.4.2a Python 恢复 (F2-13)**: 补充退避序列 + 降级策略
> - **§6 附件 (F2-14)**: 补充 AttachmentController 完整实现
> - **§11 路线图 (F2-15)**: 补充 Phase 1 验收清单

**v1.43.0 变更日志** (2026-04-04):
> - **§3.1.2 QueryEngine**: 补充完整 execute() while(true) 查询循环 + 6步状态机 + CancellationToken + submitUserMessage/interrupt/resolvePermission/rewindFiles/resolveElicitation (305行)
> - **§3.1.1 SystemPromptBuilder**: 补充 10 个动态段方法实现 + SystemPromptSection sealed interface + MemoizedSection/UncachedSection (143行)
> - **§3.5 CompactService**: 补充 CompactQualityReport 三维质量验证 + fallbackKeyMessageSelection 6级优先级降级策略 (151行)
> - **§3.6 WebSearchTool**: 补充 4 个 WebSearchBackend 具体实现 (SerpApi/Brave/Searxng/Disabled) + WebSearchBackendFactory (165行)
> - **§8.2.6a 前端完整实现**: 新增 29 个组件/Hook/页面 — App.tsx, ChatPage, AppLayout, Sidebar, Header, StatusBar, PromptInput(完整交互), MessageList(react-virtuoso虚拟滚动), PermissionDialog(三级风险), useWebSocket(STOMP 25消息分发), CodeBlock, MarkdownRenderer, ErrorBoundary, Modal, Toast, CommandPalette, FileUpload, ModelSelector, TaskPanel, LoginPage, SettingsPage 等 (1253行)
> - **§6.1.6a REST Controller**: 新增 ToolController(工具管理) + FileHistoryController(快照回退) + 端点表更新至 9 Controller / 36 端点 (115行)
> - **§4.1.1d Agent 子代理编排**: 新增 SubAgentExecutor(完整生命周期编排) + BackgroundAgentTracker(后台代理追踪+STOMP事件) + WorktreeManager(Git Worktree 生命周期) (356行)
> - **§3.9 FileHistoryService**: 补充 rewindToSnapshot() 完整实现 + trackEdit 补全 + computeDiffStats 完整实现 + listSnapshots + helper 方法 (199行)

---

