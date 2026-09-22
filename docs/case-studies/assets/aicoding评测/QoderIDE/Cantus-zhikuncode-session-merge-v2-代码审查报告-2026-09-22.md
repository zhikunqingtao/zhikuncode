# zhikuncode 本地未提交改动代码审查报告（对照 session-merge-architecture-v2.md）

- 项目：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`
- 审查日期：2026-09-22
- 审查方式：Leader 调度代码审查员 Mark（CodeReview 子代理）完成全量只读审查，Leader 汇总输出
- 审查基准：`docs/session-merge-architecture-v2.md`
- 审查时间线：首轮报告 10:40:42（仅 correctness 视角，未达要求）→ 补全完整报告 10:57:16
- 约束：全程只读，未修改任何代码文件，未执行 git commit / push，未启动服务，未运行重型 e2e 套件

---

## 一、审查范围

### 1.1 纳入审查的改动文件（共 33 个）

**后端主代码（修改 12 / 新增 6）**

修改：
- `backend/src/main/java/com/aicodeassistant/authorization/AuthorizationService.java`
- `backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java`
- `backend/src/main/java/com/aicodeassistant/controller/QueryController.java`
- `backend/src/main/java/com/aicodeassistant/controller/SessionMergeController.java`
- `backend/src/main/java/com/aicodeassistant/engine/ImageRefInjector.java`
- `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java`
- `backend/src/main/java/com/aicodeassistant/engine/QueryLoopState.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/MergeTextBudget.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java`
- `backend/src/main/java/com/aicodeassistant/websocket/WebSocketController.java`

新增：
- `backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java`
- `backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/MergeHandoffData.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java`
- `backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java`

**后端测试（修改 7 / 新增 4）**

修改：
- `AuthorizationServiceProjectFileScopeTest.java`
- `OperationAnalyzerRegistryTest.java`
- `QueryEngineUnitTest.java`
- `session/merge/MergeFixture.java`
- `session/merge/MergePackageServiceTest.java`
- `session/merge/MergeSummaryServiceTest.java`
- `session/merge/SessionMergeServiceTest.java`

新增：
- `config/database/V026ExtendSessionMergesTest.java`
- `engine/HandoffContextServiceTest.java`
- `session/merge/HandoffReadServiceTest.java`
- `session/merge/MergeProgressRepositoryTest.java`

**前端（修改 4）**
- `frontend/src/store/sessionMergeStore.ts`
- `frontend/src/store/sessionMergeStore.test.ts`
- `frontend/src/components/session/SessionMergePanel.tsx`
- `frontend/src/components/session/SessionMergePanel.test.tsx`

**文档（新增 1）**
- `docs/session-merge-architecture-v2.md`

**python-service / 其他**：无未提交改动。

### 1.2 按用户要求排除的文件（16 个，不计入结论）

1. `frontend/src/components/message/ImageBlock.tsx`
2. `frontend/src/components/message/ImageBlock.test.tsx`
3. `frontend/src/utils/messageContent.ts`
4. `frontend/src/utils/messageContent.test.ts`
5. `frontend/src/components/message/MessageActions.tsx`
6. `frontend/src/components/message/MessageActions.test.tsx`
7. `frontend/e2e/message-copy-all.spec.ts`（新增）
8. `frontend/src/components/status/sessionStatusMeta.ts`（新增）
9. `frontend/src/components/status/SessionStatusCapsule.tsx`（新增）
10. `frontend/src/components/layout/Header.tsx`
11. `frontend/src/components/input/PromptInput/PermissionMenu.tsx`
12. `frontend/src/components/input/PromptInput/index.tsx`
13. `frontend/src/components/layout/Sidebar.tsx`
14. `frontend/src/styles/globals.css`
15. `frontend/src/styles/liquid-glass.css`
16. `frontend/src/components/layout/Sidebar.desktop.test.tsx`

同时忽略运行产物与临时目录：`.tmp/`、`log/`、`backend/log/`、`.service-pids*`、`node_modules/`、`dist/`、`backend/target/`、`frontend/coverage/`、`frontend/playwright-report/`、`frontend/test-results/`、`docs/test-results/`。

---

## 二、架构规范对照（v2 文档 → 代码）

| 规范点 | 文档章节 | 状态 | 关键证据 |
|---|---|---|---|
| 只读已有持久化历史，不修改普通执行与保存机制 | 第 1 节、第 10 节 | 符合 | `SessionManager`、`SubAgentExecutor`、`SwarmWorkerRunner` 均未出现在改动中；合并逻辑集中于 `SessionMergeService`、`MergePackageService` |
| 封存即释放来源、只允许一个未结束操作 | 5.1 / 5.2 | 符合 | V026 创建 `uq_session_merges_active` 唯一索引（`WHERE active_slot=1`）；`MergeProgressRepository.active()`；`SessionMergeService.start` 对已有 active 抛 `MERGE_ACTIVE_EXISTS`（409）；`execute` 中快照完成后立即 `leases.forEach(Token::close)` |
| 每次合并自持资料副本 | 第 1 节 | 符合 | `MergePackageService.seal` 构建独立 `snapshot/` 目录（raw/text/assets）；`importPrevious` 导入旧包但保持独立副本 |
| V026 迁移 SQL 与约束 | 附录 A | 符合 | 内嵌 SQL 与文档一致（`session_merges_v2`、`session_merge_units`、`session_merge_attempts`）；已存在 `protocol_version` 时仅 `validate()` 不重建；validate 检查字段集、`active_slot IS 1`、`handoff_hash IS NOT NULL`、索引与 FK |
| status / stage 状态机、run_epoch 仅用于代次隔离 | 5.2 | 符合 | DB CHECK 限定状态值；`resume` 执行 `run_epoch+1` 并重置 pending/running 单元与 attempts；`cancel` 递增 epoch、置 cancelled、清 active_slot；`complete` 要求已有 `snapshot_hash` |
| `/active` `/resume` `/cancel` 接口语义 | 5.3 | 符合 | `SessionMergeController`：`active` 无操作返回 204；`resume` 返回 202；`cancel` 冲突统一由 `@ExceptionHandler(Conflict)` 转 409 |
| 结构化交接 schema：六大 section、状态值、evidence 规则 | 第 6 节 | 符合 | `MergeSummaryService.validate` 严格校验 `SECTIONS`、`ITEM_STATUSES`、非空 content、非空 evidence 数组、别名 `i[1-9][0-9]*`；evidence 映射为 `ref@start:end` |
| 单请求预算、300 秒单次超时、有限重试、移除 10 分钟整任务硬超时 | 6.x | 符合 | 单请求资料目标 16384 token、输出 2048 token；`call()` 使用 300s 超时、最多 3 次尝试，按 413 / 上下文 / JSON 错误分类；整任务 10 分钟截止与 16 次总调用上限已移除 |
| 仅合并会话 E 启用交接入口；普通会话不查合并表、不注入工具 | 第 7 节 | 符合 | E 会话 `metadata_json` 写入 `sessionMergeOperationId`；`HandoffContextService.isMerged(metadata)` 仅在该键为非空字符串时为 true；`QueryController` 与 `WebSocketController` 两条入口均先 `isMerged` 再 `configure` |
| HandoffRead 为内置只读工具，不注册为全局 Spring Bean，遵循授权框架 | 7.x | 符合 | `HandoffContextServiceTest` 断言 `HandoffReadTool` 无 `@Component`；`OperationAnalyzerRegistry` 专用 `handoff-read-v1` analyzer；binding 变更时 recheck 抛 `HANDOFF_BINDING_CHANGED`；`AuthorizationService` 将 `handoff-read-v1` 纳入 SAFE 读自动放行 |
| 前端来源锁真实依赖服务端 gate、封存后释放、恢复/取消为独立操作 | 第 8 / 10 节 | 符合 | `selectMergeSourceIds` 仅在 `status === 'preparing'` 时返回 `lockedSourceSessionIds`；`resume`/`cancel` 映射后端接口；`dismiss` 仅在不可恢复/不可取消终态可用；面板文案明确“快照封存后可继续使用来源” |

**结论：未发现与架构文档不符或部分符合的条目。**

---

## 三、非合并链路影响分析（核心关注点）

逐一追踪了被修改 / 新增公共符号的所有调用方（grep 定位）：

| 公共符号 | 调用方 | 非合并链路是否触达 | 结论 |
|---|---|---|---|
| `V026_ExtendSessionMerges` | `MigrationRunner` | 仅迁移期执行，不改 `sessions` 表、不改普通请求路径 | 无影响 |
| `MergeProgressRepository` / `MergeHandoffData` | `SessionMergeService`、`MergeSummaryService`、`HandoffReadService` | 无普通请求路径引用 | 无影响 |
| `HandoffContextService.configure / project / inject / canReadAsset` | `QueryEngine`（历史预算 Step1、API 消息准备 Step3、备用预算）、`QueryController`、`WebSocketController` | `handoffOperationId == null` 时返回空投影、预留 0 token；两条入口均以 `isMerged` 守卫 | 普通会话历史预算与 HEAD 基线一致 |
| `ImageRefInjector.injectForApiCall` 新增 `BiPredicate boundHandoffAsset` 重载 | `QueryEngine` | 旧签名保留并委托到新签名，默认 `(path,hash) -> false`；仅当 pathSecurity 拒绝且为 E 会话交接包内图片时走 handoff 绑定校验 | 普通会话图片安全边界不变；E 会话新增读取路径受 `HandoffReadService` 路径/哈希校验约束 |
| `AuthorizationService` SAFE 读自动放行 | 所有经 `OperationAnalyzerRegistry` 的工具调用 | 仅新增 `handoff-read-v1` 一个 analyzerId；`file-v1` 逻辑不变；其他工具（用户自定义、MCP）不受影响 | 不放宽其他工具权限 |
| `OperationAnalyzerRegistry.analyzerFor / isExplicitCoreTool` | 工具授权入口 | 仅对 `HandoffReadTool` 实例返回专用 analyzer；同名 MCP/动态工具仍走 `static-or-remote-v1` | 增强了防冒充保护，非放宽 |
| `SessionMergeController` 新端点 | 前端 `sessionMergeStore` | 新增 REST 路径，不改既有路由 | 无影响 |
| 前端 `MergeOperation` 类型 / `useSessionMergeStore` / `isMergeSource` | `SessionMergePanel`、会话列表 | 锁集合仅由服务端返回决定，不再受本地残留记录影响 | 普通会话可用性更准确 |

**明确结论：本次改动不会对非合并分支的功能链路（普通会话消息收发、权限授权、普通工具调用、工作区、恢复/重连、上下文折叠、图片处理等）造成负面影响或破坏。** 所有新行为均以 `metadata_json.sessionMergeOperationId` 为唯一开关，普通会话不会进入任何新增代码路径。

---

## 四、测试覆盖与执行证据

### 4.1 后端

命令：

```bash
cd backend && ./mvnw -q -Dspring.profiles.active=test \
  -Dtest=V026ExtendSessionMergesTest,MergeProgressRepositoryTest,MergePackageServiceTest,\
MergeSummaryServiceTest,SessionMergeServiceTest,HandoffContextServiceTest,HandoffReadServiceTest,\
QueryEngineUnitTest,OperationAnalyzerRegistryTest,AuthorizationServiceProjectFileScopeTest test
```

结果：退出码 0，无失败用例输出。

| 测试类 | 覆盖内容 |
|---|---|
| `V026ExtendSessionMergesTest` | 迁移 SQL 执行、结构验证、索引/约束检查 |
| `MergeProgressRepositoryTest` | create / active / binding / nextSnapshot / sealed / resume / cancel / commitUnit / progress |
| `MergePackageServiceTest` | 快照封存、目录结构、records/files/gaps/occurrences 清单、hash 校验、复制限制与缺口记录 |
| `MergeSummaryServiceTest` | 分页提取、聚合、预算、失败路径（JSON 错误、长度错误、413、上下文错误） |
| `SessionMergeServiceTest` | start / resume / cancel / execute 流程、封存即释放来源、暂停与错误码映射 |
| `HandoffContextServiceTest` | 普通配置无交接调用；E 配置启用 HandoffReadTool；allow/deny 工具集；无 `@Component` |
| `HandoffReadServiceTest` | ready.json / manifest / catalog 哈希校验；list/search/read/asset；游标预算与 `HANDOFF_READ_BUDGET_EXCEEDED` |
| `QueryEngineUnitTest` | 无 handoff 标记时零交接入口；有标记时入口注入与预算等价；图片注入含 handoff asset 路径 |
| `OperationAnalyzerRegistryTest` | 专用 analyzer；binding 变更抛异常；同名非内置工具不获得 `handoff-read-v1` |
| `AuthorizationServiceProjectFileScopeTest` | PLAN 模式下 HandoffRead 默认策略；同名工具 analyzerId 为 `static-or-remote-v1` 时被拒 |

### 4.2 前端

```bash
cd frontend && npx vitest run src/store/sessionMergeStore.test.ts src/components/session/SessionMergePanel.test.tsx
cd frontend && npx tsc --noEmit
```

结果：
- `sessionMergeStore.test.ts` 43 例通过；`SessionMergePanel.test.tsx` 20 例通过；合计 **63/63 通过**。
- TypeScript 全项目类型检查通过，无错误。

### 4.3 覆盖缺口

- 前端缺少 `resume` / `cancel` 新行为的专门单测（暂停后换模型恢复、取消后通知与锁集合更新）。
- 后端缺少 `MERGE_DISK_SPACE_LOW`（磁盘不足）与 `HANDOFF_INVALID_CURSOR`（游标越界）的针对性用例。
- 未运行 e2e（按要求不启动重型套件），浏览器端整体交互仅依赖单元测试。

---

## 五、问题清单

### Blocker
无。

### Major
无。

### Minor

**M1. `HandoffReadTool.call` 对 `currentRunId` 缺少防御性校验（潜在 NPE）**
- 位置：`backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java`（`call` 方法，约 L28-L45）
- 现状：`String root=subjects.resolve(context.currentRunId()).rootSessionId();` 直接假定非空。
- 依据：文档 7.3 要求通过 `AuthorizationSubjectResolver.resolve(currentRunId).rootSessionId()` 校验绑定，同时要求工具不应在错误上下文中崩溃。
- 影响：当前 QueryEngine 接入路径下 runId 必然存在，线上无即时风险；未来在无 run 的测试/诊断场景直接调用会 NPE 且难定位。
- 建议：前置 `context == null || context.currentRunId() == null` 校验，返回 `ToolResult.validationError("HANDOFF_CONTEXT_MISSING", ...)`。

**M2. `HandoffReadTool` 的 `asset` 分支对 `images == null` 无防护（测试易错点）**
- 位置：`backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java`（四参构造器以 `null` 传入 `ImageResultExternalizer`，约 L20-L31）；`HandoffReadTool` 的 `asset` 分支。
- 依据：文档 7.2 强调图片通过现有 `ImageResultExternalizer` 处理；能力不可用时应返回说明而非崩溃。
- 影响：生产使用 `@Autowired` 五参构造器不受影响，现有测试也注入了 mock；属未来编写测试时的隐含陷阱。
- 建议：在 `asset` 分支对 `images == null` 返回 `HANDOFF_ASSET_UNSUPPORTED` 验证错误。

### Nit

**N1. `MergePackageService` 路径安全校验错误码复用**
- 位置：`MergePackageService.java` 约 L304-L319。
- 现状：绝对路径、`..` 穿越、符号链接、越界均返回 `MERGE_INVALID_REF`。
- 建议：如需诊断区分，可细分为 `MERGE_REF_SYMLINK`、`MERGE_REF_OUT_OF_SCOPE` 等。逻辑正确，仅为可读性。

**N2. 前端轮询间隔硬编码**
- 位置：`sessionMergeStore.ts` 中 `nextActiveCheck = Date.now() + 5000`。
- 建议：常量化或配置化以便调整退避策略。

---

## 六、发布就绪结论

### 6.1 代码质量与规范性
- 分层与落点：合并逻辑集中于 `session/merge/` 包、`engine/HandoffContextService`，权限登记集中于 `authorization/`，与文档第 10 节文件落点一致。
- 错误处理：合并服务统一 `safeCode(e)` 规范化错误码并配中文提示；`HandoffReadService` 对预算超限、哈希不匹配、游标错误返回具体错误码。
- 可维护性：`MergePackageService` 中 snapshot 与 legacy 包处理复杂度较高，但按封存/恢复/导入拆分为小方法，尚可。

### 6.2 文档与 CHANGELOG 同步
- 新增 `docs/session-merge-architecture-v2.md`，完整描述 v2 设计。
- 当前 diff 未包含 `CHANGELOG.md` / `README.md` 更新；按项目既往提交规范，重要特性需有变更记录。

### 6.3 总体结论

**有条件达标。**

依据：
- 架构一致性：与 v2 文档高度一致，无偏离。
- 正确性：无 Blocker / Major；两处 Minor 均为防御性加固，不影响当前线上路径。
- 影响面：非合并链路零回归。
- 测试：后端 10 个关键测试类 + 前端 63 例全绿；TypeScript 类型检查通过。

**建议提交前处理**
1. 补齐 `HandoffReadTool` 两处防御性校验（M1、M2）——工作量极小，可消除唯一残留正确性风险。
2. 同步 `CHANGELOG.md`，补充 Session Merge v2 条目。

**可选改进（非阻塞）**
- 前端补 `resume` / `cancel` 单测；后端补磁盘不足与游标越界用例。
- `MergePackageService.importPrevious` 与 `MergeSummaryService.processStage / split` 适度拆分以降低认知负担。
- 对 `MERGE_SNAPSHOT_CORRUPT`、`HANDOFF_HASH_MISMATCH`、`RECORD_REQUIRES_HANDLING` 等关键错误码输出结构化日志（operationId、sourceIds、原因）以便运维监控。
- `SessionMergePanel` 在 `paused` 状态突出提示“恢复将重试未完成单元并可能再次计费”；对 `targetAvailable === false` 提供“重新合并”或“查看历史交接索引”入口。

---

## 七、风险与假设说明

- 后端测试证据基于 `-q` 静默模式的退出码与无失败输出，未逐用例列出；如需更强证据可去掉 `-q` 复跑。
- 被排除文件（如 `SessionStatusCapsule.tsx`、`sessionStatusMeta.ts`）与合并状态展示存在关联，但按要求未纳入评估，其与 `sessionMergeStore` 的集成正确性不在本结论范围内。
- 本轮为纯审查，未做任何代码修改；是否执行上述加固与 CHANGELOG 同步，等待用户明确指令。
