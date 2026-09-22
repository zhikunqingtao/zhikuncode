# 会话合并 v2 本地改动审查报告

**审查对象**：`/Users/guoqingtao/Desktop/dev/code/zhikuncode` 未提交改动（36 个修改文件 + 13 个未跟踪文件，约 +2565/−1058 行）  
**架构基准**：`docs/session-merge-architecture-v2.md`（620 行，全文逐条对照）  
**审查日期**：2026-09-22  
**审查重点**：
1. 本次改动是否会对非合并分支的功能链路造成任何负面影响或破坏
2. 代码质量、测试覆盖及规范性，是否达到提交至 GitHub 的发布标准

**忽略清单（16 个文件，按要求排除审查）**：
1. `frontend/src/components/message/ImageBlock.tsx`（改）
2. `frontend/src/components/message/ImageBlock.test.tsx`（改）
3. `frontend/src/utils/messageContent.ts`（改）
4. `frontend/src/utils/messageContent.test.ts`（改）
5. `frontend/src/components/message/MessageActions.tsx`（改）
6. `frontend/src/components/message/MessageActions.test.tsx`（改）
7. `frontend/e2e/message-copy-all.spec.ts`（新增）
8. `frontend/src/components/status/sessionStatusMeta.ts`（新增）
9. `frontend/src/components/status/SessionStatusCapsule.tsx`（新增）
10. `frontend/src/components/layout/Header.tsx`（改）
11. `frontend/src/components/input/PromptInput/PermissionMenu.tsx`（改）
12. `frontend/src/components/input/PromptInput/index.tsx`（改）
13. `frontend/src/components/layout/Sidebar.tsx`
14. `frontend/src/styles/globals.css`
15. `frontend/src/styles/liquid-glass.css`
16. `frontend/src/components/layout/Sidebar.desktop.test.tsx`

---

## 〇、范围与异常

| 项 | 说明 |
|---|---|
| 忽略清单核对 | 15 个文件确实在本地改动中；**`frontend/e2e/message-copy-all.spec.ts` 在工作区不存在**（既非 untracked 也非 modified），但被列入「新增」忽略清单 |
| 规格要求保持不动的文件 | `SessionManager` / `SessionMessagePersistence` / `SubAgentExecutor` / `SwarmWorkerRunner` / `SessionExecutionGate` / `ToolRegistry` / `ImageResultExternalizer` **全部未改动**，符合规格第 10 节 |
| 敏感/垃圾文件 | 无密钥、`.db`、日志、`node_modules` 混入 git status |
| 分支 | `main` @ `b98e187`（与 origin/main 同步） |

### 本次纳入审查的核心改动

**后端修改**：
- `authorization/AuthorizationService.java`、`authorization/OperationAnalyzerRegistry.java`
- `controller/QueryController.java`、`controller/SessionMergeController.java`
- `engine/ImageRefInjector.java`、`engine/QueryEngine.java`、`engine/QueryLoopState.java`
- `session/merge/MergePackageService.java`、`MergeSummaryService.java`、`MergeTextBudget.java`、`SessionMergeService.java`
- `websocket/WebSocketController.java`
- 对应测试：`AuthorizationServiceProjectFileScopeTest`、`OperationAnalyzerRegistryTest`、`QueryEngineUnitTest`、`MergeFixture`、`MergePackageServiceTest`、`MergeSummaryServiceTest`、`SessionMergeServiceTest`

**后端新增**：
- `config/database/V026_ExtendSessionMerges.java`（+ test）
- `engine/HandoffContextService.java`（+ test）
- `session/merge/HandoffReadService.java`（+ test）
- `session/merge/MergeHandoffData.java`
- `session/merge/MergeProgressRepository.java`（+ test）
- `tool/impl/HandoffReadTool.java`

**前端纳入审查**：
- `store/sessionMergeStore.ts`（+ test）
- `components/session/SessionMergePanel.tsx`（+ test）

**文档**：
- `docs/session-merge-architecture-v2.md`（规格本身）

---

## 一、非合并主链路影响评估（审查重点 1）

### 结论：**未发现对普通 / fork / Swarm 功能链路的破坏性影响。**

| 入口 | 是否受影响 | 证据 |
|---|---|---|
| 普通 QueryEngine 分支 | **否**（预算公式等价） | `QueryEngine.java:1811-1814`：`handoffOperationId==null` 时直接返回 `Projection(List.of(), 0)`，`reservedTokens=0`，`historyBudget == inputBudget`；`HandoffContextService.inject` 对空投影原样返回 |
| REST 三入口 | **否** | `QueryController.java:175-176 / 262-263 / 359-360`：仅 `isMerged(session.config())` 为真才调用 `configure`；普通请求不查合并表、不读包 |
| WebSocket | **否** | `WebSocketController.java:956-957`：同一 `isMerged` 门控；`session.config()` 取自已加载的 SessionData，无额外查询 |
| 工具池 / 子代理 | **否** | `HandoffReadTool` **不是** Spring `@Component`/`List<Tool>` Bean（`HandoffContextServiceTest.java:29` 断言）；`ToolRegistry` 中以 `isExplicitCoreTool("HandoffRead")` 登记仅作完整性断言，不进入 `getAllTools()`；`SubAgentExecutor` 未改动 |
| 图片注入 | **否** | `ImageRefInjector.java:96-98 / 269`：旧重载默认 `(path,hash)->false`，路径安全判定与改前等价；仅合并会话且投影非空时才启用 `boundHandoffAsset` |
| 压缩 / 历史 | **否** | `QueryLoopState` 标记 `@JsonIgnore` 无默认行为；投影插在 `CompactionHistory.forRequest` 之前且为独立 UserMessage，不进 tool_use/result 配对 |
| fork / Swarm 保存机制 | **否** | 相关类零 diff；合并侧只读已有 checkpoint/消息，缺口显式记入 gaps |
| 孤立测试证明 | 有 | `QueryEngineUnitTest.handoffProjectionOnlyRunsForExplicitlyMergedSessions(false)`：`verifyNoInteractions(handoff)` + payload 不含 HandoffRead + phase2−phase1==0 + 不写回 state |

**唯一对普通路径的新增成本**：`isMerged()` 一次 Map 查询、`handoffProjection` 一次空判断、字段注入一个可选 Bean。可忽略。

### 逐入口细节

#### 1.1 QueryController（REST 三入口）

```java
if (HandoffContextService.isMerged(session.config()))
    config = handoffContext.configure(config, state, session.config(),
            request.allowedTools(), request.disallowedTools());
```

- 门控在已加载的 `SessionData.config` 上，不额外查合并表
- 普通会话：`isMerged` 为 false → 零调用
- 合并会话 E：校验绑定后才把 `HandoffRead` 追加到**本次** QueryConfig

#### 1.2 WebSocketController

- `sessionMetadata` 取自 `sessionManager.loadSession(sessionId)` 的 `data.config()`，与历史消息同一次加载
- 同一 `isMerged` 门控；PROMPT 路径把 `allowedToolNames` 传入 `configure`，保留工具限制

#### 1.3 QueryEngine 预算与注入

```java
var handoff = handoffProjection(config, state, effectiveModel, inputBudget, tokenCharRatio);
int historyBudget = inputBudget - handoff.reservedTokens();
// phase1 / remainingBudget / 图片预算均使用 historyBudget
// 归一化前：HandoffContextService.inject(apiReadyMessages, handoff)
```

- 无标记：`reservedTokens=0`，`inject` 返回原列表 → 预算与历史装配与基线一致
- 有标记：预留 `min(2048, 总历史预算*10%)`，投影作为独立 UserMessage 前置
- 图片：仅当 `handoffContext != null && !handoff.messages().isEmpty()` 才启用 `canReadAsset` 宽限；否则走旧重载（谓词恒 false）

#### 1.4 HandoffRead 工具隔离

| 约束 | 实现 |
|---|---|
| 不注册为全局 Tool Bean | 仅 `HandoffContextService` 内部 `new HandoffReadTool(...)` |
| 不进普通/fork/Swarm 默认工具池 | `SubAgentExecutor` / `ToolRegistry.getSubAgentTools` 零改动 |
| 同名 MCP/动态工具不继承权限 | `OperationAnalyzerRegistry` 用 `instanceof HandoffReadTool` 分发，同名走 `static-or-remote-v1` / `mcp-v1` |
| 用户禁用工具时尊重限制 | `configure` 不追加工具，投影换成「工具当前不可用」说明 |

---

## 二、架构合规核对（对照 `session-merge-architecture-v2.md`）

### 2.1 符合项

| 规格条款 | 实现 | 结论 |
|---|---|---|
| 状态机 `preparing/paused/failed/completed/cancelled` + stage 六值 | V026 CHECK + `MergeProgressRepository` | 符合 |
| unit state `pending/running/completed/split/failed` | `session_merge_units` CHECK | 符合 |
| `active_slot` 唯一名额（DB 索引，非内存 semaphore） | `uq_session_merges_active ... WHERE active_slot=1`；`create` 直接写 `active_slot=1` | 符合 |
| `run_epoch` 围栏 | 所有推进 SQL 带 `run_epoch=? AND status='preparing'` | 符合 |
| 封存即释放来源 | `SessionMergeService.execute`：`sealed()` 后立刻 `leases.close()` 再整理 | 符合 |
| 原子发布 | `tx` 内 createSessionRecord + metadata 写 `sessionMergeOperationId` + 入口消息 + `complete()` | 符合 |
| 恢复/取消/active HTTP | Controller 三接口 + `Resume(expectedEpoch)` + 错误码 7 个齐全 | 符合 |
| 删除 3,500 阈值与 16 次总调用上限 | `MergeSummaryService` 已无该限制；改为 16384 / 2048 / 256 / 300s（规格 6.3 表） | 符合 |
| 大记录不再整单失败 | `MAX_RECORD_BYTES` 仅作 JDBC 物化上界；超限 → raw + `RECORD_REQUIRES_HANDLING` 暂停 | 符合 |
| 普通长文本分片 | `v2OrdinaryTextBeyondLegacyRecordLimitIsFullySplit` 等测试 | 符合 |
| 资料根目录 | `packageRoot()` = `DatabaseResolver` 工程库父目录 + `session-merges/` | 符合 |
| V026 迁移 SQL | 与附录 A 逐字段一致；`active_slot IS 1` 未写成会放过 NULL 的形式；`validate` 检查 CHECK/索引/FK | 符合 |
| 旧行兼容 | protocol=1、preparing→failed/`LEGACY_INTERRUPTED`、active_slot=NULL | 符合 |
| 绑定查询 | `protocol_version=2 AND status='completed' AND target_session_id=?` JOIN sessions | 符合 |
| 简单六 section / 条目 schema | `MergeHandoffData.SECTIONS` / `ITEM_STATUSES` 与规格 6.1 一致 | 符合 |
| 证据别名 i1/i2，程序关联 UTF-16 范围 | `InputRef(ref, sourceId, start, end)` | 符合 |
| 重复合并按 origin/version 去重 | `v2RepeatedMergeCopiesIndependentOriginalsAndDeduplicatesMessages` | 符合 |
| HandoffRead 游标/UTF-8/跨片/16KiB/8MiB/15s | `HandoffReadService` 全覆盖 | 符合 |
| 路径穿越/symlink 拒绝 | `MergePackageService.safeFile` + 测试 | 符合 |
| 普通请求零交接 | `HandoffContextServiceTest.ordinaryRequestRetainsTheExactConfigurationWithoutCallingMergeServices` | 符合 |
| 授权 7.3 | `instanceof HandoffReadTool` 分发；PLAN 前 `safeRead`；不进 SAFE_INTERNAL | 符合 |
| 不改 SessionManager 等基线 | 零 diff | 符合 |

### 2.2 偏差项

| # | 级别 | 位置 | 偏差 | 规格依据 |
|---|---|---|---|---|
| 1 | **P2** | `SessionMergeService.get` 锁集合构造 | `lockedSourceSessionIds` **只过滤根来源**，不含 descendants，但 `lockSources` 锁的是整棵来源树 | 规格 5.3「锁集合来自真实 gate」、8「来源是否锁住只看 lockedSourceSessionIds」 |
| 2 | **P2** | `sessionMergeStore.ts:28-32` | `selectMergeSourceIds` 仍以 `status==='preparing'` 门控，paused/failed 行的非空锁集合会被忽略 | 规格 8「只看 lockedSourceSessionIds」 |
| 3 | **P2** | `SessionMergePanel.test.tsx` | 缺少「恢复/取消按钮、单元数、暂停原因、targetAvailable 禁用打开目标」的 Panel 层交互断言 | 规格 9「UI 与控制」 |
| 4 | **P2** | `QueryEngine.java:841` + `:1835` | 合并会话下 `handoffProjection`→`project()`（含包哈希校验与 brief 读取）**被调用两次**；非合并路径无影响，合并路径重复 I/O | 规格 7.1「同一规则」未禁止重复，但属浪费且放大截止窗口 |
| 5 | P3 | `V026_ExtendSessionMerges.execute` | `SQL.split(";")` 切语句，当前 SQL 无问题，后续在字符串/注释中加分号会静默破坏迁移 | 附录 A |
| 6 | P3 | `MergeProgressRepository.failAttempt` | 先 `finishAttempt(...,"error")` 再把 `completed` 改成 `error`，语义可疑，可能误伤已记账调用 | 规格 5.1 attempts「迟到 usage 只记账」 |
| 7 | P3 | `SessionMergeController.java:33` | `Map.of("message", failure.getMessage())` 在 message==null 时 NPE | 防御性 |
| 8 | P3 | 匿名内部类 / FQCN 内联（`OperationAnalyzerRegistry`、`QueryController`、`QueryEngine`） | 可读性差，与既有风格不一致 | 代码质量 |
| 9 | 备注 | `MergePackageService.packagePath()` 仍走 scratchpad | 规格允许「仅为兼容夹具保留，在线入口已改用 `snapshotPath()`」；`deleteUnreferenced` 同时兼容新旧两根路径 | 规格 2.2 / 10 |

---

## 三、风险清单（P0–P3）

**无 P0。** 不构成对非合并链路的破坏，也无数据损坏/权限越权级问题。

| 级别 | 数量 | 代表项 |
|---|---|---|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 4 | 上表 #1–#4 |
| P3 | 5 | 上表 #5–#8 + 通知 cancelled 文案/去重 |

### 详细风险

#### P2-1 锁集合不完整
- **位置**：`SessionMergeService.get`
- **描述**：`lockedSourceSessionIds` 只对 `request.sourceSessionIds()` 求交，但 `lockSources` 对 `packages.descendants(...)` 全树取 token。子任务实际被锁却不在 DTO 中，前端按规格「只看 lockedSourceSessionIds」会误判子任务可删/可用。
- **建议**：与 `lockSources` 共用同一 descendants 列表，或对全树求交。

#### P2-2 前端锁门控残留
- **位置**：`frontend/src/store/sessionMergeStore.ts:28-32`
- **描述**：`selectMergeSourceIds` 在 `status==='preparing'` 时才返回锁集合；若 paused/failed 行带非空 `lockedSourceSessionIds`（服务端异常或中间态）会被忽略。
- **建议**：去掉 status 门控，直接 `operation?.lockedSourceSessionIds ?? []`。

#### P2-3 Panel 层 UI 测试缺口
- **位置**：`frontend/src/components/session/SessionMergePanel.test.tsx`
- **描述**：规格 9「UI 与控制」要求覆盖恢复/取消按钮、单元数、暂停原因、targetAvailable 禁用打开；目前仅 store 层覆盖 resume/cancel 序列化。
- **建议**：补 1～2 个 Panel 用例断言 canResume/canCancel 渲染与 openTarget 双条件。

#### P2-4 合并路径投影重复计算
- **位置**：`QueryEngine.java:841`（主路径）与 `:1835`（`prepareCompactionContext`）
- **描述**：合并会话每次请求会调用两次 `project()`，各自做包哈希校验与 brief 读取。非合并路径 early-return 无影响；合并路径放大 15s 读取截止窗口的抖动。
- **建议**：在同一请求内缓存 `Projection`，供两处共用。

#### P3 汇总
| 项 | 说明 |
|---|---|
| V026 `SQL.split(";")` | 脆弱；建议语句列表或 `ScriptUtils` |
| `failAttempt` 改写 completed | 语义可疑 |
| `SessionMergeController` `Map.of` NPE | message 空值防御 |
| FQCN/匿名类风格 | 可读性 |
| 通知 cancelled 用 error 级 + 「合并暂停或失败」文案 | UX 不一致；通知去重依赖 `changed &&` 门控，无 key 去重 |

### 安全面补充

- `safeFile` 拒绝绝对路径 / `..` / 越界 normalize / symlink（`MergePackageService.java:304-321`）
- HandoffRead 经 `AuthorizationSubjectResolver.resolve(currentRunId).rootSessionId()` 绑定，模型不能选其他 session/operation
- 同名 MCP/动态工具不继承权限（`instanceof` 分发 + 测试覆盖）
- `HandoffRead` 位于 `AuthorizationService` PLAN 拒绝分支之前的 `safeRead`（限 `handoff-read-v1` + `RiskClass.SAFE` + `READ_RESOURCE`）
- 外部图片仅当 `isBoundAsset`（包内登记 + 哈希匹配 + 路径落在本包）才放宽工作区限制

---

## 四、代码质量评价

### 优点

1. **职责边界清晰**：`SessionMergeService`（准入/推进/发布）、`MergePackageService`（快照/清单/原件）、`MergeSummaryService`（分块提取/聚合）、`MergeProgressRepository`（SQL/epoch）、`HandoffReadService`（读取）符合规格第 3/10 节，未过度拆子系统
2. **事务纪律良好**：文件复制与 LLM 调用均在事务外；`commitUnit` / `beginAttempt` / `complete` 为短事务；发布失败可回滚且保留已封存资料
3. **取消/重启语义正确**：`writers` map + `run_epoch+1` + 旧 running→pending；cancel 后 cleanup 等 writer 停止；不确定提交按 durable completed 权威判断
4. **删除反模式**：旧 10 分钟整任务截止、失败即删包、preparing 全标 failed、内存 semaphore 单飞、固定 16 次调用、3,500 正文混用预算——均已移除
5. **磁盘与配额防护延续**：`MergeTextBudget.atomicWrite`、复制配额、至少 1 GiB 余量

### 不足

1. 大量 FQCN / 单行压缩风格（`handoffRead` 匿名类、`QueryController`/`QueryEngine` 字段注入），可读性与可维护性偏弱
2. `failAttempt`、`SQL.split(";")` 等边界语义未注释清楚
3. 合并路径下 `project()` 重复调用属隐性性能/截止风险
4. `packagePath` 遗留 helper 与新 `snapshotPath` 双轨，需长期靠注释/测试约束

---

## 五、测试覆盖评估

### 5.1 已有覆盖（质量较高）

| 测试文件 | 约计 | 对应规格 9 |
|---|---|---|
| `SessionMergeServiceTest` | 12 场景 | 2–5 来源原子发布、损坏 checkpoint 暂停、**封存即释放来源**、失败保留单元且不重做已提交、取消围栏迟到响应、根+子任务忙拒绝、发布回滚、active/resume 冲突、不确定提交保目标、rename 先于 ledger 可恢复、目标删除撤销绑定、重启续封存快照 |
| `MergePackageServiceTest` | 30 | legacy 包、阻断记录仅从不可变副本重解析、空 seal 不放行、大记录分片保尾部、去重、symlink/路径穿越、Unicode/代理对、磁盘与配额、产物验证分离 |
| `MergeSummaryServiceTest` | 8 | schema/evidence 校验、字节/token/推理分离、**>16 单元**、413 二分、损坏 committed 单元不重复计费、推理预留 |
| `HandoffReadServiceTest` | 15 | 中文/路径/跨片/长行尾部、游标绑定与 mid-line 拒绝、穿越/symlink/跨会话、8 MiB 续页、15s 超时、不跨调用缓存完整性 |
| `HandoffContextServiceTest` | 5 | 普通零调用、仅 E 注入工具、allow/deny、绑定不匹配拒绝 |
| `QueryEngineUnitTest`（新增段） | 参数化 | 无标记零交接、预算不双扣 |
| `V026ExtendSessionMergesTest` | 5 | 迁移与约束 |
| `MergeProgressRepositoryTest` | 6 | 名额/epoch/attempt |
| `Authorization*Test` | 32 通过 | PLAN + 同名工具拒绝 |

### 5.2 缺口

1. **Panel UI 与控制**（规格 9 明确行）— canResume/canCancel 渲染、openTarget 双条件无组件断言（P2）
2. 规格 9「本次失败条件」的**合成 4,183 bytes / provider output=13,370** 专测未见具名用例（已有「无 3500 拒绝 / >16 单元」等价覆盖，但未按失败日志数字标注为合成回归）
3. `GET /api/session-merges/active` 与 `GET /{operationId}` 路由优先级无集成测试
4. **真实模型接续开发语义验收未执行**（规格首段/第 9 节明示为未完成项，不得用 stub 冒充）

### 5.3 实跑结果

| 范围 | 结果 |
|---|---|
| 后端授权定向（Java 21 / test profile） | **32 / 0 失败** |
| 前端 `sessionMergeStore.test.ts` + `SessionMergePanel.test.tsx` | **63 / 0 失败**（存在 React `act(...)` warning，不影响结果） |

> 说明：规格第 7 节曾记录隔离副本上后端全量 2,975 项（2,905 通过 / 70 跳过 / 0 失败）与前端全量 103 文件（876 通过 / 16 跳过）；本轮审查未重复全量，以定向结果与代码证据为准。

---

## 六、发布就绪度（审查重点 2）

### 判断：**有条件可以提交 GitHub**（无 P0/P1 阻塞）

| 类别 | 内容 |
|---|---|
| **建议提交前处理（或 PR 明示接受）** | ① 锁集合含 descendants 或前端去掉 status 门控（P2 #1/#2）② 补 Panel 控制断言（P2 #3） |
| **非阻塞** | P3 五条（风格 / NPE 防御 / `failAttempt` / `SQL.split` / 通知文案） |
| **必须在提交信息/PR 中如实声明** | 「真实模型接续开发语义验收尚未执行」；stub 测试不得表述为语义验收闭环 |
| **工作区卫生** | 通过；未跟踪文件均为应入库的源码/测试/规格文档 |
| **额外提醒** | 忽略清单中的 `frontend/e2e/message-copy-all.spec.ts` **当前不存在**，若属本次交付物需确认是否漏提交或清单笔误 |

### 授权 / HTTP / 前端三条主线结论（专项复核）

| 主线 | 结论 |
|---|---|
| 授权（7.3） | 合规：`handoff-read-v1` 分析器 + 执行前范围复核 + PLAN 只读 + 不进 SAFE_INTERNAL + 同名工具不继承 |
| HTTP（5.3） | 合规：active/resume/cancel + 7 错误码 + MergeProgress DTO 字段齐全 + 锁集合来自 gate |
| 前端（第 8 节） | 基本合规：active 恢复、真实锁集合驱动、syncing/error 分离、阶段/单元数/暂停原因/恢复取消、关闭仅隐藏、completed+targetAvailable 才开目标、cancel 无版本；**残留 P2 门控偏差与 Panel 测试缺口** |

---

## 七、改进建议（按优先级）

1. **P2** `SessionMergeService.get` 的 `lockedSourceSessionIds` 改为对 `packages.descendants(...)` 全树求交，或与 `lockSources` 共用同一 ID 列表来源。
2. **P2** `sessionMergeStore.selectMergeSourceIds` 去掉 `status==='preparing'` 条件，只信 `lockedSourceSessionIds`。
3. **P2** `SessionMergePanel.test.tsx` 增加：canResume/canCancel 按钮、暂停原因展示、`targetAvailable===false` 时禁止打开目标。
4. **P2** `QueryEngine` 缓存一次 `Projection`，供主路径与 `prepareCompactionContext` 共用，避免重复包校验。
5. **P3** V026 改用 `ScriptUtils`/按语句列表执行，替代 `split(";")`；`failAttempt` 澄清或删除对 `completed` 的改写；`conflict` 的 message 空值防御。
6. 补一条规格 9 的合成失败条件回归（4,183 / 13,370，明确标注 synthetic）。
7. 合并前确认 `message-copy-all.spec.ts` 去向。

---

## 八、总评

本次改动对**非合并主链路是隔离且无破坏的**，架构 v2 的核心契约（封存即释放、原子发布、DB 名额/epoch、预算解耦、HandoffRead 窄授权、普通路径零交接）落实到位，测试面覆盖规格第 9 节大部分硬性用例。

发布层面达到「**有条件可提交**」：
- 建议先收掉 2 项前端/锁集合 P2 与 Panel 测试缺口
- 在 PR 中保留「真实模型语义验收未做」的诚实声明
- 勿将 stub 的数据/隔离/接线断言描述成真实模型接续开发验收

---

## 附录 A：审查方法

1. `git status` / `git diff --stat` 全量清点，对照忽略清单与规格「保持不改」清单
2. 全文精读 `docs/session-merge-architecture-v2.md`（620 行）
3. 逐文件 diff + 关键方法上下文（`execute` / `start` / `recover` / `resume` / `cancel` / `seal` / `safeFile` / 迁移 SQL / `handoffProjection` / `configure` / `authorize`）
4. 授权/前端/发布就绪专项复核（含定向测试实跑）
5. 测试用例名与规格第 9 节表格逐行映射

## 附录 B：关键文件索引

| 角色 | 路径 |
|---|---|
| 规格 | `docs/session-merge-architecture-v2.md` |
| 合并编排 | `backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java` |
| 快照/资料包 | `backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java` |
| 提取/聚合 | `backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java` |
| 持久进度 | `backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java` |
| 迁移 | `backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java` |
| 读取服务 | `backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java` |
| 读取工具 | `backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java` |
| 上下文入口 | `backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java` |
| 引擎接点 | `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` |
| 前端状态 | `frontend/src/store/sessionMergeStore.ts` |
| 前端面板 | `frontend/src/components/session/SessionMergePanel.tsx` |
