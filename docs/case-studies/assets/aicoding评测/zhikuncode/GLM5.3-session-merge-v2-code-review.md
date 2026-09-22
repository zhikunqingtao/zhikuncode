# 会话合并 v2 代码改动深度审查报告

> **审查标识**：GLM5.3 · 会话合并 v2 发布前审查
> **审查日期**：2026-09-22
> **审查基线**：`main` 分支 `b98e187` + 本地未提交改动（49 项）
> **对照规格**：`docs/session-merge-architecture-v2.md`（620 行，全文通读）
> **审查方式**：4 个并行深度审查（共享链路影响分析 / 合并核心模块对照 / 前端审查 / 独立测试验证），全部只读，未修改任何源码

---

## 〇、审查范围说明

本地共有 49 项未提交改动。按委托要求，以下 15 个 UI/样式相关文件**排除在审查范围之外**（不计入结论）：

- `frontend/src/components/message/ImageBlock.tsx` / `ImageBlock.test.tsx`
- `frontend/src/utils/messageContent.ts` / `messageContent.test.ts`
- `frontend/src/components/message/MessageActions.tsx` / `MessageActions.test.tsx`
- `frontend/e2e/message-copy-all.spec.ts`
- `frontend/src/components/status/sessionStatusMeta.ts` / `SessionStatusCapsule.tsx`
- `frontend/src/components/layout/Header.tsx`
- `frontend/src/components/input/PromptInput/PermissionMenu.tsx` / `PromptInput/index.tsx`
- `frontend/src/components/layout/Sidebar.tsx` / `Sidebar.desktop.test.tsx`
- `frontend/src/styles/globals.css` / `liquid-glass.css`

**实际审查范围**：

| 层 | 修改文件 | 新增文件 |
|---|---|---|
| 后端主代码 | QueryEngine、QueryLoopState、ImageRefInjector、QueryController、WebSocketController、AuthorizationService、OperationAnalyzerRegistry、SessionMergeService、MergePackageService、MergeSummaryService、MergeTextBudget、SessionMergeController | HandoffContextService、HandoffReadService、MergeHandoffData、MergeProgressRepository、HandoffReadTool、V026_ExtendSessionMerges |
| 后端测试 | QueryEngineUnitTest、AuthorizationServiceProjectFileScopeTest、OperationAnalyzerRegistryTest、MergeFixture、MergePackageServiceTest、MergeSummaryServiceTest、SessionMergeServiceTest | V026ExtendSessionMergesTest、HandoffContextServiceTest、HandoffReadServiceTest、MergeProgressRepositoryTest |
| 前端 | sessionMergeStore.ts、sessionMergeStore.test.ts、SessionMergePanel.tsx、SessionMergePanel.test.tsx | — |

---

## 一、总体结论

| 维度 | 结论 |
|---|---|
| **非合并链路影响** | ✅ **无负面影响**。隔离设计严格，共享链路定向回归 160/160 通过，「保持不改」清单文件零改动 |
| **测试与构建** | ✅ 独立完整复现文档声称结果：后端 2,975 项（受控环境 0 失败）、前端 103 文件全过、生产构建通过 |
| **架构符合度** | ✅ 五项核心承诺（封存即释放 / 原子发布 / 单一名额 / epoch 防重放 / 预算分离）全部成立；V026 与附录 A SQL 逐行一致 |
| **发布标准** | ⚠️ **工程层面达标，无严重缺陷；但有 6 个中等问题建议发布前处理**，且文档第 9 节自认的「真实模型语义验收」尚未执行 |

一句话总结：**本次改动没有对非合并分支（普通聊天 / fork / Swarm 子代理）造成任何可证实的影响，达到可提交 GitHub 的工程标准；建议先修复 2 个改动量小但用户可见的中等问题，并在发布说明中如实标注语义验收未执行。**

---

## 二、问题一：非合并分支链路是否受影响 —— 结论：无

这是本次审查最核心的问题，从代码、约束清单、回归测试、全量测试四个维度取证。

### 2.1 代码级隔离（逐文件核验）

**QueryEngine.java（4 处接点）— 隔离严格 ✅**
- `handoffProjection()` 首行判断 `state.getHandoffOperationId()==null` 即返回 `Projection(List.of(),0)`，先于任何 repo/brief 访问——无标记路径**零 I/O、零数据库查询**，仅一次小对象分配
- `historyBudget = inputBudget - 0`；phase1、remainingBudget、压缩后重算全部使用 historyBudget ≡ inputBudget，与原逻辑数值等价；phase2 仍用**原 inputBudget** 校验 payload——投影为空时 payload 为同一引用，**无预算双重扣减**
- 仅当 `handoffContext != null && !handoff.messages().isEmpty()` 才走新增 8 参 injectForApiCall；无标记走原 7 参重载
- 413 恢复与换模型重试在同一轮循环内重新经 `handoffProjection` 派生，同一规则；`prepareCompactionContext` 3 个调用点统一经同一 helper
- 唯一非语义变化：新增 `@Autowired(required=false)` 字段

**QueryLoopState.java ✅**：合并运行标记为 `@JsonIgnore private String handoffOperationId`，null 默认；@JsonIgnore 在属性级同时屏蔽序列化与反序列化；grep 确认主代码中无 JSON 序列化落点，现有字段零触碰。

**ImageRefInjector.java ✅**：原 7 参方法委托 8 参版并传 `(path,hash)->false` 谓词——`!allowed && !false ≡ !allowed`，**普通路径逐字节等价**。绑定包分支的 `isBoundAsset`（HandoffReadService L182-192）做 kind=asset + sha256 匹配 + 归一化绝对路径与清单条目相等 + 实际文件内容重哈希的**双重校验**，符合文档 §7.2/§10。

**QueryController.java ✅**：3 处接点均先 `isMerged(session.config())`——纯内存 map 检查，复用已加载 SessionData，**无额外数据库查询**；toolUseContext 创建先于 configure，无 NPE；绑定校验异常仅对合并会话抛出。

**WebSocketController.java ✅**：loadSession 重构为同一单次调用并顺带取 `.config()`（同一已加载对象，无新查询）；加载失败时 `sessionMetadata=Map.of()` → isMerged=false → 不调 configure，**失败路径不阻断普通请求**；PROMPT 工具限制保留（allowedToolNames 贯穿传入，allowlist 不含 HandoffRead 时不注入）。

**AuthorizationService.java ✅**：仅一处追加 `"handoff-read-v1".equals(...)` 判定，对既有 analyzer 全部短路不变；**同名工具无法继承权限**——MCP 先走 isMcp 分支；动态同名工具走 generic 路径（analyzerId 不匹配）→ 正常弹窗；handoff-read-v1 的 SAFE/READ 自动放行前置条件是 analyzer 已完成绑定验证（未绑定抛 HANDOFF_NOT_BOUND）。

**OperationAnalyzerRegistry.java ✅**：`instanceof HandoffReadTool` 位于 isMcp 之后、名称匹配之前——**身份判定而非名称判定**；recheck 检测绑定变化；对普通工具仅多一次 instanceof 开销。

**HandoffReadTool 接入方式 ✅**：无任何 Spring 构造型注解，仅由 HandoffContextService 构造器 `new` 实例化；全库引用仅 3 处（analyzer instanceof、QueryEngine instanceof、ContextService）；**未注册为全局 Bean、不进 ToolRegistry**，普通会话工具池 / schema / token 开销不变。

### 2.2 「保持不改」清单核验

对以下路径执行 `git diff --stat` / `git status --porcelain` 核验，**全部零改动** ✅：

SessionManager、SessionMessagePersistence、SessionExecutionGate、SubAgentExecutor、SwarmWorkerRunner（含 tool/agent/ 整目录、CheckpointService）、ToolRegistry（tool/registry/ 整目录）、FileReadTool、GrepTool、ImageResultExternalizer、SessionController、coordinator/ 整目录。session/ 与 tool/agent/ 树下仅有 `session/merge/*` 变动，属架构文档第 10 节声明的合并侧落点，不违反清单。

### 2.3 回归测试证据

共享链路定向回归（文档第 9 节指定的 10 类）**160/160 通过**，且测试中存在精确断言：

- 「无标记零开销」：`verifyNoInteractions(handoff)` + payload 不含 HandoffRead + 消息不落 state
- 「预算等价」：断言 `phase2 - phase1 == (merged ? 200 : 0)`、`compactionContext.historyBudget() == phase1`
- 「普通会话工具池不变」：merged 才注入、config 原 list 不被修改、allowlist 不增益
- 「同名工具授权」：MCP / 动态伪装均不获得 handoff-read-v1 权限；PLAN 模式下同名未知工具被拒
- 「防意外全局注册」：甚至断言 HandoffReadTool 无 `@Component` 注解

已知缺口（低风险）：413 恢复 / 换模型重试路径**带投影**的场景无显式用例（仅影响 E）；控制器/WS 层未直接断言普通请求不触发 configure（isMerged 为纯 map 检查，风险极低）。

### 2.4 全量测试独立复现

后端 2,975 项在受控环境 **0 失败**。本机原始环境出现的 11 个失败，经三重取证全部归因于环境因素，**0 项归因于本次改动**：

1. 清除 4 个本机导出变量（`ZHIKUN_COORDINATOR_MODE`、`ALLOW_PRIVATE_NETWORK`、真实 DashScope key）后重跑：CoordinatorServiceTest 7/7、SecurityFilterIntegrationTest 9/9、OneKeyRegistryIntegrationTest 7/7 全部通过
2. 用 git worktree 在**干净 HEAD（b98e187）**上运行 WorkspaceFileBoundaryTest：同样失败 → 该失败在未叠加任何本地改动时即存在（GrepTool/PathSecurityService 均不在改动清单中）
3. 补充 ripgrep 到 PATH 后受控环境全量复现 `2975/0/70`

---

## 三、问题二：是否达到 GitHub 发布标准

### 3.1 测试与构建验证（独立执行，非引用文档数字）

| 验证项 | 结果 |
|---|---|
| 后端定向测试第一组（合并相关 9 类） | 83 运行 / 0 失败 / 0 跳过，BUILD SUCCESS |
| 后端定向测试第二组（共享链路 10 类） | 160 运行 / 0 失败 / 0 跳过，BUILD SUCCESS |
| 后端全量（受控环境） | 2,975 运行 / 0 失败 / 0 错误 / 70 跳过，与文档声称**完全一致** |
| 前端全量（`--maxWorkers=2`） | 103 文件全过；894 通过 / 16 跳过（文档称 876，+18 为文档明确排除的其他任务未提交用例，全部通过） |
| 前端合并用例单独核验 | SessionMergePanel + sessionMergeStore = **63/63 通过**，与文档「63 项合并前端用例」一致 |
| 生产构建（tsc + vite build） | EXIT=0，仅有已知非阻断的大 chunk 警告 |

### 3.2 架构符合度总评（合并核心模块）

| 核心承诺 | 结论 | 关键证据 |
|---|---|---|
| 封存即释放（LLM 前释放来源 token） | ✅ | SessionMergeService:189-194 `seal→sealed→leases.forEach(close)`，之后才调 `summaries.prepare`；测试 `sealedSnapshotReleasesSourcesBeforeFirstProviderCall` 在首个 provider 调用阻塞期验证 gate 空闲 + 封存后消息不入包 |
| 不跨复制/LLM 持写事务 | ✅ | `nextSnapshot`/`sealed` 均为短事务；`seal()` 逐行 keyset 分页读、无事务；单元提交顺序（短事务 pending→running → 无事务模型调用 → 原子 rename → 短事务 completed）与文档 §5.2 一致 |
| 原子发布 | ✅ | 单一事务内 createSessionRecord + metadata + 入口消息 + complete；`complete` 条件 `status='preparing' AND stage='publishing' AND run_epoch=?` |
| cancel 与发布竞态只有一方成功 | ✅ | cancel 单条 UPDATE 与 complete 互斥于同一行状态；发布后 cancel 返回 409 |
| 单一 active 名额 | ✅ | `uq_session_merges_active WHERE active_slot=1` 部分唯一索引；paused/failed 仍占名额 |
| epoch 防重放 | ✅ | resume 条件更新 `run_epoch=? AND status='paused'`；服务层 expectedEpoch 比对；assertCurrent 贯穿全程 |
| 预算分离 | ✅ | inputBudget `min(16384, window-生成-系统-安全余量)`；可见 2048；单调用 300s；响应 256KiB；同单元 3 次；缩小下限 256；grep 确认**无 3,500 / 16 次 / 10 分钟残留** |
| 快照 6 步与阻断暂停 | ✅ | staging 全删重建、流式哈希、ATOMIC_MOVE rename、消息计数前后复核；解析阻断→RECORD_REQUIRES_HANDLING；磁盘不足→MERGE_DISK_SPACE_LOW 暂停不降级 |
| 重启恢复 | ✅ | recover() 只对 protocol2+preparing 递增 epoch、running→pending；有快照不取 token；commit 不确定按 DB 权威裁决 |
| HandoffRead | ✅ | 游标 key 绑定 operationId+handoffHash+action/query/ref/过滤；15s 共享截止含校验；8MiB/16KiB 限额；UTF-8/代理对边界；跨片重叠去重（测试精确断言 5 处偏移含跨界匹配）；complete 仅全候选扫描结束；穿越/symlink 逐组件拒绝 |
| 仅 E 可用 | ✅ | configure 仅 metadata 有标记时启用并比对绑定；工具非全局 bean；analyzer instanceof 防同名继承 |

### 3.3 V026 迁移与文档附录 A 逐条核对

- 提取双方 SQL 做 whitespace 归一化比对，85 行 **IDENTICAL**（仅 Java text block 缩进差异）
- 关键细节全部正确：`active_slot IS 1` 未误写为会让 NULL 误通过的 `=1`；`WHERE active_slot=1` 部分唯一索引；旧 preparing→`failed/'interrupted'/'旧版任务中断，请重新发起'/LEGACY_INTERRUPTED`；protocol_version=1 旧行保留；三张表全部列/默认值/索引名一致
- 执行机制：MigrationRunner 单一事务内执行+validate+登记 checksum，迁移中断整体回滚；`execute()` 发现 protocol_version 已存在只 validate 不重复重建；V024 文件零改动（checksum 保留）
- 结论：**无差异**

### 3.4 代码质量评估

- 日志仅记录 request/model/token 数/字节/stop/usage 数字与异常类名，**无全文、无凭据泄露** ✅
- 无 TODO/FIXME/System.out/printStackTrace 残留 ✅
- 依赖方向符合文档第 10 节（Repository 仅 JDBC/Tx/Jackson；ReadService 不依赖编排/QueryEngine/ToolRegistry；无循环依赖）✅
- 资源管理全面 try-with-resources；writer 异常路径 finally 释放 token、清理、writers.remove ✅

---

## 四、问题清单（无严重级；6 个中级）

### 中 1 ｜ HandoffContextService.java:50-52 — E 的绑定异常导致每条消息 500

绑定缺失抛 AuthorizationException、绑定不匹配抛 IllegalStateException，均未捕获——合并会话 E 的**每条后续消息都会 500**。
违反意图：文档 §7.1「连最小入口和当前请求都放不下时返回正常容量错误，不把已完成合并改为失败」。
建议：降级为普通请求 + 一次性警告事件。

### 中 2 ｜ HandoffContextService.java:92-95 — brief 不可读/预算过小硬失败

`reads.brief` 抛 IOException → IllegalStateException("HANDOFF_UNAVAILABLE")；预算过小抛 HANDOFF_CONTEXT_BUDGET_TOO_SMALL——两者使 E 的整次请求硬失败而非降级。包损坏/小窗口时 E 完全无法聊天。
建议：降级为 unavailable/minimal 文本或空投影并告警，让 phase2 走正常容量错误。

### 中 3 ｜ 快照来源树集合未按文档复核

`lockSources`（SessionMergeService.java:111-118）取 token 后只核对一次集合，未实现文档 §4.3 步骤 1 的「集合变动最多重新取得两次，仍不稳定则暂停」；worker 异步执行 `seal()`（MergePackageService:150-157）时重新枚举 descendants 但**不与已锁定集合比对**——`start→seal` 窗口内新增的子会话会被无 token 复制。
缓解：gate 使父来源上新 run 难以出现、消息计数复核兜底部分场景。
建议：seal 前比对两集合，不一致转 SOURCE_BUSY 暂停；补该边界测试。

### 中 4 ｜ MERGE_STALE_EXECUTION 未映射为 409

Repository `changed()`（MergeProgressRepository.java:60）抛 IllegalStateException，Controller 仅处理 Conflict；文档 §5.3 要求并发 resume 失败方「返回当前状态」。本机 synchronized 覆盖主路径，**跨实例条件更新失败会 500**。
建议：服务层捕获该异常转 Conflict 并附最新操作。

### 中 5 ｜ sessionMergeStore.ts:28-31 — 来源锁仍含状态推断

`status==='preparing'` 才返回锁集合。后端在**封存前暂停**（磁盘/配额不足、SERVICE_SHUTDOWN）时 `lockedSourceSessionIds` 非空且 gate 仍持锁，前端却显示来源可用：Sidebar 不禁用、App 不拦截发消息，用户操作将收到服务端 409。与文档「只看 lockedSourceSessionIds、不得从状态推断」不符（风险方向与旧代码相反）。
建议：去掉状态门控，直接返回 `lockedSourceSessionIds`。**注意**：修复时需连带验证反向依赖——被排除文件 `Sidebar.tsx:2,542,862`（selectMergeSourceIds/useSessionMergeStore/openDialog 的「合并中」禁用徽标）与 `App.tsx:86,269,346`（isMergeSource 拦截发消息）均消费该选择器。

### 中 6 ｜ 前端关键分支零测试

resume 的 epoch 拒绝（409 MERGE_STALE_OPERATION 携带 operation → 采纳并刷新）与 `MERGE_ACTIVE_EXISTS` 的 `body.operation` 采纳分支（store:171-179）均无测试——前者是文档 §8 明确要求的语义。

### 低级问题（摘要）

1. `MergeSummaryService.source()`（:176-188）每个 InputRef 线性扫全清单，O(N²)；万级分片时明显变慢，建议一次构建 ref→entry 映射
2. `MergeTextBudget.atomicWrite` 崩溃残留 `.tmp-UUID` 文件（仅本次调用清理）
3. 前端 `control` 路径 `response.json()` 无 catch（代理返回 HTML 错误页时暴露解析错误；refresh 路径有 `.catch(()=>({}))`，不一致）
4. panel:206-207 retryAt 直接渲染 ISO 字符串、errorCode 从不展示
5. 通知仅 push 不按 key 去重，paused→resume→再暂停会短暂叠两条同 key 通知
6. 冷启动采纳 active 的 paused 操作会立即弹 **error 级**通知（paused 可恢复，级别欠妥）
7. 未知 stage 回退文案「提交中…」对运行中的新阶段有误导
8. `protocolVersion?: number` 应为 `1|2`；v1 兼容靠可选字段成立但无显式守卫
9. V026 `execute()` 用 `SQL.split(";")` 逐句执行——当前安全但脆弱，建议显式语句列表
10. 补记路径只核对 version，未校验文档 §4.3 要求的「来源集合」
11. `messageVersion` 只剔除 handoffOrigin，未按文档 §4.2 剔除 metadata 中的 usage，跨 checkpoint 去重精确性略降
12. cancel 中断路径下 attempt 可能停留在 `outcome='completed'`（记账轻微失真）
13. v1 旧包 import 整目录 walk 复制，旧摘要未明确「标为派生参考」（已有 legacy gap 但提示弱）
14. 风格：SessionMergeService 内联 new MergeProgressRepository 与 Spring bean 重复实例、字段注入
15. `OperationAnalyzerRegistry` 的 `isExplicitCoreTool("HandoffRead")` 当前为无操作代码
16. 投影 UserMessage 置于历史头部，原首条为 UserMessage 时连续同角色消息依赖 normalizeTyped 合并（未测）
17. 测试名不符实：store.test 中「晚到 GET 不覆盖」竞态并未被真正构造（该竞态实际由 while(inflight) 串行化防住，设计正确）

---

## 五、测试覆盖缺口（建议补充，按优先级）

1. resume 409 后采纳 + 刷新；MERGE_ACTIVE_EXISTS 的 body.operation 采纳
2. 429/Retry-After 退避序列及耗尽转 `paused/PROVIDER_UNAVAILABLE`（无直接用例）
3. `MERGE_LENGTH_STOP` 触发缩小的直接用例（413 已覆盖）
4. 413 恢复 / 换模型重试**带投影**的回归用例（文档 §9「小模型/多次压缩/413 后入口仍可重建」）
5. `seal()`/`prepare()` 写入路径的磁盘不足（仅旧 build() 路径测试）
6. 快照窗口内来源树新增子会话（对应中 3）
7. 面板 paused UI（暂停原因、retryAt、totalFinal 文案、按钮禁用）；targetAvailable=false 的禁用与提示；protocol=1 只读展示；cancel 幂等/MERGE_ALREADY_COMPLETED
8. 合并会话连续 user 消息的归一化用例

已覆盖的强项：封存即释放 + 截止后消息排除、commit 不确定、哈希篡改（快照/结果/读取三层）、8MiB 截断续扫与跨片去重精确偏移、游标伪造/过滤变更绑定、迁移回滚 + CHECK + NULL 语义、损坏 checkpoint 暂停且 resume 不可绕过、>16 单元、合成 4,183 bytes + 13,370 output 不因 3,500 拒绝且缺 usage 记未知非零费、resume 只重做未完成单元、取消清理延迟到 writer 退出、404/localStorage 写失败/多 tab/不抢焦点。

---

## 六、发布标准最终判定

**工程层面达标**：

- 测试与构建全部独立复现通过（受控环境全量 0 失败）
- 架构五项核心承诺成立，V026 与规格逐行一致
- 无严重级缺陷；普通/fork/Swarm 链路零影响（本文第二节四维取证）
- 代码质量、依赖方向、资源管理、日志规范均合格

**两个发布前建议**：

1. **优先修复中 5（前端锁语义）+ 补中 6 的两个测试**——最接近用户可见缺陷，改动量小（去掉一个状态门控 + 两个测试用例），但需连带验证 Sidebar/App 消费行为
2. **修复中 1/中 2（HandoffContextService 异常降级）**——避免包损坏时合并会话 E 完全不可用（触发面窄但不体面）

**一个明确缺口（须在发布说明中如实标注）**：

文档自身声明「**真实模型接续开发语义验收尚未执行**」——第 9 节要求的 A/B 合并为 E 后完成联调的语义样本（含「封存后共享代码又有变化」变体）均未跑过。当前所有验证均为 stub provider 层面。如果发布标准包含该语义验收，这一项尚未满足；这是文档自认的边界而非代码缺陷，但不应在发布说明中省略。

**附带建议（非阻断）**：CoordinatorServiceTest / SecurityFilterIntegrationTest / OneKeyRegistryIntegrationTest / WorkspaceFileBoundaryTest 对宿主机环境变量和 ripgrep 安装状态敏感，在携带真实开发配置的机器上全量跑会误报失败（本次 11 个失败全部源于此）；如需 CI 稳定可考虑在测试中固定这些环境。

---

## 七、改进建议优先级汇总

| 优先级 | 事项 | 位置 |
|---|---|---|
| P0（发布前） | 来源锁去掉 preparing 门控 + 连带验证 Sidebar/App | sessionMergeStore.ts:28-31 |
| P0（发布前） | 补 resume epoch 拒绝、body.operation 采纳两个测试 | sessionMergeStore.test.ts |
| P1（发布前/紧随） | 绑定校验失败降级为普通请求 + 警告事件 | HandoffContextService.java:50-52 |
| P1（发布前/紧随） | brief IOException / 预算不足降级为空投影告警 | HandoffContextService.java:92-95 |
| P2（下迭代） | seal 前比对来源树集合，不一致转暂停 | SessionMergeService / MergePackageService |
| P2（下迭代） | MERGE_STALE_EXECUTION 映射 409 + 当前状态 | MergeProgressRepository / Controller |
| P3（择机） | O(N²) 清单扫描、.tmp 残留、JSON parse catch、errorCode/retryAt 展示、通知去重等 17 项低级问题 | 见第四节 |
| P3（择机） | 上述第五节测试缺口 | 对应测试文件 |

---

## 附录：验证环境与执行记录

- Java：OpenJDK 21.0.10 LTS (Corretto)；Node v22.14.0 / npm 10.9.2
- 后端定向测试 83 项 + 160 项（18s + 11s）；全量 2,975 项约 94-102s（两种环境各跑一次）；前端全量 52.5s；构建 20s
- 受控环境定义：清除 `ZHIKUN_COORDINATOR_MODE`、`ALLOW_PRIVATE_NETWORK`、`LLM_PROVIDER_DASHSCOPE_API_KEY`、`DASHSCOPE_API_KEY` 四个本机导出变量，并将 ripgrep 15.0.0 加入 PATH
- 全程未修改任何源码文件；验证结束时 `git status` 仍为原始 49 项改动
- 报告基于 4 个独立审查通道的交叉证据，所有「✅」结论均有 文件:行号 级别证据支撑

---

*审查报告完 · GLM5.3 · 2026-09-22*
