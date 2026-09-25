# Python 侧独立对抗性审查报告 — 0db4b049 "fix: close process and browser lifecycle leaks"

审查范围（Python 部分）：`python-service/src/routers/journey.py`、`src/services/browser_service.py`（+559）、`src/services/journey_models.py`、
`tests/test_browser_resource_container.py`、`tests/test_browser_service_lifecycle.py`、`tests/test_journey_lifecycle.py`、`tests/test_journey_publication.py`。
契约对端（只读参考）：`backend/.../verify/BrowserVerifier.java`、`JourneyRequest.java`、`tool/verify/VerifyJourneyTool.java`、`service/PythonCapabilityAwareClient.java`。
运行环境静态核对：python-service/venv（Python 3.11.15、fastapi 0.115.6、starlette 0.41.3、uvicorn 0.32.1、playwright 1.58.0）。
说明：本次只做只读静态分析 + 只读命令；未运行 pytest（另有 worker 负责）。所有"推测"均已标注。

---

## 一、发现（按严重级别）

### F1 (P2) 关闭失败的 context 永不重试，却永久占用 max_sessions 配额与 session_id；"TTL 兜底"在该场景失效
- 位置：`python-service/src/services/browser_service.py:324-357`（`_close_resource` + `finished` 回调）、`:359-373`（`_close_context`）、`:406-412`（`_create_session` 的 unclosed/容量检查）、`:582-597`（`_cleanup_expired_sessions`）、`:506-516`（`_close_session`）
- 证据：
  - `_close_resource` 的完成回调只在**成功**时把 close task 从 `_resource_close_tasks` 移除：
    `succeeded = not completed.cancelled() and completed.exception() is None; if succeeded and ...: pop`（browser_service.py:338-340）。
  - 下次任何关闭路径（用户 close、TTL 清理、shutdown）都会先命中 `task = self._resource_close_tasks.get(close)` 并**复用这个已失败的任务**，`asyncio.wait({task})` 立即返回 → `task.result()` 再次抛同一异常 → 返回 False；**不会再发起一次 `context.close()` RPC**（:326-357）。
  - `_close_context` 失败时把 context 记入 `self._unclosed_contexts[context] = label`（:370），而 `_create_session` 的容量公式把它算进配额：`len(self._sessions) + len(self._creating) + len(self._unclosed_contexts) >= self.max_sessions`（:411-412），并且 `if session_id in self._unclosed_contexts.values(): raise "has unfinished cleanup"`（:408-409）会**永久封锁该 session_id**。
  - `_cleanup_expired_sessions` 每 60s 重试（:582-597），但对"异常型失败"是空转，日志只会反复出现 `Expired session cleanup failed for ...`；只有 browser/driver 级释放（`_release_browser_ownership`，:277-290）才会清空，即**进程重启前不会回收**。
- 影响：
  1. 每次 `context.close()` 异常 ⇒ 真实 Chromium context 泄漏 + 永久占用 1/10 容量槽；累积 10 次后**所有新建会话失败**——包括 VerifyJourney 的 journey 会话（本提交新引入了容量检查，见 F2），Java 侧表现为 `PYTHON_CALL_FAILED / "Python service unreachable or timeout"`，属于"验证工具整体不可用且原因不可见"，只能重启 Python 服务恢复。
  2. 交互式链路：某 chat session 的 close 失败后，该 session_id 后续所有 WebBrowser 动作报 `RuntimeError: ... already exists or is closing`（:399-401），用户的浏览器能力被永久破坏。
  3. Java 注释声称 "TTL remains a fallback"（VerifyJourneyTool.java:383）——对 **timeout 型**失败成立（重试会重新等待同一任务，任务最终成功则能恢复），对 **异常型**失败不成立。
- 反向验证（避免误判）：不重试的决定本身**技术上正确**——playwright 1.58 `BrowserContext.close()` 先置 latch 再发 RPC：
  `if self._closing_or_closed: return; ...; self._closing_or_closed = True; await self.request.dispose(...)`（venv: playwright/_impl/_browser_context.py:584-590）。
  即"重试成功"是假成功。因此修复方向不应是"重试 close"，而是**升级回收**。
- 建议修复：
  - 为 `_unclosed_contexts` 增加失败计数/首败时间；超过阈值后走**浏览器级回收**（`browser.close()` + 重新 launch，代码里已有 `_release_browser_ownership` 的语义可复用），而不是永久保留。
  - 至少在容量公式中不把"关闭失败且已重试 N 次"的 context 永久计入，并在 `/api/health/capabilities` 上暴露 `unclosed_contexts` 计数（当前不可观测）。
  - 若保持"永不重试"，请在 `_close_resource` 注释与 Java 注释中明确 "exception-type failure is NOT recoverable by TTL"，避免运维误判。

### F2 (P2) 移除"驱逐最旧会话"后改为直接拒绝，journey 路径首次引入容量上限；失败以裸 500 + 误导信息暴露
- 位置：`browser_service.py:393-412`（`_create_session` 的 `raise RuntimeError("Browser session capacity reached")`）；`journey.py:66-70`（`_run_owned_journey` 直接 await 创建，无异常包装）
- 证据（对照父提交 e78d686）：
  - 旧 `get_or_create_session`：`if len(self._sessions) >= self.max_sessions: oldest = min(...); await self._close_session_unsafe(oldest)`——**驱逐最旧**后继续创建。
  - 旧 `_create_context_for_journey`：**完全没有容量检查**（diff 中该函数无 max_sessions 逻辑）。
  - 新实现两处都变成拒绝（:411-412）。
- 影响：
  - 交互式：达到 10 个会话时 `navigate`(新 session_id) 返回 `success=false / error_code=RuntimeError`（消息尚可读）。
  - journey：`_create_context_for_journey` 抛出的 RuntimeError 在 `journey_run` 中**无人捕获** → uvicorn `run_asgi` 的 `except BaseException → send_500_response()`（venv: uvicorn/protocols/http/h11_impl.py）→ 返回**纯文本 500**，Java `callIfAvailable` 得到 `Optional.empty()` → `JourneyResult.failed("PYTHON_CALL_FAILED","Python service unreachable or timeout")`。结论错误且不可诊断（真实原因是容量）。
  - 与 F1 叠加：容量被永久泄漏后，journey 会以不可诊断方式持续失败（升级为"服务级不可用"）。
- 建议修复：容量拒绝改为可识别错误（如 `HTTPException(503, "JOURNEY_CAPACITY_REACHED")`，或在 `journey_run` 捕获 RuntimeError→503），并保留"只驱逐 idle 且无 owner lease 的会话"作为兜底，避免把多用户/多 chat session 场景变成硬失败。

### F3 (P3) deadline 到期只回 504（无部分结果）+ Python 侧无任何日志，可观测性缺失
- 位置：`journey.py:33-57`（deadline 计算与 504/499 抛出）、`:50-57`（finally 清理）
- 证据：`raise HTTPException(status_code=499, detail="JOURNEY_CLIENT_DISCONNECTED")`（:48）；`raise HTTPException(status_code=504, detail="JOURNEY_DEADLINE_EXCEEDED")`（:49）；整个 `journey_run` 没有任何 `logger.*` 调用（该文件 logger 只在 `_execute_journey` 内用于截图/跟踪失败）。
- 影响：① Java 端 `resp.isEmpty()` → `PYTHON_CALL_FAILED`（把 deadline 超时描述成"服务不可达/超时"），已完成的 step 结果被整体丢弃；② 运维在 Python 日志中看不到任何"为何 504/499"的记录（没有 elapsed、session_id、已执行步数），排障只能靠 Java 侧。
- 说明：结果丢弃可接受（Java 无部分结果契约：`handleVerificationResult` 只在 2xx 解析 `JourneyResponse`），但**日志缺失**应修复。
- 建议修复：在 504/499 分支加 `logger.warning("Journey deadline/disconnect ... session_id=%s elapsed=%.1fs steps_done=%s")`；如未来要支持部分结果，需要 Java 侧同步改造。

### F4 (P3) 并发 close 与"创建中"竞争：创建方请求以 CancelledError 逃逸 → 500；共享 waiter 会被传染 CancelledError
- 位置：`browser_service.py:498-500`（`reservation.cancelled = True; reservation.owner.cancel()`）、`:420-427`（waiter `raise pending.error`，`pending.error` 可能是 CancelledError）、`journey.py:53-57`
- 证据：`_close_session` 对 `_creating` 中的 reservation 直接 `reservation.owner.cancel()`——owner 是**发起创建的那个 HTTP 请求任务**（普通路径为 FastAPI handler，journey 路径为 execution task）。owner 被取消后，`_create_session` 的 `except BaseException → raise`（:445-457）把 CancelledError 抛回路由；`browser.py` 各端点 `except Exception` 捕获不到 BaseException → 一路逃逸到 uvicorn（`except BaseException → 500`）。waiter 侧 `if pending.error is not None: raise pending.error` 会把 owner 的 CancelledError 当作自己的错误抛出。
- 触发场景（窄）：同一 session_id 上"创建中"与 `close_session`/`DELETE /session/{id}` 并发。WebBrowserTool `isConcurrencySafe=false`（串行）降低了概率，但 `DomSnapshotClient`（strict_session=false，可能触发创建）与 close 可能并发；Java 的 replay 快照与 WebBrowser 关闭也可能并发。
- 影响：请求得到 500 + `Exception in ASGI application` 噪音日志；客户端把 500 当作服务错误重试/降级，语义不清晰（本应返回 409/404 类"会话正在创建/已关闭"）。
- 建议修复：对非 journey 的 owner 不要 `cancel()`，改为置 `reservation.cancelled=True` 并让创建方在既有的检查点（:447 `if self._stopping or reservation.cancelled or reservation.owner.cancelling(): raise asyncio.CancelledError`）抛出一个**自定义异常**（如 `SessionClosedDuringCreation`），由路由映射成 409/410；waiter 也只抛该异常而不抛 CancelledError。

### F5 (P3) deadline 时钟语义与旧 verifier 超时预算不一致（含死代码）
- 位置：`journey.py:34-35`、`journey_models.py:16-18`（注释）、`backend/.../UserJourneyVerifier.java:19,36`
- 结论（语义本身 OK）：Python 用 `time.time()`（墙钟），Java 用 `System.currentTimeMillis()`（墙钟），**语义一致**，且有 `min(120, ...)` 上界保护（即使 Python 钟慢也不会无限延长）。
- 风险点：
  1. Python 主机时钟**快于** Java 时，预算被静默缩短（`deadline - now` 变小）→ 提前 504 → Java 报 `PYTHON_CALL_FAILED`。NTP 正常时偏差 ms 级；跨主机/容器部署需注意（注释已声明该假设，属可接受）。
  2. `UserJourneyVerifier`（@Deprecated，未被 VerifierFactory 使用）仍使用 120s HTTP 超时 + `"rv-" + req.sessionId()`：一旦被重新启用，面对"120s 工作 + ≤5s 清理"的新服务预算**必然先超时**（拿不到 504），且 session_id 复用会命中 `_create_session(journey=True)` 的 `already exists` 拒绝（:399-401）。`VerifyJourneyTool.java:24` 还保留了对它的无用 import。
- 建议：(a) 若保留旧 verifier，至少同步把超时改为 130s；(b) 删除死 import；(c) 在 router docstring 明确"墙钟 + 同主机"假设。

### F6 (P3) `_unclosed_contexts` 的 label 语义不一致（两处写法不同，导致 id 级保护只覆盖一半路径）
- 位置：`browser_service.py:476`（`_abort_creation`：`self._close_context(context, session_id)`，label=裸 session_id）、`:528`（`_close_registered_session`：label=`f"session {session_id}"`）、`:409`（`if session_id in self._unclosed_contexts.values()`）、`:496`（`unclosed = [... if sid == session_id]`）
- 证据：label 同时承担"日志名"和"按 session_id 反查"两个职责，但两处格式不同：只有 `_abort_creation` 写入的条目能被 `values()` 命中；`_close_registered_session` 写入的条目在 `_close_session` 的 `unclosed` 列表里查不到（它靠 `_sessions[session_id].closing` 兜底，功能上没坏，但机制不统一、易在后续修改中出错）。
- 建议：`_unclosed_contexts` 存原始 session_id，日志名在调用处拼接；或改为 `dict[session_id, list[context]]`。

### F7 (P3) 规范/风格：中英混用、注解与默认值、缺少返回注解
- 位置：`browser_service.py:375-521`（新代码全英文 docstring/注释，周围为中文注释）、`journey.py:15-62`（英文注释）
- 证据：仓库风格以中文注释为主（如 `browser_service.py:1-15`、`_periodic_cleanup`/`_strict_session_guard` 的中文 docstring）；本提交新增行几乎全英文。`journey_run(request: JourneyRunRequest, http_request: Request = None)` 用 `Request` 注解 + `None` 默认值（mypy 会报）；`_wait_for_disconnect` 无返回注解；`_create_session(session_id, context_kwargs, initialize, *, journey=False)` 全参数无注解（`get_or_create_session` 的既有风格也是部分注解，属一致但可改进）。
- 影响：可读性/一致性；无功能影响。
- 建议：统一注释语言；`Optional[Request] = None`；补 `-> None`。

### F8 (P3) 测试质量：真实资源收敛测试在 CI 永不执行；mock 测试依赖 Playwright 私有内部结构；若干高风险路径无覆盖
- 位置：`tests/test_browser_resource_container.py:29`（`@unittest.skipUnless(os.getenv("ZHIKUN_BROWSER_CONTAINER_TEST") == "1", "isolated Linux container only")`）、`tests/test_browser_service_lifecycle.py:12-13,405-452`
- 证据/结论：
  1. 容器测试需要 `ZHIKUN_BROWSER_CONTAINER_TEST=1` + Linux `/proc`/cgroup + 真实 Chromium；`.github/workflows/ci.yml:122-123` 只跑 `pytest tests/ -v --ignore=tests/integration -k "not api_key and not llm"`，未设置该变量 ⇒ **该用例在 CI 恒为 skip**，"无进程/无 zombie/cgroup pid 不增长"的泄漏收敛结论**没有任何 CI 门禁**（唯一覆盖真实 Playwright 的测试）。
  2. `test_browser_service_lifecycle.py` 用 mock driver（CI 可跑，无需浏览器，方向正确），但直接依赖 Playwright 私有实现：`DriverContext._closing_or_closed`、`context._request.dispose`、`context._channel.send`、`PlaywrightContextManager._exit_was_called`、`manager._connection.stop_async`（:405-452）——playwright 升级（pin=1.58.0）会静默失效；这类"验证 latch 语义"的测试本质是绑定 SDK 实现，建议加版本断言或改为对行为（而非私有字段）断言。
  3. 覆盖良好的部分：创建失败/取消后的 context 回收、capacity 原子性、共享创建、重复 id 拒绝、外部 close 取消 journey、失败快照保留与原子 strict 查找、close 超时保留待重试、shutdown 继续、driver 失败不重试、startup 取消部分启动、事件循环无遗留任务断言等——质量高于仓库平均（用 Event 驱动而非 sleep，`cleanup_timeout` 缩短，真实 `BrowserService` 而非全 mock）。
  4. 未覆盖/未在测试中体现的高风险路径：**(a)** 真实 `Request.is_disconnected` 轮询（`test_journey_lifecycle.py:133-148` 用 `SimpleNamespace(is_disconnected=...)`；容器测试直接 `journey.journey_run(request)` 不传 http_request）——FastAPI 注入路径在生产才生效（我用 venv 手工验证 200 OK，见下）；**(b)** Java 130s / Python 120s+5s 的端到端边界（mock 的 `close_session` 立即返回，没有"真实 5s 清理占用预算"的断言）；**(c)** `_periodic_cleanup` 的 60s 循环（用直接调用 `_cleanup_expired_sessions` 代替，时钟靠改 `last_activity` 模拟——可接受）；**(d)** F1 的"异常型关闭失败后永久占用容量/封锁 session_id"被测试**固化为期望行为**（`test_failed_creation_cleanup_retains_capacity_until_browser_shutdown`），但没有断言其对 journey/交互式的业务影响（没有测试"容量耗尽后 journey 路由如何响应"）。
- 建议：把容器测试接入 CI（或至少 nightly + docker），为"容量耗尽时 journey 的 HTTP 响应"补一条路由级测试，为 disconnect 轮询补一条走 ASGI/真实 Request 的测试。

### F9 (P3, 提示) 其他
- `journey.py:66` `f"rv-{uuid.uuid4().hex[:8]}"` 仅 32bit，理论冲突；Java 现在总是传唯一 id，应把该兜底分支标注为 legacy（未改动的旧代码）。
- `browser_service.py:474-478` `_abort_creation` 对 `reservation.allocation`（`new_context` RPC）**无超时**地 await：若 RPC 永不返回（driver 挂死），rollback 任务与 reservation 永久保留（设计上"retained for diagnosis"），配合 F1/F2 同样是"永久占用 1 个容量槽"。建议加告警/健康暴露。
- 以"动态 callable"作 `_resource_close_tasks` 的键（`_shutdown_resources` 的 `close_cleanup = lambda: self._cleanup_task`，:246-249；`_close_session` 的 `reservation.done.wait`，:515）。bound method 的 `__eq__`/`__hash__` 基于 (`__self__`,`__func__`) 所以语义正确，但 lambda 每次新建对象、可读性差，建议显式维护 key（如 context/browser 对象自身或 `id()`）。
- `shutdown()` 后若 driver `stop()` 失败（latch 已置位，重试无意义），`_playwright`/`_resource_close_tasks` 保留 → 同进程内后续 `startup()` 永远抛 `RuntimeError("... unreleased resources")`（:188-192）。设计上可辩护（重试是假成功），但意味着**该 Python 进程必须重启才能恢复浏览器能力**；建议在 health/capabilities 上暴露该状态（当前只有日志）。
- 本提交未同步任何 docs/（仓库有 docs/ 与 zhikun 文档目录）；如存在 Python 服务 API 契约文档，建议补 `deadline_epoch_ms`、504/499 语义与 close 语义（按仓库惯例，README/zhikun 文档常记录接口契约；**未逐一确认是否存在必须更新的页面，标注为未验证**）。

---

## 二、已验证无问题清单（对抗性验证后的结论）

1. **Java close_session 的 `success` 契约（重点核实项）——无问题**：Python 端 envelope 顶层即 `success`：`BrowserResponse(success=True, data={"closed": closed})`（`routers/browser.py:254-261`；模型 `services/browser_models.py` 的 `BrowserResponse(success, data, error_code, error_message)`）；Java `PythonCapabilityAwareClient.callIfAvailable(..., Map.class, 5s)` 反序列化成顶层 Map 后读 `closed.get().get("success")`（`VerifyJourneyTool.java:375-384`），字段层级一致，**不会出现"永远视为未确认"**。清理失败时 Python 抛 RuntimeError → 路由 `except Exception` → `success=false`（`browser.py:258-261`），Java 记录 `Browser cleanup unconfirmed; TTL remains a fallback` —— 契约闭环成立（TTL 例外见 F1）。
2. **deadline 墙钟语义**与 Java `System.currentTimeMillis()+120000` 一致；`deadline` 已过期时**不创建任何 context 直接 504**（`journey.py:36-37`；测试 `test_expired_request_never_creates_a_context` 断言 `_create_context_for_journey.assert_not_awaited()`）。
3. **TTL 兜底真实存在**：journey session 在 `release_session` 后 `owner_task=None` → `is_expired` 生效（`browser_service.py:115-116`）→ `_periodic_cleanup` 每 60s 清理（:571-597）；`is_expired` 的 `owner_task is None` 保护使"运行中的 journey 不会被 TTL 淘汰"（测试 `test_expired_journey_is_protected_until_lease_release`）。Java 侧 "rv-<uuid> 独立资源 + TTL 兜底" 的假设在 Python 侧成立（F1 的异常型关闭失败除外）。
4. **失败快照（RV-5）链路无回归**：步骤失败属于"正常结果"，`_execute_journey` 正常返回 ⇒ 不进入 `except` ⇒ context 保留；`release_session` 只解除 lease 不销毁（:542-550）；`snapshot_semantic(strict_session=True)` 改为**锁内原子查找**（:990-1000）→ 不会重建资源，返回 SESSION_NOT_FOUND 时 Java 静默降级（`enrichWithFailureSnapshot` 读顶层 `data`，层级一致）。外部 close 会先取消 journey（:504-508），避免"关闭后 navigate 又重建"。
5. **取消安全（主错误保留）**：`_run_owned_journey` 的 `except BaseException → try close_session → except Exception: logger.exception(...) → raise` 保留原错误（`journey.py:74-85`）；`_finish_cleanup` 在清理期间吞掉一次 CancelledError、清理完成后重抛（:306-321）；`_close_resource` 不 cancel driver 调用、不把"取消"误报为成功（:344-357）。测试 `test_cleanup_failure_does_not_replace_cancellation`、`test_cancelled_shutdown_still_releases_driver` 覆盖。
6. **正常成功路径无行为变化**：`_execute_journey`/`_execute_step` 未改动（本提交对 journey.py 的 diff 只到函数头）；record(video/har/trace)、viewport、步骤语义、逐步截图、artifacts 全部原样；新增开销仅为 1 个 0.25s 轮询协程 + `asyncio.wait`（无额外 HTTP 交互、无额外锁）。
7. **交互式共享 helper 的等价性**：`get_or_create_session` 单调用方路径行为等价（touch + 返回；undo 掉"重复创建再关闭"的旧 double-check，因为预留已原子化）；全部 16 个 `/api/browser/*` 端点签名与响应 envelope 未变；`snapshot_semantic` 非 strict 路径未变；`_strict_session_guard` 语义等价（仅多了 `closing` 判定）。`page.set_default_timeout`、webdriver init script、JS 错误收集（`page.on` 闭包捕获 session_id、`_js_errors.get(..., [])` 兜底）均与旧实现等价。
8. **锁与并发**：Playwright I/O 全部在锁外；`_close_session` 先取锁决策再在锁外 await close_task（旧实现在锁内 await `context.close()`，新实现更优）；journey 自关闭走 `expected_session` 分支不会自我 cancel（`requester` 即 journey task）；同一 close_task 被多个等待者 `shield` 共享，无双重关闭。两个不同 id 的并发 journey 互不阻塞。
9. **进程/可重启性**：`startup()` 对"未释放资源"显式拒绝（:188-192），`_release_browser_ownership` 仅在确认的 browser/driver 关闭后清空（:277-290），并有重启/部分启动回滚测试（`test_normal_lifecycle_can_restart_after_confirmed_shutdown`、`test_cancelled_start_retains_one_cleanup_until_driver_is_obtainable`）。
10. **版本/兼容性**：`Task.cancelling()` 需 3.11+，仓库 `requires-python=">=3.11,<3.13"`、CI 3.11 ⇒ 无兼容问题；`asyncio.wait` 只接收 Task（3.12 移除 coroutine 支持）；无 `_close_session_unsafe` 残留引用（grep 全仓）。
11. **`http_request: Request = None` 注入有效（实测）**：用 venv 起内存 FastAPI + httpx ASGITransport 验证 `async def probe(request: Body, http_request: Request = None)` → `200 {'injected': True, 'req_type': 'Request'}` ⇒ 生产注入成立，测试里传 None 也不会崩。
12. **disconnect 检测机制（静态）**：starlette 0.41.3 `is_disconnected()` 是非阻塞 receive（anyio 立即 cancel 的 CancelScope）；uvicorn 0.32.1 `connection_lost` 会 `cycle.disconnected=True` 且 `message_event.set()`（h11_impl.py:109-122），此时 `receive()` 无挂起点直接返回 `http.disconnect` ⇒ 0.25s 轮询能在客户端断开后 ≤0.25s 观测到。HTTP/1.1 客户端半关闭（FIN）只触发 `eof_received()`（h11_impl:126-127，空实现）⇒ 不会误判为断开。
13. **响应 schema 向后兼容**：`JourneyRunResponse{passed, step_results, session_id, final_url, artifacts}` 未变，与 Java `JourneyResponse`（`@JsonProperty` snake_case）一致；`journey_models.py` 仅新增可选请求字段 `deadline_epoch_ms`，旧调用方不传也合法（`remaining` 上限 120s）。

---

## 三、无法静态确认、需动态验证清单

1. **F1 的真实触发概率**：生产环境中 `context.close()`/`request.dispose()` 异常频率（决定"永久容量泄漏"是理论风险还是现实风险）。建议：注入式故障演练 + 观测 `_unclosed_contexts` 长度。**未验证/推测**。
2. **130s vs 125s 边界**：真实负载（多步 journey、网络慢、Java HttpClient 复用 keep-alive）下 Python 是否总能在 Java 130s 前上报；`asyncio.gather` 无超时的 finally 是否可能被"不可取消的 Playwright RPC"拖长（静态分析认为 RPC 可取消，**未动态验证**）。
3. **uvicorn read-flow 细节**：轮询被 anyio CancelScope 取消时 `pause_reading()` 被跳过（uvicorn h11_impl `receive()` 内 `resume_reading()` 后 await、cancel 会跳过 `pause_reading()`），长期是否造成读流一直 resume/缓冲（推测影响极小）。**推测/未验证**。
4. **disconnect 在生产客户端的实测**：Java `java.net.http.HttpClient` keep-alive 复用连接下的断连行为（含代理/反向代理）；以及 499 响应在"已断开"连接上的写入是否产生噪声日志。**未验证**。
5. **容量耗尽的现实概率**：真实部署中 WebBrowserTool（默认 `session_id=chatSessionId`）并发会话数是否可能达到 `BROWSER_MAX_SESSIONS=10`；需要运行期统计。**未验证/推测**。
6. **F4 的触发频率**：`DomSnapshotClient`（strict_session=false，可能创建）与 close/DELETE 并发的实际概率。**未验证**。
7. **CancelledError 逃逸 → uvicorn 500**：读源码确认（`except BaseException → send_500_response`），但没有测试覆盖，也未在真实 ASGI 栈上观测。**需动态验证**。
8. **容器级泄漏测试**：`test_browser_resource_container.py` 需在 Linux+Docker+真实 Chromium 环境手动跑（本机 macOS 无法执行；CI 未接入）。**未运行**。

---

## 四、附：逐条清单结论速览（对应任务 A–I）

- **A 资源容器/生命周期**：键控 = `_sessions`(session→BrowserSession) + `_creating`(session→reservation) + `_unclosed_contexts`(context→label) + `_resource_close_tasks`(close-callable→task)；TTL/淘汰见"已验证 3"+F1；进程中途异常有 `_abort_creation`/`startup` 回滚（覆盖良好）；容器淘汰与运行中 journey 的竞态**已消除**（owner lease 保护，A 项无问题）；close_session 幂等（重复 close 复用同一 close_task；已关闭返回 True；不存在返回 False+success=true）；清理失败不掩盖主错误（已验证 5）；**风险集中在 F1/F2/F4**。
- **B deadline**：墙钟、与 Java 一致；到期返回 504 且**不返回部分结果**、清理在约 5s 预算内（`cleanup_timeout=5.0`）；无无界阻塞（gather 理论上无超时，见"需动态验证 2"）；deadline 已过期入口直接 504 不建资源；步骤级 timeout 与 journey deadline 无耦合（步骤 timeout 只影响单步，整体由 route 的 `asyncio.wait` 兜底）——见 F3/F5。
- **C 取消安全**：try/finally + `_finish_cleanup` 屏蔽 + 有界 `_close_resource`，主错误保留（已验证 5）；`shield` 使用恰当（保护 close task / 创建 RPC，未滥用）；`gather(return_exceptions=True)` 只用于 join；清理期间二次取消会中断清理但任务仍会被 join（可接受）。
- **D 交互式回归**：共享 helper 逐一比对见"已验证 7"；`strict_session` 语义未破坏自动创建/回退（strict=True 从不创建；strict=False 仍自动创建）；**行为变化只有 F2（不再驱逐，改为拒绝）与 F4/关闭态语义**。
- **E 并发/正确性**：单线程事件循环 + 两个锁（`_lock` 状态、`_lifecycle_lock` 启停）配合正确，无锁内 I/O；同一 session 并发 journey 被显式拒绝（F2 的拒绝语义）；Playwright 关闭顺序 context→browser→driver，且有 `_close_context` 的 ancestor-release 判定防 use-after-close；无忙等（0.25s/60s 两个定时器）；内存增长见 F1/F9；异常→HTTP 映射变化 = 新增 504/499（JSON）与裸 500（RuntimeError，F2）；响应 schema 兼容（已验证 13）。
- **F 成功路径**：见"已验证 6/7"，无回归。
- **G 测试**：见 F8。
- **H 规范**：见 F7/F9（docs 未同步，未确认是否有强制页面）。
- **I 交叉一致性**：`rv-` 前缀仅用于生成（`journey.py:66`）与 Java 资源 id，**Python 无按前缀分流逻辑**；grep 其他模块（http_api/ASR/前端）无浏览器会话依赖；`DomSnapshotClient`(strict=false) 与 WebBrowserTool 走同一 `browser_service`，行为见 D；"rv-<uuid> + TTL 兜底"在 Python 侧真实存在（已验证 3，例外见 F1）。
