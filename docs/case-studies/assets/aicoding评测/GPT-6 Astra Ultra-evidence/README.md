# 公平排名证据包

对应上一级的 [GPT-6 Astra Ultra 排名报告](../GPT-6%20Astra%20Ultra-17份审查报告公平排名与可复核证据.md)。本目录只评价 17 份既有报告在一次代码审查任务上的质量，不认证模型身份或总体能力。

## 从哪里开始

|材料|用途|
|---|---|
|[reports.json](reports.json)、[evaluation-conditions.md](evaluation-conditions.md)|17 份原报告、哈希、行数和可确认的条件/未确认项|
|[manifest.json](manifest.json)、[change-freeze.json](change-freeze.json)、[reviewed-change.patch](reviewed-change.patch)|输入版本、原改动基线和冻结差异|
|[rules.md](rules.md)、[rules-freeze.json](rules-freeze.json)|首次计分前的规则与哈希|
|[claim-ledger.md](claim-ledger.md)|408 条完整原文摘录、代码定位、判定和排除理由，含四项判断维|
|[claims/unified.json](claims/unified.json)、[defects.json](defects.json)|复算使用的最终统一裁定、22 类已核实新增缺陷|
|[claims/adjudication-changes.json](claims/adjudication-changes.json)、[adjudication-freeze.json](adjudication-freeze.json)|跨组统一时的修正及首次算分前最终冻结；原分组阅读记录仍在 claims/group-a/b/c.json|
|[method-audit.md](method-audit.md)、[edge-adjudication.md](edge-adjudication.md)|计分前交叉审查、独立算术复核与最后的代码/基线审查|
|[results/main.csv](results/main.csv)、[results/hit-matrix.md](results/hit-matrix.md)|主榜、权重贡献和命中矩阵|
|[results/sensitivity.csv](results/sensitivity.csv)、[results/stability.json](results/stability.json)|27 组完整结果、名次范围及逐对反转参数|
|[reproduction-results.md](reproduction-results.md)、[results/run-summary.json](results/run-summary.json)|实际执行的合成复现、结果、脱敏日志哈希及局限|
|[video-observations.md](video-observations.md)|视频分段概览和 3 个精确时间点的画面，只作附录、不计分|

## 评分复算

在仓库根目录运行，Python 3 标准库即可：

```sh
python3 "docs/case-studies/assets/aicoding评测/GPT-6 Astra Ultra-evidence/recompute.py"
```

脚本核对原文件哈希、规则冻结及最终裁定哈希，生成主榜 JSON/CSV、全量核验台账、命中矩阵、27 个敏感性方案 JSON/CSV 和反转明细。输入文件或规则已改变会拒绝复算，不悄悄把新代码当旧版本。

需要原被审代码时使用 `manifest.json` 中的提交 `207fe6d0d159db1b575af243dbf3c77536377431`；原差异基线为 `b98e18721169436f8f35373a4120c530f54310d5`。仓库相对源码链接用于阅读，哈希和提交确定本轮实际版本。不得在新代码上运行测试后继续沿用旧结论而不注明。

`adjudicate.py`记录分组初稿如何统一成最终台账，是判定过程的生成源码，**评分复算不需要重新运行它**。它重新生成裁定冻结时间；重审事实时应另存修订记录/版本，不覆盖首次评审记录。原分组文件属于初稿，和统一台账不同的判定不得拿来代替最终计分。

## 隔离复现

见 [运行器说明](reproduction-runner.md) 和 [run_reproductions.py](run_reproductions.py)。需要 Java 21、已有 Maven/前端依赖；不自动安装，不调用真实模型。它从冻结提交新建隔离副本，把公开的 7 个测试源文件放入对应测试目录，使用临时 SQLite 与合成数据。

本轮实际分批复跑记录是：6 个期望正确行为的红色断言、15 个后端现状/控制断言通过、5 个前端现状/控制断言通过。红色断言不全对应独立可计分缺陷，观察性绿色也不等于修复成功。运行器本身只做过 prepare-only 验证，未重复跑完整 runner 流程；实际分批命令和证据见复跑结果。

公开日志仅替换机器路径；原断言、错误码与栈不变。未发布 JVM 完整 properties/env。原文摘录中保留报告作者原有本机路径，但点击阅读应使用台账上方的仓库相对链接；没有任何复算依赖 `.tmp` 或评审机器的绝对目录。

## 提出异议

请附报告 ID、claim ID 或缺陷 ID、原文上下文、同版本代码及反例。规范不清先转待定；事实修正保留理由后重算；偏好不同的权重另列情景。哈希和本地时间不是外部公证，也不能消除同一评审体系的偏差。
