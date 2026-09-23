# 各报告可确认的评测条件

这些是冻结报告中的自述，不是本轮独立认证的执行记录。17份均围绕同一会话合并v2改动与规格；现有材料不足以证明每次实际输入文件哈希、完整提示词、上下文、token/时间预算、模型服务版本和工具权限相同。当前冻结的代码不反向证明历史每次审查读取了完全相同的工作树。

方法：通读原报告，以下只摘取范围/方法/验证声明便于核对；字数、并行代理数、测试数量都不加分。原文所写测试“实跑”仍标自述，本轮实际复跑另见 reproduction-results.md。

## R01

[原报告](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md)；SHA-256 `da3f3f4b07a740b7a81029880b55dcc77019f9b57abd36ec58d2cf8910d08123`。

原文 L3：

````text
审查日期：2026-09-22。基线：`b98e18721169436f8f35373a4120c530f54310d5`。规范依据：[session-merge-architecture-v2.md](/Users/guoqingtao/Desktop/dev/code/zhikuncode/docs/session-merge-architecture-v2.md)，优先采用其第 1 节最新收缩范围，不重新要求普通子任务全量落库，也不要求实现通用附件解析平台。
````

原文 L103：

````text
| 授权 | 内置实例身份匹配、可信根会话绑定、分析/执行前复核；同名 MCP/动态工具单测覆盖 | 未发现泛化只读放行或普通文件权限变宽 |
````

原文 L112：

````text
已实现的关键点包括：V026 保持旧迁移不变并重建 CHECK；数据库唯一 active_slot；运行 epoch 抵御旧 worker；独立 snapshot 与原文哈希；串行提取及单元落库；去掉 16 次总调用和旧 3,500 拒绝规则；分层摘要保留详情；取消后等待 writer 再清包；发布与目标创建处于同一事务；通过 target 存在性撤销绑定；只读工具按 ref 读取、限量、游标、哈希和 symlink 检查。实现职责大体仍在文档指定的服务中，没有扩展成额外工作流平台。
````

原文 L127：

````text
| 前端全量 Vitest | 103 文件通过，876 通过，16 跳过 | 2 个 worker；排除文件使用基线版本 |
````

原文 L132：

````text
| 真实模型接续开发 | 未执行 | 不以 stub 测试替代；未主动调用付费模型 |
````

原文 L133：

````text
| 浏览器 E2E、Linux/Docker、量化行/分支覆盖率 | 本次未执行 | 不把本机编译与单元测试称为完整发布环境验证 |
````

原文 L135：

````text
后端命令为 `./mvnw -Dspring.profiles.active=test '-Dtest=*,!MergeReviewReproTest' test`。该选择器除默认命名用例，还选中了 AiCodingTestSuite 的 15 个本地用例以及 2 个被跳过的 live IT，因此比文档中的 2,975 多 17 项，不是原工作区混入了其他功能改动。额外复现单独运行并单独报告，未混入绿色基线计数。首轮后端执行在 1,282 项时被 SIGTERM/退出码 143 中断，当时 0 断言失败；未将其当作产品缺陷，后续完整重跑通过。
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R02

[原报告](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)；SHA-256 `a0372bb6fc5951c7c4539a98cb19051881f45dc205a5e8d6f527fea75e30c281`。

原文 L7：

````text
| 分支 / 基线 | `main` @ `b98e187`（工作区未提交改动） |
````

原文 L11：

````text
| 审查方式 | 全量 diff 阅读 + 规范逐条比对 + 本机实跑回归 + 干净基线对照（非仅采信文档结论） |
````

原文 L19：

````text
| 是否破坏非合并分支？ | **未发现破坏。** 静默隔离 + 全量回归 + 基线对照三重证据一致（详见第二节） |
````

原文 L50：

````text
| **基线对照** | 在 `b98e187` 干净 worktree 上单跑上述两个失败类 | **同样的 5 个失败，行号完全一致** |
````

原文 L52：

````text
5 个失败全部位于 `WorkspaceFileBoundaryTest`（1 个）与 `ManagedProcessRunnerTest`（4 个），与本次改动无任何文件交集；**基线同样失败，属环境相关（疑似沙箱对进程/受保护文件操作的限制），不是本次回归**。这直接回答了审查重点 1：**非合并分支的功能链路没有被破坏**。
````

原文 L143：

````text
| 普通 / fork / Swarm 隔离 | ⚠️ | 有 `verifyNoInteractions(handoff)` 强断言；但没有"执行和保存代码与基线一致"的守护测试（靠人工比对） |
````

原文 L152：

````text
| UI 与控制 | ⚠️ | active 恢复、真实锁集合已覆盖；**protocol=1 只读展示无实现分支也无测试**（目前仅靠后端 `canResume/canCancel=false` 间接只读）；**stale epoch 409 后刷新状态**、**取消期间有轮询在飞**两条前端路径无测试 |
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R03

[原报告](../KimiCode/KimiK3-session-merge-v2-review.md)；SHA-256 `585b3e23e1aaf2590202d8332abb2bb151ca12c6cd84cff20970b175058237a4`。

原文 L3：

````text
审查日期：2026-09-22 ｜ 基线：HEAD `b98e187` ｜ 规范：`docs/session-merge-architecture-v2.md`（下称"规范"，引用 §号）
````

原文 L29：

````text
   - `AuthorizationService.java:158` 仅将 `handoff-read-v1` 且 `RiskClass.SAFE` 且 effects 恰为 `[READ_RESOURCE]` 的操作纳入只读放行，**未加入 SAFE_INTERNAL**（§7.3 红线）；`OperationAnalyzerRegistry.java:87` 按 `instanceof` 分派，同名 MCP/动态工具分别走 `isMcp()`/`generic` 分支，不能继承权限（测试 `OperationAnalyzerRegistryTest.java:45-66` 佐证）。
````

原文 L64：

````text
- §7.2 游标绑定包版本+参数、16KiB/8MiB 上限、15 秒截止与中断检查、UTF-8 边界分页、跨分片重叠去重、长行尾部不漏、路径穿越/symlink 防御、PLAN 模式只读——逐项核实通过且有测试；
````

原文 L116：

````text
| 规范自述的遗留验收 | ⚠️ §9 末段**真实模型接续开发语义验收尚未执行**（文档第 3、471 行均明示），stub 测试不能替代；规范允许阶段分提交，但"整体闭合前不宣称完成" |
````

原文 L124：

````text
5. 提交时包含 `docs/session-merge-architecture-v2.md` 本身；提交信息中如实标注"真实模型语义验收未执行"。
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R04

[原报告](../Qoder/kimik3-session-merge-v2-review.md)；SHA-256 `f767de7a794857168ec2b79d6012cfa9e9d560e436396da7eb90671355d176f5`。

原文 L4：

````text
- 审查基线：工作区全部本地改动（基线提交 `b98e187`），对照架构规范 `docs/session-merge-architecture-v2.md`
````

原文 L16：

````text
说明：文档声称的隔离副本全量验证（后端 2,975 项、前端 103 文件 876 项）本次未完整重跑，以上为针对合并链路的定向验证。
````

原文 L26：

````text
- **授权**：`handoff-read-v1` 的 analyzerId 只能由 `instanceof HandoffReadTool` 的真实内置工具获得（`OperationAnalyzerRegistry.java:87`）——同名 MCP 工具先被 `isMcp()` 拦截，同名动态工具落入 generic，不继承只读放行；分析（analyze）与执行前（recheck）双重校验绑定，绑定变化抛 `HANDOFF_BINDING_CHANGED`，符合 7.3 节。`AuthorizationService` 只对 analyzerId 为 `handoff-read-v1` 且 READ/SAFE 的操作自动放行，未简单加入 SAFE_INTERNAL。
````

原文 L67：

````text
- **真实模型接续开发语义验收未执行**——文档明确 stub 结构测试不能替代语义评测，第 9 节的人工标注样本（含 A 改服务端字段、B 前端未联调的接续开发样本及"封存后共享代码又变化"变体）仍需另行执行。
````

原文 L71：

````text
**已达到提交 GitHub 的标准**：非合并链路隔离干净且有测试证明（普通请求零交接调用、预算等价、原生 tool ID 不串来源）；合并链路严格按 v2 架构落地（封存即释放、持久进度、epoch fencing、原子发布、有界入口、只读工具授权）；定向结构测试与前端构建全绿；无禁用测试或放宽断言。
````

原文 L75：

````text
1. 提交信息中保留"真实模型语义验收未执行"的声明（文档第 9 节强制要求，不得以 stub 结果冒充语义验收）；
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R05

[原报告](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)；SHA-256 `250303732c1353f9661bd5e171d9446eb0c09f4a0e76ba85749c054e10878699`。

原文 L4：

````text
- 仓库：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`（分支 `main`，基线 `b98e187`，改动全部处于未提交状态）
````

原文 L18：

````text
| 基线文件是否被改动 | `SessionManager`、`SessionMessagePersistence`、`SessionExecutionGate`、`SubAgentExecutor`、`SwarmWorkerRunner`、`ToolRegistry`、`CheckpointService`、`V024_CreateSessionMerges` — `git diff` **均为空**，符合规范第 511 行 |
````

原文 L76：

````text
仅把 `handoff-read-v1` 加入 SAFE 只读自动放行条件（:158）。普通工具的 `analyzerId` 永不为该值 → 零影响。符合规范第 398 行"不能简单加入 SAFE_INTERNAL"（此处是按 analyzerId 而非工具名判定，未污染 SAFE_INTERNAL 集合）。
````

原文 L80：

````text
- `analyzerFor` 先判 `tool.isMcp()`（:86）再判 `tool instanceof HandoffReadTool`（:87）。同名 MCP 工具走 `mcp` 分析器，同名动态工具走 `generic` → GUARDED/UNKNOWN → **仍需交互授权，不会继承只读放行**。已由 `OperationAnalyzerRegistryTest:44-66` 覆盖，符合规范第 398 行。
````

原文 L97：

````text
#### 1.7 基线隔离（规范第 511 行）
````

原文 L172：

````text
文档第 471 行"真实模型的人工语义样本尚未执行"**至今仍成立**。因此本次改动只能表述为"结构、数据隔离与接线已验证"，**不得表述为接续开发能力已验收**——这也正是文档第 9 行、第 455 行反复自我约束的口径，提交信息不能比文档更乐观。
````

原文 L185：

````text
| P2-6 | `sessionMergeStore.ts:16-19` | **DTO 与规范不符且零运行时校验**。规范第 253-268 行要求**必填**的 `protocolVersion`/`runEpoch`/`snapshotSealed`/`lockedSourceSessionIds`/`progress`/`canResume`/`canCancel`/`targetAvailable` 全部被声明为可选（`?`）；`protocolVersion?: number` 而非 `1 \| 2` 联合类型；:185-187 只校验 `operationId` 与 `request`，`status` 联合、`progress` 形状、数组性均未校验。`protocolVersion` 在 `SessionMergePanel.tsx` 中**从未被引用** → 规范第 427 行"protocol=1 只读展示旧结果，不伪造 v2 绑定/恢复能力"完全依赖服务端 `canResume`/`canCancel` 标志，无客户端纵深防御。 |
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R06

[原报告](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md)；SHA-256 `e7de7dfa002b076df84aa57e83778109bfea22e6df0ed17acd6bbde57124aa15`。

原文 L5：

````text
- 审查方式：Leader 调度代码审查员 Mark（CodeReview 子代理）完成全量只读审查，Leader 汇总输出
````

原文 L7：

````text
- 审查时间线：首轮报告 10:40:42（仅 correctness 视角，未达要求）→ 补全完整报告 10:57:16
````

原文 L8：

````text
- 约束：全程只读，未修改任何代码文件，未执行 git commit / push，未启动服务，未运行重型 e2e 套件
````

原文 L95：

````text
| 只读已有持久化历史，不修改普通执行与保存机制 | 第 1 节、第 10 节 | 符合 | `SessionManager`、`SubAgentExecutor`、`SwarmWorkerRunner` 均未出现在改动中；合并逻辑集中于 `SessionMergeService`、`MergePackageService` |
````

原文 L104：

````text
| HandoffRead 为内置只读工具，不注册为全局 Spring Bean，遵循授权框架 | 7.x | 符合 | `HandoffContextServiceTest` 断言 `HandoffReadTool` 无 `@Component`；`OperationAnalyzerRegistry` 专用 `handoff-read-v1` analyzer；binding 变更时 recheck 抛 `HANDOFF_BINDING_CHANGED`；`AuthorizationService` 将 `handoff-read-v1` 纳入 SAFE 读自动放行 |
````

原文 L119：

````text
| `HandoffContextService.configure / project / inject / canReadAsset` | `QueryEngine`（历史预算 Step1、API 消息准备 Step3、备用预算）、`QueryController`、`WebSocketController` | `handoffOperationId == null` 时返回空投影、预留 0 token；两条入口均以 `isMerged` 守卫 | 普通会话历史预算与 HEAD 基线一致 |
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R07

[原报告](../QoderIDE/Sonus-session-merge-v2-review.md)；SHA-256 `b1447fe74ca2db8dc2fcee8288ce5ade4f36355df1d09cf6c0fa20d3e8931160`。

原文 L12：

````text
- **本轮为只读静态审查，没有运行测试、构建或端到端验收。** 因而既不能宣称测试通过，也不能宣称这些尚未执行的检查失败。
````

原文 L18：

````text
审查基线：HEAD `b98e18721169436f8f35373a4120c530f54310d5` 与当前工作树。
````

原文 L257：

````text
| 7.2/7.3：可信根绑定、只读工具、不继承同名权限 | 静态符合 | 按真实工具类型分派，分析及 recheck 校验绑定，没有发现普通同名工具自动取得权限的路径。 |
````

原文 L386：

````text
前提：在已经准备好的隔离副本执行，使用 Java 21 及测试配置；确认数据库、资料目录与运行库完全隔离。
````

原文 L388：

````text
在隔离副本的 `backend` 目录：
````

原文 L402：

````text
在隔离副本的 `frontend` 目录：
````

原文 L411：

````text
完成定向回归后，再分别执行隔离副本全量验证：
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R08

[原报告](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)；SHA-256 `ca237f8395644f08227bc9a1862a85faaca5e8cec62592b828e485a46a1d6b4d`。

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R09

[原报告](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)；SHA-256 `97319de114582ea625bdb44aa1f071707ebd0610ddde7c87d30a8b26db2cdad4`。

原文 L16：

````text
**意图**：实现会话合并 v2 协议——将 2~5 个来源会话经"封存快照 → 结构化分片提取/聚合 → 校验 → 原子发布"合并为新会话 E；封存即释放来源；持久化单元进度与 run_epoch 围栏保证恢复/取消安全；E 通过交接摘要 + 只读 HandoffRead 工具按需读取原文；普通请求链路零合并开销。
````

原文 L74：

````text
| 基线文件 | 规范第 10 节要求"保持不改"的 SessionManager、SessionMessagePersistence、SubAgentExecutor、SwarmWorkerRunner、SessionExecutionGate、ToolRegistry 均不在改动列表（git status 核实） |
````

原文 L124：

````text
1. **真实模型语义验收尚未执行**（文档自述缺口）——摘要/提取/聚合的模型调用在测试中均为 mock，`MergeSummaryService.call()` 的真实行为（含 4183 字节分片回归）未经真实模型验证；
````

原文 L133：

````text
| 非合并分支影响 | **无负面影响或破坏**，隔离证据链完整（零调用隔离 + 入口门控 + 工具池隔离 + 迁移无损 + 基线文件零改动） |
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R10

[原报告](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)；SHA-256 `80b2385e95077d3dba320b0f465b3992cc0ff116eb707ebca63f5d12069b92d9`。

原文 L3：

````text
> 审查模型：Kimi-K3 · 审查日期：2026-09-22 · 基线：`b98e187`（本地未提交改动）
````

原文 L16：

````text
**改动意图**：将会话合并从"一次性整包摘要"重构为 v2 架构——独立快照封存 → 串行分片提取/有界聚合 → 校验 → 原子发布，并新增持久进度、暂停/恢复/取消、运行时 HandoffRead 只读工具与每轮交接上下文入口。本地统计：约 +2565/-1058 行。
````

原文 L90：

````text
文档第 9 节的**真实模型语义验收**（接续开发样本评测）尚未执行——这是文档自述的边界，不阻塞代码提交，但发布说明中不应把 stub 测试描述为语义验收通过。
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R11

[原报告](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)；SHA-256 `b9c4f84bf6b49de954b4a6623738eb19d0c4c81be942d4a5842a1215e2c1ef46`。

原文 L8：

````text
- 审查方式：对照架构规范阅读 diff 与新增文件；未重跑后端 2,975 项与前端 876 项全量测试
````

原文 L78：

````text
- 文件读取的放行条件只多了分析器 `handoff-read-v1`。同名的动态工具或 MCP 工具不会走这条只读策略；PLAN 模式下也只有真正的内置 `HandoffRead` 能读交接包。`file-v1` 的原有判断没有放宽。
````

原文 L101：

````text
- `HandoffRead` 只读本包登记文件，拒绝路径穿越和符号链接。搜索使用字面匹配，未扫完时 `complete=false` 并给出游标。
````

原文 L167：

````text
定向测试覆盖了迁移、唯一名额、封存后释放来源、损坏 checkpoint、长历史超过 16 个单元、413 后只拆失败单元、哈希被改后的单元不重付费、游标/跨包/符号链接、PLAN 下的交接只读，以及无标记请求零交接调用。这些和文档第 9 节的结构用例是对齐的。
````

原文 L196：

````text
| 合并主链路是否按 v2 接上 | 已接上。封存、进度、恢复/取消、只读工具、每轮入口和前端状态都在。 |
````

原文 L197：

````text
| 是否达到文档定义的完成/发布标准 | 没有。第 9 节真实模型语义样本未执行；聚合换模型、部分阻断恢复和极小窗口入口仍有 P2 缺陷。 |
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R12

[原报告](../zhikuncode/GLM5.3-session-merge-v2-code-review.md)；SHA-256 `4bede0a829bed115a205f8200a8e0a27d9bcdafa11c223c2885064b8b3b207ca`。

原文 L5：

````text
> **审查基线**：`main` 分支 `b98e187` + 本地未提交改动（49 项）
````

原文 L7：

````text
> **审查方式**：4 个并行深度审查（共享链路影响分析 / 合并核心模块对照 / 前端审查 / 独立测试验证），全部只读，未修改任何源码
````

原文 L42：

````text
| **发布标准** | ⚠️ **工程层面达标，无严重缺陷；但有 6 个中等问题建议发布前处理**，且文档第 9 节自认的「真实模型语义验收」尚未执行 |
````

原文 L44：

````text
一句话总结：**本次改动没有对非合并分支（普通聊天 / fork / Swarm 子代理）造成任何可证实的影响，达到可提交 GitHub 的工程标准；建议先修复 2 个改动量小但用户可见的中等问题，并在发布说明中如实标注语义验收未执行。**
````

原文 L211：

````text
7. 面板 paused UI（暂停原因、retryAt、totalFinal 文案、按钮禁用）；targetAvailable=false 的禁用与提示；protocol=1 只读展示；cancel 幂等/MERGE_ALREADY_COMPLETED
````

原文 L234：

````text
文档自身声明「**真实模型接续开发语义验收尚未执行**」——第 9 节要求的 A/B 合并为 E 后完成联调的语义样本（含「封存后共享代码又有变化」变体）均未跑过。当前所有验证均为 stub provider 层面。如果发布标准包含该语义验收，这一项尚未满足；这是文档自认的边界而非代码缺陷，但不应在发布说明中省略。
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R13

[原报告](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md)；SHA-256 `35d16f03a382c06db0fd2ef8e90eb482fe23e43fffe96973e0fb7ee9eed9d111`。

原文 L4：

````text
> **架构基线**：`docs/session-merge-architecture-v2.md`（本次改动新增，更新日期 2026-09-22，问题核对基线 `b98e187`）
````

原文 L5：

````text
> **审查方式**：6 个并行审查员分模块深挖 + 协调者对关键结论独立复核 + 实跑编译与全量测试
````

原文 L6：

````text
> **审查性质**：只读审查，未修改任何源代码/测试代码/配置文件
````

原文 L37：

````text
### 1.2 审查方法
````

原文 L67：

````text
| **1) 是否对非合并分支造成功能链路的负面影响或破坏？** | **未造成任何影响。** 9 条普通链路逐条验证与基线等价；规格第 10 节"保持不改"清单经 `git diff` 证实**零改动**；全量后端测试的 11 个失败在**不含本次改动的 HEAD 干净基线上逐一同名复现** → 本次改动引入的新增失败 = **0**。 |
````

原文 L156：

````text
| 文件 | 基线 | 最终 | 判定 |
````

原文 L249：

````text
#### B5 规格第 9 节强制的真实模型语义验收未执行
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R14

[原报告](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md)；SHA-256 `831475e790246748d8c79b8f1a5dd8980dd82c2f64cce02dba2aff6df6c197ed`。

原文 L8：

````text
- **审查方法**：四路并行独立审查（① 后端合并核心服务；② 后端运行时集成/工具/授权/控制器隔离性；③ 前端 store/面板；④ 独立测试执行验证），全部结论以代码证据与实测为准，不采信文档自述声明；关键 Major 发现经 coordinator 亲自抽验代码复核。
````

原文 L18：

````text
**结论：无实际风险，未发现任何破坏。** 本次改动是强隔离型设计：普通会话在入口、预算、工具池、图片注入、授权五个层面均走基线分支，并有针对性单测（含"零交接调用"证明）与 HEAD 基线对照实验佐证（详见第三节）。
````

原文 L22：

````text
**结论：代码质量与工程完备度整体达到"可安全提交"水平（0 Blocker、全量测试实证通过、无数据完整性/安全漏洞），但距"放心合并主干/对外发布"还差 4 条 Major 级问题的处理**——其中 2 条是后端功能缺陷（用户可能永久卡死、恢复协议部分不可达），2 条是前端规格强制验收项的测试缺口。规格文档自身亦声明"真实模型接续开发语义验收尚未执行"，该部分不能由本次 stub 测试替代。
````

原文 L26：

````text
| 非合并链路安全性 | ✅ 通过 | 隔离点逐 hunk 核对 + 基线对照实验，无回归 |
````

原文 L32：

````text
| 功能语义验收 | ⛔ 未执行 | 文档明示：真实模型接续开发语义样本评测尚未运行，不可用 stub 结果替代 |
````

原文 L56：

````text
3. 独立执行验证：在**原样工作区**上真实运行后端定向批次、后端全量、前端全量与生产构建；对失败项做隔离重跑 + `env -u`/补 ripgrep 对照实验，并在 `git archive b98e187` 的干净 HEAD 副本上复现基线；
````

原文 L62：

````text
- 审查与测试执行前后 `git status --porcelain` 均为 49 项，未改/未增/未减任何被跟踪文件，未执行 git 提交。
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R15

[原报告](../zhikuncode/kimik3_session-merge-v2-code-review-report.md)；SHA-256 `f1d163db6693c8f294307ad05901e15ba81bcf31383e4b5b8bcbdad79fc1bbea`。

原文 L4：

````text
- **代码基线**：main @ b98e187 + 全部未提交改动
````

原文 L6：

````text
- **审查方式**：4 路并行深度审查（架构符合性 / 非合并链路回归 / 前端质量 / 构建测试实测验证）+ 干净 HEAD 对照实验
````

原文 L80：

````text
- **J. HandoffRead 只读工具**：不注册全局 Bean；固定 4 action、模型不能选择 session/operation；统一 entries/complete/nextCursor/warning；16 KiB 包装上限、8 MiB 搜索页预算、15s 截止+中断检查；游标绑定包版本+action/query/过滤；UTF-8 边界分页、跨缓冲重叠去重不漏长行尾部；search 字面匹配支持中文、不调 shell、不受 .gitignore 影响。
````

原文 L81：

````text
- **K. 授权**：`handoff-read-v1` 真实内置分析器+core tool 登记；分析/执行前均从可信根会话解析绑定并校验 ref/realpath/包哈希/归属；仅该绑定 READ/SAFE 适用只读策略、不入 SAFE_INTERNAL；同名 MCP/动态工具不继承权限；执行时用 rootSessionId 校验绑定。
````

原文 L99：

````text
| D-3 | 无"全部来源统一短只读事务"记录截止边界；实际隔离靠 gate 排他 token | 等价安全性成立；仅外部直接篡改库（文档声明不支持）才有窗口 |
````

原文 L168：

````text
文档 §9 的 13 个必测用例族基本有自动化对应（含 4,183 bytes + 13,370 output 的本次失败条件合成回归）。文档自承"真实模型语义验收尚未执行"——审查结论同样只覆盖结构/接线/预算/持久化正确性，**不覆盖真实模型的语义质量**。
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R16

[原报告](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md)；SHA-256 `6af33f49e42cd089c74b9eb72cd3c20a8b3eab87e52d7dbbbb169e32f3aca6ff`。

原文 L78：

````text
| fork / Swarm 保存机制 | **否** | 相关类零 diff；合并侧只读已有 checkpoint/消息，缺口显式记入 gaps |
````

原文 L111：

````text
- 无标记：`reservedTokens=0`，`inject` 返回原列表 → 预算与历史装配与基线一致
````

原文 L153：

````text
| 不改 SessionManager 等基线 | 零 diff | 符合 |
````

原文 L263：

````text
4. **真实模型接续开发语义验收未执行**（规格首段/第 9 节明示为未完成项，不得用 stub 冒充）
````

原文 L272：

````text
> 说明：规格第 7 节曾记录隔离副本上后端全量 2,975 项（2,905 通过 / 70 跳过 / 0 失败）与前端全量 103 文件（876 通过 / 16 跳过）；本轮审查未重复全量，以定向结果与代码证据为准。
````

原文 L284：

````text
| **必须在提交信息/PR 中如实声明** | 「真实模型接续开发语义验收尚未执行」；stub 测试不得表述为语义验收闭环 |
````

原文 L292：

````text
| 授权（7.3） | 合规：`handoff-read-v1` 分析器 + 执行前范围复核 + PLAN 只读 + 不进 SAFE_INTERNAL + 同名工具不继承 |
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。

## R17

[原报告](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)；SHA-256 `8efaf680b65190f81079e30e4cc2f8b6961cec22473417153f52bbc9e04ea156`。

原文 L5：

````text
- **审查基线**:`b98e187` 工作树未提交改动,对照 `docs/session-merge-architecture-v2.md`(620 行规格)
````

原文 L38：

````text
1. **基线文件零改动**。`git status` 确认 `SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner`、`SessionExecutionGate`、`ToolRegistry`、`FileRead/Grep`、`ImageResultExternalizer`、`SessionController` 均未修改,与规格第 10 节"保持不改"清单及文档声明一致。
````

原文 L94：

````text
第 9 节要求的**真实模型接续开发语义样本尚未执行**(displayName 联调样本、封存后代码变化样本等)。当前全部通过的是 stub 数据/隔离/接线断言,按文档要求不得将其表述为"真实模型接续验收已通过"。
````

原文 L104：

````text
1. 真实模型语义验收未执行;
````

未能独立确认：完整初始提示词一致、全过程上下文一致、预算一致、底层模型身份、全程权限与并行策略一致。
