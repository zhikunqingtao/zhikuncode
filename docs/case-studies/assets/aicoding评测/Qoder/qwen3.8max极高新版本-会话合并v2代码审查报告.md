# 会话合并 v2 代码审查报告

- 审查日期：2026-09-22
- 仓库：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`（分支 `main`，基线 `b98e187`，改动全部处于未提交状态）
- 审查基准：`docs/session-merge-architecture-v2.md`（该文档本身也是本次新增的未跟踪文件）
- 审查范围：本地全部改动，**已按用户要求排除**下列文件：
  `frontend/src/components/message/ImageBlock.tsx`、`ImageBlock.test.tsx`、`frontend/src/utils/messageContent.ts`、`messageContent.test.ts`、`frontend/src/components/message/MessageActions.tsx`、`MessageActions.test.tsx`、`frontend/e2e/message-copy-all.spec.ts`、`frontend/src/components/status/sessionStatusMeta.ts`、`SessionStatusCapsule.tsx`、`frontend/src/components/layout/Header.tsx`、`Sidebar.tsx`、`Sidebar.desktop.test.tsx`、`frontend/src/components/input/PromptInput/PermissionMenu.tsx`、`PromptInput/index.tsx`、`frontend/src/styles/globals.css`、`liquid-glass.css`

---

## 0. 实际执行的验证

| 验证项 | 结果 |
|---|---|
| 后端全量 `./mvnw -Dspring.profiles.active=test test` | **2975 项：0 失败、0 错误、70 跳过** — 与文档第 7 行声明完全一致 |
| 前端全量 `npm run test:run -- --maxWorkers=2 --minWorkers=1` | **103 文件：894 通过、16 跳过** — 文档第 7 行写的是 876 通过，**该数字已过期** |
| 定向套件（V026ExtendSessionMergesTest、MergeProgressRepositoryTest、MergePackageServiceTest、MergeSummaryServiceTest、SessionMergeServiceTest、HandoffContextServiceTest、HandoffReadServiceTest、QueryEngineUnitTest、OperationAnalyzerRegistryTest、AuthorizationServiceProjectFileScopeTest、ImageRefInjectorMetadataTest、MigrationRunnerV2Test） | 全部通过 |
| 基线文件是否被改动 | `SessionManager`、`SessionMessagePersistence`、`SessionExecutionGate`、`SubAgentExecutor`、`SwarmWorkerRunner`、`ToolRegistry`、`CheckpointService`、`V024_CreateSessionMerges` — `git diff` **均为空**，符合规范第 511 行 |
| 待提交文件是否含敏感物 | 无。`.gitignore` 已覆盖 `backend/.ai-code-assistant/`（第 60 行）与 `log/`（第 64 行），未跟踪文件只有 java/ts/tsx/md |

**改动面统计**：主源码仅 12 个 Java 文件被修改（其中 7 个是规范 §10 表格声明的共享落点，5 个在 `session/merge` 包内），另有 6 个新增 Java 文件、4 个新增测试文件。与规范第 505 行"14 个现有文件 + 6 个新增文件"的声明吻合，**未越界扩张**。

```
 authorization/AuthorizationService.java        |   2 +-
 authorization/OperationAnalyzerRegistry.java   |  19 +-
 controller/QueryController.java                |   8 +
 controller/SessionMergeController.java         |  19 +
 engine/ImageRefInjector.java                   |  16 +-
 engine/QueryEngine.java                        |  32 +-
 engine/QueryLoopState.java                     |   7 +
 session/merge/MergePackageService.java         | 597 ++++++++++++++
 session/merge/MergeSummaryService.java         | 517 ++++++++-----
 session/merge/MergeTextBudget.java             |   7 +
 session/merge/SessionMergeService.java         | 360 +++++-----
 websocket/WebSocketController.java             |  19 +-
 12 files changed, 1211 insertions(+), 392 deletions(-)
```

新增文件行数：`MergePackageService.java` 1122、`HandoffReadService.java` 324、`SessionMergeService.java` 291、`MergeProgressRepository.java` 230、`V026_ExtendSessionMerges.java` 137、`HandoffContextService.java` 88、`HandoffReadTool.java` 47、`MergeHandoffData.java` 40。

---

## 1. 问题一：对非合并分支功能链路的影响

### 结论：**未发现功能性破坏**

规范第 25 行要求"正常开发主链路优先"、第 362 行要求"普通分支不调用交接服务"。逐共享落点复核如下。

#### 1.1 `QueryEngine.java`（最高风险，已确认安全）

- `handoffProjection()`（:1808-1815）在 `state.getHandoffOperationId()==null` 时**立即返回** `Projection(List.of(),0)`，因此 `historyBudget == inputBudget`（:841-842），Phase1 清理与多模态装填的算术**逐字节等价**（:846、:860、:893 三处 `inputBudget` → `historyBudget` 的替换在 reservedTokens=0 时恒等）。
- 该运行标记只由 `HandoffContextService.configure()`（:43）设置，而 `configure()` 只在 `isMerged(metadata)` 为真时被调用（QueryController:178/265/362、WebSocketController:959）→ **普通会话永不进入该分支**。
- **索引位移风险已排除**：`runStartIndex` 是常量 `0`（:717），仅在 :908/:911 传给 `imageRefInjector.injectForApiCall`，两处都**早于** :940 的投影注入点，不存在因前置插入消息导致的下标错位。
- **持久化污染风险已排除**：`HandoffContextService.inject()`（:84-87）在投影为空时原样返回入参；非空时返回 `new ArrayList<>`。`apiReadyMessages`（:919/924/926）与 `state.getMessages()` 均未被改写 → 投影消息**不会被写库**，符合规范第 362 行"不写回 messages"。
- **预算双扣已排除**：Phase2（:951）仍用完整 `inputBudget` 校验含投影的真实 payload，符合规范第 368 行"phase2 用原总预算校验含参考投影的真实 payload，避免重复扣减"；`prepareCompactionContext`（:1835）扣减的是压缩路径预算，属不同层，不重复。
- `handoffContext` 字段用 `@Autowired(required=false)`（:1805），普通路径在解引用之前就返回，**不会 NPE**；扩展/Mock 场景下 Bean 缺失也不影响普通会话。
- 图片注入分支（:907-913）只在 `handoffContext != null && !handoff.messages().isEmpty()` 时才走新重载，否则调用原 7 参重载 → 普通路径**调用签名完全不变**。

#### 1.2 `ImageRefInjector.java`（安全敏感，已确认收窄得当）

- 旧 7 参重载（:96-97）与旧 `validateAndLoad`（:255-257）都委托给新重载并传入 `(path,hash) -> false` 常量谓词 → 普通会话行为**完全不变**。
- 绕过 `pathSecurityService.checkReadPermission` 的唯一入口是 :269 的 `!checkResult.isAllowed() && !boundHandoffAsset.test(ref.path(), ref.sha256())`。该谓词链路为 `HandoffContextService.canReadAsset`（:54-57）→ `HandoffReadService.isBoundAsset`（:182-194），要求同时满足：
  1. `context.currentRunId() != null`；
  2. 由 `AuthorizationSubjectResolver` 解析出的**可信根会话**存在 completed 的 v2 绑定；
  3. 整包哈希校验通过（`verify(ledger,budget)`）；
  4. 目录中存在 `kind=="asset"` 且 `sha256` 匹配的条目；
  5. 该条目路径规范化后**等于**请求路径的绝对规范化形式；
  6. 经 `MergePackageService.safeFile` 复核（拒绝绝对路径、`..`、任意层符号链接）；
  7. **对文件重新计算哈希**并与期望值比对；
  8. 任何 `IOException | RuntimeException` 一律 `return false`（**失败关闭**）。
- 即便上述谓词被绕过，:298-304 的第 ④ 步仍会用实际文件 SHA-256 与 `ref.sha256()` 比对，不匹配即返回 null。**双重校验，未发现可利用路径。**
- 符合规范第 484 行"仅为当前可信根会话绑定包中已登记且哈希匹配的图片增加读取入口；普通路径权限不变"与第 509 行"未放宽普通文件读取权限"。

#### 1.3 `AuthorizationService.java`（2 行）

仅把 `handoff-read-v1` 加入 SAFE 只读自动放行条件（:158）。普通工具的 `analyzerId` 永不为该值 → 零影响。符合规范第 398 行"不能简单加入 SAFE_INTERNAL"（此处是按 analyzerId 而非工具名判定，未污染 SAFE_INTERNAL 集合）。

#### 1.4 `OperationAnalyzerRegistry.java`

- `analyzerFor` 先判 `tool.isMcp()`（:86）再判 `tool instanceof HandoffReadTool`（:87）。同名 MCP 工具走 `mcp` 分析器，同名动态工具走 `generic` → GUARDED/UNKNOWN → **仍需交互授权，不会继承只读放行**。已由 `OperationAnalyzerRegistryTest:44-66` 覆盖，符合规范第 398 行。
- `recheck`（:111-116）重新执行 `authorize` 并比对 `descriptor.resources()`；`ResourceRef` 是 record，`.equals` 为值相等 → TOCTOU 复核**真实有效**（若为普通类则该比对恒假，已排除此隐患）。`recheck` 由 `ToolExecutionGateway:51` 在 `tool.call` 前调用。
- 绑定查询走 `MergeProgressRepository:37-42`，条件为 `protocol_version=2 AND status='completed' AND target_session_id=? AND JOIN sessions`，与规范第 618 行**完全一致**；根会话 ID 仅来自 `subjects.resolve(currentRunId).rootSessionId()`（HandoffReadTool:29、分析器 :107/:113），**绝不取自模型输入或游标**。
- 唯一可疑处：`isExplicitCoreTool("HandoffRead")`（:120）。该方法的**唯一消费者**是 `ToolRegistry:48` 的启动期断言（要求每个内部 Spring Bean 工具都有分析器）。而 `HandoffReadTool` 刻意**不注册为 Spring Bean**（符合规范第 379 行），因此这行**没有必要**，反而让未来任何名为 `HandoffRead` 的内部 Bean 免检，削弱 fail-fast 守卫。**不构成权限提升**，建议删除（P3）。

#### 1.5 `QueryController.java` / `WebSocketController.java`

- 三个 REST 入口（:178、:265、:362）与 WS 入口（:959）全部由 `HandoffContextService.isMerged(session.config())` 短路，普通会话**不查合并表、不读包、不携带 HandoffRead**，符合规范第 25 行与第 360 行。
- WS 侧把原先的 `ifPresent` lambda 改为显式 `isPresent()`（:923-929）以顺带取出 `config()` 作为 metadata —— **复用同一次 `loadSession`，未新增数据库查询**，符合规范第 360 行"复用已经加载的 SessionData.config"。`sessionMetadata` 默认 `Map.of()`，加载失败时 `isMerged` 为假，退回普通路径。
- PROMPT 路径把 `allowedToolNames` 透传给 `configure`（:875 → :959），工具受限时 `enabled=false`（HandoffContextService:44-46），**不注入工具但仍设置运行标记**（:43 在 :44 之前），于是 `project(..., toolAvailable=false)` 生成规范第 392 行要求的"资料工具不可用"说明（:69）。语义正确。
- 普通 `executeQuery` 路径使用全量工具池且传 `allowedToolNames=null`（:840），与该路径既有"无工具限制"语义一致，非回归。
- 遗留观察：WS 侧 `denied` 恒传 `null`（:960），WS 协议本身没有 deny 列表概念 —— 属既有限制，非本次引入。

#### 1.6 `QueryLoopState.java`

新字段 `handoffOperationId` 在**字段与 getter 上**均标注 `@JsonIgnore`（:31-34），不会被序列化，符合规范第 486 行"一个不序列化的合并运行标记，无默认执行行为"。setter 未标注，但因该字段永不进入序列化形式，无实际影响。

#### 1.7 基线隔离（规范第 511 行）

`git diff --stat` 对以下文件**全部为空**，我已亲自验证：

`session/SessionManager.java`、`session/SessionMessagePersistence.java`、`session/SessionExecutionGate.java`、`tool/agent/SubAgentExecutor.java`、`coordinator/SwarmWorkerRunner.java`、`tool/ToolRegistry.java`、`tool/agent/CheckpointService.java`、`config/database/V024_CreateSessionMerges.java`

符合规范第 108 行"此前实现的 SubAgentHistoryPersistence 已撤回"与第 82-83 行"保持原样"的要求。

> **问题一总结**：普通聊天、fork、Swarm、图片注入、历史压缩、授权弹窗、会话删除等链路**均无行为变化**。共享路径的 7 处改动全部由"合并运行标记 / 可信绑定"双重条件收窄，且默认分支保持原签名与原算术。唯一残留隐患是多余的 `isExplicitCoreTool("HandoffRead")`（P3）。

---

## 2. 问题二：发布标准评估

### 结论：**尚未达到可推送 GitHub 的标准**

自动化测试全绿（后端 2975/0/0/70，前端 894/16），但存在 1 个功能性 P1 缺陷、22 项 P2，以及规范自己定义的验收缺口（§9 表 13 行中仅 5 行完全覆盖，语义验收完全缺失）。

---

### P1 — 必须修复后才可推送

#### P1-1｜暂停后的合并永远无法恢复（功能性缺陷，非理论）

`MergePackageService.java:436` 在消息 `content_json`/`meta_json` 解析失败时写入：

```java
} catch (IOException invalid) {
    if (!parseFailure(invalid)) throw invalid;
    if (Files.exists(raw)) record(new Origin(source,row.get("id").toString(),"raw-"+hash(raw,check),"message"),"reference",null,raw,"blocked");
    gap(source,"RECORD_REQUIRES_HANDLING",true);          // ← 无 :ref 后缀
}
```

`:531` 写入 `gap(source,"RECORD_REQUIRES_HANDLING:invalid_image",true)`。

而恢复校验 `recoverBlockedProjections`（:232-236）要求：

```java
String prefix="RECORD_REQUIRES_HANDLING:";
if(!reason.startsWith(prefix) || !blockedRecords.containsKey(reason.substring(prefix.length())))
    throw new IOException("RECORD_REQUIRES_HANDLING");
```

`blockedRecords` 的键是 `record.recordRef()`（:214，由 `"blocked".equals(record.processingPolicy())` 筛出）。两处 gap 的 reason 都**不可能**匹配任何 recordRef：

- `:436` 连前缀冒号都没有 → `startsWith` 直接为假；
- `:531` 的 ref 是字面量 `invalid_image`，不是内容哈希。

**后果**：一旦触发，操作永久停在 `paused/RECORD_REQUIRES_HANDLING`，`resume` 每次都重新抛同一异常，用户唯一出路是 `cancel` —— **丢弃全部已完成单元和已封存资料**。这与规范第 171 行（"封存后修正了解析配置、能够恢复此前阻断记录时，从本包 raw 生成投影到 work，不改 sealed 原文"）和第 179 行（"保留其 raw 文件、可读记录头及已完成单元，转 paused/RECORD_REQUIRES_HANDLING"）直接冲突：raw 确实保留了，但**恢复通道被自己的字符串格式堵死**。

**对照**：`:565` 的写法是正确的 —— `gap(source,"RECORD_REQUIRES_HANDLING:"+Objects.toString(container,last),true)`，携带了可匹配的 ref。

**修复**：把 `:436` 改为携带该记录的 recordRef（即 :435 中 `record(...)` 用的同一 ref），`:531` 同理。各一行。

#### P1-2｜规范强制"保留并扩展"的测试被删除而非改写

规范第 449 行明确要求："保留并扩展原有磁盘、Unicode、路径/权限、幂等、事务不确定提交、**工具 ID 隔离**和前端恢复测试。"实际情况：

| 被删测试 | 规范要求 | 当前覆盖 |
|---|---|---|
| `sameSourceToolIdsNeverEnterENativeHistoryAndENextMessageNormalizes` | §9 第 445 行"原生 tool IDs 不串来源"；第 449 行明令保留并扩展 | **零覆盖，无替代** |
| `rejectsInvalidSourceSetsWithoutOccupyingSessions` | §5.2 第 220 行"2～5 个不同普通来源、主来源在集合内" | **零覆盖**（`SessionMergeService:165` 的校验逻辑无测试） |
| `uncertainCommitAndHookFailureCannotEraseCompletedTarget` | §9 第 442 行"慢 hook"；§8 第 415 行"通知 hook 失败不回滚已完成目标" | **零覆盖**，且 `MergeFixture.hooks` 已成无用夹具 |

规范第 449 行要求改写的五类旧断言（"缺 usage 必须失败"、"超过 16 次必须失败"、"所有来源锁到摘要结束"、"摘要失败必须清包"、"重启全部失败"）**已确证全部完成改写**：`SUMMARY_EXCEEDS_BUDGET`、`SUMMARY_CALL_BUDGET`、`MAX_CALL`、`3500` 在 `backend/src` 内**零命中**；`fiveSourcesStayOccupiedUntilSummaryExits…`、`summaryFailureCreatesNoTarget…`、`textFailureCleansPackage…`、`recoveryFailsInterruptedPreparation…` 均已被新语义用例替换（如 `SessionMergeServiceTest:152` 反向断言包**被保留**）。旧 CopyLimit 的"复制失败仍带警告发布"用例已删除，符合第 449 行末句。**问题只在于上述三项是删除而非改写。**

#### P1-3｜语义验收完全缺失（文档已自认，不可当作已完成）

规范第 453 行要求的接续开发样本 —— A 把服务端响应字段 `name` 改为 `displayName` 并记录验证结果；B 的前端仍读 `name` 且有未完成联调待办；A/B 共用同一临时工程，合并为 E 后要求其完成联调；再加"封存后共享代码又有变化"的变体 —— 在测试树中**完全不存在**：

- `backend/src/test/.../session/merge` 与 `HandoffContextServiceTest` 中 `displayName` **零出现**；
- `docs`、`backend/src/test`、`frontend/src` 全库检索"语义样本/人工标注/semantic acceptance"**只命中标准文档自身**，无任何评测产物、转录或报告；
- "E 更新待办不改 A/B"、"A 追加消息不改 E 资料"、"删除来源后 E 仍能读取已归档依据"三项均无测试（最接近的只有 `SessionMergeServiceTest:108-114` 验证截止边界后的来源消息不进入快照）。

文档第 471 行"真实模型的人工语义样本尚未执行"**至今仍成立**。因此本次改动只能表述为"结构、数据隔离与接线已验证"，**不得表述为接续开发能力已验收**——这也正是文档第 9 行、第 455 行反复自我约束的口径，提交信息不能比文档更乐观。

---

### P2 — 建议推送前修复

| # | 位置 | 问题 |
|---|---|---|
| P2-1 | `MergePackageService.java:~648-651` | **`importPrevious` 目标路径未复核（路径穿越）**。`relative = Path.of(entry.path())` **未规范化**；`"snapshot/a/../../evil.txt"` 能通过 `safeFile(previous,…)`（规范化后仍在 previous 内）、通过 `relative.startsWith("snapshot")`，`Path.of("snapshot").relativize(relative)` 保留内部 `..` 得到 `"a/../../evil.txt"`，`root.resolve(...)` 遂**写到暂存根之外**，`Files.createDirectories(destination.getParent())` 还会在包外建目录，随后 `budget.output(destination, CREATE_NEW)` 落盘。需祖先包被篡改（目录哈希受 DB `handoff_hash` 校验），可利用性低，但违反规范第 156 行明令禁止的路径穿越，且是**全文件唯一一处未用 `safeFile(root,…)` 复核的写入点**。修复：对 destination 再做一次 `safeFile(root, …)` 或规范化后校验 `startsWith(root)`。 |
| P2-2 | `sessionMergeStore.ts:144` + `SessionMergePanel.tsx:222,225` | **后台轮询间歇性锁死恢复/取消按钮**。`refresh()`（轮询）内 `set({ submitting: true })`，而"恢复合并"（:222）与"取消合并"（:225）均为 `disabled={submitting}`。轮询约每 2s 一次 → 按钮随之间歇性禁用；若 GET 挂起，最长 15s（:147 `setTimeout(() => controller.abort(), 15000)`）内**用户无法取消卡死的合并**。违反规范第 420 行（"网络状态单独用 syncing/error"）与第 425 行（"取消不带进度版本，不因后台进度更新被拒绝"）。修复：把轮询的 `syncing` 与用户操作的 `submitting` 拆成两个标志。 |
| P2-3 | `HandoffContextService.java:74,78` → `QueryEngine.java:841` | **预算不足抛未处理异常而非容量错误**。`project()` 抛 `IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL")`，而 :841 不在任何会将其转为容量错误的 try 内（最近的 `catch(RuntimeException)` 在 :1163，作用域仅包裹 LLM 调用且会 `throw e` 重抛）。规范第 371 行要求"连最小入口和当前请求都放不下时返回正常容量错误，不把已完成合并改为失败"。小窗口模型下 E 的每次请求都会变成未处理异常 → REST 500 / WS 通用错误。**仅影响 E，不影响普通会话。** |
| P2-4 | `HandoffContextService.java:41-42` | **绑定缺失时 E 永久不可用，无降级路径**。`reads.binding()` 在无绑定时抛 `AuthorizationException("HANDOFF_NOT_BOUND")`（`HandoffReadService:47`）。E 的 metadata 标记仍在但 DB 绑定消失（合并行被删、库损坏、legacy 行）时，E **每次请求都失败**，无法退回普通会话。 |
| P2-5 | `MergeProgressRepository.java:197-200` vs `:184` | **attempt 终态被覆写 + 迟到 usage 被丢弃**。`failAttempt` 会把已记为 `completed` 的 attempt 强制改写成 `error`（场景：流式成功但本地 schema 校验失败，`MergeSummaryService:231-233`）；随后到达的 usage 回调因 `finishAttempt` 只匹配 `outcome IN ('running','unknown')` 而被静默丢弃。违反规范第 232 行"迟到回调仅能幂等记录自己的 attempt usage"。方向安全（不会误改单元状态、不会重复计费），但**费用少计、终态失真**，与第 203 行"让失败/取消的调用仍可记账"的意图相悖。 |
| P2-6 | `sessionMergeStore.ts:16-19` | **DTO 与规范不符且零运行时校验**。规范第 253-268 行要求**必填**的 `protocolVersion`/`runEpoch`/`snapshotSealed`/`lockedSourceSessionIds`/`progress`/`canResume`/`canCancel`/`targetAvailable` 全部被声明为可选（`?`）；`protocolVersion?: number` 而非 `1 \| 2` 联合类型；:185-187 只校验 `operationId` 与 `request`，`status` 联合、`progress` 形状、数组性均未校验。`protocolVersion` 在 `SessionMergePanel.tsx` 中**从未被引用** → 规范第 427 行"protocol=1 只读展示旧结果，不伪造 v2 绑定/恢复能力"完全依赖服务端 `canResume`/`canCancel` 标志，无客户端纵深防御。 |
| P2-7 | `sessionMergeStore.ts:241` | **幂等 cancel 被误报为失败**。`control()` 无条件 `await response.json()`；规范第 244 行允许"已 cancelled 幂等返回"，若服务端返回 204/空体则 `json()` 抛错 → 被 catch → 向用户显示"操作失败，请重试"，尽管服务端已成功。 |
| P2-8 | `MergePackageServiceTest.java` 约 13-16 处 | **大量测试跑在死代码上**。我已亲自确认 `build()`、`Bundle`、`packagePath(target,operation)`、`writeParts`、`PartWriter`、`exportRuns`、`renderBlock`、`MAX_RECORD_BYTES` 在 `backend/src/main` 下**零调用者**（符合文档第 5 行"旧 build/packagePath helper 仅为兼容已有夹具保留，在线入口不再调用"）。但 Unicode/二进制保真（:198）、checkpoint 去重（:317）、复制配额（:356/369/411）、磁盘余量、验证状态（:428/439）等断言全部跑在这条死路径上，**不能证明 v2 `seal()` 路径正确**。尤其：v2 替代守卫 `MERGE_COPY_INCOMPLETE`（`MergePackageService:576`，由 `:621` 触发）**无任何测试**；`exportSimpleRows("run_envelopes"…)`（:441）的产物导出路径**无断言**。存活路径为 `snapshotPath`（SessionMergeService:105）、`seal`/`validateSnapshot`/`recoverBlockedProjections`（:185-197）、`deleteUnreferenced`（:274）、`safeFile`/`projectionCatalogs`（HandoffReadService）。 |
| P2-9 | `QueryEngine.java:841` + `:1835` | **每轮两次完整包哈希校验**。`handoffProjection` 在主循环（:841）与 `prepareCompactionContext`（:1835，每轮由 :843 调用）各执行一次 → 两次绑定查询 + 两次 `reads.brief(root)`（内含 `verify(ledger,budget)` 整包哈希链）。压缩/413 重入时更多。规范第 362 行禁止全局缓存，但**同一轮内复用一次投影结果**不违反该约束。仅影响 E 的性能。 |
| P2-10 | `MergeSummaryService.java:33-42,90,97,139-146,162` | **提示词与降容标注不达规范要求**。(a) 提取与聚合**共用同一份提示词**，未包含 §6.1 表格（第 282-287 行）中 `state_conclusions` 的具体要求（各来源工作范围、已完成/未完成状态、关键决策、结论保留来源依据），也无聚合语义（有界 brief、详情入口、每来源覆盖）；(b) 降容兜底（:139-145）只打印单元/栏目计数，**没有规范第 321 行要求的"还有 N 项未展开"标注**，状态计数只进 `overview.json`（:146）不进可见 brief；(c) 单单元场景 `brief = readResult(...)`（:90）→ `handoff.md` 会嵌入机器 JSON `{"schemaVersion":2,"items":...}` 而非可读概览，违反第 317 行；(d) `pieces(...,"",content)`（:97）导致 `details.jsonl` 聚合行 `sourceId=""`（:130/134），**丢失来源归属**，与第 289 行"冲突双方保留来源"相悖；(e) `planAggregate` 的 `inputHash = sha256(encode(in))`（:162）**未绑定 snapshotHash/PROCESSOR_VERSION**（提取单元在 :79 有绑定），弱于第 313 行要求；(f) 聚合再分片 `pieces()` 按 4096 字符切且**零重叠**（:97,164-172），不符合第 313 行"保留相邻引用和少量上下文重叠"。 |
| P2-11 | `MergeSummaryService.java:237-238` | **按子串判定容量失败**。errorType 含 `"context"` 或 message 含 `"context length"` 即视为 `capacityFailure` → 走二分而非暂停。非可重试的配置/鉴权错误若文案恰含 "context" 会被误判，**白烧调用次数**（每次二分都是真实计费请求）。 |
| P2-12 | `HandoffReadTool.java:45`、`:35-38` | **错误信息泄漏绝对路径 + ImageIO 空流崩溃**。(a) :45 直接把 `e.getMessage()` 返回给模型，`NoSuchFileException`/`AccessDeniedException`（如哈希校验与打开之间文件被删）会把**包目录绝对路径**带入模型上下文；`AuthorizationException`（:208/:230 抛出）是 RuntimeException，越过 `call()` 的 IOException catch。(b) :35-38 当 `ImageIO.createImageInputStream` 返回 null 时 `getImageReaders(null)` 抛 `IllegalArgumentException`，同样越过 catch，未给出规范第 390 行要求的"不支持类型返回明确说明"。 |
| P2-13 | `MergePackageService.java:89` | **包根路径由 `user.dir` 派生**。`packageRoot()` 调 `databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")))`，而 `DatabaseResolver:43-47` 在 `zhikuncode.database.project-root` 为空（该属性默认值确为空）时**回退到传入的 projectRoot**，即 JVM 启动目录。字面违反规范第 120 行"不硬编码 cwd"。**但需公允指出**：这是本仓库既有约定 —— `CheckpointService:50`、`ArtifactManifestService:48`、`RunControlService:53`、`PermissionGrantRepository:67`、`DurableInteractionService:70`、`WorkbenchRunLinkService:41`、`DataCleanupScheduler:30` 七处同样写法，且 `SqliteConfig:75` 的 DataSource 同源解析，故 DB 与包目录会一起移动，实际风险低于表面。真正稳健的做法是 `databaseResolver==null` 分支已使用的 `PRAGMA database_list`（:90-92）—— 建议生产分支也改用它。 |
| P2-14 | `MergePackageService.java:145-146,386-397` | **非法 UTF-8 终止整个合并**。REPORT 模式解码器遇畸形字节会在 `rawColumns` 中途抛出，**绕过 per-record blocked 路径**直接冒泡终止整次 seal。规范第 164 行要求"不能安全解析的记录先保存 raw 并记阻断原因，不为解决解析问题继续占用来源"。残留 `raw/tmp-*.json` 只能靠重做 staging 清理。 |
| P2-15 | `MergePackageService.java:100-115` | **`descendants()` 无分页全表读取**。加载所有 session 的 `metadata_json` 并在每次不动点迭代中重新解析全部行，且**在持有合并 token 期间**执行 → 大会话库下延长来源占用时间，与规范第 160 行"不跨文件复制或 LLM 调用持有数据库写事务"的精神相悖（虽为只读）。 |
| P2-16 | `App.tsx:490` + P2-6 | **无错误边界，畸形 payload 可崩整个 App**。`<SessionMergePanel/>` 裸渲染，叠加 DTO 零校验：若 `result.warnings` 是 truthy 非数组但带 `.length`，`Panel:211 .map()` 会在渲染期抛错 → 整应用白屏。 |
| P2-17 | `sessionMergeStore.ts:200-203` | **通知未按 key 去重**。`notificationStore.addNotification`（:32-42）只 push，store 未先 `removeNotification('merge-'+id)`；反复暂停/失败会堆叠同 key toast（React 重复 key 警告）。`changed` 守卫只覆盖了常见路径。规范第 426 行要求"通知去重"。焦点未被抢占（被动状态更新），该部分合规。 |
| P2-18 | `MergePackageService.java:160-169` | **`manifest.json` 缺截止边界**。规范第 122 行要求 manifest 含"版本、操作/来源 ID、**截止边界**、清单哈希/计数"。实际写入了 schemaVersion/operationId/snapshotVersion/sources/capturedAt/counts/catalog-hashes，但每来源的消息截止（lastSeq/count，:411 计算）只埋在 session raw 记录里（:413-415），未进 manifest。 |
| P2-19 | `MergeProgressRepository.java:54-55` | **绑定查询 `findFirst()` 无 `ORDER BY`**。若某会话曾存在两条 completed 的 v2 合并记录（规范第 205 行以 `target_session_id` UNIQUE 防止，但旧行/异常数据下不能绝对排除），绑定结果不确定。 |
| P2-20 | `SessionMergeService.java:90-105` | **跨进程并发 `start()` 返回 500 而非协议错误**。唯一索引 `uq_session_merges_active` 能守住"单一未结束名额"不变量（符合规范第 220 行"不能只用内存 semaphore 或 COUNT 后 INSERT"），但竞争失败方抛出裸 `DataIntegrityViolationException` → HTTP 500，而非规范第 272 行的 `MERGE_ACTIVE_EXISTS`。 |
| P2-21 | `HandoffReadService.java:206-208`、`:310-311`、`:256` | (a) 校验阶段预算耗尽被重编码为 `HANDOFF_INVALID_RESOURCE`（仅 message 保留原字样），规范第 387 行要求显式 `HANDOFF_READ_BUDGET_EXCEEDED` 错误码；(b) `skip()` 在续页时**从文件头重读**，且跳过的字节不计入 8 MiB 正文预算 → 单页实际 I/O 可超 8 MiB，与第 387 行"搜索正文每页最多 8 MiB"及"不把 8 MiB 误称为含完整性校验的总 I/O 上限"的谨慎表述不符。 |
| P2-22 | `HandoffReadService.java:106`、`:275` | 目录条目缺 `sha256` 时 `expected.equals(...)` **NPE**；分片后缀 `Integer.parseInt` 可抛未捕获 `NumberFormatException`。两者均以 RuntimeException 形式逃逸到管线通用处理器。 |

---

### P3 / 观察项

- **一次逻辑调用跑三遍完整完整性校验**：`analyze`（:50）、`recheck`（:111）、`execute`（:205）各带独立 15s 预算 → 最坏 ~45s。字面满足规范第 387 行"每次实际读取…共用 15 秒截止"，但成本三倍。
- **游标未签名且 binding key 暴露给模型**：`nextCursor` 内含 `binding`（`sha256(operationId+handoffHash+params)`，:210）。我已追踪确认**无法越权** —— 偏移被 `catalog()`（:128-137，拒绝越 EOF 或前字符非 `\n`）与 `resolve` 的范围钳制（:233）限制在本包内，跨包/跨 action/跨 query 的游标在 :221 的 key 相等校验处失败。符合规范第 388 行"游标不是授权凭据，伪造偏移也不能访问其他包"。签名游标可作纵深防御。
- **`isExplicitCoreTool("HandoffRead")` 多余**（`OperationAnalyzerRegistry:120`），见 §1.4。
- **`HandoffContextServiceTest:29`** 在名为 `ordinaryRequestRetainsTheExactConfiguration…` 的用例里断言 `HandoffReadTool` 无 `@Component`，与用例主题无关。
- **`HandoffReadServiceTest:121-128` 可能零断言通过**：循环对非 completed 单元 `continue`，循环体外无断言；若 `prepare()` 在单个小 extracting 单元处短路（`MergeSummaryService:89-91`），该用例形同空跑。
- **`SessionMergePanel.test.tsx:140,186`** 名为"limits selection to five…"，实际断言 `2 个来源会话正在复制`（因 mock 的 `lockedSourceSessionIds` 只有 A/B，而提交了 5 个来源），名实不符。
- **`sessionMergeStore.test.ts:487`** 名为"serializes progress polling…"，实际证明的是 GET **从未发出**（`toHaveBeenCalledTimes(1)`、`finishPolling` 保持 undefined）。
- **`MergeSummaryServiceTest`** 合成响应用 4,500 字节，规范第 440 行要求 4,183 字节 —— 数值不精确但结论等价（我已确认 `backend/src` 内 `SUMMARY_EXCEEDS_BUDGET`/`3500`/`MAX_CALL` **零命中**，阈值已彻底移除）。用例已按第 455 行要求标为合成。
- **`MergePackageServiceTest:45,72,121,138,190`** 的哈希断言属同义反复（`validateSnapshot(path, snapshot.hash())` 按构造返回同一哈希），仅"不抛异常"部分有信息量。
- **`SessionMergeServiceTest:48`、`MergeSummaryServiceTest:38`** 把断言写在 `doAnswer` 内部 → 失败会表现为"合并被暂停/失败"和误导性的下游状态断言，而非真实原因。
- **`MergePackageServiceTest:173`** 在测试体内重复执行 `MergeFixture:44` 已应用的 V026 迁移。
- **超时被延长而非放宽**：`SessionMergeServiceTest:59`（20s deadline）、`:105/:135`（5s→10s latch）。符合文档第 7 行"未放宽断言或超时"的声明，但失败反馈变慢。
- **`MergeHandoffData.java:33-34`** 的 `FileEntry` 比规范第 133 行字段集多一个 `sourceId`。属附加字段，低影响。
- **`SessionMergeService` 发布事务的消息 meta** 只带 `operationId`，规范第 410 行还要求标记"历史参考"——该语义目前只体现在消息正文里，未进 meta。
- **`docs/session-merge-architecture-v2.md` 推送前需脱敏判断**：第 48-64 行含**真实会话 UUID**（`caf441d4-5ef8-44ea-b2ff-2b5425f749f9`、`82f4e5e2-…`、`f7c8e232-…`、`cb11ed96-…`）、**本地数据库路径**（`backend/.ai-code-assistant/data.db`）、**日志行号**（`log/app.log:19692`、`:19788`）以及真实 usage 数字。非凭据，但属内部事故细节，推公开仓库前建议评估。
- **文档自身数字过期**：第 7 行"前端全量 103 文件：876 通过、16 跳过" —— 实测为 **894 通过**。后端 2975/2905/70/0 的数字**准确**。

---

## 3. 规范符合度总览

### 3.1 已确证符合的核心要求

逐条核对到代码行，以下要求**实现忠实**：

- **迁移（附录 A）**：V026 SQL 与附录 A **逐行等价**（规范化后 diff 为 IDENTICAL）。`active_slot IS 1` 未被削弱为 NULL 可通过的形式（V026:37）；部分唯一索引 `uq_session_merges_active … WHERE active_slot=1`（:58）；两张新表的 FK `ON DELETE CASCADE`（:61/:84）；四个索引齐备；保留 V024 全部 12 个列名并**重新执行** `new V024_CreateSessionMerges(jdbc).validate()`（:113）；`execute()` 在 `protocol_version` 已存在时**只 validate 不重建**（:103-106，幂等）；旧行按 `protocol_version=1`/`active_slot=NULL`/`preparing→failed`+`LEGACY_INTERRUPTED`+stage `interrupted`+"旧版任务中断，请重新发起" 复制（:47-53）；整个 PROJECT 作用域包在 `MigrationRunner:87-111` 的单个 `TransactionTemplate` 内，失败 → `ApplicationContextException` 拒绝启动；旧 `target_session_id` 重复时由 UNIQUE 约束触发**整体回滚而非删行**（符合第 527 行）。
- **并发与状态机**：单一未结束名额由**数据库唯一索引**保证（非内存 semaphore、非 COUNT-then-INSERT）；`run_epoch` 仅在 create(=1)/resume/cancel 递增，普通进度不递增；所有推进与发布均以 `WHERE operation_id=? AND run_epoch=? AND status='preparing'` 围栏（`assertCurrent`，repo:75-78）；单元三段式提交顺序正确（短事务 beginAttempt → 无事务读料/调用/校验/`atomicWrite` → 短事务 commitUnit 带 epoch+state 围栏）；completed 单元经 `readResult` 哈希校验后复用（`MERGE_RESULT_HASH_MISMATCH`）；cancel 递增 epoch 使 cancel 与 publish 在 DB 层互斥，且**永不删除已发布目标**；崩溃恢复只挑 `status='preparing'` 的 v2 行、递增 epoch、`running→pending`、attempts `running→unknown`，**不静默收养未提交候选**；优雅停机 `closing=true` → `paused/SERVICE_SHUTDOWN` + abort；**无数据库写事务跨文件复制或 LLM 调用**；幂等键语义正确（同键同参返回原操作，同键异参 409 `MERGE_IDEMPOTENCY_CONFLICT`）。
- **HTTP 协议**：`GET /active` 无操作时返回 204；`POST /{id}/resume` 收 `{expectedEpoch, model?}`、仅从 paused、epoch 条件更新、经 `writers.putIfAbsent` + `synchronized` 保证**只启动一个 worker**，重复 resume → 409 `MERGE_STALE_OPERATION` 并携带当前操作；`POST /{id}/cancel` 无版本参数、可取消 preparing/paused/failed、已 cancelled 幂等、completed → 409 `MERGE_ALREADY_COMPLETED`；七类错误码全部存在且使用正确；冲突响应体为 `{error:{code,message}, operation?}`（`@RequestMapping(produces="application/json")` + `@ExceptionHandler`）；Operation DTO 字段与规范第 252-269 行**逐项对应**，`lockedSourceSessionIds` 来自**真实 gate**（`gate.mergeOperationId`，`SessionMergeService:263`）而非由 `preparing` 推断，封存后立即释放并通知（:190-195）—— `sealedSnapshotReleasesSourcesBeforeFirstProviderCall` 断言仍在 `preparing` 时 `gate.isBusy==false`。
- **发布事务**：事务前校验封存资料 + `ready.json` 哈希链 + 当前 epoch + 捕获目录仍绑定工作区（:202-205）；单个短事务要求 status/stage/epoch 三重条件，用预留 ID + 捕获的目录/标题/模型 + 既有默认权限创建 session，仅向 E 的 `metadata_json` 写 `sessionMergeOperationId`（普通会话不写），写一条有界交接入口消息，更新 completed + `handoff_hash` + `active_slot=NULL`；提交后按"释放 worker → 通知状态 → `notifySessionCreated`"顺序，hook 失败不回滚；**不复制来源原生 tool calls**；回滚保留资料与已完成单元（`failedPublicationRollsBackTargetAndPreservesPreparedFiles` 断言零消息 + `ready.json` 完好）；提交不确定时重新核对持久化 completed 与 session 行再决定暂停。
- **快照与清单**：staging→snapshot 同目录 `ATOMIC_MOVE`（:173）；`seal.json = {snapshotVersion, manifestHash}`（:172）；`records.jsonl` 字段集与规范第 150 行一致（`MergeHandoffData:31-32`）；`recordRef` = 对 origin JSON 的完整 SHA-256（:369, :1098-1101）；**仅按 ref 去重**，重复位置一律写入 `occurrences.jsonl`，**不跨来源按相同文本去重**（:368-376）；32 KiB 分片按**码点**切分并拒绝孤立代理对（:677-688）；旧 16 MiB 致命规则（`MAX_RECORD_BYTES:71`）只残留在死代码 `build()`/`exportRuns` 中，**存活路径改用有界 `substr` 分页**（:124-147）+ 物化上限 → 保存 raw 后转阻断记录（:400-407）；`artifact_manifests`/`artifact_entries` 分页只读导出、**每行一条记录**、`role=reference`、按固定 PRAGMA 列序计算原行哈希（:442-451, :485-493）；子任务未保存过程记为**非阻断** gap（:568-572），已保存但损坏的 checkpoint 为**阻断**且不可被空恢复结果放行（:565）；文件名全部程序生成，`safeFile` 拒绝绝对路径/`..`/任意层符号链接（:304-319）；所有存活写入均经 `MergeTextBudget`/`CopyBudget` 磁盘守卫（:358, :379, :386, :687, :170-172, :278-286），新增的 `atomicWrite` 也走 `write → output → reserve`（`MergeTextBudget:34-40`）；重复合并按 ref 并集从祖先**自有包**导入并校验先前快照哈希（:601-672）；清理规则正确 —— writers-stopped 守卫（`SessionMergeService:271-275`）、只删本操作目录（:1112-1121）、启动时与下次合并时惰性清理并用 `NOT EXISTS(sessions)` 确认目标确已消失（`MergeProgressRepository:47-53`）、DB 不可读时捕获并保留数据；**普通 `deleteSession`（`SessionManager:595-625`）不触发任何合并查库或磁盘扫描**，符合规范第 112 行与第 415 行。
- **预算与重试**：存储 bytes / 请求 bytes / 估算输入 token / 可见输出 token / 含推理的 provider usage **五者分离**；`capacity()` 只用 token 估算（`MergeSummaryService:52`），provider `outputTokens` 仅记录不参与准入（:333-336）；默认值 16384 请求目标（:58）、2048 可见/brief 目标（:54/58/145）、900 KiB 请求分片守卫（:212，比规范 1 MiB 略严）、256 KiB 响应守卫（:311）、300s 单调用超时配 AbortContext/LlmCallContext（:301-303/327）且**无整任务截止**、3 次自动尝试 + 2s/5s 退避 + 遵循 Retry-After（:216/249-250）、256 token 缩小下限 → `MERGE_MIN_UNIT_FAILED` → 暂停（:269）、推理预留 `min(32768, window/4)` 且受 maxOutput 约束（:53-56）、安全余量 `window/20 = max(1024, 5%)`（:59）；**无固定 16 次总调用上限**；usage 缺失记 `usage_reported=0` + `usage_json/estimated_cost_usd=NULL`，**绝不写成零费用**（`MergeProgressRepository:181-194`）；split 父单元既不参与执行（`state<>'split'` 跳过）也不计入进度（repo:225）。
- **本次失败条件的回归保证（规范第 440 行）**：4,183 字节合法中文响应**不会**被任何 3,500 阈值拒绝 —— 唯一输出闸门是 256 KiB 字节上限（:311）、stop reason/完整性（:343-345）与 `validate()`；provider `output=13370` 仅记录不参与准入；超目标中间结果按第 344 行表格第一行"保存详细结果"。> 16 个单元可完成 —— `processStage`（:207-261）会排空全部规划单元，无任何调用/单元数上限。`MergeSummaryServiceTest:55-73` 两个用例正是针对此条件。
- **读取工具**：action 限定 `list|search|read|asset`，search 要 `query`、read/asset 要 `ref`（:59-60, :166-178）；统一返回 `entries`/`complete`/`nextCursor`/`warning`（:302-304）；字面 `indexOf` 匹配，**不调 shell、不受 .gitignore 影响、不建索引**（:259）；UTF-8/代理对安全的跨缓冲 carry + query 长度重叠 + 相邻分片前瞻且去重正确（:251-294，经坐标分析确认无重复/漏匹配，`HandoffReadServiceTest:129-150` 覆盖）；游标绑定包版本 + action/query/过滤条件（:210, :221）；`complete=true` **仅在全部候选扫描结束后**给出；asset 经 `ImageResultExternalizer` + `canReadAsset` 哈希门控并纳入既有多模态预算（`QueryEngine:907-911`）；所有引用映射到包内独立副本，**不返回源会话或临时路径**。

### 3.2 §9 验收表覆盖度

**13 行中：5 行 COVERED、8 行 PARTIAL、0 行完全 MISSING。**

| 规范行 | 用例 | 判定 | 主要缺口 |
|---|---|---|---|
| 435 迁移与单一操作 | `V026ExtendSessionMergesTest:34,44,59`；`MergeProgressRepositoryTest:37-45`；`SessionMergeServiceTest:82,124,138` | **COVERED** | 两浏览器场景为顺序模拟，无并行线程准入竞争 |
| 436 占用与释放 | `SessionMergeServiceTest:146-151,100-115` | PARTIAL | 审批占用、后台服务 lease、第五来源忙、2~5 集合校验（P1-2）、阻塞期间**删除**来源、无关聊天可用 —— 均未测 |
| 437 普通/fork/Swarm 隔离 | `MergePackageServiceTest:95`；`SessionMergeServiceTest:85` | PARTIAL | checkpoint 去重跑在死代码 `build()` 上（P2-8）；无 fork 专项 |
| 438 长历史与重复 | `MergeSummaryServiceTest:65`；`MergePackageServiceTest:153`；`HandoffReadServiceTest:129,178` | PARTIAL | "checkpoint 重复位置不灌入 LLM" 无重复 checkpoint 夹具到达 `prepare()` |
| 439 极端记录/附件 | `MergePackageServiceTest:35,51,63,75,132,110,153` | **COVERED** | 字节级 Unicode/二进制断言（:198）在死路径上 |
| 440 本次失败条件 | `MergeSummaryServiceTest:55-64,65-73`；`SessionMergeServiceTest:55` | **COVERED** | 用 4,500 字节而非 4,183；3,500 阈值的缺席仅由"成功"隐含证明 |
| 441 当前单元恢复 | `MergeSummaryServiceTest:74,47`；`SessionMergeServiceTest:116-129,207` | PARTIAL | `stop=length`（`MERGE_LENGTH_STOP`）、**带错误说明的一次修复重试**、schema 反复失败→缩小 —— 均未测 |
| 442 发布与取消竞态 | `SessionMergeServiceTest:152,170,130,186,195` | PARTIAL | **慢 hook / hook 失败**（P1-2）、文件写**之前**的故障、并发第二个 completed 包下"不误删" —— 均未测 |
| 443 绑定与重复合并 | `MergePackageServiceTest:171`；`HandoffReadServiceTest:41`；`SessionMergeServiceTest:195` | PARTIAL | 重复合并未经 `SessionMergeService`/LLM 端到端（测试用裸 SQL + 直接 `seal`）；"删目标只清自己的包"在存在另一 completed 包时无测试 |
| 444 接续资料与状态隔离 | `HandoffReadServiceTest:121`；`MergeSummaryServiceTest:47`；`SessionMergeServiceTest:100-115` | **PARTIAL（最弱）** | **"E 使用独立 ID 和空文件缓存"、"E 发消息/压缩/更新待办或设置/删除不改 A/B" 零测试** —— 全库无任何测试触及 `FileStateCache`/`TodoWriteTool` 的会话隔离；v2 产物导出路径 `exportSimpleRows("run_envelopes"…)` 无断言 |
| 445 请求上下文 | `QueryEngineUnitTest:1950-1983`；`HandoffContextServiceTest:24-30,41,52,58` | PARTIAL | **原生 tool IDs 不串来源（P1-2）**；"小模型/多次压缩/413 后入口仍可重建"无测试；`QueryController`/`WebSocketController` 的交接门禁**完全无测试** |
| 446 读取与权限 | `HandoffReadServiceTest:41,50,63-75,129,144,151,165,178,191`；`OperationAnalyzerRegistryTest:44-66`；`AuthorizationServiceProjectFileScopeTest:35-45` | **COVERED** | "跨包 ref" 只用了字面串 `foreign-package-ref`，未构造第二个真实包 |
| 447 UI 与控制 | `sessionMergeStore.test.ts:398-487` 及既有 `:12-384`；`SessionMergePanel.test.tsx:45` | PARTIAL | Panel 的 v2 新渲染（暂停原因、`completedUnits/knownUnits`、恢复/取消按钮、`targetAvailable===false` 禁用打开目标）**无测试**；stale-epoch resume 409 → 刷新无前端测试 |

### 3.3 隔离证明的强度

**已确证**：§1.7 列出的 8 个基线文件 `git diff` 为空；"普通请求零交接调用"由 `QueryEngineUnitTest:1976`（`verifyNoInteractions(handoff)`，标记缺失）与 `HandoffContextServiceTest:28`（`verifyNoInteractions(repository,reads,subjects,tokens)` + `isSameAs(config)`）证明。

**强度不足**：两处证明均为 **mock 级**。**没有任何测试用真实临时 SQLite 验证普通请求不触碰合并表**；REST/WS 两个入口的交接门禁**完全无测试**。规范第 445 行的"普通无标记不调用交接服务，工具定义/历史/预算保持基线"目前只有单元级证据，缺少入口级证据。

### 3.4 测试卫生

- **无 `@Disabled`/跳过隐藏缺口**，**无测试写入运行库**（全部 `@TempDir` + `SQLiteDataSource`：`MergeFixture:36`、`V026…Test:21`、`MergeProgressRepositoryTest:27`），**无真实付费模型调用**（provider 全为 `mock(LlmProvider.class)`）。符合规范第 431 行。
- `MergePackageService:87-93` 的 `packageRoot()` 在 `databaseResolver` 为 null（测试）时回退到 `PRAGMA database_list`，故测试确实用临时库目录 —— 这也侧面说明 PRAGMA 方案更稳健（见 P2-13）。
- 主要卫生问题集中在 P2-8（死代码测试）、P3 中列出的名实不符/同义反复/`doAnswer` 内断言。

---

## 4. 建议

### 不建议现在推送。最小放行清单：

1. **修 P1-1**（两行：`:436`/`:531` 的 gap reason 带上真实 recordRef）。这是唯一会让用户**丢失全部合并成果**的功能缺陷，且修复成本极低。
2. **补回 P1-2 的三个被删测试**（工具 ID 隔离、2~5 来源集合校验、hook 失败/慢 hook）。规范第 449 行是硬性要求，不是可选项。
3. **修 P2-1**（`importPrevious` 目标路径用 `safeFile(root,…)` 复核）与 **P2-2**（把轮询 `syncing` 与用户操作 `submitting` 拆成两个标志）。前者是全文件唯一违反规范明令禁止项的写入点；后者是用户最能直接感知的缺陷（卡死的合并无法取消）。
4. **修 P2-3 / P2-4**：让 E 在预算不足或绑定缺失时降级为普通会话或返回规范容量错误，而不是抛未处理异常。这两项决定 E 在边缘条件下是"变慢"还是"变砖"。
5. **修 P2-6 / P2-7**（DTO 必填化 + 运行时校验 + `control()` 容忍空响应体），并给 `SessionMergePanel` 加错误边界（P2-16）—— 否则一个畸形服务端响应能白屏整个应用。
6. **在提交信息 / PR 描述中如实写明 P1-3 的状态**："结构与数据隔离已自动验证；规范第 453 行的真实模型接续开发语义样本尚未执行。"文档第 9 行、第 455 行、第 471 行已经这样自我约束，提交说明**不能比文档更乐观**。
7. **顺手项**：删除多余的 `isExplicitCoreTool("HandoffRead")`；更新文档第 7 行前端数字 876 → 894；评估 `docs/session-merge-architecture-v2.md` 第 48-64 行的真实 UUID / 本地 DB 路径 / 日志行号是否脱敏后再公开。

### 可进后续提交，但建议尽早处理

**P2-8** 优先级高于其余 P2：约 13-16 个测试跑在死代码 `build()` 上，而 v2 存活路径的 `MERGE_COPY_INCOMPLETE` 守卫、`exportSimpleRows` 产物导出**零覆盖**。这会让"测试全绿"对 v2 实际行为产生**虚假信心** —— 正是本次审查中最容易误导后续维护者的一项。

其余 P2-5、P2-9~P2-22 属健壮性、可观测性与规范字面符合度问题，方向安全（不会损坏数据、不会破坏单一目标不变量），可分批处理。

### 总体判断

这是一次**架构纪律良好**的实现：改动面严格控制在规范声明的落点内，基线文件零改动，共享路径的 7 处改动全部由双重条件收窄且默认分支保持原语义，迁移 SQL 与附录 A 逐行等价，并发/状态机/发布事务的核心不变量（数据库唯一名额、epoch 围栏、三段式单元提交、原子发布、cancel 与 publish 互斥）均有实现且有测试。规范第 9 行"本轮复审修正"列出的六项自我纠正（撤回全量落库、撤回全局工具注入、普通请求不调用交接服务、损坏 checkpoint 不经空恢复发布、搜索不漏块内后续命中、读取全程受截止/中断检查、无合并任务时不弹"合并失败"、恢复/取消与轮询串行）**在代码中均可核实**。

阻碍发布的不是架构问题，而是**三类收尾缺陷**：一个字符串格式不一致导致的不可恢复暂停（P1-1）、三项被删除而非改写的隔离测试（P1-2）、以及规范自己定义但尚未执行的语义验收（P1-3）。前两项可在数小时内闭合；第三项需要真实模型与人工标注，应按文档口径如实标注为未完成，而非以结构测试替代。
