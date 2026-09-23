# 六类边缘条目的独立复核

范围：仅当前代码相对 `b98e18721169436f8f35373a4120c530f54310d5` 的变化，不修改业务文件，不据作者 P1/P2 标签评严重度。下面的代码行号指当前工作树；标明 `b98e187` 的行号指 `git show b98e187:<path>`。本轮只写本文件和 `reproductions/rankingProtocolRepro.test.ts`，没有运行 Maven、真实模型或真实用户数据测试。

## 裁定摘要

| 统一键 | 事实与范围 | 建议归类 | 去重与计分边界 |
| --- | --- | --- | --- |
| `cancel-publish-conflict-code` | 新增取消接口的真实窄竞态；最终 completed 安全，错误码可能错成 STALE | 功能，成立，new，minor | 独立于取消日志；仅算错误分类，不能扩写成取消已发布会话或事务失效 |
| `cancel-log-says-paused` | 取消后 worker 的 warn 文案仍称 paused；持久化仍为 cancelled | 工程/诊断，成立，new，不计功能 TP | 不与取消接口竞态重复计分，也不单列功能缺陷 |
| `merge-warning-details-unavailable` | 新完成 DTO 不再带旧 warnings，结果面板的明细分支失去数据 | 功能，成立，new，minor | 独立于 copiedCount/warningCount 计数语义错误；仅指结果面板，资料包及 E 内的 gaps 仍可读 |
| `merge-model-unavailable-classification` | 新 worker/recover 重新选模型时，已失效的容量配置可能降为通用准备失败 | 功能，成立，new，minor | 独立的已知错误缺分类；不代表不能换模型恢复，也不指首次 start/resume 的同步选择路径 |
| `terminal-target-availability-stale` | 当前 targetAvailable 会陈旧，但删除目标后旧结果按钮仍能点击的后果在基线已有 | 既存限制，retained，不计新增 TP | 与 active 发现问题不完全同因；不能拿新增字段/新规范包装为新增回归 |
| `duplicate-merge-toast` | 新 paused/resume 循环及 paused DTO 变化使同 ID 的重复通知成为正式流程可达 | 功能，成立，new，minor | R05/R12/R14/R16 合为一个缺陷；通知 store 的 push 虽是旧代码，新增可达链路属于本次变化 |

按主审已统一的报告计分政策，R13/R17 对“通知已去重”的 UI 整体保证不另计 FP；漏报由 FN 体现。R05 的具体重复通知发现应从先前 `retained` 修正为 `new`。本文件不计算总分，也不改变该统一政策。

## 1. cancel 与 publish：只有错误分类不精确

对应 R03-C10，报告第 92 行。

实际可达顺序：

1. `SessionMergeService.cancel` 第 147 行读到 preparing；第 148 行尚不进入 ALREADY_COMPLETED 分支。
2. worker 在第 207—216 行的发布事务提交目标及 completed。`cancel` 是 synchronized，但 worker 的 `execute` 第 172 行没有获取同一 service 监视器，不能用 synchronized 排除该交错。
3. `MergeProgressRepository.cancel` 第 120—124 行仅更新 preparing/paused/failed，此时 UPDATE 命中 0 行。
4. service 第 150 行固定抛 `MERGE_STALE_OPERATION`，同时重新 `get(id)` 带回 completed 的最新 operation。

这违反规范第 272 行区分 ALREADY_COMPLETED 与 STALE 的接口约定。不过目标与 completed 在事务内原子发布；cancel 的 UPDATE 不会覆盖 completed。`sessionMergeStore.ts:243-247` 还会先采纳冲突响应中的 completed operation，再于第 249 行展示错误。因此已证明的后果是窄窗口中的错误码/提示偏差，不是目标被取消、资料损坏或永久失去结果。静态控制流足以证明交错，未做真实并发频率测量。

## 2. cancelled-log：诊断问题，不等于状态错误

对应 R09-C03，报告第 94 行；原报告已明确“数据库状态正确”“仅日志误导排障”。

`cancel` 第 150—152 行先把数据库状态改为 cancelled 并增加 epoch，再 abort worker。worker 在第 176—177 行检查 abort/epoch 后抛出中断或旧 epoch 异常，第 219 行无条件输出 `Merge paused; snapshot and committed units retained`。第 226 行尝试 pause，但 `MergeProgressRepository.pause:98-102` 只更新原 epoch 且仍 preparing 的记录，不能把 cancelled 改回 paused。随后第 232 行清理并释放 writer。

所以静态事实成立，且是新增取消流程上的不准确日志。不过未证明任何用户操作、资料正确性或状态机受到影响。按功能缺陷集合边界调整为 engineering，不作为 TP，也不把它作为 FP。它与上一项没有共同的直接根因：上一项是竞争失败后的返回码选择；这一项是 catch 日志在核对持久化终态前固定使用 paused 文案。

## 3. missing warnings details：结果面板回退，资料明细并未丢失

对应 R13-C54，报告第 473 行。

基线 `SessionMergeService.java:168-170,181-183` 把 missing/ownership_unknown/copy_failed 附件的 warnings 列表放入 result。当前 service 第 192 行封存结果及第 214—215 行完成结果都只保留计数和完成元数据，没有 warnings；`SessionMergePanel.tsx:210-212` 仍仅在 `result.warnings?.length` 非空时展示清单。因此在允许成功的历史缺失附件场景，旧面板可以列明具体缺失路径和原因，新面板只有未收录数量及资料索引路径。这是直接可证明的 DTO 与现有 UI 脱节，建议 minor。

边界：`MergePackageService.java:360-362` 仍将原因写入 gaps；`MergeSummaryService.java:139-148` 把 `HandoffRead read ref=gaps` 入口写入 E 的交接文本；`HandoffReadService.java:171-174` 支持读取该文件。因此不能把报告中的“无明细”扩写为资料丢失、E 无法追溯或所有入口均无明细。当前 `indexPath` 在 panel 第 213 行是文本，结果面板没有直接 gaps 入口；打开 E 后仍可按交接说明读取。

该项与“复制数量实际混合了非文件缺口”的统计问题可分开：即使把计数计算和文案全部修正，warnings 列表仍为空；反之只补结果明细也不能改变计数口径。只把同一缺失 DTO 的不同附件例子算一次。

## 4. model unavailable：限于重选已持久化模型后的错误指引

对应 R04-C04，报告第 50 行。

可靠触发前提是：已持久化的 v2 操作使用原先有效的自定义模型；进程中断后，模型配置被移除或失去明确容量配置，再启动恢复。无需假定配置热更新。`SessionMergeService.recover:74-77` 对 interrupted 操作重新启动 worker；第 200 行重选 ledger 中的 resolvedModel。`MergeSummaryService.select:46-50` 在 provider 仍存在但找不到明确容量时抛中文 IllegalArgumentException。`safeCode:257-258` 只接受符合大写错误码正则的 message 首段，所以降为 `MERGE_PREPARATION_FAILED`，`explain:246` 给通用检查指引，未进入第 242 行明确换模型的提示。

该前提由 `ModelRegistry.java:166-185` 的明确容量查找路径支持：配置可在重启前改变；不需要在普通请求或真实模型调用中注入故障。provider 不再提供该 ID 时，`LlmProviderRegistry.java:65-73` 的英文异常也会走通用 fallback，但这不是额外独立缺陷。

基线把 start 时选好的 Selection 直接传给 worker（旧 `SessionMergeService.java:154,172`），没有这一 v2 持久化恢复重选路径，因此范围为 new。已证明后果只是原因码及恢复提示不精确：暂停进度仍保留，panel 第 218—222 行仍提供换模型后恢复，不能称为恢复能力本身失效。首次 start 第 103 行和显式 resume 第 141 行的同步 select 发生在 worker catch 之外，不能拿它们来证明本条 execute 内的降级。

这与通用 safeCode 中其他已知异常缺映射可能共享实现位置；应以具体触发契约和后果去重，不能将每一种失效模型/语言文本各计一次。本次只保留一个模型选择错误分类条目。

## 5. terminal targetAvailable：事实真，但用户后果早已存在

对应 R03-C28，报告第 100 行。

当前服务 `SessionMergeService.java:264` 每次 GET 会从 sessions 表计算 targetAvailable。前端收到完成状态后把 operationId 放入 `validatedTerminal`（store 第 188—189 行），后续 refresh 第 141—142 行直接返回；其他入口删除 E 后，当前已打开结果的 targetAvailable 仍可留在 true。panel 第 214 行于是仍允许点击，实际激活检查失败后由第 79—82 行显示错误。`openDialog:119-122` 会清缓存，所以关闭重开可以重新校验，不是永久错误。

但基线 `sessionMergeStore.ts:129` 已对所有终态停止查询，旧 `SessionMergePanel.tsx:212` 对任何 completed 都展示可点击按钮；旧服务 Operation 根本没有 targetAvailable。删除目标后从旧完成面板点入失败的后果在基线已有。v2 新增首次校验、重开校验和按钮禁用，在这点上有改善，不能只因新字段可能陈旧便断言新增回归。按父任务确认，改记 retained，不进入新增缺陷集合；不因报告指出真实限制而计 FP。

与 `terminal-cache-blocks-active`/`terminal-pending-hides-active` 不能简单用同一行号强行去重：即使删除第 141—142 行 cache 短路，只要 pending 还在，第 149—152 行仍只 GET 旧操作而不会查 active，因此跨页面新 active 发现问题依然存在。这里的限制是旧目标可用性不刷新；另一个是新操作发现被旧 pending 支配。当前项因基线范围排除，不再增加功能计分键。

## 6. duplicate toast：旧 sink，新正式循环，属于新增可达

对应 R05-C27（196 行）、R12-C12（187 行）、R14-C24（204 行）、R16-C10（211 行）。统一键为 `duplicate-merge-toast`，旧台账键 `merge-notification-key-dedup` 应合并到此键。

`notificationStore.ts:32-41` 的确在基线和当前完全一致，只 push 不去重。仅看这份文件会误判为 retained。应比较调用可达性：

- 基线没有 paused/resume；store 第 129 行一旦观察到 terminal 就停止后续 GET，因此一个正常操作只有一次 preparing→terminal 通知（第 172—175 行）。
- 当前 store 第 200—203 行对每一次 changed 且非 preparing DTO 都 push 同一个 `merge-${operationId}`。
- 正式 resume 接口第 216、226—247 行可以把同一 operation 从 paused 推回 preparing；随后再次 paused 的查询又满足 changed，首条通知尚未消失时便存在两个相同 key。
- 即使不操作恢复，也存在更窄的合法 DTO 变化：service 第 226 行先持久化 paused，writer 第 232 行才移除；GET 第 267 行把 canResume 定义为 paused 且无 writer。所以在该窗口内分别观察到 paused/canResume=false 和 paused/canResume=true，也会两次 push 同 key。未测量该窗口发生频率，不以该窗口证明高频。

用户后果限于同一事件重复展示；`ToastContainer.tsx:39-41` 还用 key 作为 React 列表 key，开发环境可产生重复 key 警告；第 20 行和 notificationStore 第 43—44 行按 key 删除，两个通知会一起消失。没有证明资料损坏或普通执行链路失效，建议 minor。相同 DTO 确实会被 changed 门抑制，不能断言每次轮询都叠通知。

### 新增的观察性测试源码

`reproductions/rankingProtocolRepro.test.ts` 保留原 2 个用例，追加 3 个；均动态导入真实 sessionMergeStore / notificationStore，仅 mock fetch，并使用虚构 A/B/E/operation：

1. `adds two same-key notifications through the supported pause resume pause cycle`：真实调用 refresh→resume→refresh，验证三条请求、expectedEpoch、两条相同 key 和相同暂停文案；固定 Date.now，表示两次观察处于默认 5 秒展示期内。
2. `also repeats a paused notification when only worker cleanup makes resume available`：两个 paused DTO 只改变 canResume，得到两条通知；第三个完全相同 DTO 不再增加，明确反例边界。
3. `retains terminal target availability until reopening the dialog invalidates the cache`：第二次刷新不发 GET，保留旧 true；openDialog 后再刷新才取得 false。

这些是现状观察测试，PASS 表示观察到问题，并不表示业务已修复。store 测试没有挂载 ToastContainer，所以自动移除计时器不运行；其证明的是能在相邻响应中到达两条同 key 的通知状态，展示由真实组件静态路径佐证。本轮作者未执行测试，统一复跑结果以父任务生成的测试证据为准。复制到隔离目录的 `frontend/src/store/rankingProtocolRepro.test.ts` 后，用该项目现有 Vitest 配置执行同名测试文件即可。
