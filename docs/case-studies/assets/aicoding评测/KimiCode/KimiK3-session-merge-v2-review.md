# 会话合并 v2 本地改动全面审查报告

审查日期：2026-09-22 ｜ 基线：HEAD `b98e187` ｜ 规范：`docs/session-merge-architecture-v2.md`（下称"规范"，引用 §号）

## 审查范围与方法

**纳入审查**（git 工作区全部改动减去用户指定忽略清单）：

- 后端主代码 12 改 6 增：`SessionMergeService`、`MergePackageService`、`MergeSummaryService`、`MergeTextBudget`、`SessionMergeController`、`QueryController`、`WebSocketController`、`QueryEngine`、`QueryLoopState`、`ImageRefInjector`、`AuthorizationService`、`OperationAnalyzerRegistry`；新增 `V026_ExtendSessionMerges`、`MergeProgressRepository`、`MergeHandoffData`、`HandoffReadService`、`HandoffReadTool`、`HandoffContextService`
- 后端测试 7 改 4 增；前端 `sessionMergeStore.ts(.test)`、`SessionMergePanel.tsx(.test)`
- 规范文档本身为未跟踪新文件，应随本次改动一并提交

**忽略**：用户指定的 12+4 个前端文件（ImageBlock、MessageActions、messageContent、SessionStatusCapsule、Header、Sidebar、样式等），本报告不对其下结论。

**方法**：规范全文通读；三个并行专项审查（合并核心服务 / 新增文件 / 测试与前端）；共享链路 diff 逐一人工复核；本地实测后端全量套件、前端全量套件与生产构建；对唯一失败用例在 HEAD 纯净 worktree 上做对照实验。

---

## 一、问题 1 结论：对非合并分支功能链路的影响 —— 无破坏

**结论：本次改动不会对非合并分支（普通/fork/Swarm 会话链路）造成负面影响，且有多层证据支撑。**

1. **规范 §10 明令"保持不改"的文件与 HEAD 逐字节一致**（`git diff` 为空）：`SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner`、`SessionExecutionGate`、`ToolRegistry`。普通会话的执行与持久化机制零改动。
2. **普通请求零开销、零行为变化**，逐点核实：
   - `QueryController.java:178/265/362` 与 `WebSocketController.java:959` 的交接启用均以 `HandoffContextService.isMerged(config)` 静态纯元数据检查门控——无 `sessionMergeOperationId` 标记的普通会话**不调用交接服务、不查合并表、不注入工具**（规范 §7.1"没有该标记时不调用交接服务"）。
   - `QueryLoopState.java:31-35` 新增标记为 `@JsonIgnore` 不序列化字段，无默认执行行为（§10）。
   - `QueryEngine` 无标记时直接返回空投影，原历史/预算/工具/图片分支不变；phase2 用原总预算校验含投影 payload，预算不双扣（测试 `QueryEngineUnitTest.java:1948-1986` 佐证）。
   - `ImageRefInjector.java:96-99` 旧重载以 `(path,hash)->false` 委托新逻辑，普通路径行为逐字节等价；新入口仅在路径安全被拒**且**绑定包内已登记、哈希实时复核匹配时放行（§10 授权的窄扩展，未放宽普通文件读取权限）。
   - `AuthorizationService.java:158` 仅将 `handoff-read-v1` 且 `RiskClass.SAFE` 且 effects 恰为 `[READ_RESOURCE]` 的操作纳入只读放行，**未加入 SAFE_INTERNAL**（§7.3 红线）；`OperationAnalyzerRegistry.java:87` 按 `instanceof` 分派，同名 MCP/动态工具分别走 `isMcp()`/`generic` 分支，不能继承权限（测试 `OperationAnalyzerRegistryTest.java:45-66` 佐证）。
   - `HandoffReadTool` 无任何 Spring 注解，全局唯一构造点是 `HandoffContextService`，`ToolRegistry` 只收集 Bean——普通主会话与子代理的工具集合、schema、token 开销不变（§7.2）。
   - `MergePackageService` 旧 `build/packagePath/deleteUnreferenced` scratchpad 分支完整保留，仅供兼容夹具；`V026` 迁移幂等（已有 `protocol_version` 仅 validate），启动时只多一次校验。
3. **实测佐证**：后端全量 2,975 项中除 1 个与本次无关的既有环境失败外全部通过（详见第二节）；前端 894 项全通过。

**唯一需要说明的边界情况**：V026 迁移会在所有环境启动时执行。SQL 与附录 A 逐字一致、事务内执行、中断整体回滚、旧 preparing 行转 failed/LEGACY_INTERRUPTED 并保留旧包——对存量用户库是安全升级，符合 §5.1/附录 A。

---

## 二、测试与构建实测结果（本次审查亲自执行，非引用文档自述）

| 验证项 | 结果 |
| --- | --- |
| 后端全量 `./mvnw -Dspring.profiles.active=test test` | 2,975 项：**2,974 通过、1 失败、70 跳过**，1 分 21 秒 |
| 前端全量 `npm run test:run -- --maxWorkers=2 --minWorkers=1` | **103 文件 894 通过、16 跳过**，48 秒 |
| 前端 `npm run build` | **通过**，仅既有的大 chunk/Browserslist 提示 |

**关于后端唯一失败的定性（重要）**：`WorkspaceFileBoundaryTest.recursiveGrepSkipsProtectedFilesButDirectAccessCanBeAuthorized:269`。

- 该测试只直接构造 `GrepTool`/`PathSecurityService`，依赖链不含任何本次改动的类；
- 在 **HEAD `b98e187` 纯净 worktree 上原样复现失败** —— 是先于本次改动的既有失败，**非本次回归**；
- 根因已定位：**本机未安装 ripgrep**（`rg` 不在 PATH，`/opt/homebrew/bin`/`/usr/local/bin` 均无），`GrepTool.java:86` 启动检测失败后走系统 grep 回退，回退路径对直接指定隐藏目录（`.SsH`）的搜索行为与 rg 不一致，导致断言落空。规范文档第 7 行自述的"0 失败"应是在 rg 可用的环境得出。
- 处置建议：属环境依赖问题而非代码缺陷，但**会挡住任何本机/CI 的全绿门禁**。发布前应在 CI 镜像安装 ripgrep，或修复 grep 回退路径对显式隐藏目录的处理。此外需意识到：rg 不可用的部署环境中 GrepTool 均走回退路径，属既有运营风险，与本次合并功能无关。

---

## 三、规范符合性总结（对照 §3–§10）

主干实现**高度符合规范**：

- §3 固定流程（空闲检查→封存→立即释放→串行提取→有界聚合→校验→事务发布）完整落地；
- §4.3 封存即释放、崩溃恢复三分支（staging 重做/seal 补记/哈希校验）正确；来源释放在首个 LLM 调用前（有测试：阻塞期间来源可发消息、截止后消息不入包）；
- §5.1/5.2 状态机、`active_slot` 唯一索引原子占名、`run_epoch` 仅启动/恢复/取消递增、单元"短事务-无事务-短事务"提交顺序、attempt 幂等记账（usage 缺失记未知不写零费用）全部达标；
- §5.3 三个 HTTP 接口与六个错误码语义正确（active 204、resume 需 expectedEpoch 仅 paused、cancel 幂等/completed 409、MERGE_CANCEL_CLEANUP 等）；
- §6.3 预算分离（删除旧 `max(token,UTF8Bytes)` 与 3,500 混用规则、16 次总上限）、16384/2048/1MiB/256KiB/300s/3 次/256 token 下限全部落实；本次失败条件（4,183 bytes + output 13,370 + 无 usage 不拒绝、>16 单元可完成）有合成回归测试且标注 synthetic；
- §7.2 游标绑定包版本+参数、16KiB/8MiB 上限、15 秒截止与中断检查、UTF-8 边界分页、跨分片重叠去重、长行尾部不漏、路径穿越/symlink 防御、PLAN 模式只读——逐项核实通过且有测试；
- §8 发布短事务（createSessionRecord + metadata 标记 + 入口消息 + completed/handoff_hash/active_slot=NULL 同事务）、cancel 与发布互斥只有一方成功、提交不确定保留现场；前端 store/panel 符合 §8 全部硬性要求（active 恢复、真实锁集合、epoch 控制、无虚假百分比、protocol=1 靠服务端字段降级）；
- 附录 A 迁移 SQL 逐字一致，`active_slot IS 1` 语义保留，V024 checksum 未动；
- §9 第 449 行五条旧断言改写**均属规范授权的正当改写**（方向均为等价或更强），未发现静默削弱；测试全部使用 @TempDir/临时 SQLite/stub provider，不写运行库、不调真实模型。

---

## 四、风险清单（按严重级别）

### 高（建议提交前修复）

**H1. 三类阻断性 gap 形成永久死暂停，含此类来源的合并永远无法完成** —— `MergePackageService.java:598`（非 UTF-8 文本附件如 GBK 日志）、`:531`（base64 非法的内嵌图片）、`:436`（消息级 content_json 解析失败写了无 ref 后缀的裸 `RECORD_REQUIRES_HANDLING`）。三者的共同后果：记录未按 blocked 登记或 gap 缺 ref，`recoverBlockedProjections`（`:232-236`）校验必然抛错 → resume 永远重现同一失败 → 只能取消，而来源内容不变，取消重建后依旧失败。且非关键附件阻断本身违反 §4.4"非关键附件未解析可明确标记并保留原件"。**建议**：非关键附件解析失败降级为非阻断 gap（保留原件+标记）；确需阻断的一律携带 blocked 记录 ref，使配置修复后可恢复。

### 中

- **M1. v2 代码不存在任何到 failed 的转移路径**（`SessionMergeService.java:218-228`）：哈希不符等"确认无法恢复"的损坏也一律转 paused，用户提示"请排查后恢复"对此类情况永远无效。§5.1/§6.3 要求区分。建议至少在错误提示上引导取消，或提供确认转 failed 路径。
- **M2. v1 旧包缺失记阻断 gap**（`MergePackageService.java:608` `legacy_package_missing`）：与 §1 第 5 条"重复合并不依赖旧包存活"、§4.4"不依赖祖先会话存在"冲突。旧包被清理后，E 类会话的再次合并将永久阻断。建议降级为非阻断缺口（标明无法精确跨包去重）。
- **M3. `MergeProgressRepository.java:177` 依赖 `MergeSummaryService.CallUsage`**，而后者反向依赖 Repository，形成包内环，违反 §10"Repository 只依赖 JDBC/事务/序列化"。建议把 `CallUsage` 移到 `MergeHandoffData`。
- **M4. `HandoffReadService.authorize()`（`:49-56`）把所有 IOException 包成 `HANDOFF_INVALID_RESOURCE`**，吞掉 `InterruptedIOException`（用户取消被当成"非法资源"）和 budget 超时（应为 `HANDOFF_READ_BUDGET_EXCEEDED`，§7.2）。`read()` 路径（`:206-208`）同样。建议重抛中断、透出原码。
- **M5. `HandoffReadTool.getDescription()` 缺 §7.1 强制的程序生成指引**（"继续来源任务先读交接详情""目录/未展开计数不代表已了解详情，历史测试不代表当前测试"等）。参考入口侧已有等价表述，工具说明需补齐——这是 §7.1 明确写在"及"两侧的要求。
- **M6. 测试覆盖回退 4 处**（§9 矩阵空洞，均因旧断言删除后未补替代）：
  a. 准入/忙态：审批中（waiting_interaction）拒绝、后台租约忙拒绝、同键不同参数 409、2～5/重复 ID 参数校验；
  b. `stop=length`/截断 JSON 不得当成功的缩小处理（§6.3 表格行）；
  c. v2 seal 路径的磁盘余量/复制配额暂停（现有 `MERGE_DISK_SPACE_LOW` 用例全走 legacy `build` 路径，而在线入口已不调用它）；
  d. `occurrences.jsonl` 重复位置清单与 artifact_manifests/entries 导出（生产代码已实现）零断言；慢 hook/通知 hook 失败不回滚目标无替代测试。

### 低（可在后续迭代处理）

- cancel 与发布竞态时返回 `MERGE_STALE_OPERATION` 而非 §5.3 的 `MERGE_ALREADY_COMPLETED`（`SessionMergeService.java:150`）；
- `lockedSourceSessionIds` 只含根来源，实际 lease 锁了整棵子任务树（`SessionMergeService.java:263`）；
- 请求字节保护阈值 900KiB 低于规范默认 1MiB 且未计入 JSON 转义膨胀（`MergeSummaryService.java:212`）；聚合分批用 UTF-16 字符数与估算 token 混用（`:99`）；"同单元/模型最多 3 次"按单次运行局部计数，持久 `attempt_count` 不参与上限（`:216`）；`details.jsonl` 用 truncate 直写而非原子写（`:119`）；取消时当前 attempt 行永久残留 `running`；
- `MergeProgressRepository`：`failAttempt` 会把已 completed 的 attempt 改写为 error（`:197-200`）；`beginAttempt` 允许 running→running 重复递增（`:166`）；`pause()` 不校验影响行数（`:99-103`）；
- `MergePackageService.packageRoot()` 以 `user.dir` 为解析输入，§4.2 要求不硬编码 cwd（`:89`）；messages/artifacts/checkpoints 分页均 `LIMIT 1` 逐行查询，长历史下数千次往返（性能备注）；
- `V026_ExtendSessionMerges.java:104` `SQL.split(";")` 依赖 SQL 文本无内嵌分号，当前成立但脆弱；
- unit state/operation status 字符串字面量散落，未在 `MergeHandoffData` 集中为常量；
- `HandoffReadService` 命中片段截取可能切断代理对（`:261`）；跨分片续接每分片一次全目录扫描（O(n²)，有 15s 预算兜底）；
- 前端：`selectMergeSourceIds` 在 POST 响应到达前不本地标记来源占用（服务端 gate 兜底）；protocol=1 降级无专项测试；`validatedTerminal` 跳过后续轮询导致 `targetAvailable` 可能陈旧；
- `QueryController`/`WebSocketController` 对 `HandoffContextService` 用字段注入而类内其他依赖为构造器注入（风格不一致，无功能影响）。

---

## 五、是否达到提交 GitHub 的发布标准 —— 结论

**暂未达到，差一步。建议修复 H1 并补齐 M6 测试缺口后再提交；其余中/低项可列入后续迭代。**

| 维度 | 评估 |
| --- | --- |
| 非合并分支安全性 | ✅ 无破坏，证据充分（第一节） |
| 规范符合性 | ✅ 主干高度符合（第三节） |
| 代码质量 | 良好：事务边界、并发栅栏、日志卫生（无全文/凭据）均到位；存在 1 个高级功能缺陷与若干一致性瑕疵 |
| 测试覆盖 | 主体扎实且改写正当；但有 4 处 §9 矩阵空洞（M6），不能说完全满足规范 |
| 实测门禁 | 前端全绿+构建通过；后端在本机有 1 个**既有环境性**失败（缺 ripgrep，HEAD 同现），CI 环境需确保 rg 可用或修复回退路径 |
| 规范自述的遗留验收 | ⚠️ §9 末段**真实模型接续开发语义验收尚未执行**（文档第 3、471 行均明示），stub 测试不能替代；规范允许阶段分提交，但"整体闭合前不宣称完成" |

**建议行动顺序**：

1. 修复 H1（非关键附件降级非阻断 + 阻断 gap 补 ref）——这是唯一会导致用户场景永久卡死的功能缺陷；
2. 补 M6 四项测试缺口（准入/忙态、stop=length、v2 磁盘暂停、occurrences/artifact 断言）；
3. 顺手处理 M3–M5（依赖方向、错误码透出、工具说明补全，均为小改动）；
4. 确认 CI 环境 ripgrep 可用（或修复 grep 回退对显式隐藏目录的处理），使后端套件全绿；
5. 提交时包含 `docs/session-merge-architecture-v2.md` 本身；提交信息中如实标注"真实模型语义验收未执行"。
