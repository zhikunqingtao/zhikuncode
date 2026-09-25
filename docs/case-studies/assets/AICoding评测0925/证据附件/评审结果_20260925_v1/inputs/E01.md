# DeepSeekHarness 独立审查报告：`0db4b0498f6cf886f862fe293883256b1c52e049`

> 审查对象：`fix: close process and browser lifecycle leaks`（zhikuncode 当前 HEAD）
> 审查方式：**不采信提交说明**，从零重读全部改动 + 独立编译/执行测试 + 自研探针复现
> 审查日期：2026-09-25
> 审查结论：**方向正确、实现质量总体较高；未发现会必然打断主功能链路的缺陷，但发现 1 个"取消竞态导致 Run 终态判错"的中高风险问题，以及若干必须同步到文档/提示词的行为变更。**

---

## 0. 结论速览（TL;DR）

| 编号 | 风险 | 等级 | 是否已独立证实 | 影响面 |
|---|---|---|---|---|
| R1 | 取消落在"进程启动窗口"时，`cancelRunDetailed` 返回 `unconfirmed=1`，`RunTerminationCoordinator` 据此把 Run 终态写成 **FAILED(`PROCESS_TERMINATION_UNCONFIRMED`)**，而不是 `USER_CANCELLED` | **中高** | ✅ 探针复现 | 用户点"停止"时恰好有命令在启动 |
| R2 | 前台命令**正常结束后也会强制清扫同组子进程**，并把"清理未确认"升级为**硬失败**；该语义变更未写入工具描述/系统提示/CHANGELOG | 中 | ✅ 代码＋测试佐证 | `npm run dev &`、自建守护进程等工作流 |
| R3 | "保留所有权"= 保留容量许可，但**没有后台回收器**；累计 16 次未确认即永久 `PROCESS_CAPACITY_EXCEEDED` | 中 | ✅ 代码＋新增测试佐证 | 全部 Bash/PowerShell/沙箱调用 |
| R4 | 浏览器会话容量策略由"淘汰最旧"改为"直接拒绝"，且 `_creating`/`_unclosed_contexts` 也占额度、部分条目不可回收 | 中低 | ✅ 代码走查 | 浏览器工具、VerifyJourney |
| R5 | "检查失败"被等同于"清理未确认"：`liveMembers()` 一旦抛异常就永不确认，且会跳过 Linux 上真正权威的 `/proc` 进程组扫描 | 中低 | ✅ 探针复现 | 受限主机（沙箱/受限 sysctl/hidepid 挂载） |
| R6 | 退出码 0 的命令可能因 2s 清扫预算不足被报为失败（含 D 状态、慢关闭守护进程） | 中低 | ✅ 逻辑推演＋代码证据 | 前台 Bash |
| R7 | `terminate` 每 10ms 全量扫描 `/proc`（每 PID 读 2 次 stat），多任务并发取消时 CPU 放大 | 低 | ✅ 代码走查 | 容器内进程数多时 |
| R8 | 文档/规范未同步：CHANGELOG 未更新；`docs/local/rv5-*.md`、`docs/ZhikunCode-Architecture.html` 仍写 `rv-`+sessionId | 低 | ✅ | 维护者/后续 agent |
| R9 | 新增的"真实 Chromium 容器回归"与"真实浏览器进程"用例默认跳过，**未接入任何 CI** | 低 | ✅ | 回归保护缺口 |
| R10 | `UserJourneyVerifier`（@Deprecated 未被使用）仍用 `rv-`+sessionId 且从不关闭会话，与新所有权模型不一致 | 低（信息） | ✅ | 仅潜在 |
| R11 | 新增/存量测试存在时序脆弱性（固定 `sleep`、`cleanup_timeout=0.05`），高负载下假失败 | 低 | ✅ A/B 对比 | 评审/CI 噪声 |

**对主功能链路的总体判断**：`BashTool` 前台、后台、沙箱、`VerifyJourney`（browser/http_api 双模）、`PythonProcessManager`、`DevServerLauncher` 六条链路**均未发现确定性破坏**；R1 是唯一"改动直接引入且会产生错误终态"的问题，R2/R3/R4/R6 属于"行为收紧后暴露的新失败模式"，需要显式确认是否接受。

---

## 1. 审查对象与范围

```
commit 0db4b0498f6cf886f862fe293883256b1c52e049
Author: zhikunqingtao <alizhikun@gmail.com>
Date:   Fri Sep 25 08:41:18 2026 +0800
24 files changed, 2870 insertions(+), 400 deletions(-)
parent: e78d6867f6fcd9172500b4c2cc85d4c4d927ec34
```

| 分类 | 文件 |
|---|---|
| Java 主体（9） | `PythonProcessManager`、`ProcessTreeManager`、`BashTool`、`ManagedProcessRunner`、**`OwnedProcess`(新)**、`VerifyJourneyTool`、`BrowserVerifier`、`DevServerLauncher`、`JourneyRequest` |
| Java 测试（8） | `OwnedProcessTest`(新)、`ManagedProcessRunnerTest`、`ProcessTreeManagerTest`、`BashToolFailureClassificationTest`、`PythonProcessManagerTest`、`BrowserVerifierTest`、`DevServerLauncherTest`、`VerifyJourneyEdgeCaseTest` |
| Python 主体（3） | `services/browser_service.py`、`routers/journey.py`、`services/journey_models.py` |
| Python 测试（4） | `test_browser_resource_container.py`(新)、`test_browser_service_lifecycle.py`(新)、`test_journey_lifecycle.py`(新)、`test_journey_publication.py`(1 行) |

**审查环境（真实执行环境，非纸面推演）**

- 主机：macOS（aarch64，沙箱化 JVM），Corretto JDK 21，Python 3.11.15（`python-service/venv`），Maven wrapper（离线 `~/.m2`）。
- 负载：审查期间同一工作区还有**另一个会话在跑全量 Maven 测试**，因此本报告刻意区分"负载敏感失败"与"真实失败"（见 §3）。
- 为**不修改 zhikuncode 仓库**，Java 验证在隔离副本中执行：`/tmp/zc-verify`（HEAD 源码+class）、`/tmp/zc-parent`（`git archive 0db4b04^` 的父提交），探针位于 `/tmp/probe*`。

---

## 2. 变更做了什么（重读后的中性描述）

1. **进程所有权前移**：新增 `OwnedProcess`，在 Linux 上用 `setsid /bin/bash -c 'IFS= read -r admission; [ "$admission" = start ] || exit 125; exec "$@"'` 包装命令，先建立"独立会话+进程组"，再通过 stdin 写入 `start` 放行用户代码；同时用 `/proc/<pid>/stat` 的 `pgrp/session/starttime/state` 做身份快照，防止 PID 复用误杀。
2. **前台进程改为"完成即清算"**：`ManagedProcessRunner` 在 `putIfAbsent` 阶段就预占所有权（早于 `start()`），正常结束路径也强制走 `terminate + cleanup`，未确认则保留所有权与容量许可。
3. **`BashTool` 新增失败类型** `PROCESS_TERMINATION_UNCONFIRMED`（退出码 0 但清理未确认 → 判失败）。
4. **`PythonProcessManager`/`DevServerLauncher`** 的停止逻辑改为"未确认即保留所有权/句柄，不谎报成功"。
5. **浏览器侧**：`BrowserService` 引入 `_creating` 预留、`_unclosed_contexts`、`_resource_close_tasks`、`owner_task` 租约、`_finish_cleanup` 取消安全清理，容量核算含未完成资源；`journey/run` 增加服务端 120s 截止时间、断连检测与取消-汇合。
6. **验证资源 ID 与业务会话解耦**：`JourneyRequest.browserResourceId`（`rv-`+UUID），失败快照加 `strict_session=true`，API 模式不再触发浏览器快照。

---

## 3. 独立验证结果（原始证据）

### 3.1 Java（隔离副本，HEAD）

```
Tests run: 87, Failures: 5, Errors: 0, Skipped: 6
  OwnedProcessTest          : 15 run / 0 fail / 6 skipped   （Linux-only 用例在 macOS 全跳过）
  ManagedProcessRunnerTest  : 17 run / 5 fail               ← 见下方归因
  ProcessTreeManagerTest    : 4 run / 0 fail
  BashTool…Classification   : 7 run / 0 fail
  PythonProcessManagerTest  : 12 run / 0 fail
  BrowserVerifierTest       : 5 run / 0 fail
  DevServerLauncherTest     : 6 run / 0 fail
  VerifyJourneyEdgeCaseTest : 21 run / 0 fail
```

失败用例：`survivingChildRetainsLeaseAfterShellExitsOrIsKilled[1][2]`、`backgroundProcessRemainsOwnedAndIsCancelledWithItsRun`、`backgroundSessionLeaseOutlivesLaunchAndReleasesOnlyOnProcessExit`、`cancellationIsDistinguishedFromTimeout`。

**父提交 A/B 归因（同机同参数跑 `ManagedProcessRunnerTest`）**：

```
父提交 e78d686: Tests run: 9, Failures: 4
  → survivingChildRetainsLease…[1][2] / backgroundProcessRemainsOwned… / backgroundSessionLeaseOutlives…
  （失败原因：java.io.IOException: PROCESS_GROUP_INSPECTION_FAILED —— 见 BackgroundProcessGroup.java:108）
HEAD 0db4b04  : Tests run: 17, Failures: 5（多出的第 5 个即 cancellationIsDistinguishedFromTimeout）
```

结论：
- 前 4 个失败 = **父提交同样失败的环境性问题**（本机 `ps -eo pgid=,stat=` 受限，后台进程组机制不可用），**与本次提交无关**。
- 第 5 个 `cancellationIsDistinguishedFromTimeout` 是**本次改动带来的时序窗口变化**触发（详见 R1），父提交在本次运行中通过，但两版都依赖 `Thread.sleep(100)` 的固定时序 —— 属于"测试脆弱 + 窗口变宽"的叠加。

### 3.2 Python（真实 venv）

```
全量:  3 failed, 226 passed, 1 skipped in 277s
  ✗ test_browser_service_lifecycle::test_close_during_creation_prevents_late_registration_even_if_driver_swallows_cancel
  ✗ test_browser_service_lifecycle::test_concurrent_orphan_close_cannot_restore_ownership_after_shutdown[False]
  ✗ test_token_estimation::test_estimate_batch（"响应耗时 5252.4ms 超过 500ms" —— 与本次改动无关的时延断言）
隔离重跑（两个生命周期用例）: 3 passed in 1.01s
指定 5 个受影响文件（含新增 3 个）: 通过（首次并发跑时同样出现上述 2 例）
```

→ 两个生命周期用例如在**并发高负载**下失败、隔离即通过；根因是 fixture 把 `service.cleanup_timeout` 设为 **0.05s**（`test_browser_service_lifecycle.py:18`）等实时阈值，属**测试健壮性**问题，不是被测逻辑的确定性缺陷。

### 3.3 自研探针（用于验证"未言明"的行为，`/tmp/probe*`）

| 探针 | 输入 | 输出 | 说明 |
|---|---|---|---|
| `Probe` | 默认 `ProcessBuilder` 的 stdin 重定向 | `redirectInput == Redirect.PIPE : true` | 证明 Linux 分支的"必须管道 stdin"断言不会误伤现有调用方（无任何调用方设置 `redirectInput`） |
| `Probe` | 已死根进程 + `descendants()` 抛异常 | `terminateTree=true (10ms)` | 常见路径不受受限枚举影响（`rememberDescendants` 对死进程短路） |
| `Probe` | **存活根进程 + `descendants()` 抛异常** | `terminateTree=false (2108ms)` | 受限主机上"杀进程"路径**永不确认** → 对应 R5 |
| `CancelTimelineProbe` | 启动一个前台命令并每 50ms 采样 | `t=54ms active=0`；`t=189ms active=1 process=null`；`t=247ms process=OwnedProcess`；`cancel=true` | 量化 R1 的竞态窗口（预留已登记、进程尚未创建） |
| `LaunchWindowCancelProbe` | 在"预留存在但进程为 null"瞬间调用 `cancelRunDetailed` | `active=1 confirmed=0 unconfirmed=1 allTerminated=false`；运行体自身 `cancelled=true terminationConfirmed=true` | **R1 的直接证据**：Run 会被判 `PROCESS_TERMINATION_UNCONFIRMED` |

### 3.4 兼容性核查（正向结论）

- **Python 未知字段**：`pydantic==2.10.3`，默认 `extra='ignore'` → 旧 Python 服务收到新 Java 的 `deadline_epoch_ms` 不会 422；新 Python 收到旧 Java 的请求（无该字段）也正常（字段 `Optional`）。**版本错配安全**。
- **FastAPI 参数注入**：`async def journey_run(request: JourneyRunRequest, http_request: Request = None)` 经 `TestClient` 实测可正确注入 `Request`（返回 `got_request: true`，OpenAPI 仅 1 个 body 参数），**不会导致应用启动或 422 问题**。
- **容器依赖**：运行时镜像 `eclipse-temurin:21-jre-noble`（Ubuntu 24.04，`util-linux` 提供 `/usr/bin/setsid`），且 `docker-entrypoint.sh` 已用 `#!/bin/bash` → 新引入的 `setsid`+`/bin/bash` 硬依赖在官方镜像中满足。
- **客户端重试策略**：`/api/browser/journey/run` 不在 `isReadOnlyEndpoint` 白名单 → 新的 504/499 **不会**触发指数退避重试（避免 4×120s 放大），行为符合预期。
- **API 模式**：`VerifyJourneyTool` 走 `VerifierFactory` → `HttpApiVerifier`（`/api/http/*`），故 `browserResourceId=null` 时跳过失败快照是**修复**：旧代码在 API 模式下会用 `strict_session=false` 的 `rv-`+sessionId 调 `snapshot-semantic`，从而**凭空创建一个永不关闭的浏览器 context**。本次改动把该泄漏关闭了。

---

## 4. 风险明细

### R1（中高，已证实）取消落在启动窗口 → Run 终态被误判为"进程终止未确认"

**证据链**

1. `ManagedProcessRunner.java:102-110`：`ActiveProcess` 以 `processRef=null` 先登记，然后才 `OwnedProcess.start()`（`:120-121`）。
2. `ManagedProcessRunner.terminate`（`:364-370`）：`owned.process() instanceof OwnedProcess` 为 false → **直接 return false**；`cleanup`（`:372-374`）同样 `process()==null → return false`。
3. `cancelRunDetailed`（`:311-331`）→ `found=1, confirmed=0, unconfirmed=1`。
4. `RunTerminationCoordinator.java:60-69`：`!stopped.allTerminated()` 时 `runs.fail(runId, PROCESS_TERMINATION_UNCONFIRMED, ...)`，**且该分支在 `reason == USER_CANCELLED` 之前**。
5. 探针 `LaunchWindowCancelProbe` 实测：`unconfirmed=1 allTerminated=false`，而运行体最终 `cancelled=true/terminationConfirmed=true` —— 即**进程其实被正确清理了，但 Run 被写成失败**。

**父提交对照**：父提交 `active.putIfAbsent` 在 `builder.start()` 之后（改动 diff 可证），启动期间根本不登记 → `found=0, unconfirmed=0, allTerminated()=true` → Run 正常判为 `USER_CANCELLED`。→ **本提交新引入的窗口**。

**窗口量级**：实测（高负载 macOS）从"预留可见"到"进程就绪"约 58ms；Linux 下还要叠加 setsid+准入握手（5ms 轮询），典型 10–30ms。每次前台进程启动都有一次这样的窗口。

**影响**：用户在命令启动瞬间点"停止"，Run 会以 `PROCESS_TERMINATION_UNCONFIRMED` 失败落库并在 UI 呈现为"失败"而非"已取消"。

**建议（任一即可）**
- `terminate()` 遇到 `process()==null` 时**有限等待** `processRef` 变为非空（用同一个 deadline 轮询），再执行终止；
- 或给 `ActiveProcess` 增加"启动中"状态，`CancelSummary` 将其计为"已被取消请求覆盖（confirmed）"，由 runner 的 `finally` 完成实际释放；
- 至少不要让 `process()==null` 参与 `unconfirmedCount`（它既不是"无法确认停止"，也不该拉高失败计数）。

---

### R2（中）前台命令"完成即清算 + 未确认即失败"的行为变更未对外声明

**改动**：`ManagedProcessRunner.java:146-151` 现在无论是否超时都执行 `terminate + cleanup`，`Result.terminationConfirmed` 反映结果；`BashTool.java:405-415` 在 `cancelled`/`timedOut` 之后新增分支，未确认即返回失败：

```
ToolResult.failed(PROCESS, "PROCESS_TERMINATION_UNCONFIRMED",
   "Command exited but child process cleanup could not be confirmed\n" + …,
   Retryability.NEVER, EffectState.UNKNOWN, exitCode, …)
```

**影响**
1. `bash -c "npm run dev &"` / `python manage.py runserver &` / `nohup … &` 这类**有意留驻**的子进程，现在会在命令返回后被杀掉（旧行为：保留）。
   - 这可能是有意为之（提交标题即"close process lifecycle leaks"），但**工具描述（`BashTool.java:175-197`）与系统提示均未提及**，模型仍可能按"`&` 起服务、后续 curl"的直觉工作，产生难以解释的失败。
2. 只要清扫在 2s 预算内无法确认（D 状态、需 >1s 优雅关闭且忽略 SIGTERM 的守护进程、极重负载），**退出码 0 的命令会被报成失败**，并附 `EffectState.UNKNOWN` + `Retryability.NEVER`；模型可能转而手工重试，造成副作用命令重复执行。
3. 该分支**跳过了 `shellStateManager.updateStateFromSnapshot(sessionId)`**（`:414` 之后才调用），即"清理未确认"时不再刷新 shell 状态快照，属次要副作用。

**建议**
- 在 Bash 工具描述中显式写清："前台命令返回时其派生的后台子进程会被一并终止；需要长期驻留请使用 `is_background`"；
- 或把"根已退出但后代未确认"降级为**成功 + `terminationConfirmed=false` 元数据 + 警告文本**，仅在真正需要阻断的场景（沙箱容器未清理）才是失败；
- 把未确认场景纳入可观测指标（当前只有 `log.warn`）。

---

### R3（中）"保留所有权"会永久占用容量许可，且无后台回收器

**代码**：`ManagedProcessRunner.java:166-186`（未确认 → 保留条目与 `capacity` 许可）、`:343-352`（`releaseRetained` 仅在"后台组为空 + runner 已结束 + remove 成功"时释放）、`:100`（`capacity.tryAcquire()` 失败即 `IOException("PROCESS_CAPACITY_EXCEEDED")`）。新增测试 `failedCleanupRetainsRunLeaseUntilRetryConfirmsExit` **明确断言** `capacity.availablePermits()==0` 且后续请求抛 `PROCESS_CAPACITY_EXCEEDED`。

**风险**：释放路径只有"针对同一 `(runId, toolUseId)` 的显式取消重试"或"应用关闭"。**Run 已正常结束**的场景不会有任何重试入口——泄漏的许可直到进程重启才归还。默认 `process.runner.max-concurrent=16`，累计 16 次不可恢复的未确认即导致**所有** Bash/PowerShell/沙箱调用持续失败。

**建议**：为保留条目增加带退避的后台重试/超时回收（例如 30s 后升级为告警并释放许可、或按 TTL 降级），并导出 `process_runner_retained` 指标；同时在设计上明确"容量保护 vs 可用性"的取舍。

---

### R4（中低）浏览器会话容量由"淘汰最旧"改为"直接拒绝"

**代码**：`browser_service.py:410-412`

```python
# Unclosed rollback contexts still consume capacity; never silently evict a user.
if len(self._sessions) + len(self._creating) + len(self._unclosed_contexts) >= self.max_sessions:
    raise RuntimeError("Browser session capacity reached")
```

**变化与风险**
- 旧行为：达上限时淘汰 `last_activity` 最旧的会话并继续服务；新行为：直接抛错 → 路由层转 `success=false` → 工具报错。默认 `BROWSER_MAX_SESSIONS=10`。
- 未完成资源（`_creating`、`_unclosed_contexts`）**也占额度**；其中"迟到的 `new_context` RPC 永不返回"的预留被**有意永久保留**（`:432-435`、`:468-474`、`:415-421` 注释即如此声明）。长期运行的共享服务存在"被簿记条目吃满额度且无法自愈"的理论路径。
- 淘汰最旧本身有风险（会打断他人会话），因此新策略可以理解，但缺少中间态（例如"仅淘汰已过期/空闲且非 Journey 持有"的会话）与容量告警。

**建议**：优先淘汰 `is_expired()` 且 `owner_task is None` 的会话；对 `_creating/_unclosed_contexts` 设置 TTL 与上限；暴露容量指标；必要时提高默认上限或按用户隔离配额。

---

### R5（中低）"检查失败"被当成"清理未确认"，且会跳过 Linux 权威的 `/proc` 组扫描

**代码**：`OwnedProcess.java:106-115`（`liveMembers()` 抛 `IOException|RuntimeException` → `inspected=false`，而成功判定要求 `inspected && live.isEmpty() && !delegate.isAlive()`）、`:150-153`（`rememberDescendants` 在**存活**根进程上会真正调用 `descendants()`，异常会冒泡导致整次检查作废，**后面的 `/proc` 进程组扫描根本不会执行**）。

**已证实**：探针 `live root + descendants() DENIED → terminateTree=false (2108ms)`。
**未证实但可推演**：若 `/proc` 目录枚举或 `/proc/<pid>/stat` 读取被拒（`hidepid` 挂载、受限 seccomp/沙箱），`readIdentity` 只捕获 `NoSuchFileException`，其他 IOException 会一路冒泡 → 永远 `inspected=false` → 在 Linux 上**每条前台命令都会以 `PROCESS_TERMINATION_UNCONFIRMED` 失败**（即使命令本身成功）。官方 Docker 镜像默认 `/proc` 可读，因此生产概率低，但属于"低概率-高影响"。

**建议**
- 把 `rememberDescendants` 的 `RuntimeException` **就地捕获**（旧 `ProcessTreeManager` 正是这么做的），保证 Linux 组扫描仍能执行；
- 扩容成功判定：`(inspected || groupScanDone) && live.isEmpty() && !delegate.isAlive()`；
- 明确区分"枚举能力不可用"（应作为元数据/告警）与"确有存活后代"（才是不确认）。

---

### R6（中低）退出码 0 也可能被判失败：2s 清扫预算

`cleanupDeadline = now + 2s`（`:146`），`terminateGraceMs` 默认 1000ms。若同组存在需要 >1s 才退出、或处于不可中断睡眠（D 状态）的进程，`terminate` 返回 false → R2 的失败分支。旧代码在"正常完成"路径**不做任何清扫也不判失败**，因此这是新暴露的失败模式。建议：给"根已退出"场景单独设定更宽松/可配置预算，或按"是否仍有存活后代"分级（真正残留才失败，仅超时未确认降级为元数据）。

---

### R7（低）`/proc` 全量轮询的性能放大

`OwnedProcess.java:106-135`：循环 `Thread.sleep(10)`，每次迭代调用 `liveMembers()`；后者对 `/proc` 全目录逐 PID 读 **2 次** `stat`（`:161-173`）。进程数 2000 的容器里，一次"顽固子进程"清理 ≈ 数十万次文件读取/秒。`groupRetired` 只在组为空时置位，因此"杀不死"的场景正是最耗 CPU 的场景。建议把轮询间隔提升到 50–100ms，或仅对本进程组/已知集合做增量化，避免与应用主负载争抢。

---

### R8（低）文档与规范同步缺口

- 仓库维护 `CHANGELOG.md`（含 `[Unreleased]`，父提交 `e78d686` 即更新过），**本次提交未更新**，而它包含用户可感知的行为变更（前台子进程清算、`PROCESS_TERMINATION_UNCONFIRMED`、浏览器资源 ID 与容量语义）。
- `docs/local/rv5-semantic-snapshot-on-failure.md`（多处）、`docs/local/rv1-backend-api-verification-guide.md:143`、`docs/local/zhikuncode-runtime-verification-implementation.md:591,832`、`docs/ZhikunCode-Architecture.html:4427` 仍写 `session_id="rv-"+sid`，与本提交后的"调用级唯一资源 ID"不符，会误导后续维护者与 AI agent。
- 提交信息为英文摘要，风格与仓库近期中文提交信息（如 `fix(engine): …`）不完全一致；影响很小，仅作记录。

---

### R9（低）新增的高价值回归用例默认不执行

- `OwnedProcessTest` 中 6 个用例 `@EnabledOnOs(OS.LINUX)`：在 macOS 开发机**全跳过**（本次实测 6 skipped），进程所有权逻辑在开发者本地零覆盖，只在 CI（ubuntu-latest）生效。
- `python-service/tests/test_browser_resource_container.py`（真实 Chromium + cgroup `pids.events`/zombie 统计，最有价值的泄漏回归）需 `ZHIKUN_BROWSER_CONTAINER_TEST=1`；`OwnedProcessTest.stoppedRealChromiumIsReapedByForcedServiceCleanup` 需 `ZHIKUN_REAL_BROWSER_PROCESS_TEST=1`。**仓库内没有任何 workflow/脚本设置这两个变量**（已检索 `.github/workflows/*`），即这两个"真机验证"目前只是文档化的手工步骤。
- 缺少的针对性用例：容量许可被耗尽后的恢复路径；`/proc` 不可用/`hidepid`；取消落在启动窗口（R1，现有测试用固定 `sleep` 无法稳定命中）；浏览器容量拒绝后的用户可见行为。

---

### R10（低/信息）`UserJourneyVerifier` 与新模型不一致

`UserJourneyVerifier.java:35-45`（`@Deprecated`，全仓库无注入使用）仍发送 `"rv-" + req.sessionId()`、不带 `deadline_epoch_ms`，且**从不关闭会话**。若未来被重新启用，将重现本提交刚修掉的"资源不释放"问题。

---

### R11（低）测试时序脆弱性

- 新增/存量 Java 用例大量使用 `Thread.sleep(100/350)` 后再断言（`ManagedProcessRunnerTest:325,246`），本次已在"父提交同样失败"的 A/B 中被证明对负载敏感。
- Python 侧 `cleanup_timeout=0.05/0.01`（`test_browser_service_lifecycle.py:18,460`）远低于 CI 抖动；本次全量并发跑出现 2 例假失败、隔离即通过。
- `tests/test_token_estimation.py::test_estimate_batch` 断言 `elapsed_ms < 500`，在并发负载下得到 5252ms 失败 —— 与本次改动无关，但会污染评审信号，建议放宽或改为相对基准。

---

## 5. 逐链路影响评估（回答"是否破坏正常功能链路"）

| 链路 | 结论 | 依据 |
|---|---|---|
| `BashTool` 前台（含超时/取消） | **存在受控行为变更**：新增启动竞态（R1，影响 Run 终态）、完成即清算（R2/R6）。命令执行、输出捕获、退出码映射、截断与提示逻辑未变；新增的元数据键为**增量**，旧键全部保留 | `ManagedProcessRunner.java:143-165`、`BashTool.java:390-430`、`awaitDrain` 新增 `isDone()` 快路径（`:454`）属纯优化 |
| `BashTool` 后台（`is_background`） | **未受影响**：仍走 `BackgroundProcessGroup`（本提交未改），`shutdown/cancelOwnedBy` 的改动对 `backgroundGroup!=null` 的条目是 no-op | `:365`、`:280`、`:226-228` |
| 沙箱执行（`executeSandboxed`） | **受益**：`cleanup` 的"失败不缓存、可重试、并发共享单次执行"更严格；未发现回归 | `:376-400`，新增测试 `concurrentCancellationsShareOneRetryAfterFailedCleanup` |
| `VerifyJourney`（browser 模式） | **受益**：调用级资源 ID 修复了并发验证互相关闭会话的风险（新增 `VerifyJourneyEdgeCaseTest.concurrentJourneysKeepBusinessSessionButOwnDistinctSnapshotAndCloseIds` 正是覆盖它）；`strict_session` 修复了 API 模式的 context 泄漏 | `VerifyJourneyTool.java:318,341,377-388,505-507` |
| `VerifyJourney`（http_api 模式） | **无回归**：API 模式用 `HttpApiVerifier`，`browserResourceId=null` 只影响浏览器快照分支 | `VerifierFactory.java:22-38`、`VerifyJourneyTool.java:397-423` |
| Python 服务托管 | **更严格**：未确认即保留 `processRef` 且 `restart()` 拒绝，避免"旧代未死又起新代" | `PythonProcessManager.java:128-135,182-200,360-365`；预算 10s 优雅 + 12s 上限 |
| DevServer | **更严格**：启动即登记句柄、失败复用同一清理路径；未确认保留句柄与 pid 文件（pid 文件无其他读取方，无 PID 复用误杀风险，已全仓检索 `devserver.pid`） | `DevServerLauncher.java:96-130,137-145,172-205` |
| 前端 / Web | **无改动**，无影响 | 提交未触及 `frontend/` |
| 版本错配（Java↔Python） | **双向兼容** | Pydantic `extra='ignore'` + `deadline_epoch_ms` 可选 |

---

## 6. 代码质量评价

**做得好的部分**
- `OwnedProcess` 的身份快照（`starttime` + `pgrp/session`）与"先确认身份、再放行用户代码"的准入握手，确实把"PID 复用误杀"与"启动窗口内失控"这两类老问题正面解决了；`/proc/<pid>/stat` 使用 `lastIndexOf(')')` 切分 `comm` 的细节正确。
- 取消安全清理（`_finish_cleanup` 吞掉一次取消、保留中断状态后再抛）与"失败关闭不缓存为成功"（`_close_resource` 的 `finished` 回调）体现了对 Playwright 语义的准确理解。
- 断连检测 + `asyncio.wait` + 取消-汇合（`journey.py:39-57`）比"依赖 ASGI 自动取消"更可靠；服务端截止时间与客户端 130s 超时的预算划分（120+5+5）有注释支撑。
- 新增测试普遍采用"真实状态机 + mock 驱动"（而非 mock 被测对象），并通过 `ConcurrentHashMap/CyclicBarrier` 构造真并发；`OwnedProcessTest` 用反射注入 mock 的 `ProcIdentity` 来穷举"身份存活/复用"组合，性价比高。
- 方法级注释解释了"为什么"（例如为何不取消迟到的 `new_context`、为何根优先 SIGTERM），可维护性良好。

**值得改进的部分**
- `OwnedProcess.terminate` 的判定条件与异常处理耦合过紧（R5），且 `inspected` 与 `groupScanDone` 未区分；`rememberDescendants` 与 `/proc` 扫描的失败域没有独立隔离。
- `cancel` 语义在"进程尚未创建"与"清理失败"之间没有区分（R1），直接污染了上层 `CancelSummary` 的语义。
- `_create_session` 的 `pending`/`reservation` 双分支局部变量在可读性上偏弱（读者需自行证明两条控制流互斥）；`_close_resource` 以 bound method 作为字典 key 依赖 CPython 的相等语义，建议改为显式资源键，避免隐式约定。
- `cleanup()` 的 `for(;;)` + CAS 结构虽正确，但一个方法同时承担"发起一次尝试/等待在途尝试/重试失败尝试"三种语义，建议拆分或补注释说明"每次调用至多执行一次 hook"。
- 缺少容量/保留资源的可观测指标（R3/R4）。

---

## 7. 建议的改进优先级

**P0（建议合并前处理或显式决策）**
1. R1：让"启动中的预留"不再产生 `unconfirmed`（等待 `processRef` 或引入 launch-pending 语义），或在 `RunTerminationCoordinator` 中区分该状态。
2. R2：在 Bash 工具描述/系统提示中声明"前台命令返回时会清算其派生后台进程"；并决策"未确认=失败"是否要降级。

**P1**
3. R3：为保留所有权增加后台重试/TTL 回收与指标，避免容量永久泄漏。
4. R5：隔离 `rememberDescendants` 的失败域，保留 Linux `/proc` 组扫描；区分"枚举不可用"与"存在存活后代"。
5. R4：容量拒绝前先回收"已过期且无 owner"的会话；为 `_creating/_unclosed_contexts` 加 TTL。

**P2**
6. R7 轮询退避；R8 文档/CHANGELOG 同步（尤其 `rv-` 相关 5 处文档）；R9 把两个真机用例接入 CI（哪怕 nightly）；R11 用条件等待替换固定 `sleep`、放宽与本次无关的时延断言；R10 删除或标注 `UserJourneyVerifier`。

---

## 8. 附录：可复现命令

```bash
# 1) Java（隔离副本，避免与并行会话争用 target/）
rsync -a backend/src backend/pom.xml backend/mvnw /tmp/zc-verify/ && rsync -a backend/.mvn /tmp/zc-verify/
cd /tmp/zc-verify && ./mvnw -o -B test \
  -Dtest='OwnedProcessTest,ManagedProcessRunnerTest,ProcessTreeManagerTest,BashToolFailureClassificationTest,
          PythonProcessManagerTest,BrowserVerifierTest,DevServerLauncherTest,VerifyJourneyEdgeCaseTest'

# 2) 父提交 A/B
git archive 0db4b04^ backend | tar -x -C /tmp/zc-parent --strip-components=1
cd /tmp/zc-parent && ./mvnw -o -B test -Dtest=ManagedProcessRunnerTest

# 3) Python
cd python-service && venv/bin/python -m pytest tests/ -q
venv/bin/python -m pytest tests/test_browser_service_lifecycle.py -q     # 隔离复跑

# 4) 探针（R1/R5 证据）
javac -cp backend/target/classes -d /tmp/probe /tmp/probe/Probe.java && java -cp /tmp/probe:backend/target/classes Probe
javac -cp /tmp/zc-verify/target/classes:<slf4j> -d /tmp/probe3 /tmp/probe3/LaunchWindowCancelProbe.java
java -cp /tmp/probe3:/tmp/zc-verify/target/classes:<slf4j> LaunchWindowCancelProbe
```

## 9. 补充核查（交叉校对其它评审线索后逐条独立验证）

为覆盖盲区，在完成上述独立审查后，我又对同目录其它工具报告中提出的若干线索**逐条回到代码/运行环境自行验证**，结论如下（仅记录我本人验证过的部分）：

| 补充编号 | 线索 | 我的验证结论 | 定级 |
|---|---|---|---|
| S1 | `terminate()` 与 `cleanup()`（沙箱容器的 `terminationHook`）**共享同一个 2s deadline**，`cleanup()` 在 `remainingMillis<=0` 时**不调用 hook 直接返回 false**（`ManagedProcessRunner.java:395-396、146-149`） | **属实**，但该行为在父提交同样存在（旧 `cleanup` 亦以同一 deadline 传入并在此之前返回 false）。本次的边际影响是"正常完成路径也开始消费该预算"，而正常完成时 `terminate` 通常 <10ms，故实际放大有限 | 低（既有） |
| S2 | 非 Linux 平台"终止已确认"存在**假阳性**：`OwnedProcess.start` 非 Linux 分支 `leader=null`，无会话/进程组归属，仅靠 `descendants()` 快照尽力追踪；被 `launchd` 收养或 `setsid` 逃逸的后代可在任何快照之前脱离视野 | **属实**（`OwnedProcess.java:35-44、150-153、236-250`；类注释亦自述"lifecycle ownership, not a security sandbox"）。属**设计局限而非本次回归**（父提交更弱），但说明"泄漏已闭环"在 macOS/非 Linux 与主动逃逸场景**不成立**，应在文档中明确 | 信息（非回归） |
| S3 | 沙箱分支在"清理未确认"时**丢弃命令输出**，与 `BashTool` 前台分支（会把输出附在失败消息里）不一致 | **属实**（`BashTool.java:481-489`：消息仅 "Sandbox command exited but container cleanup could not be confirmed"，未附 `stdout/stderr`；而该场景恰为 `EffectState.UNKNOWN`，模型无法据输出判断副作用是否已发生） | 中低（新暴露） |
| S4 | `PythonProcessManager` 进入 `FAILED` 后**无自动恢复路径** | **属实**（`PythonProcessManager.java:242-247`：健康检查只在 `RUNNING/HEALTH_CHECK_FAILED` 下运行，`FAILED` 时不再进入；`restart()` 又因保留的 `processRef` 直接返回 false）→ 需人工 `start()/restart()`。与本次"未确认即保留所有权"设计一致，但属可用性代价，建议纳入运维告警 | 低 |
| S5 | `setsid`/`/bin/bash` 成为 Linux 硬依赖，缺失即全量命令失败 | **已核实不构成风险**：运行时基镜像为 Ubuntu 24.04（`util-linux` 提供 `setsid`），且入口脚本本身依赖 `/bin/bash`；仅自定义精简镜像需注意 | 已排除 |

S1/S2/S3/S4 建议与正文 P1/P2 建议合并处理；S5 无需处理，但**建议在 README/Docker 文档中固化"Linux 运行时需要 setsid + bash"前提**，避免后续裁剪基础镜像时踩坑。

---

**审查声明**：本报告未修改 zhikuncode 仓库任何文件；Java 测试与探针均在 `/tmp` 隔离副本中执行。所有"已证实"结论均附可复现命令或原始输出；"推演"结论（R5 的 `/proc` 分支、R6 的 D 状态场景）已在正文中明确标注为未在本机复现的条件性风险。§9 中标注为"交叉校对线索"的条目，均已由本人回到源码/运行环境独立复核后才收录，并标注了复核结论（含"属实但非回归"与"已排除"两类）。
