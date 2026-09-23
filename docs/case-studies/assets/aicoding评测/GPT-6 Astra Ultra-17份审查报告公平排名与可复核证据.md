# GPT-6 Astra Ultra：17 份审查报告公平排名与可复核证据

**这是一份可复核、接受异议的单次报告质量评议，不是 AICoding 产品或模型的综合能力榜。** 评分和证据已经交叉核查，但尚未经参评方确认；评审者参与过被审代码实现、看过旧排名，因此不是盲评。

**[本报告分析全程录屏：50:42，74.00 MB](./钉钉录屏_2026-09-23_074629.mp4?raw=true)**（播放器与说明见第 8 节）。

## 先看排名

评价对象：2026-09-22 会话合并 v2 审查任务的 **17 份最终报告**。满分 100：缺陷识别 80，整体判断 20。模型/档位只按报告或画面标签标注，不认证底层模型身份。

| 名次 | AICoding 工具 | 模型 / 档位标签 | 得分 / 100 | 原报告 |
|---:|---|---|---:|---|
| **1** | DeepSeekHarness | deepseekV4.1flashmax | **60.7** | [R02](DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md) |
| **2** | zhikuncode | deepseekV4.1flash | **58.5** | [R14](zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md) |
| **3** | Codex | Astra · 极高 | **54.5** | [R01](Codex/Astra%E6%9E%81%E9%AB%98_%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A_2026-09-22.md) |
| **4** | zhikuncode | Qwen3.8max | **46.7** | [R13](zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md) |
| **5** | QoderIDE | Sonus | **43.8** | [R07](QoderIDE/Sonus-session-merge-v2-review.md) |
| **6** | KimiCode | KimiK3 | **43.3** | [R03](KimiCode/KimiK3-session-merge-v2-review.md) |
| **7** | Qoder | qwen3.8max · 极高新版本 | **42.9** | [R05](Qoder/qwen3.8max%E6%9E%81%E9%AB%98%E6%96%B0%E7%89%88%E6%9C%AC-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md) |
| **8** | Cursor | Grok4.7 | **37.0** | [R11](cursor/Grok4.7-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E5%AE%8C%E6%95%B4%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md) |
| **9** | zhikuncode | GLM5.3 | **32.8** | [R12](zhikuncode/GLM5.3-session-merge-v2-code-review.md) |
| **并列 10** | Qoder | kimiK3 | **26.2** | [R04](Qoder/kimik3-session-merge-v2-review.md) |
| **并列 10** | zhikuncode | kimiK3 | **26.2** | [R15](zhikuncode/kimik3_session-merge-v2-code-review-report.md) |
| **并列 12** | TraeCode | kimiK3 | **22.6** | [R10](TraeCode/kimik3-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md) |
| **并列 12** | 小米 mimo（目录标识） | 未单独注明版本 | **22.6** | [R16](%E5%B0%8F%E7%B1%B3mimo/session-merge-v2-code-review.md) |
| **14** | 智普 ZCode | GLM5.3 | **18.8** | [R17](%E6%99%BA%E6%99%AEZCode/%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md) |
| **15** | TraeCode | Qwen3.8Max | **15.0** | [R09](TraeCode/Qwen3.8Max-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md) |
| **16** | QoderIDE | Cantus | **10.0** | [R06](QoderIDE/Cantus-zhikuncode-session-merge-v2-%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A-2026-09-22.md) |
| **17** | QoderIDE | 极致模型（档位名） | **5.0** | [R08](QoderIDE/%E6%9E%81%E8%87%B4%E6%A8%A1%E5%9E%8B-%E4%BC%9A%E8%AF%9D%E5%90%88%E5%B9%B6v2%E4%BB%A3%E7%A0%81%E5%AE%A1%E6%9F%A5%E6%8A%A5%E5%91%8A.md) |

**怎样读这张榜：**

- 主规则下 R02 第一、R14 第二；换用预设的 27 组权重，**9 组 R02 独占第一，18 组 R02/R14 并列第一**。不能据此宣称 R02 明显或普遍优于 R14。
- 第 5–7 名仅相差 0.9 分；R13 在不同方案中位于第 3–8 名。相邻名次不代表已证明的能力差距。
- 两组同分分别并列第 10、第 12，后续名次顺延；低分只表示未充分命中本轮确认缺陷与判断要求，不表示该工具整体能力差。

具体分项见[计分明细](#score-details)，每份报告的原文、发现、误报、遗漏和待定见第 6 节；证据与复算脚本均公开。

## 这份报告能经得起哪些质疑

**计算和引用有依据可以回应；事实裁定、评价口径和外推范围仍允许合理质疑。** “可复算”证明给定台账下的分数算得一致，不能自动证明台账的每项裁定客观唯一。公开时应以这份评议的范围为限，不能宣传成“没有争议的权威能力榜”。

| 参评方可能提出的质疑 | 当前能证明什么 | 仍不能证明什么 |
|---|---|---|
| 是否凭印象排名、算错或漏列报告？ | 17 份均在榜；408 条台账有原文位置；主榜和 27 组情景有独立公式复算。 | 算术正确不等于全部事实判定无误。 |
| 是否所有人收到完全相同的任务和预算？ | 各报告的范围/方法自述已列出，可核查当前冻结材料。 | 没有完整的同提示词、同上下文、同权限、同预算证明；不能比较单位成本或模型本体能力。 |
| 评审是否有立场或偏袒？ | 已披露参与实现、看过旧榜，并公开裁定修正；参评者中也包含同体系的 Codex/Astra 报告 R01。 | 同一模型体系的子代理复核不是独立第三方盲评，不能排除共同偏差。 |
| 为何只认这 22 类、为何权重是 4/2/1？ | 每类有代码/复现依据；未入分条目和排除理由保留，权重在算分前冻结。 | 已核实集合不保证穷尽；缺陷/建议边界、严重度、半命中及具体安全保证的 FP 例外仍包含判断。冻结不等于没有其他合理规则。 |
| “可提交”却没明确说“不可正式发布”，为何是 0 分？ | 这是冻结规则中的“完成版发布”维；未回答和错误已分开记录，没有当作同一种误报。 | 这只能说明未满足本轮该评分要求；完整初始提示未核齐时，不能断言每位参评者都违反了自己的委托。该解释影响 R04/R09/R10/R16 等报告。 |
| 27 组名次稳定，是否就证明谁更强？ | 只证明保持事实裁定不变、改变这几组权重时的结果。 | 没有覆盖缺陷集合、事实判定或措辞理解变化，也不是重复实验或统计置信区间。 |

本轮评分主要衡量**缺陷识别与四项整体判断**，没有量化报告的可读性、修复建议质量、审查效率等全部价值。公平使用的方式是允许参评方按 claim ID 提供反例，修正有误裁定并保留版本；更强的能力结论需要另做任务条件一致的独立评测。以上是证据强度说明，本次没有因此回调分数或更改冻结规则。

## 1. 披露、范围与材料冻结

评审者参与过被审代码实现，并已阅读旧 Opus 排名；**这不是盲评，也不是外部独立第三方审计**。使用并行子代理对报告分组通读、争议代码复核和方法复核，主评审统一裁定。这些代理属于同一评审体系，交叉复核有助于发现错误，但不能代替不同评审者的盲评。名称中的 GPT-6 Astra Ultra 是本次产物前缀，不是评分加分项。

- 当前分支：`aicoding-eval-0923`；冻结提交：`207fe6d0d159db1b575af243dbf3c77536377431`。
- 原改动基线：`b98e18721169436f8f35373a4120c530f54310d5`。核验范围为原审查的 34 个文件：33 个代码/测试文件和 1 份架构文档。
- [冻结清单](GPT-6%20Astra%20Ultra-evidence/manifest.json)保存 34 个文件、17 份报告、旧排名与两段视频的 SHA-256；[代码差异](GPT-6%20Astra%20Ultra-evidence/reviewed-change.patch)及[差异哈希](GPT-6%20Astra%20Ultra-evidence/change-freeze.json)固定被审改动。没有把排除的其他前端改动混入评分。
- [17 份报告索引](GPT-6%20Astra%20Ultra-evidence/reports.json)与[评测条件核查](GPT-6%20Astra%20Ultra-evidence/evaluation-conditions.md)逐份链接原材料。报告自述有隔离副本、全量测试、定向测试、并行代理数等差异；**未证实所有参评者同提示词、同上下文、同预算、同权限或同底层模型版本**。当前代码哈希不反向证明历史每次读取的工作树完全一致。
- 保存原 17 份报告和 Opus 排名；报告生成与复核阶段只新增报告与证据，没有修改业务实现，也未提交或推送 Git。后续按用户单独授权进行的 Git 发布不属于评分过程。

这些限制意味着：可以比较交付报告在统一核验标准下的质量；不能据此解释成控制变量实验中的模型能力差距，也不能比较成本或速度。

## 2. 规则先冻结，事实再核验，最后算分

[原始冻结规则](GPT-6%20Astra%20Ultra-evidence/rules.md)于 UTC `2026-09-22T23:53:57.578091+00:00` 保存，SHA-256 为 `76ac8ff9792b1696d024eee1d6de1136384ca3d0a01e010cfcb928b8b36fe361`。[规则冻结记录](GPT-6%20Astra%20Ultra-evidence/rules-freeze.json)、[计分前裁定冻结](GPT-6%20Astra%20Ultra-evidence/adjudication-freeze.json)和[修正台账](GPT-6%20Astra%20Ultra-evidence/claims/adjudication-changes.json)可核查。没有因首次排名结果更改权重。时间戳为本地生成记录，不是外部公证的预注册。

**主榜满分 100：缺陷识别 80，普通链路功能判断 5，影响边界表述 5，完成版发布判断 5，真实模型语义验收判断 5。**

缺陷按可证明后果、触发前提及可恢复性分严重/一般/轻微，权重 4/2/1，不照搬各报告 P1/P2 标签，不猜测发生频率。严重包括关键证据遗漏与资料边界越界；一般是较窄且可恢复的功能错误；轻微是资料仍正确的显示、诊断或错误分类问题。

```text
TP = 去重后正确发现的权重之和
FN = 本轮已核实新增缺陷总权重 − TP
FP = 去重后确定错误指控的权重之和
缺陷分 = 80 × 2TP / (2TP + FP + FN)
总分 = 缺陷分 + 四项判断分
```

准确写出真实触发与后果即可完整命中，无须自己跑测试。真实触发及后果正确、但明确写错根因或传播路径，按同规则计半 TP、半 FP、半 FN；仅说中相似现象而触发不同不计半分。同根因和后果只算一次，不能靠拆条目增加分数。

判定区分成立、不成立、证据不足、规范歧义、范围外。工程/测试建议、明确条件性风险、撤回的指控另标性质；“建议成立”不代表产品缺陷入分。待定和规范歧义既不计 TP 也不罚 FP。整体结论不额外作误报双罚；具体安全路径的错误保证仍核验，例如 R13 对 symlink 复制边界的具体错误论证。

判断维每项正确 5、明确错误/未回答/未消解冲突 0，并公开原因。允许保存阶段成果或 Draft PR，同时禁止完成版发布，不扣分。“未发现普通链路回归”不等于“证明任何环境绝对零影响”。全文有公共改动或验证边界时，不孤立抓一个“零”字扣分。

字数、条目数量、测试数量、页面美观、模型名称均不直接加分。主分按十进制四舍五入保留一位小数，同显示分数采用并列竞赛名次，例如 1、1、3。

<a id="score-details"></a>

## 3. 计分明细：分数如何得出

本轮通读 **17 份、4,050 行报告，形成 408 条核验记录**。最终入分的是 **22 类已核实新增缺陷，总权重 40**：严重 3 类、一般 9 类、轻微 10 类。它是本轮找到并确认的集合，**不是完整标准答案**。资料损坏本来允许暂停、纯建议及原有问题都保留在台账中，没有为了排名删除。

TP/FP/FN 是加权数，不是问题条数。错误归因与待定的逐报告数量保留在主榜 JSON/CSV 和台账中；待定含工程建议，不能直接比较成错误率。主榜用于看名次，下表用于核算，不再把所有指标挤在同一张表。

下表的报告编号跳转到该报告的核验台账；阅读参评原报告请使用文首主榜的“原报告”列。台账内的原文行号链接会打开 GitHub 源码视图并定位到对应行。

| 核验台账 | 缺陷分 /80 | 判断分 /20 | 命中权重 TP | 错误权重 FP | 遗漏权重 FN | 权重变化名次 |
|---|---:|---:|---:|---:|---:|---:|
| [R02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02) | 40.7 | 20 | 14.0 | 1.0 | 26.0 | 1 |
| [R14](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14) | 38.5 | 20 | 13.0 | 1.0 | 27.0 | 1–2 |
| [R01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01) | 34.5 | 20 | 11 | 0 | 29 | 3–4 |
| [R13](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13) | 26.7 | 20 | 10.0 | 10.0 | 30.0 | 3–8 |
| [R07](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r07) | 23.8 | 20 | 7 | 0 | 33 | 4–7 |
| [R03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03) | 23.3 | 20 | 7 | 1 | 33 | 4–6 |
| [R05](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05) | 22.9 | 20 | 7.0 | 2.0 | 33.0 | 6–7 |
| [R11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11) | 17.0 | 20 | 5 | 2 | 35 | 8–9 |
| [R12](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12) | 17.8 | 15 | 5 | 0 | 35 | 6–9 |
| [R04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r04) | 11.2 | 15 | 3 | 0 | 37 | 10 |
| [R15](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r15) | 11.2 | 15 | 3 | 0 | 37 | 10 |
| [R10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r10) | 7.6 | 15 | 2 | 0 | 38 | 12–13 |
| [R16](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r16) | 7.6 | 15 | 2 | 0 | 38 | 10–12 |
| [R17](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r17) | 3.8 | 15 | 1.0 | 1.0 | 39.0 | 14 |
| [R09](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r09) | 0.0 | 15 | 0 | 0 | 40 | 15 |
| [R06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r06) | 0.0 | 10 | 0 | 0 | 40 | 16 |
| [R08](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r08) | 0.0 | 5 | 0 | 0 | 40 | 17 |

机器结果：[主榜 JSON](GPT-6%20Astra%20Ultra-evidence/results/main.json)、[CSV](GPT-6%20Astra%20Ultra-evidence/results/main.csv)、[命中矩阵](GPT-6%20Astra%20Ultra-evidence/results/hit-matrix.md)。准确率和覆盖率保存在 JSON；没有有效产品发现的报告缺陷分为 0，但正确的整体判断仍可得分。

最高分并不高，主要因为没有任何一份报告覆盖本轮联合核实集合的大部分权重。这个分母随着新证据可能变化，分数不是传统考试的“及格线”，也不能解读为产品能力百分比。

人工抽算：R02 的 TP=14、FP=1、FN=26，`80×28/55+20=60.727…→60.7`；R13 为 `80×20/(20+10+30)+20=46.666…→46.7`；R17 为 `80×2/(2+1+39)+15=18.809…→18.8`。

## 4. 本轮已核实缺陷集合

严重度的共同依据是下表限定的后果。代码定位、触发/预期/实际、原文命中和核验来源均在 [defects.json](GPT-6%20Astra%20Ultra-evidence/defects.json) 与 [全量台账](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md)。表中“实跑”包括观察性断言；“静态”没有冒充完整端到端测试。

|编号|权重|确认的缺陷与边界|核验|命中原文记录|
|---|---:|---|---|---|
|D01|4|恢复投影将thinking/供应商私有字段送入提取；与首次过滤不一致。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/MergeReviewReproTest.java)|[R01-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c01)、[R07-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r07-c01)|
|D02|4|顶层toolUseResult未入版本哈希/文字索引；原checkpoint容器仍保留，不能称原件彻底丢失。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/MergeReviewReproTest.java)|[R01-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c02)|
|D03|4|历史文件引用的中间目录symlink穿过允许的来源目录边界，复制合成外部文件。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingProtocolReproTest.java)|[R02-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c01)|
|D04|2|已修正解析配置仍被gap/ref关联校验阻止；不等于所有坏原件应可恢复。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingProtocolReproTest.java)|[R02-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c02)、[R03-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c02)、[R05-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c01)、[R11-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11-c03)、[R13-C15](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c15)、[R14-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c04)|
|D05|2|已保存非关键GBK日志原件已复制，仍因不能UTF8解析阻断合并。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingProtocolReproTest.java)|[R03-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c01)|
|D06|2|token估算变化越过1536阈值时遗留既有pending聚合单元；换回条件可恢复。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingProtocolReproTest.java)|[R07-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r07-c02)|
|D07|2|本地旧终态遮蔽服务端新active；关闭旧结果可绕过。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/sessionMergeReviewRepro.test.ts)|[R01-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c04)、[R14-C22](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c22)|
|D08|2|轮询复用submitting禁用恢复/取消，程序cancel也等待GET结束；不表示后端永久不能取消。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/rankingProtocolRepro.test.ts)|[R02-C25](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c25)、[R05-C06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c06)、[R13-C23](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c23)、[R14-C26](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c26)、[R15-C17](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r15-c17)|
|D09|2|E最小入口超预算走通用QueryResult.error，未走容量错误通道；不是必然HTTP500。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingQueryReproTest.java)|[R02-C11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c11)、[R04-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r04-c03)、[R05-C07](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c07)、[R10-C11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r10-c11)、[R11-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11-c04)、[R12-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c03)、[R13-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c10)、[R14-C14](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c14)、[R17-C08](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r17-c08)|
|D10|2|历史外部引用含NUL时Path.of抛错，中止封存而不是记外部缺口。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingProtocolReproTest.java)|[R13-C18](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c18)|
|D11|2|有效WebP封存到无扩展副本后，在已测环境失去MIME识别。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingAssetReproTest.java)|[R02-C20](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c20)|
|D12|2|约12MiB合法PNG获成功image_ref但视觉未注入；tool/injector上限不一致。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingAssetReproTest.java)|[R14-C42](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c42)|
|D13|1|授权/读取支路把预算错误码包成INVALID_RESOURCE等泛码；read入口中断反例正常重抛。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingReadReproTest.java)|[R02-C09](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c09)、[R03-C06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c06)、[R05-C31](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c31)、[R13-C16](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c16)、[R14-C16](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c16)、[R15-C08](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r15-c08)|
|D14|1|已复制托管文件还被计外部缺口；资料本体存在。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/MergeReviewReproTest.java)|[R01-C05](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c05)|
|D15|1|cancelled/paused短窗口真实锁仍在但selector隐藏；后端gate保护仍在。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/rankingProtocolRepro.test.ts)|[R02-C26](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c26)、[R07-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r07-c03)、[R11-C06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11-c06)、[R12-C06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c06)、[R13-C20](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c20)、[R14-C21](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c21)、[R16-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r16-c03)|
|D16|1|搜索摘录UTF16 substring可能切断合法emoji；原文不变。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingReadReproTest.java)|[R03-C24](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c24)|
|D17|1|内嵌图片复制配额报通用准备失败；普通文件相同配额有明确COPY_INCOMPLETE。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/RankingReadReproTest.java)|[R02-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c04)|
|D18|1|操作保存后模型能力配置不可用，worker重新select抛中文异常被压成通用准备失败。|[静态复核](GPT-6%20Astra%20Ultra-evidence/edge-adjudication.md)|[R04-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r04-c04)|
|D19|1|操作 result 仅含 warningCount，不含 warnings 明细，合并面板无法展示缺口明细；包中原记录仍在。|[静态复核](GPT-6%20Astra%20Ultra-evidence/edge-adjudication.md)|[R13-C54](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c54)|
|D20|1|控制接口非JSON错误响应显示JSON解析异常，掩盖可操作错误信息。|[静态定位](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c10)|[R12-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c10)、[R14-C25](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c25)|
|D21|1|读取cancel状态后publish抢先完成，CAS失败被映射STALE而非ALREADY_COMPLETED；原子性仍在。|[静态复核](GPT-6%20Astra%20Ultra-evidence/edge-adjudication.md)|[R03-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c10)|
|D22|1|5秒通知仍在期间，新增paused→resume→paused可推送同key通知两份。|[实跑](GPT-6%20Astra%20Ultra-evidence/reproductions/rankingProtocolRepro.test.ts)|[R05-C27](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c27)、[R12-C12](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c12)、[R14-C24](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c24)、[R16-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r16-c10)|

去重说明：legacy 工具结果的版本折叠和提取遗漏同源，合为 D02；错误码压平的不同分支合为 D13。D04 的 gap/ref 恢复协议与 D05 非关键附件不应阻断的策略可独立修复，分别计分。旧终态目标可用性陈旧属于既存后果，未进入集合；发现新 active 的承诺由新增接口和流程引入，计 D07。

## 5. 影响排名的争议与统一裁定

1. **极小预算不是必然 HTTP 500。** `project` 抛异常后，QueryEngine 捕获并经 handler/QueryResult.error 返回，REST 控制器通常仍返回 OK 结果体。本轮引擎消费测试验证这一点，HTTP 返回依据另作静态核验，未声称做了完整 REST E2E。R02/R05/R13/R14/R17 写错传播路径，统一半命中。损坏绑定在 configure 阶段失败是另一条安全拒绝路径，不能与预算混为一谈。
2. **恢复协议有缺口，不等于所有暂停都不可恢复。** 夹具启用已有 Jackson 单引号解析选项后，同一 raw 已可解析，缺 ref 仍令恢复提前拒绝；提高物化上限的对照却能成功。解析选项在夹具中设置，不是现有 UI 恢复开关；不要求系统神奇修复坏原件。R02/R03/R05/R11/R13/R14 的相同协议发现统一一般级命中。
3. **两种“换模型暂停”不能混算。** R07 找到估算阈值变化遗留 pending 聚合单元，已复现且换回可恢复；R11 把原因明确写成模型改变分片宽度/input_hash，实际宽度固定，属于不同触发的错误机制，计 FP，不借相似关键词得半分。
4. **“仅图片关键需求仍发布”留作规范歧义。** 原断言确实复现 completed，但原件保留、后续有 asset 入口；“没有先视觉提取”是否已等于“现有能力无法理解关键资料”仍需明确约定。R01 这条不奖也不罚。绿色/红色测试都不能自行定义需求。
5. **WebP 和大图阈值按实测范围陈述。** WebP 是当前无扩展副本和运行环境的识别缺口，非所有平台永远不支持。大 PNG 获成功 ref 但未注入，最早实际阻断为 Base64 单图预算，另有较小的文件上限；R14 正确指出两个组件的容量契约不兼容，未虚构其先后次序。
6. **条件性防御建议不是确定误报。** R02 epoch 孤儿 worker 缺乏可达证明；R05 的 cancel 返回 204、异常旧 DB 绕过 UNIQUE 都明确带条件，当前支持范围内未发生，不计 FP。聚合 sourceId 空也不自动等于证据永久丢失。
7. **提交与发布按全文区分。** R13 明确“现在还不能发”，发布维得 5，不因它漏审安全问题再扣一次。R04/R09/R10/R16 只明确允许提交/推送，完成版发布边界未明确，记未回答而非捏造“赞成正式发布”。R08/R17 则明确建议发布，属于判断错误。
8. **边界措辞对所有报告一致。** R17 全文列普通门控与全局迁移，与 R03/R10 同口径得 5；不等于认可“零风险”字面断言。R08 明确说所有新增代码路径都在守卫之后，与迁移/启动路径矛盾且未限定，边界项为 0。

上述修正均可在[计分前方法审计](GPT-6%20Astra%20Ultra-evidence/method-audit.md)、[最后一轮代码/基线复核](GPT-6%20Astra%20Ultra-evidence/edge-adjudication.md)与[裁定变化记录](GPT-6%20Astra%20Ultra-evidence/claims/adjudication-changes.json)逐条定位。

## 6. 每份报告的发现、错误、遗漏和判断

以下“遗漏”只相对本轮 22 类已核实集合，不断言报告没有其他价值。完整工程建议、已撤回说法和不入分原因在各报告台账分节保留。判断四元组顺序为：普通功能 / 影响边界 / 完成版发布 / 真实语义验收，每项 0 或 5。

### R01 Codex / Astra 极高

- 正确发现：[R01-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c01) 恢复投影绕过归档字段过滤（D01）；[R01-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c02) 旧工具结果版本折叠且未进入投影（D02）；[R01-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c04) 旧终态缓存阻止active发现（D07）；[R01-C05](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c05) 已复制路径仍计为外部缺口（D14）。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D03、D04、D05、D06、D08、D09、D10、D11、D12、D13、D15、D16、D17、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R01-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c03)、[R01-C08](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01-c08)。
- 判断质量：5 / 5 / 5 / 5；normal_function：结论限定为未发现普通主链路新增回归，与静态门控一致。；boundary：明确后台active请求与共享SQLite/磁盘、迁移及压力验证边界，拒绝绝对零风险。；release：允许Draft/开发分支，明确不作为已完成验收正式版本。；semantic：明确真实模型未执行且不以stub替代。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r01)。

### R02 DeepSeekHarness / deepseekV4.1flashmax

- 正确发现：[R02-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c01) 引用文件中间symlink穿透复制边界（D03）；[R02-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c02) 阻断gap引用与恢复协议不一致（D04）；[R02-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c04) 内嵌图片复制配额丢失可操作错误分类（D17）；[R02-C09](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c09) 读预算错误码压平为授权错误（D13）；[R02-C20](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c20) 无扩展副本WebP无法读取（D11）；[R02-C25](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c25) 取消等待在飞轮询（D08）；[R02-C26](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c26) 非preparing持锁状态UI未显示（D15）。
- 误报 / 错误归因：[R02-C11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c11) 小窗口入口容量未分类且声称HTTP500（D09，半命中）。
- 遗漏：D01、D02、D05、D06、D07、D09（漏半份）、D10、D12、D14、D16、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R02-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c03)、[R02-C05](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c05)、[R02-C07](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c07)、[R02-C12](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c12)、[R02-C13](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c13)、[R02-C17](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c17)、[R02-C29](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02-c29)。
- 判断质量：5 / 5 / 5 / 5；normal_function：以未发现破坏表述，门控和普通保存隔离与代码一致。；boundary：明确迁移和启动同步清理/续跑全局影响，未将普通分支隔离扩张为无共享影响。；release：明确正确性问题未闭合，不宣称完成版达标。虽然措辞要求修完才提交更严，不误放完成版。；semantic：明确stub不能替代真实模型语义验收。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r02)。

### R03 KimiCode / KimiK3

- 正确发现：[R03-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c01) 非关键非UTF8文本附件阻断整个合并（D05）；[R03-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c02) 消息解析和非法图片gap缺少可恢复记录ref（D04）；[R03-C06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c06) authorize把截止/中断错误包装为非法资源（D13）；[R03-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c10) 取消与发布竞态返回stale而非already-completed（D21）；[R03-C24](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c24) 搜索命中摘要可能切断UTF16代理对（D16）。
- 误报 / 错误归因：[R03-C07](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c07) read入口也吞掉InterruptedIOException。
- 遗漏：D01、D02、D03、D06、D07、D08、D09、D10、D11、D12、D14、D15、D17、D18、D19、D20、D22。
- 待定 / 规范歧义：[R03-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c03)、[R03-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c04)、[R03-C11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c11)、[R03-C12](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c12)、[R03-C14](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c14)、[R03-C16](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c16)、[R03-C20](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03-c20)。
- 判断质量：5 / 5 / 5 / 5；normal_function：普通入口守卫、原持久化文件与工具池隔离符合独立代码核验；合并来源的取消显示问题不等于无关普通请求回归。；boundary：全文明确静态元数据守卫、公共图片/授权接点与V026全环境迁移边界；不把“零开销”口头概括单独当绝对零风险声明。；release：明确当前未达标准，先修功能缺陷并补覆盖，不把结构测试当完成版。；semantic：明确真实模型接续语义未执行且stub不能替代，并允许阶段提交。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r03)。

### R04 Qoder / kimiK3

- 正确发现：[R04-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r04-c03) 极小入口预算走通用失败而非容量错误（D09）；[R04-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r04-c04) 模型配置失效被归为通用准备失败（D18）。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D01、D02、D03、D04、D05、D06、D07、D08、D10、D11、D12、D13、D14、D15、D16、D17、D19、D20、D21、D22。
- 待定 / 规范歧义：[R04-C09](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r04-c09)、[R04-C11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r04-c11)。
- 判断质量：5 / 5 / 0 / 5；normal_function：普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。；boundary：全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。；release：未明确回答当前能否作为功能完成版合入发布；允许提交/推送和披露语义未验收本身并非错误，不据此捏造赞成正式发布。；semantic：明确真实模型语义验收未完成，stub 测试不能替代。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r04)。

### R05 Qoder / qwen3.8max 极高新版本

- 正确发现：[R05-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c01) P1-1 gap引用堵死恢复（D04）；[R05-C06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c06) P2-2轮询暂时禁用恢复取消（D08）；[R05-C27](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c27) P2-17通知同key重复（D22）；[R05-C31](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c31) P2-21a读预算错误码压平（D13）。
- 误报 / 错误归因：[R05-C33](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c33) P2-22a缺sha256导致NPE；[R05-C07](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c07) P2-3容量未分类并错误推断REST500（D09，半命中）。
- 遗漏：D01、D02、D03、D05、D06、D07、D09（漏半份）、D10、D11、D12、D14、D15、D16、D17、D18、D19、D20、D21。
- 待定 / 规范歧义：[R05-C05](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c05)、[R05-C08](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c08)、[R05-C09](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c09)、[R05-C13](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c13)、[R05-C16](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c16)、[R05-C17](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c17)、[R05-C18](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c18)、[R05-C20](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c20)、[R05-C21](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c21)、[R05-C22](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c22)、[R05-C24](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c24)、[R05-C26](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c26)、[R05-C32](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c32)、[R05-C34](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c34)、[R05-C35](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c35)、[R05-C42](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05-c42)。
- 判断质量：5 / 5 / 5 / 5；normal_function：明确未发现功能性破坏，普通标记早退/预算等价有据。；boundary：明确mock级隔离证据不足、缺真实入口证据；全文承认共享落点和E性能风险，未用绿色测试证明绝对无风险。；release：明确完成版不能发布，先修功能与覆盖并保持语义验收未完成标记；较严格的禁止推送措辞不算错误放行。；semantic：明确缺真实模型/人工样本，不把数据隔离当接续决策验收。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r05)。

### R06 QoderIDE / Cantus

- 正确发现：本轮新增产品缺陷集合中无完整命中。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D01、D02、D03、D04、D05、D06、D07、D08、D09、D10、D11、D12、D13、D14、D15、D16、D17、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R06-C08](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r06-c08)、[R06-C14](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r06-c14)。
- 判断质量：5 / 5 / 0 / 0；normal_function：表格正确识别普通请求守卫、图片/授权原分支，未发现无关普通链路新增功能破坏。；boundary：虽126总结过满，但正文列出全局迁移和公共授权接点，249又限定被排除集成范围；按冻结规则结合全文给予边界分。；release：有条件达标仅要求防御校验和CHANGELOG，宣称无Major且唯一正确性风险可消除，未区分阶段提交与功能完成版。；semantic：全文未回答真实模型语义验收尚未完成及stub不可替代，属于未回答，不作为FP。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r06)。

### R07 QoderIDE / Sonus

- 正确发现：[R07-C01](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r07-c01) 恢复投影绕过归档字段和图片分类（D01）；[R07-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r07-c02) 换模型后聚合提前退出遗留pending（D06）；[R07-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r07-c03) 取消复制时UI提前解除来源占用（D15）。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D02、D03、D04、D05、D07、D08、D09、D10、D11、D12、D13、D14、D16、D17、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R07-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r07-c04)。
- 判断质量：5 / 5 / 5 / 5；normal_function：明确未发现无关普通链路新增破坏，并准确限定来源取消窗口UI问题。；boundary：逐项列出共享引擎/迁移/后台轮询/验证未闭合，拒绝绝对零回归。；release：允许注明已知问题的阶段提交，禁止标记功能完成或发布就绪。；semantic：明确真实模型样本不可由stub/结构校验替代，并说明尚未执行。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r07)。

### R08 QoderIDE / 极致模型

- 正确发现：本轮新增产品缺陷集合中无完整命中。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D01、D02、D03、D04、D05、D06、D07、D08、D09、D10、D11、D12、D13、D14、D15、D16、D17、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R08-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r08-c02)、[R08-C07](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r08-c07)。
- 判断质量：5 / 0 / 0 / 0；normal_function：正确识别普通请求/图片/工具池隔离，没有无关普通功能损坏指控。；boundary：宣称所有新增路径均受metadata守卫且基础功能不受任何影响，未识别全局迁移等边界，也无运行验证限制消解。；release：明确建议发布/达到发布标准，与尚存确认缺陷和未完成真实语义验收矛盾。；semantic：全文没有真实模型语义验收未完成/不可由stub替代的说明，属于未回答。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r08)。

### R09 TraeCode / Qwen3.8Max

- 正确发现：本轮新增产品缺陷集合中无完整命中。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D01、D02、D03、D04、D05、D06、D07、D08、D09、D10、D11、D12、D13、D14、D15、D16、D17、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R09-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r09-c02)、[R09-C06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r09-c06)。
- 判断质量：5 / 5 / 0 / 5；normal_function：无关普通分支保持原工具和预算，结论符合本轮代码核验。；boundary：列出全局迁移与所有页面后台请求并限定接受的开销，没有单凭守卫声称无任何共享变化。；release：未明确回答当前能否作为功能完成版合入发布；允许提交/推送和披露语义未验收本身并非错误，不据此捏造赞成正式发布。；semantic：明确真实模型语义未执行，mock不能代表真实模型验证。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r09)。

### R10 TraeCode / kimiK3

- 正确发现：[R10-C11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r10-c11) 交接预算不足返回裸内部错误码而非正常容量分类（D09）。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D01、D02、D03、D04、D05、D06、D07、D08、D10、D11、D12、D13、D14、D15、D16、D17、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R10-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r10-c02)、[R10-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r10-c04)。
- 判断质量：5 / 5 / 0 / 5；normal_function：普通工具/预算/保存机制判断与核验一致。；boundary：列明公共授权/图片/全局V026迁移及测试证据，不按一般无负面影响表述机械扣分。；release：未明确回答当前能否作为功能完成版合入发布；允许提交/推送和披露语义未验收本身并非错误，不据此捏造赞成正式发布。；semantic：准确说明真实模型语义验收未做，stub不应冒充通过。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r10)。

### R11 Cursor / Grok4.7

- 正确发现：[R11-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11-c03) 缺口无法关联 blocked 记录导致恢复不尝试（D04）；[R11-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11-c04) E 最小入口超预算走内部执行失败（D09）；[R11-C06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11-c06) 短暂 paused 仍有锁却前端忽略（D15）。
- 误报 / 错误归因：[R11-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11-c02) 聚合换小模型引起 input_hash 冲突。
- 遗漏：D01、D02、D03、D05、D06、D07、D08、D10、D11、D12、D13、D14、D16、D17、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R11-C05](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11-c05)、[R11-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11-c10)。
- 判断质量：5 / 5 / 5 / 5；normal_function：普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。；boundary：全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。；release：明确区分阶段提交与功能完成版合入发布。；semantic：明确真实模型语义验收未完成，stub 测试不能替代。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r11)。

### R12 zhikuncode / GLM5.3

- 正确发现：[R12-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c03) 预算过小使用通用硬失败（D09）；[R12-C06](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c06) paused时仍持锁却被selector隐藏（D15）；[R12-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c10) 控制请求HTML错误页显示JSON解析异常（D20）；[R12-C12](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c12) 相同操作重复暂停通知key叠加（D22）。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D01、D02、D03、D04、D05、D06、D07、D08、D10、D11、D12、D13、D14、D16、D17、D18、D19、D21。
- 待定 / 规范歧义：[R12-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c02)、[R12-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c04)、[R12-C14](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c14)、[R12-C17](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c17)、[R12-C18](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c18)、[R12-C19](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12-c19)。
- 判断质量：5 / 5 / 0 / 5；normal_function：普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。；boundary：全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。；release：原文234将语义验收是否属于发布标准条件化，未明确作出当前完成版暂不可发布结论。；semantic：明确真实模型语义验收未完成，stub 测试不能替代。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r12)。

### R13 zhikuncode / Qwen3.8max

- 正确发现：[R13-C15](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c15) I5缺recordRef与恢复协议（D04）；[R13-C16](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c16) I6读取错误码压平（D13）；[R13-C18](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c18) I8非法外部路径导致整次暂停（D10）；[R13-C20](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c20) I10取消后仍持锁被前端忽略（D15）；[R13-C23](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c23) I12轮询禁用用户操作（D08）；[R13-C54](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c54) v2 warnings不再含缺口明细（D19）。
- 误报 / 错误归因：[R13-C12](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c12) I2并发resume/create直接500；[R13-C22](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c22) I11缺expectedEpoch导致防重放失效；[R13-C30](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c30) schema失败attempt仍completed；[R13-C59](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c59) 具体保证复制symlink不可利用；[R13-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c10) I1容量错误与HTTP500控制流（D09，半命中）。
- 遗漏：D01、D02、D03、D05、D06、D07、D09（漏半份）、D11、D12、D14、D16、D17、D18、D20、D21、D22。
- 待定 / 规范歧义：[R13-C11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c11)、[R13-C14](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c14)、[R13-C19](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c19)、[R13-C21](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c21)、[R13-C27](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c27)、[R13-C36](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c36)、[R13-C41](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c41)、[R13-C47](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c47)、[R13-C48](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c48)、[R13-C49](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c49)、[R13-C57](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13-c57)。
- 判断质量：5 / 5 / 5 / 5；normal_function：未发现普通主链路破坏这一功能判断与当前独立核验一致；不要因合并域缺陷倒推普通回归。；boundary：尽管速览未造成任何影响措辞过满，正文明确合并清理查询可影响启动及关闭writer共享资源边界；按冻结规则结合全文，不机械扣分。；release：原文597明确现在还不能发，602要求真实语义验收；允许阶段提交不扣分，缺陷漏审不在发布维重复罚。；semantic：正确说明真实模型/人工样本未执行，stub不可替代；572也要求明确未完成前置验收。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r13)。

### R14 zhikuncode / deepseekV4.1flash

- 正确发现：[R14-C04](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c04) 阻断gap与record的恢复关联断裂（D04）；[R14-C16](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c16) HandoffRead超时code被包装（D13）；[R14-C21](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c21) paused/cancelled旧writer仍持锁时忽略（D15）；[R14-C22](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c22) 本地终态遮蔽服务端新active（D07）；[R14-C24](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c24) 同key通知未去重（D22）；[R14-C25](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c25) control返回HTML暴露解析异常（D20）；[R14-C26](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c26) 轮询禁用恢复取消按钮（D08）；[R14-C42](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c42) 图片asset成功阈值高于注入限制（D12）。
- 误报 / 错误归因：[R14-C14](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c14) E预算过小时错误类别通用（D09，半命中）。
- 遗漏：D01、D02、D03、D05、D06、D09（漏半份）、D10、D11、D14、D16、D17、D18、D19、D21。
- 待定 / 规范歧义：[R14-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c03)、[R14-C07](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c07)、[R14-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c10)、[R14-C11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c11)、[R14-C13](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c13)、[R14-C20](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c20)、[R14-C23](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14-c23)。
- 判断质量：5 / 5 / 5 / 5；normal_function：普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。；boundary：全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。；release：明确区分阶段提交与功能完成版合入发布。；semantic：明确真实模型语义验收未完成，stub 测试不能替代。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r14)。

### R15 zhikuncode / kimiK3

- 正确发现：[R15-C08](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r15-c08) 读取超时错误码被包装（D13）；[R15-C17](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r15-c17) 轮询禁用合并控制按钮（D08）。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D01、D02、D03、D04、D05、D06、D07、D09、D10、D11、D12、D14、D15、D16、D17、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R15-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r15-c02)、[R15-C11](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r15-c11)。
- 判断质量：5 / 5 / 0 / 5；normal_function：普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。；boundary：全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。；release：未明确阻止功能完成版合入发布，或将已有缺陷/语义未验收仅作可跟进声明；按冻结规则不算正确完成版判断。；semantic：明确真实模型语义验收未完成，stub 测试不能替代。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r15)。

### R16 小米 mimo（文件标识）

- 正确发现：[R16-C03](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r16-c03) paused/failed锁被前端门控忽略（D15）；[R16-C10](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r16-c10) 合并通知同key可叠加（D22）。
- 误报 / 错误归因：无计分误报或错误归因。
- 遗漏：D01、D02、D03、D04、D05、D06、D07、D08、D09、D10、D11、D12、D13、D14、D16、D17、D18、D19、D20、D21。
- 待定 / 规范歧义：[R16-C02](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r16-c02)、[R16-C08](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r16-c08)。
- 判断质量：5 / 5 / 0 / 5；normal_function：普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。；boundary：全文说明短路、小对象/标记检查或 E 专属范围及验证局限，没有把绿色测试当绝对安全证明。；release：未明确回答当前能否作为功能完成版合入发布；允许提交/推送和披露语义未验收本身并非错误，不据此捏造赞成正式发布。；semantic：明确真实模型语义验收未完成，stub 测试不能替代。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r16)。

### R17 智普 ZCode / GLM5.3

- 正确发现：本轮新增产品缺陷集合中无完整命中。
- 误报 / 错误归因：[R17-C08](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r17-c08) tiny入口走通用错误而非容量通道（D09，半命中）。
- 遗漏：D01、D02、D03、D04、D05、D06、D07、D08、D09（漏半份）、D10、D11、D12、D13、D14、D15、D16、D17、D18、D19、D20、D21、D22。
- 待定 / 规范歧义：[R17-C05](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r17-c05)。
- 判断质量：5 / 5 / 0 / 5；normal_function：普通请求不触发合并读取/工具注入，未发现报告范围内新增功能回归。；boundary：全文38—54门控与64全局迁移说明公共改动；与R03/R10同标准。零影响措辞过满，不当作绝对零风险证明。；release：明确建议发布/达到发布标准，与尚存确认缺陷和未完成真实语义验收矛盾。；semantic：明确真实模型语义验收未完成，stub 测试不能替代。 [全部原文与排除项](GPT-6%20Astra%20Ultra-evidence/claim-ledger.md#r17)。

## 7. 敏感性：哪些顺序稳定，哪些取决于取舍

预设全部 27 个方案：F0.5/F1/F2 × 严重度权重 (4,2,1)/(3,2,1)/(2,1,1) × 缺陷维 70/80/90。剩余分数由四项判断等分。没有根据结果选择方案。

```text
Fβ = (1+β²)TP / ((1+β²)TP + β²FN + FP)
情景总分 = D×Fβ + (100−D)×判断得分/20
```

R02 在 27 个方案中均处第一名位置；其中 9 组独占第一、18 组与 R14 并列第一。R14 范围为 1–2，R01 为 3–4。R13 的 3–8 变化较大，反映覆盖与错误指控之间的取舍敏感，不能把主榜第四解释成稳定能力档位。R03/R07 的顺序也会反转。

**发生严格前后反转的全部 9 对**（仅并列变化不算反转）：

|报告对|前者领先的方案数|后者领先的方案数|并列数|
|---|---:|---:|---:|
|R01 / R13|21|6|0|
|R03 / R07|18|9|0|
|R03 / R13|9|18|0|
|R05 / R07|12|15|0|
|R05 / R12|26|1|0|
|R05 / R13|9|18|0|
|R07 / R13|9|18|0|
|R11 / R12|19|8|0|
|R12 / R13|1|26|0|

全部分数/名次见[敏感性 CSV](GPT-6%20Astra%20Ultra-evidence/results/sensitivity.csv)、[JSON](GPT-6%20Astra%20Ultra-evidence/results/sensitivity.json)；每份范围及每个反转对应的具体参数见[稳定性明细](GPT-6%20Astra%20Ultra-evidence/results/stability.json)。这些范围不是统计置信区间，27 个方案不是随机样本。“没有反转”也只针对这些预设权重，不保证改变判定、加入新证据后仍如此。

## 8. 视频只作为证据附录

### 8.1 本报告分析过程录屏（2026-09-23 07:46）

用户在评分完成后补充本次分析的全程录屏。原片 852.60 MB，压缩版 **74.00 MB**（小于十进制 80 MB），时长 **50 分 42 秒**；H.264、1874×1080、5 帧/秒，无音轨。未裁剪时间范围；降低分辨率和帧率会损失画面细节，因此不是逐帧无损副本。

<video src="./钉钉录屏_2026-09-23_074629.mp4" poster="./GPT-6%20Astra%20Ultra-evidence/video/analysis-20260923-074629.jpg" controls preload="metadata" width="100%"></video>

[![点击打开本报告分析全程录屏（封面取自25:00）](GPT-6%20Astra%20Ultra-evidence/video/analysis-20260923-074629.jpg)](./钉钉录屏_2026-09-23_074629.mp4?raw=true)

**[打开或下载完整录屏（50:42，74.00 MB）](./钉钉录屏_2026-09-23_074629.mp4?raw=true)**。GitHub 会过滤上方 HTML 播放器，请点击封面或此链接；支持 HTML 视频的本地预览可使用播放器。

已检查首段、中段、末段抽样画面及整段解码，未逐帧核验全片。该录屏仅补充分析过程，不新增评分依据，也不改变冻结规则、裁定或排名。[原片/压缩版 SHA-256、编码参数与验证记录](GPT-6%20Astra%20Ultra-evidence/video/analysis-recording-20260923-074629.json)。

### 8.2 原参评过程录屏（2026-09-22 23:36）

对 2026-09-22 23:36 的 36:12.3 视频做分段概览与关键帧检查；引用的精确位置为 07:30、16:30、25:30，见[时间点、原帧与限制](GPT-6%20Astra%20Ultra-evidence/video-observations.md)。没有逐帧审计全程。

页面值得纳入过程附录，但不计评分。画面可以佐证可见工具、标签、当时任务和报告保存过程；覆盖与阶段不一致，不适合比较操作体验、耗时或成本。Codex 的“38 秒”对应保存报告回合，不是完整审查耗时；Cursor 左侧显示别家报告不能当作 Grok 的产物，也不足以证明模型实际读取了它。页面标签不认证底层模型身份，页面评价和旧排名不充当独立正确性证据。缺少某家的完整画面不扣分。

## 9. 实际验证与复算方式

本轮在冻结代码的隔离副本中，仅用合成资料、临时 SQLite 和 stub/mock 完成定向复现，未调用真实付费模型，未访问运行中的用户数据库。[逐用例结果、日志和限制](GPT-6%20Astra%20Ultra-evidence/reproduction-results.md)保留实际记录：

- 原有后端 5 个、前端 1 个期望正确行为的断言重跑，6 个均失败，属于预期红色证据；其中两个测试同属一个缺陷，图片发布项仍是规范歧义。
- 新后端观察性 13 个，加既有普通/E 隔离参数化 2 个，共 15 个通过；前端观察性 5 个通过。断言当前异常行为的“通过”不代表缺陷已修复。
- 未重新运行后端/前端全量套件，未完成真实模型接续开发语义验收，未做完整 UI/HTTP/真实模型 E2E。旧报告的 2,975 等测试数字是历史自述，不挪用为本轮新结果。
- 原有复现材料先读源代码再复跑。夹具开发中纠正了测试变量构造和 macOS `/var` 与 `/private/var` 路径规范化；这些是评审夹具问题，未记成业务缺陷。最终公开的是实际复跑通过/预期断言失败的源码及对应记录。

评分复算需使用冻结版本（Python 3 标准库，无模型调用）。请按[冻结版本隔离复算步骤](GPT-6%20Astra%20Ultra-evidence/README.md#评分复算)，从完整证据发布提交 `e8877b36d5794fb7f64ebea10bfb080a026fe3c7` 建立独立工作目录后运行。当前开发源码已经修复，直接在当前工作区复算可能因冻结哈希不符而拒绝；不能修改旧哈希来适配新代码。该发布提交的 54 项输入符合原冻结清单，评分、规则和原始裁定保持不变。

复现测试的隔离创建与命令见 [runner 说明](GPT-6%20Astra%20Ultra-evidence/reproduction-runner.md)。自动 runner 本轮验证了 prepare-only；上列实际测试由等价隔离命令分批执行，**没有把 runner 全流程说成已重新执行**。重算脚本检查原材料、规则与判定哈希，生成主榜、命中矩阵和全部敏感性结果。脚本仅验证计算与材料一致性，不能替代人工事实判断。

## 10. 如何提出异议与更新

任何参评者都可以指定报告 ID / claim ID / 缺陷 ID，附原文上下文、同版本代码路径和支持条件下的反例。异议区分三种：事实错判（应修台账并复算）、规范含义未定（应先转待定）、权重偏好不同（用公开敏感性数据或另列新方案，不覆盖本轮冻结规则）。

评审特别欢迎反例证明：某个声称的新增问题实际在基线已有、某个恢复条件不可达、两条缺陷实为同根因、某份报告已在别处澄清被认作错误的结论。修订必须保留旧判定、理由和分数变化，不能只改榜单。

本轮提供的是可追溯、规则一致、能复算的一次评估。未做多评审者盲标一致性测量，也没有穷尽全部缺陷；重要相邻差距应结合已公开争议和敏感性解释，而不是宣传为不可质疑的能力结论。
