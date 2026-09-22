# 会话合并 v2 本地改动审查报告

> **审查对象**：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`（main 分支，未提交的本地改动）
> **架构基线**：`docs/session-merge-architecture-v2.md`（本次改动新增，更新日期 2026-09-22，问题核对基线 `b98e187`）
> **审查方式**：6 个并行审查员分模块深挖 + 协调者对关键结论独立复核 + 实跑编译与全量测试
> **审查性质**：只读审查，未修改任何源代码/测试代码/配置文件

---

## 一、审查范围与方法

### 1.1 在范围内的改动

已按要求**排除** 16 个指定文件（`ImageBlock.tsx`、`ImageBlock.test.tsx`、`messageContent.ts`、`messageContent.test.ts`、`MessageActions.tsx`、`MessageActions.test.tsx`、`e2e/message-copy-all.spec.ts`、`sessionStatusMeta.ts`、`SessionStatusCapsule.tsx`、`Header.tsx`、`PermissionMenu.tsx`、`PromptInput/index.tsx`、`Sidebar.tsx`、`globals.css`、`liquid-glass.css`、`Sidebar.desktop.test.tsx`）。

| 区域 | 文件数 | 规模 |
|---|---|---|
| 后端生产代码 | 12 改 + 6 新 | ~2,100 行改动 + 866 行新增 |
| 后端测试 | 7 改 + 4 新 | ~1,000 行改动 + 435 行新增 |
| 前端（合并模块） | 2 改 + 2 测试 | 291 行 |
| 文档 | 1 新 | `docs/session-merge-architecture-v2.md` |

**后端生产代码清单**

改动：`authorization/AuthorizationService.java`、`authorization/OperationAnalyzerRegistry.java`、`controller/QueryController.java`、`controller/SessionMergeController.java`、`engine/ImageRefInjector.java`、`engine/QueryEngine.java`、`engine/QueryLoopState.java`、`session/merge/MergePackageService.java`、`session/merge/MergeSummaryService.java`、`session/merge/MergeTextBudget.java`、`session/merge/SessionMergeService.java`、`websocket/WebSocketController.java`

新增：`config/database/V026_ExtendSessionMerges.java`(137)、`engine/HandoffContextService.java`(88)、`session/merge/HandoffReadService.java`(324)、`session/merge/MergeHandoffData.java`(40)、`session/merge/MergeProgressRepository.java`(230)、`tool/impl/HandoffReadTool.java`(47)

**后端测试清单**

改动：`AuthorizationServiceProjectFileScopeTest`、`OperationAnalyzerRegistryTest`、`QueryEngineUnitTest`、`MergeFixture`、`MergePackageServiceTest`、`MergeSummaryServiceTest`、`SessionMergeServiceTest`

新增：`V026ExtendSessionMergesTest`(71)、`HandoffContextServiceTest`(65)、`HandoffReadServiceTest`(219)、`MergeProgressRepositoryTest`(80)

**前端清单**：`store/sessionMergeStore.ts` + `.test.ts`、`components/session/SessionMergePanel.tsx` + `.test.tsx`

### 1.2 审查方法

6 个并行审查员分模块深挖：

1. 合并编排与持久化（SessionMergeService / MergeProgressRepository / V026 迁移 / MergeHandoffData / SessionMergeController）
2. 资料包与读取安全（MergePackageService / HandoffReadService / HandoffReadTool / MergeTextBudget）
3. 非合并链路隔离性（QueryEngine / HandoffContextService / ImageRefInjector / authorization / MergeSummaryService 预算）
4. 测试覆盖对照（全部在范围内的测试文件 vs 规格第 9 节验收表）
5. 前端契约与状态语义（sessionMergeStore / SessionMergePanel）
6. 实跑验证（编译、定向测试、全量测试、类型检查、生产构建、副作用检查）

协调者对最关键的结论做了**独立复核**，而非照单全收：

- 亲自查证被删测试方法清单（`git diff` + grep 方法名），并逐条比对是否有规格依据
- 亲自 grep 6 个规格错误码在 main/test 中的命中数
- 亲自 grep `AUTO_APPROVE` / `permission_mode` / `occurrences` / `FileStateCache` / `TodoWrite` 在 merge 测试中的覆盖
- 亲自阅读 `HandoffContextService.java` 全文，确认异常降级路径
- 亲自定位 `QueryEngine` 第 841 行的 try/catch 嵌套边界，确认异常是否被兜住
- 亲自修正审查员的一处判断（磁盘失败测试的覆盖情况，见 B1）

**实跑环境**：OpenJDK 21.0.10 (Amazon Corretto 21)、Node v22.14.0 / npm 10.9.2、`frontend/node_modules` 已安装（563 项）。

**已知环境干扰因素**（后文失败归因的关键）：本机有常驻后端服务 PID 41395 监听 8080、vite dev server 监听 5173，shell 中导出了 `ALLOW_PRIVATE_NETWORK=true`、`ZHIKUN_COORDINATOR_MODE=1`、`LOG_DIR=<repo>/log`、多组真实 `DASHSCOPE_*` / `LLM_PROVIDER_*` API key；**未安装 ripgrep**。

---

## 二、结论速览

| 审查问题 | 结论 |
|---|---|
| **1) 是否对非合并分支造成功能链路的负面影响或破坏？** | **未造成任何影响。** 9 条普通链路逐条验证与基线等价；规格第 10 节"保持不改"清单经 `git diff` 证实**零改动**；全量后端测试的 11 个失败在**不含本次改动的 HEAD 干净基线上逐一同名复现** → 本次改动引入的新增失败 = **0**。 |
| **2) 是否达到提交至 GitHub 的发布标准？** | **尚未达到（有条件）。** 代码实现质量高、架构契约落实扎实、**阻断级代码缺陷 0 项**；但存在 **6 项发布阻断问题，其中 5 项集中在测试覆盖，1 项在文档真实性**，而非产品逻辑。 |

---

## 三、问题一：非合并链路影响评估

隔离设计是**干净且可证明的**，而非仅靠约定。三重独立证据：代码级逐链路推演、diff 级文件清单核对、运行时级实跑回归。

### 3.1 逐链路证据

| 普通链路 | 结论 | 关键证据 |
|---|---|---|
| 普通会话请求（REST×3 / WS） | 未受影响 | `QueryController:177/264/361`、`WebSocketController:959` 全部以 `isMerged(metadata)` **前置门控**；`HandoffContextServiceTest:24` 用 `verifyNoInteractions(repository, reads, subjects, tokens)` 证明普通请求**零交接调用** |
| QueryEngine 预算 / phase1 | 未受影响 | `QueryEngine:1810-1811` 空投影早退**在任何 I/O 之前**；`:842` `historyBudget = inputBudget - 0`，`:847/860/893` 与旧值逐字相同 |
| phase2 最终 payload | 未受影响 | `:951-952` 仍传**原总预算** `inputBudget`，校验对象是 `:940` 注入投影后的 payload → **既不重复扣减也不漏扣**；`QueryEngineUnitTest:1983` 断言 `phase2-phase1 == (merged?200:0)`，非合并恒为 0 |
| 普通图片注入 | 未受影响 | `:907-912` 三元选择，空投影走**旧 7 参重载**；`ImageRefInjector:96-97` 委托 `(path,hash)->false`，`:269` `!allowed && !false` ≡ 原 `!allowed` |
| 普通文件授权 | 未受影响 | `AuthorizationService:158-160` 括号正确、`file-v1` 判定等价；`SAFE_INTERNAL`（`OperationAnalyzerRegistry:42-44`）**未加入** handoff（符合规格明令） |
| 工具集合 / schema / token 开销 | 未受影响 | `HandoffReadTool` **无 `@Component`/`@Service`**，全仓唯一实例化是 `HandoffContextService:31` 的 `new`；`ToolRegistry:44-57` 只注册 Spring 注入的 `List<Tool>`，无类路径扫描 → 普通会话与子代理工具集不变 |
| fork / Swarm / 子代理 / 消息落库 | 未受影响 | `git diff --name-only HEAD` 证实 `SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner`、`SessionExecutionGate`、`ToolRegistry`、`ImageResultExternalizer`、`FileRead/Grep` **全部零改动**；已撤回的 `SubAgentHistoryPersistence` 全仓无源码引用 |
| 普通压缩 / 413 恢复 / 换模型重试 | 未受影响 | `:1835` `budget -= 0`；`:1933/:1973` 经同一 `prepareCompactionContext`；换模型后 `:838-841` 用**新 effectiveModel** 重算窗口/ratio/投影 |
| Spring 启动 | 未受影响 | `WebSocketStompIntegrationTest 19/19`、`QueryControllerProjectContractTest 6/6`、`SecurityFilterIntegrationTest` 上下文均加载成功 → 新增字段注入未导致启动失败 |

### 3.2 实跑验证结果

| # | 命令 | 结果 | 确切数字 | 与文档声称对比 |
|---|---|---|---|---|
| 1 | `./mvnw -q -DskipTests compile` | EXIT=0 | 无错误 | 一致 |
| 1b | `./mvnw -q -DskipTests test-compile` | EXIT=0 | **346** 个测试类编译成功 | 一致 |
| 2a | 定向命令 1（9 个类） | BUILD SUCCESS 14.0s | **Tests run: 83, Failures: 0, Errors: 0, Skipped: 0** | 一致（全绿） |
| 2b | 定向命令 2（10 个类） | BUILD SUCCESS 5.9s | **Tests run: 160, Failures: 0, Errors: 0, Skipped: 0** | 一致（全绿） |
| 2c | 后端全量 `-Dspring.profiles.active=test test` | **BUILD FAILURE** EXIT=1，1:34 min | **Tests run: 2975, Failures: 11, Errors: 0, Skipped: 70** | **不一致**（见 B6） |
| 3a | `npm run test:run -- --maxWorkers=2 --minWorkers=1` | EXIT=0，44.05s | **Test Files 103 passed；Tests 894 passed \| 16 skipped (910)** | 文件数/跳过一致，通过数 894 ≠ 876 |
| 3b | 合并模块前端定向 | EXIT=0 | **2 files / 63 passed**（43 + 20） | 一致（文档"63 项"吻合） |
| 4a | `npx tsc --noEmit` | EXIT=0 | **0 个类型错误** | 一致 |
| 4b | `npm run build` | EXIT=0，11.78s | 0 error | 基本一致但提示清单不完整 |
| 5 | `git status --porcelain=v1` 前后对比 | — | **49 行 → 49 行，`diff` 为空** | 工作区**零污染** |

**定向命令 1 逐类（83/83 全绿）**

```
V026ExtendSessionMergesTest        3/3      MergePackageServiceTest     30/30
MigrationRunnerV2Test              3/3      SessionMergeServiceTest     15/15
HandoffContextServiceTest          5/5      HandoffReadServiceTest      13/13
MergeSummaryServiceTest            6/6      SessionMessagePersistenceTest 5/5
MergeProgressRepositoryTest        3/3
```

**定向命令 2 逐类（160/160 全绿）**

```
SwarmWorkerRunnerModelTest        10/10     QueryEngineWithholdTest        6/6
OperationAnalyzerRegistryTest     22/22     ImageRefInjectorMetadataTest   1/1
AuthorizationServiceProjectFileScopeTest 10/10  SubAgentExecutorStatusPropagationTest 9/9
QueryEngineMediaRecoveryTest      25/25     SessionManagerMessageIdempotencyTest 1/1
QueryEngineUnitTest（5 组 @Nested：9+2+3+46+5）65/65   SessionManagerSearchTest 11/11
```

### 3.3 非合并链路回归结论（用户最关心项）

**答案明确：全部通过，零失败。**

| 回归测试类 | 结果 | 该文件是否被本次改动修改 |
|---|---|---|
| `SessionMessagePersistenceTest` | **5/5** | 未修改 |
| `SubAgentExecutorStatusPropagationTest` | **9/9** | 未修改 |
| `SwarmWorkerRunnerModelTest` | **10/10** | 未修改 |
| `SessionManagerMessageIdempotencyTest` | **1/1** | 未修改 |
| `SessionManagerSearchTest` | **11/11** | 未修改 |
| `QueryEngineMediaRecoveryTest` | **25/25** | 未修改 |
| `QueryEngineWithholdTest` | **6/6** | 未修改 |
| `ImageRefInjectorMetadataTest` | **1/1** | 未修改 |
| `MigrationRunnerV2Test` | **3/3** | 未修改 |

另**静态核验**了文档的声称"普通 SessionManager、SessionMessagePersistence、SubAgentExecutor、SwarmWorkerRunner 与 HEAD 完全一致"：`git diff --name-only HEAD` 逐文件核验，9 个文件全部 UNCHANGED（含 `WebSocketSessionManager.java` 及对应 5 个测试类）→ **该声称属实**。

前端邻接测试同样全绿：`Sidebar.desktop.test.tsx 8/8`、`ImageBlock.test.tsx 6/6`、`MessageActions.test.tsx 16/16`、`messageContent.test.ts 19/19`，`src/components/message/` 下 **17 个文件全部通过**。这些文件的代码质量不在审查范围，但它们全绿证明**本次改动没有破坏它们**。

### 3.4 副作用 / 污染检查

**仓库零污染（git 层面）**

- `git status --porcelain=v1`：前 49 行 / 后 49 行，`diff` 完全为空 → 未新增任何未跟踪文件、未修改任何被跟踪文件
- 未产生 `session-merges` 目录、快照文件、合并产物
- 运行产物 `frontend/dist/`（`.gitignore:11`）与 `backend/target/`（`.gitignore:2`）均已被忽略
- 本次审查新增的 4 个后端源文件与 `docs/session-merge-architecture-v2.md` 经 `git check-ignore` 验证**未被 gitignore 误伤**，可正常提交

**运行库未被测试写入**（符合规格第 9 节"禁止测试写入运行库"）

| 文件 | 基线 | 最终 | 判定 |
|---|---|---|---|
| `.ai-code-assistant/data.db`（仓库根） | 09-11 22:51:34 / 3956736 B | **完全相同** | 未动 |
| `backend/.ai-code-assistant/data.db` | 11:10:03 / 617693184 B | 11:30:31 / **大小未变** | 由常驻服务写入，非测试 |
| `backend/target/test-databases/global.db` | 10:46:19 | **未变** | 测试未重写 |
| `backend/target/test-project/.ai-code-assistant/data.db` | 10:46:19 | **未变** | 同上 |

证据链：① `data.db` mtime=11:23:37、`data.db-wal`=11:25:50，**均晚于所有后端测试结束时刻（最后一次 11:18:38）**；② `lsof -nP` 显示唯一持有者是 **java PID 41395（常驻服务）**；③ 测试配置 `application-test.yml:3` 为 `global-path: target/test-databases/global.db`。

**但 `log/app.log` 确实被测试写入**（真实副作用）

- 当前 `log/app.log` 共 1604 行，线程名 100% 是 `[main]`（surefire 测试主线程，无一条 `http-nio-*-exec-*` 服务线程）
- **根因**：shell 导出了 `LOG_DIR=<repo>/log`，而 `backend/src/main/resources/log4j2.xml:14` 为 `${env:LOG_DIR:-${sys:user.dir}/log}` → **优先取 LOG_DIR**。因此即使 maven 的 cwd 在 `/tmp`，日志仍写进主仓库的 `log/`
- **后果**：测试日志与常驻服务日志混写同一文件，并在窗口内触发滚动压缩（新建 `log/app-2026-09-22-6.log.gz`、`log/debug/debug-2026-09-22-6.log.gz`）
- **归因与定级**：`LOG_DIR` 是本机 shell 变量，`log4j2.xml` **不在本次改动清单内** → 既有行为，非本次引入；且 `.gitignore:64 log/` 已忽略 → **不会被误提交到 GitHub**。**不构成发布阻断**，但会污染每个开了 `LOG_DIR` 的开发者的服务日志。建议跑后端测试前 `unset LOG_DIR`

---

## 四、问题二：发布标准评估

### 4.1 阻断级（6 项，必须处理才能发布）

#### B1 测试覆盖率倒退：8 个测试被无规格依据地删除

规格第 9 节明确要求"改写"的 5 类旧断言（来源锁到摘要结束 / 摘要失败必须清包 / 超 16 次必须失败 / 重启全部失败 / 缺 usage 必须失败）以及 CopyLimit 测试，删除是**合法的**，已逐条确认：

- `fiveSourcesStayOccupiedUntilSummaryExitsAndUnrelatedSessionCanContinue`（"所有来源锁到摘要结束"）✓ 合法
- `summaryFailureCreatesNoTargetAndReleasesBothSources` / `textFailureCleansPackageEvenWhenRecordingFailureIsRejected`（"摘要失败必须清包"）✓ 合法
- `overCallBudgetFails` 及 `fiveSourceFailures` 的 `SUMMARY_CALL_BUDGET_EXCEEDED` 分支（"超 16 次必须失败"）✓ 合法
- `recoveryFailsInterruptedPreparationButKeepsCommittedPackages`（"重启全部失败"）✓ 合法，已由 `unexpectedRestartContinuesSealedSnapshotWithoutLiveSources` 替代
- `copyLimitWarningStillCommitsTargetAndPreservesSources`（§9 明令"旧 CopyLimit 测试不能继续要求托管原件复制失败后带警告发布"）✓ 合法
- `providerOutputTokens…MissingUsageStillFailsClosed`（"缺 usage 必须失败"）✓ 合法
- `reasoningUsageDoesNotReject…OversizeVisibleTextStillFails` / `largeModelWindow…OldByteCeiling`（旧 3500 正文上限）✓ 合法

**但以下删除无规格依据**，已由协调者逐一独立查证：

| 被删测试 | 违反的规格条款 | 独立复核结果 |
|---|---|---|
| `rejectsInvalidSourceSetsWithoutOccupyingSessions` | §5.2 要求**保留** start 请求标准化（2~5 个不同来源、主来源在集合内、标题与幂等键长度限制） | 新增 `idleRootAndDescendantsAreBothRequired` 只覆盖空闲检查，**不覆盖参数校验**；`../B`、null、重复来源、超长标题/幂等键均无断言 |
| `fifthSourceAndDescendantBusyRejectWithoutLeakingEarlierOccupancy`<br>`rejectsWaitingApprovalAndPartialAdmissionReleasesFirstSource` | §9"占用与释放"明列**根/第五来源/子任务/审批/后台服务**忙时拒绝 | 仅"根+子任务"保留；**审批（waiting_interaction）、后台服务（acquireBackgroundLease）、第五来源忙、部分准入回滚全部消失**；"无关聊天可用"与"来源可 deleteSession"也无断言 |
| `sameSourceToolIdsNeverEnterENativeHistoryAndENextMessageNormalizes` | §9 明令"保留并扩展原有…**工具 ID 隔离**…测试"；§8"不复制来源原生 tool calls 到目标" | grep 确认：merge 测试中 `toolUseId` **仅 1 处命中**（`MergePackageServiceTest:305`），且是 legacy 夹具输入，**不是隔离断言**；`MergePackageServiceTest:198` 甚至反向断言包内**含** same-tool-id |
| `slowCreationHookDoesNotRetainSourcesOrPreventAnotherMerge`<br>+ `uncertainCommitAndHookFailureCannotEraseCompletedTarget` 的 hook 半段 | §9"发布与取消竞态…**慢 hook**"；§8"通知 hook 失败不回滚已完成目标" | 新增 `uncertainCommitKeepsTheDurableTargetAndItsPackage` 只覆盖提交不确定，**慢 hook 与 hook 失败路径零覆盖**；"最多一个目标"仅间接体现 |
| `missingOperationReturnsStableNotFoundCodeWithoutChangingSourcesOrGate` | §5.3"GET 404 沿用 `MERGE_OPERATION_NOT_FOUND`" | grep 确认：该错误码 **main=2 处（`SessionMergeService:137/261`）、test=0 处**；`SessionMergeServiceTest:167` 的 404 只断言状态码不断言 code |
| `atomicHandoffPreservesSourcesBlocksWritesAndRetryReturnsSameTarget` 丢失的 4 项断言 | §1 要求保留"权限默认策略和最终请求预算校验"；§8"createSessionRecord(…现有权限默认)" | grep 确认：`AUTO_APPROVE` / `permission_mode` / `permissionMode` 在 merge 测试中**零命中**；同时丢失幂等键冲突、`totalUsage=zero`、`verifyNoInteractions(f.app)` |
| `diskFailureAndMessageCommitFailureDoNotLeaveEmptyE` | §9"保留并扩展原有**磁盘**…测试" | **协调者修正审查员的判断**：磁盘防护本身仍有覆盖（`MergePackageServiceTest:389/462` 断言 `MERGE_DISK_SPACE_LOW`），但"**磁盘失败不得留下空 E**"这一服务级原子性断言确实消失 |

另有 `MergeSummaryServiceTest` 侧丢失 `usesToolFreeIndependentCalls` 的 usage 汇总断言与 `stagedSummaryInputsRespectDiskReserve`。

**断言强度降级（非删除，但等效弱化）**

- 错误码断言降为异常类型：`SessionMergeServiceTest:81/124/137` 用 `isInstanceOf(Conflict.class)`，规格 §5.3 的 7 个错误码中仅 `MERGE_STALE_OPERATION` 有断言
- `SessionMergeServiceTest:80` 目标消息断言由"1 条 UserMessage + usage=0 + AUTO_APPROVE"降为 `COUNT(*)==1`
- 4,183 bytes 这一关键数字**降为注释**（`MergeSummaryServiceTest:57` 用 `"说明".repeat(750)`，无字节断言）
- `hasMessageContaining("来源会话")` → `("来源")`
- `SessionMergeServiceTest:60` await 由 10s→20s 且轮询条件放宽（建议加注释说明，避免掩盖挂起）

**未发现以下放宽手段**（这一点是好的）：`@Disabled` / `@Ignore` / `assumeTrue` / 注释掉的断言 / `catch(Exception ignored)` **全部 0 命中**；`frontend/vitest.config`、`package.json` 未被修改，两个前端测试文件 diff 中无 timeout 调大（`advanceTimersByTime` 均为既有行）。因此文档"未放宽断言或超时"在**超时维度成立**，在**断言强度与覆盖广度维度不成立**。

**测试卫生（符合规格）**：全部使用 `@TempDir` + SQLite 临时库 + mock provider，**无写入运行库**，无提交真实会话/附件。但并发类测试均为 `CountDownLatch` 阻塞单线程或纯顺序调用，**未构造真实线程竞争**（见 I8-coverage）。

#### B2 规格要求的 6 个冲突错误码，5 个测试命中为 0

协调者独立 grep 结果：

```
MERGE_ACTIVE_EXISTS         main=1  test=0
MERGE_SOURCE_BUSY           main=4  test=0
MERGE_STALE_OPERATION       main=3  test=1
MERGE_ALREADY_COMPLETED     main=1  test=0
MERGE_IDEMPOTENCY_CONFLICT  main=1  test=0
MERGE_CANCEL_CLEANUP        main=1  test=0
```

违反 §5.3"冲突沿用 `{error:{code,message}, operation?: 最新操作}`，区分 `MERGE_ACTIVE_EXISTS`、`MERGE_SOURCE_BUSY`、`MERGE_STALE_OPERATION`、`MERGE_ALREADY_COMPLETED`、`MERGE_IDEMPOTENCY_CONFLICT`；取消后写入器尚未退出返回 `MERGE_CANCEL_CLEANUP`"。

**且这不是纯粹的测试缺口——它对应真实缺陷**：见 I2，resume/create 竞态实际会退化成 HTTP 500。断言从精确错误码降级为 `isInstanceOf(Conflict.class)`，正好掩盖了这个 bug。

#### B3 `occurrences.jsonl` 全仓测试零命中

协调者 grep 确认 `grep -rn "occurrences" src/test/java/` **无任何输出**。

违反 §4.2（重复位置写 occurrences.jsonl 而**不是**灌给模型）与 §9"长历史与重复"（**checkpoint 重复位置不灌入 LLM**）。

这条是本次改造的**核心动机之一**：规格 §2.1 记录的线上失败中，11 个子会话的 58 个 checkpoint 去重后有 840 条约 4,826,471 字节，**另有 1,866 个重复出现位置**——正是这些重复位置灌进模型导致容量爆炸。如今这个机制完全没有断言保护。

#### B4 E 的状态隔离零覆盖

协调者 grep 确认 merge 测试目录中**无任何文件**提及 `FileStateCache` 或 `TodoWrite`。

违反 §8"`SessionManager.getFileStateCache(E)` 从空缓存开始，不 clone/merge A/B 的已读缓存"、"E 的发消息、压缩、待办/设置变更与删除不能回写 A/B"，以及 §9"接续资料与状态隔离"整行（E 使用独立 ID 和空文件缓存，E 发消息/压缩/更新待办或设置/删除不改 A/B 的对应状态，来源后续消息不进入 E 快照）。

其中"E 独立 ID + metadata"（`SessionMergeServiceTest:78`）与"来源后续消息不入快照"（`:100`）有覆盖，**空 FileStateCache 与不回写 A/B 完全无覆盖**。

#### B5 规格第 9 节强制的真实模型语义验收未执行

§9 要求一个具体的接续开发样本：A 将服务端响应字段从 `name` 改为 `displayName` 并记录验证结果；B 的前端仍读取 `name`，有未完成联调待办；A/B 使用同一临时工程，合并为 E 后要求其完成联调。另需 7 类人工标注样本（用户约束、子任务尾部失败、冲突双方、未验证自述、不同版本、重复合并、附件未知），以及"封存后共享代码又有变化"的变体。

仓库中**无任何脚本、夹具或记录**：`docs/zhikun/Session-Merge*Acceptance*` 均为 09-20 的 v1 旧版，不含 `displayName`。

文档自己承认"真实模型的人工语义样本尚未执行"，并明确"不得将 stub 的数据、隔离与接线断言描述成真实模型接续开发验收"。这是**发布前置人工验收缺口**，必须显式标注，不得以 stub 结果替代或掩盖。

#### B6 文档验证陈述不实（阻断文档发布，不阻断代码发布）

| 文档声称 | 实测 |
|---|---|
| 后端全量"2,905 通过、**0 失败/错误**" | **2,894 通过、11 失败，`BUILD FAILURE`，EXIT=1** |
| 前端"**876** 通过" | **894 通过** |
| "构建仅有现有大 chunk/Browserslist 提示" | 另有 empty chunk ×2（`editor`、`terminal`）、动态/静态混合导入提示 ×6（`stompClient.ts`、`sessionActivation.ts`、`MermaidBlock.tsx`、`GitTimeline.tsx`、`SchemaViewer.tsx`、`@monaco-editor/react`） |

**必须说明的限定：这 11 个失败与本次改动无因果关系。** 归因采用三重证据，非推测：

1. **隔离复跑**：只跑这 4 个类 → 仍 `Tests run: 39, Failures: 11` → 排除测试顺序/状态污染
2. **HEAD 干净基线**：`git worktree add /tmp/verify-head-baseline HEAD --detach`（**不含本次任何改动**）跑同样 4 个类 → `Tests run: 39, Failures: 11`，**类名+方法名逐一完全相同** → 排除本次改动引入
3. **代码级根因定位**：

| # | 类.方法 | 断言差异 | 根因 |
|---|---|---|---|
| 1-6 | `SecurityFilterIntegrationTest`：`noAuth_shouldReturn401` / `bearerToken_invalid_shouldReturn401` / `publicNetwork_shouldReturn403` / `urlToken_valid_shouldRedirect` / `bearerToken_valid_shouldPassAndIssueCookie` / `cookie_valid_shouldPass` | `expected:<401> but was:<200>`；`expected:<403> but was:<200>`；`expected:<REDIRECTION> but was:<SUCCESSFUL>`；Set-Cookie 为 null | 本机导出 **`ALLOW_PRIVATE_NETWORK=true`**。`RemoteAccessSecurityFilter.java:126-130`：`if (allowPrivateNetworkAccess) { chain.doFilter(...); return; }` → 对所有来源 IP 免认证放行，鉴权层被整体短路 |
| 7-9 | `CoordinatorServiceTest`：`matchSessionMode_same_mode_returns_null` / `matchSessionMode_coordinator_then_isCoordinatorMode_returns_true` / `matchSessionMode_normal_then_isCoordinatorMode_returns_false` | `expected: <null> but was: <Exited coordinator mode…>`；`isCoordinatorMode() should return false ==> expected: <false> but was: <true>` | 本机导出 **`ZHIKUN_COORDINATOR_MODE=1`**。`CoordinatorService.java:89-94`：`isCoordinatorMode()` 在 `runtimeEnv` 无值时回退 `System.getenv(...)` → 测试"默认非 coordinator 模式"的前提不成立 |
| 10 | `OneKeyRegistryIntegrationTest.genericLlmApiKeyDoesNotAuthenticateZhipuDashscopeMcp:137` | `expected: <> but was: <sk-9***-35***>` | 测试用 `new StandardEnvironment()`（自动包含真实 systemEnvironment），本机存在真实 `DASHSCOPE_*` key |
| 11 | `WorkspaceFileBoundaryTest.recursiveGrepSkipsProtectedFilesButDirectAccessCanBeAuthorized:269` | `Expecting actual: "" to contain: "directory-secret"` | 本机**未安装 ripgrep**。日志明确：`GrepTool - ripgrep not found, falling back to system grep` → 回退路径输出空串 |

四者及其被测生产类**均不在本次 diff 中**。

**总数 2,975 与跳过 70 精确吻合**，说明作者确实在同一测试集上跑过全量。前端 894 与 876 的差 **+18 可完全对账**：来自被排除的其他任务测试新增（`Sidebar.desktop.test.tsx +2`、`ImageBlock.test.tsx +5`、`MessageActions.test.tsx +5`、`messageContent.test.ts +6` = +18），而文档明说其验证"未纳入其他任务的 UI/消息复制变更" → **876 + 18 = 894** ✓。

**问题定性**：文档把**环境前提当成了默认前提而未声明**。这份文档会被提交到 GitHub，"0 失败"在任何一台带上述变量或缺 ripgrep 的机器上都不可复现，会误导 reviewer 与 CI。建议改写为：

> 后端全量 2,975 项：2,894 通过、70 跳过、**11 失败**——失败集中在 4 个既有非密封测试类（`SecurityFilterIntegrationTest` / `CoordinatorServiceTest` / `OneKeyRegistryIntegrationTest` / `WorkspaceFileBoundaryTest`），在**不含本次改动的 HEAD 基线上同样复现**，根因为宿主环境变量 `ALLOW_PRIVATE_NETWORK`、`ZHIKUN_COORDINATOR_MODE`、真实 `DASHSCOPE_*` key 与缺失 ripgrep，与本次改动无关。前端 103 文件：894 通过、16 跳过（876 为不含其他任务 UI/消息测试新增 +18 的口径）。

> **顺带发现（既有问题，非本次引入，建议单独立项）**：`OneKeyRegistryIntegrationTest` 的失败信息会把开发者**真实 API key 片段打印进 surefire 报告**（`expected: <> but was: <sk-9...>`）。这 4 个非密封测试类直读宿主 env、依赖宿主安装 ripgrep，会持续给所有开发者制造假红，属既有安全隐患，应优先处理。

---

### 4.2 重要（17 项，应在发布前或紧随其后修复）

#### I1 E 会话可被未分类异常永久打死（头号功能风险，协调者亲自验证）

`HandoffContextService.project()` 抛的是裸 `IllegalStateException`：

```java
if(cost(body,model,ratio)>limit) body=toolAvailable ? minimal : unavailable;
if(cost(body,model,ratio)>limit) throw new IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL");
var message=new Message.UserMessage(...);
int cost=Math.max(tokens.estimateTokens(List.of(message),model),cost(body,model,ratio));
if(cost>limit) throw new IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL");
```

**异常边界已亲自确认**：`execute()`（第 294 行起）**没有包裹轮次循环的外层 try**；第 841 行位于 783–799 的 try/finally（MDC 块）**之外**，841 之后下一个 try 起点在 **868** 行；`catch (LlmApiException e)` 在 1041、`catch (RuntimeException e)` 在 1163，都属于 868/998 之后的 LLM 调用块。→ 异常**直接穿出 `execute()`**，经 `GlobalExceptionHandler:138` 变成 **500 INTERNAL_ERROR**。

违反 §7.1"连最小入口和当前请求都放不下时**返回正常容量错误**，不把已完成合并改为失败"。

**更严重的两点：**

1. **`toolAvailable==false` 时没有任何降级路径。** 用户一旦限制工具集，`body` 只能是 `unavailable`（无法退到 `minimal`，因为 minimal 会让模型去调用不可用的工具），若 `cost(unavailable) > limit` 就直接抛。`limit = min(2048, max(0, totalBudget/10))`，`unavailable` 约 100 中文字 + 64 → 只要 `historyInputBudget ≲1640` 就会触发。**小窗口模型或大系统提示下，用户一次工具限制就能让 E 每轮 500**，违反 §7.2"若用户明确禁用工具，尊重限制并**提示**资料工具不可用"。

2. **`configure()` 的失败无法自愈。** `HandoffReadService:47` 在无绑定时抛 `AuthorizationException("HANDOFF_NOT_BOUND","当前根会话没有可用交接资料")`，`configure()` 在 ID 不符时抛 `IllegalStateException("HANDOFF_BINDING_MISMATCH")`；这两个调用点在 `QueryController:178/265/362` 与 `WebSocketController:959` 均**不在 try 内**（已亲自阅读 `QueryController:165-192` 确认无包裹）。合并记录消失或包损坏后，**E 的每个请求都 500，UI 无任何恢复入口**。

**设计不一致值得注意**：`project()` 在 `binding.isEmpty()` 时**优雅返回空投影**（`return new Projection(List.of(),0)`），而 `configure()` 却抛异常。同一个"绑定不存在"条件，两条路径语义相反。

**修复建议**：统一为降级语义——投影失败返回空投影 + 一条提示消息（可复用 `emitImageNotice` 式通道），或包装成 `LlmApiException(...,413)` 让其进入既有 413 处理；`configure()` 绑定失败降级为"本会话暂无交接能力"而非终止请求。

#### I2 resume/create 竞态退化为 500 而非 409

`MergeProgressRepository:108-118` 的 `changed()` 抛 `IllegalStateException("MERGE_STALE_EXECUTION")` → `GlobalExceptionHandler:138` → **500 INTERNAL_ERROR**。违反 §5.3"并发重复 resume 只有一个成功，**其余返回当前状态**"（应为 409 + `operation`）。

同类：`create` 撞唯一索引抛 `DataIntegrityViolation` → 500 而非 `MERGE_ACTIVE_EXISTS`（`SessionMergeService:105`）。

这正是 B2 中错误码零断言所掩盖的缺陷。**修复**：在 Service 层捕获并转 `Conflict`。

#### I3 brief 阈值未扣 header，可能整份丢弃聚合成果

`MergeSummaryService:91` `if(capacity(brief)<=1536) break;` 与 `:145` `if(capacity(body)>2048) body=header;`。

header 含来源 ID 列表 + 栏目统计，可达数百 token：1500 token 的 brief + 600 token header = 2100 > 2048 → **整份 brief 被丢弃**，退化为纯目录。

结果符合 §6.2 的兜底语义（"就用程序生成来源/栏目/状态计数和详情入口"），但**浪费了全部聚合调用的费用与时间**。

**修复**：`1536` 改为 `2048 - capacity(header, model)`。

#### I4 `importPrevious` 目标路径缺包含性校验

`MergePackageService:643-654`：`relative=Path.of(entry.path())` 仅判 `startsWith("snapshot")`，随后 `root.resolve(Path.of("snapshot").relativize(relative))`。

实测 `snapshot/sub/../../evil` 能通过 `safeFile`（normalize 后为 `evil`，不以 `..` 开头），relativize 得 `../evil`，dest 逃出 `staging/` 落到 `<operationId>/evil`——**含 `<operationId>/snapshot/*`，可致后续原子 rename 失败**。

**判定：不可被利用**——前提是篡改一个已通过 `validateSnapshot` + DB `snapshot_hash` 校验的前序包，即攻击者已具备本地写权限。但违反 §4.2"禁止路径穿越"的纵深防御要求。

**修复**：加一行 `destination.normalize().startsWith(root.normalize())` 断言。

#### I5 丢弃 recordRef 导致暂停后永久不可恢复

`MergePackageService:435-436`：`exportMessage` 的 catch 里 `record(...,"blocked")` 返回的 ref **被丢弃**，写入裸 `RECORD_REQUIRES_HANDLING`；而 `recoverBlockedProjections:233-238` 要求 `RECORD_REQUIRES_HANDLING:<recordRef>` 且属于 blockedRecords → 裸原因**必然抛错** → 该操作只能 cancel。

同类在 `:571`（`exportAsset` 解码失败用 extract 策略记录的 ref，不在 blockedRecords）。

方向是 fail-closed（安全），但违反 §4.4"保留其 raw 文件、可读记录头及已完成单元，转 paused/RECORD_REQUIRES_HANDLING"的**可恢复性意图**——规格设计这个状态就是为了封存后修正解析配置能恢复。

#### I6 HandoffRead 错误码归类错误

`HandoffReadService:55、206-209`：verify 阶段所有 IOException 统一转 `AuthorizationException("HANDOFF_INVALID_RESOURCE")`，**超时/中断与哈希不符同码**（`InterruptedIOException` 已单独放行）。

违反 §7.2"校验未完成或整体超时明确报 `HANDOFF_READ_BUDGET_EXCEEDED`，不跳过哈希"与 §7.3"无绑定、未知 ref、包损坏**分别**报明确错误"。

测试用 `hasMessage` 断言，恰好掩盖了 code 差异。

#### I7 `descendants()` 静默吞 IOException

`MergePackageService:100-113` 的 `catch(IOException){}` 使 metadata 损坏的子会话**及其全部后代**整体不入快照、且**不写 gaps**。

违反 §4.1"未保存、已裁剪、缺少 checkpoint 的子任务过程明确记入 gaps，不冒充完整记录"——这是**静默数据丢失**，比报错更危险，因为用户会以为资料完整。

#### I8 `Path.of(asset.originalPath())` 未捕获 `InvalidPathException`

`MergePackageService:458-463`（legacy 路径 `:831/840/845/859` 均有捕获，此处遗漏）。

`originalPath` 源自消息文本 `collectReferences`，含 NUL 或非法字符即抛未检查异常 → **整次 seal 转 paused**，而非记一条缺口。违反 §4.4 的"既有缺失附件/外部链接记录缺口"。

#### I9 `project()` 非纯函数，E 每轮 ≥3 次全包完整性校验

`HandoffReadService:110-113` 的 `brief()` = `binding()`(DB 查询) + `verify()`（ready.json / snapshot manifest / recovered seal **三次哈希**）+ 读文件。

调用点：`QueryEngine:841`、`:844→1835`、`:779→1835`，恢复路径再叠加。

**仅影响 E**（普通会话在门控处早退），但每轮 3× DB + 3× 哈希是明显冗余。**修复**：按 `operationId+handoffHash` 每轮缓存一次 Projection，或让 `prepareCompactionContext` 复用主循环已算出的 `reservedTokens`。

#### I10 前端仍以 `preparing` 推断来源锁定

`sessionMergeStore.ts:30` 虽已改读 `lockedSourceSessionIds`，但仍以 `status==='preparing'` 为**前置条件**。

违反 §5.3"前端不能从 preparing 推断来源锁定"与 §8"来源是否锁住**只看** lockedSourceSessionIds"、"封存后即使 preparing 也允许来源使用/删除"。

**后果**：cancel 后 writer 仍在清理、未封存即 paused、或从 localStorage 恢复的旧记录（缺 `lockedSourceSessionIds` 字段）时，**服务端仍持锁而前端判定"未锁"** → 用户发消息/删除被拒且无解释。

**修复**：`return pending?.operation?.lockedSourceSessionIds ?? NO_MERGE_SOURCES`。

#### I11 前端 DTO 可选性放宽导致门禁失效

`store.ts:16-19` 把 8 个后端**必返**字段全标为可选，`protocolVersion?: number` 而非 `1|2`。字段名本身与后端 `SessionMergeService:37-41` 的 Operation record 及 `MergeHandoffData:29` 的 progress 三字段**完全对齐**（这点是对的）。

**后果**：

- `panel:78/214` 用 `targetAvailable === false` 判断 → **`undefined` 被当作"可打开"**，违反 §8"只有 completed 且 targetAvailable 才能打开目标"
- `store:228` 的 `expectedEpoch: runEpoch` 若缺失会序列化成 `{}`，使 §5.3 的 epoch 防重放机制失效

**修复**：按 §5.3 精确标注必选，门禁改 `!== true`。

#### I12 前端用 `submitting` 禁用恢复/取消按钮

`panel:222/225/186`：`submitting` 在**每 2s 轮询期间均为 true**（超时上限 15s，`store:149`）→ 恢复/取消/开始合并按钮间歇性不可点、点击无任何反馈。

store 已用模块级 `inflight` 串行化，应改用独立的 control-in-flight 标志。

#### I13 前端 Panel 新增 UI 基本无测试覆盖

`SessionMergePanel.test.tsx` 只做了 4 处适配（fixture 加 `lockedSourceSessionIds`、`/active` 返 204、两句文案），对**单元数与 `totalFinal` 文案、暂停原因、恢复（含模型下拉）、取消、`targetAvailable` 禁用、"目标会话已删除"零断言**。

`store.test.ts` 新增 117 行覆盖了 204、封存后不锁(`:418`)、不改写 preparing(`:410`)、写失败(`:282/305/319`)、多 tab(`:12/87`)、串行化(`:487`)，但**缺 §9 明列的"旧 resume 不重放"**（resume 返回 409 `MERGE_STALE_OPERATION`+operation 后刷新状态）、409 采用 operation、锁集合变化 dispatch、protocol=1 只读用例。

#### I14 Repository 反向依赖 + 双实例

`MergeProgressRepository:177` 的 `finishAttempt(..., MergeSummaryService.CallUsage)`，而 `MergeSummaryService:63` 又以 Repository 为参数 → **两类互相编译依赖**。违反 §10"Repository 只依赖 JDBC/事务/序列化""避免循环依赖"。

**修复成本极低**：把 `CallUsage` 移入 `MergeHandoffData`（其定位就是"共用 records/enums"）。

另：`SessionMergeService:72` 用 `new MergeProgressRepository(jdbc,json,transactions)`，而该类标了 `@Repository` → **Spring 另建一个 bean** 注入给 `HandoffReadService`/`HandoffContextService`。当前无状态故行为一致，但注解名不副实、编排侧无法注入测试替身。修复：改构造器注入（`MergeSummaryService` 不依赖 Service bean，无循环风险）。

#### I15 暂停日志丢弃异常栈，线上不可运维

`SessionMergeService:219` 只记 `code` + 异常类名，而 HEAD 是 `log.warn("Merge preparation failed: {}", operation, failure)`。

规格 §2.1 的整个失败排障正是**依赖 app.log 的堆栈**（文档引用了 `log/app.log:19692`、`:19788` 的堆栈行）——现在无法定位根因，等于自废了下次排障的能力。

**修复**：非预期 code 时把 throwable 一并交给 logger（仍不打印正文/凭据）。

#### I16 清单单行无长度上限

`HandoffReadService:141-149` 逐字节写入 `ByteArrayOutputStream`，仅靠 15s 截止兜底。一个哈希自洽但超大的损坏行可在截止前占用大量堆。非模型可控，属稳健性风险。

#### I17 `read()` 对 `action=asset` 返回假成功

落入 else 分支返回 `entries:[], complete:true`。当前 `HandoffReadTool` 会先拦截 asset，但服务层契约仍应收紧。

---

### 4.3 建议（可选）

#### 后端

- **预算常量与文档不符**：`MergeSummaryService:212` 请求 bytes 保护是 **900 KiB**，规格 §6.3 表格写的是 **1 MiB**。更严格但与文档不符——改常量或改文档，二者取一。
- **attempt 终态早于校验**：`:324` 的 `usageSink` 在 `call()` 的 finally 里就写 `finishAttempt(...,"completed")`，随后 `validate()` 若失败，attempts 表仍记 completed。建议改记 `error` 或新增 `rejected` 语义。
- `QueryEngine:2698-2702` `preCleanImageHistory` 自算 `inputBudget` 并调 `enforcePhase1`，**未预留投影**。无正确性风险（循环内 `:847` 会用正确 `historyBudget` 重裁），但与 §7.1 第 4 条"使用同一规则"精神不一致。
- **`isExplicitCoreTool` 的名字放行是多余的**：`OperationAnalyzerRegistry` 中新增 `"HandoffRead".equals(name)`，唯一调用点是 `ToolRegistry:48` 的 `AUTHORIZATION_ANALYZER_MISSING` 启动断言，而真实 `HandoffReadTool` 永不进该构造器。副作用是任何 `com.aicodeassistant.*` 且名为 HandoffRead 的工具可绕过该启动断言（其 `analyzerFor` 仍返回 `generic`，**不获得权限**——所以不是安全漏洞）。建议删除。
- **风格倒退**：`QueryController:72-73`、`WebSocketController:92-93`、`QueryEngine:1808-1809` 使用内联全限定类名 + `@Autowired` 字段注入，插在 `private final` 之间，破坏两个 Controller 既有的显式构造器注入风格；测试须靠 `ReflectionTestUtils.setField`（`QueryEngineUnitTest:1956`）；且 `new QueryController(...)` 的测试中该字段为 null，将来若出现合并会话用例会 NPE。`QueryEngine` 用 `required=false` 而 Controller 用必需注入的不一致虽不构成启动风险（Bean 是 `@Service`，上下文已验证可加载），仍建议统一为构造器参数。
- `SessionMergeController:8` 类级 `@RequestMapping(produces="application/json")` 给**既有两个端点**加了内容协商约束：严格 `Accept: text/html`（不含 `*/*`）会 406。实测无回归（自家前端 `sessionMergeStore.ts:150-155` 带 `Accept: application/json` 或不带，MockMvc 用 `*/*`），但**零收益**，建议删除或只标在新端点上。
- **死代码约 250 行**：`build()`(`:698`)、`packagePath()`(`:95`)、`MAX_RECORD_BYTES`(`:71`)、`exportRuns`、`renderBlock`、`PartWriter`、`writeParts`、`canonicalReference`、`Bundle`、`deleteUnreferenced` 的 scratchpad 分支，**仅被 `MergePackageServiceTest` 调用**（`:18/212/247/259/278/293/309/326/336`）。规格允许"仅为兼容已有夹具保留"，在线入口确实不再调用（已验证只走 seal / validateSnapshot / recoverBlockedProjections / snapshotPath / packageRoot），但把 250 行生产死代码推到 GitHub 是技术债。另：`SessionMergeService:67` 构造参数 `PermissionModeManager permissions` 已无字段接收，import 行 9 与 `java.time.Instant`（行 28）未使用。
- `MergeTextBudget.atomicWrite` 缺 `force(true)` 与父目录 fsync → 断电后 `work/recovered/seal.json` 可能丢失；`finally` 中 `deleteIfExists` 自身异常会掩盖原始异常。（其余方面正确：临时文件 `resolveSibling` 同目录同文件系统；走 `output()`→`reserve()` 接入既有磁盘防护；move 成功后 deleteIfExists 为 no-op、失败时清垃圾。）
- `seal()` 在 rename 前未按 §4.3 步骤 5 复校 manifest/seal（依赖 `SessionMergeService:203`，而此时来源已释放）。建议在 staging 内先自检。
- **残留未登记文件**：内嵌图片解码失败留下的 `assets/<uuid>`（`:521-527`）、空 `assets/files/` 目录 → 与 §4.2"目录展示由清单生成"矛盾，建议清理。
- `handoffOrigin` 主代码**无写入点**（`:499` 仅读取）；跨包身份实际靠 records.jsonl 逐行导入 + origin 确定性哈希达成，建议补注释或写入点。
- `HandoffReadTool:16` 工具说明缺 §7.1 要求的三句（继续来源工作前先读缺失的交接详情；目录/未展开计数不代表已了解详情；历史测试不代表当前测试；改代码前先读现有文件），目前部分由结果 warning(`:302`) 与投影文本承担。
- `HandoffReadTool:35-37`：`ImageIO.createImageInputStream` 返回 null 时 `getImageReaders(null)` 抛 `IllegalArgumentException`，**不被 `catch(IOException)` 捕获**。
- `HandoffReadService:224`：`limit` 仅在为 `Number` 时生效，字符串 `"10"` 静默降级为 5。
- `HandoffReadService:270`：`e.path().substring("snapshot/".length())` 无 `startsWith` 守卫。
- `SessionMergeService:106` promptVersion 硬编码 `"handoff-v2-1"`，与 `PROCESSOR_VERSION` 同值重复，提示词升级易漏改，建议提为常量。
- `SessionMergeService:272` `progress.cleanupCandidates()` 在 try **之外**：DB 不可读时会从 `execute()` 的 finally（可能掩盖原异常）与 `recover()`（`@PostConstruct`，**可阻断启动**）抛出，与 §4.4"数据库不可读则保留"意图不符。
- `SessionMergeService:287` `worker.shutdown()` 未 awaitTermination → `@PreDestroy` 返回后 writer 仍可能写文件/查库（DataSource 或已关闭）。
- 规划阶段每分片一次写事务（`MergeSummaryService:79` → `Repo.plan`）→ 大历史下上千次 SQLite 写事务，建议批量。
- `MERGE_SERVICE_SHUTDOWN`（`SessionMergeService:82`）不在规格列举的错误码内，需确认前端按通用提示降级；cancel 与发布事务相撞时 SQLite busy 可能抛 500，可考虑一次重试。
- v2 无任何路径写 `status='failed'`（哈希不符也只 pause）→ 损坏包会**长期占用唯一名额**直到用户 cancel。符合规格（§5.1"failed：已确认不可恢复的损坏"），但建议在 `explain` 文案中明确"确认不可恢复请取消"。

#### 前端

- **轮询无退避**：固定 2s（`panel:47`），`document.hidden` 时仍空转；无任务时每 5s 打 `/active`；`failed && canCancel` **永不进** `validatedTerminal`（`store:187-189`）→ 直到用户取消前一直 2s 轮询。建议 hidden 暂停 + 错误/终态退避。
- `store.ts:113` `validatedTerminal` 模块级 Set 只增不减（仅 openDialog 删当前 id），轻微泄漏。
- `errorCode` 已入类型但**从未使用**；建议对 `MERGE_CANCEL_CLEANUP`（稍后重试）、`MERGE_STALE_OPERATION`（自动刷新）做 code 分支——目前 6 个 code 中只有 `MERGE_OPERATION_NOT_FOUND` 有分支（`:161`）。
- `store.ts:241` `await response.json()` 无 `.catch`（对比 `:159` 有）→ 网关返回 HTML 时把 `Unexpected token <…` 当错误文案直接展示给用户。
- `panel:206` 直接渲染 ISO 时间戳；`panel:210-211` 的 `result.warnings` 在 v2 后端**已不产出**（`SessionMergeService:214` 只写 copiedCount/warningCount/model/title/indexPath）→ "未收录 N 项"永远无明细，建议改为指向 gaps 或移除该分支。
- 前端**从不读取 `protocolVersion`**，缺 v1 只读兜底与"旧版结果"文案（§8 要求 protocol=1 只读展示、不伪造 v2 绑定/恢复能力）。当前靠服务端 `canResume/canCancel/targetAvailable` 驱动（后端 v1 时 canCancel=false）间接达成，但没有显式兜底。
- 锁集合变化触发 `session-list-updated` **只在主轮询路径**（`:196-199`），冲突采用（`:171-176`）与 404（`:163-169`）路径缺失。
- **耦合检查通过**：`isMergeSource`/`selectMergeSourceIds` 签名与返回稳定性未变，`App.tsx:86/269/346`、`Sidebar.tsx:542` 消费正常（`sameProgress` + `NO_MERGE_SOURCES` 保证 selector 引用稳定）→ 本次改动未破坏被排除文件的消费方。

#### 文档卫生

- `docs/zhikun/` 下有 **7 份 09-20 的 v1 时代 Session-Merge 文档**（`Session-Merge-Five-Source-Acceptance`、`Session-Merge-Live-Acceptance`、`Session-Merge-Full-Acceptance`、`Session-Merge-and-Inject-Review`、`Session-Merge-and-Inject-Simplified-Review`、`Session-Merge-and-Inject-Implementation-Plan`、`Session-Merge-MVP-Implementation-Notes`），将与声称"本文替代此前更复杂的 v2 草案"的 v2 文档一起发布到 GitHub。建议标注 superseded 或移入归档目录。
- `docs/session-merge-architecture-v2.md` 权限是 `600`（`-rw-------`），`README_EN.md` 同样。git 只跟踪可执行位，提交后会变 644，**不构成问题**，仅提示。

---

## 五、已确认正确实现的部分

为避免只报忧，以下是**经证据核实、符合规格**的关键实现。这些是本次改造的实质成果，也是"可以有条件发布"这个判断的基础。

### 5.1 迁移与持久化

- **附录 A 的 SQL 与 `V026_ExtendSessionMerges.java:13-104` 规范化后 82 行 vs 82 行、零差异**。`active_slot IS 1`（`:35-39`，未被换成会让 NULL 误通过的写法）、部分唯一索引 `uq_session_merges_active ... WHERE active_slot=1`（`:58`）、`preparing→failed/LEGACY_INTERRUPTED` 的 CASE（`:47-53`）、旧行 `protocol_version=1`（`:52`）全部原样。
- 事务性由 `MigrationRunner:87-110`（`executeWriteVoid` + `tx.executeWithoutResult` 内 execute→validate→记账）保证，中断整体回滚。
- 幂等：`execute()` 仅在缺 `protocol_version` 时重建、否则只 validate（`:106-111`）。
- `validate()` 复用 `new V024_CreateSessionMerges(jdbc).validate()`（`:118`）保住 V024 全部列名，并额外校验 4 个索引、两张子表 FK+CASCADE、约束文本（`:124-136`）。
- **未修改已执行迁移**：`git diff config/database/` 为空，V024/V025 checksum 未变；无其他 schema 对象引用 `session_merges`。
- **单一未结束名额**：`MergeProgressRepository:67-75` 直接 INSERT `active_slot=1`，由部分唯一索引原子占位（**非** COUNT+INSERT）；旧内存 `Semaphore generation` 已删，`writers` map 只防本机重复 worker（`Service:59、130-135`）。
- **run_epoch 单一代次**：create=1；resume/cancel 递增（`Repo:111、122`）；普通进度不递增，且 stage/sealed/retryAt/plan/beginAttempt/commitUnit 全带 `run_epoch=? AND status='preparing'`（`Repo:83-105、146、165-176、201-208`），complete 另加 `stage='publishing' AND snapshot_hash IS NOT NULL`（`:127-133`）。
- **封存即释放**：`Service:190-194` `seal → sealed(单条 UPDATE) → 立即 Token::close + leases=List.of() + notifyChanged`，之后才开始整理；所有写事务内只有 SQL，**无跨文件复制或 LLM 调用**（符合规格明令）。
- **单元三步提交协议**：`beginAttempt`（短事务，`Repo:165-176`）→ 无事务 `call` + `disk.atomicWrite`（tmp+ATOMIC_MOVE）→ `commitUnit`（assertCurrent + `run_epoch=? AND state='running'`，`Repo:201-208`）。split 父子同事务（`Repo:210-221` 内嵌 `plan`，PROPAGATION_REQUIRED 复用），父置 split 并写 childUnitIds；发布前逐单元要求 completed（`MergeSummaryService:121-122` `MERGE_INCOMPLETE_UNITS`）；进度统计排除 split 父项（`Repo:222-229`）。
- **发布单事务**：`Service:205-217` 内 createSessionRecord + `metadata_json.sessionMergeOperationId` + 交接入口消息 + complete；`notifySessionCreated` 在事务外且 try/catch（`:234-235`），`SessionManager:236-239` 自身也吞异常 → **hook 失败不回滚**；提交不确定时以 DB 复核 completed + session 存在（`:221-223`）。
- **cancel/resume 竞态**：cancel 无版本参数、覆盖 preparing/paused/failed、cancelled 幂等、completed→409（`Service:146-153`），先改库再 abort 再由 writer 收尾清目录；resume 仅 paused + expectedEpoch + `writers` 去重（`:136-144`）；`MERGE_CANCEL_CLEANUP`(`:92`)；cancel 与发布互斥（complete 要求 preparing；`cleanupCandidates` 的 completed 分支要求目标不存在，`Repo:53-60`）→ **不会删已发布目标**。
- **状态机纯净**：全仓无 `retry_wait`/`needs_attention`（规格删减项）；`execution_json` 仅 5 字段（`MergeHandoffData:24-25`）；进度由 units 统计、退避用操作行 `retry_at`（`MergeSummaryService:251/257`），**无第二份进度游标**。
- **包清理**：paused/failed 不在候选；`deleteUnreferenced` 只删本操作目录并做 packageRoot 父目录 + `requireOperationPath` 双校验（`MergePackageService:1112-1121`），**无引用计数**（规格明令禁止）；启动 `recover()` 补做清理（`Service:79`）。
- **资料目录**：`packageRoot` 由 `DatabaseResolver.getProjectDbPath(user.dir).getParent()/session-merges` 派生（`MergePackageService:87-98`），`UUID.fromString` 防穿越；**非 scratchpad、非硬编码 cwd**。
- **依赖方向**：SessionManager 未依赖 MergeService；ReadService/ContextService 依赖 Repository，Repository 不依赖 QueryEngine/ToolRegistry（唯一例外见 I14）。

### 5.2 安全边界（5 项高危全部"不可被利用"，含测试实证）

| 项 | 结论 | 依据 |
|---|---|---|
| 路径穿越 | **不可被利用**（写侧存 1 处纵深缺口，见 I4） | 读侧 `ref` 仅作清单查找键、**从不拼路径**（`HandoffReadService:161-180` 全量扫 catalog 匹配后取清单内 `path`）；`safeFile`（`MergePackageService:304-316`）拒绝对路径 / `normalize().startsWith("..")` / NUL（`Path.of` 抛 InvalidPathException 被捕获）/ 逐级 `isSymbolicLink` / 非普通文件；写侧文件名仅 `r_<sha256>`(`:369`)、`a_<sha256>`(`:580`)、`UUID`(`:521`)、`:pN`(`:686`)，**无标题或模型输出参与**。测试 `HandoffReadServiceTest:66-73` 实测 `../../secret`、`/etc/passwd` 被拒 |
| symlink | **不可被利用** | `safeFile` 逐级查链接；`hash()`(`:105`) 与 `catalog()`(`:138`) 均 `NOFOLLOW_LINKS`；附件复制**不用** `Files.copy`，而是 `isRegularFile(NOFOLLOW)` + `newInputStream(NOFOLLOW)`(`:1058-1064`)；`copyTree` 对范围外链接只记 `external_reference`(`:1035-1041`)；legacy 包链接记阻断缺口(`:614`)。测试把已登记文件换成指向包外 secret 的 symlink，断言拒绝 |
| 游标伪造 / 跨包越权 | **不可被利用** | key = `sha256(operationId + handoffHash + encode(action,query,ref,sourceId,section))`(`:213`)，不符即 `HANDOFF_INVALID_CURSOR`(`:222`)；`catalog:129-137` 校验索引范围、偏移前一字节必须 `\n`、不得超文件大小；游标不含路径，偏移只作用于 `binding()` 已解析的本包路径 |
| 全局工具泄漏 | **不可被利用** | `HandoffReadTool` 无 `@Component`/`@Service`；全仓唯一实例化为 `new`（`HandoffContextService:31`）；`ToolRegistry:44-57` 仅注册 Spring 注入的 `List<Tool>`，无类路径扫描；仅 `configure()` 在 metadata 含 `sessionMergeOperationId` 且过 allow/deny 时追加 |
| 绑定校验 | **已符合** | `subjects.resolve(currentRunId).rootSessionId()`(`Tool:29`)；`binding()` SQL 要求 `protocol_version=2 AND status='completed' AND target_session_id=?` JOIN sessions；读取前 `verify()` 按 `handoff_hash` 校 `ready.json`(`:69-73`)，模型无法选择 session/operation |

**`ImageRefInjector` 的绕过是窄范围且安全的**（协调者重点复核项）：`HandoffReadService.isBoundAsset:182-194` 五项齐备——① rootSessionId 来自 `subjects.resolve(context.currentRunId())`（`HandoffContextService:63-66`，**非模型输入**）；② 必须命中清单 `kind=="asset"` 条目；③ `e.sha256().equals(hash)` **且** `hash(safeFile(...)).equals(hash)` 实盘重算；④ 路径用**清单内的 `e.path()`** 解析后 `equals(requested)`，`../` 无法构造成登记路径，`safeFile` 拒绝绝对路径、`..` 逃逸、逐段 symlink、非常规文件；⑤ 绕过的**仅是** `ImageRefInjector:269` 的路径检查，后续 `:275` isRegularFile、`:281-291` 大小 + `fileSize` 一致、`:298` SHA-256 重算、`:305+` MIME/base64、调用方 `budgetLeft`/`dataLen` 硬限**全部照常执行**。符合 §10"仅为当前可信根会话绑定包中已登记且哈希匹配的图片增加读取入口；普通路径权限不变"。

**`AuthorizationService` 那一行改动**（协调者复核）：括号正确、`file-v1` 判定与改动前完全等价；`safeRead`(`:158`) 位于 `PLAN` 拒绝(`:166`)**之前** → 含 PLAN 模式 ✓（符合 §7.3）；`SAFE_INTERNAL`(`OperationAnalyzerRegistry:42-44`) 未含 handoff ✓（符合 §7.3 明令）。`analyzerFor:87` 先判 `isMcp()` 再判 `instanceof HandoffReadTool`，同名 MCP/动态工具只会拿到 `mcp`/`generic` 分析器，`analyzerId` 不等于 `handoff-read-v1` → 不进 `safeRead`，符合"不允许同名 MCP/动态工具继承权限"。

### 5.3 资料包与读取

- **目录结构与规格树全部齐备**：staging/、snapshot(manifest、4 个 jsonl、raw/text/assets、seal)、work/<unitId>/<attemptId>（`MergeSummaryService:218`）、handoff 全部 7 项（`:127-152`）。
- **原子封存**：`MergePackageService:157-174` 先写 staging，写 manifest+seal 后 `Files.move(staging,sealed,ATOMIC_MOVE)`，两者同为 `directory` 子目录 → **无跨文件系统风险**；已存在 snapshot 时走 `validateSnapshot` 幂等。
- **recordRef 哈希**：`:369` 完整 SHA-256(origin)；`messageVersion:337-343` 移除 `handoffOrigin`；usage 存于 `input_tokens/output_tokens` 独立列且 meta_json 无 UPDATE 路径，复制时间不入 meta → **排除项满足**；不可解析记录用 `raw-<原始字节哈希>`(`:399-402`)。
- **去重**：同 ref 跳过并 `Files.deleteIfExists(raw)`(`:371`)，每个位置写 occurrences(`:370`)；无 UUID 时 `inferred-<digest>` 且 origin 含 sessionId → **天然限同一来源**、标记 `identityInferred`(`:552-556`)；**未跨来源按文本去重**（符合规格）。
- **分片**：`TextParts.append:678-684` 按**码点**累加 UTF-8 宽度、超 32768 flush、**孤立代理对抛 `SOURCE_TEXT_INVALID_UTF16`** → 不切断多字节字符。`MAX_RECORD_BYTES`(`:71`) 仅存于 legacy `build/exportRuns`(`:732/769/875/878`)，v2 用 `columnReader` 64 KiB 分页 + `maxRecordMaterializeBytes`，超限转 blocked+gap → **16 MiB 终止规则已移除**。
- **托管产物**：`:441-450` 按 manifest_id/artifact_id 键集分页，`exportRow` 用 `PRAGMA table_info` 全列导出，**无验证调用**；`copyFile:1067-1071` 前后校验 size/mtime/双哈希（最多重读一次）；`CopyLimitException` → `copy_failed` → `exportAsset` 抛 `MERGE_COPY_INCOMPLETE`(`:576`)，**不降级为 external_reference**（符合 §9"旧 CopyLimit 测试不能继续要求托管原件复制失败后带警告发布"）。
- **gaps 语义**：`:568-573` 未保存/已裁剪 checkpoint 记**非阻断**；损坏 checkpoint 记**阻断**且 `recoverBlockedProjections` 不被空结果放行。
- **HandoffRead 限额与截止**：16 KiB 包装上限(`:305`)、`SEARCH_BYTES` 8 MiB(`:257`)、到界 `complete=false`+游标、单一 `budget()` 覆盖校验/目录/正文、`check()` 同时查中断与 15s、**超时不跳过哈希**（verify 先行）。
- **搜索正确性**：字面 `indexOf` 支持中文/路径/标识符；`carry` 保留 `query.length()-1` 重叠并处理低代理对(`:268-271`)；相邻分片前缀拼接(`:273-296`)；**按字符读不按行** → 超长单行尾部不漏；`complete=true` 仅在 catalog 全扫完（测试 `:129-190` 实证跨片、代理对、8 MiB 尾部命中）。
- **asset**：复用 `ImageResultExternalizer` + `withMetadata("type","image_ref")`，与 `FileReadTool:164` 同一既有约定；不支持类型返回 `HANDOFF_ASSET_UNSUPPORTED` 明确文案，**未用 base64 页伪装**。

### 5.4 模型预算（本次改造的起因，已彻底修复）

线上失败根因是 `SUMMARY_EXCEEDS_BUDGET: visibleUpperBound=4183, limit=3500, totalOutput=13370` —— 旧逻辑把 `max(估算token, UTF-8字节)` 和含推理的总 output usage 混用，且中间摘要与最终启动消息共用 3500 上限。核对结果：

- 全仓**无** `SUMMARY_EXCEEDS_BUDGET` / `3500` / `visibleUpperBound`；`capacity()` 是纯 token 估算、无 UTF-8 取大。
- **五种计量单位分离**：存储 bytes（`disk`）／请求 bytes（`:212`）／估算输入 token（`:212`）／可见输出 token（`generationBudget:54-56`）／provider usage 含推理（`CallUsage.usageReported`）。
- **默认值全部命中规格表**：单请求资料目标 16384、可见输出与 brief 各 2048、响应 bytes 256 KiB、单调用超时 300s、同单元同模型最多 3 次退避 2s+5s 且 `Math.max(delay, api.getRetryAfterMs())`、输入缩小下限 256 估算 token。
- **推理预留**：`supportsThinking` 时 `min(32768, window/4)`；安全量 `Math.max(1024, window/20)` = 5%。
- **无 16 次总调用上限、无 15 文件上限、无 10 分钟整任务截止**，沿用 `AbortContext`/`LlmCallContext` + `check.run()`。
- **usage 缺失** → `estimatedCostUsd=null` + `usageReported=false`，**不失败、不写零费用**（符合"usage 缺失记未知，不能写成零费用"）。
- **6 个 section / 9 种状态**与 `MergeHandoffData:16-19` 完全一致；evidence 限 `i[1-9][0-9]*` 别名，偏移由程序追加（模型不填字节偏移/路径/哈希）。
- **错误处理表逐条落实**：413/length/响应超限 → `split` 二分带 overlap；JSON 错 → attempt0 带错误说明修复、attempt≥1 缩小、256 下限 `MERGE_MIN_UNIT_FAILED`；`isRetryable` → 退避后 attempt2 抛出转 `PROVIDER_UNAVAILABLE`；`!isRetryable`（凭据/配置错）→ 直接抛出转暂停。
- 旧 `summarize` 已删除，`capacity`/`call` 为复用**非死代码**。
- `HandoffReadService.brief()` 与投影上限 `min(2048, totalBudget/10)` 符合 §7.1（间接由 `HandoffReadServiceTest:81` 的 `reservedTokens<=500` 体现，建议直接断言公式）。

### 5.5 前端

- `tsc --noEmit` **0 错误**；lint 0 error / 4 warning，与 HEAD 基线**逐条相同**（用 `--stdin` 对比验证）→ **未引入新告警**。
- **204 短路位置正确**：在 `!ok` 分支之后、`json()` 之前（`store:183`，204 属 ok 故不会解析）→ 不会把无任务当错误。
- cancel `body: undefined` 无版本参数（`:239`，测试 `store.test:447` 断言）；resume 带 `expectedEpoch`（`:238`）且 409 时用 `body.operation` 覆盖本地并 dispatch 列表更新（`:243-247`）后再报错，不静默、不自动重放。
- **`restore` 不再强改 preparing**（`:71`，旧逻辑已删），网络态独立用 `submitting`/`error`（测试 `store.test:410`）。
- `inflight` 模块级串行化生效（测试 `store.test:487` 验证旧 GET 不覆盖 epoch=8）。
- **无 pending 时 catch 直接 return、不弹"合并失败"**（`:203-205`，测试 `store.test:450/469`）→ 符合本轮复审修正。
- 进度展示：六阶段齐备（`panel:14`）、`!totalFinal` 只显示"（总数随分片增加）"、**全文无百分比**（`:205`）、暂停原因 role=alert(`:207`)、retryAt(`:206`)、恢复/取消入口(`:217-225`)。
- `closeDialog` 仅 `open:false`(`:131`)；打开目标需 `completed && targetAvailable!==false`（`panel:78/214`）且走 `activateSessionCandidate`，**未直接用 targetSessionId 跳转**（符合"预留 ID，不能据此执行"）。
- 既有保护全部保留：多 tab(`:254-274`)、幂等键(`:150` + 每 key 独立记录 `:76-79`)、localStorage 写失败容错(`:91-100`)、404 恢复(`:161-169`)、通知去重(`removeNotification('merge-storage')` + `changed &&` 门 `:199`)、不抢焦点(`panel:86-88`，测试 `panel.test:45`)。
- submit 前 `restore(true)` + refresh 复核(`:122-127`)；无本地 pending 时轮询 `/active`(`:147`，5s 节流 `:145`)。

---

## 六、发布前行动清单

### 必须做（阻断级）

1. **补回 8 项被删测试**，优先级：① 准入参数校验（2~5/重复/主来源不在集合/`../B`/null/标题与幂等键长度）+ 不占名额、不写 session_merges；② 审批 / 后台服务 / 第五来源忙时拒绝 + 部分准入回滚不泄漏占用；③ E 原生 tool ID 隔离（同来源 tool_use_id 不进入 E 的原生历史，E 下一条消息正常归一化）；④ 慢 hook + hook 失败不回滚 completed + 仅一个目标；⑤ `MERGE_OPERATION_NOT_FOUND` 与权限默认（AUTO_APPROVE）+ totalUsage；⑥ 磁盘失败不留空 E。
2. **为 5 个零断言的冲突错误码补测试**，并同时修复 I2（500 → 409 + operation）；把 `isInstanceOf(Conflict.class)` 改回精确 code 断言。
3. **补 `occurrences.jsonl` 断言**：重复位置被登记、且**不进入**提取单元输入。
4. **补 E 状态隔离测试**：空 FileStateCache、E 发消息/压缩/更新待办或设置/删除**不回写** A/B。
5. **执行 §9 强制的真实模型语义验收**（含 `name`→`displayName` 接续开发样本 + "封存后共享代码又变化"变体 + 7 类人工标注样本），或在发布说明中**明确标注为未完成的前置验收**，不得以 stub 结果替代。
6. **修正文档第 7 行的验证陈述**：把"0 失败/错误、2,905 通过"改为实测的"11 失败、2,894 通过"，并注明这 11 项在 HEAD 基线同样复现、根因为宿主环境；前端"876"改"894"或标注口径；构建提示补齐 empty chunk 与混合导入两类。

### 应该做（重要，建议同批）

7. **修 I1（E 会话被 500 打死）——这是唯一可能影响用户体验的功能性缺陷**，且触发条件不难达到（用户限制工具集 + 小窗口模型，或包损坏/绑定丢失）。
8. 修 I3（brief 阈值未扣 header）、I5（暂停后不可恢复）、I6（错误码归类）、I7（静默吞 IOException 导致数据无声丢失）、I8（未捕获 InvalidPathException）。
9. 修前端 I10（preparing 推断锁）、I11（`targetAvailable` 门禁改 `!== true`、DTO 必选性）、I12（按钮禁用标志）。
10. 修 I14（把 `CallUsage` 移入 `MergeHandoffData`，改构造器注入）与 I15（日志保留异常栈）——两处修复成本都极低，但 I15 直接决定线上可运维性。
11. I4 加一行包含性断言；I9 加每轮投影缓存；I13 补 Panel 最小测试与"旧 resume 不重放"用例；I16/I17 收紧服务层契约。

### 可延后（建议）

12. 清理 ~250 行死代码与未使用 import/构造参数；统一字段注入为构造器注入；删除多余的 `isExplicitCoreTool` 名字放行与类级 `produces`。
13. 前端轮询退避 + `document.hidden` 暂停；`validatedTerminal` 泄漏；`response.json()` 加 `.catch`；移除已失效的 `result.warnings` 分支；补 `protocolVersion===1` 只读兜底；错误码分支。
14. 归档 `docs/zhikun/` 下 7 份 v1 文档；单独立项修复 4 个非密封测试类（尤其 `OneKeyRegistryIntegrationTest` 泄露 API key 片段）；跑后端测试前 `unset LOG_DIR`。

---

## 七、总体判断

**这批改动的工程质量明显高于平均水平。** 规格中"最小必要改造"的意图被真实落实了：删除了 3,500 正文上限与 16 次调用上限这两个导致线上失败的根因，五种计量单位彻底分离，封存即释放、epoch 栅栏、单元三步提交、原子发布都做得干净；四条高危安全边界（路径穿越、symlink、游标伪造、全局工具泄漏）经代码推演与测试双重验证均不可利用；隔离设计（早退门控在任何 I/O 之前、旧重载委托 `false`、三元选择保留 7 参路径）是**可证明的**，而非仅靠约定。规格第 10 节列为"保持不改"的 8 个文件经 `git diff` 证实全部零改动，已撤回的 `SubAgentHistoryPersistence` 无残留。

**第一个问题可以明确回答：非合并链路没有被破坏。** 这个结论有代码级（9 条链路逐条推演）、diff 级（文件清单核对）、运行时级（9 个回归类全绿 + HEAD 基线对照证明 11 个失败与本次无关）三重独立证据支撑。

**但第二个问题的答案是否定的：现在还不能发。** 障碍不在产品逻辑（**阻断级代码缺陷 0 项**），而在**验证的完整性与陈述的真实性**：

- 规格第 9 节明令"保留并扩展"的测试被删掉了 8 项，其中工具 ID 隔离、审批/后台忙时拒绝、慢 hook 三项是规格**点名要求保留**的
- 6 个规格错误码有 5 个零断言，且恰好掩盖了一个真实的 500 缺陷（I2）
- `occurrences.jsonl` 和 E 状态隔离这两个**核心语义**完全无覆盖——前者正是本次改造要解决的线上失败的直接成因
- §9 强制的真实模型语义验收未执行，仓库无任何样本或记录
- 文档写着一个在本机无法复现的"0 失败"

按第六节清单完成第 1–6 项后即可发布；第 7 项（I1）强烈建议同批修掉，因为它是唯一会让用户在正常使用中撞到 500 且**无法自愈**的缺陷。

---

## 附录：审查足迹

**未修改任何源代码 / 测试代码 / 配置文件。**

- 只读检查：`git status` / `git diff` / `git check-ignore` / `git log`，阅读规格文档全文、`HandoffContextService.java` 全文、`QueryEngine.java` 关键段落、`QueryController.java:165-192`、`log4j2.xml`、`RemoteAccessSecurityFilter.java`、`CoordinatorService.java`、`application-test.yml`，以及对被删测试方法与错误码的多轮 grep
- 运行产物（均 gitignore，不影响仓库）：`backend/target/**`、`frontend/dist/**`
- 临时创建并已删除：git worktree `/tmp/verify-head-baseline`（HEAD 基线对照，`git worktree remove --force` 已完成，`git worktree list` 现只剩主工作树）——仅写入过 `.git/worktrees/` 元数据，未触碰任何被跟踪文件
- 非主观意愿的副作用：因宿主 `LOG_DIR` 指向仓库 `log/`，后端测试 JVM 向 `log/app.log`、`log/debug/debug.log` 写入了测试日志并触发一次滚动压缩。这些路径已被 `.gitignore` 忽略，**不存在被提交到 GitHub 的风险**；未做清理/回滚，以保持证据原样
