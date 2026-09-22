# 会话合并 v2 代码审查报告

- 审查日期：2026-09-22
- 审查基线：工作区全部本地改动（基线提交 `b98e187`），对照架构规范 `docs/session-merge-architecture-v2.md`
- 审查范围：后端 12 个修改文件 + 6 个新增文件 + 10 个测试文件；前端 `sessionMergeStore.ts`、`SessionMergePanel.tsx` 及其测试
- 按委托要求排除的文件：`ImageBlock.tsx/.test.tsx`、`messageContent.ts/.test.ts`、`MessageActions.tsx/.test.tsx`、`message-copy-all.spec.ts`、`sessionStatusMeta.ts`、`SessionStatusCapsule.tsx`、`Header.tsx`、`PermissionMenu.tsx`、`PromptInput/index.tsx`、`Sidebar.tsx`、`globals.css`、`liquid-glass.css`、`Sidebar.desktop.test.tsx`

## 一、验证结果（本次实跑）

| 验证项 | 结果 |
| --- | --- |
| 后端定向测试（V026ExtendSessionMergesTest、MergeProgressRepositoryTest、MergePackageServiceTest、MergeSummaryServiceTest、SessionMergeServiceTest、HandoffContextServiceTest、HandoffReadServiceTest、QueryEngineUnitTest、OperationAnalyzerRegistryTest、AuthorizationServiceProjectFileScopeTest，共 10 类） | 通过，退出码 0 |
| 前端合并用例（sessionMergeStore.test.ts + SessionMergePanel.test.tsx） | 63 通过 / 0 失败 |
| 前端生产构建 `npm run build` | 通过，退出码 0 |

说明：文档声称的隔离副本全量验证（后端 2,975 项、前端 103 文件 876 项）本次未完整重跑，以上为针对合并链路的定向验证。

## 二、问题 1：非合并分支链路影响评估 —— 结论：无破坏

逐链路确认隔离有效：

- **QueryLoopState**（`QueryLoopState.java:30-35`）：仅新增 `@JsonIgnore` 不序列化标记，无默认行为，符合文档第 10 节。
- **QueryEngine**（`QueryEngine.java:1810-1817`）：`handoffProjection` 在无标记时直接返回空投影，**不调用交接服务、不查合并表**；有标记时 phase1 用 `inputBudget - reserved` 装填，phase2 用**原总预算**校验含投影的真实 payload（`QueryEngine.java:950-952`），无双扣、无漏扣，符合 7.1 节第 3 条。`prepareCompactionContext` 同样扣预留，压缩/413 恢复路径规则一致。
- **ImageRefInjector**：旧签名委托给默认 `boundHandoffAsset=false` 谓词，普通路径权限判定逐字节不变（`ImageRefInjector.java:96-100, 256-269`）。仅当前可信根会话绑定包中已登记且哈希匹配的图片才放行。
- **QueryController 三个 REST 入口 / WebSocketController**：均以 `isMerged(metadata)` 纯 Map 静态判断为前提，普通会话零交接调用、零合并表查询；`toolUseContext` 均在 `configure` 之前构建（`QueryController.java:164-179`、`WebSocketController.java:917-959`），无 NPE 风险。PROMPT 路径传入 `allowedToolNames`，工具限制保留。
- **授权**：`handoff-read-v1` 的 analyzerId 只能由 `instanceof HandoffReadTool` 的真实内置工具获得（`OperationAnalyzerRegistry.java:87`）——同名 MCP 工具先被 `isMcp()` 拦截，同名动态工具落入 generic，不继承只读放行；分析（analyze）与执行前（recheck）双重校验绑定，绑定变化抛 `HANDOFF_BINDING_CHANGED`，符合 7.3 节。`AuthorizationService` 只对 analyzerId 为 `handoff-read-v1` 且 READ/SAFE 的操作自动放行，未简单加入 SAFE_INTERNAL。
- **普通/fork/Swarm 执行与保存机制**：`SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner` 等文件不在改动列表中，与 HEAD 一致，符合 4.1 节"不为合并改变普通执行与失败条件"。

## 三、问题 2：架构符合性 —— 高度符合

对照文档逐项抽查均落实：

- **迁移**：V026 SQL 与附录 A 逐字一致；execute 幂等（`protocol_version` 已存在则只 validate，不重复重建）；validate 覆盖 V024 契约、全部列、CHECK 片段、索引与外键 CASCADE。
- **准入与占用**：唯一 `active_slot` 部分索引原子占位（非内存 semaphore / COUNT 后 INSERT）；同键同参返回原操作、同键异参 409 `MERGE_IDEMPOTENCY_CONFLICT`；来源树按 ID 排序取 token，忙时拒绝 `MERGE_SOURCE_BUSY`。
- **封存即释放**：`sealed()` 落库后立即关闭全部来源 token 并通知锁集合变化，之后才进入整理，符合 4.3 节第 6 步；封存后仅读本包，不回源补材料。
- **恢复与取消**：`run_epoch` fencing（worker 推进与发布均带 `WHERE operation_id=? AND run_epoch=? AND status='preparing'`）；resume 校验 expectedEpoch 且仅 paused，并发重复 resume 只有一个成功；cancel 无版本参数、递增 epoch、清 active_slot，writer 停止后才清本操作目录，期间新合并返回 `MERGE_CANCEL_CLEANUP`。
- **原子发布**：建会话、写 `metadata_json.sessionMergeOperationId`、写入口消息、`complete` 更新四件事同一事务；`complete` 要求 status=preparing、stage=publishing、epoch 相同、snapshot_hash 非空；不确定提交经 DB 重读核对，查不清保留现场不清包；通知 hook 在提交后执行且失败不回滚。
- **预算体系**：单请求资料目标 16,384 估算 token（扣系统/生成/至少 max(1024, window×5%) 安全量）、可见输出及 brief 目标 2,048、请求/响应 bytes 保护 1 MiB/256 KiB、单调用 300 秒超时、同单元同模型最多 3 次尝试（退避 2/5 秒并遵循 Retry-After）、输入缩小下限 256 token；旧的 3,500 正文阈值与 16 次总调用上限已彻底移除；bytes/token/usage 分离计量，usage 缺失不影响成功判定。
- **结构化交接**：六 section 固定、九种 status 限定、模型只填 i1/i2 别名、程序展开为 `ref@start:end` 并生成 itemId、FAIL_ON_TRAILING_TOKENS 严格解析；无合格提取不发布；聚合非收缩时降级为目录兜底而非循环总结。
- **HandoffRead**：15 秒截止 + 中断检查覆盖校验/目录/正文全过程；搜索每页 8 MiB、输出含包装 16 KiB；跨缓冲重叠与相邻 `:pN+1` 分片查找，不漏跨片命中；游标绑定包版本 + action/query/过滤条件 + 偏移，参数变化拒绝旧游标；`safeFile` 逐段防路径穿越与 symlink；每次读取重算哈希，请求内缓存不跨调用。
- **运行时入口**：目标创建事务写 `sessionMergeOperationId`；无标记会话不调用交接服务；投影为瞬时 UserMessage（EPOCH 时间戳），不持久化、不提升 system；入口上限 `min(2048, 总历史预算×10%)`；工具被 allow/deny 限制时降级为"工具不可用"说明而非绕过。
- **旧断言反转**：文档要求改写的旧断言（缺 usage 必须失败、超 16 次必须失败、所有来源锁到摘要结束、摘要失败必须清包、重启全部失败）已全部移除并替换为反向断言，测试无 `@Disabled`、无放宽断言、无真实付费模型调用、全部使用临时库与临时目录。

## 四、风险与改进建议（按严重度）

### 中等问题

1. **死条件引用不存在的元数据键** — `MergePackageService.java:440`：`history_storage_version != 2` 才导出 checkpoint，但全仓库（含前端）无任何写入方，文档 4.1 节还明确禁止新增该写入。条件恒真、checkpoint 永远导出——功能上恰好符合规范，但属于误导性代码。建议删除条件或注释说明意图。
2. **极小模型下 E 会话每轮 500** — `HandoffContextService.java:74,78`：最小入口也放不下时抛 `IllegalStateException(HANDOFF_CONTEXT_BUDGET_TOO_SMALL)`，从引擎循环冒出变成 500，而非文档 7.1 节要求的"正常容量错误"；`prepareCompactionContext` 路径同样会抛。建议映射为现有容量错误类型（如 413 语义的 LlmApiException），且不因此把已完成合并置为失败。
3. **模型失效的错误分类不精确** — `SessionMergeService.execute` 中 `summaries.select()` 抛中文 `IllegalArgumentException`，经 `safeCode` 归入 `MERGE_PREPARATION_FAILED`，恢复指引无法提示"换模型后恢复"。建议 select 失败映射到 `MERGE_MODEL_UNAVAILABLE`。
4. **attempt outcome 语义混淆** — MergeSummaryService 校验失败路径调用 `failAttempt`，会把传输已成功（completed）的 attempt 改为 error（`MergeProgressRepository.java:197-199`）。usage 保留、计费汇总不受影响，但 outcome 含义模糊，建议注释说明或区分 outcome 取值。
5. **packageRoot 回退 user.dir** — `MergePackageService.java:20-27`：未配置 project root 时依赖进程 cwd，与文档"不硬编码 cwd"有出入；resolver 为 null 时用 PRAGMA 真实库文件兜底且 `requireOperationPath` 自洽，实际风险低，建议统一改为以实际打开的数据库文件为准。

### 轻微问题

6. lockSources 树集合只重取一次（文档允许最多两次），属保守拒绝，可接受；`progress.create` 若并发违反唯一索引会 500 而非 409（单进程 synchronized 下概率极低）。
7. v2 任何失败一律 paused、永不 failed——文档允许"确认无法恢复才 failed"，此实现选择保守，符合"不虚构可恢复进度"精神，建议在提交说明中写明这一取舍。
8. 风格不一致：QueryController/WebSocketController 用字段注入 + 行内全限定名，与类内 constructor-final 风格冲突；`SessionMergeService` 构造函数仍接收已弃用的 `PermissionModeManager` 参数；`isExplicitCoreTool("HandoffRead")` 按名称登记会让假想的同名内部 bean 绕过启动 analyzer 检查（当前无此 bean，仅理论风险）。
9. 消息导出每条消息一次 SQL（LIMIT 1 分页）+ 每列 substr 流式读取，长历史下查询次数 ≈ 消息数；正确但可批量优化。

### 测试覆盖缺口（对照第 9 节）

- 审批中 / 后台服务忙 / 第五来源忙的准入拒绝无测试；
- 慢 hook 发布竞态无测试；
- E 发消息 / 压缩 / 改待办不改 A/B 状态无直接测试；
- 小模型 / 多次压缩 / 413 后交接入口重建无测试；
- **真实模型接续开发语义验收未执行**——文档明确 stub 结构测试不能替代语义评测，第 9 节的人工标注样本（含 A 改服务端字段、B 前端未联调的接续开发样本及"封存后共享代码又变化"变体）仍需另行执行。

## 五、发布结论

**已达到提交 GitHub 的标准**：非合并链路隔离干净且有测试证明（普通请求零交接调用、预算等价、原生 tool ID 不串来源）；合并链路严格按 v2 架构落地（封存即释放、持久进度、epoch fencing、原子发布、有界入口、只读工具授权）；定向结构测试与前端构建全绿；无禁用测试或放宽断言。

建议：

1. 提交信息中保留"真实模型语义验收未执行"的声明（文档第 9 节强制要求，不得以 stub 结果冒充语义验收）；
2. 中等问题 1–3 修复成本低，可在本次或紧随的提交中处理，不阻塞本次提交；
3. 测试覆盖缺口可列为后续任务，其中"E 对 A/B 状态隔离"和"准入拒绝"两类建议优先补齐。
