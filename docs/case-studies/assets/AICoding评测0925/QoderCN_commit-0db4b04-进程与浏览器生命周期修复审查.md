# Commit 审查报告：0db4b0498f6cf886f862fe293883256b1c52e049

> **审查对象**：`fix: close process and browser lifecycle leaks`
> **审查方式**：独立重新审查（不默认提交正确），代码走查 + 双向调用链追踪 + 两侧测试套件实跑验证
> **审查人**：QoderCN
> **日期**：2026-09-25
> **仓库**：zhikuncode（本地 `/Users/guoqingtao/Desktop/dev/code/zhikuncode`，与线上 commit 一致）
> **改动规模**：24 个文件，+2870 / -400

---

## 一、总体结论

| 维度 | 结论 |
|---|---|
| 对正常功能链路的确定性破坏 | **未发现**（两侧测试套件全部通过，见 §五） |
| 行为语义变化（需业务确认） | **4 处**（BashTool 清理失败误报、容量槽位保留、session 满员报错、API 模式失败快照移除） |
| 部署环境前置依赖 | **新增 2 项硬前提**（Linux 需 setsid+bash；Java/Python 时钟需同步） |
| 代码质量 | 高（注释质量、并发控制、错误语义一致性好） |
| 测试覆盖 | 显著增强（Java +~700 行、Python +~1300 行），但有 1 例时序 flake |

**一句话评价**：这是一次质量很高的生命周期泄漏修复——"保留所有权直到确认清理"的核心思想贯彻一致、异常路径（中断保留、主失败优先）处理规范。主要风险不在正确性，而在**语义收紧带来的行为变化**和**保留策略的耗尽风险**。

---

## 二、变更内容概览

### Java 侧（进程生命周期）

| 文件 | 变更 |
|---|---|
| `tool/process/OwnedProcess.java`（新增，257 行） | 进程所有权封装：Linux 上经 `setsid` 建立独立 session，用 `/proc` 扫描按组/会话归属后代，防止 PID 复用误杀；梯度终止（SIGTERM→grace→SIGKILL）；非 Linux 平台降级为 ProcessHandle 快照 |
| `tool/process/ManagedProcessRunner.java`（重写核心） | 启动前先预留所有权（防重复请求）；前台命令退出后**无条件**清理进程树；清理未确认时保留 active 条目 + capacity 槽位 + Run 租约，供后续 cancel 重试；清理钩子改为单飞 + 失败可重试的 CAS 语义 |
| `service/PythonProcessManager.java` | 停止/重启仅在终止确认后释放所有权；未确认 → FAILED 且阻止 restart |
| `tool/bash/ProcessTreeManager.java` | 精简为 `OwnedProcess.terminateTree` 委托 |
| `tool/impl/BashTool.java` | 新增 `PROCESS_TERMINATION_UNCONFIRMED` 失败分支（exit 0 也可能报失败） |
| `verify/DevServerLauncher.java` | dev server / npm install 均走 OwnedProcess；清理未确认时保留 handle 供重试；npm install 成功但清理失败 → 整体报失败（suppressed 保留主失败） |
| `tool/verify/VerifyJourneyTool.java` | browser 资源 ID 从 `rv-<sessionId>` 改为每次调用独立的 `rv-<uuid>`；失败快照带 `strict_session=true`；API 模式不再做失败快照 |
| `verify/BrowserVerifier.java` / `JourneyRequest.java` | HTTP 超时 120s→130s（120s 工作预算 + 清理/传输余量）；下发绝对 `deadline_epoch_ms` |

### Python 侧（浏览器资源生命周期）

| 文件 | 变更 |
|---|---|
| `services/browser_service.py`（+~380/-~150） | 会话创建改为原子容量预留 + 单飞创建 + 可取消回滚；close 失败保留资源供重试（防 Playwright close 闩锁假成功）；启动失败回滚；shutdown 全量有界清理；TTL 保护 Journey 租约 |
| `routers/journey.py` | journey 执行包裹为可取消 task，支持 120s 硬期限、客户端断连检测（499）、超时（504）；取消后 join 有界清理；正常步骤失败保留上下文供 Java 失败快照 |
| `services/journey_models.py` | `deadline_epoch_ms` 可选字段 |

---

## 三、对正常功能链路的影响评估

### 3.1 已验证不受影响的链路（走查 + 测试佐证）

1. **BashTool 前台命令**：`ManagedProcessRunner.run()` → `OwnedProcess.start()` → waitFor → 清理。macOS（开发机）走非 Linux 分支，`ProcessHandle.descendants()` 可用；命令本身执行、输出截断、超时分类逻辑不变。相关测试 7+17 项全过。
2. **BashTool 后台命令（is_background）**：`startBackground` 路径未被改动，dev server 存活语义保留。
3. **Python 服务托管**：`PythonProcessManager.start/stop/restart` 语义兼容；健康检查、重启重试上限不变。12 项测试全过。
4. **DevServer 启动/静态预览**：`startProcess` 仅换启动封装；日志重定向（`redirectOutput(appendTo)`）、PID 文件、HTTP 就绪轮询不变。6 项测试全过。
5. **VerifyJourney 浏览器模式全链路**：Java `rv-<uuid>` → journey/run（120s 期限）→ 失败时 Java 快照（strict，不重建资源）→ finally close。时序正确：快照发生在 close 之前（`handleVerificationResult` 在 try 内，close 在 finally）。21 项 edge-case 测试全过。
6. **浏览器 ad-hoc 会话（navigate/click/screenshot/get_js_errors）**：`get_or_create_session` 语义保留（存在即 touch）；JS 错误收集用 `setdefault` 惰性初始化，无回归（已核实 `_collect_js_error`/`_collect_console_error` 实现）。
7. **close_session 端点响应契约**：路由返回 `{success, data:{closed}}`，Java 检查 `success` 字段——契约匹配。
8. **生产容器兼容性**：`Dockerfile` 运行时镜像为 `eclipse-temurin:21-jre-noble`（Ubuntu 24.04），`/usr/bin/setsid` 与 `/bin/bash` 均为 essential 包，依赖满足。

### 3.2 行为语义变化（P1，需业务确认）

| # | 变化 | 影响 | 建议 |
|---|---|---|---|
| S1 | **BashTool：exit 0 但清理未确认 → 整条命令报 `PROCESS_TERMINATION_UNCONFIRMED` 失败**（`BashTool.java:408`）。旧逻辑下 exit 0 即成功（遗留子进程是泄漏），新逻辑下残留子进程被强杀，杀不掉则命令失败，且**跳过 shell 状态快照更新**（cd/env 变更丢失），`Retryability.NEVER` | LLM 会把"命令其实成功、只是清理未确认"误判为失败并放弃/重试；macOS 受限环境（ProcessHandle 不可用）下会**全部命令**误报失败 | 确认语义是否可接受；失败消息已包含完整输出（现状可接受），建议加一句"命令已退出，仅后台子进程清理未确认"以减少误判 |
| S2 | **清理未确认 → 永久保留 capacity 槽位 + Run 租约**（`ManagedProcessRunner`：16 个槽位）。只有 cancel/shutdown 重试成功才释放，无超时兜底、无指标 | D-state 进程或终止确认持续失败时，16 个槽位耗尽后**所有前台命令**报 `PROCESS_CAPACITY_EXCEEDED`（软 DoS）；Python 侧 `_unclosed_contexts` 同理占容量 | 增加保留条目的指标/告警与上限；考虑"保留但可降级"策略 |
| S3 | **Python 会话满员（max_sessions=10）从"驱逐最旧会话"改为直接报错** `Browser session capacity reached`（`browser_service.py:411`），且 `_unclosed_contexts` 也计入容量 | 多会话并发场景从"能工作（牺牲最旧）"变为"直接失败"；清理失败长期占用容量直到浏览器重启 | 确认有意的安全取舍（避免误杀他人活跃会话）；建议错误信息带指引（等待/重试/管理员清理） |
| S4 | **VerifyJourney API 模式失败不再附加语义快照**（`browserResourceId==null` 分支）。旧代码会因 snapshot-semantic 的非 strict 路径**创建幽灵会话且永不关闭**（泄漏 bug），本提交顺带修复；但 API 模式失败消息失去 "Page Snapshot at Failure" 段落 | 用户可见的失败消息内容变化（偏正向，幽灵会话本身无页面内容，快照价值本就有限） | 确认可接受；可在 commit message/PR 中说明 |

### 3.3 部署与环境前提（P1/P2）

1. **Linux 硬依赖 setsid + /bin/bash**（`OwnedProcess.java:38-44`）：缺失时**所有进程启动失败**（`PROCESS_GROUP_UNAVAILABLE`）。当前 Ubuntu 镜像满足，但未来若换 alpine/distroless 镜像会全链路崩坏；`/proc` 扫描还要求宿主 `/proc` 可读。建议在 README/部署文档中写明。
2. **时钟同步前提**（`BrowserVerifier.deadline_epoch_ms` vs Python `time.time()`）：Java/Python 分机部署且 Python 时钟超前时，journey 会被提前 504。当前单容器/本机部署无风险，但注释假设"共享主机时钟"是隐性部署约束。
3. **Windows 行为不一致**：`PowerShellTool` 未加 terminationConfirmed 失败分支（与 BashTool 不一致）；Windows 上走非 Linux 分支（ProcessHandle 级终止，无 session 隔离）。

---

## 四、代码质量评估

### 4.1 做得好的地方

- **所有权语义贯彻一致**："清理未确认 = 保留所有权 + 阻止复用"在 Java（`processRef`/`active`/`activeHandles`/`pendingCleanup`）与 Python（`_sessions`/`_creating`/`_unclosed_contexts`/`_resource_close_tasks`）两侧对称实现，是本次修复的设计精髓。
- **注释质量高**：解释了非显然的约束（PID 复用防护、Playwright close 闩锁"假成功"、late allocation 回滚、root-first 终止为何让 uvicorn 先关浏览器）。
- **异常语义正确**：中断标志保留（`Thread.interrupted()` 后恢复）、主失败优先（suppressed 附加清理失败）、CancelledError 不被清理失败吞掉（`_finish_cleanup`）。
- **并发控制扎实**：单飞创建（reservation）、共享 close task（防重复关闭/防取消已闩锁的关闭）、CAS 重试、`AtomicReference` 快照。
- **进程身份防护是真实的安全修复**：`rememberDescendants` 双重 isAlive 校验 + `/proc` startTicks 比对，防 PID 复用误杀他人进程——这是旧代码 `ProcessHandle.descendants()` 的固有缺陷，修复思路正确。
- **日志规范**：`SafeLogValue` 全程使用，无命令内容泄露。

### 4.2 问题与改进建议

| # | 级别 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| Q1 | P1 | `ManagedProcessRunner` | 容量槽位保留无界（同 S2）：`_resource_close_tasks`/`pendingCleanup` 在持续失败下无限增长，无指标可观测 | 增加 Micrometer 指标 + 保留条目上限/告警 |
| Q2 | P2 | `browser_service.py:473` | `pending.result_ready.wait()` **无超时**：new_context RPC 僵死时，同 ID 后续调用者无限阻塞（HTTP 请求挂起） | 加超时或依赖 Playwright 操作超时 |
| Q3 | P2 | `browser_service.py` | 依赖 Playwright **私有 API**：`PlaywrightContextManager.__aexit__`、`_exit_was_called`、`context._impl_obj.request.dispose` | requirements 已 pin 版本（可接受），升级 Playwright 时需回归验证 |
| Q4 | P2 | `OwnedProcess.java:186-193` | 手写 `/proc/<pid>/stat` 字段偏移解析（`fields[19]`=starttime，经核实正确），依赖内核格式稳定 | 可接受，注释充分；建议补一个字段数 < 20 的防御（解析异常会被上层按"检查不可用"降级处理，已兜底） |
| Q5 | P2 | `DevServerLauncher.runSync` | npm install **成功（exit 0）但清理未确认 → 整体抛 IllegalStateException**，验证流程失败 | 语义保守（可能误伤），建议确认；至少错误信息已明确 |
| Q6 | P3 | `journey.py:57` | `await asyncio.gather(*watched)` 对 set 解包，顺序不确定 | 无实际影响，可改 list 提升可读性 |
| Q7 | P3 | `close_session` 端点 | 对不存在 session 返回 `success=True, data:{closed:false}`，Java 只消费 `success` | 语义可接受，但 `closed` 字段从未被消费，属死字段 |
| Q8 | P3 | `ManagedProcessRunner.descendantsUnavailable` | 每次 run 结束额外枚举一次 descendants 仅用于元数据 | 开销小，可接受 |
| Q9 | P3 | `OwnedProcessTest` | 反射访问私有字段/构造器，测试与实现强耦合 | 可接受；重构时留意 |
| Q10 | P3 | `_finish_cleanup` | 嵌套 task + shield + 取消重抛逻辑复杂 | 注释到位；可考虑拆分或抽离为工具函数 |

---

## 五、测试覆盖与实测结果

### 5.1 覆盖评价

- **Java 新增/扩展 8 个测试类**：覆盖清理失败保留租约、启动失败释放预留、重复请求冲突、启动期取消、并发取消单飞、真实子进程树终止、真实进程身份场景（Linux-only，opt-in 真实 Chromium）。
- **Python 新增 3 个测试文件（~1250 行）**：mock 驱动的生命周期矩阵（创建失败/取消/并发/共享失败/迟到分配回滚/关闭闩锁语义/启动取消回滚/shutdown 失败续跑），另有一个 **opt-in 真实 Chromium 容器测试**，断言 tasks/zombies 收敛（基线 +3 内、零僵尸）——这是少见的对"进程泄漏"做定量回归的设计，质量高。
- 测试风格统一（pytest.mark.asyncio + mock 驱动层 + 少量真实进程），用例命名自解释。

### 5.2 本次实测（macOS arm64，本地环境）

| 套件 | 结果 |
|---|---|
| Python `tests/`（排除 opt-in 容器测试与 token_estimation） | **225 passed** |
| Python `test_browser_service_lifecycle.py` 首轮整跑 | **1 flake**（见下） |
| Java `mvn test`（全量） | **全部通过**；`OwnedProcessTest` 15 项中 6 项 Linux-only 按预期跳过 |
| 真实 Chromium 容器测试（`ZHIKUN_BROWSER_CONTAINER_TEST=1`） | 本次未运行（需容器环境）——**建议确认 CI 中有执行** |
| `test_token_estimation.py::test_estimate_batch` | 失败 1 次（563ms > 500ms 性能断言，与本 commit 无关，机器负载所致） |

### 5.3 发现的测试问题（P2）

- **flake：`test_waiting_ordinary_caller_shares_failure_without_recreating[True]`**
  - 首次整跑失败：`close_session` 等待 `reservation.done` 超过 fixture 的 `cleanup_timeout=0.05s`（"Timed out closing creating session resource"）；失败后还遗留 pending task 告警（`Task was destroyed but it is pending!`）。
  - 单独跑 5/5 通过；第二次整跑通过。根因是 50ms 超时在满负载事件循环下过紧（回滚需多轮调度）。
  - 建议：fixture 的 `cleanup_timeout` 提高到 ≥0.2s，或改为事件驱动等待。

---

## 六、风险清单汇总

| 风险 | 级别 | 触发条件 | 后果 |
|---|---|---|---|
| BashTool exit 0 误报失败（S1） | P1 | 清理 2s 内未确认（受限宿主/顽固子进程） | LLM 误判命令失败；shell 状态丢失 |
| 容量槽位永久保留（S2/Q1） | P1 | 终止确认持续失败 ×16 | 所有前台命令拒绝服务（软 DoS），无告警 |
| session 满员报错替代驱逐（S3） | P1 | 并发会话 ≥10 或清理失败累积 | 浏览器工具链报错（原为透明驱逐） |
| Linux 缺 setsid/bash | P1 | 精简镜像部署 | 所有进程启动失败 |
| 时钟偏移导致提前 504 | P2 | 分机部署 + NTP 偏移 | journey 误报超时 |
| waiter 无界阻塞（Q2） | P2 | new_context RPC 僵死 | 同 ID 请求挂起 |
| 测试 flake（5.3） | P2 | CI 负载高 | 偶发红构建 |
| Playwright 私有 API 依赖（Q3） | P2 | SDK 升级 | 启动回滚路径失效 |
| npm install 成功后报失败（Q5） | P2 | 清理未确认 | 验证流程误伤 |
| Windows PowerShell 行为不一致 | P3 | Windows 部署 | 语义不一致 |

---

## 七、审查结论与建议

1. **本次提交可以接受合并**（其修复的泄漏问题真实存在：旧代码前台命令遗留子进程、API 模式失败创建幽灵会话、`descendants()` PID 复用误杀风险）。未发现对正常功能链路的确定性破坏，两侧测试全绿。
2. **上线前建议确认三个语义决策**：S1（exit 0 + 清理未确认 = 失败）、S2（槽位保留策略的上限与告警）、S3（满员报错替代驱逐）。
3. **建议跟进**：修复 5.3 的 flake；为保留资源增加可观测性（指标/告警）；文档化 Linux 部署前提（setsid/bash）与时钟同步前提；确认 CI 覆盖 opt-in 的真实 Chromium 容器测试。
4. **无需回滚**：未发现需要回滚级别的缺陷。
