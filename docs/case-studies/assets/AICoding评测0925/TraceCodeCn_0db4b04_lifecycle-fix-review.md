# 代码审查报告：`fix: close process and browser lifecycle leaks`（0db4b04）

> **审查对象**：`zhikunqingtao/zhikuncode` @ `0db4b0498f6cf886f862fe293883256b1c52e049`
> **父提交**：`e78d686`（`feat(web): confirm session deletion and clarify display modes`）
> **被审仓库工作副本**：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`（HEAD == 0db4b04，工作区干净，未做任何改动）
> **审查日期**：2026-09-25
> **审查性质**：独立复审（不采信提交信息中的结论，所有结论均由代码通读 + 实机复现重新推导）
> **审查环境**：macOS（Darwin，非 Linux）；Java 21.0.10 Amazon Corretto；Maven 3.9.9（离线）；Python 3.11.15（`python-service/venv`）
> **改动规模**：24 个文件，+2870 / −400（9 个 Java 源文件、4 个 Python 源文件、11 个测试文件）

---

## 1. 结论摘要

**对既有功能链路是否造成负面影响：未发现"破坏性"改动（无编译/接口不兼容、无跨模块调用断裂），但存在 2 项 P1 级回归与 6 项 P2 级风险。**

| 维度 | 结论 |
|---|---|
| 编译/接口兼容 | ✅ 通过。全量 Java 测试 3039 项可编译并执行；`ProcessTreeManager.destroyProcessTree`、`DevServerLauncher`、`ManagedProcessRunner` 既有调用点签名均未被破坏（仅新增 `SessionCreation`/`OwnedProcess` 等内部结构）。 |
| 非合并分支功能链路 | ⚠️ **有 1 处确定性行为回归**：Python 浏览器会话的 LRU 驱逐策略被删除，超容量由"回收最旧"变为"硬失败"（见 P1‑1，已用父提交对照实测）。 |
| 跨平台正确性 | ⚠️ **有 1 处确定性缺口**：非 Linux（macOS）平台"终止已确认"为假阳性，且该平台的子进程泄漏并未真正闭合（见 P1‑2，已实测）。 |
| 测试覆盖 | ⚠️ 核心机制（setsid 进程组）仅在 Linux 生效并入 CI；**真实浏览器泄漏场景的两个关键测试在 CI 中被 env 门禁跳过**，与提交信息"regression coverage"的表述不符。 |
| 规范性 | ⚠️ 缺少 `CHANGELOG.md` 条目；触发 `task3-5-regression`（含覆盖率门）却未同步说明。 |
| 发布建议 | **不建议以"零风险/已完备"发布。** 建议先修 P1‑1、P1‑2（或至少把 P1‑2 的"确认"语义降级为"尽力而为"并在发布说明中明示），并补 CHANGELOG 后再发布。 |

**做得好的地方（应予肯定）**：`OwnedProcess` 的 PID 复用防护（startTicks 双读校验）、admission 握手（用户代码在身份确认前不执行）、清理结果缓存与"失败不谎报成功"（fail-closed）、`_finish_cleanup` 的取消安全清理、以及 journey 侧"浏览器资源 ID 与业务 session 分离 / `strict_session` 防止失败快照重建已关闭会话"等，方向正确，确实修掉了原有的一部分真实泄漏与跨调用串扰。另外，本次为前台路径补上的 `PROCESS_TERMINATION_UNCONFIRMED` 分支与**父提交中沙箱路径已有**的同形分支保持了一致，属规范性正向改动。

---

## 2. 变更内容速览（按职责分组）

| 分组 | 文件 | 核心意图 |
|---|---|---|
| 进程归属基础设施 | [OwnedProcess.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java)（新增） | Linux 上以 `setsid` 独立会话运行，会话/组身份随 shell 退出而保留；PID 复用防护；梯度终止 |
| 前台进程监督 | [ManagedProcessRunner.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java)、[BashTool.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/impl/BashTool.java) | 启动前先"预留归属"；正常退出也回收残留子进程；清理未确认不宣告成功 |
| 进程树兼容层 | [ProcessTreeManager.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/bash/ProcessTreeManager.java) | 改为委托 `OwnedProcess.terminateTree` |
| Python 服务托管 | [PythonProcessManager.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/service/PythonProcessManager.java) | 清理未确认时保留归属并阻断重启 |
| Dev Server / 旅程 | [DevServerLauncher.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/verify/DevServerLauncher.java)、[VerifyJourneyTool.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/verify/VerifyJourneyTool.java)、[BrowserVerifier.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/verify/BrowserVerifier.java)、[JourneyRequest.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/verify/JourneyRequest.java) | 每次调用独占 `browserResourceId`；超时预算 120s+10s；快照/关闭都按资源 ID |
| 浏览器服务 | [browser_service.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/src/services/browser_service.py)（+559/−…）、[journey.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/src/routers/journey.py)、[journey_models.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/src/services/journey_models.py) | 会话创建预留/回滚、资源关闭任务去重、shutdown 取消安全、journey 超时与断连处理 |

---

## 3. 详细发现

### 🔴 P1‑1　浏览器会话 LRU 驱逐被移除 → 超容量直接失败（确定性回归，已实测对照）

**位置**：[browser_service.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/src/services/browser_service.py#L407-L414)

```python
# Unclosed rollback contexts still consume capacity; never silently evict a user.
if len(self._sessions) + len(self._creating) + len(self._unclosed_contexts) >= self.max_sessions:
    raise RuntimeError("Browser session capacity reached")
```

父提交在同一位置的行为是**驱逐最旧会话后继续创建**（`min(..., key=last_activity)` → `_close_session_unsafe(oldest)` → `logger.warning("Session limit reached, evicted oldest")`）。本提交将其整体替换为抛错。

**实测对照**（`max_sessions=2`，连续创建 4 个不同 session_id，同一份 mock 驱动）：

| 版本 | 结果 |
|---|---|
| 父提交 `e78d686` | `s0 CREATED → s1 CREATED → s2 CREATED(live ['s1','s2']) → s3 CREATED(live ['s2','s3'])`，日志 `Session limit reached, evicted oldest` |
| 当前提交 `0db4b04` | `s0 CREATED → s1 CREATED → s2 RuntimeError: Browser session capacity reached → s3 同错` |

**影响面（这正是"非合并分支的既有功能链路"）**：
1. `get_or_create_session` 是**全部**非 journey 浏览器操作的入口（`navigate/click/type/screenshot/extract_text/...` 共 13 处调用，见 [browser_service.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/src/services/browser_service.py#L605-L1001)）。默认 `BROWSER_MAX_SESSIONS=10`、空闲回收周期 5 分钟，因此"5 分钟内产生 >10 个不同 session_id"以前会自动回收，现在直接失败。
2. 新增把 `_unclosed_contexts`（清理失败的 context）计入容量：**一次 close 失败即可永久占用一个配额**，且 `startup()` 会因"unreleased resources"拒绝重启浏览器，最终只能重启整个 Python 进程才能释放。旧实现没有任何此类容量记账，不存在该死锁式占用。
3. 浏览器路由会把异常转成 `success=false`（[browser.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/src/routers/browser.py#L254-L274)），因此对用户表现为工具报错，而不是旧版的"静默回收后继续"。

**建议**：恢复 LRU 驱逐（剔除 `closing` / `owner_task` 非空 / 属于 `_unclosed_contexts` 的条目），或把"活跃会话上限"与"资源配额"分离并给出可回收策略；至少在容量检查失败时先尝试一次驱逐再重试。

---

### 🔴 P1‑2　非 Linux 平台"终止已确认"是假阳性，且该平台泄漏未真正闭合（已实测）

**位置**：[OwnedProcess.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java#L603-L614)（非 Linux 分支不建立会话组）与 [liveMembers()](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java#L720-L770)

核心机制 `setsid` 仅存在于 Linux 分支（`os.name` 非 Linux 时 `leader = null`，仅靠 `ProcessHandle.descendants()` 快照）。而 `rememberDescendants` 在父进程已退出时直接返回：

```java
private void rememberDescendants(ProcessHandle parent) {
    if (!parent.isAlive()) return;   // ← shell 退出后无法再发现其已被 reparent 的子进程
    ...
}
```

**实测证据**（macOS，脚本 `bash -c "bash -c 'echo $$ > pid; exec sleep 300' & echo spawned"`，即"前台命令派生常驻子进程"）：

```
A child pid=52776 aliveBeforeCleanup=true completed=true
A confirmed=true ms=0 childAliveAfter=true      ← 返回“已确认”，但子进程仍存活
B child pid=52793 aliveBeforeCleanup=true rootAlive=false
B confirmed=true childAliveAfter=true           ← 同上（含未调用 waitFor 的情形）
```

对照：`descendants()` 在本机可用；普通 `echo/sleep` 的 `terminate` 也能正确确认（见附录 A.1）。**问题不在"探测不可用"，而在"父进程退出后重新挂靠的子进程无法被归属，却被判定为已确认"。**

**影响**：
- `BashTool` 直接以 `terminationConfirmed` 决定成功/失败（[BashTool.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/impl/BashTool.java#L408-L418)）。在 macOS 上，一个"确实泄漏了子进程"的命令会被**宣告成功**——既没有闭合泄漏，也把不可信的信号传递给了上层。
- 本提交的核心目标（关闭进程泄漏）在**非 Linux 平台并未达成**，且提交信息中"guard descendant discovery against PID reuse / close leaks"的措辞会让人误以为跨平台成立。

**建议**：二选一——(a) 在非 Linux 明确将结果标注为 `terminationConfirmed=best-effort`（例如新增 `confirmationLevel` 字段，或让 `descendantTrackingUnavailable=true` 一并表达"仅尽力"），避免虚假成功；(b) 复用仓库已有的 POSIX 组机制（[BackgroundProcessGroup](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/BackgroundProcessGroup.java#L17-L27) 的 `set -m` + `ps -eo pgid` + `kill -- -pgid` 是 POSIX 语义，非 Linux 专属），把"前台子进程归属"也纳入同一套组身份校验。

---

### 🟠 P2‑1　清理未确认时长期占用容量与 Run lease，且缺少自动重试

**位置**：[ManagedProcessRunner.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java#L166-L186)、[releaseRetained()](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java#L343-L352)

未确认时故意保留 `active` 条目、`Semaphore` 许可（默认 16）与 `WorkLease`，注释说明"稍后 cancel 可重试"。但**除显式 `cancel/cancelRun/cancelSessionBackground/shutdown` 外没有任何自动重试路径**，而 `BashTool` 在返回失败后不会再发起 cancel。因此：

1. 连续出现 16 次无法确认的清理 → 之后所有命令 `PROCESS_CAPACITY_EXCEEDED`；
2. `WorkLease` 不释放 → `RunExecutionRegistry.awaitQuiescence` 不满足，而它被 [RunTracker](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/run/RunTracker.java#L77)、[RunTerminationCoordinator](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/run/RunTerminationCoordinator.java#L62) 以 2s 超时用于 Run 收敛，也决定 [unregister](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/run/RunExecutionRegistry.java#L258-L264) 是否能摘除执行记录；在"会话合并/终止收敛"链路上可能表现为收敛超时。

**建议**：为保留态加入有界后台重试（例如调度到独立线程，按退避重试若干次后强制回收并释放许可），或至少在保留时输出高可见度告警指标，并提供运维可触发的强制回收入口。

---

### 🟠 P2‑2　Linux 新增硬依赖 `setsid` + `/bin/bash`，缺失即全量命令失败

**位置**：[OwnedProcess.start](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java#L44-L52)

```java
if (!Files.isExecutable(setsid) || !Files.isExecutable(Path.of("/bin/bash")))
    throw new IOException("PROCESS_GROUP_UNAVAILABLE: setsid and bash are required on Linux");
```

这是 fail-closed 设计（原则正确），但把"所有 Bash 命令 / dev server / npm install"都绑到了 `util-linux` 与 `bash` 的存在性上——这是本提交**新增的运行时前提**。同时 `readIdentity` 依赖可读的 `/proc/<pid>/stat`（`hidepid`、受限沙箱会直接失败）。

现状核查：Dockerfile 运行阶段基于 `eclipse-temurin:21-jre-noble`（[Dockerfile](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/Dockerfile#L89-L148)），Ubuntu 基础镜像通常自带 `util-linux(setsid)` 与 `bash`，因此**当前官方镜像大概率满足**；但自定义/精简镜像、`docker-compose` 变体或未合并分支的本地环境不保证。

**建议**：启动期自检并在 `/api/health`（capabilities）中暴露 `setsid/bash` 可用性；README/Docker 文档显式声明该前提；错误码 `PROCESS_GROUP_UNAVAILABLE` 在工具层给出可操作提示（当前会以普通 `IOException` 冒泡到 `buildErrorWithClassification`）。

---

### 🟠 P2‑3　清理轮询的系统调用/CPU 开销（已量化）

**位置**：[liveMembers()](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java#L720-L770) 每轮轮询都会 `Files.newDirectoryStream("/proc")` 并对**主机上每个 PID** 读取一次 `/proc/<pid>/stat`；非 Linux 分支的 [waitFor()](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java#L806-L820) 每 ≤20ms 调用一次 `descendants()`。

**实测（macOS，等待 4s 子进程的进程 CPU 时间）**：

| 实现 | 进程 CPU |
|---|---|
| 裸 `Process.waitFor` | 3–5 ms |
| `OwnedProcess.waitFor` | 112–132 ms（约 **25–40×**） |

Linux 侧的影响被"组为空即立即返回"限制住（实测普通命令 `cleanupMs` 为 0–4ms），但当组非空（正是泄漏场景）时，`ManagedProcessRunner` 最多 2s、`PythonProcessManager` 最多 **12s**（grace 10s）会按 10ms 步长持续全量扫描 `/proc`——以 300 进程的机器估算可达数十万次文件读取。

**建议**：轮询间隔改为指数退避（如 20→100ms）；只对已记录的 `known` PID 集合 + 组内增量扫描，避免每次全量 `/proc`；非 Linux 分支把 `descendants()` 采样降频（如 200ms）。

---

### 🟠 P2‑4　前台命令残留子进程的语义变更（需明示）

**位置**：[ManagedProcessRunner.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java#L143-L149)

```java
// Foreground completion owns its remaining children, even after the shell has exited.
boolean terminationConfirmed = terminate(activeProcess, cleanupDeadline);
```

旧行为：命令正常结束时（`completed=true`）**不做**终止，残留子进程被保留。新行为：无论是否超时都执行 `terminate`，并按 `gracefulRootFirst=false` 对组内/已发现的成员发 SIGTERM→SIGKILL。

对"泄漏治理"这是正确的方向，但对**既有工作流是语义变更**：例如 `nohup x &`、`my_daemon &`、或"前台一条命令顺带拉起常驻服务"的用法，其子进程现在会被杀掉；若确认失败，整条命令还会被判为 `PROCESS_TERMINATION_UNCONFIRMED` 失败。虽然工具契约提供了 `is_background`（[BashTool.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/impl/BashTool.java#L333-L367)），但模型仍可能写出上述命令。

**建议**：在 CHANGELOG 以 **Breaking / Changed** 明示"前台命令不再保留其派生的常驻子进程"；如确有需要，考虑为确认失败与"成功但清理未确认"提供不同的错误码，避免把"命令本身成功但残留被清理"与"命令失败"混为一谈。

---

### 🟡 P2‑5　`BashTool` 未确认分支的连带副作用

**位置**：[BashTool.java](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/impl/BashTool.java#L408-L419)

新分支在 `shellStateManager.updateStateFromSnapshot(sessionId)` **之前**返回。因此一旦终止未确认，本轮 shell 状态快照（`cd`、导出的环境变量等）不会被更新，后续命令可能沿用过期工作目录。此外 `metadata.exitCode` 会携带真实退出码（可能为 0），但 `failureCode=PROCESS_TERMINATION_UNCONFIRMED`，对上层分类可能造成歧义。

与仓库既有约定一致（`CHANGELOG.md` 中"工具终止未确认不宣告成功"），方向正确，但建议补充：未确认时保留 shell 状态（或在 metadata 中标注 `shellStateStale=true`），并把原始退出码语义写清。

---

### 🟡 P2‑6　测试覆盖与测试健壮性

**（a）核心机制与真实场景的覆盖缺口**

| 测试 | 平台/门禁 | CI 是否执行 |
|---|---|---|
| [OwnedProcessTest](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/test/java/com/aicodeassistant/tool/process/OwnedProcessTest.java)（15 项） | 6 项 `@EnabledOnOs(OS.LINUX)` | 本机 macOS 实测 **Skipped 6**；CI 为 ubuntu-latest，会执行 ✅ |
| 真实 Chromium 回收（`stoppedRealChromiumIsReapedByForcedServiceCleanup`） | `ZHIKUN_REAL_BROWSER_PROCESS_TEST=1` + `ZHIKUN_TEST_PYTHON` | ❌ **CI 未设置**（[ci.yml](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/.github/workflows/ci.yml#L94-L123)） |
| [test_browser_resource_container.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/tests/test_browser_resource_container.py)（容器内 67 场景收敛） | `ZHIKUN_BROWSER_CONTAINER_TEST=1` | ❌ **CI 未设置**（实测 `SKIPPED: isolated Linux container only`） |

即：**"真实浏览器/容器泄漏收敛"这一本提交最核心的验收场景在 CI 中并未运行**，与提交信息"with ... regression coverage"的表述存在落差。核心机制在 macOS 开发机上同样不被覆盖（6 项 Linux 测试跳过），而实测显示 macOS 行为与 Linux 并不一致（P1‑2）。

**（b）新增测试存在紧时限/负载敏感**

整仓全量 `pytest tests` 运行中出现一次**真实失败**（CPU 高负载，load avg 峰值约 242，同时存在多个并发 maven 构建）：

```
FAILED tests/test_browser_service_lifecycle.py::test_close_during_creation_prevents_late_registration_even_if_driver_swallows_cancel
       - RuntimeError: Browser session 'resource' cleanup was not confirmed
```

单独运行该文件 **连续 3 次全部通过（49 passed）**，说明是"`cleanup_timeout=0.05s` 严苛时限 + 负载"导致的 flake，而非确定性缺陷。该用例断言的正是"关闭创建中会话必须在 50ms 内完成回滚"，对宿主调度非常敏感，建议改用事件驱动（等待 `reservation.done`）而非固定超时。

**（c）既有计时断言在负载下的 flake（本提交新增用例继承同类脆弱性）**

在 8 个测试类同批运行 + 主机高负载时，`ManagedProcessRunnerTest` 出现 4 项失败（`survivingChildRetainsLeaseAfterShellExitsOrIsKilled[1][2]`、`backgroundProcessRemainsOwnedAndIsCancelledWithItsRun`、`concurrentCancellationWaitsForTheSingleCleanupOwner`）；单独运行该文件 **17/17 全通过**。这些用例依赖 `Thread.sleep(75/350)`、`isDone()` 取反、2s 清理预算等墙钟断言，属环境性 flake——但本提交新增的 `failedCleanupRetainsRunLeaseUntilRetryConfirmsExit`、`concurrentCancellationsShareOneRetryAfterFailedCleanup` 沿用了同类模式，建议统一改为同步原语。

---

### 🟡 P2‑7　缺少 CHANGELOG 条目与变更说明

仓库维护 [CHANGELOG.md](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/CHANGELOG.md)（Keep a Changelog），本提交未添加任何条目（`git show --stat` 未包含该文件）。本次至少涉及三类需记录的行为变更：会话容量策略、前台残留子进程清理、终止确认语义。

另需注意：修改 `python-service/src/services/browser_service.py` 会触发 [task3-5-regression.yml](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/.github/workflows/task3-5-regression.yml#L4-L20)（`paths` 明确包含该文件），其中包含 `mvnw verify -Pcoverage -DcoverageFailOnViolation=true`（JaCoCo BUNDLE **INSTRUCTION ≥ 0.70**，[pom.xml](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/pom.xml#L362-L375)）与 `pytest --cov-fail-under=70`。本提交大量新增分支，发布前应确认该门通过。

---

## 4. 影响面逐链路评估

| 功能链路 | 是否受影响 | 说明 |
|---|---|---|
| 编译与模块接口 | 无 | 全量 3039 项 Java 测试可运行；公共方法签名未变 |
| Bash 前台命令 | ⚠️ 有（语义+确认语义） | 残留子进程改为被清理（P2‑4）；未确认时跳过 shell 状态更新（P2‑5）；非 Linux 确认不可信（P1‑2） |
| Bash 后台进程（`is_background`） | 基本无 | `startBackground` 走 `BackgroundProcessGroup`，本次仅适配 `ActiveProcess` 结构；实测相关用例单独运行通过 |
| 沙箱命令（`executeSandboxed`） | 无破坏（一致性改进） | 沙箱路径**早在父提交**就已存在同形的 `PROCESS_TERMINATION_UNCONFIRMED` 分支；本次为前台路径补上同一分支，使两条路径契约一致（属正向改动） |
| Python 服务托管/重启 | ⚠️ 有（更严格） | 清理未确认时保留归属并阻断 `restart()/start()`（P1‑2/P2‑1 同源）；`onShutdown` 可能记录 FAILED |
| Dev Server 启停 | ⚠️ 小 | 启动失败路径改为保留 handle 供重试；pid 文件在未确认时保留（设计如此） |
| VerifyJourney（浏览器模式） | ⚠️ 有改进亦有回归 | 资源 ID 隔离与 `strict_session` 修掉了"失败快照重建已关闭会话"；但底层受 P1‑1/P1‑2 影响 |
| VerifyJourney（HTTP 模式） | 改进 | 不再对 HTTP 模式调用失败快照（`browserResourceId == null` → 跳过），避免无意义会话创建 |
| 浏览器自动化（非 journey） | 🔴 有回归 | P1‑1：超容量由"驱逐最旧"变为"报错" |
| 会话合并/终止收敛 | ⚠️ 间接风险 | 保留态 lease 使 `awaitQuiescence` 失败（P2‑1） |
| Python 服务（Python 侧 API） | ⚠️ 行为更严格 | `close_session` 现在在清理未确认时抛 `RuntimeError`（旧版返回 True 并吞掉异常）；路由已捕获并转 `success=false`，但**其他直接调用方需注意**（`shutdown` 内部已 try/except） |

---

## 5. 实测结果汇总（本机证据）

**Java（`mvn -o -B -DskipITs test`）**：`Tests run: 3039, Failures: 2, Errors: 0, Skipped: 76`，`BUILD FAILURE`
- 失败仅为 `com.aicodeassistant.agent.ConcurrencyControlTest.testConcurrentAcquireRelease()[1][2]`（`成功数应≤全局限制30`）；该文件**不在本提交改动范围**，单独复跑亦稳定失败（7 项中 2 项失败），属**既有问题**，与本提交无因果关系，建议单独立项。
- 本提交相关测试类单独复跑均通过：`OwnedProcessTest 15（6 skipped）`、`ManagedProcessRunnerTest 17/17`、`ProcessTreeManagerTest 4/4`、`DevServerLauncherTest 6/6`、`PythonProcessManagerTest 12/12`、`BrowserVerifierTest 5/5`、`BashToolFailureClassificationTest 7/7`、`VerifyJourneyEdgeCaseTest 21/21`。

**Python（`.venv` / `venv` Python 3.11.15，`pytest tests -q`）**：`228 passed, 1 failed, 1 skipped`
- 唯一失败为 [test_token_estimation.py](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/tests/test_token_estimation.py) 的 `test_estimate_batch`（`560ms > 500ms`），**与本提交无关**（未触碰分词/估算代码），属计时断言 + 环境负载。
- 新增测试规模：`test_browser_service_lifecycle.py` 49 项、`test_journey_lifecycle.py` 12 项、`test_browser_resource_container.py` 1 项（CI 跳过）。
- 全量运行中出现 1 次新增用例 flake（见 P2‑6b），单独复跑 3 次全通过。

**独立探针（`/tmp/oprobe`，直接复用仓库 `OwnedProcess.java`）**：见 P1‑2、P2‑3 的实测数据；另附普通命令确认结果：

```
os=Mac OS X   descendants(): available
simple#0 completed=true exit=0 confirmed=true cleanupMs=4
simple#1 completed=true exit=0 confirmed=true cleanupMs=0
kill-running completed=false confirmed=true cleanupMs=32
gracefulRootFirst confirmed=true cleanupMs=40
```

---

## 6. 改进建议（按优先级）

1. **P1‑1**：恢复（或重新设计）浏览器会话容量回收策略；把 `_unclosed_contexts` 的配额占用改为"可回收/可观测"，避免一次 close 失败永久锁死容量与 `startup()`。
2. **P1‑2**：让非 Linux 的"确认"语义诚实——要么把结果降级为 best-effort 并显式暴露，要么复用 POSIX 进程组机制补齐非 Linux 的归属能力；同时补一条"父进程已退出、子进程 reparent 后"的回归测试（Linux 用组、macOS 用 ps/pgid）。
3. **CI 门禁**：为真实浏览器/容器泄漏场景增加一个**默认执行**（非 opt-in）的 Linux 作业（可用官方便携镜像 + `--init`），否则本提交最核心的验收在 CI 是空转。
4. **P2‑1**：为"清理未确认"的保留态加入**有界自动重试 + 最终强制回收**，并为容量/lease 保留打点告警。
5. **P2‑3**：轮询退避 + 增量 `/proc` 扫描；非 Linux 降频 `descendants()`。
6. **P2‑4 / P2‑5**：更新 CHANGELOG（Changed/Breaking）与工具描述；明确"命令成功但清理未确认"与"命令失败"的区分；未确认时标注 shell 状态是否陈旧。
7. **测试健壮性（P2‑6）**：把新增用例中 `cleanup_timeout=0.05`、`Thread.sleep(75/350)`、2s 清理预算等墙钟断言改为事件/闩锁驱动，降低负载敏感性；同时修正既有 `test_token_estimation` 计时断言（改为相对基线或放宽阈值）。
8. **P2‑2**：启动自检 `setsid/bash` 与 `/proc` 可读性并暴露到健康检查；文档声明运行前提。
9. **既有问题单独立项**：`ConcurrencyControlTest.testConcurrentAcquireRelease` 的"成功数 > 全局上限 30"（确定性失败）应作为独立缺陷跟踪。

---

## 7. 审查方法与局限声明

**方法**：以父提交 `e78d686` 为基线逐文件比对 diff → 通读全部受影响生产代码（非仅 diff 片段）→ 追溯所有调用方与依赖方 → 在本机实机运行 Java/Python 测试套件 → 用独立探针直接复用仓库 `OwnedProcess.java` / 父提交 `browser_service.py` 复现关键结论 → 与父提交行为做对照实验。

**局限**（请在使用本报告时纳入考量）：
1. 本机为 **macOS（非 Linux）**，`OwnedProcess` 的 `setsid` 会话组路径**无法在本机执行**（6 项 Linux 测试被跳过）。因此 Linux 组语义、`/proc` 解析、真实 Chromium 回收等结论基于**代码通读 + 设计推断**，未在本机实证；建议在 Linux 容器内补跑 `OwnedProcessTest` 与两个 opt-in 测试。
2. 未执行端到端真机验收（未启动真实 Dev Server/Chromium 完整旅程），也未做多主机时钟偏移（`deadline_epoch_ms`）验证。
3. 审查期间本机存在**其他并发 maven/pytest 进程**（load avg 峰值约 242），已通过"单独复跑对照"剥离其影响；所有被引用为"确定性"的结论均来自单独复跑或对照实验，计时类结论已明确标注为负载敏感。
4. 未修改被审仓库任何文件；所有探针与报告均落在 `/tmp` 与指定输出目录。
