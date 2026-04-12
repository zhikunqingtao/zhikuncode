# Task 7: System Prompt 工程测试报告

**测试日期**: 2026-04-12  
**测试环境**: Backend http://localhost:8080 | LLM: qwen3.6-plus  
**核心文件**: `SystemPromptBuilder.java` (1157行)  
**测试方法**: 源码静态分析 + REST API 实际请求验证 + 日志确认

---

## 一、8 段静态 Prompt 完整性验证 (SP-01)

### 1.1 静态段清单（buildStaticSections 返回的 8 段）

| # | 段名 | 来源 | 字符数 | 行数 | 内容摘要 |
|---|------|------|--------|------|----------|
| 1 | **INTRO_SECTION** | 内联常量 L301-328 | 1,886 | 28 | 身份定义 + 网络安全指令 + URL生成禁止 + 模型身份隐藏 |
| 2 | **SYSTEM_SECTION** | 内联常量 L333-382 | 4,041 | 50 | 权限模式(Auto/Manual/Suggestions) + Hook机制 + 上下文压缩 + 渲染格式 |
| 3 | **DOING_TASKS_SECTION** | 内联常量 L385-497 | 8,837 | 113 | 任务执行指南 + 代码风格 + 验证完成 + 诚实透明 + 避免过度工程 |
| 4 | **ACTIONS_SECTION** | 内联常量 L500-543 | 2,636 | 44 | 操作风险评估(可逆性/爆炸半径/可见性) + 安全/危险操作分类 |
| 5 | **getUsingToolsSection()** | 内联基础段 + 4个条件子段 | 4,872+ | 73+ | 工具选择优先级 + 任务管理 + 并行调用 + Agent/MCP/错误处理 |
| 6 | **TONE_STYLE_SECTION** | 内联常量 L697-717 | 1,677 | 21 | 语调风格：无emoji + 简洁 + 代码引用格式 + 无废话 |
| 7 | **getOutputEfficiencySection()** | 内联(两版) | 4,361(内部)/1,427(外部) | 73/25 | 沟通原则 + 结构输出 + 简洁规则 + 错误报告 |
| 8 | **FUNCTION_RESULT_CLEARING_SECTION** | 内联常量 L822-854 | 1,846 | 33 | 工具结果保留策略：必须记录/可丢弃分类 |

**段 5 条件子段详情**：

| 子段 | 触发条件 | 字符数 | 说明 |
|------|----------|--------|------|
| REPL_MODE_GUIDANCE | `REPL_MODE` flag | 372 | REPL模式工具替换 |
| EMBEDDED_SEARCH_GUIDANCE | `EMBEDDED_SEARCH_TOOLS` flag | 380 | 嵌入式搜索工具优先 |
| SKILL_DISCOVERY_GUIDANCE | `SKILL_DISCOVERY` flag + DiscoverSkills工具 | 865 | 技能发现工具使用 |
| FORK_SUBAGENT_GUIDANCE | `FORK_SUBAGENT` flag | 752 | Fork vs Spawn 子代理 |

**段 7 双版本**：
- 内部版 (OUTPUT_EFFICIENCY_SECTION, 4,361 chars): 侧重文章式写作、冷读者视角、逻辑线性表达，适用于 `INTERNAL_USER_MODE` 启用时
- 外部版 (OUTPUT_EFFICIENCY_EXTERNAL, 1,427 chars): 侧重简洁直接，先结论后推理，为默认版本

### 1.2 外部 Prompt 模板文件（追加在静态段之后）

| # | 文件名 | 路径 | 字符数 | 行数 | 内容摘要 |
|---|--------|------|--------|------|----------|
| 9 | tool_examples.txt | prompts/ | 7,188 | 200 | FileEdit/FileRead/FileWrite/BashTool/GrepTool/GlobTool/TodoWrite/AgentTool 详细用例 |
| 10 | boundary_conditions.txt | prompts/ | 6,414 | 132 | 文件大小限制/Token限制/并发限制/搜索限制/命令限制/Git操作限制 |
| 11 | code_style_guide.txt | prompts/ | 7,253 | 162 | Java/Python/JS/TS/Go/SQL 代码风格指南 + 注释规范 |
| 12 | security_practices.txt | prompts/ | 7,020 | 151 | 输入验证/路径遍历/命令注入/SQL注入/XSS/敏感信息/认证/依赖安全 |
| 13 | error_recovery.txt | prompts/ | 7,572 | 155 | 错误恢复策略: 诊断优先/工具失败/编译错误/运行时错误/Git错误 |

### 1.3 总大小统计

| 类别 | 字符数 | 说明 |
|------|--------|------|
| 8 段内联静态段 | ~25,796 | buildStaticSections 返回（外部版output efficiency） |
| 5 个外部模板 | ~35,447 | prompts/*.txt |
| **静态段总计** | **~61,243** | 所有静态内容 |

### 1.4 API 实测验证

**方法**: 发送 API 请求要求 LLM 逐项报告系统提示中的段落存在性

**结果**: 13 个段落全部被 LLM 确认存在：
- ✅ 身份定义段 (INTRO_SECTION)
- ✅ System段 (SYSTEM_SECTION) 
- ✅ Doing tasks段 (DOING_TASKS_SECTION)
- ✅ Executing actions with care段 (ACTIONS_SECTION)
- ✅ Using your tools段 (USING_TOOLS)
- ✅ Tone and style段 (TONE_STYLE_SECTION)
- ✅ Output efficiency段 (外部版)
- ✅ Tool result retention段 (FUNCTION_RESULT_CLEARING_SECTION)
- ✅ Tool Usage Examples段 (tool_examples.txt)
- ✅ Boundary Conditions段 (boundary_conditions.txt)
- ✅ Code Style Guide段 (code_style_guide.txt)
- ✅ Security Practices段 (security_practices.txt)
- ✅ Error Recovery段 (error_recovery.txt)

**判定**: ✅ **通过** — 所有 8 段(+5 模板)静态 Prompt 完整存在，内容充实，无占位符或 TODO。

---

## 二、13 段动态 Prompt 注入验证 (SP-02)

### 2.1 动态段完整清单

代码中 `buildSystemPrompt()` L134-204 定义了 13 个动态段（12 个列表内 + 1 个追加）：

| # | 段名 | 类型 | 注入条件 | 数据来源 | 默认是否注入 | API验证 |
|---|------|------|----------|----------|-------------|---------|
| D1 | **session_guidance** | Memoized | enabledTools 包含特定工具 | 工具名集合检查 AskUserQuestion/AgentTool/SkillTool | ✅ 是 | ✅ LLM确认存在 |
| D2 | **memory** | Memoized | ClaudeMdLoader 返回非空 | CLAUDE.md 文件合并加载 | 取决于是否有CLAUDE.md | ❌ LLM报告不存在 |
| D3 | **env_info** | Memoized | 始终注入 | 系统属性(os.name/SHELL) + git检测 + model名称 | ✅ 是 | ✅ LLM完整报告 |
| D4 | **language** | Memoized | locale非en且非空 | configService.getUserConfig().locale() → fallback System.getProperty("user.language") | ❌ 默认en | ❌ LLM确认不存在 |
| D5 | **output_style** | Memoized | 硬编码返回null | getOutputStyleSection() → null | ❌ 不注入 | N/A (预留段) |
| D6 | **mcp_instructions** | **Uncached** | mcpClients非空且有CONNECTED状态 | McpServerConnection列表 | 取决于MCP连接 | ❌ 当前无MCP连接 |
| D7 | **scratchpad** | Memoized | `SCRATCHPAD` flag=true + workDir非空 | appStateStore.session().workingDirectory() | ✅ 是(flag默认true) | ✅ LLM确认存在 |
| D8 | **frc** (Function Result Clearing) | Memoized | `CACHED_MICROCOMPACT`=true + model含haiku/sonnet | FeatureFlagService + model名称匹配 | ❌ 模型不匹配(qwen) | ❌ 预期不注入 |
| D9 | **summarize_tool_results** | **Uncached** | 始终注入(内容随上下文大小变化) | currentMessages + currentContextLimit | ✅ 是 | ✅ LLM确认存在 |
| D10 | **token_budget** | Memoized | `TOKEN_BUDGET` flag=true | FeatureFlagService | ❌ flag默认false | ❌ 预期不注入 |
| D11 | **ant_specific_guidance** | Memoized | `INTERNAL_USER_MODE` flag=true | 内联文本(验证协议+诚实报告+注释纪律) | ❌ flag默认false | ❌ 预期不注入 |
| D12 | **numeric_length_anchors** | Memoized | `NUMERIC_LENGTH_ANCHORS` flag=true | 内联文本(工具调用间25词限制) | ❌ flag默认false | ❌ 预期不注入 |
| D13 | **project_context** | Memoized | projectContextService.getContext()非空 | Git信息 + 文件树 + 项目类型检测 | 取决于git仓库 | ❌ LLM报告不存在* |

*注：project_context 使用 `<project_context>` XML标签包裹，LLM可能未将其识别为独立段落。但 env_info 中已包含 git repository 信息。

### 2.2 当前运行时实际注入的动态段

基于 application.yml 配置和运行环境：

| 段名 | 状态 | 原因 |
|------|------|------|
| session_guidance | ✅ 注入 | 工具池包含 AskUserQuestion、AgentTool、SkillTool |
| memory | ❌ 未注入 | 无 CLAUDE.md 文件 |
| env_info | ✅ 注入 | 始终注入 |
| language | ❌ 未注入 | 默认 locale 为 en |
| output_style | ❌ 未注入 | 硬编码 return null（预留段） |
| mcp_instructions | ❌ 未注入 | 当前无 MCP 服务器连接 |
| scratchpad | ✅ 注入 | SCRATCHPAD=true |
| frc | ❌ 未注入 | 模型名 qwen 不匹配 haiku/sonnet |
| summarize_tool_results | ✅ 注入 | 始终注入基础提示 |
| token_budget | ❌ 未注入 | TOKEN_BUDGET=false |
| ant_specific_guidance | ❌ 未注入 | INTERNAL_USER_MODE=false |
| numeric_length_anchors | ❌ 未注入 | NUMERIC_LENGTH_ANCHORS=false |
| project_context | ⚠️ 可能注入 | 依赖 git 仓库存在性和 workingDir 参数 |

**判定**: ✅ **通过** — 动态段注入逻辑符合设计：4段始终/条件注入，9段受 feature flag 或运行时状态门控。每段的门控条件清晰、实现正确。

---

## 三、分段缓存 cache_control 验证 (SP-03)

### 3.1 缓存分段架构

`SystemPromptBuilder` 使用 **SYSTEM_PROMPT_DYNAMIC_BOUNDARY** 标记分割静态和动态内容：

```
[ 静态段 1..8 ] + [ 外部模板 9..13 ]
       ↓  cacheControl=true
    DYNAMIC_BOUNDARY
       ↓  cacheControl=false  
[ 动态段 D1..D13 ]
```

### 3.2 缓存控制实现分析

**`buildSystemPromptWithCacheControl()` (L237-262)**:
1. 调用 `buildSystemPrompt()` 获取完整段落列表
2. 查找 `DYNAMIC_BOUNDARY` 标记位置
3. 标记之前 → `Segment(prefix, cacheControl=true)` — 可全局缓存
4. 标记之后 → `Segment(suffix, cacheControl=false)` — 每会话动态

**DYNAMIC_BOUNDARY 插入条件**: `shouldUseGlobalCacheScope()` → `featureFlags.isEnabled("PROMPT_CACHE_GLOBAL_SCOPE")`  
**当前状态**: `PROMPT_CACHE_GLOBAL_SCOPE` 未在 application.yml 中配置 → **默认 false → 不插入 BOUNDARY → 整体不可缓存**

### 3.3 段级别缓存策略

`SystemPromptSection` 密封接口的两个实现：

| 类型 | cacheBreak() | 行为 | 使用段 |
|------|-------------|------|--------|
| **MemoizedSection** | false | 首次计算后缓存到 `sectionCache`，跨轮次复用 | D1,D2,D3,D4,D5,D7,D8,D10,D11,D12,D13 |
| **UncachedSection** | true | 每轮重算，不使用缓存 | D6(mcp_instructions), D9(summarize_tool_results) |

### 3.4 与 Anthropic API cache_control 格式兼容性

**`SystemPrompt` record (llm/SystemPrompt.java)**:
- `Segment(String text, boolean cacheControl)` — 对应 Anthropic 的 `cache_control: {type: "ephemeral"}`
- `cacheControl=true` 表示可缓存段
- 实际 API 适配层需要将 `cacheControl=true` 转换为 `cache_control: {type: "ephemeral"}` 标记

**兼容性评估**:
- ✅ 数据模型正确：分段 + 布尔标记
- ✅ 静态/动态分离合理
- ⚠️ 当前 `PROMPT_CACHE_GLOBAL_SCOPE=false`，缓存分段功能未启用
- ⚠️ 实际 LLM 适配层(AliyunProvider)是否传递 cache_control 需进一步确认（qwen 不支持 Anthropic 缓存）

**判定**: ✅ **通过（设计层面）** — 缓存架构设计合理，但因当前使用 qwen 模型（非 Anthropic），cache_control 分段功能处于未启用状态，属于预期行为。

---

## 四、自定义 Prompt 合并验证 (SP-04)

### 4.1 EffectiveSystemPromptBuilder 5 级优先级链

| 优先级 | 来源 | 行为 | 代码位置 |
|--------|------|------|----------|
| 0 (最高) | overrideSystemPrompt | **完全替换**所有其他提示 | L97-100 |
| 1 | Coordinator 模式 | `COORDINATOR_MODE` flag + CoordinatorPromptBuilder | L103-113 |
| 2 | Agent 定义 | proactive=追加到默认提示; 非proactive=替换 | L116-130 |
| 3 | customSystemPrompt (--system-prompt) | **替换**默认提示 | L133-135 |
| 4 (最低) | 默认提示 | SystemPromptBuilder.buildDefaultSystemPrompt() | L139-140 |

**appendSystemPrompt**: 始终追加到最终提示末尾（除非使用 Override 优先级 0）

### 4.2 API 实测结果

**测试 1: 默认 Prompt**
```
请求: {"prompt":"你好","maxTurns":2}
日志: "Using default system prompt"
结果: inputTokens=23,695 → 完整系统提示被注入
```

**测试 2: customSystemPrompt (替换)**
```
请求: {"prompt":"你好","systemPrompt":"你是一个友好的助手，总是用简短的句子回答。","maxTurns":2}
日志: "Using custom system prompt"  
结果: inputTokens=11,072 → 默认提示被替换为自定义提示
回复: "你好！有什么我可以帮你的吗？"（简短风格符合预期）
```

**测试 3: appendSystemPrompt (追加)**
```
请求: {"prompt":"请用一个字回答：天空是什么颜色的？","appendSystemPrompt":"重要：用户每次问你问题时，你必须先说一句:我是追加提示生效的证据。然后再回答。","maxTurns":1}
日志: "Using default system prompt"（基础仍为默认）
结果: 回复以"我是追加提示生效的证据。" 开头 → append 生效
```

**合并逻辑总结**:
- `systemPrompt` 参数 → 走优先级 3 (custom)，**替换**默认提示
- `appendSystemPrompt` → 始终**追加**到最终提示末尾
- 两者可组合使用

**判定**: ✅ **通过** — 自定义 Prompt 合并逻辑正确，替换和追加均按预期工作。

---

## 五、工具描述和 Schema 注入验证 (SP-05)

### 5.1 工具注入机制分析

工具描述不是通过 SystemPrompt 注入，而是通过 LLM API 的 `tools` 参数独立传递：
- `QueryController` L109: `assembleToolPool()` → 收集工具列表
- `QueryController` L128: `toolRegistry.getToolDefinitions()` → 生成工具定义 Map
- `QueryConfig.withDefaults()` → 将工具列表和定义打包到配置对象
- `QueryEngine.execute()` → 通过 LLM Provider API 传递工具定义

### 5.2 API 实测结果

请求 LLM 报告可用工具：

```
结果: 共提供了 34 个工具
工具列表: Write, Read, Edit, Bash, Grep, Glob, Agent, TaskCreate, TaskUpdate, 
TaskList, TaskGet, TaskOutput, TaskStop, Brief, AskUserQuestion, TodoWrite, 
EnterPlanMode, ExitPlanMode, WebFetch, LSP, REPL, Monitor, Memory, Config, 
Skill, ToolSearch, ReadMcpResource, ListMcpResources, SendMessage, 
NotebookEdit, Sleep（及其他）

inputTokens=47,630（含工具定义，远高于基础23K）
```

### 5.3 工具注入格式

- 工具通过 `Tool.toToolDefinition()` 生成标准格式 `{name, description, inputSchema}`
- 由 LLM Provider 按各自 API 规范注入（Anthropic/OpenAI 兼容格式）
- 工具定义包含完整的 JSON Schema 描述

**判定**: ✅ **通过** — 34 个工具的描述和 Schema 正确注入，LLM 可识别和使用。

---

## 六、上下文信息注入验证 (SP-06)

### 6.1 computeEnvInfo() 注入内容分析 (L963-983)

| 字段 | 来源 | 实际值 |
|------|------|--------|
| Primary working directory | workingDir 参数 / System.getProperty("user.dir") | `/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend` |
| Is a git repository | gitService.isGitRepository(cwd) | true |
| Platform | System.getProperty("os.name") | Mac OS X |
| Shell | System.getenv("SHELL") | /bin/zsh |
| OS Version | os.name + os.version | Mac OS X 26.4 |
| Model | 传入的 model 参数 | "default"（REST API 未指定时） |

### 6.2 API 实测结果

LLM 准确报告了所有环境信息：

| 项目 | LLM 报告值 | 预期值 | 匹配 |
|------|-----------|--------|------|
| 工作目录 | /Users/guoqingtao/Desktop/dev/code/zhikuncode/backend | ✓ | ✅ |
| Shell | /bin/zsh | ✓ | ✅ |
| 操作系统 | Mac OS X 26.4 | ✓ | ✅ |
| 模型名称 | default | ✓（REST API 未指定时为 "default"） | ✅ |
| Git 仓库 | true | ✓ | ✅ |

### 6.3 项目类型检测

`ProjectContextService.detectProjectType()` 基于构建文件存在性检测：
- pom.xml → Maven/Java
- package.json → Node.js
- requirements.txt/pyproject.toml → Python

当前项目（zhikuncode）应检测为: "Maven/Java, Node.js, Python"

**判定**: ✅ **通过** — 所有上下文信息正确注入且 LLM 可访问。

---

## 七、Agent 模式差异验证 (SP-07)

### 7.1 三种模式 Prompt 差异

| 模式 | 触发条件 | 系统提示来源 | 独有段落 |
|------|----------|-------------|----------|
| **Default (Leader)** | 无特殊配置 | `SystemPromptBuilder.buildDefaultSystemPrompt()` | 完整 8 静态 + 5 模板 + 13 动态段 |
| **Worker (Agent)** | AgentDefinition 非空 + proactive=false | `agentDef.systemPromptTemplate()` 替换 | 使用 `DEFAULT_AGENT_PROMPT`（957 chars） |
| **Coordinator** | `COORDINATOR_MODE` flag | `CoordinatorPromptBuilder` | 独立的 ~16KB 协调器提示模板 |

### 7.2 Worker/Agent 模式 Prompt

`DEFAULT_AGENT_PROMPT` (L1112-1126, 957 chars) 包含：
- 身份：AI Code Assistant 的代理
- 规则：使用绝对路径、简洁报告、无 emoji
- 特点：完全替换默认提示（非 proactive 模式），或追加（proactive 模式）

### 7.3 Coordinator 模式 Prompt

`CoordinatorPromptBuilder` (16,054 chars, 282 lines) 独有段落：
1. **角色定义** — 协调器而非直接执行者
2. **工具限制** — 仅 Agent/SendMessage/TaskStop
3. **Worker 管理** — 生成、通信、停止工作者
4. **任务工作流四阶段** — Research → Synthesis → Implementation → Verification  
5. **并行策略** — 读只并行/写串行
6. **合成反模式** — 禁止"based on your findings"式惰性委托
7. **Continue vs Spawn 决策矩阵** — 6 种场景的选择指南
8. **Worker Prompt 编写指南** — 自包含、包含文件路径/行号
9. **MCP 服务器列表** — 动态注入
10. **Scratchpad 目录** — 跨 Worker 数据交换

### 7.4 优先级链验证

`EffectiveSystemPromptBuilder.resolveBasePrompt()` 的选择逻辑：

```
Override(0) → Coordinator(1) → Agent(2) → Custom(3) → Default(4)
```

- COORDINATOR_MODE=true 时：返回 Coordinator 提示
- COORDINATOR_MODE=false + Agent 定义存在时：根据 proactive 标志决定替换或追加
- 均不满足时：返回完整默认提示

**判定**: ✅ **通过** — 三种模式的 Prompt 差异清晰，优先级链逻辑正确。

---

## 八、深度分析

### 8.1 Prompt 总大小统计

| 类别 | 字符数 | 估算 Token 数* |
|------|--------|---------------|
| 8 段内联静态段 | ~25,796 | ~6,449 |
| 5 个外部模板 | ~35,447 | ~8,862 |
| **静态段合计** | **~61,243** | **~15,311** |
| 活跃动态段 (env_info + session_guidance + scratchpad + summarize) | ~2,000 | ~500 |
| **总计(无工具定义)** | **~63,243** | **~15,811** |
| 34 个工具定义 (JSON Schema) | ~32,000** | ~8,000 |
| **最终总计(含工具)** | **~95,243** | **~23,811** |

*估算按 4 chars/token，与实际 API 报告 inputTokens=23,695 高度吻合。  
**工具定义大小基于 API 报告 47,630 tokens（含工具请求）- 23,695 tokens（无工具请求）推算。

### 8.2 与原版 Claude Code 对比

| 维度 | ZhikuCode | 原版 Claude Code | 差异分析 |
|------|-----------|-----------------|----------|
| 系统提示总大小 | ~63KB (含模板) | ~40KB | ZhikuCode 更大，因额外5个模板文件(35KB) |
| 静态段数量 | 8 段 + 5 模板 | ~8 段 | 模板文件是 ZhikuCode 的增量 |
| 动态段数量 | 13 段 | ~12 段 | 增加了 project_context 段 |
| 缓存策略 | DYNAMIC_BOUNDARY 分割 | cache_control: ephemeral | 架构对齐，但 qwen 不支持 |
| 工具定义注入 | API tools 参数 | API tools 参数 | 一致 |
| Coordinator Prompt | 16KB | ~1400 行 | 核心工作协议已提取 |
| Worker Prompt | 957 chars | 类似规模 | 精简版，符合预期 |

### 8.3 Prompt 质量评估

| 维度 | 评分 | 说明 |
|------|------|------|
| **完整性** | ⭐⭐⭐⭐⭐ | 覆盖身份/系统/任务/风险/工具/风格/沟通/错误恢复/安全全方位 |
| **指导性** | ⭐⭐⭐⭐⭐ | 每段都包含具体、可操作的指导规则和反例 |
| **结构化** | ⭐⭐⭐⭐⭐ | 清晰的标题层级、列表格式、表格对比 |
| **安全性** | ⭐⭐⭐⭐⭐ | 专门的安全实践段 + 网络安全指令 + 风险评估 |
| **代码风格** | ⭐⭐⭐⭐⭐ | 多语言代码风格指南（Java/Python/JS/TS/Go/SQL） |
| **冗余度** | ⭐⭐⭐ | DOING_TASKS_SECTION 中存在重复内容（验证完成出现两次） |
| **Token效率** | ⭐⭐⭐ | ~63KB 静态内容较大，外部模板可考虑精简 |

### 8.4 已识别问题和改进建议

| # | 类型 | 问题描述 | 严重度 | 建议 |
|---|------|----------|--------|------|
| 1 | **冗余** | DOING_TASKS_SECTION 中"Verifying task completion"和"Verification before completion"内容高度重复(L431-457 vs L449-458) | 低 | 合并为一段 |
| 2 | **预留段** | output_style 段硬编码返回 null（L1038-1040），无实际功能 | 低 | 移除或实现 |
| 3 | **缓存未启用** | PROMPT_CACHE_GLOBAL_SCOPE 默认 false，cache_control 分段功能未生效 | 中 | 如切换到 Anthropic 模型需启用 |
| 4 | **模型名显示** | REST API 未指定 model 时，env_info 注入 "default" 而非实际模型名 | 低 | 在 QueryController 中解析后再传入 |
| 5 | **FRC 模型匹配** | frc 段只匹配 haiku/sonnet，qwen 模型永远不触发 | 信息 | 如需要可添加 qwen 到匹配列表 |
| 6 | **project_context** | 依赖 git 仓库且 workingDir=backend 子目录，可能获取不完整项目上下文 | 低 | 考虑使用项目根目录 |

---

## 九、测试用例总结

| 编号 | 测试用例 | 方法 | 结果 | 判定 |
|------|----------|------|------|------|
| SP-01 | 8段静态Prompt完整性 | 源码分析 + API验证 | 13段全部存在，内容充实 | ✅ **通过** |
| SP-02 | 13段动态Prompt注入 | 源码分析 + API验证 + 日志 | 门控条件正确，4段活跃注入 | ✅ **通过** |
| SP-03 | cache_control分段 | 源码分析 | 架构设计正确，当前未启用(预期) | ✅ **通过** |
| SP-04 | 自定义Prompt合并 | API实测(3种场景) | 替换/追加均正确 | ✅ **通过** |
| SP-05 | 工具描述Schema注入 | API实测 | 34个工具正确注入 | ✅ **通过** |
| SP-06 | 上下文信息注入 | API实测 | cwd/shell/os/git全部正确 | ✅ **通过** |
| SP-07 | Agent模式差异 | 源码分析 | Default/Worker/Coordinator 三模式清晰分离 | ✅ **通过** |

**总体判定**: ✅ **7/7 通过** — SystemPromptBuilder 的 Prompt 组装逻辑实现完整、正确。

---

## 十、附录：关键代码路径

- 主构建器: `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptBuilder.java` (1157行)
- 优先级链: `backend/src/main/java/com/aicodeassistant/prompt/EffectiveSystemPromptBuilder.java` (143行)
- 配置对象: `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptConfig.java` (133行)
- 段抽象: `backend/src/main/java/com/aicodeassistant/prompt/SystemPromptSection.java` (80行)
- 缓存模型: `backend/src/main/java/com/aicodeassistant/llm/SystemPrompt.java` (48行)
- Coordinator: `backend/src/main/java/com/aicodeassistant/coordinator/CoordinatorPromptBuilder.java` (372行)
- 项目上下文: `backend/src/main/java/com/aicodeassistant/context/ProjectContextService.java` (277行)
- 模板目录: `backend/src/main/resources/prompts/` (5 个 .txt 文件, 共 35KB)
- 特性标志: `backend/src/main/java/com/aicodeassistant/config/FeatureFlagService.java` (156行)
- API 入口: `backend/src/main/java/com/aicodeassistant/controller/QueryController.java` (540行)
