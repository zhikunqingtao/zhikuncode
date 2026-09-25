# zhikuncode 提交 0db4b049 独立审查报告

> **审查对象**：`fix: close process and browser lifecycle leaks`
> **仓库**：zhikunqingtao/zhikuncode
> **提交**：`0db4b0498f6cf886f862fe293883256b1c52e049`（GitHub 链接：https://github.com/zhikunqingtao/zhikuncode/commit/0db4b0498f6cf886f862fe293883256b1c52e049 ）
> **父提交**：`e78d686`；本地 HEAD、origin/main 与该提交三方一致（已在审查前核对哈希）
> **审查日期**：2026-09-25
> **审查方式**：独立对抗式审查（不预设提交正确），三路并行（Java 深度审查 / Python 深度审查 / 测试执行与部署验证）
> **审查原则**：只读分析 + 运行测试取证；**未修改 zhikuncode 任何源码、配置与测试**（收尾 `git status` 为空，构建产物均在 gitignore 范围）

---

## 目录

1. [审查结论摘要](#1-审查结论摘要)
2. [审查范围与方法](#2-审查范围与方法)
3. [详细发现（P1/P2/P3）](#3-详细发现)
4. [正常功能链路逐链路影响分析](#4-正常功能链路逐链路影响分析)
5. [代码质量与规范性评估](#5-代码质量与规范性评估)
6. [测试覆盖与执行验证](#6-测试覆盖与执行验证)
7. [部署环境核验（setsid / 时钟 / 契约）](#7-部署环境核验)
8. [提交信息四项声明核验](#8-提交信息四项声明核验)
9. [改进建议（按优先级）](#9-改进建议按优先级)
10. [未验证事项与审查局限性](#10-未验证事项与审查局限性)
11. [附录：证据留存](#11-附录证据留存)

---

## 1. 审查结论摘要

### 1.1 总体判定

本次提交的**核心修复方向正确**：以"setsid 会话归属 + /proc 身份校验"取代不可靠的 `descendants()` 后验枚举、以"保留所有权直至确认清理"取代"尽力而为"、为浏览器资源引入独立资源 ID（`rv-<uuid>`）、Python 侧引入 deadline 与取消安全清理——这些改造在**正常成功路径上没有引入回归**，Java→Python 的关停/快照/清理契约闭环也已逐项核验通过。**最高优先级的"部署阻断"疑点（Linux 生产镜像缺 setsid）经 Docker 实测排除**：基础镜像与仓库构建镜像均自带 `/usr/bin/setsid`（util-linux 核心包）与 `/bin/bash`。

但该提交**不能视为"正确无问题"**，审查发现了 **3 条 P1、6 条 P2、5 条 P3** 的风险点，其中既有真实缺陷，也有需要产品确认的破坏性语义变更：

- **P1（缺陷）**：清理未确认时，容量槽位与 WorkLease 被无限期保留且**无任何自动回收/重试通道**；一旦发生，将经由 `RunExecutionRegistry` 级联导致**同一会话在重启前无法再发起任何查询**（完整链路已由协调者独立复核）。
- **P1（条件缺陷）**：`OwnedProcess.terminate` 的 `inspected` 门控在"无法枚举后代"的宿主（父提交注释中明确存在此环境，如 macOS sandbox sysctl 被拒）上会让超时/取消命令长期判为"未确认"，每条多付 ~4s 延迟并叠加上述泄漏。
- **P1（破坏性语义变更）**：前台命令**正常结束**后现在会无条件清理并杀死会话组内的残留子进程——`npm run dev &`、`nohup ... &` 这类写法返回后被静默杀死，而工具仍上报 exit 0 + SUCCESS。这是相对父提交的用户可见行为变化，需产品确认与文档化。
- **P2**：DevServer 未确认停止后残留句柄/端口会被后续验证持续命中；Python 侧关闭失败的 context 永久占用 1/10 容量并封锁 session_id（Java 注释中"TTL 兜底"在该异常场景不成立）；Python 容量策略从"驱逐最旧"变为"硬拒绝"且 journey 路径首次出现容量上限，拒绝时以裸 500 暴露给 Java（显示为误导性的 "unreachable or timeout"）。
- **P2（测试）**：新增测试整体质量高于仓库平均，但存在门禁缺口——ProcessTreeManagerTest 用 `assumeTrue(false)` 静默跳过核心断言；唯一的真实 Playwright 资源收敛测试在 CI 恒被 skip；若干新测试为时序/负载敏感型（本机已实测到负载下的失败）。

**建议处置**：不建议回滚（修复方向与大部分目标达成）；建议在下次发布前至少修复 P1 项，或明确运维缓解预案（见 [第 9 节](#9-改进建议按优先级)）。

### 1.2 对两个审查重点的直接回答

**重点 1：是否对 zhikuncode 正常功能链路造成负面影响或破坏？**

| 功能链路 | 判定 | 说明 |
|---|---|---|
| 前台 Bash 命令（普通命令） | ✅ 基本无回归 | 成功率/输出/退出码语义不变；但**残留子进程行为变化**（见 H3） |
| 前台 Bash 命令（`&`/nohup 后台化） | ⚠️ 行为破坏（静默） | 工具返回时残留进程被清理杀死，工具仍报成功（H3） |
| 前台 Bash 命令（受限宿主：无法枚举后代） | ⚠️ 条件性风险 | 可能被判 `PROCESS_TERMINATION_UNCONFIRMED` 且每次 +4s（H2） |
| 清理未确认的会话 | ❌ 潜在会话级不可用 | 容量/租约泄漏 → `SESSION_EXECUTION_ALREADY_REGISTERED` → 查询失败（H1） |
| 后台命令通道（`is_background`） | ✅ 未受影响 | diff 未触及；容量由既有 group.exited 回调释放 |
| Python 服务生命周期 | ⚠️ 语义变化、影响面有限 | 无外部 Java 调用方（auto-start 默认 false）；关停最长阻塞 12s（H8） |
| DevServer + 浏览器 journey 验证（成功路径） | ✅ 无回归 | 资源 ID 隔离更优；失败快照/清理顺序/契约均核验通过 |
| DevServer（清理未确认后） | ❌ 后续验证持续失败 | 残留端口占用，重试仅 @PreDestroy（H4） |
| journey 验证（Python 容量拒绝时） | ⚠️ 误导性失败 | 裸 500 → Java 报 "PYTHON_CALL_FAILED"（H7） |
| 交互式浏览器（WebBrowser 工具） | ⚠️ 边缘风险 | 共享路径等价性已核验；但关闭失败会永久封锁 session_id（H6）、创建期 close 竞态返回 500（H11） |
| 前端 / 其他工具 | ✅ 不受影响 | 本次提交未涉及 |

**结论**：正常"成功路径"（happy path）没有发现回归；但存在 3 类会真实破坏特定使用方式的变更/缺陷（H1/H3 为主，H4/H6/H7 次之）。其中 H1、H3、H6 建议发布前修复或明确产品决策。

**重点 2：代码质量、测试覆盖及规范性评估**

- **代码质量：良好偏优**。设计意图清晰（注释解释了每个权衡）；错误处理认真（主异常保留、`addSuppressed`、中断位保存/恢复、PID 复用防护均有实现与理由）；Java/Python 契约逐项核验通过；未发现敏感信息泄漏或明显的线程安全错误。**主要问题**：保留所有权却没有回收通道（设计缺口）、语义变更未同步 CHANGELOG/文档、个别注释与实际行为不符（`descendantTrackingUnavailable` 误导、UserJourneyVerifier 协议不一致）。
- **测试覆盖：中上，有门禁缺口**。新增/修改 12 个测试类/文件（Java 8 类 + Python 3 文件），断言质量高（行为断言、真实进程、竞态用例、`touch should-not-exist` 证明未执行等），但也存在：静默 skip、CI 恒 skip 的真实浏览器测试、mock 绑定 Playwright 私有字段、时序敏感用例在负载下 flaky（本机已复现）。
- **规范性：小问题若干**。CHANGELOG 未更新（与仓库惯例不符）、docs 未同步、中英注释混用、死代码/未用 import、测试内 `assumeTrue(false)` 反模式。

### 1.3 风险分级总表

| ID | 级别 | 主题 | 位置（主要） | 触发条件 |
|---|---|---|---|---|
| H1 | **P1** | 未确认清理 → 容量槽/WorkLease 无回收 → 会话永久不可用 | `ManagedProcessRunner.java:171-179,343-350`；`RunExecutionRegistry.java:258-263,87-96`；`QueryEngine.java:326-350` | 任一 terminate/cleanup 未确认（H2/H3 场景均可触发） |
| H2 | **P1**（条件） | `inspected` 门控在受限宿主上长期"未确认"+每条 +4s | `OwnedProcess.java:105-121,150-152` | 宿主无法枚举后代（macOS sandbox/hidepid/seccomp）且清理时刻有存活成员 |
| H3 | **P1** | 正常结束也杀会话组残留子进程（静默行为变更） | `ManagedProcessRunner.java:147-149` | 前台命令以 `&`/nohup 等留下子进程 |
| H4 | P2 | DevServer 未确认停止 → 残留端口 → 后续验证失败 | `DevServerLauncher.java:132-139` | dev server 清理未确认 |
| H5 | P2 | BashTool 新失败分支语义细节（metadata 误导、exit-0 判失败） | `BashTool.java:408-418` | 配合 H2/H3 |
| H6 | P2 | Python：关闭失败 context 永久占容量 + 封锁 session_id；TTL 兜底失效 | `browser_service.py:324-357,406-412,582-597` | `context.close()` 异常 |
| H7 | P2 | Python：容量策略改硬拒绝 + journey 首次限容 + 裸 500 误导 | `browser_service.py:393-412`；`journey.py:66-70` | 达到 max_sessions(10) 或容量被泄漏 |
| H8 | P2 | PythonProcessManager 未确认即 FAILED、保留引用、关停最长 12s | `PythonProcessManager.java:181-205,360-366` | Python 服务清理未确认 |
| H9 | P2 | 测试门禁缺口：静默 skip / CI 恒 skip / 私有字段耦合 / 时序 flaky | 各测试文件 | 见第 6 节 |
| H10 | P3 | journey 504/499 无 Python 日志、无部分结果 | `journey.py:37,48-49` | deadline 到期 / 客户端断开 |
| H11 | P3 | close 与"创建中"并发 → CancelledError → 500 | `browser_service.py:498-500,420-427` | 创建期并发 close |
| H12 | P3 | UserJourneyVerifier 与新的 130s/唯一资源 ID 协议不一致（当前不可达） | `UserJourneyVerifier.java:19,36` | 若未来启用 |
| H13 | P3 | 规范性打包：CHANGELOG/docs 缺失、中英混用、死代码、label 双语义、`rv-` 32bit、预算余量收窄等 | 多处 | — |
| H14 | P3 | Python 进程内浏览器能力不可恢复状态仅日志、未暴露 health | `browser_service.py:188-192` | shutdown/close 异常后 |

> **P0 说明**：无。审查前怀疑的"生产镜像缺 setsid 导致全部命令失败"阻断已用 Docker 实测排除（见 [第 7 节](#7-部署环境核验)）。

---

## 2. 审查范围与方法

### 2.1 范围

- **Java（backend/src/main/java）**：`OwnedProcess.java`（新增 257 行）、`ManagedProcessRunner.java`（±189）、`ProcessTreeManager.java`、`PythonProcessManager.java`、`BashTool.java`、`VerifyJourneyTool.java`、`BrowserVerifier.java`、`DevServerLauncher.java`、`JourneyRequest.java`
- **Java 测试（backend/src/test/java）**：`ManagedProcessRunnerTest`（+206）、`OwnedProcessTest`（+210）、`PythonProcessManagerTest`（+31）、`ProcessTreeManagerTest`、`BashToolFailureClassificationTest`、`BrowserVerifierTest`、`DevServerLauncherTest`（+89）、`VerifyJourneyEdgeCaseTest`（+57）
- **Python（python-service/src）**：`services/browser_service.py`（+559/-…）、`routers/journey.py`、`services/journey_models.py`
- **Python 测试**：`test_browser_resource_container.py`（+215）、`test_browser_service_lifecycle.py`（+816）、`test_journey_lifecycle.py`（+225）、`test_journey_publication.py`（+1）
- 关联上下文（只读）：`RunExecutionRegistry`、`QueryEngine`、`PythonCapabilityAwareClient`、`VerifierFactory`、`ToolExecutionPipeline`、Dockerfile、docker-compose、CI workflows

### 2.2 方法

1. **静态对抗分析**：全量 diff（`e78d686..0db4b049`）+ 完整文件阅读 + 所有被改 API 调用点穷举 + 父提交行为对照。
2. **动态执行取证**（未改代码）：
   - Java 定向测试 3 轮（8 类 × 87 用例）、Java 全量 3039 用例、失败类隔离复测、父提交快照对照复测；
   - Python 定向 2 轮、Python 全量、失败用例负载/空载各 3-5 次复测；
   - Docker 实测三个镜像内 `setsid`/`bash` 存在性；容器内 `/proc/<pid>/stat` 字段解析验证。
3. **契约一致性核验**：Java 客户端反序列化层级 vs Python 响应 envelope（close_session/snapshot/journey）；deadline 时钟语义；`strict_session` 行为。
4. **协调者独立复核**：对最高严重度指控（H1 级联链、H3 行为变化、Python 容量移除）逐条重新阅读源码验证，而非直接采信子审查结论。

### 2.3 审查环境

- macOS 26.5.2 / aarch64；Amazon Corretto 21.0.10；Maven Wrapper 3.9.9；Python 3.11.15（仓库 venv，playwright 1.58.0、pytest 8.3.4、pytest-asyncio 0.24.0）；Docker Desktop 29.4.0（linux/arm64）。
- 本机有正在运行的开发服务（8080/8000/5173），审查全程未触碰、未占用。

---

## 3. 详细发现

### 3.1 P1 级

#### H1【P1】清理未确认时容量槽位与 WorkLease 无限期保留，且无任何自动回收通道 → 会话级永久不可用

**位置**
- `backend/src/main/java/com/aicodeassistant/tool/process/ManagedProcessRunner.java:147-149, 171-179, 343-350`
- `backend/src/main/java/com/aicodeassistant/run/RunExecutionRegistry.java:87-96, 258-263, 272-276, 620-631`
- `backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java:326-350, 513`

**证据（代码路径，协调者已独立复核）**

```java
// ManagedProcessRunner.java:171-179（runWithLease 的 finally）
boolean stopped = process == null || terminate(activeProcess, finalDeadline);
if (process != null) stopped = cleanup(activeProcess, finalDeadline) && stopped;
if (stopped) {
    releaseRetained(key, activeProcess);
} else {
    // Retain ownership and its capacity slot; a later cancellation can retry termination.
    log.warn("Foreground process cleanup unconfirmed: pid={}", process.pid());
}
```

```java
// ManagedProcessRunner.java:343-350 —— 只有 releaseRetained 才会归还容量与租约
private void releaseRetained(ProcessKey key, ActiveProcess process) {
    if (process.backgroundGroup() == null && process.runnerFinished().get()
            && active.remove(key, process)) {
        try { if (process.workLease() != null) process.workLease().close(); }
        finally { capacity.release(); }
    }
}
```

```java
// RunExecutionRegistry.java:258-263 —— WorkLease 未关闭（work 非空）时 Execution 不会被移除
public void unregister(String runId) {
    Execution execution = byRun.get(runId);
    if (execution != null) {
        execution.requestUnregister();
        if (execution.awaitQuiescence(Duration.ofSeconds(2))) removeExecution(execution);
    }
}
// :87-96 —— 同一 session 再次 register 直接抛错
if (previous != null && !previous.equals(runId)) { ... throw new IllegalStateException("SESSION_EXECUTION_ALREADY_REGISTERED"); }
```

**级联链（完整、静态可证）**

1. 任一 `terminate()/cleanup()` 未确认（触发源见 H2/H3 及"不可杀进程"场景）→ `releaseRetained` 不执行；
2. `capacity`（默认 16，`process.runner.max-concurrent` 可配）槽位不归还，`active` 条目常驻，`WorkLease` 不关闭；
3. 该 run 正常结束后，`QueryEngine` 收尾调用 `runExecutions.unregister(runId)`（`QueryEngine.java:513`）→ `awaitQuiescence(2s)` 因 work 永不为空而失败 → **Execution 不移除**，`runBySession` 残留旧 runId 映射；
4. 同一会话的下一次查询：`QueryEngine.java:326` `register(...)` → 抛 `SESSION_EXECUTION_ALREADY_REGISTERED` → catch 后以 `RUN_EXECUTION_REGISTRATION_FAILED` 直接结束本次查询（`:341-349`）；
5. 累计 16 次此类保留后：所有前台命令直接 `PROCESS_CAPACITY_EXCEEDED`（`ManagedProcessRunner.java:100`）。

**说明/严重度判定**：唯一的重试通道是 `cancelTargeted/cancelAll/cancelSessionBackground/shutdown`（`ManagedProcessRunner.java:127,293,321`），但一条**正常结束**的 run 之后不会再发生取消——即泄漏没有自愈路径。触发频率取决于 H2/H3 场景的实际发生率，但一旦触发，影响是"会话（会话级工作流）在进程重启前不可用"，故定 P1。

**建议修复**
1. 为保留的 `ActiveProcess` 增加带 TTL 的后台重试（reaper 定时器，例如每 30s 对 `active` 中已 `runnerFinished` 且未确认的条目重试 `terminate+cleanup`，成功后 `releaseRetained`）。
2. 容量与租约解耦：容量槽可在输出流关闭后归还，仅保留"所有权记录"用于后续重试；
3. `RunExecutionRegistry.unregister` 为 stale execution 提供强制移除路径（或在 unregister 超时后告警并允许新 run 注册，避免整会话卡死）；
4. 增加指标/日志：保留中的进程数与时长（当前只有一行 warn）。

---

#### H2【P1，条件触发】`inspected` 门控使 terminate 在"无法枚举后代"的宿主上长期无法确认，且每条命令额外 +2~4s

**位置**：`OwnedProcess.java:105-121`（`inspected` 判定）、`:150-152`（`liveMembers` 起手 `rememberDescendants`）、`:186-195`（`rememberDescendants` 不吞异常）；`ManagedProcessRunner.java:145-149, 363-370`

**证据**

```java
// OwnedProcess.java（terminate 循环）
boolean inspected = false;
do {
    List<ProcessHandle> live;
    try { live = liveMembers(); inspected = true; }
    catch (IOException | RuntimeException unavailable) {
        live = known.values().stream().filter(ProcessHandle::isAlive).toList();
        inspected = false;                       // ← 任何一次枚举失败，本轮不可确认
    }
    ...
    if (inspected && live.isEmpty() && !delegate.isAlive()) { terminated = true; return true; }
} while (true);                                  // 直到 deadline → return false
```

`liveMembers()` 首行 `rememberDescendants(delegate.toHandle())` → `parent.descendants().toList()` **不吞异常**；`ProcessHandle.descendants()` 在受限宿主抛 `RuntimeException`（父提交 `ProcessTreeManager` 旧注释原文："the primary process has to be terminated even when descendants() is unavailable (for example, macOS sandbox denies sysctl)"，且本提交把 `ProcessTreeManagerTest` 改为 `assumeTrue(false)` 变相承认该环境存在）。

**后果（限定条件）**
- 在"枚举持续被拒"的宿主上，若清理时刻存在存活/未收割成员，`terminate` 会一直无法进入确认分支，直到 deadline（body 2s + finally 再 2s）→ `terminationConfirmed=false` → BashTool 走 H5 的失败分支；每次多付 ~4s 延迟。
- 协调者复核修正：**并非"永远无法确认"**——若所有成员确实在信号后死亡，后续轮次中 `rememberDescendants` 对已死进程直接早退，不再触发异常，仍可确认。真正的"永不确认"需要成员存活到 deadline（不可杀/僵尸/权限受限）或 `/proc` 读取持续抛非 `NoSuchFileException` 异常（Linux hidepid 等）。因此该条严重度标注为**条件性 P1**：普通 macOS 开发机（本机实测 `descendants()` 正常）与官方 Linux 容器（走 /proc 分支）不触发。
- 该条件一旦成立，会与 H1 叠加（未确认 → 泄漏积累），是 H1 的触发源之一。

**建议修复**：将"枚举不可用"与"存在存活成员"分开处理——枚举失败时回退到 `known + ProcessHandle.isAlive()` 快照作为可确认依据（父提交即如此），或把 `inspected` 只用于"是否可能有未知成员"的告警而非确认前提。

---

#### H3【P1，破坏性语义变更】前台命令正常结束后无条件清理会话组残留子进程：`npm run dev &` / `nohup ... &` 被静默杀死，工具仍报成功

**位置**：`ManagedProcessRunner.java:145-149`

**证据（与父提交对照）**

```java
// 父提交：仅超时才终止
// if (!completed) terminationConfirmed = terminate(activeProcess, cleanupDeadline);
// 现在（147-148 行注释 + 无条件调用）：
// Foreground completion owns its remaining children, even after the shell has exited.
boolean terminationConfirmed = terminate(activeProcess, cleanupDeadline);
```

`terminate` → `OwnedProcess.liveMembers()`（Linux：/proc 扫描同 session+group；macOS：轮询记录的后代快照）→ 对残留成员先 `destroy()`、宽限后 `destroyForcibly()`。

**影响**
- LLM/用户常用 "先起服务再验证" 的写法（`npm run dev > log 2>&1 &`、`nohup python -m http.server &`）在工具返回时被杀死，**而工具返回 exit 0 + SUCCESS**（terminationConfirmed=true），调用方以为服务仍在运行——后续步骤（如 `curl localhost`）会以"无法解释"的方式失败。
- 这是相对父提交的**用户可见行为变化**（父提交不杀正常结束命令的子进程）。设计者意图明确（"前台即前台，长期服务请用 `is_background`"），且文档中存在 `is_background` 后台通道，但 BashTool 的工具描述未禁止 `&` 写法，模型常用该模式。
- 反向副作用：若残留进程 2s 内杀不掉（D 状态等），一条 exit-0 的正常命令会被升级判为 `PROCESS_TERMINATION_UNCONFIRMED`（见 H5）。

**建议修复（产品决策 + 工程配套）**
1. 明确产品预期：若"前台命令返回前杀死残留子进程"是设计目标，请在 BashTool 描述与系统提示中**显式禁止 `&` 起长期服务**并说明后果；若否，请恢复"仅超时/取消才强杀"的条件调用。
2. 若保留现语义：对 exit==0 的正常结束，将"残留清理结果"作为**告警 metadata** 而非硬失败（见 H5），并在输出中提示"后台子进程已被清理"。

---

### 3.2 P2 级

#### H4【P2】DevServer 未确认停止 → 句柄与 pid 文件保留、重试仅 @PreDestroy → 下次验证因端口占用直接失败

**位置**：`DevServerLauncher.java:132-139`（stop）、`:141-147`（shutdownAll）、`:43,77`（requireAvailablePort）；`VerifyJourneyTool.java:333`（固定端口）

**证据**

```java
public void stop(DevServerHandle handle) {
    if (!processTreeManager.destroyProcessTree(handle.process(), GRACE_PERIOD)) {
        activeHandles.putIfAbsent(String.valueOf(handle.pid()), handle);
        log.warn("Dev server cleanup unconfirmed: pid={}", handle.pid());
        return;                                  // pid 文件保留、句柄保留
    }
    activeHandles.remove(String.valueOf(handle.pid()), handle);
    try { Files.deleteIfExists(handle.pidFile()); } catch (IOException ignored) {}
    ...
}
```

**影响**：残留 dev server 仍占用 `baseUrl` 端口（如 vite 5173）→ 下一次验证 `requireAvailablePort` 抛 `IllegalStateException("Dev server port is already in use")` → `VERIFY_JOURNEY_FAILED`，形成"一次未确认清理 = 后续验证持续失败"，重试只发生在应用 @PreDestroy。启动失败路径 `catch (RuntimeException|Error) { stop(handle) }` 同样保留句柄。

**建议**：start 前对 `activeHandles` 中同端口残留先做一次 terminate 重试；或未确认时改用动态端口并显式告警。

---

#### H5【P2】BashTool 新失败分支的语义细节：exit-0 命令被判 `PROCESS_TERMINATION_UNCONFIRMED`（NEVER/UNKNOWN）、`descendantTrackingUnavailable` 恒 false 具误导性、`truncated` 未折算

**位置**：`BashTool.java:408-418`（前台分支）、`:477-486`（sandbox 分支）；`ToolExecutionPipeline.java:546-555`

**逐项结论**
- **判定点穷举**（terminationConfirmed=false 的来源）：① `OwnedProcess.terminate` 返回 false（H2 门控 / 不可杀进程）；② `cleanup()` 返回 false（期限用尽/hook 异常）；③ backgroundGroup 路径 terminate false。前台路径 `terminationHook==null`，`cleanup()` 在 `process!=null` 时恒 true → 实际几乎只有 ①。
- **exit-0 命令被判失败**：若正常命令留有"2s 内无法确认死亡"的子进程（D 状态/受限宿主），一条成功命令会返回 `Retryability.NEVER / EffectState.UNKNOWN` 的失败结果。**不会自动重跑命令**（`ToolExecutionPipeline.attemptBashErrorRecovery` 仅附加 hint；`isRetryable()` 对 NEVER+UNKNOWN 为 false），无双重执行副作用——此为已验证的好消息。
- **`descendantTrackingUnavailable` 误导**：该字段来自 Result 构造时刻的 `process.descendants()` 探测，与"终止过程中枚举失败"（真实原因）不是同一件事，真实路径上几乎恒为 false；测试用 mock 置 true 未验证真实语义。
- **`truncated` 未折算**：新分支未把 `stdoutTruncated/stderrTruncated` 折算进 `ToolResult.truncated`（`ToolResult.failed` 只读 `metadata["truncated"]`），下游 `outputTruncated=false`（轻微不一致）。
- 跳过 `shellStateManager.updateStateFromSnapshot(sessionId)` 无功能影响（该方法为空实现，仅 debug 日志）——已核验。

**建议**：`descendantTrackingUnavailable` 在 unconfirmed 分支重算（或改名 `cleanupPhaseEnumerationFailed`）；`truncated` 一并带入 metadata；对 exit==0 场景评估"告警而非失败"的降级语义。

---

#### H6【P2】Python：关闭失败的 context 永不重试却永久占用 max_sessions 配额并封锁 session_id；"TTL 兜底"在异常场景不成立

**位置**：`python-service/src/services/browser_service.py:324-357`（`_close_resource` + finished 回调）、`:359-373`（`_close_context`）、`:406-412`（`_create_session` 容量/封锁检查）、`:582-597`（TTL 清理）

**证据**
- 完成回调仅在**成功**时移除 close task：`succeeded = not completed.cancelled() and completed.exception() is None; if succeeded ...: pop`（`:338-340`）→ 已失败任务被后续所有关闭路径复用，`task.result()` 反复抛同一异常，**不会再发起一次 close RPC**（业务上正确，见下）。
- 失败时 context 记入 `_unclosed_contexts`（`:370`），容量公式计入：`len(self._sessions) + len(self._creating) + len(self._unclosed_contexts) >= self.max_sessions`（`:411-412`）；且 `session_id in self._unclosed_contexts.values()` → 抛 "has unfinished cleanup"（`:408-409`）**永久封锁该 session_id**。
- TTL 清理（每 60s）对"异常型失败"是空转；只有 browser/driver 级释放才真正回收 → **进程重启前不会恢复**。

**影响**：每次 close 异常 ⇒ 泄漏 1 个真实 Chromium context + 永久占 1/10 容量 → 累计 10 次后**所有新建会话失败**（含 journey 会话，本提交新引入容量检查），Java 侧显示为 `PYTHON_CALL_FAILED / "Python service unreachable or timeout"`；交互式链路中该 session_id 后续所有 WebBrowser 动作报 `already exists or is closing`。Java 注释 "TTL remains a fallback"（`VerifyJourneyTool.java:383`）对 timeout 型失败成立、对**异常型失败不成立**。

**注意（反向验证）**：worker 核实 playwright 1.58 `BrowserContext.close()` 先置 latch 再发 RPC（`_closing_or_closed=True`），"重试 close"是假成功——因此修复方向不应是重试，而是**升级回收**（browser 级 close+relaunch，复用 `_release_browser_ownership` 语义），并在 health/capabilities 暴露 `unclosed_contexts` 计数（当前不可观测）。

---

#### H7【P2】Python：容量策略从"驱逐最旧"改为"硬拒绝"，journey 路径首次出现容量上限；拒绝以裸 500 暴露 → Java 报误导性 "unreachable or timeout"

**位置**：`browser_service.py:393-412`（`_create_session` 抛 `RuntimeError("Browser session capacity reached")`）；`journey.py:66-70`（无异常包装）

**证据（与父提交对照）**
- 旧 `get_or_create_session`：达到上限时 `oldest_sid = min(...); await self._close_session_unsafe(oldest_sid)` —— **驱逐最旧**后继续创建（diff 删除段）。
- 旧 `_create_context_for_journey`：**完全没有容量检查**。
- 新实现两处都变拒绝；journey 路径 `RuntimeError` 无人捕获 → uvicorn `except BaseException → send_500_response` → 纯文本 500 → Java `callIfAvailable` 得 `Optional.empty()` → `JourneyResult.failed("PYTHON_CALL_FAILED", "Python service unreachable or timeout")`（结论错误且不可诊断）。
- 与 H6 叠加：容量被泄漏后 journey 会以不可诊断方式持续失败 → 升级为服务级不可用。

**建议**：容量拒绝返回可识别错误（如 `HTTPException(503, "JOURNEY_CAPACITY_REACHED")`）；保留"只驱逐 idle 且无 owner lease 的会话"作为兜底，避免多 chat session 场景硬失败；健康检查暴露当前会话数。

---

#### H8【P2】PythonProcessManager：未确认即置 FAILED 并保留 processRef；`stop()` 无返回值；关停最长阻塞 12s；重试必然失败仅刷日志

**位置**：`PythonProcessManager.java:126-137, 181-205, 360-366`

**证据与结论**
- `stop()`：未确认 → `stateRef.set(FAILED); return;`（不再置 STOPPED，processRef 保留）——语义可辩护（保留所有权），但 `stop()` 是 void，调用方无法区分"已停/未停"；JVM 关闭后可能留下孤儿 Python 进程占端口。
- `stopProcess`：`owned.terminate(now+12s, 10_000, gracefulRootFirst=true)` → grace 内只给 root 发 SIGTERM，成员一律不发信号；若 root 已死但有遗留成员，会**空等满 10s** 才强杀，最坏 12s；`stop()/restart()/@PreDestroy` 均 synchronized，健康检查会排队。
- `start()` 若旧代停止失败直接 FAILED+return false → 上层 `startWithRetry()` 重试 3 次、每次 sleep 5s，但每次必然同样失败（没有新手段），仅浪费 15s 并刷日志。
- 影响面：本仓库内 `PythonProcessManager` **无外部 Java 调用方**（仅自身生命周期；auto-start 默认 false），故降为 P2。
- `restart()` 新增 `if (processRef.get() != null) return false;` 逻辑正确；`catch (Error)` 保留原始 Error；中断处理正确。

**建议**：`stop()` 返回 boolean 或暴露 lastStopConfirmed；grace 内"root 已死"时立即允许对成员发 TERM。

---

#### H9【P2】测试门禁缺口：静默 skip / CI 恒 skip / 私有字段耦合 / 时序 flaky

见 [第 6 节](#6-测试覆盖与执行验证) 详述。要点：
1. `ProcessTreeManagerTest:74-92` 在无法枚举子进程的宿主用 `Assumptions.assumeTrue(false, ...)` **静默跳过核心断言**（"子进程也被终止"从未在受限宿主被验证），覆盖率被高估；
2. `test_browser_resource_container.py:29` 需 `ZHIKUN_BROWSER_CONTAINER_TEST=1`（隔离 Linux 容器），CI 未设置 → **唯一真实 Playwright 资源收敛测试在 CI 恒 skip**，无门禁；
3. `test_browser_service_lifecycle.py:405-452` 直接断言 Playwright 私有字段（`DriverContext._closing_or_closed`、`context._request.dispose`、`_exit_was_called` 等），SDK 升级即静默失效；
4. 若干新用例为时序/负载敏感：本机已复现负载下 `ManagedProcessRunnerTest` 3 个失败（隔离/空载通过）、Python 新用例 `cleanup_timeout=0.05` 窗口在负载下失败（空载 5/5 通过）。

---

### 3.3 P3 级

#### H10【P3】journey deadline 504/499 无 Python 侧日志、无部分结果

`journey.py:37,48-49`（504/499）/ `:50-57`（finally 清理）。deadline 到期只回 504、丢弃已完成步骤结果（Java 无部分结果契约，可接受）；但**整个路由无任何 logger 记录**（elapsed/session/已完成步数），排障只能靠 Java 侧。建议在 504/499 分支补 warning 日志。

#### H11【P3】close 与"创建中"并发 → CancelledError 逃逸 → 裸 500；语义应为 409/410

`browser_service.py:498-500`（对 `_creating` 中的 reservation `reservation.owner.cancel()`）、`:420-427`（waiter 复抛 `pending.error`）。owner 即 FastAPI handler 任务，CancelledError 不是 `except Exception`，一路逃逸到 uvicorn → 500 噪音；waiter 也会被传染 CancelledError。建议改为自定义异常（`SessionClosedDuringCreation`）并由路由映射为 409/410。触发面窄（WebBrowserTool 串行；DomSnapshotClient 与 close 的并发窗口存在）。

#### H12【P3】UserJourneyVerifier 与新的 130s/唯一资源 ID 协议不一致（当前不可达，属潜在坑）

`UserJourneyVerifier.java:19,36` 仍用 120s HTTP 超时 + `"rv-" + req.sessionId()`。核验：`VerifierFactory` 只返回 `browserVerifier/httpApiVerifier`，该 bean 当前不可达（`VerifyJourneyTool.java:24` 仅剩一个未用 import）。若未来重新启用：120s 会先于"120s 工作 + ≤5s 清理"的服务预算超时；`"rv-"+sessionId` 会命中新 `_create_session` 的 "already exists" 拒绝。建议删除该 bean 或同步改为 `req.browserResourceId()` 与新超时；删掉死 import。

#### H13【P3】规范性打包问题

- **CHANGELOG 未更新**：仓库惯例（`e78d686`、`698299a` 等 fix/feat 均同步更新 `CHANGELOG.md [Unreleased]`）；本提交改变了用户可见行为（残留子进程清理、新失败码、生命周期语义），应补条目；
- **docs 未同步**：新增 `deadline_epoch_ms`、504/499、close 语义等接口契约未见于任何文档（未逐一确认必须更新的页面）；
- **中英注释混用**：仓库以中文注释为主，本提交新增行几乎全英文（browser_service.py:375-521、journey.py:15-62）；
- **死代码/冗余**：`ManagedProcessRunner.java:368` 的 `return false`（"legacy"分支仅测试可达）；`VerifyJourneyTool.java:24` 未用 import；
- **`_unclosed_contexts` label 双语义**：`:476` 写裸 session_id、`:528` 写 `f"session {session_id}"`，同一字段承担"日志名"与"按 id 反查"两职责，仅一半路径可被 `values()` 命中（功能靠 `closing` 兜底，但机制不统一）；
- **`journey.py:66` 兜底资源 ID 仅 32bit**（`uuid4().hex[:8]`），Java 现在总是传唯一 ID，该分支属 legacy；
- **时延预算余量收窄**：VerifyJourneyTool 600s 预算下最坏 ≈557s（npm install 300s + ready 120s + journey 130s + snapshot 2s + close 5s），余量从 ~53s 缩至 ~43s，建议显式对齐常量或加校验注释；
- **性能小项**：每次 terminate 全量扫描 /proc（示例 500 进程 ≈ 每轮 500+ 次 stat），`known` 只增不减；
- **`_resource_close_tasks` 以动态 callable 为键**（lambda/bound method），语义正确但可读性差，建议显式键；
- **`http_request: Request = None`**：mypy 会报；建议 `Optional[Request]`（运行时实测注入有效）。

#### H14【P3】Python 进程内浏览器能力"不可恢复"状态仅日志、未暴露

`browser_service.py:188-192`：shutdown 后若 driver `stop()` 失败（latch 已置位重试无意义），`_playwright`/`_resource_close_tasks` 保留 → 同进程内后续 `startup()` 永远抛 "unreleased resources"。设计可辩护，但意味着**必须重启 Python 进程**才能恢复浏览器能力，且当前只有日志、health/capabilities 不暴露该状态。建议在 `/api/health/capabilities` 暴露 `browser_resources_unreleased` 标志。

---

### 3.4 已确认无问题清单（对抗性验证后）

| # | 结论 | 证据 |
|---|---|---|
| 1 | **生产镜像不缺 setsid/bash**（部署阻断排除）| docker 实测：`eclipse-temurin:21-jre-noble`(Ubuntu 24.04.4)、`zhikuncode:meoo-review`、`zhikuncode:latest` 均存在 `/usr/bin/setsid`(util-linux 2.39.3) + `/bin/bash` |
| 2 | `/proc/<pid>/stat` 字段映射正确（pgrp/session/starttime/state），`lastIndexOf(')')` 解析健壮 | 容器内逐列实测 + 代码复核（`OwnedProcess.java:215-221`） |
| 3 | Java `close_session` 读 `success` 的层级与 Python envelope 一致（`BrowserResponse.success` 顶层） | `PythonCapabilityAwareClient.java:274-277`、`routers/browser.py:254-262` |
| 4 | `strict_session:true` 不创建幻影会话，SESSION_NOT_FOUND 时 Java 静默降级 | `browser_service.py:558-568`、`VerifyJourneyTool.java:756-778` |
| 5 | 失败快照(RV-5)→close 顺序正确（快照在 handleVerificationResult 内、close 在 finally） | `VerifyJourneyTool.java:346-348,373-387`、`VerifyJourneyEdgeCaseTest` |
| 6 | `deadline_epoch_ms` 双方墙钟语义一致；过期入口直接 504 不建资源 | `BrowserVerifier.java:20-24,44`、`journey.py:36-37` |
| 7 | journey 正常成功路径无回归（record/viewport/步骤/artifacts 原样；新增开销仅 1 个 0.25s 轮询协程） | `_execute_journey` 未改；`journey.py` diff 仅函数头 |
| 8 | 交互式共享 helper 行为等价（16 个端点签名/响应未变；strict/non-strict 自动创建语义未破坏） | Python worker 逐 helper 比对 |
| 9 | 清理错误不掩盖主错误；取消安全（`_finish_cleanup` 屏蔽一次取消后重抛） | `journey.py:74-85`、`browser_service.py:306-321`、对应测试 |
| 10 | Java 侧 `ProcessKey.trackable()` 删除安全（Request 构造强制 runId/toolUseId 非空） | `ManagedProcessRunner.java:496-516` + 全调用点核查 |
| 11 | `putIfAbsent` 冲突路径 capacity 恰释放一次；stale 回调不误删新条目 | `ManagedProcessRunner.java:106-109,343-350` + 测试 |
| 12 | backgroundGroup 条目不被 `releaseRetained` 误释放 | `:170-190,226-233,343-346` |
| 13 | 中断保存/恢复正确（ManagedProcessRunner/OwnedProcess/DevServerLauncher 三处） | 代码 + `DevServerLauncherTest.interruptedInstall...` |
| 14 | 新失败分支**不会自动重跑命令**（无双重副作用） | `ToolExecutionPipeline.java:563-595`、`ToolResult.java:132-135` |
| 15 | macOS 开发机 `ProcessHandle.descendants()` 实测正常；非 Linux 分支不依赖 setsid | 本机 Corretto 21 探针 |
| 16 | TTL 兜底真实存在（journey 会话 release 后可过期；运行中 journey 被 owner 保护） | `browser_service.py:115-116,571-597` + 测试 |
| 17 | 同一 session 并发 journey 被显式拒绝；不同 id 互不阻塞；Playwright I/O 全在锁外 | Python worker 逐路径核验 |
| 18 | `asyncio.wait` 只收 Task、`Task.cancelling()` 需 3.11+ —— 与 `requires-python>=3.11,<3.13` 一致 | venv/CI 均 3.11 |

---

## 4. 正常功能链路逐链路影响分析

### 4.1 前台 Bash 命令执行（核心链路）

- **普通命令（成功/失败）**：无回归。`descendantsUnavailable` 探测、输出 drain、退出码、失败分类保持；`awaitDrain` 提前返回改动更精确。
- **残留后台子进程**：行为变化（H3）——工具返回时清理杀死；`&`/nohup 类写法语义被改变，且工具仍报成功。
- **超时/取消**：终止逻辑改为 `OwnedProcess.terminate`（组级、有 deadline），方向正确；受限宿主上可能未确认（H2）。
- **所有权/重复请求**：`PROCESS_OWNERSHIP_CONFLICT` 语义更严格且更早（启动前预留）；原"未跟踪"路径现永远跟踪，但生产调用点全部提供非空 runId/toolUseId（已核验）。
- **平台差异**：Linux（Docker/CI）走 setsid+/proc 路径（能力最强）；macOS 走 `descendants()` 轮询快照（能力为 best-effort，注释已声明）。
- **容量**：default 16；未确认清理时槽位不归还（H1 关键）。

### 4.2 后台命令通道（`is_background`）

未受影响：diff 未触及 `runBackground/adopt/BackgroundProcessGroup` 语义；容量由 `group.exited` 回调释放（`:226-233`）。

### 4.3 Python 服务生命周期（PythonProcessManager）

- 正常启动/停止：语义等价（`OwnedProcess` 包装在 macOS 为直启、Linux 加 setsid 握手，导入时延正常 <10ms）+ 最多 2s 身份校验窗口。
- 异常路径：未确认 → FAILED + 保留引用；`stop()` void 无法反馈；关停最长 12s；重试必然失败（H8）。
- 影响面有限（无外部调用方、auto-start 默认 false）。

### 4.4 DevServer + 浏览器 journey 验证

- **成功路径无回归**（已验证 5/6/7/8 项）；资源隔离改进真实有效：每次验证独立 `rv-<uuid>`，并发验证互不干扰（此前同 sessionId 验证会复用同一资源 ID）。
- **清理未确认后的持续性影响**：DevServer 端口占用（H4）+ Python context 泄漏（H6）。
- **HTTP API 模式**：`browserResourceId=null`，跳过失败快照与浏览器清理，行为与父提交一致（已核验）。

### 4.5 应用启动/关停（shutdown hook）

`DevServerLauncher.shutdownAll` 会重试全部残留句柄；`PythonProcessManager.@PreDestroy` 最长阻塞 12s；若未确认停止，可能留下孤儿 Python 进程占端口（H8）。整体为"更安全但更慢"的权衡。

### 4.6 交互式浏览器（WebBrowser/DomSnapshot 共享服务）

正常路径等价（已验证 8）；边缘风险：关闭失败永久封锁 session_id（H6）、创建期 close 竞态 500（H11）、容量硬拒绝语义（H7）。

### 4.7 其他

前端、其他工具、ASR 等均不涉及本次提交代码路径。

---

## 5. 代码质量与规范性评估

### 5.1 优点

1. **设计意图与权衡记录清晰**：`OwnedProcess` 类注释（"lifecycle ownership，非安全沙箱"）、`ManagedProcessRunner` 注释（"Foreground completion owns its remaining children"）、`BrowserVerifier` 超时注释（"120s work + bounded cleanup/transport headroom"）均把 Why 写清。
2. **错误与中断处理认真**：主异常 + `addSuppressed`、`Thread.interrupted()` 保存/恢复、Error 重抛、清理失败不掩盖主错误——4 项声明中的"preserve interruption and primary failures"高置信度成立。
3. **PID 复用防护扎实**：starttime 校验、`groupRetired`、双重 `isAlive` 校验、信号前复核身份；字段解析实测正确。
4. **Python 侧并发处理成熟**：单事件循环 + 双锁状态机、I/O 在锁外、close task 共享、owner lease 保护 TTL 不误杀运行中 journey。
5. **契约意识**：Java/Python 两端对 close/snapshot/deadline/strict_session 的对接逐一验证通过。
6. **测试断言质量高**（多数）：行为断言、真实进程、`touch should-not-exist` 证明未执行、并发清理唯一性断言等。

### 5.2 问题（对应 Findings）

- **设计缺口**：保留所有权却没有回收通道（H1）——"bounded lifecycle"声明在此不成立；
- **条件盲区**：`inspected` 门控把"枚举能力"当作"确认前提"（H2），与父提交专门处理过的环境相矛盾；
- **语义变更未配套**：`&` 行为变化（H3）无文档/prompt 配套；新失败码文案未解释"子进程已被/未能清理"；
- **可观测性**：Python 504/499 无日志（H10）；`_unclosed_contexts`、容量水位、未释放浏览器资源均未暴露 health（H6/H14）；
- **一致性**：`descendantTrackingUnavailable` 字段与真实语义不符（H5）；UserJourneyVerifier 协议不一致（H12）；label 双语义（H13）；
- **规范性**：CHANGELOG/docs 未更新、中英混用、死代码、`assumeTrue(false)` 反模式（H13/H9）。

### 5.3 量化小结

| 维度 | 评价 |
|---|---|
| 正确性（成功路径） | 高（未发现回归，契约闭环） |
| 正确性（异常/清理路径） | 中（H1/H2/H6 的"未确认"分支存在级联缺口） |
| 可维护性 | 中上（注释好；但状态机复杂、部分字段语义漂移） |
| 可观测性 | 中下（多处失败路径无日志/无指标） |
| 规范性 | 中（CHANGELOG/docs/注释语言/死代码） |

---

## 6. 测试覆盖与执行验证

### 6.1 新增测试规模与质量

- Java 8 类：`ManagedProcessRunnerTest`(+206)、`OwnedProcessTest`(+210)、`PythonProcessManagerTest`(+31)、`ProcessTreeManagerTest`、`BashToolFailureClassificationTest`(+16)、`BrowserVerifierTest`(+23)、`DevServerLauncherTest`(+89)、`VerifyJourneyEdgeCaseTest`(+57)。
- Python 3 文件：`test_browser_service_lifecycle.py`(+816)、`test_journey_lifecycle.py`(+225)、`test_browser_resource_container.py`(+215) + `test_journey_publication.py`(±1)。
- **亮点**：真实进程用例（顽固子进程、root 优雅关闭 trap、跨 session 浏览器式子进程）、竞态用例（mockStatic 在 start 内触发取消）、并发清理唯一性（`maximumCleaners==1`）、事件驱动而非 sleep 的 Python 生命周期测试（多数）。
- **缺口**：见 H9 + 下节"未覆盖路径"。

### 6.2 执行结果（本机实测，未改代码）

**Java 定向（8 类 × 87 用例，3 轮）**

| 轮次 | 结果 | 备注 |
|---|---|---|
| 第 1 次 | 87 run / **3 failed** / 6 skipped | 3 个失败全在 `ManagedProcessRunnerTest`（时序断言）；当时与 Docker 探测并发、负载高 |
| 隔离重跑 | 17/17 通过 | 3 个失败未复现 |
| 第 2 次 | 87 run / **0 failed** / 6 skipped / BUILD SUCCESS | 6 skip = `OwnedProcessTest` 的 `@EnabledOnOs(LINUX)` 用例（macOS 跳过） |

**Java 全量（3039 用例，8m58s）**：14 failed / 76 skipped。归因：**11 个确定性失败（4 个类）在父提交 HEAD~1 快照上用同一命令逐一复现（既有问题，与本提交无关）**；3 个负载敏感失败（ConcurrencyControlTest×2、ManagedProcessRunnerTest×1）空载复测通过。

**Python 定向（4 文件）**：65 passed / 1 skipped（容器专用用例），两轮结果完全一致。

**Python 全量**：2 failed / 227 passed / 1 skipped（与 Java 全量并发）。失败 1 = 本提交新用例（`cleanup_timeout=0.05` 的负载敏感窗口，空载 5/5 通过）；失败 2 = 既有 `test_token_estimation` 的 500ms 性能断言（空载通过）。**新增用例存在负载下 flaky 风险**。

### 6.3 CI 接入核查

- `ci.yml`（push main / PR）：ubuntu-latest 跑**全量** Java 测试（8 个新类均含；Linux 下 `OwnedProcessTest` 6 个用例不再跳过 → setsid 路径真实覆盖）与全量 Python（4 个新文件均收集；无需浏览器二进制）；
- `task3-5-regression.yml`：因 `browser_service.py` 命中路径会触发，Python 全量会跑新测试；但 Java 只跑 2 个无关类；
- `security.yml` 与本次无关；
- **判定**：新测试已接入 CI、无"环境缺失必然失败"依赖；主要残余风险是时序敏感用例在共享 runner 上的 flaky（H9）。

### 6.4 未覆盖的高风险路径（清单）

1. `PROCESS_GROUP_UNAVAILABLE` 全部分支（setsid 缺失、非 PIPE stdin、2s 身份校验超时）——无测试（部署风险已由 Docker 实测缓解，但代码分支仍无覆盖）；
2. `terminate=false 且 cleanup=true` 的 finally 保留路径（= H1 泄漏点）无直接断言；
3. `groupRetired` 真实 PID 复用仅 mock 模拟；
4. BashTool sandbox 分支的 `PROCESS_TERMINATION_UNCONFIRMED`（`:477-486`）无测试；
5. DevServer 未确认 → 端口占用 → 后续验证失败链路无测试（H4）；
6. Python 真实 `Request.is_disconnected` 轮询路径无测试（用 `SimpleNamespace` 模拟）；
7. "容量耗尽时 journey 路由如何响应"无路由级测试（H7）；
8. F1（H6）的"异常型关闭失败"对业务的长期影响被测试**固化为期望行为**（`test_failed_creation_cleanup_retains_capacity_until_browser_shutdown`），无人讨论其产品后果；
9. `PythonProcessManager.catch(Error)`、drain、grace 空等行为无测试；
10. 容器级资源收敛测试仅在手动条件下运行（H9）。

---

## 7. 部署环境核验

### 7.1 setsid（最高优先级部署疑点 → 已排除）

| 镜像 | 系统 | setsid | bash | 结论 |
|---|---|---|---|---|
| `eclipse-temurin:21-jre-noble`（生产基础镜像） | Ubuntu 24.04.4 LTS | ✅ `/usr/bin/setsid`（util-linux 2.39.3 核心包，**非** util-linux-extra） | ✅ `/bin/bash` | 通过 |
| `zhikuncode:meoo-review`（本仓库构建、24.04 形态） | Ubuntu 24.04.4 | ✅ | ✅ | 通过 |
| `zhikuncode:latest`（旧 22.04 形态） | Ubuntu 22.04.5 | ✅ | ✅ | 通过 |

Dockerfile runtime 阶段虽未显式安装 `util-linux`，但 Ubuntu 基础镜像自带；`docker-compose.yml`/`docker-entrypoint.sh` 无相关删减。**生产不会触发 `PROCESS_GROUP_UNAVAILABLE`**。

### 7.2 其他环境核验

- `/proc/<pid>/stat` 字段映射（pgrp=第5列/session=第6列/starttime=第22列）在容器内实测与代码一致；
- 时钟：Java `System.currentTimeMillis()` 与 Python `time.time()` 同为墙钟，`deadline_epoch_ms` 语义一致；跨主机部署需注意时钟偏移（注释已声明假设）；
- macOS 开发机：`ProcessHandle.descendants()` 正常（H2 仅条件触发）；
- 本机另有运行中的开发服务（8080/8000/5173），审查全程未触碰。

---

## 8. 提交信息四项声明核验

| 声明 | 判定 | 说明 |
|---|---|---|
| "Retain process ownership through cleanup" | ✅ 成立（有副作用） | `releaseRetained`/`processRef` 保留/DevServer 句柄保留均实现；**但保留后无回收通道（H1）** |
| "guard descendant discovery against PID reuse" | ✅ 成立 | starttime 校验 + `groupRetired` + 双重 isAlive + 信号前复核；字段解析实测正确；仅剩极窄 TOCTOU 窗口（低风险） |
| "preserve interruption and primary failures" | ✅ 成立 | 三处中断保存/恢复；`addSuppressed`；Error 重抛；对应测试存在 |
| "Bound browser and journey resource lifecycles with cancellation-safe cleanup and regression coverage" | ⚠️ 部分成立 | deadline/TTL/取消安全均实现且正常路径无回归；但 (a) 未确认保留无 TTL（H1）；(b) Python 关闭失败永久占容量（H6）；(c) 覆盖存在门禁缺口（H9） |

---

## 9. 改进建议（按优先级）

### 立即（发布前，P1）

1. **H1 回收通道**：为 `active` 中未确认条目增加 TTL 后台重试（reaper）；容量与租约解耦；`unregister` 提供 stale 兜底。**这是唯一能让会话整体不可用的缺陷。**
2. **H2 门控修正**：枚举不可用时回退 `known + isAlive` 快照作为可确认依据（或降级为告警而非不可确认）。
3. **H3 语义决策**：确认"前台返回即杀残留子进程"是否为产品目标——若是，更新 BashTool 描述/prompt 明确禁止 `&` 写法并提示后果；若否，恢复条件调用；同时把 exit==0 的清理结果降级为告警 metadata。

### 短期（下一个小版本，P2）

4. H4：DevServer start 前重试同端口残留句柄；未确认时告警可操作化。
5. H6：`_unclosed_contexts` 走浏览器级回收；health 暴露计数；修正 "TTL remains a fallback" 注释适用范围。
6. H7：容量拒绝返回可识别 503 错误码；journey 路由捕获并映射；保留 idle-only 驱逐兜底。
7. H8：`stop()` 返回状态；grace 内 root 已死时立即对成员发 TERM；`startWithRetry` 对"旧代停止失败"快速失败而非 3×5s 空转。
8. H9：把容器资源收敛测试接入 CI（至少 nightly + docker）；`ProcessTreeManagerTest` 改为显式 `@EnabledOnOs(LINUX)` + /proc 断言；为 H1/H4/H7 补链路测试；将新用例的时序窗口调宽或改为事件驱动。

### 工程性收尾（P3）

9. H10-H14：504/499 日志、close-vs-creating 竞态映射 409/410、删除/修正 `UserJourneyVerifier`、补 CHANGELOG 与 docs、统一注释语言、清理死代码、`truncated` 折算、预算常量对齐、health 暴露未释放资源。

### 运维缓解（若暂不修复）

- 监控进程数：前台进程 capacity 水位、Python `unclosed_contexts`、DevServer `activeHandles`；
- 制定"异常后重启对应服务"的 Runbook（H1/H6/H14 的最终恢复手段都是重启进程）。

---

## 10. 未验证事项与审查局限性

1. **平台**：动态执行在 macOS 完成；Java 全量测试的 Linux 行为未在本机复跑（CI 将覆盖；Docker 已验证 setsid/字段解析关键前置）。
2. **条件场景未复现**：H1 的端到端表现（需构造不可杀进程 + 完整 query 流程）；H2 的受限宿主（本机 macOS `descendants()` 正常）；真实 PID 复用；hidepid/seccomp 容器行为。
3. **真实浏览器**：唯一真实 Playwright 资源收敛测试（`ZHIKUN_BROWSER_CONTAINER_TEST=1`）未在本次审查运行；`ZHIKUN_REAL_BROWSER_PROCESS_TEST` 亦未运行。
4. **概率性判断**：H6/H7 的实际触发频率（生产 close 异常率、并发会话数）未统计——严重度基于"发生时影响"判定。
5. **130s/125s 边界**：真实负载下 Python 是否总能在 Java 130s 内上报，静态分析成立但未动态压测。
6. **既有失败**：Java 全量的 11 个确定性失败与 token_estimation 性能断言已在父提交复现（非本提交引入），但会让本机全量 `mvn test` 始终 BUILD FAILURE，建议单独处理。

---

## 11. 附录：证据留存

- **完整原始证据（审查过程产物，位于仓库 gitignore 范围的 scratchpad）**：
  - Java 深度审查（289 行）：`backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098/java_review_findings.md`
  - Python 深度审查：`backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098/python_review_findings.md`
  - 测试执行证据（554 行，含全部命令与原始输出）：`backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098/test_execution_evidence.md`
- **关键命令摘要**：
  - `git diff e78d686 0db4b049 -- backend/src python-service/`（改动全量）
  - `cd backend && ./mvnw -Dtest='PythonProcessManagerTest,...' test`（定向 3 轮）；`./mvnw test -B`（全量）
  - `cd python-service && .venv/bin/python -m pytest tests/... -q -p no:cacheprovider`（定向 2 轮 + 全量）
  - `docker run --rm --entrypoint sh eclipse-temurin:21-jre-noble -c 'command -v setsid; ...'`（部署核验）
  - 父提交对照：`git archive HEAD~1 | tar -x -C /tmp/zhikun_parent`（隔离复测既有失败）
- **审查清洁性**：审查前后 `git status --short` 均为空；未修改任何源码/配置/测试；仅新增构建产物（`backend/target/` 等，已被 gitignore）。

---

*报告完。本报告基于提交 `0db4b0498f6cf886f862fe293883256b1c52e049` 的静态与动态证据独立出具，不代表修复建议已被采纳或验证。*
