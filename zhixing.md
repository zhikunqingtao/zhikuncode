这是一个很好的战略问题。SPEC.md 有 5.6 万行，没有任何 AI 能一次性消化并实现它，必须分阶段、按依赖顺序拆解。以下是我建议的实施策略和 prompt 模板：

---

## 总体策略：4 阶段 × 模块化 prompt

### 原则

1. **每个 prompt 只聚焦 SPEC 的一个小节**（不超过 1-2 个 §）
2. **先骨架后血肉** — 先建项目结构和接口，再填实现
3. **每步可验证** — 每个 prompt 的产出必须能编译/运行
4. **用 `@` 附件引入 SPEC 片段** — 而非期望 AI 记住全文

---

### 阶段 0：项目脚手架（1 轮 prompt）

```
请参照 SPEC.md §2.8 的项目结构和 §1.3-§1.4 的技术栈定义，初始化以下三个子项目：

1. Java 后端：Spring Boot 3.3+ 项目（Gradle/Maven），包结构按 §2.8.1
   - 包含 pom.xml/build.gradle 核心依赖（OkHttp、SQLite JDBC、Jackson、Lombok、MapStruct）
   - application.yml 基础配置
   
2. Python 服务：FastAPI 项目，按 §4.14 结构
   - requirements.txt（tree-sitter、rope、jedi、pygls、bashlex）
   - main.py + health 端点
   
3. React 前端：Vite + React 18 + TypeScript 项目，按 §2.5
   - vite.config.ts（含 proxy 到 localhost:8080）
   - Zustand + Tailwind + shadcn/ui 依赖
   - .env 模板

三个项目能各自独立启动即可，不需要业务逻辑。
```

---

### 阶段 1：P0 核心后端（按依赖序，~10 轮 prompt）

每轮 prompt 的模板：

```
请参照 SPEC.md §X.X.X 实现 [模块名]。

核心要求：
- [从 SPEC 中提取的 2-3 个关键设计点]
- [接口签名/数据结构要求]

依赖：[上一步已完成的模块]
验收标准：[能编译 / 单元测试通过 / API 可调用]
```

**推荐顺序：**

| 轮次 | 目标模块 | SPEC 章节 | prompt 要点 |
|------|---------|----------|------------|
| 1 | 数据模型 | §5.1-§5.2 | `sealed interface Message`、品牌化 ID (`SessionId`/`MessageId`) |
| 2 | 数据库层 | §7.1-§7.2 | SQLite 双库 Schema (`global.db` + `data.db`)、Migration |
| 3 | AppState | §3.5 | Java record 6 子组 90+ 字段、不可变状态 |
| 4 | SessionManager | §3.6 | 会话 CRUD、SQLite 持久化、WAL 模式 |
| 5 | LLM Provider 抽象层 | §3.1.1-§3.1.1b | `LlmProvider` 接口、OkHttp SSE 流式、速率限制头解析 |
| 6 | Tool 基础框架 | §3.2.1-§3.2.2 | `Tool<I extends ToolInput>` 接口、`ToolRegistry`、`ToolUseContext` |
| 7 | P0 10 工具 | §3.2.3 | BashTool(含安全分析)、FileRead/Write/Edit、Glob、Grep、WebFetch/Search、PlanMode 2 个 |
| 8 | 权限管线 | §3.4 | 7 阶段决策管线、`PermissionMode` 枚举、风险评估 |
| 9 | QueryEngine | §3.1 | 8 步核心循环、`StreamingToolExecutor`、Virtual Threads |
| 10 | CommandRegistry | §3.3 | 12 个 P0 命令、Spring Bean 自动发现 |

**轮次 7 的具体 prompt 示例：**

```
请参照 SPEC.md §3.2.3 实现 P0 的 10 个核心工具。

已完成的依赖：Tool 接口框架（Tool<I>、ToolInput、ToolResult、ToolUseContext）。

实现要求：
1. BashTool (§3.2.3c)：ProcessBuilder 执行 + 120s 超时 + SIGTERM→2s→SIGKILL，
   输出截断 30000 chars。安全分析部分先用正则匹配降级方案，
   Java AST 解析器（§3.2.3c.1 六步移植方案）留到后续专项实现。
2. FileReadTool：java.nio.file.Files.readString，200MB/60K tokens 限制。
3. FileWriteTool：Files.writeString + mtime 竞态检测。
4. FileEditTool：java-diff-utils，3 策略 fuzzy matching（exact/line-trimmed/normalized）。
5. GlobTool：FileSystem.getPathMatcher("glob:...") + FileVisitor。
6. GrepTool：ProcessBuilder 调用 ripgrep（外部依赖），getOptionalInt 获取上下文行数。
7. WebFetchTool：OkHttp + Jsoup HTML→Markdown，100K chars/10MB 限制。
8. WebSearchTool：策略模式 4 后端（Brave/SerpAPI/Searxng/Disabled），模型无关。
9-10. EnterPlanModeTool / ExitPlanModeV2Tool：纯模式切换。

每个工具一个 Java 类，放在 com.aicode.tool.impl 包下。
需要能编译通过并有基础单元测试。
```

---

### 阶段 2：API 与通信层（~4 轮）

| 轮次 | 目标 | SPEC 章节 | 要点 |
|------|------|----------|------|
| 11 | REST API | §6.1 | Spring MVC `@RestController`、QueryController 3 端点 |
| 12 | WebSocket STOMP | §6.2 + §8.5 | STOMP 配置、25 种 ServerMessage 类型、dispatch 分发 |
| 13 | 安全认证 | §9.1.0 + §10.6.6 | 三层递进认证（localhost/Token/JWT）、SecurityFilterChain |
| 14 | 集成测试 | — | 完整链路：前端发消息 → 后端 QueryEngine → LLM → 流式返回 |

---

### 阶段 3：P0 前端（~5 轮）

| 轮次 | 目标 | SPEC 章节 |
|------|------|----------|
| 15 | TypeScript 类型 + Zustand Store | §8.3 全部 11 个 Store |
| 16 | STOMP 客户端 + dispatch | §8.5.3 |
| 17 | MessageList + 14 种渲染器 | §8.2.1-§8.2.2 |
| 18 | PromptInput + PermissionDialog + CommandPalette | §8.2 |
| 19 | 响应式布局 + 主题系统 | §8.6-§8.8 |

---

### 阶段 4：P1 增强（按需，每个子系统 1-2 轮）

```
请参照 SPEC.md §4.1.1 实现 AgentTool 子代理系统。

核心要求：
- Virtual Thread 子代理 + ThreadLocal 隔离
- 嵌套深度 ≤3 层 / 全局 ≤30 / 会话 ≤10 (Semaphore)
- 与已实现的 QueryEngine 复用核心循环

依赖：QueryEngine (§3.1)、Tool 框架 (§3.2)
```

---

## 关键技巧

### 1. 用文件选择代替全文引用

在 Qoder 中，**选中 SPEC.md 的特定行范围**作为附件比让 AI 自己去读更精确：

```
[选中 SPEC.md L6082-L6500]  ← 只选 QueryEngine 核心循环部分
请按照选中内容实现 QueryEngine 的 8 步核心循环...
```

### 2. 为重复模式创建 Skill

如果你发现自己在反复写类似 prompt（比如"实现一个 Tool"），可以用 `/create-skill` 创建一个 `implement-tool` 技能：

```yaml
---
name: implement-tool
description: 按 SPEC 规范实现一个工具类
---
# 工具实现模板
1. 读取 SPEC.md §3.2.3 中该工具的定义
2. 创建 com.aicode.tool.impl.{ToolName}.java
3. 实现 Tool<{ToolName}Input> 接口
4. 创建对应的 {ToolName}Input record
5. 编写单元测试
```

### 3. 每轮结束检查点

每完成一个模块，追加一个验证 prompt：

```
请检查刚才实现的 [模块名] 是否与 SPEC.md §X.X 的以下要点一致：
1. [关键接口签名]
2. [常量值]
3. [错误处理方式]
如有偏差请修正。
```

### 4. BashParser 作为独立专项

BashParser 移植（§3.2.3c.1）是最大单项工程，建议单独开 5-8 轮 prompt 处理，不要和其他模块混在一起。配合 v1.66.0 新增的 50 条黄金测试用例逐条验证。

---

## 预估总轮次

| 阶段 | 轮次 | 说明 |
|------|------|------|
| 脚手架 | 1 | 三项目初始化 |
| P0 后端 | 10 | 核心业务逻辑 |
| API/通信 | 4 | REST + WebSocket + 安全 |
| P0 前端 | 5 | Store + 组件 + 布局 |
| BashParser 专项 | 5-8 | 最复杂的单项移植 |
| P1 增强 | 10-15 | 按需逐个实现 |
| **合计** | **~35-43 轮** | 每轮约 1 个 focused prompt |

每轮 prompt 保持 **单一职责**、**引用精确 SPEC 段落**、**有明确验收标准**，这是用 Qoder 落地大型 SPEC 的核心方法论。