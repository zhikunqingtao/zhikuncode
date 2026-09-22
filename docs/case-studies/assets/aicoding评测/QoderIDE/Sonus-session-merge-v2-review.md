# 会话合并 v2 本地改动深度审查报告

## 一、结论

**当前不建议将这批改动作为"功能完成、可合入发布"的版本提交。**

本次结合 `docs/session-merge-architecture-v2.md`，完成了架构符合性、代码正确性、非合并链路影响和测试质量审查，结论如下：

- **发现3项证据充分的 P2 问题**，分别涉及恢复时的材料分类、换模型后的聚合恢复、取消过程中的来源锁状态。
- **未发现证据充分的 P0/P1 问题**，也未发现无关普通会话必然损坏的执行路径，但不能据此保证"非合并链路零回归"。
- 架构主体基本成立，不过存在明确的规范偏离，以及数据库升级、公共执行链路、真实模型接续等验证缺口。
- **本轮为只读静态审查，没有运行测试、构建或端到端验收。** 因而既不能宣称测试通过，也不能宣称这些尚未执行的检查失败。

---

## 二、审查范围

审查基线：HEAD `b98e18721169436f8f35373a4120c530f54310d5` 与当前工作树。

| 项目 | 数量 |
|---|---:|
| 本地变更文件总数 | 49 |
| 已跟踪修改 | 36 |
| 未跟踪新增 | 13 |
| 指定排除文件中实际有改动的文件 | 15 |
| 最终纳入审查 | **34** |
| 纳入的生产文件 | 20 |
| 纳入的测试及夹具 | 13 |
| 纳入的架构文档 | 1 |

已严格排除指定的全部16个文件。其中，`frontend/e2e/message-copy-all.spec.ts` 当前没有本地变化。

### 纳入范围分类清单

#### 生产文件（20个）

后端路径统一以 `backend/src/main/java/com/aicodeassistant/` 为前缀：

| 文件 | 状态 |
|---|---|
| authorization/AuthorizationService.java | 修改 |
| authorization/OperationAnalyzerRegistry.java | 修改 |
| config/database/V026_ExtendSessionMerges.java | 新增 |
| controller/QueryController.java | 修改 |
| controller/SessionMergeController.java | 修改 |
| engine/HandoffContextService.java | 新增 |
| engine/ImageRefInjector.java | 修改 |
| engine/QueryEngine.java | 修改 |
| engine/QueryLoopState.java | 修改 |
| session/merge/HandoffReadService.java | 新增 |
| session/merge/MergeHandoffData.java | 新增 |
| session/merge/MergePackageService.java | 修改 |
| session/merge/MergeProgressRepository.java | 新增 |
| session/merge/MergeSummaryService.java | 修改 |
| session/merge/MergeTextBudget.java | 修改 |
| session/merge/SessionMergeService.java | 修改 |
| tool/impl/HandoffReadTool.java | 新增 |
| websocket/WebSocketController.java | 修改 |

前端（2个）：

| 文件 | 状态 |
|---|---|
| frontend/src/components/session/SessionMergePanel.tsx | 修改 |
| frontend/src/store/sessionMergeStore.ts | 修改 |

#### 测试及夹具（13个）

后端路径统一以 `backend/src/test/java/com/aicodeassistant/` 为前缀：

| 文件 | 状态 |
|---|---|
| authorization/AuthorizationServiceProjectFileScopeTest.java | 修改 |
| authorization/OperationAnalyzerRegistryTest.java | 修改 |
| config/database/V026ExtendSessionMergesTest.java | 新增 |
| engine/HandoffContextServiceTest.java | 新增 |
| engine/QueryEngineUnitTest.java | 修改 |
| session/merge/HandoffReadServiceTest.java | 新增 |
| session/merge/MergeFixture.java | 修改 |
| session/merge/MergePackageServiceTest.java | 修改 |
| session/merge/MergeProgressRepositoryTest.java | 新增 |
| session/merge/MergeSummaryServiceTest.java | 修改 |
| session/merge/SessionMergeServiceTest.java | 修改 |

前端（2个）：

| 文件 | 状态 |
|---|---|
| frontend/src/components/session/SessionMergePanel.test.tsx | 修改 |
| frontend/src/store/sessionMergeStore.test.ts | 修改 |

#### 规范文档（1个）

| 文件 | 状态 |
|---|---|
| docs/session-merge-architecture-v2.md | 新增 |

范围内已跟踪 diff 为新增1960行、删除924行，不包含新增文件全文。

审查前后 HEAD、变更路径、状态和复核的行数统计一致；未做逐文件内容指纹，不能排除外部发生同状态、同计数的内容修改。

---

## 三、问题清单

### P2-1：阻断记录恢复时绕过材料分类，归档专用内容可能进入模型输入

**位置**

- `MergePackageService.java:265–271`
- 正常分类逻辑：同文件 `509–535`
- 下游整理输入：`MergeSummaryService.java:72–79、198–201`

**触发条件**

消息或 checkpoint 因物化上限被保存为阻断记录，随后提高上限并恢复；原始记录包含思考内容、私有续传状态或内嵌图片。

**根因**

正常解析路径会：

- 跳过仅供归档的思考和续传字段。
- 将图片作为资产处理，而非把 base64 当正文交给模型。

恢复路径却直接将完整原始 JSON 转成提取文本，没有复用上述分类逻辑。因此，同一条记录是否经过暂停恢复，会改变模型能看到的内容。

**影响**

- 违反规范第4.4节的"仅归档材料"边界。
- 图片 base64 可能被当成普通文本切片、整理，增加无效输入和成本。
- 原本不应参与整理的内部状态可能发送至所选 provider。

这是**合并恢复链路的问题**，不是普通会话保存路径的直接回归；没有证据表明已经发生凭据泄露。

**修复建议**

正常解析与恢复解析应共用同一套分类、校验和投影逻辑。恢复仍读取封存原件，但不能用完整 JSON 文本代替受控投影。

**必须补充的测试**

让同一记录分别经历：

1. 正常封存和解析。
2. 先阻断、提高上限、再恢复。

断言两条路径产生等价的模型可见内容，同时原件完整保留、模型输入不包含归档专用字段及图片 base64。

---

### P2-2：换模型恢复可能跳过已有聚合计划，导致持续暂停、无法发布

**位置**

- `MergeSummaryService.java:87–106、120–122`
- 恢复单元状态：`MergeProgressRepository.java:108–115`

**触发条件**

1. 已完成一层聚合，只剩一个结果。
2. 旧模型估算该结果超过1536 token，于是创建下一层聚合任务。
3. 下一层调用失败，操作暂停。
4. 换模型恢复后，新模型估算同一结果不超过1536 token。

**根因**

是否结束聚合，取决于**当前模型的动态估算**；但未完成的聚合计划已经持久化。

新模型可能使循环提前退出，遗留旧计划中的 pending 单元。随后完整性校验发现未完成单元，抛出 `MERGE_INCOMPLETE_UNITS`。

再次使用该模型恢复，仍可能重复同一路径。

**影响**

- 合并无法发布，恢复操作不能有效推进。
- 未结束操作继续占用 active slot，阻止新的合并。
- 不直接阻断普通查询。

这不符合规范第5.2、5.3节关于恢复未完成单元的要求，也暴露了聚合计划与执行终止条件不一致的问题。

**修复建议**

必须明确持久计划的权威性：

- 优先完成已经存在的待处理层，再判断是否结束；或
- 事务性地作废不再需要的未完成计划，发布校验仅针对有效计划。

不能简单忽略 pending 单元，否则会破坏完整性保障。

**必须补充的测试**

在第二层聚合暂停后切换模型，使前一层结果跨过1536 token 阈值。验证恢复可以发布、没有遗留活动 pending，已完成单元不会重复调用模型。

---

### P2-3：取消状态被提前等同于来源锁已释放，前后端可用性判断不一致

**位置**

- `sessionMergeStore.ts:28–31`
- 终态处理：同文件 `141–142、188–189`
- 后端取消与解锁：`SessionMergeService.java:150–153、230、263`

**触发条件**

在快照复制期间取消合并，worker 尚未完成退出和清理。

**根因**

后端存在合法的过渡状态：

- 操作已经是 `cancelled`。
- 实际来源 gate 尚未释放。
- 服务端返回的 `lockedSourceSessionIds` 仍然非空。

前端却只在 `preparing` 时认可锁集合，看到 `cancelled` 就立即推断来源未占用。

**影响**

- 界面提前表现为来源会话可用。
- 用户尝试执行或写入时，后端仍可能拒绝。
- **后端写保护仍然有效，没有发现因此绕过保护或造成数据丢失。**

该问题直接影响参与合并的普通来源会话，不影响无关会话。它违反了规范要求的"根据服务端真实锁集合判断占用"。

**修复建议**

直接使用服务端锁集合，不从操作状态推断是否解锁；同时修正终态停止轮询的条件：

- 终态但锁非空：继续有界查询。
- 确认锁集合为空：才结束解锁状态跟踪。

只改 selector、不改终态缓存和轮询，会产生另一种风险：取消时的旧锁可能长期留在前端。

**必须补充的测试**

用同步屏障阻塞复制，取消后先返回"已取消但仍有锁"，随后 worker 退出并返回空锁。验证前端只在收到真实空集合后解除占用。

---

## 四、架构规范符合性矩阵

以下"符合"仅指源码层面的判断，不表示测试或运行验收通过。

| 架构要求 | 判断 | 说明 |
|---|---|---|
| 4.3：封存包自持，后续不依赖来源会话存在 | 静态符合 | 独立包、staging 原子移动及已有包校验路径存在；见 `MergePackageService.java:87–98、150–198`。 |
| 4.3：复制截止点与来源树写隔离 | 部分符合／待验证 | 有来源及后代 gate、集合与消息边界复核；尚不能证明所有写入口及跨表并发边界完全闭合。 |
| 4.3：封存后、首次模型调用前释放来源 | 静态符合 | `SessionMergeService.java:192–201` 先释放来源，之后才进入整理；异常路径有 finally 清理。 |
| 4.4：仅归档材料不送入提取 | **不符合** | 正常与恢复路径分类不一致，对应 P2-1。 |
| 5.1：旧库迁移、旧行保留、约束更新 | 静态符合 | V026 有旧行保留及约束迁移逻辑；实际旧库通过完整启动链尚未验证。 |
| 5.2：数据库保证单一未结束操作 | 静态符合 | active slot 唯一约束覆盖暂停等状态，不仅依赖进程内互斥。 |
| 5.2/5.3：epoch 隔离、取消失效旧执行 | 静态符合 | 仓库及服务存在 epoch 递增、取消和旧 writer 收尾控制；不代表全部并发竞态已运行验证。 |
| 6.1：结构化栏目、状态和证据校验 | 静态符合 | 有 schema、栏目和证据别名校验；结构有效不等于语义完整。 |
| 6.2：分片聚合与失败后复用完成结果 | 部分符合 | 存在结果复用、拆分和兜底，但换模型恢复有 P2-2。 |
| 6.3：单请求预算、响应上限和有限重试 | 部分符合／待验证 | 已有估算预算及大小限制；完整 provider 序列化请求硬上限、取消后的实际退出时效未验证。 |
| 7.1：仅合并会话配置交接入口，不持久化投影 | 静态符合 | 配置守卫、临时历史投影及不持久化运行标记路径存在。 |
| 7.2/7.3：可信根绑定、只读工具、不继承同名权限 | 静态符合 | 按真实工具类型分派，分析及 recheck 校验绑定，没有发现普通同名工具自动取得权限的路径。 |
| 7.2：读取分页、完整性、游标和硬限制 | 静态符合 | 15秒检查覆盖 hash 和正文；搜索正文8MiB，输出16KiB，游标绑定版本及参数。 |
| 8：发布事务及提交不确定性处理 | 静态符合 | 目标创建、入口消息及完成状态位于事务内，提交异常后检查持久状态。 |
| 8：前端依据真实锁集合显示占用 | **部分不符合** | 正常封存后释放能够表达，取消收尾窗口存在 P2-3。 |
| 9：真实接续开发语义验收 | **未验证** | 规范 451–455 行的真实样本及变体没有执行。 |

---

## 五、对非合并功能链路的影响

**不能用"有 metadata 开关"直接推出"所有普通链路完全不受影响"。** 本次既有受开关保护的交接行为，也有全局公共代码和启动迁移。

### 1. HTTP/WebSocket 普通查询：未发现直接破坏，但缺集成验证

证据：`QueryController.java:161–180、249–267、342–364`；`WebSocketController.java:921–941、954–961`

普通会话没有合并标记时，不调用交接配置，也不会因此读取交接包或查询合并记录。原有历史加载和持久化接线保留。

不过，这些入口已有代码修改，尚未运行完整 HTTP/WebSocket 回归。

### 2. 查询引擎和历史预算：普通分支有明确退回原行为的逻辑

证据：`QueryEngine.java:838–847、940–952、1811–1817、1830–1836`；`HandoffContextService.java:84–86`

所有查询都会经过新增辅助调用，但普通分支返回空投影、预留0 token，空投影不会改变历史。

已有测试断言覆盖普通分支零交接调用、预算差额和投影不写回历史；**小模型、连续压缩、413恢复的完整执行链仍未验证。**

### 3. 工具与授权：未发现普通工具越权回归

交接工具不是全局注册的工具 Bean；配置时复制集合后追加，没有直接污染全局工具集合。

全局授权分析器确实发生变化，但当前分派以真实工具类型和绑定校验为依据，没有发现同名 MCP/动态工具获得交接工具权限的路径。

### 4. 图片引用：旧入口保留，但共享实现仍属于回归范围

证据：`ImageRefInjector.java:92–103、255–300`；`QueryEngine.java:907–912`

旧重载将新增资产授权条件固定为 false，原路径权限、大小及 hash 校验保留。合并会话才可能获得绑定资产的附加授权。

因此，静态上保留了普通行为，但不能称为"共享代码完全未变"。

### 5. 数据库迁移：影响所有会话，必须单独设发布门禁

**V026 不受会话 metadata 开关控制。**

所有使用该数据库的应用启动都需要执行或校验迁移；一旦迁移失败，普通会话也无法使用。

本轮没有发现受支持旧库必然失败的新增缺陷，但实际旧库升级、完整迁移执行器和再次启动验收尚未完成。

### 6. 前端状态：普通页面也会发生新增后台请求

合并面板挂载后会发现活动操作，即使当前没有本地 pending 操作，也存在经过节流的后台请求。

已有请求合并、错误隔离和监听清理逻辑，没有发现这些请求必然破坏普通页面；但 P2-3 已确认会影响被选为合并来源的普通会话可用性显示。

### 7. fork/Swarm：没有直接修改，不等于零回归

会话管理、消息持久化、子代理执行和 Swarm runner 的相关文件相对 HEAD 没有直接变化。

但它们仍依赖共享引擎、授权、数据库和 gate。**本轮没有运行 fork/Swarm 场景，也没有证明合并目标派生后的全部 metadata 继承行为。**

**综合判断：**

> 没有发现无关普通会话必然损坏的新增路径；但参与合并的来源会话存在已确认的前端状态问题，公共链路和数据库升级也尚不足以获得"零回归"背书。

---

## 六、代码质量与测试覆盖

### 已具备的质量基础

这批改动不是只有接口或空壳测试：

- 快照封存、进度仓库、模型整理、运行投影、读取授权已有较清楚的职责划分。
- 数据库迁移测试使用临时 SQLite，包含外键、唯一约束和回滚断言。
- 进度仓库测试覆盖旧 epoch 拒绝、暂停占位及发布事务边界。
- 服务测试使用同步屏障检查首次模型调用前已释放来源。
- 引擎测试捕获经 normalizer 处理的请求，检查普通分支预算与历史行为。
- 授权测试包含正确绑定和同名工具冒充的正反断言。

这些是有价值的静态测试覆盖证据，但不是本轮测试通过证明。

### 关键缺口

| 缺口 | 具体依据 | 后果 |
|---|---|---|
| 恢复投影缺少负向断言 | `MergePackageServiceTest.java:35–49、75–93` 主要验证封存原件恢复 | 无法发现思考字段、续传状态或 base64 进入整理输入。 |
| 换模型测试未覆盖聚合阈值变化 | `MergeSummaryServiceTest.java:23–25` 对不同模型采用相同估算 | 无法发现持久聚合计划与新模型终止条件冲突。 |
| 取消测试没有复制阶段延迟解锁窗口 | `sessionMergeStore.test.ts:443` 直接返回空锁 | 无法发现 cancelled 与真实解锁之间的时间差。 |
| 部分测试仍走旧封装路径 | `MergePackageServiceTest.java:212、247、259、278、293、309` 调用旧 `build`，线上使用 `seal` | 旧图片、checkpoint、路径及复制测试不能自动算作新链路覆盖。 |
| artifact 导出分支未被夹具激活 | `MergeFixture.java:36–44` 未创建两张 artifact 表；生产代码按表存在与否进入分支 | 无法证明真实产物导出、复制及来源删除后的读取正确。 |
| 夹具弱化生产数据库接线 | `MergeFixture.java:45–47` 将写串行器模拟为直接执行，且未显式开启外键 | 不足以代表生产并发和约束环境。 |
| 个别测试名称强于实际断言 | "待办隔离"未调用 TodoWrite；"聚合引用"未核对完整返回范围 | 不能据名称计算能力覆盖。 |
| 发布回滚服务用例故障发生较早 | `SessionMergeServiceTest.java:152–158` 在发布事务前失败 | 不能算服务层事务中段失败的完整覆盖。 |
| 新增面板交互覆盖不足 | paused/resume/cancel、目标可用性缺少完整专项覆盖 | 状态仓库单测不能替代真实交互验收。 |

**质量改进重点应是统一权威逻辑，而非继续堆局部判断：**

- 材料分类由同一投影流程负责。
- 聚合完成条件与持久计划保持一致。
- 来源占用以服务端真实锁集合为准。

---

## 七、GitHub 提交与发布建议

### 提交判断

如果只是保存开发进度，可以作为**明确标注已知问题的开发中提交**；但不建议把它标记为功能完成或发布就绪。

另一个提交完整性风险是：范围内有 **11个未跟踪文件**，包括6个生产文件、4个测试和1份规范。只提交已跟踪修改，会遗漏新服务、迁移等必要依赖。

**审查排除不等于提交排除。** 指定的16个文件没有获得本次审查背书；如果后续选择性提交，实际提交组合仍需重新构建验证。

### 发布前最低门禁

1. **修复3项 P2，并补齐对应回归测试。**
2. **验证数据库升级**：旧库迁移、完整启动、再次启动校验、约束失败回滚。
3. **验证普通链路**：HTTP/WebSocket 查询、持久化、工具授权、图片，以及 fork/Swarm。
4. **验证恢复边界**：换模型、连续压缩、413、取消期间 worker 收尾及交接入口重建。
5. **补齐真实 artifact 表结构测试**，覆盖导出、读取和来源删除。
6. **执行后端定向及全量验证、前端单测与构建。**
7. **完成浏览器交互及规范要求的真实模型接续开发样本**，不能仅用 stub provider 或结构校验替代。

建议在数据库和资料目录均隔离的环境执行，避免验证过程触碰现有运行数据。

### 建议验证命令（仅供后续执行，本轮全部未运行）

前提：在已经准备好的隔离副本执行，使用 Java 21 及测试配置；确认数据库、资料目录与运行库完全隔离。

在隔离副本的 `backend` 目录：

```bash
# 定向合并链路测试
./mvnw -Dspring.profiles.active=test \
  -Dtest=MergePackageServiceTest,MergeSummaryServiceTest,SessionMergeServiceTest,MergeProgressRepositoryTest,V026ExtendSessionMergesTest,HandoffReadServiceTest \
  test

# 公共接线与迁移回归
./mvnw -Dspring.profiles.active=test \
  -Dtest=MigrationRunnerV2Test,HandoffContextServiceTest,QueryEngineUnitTest,QueryControllerProjectContractTest,SessionMessagePersistenceTest,OperationAnalyzerRegistryTest,AuthorizationServiceProjectFileScopeTest \
  test
```

在隔离副本的 `frontend` 目录：

```bash
npm run test:run -- \
  src/store/sessionMergeStore.test.ts \
  src/components/session/SessionMergePanel.test.tsx \
  --maxWorkers=2 --minWorkers=1
```

完成定向回归后，再分别执行隔离副本全量验证：

```bash
# backend 目录
./mvnw -Dspring.profiles.active=test verify

# frontend 目录
npm run test:run -- --maxWorkers=2 --minWorkers=1
npm run build
```

这些命令不会补出当前缺失的测试，也不能替代真实模型语义验收。

---

## 八、只读确认与局限

- 本次审查仅读取源码、规范及 Git 信息。
- **没有修改工作区、创建产物、运行测试或构建、启停服务、提交或推送代码。**
- 历史测试通过记录未作为本轮验证证据。
- 静态审查不能替代运行验收；普通链路和数据库升级的回归结论基于调用路径分析，而非端到端证明。
- fork/Swarm 完整回归和真实模型语义验收均属未验证状态。

---

## 最终评价

**架构方向基本正确，关键隔离措施已有实现；但恢复与取消边界存在3项明确缺陷，运行验证也尚未闭合，当前不应给出"非合并链路零回归、达到发布标准"的结论。**
