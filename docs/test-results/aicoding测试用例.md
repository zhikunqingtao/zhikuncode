# ZhikunCode AI Coding 测试用例

> **文档版本**: v1.0 | **创建日期**: 2026-05-16 | **基于变更**: main 分支未提交代码（18 files modified + 20 files added）
> **关联测试报告**: [v9.3 全链路测试报告](v9.3/ZhikunCode全链路测试报告.md)

---

## 一、变更概览

### 1.1 新增文件（20个）

| # | 文件路径 | 功能描述 |
|---|----------|----------|
| 1 | `engine/correction/SelfCorrectionLoop.java` | 自纠错循环主控逻辑：错误检测→修复指令生成→中止判断 |
| 2 | `engine/correction/CorrectionInstruction.java` | 修复指令数据结构（record）：指令+上下文+类型+尝试次数 |
| 3 | `engine/correction/CompileErrorParser.java` | 编译错误解析器：支持 Java/TypeScript/Python |
| 4 | `engine/correction/TestFailureParser.java` | 测试失败解析器：支持 JUnit/Jest/pytest |
| 5 | `engine/tokenizer/TokenizerService.java` | 精确 Tokenizer 服务：调用 Python tiktoken 计数 |
| 6 | `service/GitDiffTracker.java` | Git 变更追踪器：聚合 Git 状态与编辑历史 |
| 7 | `skill/SkillTokenBudget.java` | Skill 级别 token 预算控制（单 Skill 5000/会话 25000） |
| 8 | `skill/SkillToolValidator.java` | Skill 安全拦截器：工具权限+参数安全性+fork 嵌套验证 |
| 9 | `tool/search/ScopeContext.java` | 搜索作用域上下文 |
| 10 | `tool/search/ScopedSearchResult.java` | 分层搜索结果 |
| 11 | `tool/search/SearchMatch.java` | 搜索匹配项 |
| 12 | `tool/search/SearchQuery.java` | 搜索查询参数 |
| 13 | `tool/search/SearchStrategyRouter.java` | 搜索策略路由器：作用域感知分层搜索 |
| 14 | `python-service/src/routers/tokenizer.py` | Python 精确 Token 计数路由（tiktoken cl100k_base） |
| 15 | `test/.../ContextCascadeE2ETest.java` | 上下文级联端到端测试 |
| 16 | `test/.../correction/` | SelfCorrectionLoop 相关测试 |
| 17 | `test/.../tokenizer/` | TokenizerService 相关测试 |
| 18 | `test/.../GitDiffTrackerTest.java` | GitDiffTracker 单元测试 |
| 19 | `test/.../SkillTokenBudgetTest.java` | SkillTokenBudget 单元测试 |
| 20 | `test/.../SkillToolValidatorTest.java` | SkillToolValidator 单元测试 |

### 1.2 修改文件（18个）

| # | 文件路径 | 变更摘要 | 行数 (+/-) |
|---|----------|----------|-----------|
| 1 | `QueryEngine.java` | 集成 SelfCorrectionLoop：步骤 5.5 自纠错检测 + `findToolResultContent` 辅助方法 | +76 |
| 2 | `QueryLoopState.java` | 新增 correctionAttempts/previousToolOutput 状态字段 | +11 |
| 3 | `TokenCounter.java` | 集成 TokenizerService + FeatureFlagService，精确计数优先策略 | +22/-6 |
| 4 | `SkillExecutor.java` | 集成 SkillToolValidator/SkillTokenBudget/TokenCounter，超时层级治理 | +80 |
| 5 | `ToolExecutionPipeline.java` | 新增 Bash 错误恢复阶段 5c + 退出码解析增强 + 指数退避重试 | +121 |
| 6 | `BashCommandClassifier.java` | 新增 `classifyForTimeout` 方法：动态超时分类（编译/测试/安装/Git/服务） | +74 |
| 7 | `CommandCategory.java` | 枚举扩展：新增5个细粒度分类 + `recommendedTimeoutMs` 属性 | +45/-28 |
| 8 | `BashTool.java` | 集成动态超时分类，使用 `CommandCategory.getRecommendedTimeoutMs()` | +16 |
| 9 | `BashRecoveryPolicy.java` | 重构：移除硬编码分支逻辑，改用 BashErrorClassifier 分类映射 | +37/-72 |
| 10 | `application.yml` | 新增 Feature Flag D/E/F 组（SELF_CORRECTION_LOOP/PRECISE_TOKENIZER/GIT_DIFF_TRACKER/SEARCH_STRATEGY_ROUTER） | +8 |
| 11 | `python-service/src/main.py` | 注册 tokenizer 路由 | +2 |
| 12-18 | 7 个测试文件 | 适配构造函数签名变更 | 总计 +53/-20 |

### 1.3 功能模块分类

| 功能模块 | 涉及文件数 | Feature Flag |
|----------|-----------|--------------|
| **A. SelfCorrectionLoop 自纠错循环** | 7 | `SELF_CORRECTION_LOOP` |
| **B. 精确 Tokenizer** | 4 | `PRECISE_TOKENIZER` |
| **C. Skill 增强（预算+安全）** | 3 | — (始终生效) |
| **D. Bash 动态超时+恢复增强** | 5 | — (始终生效) |
| **E. Git 变更追踪** | 1 | `GIT_DIFF_TRACKER` |
| **F. 搜索策略路由** | 6 | `SEARCH_STRATEGY_ROUTER` |

---

## 二、变更影响分析

### 2.1 受影响的现有测试用例

| 现有测试用例 | 影响原因 | 影响程度 |
|-------------|----------|----------|
| **TC-AL-04/05: Agent Loop 工具调用** | QueryEngine 新增自纠错路径 | 中（逻辑分支增加） |
| **TC-AL-07: Token 使用统计** | TokenCounter 逻辑变更（精确计数优先） | 低（Feature Flag 默认关） |
| **TC-AL-08: 上下文压缩** | TokenCounter 估算逻辑可能影响压缩阈值 | 低 |
| **TC-TOOL-04/05: Bash 执行** | BashTool 超时逻辑+恢复策略重构 | 高 |
| **TC-TOOL-10: 工具启用/禁用** | ToolExecutionPipeline 恢复逻辑变更 | 低 |
| **模块 4: Agent Loop 核心循环** | QueryEngine 构造函数签名变更 | 中（DI 注入） |
| **模块 5: 工具系统与安全** | BashRecoveryPolicy 重构 | 高 |
| **模块 9: 技能系统** | SkillExecutor 构造函数+执行逻辑变更 | 高 |

### 2.2 变更-测试映射关系

| 变更文件/模块 | 新增测试用例 | 受影响现有用例 |
|--------------|-------------|---------------|
| `SelfCorrectionLoop` + `QueryEngine` | TC-SCL-01~08 | TC-AL-04, TC-AL-05 |
| `CompileErrorParser` + `TestFailureParser` | TC-SCL-09~14 | — |
| `TokenizerService` + `TokenCounter` | TC-TOK-01~06 | TC-AL-07, TC-AL-08 |
| `SkillTokenBudget` + `SkillToolValidator` | TC-SKL-01~08 | 模块 9 技能系统 |
| `SkillExecutor` | TC-SKL-09~12 | TC-2.7a, TC-2.7b |
| `BashCommandClassifier` + `CommandCategory` | TC-BTO-01~06 | TC-TOOL-04 |
| `ToolExecutionPipeline` | TC-BTO-07~12 | TC-TOOL-04, TC-TOOL-05 |
| `BashRecoveryPolicy` | TC-BTO-13~16 | 模块 5 |
| `GitDiffTracker` | TC-GIT-01~04 | — |
| `SearchStrategyRouter` | TC-SSR-01~06 | TC-TOOL-08 |

---

## 三、新增测试用例设计

### 3.1 P0 测试用例（核心功能，必须通过）

---

#### TC-SCL-01: SelfCorrectionLoop 编译错误检测与修复指令生成

- **用例ID**: TC-SCL-01
- **测试场景**: 当 Bash 工具输出包含 Java 编译错误时，SelfCorrectionLoop 能正确检测并生成修复指令
- **前置条件**: Feature Flag `SELF_CORRECTION_LOOP` 开启；会话已建立
- **执行步骤**:
  1. 模拟 Bash 工具输出包含 `QueryEngine.java:45: error: cannot find symbol`
  2. 调用 `selfCorrectionLoop.detectAndPrepareCorrection(toolOutput, 0)`
  3. 验证返回的 CorrectionInstruction
- **预期结果**:
  - 返回 `Optional` 不为空
  - `type` 为 `COMPILE_ERROR`
  - `instruction` 包含文件名、行号和错误消息
  - `attemptNumber` 为 1
- **验证方式**: 自动（单元测试）

---

#### TC-SCL-02: SelfCorrectionLoop 最大尝试次数限制

- **用例ID**: TC-SCL-02
- **测试场景**: 修复尝试达到 MAX_ATTEMPTS(3) 后停止自纠错
- **前置条件**: Feature Flag `SELF_CORRECTION_LOOP` 开启
- **执行步骤**:
  1. 调用 `detectAndPrepareCorrection(toolOutputWithErrors, 3)`（currentAttempts=3，已达上限）
  2. 验证返回结果
- **预期结果**: 返回 `Optional.empty()`，日志输出 "attempt limit reached"
- **验证方式**: 自动（单元测试）

---

#### TC-SCL-03: SelfCorrectionLoop 中止检测（新增错误）

- **用例ID**: TC-SCL-03
- **测试场景**: 修复后错误数增加时中止修复循环
- **前置条件**: 已有一次修复尝试
- **执行步骤**:
  1. 设置 previousToolOutput 含 1 个编译错误
  2. 设置 newToolOutput 含 3 个编译错误
  3. 调用 `selfCorrectionLoop.shouldAbort(newToolOutput, previousToolOutput)`
- **预期结果**: 返回 `true`，日志包含 "Error count increased"
- **验证方式**: 自动（单元测试）

---

#### TC-SCL-04: SelfCorrectionLoop 在 QueryEngine 中完整集成

- **用例ID**: TC-SCL-04
- **测试场景**: 端到端验证 QueryEngine 步骤 5.5 自纠错路径
- **前置条件**: `SELF_CORRECTION_LOOP` 开启；Bash 工具执行返回编译错误
- **执行步骤**:
  1. 启动会话，发送要求编译 Java 文件的指令
  2. 模拟 Bash 工具返回编译错误
  3. 验证 QueryEngine 自动注入修复 UserMessage
  4. 验证 `state.correctionAttempts` 递增
- **预期结果**:
  - messages 列表中出现 SelfCorrection 注入的 UserMessage
  - 消息内容为结构化修复指令
  - correctionAttempts 增加到 1
- **验证方式**: 自动（集成测试）

---

#### TC-BTO-01: BashCommandClassifier 动态超时分类

- **用例ID**: TC-BTO-01
- **测试场景**: 验证新增的 `classifyForTimeout` 方法正确分类各类命令
- **前置条件**: 无
- **执行步骤**:
  1. 测试编译命令: `mvn compile` → COMPILATION (300s)
  2. 测试测试命令: `npm test` → TEST_EXECUTION (600s)
  3. 测试安装命令: `pip install requests` → PACKAGE_INSTALL (300s)
  4. 测试 Git 命令: `git status` → GIT_OPERATION (60s)
  5. 测试服务启动: `npm run dev` → SERVER_START (120s)
  6. 测试未分类命令: `echo hello` → 回退到 UI 分类
- **预期结果**: 每个命令返回对应的 CommandCategory，`getRecommendedTimeoutMs()` 值正确
- **验证方式**: 自动（单元测试）

---

#### TC-BTO-07: ToolExecutionPipeline Bash 自动重试

- **用例ID**: TC-BTO-07
- **测试场景**: 验证 Bash 工具网络错误时自动重试（指数退避）
- **前置条件**: RecoveryPolicy 返回 RETRY_SAME 决策
- **执行步骤**:
  1. 模拟 Bash 工具第一次执行返回 "connection refused"（exitCode=1）
  2. 验证 Pipeline 自动延迟 1s 后重试
  3. 模拟第二次执行成功
  4. 验证最终返回成功结果
- **预期结果**:
  - 执行了 2 次 `doExecute`
  - 延迟约 1000ms（首次重试）
  - 最终结果为成功
  - 日志包含 "[Recovery] Bash tool retry #2"
- **验证方式**: 自动（集成测试）

---

#### TC-BTO-08: ToolExecutionPipeline 最大重试限制

- **用例ID**: TC-BTO-08
- **测试场景**: 验证达到 MAX_RETRY_ATTEMPTS(3) 后停止重试
- **前置条件**: 每次执行都返回可重试错误
- **执行步骤**:
  1. 模拟 Bash 工具持续返回 "connection refused"
  2. 观察重试行为
- **预期结果**:
  - 最多重试 3 次（共执行 3 次）
  - 第 3 次后返回带 `[Recovery Hint]` 的错误结果
  - 不会无限重试
- **验证方式**: 自动（单元测试）

---

#### TC-SKL-01: SkillToolValidator 工具白名单验证

- **用例ID**: TC-SKL-01
- **测试场景**: 验证 Skill 只能使用其 frontmatter 声明的允许工具
- **前置条件**: Skill 定义 `allowedTools: [Read, Grep]`
- **执行步骤**:
  1. 验证 `validate(skill, "Read", input)` → allow
  2. 验证 `validate(skill, "Bash", input)` → deny
  3. 验证无白名单 Skill → 允许所有工具
- **预期结果**:
  - Read 允许，Bash 拒绝
  - deny 结果包含原因描述
  - 无白名单时全部允许
- **验证方式**: 自动（单元测试）

---

#### TC-SKL-02: SkillToolValidator 参数安全性（防注入）

- **用例ID**: TC-SKL-02
- **测试场景**: 验证危险参数模式被拦截
- **前置条件**: 无
- **执行步骤**:
  1. 正常参数 `{"path": "/src/main.java"}` → allow
  2. Shell 注入 `{"cmd": "$(rm -rf /)"}` → deny
  3. 管道注入 `{"cmd": "| cat /etc/passwd"}` → deny
  4. 反引号注入 `{"cmd": "\`whoami\`"}` → deny
  5. 超长参数（>2000字符）→ deny
- **预期结果**: 所有危险模式被识别并拒绝，包含安全告警日志
- **验证方式**: 自动（单元测试）

---

#### TC-SKL-03: SkillTokenBudget 单 Skill 预算限制

- **用例ID**: TC-SKL-03
- **测试场景**: 单个 Skill 消耗超过 5000 tokens 时被拒绝
- **前置条件**: 会话已建立
- **执行步骤**:
  1. 记录 skill "translate" 消耗 4500 tokens → `canConsume` 返回 true
  2. 继续请求 1000 tokens → `canConsume` 返回 false（超过 5000 限制）
  3. 验证另一个 skill 不受影响
- **预期结果**:
  - 4500+1000=5500 > 5000，第二次请求被拒绝
  - 日志包含 "would exceed single budget"
  - 不同 skill 预算独立
- **验证方式**: 自动（单元测试）

---

#### TC-BTO-13: BashRecoveryPolicy 重构后错误分类映射

- **用例ID**: TC-BTO-13
- **测试场景**: 验证重构后的 BashRecoveryPolicy 正确映射 BashErrorClassifier 分类
- **前置条件**: BashErrorClassifier bean 可用
- **执行步骤**:
  1. exitCode=127 (命令不存在) → `NON_RETRYABLE` → `reportToLlm`
  2. 网络超时错误 → `RETRYABLE` → `retrySame`
  3. 编译错误 → `NON_RETRYABLE` → `reportToLlm`
  4. 超时 → `TIMEOUT` → `reportToLlm` + "Command timed out"
  5. attemptCount > MAX_ATTEMPTS → `escalateToUser`
- **预期结果**: 每种错误类型映射到正确的 RecoveryDecision
- **验证方式**: 自动（单元测试）

---

### 3.2 P1 测试用例（重要功能，应当通过）

---

#### TC-SCL-05: SelfCorrectionLoop 测试失败检测

- **用例ID**: TC-SCL-05
- **测试场景**: 检测 JUnit/Jest/pytest 测试失败并生成修复指令
- **前置条件**: `SELF_CORRECTION_LOOP` 开启
- **执行步骤**:
  1. 模拟 JUnit 输出: `Tests run: 5, Failures: 2` + `expected:<true> but was:<false>`
  2. 调用 `detectAndPrepareCorrection`
- **预期结果**:
  - `type` 为 `TEST_FAILURE`
  - instruction 包含测试名、期望值和实际值
  - `framework` 标识为 "junit"
- **验证方式**: 自动（单元测试）

---

#### TC-SCL-06: CompileErrorParser 多语言支持

- **用例ID**: TC-SCL-06
- **测试场景**: 同时解析 Java、TypeScript、Python 编译错误
- **前置条件**: 无
- **执行步骤**:
  1. Java: `Main.java:10: error: ';' expected` → 解析成功
  2. TypeScript: `App.tsx(5,10): error TS2304: Cannot find name 'x'` → 解析成功
  3. Python: `File "test.py", line 3\n    SyntaxError: invalid syntax` → 解析成功
  4. 混合输出（同时包含 Java 和 TypeScript 错误）→ 全部正确解析
- **预期结果**: 每种语言的错误都正确提取文件名、行号、错误消息和语言标识
- **验证方式**: 自动（单元测试）

---

#### TC-TOK-01: TokenizerService 精确计数

- **用例ID**: TC-TOK-01
- **测试场景**: 通过 Python tiktoken 服务精确计算 token 数
- **前置条件**: Python 服务启动；`PRECISE_TOKENIZER` 开启
- **执行步骤**:
  1. 调用 `tokenizerService.countExact("Hello, World!", "default")`
  2. 验证返回正整数
  3. 验证比字符估算更精确
- **预期结果**:
  - 返回值 ≥ 0（"Hello, World!" → 约 4 tokens）
  - 与 tiktoken 直接调用结果一致
- **验证方式**: 自动（集成测试，需 Python 服务运行）

---

#### TC-TOK-02: TokenCounter 精确模式降级

- **用例ID**: TC-TOK-02
- **测试场景**: Python 服务不可用时自动降级到字符估算
- **前置条件**: `PRECISE_TOKENIZER` 开启但 Python 服务不可用
- **执行步骤**:
  1. 模拟 TokenizerService.countExact 返回 -1
  2. 调用 `tokenCounter.estimateTokens("some text")`
  3. 验证仍返回合理估算值
- **预期结果**: 返回字符估算结果（text.length() / charsPerToken），不抛异常
- **验证方式**: 自动（单元测试）

---

#### TC-TOK-03: TokenCounter Feature Flag 关闭时保持原逻辑

- **用例ID**: TC-TOK-03
- **测试场景**: `PRECISE_TOKENIZER` 关闭时不调用 Python 服务
- **前置条件**: `PRECISE_TOKENIZER` = false
- **执行步骤**:
  1. 调用 `tokenCounter.estimateTokens("test text")`
  2. 验证未调用 Python 服务
- **预期结果**: 直接使用字符比率估算，行为与变更前一致
- **验证方式**: 自动（单元测试，mock 验证无交互）

---

#### TC-SKL-04: SkillTokenBudget 会话总预算限制

- **用例ID**: TC-SKL-04
- **测试场景**: 会话内所有 Skill 累计超过 25000 tokens 时被拒绝
- **前置条件**: 会话已建立
- **执行步骤**:
  1. Skill A 消耗 4000 tokens (记录)
  2. Skill B 消耗 4000 tokens (记录)
  3. 重复直到累计 24000 tokens
  4. 请求 2000 tokens → 被拒绝（24000+2000=26000 > 25000）
- **预期结果**: 日志包含 "would exceed total budget"，`canConsume` 返回 false
- **验证方式**: 自动（单元测试）

---

#### TC-SKL-09: SkillExecutor 超时层级治理

- **用例ID**: TC-SKL-09
- **测试场景**: 验证 Skill 执行超时为 120s，不超过 Agent 600s 限制
- **前置条件**: SkillExecutor 已注入依赖
- **执行步骤**:
  1. 模拟 Skill 执行耗时 130s
  2. 验证在 120s 时被超时中止
- **预期结果**:
  - 抛出/捕获 TimeoutException
  - 日志包含超时信息
  - 不影响其他工具执行
- **验证方式**: 自动（单元测试，使用 CompletableFuture 超时控制）

---

#### TC-BTO-02: BashTool 动态超时应用

- **用例ID**: TC-BTO-02
- **测试场景**: 验证 BashTool 根据命令分类应用动态超时
- **前置条件**: 无
- **执行步骤**:
  1. 执行 `mvn test` → 验证超时设为 600s
  2. 执行 `git status` → 验证超时设为 60s
  3. 执行 `npm install` → 验证超时设为 300s
- **预期结果**: 日志包含 "Dynamic timeout applied: ... → Nms (category: X)"
- **验证方式**: 自动（单元测试）

---

#### TC-GIT-01: GitDiffTracker 会话变更聚合

- **用例ID**: TC-GIT-01
- **测试场景**: 验证 Git 状态与编辑历史的聚合
- **前置条件**: `GIT_DIFF_TRACKER` 开启；Git 工作目录有变更
- **执行步骤**:
  1. 调用 `getSessionChanges(sessionId)`
  2. 验证返回结果包含 Git tracked 和 session 编辑的文件
- **预期结果**:
  - 返回包含 ADDED/MODIFIED/DELETED 类型的 FileChange 列表
  - Git 追踪的文件和会话内编辑的文件都被包含
  - 不重复计算
- **验证方式**: 自动（集成测试）

---

#### TC-SSR-01: SearchStrategyRouter 策略选择

- **用例ID**: TC-SSR-01
- **测试场景**: 验证根据上下文自动选择 DEFAULT 或 SCOPE_AWARE 策略
- **前置条件**: `SEARCH_STRATEGY_ROUTER` 开启
- **执行步骤**:
  1. context.workingDirectory = null → DEFAULT
  2. context.workingDirectory = "/project" → SCOPE_AWARE
  3. Feature Flag 关闭 → 始终 DEFAULT
- **预期结果**: 策略选择正确
- **验证方式**: 自动（单元测试）

---

#### TC-SSR-02: SearchStrategyRouter 分层搜索

- **用例ID**: TC-SSR-02
- **测试场景**: 验证 SCOPE_AWARE 搜索按优先级分层
- **前置条件**: `SEARCH_STRATEGY_ROUTER` 开启
- **执行步骤**:
  1. 设置 ScopeContext（activeFilePath + recentFiles + gitChangedFiles + workingDirectory）
  2. 调用 `scopeAwareSearch(query, scope)`
  3. 验证结果排序
- **预期结果**:
  - 结果按 relevance 降序排序
  - Layer 1 (local, boost 1.0) 结果排在前面
  - Layer 2 (recent, boost 0.8) 其次
  - Layer 3 (git-changed, boost 0.6) 再次
  - Layer 4 (global, boost 0.4) 最后
  - 总结果不超过 20
- **验证方式**: 自动（集成测试）

---

### 3.3 P2 测试用例（边界条件和异常场景）

---

#### TC-SCL-07: SelfCorrectionLoop 空/null 输入处理

- **用例ID**: TC-SCL-07
- **测试场景**: 各种边界输入的鲁棒性
- **前置条件**: 无
- **执行步骤**:
  1. `detectAndPrepareCorrection(null, 0)` → empty
  2. `detectAndPrepareCorrection("", 0)` → empty
  3. `detectAndPrepareCorrection("   ", 0)` → empty（blank）
  4. `shouldAbort(null, "previous")` → false（错误数 0 ≤ previous）
  5. `shouldAbort("new", null)` → 取决于 new 中是否有错误
- **预期结果**: 不抛异常，返回安全默认值
- **验证方式**: 自动（单元测试）

---

#### TC-SCL-08: SelfCorrectionLoop Token 截断

- **用例ID**: TC-SCL-08
- **测试场景**: 修复指令超过 800 tokens 时自动截断
- **前置条件**: 无
- **执行步骤**:
  1. 构造含 100 个编译错误的工具输出（生成极长指令）
  2. 调用 `detectAndPrepareCorrection`
  3. 验证返回的指令长度
- **预期结果**:
  - 指令被截断
  - 末尾包含 `[truncated due to token limit]`
  - 估算 token 数 ≤ 800
- **验证方式**: 自动（单元测试）

---

#### TC-SCL-09: CompileErrorParser 最大错误数限制

- **用例ID**: TC-SCL-09
- **测试场景**: 输出含超过 5 个错误时只返回前 5 个
- **前置条件**: 无
- **执行步骤**:
  1. 构造含 10 个 Java 编译错误的输出
  2. 调用 `compileErrorParser.parse(output)`
- **预期结果**: 返回列表大小为 5（MAX_ERRORS）
- **验证方式**: 自动（单元测试）

---

#### TC-TOK-04: TokenizerService Python 服务超时

- **用例ID**: TC-TOK-04
- **测试场景**: Python 服务响应超时时的降级行为
- **前置条件**: Python 服务配置超时 5s
- **执行步骤**:
  1. 模拟 Python 服务延迟 10s 响应
  2. 调用 `countExact("text", "default")`
- **预期结果**: 返回 -1（失败标记），不阻塞调用线程超过 5s
- **验证方式**: 自动（单元测试，mock 超时）

---

#### TC-TOK-05: TokenizerService 空文本处理

- **用例ID**: TC-TOK-05
- **测试场景**: 空/null 文本的 token 计数
- **前置条件**: 无
- **执行步骤**:
  1. `countExact(null, "default")` → 0
  2. `countExact("", "default")` → 0
- **预期结果**: 返回 0，不调用 Python 服务
- **验证方式**: 自动（单元测试）

---

#### TC-SKL-05: SkillToolValidator Fork 嵌套深度限制

- **用例ID**: TC-SKL-05
- **测试场景**: Fork 模式 Skill 嵌套深度超过 3 层时被拒绝
- **前置条件**: Skill 定义为 fork 模式
- **执行步骤**:
  1. context.nestingDepth = 2 → allow
  2. context.nestingDepth = 3 → deny
  3. context.nestingDepth = 10 → deny
  4. 非 fork 模式 Skill + nestingDepth = 10 → allow（不检查）
- **预期结果**: fork 模式 Skill 嵌套深度 ≥ 3 时被拒绝
- **验证方式**: 自动（单元测试）

---

#### TC-SKL-06: SkillTokenBudget 会话清理

- **用例ID**: TC-SKL-06
- **测试场景**: 会话结束后预算数据被正确清理
- **前置条件**: 会话已有预算消耗记录
- **执行步骤**:
  1. 记录消耗 3000 tokens
  2. 调用 `clearSession(sessionId)`
  3. 验证 `getStatus(sessionId, skillName)`
- **预期结果**: 清理后状态显示 0 consumed，`canContinue` = true
- **验证方式**: 自动（单元测试）

---

#### TC-SKL-07: SkillTokenBudget 并发安全

- **用例ID**: TC-SKL-07
- **测试场景**: 多线程并发记录消耗时数据一致性
- **前置条件**: 无
- **执行步骤**:
  1. 启动 10 个线程，每个记录 100 tokens
  2. 等待所有线程完成
  3. 验证 `getStatus` 显示总消耗 = 1000
- **预期结果**: 并发不丢数据，总消耗准确为 1000
- **验证方式**: 自动（单元测试，CountDownLatch 同步）

---

#### TC-BTO-09: ToolExecutionPipeline exitCode 解析增强

- **用例ID**: TC-BTO-09
- **测试场景**: 验证从错误内容中解析退出码
- **前置条件**: 无
- **执行步骤**:
  1. metadata 含 exitCode → 优先使用
  2. 内容 "Command timed out after 120000ms" → 返回 137
  3. 内容 "Exit code: 127\ncommand not found" → 返回 127
  4. 内容为空 → 返回 -1
- **预期结果**: 每种格式正确解析
- **验证方式**: 自动（单元测试）

---

#### TC-BTO-10: ToolExecutionPipeline 重试指数退避

- **用例ID**: TC-BTO-10
- **测试场景**: 验证重试延迟遵循指数退避（1s, 2s, 4s）
- **前置条件**: 无
- **执行步骤**:
  1. attemptCount=1 → delay = 1000ms
  2. attemptCount=2 → delay = 2000ms
  3. attemptCount=3 → delay = 4000ms
- **预期结果**: 延迟值为 `1000 * 2^(attempt-1)`
- **验证方式**: 自动（单元测试）

---

#### TC-BTO-14: BashRecoveryPolicy 超限上报用户

- **用例ID**: TC-BTO-14
- **测试场景**: attemptCount 超过 MAX_ATTEMPTS(3) 时上报用户
- **前置条件**: 无
- **执行步骤**:
  1. 设置 RecoveryContext.attemptCount = 4
  2. 调用 `decide(context)`
- **预期结果**: 返回 `escalateToUser`，提示 "Manual intervention required"
- **验证方式**: 自动（单元测试）

---

#### TC-GIT-02: GitDiffTracker Git 命令失败降级

- **用例ID**: TC-GIT-02
- **测试场景**: 不在 Git 仓库中时的降级行为
- **前置条件**: 工作目录非 Git 仓库
- **执行步骤**:
  1. 模拟 `gitService.execGitPublic` 抛出异常
  2. 调用 `getSessionChanges(sessionId)`
- **预期结果**: 不抛异常，返回 FileHistoryService 中的编辑记录（可能为空列表）
- **验证方式**: 自动（单元测试）

---

#### TC-GIT-03: GitDiffTracker 变更摘要生成

- **用例ID**: TC-GIT-03
- **测试场景**: 验证 getChangeSummary 输出格式
- **前置条件**: 有 3 个 ADDED + 2 个 MODIFIED 文件
- **执行步骤**:
  1. 模拟 git status 返回 5 个文件
  2. 调用 `getChangeSummary(sessionId)`
- **预期结果**: 输出包含 "ADDED: 3 file(s) (...)" 和 "MODIFIED: 2 file(s) (...)" 格式
- **验证方式**: 自动（单元测试）

---

#### TC-SSR-03: SearchStrategyRouter 结果不足时全局补充

- **用例ID**: TC-SSR-03
- **测试场景**: 前 3 层搜索结果不足 10 个时触发全局搜索
- **前置条件**: 局部搜索只有 5 个结果
- **执行步骤**:
  1. 模拟 Layer 1~3 共返回 5 个结果
  2. 验证触发 Layer 4 全局搜索
- **预期结果**: 最终结果数量 > 5，包含 source="global" 的结果
- **验证方式**: 自动（单元测试）

---

#### TC-SSR-04: SearchStrategyRouter 去重与排序

- **用例ID**: TC-SSR-04
- **测试场景**: 同一文件在多层搜索中出现时只保留最高 relevance
- **前置条件**: 无
- **执行步骤**:
  1. 模拟同一文件在 Layer 1 (relevance=0.9) 和 Layer 4 (relevance=0.3) 出现
  2. 调用 scopeAwareSearch
- **预期结果**: 最终结果中该文件只出现一次，relevance=0.9
- **验证方式**: 自动（单元测试）

---

## 四、测试执行计划

### 4.1 执行顺序建议

```
Phase 1: 单元测试（可并行，约 5 分钟）
├── TC-SCL-01~03, TC-SCL-07~09    → SelfCorrectionLoop + Parsers
├── TC-BTO-01, TC-BTO-09~10       → BashCommandClassifier + 退出码解析
├── TC-SKL-01~07                    → SkillToolValidator + SkillTokenBudget
├── TC-TOK-02~05                    → TokenCounter + TokenizerService
├── TC-GIT-02~03                    → GitDiffTracker
├── TC-SSR-01, TC-SSR-03~04        → SearchStrategyRouter
└── TC-BTO-13~14                    → BashRecoveryPolicy

Phase 2: 集成测试（需三端启动，约 10 分钟）
├── TC-SCL-04                       → QueryEngine 自纠错集成
├── TC-BTO-07~08                    → ToolExecutionPipeline 重试
├── TC-TOK-01                       → Python Tokenizer 集成
├── TC-BTO-02                       → BashTool 动态超时
├── TC-GIT-01                       → GitDiffTracker 聚合
└── TC-SSR-02                       → SearchStrategyRouter 分层搜索

Phase 3: 回归测试（验证现有功能不受影响）
├── TC-AL-04/05: Agent Loop 工具调用
├── TC-TOOL-04/05: Bash 安全命令执行
├── 模块 9: 技能系统 (7 用例)
└── 所有 Feature Flag 默认关闭状态下的功能正确性
```

### 4.2 环境要求

| 环境组件 | 要求 | 用途 |
|----------|------|------|
| Java 21+ | Amazon Corretto 21.0.10 | 后端编译和测试运行 |
| Maven 3.9+ | `./mvnw` 内置 | 构建和单元测试 |
| Node.js 22+ | v22.14.0 | 前端构建 |
| Python 3.11+ | 3.11.15 + tiktoken | TokenizerService 集成测试 |
| Spring Boot | 启动状态，端口 8080 | 集成测试 |
| Python FastAPI | 启动状态，端口 8000 | Tokenizer 路由集成测试 |
| Git 2.x | 工作目录为有效仓库 | GitDiffTracker 测试 |

### 4.3 依赖关系

```
TC-SCL-04 依赖 → TC-SCL-01 (SelfCorrectionLoop 核心逻辑通过)
TC-BTO-07 依赖 → TC-BTO-13 (BashRecoveryPolicy 分类正确)
TC-BTO-02 依赖 → TC-BTO-01 (BashCommandClassifier 分类正确)
TC-TOK-01 依赖 → Python 服务启动 + tiktoken 安装
TC-GIT-01 依赖 → Git 仓库存在且有变更文件
TC-SSR-02 依赖 → TC-SSR-01 (策略选择正确)
所有集成测试 依赖 → 三端服务启动成功 (TC-1.1~TC-1.7)
```

### 4.4 Feature Flag 测试矩阵

| Feature Flag | 默认值 | 测试覆盖 |
|---|---|---|
| `SELF_CORRECTION_LOOP` | false | TC-SCL-01~08（开启时）+ 回归（关闭时不影响） |
| `PRECISE_TOKENIZER` | false | TC-TOK-01~05（开启时）+ TC-TOK-03（关闭时） |
| `GIT_DIFF_TRACKER` | false | TC-GIT-01~03（开启时） |
| `SEARCH_STRATEGY_ROUTER` | false | TC-SSR-01~04（开启时）+ TC-SSR-01（关闭时） |

---

## 五、测试用例统计

| 优先级 | 用例数 | 覆盖模块 |
|--------|--------|----------|
| P0（必须通过） | 11 | SelfCorrectionLoop(4), BashTimeout(3), Skill 安全(3), BashRecovery(1) |
| P1（应当通过） | 10 | Tokenizer(3), SkillBudget(2), SkillExecutor(1), GitDiff(1), Search(2), BashTool(1) |
| P2（边界异常） | 12 | SelfCorrection 边界(3), Tokenizer 异常(2), Skill 并发(3), Pipeline 解析(2), Git 降级(1), Search 去重(1) |
| **合计** | **33** | 6 大功能模块全覆盖 |
