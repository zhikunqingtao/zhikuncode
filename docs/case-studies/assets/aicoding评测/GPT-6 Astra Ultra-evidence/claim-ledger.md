# 全量核验台账

原始三组逐条阅读记录保留；本文件由统一裁定生成。成立不等于入分：测试建议/整体判断等按规则排除。原文摘录内可能含原报告本机路径，仅作引文，点击定位使用每条上方仓库相对链接。

## R01

[完整原报告](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md)

### R01-normal_function

**判断分：5/5**。[原文 L95–L108](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L95)。结论限定为未发现普通主链路新增回归，与静态门控一致。

````text
**非合并路径评估**

| 链路 | 本次证据 | 判断 |
| --- | --- | --- |
| 普通 REST / SSE / WebSocket 对话 | configure 仅在既有 metadata 含合并标记时调用；QueryEngine 无运行标记返回空投影 | 未发现新增查包、查合并表或工具注入进入普通推理请求 |
| 普通请求预算与图片处理 | 无投影时预留为 0，使用原图片重载；新增 QueryEngine 单测断言预算差为 0、交接服务零交互 | 直接回归风险较低，现有单测通过 |
| 普通/fork/Swarm 持久化 | SessionManager、SessionMessagePersistence、SubAgentExecutor、SwarmWorkerRunner 与 HEAD 字节一致 | 未引入额外落库、归档或新的保存失败条件 |
| 工具池与执行 gate | ToolRegistry、SessionExecutionGate 与 HEAD 一致；HandoffRead 不是全局 Spring Tool Bean | 普通及子代理默认工具不增加交接工具 |
| 授权 | 内置实例身份匹配、可信根会话绑定、分析/执行前复核；同名 MCP/动态工具单测覆盖 | 未发现泛化只读放行或普通文件权限变宽 |
| 来源与其他会话 | snapshot 封存后释放 token；单元进度和模型调用在来源锁释放后进行 | 没有发现来源长期被摘要阶段锁住的回归 |
| 普通删除与数据绑定 | 既有删除不新增查库/目录清理，binding JOIN sessions；清包在合并生命周期 | 未发现普通删除链路被附加大文件 I/O |
| 前端恢复与锁可见性 | 存在发现 4 的旧终态缓存问题；后台 active 发现新增周期性请求 | UI 恢复仍有缺口；不能宣称所有非合并页面完全无负面影响 |

因此，证据支持“普通执行主链路保持隔离、现有回归通过”，不支持“任何非合并场景绝对零风险”。新迁移和 shared SQLite、磁盘仍是共同运行资源，尚未进行大量历史下的吞吐/磁盘压力验证。
````

### R01-boundary

**判断分：5/5**。[原文 L106–L108](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L106)。明确后台active请求与共享SQLite/磁盘、迁移及压力验证边界，拒绝绝对零风险。

````text
| 前端恢复与锁可见性 | 存在发现 4 的旧终态缓存问题；后台 active 发现新增周期性请求 | UI 恢复仍有缺口；不能宣称所有非合并页面完全无负面影响 |

因此，证据支持“普通执行主链路保持隔离、现有回归通过”，不支持“任何非合并场景绝对零风险”。新迁移和 shared SQLite、磁盘仍是共同运行资源，尚未进行大量历史下的吞吐/磁盘压力验证。
````

### R01-release

**判断分：5/5**。[原文 L149–L151](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L149)。允许Draft/开发分支，明确不作为已完成验收正式版本。

````text
**提交建议**

先修复发现 1、2 的事实/数据处理边界，再修复发现 3～5；将必须验收用例落到在线路径，最后重新运行当前绿色测试和复现用例。真实语义验收完成前，发布说明应明确“自动化结构回归已通过，真实模型接续开发未验收”。若先推送 GitHub 供审阅，应以 Draft PR/开发分支形式列出未关闭事项；本次审查不建议直接合入并宣称达到架构要求的正式发布标准。
````

### R01-semantic

**判断分：5/5**。[原文 L132–L133](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L132)。明确真实模型未执行且不以stub替代。

````text
| 真实模型接续开发 | 未执行 | 不以 stub 测试替代；未主动调用付费模型 |
| 浏览器 E2E、Linux/Docker、量化行/分支覆盖率 | 本次未执行 | 不把本机编译与单元测试称为完整发布环境验证 |
````

### R01-C01

**恢复投影绕过归档字段过滤** — 成立 / functional / scope=new / 入分；统一问题：D01；credit=1。

[原文 L32–L42](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L32)

````text
**发现 1 — P1：恢复投影绕过归档字段过滤，把思考/供应商私有字段作为提取材料。**

位置：[MergePackageService.java:264](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java:264)、[MergePackageService.java:270](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java:270)；对照正常路径 [MergePackageService.java:514](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java:514)。违反规范第 4.4 节“thinking、私有续传字段只归档”和第 6 节输入控制原则。

当记录因物化大小上限被标记 blocked，之后提高配置并恢复时，`recoverBlockedProjections` 直接把完整 `content_json`/`messages_json` 的 JsonNode 拼入文字，没有复用正常 `render` 的过滤。`MergeSummaryService.prepare` 随后处理恢复目录登记的所有 text 文件，因此这些字段会进入模型输入。图片的 base64 等内容同样可能以 JSON 文本进入恢复提取。

复现：构造包含可见文本、`thinking` 和 `provider_response_state` 的消息，降低物化阈值使其封存暂停，然后提高阈值并恢复；生成的提取投影实际含 `ARCHIVE_ONLY_REASONING` 和 `PRIVATE_CONTINUATION_STATE`，排除这些内容的断言失败。这是合成夹具，不是访问真实私有数据。

影响：恢复后的数据分类与首次提取不同；思考可能被总结成已执行事实，供应商私有内容进入整理调用，也可能造成不必要的大量模型输入。影响合并/恢复路径，未发现其修改普通会话状态。

建议：在现有 MergePackageService 内复用同一套可见内容投影规则；恢复仅读 sealed raw，保留角色、工具结果和必要元数据，过滤归档字段。无法安全生成等价投影时继续暂停，不以整个 JSON 字符串替代。增加消息与 checkpoint 两种恢复投影等价测试。
````

触发：物化上限导致封存blocked，调高上限后恢复
预期：只提取正常可见内容
实际：恢复文字含思考、供应商私有状态及可能的图片base64

判定理由：recoverBlockedProjections 直接串化完整 content/messages；正常 render 跳过 thinking/provider_response_state。恢复投影进入 projectionCatalogs 后被 prepare 作为 text 提取。

核实范围：恢复投影将thinking/供应商私有字段送入提取；与首次过滤不一致。

代码：[MergePackageService.java:264](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L264)；[MergePackageService.java:270](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L270)；[MergePackageService.java:514](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L514)；[MergeSummaryService.java:72](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L72)

核验：[reproductions/MergeReviewReproTest.java](reproductions/MergeReviewReproTest.java)

### R01-C02

**旧工具结果版本折叠且未进入投影** — 成立 / functional / scope=new / 入分；统一问题：D02；credit=1。

[原文 L44–L57](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L44)

````text
**发现 2 — P1：旧格式工具结果未参与消息版本判定，并且没有进入可提取、可搜索的文字。**

位置：[MergePackageService.java:558](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java:558)、[MergePackageService.java:337](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java:337)。违反规范第 4.1、4.2、4.4 节：已保存工具结果应正常合并；同身份不同内容版本必须保留。

checkpoint 的 `Message.UserMessage` 允许把旧工具结果保存在顶层 `toolUseResult`，通过 `sourceToolAssistantUUID` 关联。导出只传递 `content`、`meta` 和 stopReason；两项旧字段没有进入版本哈希和投影。两个同来源、同 UUID、相同 content 但不同 toolUseResult 的消息因此被视为相同版本。

复现包含两个独立断言：

1. 同一 UUID 的结果依次为 `TEST_FAILED_V1`、`TEST_PASSED_V2`，消息级记录预期 2 条，实际 1 条。
2. 唯一失败证据放在 `toolUseResult`，全部 text 投影均找不到该证据。

完整 checkpoint raw 容器仍保存，因此不能称为原件彻底丢失；但结构化整理、字面搜索和消息级版本索引都不能正确呈现这些事实。旧 helper 对顶层结果的文字展示也有兼容缺口；本次 v2 的新版本去重进一步引入不同工具结果被折叠的问题。

建议：在合并侧把旧结果及关联 ID 归一为明确工具证据，纳入语义版本和投影；同时存在原生 tool_result 与旧镜像时去重，不重复计算，也不能静默覆盖冲突。保留原始 JSON，不改普通持久化机制。补齐仅旧字段、双格式镜像、相同 UUID 结果更新三类测试。
````

触发：相同来源/UUID/content而顶层toolUseResult不同
预期：保留不同工具结果版本并可提取/搜索
实际：消息级去重折叠，text/search缺失旧结果

判定理由：exportCheckpoints 只传 content/meta/stop；hash和render不包含顶层旧结果。完整checkpoint raw仍在，报告已正确限定并非原件全部丢失。

核实范围：顶层toolUseResult未入版本哈希/文字索引；原checkpoint容器仍保留，不能称原件彻底丢失。

代码：[MergePackageService.java:337](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L337)；[MergePackageService.java:371](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L371)；[MergePackageService.java:549](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L549)；[MergePackageService.java:560](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L560)；[HandoffReadService.java:246](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L246)

核验：[reproductions/MergeReviewReproTest.java](reproductions/MergeReviewReproTest.java)

### R01-C03

**仅图片含关键需求仍可发布** — 规范歧义 / functional / scope=new / 不入分；统一问题：critical-uninterpreted-attachment；credit=None。

[原文 L59–L69](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L59)

````text
**发现 3 — P2：关键输入只在图片/未解析附件中时，缺少发布阻断。**

位置：[MergePackageService.java:523](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java:523)、[MergePackageService.java:599](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java:599)；发布路径 [SessionMergeService.java:195](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java:195)。违反规范第 4.4 节关键需求无法理解时暂停，以及第 8 节发布前不得存在阻断缺口的要求。

图片只被复制并写入“尚未解释其视觉内容”占位文字；非文字扩展名统一产生 `blocking=false` 的 attachment_uninterpreted 缺口。整理模型调用只接收文字，没有读取视觉内容的步骤，也没有关键附件判定分支。

复现：合成来源明确声明“所有实现要求仅在附图中，请按图实现”，附一张测试图片，provider 为只接收文本的 stub。没有发生任何视觉提取，操作仍进入 completed。该测试证明发布门槛缺失，不证明真实模型一定会误解某张图。

图片原件保留、E 之后可用 asset 工具读取，是当前实现的正向能力；但这与规范要求的“关键资料无法理解时明确暂停”并不等价。真实附件若另有已保存解析文字，则应正常提取，不属于本问题。

建议：复用已有图片/附件读取能力，对无法可靠处理的关键输入通过现有 gaps/paused 机制明确说明，允许用户补充文字后取消重建；不新建 OCR 或通用解析平台。补关键图片、非关键附件、已有解析文字及不支持附件的差异化测试。
````

触发：唯一要求在图片且来源声明按图实施
预期：关键资料无法由现有能力理解时暂停
实际：图片原件保留并标未知，未做视觉提取仍发布；是否违反门槛需澄清规范

判定理由：代码确实只复制图片并以未知占位，stub仍可completed；规范183要求无法理解的关键附件暂停，但正常PNG可在E经asset读取，未先视觉提取不自动等于现有能力无法理解。测试证明未做视觉整理，不足证明所示支持PNG必须暂停。

代码：[MergePackageService.java:523](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L523)；[MergePackageService.java:599](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L599)；[MergeSummaryService.java:326](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L326)；[session-merge-architecture-v2.md:183](../../../../session-merge-architecture-v2.md#L183)

核验：代码静态核验及原文：见code_refs/lines

### R01-C04

**旧终态缓存阻止active发现** — 成立 / functional / scope=new / 入分；统一问题：D07；credit=1。

[原文 L71–L81](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L71)

````text
**发现 4 — P2：本地旧终态记录会阻止发现服务端当前未结束操作。**

位置：[sessionMergeStore.ts:140](/Users/guoqingtao/Desktop/dev/code/zhikuncode/frontend/src/store/sessionMergeStore.ts:140)、[sessionMergeStore.ts:149](/Users/guoqingtao/Desktop/dev/code/zhikuncode/frontend/src/store/sessionMergeStore.ts:149)。违反规范第 5.3、8 节服务端 active 为权威、页面恢复应发现未结束操作的要求。

`/active` 只在 pending 为空时调用。恢复旧 completed/cancelled 记录后，代码只查询旧 ID；该终态通过验证后，后续 refresh 被 `validatedTerminal` 直接短路。另一浏览器后来发起的新合并不会被发现。同一浏览器跨 tab 的存储监听在已有 current 时也不会主动采用不同 key 的新记录。

复现：localStorage 含旧 completed，服务端当前另有 paused；两次 refresh 后只有一次 `/api/session-merges/old` 请求，完全没有 `/active` 请求，新操作没有进入 store。

影响：用户无法自动看到正在占名额的任务、暂停原因和恢复/取消入口；来源锁展示也可能停留在旧结果。服务端 gate 仍保护实际来源，未证明会导致并发写入破坏。手动关闭旧结果后可以恢复发现，属于可绕过的前端恢复缺陷。

建议：把“上次结果展示”与“当前 active 发现”分离；页面恢复、重新获得焦点时独立核对 active，并维持现有节流、串行控制和不抢焦点行为。补旧 completed/failed/cancelled 缓存与另一浏览器新任务的组合测试。
````

触发：本地旧completed/cancelled，另一页面产生新active
预期：恢复页面发现服务端当前active
实际：一直复用旧终态而不查active

判定理由：refresh有本地operation时仅查旧ID；validatedTerminal短路后不再active发现。清除旧结果可绕过，gate仍保护数据。

核实范围：本地旧终态遮蔽服务端新active；关闭旧结果可绕过。

代码：[sessionMergeStore.ts:140](../../../../../frontend/src/store/sessionMergeStore.ts#L140)；[sessionMergeStore.ts:149](../../../../../frontend/src/store/sessionMergeStore.ts#L149)；[sessionMergeStore.ts:189](../../../../../frontend/src/store/sessionMergeStore.ts#L189)；[sessionMergeStore.ts:268](../../../../../frontend/src/store/sessionMergeStore.ts#L268)

核验：[reproductions/sessionMergeReviewRepro.test.ts](reproductions/sessionMergeReviewRepro.test.ts)

### R01-C05

**已复制路径仍计为外部缺口** — 成立 / functional / scope=new / 入分；统一问题：D14；credit=1。

[原文 L83–L93](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L83)

````text
**发现 5 — P2：同一托管文件既成功复制，又被作为外部缺口计数。**

位置：[MergePackageService.java:449](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java:449)、[MergePackageService.java:575](/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java:575)。涉及规范第 4.2、4.4、8 节资料归属及可解释缺口展示。

`collectReferences` 先为消息中的路径添加 external_reference，之后 copyTree/copyFile 已成功复制该托管文件；v2 导出未像旧 build helper 那样消除同一路径的重复外部状态。`exportAsset` 把 external_reference 写入 gaps 并递增 warningCount。

复现：A 的 scratch 下有一个 plan.md，消息引用其绝对路径；结果 copiedCount=1，同时 warningCount=1，gaps 仍将它标为外部引用。预期无缺口的断言失败。

影响：UI 将 warningCount 描述为“未收录 N 个文件”，因此已复制文件被误报；checkpoint 历史说明等非文件缺口也会混入该计数。当前完成 DTO 只返回总数，没有给现有 warnings 列表填充明细，用户更难判断真实缺失范围。

建议：按来源和规范化路径保留最终归属状态，成功复制后去除同路径临时 external_reference；区分“未复制文件数”和“历史说明/未解析项数”，提供有界缺口明细或清晰入口。无需更改普通文件处理机制。
````

触发：消息引用托管目录文件且该文件复制成功
预期：不把已复制文件展示为未收录
实际：copied和external_reference并存，UI未收录数偏大

判定理由：collectReferences先添加external_reference；copyTree随后添加copied；exportAsset逐条导出未消重，非copied转gap，warningCount也包含历史说明。

核实范围：已复制托管文件还被计外部缺口；资料本体存在。

代码：[MergePackageService.java:935](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L935)；[MergePackageService.java:453](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L453)；[MergePackageService.java:575](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L575)；[sessionMergeStore.ts:202](../../../../../frontend/src/store/sessionMergeStore.ts#L202)

核验：[reproductions/MergeReviewReproTest.java](reproductions/MergeReviewReproTest.java)

### R01-C06

**旧helper测试不足以保障在线seal** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-helper-tests；credit=None。

[原文 L116–L116](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L116)

````text
- MergePackageService 已 1,122 行，其中旧 build 约 400 行仅用于兼容夹具。大量磁盘/复制/Unicode 测试仍测试该旧入口，对当前 seal 在线入口不构成直接保障。应迁移必要断言至 seal，同时保留真正需要的 legacy import 兼容验证。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：旧build被规范保留为兼容夹具；Unicode/复制配额等多个测试仍调用build，在线服务走seal，需迁移有意义断言。

判定理由：旧build被规范保留为兼容夹具；Unicode/复制配额等多个测试仍调用build，在线服务走seal，需迁移有意义断言。

代码：[MergePackageServiceTest.java:198](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L198)；[MergePackageServiceTest.java:356](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L356)；[SessionMergeService.java:190](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L190)；[session-merge-architecture-v2.md:5](../../../../session-merge-architecture-v2.md#L5)

核验：代码静态核验及原文：见code_refs/lines

### R01-C07

**排版和依赖注入混用** — 成立 / engineering / scope=new / 不入分；统一问题：formatting-and-injection；credit=None。

[原文 L117–L117](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L117)

````text
- 新增生产代码大量压缩为单行语句，导入用全限定类名、字段注入与手动 new Repository 混用，使事务和取消路径难读。应在现有服务内整理命名、排版和依赖注入，不增加文档禁止的新子系统。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：控制器新增字段注入；Service手工new Repository并存在注解bean。可维护性建议，没有证成产品错误。

判定理由：控制器新增字段注入；Service手工new Repository并存在注解bean。可维护性建议，没有证成产品错误。

代码：[SessionMergeService.java:72](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L72)；[HandoffContextService.java:31](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L31)

核验：代码静态核验及原文：见code_refs/lines

### R01-C08

**逐单元全表与逐ref重扫性能风险** — 证据不足 / conditional / scope=new / 不入分；统一问题：quadratic-planning-scans；credit=None。

[原文 L118–L118](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L118)

````text
- `processStage` 每次取全阶段 units 再过滤下一个单元；`source` 为每个 ref 从头扫描投影清单。长历史可能形成二次增长的扫描/SQL物化开销。本次没有基准数据，列为性能风险，不声称已造成普通聊天卡顿。可用既有表的 keyset/下一未完成单元查询优化，不引入向量索引或新检索平台。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：processStage每次读全部stage；source从清单头查找，增长风险真实，尚无负载结果，不能据此认定已致聊天卡顿。

判定理由：processStage每次读全部stage；source从清单头查找，增长风险真实，尚无负载结果，不能据此认定已致聊天卡顿。

代码：[MergeSummaryService.java:185](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L185)；[MergeSummaryService.java:208](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L208)

核验：代码静态核验及原文：见code_refs/lines

### R01-C09

**split图完整性缺显式验证** — 成立 / engineering / scope=new / 不入分；统一问题：split-graph-validation；credit=None。

[原文 L119–L119](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L119)

````text
- 发布验证跳过 split 父节点，没有显式逐项验证 childUnitIds 的存在/最终完成；目前 split 事务提供基础一致性，但规范要求的恢复/发布图完整性防护及故障注入测试仍应补齐。未将这一点列为已复现的线上故障。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：最终验证跳过split父项，未遍历childUnitIds；正常split事务同建父子，未证明受支持执行中会产生孤儿。

判定理由：最终验证跳过split父项，未遍历childUnitIds；正常split事务同建父子，未证明受支持执行中会产生孤儿。

代码：[MergeSummaryService.java:120](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L120)；[MergeProgressRepository.java:210](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L210)

核验：代码静态核验及原文：见code_refs/lines

### R01-C10

**固定工具说明缺部分语义指引** — 成立 / engineering / scope=new / 不入分；统一问题：handoff-tool-guidance；credit=None。

[原文 L120–L120](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L120)

````text
- HandoffRead 工具固定说明没有完全承载文档要求的“跨前后端读取双方约定、历史验证不代表当前验证”等语义引导；入口已有相关提示，仍应统一到固定工具说明，并由真实样本检验行为。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：Tool.getDescription未包含跨前后端和当前代码/历史测试边界全部要求；参考入口有部分指引。

判定理由：Tool.getDescription未包含跨前后端和当前代码/历史测试边界全部要求；参考入口有部分指引。

代码：[HandoffReadTool.java:16](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L16)；[HandoffContextService.java:68](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L68)；[session-merge-architecture-v2.md:373](../../../../session-merge-architecture-v2.md#L373)

核验：代码静态核验及原文：见code_refs/lines

### R01-C11

**将复现补成正式回归** — 成立 / engineering / scope=new / 不入分；统一问题：reproduction-regressions；credit=None。

[原文 L141–L141](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L141)

````text
1. 把本报告 6 个失败断言修成正式回归测试，并补 checkpoint/消息的恢复一致性、旧结果镜像冲突及附件类型变体。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：当前正式测试未覆盖恢复归档字段过滤及顶层legacy结果版本；图片门槛部分须先裁定。

判定理由：当前正式测试未覆盖恢复归档字段过滤及顶层legacy结果版本；图片门槛部分须先裁定。

代码：[MergePackageServiceTest.java:35](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L35)；[MergePackageServiceTest.java:317](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L317)

核验：代码静态核验及原文：见code_refs/lines

### R01-C12

**准入有效测试删除未补** — 成立 / engineering / scope=new / 不入分；统一问题：admission-test-gaps；credit=None。

[原文 L142–L142](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L142)

````text
2. 保留并迁移原有仍有效的准入测试：第五来源 busy、等待审批/后台 writer、同键不同参数、非法来源集合和部分 token 获取失败后的清理。新版 SessionMergeServiceTest 删除了一批旧测试，不能把所有删除都视为合理更新。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：现有ServiceTest仅根/child忙，未覆盖第五来源/审批/后台及原来完整非法参数集合。

判定理由：现有ServiceTest仅根/child忙，未覆盖第五来源/审批/后台及原来完整非法参数集合。

代码：[SessionMergeServiceTest.java:146](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L146)；[SessionMergeService.java:111](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L111)

核验：代码静态核验及原文：见code_refs/lines

### R01-C13

**事务回滚测试实际在事务前失败** — 成立 / engineering / scope=new / 不入分；统一问题：publication-rollback-test-gap；credit=None。

[原文 L143–L143](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L143)

````text
3. 真正的事务中途失败测试：当前 `failedPublicationRollsBackTargetAndPreservesPreparedFiles` 通过工作区校验抛错，发生在 tx.execute 之前，不能证明“createSessionRecord 后 addMessage/complete 失败”的事务回滚。应覆盖这两个真实事务内部故障点，以及取消与提交竞态。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：failedPublication测试让requireCurrentBinding第二次抛错，对应Service202早于207 tx，不是createSessionRecord后故障回滚。

判定理由：failedPublication测试让requireCurrentBinding第二次抛错，对应Service202早于207 tx，不是createSessionRecord后故障回滚。

代码：[SessionMergeServiceTest.java:152](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L152)；[SessionMergeService.java:202](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L202)；[SessionMergeService.java:207](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L207)

核验：代码静态核验及原文：见code_refs/lines

### R01-C14

**真实QueryEngine压缩413小模型组合覆盖不足** — 成立 / engineering / scope=new / 不入分；统一问题：handoff-query-recovery-tests；credit=None。

[原文 L144–L144](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L144)

````text
4. 合并上下文在实际 QueryEngine 发生压缩、413 和换小模型之后的组合测试。现有新增 QueryEngine 用例只证明普通/合并单轮投影与预算差，HandoffContext 的预算夹具不能完全替代真实回退链路。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：新增投影测试证明单轮预算与无交互；未覆盖合并投影叠加真实压缩/413完整组合。

判定理由：新增投影测试证明单轮预算与无交互；未覆盖合并投影叠加真实压缩/413完整组合。

代码：[QueryEngine.java:1835](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1835)；[QueryEngineUnitTest.java:1950](../../../../../backend/src/test/java/com/aicodeassistant/engine/QueryEngineUnitTest.java#L1950)

核验：代码静态核验及原文：见code_refs/lines

### R01-C15

**E状态不回写A/B缺直接端到端断言** — 成立 / engineering / scope=new / 不入分；统一问题：session-state-isolation-tests；credit=None。

[原文 L145–L145](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L145)

````text
5. E 的待办、设置、压缩、文件读取缓存和删除对 A/B 不回写的直接端到端断言；现有共享 session ID 隔离是合理基础，但不等于文档全部新验收已自动覆盖。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：独立session ID提供基础隔离，但现有merge测试未直测TodoWrite/FileStateCache与设置变更对来源零回写。

判定理由：独立session ID提供基础隔离，但现有merge测试未直测TodoWrite/FileStateCache与设置变更对来源零回写。

代码：[SessionMergeService.java:209](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L209)；[SessionMergeServiceTest.java:69](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L69)；[session-merge-architecture-v2.md:417](../../../../session-merge-architecture-v2.md#L417)

核验：代码静态核验及原文：见code_refs/lines

### R01-C16

**Panel新恢复取消等交互缺测试** — 成立 / engineering / scope=new / 不入分；统一问题：panel-v2-test-gaps；credit=None。

[原文 L146–L146](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L146)

````text
6. panel 层新增恢复/取消/换模型/目标不可用操作测试；现有 panel 修改基本为 fixture/文案适配，新交互主要只有 store 测试。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：panel测试主要fixture/文案更新，恢复取消及targetAvailable交互主要由store测试承担。

判定理由：panel测试主要fixture/文案更新，恢复取消及targetAvailable交互主要由store测试承担。

代码：[SessionMergePanel.tsx:217](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L217)；[SessionMergePanel.test.tsx:45](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L45)

核验：代码静态核验及原文：见code_refs/lines

### R01-C17

**真实模型接续样本未完成** — 成立 / engineering / scope=new / 不入分；统一问题：semantic-acceptance-missing；credit=None。

[原文 L147–L147](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L147)

````text
7. 按第 9 节运行真实模型语义样本：服务端 name→displayName、前端仍读 name；E 查阅双方证据、读取当前代码、修复联调并复测；后续轮次不重复旧待办。再覆盖封存后代码变化、目录兜底、删除来源后读取、未验证自述及冲突双方。记录授权、模型、费用及样本边界。这一项尚未获得真实模型结果，不能宣称已完成。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：文档3/9明确未执行，报告未把stub当语义验收。

判定理由：文档3/9明确未执行，报告未把stub当语义验收。

代码：[session-merge-architecture-v2.md:3](../../../../session-merge-architecture-v2.md#L3)；[session-merge-architecture-v2.md:453](../../../../session-merge-architecture-v2.md#L453)

核验：代码静态核验及原文：见code_refs/lines

### R01-C18

**普通链路的具体隔离保证** — 成立 / global / scope=new / 不入分；统一问题：ordinary-path-isolation；credit=None。

[原文 L99–L105](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L99)

````text
| 普通 REST / SSE / WebSocket 对话 | configure 仅在既有 metadata 含合并标记时调用；QueryEngine 无运行标记返回空投影 | 未发现新增查包、查合并表或工具注入进入普通推理请求 |
| 普通请求预算与图片处理 | 无投影时预留为 0，使用原图片重载；新增 QueryEngine 单测断言预算差为 0、交接服务零交互 | 直接回归风险较低，现有单测通过 |
| 普通/fork/Swarm 持久化 | SessionManager、SessionMessagePersistence、SubAgentExecutor、SwarmWorkerRunner 与 HEAD 字节一致 | 未引入额外落库、归档或新的保存失败条件 |
| 工具池与执行 gate | ToolRegistry、SessionExecutionGate 与 HEAD 一致；HandoffRead 不是全局 Spring Tool Bean | 普通及子代理默认工具不增加交接工具 |
| 授权 | 内置实例身份匹配、可信根会话绑定、分析/执行前复核；同名 MCP/动态工具单测覆盖 | 未发现泛化只读放行或普通文件权限变宽 |
| 来源与其他会话 | snapshot 封存后释放 token；单元进度和模型调用在来源锁释放后进行 | 没有发现来源长期被摘要阶段锁住的回归 |
| 普通删除与数据绑定 | 既有删除不新增查库/目录清理，binding JOIN sessions；清包在合并生命周期 | 未发现普通删除链路被附加大文件 I/O |
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：无标记请求早退；普通保存与子任务文件相对b98基线未动；内置实例身份判定与绑定检查收窄工具权限。此保证限普通推理，不等同复制所有安全路径。

判定理由：无标记请求早退；普通保存与子任务文件相对b98基线未动；内置实例身份判定与绑定检查收窄工具权限。此保证限普通推理，不等同复制所有安全路径。

代码：[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)；[HandoffContextService.java:39](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L39)；[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)

核验：代码静态核验及原文：见code_refs/lines

### R01-C19

**只读工具ref哈希及symlink保证** — 成立 / global / scope=new / 不入分；统一问题：read-side-resource-boundary；credit=None。

[原文 L112–L112](../Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md#L112)

````text
已实现的关键点包括：V026 保持旧迁移不变并重建 CHECK；数据库唯一 active_slot；运行 epoch 抵御旧 worker；独立 snapshot 与原文哈希；串行提取及单元落库；去掉 16 次总调用和旧 3,500 拒绝规则；分层摘要保留详情；取消后等待 writer 再清包；发布与目标创建处于同一事务；通过 target 存在性撤销绑定；只读工具按 ref 读取、限量、游标、哈希和 symlink 检查。实现职责大体仍在文档指定的服务中，没有扩展成额外工作流平台。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：此句限定读取工具，HandoffRead与safeFile确有逐层符号链接拒绝；不能扩张为复制源侧也安全。

判定理由：此句限定读取工具，HandoffRead与safeFile确有逐层符号链接拒绝；不能扩张为复制源侧也安全。

代码：[HandoffReadService.java:179](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L179)；[MergePackageService.java:304](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L304)

核验：代码静态核验及原文：见code_refs/lines

## R02

[完整原报告](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)

### R02-normal_function

**判断分：5/5**。[原文 L19–L19](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L19)。以未发现破坏表述，门控和普通保存隔离与代码一致。

````text
| 是否破坏非合并分支？ | **未发现破坏。** 静默隔离 + 全量回归 + 基线对照三重证据一致（详见第二节） |
````

### R02-boundary

**判断分：5/5**。[原文 L54–L57](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L54)。明确迁移和启动同步清理/续跑全局影响，未将普通分支隔离扩张为无共享影响。

````text
### 2.3 仍需注意的两个"非合并但全局"影响

1. **迁移对所有用户库生效**：`V026` 会 DROP 并重建 `session_merges`（`V026_ExtendSessionMerges.java` 第 13-98 行）。已核对旧校验器 `V024_CreateSessionMerges.validate()` 只校验列存在，因此**不会因 schema 变更导致启动校验失败**；`MigrationRunner` 在事务内执行并以 `protocol_version` 存在性做幂等，测试覆盖了"再启动校验通过 / 重复 target 回滚"。
2. **启动期副作用**：`SessionMergeService.@PostConstruct recover()`（第 74-80 行）会在应用启动时**同步扫描并删除**已取消/目标已删除的包目录，并对未完成的 v2 操作**自动续跑（含真实付费模型调用）**。这是规范 §5.2 的既定行为，但使"所有用户"的启动耗时与重启后费用受合并任务影响。建议 cleanup 加超时或异步化，并在文档中显式写明"重启会自动续跑并计费"。
````

### R02-release

**判断分：5/5**。[原文 L191–L195](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L191)。明确正确性问题未闭合，不宣称完成版达标。虽然措辞要求修完才提交更严，不误放完成版。

````text
## 九、总体评价

- 本次改动在**非合并链路上是安全的**：隔离设计正确（7 个接入点全部带开关、核心链路文件零改动），全量回归与干净基线对照均未发现新增破坏。
- 合并域本身的架构落地度较高：封存即释放、单一名额、epoch 栅栏、原子发布、可恢复单元、只读交接工具与按轮有界入口都已按规范实现，测试质量明显优于 v1。
- 但仍存在 2 个正确性缺陷与 1 个会随包体积放大的性能缺陷，加上若干关键路径零覆盖与文档口径不准，**建议按第八节 P1/P2 修复后再提交 GitHub**，不要在修复前宣称会话合并 v2 已达到其自身文档定义的验收标准。
````

### R02-semantic

**判断分：5/5**。[原文 L167–L167](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L167)。明确stub不能替代真实模型语义验收。

````text
2. **文档缺失内容**：规范第 9 节要求"必须包含一个接续开发样本"（A 改 `name`→`displayName`、B 未联调）与真实模型语义验收，文档第 3 / 471 行自认**尚未执行**。按该文档自身的验收标准，本改动目前**只完成了 stub 层验证**，语义验收仍是发布前的未完成项——必须在 PR 描述里明确，不能以"自动化全绿"代替。
````

### R02-C01

**引用文件中间symlink穿透复制边界** — 成立 / functional / scope=new / 入分；统一问题：D03；credit=1。

[原文 L73–L88](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L73)

````text
### P1-1 封存期引用文件复制存在 symlink 穿透（违反规范第 156 行）

`MergePackageService` 第 458-462 行：

```java
if ("external_reference".equals(asset.status()) && asset.originalPath().startsWith("/")) {
    Path path = Path.of(asset.originalPath()).toAbsolutePath().normalize();
    if (path.startsWith(own.toAbsolutePath().normalize()) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        copyFile(source, path, ...);
}
```

- ref 来源是消息正文里任意绝对路径字符串（`collectReferences` 第 937-941 行，含 agent 输出），只做**规范化**（`normalize`，不解析符号链接）。
- `NOFOLLOW_LINKS` 只作用于**最后一段**。若 scratchpad 内存在中间软链（如 `<own>/link -> /`），则 `<own>/link/etc/passwd` 既通过前缀校验、也不是目录，`copyFile` 会顺着中间软链把**包外文件**复制进快照，随后可被 E 的 `HandoffRead` 读取。
- 同文件的兄弟实现都做了正确校验（`copyTree` 第 956-964 行用 `toRealPath()` 比对；`safeFile` 第 304-318 行逐段拒绝软链），只有这一处没有 → 属实现疏漏而非有意设计；**无任何测试覆盖该分支**。
- 建议：改为 `Path canonical = path.toRealPath()` 后与 `own.toRealPath()` 比对，或直接复用 `safeFile` 的"逐段拒绝符号链接"逻辑。
````

触发：scratchpad已有中间软链且消息引用软链下包外文件
预期：拒绝复制范围外文件
实际：可复制包外文件进入归档供E读取

判定理由：历史绝对路径在own词法前缀下，中间symlink不会被末段NOFOLLOW阻止。已用Java21临时synthetic-proof验证API机制；完整合并复现由主评审集中负责。

核实范围：历史文件引用的中间目录symlink穿过允许的来源目录边界，复制合成外部文件。

代码：[MergePackageService.java:458](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L458)；[MergePackageService.java:975](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L975)；[MergePackageService.java:984](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L984)；[MergePackageService.java:935](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L935)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R02-C02

**阻断gap引用与恢复协议不一致** — 成立 / functional / scope=new / 入分；统一问题：D04；credit=1。

[原文 L90–L101](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L90)

````text
### P1-2 阻断型 gap 报 `paused` 但实际不可恢复（暂停/恢复死循环）

`recoverBlockedProjections` 只接受 `RECORD_REQUIRES_HANDLING:<recordRef>` 且该 recordRef 必须在 `records.jsonl` 里是 `blocked`（`MergePackageService` 第 232-236 行），否则抛错。但两处写入的 gap 键不满足该格式：

| 位置 | gap 键 | 后果 |
| --- | --- | --- |
| 第 436 行：消息内容解析失败 | 裸 `RECORD_REQUIRES_HANDLING` | 明明已 `record(..., "blocked")` 注册了 raw 记录，却因键没有 recordRef 而永远无法走恢复分支（第 406 行同一场景就带了 `":"+ref`，属不一致） |
| 第 531 行：内嵌图片 base64 非法 | `RECORD_REQUIRES_HANDLING:invalid_image` | `invalid_image` 不是 recordRef → 永远抛错 |

而 `SessionMergeService` 第 195-198 行对任何以 `RECORD_REQUIRES_HANDLING` 开头的 blockedReason 都调用 `recoverBlockedProjections`，失败后落 `paused`，`explain()` 还提示用户"检查原件后再恢复"。用户恢复 → 同点再失败，**唯一出路是 cancel（随后包被删除）**。这与规范第 210/211 行"paused = 可处理问题 / failed = 已确认不可恢复"的语义划分冲突。

建议：① 第 436 行改为携带 recordRef（利用 `record(...)` 返回值）；② `invalid_image` 要么也登记 blocked 记录，要么改为非阻断 gap 或直接判 `failed/RECORD_REQUIRES_HANDLING`，并在 `explain()` 中给出可行操作。
````

触发：解析失败产生裸gap或非法图片
预期：受支持可修复限制调整后恢复；真损坏材料不跳过
实际：这些gap反复恢复失败，是否存在正常可修复触发待集中验证

判定理由：裸reason/invalid_image确实不匹配blockedRecords；但已损坏base64不可通过调高资源上限修好，规范允许保留暂停。尚需证明可修复配置限制进入436且修复后仍被reason卡住，不能把一切损坏原件暂停都算严重。；统一复核：合成原件不变，启用Jackson现有ALLOW_SINGLE_QUOTES后已可解析，恢复仍在读raw前被缺ref挡住；提高物化上限的正控制能恢复。仅确认这类关联协议缺口，损坏资料允许暂停，不采用永久/所有恢复失效的泛化。

核实范围：已修正解析配置仍被gap/ref关联校验阻止；不等于所有坏原件应可恢复。

代码：[MergePackageService.java:235](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L235)；[MergePackageService.java:436](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L436)；[MergePackageService.java:531](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L531)；[session-merge-architecture-v2.md:179](../../../../session-merge-architecture-v2.md#L179)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R02-C03

**每轮反复校验清单性能** — 证据不足 / conditional / scope=new / 不入分；统一问题：repeated-handoff-integrity-io；credit=None。

[原文 L103–L107](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L103)

````text
### P1-3 每轮请求对整包做 2～4 次全量哈希校验（性能）

`HandoffContextService.project`（第 59 行）每次调用都会 `repository.binding(root)` + `reads.brief(root)` → `verify()` → 对 manifest、records/files/gaps/occurrences、details.jsonl、handoff.md、overview.json **全部重新哈希**（`HandoffReadService` 第 66-91 行；校验缓存仅限单次调用内）。而 `project` 在一次请求里会被调用 **2～4 次**：`QueryEngine` 第 841 行、第 844 行的 `prepareCompactionContext`（内部第 1835 行）、以及第 1933 / 1973 行的压缩与恢复路径。摘要包随来源规模增长（details.jsonl 可达数 MB），即"每轮对话重复哈希数 MB～数十 MB 并查库 2N 次"。

建议：在 `QueryLoopState` 或请求作用域内记忆绑定与 `ready.json` 校验结果（同一次请求内包不变，语义安全）。规范 §7.1 只要求**入口 token 有界**，并未要求每轮重做全包完整性校验。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：重复project会哈希清单并查库；不是每次哈希所有原文/附件。没有包规模/耗时基准，P1性能缺陷尚未实证。

判定理由：重复project会哈希清单并查库；不是每次哈希所有原文/附件。没有包规模/耗时基准，P1性能缺陷尚未实证。

代码：[HandoffContextService.java:65](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L65)；[HandoffReadService.java:69](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L69)；[QueryEngine.java:841](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L841)；[QueryEngine.java:1835](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1835)

核验：代码静态核验及原文：见code_refs/lines

### R02-C04

**内嵌图片复制配额丢失可操作错误分类** — 成立 / functional / scope=new / 入分；统一问题：D17；credit=1。

[原文 L111–L111](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L111)

````text
1. **图片复制配额异常绕过错误分类**：`MergePackageService` 第 528 行抛 `CopyLimitException`（`IOException` 子类），只被第 531 行的 `IllegalArgumentException` catch 覆盖，异常逃逸到 `SessionMergeService.safeCode`，中文消息 → 落成 `MERGE_PREPARATION_FAILED`，绕过了规范第 339/352 行要求的 `MERGE_COPY_INCOMPLETE` / `MERGE_DISK_SPACE_LOW` 可操作分类（`explain()` 也匹配不到）。
````

触发：内嵌图片触达配置复制配额/磁盘余量
预期：保留可操作复制/磁盘错误码
实际：暂停为泛化准备失败

判定理由：CopyBudget.check在render内抛中文CopyLimitException，catch只接IllegalArgumentException；safeCode只接受大写错误码，因此降为MERGE_PREPARATION_FAILED。普通文件copyFile会转MERGE_COPY_INCOMPLETE。

核实范围：内嵌图片复制配额报通用准备失败；普通文件相同配额有明确COPY_INCOMPLETE。

代码：[MergePackageService.java:528](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L528)；[MergePackageService.java:61](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L61)；[SessionMergeService.java:257](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L257)

核验：[reproductions/RankingReadReproTest.java](reproductions/RankingReadReproTest.java)

### R02-C05

**封存缺fsync** — 规范歧义 / conditional / scope=new / 不入分；统一问题：snapshot-fsync；credit=None。

[原文 L112–L112](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L112)

````text
2. **封存无 fsync**：`Files.move(staging, sealed, ATOMIC_MOVE)`（第 170-173 行）前未 `flush/fsync` 目录与文件，与规范第 166 行"关闭/刷新文件"不完全一致；掉电可能封存出撕裂快照（`validateSnapshot` 能检出，退化为再暂停，而非静默损坏）。建议补 fsync 或明确记录该取舍。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：closeCatalogs和write关闭流满足flush语言层面；未force/fsync存在掉电持久性风险，但规范未明确承诺崩溃以外断电耐久性，校验可拒损坏。

判定理由：closeCatalogs和write关闭流满足flush语言层面；未force/fsync存在掉电持久性风险，但规范未明确承诺崩溃以外断电耐久性，校验可拒损坏。

代码：[MergePackageService.java:159](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L159)；[MergePackageService.java:173](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L173)；[MergeTextBudget.java:34](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeTextBudget.java#L34)

核验：代码静态核验及原文：见code_refs/lines

### R02-C06

**来源原地变更检测弱** — 范围外 / conditional / scope=out / 不入分；统一问题：external-db-mutation-cutoff；credit=None。

[原文 L113–L113](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L113)

````text
3. **来源变更检测偏弱**：仅比较 `MAX(seq_num)` 与行数（第 439 行），原地修改一条历史消息不会被发现；并发删行会让 `queryForObject` 抛 `EmptyResultDataAccessException`（RuntimeException）而非 `SOURCE_CHANGED`。规范第 164 行要求"观测到变更必须暂停"。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：MAX/count不能检出外部同量修改，但规范164明确外部直接改库不支持；应用写入由gate阻止。分页读取缺失行本身136有SOURCE_CHANGED，不是所有删行都裸运行异常。

判定理由：MAX/count不能检出外部同量修改，但规范164明确外部直接改库不支持；应用写入由gate阻止。分页读取缺失行本身136有SOURCE_CHANGED，不是所有删行都裸运行异常。

代码：[MergePackageService.java:439](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L439)；[MergePackageService.java:136](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L136)；[session-merge-architecture-v2.md:164](../../../../session-merge-architecture-v2.md#L164)

核验：代码静态核验及原文：见code_refs/lines

### R02-C07

**pause静默失效留下preparing无worker** — 证据不足 / conditional / scope=new / 不入分；统一问题：pause-epoch-orphan-worker；credit=None。

[原文 L114–L114](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L114)

````text
4. **恢复路径的能力差距**：`resume` 会把 `failed` 单元重置为 `pending`（`MergeProgressRepository` 第 115 行），而 `pause` 不使用 `changed()` 校验（第 98-103 行）→ 若 pause 因 epoch 不符而静默失效，操作可能滞留在 `preparing` 且无 worker（需重启才被 `interrupted()` 捞回）。建议 pause 失败时补降级日志或兜底调度。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：epoch变化由resume/cancel且本服务synchronized/writers阻止同操作双worker。旧epoch pause为no-op是避免覆盖新状态；没有证明epoch已变但无新worker且仍preparing的支持控制流。

判定理由：epoch变化由resume/cancel且本服务synchronized/writers阻止同操作双worker。旧epoch pause为no-op是避免覆盖新状态；没有证明epoch已变但无新worker且仍preparing的支持控制流。；统一复核：缺乏epoch变化后仍preparing且无worker的可达路径；明确条件风险不罚FP。

代码：[MergeProgressRepository.java:98](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L98)；[SessionMergeService.java:136](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L136)；[SessionMergeService.java:146](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L146)；[SessionMergeService.java:132](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L132)

核验：代码静态核验及原文：见code_refs/lines

### R02-C08

**工具currentRunId缺空判** — 不成立 / engineering / scope=new / 不入分；统一问题：tool-null-run-guard；credit=None。

[原文 L115–L115](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L115)

````text
5. **`HandoffReadTool.call` 未防 `currentRunId == null`**（第 29 行），而 `HandoffContextService.canReadAsset` 做了空判，属同类不一致。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：AuthorizationSubjectResolver.resolve36-38与ToolExecutionGateway48已明确拒绝null run，工具只在可信持久Run执行；无新增NPE证据。

判定理由：AuthorizationSubjectResolver.resolve36-38与ToolExecutionGateway48已明确拒绝null run，工具只在可信持久Run执行；无新增NPE证据。

代码：[HandoffReadTool.java:29](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L29)；[AuthorizationSubjectResolver.java:36](../../../../../backend/src/main/java/com/aicodeassistant/authorization/AuthorizationSubjectResolver.java#L36)；[ToolExecutionGateway.java:48](../../../../../backend/src/main/java/com/aicodeassistant/authorization/ToolExecutionGateway.java#L48)

核验：代码静态核验及原文：见code_refs/lines

### R02-C09

**读预算错误码压平为授权错误** — 成立 / functional / scope=new / 入分；统一问题：D13；credit=1。

[原文 L116–L116](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L116)

````text
6. **错误码被压平**：`HandoffReadService.read` 把校验阶段的所有 `IOException`（含 `HANDOFF_READ_BUDGET_EXCEEDED`）统一转成 `AuthorizationException("HANDOFF_INVALID_RESOURCE")`（第 206-208 行），仅保留 message。规范 §7.2 要求超时"明确报 HANDOFF_READ_BUDGET_EXCEEDED"——现有测试只断言 message，掩盖了码值变化；且工具层最终呈现为 `permission denied`，可能误导模型以为"没权限"。另：`asset` 动作在 `read()` 的 else 分支会静默返回空列表（实际靠工具层拦截），属死分支。
````

触发：实际验证/读取超过15秒
预期：明确HANDOFF_READ_BUDGET_EXCEEDED
实际：授权层返回HANDOFF_INVALID_RESOURCE，信息仅藏message

判定理由：authorize捕获IOException转HANDOFF_INVALID_RESOURCE；read.verify亦转换，预算异常message保留但正式code丢失，与规范明确码要求不同。

核实范围：授权/读取支路把预算错误码包成INVALID_RESOURCE等泛码；read入口中断反例正常重抛。

代码：[HandoffReadService.java:55](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L55)；[HandoffReadService.java:208](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L208)；[HandoffReadService.java:43](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L43)；[session-merge-architecture-v2.md:387](../../../../session-merge-architecture-v2.md#L387)

核验：[reproductions/RankingReadReproTest.java](reproductions/RankingReadReproTest.java)

### R02-C10

**read直接asset返回空成功** — 成立 / engineering / scope=new / 不入分；统一问题：read-asset-internal-dead-branch；credit=None。

[原文 L116–L116](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L116)

````text
6. **错误码被压平**：`HandoffReadService.read` 把校验阶段的所有 `IOException`（含 `HANDOFF_READ_BUDGET_EXCEEDED`）统一转成 `AuthorizationException("HANDOFF_INVALID_RESOURCE")`（第 206-208 行），仅保留 message。规范 §7.2 要求超时"明确报 HANDOFF_READ_BUDGET_EXCEEDED"——现有测试只断言 message，掩盖了码值变化；且工具层最终呈现为 `permission denied`，可能误导模型以为"没权限"。另：`asset` 动作在 `read()` 的 else 分支会静默返回空列表（实际靠工具层拦截），属死分支。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：Service.read的else对asset没有处理；线上HandoffReadTool31先路由asset，外部工具调用不触发此死分支。

判定理由：Service.read的else对asset没有处理；线上HandoffReadTool31先路由asset，外部工具调用不触发此死分支。

代码：[HandoffReadService.java:238](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L238)；[HandoffReadTool.java:31](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L31)

核验：代码静态核验及原文：见code_refs/lines

### R02-C11

**小窗口入口容量未分类且声称HTTP500** — 成立 / functional / scope=new / 入分；统一问题：D09；credit=0.5。

[原文 L117–L117](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L117)

````text
7. **入口失败是 500 而非容量错误**：`project()` 抛 `IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL" / "HANDOFF_UNAVAILABLE")`，经 `GlobalExceptionHandler` 第 138 行变成不透明的 500；规范第 371 行要求"返回正常容量错误"。同理 `configure` 里的 `AuthorizationException(HANDOFF_NOT_BOUND)` 也是 500。建议加显式异常映射（容量类 4xx + 可读 code）。
````

触发：有效合并会话历史预算太小
预期：正常可识别容量错误
实际：产生通用查询错误；不能按报告认定REST500

判定理由：project抛IllegalStateException确实没有正常容量分类；但QueryEngine.execute外围catch将其包装QueryResult，REST通常200，报告HTTP500归因不成立。触发与请求失败方向正确，精确响应/控制流错误，拟部分命中待主评审复现统一裁定。

核实范围：E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)；[QueryEngine.java:407](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L407)

核验：[reproductions/RankingQueryReproTest.java](reproductions/RankingQueryReproTest.java)

### R02-C12

**configure无绑定可能通用500** — 规范歧义 / conditional / scope=new / 不入分；统一问题：handoff-missing-binding-error；credit=None。

[原文 L117–L117](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L117)

````text
7. **入口失败是 500 而非容量错误**：`project()` 抛 `IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL" / "HANDOFF_UNAVAILABLE")`，经 `GlobalExceptionHandler` 第 138 行变成不透明的 500；规范第 371 行要求"返回正常容量错误"。同理 `configure` 里的 `AuthorizationException(HANDOFF_NOT_BOUND)` 也是 500。建议加显式异常映射（容量类 4xx + 可读 code）。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：configure在engine前抛且全局异常兜底可能500；但绑定损坏必须failclosed，不能默默当普通会话继续。报告只要求显式映射有工程价值，正常支持流程未证明丢绑定。

判定理由：configure在engine前抛且全局异常兜底可能500；但绑定损坏必须failclosed，不能默默当普通会话继续。报告只要求显式映射有工程价值，正常支持流程未证明丢绑定。

代码：[HandoffContextService.java:41](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L41)；[HandoffReadService.java:47](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L47)

核验：代码静态核验及原文：见code_refs/lines

### R02-C13

**锁DTO仅列根来源** — 规范歧义 / engineering / scope=new / 不入分；统一问题：lock-dto-descendants；credit=None。

[原文 L118–L118](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L118)

````text
8. **`lockedSourceSessionIds` 只报根来源**（`SessionMergeService` 第 263 行），gate 实际同时锁子任务；虽然同批释放，但契约上不如规范"锁集合来自真实 gate"严谨。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：Service263只从request roots滤真实gate，子任务不返回；规范只说锁集合来自真实gate，未明确所有不可选子任务必须UI列出。根来源锁判定真实，未证用户错误动作。

判定理由：Service263只从request roots滤真实gate，子任务不返回；规范只说锁集合来自真实gate，未明确所有不可选子任务必须UI列出。根来源锁判定真实，未证用户错误动作。

代码：[SessionMergeService.java:263](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L263)；[SessionMergeService.java:114](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L114)；[session-merge-architecture-v2.md:421](../../../../session-merge-architecture-v2.md#L421)

核验：代码静态核验及原文：见code_refs/lines

### R02-C14

**结果4MiB读上限** — 不成立 / engineering / scope=new / 不入分；统一问题：detail-result-size-cap；credit=None。

[原文 L119–L119](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L119)

````text
9. **大结果上限需与规范对齐**：单个 unit 结果 >4 MiB 即整任务暂停（`MergeSummaryService` 第 178 行）；可见输出 256 KiB 上限使正常路径够用，但该上限与规范"超大详细结果保留原文件"的表述需要对齐说明。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：当前响应上限256KiB，经程序展开有界evidence，未给出正常生成结果超过4MiB的路径。异常/篡改大文件暂停不能证明违反保留详情。

判定理由：当前响应上限256KiB，经程序展开有界evidence，未给出正常生成结果超过4MiB的路径。异常/篡改大文件暂停不能证明违反保留详情。

代码：[MergeSummaryService.java:178](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L178)；[MergeSummaryService.java:311](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L311)

核验：代码静态核验及原文：见code_refs/lines

### R02-C15

**注入及密集排版** — 成立 / engineering / scope=new / 不入分；统一问题：formatting-and-injection；credit=None。

[原文 L125–L126](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L125)

````text
1. **注入风格不一致（会被 review 直接打回）**：`QueryController` 第 70-73 行与 `WebSocketController` 第 91-93 行新增了 **field injection**，而这两个文件在 HEAD 上 `@Autowired` 计数为 **0**（全构造器注入）；且使用行内全限定名 `com.aicodeassistant.engine.HandoffContextService`。同样问题在 `QueryEngine` 第 1809 行（此处 `required=false` 是有意的，但建议改为构造器可选注入并加注释）。建议统一为构造器注入 + 正常 import。
2. **同一文件内两种排版风格**：新增的合并类大量使用 `a=1;b=2;if(x) return;` 单行密集写法（如 `HandoffReadService`、`MergeProgressRepository`），与 `MergePackageService` 既有部分（4 空格、逐行、注释充分）风格割裂。仓库无 checkstyle/spotless，编译不会拦，但可读性与后续维护成本明显上升，建议至少对新增文件做一次格式化。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：属于代码风格和依赖组织建议，不是产品阻断错误。

判定理由：属于代码风格和依赖组织建议，不是产品阻断错误。

代码：[SessionMergeService.java:72](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L72)；[HandoffContextService.java:31](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L31)

核验：代码静态核验及原文：见code_refs/lines

### R02-C16

**多余core-tool名字登记削弱未来启动检查** — 成立 / engineering / scope=new / 不入分；统一问题：redundant-core-name-registration；credit=None。

[原文 L127–L127](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L127)

````text
3. **`isExplicitCoreTool("HandoffRead")` 是无效/误导性放行**（`OperationAnalyzerRegistry` 第 120 行）：该方法只服务 `ToolRegistry` 的启动自检，而 `HandoffReadTool` 并非 Bean；把它列为"显式核心工具"反而让未来一个同名 Spring Bean 逃过 `AUTHORIZATION_ANALYZER_MISSING` 自检。建议删除该字符串（真正生效的是 `instanceof` 分支）。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：确为启动自检白名单；不是当前权限提升。规范396同时要求core登记，删除不是唯一解释。

判定理由：确为启动自检白名单；不是当前权限提升。规范396同时要求core登记，删除不是唯一解释。

代码：[OperationAnalyzerRegistry.java:120](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L120)；[ToolRegistry.java:48](../../../../../backend/src/main/java/com/aicodeassistant/tool/ToolRegistry.java#L48)；[session-merge-architecture-v2.md:396](../../../../session-merge-architecture-v2.md#L396)

核验：代码静态核验及原文：见code_refs/lines

### R02-C17

**promptVersion未校验** — 证据不足 / conditional / scope=new / 不入分；统一问题：prompt-version-drift；credit=None。

[原文 L128–L128](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L128)

````text
4. **`Execution.promptVersion` 写入但从不校验**（`MergeHandoffData` 第 21 行、`SessionMergeService` 第 106 行）：修改提示词不会使旧单元失效，属隐性错误风险；要么用它参与 unitId/校验，要么删掉。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：当前仅PROCESSOR_VERSION核验，若未来只改promptVersion可能混用旧结果；尚无当前版本内触发。

判定理由：当前仅PROCESSOR_VERSION核验，若未来只改promptVersion可能混用旧结果；尚无当前版本内触发。

代码：[MergeSummaryService.java:65](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L65)；[SessionMergeService.java:106](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L106)

核验：代码静态核验及原文：见code_refs/lines

### R02-C18

**ObjectMapper实例未统一** — 成立 / engineering / scope=new / 不入分；统一问题：objectmapper-reuse；credit=None。

[原文 L129–L129](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L129)

````text
5. **ObjectMapper 未复用容器实例**：`projectionCatalogs` 内 `new ObjectMapper()`，`MergeSummaryService` 第 32 行自建实例，建议注入共享实例（也避免与全局 Jackson 配置漂移）。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：局部实例存在；建议可能提高一致性，但无已证序列化错误。

判定理由：局部实例存在；建议可能提高一致性，但无已证序列化错误。

代码：[MergeSummaryService.java:32](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L32)；[MergePackageService.java:298](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L298)

核验：代码静态核验及原文：见code_refs/lines

### R02-C19

**attempt完成后补error可维护性** — 成立 / engineering / scope=new / 不入分；统一问题：attempt-outcome-maintainability；credit=None。

[原文 L130–L130](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L130)

````text
6. **可维护性小项**：`finishAttempt` / `failAttempt` 的"先 completed 再翻 error"补偿逻辑（`MergeProgressRepository` 第 197-200 行）语义晦涩，建议在 `call()` 里按结果直接上报 outcome；`HandoffReadTool` 用 ImageIO 探测类型导致 **WebP 永远判为不支持**（白名单里有 `image/webp`），与"复用现有图片能力"表述不符。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：failAttempt会completed→error且不清usage，建议直接按结果记录可讨论，非结果错误。

判定理由：failAttempt会completed→error且不清usage，建议直接按结果记录可讨论，非结果错误。

代码：[MergeProgressRepository.java:197](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L197)；[MergeSummaryService.java:233](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L233)

核验：代码静态核验及原文：见code_refs/lines

### R02-C20

**无扩展副本WebP无法读取** — 成立 / functional / scope=new / 入分；统一问题：D11；credit=1。

[原文 L130–L130](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L130)

````text
6. **可维护性小项**：`finishAttempt` / `failAttempt` 的"先 completed 再翻 error"补偿逻辑（`MergeProgressRepository` 第 197-200 行）语义晦涩，建议在 `call()` 里按结果直接上报 outcome；`HandoffReadTool` 用 ImageIO 探测类型导致 **WebP 永远判为不支持**（白名单里有 `image/webp`），与"复用现有图片能力"表述不符。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：ImageIO默认无WebP reader，副本a_hash无后缀；FileRead原支持.webp。但probeContentType依平台，不能静态证明永远拒绝，已提交主评审合成WebP复现。

判定理由：ImageIO默认无WebP reader，副本a_hash无后缀；FileRead原支持.webp。但probeContentType依平台，不能静态证明永远拒绝，已提交主评审合成WebP复现。；统一复核：有效WebP在当前macOS/JDK环境有.webp后缀时MIME通过，a_hash无扩展副本被拒；默认ImageIO无WebP reader。确认此环境和副本路径，不证明所有平台永远拒绝，也未证明有后缀即完成视觉注入。

核实范围：有效WebP封存到无扩展副本后，在已测环境失去MIME识别。

代码：[HandoffReadTool.java:33](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L33)；[HandoffReadTool.java:36](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L36)；[FileReadTool.java:259](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/FileReadTool.java#L259)

核验：[reproductions/RankingAssetReproTest.java](reproductions/RankingAssetReproTest.java)

### R02-C21

**验收表中的测试缺口** — 成立 / engineering / scope=new / 不入分；统一问题：merge-test-coverage-gaps；credit=None。

[原文 L141–L152](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L141)

````text
| 迁移与单一操作 | ✅ | 但 **`MERGE_IDEMPOTENCY_CONFLICT`（同键不同参数 409，规范第 220 行）零覆盖**；`normalize()` 的 2～5 / 主来源 / 非法 ID 校验在重写测试中**被删除且未补** |
| 占用与释放 | ⚠️ | 仅根 + 子任务忙；**第五来源忙、后台服务占用、等待审批**用例被删未补；来源"可删除"仅验证了发消息与截止边界 |
| 普通 / fork / Swarm 隔离 | ⚠️ | 有 `verifyNoInteractions(handoff)` 强断言；但没有"执行和保存代码与基线一致"的守护测试（靠人工比对） |
| 长历史与重复 | ✅ | 含 >16 单元、尾部可检索、跨分片搜索 |
| 极端记录 / 附件 | ✅ | raw + 进度保留后暂停 |
| 本次失败条件 | ✅ | 4,183 bytes 仅存在于注释，建议加真实长度断言 |
| 当前单元恢复 | ⚠️ | 413 / 非法 JSON / 坏哈希有；**`MERGE_LENGTH_STOP`（`MergeSummaryService` 第 343 行）与 256 KiB `MERGE_RESPONSE_LIMIT` 的测试被删且未补** |
| 发布与取消竞态 | ⚠️ | 不确定提交、迟到响应、回滚已覆盖；**慢 hook、磁盘/消息提交失败**用例被删未补；"不误删 completed 包"间接验证 |
| 绑定与重复合并 | ⚠️ | 重复合并去重有；**新包未做 HandoffRead 可读性断言**；"删目标只清自己的包"仅单操作 |
| 接续资料与状态隔离 | ⚠️ | **工具 ID 隔离测试被删**（规范第 449 行明确要求保留）；E 的独立文件缓存 / 待办 / 设置隔离无自动化 |
| 读取与权限 | ✅ | 较强；`foreign-package-ref` 与穿越用例共用同一断言，无法区分命中哪条检查 |
| UI 与控制 | ⚠️ | active 恢复、真实锁集合已覆盖；**protocol=1 只读展示无实现分支也无测试**（目前仅靠后端 `canResume/canCancel=false` 间接只读）；**stale epoch 409 后刷新状态**、**取消期间有轮询在飞**两条前端路径无测试 |
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：准入/幂等精确码、第五来源审批后台、length/response_limit、hook、工具ID及Panel新协议路径缺显式断言；具体已有测试不构成所有组合覆盖。

判定理由：准入/幂等精确码、第五来源审批后台、length/response_limit、hook、工具ID及Panel新协议路径缺显式断言；具体已有测试不构成所有组合覆盖。

代码：[SessionMergeServiceTest.java:69](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L69)；[SessionMergeServiceTest.java:146](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L146)；[MergeSummaryServiceTest.java:74](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L74)；[sessionMergeStore.test.ts:427](../../../../../frontend/src/store/sessionMergeStore.test.ts#L427)

核验：代码静态核验及原文：见code_refs/lines

### R02-C22

**artifact表分支和旧build测试** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-helper-and-artifact-tests；credit=None。

[原文 L156–L157](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L156)

````text
- `artifact_manifests` / `artifact_entries` 导出（规范第 118 行硬要求）在测试夹具里**没有对应表**，`tableExists` 恒为 false → **该分支零执行**。
- `build()` 已不在在线入口使用，但 `MergePackageServiceTest` 仍有 17 处调用它、12 处调用 `seal()`，存在"大量测试养着死路径"的结构性问题（建议将复制 / 软链 / 配额断言迁移到 `seal`）。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：MergeFixture未建artifact表；多项复制/Unicode测试走旧build，在线seal分支覆盖不足。

判定理由：MergeFixture未建artifact表；多项复制/Unicode测试走旧build，在线seal分支覆盖不足。

代码：[MergeFixture.java:38](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeFixture.java#L38)；[MergePackageServiceTest.java:198](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L198)

核验：代码静态核验及原文：见code_refs/lines

### R02-C23

**restore测试因没refresh而空洞** — 不成立 / engineering / scope=new / 不入分；统一问题：restore-test-vacuous-claim；credit=None。

[原文 L158–L158](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L158)

````text
- 前端两处**空洞断言**：`sessionMergeStore.test.ts:410` 未调用 `refresh()` 就断言 paused 不被改写（即使回归也通过）；`sessionMergeStore.test.ts:487-508` 的 `finishPolling?.()` 因 `control()` 先占用 `inflight` 而是死分支（该用例实际证明的是"根本不会并发发 GET"，本身符合规范"恢复/取消与轮询串行"，但断言写法有误导）。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：410测试通过import触发restore；其目标是模块加载不把paused改成preparing，根本不要求refresh。测试能发现旧restore恢复错误。

判定理由：410测试通过import触发restore；其目标是模块加载不把paused改成preparing，根本不要求refresh。测试能发现旧restore恢复错误。

代码：[sessionMergeStore.test.ts:410](../../../../../frontend/src/store/sessionMergeStore.test.ts#L410)；[sessionMergeStore.ts:118](../../../../../frontend/src/store/sessionMergeStore.ts#L118)；[sessionMergeStore.ts:56](../../../../../frontend/src/store/sessionMergeStore.ts#L56)

核验：代码静态核验及原文：见code_refs/lines

### R02-C24

**串行测试未发GET** — 成立 / engineering / scope=new / 不入分；统一问题：polling-test-order-coverage；credit=None。

[原文 L158–L158](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L158)

````text
- 前端两处**空洞断言**：`sessionMergeStore.test.ts:410` 未调用 `refresh()` 就断言 paused 不被改写（即使回归也通过）；`sessionMergeStore.test.ts:487-508` 的 `finishPolling?.()` 因 `control()` 先占用 `inflight` 而是死分支（该用例实际证明的是"根本不会并发发 GET"，本身符合规范"恢复/取消与轮询串行"，但断言写法有误导）。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：此用例明确验证POST占inflight后GET被阻止；可补反序场景，但当前测试有效，不能因finishPolling未赋值认定虚假覆盖。

判定理由：此用例明确验证POST占inflight后GET被阻止；可补反序场景，但当前测试有效，不能因finishPolling未赋值认定虚假覆盖。

代码：[sessionMergeStore.test.ts:487](../../../../../frontend/src/store/sessionMergeStore.test.ts#L487)；[sessionMergeStore.ts:138](../../../../../frontend/src/store/sessionMergeStore.ts#L138)

核验：代码静态核验及原文：见code_refs/lines

### R02-C25

**取消等待在飞轮询** — 成立 / functional / scope=new / 入分；统一问题：D08；credit=1。

[原文 L159–L159](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L159)

````text
- `control()` 会 `while (inflight) await inflight`（`sessionMergeStore.ts` 第 227 行），最坏情况下"取消"要等一个 15 秒超时的轮询结束，建议在 UI 上给出"正在取消"反馈。
````

触发：暂停/运行操作轮询GET较慢
预期：控制操作可立即反馈并协调在飞请求
实际：恢复取消按钮暂时禁用/控制等待轮询

判定理由：control等待inflight，refresh设置submitting使panel按钮禁用；悬挂GET最长15秒阻止立即取消。设计保证串行但交互状态复用产生延迟。

核实范围：轮询复用submitting禁用恢复/取消，程序cancel也等待GET结束；不表示后端永久不能取消。

代码：[sessionMergeStore.ts:144](../../../../../frontend/src/store/sessionMergeStore.ts#L144)；[sessionMergeStore.ts:227](../../../../../frontend/src/store/sessionMergeStore.ts#L227)；[SessionMergePanel.tsx:222](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L222)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R02-C26

**非preparing持锁状态UI未显示** — 成立 / functional / scope=new / 入分；统一问题：D15；credit=1。

[原文 L160–L160](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L160)

````text
- 前端 `selectMergeSourceIds` 仅在 `status === 'preparing'` 时返回 `lockedSourceSessionIds`（`sessionMergeStore.ts` 第 30-31 行），而规范第 421 行要求"来源是否锁住只看 `lockedSourceSessionIds`"；`paused` 且仍持锁时 UI 会显示未锁定（服务端 gate 仍会拒绝，属向用户展示不一致的低危问题）。
````

触发：封存过程中cancel，writer尚未释放token
预期：UI按真实lockedSourceSessionIds展示
实际：已cancelled但仍持锁的来源被显示未锁

判定理由：selector只返回preparing的锁；cancel先写cancelled再abort旧writer，封存中writer未收尾gate仍持有时可返回cancelled+非空locks，UI忽略。

核实范围：cancelled/paused短窗口真实锁仍在但selector隐藏；后端gate保护仍在。

代码：[sessionMergeStore.ts:30](../../../../../frontend/src/store/sessionMergeStore.ts#L30)；[SessionMergeService.java:149](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L149)；[SessionMergeService.java:230](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L230)；[SessionMergeService.java:263](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L263)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R02-C27

**要求环境失败覆盖隔离绿色文档** — 不成立 / engineering / scope=new / 不入分；统一问题：isolated-test-evidence-invalidated；credit=None。

[原文 L166–L166](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L166)

````text
1. **文档结论与本机实测不符**：`docs/session-merge-architecture-v2.md` 第 7 行写"后端全量 2,975 项：2,905 通过、70 跳过、0 失败/错误"，实测为 **2,900 通过 / 5 失败**（`WorkspaceFileBoundaryTest`、`ManagedProcessRunnerTest`，基线同样失败）。既然文档要随包提交，应改为"5 项与本改动无关的环境相关失败（已用 HEAD 干净副本对照复现）"。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：规范7明确只叠加本次改动的干净副本；该报告环境中基线也失败，不能否认另一个隔离运行的成功。可以补环境说明，不能把其5失败替换成唯一真实结论。

判定理由：规范7明确只叠加本次改动的干净副本；该报告环境中基线也失败，不能否认另一个隔离运行的成功。可以补环境说明，不能把其5失败替换成唯一真实结论。

代码：[session-merge-architecture-v2.md:7](../../../../session-merge-architecture-v2.md#L7)

核验：代码静态核验及原文：见code_refs/lines

### R02-C28

**真实语义验收缺失** — 成立 / engineering / scope=new / 不入分；统一问题：semantic-acceptance-missing；credit=None。

[原文 L167–L167](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L167)

````text
2. **文档缺失内容**：规范第 9 节要求"必须包含一个接续开发样本"（A 改 `name`→`displayName`、B 未联调）与真实模型语义验收，文档第 3 / 471 行自认**尚未执行**。按该文档自身的验收标准，本改动目前**只完成了 stub 层验证**，语义验收仍是发布前的未完成项——必须在 PR 描述里明确，不能以"自动化全绿"代替。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：文档和报告均明确未执行。

判定理由：文档和报告均明确未执行。

代码：[session-merge-architecture-v2.md:3](../../../../session-merge-architecture-v2.md#L3)；[session-merge-architecture-v2.md:453](../../../../session-merge-architecture-v2.md#L453)

核验：代码静态核验及原文：见code_refs/lines

### R02-C29

**未跟踪新增文件与排除文件提示** — 证据不足 / engineering / scope=new / 不入分；统一问题：commit-hygiene；credit=None。

[原文 L168–L170](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L168)

````text
3. **未跟踪文件**：6 个新增 Java 文件、2 个新增前端文件与架构文档均为 `??` 未跟踪状态（共 49 项变更）。提交时若使用 `git commit -am` 会**静默漏掉全部新增文件**；请显式 `git add` 后再提交。
4. 用户给出的忽略清单中 `frontend/e2e/message-copy-all.spec.ts` 在工作区**并不存在**，请确认是否属于另一未完成的任务。
5. 无密钥 / 凭据泄漏；`.env` 未被跟踪；测试未写入运行库。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：属当时git工作区卫生提示，当前材料版本冻结不还原每个时点工作区；不构成功能TP/FP。

判定理由：属当时git工作区卫生提示，当前材料版本冻结不还原每个时点工作区；不构成功能TP/FP。

代码：[session-merge-architecture-v2.md:7](../../../../session-merge-architecture-v2.md#L7)

核验：代码静态核验及原文：见code_refs/lines

### R02-C30

**迁移与启动清理/恢复全局影响** — 成立 / conditional / scope=new / 不入分；统一问题：merge-startup-side-effects；credit=None。

[原文 L56–L57](../DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L56)

````text
1. **迁移对所有用户库生效**：`V026` 会 DROP 并重建 `session_merges`（`V026_ExtendSessionMerges.java` 第 13-98 行）。已核对旧校验器 `V024_CreateSessionMerges.validate()` 只校验列存在，因此**不会因 schema 变更导致启动校验失败**；`MigrationRunner` 在事务内执行并以 `protocol_version` 存在性做幂等，测试覆盖了"再启动校验通过 / 重复 target 回滚"。
2. **启动期副作用**：`SessionMergeService.@PostConstruct recover()`（第 74-80 行）会在应用启动时**同步扫描并删除**已取消/目标已删除的包目录，并对未完成的 v2 操作**自动续跑（含真实付费模型调用）**。这是规范 §5.2 的既定行为，但使"所有用户"的启动耗时与重启后费用受合并任务影响。建议 cleanup 加超时或异步化，并在文档中显式写明"重启会自动续跑并计费"。
````

触发：报告所述场景
预期：按当前规格和已确认范围处理；工程建议不等同产品缺陷
实际：启动同步cleanup且preparing可续跑付费调用，属于声明的恢复行为；不证明普通推理函数语义被破坏。

判定理由：启动同步cleanup且preparing可续跑付费调用，属于声明的恢复行为；不证明普通推理函数语义被破坏。

代码：[SessionMergeService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L74)；[V026_ExtendSessionMerges.java:55](../../../../../backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java#L55)

核验：代码静态核验及原文：见code_refs/lines

## R03

[完整原报告](../KimiCode/KimiK3-session-merge-v2-review.md)

### R03-normal_function

**判断分：5/5**。[原文 L19–L34](../KimiCode/KimiK3-session-merge-v2-review.md#L19)。普通入口守卫、原持久化文件与工具池隔离符合独立代码核验；合并来源的取消显示问题不等于无关普通请求回归。

````text
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
````

### R03-boundary

**判断分：5/5**。[原文 L23–L34](../KimiCode/KimiK3-session-merge-v2-review.md#L23)。全文明确静态元数据守卫、公共图片/授权接点与V026全环境迁移边界；不把“零开销”口头概括单独当绝对零风险声明。

````text
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
````

### R03-release

**判断分：5/5**。[原文 L105–L116](../KimiCode/KimiK3-session-merge-v2-review.md#L105)。明确当前未达标准，先修功能缺陷并补覆盖，不把结构测试当完成版。

````text
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
````

### R03-semantic

**判断分：5/5**。[原文 L116–L124](../KimiCode/KimiK3-session-merge-v2-review.md#L116)。明确真实模型接续语义未执行且stub不能替代，并允许阶段提交。

````text
| 规范自述的遗留验收 | ⚠️ §9 末段**真实模型接续开发语义验收尚未执行**（文档第 3、471 行均明示），stub 测试不能替代；规范允许阶段分提交，但"整体闭合前不宣称完成" |

**建议行动顺序**：

1. 修复 H1（非关键附件降级非阻断 + 阻断 gap 补 ref）——这是唯一会导致用户场景永久卡死的功能缺陷；
2. 补 M6 四项测试缺口（准入/忙态、stop=length、v2 磁盘暂停、occurrences/artifact 断言）；
3. 顺手处理 M3–M5（依赖方向、错误码透出、工具说明补全，均为小改动）；
4. 确认 CI 环境 ripgrep 可用（或修复 grep 回退对显式隐藏目录的处理），使后端套件全绿；
5. 提交时包含 `docs/session-merge-architecture-v2.md` 本身；提交信息中如实标注"真实模型语义验收未执行"。
````

### R03-C01

**非关键非UTF8文本附件阻断整个合并** — 成立 / functional / scope=new / 入分；统一问题：D05；credit=1。

[原文 L75–L75](../KimiCode/KimiK3-session-merge-v2-review.md#L75)

````text
**H1. 三类阻断性 gap 形成永久死暂停，含此类来源的合并永远无法完成** —— `MergePackageService.java:598`（非 UTF-8 文本附件如 GBK 日志）、`:531`（base64 非法的内嵌图片）、`:436`（消息级 content_json 解析失败写了无 ref 后缀的裸 `RECORD_REQUIRES_HANDLING`）。三者的共同后果：记录未按 blocked 登记或 gap 缺 ref，`recoverBlockedProjections`（`:232-236`）校验必然抛错 → resume 永远重现同一失败 → 只能取消，而来源内容不变，取消重建后依旧失败。且非关键附件阻断本身违反 §4.4"非关键附件未解析可明确标记并保留原件"。**建议**：非关键附件解析失败降级为非阻断 gap（保留原件+标记）；确需阻断的一律携带 blocked 记录 ref，使配置修复后可恢复。
````

触发：来源包含非关键GBK日志/文本附件，其他对话足以整理
预期：保留原件并明确未解析，不因此阻断基本合并
实际：转paused且同一快照resume无法前进

判定理由：资产已独立复制并登记extract，文本按UTF8读取失败却记blocking；恢复只接受blocked记录，无法降级为保留原件的非关键缺口。与规格183允许非关键未解析附件保留并说明不符。

核实范围：已保存非关键GBK日志原件已复制，仍因不能UTF8解析阻断合并。

代码：[MergePackageService.java:583](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L583)；[MergePackageService.java:589](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L589)；[MergePackageService.java:598](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L598)；[MergePackageService.java:235](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L235)；[session-merge-architecture-v2.md:183](../../../../session-merge-architecture-v2.md#L183)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R03-C02

**消息解析和非法图片gap缺少可恢复记录ref** — 成立 / functional / scope=new / 入分；统一问题：D04；credit=1。

[原文 L75–L75](../KimiCode/KimiK3-session-merge-v2-review.md#L75)

````text
**H1. 三类阻断性 gap 形成永久死暂停，含此类来源的合并永远无法完成** —— `MergePackageService.java:598`（非 UTF-8 文本附件如 GBK 日志）、`:531`（base64 非法的内嵌图片）、`:436`（消息级 content_json 解析失败写了无 ref 后缀的裸 `RECORD_REQUIRES_HANDLING`）。三者的共同后果：记录未按 blocked 登记或 gap 缺 ref，`recoverBlockedProjections`（`:232-236`）校验必然抛错 → resume 永远重现同一失败 → 只能取消，而来源内容不变，取消重建后依旧失败。且非关键附件阻断本身违反 §4.4"非关键附件未解析可明确标记并保留原件"。**建议**：非关键附件解析失败降级为非阻断 gap（保留原件+标记）；确需阻断的一律携带 blocked 记录 ref，使配置修复后可恢复。
````

触发：content_json损坏或内嵌图片base64非法
预期：需要区分可支持的配置恢复与不可变损坏原件
实际：这些gap在resume持续拒绝；支持内可恢复前提尚未证实

判定理由：裸gap与invalid_image确实不能通过blockedRecords匹配，但已损坏JSON/base64属于规范允许暂停且可取消重建的输入；报告未证明这些具体原件在支持内只改配置即可变为可解析，不能由字符串不一致直接认定核心恢复承诺失效。；统一复核：合成原件不变，启用Jackson现有ALLOW_SINGLE_QUOTES后已可解析，恢复仍在读raw前被缺ref挡住；提高物化上限的正控制能恢复。仅确认这类关联协议缺口，损坏资料允许暂停，不采用永久/所有恢复失效的泛化。

核实范围：已修正解析配置仍被gap/ref关联校验阻止；不等于所有坏原件应可恢复。

代码：[MergePackageService.java:435](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L435)；[MergePackageService.java:531](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L531)；[MergePackageService.java:235](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L235)；[session-merge-architecture-v2.md:179](../../../../session-merge-architecture-v2.md#L179)；[session-merge-architecture-v2.md:183](../../../../session-merge-architecture-v2.md#L183)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R03-C03

**v2无failed终态且损坏快照一律paused** — 规范歧义 / functional / scope=new / 不入分；统一问题：irrecoverable-state-classification；credit=None。

[原文 L79–L79](../KimiCode/KimiK3-session-merge-v2-review.md#L79)

````text
- **M1. v2 代码不存在任何到 failed 的转移路径**（`SessionMergeService.java:218-228`）：哈希不符等"确认无法恢复"的损坏也一律转 paused，用户提示"请排查后恢复"对此类情况永远无效。§5.1/§6.3 要求区分。建议至少在错误提示上引导取消，或提供确认转 failed 路径。
````

触发：
预期：
实际：

判定理由：代码确实只有pause；规格211有failed，但352也规定先暂停诊断，代码没有可信的“已确认不可恢复”判定器，且说明允许cancel。不能仅由一次哈希不符认定必须自动failed。

代码：[SessionMergeService.java:218](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L218)；[SessionMergeService.java:246](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L246)；[session-merge-architecture-v2.md:211](../../../../session-merge-architecture-v2.md#L211)；[session-merge-architecture-v2.md:352](../../../../session-merge-architecture-v2.md#L352)

核验：代码静态核验及原文：见code_refs/lines

### R03-C04

**legacy旧包缺失阻断重复合并** — 规范歧义 / functional / scope=new / 不入分；统一问题：missing-legacy-package-policy；credit=None。

[原文 L80–L80](../KimiCode/KimiK3-session-merge-v2-review.md#L80)

````text
- **M2. v1 旧包缺失记阻断 gap**（`MergePackageService.java:608` `legacy_package_missing`）：与 §1 第 5 条"重复合并不依赖旧包存活"、§4.4"不依赖祖先会话存在"冲突。旧包被清理后，E 类会话的再次合并将永久阻断。建议降级为非阻断缺口（标明无法精确跨包去重）。
````

触发：
预期：
实际：

判定理由：缺失v1包确实blocking且不会恢复；但“不依赖旧包”与“只从现存包收录、不冒充完整历史”边界存在张力，应明确是否允许摘要降级后再认定，不按旧榜给其他缺陷半分。

代码：[MergePackageService.java:606](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L606)；[SessionMergeService.java:195](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L195)；[session-merge-architecture-v2.md:185](../../../../session-merge-architecture-v2.md#L185)；[session-merge-architecture-v2.md:216](../../../../session-merge-architecture-v2.md#L216)

核验：代码静态核验及原文：见code_refs/lines

### R03-C05

**Repository依赖Summary内部CallUsage形成包内环** — 成立 / engineering / scope=new / 不入分；统一问题：repository-callusage-dependency；credit=None。

[原文 L81–L81](../KimiCode/KimiK3-session-merge-v2-review.md#L81)

````text
- **M3. `MergeProgressRepository.java:177` 依赖 `MergeSummaryService.CallUsage`**，而后者反向依赖 Repository，形成包内环，违反 §10"Repository 只依赖 JDBC/事务/序列化"。建议把 `CallUsage` 移到 `MergeHandoffData`。
````

触发：
预期：
实际：

判定理由：仓库finishAttempt签名直接引用Summary.CallUsage，Summary依赖仓库；职责整理建议成立，未证明产品功能故障。

代码：[MergeProgressRepository.java:177](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L177)；[MergeSummaryService.java:63](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L63)

核验：代码静态核验及原文：见code_refs/lines

### R03-C06

**authorize把截止/中断错误包装为非法资源** — 成立 / functional / scope=new / 入分；统一问题：D13；credit=1。

[原文 L82–L82](../KimiCode/KimiK3-session-merge-v2-review.md#L82)

````text
- **M4. `HandoffReadService.authorize()`（`:49-56`）把所有 IOException 包成 `HANDOFF_INVALID_RESOURCE`**，吞掉 `InterruptedIOException`（用户取消被当成"非法资源"）和 budget 超时（应为 `HANDOFF_READ_BUDGET_EXCEEDED`，§7.2）。`read()` 路径（`:206-208`）同样。建议重抛中断、透出原码。
````

触发：HandoffRead授权校验超时或线程中断
预期：返回可识别的预算/中断错误
实际：外层AuthorizationException.code统一HANDOFF_INVALID_RESOURCE

判定理由：authorize catch(IOException)覆盖Budget.check的截止和中断，异常code变为HANDOFF_INVALID_RESOURCE，虽然message仍保留原始原因。截止应按规格保留明确预算错误类别。

核实范围：授权/读取支路把预算错误码包成INVALID_RESOURCE等泛码；read入口中断反例正常重抛。

代码：[HandoffReadService.java:41](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L41)；[HandoffReadService.java:49](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L49)；[HandoffReadService.java:55](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L55)；[session-merge-architecture-v2.md:387](../../../../session-merge-architecture-v2.md#L387)

核验：[reproductions/RankingReadReproTest.java](reproductions/RankingReadReproTest.java)

### R03-C07

**read入口也吞掉InterruptedIOException** — 不成立 / functional / scope=new / 入分；统一问题：read-initial-interruption-swallowed；credit=0。

[原文 L82–L82](../KimiCode/KimiK3-session-merge-v2-review.md#L82)

````text
- **M4. `HandoffReadService.authorize()`（`:49-56`）把所有 IOException 包成 `HANDOFF_INVALID_RESOURCE`**，吞掉 `InterruptedIOException`（用户取消被当成"非法资源"）和 budget 超时（应为 `HANDOFF_READ_BUDGET_EXCEEDED`，§7.2）。`read()` 路径（`:206-208`）同样。建议重抛中断、透出原码。
````

触发：read初始完整性校验发生线程中断
预期：保留中断语义
实际：代码已显式重抛InterruptedIOException

判定理由：read在207行明确catch InterruptedIOException并原样重抛；不能把authorize的catch概括为read入口同样吞中断。后续resolve分支另有IOException包装，但报告指定206-208的入口指控不成立。；统一复核：统一按其具体指控的可证明后果计轻微：错误分类/状态展示，未声称资料毁损；不沿用作者P级。

代码：[HandoffReadService.java:206](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L206)；[HandoffReadService.java:207](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L207)；[HandoffReadServiceTest.java:165](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L165)

核验：代码静态核验及原文：见code_refs/lines

### R03-C08

**固定工具说明遗漏交接使用指引** — 成立 / engineering / scope=new / 不入分；统一问题：handoff-tool-guidance-incomplete；credit=None。

[原文 L83–L83](../KimiCode/KimiK3-session-merge-v2-review.md#L83)

````text
- **M5. `HandoffReadTool.getDescription()` 缺 §7.1 强制的程序生成指引**（"继续来源任务先读交接详情""目录/未展开计数不代表已了解详情，历史测试不代表当前测试"等）。参考入口侧已有等价表述，工具说明需补齐——这是 §7.1 明确写在"及"两侧的要求。
````

触发：
预期：
实际：

判定理由：getDescription确实没有373行要求的读详情/核对现状/复测等完整指引；入口已有部分说明。属于可直接修正文案规范缺口，未以真实模型证据证明已导致错误开发。

代码：[HandoffReadTool.java:16](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L16)；[HandoffContextService.java:68](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L68)；[session-merge-architecture-v2.md:373](../../../../session-merge-architecture-v2.md#L373)

核验：代码静态核验及原文：见code_refs/lines

### R03-C09

**准入/截断响应/v2复制/artifact/通知测试缺口** — 成立 / engineering / scope=new / 不入分；统一问题：merge-required-test-gaps；credit=None。

[原文 L84–L88](../KimiCode/KimiK3-session-merge-v2-review.md#L84)

````text
- **M6. 测试覆盖回退 4 处**（§9 矩阵空洞，均因旧断言删除后未补替代）：
  a. 准入/忙态：审批中（waiting_interaction）拒绝、后台租约忙拒绝、同键不同参数 409、2～5/重复 ID 参数校验；
  b. `stop=length`/截断 JSON 不得当成功的缩小处理（§6.3 表格行）；
  c. v2 seal 路径的磁盘余量/复制配额暂停（现有 `MERGE_DISK_SPACE_LOW` 用例全走 legacy `build` 路径，而在线入口已不调用它）；
  d. `occurrences.jsonl` 重复位置清单与 artifact_manifests/entries 导出（生产代码已实现）零断言；慢 hook/通知 hook 失败不回滚目标无替代测试。
````

触发：
预期：
实际：

判定理由：现有服务测试覆盖根和后代忙，但未覆盖waiting_interaction等细分；长度停止、v2磁盘路径、artifact夹具及hook失败缺专门断言。legacy build测试不能自动证明seal覆盖。

代码：[SessionMergeServiceTest.java:146](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L146)；[MergeSummaryServiceTest.java:74](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L74)；[MergePackageServiceTest.java:380](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L380)；[MergeFixture.java:36](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeFixture.java#L36)

核验：代码静态核验及原文：见code_refs/lines

### R03-C10

**取消与发布竞态返回stale而非already-completed** — 成立 / functional / scope=new / 入分；统一问题：D21；credit=1。

[原文 L92–L92](../KimiCode/KimiK3-session-merge-v2-review.md#L92)

````text
- cancel 与发布竞态时返回 `MERGE_STALE_OPERATION` 而非 §5.3 的 `MERGE_ALREADY_COMPLETED`（`SessionMergeService.java:150`）；
````

触发：发布提交落在cancel首次get与UPDATE之间
预期：已完成时返回MERGE_ALREADY_COMPLETED
实际：409 MERGE_STALE_OPERATION，附最新状态

判定理由：第一次get尚未completed而UPDATE时发布已提交，cancel返回false分支固定STALE；状态安全但错误分类不符合区分已完成的接口约定。

核实范围：读取cancel状态后publish抢先完成，CAS失败被映射STALE而非ALREADY_COMPLETED；原子性仍在。

代码：[SessionMergeService.java:147](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L147)；[SessionMergeService.java:150](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L150)；[MergeProgressRepository.java:120](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L120)；[session-merge-architecture-v2.md:272](../../../../session-merge-architecture-v2.md#L272)

核验：代码静态核验：见code_refs

### R03-C11

**DTO锁集合只含根而gate锁整棵来源树** — 证据不足 / conditional / scope=new / 不入分；统一问题：locked-descendants-omitted；credit=None。

[原文 L93–L93](../KimiCode/KimiK3-session-merge-v2-review.md#L93)

````text
- `lockedSourceSessionIds` 只含根来源，实际 lease 锁了整棵子任务树（`SessionMergeService.java:263`）；
````

触发：空闲根来源拥有已保存后代并正在复制
预期：DTO暴露实际被锁的来源树成员
实际：只返回请求的根来源ID

判定理由：DTO只返回根而gate锁后代的事实成立，但报告未证明普通用户可操作后代会话的支持内UI路径；列表SessionController另返回真实gate占用。暂不把资料字段差异升级为漏锁功能缺陷。

代码：[SessionMergeService.java:114](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L114)；[SessionMergeService.java:263](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L263)；[session-merge-architecture-v2.md:272](../../../../session-merge-architecture-v2.md#L272)；[SessionController.java:175](../../../../../backend/src/main/java/com/aicodeassistant/controller/SessionController.java#L175)

核验：代码静态核验及原文：见code_refs/lines

### R03-C12

**请求字节保护未测JSON转义及系统包装** — 证据不足 / engineering / scope=new / 不入分；统一问题：serialized-request-byte-guard；credit=None。

[原文 L94–L94](../KimiCode/KimiK3-session-merge-v2-review.md#L94)

````text
- 请求字节保护阈值 900KiB 低于规范默认 1MiB 且未计入 JSON 转义膨胀（`MergeSummaryService.java:212`）；聚合分批用 UTF-16 字符数与估算 token 混用（`:99`）；"同单元/模型最多 3 次"按单次运行局部计数，持久 `attempt_count` 不参与上限（`:216`）；`details.jsonl` 用 truncate 直写而非原子写（`:119`）；取消时当前 attempt 行永久残留 `running`；
````

触发：
预期：
实际：

判定理由：检查确实仅input UTF8且阈值900KiB，但正常单元来自32KiB正文片或约8192字符聚合批次，尚未证明受支持在线路径会发送超过1MiB。900KiB保护比默认保守本身不构成功能错误。

代码：[MergeSummaryService.java:212](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L212)；[MergeSummaryService.java:78](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L78)；[MergeSummaryService.java:99](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L99)；[session-merge-architecture-v2.md:334](../../../../session-merge-architecture-v2.md#L334)

核验：代码静态核验及原文：见code_refs/lines

### R03-C13

**聚合预分批按UTF16字符而非token** — 成立 / engineering / scope=new / 不入分；统一问题：aggregate-planning-character-budget；credit=None。

[原文 L94–L94](../KimiCode/KimiK3-session-merge-v2-review.md#L94)

````text
- 请求字节保护阈值 900KiB 低于规范默认 1MiB 且未计入 JSON 转义膨胀（`MergeSummaryService.java:212`）；聚合分批用 UTF-16 字符数与估算 token 混用（`:99`）；"同单元/模型最多 3 次"按单次运行局部计数，持久 `attempt_count` 不参与上限（`:216`）；`details.jsonl` 用 truncate 直写而非原子写（`:119`）；取消时当前 attempt 行永久残留 `running`；
````

触发：
预期：
实际：

判定理由：size=end-start+128用于8192字符预分批，真正调用前又用capacity与budget检查并split，因此是计量命名/规划保守性建议，非已证预算越界。

代码：[MergeSummaryService.java:98](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L98)；[MergeSummaryService.java:212](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L212)

核验：代码静态核验及原文：见code_refs/lines

### R03-C14

**同模型attempt上限仅局部3次** — 规范歧义 / conditional / scope=new / 不入分；统一问题：attempt-limit-across-manual-resume；credit=None。

[原文 L94–L94](../KimiCode/KimiK3-session-merge-v2-review.md#L94)

````text
- 请求字节保护阈值 900KiB 低于规范默认 1MiB 且未计入 JSON 转义膨胀（`MergeSummaryService.java:212`）；聚合分批用 UTF-16 字符数与估算 token 混用（`:99`）；"同单元/模型最多 3 次"按单次运行局部计数，持久 `attempt_count` 不参与上限（`:216`）；`details.jsonl` 用 truncate 直写而非原子写（`:119`）；取消时当前 attempt 行永久残留 `running`；
````

触发：
预期：
实际：

判定理由：每次processStage确实重置attempt局部计数，但规格339限定自动尝试，用户主动resume通常允许修复后重试；不应把跨手动恢复累计超过3当成自动无限重试。

代码：[MergeSummaryService.java:216](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L216)；[MergeProgressRepository.java:115](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L115)；[session-merge-architecture-v2.md:339](../../../../session-merge-architecture-v2.md#L339)

核验：代码静态核验及原文：见code_refs/lines

### R03-C15

**details清单truncate直写非原子** — 成立 / engineering / scope=new / 不入分；统一问题：details-catalog-nonatomic-intermediate；credit=None。

[原文 L94–L94](../KimiCode/KimiK3-session-merge-v2-review.md#L94)

````text
- 请求字节保护阈值 900KiB 低于规范默认 1MiB 且未计入 JSON 转义膨胀（`MergeSummaryService.java:212`）；聚合分批用 UTF-16 字符数与估算 token 混用（`:99`）；"同单元/模型最多 3 次"按单次运行局部计数，持久 `attempt_count` 不参与上限（`:216`）；`details.jsonl` 用 truncate 直写而非原子写（`:119`）；取消时当前 attempt 行永久残留 `running`；
````

触发：
预期：
实际：

判定理由：输出直接打开catalog，未atomicWrite；但发布ready及完整性验证发生后，paused的中间清单不能被E消费，未证明资料错误发布。

代码：[MergeSummaryService.java:119](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L119)；[MergeSummaryService.java:149](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L149)

核验：代码静态核验及原文：见code_refs/lines

### R03-C16

**取消时当前attempt永久running** — 证据不足 / conditional / scope=new / 不入分；统一问题：cancelled-attempt-running-window；credit=None。

[原文 L94–L94](../KimiCode/KimiK3-session-merge-v2-review.md#L94)

````text
- 请求字节保护阈值 900KiB 低于规范默认 1MiB 且未计入 JSON 转义膨胀（`MergeSummaryService.java:212`）；聚合分批用 UTF-16 字符数与估算 token 混用（`:99`）；"同单元/模型最多 3 次"按单次运行局部计数，持久 `attempt_count` 不参与上限（`:216`）；`details.jsonl` 用 truncate 直写而非原子写（`:119`）；取消时当前 attempt 行永久残留 `running`；
````

触发：
预期：
实际：

判定理由：正常已进入call的取消仍执行finally usageSink完成attempt，报告泛指取消永久running不成立；beginAttempt后且call前本地异常存在窗口，但不能视为所有取消路径。

代码：[MergeSummaryService.java:217](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L217)；[MergeSummaryService.java:329](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L329)；[MergeProgressRepository.java:177](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L177)

核验：代码静态核验及原文：见code_refs/lines

### R03-C17

**failAttempt可将completed改写error** — 成立 / engineering / scope=new / 不入分；统一问题：attempt-outcome-transport-vs-validation；credit=None。

[原文 L95–L95](../KimiCode/KimiK3-session-merge-v2-review.md#L95)

````text
- `MergeProgressRepository`：`failAttempt` 会把已 completed 的 attempt 改写为 error（`:197-200`）；`beginAttempt` 允许 running→running 重复递增（`:166`）；`pause()` 不校验影响行数（`:99-103`）；
````

触发：
预期：
实际：

判定理由：这是传输完成后schema验证失败时的结果终态标记；usage未被清空且汇总不按outcome过滤，未证明计费数据损坏。

代码：[MergeProgressRepository.java:197](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L197)；[MergeProgressRepository.java:190](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L190)；[MergeSummaryService.java:224](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L224)

核验：代码静态核验及原文：见code_refs/lines

### R03-C18

**beginAttempt允许running再次递增** — 成立 / engineering / scope=new / 不入分；统一问题：attempt-running-to-running；credit=None。

[原文 L95–L95](../KimiCode/KimiK3-session-merge-v2-review.md#L95)

````text
- `MergeProgressRepository`：`failAttempt` 会把已 completed 的 attempt 改写为 error（`:197-200`）；`beginAttempt` 允许 running→running 重复递增（`:166`）；`pause()` 不校验影响行数（`:99-103`）；
````

触发：
预期：
实际：

判定理由：局部重试需要running到running新attempt且串行worker/epoch守卫存在；状态条件事实正确，未证明重复worker故障。

代码：[MergeProgressRepository.java:161](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L161)；[SessionMergeService.java:132](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L132)；[MergeSummaryService.java:216](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L216)

核验：代码静态核验及原文：见code_refs/lines

### R03-C19

**pause不校验更新行数** — 成立 / engineering / scope=new / 不入分；统一问题：pause-zero-row-check；credit=None。

[原文 L95–L95](../KimiCode/KimiK3-session-merge-v2-review.md#L95)

````text
- `MergeProgressRepository`：`failAttempt` 会把已 completed 的 attempt 改写为 error（`:197-200`）；`beginAttempt` 允许 running→running 重复递增（`:166`）；`pause()` 不校验影响行数（`:99-103`）；
````

触发：
预期：
实际：

判定理由：取消/已完成及过期epoch时有意不覆写状态，0行可接受；纯一致性建议并非丢失正确性证据。

代码：[MergeProgressRepository.java:98](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L98)；[SessionMergeService.java:225](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L225)

核验：代码静态核验及原文：见code_refs/lines

### R03-C20

**packageRoot使用cwd参数** — 规范歧义 / engineering / scope=new / 不入分；统一问题：package-root-cwd-fallback；credit=None。

[原文 L96–L96](../KimiCode/KimiK3-session-merge-v2-review.md#L96)

````text
- `MergePackageService.packageRoot()` 以 `user.dir` 为解析输入，§4.2 要求不硬编码 cwd（`:89`）；messages/artifacts/checkpoints 分页均 `LIMIT 1` 逐行查询，长历史下数千次往返（性能备注）；
````

触发：
预期：
实际：

判定理由：代码调用DatabaseResolver且使用与SqliteConfig相同的cwd默认输入，配置根会覆盖该参数；与“不硬编码cwd”字面有张力，但未证明正常配置下与实际项目库不一致。

代码：[MergePackageService.java:87](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L87)；[DatabaseResolver.java:42](../../../../../backend/src/main/java/com/aicodeassistant/config/database/DatabaseResolver.java#L42)；[SqliteConfig.java:94](../../../../../backend/src/main/java/com/aicodeassistant/config/database/SqliteConfig.java#L94)；[session-merge-architecture-v2.md:120](../../../../session-merge-architecture-v2.md#L120)

核验：代码静态核验及原文：见code_refs/lines

### R03-C21

**长历史逐行SQL与目录扫描性能** — 成立 / conditional / scope=new / 不入分；统一问题：merge-linear-page-query-overhead；credit=None。

[原文 L96–L96](../KimiCode/KimiK3-session-merge-v2-review.md#L96)

````text
- `MergePackageService.packageRoot()` 以 `user.dir` 为解析输入，§4.2 要求不硬编码 cwd（`:89`）；messages/artifacts/checkpoints 分页均 `LIMIT 1` 逐行查询，长历史下数千次往返（性能备注）；
````

触发：
预期：
实际：

判定理由：LIMIT 1分页和多次目录遍历存在，报告注明性能备注，尚无延迟或普通主链路影响测量。

代码：[MergePackageService.java:421](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L421)；[MergePackageService.java:445](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L445)；[MergeSummaryService.java:185](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L185)

核验：代码静态核验及原文：见code_refs/lines

### R03-C22

**迁移SQL用分号切分脆弱** — 成立 / engineering / scope=new / 不入分；统一问题：migration-semicolon-split；credit=None。

[原文 L97–L97](../KimiCode/KimiK3-session-merge-v2-review.md#L97)

````text
- `V026_ExtendSessionMerges.java:104` `SQL.split(";")` 依赖 SQL 文本无内嵌分号，当前成立但脆弱；
````

触发：
预期：
实际：

判定理由：当前内嵌SQL无字符串内分号，改进建议成立但当前不会因此误执行。

代码：[V026_ExtendSessionMerges.java:104](../../../../../backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java#L104)

核验：代码静态核验及原文：见code_refs/lines

### R03-C23

**状态字符串未集中常量** — 成立 / engineering / scope=new / 不入分；统一问题：status-string-constants；credit=None。

[原文 L98–L98](../KimiCode/KimiK3-session-merge-v2-review.md#L98)

````text
- unit state/operation status 字符串字面量散落，未在 `MergeHandoffData` 集中为常量；
````

触发：
预期：
实际：

判定理由：状态字面量散布在仓库/服务；纯可维护性不计产品缺陷。

代码：[MergeProgressRepository.java:115](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L115)；[SessionMergeService.java:138](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L138)

核验：代码静态核验及原文：见code_refs/lines

### R03-C24

**搜索命中摘要可能切断UTF16代理对** — 成立 / functional / scope=new / 入分；统一问题：D16；credit=1。

[原文 L99–L99](../KimiCode/KimiK3-session-merge-v2-review.md#L99)

````text
- `HandoffReadService` 命中片段截取可能切断代理对（`:261`）；跨分片续接每分片一次全目录扫描（O(n²)，有 15s 预算兜底）；
````

触发：匹配前后文截取边界落在emoji高低代理之间
预期：摘要保持完整Unicode码点
实际：substring结果含孤立代理项

判定理由：substring前后文边界未调整，虽然chunk/carry调整代理对，但命中附近emoji刚好跨match-100或尾部边界时可产生孤立代理项，输出摘要损坏；原文与继续游标未丢失。

核实范围：搜索摘录UTF16 substring可能切断合法emoji；原文不变。

代码：[HandoffReadService.java:260](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L260)；[HandoffReadService.java:268](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L268)；[HandoffReadService.java:313](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L313)

核验：[reproductions/RankingReadReproTest.java](reproductions/RankingReadReproTest.java)

### R03-C25

**跨分片查找每次扫描目录可能平方增长** — 成立 / conditional / scope=new / 不入分；统一问题：search-adjacent-catalog-quadratic；credit=None。

[原文 L99–L99](../KimiCode/KimiK3-session-merge-v2-review.md#L99)

````text
- `HandoffReadService` 命中片段截取可能切断代理对（`:261`）；跨分片续接每分片一次全目录扫描（O(n²)，有 15s 预算兜底）；
````

触发：
预期：
实际：

判定理由：resolve每次从catalog起点线性定位后继ref，很多分片时可能二次扫描；有15秒截止，未测得具体不可用规模。

代码：[HandoffReadService.java:176](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L176)；[HandoffReadService.java:280](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L280)

核验：代码静态核验及原文：见code_refs/lines

### R03-C26

**提交响应前无本地来源占用** — 成立 / conditional / scope=new / 不入分；统一问题：source-lock-before-post-response；credit=None。

[原文 L100–L100](../KimiCode/KimiK3-session-merge-v2-review.md#L100)

````text
- 前端：`selectMergeSourceIds` 在 POST 响应到达前不本地标记来源占用（服务端 gate 兜底）；protocol=1 降级无专项测试；`validatedTerminal` 跳过后续轮询导致 `targetAvailable` 可能陈旧；
````

触发：
预期：
实际：

判定理由：selector没有operation即空；客户端可能暂时显示可用但后台gate负责最终拒绝，与服务端锁权威设计一致，未证明新的独立功能缺陷。

代码：[sessionMergeStore.ts:28](../../../../../frontend/src/store/sessionMergeStore.ts#L28)；[sessionMergeStore.ts:132](../../../../../frontend/src/store/sessionMergeStore.ts#L132)；[SessionMergeService.java:95](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L95)

核验：代码静态核验及原文：见code_refs/lines

### R03-C27

**protocol1缺专项前端测试** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-ui-test-gap；credit=None。

[原文 L100–L100](../KimiCode/KimiK3-session-merge-v2-review.md#L100)

````text
- 前端：`selectMergeSourceIds` 在 POST 响应到达前不本地标记来源占用（服务端 gate 兜底）；protocol=1 降级无专项测试；`validatedTerminal` 跳过后续轮询导致 `targetAvailable` 可能陈旧；
````

触发：
预期：
实际：

判定理由：当前测试无protocolVersion=1专项断言；应补兼容性覆盖，不推出运行失败。

代码：[sessionMergeStore.test.ts:398](../../../../../frontend/src/store/sessionMergeStore.test.ts#L398)；[SessionMergePanel.test.tsx:40](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L40)

核验：代码静态核验及原文：见code_refs/lines

### R03-C28

**终态缓存导致targetAvailable陈旧** — 成立 / functional / scope=retained / 不入分；统一问题：terminal-target-availability-stale；credit=None。

[原文 L100–L100](../KimiCode/KimiK3-session-merge-v2-review.md#L100)

````text
- 前端：`selectMergeSourceIds` 在 POST 响应到达前不本地标记来源占用（服务端 gate 兜底）；protocol=1 降级无专项测试；`validatedTerminal` 跳过后续轮询导致 `targetAvailable` 可能陈旧；
````

触发：结果卡已展示且E随后在其他入口删除
预期：结果可用性随服务端更新
实际：缓存targetAvailable可保持true，打开动作失败

判定理由：已验证completed后refresh短路；外部删除E不再查询，按钮仍启用直至重新打开dialog清缓存，实际activate可能失败。；统一复核：基线b98已对所有终态停止查询，删除E后旧入口打开失败的用户后果已存在；新targetAvailable首验/重开还改善，不计本次新增回归。

代码：[sessionMergeStore.ts:141](../../../../../frontend/src/store/sessionMergeStore.ts#L141)；[sessionMergeStore.ts:119](../../../../../frontend/src/store/sessionMergeStore.ts#L119)；[SessionMergePanel.tsx:214](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L214)

核验：代码静态核验及原文：见code_refs/lines

### R03-C29

**控制器混用字段与构造注入** — 成立 / engineering / scope=new / 不入分；统一问题：controller-injection-style；credit=None。

[原文 L101–L101](../KimiCode/KimiK3-session-merge-v2-review.md#L101)

````text
- `QueryController`/`WebSocketController` 对 `HandoffContextService` 用字段注入而类内其他依赖为构造器注入（风格不一致，无功能影响）。
````

触发：
预期：
实际：

判定理由：风格建议无功能后果。

代码：[QueryController.java:178](../../../../../backend/src/main/java/com/aicodeassistant/controller/QueryController.java#L178)

核验：代码静态核验及原文：见code_refs/lines

### R03-C30

**具体同名工具和普通工具池安全保证** — 成立 / global / scope=new / 不入分；统一问题：builtin-tool-identity-isolation；credit=None。

[原文 L29–L30](../KimiCode/KimiK3-session-merge-v2-review.md#L29)

````text
   - `AuthorizationService.java:158` 仅将 `handoff-read-v1` 且 `RiskClass.SAFE` 且 effects 恰为 `[READ_RESOURCE]` 的操作纳入只读放行，**未加入 SAFE_INTERNAL**（§7.3 红线）；`OperationAnalyzerRegistry.java:87` 按 `instanceof` 分派，同名 MCP/动态工具分别走 `isMcp()`/`generic` 分支，不能继承权限（测试 `OperationAnalyzerRegistryTest.java:45-66` 佐证）。
   - `HandoffReadTool` 无任何 Spring 注解，全局唯一构造点是 `HandoffContextService`，`ToolRegistry` 只收集 Bean——普通主会话与子代理的工具集合、schema、token 开销不变（§7.2）。
````

触发：
预期：
实际：

判定理由：analyzer按真实类型分派，HandoffRead不是全局Bean，普通配置不调用handoff；这是具体安全机制核验，未发现所指路径放宽。

代码：[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)；[HandoffReadTool.java:10](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L10)；[HandoffContextService.java:39](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L39)

核验：代码静态核验及原文：见code_refs/lines

### R03-C31

**HandoffRead读路径拒绝穿越与符号链接** — 成立 / global / scope=new / 不入分；统一问题：handoff-read-path-containment；credit=None。

[原文 L64–L64](../KimiCode/KimiK3-session-merge-v2-review.md#L64)

````text
- §7.2 游标绑定包版本+参数、16KiB/8MiB 上限、15 秒截止与中断检查、UTF-8 边界分页、跨分片重叠去重、长行尾部不漏、路径穿越/symlink 防御、PLAN 模式只读——逐项核实通过且有测试；
````

触发：
预期：
实际：

判定理由：该段针对7.2读取，safeFile逐段拒symlink；不能外推为source复制每条分支均安全。

代码：[HandoffReadService.java:179](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L179)；[MergePackageService.java:304](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L304)

核验：代码静态核验及原文：见code_refs/lines

### R03-C32

**测试数字包含跳过项目的通过计数不自洽** — 不成立 / engineering / scope=out / 不入分；统一问题：test-count-arithmetic；credit=None。

[原文 L42–L42](../KimiCode/KimiK3-session-merge-v2-review.md#L42)

````text
| 后端全量 `./mvnw -Dspring.profiles.active=test test` | 2,975 项：**2,974 通过、1 失败、70 跳过**，1 分 21 秒 |
````

触发：
预期：
实际：

判定理由：2975总数、2974通过、1失败再加70跳过不相容；只能证明此行统计口径写错，不能据此推断没有跑测试或伪造日志。

代码：

核验：代码静态核验及原文：见code_refs/lines

## R04

[完整原报告](../Qoder/kimik3-session-merge-v2-review.md)

### R04-normal_function

**判断分：5/5**。[原文 L18–L27](../Qoder/kimik3-session-merge-v2-review.md#L18)。普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。

````text
## 二、问题 1：非合并分支链路影响评估 —— 结论：无破坏

逐链路确认隔离有效：

- **QueryLoopState**（`QueryLoopState.java:30-35`）：仅新增 `@JsonIgnore` 不序列化标记，无默认行为，符合文档第 10 节。
- **QueryEngine**（`QueryEngine.java:1810-1817`）：`handoffProjection` 在无标记时直接返回空投影，**不调用交接服务、不查合并表**；有标记时 phase1 用 `inputBudget - reserved` 装填，phase2 用**原总预算**校验含投影的真实 payload（`QueryEngine.java:950-952`），无双扣、无漏扣，符合 7.1 节第 3 条。`prepareCompactionContext` 同样扣预留，压缩/413 恢复路径规则一致。
- **ImageRefInjector**：旧签名委托给默认 `boundHandoffAsset=false` 谓词，普通路径权限判定逐字节不变（`ImageRefInjector.java:96-100, 256-269`）。仅当前可信根会话绑定包中已登记且哈希匹配的图片才放行。
- **QueryController 三个 REST 入口 / WebSocketController**：均以 `isMerged(metadata)` 纯 Map 静态判断为前提，普通会话零交接调用、零合并表查询；`toolUseContext` 均在 `configure` 之前构建（`QueryController.java:164-179`、`WebSocketController.java:917-959`），无 NPE 风险。PROMPT 路径传入 `allowedToolNames`，工具限制保留。
- **授权**：`handoff-read-v1` 的 analyzerId 只能由 `instanceof HandoffReadTool` 的真实内置工具获得（`OperationAnalyzerRegistry.java:87`）——同名 MCP 工具先被 `isMcp()` 拦截，同名动态工具落入 generic，不继承只读放行；分析（analyze）与执行前（recheck）双重校验绑定，绑定变化抛 `HANDOFF_BINDING_CHANGED`，符合 7.3 节。`AuthorizationService` 只对 analyzerId 为 `handoff-read-v1` 且 READ/SAFE 的操作自动放行，未简单加入 SAFE_INTERNAL。
- **普通/fork/Swarm 执行与保存机制**：`SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner` 等文件不在改动列表中，与 HEAD 一致，符合 4.1 节"不为合并改变普通执行与失败条件"。
````

### R04-boundary

**判断分：5/5**。[原文 L16–L27](../Qoder/kimik3-session-merge-v2-review.md#L16)。全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。

````text
说明：文档声称的隔离副本全量验证（后端 2,975 项、前端 103 文件 876 项）本次未完整重跑，以上为针对合并链路的定向验证。

## 二、问题 1：非合并分支链路影响评估 —— 结论：无破坏

逐链路确认隔离有效：

- **QueryLoopState**（`QueryLoopState.java:30-35`）：仅新增 `@JsonIgnore` 不序列化标记，无默认行为，符合文档第 10 节。
- **QueryEngine**（`QueryEngine.java:1810-1817`）：`handoffProjection` 在无标记时直接返回空投影，**不调用交接服务、不查合并表**；有标记时 phase1 用 `inputBudget - reserved` 装填，phase2 用**原总预算**校验含投影的真实 payload（`QueryEngine.java:950-952`），无双扣、无漏扣，符合 7.1 节第 3 条。`prepareCompactionContext` 同样扣预留，压缩/413 恢复路径规则一致。
- **ImageRefInjector**：旧签名委托给默认 `boundHandoffAsset=false` 谓词，普通路径权限判定逐字节不变（`ImageRefInjector.java:96-100, 256-269`）。仅当前可信根会话绑定包中已登记且哈希匹配的图片才放行。
- **QueryController 三个 REST 入口 / WebSocketController**：均以 `isMerged(metadata)` 纯 Map 静态判断为前提，普通会话零交接调用、零合并表查询；`toolUseContext` 均在 `configure` 之前构建（`QueryController.java:164-179`、`WebSocketController.java:917-959`），无 NPE 风险。PROMPT 路径传入 `allowedToolNames`，工具限制保留。
- **授权**：`handoff-read-v1` 的 analyzerId 只能由 `instanceof HandoffReadTool` 的真实内置工具获得（`OperationAnalyzerRegistry.java:87`）——同名 MCP 工具先被 `isMcp()` 拦截，同名动态工具落入 generic，不继承只读放行；分析（analyze）与执行前（recheck）双重校验绑定，绑定变化抛 `HANDOFF_BINDING_CHANGED`，符合 7.3 节。`AuthorizationService` 只对 analyzerId 为 `handoff-read-v1` 且 READ/SAFE 的操作自动放行，未简单加入 SAFE_INTERNAL。
- **普通/fork/Swarm 执行与保存机制**：`SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner` 等文件不在改动列表中，与 HEAD 一致，符合 4.1 节"不为合并改变普通执行与失败条件"。
````

### R04-release

**判断分：0/5**。[原文 L69–L77](../Qoder/kimik3-session-merge-v2-review.md#L69)。未明确回答当前能否作为功能完成版合入发布；允许提交/推送和披露语义未验收本身并非错误，不据此捏造赞成正式发布。

````text
## 五、发布结论

**已达到提交 GitHub 的标准**：非合并链路隔离干净且有测试证明（普通请求零交接调用、预算等价、原生 tool ID 不串来源）；合并链路严格按 v2 架构落地（封存即释放、持久进度、epoch fencing、原子发布、有界入口、只读工具授权）；定向结构测试与前端构建全绿；无禁用测试或放宽断言。

建议：

1. 提交信息中保留"真实模型语义验收未执行"的声明（文档第 9 节强制要求，不得以 stub 结果冒充语义验收）；
2. 中等问题 1–3 修复成本低，可在本次或紧随的提交中处理，不阻塞本次提交；
3. 测试覆盖缺口可列为后续任务，其中"E 对 A/B 状态隔离"和"准入拒绝"两类建议优先补齐。
````

### R04-semantic

**判断分：5/5**。[原文 L67–L67](../Qoder/kimik3-session-merge-v2-review.md#L67)。明确真实模型语义验收未完成，stub 测试不能替代。

````text
- **真实模型接续开发语义验收未执行**——文档明确 stub 结构测试不能替代语义评测，第 9 节的人工标注样本（含 A 改服务端字段、B 前端未联调的接续开发样本及"封存后共享代码又变化"变体）仍需另行执行。
````

### R04-C01

**普通路径与内置工具身份隔离** — 成立 / global / scope=new / 不入分；统一问题：ordinary-path-isolation；credit=None。

[原文 L22–L27](../Qoder/kimik3-session-merge-v2-review.md#L22)

````text
- **QueryLoopState**（`QueryLoopState.java:30-35`）：仅新增 `@JsonIgnore` 不序列化标记，无默认行为，符合文档第 10 节。
- **QueryEngine**（`QueryEngine.java:1810-1817`）：`handoffProjection` 在无标记时直接返回空投影，**不调用交接服务、不查合并表**；有标记时 phase1 用 `inputBudget - reserved` 装填，phase2 用**原总预算**校验含投影的真实 payload（`QueryEngine.java:950-952`），无双扣、无漏扣，符合 7.1 节第 3 条。`prepareCompactionContext` 同样扣预留，压缩/413 恢复路径规则一致。
- **ImageRefInjector**：旧签名委托给默认 `boundHandoffAsset=false` 谓词，普通路径权限判定逐字节不变（`ImageRefInjector.java:96-100, 256-269`）。仅当前可信根会话绑定包中已登记且哈希匹配的图片才放行。
- **QueryController 三个 REST 入口 / WebSocketController**：均以 `isMerged(metadata)` 纯 Map 静态判断为前提，普通会话零交接调用、零合并表查询；`toolUseContext` 均在 `configure` 之前构建（`QueryController.java:164-179`、`WebSocketController.java:917-959`），无 NPE 风险。PROMPT 路径传入 `allowedToolNames`，工具限制保留。
- **授权**：`handoff-read-v1` 的 analyzerId 只能由 `instanceof HandoffReadTool` 的真实内置工具获得（`OperationAnalyzerRegistry.java:87`）——同名 MCP 工具先被 `isMcp()` 拦截，同名动态工具落入 generic，不继承只读放行；分析（analyze）与执行前（recheck）双重校验绑定，绑定变化抛 `HANDOFF_BINDING_CHANGED`，符合 7.3 节。`AuthorizationService` 只对 analyzerId 为 `handoff-read-v1` 且 READ/SAFE 的操作自动放行，未简单加入 SAFE_INTERNAL。
- **普通/fork/Swarm 执行与保存机制**：`SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner` 等文件不在改动列表中，与 HEAD 一致，符合 4.1 节"不为合并改变普通执行与失败条件"。
````

触发：普通路径与内置工具身份隔离
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：对应门控、默认空投影、授权 instanceof 与基线未改代码可支持此限定判断。

判定理由：对应门控、默认空投影、授权 instanceof 与基线未改代码可支持此限定判断。

代码：[HandoffContextService.java:33](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L33)；[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)；[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)

核验：代码静态核验及原文：见code_refs/lines

### R04-C02

**history_storage_version 无生产写入方** — 成立 / engineering / scope=new / 不入分；统一问题：dead-history-storage-version；credit=None。

[原文 L48–L48](../Qoder/kimik3-session-merge-v2-review.md#L48)

````text
1. **死条件引用不存在的元数据键** — `MergePackageService.java:440`：`history_storage_version != 2` 才导出 checkpoint，但全仓库（含前端）无任何写入方，文档 4.1 节还明确禁止新增该写入。条件恒真、checkpoint 永远导出——功能上恰好符合规范，但属于误导性代码。建议删除条件或注释说明意图。
````

触发：history_storage_version 无生产写入方
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：当前代码无该字段生产写入；条件按现有资料通常恒真，不造成报告所述功能错误。

判定理由：当前代码无该字段生产写入；条件按现有资料通常恒真，不造成报告所述功能错误。

代码：[MergePackageService.java:440](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L440)

核验：代码静态核验及原文：见code_refs/lines

### R04-C03

**极小入口预算走通用失败而非容量错误** — 成立 / functional / scope=new / 入分；统一问题：D09；credit=1。

[原文 L49–L49](../Qoder/kimik3-session-merge-v2-review.md#L49)

````text
2. **极小模型下 E 会话每轮 500** — `HandoffContextService.java:74,78`：最小入口也放不下时抛 `IllegalStateException(HANDOFF_CONTEXT_BUDGET_TOO_SMALL)`，从引擎循环冒出变成 500，而非文档 7.1 节要求的"正常容量错误"；`prepareCompactionContext` 路径同样会抛。建议映射为现有容量错误类型（如 413 语义的 LlmApiException），且不因此把已完成合并置为失败。
````

触发：合并 E 的总历史预算 10% 小于 minimal 入口
预期：返回明确容量错误
实际：IllegalStateException 导致当轮失败；合并 completed 状态不变

判定理由：project 抛 IllegalStateException，queryLoop 外层捕获为普通执行失败；不是现有 CONTEXT_BUDGET_EXCEEDED 路径。报告“每轮500”不适用于所有流式入口，但通用错误这一定位成立。

核实范围：E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)；[QueryEngine.java:960](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L960)

核验：[reproductions/RankingQueryReproTest.java](reproductions/RankingQueryReproTest.java)

### R04-C04

**模型配置失效被归为通用准备失败** — 成立 / functional / scope=new / 入分；统一问题：D18；credit=1。

[原文 L50–L50](../Qoder/kimik3-session-merge-v2-review.md#L50)

````text
3. **模型失效的错误分类不精确** — `SessionMergeService.execute` 中 `summaries.select()` 抛中文 `IllegalArgumentException`，经 `safeCode` 归入 `MERGE_PREPARATION_FAILED`，恢复指引无法提示"换模型后恢复"。建议 select 失败映射到 `MERGE_MODEL_UNAVAILABLE`。
````

触发：创建后/崩溃重启时既有 resolvedModel 的能力配置不再可用
预期：MERGE_MODEL_UNAVAILABLE 与换模型指引
实际：MERGE_PREPARATION_FAILED

判定理由：execute 重新 select 时缺明确容量配置抛中文 IllegalArgumentException，经 safeCode 变 MERGE_PREPARATION_FAILED，丢失换模型指引。首次 start/resume select 在 worker 之前的错误不属于此路径。

核实范围：操作保存后模型能力配置不可用，worker重新select抛中文异常被压成通用准备失败。

代码：[MergeSummaryService.java:46](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L46)；[SessionMergeService.java:200](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L200)；[SessionMergeService.java:257](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L257)

核验：代码静态核验：见code_refs

### R04-C05

**attempt completed 改为 error 语义可澄清** — 成立 / engineering / scope=new / 不入分；统一问题：attempt-outcome-semantics；credit=None。

[原文 L51–L51](../Qoder/kimik3-session-merge-v2-review.md#L51)

````text
4. **attempt outcome 语义混淆** — MergeSummaryService 校验失败路径调用 `failAttempt`，会把传输已成功（completed）的 attempt 改为 error（`MergeProgressRepository.java:197-199`）。usage 保留、计费汇总不受影响，但 outcome 含义模糊，建议注释说明或区分 outcome 取值。
````

触发：attempt completed 改为 error 语义可澄清
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：调用传输成功但响应校验失败须将 attempt 标错，保留 usage；当前没有证明计费损坏。

判定理由：调用传输成功但响应校验失败须将 attempt 标错，保留 usage；当前没有证明计费损坏。

代码：[MergeProgressRepository.java:197](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L197)；[MergeSummaryService.java:224](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L224)

核验：代码静态核验及原文：见code_refs/lines

### R04-C06

**packageRoot 默认依赖 cwd** — 成立 / engineering / scope=new / 不入分；统一问题：package-root-resolver；credit=None。

[原文 L52–L52](../Qoder/kimik3-session-merge-v2-review.md#L52)

````text
5. **packageRoot 回退 user.dir** — `MergePackageService.java:20-27`：未配置 project root 时依赖进程 cwd，与文档"不硬编码 cwd"有出入；resolver 为 null 时用 PRAGMA 真实库文件兜底且 `requireOperationPath` 自洽，实际风险低，建议统一改为以实际打开的数据库文件为准。
````

触发：packageRoot 默认依赖 cwd
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：实际通过与 SQLite 配置共用 DatabaseResolver 解析工程库位置，配置优先；无错误目录触发案例，属于一致性建议。

判定理由：实际通过与 SQLite 配置共用 DatabaseResolver 解析工程库位置，配置优先；无错误目录触发案例，属于一致性建议。

代码：[MergePackageService.java:87](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L87)；[DatabaseResolver.java:42](../../../../../backend/src/main/java/com/aicodeassistant/config/database/DatabaseResolver.java#L42)

核验：代码静态核验及原文：见code_refs/lines

### R04-C07

**来源集合变化直接拒绝，不做两次重取** — 成立 / engineering / scope=new / 不入分；统一问题：source-tree-retry-policy；credit=None。

[原文 L56–L56](../Qoder/kimik3-session-merge-v2-review.md#L56)

````text
6. lockSources 树集合只重取一次（文档允许最多两次），属保守拒绝，可接受；`progress.create` 若并发违反唯一索引会 500 而非 409（单进程 synchronized 下概率极低）。
````

触发：来源集合变化直接拒绝，不做两次重取
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：代码发现集合变化即拒绝并释放，保守行为、可重试；未证明复制错误。

判定理由：代码发现集合变化即拒绝并释放，保守行为、可重试；未证明复制错误。

代码：[SessionMergeService.java:120](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L120)

核验：代码静态核验及原文：见code_refs/lines

### R04-C08

**多实例唯一名额冲突500** — 范围外 / conditional / scope=out / 不入分；统一问题：multi-instance-active-conflict；credit=None。

[原文 L56–L56](../Qoder/kimik3-session-merge-v2-review.md#L56)

````text
6. lockSources 树集合只重取一次（文档允许最多两次），属保守拒绝，可接受；`progress.create` 若并发违反唯一索引会 500 而非 409（单进程 synchronized 下概率极低）。
````

触发：多实例唯一名额冲突500
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：本机 synchronized 串行化；多应用实例共享用户库不在本次单实例契约内，不计范围内功能错误。

判定理由：本机 synchronized 串行化；多应用实例共享用户库不在本次单实例契约内，不计范围内功能错误。

代码：[SessionMergeService.java:81](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L81)；[MergeProgressRepository.java:35](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L35)

核验：代码静态核验及原文：见code_refs/lines

### R04-C09

**v2 运行时统一 paused** — 规范歧义 / engineering / scope=new / 不入分；统一问题：paused-without-failed-state；credit=None。

[原文 L57–L57](../Qoder/kimik3-session-merge-v2-review.md#L57)

````text
7. v2 任何失败一律 paused、永不 failed——文档允许"确认无法恢复才 failed"，此实现选择保守，符合"不虚构可恢复进度"精神，建议在提交说明中写明这一取舍。
````

触发：v2 运行时统一 paused
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：规范要求确认不可恢复才 failed，但未定义自动确认器；保守暂停且 cancel 可退出不等于永久卡死。

判定理由：规范要求确认不可恢复才 failed，但未定义自动确认器；保守暂停且 cancel 可退出不等于永久卡死。

代码：[SessionMergeService.java:218](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L218)；[session-merge-architecture-v2.md:352](../../../../session-merge-architecture-v2.md#L352)

核验：代码静态核验及原文：见code_refs/lines

### R04-C10

**注入/全限定名/未用参数风格** — 成立 / engineering / scope=new / 不入分；统一问题：injection-style；credit=None。

[原文 L58–L58](../Qoder/kimik3-session-merge-v2-review.md#L58)

````text
8. 风格不一致：QueryController/WebSocketController 用字段注入 + 行内全限定名，与类内 constructor-final 风格冲突；`SessionMergeService` 构造函数仍接收已弃用的 `PermissionModeManager` 参数；`isExplicitCoreTool("HandoffRead")` 按名称登记会让假想的同名内部 bean 绕过启动 analyzer 检查（当前无此 bean，仅理论风险）。
````

触发：注入/全限定名/未用参数风格
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：风格与维护建议，不是产品功能缺陷。

判定理由：风格与维护建议，不是产品功能缺陷。

代码：[SessionMergeService.java:62](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L62)；[QueryController.java:72](../../../../../backend/src/main/java/com/aicodeassistant/controller/QueryController.java#L72)

核验：代码静态核验及原文：见code_refs/lines

### R04-C11

**假想同名内部 bean 的启动检查绕过** — 证据不足 / conditional / scope=new / 不入分；统一问题：hypothetical-handoff-global-bean；credit=None。

[原文 L58–L58](../Qoder/kimik3-session-merge-v2-review.md#L58)

````text
8. 风格不一致：QueryController/WebSocketController 用字段注入 + 行内全限定名，与类内 constructor-final 风格冲突；`SessionMergeService` 构造函数仍接收已弃用的 `PermissionModeManager` 参数；`isExplicitCoreTool("HandoffRead")` 按名称登记会让假想的同名内部 bean 绕过启动 analyzer 检查（当前无此 bean，仅理论风险）。
````

触发：假想同名内部 bean 的启动检查绕过
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：当前 HandoffRead 不是 Bean；真实授权仍要求 instanceof。未来错误注册风险不是当前已发生越权。

判定理由：当前 HandoffRead 不是 Bean；真实授权仍要求 instanceof。未来错误注册风险不是当前已发生越权。

代码：[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)；[HandoffReadTool.java:10](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L10)

核验：代码静态核验及原文：见code_refs/lines

### R04-C12

**逐消息 SQL 与 substr 读取性能** — 成立 / engineering / scope=new / 不入分；统一问题：snapshot-query-count；credit=None。

[原文 L59–L59](../Qoder/kimik3-session-merge-v2-review.md#L59)

````text
9. 消息导出每条消息一次 SQL（LIMIT 1 分页）+ 每列 substr 流式读取，长历史下查询次数 ≈ 消息数；正确但可批量优化。
````

触发：逐消息 SQL 与 substr 读取性能
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：确实逐消息导出；没有量化证明不可用/违反预算，保留优化项。

判定理由：确实逐消息导出；没有量化证明不可用/违反预算，保留优化项。

代码：[MergePackageService.java:420](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L420)

核验：代码静态核验及原文：见code_refs/lines

### R04-C13

**准入/慢hook/隔离/入口重建及真实模型测试缺口** — 成立 / engineering / scope=new / 不入分；统一问题：merge-test-gaps；credit=None。

[原文 L63–L67](../Qoder/kimik3-session-merge-v2-review.md#L63)

````text
- 审批中 / 后台服务忙 / 第五来源忙的准入拒绝无测试；
- 慢 hook 发布竞态无测试；
- E 发消息 / 压缩 / 改待办不改 A/B 状态无直接测试；
- 小模型 / 多次压缩 / 413 后交接入口重建无测试；
- **真实模型接续开发语义验收未执行**——文档明确 stub 结构测试不能替代语义评测，第 9 节的人工标注样本（含 A 改服务端字段、B 前端未联调的接续开发样本及"封存后共享代码又变化"变体）仍需另行执行。
````

触发：准入/慢hook/隔离/入口重建及真实模型测试缺口
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：列举的是验证缺口；不能将未写测试直接当产品错误。真实模型未验收与规格一致。

判定理由：列举的是验证缺口；不能将未写测试直接当产品错误。真实模型未验收与规格一致。

代码：[session-merge-architecture-v2.md:440](../../../../session-merge-architecture-v2.md#L440)；[SessionMergeServiceTest.java:1](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L1)

核验：代码静态核验及原文：见code_refs/lines

## R05

[完整原报告](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)

### R05-normal_function

**判断分：5/5**。[原文 L45–L56](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L45)。明确未发现功能性破坏，普通标记早退/预算等价有据。

````text
### 结论：**未发现功能性破坏**

规范第 25 行要求"正常开发主链路优先"、第 362 行要求"普通分支不调用交接服务"。逐共享落点复核如下。

#### 1.1 `QueryEngine.java`（最高风险，已确认安全）

- `handoffProjection()`（:1808-1815）在 `state.getHandoffOperationId()==null` 时**立即返回** `Projection(List.of(),0)`，因此 `historyBudget == inputBudget`（:841-842），Phase1 清理与多模态装填的算术**逐字节等价**（:846、:860、:893 三处 `inputBudget` → `historyBudget` 的替换在 reservedTokens=0 时恒等）。
- 该运行标记只由 `HandoffContextService.configure()`（:43）设置，而 `configure()` 只在 `isMerged(metadata)` 为真时被调用（QueryController:178/265/362、WebSocketController:959）→ **普通会话永不进入该分支**。
- **索引位移风险已排除**：`runStartIndex` 是常量 `0`（:717），仅在 :908/:911 传给 `imageRefInjector.injectForApiCall`，两处都**早于** :940 的投影注入点，不存在因前置插入消息导致的下标错位。
- **持久化污染风险已排除**：`HandoffContextService.inject()`（:84-87）在投影为空时原样返回入参；非空时返回 `new ArrayList<>`。`apiReadyMessages`（:919/924/926）与 `state.getMessages()` 均未被改写 → 投影消息**不会被写库**，符合规范第 362 行"不写回 messages"。
- **预算双扣已排除**：Phase2（:951）仍用完整 `inputBudget` 校验含投影的真实 payload，符合规范第 368 行"phase2 用原总预算校验含参考投影的真实 payload，避免重复扣减"；`prepareCompactionContext`（:1835）扣减的是压缩路径预算，属不同层，不重复。
- `handoffContext` 字段用 `@Autowired(required=false)`（:1805），普通路径在解引用之前就返回，**不会 NPE**；扩展/Mock 场景下 Bean 缺失也不影响普通会话。
````

### R05-boundary

**判断分：5/5**。[原文 L261–L265](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L261)。明确mock级隔离证据不足、缺真实入口证据；全文承认共享落点和E性能风险，未用绿色测试证明绝对无风险。

````text
### 3.3 隔离证明的强度

**已确证**：§1.7 列出的 8 个基线文件 `git diff` 为空；"普通请求零交接调用"由 `QueryEngineUnitTest:1976`（`verifyNoInteractions(handoff)`，标记缺失）与 `HandoffContextServiceTest:28`（`verifyNoInteractions(repository,reads,subjects,tokens)` + `isSameAs(config)`）证明。

**强度不足**：两处证明均为 **mock 级**。**没有任何测试用真实临时 SQLite 验证普通请求不触碰合并表**；REST/WS 两个入口的交接门禁**完全无测试**。规范第 445 行的"普通无标记不调用交接服务，工具定义/历史/预算保持基线"目前只有单元级证据，缺少入口级证据。
````

### R05-release

**判断分：5/5**。[原文 L277–L284](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L277)。明确完成版不能发布，先修功能与覆盖并保持语义验收未完成标记；较严格的禁止推送措辞不算错误放行。

````text
### 不建议现在推送。最小放行清单：

1. **修 P1-1**（两行：`:436`/`:531` 的 gap reason 带上真实 recordRef）。这是唯一会让用户**丢失全部合并成果**的功能缺陷，且修复成本极低。
2. **补回 P1-2 的三个被删测试**（工具 ID 隔离、2~5 来源集合校验、hook 失败/慢 hook）。规范第 449 行是硬性要求，不是可选项。
3. **修 P2-1**（`importPrevious` 目标路径用 `safeFile(root,…)` 复核）与 **P2-2**（把轮询 `syncing` 与用户操作 `submitting` 拆成两个标志）。前者是全文件唯一违反规范明令禁止项的写入点；后者是用户最能直接感知的缺陷（卡死的合并无法取消）。
4. **修 P2-3 / P2-4**：让 E 在预算不足或绑定缺失时降级为普通会话或返回规范容量错误，而不是抛未处理异常。这两项决定 E 在边缘条件下是"变慢"还是"变砖"。
5. **修 P2-6 / P2-7**（DTO 必填化 + 运行时校验 + `control()` 容忍空响应体），并给 `SessionMergePanel` 加错误边界（P2-16）—— 否则一个畸形服务端响应能白屏整个应用。
6. **在提交信息 / PR 描述中如实写明 P1-3 的状态**："结构与数据隔离已自动验证；规范第 453 行的真实模型接续开发语义样本尚未执行。"文档第 9 行、第 455 行、第 471 行已经这样自我约束，提交说明**不能比文档更乐观**。
````

### R05-semantic

**判断分：5/5**。[原文 L164–L172](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L164)。明确缺真实模型/人工样本，不把数据隔离当接续决策验收。

````text
#### P1-3｜语义验收完全缺失（文档已自认，不可当作已完成）

规范第 453 行要求的接续开发样本 —— A 把服务端响应字段 `name` 改为 `displayName` 并记录验证结果；B 的前端仍读 `name` 且有未完成联调待办；A/B 共用同一临时工程，合并为 E 后要求其完成联调；再加"封存后共享代码又有变化"的变体 —— 在测试树中**完全不存在**：

- `backend/src/test/.../session/merge` 与 `HandoffContextServiceTest` 中 `displayName` **零出现**；
- `docs`、`backend/src/test`、`frontend/src` 全库检索"语义样本/人工标注/semantic acceptance"**只命中标准文档自身**，无任何评测产物、转录或报告；
- "E 更新待办不改 A/B"、"A 追加消息不改 E 资料"、"删除来源后 E 仍能读取已归档依据"三项均无测试（最接近的只有 `SessionMergeServiceTest:108-114` 验证截止边界后的来源消息不进入快照）。

文档第 471 行"真实模型的人工语义样本尚未执行"**至今仍成立**。因此本次改动只能表述为"结构、数据隔离与接线已验证"，**不得表述为接续开发能力已验收**——这也正是文档第 9 行、第 455 行反复自我约束的口径，提交信息不能比文档更乐观。
````

### R05-C01

**P1-1 gap引用堵死恢复** — 成立 / functional / scope=new / 入分；统一问题：D04；credit=1。

[原文 L119–L150](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L119)

````text
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
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：裸reason与invalid_image不匹配blockedRecords属实；损坏内容无法自动修复本身被规格允许，尚需受支持可调解析限制场景。报告把565携带archive ref称正确也不充分：恢复要求policy=blocked。不能把所有paused都说永远无法恢复。

判定理由：裸reason与invalid_image不匹配blockedRecords属实；损坏内容无法自动修复本身被规格允许，尚需受支持可调解析限制场景。报告把565携带archive ref称正确也不充分：恢复要求policy=blocked。不能把所有paused都说永远无法恢复。；统一复核：合成原件不变，启用Jackson现有ALLOW_SINGLE_QUOTES后已可解析，恢复仍在读raw前被缺ref挡住；提高物化上限的正控制能恢复。仅确认这类关联协议缺口，损坏资料允许暂停，不采用永久/所有恢复失效的泛化。

核实范围：已修正解析配置仍被gap/ref关联校验阻止；不等于所有坏原件应可恢复。

代码：[MergePackageService.java:235](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L235)；[MergePackageService.java:436](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L436)；[MergePackageService.java:531](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L531)；[MergePackageService.java:549](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L549)；[MergePackageService.java:565](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L565)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R05-C02

**P1-2有效测试删除未补** — 成立 / engineering / scope=new / 不入分；统一问题：admission-tool-id-hook-test-gaps；credit=None。

[原文 L152–L162](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L152)

````text
#### P1-2｜规范强制"保留并扩展"的测试被删除而非改写

规范第 449 行明确要求："保留并扩展原有磁盘、Unicode、路径/权限、幂等、事务不确定提交、**工具 ID 隔离**和前端恢复测试。"实际情况：

| 被删测试 | 规范要求 | 当前覆盖 |
|---|---|---|
| `sameSourceToolIdsNeverEnterENativeHistoryAndENextMessageNormalizes` | §9 第 445 行"原生 tool IDs 不串来源"；第 449 行明令保留并扩展 | **零覆盖，无替代** |
| `rejectsInvalidSourceSetsWithoutOccupyingSessions` | §5.2 第 220 行"2～5 个不同普通来源、主来源在集合内" | **零覆盖**（`SessionMergeService:165` 的校验逻辑无测试） |
| `uncertainCommitAndHookFailureCannotEraseCompletedTarget` | §9 第 442 行"慢 hook"；§8 第 415 行"通知 hook 失败不回滚已完成目标" | **零覆盖**，且 `MergeFixture.hooks` 已成无用夹具 |

规范第 449 行要求改写的五类旧断言（"缺 usage 必须失败"、"超过 16 次必须失败"、"所有来源锁到摘要结束"、"摘要失败必须清包"、"重启全部失败"）**已确证全部完成改写**：`SUMMARY_EXCEEDS_BUDGET`、`SUMMARY_CALL_BUDGET`、`MAX_CALL`、`3500` 在 `backend/src` 内**零命中**；`fiveSourcesStayOccupiedUntilSummaryExits…`、`summaryFailureCreatesNoTarget…`、`textFailureCleansPackage…`、`recoveryFailsInterruptedPreparation…` 均已被新语义用例替换（如 `SessionMergeServiceTest:152` 反向断言包**被保留**）。旧 CopyLimit 的"复制失败仍带警告发布"用例已删除，符合第 449 行末句。**问题只在于上述三项是删除而非改写。**
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：非法来源、工具ID隔离、hook失败/慢hook缺替代直接断言；已更新反向旧语义测试，不代表有效准入断言也应删。

判定理由：非法来源、工具ID隔离、hook失败/慢hook缺替代直接断言；已更新反向旧语义测试，不代表有效准入断言也应删。

代码：[SessionMergeServiceTest.java:69](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L69)；[SessionMergeServiceTest.java:146](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L146)；[SessionMergeServiceTest.java:170](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L170)

核验：代码静态核验及原文：见code_refs/lines

### R05-C03

**P1-3真实语义样本缺失** — 成立 / engineering / scope=new / 不入分；统一问题：semantic-acceptance-missing；credit=None。

[原文 L164–L172](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L164)

````text
#### P1-3｜语义验收完全缺失（文档已自认，不可当作已完成）

规范第 453 行要求的接续开发样本 —— A 把服务端响应字段 `name` 改为 `displayName` 并记录验证结果；B 的前端仍读 `name` 且有未完成联调待办；A/B 共用同一临时工程，合并为 E 后要求其完成联调；再加"封存后共享代码又有变化"的变体 —— 在测试树中**完全不存在**：

- `backend/src/test/.../session/merge` 与 `HandoffContextServiceTest` 中 `displayName` **零出现**；
- `docs`、`backend/src/test`、`frontend/src` 全库检索"语义样本/人工标注/semantic acceptance"**只命中标准文档自身**，无任何评测产物、转录或报告；
- "E 更新待办不改 A/B"、"A 追加消息不改 E 资料"、"删除来源后 E 仍能读取已归档依据"三项均无测试（最接近的只有 `SessionMergeServiceTest:108-114` 验证截止边界后的来源消息不进入快照）。

文档第 471 行"真实模型的人工语义样本尚未执行"**至今仍成立**。因此本次改动只能表述为"结构、数据隔离与接线已验证"，**不得表述为接续开发能力已验收**——这也正是文档第 9 行、第 455 行反复自我约束的口径，提交信息不能比文档更乐观。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：真实模型样本无证据且规范自认未执行；不将stub当真实接续。

判定理由：真实模型样本无证据且规范自认未执行；不将stub当真实接续。

代码：[session-merge-architecture-v2.md:3](../../../../session-merge-architecture-v2.md#L3)；[session-merge-architecture-v2.md:453](../../../../session-merge-architecture-v2.md#L453)

核验：代码静态核验及原文：见code_refs/lines

### R05-C04

**声称删除来源后读取无测试** — 不成立 / engineering / scope=new / 不入分；统一问题：source-deletion-test-missing-claim；credit=None。

[原文 L170–L170](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L170)

````text
- "E 更新待办不改 A/B"、"A 追加消息不改 E 资料"、"删除来源后 E 仍能读取已归档依据"三项均无测试（最接近的只有 `SessionMergeServiceTest:108-114` 验证截止边界后的来源消息不进入快照）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：HandoffReadServiceTest41直接删除A/B messages/sessions后search/read原文与详情；独立待办覆盖确实缺，但这一并列子项已覆盖。

判定理由：HandoffReadServiceTest41直接删除A/B messages/sessions后search/read原文与详情；独立待办覆盖确实缺，但这一并列子项已覆盖。

代码：[HandoffReadServiceTest.java:41](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L41)

核验：代码静态核验及原文：见code_refs/lines

### R05-C05

**P2-1祖先路径逃出staging** — 证据不足 / conditional / scope=new / 不入分；统一问题：ancestor-import-destination-containment；credit=None。

[原文 L180–L180](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L180)

````text
| P2-1 | `MergePackageService.java:~648-651` | **`importPrevious` 目标路径未复核（路径穿越）**。`relative = Path.of(entry.path())` **未规范化**；`"snapshot/a/../../evil.txt"` 能通过 `safeFile(previous,…)`（规范化后仍在 previous 内）、通过 `relative.startsWith("snapshot")`，`Path.of("snapshot").relativize(relative)` 保留内部 `..` 得到 `"a/../../evil.txt"`，`root.resolve(...)` 遂**写到暂存根之外**，`Files.createDirectories(destination.getParent())` 还会在包外建目录，随后 `budget.output(destination, CREATE_NEW)` 落盘。需祖先包被篡改（目录哈希受 DB `handoff_hash` 校验），可利用性低，但违反规范第 156 行明令禁止的路径穿越，且是**全文件唯一一处未用 `safeFile(root,…)` 复核的写入点**。修复：对 destination 再做一次 `safeFile(root, …)` 或规范化后校验 `startsWith(root)`。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：destination缺再次containment核验，所给路径词法可逃出staging；但当前生成器仅程序安全文件名，import前validateSnapshot与DB锚定哈希拒普通篡改。未给正常/仅模型可控触发，归防御加固风险，不算当前可利用产品漏洞。

判定理由：destination缺再次containment核验，所给路径词法可逃出staging；但当前生成器仅程序安全文件名，import前validateSnapshot与DB锚定哈希拒普通篡改。未给正常/仅模型可控触发，归防御加固风险，不算当前可利用产品漏洞。

代码：[MergePackageService.java:628](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L628)；[MergePackageService.java:643](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L643)；[MergePackageService.java:649](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L649)；[MergePackageService.java:304](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L304)

核验：代码静态核验及原文：见code_refs/lines

### R05-C06

**P2-2轮询暂时禁用恢复取消** — 成立 / functional / scope=new / 入分；统一问题：D08；credit=1。

[原文 L181–L181](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L181)

````text
| P2-2 | `sessionMergeStore.ts:144` + `SessionMergePanel.tsx:222,225` | **后台轮询间歇性锁死恢复/取消按钮**。`refresh()`（轮询）内 `set({ submitting: true })`，而"恢复合并"（:222）与"取消合并"（:225）均为 `disabled={submitting}`。轮询约每 2s 一次 → 按钮随之间歇性禁用；若 GET 挂起，最长 15s（:147 `setTimeout(() => controller.abort(), 15000)`）内**用户无法取消卡死的合并**。违反规范第 420 行（"网络状态单独用 syncing/error"）与第 425 行（"取消不带进度版本，不因后台进度更新被拒绝"）。修复：把轮询的 `syncing` 与用户操作的 `submitting` 拆成两个标志。 |
````

触发：恢复/取消时进度GET未返回
预期：用户操作有及时响应
实际：最长等轮询15秒

判定理由：refresh与控制共用submitting；GET挂起时panel禁用且control等inflight。

核实范围：轮询复用submitting禁用恢复/取消，程序cancel也等待GET结束；不表示后端永久不能取消。

代码：[sessionMergeStore.ts:144](../../../../../frontend/src/store/sessionMergeStore.ts#L144)；[sessionMergeStore.ts:147](../../../../../frontend/src/store/sessionMergeStore.ts#L147)；[sessionMergeStore.ts:227](../../../../../frontend/src/store/sessionMergeStore.ts#L227)；[SessionMergePanel.tsx:222](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L222)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R05-C07

**P2-3容量未分类并错误推断REST500** — 成立 / functional / scope=new / 入分；统一问题：D09；credit=0.5。

[原文 L182–L182](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L182)

````text
| P2-3 | `HandoffContextService.java:74,78` → `QueryEngine.java:841` | **预算不足抛未处理异常而非容量错误**。`project()` 抛 `IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL")`，而 :841 不在任何会将其转为容量错误的 try 内（最近的 `catch(RuntimeException)` 在 :1163，作用域仅包裹 LLM 调用且会 `throw e` 重抛）。规范第 371 行要求"连最小入口和当前请求都放不下时返回正常容量错误，不把已完成合并改为失败"。小窗口模型下 E 的每次请求都会变成未处理异常 → REST 500 / WS 通用错误。**仅影响 E，不影响普通会话。** |
````

触发：E最小投影超预算
预期：返回可识别容量错误
实际：通用查询错误；报告HTTP500控制流有误

判定理由：容量抛错确未映射正常容量错误；但execute外围catch包装QueryResult，controller正常分支返回200，不是报告所述execute没有catch。拟部分命中，集中复现确认。

核实范围：E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)；[QueryEngine.java:407](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L407)

核验：[reproductions/RankingQueryReproTest.java](reproductions/RankingQueryReproTest.java)

### R05-C08

**P2-4绑定丢失应降级普通会话** — 规范歧义 / conditional / scope=new / 不入分；统一问题：handoff-missing-binding-error；credit=None。

[原文 L183–L183](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L183)

````text
| P2-4 | `HandoffContextService.java:41-42` | **绑定缺失时 E 永久不可用，无降级路径**。`reads.binding()` 在无绑定时抛 `AuthorizationException("HANDOFF_NOT_BOUND")`（`HandoffReadService:47`）。E 的 metadata 标记仍在但 DB 绑定消失（合并行被删、库损坏、legacy 行）时，E **每次请求都失败**，无法退回普通会话。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：绑定丢失时拒读/拒启动符合可信绑定failclosed；规范不要求损坏E静默退普通。可改错误映射，但数据库行人工丢失不能证明正常功能回归。

判定理由：绑定丢失时拒读/拒启动符合可信绑定failclosed；规范不要求损坏E静默退普通。可改错误映射，但数据库行人工丢失不能证明正常功能回归。

代码：[HandoffContextService.java:41](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L41)；[HandoffReadService.java:47](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L47)；[session-merge-architecture-v2.md:398](../../../../session-merge-architecture-v2.md#L398)

核验：代码静态核验及原文：见code_refs/lines

### R05-C09

**P2-5 attempt反写终态造成迟到usage丢失** — 证据不足 / conditional / scope=new / 不入分；统一问题：late-usage-after-final-outcome；credit=None。

[原文 L184–L184](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L184)

````text
| P2-5 | `MergeProgressRepository.java:197-200` vs `:184` | **attempt 终态被覆写 + 迟到 usage 被丢弃**。`failAttempt` 会把已记为 `completed` 的 attempt 强制改写成 `error`（场景：流式成功但本地 schema 校验失败，`MergeSummaryService:231-233`）；随后到达的 usage 回调因 `finishAttempt` 只匹配 `outcome IN ('running','unknown')` 而被静默丢弃。违反规范第 232 行"迟到回调仅能幂等记录自己的 attempt usage"。方向安全（不会误改单元状态、不会重复计费），但**费用少计、终态失真**，与第 203 行"让失败/取消的调用仍可记账"的意图相悖。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：failAttempt将completed转error是对schema失败的补偿且保留已记usage；finishAttempt本身只running/unknown。实际usageSink仅call的finally执行一次，未给支持的后续回调再次记账路径，不能单由状态过滤证明该归因。

判定理由：failAttempt将completed转error是对schema失败的补偿且保留已记usage；finishAttempt本身只running/unknown。实际usageSink仅call的finally执行一次，未给支持的后续回调再次记账路径，不能单由状态过滤证明该归因。

代码：[MergeProgressRepository.java:177](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L177)；[MergeProgressRepository.java:197](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L197)；[MergeSummaryService.java:233](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L233)；[MergeSummaryService.java:333](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L333)

核验：代码静态核验及原文：见code_refs/lines

### R05-C10

**P2-6 DTO宽松与运行时校验不足** — 成立 / engineering / scope=new / 不入分；统一问题：frontend-dto-validation；credit=None。

[原文 L185–L185](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L185)

````text
| P2-6 | `sessionMergeStore.ts:16-19` | **DTO 与规范不符且零运行时校验**。规范第 253-268 行要求**必填**的 `protocolVersion`/`runEpoch`/`snapshotSealed`/`lockedSourceSessionIds`/`progress`/`canResume`/`canCancel`/`targetAvailable` 全部被声明为可选（`?`）；`protocolVersion?: number` 而非 `1 \| 2` 联合类型；:185-187 只校验 `operationId` 与 `request`，`status` 联合、`progress` 形状、数组性均未校验。`protocolVersion` 在 `SessionMergePanel.tsx` 中**从未被引用** → 规范第 427 行"protocol=1 只读展示旧结果，不伪造 v2 绑定/恢复能力"完全依赖服务端 `canResume`/`canCancel` 标志，无客户端纵深防御。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：字段可选且响应仅检查operationId/request，缺纵深校验事实成立；当前服务总返回类型化DTO，v1只读可由canResume/canCancel保障，未证明正常门禁被绕过。

判定理由：字段可选且响应仅检查operationId/request，缺纵深校验事实成立；当前服务总返回类型化DTO，v1只读可由canResume/canCancel保障，未证明正常门禁被绕过。

代码：[sessionMergeStore.ts:16](../../../../../frontend/src/store/sessionMergeStore.ts#L16)；[sessionMergeStore.ts:187](../../../../../frontend/src/store/sessionMergeStore.ts#L187)；[SessionMergeService.java:265](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L265)

核验：代码静态核验及原文：见code_refs/lines

### R05-C11

**P2-7幂等cancel空响应误报** — 范围外 / conditional / scope=new / 不入分；统一问题：cancel-empty-response；credit=None。

[原文 L186–L186](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L186)

````text
| P2-7 | `sessionMergeStore.ts:241` | **幂等 cancel 被误报为失败**。`control()` 无条件 `await response.json()`；规范第 244 行允许"已 cancelled 幂等返回"，若服务端返回 204/空体则 `json()` 抛错 → 被 catch → 向用户显示"操作失败，请重试"，尽管服务端已成功。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：当前Controller.cancel固定返回Operation，Service已cancelled也return state；协议没有约定cancel返回204，204仅active。缺少真实触发，不能把假想服务响应当当前幂等错误。

判定理由：当前Controller.cancel固定返回Operation，Service已cancelled也return state；协议没有约定cancel返回204，204仅active。缺少真实触发，不能把假想服务响应当当前幂等错误。；统一复核：当前cancel返回Operation；报告明确假设204/空体，属未采用协议的防御建议。

代码：[SessionMergeController.java:29](../../../../../backend/src/main/java/com/aicodeassistant/controller/SessionMergeController.java#L29)；[SessionMergeService.java:149](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L149)；[sessionMergeStore.ts:241](../../../../../frontend/src/store/sessionMergeStore.ts#L241)

核验：代码静态核验及原文：见code_refs/lines

### R05-C12

**P2-8大量旧build测试与在线覆盖缺口** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-helper-and-artifact-tests；credit=None。

[原文 L187–L187](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L187)

````text
| P2-8 | `MergePackageServiceTest.java` 约 13-16 处 | **大量测试跑在死代码上**。我已亲自确认 `build()`、`Bundle`、`packagePath(target,operation)`、`writeParts`、`PartWriter`、`exportRuns`、`renderBlock`、`MAX_RECORD_BYTES` 在 `backend/src/main` 下**零调用者**（符合文档第 5 行"旧 build/packagePath helper 仅为兼容已有夹具保留，在线入口不再调用"）。但 Unicode/二进制保真（:198）、checkpoint 去重（:317）、复制配额（:356/369/411）、磁盘余量、验证状态（:428/439）等断言全部跑在这条死路径上，**不能证明 v2 `seal()` 路径正确**。尤其：v2 替代守卫 `MERGE_COPY_INCOMPLETE`（`MergePackageService:576`，由 `:621` 触发）**无任何测试**；`exportSimpleRows("run_envelopes"…)`（:441）的产物导出路径**无断言**。存活路径为 `snapshotPath`（SessionMergeService:105）、`seal`/`validateSnapshot`/`recoverBlockedProjections`（:185-197）、`deleteUnreferenced`（:274）、`safeFile`/`projectionCatalogs`（HandoffReadService）。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：旧helper无在线调用，多复制/Unicode断言测build；缺v2 artifact/run导出及部分复制失败断言。不能据此宣称在线实现必错。

判定理由：旧helper无在线调用，多复制/Unicode断言测build；缺v2 artifact/run导出及部分复制失败断言。不能据此宣称在线实现必错。

代码：[MergePackageServiceTest.java:198](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L198)；[MergePackageServiceTest.java:356](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L356)；[SessionMergeService.java:190](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L190)

核验：代码静态核验及原文：见code_refs/lines

### R05-C13

**P2-9每轮多次清单哈希** — 证据不足 / conditional / scope=new / 不入分；统一问题：repeated-handoff-integrity-io；credit=None。

[原文 L188–L188](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L188)

````text
| P2-9 | `QueryEngine.java:841` + `:1835` | **每轮两次完整包哈希校验**。`handoffProjection` 在主循环（:841）与 `prepareCompactionContext`（:1835，每轮由 :843 调用）各执行一次 → 两次绑定查询 + 两次 `reads.brief(root)`（内含 `verify(ledger,budget)` 整包哈希链）。压缩/413 重入时更多。规范第 362 行禁止全局缓存，但**同一轮内复用一次投影结果**不违反该约束。仅影响 E 的性能。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：相同请求多个project确实重复查库/校验清单；未量化用户延迟，也不是全部资料正文反复hash。

判定理由：相同请求多个project确实重复查库/校验清单；未量化用户延迟，也不是全部资料正文反复hash。

代码：[HandoffContextService.java:65](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L65)；[HandoffReadService.java:69](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L69)；[QueryEngine.java:841](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L841)；[QueryEngine.java:1835](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1835)

核验：代码静态核验及原文：见code_refs/lines

### R05-C14

**P2-10a提取聚合提示词不细** — 成立 / engineering / scope=new / 不入分；统一问题：extraction-aggregation-prompt-guidance；credit=None。

[原文 L189–L189](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L189)

````text
| P2-10 | `MergeSummaryService.java:33-42,90,97,139-146,162` | **提示词与降容标注不达规范要求**。(a) 提取与聚合**共用同一份提示词**，未包含 §6.1 表格（第 282-287 行）中 `state_conclusions` 的具体要求（各来源工作范围、已完成/未完成状态、关键决策、结论保留来源依据），也无聚合语义（有界 brief、详情入口、每来源覆盖）；(b) 降容兜底（:139-145）只打印单元/栏目计数，**没有规范第 321 行要求的"还有 N 项未展开"标注**，状态计数只进 `overview.json`（:146）不进可见 brief；(c) 单单元场景 `brief = readResult(...)`（:90）→ `handoff.md` 会嵌入机器 JSON `{"schemaVersion":2,"items":...}` 而非可读概览，违反第 317 行；(d) `pieces(...,"",content)`（:97）导致 `details.jsonl` 聚合行 `sourceId=""`（:130/134），**丢失来源归属**，与第 289 行"冲突双方保留来源"相悖；(e) `planAggregate` 的 `inputHash = sha256(encode(in))`（:162）**未绑定 snapshotHash/PROCESSOR_VERSION**（提取单元在 :79 有绑定），弱于第 313 行要求；(f) 聚合再分片 `pieces()` 按 4096 字符切且**零重叠**（:97,164-172），不符合第 313 行"保留相邻引用和少量上下文重叠"。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：共用PROMPT有栏目和状态但不完全列明规范细节，属于提示和语义验收建议；不能单靠提示词字面证明模型必漏。

判定理由：共用PROMPT有栏目和状态但不完全列明规范细节，属于提示和语义验收建议；不能单靠提示词字面证明模型必漏。

代码：[MergeSummaryService.java:33](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L33)；[MergeSummaryService.java:222](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L222)；[session-merge-architecture-v2.md:317](../../../../session-merge-architecture-v2.md#L317)

核验：代码静态核验及原文：见code_refs/lines

### R05-C15

**P2-10b降容未展开计数文案** — 成立 / engineering / scope=new / 不入分；统一问题：brief-unexpanded-count-guidance；credit=None。

[原文 L189–L189](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L189)

````text
| P2-10 | `MergeSummaryService.java:33-42,90,97,139-146,162` | **提示词与降容标注不达规范要求**。(a) 提取与聚合**共用同一份提示词**，未包含 §6.1 表格（第 282-287 行）中 `state_conclusions` 的具体要求（各来源工作范围、已完成/未完成状态、关键决策、结论保留来源依据），也无聚合语义（有界 brief、详情入口、每来源覆盖）；(b) 降容兜底（:139-145）只打印单元/栏目计数，**没有规范第 321 行要求的"还有 N 项未展开"标注**，状态计数只进 `overview.json`（:146）不进可见 brief；(c) 单单元场景 `brief = readResult(...)`（:90）→ `handoff.md` 会嵌入机器 JSON `{"schemaVersion":2,"items":...}` 而非可读概览，违反第 317 行；(d) `pieces(...,"",content)`（:97）导致 `details.jsonl` 聚合行 `sourceId=""`（:130/134），**丢失来源归属**，与第 289 行"冲突双方保留来源"相悖；(e) `planAggregate` 的 `inputHash = sha256(encode(in))`（:162）**未绑定 snapshotHash/PROCESSOR_VERSION**（提取单元在 :79 有绑定），弱于第 313 行要求；(f) 聚合再分片 `pieces()` 按 4096 字符切且**零重叠**（:97,164-172），不符合第 313 行"保留相邻引用和少量上下文重叠"。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：header有单元/栏目数与需继续读取说明，但未明确还有N项未展开和可见状态计数；字面规范偏差，详情仍保留。

判定理由：header有单元/栏目数与需继续读取说明，但未明确还有N项未展开和可见状态计数；字面规范偏差，详情仍保留。

代码：[MergeSummaryService.java:139](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L139)；[MergeSummaryService.java:145](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L145)；[session-merge-architecture-v2.md:321](../../../../session-merge-architecture-v2.md#L321)

核验：代码静态核验及原文：见code_refs/lines

### R05-C16

**P2-10c brief嵌入JSON必不符合** — 规范歧义 / engineering / scope=new / 不入分；统一问题：brief-json-presentation；credit=None。

[原文 L189–L189](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L189)

````text
| P2-10 | `MergeSummaryService.java:33-42,90,97,139-146,162` | **提示词与降容标注不达规范要求**。(a) 提取与聚合**共用同一份提示词**，未包含 §6.1 表格（第 282-287 行）中 `state_conclusions` 的具体要求（各来源工作范围、已完成/未完成状态、关键决策、结论保留来源依据），也无聚合语义（有界 brief、详情入口、每来源覆盖）；(b) 降容兜底（:139-145）只打印单元/栏目计数，**没有规范第 321 行要求的"还有 N 项未展开"标注**，状态计数只进 `overview.json`（:146）不进可见 brief；(c) 单单元场景 `brief = readResult(...)`（:90）→ `handoff.md` 会嵌入机器 JSON `{"schemaVersion":2,"items":...}` 而非可读概览，违反第 317 行；(d) `pieces(...,"",content)`（:97）导致 `details.jsonl` 聚合行 `sourceId=""`（:130/134），**丢失来源归属**，与第 289 行"冲突双方保留来源"相悖；(e) `planAggregate` 的 `inputHash = sha256(encode(in))`（:162）**未绑定 snapshotHash/PROCESSOR_VERSION**（提取单元在 :79 有绑定），弱于第 313 行要求；(f) 聚合再分片 `pieces()` 按 4096 字符切且**零重叠**（:97,164-172），不符合第 313 行"保留相邻引用和少量上下文重叠"。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：单个详细结果作为brief的确是JSON，但规范317要求有界概览未禁止结构化JSON；可读性可改，不能由格式认定功能不正确。

判定理由：单个详细结果作为brief的确是JSON，但规范317要求有界概览未禁止结构化JSON；可读性可改，不能由格式认定功能不正确。

代码：[MergeSummaryService.java:90](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L90)；[MergeSummaryService.java:144](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L144)；[session-merge-architecture-v2.md:317](../../../../session-merge-architecture-v2.md#L317)

核验：代码静态核验及原文：见code_refs/lines

### R05-C17

**P2-10d聚合sourceId为空导致来源丢失** — 规范歧义 / engineering / scope=new / 不入分；统一问题：aggregate-empty-source-attribution；credit=None。

[原文 L189–L189](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L189)

````text
| P2-10 | `MergeSummaryService.java:33-42,90,97,139-146,162` | **提示词与降容标注不达规范要求**。(a) 提取与聚合**共用同一份提示词**，未包含 §6.1 表格（第 282-287 行）中 `state_conclusions` 的具体要求（各来源工作范围、已完成/未完成状态、关键决策、结论保留来源依据），也无聚合语义（有界 brief、详情入口、每来源覆盖）；(b) 降容兜底（:139-145）只打印单元/栏目计数，**没有规范第 321 行要求的"还有 N 项未展开"标注**，状态计数只进 `overview.json`（:146）不进可见 brief；(c) 单单元场景 `brief = readResult(...)`（:90）→ `handoff.md` 会嵌入机器 JSON `{"schemaVersion":2,"items":...}` 而非可读概览，违反第 317 行；(d) `pieces(...,"",content)`（:97）导致 `details.jsonl` 聚合行 `sourceId=""`（:130/134），**丢失来源归属**，与第 289 行"冲突双方保留来源"相悖；(e) `planAggregate` 的 `inputHash = sha256(encode(in))`（:162）**未绑定 snapshotHash/PROCESSOR_VERSION**（提取单元在 :79 有绑定），弱于第 313 行要求；(f) 聚合再分片 `pieces()` 按 4096 字符切且**零重叠**（:97,164-172），不符合第 313 行"保留相邻引用和少量上下文重叠"。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：聚合输入跨来源，sourceId空不删除叶子来源；evidence保留detail:child@范围可逐层回溯，所有leaf详细条目仍按sourceId可搜索。不能把聚合多来源字段空等同冲突证据来源丢失。

判定理由：聚合输入跨来源，sourceId空不删除叶子来源；evidence保留detail:child@范围可逐层回溯，所有leaf详细条目仍按sourceId可搜索。不能把聚合多来源字段空等同冲突证据来源丢失。；统一复核：聚合sourceId空是事实，完整evidence链仍在；单行归属的规范含义不清，不罚资料丢失FP。

代码：[MergeSummaryService.java:97](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L97)；[MergeSummaryService.java:130](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L130)；[MergeSummaryService.java:162](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L162)；[MergeSummaryService.java:284](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L284)；[session-merge-architecture-v2.md:319](../../../../session-merge-architecture-v2.md#L319)

核验：代码静态核验及原文：见code_refs/lines

### R05-C18

**P2-10e聚合hash未含快照和处理器** — 规范歧义 / engineering / scope=new / 不入分；统一问题：aggregate-hash-binding；credit=None。

[原文 L189–L189](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L189)

````text
| P2-10 | `MergeSummaryService.java:33-42,90,97,139-146,162` | **提示词与降容标注不达规范要求**。(a) 提取与聚合**共用同一份提示词**，未包含 §6.1 表格（第 282-287 行）中 `state_conclusions` 的具体要求（各来源工作范围、已完成/未完成状态、关键决策、结论保留来源依据），也无聚合语义（有界 brief、详情入口、每来源覆盖）；(b) 降容兜底（:139-145）只打印单元/栏目计数，**没有规范第 321 行要求的"还有 N 项未展开"标注**，状态计数只进 `overview.json`（:146）不进可见 brief；(c) 单单元场景 `brief = readResult(...)`（:90）→ `handoff.md` 会嵌入机器 JSON `{"schemaVersion":2,"items":...}` 而非可读概览，违反第 317 行；(d) `pieces(...,"",content)`（:97）导致 `details.jsonl` 聚合行 `sourceId=""`（:130/134），**丢失来源归属**，与第 289 行"冲突双方保留来源"相悖；(e) `planAggregate` 的 `inputHash = sha256(encode(in))`（:162）**未绑定 snapshotHash/PROCESSOR_VERSION**（提取单元在 :79 有绑定），弱于第 313 行要求；(f) 聚合再分片 `pieces()` 按 4096 字符切且**零重叠**（:97,164-172），不符合第 313 行"保留相邻引用和少量上下文重叠"。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：哈希仅inputrefs是真的；unit主键含operation_id，prepare先核验PROCESSOR_VERSION，同operation快照不可变，缺少文字字段不证明跨快照复用。

判定理由：哈希仅inputrefs是真的；unit主键含operation_id，prepare先核验PROCESSOR_VERSION，同operation快照不可变，缺少文字字段不证明跨快照复用。

代码：[MergeSummaryService.java:65](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L65)；[MergeSummaryService.java:162](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L162)；[MergeProgressRepository.java:139](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L139)

核验：代码静态核验及原文：见code_refs/lines

### R05-C19

**P2-10f聚合pieces无重叠** — 成立 / engineering / scope=new / 不入分；统一问题：aggregate-piece-overlap；credit=None。

[原文 L189–L189](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L189)

````text
| P2-10 | `MergeSummaryService.java:33-42,90,97,139-146,162` | **提示词与降容标注不达规范要求**。(a) 提取与聚合**共用同一份提示词**，未包含 §6.1 表格（第 282-287 行）中 `state_conclusions` 的具体要求（各来源工作范围、已完成/未完成状态、关键决策、结论保留来源依据），也无聚合语义（有界 brief、详情入口、每来源覆盖）；(b) 降容兜底（:139-145）只打印单元/栏目计数，**没有规范第 321 行要求的"还有 N 项未展开"标注**，状态计数只进 `overview.json`（:146）不进可见 brief；(c) 单单元场景 `brief = readResult(...)`（:90）→ `handoff.md` 会嵌入机器 JSON `{"schemaVersion":2,"items":...}` 而非可读概览，违反第 317 行；(d) `pieces(...,"",content)`（:97）导致 `details.jsonl` 聚合行 `sourceId=""`（:130/134），**丢失来源归属**，与第 289 行"冲突双方保留来源"相悖；(e) `planAggregate` 的 `inputHash = sha256(encode(in))`（:162）**未绑定 snapshotHash/PROCESSOR_VERSION**（提取单元在 :79 有绑定），弱于第 313 行要求；(f) 聚合再分片 `pieces()` 按 4096 字符切且**零重叠**（:97,164-172），不符合第 313 行"保留相邻引用和少量上下文重叠"。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：pieces按4096字符不重叠；split失败缩小才带overlap，规范有少量重叠要求。完整片段均保留，未量化语义后果。

判定理由：pieces按4096字符不重叠；split失败缩小才带overlap，规范有少量重叠要求。完整片段均保留，未量化语义后果。

代码：[MergeSummaryService.java:164](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L164)；[MergeSummaryService.java:272](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L272)；[session-merge-architecture-v2.md:313](../../../../session-merge-architecture-v2.md#L313)

核验：代码静态核验及原文：见code_refs/lines

### R05-C20

**P2-11 context子串误判配置鉴权** — 证据不足 / conditional / scope=new / 不入分；统一问题：context-error-substring-classification；credit=None。

[原文 L190–L190](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L190)

````text
| P2-11 | `MergeSummaryService.java:237-238` | **按子串判定容量失败**。errorType 含 `"context"` 或 message 含 `"context length"` 即视为 `capacityFailure` → 走二分而非暂停。非可重试的配置/鉴权错误若文案恰含 "context" 会被误判，**白烧调用次数**（每次二分都是真实计费请求）。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：宽泛errorType包含context可走split；未提供本项目provider实际输出此类非容量错误的样本，构造任意字符串不足认定常见计费错误。

判定理由：宽泛errorType包含context可走split；未提供本项目provider实际输出此类非容量错误的样本，构造任意字符串不足认定常见计费错误。

代码：[MergeSummaryService.java:237](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L237)

核验：代码静态核验及原文：见code_refs/lines

### R05-C21

**P2-12a错误message绝对路径** — 证据不足 / conditional / scope=new / 不入分；统一问题：handoff-error-path-disclosure；credit=None。

[原文 L191–L191](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L191)

````text
| P2-12 | `HandoffReadTool.java:45`、`:35-38` | **错误信息泄漏绝对路径 + ImageIO 空流崩溃**。(a) :45 直接把 `e.getMessage()` 返回给模型，`NoSuchFileException`/`AccessDeniedException`（如哈希校验与打开之间文件被删）会把**包目录绝对路径**带入模型上下文；`AuthorizationException`（:208/:230 抛出）是 RuntimeException，越过 `call()` 的 IOException catch。(b) :35-38 当 `ImageIO.createImageInputStream` 返回 null 时 `getImageReaders(null)` 抛 `IllegalArgumentException`，同样越过 catch，未给出规范第 390 行要求的"不支持类型返回明确说明"。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：IOException原message被透出，TOCTOU文件消失可能携路径；本地助手本已获目录且相关包元数据含路径，未明确保密边界/实际样本。建议规范化错误不作当前数据泄漏定论。

判定理由：IOException原message被透出，TOCTOU文件消失可能携路径；本地助手本已获目录且相关包元数据含路径，未明确保密边界/实际样本。建议规范化错误不作当前数据泄漏定论。

代码：[HandoffReadTool.java:45](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L45)；[HandoffReadService.java:208](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L208)

核验：代码静态核验及原文：见code_refs/lines

### R05-C22

**P2-12b ImageIO空stream异常** — 证据不足 / conditional / scope=new / 不入分；统一问题：imageio-null-stream；credit=None。

[原文 L191–L191](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L191)

````text
| P2-12 | `HandoffReadTool.java:45`、`:35-38` | **错误信息泄漏绝对路径 + ImageIO 空流崩溃**。(a) :45 直接把 `e.getMessage()` 返回给模型，`NoSuchFileException`/`AccessDeniedException`（如哈希校验与打开之间文件被删）会把**包目录绝对路径**带入模型上下文；`AuthorizationException`（:208/:230 抛出）是 RuntimeException，越过 `call()` 的 IOException catch。(b) :35-38 当 `ImageIO.createImageInputStream` 返回 null 时 `getImageReaders(null)` 抛 `IllegalArgumentException`，同样越过 catch，未给出规范第 390 行要求的"不支持类型返回明确说明"。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：getImageReaders(null)可抛IllegalArgumentException且仅catch IOException；asset已校验真实普通文件，触发还需后续文件竞态/平台stream失败。建议空判，未证明支持附件稳定触发。

判定理由：getImageReaders(null)可抛IllegalArgumentException且仅catch IOException；asset已校验真实普通文件，触发还需后续文件竞态/平台stream失败。建议空判，未证明支持附件稳定触发。

代码：[HandoffReadTool.java:35](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L35)；[HandoffReadService.java:195](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L195)

核验：代码静态核验及原文：见code_refs/lines

### R05-C23

**P2-13包目录硬编码cwd** — 不成立 / engineering / scope=new / 不入分；统一问题：package-root-cwd；credit=None。

[原文 L192–L192](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L192)

````text
| P2-13 | `MergePackageService.java:89` | **包根路径由 `user.dir` 派生**。`packageRoot()` 调 `databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")))`，而 `DatabaseResolver:43-47` 在 `zhikuncode.database.project-root` 为空（该属性默认值确为空）时**回退到传入的 projectRoot**，即 JVM 启动目录。字面违反规范第 120 行"不硬编码 cwd"。**但需公允指出**：这是本仓库既有约定 —— `CheckpointService:50`、`ArtifactManifestService:48`、`RunControlService:53`、`PermissionGrantRepository:67`、`DurableInteractionService:70`、`WorkbenchRunLinkService:41`、`DataCleanupScheduler:30` 七处同样写法，且 `SqliteConfig:75` 的 DataSource 同源解析，故 DB 与包目录会一起移动，实际风险低于表面。真正稳健的做法是 `databaseResolver==null` 分支已使用的 `PRAGMA database_list`（:90-92）—— 建议生产分支也改用它。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：实际通过DatabaseResolver按与DataSource相同配置解析项目DB，再取父目录；规范禁止直接硬编码cwd包路径，此实现恰满足从实际库派生的意图。报告自己确认同源。

判定理由：实际通过DatabaseResolver按与DataSource相同配置解析项目DB，再取父目录；规范禁止直接硬编码cwd包路径，此实现恰满足从实际库派生的意图。报告自己确认同源。

代码：[MergePackageService.java:87](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L87)；[DatabaseResolver.java:42](../../../../../backend/src/main/java/com/aicodeassistant/config/database/DatabaseResolver.java#L42)

核验：代码静态核验及原文：见code_refs/lines

### R05-C24

**P2-14非法UTF8中断seal** — 规范歧义 / functional / scope=new / 不入分；统一问题：malformed-db-utf8-raw-retention；credit=None。

[原文 L193–L193](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L193)

````text
| P2-14 | `MergePackageService.java:145-146,386-397` | **非法 UTF-8 终止整个合并**。REPORT 模式解码器遇畸形字节会在 `rawColumns` 中途抛出，**绕过 per-record blocked 路径**直接冒泡终止整次 seal。规范第 164 行要求"不能安全解析的记录先保存 raw 并记阻断原因，不为解决解析问题继续占用来源"。残留 `raw/tmp-*.json` 只能靠重做 staging 清理。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：Decoder REPORT会在写raw过程中失败，完整原始字节无法按TEXT解析保存；支持范围接受已损坏材料暂停，是否必须逐字节归档损坏SQLite TEXT无明确协议。不是普通长文本故障。

判定理由：Decoder REPORT会在写raw过程中失败，完整原始字节无法按TEXT解析保存；支持范围接受已损坏材料暂停，是否必须逐字节归档损坏SQLite TEXT无明确协议。不是普通长文本故障。

代码：[MergePackageService.java:145](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L145)；[MergePackageService.java:386](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L386)；[session-merge-architecture-v2.md:27](../../../../session-merge-architecture-v2.md#L27)

核验：代码静态核验及原文：见code_refs/lines

### R05-C25

**P2-15 descendants全表读性能** — 范围外 / conditional / scope=retained / 不入分；统一问题：descendant-full-scan；credit=None。

[原文 L194–L194](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L194)

````text
| P2-15 | `MergePackageService.java:100-115` | **`descendants()` 无分页全表读取**。加载所有 session 的 `metadata_json` 并在每次不动点迭代中重新解析全部行，且**在持有合并 token 期间**执行 → 大会话库下延长来源占用时间，与规范第 160 行"不跨文件复制或 LLM 调用持有数据库写事务"的精神相悖（虽为只读）。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：该实现与b98基线逐字相同，报告没有新增负载/调用放大证据；分页优化不是本次新增产品缺陷。

判定理由：该实现与b98基线逐字相同，报告没有新增负载/调用放大证据；分页优化不是本次新增产品缺陷。

代码：[MergePackageService.java:100](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L100)

核验：代码静态核验及原文：见code_refs/lines

### R05-C26

**P2-16畸形warnings白屏** — 证据不足 / conditional / scope=new / 不入分；统一问题：malformed-operation-ui-payload；credit=None。

[原文 L195–L195](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L195)

````text
| P2-16 | `App.tsx:490` + P2-6 | **无错误边界，畸形 payload 可崩整个 App**。`<SessionMergePanel/>` 裸渲染，叠加 DTO 零校验：若 `result.warnings` 是 truthy 非数组但带 `.length`，`Panel:211 .map()` 会在渲染期抛错 → 整应用白屏。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：truthy非数组warnings可使map抛错，但typed本地后端从不产生此shape，需网关/存储篡改；缺客户端校验是加固建议，不证明正常主链路回归。

判定理由：truthy非数组warnings可使map抛错，但typed本地后端从不产生此shape，需网关/存储篡改；缺客户端校验是加固建议，不证明正常主链路回归。

代码：[SessionMergePanel.tsx:210](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L210)；[sessionMergeStore.ts:185](../../../../../frontend/src/store/sessionMergeStore.ts#L185)

核验：代码静态核验及原文：见code_refs/lines

### R05-C27

**P2-17通知同key重复** — 成立 / functional / scope=new / 入分；统一问题：D22；credit=1。

[原文 L196–L196](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L196)

````text
| P2-17 | `sessionMergeStore.ts:200-203` | **通知未按 key 去重**。`notificationStore.addNotification`（:32-42）只 push，store 未先 `removeNotification('merge-'+id)`；反复暂停/失败会堆叠同 key toast（React 重复 key 警告）。`changed` 守卫只覆盖了常见路径。规范第 426 行要求"通知去重"。焦点未被抢占（被动状态更新），该部分合规。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：notificationStore push及merge终态同key通知在b98已存在；本次paused语义可能增加出现机会，但未给独立新增后果。保留问题，不计当前改动TP。

判定理由：notificationStore push及merge终态同key通知在b98已存在；本次paused语义可能增加出现机会，但未给独立新增后果。保留问题，不计当前改动TP。；统一复核：旧push助手未改，但v2新增恢复后再暂停让同一operation同key再次通知可达；与R12/R14/R16统一。

核实范围：5秒通知仍在期间，新增paused→resume→paused可推送同key通知两份。

代码：[sessionMergeStore.ts:200](../../../../../frontend/src/store/sessionMergeStore.ts#L200)；[notificationStore.ts:32](../../../../../frontend/src/store/notificationStore.ts#L32)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R05-C28

**P2-18 manifest未直接含cutoff** — 成立 / engineering / scope=new / 不入分；统一问题：manifest-cutoff-placement；credit=None。

[原文 L197–L197](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L197)

````text
| P2-18 | `MergePackageService.java:160-169` | **`manifest.json` 缺截止边界**。规范第 122 行要求 manifest 含"版本、操作/来源 ID、**截止边界**、清单哈希/计数"。实际写入了 schemaVersion/operationId/snapshotVersion/sources/capturedAt/counts/catalog-hashes，但每来源的消息截止（lastSeq/count，:411 计算）只埋在 session raw 记录里（:413-415），未进 manifest。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：cutoff在session raw且被目录哈希覆盖，manifest仅根sources；格式与规范树有差异，但截止资料并未丢失。

判定理由：cutoff在session raw且被目录哈希覆盖，manifest仅根sources；格式与规范树有差异，但截止资料并未丢失。

代码：[MergePackageService.java:160](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L160)；[MergePackageService.java:413](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L413)；[session-merge-architecture-v2.md:126](../../../../session-merge-architecture-v2.md#L126)

核验：代码静态核验及原文：见code_refs/lines

### R05-C29

**P2-19多completed绑定不确定** — 范围外 / conditional / scope=new / 不入分；统一问题：duplicate-target-binding-order；credit=None。

[原文 L198–L198](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L198)

````text
| P2-19 | `MergeProgressRepository.java:54-55` | **绑定查询 `findFirst()` 无 `ORDER BY`**。若某会话曾存在两条 completed 的 v2 合并记录（规范第 205 行以 `target_session_id` UNIQUE 防止，但旧行/异常数据下不能绝对排除），绑定结果不确定。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：V026 target_session_id NOT NULL UNIQUE覆盖旧新行；旧重复数据迁移回滚而不是保留不确定绑定。报告假设绕过约束的异常DB不能证明正常查询需ORDER BY。

判定理由：V026 target_session_id NOT NULL UNIQUE覆盖旧新行；旧重复数据迁移回滚而不是保留不确定绑定。报告假设绕过约束的异常DB不能证明正常查询需ORDER BY。；统一复核：明确假设异常旧DB绕过UNIQUE，不是支持内正常运行态。

代码：[V026_ExtendSessionMerges.java:18](../../../../../backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java#L18)；[MergeProgressRepository.java:37](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L37)

核验：代码静态核验及原文：见code_refs/lines

### R05-C30

**P2-20跨进程start500** — 范围外 / conditional / scope=out / 不入分；统一问题：cross-process-admission-error；credit=None。

[原文 L199–L199](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L199)

````text
| P2-20 | `SessionMergeService.java:90-105` | **跨进程并发 `start()` 返回 500 而非协议错误**。唯一索引 `uq_session_merges_active` 能守住"单一未结束名额"不变量（符合规范第 220 行"不能只用内存 semaphore 或 COUNT 后 INSERT"），但竞争失败方抛出裸 `DataIntegrityViolationException` → HTTP 500，而非规范第 272 行的 `MERGE_ACTIVE_EXISTS`。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：同服务start synchronized，单机单进程支持场景串行返回Conflict。跨进程共享SQLite时确可能撞约束，但此变更不建设多进程调度，缺支持合同，不能按双浏览器故障计。

判定理由：同服务start synchronized，单机单进程支持场景串行返回Conflict。跨进程共享SQLite时确可能撞约束，但此变更不建设多进程调度，缺支持合同，不能按双浏览器故障计。

代码：[SessionMergeService.java:81](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L81)；[SessionMergeService.java:105](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L105)；[session-merge-architecture-v2.md:18](../../../../session-merge-architecture-v2.md#L18)

核验：代码静态核验及原文：见code_refs/lines

### R05-C31

**P2-21a读预算错误码压平** — 成立 / functional / scope=new / 入分；统一问题：D13；credit=1。

[原文 L200–L200](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L200)

````text
| P2-21 | `HandoffReadService.java:206-208`、`:310-311`、`:256` | (a) 校验阶段预算耗尽被重编码为 `HANDOFF_INVALID_RESOURCE`（仅 message 保留原字样），规范第 387 行要求显式 `HANDOFF_READ_BUDGET_EXCEEDED` 错误码；(b) `skip()` 在续页时**从文件头重读**，且跳过的字节不计入 8 MiB 正文预算 → 单页实际 I/O 可超 8 MiB，与第 387 行"搜索正文每页最多 8 MiB"及"不把 8 MiB 误称为含完整性校验的总 I/O 上限"的谨慎表述不符。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：验证阶段IOException统一AuthorizationException码；超时与损坏仅message区分。

判定理由：验证阶段IOException统一AuthorizationException码；超时与损坏仅message区分。

核实范围：授权/读取支路把预算错误码包成INVALID_RESOURCE等泛码；read入口中断反例正常重抛。

代码：[HandoffReadService.java:43](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L43)；[HandoffReadService.java:55](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L55)；[HandoffReadService.java:208](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L208)

核验：[reproductions/RankingReadReproTest.java](reproductions/RankingReadReproTest.java)

### R05-C32

**P2-21b续页skip未计正文预算** — 规范歧义 / conditional / scope=new / 不入分；统一问题：search-resume-skip-io；credit=None。

[原文 L200–L200](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L200)

````text
| P2-21 | `HandoffReadService.java:206-208`、`:310-311`、`:256` | (a) 校验阶段预算耗尽被重编码为 `HANDOFF_INVALID_RESOURCE`（仅 message 保留原字样），规范第 387 行要求显式 `HANDOFF_READ_BUDGET_EXCEEDED` 错误码；(b) `skip()` 在续页时**从文件头重读**，且跳过的字节不计入 8 MiB 正文预算 → 单页实际 I/O 可超 8 MiB，与第 387 行"搜索正文每页最多 8 MiB"及"不把 8 MiB 误称为含完整性校验的总 I/O 上限"的谨慎表述不符。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：Reader.skip从头解码，跳过字节不计scanned，实际I/O会更大；规范387把8MiB称搜索正文预算并允许完整性I/O额外，是否含定位跳读未明确；15秒仍覆盖skip。

判定理由：Reader.skip从头解码，跳过字节不计scanned，实际I/O会更大；规范387把8MiB称搜索正文预算并允许完整性I/O额外，是否含定位跳读未明确；15秒仍覆盖skip。

代码：[HandoffReadService.java:250](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L250)；[HandoffReadService.java:256](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L256)；[HandoffReadService.java:310](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L310)；[session-merge-architecture-v2.md:387](../../../../session-merge-architecture-v2.md#L387)

核验：代码静态核验及原文：见code_refs/lines

### R05-C33

**P2-22a缺sha256导致NPE** — 不成立 / functional / scope=new / 入分；统一问题：missing-hash-null-pointer；credit=0。

[原文 L201–L201](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L201)

````text
| P2-22 | `HandoffReadService.java:106`、`:275` | 目录条目缺 `sha256` 时 `expected.equals(...)` **NPE**；分片后缀 `Integer.parseInt` 可抛未捕获 `NumberFormatException`。两者均以 RuntimeException 形式逃逸到管线通用处理器。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：catalog151 n.path(sha256).asText()返回空字符串而非null；固定ref哈希来自已verify的request-local缓存。缺值会HASH_MISMATCH，不是expected.equals NPE。

判定理由：catalog151 n.path(sha256).asText()返回空字符串而非null；固定ref哈希来自已verify的request-local缓存。缺值会HASH_MISMATCH，不是expected.equals NPE。；统一复核：统一按其具体指控的可证明后果计轻微：错误分类/状态展示，未声称资料毁损；不沿用作者P级。

代码：[HandoffReadService.java:151](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L151)；[HandoffReadService.java:106](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L106)；[HandoffReadService.java:174](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L174)

核验：代码静态核验及原文：见code_refs/lines

### R05-C34

**P2-22b超大part后缀整数溢出** — 证据不足 / conditional / scope=new / 不入分；统一问题：part-index-overflow；credit=None。

[原文 L201–L201](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L201)

````text
| P2-22 | `HandoffReadService.java:106`、`:275` | 目录条目缺 `sha256` 时 `expected.equals(...)` **NPE**；分片后缀 `Integer.parseInt` 可抛未捕获 `NumberFormatException`。两者均以 RuntimeException 形式逃逸到管线通用处理器。 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：Integer.parseInt可能溢出，但part为程序int递增且完整清单受哈希锚定，需伪造自洽目录或无法现实生成的片数；未给支持触发。

判定理由：Integer.parseInt可能溢出，但part为程序int递增且完整清单受哈希锚定，需伪造自洽目录或无法现实生成的片数；未给支持触发。

代码：[HandoffReadService.java:275](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L275)；[MergePackageService.java:686](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L686)

核验：代码静态核验及原文：见code_refs/lines

### R05-C35

**P3授权复核执行多次完整校验** — 证据不足 / conditional / scope=new / 不入分；统一问题：repeated-handoff-integrity-io；credit=None。

[原文 L207–L207](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L207)

````text
- **一次逻辑调用跑三遍完整完整性校验**：`analyze`（:50）、`recheck`（:111）、`execute`（:205）各带独立 15s 预算 → 最坏 ~45s。字面满足规范第 387 行"每次实际读取…共用 15 秒截止"，但成本三倍。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：三调用各自预算/哈希成立，但无实际45秒/吞吐证据；复核有防TOCTOU目的，不能直接视为错误。

判定理由：三调用各自预算/哈希成立，但无实际45秒/吞吐证据；复核有防TOCTOU目的，不能直接视为错误。

代码：[HandoffReadService.java:49](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L49)；[OperationAnalyzerRegistry.java:111](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L111)；[HandoffReadService.java:204](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L204)

核验：代码静态核验及原文：见code_refs/lines

### R05-C36

**P3未签名游标仍不可跨包** — 成立 / global / scope=new / 不入分；统一问题：cursor-binding-safety；credit=None。

[原文 L208–L208](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L208)

````text
- **游标未签名且 binding key 暴露给模型**：`nextCursor` 内含 `binding`（`sha256(operationId+handoffHash+params)`，:210）。我已追踪确认**无法越权** —— 偏移被 `catalog()`（:128-137，拒绝越 EOF 或前字符非 `\n`）与 `resolve` 的范围钳制（:233）限制在本包内，跨包/跨 action/跨 query 的游标在 :221 的 key 相等校验处失败。符合规范第 388 行"游标不是授权凭据，伪造偏移也不能访问其他包"。签名游标可作纵深防御。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：key绑定operation+hash+params，偏移不作为授权，目录/文件仍从当前绑定查找。报告明确不称权限漏洞。

判定理由：key绑定operation+hash+params，偏移不作为授权，目录/文件仍从当前绑定查找。报告明确不称权限漏洞。

代码：[HandoffReadService.java:210](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L210)；[HandoffReadService.java:221](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L221)；[HandoffReadService.java:128](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L128)

核验：代码静态核验及原文：见code_refs/lines

### R05-C37

**core名字登记多余** — 成立 / engineering / scope=new / 不入分；统一问题：redundant-core-name-registration；credit=None。

[原文 L83–L83](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L83)

````text
- 唯一可疑处：`isExplicitCoreTool("HandoffRead")`（:120）。该方法的**唯一消费者**是 `ToolRegistry:48` 的启动期断言（要求每个内部 Spring Bean 工具都有分析器）。而 `HandoffReadTool` 刻意**不注册为 Spring Bean**（符合规范第 379 行），因此这行**没有必要**，反而让未来任何名为 `HandoffRead` 的内部 Bean 免检，削弱 fail-fast 守卫。**不构成权限提升**，建议删除（P3）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：此处只影响未来同名bean启动自检，不赋权限；规范396明列core登记故属维护取舍。

判定理由：此处只影响未来同名bean启动自检，不赋权限；规范396明列core登记故属维护取舍。

代码：[OperationAnalyzerRegistry.java:120](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L120)；[session-merge-architecture-v2.md:396](../../../../session-merge-architecture-v2.md#L396)

核验：代码静态核验及原文：见code_refs/lines

### R05-C38

**Component断言位置不贴切** — 成立 / engineering / scope=new / 不入分；统一问题：test-organization；credit=None。

[原文 L210–L210](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L210)

````text
- **`HandoffContextServiceTest:29`** 在名为 `ordinaryRequestRetainsTheExactConfiguration…` 的用例里断言 `HandoffReadTool` 无 `@Component`，与用例主题无关。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：测试把工具无@Component放普通request配置测试内，仍有真实断言，只属组织建议。

判定理由：测试把工具无@Component放普通request配置测试内，仍有真实断言，只属组织建议。

代码：[HandoffContextServiceTest.java:29](../../../../../backend/src/test/java/com/aicodeassistant/engine/HandoffContextServiceTest.java#L29)

核验：代码静态核验及原文：见code_refs/lines

### R05-C39

**聚合引用测试可能零断言** — 成立 / engineering / scope=new / 不入分；统一问题：aggregate-test-vacuity-risk；credit=None。

[原文 L211–L211](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L211)

````text
- **`HandoffReadServiceTest:121-128` 可能零断言通过**：循环对非 completed 单元 `continue`，循环体外无断言；若 `prepare()` 在单个小 extracting 单元处短路（`MergeSummaryService:89-91`），该用例形同空跑。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：只遍历completed聚合项且无聚合非空断言，理论上空聚合可通过；当前fixture含多text会聚合，不能声称此次实际零断言。

判定理由：只遍历completed聚合项且无聚合非空断言，理论上空聚合可通过；当前fixture含多text会聚合，不能声称此次实际零断言。

代码：[HandoffReadServiceTest.java:121](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L121)；[HandoffReadServiceTest.java:26](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L26)

核验：代码静态核验及原文：见code_refs/lines

### R05-C40

**Panel五来源测试与串行GET测试命名** — 成立 / engineering / scope=new / 不入分；统一问题：test-name-and-order-coverage；credit=None。

[原文 L212–L213](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L212)

````text
- **`SessionMergePanel.test.tsx:140,186`** 名为"limits selection to five…"，实际断言 `2 个来源会话正在复制`（因 mock 的 `lockedSourceSessionIds` 只有 A/B，而提交了 5 个来源），名实不符。
- **`sessionMergeStore.test.ts:487`** 名为"serializes progress polling…"，实际证明的是 GET **从未发出**（`toHaveBeenCalledTimes(1)`、`finishPolling` 保持 undefined）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：一个同时验证选择数量与真实锁集合，可多断言；串行控制先POST使GET不发正是互斥目标，缺反向并发用例不等于当前断言无效。

判定理由：一个同时验证选择数量与真实锁集合，可多断言；串行控制先POST使GET不发正是互斥目标，缺反向并发用例不等于当前断言无效。

代码：[SessionMergePanel.test.tsx:140](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L140)；[sessionMergeStore.test.ts:487](../../../../../frontend/src/store/sessionMergeStore.test.ts#L487)

核验：代码静态核验及原文：见code_refs/lines

### R05-C41

**合成响应/哈希断言/异步断言/迁移重复/超时测试建议** — 成立 / engineering / scope=new / 不入分；统一问题：test-hygiene-observations；credit=None。

[原文 L214–L219](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L214)

````text
- **`MergeSummaryServiceTest`** 合成响应用 4,500 字节，规范第 440 行要求 4,183 字节 —— 数值不精确但结论等价（我已确认 `backend/src` 内 `SUMMARY_EXCEEDS_BUDGET`/`3500`/`MAX_CALL` **零命中**，阈值已彻底移除）。用例已按第 455 行要求标为合成。
- **`MergePackageServiceTest:45,72,121,138,190`** 的哈希断言属同义反复（`validateSnapshot(path, snapshot.hash())` 按构造返回同一哈希），仅"不抛异常"部分有信息量。
- **`SessionMergeServiceTest:48`、`MergeSummaryServiceTest:38`** 把断言写在 `doAnswer` 内部 → 失败会表现为"合并被暂停/失败"和误导性的下游状态断言，而非真实原因。
- **`MergePackageServiceTest:173`** 在测试体内重复执行 `MergeFixture:44` 已应用的 V026 迁移。
- **超时被延长而非放宽**：`SessionMergeServiceTest:59`（20s deadline）、`:105/:135`（5s→10s latch）。符合文档第 7 行"未放宽断言或超时"的声明，但失败反馈变慢。
- **`MergeHandoffData.java:33-34`** 的 `FileEntry` 比规范第 133 行字段集多一个 `sourceId`。属附加字段，低影响。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：4500字节代替4183满足回归方向；validateSnapshot不抛也有价值；异步assert可掩原始栈；重复迁移也检验幂等；超时增大属实现阶段变化。均非已证明产品错误，报告部分评价主观。

判定理由：4500字节代替4183满足回归方向；validateSnapshot不抛也有价值；异步assert可掩原始栈；重复迁移也检验幂等；超时增大属实现阶段变化。均非已证明产品错误，报告部分评价主观。

代码：[MergeSummaryServiceTest.java:55](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L55)；[MergePackageServiceTest.java:143](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L143)；[SessionMergeServiceTest.java:46](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L46)；[MergePackageServiceTest.java:173](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L173)

核验：代码静态核验及原文：见code_refs/lines

### R05-C42

**发布meta未单列历史参考** — 规范歧义 / engineering / scope=new / 不入分；统一问题：handoff-meta-reference-tag；credit=None。

[原文 L220–L220](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L220)

````text
- **`SessionMergeService` 发布事务的消息 meta** 只带 `operationId`，规范第 410 行还要求标记"历史参考"——该语义目前只体现在消息正文里，未进 meta。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：正文明确历史参考，meta仅sessionMergeOperationId；无具体schema字段要求，不证明执行时误当新授权。

判定理由：正文明确历史参考，meta仅sessionMergeOperationId；无具体schema字段要求，不证明执行时误当新授权。

代码：[SessionMergeService.java:212](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L212)；[MergeSummaryService.java:139](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L139)；[session-merge-architecture-v2.md:410](../../../../session-merge-architecture-v2.md#L410)

核验：代码静态核验及原文：见code_refs/lines

### R05-C43

**事故文档脱敏建议** — 成立 / engineering / scope=new / 不入分；统一问题：incident-document-redaction；credit=None。

[原文 L221–L221](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L221)

````text
- **`docs/session-merge-architecture-v2.md` 推送前需脱敏判断**：第 48-64 行含**真实会话 UUID**（`caf441d4-5ef8-44ea-b2ff-2b5425f749f9`、`82f4e5e2-…`、`f7c8e232-…`、`cb11ed96-…`）、**本地数据库路径**（`backend/.ai-code-assistant/data.db`）、**日志行号**（`log/app.log:19692`、`:19788`）以及真实 usage 数字。非凭据，但属内部事故细节，推公开仓库前建议评估。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：含历史会话UUID/日志路径，非密钥；是否公开属仓库政策，不直接评产品正确性。

判定理由：含历史会话UUID/日志路径，非密钥；是否公开属仓库政策，不直接评产品正确性。

代码：[session-merge-architecture-v2.md:48](../../../../session-merge-architecture-v2.md#L48)

核验：代码静态核验及原文：见code_refs/lines

### R05-C44

**前端876数字已过期** — 不成立 / engineering / scope=new / 不入分；统一问题：isolated-test-evidence-invalidated；credit=None。

[原文 L16–L16](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L16)

````text
| 前端全量 `npm run test:run -- --maxWorkers=2 --minWorkers=1` | **103 文件：894 通过、16 跳过** — 文档第 7 行写的是 876 通过，**该数字已过期** |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：规范7明确独立副本排除其他UI改动；894多出的18项来自排除测试，不是同范围数字过期。不能以工作区全量替代原隔离证据。

判定理由：规范7明确独立副本排除其他UI改动；894多出的18项来自排除测试，不是同范围数字过期。不能以工作区全量替代原隔离证据。

代码：[session-merge-architecture-v2.md:7](../../../../session-merge-architecture-v2.md#L7)

核验：代码静态核验及原文：见code_refs/lines

### R05-C45

**主要正向具体安全/隔离保证** — 成立 / global / scope=new / 不入分；统一问题：read-side-resource-boundary；credit=None。

[原文 L232–L239](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L232)

````text
- **迁移（附录 A）**：V026 SQL 与附录 A **逐行等价**（规范化后 diff 为 IDENTICAL）。`active_slot IS 1` 未被削弱为 NULL 可通过的形式（V026:37）；部分唯一索引 `uq_session_merges_active … WHERE active_slot=1`（:58）；两张新表的 FK `ON DELETE CASCADE`（:61/:84）；四个索引齐备；保留 V024 全部 12 个列名并**重新执行** `new V024_CreateSessionMerges(jdbc).validate()`（:113）；`execute()` 在 `protocol_version` 已存在时**只 validate 不重建**（:103-106，幂等）；旧行按 `protocol_version=1`/`active_slot=NULL`/`preparing→failed`+`LEGACY_INTERRUPTED`+stage `interrupted`+"旧版任务中断，请重新发起" 复制（:47-53）；整个 PROJECT 作用域包在 `MigrationRunner:87-111` 的单个 `TransactionTemplate` 内，失败 → `ApplicationContextException` 拒绝启动；旧 `target_session_id` 重复时由 UNIQUE 约束触发**整体回滚而非删行**（符合第 527 行）。
- **并发与状态机**：单一未结束名额由**数据库唯一索引**保证（非内存 semaphore、非 COUNT-then-INSERT）；`run_epoch` 仅在 create(=1)/resume/cancel 递增，普通进度不递增；所有推进与发布均以 `WHERE operation_id=? AND run_epoch=? AND status='preparing'` 围栏（`assertCurrent`，repo:75-78）；单元三段式提交顺序正确（短事务 beginAttempt → 无事务读料/调用/校验/`atomicWrite` → 短事务 commitUnit 带 epoch+state 围栏）；completed 单元经 `readResult` 哈希校验后复用（`MERGE_RESULT_HASH_MISMATCH`）；cancel 递增 epoch 使 cancel 与 publish 在 DB 层互斥，且**永不删除已发布目标**；崩溃恢复只挑 `status='preparing'` 的 v2 行、递增 epoch、`running→pending`、attempts `running→unknown`，**不静默收养未提交候选**；优雅停机 `closing=true` → `paused/SERVICE_SHUTDOWN` + abort；**无数据库写事务跨文件复制或 LLM 调用**；幂等键语义正确（同键同参返回原操作，同键异参 409 `MERGE_IDEMPOTENCY_CONFLICT`）。
- **HTTP 协议**：`GET /active` 无操作时返回 204；`POST /{id}/resume` 收 `{expectedEpoch, model?}`、仅从 paused、epoch 条件更新、经 `writers.putIfAbsent` + `synchronized` 保证**只启动一个 worker**，重复 resume → 409 `MERGE_STALE_OPERATION` 并携带当前操作；`POST /{id}/cancel` 无版本参数、可取消 preparing/paused/failed、已 cancelled 幂等、completed → 409 `MERGE_ALREADY_COMPLETED`；七类错误码全部存在且使用正确；冲突响应体为 `{error:{code,message}, operation?}`（`@RequestMapping(produces="application/json")` + `@ExceptionHandler`）；Operation DTO 字段与规范第 252-269 行**逐项对应**，`lockedSourceSessionIds` 来自**真实 gate**（`gate.mergeOperationId`，`SessionMergeService:263`）而非由 `preparing` 推断，封存后立即释放并通知（:190-195）—— `sealedSnapshotReleasesSourcesBeforeFirstProviderCall` 断言仍在 `preparing` 时 `gate.isBusy==false`。
- **发布事务**：事务前校验封存资料 + `ready.json` 哈希链 + 当前 epoch + 捕获目录仍绑定工作区（:202-205）；单个短事务要求 status/stage/epoch 三重条件，用预留 ID + 捕获的目录/标题/模型 + 既有默认权限创建 session，仅向 E 的 `metadata_json` 写 `sessionMergeOperationId`（普通会话不写），写一条有界交接入口消息，更新 completed + `handoff_hash` + `active_slot=NULL`；提交后按"释放 worker → 通知状态 → `notifySessionCreated`"顺序，hook 失败不回滚；**不复制来源原生 tool calls**；回滚保留资料与已完成单元（`failedPublicationRollsBackTargetAndPreservesPreparedFiles` 断言零消息 + `ready.json` 完好）；提交不确定时重新核对持久化 completed 与 session 行再决定暂停。
- **快照与清单**：staging→snapshot 同目录 `ATOMIC_MOVE`（:173）；`seal.json = {snapshotVersion, manifestHash}`（:172）；`records.jsonl` 字段集与规范第 150 行一致（`MergeHandoffData:31-32`）；`recordRef` = 对 origin JSON 的完整 SHA-256（:369, :1098-1101）；**仅按 ref 去重**，重复位置一律写入 `occurrences.jsonl`，**不跨来源按相同文本去重**（:368-376）；32 KiB 分片按**码点**切分并拒绝孤立代理对（:677-688）；旧 16 MiB 致命规则（`MAX_RECORD_BYTES:71`）只残留在死代码 `build()`/`exportRuns` 中，**存活路径改用有界 `substr` 分页**（:124-147）+ 物化上限 → 保存 raw 后转阻断记录（:400-407）；`artifact_manifests`/`artifact_entries` 分页只读导出、**每行一条记录**、`role=reference`、按固定 PRAGMA 列序计算原行哈希（:442-451, :485-493）；子任务未保存过程记为**非阻断** gap（:568-572），已保存但损坏的 checkpoint 为**阻断**且不可被空恢复结果放行（:565）；文件名全部程序生成，`safeFile` 拒绝绝对路径/`..`/任意层符号链接（:304-319）；所有存活写入均经 `MergeTextBudget`/`CopyBudget` 磁盘守卫（:358, :379, :386, :687, :170-172, :278-286），新增的 `atomicWrite` 也走 `write → output → reserve`（`MergeTextBudget:34-40`）；重复合并按 ref 并集从祖先**自有包**导入并校验先前快照哈希（:601-672）；清理规则正确 —— writers-stopped 守卫（`SessionMergeService:271-275`）、只删本操作目录（:1112-1121）、启动时与下次合并时惰性清理并用 `NOT EXISTS(sessions)` 确认目标确已消失（`MergeProgressRepository:47-53`）、DB 不可读时捕获并保留数据；**普通 `deleteSession`（`SessionManager:595-625`）不触发任何合并查库或磁盘扫描**，符合规范第 112 行与第 415 行。
- **预算与重试**：存储 bytes / 请求 bytes / 估算输入 token / 可见输出 token / 含推理的 provider usage **五者分离**；`capacity()` 只用 token 估算（`MergeSummaryService:52`），provider `outputTokens` 仅记录不参与准入（:333-336）；默认值 16384 请求目标（:58）、2048 可见/brief 目标（:54/58/145）、900 KiB 请求分片守卫（:212，比规范 1 MiB 略严）、256 KiB 响应守卫（:311）、300s 单调用超时配 AbortContext/LlmCallContext（:301-303/327）且**无整任务截止**、3 次自动尝试 + 2s/5s 退避 + 遵循 Retry-After（:216/249-250）、256 token 缩小下限 → `MERGE_MIN_UNIT_FAILED` → 暂停（:269）、推理预留 `min(32768, window/4)` 且受 maxOutput 约束（:53-56）、安全余量 `window/20 = max(1024, 5%)`（:59）；**无固定 16 次总调用上限**；usage 缺失记 `usage_reported=0` + `usage_json/estimated_cost_usd=NULL`，**绝不写成零费用**（`MergeProgressRepository:181-194`）；split 父单元既不参与执行（`state<>'split'` 跳过）也不计入进度（repo:225）。
- **本次失败条件的回归保证（规范第 440 行）**：4,183 字节合法中文响应**不会**被任何 3,500 阈值拒绝 —— 唯一输出闸门是 256 KiB 字节上限（:311）、stop reason/完整性（:343-345）与 `validate()`；provider `output=13370` 仅记录不参与准入；超目标中间结果按第 344 行表格第一行"保存详细结果"。> 16 个单元可完成 —— `processStage`（:207-261）会排空全部规划单元，无任何调用/单元数上限。`MergeSummaryServiceTest:55-73` 两个用例正是针对此条件。
- **读取工具**：action 限定 `list|search|read|asset`，search 要 `query`、read/asset 要 `ref`（:59-60, :166-178）；统一返回 `entries`/`complete`/`nextCursor`/`warning`（:302-304）；字面 `indexOf` 匹配，**不调 shell、不受 .gitignore 影响、不建索引**（:259）；UTF-8/代理对安全的跨缓冲 carry + query 长度重叠 + 相邻分片前瞻且去重正确（:251-294，经坐标分析确认无重复/漏匹配，`HandoffReadServiceTest:129-150` 覆盖）；游标绑定包版本 + action/query/过滤条件（:210, :221）；`complete=true` **仅在全部候选扫描结束后**给出；asset 经 `ImageResultExternalizer` + `canReadAsset` 哈希门控并纳入既有多模态预算（`QueryEngine:907-911`）；所有引用映射到包内独立副本，**不返回源会话或临时路径**。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：当前读取路径ref/哈希/逐段symlink与instanceof授权收窄有据；此段描述safeFile/read入口，未明确断言复制源所有symlink不可利用。不要因漏报源复制漏洞机械扣此正向保证。

判定理由：当前读取路径ref/哈希/逐段symlink与instanceof授权收窄有据；此段描述safeFile/read入口，未明确断言复制源所有symlink不可利用。不要因漏报源复制漏洞机械扣此正向保证。

代码：[MergePackageService.java:304](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L304)；[HandoffReadService.java:179](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L179)；[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)；[HandoffContextService.java:39](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L39)

核验：代码静态核验及原文：见code_refs/lines

### R05-C46

**覆盖表入口门控等欠缺** — 成立 / engineering / scope=new / 不入分；统一问题：merge-test-coverage-gaps；credit=None。

[原文 L247–L265](../Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L247)

````text
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
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：无直接REST/WS合并gate专测；单测无交互不等于全部压缩/413和端到端状态隔离覆盖。覆盖分类允许不同意见，不当产品FP。

判定理由：无直接REST/WS合并gate专测；单测无交互不等于全部压缩/413和端到端状态隔离覆盖。覆盖分类允许不同意见，不当产品FP。

代码：[SessionMergeServiceTest.java:159](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L159)；[HandoffReadServiceTest.java:81](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L81)；[QueryEngineUnitTest.java:1950](../../../../../backend/src/test/java/com/aicodeassistant/engine/QueryEngineUnitTest.java#L1950)

核验：代码静态核验及原文：见code_refs/lines

## R06

[完整原报告](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md)

### R06-normal_function

**判断分：5/5**。[原文 L115–L126](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L115)。表格正确识别普通请求守卫、图片/授权原分支，未发现无关普通链路新增功能破坏。

````text
| 公共符号 | 调用方 | 非合并链路是否触达 | 结论 |
|---|---|---|---|
| `V026_ExtendSessionMerges` | `MigrationRunner` | 仅迁移期执行，不改 `sessions` 表、不改普通请求路径 | 无影响 |
| `MergeProgressRepository` / `MergeHandoffData` | `SessionMergeService`、`MergeSummaryService`、`HandoffReadService` | 无普通请求路径引用 | 无影响 |
| `HandoffContextService.configure / project / inject / canReadAsset` | `QueryEngine`（历史预算 Step1、API 消息准备 Step3、备用预算）、`QueryController`、`WebSocketController` | `handoffOperationId == null` 时返回空投影、预留 0 token；两条入口均以 `isMerged` 守卫 | 普通会话历史预算与 HEAD 基线一致 |
| `ImageRefInjector.injectForApiCall` 新增 `BiPredicate boundHandoffAsset` 重载 | `QueryEngine` | 旧签名保留并委托到新签名，默认 `(path,hash) -> false`；仅当 pathSecurity 拒绝且为 E 会话交接包内图片时走 handoff 绑定校验 | 普通会话图片安全边界不变；E 会话新增读取路径受 `HandoffReadService` 路径/哈希校验约束 |
| `AuthorizationService` SAFE 读自动放行 | 所有经 `OperationAnalyzerRegistry` 的工具调用 | 仅新增 `handoff-read-v1` 一个 analyzerId；`file-v1` 逻辑不变；其他工具（用户自定义、MCP）不受影响 | 不放宽其他工具权限 |
| `OperationAnalyzerRegistry.analyzerFor / isExplicitCoreTool` | 工具授权入口 | 仅对 `HandoffReadTool` 实例返回专用 analyzer；同名 MCP/动态工具仍走 `static-or-remote-v1` | 增强了防冒充保护，非放宽 |
| `SessionMergeController` 新端点 | 前端 `sessionMergeStore` | 新增 REST 路径，不改既有路由 | 无影响 |
| 前端 `MergeOperation` 类型 / `useSessionMergeStore` / `isMergeSource` | `SessionMergePanel`、会话列表 | 锁集合仅由服务端返回决定，不再受本地残留记录影响 | 普通会话可用性更准确 |

**明确结论：本次改动不会对非合并分支的功能链路（普通会话消息收发、权限授权、普通工具调用、工作区、恢复/重连、上下文折叠、图片处理等）造成负面影响或破坏。** 所有新行为均以 `metadata_json.sessionMergeOperationId` 为唯一开关，普通会话不会进入任何新增代码路径。
````

### R06-boundary

**判断分：5/5**。[原文 L117–L126](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L117)。虽126总结过满，但正文列出全局迁移和公共授权接点，249又限定被排除集成范围；按冻结规则结合全文给予边界分。

````text
| `V026_ExtendSessionMerges` | `MigrationRunner` | 仅迁移期执行，不改 `sessions` 表、不改普通请求路径 | 无影响 |
| `MergeProgressRepository` / `MergeHandoffData` | `SessionMergeService`、`MergeSummaryService`、`HandoffReadService` | 无普通请求路径引用 | 无影响 |
| `HandoffContextService.configure / project / inject / canReadAsset` | `QueryEngine`（历史预算 Step1、API 消息准备 Step3、备用预算）、`QueryController`、`WebSocketController` | `handoffOperationId == null` 时返回空投影、预留 0 token；两条入口均以 `isMerged` 守卫 | 普通会话历史预算与 HEAD 基线一致 |
| `ImageRefInjector.injectForApiCall` 新增 `BiPredicate boundHandoffAsset` 重载 | `QueryEngine` | 旧签名保留并委托到新签名，默认 `(path,hash) -> false`；仅当 pathSecurity 拒绝且为 E 会话交接包内图片时走 handoff 绑定校验 | 普通会话图片安全边界不变；E 会话新增读取路径受 `HandoffReadService` 路径/哈希校验约束 |
| `AuthorizationService` SAFE 读自动放行 | 所有经 `OperationAnalyzerRegistry` 的工具调用 | 仅新增 `handoff-read-v1` 一个 analyzerId；`file-v1` 逻辑不变；其他工具（用户自定义、MCP）不受影响 | 不放宽其他工具权限 |
| `OperationAnalyzerRegistry.analyzerFor / isExplicitCoreTool` | 工具授权入口 | 仅对 `HandoffReadTool` 实例返回专用 analyzer；同名 MCP/动态工具仍走 `static-or-remote-v1` | 增强了防冒充保护，非放宽 |
| `SessionMergeController` 新端点 | 前端 `sessionMergeStore` | 新增 REST 路径，不改既有路由 | 无影响 |
| 前端 `MergeOperation` 类型 / `useSessionMergeStore` / `isMergeSource` | `SessionMergePanel`、会话列表 | 锁集合仅由服务端返回决定，不再受本地残留记录影响 | 普通会话可用性更准确 |

**明确结论：本次改动不会对非合并分支的功能链路（普通会话消息收发、权限授权、普通工具调用、工作区、恢复/重连、上下文折叠、图片处理等）造成负面影响或破坏。** 所有新行为均以 `metadata_json.sessionMergeOperationId` 为唯一开关，普通会话不会进入任何新增代码路径。
````

### R06-release

**判断分：0/5**。[原文 L226–L242](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L226)。有条件达标仅要求防御校验和CHANGELOG，宣称无Major且唯一正确性风险可消除，未区分阶段提交与功能完成版。

````text
**有条件达标。**

依据：
- 架构一致性：与 v2 文档高度一致，无偏离。
- 正确性：无 Blocker / Major；两处 Minor 均为防御性加固，不影响当前线上路径。
- 影响面：非合并链路零回归。
- 测试：后端 10 个关键测试类 + 前端 63 例全绿；TypeScript 类型检查通过。

**建议提交前处理**
1. 补齐 `HandoffReadTool` 两处防御性校验（M1、M2）——工作量极小，可消除唯一残留正确性风险。
2. 同步 `CHANGELOG.md`，补充 Session Merge v2 条目。

**可选改进（非阻塞）**
- 前端补 `resume` / `cancel` 单测；后端补磁盘不足与游标越界用例。
- `MergePackageService.importPrevious` 与 `MergeSummaryService.processStage / split` 适度拆分以降低认知负担。
- 对 `MERGE_SNAPSHOT_CORRUPT`、`HANDOFF_HASH_MISMATCH`、`RECORD_REQUIRES_HANDLING` 等关键错误码输出结构化日志（operationId、sourceIds、原因）以便运维监控。
- `SessionMergePanel` 在 `paused` 状态突出提示“恢复将重试未完成单元并可能再次计费”；对 `targetAvailable === false` 提供“重新合并”或“查看历史交接索引”入口。
````

### R06-semantic

**判断分：0/5**。[原文 L246–L250](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L246)。全文未回答真实模型语义验收尚未完成及stub不可替代，属于未回答，不作为FP。

````text
## 七、风险与假设说明

- 后端测试证据基于 `-q` 静默模式的退出码与无失败输出，未逐用例列出；如需更强证据可去掉 `-q` 复跑。
- 被排除文件（如 `SessionStatusCapsule.tsx`、`sessionStatusMeta.ts`）与合并状态展示存在关联，但按要求未纳入评估，其与 `sessionMergeStore` 的集成正确性不在本结论范围内。
- 本轮为纯审查，未做任何代码修改；是否执行上述加固与 CHANGELOG 同步，等待用户明确指令。
````

### R06-C01

**缺少currentRunId校验导致未来NPE** — 不成立 / conditional / scope=new / 不入分；统一问题：tool-null-run-context；credit=None。

[原文 L187–L192](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L187)

````text
**M1. `HandoffReadTool.call` 对 `currentRunId` 缺少防御性校验（潜在 NPE）**
- 位置：`backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java`（`call` 方法，约 L28-L45）
- 现状：`String root=subjects.resolve(context.currentRunId()).rootSessionId();` 直接假定非空。
- 依据：文档 7.3 要求通过 `AuthorizationSubjectResolver.resolve(currentRunId).rootSessionId()` 校验绑定，同时要求工具不应在错误上下文中崩溃。
- 影响：当前 QueryEngine 接入路径下 runId 必然存在，线上无即时风险；未来在无 run 的测试/诊断场景直接调用会 NPE 且难定位。
- 建议：前置 `context == null || context.currentRunId() == null` 校验，返回 `ToolResult.validationError("HANDOFF_CONTEXT_MISSING", ...)`。
````

触发：
预期：
实际：

判定理由：报告自己排除线上风险；currentRunId=null时Resolver已有显式AuthorizationException，resolve从不正常返回null。只有context对象本身null才直接NPE，不能把二者混为一谈。不作为受支持产品FP。

代码：[HandoffReadTool.java:29](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L29)；[AuthorizationSubjectResolver.java:36](../../../../../backend/src/main/java/com/aicodeassistant/authorization/AuthorizationSubjectResolver.java#L36)

核验：代码静态核验及原文：见code_refs/lines

### R06-C02

**测试构造器images为null可能使asset崩溃** — 成立 / conditional / scope=new / 不入分；统一问题：test-constructor-null-images；credit=None。

[原文 L194–L198](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L194)

````text
**M2. `HandoffReadTool` 的 `asset` 分支对 `images == null` 无防护（测试易错点）**
- 位置：`backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java`（四参构造器以 `null` 传入 `ImageResultExternalizer`，约 L20-L31）；`HandoffReadTool` 的 `asset` 分支。
- 依据：文档 7.2 强调图片通过现有 `ImageResultExternalizer` 处理；能力不可用时应返回说明而非崩溃。
- 影响：生产使用 `@Autowired` 五参构造器不受影响，现有测试也注入了 mock；属未来编写测试时的隐含陷阱。
- 建议：在 `asset` 分支对 `images == null` 返回 `HANDOFF_ASSET_UNSUPPORTED` 验证错误。
````

触发：
预期：
实际：

判定理由：四参构造器确实传null，asset支持图片走42时会NPE；生产Autowired五参不受影响，属于测试夹具防御建议。

代码：[HandoffContextService.java:22](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L22)；[HandoffReadTool.java:42](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L42)

核验：代码静态核验及原文：见code_refs/lines

### R06-C03

**路径错误码可细分** — 成立 / engineering / scope=new / 不入分；统一问题：path-error-code-granularity；credit=None。

[原文 L202–L205](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L202)

````text
**N1. `MergePackageService` 路径安全校验错误码复用**
- 位置：`MergePackageService.java` 约 L304-L319。
- 现状：绝对路径、`..` 穿越、符号链接、越界均返回 `MERGE_INVALID_REF`。
- 建议：如需诊断区分，可细分为 `MERGE_REF_SYMLINK`、`MERGE_REF_OUT_OF_SCOPE` 等。逻辑正确，仅为可读性。
````

触发：
预期：
实际：

判定理由：safeFile多种拒绝使用MERGE_INVALID_REF，逻辑仍拒绝越界。

代码：[MergePackageService.java:304](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L304)

核验：代码静态核验及原文：见code_refs/lines

### R06-C04

**active轮询间隔应提为常量** — 成立 / engineering / scope=new / 不入分；统一问题：poll-interval-constant；credit=None。

[原文 L207–L209](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L207)

````text
**N2. 前端轮询间隔硬编码**
- 位置：`sessionMergeStore.ts` 中 `nextActiveCheck = Date.now() + 5000`。
- 建议：常量化或配置化以便调整退避策略。
````

触发：
预期：
实际：

判定理由：5000毫秒确实硬编码，风格/配置建议。

代码：[sessionMergeStore.ts:145](../../../../../frontend/src/store/sessionMergeStore.ts#L145)

核验：代码静态核验及原文：见code_refs/lines

### R06-C05

**前端缺少resume/cancel专门单测** — 不成立 / engineering / scope=new / 不入分；统一问题：existing-resume-cancel-tests-denied；credit=None。

[原文 L171–L171](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L171)

````text
- 前端缺少 `resume` / `cancel` 新行为的专门单测（暂停后换模型恢复、取消后通知与锁集合更新）。
````

触发：
预期：
实际：

判定理由：store427含换模型和expectedEpoch、440含取消与锁释放，487含控制/轮询串行。面板交互覆盖仍可扩展，但不能称这两种新行为没有专门单测。

代码：[sessionMergeStore.test.ts:427](../../../../../frontend/src/store/sessionMergeStore.test.ts#L427)；[sessionMergeStore.test.ts:440](../../../../../frontend/src/store/sessionMergeStore.test.ts#L440)；[sessionMergeStore.test.ts:487](../../../../../frontend/src/store/sessionMergeStore.test.ts#L487)

核验：代码静态核验及原文：见code_refs/lines

### R06-C06

**缺少磁盘不足与游标越界针对性测试** — 不成立 / engineering / scope=new / 不入分；统一问题：existing-disk-cursor-tests-denied；credit=None。

[原文 L172–L172](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L172)

````text
- 后端缺少 `MERGE_DISK_SPACE_LOW`（磁盘不足）与 `HANDOFF_INVALID_CURSOR`（游标越界）的针对性用例。
````

触发：
预期：
实际：

判定理由：pkgtest380/448有磁盘测试（确实多为legacy），readtest151明确构造mid-line cursor并断言HANDOFF_INVALID_CURSOR。应准确说v2 seal磁盘专项及具体偏移类型不足，非两者都无用例。

代码：[MergePackageServiceTest.java:380](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L380)；[HandoffReadServiceTest.java:151](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L151)

核验：代码静态核验及原文：见code_refs/lines

### R06-C07

**未跑浏览器E2E** — 成立 / engineering / scope=new / 不入分；统一问题：browser-e2e-unexecuted；credit=None。

[原文 L173–L173](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L173)

````text
- 未运行 e2e（按要求不启动重型套件），浏览器端整体交互仅依赖单元测试。
````

触发：
预期：
实际：

判定理由：这是报告自述验证边界，不冒充端到端覆盖。

代码：[SessionMergePanel.test.tsx:40](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L40)

核验：代码静态核验及原文：见code_refs/lines

### R06-C08

**建议补CHANGELOG** — 证据不足 / engineering / scope=new / 不入分；统一问题：changelog-synchronization；credit=None。

[原文 L222–L236](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L222)

````text
- 当前 diff 未包含 `CHANGELOG.md` / `README.md` 更新；按项目既往提交规范，重要特性需有变更记录。

### 6.3 总体结论

**有条件达标。**

依据：
- 架构一致性：与 v2 文档高度一致，无偏离。
- 正确性：无 Blocker / Major；两处 Minor 均为防御性加固，不影响当前线上路径。
- 影响面：非合并链路零回归。
- 测试：后端 10 个关键测试类 + 前端 63 例全绿；TypeScript 类型检查通过。

**建议提交前处理**
1. 补齐 `HandoffReadTool` 两处防御性校验（M1、M2）——工作量极小，可消除唯一残留正确性风险。
2. 同步 `CHANGELOG.md`，补充 Session Merge v2 条目。
````

触发：
预期：
实际：

判定理由：diff确无CHANGELOG更新，但所谓项目既往强制规范未附出处；可列交付文档建议，不据此判产品错误。

代码：

核验：代码静态核验及原文：见code_refs/lines

### R06-C09

**建议适度拆分复杂方法** — 成立 / engineering / scope=new / 不入分；统一问题：method-complexity-refactor；credit=None。

[原文 L240–L240](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L240)

````text
- `MergePackageService.importPrevious` 与 `MergeSummaryService.processStage / split` 适度拆分以降低认知负担。
````

触发：
预期：
实际：

判定理由：大型方法存在，纯维护性建议无需产品缺陷加分。

代码：[MergePackageService.java:601](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L601)；[MergeSummaryService.java:204](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L204)

核验：代码静态核验及原文：见code_refs/lines

### R06-C10

**错误日志增加来源及operation结构字段** — 成立 / engineering / scope=new / 不入分；统一问题：merge-error-diagnostics；credit=None。

[原文 L241–L241](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L241)

````text
- 对 `MERGE_SNAPSHOT_CORRUPT`、`HANDOFF_HASH_MISMATCH`、`RECORD_REQUIRES_HANDLING` 等关键错误码输出结构化日志（operationId、sourceIds、原因）以便运维监控。
````

触发：
预期：
实际：

判定理由：现log含id/code/type，补sourceIds是诊断建议，不能说现有错误不可定位。

代码：[SessionMergeService.java:219](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L219)

核验：代码静态核验及原文：见code_refs/lines

### R06-C11

**增加恢复计费/目标缺失操作提示** — 成立 / engineering / scope=new / 不入分；统一问题：resume-and-deleted-target-ux；credit=None。

[原文 L242–L242](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L242)

````text
- `SessionMergePanel` 在 `paused` 状态突出提示“恢复将重试未完成单元并可能再次计费”；对 `targetAvailable === false` 提供“重新合并”或“查看历史交接索引”入口。
````

触发：
预期：
实际：

判定理由：现panel缺该完整提示，属UX建议，不能凭缺按钮推导资料无法读取。

代码：[SessionMergePanel.tsx:215](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L215)；[SessionMergePanel.tsx:217](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L217)

核验：代码静态核验及原文：见code_refs/lines

### R06-C12

**HandoffRead真实类型与binding安全保证** — 成立 / global / scope=new / 不入分；统一问题：builtin-tool-identity-isolation；credit=None。

[原文 L104–L104](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L104)

````text
| HandoffRead 为内置只读工具，不注册为全局 Spring Bean，遵循授权框架 | 7.x | 符合 | `HandoffContextServiceTest` 断言 `HandoffReadTool` 无 `@Component`；`OperationAnalyzerRegistry` 专用 `handoff-read-v1` analyzer；binding 变更时 recheck 抛 `HANDOFF_BINDING_CHANGED`；`AuthorizationService` 将 `handoff-read-v1` 纳入 SAFE 读自动放行 |
````

触发：
预期：
实际：

判定理由：绑定由可信run主体解析、授权分析器校验，非Bean且允许列表仍约束注入。

代码：[HandoffReadTool.java:29](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L29)；[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)；[HandoffContextService.java:44](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L44)

核验：代码静态核验及原文：见code_refs/lines

### R06-C13

**preparing状态门控符合真实锁集合规范** — 不成立 / global / scope=new / 不入分；统一问题：D15；credit=None。

[原文 L105–L105](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L105)

````text
| 前端来源锁真实依赖服务端 gate、封存后释放、恢复/取消为独立操作 | 第 8 / 10 节 | 符合 | `selectMergeSourceIds` 仅在 `status === 'preparing'` 时返回 `lockedSourceSessionIds`；`resume`/`cancel` 映射后端接口；`dismiss` 仅在不可恢复/不可取消终态可用；面板文案明确“快照封存后可继续使用来源” |
````

触发：复制期间取消，worker尚未释放lease
预期：仍认可服务端非空lockedSourceSessionIds
实际：状态cancelled导致selector返回空

判定理由：代码确有preparing门控，但cancelled仍持锁的收尾窗口被过滤，与规范272仅真实gate权威不符；此为具体实现符合性错误保证。；统一复核：概括性UI符合保证只保留事实裁定，漏报由FN体现，不加整体判断FP。

代码：[sessionMergeStore.ts:30](../../../../../frontend/src/store/sessionMergeStore.ts#L30)；[SessionMergeService.java:150](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L150)；[SessionMergeService.java:230](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L230)；[session-merge-architecture-v2.md:272](../../../../session-merge-architecture-v2.md#L272)

核验：代码静态核验及原文：见code_refs/lines

### R06-C14

**Summary测试覆盖JSON长度错误** — 证据不足 / engineering / scope=new / 不入分；统一问题：summary-length-test-coverage；credit=None。

[原文 L150–L150](../QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md#L150)

````text
| `MergeSummaryServiceTest` | 分页提取、聚合、预算、失败路径（JSON 错误、长度错误、413、上下文错误） |
````

触发：
预期：
实际：

判定理由：有schema直接校验和413用例，但没有stop=length运行处理断言；表格未分清解析单测与完整恢复路径，不据此推断伪造实跑。

代码：[MergeSummaryServiceTest.java:47](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L47)；[MergeSummaryServiceTest.java:74](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L74)

核验：代码静态核验及原文：见code_refs/lines

## R07

[完整原报告](../QoderIDE/Sonus-session-merge-v2-review.md)

### R07-normal_function

**判断分：5/5**。[原文 L319–L321](../QoderIDE/Sonus-session-merge-v2-review.md#L319)。明确未发现无关普通链路新增破坏，并准确限定来源取消窗口UI问题。

````text
**综合判断：**

> 没有发现无关普通会话必然损坏的新增路径；但参与合并的来源会话存在已确认的前端状态问题，公共链路和数据库升级也尚不足以获得"零回归"背书。
````

### R07-boundary

**判断分：5/5**。[原文 L265–L321](../QoderIDE/Sonus-session-merge-v2-review.md#L265)。逐项列出共享引擎/迁移/后台轮询/验证未闭合，拒绝绝对零回归。

````text
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
````

### R07-release

**判断分：5/5**。[原文 L364–L380](../QoderIDE/Sonus-session-merge-v2-review.md#L364)。允许注明已知问题的阶段提交，禁止标记功能完成或发布就绪。

````text
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
````

### R07-semantic

**判断分：5/5**。[原文 L374–L380](../QoderIDE/Sonus-session-merge-v2-review.md#L374)。明确真实模型样本不可由stub/结构校验替代，并说明尚未执行。

````text
1. **修复3项 P2，并补齐对应回归测试。**
2. **验证数据库升级**：旧库迁移、完整启动、再次启动校验、约束失败回滚。
3. **验证普通链路**：HTTP/WebSocket 查询、持久化、工具授权、图片，以及 fork/Swarm。
4. **验证恢复边界**：换模型、连续压缩、413、取消期间 worker 收尾及交接入口重建。
5. **补齐真实 artifact 表结构测试**，覆盖导出、读取和来源删除。
6. **执行后端定向及全量验证、前端单测与构建。**
7. **完成浏览器交互及规范要求的真实模型接续开发样本**，不能仅用 stub provider 或结构校验替代。
````

### R07-C01

**恢复投影绕过归档字段和图片分类** — 成立 / functional / scope=new / 入分；统一问题：D01；credit=1。

[原文 L106–L146](../QoderIDE/Sonus-session-merge-v2-review.md#L106)

````text
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
````

触发：合法大消息/checkpoint因物化上限暂停，提高上限恢复
预期：恢复与正常路径同一材料分类
实际：整个原始content JSON进入提取文字

判定理由：正常export跳过thinking/private状态，恢复直接将content JSON写模型文字投影，包含思考/私有状态及base64。无需真实模型响应即可确认输入边界。

核实范围：恢复投影将thinking/供应商私有字段送入提取；与首次过滤不一致。

代码：[MergePackageService.java:265](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L265)；[MergePackageService.java:270](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L270)；[MergePackageService.java:514](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L514)；[MergeSummaryService.java:72](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L72)

核验：[reproductions/MergeReviewReproTest.java](reproductions/MergeReviewReproTest.java)

### R07-C02

**换模型后聚合提前退出遗留pending** — 成立 / functional / scope=new / 入分；统一问题：D06；credit=1。

[原文 L150–L191](../QoderIDE/Sonus-session-merge-v2-review.md#L150)

````text
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
````

触发：旧模型创建下一层后失败，新模型估算上一结果<=1536
预期：处理或显式作废持久化未完成层
实际：MERGE_INCOMPLETE_UNITS再次paused

判定理由：1536阈值用当前模型估算，但已有计划保存在repo；新估算可使循环break而发布全量单位校验仍拒绝pending，当前模型下重复恢复会再次暂停。

核实范围：token估算变化越过1536阈值时遗留既有pending聚合单元；换回条件可恢复。

代码：[MergeSummaryService.java:89](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L89)；[MergeSummaryService.java:120](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L120)；[MergeProgressRepository.java:115](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L115)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R07-C03

**取消复制时UI提前解除来源占用** — 成立 / functional / scope=new / 入分；统一问题：D15；credit=1。

[原文 L195–L236](../QoderIDE/Sonus-session-merge-v2-review.md#L195)

````text
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
````

触发：快照复制未退出时取消
预期：等待服务端锁集合清空后解除UI占用
实际：cancelled+非空锁被selector当空集合

判定理由：cancel先写cancelled再异步abort，finally才释放lease；get期间返回实际锁而selector只认preparing。终态缓存还会过早停止更新，后台保护仍有效。

核实范围：cancelled/paused短窗口真实锁仍在但selector隐藏；后端gate保护仍在。

代码：[sessionMergeStore.ts:30](../../../../../frontend/src/store/sessionMergeStore.ts#L30)；[sessionMergeStore.ts:141](../../../../../frontend/src/store/sessionMergeStore.ts#L141)；[SessionMergeService.java:150](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L150)；[SessionMergeService.java:230](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L230)；[SessionMergeService.java:263](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L263)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R07-C04

**跨表写入/来源树隔离尚需验证** — 证据不足 / conditional / scope=new / 不入分；统一问题：snapshot-cross-table-concurrency-boundary；credit=None。

[原文 L247–L247](../QoderIDE/Sonus-session-merge-v2-review.md#L247)

````text
| 4.3：复制截止点与来源树写隔离 | 部分符合／待验证 | 有来源及后代 gate、集合与消息边界复核；尚不能证明所有写入口及跨表并发边界完全闭合。 |
````

触发：
预期：
实际：

判定理由：报告没有指控已发生绕过，只提出所有写入口遵守gate的证明缺口；不将未验证改为产品FP。

代码：[SessionMergeService.java:114](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L114)；[MergePackageService.java:411](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L411)

核验：代码静态核验及原文：见code_refs/lines

### R07-C05

**完整序列化字节/取消退出时效未验证** — 成立 / engineering / scope=new / 不入分；统一问题：serialized-request-and-cancel-verification-gap；credit=None。

[原文 L255–L255](../QoderIDE/Sonus-session-merge-v2-review.md#L255)

````text
| 6.3：单请求预算、响应上限和有限重试 | 部分符合／待验证 | 已有估算预算及大小限制；完整 provider 序列化请求硬上限、取消后的实际退出时效未验证。 |
````

触发：
预期：
实际：

判定理由：明确作为未验证而非宣称已超限；代码只有input字节check、取消依赖provider响应AbortContext，应做针对性故障注入。

代码：[MergeSummaryService.java:212](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L212)；[MergeSummaryService.java:300](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L300)

核验：代码静态核验及原文：见code_refs/lines

### R07-C06

**可信根绑定和同名工具不继承权限** — 成立 / global / scope=new / 不入分；统一问题：builtin-tool-identity-isolation；credit=None。

[原文 L257–L257](../QoderIDE/Sonus-session-merge-v2-review.md#L257)

````text
| 7.2/7.3：可信根绑定、只读工具、不继承同名权限 | 静态符合 | 按真实工具类型分派，分析及 recheck 校验绑定，没有发现普通同名工具自动取得权限的路径。 |
````

触发：
预期：
实际：

判定理由：真实类分派、run主体解析与绑定recheck存在。

代码：[HandoffReadTool.java:29](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L29)；[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)

核验：代码静态核验及原文：见code_refs/lines

### R07-C07

**读取校验目录正文覆盖截止预算** — 成立 / global / scope=new / 不入分；统一问题：read-operation-budget-enforcement；credit=None。

[原文 L258–L258](../QoderIDE/Sonus-session-merge-v2-review.md#L258)

````text
| 7.2：读取分页、完整性、游标和硬限制 | 静态符合 | 15秒检查覆盖 hash 和正文；搜索正文8MiB，输出16KiB，游标绑定版本及参数。 |
````

触发：
预期：
实际：

判定理由：Budget通过hash/catalog/skip/chunk传播；错误码包装另属候选，不否定资源检查本身覆盖。

代码：[HandoffReadService.java:41](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L41)；[HandoffReadService.java:92](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L92)；[HandoffReadService.java:126](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L126)

核验：代码静态核验及原文：见code_refs/lines

### R07-C08

**小模型连续压缩413完整链路未验证** — 成立 / engineering / scope=new / 不入分；统一问题：compaction-handoff-integration-test-gap；credit=None。

[原文 L283–L283](../QoderIDE/Sonus-session-merge-v2-review.md#L283)

````text
已有测试断言覆盖普通分支零交接调用、预算差额和投影不写回历史；**小模型、连续压缩、413恢复的完整执行链仍未验证。**
````

触发：
预期：
实际：

判定理由：普通/E参数化用例存在，不等于已覆盖全部压缩阶段和真实provider413接续。

代码：[QueryEngine.java:1830](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1830)；[HandoffReadServiceTest.java:81](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L81)

核验：代码静态核验及原文：见code_refs/lines

### R07-C09

**迁移是全局启动风险且需旧库启动验证** — 成立 / conditional / scope=new / 不入分；统一问题：migration-global-startup-boundary；credit=None。

[原文 L299–L305](../QoderIDE/Sonus-session-merge-v2-review.md#L299)

````text
### 5. 数据库迁移：影响所有会话，必须单独设发布门禁

**V026 不受会话 metadata 开关控制。**

所有使用该数据库的应用启动都需要执行或校验迁移；一旦迁移失败，普通会话也无法使用。

本轮没有发现受支持旧库必然失败的新增缺陷，但实际旧库升级、完整迁移执行器和再次启动验收尚未完成。
````

触发：
预期：
实际：

判定理由：V026对所有项目启动生效，报告明确未发现受支持旧库必然失败，风险边界描述准确。

代码：[V026_ExtendSessionMerges.java:17](../../../../../backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java#L17)

核验：代码静态核验及原文：见code_refs/lines

### R07-C10

**forkSwarm未改不等于所有派生行为已验证** — 成立 / engineering / scope=new / 不入分；统一问题：fork-swarm-validation-boundary；credit=None。

[原文 L313–L317](../QoderIDE/Sonus-session-merge-v2-review.md#L313)

````text
### 7. fork/Swarm：没有直接修改，不等于零回归

会话管理、消息持久化、子代理执行和 Swarm runner 的相关文件相对 HEAD 没有直接变化。

但它们仍依赖共享引擎、授权、数据库和 gate。**本轮没有运行 fork/Swarm 场景，也没有证明合并目标派生后的全部 metadata 继承行为。**
````

触发：
预期：
实际：

判定理由：既有四文件不变但共享引擎/授权/库仍是依赖，未声称派生metadata已出错。

代码：[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)；[HandoffContextService.java:33](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L33)

核验：代码静态核验及原文：见code_refs/lines

### R07-C11

**恢复分类缺负向断言** — 成立 / engineering / scope=new / 不入分；统一问题：recovery-classification-test-gap；credit=None。

[原文 L344–L344](../QoderIDE/Sonus-session-merge-v2-review.md#L344)

````text
| 恢复投影缺少负向断言 | `MergePackageServiceTest.java:35–49、75–93` 主要验证封存原件恢复 | 无法发现思考字段、续传状态或 base64 进入整理输入。 |
````

触发：
预期：
实际：

判定理由：现有大记录恢复测试只查尾部及封存hash，不禁止thinking/private/base64进入提取。

代码：[MergePackageServiceTest.java:35](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L35)

核验：代码静态核验及原文：见code_refs/lines

### R07-C12

**换模型测试未改变估算阈值** — 成立 / engineering / scope=new / 不入分；统一问题：model-switch-threshold-test-gap；credit=None。

[原文 L345–L345](../QoderIDE/Sonus-session-merge-v2-review.md#L345)

````text
| 换模型测试未覆盖聚合阈值变化 | `MergeSummaryServiceTest.java:23–25` 对不同模型采用相同估算 | 无法发现持久聚合计划与新模型终止条件冲突。 |
````

触发：
预期：
实际：

判定理由：counter mock针对所有模型用相同length/2；无法命中新旧模型跨1536阈值。

代码：[MergeSummaryServiceTest.java:24](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L24)；[SessionMergeServiceTest.java:35](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L35)

核验：代码静态核验及原文：见code_refs/lines

### R07-C13

**取消测试未覆盖复制尚持锁窗口** — 成立 / engineering / scope=new / 不入分；统一问题：cancel-during-copy-test-gap；credit=None。

[原文 L346–L346](../QoderIDE/Sonus-session-merge-v2-review.md#L346)

````text
| 取消测试没有复制阶段延迟解锁窗口 | `sessionMergeStore.test.ts:443` 直接返回空锁 | 无法发现 cancelled 与真实解锁之间的时间差。 |
````

触发：
预期：
实际：

判定理由：store取消mock直接空锁；service取消发生首个LLM内，封存已释放来源，未覆盖复制阶段取消。

代码：[sessionMergeStore.test.ts:443](../../../../../frontend/src/store/sessionMergeStore.test.ts#L443)；[SessionMergeServiceTest.java:130](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L130)

核验：代码静态核验及原文：见code_refs/lines

### R07-C14

**legacy build测试不能作为seal覆盖** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-build-vs-seal-test-gap；credit=None。

[原文 L347–L347](../QoderIDE/Sonus-session-merge-v2-review.md#L347)

````text
| 部分测试仍走旧封装路径 | `MergePackageServiceTest.java:212、247、259、278、293、309` 调用旧 `build`，线上使用 `seal` | 旧图片、checkpoint、路径及复制测试不能自动算作新链路覆盖。 |
````

触发：
预期：
实际：

判定理由：列出的旧测试调用build而生产execute调用seal，覆盖不能自动转移。

代码：[MergePackageServiceTest.java:212](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L212)；[SessionMergeService.java:190](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L190)

核验：代码静态核验及原文：见code_refs/lines

### R07-C15

**fixture未创建artifact表** — 成立 / engineering / scope=new / 不入分；统一问题：artifact-fixture-test-gap；credit=None。

[原文 L348–L348](../QoderIDE/Sonus-session-merge-v2-review.md#L348)

````text
| artifact 导出分支未被夹具激活 | `MergeFixture.java:36–44` 未创建两张 artifact 表；生产代码按表存在与否进入分支 | 无法证明真实产物导出、复制及来源删除后的读取正确。 |
````

触发：
预期：
实际：

判定理由：fixture仅初始化基础/checkpoint/merge等表，未激活tableExists的artifact分支。

代码：[MergeFixture.java:36](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeFixture.java#L36)；[MergePackageService.java:442](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L442)

核验：代码静态核验及原文：见code_refs/lines

### R07-C16

**fixture未代表生产串行器/外键环境** — 成立 / engineering / scope=new / 不入分；统一问题：service-fixture-production-concurrency-gap；credit=None。

[原文 L349–L349](../QoderIDE/Sonus-session-merge-v2-review.md#L349)

````text
| 夹具弱化生产数据库接线 | `MergeFixture.java:45–47` 将写串行器模拟为直接执行，且未显式开启外键 | 不足以代表生产并发和约束环境。 |
````

触发：
预期：
实际：

判定理由：executeWriteVoid被同步直接执行mock，未启SQLite外键；独立迁移测试外键覆盖不等于service夹具生产并发。

代码：[MergeFixture.java:45](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeFixture.java#L45)

核验：代码静态核验及原文：见code_refs/lines

### R07-C17

**部分测试名强于实际断言** — 成立 / engineering / scope=new / 不入分；统一问题：test-name-vs-assertion-gap；credit=None。

[原文 L350–L350](../QoderIDE/Sonus-session-merge-v2-review.md#L350)

````text
| 个别测试名称强于实际断言 | "待办隔离"未调用 TodoWrite；"聚合引用"未核对完整返回范围 | 不能据名称计算能力覆盖。 |
````

触发：
预期：
实际：

判定理由：待办隔离只比较state历史、聚合引用主要解析ref，不代表真实TodoWrite或全部范围核对。

代码：[HandoffReadServiceTest.java:81](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L81)；[HandoffReadServiceTest.java:121](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L121)

核验：代码静态核验及原文：见code_refs/lines

### R07-C18

**发布回滚服务测试未进入发布事务** — 成立 / engineering / scope=new / 不入分；统一问题：publication-transaction-middle-fault-test-gap；credit=None。

[原文 L351–L351](../QoderIDE/Sonus-session-merge-v2-review.md#L351)

````text
| 发布回滚服务用例故障发生较早 | `SessionMergeServiceTest.java:152–158` 在发布事务前失败 | 不能算服务层事务中段失败的完整覆盖。 |
````

触发：
预期：
实际：

判定理由：第二次workspace绑定校验抛异常位于tx之前，因此该用例不验证创建目标后事务中段回滚；独立repo事务用例不能替代。

代码：[SessionMergeServiceTest.java:152](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L152)；[SessionMergeService.java:202](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L202)；[SessionMergeService.java:207](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L207)

核验：代码静态核验及原文：见code_refs/lines

### R07-C19

**面板暂停恢复取消交互覆盖不足** — 成立 / engineering / scope=new / 不入分；统一问题：panel-control-interaction-test-gap；credit=None。

[原文 L352–L352](../QoderIDE/Sonus-session-merge-v2-review.md#L352)

````text
| 新增面板交互覆盖不足 | paused/resume/cancel、目标可用性缺少完整专项覆盖 | 状态仓库单测不能替代真实交互验收。 |
````

触发：
预期：
实际：

判定理由：store有控制测试，panel未覆盖完整点击流程；报告明确区分层次。

代码：[SessionMergePanel.test.tsx:40](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L40)；[sessionMergeStore.test.ts:427](../../../../../frontend/src/store/sessionMergeStore.test.ts#L427)

核验：代码静态核验及原文：见code_refs/lines

### R07-C20

**未跟踪文件须纳入实际提交组合** — 成立 / engineering / scope=out / 不入分；统一问题：selective-commit-completeness；credit=None。

[原文 L368–L370](../QoderIDE/Sonus-session-merge-v2-review.md#L368)

````text
另一个提交完整性风险是：范围内有 **11个未跟踪文件**，包括6个生产文件、4个测试和1份规范。只提交已跟踪修改，会遗漏新服务、迁移等必要依赖。

**审查排除不等于提交排除。** 指定的16个文件没有获得本次审查背书；如果后续选择性提交，实际提交组合仍需重新构建验证。
````

触发：
预期：
实际：

判定理由：当时新增生产/测试/文档需要显式暂存；被排除文件未获审查背书，提交组合应单独验证。

代码：

核验：代码静态核验及原文：见code_refs/lines

## R08

[完整原报告](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)

### R08-normal_function

**判断分：5/5**。[原文 L74–L90](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L74)。正确识别普通请求/图片/工具池隔离，没有无关普通功能损坏指控。

````text
**结论：未发现对非合并分支功能链路的任何负面影响。**

### 3.1 QueryController / WebSocketController

所有新增代码均在 `isMerged` 守卫之后，普通会话完全不进入交接配置路径。`QueryEngine.execute(config, state, handler)` 的原有调用签名和行为保持不变。

### 3.2 QueryEngine 历史预算

`handoffOperationId == null` 时，`handoffProjection()` 返回空投影（`List.of(), 0`），历史预算计算与图片预算逻辑与原实现保持等价。无 token 预算被额外占用。

### 3.3 AuthorizationService 授权层

新增的 `SAFE_READ_AUTO` 放行逻辑仅适用于 `handoff-read-v1` analyzer，普通 MCP/动态工具仍走原有 `static-or-remote-v1`。`recheck` 时强制校验 binding 不变，否则抛 `HANDOFF_BINDING_CHANGED`，防止权限泄露。

### 3.4 ImageRefInjector 图片处理

普通会话调用传入 `(path,hash)->false` 的 BiPredicate，不改变原有 pathSecurity 行为。仅合并会话通过 `handoffContext.canReadAsset` 在交接包范围内放宽读取。
````

### R08-boundary

**判断分：0/5**。[原文 L197–L197](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L197)。宣称所有新增路径均受metadata守卫且基础功能不受任何影响，未识别全局迁移等边界，也无运行验证限制消解。

````text
**总结**：本次改动严格遵循 `session-merge-architecture-v2.md` 的架构规范，通过元数据开关（`sessionMergeOperationId`）实现了会话合并能力与既有功能链路的严格隔离。所有新增代码路径均在守卫条件之后，普通会话的创建、消息收发、权限控制、文件操作等基础功能不受任何影响。测试覆盖充分，代码质量达到 GitHub 发布标准。
````

### R08-release

**判断分：0/5**。[原文 L186–L197](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L186)。明确建议发布/达到发布标准，与尚存确认缺陷和未完成真实语义验收矛盾。

````text
### 判定结果：**Pass — 建议发布**

| 维度 | 评估 |
|---|---|
| 编译状态 | 通过（Java 21，`mvnw compile` 验证成功） |
| 架构合规性 | 全部 8 项规范要求通过 |
| 非合并链路影响 | 无负面影响，隔离机制设计严密 |
| 代码质量 | 无 Critical/High 问题，1 项 Medium 防御性建议 |
| 测试覆盖 | 充分，关键路径均有对应用例 |
| 发布风险 | 低 — 仅存防御性边界建议和 UX 优化建议，不影响功能正确性 |

**总结**：本次改动严格遵循 `session-merge-architecture-v2.md` 的架构规范，通过元数据开关（`sessionMergeOperationId`）实现了会话合并能力与既有功能链路的严格隔离。所有新增代码路径均在守卫条件之后，普通会话的创建、消息收发、权限控制、文件操作等基础功能不受任何影响。测试覆盖充分，代码质量达到 GitHub 发布标准。
````

### R08-semantic

**判断分：0/5**。[原文 L184–L197](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L184)。全文没有真实模型语义验收未完成/不可由stub替代的说明，属于未回答。

````text
## 七、发布就绪度判定

### 判定结果：**Pass — 建议发布**

| 维度 | 评估 |
|---|---|
| 编译状态 | 通过（Java 21，`mvnw compile` 验证成功） |
| 架构合规性 | 全部 8 项规范要求通过 |
| 非合并链路影响 | 无负面影响，隔离机制设计严密 |
| 代码质量 | 无 Critical/High 问题，1 项 Medium 防御性建议 |
| 测试覆盖 | 充分，关键路径均有对应用例 |
| 发布风险 | 低 — 仅存防御性边界建议和 UX 优化建议，不影响功能正确性 |

**总结**：本次改动严格遵循 `session-merge-architecture-v2.md` 的架构规范，通过元数据开关（`sessionMergeOperationId`）实现了会话合并能力与既有功能链路的严格隔离。所有新增代码路径均在守卫条件之后，普通会话的创建、消息收发、权限控制、文件操作等基础功能不受任何影响。测试覆盖充分，代码质量达到 GitHub 发布标准。
````

### R08-C01

**ScheduledExecutorService不可用于try-with-resources已撤回** — 不成立 / retracted / scope=new / 不入分；统一问题：executor-autocloseable-compilation；credit=None。

[原文 L104–L104](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L104)

````text
> **说明**：初步审查曾标记 `MergeSummaryService.call` 中 `ScheduledExecutorService` 用于 try-with-resources 为编译错误。经交叉验证确认为**误报**——项目使用 Java 21（pom.xml 配置 `<java.version>21</java.version>`，运行时为 OpenJDK 21.0.10 Corretto），而 `ExecutorService` 自 Java 19 起已实现 `AutoCloseable`。`mvnw compile` 验证编译通过，无任何错误。
````

触发：
预期：
实际：

判定理由：Java21 ExecutorService继承AutoCloseable；最终报告已明确撤回，不计FP。

代码：[MergeSummaryService.java:302](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L302)

核验：代码静态核验及原文：见code_refs/lines

### R08-C02

**缺HandoffContext Bean的未来入口应降级** — 证据不足 / conditional / scope=new / 不入分；统一问题：hypothetical-missing-handoff-bean；credit=None。

[原文 L110–L123](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L110)

````text
#### M1：HandoffContextService 依赖注入防御性边界

**文件**：`backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java`

**问题描述**：当 `handoffOperationId != null` 但 `handoffContext` Bean 缺失时（`@Autowired(required=false)`），直接抛出 `IllegalStateException("HANDOFF_CONTEXT_UNAVAILABLE")`。当前架构下不存在真实触发路径（所有设置 `handoffOperationId` 的入口都运行在有该 Bean 的 Spring 容器中），但未来若有新入口（如测试夹具或 CLI 工具）在不加载 `HandoffContextService` Bean 的上下文中设置了该字段，将导致运行时崩溃而非优雅降级。

**建议**：补充防御性日志或集成测试覆盖此边界：

```java
if (state.getHandoffOperationId() != null && handoffContext == null) {
    log.error("Merged session flag present but HandoffContextService is unavailable");
    return new HandoffContextService.Projection(List.of(), 0);
}
```
````

触发：
预期：
实际：

判定理由：报告承认生产无触发路径。抛错为fail-closed可防静默丢失交接，建议返回空投影并非显然更正确；不算已证功能缺陷。

代码：[QueryEngine.java:1813](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1813)；[HandoffContextService.java:43](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L43)

核验：代码静态核验及原文：见code_refs/lines

### R08-C03

**暂停面板未直接展示errorCode** — 成立 / engineering / scope=new / 不入分；统一问题：panel-error-code-visibility；credit=None。

[原文 L127–L133](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L127)

````text
#### L1：前端合并面板 errorCode 展示不足

**文件**：`frontend/src/components/session/SessionMergePanel.tsx`

**问题描述**：当合并暂停时，面板未向用户展示具体的 `errorCode`（如 `MERGE_SOURCE_BUSY`、`MERGE_DISK_SPACE_LOW`），用户难以判断恢复条件。

**建议**：在 panel 的暂停状态文案中补充 `errorCode` 的简短说明，帮助用户理解暂停原因和恢复条件。
````

触发：
预期：
实际：

判定理由：panel确实不渲染errorCode字段，但渲染服务端已翻译的error说明，不能由此声称用户完全不知道恢复条件。可保留诊断UX建议。

代码：[SessionMergePanel.tsx:207](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L207)；[SessionMergeService.java:237](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L237)

核验：代码静态核验及原文：见code_refs/lines

### R08-C04

**删除E后应提示仍可HandoffRead读取** — 不成立 / engineering / scope=new / 不入分；统一问题：deleted-target-handoff-read-claim；credit=None。

[原文 L135–L141](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L135)

````text
#### L2：目标会话删除提示可优化

**文件**：`frontend/src/components/session/SessionMergePanel.tsx`

**问题描述**：`targetAvailable === false` 时面板显示"目标会话已删除"，但未提示合并资料的保留情况。

**建议**：补充"合并资料仍保留在交接包中，可通过 HandoffRead 读取"的说明，与后端语义保持一致。
````

触发：
预期：
实际：

判定理由：目标删除立即使binding JOIN失效，包可能等清理但已不能通过该E的HandoffRead读取；建议文案声称与后端一致不正确。它是建议中的错误事实，不应实施。

代码：[MergeProgressRepository.java:37](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L37)；[HandoffReadService.java:46](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L46)；[SessionMergeService.java:271](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L271)

核验：代码静态核验及原文：见code_refs/lines

### R08-C05

**selector直接依据真实锁集合符合规范** — 不成立 / global / scope=new / 不入分；统一问题：D15；credit=None。

[原文 L66–L66](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L66)

````text
| 前端 store 锁集合基于服务端 | **通过** | `selectMergeSourceIds` 直接基于 `lockedSourceSessionIds` |
````

触发：取消复制但真实gate尚持有
预期：以真实锁集合为准
实际：状态过滤忽略真实锁

判定理由：漏掉preparing门控，取消而worker未退出时服务端锁非空但selector返回空，是具体锁语义保证错误。；统一复核：概括性UI符合保证只保留事实裁定，漏报由FN体现，不加整体判断FP。

代码：[sessionMergeStore.ts:30](../../../../../frontend/src/store/sessionMergeStore.ts#L30)；[SessionMergeService.java:150](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L150)；[SessionMergeService.java:230](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L230)

核验：代码静态核验及原文：见code_refs/lines

### R08-C06

**同名工具与普通图片权限保持隔离** — 成立 / global / scope=new / 不入分；统一问题：builtin-tool-identity-isolation；credit=None。

[原文 L86–L90](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L86)

````text
新增的 `SAFE_READ_AUTO` 放行逻辑仅适用于 `handoff-read-v1` analyzer，普通 MCP/动态工具仍走原有 `static-or-remote-v1`。`recheck` 时强制校验 binding 不变，否则抛 `HANDOFF_BINDING_CHANGED`，防止权限泄露。

### 3.4 ImageRefInjector 图片处理

普通会话调用传入 `(path,hash)->false` 的 BiPredicate，不改变原有 pathSecurity 行为。仅合并会话通过 `handoffContext.canReadAsset` 在交接包范围内放宽读取。
````

触发：
预期：
实际：

判定理由：专用分析器按真实类分派，旧图片入口传false，新范围绑定检查局限E。

代码：[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)；[ImageRefInjector.java:96](../../../../../backend/src/main/java/com/aicodeassistant/engine/ImageRefInjector.java#L96)；[HandoffContextService.java:54](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L54)

核验：代码静态核验及原文：见code_refs/lines

### R08-C07

**关键测试充分覆盖全部流程的总结** — 证据不足 / engineering / scope=new / 不入分；统一问题：test-sufficiency-overstatement；credit=None。

[原文 L149–L155](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L149)

````text
| 授权层 | `AuthorizationServiceProjectFileScopeTest` / `OperationAnalyzerRegistryTest` | **充分** — 验证了 handoff-read-v1 专用 analyzer 和同名工具走原有路径 |
| QueryEngine | `QueryEngineUnitTest` | **充分** — 验证了空投影路径和历史预算不变 |
| 合并核心流程 | `SessionMergeServiceTest` | **充分** — 覆盖了快照封存、恢复 epoch 递增、目标删除后绑定失效、取消等场景 |
| 合并摘要 | `MergeSummaryServiceTest` | **充分** — 验证了分块聚合和 ready.json 生成 |
| 合并资料包 | `MergePackageServiceTest` / `MergeFixture` | **充分** — 验证了快照校验逻辑 |
| 前端 store | `sessionMergeStore.test.ts` | **充分** — 包含串行化控制、状态恢复、终态去重等用例 |
| 前端面板 | `SessionMergePanel.test.tsx` | **充分** — 覆盖了各 stage 文案和条件渲染 |
````

触发：
预期：
实际：

判定理由：存在所列测试文件和相关断言，但充分性缺量化依据，恢复分类/取消复制等负向覆盖缺失；未指控伪造，不能把主观充分评价当产品FP。

代码：[MergePackageServiceTest.java:35](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L35)；[sessionMergeStore.test.ts:443](../../../../../frontend/src/store/sessionMergeStore.test.ts#L443)

核验：代码静态核验及原文：见code_refs/lines

### R08-C08

**终态缓存及控制串行机制存在** — 成立 / global / scope=new / 不入分；统一问题：frontend-terminal-cache-mechanism；credit=None。

[原文 L177–L180](../QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L177)

````text
- `sessionMergeStore` 的 `refresh()` 通过 `/api/session-merges/active` 做轻量背景发现，`nextActiveCheck` 控制轮询频率
- 服务端返回 Conflict 且 body 包含 `operation` 时，采用服务端权威结果替换本地 pending
- 终态使用 `validatedTerminal` 集合避免重复通知
- `resume/cancel` 通过 `control(...)` 串行化控制请求与 `refresh` 的并发
````

触发：
预期：
实际：

判定理由：代码有validatedTerminal和统一inflight。该段只描述机制本身，不将存在缓存扩写为所有恢复场景正确。

代码：[sessionMergeStore.ts:141](../../../../../frontend/src/store/sessionMergeStore.ts#L141)；[sessionMergeStore.ts:226](../../../../../frontend/src/store/sessionMergeStore.ts#L226)

核验：代码静态核验及原文：见code_refs/lines

## R09

[完整原报告](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)

### R09-normal_function

**判断分：5/5**。[原文 L63–L77](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L63)。无关普通分支保持原工具和预算，结论符合本轮代码核验。

````text
## 三、对非合并分支的影响评估

**结论：未发现对普通/fork/Swarm 主链路的破坏，隔离证据链完整。**

| 隔离点 | 证据 |
|---|---|
| 投影零开销 | `QueryEngine.java` L1811 `handoffProjection` 在 `handoffOperationId==null` 时直接返回空投影；`QueryEngineUnitTest` 以 `verifyNoInteractions(handoff)` 证明普通请求不触碰任何合并服务 |
| 入口门控 | 3 个 REST 入口 + WS 路径均以 `isMerged(session.config())` 前置判断；普通会话 metadata 无 `sessionMergeOperationId`，`configure` 原样返回同一 QueryConfig 实例（`HandoffContextServiceTest.ordinaryRequestRetainsTheExactConfiguration` 断言 `isSameAs`） |
| 工具池隔离 | `HandoffReadTool` 无 `@Component` 注解（测试显式断言），不进入全局工具注册表/schema/token 计算 |
| 图片注入 | `ImageRefInjector` 新重载默认谓词恒为 false，仅 E 请求传真实绑定谓词 |
| 迁移安全 | V026 事务内重建表、逐列复制旧行，旧行 `protocol=1/active_slot=NULL` 保留，旧 preparing→failed/LEGACY_INTERRUPTED；`V026ExtendSessionMergesTest` 覆盖再执行幂等/CHECK/唯一名额/FK |
| 基线文件 | 规范第 10 节要求"保持不改"的 SessionManager、SessionMessagePersistence、SubAgentExecutor、SwarmWorkerRunner、SessionExecutionGate、ToolRegistry 均不在改动列表（git status 核实） |
| 预算不双扣 | phase1 扣 `reservedTokens`、phase2 仍用原 `inputBudget`；普通会话投影为空、预留为 0，行为与改动前完全一致 |

**需知悉的既定设计**：前端新增每 5 秒节流一次的 `GET /api/session-merges/active` 后台轮询（所有页面，无操作时返回 204）。这是规范要求的重启续跑能力，开销极小，属可接受项。
````

### R09-boundary

**判断分：5/5**。[原文 L67–L77](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L67)。列出全局迁移与所有页面后台请求并限定接受的开销，没有单凭守卫声称无任何共享变化。

````text
| 隔离点 | 证据 |
|---|---|
| 投影零开销 | `QueryEngine.java` L1811 `handoffProjection` 在 `handoffOperationId==null` 时直接返回空投影；`QueryEngineUnitTest` 以 `verifyNoInteractions(handoff)` 证明普通请求不触碰任何合并服务 |
| 入口门控 | 3 个 REST 入口 + WS 路径均以 `isMerged(session.config())` 前置判断；普通会话 metadata 无 `sessionMergeOperationId`，`configure` 原样返回同一 QueryConfig 实例（`HandoffContextServiceTest.ordinaryRequestRetainsTheExactConfiguration` 断言 `isSameAs`） |
| 工具池隔离 | `HandoffReadTool` 无 `@Component` 注解（测试显式断言），不进入全局工具注册表/schema/token 计算 |
| 图片注入 | `ImageRefInjector` 新重载默认谓词恒为 false，仅 E 请求传真实绑定谓词 |
| 迁移安全 | V026 事务内重建表、逐列复制旧行，旧行 `protocol=1/active_slot=NULL` 保留，旧 preparing→failed/LEGACY_INTERRUPTED；`V026ExtendSessionMergesTest` 覆盖再执行幂等/CHECK/唯一名额/FK |
| 基线文件 | 规范第 10 节要求"保持不改"的 SessionManager、SessionMessagePersistence、SubAgentExecutor、SwarmWorkerRunner、SessionExecutionGate、ToolRegistry 均不在改动列表（git status 核实） |
| 预算不双扣 | phase1 扣 `reservedTokens`、phase2 仍用原 `inputBudget`；普通会话投影为空、预留为 0，行为与改动前完全一致 |

**需知悉的既定设计**：前端新增每 5 秒节流一次的 `GET /api/session-merges/active` 后台轮询（所有页面，无操作时返回 204）。这是规范要求的重启续跑能力，开销极小，属可接受项。
````

### R09-release

**判断分：0/5**。[原文 L118–L137](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L118)。未明确回答当前能否作为功能完成版合入发布；允许提交/推送和披露语义未验收本身并非错误，不据此捏造赞成正式发布。

````text
### 发布标准结论

**代码质量与测试覆盖达到提交 GitHub 的标准**；隔离设计经单测强断言（`verifyNoInteractions`/`isSameAs`/非 Bean 断言）保障，非合并链路无破坏。

**两点必须在发布说明中明示**：

1. **真实模型语义验收尚未执行**（文档自述缺口）——摘要/提取/聚合的模型调用在测试中均为 mock，`MergeSummaryService.call()` 的真实行为（含 4183 字节分片回归）未经真实模型验证；
2. **建议先修复问题 1（E 会话性能）与问题 2（违反"必须保留"级规范条款）再推送**，问题 3-5 可随后跟进。

---

## 六、最终判定

| 维度 | 结论 |
|---|---|
| 非合并分支影响 | **无负面影响或破坏**，隔离证据链完整（零调用隔离 + 入口门控 + 工具池隔离 + 迁移无损 + 基线文件零改动） |
| 代码质量 | 与规范逐条对应，架构实现完整；无 critical 级缺陷，无数据丢失或安全漏洞 |
| 测试覆盖 | 规范第 9 节矩阵全覆盖，约 19 个新增测试文件 |
| 发现问题 | 2 major + 3 minor（详见第四节），经双子代理交叉验证 |
| 发布建议 | 改动可以提交；建议以"修复问题 1、2 + 发布说明注明模型验收缺口"作为推送前置条件；若接受带已知缺陷推送，则当前状态不构成阻断 |
````

### R09-semantic

**判断分：5/5**。[原文 L122–L124](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L122)。明确真实模型语义未执行，mock不能代表真实模型验证。

````text
**两点必须在发布说明中明示**：

1. **真实模型语义验收尚未执行**（文档自述缺口）——摘要/提取/聚合的模型调用在测试中均为 mock，`MergeSummaryService.call()` 的真实行为（含 4183 字节分片回归）未经真实模型验证；
````

### R09-C01

**E每轮重复投影校验缺少请求内复用** — 成立 / conditional / scope=new / 不入分；统一问题：repeated-turn-handoff-verification；credit=None。

[原文 L92–L92](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L92)

````text
| 1 | major | **合并会话 E 每 turn 重复执行昂贵投影**：`project()` 每轮被调 3 次（L779/L844 经 `prepareCompactionContext` 各一次 + L841 直调），反应式压缩路径（L1933/L1973）还会追加；每次均执行 DB 绑定查询 + 整包哈希校验 + brief 文件读取，无任何请求级缓存 | 在 `QueryLoopState` 或请求作用域内缓存投影结果（同一 turn 预算参数相同时复用），或将 `prepareCompactionContext` 两次调用合并为一次投影计算 | `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` L779-L844；`HandoffContextService.java` L62-L79 |
````

触发：
预期：
实际：

判定理由：正常轮至少779/841/844三次调用project，brief各自新Budget重新哈希；影响规模和性能劣化尚未量化，报告major等级不照抄。

代码：[QueryEngine.java:779](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L779)；[QueryEngine.java:841](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L841)；[QueryEngine.java:844](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L844)；[HandoffContextService.java:71](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L71)；[HandoffReadService.java:110](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L110)

核验：代码静态核验及原文：见code_refs/lines

### R09-C02

**缺失v1包导致永久暂停** — 规范歧义 / functional / scope=new / 不入分；统一问题：missing-legacy-package-policy；credit=None。

[原文 L93–L93](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L93)

````text
| 2 | major | **缺失 legacy v1 包被记为阻塞缺口导致永久暂停**：`importPrevious` 对缺失旧包记 `gap(...,true)`（blocking），转暂停后恢复时 `validateSnapshot` 重读同一 blockedReason 必然再次暂停，不可自愈。违反规范"不依赖旧包存活"（第 19 行）与"仅收录现存旧包、说明去重局限"（第 216 行）——旧包可随目标删除被合法清理（第 187 行），阻塞会使此类来源永远无法再合并 | 将 `legacy_package_missing` 降为非阻塞缺口（`gap(...,false)`），以来源现有 DB 消息 + 新消息继续合并，并在结果警告中说明无法跨包精确去重；补充对应测试用例 | `backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java` L606-L611 |
````

触发：
预期：
实际：

判定理由：行为属实，但旧协议资料完整性与非依赖旧包的规范边界有张力，不能直接降级历史完整性或当成其他恢复bug半命中。

代码：[MergePackageService.java:606](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L606)；[SessionMergeService.java:195](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L195)；[session-merge-architecture-v2.md:216](../../../../session-merge-architecture-v2.md#L216)

核验：代码静态核验及原文：见code_refs/lines

### R09-C03

**取消worker仍打印Merge paused** — 成立 / engineering / scope=new / 不入分；统一问题：cancel-log-says-paused；credit=None。

[原文 L94–L94](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L94)

````text
| 3 | minor | **取消路径误打"Merge paused"日志**：cancel 使工作线程抛 `MERGE_INTERRUPTED`（L176），catch 块（L218-219）对取消同样打 "Merge paused" warn。数据库状态正确（cancel 已原子置 cancelled 且后续 pause UPDATE 命中 0 行），仅日志误导排障 | 在 catch 中区分 `MERGE_INTERRUPTED` 与取消语义，按实际终态输出日志文案 | `backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java` L218-L219 |
````

触发：执行中的合并被取消，worker检查取消
预期：日志说明cancelled
实际：warn文本Merge paused

判定理由：abort导致check抛MERGE_INTERRUPTED被统一catch打印paused，但DB取消状态受epoch保护仍cancelled，日志误导不影响资料。；统一复核：日志记paused与持久cancelled不一致，但只是诊断措辞，未改变功能状态；工程项不作产品缺陷。

代码：[SessionMergeService.java:176](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L176)；[SessionMergeService.java:219](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L219)

核验：代码静态核验及原文：见code_refs/lines

### R09-C04

**仓库自建与Spring实例重复** — 成立 / engineering / scope=new / 不入分；统一问题：repository-manual-construction；credit=None。

[原文 L95–L95](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L95)

````text
| 4 | minor | **自建仓储实例绕过 Spring 容器**：`MergeProgressRepository` 带 `@Repository` 注解，但 `SessionMergeService` 构造函数 `new` 自建实例，容器内存在双实例；与 `HandoffContextService`/`HandoffReadService` 的注入方式不一致。因共享同一 JdbcTemplate 且仓储无状态，功能等价、无事务风险，仅一致性问题 | 改为构造函数注入 `MergeProgressRepository` Bean，删除自建实例 | `backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java` L72 |
````

触发：
预期：
实际：

判定理由：服务new仓库，别处注入Bean，但仓库无状态且共用同一JDBC/事务管理器；未产生独立事务风险。

代码：[SessionMergeService.java:72](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L72)；[MergeProgressRepository.java:20](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L20)

核验：代码静态核验及原文：见code_refs/lines

### R09-C05

**validatedTerminal集合仅部分清理** — 成立 / conditional / scope=new / 不入分；统一问题：terminal-cache-id-retention；credit=None。

[原文 L96–L96](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L96)

````text
| 5 | minor | **`validatedTerminal` Set 缺少完整清理**（1/2 验证者确认，附注纳入）：终态操作 ID 只增，仅 `openDialog` 时 delete。因 active_slot 唯一且页面重载即清空，实际条目极少、影响可忽略 | 可在记录被 `removeSaved` 时同步 delete，或保持现状并加注释说明生命周期 | `frontend/src/store/sessionMergeStore.ts` L115 |
````

触发：
预期：
实际：

判定理由：add后通常只有openDialog delete，dismiss不清；页面重载清空且每操作一键，属于有界实际场景下未量化内存风险。

代码：[sessionMergeStore.ts:115](../../../../../frontend/src/store/sessionMergeStore.ts#L115)；[sessionMergeStore.ts:121](../../../../../frontend/src/store/sessionMergeStore.ts#L121)；[sessionMergeStore.ts:188](../../../../../frontend/src/store/sessionMergeStore.ts#L188)；[sessionMergeStore.ts:218](../../../../../frontend/src/store/sessionMergeStore.ts#L218)

核验：代码静态核验及原文：见code_refs/lines

### R09-C06

**小预算错误作为既定设计无需处理** — 证据不足 / global / scope=new / 不入分；统一问题：D09；credit=None。

[原文 L81–L81](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L81)

````text
1. `project()` 在预算极小/包不可读时抛 `IllegalStateException` 使 E 请求报错——规范 7.1/7.3 明确要求"连最小入口都放不下时返回正常容量错误""包损坏报明确错误"，且异常被 QueryEngine 捕获转为会话错误消息（非 500）。
````

触发：
预期：
实际：

判定理由：QueryEngine外层确实捕获并转QueryResult/handler，不可因局部无catch推导HTTP500；但IllegalStateException没有走正常容量错误类型，报告称完全符合容量错误要求仍需沿入口响应核对。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)；[QueryController.java:180](../../../../../backend/src/main/java/com/aicodeassistant/controller/QueryController.java#L180)；[session-merge-architecture-v2.md:371](../../../../session-merge-architecture-v2.md#L371)

核验：代码静态核验及原文：见code_refs/lines

### R09-C07

**control等候inflight后赋值无JS并发窗口** — 成立 / global / scope=new / 不入分；统一问题：frontend-control-request-serialization；credit=None。

[原文 L82–L82](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L82)

````text
2. 前端 `control()` 的 inflight 串行在 while 退出至赋值间无 await 插入点，JS 单线程下无竞态，后端另有 epoch 围栏兜底。
````

触发：
预期：
实际：

判定理由：while await每次醒来重新检查inflight，离开while后到赋值没有await，JS单线程可防两个控制请求同时起飞。

代码：[sessionMergeStore.ts:226](../../../../../frontend/src/store/sessionMergeStore.ts#L226)

核验：代码静态核验及原文：见code_refs/lines

### R09-C08

**规范测试矩阵基本全覆盖** — 不成立 / engineering / scope=new / 不入分；统一问题：merge-test-matrix-complete-claim；credit=None。

[原文 L104–L116](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L104)

````text
规范第 9 节测试矩阵基本全覆盖：

- 迁移安全（6 场景：旧行保留/再执行幂等/CHECK 约束/唯一 active 名额/重复 target 回滚/外键）
- 封存后来源释放与消息排除
- 普通请求零调用隔离（`verifyNoInteractions` 强断言）
- 跨包/路径穿越/symlink 拒绝、损坏目录
- 预算超限、游标连续性、跨分片搜索与代理对、15 秒超时
- 单元恢复不重做、取消围栏迟到响应、发布失败回滚、重启续跑、绑定失效
- 同名工具冒充走 generic 分析器、PLAN 模式授权放行
- 前端进度/暂停/恢复/取消 UI
- 4183 字节/13370 输出的极端记录合成回归

新增测试约 19 个文件，断言密度高，与规范条款逐条对应。
````

触发：
预期：
实际：

判定理由：现有面板无完整暂停恢复取消点击、artifact夹具及真实模型缺口，且测试文件计数19不符本次13代码测试文件；这是覆盖夸大，不等同缺陷误报。

代码：[MergeFixture.java:36](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeFixture.java#L36)；[SessionMergePanel.test.tsx:40](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L40)；[MergeSummaryServiceTest.java:24](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L24)

核验：代码静态核验及原文：见code_refs/lines

### R09-C09

**读取路径拒绝越界和symlink测试存在** — 成立 / global / scope=new / 不入分；统一问题：handoff-read-path-containment；credit=None。

[原文 L109–L109](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L109)

````text
- 跨包/路径穿越/symlink 拒绝、损坏目录
````

触发：
预期：
实际：

判定理由：HandoffReadServiceTest确有ref穿越/symlink拒绝；未明确扩写到全部来源复制分支，因此不拿复制symlink漏项惩罚这句。

代码：[HandoffReadServiceTest.java:66](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L66)；[MergePackageService.java:304](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L304)

核验：代码静态核验及原文：见code_refs/lines

### R09-C10

**4183字节等合成回归不是真实模型验收** — 成立 / engineering / scope=new / 不入分；统一问题：semantic-vs-synthetic-test-boundary；credit=None。

[原文 L124–L124](../TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L124)

````text
1. **真实模型语义验收尚未执行**（文档自述缺口）——摘要/提取/聚合的模型调用在测试中均为 mock，`MergeSummaryService.call()` 的真实行为（含 4183 字节分片回归）未经真实模型验证；
````

触发：
预期：
实际：

判定理由：测试stub构造响应、无真实provider接续开发样本，描述边界准确。

代码：[MergeSummaryServiceTest.java:55](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L55)

核验：代码静态核验及原文：见code_refs/lines

## R10

[完整原报告](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)

### R10-normal_function

**判断分：5/5**。[原文 L49–L63](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L49)。普通工具/预算/保存机制判断与核验一致。

````text
## 问题 1：对非合并分支的影响评估 —— ✅ 隔离性合格

逐接点核实结果：

| 接点 | 隔离机制 | 结论 |
|---|---|---|
| `QueryController`（3 个入口）/ `WebSocketController` | `isMerged(metadata)` 为 false 时不调用交接服务、不查合并表 | ✅ 测试验证 `verifyNoInteractions` |
| `QueryEngine` | `handoffOperationId==null` → 空投影，`historyBudget==inputBudget`，phase1/phase2 与原路径一致 | ✅ 参数化测试断言普通路径预算差为 0 |
| `ImageRefInjector` | 原 7 参方法委托新重载并传恒 false 谓词，行为不变 | ✅ |
| `AuthorizationService` | 仅新增 `handoff-read-v1` 分支；`instanceof` 分派使同名 MCP/动态工具无法继承权限 | ✅ 专项测试覆盖冒充者 |
| `HandoffReadTool` | 未注册为 Spring Bean，不进全局工具池 | ✅ 测试断言无 `@Component` |
| `SessionManager` / `SessionMessagePersistence` / `SubAgentExecutor` / `SwarmWorkerRunner` / `SessionExecutionGate` | 零改动（git 确认） | ✅ 对应回归测试全部通过 |
| `V026` 迁移 | 不动 V024 checksum；旧行保留、旧 preparing 转 failed/LEGACY_INTERRUPTED；回滚安全 | ✅ 迁移测试通过 |

另核实：`SubAgentExecutor`、`AgentResumeService`、`SwarmWorkerRunner` 的 `queryEngine.execute` 调用点均使用独立 `QueryLoopState`，不携带交接标记，符合文档"普通/fork/Swarm 不自动注入交接入口"的要求。
````

### R10-boundary

**判断分：5/5**。[原文 L55–L63](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L55)。列明公共授权/图片/全局V026迁移及测试证据，不按一般无负面影响表述机械扣分。

````text
| `QueryController`（3 个入口）/ `WebSocketController` | `isMerged(metadata)` 为 false 时不调用交接服务、不查合并表 | ✅ 测试验证 `verifyNoInteractions` |
| `QueryEngine` | `handoffOperationId==null` → 空投影，`historyBudget==inputBudget`，phase1/phase2 与原路径一致 | ✅ 参数化测试断言普通路径预算差为 0 |
| `ImageRefInjector` | 原 7 参方法委托新重载并传恒 false 谓词，行为不变 | ✅ |
| `AuthorizationService` | 仅新增 `handoff-read-v1` 分支；`instanceof` 分派使同名 MCP/动态工具无法继承权限 | ✅ 专项测试覆盖冒充者 |
| `HandoffReadTool` | 未注册为 Spring Bean，不进全局工具池 | ✅ 测试断言无 `@Component` |
| `SessionManager` / `SessionMessagePersistence` / `SubAgentExecutor` / `SwarmWorkerRunner` / `SessionExecutionGate` | 零改动（git 确认） | ✅ 对应回归测试全部通过 |
| `V026` 迁移 | 不动 V024 checksum；旧行保留、旧 preparing 转 failed/LEGACY_INTERRUPTED；回滚安全 | ✅ 迁移测试通过 |

另核实：`SubAgentExecutor`、`AgentResumeService`、`SwarmWorkerRunner` 的 `queryEngine.execute` 调用点均使用独立 `QueryLoopState`，不携带交接标记，符合文档"普通/fork/Swarm 不自动注入交接入口"的要求。
````

### R10-release

**判断分：0/5**。[原文 L92–L96](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L92)。未明确回答当前能否作为功能完成版合入发布；允许提交/推送和披露语义未验收本身并非错误，不据此捏造赞成正式发布。

````text
## 结论

1. **非合并分支功能链路无负面影响**，隔离机制有测试背书。
2. **代码质量、测试覆盖与文档规范符合度均达到提交 GitHub 的标准**：本次实测后端合并核心 76 项 + 隔离性批次 + `QueryEngineUnitTest` 65 项全部通过；前端 894 通过 / 16 跳过 / 0 失败；生产构建通过。
3. 5 项问题全部为 minor/low，不阻塞提交；其中 4 项 minor 可在后续迭代中低成本修复。
````

### R10-semantic

**判断分：5/5**。[原文 L88–L90](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L88)。准确说明真实模型语义验收未做，stub不应冒充通过。

````text
## 残留风险提示

文档第 9 节的**真实模型语义验收**（接续开发样本评测）尚未执行——这是文档自述的边界，不阻塞代码提交，但发布说明中不应把 stub 测试描述为语义验收通过。
````

### R10-C01

**E每轮重复投影成本** — 成立 / conditional / scope=new / 不入分；统一问题：repeated-turn-handoff-verification；credit=None。

[原文 L78–L78](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L78)

````text
| 1 | **每轮最多 3 次重复交接投影**：`handoffProjection` 在预循环压缩（L779）、主流程（L841）、`prepareCompactionContext`（L1835）各执行一次，每次都做绑定查询 + 9 个文件全量哈希校验 + 读 brief。仅影响合并会话，无正确性问题（2/2 确认） | 每轮缓存一次 `Projection` 结果复用，或在 `QueryLoopState` 上暂存 | `QueryEngine.java` L779/L841/L1835；`HandoffContextService.java` L59-80 | minor |
````

触发：
预期：
实际：

判定理由：project被多次调用，每次brief新建预算重算清单hash；报告明确无正确性问题，未测性能严重性。

代码：[QueryEngine.java:779](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L779)；[QueryEngine.java:841](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L841)；[QueryEngine.java:844](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L844)；[HandoffReadService.java:110](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L110)

核验：代码静态核验及原文：见code_refs/lines

### R10-C02

**configure绑定错误落入泛型500并掩盖原因** — 规范歧义 / conditional / scope=new / 不入分；统一问题：handoff-binding-error-500；credit=None。

[原文 L79–L79](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L79)

````text
| 2 | **交接异常的用户可见形态不统一**：`HANDOFF_BINDING_MISMATCH`/`HANDOFF_NOT_BOUND` 在 controller 层抛出后落入泛型 500 且消息被掩盖；`HANDOFF_CONTEXT_BUDGET_TOO_SMALL` 在引擎内转为裸错误码文本，非文档 7.1 要求的"正常容量错误"。不会污染合并状态（2/2 确认） | 为绑定类异常注册明确的 4xx 响应；预算不足映射为现有容量错误码 | `HandoffContextService.java` L42/L74；`QueryController.java` L178 | minor |
````

触发：E元数据与权威绑定不符或权威绑定缺失
预期：明确提示交接绑定错误，供诊断处理
实际：HTTP500 INTERNAL_ERROR / An unexpected error occurred

判定理由：configure在QueryEngine.execute外抛IllegalStateException/AuthorizationException；GlobalExceptionHandler未设对应handler而由138行泛型异常处理返回500 INTERNAL_ERROR，丢失HANDOFF_BINDING_MISMATCH/HANDOFF_NOT_BOUND。此与预算project在engine内捕获是两条不同路径。；统一复核：坏绑定fail-closed有规范依据，不能与受支持的小预算错误通道混同。

代码：[HandoffContextService.java:42](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L42)；[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryController.java:178](../../../../../backend/src/main/java/com/aicodeassistant/controller/QueryController.java#L178)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)；[GlobalExceptionHandler.java:138](../../../../../backend/src/main/java/com/aicodeassistant/controller/GlobalExceptionHandler.java#L138)

核验：代码静态核验及原文：见code_refs/lines

### R10-C03

**工具说明未含现状差异处理和复测指引** — 成立 / engineering / scope=new / 不入分；统一问题：handoff-tool-guidance-incomplete；credit=None。

[原文 L80–L80](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L80)

````text
| 3 | **工具说明缺少文档 7.1 要求的指引文案**：`HandoffRead.getDescription()` 未含"涉及代码修改时读取相关现有文件、历史与现状有差异则说明、必要时复测"。入口投影/header 文案仅覆盖"核对当前代码"，"必要时复测"两处均缺（2/2 确认） | 在工具说明末尾补一句差异处理与复测指引 | `HandoffReadTool.java` L16 | minor |
````

触发：
预期：
实际：

判定理由：工具及最小入口缺完整373行指引，属可改的提示规范缺口；没有真实模型证据证明已导致错误代码。

代码：[HandoffReadTool.java:16](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L16)；[HandoffContextService.java:68](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L68)；[session-merge-architecture-v2.md:373](../../../../session-merge-architecture-v2.md#L373)

核验：代码静态核验及原文：见code_refs/lines

### R10-C04

**旧包缺失暂停属于设计确认点** — 规范歧义 / conditional / scope=new / 不入分；统一问题：missing-legacy-package-policy；credit=None。

[原文 L81–L81](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L81)

````text
| 4 | **v1 旧包缺失时整体暂停（设计确认点）**：`legacy_package_missing` 为阻断 gap。验证者存在分歧：一方认为与第 1 节"不依赖旧包存活"有字面张力；另一方认为 v1 目标自身无原始内容，缺包时继续会"冒充完整记录"，暂停属合理 fail-closed 且可恢复（1/2 确认） | 建议保留现状，在文档 4.4 节补一句"旧包缺失时暂停而非降级"以消除歧义 | `MergePackageService.java` L606-608 | low |
````

触发：
预期：
实际：

判定理由：正确记录争议；但其“可恢复”字样在已封存blocking gap下并不成立，不能靠还原旧源目录使本包缺口消失。整体以设计确认点呈现，不给其他恢复缺陷半分。

代码：[MergePackageService.java:608](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L608)；[SessionMergeService.java:195](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L195)；[session-merge-architecture-v2.md:216](../../../../session-merge-architecture-v2.md#L216)

核验：代码静态核验及原文：见code_refs/lines

### R10-C05

**补记封存未校验来源集合** — 成立 / engineering / scope=new / 不入分；统一问题：sealed-ledger-source-set-check；credit=None。

[原文 L82–L82](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L82)

````text
| 5 | **补记路径未比对来源集合**：seal 已写盘但 DB 未记录时，`validateSnapshot` 校验了 operationId/版本/哈希，未按文档 4.3 字面要求比对 `manifest.sources`。包路径由 operationId(UUID) 派生，实际风险可忽略（2/2 确认） | 一行比对即可闭合纵深防御缺口 | `SessionMergeService.java` L184-186 | low |
````

触发：
预期：
实际：

判定理由：validateSnapshot检查operation/version/hash未比对ledger请求sourceIds；现有路径由同操作生成，无支持内错配触发证据，属规范纵深防御缺口。

代码：[SessionMergeService.java:184](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L184)；[MergePackageService.java:185](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L185)；[session-merge-architecture-v2.md:173](../../../../session-merge-architecture-v2.md#L173)

核验：代码静态核验及原文：见code_refs/lines

### R10-C06

**history_storage_version无写入不构成现有故障** — 成立 / retracted / scope=new / 不入分；统一问题：history-storage-version-dead-condition；credit=None。

[原文 L85–L85](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L85)

````text
- `history_storage_version` 无写入方（0/2 确认误报）：无害保守分支，且文档明确禁止新增写入。
````

触发：
预期：
实际：

判定理由：默认0使checkpoint仍被保守导出；无写入方是死条件/维护性问题，不是数据遗漏。

代码：[MergePackageService.java:440](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L440)

核验：代码静态核验及原文：见code_refs/lines

### R10-C07

**attempt校验失败改outcome不会丢usage** — 成立 / retracted / scope=new / 不入分；统一问题：attempt-outcome-transport-vs-validation；credit=None。

[原文 L86–L86](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L86)

````text
- attempt 两段式 outcome 记账（0/2 确认误报）：崩溃窗口期 usage 汇总一致（聚合 SQL 不按 outcome 过滤），且文档已接受末次调用重复计费。
````

触发：
预期：
实际：

判定理由：finishAttempt按running/unknown幂等，failAttempt只改completed的outcome；聚合SQL不按outcome过滤，不能推出费用丢失。

代码：[MergeProgressRepository.java:184](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L184)；[MergeProgressRepository.java:190](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L190)；[MergeProgressRepository.java:199](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L199)

核验：代码静态核验及原文：见code_refs/lines

### R10-C08

**普通同名工具不能继承交接权限** — 成立 / global / scope=new / 不入分；统一问题：builtin-tool-identity-isolation；credit=None。

[原文 L58–L59](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L58)

````text
| `AuthorizationService` | 仅新增 `handoff-read-v1` 分支；`instanceof` 分派使同名 MCP/动态工具无法继承权限 | ✅ 专项测试覆盖冒充者 |
| `HandoffReadTool` | 未注册为 Spring Bean，不进全局工具池 | ✅ 测试断言无 `@Component` |
````

触发：
预期：
实际：

判定理由：真实类分派及不全局注册在代码和对应测试中成立。

代码：[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)；[HandoffReadTool.java:10](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L10)

核验：代码静态核验及原文：见code_refs/lines

### R10-C09

**普通子代理不自动注入交接标记** — 成立 / global / scope=new / 不入分；统一问题：ordinary-agent-no-handoff-propagation；credit=None。

[原文 L63–L63](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L63)

````text
另核实：`SubAgentExecutor`、`AgentResumeService`、`SwarmWorkerRunner` 的 `queryEngine.execute` 调用点均使用独立 `QueryLoopState`，不携带交接标记，符合文档"普通/fork/Swarm 不自动注入交接入口"的要求。
````

触发：
预期：
实际：

判定理由：原执行者独立构造state，本次没有增加传播，符合375既定边界；不等于E子任务天然拥有全部资料。

代码：[HandoffContextService.java:43](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L43)；[session-merge-architecture-v2.md:375](../../../../session-merge-architecture-v2.md#L375)

核验：代码静态核验及原文：见code_refs/lines

### R10-C10

**列出的结构性回归断言存在** — 成立 / engineering / scope=new / 不入分；统一问题：existing-structural-regression-coverage；credit=None。

[原文 L70–L70](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L70)

````text
- 覆盖与文档第 9 节验收表逐行对应：封存即释放、中断恢复、取消围栏、不确定提交、损坏资料暂停、>16 单元、413 拆分、游标/穿越/symlink 拒绝、Unicode 搜索进位等均有断言。
````

触发：
预期：
实际：

判定理由：大部分列举的结构用例存在；不把这句话扩大成全部第9节已验收，后文已明确语义未做。

代码：[SessionMergeServiceTest.java:100](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L100)；[MergeSummaryServiceTest.java:65](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L65)；[HandoffReadServiceTest.java:129](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/HandoffReadServiceTest.java#L129)

核验：代码静态核验及原文：见code_refs/lines

### R10-C11

**交接预算不足返回裸内部错误码而非正常容量分类** — 成立 / functional / scope=new / 入分；统一问题：D09；credit=1。

[原文 L79–L79](../TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L79)

````text
| 2 | **交接异常的用户可见形态不统一**：`HANDOFF_BINDING_MISMATCH`/`HANDOFF_NOT_BOUND` 在 controller 层抛出后落入泛型 500 且消息被掩盖；`HANDOFF_CONTEXT_BUDGET_TOO_SMALL` 在引擎内转为裸错误码文本，非文档 7.1 要求的"正常容量错误"。不会污染合并状态（2/2 确认） | 为绑定类异常注册明确的 4xx 响应；预算不足映射为现有容量错误码 | `HandoffContextService.java` L42/L74；`QueryController.java` L178 | minor |
````

触发：E可用入口额度小于最小交接入口且工具可用
预期：按既有容量错误机制说明窗口不足
实际：QueryResult.stopReason=error，error为裸HANDOFF_CONTEXT_BUDGET_TOO_SMALL；不是HTTP500

判定理由：project抛IllegalStateException，execute364捕获且407通知handler；REST仍ResponseEntity.ok但结果error是裸HANDOFF_CONTEXT_BUDGET_TOO_SMALL，未走正常容量LlmApiException路径。报告对预算错误区别于controller500的描述准确。

核实范围：E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)；[QueryController.java:185](../../../../../backend/src/main/java/com/aicodeassistant/controller/QueryController.java#L185)

核验：[reproductions/RankingQueryReproTest.java](reproductions/RankingQueryReproTest.java)

## R11

[完整原报告](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)

### R11-normal_function

**判断分：5/5**。[原文 L73–L83](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L73)。普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。

````text
普通请求的隔离是成立的。

- 三个 REST 入口和 WebSocket 都先看已加载的会话元数据。只有合并目标才会调用 `HandoffContextService.configure`。没有 `sessionMergeOperationId` 时，不查合并表、不读资料包、不携带 `HandoffRead`。
- `HandoffReadTool` 不是 Spring Bean，不会进入全局工具表。fork 和 Swarm 仍从 `ToolRegistry` 取工具，并新建自己的 `QueryLoopState`，不会带上交接标记。
- `SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner`、`SessionExecutionGate`、`ToolRegistry` 本次都没有改动。普通执行、保存、子任务和 Swarm 的失败条件保持原样。
- 文件读取的放行条件只多了分析器 `handoff-read-v1`。同名的动态工具或 MCP 工具不会走这条只读策略；PLAN 模式下也只有真正的内置 `HandoffRead` 能读交接包。`file-v1` 的原有判断没有放宽。
- 图片注入对普通路径仍使用“不允许包外文件”的判断。只有合并会话的投影非空时，才会多放行本包已登记且哈希匹配的图片。路径安全检查先执行，交接包判断只在工程目录拒绝之后作为补充。
- 来源锁只来自执行 gate 里仍持有的 token。快照封存后锁集合为空，来源可以继续聊天和删除。无关会话不会被写进这个集合。
- `QueryEngine` 在没有运行标记时直接返回空投影，历史预算不扣减。`QueryEngineUnitTest` 断言无标记时 phase2 与 phase1 的预算差为 0，且不调用交接服务。`HandoffContextServiceTest` 断言普通配置与原配置是同一对象，且不触达仓库和读取服务。

因此，普通开发、权限、压缩和工具调用不会因为这次改动改变行为。风险集中在**已经合并出来的新会话**，以及**正在进行的合并任务**。
````

### R11-boundary

**判断分：5/5**。[原文 L85–L89](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L85)。全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。

````text
补充边界：

- WebSocket 普通对话沿用全量工具池，`allowedToolNames` 为 `null` 时会给合并目标加上 `HandoffRead`。PROMPT 命令的允许列表和 REST 的 `disallowedTools` 会阻止该工具。这与“全量工具默认可用、显式限制必须遵守”一致。
- 子代理和 Swarm worker 的工具来自 `ToolRegistry`，不复制父会话的 `QueryConfig.tools()`，因此不会自动获得 `HandoffRead`。
- 发布事务把目标会话权限写成 `AUTO_APPROVE`，与现有 Web 新建会话的默认权限相同，权限存在独立列里，不会被只含 `sessionMergeOperationId` 的 `metadata_json` 覆盖掉。
````

### R11-release

**判断分：5/5**。[原文 L184–L188](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L184)。明确区分阶段提交与功能完成版合入发布。

````text
1. 先不要把当前工作区当作 v2 完成版推上去。若要保存进度，只提交合并相关文件，并在说明里写明语义验收未做、上述恢复缺陷仍在。被排除的前端文件应单独提交，不要混进这次合并变更。
2. 换模型时按新预算重建未完成聚合单元，已完成单元的结果文件继续沿用。
3. 阻断缺口一律带上 blocked 记录号。非关键附件解析失败记为非阻断缺口，保留原件。
4. 交接入口超出预算时走现有上下文容量错误，让合并会话的失败形态和普通超窗一致。
5. 发布前增加“零条目不得发布”的断言，并单独做第 9 节的真实模型样本。样本结果不要和 stub 测试混写成已经验收。
````

### R11-semantic

**判断分：5/5**。[原文 L106–L106](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L106)。明确真实模型语义验收未完成，stub 测试不能替代。

````text
第 7 节的预算接法与实现一致。尚未满足的是第 9 节后半：A/B 改字段后合并为 E、封存后代码再变化、E 更新待办不回写 A/B 的真实模型样本都还没跑。文档写明 stub 的数据、隔离和接线断言不能描述成真实模型接续开发验收。
````

### R11-C01

**普通请求/工具/来源隔离与默认权限** — 成立 / global / scope=new / 不入分；统一问题：ordinary-path-isolation；credit=None。

[原文 L75–L89](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L75)

````text
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
````

触发：普通请求/工具/来源隔离与默认权限
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：所列空投影、局部工具池、来源 gate、目标独立默认权限均可核对；未扩张成绝对零风险。

判定理由：所列空投影、局部工具池、来源 gate、目标独立默认权限均可核对；未扩张成绝对零风险。

代码：[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)；[SessionMergeService.java:209](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L209)

核验：代码静态核验及原文：见code_refs/lines

### R11-C02

**聚合换小模型引起 input_hash 冲突** — 不成立 / functional / scope=new / 入分；统一问题：model-switch-aggregate-resume；credit=0。

[原文 L112–L118](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L112)

````text
### [P2] 聚合开始后换模型会把未完成任务判成输入冲突

位置：`backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java` 的 `plan`；`MergeSummaryService.prepare` 的聚合分片。

聚合单元的编号和 `input_hash` 按当前模型的分片宽度生成。`plan` 在主键冲突时保留旧行，再要求新哈希必须相同，否则抛出 `MERGE_UNIT_INPUT_CHANGED`。提取阶段的哈希不依赖模型，所以提取中途换模型还可以。一旦已经写出 `aggregate-*` 单元，即使用户只是为了完成剩余单元而换一个更小的模型，恢复也会再次暂停。

文档第 5.3 节要求：换模型只重新规划未完成单元，保留已合格结果及实际模型记录。
````

触发：聚合换小模型引起 input_hash 冲突
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：聚合 pieces 固定4096 UTF-16、batch固定8192，input_hash不含模型。需区分另一条件：换模型改变单结果1536 token阈值而提前结束已规划层。报告的“当前模型分片宽度”根因错误，常规换模型不必失败，不能仅凭换模型关键词给半分。

判定理由：聚合 pieces 固定4096 UTF-16、batch固定8192，input_hash不含模型。需区分另一条件：换模型改变单结果1536 token阈值而提前结束已规划层。报告的“当前模型分片宽度”根因错误，常规换模型不必失败，不能仅凭换模型关键词给半分。；统一复核：固定4096字符pieces/8192 batch及hash不依赖模型；与真实1536阈值问题触发不同，明确机制已证伪。

代码：[MergeSummaryService.java:89](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L89)；[MergeSummaryService.java:97](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L97)；[MergeSummaryService.java:164](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L164)；[MergeProgressRepository.java:144](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L144)

核验：代码静态核验及原文：见code_refs/lines

### R11-C03

**缺口无法关联 blocked 记录导致恢复不尝试** — 成立 / functional / scope=new / 入分；统一问题：D04；credit=1。

[原文 L120–L126](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L120)

````text
### [P2] 一部分阻断记录被写成永远无法恢复的缺口

位置：`backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java` 导出消息与内嵌图片处；`recoverBlockedProjections`。

消息形状不合法时，原文会记成 `blocked`，但缺口原因是不带记录号的 `RECORD_REQUIRES_HANDLING`。非法 base64 图片则记成 `RECORD_REQUIRES_HANDLING:invalid_image`。恢复逻辑只接受“原因后缀正好是某条 blocked 记录号”的缺口，这两种都会在重读原文之前直接失败。于是界面提示可以排查后恢复，实际每次恢复都回到暂停。一条非关键的坏图片也会挡住整次合并。

文档第 4.4 节要求：非关键附件未解析时明确标记并保留原件；可恢复的阻断记录应能从本包 raw 重新生成投影。已经损坏、JSON 都读不出来的 checkpoint 会暂停且不发布，这条与文档和现有测试一致，不记为缺陷。
````

触发：消息解析产生未关联 gap；恢复时解析能力/配置已改善
预期：按已保留 raw 有条件重新投影
实际：gap关联校验先拒绝，无法尝试

判定理由：消息解析异常写无ref gap；invalid_image写固定后缀；recover在读取raw前因不属于blockedRecords拒绝。只确认该恢复协议缺口，不把所有损坏材料应暂停称缺陷。；统一复核：合成原件不变，启用Jackson现有ALLOW_SINGLE_QUOTES后已可解析，恢复仍在读raw前被缺ref挡住；提高物化上限的正控制能恢复。仅确认这类关联协议缺口，损坏资料允许暂停，不采用永久/所有恢复失效的泛化。

核实范围：已修正解析配置仍被gap/ref关联校验阻止；不等于所有坏原件应可恢复。

代码：[MergePackageService.java:232](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L232)；[MergePackageService.java:435](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L435)；[MergePackageService.java:531](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L531)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R11-C04

**E 最小入口超预算走内部执行失败** — 成立 / functional / scope=new / 入分；统一问题：D09；credit=1。

[原文 L128–L134](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L128)

````text
### [P2] 交接入口放不下时，合并会话的普通提问会变成内部错误

位置：`backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java` 的 `project`；`QueryEngine.execute` 的外层异常处理。

入口上限是 `min(2048, 历史预算的 10%)`。最小说明仍然放不下时，`project` 抛出 `HANDOFF_CONTEXT_BUDGET_TOO_SMALL`。这个异常发生在查询循环的模型调用之外，最终被记成一次失败的 run，而不是现有的上下文容量错误。

文档第 7.1 节要求：连最小入口和当前请求都放不下时返回正常容量错误，不把已完成合并改为失败。普通无标记会话不会走到这里。合并操作本身的状态不会被改回失败，但用户在该合并会话里的本轮请求会以内部错误结束。
````

触发：minimal入口超出总历史预算10%
预期：明确容量错误
实际：IllegalStateException

判定理由：准确区分合并操作 completed 与本轮 run 失败；project 异常位于模型错误恢复块外。

核实范围：E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)

核验：[reproductions/RankingQueryReproTest.java](reproductions/RankingQueryReproTest.java)

### R11-C05

**全部提取返回空items仍发布** — 规范歧义 / conditional / scope=new / 不入分；统一问题：empty-items-publication；credit=None。

[原文 L136–L142](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L136)

````text
### [P2] 所有分片都返回空条目时仍会创建目标会话

位置：`backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java` 的 `validate` 与 `prepare`。

校验允许 `items` 为空数组，空数组也算单元成功。若每个分片都这样返回，详细交接条数为 0，启动摘要可以退成只有目录的 `handoff.md`，发布仍会继续。

文档第 6.2 节要求：没有合格结构化提取就不能发布；确定性导航兜底只用于已有详细交接的启动入口降容，不能用原文目录冒充整理完成。提示词允许“没有信息可返回空 items”，这和发布门槛需要分开：单个无关分片可以没有条目，全部详细条目为空时不应发布。
````

触发：全部提取返回空items仍发布
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：确实允许空items且无总非空门槛；但PROMPT明确允许无信息空数组，规范未明定至少一条。非空输入也未自动证明应产生某条语义，属发布门槛规范歧义。

判定理由：确实允许空items且无总非空门槛；但PROMPT明确允许无信息空数组，规范未明定至少一条。非空输入也未自动证明应产生某条语义，属发布门槛规范歧义。

代码：[MergeSummaryService.java:41](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L41)；[MergeSummaryService.java:278](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L278)；[session-merge-architecture-v2.md:402](../../../../session-merge-architecture-v2.md#L402)

核验：代码静态核验及原文：见code_refs/lines

### R11-C06

**短暂 paused 仍有锁却前端忽略** — 成立 / functional / scope=new / 入分；统一问题：D15；credit=1。

[原文 L144–L150](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L144)

````text
### [P3] 来源是否锁定仍先看 preparing

位置：`frontend/src/store/sessionMergeStore.ts` 的 `selectMergeSourceIds`。

封存后的空锁集合行为是对的，暂停后服务端也会释放 token。判断式仍是“必须处于 preparing 才采用 `lockedSourceSessionIds`”。文档第 8 节要求锁集合本身就是唯一依据，前端不能从 preparing 推断来源锁定。

暂停落库和 `finally` 释放 token 之间若被轮询到，界面会认为来源已空闲，而 gate 仍可能拒绝操作。窗口很短，所以定为 P3。
````

触发：短暂 paused 仍有锁却前端忽略
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：pause更新发生在finally关闭leases之前，get可见paused+非空真实锁，selector因status丢弃。后端仍拒绝，无数据损失；仅提示短窗口。

判定理由：pause更新发生在finally关闭leases之前，get可见paused+非空真实锁，selector因status丢弃。后端仍拒绝，无数据损失；仅提示短窗口。

核实范围：cancelled/paused短窗口真实锁仍在但selector隐藏；后端gate保护仍在。

代码：[SessionMergeService.java:226](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L226)；[SessionMergeService.java:230](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L230)；[sessionMergeStore.ts:28](../../../../../frontend/src/store/sessionMergeStore.ts#L28)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R11-C07

**同轮重复投影校验** — 成立 / engineering / scope=new / 不入分；统一问题：duplicate-handoff-projection；credit=None。

[原文 L152–L156](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L152)

````text
### [P3] 合并会话每一轮都会把交接清单哈希算两遍

位置：`backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java` 查询循环中的 `handoffProjection`，以及 `prepareCompactionContext`。

同一轮里，装填历史和准备压缩各调用一次 `project`。每次都会重新校验 `ready.json` 和清单哈希。两次使用同一预算，phase1 与压缩预算一致，没有把同一笔预留扣两次。普通会话在看到空标记后会立刻返回，不受影响。合并会话的每轮提问都会重复做这些磁盘校验。
````

触发：同轮重复投影校验
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：同轮主路径与压缩准备重复project成立，但读取的是清单/brief链，不是所有asset全文；无量化产品失败，工程优化不入缺陷分。

判定理由：同轮主路径与压缩准备重复project成立，但读取的是清单/brief链，不是所有asset全文；无量化产品失败，工程优化不入缺陷分。

代码：[QueryEngine.java:841](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L841)；[QueryEngine.java:1835](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1835)；[HandoffReadService.java:69](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L69)

核验：代码静态核验及原文：见code_refs/lines

### R11-C08

**暂停折叠按钮称合并结果** — 成立 / engineering / scope=new / 不入分；统一问题：paused-floating-label；credit=None。

[原文 L160–L160](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L160)

````text
- 折叠后的进度按钮在暂停时仍写“合并结果”。打开面板后的文案、恢复和取消是对的。
````

触发：暂停折叠按钮称合并结果
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：文案确为合并结果，按钮仍可打开并恢复；结果可泛指操作状态，保留 UX 建议不当确定产品缺陷。

判定理由：文案确为合并结果，按钮仍可打开并恢复；结果可泛指操作状态，保留 UX 建议不当确定产品缺陷。

代码：[SessionMergePanel.tsx:143](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L143)

核验：代码静态核验及原文：见code_refs/lines

### R11-C09

**暂停通知使用error级别** — 成立 / engineering / scope=new / 不入分；统一问题：merge-toast-severity；credit=None。

[原文 L161–L161](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L161)

````text
- 暂停通知和失败共用错误级别，文案是“合并暂停或失败”。没有本地合并任务时，后台发现错误不会弹“合并失败”，这一点已按复审要求处理。
````

触发：暂停通知使用error级别
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：代码事实成立，级别选择无明确规格硬约束，保留体验建议。

判定理由：代码事实成立，级别选择无明确规格硬约束，保留体验建议。

代码：[sessionMergeStore.ts:200](../../../../../frontend/src/store/sessionMergeStore.ts#L200)

核验：代码静态核验及原文：见code_refs/lines

### R11-C10

**预算512与拆分下限256冲突** — 规范歧义 / conditional / scope=new / 不入分；统一问题：minimum-model-budget-512；credit=None。

[原文 L162–L162](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L162)

````text
- 单请求预算低于 512 估算 token 就直接拒绝模型，而分片缩小下限是 256。小模型可能在仍能装下 256 token 资料时被整次拒绝。
````

触发：预算512与拆分下限256冲突
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：两者分别是模型可用资料容量下限与失败二分停止点，资料还需别名/来源/包装；未证明能有效运行而被拒的受支持模型，非必然矛盾。

判定理由：两者分别是模型可用资料容量下限与失败二分停止点，资料还需别名/来源/包装；未证明能有效运行而被拒的受支持模型，非必然矛盾。

代码：[MergeSummaryService.java:57](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L57)；[MergeSummaryService.java:269](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L269)

核验：代码静态核验及原文：见code_refs/lines

### R11-C11

**history_storage_version死分支** — 成立 / engineering / scope=new / 不入分；统一问题：dead-history-storage-version；credit=None。

[原文 L163–L163](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L163)

````text
- `history_storage_version == 2` 会跳过 checkpoint。当前仓库里已经没有写入这个字段的生产代码，现有会话不会走到这条分支。
````

触发：history_storage_version死分支
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：无现有生产写方，属于遗留维护问题。

判定理由：无现有生产写方，属于遗留维护问题。

代码：[MergePackageService.java:440](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L440)

核验：代码静态核验及原文：见code_refs/lines

### R11-C12

**恢复与真实模型验收缺测** — 成立 / engineering / scope=new / 不入分；统一问题：merge-test-gaps；credit=None。

[原文 L171–L176](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L171)

````text
还缺的是：

- 聚合开始之后换模型，已完成单元保留、未完成单元按新预算重规划。
- 非法图片，以及不带记录号的阻断缺口，在原件重新可解析后能否恢复。
- 极小上下文窗口下，交接入口不足时是否走正常容量错误。
- 第 9 节的真实模型样本：A 把响应字段从 `name` 改为 `displayName` 并记录验证；B 的前端仍读取 `name`，且有未完成联调待办。二者使用同一临时工程，合并为 E 后完成联调。再验证封存后共享代码发生变化时，E 不凭历史通过记录宣称当前成功。并分别验证 E 更新待办不改变 A/B、A 追加消息不改变 E 资料、删除来源后 E 仍能读取已归档依据。
````

触发：恢复与真实模型验收缺测
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：测试缺口与规格人工样本尚未执行属实，未执行不冒充产品已坏。

判定理由：测试缺口与规格人工样本尚未执行属实，未执行不冒充产品已坏。

代码：[session-merge-architecture-v2.md:440](../../../../session-merge-architecture-v2.md#L440)

核验：代码静态核验及原文：见code_refs/lines

### R11-C13

**压缩单行风格影响维护** — 成立 / engineering / scope=new / 不入分；统一问题：compressed-code-style；credit=None。

[原文 L180–L180](../cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L180)

````text
代码职责划分和文档第 10 节一致：14 个既有落点加 6 个新生产文件，测试落在规定的迁移、Repository、ReadService、Context 四个新测试文件，以及原有 merge 测试。实现写成了很长的单行方法，后续改恢复和预算时不容易看清事务边界。这不影响当前行为判断。
````

触发：压缩单行风格影响维护
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：不影响当前功能判定。

判定理由：不影响当前功能判定。

代码：[MergeSummaryService.java:1](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L1)

核验：代码静态核验及原文：见code_refs/lines

## R12

[完整原报告](../zhikuncode/GLM5.3-session-merge-v2-code-review.md)

### R12-normal_function

**判断分：5/5**。[原文 L39–L44](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L39)。普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。

````text
| **非合并链路影响** | ✅ **无负面影响**。隔离设计严格，共享链路定向回归 160/160 通过，「保持不改」清单文件零改动 |
| **测试与构建** | ✅ 独立完整复现文档声称结果：后端 2,975 项（受控环境 0 失败）、前端 103 文件全过、生产构建通过 |
| **架构符合度** | ✅ 五项核心承诺（封存即释放 / 原子发布 / 单一名额 / epoch 防重放 / 预算分离）全部成立；V026 与附录 A SQL 逐行一致 |
| **发布标准** | ⚠️ **工程层面达标，无严重缺陷；但有 6 个中等问题建议发布前处理**，且文档第 9 节自认的「真实模型语义验收」尚未执行 |

一句话总结：**本次改动没有对非合并分支（普通聊天 / fork / Swarm 子代理）造成任何可证实的影响，达到可提交 GitHub 的工程标准；建议先修复 2 个改动量小但用户可见的中等问题，并在发布说明中如实标注语义验收未执行。**
````

### R12-boundary

**判断分：5/5**。[原文 L54–L91](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L54)。全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。

````text
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
````

### R12-release

**判断分：0/5**。[原文 L218–L234](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L218)。原文234将语义验收是否属于发布标准条件化，未明确作出当前完成版暂不可发布结论。

````text
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
````

### R12-semantic

**判断分：5/5**。[原文 L234–L234](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L234)。明确真实模型语义验收未完成，stub 测试不能替代。

````text
文档自身声明「**真实模型接续开发语义验收尚未执行**」——第 9 节要求的 A/B 合并为 E 后完成联调的语义样本（含「封存后共享代码又有变化」变体）均未跑过。当前所有验证均为 stub provider 层面。如果发布标准包含该语义验收，这一项尚未满足；这是文档自认的边界而非代码缺陷，但不应在发布说明中省略。
````

### R12-C01

**共享链路隔离和回归覆盖** — 成立 / global / scope=new / 不入分；统一问题：ordinary-path-isolation；credit=None。

[原文 L54–L91](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L54)

````text
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
````

触发：共享链路隔离和回归覆盖
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：静态门控/工具身份/预算等价支持普通路径无新增功能回归；声明小对象、instanceof开销与缺测边界。

判定理由：静态门控/工具身份/预算等价支持普通路径无新增功能回归；声明小对象、instanceof开销与缺测边界。

代码：[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)；[HandoffContextService.java:33](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L33)

核验：代码静态核验及原文：见code_refs/lines

### R12-C02

**绑定缺失应降级普通聊天** — 规范歧义 / conditional / scope=new / 不入分；统一问题：handoff-binding-fail-closed；credit=None。

[原文 L150–L154](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L150)

````text
### 中 1 ｜ HandoffContextService.java:50-52 — E 的绑定异常导致每条消息 500

绑定缺失抛 AuthorizationException、绑定不匹配抛 IllegalStateException，均未捕获——合并会话 E 的**每条后续消息都会 500**。
违反意图：文档 §7.1「连最小入口和当前请求都放不下时返回正常容量错误，不把已完成合并改为失败」。
建议：降级为普通请求 + 一次性警告事件。
````

触发：绑定缺失应降级普通聊天
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：绑定缺失失败行为存在，但规范要求可信绑定和资料可靠，不要求故障时绕过；引用最小容量条款不能证明绑定校验应降级。500诊断可改善不等于必须无资料继续。

判定理由：绑定缺失失败行为存在，但规范要求可信绑定和资料可靠，不要求故障时绕过；引用最小容量条款不能证明绑定校验应降级。500诊断可改善不等于必须无资料继续。

代码：[HandoffContextService.java:41](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L41)；[HandoffReadService.java:46](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L46)

核验：代码静态核验及原文：见code_refs/lines

### R12-C03

**预算过小使用通用硬失败** — 成立 / functional / scope=new / 入分；统一问题：D09；credit=1。

[原文 L156–L159](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L156)

````text
### 中 2 ｜ HandoffContextService.java:92-95 — brief 不可读/预算过小硬失败

`reads.brief` 抛 IOException → IllegalStateException("HANDOFF_UNAVAILABLE")；预算过小抛 HANDOFF_CONTEXT_BUDGET_TOO_SMALL——两者使 E 的整次请求硬失败而非降级。包损坏/小窗口时 E 完全无法聊天。
建议：降级为 unavailable/minimal 文本或空投影并告警，让 phase2 走正常容量错误。
````

触发：E最小入口不能装下
预期：正常容量错误
实际：通用执行失败

判定理由：tiny预算明确要求容量错误，project却抛IllegalStateException；同段包损坏不得自动退空投影为另一个安全取舍，不因本命中认可降级建议。

核实范围：E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。

代码：[HandoffContextService.java:72](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L72)；[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)

核验：[reproductions/RankingQueryReproTest.java](reproductions/RankingQueryReproTest.java)

### R12-C04

**封存窗口新增子会话无token复制** — 证据不足 / conditional / scope=new / 不入分；统一问题：source-tree-after-lock-race；credit=None。

[原文 L161–L165](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L161)

````text
### 中 3 ｜ 快照来源树集合未按文档复核

`lockSources`（SessionMergeService.java:111-118）取 token 后只核对一次集合，未实现文档 §4.3 步骤 1 的「集合变动最多重新取得两次，仍不稳定则暂停」；worker 异步执行 `seal()`（MergePackageService:150-157）时重新枚举 descendants 但**不与已锁定集合比对**——`start→seal` 窗口内新增的子会话会被无 token 复制。
缓解：gate 使父来源上新 run 难以出现、消息计数复核兜底部分场景。
建议：seal 前比对两集合，不一致转 SOURCE_BUSY 暂停；补该边界测试。
````

触发：封存窗口新增子会话无token复制
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：seal重新descendants且未与token集合比较属实；但父来源gate和activeAgent检查阻止正常新任务启动，报告未提供受支持新增子会话路径。不能仅静态两次枚举差异证明数据竞态。

判定理由：seal重新descendants且未与token集合比较属实；但父来源gate和activeAgent检查阻止正常新任务启动，报告未提供受支持新增子会话路径。不能仅静态两次枚举差异证明数据竞态。

代码：[SessionMergeService.java:114](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L114)；[SessionMergeService.java:123](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L123)；[MergePackageService.java:158](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L158)

核验：代码静态核验及原文：见code_refs/lines

### R12-C05

**跨实例resume条件失败返回500** — 范围外 / conditional / scope=out / 不入分；统一问题：multi-instance-resume-conflict；credit=None。

[原文 L167–L170](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L167)

````text
### 中 4 ｜ MERGE_STALE_EXECUTION 未映射为 409

Repository `changed()`（MergeProgressRepository.java:60）抛 IllegalStateException，Controller 仅处理 Conflict；文档 §5.3 要求并发 resume 失败方「返回当前状态」。本机 synchronized 覆盖主路径，**跨实例条件更新失败会 500**。
建议：服务层捕获该异常转 Conflict 并附最新操作。
````

触发：跨实例resume条件失败返回500
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：只对跨实例共享库情形，单进程synchronized和writers挡住；本轮单实例外。

判定理由：只对跨实例共享库情形，单进程synchronized和writers挡住；本轮单实例外。

代码：[SessionMergeService.java:136](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L136)；[MergeProgressRepository.java:108](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L108)

核验：代码静态核验及原文：见code_refs/lines

### R12-C06

**paused时仍持锁却被selector隐藏** — 成立 / functional / scope=new / 入分；统一问题：D15；credit=1。

[原文 L172–L175](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L172)

````text
### 中 5 ｜ sessionMergeStore.ts:28-31 — 来源锁仍含状态推断

`status==='preparing'` 才返回锁集合。后端在**封存前暂停**（磁盘/配额不足、SERVICE_SHUTDOWN）时 `lockedSourceSessionIds` 非空且 gate 仍持锁，前端却显示来源可用：Sidebar 不禁用、App 不拦截发消息，用户操作将收到服务端 409。与文档「只看 lockedSourceSessionIds、不得从状态推断」不符（风险方向与旧代码相反）。
建议：去掉状态门控，直接返回 `lockedSourceSessionIds`。**注意**：修复时需连带验证反向依赖——被排除文件 `Sidebar.tsx:2,542,862`（selectMergeSourceIds/useSessionMergeStore/openDialog 的「合并中」禁用徽标）与 `App.tsx:86,269,346`（isMergeSource 拦截发消息）均消费该选择器。
````

触发：paused时仍持锁却被selector隐藏
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：报告对服务关闭/暂停过程中短暂gate未释放定位成立；不是长期锁泄漏，finally会释放。

判定理由：报告对服务关闭/暂停过程中短暂gate未释放定位成立；不是长期锁泄漏，finally会释放。

核实范围：cancelled/paused短窗口真实锁仍在但selector隐藏；后端gate保护仍在。

代码：[sessionMergeStore.ts:28](../../../../../frontend/src/store/sessionMergeStore.ts#L28)；[SessionMergeService.java:226](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L226)；[SessionMergeService.java:230](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L230)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R12-C07

**resume409与active错误采纳缺测** — 成立 / engineering / scope=new / 不入分；统一问题：store-conflict-test-gap；credit=None。

[原文 L177–L179](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L177)

````text
### 中 6 ｜ 前端关键分支零测试

resume 的 epoch 拒绝（409 MERGE_STALE_OPERATION 携带 operation → 采纳并刷新）与 `MERGE_ACTIVE_EXISTS` 的 `body.operation` 采纳分支（store:171-179）均无测试——前者是文档 §8 明确要求的语义。
````

触发：resume409与active错误采纳缺测
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：对应分支存在而现有测试主要快乐路径；属于测试缺口。

判定理由：对应分支存在而现有测试主要快乐路径；属于测试缺口。

代码：[sessionMergeStore.ts:171](../../../../../frontend/src/store/sessionMergeStore.ts#L171)；[sessionMergeStore.ts:243](../../../../../frontend/src/store/sessionMergeStore.ts#L243)

核验：代码静态核验及原文：见code_refs/lines

### R12-C08

**source线性扫catalog形成二次工作量** — 成立 / engineering / scope=new / 不入分；统一问题：summary-catalog-scan；credit=None。

[原文 L183–L183](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L183)

````text
1. `MergeSummaryService.source()`（:176-188）每个 InputRef 线性扫全清单，O(N²)；万级分片时明显变慢，建议一次构建 ref→entry 映射
````

触发：source线性扫catalog形成二次工作量
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：每个input扫描目录成立，无测量证实万级必不可用，工程优化。

判定理由：每个input扫描目录成立，无测量证实万级必不可用，工程优化。

代码：[MergeSummaryService.java:183](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L183)

核验：代码静态核验及原文：见code_refs/lines

### R12-C09

**强制崩溃残留tmp文件** — 成立 / engineering / scope=new / 不入分；统一问题：atomic-write-crash-orphans；credit=None。

[原文 L184–L184](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L184)

````text
2. `MergeTextBudget.atomicWrite` 崩溃残留 `.tmp-UUID` 文件（仅本次调用清理）
````

触发：强制崩溃残留tmp文件
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：finally不能处理进程强杀，旧临时文件会残留；不被采纳为完成结果，取消/包清理回收，未证明正确性失败。

判定理由：finally不能处理进程强杀，旧临时文件会残留；不被采纳为完成结果，取消/包清理回收，未证明正确性失败。

代码：[MergeTextBudget.java:34](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeTextBudget.java#L34)

核验：代码静态核验及原文：见code_refs/lines

### R12-C10

**控制请求HTML错误页显示JSON解析异常** — 成立 / functional / scope=new / 入分；统一问题：D20；credit=1。

[原文 L185–L185](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L185)

````text
3. 前端 `control` 路径 `response.json()` 无 catch（代理返回 HTML 错误页时暴露解析错误；refresh 路径有 `.catch(()=>({}))`，不一致）
````

触发：代理给resume/cancel返回HTML错误页
预期：有界明确控制请求失败提示
实际：展示Unexpected token等技术异常

判定理由：control response.json未catch，catch直接把SyntaxError.message放UI；refresh有兜底，真实用户看到技术解析错误。

核实范围：控制接口非JSON错误响应显示JSON解析异常，掩盖可操作错误信息。

代码：[sessionMergeStore.ts:241](../../../../../frontend/src/store/sessionMergeStore.ts#L241)；[sessionMergeStore.ts:252](../../../../../frontend/src/store/sessionMergeStore.ts#L252)

核验：代码静态核验：见code_refs

### R12-C11

**retryAt/errorCode展示可读性** — 成立 / engineering / scope=new / 不入分；统一问题：merge-diagnostic-display；credit=None。

[原文 L186–L186](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L186)

````text
4. panel:206-207 retryAt 直接渲染 ISO 字符串、errorCode 从不展示
````

触发：retryAt/errorCode展示可读性
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：ISO原样及不单列errorCode属实，但error已有解释，纯呈现偏好。

判定理由：ISO原样及不单列errorCode属实，但error已有解释，纯呈现偏好。

代码：[SessionMergePanel.tsx:206](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L206)

核验：代码静态核验及原文：见code_refs/lines

### R12-C12

**相同操作重复暂停通知key叠加** — 成立 / functional / scope=new / 入分；统一问题：D22；credit=1。

[原文 L187–L187](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L187)

````text
5. 通知仅 push 不按 key 去重，paused→resume→再暂停会短暂叠两条同 key 通知
````

触发：5秒toast存活内paused→resume→再次paused并被poll观察
预期：同key替换或去重
实际：重复toast/重复React key

判定理由：addNotification仅push；paused通知尚未消失时恢复再暂停可再次push同key，违反保留通知去重。

核实范围：5秒通知仍在期间，新增paused→resume→paused可推送同key通知两份。

代码：[sessionMergeStore.ts:200](../../../../../frontend/src/store/sessionMergeStore.ts#L200)；[notificationStore.ts:32](../../../../../frontend/src/store/notificationStore.ts#L32)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R12-C13

**冷启动发现paused弹error** — 成立 / engineering / scope=new / 不入分；统一问题：merge-toast-severity；credit=None。

[原文 L188–L188](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L188)

````text
6. 冷启动采纳 active 的 paused 操作会立即弹 **error 级**通知（paused 可恢复，级别欠妥）
````

触发：冷启动发现paused弹error
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：暂停级别是UX偏好，恢复入口仍正常。

判定理由：暂停级别是UX偏好，恢复入口仍正常。

代码：[sessionMergeStore.ts:200](../../../../../frontend/src/store/sessionMergeStore.ts#L200)

核验：代码静态核验及原文：见code_refs/lines

### R12-C14

**未知stage显示提交中** — 证据不足 / conditional / scope=new / 不入分；统一问题：unknown-stage-fallback；credit=None。

[原文 L189–L189](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L189)

````text
7. 未知 stage 回退文案「提交中…」对运行中的新阶段有误导
````

触发：未知stage显示提交中
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：当前受支持stage均映射；未知未来值条件无当前可达证明。

判定理由：当前受支持stage均映射；未知未来值条件无当前可达证明。

代码：[SessionMergePanel.tsx:13](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L13)；[SessionMergePanel.tsx:198](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L198)

核验：代码静态核验及原文：见code_refs/lines

### R12-C15

**protocolVersion类型不收紧** — 成立 / engineering / scope=new / 不入分；统一问题：protocol-version-types；credit=None。

[原文 L190–L190](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L190)

````text
8. `protocolVersion?: number` 应为 `1|2`；v1 兼容靠可选字段成立但无显式守卫
````

触发：protocolVersion类型不收紧
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：服务端契约已限制，仅TS类型建议。

判定理由：服务端契约已限制，仅TS类型建议。

代码：[sessionMergeStore.ts:16](../../../../../frontend/src/store/sessionMergeStore.ts#L16)

核验：代码静态核验及原文：见code_refs/lines

### R12-C16

**SQL分号切分脆弱** — 成立 / engineering / scope=new / 不入分；统一问题：migration-sql-split；credit=None。

[原文 L191–L191](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L191)

````text
9. V026 `execute()` 用 `SQL.split(";")` 逐句执行——当前安全但脆弱，建议显式语句列表
````

触发：SQL分号切分脆弱
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：当前SQL无分号字面量，未来维护风险不算当前缺陷。

判定理由：当前SQL无分号字面量，未来维护风险不算当前缺陷。

代码：[V026_ExtendSessionMerges.java:124](../../../../../backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java#L124)

核验：代码静态核验及原文：见code_refs/lines

### R12-C17

**补记快照不核对来源集合** — 证据不足 / conditional / scope=new / 不入分；统一问题：snapshot-source-set-revalidation；credit=None。

[原文 L192–L192](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L192)

````text
10. 补记路径只核对 version，未校验文档 §4.3 要求的「来源集合」
````

触发：补记快照不核对来源集合
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：validateSnapshot核operation/version/hash不核sources，静态缺项成立；要触发异来源同operation须外部替换/重算封存，无正常代码路径证明。

判定理由：validateSnapshot核operation/version/hash不核sources，静态缺项成立；要触发异来源同operation须外部替换/重算封存，无正常代码路径证明。

代码：[SessionMergeService.java:184](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L184)；[MergePackageService.java:185](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L185)

核验：代码静态核验及原文：见code_refs/lines

### R12-C18

**messageVersion没有剔除usage** — 证据不足 / conditional / scope=new / 不入分；统一问题：message-version-usage；credit=None。

[原文 L193–L193](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L193)

````text
11. `messageVersion` 只剔除 handoffOrigin，未按文档 §4.2 剔除 metadata 中的 usage，跨 checkpoint 去重精确性略降
````

触发：messageVersion没有剔除usage
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：只remove handoffOrigin属实，usage通常为message顶层/DB独立列，需证明进入semantic metadata而同一事件仅usage变化的生产路径；未证明实际去重回归。

判定理由：只remove handoffOrigin属实，usage通常为message顶层/DB独立列，需证明进入semantic metadata而同一事件仅usage变化的生产路径；未证明实际去重回归。

代码：[MergePackageService.java:337](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L337)；[session-merge-architecture-v2.md:152](../../../../session-merge-architecture-v2.md#L152)

核验：代码静态核验及原文：见code_refs/lines

### R12-C19

**取消后attempt可显示completed** — 规范歧义 / engineering / scope=new / 不入分；统一问题：attempt-cancel-outcome；credit=None。

[原文 L194–L194](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L194)

````text
12. cancel 中断路径下 attempt 可能停留在 `outcome='completed'`（记账轻微失真）
````

触发：取消后attempt可显示completed
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：HTTP完成且usage已返回但unit被取消可合法completed，单元状态由epoch隔离；未证明记账错误。

判定理由：HTTP完成且usage已返回但unit被取消可合法completed，单元状态由epoch隔离；未证明记账错误。

代码：[MergeSummaryService.java:223](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L223)；[MergeProgressRepository.java:177](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L177)

核验：代码静态核验及原文：见code_refs/lines

### R12-C20

**legacy摘要派生参考标记弱** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-summary-label；credit=None。

[原文 L195–L195](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L195)

````text
13. v1 旧包 import 整目录 walk 复制，旧摘要未明确「标为派生参考」（已有 legacy gap 但提示弱）
````

触发：legacy摘要派生参考标记弱
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：legacy文件完整复制并带origin_inferred缺口；旧摘要来源说明强度为语义/呈现建议。

判定理由：legacy文件完整复制并带origin_inferred缺口；旧摘要来源说明强度为语义/呈现建议。

代码：[MergePackageService.java:606](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L606)

核验：代码静态核验及原文：见code_refs/lines

### R12-C21

**Repository双实例与字段注入** — 成立 / engineering / scope=new / 不入分；统一问题：injection-style；credit=None。

[原文 L196–L196](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L196)

````text
14. 风格：SessionMergeService 内联 new MergeProgressRepository 与 Spring bean 重复实例、字段注入
````

触发：Repository双实例与字段注入
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：无状态repository不是当前并发错误。

判定理由：无状态repository不是当前并发错误。

代码：[SessionMergeService.java:70](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L70)

核验：代码静态核验及原文：见code_refs/lines

### R12-C22

**isExplicitCoreTool额外登记当前无作用** — 成立 / engineering / scope=new / 不入分；统一问题：handoff-core-registration；credit=None。

[原文 L197–L197](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L197)

````text
15. `OperationAnalyzerRegistry` 的 `isExplicitCoreTool("HandoffRead")` 当前为无操作代码
````

触发：isExplicitCoreTool额外登记当前无作用
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：不影响实际授权/工具注册，可维护性建议。

判定理由：不影响实际授权/工具注册，可维护性建议。

代码：[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)

核验：代码静态核验及原文：见code_refs/lines

### R12-C23

**连续UserMessage依赖normalize未测** — 成立 / engineering / scope=new / 不入分；统一问题：consecutive-user-normalization-test；credit=None。

[原文 L198–L198](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L198)

````text
16. 投影 UserMessage 置于历史头部，原首条为 UserMessage 时连续同角色消息依赖 normalizeTyped 合并（未测）
````

触发：连续UserMessage依赖normalize未测
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：现有normalizeTyped正是处理协议归一化的层，缺用例不等于失败。

判定理由：现有normalizeTyped正是处理协议归一化的层，缺用例不等于失败。

代码：[HandoffContextService.java:84](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L84)；[QueryEngine.java:940](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L940)

核验：代码静态核验及原文：见code_refs/lines

### R12-C24

**轮询竞态测试未实际构造** — 成立 / engineering / scope=new / 不入分；统一问题：store-serialization-test-gap；credit=None。

[原文 L199–L199](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L199)

````text
17. 测试名不符实：store.test 中「晚到 GET 不覆盖」竞态并未被真正构造（该竞态实际由 while(inflight) 串行化防住，设计正确）
````

触发：轮询竞态测试未实际构造
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：测试中迟到GET未被真正起动，while inflight实现仍可挡住；测试质量发现不算功能TP。

判定理由：测试中迟到GET未被真正起动，while inflight实现仍可挡住；测试质量发现不算功能TP。

代码：[sessionMergeStore.test.ts:487](../../../../../frontend/src/store/sessionMergeStore.test.ts#L487)；[sessionMergeStore.ts:227](../../../../../frontend/src/store/sessionMergeStore.ts#L227)

核验：代码静态核验及原文：见code_refs/lines

### R12-C25

**其他准入/重试/磁盘/UI测试缺口** — 成立 / engineering / scope=new / 不入分；统一问题：merge-test-gaps；credit=None。

[原文 L205–L214](../zhikuncode/GLM5.3-session-merge-v2-code-review.md#L205)

````text
1. resume 409 后采纳 + 刷新；MERGE_ACTIVE_EXISTS 的 body.operation 采纳
2. 429/Retry-After 退避序列及耗尽转 `paused/PROVIDER_UNAVAILABLE`（无直接用例）
3. `MERGE_LENGTH_STOP` 触发缩小的直接用例（413 已覆盖）
4. 413 恢复 / 换模型重试**带投影**的回归用例（文档 §9「小模型/多次压缩/413 后入口仍可重建」）
5. `seal()`/`prepare()` 写入路径的磁盘不足（仅旧 build() 路径测试）
6. 快照窗口内来源树新增子会话（对应中 3）
7. 面板 paused UI（暂停原因、retryAt、totalFinal 文案、按钮禁用）；targetAvailable=false 的禁用与提示；protocol=1 只读展示；cancel 幂等/MERGE_ALREADY_COMPLETED
8. 合并会话连续 user 消息的归一化用例

已覆盖的强项：封存即释放 + 截止后消息排除、commit 不确定、哈希篡改（快照/结果/读取三层）、8MiB 截断续扫与跨片去重精确偏移、游标伪造/过滤变更绑定、迁移回滚 + CHECK + NULL 语义、损坏 checkpoint 暂停且 resume 不可绕过、>16 单元、合成 4,183 bytes + 13,370 output 不因 3,500 拒绝且缺 usage 记未知非零费、resume 只重做未完成单元、取消清理延迟到 writer 退出、404/localStorage 写失败/多 tab/不抢焦点。
````

触发：其他准入/重试/磁盘/UI测试缺口
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：保留全部8类缺口，不把测试不足直接当产品错误。

判定理由：保留全部8类缺口，不把测试不足直接当产品错误。

代码：[session-merge-architecture-v2.md:440](../../../../session-merge-architecture-v2.md#L440)

核验：代码静态核验及原文：见code_refs/lines

## R13

[完整原报告](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md)

### R13-normal_function

**判断分：5/5**。[原文 L595–L595](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L595)。未发现普通主链路破坏这一功能判断与当前独立核验一致；不要因合并域缺陷倒推普通回归。

````text
**第一个问题可以明确回答：非合并链路没有被破坏。** 这个结论有代码级（9 条链路逐条推演）、diff 级（文件清单核对）、运行时级（9 个回归类全绿 + HEAD 基线对照证明 11 个失败与本次无关）三重独立证据支撑。
````

### R13-boundary

**判断分：5/5**。[原文 L461–L462](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L461)。尽管速览未造成任何影响措辞过满，正文明确合并清理查询可影响启动及关闭writer共享资源边界；按冻结规则结合全文，不机械扣分。

````text
- `SessionMergeService:272` `progress.cleanupCandidates()` 在 try **之外**：DB 不可读时会从 `execute()` 的 finally（可能掩盖原异常）与 `recover()`（`@PostConstruct`，**可阻断启动**）抛出，与 §4.4"数据库不可读则保留"意图不符。
- `SessionMergeService:287` `worker.shutdown()` 未 awaitTermination → `@PreDestroy` 返回后 writer 仍可能写文件/查库（DataSource 或已关闭）。
````

### R13-release

**判断分：5/5**。[原文 L597–L605](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L597)。原文597明确现在还不能发，602要求真实语义验收；允许阶段提交不扣分，缺陷漏审不在发布维重复罚。

````text
**但第二个问题的答案是否定的：现在还不能发。** 障碍不在产品逻辑（**阻断级代码缺陷 0 项**），而在**验证的完整性与陈述的真实性**：

- 规格第 9 节明令"保留并扩展"的测试被删掉了 8 项，其中工具 ID 隔离、审批/后台忙时拒绝、慢 hook 三项是规格**点名要求保留**的
- 6 个规格错误码有 5 个零断言，且恰好掩盖了一个真实的 500 缺陷（I2）
- `occurrences.jsonl` 和 E 状态隔离这两个**核心语义**完全无覆盖——前者正是本次改造要解决的线上失败的直接成因
- §9 强制的真实模型语义验收未执行，仓库无任何样本或记录
- 文档写着一个在本机无法复现的"0 失败"

按第六节清单完成第 1–6 项后即可发布；第 7 项（I1）强烈建议同批修掉，因为它是唯一会让用户在正常使用中撞到 500 且**无法自愈**的缺陷。
````

### R13-semantic

**判断分：5/5**。[原文 L249–L255](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L249)。正确说明真实模型/人工样本未执行，stub不可替代；572也要求明确未完成前置验收。

````text
#### B5 规格第 9 节强制的真实模型语义验收未执行

§9 要求一个具体的接续开发样本：A 将服务端响应字段从 `name` 改为 `displayName` 并记录验证结果；B 的前端仍读取 `name`，有未完成联调待办；A/B 使用同一临时工程，合并为 E 后要求其完成联调。另需 7 类人工标注样本（用户约束、子任务尾部失败、冲突双方、未验证自述、不同版本、重复合并、附件未知），以及"封存后共享代码又有变化"的变体。

仓库中**无任何脚本、夹具或记录**：`docs/zhikun/Session-Merge*Acceptance*` 均为 09-20 的 v1 旧版，不含 `displayName`。

文档自己承认"真实模型的人工语义样本尚未执行"，并明确"不得将 stub 的数据、隔离与接线断言描述成真实模型接续开发验收"。这是**发布前置人工验收缺口**，必须显式标注，不得以 stub 结果替代或掩盖。
````

### R13-C01

**B1有效测试删除与断言弱化** — 成立 / engineering / scope=new / 不入分；统一问题：admission-tool-id-hook-test-gaps；credit=None。

[原文 L178–L210](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L178)

````text
#### B1 测试覆盖率倒退：8 个测试被无规格依据地删除

规格第 9 节明确要求"改写"的 5 类旧断言（来源锁到摘要结束 / 摘要失败必须清包 / 超 16 次必须失败 / 重启全部失败 / 缺 usage 必须失败）以及 CopyLimit 测试，删除是**合法的**，已逐条确认：

- `fiveSourcesStayOccupiedUntilSummaryExitsAndUnrelatedSessionCanContinue`（"所有来源锁到摘要结束"）✓ 合法
- `summaryFailureCreatesNoTargetAndReleasesBothSources` / `textFailureCleansPackageEvenWhenRecordingFailureIsRejected`（"摘要失败必须清包"）✓ 合法
- `overCallBudgetFails` 及 `fiveSourceFailures` 的 `SUMMARY_CALL_BUDGET_EXCEEDED` 分支（"超 16 次必须失败"）✓ 合法
- `recoveryFailsInterruptedPreparationButKeepsCommittedPackages`（"重启全部失败"）✓ 合法，已由 `unexpectedRestartContinuesSealedSnapshotWithoutLiveSources` 替代
- `copyLimitWarningStillCommitsTargetAndPreservesSources`（§9 明令"旧 CopyLimit 测试不能继续要求托管原件复制失败后带警告发布"）✓ 合法
- `providerOutputTokens…MissingUsageStillFailsClosed`（"缺 usage 必须失败"）✓ 合法
- `reasoningUsageDoesNotReject…OversizeVisibleTextStillFails` / `largeModelWindow…OldByteCeiling`（旧 3500 正文上限）✓ 合法

**但以下删除无规格依据**，已由协调者逐一独立查证：

| 被删测试 | 违反的规格条款 | 独立复核结果 |
|---|---|---|
| `rejectsInvalidSourceSetsWithoutOccupyingSessions` | §5.2 要求**保留** start 请求标准化（2~5 个不同来源、主来源在集合内、标题与幂等键长度限制） | 新增 `idleRootAndDescendantsAreBothRequired` 只覆盖空闲检查，**不覆盖参数校验**；`../B`、null、重复来源、超长标题/幂等键均无断言 |
| `fifthSourceAndDescendantBusyRejectWithoutLeakingEarlierOccupancy`<br>`rejectsWaitingApprovalAndPartialAdmissionReleasesFirstSource` | §9"占用与释放"明列**根/第五来源/子任务/审批/后台服务**忙时拒绝 | 仅"根+子任务"保留；**审批（waiting_interaction）、后台服务（acquireBackgroundLease）、第五来源忙、部分准入回滚全部消失**；"无关聊天可用"与"来源可 deleteSession"也无断言 |
| `sameSourceToolIdsNeverEnterENativeHistoryAndENextMessageNormalizes` | §9 明令"保留并扩展原有…**工具 ID 隔离**…测试"；§8"不复制来源原生 tool calls 到目标" | grep 确认：merge 测试中 `toolUseId` **仅 1 处命中**（`MergePackageServiceTest:305`），且是 legacy 夹具输入，**不是隔离断言**；`MergePackageServiceTest:198` 甚至反向断言包内**含** same-tool-id |
| `slowCreationHookDoesNotRetainSourcesOrPreventAnotherMerge`<br>+ `uncertainCommitAndHookFailureCannotEraseCompletedTarget` 的 hook 半段 | §9"发布与取消竞态…**慢 hook**"；§8"通知 hook 失败不回滚已完成目标" | 新增 `uncertainCommitKeepsTheDurableTargetAndItsPackage` 只覆盖提交不确定，**慢 hook 与 hook 失败路径零覆盖**；"最多一个目标"仅间接体现 |
| `missingOperationReturnsStableNotFoundCodeWithoutChangingSourcesOrGate` | §5.3"GET 404 沿用 `MERGE_OPERATION_NOT_FOUND`" | grep 确认：该错误码 **main=2 处（`SessionMergeService:137/261`）、test=0 处**；`SessionMergeServiceTest:167` 的 404 只断言状态码不断言 code |
| `atomicHandoffPreservesSourcesBlocksWritesAndRetryReturnsSameTarget` 丢失的 4 项断言 | §1 要求保留"权限默认策略和最终请求预算校验"；§8"createSessionRecord(…现有权限默认)" | grep 确认：`AUTO_APPROVE` / `permission_mode` / `permissionMode` 在 merge 测试中**零命中**；同时丢失幂等键冲突、`totalUsage=zero`、`verifyNoInteractions(f.app)` |
| `diskFailureAndMessageCommitFailureDoNotLeaveEmptyE` | §9"保留并扩展原有**磁盘**…测试" | **协调者修正审查员的判断**：磁盘防护本身仍有覆盖（`MergePackageServiceTest:389/462` 断言 `MERGE_DISK_SPACE_LOW`），但"**磁盘失败不得留下空 E**"这一服务级原子性断言确实消失 |

另有 `MergeSummaryServiceTest` 侧丢失 `usesToolFreeIndependentCalls` 的 usage 汇总断言与 `stagedSummaryInputsRespectDiskReserve`。

**断言强度降级（非删除，但等效弱化）**

- 错误码断言降为异常类型：`SessionMergeServiceTest:81/124/137` 用 `isInstanceOf(Conflict.class)`，规格 §5.3 的 7 个错误码中仅 `MERGE_STALE_OPERATION` 有断言
- `SessionMergeServiceTest:80` 目标消息断言由"1 条 UserMessage + usage=0 + AUTO_APPROVE"降为 `COUNT(*)==1`
- 4,183 bytes 这一关键数字**降为注释**（`MergeSummaryServiceTest:57` 用 `"说明".repeat(750)`，无字节断言）
- `hasMessageContaining("来源会话")` → `("来源")`
- `SessionMergeServiceTest:60` await 由 10s→20s 且轮询条件放宽（建议加注释说明，避免掩盖挂起）
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：基线有效准入/工具ID/hook/默认权限/磁盘原子性断言部分未迁移；report正确区分新规范已废旧断言。不等于产品必错。

判定理由：基线有效准入/工具ID/hook/默认权限/磁盘原子性断言部分未迁移；report正确区分新规范已废旧断言。不等于产品必错。

代码：[SessionMergeServiceTest.java:69](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L69)；[SessionMergeServiceTest.java:146](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L146)；[MergePackageServiceTest.java:380](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergePackageServiceTest.java#L380)

核验：代码静态核验及原文：见code_refs/lines

### R13-C02

**文档未放宽断言不实** — 不成立 / engineering / scope=new / 不入分；统一问题：isolated-test-evidence-invalidated；credit=None。

[原文 L212–L212](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L212)

````text
**未发现以下放宽手段**（这一点是好的）：`@Disabled` / `@Ignore` / `assumeTrue` / 注释掉的断言 / `catch(Exception ignored)` **全部 0 命中**；`frontend/vitest.config`、`package.json` 未被修改，两个前端测试文件 diff 中无 timeout 调大（`advanceTimersByTime` 均为既有行）。因此文档"未放宽断言或超时"在**超时维度成立**，在**断言强度与覆盖广度维度不成立**。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：规范7说该次隔离复跑失败后降低worker并按原超时，不是声称实现相对b98从未调整测试。报告以开发diff断言变化否认执行过程限定，混淆口径。

判定理由：规范7说该次隔离复跑失败后降低worker并按原超时，不是声称实现相对b98从未调整测试。报告以开发diff断言变化否认执行过程限定，混淆口径。

代码：[session-merge-architecture-v2.md:7](../../../../session-merge-architecture-v2.md#L7)

核验：代码静态核验及原文：见code_refs/lines

### R13-C03

**并发测试均无真实线程竞争** — 不成立 / engineering / scope=new / 不入分；统一问题：concurrency-test-no-threads-claim；credit=None。

[原文 L214–L214](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L214)

````text
**测试卫生（符合规格）**：全部使用 `@TempDir` + SQLite 临时库 + mock provider，**无写入运行库**，无提交真实会话/附件。但并发类测试均为 `CountDownLatch` 阻塞单线程或纯顺序调用，**未构造真实线程竞争**（见 I8-coverage）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：ServiceTest130由真实异步worker阻塞provider，测试线程cancel并释放；确有cancel与worker的跨线程交错。可说缺双请求同时resume/start，不能说全部只单线程。

判定理由：ServiceTest130由真实异步worker阻塞provider，测试线程cancel并释放；确有cancel与worker的跨线程交错。可说缺双请求同时resume/start，不能说全部只单线程。

代码：[SessionMergeServiceTest.java:130](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L130)；[SessionMergeService.java:133](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L133)

核验：代码静态核验及原文：见code_refs/lines

### R13-C04

**B2缺精确冲突码测试** — 成立 / engineering / scope=new / 不入分；统一问题：conflict-code-tests；credit=None。

[原文 L216–L231](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L216)

````text
#### B2 规格要求的 6 个冲突错误码，5 个测试命中为 0

协调者独立 grep 结果：

```
MERGE_ACTIVE_EXISTS         main=1  test=0
MERGE_SOURCE_BUSY           main=4  test=0
MERGE_STALE_OPERATION       main=3  test=1
MERGE_ALREADY_COMPLETED     main=1  test=0
MERGE_IDEMPOTENCY_CONFLICT  main=1  test=0
MERGE_CANCEL_CLEANUP        main=1  test=0
```

违反 §5.3"冲突沿用 `{error:{code,message}, operation?: 最新操作}`，区分 `MERGE_ACTIVE_EXISTS`、`MERGE_SOURCE_BUSY`、`MERGE_STALE_OPERATION`、`MERGE_ALREADY_COMPLETED`、`MERGE_IDEMPOTENCY_CONFLICT`；取消后写入器尚未退出返回 `MERGE_CANCEL_CLEANUP`"。

**且这不是纯粹的测试缺口——它对应真实缺陷**：见 I2，resume/create 竞态实际会退化成 HTTP 500。断言从精确错误码降级为 `isInstanceOf(Conflict.class)`，正好掩盖了这个 bug。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：多数测试只断Conflict类，精确错误码缺显式断言；零文本命中不证明分支零执行，后果须与I2另判。

判定理由：多数测试只断Conflict类，精确错误码缺显式断言；零文本命中不证明分支零执行，后果须与I2另判。

代码：[SessionMergeServiceTest.java:81](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L81)；[SessionMergeServiceTest.java:165](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L165)

核验：代码静态核验及原文：见code_refs/lines

### R13-C05

**B3 occurrences缺显式断言** — 成立 / engineering / scope=new / 不入分；统一问题：occurrence-ledger-tests；credit=None。

[原文 L233–L239](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L233)

````text
#### B3 `occurrences.jsonl` 全仓测试零命中

协调者 grep 确认 `grep -rn "occurrences" src/test/java/` **无任何输出**。

违反 §4.2（重复位置写 occurrences.jsonl 而**不是**灌给模型）与 §9"长历史与重复"（**checkpoint 重复位置不灌入 LLM**）。

这条是本次改造的**核心动机之一**：规格 §2.1 记录的线上失败中，11 个子会话的 58 个 checkpoint 去重后有 840 条约 4,826,471 字节，**另有 1,866 个重复出现位置**——正是这些重复位置灌进模型导致容量爆炸。如今这个机制完全没有断言保护。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：当前正式merge测试无occurrences断言，机制确为规范要求；未给出证据证明1866位置是历史失败唯一直接根因，后者不沿用为产品结论。

判定理由：当前正式merge测试无occurrences断言，机制确为规范要求；未给出证据证明1866位置是历史失败唯一直接根因，后者不沿用为产品结论。

代码：[MergePackageService.java:370](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L370)；[session-merge-architecture-v2.md:150](../../../../session-merge-architecture-v2.md#L150)

核验：代码静态核验及原文：见code_refs/lines

### R13-C06

**B4独立缓存待办设置未直测** — 成立 / engineering / scope=new / 不入分；统一问题：session-state-isolation-tests；credit=None。

[原文 L241–L247](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L241)

````text
#### B4 E 的状态隔离零覆盖

协调者 grep 确认 merge 测试目录中**无任何文件**提及 `FileStateCache` 或 `TodoWrite`。

违反 §8"`SessionManager.getFileStateCache(E)` 从空缓存开始，不 clone/merge A/B 的已读缓存"、"E 的发消息、压缩、待办/设置变更与删除不能回写 A/B"，以及 §9"接续资料与状态隔离"整行（E 使用独立 ID 和空文件缓存，E 发消息/压缩/更新待办或设置/删除不改 A/B 的对应状态，来源后续消息不进入 E 快照）。

其中"E 独立 ID + metadata"（`SessionMergeServiceTest:78`）与"来源后续消息不入快照"（`:100`）有覆盖，**空 FileStateCache 与不回写 A/B 完全无覆盖**。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：新session与消息截断有覆盖，TodoWrite/FileStateCache不回写来源缺直接行为断言。

判定理由：新session与消息截断有覆盖，TodoWrite/FileStateCache不回写来源缺直接行为断言。

代码：[SessionMergeService.java:209](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L209)；[SessionMergeServiceTest.java:100](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/SessionMergeServiceTest.java#L100)；[session-merge-architecture-v2.md:417](../../../../session-merge-architecture-v2.md#L417)

核验：代码静态核验及原文：见code_refs/lines

### R13-C07

**B5真实语义验收未执行** — 成立 / engineering / scope=new / 不入分；统一问题：semantic-acceptance-missing；credit=None。

[原文 L249–L255](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L249)

````text
#### B5 规格第 9 节强制的真实模型语义验收未执行

§9 要求一个具体的接续开发样本：A 将服务端响应字段从 `name` 改为 `displayName` 并记录验证结果；B 的前端仍读取 `name`，有未完成联调待办；A/B 使用同一临时工程，合并为 E 后要求其完成联调。另需 7 类人工标注样本（用户约束、子任务尾部失败、冲突双方、未验证自述、不同版本、重复合并、附件未知），以及"封存后共享代码又有变化"的变体。

仓库中**无任何脚本、夹具或记录**：`docs/zhikun/Session-Merge*Acceptance*` 均为 09-20 的 v1 旧版，不含 `displayName`。

文档自己承认"真实模型的人工语义样本尚未执行"，并明确"不得将 stub 的数据、隔离与接线断言描述成真实模型接续开发验收"。这是**发布前置人工验收缺口**，必须显式标注，不得以 stub 结果替代或掩盖。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：规范自认，报告正确要求与stub分开。

判定理由：规范自认，报告正确要求与stub分开。

代码：[session-merge-architecture-v2.md:3](../../../../session-merge-architecture-v2.md#L3)；[session-merge-architecture-v2.md:453](../../../../session-merge-architecture-v2.md#L453)

核验：代码静态核验及原文：见code_refs/lines

### R13-C08

**B6文档验证不实需替换为环境失败** — 不成立 / engineering / scope=new / 不入分；统一问题：isolated-test-evidence-invalidated；credit=None。

[原文 L257–L284](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L257)

````text
#### B6 文档验证陈述不实（阻断文档发布，不阻断代码发布）

| 文档声称 | 实测 |
|---|---|
| 后端全量"2,905 通过、**0 失败/错误**" | **2,894 通过、11 失败，`BUILD FAILURE`，EXIT=1** |
| 前端"**876** 通过" | **894 通过** |
| "构建仅有现有大 chunk/Browserslist 提示" | 另有 empty chunk ×2（`editor`、`terminal`）、动态/静态混合导入提示 ×6（`stompClient.ts`、`sessionActivation.ts`、`MermaidBlock.tsx`、`GitTimeline.tsx`、`SchemaViewer.tsx`、`@monaco-editor/react`） |

**必须说明的限定：这 11 个失败与本次改动无因果关系。** 归因采用三重证据，非推测：

1. **隔离复跑**：只跑这 4 个类 → 仍 `Tests run: 39, Failures: 11` → 排除测试顺序/状态污染
2. **HEAD 干净基线**：`git worktree add /tmp/verify-head-baseline HEAD --detach`（**不含本次任何改动**）跑同样 4 个类 → `Tests run: 39, Failures: 11`，**类名+方法名逐一完全相同** → 排除本次改动引入
3. **代码级根因定位**：

| # | 类.方法 | 断言差异 | 根因 |
|---|---|---|---|
| 1-6 | `SecurityFilterIntegrationTest`：`noAuth_shouldReturn401` / `bearerToken_invalid_shouldReturn401` / `publicNetwork_shouldReturn403` / `urlToken_valid_shouldRedirect` / `bearerToken_valid_shouldPassAndIssueCookie` / `cookie_valid_shouldPass` | `expected:<401> but was:<200>`；`expected:<403> but was:<200>`；`expected:<REDIRECTION> but was:<SUCCESSFUL>`；Set-Cookie 为 null | 本机导出 **`ALLOW_PRIVATE_NETWORK=true`**。`RemoteAccessSecurityFilter.java:126-130`：`if (allowPrivateNetworkAccess) { chain.doFilter(...); return; }` → 对所有来源 IP 免认证放行，鉴权层被整体短路 |
| 7-9 | `CoordinatorServiceTest`：`matchSessionMode_same_mode_returns_null` / `matchSessionMode_coordinator_then_isCoordinatorMode_returns_true` / `matchSessionMode_normal_then_isCoordinatorMode_returns_false` | `expected: <null> but was: <Exited coordinator mode…>`；`isCoordinatorMode() should return false ==> expected: <false> but was: <true>` | 本机导出 **`ZHIKUN_COORDINATOR_MODE=1`**。`CoordinatorService.java:89-94`：`isCoordinatorMode()` 在 `runtimeEnv` 无值时回退 `System.getenv(...)` → 测试"默认非 coordinator 模式"的前提不成立 |
| 10 | `OneKeyRegistryIntegrationTest.genericLlmApiKeyDoesNotAuthenticateZhipuDashscopeMcp:137` | `expected: <> but was: <sk-9***-35***>` | 测试用 `new StandardEnvironment()`（自动包含真实 systemEnvironment），本机存在真实 `DASHSCOPE_*` key |
| 11 | `WorkspaceFileBoundaryTest.recursiveGrepSkipsProtectedFilesButDirectAccessCanBeAuthorized:269` | `Expecting actual: "" to contain: "directory-secret"` | 本机**未安装 ripgrep**。日志明确：`GrepTool - ripgrep not found, falling back to system grep` → 回退路径输出空串 |

四者及其被测生产类**均不在本次 diff 中**。

**总数 2,975 与跳过 70 精确吻合**，说明作者确实在同一测试集上跑过全量。前端 894 与 876 的差 **+18 可完全对账**：来自被排除的其他任务测试新增（`Sidebar.desktop.test.tsx +2`、`ImageBlock.test.tsx +5`、`MessageActions.test.tsx +5`、`messageContent.test.ts +6` = +18），而文档明说其验证"未纳入其他任务的 UI/消息复制变更" → **876 + 18 = 894** ✓。

**问题定性**：文档把**环境前提当成了默认前提而未声明**。这份文档会被提交到 GitHub，"0 失败"在任何一台带上述变量或缺 ripgrep 的机器上都不可复现，会误导 reviewer 与 CI。建议改写为：

> 后端全量 2,975 项：2,894 通过、70 跳过、**11 失败**——失败集中在 4 个既有非密封测试类（`SecurityFilterIntegrationTest` / `CoordinatorServiceTest` / `OneKeyRegistryIntegrationTest` / `WorkspaceFileBoundaryTest`），在**不含本次改动的 HEAD 基线上同样复现**，根因为宿主环境变量 `ALLOW_PRIVATE_NETWORK`、`ZHIKUN_COORDINATOR_MODE`、真实 `DASHSCOPE_*` key 与缺失 ripgrep，与本次改动无关。前端 103 文件：894 通过、16 跳过（876 为不含其他任务 UI/消息测试新增 +18 的口径）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：文档7明确clean副本只叠加本次改动，报告280自己确认前端+18来自排除变更。宿主env/ripgrep造成基线失败不能推翻另一隔离运行成功；建议补环境可以，强制把其11失败替换原实测不成立。

判定理由：文档7明确clean副本只叠加本次改动，报告280自己确认前端+18来自排除变更。宿主env/ripgrep造成基线失败不能推翻另一隔离运行成功；建议补环境可以，强制把其11失败替换原实测不成立。

代码：[session-merge-architecture-v2.md:7](../../../../session-merge-architecture-v2.md#L7)

核验：代码静态核验及原文：见code_refs/lines

### R13-C09

**既有非密封测试及密钥片段风险** — 范围外 / conditional / scope=out / 不入分；统一问题：preexisting-environment-test-hygiene；credit=None。

[原文 L286–L286](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L286)

````text
> **顺带发现（既有问题，非本次引入，建议单独立项）**：`OneKeyRegistryIntegrationTest` 的失败信息会把开发者**真实 API key 片段打印进 surefire 报告**（`expected: <> but was: <sk-9...>`）。这 4 个非密封测试类直读宿主 env、依赖宿主安装 ripgrep，会持续给所有开发者制造假红，属既有安全隐患，应优先处理。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：相关测试和生产类非本次改动；env进入断言有泄露风险，但按报告自身建议单独立项，不计此次产品TP/FP。

判定理由：相关测试和生产类非本次改动；env进入断言有泄露风险，但按报告自身建议单独立项，不计此次产品TP/FP。

代码：[OneKeyRegistryIntegrationTest.java:137](../../../../../backend/src/test/java/com/aicodeassistant/mcp/OneKeyRegistryIntegrationTest.java#L137)；[WorkspaceFileBoundaryTest.java:269](../../../../../backend/src/test/java/com/aicodeassistant/security/WorkspaceFileBoundaryTest.java#L269)

核验：代码静态核验及原文：见code_refs/lines

### R13-C10

**I1容量错误与HTTP500控制流** — 成立 / functional / scope=new / 入分；统一问题：D09；credit=0.5。

[原文 L292–L310](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L292)

````text
#### I1 E 会话可被未分类异常永久打死（头号功能风险，协调者亲自验证）

`HandoffContextService.project()` 抛的是裸 `IllegalStateException`：

```java
if(cost(body,model,ratio)>limit) body=toolAvailable ? minimal : unavailable;
if(cost(body,model,ratio)>limit) throw new IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL");
var message=new Message.UserMessage(...);
int cost=Math.max(tokens.estimateTokens(List.of(message),model),cost(body,model,ratio));
if(cost>limit) throw new IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL");
```

**异常边界已亲自确认**：`execute()`（第 294 行起）**没有包裹轮次循环的外层 try**；第 841 行位于 783–799 的 try/finally（MDC 块）**之外**，841 之后下一个 try 起点在 **868** 行；`catch (LlmApiException e)` 在 1041、`catch (RuntimeException e)` 在 1163，都属于 868/998 之后的 LLM 调用块。→ 异常**直接穿出 `execute()`**，经 `GlobalExceptionHandler:138` 变成 **500 INTERNAL_ERROR**。

违反 §7.1"连最小入口和当前请求都放不下时**返回正常容量错误**，不把已完成合并改为失败"。

**更严重的两点：**

1. **`toolAvailable==false` 时没有任何降级路径。** 用户一旦限制工具集，`body` 只能是 `unavailable`（无法退到 `minimal`，因为 minimal 会让模型去调用不可用的工具），若 `cost(unavailable) > limit` 就直接抛。`limit = min(2048, max(0, totalBudget/10))`，`unavailable` 约 100 中文字 + 64 → 只要 `historyInputBudget ≲1640` 就会触发。**小窗口模型或大系统提示下，用户一次工具限制就能让 E 每轮 500**，违反 §7.2"若用户明确禁用工具，尊重限制并**提示**资料工具不可用"。
````

触发：E历史输入预算过小或工具禁用说明放不下
预期：可识别的正常容量错误
实际：未分类查询错误；HTTP500归因不成立

判定理由：project容量不足抛通用IllegalStateException属实；报告断言execute无外层catch错误，execute364/407捕获并包装QueryResult，REST一般200。拟部分命中，最终响应由主评审复现统一。

核实范围：E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)；[QueryEngine.java:407](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L407)

核验：[reproductions/RankingQueryReproTest.java](reproductions/RankingQueryReproTest.java)

### R13-C11

**I1绑定丢失/损坏无普通降级** — 规范歧义 / conditional / scope=new / 不入分；统一问题：handoff-missing-binding-error；credit=None。

[原文 L312–L316](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L312)

````text
2. **`configure()` 的失败无法自愈。** `HandoffReadService:47` 在无绑定时抛 `AuthorizationException("HANDOFF_NOT_BOUND","当前根会话没有可用交接资料")`，`configure()` 在 ID 不符时抛 `IllegalStateException("HANDOFF_BINDING_MISMATCH")`；这两个调用点在 `QueryController:178/265/362` 与 `WebSocketController:959` 均**不在 try 内**（已亲自阅读 `QueryController:165-192` 确认无包裹）。合并记录消失或包损坏后，**E 的每个请求都 500，UI 无任何恢复入口**。

**设计不一致值得注意**：`project()` 在 `binding.isEmpty()` 时**优雅返回空投影**（`return new Projection(List.of(),0)`），而 `configure()` 却抛异常。同一个"绑定不存在"条件，两条路径语义相反。

**修复建议**：统一为降级语义——投影失败返回空投影 + 一条提示消息（可复用 `emitImageNotice` 式通道），或包装成 `LlmApiException(...,413)` 让其进入既有 413 处理；`configure()` 绑定失败降级为"本会话暂无交接能力"而非终止请求。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：configure在QueryEngine前可能通用500；与project空绑定行为不统一。但损坏绑定不能默默当有资料普通会话，规范要求failclosed，不承诺自动修复删库。应改善错误说明不必普通降级。

判定理由：configure在QueryEngine前可能通用500；与project空绑定行为不统一。但损坏绑定不能默默当有资料普通会话，规范要求failclosed，不承诺自动修复删库。应改善错误说明不必普通降级。

代码：[HandoffContextService.java:41](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L41)；[HandoffContextService.java:66](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L66)；[HandoffReadService.java:47](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L47)；[session-merge-architecture-v2.md:398](../../../../session-merge-architecture-v2.md#L398)

核验：代码静态核验及原文：见code_refs/lines

### R13-C12

**I2并发resume/create直接500** — 不成立 / functional / scope=new / 入分；统一问题：local-admission-race-error；credit=0。

[原文 L318–L324](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L318)

````text
#### I2 resume/create 竞态退化为 500 而非 409

`MergeProgressRepository:108-118` 的 `changed()` 抛 `IllegalStateException("MERGE_STALE_EXECUTION")` → `GlobalExceptionHandler:138` → **500 INTERNAL_ERROR**。违反 §5.3"并发重复 resume 只有一个成功，**其余返回当前状态**"（应为 409 + `operation`）。

同类：`create` 撞唯一索引抛 `DataIntegrityViolation` → 500 而非 `MERGE_ACTIVE_EXISTS`（`SessionMergeService:105`）。

这正是 B2 中错误码零断言所掩盖的缺陷。**修复**：在 Service 层捕获并转 `Conflict`。
````

触发：同应用并发创建/恢复
预期：一个成功其余409当前状态
实际：服务层同步与writers守卫串行处理；未证明所述500

判定理由：报告从Repository.changed跳过Service synchronized、writers和status/epoch预检。本机双浏览器重复请求串行，返回Conflict。跨进程共享库需另立范围，不按它声称普通并发缺陷计。

代码：[SessionMergeService.java:81](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L81)；[SessionMergeService.java:136](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L136)；[SessionMergeService.java:138](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L138)；[MergeProgressRepository.java:108](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L108)

核验：代码静态核验及原文：见code_refs/lines

### R13-C13

**I3 header使brief回退浪费聚合** — 成立 / engineering / scope=new / 不入分；统一问题：brief-header-budget-efficiency；credit=None。

[原文 L326–L334](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L326)

````text
#### I3 brief 阈值未扣 header，可能整份丢弃聚合成果

`MergeSummaryService:91` `if(capacity(brief)<=1536) break;` 与 `:145` `if(capacity(body)>2048) body=header;`。

header 含来源 ID 列表 + 栏目统计，可达数百 token：1500 token 的 brief + 600 token header = 2100 > 2048 → **整份 brief 被丢弃**，退化为纯目录。

结果符合 §6.2 的兜底语义（"就用程序生成来源/栏目/状态计数和详情入口"），但**浪费了全部聚合调用的费用与时间**。

**修复**：`1536` 改为 `2048 - capacity(header, model)`。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：1536阈值未减动态header，body>2048可整体用目录；详情不丢且规范允许兜底。预算优化建议，无资料正确性故障。

判定理由：1536阈值未减动态header，body>2048可整体用目录；详情不丢且规范允许兜底。预算优化建议，无资料正确性故障。

代码：[MergeSummaryService.java:91](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L91)；[MergeSummaryService.java:145](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L145)；[session-merge-architecture-v2.md:317](../../../../session-merge-architecture-v2.md#L317)

核验：代码静态核验及原文：见code_refs/lines

### R13-C14

**I4 import目的路径未复核** — 证据不足 / conditional / scope=new / 不入分；统一问题：ancestor-import-destination-containment；credit=None。

[原文 L336–L344](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L336)

````text
#### I4 `importPrevious` 目标路径缺包含性校验

`MergePackageService:643-654`：`relative=Path.of(entry.path())` 仅判 `startsWith("snapshot")`，随后 `root.resolve(Path.of("snapshot").relativize(relative))`。

实测 `snapshot/sub/../../evil` 能通过 `safeFile`（normalize 后为 `evil`，不以 `..` 开头），relativize 得 `../evil`，dest 逃出 `staging/` 落到 `<operationId>/evil`——**含 `<operationId>/snapshot/*`，可致后续原子 rename 失败**。

**判定：不可被利用**——前提是篡改一个已通过 `validateSnapshot` + DB `snapshot_hash` 校验的前序包，即攻击者已具备本地写权限。但违反 §4.2"禁止路径穿越"的纵深防御要求。

**修复**：加一行 `destination.normalize().startsWith(root.normalize())` 断言。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：词法destination可逃出staging，但需已有包目录与DB锚定hash自洽恶意路径。正常生成器安全，无给定支持触发；作为防御缺口记录，不从已能任意改DB推出新越权。

判定理由：词法destination可逃出staging，但需已有包目录与DB锚定hash自洽恶意路径。正常生成器安全，无给定支持触发；作为防御缺口记录，不从已能任意改DB推出新越权。

代码：[MergePackageService.java:628](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L628)；[MergePackageService.java:643](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L643)；[MergePackageService.java:649](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L649)

核验：代码静态核验及原文：见code_refs/lines

### R13-C15

**I5缺recordRef与恢复协议** — 成立 / functional / scope=new / 入分；统一问题：D04；credit=1。

[原文 L346–L352](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L346)

````text
#### I5 丢弃 recordRef 导致暂停后永久不可恢复

`MergePackageService:435-436`：`exportMessage` 的 catch 里 `record(...,"blocked")` 返回的 ref **被丢弃**，写入裸 `RECORD_REQUIRES_HANDLING`；而 `recoverBlockedProjections:233-238` 要求 `RECORD_REQUIRES_HANDLING:<recordRef>` 且属于 blockedRecords → 裸原因**必然抛错** → 该操作只能 cancel。

同类在 `:571`（`exportAsset` 解码失败用 extract 策略记录的 ref，不在 blockedRecords）。

方向是 fail-closed（安全），但违反 §4.4"保留其 raw 文件、可读记录头及已完成单元，转 paused/RECORD_REQUIRES_HANDLING"的**可恢复性意图**——规格设计这个状态就是为了封存后修正解析配置能恢复。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：裸reason及extract-policy附件不进blockedRecords属实；规范接受坏附件暂停，尚需可修复配置受支持场景。不能仅因真损坏资料不恢复判严重。

判定理由：裸reason及extract-policy附件不进blockedRecords属实；规范接受坏附件暂停，尚需可修复配置受支持场景。不能仅因真损坏资料不恢复判严重。；统一复核：合成原件不变，启用Jackson现有ALLOW_SINGLE_QUOTES后已可解析，恢复仍在读raw前被缺ref挡住；提高物化上限的正控制能恢复。仅确认这类关联协议缺口，损坏资料允许暂停，不采用永久/所有恢复失效的泛化。

核实范围：已修正解析配置仍被gap/ref关联校验阻止；不等于所有坏原件应可恢复。

代码：[MergePackageService.java:235](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L235)；[MergePackageService.java:436](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L436)；[MergePackageService.java:598](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L598)；[session-merge-architecture-v2.md:183](../../../../session-merge-architecture-v2.md#L183)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R13-C16

**I6读取错误码压平** — 成立 / functional / scope=new / 入分；统一问题：D13；credit=1。

[原文 L354–L360](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L354)

````text
#### I6 HandoffRead 错误码归类错误

`HandoffReadService:55、206-209`：verify 阶段所有 IOException 统一转 `AuthorizationException("HANDOFF_INVALID_RESOURCE")`，**超时/中断与哈希不符同码**（`InterruptedIOException` 已单独放行）。

违反 §7.2"校验未完成或整体超时明确报 `HANDOFF_READ_BUDGET_EXCEEDED`，不跳过哈希"与 §7.3"无绑定、未知 ref、包损坏**分别**报明确错误"。

测试用 `hasMessage` 断言，恰好掩盖了 code 差异。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：authorize/read验证阶段把预算与hash异常统一HANDOFF_INVALID_RESOURCE，只保留message，违背明确错误分类。

判定理由：authorize/read验证阶段把预算与hash异常统一HANDOFF_INVALID_RESOURCE，只保留message，违背明确错误分类。

核实范围：授权/读取支路把预算错误码包成INVALID_RESOURCE等泛码；read入口中断反例正常重抛。

代码：[HandoffReadService.java:43](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L43)；[HandoffReadService.java:55](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L55)；[HandoffReadService.java:208](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L208)；[session-merge-architecture-v2.md:387](../../../../session-merge-architecture-v2.md#L387)

核验：[reproductions/RankingReadReproTest.java](reproductions/RankingReadReproTest.java)

### R13-C17

**I7 descendants吞坏metadata** — 范围外 / functional / scope=retained / 不入分；统一问题：corrupt-descendant-metadata；credit=None。

[原文 L362–L366](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L362)

````text
#### I7 `descendants()` 静默吞 IOException

`MergePackageService:100-113` 的 `catch(IOException){}` 使 metadata 损坏的子会话**及其全部后代**整体不入快照、且**不写 gaps**。

违反 §4.1"未保存、已裁剪、缺少 checkpoint 的子任务过程明确记入 gaps，不冒充完整记录"——这是**静默数据丢失**，比报错更危险，因为用户会以为资料完整。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：代码与b98基线逐字相同；坏metadata无法确定归属，不应把全部不相关损坏会话都阻断当前合并。尚无其他可信关系可归属该child的具体受支持场景。

判定理由：代码与b98基线逐字相同；坏metadata无法确定归属，不应把全部不相关损坏会话都阻断当前合并。尚无其他可信关系可归属该child的具体受支持场景。

代码：[MergePackageService.java:100](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L100)；[MergePackageService.java:111](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L111)

核验：代码静态核验及原文：见code_refs/lines

### R13-C18

**I8非法外部路径导致整次暂停** — 成立 / functional / scope=new / 入分；统一问题：D10；credit=1。

[原文 L368–L372](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L368)

````text
#### I8 `Path.of(asset.originalPath())` 未捕获 `InvalidPathException`

`MergePackageService:458-463`（legacy 路径 `:831/840/845/859` 均有捕获，此处遗漏）。

`originalPath` 源自消息文本 `collectReferences`，含 NUL 或非法字符即抛未检查异常 → **整次 seal 转 paused**，而非记一条缺口。违反 §4.4 的"既有缺失附件/外部链接记录缺口"。
````

触发：文本block等于/missing/ref\u0000.txt
预期：标记无效/未知外部引用且保留其余已保存文本
实际：整次seal暂停，错误泛化

判定理由：collectReferences接受以/开头含NUL的普通文本字段；source460 Path.of抛InvalidPathException，外部未知引用本应仅gap，现导致seal整体暂停。已将最小输入交主评审集中复现。

核实范围：历史外部引用含NUL时Path.of抛错，中止封存而不是记外部缺口。

代码：[MergePackageService.java:935](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L935)；[MergePackageService.java:460](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L460)；[SessionMergeService.java:218](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L218)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R13-C19

**I9每轮重复project哈希** — 证据不足 / conditional / scope=new / 不入分；统一问题：repeated-handoff-integrity-io；credit=None。

[原文 L374–L380](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L374)

````text
#### I9 `project()` 非纯函数，E 每轮 ≥3 次全包完整性校验

`HandoffReadService:110-113` 的 `brief()` = `binding()`(DB 查询) + `verify()`（ready.json / snapshot manifest / recovered seal **三次哈希**）+ 读文件。

调用点：`QueryEngine:841`、`:844→1835`、`:779→1835`，恢复路径再叠加。

**仅影响 E**（普通会话在门控处早退），但每轮 3× DB + 3× 哈希是明显冗余。**修复**：按 `operationId+handoffHash` 每轮缓存一次 Projection，或让 `prepareCompactionContext` 复用主循环已算出的 `reservedTokens`。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：确有多调用路径，固定每轮>=3不准确：某些调用仅压缩/恢复才触发；无耗时基准。仅性能风险不算已证产品错误。

判定理由：确有多调用路径，固定每轮>=3不准确：某些调用仅压缩/恢复才触发；无耗时基准。仅性能风险不算已证产品错误。

代码：[QueryEngine.java:841](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L841)；[QueryEngine.java:1835](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1835)；[HandoffReadService.java:69](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L69)

核验：代码静态核验及原文：见code_refs/lines

### R13-C20

**I10取消后仍持锁被前端忽略** — 成立 / functional / scope=new / 入分；统一问题：D15；credit=1。

[原文 L382–L390](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L382)

````text
#### I10 前端仍以 `preparing` 推断来源锁定

`sessionMergeStore.ts:30` 虽已改读 `lockedSourceSessionIds`，但仍以 `status==='preparing'` 为**前置条件**。

违反 §5.3"前端不能从 preparing 推断来源锁定"与 §8"来源是否锁住**只看** lockedSourceSessionIds"、"封存后即使 preparing 也允许来源使用/删除"。

**后果**：cancel 后 writer 仍在清理、未封存即 paused、或从 localStorage 恢复的旧记录（缺 `lockedSourceSessionIds` 字段）时，**服务端仍持锁而前端判定"未锁"** → 用户发消息/删除被拒且无解释。

**修复**：`return pending?.operation?.lockedSourceSessionIds ?? NO_MERGE_SOURCES`。
````

触发：封存中取消，writer尚未收尾
预期：按真实lockedSourceSessionIds显示
实际：cancelled的非空锁集合被丢弃

判定理由：cancel先更新状态再abort，封存writer尚未退出可仍持gate；get返真实锁但selector仅preparing。服务拒写保护数据，属于短期锁展示错误。

核实范围：cancelled/paused短窗口真实锁仍在但selector隐藏；后端gate保护仍在。

代码：[SessionMergeService.java:149](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L149)；[SessionMergeService.java:230](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L230)；[SessionMergeService.java:263](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L263)；[sessionMergeStore.ts:30](../../../../../frontend/src/store/sessionMergeStore.ts#L30)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R13-C21

**I11可选DTO使undefined目标显示可打开** — 证据不足 / conditional / scope=new / 不入分；统一问题：frontend-dto-validation；credit=None。

[原文 L392–L398](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L392)

````text
#### I11 前端 DTO 可选性放宽导致门禁失效

`store.ts:16-19` 把 8 个后端**必返**字段全标为可选，`protocolVersion?: number` 而非 `1|2`。字段名本身与后端 `SessionMergeService:37-41` 的 Operation record 及 `MergeHandoffData:29` 的 progress 三字段**完全对齐**（这点是对的）。

**后果**：

- `panel:78/214` 用 `targetAvailable === false` 判断 → **`undefined` 被当作"可打开"**，违反 §8"只有 completed 且 targetAvailable 才能打开目标"
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：本地损坏/旧缓存可缺字段，Panel!==false较宽；真实Operation始终targetAvailable boolean。activateSessionCandidate仍核验目标，不等于成功越过服务门禁。可收紧DTO防御。

判定理由：本地损坏/旧缓存可缺字段，Panel!==false较宽；真实Operation始终targetAvailable boolean。activateSessionCandidate仍核验目标，不等于成功越过服务门禁。可收紧DTO防御。

代码：[sessionMergeStore.ts:16](../../../../../frontend/src/store/sessionMergeStore.ts#L16)；[SessionMergePanel.tsx:78](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L78)；[SessionMergeService.java:264](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L264)

核验：代码静态核验及原文：见code_refs/lines

### R13-C22

**I11缺expectedEpoch导致防重放失效** — 不成立 / functional / scope=new / 入分；统一问题：missing-epoch-bypasses-fence；credit=0。

[原文 L399–L401](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L399)

````text
- `store:228` 的 `expectedEpoch: runEpoch` 若缺失会序列化成 `{}`，使 §5.3 的 epoch 防重放机制失效

**修复**：按 §5.3 精确标注必选，门禁改 `!== true`。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：缺失Long/primitive epoch反序列化为0或请求校验错误，不会被后端视为任意epoch；Service138比较真实ledger epoch，v2创建至少1，因此拒绝而非绕过。

判定理由：缺失Long/primitive epoch反序列化为0或请求校验错误，不会被后端视为任意epoch；Service138比较真实ledger epoch，v2创建至少1，因此拒绝而非绕过。

代码：[SessionMergeService.java:138](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L138)；[MergeProgressRepository.java:67](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L67)；[sessionMergeStore.ts:238](../../../../../frontend/src/store/sessionMergeStore.ts#L238)

核验：代码静态核验及原文：见code_refs/lines

### R13-C23

**I12轮询禁用用户操作** — 成立 / functional / scope=new / 入分；统一问题：D08；credit=1。

[原文 L403–L407](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L403)

````text
#### I12 前端用 `submitting` 禁用恢复/取消按钮

`panel:222/225/186`：`submitting` 在**每 2s 轮询期间均为 true**（超时上限 15s，`store:149`）→ 恢复/取消/开始合并按钮间歇性不可点、点击无任何反馈。

store 已用模块级 `inflight` 串行化，应改用独立的 control-in-flight 标志。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：进度请求置submitting，panel按钮直接依赖，控制同时等inflight，最长15s。

判定理由：进度请求置submitting，panel按钮直接依赖，控制同时等inflight，最长15s。

核实范围：轮询复用submitting禁用恢复/取消，程序cancel也等待GET结束；不表示后端永久不能取消。

代码：[sessionMergeStore.ts:144](../../../../../frontend/src/store/sessionMergeStore.ts#L144)；[sessionMergeStore.ts:147](../../../../../frontend/src/store/sessionMergeStore.ts#L147)；[SessionMergePanel.tsx:222](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L222)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R13-C24

**I13 Panel新功能与旧epoch冲突测试不足** — 成立 / engineering / scope=new / 不入分；统一问题：panel-v2-test-gaps；credit=None。

[原文 L409–L413](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L409)

````text
#### I13 前端 Panel 新增 UI 基本无测试覆盖

`SessionMergePanel.test.tsx` 只做了 4 处适配（fixture 加 `lockedSourceSessionIds`、`/active` 返 204、两句文案），对**单元数与 `totalFinal` 文案、暂停原因、恢复（含模型下拉）、取消、`targetAvailable` 禁用、"目标会话已删除"零断言**。

`store.test.ts` 新增 117 行覆盖了 204、封存后不锁(`:418`)、不改写 preparing(`:410`)、写失败(`:282/305/319`)、多 tab(`:12/87`)、串行化(`:487`)，但**缺 §9 明列的"旧 resume 不重放"**（resume 返回 409 `MERGE_STALE_OPERATION`+operation 后刷新状态）、409 采用 operation、锁集合变化 dispatch、protocol=1 只读用例。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：现有store覆盖恢复成功/取消成功，panel主要fixture适配；新控件、错误码409同步缺覆盖。

判定理由：现有store覆盖恢复成功/取消成功，panel主要fixture适配；新控件、错误码409同步缺覆盖。

代码：[sessionMergeStore.test.ts:427](../../../../../frontend/src/store/sessionMergeStore.test.ts#L427)；[SessionMergePanel.tsx:217](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L217)

核验：代码静态核验及原文：见code_refs/lines

### R13-C25

**I14双向类型依赖与双Repository实例** — 成立 / engineering / scope=new / 不入分；统一问题：repository-dependency-organization；credit=None。

[原文 L415–L421](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L415)

````text
#### I14 Repository 反向依赖 + 双实例

`MergeProgressRepository:177` 的 `finishAttempt(..., MergeSummaryService.CallUsage)`，而 `MergeSummaryService:63` 又以 Repository 为参数 → **两类互相编译依赖**。违反 §10"Repository 只依赖 JDBC/事务/序列化""避免循环依赖"。

**修复成本极低**：把 `CallUsage` 移入 `MergeHandoffData`（其定位就是"共用 records/enums"）。

另：`SessionMergeService:72` 用 `new MergeProgressRepository(jdbc,json,transactions)`，而该类标了 `@Repository` → **Spring 另建一个 bean** 注入给 `HandoffReadService`/`HandoffContextService`。当前无状态故行为一致，但注解名不副实、编排侧无法注入测试替身。修复：改构造器注入（`MergeSummaryService` 不依赖 Service bean，无循环风险）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：Repository签名引用Summary.CallUsage且Service手动new Repository，另有Springbean；无状态所以不构成当前行为错误。

判定理由：Repository签名引用Summary.CallUsage且Service手动new Repository，另有Springbean；无状态所以不构成当前行为错误。

代码：[MergeProgressRepository.java:177](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L177)；[SessionMergeService.java:72](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L72)

核验：代码静态核验及原文：见code_refs/lines

### R13-C26

**I15暂停日志未打印原始异常栈** — 成立 / engineering / scope=new / 不入分；统一问题：pause-diagnostic-stack；credit=None。

[原文 L423–L429](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L423)

````text
#### I15 暂停日志丢弃异常栈，线上不可运维

`SessionMergeService:219` 只记 `code` + 异常类名，而 HEAD 是 `log.warn("Merge preparation failed: {}", operation, failure)`。

规格 §2.1 的整个失败排障正是**依赖 app.log 的堆栈**（文档引用了 `log/app.log:19692`、`:19788` 的堆栈行）——现在无法定位根因，等于自废了下次排障的能力。

**修复**：非预期 code 时把 throwable 一并交给 logger（仍不打印正文/凭据）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：catch日志只id/code/type，除persist暂停再失败外丢栈；诊断质量下降属可维护性缺口，不能扩大为线上无法运维所有故障。

判定理由：catch日志只id/code/type，除persist暂停再失败外丢栈；诊断质量下降属可维护性缺口，不能扩大为线上无法运维所有故障。

代码：[SessionMergeService.java:219](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L219)；[SessionMergeService.java:228](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L228)

核验：代码静态核验及原文：见code_refs/lines

### R13-C27

**I16清单行无长度上限** — 证据不足 / conditional / scope=new / 不入分；统一问题：catalog-line-memory-budget；credit=None。

[原文 L431–L433](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L431)

````text
#### I16 清单单行无长度上限

`HandoffReadService:141-149` 逐字节写入 `ByteArrayOutputStream`，仅靠 15s 截止兜底。一个哈希自洽但超大的损坏行可在截止前占用大量堆。非模型可控，属稳健性风险。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：ByteArrayOutputStream逐字节增长有15s没有固定line cap，需哈希自洽超大行且非模型控制；无可复现OOM/支持场景，列防御风险。

判定理由：ByteArrayOutputStream逐字节增长有15s没有固定line cap，需哈希自洽超大行且非模型控制；无可复现OOM/支持场景，列防御风险。

代码：[HandoffReadService.java:141](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L141)

核验：代码静态核验及原文：见code_refs/lines

### R13-C28

**I17 Service.read(asset)空成功** — 成立 / engineering / scope=new / 不入分；统一问题：read-asset-internal-dead-branch；credit=None。

[原文 L435–L437](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L435)

````text
#### I17 `read()` 对 `action=asset` 返回假成功

落入 else 分支返回 `entries:[], complete:true`。当前 `HandoffReadTool` 会先拦截 asset，但服务层契约仍应收紧。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：内部read分支未实现asset，但线上工具31专门调用asset()。内部API契约可收紧，非当前产品路径失败。

判定理由：内部read分支未实现asset，但线上工具31专门调用asset()。内部API契约可收紧，非当前产品路径失败。

代码：[HandoffReadService.java:238](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L238)；[HandoffReadTool.java:31](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L31)

核验：代码静态核验及原文：见code_refs/lines

### R13-C29

**900KiB请求守卫和文档1MiB** — 成立 / engineering / scope=new / 不入分；统一问题：request-byte-limit-documentation；credit=None。

[原文 L445–L445](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L445)

````text
- **预算常量与文档不符**：`MergeSummaryService:212` 请求 bytes 保护是 **900 KiB**，规格 §6.3 表格写的是 **1 MiB**。更严格但与文档不符——改常量或改文档，二者取一。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：代码更保守，可同步文档；只触发分片非拒绝总历史，未证明功能故障。

判定理由：代码更保守，可同步文档；只触发分片非拒绝总历史，未证明功能故障。

代码：[MergeSummaryService.java:212](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L212)；[session-merge-architecture-v2.md:325](../../../../session-merge-architecture-v2.md#L325)

核验：代码静态核验及原文：见code_refs/lines

### R13-C30

**schema失败attempt仍completed** — 不成立 / functional / scope=new / 入分；统一问题：attempt-schema-failure-outcome；credit=0。

[原文 L446–L446](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L446)

````text
- **attempt 终态早于校验**：`:324` 的 `usageSink` 在 `call()` 的 finally 里就写 `finishAttempt(...,"completed")`，随后 `validate()` 若失败，attempts 表仍记 completed。建议改记 `error` 或新增 `rejected` 语义。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：Summary230-233捕获validate的IOException并调用failAttempt，Repository199明确completed→error；已记usage保留。报告漏读补偿逻辑。

判定理由：Summary230-233捕获validate的IOException并调用failAttempt，Repository199明确completed→error；已记usage保留。报告漏读补偿逻辑。；统一复核：统一按其具体指控的可证明后果计轻微：错误分类/状态展示，未声称资料毁损；不沿用作者P级。

代码：[MergeSummaryService.java:230](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L230)；[MergeProgressRepository.java:197](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L197)

核验：代码静态核验及原文：见code_refs/lines

### R13-C31

**preCleanImageHistory未先预留投影** — 成立 / engineering / scope=new / 不入分；统一问题：preclean-handoff-budget-consistency；credit=None。

[原文 L447–L447](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L447)

````text
- `QueryEngine:2698-2702` `preCleanImageHistory` 自算 `inputBudget` 并调 `enforcePhase1`，**未预留投影**。无正确性风险（循环内 `:847` 会用正确 `historyBudget` 重裁），但与 §7.1 第 4 条"使用同一规则"精神不一致。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：预清理预算未扣但后续正常主循环再次以historyBudget裁切，报告自己承认无正确性风险。

判定理由：预清理预算未扣但后续正常主循环再次以historyBudget裁切，报告自己承认无正确性风险。

代码：[QueryEngine.java:2698](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L2698)；[QueryEngine.java:847](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L847)

核验：代码静态核验及原文：见code_refs/lines

### R13-C32

**core名字登记多余** — 成立 / engineering / scope=new / 不入分；统一问题：redundant-core-name-registration；credit=None。

[原文 L448–L448](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L448)

````text
- **`isExplicitCoreTool` 的名字放行是多余的**：`OperationAnalyzerRegistry` 中新增 `"HandoffRead".equals(name)`，唯一调用点是 `ToolRegistry:48` 的 `AUTHORIZATION_ANALYZER_MISSING` 启动断言，而真实 `HandoffReadTool` 永不进该构造器。副作用是任何 `com.aicodeassistant.*` 且名为 HandoffRead 的工具可绕过该启动断言（其 `analyzerFor` 仍返回 `generic`，**不获得权限**——所以不是安全漏洞）。建议删除。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：仅启动自检白名单，analyzer仍generic没有权限提升。规范396有core登记要求，属组织取舍。

判定理由：仅启动自检白名单，analyzer仍generic没有权限提升。规范396有core登记要求，属组织取舍。

代码：[OperationAnalyzerRegistry.java:120](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L120)；[session-merge-architecture-v2.md:396](../../../../session-merge-architecture-v2.md#L396)

核验：代码静态核验及原文：见code_refs/lines

### R13-C33

**字段注入风格与手动构造测试null** — 成立 / engineering / scope=new / 不入分；统一问题：formatting-and-injection；credit=None。

[原文 L449–L449](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L449)

````text
- **风格倒退**：`QueryController:72-73`、`WebSocketController:92-93`、`QueryEngine:1808-1809` 使用内联全限定类名 + `@Autowired` 字段注入，插在 `private final` 之间，破坏两个 Controller 既有的显式构造器注入风格；测试须靠 `ReflectionTestUtils.setField`（`QueryEngineUnitTest:1956`）；且 `new QueryController(...)` 的测试中该字段为 null，将来若出现合并会话用例会 NPE。`QueryEngine` 用 `required=false` 而 Controller 用必需注入的不一致虽不构成启动风险（Bean 是 `@Service`，上下文已验证可加载），仍建议统一为构造器参数。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：手动new控制器不注入字段时合并测试会失败；正式Spring有@Service且上下文通过。建议统一依赖注入不等于正常请求NPE。

判定理由：手动new控制器不注入字段时合并测试会失败；正式Spring有@Service且上下文通过。建议统一依赖注入不等于正常请求NPE。

代码：[HandoffContextService.java:14](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L14)；[QueryController.java:72](../../../../../backend/src/main/java/com/aicodeassistant/controller/QueryController.java#L72)

核验：代码静态核验及原文：见code_refs/lines

### R13-C34

**类级produces改变严格Accept内容协商** — 成立 / engineering / scope=new / 不入分；统一问题：merge-controller-produces；credit=None。

[原文 L450–L450](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L450)

````text
- `SessionMergeController:8` 类级 `@RequestMapping(produces="application/json")` 给**既有两个端点**加了内容协商约束：严格 `Accept: text/html`（不含 `*/*`）会 406。实测无回归（自家前端 `sessionMergeStore.ts:150-155` 带 `Accept: application/json` 或不带，MockMvc 用 `*/*`），但**零收益**，建议删除或只标在新端点上。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：Accept:text/html且不含通配符会406，自家客户端JSON不受影响；没有兼容合同要求此API接受HTML。

判定理由：Accept:text/html且不含通配符会406，自家客户端JSON不受影响；没有兼容合同要求此API接受HTML。

代码：[SessionMergeController.java:8](../../../../../backend/src/main/java/com/aicodeassistant/controller/SessionMergeController.java#L8)；[sessionMergeStore.ts:150](../../../../../frontend/src/store/sessionMergeStore.ts#L150)

核验：代码静态核验及原文：见code_refs/lines

### R13-C35

**旧helper死代码与未使用构造参数** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-helper-tests；credit=None。

[原文 L451–L451](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L451)

````text
- **死代码约 250 行**：`build()`(`:698`)、`packagePath()`(`:95`)、`MAX_RECORD_BYTES`(`:71`)、`exportRuns`、`renderBlock`、`PartWriter`、`writeParts`、`canonicalReference`、`Bundle`、`deleteUnreferenced` 的 scratchpad 分支，**仅被 `MergePackageServiceTest` 调用**（`:18/212/247/259/278/293/309/326/336`）。规格允许"仅为兼容已有夹具保留"，在线入口确实不再调用（已验证只走 seal / validateSnapshot / recoverBlockedProjections / snapshotPath / packageRoot），但把 250 行生产死代码推到 GitHub 是技术债。另：`SessionMergeService:67` 构造参数 `PermissionModeManager permissions` 已无字段接收，import 行 9 与 `java.time.Instant`（行 28）未使用。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：文档5明确允许兼容夹具保留；规模估算不作事实严重度，移除需先迁移测试。

判定理由：文档5明确允许兼容夹具保留；规模估算不作事实严重度，移除需先迁移测试。

代码：[MergePackageService.java:698](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L698)；[SessionMergeService.java:67](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L67)；[session-merge-architecture-v2.md:5](../../../../session-merge-architecture-v2.md#L5)

核验：代码静态核验及原文：见code_refs/lines

### R13-C36

**atomicWrite无fsync和finally掩异常** — 规范歧义 / conditional / scope=new / 不入分；统一问题：snapshot-fsync；credit=None。

[原文 L452–L452](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L452)

````text
- `MergeTextBudget.atomicWrite` 缺 `force(true)` 与父目录 fsync → 断电后 `work/recovered/seal.json` 可能丢失；`finally` 中 `deleteIfExists` 自身异常会掩盖原始异常。（其余方面正确：临时文件 `resolveSibling` 同目录同文件系统；走 `output()`→`reserve()` 接入既有磁盘防护；move 成功后 deleteIfExists 为 no-op、失败时清垃圾。）
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：force/fsync未实现，删除tmp失败可能遮原异常；语言flush已做到，尚无断电耐久性合同或实际复现。

判定理由：force/fsync未实现，删除tmp失败可能遮原异常；语言flush已做到，尚无断电耐久性合同或实际复现。

代码：[MergeTextBudget.java:34](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeTextBudget.java#L34)；[session-merge-architecture-v2.md:166](../../../../session-merge-architecture-v2.md#L166)

核验：代码静态核验及原文：见code_refs/lines

### R13-C37

**seal在rename前未全面复校** — 成立 / engineering / scope=new / 不入分；统一问题：preseal-self-validation；credit=None。

[原文 L453–L453](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L453)

````text
- `seal()` 在 rename 前未按 §4.3 步骤 5 复校 manifest/seal（依赖 `SessionMergeService:203`，而此时来源已释放）。建议在 staging 内先自检。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：writer计算清单哈希后原子rename，Service发布前再validate；规范166先写并校验措辞较强。额外自检可加，未证明用户资料在此阶段可被改变后发布。

判定理由：writer计算清单哈希后原子rename，Service发布前再validate；规范166先写并校验措辞较强。额外自检可加，未证明用户资料在此阶段可被改变后发布。

代码：[MergePackageService.java:159](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L159)；[MergePackageService.java:173](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L173)；[SessionMergeService.java:203](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L203)；[session-merge-architecture-v2.md:166](../../../../session-merge-architecture-v2.md#L166)

核验：代码静态核验及原文：见code_refs/lines

### R13-C38

**图片解码失败残留未登记文件** — 不成立 / engineering / scope=new / 不入分；统一问题：invalid-image-orphan-file-claim；credit=None。

[原文 L454–L454](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L454)

````text
- **残留未登记文件**：内嵌图片解码失败留下的 `assets/<uuid>`（`:521-527`）、空 `assets/files/` 目录 → 与 §4.2"目录展示由清单生成"矛盾，建议清理。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：Base64.decode先于打开assets文件流，IllegalArgumentException发生时根本未创建该path；IO失败会整体seal中断，下一次staging重做。空目录不进入清单不违反清单展示。

判定理由：Base64.decode先于打开assets文件流，IllegalArgumentException发生时根本未创建该path；IO失败会整体seal中断，下一次staging重做。空目录不进入清单不违反清单展示。

代码：[MergePackageService.java:526](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L526)；[MergePackageService.java:528](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L528)；[MergePackageService.java:529](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L529)；[MergePackageService.java:156](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L156)

核验：代码静态核验及原文：见code_refs/lines

### R13-C39

**handoffOrigin无写入点** — 成立 / engineering / scope=new / 不入分；统一问题：handoff-origin-compatibility；credit=None。

[原文 L455–L455](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L455)

````text
- `handoffOrigin` 主代码**无写入点**（`:499` 仅读取）；跨包身份实际靠 records.jsonl 逐行导入 + origin 确定性哈希达成，建议补注释或写入点。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：跨包导入records保留身份即可达成，不需额外写metadata；可注释兼容hook，非产品缺陷。

判定理由：跨包导入records保留身份即可达成，不需额外写metadata；可注释兼容hook，非产品缺陷。

代码：[MergePackageService.java:499](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L499)；[MergePackageService.java:630](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L630)

核验：代码静态核验及原文：见code_refs/lines

### R13-C40

**工具说明未包含全部语义引导** — 成立 / engineering / scope=new / 不入分；统一问题：handoff-tool-guidance；credit=None。

[原文 L456–L456](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L456)

````text
- `HandoffReadTool:16` 工具说明缺 §7.1 要求的三句（继续来源工作前先读缺失的交接详情；目录/未展开计数不代表已了解详情；历史测试不代表当前测试；改代码前先读现有文件），目前部分由结果 warning(`:302`) 与投影文本承担。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：固定工具description较短，部分提示由projection/warning承担；规格373同时要求两处，应统一但语义成效待验收。

判定理由：固定工具description较短，部分提示由projection/warning承担；规格373同时要求两处，应统一但语义成效待验收。

代码：[HandoffReadTool.java:16](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L16)；[HandoffContextService.java:68](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L68)；[session-merge-architecture-v2.md:373](../../../../session-merge-architecture-v2.md#L373)

核验：代码静态核验及原文：见code_refs/lines

### R13-C41

**ImageIO null stream异常未捕获** — 证据不足 / conditional / scope=new / 不入分；统一问题：imageio-null-stream；credit=None。

[原文 L457–L457](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L457)

````text
- `HandoffReadTool:35-37`：`ImageIO.createImageInputStream` 返回 null 时 `getImageReaders(null)` 抛 `IllegalArgumentException`，**不被 `catch(IOException)` 捕获**。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：静态API行为成立，但asset先校验普通文件，未给出受支持稳定触发，需外部竞态/平台失败。

判定理由：静态API行为成立，但asset先校验普通文件，未给出受支持稳定触发，需外部竞态/平台失败。

代码：[HandoffReadTool.java:35](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L35)；[HandoffReadService.java:195](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L195)

核验：代码静态核验及原文：见code_refs/lines

### R13-C42

**字符串limit退回5** — 不成立 / engineering / scope=new / 不入分；统一问题：string-limit-input；credit=None。

[原文 L458–L458](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L458)

````text
- `HandoffReadService:224`：`limit` 仅在为 `Number` 时生效，字符串 `"10"` 静默降级为 5。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：工具JSON schema要求integer，字符串10不符合输入契约；fallback不是支持输入错误。

判定理由：工具JSON schema要求integer，字符串10不符合输入契约；fallback不是支持输入错误。

代码：[HandoffReadTool.java:23](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L23)；[HandoffReadService.java:223](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L223)

核验：代码静态核验及原文：见code_refs/lines

### R13-C43

**path substring缺startsWith** — 不成立 / engineering / scope=new / 不入分；统一问题：unguarded-snapshot-substring；credit=None。

[原文 L459–L459](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L459)

````text
- `HandoffReadService:270`：`e.path().substring("snapshot/".length())` 无 `startsWith` 守卫。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：当前HandoffReadService270是carry.substring，无此path substring；对应MergePackageService281只消费本地SnapshotWriter生成的snapshot前缀路径。未给用户可控触发。

判定理由：当前HandoffReadService270是carry.substring，无此path substring；对应MergePackageService281只消费本地SnapshotWriter生成的snapshot前缀路径。未给用户可控触发。

代码：[HandoffReadService.java:270](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L270)；[MergePackageService.java:281](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L281)

核验：代码静态核验及原文：见code_refs/lines

### R13-C44

**promptVersion常量重复** — 成立 / engineering / scope=new / 不入分；统一问题：prompt-version-drift；credit=None。

[原文 L460–L460](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L460)

````text
- `SessionMergeService:106` promptVersion 硬编码 `"handoff-v2-1"`，与 `PROCESSOR_VERSION` 同值重复，提示词升级易漏改，建议提为常量。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：重复字面量且prepare检查processor，未来升级易遗漏，但当前无版本不一致。

判定理由：重复字面量且prepare检查processor，未来升级易遗漏，但当前无版本不一致。

代码：[SessionMergeService.java:106](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L106)；[MergeSummaryService.java:65](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L65)

核验：代码静态核验及原文：见code_refs/lines

### R13-C45

**cleanup候选DB异常可传播到启动** — 成立 / conditional / scope=new / 不入分；统一问题：cleanup-db-startup-exception；credit=None。

[原文 L461–L461](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L461)

````text
- `SessionMergeService:272` `progress.cleanupCandidates()` 在 try **之外**：DB 不可读时会从 `execute()` 的 finally（可能掩盖原异常）与 `recover()`（`@PostConstruct`，**可阻断启动**）抛出，与 §4.4"数据库不可读则保留"意图不符。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：cleanupCandidates在for迭代求值/try之外，失败可传播@PostConstruct；数据库本来是启动依赖，需注入仅合并查询失败证明增量影响。规范要求保留文件已满足未删除，但应避免阻断启动。

判定理由：cleanupCandidates在for迭代求值/try之外，失败可传播@PostConstruct；数据库本来是启动依赖，需注入仅合并查询失败证明增量影响。规范要求保留文件已满足未删除，但应避免阻断启动。

代码：[SessionMergeService.java:272](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L272)；[SessionMergeService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L74)；[session-merge-architecture-v2.md:187](../../../../session-merge-architecture-v2.md#L187)

核验：代码静态核验及原文：见code_refs/lines

### R13-C46

**shutdown未等待writer退出** — 成立 / conditional / scope=new / 不入分；统一问题：merge-shutdown-writer-lifecycle；credit=None。

[原文 L462–L462](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L462)

````text
- `SessionMergeService:287` `worker.shutdown()` 未 awaitTermination → `@PreDestroy` 返回后 writer 仍可能写文件/查库（DataSource 或已关闭）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：worker.shutdown非awaitTermination，PreDestroy返回仍可能有worker；abort/check减小窗口，未证关闭顺序导致数据损坏。

判定理由：worker.shutdown非awaitTermination，PreDestroy返回仍可能有worker；abort/check减小窗口，未证关闭顺序导致数据损坏。

代码：[SessionMergeService.java:281](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L281)；[SessionMergeService.java:287](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L287)

核验：代码静态核验及原文：见code_refs/lines

### R13-C47

**规划每片独立事务** — 证据不足 / conditional / scope=new / 不入分；统一问题：planning-transaction-overhead；credit=None。

[原文 L463–L463](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L463)

````text
- 规划阶段每分片一次写事务（`MergeSummaryService:79` → `Repo.plan`）→ 大历史下上千次 SQLite 写事务，建议批量。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：plan每单元TransactionTemplate，量大可能增加SQLite开销；未量化普通链路阻塞，列优化建议。

判定理由：plan每单元TransactionTemplate，量大可能增加SQLite开销；未量化普通链路阻塞，列优化建议。

代码：[MergeSummaryService.java:79](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L79)；[MergeProgressRepository.java:136](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L136)

核验：代码静态核验及原文：见code_refs/lines

### R13-C48

**服务关闭码与SQLite busy协议** — 证据不足 / conditional / scope=new / 不入分；统一问题：shutdown-and-db-busy-errors；credit=None。

[原文 L464–L464](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L464)

````text
- `MERGE_SERVICE_SHUTDOWN`（`SessionMergeService:82`）不在规格列举的错误码内，需确认前端按通用提示降级；cancel 与发布事务相撞时 SQLite busy 可能抛 500，可考虑一次重试。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：MERGE_SERVICE_SHUTDOWN走Conflict且前端有通用message；SQLite busy可能性缺具体同进程调度/锁证据。

判定理由：MERGE_SERVICE_SHUTDOWN走Conflict且前端有通用message；SQLite busy可能性缺具体同进程调度/锁证据。

代码：[SessionMergeService.java:82](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L82)；[SessionMergeService.java:207](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L207)；[sessionMergeStore.ts:177](../../../../../frontend/src/store/sessionMergeStore.ts#L177)

核验：代码静态核验及原文：见code_refs/lines

### R13-C49

**所有错误paused长期占active** — 规范歧义 / engineering / scope=new / 不入分；统一问题：unrecoverable-paused-state；credit=None。

[原文 L465–L465](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L465)

````text
- v2 无任何路径写 `status='failed'`（哈希不符也只 pause）→ 损坏包会**长期占用唯一名额**直到用户 cancel。符合规格（§5.1"failed：已确认不可恢复的损坏"），但建议在 `explain` 文案中明确"确认不可恢复请取消"。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：不可恢复损坏也暂停，用户可cancel释放slot；规范把failed分开但本轮明确允许损坏暂停且保留原件，无自动判不可恢复标准。宜改善说明。

判定理由：不可恢复损坏也暂停，用户可cancel释放slot；规范把failed分开但本轮明确允许损坏暂停且保留原件，无自动判不可恢复标准。宜改善说明。

代码：[SessionMergeService.java:226](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L226)；[session-merge-architecture-v2.md:25](../../../../session-merge-architecture-v2.md#L25)；[session-merge-architecture-v2.md:210](../../../../session-merge-architecture-v2.md#L210)

核验：代码静态核验及原文：见code_refs/lines

### R13-C50

**前端固定轮询无退避** — 成立 / conditional / scope=new / 不入分；统一问题：merge-polling-backoff；credit=None。

[原文 L469–L469](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L469)

````text
- **轮询无退避**：固定 2s（`panel:47`），`document.hidden` 时仍空转；无任务时每 5s 打 `/active`；`failed && canCancel` **永不进** `validatedTerminal`（`store:187-189`）→ 直到用户取消前一直 2s 轮询。建议 hidden 暂停 + 错误/终态退避。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：panel2秒、无pending5秒active，hidden不暂停；资源成本事实，不证明故障。

判定理由：panel2秒、无pending5秒active，hidden不暂停；资源成本事实，不证明故障。

代码：[SessionMergePanel.tsx:47](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L47)；[sessionMergeStore.ts:145](../../../../../frontend/src/store/sessionMergeStore.ts#L145)

核验：代码静态核验及原文：见code_refs/lines

### R13-C51

**validatedTerminal集合只增不减** — 成立 / conditional / scope=new / 不入分；统一问题：terminal-cache-memory-growth；credit=None。

[原文 L470–L470](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L470)

````text
- `store.ts:113` `validatedTerminal` 模块级 Set 只增不减（仅 openDialog 删当前 id），轻微泄漏。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：dismiss未删Set，openDialog才删当前id；每完成操作留一个小id，有限会话规模下未证用户内存问题。

判定理由：dismiss未删Set，openDialog才删当前id；每完成操作留一个小id，有限会话规模下未证用户内存问题。

代码：[sessionMergeStore.ts:115](../../../../../frontend/src/store/sessionMergeStore.ts#L115)；[sessionMergeStore.ts:189](../../../../../frontend/src/store/sessionMergeStore.ts#L189)；[sessionMergeStore.ts:218](../../../../../frontend/src/store/sessionMergeStore.ts#L218)

核验：代码静态核验及原文：见code_refs/lines

### R13-C52

**errorCode不用专用分支** — 成立 / engineering / scope=new / 不入分；统一问题：frontend-error-code-guidance；credit=None。

[原文 L471–L471](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L471)

````text
- `errorCode` 已入类型但**从未使用**；建议对 `MERGE_CANCEL_CLEANUP`（稍后重试）、`MERGE_STALE_OPERATION`（自动刷新）做 code 分支——目前 6 个 code 中只有 `MERGE_OPERATION_NOT_FOUND` 有分支（`:161`）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：部分code有通用消息且control409采用operation，协议功能可工作，专用提示属于UX完善。

判定理由：部分code有通用消息且control409采用operation，协议功能可工作，专用提示属于UX完善。

代码：[sessionMergeStore.ts:177](../../../../../frontend/src/store/sessionMergeStore.ts#L177)；[sessionMergeStore.ts:243](../../../../../frontend/src/store/sessionMergeStore.ts#L243)

核验：代码静态核验及原文：见code_refs/lines

### R13-C53

**控制响应HTML导致原始JSON错误文案** — 成立 / conditional / scope=new / 不入分；统一问题：control-nonjson-error-message；credit=None。

[原文 L472–L472](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L472)

````text
- `store.ts:241` `await response.json()` 无 `.catch`（对比 `:159` 有）→ 网关返回 HTML 时把 `Unexpected token <…` 当错误文案直接展示给用户。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：response.json未保护可展示SyntaxError；网关畸形响应是条件性UX，不是当前服务会返回HTML的正常路径。

判定理由：response.json未保护可展示SyntaxError；网关畸形响应是条件性UX，不是当前服务会返回HTML的正常路径。

代码：[sessionMergeStore.ts:241](../../../../../frontend/src/store/sessionMergeStore.ts#L241)；[sessionMergeStore.ts:250](../../../../../frontend/src/store/sessionMergeStore.ts#L250)

核验：代码静态核验及原文：见code_refs/lines

### R13-C54

**v2 warnings不再含缺口明细** — 成立 / functional / scope=new / 入分；统一问题：D19；credit=1。

[原文 L473–L473](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L473)

````text
- `panel:206` 直接渲染 ISO 时间戳；`panel:210-211` 的 `result.warnings` 在 v2 后端**已不产出**（`SessionMergeService:214` 只写 copiedCount/warningCount/model/title/indexPath）→ "未收录 N 项"永远无明细，建议改为指向 gaps 或移除该分支。
````

触发：完成合并含缺失附件/未解析说明
预期：用户可从结果定位缺口原因
实际：仅显示总数，原warnings明细分支无数据

判定理由：Service result仅copiedCount/warningCount，Panel warnings永远为空；gaps存包但UI没有入口，用户只见未收录总数。和计数混合问题相关但根因是完成DTO不提供明细。

核实范围：操作warnings仅总数，合并面板无法展示缺口明细；包中原记录仍在。

代码：[SessionMergeService.java:214](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L214)；[SessionMergePanel.tsx:210](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L210)；[HandoffReadService.java:171](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L171)

核验：代码静态核验：见code_refs

### R13-C55

**retryAt直接ISO显示** — 成立 / engineering / scope=new / 不入分；统一问题：retry-time-format；credit=None。

[原文 L473–L473](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L473)

````text
- `panel:206` 直接渲染 ISO 时间戳；`panel:210-211` 的 `result.warnings` 在 v2 后端**已不产出**（`SessionMergeService:214` 只写 copiedCount/warningCount/model/title/indexPath）→ "未收录 N 项"永远无明细，建议改为指向 gaps 或移除该分支。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：纯呈现建议，不影响后端退避控制。

判定理由：纯呈现建议，不影响后端退避控制。

代码：[SessionMergePanel.tsx:206](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L206)

核验：代码静态核验及原文：见code_refs/lines

### R13-C56

**v1客户端无显式只读fallback** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-ui-protocol-defense；credit=None。

[原文 L474–L474](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L474)

````text
- 前端**从不读取 `protocolVersion`**，缺 v1 只读兜底与"旧版结果"文案（§8 要求 protocol=1 只读展示、不伪造 v2 绑定/恢复能力）。当前靠服务端 `canResume/canCancel/targetAvailable` 驱动（后端 v1 时 canCancel=false）间接达成，但没有显式兜底。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：后端v1 canResume/canCancel为false已保证真实UI只读；无protocolVersion分支不是现实恢复越权。

判定理由：后端v1 canResume/canCancel为false已保证真实UI只读；无protocolVersion分支不是现实恢复越权。

代码：[SessionMergeService.java:267](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L267)；[SessionMergePanel.tsx:217](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L217)

核验：代码静态核验及原文：见code_refs/lines

### R13-C57

**404和冲突采用未dispatch列表更新** — 证据不足 / conditional / scope=new / 不入分；统一问题：conflict-lock-list-refresh；credit=None。

[原文 L475–L475](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L475)

````text
- 锁集合变化触发 `session-list-updated` **只在主轮询路径**（`:196-199`），冲突采用（`:171-176`）与 404（`:163-169`）路径缺失。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：这两处确无window event，但Zustand pending变化直接驱动isMergeSource消费者；未证明除了列表刷新外仍有实际错误锁状态。

判定理由：这两处确无window event，但Zustand pending变化直接驱动isMergeSource消费者；未证明除了列表刷新外仍有实际错误锁状态。

代码：[sessionMergeStore.ts:163](../../../../../frontend/src/store/sessionMergeStore.ts#L163)；[sessionMergeStore.ts:171](../../../../../frontend/src/store/sessionMergeStore.ts#L171)；[sessionMergeStore.ts:262](../../../../../frontend/src/store/sessionMergeStore.ts#L262)

核验：代码静态核验及原文：见code_refs/lines

### R13-C58

**v1文档归档与文件权限说明** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-document-hygiene；credit=None。

[原文 L480–L481](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L480)

````text
- `docs/zhikun/` 下有 **7 份 09-20 的 v1 时代 Session-Merge 文档**（`Session-Merge-Five-Source-Acceptance`、`Session-Merge-Live-Acceptance`、`Session-Merge-Full-Acceptance`、`Session-Merge-and-Inject-Review`、`Session-Merge-and-Inject-Simplified-Review`、`Session-Merge-and-Inject-Implementation-Plan`、`Session-Merge-MVP-Implementation-Notes`），将与声称"本文替代此前更复杂的 v2 草案"的 v2 文档一起发布到 GitHub。建议标注 superseded 或移入归档目录。
- `docs/session-merge-architecture-v2.md` 权限是 `600`（`-rw-------`），`README_EN.md` 同样。git 只跟踪可执行位，提交后会变 644，**不构成问题**，仅提示。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：归档是文档卫生，Git不追踪600/644差异只有可执行位的说明正确；不影响功能评估。

判定理由：归档是文档卫生，Git不追踪600/644差异只有可执行位的说明正确；不影响功能评估。

代码：[session-merge-architecture-v2.md:3](../../../../session-merge-architecture-v2.md#L3)

核验：代码静态核验及原文：见code_refs/lines

### R13-C59

**具体保证复制symlink不可利用** — 不成立 / functional / scope=new / 入分；统一问题：D03；credit=0。

[原文 L512–L512](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L512)

````text
| symlink | **不可被利用** | `safeFile` 逐级查链接；`hash()`(`:105`) 与 `catalog()`(`:138`) 均 `NOFOLLOW_LINKS`；附件复制**不用** `Files.copy`，而是 `isRegularFile(NOFOLLOW)` + `newInputStream(NOFOLLOW)`(`:1058-1064`)；`copyTree` 对范围外链接只记 `external_reference`(`:1035-1041`)；legacy 包链接记阻断缺口(`:614`)。测试把已登记文件换成指向包外 secret 的 symlink，断言拒绝 |
````

触发：scratchpad中间链接且历史绝对引用指向其下外部文件
预期：拒范围外复制
实际：当前会沿中间软链读取并归档

判定理由：safeFile读侧保护不能覆盖source458-462回补路径；NOFOLLOW仅最后段，中间软链历史引用可把包外文件复制入独立包。此为具体安全误保证，不是整体无P1判断。

代码：[MergePackageService.java:458](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L458)；[MergePackageService.java:975](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L975)；[MergePackageService.java:984](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L984)

核验：代码静态核验及原文：见code_refs/lines

### R13-C60

**读ref路径穿越边界保证** — 成立 / global / scope=new / 不入分；统一问题：read-side-resource-boundary；credit=None。

[原文 L511–L511](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L511)

````text
| 路径穿越 | **不可被利用**（写侧存 1 处纵深缺口，见 I4） | 读侧 `ref` 仅作清单查找键、**从不拼路径**（`HandoffReadService:161-180` 全量扫 catalog 匹配后取清单内 `path`）；`safeFile`（`MergePackageService:304-316`）拒绝对路径 / `normalize().startsWith("..")` / NUL（`Path.of` 抛 InvalidPathException 被捕获）/ 逐级 `isSymbolicLink` / 非普通文件；写侧文件名仅 `r_<sha256>`(`:369`)、`a_<sha256>`(`:580`)、`UUID`(`:521`)、`:pN`(`:686`)，**无标题或模型输出参与**。测试 `HandoffReadServiceTest:66-73` 实测 `../../secret`、`/etc/passwd` 被拒 |
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：ref只找清单，safeFile校验包内路径；声明写侧另有containment加固项。该读取断言本身成立，不能与symlink误保证重复扣。

判定理由：ref只找清单，safeFile校验包内路径；声明写侧另有containment加固项。该读取断言本身成立，不能与symlink误保证重复扣。

代码：[HandoffReadService.java:163](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L163)；[MergePackageService.java:304](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L304)

核验：代码静态核验及原文：见code_refs/lines

### R13-C61

**游标绑定/局部工具/可信根/图片窄权限保证** — 成立 / global / scope=new / 不入分；统一问题：scoped-handoff-authorization；credit=None。

[原文 L513–L519](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L513)

````text
| 游标伪造 / 跨包越权 | **不可被利用** | key = `sha256(operationId + handoffHash + encode(action,query,ref,sourceId,section))`(`:213`)，不符即 `HANDOFF_INVALID_CURSOR`(`:222`)；`catalog:129-137` 校验索引范围、偏移前一字节必须 `\n`、不得超文件大小；游标不含路径，偏移只作用于 `binding()` 已解析的本包路径 |
| 全局工具泄漏 | **不可被利用** | `HandoffReadTool` 无 `@Component`/`@Service`；全仓唯一实例化为 `new`（`HandoffContextService:31`）；`ToolRegistry:44-57` 仅注册 Spring 注入的 `List<Tool>`，无类路径扫描；仅 `configure()` 在 metadata 含 `sessionMergeOperationId` 且过 allow/deny 时追加 |
| 绑定校验 | **已符合** | `subjects.resolve(currentRunId).rootSessionId()`(`Tool:29`)；`binding()` SQL 要求 `protocol_version=2 AND status='completed' AND target_session_id=?` JOIN sessions；读取前 `verify()` 按 `handoff_hash` 校 `ready.json`(`:69-73`)，模型无法选择 session/operation |

**`ImageRefInjector` 的绕过是窄范围且安全的**（协调者重点复核项）：`HandoffReadService.isBoundAsset:182-194` 五项齐备——① rootSessionId 来自 `subjects.resolve(context.currentRunId())`（`HandoffContextService:63-66`，**非模型输入**）；② 必须命中清单 `kind=="asset"` 条目；③ `e.sha256().equals(hash)` **且** `hash(safeFile(...)).equals(hash)` 实盘重算；④ 路径用**清单内的 `e.path()`** 解析后 `equals(requested)`，`../` 无法构造成登记路径，`safeFile` 拒绝绝对路径、`..` 逃逸、逐段 symlink、非常规文件；⑤ 绕过的**仅是** `ImageRefInjector:269` 的路径检查，后续 `:275` isRegularFile、`:281-291` 大小 + `fileSize` 一致、`:298` SHA-256 重算、`:305+` MIME/base64、调用方 `budgetLeft`/`dataLen` 硬限**全部照常执行**。符合 §10"仅为当前可信根会话绑定包中已登记且哈希匹配的图片增加读取入口；普通路径权限不变"。

**`AuthorizationService` 那一行改动**（协调者复核）：括号正确、`file-v1` 判定与改动前完全等价；`safeRead`(`:158`) 位于 `PLAN` 拒绝(`:166`)**之前** → 含 PLAN 模式 ✓（符合 §7.3）；`SAFE_INTERNAL`(`OperationAnalyzerRegistry:42-44`) 未含 handoff ✓（符合 §7.3 明令）。`analyzerFor:87` 先判 `isMcp()` 再判 `instanceof HandoffReadTool`，同名 MCP/动态工具只会拿到 `mcp`/`generic` 分析器，`analyzerId` 不等于 `handoff-read-v1` → 不进 `safeRead`，符合"不允许同名 MCP/动态工具继承权限"。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：真实tool实例+trustedRun+绑定hash，游标不授权任意包，图片必须为当前包登记hash路径；普通文件权限保留。源复制漏洞单独计，不把所有安全路径都判错。

判定理由：真实tool实例+trustedRun+绑定hash，游标不授权任意包，图片必须为当前包登记hash路径；普通文件权限保留。源复制漏洞单独计，不把所有安全路径都判错。

代码：[HandoffReadService.java:210](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L210)；[HandoffReadService.java:182](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L182)；[HandoffContextService.java:39](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L39)；[OperationAnalyzerRegistry.java:87](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L87)

核验：代码静态核验及原文：见code_refs/lines

### R13-C62

**普通附件复制配额分类安全保证** — 成立 / global / scope=new / 不入分；统一问题：file-copy-quota-classification；credit=None。

[原文 L528–L528](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L528)

````text
- **托管产物**：`:441-450` 按 manifest_id/artifact_id 键集分页，`exportRow` 用 `PRAGMA table_info` 全列导出，**无验证调用**；`copyFile:1067-1071` 前后校验 size/mtime/双哈希（最多重读一次）；`CopyLimitException` → `copy_failed` → `exportAsset` 抛 `MERGE_COPY_INCOMPLETE`(`:576`)，**不降级为 external_reference**（符合 §9"旧 CopyLimit 测试不能继续要求托管原件复制失败后带警告发布"）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：copyFile将CopyLimitException变copy_failed，exportAsset转MERGE_COPY_INCOMPLETE；仅此路径正确，不能扩张至内嵌image render。

判定理由：copyFile将CopyLimitException变copy_failed，exportAsset转MERGE_COPY_INCOMPLETE；仅此路径正确，不能扩张至内嵌image render。

代码：[MergePackageService.java:970](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L970)；[MergePackageService.java:995](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L995)；[MergePackageService.java:576](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L576)

核验：代码静态核验及原文：见code_refs/lines

### R13-C63

**串行测试证明旧GET不覆盖epoch** — 成立 / engineering / scope=new / 不入分；统一问题：polling-test-order-coverage；credit=None。

[原文 L555–L555](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L555)

````text
- `inflight` 模块级串行化生效（测试 `store.test:487` 验证旧 GET 不覆盖 epoch=8）。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：测试先control后refresh，GET不会发送，证明互斥防旧结果；未覆盖已有GET时control的反序情况，不能夸大为所有交错验收。

判定理由：测试先control后refresh，GET不会发送，证明互斥防旧结果；未覆盖已有GET时control的反序情况，不能夸大为所有交错验收。

代码：[sessionMergeStore.test.ts:487](../../../../../frontend/src/store/sessionMergeStore.test.ts#L487)；[sessionMergeStore.ts:138](../../../../../frontend/src/store/sessionMergeStore.ts#L138)；[sessionMergeStore.ts:227](../../../../../frontend/src/store/sessionMergeStore.ts#L227)

核验：代码静态核验及原文：见code_refs/lines

### R13-C64

**通知已按key去重保证** — 不成立 / global / scope=retained / 不入分；统一问题：D22；credit=None。

[原文 L559–L559](../zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md#L559)

````text
- 既有保护全部保留：多 tab(`:254-274`)、幂等键(`:150` + 每 key 独立记录 `:76-79`)、localStorage 写失败容错(`:91-100`)、404 恢复(`:161-169`)、通知去重(`removeNotification('merge-storage')` + `changed &&` 门 `:199`)、不抢焦点(`panel:86-88`，测试 `panel.test:45`)。
````

触发：报告所述场景
预期：按当前规格与支持范围；工程建议不计产品缺陷
实际：merge-storage确先remove；merge-id通知只changed门控，notificationStore.add只是push，同operation不同paused/error状态可留重复key。根因b98已有，不计本次功能FP。

判定理由：merge-storage确先remove；merge-id通知只changed门控，notificationStore.add只是push，同operation不同paused/error状态可留重复key。根因b98已有，不计本次功能FP。

代码：[sessionMergeStore.ts:200](../../../../../frontend/src/store/sessionMergeStore.ts#L200)；[notificationStore.ts:32](../../../../../frontend/src/store/notificationStore.ts#L32)

核验：代码静态核验及原文：见code_refs/lines

## R14

[完整原报告](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md)

### R14-normal_function

**判断分：5/5**。[原文 L18–L18](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L18)。普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。

````text
**结论：无实际风险，未发现任何破坏。** 本次改动是强隔离型设计：普通会话在入口、预算、工具池、图片注入、授权五个层面均走基线分支，并有针对性单测（含"零交接调用"证明）与 HEAD 基线对照实验佐证（详见第三节）。
````

### R14-boundary

**判断分：5/5**。[原文 L285–L288](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L285)。全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。

````text
1. 按用户要求，12 类前端文件未纳入审查（其与合并 v2 的相互关系仅做"无调用关系"的粗判）；
2. 语义验收（真实模型接续开发样本）未执行，与本规格文档自身声明的剩余工作一致；
3. 审查基于未提交工作区快照（HEAD=b98e187 + 49 项改动），若后续代码变动需重新验证；
4. 本报告不承诺软件在任何环境绝对零风险。
````

### R14-release

**判断分：5/5**。[原文 L22–L22](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L22)。明确区分阶段提交与功能完成版合入发布。

````text
**结论：代码质量与工程完备度整体达到"可安全提交"水平（0 Blocker、全量测试实证通过、无数据完整性/安全漏洞），但距"放心合并主干/对外发布"还差 4 条 Major 级问题的处理**——其中 2 条是后端功能缺陷（用户可能永久卡死、恢复协议部分不可达），2 条是前端规格强制验收项的测试缺口。规格文档自身亦声明"真实模型接续开发语义验收尚未执行"，该部分不能由本次 stub 测试替代。
````

### R14-semantic

**判断分：5/5**。[原文 L22–L32](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L22)。明确真实模型语义验收未完成，stub 测试不能替代。

````text
**结论：代码质量与工程完备度整体达到"可安全提交"水平（0 Blocker、全量测试实证通过、无数据完整性/安全漏洞），但距"放心合并主干/对外发布"还差 4 条 Major 级问题的处理**——其中 2 条是后端功能缺陷（用户可能永久卡死、恢复协议部分不可达），2 条是前端规格强制验收项的测试缺口。规格文档自身亦声明"真实模型接续开发语义验收尚未执行"，该部分不能由本次 stub 测试替代。

| 维度 | 判定 | 说明 |
|---|---|---|
| 非合并链路安全性 | ✅ 通过 | 隔离点逐 hunk 核对 + 基线对照实验，无回归 |
| 编译 / 全量测试 / 构建 | ✅ 通过 | 干净环境下后端 2,975 项 0 失败、前端 103 文件全过、生产构建成功（独立实测复现参考值） |
| 数据安全 / 事务 / 安全防护 | ✅ 通过 | 无 SQL 注入、无事务边界违规、路径穿越/symlink 防护有效、无锁泄漏、无数据丢失路径 |
| 数据库迁移（V026） | ✅ 通过 | 与规格附录 A SQL 逐字一致、事务化执行、旧行逐列保留、V024 checksum 未动 |
| 规格符合性 | 🟡 高但非完全 | 核心流程全面符合；2 条 Major 涉及状态机语义与恢复协议偏差 |
| 测试覆盖 | 🟡 接近 | 后端扎实（断言具体、无放水）；前端有 2 个规格"必须证明"项零覆盖 |
| 功能语义验收 | ⛔ 未执行 | 文档明示：真实模型接续开发语义样本评测尚未运行，不可用 stub 结果替代 |
````

### R14-C01

**普通链路门控与授权隔离** — 成立 / global / scope=new / 不入分；统一问题：ordinary-path-isolation；credit=None。

[原文 L72–L79](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L72)

````text
| 1 | **QueryEngine 普通路径** | `QueryEngine.java:841-842 / 1813-1814`：`state.getHandoffOperationId()==null` 时直接返回空投影，`historyBudget==inputBudget`，零 DB/交接服务调用；`QueryEngineUnitTest.java:1950-1984` 参数化用例断言 merged=false 分支 payload 不含 "HandoffRead"、`verifyNoInteractions(handoff)`、`phase2-phase1==0`（不双扣） |
| 2 | **投影注入对空集无损** | `HandoffContextService.java:84-85`：空投影时 `inject()` 返回**原列表对象**，`CompactionHistory.forRequest/normalizeTyped` 输入与基线同一引用；插入点在压缩前、不落入 tool_use/result 配对内部 |
| 3 | **工具注册隔离** | `HandoffReadTool` 非 Spring Bean（`HandoffContextServiceTest.java:28` 显式断言无 `@Component`），唯一实例化于 `HandoffContextService.java:31`；`ToolRegistry.java:43-50` 只注册注入的 Tool bean → 普通主会话/子代理/Swarm 的工具 schema 与 token 开销完全不变 |
| 4 | **控制器入口零额外开销** | `QueryController.java:178-179 / 265-266 / 362-363` 仅复用已加载的 `session.config()`（多一次 Map.get）；`WebSocketController.java:925-934` 将 `ifPresent` 改为显式取值，`loadSession` 次数不变，仅 E 才调 configure（`:958-960`）；PROMPT 工具限制保留（`:878`） |
| 5 | **ImageRefInjector 权限不倒退** | 普通路径走旧 7 参重载，谓词固定 false（`ImageRefInjector.java:93-98`），注入行为与基线逐字节等价；新放行仅当 `checkResult.isAllowed()==false` 时咨询 `canReadAsset`（`:266-269`），要求：可信根会话 + 包内登记 + kind=asset + sha256 相等 + 归一化绝对路径等于包内路径 |
| 6 | **授权最小且不可继承** | `AuthorizationService.java:157-158` 仅在既有 safeRead 条件（`risk==SAFE && effects==[READ_RESOURCE]`）叠加 `handoff-read-v1`，位于 PLAN 检查之前（PLAN 可用）；未入 SAFE_INTERNAL；`OperationAnalyzerRegistry.java:87` 的 `instanceof HandoffReadTool` 判定在 `isMcp()` 之后 → 同名 MCP/动态工具仍走原分析器（`OperationAnalyzerRegistryTest.java:65-68` 验证） |
| 7 | **启动/删除路径** | `SessionMergeService.recover()` 启动时仅一次小查询 + 清理候选；`SessionManager` 完全未改动（git status 证实），普通删除不触发合并查库/磁盘清理 |
| 8 | **其他入口不设标记** | `SwarmWorkerRunner.java:198`、`SubAgentExecutor.java:411`、`AgentResumeService.java:108` 不设置运行标记，行为保持基线（此三文件未被本次改动触碰） |
````

触发：普通链路门控与授权隔离
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：关键热路径门控、原工具池、默认图片策略均可支持功能无新增回归。

判定理由：关键热路径门控、原工具池、默认图片策略均可支持功能无新增回归。

代码：[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)；[HandoffContextService.java:33](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L33)

核验：代码静态核验及原文：见code_refs/lines

### R14-C02

**忽略UI文件与合并无调用关系** — 不成立 / conditional / scope=new / 不入分；统一问题：excluded-ui-no-merge-dependency；credit=None。

[原文 L82–L82](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L82)

````text
**附加说明**：合并核心服务（`SessionMergeService` 等）全仓 grep 确认只被合并链路（`SessionMergeController`、`HandoffReadService`/`HandoffContextService`）引用，未挂入普通 SessionManager/SubAgent/Swarm 路径。被您指定忽略的 12 类前端文件与合并功能无调用关系，但属于**另一批独立改动混在同一工作区**——提交时建议与合并 v2 拆分 commit/PR，避免污染历史。
````

触发：忽略UI文件与合并无调用关系
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：Sidebar/App实际消费合并selector；报告附录承认粗判。属于变更集范围陈述失准，不等于合并功能产品误报，不算功能FP。

判定理由：Sidebar/App实际消费合并selector；报告附录承认粗判。属于变更集范围陈述失准，不等于合并功能产品误报，不算功能FP。

代码：[sessionMergeStore.ts:262](../../../../../frontend/src/store/sessionMergeStore.ts#L262)；[Sidebar.tsx:2](../../../../../frontend/src/components/layout/Sidebar.tsx#L2)

核验：代码静态核验及原文：见code_refs/lines

### R14-C03

**全部不可恢复失败仍paused与恢复文案** — 规范歧义 / engineering / scope=new / 不入分；统一问题：paused-without-failed-state；credit=None。

[原文 L128–L140](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L128)

````text
### 🔴 Major-1【后端·功能】不可恢复的阻断项被记为可恢复 `paused`，resume 永远空转（无 `failed` 状态使用，explain 文案误导）

**证据**（coordinator 已亲自抽验）：
- `backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java:194-198`：`snapshot.blockedReason()` 非 `RECORD_REQUIRES_HANDLING` 前缀时直接 `throw new IOException(blockedReason)` → 落入 `catch` 的 `progress.pause(...)`（`:258-263` 附近）；
- 而以下阻断均以 `blocking=true` 写入**不可变 manifest**：legacy 旧包目录缺失（`MergePackageService.java:608`）、旧包内 symlink（`:616`）、`invalid_image`（`:531`）、附件文本解码失败（`:598`）、checkpoint 类（`:565`）——每次 resume 都在同一处再次失败；
- 全仓 grep 证实：v2 运行期**从不写 `failed` 状态**（`status='failed'` 仅出现在 V026 迁移 SQL 的旧行转换中）；`canResume` 对 paused 恒为 true（`SessionMergeService.java:267`），`explain()`（`:237-247`）对未识别 code 输出"请排查后恢复"，但 legacy 包缺失/单文件 symlink/附件编码错误**不存在任何"修好后恢复"的路径**。

**影响**：用户被卡死在 paused——无法完成、无法通过 resume 前进（每次还付出全量快照哈希校验、可能 GB 级与派生投影重算成本）、文案误导，只能 cancel；cancel 后以同一 legacy 来源重发合并，会在同一位置再次暂停，形成循环。**与规格 §5.1"failed：已确认不可恢复的损坏；不能 resume，由用户 cancel 结束"的状态机定义不符**；与 §1"重复合并不依赖旧包存活"的意图有张力。

**建议修法**（任选，建议 a+b）：
- a. 区分可恢复性：将"旧包缺失/单文件 symlink 未收录/非关键附件不可解析"降级为**非阻断 gap**（记录"未收录范围"），仅真正影响关键资料完整性的才阻断；或给 Operation 增加 `recoverable` 标志，前端对不可恢复项渲染"只能取消重建"；
- b. 对确定性不可恢复路径（`legacy_package_missing`、`legacy_symlink_not_copied` 等）pause 时写明确 `error_code`，`explain()` 输出"该问题无法通过恢复解决，请取消后重试/改用其他来源"；
- c. 或新增 paused→failed 的判定迁移（failed 不可 resume）。
````

触发：全部不可恢复失败仍paused与恢复文案
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：确无failed运行期写入，但规格先暂停诊断、何谓确认不可恢复没有自动机制；cancel可结束。报告永久卡死过强；可作为恢复指引/状态语义建议，未把合法损坏暂停判成功能缺陷。

判定理由：确无failed运行期写入，但规格先暂停诊断、何谓确认不可恢复没有自动机制；cancel可结束。报告永久卡死过强；可作为恢复指引/状态语义建议，未把合法损坏暂停判成功能缺陷。

代码：[SessionMergeService.java:218](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L218)；[SessionMergeService.java:237](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L237)；[session-merge-architecture-v2.md:352](../../../../session-merge-architecture-v2.md#L352)

核验：代码静态核验及原文：见code_refs/lines

### R14-C04

**阻断gap与record的恢复关联断裂** — 成立 / functional / scope=new / 入分；统一问题：D04；credit=1。

[原文 L142–L151](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L142)

````text
### 🔴 Major-2【后端·功能】恢复投影协议对两类阻断记录不可达（gap 与 blocked 记录无法关联；asset 类阻断 ref 不属于 blocked 集合）

**证据**（coordinator 已亲自抽验）：
- `MergePackageService.java:232-236`（`recoverBlockedProjections`）：只接受 reason 形如 `RECORD_REQUIRES_HANDLING:<ref>` **且该 ref 必须存在于 records.jsonl 中 `processingPolicy=='blocked'` 的集合**，否则直接抛 `RECORD_REQUIRES_HANDLING`；
- 但 `:436` 消息内容无法解析时写入的 gap **不带 ref**（`:406` 无 UUID 重复记录同样不带 ref）——尽管 `:435` 已创建 policy=blocked 的记录，二者永远无法关联；
- `:598` 的资产文本编码失败写入 `RECORD_REQUIRES_HANDLING:<asset recordRef>`，但资产记录 policy 是 `extract`（`exportAsset:594-599`），不进入 blocked 集合，同样永远抛错。

**影响**：对这两类阻断，`recoverBlockedProjections` 从不真正尝试恢复；规格 §4.3"封存后修正解析配置 → 从本包 raw 生成投影"的派生投影机制只能覆盖"raw 超大/JSON 无法解析但可重新物化"那一类。与 Major-1 叠加放大（阻断记录已保留但恢复协议声称支持的场景不可达）。

**建议修法**：统一 gap 与 blocked 记录的关联键（消息阻断写 `RECORD_REQUIRES_HANDLING:<ref>`）；asset 类阻断单独分类（`ASSET_UNREADABLE:<ref>`）或允许非 blocked 记录通过"生成可读投影/占位说明"参与恢复；为 `invalid_image`/attachment 类提供"跳过并标记"的非阻断策略。
````

触发：产生无ref或不属blocked集合的可重解析记录缺口
预期：可恢复时尝试从immutable raw生成派生投影
实际：关联检查直接拒绝

判定理由：无ref消息gap及asset extract policy与blockedRecords不匹配，恢复在读raw前被拒。确认关联协议问题；损坏checkpoint保持暂停符合规格，不因本命中认可跳过已保存关键原件。；统一复核：合成原件不变，启用Jackson现有ALLOW_SINGLE_QUOTES后已可解析，恢复仍在读raw前被缺ref挡住；提高物化上限的正控制能恢复。仅确认这类关联协议缺口，损坏资料允许暂停，不采用永久/所有恢复失效的泛化。

核实范围：已修正解析配置仍被gap/ref关联校验阻止；不等于所有坏原件应可恢复。

代码：[MergePackageService.java:232](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L232)；[MergePackageService.java:435](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L435)；[MergePackageService.java:598](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L598)

核验：[reproductions/RankingProtocolReproTest.java](reproductions/RankingProtocolReproTest.java)

### R14-C05

**epoch409采纳无前端测试** — 成立 / engineering / scope=new / 不入分；统一问题：store-conflict-test-gap；credit=None。

[原文 L153–L159](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L153)

````text
### 🔴 Major-3【前端·测试缺口】规格强制验收项"旧 resume 不重放 / epoch 过期刷新状态"零断言

**证据**：`frontend/src/store/sessionMergeStore.ts:243` `const operation = response.ok ? body : body.operation;` 与 `:249` 的错误抛出；收到 409 `MERGE_STALE_OPERATION` 时会采用 `body.operation`（最新 epoch/状态）并展示服务端 message——该分支**无任何用例**。现有测试只有快乐路径（`sessionMergeStore.test.ts:427-438` 断言 resume 发送 `{expectedEpoch:7, model}`；`:440-446` 断言 cancel 无 body）。

**影响**：规格 §8"旧 resume 请求按 epoch 拒绝后刷新状态"与 §9 UI 用例"旧 resume 不重放"是本轮关键验收项，当前无回归保护。若该分支被改坏（遗忘 adopt、自动重试、epoch 回写旧值），测试全绿但真实用户会卡在"状态已变化"循环。

**建议**：新增 store 用例：pending=paused(epoch=7) → POST /resume 返回 409 `{error:{code:'MERGE_STALE_OPERATION',...}, operation:{...status:'preparing',runEpoch:8}}` → 断言：(a) pending.operation.runEpoch===8 且 status==='preparing'；(b) 未自动再次 POST /resume（fetch 调用次数）；(c) 展示服务端 message；(d) 再次 resume 使用 expectedEpoch=8。另补一条 cancel 409 `MERGE_ALREADY_COMPLETED` + operation(completed) 的采用断言。
````

触发：epoch409采纳无前端测试
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：有实现分支，无对应409直接回归；只记测试缺口。

判定理由：有实现分支，无对应409直接回归；只记测试缺口。

代码：[sessionMergeStore.ts:243](../../../../../frontend/src/store/sessionMergeStore.ts#L243)；[sessionMergeStore.test.ts:427](../../../../../frontend/src/store/sessionMergeStore.test.ts#L427)

核验：代码静态核验及原文：见code_refs/lines

### R14-C06

**v2面板新控件无组件测试** — 成立 / engineering / scope=new / 不入分；统一问题：panel-v2-test-gap；credit=None。

[原文 L161–L167](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L161)

````text
### 🔴 Major-4【前端·测试缺口】面板 v2 全部新控件零覆盖

**证据**：`frontend/src/components/session/SessionMergePanel.tsx:198-226` 新增的 paused/cancelled 状态行、`progress.completedUnits/knownUnits`（`:205`）、`retryAt`（`:206`）、暂停原因（`:207`）、`canResume` 恢复块（`:217-224`）、`canCancel` 取消按钮（`:225`）、"关闭结果"门禁（`:226`）、`targetAvailable===false` 禁用与"目标会话已删除。"（`:214-215`）——`SessionMergePanel.test.tsx` 本次仅改 8 行（fixture + 2 处文案断言）。

**影响**：面板是用户唯一操作入口；按钮渲染条件（如 `disabled={targetAvailable===false}`、`!busy && !canCancel` 的 dismiss 隐藏）或 resume 参数（`model || undefined`）被改坏不会被发现。§9"UI 与控制"验收实际只覆盖了旧用例。

**建议**：补 5-6 条面板用例：(1) paused+canResume+canCancel → 渲染暂停文案/原因/恢复/取消，点击恢复断言 POST resume 带 expectedEpoch；(2) 点击取消断言 POST /cancel 且无 body；(3) preparing+`progress{3,10,totalFinal:false}` → 断言"整理单元：3/10（总数随分片增加）"且无百分比；(4) completed+targetAvailable:false → "打开新会话"禁用 + "目标会话已删除。"；(5) failed(v2) → 只显示取消按钮，取消后显示"关闭结果"。
````

触发：v2面板新控件无组件测试
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：既有Panel测试覆盖旧创建/激活/恢复，新增canResume/canCancel等缺直接组件断言。

判定理由：既有Panel测试覆盖旧创建/激活/恢复，新增canResume/canCancel等缺直接组件断言。

代码：[SessionMergePanel.tsx:217](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L217)；[SessionMergePanel.test.tsx:1](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L1)

核验：代码静态核验及原文：见code_refs/lines

### R14-C07

**lockedSourceIds未列descendants** — 规范歧义 / conditional / scope=new / 不入分；统一问题：descendant-lock-dto；credit=None。

[原文 L177–L177](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L177)

````text
| M-1 | `lockedSourceSessionIds` 只反映根来源，子会话（descendants）的锁不可见 | `SessionMergeService.java:264-266` 只对 `request.sourceSessionIds()` 过滤；`lockSources:114-119` 却对每个 descendant 取 token | 前端"来源是否锁住只看服务端字段"会低估锁范围；建议纳入 descendants 或明确 DTO 语义 |
````

触发：lockedSourceIds未列descendants
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：DTO只过滤根来源、实际锁含后代属实；所选来源只允许普通会话，子任务是否需作为该字段来源显示规范不完全明确；后端session summary另有mergeOperationId，不能直接推断子任务可删除。

判定理由：DTO只过滤根来源、实际锁含后代属实；所选来源只允许普通会话，子任务是否需作为该字段来源显示规范不完全明确；后端session summary另有mergeOperationId，不能直接推断子任务可删除。

代码：[SessionMergeService.java:263](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L263)；[SessionMergeService.java:114](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L114)

核验：代码静态核验及原文：见code_refs/lines

### R14-C08

**跨实例create冲突500** — 范围外 / conditional / scope=out / 不入分；统一问题：multi-instance-active-conflict；credit=None。

[原文 L178–L178](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L178)

````text
| M-2 | 跨进程/多实例抢名额时唯一索引冲突以 500（DataAccessException）暴露而非 `MERGE_ACTIVE_EXISTS` 409 | `SessionMergeService.start:95-97` 先 active() 再 create()；同一 JVM 有 synchronized 覆盖 | 单机部署不可达；建议 catch `DataIntegrityViolationException` 转 409 |
````

触发：跨实例create冲突500
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：仅多进程同库，非本次单实例支持契约。

判定理由：仅多进程同库，非本次单实例支持契约。

代码：[SessionMergeService.java:81](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L81)

核验：代码静态核验及原文：见code_refs/lines

### R14-C09

**来源集合不重取两次** — 成立 / engineering / scope=new / 不入分；统一问题：source-tree-retry-policy；credit=None。

[原文 L179–L179](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L179)

````text
| M-3 | 未实现规格 §4.3.1"集合变动最多重新取得两次" | `lockSources:121-122` 单次失败即 MERGE_SOURCE_BUSY | 低概率竞态下合并被误拒一次（fail-safe，可手动恢复）；建议按规格补重取 |
````

触发：来源集合不重取两次
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：保守拒绝可重试，缺一轮自动重试而非数据错误。

判定理由：保守拒绝可重试，缺一轮自动重试而非数据错误。

代码：[SessionMergeService.java:120](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L120)

核验：代码静态核验及原文：见code_refs/lines

### R14-C10

**非UTF8文本附件阻断** — 规范歧义 / conditional / scope=new / 不入分；统一问题：non-utf8-attachment-blocking；credit=None。

[原文 L180–L180](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L180)

````text
| M-4 | 损坏的文本类附件（非 UTF-8 的 .txt/.md/.log 等）即阻断整个合并 | `MergePackageService.java:588-598` 严格 UTF-8 读取，失败即 `blocking=true` | 与规格"非关键附件可标记保留原件"不一致，可能误伤；建议降级为非阻断 gap 或允许 resume 时跳过 |
````

触发：非UTF8文本附件阻断
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：编码错误触发blocking属实；规格允许关键附件不可理解暂停，未定义机器判断关键性；未提供已知非关键标志路径。可改进编码策略，不强行奖惩。

判定理由：编码错误触发blocking属实；规格允许关键附件不可理解暂停，未定义机器判断关键性；未提供已知非关键标志路径。可改进编码策略，不强行奖惩。

代码：[MergePackageService.java:588](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L588)；[session-merge-architecture-v2.md:183](../../../../session-merge-architecture-v2.md#L183)

核验：代码静态核验及原文：见code_refs/lines

### R14-C11

**不可恢复错误paused文案** — 规范歧义 / engineering / scope=new / 不入分；统一问题：paused-without-failed-state；credit=None。

[原文 L181–L181](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L181)

````text
| M-5 | `MERGE_UNIT_INPUT_CHANGED` / `MERGE_PROCESSOR_VERSION_CHANGED` / `MERGE_SNAPSHOT_CORRUPT` 等不可恢复错误同样进入 paused 循环，文案缺操作指引 | `MergeProgressRepository.plan:152-155`；`SessionMergeService.explain:237-247` default 分支 | 与 Major-1 同源体验问题；建议一并处理 |
````

触发：不可恢复错误paused文案
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：与Major-1同根去重，合法先暂停不另计。

判定理由：与Major-1同根去重，合法先暂停不另计。

代码：[SessionMergeService.java:246](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L246)

核验：代码静态核验及原文：见code_refs/lines

### R14-C12

**v2磁盘/复制/artifact测试缺口** — 成立 / engineering / scope=new / 不入分；统一问题：snapshot-artifact-test-gap；credit=None。

[原文 L182–L182](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L182)

````text
| M-6 | v2 `seal()` 路径的磁盘余量/复制配额/复制中途变化无直接测试；artifact_manifests/entries 分页导出（§4.2 明确要求）零覆盖 | `MergePackageServiceTest` 同类用例均走旧 `build()`；`MergeFixture:41-47` 未建两张表；`MergePackageService.java:442-451` 无测试触及 | 代码静态审阅正确（列名与 V017 实表一致），但上游建表变动/SQL 拼写错误无测试兜底；建议补 v2 用例 |
````

触发：v2磁盘/复制/artifact测试缺口
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：正确保留为测试建议，未证明SQL错误。

判定理由：正确保留为测试建议，未证明SQL错误。

代码：[MergeFixture.java:41](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeFixture.java#L41)；[MergePackageService.java:442](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L442)

核验：代码静态核验及原文：见code_refs/lines

### R14-C13

**导入旧包destination缺防御校验** — 证据不足 / conditional / scope=new / 不入分；统一问题：import-destination-check；credit=None。

[原文 L183–L183](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L183)

````text
| M-7 | `importPrevious` 的 destination 重映射缺少"目标仍在包内"的防御性校验 | `MergePackageService.java:648-657`：`root.resolve(Path.of("snapshot").relativize(relative))` 直接拼接 | 仅旧包 files.jsonl 被人工篡改时可能破坏布局（无法逃出包根）；建议写入前 `normalize().startsWith(root)` 校验 |
````

触发：导入旧包destination缺防御校验
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：safeFile先拒绝..和symlink，catalog哈希由已绑定snapshotHash验证；仅人工篡改catalog会先hash失败，未证明可接受恶意条目。

判定理由：safeFile先拒绝..和symlink，catalog哈希由已绑定snapshotHash验证；仅人工篡改catalog会先hash失败，未证明可接受恶意条目。

代码：[MergePackageService.java:626](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L626)；[MergePackageService.java:643](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L643)

核验：代码静态核验及原文：见code_refs/lines

### R14-C14

**E预算过小时错误类别通用** — 成立 / functional / scope=new / 入分；统一问题：D09；credit=0.5。

[原文 L189–L189](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L189)

````text
| I-1 | E 会话在绑定缺失/包损坏/预算过小时**整会话失败且 REST 落 500** | `HandoffContextService.java:41-43, 71-78` 抛 AuthorizationException/IllegalStateException；`GlobalExceptionHandler` 落 500 catch-all | 仅 E（普通不受影响）；库回滚/包丢失时 E 每条消息都失败。建议降级为"资料不可用"提示或结构化 4xx |
````

触发：最小入口放不下
预期：容量错误
实际：通用执行错误

判定理由：同条中的绑定/包损坏拒绝是安全策略，不自动计缺陷；tiny预算错误通道有明确规范依据。不是整个会话状态被改failed，而是请求失败。；统一复核：同R02/R05/R13：真实tiny预算触发正确，但QueryEngine捕获project异常并返回error，REST控制器正常ResponseEntity.ok；不是异常穿透/必然500。按同规则半命中并半份归因错误。

核实范围：E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)

核验：[reproductions/RankingQueryReproTest.java](reproductions/RankingQueryReproTest.java)

### R14-C15

**每轮重复投影和哈希** — 成立 / engineering / scope=new / 不入分；统一问题：duplicate-handoff-projection；credit=None。

[原文 L190–L190](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L190)

````text
| I-2 | E 每轮最多 3 次全量投影（绑定查询 + 全目录哈希校验 + brief 读取），工具调用再 3 次校验 | `QueryEngine.java:779/841/844/1835`；`HandoffContextService.project:62-78`；`HandoffReadService.verifyPrepared:69-101` | E-only 性能开销（大包时单轮数 MB 哈希）；建议同轮复用一次投影 |
````

触发：每轮重复投影和哈希
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：多次校验成立，未量化失败，不等于每次全包asset哈希。

判定理由：多次校验成立，未量化失败，不等于每次全包asset哈希。

代码：[QueryEngine.java:841](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L841)；[HandoffReadService.java:69](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L69)

核验：代码静态核验及原文：见code_refs/lines

### R14-C16

**HandoffRead超时code被包装** — 成立 / functional / scope=new / 入分；统一问题：D13；credit=1。

[原文 L191–L191](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L191)

````text
| I-3 | `HANDOFF_READ_BUDGET_EXCEEDED` 的 code 被包装替换 | 初始校验超时→`HANDOFF_INVALID_RESOURCE`（`HandoffReadService.java:205-208`）；扫描超时→`HANDOFF_READ_FAILED`（`HandoffReadTool.java:44`），仅 message 保留原 code | 规格要求明确透传；模型/前端难区分"越界"与"损坏"；建议透传 |
````

触发：完整性校验或扫描超过15秒
预期：HANDOFF_READ_BUDGET_EXCEEDED顶层错误
实际：顶层分类被改，原码仅message

判定理由：Budget抛HANDOFF_READ_BUDGET_EXCEEDED；authorize包装HANDOFF_INVALID_RESOURCE，tool执行包装HANDOFF_READ_FAILED，与规范明确code不符。

核实范围：授权/读取支路把预算错误码包成INVALID_RESOURCE等泛码；read入口中断反例正常重抛。

代码：[HandoffReadService.java:43](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L43)；[HandoffReadService.java:55](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L55)；[HandoffReadTool.java:45](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L45)；[session-merge-architecture-v2.md:387](../../../../session-merge-architecture-v2.md#L387)

核验：[reproductions/RankingReadReproTest.java](reproductions/RankingReadReproTest.java)

### R14-C17

**跨实例resume冲突500** — 范围外 / conditional / scope=out / 不入分；统一问题：multi-instance-resume-conflict；credit=None。

[原文 L192–L192](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L192)

````text
| I-4 | 跨进程并发 resume 败者返回 500 而非 409 `MERGE_STALE_OPERATION` | `MergeProgressRepository.java:108-118` 条件更新失败 → `IllegalStateException("MERGE_STALE_EXECUTION")`，Service 未转换 | 单 JVM 有 synchronized+writers 防护；多实例共享库时契约退化；建议映射为 Conflict |
````

触发：跨实例resume冲突500
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：单进程正常路径有synchronized；多实例同库范围外。

判定理由：单进程正常路径有synchronized；多实例同库范围外。

代码：[SessionMergeService.java:136](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L136)

核验：代码静态核验及原文：见code_refs/lines

### R14-C18

**全量环境依赖既有失败** — 范围外 / engineering / scope=out / 不入分；统一问题：baseline-environment-tests；credit=None。

[原文 L193–L193](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L193)

````text
| I-5 | 本机默认环境全量 2,975 项有 11 个环境变量相关失败（HEAD 基线同样失败） | `ZHIKUN_COORDINATOR_MODE=1`（×3）、`ALLOW_PRIVATE_NETWORK=true`（×6）、真实 `LLM_PROVIDER_DASHSCOPE_API_KEY`（×1）；缺 ripgrep（×1） | 与本次改动无关，但影响"发布标准"叙事；建议测试侧隔离环境变量并在验收说明注明环境前提 |
````

触发：全量环境依赖既有失败
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：报告明确与本改动无关；本轮未复跑其原日志，不对历史真实性加分。

判定理由：报告明确与本改动无关；本轮未复跑其原日志，不对历史真实性加分。

代码：[SecurityFilterIntegrationTest.java:1](../../../../../backend/src/test/java/com/aicodeassistant/config/SecurityFilterIntegrationTest.java#L1)

核验：代码静态核验及原文：见code_refs/lines

### R14-C19

**参数captor和异常分支测试薄弱** — 成立 / engineering / scope=new / 不入分；统一问题：handoff-context-test-gaps；credit=None。

[原文 L194–L194](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L194)

````text
| I-6 | 新增测试薄弱点 | `QueryEngineUnitTest.java:1950-1984` 未 captor 校验传给 `project()` 的是扣减前总预算（仅间接证明）；`HandoffContextServiceTest` 缺 `toolAvailable=true` 全量 brief / `HANDOFF_CONTEXT_BUDGET_TOO_SMALL` / brief IOException 三类用例；`AuthorizationServiceProjectFileScopeTest.java:41-43` 未断言 `PLAN_MODE_EFFECT_DENIED` | 边界回归证明力略低；建议补 captor/异常 code 断言与三类用例 |
````

触发：参数captor和异常分支测试薄弱
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：测试精度建议，不自动等价产品错误。

判定理由：测试精度建议，不自动等价产品错误。

代码：[HandoffContextServiceTest.java:1](../../../../../backend/src/test/java/com/aicodeassistant/engine/HandoffContextServiceTest.java#L1)；[QueryEngineUnitTest.java:1950](../../../../../backend/src/test/java/com/aicodeassistant/engine/QueryEngineUnitTest.java#L1950)

核验：代码静态核验及原文：见code_refs/lines

### R14-C20

**null ToolUseContext防御** — 证据不足 / conditional / scope=new / 不入分；统一问题：null-context-configure；credit=None。

[原文 L195–L195](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L195)

````text
| I-7 | `HandoffContextService.configure` 对 null ToolUseContext 无防护（E-only NPE 风险） | `HandoffContextService.java:41` 直接 `state.getToolUseContext().sessionId()` | 当前三个入口均先设置，但 API 未防御；建议 null 时明确降级或抛带 code 的异常 |
````

触发：null ToolUseContext防御
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：当前所有入口先创建context，报告亦承认；未知外部调用仅防御性建议。

判定理由：当前所有入口先创建context，报告亦承认；未知外部调用仅防御性建议。

代码：[HandoffContextService.java:41](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L41)；[QueryController.java:164](../../../../../backend/src/main/java/com/aicodeassistant/controller/QueryController.java#L164)

核验：代码静态核验及原文：见code_refs/lines

### R14-C21

**paused/cancelled旧writer仍持锁时忽略** — 成立 / functional / scope=new / 入分；统一问题：D15；credit=1。

[原文 L201–L201](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L201)

````text
| F-1 | `selectMergeSourceIds` 仍从 `status==='preparing'` 反向推断锁定 | `sessionMergeStore.ts:28-31` | paused/cancelled 旧 writer 收尾期间服务端仍可能返回非空锁集合，此窗口内前端误判为未锁（gate 仍会拒绝，用户看到报错而非前置阻断）；建议去掉 status 条件，只取服务端字段 |
````

触发：paused/cancelled旧writer仍持锁时忽略
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：selector状态门控与真实锁短窗口不一致，后端继续拒绝保护数据。

判定理由：selector状态门控与真实锁短窗口不一致，后端继续拒绝保护数据。

核实范围：cancelled/paused短窗口真实锁仍在但selector隐藏；后端gate保护仍在。

代码：[sessionMergeStore.ts:28](../../../../../frontend/src/store/sessionMergeStore.ts#L28)；[SessionMergeService.java:230](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L230)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R14-C22

**本地终态遮蔽服务端新active** — 成立 / functional / scope=new / 入分；统一问题：D07；credit=1。

[原文 L202–L202](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L202)

````text
| F-2 | 本地存在陈旧终态记录时不会查询服务端 active | `sessionMergeStore.ts:140` `if (!pending && Date.now() < nextActiveCheck)`；`restore():66-71` 直接采用已保存记录 | 其他设备发起的新合并在本机不可见，须先 dismiss 旧结果；建议终态记录时仍查询一次 `/active` |
````

触发：本地保留旧completed结果，另一设备启动新merge
预期：重载能发现服务端未结束操作
实际：停在旧终态直到手动dismiss

判定理由：有pending只GET旧id；终态验证缓存后直接return，不询active；另设备新任务无storage同步，需dismiss旧结果才能发现。

核实范围：本地旧终态遮蔽服务端新active；关闭旧结果可绕过。

代码：[sessionMergeStore.ts:139](../../../../../frontend/src/store/sessionMergeStore.ts#L139)；[sessionMergeStore.ts:149](../../../../../frontend/src/store/sessionMergeStore.ts#L149)；[session-merge-architecture-v2.md:422](../../../../session-merge-architecture-v2.md#L422)

核验：[reproductions/sessionMergeReviewRepro.test.ts](reproductions/sessionMergeReviewRepro.test.ts)

### R14-C23

**failed行显示旧stage** — 证据不足 / conditional / scope=new / 不入分；统一问题：failed-stage-label；credit=None。

[原文 L203–L203](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L203)

````text
| F-3 | failed(v2) 状态行展示最后一个工作阶段文案（如"整理详细交接"）；error 为空时渲染空 alert | `SessionMergePanel.tsx:198, 207` | 失败态可读性差；建议为 failed 增加专门文案 + error 非空条件渲染 |
````

触发：failed行显示旧stage
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：v2当前没有failed写入；v1失败stage为failed/interrupted有映射。假定未来v2failed可复现但当前无支持路径。

判定理由：v2当前没有failed写入；v1失败stage为failed/interrupted有映射。假定未来v2failed可复现但当前无支持路径。

代码：[SessionMergePanel.tsx:198](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L198)；[SessionMergeService.java:226](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L226)

核验：代码静态核验及原文：见code_refs/lines

### R14-C24

**同key通知未去重** — 成立 / functional / scope=new / 入分；统一问题：D22；credit=1。

[原文 L204–L204](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L204)

````text
| F-4 | 通知去重依赖调用方 `changed &&`；notificationStore 不按 key 去重；paused/cancelled 一律 error 级别 | `sessionMergeStore.ts:200-203`；`notificationStore.ts:32-33` 无条件 push；`ToastContainer.tsx:41` `key={n.key}` | 5s 窗口内重复触发会产生重复 Toast 与 React duplicate-key 警告；cancelled 是用户主动操作却报 error。建议按 key 覆盖 + 分级 warning/info |
````

触发：同key通知未去重
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：changed只去重复完全相同DTO；再次暂停或paused进度改变仍push同key。取消级别另属UX偏好，不加额外分。

判定理由：changed只去重复完全相同DTO；再次暂停或paused进度改变仍push同key。取消级别另属UX偏好，不加额外分。

核实范围：5秒通知仍在期间，新增paused→resume→paused可推送同key通知两份。

代码：[sessionMergeStore.ts:200](../../../../../frontend/src/store/sessionMergeStore.ts#L200)；[notificationStore.ts:32](../../../../../frontend/src/store/notificationStore.ts#L32)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R14-C25

**control返回HTML暴露解析异常** — 成立 / functional / scope=new / 入分；统一问题：D20；credit=1。

[原文 L205–L205](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L205)

````text
| F-5 | `control()` 的 `response.json()` 无兜底、无 404 处理 | `sessionMergeStore.ts:241`（对比 `:159` 有 `.catch(() => ({}))`）；`:252` 解析异常原文当 UI 消息 | 代理返回 HTML 时用户看到 "Unexpected token …"；404 要等下一次轮询才释放；建议 json catch + 复用 refresh 404 释放逻辑 |
````

触发：control返回HTML暴露解析异常
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：control不含JSON兜底；用户UI直接显示解析异常。404要等poll是有限延迟，不另计第二缺陷。

判定理由：control不含JSON兜底；用户UI直接显示解析异常。404要等poll是有限延迟，不另计第二缺陷。

核实范围：控制接口非JSON错误响应显示JSON解析异常，掩盖可操作错误信息。

代码：[sessionMergeStore.ts:241](../../../../../frontend/src/store/sessionMergeStore.ts#L241)；[sessionMergeStore.ts:252](../../../../../frontend/src/store/sessionMergeStore.ts#L252)

核验：代码静态核验：见code_refs

### R14-C26

**轮询禁用恢复取消按钮** — 成立 / functional / scope=new / 入分；统一问题：D08；credit=1。

[原文 L206–L206](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L206)

````text
| F-6 | 轮询期间恢复/取消按钮被 `disabled={submitting}` 周期性禁用 | `sessionMergeStore.ts:145` 每次 refresh 开始 `set({submitting:true})`；`SessionMergePanel.tsx:222/225` | 每 2s 轮询反复禁用按钮，与"进度更新不阻挡取消"相悖（`control()` 已串行化，并不需要禁用）；建议拆出 `controlling` 标志或去掉 disabled |
````

触发：progress GET挂起/延迟
预期：可以发起取消意图
实际：按钮被disabled最长15秒并周期重现

判定理由：refresh设置submitting=true；Panel所有新控制按钮使用同flag，而控制内部已有串行机制。轮询窗口用户不能点击，与进度不阻挡取消要求相悖。

核实范围：轮询复用submitting禁用恢复/取消，程序cancel也等待GET结束；不表示后端永久不能取消。

代码：[sessionMergeStore.ts:144](../../../../../frontend/src/store/sessionMergeStore.ts#L144)；[SessionMergePanel.tsx:222](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L222)；[SessionMergePanel.tsx:225](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L225)；[session-merge-architecture-v2.md:447](../../../../session-merge-architecture-v2.md#L447)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R14-C27

**暂停浮动按钮合并结果** — 成立 / engineering / scope=new / 不入分；统一问题：paused-floating-label；credit=None。

[原文 L207–L207](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L207)

````text
| F-7 | 收起态浮动按钮对 paused/failed 显示"合并结果" | `SessionMergePanel.tsx:34, 143`（`busy` 只含 preparing） | 暂停/失败没有"结果"可看，文案误导；建议按状态显示"合并已暂停 · 去恢复 / 合并失败 · 处理" |
````

触发：暂停浮动按钮合并结果
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：可改善文案，但仍可打开并处理，语义偏好不计产品TP。

判定理由：可改善文案，但仍可打开并处理，语义偏好不计产品TP。

代码：[SessionMergePanel.tsx:143](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L143)

核验：代码静态核验及原文：见code_refs/lines

### R14-C28

**history_storage_version死条件** — 成立 / engineering / scope=new / 不入分；统一问题：dead-history-storage-version；credit=None。

[原文 L213–L213](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L213)

````text
**后端核心（7）**：`history_storage_version` 死条件（`MergePackageService.java:440`，全仓无生产者）；`MAX_RECORD_BYTES=16MiB` 常量残留易误读（`:71`，仅旧 build 引用）；聚合单元 `sourceId` 为空串（`MergeSummaryService.java:130-133`）；unit `state='failed'` 定义但从不写入（`MergeSummaryService.java:238`）；`SessionMergeService` 构造函数保留未用 `permissions` 参数（`:83-85`）；brief 兜底缺"还有 N 项未展开"数字（`MergeSummaryService.java:112`，overview 已有计数）；`retry_at` 崩溃残留且恢复不遵守（`MergeSummaryService.java:251-257`）。
````

触发：history_storage_version死条件
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：无当前写方，维护问题。

判定理由：无当前写方，维护问题。

代码：[MergePackageService.java:440](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L440)

核验：代码静态核验及原文：见code_refs/lines

### R14-C29

**MAX_RECORD_BYTES旧helper常量** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-record-limit-constant；credit=None。

[原文 L213–L213](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L213)

````text
**后端核心（7）**：`history_storage_version` 死条件（`MergePackageService.java:440`，全仓无生产者）；`MAX_RECORD_BYTES=16MiB` 常量残留易误读（`:71`，仅旧 build 引用）；聚合单元 `sourceId` 为空串（`MergeSummaryService.java:130-133`）；unit `state='failed'` 定义但从不写入（`MergeSummaryService.java:238`）；`SessionMergeService` 构造函数保留未用 `permissions` 参数（`:83-85`）；brief 兜底缺"还有 N 项未展开"数字（`MergeSummaryService.java:112`，overview 已有计数）；`retry_at` 崩溃残留且恢复不遵守（`MergeSummaryService.java:251-257`）。
````

触发：MAX_RECORD_BYTES旧helper常量
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：旧build兼容路径保留，线上seal不受此常量阈值影响。

判定理由：旧build兼容路径保留，线上seal不受此常量阈值影响。

代码：[MergePackageService.java:71](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L71)

核验：代码静态核验及原文：见code_refs/lines

### R14-C30

**聚合sourceId为空** — 成立 / engineering / scope=new / 不入分；统一问题：aggregate-source-id；credit=None。

[原文 L213–L213](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L213)

````text
**后端核心（7）**：`history_storage_version` 死条件（`MergePackageService.java:440`，全仓无生产者）；`MAX_RECORD_BYTES=16MiB` 常量残留易误读（`:71`，仅旧 build 引用）；聚合单元 `sourceId` 为空串（`MergeSummaryService.java:130-133`）；unit `state='failed'` 定义但从不写入（`MergeSummaryService.java:238`）；`SessionMergeService` 构造函数保留未用 `permissions` 参数（`:83-85`）；brief 兜底缺"还有 N 项未展开"数字（`MergeSummaryService.java:112`，overview 已有计数）；`retry_at` 崩溃残留且恢复不遵守（`MergeSummaryService.java:251-257`）。
````

触发：聚合sourceId为空
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：聚合跨来源输入的sourceId为空设计可理解，具体证据链仍回指各source。

判定理由：聚合跨来源输入的sourceId为空设计可理解，具体证据链仍回指各source。

代码：[MergeSummaryService.java:97](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L97)

核验：代码静态核验及原文：见code_refs/lines

### R14-C31

**unit failed未写** — 成立 / engineering / scope=new / 不入分；统一问题：unit-failed-unused；credit=None。

[原文 L213–L213](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L213)

````text
**后端核心（7）**：`history_storage_version` 死条件（`MergePackageService.java:440`，全仓无生产者）；`MAX_RECORD_BYTES=16MiB` 常量残留易误读（`:71`，仅旧 build 引用）；聚合单元 `sourceId` 为空串（`MergeSummaryService.java:130-133`）；unit `state='failed'` 定义但从不写入（`MergeSummaryService.java:238`）；`SessionMergeService` 构造函数保留未用 `permissions` 参数（`:83-85`）；brief 兜底缺"还有 N 项未展开"数字（`MergeSummaryService.java:112`，overview 已有计数）；`retry_at` 崩溃残留且恢复不遵守（`MergeSummaryService.java:251-257`）。
````

触发：unit failed未写
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：保守pending/running配合operation paused，不等于恢复失败。

判定理由：保守pending/running配合operation paused，不等于恢复失败。

代码：[MergeProgressRepository.java:201](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L201)

核验：代码静态核验及原文：见code_refs/lines

### R14-C32

**未使用permissions构造参数** — 成立 / engineering / scope=new / 不入分；统一问题：unused-permissions-argument；credit=None。

[原文 L213–L213](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L213)

````text
**后端核心（7）**：`history_storage_version` 死条件（`MergePackageService.java:440`，全仓无生产者）；`MAX_RECORD_BYTES=16MiB` 常量残留易误读（`:71`，仅旧 build 引用）；聚合单元 `sourceId` 为空串（`MergeSummaryService.java:130-133`）；unit `state='failed'` 定义但从不写入（`MergeSummaryService.java:238`）；`SessionMergeService` 构造函数保留未用 `permissions` 参数（`:83-85`）；brief 兜底缺"还有 N 项未展开"数字（`MergeSummaryService.java:112`，overview 已有计数）；`retry_at` 崩溃残留且恢复不遵守（`MergeSummaryService.java:251-257`）。
````

触发：未使用permissions构造参数
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：非功能风格项。

判定理由：非功能风格项。

代码：[SessionMergeService.java:62](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L62)

核验：代码静态核验及原文：见code_refs/lines

### R14-C33

**brief未展开数未明确** — 成立 / engineering / scope=new / 不入分；统一问题：brief-hidden-count；credit=None。

[原文 L213–L213](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L213)

````text
**后端核心（7）**：`history_storage_version` 死条件（`MergePackageService.java:440`，全仓无生产者）；`MAX_RECORD_BYTES=16MiB` 常量残留易误读（`:71`，仅旧 build 引用）；聚合单元 `sourceId` 为空串（`MergeSummaryService.java:130-133`）；unit `state='failed'` 定义但从不写入（`MergeSummaryService.java:238`）；`SessionMergeService` 构造函数保留未用 `permissions` 参数（`:83-85`）；brief 兜底缺"还有 N 项未展开"数字（`MergeSummaryService.java:112`，overview 已有计数）；`retry_at` 崩溃残留且恢复不遵守（`MergeSummaryService.java:251-257`）。
````

触发：brief未展开数未明确
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：header给单元数和栏目统计并提示继续读，具体N字样可优化；没有证明信息丢失。

判定理由：header给单元数和栏目统计并提示继续读，具体N字样可优化；没有证明信息丢失。

代码：[MergeSummaryService.java:143](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L143)

核验：代码静态核验及原文：见code_refs/lines

### R14-C34

**retryAt崩溃残留** — 成立 / engineering / scope=new / 不入分；统一问题：retry-at-restart；credit=None。

[原文 L213–L213](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L213)

````text
**后端核心（7）**：`history_storage_version` 死条件（`MergePackageService.java:440`，全仓无生产者）；`MAX_RECORD_BYTES=16MiB` 常量残留易误读（`:71`，仅旧 build 引用）；聚合单元 `sourceId` 为空串（`MergeSummaryService.java:130-133`）；unit `state='failed'` 定义但从不写入（`MergeSummaryService.java:238`）；`SessionMergeService` 构造函数保留未用 `permissions` 参数（`:83-85`）；brief 兜底缺"还有 N 项未展开"数字（`MergeSummaryService.java:112`，overview 已有计数）；`retry_at` 崩溃残留且恢复不遵守（`MergeSummaryService.java:251-257`）。
````

触发：retryAt崩溃残留
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：时间值在resume不重置且重新请求，退避续守不明确；保留恢复调度建议。

判定理由：时间值在resume不重置且重新请求，退避续守不明确；保留恢复调度建议。

代码：[MergeSummaryService.java:251](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L251)

核验：代码静态核验及原文：见code_refs/lines

### R14-C35

**全限定名风格** — 成立 / engineering / scope=new / 不入分；统一问题：injection-style；credit=None。

[原文 L215–L215](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L215)

````text
**后端集成（8）**：内联全限定类名（`QueryEngine.java:1808-1818, 940`）；`@Autowired` 字段注入（`QueryController.java:72-74`、`WebSocketController.java:90-91`，与项目构造注入主体不一致）；`@Autowired(required=false)` 与"缺 bean 即抛 IllegalStateException"的语义矛盾（`QueryEngine.java:1808-1815`）；类级 `produces="application/json"` 波及既有 create/get 端点（`SessionMergeController.java:8`，非 JSON Accept 客户端可能 406）；HandoffReadService 魔法数字散落（`:204-306`，16384/8500/2048/1500/512/20）；asset 放行阈值 20MB > `ImageRefInjector.MAX_FILE_SIZE` 10MB（`HandoffReadTool.java:41-44`，10~20MB 图片会生成必然被静默拒绝的 ref）；`alwaysLoad()==true` 与 core 登记的将来风险点（`HandoffReadTool.java:16`，当前非 bean 安全，已有测试锁定非 @Component）；`preCleanImageHistory` 未预留 handoff 预算（`QueryEngine.java:2690-2706`，无功能影响）。
````

触发：全限定名风格
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：可维护性，不计产品错误。

判定理由：可维护性，不计产品错误。

代码：[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)

核验：代码静态核验及原文：见code_refs/lines

### R14-C36

**字段注入风格** — 成立 / engineering / scope=new / 不入分；统一问题：injection-style；credit=None。

[原文 L215–L215](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L215)

````text
**后端集成（8）**：内联全限定类名（`QueryEngine.java:1808-1818, 940`）；`@Autowired` 字段注入（`QueryController.java:72-74`、`WebSocketController.java:90-91`，与项目构造注入主体不一致）；`@Autowired(required=false)` 与"缺 bean 即抛 IllegalStateException"的语义矛盾（`QueryEngine.java:1808-1815`）；类级 `produces="application/json"` 波及既有 create/get 端点（`SessionMergeController.java:8`，非 JSON Accept 客户端可能 406）；HandoffReadService 魔法数字散落（`:204-306`，16384/8500/2048/1500/512/20）；asset 放行阈值 20MB > `ImageRefInjector.MAX_FILE_SIZE` 10MB（`HandoffReadTool.java:41-44`，10~20MB 图片会生成必然被静默拒绝的 ref）；`alwaysLoad()==true` 与 core 登记的将来风险点（`HandoffReadTool.java:16`，当前非 bean 安全，已有测试锁定非 @Component）；`preCleanImageHistory` 未预留 handoff 预算（`QueryEngine.java:2690-2706`，无功能影响）。
````

触发：字段注入风格
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：同根维护建议去重。

判定理由：同根维护建议去重。

代码：[QueryController.java:72](../../../../../backend/src/main/java/com/aicodeassistant/controller/QueryController.java#L72)

核验：代码静态核验及原文：见code_refs/lines

### R14-C37

**可选bean与E缺bean抛错** — 成立 / engineering / scope=new / 不入分；统一问题：optional-handoff-bean；credit=None。

[原文 L215–L215](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L215)

````text
**后端集成（8）**：内联全限定类名（`QueryEngine.java:1808-1818, 940`）；`@Autowired` 字段注入（`QueryController.java:72-74`、`WebSocketController.java:90-91`，与项目构造注入主体不一致）；`@Autowired(required=false)` 与"缺 bean 即抛 IllegalStateException"的语义矛盾（`QueryEngine.java:1808-1815`）；类级 `produces="application/json"` 波及既有 create/get 端点（`SessionMergeController.java:8`，非 JSON Accept 客户端可能 406）；HandoffReadService 魔法数字散落（`:204-306`，16384/8500/2048/1500/512/20）；asset 放行阈值 20MB > `ImageRefInjector.MAX_FILE_SIZE` 10MB（`HandoffReadTool.java:41-44`，10~20MB 图片会生成必然被静默拒绝的 ref）；`alwaysLoad()==true` 与 core 登记的将来风险点（`HandoffReadTool.java:16`，当前非 bean 安全，已有测试锁定非 @Component）；`preCleanImageHistory` 未预留 handoff 预算（`QueryEngine.java:2690-2706`，无功能影响）。
````

触发：可选bean与E缺bean抛错
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：普通请求安全；E依赖缺失应显式失败，optional供无合并测试上下文。

判定理由：普通请求安全；E依赖缺失应显式失败，optional供无合并测试上下文。

代码：[QueryEngine.java:1815](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1815)

核验：代码静态核验及原文：见code_refs/lines

### R14-C38

**JSON produces内容协商变化** — 成立 / engineering / scope=new / 不入分；统一问题：merge-produces-json；credit=None。

[原文 L215–L215](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L215)

````text
**后端集成（8）**：内联全限定类名（`QueryEngine.java:1808-1818, 940`）；`@Autowired` 字段注入（`QueryController.java:72-74`、`WebSocketController.java:90-91`，与项目构造注入主体不一致）；`@Autowired(required=false)` 与"缺 bean 即抛 IllegalStateException"的语义矛盾（`QueryEngine.java:1808-1815`）；类级 `produces="application/json"` 波及既有 create/get 端点（`SessionMergeController.java:8`，非 JSON Accept 客户端可能 406）；HandoffReadService 魔法数字散落（`:204-306`，16384/8500/2048/1500/512/20）；asset 放行阈值 20MB > `ImageRefInjector.MAX_FILE_SIZE` 10MB（`HandoffReadTool.java:41-44`，10~20MB 图片会生成必然被静默拒绝的 ref）；`alwaysLoad()==true` 与 core 登记的将来风险点（`HandoffReadTool.java:16`，当前非 bean 安全，已有测试锁定非 @Component）；`preCleanImageHistory` 未预留 handoff 预算（`QueryEngine.java:2690-2706`，无功能影响）。
````

触发：JSON produces内容协商变化
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：端点本身返回JSON，非JSON Accept得到406合理；没有承诺其他媒体格式。

判定理由：端点本身返回JSON，非JSON Accept得到406合理；没有承诺其他媒体格式。

代码：[SessionMergeController.java:8](../../../../../backend/src/main/java/com/aicodeassistant/controller/SessionMergeController.java#L8)

核验：代码静态核验及原文：见code_refs/lines

### R14-C39

**ReadService魔法数字** — 成立 / engineering / scope=new / 不入分；统一问题：read-service-constants；credit=None。

[原文 L215–L215](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L215)

````text
**后端集成（8）**：内联全限定类名（`QueryEngine.java:1808-1818, 940`）；`@Autowired` 字段注入（`QueryController.java:72-74`、`WebSocketController.java:90-91`，与项目构造注入主体不一致）；`@Autowired(required=false)` 与"缺 bean 即抛 IllegalStateException"的语义矛盾（`QueryEngine.java:1808-1815`）；类级 `produces="application/json"` 波及既有 create/get 端点（`SessionMergeController.java:8`，非 JSON Accept 客户端可能 406）；HandoffReadService 魔法数字散落（`:204-306`，16384/8500/2048/1500/512/20）；asset 放行阈值 20MB > `ImageRefInjector.MAX_FILE_SIZE` 10MB（`HandoffReadTool.java:41-44`，10~20MB 图片会生成必然被静默拒绝的 ref）；`alwaysLoad()==true` 与 core 登记的将来风险点（`HandoffReadTool.java:16`，当前非 bean 安全，已有测试锁定非 @Component）；`preCleanImageHistory` 未预留 handoff 预算（`QueryEngine.java:2690-2706`，无功能影响）。
````

触发：ReadService魔法数字
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：风格建议。

判定理由：风格建议。

代码：[HandoffReadService.java:71](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L71)

核验：代码静态核验及原文：见code_refs/lines

### R14-C40

**alwaysLoad/core未来风险** — 成立 / engineering / scope=new / 不入分；统一问题：hypothetical-handoff-global-bean；credit=None。

[原文 L215–L215](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L215)

````text
**后端集成（8）**：内联全限定类名（`QueryEngine.java:1808-1818, 940`）；`@Autowired` 字段注入（`QueryController.java:72-74`、`WebSocketController.java:90-91`，与项目构造注入主体不一致）；`@Autowired(required=false)` 与"缺 bean 即抛 IllegalStateException"的语义矛盾（`QueryEngine.java:1808-1815`）；类级 `produces="application/json"` 波及既有 create/get 端点（`SessionMergeController.java:8`，非 JSON Accept 客户端可能 406）；HandoffReadService 魔法数字散落（`:204-306`，16384/8500/2048/1500/512/20）；asset 放行阈值 20MB > `ImageRefInjector.MAX_FILE_SIZE` 10MB（`HandoffReadTool.java:41-44`，10~20MB 图片会生成必然被静默拒绝的 ref）；`alwaysLoad()==true` 与 core 登记的将来风险点（`HandoffReadTool.java:16`，当前非 bean 安全，已有测试锁定非 @Component）；`preCleanImageHistory` 未预留 handoff 预算（`QueryEngine.java:2690-2706`，无功能影响）。
````

触发：alwaysLoad/core未来风险
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：当前非Bean且绑定/身份权限仍检查，未来风险不当现行漏洞。

判定理由：当前非Bean且绑定/身份权限仍检查，未来风险不当现行漏洞。

代码：[HandoffReadTool.java:17](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L17)

核验：代码静态核验及原文：见code_refs/lines

### R14-C41

**图片预清理未预留handoff** — 成立 / engineering / scope=new / 不入分；统一问题：image-preclean-budget；credit=None。

[原文 L215–L215](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L215)

````text
**后端集成（8）**：内联全限定类名（`QueryEngine.java:1808-1818, 940`）；`@Autowired` 字段注入（`QueryController.java:72-74`、`WebSocketController.java:90-91`，与项目构造注入主体不一致）；`@Autowired(required=false)` 与"缺 bean 即抛 IllegalStateException"的语义矛盾（`QueryEngine.java:1808-1815`）；类级 `produces="application/json"` 波及既有 create/get 端点（`SessionMergeController.java:8`，非 JSON Accept 客户端可能 406）；HandoffReadService 魔法数字散落（`:204-306`，16384/8500/2048/1500/512/20）；asset 放行阈值 20MB > `ImageRefInjector.MAX_FILE_SIZE` 10MB（`HandoffReadTool.java:41-44`，10~20MB 图片会生成必然被静默拒绝的 ref）；`alwaysLoad()==true` 与 core 登记的将来风险点（`HandoffReadTool.java:16`，当前非 bean 安全，已有测试锁定非 @Component）；`preCleanImageHistory` 未预留 handoff 预算（`QueryEngine.java:2690-2706`，无功能影响）。
````

触发：图片预清理未预留handoff
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：报告明确无功能影响，最终预算仍守护。

判定理由：报告明确无功能影响，最终预算仍守护。

代码：[QueryEngine.java:2690](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L2690)

核验：代码静态核验及原文：见code_refs/lines

### R14-C42

**图片asset成功阈值高于注入限制** — 成立 / functional / scope=new / 入分；统一问题：D12；credit=1。

[原文 L215–L215](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L215)

````text
**后端集成（8）**：内联全限定类名（`QueryEngine.java:1808-1818, 940`）；`@Autowired` 字段注入（`QueryController.java:72-74`、`WebSocketController.java:90-91`，与项目构造注入主体不一致）；`@Autowired(required=false)` 与"缺 bean 即抛 IllegalStateException"的语义矛盾（`QueryEngine.java:1808-1815`）；类级 `produces="application/json"` 波及既有 create/get 端点（`SessionMergeController.java:8`，非 JSON Accept 客户端可能 406）；HandoffReadService 魔法数字散落（`:204-306`，16384/8500/2048/1500/512/20）；asset 放行阈值 20MB > `ImageRefInjector.MAX_FILE_SIZE` 10MB（`HandoffReadTool.java:41-44`，10~20MB 图片会生成必然被静默拒绝的 ref）；`alwaysLoad()==true` 与 core 登记的将来风险点（`HandoffReadTool.java:16`，当前非 bean 安全，已有测试锁定非 @Component）；`preCleanImageHistory` 未预留 handoff 预算（`QueryEngine.java:2690-2706`，无功能影响）。
````

触发：已复制有效10–20MiB图片
预期：支持成功就注入，或明确容量错误
实际：成功ref随后被图片注入拒绝

判定理由：asset放行<=20MiB并输出image_ref，注入器拒>10MiB；10–20MiB合法图片成功结果不能进入模型视觉。；统一复核：真实约12MiB PNG获工具成功image_ref后未注入。最早实际限制是1,500,000 Base64字符预算，另有10MiB文件上限，均低于工具20MiB。报告指出真实阈值不兼容，未声称10MiB检查先执行，完整命中。

核实范围：约12MiB合法PNG获成功image_ref但视觉未注入；tool/injector上限不一致。

代码：[HandoffReadTool.java:41](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L41)；[ImageRefInjector.java:284](../../../../../backend/src/main/java/com/aicodeassistant/engine/ImageRefInjector.java#L284)

核验：[reproductions/RankingAssetReproTest.java](reproductions/RankingAssetReproTest.java)

### R14-C43

**串行测试名与构造不符** — 成立 / engineering / scope=new / 不入分；统一问题：store-serialization-test-gap；credit=None。

[原文 L217–L217](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L217)

````text
**前端（8）**：串行化用例名不副实且 `finishPolling` 是死代码（`sessionMergeStore.test.ts:487-508`，真正"先轮询后 resume"顺序未覆盖）；"paused or completed"用例只构造 paused（`:410-415`）；restore/decode 异常路径无测试（`sessionMergeStore.ts:60-72`）；锁集合变化触发 `session-lists-updated` 无直接断言（`:197-199`）；`protocolVersion?: number` 未收紧为 `1|2` 且未被消费（`:16`）；错误体采用分支不校验 `request` 字段（`:171-175`）；"五来源"用例断言"2 个来源会话正在复制"可读性差（`SessionMergePanel.test.tsx:182`）；`retryAt` 直接展示原始时间串（`SessionMergePanel.tsx:206`）。
````

触发：串行测试名与构造不符
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：缺实际迟到GET构造，产品串行实现仍正确。

判定理由：缺实际迟到GET构造，产品串行实现仍正确。

代码：[sessionMergeStore.test.ts:487](../../../../../frontend/src/store/sessionMergeStore.test.ts#L487)

核验：代码静态核验及原文：见code_refs/lines

### R14-C44

**paused-or-completed用例只测paused** — 成立 / engineering / scope=new / 不入分；统一问题：store-terminal-test-gap；credit=None。

[原文 L217–L217](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L217)

````text
**前端（8）**：串行化用例名不副实且 `finishPolling` 是死代码（`sessionMergeStore.test.ts:487-508`，真正"先轮询后 resume"顺序未覆盖）；"paused or completed"用例只构造 paused（`:410-415`）；restore/decode 异常路径无测试（`sessionMergeStore.ts:60-72`）；锁集合变化触发 `session-lists-updated` 无直接断言（`:197-199`）；`protocolVersion?: number` 未收紧为 `1|2` 且未被消费（`:16`）；错误体采用分支不校验 `request` 字段（`:171-175`）；"五来源"用例断言"2 个来源会话正在复制"可读性差（`SessionMergePanel.test.tsx:182`）；`retryAt` 直接展示原始时间串（`SessionMergePanel.tsx:206`）。
````

触发：paused-or-completed用例只测paused
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：测试命名/覆盖建议。

判定理由：测试命名/覆盖建议。

代码：[sessionMergeStore.test.ts:410](../../../../../frontend/src/store/sessionMergeStore.test.ts#L410)

核验：代码静态核验及原文：见code_refs/lines

### R14-C45

**restore/decode异常缺测** — 成立 / engineering / scope=new / 不入分；统一问题：store-restore-test-gap；credit=None。

[原文 L217–L217](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L217)

````text
**前端（8）**：串行化用例名不副实且 `finishPolling` 是死代码（`sessionMergeStore.test.ts:487-508`，真正"先轮询后 resume"顺序未覆盖）；"paused or completed"用例只构造 paused（`:410-415`）；restore/decode 异常路径无测试（`sessionMergeStore.ts:60-72`）；锁集合变化触发 `session-lists-updated` 无直接断言（`:197-199`）；`protocolVersion?: number` 未收紧为 `1|2` 且未被消费（`:16`）；错误体采用分支不校验 `request` 字段（`:171-175`）；"五来源"用例断言"2 个来源会话正在复制"可读性差（`SessionMergePanel.test.tsx:182`）；`retryAt` 直接展示原始时间串（`SessionMergePanel.tsx:206`）。
````

触发：restore/decode异常缺测
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：测试缺口不计产品错误。

判定理由：测试缺口不计产品错误。

代码：[sessionMergeStore.ts:50](../../../../../frontend/src/store/sessionMergeStore.ts#L50)

核验：代码静态核验及原文：见code_refs/lines

### R14-C46

**锁变化列表事件缺测** — 成立 / engineering / scope=new / 不入分；统一问题：lock-event-test-gap；credit=None。

[原文 L217–L217](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L217)

````text
**前端（8）**：串行化用例名不副实且 `finishPolling` 是死代码（`sessionMergeStore.test.ts:487-508`，真正"先轮询后 resume"顺序未覆盖）；"paused or completed"用例只构造 paused（`:410-415`）；restore/decode 异常路径无测试（`sessionMergeStore.ts:60-72`）；锁集合变化触发 `session-lists-updated` 无直接断言（`:197-199`）；`protocolVersion?: number` 未收紧为 `1|2` 且未被消费（`:16`）；错误体采用分支不校验 `request` 字段（`:171-175`）；"五来源"用例断言"2 个来源会话正在复制"可读性差（`SessionMergePanel.test.tsx:182`）；`retryAt` 直接展示原始时间串（`SessionMergePanel.tsx:206`）。
````

触发：锁变化列表事件缺测
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：事件已实现，测试缺口。

判定理由：事件已实现，测试缺口。

代码：[sessionMergeStore.ts:197](../../../../../frontend/src/store/sessionMergeStore.ts#L197)

核验：代码静态核验及原文：见code_refs/lines

### R14-C47

**protocolVersion宽类型** — 成立 / engineering / scope=new / 不入分；统一问题：protocol-version-types；credit=None。

[原文 L217–L217](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L217)

````text
**前端（8）**：串行化用例名不副实且 `finishPolling` 是死代码（`sessionMergeStore.test.ts:487-508`，真正"先轮询后 resume"顺序未覆盖）；"paused or completed"用例只构造 paused（`:410-415`）；restore/decode 异常路径无测试（`sessionMergeStore.ts:60-72`）；锁集合变化触发 `session-lists-updated` 无直接断言（`:197-199`）；`protocolVersion?: number` 未收紧为 `1|2` 且未被消费（`:16`）；错误体采用分支不校验 `request` 字段（`:171-175`）；"五来源"用例断言"2 个来源会话正在复制"可读性差（`SessionMergePanel.test.tsx:182`）；`retryAt` 直接展示原始时间串（`SessionMergePanel.tsx:206`）。
````

触发：protocolVersion宽类型
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：类型维护建议。

判定理由：类型维护建议。

代码：[sessionMergeStore.ts:16](../../../../../frontend/src/store/sessionMergeStore.ts#L16)

核验：代码静态核验及原文：见code_refs/lines

### R14-C48

**错误体operation未校验request** — 成立 / engineering / scope=new / 不入分；统一问题：unvalidated-operation-shape；credit=None。

[原文 L217–L217](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L217)

````text
**前端（8）**：串行化用例名不副实且 `finishPolling` 是死代码（`sessionMergeStore.test.ts:487-508`，真正"先轮询后 resume"顺序未覆盖）；"paused or completed"用例只构造 paused（`:410-415`）；restore/decode 异常路径无测试（`sessionMergeStore.ts:60-72`）；锁集合变化触发 `session-lists-updated` 无直接断言（`:197-199`）；`protocolVersion?: number` 未收紧为 `1|2` 且未被消费（`:16`）；错误体采用分支不校验 `request` 字段（`:171-175`）；"五来源"用例断言"2 个来源会话正在复制"可读性差（`SessionMergePanel.test.tsx:182`）；`retryAt` 直接展示原始时间串（`SessionMergePanel.tsx:206`）。
````

触发：错误体operation未校验request
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：正常服务器DTO完整；未知代理非法JSON结构未证明为受支持响应。

判定理由：正常服务器DTO完整；未知代理非法JSON结构未证明为受支持响应。

代码：[sessionMergeStore.ts:171](../../../../../frontend/src/store/sessionMergeStore.ts#L171)

核验：代码静态核验及原文：见code_refs/lines

### R14-C49

**五来源fixture显示两来源** — 成立 / engineering / scope=new / 不入分；统一问题：panel-fixture-wording；credit=None。

[原文 L217–L217](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L217)

````text
**前端（8）**：串行化用例名不副实且 `finishPolling` 是死代码（`sessionMergeStore.test.ts:487-508`，真正"先轮询后 resume"顺序未覆盖）；"paused or completed"用例只构造 paused（`:410-415`）；restore/decode 异常路径无测试（`sessionMergeStore.ts:60-72`）；锁集合变化触发 `session-lists-updated` 无直接断言（`:197-199`）；`protocolVersion?: number` 未收紧为 `1|2` 且未被消费（`:16`）；错误体采用分支不校验 `request` 字段（`:171-175`）；"五来源"用例断言"2 个来源会话正在复制"可读性差（`SessionMergePanel.test.tsx:182`）；`retryAt` 直接展示原始时间串（`SessionMergePanel.tsx:206`）。
````

触发：五来源fixture显示两来源
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：测试可读性，不是UI真实数量错误。

判定理由：测试可读性，不是UI真实数量错误。

代码：[SessionMergePanel.test.tsx:182](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L182)

核验：代码静态核验及原文：见code_refs/lines

### R14-C50

**retryAt未本地化** — 成立 / engineering / scope=new / 不入分；统一问题：merge-diagnostic-display；credit=None。

[原文 L217–L217](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L217)

````text
**前端（8）**：串行化用例名不副实且 `finishPolling` 是死代码（`sessionMergeStore.test.ts:487-508`，真正"先轮询后 resume"顺序未覆盖）；"paused or completed"用例只构造 paused（`:410-415`）；restore/decode 异常路径无测试（`sessionMergeStore.ts:60-72`）；锁集合变化触发 `session-lists-updated` 无直接断言（`:197-199`）；`protocolVersion?: number` 未收紧为 `1|2` 且未被消费（`:16`）；错误体采用分支不校验 `request` 字段（`:171-175`）；"五来源"用例断言"2 个来源会话正在复制"可读性差（`SessionMergePanel.test.tsx:182`）；`retryAt` 直接展示原始时间串（`SessionMergePanel.tsx:206`）。
````

触发：retryAt未本地化
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：呈现偏好。

判定理由：呈现偏好。

代码：[SessionMergePanel.tsx:206](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L206)

核验：代码静态核验及原文：见code_refs/lines

### R14-C51

**其余artifact/usage/慢hook测试缺口** — 成立 / engineering / scope=new / 不入分；统一问题：merge-test-gaps；credit=None。

[原文 L257–L257](../zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md#L257)

````text
**缺口**（除 Major-3/4）：v2 `seal()` 磁盘/配额路径无直接测试；artifact 导出零覆盖；v2 路径"重复位置不灌模型"无直接断言；4183 字节回归缺"provider output=13,370 已报告"的组合分支（旧阈值已整体删除，风险低）；慢 hook/双操作并发发布无测试；`MERGE_CANCEL_CLEANUP` 等错误码无前端契约断言；测试对 3 个宿主环境变量敏感。
````

触发：其余artifact/usage/慢hook测试缺口
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：独立记录验证不足，不把未测等同功能错。

判定理由：独立记录验证不足，不把未测等同功能错。

代码：[session-merge-architecture-v2.md:440](../../../../session-merge-architecture-v2.md#L440)

核验：代码静态核验及原文：见code_refs/lines

## R15

[完整原报告](../zhikuncode/kimik3_session-merge-v2-code-review-report.md)

### R15-normal_function

**判断分：5/5**。[原文 L36–L61](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L36)。普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。

````text
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
````

### R15-boundary

**判断分：5/5**。[原文 L42–L59](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L42)。全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。

````text
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
````

### R15-release

**判断分：0/5**。[原文 L203–L225](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L203)。未明确阻止功能完成版合入发布，或将已有缺陷/语义未验收仅作可跟进声明；按冻结规则不算正确完成版判断。

````text
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
````

### R15-semantic

**判断分：5/5**。[原文 L168–L168](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L168)。明确真实模型语义验收未完成，stub 测试不能替代。

````text
文档 §9 的 13 个必测用例族基本有自动化对应（含 4,183 bytes + 13,370 output 的本次失败条件合成回归）。文档自承"真实模型语义验收尚未执行"——审查结论同样只覆盖结构/接线/预算/持久化正确性，**不覆盖真实模型的语义质量**。
````

### R15-C01

**普通请求严格隔离** — 成立 / global / scope=new / 不入分；统一问题：ordinary-path-isolation；credit=None。

[原文 L36–L61](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L36)

````text
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
````

触发：普通请求严格隔离
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：正文说明getter/record成本、低风险长期守护，普通功能无回归与代码相符。

判定理由：正文说明getter/record成本、低风险长期守护，普通功能无回归与代码相符。

代码：[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)；[HandoffContextService.java:33](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L33)

核验：代码静态核验及原文：见code_refs/lines

### R15-C02

**v2没有failed终态路径** — 规范歧义 / engineering / scope=new / 不入分；统一问题：paused-without-failed-state；credit=None。

[原文 L91–L91](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L91)

````text
- **D-1｜v2 操作不存在 `failed` 终态路径。** 文档 §5.1 定义 failed="已确认不可恢复的损坏；不能 resume，由用户 cancel 结束"，§6.3 要求"哈希不符/关键原件损坏……确认无法恢复才 failed"。实现中 execute 的异常出口一律 `progress.pause`（SessionMergeService.java:218-224），v2 行永远到不了 failed；Operation.canResume/canCancel 中 failed 分支为死代码（failed 仅来自 V026 旧行迁移）。实际后果可控（用户可 cancel），但状态机语义不完整。**建议二选一**：为哈希不符类不可恢复损坏补齐"重试 N 次后置 failed"路径并补测试；或修订文档明确 v2 一律 paused。
````

触发：v2没有failed终态路径
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：明确用户可cancel且建议修订文档，状态确认标准未定义；不据此认定核心合并必坏。

判定理由：明确用户可cancel且建议修订文档，状态确认标准未定义；不据此认定核心合并必坏。

代码：[SessionMergeService.java:226](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L226)；[session-merge-architecture-v2.md:352](../../../../session-merge-architecture-v2.md#L352)

核验：代码静态核验及原文：见code_refs/lines

### R15-C03

**其他任务文件混入工作区** — 范围外 / engineering / scope=out / 不入分；统一问题：unrelated-worktree-changes；credit=None。

[原文 L92–L92](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L92)

````text
- **D-8｜工作区混入不属于 v2 文件范围的前端改动**（即本次审查排除的 16 个文件）。文档 §10 明确前端只改 sessionMergeStore/SessionMergePanel 且"保持不改 Sidebar"等。这不是代码缺陷，而是**变更集卫生问题**：提交时必须拆分为独立 commit/PR，否则 v2 变更集不闭合、无法独立回滚。
````

触发：其他任务文件混入工作区
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：范围卫生建议，已排除文件不计本改动缺陷。

判定理由：范围卫生建议，已排除文件不计本改动缺陷。

代码：[session-merge-architecture-v2.md:479](../../../../session-merge-architecture-v2.md#L479)

核验：代码静态核验及原文：见code_refs/lines

### R15-C04

**来源变化重试次数少** — 成立 / engineering / scope=new / 不入分；统一问题：source-tree-retry-policy；credit=None。

[原文 L98–L98](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L98)

````text
| D-2 | 来源集合复核只重新取一次（文档允许最多两次），start 路径直接拒绝而非暂停 | 拒绝同样释放 token 且用户可重试，影响轻微 |
````

触发：来源变化重试次数少
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：保守拒绝，不复制不一致资料，可手动重发。

判定理由：保守拒绝，不复制不一致资料，可手动重发。

代码：[SessionMergeService.java:120](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L120)

核验：代码静态核验及原文：见code_refs/lines

### R15-C05

**缺统一只读事务但gate替代** — 成立 / engineering / scope=new / 不入分；统一问题：snapshot-read-transaction；credit=None。

[原文 L99–L99](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L99)

````text
| D-3 | 无"全部来源统一短只读事务"记录截止边界；实际隔离靠 gate 排他 token | 等价安全性成立；仅外部直接篡改库（文档声明不支持）才有窗口 |
````

触发：缺统一只读事务但gate替代
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：报告承认当前gate等价安全，非缺陷。

判定理由：报告承认当前gate等价安全，非缺陷。

代码：[MergePackageService.java:411](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L411)；[SessionMergeService.java:114](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L114)

核验：代码静态核验及原文：见code_refs/lines

### R15-C06

**多进程抢名额500** — 范围外 / conditional / scope=out / 不入分；统一问题：multi-instance-active-conflict；credit=None。

[原文 L100–L100](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L100)

````text
| D-4 | 占名额为"synchronized + 唯一索引兜底"两段式 | 单进程安全；多进程撞索引时用户得到未包装 500 而非 `MERGE_ACTIVE_EXISTS`，建议捕获 `DuplicateKeyException` 映射 |
````

触发：多进程抢名额500
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：报告明示单进程安全；多进程共享库不属本轮契约。

判定理由：报告明示单进程安全；多进程共享库不属本轮契约。

代码：[SessionMergeService.java:81](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L81)

核验：代码静态核验及原文：见code_refs/lines

### R15-C07

**unitId不含snapshotHash但inputHash包含** — 成立 / engineering / scope=new / 不入分；统一问题：unit-id-spelling；credit=None。

[原文 L101–L101](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L101)

````text
| D-5 | unitId 字面值不含 snapshotHash/处理器版本（为 `e<ordinal>`/`aggregate-L-O`） | 这些要素全部进入 input_hash 列并做冲突校验，语义等价、字面不符 |
````

触发：unitId不含snapshotHash但inputHash包含
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：报告明示语义等价，字面偏差不算缺陷。

判定理由：报告明示语义等价，字面偏差不算缺陷。

代码：[MergeSummaryService.java:79](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L79)

核验：代码静态核验及原文：见code_refs/lines

### R15-C08

**读取超时错误码被包装** — 成立 / functional / scope=new / 入分；统一问题：D13；credit=1。

[原文 L102–L102](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L102)

````text
| D-6 | 读取预算超时错误码被包装为 `HANDOFF_INVALID_RESOURCE`/`HANDOFF_READ_FAILED` | 原始码 `HANDOFF_READ_BUDGET_EXCEEDED` 保留在 message 中，信息未丢失但顶层 code 不直接 |
````

触发：读取超时错误码被包装
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：超时顶层码改变，message保留并不能满足明确code契约。

判定理由：超时顶层码改变，message保留并不能满足明确code契约。

核实范围：授权/读取支路把预算错误码包成INVALID_RESOURCE等泛码；read入口中断反例正常重抛。

代码：[HandoffReadService.java:43](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L43)；[HandoffReadService.java:55](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L55)；[HandoffReadTool.java:45](../../../../../backend/src/main/java/com/aicodeassistant/tool/impl/HandoffReadTool.java#L45)

核验：[reproductions/RankingReadReproTest.java](reproductions/RankingReadReproTest.java)

### R15-C09

**发布前缺真实读/最小入口自检** — 成立 / engineering / scope=new / 不入分；统一问题：publish-read-selfcheck-gap；credit=None。

[原文 L103–L103](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L103)

````text
| D-7 | 发布前缺"读接口/最小入口自检"的完整实现 | 以 verifyPrepared 全哈希链校验代替，影响低 |
````

触发：发布前缺真实读/最小入口自检
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：verifyPrepared仅hash链未调用project/read，测试/自检缺口成立；没有具体额外失败输入，不单列功能TP。

判定理由：verifyPrepared仅hash链未调用project/read，测试/自检缺口成立；没有具体额外失败输入，不单列功能TP。

代码：[SessionMergeService.java:204](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L204)；[session-merge-architecture-v2.md:402](../../../../session-merge-architecture-v2.md#L402)

核验：代码静态核验及原文：见code_refs/lines

### R15-C10

**legacy标记名不同但语义等价** — 成立 / engineering / scope=new / 不入分；统一问题：legacy-import-tag；credit=None。

[原文 L104–L104](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L104)

````text
| D-9 | 旧包收录标记名与文档不同（`legacy_package_origin_inferred` vs `legacy_import`） | 语义等价 |
````

触发：legacy标记名不同但语义等价
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：形式差异，不是资料错误。

判定理由：形式差异，不是资料错误。

代码：[MergePackageService.java:611](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L611)

核验：代码静态核验及原文：见code_refs/lines

### R15-C11

**恢复投影重复残片** — 证据不足 / conditional / scope=new / 不入分；统一问题：recovered-partial-projection-duplication；credit=None。

[原文 L105–L105](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L105)

````text
| D-10 | exportMessage 解析中途失败时同内容存在"部分投影+完整 raw"两份 | 恢复后投影可能与残留分片重复；去重按身份，语义无碍 |
````

触发：恢复投影重复残片
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：解析中途已移动raw可能只留extract记录并挂无ref gap，此时实际恢复被挡；未证明完成后重复路径，待复现。

判定理由：解析中途已移动raw可能只留extract记录并挂无ref gap，此时实际恢复被挡；未证明完成后重复路径，待复现。

代码：[MergePackageService.java:435](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L435)；[MergePackageService.java:502](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L502)

核验：代码静态核验及原文：见code_refs/lines

### R15-C12

**cancel清理扫描时机** — 成立 / engineering / scope=new / 不入分；统一问题：cleanup-scan；credit=None。

[原文 L106–L106](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L106)

````text
| D-11 | cancel 对已无 writer 的操作调用 cleanupFinished() 全量扫描 | 与"由新合并/启动清理"语义一致，无风险 |
````

触发：cancel清理扫描时机
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：取消后无writer清理自有终态包符合生命周期，无缺陷。

判定理由：取消后无writer清理自有终态包符合生命周期，无缺陷。

代码：[SessionMergeService.java:152](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L152)

核验：代码静态核验及原文：见code_refs/lines

### R15-C13

**Repository两个无状态实例** — 成立 / engineering / scope=new / 不入分；统一问题：injection-style；credit=None。

[原文 L123–L123](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L123)

````text
1. `SessionMergeService` 构造器内 `new MergeProgressRepository(...)`（:71）绕过 Spring 注入产生双实例——无状态故无害，但削弱一致性。
````

触发：Repository两个无状态实例
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：维护建议，不计并发错误。

判定理由：维护建议，不计并发错误。

代码：[SessionMergeService.java:70](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L70)

核验：代码静态核验及原文：见code_refs/lines

### R15-C14

**四参构造器把tool置null** — 不成立 / engineering / scope=new / 不入分；统一问题：handoff-test-constructor；credit=None。

[原文 L124–L124](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L124)

````text
2. `HandoffContextService` 4 参构造器将 tool 置 null（:22-24），误用于 configure 路径会 NPE——目前仅测试使用，建议私有化或加注释。
````

触发：四参构造器把tool置null
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：事实不成立：四参传null的是ImageResultExternalizer，五参构造器仍new HandoffReadTool；因此configure不因tool为null而NPE。此为测试构造器工程断言，不当产品FP。

判定理由：事实不成立：四参传null的是ImageResultExternalizer，五参构造器仍new HandoffReadTool；因此configure不因tool为null而NPE。此为测试构造器工程断言，不当产品FP。

代码：[HandoffContextService.java:22](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L22)；[HandoffContextService.java:31](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L31)

核验：代码静态核验及原文：见code_refs/lines

### R15-C15

**databaseResolver注入回退隐式** — 成立 / engineering / scope=new / 不入分；统一问题：package-root-resolver；credit=None。

[原文 L125–L125](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L125)

````text
3. `MergePackageService.databaseResolver` 字段注入可空 + PRAGMA 回退（:84-92），生产/测试双路径隐式切换，建议显式化。
````

触发：databaseResolver注入回退隐式
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：生产配置明确、测试回退PRAGMA，维护建议。

判定理由：生产配置明确、测试回退PRAGMA，维护建议。

代码：[MergePackageService.java:85](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L85)

核验：代码静态核验及原文：见code_refs/lines

### R15-C16

**重复全量哈希性能建议** — 成立 / engineering / scope=new / 不入分；统一问题：duplicate-handoff-projection；credit=None。

[原文 L126–L126](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L126)

````text
4. **性能注意（非缺陷）**：`HandoffContextService.project` 每轮对话对全部清单文件做全量 SHA-256 校验；QueryEngine 每轮因 prepareCompactionContext 再调一次 project（:1835），即每个用户请求约 2 次全包校验哈希。正确性优先的设计符合文档（"不引入全局缓存""读取前按绑定校验"），但包较大时每轮开销可观，建议后续在请求内复用一次校验结果。
````

触发：重复全量哈希性能建议
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：两次投影成立；并非全部asset内容每轮都hash，所举性能无量化，报告已标非缺陷。

判定理由：两次投影成立；并非全部asset内容每轮都hash，所举性能无量化，报告已标非缺陷。

代码：[HandoffReadService.java:69](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L69)；[QueryEngine.java:1835](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1835)

核验：代码静态核验及原文：见code_refs/lines

### R15-C17

**轮询禁用合并控制按钮** — 成立 / functional / scope=new / 入分；统一问题：D08；credit=1。

[原文 L140–L140](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L140)

````text
1. **`submitting` 被轮询与控制操作共用，造成按钮间歇性不可点**（store L141/L205/L230；panel L222/L225/L229）：活跃合并期间每 2s 轮询把 `submitting` 翻 true，"恢复合并"/"取消合并"/"重试查询"按钮随之出现几十~几百毫秒的不可点窗口，用户点击被静默吞掉。**这是前端最值得修的交互缺陷**，建议拆分 `polling`/`controlling` 两个标志，按钮仅跟随后者。
````

触发：轮询禁用合并控制按钮
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：同一submitting被GET翻true且按钮disabled，用户不能在慢poll期间触发控制，规格要求进度不阻挡取消。

判定理由：同一submitting被GET翻true且按钮disabled，用户不能在慢poll期间触发控制，规格要求进度不阻挡取消。

核实范围：轮询复用submitting禁用恢复/取消，程序cancel也等待GET结束；不表示后端永久不能取消。

代码：[sessionMergeStore.ts:144](../../../../../frontend/src/store/sessionMergeStore.ts#L144)；[SessionMergePanel.tsx:222](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L222)；[SessionMergePanel.tsx:225](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L225)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R15-C18

**全store订阅重渲染** — 成立 / engineering / scope=retained / 不入分；统一问题：panel-store-subscription；credit=None。

[原文 L141–L141](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L141)

````text
2. **全 store 订阅无 selector**（panel L17）：轮询驱动的 `submitting` 翻转引发整树重渲染。属既有模式，建议按 selector 分片订阅。
````

触发：全store订阅重渲染
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：既有模式，未测量性能回归；工程优化。

判定理由：既有模式，未测量性能回归；工程优化。

代码：[SessionMergePanel.tsx:17](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L17)

核验：代码静态核验及原文：见code_refs/lines

### R15-C19

**v2新增面板测试缺口** — 成立 / engineering / scope=new / 不入分；统一问题：panel-v2-test-gap；credit=None。

[原文 L142–L142](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L142)

````text
3. **新增 UI 零组件级测试覆盖**：恢复合并区（模型下拉+默认路径）、取消按钮、整理单元行、retryAt 行、`targetAvailable=false` 禁用态、paused/cancelled 状态行均无组件测试。
````

触发：v2新增面板测试缺口
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：缺直接控件测试成立，非产品错误。

判定理由：缺直接控件测试成立，非产品错误。

代码：[SessionMergePanel.test.tsx:1](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L1)

核验：代码静态核验及原文：见code_refs/lines

### R15-C20

**retryAt未本地化** — 成立 / engineering / scope=new / 不入分；统一问题：merge-diagnostic-display；credit=None。

[原文 L144–L144](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L144)

````text
**低级（节选）**：`retryAt` 原文输出 ISO-8601 未本地化；`validatedTerminal` 模块级 Set 只增不减；409 采纳分支 `body.operation` 无形状校验；`resume` 的 `expectedEpoch` 可选类型掩盖了"v2 服务端恒返回 runEpoch"的契约；串行化测试名与实际断言存在偏差；面板 fixture 仍用 v1 stage 值。
````

触发：retryAt未本地化
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：呈现偏好。

判定理由：呈现偏好。

代码：[SessionMergePanel.tsx:206](../../../../../frontend/src/components/session/SessionMergePanel.tsx#L206)

核验：代码静态核验及原文：见code_refs/lines

### R15-C21

**validatedTerminal只增不减** — 成立 / engineering / scope=new / 不入分；统一问题：terminal-cache-growth；credit=None。

[原文 L144–L144](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L144)

````text
**低级（节选）**：`retryAt` 原文输出 ISO-8601 未本地化；`validatedTerminal` 模块级 Set 只增不减；409 采纳分支 `body.operation` 无形状校验；`resume` 的 `expectedEpoch` 可选类型掩盖了"v2 服务端恒返回 runEpoch"的契约；串行化测试名与实际断言存在偏差；面板 fixture 仍用 v1 stage 值。
````

触发：validatedTerminal只增不减
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：每操作一短ID，未证明有限产品使用内造成内存问题。

判定理由：每操作一短ID，未证明有限产品使用内造成内存问题。

代码：[sessionMergeStore.ts:115](../../../../../frontend/src/store/sessionMergeStore.ts#L115)

核验：代码静态核验及原文：见code_refs/lines

### R15-C22

**409operation形状不验** — 成立 / engineering / scope=new / 不入分；统一问题：unvalidated-operation-shape；credit=None。

[原文 L144–L144](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L144)

````text
**低级（节选）**：`retryAt` 原文输出 ISO-8601 未本地化；`validatedTerminal` 模块级 Set 只增不减；409 采纳分支 `body.operation` 无形状校验；`resume` 的 `expectedEpoch` 可选类型掩盖了"v2 服务端恒返回 runEpoch"的契约；串行化测试名与实际断言存在偏差；面板 fixture 仍用 v1 stage 值。
````

触发：409operation形状不验
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：服务端契约完整，未知错误体属于防御强化。

判定理由：服务端契约完整，未知错误体属于防御强化。

代码：[sessionMergeStore.ts:243](../../../../../frontend/src/store/sessionMergeStore.ts#L243)

核验：代码静态核验及原文：见code_refs/lines

### R15-C23

**epoch可选类型** — 成立 / engineering / scope=new / 不入分；统一问题：optional-epoch-type；credit=None。

[原文 L144–L144](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L144)

````text
**低级（节选）**：`retryAt` 原文输出 ISO-8601 未本地化；`validatedTerminal` 模块级 Set 只增不减；409 采纳分支 `body.operation` 无形状校验；`resume` 的 `expectedEpoch` 可选类型掩盖了"v2 服务端恒返回 runEpoch"的契约；串行化测试名与实际断言存在偏差；面板 fixture 仍用 v1 stage 值。
````

触发：epoch可选类型
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：服务端v2始终提供，类型建议。

判定理由：服务端v2始终提供，类型建议。

代码：[sessionMergeStore.ts:16](../../../../../frontend/src/store/sessionMergeStore.ts#L16)

核验：代码静态核验及原文：见code_refs/lines

### R15-C24

**串行测试不足** — 成立 / engineering / scope=new / 不入分；统一问题：store-serialization-test-gap；credit=None。

[原文 L144–L144](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L144)

````text
**低级（节选）**：`retryAt` 原文输出 ISO-8601 未本地化；`validatedTerminal` 模块级 Set 只增不减；409 采纳分支 `body.operation` 无形状校验；`resume` 的 `expectedEpoch` 可选类型掩盖了"v2 服务端恒返回 runEpoch"的契约；串行化测试名与实际断言存在偏差；面板 fixture 仍用 v1 stage 值。
````

触发：串行测试不足
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：测试未造真实迟到GET，不代表产品串行失败。

判定理由：测试未造真实迟到GET，不代表产品串行失败。

代码：[sessionMergeStore.test.ts:487](../../../../../frontend/src/store/sessionMergeStore.test.ts#L487)

核验：代码静态核验及原文：见code_refs/lines

### R15-C25

**panel fixture仍v1stage** — 成立 / engineering / scope=new / 不入分；统一问题：panel-fixture-wording；credit=None。

[原文 L144–L144](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L144)

````text
**低级（节选）**：`retryAt` 原文输出 ISO-8601 未本地化；`validatedTerminal` 模块级 Set 只增不减；409 采纳分支 `body.operation` 无形状校验；`resume` 的 `expectedEpoch` 可选类型掩盖了"v2 服务端恒返回 runEpoch"的契约；串行化测试名与实际断言存在偏差；面板 fixture 仍用 v1 stage 值。
````

触发：panel fixture仍v1stage
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：兼容fixture可保留，补v2测试另列。

判定理由：兼容fixture可保留，补v2测试另列。

代码：[SessionMergePanel.test.tsx:1](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L1)

核验：代码静态核验及原文：见code_refs/lines

### R15-C26

**后端10类测试缺口** — 成立 / engineering / scope=new / 不入分；统一问题：merge-test-gaps；credit=None。

[原文 L172–L181](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L172)

````text
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
````

触发：后端10类测试缺口
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：含聚合兜底/asset/准入/并发/ref/漂移/入口/E状态/failed分支，全部保留为测试条目。

判定理由：含聚合兜底/asset/准入/并发/ref/漂移/入口/E状态/failed分支，全部保留为测试条目。

代码：[session-merge-architecture-v2.md:440](../../../../session-merge-architecture-v2.md#L440)

核验：代码静态核验及原文：见code_refs/lines

### R15-C27

**最小入口放不下已走正常容量错误的保证** — 不成立 / global / scope=new / 不入分；统一问题：handoff-capacity-safety-assertion；credit=None。

[原文 L79–L79](../zhikuncode/kimik3_session-merge-v2-code-review-report.md#L79)

````text
- **I. 运行时消费**：目标 E 事务内写标记；无标记不调用交接服务、不查合并表（`verifyNoInteractions` 用例）；入口≤min(2048, 总历史预算×10%)；连最小入口都放不下返回正常容量错误而非把合并改失败；REST 三入口+WS 入口仅 E 启用，普通/fork/Swarm 工具池不变。
````

触发：最小入口放不下已走正常容量错误的保证
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：前半运行隔离正确，但“正常容量错误”与project抛IllegalStateException冲突；作为具体错误安全保证记录，不能作为发现tiny预算缺陷的命中。

判定理由：前半运行隔离正确，但“正常容量错误”与project抛IllegalStateException冲突；作为具体错误安全保证记录，不能作为发现tiny预算缺陷的命中。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)

核验：代码静态核验及原文：见code_refs/lines

## R16

[完整原报告](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md)

### R16-normal_function

**判断分：5/5**。[原文 L68–L81](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L68)。普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。

````text
### 结论：**未发现对普通 / fork / Swarm 功能链路的破坏性影响。**

| 入口 | 是否受影响 | 证据 |
|---|---|---|
| 普通 QueryEngine 分支 | **否**（预算公式等价） | `QueryEngine.java:1811-1814`：`handoffOperationId==null` 时直接返回 `Projection(List.of(), 0)`，`reservedTokens=0`，`historyBudget == inputBudget`；`HandoffContextService.inject` 对空投影原样返回 |
| REST 三入口 | **否** | `QueryController.java:175-176 / 262-263 / 359-360`：仅 `isMerged(session.config())` 为真才调用 `configure`；普通请求不查合并表、不读包 |
| WebSocket | **否** | `WebSocketController.java:956-957`：同一 `isMerged` 门控；`session.config()` 取自已加载的 SessionData，无额外查询 |
| 工具池 / 子代理 | **否** | `HandoffReadTool` **不是** Spring `@Component`/`List<Tool>` Bean（`HandoffContextServiceTest.java:29` 断言）；`ToolRegistry` 中以 `isExplicitCoreTool("HandoffRead")` 登记仅作完整性断言，不进入 `getAllTools()`；`SubAgentExecutor` 未改动 |
| 图片注入 | **否** | `ImageRefInjector.java:96-98 / 269`：旧重载默认 `(path,hash)->false`，路径安全判定与改前等价；仅合并会话且投影非空时才启用 `boundHandoffAsset` |
| 压缩 / 历史 | **否** | `QueryLoopState` 标记 `@JsonIgnore` 无默认行为；投影插在 `CompactionHistory.forRequest` 之前且为独立 UserMessage，不进 tool_use/result 配对 |
| fork / Swarm 保存机制 | **否** | 相关类零 diff；合并侧只读已有 checkpoint/消息，缺口显式记入 gaps |
| 孤立测试证明 | 有 | `QueryEngineUnitTest.handoffProjectionOnlyRunsForExplicitlyMergedSessions(false)`：`verifyNoInteractions(handoff)` + payload 不含 HandoffRead + phase2−phase1==0 + 不写回 state |

**唯一对普通路径的新增成本**：`isMerged()` 一次 Map 查询、`handoffProjection` 一次空判断、字段注入一个可选 Bean。可忽略。
````

### R16-boundary

**判断分：5/5**。[原文 L81–L81](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L81)。全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。

````text
**唯一对普通路径的新增成本**：`isMerged()` 一次 Map 查询、`handoffProjection` 一次空判断、字段注入一个可选 Bean。可忽略。
````

### R16-release

**判断分：0/5**。[原文 L278–L286](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L278)。未明确回答当前能否作为功能完成版合入发布；允许提交/推送和披露语义未验收本身并非错误，不据此捏造赞成正式发布。

````text
### 判断：**有条件可以提交 GitHub**（无 P0/P1 阻塞）

| 类别 | 内容 |
|---|---|
| **建议提交前处理（或 PR 明示接受）** | ① 锁集合含 descendants 或前端去掉 status 门控（P2 #1/#2）② 补 Panel 控制断言（P2 #3） |
| **非阻塞** | P3 五条（风格 / NPE 防御 / `failAttempt` / `SQL.split` / 通知文案） |
| **必须在提交信息/PR 中如实声明** | 「真实模型接续开发语义验收尚未执行」；stub 测试不得表述为语义验收闭环 |
| **工作区卫生** | 通过；未跟踪文件均为应入库的源码/测试/规格文档 |
| **额外提醒** | 忽略清单中的 `frontend/e2e/message-copy-all.spec.ts` **当前不存在**，若属本次交付物需确认是否漏提交或清单笔误 |
````

### R16-semantic

**判断分：5/5**。[原文 L263–L263](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L263)。明确真实模型语义验收未完成，stub 测试不能替代。

````text
4. **真实模型接续开发语义验收未执行**（规格首段/第 9 节明示为未完成项，不得用 stub 冒充）
````

### R16-C01

**普通链路无破坏及轻量开销** — 成立 / global / scope=new / 不入分；统一问题：ordinary-path-isolation；credit=None。

[原文 L68–L81](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L68)

````text
### 结论：**未发现对普通 / fork / Swarm 功能链路的破坏性影响。**

| 入口 | 是否受影响 | 证据 |
|---|---|---|
| 普通 QueryEngine 分支 | **否**（预算公式等价） | `QueryEngine.java:1811-1814`：`handoffOperationId==null` 时直接返回 `Projection(List.of(), 0)`，`reservedTokens=0`，`historyBudget == inputBudget`；`HandoffContextService.inject` 对空投影原样返回 |
| REST 三入口 | **否** | `QueryController.java:175-176 / 262-263 / 359-360`：仅 `isMerged(session.config())` 为真才调用 `configure`；普通请求不查合并表、不读包 |
| WebSocket | **否** | `WebSocketController.java:956-957`：同一 `isMerged` 门控；`session.config()` 取自已加载的 SessionData，无额外查询 |
| 工具池 / 子代理 | **否** | `HandoffReadTool` **不是** Spring `@Component`/`List<Tool>` Bean（`HandoffContextServiceTest.java:29` 断言）；`ToolRegistry` 中以 `isExplicitCoreTool("HandoffRead")` 登记仅作完整性断言，不进入 `getAllTools()`；`SubAgentExecutor` 未改动 |
| 图片注入 | **否** | `ImageRefInjector.java:96-98 / 269`：旧重载默认 `(path,hash)->false`，路径安全判定与改前等价；仅合并会话且投影非空时才启用 `boundHandoffAsset` |
| 压缩 / 历史 | **否** | `QueryLoopState` 标记 `@JsonIgnore` 无默认行为；投影插在 `CompactionHistory.forRequest` 之前且为独立 UserMessage，不进 tool_use/result 配对 |
| fork / Swarm 保存机制 | **否** | 相关类零 diff；合并侧只读已有 checkpoint/消息，缺口显式记入 gaps |
| 孤立测试证明 | 有 | `QueryEngineUnitTest.handoffProjectionOnlyRunsForExplicitlyMergedSessions(false)`：`verifyNoInteractions(handoff)` + payload 不含 HandoffRead + phase2−phase1==0 + 不写回 state |

**唯一对普通路径的新增成本**：`isMerged()` 一次 Map 查询、`handoffProjection` 一次空判断、字段注入一个可选 Bean。可忽略。
````

触发：普通链路无破坏及轻量开销
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：门控、空投影及工具身份支持限定结论；新增Map/空判断明确披露。

判定理由：门控、空投影及工具身份支持限定结论；新增Map/空判断明确披露。

代码：[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)；[HandoffContextService.java:33](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L33)

核验：代码静态核验及原文：见code_refs/lines

### R16-C02

**锁集合不含后代** — 规范歧义 / conditional / scope=new / 不入分；统一问题：descendant-lock-dto；credit=None。

[原文 L184–L187](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L184)

````text
#### P2-1 锁集合不完整
- **位置**：`SessionMergeService.get`
- **描述**：`lockedSourceSessionIds` 只对 `request.sourceSessionIds()` 求交，但 `lockSources` 对 `packages.descendants(...)` 全树取 token。子任务实际被锁却不在 DTO 中，前端按规格「只看 lockedSourceSessionIds」会误判子任务可删/可用。
- **建议**：与 `lockSources` 共用同一 descendants 列表，或对全树求交。
````

触发：锁集合不含后代
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：实际持有后代token但DTO只列根，来源选项只普通；用户可见后代误判需要消费路径证明，规范可解释为来源根列表。

判定理由：实际持有后代token但DTO只列根，来源选项只普通；用户可见后代误判需要消费路径证明，规范可解释为来源根列表。

代码：[SessionMergeService.java:114](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L114)；[SessionMergeService.java:263](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L263)

核验：代码静态核验及原文：见code_refs/lines

### R16-C03

**paused/failed锁被前端门控忽略** — 成立 / functional / scope=new / 入分；统一问题：D15；credit=1。

[原文 L189–L192](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L189)

````text
#### P2-2 前端锁门控残留
- **位置**：`frontend/src/store/sessionMergeStore.ts:28-32`
- **描述**：`selectMergeSourceIds` 在 `status==='preparing'` 时才返回锁集合；若 paused/failed 行带非空 `lockedSourceSessionIds`（服务端异常或中间态）会被忽略。
- **建议**：去掉 status 门控，直接 `operation?.lockedSourceSessionIds ?? []`。
````

触发：paused/failed锁被前端门控忽略
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：pause与finally释放之间可出现paused+非空真实锁，selector抹掉；后端仍保护，短窗口UI不一致。

判定理由：pause与finally释放之间可出现paused+非空真实锁，selector抹掉；后端仍保护，短窗口UI不一致。

核实范围：cancelled/paused短窗口真实锁仍在但selector隐藏；后端gate保护仍在。

代码：[SessionMergeService.java:226](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L226)；[sessionMergeStore.ts:28](../../../../../frontend/src/store/sessionMergeStore.ts#L28)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R16-C04

**面板v2UI控制测试缺口** — 成立 / engineering / scope=new / 不入分；统一问题：panel-v2-test-gap；credit=None。

[原文 L194–L197](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L194)

````text
#### P2-3 Panel 层 UI 测试缺口
- **位置**：`frontend/src/components/session/SessionMergePanel.test.tsx`
- **描述**：规格 9「UI 与控制」要求覆盖恢复/取消按钮、单元数、暂停原因、targetAvailable 禁用打开；目前仅 store 层覆盖 resume/cancel 序列化。
- **建议**：补 1～2 个 Panel 用例断言 canResume/canCancel 渲染与 openTarget 双条件。
````

触发：面板v2UI控制测试缺口
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：只是缺测试，不是实现坏。

判定理由：只是缺测试，不是实现坏。

代码：[SessionMergePanel.test.tsx:1](../../../../../frontend/src/components/session/SessionMergePanel.test.tsx#L1)

核验：代码静态核验及原文：见code_refs/lines

### R16-C05

**每轮重复投影IO** — 成立 / engineering / scope=new / 不入分；统一问题：duplicate-handoff-projection；credit=None。

[原文 L199–L202](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L199)

````text
#### P2-4 合并路径投影重复计算
- **位置**：`QueryEngine.java:841`（主路径）与 `:1835`（`prepareCompactionContext`）
- **描述**：合并会话每次请求会调用两次 `project()`，各自做包哈希校验与 brief 读取。非合并路径 early-return 无影响；合并路径放大 15s 读取截止窗口的抖动。
- **建议**：在同一请求内缓存 `Projection`，供两处共用。
````

触发：每轮重复投影IO
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：重复校验成立，无测量失效证据且规格不禁止，保留优化。

判定理由：重复校验成立，无测量失效证据且规格不禁止，保留优化。

代码：[QueryEngine.java:841](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L841)；[QueryEngine.java:1835](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1835)

核验：代码静态核验及原文：见code_refs/lines

### R16-C06

**迁移分号切分脆弱** — 成立 / engineering / scope=new / 不入分；统一问题：migration-sql-split；credit=None。

[原文 L207–L207](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L207)

````text
| V026 `SQL.split(";")` | 脆弱；建议语句列表或 `ScriptUtils` |
````

触发：迁移分号切分脆弱
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：当前语句安全，未来维护建议。

判定理由：当前语句安全，未来维护建议。

代码：[V026_ExtendSessionMerges.java:124](../../../../../backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java#L124)

核验：代码静态核验及原文：见code_refs/lines

### R16-C07

**failAttempt completed覆盖error可疑** — 成立 / engineering / scope=new / 不入分；统一问题：attempt-outcome-semantics；credit=None。

[原文 L208–L208](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L208)

````text
| `failAttempt` 改写 completed | 语义可疑 |
````

触发：failAttempt completed覆盖error可疑
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：校验失败须更新outcome，usage保留，未证明误记费用。

判定理由：校验失败须更新outcome，usage保留，未证明误记费用。

代码：[MergeProgressRepository.java:197](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L197)

核验：代码静态核验及原文：见code_refs/lines

### R16-C08

**Conflict message null导致Map.ofNPE** — 证据不足 / conditional / scope=new / 不入分；统一问题：null-conflict-message；credit=None。

[原文 L209–L209](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L209)

````text
| `SessionMergeController` `Map.of` NPE | message 空值防御 |
````

触发：Conflict message null导致Map.ofNPE
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：所有生产Conflict构造点均明确非null中文消息；需外部误调用才触发，防御性建议。

判定理由：所有生产Conflict构造点均明确非null中文消息；需外部误调用才触发，防御性建议。

代码：[SessionMergeController.java:33](../../../../../backend/src/main/java/com/aicodeassistant/controller/SessionMergeController.java#L33)；[SessionMergeService.java:290](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java#L290)

核验：代码静态核验及原文：见code_refs/lines

### R16-C09

**FQCN与匿名类风格** — 成立 / engineering / scope=new / 不入分；统一问题：injection-style；credit=None。

[原文 L210–L210](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L210)

````text
| FQCN/匿名类风格 | 可读性 |
````

触发：FQCN与匿名类风格
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：可维护性建议。

判定理由：可维护性建议。

代码：[OperationAnalyzerRegistry.java:104](../../../../../backend/src/main/java/com/aicodeassistant/authorization/OperationAnalyzerRegistry.java#L104)

核验：代码静态核验及原文：见code_refs/lines

### R16-C10

**合并通知同key可叠加** — 成立 / functional / scope=new / 入分；统一问题：D22；credit=1。

[原文 L211–L211](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L211)

````text
| 通知 cancelled 用 error 级 + 「合并暂停或失败」文案 | UX 不一致；通知去重依赖 `changed &&` 门控，无 key 去重 |
````

触发：合并通知同key可叠加
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：changed门控只挡完全相同DTO；操作再暂停或paused进度变化仍可多push，通知store不去重。级别和文案单独不加分。

判定理由：changed门控只挡完全相同DTO；操作再暂停或paused进度变化仍可多push，通知store不去重。级别和文案单独不加分。

核实范围：5秒通知仍在期间，新增paused→resume→paused可推送同key通知两份。

代码：[sessionMergeStore.ts:200](../../../../../frontend/src/store/sessionMergeStore.ts#L200)；[notificationStore.ts:32](../../../../../frontend/src/store/notificationStore.ts#L32)

核验：[reproductions/rankingProtocolRepro.test.ts](reproductions/rankingProtocolRepro.test.ts)

### R16-C11

**绑定授权与路径安全保证** — 成立 / global / scope=new / 不入分；统一问题：handoff-read-authority；credit=None。

[原文 L215–L219](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L215)

````text
- `safeFile` 拒绝绝对路径 / `..` / 越界 normalize / symlink（`MergePackageService.java:304-321`）
- HandoffRead 经 `AuthorizationSubjectResolver.resolve(currentRunId).rootSessionId()` 绑定，模型不能选其他 session/operation
- 同名 MCP/动态工具不继承权限（`instanceof` 分发 + 测试覆盖）
- `HandoffRead` 位于 `AuthorizationService` PLAN 拒绝分支之前的 `safeRead`（限 `handoff-read-v1` + `RiskClass.SAFE` + `READ_RESOURCE`）
- 外部图片仅当 `isBoundAsset`（包内登记 + 哈希匹配 + 路径落在本包）才放宽工作区限制
````

触发：绑定授权与路径安全保证
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：对于读取工具的路径检查与根绑定限定成立，不据此扩张原件导出器全局安全。

判定理由：对于读取工具的路径检查与根绑定限定成立，不据此扩张原件导出器全局安全。

代码：[HandoffReadService.java:49](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L49)；[MergePackageService.java:304](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L304)

核验：代码静态核验及原文：见code_refs/lines

### R16-C12

**合成回归未具名** — 成立 / engineering / scope=new / 不入分；统一问题：synthetic-regression-name；credit=None。

[原文 L261–L261](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L261)

````text
2. 规格 9「本次失败条件」的**合成 4,183 bytes / provider output=13,370** 专测未见具名用例（已有「无 3500 拒绝 / >16 单元」等价覆盖，但未按失败日志数字标注为合成回归）
````

触发：合成回归未具名
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：报告承认已有等价覆盖，具名是可读性建议；不存在新产品问题。

判定理由：报告承认已有等价覆盖，具名是可读性建议；不存在新产品问题。

代码：[MergeSummaryServiceTest.java:1](../../../../../backend/src/test/java/com/aicodeassistant/session/merge/MergeSummaryServiceTest.java#L1)

核验：代码静态核验及原文：见code_refs/lines

### R16-C13

**active路由优先级未集成测** — 成立 / engineering / scope=new / 不入分；统一问题：active-route-test-gap；credit=None。

[原文 L262–L262](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L262)

````text
3. `GET /api/session-merges/active` 与 `GET /{operationId}` 路由优先级无集成测试
````

触发：active路由优先级未集成测
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：Spring静态路由优先于路径变量，缺直接集成用例不算功能错。

判定理由：Spring静态路由优先于路径变量，缺直接集成用例不算功能错。

代码：[SessionMergeController.java:17](../../../../../backend/src/main/java/com/aicodeassistant/controller/SessionMergeController.java#L17)

核验：代码静态核验及原文：见code_refs/lines

### R16-C14

**遗留helper与风格建议** — 成立 / engineering / scope=new / 不入分；统一问题：compressed-code-style；credit=None。

[原文 L235–L238](../%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md#L235)

````text
1. 大量 FQCN / 单行压缩风格（`handoffRead` 匿名类、`QueryController`/`QueryEngine` 字段注入），可读性与可维护性偏弱
2. `failAttempt`、`SQL.split(";")` 等边界语义未注释清楚
3. 合并路径下 `project()` 重复调用属隐性性能/截止风险
4. `packagePath` 遗留 helper 与新 `snapshotPath` 双轨，需长期靠注释/测试约束
````

触发：遗留helper与风格建议
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：维护性建议，不重复计算功能发现。

判定理由：维护性建议，不重复计算功能发现。

代码：[MergePackageService.java:95](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L95)

核验：代码静态核验及原文：见code_refs/lines

## R17

[完整原报告](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md)

### R17-normal_function

**判断分：5/5**。[原文 L36–L54](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L36)。普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。

````text
结论:**无负面影响**。证据逐条如下:

1. **基线文件零改动**。`git status` 确认 `SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner`、`SessionExecutionGate`、`ToolRegistry`、`FileRead/Grep`、`ImageResultExternalizer`、`SessionController` 均未修改,与规格第 10 节"保持不改"清单及文档声明一致。

2. **QueryEngine 的门控是数学上等价的**。`handoffProjection()` 在 `state.getHandoffOperationId()==null` 时直接返回 `Projection(List.of(),0)`(QueryEngine.java:1810 附近),因此普通会话:
   - phase1 预算 `historyBudget = inputBudget - 0`,数字完全不变;
   - 图片注入走原重载(空投影时 `handoff.messages().isEmpty()` 分支);
   - `HandoffContextService.inject()` 空投影时返回原 list 引用;
   - phase2 仍用原 `inputBudget` 校验(满足规格 7.1 第 3 点"避免重复扣减")。

   单元测试 `handoffProjectionOnlyRunsForExplicitlyMergedSessions(false)` 断言了 `verifyNoInteractions(handoff)`、payload 不含 HandoffRead、state.messages 不被污染——三点都有测试兜底。

3. **普通请求不查合并表、不带新工具**。三个 REST 入口与 WebSocket 入口都以 `isMerged(session.config())`(纯内存元数据判断,null 安全)为前置条件;`HandoffReadTool` 不是 Spring Bean,只在 `configure()` 中对已验证绑定的 E 追加,allow/deny 列表被尊重(测试 `explicitAllowListDoesNotGainAnUnrequestedTool`)。子代理/fork/Swarm 路径不经过这些入口,天然隔离。

4. **授权收紧而非放宽**。`AuthorizationService.java:158` 的一行改动只在分析器 ID 恰为 `handoff-read-v1` 时生效,而该分析器通过 `instanceof HandoffReadTool`(内置类身份)选取——同名 MCP 或动态伪装工具会落到 `static-or-remote-v1`/`mcp-v1` 且**不会**获得 SAFE 自动放行,有专门测试覆盖(`handoffReadRequiresBuiltinIdentityAndRechecksTheRootBinding`、`planAllowsAnalyzedHandoffReadButNotAnUnknownToolWithTheSameName`)。PLAN 模式可用,符合规格 7.3。

5. **QueryLoopState** 新增字段带 `@JsonIgnore`,不参与序列化、无默认行为。

一个已确认的小坑(非缺陷):WebSocketController 的 `executeQueryInternal` 重构后 `sessionMetadata` 初始为不可变 `Map.of()`,仅在 loadSession 失败时保持空——此时 `isMerged` 为 false,行为安全。
````

### R17-boundary

**判断分：5/5**。[原文 L12–L17](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L12)。全文38—54门控与64全局迁移说明公共改动；与R03/R10同标准。零影响措辞过满，不当作绝对零风险证明。

````text
**达到发布标准,建议放行。**

- 未发现任何阻断级风险;
- 非合并链路的影响经代码与测试双重验证为**零**;
- 实测全量测试全绿(后端 2,975 项 0 失败,前端 894 项通过,生产构建通过);
- 存在 4 个建议尽快跟进的 P2 改进项和若干 P3 打磨项,均不阻塞提交。
````

### R17-release

**判断分：0/5**。[原文 L12–L17](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L12)。明确建议发布/达到发布标准，与尚存确认缺陷和未完成真实语义验收矛盾。

````text
**达到发布标准,建议放行。**

- 未发现任何阻断级风险;
- 非合并链路的影响经代码与测试双重验证为**零**;
- 实测全量测试全绿(后端 2,975 项 0 失败,前端 894 项通过,生产构建通过);
- 存在 4 个建议尽快跟进的 P2 改进项和若干 P3 打磨项,均不阻塞提交。
````

### R17-semantic

**判断分：5/5**。[原文 L94–L94](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L94)。明确真实模型语义验收未完成，stub 测试不能替代。

````text
第 9 节要求的**真实模型接续开发语义样本尚未执行**(displayName 联调样本、封存后代码变化样本等)。当前全部通过的是 stub 数据/隔离/接线断言,按文档要求不得将其表述为"真实模型接续验收已通过"。
````

### R17-C01

**普通链路隔离具体断言** — 成立 / global / scope=new / 不入分；统一问题：ordinary-path-isolation；credit=None。

[原文 L38–L54](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L38)

````text
1. **基线文件零改动**。`git status` 确认 `SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner`、`SessionExecutionGate`、`ToolRegistry`、`FileRead/Grep`、`ImageResultExternalizer`、`SessionController` 均未修改,与规格第 10 节"保持不改"清单及文档声明一致。

2. **QueryEngine 的门控是数学上等价的**。`handoffProjection()` 在 `state.getHandoffOperationId()==null` 时直接返回 `Projection(List.of(),0)`(QueryEngine.java:1810 附近),因此普通会话:
   - phase1 预算 `historyBudget = inputBudget - 0`,数字完全不变;
   - 图片注入走原重载(空投影时 `handoff.messages().isEmpty()` 分支);
   - `HandoffContextService.inject()` 空投影时返回原 list 引用;
   - phase2 仍用原 `inputBudget` 校验(满足规格 7.1 第 3 点"避免重复扣减")。

   单元测试 `handoffProjectionOnlyRunsForExplicitlyMergedSessions(false)` 断言了 `verifyNoInteractions(handoff)`、payload 不含 HandoffRead、state.messages 不被污染——三点都有测试兜底。

3. **普通请求不查合并表、不带新工具**。三个 REST 入口与 WebSocket 入口都以 `isMerged(session.config())`(纯内存元数据判断,null 安全)为前置条件;`HandoffReadTool` 不是 Spring Bean,只在 `configure()` 中对已验证绑定的 E 追加,allow/deny 列表被尊重(测试 `explicitAllowListDoesNotGainAnUnrequestedTool`)。子代理/fork/Swarm 路径不经过这些入口,天然隔离。

4. **授权收紧而非放宽**。`AuthorizationService.java:158` 的一行改动只在分析器 ID 恰为 `handoff-read-v1` 时生效,而该分析器通过 `instanceof HandoffReadTool`(内置类身份)选取——同名 MCP 或动态伪装工具会落到 `static-or-remote-v1`/`mcp-v1` 且**不会**获得 SAFE 自动放行,有专门测试覆盖(`handoffReadRequiresBuiltinIdentityAndRechecksTheRootBinding`、`planAllowsAnalyzedHandoffReadButNotAnUnknownToolWithTheSameName`)。PLAN 模式可用,符合规格 7.3。

5. **QueryLoopState** 新增字段带 `@JsonIgnore`,不参与序列化、无默认行为。

一个已确认的小坑(非缺陷):WebSocketController 的 `executeQueryInternal` 重构后 `sessionMetadata` 初始为不可变 `Map.of()`,仅在 loadSession 失败时保持空——此时 `isMerged` 为 false,行为安全。
````

触发：普通链路隔离具体断言
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：代码可支持普通功能无新增回归；不支持总体影响绝对零，判断维另扣。

判定理由：代码可支持普通功能无新增回归；不支持总体影响绝对零，判断维另扣。

代码：[QueryEngine.java:1811](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L1811)；[HandoffContextService.java:33](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L33)

核验：代码静态核验及原文：见code_refs/lines

### R17-C02

**attempt completed改error语义困惑** — 成立 / engineering / scope=new / 不入分；统一问题：attempt-outcome-semantics；credit=None。

[原文 L79–L79](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L79)

````text
1. **`MergeProgressRepository.failAttempt` 的第二条 UPDATE**(MergeProgressRepository.java:199)会把已记为 `completed` 的 attempt 强制改写为 `error`。它只影响计费明细的语义表达,不改单元/操作状态,但这个"completed→error"覆写在语义上令人困惑,建议删除或注释说明触发场景。
````

触发：attempt completed改error语义困惑
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：调用完成但schema验证失败时应记error，usage不丢，注释建议成立而非功能缺陷。

判定理由：调用完成但schema验证失败时应记error，usage不丢，注释建议成立而非功能缺陷。

代码：[MergeSummaryService.java:224](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L224)；[MergeProgressRepository.java:197](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeProgressRepository.java#L197)

核验：代码静态核验及原文：见code_refs/lines

### R17-C03

**取消暂停通知error级别** — 成立 / engineering / scope=new / 不入分；统一问题：merge-toast-severity；credit=None。

[原文 L80–L80](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L80)

````text
2. **前端取消通知级别错误**:sessionMergeStore 中非 completed 终态统一映射 `level:'error'`,"合并已取消"/暂停也弹错误样式,语气不当。一行修复(cancelled → info/warning)。
````

触发：取消暂停通知error级别
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：事实成立，纯UX级别选择，未发现功能后果。

判定理由：事实成立，纯UX级别选择，未发现功能后果。

代码：[sessionMergeStore.ts:200](../../../../../frontend/src/store/sessionMergeStore.ts#L200)

核验：代码静态核验及原文：见code_refs/lines

### R17-C04

**单元brief直接采用JSON** — 成立 / engineering / scope=new / 不入分；统一问题：single-unit-json-brief；credit=None。

[原文 L81–L81](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L81)

````text
3. **单摘要单元时 brief 是原始 JSON**:当提取单元只剩 1 个时,`handoff.md` 直接拼 Detail JSON 而非整理过的摘要文本(MergeSummaryService.prepare 聚合循环 `current.size()==1` 分支)。信息完整可读,但不符合"有界启动摘要"的体验预期,建议对最终层强制一次聚合或做可读渲染。
````

触发：单元brief直接采用JSON
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：JSON仍为结构化交接且有header和2048上限；规格不禁止启动摘要含JSON，可读呈现建议。

判定理由：JSON仍为结构化交接且有header和2048上限；规格不禁止启动摘要含JSON，可读呈现建议。

代码：[MergeSummaryService.java:89](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L89)；[MergeSummaryService.java:145](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L145)

核验：代码静态核验及原文：见code_refs/lines

### R17-C05

**复制变化不自动重读一次** — 规范歧义 / engineering / scope=retained / 不入分；统一问题：copy-change-retry；credit=None。

[原文 L82–L82](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L82)

````text
4. **`copyFile` 无"变化重读一次"**:规格 4.3 第 4 步要求托管文件复制变化"最多重读一次,仍变化则暂停";当前实现直接抛 SOURCE_CHANGED 暂停。是保守方向的偏差(更少重试、更易暂停),建议按规格补一次重读或记录该偏差。
````

触发：复制变化不自动重读一次
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：现有copyFile一致性变化立即copy_failed，v2转暂停，确少一次可选重读但保守不漏资料且可恢复。helper基线已存在；规范称“最多”不强制必须重试一次。

判定理由：现有copyFile一致性变化立即copy_failed，v2转暂停，确少一次可选重读但保守不漏资料且可恢复。helper基线已存在；规范称“最多”不强制必须重试一次。

代码：[MergePackageService.java:970](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L970)；[MergePackageService.java:576](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java#L576)

核验：代码静态核验及原文：见code_refs/lines

### R17-C06

**压缩式代码风格** — 成立 / engineering / scope=new / 不入分；统一问题：compressed-code-style；credit=None。

[原文 L86–L86](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L86)

````text
- 新增合并链路文件的压缩式代码风格(无空格、单字母变量)与仓库既有规范差异显著,影响可维护性,建议提交后做一次格式化统一。
````

触发：压缩式代码风格
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：可维护性，不计产品缺陷。

判定理由：可维护性，不计产品缺陷。

代码：[MergeSummaryService.java:63](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java#L63)

核验：代码静态核验及原文：见code_refs/lines

### R17-C07

**字段注入风格** — 成立 / engineering / scope=new / 不入分；统一问题：injection-style；credit=None。

[原文 L87–L87](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L87)

````text
- `QueryController`/`WebSocketController` 用字段 `@Autowired` 注入 `HandoffContextService`(其余依赖都是构造器注入),风格不一致。
````

触发：字段注入风格
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：维护建议。

判定理由：维护建议。

代码：[QueryController.java:72](../../../../../backend/src/main/java/com/aicodeassistant/controller/QueryController.java#L72)

核验：代码静态核验及原文：见code_refs/lines

### R17-C08

**tiny入口走通用错误而非容量通道** — 成立 / functional / scope=new / 入分；统一问题：D09；credit=0.5。

[原文 L88–L88](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L88)

````text
- `handoffProjection` 抛出的 `HANDOFF_CONTEXT_BUDGET_TOO_SMALL`/`HANDOFF_UNAVAILABLE` 以 IllegalStateException 形式穿透到控制器,极端容量场景下 E 的请求会得到通用错误而非规格 7.1 期望的"正常容量错误"通道;触发条件苛刻(历史预算需 < 约 1,300 token,或包文件 I/O 损坏),且只影响 E 自身,建议后续映射到容量错误响应。
````

触发：合并E入口预算不足
预期：容量错误
实际：通用run失败

判定理由：project超最小预算抛IllegalStateException，未走ContextBudget现有通道；包损坏不可混同容量错误。；统一复核：同R02/R05/R13：真实tiny预算触发正确，但QueryEngine捕获project异常并返回error，REST控制器正常ResponseEntity.ok；不是异常穿透/必然500。按同规则半命中并半份归因错误。

核实范围：E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。

代码：[HandoffContextService.java:74](../../../../../backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java#L74)；[QueryEngine.java:364](../../../../../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java#L364)

核验：[reproductions/RankingQueryReproTest.java](reproductions/RankingQueryReproTest.java)

### R17-C09

**大目录校验可能耗尽15秒** — 成立 / engineering / scope=new / 不入分；统一问题：handoff-large-catalog-performance；credit=None。

[原文 L89–L89](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L89)

````text
- `HandoffReadService` 每次调用都对全部清单/详情文件做全量 SHA-256(规格允许),包体增长到数百 MB 后 15 秒预算可能吃紧,目前无需处理,留意即可。
````

触发：大目录校验可能耗尽15秒
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：预算按规格要求，报告也称目前无需处理；无量化回归，工程风险不计。

判定理由：预算按规格要求，报告也称目前无需处理；无量化回归，工程风险不计。

代码：[HandoffReadService.java:69](../../../../../backend/src/main/java/com/aicodeassistant/session/merge/HandoffReadService.java#L69)

核验：代码静态核验及原文：见code_refs/lines

### R17-C10

**SQL分号切分维护风险** — 成立 / engineering / scope=new / 不入分；统一问题：migration-sql-split；credit=None。

[原文 L90–L90](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L90)

````text
- V026 用 `SQL.split(";")` 切分语句——当前 SQL 无字面量分号所以安全,但这是脆弱模式。
````

触发：SQL分号切分维护风险
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：当前安全，未来维护风险。

判定理由：当前安全，未来维护风险。

代码：[V026_ExtendSessionMerges.java:124](../../../../../backend/src/main/java/com/aicodeassistant/config/database/V026_ExtendSessionMerges.java#L124)

核验：代码静态核验及原文：见code_refs/lines

### R17-C11

**通知去重已全部落实的保证** — 不成立 / global / scope=new / 不入分；统一问题：merge-toast-dedup-safety-assertion；credit=None。

[原文 L71–L71](../%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md#L71)

````text
| 8 前端 | active 接口恢复(204 语义)、`lockedSourceSessionIds` 作为唯一锁来源、不再把保存状态伪装成 preparing、epoch resume、无版本 cancel、终态校验缓存停止轮询、通知去重、`targetAvailable` 门控打开目标——全部落地且有 8 个新增 store 测试对应 |
````

触发：通知去重已全部落实的保证
预期：按引用规范或报告主张核验；工程建议不自动等于产品缺陷。
实际：changed只能去相同DTO；重复暂停或pausedDTO变化仍重复同key，notificationStore仅push。具体保证过强，记录但不是发现此问题的TP。

判定理由：changed只能去相同DTO；重复暂停或pausedDTO变化仍重复同key，notificationStore仅push。具体保证过强，记录但不是发现此问题的TP。

代码：[sessionMergeStore.ts:200](../../../../../frontend/src/store/sessionMergeStore.ts#L200)；[notificationStore.ts:32](../../../../../frontend/src/store/notificationStore.ts#L32)

核验：代码静态核验及原文：见code_refs/lines
