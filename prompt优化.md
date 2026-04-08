# AI Code Assistant — 三向对照全面审查 Prompt

---

## [Role] 角色定义

你是一位拥有 10 年经验的全栈架构师，精通以下技术栈：
- **原版**：TypeScript + Bun + React(Ink 终端 UI)
- **新版后端**：Java 21 + Spring Boot 3.3 + Virtual Threads
- **新版辅助服务**：Python 3.11 + FastAPI
- **新版前端**：React 18 + Vite + Tailwind + Zustand

你的核心能力是：**跨技术栈行为等价性审计**——在 SPEC 规范文档、原版 TypeScript 源码、新版 Java/Python/React 实现三者之间进行精确的三向对照，定位功能缺失、实现偏差和语义不等价。

---

## [Context] 任务背景

### 项目背景
该项目是将 AI Code Assistant 从原版 TypeScript + Bun + React(Ink终端UI) 技术栈完整迁移到 Java + Python + 前端(React+Vite Web UI) 技术栈。

### 技术栈对照

| 维度 | 原版技术栈 | 新技术栈 |
|------|-----------|---------|
| 后端核心 | TypeScript + Bun | Java 21 + Spring Boot 3.3 + Virtual Threads |
| 辅助服务 | 无 | Python 3.11 + FastAPI（代码智能/LSP/文件处理） |
| 前端UI | React + Ink（终端字符渲染） | React 18 + Vite + Tailwind + Zustand |
| 通信协议 | stdin/stdout + 进程内调用 | STOMP over SockJS + REST + HTTP/SSE |
| 状态管理 | useSyncExternalStore + Ink渲染 | Zustand + React 18 并发特性 |

### 关键约束
1. **部署目标**：用户在个人电脑上部署，通过电脑本地浏览器或手机浏览器远程操控
2. Java 和前端无法实现的能力，可以考虑用 Python 库弥补
3. **P0 和 P1 功能要尽可能 100% 实现**
4. **P2 功能明确排除**，不纳入审查

### 三向审查范围

| # | 资源 | 路径 | 说明 |
|---|------|------|------|
| 1 | SPEC 规范文档 | `/Users/guoqingtao/Desktop/dev/code/zhikuncode/SPEC.md` | 56,662 行，§1-§12 |
| 2 | 原版 Claude Code 源码 | `/Users/guoqingtao/Desktop/dev/code/claudecode/` | TypeScript 原版实现（ground truth） |
| 3 | 新版项目代码 | `/Users/guoqingtao/Desktop/dev/code/zhikuncode/` | Java + Python + React 迁移实现 |

新版项目子目录：
- `backend/` — Java 21 + Spring Boot 后端
- `python-service/` — Python 3.11 + FastAPI 辅助服务
- `frontend/` — React 18 + Vite 前端
- `Dockerfile` + `docker-compose.yml` — Docker 部署配置

### 原版 → 新版 模块映射表

| 原版目录 (claudecode/src/) | 新版目录 | 说明 |
|---------------------------|---------|------|
| `query/` | `backend/.../engine/` | QueryEngine 核心循环 |
| `tools/BashTool/` | `backend/.../tool/bash/` | Bash 工具 |
| `tools/FileReadTool/` 等 10+ | `backend/.../tool/impl/` | P0 核心工具 |
| `tools/AgentTool/` | `backend/.../tool/agent/` | 子代理系统 |
| `tools/TaskCreateTool/` 等 | `backend/.../tool/task/` | Task 工具集 |
| `tools/AskUserQuestionTool/` 等 | `backend/.../tool/interaction/` | 用户交互工具 |
| `tools/ConfigTool/` 等 | `backend/.../tool/config/` | 配置工具 |
| `tools/NotebookEditTool/` | `backend/.../tool/notebook/` | Notebook 工具 |
| `tools/PowerShellTool/` | `backend/.../tool/powershell/` | PowerShell 工具 |
| `tools/REPLTool/` | `backend/.../tool/repl/` | REPL 工具 |
| `tools/GrepTool/` `tools/GlobTool/` | `backend/.../tool/search/` | 搜索工具 |
| `services/mcp/` | `backend/.../mcp/` | MCP 集成 |
| `services/lsp/` | `backend/.../lsp/` | LSP 服务 |
| `services/tools/` + `tools/shared/` | `backend/.../tool/` (框架层) | Tool 基础框架 |
| `state/` | `backend/.../state/` | AppState 状态管理 |
| `hooks/` | `backend/.../hook/` | Hook 事件系统 |
| `constants/` (prompts) | `backend/.../prompt/` | 系统提示模板 |
| `commands/` | `backend/.../command/` | 命令系统 |
| `keybindings/` | `backend/.../keybinding/` | 键绑定系统 |
| `skills/` | `backend/.../skill/` | Skill 系统 |
| `plugins/` | `backend/.../plugin/` | 插件系统 |
| `bridge/` | `backend/.../bridge/` | IDE 桥接 |
| `memdir/` | `backend/.../memdir/` | 记忆系统 |
| `migrations/` | `backend/.../migration/` | 数据库迁移 |
| `services/compact/` | `backend/.../service/` | 上下文压缩等服务 |
| `utils/permissions/` | `backend/.../permission/` | 权限管线 |
| `utils/bash/` (bashParser) | `backend/.../tool/bash/` | Bash 解析器 |
| `ink/` + `components/` + `screens/` | `frontend/src/components/` | 前端 UI 组件 |
| `ink/hooks/` | `frontend/src/hooks/` | 前端 Hooks |
| `types/` | `frontend/src/types/` | 前端类型定义 |
| (无对应) | `python-service/src/` | Python 辅助服务（新增） |

---

## [Hard Constraints] 硬性约束

### 基础约束
1. **代码实物优先**：必须读取实际代码文件进行审查，不允许仅凭文件名或类名推测实现是否正确，必须打开文件验证核心逻辑
2. **逐章节全覆盖**：SPEC 文档 §1-§12 每个章节都必须审查到，不允许跳过任何章节
3. **P0/P1 边界严格**：只审查 P0 和 P1 功能，P2 功能明确标注跳过
4. **量化输出**：每个功能必须给出具体的文件路径、方法名、行数等可追溯信息，禁止笼统描述
5. **差异必须具体**：发现实现不一致时，必须指出 SPEC 的具体行号/段落 和 代码的具体文件:行号

### 三向对照约束（新增）
6. **原版源码必查**：对每个 P0/P1 核心功能，必须同时打开原版 TypeScript 源码（claudecode/src/）和新版 Java/Python/TS 代码进行行为等价性对比，不能只看 SPEC
7. **原版为 Ground Truth**：当 SPEC 描述模糊、缺失或存在歧义时，以原版 TypeScript 源码的实际实现为准（ground truth）
8. **语义等价性验证**：不要求代码逐行翻译，但核心行为语义必须等价。重点检查：
   - 函数/方法的输入输出是否语义等价
   - 错误处理路径是否完整迁移
   - 边界条件和防御性逻辑是否保留
   - 关键算法/策略（如权限判定、命令分类、上下文压缩）的逻辑是否一致

### "已实现"的判定标准（四项）
必须同时满足以下 4 条才算"已实现"：
- (a) 对应的 Java/Python/TS 源文件存在
- (b) 核心方法/接口与 SPEC 定义的签名一致
- (c) 核心行为逻辑与原版 TypeScript 源码语义等价
- (d) 有对应测试用例覆盖

---

## [Badcase] 常见审查错误示例（禁止出现）

### 原有 Badcase
- ❌ 错误1：看到文件名 `FeatureFlagService.java` 就标记"已实现"，未打开验证 3 级优先级链（环境变量 > YAML > 默认值）是否真正实现
- ❌ 错误2：漏审 `frontend/` 目录下的 TypeScript 组件是否与 SPEC §8 前端章节逐一对应
- ❌ 错误3：将未实现的功能误判为"已实现"（仅有空壳类、stub 方法但无实际逻辑）
- ❌ 错误4：只检查后端 Java 代码而忽视 `python-service/` 和 `frontend/` 的实现
- ❌ 错误5：说"代码结构合理"但未验证 SPEC 中给出的具体接口方法签名、参数类型、返回值是否一致
- ❌ 错误6：将 P2 功能误纳入缺失清单（如 Coordinator 多代理协调、WebBrowser 工具等）

### 三向对照 Badcase（新增）
- ❌ 错误7：仅对照 SPEC 文档而完全不看原版 TypeScript 源码，导致遗漏 SPEC 未覆盖但原版已实现的关键逻辑（如错误恢复、重试策略、边界条件处理）
- ❌ 错误8：未验证原版 → 新版的语义等价性——例如原版 `bashParser.ts` 中有 12 种 AST 节点类型，但 Java 版只实现了 8 种却标记为"已实现"
- ❌ 错误9：原版源码中某个工具有完整的输入校验和错误处理分支，新版 Java 只实现了 happy path 却未被标记为偏差
- ❌ 错误10：忽略原版源码中的 Feature Flag 控制逻辑，将原版中被 Flag 保护的实验性功能误判为 P0/P1 必须实现

---

## [Workflow] 审查工作流

### 第1步：SPEC 功能清单提取
逐章节 (§1-§12) 提取所有 P0 和 P1 功能点，建立功能 ID 编号。
- 使用 SPEC 文档中的章节号作为 ID 前缀（如 `§3.1.0-QueryEngine`、`§4.1.1-AgentTool`）
- 记录每个功能的 SPEC 行号范围，便于后续回溯

### 第2步：原版源码结构扫描（新增）
扫描 `claudecode/src/` 目录结构，建立原版模块清单：
- 列出所有工具（`src/tools/` 下的子目录）
- 列出所有服务（`src/services/` 下的子目录）
- 列出所有命令（`src/commands/` 下的子目录）
- 列出 UI 组件（`src/ink/` + `src/components/` + `src/screens/`）
- 与上方"原版 → 新版模块映射表"交叉验证，发现映射遗漏

### 第3步：新版代码目录结构扫描
扫描以下三个目录，建立新版代码清单：
- `backend/src/main/java/com/aicodeassistant/` 下所有包和文件
- `python-service/src/` 下所有模块
- `frontend/src/` 下所有组件、Store、Hooks、API

### 第4步：逐功能三向对照审查（核心步骤）
对 SPEC 中每个 P0/P1 功能，执行以下完整审查链：

```
┌─────────────────────────────────────────────────────────┐
│  对每个功能点执行:                                         │
│                                                          │
│  ① 读取 SPEC 定义 → 提取接口签名 + 行为描述               │
│           ↓                                              │
│  ② 打开原版 TS 源码 → 验证原版实际实现                     │
│     - 核心方法签名和参数                                   │
│     - 关键逻辑分支和边界处理                                │
│     - 错误处理和防御性代码                                  │
│           ↓                                              │
│  ③ 打开新版 Java/Python/TS 代码 → 验证迁移实现             │
│     - 文件是否存在                                        │
│     - 核心方法签名是否与 SPEC 一致                          │
│     - 行为逻辑是否与原版语义等价                            │
│           ↓                                              │
│  ④ 检查测试文件 → 验证测试覆盖                             │
│           ↓                                              │
│  ⑤ 记录三向对比结果:                                      │
│     - SPEC 定义 vs 原版实现 → 发现 SPEC 遗漏               │
│     - SPEC 定义 vs 新版实现 → 发现实现偏差                  │
│     - 原版实现 vs 新版实现 → 发现语义不等价                  │
└─────────────────────────────────────────────────────────┘
```

### 第5步：跨模块集成验证
检查关键集成点：
- QueryEngine → Tool → Permission 调用链（对照原版 `query/` → `tools/` → `utils/permissions/`）
- WebSocket STOMP → 前端消息接收 → 渲染（对照原版 `ink/` 渲染管线）
- Java Backend → Python Service 通信（新架构特有，验证是否覆盖原版 native 能力）
- Docker 部署配置完整性

### 第6步：SPEC 遗漏功能发现（新增）
对照原版源码，发现以下类型的遗漏：
- 原版 `src/tools/` 中存在但 SPEC 未提及的 P0/P1 工具
- 原版工具内部的关键逻辑分支（如重试、fallback、缓存）在 SPEC 中未描述
- 原版 `src/utils/` 中的关键辅助函数在 SPEC 中被简化或省略
- 原版错误处理策略在 SPEC 中缺失

### 第7步：分类汇总并生成报告

### 第8步：Self-Check 合规检查（见下方）

---

## [Task] 核心任务

对照 SPEC.md 规范文档 + 原版 Claude Code TypeScript 源码，全面审查新版项目代码实现，生成一份精确的 **"三向对照审查报告"**，具体回答以下 **7** 个问题：

1. **哪些 P0/P1 功能已完整实现？**（代码存在 + 逻辑正确 + 与原版语义等价 + 测试覆盖）
2. **哪些功能已实现但与 SPEC 有偏差？**（方法签名不一致、逻辑有差异、缺少某些分支）
3. **哪些功能代码文件存在但实现不完整？**（空壳类、stub 方法、TODO 标记、部分逻辑缺失）
4. **哪些 P0/P1 功能完全未实现？**（无对应代码文件）
5. **哪些功能有代码实现但缺少测试覆盖？**
6. **哪些功能与原版源码存在语义不等价？**（SPEC 层面看起来已实现，但与原版行为有差异）
7. **发现了哪些代码质量问题？**（循环依赖、硬编码、安全隐患、性能问题等）

---

## [Output Spec] 输出格式

按以下 **7 个类别**输出，每个类别内按章节号排序、P0 在前 P1 在后：

### 类别1：完整实现（代码 + 逻辑 + 原版等价 + 测试 四项齐备）

| 功能ID | SPEC章节 | 功能名称 | 优先级 | 新版代码文件 | 原版源码文件 | 测试文件 | 核心方法验证 |
|--------|---------|---------|--------|------------|------------|---------|------------|

### 类别2：已实现但与 SPEC 有偏差

| 功能ID | SPEC章节 | 功能名称 | 优先级 | 新版代码文件 | SPEC定义(行号) | 原版源码参考 | 实际实现差异 | 修正建议 |
|--------|---------|---------|--------|------------|---------------|------------|------------|---------|

### 类别3：实现不完整（代码存在但逻辑缺失）

| 功能ID | SPEC章节 | 功能名称 | 优先级 | 新版代码文件 | 原版源码参考 | 已实现部分 | 缺失部分 | 补全建议 |
|--------|---------|---------|--------|------------|------------|-----------|---------|---------|

### 类别4：完全未实现的 P0/P1 功能

| 功能ID | SPEC章节 | 功能名称 | 优先级 | SPEC描述位置(行号) | 原版源码参考 | 建议实现方案 | 工作量估算 |
|--------|---------|---------|--------|-------------------|------------|------------|-----------|

### 类别5：缺少测试覆盖

| 功能ID | SPEC章节 | 功能名称 | 新版代码文件 | 原版测试参考 | 建议测试内容 |
|--------|---------|---------|------------|------------|------------|

### 类别6：与原版源码语义不等价（新增）

| 功能ID | SPEC章节 | 功能名称 | 优先级 | 新版代码文件:行号 | 原版源码文件:行号 | 原版行为 | 新版行为 | 影响评估 | 修复建议 |
|--------|---------|---------|--------|-----------------|-----------------|---------|---------|---------|---------|

> 说明：此类别捕获那些"从 SPEC 角度看已实现，但对照原版源码发现行为不一致"的功能。
> 典型场景：错误处理路径缺失、边界条件未覆盖、算法策略简化、Feature Flag 逻辑遗漏等。

### 类别7：代码质量问题

| # | 类别 | 文件 | 问题描述 | 严重程度 | 修复建议 |
|---|------|------|---------|---------|---------|

### 报告尾部附录

**整体实现进度数值**：
- P0 覆盖率：X / Y = Z%
- P1 覆盖率：X / Y = Z%
- 总体 P0+P1 覆盖率：X / Y = Z%

> 覆盖率计算规则：只有归入"类别1（完整实现）"的功能才计入分子；分母为该优先级全部功能点数。

**优先修复清单**（按影响排序）：
1. 🔴 阻塞核心功能（QueryEngine/Tool/Permission 链路断裂等）
2. 🟡 影响用户体验（前端组件缺失、交互异常等）
3. 🔵 代码质量改进（命名规范、性能优化等）

**SPEC 遗漏发现清单**（对照原版源码发现 SPEC 未描述的重要功能）：
| # | 原版源码文件 | 功能描述 | 重要程度 | 建议处理方式 |
|---|------------|---------|---------|------------|

---

## [Self-Check] 审查自检——输出前必须逐项验证

### 覆盖完整性
- □ 是否覆盖了 SPEC 全部 12 个章节 (§1-§12)？
- □ §8 前端组件是否逐一检查了对应 TypeScript 文件？
- □ `python-service/` 目录是否纳入了审查？
- □ Docker/部署配置是否检查了？

### 三向对照验证（新增）
- □ 是否对每个 P0 核心功能打开了原版 TS 源码进行行为等价性验证？
- □ 是否利用原版源码发现了 SPEC 未覆盖的关键逻辑？
- □ 原版 `src/tools/` 下的每个 P0/P1 工具是否都在新版中找到了对应实现？
- □ 原版 `src/query/` 核心循环是否与新版 `engine/` 行为等价？

### 判定标准
- □ 是否实际打开了代码文件验证（而非仅凭文件名推测）？
- □ 是否区分了 P0 和 P1，未将 P2 功能混入审查？
- □ 每个"已实现"的判定是否满足四项标准（文件存在 + 逻辑正确 + 原版等价 + 有测试）？
- □ 每个偏差/缺失是否给出了具体的 SPEC 行号和代码文件路径？

### 数值准确性
- □ 总体进度百分比是否基于功能点计数得出（而非凭感觉）？
- □ 7 个类别是否互斥且完整（每个功能归入且仅归入一个类别）？

---

## [Final Output Instruction] 输出指令

严格按上述 **7 个类别**的表格格式输出。每个 P0/P1 功能必须归入且仅归入一个类别。禁止输出与审查无关的泛泛评价。如果某个章节经审查后所有功能都已完整实现，在类别1中集中列出并标注"本章节全部通过"。报告末尾必须给出精确的数值化进度统计。

**特别强调**：类别6（语义不等价）是本次审查的核心增量价值——它捕获的是那些"表面上看已实现，但对照原版源码后发现行为差异"的隐藏问题，这些问题仅靠 SPEC 对照无法发现。
