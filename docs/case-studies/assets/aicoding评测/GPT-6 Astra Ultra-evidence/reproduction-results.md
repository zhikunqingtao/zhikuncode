# 隔离复跑结果与证据边界

本页只汇总已执行且有记录的合成复现。代码版本为 `aicoding-eval-0923` / `207fe6d0d159db1b575af243dbf3c77536377431`，与 [manifest.json](manifest.json) 冻结的材料相对应。业务代码未为本次复现修改；新增测试放入临时隔离副本，使用临时目录、临时 SQLite、合成资料和 mock/stub provider，没有调用真实付费模型或运行中的用户数据库。

**本页结果截止：2026-09-23 08:14（Asia/Shanghai）已经结束的批次。** 已包含 Read 追加 4 项和前端最终 5 项；前端先前 2 项保留为历史记录，已包含在最终 5 项内，不重复加总。机器可核对的逐用例结果、日志哈希及脱敏说明见 [results/run-summary.json](results/run-summary.json)。

## 已执行批次

| 批次 | 用例 | 结果 | 如何理解 |
|---|---:|---|---|
| 既有后端复现 `MergeReviewReproTest` | 5 | **5 个断言失败，0 error，0 skipped** | 测试断言预期正确行为，当前代码违反断言，形成红色缺陷证据；这不是本轮全量回归结果。 |
| 既有前端复现 `sessionMergeReviewRepro.test.ts` | 1 | **1 个断言失败** | 已保存旧终态时没有调用 `/active`；应与后台发现承诺一起评估。 |
| 本轮后端协议/资产/引擎/读取观察性复现及既有隔离控制 | 15 | **15 通过，0 failure/error/skipped** | 断言的是实际异常行为、边界或控制条件；绿色不表示业务缺陷已修复。 |
| 本轮前端观察性复现 `rankingProtocolRepro.test.ts` | 5 | **5 通过** | 包含 selector 锁、轮询串行、两种通知重复路径及终态缓存边界；不是修复验收。 |

上述 **6 个红色断言不等于 6 个独立缺陷**：两个 checkpoint 用例覆盖同一兼容/版本问题；图片唯一需求的发布门槛还涉及规范解释。是否计分及去重以核验台账和最终裁定为准。15 个后端通过项含 `RankingProtocolReproTest` 6 项、`RankingAssetReproTest` 2 项、`RankingQueryReproTest` 1 项、`RankingReadReproTest` 4 项（合计 13 个本轮观察项），以及既有 `QueryEngineUnitTest.handoffProjectionOnlyRunsForExplicitlyMergedSessions` 普通/E 两个参数化控制项。

## 红色断言观察到什么

源代码：[MergeReviewReproTest.java](reproductions/MergeReviewReproTest.java)、[sessionMergeReviewRepro.test.ts](reproductions/sessionMergeReviewRepro.test.ts)。执行日志：[后端](results/original-backend.txt)、[前端](results/original-frontend.txt)。

| 用例 | 原断言与实际结果 | 限定 |
|---|---|---|
| `recoveredProjectionMustNotSendArchiveOnlyBlocksToExtractor` | 预期派生投影不含合成 thinking/private state 标记，实际都含有。 | 检查进入提取输入的派生文本，不是读取模型真实隐藏推理。 |
| `sameCheckpointUuidWithChangedLegacyToolResultMustRetainVersions` | 预期同 UUID、不同 legacy 工具结果保留 2 个版本，实际只有 1 个。 | 合成旧结构用于验证兼容与去重。 |
| `ordinarySavedCheckpointToolResultMustBeSearchableForExtraction` | 预期提取文本含 `ONLY_EVIDENCE_TEST_FAILED`，实际缺失。 | 与上一项同源，排名不能重复计权。 |
| `copiedManagedFileMustNotAlsoBeReportedAsAnExternalGap` | 原件已复制后预期无同一文件的外部缺口，实际有 1 项。 | 证明诊断/收录清单不一致，不推导全部资料丢失。 |
| `imageOnlyUserRequirementMustNotPublishWithoutAnyVisualExtraction` | 预期 paused，实际 completed。 | 证明存在不先理解视觉内容就发布的路径；是否应阻止发布需结合“关键需求仅存在附件”规范，不能只凭断言决定规则。 |
| `discovers the authoritative active operation after restoring an older completed result` | 预期调用 `/api/session-merges/active`，实际只调用旧操作 `/api/session-merges/old`。 | 合成 localStorage 与 fetch；无真实跨设备网络。 |

## 本轮观察性复现

源代码：[协议](reproductions/RankingProtocolReproTest.java)、[资产](reproductions/RankingAssetReproTest.java)、[引擎](reproductions/RankingQueryReproTest.java)、[读取](reproductions/RankingReadReproTest.java)、[前端](reproductions/rankingProtocolRepro.test.ts)。执行日志：[后端首批](results/protocol-backend.txt)、[后端读取追加](results/read-backend.txt)、[前端最终 5 项](results/final-frontend.txt)。[前端先前 2 项日志](results/protocol-frontend.txt)仅保留过程记录，不额外计入通过总数。

| 场景 | 已观察的行为 | 不应扩张的结论 |
|---|---|---|
| 显式历史引用含中间 symlink | 指向合成外部目录的文件被复制入快照。 | 非通过读取工具直接突破目录校验；应定位为导出阶段引用处理。 |
| 无效外部路径含 NUL | `InvalidPathException` 终止 seal，未生成封存文件。 | 非所有外部路径都会失败。 |
| 修正 JSON 解析配置后恢复 | 开启现有 Jackson 单引号解析选项后，原合成内容已可解析，但无 ref gap 的关联校验仍先拒绝恢复。 | 不要求任意损坏原件均能修复，不认可跳过坏 checkpoint。 |
| 物化容量提高的正控制 | 提高合成物化上限后，同类已关联 blocked 记录可以恢复成功。 | 说明恢复功能并非全部失效。 |
| 非 UTF-8 附件 | GBK 合成附件原件已复制，但列为 blocking，恢复仍拒绝。 | 仅证明策略；“非关键”语义判断与规格裁定另记。 |
| 换模型后的聚合恢复 | 特定分层已经规划时，模型估算比例改变使较早层达到 brief 阈值，遗留未完成单元导致 `MERGE_INCOMPLETE_UNITS`；换回旧比例可继续。 | 不是“所有换模型必失败”，也不是模型相关分片宽度导致 `MERGE_UNIT_INPUT_CHANGED`。 |
| E 投影容量异常的引擎消费 | 注入 `HANDOFF_CONTEXT_BUDGET_TOO_SMALL` 异常后，引擎捕获并返回 error 类型 `QueryResult`。 | 此用例 mock 了 project 异常，仅验证消费路径；不证明 REST 必返回 HTTP 500，控制器通常仍返回结果体。 |
| WebP 扩展名丢失 | 同一 2×2 合成 WebP，有后缀时工具接受，复制成无后缀后报 `HANDOFF_ASSET_UNSUPPORTED`。 | 当前运行时缺内容识别 WebP reader；有后缀工具接受不代表图片已成功注入模型。 |
| 10–20MiB PNG | 真实高熵 PNG 能完整解码，HandoffReadTool 返回成功 image_ref，ImageRefInjector 不注入；小 PNG 正控制能注入。 | **最先触发的是 1,500,000 Base64 字节单图上限**，早于 10MiB 文件检查，不能只把第一阻断点归给后者。 |
| 非 preparing 的真实锁 DTO | cancelled+非空 lockedSourceSessionIds 输入被 selector 转为空。 | 是函数级输入复现；实际服务端可见时间窗口由 cancel/暂停与 writer 收尾次序的静态代码补充。 |
| 轮询与取消 | GET 未完成时 submitting=true，cancel 调用等 GET 完成才 POST；面板静态绑定该标志禁用按钮。 | 没有在此批运行完整浏览器点击测试，也不宣称取消永远无法执行。 |
| 读取授权超时的顶层错误码 | 人工时钟触发预算超时后，`authorize` 抛出的错误 code 为 `HANDOFF_INVALID_RESOURCE`，message 保留 `HANDOFF_READ_BUDGET_EXCEEDED`。 | 证明错误分类被包装，不等于缺少超时约束或继续越界读取。 |
| 读取入口中断的正控制 | 预设线程中断时，`read` 抛出 `InterruptedIOException(HANDOFF_READ_INTERRUPTED)`，并保留中断标志。 | 此路径未被统一包装为无效资源，不能笼统断言所有中断均被吞掉。 |
| 搜索摘录 Unicode 边界 | 合法原文中的 emoji 恰跨搜索片段截取边界，返回片段含未配对高代理项。 | 只证明摘录的 UTF-16 边界问题，不证明原件被修改或丢失。 |
| 图片复制配额错误分类 | 相同复制配额下，内嵌图片超限暂停码为 `MERGE_PREPARATION_FAILED`；普通受管文件超限为 `MERGE_COPY_INCOMPLETE`。 | 两者均暂停且目标不可用；只证明图片路径丢失更具体的分类，不能说仍发布了损坏目标。 |
| 暂停通知重复的两条路径 | 支持的 pause→resume→pause，以及 paused 的 canResume=false→true，均会在 5 秒有效期内追加相同 key 的两条通知。 | 真实 store/notificationStore 加合成 fetch；相同 DTO 再轮询不会继续追加是正控制，没有测浏览器 toast 像素呈现。 |
| 终态目标可用性缓存边界 | 首次 completed 校验后，后续 refresh 不重查 targetAvailable；重新 openDialog 后缓存失效并能读取已变为 false 的状态。 | 是可恢复的缓存陈旧边界，不是永久无法更新。 |

资产测试只 mock 可信根身份和 `HandoffReadService.asset` 的已授权资产查找，实际运行 HandoffReadTool、ImageResultExternalizer、ImageRefInjector；不是数据库绑定到真实模型的完整 E2E。WebP 是本机 Pillow/libwebp 生成且重新解码验证的合成 2×2 图，PNG 为固定随机种子的真实像素，未下载第三方素材。

## 复跑方式和日志处理

在冻结提交的独立副本中，将上述 Java 测试按其 `package` 放入 `backend/src/test/java` 对应目录，将两个 TypeScript 测试放入 `frontend/src/store`。沿用仓库测试依赖与 Java 21；运行下列选择器即可重做本页范围。以下是可复跑命令，不是声称保留了操作系统完整 shell 历史：

```sh
# 独立副本/backend；这条预期为红色，因为断言期望正确行为。
./mvnw -q -Dspring.profiles.active=test -Dtest=MergeReviewReproTest test

# 独立副本/backend；观察性断言预期为绿色。
./mvnw -q -Dspring.profiles.active=test '-Dtest=RankingProtocolReproTest,RankingAssetReproTest,RankingQueryReproTest,RankingReadReproTest,QueryEngineUnitTest#handoffProjectionOnlyRunsForExplicitlyMergedSessions' test

# 独立副本/frontend；第一条预期红，第二条预期绿。
npm run test:run -- src/store/sessionMergeReviewRepro.test.ts --maxWorkers=1 --minWorkers=1
npm run test:run -- src/store/rankingProtocolRepro.test.ts --maxWorkers=1 --minWorkers=1
```

日志仅将本机仓库、隔离副本、用户家目录和 JUnit 临时目录替换为 `<REPOSITORY>`、`<ISOLATED_ROOT>`、`<USER_HOME>`、`<TEST_TEMP>`；原断言、错误码、测试名和堆栈保留。保留源日志哈希与公开日志哈希，二者因路径脱敏不同是预期行为。没有发布 surefire XML 的完整 properties/env/classpath；逐用例摘要从 XML 提取并标出原 XML 哈希。日志出现测试有意触发的 ERROR 堆栈不等于该观察性用例失败，应以用例断言与汇总为准。

**本轮没有重新运行后端/前端全量套件，没有做真实模型接续开发语义验收，也没有修改业务实现。** 旧报告声称的全量数字属于历史材料，不能混作本页新的实跑证据。
