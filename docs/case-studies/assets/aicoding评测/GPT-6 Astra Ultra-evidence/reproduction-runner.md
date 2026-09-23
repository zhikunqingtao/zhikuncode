# 定向复现运行器

`run_reproductions.py`独立于评分脚本。它从`manifest.json`的冻结HEAD归档backend/frontend，核对清单中的代码哈希，再将本目录`reproductions`夹具复制到临时测试目录。仓库业务文件不被改写，结果和完整日志保存在每次新建、不会自动删除的`astra-merge-repro-*`临时目录中。

前提：Python 3.9+、Git、**Java 21**、Node.js，以及已经安装的前端依赖和已缓存的Maven依赖。Maven使用离线模式；不会自动安装依赖。没有缓存时退出错误，不能作为缺陷复现成立。

在仓库根目录运行（路径包含空格，须保留引号）：

```sh
python3 "docs/case-studies/assets/aicoding评测/GPT-6 Astra Ultra-evidence/run_reproductions.py" \
  --java-home /path/to/jdk-21 \
  --node-modules frontend/node_modules
```

如`JAVA_HOME`或PATH上的java已经是21，可省略`--java-home`。`--prepare-only`只归档、核对与复制，不执行测试。`--output-parent /path/to/parent`可选输出父目录，脚本仍在其中创建新的随机子目录。`--maven-repository`可指定已有Maven本地仓库；默认`~/.m2/repository`。前端只链接现有依赖，Vite缓存留在临时目录，不执行npm安装。

运行分四批，不执行全量测试：

| 批次 | 固定预期 |
|---|---|
| 历史`MergeReviewReproTest` | 5项均以断言失败，反映冻结版本仍违反期望行为 |
| `RankingProtocolReproTest` / `RankingQueryReproTest` / `RankingAssetReproTest` / `RankingReadReproTest` | 6 / 1 / 2 / 4项通过；这些断言确认观察到的行为，通过不等于缺陷已修复 |
| 原有`QueryEngineUnitTest#handoffProjectionOnlyRunsForExplicitlyMergedSessions` | 2项参数化通过（普通与合并分支对照） |
| 前端历史`sessionMergeReviewRepro.test.ts` | 1项断言失败 |
| 前端`rankingProtocolRepro.test.ts` | 5项现状观察通过（含两种通知重叠与目标可用性既有局限反例） |

后端两组通过测试在同一Maven批次中执行，但按独立suite检查数量；前端两个文件分别执行。运行器检查Surefire XML / Vitest JSON中的用例数、失败类型、错误和跳过状态。编译错误、依赖缺失、超时、用例未执行或非断言错误不能算预期失败。预期全部匹配时脚本退出0；观察不符退出1；准备/环境错误退出2。查看`results.json`中的`expectation_met`和`diagnostics`，详细栈在`logs/`及各测试结果文件。

仅执行这里列出的合成/Mockito夹具，不启动Spring应用或真实provider。夹具数据库建在JUnit临时目录；测试JVM的user.home和临时目录也单独设置。子进程不继承API Key、应用环境配置或额外Java参数。该运行器不是通用不可信测试沙箱；不要把未审查的真实服务/用户数据库测试加入`reproductions`后仍沿用此安全说明。此结果仅用于代码事实复核，不代表真实模型接续开发语义验收。
