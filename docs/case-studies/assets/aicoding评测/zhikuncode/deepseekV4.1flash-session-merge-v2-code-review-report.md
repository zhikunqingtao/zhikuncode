# 会话合并 v2 改动全面审查报告

- **生成者**：DeepSeek V4.1 Flash（Coordinator 模式，四路独立子代理交叉审查 + 本人抽验）
- **审查日期**：2026-09-22
- **仓库**：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`（分支 `main`，HEAD=`b98e187`）
- **审查对象**：工作区全部未提交本地改动（共 49 项：36 个已跟踪文件修改 + 13 个未跟踪新文件）
- **基准规格**：`docs/session-merge-architecture-v2.md`（会话合并 v2：最小必要改造规格，620 行）
- **审查方法**：四路并行独立审查（① 后端合并核心服务；② 后端运行时集成/工具/授权/控制器隔离性；③ 前端 store/面板；④ 独立测试执行验证），全部结论以代码证据与实测为准，不采信文档自述声明；关键 Major 发现经 coordinator 亲自抽验代码复核。

**按用户要求忽略的文件（未审查）**：ImageBlock.tsx / ImageBlock.test.tsx / messageContent.ts / messageContent.test.ts / MessageActions.tsx / MessageActions.test.tsx / e2e/message-copy-all.spec.ts / sessionStatusMeta.ts / SessionStatusCapsule.tsx / Header.tsx / PermissionMenu.tsx / PromptInput/index.tsx / Sidebar.tsx / globals.css / liquid-glass.css / Sidebar.desktop.test.tsx（这些属于与合并功能无关的独立任务改动）。

---

## 一、总体结论（TL;DR）

### Q1：本次改动是否会对非合并分支（普通会话）的功能链路造成负面影响或破坏？

**结论：无实际风险，未发现任何破坏。** 本次改动是强隔离型设计：普通会话在入口、预算、工具池、图片注入、授权五个层面均走基线分支，并有针对性单测（含"零交接调用"证明）与 HEAD 基线对照实验佐证（详见第三节）。

### Q2：代码质量、测试覆盖及规范性是否达到提交至 GitHub 的发布标准？

**结论：代码质量与工程完备度整体达到"可安全提交"水平（0 Blocker、全量测试实证通过、无数据完整性/安全漏洞），但距"放心合并主干/对外发布"还差 4 条 Major 级问题的处理**——其中 2 条是后端功能缺陷（用户可能永久卡死、恢复协议部分不可达），2 条是前端规格强制验收项的测试缺口。规格文档自身亦声明"真实模型接续开发语义验收尚未执行"，该部分不能由本次 stub 测试替代。

| 维度 | 判定 | 说明 |
|---|---|---|
| 非合并链路安全性 | ✅ 通过 | 隔离点逐 hunk 核对 + 基线对照实验，无回归 |
| 编译 / 全量测试 / 构建 | ✅ 通过 | 干净环境下后端 2,975 项 0 失败、前端 103 文件全过、生产构建成功（独立实测复现参考值） |
| 数据安全 / 事务 / 安全防护 | ✅ 通过 | 无 SQL 注入、无事务边界违规、路径穿越/symlink 防护有效、无锁泄漏、无数据丢失路径 |
| 数据库迁移（V026） | ✅ 通过 | 与规格附录 A SQL 逐字一致、事务化执行、旧行逐列保留、V024 checksum 未动 |
| 规格符合性 | 🟡 高但非完全 | 核心流程全面符合；2 条 Major 涉及状态机语义与恢复协议偏差 |
| 测试覆盖 | 🟡 接近 | 后端扎实（断言具体、无放水）；前端有 2 个规格"必须证明"项零覆盖 |
| 功能语义验收 | ⛔ 未执行 | 文档明示：真实模型接续开发语义样本评测尚未运行，不可用 stub 结果替代 |

**发现统计**：Blocker **0**；Major **4**（后端 2 + 前端 2）；Minor **21**（后端 14 + 前端 7）；Nit **23**（后端 15 + 前端 8）。

---

## 二、审查范围与方法（证据链）

### 2.1 审查范围

| 层 | 文件 |
|---|---|
| 后端·合并核心（改） | `session/merge/SessionMergeService.java`(+360) / `MergePackageService.java`(+597) / `MergeSummaryService.java`(517) / `MergeTextBudget.java`(+7) |
| 后端·合并核心（新） | `session/merge/MergeProgressRepository.java` / `MergeHandoffData.java` / `config/database/V026_ExtendSessionMerges.java` |
| 后端·集成（改） | `engine/QueryEngine.java`(+32) / `engine/QueryLoopState.java`(+7) / `engine/ImageRefInjector.java`(+16) / `controller/QueryController.java`(+8) / `controller/SessionMergeController.java`(+19) / `websocket/WebSocketController.java`(+19) / `authorization/AuthorizationService.java`(2) / `authorization/OperationAnalyzerRegistry.java`(+19) |
| 后端·集成（新） | `engine/HandoffContextService.java` / `session/merge/HandoffReadService.java` / `tool/impl/HandoffReadTool.java` |
| 后端·测试 | `SessionMergeServiceTest` / `MergePackageServiceTest` / `MergeSummaryServiceTest` / `MergeFixture` / `QueryEngineUnitTest` / `OperationAnalyzerRegistryTest` / `AuthorizationServiceProjectFileScopeTest`（改）；`V026ExtendSessionMergesTest` / `MergeProgressRepositoryTest` / `HandoffContextServiceTest` / `HandoffReadServiceTest`（新） |
| 前端 | `src/store/sessionMergeStore.ts`(+117) / `src/components/session/SessionMergePanel.tsx`(+32) 及两个测试文件 |
| 文档 | `docs/session-merge-architecture-v2.md`（新增规格，620 行） |

### 2.2 方法

1. 逐条阅读规格全文（§3–§10 与附录 A），逐 hunk `git diff HEAD` 核对实现，`git show HEAD:<file>` 对比旧版；
2. 新增文件全文阅读；交叉验证被依赖的既有契约（SessionExecutionGate、MigrationRunner、V024.validate、V017 artifact 表、TokenCounter、AbortContext 等）；
3. 独立执行验证：在**原样工作区**上真实运行后端定向批次、后端全量、前端全量与生产构建；对失败项做隔离重跑 + `env -u`/补 ripgrep 对照实验，并在 `git archive b98e187` 的干净 HEAD 副本上复现基线；
4. Coordinator 亲自抽验 Major 发现：阅读 `SessionMergeService.java:172-291`、`MergePackageService.java:225-242`，并用全仓 grep 证实"v2 运行期从不写 `failed` 状态"（`status='failed'` 仅出现在 V026 迁移 SQL 中）。

### 2.3 验证安全性声明

- 全部测试使用临时 SQLite + 临时目录 + stub provider；未写真实用户库（`backend/.ai-code-assistant/data.db` 的写入经 lsof 证实来自用户手动启动的应用进程，非测试）；未调用真实付费模型 API（Live 测试均被环境门控跳过，构成 70 项跳过）；
- 审查与测试执行前后 `git status --porcelain` 均为 49 项，未改/未增/未减任何被跟踪文件，未执行 git 提交。

---

## 三、Q1 详证：非合并链路影响评估

逐 hunk 对照 HEAD 基线核查，关键隔离证据（均有代码位置或测试支撑）：

| # | 隔离点 | 证据 |
|---|---|---|
| 1 | **QueryEngine 普通路径** | `QueryEngine.java:841-842 / 1813-1814`：`state.getHandoffOperationId()==null` 时直接返回空投影，`historyBudget==inputBudget`，零 DB/交接服务调用；`QueryEngineUnitTest.java:1950-1984` 参数化用例断言 merged=false 分支 payload 不含 "HandoffRead"、`verifyNoInteractions(handoff)`、`phase2-phase1==0`（不双扣） |
| 2 | **投影注入对空集无损** | `HandoffContextService.java:84-85`：空投影时 `inject()` 返回**原列表对象**，`CompactionHistory.forRequest/normalizeTyped` 输入与基线同一引用；插入点在压缩前、不落入 tool_use/result 配对内部 |
| 3 | **工具注册隔离** | `HandoffReadTool` 非 Spring Bean（`HandoffContextServiceTest.java:28` 显式断言无 `@Component`），唯一实例化于 `HandoffContextService.java:31`；`ToolRegistry.java:43-50` 只注册注入的 Tool bean → 普通主会话/子代理/Swarm 的工具 schema 与 token 开销完全不变 |
| 4 | **控制器入口零额外开销** | `QueryController.java:178-179 / 265-266 / 362-363` 仅复用已加载的 `session.config()`（多一次 Map.get）；`WebSocketController.java:925-934` 将 `ifPresent` 改为显式取值，`loadSession` 次数不变，仅 E 才调 configure（`:958-960`）；PROMPT 工具限制保留（`:878`） |
| 5 | **ImageRefInjector 权限不倒退** | 普通路径走旧 7 参重载，谓词固定 false（`ImageRefInjector.java:93-98`），注入行为与基线逐字节等价；新放行仅当 `checkResult.isAllowed()==false` 时咨询 `canReadAsset`（`:266-269`），要求：可信根会话 + 包内登记 + kind=asset + sha256 相等 + 归一化绝对路径等于包内路径 |
| 6 | **授权最小且不可继承** | `AuthorizationService.java:157-158` 仅在既有 safeRead 条件（`risk==SAFE && effects==[READ_RESOURCE]`）叠加 `handoff-read-v1`，位于 PLAN 检查之前（PLAN 可用）；未入 SAFE_INTERNAL；`OperationAnalyzerRegistry.java:87` 的 `instanceof HandoffReadTool` 判定在 `isMcp()` 之后 → 同名 MCP/动态工具仍走原分析器（`OperationAnalyzerRegistryTest.java:65-68` 验证） |
| 7 | **启动/删除路径** | `SessionMergeService.recover()` 启动时仅一次小查询 + 清理候选；`SessionManager` 完全未改动（git status 证实），普通删除不触发合并查库/磁盘清理 |
| 8 | **其他入口不设标记** | `SwarmWorkerRunner.java:198`、`SubAgentExecutor.java:411`、`AgentResumeService.java:108` 不设置运行标记，行为保持基线（此三文件未被本次改动触碰） |
| 9 | **基线对照实验** | 用 `git archive b98e187` 干净副本复现本机全量测试的全部 11 个失败类 → 与本次改动无因果关系；相关测试类及其依赖主类均不在改动清单内 |

**附加说明**：合并核心服务（`SessionMergeService` 等）全仓 grep 确认只被合并链路（`SessionMergeController`、`HandoffReadService`/`HandoffContextService`）引用，未挂入普通 SessionManager/SubAgent/Swarm 路径。被您指定忽略的 12 类前端文件与合并功能无调用关系，但属于**另一批独立改动混在同一工作区**——提交时建议与合并 v2 拆分 commit/PR，避免污染历史。

---

## 四、规格符合性概览（对照 `session-merge-architecture-v2.md`）

| 规格条目 | 结论 | 关键证据 |
|---|---|---|
| §3 流程：空闲检查→封存→立即释放→提取→聚合→校验→事务发布 | ✅ 符合 | `SessionMergeService.execute:172-232`；`MergePackageService.seal:150-175` |
| §4.2 目录布局（staging/snapshot/{manifest,records,files,occurrences,gaps,raw,text,assets,seal}/work/handoff） | ✅ 符合 | `SnapshotWriter:150-660`；`MergeSummaryService.prepare:63-152` |
| §4.2 路径程序生成、防穿越/symlink、只允许清单内路径 | ✅ 符合 | `safeFile:304-318`（绝对路径/`..`/逐段 symlink/NOFOLLOW regular）；`deleteTree` 拒 symlink 根 |
| §4.2 origin/version 完整 SHA-256 去重、occurrences 保留重复位置 | ✅ 符合 | `record:374-382`；`messageVersion:346-353` |
| §4.2 产物清单（artifact_manifests/entries）分页导出 | ✅ 实现符合 / ⚠️ 零测试 | `MergePackageService.java:442-451`；测试夹具未建这两张表 |
| §4.3 封存提交顺序、封存即释放、未封存崩溃重做、已封存损坏暂停 | ✅ 符合 | 释放 `:194`；`nextSnapshot:98-104`；损坏检测测试通过 |
| §4.3.1 "集合变动最多重新取得两次" | 🟡 未实现（fail-safe） | `lockSources:121-122` 单次失败即 MERGE_SOURCE_BUSY |
| §4.4 32KiB 分片、16MiB 规则移除、极端 JSON→paused/RECORD_REQUIRES_HANDLING | ✅ 符合 | `TextParts:609-628`；`maxRecordMaterializeBytes=64MiB` 超限→blocked+paused |
| §4.4 重复合并（C=merge(A,B) 后 merge(C,A)） | ✅ 符合 | `importPrevious:601-670`；测试证明删旧包/删来源后仍可读、去重、保留新增 |
| §5.1 V026 迁移（表重建、逐列复制、preparing→failed/LEGACY_INTERRUPTED、CHECK 组合、唯一 active） | ✅ 符合 | `V026:15-120` 与附录 A 逐字对应；`active_slot IS 1`、NULL 语义被显式测试；连续两次 execute 幂等 |
| §5.2 唯一 active 原子占名额（唯一索引，非 COUNT-then-INSERT） | ✅ 符合 | 部分唯一索引 `WHERE active_slot=1`；测试直接证明第二次 INSERT 失败 |
| §5.2 run_epoch 只在校验时机递增；写路径带 epoch+preparing 围栏 | ✅ 符合 | create=1 / resume +1 / cancel +1；进度更新不递增 |
| §5.2 单元提交协议（短事务→无事务模型调用→短事务提交）、重启 running→pending | ✅ 符合 | `beginAttempt` / `processStage` / `commitUnit`；测试 `attemptCount==1` 证明已完成单元不重调 |
| §5.2 cancel 与 publish 互斥、最多一个目标 | ✅ 符合 | SQL 条件互斥；`targetBindingRequiresAtomicPublicationAndLiveTarget` 测试 |
| §5.3 active/resume/cancel 接口语义与错误码 | ✅ 符合 | 204/409/404 均有 HTTP 层测试；expectedEpoch 条件更新 |
| §5 旧 protocol=1 只读兼容 | ✅ 符合 | `get()` 排除 v1 的 canResume/canCancel；cancel 对 v1 返回 409 |
| §6.1 六栏目 schema、状态白名单、content/evidence 校验、itemId 程序生成 | ✅ 符合 | `validate:278-296`；越界别名/非法状态/尾随内容负例测试 |
| §6.2 串行提取、单元即时落盘、聚合分层收缩、程序兜底目录 | ✅ 符合 | 聚合 level>=2 强制兜底；details.jsonl 全量登记不被上层替换 |
| §6.3 删除旧 max(token,UTF8Bytes)/3500/16 次上限 | ✅ 符合 | `capacity` 仅 TokenCounter；grep 无旧 `SUMMARY_*` 错误码 |
| §6.3 预算默认值（16,384 / 2,048 / 1MiB / 256KiB / 300s / 3 次退避 2,5s / 下限 256） | ✅ 符合 | `inputBudget:57-61`、`generationBudget:53-55`、timer 300s、`attempt<3`、`MERGE_MIN_UNIT_FAILED` |
| §6.3 thinking 预留 min(32768, window/4) | ✅ 符合 | `generationBudget`；测试断言 32768+2048 |
| §7.1 每轮有界入口（min(2048, 预算×10%)）、不写回、不调 LLM、不提升 system | ✅ 符合 | 测试断言不写 state.messages、payload 含本轮请求、phase2 不双扣 |
| §7.2 HandoffRead 参数/游标/搜索/预算语义 | ✅ 符合（1 处 code 透传偏差，见 Minor） | 13 项测试覆盖读取/权限/游标/搜索去重/超长行/15s 截止/8MiB/16KiB/complete |
| §7.3 授权复用与范围校验 | ✅ 符合 | 分析/执行前均从可信根会话复核绑定与包哈希；`HANDOFF_BINDING_CHANGED` |
| §8 发布前检查、同事务建目标+metadata+入口消息+completed、hook 不回滚 | ✅ 符合 | 绑定撤回→paused 且无目标消息；提交不确定核对测试 |
| §8 前端要求（active 恢复、真实锁、epoch、cancel 不带版本等） | ✅ 核心达成 / ⚠️ 2 处测试缺口 | 详见 Major-3/4 与 Minor 清单 |
| §9 关键用例 | ✅ 大部分有直接测试 | 缺口见第八节 |

---

## 五、发现清单：Blocker（无）

未发现数据丢失、错发目标、锁泄漏、事务边界违规或可绕过的完整性校验。`Token::close` 幂等、release 路径在 finally/异常分支全覆盖；快照损坏不会触发空恢复放行/自动替换；发布与取消互斥由 SQL 条件保证；SQL 动态拼接的表/列名均为内部常量、值全部参数化（无注入）。

---

## 六、Major 级发现（4 条）

### 🔴 Major-1【后端·功能】不可恢复的阻断项被记为可恢复 `paused`，resume 永远空转（无 `failed` 状态使用，explain 文案误导）

**证据**（coordinator 已亲自抽验）：
- `backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java:194-198`：`snapshot.blockedReason()` 非 `RECORD_REQUIRES_HANDLING` 前缀时直接 `throw new IOException(blockedReason)` → 落入 `catch` 的 `progress.pause(...)`（`:258-263` 附近）；
- 而以下阻断均以 `blocking=true` 写入**不可变 manifest**：legacy 旧包目录缺失（`MergePackageService.java:608`）、旧包内 symlink（`:616`）、`invalid_image`（`:531`）、附件文本解码失败（`:598`）、checkpoint 类（`:565`）——每次 resume 都在同一处再次失败；
- 全仓 grep 证实：v2 运行期**从不写 `failed` 状态**（`status='failed'` 仅出现在 V026 迁移 SQL 的旧行转换中）；`canResume` 对 paused 恒为 true（`SessionMergeService.java:267`），`explain()`（`:237-247`）对未识别 code 输出"请排查后恢复"，但 legacy 包缺失/单文件 symlink/附件编码错误**不存在任何"修好后恢复"的路径**。

**影响**：用户被卡死在 paused——无法完成、无法通过 resume 前进（每次还付出全量快照哈希校验、可能 GB 级与派生投影重算成本）、文案误导，只能 cancel；cancel 后以同一 legacy 来源重发合并，会在同一位置再次暂停，形成循环。**与规格 §5.1"failed：已确认不可恢复的损坏；不能 resume，由用户 cancel 结束"的状态机定义不符**；与 §1"重复合并不依赖旧包存活"的意图有张力。

**建议修法**（任选，建议 a+b）：
- a. 区分可恢复性：将"旧包缺失/单文件 symlink 未收录/非关键附件不可解析"降级为**非阻断 gap**（记录"未收录范围"），仅真正影响关键资料完整性的才阻断；或给 Operation 增加 `recoverable` 标志，前端对不可恢复项渲染"只能取消重建"；
- b. 对确定性不可恢复路径（`legacy_package_missing`、`legacy_symlink_not_copied` 等）pause 时写明确 `error_code`，`explain()` 输出"该问题无法通过恢复解决，请取消后重试/改用其他来源"；
- c. 或新增 paused→failed 的判定迁移（failed 不可 resume）。

### 🔴 Major-2【后端·功能】恢复投影协议对两类阻断记录不可达（gap 与 blocked 记录无法关联；asset 类阻断 ref 不属于 blocked 集合）

**证据**（coordinator 已亲自抽验）：
- `MergePackageService.java:232-236`（`recoverBlockedProjections`）：只接受 reason 形如 `RECORD_REQUIRES_HANDLING:<ref>` **且该 ref 必须存在于 records.jsonl 中 `processingPolicy=='blocked'` 的集合**，否则直接抛 `RECORD_REQUIRES_HANDLING`；
- 但 `:436` 消息内容无法解析时写入的 gap **不带 ref**（`:406` 无 UUID 重复记录同样不带 ref）——尽管 `:435` 已创建 policy=blocked 的记录，二者永远无法关联；
- `:598` 的资产文本编码失败写入 `RECORD_REQUIRES_HANDLING:<asset recordRef>`，但资产记录 policy 是 `extract`（`exportAsset:594-599`），不进入 blocked 集合，同样永远抛错。

**影响**：对这两类阻断，`recoverBlockedProjections` 从不真正尝试恢复；规格 §4.3"封存后修正解析配置 → 从本包 raw 生成投影"的派生投影机制只能覆盖"raw 超大/JSON 无法解析但可重新物化"那一类。与 Major-1 叠加放大（阻断记录已保留但恢复协议声称支持的场景不可达）。

**建议修法**：统一 gap 与 blocked 记录的关联键（消息阻断写 `RECORD_REQUIRES_HANDLING:<ref>`）；asset 类阻断单独分类（`ASSET_UNREADABLE:<ref>`）或允许非 blocked 记录通过"生成可读投影/占位说明"参与恢复；为 `invalid_image`/attachment 类提供"跳过并标记"的非阻断策略。

### 🔴 Major-3【前端·测试缺口】规格强制验收项"旧 resume 不重放 / epoch 过期刷新状态"零断言

**证据**：`frontend/src/store/sessionMergeStore.ts:243` `const operation = response.ok ? body : body.operation;` 与 `:249` 的错误抛出；收到 409 `MERGE_STALE_OPERATION` 时会采用 `body.operation`（最新 epoch/状态）并展示服务端 message——该分支**无任何用例**。现有测试只有快乐路径（`sessionMergeStore.test.ts:427-438` 断言 resume 发送 `{expectedEpoch:7, model}`；`:440-446` 断言 cancel 无 body）。

**影响**：规格 §8"旧 resume 请求按 epoch 拒绝后刷新状态"与 §9 UI 用例"旧 resume 不重放"是本轮关键验收项，当前无回归保护。若该分支被改坏（遗忘 adopt、自动重试、epoch 回写旧值），测试全绿但真实用户会卡在"状态已变化"循环。

**建议**：新增 store 用例：pending=paused(epoch=7) → POST /resume 返回 409 `{error:{code:'MERGE_STALE_OPERATION',...}, operation:{...status:'preparing',runEpoch:8}}` → 断言：(a) pending.operation.runEpoch===8 且 status==='preparing'；(b) 未自动再次 POST /resume（fetch 调用次数）；(c) 展示服务端 message；(d) 再次 resume 使用 expectedEpoch=8。另补一条 cancel 409 `MERGE_ALREADY_COMPLETED` + operation(completed) 的采用断言。

### 🔴 Major-4【前端·测试缺口】面板 v2 全部新控件零覆盖

**证据**：`frontend/src/components/session/SessionMergePanel.tsx:198-226` 新增的 paused/cancelled 状态行、`progress.completedUnits/knownUnits`（`:205`）、`retryAt`（`:206`）、暂停原因（`:207`）、`canResume` 恢复块（`:217-224`）、`canCancel` 取消按钮（`:225`）、"关闭结果"门禁（`:226`）、`targetAvailable===false` 禁用与"目标会话已删除。"（`:214-215`）——`SessionMergePanel.test.tsx` 本次仅改 8 行（fixture + 2 处文案断言）。

**影响**：面板是用户唯一操作入口；按钮渲染条件（如 `disabled={targetAvailable===false}`、`!busy && !canCancel` 的 dismiss 隐藏）或 resume 参数（`model || undefined`）被改坏不会被发现。§9"UI 与控制"验收实际只覆盖了旧用例。

**建议**：补 5-6 条面板用例：(1) paused+canResume+canCancel → 渲染暂停文案/原因/恢复/取消，点击恢复断言 POST resume 带 expectedEpoch；(2) 点击取消断言 POST /cancel 且无 body；(3) preparing+`progress{3,10,totalFinal:false}` → 断言"整理单元：3/10（总数随分片增加）"且无百分比；(4) completed+targetAvailable:false → "打开新会话"禁用 + "目标会话已删除。"；(5) failed(v2) → 只显示取消按钮，取消后显示"关闭结果"。

---

## 七、Minor 级发现（21 条）

### 后端·合并核心（7）

| # | 发现 | 证据 | 影响/建议 |
|---|---|---|---|
| M-1 | `lockedSourceSessionIds` 只反映根来源，子会话（descendants）的锁不可见 | `SessionMergeService.java:264-266` 只对 `request.sourceSessionIds()` 过滤；`lockSources:114-119` 却对每个 descendant 取 token | 前端"来源是否锁住只看服务端字段"会低估锁范围；建议纳入 descendants 或明确 DTO 语义 |
| M-2 | 跨进程/多实例抢名额时唯一索引冲突以 500（DataAccessException）暴露而非 `MERGE_ACTIVE_EXISTS` 409 | `SessionMergeService.start:95-97` 先 active() 再 create()；同一 JVM 有 synchronized 覆盖 | 单机部署不可达；建议 catch `DataIntegrityViolationException` 转 409 |
| M-3 | 未实现规格 §4.3.1"集合变动最多重新取得两次" | `lockSources:121-122` 单次失败即 MERGE_SOURCE_BUSY | 低概率竞态下合并被误拒一次（fail-safe，可手动恢复）；建议按规格补重取 |
| M-4 | 损坏的文本类附件（非 UTF-8 的 .txt/.md/.log 等）即阻断整个合并 | `MergePackageService.java:588-598` 严格 UTF-8 读取，失败即 `blocking=true` | 与规格"非关键附件可标记保留原件"不一致，可能误伤；建议降级为非阻断 gap 或允许 resume 时跳过 |
| M-5 | `MERGE_UNIT_INPUT_CHANGED` / `MERGE_PROCESSOR_VERSION_CHANGED` / `MERGE_SNAPSHOT_CORRUPT` 等不可恢复错误同样进入 paused 循环，文案缺操作指引 | `MergeProgressRepository.plan:152-155`；`SessionMergeService.explain:237-247` default 分支 | 与 Major-1 同源体验问题；建议一并处理 |
| M-6 | v2 `seal()` 路径的磁盘余量/复制配额/复制中途变化无直接测试；artifact_manifests/entries 分页导出（§4.2 明确要求）零覆盖 | `MergePackageServiceTest` 同类用例均走旧 `build()`；`MergeFixture:41-47` 未建两张表；`MergePackageService.java:442-451` 无测试触及 | 代码静态审阅正确（列名与 V017 实表一致），但上游建表变动/SQL 拼写错误无测试兜底；建议补 v2 用例 |
| M-7 | `importPrevious` 的 destination 重映射缺少"目标仍在包内"的防御性校验 | `MergePackageService.java:648-657`：`root.resolve(Path.of("snapshot").relativize(relative))` 直接拼接 | 仅旧包 files.jsonl 被人工篡改时可能破坏布局（无法逃出包根）；建议写入前 `normalize().startsWith(root)` 校验 |

### 后端·集成（7）

| # | 发现 | 证据 | 影响/建议 |
|---|---|---|---|
| I-1 | E 会话在绑定缺失/包损坏/预算过小时**整会话失败且 REST 落 500** | `HandoffContextService.java:41-43, 71-78` 抛 AuthorizationException/IllegalStateException；`GlobalExceptionHandler` 落 500 catch-all | 仅 E（普通不受影响）；库回滚/包丢失时 E 每条消息都失败。建议降级为"资料不可用"提示或结构化 4xx |
| I-2 | E 每轮最多 3 次全量投影（绑定查询 + 全目录哈希校验 + brief 读取），工具调用再 3 次校验 | `QueryEngine.java:779/841/844/1835`；`HandoffContextService.project:62-78`；`HandoffReadService.verifyPrepared:69-101` | E-only 性能开销（大包时单轮数 MB 哈希）；建议同轮复用一次投影 |
| I-3 | `HANDOFF_READ_BUDGET_EXCEEDED` 的 code 被包装替换 | 初始校验超时→`HANDOFF_INVALID_RESOURCE`（`HandoffReadService.java:205-208`）；扫描超时→`HANDOFF_READ_FAILED`（`HandoffReadTool.java:44`），仅 message 保留原 code | 规格要求明确透传；模型/前端难区分"越界"与"损坏"；建议透传 |
| I-4 | 跨进程并发 resume 败者返回 500 而非 409 `MERGE_STALE_OPERATION` | `MergeProgressRepository.java:108-118` 条件更新失败 → `IllegalStateException("MERGE_STALE_EXECUTION")`，Service 未转换 | 单 JVM 有 synchronized+writers 防护；多实例共享库时契约退化；建议映射为 Conflict |
| I-5 | 本机默认环境全量 2,975 项有 11 个环境变量相关失败（HEAD 基线同样失败） | `ZHIKUN_COORDINATOR_MODE=1`（×3）、`ALLOW_PRIVATE_NETWORK=true`（×6）、真实 `LLM_PROVIDER_DASHSCOPE_API_KEY`（×1）；缺 ripgrep（×1） | 与本次改动无关，但影响"发布标准"叙事；建议测试侧隔离环境变量并在验收说明注明环境前提 |
| I-6 | 新增测试薄弱点 | `QueryEngineUnitTest.java:1950-1984` 未 captor 校验传给 `project()` 的是扣减前总预算（仅间接证明）；`HandoffContextServiceTest` 缺 `toolAvailable=true` 全量 brief / `HANDOFF_CONTEXT_BUDGET_TOO_SMALL` / brief IOException 三类用例；`AuthorizationServiceProjectFileScopeTest.java:41-43` 未断言 `PLAN_MODE_EFFECT_DENIED` | 边界回归证明力略低；建议补 captor/异常 code 断言与三类用例 |
| I-7 | `HandoffContextService.configure` 对 null ToolUseContext 无防护（E-only NPE 风险） | `HandoffContextService.java:41` 直接 `state.getToolUseContext().sessionId()` | 当前三个入口均先设置，但 API 未防御；建议 null 时明确降级或抛带 code 的异常 |

### 前端（7）

| # | 发现 | 证据 | 影响/建议 |
|---|---|---|---|
| F-1 | `selectMergeSourceIds` 仍从 `status==='preparing'` 反向推断锁定 | `sessionMergeStore.ts:28-31` | paused/cancelled 旧 writer 收尾期间服务端仍可能返回非空锁集合，此窗口内前端误判为未锁（gate 仍会拒绝，用户看到报错而非前置阻断）；建议去掉 status 条件，只取服务端字段 |
| F-2 | 本地存在陈旧终态记录时不会查询服务端 active | `sessionMergeStore.ts:140` `if (!pending && Date.now() < nextActiveCheck)`；`restore():66-71` 直接采用已保存记录 | 其他设备发起的新合并在本机不可见，须先 dismiss 旧结果；建议终态记录时仍查询一次 `/active` |
| F-3 | failed(v2) 状态行展示最后一个工作阶段文案（如"整理详细交接"）；error 为空时渲染空 alert | `SessionMergePanel.tsx:198, 207` | 失败态可读性差；建议为 failed 增加专门文案 + error 非空条件渲染 |
| F-4 | 通知去重依赖调用方 `changed &&`；notificationStore 不按 key 去重；paused/cancelled 一律 error 级别 | `sessionMergeStore.ts:200-203`；`notificationStore.ts:32-33` 无条件 push；`ToastContainer.tsx:41` `key={n.key}` | 5s 窗口内重复触发会产生重复 Toast 与 React duplicate-key 警告；cancelled 是用户主动操作却报 error。建议按 key 覆盖 + 分级 warning/info |
| F-5 | `control()` 的 `response.json()` 无兜底、无 404 处理 | `sessionMergeStore.ts:241`（对比 `:159` 有 `.catch(() => ({}))`）；`:252` 解析异常原文当 UI 消息 | 代理返回 HTML 时用户看到 "Unexpected token …"；404 要等下一次轮询才释放；建议 json catch + 复用 refresh 404 释放逻辑 |
| F-6 | 轮询期间恢复/取消按钮被 `disabled={submitting}` 周期性禁用 | `sessionMergeStore.ts:145` 每次 refresh 开始 `set({submitting:true})`；`SessionMergePanel.tsx:222/225` | 每 2s 轮询反复禁用按钮，与"进度更新不阻挡取消"相悖（`control()` 已串行化，并不需要禁用）；建议拆出 `controlling` 标志或去掉 disabled |
| F-7 | 收起态浮动按钮对 paused/failed 显示"合并结果" | `SessionMergePanel.tsx:34, 143`（`busy` 只含 preparing） | 暂停/失败没有"结果"可看，文案误导；建议按状态显示"合并已暂停 · 去恢复 / 合并失败 · 处理" |

---

## 八、Nit 级发现（23 条，摘列）

**后端核心（7）**：`history_storage_version` 死条件（`MergePackageService.java:440`，全仓无生产者）；`MAX_RECORD_BYTES=16MiB` 常量残留易误读（`:71`，仅旧 build 引用）；聚合单元 `sourceId` 为空串（`MergeSummaryService.java:130-133`）；unit `state='failed'` 定义但从不写入（`MergeSummaryService.java:238`）；`SessionMergeService` 构造函数保留未用 `permissions` 参数（`:83-85`）；brief 兜底缺"还有 N 项未展开"数字（`MergeSummaryService.java:112`，overview 已有计数）；`retry_at` 崩溃残留且恢复不遵守（`MergeSummaryService.java:251-257`）。

**后端集成（8）**：内联全限定类名（`QueryEngine.java:1808-1818, 940`）；`@Autowired` 字段注入（`QueryController.java:72-74`、`WebSocketController.java:90-91`，与项目构造注入主体不一致）；`@Autowired(required=false)` 与"缺 bean 即抛 IllegalStateException"的语义矛盾（`QueryEngine.java:1808-1815`）；类级 `produces="application/json"` 波及既有 create/get 端点（`SessionMergeController.java:8`，非 JSON Accept 客户端可能 406）；HandoffReadService 魔法数字散落（`:204-306`，16384/8500/2048/1500/512/20）；asset 放行阈值 20MB > `ImageRefInjector.MAX_FILE_SIZE` 10MB（`HandoffReadTool.java:41-44`，10~20MB 图片会生成必然被静默拒绝的 ref）；`alwaysLoad()==true` 与 core 登记的将来风险点（`HandoffReadTool.java:16`，当前非 bean 安全，已有测试锁定非 @Component）；`preCleanImageHistory` 未预留 handoff 预算（`QueryEngine.java:2690-2706`，无功能影响）。

**前端（8）**：串行化用例名不副实且 `finishPolling` 是死代码（`sessionMergeStore.test.ts:487-508`，真正"先轮询后 resume"顺序未覆盖）；"paused or completed"用例只构造 paused（`:410-415`）；restore/decode 异常路径无测试（`sessionMergeStore.ts:60-72`）；锁集合变化触发 `session-lists-updated` 无直接断言（`:197-199`）；`protocolVersion?: number` 未收紧为 `1|2` 且未被消费（`:16`）；错误体采用分支不校验 `request` 字段（`:171-175`）；"五来源"用例断言"2 个来源会话正在复制"可读性差（`SessionMergePanel.test.tsx:182`）；`retryAt` 直接展示原始时间串（`SessionMergePanel.tsx:206`）。

---

## 九、测试覆盖与发布标准实测（独立执行验证）

### 9.1 环境

macOS arm64 / Java Corretto 21.0.10 / Maven 3.9.9（mvnw）/ Node v22.14.0 / vitest 2.1.8。测试 profile 隔离于 `backend/target/test-databases/` 与临时目录。

### 9.2 实测结果

| # | 批次 | 精确命令（摘要） | 用例 | 结果 | 耗时 |
|---|---|---|---|---|---|
| 1 | 后端定向一 | `./mvnw -Dspring.profiles.active=test -Dtest='V026ExtendSessionMergesTest,MergeProgressRepositoryTest,MergePackageServiceTest,MergeSummaryServiceTest,SessionMergeServiceTest,SessionMessagePersistenceTest,HandoffContextServiceTest,HandoffReadServiceTest,MigrationRunnerV2Test' test` | 83 | ✅ 全过 | 23s |
| 2 | 后端定向二 | `./mvnw … -Dtest='SubAgentExecutorStatusPropagationTest,SwarmWorkerRunnerModelTest,QueryEngineUnitTest,QueryEngineMediaRecoveryTest,QueryEngineWithholdTest,ImageRefInjectorMetadataTest,OperationAnalyzerRegistryTest,AuthorizationServiceProjectFileScopeTest,SessionManagerMessageIdempotencyTest,SessionManagerSearchTest' test` | 160 | ✅ 全过 | 10s |
| 3 | 后端全量（原样环境） | `./mvnw -Dspring.profiles.active=test test` | 2,975 | ❌ 11 失败 / 70 跳过（**全部为环境因素**，见 9.3） | 102s |
| 4 | **后端全量（干净环境）** | 同上 + `env -u` 三个变量 + 补 ripgrep PATH | **2,975** | ✅ **2,905 通过 / 0 失败 / 0 错误 / 70 跳过，BUILD SUCCESS**（与参考值精确一致） | 98s |
| 5 | 前端全量 | `npm run test:run -- --maxWorkers=2 --minWorkers=1` | 910（103 文件） | ✅ 894 通过 / 16 跳过 / 0 失败 | 60.6s |
| 6 | 前端生产构建 | `npm run build`（tsc && vite build） | — | ✅ 成功（仅 chunk >1000kB 体积警告） | 26s |

### 9.3 原样环境 11 项失败的归因（全部实锤，与改动无关）

| 失败类 | 数量 | 根因 | 对照实验 |
|---|---|---|---|
| CoordinatorServiceTest | 3 | 本机 `ZHIKUN_COORDINATOR_MODE=1`（CoordinatorService 构造时读 env） | `env -u` 后 7/7 全过 |
| SecurityFilterIntegrationTest | 6 | 本机 `ALLOW_PRIVATE_NETWORK=true`（安全过滤器放行私网） | `env -u` 后 9/9 全过 |
| OneKeyRegistryIntegrationTest | 1 | 本机真实 `LLM_PROVIDER_DASHSCOPE_API_KEY`（StandardEnvironment 读 OS env） | `env -u` 后 7/7 全过 |
| WorkspaceFileBoundaryTest | 1 | 系统无 ripgrep，BSD grep fallback 行为差异 | 补 rg 14.1.1 后 16/16 全过 |

四个失败类及依赖主类均不在本次改动清单内；`git archive b98e187` 基线副本复现完全相同的失败 → **无证据表明由当前工作区代码引入**。surefire XML 独立聚合（305 套件）证实：2,975 testcases / 0 failures / 0 errors / 70 skipped 与 Maven 汇总一致（70 项跳过均为 Live/浏览器回放等环境门控用例，无异常跳过）。

### 9.4 测试质量评估

**强项**：
- 迁移/仓库测试断言具体非恒真（`active_slot IS 1` 的 NULL 组合语义、唯一 active 第二次 INSERT 失败、迟到 usage 幂等记账、发布回滚后 binding 为空、删目标后进入 cleanup 候选、split 计数排除）；
- 核心服务覆盖封存即释放、损坏 checkpoint 拒绝空恢复（`verifyNoInteractions(provider)`）、失败保留单元不重复付费（attemptCount==1）、发布/取消竞态、提交不确定核对、崩溃恢复；
- 前端 63 项覆盖重载恢复、多 tab、localStorage 缺失/写失败、404 释放、封存后不误锁；
- **未发现被删除或弱化的断言**（diff 为纯新增 + 少量规格行为变化导致的文案替换）；全部使用临时库/临时目录/stub provider，无真实付费调用。

**缺口**（除 Major-3/4）：v2 `seal()` 磁盘/配额路径无直接测试；artifact 导出零覆盖；v2 路径"重复位置不灌模型"无直接断言；4183 字节回归缺"provider output=13,370 已报告"的组合分支（旧阈值已整体删除，风险低）；慢 hook/双操作并发发布无测试；`MERGE_CANCEL_CLEANUP` 等错误码无前端契约断言；测试对 3 个宿主环境变量敏感。

---

## 十、发布建议（按优先级）

1. **合并前处理 Major-1/2**（后端功能缺陷）：至少让不可恢复阻断输出明确指引（"只能取消重建"），理想做法是降级非关键阻断 + 统一恢复协议关联键——这是唯一会导致用户永久卡死的功能性缺陷；
2. **补齐前端 Major-3/4 测试**（规格"必须证明"的验收项）：epoch 过期 resume 采用分支 + 面板全部新控件，工作量约 1–2 小时；
3. **提交拆分**：工作区混有两批独立改动（合并 v2 + 被忽略的其他前端任务），建议分 commit/分 PR 提交；
4. **PR 描述如实标注**：a) 真实模型语义验收未执行（规格 §9 明示不可用 stub 替代）；b) 全量测试需干净环境（3 个环境变量 + ripgrep），建议同时修复测试的环境隔离敏感性；
5. **收尾可选优化**（选自 Minor）：`lockedSourceSessionIds` 纳入 descendants；跨进程冲突统一映射 409；附件编码损坏降级为非阻断；`HANDOFF_READ_BUDGET_EXCEEDED` 透传；E 会话绑定异常降级为"资料不可用"提示；通知按键去重；恢复/取消按钮 disabled 拆分；failed 状态专门文案。

---

## 附录 A：审查证据文件索引

Coordinator 四路审查的详细底稿（含逐条 文件:行号 证据、对照实验日志、surefire 归档）：

| 文件 | 内容 |
|---|---|
| `backend/.zhikun/scratchpad/8ca39e67-f187-4c5c-8b7c-9d97032b7768/backend-merge-core-review.md` | 后端合并核心详报（含规格符合性对照表、测试质量评估） |
| `…/backend-integration-review.md` | 后端集成隔离详报（含逐 hunk 证据、非合并链路逐条结论） |
| `…/frontend-merge-review.md` | 前端详报（含测试覆盖评估与缺口清单） |
| `…/test-run-report.md` | 测试执行详报（含 11 项环境失败完整对照实验、两次全量 surefire 归档） |
| `…/batch1.log`、`batch2.log`、`full.log`、`full-clean.log`、`diag*-*.log`、`frontend-test.log`、`frontend-build.log` | 原始执行日志与对照实验证据 |

## 附录 B：本次审查的局限

1. 按用户要求，12 类前端文件未纳入审查（其与合并 v2 的相互关系仅做"无调用关系"的粗判）；
2. 语义验收（真实模型接续开发样本）未执行，与本规格文档自身声明的剩余工作一致；
3. 审查基于未提交工作区快照（HEAD=b98e187 + 49 项改动），若后续代码变动需重新验证；
4. 本报告不承诺软件在任何环境绝对零风险。

---

*报告完 — DeepSeek V4.1 Flash*
