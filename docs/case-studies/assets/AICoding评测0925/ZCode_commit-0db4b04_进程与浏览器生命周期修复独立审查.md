# ZhikunCode 提交独立审查报告

- **被审查提交**：`0db4b0498f6cf886f862fe293883256b1c52e049` — *fix: close process and browser lifecycle leaks*（当前 `main` HEAD，工作区干净）
- **仓库**：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`
- **审查者**：ZCode
- **审查日期**：2026-09-25
- **改动规模**：24 文件，+2870 / −400（Java 后端 9 个主文件 + 8 个测试文件，Python 服务 3 个主文件 + 4 个测试文件；前端、配置、文档均未改动）

---

## 0. 审查方法与独立性声明

本次审查**不复用提交者（或其它 AI 工具）的结论**，全部结论来自对 diff、周边调用链的独立阅读，以及自行设计的实测：本目录下其它工具（QoderCN/QoderIDE/pi）的审查报告我**未阅读**，以保持判断独立。

审查过程中**未修改 zhikuncode 任何源码**。为验证 Linux 专属路径与复现风险，我在仓库外（`/tmp`）编译了独立的探针/复现程序，并挂载仓库已编译的 `backend/target/classes` 在容器内运行；未对仓库做任何写操作（未使用 `git worktree`/`stash`，对照用的父提交通过 `git archive` 导出到 `/tmp/zc_base`）。

实际执行的验证手段：

| 手段 | 命令/位置 | 结果 |
|---|---|---|
| 后端定向测试（8 个相关测试类） | `./mvnw -o -q test -Dtest=...` | 87 个用例，失败 0，其中 6 例在 macOS 被跳过 |
| 后端**全量**测试 | `./mvnw -o test -DskipITs` | **Tests run: 3039, Failures: 1, Errors: 0, Skipped: 76** |
| Python 测试 | `pytest tests/ -q` | **229 passed, 1 skipped** |
| Linux 真实路径实测（生产基础镜像 `eclipse-temurin:21-jre-noble`，`--init`） | `/tmp/zc_linux/Probe*.java` | 见 §2.1，核心修复在 Linux 上确实生效 |
| 会话锁死风险复现 | `/tmp/zc_repro/Repro.java` | 见 §2.2，**可稳定复现** |
| 不稳定用例量化 | 目标用例隔离 14 次 + 整类 6 次（含 CPU 高负载 6 次） | 20 次全通过，见 §6.3 |

未覆盖的部分（诚实声明）：未跑真实 Chromium 端到端（该用例为 opt-in，CI 亦未启用）；未在 `hidepid`/受限 `/proc` 主机上实测 §3.1 中 F2 的推断；未执行 `-Pcoverage` 的 Java 覆盖率门禁与 Python `--cov-fail-under=70`（本地 venv 未安装 `pytest-cov`，该门禁仅在 nightly 生效）。

---

## 1. 结论摘要

**总判定：不建议直接视为"无问题的修复"。** 本次改动在**常规链路**上经我实测是有效的、没有发现确定性破坏（Linux 生产镜像内前台命令输出/退出码/环境保真，清理确认正常，子进程与脱离会话的浏览器子进程都能被回收；Python 浏览器与 journey 生命周期语义明显更严谨）。但发现 **1 个可复现的高危回退**（未确认清理会**永久锁死整个会话**并泄漏 runner 容量槽，且没有恢复路径）和 1 个条件性高危回退（受限主机从"优雅降级"变成"永久失败 + 容量泄漏"），另有若干行为变更与规范性缺口。

风险清单（P0=必须修，P1=高，P2=中，P3=低/规范）：

| 编号 | 级别 | 问题 | 位置 |
|---|---|---|---|
| F1 | **P1（高）** | 未确认清理 → 保留 Run `WorkLease` → `RunExecutionRegistry.unregister` 无法移除注册 → **同会话后续所有 Run 永久失败**（`SESSION_EXECUTION_ALREADY_REGISTERED`），且无恢复路径，只能重启 JVM；同时泄漏 1 个 runner 容量槽（上限 16） | `ManagedProcessRunner.java:148-186,343-352`；`RunExecutionRegistry.java:258-264,632-654` |
| F2 | **P1（高，条件性）** | 受限主机（`/proc` 受限、枚举抛异常）上"枚举不可用"从旧实现的"视主进程已死即成功"变为**永不确认**：每条命令都返回失败 + 每条命令泄漏一个容量槽，最终 `PROCESS_CAPACITY_EXCEEDED` | `OwnedProcess.java:105-140`（尤其 `120`），旧行为见 diff 中删除的 `if (!process.isAlive()) return true;` |
| F3 | P2 | Python 侧**删除了"容量满时驱逐最旧会话"**，改为硬失败 `Browser session capacity reached`；普通浏览器链路从自愈变为报错 | `browser_service.py:410-412` |
| F4 | P2 | "清理未确认"被提升为**硬失败**：`exit 0` 的命令也报错并**跳过 shell 状态更新**；`npm install` 成功也可能整体失败；新失败码与既有 `RunExitReason.PROCESS_TERMINATION_UNCONFIRMED` 同名不同义 | `BashTool.java:408-419,477-484`；`DevServerLauncher.java:188-198` |
| F5 | P2 | journey 截止时间依赖 Java/Python 时钟一致（绝对时间戳），时钟漂移会截断旅程或使保护失效 | `BrowserVerifier.java:44`；`journey.py:34-37` |
| F6 | P2 | HTTP 断开 watcher 的**异常**被当成客户端断开 → 取消正常旅程并返回 499 | `journey.py:47-48,60-62` |
| F7 | P3 | `UserJourneyVerifier` 仍用旧 `"rv-"+sessionId` 与 120s（死代码，但保留不一致）；`VerifyJourneyTool` 有未使用 import | `UserJourneyVerifier.java:18,36`；`VerifyJourneyTool.java:24` |
| F8 | P3 | Linux 硬依赖 `setsid`+`bash`（缺失即所有前台命令失败）、`/proc/pid/stat` 解析对空/截断读取未按异常处理、每次 `terminate` 迭代全量扫 `/proc` | `OwnedProcess.java:45-48,111,150-183,215-223` |
| F9 | P3 | 包装器使子进程环境多出 `PWD`/`SHLVL`，`runIsolated` 的"清空环境"不再严格清空 | `OwnedProcess.java:53-81` |
| F10 | P3 | 子进程进入新会话、无控制终端：终端 Ctrl+C / 组信号不再隐式传递；`/dev/tty` 相关行为变化 | `OwnedProcess.java:54-57` |
| F11 | P3 | DevServer：销毁预算从 `grace+5s` 缩到 `grace+2s`（更易"未确认"）；未确认时不删 pid 文件、handle 常驻 | `DevServerLauncher.java:31,133-140` |
| D1 | P2（规范） | **未更新 CHANGELOG.md / README / docs**，而本提交含用户可见的行为与失败语义变更（该仓库惯例是逐条记录） | 仓库根 `CHANGELOG.md` |

**对"是否破坏正常功能链路"的直接回答**：常规路径（前台命令、后台进程、Python 受管服务、DevServer、Browser/Journey 验证）我未发现确定性破坏，实测均正常；但 F1/F2 会在**特定失败条件下把"局部不确定"放大为"会话级不可用 + 容量泄漏"**，F3/F4 是明确的行为/语义变更（部分可能有意的），需产品与文档确认。

---

## 2. 关键实测证据

### 2.1 Linux 生产路径实测（补上 macOS 无法覆盖的部分）

`OwnedProcess` 在非 Linux 平台走的是 `builder.start()` 直启分支（`OwnedProcess.java:35-44`），`setsid` 包装、进程组扫描、`/proc` 身份校验**全部只在 Linux 生效**。开发机是 macOS，`OwnedProcessTest` 的 6 个关键用例被 `@EnabledOnOs(LINUX)` 跳过——**本地"全绿"不能证明 Linux 路径可用**。我用仓库已编译的类在生产基础镜像里做了实测：

```
docker run --rm --init \
  -v /tmp/zc_linux:/probe:ro \
  -v <repo>/backend/target/classes:/classes:ro \
  -w /probe eclipse-temurin:21-jre-noble java -cp /probe:/classes Probe
```

输出（节选）：

```
A setsid=true bash=true uid=root
B run0 out='ok' err='perr' exit=3 confirmed=true ms=294
B run1 out='ok' err='perr' exit=3 confirmed=true ms=74
B run2 out='ok' err='perr' exit=3 confirmed=true ms=37
C childAliveBeforeCleanup=true
D confirmed=true ms=1026 childAliveAfter=false      ← 忽略 TERM 的同组子进程被强杀回收
E detachedAliveBefore=true
F confirmed=true detachedAliveAfter=false           ← 自行 setsid 脱离的"浏览器类"子进程也被回收
```

第二个探针：

```
H exit=127 stderr='bash: line 1: definitely-not-a-command-xyz: command not found'   ← 报错信息保真
I out='DONE-0'                                                                     ← 子进程 stdin 仍为 EOF（与旧 closedOutput 语义一致）
K child env='PWD=/probe|SHLVL=0|ONLY_THIS=1'                                       ← 见 F9
```

**结论**：在生产基础镜像中 `setsid`/`bash` 存在（不构成硬依赖破坏），包装器的"准入握手 + exec"、输出/退出码/环境保真、同组顽固子进程与脱离会话子进程的回收，都按设计工作；常规命令清理确认耗时 37–294ms，不会误报。

### 2.2 高危回退的可复现证据（F1）

我写了与仓库无关的独立复现（`/tmp/zc_repro/Repro.java`，使用真实 `RunExecutionRegistry` + `ManagedProcessRunner`，只把清理钩子设为"未确认"以触发保留路径）：

```
[1] exitCode=0 terminationConfirmed=false
[2] isRegistered(run-1)=true quiescent=false runBySession(session-1)=run-1
[3] SECOND RUN IN SAME SESSION FAILED: java.lang.IllegalStateException: SESSION_EXECUTION_ALREADY_REGISTERED
[4] cancelRunDetailed(run-1)=CancelSummary[activeCount=1, confirmedCount=0, unconfirmedCount=1]
[5] THIRD RUN FAILED: java.lang.IllegalStateException: SESSION_EXECUTION_ALREADY_REGISTERED
```

即：**一次前台清理未确认，会让该会话后续每一轮对话都直接失败**，且 `cancelRunDetailed` 也救不回来。

---

## 3. 逐文件审查（Java 端）

### 3.1 `tool/process/OwnedProcess.java`（新增 257 行）

**做了什么**：Linux 下用 `setsid /bin/bash -c 'IFS= read -r admission; …; exec "$@"'` 包装用户命令，使命令成为独立会话/进程组组长；启动前用 `/proc/<pid>/stat`（pgrp/session/starttime/state）确认身份；通过 `/proc` 扫描收集整个组的成员，并额外对每个已知成员做后代快照（覆盖 Playwright/Chromium 自行 setsid 的情况）；`terminate()` 用"组集合清空 + 主进程退出"作为确认条件，并防止 PID 复用误杀。

**正面评价**：
- `readIdentity` 的字段下标正确（`fields[2]=pgrp`、`fields[3]=session`、`fields[19]=starttime`），`comm` 内含空格/括号用 `lastIndexOf(')')` 处理是正确做法。
- PID 复用防护（`startTicks` 比对 + 双读校验 `identity/after`）比旧实现严谨得多。
- `gracefulRootFirst`（根先收 SIGTERM 让 uvicorn 自己关浏览器，再在宽限期后强杀全组）与"根先退出再清后代"的语义都是真实问题驱动的设计，测试覆盖到位。
- `terminate` 中 `Thread.interrupted()` 保存/恢复中断状态，是正确的取消安全写法。

**问题**：

1. **F2（P1）确认条件过严导致降级路径丧失**。`OwnedProcess.java:105-140`：`inspected` 只在 `liveMembers()` 成功时置 true，而返回 true 的必要条件包含 `inspected`。只要 `liveMembers()` 抛 `IOException|RuntimeException`（`/proc` 扫描或 `readIdentity` 失败），且该失败在 2s 内持续，`terminate` 必然返回 false。对比被删除的旧实现（本提交 diff）：
   ```java
   Process process = owned.process();
   if (!process.isAlive()) return true;   // 旧：主进程已死即视为成功，枚举失败仅降级
   ```
   旧行为明确是"best-effort + 主进程必须终止"（`ProcessTreeManager` 的旧注释与 `ProcessTreeManagerTest` 都写明了"受限主机枚举不可用"是已知场景）。新行为把该场景变成"永不确认"，并（因 F1 与 `BashTool:408`）放大为"每条命令失败 + 每条命令泄漏一个容量槽"。**这是本次改动里我认为最需要修的回退**。
   建议：把返回条件放宽为"主进程已退出 **且**（枚举成功且组内为空 **或** 枚举不可用且 `known` 为空）"，即恢复 best-effort 语义；同时继续用 `descendantTrackingUnavailable` 元数据把不确定性上报。

2. **F8（P3）`readIdentity` 的解析脆弱**。`OwnedProcess.java:215-223` 只捕获 `NoSuchFileException`；若读到空/截断内容，`lastIndexOf(')')` 返回 −1 → `substring(1)` → `StringIndexOutOfBoundsException`（`NumberFormatException` 同理）。在 `start()` 里它会被 `catch (… RuntimeException …)` 吞成 `PROCESS_GROUP_UNAVAILABLE`（低概率但会导致单条命令直接启动失败）；在 `liveMembers()` 里会让整个组扫描中途放弃（`discovered` 结果丢失，退化为部分快照）。建议显式校验并当作"本次读取失败"处理。

3. **F8（P3）性能**：`liveMembers()` 每次迭代都全量 `Files.newDirectoryStream("/proc")` 并对每个 PID 读 2 次 `stat`（`OwnedProcess.java:160-173`），而 `terminate` 每 10ms 迭代一次、最长约 2s（前台）或 12s（Python 服务，10s 宽限）。高负载/多进程主机上这是可观测开销，且 `groupRetired` 只在"组已消失"时才置位。建议：降低扫描频率（例如先做一次组扫描，之后只对 `known` 做存活检查）、或把扫描结果缓存一个短周期。

4. **F9/F10（P3，行为差异）**：
   - 实测 `K child env='PWD=/probe|SHLVL=0|ONLY_THIS=1'`——包装器会给子进程注入 `PWD`、`SHLVL`。对 `runIsolated`（清空环境）的调用方是保真度削弱；当前唯一调用方 `MeooCliClient` 显式设置了 `PATH`，无实际影响，但语义上"清空环境"不再严格成立。
   - 子进程进入新会话且无控制终端：终端 Ctrl+C、`docker stop` 的进程组信号不再隐式传到工具子进程（旧实现同组会被连带信号）。这实际上强化了"必须靠 `@PreDestroy` 清理"的依赖，建议在 README/运维文档中写明（见 D1）。
   - 硬依赖 `setsid` + `/bin/bash`（`OwnedProcess.java:45-48`）：生产镜像 OK（已实测），但 Alpine/精简基础镜像或自定义部署会**直接全链路失败**（不是降级）。建议在探测失败时回退到"直启 + 后代清理"的旧路径并打警告。

### 3.2 `tool/process/ManagedProcessRunner.java`

**正面评价**：把"启动后注册"改成"启动前占位注册"（`putIfAbsent`），消除了重复请求造成的双重执行（有测试 `duplicateRequestDoesNotExecuteCommandOrReleaseOriginalOwnership` 验证"重复请求不执行命令"）；`leaseTransferred` 避免 `finally` 重复 `close()`；`cleanupAttempt` 用 CAS + `CompletableFuture` 实现"失败可重试、成功即缓存"，并保证"并发取消只允许一个清理执行者"（有测试断言 `maximumCleaners==1`）；`awaitDrain` 增加 `future.isDone()` 快速路径，避免清理占满预算后丢输出（有测试 `preservesCompletedOutputEvenWhenCleanupConsumesItsBudget`）；`Thread.interrupted()` 的保存/恢复也是正确的。

**问题（F1，P1）**：`runWithLease` 的 `finally`（`ManagedProcessRunner.java:166-186`）在清理未确认时**保留 `active` 条目（连带 `workLease`）与容量槽**，并只打一条 WARN（`:177`）。设计意图写在注释里："a later cancellation can retry termination"。但该前提对**已完成的 Run 不成立**：

- `RunExecutionRegistry.unregister()`（`RunExecutionRegistry.java:258-264`）先 `requestUnregister()` 再 `awaitQuiescence(2s)`；`awaitQuiescence` 以 `work` 映射为空为判据（`:643-654`），保留的 `WorkLease` 使 `work` 非空 → 返回 false → **`removeExecution` 不执行** → `byRun`/`runBySession` 双双残留。
- `QueryEngine` 只在一处调用 `unregister`（`QueryEngine.java:511-515`），且异常被吞掉、不重试。
- 下一条消息 → `QueryEngine.java:326` `runExecutions.register(newRunId, sessionId, …)` → `runBySession.putIfAbsent` 命中旧 runId → `RunExecutionRegistry.java:96` 抛 `SESSION_EXECUTION_ALREADY_REGISTERED` → 被捕获后 `handler.onError(e)` 并以 `RUN_EXECUTION_REGISTRATION_FAILED` 结束本轮（`QueryEngine.java:338-351`）。
- 唯一可能释放的入口 `ManagedProcessRunner.cancelRunDetailed`（`:311-331`）只能由 `RunTerminationCoordinator.terminate` 到达，而它要求 `beginRunTermination` → `RunControlService.requestCancel` 返回 `APPLIED`；已终态 Run 得到 `ALREADY_TERMINAL`（`RunControlService.java:118-121,206`）→ **提前返回，不会清理进程**。我的复现第 [4]/[5] 步正是这个结果。

影响面：`maxConcurrent` 默认 16，每次未确认占用 1 个槽；累积 16 次后所有会话的前台命令都会 `PROCESS_CAPACITY_EXCEEDED`（`ManagedProcessRunner.java:100`）。而**会话级锁死是不可逆的**（除重启 JVM）。这属于"低概率触发、高代价后果"，加上 F2 会把触发概率在高负载/受限主机上抬高，因此我给 P1。

建议（任一即可打断该链条，可组合）：
1. **不要让 `WorkLease` 跨越 Run 生命周期**：保留进程条目与容量槽用于重试终止，但在 Run 结束（`unregister` 路径）时释放 lease；或让 `unregister` 超时后仍移除注册。
2. 给保留条目加**有界后台回收**（例如 30s 后重试一次 `terminate`，成功即释放 lease + 容量槽）；这也顺带解决容量槽永久泄漏。
3. `register()` 遇到"stale 且已终态"的 runId 时**清理并放行**，而不是直接抛错。
4. 与 Python 侧对齐策略：Python 侧对未确认清理是"保留 + TTL 定时重试 + 上限"，Java 侧却是"永久保留、无重试"（见 §5）。

### 3.3 `tool/bash/ProcessTreeManager.java`

`destroyProcessTree` 改为委托 `OwnedProcess.terminateTree`（`:31`）。语义变化（P3，F11 同源）：
- 旧的 `!isAlive()` 早退消失：对已退出进程现在走一遍组扫描（首个迭代即返回 true，实测无额外代价，但请注意 §3.1-F2 的降级问题）。
- 删除总量变化：旧 = `grace` + 最多 5s 终局等待；新 = `grace + 2s`（`OwnedProcess.java:146`）。对 `DevServerLauncher`（`GRACE_PERIOD=2s`）而言预算从 7s 降到 4s，更容易出现"unconfirmed"（进而触发 F4 的硬失败分支）。
- 类注释已更新（诚实标注"普通 Process 只能清理调用时仍可发现的后代"），这点做得对。

### 3.4 `tool/impl/BashTool.java`（F4，P2）

新增分支（`:408-418`）位于 `shellStateManager.updateStateFromSnapshot(sessionId)`（`:419`）**之前**，因此：

- 命令 `exit 0` 也会返回 `isError=true`、`failureCode=PROCESS_TERMINATION_UNCONFIRMED`、`EffectState.UNKNOWN`、`Retryability.NEVER`；
- 该命令的 shell 状态（cwd/变量快照）**不会回写**。若命令本身包含 `cd`，下一轮命令会拿到过期的工作目录，而模型看到的是"失败/状态未知"，容易出现"命令明明生效了却报错、状态却不一致"的困惑。建议把"状态回写"与"清理确认"解耦（状态回写按 shell 自身退出码决定），或至少在提示文案里说明"命令已执行，仅子进程清理未确认"。
- 沙箱分支同样新增（`:477-484`），但文案为"container cleanup could not be confirmed"，语义合理。
- 命名冲突：该 tool 失败码与 `RunEnvelope.RunExitReason.PROCESS_TERMINATION_UNCONFIRMED`（`RunEnvelope.java:32`）**同名不同命名空间**。两者分别代表"工具级清理未确认"和"Run 级进程未确认"，日志/前端/告警检索时极易混淆。建议改名（如 `TOOL_PROCESS_TERMINATION_UNCONFIRMED`）或在文档中明确区分。
- 测试 `BashToolFailureClassificationTest.foregroundExitZeroDoesNotHideUnconfirmedCleanup` 覆盖了该分支（含"不得回写 shell 状态"的断言），说明是有意设计；但该设计变更未见文档记录（D1）。

### 3.5 `service/PythonProcessManager.java`

- **正面**：`stopProcess` 改用 `owned.terminate(now+12s, 10_000, true)`（`:360-367`）——根进程先收 SIGTERM 让 uvicorn 有时间自己关浏览器，宽限期内不强杀组，之后强杀全组；对"未确认"保留 `processRef` 并把状态置 `FAILED`（`:131-136,181-192`），`restart()` 因此拒绝在旧实例未清干净时重启（`:197-201`）。这修掉了旧代码 `process.destroy()` 后最多等 10s 就 `destroyForcibly` 可能遗留浏览器的真实漏洞，方向正确。

**需要注意（P3）**：
- `stop()` 现在**可能不置 `STOPPED`**（未确认时置 `FAILED` 并提前返回）。调用方若把 `STOPPED` 当作"已停"的判据（如健康检查/前端状态展示），需要确认能正确处理 `FAILED`。这属于**粘性失败**：需要用人工或重启恢复，建议在日志/接口中给出明确指引。
- `catch (Error failure)`（`:164-167`）单独处理并重新抛出来释放所有权，写法正确但少见；建议注释说明意图。
- `stopProcess` 最长可阻塞 12s，而 `start/stop/restart` 都是 `synchronized`，期间其它调用方会排队——与旧实现（最多 10s）相比略增，属可接受但值得记录。
### 3.6 `verify` 包

- `BrowserVerifier.java:20-24`：`JOURNEY_TIMEOUT` 120s→130s，并新增 `deadline_epoch_ms = now + 120s`（`:44`）。**正面**：把"HTTP 超时"和"工作预算"分离，注释解释了 5s 清理 + 5s 传输余量，且客户端 `isReadOnlyEndpoint` 不包含 `/api/browser/journey/run` → 不会重试放大（我核对了 `PythonCapabilityAwareClient.java:260,314-321`，因此单次 504 不会引发 2–3 倍时长）。**问题**：绝对时间戳依赖两侧时钟（F5）。
- `JourneyRequest.java`：新增 `browserResourceId`（默认 `"rv-"+UUID`），保留 4 参构造器供既有调用方。**正面**：解决了"验证会话 id 与业务会话 id 绑定"导致并发验证互踩、以及失败快照/关闭可能作用于他人资源的隐患；`VerifyJourneyEdgeCaseTest` 新增的并发用例（2 个并发 VerifyJourney 各自持有独立快照与关闭 id，且业务会话 id 不变）是本次提交质量最高的测试之一。
- `VerifyJourneyTool.java`：`finally` 中的关闭改为检查 `success` 并只告警（`:373-385`），比旧的静默忽略好；失败快照改用同一资源 id 且 `strict_session=true`（`:754-770`），避免"快照把已关闭资源重建出来"。**问题**：`import UserJourneyVerifier`（`:24`）未使用（F7）。
- `DevServerLauncher.java`：注册顺序改为"启动即注册、失败回滚"（`:96-126`），`stop()` 未确认时回填 handle 并保留 pid 文件（`:133-140`），`runSync` 用 `primaryFailure + addSuppressed` 保留主异常、`finally` 中仅在无主异常时抛出清理失败（`:188-198`），这些都是正确的错误处理改进。**问题**：`runSync` 在 **npm install 成功**时也可能因"清理未确认"抛出 `IllegalStateException("npm install process cleanup unconfirmed")`（有测试 `successfulInstallStillFailsAndRetainsProcessWhenCleanupIsUnconfirmed` 覆盖，属有意），这意味着"依赖装好了但整体判失败"，会浪费一次 300s 的安装（建议改成告警 + 保留 handle，或在消息中明确"依赖已安装"）。

---

## 4. 逐文件审查（Python 端）

### 4.1 `services/browser_service.py`（+421/−138，本次最核心的 Python 改动）

**正面评价**（改动质量整体高于 Java 侧，抽象也更干净）：
- `_SessionCreation` 容量**预留**语义 + 锁外 Playwright I/O + `asyncio.shield(allocation)` 保留"迟到的 new_context 结果"供回滚，正确解决了"取消 Playwright RPC 并不会取消 Chromium 侧创建"这一真实泄漏（对应测试 `test_cancelled_context_rpc_retains_capacity_until_late_context_is_closed`）。
- `_finish_cleanup` 用 `shield` + 循环实现"清理不可被取消打断、之后再重抛 `CancelledError`"，是正确且少见的写法。
- `_close_resource` 用 `asyncio.wait(timeout=cleanup_timeout)` 给每个驱动调用设上限，并且**刻意不取消超时的驱动任务**、不把"被取消的关闭"当成功缓存（`browser_service.py:324-357`），避免了"重试已 latch 的 close 却返回成功"的假成功——这是本次最精细的一处正确性设计，且有对应测试。
- 失败快照与 journey 生命周期解耦：`release_session`（`:542-550`）只解除"owner 租约"、保留 context 供 Java 抓失败现场，`_close_session` 支持 `expected_session` 防误关他人资源、外部关闭会取消仍在跑的 journey owner（`:506-508`）——这些正是旧实现"验证失败后现场丢失/并发互踩"的根因修复。
- `snapshot_semantic(strict_session=True)` 改为"查表 + 命中直接返回，不再 `get_or_create`"（`:990-1001`），消除了"迟到快照把已关闭资源重建"的竞态。

**问题**：

1. **F3（P2）删除容量驱逐**：旧 `get_or_create_session` 在 `len(_sessions) >= max_sessions` 时关闭"最久未活动"的会话并继续；新实现直接 `raise RuntimeError("Browser session capacity reached")`（`:410-412`），且容量口径扩为 `sessions + creating + unclosed_contexts`。影响：普通浏览器工具（`/navigate`、`/screenshot` 等）在并发会话达到上限时**从自愈变为报错**（错误经 `BrowserResponse(success=False, error_code="RuntimeError")` 透出为工具失败），需等空闲回收（`BROWSER_IDLE_TIMEOUT_MIN` 默认 5 分钟）或人工关闭。该行为有测试覆盖（`pytest.raises(match="capacity")`），说明是有意为之，但：**(a)** 未在 CHANGELOG/README 记录；**(b)** 默认 `max_sessions=10` 且该值未出现在 `.env.example`/`docker-compose.yml`（我 grep 过，均无配置项），运维侧难以发现与调优。建议：至少保留"驱逐已**过期**会话"的自愈分支，或在容量错误信息里带上"当前/上限/可调环境变量"，并补文档。
2. **F3 附带的语义细节**：`is_expired` 现在对"仍持有 owner 租约"的会话返回 False（`:115-116`），这是必要的（否则长跑 journey 会被回收），但意味着**任何一个 owner 租约没被释放的会话都会永久占位**（`release_session` 在 journey 的 `finally` 中调用，正常路径没问题）。
3. `_close_resource` 把清理任务 key 成 `close` 调用对象，其中 `_shutdown_resources` 用 `lambda: self._cleanup_task` 作为 key（`:247-250`）——能工作但非常隐晦（"用一个 lambda 返回已取消的任务来等待维护循环退出"）。建议封装成具名方法/注释，降低维护成本。

### 4.2 `routers/journey.py`

**正面**：把 journey 执行改为"总预算内可取消 + 客户端断开即取消 + 有界清理"（`:29-56`），并用 `_run_owned_journey` 明确"正常失败保留现场、只有被中断才释放"（`:65-87`）；`finally` 里 `cancel` + `gather(return_exceptions=True)` 保证任务不会裸奔。`FastAPI` 的 `http_request: Request = None` 经我实测可正常注入（我用 `TestClient` 验证：`status: 200, {"injected":true}`），不会破坏路由签名。

**问题**：

1. **F6（P2）**：`:47-48` 只判断 `disconnected in done`，未检查 `disconnected.exception()`。watcher（`_wait_for_disconnect`）内部任何异常（例如 `is_disconnected()` 在代理/特殊 ASGI 中间件下抛错）都会被当作"客户端断开"，从而**取消一个完全正常的旅程并返回 499**；Java 侧把它映射为 `PYTHON_CALL_FAILED "Python service unreachable or timeout"`，排查方向会被误导。建议：先取 `disconnected.exception()`，有异常则记录并忽略该 watcher（退化为"无断开检测"）。
2. **F5（P2）**：`:34-37` 用绝对时间戳做差，依赖两侧时钟一致。跨主机的 Python 服务若时钟漂移，会出现"旅程被提前 504 截断"或"保护失效"（上限仍被 `min(120, …)` 兜住，所以最坏不是无限跑）。建议改为传"剩余预算毫秒"或做一次时钟偏移握手，至少留容差（例如 `deadline + 1s`）。
3. 错误码语义：504 `JOURNEY_DEADLINE_EXCEEDED` 与 499 都是 4xx/5xx 中的一支，Java 侧不区分（`retryAllowed=false` → 立即返回 empty → 统一报 `PYTHON_CALL_FAILED`）。建议在 Java 侧区分"超预算/客户端断开/服务不可达"三类，便于 RV-METRICS 归因（当前日志会把"我们主动掐断"记成"服务不可达/超时"）。
4. `mode` 字段（`journey_models.py:15`）在该路由未被使用（HTTP API 模式走 Java 的 `HttpApiVerifier`），属既有遗留，可在后续清理。

### 4.3 `services/journey_models.py` / `tests/test_journey_publication.py`

`deadline_epoch_ms: Optional[int] = None`（可选、向后兼容），`Optional` 已正确导入（我确认过 `journey_models.py:6`，不存在 NameError 导致服务无法启动的风险）。发布测试同步补了 `release_session` 桩，与新的生命周期契约一致。

---

## 5. Java 与 Python 策略不一致（跨端一致性，P2）

同一类问题（"清理未确认"）在两端采取了**相反**的策略：

| | Java `ManagedProcessRunner` | Python `BrowserService` |
|---|---|---|
| 未确认时 | 保留进程条目 + **永久保留容量槽与 Run lease**（无重试） | 保留 context/handle，**周期性重试**（每 60s 的 `_cleanup_expired_sessions` 会对 `_unclosed_contexts` 继续尝试）+ 空闲 TTL 兜底 |
| 恢复路径 | 仅"同一 Run 被取消"或 JVM 重启 | 自动重试 / TTL / 显式关闭 |
| 后果 | 会话永久不可用 + 容量槽泄漏（F1） | 有界、可自愈 |

Python 侧的做法是本次改动的正确范式（"保留但必有重试与上限"）。建议 Java 侧向 Python 对齐：**保留用于重试，但必须有有界重试与最终释放**（见 §3.2 建议 2）。

---

## 6. 测试覆盖与质量评估

### 6.1 新增/修改测试质量（总体：良好，明显高于该仓库平均水平）

- `OwnedProcessTest`（210 行）：既有真实进程用例（同组顽固子进程、脱离会话子进程、根进程 shutdown hook、中断状态保留），也有用 Mockito 精确建模"JDK 按 PID 枚举、stale 句柄仍能返回复用 PID 的子进程"的用例（`admitsDescendantsOnlyWhileOwnerIdentityRemainsAlive`），并通过反射注入 `ProcIdentity`/`known` 精确断言"不得误杀"。这是本次最有价值的测试。
- `ManagedProcessRunnerTest`（+206 行）：覆盖"重复请求不执行命令"、"启动失败不调用清理且释放容量/lease"、"启动期取消不得清理/缓存成功"、"并发取消共享一个重试"、"清理吃掉预算仍保留输出"、"前台 `&` 并行仍正常完成"。用 `ReflectionTestUtils` 注入 `Semaphore(1)` 来断言容量，手法得当。
- `DevServerLauncherTest`（122 行）：反射调用私有 `runSync` 验证"主异常保留 + 清理失败用 suppressed"；`interruptedInstallPreservesInterruptAndPrimaryFailureWhenCleanupFails` 是本提交"保留中断状态"主张的直接证据。
- Python：`test_browser_service_lifecycle.py`（816 行 / 31 用例）、`test_journey_lifecycle.py`（225 行）、`test_browser_resource_container.py`（含 `/proc` 任务/僵尸与 cgroup `pids.events` 基线对比的容器级回归）。Python 侧测试的"真实性"优于 Java 侧（用真实 `BrowserService` 只 mock 驱动）。

### 6.2 覆盖盲区（P2）

1. **平台盲区**：`OwnedProcessTest` 的 6 个 Linux 用例在 macOS 上被跳过（我的定向运行：`Tests run: 15, Skipped: 6`）。也就是说**开发机上"跑通"完全不能证明 `setsid` 路径可用**；CI（`ubuntu-latest`）会跑，但开发者的本地信心是虚高的。本次我用容器实测补上了（§2.1），建议把这类平台相关验证固化为一条可运行的脚本/容器目标（例如把 `Probe` 变成 `docker`-gated 的集成用例）。
2. **真实浏览器回收用例常年不跑**：`OwnedProcessTest.stoppedRealChromiumIsReapedByForcedServiceCleanup` 需要 `ZHIKUN_REAL_BROWSER_PROCESS_TEST=1`，我在 `.github/`、`scripts/` 全量搜索后确认**没有任何工作流设置它**；`test_browser_resource_container.py` 需要 `ZHIKUN_BROWSER_CONTAINER_TEST=1`，同样未接入 CI。这两条恰恰是本次"浏览器进程泄漏"主张的最强证据链，建议至少放进 nightly。
3. **缺失用例**：没有覆盖 F1 的关键组合——"前台清理未确认 → Run 结束 → **同一会话的下一轮 Run 能否注册**"。现有测试只断言了 `awaitQuiescence` 为 false 与"后续 cancel 可恢复"，恰好停留在问题边界之前。
4. **覆盖率门禁未在本地可执行**：`pyproject.toml` 声明 `--cov-fail-under=70`，但本地 venv 未装 `pytest-cov`（我实际运行报 `unrecognized arguments: --cov`），该门禁仅在 nightly 生效；本次新增代码量很大，建议本地也能跑一次覆盖率确认新代码被覆盖。
5. `BrowserVerifierTest` 只改了超时与 body 断言，未覆盖"`resp.empty()` 时把 504/499 记成 `PYTHON_CALL_FAILED`"这一误导性归因（对应 §4.2 问题 3）。

### 6.3 全量套件的不稳定用例（P2，非本提交引入，但会阻塞 CI）

`./mvnw -o test -DskipITs` 结果：**Tests run: 3039, Failures: 1, Errors: 0, Skipped: 76**。唯一失败：

```
ManagedProcessRunnerTest.survivingChildRetainsLeaseAfterShellExitsOrIsKilled[2]
ManagedProcessRunnerTest.java:249  Expecting value to be true but was false
（断言：runner.cancelSessionBackground("children").allTerminated()）
```

判定依据：

- 该用例（`ManagedProcessRunnerTest.java:19` 起）**在父提交中已存在**（`git archive 0db4b04^` 导出后确认），本次提交未修改该用例本身；
- 它覆盖的路径 `BackgroundProcessGroup`（自有 `terminate`，含 20ms 轮询/200ms 等待，不依赖 `OwnedProcess`）**本次未被改动**；
- 我做了量化复现：目标用例隔离运行 14 次（含 6 次 CPU 高负载）**全部通过**；整类运行 6 次**全部通过**。即 20 次未复现，仅在 3039 例的全量运行中出现 1 次。

结论：**既有的时序敏感不稳定用例**（`cancelSessionBackground` 的 2s 确认预算在重负载下不够），不是本提交引入的新缺陷；但由于 CI 会跑全量，它会让主干 CI 变红。建议：把 `cancelOwnedBy`/`backgroundGroup.terminate` 的确认改为"轮询直到预算耗尽再判定"或适度放宽预算，并在 nightly 增加重复运行以量化 flake 率。

### 6.4 会失败/会跳过的其它观察

- 定向运行 8 个相关测试类：`88 passed / 6 skipped`（跳过均为 `@EnabledOnOs(LINUX)`）——与 Python 的 `229 passed / 1 skipped` 一致，除上述 flake 外无红。
- 后端 CI 使用 `ubuntu-latest` + `./mvnw test`，因此 §2.1 的 Linux 路径在 CI 上是有覆盖的（这一点值得肯定）。

---

## 7. 规范性、可维护性与文档（D1 等）

1. **CHANGELOG 未更新（P2）**：该仓库 `CHANGELOG.md` 的惯例是逐条记录用户可见变更（`feat(web)` 提交甚至单独更新 CHANGELOG + README + 截图）。本提交包含至少 4 项用户可见变更：① 清理未确认时命令判失败（新失败码）；② 浏览器会话满时不再驱逐最旧会话；③ DevServer/npm install 的清理未确认会判失败；④ 新增 `setsid`/`bash` 运行时依赖。这些都未记录。
2. **README/docs 未更新**：搜索 `README.md`、`docs/README_EN.md`、`CHANGELOG.md` 均无 `setsid`/`OwnedProcess`/`PROCESS_TERMINATION_UNCONFIRMED` 字样；`BROWSER_MAX_SESSIONS`（容量错误的关键开关）、`BROWSER_IDLE_TIMEOUT_MIN`、`process.runner.*` 也未在 `.env.example`/`docker-compose.yml` 出现（我 grep 过）。建议把"进程所有权/清理语义/相关可调参数/平台依赖"补成一小节。
3. **提交信息**：`fix: close process and browser lifecycle leaks` 描述准确，但**未声明行为变更**（失败语义、容量策略）与**新增系统依赖**（`setsid`、`/bin/bash`）。这类"修复即改语义"的提交建议在 body 中列 Breaking/Behavior 变更段。
4. **命名冲突**：`PROCESS_TERMINATION_UNCONFIRMED` 在 tool 失败码与 Run 退出原因两处同名（§3.4），建议改名或加前缀区分。
5. **遗留不一致**：`UserJourneyVerifier` 标 `@Deprecated` 但仍在主源码树，且保留 `"rv-"+sessionId` + 120s 的旧约定；`VerifyJourneyTool` 有未使用的 import。建议删除该 verifier（或标记 `forRemoval=true` 并同步约定），以免将来被复用后与"按次资源 id"冲突。
6. **代码风格**：`OwnedProcess` 中密集使用单行 `try { … } catch … { }`（例如 `:85,90,110,134`），与该仓库多数文件的展开风格不完全一致；`2s`/`10ms`/`20ms`/`exit 125`/`cleanup_timeout=5.0` 等魔法值分散在各处，建议提为具名常量并注释（尤其 `exit 125` 的手握协议失败码只有 bash 侧可见，无注释）。
7. **可观测性**：`ManagedProcessRunner:177` 的 `log.warn("Foreground process cleanup unconfirmed: pid={}")` 未带 `runId/toolUseId`（`ProcessKey` 里有），事后难以定位到具体会话/Run；建议补 MDC 或字段。另建议增加"保留条目数/容量占用"的度量（这与 F1 的容量泄漏直接相关，便于运维发现）。

---

## 8. 对既有功能链路的逐条影响评估（本次审查重点 1）

| 链路 | 结论 | 依据 |
|---|---|---|
| BashTool 前台命令（`bash -c …`） | **正常**（常规情况）；语义变更见 F4 | 容器实测输出/err/exit/env 保真、清理确认 true（37–294ms）；`BashToolFailureClassificationTest` 7 例通过 |
| BashTool 命令报错信息 | **正常**（保真） | 实测 `exit=127` + `bash: line 1: X: command not found`，与旧实现同形 |
| BashTool 命令 stdin | **正常**（语义等价） | 实测 `I out='DONE-0'`（子进程立即 EOF，与旧 `getOutputStream().close()` 一致） |
| 后台进程（`is_background`） | **未受影响** | `startBackground`/`BackgroundProcessGroup` 本次未改动；仅 `ActiveProcess` 结构适配 |
| 命令取消 / Run 终止 | **正常**，但**未确认时无法自愈** | `cancelRunDetailed` 有测试；终态 Run 无法触达释放（F1） |
| Runner 容量（16 并发） | **有泄漏风险** | `:177-178` 保留 capacity permit，无回收（F1/F2） |
| Python 受管服务启停 | **正常**，粘性失败需注意 | 12 例测试通过；`FAILED` 状态与"重启被拒"为新语义 |
| DevServer 启停 | **正常** | `DevServerLauncherTest` 6 例通过；预算缩短与未确认语义见 F11/F4 |
| Browser/Journey 验证 | **正常**，并修掉并发互踩 | 21 例 `VerifyJourneyEdgeCaseTest` 通过（含并发快照/关闭 id 隔离） |
| 普通浏览器工具（navigate/screenshot/…） | **容量满时行为变更** | 驱逐旧会话 → 报错（F3） |
| 快照/失败现场（RV-5） | **正常且更可靠** | `strict_session=true` 不再重建已关资源；`release_session` 保留现场 |
| 前端 / CLI / 其它工具 | **未涉及** | 本次提交未改前端与其它工具源码 |

**判定**：本次改动**没有**在常规路径上造成确定性破坏；主要风险集中在"清理未确认"这一异常分支的处理策略上（F1/F2），以及若干**有意的行为/语义变更**（F3/F4/F11）需文档化与产品确认。

---

## 9. 改进建议（按优先级）

**必须修（合并前/紧随其后）**

1. **F1**：打断"保留 lease → Run 无法 unregister → 会话永久锁死"链条。至少实现"有界后台重试释放"或"unregister 超时后强制移除注册"，并补一条"未确认清理后同会话可开启下一轮 Run"的测试。
2. **F2**：恢复受限主机的 best-effort 降级（主进程已退出 + 无非空已知集合 = 确认成功），避免"每条命令失败 + 容量泄漏"。

**应当修（本迭代）**

3. **F4**：把 shell 状态回写与清理确认解耦；为 `PROCESS_TERMINATION_UNCONFIRMED` 增加"命令已执行成功"的明确文案/元数据；重命名以免与 Run 退出原因混淆。
4. **F3**：为容量错误补自愈分支（至少驱逐已过期会话）+ 错误信息带上 `max_sessions` 与环境变量名；在本提交内补 CHANGELOG。
5. **F5/F6**：journey 截止时间改为相对预算或加时钟容差；断开 watcher 区分异常与真实断开。
6. **D1**：CHANGELOG（行为变更 + 新依赖）、README（进程所有权/清理语义/可调参数/平台依赖）补全；commit body 声明行为变更。
7. **CI**：把 `ZHIKUN_REAL_BROWSER_PROCESS_TEST`/`ZHIKUN_BROWSER_CONTAINER_TEST` 接入 nightly；把 §2.1 的容器探针固化为可运行目标；量化并加固 §6.3 的既有 flaky 用例。

**可择机改善（技术债）**

8. **F7**：删除 `UserJourneyVerifier` 或同步其 id/超时约定；清理未使用 import。
9. **F8**：`readIdentity` 对空/截断读取按"读取失败"处理；`setsid`/`bash` 缺失时降级而非硬失败；降低 `/proc` 扫描频率。
10. **F9/F10/F11**：文档化环境注入差异与"新会话/无控制终端"的运维影响；DevServer 预算与 `pendingCleanup` 语义补注释；魔法数字提为常量；WARN 日志补 `runId/toolUseId` 与保留条目度量。
11. **跨端一致性**：把 Java 侧"未确认清理"策略向 Python 侧对齐（保留 + 有界重试 + TTL）。

---

## 10. 附录：复现与验证命令

```bash
# 1) 全量后端测试（得到 3039/1 failure 的既有 flake）
cd backend && ./mvnw -o test -DskipITs

# 2) 相关测试类定向运行（macOS 下 6 个 Linux 用例会被跳过）
cd backend && ./mvnw -o -q test -DfailIfNoTests=false \
  -Dtest='OwnedProcessTest,ManagedProcessRunnerTest,ProcessTreeManagerTest,BashToolFailureClassificationTest,PythonProcessManagerTest,BrowserVerifierTest,DevServerLauncherTest,VerifyJourneyEdgeCaseTest'

# 3) Python 全量测试
cd python-service && ./venv/bin/python -m pytest tests/ -q --ignore=tests/integration

# 4) Linux 生产路径实测（探针源码：/tmp/zc_linux/Probe*.java）
docker run --rm --init -v /tmp/zc_linux:/probe:ro \
  -v <repo>/backend/target/classes:/classes:ro -w /probe \
  eclipse-temurin:21-jre-noble java -cp /probe:/classes Probe

# 5) F1 会话锁死复现（源码：/tmp/zc_repro/Repro.java）
CP="<repo>/backend/target/classes:$(cat /tmp/zc_cp.txt)"
javac -proc:none -cp "$CP" -d /tmp/zc_repro /tmp/zc_repro/Repro.java
java -cp "/tmp/zc_repro:$CP" Repro
```

关键证据文件：`/tmp/zc_java_full.log`（全量套件输出）、`/tmp/zc_java_tests.log`（定向运行）、`/tmp/zc_py_tests.log`（Python）、`/tmp/zc_cls_results.txt`（整类重复运行）。

---

### 附：本报告的一处自我限制

F2 属于**静态推断**（代码路径 + 旧实现 diff 对照 + 仓库自述的受限主机场景），我没有在 `hidepid`/受限 `/proc` 主机上实测复现；F5 同样是推断（未构造时钟漂移环境）。其余结论（F1、F3、F4、F7、F11 与全部"链路正常"的判断）均有实测或测试运行结果支撑。
