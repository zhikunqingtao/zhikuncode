# 会话合并 v2：最小必要改造规格

更新日期：2026-09-23。问题核对基线：`b98e187` 与本地失败日志。**v2 已接入实际在线入口；按“正常主链路优先”的最新确认收缩公共路径改动，当前隔离回归已通过。真实模型核心接续开发场景已验证，完整语义验收仍不能判为全部通过。** 本文替代此前更复杂的 v2 草案；下文明确删除的要求不再作为验收条件。

当前实施状态：SessionMergeService 已改为 `seal/snapshotPath → 结构化分片提取/聚合 → 校验 → 原子发布`；持久进度、暂停/恢复/取消、封存即释放、active/resume/cancel HTTP 接口、HandoffRead 及授权、QueryEngine 每轮入口和预算、前端状态均已接通。旧 `build/packagePath` helper 仅为兼容已有夹具保留，在线入口不再调用。中间详情独立保存，不再受旧启动消息 3,500 阈值或总调用 16 次限制。

最新回归结果（2026-09-23）：在隔离副本核验当前全部未提交改动，后端合并、权限、普通查询等定向回归 **225 项通过，0 失败/错误/跳过**，不是本轮后端全量测试；前端全量 **103 文件：902 通过、16 跳过**，包含一并移入的 UI/消息复制变更，TypeScript 检查与生产构建通过。改动文件 ESLint 为 0 错误、4 个与 HEAD 相同的既有警告；`git diff --check` 通过。隔离副本的后端、前端源码与当前工作区逐文件哈希一致；普通 SessionManager、SessionMessagePersistence、SubAgentExecutor、SwarmWorkerRunner 与 HEAD 完全一致。前端使用 2 个 worker，未放宽断言或超时；本轮回归未追加真实模型调用，未操作现有用户会话或重启运行服务，未提交或推送。

真实模型验证：此前在独立副本、合成数据库和样例工程中，使用请求模型 `deepseek-v4.1-flash`（`dashscope-token-plan`）完成一组合成来源的三轮接续；**24 项自动断言与 4 项事后工具顺序核对通过**。已覆盖 A/B 合并后读取历史与当前代码、继续联调、共享代码变化后重新验证，以及来源历史/设置/待办独立、来源新增消息不进入快照、删除来源后仍能读取封存原文。模型对未读完日志、checkpoint 状态的表述及部分引用、归因仍有瑕疵；未完整运行浏览器端到端、线上授权框架、真实自动压缩全过程或多模型语义验收。测试装置导致的无效接续调用已排除，不能把断言通过等同于所有回答正确，也不能替代第 9 节全部验收。

已确认附件边界：GBK 文本暂不新增解码或自动跳过能力，遇到无法解析时保留原件与已完成进度并暂停；无扩展名 WebP 的识别限制暂缓处理。大 PNG 已修正反馈：原文件超过 **1,125,000 字节**时，HandoffRead 明确返回 `HANDOFF_ASSET_TOO_LARGE`，说明本次未读取图片内容、原件已保留，不提高普通图片链路的容量上限；边界及超限回归已通过。

本轮复审修正：撤回普通子任务全量落库和全局工具注入；普通请求不调用交接服务；损坏 checkpoint 不得经空恢复结果发布；搜索不得漏掉块内后续命中；读取全过程受截止/中断检查；没有合并任务时后台发现错误不弹“合并失败”；恢复/取消先中止在途轮询并隔离过期响应，不等待慢轮询结束。上述结果未发现普通执行、保存主链路回归，不承诺软件在任何环境绝对零风险。

## 1. 已确认范围与本轮简化

必须保留：

1. 完整保存当时可读取的持久化历史；新会话先读交接摘要，按需读取原文。
2. 结构化整理完成、校验通过后才能创建可用目标，不先创建半成品。
3. 只接收空闲来源及子任务；快照封存后立即释放来源，后续允许来源继续使用或删除。
4. 本地串行，只允许一个未结束操作，不排队；暂停/失败待处理仍占名额，普通聊天可继续。
5. 每次合并自持资料副本；重复合并按原始记录身份和版本去重，不依赖旧包存活。
6. 固定栏目、内容、状态和证据引用；一个受范围限制的只读工具提供目录、字面搜索和分页读取。
7. 自适应分块、持久进度、取消、恢复、原子发布，以及压缩/切换模型后的资料入口。

**已确认的接续开发场景**：A 修改服务端、B 修改前端，合并为独立的新会话 E 后继续开发。A/B/E 共享工程代码目录；隔离的是会话历史和会话自身的状态。E 保存 A/B 截止快照时的资料副本，以交接摘要启动，按需读取详情和原文；“全部上下文”不要求每次请求装入全部历史。E 修改共享代码后，A/B 再读文件会看到这些修改；A/B 后续新增消息不进入 E 的已封存资料。合并不复制工程、不合并 Git 分支、不重放历史编辑，也不建立 E 向来源回写状态的关系。

**最新优先级与用户确认（覆盖此前对子任务全过程保存的要求）**：正常开发主链路优先，保留 A/B 合并为 E 继续开发的基本能力。A/B 的已保存主消息、工具输出、子任务 checkpoint 和附件正常合并；旧子任务未保存的中间过程标为历史缺口，不因此拒绝整个合并。已经存在但损坏、复制失败或不能可靠处理的必要资料才暂停。普通/fork/Swarm 的执行和保存机制保持原样，不为合并追加同步落库、归档 session 或新的失败条件。普通请求不查询合并表、不读取包、不携带 HandoffRead；仅 E 的入口按已有会话元数据启用交接能力。不得把“降低风险”表述为已经证明任何场景绝对零风险。

**本轮已向用户确认的能力边界**：普通长文本和工具输出必须支持分片；极端大的单条 JSON 或现有能力无法解析的附件，完整保留可读取的原件与已完成进度，明确暂停及原因。本次不自研通用 JSON 解析器、不新增 OCR/音视频/文档解析平台，也不承诺所有极端格式都自动完成。资源不足以保存全部原件时明确报暂停与未复制范围，不能宣称已完整保存。

| 本轮删减 | 最小替代 |
| --- | --- |
| 模型生成 UTF-8 字节偏移 | 模型仅返回分片别名，程序关联真实来源与位置 |
| 模型逐输入 inputCoverage、跨层语义状态校验 | 程序检查所有分片已处理、引用合法；详细条目保留，语义用样本评测 |
| 递归目录树、每个数组分页、条目 continuation 协议 | 平铺 JSONL 清单、内容分片；工具统一用游标控制返回大小 |
| 二进制按 8 KiB base64 经模型搬运 | 持久原件引用，复用现有图片/附件读取能力，不支持的类型明示 |
| 进度 revision 与运行 epoch 两套版本 | 只保留运行 epoch 隔离旧执行；取消不受进度更新影响 |
| 独立 session_handoffs 表 | 从已完成合并记录按唯一 target_session_id 解析绑定 |
| retry_wait/needs_attention 独立状态 | preparing 的 retryAt 表示退避；paused + errorCode 表示等待处理 |
| 收养每个未提交候选结果的恢复协议 | 只复用已提交单元；最后未提交调用允许重做，明确可能重复计费 |

保留当前 2～5 个来源、主会话、标题/模型选择、模型别名、无工具摘要调用、执行 gate、权限默认策略和最终请求预算校验。主会话决定默认目录/标题/模型，不代表其结论优先，不继承历史授权。

不做 Git/代码合并、自动停止开发服务、多任务队列、并行整理、全局消息重构、事实关系图、自动冲突裁决、检索索引、向量库、自动语义召回、共享对象存储或引用计数。历史内容不回填；数据库结构仍须安全升级。保持已存在的来源缺口可见，不能保证恢复过去未保存的数据。

## 2. 失败证据及必须修复的代码缺口

### 2.1 本次失败，不是“几十轮也撑爆了模型窗口”

主要操作：`caf441d4-5ef8-44ea-b2ff-2b5425f749f9`。

- 来源：`82f4e5e2-e8fe-40d3-9d4f-bf80502b229d`、`f7c8e232-8979-41bb-8b3a-db4ab909f855`；主来源为后者，模型 `deepseek-flash`。
- 本地时间 21:14:58 开始、21:20:05 失败。`log/app.log:19692` 起的三次请求都收到 HTTP 200；HTTP 200 仅说明请求被接收，不能代替流完成判断。
- `log/app.log:19788` 的失败堆栈：`MergeSummaryService.call → summarize → SessionMergeService.execute`，下一行为 `SUMMARY_EXCEEDS_BUDGET: visibleUpperBound=4183, limit=3500, totalOutput=13370`。
- 数据库 `session_merges.usage_json` 记录三次 input/output usage 分别为 `269464/31427`、`318851/22699`、`332920/13370`。总 output 可能包含推理，不能直接当可见正文 token。
- 主消息为 52、68 条，包含工具结果消息，并非 52、68 个人类问答轮次；主消息 JSON 约 381,020、310,700 字节。
- 11 个子会话的 58 个 checkpoint 中，按完整消息 JSON 去重有 840 条、约 4,826,471 字节；另有 1,866 个重复出现位置。这个统计口径不是最终模型输入大小，也不是 token 数。
- 失败后没有目标 session；失败包被旧逻辑清理。因此**没有完整失败响应正文可重放**。测试只能重建相同长度/usage 条件，不能把合成文本称为当次真实输出。

另一个操作 `cb11ed96-6593-4d4b-a882-9464e0c0af18` 在 21:12:23 以 `4297/3500` 失败，来源组合不同，不能与上面一次混为同一任务。

本次实际数据库为 `backend/.ai-code-assistant/data.db`，仓库根目录同名数据库不是本次运行库。复核只读查询：

```sh
sqlite3 -readonly backend/.ai-code-assistant/data.db \
  "SELECT operation_id,params_json,status,stage,created_at,updated_at,usage_json,EXISTS(SELECT 1 FROM sessions WHERE id=target_session_id) AS target_exists FROM session_merges WHERE operation_id='caf441d4-5ef8-44ea-b2ff-2b5425f749f9';"
```

结论：第三个中间摘要触发本地 3,500 的准入阈值，整次成果被丢弃；日志不能证明模型上下文不足。真正的问题是把完整历史、分片摘要、最终启动消息共用一套容量限制，再叠加固定调用次数和缺少恢复能力。

### 2.2 现有落点与改造要求

以下 Java 路径均相对 `backend/src/main/java/com/aicodeassistant/`。

| 现有位置 | 已核实行为 | 本次改法 |
| --- | --- | --- |
| `session/merge/MergeSummaryService.java`：`capacity/call/summarize`，约 39/51/235 行 | `max(估算 token, UTF-8 字节)` 与总 output usage 参与正文校验；中间/最终同预算 | 分离计量单位；详细条目独立保存，只有请求上下文需要有界 |
| 同文件：`InputPacker.flush/summarize` | 15 个输入文件、总 16 次调用 | 单元数量随材料增长；限制单次请求和有限重试，不限制整个任务为 16 次 |
| `session/merge/SessionMergeService.java`：`execute` | 整任务 10 分钟；摘要失败立即删包；最后才释放来源 | 单调用超时；持久进度；封存即释放；可恢复错误保留包 |
| 同文件：`start/recover` | 内存 semaphore 控制并发；重启将 preparing 全标失败 | 数据库唯一未完成名额；恢复已完成单元；semaphore 只防本机重复 worker |
| `session/merge/MergePackageService.java`：`build` | 主消息加所有 checkpoint 文本；重复引用也进入摘要材料 | 只读已有消息/checkpoint，重复位置保存在清单；未保存的子任务过程明确缺口 |
| 同文件：`MAX_RECORD_BYTES/packagePath/deleteUnreferenced` | 超 16 MiB 记录终止；包在 scratchpad | 原文分页保存，普通长文本分片，极端解析失败可暂停；独立持久目录 |
| `tool/agent/CheckpointService.java` | checkpoint 数量/历史裁剪 | 继续负责恢复，不能保证完整历史 |
| `tool/agent/SubAgentExecutor.java`：普通/fork 初始化 | 部分过程只存在内存/checkpoint | 保持原样；仅合并侧读取已有资料，不能为补历史改变普通执行与失败条件 |
| `coordinator/SwarmWorkerRunner.java`：worker 初始化 | 独立内存消息，部分记录未保存 | 保持原样；未保存部分明确缺口，不新建归档 child |
| `config/database/V024_CreateSessionMerges.java` | CHECK 只允许 preparing/completed/failed | 新增 V026 结构升级，不能修改已执行迁移的 checksum |
| `frontend/src/store/sessionMergeStore.ts`：`selectMergeSourceIds/restore` | preparing 全程锁来源；恢复时把终态伪装成 preparing | 分离操作状态、网络恢复状态与真实来源锁 |
| `engine/QueryEngine.java`：预算/请求装配 | 没有持久 handoff 上下文入口 | 每次请求注入有界参考入口，参与两阶段预算及重试 |

## 3. 保留现有服务，限制新增职责

`SessionMergeService` 负责准入、占用、进度推进和发布；`MergePackageService` 负责快照、原文和资料包；`MergeSummaryService` 负责独立无工具的分块提取和聚合。不要再拆 Snapshot/Normalizer/Validator/Workflow 等子系统。

流程固定为：

```text
空闲检查 → 独立快照封存 → 立即释放来源
        → 串行分块提取 → 有界聚合/启动摘要 → 校验 → 事务创建目标
        → 每轮恢复资料入口 → 按需读取原文
```

原文、详细结构化交接、启动摘要分开：前两者可以分文件增长；只有一次请求的输入/输出必须受模型预算约束。新增组件及现有文件落点见第 10 节，不将文件数量当作功能完成标准。

## 4. 历史与快照

### 4.1 只读取已有持久化历史，不修改普通执行与保存机制

主消息和工具结果沿用现有 messages 存储；关联子任务读取现存 messages 与 checkpoint。未保存、已裁剪、缺少 checkpoint 的子任务过程明确记入 gaps，不冒充完整记录，也不因这种既存缺口阻止基本合并。已经保存但无法解析的 checkpoint 属于阻断错误，不能被空恢复结果放行。

保持 `SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner` 的普通路径原样；不增加 sink、fork 副本落库、Swarm 归档 session 或全局 history_storage_version 写入。此前实现的 SubAgentHistoryPersistence 已撤回，不再作为本次文件/验收要求。

有消息 UUID 时按来源/UUID/内容版本去重；没有 UUID 时只在同一来源内识别相同 checkpoint 内容，标记身份推定。不跨来源按相同文本去重。重复位置留在清单，不作为上千条自然语言引用送给模型。原件复制和哈希计算仅在合并侧执行。

合并准入复用现有执行 gate，并检查来源根/子任务及后台运行状态，只允许空闲来源。不能为了合并完善普通执行协议；发现现有机制不能提供安全截止边界时拒绝该次合并。SessionManager 完全保持基线，包括普通删除。删除 E 后 JOIN sessions 的绑定查询立即失效；自有包由下次新合并或合并服务启动清理，普通删除不等待合并目录扫描/磁盘 I/O。

### 4.2 独立资料目录与简单清单

资料范围为来源及关联子任务的持久化消息/工具调用与结果、第 4.1 节兼容 checkpoint、run 状态，以及现有复制范围内的托管计划、附件和产物。待办/计划事实从已保存消息、工具结果和托管文件整理；`TodoWriteTool` 的 `oldTodos/newTodos` 属于这些证据，不复制其内存 map，也不新增待办/计划迁移接口。可读计划正文及现有附件解析文本同样进入提取，不能只复制文件而遗漏其交接内容；缺失或不可解析按第 4.4 节处理。

`MergePackageService` 同时按已捕获来源及其 run 归属分页复制现有 `artifact_manifests`、`artifact_entries`，保留路径、操作、状态、哈希和验证结果等已有字段，不重新调用验证或修改来源清单。两类记录分别使用 kind、原主键及原行内容版本登记到同一 records/raw/text 流程，role 为 `reference`，processingPolicy 为提取；不把整份 entries 数组拼进一条记录。原行版本由固定字段顺序的完整行序列化计算，引用/去重沿用下文规则。没有记录即标明未记录，不反推缺失 diff；哈希一致仅代表对应完整性验证，不代表功能测试通过。不为合并新增源码快照、迁移撤销/回滚栈或克隆进程状态。

从 `DatabaseResolver` 解析的实际项目数据库父目录派生根路径，使用 `<project-db-parent>/session-merges/<operationId>/`，不放 scratchpad，不硬编码 cwd。测试使用临时库和临时目录。

```text
<operationId>/
  staging/                       # 快照尚未封存，不能开始整理
  snapshot/
    manifest.json                # 版本、操作/来源 ID、截止边界、清单哈希/计数
    records.jsonl                # 每个原始记录一条定位，不存全文
    files.jsonl                  # 每个正文分片/附件一条路径、大小和哈希
    occurrences.jsonl            # 重复出现位置
    gaps.jsonl                   # 历史缺口、外部引用、不可解析说明
    raw/<recordRef>.json         # 完整原始记录
    text/<recordRef>-<part>.txt   # 可提取/搜索的文字分片
    assets/<assetRef>            # 独立原件副本
    seal.json                    # 封存版本与 manifestHash
  work/<unitId>/<attemptId>/     # 有界输入、单次响应和诊断
  handoff/
    details/<unitId>-<attemptId>.json # 已校验详情；不被上层摘要替换
    details.jsonl               # 详情及可读投影的引用/哈希清单
    text/<unitId>.txt            # 同一条目的可读投影
    overview.json               # 来源/栏目/状态计数，详情引用
    brief.json                  # 有界启动摘要
    handoff.md                  # 人可读入口
    ready.json                  # 快照/详情/摘要/总览/派生清单哈希及处理器版本
```

`ready.json` 自身哈希写入数据库 `handoff_hash`，读取前按绑定校验。解析阻断重试只从封存原文生成 `work/recovered` 投影及清单，不重读来源；该派生清单的封存哈希也纳入 ready 校验。详情证据由程序追加 UTF-16 范围，工具可按该引用读取，模型无需生成偏移。

JSONL 顺序读写；list/search 的游标记录清单偏移和文件偏移。不建递归目录树、不把大数组塞进 manifest、不为每个字段制定分页协议。目录展示由清单生成，不维护另一套目录节点状态。manifest 只保留有限的根来源（2～5）及清单描述；子会话和大量记录逐条存在清单。

records 行包含：recordRef、origin（sessionId/recordId/version/kind）、role、createdAt、rawRef、processingPolicy。files 行包含：ref、recordRef（附件也关联所属记录）、part、相对路径、bytes、sha256、kind。字段里不嵌套全部分片/附件列表，按 recordRef 关联扫描即可。工具关联 ID、完整 metadata 保留在原文/文字投影中。

recordRef 使用来源身份及内容版本的完整 SHA-256；正常记录的版本用现有一致序列化的 role/content/stopReason/语义 metadata 计算，排除 handoffOrigin 包装、复制时间和 usage；无法解析的原始记录先用原始字节哈希标记 raw 版本，不能假装已经完成消息级去重。不建设跨 JSON 序列化形式的语义等价判断。新包导入旧包时保留原身份/版本。同一身份同一版本去重，不同版本和不同事件保留。

证据引用为服务端生成的分片 ref（例如 `r_<hash>:p0`）或附件 ref。提取请求使用 i1/i2 等短别名映射到这些引用，模型不填写字节偏移、文件路径或哈希。读取/搜索的实际位置与下一页游标由程序计算。

文件名由程序生成，只允许清单中的包内路径；不能用来源标题/模型输出组路径，禁止路径穿越、symlink 或指向源包的链接。原件复制归本包所有；工程目录只记录 external_reference，不宣称是源码快照。

### 4.3 快照提交点与来源释放

按以下顺序修改现有 build/execute，不跨文件复制或 LLM 调用持有数据库写事务：

1. 枚举来源树、按 ID 排序取得 merge token，再核对树集合及 active run/审批/agent/swarm/background writer。集合变动最多重新取得两次，仍不稳定则暂停并释放全部 token。
2. 短只读事务记录来源元数据、消息截止 seq/数量、checkpoint/run 及第 4.2 节产物记录的复制边界、已有合并绑定。写隔离依赖所有应用写路径遵守 gate，不假设 messages 已有版本列。
3. 按截止分页复制原文和托管产物到 staging，流式计算哈希；复用现有代码生成正常记录的文字投影。不能安全解析的记录先保存 raw 并记阻断原因，不为解决解析问题继续占用来源。外部直接篡改数据库不属于支持的并发写协议；观测到变更必须暂停，不能拼接前后版本。
4. 托管文件复制前后校验，变化最多重读一次；仍变化或复制不完整则暂停。既有缺失附件/外部链接记录缺口；磁盘不足不能把应复制原件降级为外部引用后宣布成功。
5. 写并校验 manifest/seal，关闭/刷新文件、在同一文件系统原子 rename 为 snapshot；短事务记录 snapshot_hash/version、stage=extracting。
6. **立即释放全部来源 token，通知锁集合变化，然后开始整理；存在阻断解析项则转暂停。**封存后仅读本包；不再回源补材料。捕获的目录/标题/模型保存在操作记录，发布不要求源 session 仍存在。

该截止边界只冻结归档资料，不冻结共享工程。封存后 A/B 或其他工具可以继续修改代码；整理器仍只读包内历史，E 继续开发时再通过现有文件工具读取当前工程。历史中的代码片段、产物哈希和测试通过均保留其来源/时点，不能当作 E 当前工作区仍满足的结论，也不因为工程发生正常变化而使合并失败。

封存后修正了解析配置、能够恢复此前阻断记录时，从本包 raw 生成投影到 work，不改 sealed 原文。该投影以单元输入文件登记，ready 收录其路径/哈希；读取服务从封存清单和已提交单元输入读取这些文件。这个例外仅处理恢复，不建立独立的派生文件版本平台。

未封存就暂停/崩溃：停止 writer、释放来源；恢复重新取得来源，使用新 snapshotVersion 完整重做，不拼接旧 staging。封存文件已写好但 DB 未记录：校验 operation/version/来源集合/哈希后补记。DB 已记录却丢失/损坏快照：暂停诊断，不自动用已变化来源替换。

### 4.4 大内容、附件与重复合并

普通长文本、工具输出按记录/段落分片，文本文件默认每片不超过 32 KiB UTF-8，保留完整原文和关联引用。移除“16 MiB 即整个合并失败”的业务规则；先用 SQLite 分页读取原始列并完整保存，解析使用现有成熟能力和显式资源上限。不要为本功能实现 JSON 转义、代理对或通用流解析器。

极端单条 JSON 在现有解析能力/内存上限内无法安全处理时，保留其 raw 文件、可读记录头及已完成单元，转 paused/RECORD_REQUIRES_HANDLING；不能截掉尾部或循环重试。已有可直接读取的外置长文本仍须分片处理，不借此把普通长对话也暂停。资源上限针对单次物化/请求，不能重新变成总历史或总摘要长度限制。

材料分三类即可：可见对话/工具证据默认提取；thinking、私有续传字段、重复位置只归档；缺失/未知格式明确记录。只归档内容不作为“已经执行”的事实，也不导入新模型的供应商续传状态。不要仅因是工具输出就跳过；无需开发通用工具输出语义解析器。

附件保存原件及现有解析文本；复用图片/附件读取能力，不新增解析平台，也不经 LLM 用 base64 分页搬运。关键需求只存在于无法理解的附件时暂停，说明可采取的操作；首版不修改已封存内容，用户可在来源补充可读说明后取消重建。非关键附件未解析可明确标记并保留原件，不能编造其内容。

重复合并时，从已完成来源绑定的独立包中取原始记录，加上该来源合并后的新消息，按 origin/version 取并集后复制。此前摘要标为派生参考，不当作原始用户指令。不要复制旧 work/整层 handoff，不依赖祖先会话存在，不把共同祖先当作循环。

paused/failed 保留资料；cancelled 等旧 writer 停止后只删本操作目录。completed 包随目标保留；目标删除完成后通过现有事件/小回调清理，启动时补做未完成清理。清理前确认目标确已不存在；数据库不可读则保留。其他包已持有独立副本，不需要引用计数。

## 5. 最小持久任务与接口

### 5.1 数据结构与状态

新增 `V026_ExtendSessionMerges`（若已被占用则取下一编号），保留旧迁移 checksum。旧 CHECK 不能直接扩展，按附录 A 在事务中重建表、逐列复制旧行、重建索引；不删除用户库或回填历史内容。

只新增两张表：

| 存储 | 最小职责 |
| --- | --- |
| 扩展 `session_merges` | 唯一未结束名额、阶段、epoch、快照/交接哈希、捕获的目录/标题/模型、错误及进度 |
| `session_merge_units` | 固定阶段的处理单元、输入引用/哈希、状态、次数、合格结果路径/哈希 |
| `session_merge_attempts` | 每次模型请求的唯一 requestId、终态和 usage；保持幂等计费汇总，无高级审计功能 |

保留 attempts 表是为了让失败/取消的调用仍可记账，而不让迟到 usage 改动处理单元状态。它只是一张调用明细表；不增加调度、查询页面或费用平台。usage 缺失记未知，不能写成零费用。

不建 session_handoffs：`target_session_id` 唯一，运行时查询 protocol=2、status=completed 且目标 session 存在的合并记录，即为权威绑定。该行保存 snapshot_hash/handoff_hash；不再向 session metadata 复制一份绑定。目标创建和 completed 更新仍在同一事务中。

操作 status 只保留 `preparing/paused/failed/completed/cancelled`：

- preparing：执行或自动退避；退避用 retry_at 表示，不另建状态。
- paused：磁盘、服务、模型配置、内容解析等可处理问题；error_code/error 说明恢复条件。
- failed：已确认不可恢复的损坏；不能 resume，由用户 cancel 结束操作。
- completed/cancelled：释放名额；前两类和 failed 均占 active_slot=1。

stage：`snapshotting/extracting/aggregating/validating/publishing/completed`。stage 描述当前工作，不承担错误分类。unit state：`pending/running/completed/split/failed`；父单元拆分时在 input_json 保存两个 childUnitIds，并与子项插入同事务提交，不能父子重复执行；恢复/发布验证 split 的子项确实存在且最终已完成。

旧行 protocol=1、active_slot=NULL；旧 preparing 改为 failed/LEGACY_INTERRUPTED，保留旧包，不虚构可恢复进度。旧 completed 仍可打开。旧合并会话作为来源时，现存旧包可标 legacy_import 收录；身份不完整就说明不能精确跨包去重，不回填不存在的原文。

### 5.2 单一执行代次、准入与恢复

保留 start 的请求标准化：2～5 个不同普通来源、主来源在集合内、标题和幂等键长度限制。同键同参数返回原操作，同键不同参数 409。新操作以唯一 active_slot 索引原子占名额，不能只用内存 semaphore 或 COUNT 后 INSERT。来源忙时拒绝；准入后才变忙则 paused/SOURCE_BUSY 并释放取得的 token。

只保留 `run_epoch`：启动/恢复/取消使旧执行失效时递增，普通进度不递增。worker 推进和发布使用 `WHERE operation_id=? AND run_epoch=? AND status='preparing'`。本地互斥防重复 worker；唯一数据库名额覆盖暂停、浏览器关闭和进程重启。

一个处理单元按以下顺序提交：

```text
短事务：pending → running，创建唯一 attempt
无事务：读取封存资料 → 有界模型调用 → 校验 → 写结果临时文件并原子 rename
短事务：检查 epoch/状态 → 写 result_path/hash → completed
```

已提交 completed 单元校验哈希后复用；未提交候选清理后只重做该单元，不设计自动收养所有孤立结果的协议。最后一次请求可能重复计费，不承诺外部调用 exactly-once。迟到回调仅能幂等记录自己的 attempt usage，不能恢复任务或发布目标。

意外进程退出后：核对 publishing 是否已提交；有可信快照就不再占用来源；递增 epoch，将旧 running 重置 pending，从第一个未完成单元继续。已暂停/失败操作保持原状态。优雅停机取消当前调用并记录 paused/SERVICE_SHUTDOWN，下次由用户恢复。

### 5.3 HTTP 协议与取消

保留现有 POST 创建和 GET 操作；新增如下三个接口，不增加任务列表平台：

| 接口 | 输入及行为 |
| --- | --- |
| `GET /api/session-merges/active` | 当前未结束操作；没有则 204，解决浏览器恢复信息丢失 |
| `POST /api/session-merges/{id}/resume` | `{expectedEpoch, model?: string}`；仅 paused；状态和 epoch 条件更新成功才启动一个 worker |
| `POST /api/session-merges/{id}/cancel` | 无版本参数；按操作 ID 取消任何未结束状态，已 cancelled 幂等返回，已 completed 返回 409 |

resume 用 expectedEpoch 防止旧的恢复请求在“再次暂停”后重放；epoch 不随普通进度变化。并发重复 resume 只有一个成功，其余返回当前状态，不再启动 worker。换模型只重新规划未完成单元，保留已合格结果及实际模型记录；不自动跨供应商切换。

cancel 在短事务中递增 epoch、置 cancelled、清 active_slot，然后 abort 请求/复制，等旧 writer 停止再清本操作目录。旧 worker 收尾时，新 POST 返回 MERGE_CANCEL_CLEANUP，不排队、不并发启动另一个整理 worker。cancel 与发布只能有一方事务成功，不能删除已发布目标。

Operation DTO 保留现有 request/result/error，新增必要字段：

```ts
type MergeProgress = {
  protocolVersion: 1 | 2;
  operationId: string;
  targetSessionId: string; // 预留 ID，不能据此执行
  status: 'preparing' | 'paused' | 'failed' | 'completed' | 'cancelled';
  stage: string;
  runEpoch: number;
  snapshotSealed: boolean;
  lockedSourceSessionIds: string[];
  progress: { completedUnits: number; knownUnits: number; totalFinal: boolean };
  retryAt?: string;
  errorCode?: string;
  error?: string;
  canResume: boolean;
  canCancel: boolean;
  targetAvailable: boolean;
};
```

锁集合来自真实 gate；只有封存并释放后才能返回空集合。前端不能从 preparing 推断来源锁定。冲突沿用 `{error:{code,message}, operation?: 最新操作}`，区分 `MERGE_ACTIVE_EXISTS`、`MERGE_SOURCE_BUSY`、`MERGE_STALE_OPERATION`、`MERGE_ALREADY_COMPLETED`、`MERGE_IDEMPOTENCY_CONFLICT`；取消后写入器尚未退出返回 `MERGE_CANCEL_CLEANUP`。GET 404 沿用 MERGE_OPERATION_NOT_FOUND。

## 6. 结构化交接与模型预算

### 6.1 模型只填写内容、状态和已给定引用

六个 section 固定，提取/聚合提示词按下表保留接续开发需要的已有事实；未记录的信息不补造，不增加 schema 字段或语义校验子系统：

| section | 接续开发内容 |
| --- | --- |
| `goals_constraints` | 各来源目标、用户约束、已明确接受或放弃的方案及已有理由 |
| `state_conclusions` | 各来源工作范围、已完成/未完成的状态和关键决策；结论保留来源依据 |
| `changes` | 已改文件与改动目的，相关接口、请求/响应字段、数据结构及调用方约定 |
| `artifacts` | 可继续使用的计划、设计说明、附件和产物入口；区分已复制资料与共享工程引用 |
| `validation_failures` | 已执行的验证命令、范围、结果及已有时间/版本信息；未验证自述、失败和环境限制 |
| `conflicts_todos` | 各来源尚未完成的工作、已记录或从双方证据发现的不一致、联调与复测事项 |

有明确后续修正或完成证据时说明前后关系并保留引用；不能把历史上出现过的所有待办都视为当前未完成。证据不足时保留未知或双方分歧，不按主会话优先、不仅按消息时间裁决，也不以“未找到失败”推断已经通过。无需新增自动冲突裁决器。

```json
{
  "schemaVersion": 2,
  "items": [
    {
      "section": "validation_failures",
      "content": "来源 A 报告测试失败，尚未见修复后的复测结果。",
      "status": "failed",
      "evidence": ["i1"]
    }
  ]
}
```

状态限定为 `recorded/completed/in_progress/pending/failed/unverified/conflict/inferred/unknown`，不建立跨阶段状态迁移模型。测试是否通过、命令、退出码、版本及“工具结果/助手自述”的区别写在内容中，并保留原始证据；不引入笼统 verified 状态或通用“证明结论正确”的解析器。动作 completed 不表示测试通过。

程序校验 JSON 完整、schema/section/status 合法、content 非空、evidence 非空且全部属于本请求提供的别名；再展开为稳定分片引用并生成 itemId。空栏目由程序展示“未记录”。不要求模型输出 emptySections/inputCoverage、字节区间、哈希或完整来源树。推断也必须有依据，冲突双方保留来源。

处理覆盖由单元计划判定：每个需要提取的分片都有成功单元；不依赖模型自报“我已覆盖全部”。这只能证明资料经过处理和引用有效，不能证明语义零遗漏。

### 6.2 串行提取和有界聚合

按来源、消息、分片顺序生成确定性 unitId，包含 snapshotHash/阶段/输入引用及程序计算的切片边界/处理器版本；保存实际模型和 input_hash。调用与结果尽量同组，超大内容继续分片，保留相邻引用和少量上下文重叠。

每个单元成功后立即持久化，不把所有 transcript/partials 放进内存 String.join。全部叶子条目保留为详细交接，上层摘要只增加概览，不能替换或删除它们。

聚合直接复用已有分组摘要思路：按预算打包若干子概览，串行生成上层概览，直到得到有界 brief。brief 尽量保留每个来源的工作目标/进展、关键约束、接口约定和主要未决项，并给出详情入口，不能只总结主会话。超大详细结果保留原文件，只向上层提供有界摘录、状态计数和详情引用。必要时再压缩一次；仍不收缩，就用程序生成来源/栏目/状态计数和详情入口，明确“详情未展开，继续相关任务前需读取”，不循环总结同一内容。降容不降低第 6.1 节对详细交接的内容要求。

聚合引用可指向本次输入中的已完成子单元，读取该结果即可逐层回溯到原文；不要把全部下层 evidence 数组复制进 brief，也不新建语义关系表。

固定来源/栏目目录和 pending/failed/unverified/conflict 等状态过滤由详细条目生成，不维护 protected 条目关系、语义升级裁决或另一棵导航树。每层子节点数量应减少；所有详细条目始终可从来源/栏目入口发现。brief 放不下的事项明确标注“还有 N 项未展开”，不写成无其他问题。

没有合格结构化提取就不能发布。确定性导航兜底只用于已有详细交接的启动入口降容，不能用原文目录冒充整理完成。

### 6.3 必要预算和有限重试

存储 bytes、请求 bytes、估算输入 token、可见输出 token、包含推理的 provider usage 分开。删除旧 `max(tokenEstimate, UTF8Bytes)`/总 output usage 与 3,500 正文上限混用的拒绝规则；usage 缺失只影响费用可知性。

初始默认值（可通过现有配置注入，不新建预算平台）：

| 参数 | 默认及规则 |
| --- | --- |
| 单请求资料目标 | 16,384 估算 token，同时扣除系统/引用包装/生成预算和模型安全余量 |
| 可见输出及 brief 目标 | 各 2,048 估算 token；合格中间结果超过目标仍可保存，不作为总交接容量上限 |
| 请求/可见响应 bytes 保护 | 1 MiB / 256 KiB；触发仅处理当前单元，不删全部成果 |
| 单调用超时 | 300 秒，沿用 AbortContext/LlmCallContext 取消；去掉整任务 10 分钟截止 |
| 自动尝试 | 同单元/模型最多 3 次，退避 2、5 秒或遵循 Retry-After；修复也算调用 |
| 输入缩小下限 | 256 估算 token；到下限仍失败则暂停，不无限二分 |
| 磁盘 | 保留现有至少 1 GiB 余量及复制配额；超配额暂停可恢复，不静默漏原件 |

使用现有模型能力和 TokenCounter 估算，明确不是精确 token。延续必要的推理生成预留：supportsThinking 时最多预留 min(32768, window/4)，与可见目标合计不超 provider maxOutput；从 window 扣除系统、生成和至少 max(1024, window*5%) 的安全量后才装填资料，估算再留余量。容量不足缩小当前输入，不把所有历史压成同样大小。

| 问题 | 当前单元处理 |
| --- | --- |
| 完整 end_turn、schema 合格，正文比目标稍长 | 保存详细结果；上层装填/读取另行限量 |
| 413/明确上下文拒绝、stop=length、响应 bytes 超限 | 缩小/二分输入；不能把截断 JSON 当成功 |
| JSON 或引用不合法 | 最多一次带错误说明的修复；仍错则缩小重做，到下限暂停 |
| 429/临时 5xx/网络错误 | 有限退避，耗尽转 paused/PROVIDER_UNAVAILABLE |
| 凭据/模型配置错误 | 直接暂停等待修正 |
| 原件解析超出安全能力/关键附件不支持 | 暂停、保留原件及成功单元，不自研解析器兜底 |
| 磁盘或用户已有费用配额不足 | 暂停并说明恢复条件 |
| 哈希不符/关键原件损坏 | 停止使用资料，暂停诊断；确认无法恢复才 failed |

去掉固定 16 次总调用上限。通过有限输入、严格缩小的子单元、有限重试及收缩的聚合层保证终止；不新增隐藏费用上限，也不自动换供应商。记录 operation/unit/requestId、模型、预算单位、stop reason、usage 是否报告和错误分类，不把全文或凭据写日志。

## 7. 运行时消费：一个入口、一个只读工具

### 7.1 每轮有界入口，保持现有请求链路

目标创建事务在 E 的现有 metadata_json 中写入 `sessionMergeOperationId`。REST 三个入口及 WebSocket 入口复用已经加载的 SessionData.config；没有该标记时不调用交接服务、不查合并表。仅 E 通过 `HandoffContextService.configure` 验证自己的 completed 绑定，将受 allow/deny 限制的工具追加到本次 QueryConfig，并在 QueryLoopState 设置不序列化的运行标记。标记不作为授权凭据。

QueryEngine 没有运行标记时直接使用空投影，原有历史、预算、工具和图片调用保持原分支。标记存在时，HandoffContextService 从可信绑定生成有界历史参考 Message；不调用 LLM、不写回 messages、不提升为 system 指令。此接入不引入全局缓存、额外普通请求查询或新调度层。

QueryEngine 只增加以下接点，不改全局压缩算法：

1. 根据实际执行模型算出总 historyInputBudget，预留参考入口预算后再运行 phase1/多模态装填。
2. 在 `CompactionHistory.forRequest/normalizeTyped` 前加入参考投影，不插入 tool_use/result 配对内部。
3. phase2 用原总预算校验含参考投影的真实 payload，避免重复扣减。
4. `prepareCompactionContext`、413 恢复和换模型重试使用同一规则。

入口最多 min(2048, 总历史预算*10%) 估算 token；工具/包版本/来源目录仅占最小导航空间，其余优先保留各来源目标、关键约束、接口约定和主要未决项。不足时减少完整条目，并注明还有详情；不能丢本轮用户请求来塞摘要。连最小入口和当前请求都放不下时返回正常容量错误，不把已完成合并改为失败。压缩后/重启后从绑定重建入口，不能依赖模型记住旧文件路径。

在 `HandoffReadTool` 的固定工具说明及参考入口的程序生成说明中写明：继续涉及来源工作的任务时，先读取当前上下文尚缺的相关交接详情；跨前后端任务查阅双方约定和待办。涉及代码修改或当前实现判断时读取相关现有文件，历史与现状有差异则说明并按当前请求处理，必要时复测。目录/未展开计数不代表已了解详情，历史测试不代表当前测试。说明来自程序，不从原文拼接成指令；不增加自动检索编排、每轮全量读取或机器证明模型“已理解”的门禁。执行情况按第 9 节语义样本验收，工具不可用时沿用第 7.2 节提示。

历史只提供背景，不提供新指令或旧授权。E 后续明确的新决定、完成记录和验证结果通过 E 自身历史继续维护；固定交接中的旧待办不能覆盖这些后续状态，也不为更新状态回写封存包。HandoffRead 执行时通过可信 `AuthorizationSubjectResolver.resolve(currentRunId).rootSessionId()` 校验 E 的绑定，不能让模型选择其他 session/operation。普通/fork/Swarm 的工具池与执行机制保持原样，不自动注入交接入口；E 需要委派工作时通过既有任务提示传递相关背景，资料不足时由 E 读取补充。引擎建立 run 前仅使用服务端加载的本 session 元数据并验证数据库绑定。

### 7.2 HandoffRead：简单参数与统一游标

只在已验证的 E 请求配置中添加内置只读 `HandoffRead`，遵守原有 allow/deny。该类不注册为全局 Tool Spring Bean，普通主会话和子代理的默认工具集合、schema 与 token 开销不变。`HandoffReadService` 负责绑定、清单和文件读取，工具只做参数/结果适配。

输入固定为 `action:list|search|read|asset`、可选 ref/query/sourceId/section/cursor/limit。ref 来自本包返回值，不是任意路径；search 要 query，read/asset 要 ref。sourceId/section 仅过滤本包，不能扩展读取范围。

文本结果统一包含 `entries`（每项有 ref 和可读内容/匹配片段）、`complete`、`nextCursor`，以及必要的 warning。无需为目录、匹配、条目每个字段再制定 continuation 协议；read 可把详细 JSON 的可读投影作为文本分页返回。

- list 顺序扫描平铺清单生成来源/栏目/附件目录；read 根据 ref 定位记录、分片或详细条目。原始 JSON 也须有可读取的 raw ref。
- search 直接扫描包中登记的文本，使用字面匹配，支持中文/路径/代码标识符；不调用 shell 拼命令，不受 .gitignore/隐藏目录默认规则影响，不建索引。
- 文本输出含包装最多 16 KiB，搜索正文每页最多 8 MiB，到界 complete=false 且给游标；每次实际读取的校验/目录/正文共用 15 秒截止和中断检查。校验未完成或整体超时明确报 HANDOFF_READ_BUDGET_EXCEEDED，不跳过哈希；不把 8 MiB 误称为含完整性校验的总 I/O 上限。只有全部候选扫描结束才 complete=true，不能把“本页未找到”当“全包无匹配”。
- 游标绑定包版本、action/query/过滤条件以及清单/文件偏移；参数变化拒绝旧游标。游标不是授权凭据，伪造偏移也不能访问其他包。
- 按 UTF-8 边界分页，搜索保留跨缓冲/相邻分片的 query 长度重叠并去重；不能因超长单行漏掉尾部。
- asset 返回原件信息及受控原件引用；图片复用 ToolResult.image/ImageResultExternalizer 和现有多模态预算。已支持的附件走已有读取能力；不支持的类型返回明确说明，不用 base64 页模拟“模型可理解”。

所有引用映射到本包独立副本；不能只返回源会话或临时文件路径。结果/警告有界，原文不截断丢失。若用户明确禁用工具，尊重限制并提示资料工具不可用，不修改授权绕过。

### 7.3 复用授权框架，补齐范围校验

在 OperationAnalyzerRegistry 增加真实内置工具的 `handoff-read-v1` 分析器及 core tool 登记。分析/执行前均从可信根会话解析已完成操作，检查 ref、realpath、包哈希/文件归属；仅允许包内登记文件，拒绝路径穿越和 symlink。

AuthorizationService 只给这个已验证绑定的 READ/SAFE 操作适用只读策略，包含 PLAN 模式；不能简单加入 SAFE_INTERNAL，也不允许同名 MCP/动态工具继承权限。无绑定、未知 ref、包损坏分别报明确错误。正常文件权限、工具注册和激活规则保持不变。

## 8. 发布、清理与前端

发布前检查：所有必要原文已归档；没有阻断缺口；全部提取单元完成（split 的子项也完成）；schema/引用可解析；详细条目可由清单找到；brief/最小入口/读接口内部自检通过。写 ready 文件和 handoffHash；不做“自动证明所有结论为真”的检查。

```text
事务前：验证封存资料、ready、当前 epoch、捕获目录仍绑定工作区
短事务：
  要求 status=preparing、stage=publishing、epoch 相同
  createSessionRecord(预留 ID、目录/标题/模型、现有权限默认)
  E 的 metadata_json 写 sessionMergeOperationId（普通会话不写此标记）
  写一条简短的交接入口消息，meta 标记 operationId/历史参考
  更新同一操作 completed、handoff_hash、active_slot=NULL
提交后：释放 worker，通知状态，再触发 notifySessionCreated
```

不复制来源原生 tool calls 到目标。事务回滚保留资料和已完成单元，恢复可重试发布。提交不确定时核对 target 和 completed 操作是否一致；查不清就保留现场，不能清包或生成另一个目标。通知 hook 失败不回滚已完成目标。删除目标后运行时查询立即不再返回绑定；由下次新合并或服务启动清理其自有包，保留操作记录供诊断。普通删除不触发合并查库/磁盘清理。

E 是独立根会话，来源 ID 仅作历史溯源，不建立指向 A/B 的运行父链。沿用新 session ID 下的历史、压缩状态、模型/权限设置及待办作用域；权限按现有默认，不能继承旧审批。`SessionManager.getFileStateCache(E)` 从空缓存开始，不 clone/merge A/B 的已读缓存。来源待办事实留在交接中，E 后续可通过现有 TodoWrite 建立自己的待办，不自动搬迁旧待办对象或原 ID；本次不新增待办面板导入功能。E 的发消息、压缩、待办/设置变更与删除不能回写 A/B，A/B 的后续消息也不追加到 E 的封存资料；这些限制不阻止共享工程文件正常变化。

前端主要修改现有 store/panel：

- 操作是否未结束看服务端 active；来源是否锁住只看 lockedSourceSessionIds。封存后即使 preparing 也允许来源使用/删除。
- 页面恢复查询 active，网络状态单独用 syncing/error，不把保存的所有状态强改 preparing。
- 展示阶段、已完成/当前已知单元数、暂停原因、恢复/取消；总量未定不展示虚假精确百分比。
- 关闭面板仅隐藏 UI；取消/结束失败操作调用后端；只有 completed 且 targetAvailable 才能打开目标。
- 取消不带进度版本，不因后台进度更新被拒绝；旧 resume 请求按 epoch 拒绝后刷新状态。
- 保留多 tab、幂等键、localStorage 写失败、404 恢复、通知去重和不抢焦点保护；锁集合改变也触发列表更新。
- protocol=1 只读展示旧结果，不伪造 v2 绑定/恢复能力。继续复用 GET 轮询和现有事件，不新增通知平台。

## 9. 必要测试与验收

默认使用 stub provider、临时 SQLite 和临时资料目录。禁止测试写入运行库，不将真实会话/附件提交到仓库，也不默认调用付费模型。

| 用例 | 必须证明 |
| --- | --- |
| 迁移与单一操作 | 旧三种状态/内容保留，CHECK/唯一 active 生效，再启动校验通过；两浏览器不同键只能一个成功，同键返回同操作 |
| 占用与释放 | 根/第五来源/子任务/审批/后台服务忙时拒绝；快照后首次 LLM 阻塞期间来源可发消息和删除，无关聊天可用 |
| 普通/fork/Swarm 隔离 | 执行和保存代码与基线一致；已有 checkpoint 去重收录，未保存过程给非阻断缺口，已保存但损坏的资料必须暂停 |
| 长历史与重复 | 同事件同版本去重、不同事件/版本保留；checkpoint 重复位置不灌入 LLM；普通长工具输出分片后尾部可检索 |
| 极端记录/附件 | 原件和进度保留后明确暂停；不自研解析器、不静默截断、不把未知附件内容编成事实 |
| 本次失败条件 | 合成合法中文响应 4,183 bytes、provider output=13,370；有/无 usage 都不因 3,500 阈值拒绝；>16 个必要单元可完成 |
| 当前单元恢复 | 413/length/错误 JSON/越界别名有限缩小/修复；第 N 次服务失败保留前 N-1 结果；重启/换模型只重做未完成工作 |
| 发布与取消竞态 | 文件写前后/DB 提交前后故障、提交结果不确定、旧响应晚到、慢 hook；最多一个目标，不误删 completed 包 |
| 绑定与重复合并 | C=merge(A,B) 后 merge(C,A)，保留新增内容并去重；删来源/旧目标后新包可读；删目标只清自己的包 |
| 接续资料与状态隔离 | 已有产物/验证记录及可读托管计划可提取、可追溯；缺失字段不补造；E 使用独立 ID 和空文件缓存，E 发消息/压缩/更新待办或设置/删除不改 A/B 的对应状态，来源后续消息不进入 E 快照 |
| 请求上下文 | 普通无标记不调用交接服务，工具定义/历史/预算保持基线；小模型/多次压缩/413 后入口仍可重建；不重复持久化、预算不双扣、原生 tool IDs 不串来源 |
| 读取与权限 | 中文/路径/跨片/长行尾部可查，扫描未结束给续页；游标/跨包 ref/路径穿越/symlink/同名动态工具拒绝，PLAN 合法包只读可用 |
| UI 与控制 | 页面重载/localStorage 缺失/写失败/多 tab 正确恢复；封存后不误锁；进度更新不阻挡取消；旧 resume 不重放、不抢焦点 |

改写现有测试中“缺 usage 必须失败”“超过 16 次必须失败”“所有来源锁到摘要结束”“摘要失败必须清包”“重启全部失败”的断言。此前文档新增的“任意超大 JSON 都必须自动解析完成”验收要求删除，按本轮确认改为原件/进度保留后暂停；普通长文本仍须成功分片。保留并扩展原有磁盘、Unicode、路径/权限、幂等、事务不确定提交、工具 ID 隔离和前端恢复测试。旧 CopyLimit 测试不能继续要求托管原件复制失败后带警告发布。

实际语义用少量人工标注样本验收：用户约束、子任务尾部失败、冲突双方、未验证自述、不同版本、重复合并及附件未知。要求新会话能回答未完成事项/失败验证，并通过引用查到原文。不要以模型自报覆盖率或代码看到退出码就宣称语义正确。

必须包含一个接续开发样本：A 将服务端响应字段从 `name` 改为 `displayName` 并记录验证结果；B 的前端仍读取 `name`，有未完成联调待办。A/B 使用同一临时工程，合并为 E 后要求其完成联调。验收 E 能查到双方约定/改动/待办的依据，读取当前相关代码、修正调用并验证；后续轮次不因固定交接仍含旧待办而把已完成联调重新视为未完成。再增加一次“封存后共享代码又有变化”的变体，确认 E 不凭历史通过记录宣称当前成功，不重放旧编辑。强制 brief 采用目录兜底时也应先读取相关详情。分别验证 E 更新待办不改变 A/B、A 追加消息不改变 E 资料、删除来源后 E 仍能读取已归档依据。数据复制/隔离用 stub 自动测试，接续决策用上述语义样本评测；这两类结果分别报告，不新增评测平台。

本次真实日志只支持已记录的长度/规模/usage 事实，失败响应全文已经不存在；合成回归必须标为合成。真实模型评测另外说明授权、费用和样本范围，结构测试不替代语义评测。

本轮在 backend 使用 Java 21/test profile/临时数据目录执行以下定向测试。工具与图片测试复用 HandoffReadServiceTest；HandoffContextServiceTest 验证普通配置无服务调用、E 配置及 allow/deny，QueryEngine 测试验证无标记零交接调用和预算等价：

```sh
./mvnw -Dspring.profiles.active=test -Dtest=V026ExtendSessionMergesTest,MergeProgressRepositoryTest,MergePackageServiceTest,MergeSummaryServiceTest,SessionMergeServiceTest,SessionMessagePersistenceTest,HandoffContextServiceTest,HandoffReadServiceTest,MigrationRunnerV2Test test
./mvnw -Dspring.profiles.active=test -Dtest=SubAgentExecutorStatusPropagationTest,SwarmWorkerRunnerModelTest,QueryEngineUnitTest,QueryEngineMediaRecoveryTest,QueryEngineWithholdTest,ImageRefInjectorMetadataTest,OperationAnalyzerRegistryTest,AuthorizationServiceProjectFileScopeTest,SessionManagerMessageIdempotencyTest,SessionManagerSearchTest test
```

在 frontend 执行：

```sh
npm run test:run -- --maxWorkers=2 --minWorkers=1
npm run build
```

以上测试已纳入本轮隔离副本全量验证，另有 63 项合并前端用例通过。后端完整命令为 `./mvnw -Dspring.profiles.active=test test`（Java 21）。最终代码已重新编译并完成全量验证。构建仅有现有大 chunk/Browserslist 提示。真实模型的人工语义样本尚未执行；不得将 stub 的数据、隔离与接线断言描述成真实模型接续开发验收。

## 10. 文件范围与实施顺序

Java 路径前缀：`backend/src/main/java/com/aicodeassistant/`。先复用以下落点，不继续扩展子系统。

| 现有文件 | 必要改动 |
| --- | --- |
| `session/merge/SessionMergeService.java` | 准入/单元推进、封存即释放、恢复取消、原子发布；保留现有事务/hook 防护 |
| `session/merge/MergePackageService.java` | 独立封存、平铺清单/分片、旧历史兼容、原文/已有产物记录保留、重复合并和自有包清理 |
| `session/merge/MergeSummaryService.java` | 简单条目 schema、接续开发提取/聚合提示词、单元预算与有限修复，取消固定总调用/正文上限 |
| `controller/SessionMergeController.java` | active/resume/cancel、DTO、错误分类 |
| `session/merge/MergeTextBudget.java` | 小范围增加原子文件替换，沿用现有磁盘防护 |
| `engine/ImageRefInjector.java` | 仅为当前可信根会话绑定包中已登记且哈希匹配的图片增加读取入口；普通路径权限不变 |
| `engine/QueryEngine.java` | 仅有运行标记的 E 启用投影；普通分支不调用交接服务 |
| `engine/QueryLoopState.java` | 一个不序列化的合并运行标记，无默认执行行为 |
| `controller/QueryController.java` | 复用已加载元数据，仅 E 的三个 REST 入口启用交接 |
| `websocket/WebSocketController.java` | 复用已加载元数据，仅 E 启用交接并保留 PROMPT 工具限制 |
| `authorization/OperationAnalyzerRegistry.java` | HandoffRead 分析与执行前范围复核 |
| `authorization/AuthorizationService.java` | 仅绑定包的只读策略 |
| `frontend/src/store/sessionMergeStore.ts` | active 恢复、真实锁集合、稳定 epoch 控制 |
| `frontend/src/components/session/SessionMergePanel.tsx` | 进度、暂停原因、恢复/取消、可用目标入口 |

新增小组件：

| 新 Java 文件 | 职责 |
| --- | --- |
| `config/database/V026_ExtendSessionMerges.java` | 附录 SQL 的结构迁移与 validate/checksum |
| `session/merge/MergeProgressRepository.java` | 操作/单元/attempt 的 SQL，按目标查询绑定；无调度平台 |
| `session/merge/MergeHandoffData.java` | 共用 records/enums，简单 schema；不把每个 DTO 拆文件 |
| `session/merge/HandoffReadService.java` | 绑定/ref/清单/分页/字面搜索 |
| `tool/impl/HandoffReadTool.java` | 一个内置工具的参数/结果适配及按需读取说明 |
| `engine/HandoffContextService.java` | 无 LLM 的有界参考投影、历史时点/详情未展开说明 |

这份简化版的主要生产落点为 **14 个现有文件 + 6 个新增文件**，不代表 20 个文件都要重写。删去的是协议和职责复杂度；不为了降低计数把必要的权限、持久化或请求接点移除。实际编译联动、测试/夹具和条件修改另计，不沿用此前 60～90 或 35～50 的估算。

本轮接续开发补充仍落在上述文件及现有测试中，不新增表、接口或生产文件。产物记录在 MergePackageService 内通过现有 JDBC 分页只读导出；复用 TodoWriteTool 和 FileStateCache 的 session ID 隔离，不重构这两个组件。

实施中两个必要的小扩展是 MergeTextBudget 的原子写入，以及 ImageRefInjector 对工程外自有包图片的窄范围读取校验；未放宽普通文件读取权限。

保持不改：SessionManager、SessionMessagePersistence、SubAgentExecutor、SwarmWorkerRunner、SessionExecutionGate、ToolRegistry、FileRead/Grep、ImageResultExternalizer、App/Sidebar/SessionController。只有检查证明现有调用点/通知不足才局部修改，不顺带重构。新的文件写入都接现有磁盘防护，不能因为不改 helper 就绕过它。

Repository 只依赖 JDBC/事务/序列化；ReadService 依赖 Repository 和文件能力，不依赖编排服务/QueryEngine/ToolRegistry；ContextService 和 Tool 依赖 ReadService。避免循环依赖。SessionManager 不依赖 MergeService，也不发送合并清理事件；包清理由合并生命周期触发。

按顺序实施：

1. schema/Repository/简单数据契约；普通/fork/Swarm 执行和持久化保持基线。
2. 独立快照、平铺清单、提前释放及重复合并；先证明来源删除后原件仍存在。
3. 串行分块、预算适配、恢复/取消及原子发布。
4. 单一读取工具、授权和每轮上下文入口。
5. 前端协议、故障回归和第 9 节前后端接续开发/会话隔离验收。

新增迁移、Repository、ReadService、Context 四个测试文件，覆盖读取、权限与普通/合并入口隔离；扩展现有 merge 三类测试及 MergeFixture，并更新上述相邻链路和两个前端测试。阶段可分提交，但整体闭合前不宣称完成。未明确要求的能力不要自行补成新的“必须”；会降低已确认能力时先询问用户。

## 附录 A：精简后的迁移 SQL

在 MigrationRunner 事务中执行；保留 V024 全部列名以通过旧 validate。execute 发现 protocol_version 已存在时只做 validate，不能重复重建。迁移中断整体回滚；新迁移编号若被占用须调整。旧 target ID 若异常重复，报告并回滚，不能删行修复。

```sql
CREATE TABLE session_merges_v2 (
    operation_id TEXT PRIMARY KEY,
    idempotency_key TEXT NOT NULL UNIQUE,
    params_json TEXT NOT NULL,
    target_session_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK(status IN ('preparing','paused','failed','completed','cancelled')),
    stage TEXT NOT NULL,
    package_path TEXT NOT NULL,
    result_json TEXT NOT NULL DEFAULT '{}',
    usage_json TEXT NOT NULL DEFAULT '[]',
    error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    protocol_version INTEGER NOT NULL DEFAULT 1 CHECK(protocol_version IN (1,2)),
    active_slot INTEGER CHECK(active_slot IS NULL OR active_slot=1),
    run_epoch INTEGER NOT NULL DEFAULT 0 CHECK(run_epoch>=0),
    snapshot_version INTEGER NOT NULL DEFAULT 0 CHECK(snapshot_version>=0),
    snapshot_hash TEXT,
    handoff_hash TEXT,
    execution_json TEXT NOT NULL DEFAULT '{}',
    error_code TEXT,
    retry_at TEXT,
    CHECK(protocol_version=1 OR
        (status IN ('preparing','paused','failed') AND active_slot IS 1) OR
        (status IN ('completed','cancelled') AND active_slot IS NULL)),
    CHECK(protocol_version=1 OR stage IN
        ('snapshotting','extracting','aggregating','validating','publishing','completed')),
    CHECK(protocol_version=1 OR status<>'completed' OR
        (snapshot_hash IS NOT NULL AND handoff_hash IS NOT NULL AND stage='completed'))
);
INSERT INTO session_merges_v2
    (operation_id,idempotency_key,params_json,target_session_id,status,stage,
     package_path,result_json,usage_json,error,created_at,updated_at,protocol_version,error_code)
SELECT operation_id,idempotency_key,params_json,target_session_id,
    CASE WHEN status='preparing' THEN 'failed' ELSE status END,
    CASE WHEN status='preparing' THEN 'interrupted' ELSE stage END,
    package_path,result_json,usage_json,
    CASE WHEN status='preparing' THEN '旧版任务中断，请重新发起' ELSE error END,
    created_at,updated_at,1,
    CASE WHEN status='preparing' THEN 'LEGACY_INTERRUPTED' ELSE NULL END
FROM session_merges;
DROP TABLE session_merges;
ALTER TABLE session_merges_v2 RENAME TO session_merges;
CREATE INDEX idx_session_merges_status ON session_merges(status);
CREATE UNIQUE INDEX uq_session_merges_active ON session_merges(active_slot) WHERE active_slot=1;

CREATE TABLE session_merge_units (
    operation_id TEXT NOT NULL REFERENCES session_merges(operation_id) ON DELETE CASCADE,
    unit_id TEXT NOT NULL,
    stage TEXT NOT NULL CHECK(stage IN ('extracting','aggregating')),
    ordinal INTEGER NOT NULL,
    input_hash TEXT NOT NULL,
    input_json TEXT NOT NULL,
    processor_version TEXT NOT NULL,
    model TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('pending','running','completed','split','failed')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    run_epoch INTEGER NOT NULL DEFAULT 0,
    result_path TEXT,
    result_hash TEXT,
    error_code TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY(operation_id,unit_id),
    CHECK(state<>'completed' OR (result_path IS NOT NULL AND result_hash IS NOT NULL))
);
CREATE INDEX idx_session_merge_units_next
    ON session_merge_units(operation_id,stage,state,ordinal,unit_id);

CREATE TABLE session_merge_attempts (
    request_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL REFERENCES session_merges(operation_id) ON DELETE CASCADE,
    unit_id TEXT NOT NULL,
    run_epoch INTEGER NOT NULL,
    model TEXT NOT NULL,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    outcome TEXT NOT NULL CHECK(outcome IN ('running','completed','error','cancelled','unknown')),
    usage_json TEXT,
    usage_reported INTEGER NOT NULL DEFAULT 0 CHECK(usage_reported IN (0,1)),
    estimated_cost_usd REAL,
    CHECK(usage_reported=1 OR estimated_cost_usd IS NULL)
);
CREATE INDEX idx_session_merge_attempts_operation
    ON session_merge_attempts(operation_id,unit_id,started_at);
```

`execution_json` 只保存 resolvedModel/workingDirectory/targetTitle/processorVersion/promptVersion，不放历史大数组。进度从 unit 表统计（split 父项不重复计入完成/已知数量），retryAt 来自操作行；不再持久化第二份进度游标。`input_json` 只放有界输入引用或输入文件引用；result_path 是包内相对路径。usage_json 对 v2 存聚合展示，逐次请求以 attempt 为准。

运行时绑定查询必须同时满足 `session_merges.protocol_version=2 AND status='completed' AND target_session_id=可信根sessionId`，并 JOIN sessions 确认目标仍存在。删除目标后此查询返回空，但不删合并操作的诊断记录。

validate/迁移测试至少覆盖：旧行保留、再启动、唯一 active（含 NULL 语义）、状态 CHECK、completed 哈希、结果文件字段、FK、目标删除后查询无绑定及迁移回滚。SQL 的 `active_slot IS 1` 不可换成会让 NULL 误通过 CHECK 的条件。
