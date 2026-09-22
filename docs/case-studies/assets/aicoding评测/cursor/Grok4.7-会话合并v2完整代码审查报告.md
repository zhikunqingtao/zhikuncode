# 会话合并 v2 代码审查报告

- 审查模型：Grok 4.7
- 审查日期：2026-09-22
- 仓库：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`
- 对照规范：`docs/session-merge-architecture-v2.md`（更新日期 2026-09-22）
- 审查范围：当前本地未提交改动中的会话合并、交接读取与查询入口
- 审查方式：对照架构规范阅读 diff 与新增文件；未重跑后端 2,975 项与前端 876 项全量测试

## 结论

本次合并改动**不会改写普通聊天、fork 和 Swarm 的执行与保存链路**。按 `docs/session-merge-architecture-v2.md` 的完成标准，**现在还不能当作 v2 完成版提交发布**：真实模型语义验收没有做，合并恢复路径上还有几处会让任务停住或让合并会话请求失败的缺陷。

审查没有包含指定排除的前端文件。下面的结论只覆盖会话合并、交接读取和查询入口。

当前工作区里还有被排除的前端改动。若把整个工作区一次提交，发布内容会带上这次没有审查的界面变更。

## 审查范围

### 纳入审查

后端生产代码：

- `backend/src/main/java/com/aicodeassistant/authorization/AuthorizationService.java`
- `backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java`
- `backend/src/main/java/com/aicodeassistant/controller/QueryController.java`
- `backend/src/main/java/com/aicodeassistant/controller/SessionMergeController.java`
- `backend/src/main/java/com/aicodeassistant/engine/ImageRefInjector.java`
- `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java`
- `backend/src/main/java/com/aicodeassistant/engine/QueryLoopState.java`
- `backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java`（新增）
- `backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/MergeTextBudget.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java`
- `backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java`（新增）
- `backend/src/main/java/com/aicodeassistant/session/merge/MergeHandoffData.java`（新增）
- `backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java`（新增）
- `backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java`（新增）
- `backend/src/main/java/com/aicodeassistant/websocket/WebSocketController.java`
- `backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java`（新增）

对应测试与前端合并界面：

- `AuthorizationServiceProjectFileScopeTest`、`OperationAnalyzerRegistryTest`、`QueryEngineUnitTest`
- `V026ExtendSessionMergesTest`、`HandoffContextServiceTest`、`HandoffReadServiceTest`、`MergeProgressRepositoryTest`
- `MergeFixture`、`MergePackageServiceTest`、`MergeSummaryServiceTest`、`SessionMergeServiceTest`
- `frontend/src/store/sessionMergeStore.ts` 与其测试
- `frontend/src/components/session/SessionMergePanel.tsx` 与其测试
- `docs/session-merge-architecture-v2.md`

### 按要求排除

1. `frontend/src/components/message/ImageBlock.tsx`
2. `frontend/src/components/message/ImageBlock.test.tsx`
3. `frontend/src/utils/messageContent.ts`
4. `frontend/src/utils/messageContent.test.ts`
5. `frontend/src/components/message/MessageActions.tsx`
6. `frontend/src/components/message/MessageActions.test.tsx`
7. `frontend/e2e/message-copy-all.spec.ts`（工作区快照中未见该新增文件）
8. `frontend/src/components/status/sessionStatusMeta.ts`
9. `frontend/src/components/status/SessionStatusCapsule.tsx`
10. `frontend/src/components/layout/Header.tsx`
11. `frontend/src/components/input/PromptInput/PermissionMenu.tsx`
12. `frontend/src/components/input/PromptInput/index.tsx`
13. `frontend/src/components/layout/Sidebar.tsx`
14. `frontend/src/styles/globals.css`
15. `frontend/src/styles/liquid-glass.css`
16. `frontend/src/components/layout/Sidebar.desktop.test.tsx`

## 非合并链路

普通请求的隔离是成立的。

- 三个 REST 入口和 WebSocket 都先看已加载的会话元数据。只有合并目标才会调用 `HandoffContextService.configure`。没有 `sessionMergeOperationId` 时，不查合并表、不读资料包、不携带 `HandoffRead`。
- `HandoffReadTool` 不是 Spring Bean，不会进入全局工具表。fork 和 Swarm 仍从 `ToolRegistry` 取工具，并新建自己的 `QueryLoopState`，不会带上交接标记。
- `SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner`、`SessionExecutionGate`、`ToolRegistry` 本次都没有改动。普通执行、保存、子任务和 Swarm 的失败条件保持原样。
- 文件读取的放行条件只多了分析器 `handoff-read-v1`。同名的动态工具或 MCP 工具不会走这条只读策略；PLAN 模式下也只有真正的内置 `HandoffRead` 能读交接包。`file-v1` 的原有判断没有放宽。
- 图片注入对普通路径仍使用“不允许包外文件”的判断。只有合并会话的投影非空时，才会多放行本包已登记且哈希匹配的图片。路径安全检查先执行，交接包判断只在工程目录拒绝之后作为补充。
- 来源锁只来自执行 gate 里仍持有的 token。快照封存后锁集合为空，来源可以继续聊天和删除。无关会话不会被写进这个集合。
- `QueryEngine` 在没有运行标记时直接返回空投影，历史预算不扣减。`QueryEngineUnitTest` 断言无标记时 phase2 与 phase1 的预算差为 0，且不调用交接服务。`HandoffContextServiceTest` 断言普通配置与原配置是同一对象，且不触达仓库和读取服务。

因此，普通开发、权限、压缩和工具调用不会因为这次改动改变行为。风险集中在**已经合并出来的新会话**，以及**正在进行的合并任务**。

补充边界：

- WebSocket 普通对话沿用全量工具池，`allowedToolNames` 为 `null` 时会给合并目标加上 `HandoffRead`。PROMPT 命令的允许列表和 REST 的 `disallowedTools` 会阻止该工具。这与“全量工具默认可用、显式限制必须遵守”一致。
- 子代理和 Swarm worker 的工具来自 `ToolRegistry`，不复制父会话的 `QueryConfig.tools()`，因此不会自动获得 `HandoffRead`。
- 发布事务把目标会话权限写成 `AUTO_APPROVE`，与现有 Web 新建会话的默认权限相同，权限存在独立列里，不会被只含 `sessionMergeOperationId` 的 `metadata_json` 覆盖掉。

## 架构符合情况

主流程已经按文档收成：空闲检查、独立快照、封存后释放来源、串行提取、有界聚合、校验后在同一事务里创建目标。旧的 3,500 正文阈值和 16 次总调用上限已经去掉。超过 16 个分片，以及合成的长中文响应加未上报 usage，都有测试。`V026` 保留了 v1 行，并用 `active_slot IS 1` 占唯一名额。损坏且无法解析的 checkpoint 会暂停、不发布，测试也锁住了“恢复后仍不能跳过这个缺口”。

已对齐的要点：

- 在线入口走 `seal`，不再调用旧 `build`。未封存的 staging 会在下次封存前删除并重做。封存文件已写好但数据库未记录时，会校验后补记。
- 原文、详细交接和启动摘要分开。单请求有输入预算、可见输出目标和 300 秒超时；中间结果超过可见目标仍可保存。
- 证据别名由程序展开为分片引用。模型输出路径、越界别名和非法状态会被拒绝。
- 运行时绑定查询要求 `protocol_version=2`、`status=completed`，并 JOIN 仍存在的目标会话。删除目标后绑定查询为空。
- `HandoffRead` 只读本包登记文件，拒绝路径穿越和符号链接。搜索使用字面匹配，未扫完时 `complete=false` 并给出游标。
- phase1 先扣交接入口，phase2 用含投影的原文校验总预算，投影不写回消息历史。
- 前端用服务端 active 恢复未结束操作，暂停状态不会被本地记录改写成 preparing。取消不带进度版本。恢复携带 `expectedEpoch`。
- 优雅停机会把进行中的操作记为 `paused/SERVICE_SHUTDOWN`。进程意外退出后，仅 `preparing` 的 v2 操作会在启动时继续。这与文档第 5.2 节一致，也会在重启后继续调用模型。

第 7 节的预算接法与实现一致。尚未满足的是第 9 节后半：A/B 改字段后合并为 E、封存后代码再变化、E 更新待办不回写 A/B 的真实模型样本都还没跑。文档写明 stub 的数据、隔离和接线断言不能描述成真实模型接续开发验收。

## 问题

严重度：P2 是应当在发布前修的缺陷；P3 是影响面较小、仍值得修的问题。本次没有发现会破坏非合并主链路的 P0 或 P1。

### [P2] 聚合开始后换模型会把未完成任务判成输入冲突

位置：`backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java` 的 `plan`；`MergeSummaryService.prepare` 的聚合分片。

聚合单元的编号和 `input_hash` 按当前模型的分片宽度生成。`plan` 在主键冲突时保留旧行，再要求新哈希必须相同，否则抛出 `MERGE_UNIT_INPUT_CHANGED`。提取阶段的哈希不依赖模型，所以提取中途换模型还可以。一旦已经写出 `aggregate-*` 单元，即使用户只是为了完成剩余单元而换一个更小的模型，恢复也会再次暂停。

文档第 5.3 节要求：换模型只重新规划未完成单元，保留已合格结果及实际模型记录。

### [P2] 一部分阻断记录被写成永远无法恢复的缺口

位置：`backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java` 导出消息与内嵌图片处；`recoverBlockedProjections`。

消息形状不合法时，原文会记成 `blocked`，但缺口原因是不带记录号的 `RECORD_REQUIRES_HANDLING`。非法 base64 图片则记成 `RECORD_REQUIRES_HANDLING:invalid_image`。恢复逻辑只接受“原因后缀正好是某条 blocked 记录号”的缺口，这两种都会在重读原文之前直接失败。于是界面提示可以排查后恢复，实际每次恢复都回到暂停。一条非关键的坏图片也会挡住整次合并。

文档第 4.4 节要求：非关键附件未解析时明确标记并保留原件；可恢复的阻断记录应能从本包 raw 重新生成投影。已经损坏、JSON 都读不出来的 checkpoint 会暂停且不发布，这条与文档和现有测试一致，不记为缺陷。

### [P2] 交接入口放不下时，合并会话的普通提问会变成内部错误

位置：`backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java` 的 `project`；`QueryEngine.execute` 的外层异常处理。

入口上限是 `min(2048, 历史预算的 10%)`。最小说明仍然放不下时，`project` 抛出 `HANDOFF_CONTEXT_BUDGET_TOO_SMALL`。这个异常发生在查询循环的模型调用之外，最终被记成一次失败的 run，而不是现有的上下文容量错误。

文档第 7.1 节要求：连最小入口和当前请求都放不下时返回正常容量错误，不把已完成合并改为失败。普通无标记会话不会走到这里。合并操作本身的状态不会被改回失败，但用户在该合并会话里的本轮请求会以内部错误结束。

### [P2] 所有分片都返回空条目时仍会创建目标会话

位置：`backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java` 的 `validate` 与 `prepare`。

校验允许 `items` 为空数组，空数组也算单元成功。若每个分片都这样返回，详细交接条数为 0，启动摘要可以退成只有目录的 `handoff.md`，发布仍会继续。

文档第 6.2 节要求：没有合格结构化提取就不能发布；确定性导航兜底只用于已有详细交接的启动入口降容，不能用原文目录冒充整理完成。提示词允许“没有信息可返回空 items”，这和发布门槛需要分开：单个无关分片可以没有条目，全部详细条目为空时不应发布。

### [P3] 来源是否锁定仍先看 preparing

位置：`frontend/src/store/sessionMergeStore.ts` 的 `selectMergeSourceIds`。

封存后的空锁集合行为是对的，暂停后服务端也会释放 token。判断式仍是“必须处于 preparing 才采用 `lockedSourceSessionIds`”。文档第 8 节要求锁集合本身就是唯一依据，前端不能从 preparing 推断来源锁定。

暂停落库和 `finally` 释放 token 之间若被轮询到，界面会认为来源已空闲，而 gate 仍可能拒绝操作。窗口很短，所以定为 P3。

### [P3] 合并会话每一轮都会把交接清单哈希算两遍

位置：`backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` 查询循环中的 `handoffProjection`，以及 `prepareCompactionContext`。

同一轮里，装填历史和准备压缩各调用一次 `project`。每次都会重新校验 `ready.json` 和清单哈希。两次使用同一预算，phase1 与压缩预算一致，没有把同一笔预留扣两次。普通会话在看到空标记后会立刻返回，不受影响。合并会话的每轮提问都会重复做这些磁盘校验。

### 其他低影响问题

- 折叠后的进度按钮在暂停时仍写“合并结果”。打开面板后的文案、恢复和取消是对的。
- 暂停通知和失败共用错误级别，文案是“合并暂停或失败”。没有本地合并任务时，后台发现错误不会弹“合并失败”，这一点已按复审要求处理。
- 单请求预算低于 512 估算 token 就直接拒绝模型，而分片缩小下限是 256。小模型可能在仍能装下 256 token 资料时被整次拒绝。
- `history_storage_version == 2` 会跳过 checkpoint。当前仓库里已经没有写入这个字段的生产代码，现有会话不会走到这条分支。

## 测试与发布标准

定向测试覆盖了迁移、唯一名额、封存后释放来源、损坏 checkpoint、长历史超过 16 个单元、413 后只拆失败单元、哈希被改后的单元不重付费、游标/跨包/符号链接、PLAN 下的交接只读，以及无标记请求零交接调用。这些和文档第 9 节的结构用例是对齐的。

`MergeSummaryServiceTest` 用重复的“说明”构造超过 4,183 字节的合成响应，并在 usage 未上报时确认不因旧阈值拒绝、费用保持未知。这覆盖了本次失败条件的结构回归，不能代替当时已经丢失的真实模型正文。

还缺的是：

- 聚合开始之后换模型，已完成单元保留、未完成单元按新预算重规划。
- 非法图片，以及不带记录号的阻断缺口，在原件重新可解析后能否恢复。
- 极小上下文窗口下，交接入口不足时是否走正常容量错误。
- 第 9 节的真实模型样本：A 把响应字段从 `name` 改为 `displayName` 并记录验证；B 的前端仍读取 `name`，且有未完成联调待办。二者使用同一临时工程，合并为 E 后完成联调。再验证封存后共享代码发生变化时，E 不凭历史通过记录宣称当前成功。并分别验证 E 更新待办不改变 A/B、A 追加消息不改变 E 资料、删除来源后 E 仍能读取已归档依据。

文档记载后端 2,975 项（2,905 通过、70 跳过）、前端 103 个文件 876 项通过，且生产构建通过。那次验证明确未纳入其他任务的 UI/消息复制变更，也未调用真实付费模型。这次审查没有重跑这两套全量测试，因此不能把那次结果再当作当前工作区的新证据。

代码职责划分和文档第 10 节一致：14 个既有落点加 6 个新生产文件，测试落在规定的迁移、Repository、ReadService、Context 四个新测试文件，以及原有 merge 测试。实现写成了很长的单行方法，后续改恢复和预算时不容易看清事务边界。这不影响当前行为判断。

## 建议

1. 先不要把当前工作区当作 v2 完成版推上去。若要保存进度，只提交合并相关文件，并在说明里写明语义验收未做、上述恢复缺陷仍在。被排除的前端文件应单独提交，不要混进这次合并变更。
2. 换模型时按新预算重建未完成聚合单元，已完成单元的结果文件继续沿用。
3. 阻断缺口一律带上 blocked 记录号。非关键附件解析失败记为非阻断缺口，保留原件。
4. 交接入口超出预算时走现有上下文容量错误，让合并会话的失败形态和普通超窗一致。
5. 发布前增加“零条目不得发布”的断言，并单独做第 9 节的真实模型样本。样本结果不要和 stub 测试混写成已经验收。
6. 前端锁判断改为只看 `lockedSourceSessionIds`。暂停态的浮动入口写明“合并已暂停”，不要显示成“合并结果”。

## 发布判断

| 问题 | 判断 |
| --- | --- |
| 是否破坏非合并主链路 | 从代码边界和现有隔离测试看，不会。普通会话不写 `sessionMergeOperationId`，执行与保存类文件未改。 |
| 合并主链路是否按 v2 接上 | 已接上。封存、进度、恢复/取消、只读工具、每轮入口和前端状态都在。 |
| 是否达到文档定义的完成/发布标准 | 没有。第 9 节真实模型语义样本未执行；聚合换模型、部分阻断恢复和极小窗口入口仍有 P2 缺陷。 |
| 能否作为未完成实现提交到 GitHub | 可以，但只能作为阶段检查点，并且不要把被排除的前端改动打进同一次提交，也不要在说明里写成 v2 已验收完成。 |
