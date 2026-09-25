# zhikuncode 提交 0db4b049 独立代码审查报告

> **审查对象**：`0db4b0498f6cf886f862fe293883256b1c52e049` — *"fix: close process and browser lifecycle leaks"*
> **仓库**：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`（该提交为本地 `main` 的 HEAD，与 `origin/main` 一致，工作区干净）
> **规模**：24 个文件，+2870 / −400（Java 进程与验证链路、Python 浏览器资源容器、双端测试）
> **审查方式**：只读静态审查（`git show` 对照父提交 + 读取 HEAD 全文 + 调用方全仓检索 + 测试断言核对）。
> **重要声明**：本次审查**未修改 zhikuncode 仓库的任何代码 / 配置 / 工作区文件**；本报告为独立交付物，输出于外部目录。
> **审查立场**：不默认该提交正确；所有结论均给出可验证的 `文件:行号` 证据，并对初判结论做了二次核验与修正（见 §9.2 复核修正记录）。

---

## 1. 结论摘要（TL;DR）

| 维度 | 结论 |
|---|---|
| 是否破坏正常功能链路 | **未发现 P0（无编译/启动/崩溃/数据破坏级问题）**；成功路径行为不变。但有 **2 个 P1** 涉及"失败语义契约"与"容量/租约回收"，存在 **条件触发的工具能力静默耗尽** 路径，建议下一提交修复 |
| 跨端契约兼容性 | **向后兼容**：`deadline_epoch_ms` 为可选字段；`JourneyRequest` 保留 4 参构造器；Java↔Python 字段（`session_id` / `strict_session` / `close_session`）双侧一致；`"rv-"+UUID` 命名无外部消费方 |
| 调用方同步 | 已逐一核查，**生产调用点无遗漏**（唯一例外是 `@Deprecated` 且无生产调用方的 `UserJourneyVerifier` 仍保留旧命名，见 P3-2） |
| 测试覆盖 | 显著增强（Java 8 个测试文件、Python 30+11 个用例 + 1 个 opt-in 容器回归），但存在 5 处关键缺口（见 §5） |
| 放行建议 | **可安全保留在 main**，但上线前需执行 §8 的 Linux 冒烟清单；建议将 P1-1 / P1-2 / P2-1 / P2-2 排入下一提交 |

**三个用户可见的行为变更**（均为"有意"但需文档化）：

1. 前台命令正常结束后，会**终止本次命令遗留的后台子进程**（`cmd &`、`nohup` 等；显式 `setsid` 逃逸者除外）；
2. 清理未确认时，**exitCode=0 的命令也会被判定为工具失败**（`PROCESS_TERMINATION_UNCONFIRMED`，`Retryability.NEVER`），且**跳过 shell 状态同步**；
3. HTTP API 模式验证失败时**不再尝试生成 DOM 语义快照**（此前该调用会以 `strict_session=false` 让 Python 新建浏览器会话——正是本次要修的泄漏形态）。

---

## 2. 变更地图

### 2.1 Java 进程生命周期（核心）
| 文件 | 意图 | 实现手法 |
|---|---|---|
| `tool/process/OwnedProcess.java`（新增 258 行） | 进程归属可跨"重父化"追认，替代仅靠 `descendants()` 的弱归属 | `setsid` + `bash -c 'read admission; exec "$@"'` 握手包装（L45-64），验证身份后才放行用户代码（L68-82）；`/proc/<pid>/stat` 解析 pgrp/session/starttime 做 PID 复用防护（L154-182、L215-223）；`terminate(deadline, grace, gracefulRootFirst)` 梯度终止 + `known` 快照（L98-140） |
| `tool/process/ManagedProcessRunner.java` | 前台命令完成后也回收残留子进程；清理未确认时保留所有权与容量 | 无条件 `terminate`+`cleanup`（L146-149）；`leaseTransferred` 双保险（L88-94）；`releaseRetained` 以 `runnerFinished` 为门闩（L343-352）；cleanup 用 `CompletableFuture` CAS 缓存、并发共享单次重试（L372-400） |
| `tool/bash/ProcessTreeManager.java` | 收敛为 `OwnedProcess.terminateTree` 单一实现 | 删除自有实现；Javadoc 明确"普通 Process 只能清理调用时可发现的后代"（L18-19、L31-33） |
| `service/PythonProcessManager.java` | 停机/启动不再"假装清理成功" | `stopProcess` 返回确认结果（L357-366），未确认时置 `FAILED` 且保留 `processRef`（L131-137、L181-192）；`restart()` 增加 `processRef != null → false`（L197-199）；`catch (Error)` 先清理再抛（L165-169） |
| `tool/impl/BashTool.java` | 把"清理未确认"暴露给模型 | 主路径新增 `if (!result.terminationConfirmed())` 硬失败（L408-418），位于 `updateStateFromSnapshot` 之前（L419） |

### 2.2 Java 验证链路
- `verify/DevServerLauncher.java`：句柄在就绪探测前注册（L102-103）；`stop()` 未确认时保留句柄与 PID 文件（L132-141）；`pendingCleanup` + `runSync` 保留 primary failure（`addSuppressed`，L170-195）；`@PreDestroy` 二次回收（L143-147）。
- `tool/verify/VerifyJourneyTool.java`：每次调用生成独立浏览器资源 `"rv-"+UUID`（L318），贯穿 journey 请求（L336-342）、失败快照（L503-507）、finally 关闭（L374-387）；HTTP 模式传 `null`（L421-422）。
- `verify/BrowserVerifier.java`：HTTP 预算提升为 130s（工作量预算仍 120s，L20-24），新增 `deadline_epoch_ms`（L44）。
- `verify/JourneyRequest.java`：新增 `browserResourceId` 字段 + 4 参兼容构造器（L7-18）。

### 2.3 Python 浏览器资源容器
- `services/browser_service.py`：`_SessionCreation` 容量预留（L119-131）、容量原子校验（L411-412）、`asyncio.shield` 保护迟到分配（L434-435）、`_abort_creation` 兜底关闭（L466-482）、身份键 `_close_resource` + 单向 close latch（L324-357）、`_finish_cleanup` 屏蔽取消后重抛（L306-322）、外部关闭取消 journey owner（L504-508）、`release_session` 租约（L542-550）、`is_expired` 增加 `owner_task is None`（L115-116）、启动/关闭回滚。
- `routers/journey.py`：`deadline_epoch_ms` 504 判定（L33-37）、`asyncio.wait` + 断连轮询 499（L39-57）、`_run_owned_journey` 的 `BaseException` 关会话 / `finally` 释放租约（L65-85）。
- `services/journey_models.py`：新增可选 `deadline_epoch_ms`（向后兼容）。

### 2.4 测试
Java 8 个测试文件（`OwnedProcessTest` 新增 210 行、`ManagedProcessRunnerTest` 新增 206 行等）；Python `test_browser_service_lifecycle.py`（30 个用例）、`test_journey_lifecycle.py`（11 个用例）、`test_browser_resource_container.py`（opt-in 真实 Chromium 容器回归）、`test_journey_publication.py`（补 `release_session` mock）。

---

## 3. 功能链路影响评估（逐链路）

| # | 链路 | 结论 | 关键证据 |
|---|---|---|---|
| 3.1 | Bash 前台命令：启动/超时/中断/输出/失败分类 | **有行为变化（含 P1 风险）**：① exitCode=0 且清理未确认 → 工具硬失败且跳过 shell 状态同步；② 新增分支不走 `buildErrorWithClassification`，缺 `failure_category/failure_suggestion` | `BashTool.java:408-419`；测试固化该行为 `BashToolFailureClassificationTest.java:201-213` |
| 3.2 | Bash 后台命令（`is_background`） | **无破坏**：仍走 `startBackground`，进程由 session 租约托管，输出不捕获（既有语义） | `BashTool.java:349-367`；`ManagedProcessRunner.java:190-266` |
| 3.3 | 沙箱命令（docker 隔离） | **有行为变化**：清理未确认 → 硬失败且**丢失全部命令输出**（该分支为父提交既有实现，见 §9.2）；若 `terminate` 耗尽 2s 预算，容器清理 hook 会被跳过（finally 会以新预算重试一次） | `BashTool.java:477-485`；`ManagedProcessRunner.java:146-149,372-380` |
| 3.4 | Python 服务启停/健康检查 | **有行为变化（fail-safe）**：停机未确认 → `FAILED` + 保留引用；但 `FAILED` 无自动恢复路径（健康检查只接管 `RUNNING/HEALTH_CHECK_FAILED`），需显式 `start()/restart()` 或重启应用 | `PythonProcessManager.java:181-192,197-199,242-247,261-266` |
| 3.5 | DevServer 启动/就绪探测/停止 | **有行为变化（fail-safe）**：`stop()` 未确认时句柄留在 `activeHandles`，延后到 `@PreDestroy` 再确认；启动失败路径先 `stop` 后抛原始异常（顺序正确） | `DevServerLauncher.java:102-103,126-129,132-141,143-147` |
| 3.6 | VerifyJourney（browser 模式）↔ Python journey 契约 | **契约一致，无破坏**：`session_id`/`deadline_epoch_ms`/`strict_session` 双侧对齐；`"rv-"+UUID` 在仓库内无外部依赖（全仓检索仅命中实现与测试）；`close_session` 未确认在 Java 侧降级为日志 + TTL 兜底 | `VerifyJourneyTool.java:318,336-342,374-387,503-507`；`BrowserVerifier.java:20-24,42-53`；`journey.py:33-57`；`browser_service.py:484-525` |
| 3.7 | VerifyJourney（http_api 模式） | **有行为变化（副作用转正）**：`browserResourceId=null` 使失败时不再尝试 DOM 快照；此前该调用会以 `strict_session=false` 让 Python **新建**浏览器会话（正是本次要修的泄漏）——代价是 HTTP 模式失败信息缺少快照富化 | `VerifyJourneyTool.java:421-422,503-507` |
| 3.8 | Python journey 生命周期 | **无破坏，契约一致**：正常返回（含步骤失败）保留 context 供 Java 抓失败快照；取消/断连/超时路径均 `close_session(expected_session=...)` + `release_session` | `journey.py:65-85`；`browser_service.py:542-550`；`test_journey_lifecycle.py:55-233` |
| 3.9 | 进程树管理器对外契约 | **语义收紧（已文档化）**：返回值由"已发起终止"变为"已确认退出"；普通 `Process` 只能清理调用时可发现的后代；3 个调用方已同步 | `ProcessTreeManager.java:18-19,29-33`；`DevServerLauncher.java:133,146,189` |

**调用方同步核查（已逐一验证，未发现生产遗漏）**：`OwnedProcess.start` 的 3 个生产调用点（`ManagedProcessRunner.java:120`、`PythonProcessManager.java:146`、`DevServerLauncher.java:95,175`）均传入默认 PIPE stdin；`OwnedProcess.terminateTree` 唯一生产调用点 `ProcessTreeManager.java:32`；`cancelRunDetailed` 生产调用点 `RunTerminationCoordinator.java:60,89` 已适配 `CancelSummary`；`HttpApiVerifier` 仍使用业务 `sessionId`，未受新字段影响；`VerifierFactory` 仅装配 `BrowserVerifier`/`HttpApiVerifier`。

---

## 4. 问题清单

> 分级标准：**P1** = 需在下一提交修复的真实缺陷/契约风险；**P2** = 应跟进的行为变更或健壮性问题；**P3** = 规范、性能与优化建议。

### P1-1【真实缺陷·本次引入】exitCode=0 的命令被改判为硬失败，并跳过 shell 状态同步
- **位置**：[BashTool.java#L408-L419](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/impl/BashTool.java)
- **问题**：`if (!result.terminationConfirmed())` 位于所有成功/失败判定之前（L408）。命令 exitCode=0 时也返回 `ToolResult.failed(..., "PROCESS_TERMINATION_UNCONFIRMED", ..., Retryability.NEVER, EffectState.UNKNOWN, 0, ...)`。由于成功路径的 `EffectState` 同为 `UNKNOWN`（L425），该字段无法帮助模型区分"命令已生效"，模型只看到"失败 + 永不重试 + cleanup could not be confirmed"，**容易误判命令未执行**。此外 L419 的 `shellStateManager.updateStateFromSnapshot(sessionId)` 在 return 之后 → **成功命令的 cwd/env 快照被丢弃**，后续同会话命令的目录/环境状态失同步（测试已把该行为固定下来：`BashToolFailureClassificationTest.java:213` 断言 `never()).updateStateFromSnapshot`）。该分支也未经过 `buildErrorWithClassification`，缺失 `failure_category/failure_suggestion` 元数据（对比 L422-424）。
- **复现逻辑**：构造 `Result(0,"done","diag",...,terminationConfirmed=false,...)` → `isError()==true`、`failureCode()=="PROCESS_TERMINATION_UNCONFIRMED"`（测试 L201-211 即该路径）。
- **修复建议**：保留"清理未确认"的安全语义，但（a）恢复 `updateStateFromSnapshot(sessionId)`（命令确已结束，状态可信）；（b）文案明确"命令已执行完成，但残留子进程回收未确认"；（c）补齐 `failure_category/failure_suggestion` 与 `exitCode` 元数据，与其它失败路径一致。

### P1-2【真实缺陷·本次引入】清理未确认条目长期占用并发许可与 Run work lease，无周期性回收
- **位置**：[ManagedProcessRunner.java#L146-L186](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java)、[#L343-L352](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java)
- **问题**：`stopped==false` 时既不 `releaseRetained` 也不释放许可（L173-178）。唯一释放点 `releaseRetained`（L343-352）只由 `cancelRunDetailed`（L311-331）、`cancel`（L333-341）、`shutdown`（L287-295）触发。综合核验后的准确结论：
  - **存在一次"完成期重试"**：`RunTracker.completeRun`（`RunTracker.java:74-81`）在 `executions.awaitQuiescence` 失败时（被保留的 work lease 会阻塞 quiescence，见 `RunExecutionRegistry.java:643-654`）会调用 `termination.terminate(...)` → `processes.cancelRunDetailed(runId)` 重试并可能释放；
  - **但无周期性回收**：若该次重试仍未确认，run 被置为 `FAILED(TOOL_TERMINATION_UNCONFIRMED)`，此后不再有重试入口，**每个未确认条目永久占用 1 个许可**（默认 16，L43-44）；累积到上限后 L100 使所有前台进程调用直接抛 `IOException("PROCESS_CAPACITY_EXCEEDED")`，无自愈（测试已固化"保留"行为：`ManagedProcessRunnerTest.java:26-72`，但只覆盖"取消后恢复"，未覆盖"无取消 → 终态"）。
  - **附带行为变更**：因保留 lease 阻塞 quiescence，一个"模型已正常完成"的 run 会被判定为**失败**（`TOOL_TERMINATION_UNCONFIRMED`），而非 `COMPLETED`——fail-safe 方向可接受，但属用户可见的状态降级，需在发布说明中标注。
- **修复建议**：为未确认条目增加**有界后台重试/回收**（如每 30s 一次、最多 N 次，或按 TTL 老化后再次 `terminate+cleanup` 并回收许可）；补充"无取消 → 最终回收"的测试用例。

### P2-1【真实缺陷·本次引入】`terminate` 与 `terminationHook.cleanup` 共享同一 2s 预算，容器清理 hook 可能被跳过
- **位置**：[ManagedProcessRunner.java#L146-L149](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java)、[#L372-L380](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java)
- **问题**：`cleanupDeadline = now+2s`（L146）先被 `terminate` 消耗（`OwnedProcess.java:132` 允许循环到 deadline），随后 `cleanup(...)` 在 L380 于**调用 hook 之前**直接返回 false → 沙箱容器清理（`DockerRuntimeService.ensureRemoved`）与自定义 hook 被静默跳过，同时触发 P1-1 的硬失败。`finally`（L170-172）会用新预算重试，但 terminate 若再次耗尽预算，hook 依旧不执行。
- **修复建议**：为 hook 单独计算预算（例如 `long hookDeadline = now + 2s` 后再传入 cleanup），或让 `cleanup` 在 hook 场景下不再共享 terminate 的 deadline。

### P2-2【既有缺陷被放大·非本次引入】沙箱分支"清理未确认"时丢弃全部命令输出
- **位置**：[BashTool.java#L477-L485](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/impl/BashTool.java)
- **归属说明**：该分支在父提交（`git show 0db4b049^:.../BashTool.java` 第 466-473 行）中已存在，**不是本次引入**；但本次改动让 `terminationConfirmed` 在"命令已结束"场景下也可能为 false，触发概率上升。
- **问题**：主路径同类分支携带 `outputProcessor.processOutput(combined, true)`（L411-412），而沙箱分支（L480）只返回固定文案，`result.stdout()/stderr()` 被完全丢弃，且缺少 `stdoutTruncated/stderrTruncated/descendantTrackingUnavailable` 元数据。
- **修复建议**：与主分支保持一致，拼接 `combined` 并补齐截断元数据；补充该分支的断言用例（当前无测试覆盖）。

### P2-3【部署依赖·本次引入】Linux 路径新增 `setsid` + `/bin/bash` 硬依赖
- **位置**：[OwnedProcess.java#L35-L52](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java)
- **问题**：`OwnedProcess.start` 在 Linux 上要求 `/usr/bin/setsid` 或 `/bin/setsid` 与 `/bin/bash` 可执行，否则抛 `IOException("PROCESS_GROUP_UNAVAILABLE")`。本次提交使 `PythonProcessManager`（L146）与 `DevServerLauncher`（L95/L175）的启动路径也走该实现——此前它们是原生 `ProcessBuilder.start()`。官方运行镜像 `eclipse-temurin:21-jre-noble`（Dockerfile L89）为 Ubuntu，默认包含二者（Dockerfile 未显式安装），**默认不受影响**；但自建精简镜像/非 Ubuntu 发行版（如 Alpine、distroless、部分 CentOS-minimal）会直接导致：Python 服务无法启动、DevServer/npm install 失败、Bash 工具全部失败。
- **修复建议**：（a）在发布说明/部署文档中显式声明依赖；（b）启动时做一次自检（例如启动阶段执行 `bash -c "true"` 断言 `terminationConfirmed==true`），并在缺失 setsid/bash 时给出明确告警；（c）错误信息 `PROCESS_GROUP_UNAVAILABLE` 补充缺失项与补救指引。

### P2-4【健壮性·条件触发】`/proc` 扫描持续失败 → "全部前台命令硬失败 + 逐条泄漏许可"叠加
- **位置**：[OwnedProcess.java#L108-L135](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java)、[#L215-L223](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java)
- **问题**：`liveMembers()` 的 group 发现依赖遍历 `/proc` 与 `readIdentity`；`readIdentity` 仅吞 `NoSuchFileException`（L218），其余 `IOException`/`NumberFormatException`（字段解析 L221-222）会冒泡到 `terminate` 的 catch（L111-115）→ `inspected=false` → 永不确认。若环境持续如此（hidepid、受限容器、/proc 不可读），**每条前台进程调用都会命中 P1-1 硬失败，并由 P1-2 逐条泄漏许可**。当前 catch 无任何日志，运维无法区分"进程没退"与"看不到 /proc"。
- **修复建议**：区分"能力不可用（一次性 WARN + 降级为快照确认）"与"临时失败"；`readIdentity` 对解析异常做防护并记录；部署冒烟断言 `terminationConfirmed==true`。

### P2-5【行为变更·有意】前台命令完成后回收残留子进程，可能破坏既有用法
- **位置**：[ManagedProcessRunner.java#L146-L148](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java)、[OwnedProcess.java#L150-L200](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java)
- **问题**：终止范围按 session/group 归属（含已脱离父进程的 `cmd &`、`nohup` 子进程；显式 `setsid/setpgid` 逃逸者除外，已在 Javadoc L17-19 说明）。"前台 Bash 起 dev server"这类既有用法在命令返回时会把服务杀掉。缓解手段是 `is_background`（`BashTool.java:175-177,244,349-367`），但工具 prompt 未明确说明"前台命令的残留子进程会被回收"。
- **修复建议**：在 Bash 工具描述中补一句提示；在 CHANGELOG/发布说明中列为行为变更。

### P2-6【可用性·fail-safe】`PythonProcessManager` 进入 `FAILED` 后无自动恢复路径
- **位置**：[PythonProcessManager.java#L181-L192](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/service/PythonProcessManager.java)、[#L242-L247](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/service/PythonProcessManager.java)、[#L261-L266](file:///Users/guoqingtao/Desktop/dev/code/zhikuncode/backend/src/main/java/com/aicodeassistant/service/PythonProcessManager.java)
- **问题**：`stop()` 未确认 → `FAILED` + 保留 `processRef`；`scheduledHealthCheck`/`runHealthCheckCycle` 只处理 `RUNNING`/`HEALTH_CHECK_FAILED`（已核验 L242-247、L261-266），`FAILED` 不再被健康检查接管；全仓库也无其它类注入该 Bean（`grep` 仅命中自身）。fail-safe 方向正确（避免重复启动第二个 uvicorn 占用端口），但"Python 能力永久不可用且无告警"是新的运维面风险。
- **修复建议**：保留 fail-safe，补充"未确认停机"的可观测事件/指标（含 pid 与原因），并提供受控的手动 `restart()` 入口。

### P3 级问题（规范、性能与优化）
| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| P3-1 | 工具级失败码 `"PROCESS_TERMINATION_UNCONFIRMED"` 与 `RunEnvelope.RunExitReason.PROCESS_TERMINATION_UNCONFIRMED` 字符串完全相同，日志/检索易混淆 | `BashTool.java:410`；`RunTerminationCoordinator.java:65` | 工具侧改为 `PROCESS_CHILD_CLEANUP_UNCONFIRMED` 或加前缀 |
| P3-2 | `UserJourneyVerifier`（`@Deprecated`、无生产调用方）仍拼 `"rv-"+sessionId`，未同步新的资源命名；若被复活将产生会话泄漏 | `UserJourneyVerifier.java:36` | 同步 `req.browserResourceId()` 或删除该类 |
| P3-3 | `VerifyJourneyTool` 存在未使用的 `UserJourneyVerifier` import | `VerifyJourneyTool.java:24` | 清理 import |
| P3-4 | `DevServerLauncher.pendingCleanup` 仅在 `@PreDestroy` 重试，运行期不重试（影响面小，有兜底） | `DevServerLauncher.java:143-147` | 与 P1-2 一并考虑统一的有界回收 |
| P3-5 | `JourneyRequest` 4 参构造器隐式生成随机 `browserResourceId`，而该构造器实际只用于选择验证器（`VerifyJourneyTool.java:267`），语义含混 | `JourneyRequest.java:14-18` | 需要处显式传 `null` |
| P3-6 | 非 Linux 平台 `waitFor` 以 ≤20ms 粒度全量枚举 `descendants()`，长命令期间持续产生进程表快照（Linux 生产路径不受影响） | `OwnedProcess.java:236-250` | 轮询粒度放宽到 200-500ms，或仅在取消/超时后集中快照 |
| P3-7 | `terminate` 每轮 10ms 全 `/proc` 扫描且扫描失败静默降级（无日志） | `OwnedProcess.java:106-135` | 复用上一轮结果；一次性 WARN 并纳入 observability |
| P3-8 | `deadline_epoch_ms` 依赖两端时钟一致（Java `System.currentTimeMillis()` ↔ Python `time.time()`）；分离部署/容器时间漂移会导致 504 误判 | `BrowserVerifier.java:44`；`journey.py:35` | 文档标注"同宿主"前提，或改传"剩余毫秒数" |
| P3-9 | Python 清理阶段无总时限（`journey.py:50-57` 的 `gather` + `cleanup_timeout=5.0` 多资源串行），最坏可能超出 Java 130s 预算，得到 `PYTHON_CALL_FAILED`（资源仍由 TTL 兜底） | `journey.py:50-57`；`browser_service.py:159` | 清理阶段加总时限（`asyncio.wait_for`） |
| P3-10 | `runSync` 在 `finally` 中 `throw`（虽已核验不会吞异常：所有原始异常均先赋值 primaryFailure） | `DevServerLauncher.java:185-195` | 改为 try/catch 之后统一抛出 |
| P3-11 | 清理未确认的 WARN 缺少 `runId/toolUseId` 上下文 | `ManagedProcessRunner.java:177` | 补上下文，便于定位具体工具调用 |

---

## 5. 测试覆盖评估

### 5.1 覆盖到的关键路径（质量总体较高）
- **进程归属与 PID 复用防护**：`OwnedProcessTest.java` — `admitsDescendantsOnlyWhileOwnerIdentityRemainsAlive`（L22-71，反射注入验证 `known` 快照与"不看活身份"）、`cleansStubbornChildAfterParentExitedWithoutKillingAnotherTask`（L74）、`preservesOutputEnvironmentAndExitStatus`（L102）、`retainsDetachedBrowserLikeChildBeforeStoppingItsDriver`（L117）、`stoppedRealChromiumIsReapedByForcedServiceCleanup`（L139）、`rootGetsGraceToRunItsOwnShutdownHook`（L176）、`interruptedCallerStillCleansAndRetainsInterruptStatus`（L190）。其中真实进程树用例带 `@EnabledOnOs(OS.LINUX)` 门控（L73/101/116/137/175/189）。
- **清理未确认的所有权保留与并发重试**：`ManagedProcessRunnerTest.java` — `failedCleanupRetainsRunLeaseUntilRetryConfirmsExit`（L26-72，断言 `availablePermits()==0` 与 `PROCESS_CAPACITY_EXCEEDED`）、`concurrentCancellationsShareOneRetryAfterFailedCleanup`（L155-197）、`foregroundParallelWorkThatWaitsStillCompletesNormally`（L213-221）、`survivingChildRetainsLeaseAfterShellExitsOrIsKilled`（L223-258）。
- **工具结果契约**：`BashToolFailureClassificationTest.java:200-214` 覆盖新增硬失败分支（同时也固化了"跳过 shell 状态同步"这一可疑行为，见 P1-1）。
- **dev server**：`DevServerLauncherTest.java` — `failedCleanupRetainsHandleAndPidFileForRetry`（L39）、`interruptedInstallPreservesInterruptAndPrimaryFailureWhenCleanupFails`（L56）、`failedInstallPreservesExitErrorWhenCleanupFails`（L74）、`successfulInstallStillFailsAndRetainsProcessWhenCleanupIsUnconfirmed`（L90）。
- **验证链路**：`VerifyJourneyEdgeCaseTest`（空步骤/超大 journey/超时/端口占用/能力不可用降级）、`BrowserVerifierTest`（断言 `browserResourceId` 与业务 `sessionId` 解耦，L123-124）。
- **Python 侧（含真实 SDK 语义）**：`test_browser_service_lifecycle.py` 30 个用例，包括真实 SDK 内部钩子验证"失败的 close 不能被当作 latch 成功重试"（L402-452，操作 `DriverContext._closing_or_closed` / `PlaywrightContextManager._exit_was_called`）、容量原子性、外部关闭取消 journey、关闭后不可重建、启动回滚；`test_journey_lifecycle.py` 11 个用例覆盖 504/499/取消/断连/重复创建/清理失败不覆盖取消。

### 5.2 缺口与测试质量问题
1. **P1-2 的终态未覆盖**：没有"未确认条目在无取消场景下最终是否回收"的断言；建议补"16 次未确认后 `PROCESS_CAPACITY_EXCEEDED`"（或实现自愈机制后的相反断言）。
2. **P2-1 未覆盖**：缺少"慢 terminate + 慢 hook"组合用例以验证 hook 是否被跳过。
3. **沙箱硬失败分支无断言用例**（P2-2 因此未被测试发现）。
4. **非 Linux 分支覆盖薄**：`leader==null` 的真实 `waitFor`/terminate 路径无测试；macOS/Windows 行为实际未验证（真实进程用例全部 `@EnabledOnOs(OS.LINUX)`）。
5. **容器回归不在常规门禁**：`test_browser_resource_container.py:29` 需要 `ZHIKUN_BROWSER_CONTAINER_TEST=1`，常规 CI 不执行 → 真实 Chromium 的收敛场景属"人工可选"。
6. **白盒耦合较强**：`OwnedProcessTest` 使用反射注入私有构造器/字段、`ManagedProcessRunnerTest` 使用 `mockStatic(OwnedProcess.class)` + `ReflectionTestUtils` 改写 `capacity`；私有结构变动即失效，建议至少为 `OwnedProcess.start` 保留一个 Linux 真实集成用例替代部分 mock。
7. **未见**对 `PROCESS_GROUP_UNAVAILABLE`（setsid/bash 缺失，`OwnedProcess.java:47-49`）与 "Owned process requires piped stdin"（L50-52）的断言用例。

---

## 6. 规范性与代码质量

**优点**：关键设计决策均有注释解释意图（如 L147 "Foreground completion owns its remaining children"、`OwnedProcess` 类注释明确"生命周期归属≠安全沙箱"）；fail-safe 方向一致（宁可保留所有权也不谎报清理成功）；测试命名清晰、语义可读；补充了 `process_started/process_finished` 观测事件。

**问题**：
1. 新增失败分支未复用 `buildErrorWithClassification`，同类失败元数据不齐（P1-1）。
2. 工具级失败码与 run 级退出原因同名（P3-1）。
3. `cleanup` 的 `for(;;)` + CAS break 条件（L372-394）缺少注释，可读性偏低（本地语义正确，二次核验未发现并发缺陷）。
4. `runSync` 的 `finally { throw }` 反模式（P3-10）。
5. 注释语言中英混排（`OwnedProcess` 全英文、`ProcessTreeManager` 中文）——属既有风格分裂，**非本次引入**，不计入问题。
6. 遗留类 `UserJourneyVerifier` 未随本次资源命名同步（P3-2），且留下未使用 import（P3-3）。

---

## 7. 改进建议（按优先级）

1. **P2-1**：隔离 hook 预算（改动最小，直接消除沙箱容器残留隐患）。
2. **P1-2**：为未确认条目增加有界后台回收/TTL，并在发布说明中标注"run 可能因清理未确认被判定为失败"。
3. **P1-1 / P2-2**：在保留安全语义的前提下，恢复 shell 状态同步、补齐分类元数据，并让沙箱分支保留命令输出。
4. **P2-4 / P2-3**：启动自检（`terminationConfirmed` 断言）+ `/proc`/setsid 能力降级路径可见化（一次性 WARN + 指引）。
5. **文档化三处行为变更**：Linux 依赖 setsid/bash；前台命令回收残留子进程；HTTP 模式失败不再生成 DOM 快照。
6. **补测试**：§5.2 的 1-5 项。
7. **命名与日志**：P3-1、P3-11；清理 P3-2/P3-3。
8. **性能（非阻塞）**：P3-6 / P3-7。

---

## 8. 放行与验证建议

- **结论：可安全保留在 main**。未发现 P0；核心链路的正常路径行为不变（成功命令在清理确认时行为与父提交一致，已用 `foregroundParallelWorkThatWaitsStillCompletesNormally` 等用例核验）。
- **上线前冒烟清单（建议在目标 Linux 容器内执行）**：
  1. 执行 `bash -c "true"` → 工具成功且元数据 `terminationConfirmed == true`（验证 P2-3/P2-4 不触发）；
  2. 连续执行 20 次"含残留子进程"的命令（如 `bash -c 'sleep 300 & echo ok'`）→ 全部成功且不出现 `PROCESS_CAPACITY_EXCEEDED`（验证 P1-2 不触发）；
  3. 完整跑一次 VerifyJourney（browser 模式）→ 结束后 `/api/browser/sessions` 应无 `rv-*` 残留会话；
  4. 重启应用 → 观察 Python 服务 `stop/start` 无 `cleanup unconfirmed` WARN 累积。
- **若冒烟 1 失败**：P2-4 → P1-1 → P1-2 会叠加放大（工具不可用 + 容量耗尽），必须先修 P1-2/P2-4 再放量。

---

## 9. 审查方法、可信度与局限

### 9.1 方法与局限
- 采用 **只读静态审查**：`git show <hash>` 对照父提交逐文件比对 + 读取 HEAD 全文交叉核对 + 全仓检索调用方 + 逐条核对测试断言。
- **未执行构建与测试实跑**（避免产生工作区改动/构建产物），因此"编译通过性"与"测试实际通过率"未在本次审查中验证；结论基于代码语义推理与既有测试断言核对。
- 所有行号均指 HEAD（0db4b049）版本文件。

### 9.2 复核修正记录（对初判结论的二次核验）
1. **沙箱分支丢失输出（P2-2）**：初判认为是本次引入；经与父提交比对，该分支**父提交已存在**（父版本第 466-473 行），已修正归属并说明"触发概率被本次改动放大"。
2. **容量保留条目是否"永不回收"（P1-2）**：初判认为正常完成的 run 完全不会触发回收；经核验 `RunTracker.completeRun → awaitQuiescence 失败 → termination.terminate → cancelRunDetailed` 存在**一次完成期重试**，已修正为"有完成期重试、但无周期性回收，重试失败后永久占用许可，并使 run 被判定为失败"。

### 9.3 已明确"未发现问题"的模块及核查范围
`ProcessTreeManager`（单一实现 + 3 个调用方同步 + Javadoc 语义一致）；`JourneyRequest`（字段与 4 参构造器向后兼容，5 处调用点核查）；`BrowserVerifier`（130s/120s 预算自洽、字段只增）；`journey_models.py`（可选字段、向后兼容）；`browser_service.py` 的取消/超时/断连/容量/关闭序列（对照 30+11 个用例逐条核对，含真实 SDK close latch 语义）；`journey.py` 的 504/499/清理路径；`DevServerLauncher` 的句柄注册顺序与启动失败清理；`HttpApiVerifier`（仍使用业务 `sessionId`，未受新字段影响）；`UserJourneyVerifier`（`@Deprecated` 且无生产调用方，不构成现网链路）。

---

*报告生成时间：2026-09-25 ｜ 审查范围：提交 0db4b049 的全部 24 个变更文件及其上下游调用方 ｜ 本报告不包含对 zhikuncode 仓库的任何代码改动。*
