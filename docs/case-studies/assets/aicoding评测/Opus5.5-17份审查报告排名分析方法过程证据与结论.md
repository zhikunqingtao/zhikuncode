# 17 份会话合并 v2 审查报告排名：分析方法、过程、证据与结论

- 评测对象：`docs/case-studies/assets/aicoding评测` 下 17 份审查报告
- 被审查代码：`zhikuncode` 会话合并 v2 本地改动（对照 `docs/session-merge-architecture-v2.md`，更新日期 2026-09-22）
- 评测人：Opus5.5（主）+Grok 4.7
- 评测日期：2026-09-23
- 本文目的：把「这些报告谁更准」这件事写成可以独立复核的记录。任何人都可以按文中路径打开代码，核对本文件的每一条判断。
- 本文不是再审一遍产品代码该怎么改，而是审「各报告对产品代码的判断对不对」。

---

## 0. 先回答三个会被追问的问题

**1. 这份排名是不是按报告长短打的？**

不是。字数、问题条数、规范表格行数、是否贴了全量测试命令，都不进分。最长的 `zhikuncode / Qwen3.8max` 约 616 行，排第 8；Astra 约 160 行，排第 2；DeepSeek Harness 不是最长，排第 1。

**2. 这份排名是不是把 Codex / Astra 当满分答案？**

不是。Astra 自己漏了分最高的「死暂停」，也漏了符号链接穿透，总分 65，不是 100。满分答案是：先汇集 17 份报告里所有被声称的缺陷，再逐条对回代码，只留下「代码里真实存在、并且会挡住按 v2 完成版提交 GitHub」的条目。

**3. 「阻碍提交」到底指什么？**

原审查提示词问的是：「是否已达到提交至 GitHub 的发布标准」。结合规范第 9 节，这里的「提交」不是「能不能推一个 Draft 分支」，而是「能不能把这次改动写成架构完成版并合入发布」。

因此缺陷分两层：

| 层次 | 含义 | 进分方式 |
| --- | --- | --- |
| 会挡完成版发布 | 核心能力坏了，或规范硬约束被违反，认真的审查者不会允许宣称 v2 已完成 | B 级，占召回 50 分，按严重度再拆 |
| 真实但不挡提交 | 代码里有，但可绕过、极窄、或只是文案/短暂锁 | C 级，合计最多 10 分，不能靠它们超过命中死暂停的报告 |

规范第 9 节写明的「真实模型接续开发语义验收尚未执行」不是代码缺陷，计入「发布结论」维，不计入缺陷召回。

---

## 1. 评测对象与范围

### 1.1 17 份报告

| 编号 | 目录 / 文件 | 下文简称 |
| --- | --- | --- |
| 1 | `Codex/Astra极高_会话合并v2完整代码审查报告_2026-09-22.md` | Astra |
| 2 | `DeepSeekHarness/deepseekV4.1flashmax-session-merge-v2代码审查报告.md` | DeepSeek Harness |
| 3 | `Qoder/qwen3.8max极高新版本-会话合并v2代码审查报告.md` | Qwen 极高 |
| 4 | `cursor/Grok4.7-会话合并v2完整代码审查报告.md` | Grok 4.7 |
| 5 | `KimiCode/KimiK3-session-merge-v2-review.md` | Kimi Code |
| 6 | `QoderIDE/Sonus-session-merge-v2-review.md` | Sonus |
| 7 | `zhikuncode/deepseekV4.1flash-session-merge-v2-code-review-report.md` | zhikuncode DeepSeek |
| 8 | `zhikuncode/Qwen3.8max-session-merge-v2-code-review-report.md` | zhikuncode Qwen |
| 9 | `TraeCode/Qwen3.8Max-会话合并v2代码审查报告.md` | Trae Qwen |
| 10 | `QoderIDE/Cantus-zhikuncode-session-merge-v2-代码审查报告-2026-09-22.md` | Cantus |
| 11 | `zhikuncode/GLM5.3-session-merge-v2-code-review.md` | GLM |
| 12 | `小米mimo/session-merge-v2-code-review.md` | 小米 MiMo |
| 13 | `Qoder/kimik3-session-merge-v2-review.md` | Qoder Kimi |
| 14 | `智普ZCode/会话合并v2代码审查报告.md` | 智普 ZCode |
| 15 | `QoderIDE/极致模型-会话合并v2代码审查报告.md` | 极致模型 |
| 16 | `TraeCode/kimik3-会话合并v2代码审查报告.md` | Trae Kimi |
| 17 | `zhikuncode/kimik3_session-merge-v2-code-review-report.md` | zhikuncode Kimi |

另有 `Qoder/kimik3-session-merge-v2-review.md` 与 `TraeCode/kimik3-会话合并v2代码审查报告.md` 等共用模型名的报告，按「工具 × 模型 × 目录」分开计，不合并。

### 1.2 原审查提示词（17 份相同）

各报告被要求做两件事：

1. 确认本次改动是否会对非合并分支（普通聊天 / fork / Swarm）造成负面影响。
2. 评估代码质量、测试覆盖及规范性，判断是否已达到提交至 GitHub 的发布标准。

并要求对照 `session-merge-architecture-v2.md`，忽略一组指定的前端 UI / 消息复制文件。

因此，评报告时也只评这两件事做得对不对。不评文笔，不评是否复述了规范全文。

### 1.3 被审查代码的关键落点

下文路径均相对仓库根。Java 前缀为 `backend/src/main/java/com/aicodeassistant/`。

| 文件 | 和本评测相关的职责 |
| --- | --- |
| `session/merge/MergePackageService.java` | 封存、文字投影、缺口、引用复制、恢复投影 |
| `session/merge/SessionMergeService.java` | 暂停 / 恢复 / 发布 |
| `session/merge/MergeSummaryService.java` | 提取、聚合、换模型后的规划 |
| `session/merge/MergeProgressRepository.java` | 单元 `plan` / 哈希冲突 |
| `engine/HandoffContextService.java` | 合并会话每轮入口预算 |
| `frontend/src/store/sessionMergeStore.ts` | `/active` 发现、终态缓存、`submitting` |

---

## 2. 为什么前几版排名经不起质疑

在写出本文件之前，同一批评测至少换过四套口径。这里把它们的失败写清楚，避免再被当成最终结论。

| 口径 | 做法 | 为什么站不住 |
| --- | --- | --- |
| 印象分 | 谁写得细、谁跑了测试、谁列了规范矩阵，谁就靠前 | 把「像审查」当成「审对了」 |
| 以 Astra 五条为标准答案 | 只认 Astra 复现的 5 类问题 | Astra 也可能漏；死暂停、符号链接被清零 |
| 发布结论一刀切 | 写成「尚未达到」就给满分 | Qwen 极高、Harness、Kimi Code、Grok 并列，区分不出谁抓到了更重的缺陷 |
| 11 条真实缺陷近似等比 | H 级 8 分、M 级 4 分 | 把「死暂停」和「未收录计数多 1」放在同一量级，不符合「阻碍提交」 |

本文件只保留最后一套口径：**先独立生成满分答案，再按严重度加权打分。**

---

## 3. 分析方法

### 3.1 生成满分答案的步骤

1. 从 17 份报告中抽出所有被写成「缺陷 / P1 / P2 / Major / 阻断」的断言，不去重、不先信任何一家。
2. 对每一条打开对应文件，沿调用看：触发条件、实际控制流、失败后状态机走到哪里。
3. 用规范核对：这是不是规范要求的行为，还是规范本身留了缺口。
4. 再问一次：「一个认真的审查者会不会因为这一条，拒绝把这次改动标成 v2 完成版？」
5. 只有「代码成立 ∧ 会挡完成版」的条目进入 B 级满分答案。
6. 代码成立但不挡提交的，进入 C 级，低权重。
7. 代码不成立、归因错误、或规范互相打架的，不进满分答案；报告把它们写成事实，精确维扣分。

### 3.2 什么叫「代码成立」

必须同时满足：

- 能指出文件和行号；
- 能说明从哪个用户动作或哪个恢复动作走进这条路径；
- 能说明走完之后的可观察结果（永远 paused、500、复制了包外文件、模型输入含思考字段等）；
- 不能只复述规范条文，也不能把旧 `build()` 死代码上的行为当成在线 `seal` 入口的行为。

Astra 对 B2、B3、C1、C4 写了失败断言，这是最强证据，但不是唯一证据。没有断言、但控制流可以静态推到同一结果的，也算成立。断言指向的机制若和代码不符，不算成立。

### 3.3 什么叫「会挡完成版提交」

会挡，必须是下面至少一类：

1. **核心承诺被拆掉。** v2 相对 v1 的主修复是：失败不再丢包，而是 paused，修好后 resume。若 resume 对若干阻断原因必然再失败，用户只能 cancel，已完成单元和封存资料被扔掉——这直接否定第 4.4 / 5.2 / 5.3 节。
2. **规范硬约束被违反，且有真实数据后果。** 例如 4.4「thinking / 私有续传只归档」、4.1「已保存工具结果应正常合并」、第 156 行「禁止 symlink」。
3. **产品自己广告的恢复动作会再次失败。** 例如 `explain()` 提示「请选择其他模型后恢复」，换模型后聚合校验又把任务打回 paused。

不会挡，即使代码成立：

- 有明确手工绕过（关掉旧结果卡片就能看到 `/active`）；
- 只在极端窗口或极短时间窗口出现（最小入口都放不下、轮询那几百毫秒锁按钮）；
- 只影响文案或计数，资料已经在包内；
- 规范同时写了互相约束的两句话，实现选了其中一种合理读法。

### 3.4 计分公式

总分 100。

```
总分 = B级召回(0-50) + 发布结论(0/10/20) + C级(0-10) + 精确(0-10) + 隔离(0或10)
```

**B 级召回（50）**

| 编号 | 分 | 缺陷 | 挡提交的强度 |
| --- | --- | --- | --- |
| B1 | 16 | 阻断缺口对不上记录号，恢复永远失败 | 最强。常见解析失败 / 坏图就会卡死唯一名额 |
| B2 | 10 | 恢复投影把思考和供应商私有字段送进模型 | 强。规范硬约束，但是恢复边上的路径 |
| B3 | 10 | 旧 `toolUseResult` 不进版本、搜索找不到 | 强。旧 checkpoint 正确性，新主路径不受影响 |
| B4 | 8 | 封存引用复制顺着中间符号链接读到包外 | 强。安全 / 规范第 156 行；需要 scratchpad 内先有软链 |
| B5 | 6 | 换模型后聚合提前退出，留下未完成单元 | 中强。广告过的恢复动作会失败，触发条件窄 |

B1 为什么比 B2/B3 高：v2 文档的写作原因就是线上一次摘要失败后整包被丢。如果暂停之后仍不能恢复，这次改造的主目标没达到。B2/B3 必须修，但不像 B1 那样在「解析失败 / 坏图」这种普通失败上必现。

B1 半分规则：只写了「旧包缺失导致永久暂停」、没有写出 `recoverBlockedProjections` 的记录号协议错误，记 8 分。旧包缺失是另一条 fail-closed 路径，用户影响类似，但不是同一处实现错误。

**发布结论（20）**

| 报告怎么写 | 分 |
| --- | --- |
| 明确不能把这次改动当架构完成版发布 | 20 |
| 可以保存进度 / 有条件提交 / 工程达标，但不能当完成发布 | 10 |
| 已达到发布标准 / 建议发布 / 可以提交且问题都不阻塞 | 0 |

规范第 3、9、455、471 行已经写明：stub 测试不能代替真实模型接续开发验收。再叠加 B 级缺陷，标准结论只能是「不能当完成版」。写成「可提交但须注明未验收」算偏松，给一半。

**C 级（10）**

| 编号 | 分 | 缺陷 | 为什么只给这么点 |
| --- | --- | --- | --- |
| C1 | 3 | 本地旧 completed 挡住 `/active` | 关掉旧卡片即可 |
| C2 | 3 | 极小窗口下交接入口变 500 | 只影响放不下最小入口的 E |
| C3 | 2 | 轮询锁住取消 / 恢复按钮 | 轮询结束后可点 |
| C4 | 2 | 已复制文件计入未收录 | 文件已在包内，是计数文案 |

C 级合计封顶 10。全部找到也只有 10 分，低于 B1 一条。这是有意的：不能靠一堆体验问题超过「找到了死暂停」。

**精确（10）**

从 10 起算：

- 把已核实的 B 级缺陷说成不存在、正确设计、或「无阻断 / 无 P1 / 符号链接全部不可利用」，一次扣 5；
- 风格建议、规范复述、未进入满分答案的低价值问题，不扣；
- 扣到 0 为止。

发布结论维已经处理「写成可以发布」。精确维处理的是「正文里把成立的缺陷说反」。两维可以同时扣，因为一个是放行决定，一个是事实判断。

**隔离（10）**

普通 / fork / Swarm 执行与保存有没有被改坏。17 份都判断「没有破坏主链路」，全部给 10。这一项是送分题，保留是为了确认没有报告胡判主链路被毁；它不决定名次。

### 3.5 命中认定规则

- 必须写到具体行为或具体行号，不能只写「恢复能力不足」。
- 严重度写低了（把 B2 写成 P2）仍然算命中，不扣召回。只要发布结论仍然拦住完成版，精确维也不因此扣分。
- 现象对、机制错：不给该条满分。Grok 写「换模型后聚合失败是因为 `input_hash` 随模型分片宽度变」——现象方向对，归因不成立，B5 记 0。
- 正文写出了 B1，结论又写「阻断级代码缺陷 0 项」：召回给分，精确扣 5。

---

## 4. 分析过程

实际执行顺序如下。

### 4.1 读规范里和「能不能发布」有关的句子

`docs/session-merge-architecture-v2.md`：

- 文首和第 9 行：真实模型接续开发语义验收尚未执行；自动化通过不能替代第 9 节。
- 第 4.4 节：thinking / 私有续传只归档；极端记录暂停后应能从 raw 恢复；非关键附件未解析可标记并保留原件；不新增 OCR。
- 第 156 行：禁止路径穿越、symlink。
- 第 5.3 / 8 节：服务端 active 为权威；进度更新不阻挡取消；换模型只重做未完成工作。
- 第 9 节：结构测试与语义样本分别报告；整体闭合前不宣称完成。

### 4.2 汇集 17 份报告的缺陷断言

用关键词检索并通读结论段，得到候选集合（不在此阶段判断真伪）：

`RECORD_REQUIRES_HANDLING`、`invalid_image`、`legacy_package_missing`、`thinking`、`provider_response_state`、`toolUseResult`、`symlink` / `normalize` / `toRealPath`、`validatedTerminal`、`warningCount`、`HANDOFF_CONTEXT_BUDGET_TOO_SMALL`、`submitting`、`MERGE_UNIT_INPUT_CHANGED`、`MERGE_INCOMPLETE_UNITS`、以及各报告的发布结论句。

### 4.3 对候选缺陷沿代码走控制流

对每一条打开对应函数，看：

- 谁写入缺口 / 哈希 / 状态；
- 恢复或下一轮请求读的是什么；
- 失败后 `SessionMergeService.execute` 的 `catch` 是 `pause` 还是能前进。

关键阅读位置见第 5 节。没有只根据报告标题给分。

### 4.4 冻结满分答案再打分

满分答案冻结为 B1–B5 + C1–C4 + 发布结论标准句。之后才逐份打分。Astra 的五条里，只有 B2、B3、C1、C4 进入答案；「仅图片仍发布」被拿掉。

### 4.5 本轮没有做的事（避免夸大）

- 没有把 Astra 的 6 个失败断言再跑一遍。B2/B3/C1/C4 采信其复现描述，并与当前代码对照，代码对得上。
- 没有重跑后端 2975 项 / 前端 876 项。测试绿不能证明「没有缺陷」，各报告自己的测试数字只用于判断它们有没有把基线失败误栽给本次改动。
- 没有做真实模型接续开发样本。这是规范自己的未完成项，所有报告都应该写出来。

---

## 5. 满分答案：逐条证据

以下行号以 2026-09-23 工作区文件为准。若后续改了代码，应以当时文件为准复核。

### 5.1 B1（16 分）：阻断缺口对不上记录号，恢复永远失败

**结论：成立，而且是最强的提交阻断项。**

**写入侧**

`MergePackageService.java` 消息内容解析失败：

```433:437:backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java
                } catch (IOException invalid) {
                    if (!parseFailure(invalid)) throw invalid;
                    if (Files.exists(raw)) record(new Origin(source,row.get("id").toString(),"raw-"+hash(raw,check),"message"),"reference",null,raw,"blocked");
                    gap(source,"RECORD_REQUIRES_HANDLING",true);
                }
```

这里已经 `record(..., "blocked")`，但 `gap` 写成裸 `RECORD_REQUIRES_HANDLING`，没有记录号。同文件 `:406` 在另一条路径上会写 `RECORD_REQUIRES_HANDLING:"+ref`，说明作者知道正确格式，436 行漏了。

非法内嵌图片：

```531:531:backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java
                            } catch (IllegalArgumentException invalid) { gap(source,"RECORD_REQUIRES_HANDLING:invalid_image",true); }
```

后缀是字面量 `invalid_image`，不是 `r_<hash>`。

附件文本解码失败（`:598`）写成 `RECORD_REQUIRES_HANDLING:"+ref`，但该资产记录的 processingPolicy 是 `extract`，不进入 blocked 集合，恢复时同样对不上。

**恢复侧**

```231:236:backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java
                String prefix="RECORD_REQUIRES_HANDLING:";
                if(!reason.startsWith(prefix) || !blockedRecords.containsKey(reason.substring(prefix.length())))
                    throw new IOException("RECORD_REQUIRES_HANDLING");
```

因此：

- 裸 `RECORD_REQUIRES_HANDLING`：`startsWith("RECORD_REQUIRES_HANDLING:")` 为假 → 抛错；
- `RECORD_REQUIRES_HANDLING:invalid_image`：后缀 `invalid_image` 不在 `blockedRecords` → 抛错。

**编排侧**

```195:198:backend/src/main/java/com/aicodeassistant/session/merge/SessionMergeService.java
            if(snapshot.blockedReason()!=null) {
                if(snapshot.blockedReason().startsWith("RECORD_REQUIRES_HANDLING")) packages.recoverBlockedProjections(path,check);
                else throw new java.io.IOException(snapshot.blockedReason());
            }
```

见此前缀就进恢复；恢复再抛同一错误；外层 `catch` 写 `paused`。`explain()` 还提示「请检查日志及原件后再恢复」。用户 resume → 再 paused。唯一出路是 cancel，包随后被删。

**为什么挡提交**

规范 4.4：「保留其 raw 文件、可读记录头及已完成单元，转 paused/RECORD_REQUIRES_HANDLING」；第 171 行：「封存后修正了解析配置、能够恢复此前阻断记录时，从本包 raw 生成投影」。raw 留下了，恢复通道被自己的字符串格式堵死。这不是风格问题，是 v2 主路径。

**谁命中**

完整命中：DeepSeek Harness、Qwen 极高、Grok 4.7、Kimi Code、zhikuncode DeepSeek、zhikuncode Qwen（正文写了，结论又否认）。

半分：Trae Qwen（只写了 `legacy_package_missing`）。

### 5.2 B2（10 分）：恢复投影把思考和供应商私有字段送进模型

**结论：成立，挡完成版，但触发面比 B1 窄。**

正常投影明确跳过归档字段：

```512:514:backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java
            for (JsonNode block:content) {
                check.run(); String type=block.path("type").asText();
                if (Set.of("thinking","redacted_thinking","provider_response_state").contains(type)) continue;
```

恢复投影把整段 JSON 拼进文字：

```269:271:backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java
                writer.text(record.recordRef()+":recovered",record.origin().sessionId(),
                        "Recovered immutable raw ref="+record.rawRef()+"; source="+record.origin().sessionId()+"\n"+content);
                projected.add(record.recordRef());
```

`MergeSummaryService.prepare` 会处理恢复目录里的全部 text 文件，这些字段进入整理模型。规范 4.4：「thinking、私有续传字段、重复位置只归档」；「只归档内容不作为已经执行的事实」。

触发条件：记录因物化上限被标 blocked，之后提高上限再 resume。不是每条合并都会走到。所以权重低于 B1，但仍挡「完成版」——规范写死了分类，Astra 也用失败断言复现了。

**谁命中**

Astra（P1，有复现）、Sonus（写成 P2，仍算命中）。

### 5.3 B3（10 分）：旧 checkpoint 的 toolUseResult 不进版本、搜索找不到

**结论：成立。只影响旧格式 checkpoint，仍挡完成版对「已保存工具结果」的承诺。**

```337:344:backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java
    private String messageVersion(String role,JsonNode content,String stop,JsonNode metadata) {
        var semantic=json.createObjectNode();
        semantic.put("role",role); semantic.set("content",content); semantic.put("stopReason",stop);
        var meta=metadata!=null && metadata.isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode)metadata.deepCopy() : json.createObjectNode();
        meta.remove("handoffOrigin"); semantic.set("metadata",meta);
        return sha256(semantic.toString());
    }
```

`exportMessage` 的入参没有 `toolUseResult` / `sourceToolAssistantUUID`。checkpoint 导出只传 `content`、`meta`、`stopReason`。两个同来源、同 UUID、相同 content、不同旧工具结果的消息会被当成同一版本。原文 raw 还在，所以不是原件丢失；但版本集合、文字投影、字面搜索都看不到那次失败证据。

规范 4.1：「主消息和工具结果沿用现有 messages 存储」；4.4：「不要仅因是工具输出就跳过」。Astra 用两条失败断言复现。

**谁命中**

只有 Astra。这不能反过来证明别人「故意漏」就该零分——漏了就是漏了，只扣召回，不另扣精确。

### 5.4 B4（8 分）：封存引用复制会顺着中间符号链接读到包外

**结论：成立。安全边界漏洞，挡完成版；威胁前提是 scratchpad 内已有软链。**

有问题的分支：

```458:462:backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java
            for (Asset asset:List.copyOf(assets)) {
                if ("external_reference".equals(asset.status()) && asset.originalPath().startsWith("/")) {
                    Path path=Path.of(asset.originalPath()).toAbsolutePath().normalize();
                    if (path.startsWith(own.toAbsolutePath().normalize()) && !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))
                        copyFile(source,path,root.resolve("assets"),assets,seenAssets,check,copyBudget);
```

`normalize()` 不解析中间符号链接。`copyFile`：

```975:984:backend/src/main/java/com/aicodeassistant/session/merge/MergePackageService.java
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                assets.add(new Asset(id, source.toString(), null, Files.exists(source) ? "ownership_unknown" : "missing", "文件不存在或不是普通文件", 0, null));
                return;
            }
            ...
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS); ...
```

`NOFOLLOW_LINKS` 只作用于最后一段。若 `<scratch>/<session>/link -> /`，则 `<scratch>/<session>/link/etc/passwd`：

1. `normalize()` 后仍以 `own` 为前缀；
2. 最后一段 `passwd` 是普通文件，`isRegularFile(NOFOLLOW)` 为真；
3. `newInputStream(NOFOLLOW)` 会顺着中间的 `link` 读到包外文件，再写入快照，之后可被 `HandoffRead` 读出。

对照：`copyTree:956-962` 用 `toRealPath()` 比较范围，`safeFile:304-316` 逐段拒绝符号链接。同一文件里另外两处做对了，458-462 是疏漏，不是有意设计。规范第 156 行禁止 symlink。

权重 8 而不是 16：本地单用户助手里，智能体本来就能读文件；要利用这一条，需要先在 scratchpad 里放软链。但它仍然违反明文安全约束，不能标成完成版。

把 `safeFile` 拒绝 symlink 写成「整条复制链都不可利用」，与 B4 相反，精确维扣 5。

**谁命中**

只有 DeepSeek Harness。zhikuncode Qwen 写「symlink 不可被利用」，精确扣分。zhikuncode DeepSeek 写「路径穿越/symlink 防护有效」，精确扣分。

### 5.5 B5（6 分）：换模型后聚合提前退出，留下未完成单元

**结论：成立。机制是 Sonus 写的那条，不是 Grok 写的那条。**

结束条件随当前模型估算变化：

```87:93:backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java
        while(!current.isEmpty()) {
            check.run();
            if(current.size()==1) {
                brief=readResult(dir,current.getFirst());
                if(capacity(brief,selected.model())<=1536) break;
            }
```

校验不允许残留未完成单元：

```120:122:backend/src/main/java/com/aicodeassistant/session/merge/MergeSummaryService.java
            for(Unit unit:java.util.stream.Stream.concat(repo.units(id,"extracting").stream(),repo.units(id,"aggregating").stream()).toList()) {
                check.run(); if("split".equals(unit.state())) continue;
                if(!"completed".equals(unit.state())) throw new IOException("MERGE_INCOMPLETE_UNITS");
```

可复现路径：

1. 已完成一层聚合，只剩一个结果，旧模型估算 >1536，于是创建下一层；
2. 下一层调用失败，操作 paused，库里留下 pending 的 `aggregate-*` 单元；
3. 用户按提示换一个估算更小的模型 resume；
4. `current.size()==1` 且新估算 ≤1536，循环 `break`，pending 还在；
5. 校验抛 `MERGE_INCOMPLETE_UNITS`，再次 paused。

规范 5.3：换模型只重做未完成工作。`SessionMergeService.explain()` 对若干错误码明确写「也可选择其他模型」。广告过的恢复动作把自己打回暂停，所以进 B 级。触发条件比 B1 窄，所以只有 6 分。

**Grok 的归因为什么不算 B5**

Grok 写：聚合 `input_hash` 按当前模型分片宽度生成，换模型后 `plan` 保留旧行再比对哈希，抛 `MERGE_UNIT_INPUT_CHANGED`。

对照代码：

- 提取哈希：`sha256(snapshotHash + PROCESSOR_VERSION + fileSha + encode(input))`，不含模型；
- 聚合分批：`used+size>8192`，8192 是常量；
- `plan` 的 `ON CONFLICT DO NOTHING` 之后比对的是上述哈希。

因此「哈希随模型分片宽度变化」不成立。`MERGE_UNIT_INPUT_CHANGED` 不是这条路径的真实失败点。现象「换模型后聚合走不下去」碰巧可能发生，但报告给的机制是错的，B5 记 0。

**谁命中**

只有 Sonus（写出了 1536 提前退出 + `MERGE_INCOMPLETE_UNITS`）。

### 5.6 C 级：成立但不挡提交

#### C1（3 分）：本地旧 completed 挡住 `/active`

```140:150:frontend/src/store/sessionMergeStore.ts
        if (pending?.operation && ['completed', 'cancelled', 'failed'].includes(pending.operation.status)
            && !pending.operation.canCancel && validatedTerminal.has(pending.operation.operationId)) return Promise.resolve();
        ...
                const response = !pending
                    ? await fetch('/api/session-merges/active', ...
```

`/active` 只在 `pending` 为空时调用。恢复到旧终态后只查旧 ID，验证后被 `validatedTerminal` 短路。另一浏览器的新合并不会被发现。规范要求服务端 active 为权威。

不挡提交的理由：关掉旧结果后可以重新发现；服务端 gate 仍锁来源，不会并发写坏。Astra 复现了。极致模型把终态缓存写成正确设计，和本条相反，但本条不是 B 级，精确维不按 B 级那条规则扣（极致模型的 0 分来自发布结论，不是来自这一条）。

#### C2（3 分）：极小窗口下交接入口变成 500

```73:78:backend/src/main/java/com/aicodeassistant/engine/HandoffContextService.java
        if(cost(body,model,ratio)>limit) body=toolAvailable ? minimal : unavailable;
        if(cost(body,model,ratio)>limit) throw new IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL");
        ...
        if(cost>limit) throw new IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL");
```

`QueryEngine.handoffProjection` 不把该异常映射成容量错误。规范 7.1 要求返回正常容量错误，不把已完成合并改失败。只影响窗口小到连最小入口都放不下的 E，普通会话不走这条路径。

#### C3（2 分）：轮询锁住取消 / 恢复

`refresh` 一开始 `set({ submitting: true })`，面板按钮 `disabled={submitting}`，`control()` 在 `submitting` 时直接 return。规范：「进度更新不阻挡取消」。锁是间歇性的，请求结束后解开，最长约 15 秒超时。不挡完成版合入，但规范写了不该如此。

#### C4（2 分）：已复制仍计入未收录

`collectReferences` 先记 `external_reference`，随后 `copyTree` / `copyFile` 可能已复制同一路径；v2 导出没有消掉临时外部状态，`exportAsset` 仍把外部引用写入 gaps 并增加 `warningCount`。UI 把该数字说成「未收录 N 个文件」。资料已在包内，是展示错误。

### 5.7 从满分答案拿掉的说法

#### 「关键要求只在图片里时，合并仍会发布」——不进 B 级

图片路径只写占位文字「尚未解释其视觉内容」，非文字附件记非阻断 `attachment_uninterpreted`，stub 下操作仍可 completed。Astra 把它列为 P2。

规范 4.4 同时有两句：

- 「关键需求只存在于无法理解的附件时暂停」；
- 「不新增 OCR / 音视频 / 文档解析平台」；「非关键附件未解析可明确标记并保留原件」。

实现没有「这张图是不是关键需求」的分类器。若凡是含图就暂停，会过度阻断。按「不新增解析平台 + 保留原件 + 占位说明」读，当前实现是一种合法读法。Astra 的夹具把「所有要求只在图里」写在可见文本里，整理模型其实看得到这句字，并不能证明「系统本该识别图中需求」。

因此：行为存在，规范有张力，**不作为必须先修才能提交的缺陷**。报告写了它不扣分，也不加 B 级分。

#### 「旧包缺失必须先修」——不单独进 B 级

`importPrevious` 对缺失的 v1 包写 `legacy_package_missing` 且 `blocking=true`。恢复不会改这条不可变缺口，再 resume 仍暂停。

规范一方面写「重复合并不依赖旧包存活」，一方面写「不能冒充完整记录」。缺包时暂停是 fail-closed，可以争，不是实现写错。只报这一条的报告，按 B1 半分处理。

#### 极致模型撤回的编译错误——不存在

不进入满分答案，也不给任何报告加「发现了编译错误」的分。

---

## 6. 发布结论怎么打分

标准句：

> 普通执行链路没有被改坏。按规范第 9 节，现在还不能把这次改动写成 v2 完成版发布：真实模型语义验收没做，且至少还存在 B 级缺陷。

| 分 | 典型原句 |
| --- | --- |
| 20 | Astra：「不应标记为已完成验收的正式版本。」DeepSeek Harness：「尚未达到。」Qwen 极高：「不建议现在推送。」Grok：「不能当作 v2 完成版提交发布。」Kimi Code：「暂未达到，差一步。」Sonus：「不建议作为功能完成、可合入发布的版本提交。」 |
| 10 | zhikuncode DeepSeek：可保存进度、不宜当发布。zhikuncode Qwen：「尚未达到（有条件）」。Trae Qwen：「可以提交」但要求先修问题并注明验收缺口。GLM：「工程层面达标……语义验收尚未执行」。小米：「有条件可以提交」。Cantus：「有条件达标。」 |
| 0 | Qoder Kimi：「已达到提交 GitHub 的标准」。智普 ZCode：「可以提交 GitHub。」极致模型：「Pass — 建议发布」。Trae Kimi：「均达到提交 GitHub 的标准」「5 项问题全部为 minor/low，不阻塞提交」。zhikuncode Kimi：「无阻断 / 无高级偏差」。 |

「测试全绿」不能推出「可以发布」。规范自己禁止这种推导。

---

## 7. 逐份计分

隔离维 17 份都是 10，下面不再重复解释。同分不拆名次。

### 第 1 名 · DeepSeek Harness · 67

| 维 | 分 | 依据 |
| --- | --- | --- |
| B | 24 | B1 + B4 |
| C | 3 | C2（入口失败是 500） |
| 结论 | 20 | 「尚未达到」 |
| 精确 | 10 | 没有把成立的 B 级说反；测试失败做了干净基线对照 |
| 隔离 | 10 | |
| 总分 | 67 | |

命中分最高的两条会挡提交的缺陷。漏了 B2/B3/B5。不是因为像 Astra，而是因为抓到了死暂停和符号链接。

### 第 2 名 · Astra · 65

| 维 | 分 | 依据 |
| --- | --- | --- |
| B | 20 | B2 + B3，均有失败断言 |
| C | 5 | C1 + C4 |
| 结论 | 20 | 不按完成版发布 |
| 精确 | 10 | 报出的五类问题里，进入满分答案的都成立；「仅图片」不进 B 级，也不因此扣精确 |
| 隔离 | 10 | 正确写了页面恢复仍有缺口 |
| 总分 | 65 | |

Astra 不是 100，因为它漏了 B1 和 B4。复现能力强，不等于覆盖了全部会挡提交的缺陷。把它设成满分答案，会把「像不像 Astra」当成能力，这正是前一版站不住的原因。

### 第 3 名 · Qwen 极高 · 61

| 维 | 分 | 依据 |
| --- | --- | --- |
| B | 16 | B1，行号和字符串格式都写对了 |
| C | 5 | C2 + C3 |
| 结论 | 20 | 「不建议现在推送」 |
| 精确 | 10 | 后排 P2 很多、价值不均，但没有把 B 级说反；说 `safeFile` 拒 symlink 是对的，没有宣称复制链整体不可利用 |
| 隔离 | 10 | |
| 总分 | 61 | |

规范矩阵最长，不因此加分。排第 3 只因为 B1 成立，且结论拦住了完成版。

### 第 4 名 · Grok 4.7 · 59

| 维 | 分 | 依据 |
| --- | --- | --- |
| B | 16 | B1 |
| C | 3 | C2 |
| 结论 | 20 | 不能当完成版 |
| 精确 | 10 | 换模型那条机制错误，按规则记 B5=0，不另扣精确（没有把 B1–B4 说反） |
| 隔离 | 10 | |
| 总分 | 59 | |

未重跑全量测试，不扣分也不加分。可复核强的是对回了文件和行为，不是是否贴了测试日志。

### 第 5 名 · Kimi Code · 56

| 维 | 分 | 依据 |
| --- | --- | --- |
| B | 16 | B1（436 / 531 / 598 三条路径） |
| C | 0 | 写到 `validatedTerminal` 可能陈旧，但不是 C1 的「旧终态挡住 /active」 |
| 结论 | 20 | 「差一步」 |
| 精确 | 10 | 写 HandoffRead 拒 symlink 针对的是读路径，成立 |
| 隔离 | 10 | |
| 总分 | 56 | |

### 第 5 名 · Sonus · 56

| 维 | 分 | 依据 |
| --- | --- | --- |
| B | 16 | B2 + B5 |
| C | 0 | |
| 结论 | 20 | 不能标成已完成 |
| 精确 | 10 | 把 B2 写成 P2，仍拦住发布，不扣 |
| 隔离 | 10 | 写明了未跑测试 |
| 总分 | 56 | |

唯一命中 B5 正确机制的报告。漏了 B1。未跑测试不扣可复核以外的分——本表已取消单独的「可复核」高权重维，避免奖励长文。

### 第 7 名 · zhikuncode DeepSeek · 46

| 维 | 分 | 依据 |
| --- | --- | --- |
| B | 16 | B1 |
| C | 5 | C1 + C3 |
| 结论 | 10 | 可提交、不宜当发布 |
| 精确 | 5 | 「路径穿越/symlink 防护有效」与 B4 相反 |
| 隔离 | 10 | |
| 总分 | 46 | |

### 第 8 名 · zhikuncode Qwen · 41

| 维 | 分 | 依据 |
| --- | --- | --- |
| B | 16 | 正文 348 行写出了裸 `RECORD_REQUIRES_HANDLING` |
| C | 5 | C2 + C3 |
| 结论 | 10 | 「尚未达到（有条件）」 |
| 精确 | 0 | 「阻断级代码缺陷 0 项」否定自己刚写的 B1；「symlink 不可被利用」否定 B4 |
| 隔离 | 10 | |
| 总分 | 41 | |

这是「看懂了意思没有」的典型例子：最长报告之一，B1 写在正文里，结论却说没有阻断级缺陷。召回给分，精确扣光。

### 第 9 名 · Trae Qwen · 38

| 维 | 分 | 依据 |
| --- | --- | --- |
| B | 8 | 只报 `legacy_package_missing`，按 B1 半分 |
| C | 0 | 写到 `validatedTerminal` 清理，不是 C1 本身 |
| 结论 | 10 | 可以提交，但要求先修并注明缺口 |
| 精确 | 10 | |
| 隔离 | 10 | |
| 总分 | 38 | |

### 第 10 名 · Cantus · 30

B=0，C=0，结论=10（有条件达标），精确=10，隔离=10。没有命中任何 B/C 级成立缺陷，但没有把完成版放行。

### 第 11 名 · GLM · 28

B=0，C=3（C2），结论=10，精确=5（「无严重缺陷」在 B1 存在时过满），隔离=10。

### 第 12 名 · 小米 MiMo · 25

B=0，C=0，结论=10（有条件可提交），精确=5（「无 P0/P1 阻塞」），隔离=10。

### 第 13 名 · Qoder Kimi · 23

B=0，C=3（C2），结论=0（已达到提交标准），精确=10，隔离=10。C2 写对了，但放行决定错了。

### 第 13 名 · 智普 ZCode · 23

B=0，C=3（C2），结论=0（可以提交），精确=10，隔离=10。

### 第 15 名 · 极致模型 · 20

B=0，C=0，结论=0（Pass — 建议发布），精确=10，隔离=10。把编译通过写成规范全部通过。精确维没有再扣，是因为「建议发布」已经在结论维记 0；它没有在正文里逐条否定 B1–B5 的机制，而是整体放行。

### 第 16 名 · Trae Kimi · 18

B=0，C=3（C2），结论=0，精确=5（「5 项全部 minor/low，不阻塞提交」在 B1 存在时过满），隔离=10。

### 第 17 名 · zhikuncode Kimi · 17

B=0，C=2（C3），结论=0，精确=5（「无阻断 / 无高级偏差」），隔离=10。C3 成立，但把架构符合性写成无阻断，和 B1 相反。

---

## 8. 总表

| 名次 | 报告 | B 级命中 | 召回 | C | 结论 | 精确 | 隔离 | 总分 |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | DeepSeek Harness / V4.1 flash max | B1 B4 | 24 | 3 | 20 | 10 | 10 | **67** |
| 2 | Codex / Astra 极高 | B2 B3 | 20 | 5 | 20 | 10 | 10 | **65** |
| 3 | Qoder / Qwen3.8max 极高 | B1 | 16 | 5 | 20 | 10 | 10 | **61** |
| 4 | Cursor / Grok 4.7 | B1 | 16 | 3 | 20 | 10 | 10 | **59** |
| 5 | Kimi Code / Kimi K3 | B1 | 16 | 0 | 20 | 10 | 10 | **56** |
| 5 | QoderIDE / Sonus | B2 B5 | 16 | 0 | 20 | 10 | 10 | **56** |
| 7 | zhikuncode / DeepSeek V4.1 flash | B1 | 16 | 5 | 10 | 5 | 10 | **46** |
| 8 | zhikuncode / Qwen3.8max | B1 | 16 | 5 | 10 | 0 | 10 | **41** |
| 9 | Trae / Qwen3.8Max | B1 半分 | 8 | 0 | 10 | 10 | 10 | **38** |
| 10 | QoderIDE / Cantus | 无 | 0 | 0 | 10 | 10 | 10 | **30** |
| 11 | zhikuncode / GLM 5.3 | 无 | 0 | 3 | 10 | 5 | 10 | **28** |
| 12 | 小米 MiMo | 无 | 0 | 0 | 10 | 5 | 10 | **25** |
| 13 | Qoder / Kimi K3 | 无 | 0 | 3 | 0 | 10 | 10 | **23** |
| 13 | 智普 ZCode | 无 | 0 | 3 | 0 | 10 | 10 | **23** |
| 15 | QoderIDE / 极致模型 | 无 | 0 | 0 | 0 | 10 | 10 | **20** |
| 16 | Trae / Kimi K3 | 无 | 0 | 3 | 0 | 5 | 10 | **18** |
| 17 | zhikuncode / Kimi K3 | 无 | 0 | 2 | 0 | 5 | 10 | **17** |

命中矩阵：

| 报告 | B1 | B2 | B3 | B4 | B5 | C 级 |
| --- | --- | --- | --- | --- | --- | --- |
| DeepSeek Harness | 命中 | | | 命中 | | C2 |
| Astra | | 命中 | 命中 | | | C1 C4 |
| Qwen 极高 | 命中 | | | | | C2 C3 |
| Grok 4.7 | 命中 | | | | 机制错，不计 | C2 |
| Kimi Code | 命中 | | | | | |
| Sonus | | 命中 | | | 命中 | |
| zhikuncode DeepSeek | 命中 | | | 说成安全 | | C1 C3 |
| zhikuncode Qwen | 写了又说无阻断 | | | 说成不可利用 | | C2 C3 |
| Trae Qwen | 只报旧包缺失 | | | | | |
| 其余 8 份 | | | | | | 至多 C2/C3 |

---

## 9. 预先回答质疑

### 9.1 「为什么第一名不是复现最多的 Astra？」

因为评的是「会不会挡完成版提交」，不是「谁的失败断言最多」。Astra 的 B2/B3 成立，各 10 分；它漏掉的 B1 是 16 分，B4 是 8 分。DeepSeek Harness 的 24 分召回来自更重的两条。65 对 67，差距就是这个权重，不是印象。

若有人认为思考字段泄漏应与死暂停同权，可以把 B2 改成 16，Astra 会变成 71，超过 Harness。本文不采用，理由在 3.4：死暂停在普通失败上必现，思考泄漏要先走到「物化上限挡住再恢复」。权重必须反映触发面和产品承诺，不能反映谁先写成了 P1。

### 9.2 「为什么符号链接只有 8 分？本地应用智能体本来就能读文件。」

承认威胁前提窄，所以不是 16。仍然进 B 级，因为：规范第 156 行是硬禁止；同一文件的 `copyTree` / `safeFile` 已经按 real path / 逐段拒绝实现；458-462 是唯一漏做的兄弟分支；复制进包后会经 `HandoffRead` 变成合并会话可读资料，范围超出「智能体当时读过一次」。

### 9.3 「为什么 Grok 的换模型问题不计 B5？用户换模型确实可能失败。」

失败现象可以由 B5 的真实机制引起。Grok 给的机制（`input_hash` 含模型分片宽度 → `MERGE_UNIT_INPUT_CHANGED`）和代码不符。评的是判断质量，不是「句子里有没有换模型三个字」。否则把错误根因写成事实，修代码的人会去改一处并不存在的哈希计算。

### 9.4 「zhikuncode Qwen 写出了死暂停，为什么精确是 0？」

召回已经给了 16 分。精确扣的是结论句「阻断级代码缺陷 0 项」和「symlink 不可被利用」。一次审查如果正文和结论相反，使用者会按结论放行。两维同时计，不是双罚同一句话：一维奖「看到了」，一维罚「说反了」。

### 9.5 「隔离 10 分人人满分，是不是注水？」

是共识题。保留 10 分是为了：若有报告把主链路说成被破坏，可以把它打下去。本轮没人这样胡判，所以隔离不改变名次。若去掉这一维，名次顺序不变，只是所有人减 10。

### 9.6 「C 级会不会被用来刷分？」

不会。C 级封顶 10，低于 B1。找齐 C1–C4 也超不过只命中死暂停且结论正确的报告（16+20+10+10=56 的下限带）。

### 9.7 「没跑测试的 Sonus 为什么能和跑了测试的 Kimi Code 并列？」

本评测不给「跑了测试」单独加分。Kimi Code 的测试数字用来排除误栽，已经体现在精确维没有乱扣。Sonus 命中了 B2 和唯一正确的 B5，Kimi Code 命中了 B1。两边召回都是 16，结论都是 20。并列是算出来的。

### 9.8 「本评测作者自己写过 Grok 4.7 那份报告，是不是护短？」

Grok 4.7 排第 4，59 分。B5 按机制错误计 0。没有把「换模型」三个字兑成 6 分。若护短，会把 B5 给满并压低 Harness / Astra。分数不支持这种怀疑。

### 9.9 「真实模型验收没做，是不是所有报告结论都该是 0？」

不是。规范允许阶段提交，但禁止宣称完成。正确写法是「不能当完成版」，给 20。写成「可以提交，但必须注明未验收」给 10。写成「已达到发布标准」给 0。没做语义验收是所有人面对的同一事实，区分在于有没有把这件事说成已经通过。

### 9.10 「如果代码后来修了，这份排名还有效吗？」

这份排名评的是 2026-09-22 左右那批未修缺陷的代码，以及当时那 17 份报告。代码修好之后，B 级条目可能不再成立；那是另一次评测。不能用修好后的代码否定当时报告抓没抓到当时的缺陷。

---

## 10. 结论

1. **满分答案必须来自代码，不能来自某一份报告。** Astra 漏了死暂停和符号链接；Harness 漏了思考字段和旧工具结果。两家都不是 100 分。
2. **「阻碍提交」必须按严重度拆开。** 死暂停（16）高于恢复路径上的思考泄漏（10），高于中间符号链接（8），高于换模型聚合死锁（6）。未收录计数、短暂锁按钮、极小窗口 500，不能和死暂停同权。
3. **按这套口径，第一名是 DeepSeek Harness（67），第二名是 Astra（65）。** 拉开它们的是 B1 和 B4，不是报告长度，也不是谁先被设成标准答案。
4. **发布结论说反，比漏掉一条 C 级严重得多。** 第 13 名以后的共同问题是：B 级全空，并且把这次改动写成可以发布或没有高级缺陷。
5. **17 份都判断对了非合并主链路没有被改坏。** 这项不能当排名依据，只能当「没有胡判」的基线。

可视化对照见 Cursor Canvas：`/Users/guoqingtao/.cursor/projects/Users-guoqingtao-Desktop-dev-code-zhikuncode/canvases/aicoding-eval-0923-ranking.canvas.tsx`。若 Canvas 与本文数字冲突，以本文第 8 节总表为准。

---

## 附录 A. 复核清单

要质疑本文件，按这个清单即可，不必重新读 17 份全文。

1. 打开 `MergePackageService.java` 436、531、235-236，确认 B1。
2. 打开同文件 514 与 270，确认 B2。
3. 打开同文件 337-344 与 `exportMessage` 入参，确认 B3。
4. 打开同文件 458-462、975-984，对比 956-962 与 `safeFile` 304-316，确认 B4。
5. 打开 `MergeSummaryService.java` 87-93、122，确认 B5；打开 79、162 行的哈希计算，确认 Grok 的「哈希含模型分片」不成立。
6. 打开 `sessionMergeStore.ts` 140-150，确认 C1。
7. 打开 `HandoffContextService.java` 73-78，确认 C2。
8. 打开 store 的 `submitting` 与 panel 按钮 disabled，确认 C3。
9. 打开 `warningCount` 与 `exportAsset` 对 `external_reference` 的处理，确认 C4。
10. 打开规范 4.4 关于 OCR 与「关键附件」的两段，确认「仅图片仍发布」为什么不进 B 级。
11. 用第 8 节公式重算任意一份报告：B + C + 结论 + 精确 + 10。

---

## 附录 B. 本文件不声称的事

- 不声称 17 份正文的每一句都被逐字校对。计分只依赖：发布结论句、对 B/C 级的命中或反结论、以及精确维列出的那几句否定。
- 不声称 B 级以外的所有 P2/P3 都假。它们多数是风格、性能备注、测试缺口。没进满分答案，是因为它们不决定「能不能当完成版提交」。
- 不声称 DeepSeek Harness 或任何一家「审完了这次改动」。第一名 67 分，仍漏了 B2/B3/B5。
- 不声称可以把本排名直接当成这些工具在所有任务上的能力排名。这是同一提示词、同一批改动、同一次会话合并审查上的结果准确性排名。
