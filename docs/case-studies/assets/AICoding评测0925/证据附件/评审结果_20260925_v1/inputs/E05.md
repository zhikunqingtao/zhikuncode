# ZhikunCode 提交 `0db4b04` 独立审查报告

- 审查对象：`fix: close process and browser lifecycle leaks`
- commit：`0db4b0498f6cf886f862fe293883256b1c52e049`
- 仓库：`https://github.com/zhikunqingtao/zhikuncode`
- 本地路径：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`
- 审查方式：重新独立审查（不默认提交正确），对照父提交 `e78d686` 在隔离副本 `/tmp/zk-parent` 中复现验证；未修改被审仓库任何代码。
- 审查环境：macOS（`os.name=Mac OS X`），OpenJDK 21.0.10（Corretto），Maven Wrapper，Python 3.11.15（`python-service/.venv`），FastAPI 0.115.6，Pydantic 2.10.3，pytest 8.3.4，Playwright 1.58.0。

> 重要前提：生产运行环境是 Linux（Docker runtime 为 Ubuntu Noble，含 `/bin/bash` 与 `util-linux/setsid`），本地审查环境为 macOS。本次审查对 Linux 专属路径无法直接执行，报告中会明确区分「已实测事实」与「基于代码/平台的推断」。

---

## 0. 结论摘要

本次提交方向正确：它确实修补了若干真实的进程/浏览器生命周期泄漏（`OwnedProcess` 归属、取消安全清理、失败原因保留、唯一浏览器资源 ID、失败快照 `strict_session`），测试投入也很大（新增/改动测试约 1839 行）。

但**不能默认该提交无问题**。审查发现了 **1 个高优先级功能缺陷、2 个高优先级行为变更、若干中风险回归**，其中最关键的一条会让「用户在进程启动窗口内取消运行」被错误归类为 `PROCESS_TERMINATION_UNCONFIRMED`，并在本机已导致提交自带的回归测试失败。

- 已在本机复现的失败（提交引入，父提交通过）：
  - `ManagedProcessRunnerTest.cancellationIsDistinguishedFromTimeout`
  - `ManagedProcessRunnerTest.concurrentCancellationWaitsForTheSingleCleanupOwner`（时序不稳定）
- 已在本机复现的失败（父提交同样失败，属既有环境问题，非本次引入）：
  - `ManagedProcessRunnerTest.survivingChildRetainsLeaseAfterShellExitsOrIsKilled`
  - `ProcessTreeManagerTest.processTree_allDescendantsKilled`（新断言在 `descendants()` 静默返回空的主机上会误报）

综合判断：**方向正确、实现存在真实缺陷，建议修复后再视为稳定版本**；不建议直接回滚，但需要按本报告优先级处理。

---

## 1. 变更概览

提交共 24 个文件，+2870 / −400。核心可分为四条独立链路：

| 链路 | 文件 | 意图 |
|---|---|---|
| Java 进程归属 | `OwnedProcess`（新增 257 行）、`ManagedProcessRunner`、`ProcessTreeManager`、`BashTool` | 用 Linux session/进程组归属替代 `descendants()` 尽力而为，启动前预占所有权，清理未确认时保留归属与容量 |
| Java 服务进程 | `PythonProcessManager`、`DevServerLauncher`、`RunExecutionRegistry` 交互 | 复用 `OwnedProcess`，清理失败时保留句柄/capacity，保留原始失败与中断状态 |
| 浏览器验证 | `VerifyJourneyTool`、`BrowserVerifier`、`JourneyRequest`、`journey.py` | 每次调用使用唯一浏览器资源 ID；增加绝对截止时间；客户端断开时取消并清理 |
| Python 浏览器生命周期 | `browser_service.py`（+559/−…）、`journey_models.py` | 原子预占容量、创建回滚、取消安全清理、失败上下文保留供快照、拒绝为已关闭会话重建 |

---

## 2. 对正常功能链路的影响评估（重点 1）

### 2.1 【高风险，已实测】进程「启动窗口」内的取消会被判为未确认

**问题**：`ManagedProcessRunner` 现在**先预占所有权、后启动进程**：

```java
// ManagedProcessRunner.runWithLease
ActiveProcess activeProcess = new ActiveProcess(new AtomicReference<>(), ...);
if (active.putIfAbsent(key, activeProcess) != null) { ... }
leaseTransferred.set(true);
...
Process process = OwnedProcess.start(builder);
activeProcess.processRef().set(process);   // ← 只有到这里 processRef 才非空
```

而 `cancel()` / `cancelRunDetailed()` 在看到 `processRef == null` 时直接返回「未确认」：

```java
private boolean terminate(ActiveProcess owned, long cleanupDeadlineNanos) {
    if (owned.backgroundGroup() != null) return ...;
    if (owned.process() instanceof OwnedProcess process) { ... }
    return false; // 未启动完成 → 永不确认
}

public boolean cancel(String runId, String toolUseId) {
    ActiveProcess process = active.get(new ProcessKey(runId, toolUseId));
    if (process == null) return false;
    process.cancelled().set(true);
    boolean confirmed = terminate(process, deadline) && cleanup(process, deadline);
    ...
}
```

**实测证据（本机，提交 vs 父提交）**：

- 提交上运行 `ManagedProcessRunnerTest.cancellationIsDistinguishedFromTimeout`（`bash -c "sleep 30"`，100ms 后 `runner.cancel(...)` 期望 `true`）**失败**；父提交 `e78d686` 通过。
- 探针（编译 `target/classes`，调用真实 `ManagedProcessRunner`）显示：`cancel` 时 `active` 中该条目的 `processRef == null`，因此 `cancel=false elapsedMs=20`；但随后 `cancelled=true exit=130 termConfirmed=true` —— 说明取消其实已被记录、进程最终也被终止，只是**返回值/统计错误**。

**功能影响**：`RunTerminationCoordinator.terminate` 先调用 `processes.cancelRunDetailed(runId)` 统计 `unconfirmedCount`，再 `awaitQuiescence`。启动窗口内点「停止」时，`found=1, confirmed=0`，即使随后进程正常退出并释放，运行仍可能被标记为：

```
RunEnvelope.RunExitReason.PROCESS_TERMINATION_UNCONFIRMED
```

也就是**用户正常取消被误报为“进程终止未确认”**，改变 Run 终态与 UI 文案。Linux 上 `OwnedProcess.start` 需要 `setsid` 启动 + 轮询 `/proc`，该窗口比 macOS 更长，命中概率不低。这属于本次修复引入的**新回归**（父提交在进程加入 `active` 之前取消时根本不会计入 `found`，因此不会误报）。

**建议**：
- 让 `terminate()` 对「已预占但未启动」的条目返回「待定/已接管」语义，而不是 `false`；或
- `cancelRunDetailed()` 对预占条目先等待其启动（有界，例如数百毫秒）再统计；或
- `RunTerminationCoordinator` 在 `awaitQuiescence` 之后**重新**统计一次 `processes.cancelRunDetailed(runId)` 再判定 `allTerminated`。
- 同时给「启动窗口内取消」增加一个显式回归测试（当前新增测试 `cancellationDuringLaunchDoesNotCleanOrCacheSuccessBeforeProcessExists` 覆盖了“不提前清理”，但没有断言 `cancel()` 的返回值契约）。

### 2.2 【高风险，行为变更】浏览器会话超限由「LRU 驱逐」改为「直接报错」

父提交在会话数达到 `max_sessions` 时驱逐最旧会话：

```python
if len(self._sessions) >= self.max_sessions:
    oldest_sid = min(self._sessions, key=lambda s: self._sessions[s].last_activity)
    await self._close_session_unsafe(oldest_sid)
```

提交后 `_create_session` 直接拒绝：

```python
if len(self._sessions) + len(self._creating) + len(self._unclosed_contexts) >= self.max_sessions:
    raise RuntimeError("Browser session capacity reached")
```

**功能影响**：浏览器会话默认空闲 5 分钟才回收、`BROWSER_MAX_SESSIONS` 默认 10。此前第 11 个会话会淘汰最旧会话后继续工作；现在 `navigate/click/...` 直接返回 `success=false, error_code=RuntimeError`。对长时交互式浏览器操作是明确的可用性回归。提交未更新任何文档/CHANGELOG 说明这是有意变更。

**建议**：若确为有意（避免静默驱逐用户），需要在文档与发布说明中声明，并考虑：容量满时优先回收真正空闲且无主（`owner_task is None`）的会话，或返回更明确的错误码（如 `SESSION_CAPACITY`）便于 Java/前端处理。

### 2.3 【中高风险，行为变更，仅 Linux 生效】前台命令完成后的残留子进程被强杀

提交把完成后的清理改为无条件执行：

```java
// 旧：仅当 shell 仍存活才清理
if (process.isAlive()) terminate(activeProcess, finalDeadline);
// 新：命令完成后无条件终止整个已归属范围
long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
boolean terminationConfirmed = terminate(activeProcess, cleanupDeadline);
...
boolean stopped = process == null || terminate(activeProcess, finalDeadline);
```

配合 `OwnedProcess` 在 Linux 用「session/pgid == root pid」扫描 `/proc`，**前台命令用 `cmd &`、`nohup ... &` 拉起的子进程，在 shell 退出后会被杀掉**。父提交中这些子进程会存活。

- 我在 macOS 上实测 `bash -c "sleep 30 & echo $! > child.pid; echo done"`：`childAlive=true`（因为本机 `descendants()` 返回空，走不到组扫描），即 **macOS 上不会触发该变更**；Linux 上会触发。
- 该产品已经有显式 `is_background`（`startBackground`）通道，因此这可能是有意收紧 ownership；但对习惯用 `&` 启动 dev server/daemon 的用法是破坏性变更，且与文档中「用 `kill -- -PID` 手动停止后台进程组」的说明存在语义摩擦。

**建议**：明确写出行为变更；若要保留 `&` 语义，可让 BashTool 只在「未使用 `is_background` 且命令显式声明后台」时豁免，或在工具描述中给出迁移指引；补一个 Linux 层面的「完成即回收残留子进程」测试（当前只有 `OwnedProcess` 直测，没有工具级端到端测试）。

### 2.4 【中风险，平台相关】非 Linux/受限主机上的终止确认语义变严

`OwnedProcess.terminate()` 将「无法完整枚举进程」视为**不能确认清理**：

```java
} catch (IOException | RuntimeException unavailable) {
    // Never turn an incomplete inspection into successful cleanup.
    live = known.values().stream().filter(ProcessHandle::isAlive).toList();
    inspected = false;
}
...
if (inspected && live.isEmpty() && !delegate.isAlive()) { terminated = true; return true; }
```

在 macOS sandbox 下 `ProcessHandle.descendants()` 静默返回空/或抛异常，导致：

- 本机 `ProcessTreeManagerTest.processTree_allDescendantsKilled` 因 `assertThat(children).isNotEmpty()` 失败（`descendants()` 返回空，不抛异常，所以不会走 `assumeTrue(false)` 跳过）。父提交没有这个断言，因此通过。
- 若 `descendants()` 抛异常（`inspected=false`），则即使 root 已被杀也无法返回确认。
- 本机 `ManagedProcessRunner` 的取消/超时路径因此可能长期保留 capacity 与 lease，最终触发 `PROCESS_CAPACITY_EXCEEDED`。

**功能影响**：生产 Linux 走 `/proc` 组扫描，影响小；macOS/受限主机（含部分容器安全策略）会出现「取消/超时永远未确认 + capacity 不释放」，且仓库自带测试在本机失败。

**建议**：区分「root 已确认退出且已掌握成员全部退出」与「枚举不可用」两种情况；后者可降级为「root 已退出即算确认，但标记 `descendantTrackingUnavailable=true`」，与 `Result.descendantTrackingUnavailable` 的既有语义对齐，而不是硬性 `false`。测试应对「枚举静默为空」与「枚举抛异常」都做 `assumeTrue` 跳过，而不是断言非空。

### 2.5 【中风险】失败的 context 关闭会永久占用浏览器容量

`_close_resource` 对失败的关闭任务**缓存且不重试**（有意为之，防止 Playwright 已 latch closed 却返回“假成功”）：

```python
task = self._resource_close_tasks.get(close)
if task is None:
    ... # 只创建一次
...
def finished(completed):
    succeeded = not completed.cancelled() and completed.exception() is None
    if succeeded and ...: pop(...)
```

失败任务保留在 `_resource_close_tasks`，context 进入 `_unclosed_contexts` 并计入容量。除非上层 `browser.close`/`playwright.stop` 成功触发 `_release_browser_ownership`，否则该容量**在服务生命周期内不再释放**。持续出现关闭失败时，会逐步逼近 `max_sessions` 并最终对**所有新会话**报 `capacity reached`。

**建议**：为「已确认失败且对象仍可寻址」的 context 提供显式恢复路径（如强制 `browser.close` 或重启浏览器），或至少在容量耗尽时触发一次浏览器重建；同时暴露指标便于巡检。

### 2.6 【中风险】`close_session` 契约由「返回 bool」变为「未确认时抛异常」

```python
async def close_session(self, session_id, expected_session=None) -> bool:
    ...
    if not closed:
        raise RuntimeError(f"Browser session '{session_id}' cleanup was not confirmed")
```

- 浏览器路由 `/close_session`、`/session/{id}` 与 `shutdown`、`_cleanup_expired_sessions`、`_run_owned_journey` 都有 `try/except`，主链路不会 500。
- 但这是对外 API 语义变化：Java `VerifyJourneyTool` 通过 HTTP 读取 `success`，行为可接受；任何未捕获的内部调用会变成 500。
- 结合 2.5，关闭失败会让调用方在重试时持续收到异常。

**建议**：保持「抛异常表达未确认」的设计，但在路由层统一映射为稳定错误码，并在文档中登记；或在内部改为返回结构化结果（`closed/unconfirmed`）避免异常控制流。

### 2.7 【低风险】`startup()` 拒绝在存在未释放资源时启动

新增守卫：

```python
if (self._browser or self._playwright or self._creating
        or self._unclosed_contexts or self._resource_close_tasks
        or self._starting or self._startup_cleanup or self._playwright_exit):
    raise RuntimeError("BrowserService is already started or has unreleased resources")
```

好处是防止重复启动造成多 Playwright 实例泄漏；代价是一次失败关闭可能让服务在重启前无法再次 `startup()`（必须先 `shutdown()`）。`main.py` lifespan 只启动一次，正常路径无碍。

### 2.8 正常功能链路的正向确认

以下改动方向正确、对正常链路是净收益：

- `VerifyJourneyTool` 每次调用生成唯一 `browserResourceId`，消除了同一业务 session 并发 journey 之间的浏览器资源串扰。
- `enrichWithFailureSnapshot(..., strict_session=true)` 不会再为已关闭资源重建会话。
- `journey.py` 的 deadline/disconnect 处理让超时与断连都能有界返回并清理，避免请求悬挂。
- `PythonProcessManager.stop/restart` 在清理未确认时保留 `processRef`，避免「以为停了其实还在跑」导致端口冲突。

---

## 3. 代码质量评估（重点 2）

### 3.1 优点

- 抽象合理：把 Linux session/进程组归属集中到 `OwnedProcess`，`ProcessTreeManager` 收敛为薄委托，消除了重复代码。
- 注释密度高且解释了「为什么」（如 PID 复用防护、`descendants()` 按 PID 枚举的风险、不重试已 latch 的 close）。
- 对取消安全做了系统性设计：`_finish_cleanup` 用 shield + 循环保证清理不被取消中断，再重抛 `CancelledError`；`ManagedProcessRunner` 的 `cleanupAttempt` 缓存避免并发重复执行清理 hook。
- 失败原因保真：`DevServerLauncher.runSync` 用 `primaryFailure.addSuppressed(cleanupFailure)` 保留主异常；`journey.py` 保留原 `CancelledError`，清理失败只记日志。
- 命名与结构基本符合仓库既有风格。

### 3.2 问题与坏味道

- **`cancel()` 返回值与内部行为不一致**（见 2.1）：`cancelled` 标记已生效，但函数返回 `false`，调用方无法区分「未找到/未启动/真未确认」。这是本提交最应修复的质量问题。
- **`OwnedProcess.destroy()/destroyForcibly()` 语义误导**：类注释强调「生命周期归属」，但覆写只 `delegate.destroy()` 根进程，不处理整棵树；调用者若直接 `destroyForcibly()` 会得到与类意图不符的结果。建议 `destroy()` 触发整树终止，或在 javadoc 明确「请使用 `terminate`」。
- **`cleanup()` 的重试循环复杂度偏高**：`attempt` 在循环内赋值、循环外再次使用，且「成功即缓存、失败可替换」的规则需要仔细推演；建议抽取为小的 `CleanupAttempt` 帮助方法并加注释化状态机。
- **`browser_service.py` 状态爆炸**：新增 `_creating / _unclosed_contexts / _resource_close_tasks / _starting / _startup_cleanup / _playwright_exit` 六个并发状态容器，叠加 `_lock / _lifecycle_lock / _stopping`。虽然测试充分，但可维护性下降，容易在后续改动中引入竞态。建议用更集中的生命周期对象封装。
- **`_close_resource` 的 `finished` 回调与 `_forget_released_close` 两套标签逻辑** 语义相近但不统一，读起来需要来回对照。
- 多处 `long`/`float` 秒与毫秒混算（Python `remaining`、Java `deadlineNanos`），边界值（如 `Math.max(1, ...)`）依赖调用方保证，建议补充断言/常量。
- `OwnedProcess.known` 只增不删，短生命周期下无碍，但类本身可复用，建议注明「实例不应长期存活」。

### 3.3 编译与静态质量

- 后端主源码编译通过（测试运行前已成功编译）。
- 未发现新增 `any`/不安全类型；Java 侧类型使用规范。
- 未发现吞异常导致主线崩溃的问题；清理路径普遍有上界（`cleanup_timeout=5s`、`terminate` deadline 2s/12s）。

---

## 4. 测试覆盖评估（重点 2）

### 4.1 已执行的测试与结果

**Python（`python-service/.venv`）**

```
.venv/bin/python -m pytest tests/test_journey_lifecycle.py \
  tests/test_browser_service_lifecycle.py tests/test_journey_publication.py -q
→ 65 passed

.venv/bin/python -m pytest tests/ -q --ignore=tests/integration -k "not api_key and not llm"
→ 1 failed, 228 passed, 1 skipped (25.08s)
  失败为 tests/test_token_estimation.py::test_estimate_batch（耗时 970ms > 500ms 阈值），
  与本次提交无关，属环境/冷启动时序抖动；新测试全部通过。
```

**Java（`/Users/guoqingtao/Desktop/dev/code/zhikuncode/backend`）**

```
./mvnw -o -q test -Dtest='OwnedProcessTest,ManagedProcessRunnerTest,ProcessTreeManagerTest,
  PythonProcessManagerTest,DevServerLauncherTest,BrowserVerifierTest,
  VerifyJourneyEdgeCaseTest,BashToolFailureClassificationTest'
→ Tests run: 87, Failures: 6, Errors: 0, Skipped: 6
```

失败清单与归因：

| 失败用例 | 提交上 | 父提交 | 归因 |
|---|---|---|---|
| `ManagedProcessRunnerTest.cancellationIsDistinguishedFromTimeout` | 失败 | 通过 | **本次引入**（见 2.1，启动窗口竞态） |
| `ManagedProcessRunnerTest.concurrentCancellationWaitsForTheSingleCleanupOwner` | 失败（时序不稳） | 通过 | **本次引入**（同一竞态） |
| `ManagedProcessRunnerTest.survivingChildRetainsLeaseAfterShellExitsOrIsKilled` | 失败 | 失败 | 既有 mac 环境问题 |
| `ManagedProcessRunnerTest.backgroundProcessRemainsOwnedAndIsCancelledWithItsRun` | 偶发失败 | 通过（单次） | 环境/并发时序，需在 Linux CI 复核 |
| `ProcessTreeManagerTest.processTree_allDescendantsKilled` | 失败 | 通过 | **新断言脆弱**（`descendants()` 静默为空） |

说明：父提交对比通过 `git archive e78d686 backend` 到 `/tmp/zk-parent` 后运行同样用例得到。

### 4.2 覆盖优点

- 新增 Python 测试 1256 行，覆盖：创建失败各阶段回滚、取消、容量预占、重复 ID、共享创建、关闭超时、失败 close 不可伪造成功、shutdown 中断仍释放 driver、late `new_context` 回滚、journey deadline/disconnect/外部关闭。
- `OwnedProcessTest` 用表格化 + 真实进程覆盖了 PID 复用、非目标进程不被误杀、根进程优雅退出、偏移中断状态保持等关键不变量。
- `ManagedProcessRunnerTest` 覆盖了 capacity/lease 保留、失败重试、重复请求不执行命令、完成输出在清理耗尽预算时仍保留。

### 4.3 覆盖缺口与测试质量问题

1. **最关键的“真实 Chromium 回收”测试在 CI 中不会运行**：`test_browser_resource_container.py` 需要 `ZHIKUN_BROWSER_CONTAINER_TEST=1` 及隔离容器；`OwnedProcessTest.stoppedRealChromiumIsReapedByForcedServiceCleanup` 需要 `ZHIKUN_REAL_BROWSER_PROCESS_TEST=1`。二者均未接入 CI（`ci.yml` 只跑 `pytest tests/ -v --ignore=tests/integration -k "not api_key and not llm"`）。也就是说本提交的核心承诺「服务清理后不残留 Chromium 进程」没有自动门禁。
2. **Linux 专属路径在 macOS 开发机上被跳过**（6 skipped），而恰恰是这条路径在 2.1/2.4 中暴露问题；本地开发者无法在提交前发现回归。
3. **缺少工具级端到端测试**：没有断言「前台命令 `cmd &` 完成后残留子进程被回收」（2.3）；没有路由级「session 超限」测试（2.2）。
4. **测试依赖 Playwright 私有实现**：`test_browser_service_lifecycle.py` 直接改 `context._closing_or_closed`、`context._request`、`context._channel`、`PlaywrightContextManager._exit_was_called`、`manager._connection.stop_async`。Playwright 升级即可能破坏测试，属长期维护风险。
5. **测试断言脆弱**：`ProcessTreeManagerTest` 的 `assertThat(children).isNotEmpty()` 在 `descendants()` 静默为空的主机会失败，应改为能力检测后 `assumeTrue`。
6. **提交自带测试存在平台时序假设**：`cancellationIsDistinguishedFromTimeout` 依赖「100ms 内进程已进入 `active` 且 `processRef` 非空」，在新架构下不再成立。

---

## 5. 规范性与流程评估

- **CHANGELOG 缺失**：仓库 `CHANGELOG.md` 维护 `[Unreleased]`（`### Fixed` 等），上一个提交 `e78d686` 即更新了 CHANGELOG；本提交为 `fix:` 却未新增条目。建议在 `### Fixed` 下补充（进程归属/capacity 保真、浏览器资源隔离、journey 截止时间等）。
- **提交粒度偏大**：单个 `fix` 同时包含「Java 进程归属重构」「Node/Python 服务清理」「浏览器资源生命周期」「journey 截止时间」「移除会话 LRU」五类改动，+2870 行。任一回滚都需要整体回滚，审查与二分成本高。建议按链路拆分。
- **未登记的对外行为变更**：2.2（容量策略）、2.3（残留子进程回收）、2.6（`close_session` 抛异常）都会影响调用方，需在文档/发布说明中显式声明。
- **commit message** 清晰，符合 `<type>: <描述>` 风格；正文说明了「why」，这点做得好。
- 未发现新增硬编码密钥、路径遍历、命令注入等安全问题；`OwnedProcess` 的 `setsid`/`bash` 硬依赖在官方镜像满足，但属新增部署前提，建议在 README/部署文档中注明。

---

## 6. 风险清单（按优先级）

| # | 级别 | 位置 | 问题 | 后果 | 建议 |
|---|---|---|---|---|---|
| 1 | 高 | `ManagedProcessRunner.cancel/cancelRunDetailed/terminate` | 启动窗口内 `processRef==null`，取消已记录却返回未确认 | 用户取消可能被标记 `PROCESS_TERMINATION_UNCONFIRMED`；自带测试失败 | 预占条目返回「已接管」语义或等待启动后再统计；`RunTerminationCoordinator` quiescence 后重算 |
| 2 | 高 | `browser_service._create_session` | 移除 LRU 驱逐，超限直接报错 | >`BROWSER_MAX_SESSIONS` 后浏览器工具不可用 | 确认产品意图；补文档/错误码；考虑只驱逐无主空闲会话 |
| 3 | 中高 | `ManagedProcessRunner`（Linux） | 前台完成后强杀残留子进程 | `cmd &`/`nohup` 启动的后台进程被回收 | 声明行为变更或提供豁免；补 Linux 端到端测试 |
| 4 | 中 | `OwnedProcess.terminate/liveMembers` | 枚举不可用即判未确认；`descendants()` 静默为空 | mac/受限主机取消、超时未确认，capacity 保留；测试失败 | 区分“root 确认 + 掌握成员退出”与“枚举不可用”，后者降级并打标 |
| 5 | 中 | `browser_service._close_resource/_unclosed_contexts` | 失败关闭不重试且永久占容量 | 长时间运行后所有新会话 capacity 报错 | 提供强制浏览器重建/恢复路径；加指标 |
| 6 | 中 | `browser_service.close_session` | 未确认时抛异常（契约变化） | 未捕获内部调用 500；重试持续异常 | 路由统一错误码；文档登记 |
| 7 | 中 | `browser_service.startup` | 存在未释放资源即拒绝启动 | 一次失败关闭后重启需先 shutdown | 文档化，或提供 `forceRestart` |
| 8 | 低 | `OwnedProcess` Linux 分支 | 硬依赖 `/bin/bash` + `setsid` | 精简 Linux 镜像可能无法执行任何命令 | 文档注明；失败信息已明确 |
| 9 | 低 | `OwnedProcess.destroy/destroyForcibly` | 只杀根进程，与类意图不符 | 误用者留下孤儿 | 覆写为整树终止或明确 javadoc |
| 10 | 低 | 测试 | 私有 Playwright API、脆弱非空断言、真实浏览器测试未入 CI | 升级/换机即失败；核心保证无门禁 | 收敛到公共 API；能力检测跳过；把 opt-in 测试接入定期 CI |
| 11 | 低 | `CHANGELOG.md` | 未记录修复 | 发布说明缺失 | 补 `[Unreleased] ### Fixed` |

---

## 7. 改进建议（可直接落地的顺序）

1. **先修 2.1**：这是唯一的“功能正确性”缺陷。建议最小改动：在 `terminate()` 中增加 `owned.processRef().get() == null` 时的「启动中」分支——不直接返回 `false`，而是标记该条目由启动协程负责，并在 `RunTerminationCoordinator.awaitQuiescence` 之后重新统计 `cancelRunDetailed` 的 `allTerminated`。修复后为「启动窗口取消」补一个稳定测试（不依赖 100ms sleep，可用 latch 控制启动时刻）。
2. **给 2.4 一个更合理的确认契约**：新增 `EnumerationStatus { OK, UNAVAILABLE }`，当 `root.isAlive()==false` 且已掌握成员全部退出时确认成功，同时把 `descendantTrackingUnavailable=true` 透出到 `Result`/BashTool metadata；这样既保留“绝不把不完整当成功”的初衷（对“确实还有存活成员”仍返回 false），又不因主机限制误判。
3. **明确 2.2/2.3/2.6 的产品语义** 并写进文档与 CHANGELOG；若 2.2 的非预期，恢复“只驱逐无主空闲会话”的降级逻辑。
4. **把真实浏览器回收测试纳入常规 CI**（至少 nightly）：`ZHIKUN_BROWSER_CONTAINER_TEST=1` 的容器用例 + `ZHIKUN_REAL_BROWSER_PROCESS_TEST=1` 的 JVM 用例。
5. **测试稳健化**：`ProcessTreeManagerTest` 改为能力检测；`test_browser_service_lifecycle.py` 尽量通过公开 API 或薄封装注入，减少私有属性依赖。
6. **拆分提交**：把「浏览器资源生命周期」「进程归属」「journey deadline」拆成独立提交，便于回滚与二分。
7. **补 CHANGELOG**；在部署文档注明 `setsid`/`bash` 依赖与浏览器容量语义。

---

## 8. 附：复现命令

```bash
# Python 新测试
cd /Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service
.venv/bin/python -m pytest tests/test_journey_lifecycle.py \
  tests/test_browser_service_lifecycle.py tests/test_journey_publication.py -q

# Python 全量（排除 integration/api_key/llm）
.venv/bin/python -m pytest tests/ -q --ignore=tests/integration \
  -k "not api_key and not llm"

# Java 受影响测试（提交）
cd /Users/guoqingtao/Desktop/dev/code/zhikuncode/backend
./mvnw -o -q test -Dtest='OwnedProcessTest,ManagedProcessRunnerTest,ProcessTreeManagerTest,PythonProcessManagerTest,DevServerLauncherTest,BrowserVerifierTest,VerifyJourneyEdgeCaseTest,BashToolFailureClassificationTest' -DfailIfNoTests=false

# 父提交对照（不修改被审仓库，使用 git archive 到 /tmp）
git archive e78d686 backend | tar -x -C /tmp/zk-parent
cd /tmp/zk-parent/backend && ./mvnw -o -q test \
  -Dtest='ManagedProcessRunnerTest#cancellationIsDistinguishedFromTimeout+backgroundProcessRemainsOwnedAndIsCancelledWithItsRun+survivingChildRetainsLeaseAfterShellExitsOrIsKilled+concurrentCancellationWaitsForTheSingleCleanupOwner' \
  -DfailIfNoTests=false
```

> 声明：本报告为独立审查结论，未修改 `/Users/guoqingtao/Desktop/dev/code/zhikuncode` 仓库中的任何代码；所有构建产物与隔离副本均位于 `/tmp`。测试结论与平台相关处已显式标注。
