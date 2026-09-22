# 会话合并 v2 本地改动综合审查报告

- **审查日期**：2026-09-22
- **代码基线**：main @ b98e187 + 全部未提交改动
- **审查依据**：`docs/session-merge-architecture-v2.md`（架构规范 v2）
- **审查方式**：4 路并行深度审查（架构符合性 / 非合并链路回归 / 前端质量 / 构建测试实测验证）+ 干净 HEAD 对照实验
- **排除范围**（按要求不审查）：`ImageBlock.tsx/.test.tsx`、`messageContent.ts/.test.ts`、`MessageActions.tsx/.test.tsx`、`e2e/message-copy-all.spec.ts`、`sessionStatusMeta.ts`、`SessionStatusCapsule.tsx`、`Header.tsx`、`PermissionMenu.tsx`、`PromptInput/index.tsx`、`Sidebar.tsx`、`Sidebar.desktop.test.tsx`、`globals.css`、`liquid-glass.css`

**在范围内的改动**：

| 类别 | 文件 |
|---|---|
| 后端生产（改 11 + 新 6） | `SessionMergeService`、`MergePackageService`、`MergeSummaryService`、`MergeTextBudget`、`SessionMergeController`（session/merge 域）；`AuthorizationService`、`OperationAnalyzerRegistry`、`QueryController`、`QueryEngine`、`QueryLoopState`、`ImageRefInjector`、`WebSocketController`（共享链路）；新增：`V026_ExtendSessionMerges`（迁移）、`MergeProgressRepository`、`MergeHandoffData`、`HandoffReadService`、`HandoffReadTool`、`HandoffContextService` |
| 后端测试（改 7 + 新 4） | `MergePackageServiceTest`、`MergeSummaryServiceTest`、`SessionMergeServiceTest`、`MergeFixture`、`QueryEngineUnitTest`、`AuthorizationServiceProjectFileScopeTest`、`OperationAnalyzerRegistryTest`；新增：`V026ExtendSessionMergesTest`、`MergeProgressRepositoryTest`、`HandoffReadServiceTest`、`HandoffContextServiceTest` |
| 前端（改 4） | `sessionMergeStore.ts/.test.ts`、`SessionMergePanel.tsx/.test.tsx` |
| 文档（新 1） | `docs/session-merge-architecture-v2.md` |

---

## 一、总体结论

| 审查维度 | 结论 |
|---|---|
| **① 非合并分支功能链路影响** | ✅ **安全** —— 所有共享链路改动对普通会话均为严格短路 no-op |
| **② 架构文档符合性** | ✅ **高度一致** —— 无阻断 / 无高级偏差，2 项中级偏差，9 项低级形式偏差 |
| **③ 代码质量** | ✅ 良好 —— 事务边界、并发围栏、SQL 安全、资源管理均规范 |
| **④ 测试覆盖** | ✅ 主干充分 —— 229 个相关测试全绿；存在 10 项边界测试缺口 |
| **⑤ 发布标准判定** | ✅ **达到可提交 GitHub 标准**（附 3 项提交前必做事项 + 分级改进建议） |

---

## 二、非合并链路影响评估（审查重点①）：安全 ✅

### 核心隔离机制

合并标记 `sessionMergeOperationId` 只由 `SessionMergeService` 在发布事务内写入目标会话 E 的 `metadata_json`（SessionMergeService.java:210-211）；`HandoffContextService.isMerged()` 只认这个键（HandoffContextService.java:33-35）。**普通会话该键不存在 → 所有合并分支对普通会话不可达。**

### 逐文件核查结果

| 共享文件 | 改动本质 | 对普通（非合并）会话的影响 | 风险 |
|---|---|---|---|
| `QueryEngine.java` | 主循环每轮计算交接投影并扣减历史预算（:841-842）；压缩预算同扣（:1835）；API 载荷前注入（:940） | `handoffProjection` 第一行判空即返回空投影（:1813-1814）；`historyBudget == inputBudget`，Phase1/Phase2 数值与旧代码**逐字节等价**；空投影时 `inject()` 返回原 list 引用，不进持久化历史；图片注入走旧 7 参重载。每轮开销仅一次 getter + 一个小 record 分配 | **低** |
| `AuthorizationService.java` | SAFE 读自动放行白名单从 `file-v1` 扩展为 `file-v1 \|\| handoff-read-v1`（:158-160） | 普通操作 analyzerId 不变，判定式其余子句未动；`handoff-read-v1` 只能由 `HandoffReadTool` **实例**产生（registry 中 `instanceof` 判定），而该类**无 @Component、不进 ToolRegistry**（有测试断言守护）→ 普通会话不可达；默认拒绝语义不变 | **低** |
| `QueryController.java` | 注入 `HandoffContextService`；三个查询端点 execute 前加 `isMerged` 门控（:178/:265/:362） | 普通会话元数据为 `Map.of()` → 门控恒 false，不触达 handoff 服务，无 NPE；请求/响应契约、校验、错误码零改动 | 无 |
| `WebSocketController.java` | 同样加 `isMerged` 门控（:957-960）；`executeQueryInternal` 增参，2 个内部调用点已同步 | **WS 协议零变化**——无新增/修改的出站消息类型；普通会话分支不进入；19 例 STOMP 集成测试全绿 | 无 |
| `QueryLoopState.java` | 新增 `handoffOperationId` 字段，`transient + @JsonIgnore`（:30-35） | 序列化形态不变；持久化的是 `state.getMessages()` 而非 state 本体，恢复时用消息重建——**既有会话持久化数据完全兼容** | 无 |
| `ImageRefInjector.java` | 旧 7 参 `injectForApiCall` 保留为委托重载（:92-97），新增 8 参重载；`validateAndLoad` 同理 | 普通会话走旧签名 → 与旧逻辑完全等价，断言短路求值零额外 IO；源码与二进制兼容 | 无 |
| `OperationAnalyzerRegistry.java` | `instanceof HandoffReadTool` 分支（:87）+ 新匿名分析器 `handoff-read-v1`（:104-118） | instanceof 插在 `isMcp()` 之后、名称匹配之前，既有工具分派结果逐一比对无变化；同名冒名工具只得 `static-or-remote-v1`/`mcp-v1`（有测试）；新异常仅 HandoffReadTool 可达 | 无 |
| `HandoffContextService` + `HandoffReadTool`（新） | `@Service` 内部 `new HandoffReadTool(...)`；仅合并会话当次请求注入工具 | 工具非 Spring Bean → 不进全局工具列表 → 普通会话工具定义、token 预算、排序缓存全部不变；伪造同名 tool_use 在 ToolRegistry 查无此工具、授权层不放行 | **低** |

### 既有测试：增强而非削弱

三个既有测试文件（`QueryEngineUnitTest` +39 行、`AuthorizationServiceProjectFileScopeTest` +11 行、`OperationAnalyzerRegistryTest` +26 行）的 diff **均为纯新增、0 删除行**：无注释掉的用例、无放宽断言、无删除用例。其中 `QueryEngineUnitTest` 新增的参数化用例（`merged=false` 分支）明确断言：载荷不含 HandoffRead、`verifyNoInteractions(handoff)`、Phase2−Phase1 预算差 == 0——是双向锁定。

### 需长期保持的守护点（低风险，不阻塞发布）

1. **QueryEngine 热路径短路契约**：非合并会话 0 预留、不查库，但该契约必须长期保持；`merged=false` 参数化测试**不得删除**。
2. **`HandoffReadTool` 不得全局注册**：若未来被加进 Spring 工具列表，所有会话将免确认可读交接资料。当前由"无 @Component"断言测试 + `instanceof` 检查双重守护。
3. **合并专属新异常**（`HANDOFF_BINDING_MISMATCH`/`HANDOFF_NOT_BOUND` 等）普通会话不可达；唯一触达场景是运维手工向会话元数据写入 `sessionMergeOperationId` 而无绑定——该会话查询会显式失败，清除该键可恢复（属有意设计：显式失败而非静默降级）。

**总体回归风险结论：安全。** 普通会话的鉴权结果、工具列表、token 预算数值、图片注入行为、WS 协议、请求/响应契约与持久化兼容性均与改动前一致，且有新增测试双向锁定。

---

## 三、架构文档符合性（对照 session-merge-architecture-v2.md 逐条核对）

13 个规范组（A~M）逐条核对，绝大多数 ✅。要点与证据：

### 3.1 符合性亮点

- **A. 模块职责与依赖方向**：SessionMergeService（准入/占用/进度/发布）、MergePackageService（快照/原文/资料包）、MergeSummaryService（独立无工具提取聚合，测试断言工具列表为空）职责与文档 §3 一致；未拆分多余子系统；Repository 只依赖 JDBC，无循环依赖；文档 §10"保持不改"清单内的后端文件（SessionManager/SessionMessagePersistence/SubAgentExecutor/ToolRegistry 等）**零改动**；新增落点恰为文档指定的 6 个新文件。
- **B. 数据库迁移**：`V026_ExtendSessionMerges` 与文档附录 A 的 SQL **逐字一致**（扩展列、3 个 CHECK 约束、部分唯一索引 `active_slot IS 1`、units/attempts 表、FK CASCADE）；`@Component @Order(26)` 自动注册进 MigrationRunner，编号无冲突（现存最大 V025），V024 未动、旧 checksum 不变；execute 幂等（已应用仅 validate）、迁移在 Runner 事务中原子执行可整体回滚；旧行 protocol=1、旧 preparing→failed/LEGACY_INTERRUPTED，v2 清理只处理 protocol_version=2。
- **C. 准入/占名额/epoch/恢复**：2~5 个不同普通来源、主来源在集合内、同键同参返回原操作/同键不同参 409；只保留 run_epoch（启动/恢复/取消递增，普通进度不递增）；worker 推进/发布全部带 `WHERE operation_id AND run_epoch AND status='preparing'` 围栏；单元提交严格遵循"短事务(pending→running+唯一 attempt)→无事务(读+调用+校验+原子写)→短事务(写 result+completed)"；已提交 completed 单元校验哈希后复用；意外退出恢复与优雅停机语义完整。
- **D. 快照与封存**：包根=`<project-db-parent>/session-merges/<operationId>/`（不放 scratchpad、不硬编码 cwd）；staging→manifest/seal→同文件系统 ATOMIC_MOVE rename→短事务记 snapshot_hash；**封存后立即释放全部来源 token 再整理**（有用例证明封存后来源可发消息且不进入快照）；分页复制原文+流式哈希（64KiB substr 分页）；未封存崩溃完整重做不拼接、封存好但 DB 未记校验补记、DB 已记快照丢失暂停诊断，三分支齐全。
- **E. 大内容/附件/重复合并**：文本按 32 KiB UTF-8 分片且禁孤立代理对（>16 MiB 用例通过）；移除"16 MiB 即整个合并失败"旧规则；极端单条 JSON→保留 raw+已完成单元→paused，不截尾不循环重试；附件保存原件+解析文本、不经 LLM base64 搬运；重复合并按 origin/version 并集去重、不跨来源按文本去重。
- **F. HTTP API 与 DTO**：`GET /active` 204 语义、resume `{expectedEpoch, model?}`、cancel 无版本参数、Operation DTO 全字段、冲突格式 `{error:{code,message}, operation?}`、错误码集合与文档 §5.3 完全一致。
- **G. 交接 schema 与串行提取**：六 section 固定、九 status、schemaVersion=2；处理覆盖由单元计划判定不依赖模型自报；无合格结构化提取不能发布；确定性 unitId + input_hash 落库；聚合有界打包、串行、层数递减、不收缩则 header-only 兜底不循环总结；聚合证据可逐层回溯原文（有用例）。
- **H. 文本预算与有限重试**：bytes/估算 token/provider usage 分离计量；16,384 入口、2,048 输出及 brief、1 MiB 请求/256 KiB 响应保护、300s 单调用超时（去掉整任务 10 分钟）、同单元同模型最多 3 次+2/5s 退避、256 token 二分下限、1 GiB 磁盘余量+复制配额、错误分类表、去掉固定 16 次总调用上限（有 >16 单元用例）——逐条落地。
- **I. 运行时消费**：目标 E 事务内写标记；无标记不调用交接服务、不查合并表（`verifyNoInteractions` 用例）；入口≤min(2048, 总历史预算×10%)；连最小入口都放不下返回正常容量错误而非把合并改失败；REST 三入口+WS 入口仅 E 启用，普通/fork/Swarm 工具池不变。
- **J. HandoffRead 只读工具**：不注册全局 Bean；固定 4 action、模型不能选择 session/operation；统一 entries/complete/nextCursor/warning；16 KiB 包装上限、8 MiB 搜索页预算、15s 截止+中断检查；游标绑定包版本+action/query/过滤；UTF-8 边界分页、跨缓冲重叠去重不漏长行尾部；search 字面匹配支持中文、不调 shell、不受 .gitignore 影响。
- **K. 授权**：`handoff-read-v1` 真实内置分析器+core tool 登记；分析/执行前均从可信根会话解析绑定并校验 ref/realpath/包哈希/归属；仅该绑定 READ/SAFE 适用只读策略、不入 SAFE_INTERNAL；同名 MCP/动态工具不继承权限；执行时用 rootSessionId 校验绑定。
- **L. 发布/清理/目标隔离**：发布前校验封存+ready+epoch+绑定；单事务建目标+metadata+入口消息+completed+active_slot=NULL；取消与发布互斥；提交不确定保留现场不生成第二个目标；通知 hook 失败不回滚；不复制来源 tool calls、目标仅一条入口消息；E 独立根会话默认权限、来源 ID 仅历史溯源；普通 SessionManager 删除不触发合并查库。
- **M. 前端协议**：store/panel 的 active 恢复、真实锁集合、稳定 epoch、进度展示、暂停原因、恢复/取消均已实现且与后端契约逐字核对一致（详见第五节）。

### 3.2 架构偏差清单（按严重程度）

**阻断：无。高级：无。**

**中级（2 项）：**

- **D-1｜v2 操作不存在 `failed` 终态路径。** 文档 §5.1 定义 failed="已确认不可恢复的损坏；不能 resume，由用户 cancel 结束"，§6.3 要求"哈希不符/关键原件损坏……确认无法恢复才 failed"。实现中 execute 的异常出口一律 `progress.pause`（SessionMergeService.java:218-224），v2 行永远到不了 failed；Operation.canResume/canCancel 中 failed 分支为死代码（failed 仅来自 V026 旧行迁移）。实际后果可控（用户可 cancel），但状态机语义不完整。**建议二选一**：为哈希不符类不可恢复损坏补齐"重试 N 次后置 failed"路径并补测试；或修订文档明确 v2 一律 paused。
- **D-8｜工作区混入不属于 v2 文件范围的前端改动**（即本次审查排除的 16 个文件）。文档 §10 明确前端只改 sessionMergeStore/SessionMergePanel 且"保持不改 Sidebar"等。这不是代码缺陷，而是**变更集卫生问题**：提交时必须拆分为独立 commit/PR，否则 v2 变更集不闭合、无法独立回滚。

**低级（9 项，节选要点）：**

| # | 偏差 | 影响与建议 |
|---|---|---|
| D-2 | 来源集合复核只重新取一次（文档允许最多两次），start 路径直接拒绝而非暂停 | 拒绝同样释放 token 且用户可重试，影响轻微 |
| D-3 | 无"全部来源统一短只读事务"记录截止边界；实际隔离靠 gate 排他 token | 等价安全性成立；仅外部直接篡改库（文档声明不支持）才有窗口 |
| D-4 | 占名额为"synchronized + 唯一索引兜底"两段式 | 单进程安全；多进程撞索引时用户得到未包装 500 而非 `MERGE_ACTIVE_EXISTS`，建议捕获 `DuplicateKeyException` 映射 |
| D-5 | unitId 字面值不含 snapshotHash/处理器版本（为 `e<ordinal>`/`aggregate-L-O`） | 这些要素全部进入 input_hash 列并做冲突校验，语义等价、字面不符 |
| D-6 | 读取预算超时错误码被包装为 `HANDOFF_INVALID_RESOURCE`/`HANDOFF_READ_FAILED` | 原始码 `HANDOFF_READ_BUDGET_EXCEEDED` 保留在 message 中，信息未丢失但顶层 code 不直接 |
| D-7 | 发布前缺"读接口/最小入口自检"的完整实现 | 以 verifyPrepared 全哈希链校验代替，影响低 |
| D-9 | 旧包收录标记名与文档不同（`legacy_package_origin_inferred` vs `legacy_import`） | 语义等价 |
| D-10 | exportMessage 解析中途失败时同内容存在"部分投影+完整 raw"两份 | 恢复后投影可能与残留分片重复；去重按身份，语义无碍 |
| D-11 | cancel 对已无 writer 的操作调用 cleanupFinished() 全量扫描 | 与"由新合并/启动清理"语义一致，无风险 |

---

## 四、代码质量评估

| 维度 | 评价与证据 |
|---|---|
| **事务边界** ✅ | LLM 调用、文件复制、哈希计算均不在 DB 事务内；发布单事务（建目标+metadata+入口消息+completed）边界正确；所有推进 SQL 带 epoch+status 乐观围栏；V026 迁移在 Runner 事务内可回滚 |
| **异常处理** ✅ | 错误分类经 safeCode/explain 收敛为有界诊断码，不泄露 provider 端点细节与凭据；cancel 后不会被迟到响应回写成 paused（有测试验证）；failAttempt 翻转语义正确但较隐晦，建议补注释 |
| **并发安全** ✅ | start/resume/cancel/recover/shutdown 全 synchronized；writers ConcurrentHashMap putIfAbsent 防本机重复 worker；active_slot 唯一部分索引防多写者；epoch 围栏防迟到写入；虚拟线程 worker 无 pin 风险 |
| **SQL 注入** ✅ | 全部参数化；字符串拼接仅接代码内常量表名/列名（有注释明示） |
| **资源泄漏** ✅ | SnapshotWriter/分页 reader/文件流全部 try-with-resources；每次 LLM 调度的 Executor 随用随关；atomicWrite 同目录临时文件+ATOMIC_MOVE 防半写 |
| **前端状态管理** ✅ | 单飞合并（inflight）、key fencing（响应写回前比对 pending key）、终态停轮询、后台发现 5s 节流且错误静默、不可变更新、sameProgress 深比较复用引用跳过渲染，设计严密 |

**设计异味（低，建议后续治理）：**

1. `SessionMergeService` 构造器内 `new MergeProgressRepository(...)`（:71）绕过 Spring 注入产生双实例——无状态故无害，但削弱一致性。
2. `HandoffContextService` 4 参构造器将 tool 置 null（:22-24），误用于 configure 路径会 NPE——目前仅测试使用，建议私有化或加注释。
3. `MergePackageService.databaseResolver` 字段注入可空 + PRAGMA 回退（:84-92），生产/测试双路径隐式切换，建议显式化。
4. **性能注意（非缺陷）**：`HandoffContextService.project` 每轮对话对全部清单文件做全量 SHA-256 校验；QueryEngine 每轮因 prepareCompactionContext 再调一次 project（:1835），即每个用户请求约 2 次全包校验哈希。正确性优先的设计符合文档（"不引入全局缓存""读取前按绑定校验"），但包较大时每轮开销可观，建议后续在请求内复用一次校验结果。

---

## 五、前端在范围改动评估（sessionMergeStore + SessionMergePanel）

### 5.1 与后端契约一致性：全部通过 ✅

REST 5 个端点（POST 合并+Idempotency-Key、GET 进度、GET /active 204 语义、resume 带 expectedEpoch+model、cancel 无 body）、错误体结构 `{error:{code,message}, operation?}`、404+`MERGE_OPERATION_NOT_FOUND` 才解除本地占用、Operation/Progress 字段、status/stage 取值集合——**逐项与后端逐字核对一致**。WS 链路复用 `session_list_updated` 事件（后端未新增 merge 专用事件），前端消费方式与后端推送模型匹配；handoff 能力属服务端执行期注入，前端无需直接消费。

### 5.2 发现的问题（3 中 + 若干低）

**中级：**

1. **`submitting` 被轮询与控制操作共用，造成按钮间歇性不可点**（store L141/L205/L230；panel L222/L225/L229）：活跃合并期间每 2s 轮询把 `submitting` 翻 true，"恢复合并"/"取消合并"/"重试查询"按钮随之出现几十~几百毫秒的不可点窗口，用户点击被静默吞掉。**这是前端最值得修的交互缺陷**，建议拆分 `polling`/`controlling` 两个标志，按钮仅跟随后者。
2. **全 store 订阅无 selector**（panel L17）：轮询驱动的 `submitting` 翻转引发整树重渲染。属既有模式，建议按 selector 分片订阅。
3. **新增 UI 零组件级测试覆盖**：恢复合并区（模型下拉+默认路径）、取消按钮、整理单元行、retryAt 行、`targetAvailable=false` 禁用态、paused/cancelled 状态行均无组件测试。

**低级（节选）**：`retryAt` 原文输出 ISO-8601 未本地化；`validatedTerminal` 模块级 Set 只增不减；409 采纳分支 `body.operation` 无形状校验；`resume` 的 `expectedEpoch` 可选类型掩盖了"v2 服务端恒返回 runEpoch"的契约；串行化测试名与实际断言存在偏差；面板 fixture 仍用 v1 stage 值。

### 5.3 前端测试质量：高 ✅

断言真实行为（状态迁移、localStorage 实际内容、fetch URL/方法/请求体）而非 mock 调用计数；通过 `vi.resetModules()` 真实模拟页面重载与多标签；discovery/恢复不改写/占用释放/resume 带 epoch/cancel 采纳权威终态/网络失败静默退避/控制与轮询串行化等主干行为全覆盖。**无 `it.skip`、无注释断言、无放宽 matcher。**

---

## 六、测试覆盖评估（后端）

### 6.1 逐类对应与边界覆盖

| 类 | 测试 | happy path 之外的边界覆盖 |
|---|---|---|
| V026_ExtendSessionMerges | 3 用例 | ✅ 旧行保留/重复 execute 幂等/重复 target 回滚/唯一 active 含 NULL 语义/CHECK/FK |
| MergeProgressRepository | 3 用例 | ✅ 暂停占名额/epoch 围栏拒迟到结果/取消后迟到 usage 仅记账/发布回滚/绑定随目标删除失效/split 原子恢复 |
| MergePackageService | ~25 用例 | ✅ 损坏 checkpoint 阻断、超大记录分片、阻断 raw 恢复、空恢复拒绝、篡改检测、路径穿越、symlink、磁盘不足/掉盘/配额、复制中变更、Unicode 边界、重复合并去重、legacy 包导入 |
| MergeSummaryService | 6 用例 | ✅ schema/别名/状态非法拒绝、无 usage 接受、>16 单元、413 二分、已提交结果篡改拒复用、推理预算预留 |
| SessionMergeService | 11 用例 | ✅ 2~5 来源参数化、封存即释放、失败保留单元+恢复不重复计费、取消围栅迟到响应+延迟清理、忙来源/忙子任务拒绝、发布失败回滚目标、提交不确定保留、重启恢复、目标删除撤绑定 |
| HandoffReadService | 12 用例 | ✅ 源删后独立读取、游标绑定/跨参数/行中游标拒绝、跨会话/穿越/symlink 拒绝、目录篡改、跨片/跨缓冲/长行尾部搜索、代理对去重、8MiB 扫描预算续页、15s 超时与中断、完整性不跨调用缓存 |
| HandoffContextService | 5 用例 | ✅ 普通配置零交互、仅 E 注入工具、deny 时不注入且投影明示不可用、显式 allow 不含 HandoffRead 不获得、metadata 不能选他人包 |
| HandoffReadTool | 无独立测试 | 🟡 经 HandoffReadServiceTest 与 OperationAnalyzerRegistryTest 间接覆盖 |
| MergeTextBudget（atomicWrite） | 无直接测试 | 🟡 经 prepare/发布链路间接覆盖 |

文档 §9 的 13 个必测用例族基本有自动化对应（含 4,183 bytes + 13,370 output 的本次失败条件合成回归）。文档自承"真实模型语义验收尚未执行"——审查结论同样只覆盖结构/接线/预算/持久化正确性，**不覆盖真实模型的语义质量**。

### 6.2 缺失的关键测试（按价值排序）

1. 聚合多层不收缩的 header-only 兜底分支（G5），"还有 N 项未展开"语义未验证
2. HandoffReadTool asset 失败分支（HANDOFF_ASSET_UNSUPPORTED / >20 MiB）
3. normalize 参数校验 400（1 个/6 个/重复来源、主来源不在集合、标题>200、幂等键>128）
4. 并发 resume 竞态（synchronized 保证但无并发断言用例）
5. `ref=gaps/manifest/occurrences/overview` 特殊 ref 读取分支
6. `plan()` 的 MERGE_UNIT_INPUT_CHANGED 确定性漂移防护负面用例
7. execute 路径"准入后才变忙"→paused/SOURCE_BUSY 直接用例
8. QueryController/WebSocket 入口 isMerged 门控接线测试（引擎层已覆盖核心）
9. E 侧隔离直接断言（E 发消息/压缩/待办不回写 A/B、E 空 FileStateCache，L7）
10. v2 failed 路径（因实现不存在自然无测试；若按 D-1 补齐实现需同步补测试）

---

## 七、构建与测试实测验证（非静态推断，全部实际运行）

| 验证项 | 命令/范围 | 结果 |
|---|---|---|
| 后端编译 | `mvn compile` | ✅ 通过（2.9s） |
| 后端改动相关测试 | merge 包 5 类 + V026 + HandoffContext + QueryEngineUnit + authorization 包 19 类 | ✅ **229/229 通过**，0 失败 0 跳过 |
| 后端全量套件 | `mvn test`（2,975 例） | ⚠️ 11 失败 / 0 错误 / 70 跳过 —— 见下方对照实验 |
| 前端相关测试 | `vitest run`（store + panel 2 个文件） | ✅ 63/63 通过 |
| 前端全量单测 | `vitest run` | ✅ 894 通过 / 0 失败 / 16 跳过（103 个文件） |
| 前端类型检查 | `tsc --noEmit` | ✅ 0 错误 |
| 前端 Lint | `npm run lint` | ✅ 0 错误（8 条预存在 `exhaustive-deps` 警告，对照 diff 确认非本次引入） |

**11 个后端失败的对照实验（关键证据）**：用 `git worktree` 检出干净 HEAD 运行同样 4 个失败测试类，得到**完全一致的 11 个失败**（SecurityFilterIntegrationTest 6 例——401/403 断言返回 200；CoordinatorServiceTest 3 例；OneKeyRegistryIntegrationTest 1 例——读到本机真实 API Key；WorkspaceFileBoundaryTest 1 例）。证实为 **main 预存在的环境依赖型失败，与本次改动无关**；失败测试类均不在改动文件列表内。

---

## 八、发布结论与行动建议

### 结论：达到可提交 GitHub 的标准 ✅

在范围内改动的编译、单测、类型检查、Lint 全部通过；非合并链路回归风险评估为安全；架构符合性高（无阻断/无高级偏差）；测试主干充分且全部真实断言。

### 提交前必做（3 项）

1. **拆分变更集**：本次排除的 16 个前端文件（ImageBlock/MessageActions/Header/Sidebar/样式/状态胶囊等）属另一任务的 UI 变更，**不要混入本次 commit**。v2 提交应恰好包含：docs 1 个 + 后端生产 18 个 + 后端测试 11 个 + 前端 merge 相关 4 个。
2. **务必 `git add` 全部 untracked 新文件**（6 个后端生产类 + 4 个后端测试类 + docs）——已跟踪改动引用了这些新类，漏 add 将导致拉取方/CI 编译失败。
3. **知悉 main 预存在的 11 个测试失败**：若 CI 在干净 main 上同样失败，建议先单独修复 main（尤其 `OneKeyRegistryIntegrationTest` 会读本机真实 API Key，属环境依赖缺陷），避免 v2 PR 被误判为引入失败。

### 建议跟进（按优先级，均不阻塞发布）

| 优先级 | 事项 | 位置 |
|---|---|---|
| 中 | 修复前端 `submitting` 轮询/控制标志耦合，拆分 `polling`/`controlling` | store L141/L205/L230；panel L222/L225/L229 |
| 中 | 决策 D-1：补 failed 终态路径，或修订文档明确 v2 一律 paused | SessionMergeService.java:218-224 |
| 中 | 补前端新增 UI 组件测试 + store control()/dismiss 分支测试 | panel test / store test |
| 低 | `DuplicateKeyException` → `MERGE_ACTIVE_EXISTS` 映射 | MergeProgressRepository create |
| 低 | 补后端测试缺口：聚合兜底、asset 失败分支、参数校验 400、并发 resume、E 侧隔离 | 见 §6.2 |
| 低 | `retryAt` 本地化显示；`整理单元` 行在 knownUnits>0 时展示 | panel L206-207 |
| 低 | 面板按 selector 分片订阅 store | panel L17 |
| 低 | 治理设计异味：双实例 Repository、null tool 构造器、databaseResolver 隐式回退；评估请求内复用交接包校验结果 | 见 §四 |
| 低 | 治理预存在的 8 条 `exhaustive-deps` 警告与测试 `act(...)` 警告 | SessionMergePanel.tsx 等 |

---

## 附录：审查产物索引

本报告的 4 份分报告（含全部 文件:行号 级证据链）存于项目 scratchpad：

- `backend/.zhikun/scratchpad/95a7fc4a-2ab6-44ba-84af-53219267b8ff/arch-review.md` —— 13 组规范逐条核对表（最详尽）
- `backend/.zhikun/scratchpad/95a7fc4a-2ab6-44ba-84af-53219267b8ff/regression-review.md` —— 非合并链路逐文件核查
- `backend/.zhikun/scratchpad/95a7fc4a-2ab6-44ba-84af-53219267b8ff/frontend-review.md` —— 前端维度化审查
- `backend/.zhikun/scratchpad/95a7fc4a-2ab6-44ba-84af-53219267b8ff/verify-results.md` —— 构建/测试实测明细与对照实验
