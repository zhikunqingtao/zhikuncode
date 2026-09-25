# opencode 独立审查报告：commit `0db4b04` "fix: close process and browser lifecycle leaks"

- **审查对象**：`zhikunqingtao/zhikuncode` @ `0db4b0498f6cf886f862fe293883256b1c52e049`
- **提交信息**：`fix: close process and browser lifecycle leaks`（24 files, +2870 / -400）
- **父提交**：`e78d686`
- **审查者**：opencode（独立重新审查，未默认该提交正确）
- **审查时间**：2026-09-25
- **审查环境**：macOS (darwin)，JDK 21.0.10 (Corretto)，Python 3.11.15，`python-service/venv`
- **生产环境差异**：产品最终运行于 `eclipse-temurin:21-jre-noble` Linux 容器（Dockerfile），依赖锁定 `starlette 0.41.3 / anyio 4.13.0 / uvicorn 0.32.1 / fastapi 0.115.6`。进程组/`setsid` 相关代码仅在 Linux 生效，本机 macOS 走的是降级分支。

---

## 0. 结论摘要（TL;DR）

| 等级 | 结论 |
| --- | --- |
| **总体判断** | 提交方向正确（关闭进程/浏览器生命周期泄漏），Java 侧改动质量较好且有回归测试；**但 Python `journey` 路由新引入的“客户端断开看门狗”存在一个可 100% 复现的请求挂死（hang）缺陷，且现有测试完全覆盖不到该路径**。建议 **不要直接视为已完成/正确**，需修复后重新验证。 |
| **对现有功能链路的影响** | Java 侧正常链路基本安全；Python `journey/run` 在特定时序下会永久挂住 HTTP 请求（Java 侧表现为 130s 后才超时并报 `PYTHON_CALL_FAILED`，而非预期的 499/504），属于功能性破坏风险。 |
| **测试覆盖** | Java 新增/修改测试均通过（本地 3039 用例仅 1 个与本提交无关的既有失败）。Python 单元测试“看起来”通过，但**没有一条测试真正传入 `http_request`，因此无法发现 hang**。此外我在审查中确实观测到若干次浏览器生命周期用例的间歇性失败（见 §4.3）。 |
| **建议动作** | 修复 §3.1（阻断级）后再合并/上线；补齐真实 ASGI 集成测试；复核 §3.2/§3.3 语义变更。 |

---

## 1. 审查方法

1. 拉取 `0db4b04` 全量 diff（`git show`），逐文件阅读变更后的**完整源码**，而非只读 diff。
2. 阅读所有调用方与被影响链路（`BashTool`、`ManagedProcessRunner`、`RunTerminationCoordinator`、`ToolExecutionPipeline`、`WebBrowserTool`、`VerifyJourneyTool`、`main.py` lifespan）。
3. 独立运行测试：
   - Java：`./mvnw -o test`（全量）及针对性子集。
   - Python：`pytest tests/`（全量）及针对性子集，重复运行检测 flaky。
4. 对关键结论做**独立可执行复现**：
   - 用真实 `uvicorn` 起服务 + 真实 HTTP 客户端（`curl`）复现 `journey/run` 挂死。
   - 用最小 anyio/pytest 复现取消被 `CancelScope` 吞掉的机制。
   - 用独立 Java main 验证 `OwnedProcess` 在 macOS 降级路径下的行为。

---

## 2. 变更概览（审查范围）

**Java（backend）**
- `tool/process/OwnedProcess.java`（新增，257 行）：Linux `setsid` 进程组归属、`/proc/<pid>/stat` 身份快照、防 PID 复用、`terminate` 梯度终止。
- `tool/process/ManagedProcessRunner.java`：前台/后台进程所有权、租约转移、失败清理重试、容量槽保留。
- `tool/bash/ProcessTreeManager.java`：改为委托 `OwnedProcess.terminateTree`。
- `service/PythonProcessManager.java`：`stopProcess` 返回布尔、失败保留所有权、`restart` 受阻。
- `tool/impl/BashTool.java`：新增 `PROCESS_TERMINATION_UNCONFIRMED` 失败分支。
- `verify/{BrowserVerifier,DevServerLauncher,JourneyRequest}.java`、`tool/verify/VerifyJourneyTool.java`：浏览器资源 ID 与业务 session 解耦、超时 120→130s、失败快照 `strict_session=true`、DevServer 清理保留。
- 新增/修改测试：`OwnedProcessTest`、`ManagedProcessRunnerTest`、`DevServerLauncherTest`、`BrowserVerifierTest`、`VerifyJourneyEdgeCaseTest` 等。

**Python（python-service）**
- `services/browser_service.py`（+421/-138）：会话/创建/关闭的归属与回滚、资源关闭任务去重、启动回滚、过期清理。
- `routers/journey.py`：新增 `deadline_epoch_ms` 绝对截止时间、**客户端断开看门狗**、取消安全清理。
- `services/journey_models.py`：新增 `deadline_epoch_ms`。
- 新增测试：`test_browser_service_lifecycle.py`（816 行）、`test_journey_lifecycle.py`、`test_browser_resource_container.py`（容器内可选）。

---

## 3. 主要发现

### 3.1 【阻断级 / 高危】`journey/run` 的断开看门狗可导致请求永久挂死

**位置**：`python-service/src/routers/journey.py:28-62`

```python
async def journey_run(request: JourneyRunRequest, http_request: Request = None) -> JourneyRunResponse:
    ...
    execution = asyncio.create_task(_run_owned_journey(browser_service, request))
    disconnected = (asyncio.create_task(_wait_for_disconnect(http_request))
                    if http_request is not None else None)
    try:
        watched = {execution, disconnected} if disconnected is not None else {execution}
        done, _ = await asyncio.wait(watched, timeout=remaining, return_when=asyncio.FIRST_COMPLETED)
        if execution in done:
            return await execution
        ...
    finally:
        if not execution.done():
            execution.cancel()
        if disconnected is not None:
            disconnected.cancel()
        await asyncio.gather(*watched, return_exceptions=True)   # ← 可能永久等待

async def _wait_for_disconnect(request: Request):
    while not await request.is_disconnected():
        await asyncio.sleep(DISCONNECT_POLL_SECONDS)
```

**根因**：Starlette 0.41.3 的 `Request.is_disconnected()` 实现为（已在本机锁定版本中核对）：

```python
async def is_disconnected(self) -> bool:
    if not self._is_disconnected:
        message: Message = {}
        with anyio.CancelScope() as cs:
            cs.cancel()
            message = await self._receive()
        if message.get("type") == "http.disconnect":
            self._is_disconnected = True
    return self._is_disconnected
```

当外部对该协程调用 `task.cancel()` 且取消恰好落在该 `CancelScope` 内时，anyio 会因为 `cs._cancel_called=True` 且“无可见的父取消作用域”，**吞掉这次外部 `CancelledError` 并对 host task 执行 `uncancel()`**（`anyio._backends._asyncio.CancelScope.__exit__` 的 `_cancel_called and not _parent_cancellation_is_visible_to_us` 分支）。于是 `_wait_for_disconnect` 并未真正终止，继续进入 `asyncio.sleep(0.25)` 循环；路由 `finally` 中的 `await asyncio.gather(*watched)` 因此永远等不到 `disconnected` 完成，HTTP 请求永不返回。

**独立复现（真实 uvicorn + 真实 HTTP 请求，锁定依赖版本）**：

- 直接对真实 `/api/browser/journey/run` 发起请求，当 journey 的 `_create_context_for_journey` 立即返回（无 await 让步）时：

  ```
  delay=0    -> HTTP=000（curl 超时，请求挂死）
  delay=0.002 -> HTTP=200
  delay=0.01  -> HTTP=200
  delay=0.2   -> HTTP=200
  delay=0.5   -> HTTP=200
  ```

  连续 5 次 `delay=0` 全部 `HTTP=000`，**确定性复现**。

- 最小复现矩阵（同样真实 uvicorn）：`{POST body + 后台 watcher}` 且业务协程“同步完成”时挂死；`{无 body}` 或业务协程有 `await` 让步时正常。挂死时任务栈显示 `_wait_for_disconnect` 停在第 62 行 `await asyncio.sleep(...)`，且任务处于 `cancelling` 却永不结束。

- 机制验证（anyio `CancelScope` 吞外部取消）：

  ```
  运行: python /tmp/mech.py
    inner caught: CancelledError
    scope exited normally (cancellation swallowed)
    task finished; cancelled= False
  ```

**影响面**：
- 正常多秒级浏览器 journey 通常安全（看门狗大多处于 0.25s sleep 中）。
- 但以下场景会命中危险窗口：
  1. journey 极快完成（缓存页面、单步 screenshot 等）时；
  2. `deadline_epoch_ms` 到期（120s）触发 504、路由在 `finally` 取消两者时；
  3. 任何“取消恰好落在 `is_disconnected` 调用内”的时序。
- 后果：HTTP 不返回 → Java `BrowserVerifier` 130s 后超时 → 工具报 `PYTHON_CALL_FAILED`（而非预期的 499/504）；Python 侧每次泄漏一个挂起请求协程。这**破坏了新增的 deadline/断开语义本身**。

**为何现有测试发现不了**：
- `tests/test_journey_lifecycle.py` 全部以 `journey.journey_run(req)` 单参调用，`http_request=None` ⇒ **从不创建看门狗任务**。
- `test_disconnect_cancels_work_and_closes_context` 使用 `SimpleNamespace(is_disconnected=...)` 假对象，断开函数立即返回 `True`，**绕过了任何io `CancelScope` 的真实行为**。
- `test_browser_resource_container.py` 同样直接调用 `journey_run(request)`。
- 因此该路径 **0 有效覆盖**，测试给出虚假信心。

**修复建议（方向）**：
1. 不要把 `is_disconnected()` 直接放进可被外部 `cancel()` 的独立任务；改为在路由主协程内用 `asyncio.wait_for(asyncio.shield(execution), remaining)` + 单独的、显式可中断的断开检查，或使用 `request.receive()` 的原始消息循环并自行处理取消；
2. `finally` 中不能无条件 `gather` 看门狗；应带超时（`asyncio.wait`）并在超时后放弃/记录，避免请求被清理路径挂死；
3. 或者干脆移除该看门狗，改由 uvicorn/ASGI 层的连接关闭 + 执行侧 deadline 处理；
4. **必须补一条真实 ASGI（`httpx.ASGITransport` 或真实 uvicorn）集成测试**，覆盖立即完成、deadline 到期、客户端断开三种时序。

> 说明：这是本次审查中唯一达到“阻断”级别的问题；它不改变 Java 侧的正常业务逻辑，但会让新加的 Python 保护机制反噬主链路。

---

### 3.2 【中危 / 行为变更】前台命令“成功但留下子进程”现在被判定为失败

**位置**：`ManagedProcessRunner.java:146-166` + `BashTool.java:408-418`

改动后，前台进程**即使正常退出（`completed==true`）也会执行 `terminate(activeProcess, cleanupDeadline)`**，并以其返回值作为 `terminationConfirmed`。若仍有无法确认清除的后代（例如命令内 `some_daemon & disown`、`setsid` 逃逸、或宿主 `/proc` 受限），`terminationConfirmed=false`，`BashTool` 随即返回：

```
failureCode = PROCESS_TERMINATION_UNCONFIRMED
retryability = NEVER, effectState = UNKNOWN
```

**潜在影响**：
- 过去“exit 0 即成功”的命令，如今可能因为残留子进程而变成错误结果；`shellStateManager.updateStateFromSnapshot(sessionId)` 也被跳过（新测试 `foregroundExitZeroDoesNotHideUnconfirmedCleanup` 明确固化了该行为）。
- 从“泄漏检测”角度这是有意设计，但与既有 Bash 语义（退出码决定成败）不一致，可能影响依赖 `cmd &` 的既有工作流与 Agent 决策。建议明确该策略是否只应对“超时/取消”路径生效，或至少在错误消息中区分“主命令成功、仅清理未确认”。

### 3.3 【中危 / 行为变更】浏览器会话满载时不再驱逐最旧会话，改为直接失败

**位置**：`browser_service.py:407-412`

```python
# Unclosed rollback contexts still consume capacity; never silently evict a user.
if len(self._sessions) + len(self._creating) + len(self._unclosed_contexts) >= self.max_sessions:
    raise RuntimeError("Browser session capacity reached")
```

改动前 `get_or_create_session` 在超过 `max_sessions` 时会 `evict old`（`Session limit reached, evicted oldest`）。改动后**不再驱逐**，直接抛错。对于长时间使用 `WebBrowserTool`（`session_id` 复用、默认上限 10）的会话，若同时活跃会话达到上限，新会话会硬失败。这可能是更安全的行为，但属于**明确的对外行为破坏**，需确认前端/工具层能否优雅处理该错误。

### 3.4 【低危 / 非 Linux 降级】`OwnedProcess` 在非 Linux 平台不提供进程组归属保证

`OwnedProcess.start` 仅在 `os.name` 以 `Linux` 开头时使用 `setsid + bash` 包装并做 `/proc` 身份校验；其他平台直接 `builder.start()`，`terminate` 依赖 `ProcessHandle.descendants()` 尽力而为。这与旧 `ProcessTreeManager` 的“best-effort”契约一致，**不是回归**，但意味着 macOS 开发环境无法复现生产 Linux 的强归属行为，测试需注意平台条件（现有 Linux-only 测试已用 `@EnabledOnOs(OS.LINUX)` 标注，合理）。

### 3.5 【低危 / 死代码】`UserJourneyVerifier` 仍与旧约定耦合

`UserJourneyVerifier.run` 仍发送 `"session_id", "rv-" + req.sessionId()`（`UserJourneyVerifier.java:36`），且 `VerifyJourneyTool` 仅 import 未使用。它标注 `@Deprecated`，当前主链路走 `BrowserVerifier`，不构成破坏；但两套 `rv-` 约定并存，建议清理或统一，避免后人误用导致跨会话资源冲突。

### 3.6 【低危 / 健壮性】`journey_run` 的类型标注与参数默认

`http_request: Request = None` 的类型标注应为 `Optional[Request]`（当前写法静态检查会告警）。功能无影响。

---

## 4. 测试与验证结果

### 4.1 Java 全量测试

```
./mvnw -o test
Tests run: 3039, Failures: 1, Errors: 0, Skipped: 76
失败: com.aicodeassistant.security.WorkspaceFileBoundaryTest
      .recursiveGrepSkipsProtectedFilesButDirectAccessCanBeAuthorized:269
      Expecting "" to contain "directory-secret"
```

- 该失败在**与本提交无关的模块**（GrepTool / PathSecurityService），本提交未触碰这些文件，`git show --stat` 无相关改动。
- 单独运行该测试类仍失败，判定为**既有问题**（可能是环境相关的 ripgrep/权限行为），非本提交引入。

针对性子集（本提交相关）全部通过：

```
PythonProcessManagerTest              12/12
ProcessTreeManagerTest                 4/4
BashToolFailureClassificationTest      7/7
ManagedProcessRunnerTest              17/17
OwnedProcessTest                      15/15（6 skipped：Linux-only）
BrowserVerifierTest                    5/5
DevServerLauncherTest                  6/6
VerifyJourneyEdgeCaseTest             21/21
```

### 4.2 Python 全量测试

```
pytest tests/ --ignore=tests/test_browser_resource_container.py
229 passed（多次运行均通过）
```

单独运行浏览器生命周期测试文件时，历史上曾出现间歇失败（见 4.3），但后续 50+ 次重复运行未再现；结合 3.1 的确定性 hang，说明时序敏感度高。

### 4.3 Python 间歇性失败（审查中实测记录）

在执行初期，`tests/test_browser_service_lifecycle.py` **单独运行**时曾多次失败（有日志留存）：

```
run 1: FAILED test_close_during_creation_prevents_late_registration_even_if_driver_swallows_cancel
run 2: FAILED test_waiting_ordinary_caller_shares_failure_without_recreating[True]
       FAILED test_close_during_creation_prevents_late_registration_even_if_driver_swallows_cancel
run 3: FAILED test_waiting_ordinary_caller_shares_failure_without_recreating[True]
run 4: FAILED test_waiting_ordinary_caller_shares_failure_without_recreating[True]
       FAILED test_close_during_creation_prevents_late_registration_even_if_driver_swallows_cancel
run 5: FAILED test_close_during_creation_prevents_late_registration_even_if_driver_swallows_cancel
       FAILED test_concurrent_orphan_close_cannot_restore_ownership_after_shutdown[False]
run 6: FAILED test_concurrent_orphan_close_cannot_restore_ownership_after_shutdown[False]
```

典型断言：

```
RuntimeError: Browser session 'resource' cleanup was not confirmed
  browser_service.py:524
WARNING Timed out closing creating session resource
```

后续（含 CPU 竞争与 50 次重复）复现率降至 0。**结论**：这些用例对调度时序/共享状态较敏感，存在 flaky 倾向；CI（`ubuntu-latest` 单核负载、Python 3.11）下有失败风险，建议加固或引入确定性同步点。此项证据来自本轮审查实测，未能稳定复现，故降级为“需关注”。

### 4.4 `journey/run` 挂死复现（关键）

见 §3.1。真实 uvicorn + `curl`，锁定生产依赖版本，`delay=0` 时 5/5 次 `HTTP=000`。最小 anyio 机制同样复现。

---

## 5. 代码质量评估

**优点**
- Java 侧设计清晰：`OwnedProcess` 以“进程组 + `/proc` 身份快照”解决 PID 复用与 detached 子进程，`terminateTree` 复用统一实现；`ManagedProcessRunner` 的租约转移（`leaseTransferred`）与成功缓存清理重试（`cleanupAttempt`）逻辑严谨。
- Python 侧对“创建即取消”“晚到的 Playwright 结果”“祖先关闭赦免子资源”等并发边界的建模相当细致，注释解释了非显而易见的设计意图。
- 回归测试量可观，且为接口/边界写了针对性用例（如 `test_cancelled_context_rpc_retains_capacity_until_late_context_is_closed`）。

**不足**
- **测试与实现存在“盲区”**：`journey` 断开看门狗没有任何真实 ASGI 覆盖，导致 3.1 这类严重缺陷逃逸；`browser_service` 生命周期用例 flaky。
- **复杂度偏高**：`browser_service.py` 中 `_creating / _unclosed_contexts / _resource_close_tasks / _close_resource / _finish_cleanup / release_session / owner_task / closing / closed` 等状态相互交叠，正确性依赖于大量隐式时序，维护成本高，`_finish_cleanup` 的 `while not task.done()` + `shield` 语义对读者不友好。
- **跨平台契约不对称**：非 Linux 走完全不同分支，文档/测试需明确“强归属仅 Linux 生效”。
- **行为变更未在提交信息中声明**：3.2（成功命令可报错）、3.3（不再驱逐）都是对用户可见的语义变化，但提交信息仅描述“close leaks”。

**规范性**
- 提交信息符合 conventional commits，且是“why”导向，值得肯定。
- 缺少对“行为破坏/兼容性”的显式说明；建议在 PR 描述中列出 `PROCESS_TERMINATION_UNCONFIRMED` 与 capacity 行为变更的影响面。
- `http_request: Request = None` 等类型标注不够严谨。

---

## 6. 改进建议清单

按优先级：

1. **【必须】** 修复 `journey.py` 看门狗导致的请求挂死（§3.1），重写取消/收尾逻辑，保证 `finally` 不会无限等待看门狗。
2. **【必须】** 新增真实 ASGI 集成测试：立即完成、`deadline` 到期、客户端中途断开三类时序，验证请求确定返回且资源被释放。
3. **【建议】** 明确并文档化 `PROCESS_TERMINATION_UNCONFIRMED` 的语义：是否应仅在超时/取消路径触发；错误消息中区分“主命令成功、仅清理未确认”，避免污染正常成功结果（§3.2）。
4. **【建议】** 确认浏览器会话满载时“失败而非驱逐”的下游处理与产品预期；或在文档/前端给出可恢复指引（§3.3）。
5. **【建议】** 加固 flaky 的 `test_browser_service_lifecycle.py`：减少对 `asyncio.sleep` 的隐式依赖，使用 `Event` 精确同步；在 CI 上重复运行观察稳定性（§4.3）。
6. **【建议】** 清理/统一已废弃的 `UserJourneyVerifier` 与 `rv-` 约定（§3.5）。
7. **【建议】** 修正 `Optional[Request]` 类型标注（§3.6）。
8. **【可选】** 为 `browser_service.py` 的取消安全清理抽取更小的、可独立测试的状态机，降低整体复杂度与理解成本。

---

## 7. 复现与验证命令附录

```bash
# Java 针对性测试
cd backend && ./mvnw -o test \
  -Dtest='OwnedProcessTest,ManagedProcessRunnerTest,PythonProcessManagerTest,\
ProcessTreeManagerTest,BashToolFailureClassificationTest,DevServerLauncherTest,\
BrowserVerifierTest,VerifyJourneyEdgeCaseTest'

# Python 全量
cd python-service && ./venv/bin/python -m pytest tests -q \
  --ignore=tests/test_browser_resource_container.py

# journey 挂死复现（真实 uvicorn + curl）
# 1) 构造仅 mock browser_service 的 app（jdelay.py），JDELAY=0
JDELAY=0 PYTHONPATH=src ./venv/bin/python -m uvicorn --app-dir /tmp jdelay:app --port 9170
# 2) 另一终端
curl -s -m 4 -o /dev/null -w 'HTTP=%{http_code}\n' -X POST \
  http://127.0.0.1:9170/api/browser/journey/run \
  -H 'Content-Type: application/json' \
  -d '{"session_id":"rv-x","base_url":"http://x/","steps":[{"action":"screenshot"}]}'
# 观测：delay=0 时 HTTP=000（挂死）；delay>=0.002 时 HTTP=200

# anyio 取消吞没机制
python /tmp/mech.py   # 输出 "task finished; cancelled= False"
```

---

## 8. 最终裁定

- **是否可判定该提交“正确、无问题”？** 否。
- **是否会对 zhikuncode 正常功能链路造成负面影响或破坏？**
  - Java 进程/验证链路：**基本安全**（全量测试除一个既有无关失败外通过）。
  - Python `journey/run` 链路：**存在真实破坏风险**——新增的断开看门狗在特定时序下会使请求永久挂死，进而使 Java 侧 130s 超时并报错、Python 侧泄漏挂起协程；且该路径无有效测试覆盖。
  - 另有两处对外可见的行为变更（成功命令可判失败、满载不再驱逐）需产品确认。
- **建议**：将 §3.1 视为**合入阻断项**，修复并补齐真实集成测试后再评估；其余按 §6 逐项处理。

> 本报告所有结论均基于独立阅读完整源码、独立运行测试与真实可执行复现，不默认原提交正确。
