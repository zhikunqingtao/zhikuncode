# Python Browser/Journey 独立事实底稿

目标提交：`0db4b0498f6cf886f862fe293883256b1c52e049`。父提交：`e78d6867f6fcd9172500b4c2cc85d4c4d927ec34`。未阅读参赛报告；仅据源码、父提交、仓库测试和独立探针裁决。本报告路径均相对于仓库根目录，证据文件相对于本目录。仓库执行前后 `git status --porcelain` 均为空。

## 方法与证据边界

- HEAD 和父提交的 `python-service` 均以 `git archive` 导出至本目录 `source/`、`parent/`；源仓库没有写入。
- Python 3.11.15；Playwright 1.58.0；FastAPI 0.115.6；Starlette 0.41.3；AnyIO 4.13.0；pytest 8.3.4；pytest-asyncio 0.24.0。关键依赖与 `requirements.lock` 一致。
- 隔离副本执行 `test_browser_service_lifecycle.py test_journey_lifecycle.py test_journey_publication.py`：**65 passed in 2.18s**。见 `focused.log`、`focused.junit.xml`。未运行全量测试、真实浏览器或容器。
- 独立探针仅替换浏览器 I/O；真实 FastAPI/Starlette/AnyIO 栈保留。`probe_loopback.py` 进一步运行真实 Uvicorn loopback HTTP，lifespan 关闭，不启动浏览器，不访问外网。
- `probe_contracts.py` 默认模式为单独观察 HTTP 错误映射，明确把断连 watcher 换成可正常取消的被动 Future，避免下面 GT-PY-01 遮蔽结果；它的 `source external_actual` 模式保留原 watcher。两个核心 watcher 探针均未替换 watcher 或 Request。
- 所有探针用 `PYTHONDONTWRITEBYTECODE=1`；pytest 关闭 cacheprovider。探针人工缩短 deadline/cleanup timeout 只节省等待，不修改业务分支。

## GT-PY-01：新 watcher 在立即失败请求上吞取消，finally 的无界 join 挂起（真实新增缺陷，建议 P2）

**精确位置**：`python-service/src/routers/journey.py:55-62`；触发路径是 `browser_service.py:393-412` 的容量满、或其他在首个 driver await 前立即拒绝的创建请求。核心问题在第 56 行只 cancel 一次、第 57 行无界 `gather`，watcher 第 61 行调用真实 `Request.is_disconnected()`。

**确定性触发**：`max_sessions=1`，先创建 `occupied`，再经 HTTP 发起另一个 Journey。当前实现立即产生 capacity RuntimeError；与此同时 watcher 处于 Starlette 的 `is_disconnected()`。该方法用预先取消的 AnyIO CancelScope 做非阻塞 receive；route 的一次外部取消在其内部被吸收。watcher 此后 `cancelling()==1`，仍继续执行第 62 行 sleep/轮询，finally 无界等待，之前的工作 deadline 不再生效。

**实测**：`probe_watcher_cancel.py source` 在裸 FastAPI 与生产 `main.app` 中都观察到：工作预算 50ms，150ms 后请求仍未结束，watcher `done=false, cancelling=1, line=62`。定向第二次取消该 watcher 才立即排空请求并返回 500。正常未满容量的对照请求均立即 200，**不能外推“所有成功 Journey 都会挂”**。最初探索脚本在“先成功、再容量拒绝”的第二次请求上挂起，底稿已按最小实验收窄，不把第一条成功请求当反例。

**真实 HTTP 排除传输假象**：`probe_loopback.py source` 经 Uvicorn TCP loopback 同样 150ms 未完成、watcher cancelling=1；第二取消后500。父提交同一容量情景200，因为父 Journey 不执行容量上限检查，也无 watcher。见 `probe_loopback_head.log`、`probe_loopback_parent.log`。

**影响/边界**：容量拒绝应尽快返回，但现在会占据 ASGI 请求任务，Java最多等待其130秒HTTP超时；由于 GT-PY-02，实际断连也不能保证排空这个 watcher。重复满容量请求可累积挂起请求。未证明真实浏览器普通成功路径普遍触发，故不建议写成全局 P1 必挂。该问题与容量500错误分类是相关但不同层：一个使拒绝迟迟不能返回，一个使可返回的拒绝丢失可诊断原因。

**证据**：`probe_watcher_cancel.py`、`probe_watcher_cancel_head.log`、`probe_watcher_cancel_parent.log`、`probe_loopback.py`、两个 loopback 日志。探索用 `probe_hang_debug.py/.log` 保留了原 task stack，但正式结论以有界 A/B 为准。

## GT-PY-02：生产中间件使新增断连轮询失效（真实集成缺口，建议 P2；父提交已无断连检测）

**精确位置**：`journey.py:40-48,60-62`，与未改动的 `main.py:104-141` 的 HTTP middleware 组合。该装饰器产生 Starlette BaseHTTPMiddleware；其 downstream receive 在 AnyIO task group 内部返回消息。`Request.is_disconnected()` 预取消作用域会打断这个返回过程，导致底层已经取得 `http.disconnect`，调用者仍得到 false。

**确定性探针**：`probe_disconnect.py` 使用完全相同的 ASGI scope/receive，浏览器截图阻塞，收到截图开始后返回真实 `http.disconnect`。裸 FastAPI：约0.011s返回499并close一次。实际 `main.app`：底层同样已收到disconnect一次，但直到约0.405s的人工deadline才504并close一次。见 `probe_disconnect.log`。

**影响/边界**：新增的250ms断连取消不能在生产中间件栈兑现；客户端提前离开后浏览器步骤/副作用仍可能继续至完成或120秒工作预算。仍有 deadline 兜底，不能称为所有断连都永久浏览器泄漏。父提交没有断连 watcher，因此这是新增承诺的实现缺口/未修复遗留情形，不能冒称父提交原本断连会立即停止、此提交才破坏。

## GT-PY-03：新增拒绝与外部取消未映射成有效 Journey HTTP 错误（真实错误契约/可观测性缺口，建议 P2/P3，避免与 GT-PY-01 重复计分）

### 容量/重复ID

**位置**：`browser_service.py:397-412` 抛 RuntimeError；`journey.py:45-46,69-71` 没有把它转换为受控HTTP错误；Java `BrowserVerifier.java:55-56` 对 Optional.empty 统一返回 `PYTHON_CALL_FAILED / Python service unreachable or timeout`。`PythonCapabilityAwareClient.java:275-288` 丢弃非2xx状态/错误体（500也不重试 Journey）。

HEAD `max_sessions=1` 第二Journey拒绝且只分配一个context；父提交会分配两个，均200。父 ordinary session容量满会驱逐最老session，HEAD保留旧session并拒绝新session。**限制Journey并停止驱逐是明确有意的容量保护，不能单独判成资源生命周期错误**。真实缺口是这些新增预期拒绝得到裸500及Java“不通/超时”分类，不能告诉调用方容量忙或ID冲突，也无重试提示。GT-PY-01生效时它甚至先挂起；`probe_contracts.py` 用被动watcher隔离错误映射，确证500。

### 外部 close 正在创建的 Journey

**位置**：`browser_service.py:495-497` 正当地取消创建owner；`journey.py:45-46` 向HTTP handler传播子task的 CancelledError；生产 BaseHTTPMiddleware 将没有响应的子app表现为 RuntimeError/500。

`probe_contracts.py source external_actual` 保留原 watcher和生产中间件：阻塞 `new_page` 时经 `/close_session` 请求取消。close端返回200/`closed=true`，原Journey返回500，context.close恰好一次，`_sessions/_creating`均清空。见 `probe_external_actual.log`。父版本close返回`closed=false`，原创建仍在运行；探针0.5秒后自行取消，context.close为0。**HEAD的停止/回收是修复，不是新的泄漏；原请求HTTP500是错误分类缺口。** 不应要求保留本来应该响应外部close的创建工作。

## GT-PY-04：failed close 的保留和恢复：安全取舍成立，但TTL/自动恢复表述要受限

**位置**：`browser_service.py:324-357`，尤其335-340只删除成功task；`527-540` 在失败时保留session；`582-594` TTL尝试相同缓存task。`VerifyJourneyTool.java:382-383` 日志称“TTL remains a fallback”。

**事实**：已完成且异常失败的context.close缓存task不会被重新发起；无论反复close还是TTL，都会复查相同失败并保留session/容量，不能再用同ID。这不是意外忘pop：源码注释和仓库测试明确防止 Playwright 1.58.0 的 `_closing_or_closed` latch在第一次失败后让第二次close变为虚假成功/no-op。65个通过测试包含直接运行安装SDK `DriverContext.close` 的 latch测试（非只Mock该语义），以及driver manager `__aexit__` latch测试。父提交pop之后吞异常并返回true，实际上可能遗失未释放context的所有权。

**独立验证**：连续close两次+一次TTL，底层close仅一次，session/cached task各一个；接着正常 `service.shutdown()`，即使context.close失败，只要browser.close或driver.stop确认成功，会在 `293-304` 清除子资源状态；再 `startup()` 可用同ID成功创建新session。见 `probe_contracts_head.log` 的 `failed_close_ttl_recovery`。仓库测试还覆盖 browser.close失败但driver.stop成功的恢复。

**正确裁决**：

- “TTL会重新调用失败close，最后总能回收”错误；已经exception的失败task不会恢复，仅pending且以后真正成功的task有晚成功/下一次清理路径。
- “失败后只能重启整个Python进程”过度；代码支持确认祖先释放后的BrowserService shutdown/startup。没有暴露HTTP reset端点、没有自动对失败context重建整个browser，因此运维可能需要生命周期层干预。
- “留着failed task就是资源泄漏/缓存忘删bug，应直接pop并重试”忽略真实SDK latch，会重新引入错误确认释放。应把合理fail-closed策略与缺乏自动恢复/告警闭环分别表述。
- 一次context失败会永久占一个容量槽直到确认祖先释放；反复失败可耗尽容量，这是**有故障前提的服务可用性/恢复缺口**，不是正常路径必然发生。
- 如果browser.close和driver.stop也失败或永远不结束，启动守卫继续拒绝是防止遗失所有权；不应把“shutdown返回/日志finished”等同所有OS资源确实退出。未运行真实driver恶劣故障，不能证明其所有祖先释放场景。

## 已核实的正向生命周期行为与边界

- 创建在锁内预留，Playwright I/O锁外执行；普通同ID调用共享创建；Journey重复ID拒绝不覆盖旧owner。创建取消/new_page/script/trace失败会关闭已经取得的context。
- new_context RPC shield，使原调用取消后仍有唯一reservation/rollback接手迟到context；创建未收尾占容量。不能把“HTTP已返回但rollback仍pending”直接判遗失资源，此时所有权仍可追踪。
- close幂等协作/expected_session身份检查、close途中同ID不可重建、external close取消Journey lease；防止后续navigate重建刚关闭的resource。
- Journey活跃lease免TTL，release时touch；普通步骤成功和业务失败都保留上下文给Java取失败快照，然后Java finally显式close。不是正常结果忘清理；独立Python客户端不显式close则走idle TTL。
- startup失败/取消会rollback driver；manager.start迟到仍保留唯一cleanup。shutdown即使context/browser失败也继续尝试driver，单个driver close每次等待受cleanup_timeout限制。**整体shutdown是逐个资源累计预算，不是无论session数量都5秒。** 正常确认关闭后可重新startup。
- Java每次VerifyJourney生成UUID browserResourceId，与business session分离；Journey/失败snapshot/final close复用同ID，snapshot设strict_session=true，避免旧`rv-sessionId`并发串扰。4参JourneyRequest构造器也产生UUID。
- Java120秒绝对deadline、Python `min(120, deadline-now)`、Java130秒HTTP预算的数值契约合理；时钟严重不一致是有部署前提的条件风险，不能默认同宿主部署存在它。GT-PY-01说明**实际route join**仍会使预算承诺失效，65测试均直接调用route或fake Request，未覆盖这一真实Request细节。

## 条件性低严重度观察，不升级为无条件主路径缺陷

`journey.py:47-48` 只检查watcher task已经done，不读取其result/exception。`probe_contracts.py` 注入 `Request.is_disconnected` 抛 RuntimeError，确实得到HTTP499 `JOURNEY_CLIENT_DISCONNECTED`，清理仍完成。说明receive管线异常会误分类断连；尚未证明正常请求中该异常自然发生，应列为条件性P3分类问题。不能由此断言正常请求必然被误杀。

录制临时目录/trace文件没有统一删除、部分HAR/video产物暴露不完整等在父提交已存在，本次未作为新增缺陷。底稿没有把Mock测试通过泛化为真实Chromium/容器OS资源零残留。

## 复跑命令

使用仓库现有解释器，不需安装依赖：

```text
PYTHONDONTWRITEBYTECODE=1 /Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/.venv/bin/python <本目录>/probe_watcher_cancel.py source
PYTHONDONTWRITEBYTECODE=1 /Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/.venv/bin/python <本目录>/probe_watcher_cancel.py parent
PYTHONDONTWRITEBYTECODE=1 /Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/.venv/bin/python <本目录>/probe_loopback.py source
PYTHONDONTWRITEBYTECODE=1 /Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/.venv/bin/python <本目录>/probe_disconnect.py
PYTHONDONTWRITEBYTECODE=1 /Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/.venv/bin/python <本目录>/probe_contracts.py source
PYTHONDONTWRITEBYTECODE=1 /Users/guoqingtao/Desktop/dev/code/zhikuncode/python-service/.venv/bin/python <本目录>/probe_contracts.py source external_actual
```

实际目录名含中文，命令使用绝对路径时须shell引用。所有服务/探针任务均已排空；没有留下真实浏览器或本地监听服务。
