# 独立对抗式审查 — 0db4b049 "fix: close process and browser lifecycle leaks"

- 仓库：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`（HEAD = 0db4b04，父 = e78d686，工作树干净）
- 审查方式：只读静态分析 + 轻量只读命令（`git diff`、`grep`、`docker run`(镜像探测)、macOS 上 `java` 单文件探针）。未修改任何仓库文件。
- 结论概览：核心机制（setsid 会话归属 + /proc 身份校验 + 保留所有权）方向正确，**官方容器镜像不缺 setsid（已实测）**；主要风险集中在
  (1) "清理未确认"分支的**容量槽/WorkLease 泄漏无自动回收 → 会话被永久卡死**，
  (2) `inspected` 门控在无法枚举后代的主机上导致**永远无法确认**（父提交明确处理过该情形），
  (3) 前台命令正常结束后**无条件杀残留子进程**（`npm run dev &` 被静默杀死且工具报成功），
  (4) DevServer 残留导致后续验证端口冲突失败。

---

## 一、发现清单

### F1 [P1] 未确认清理时容量槽 + WorkLease 无限期保留，且无任何自动重试 → 同会话后续 Run 永久失败
**位置**：`ManagedProcessRunner.java:171-179`、`343-350`；`RunExecutionRegistry.java:625-658（release/awaitQuiescence）`、`256-263（unregister）`；`QueryEngine.java:326-350`、`513`

**证据（代码）**
```java
// ManagedProcessRunner.java:171-179 （finally）
boolean stopped = process == null || terminate(activeProcess, finalDeadline);
if (process != null) stopped = cleanup(activeProcess, finalDeadline) && stopped;
if (stopped) { releaseRetained(key, activeProcess); }
else { log.warn("Foreground process cleanup unconfirmed: pid={}", process.pid()); }   // ← 不释放
// ManagedProcessRunner.java:343-350
private void releaseRetained(ProcessKey key, ActiveProcess process) {
    if (process.backgroundGroup() == null && process.runnerFinished().get()
            && active.remove(key, process)) {
        try { if (process.workLease() != null) process.workLease().close(); }
        finally { capacity.release(); }
    }
}
```
`cancel/cancelRunDetailed/cancelSessionBackground/shutdown` 是唯一的重试入口（注释也承认 "a later cancellation can retry termination"）。**一条正常结束的 run 之后不会再发生任何取消**，因此该 lease 永远不会被关闭。

**后果链（静态可证）**
1. `active` 条目常驻 + `capacity` 槽不归还 → 累计 16 次（默认 `process.runner.max-concurrent:16`）后所有前台命令抛 `PROCESS_CAPACITY_EXCEEDED`。
2. `WorkLease` 未关闭 → `RunExecutionRegistry.Execution.work` 非空 → `awaitQuiescence` 永远 false → `unregister(runId)` **不执行** `removeExecution` → `runBySession` 仍把该 session 映射到旧 runId。
3. 同一会话的下一次查询：`QueryEngine.java:326` `runExecutions.register(...)` → `RunExecutionRegistry:88-96` 抛 `SESSION_EXECUTION_ALREADY_REGISTERED` → QueryEngine 的 catch（`326-350`）以 `RUN_EXECUTION_REGISTRATION_FAILED` 直接结束本次查询。**该会话在重启前无法再运行任何查询。**
4. `RunTerminationCoordinator.terminate`（`57-78`）同样以 `awaitQuiescence==false` 判定 `TOOL_TERMINATION_UNCONFIRMED`（fail run）。

**触发条件**：任何 `terminate()` 返回 false 的场景（见 F2/F3）。
**建议**：为保留的 ActiveProcess 增加带 TTL 的后台重试（或定时 reaper，例如 30s 重试 terminate+cleanup）；把 capacity 与 lease 解耦（容量可在关闭流后归还，仅保留所有权记录）；`unregister` 对 stale execution 提供强制移除路径。

---

### F2 [P1] `inspected` 门控使 terminate 在"无法枚举后代"的主机上**永远返回 false**（父提交明确处理过该环境）
**位置**：`OwnedProcess.java:98-130（terminated 条件 121 行、inspected 105/109 行）、150-152（liveMembers 起手 rememberDescendants）、186-195`；`ManagedProcessRunner.java:364-370`

**证据（代码）**
```java
// OwnedProcess.java:105-121
boolean inspected = false;
do {
    List<ProcessHandle> live;
    try { live = liveMembers(); inspected = true; }
    catch (IOException | RuntimeException unavailable) {
        live = known.values().stream().filter(ProcessHandle::isAlive).toList();
        inspected = false;            // ← 任何一次枚举失败，本次 terminate 永不确认
    }
    ...
    if (inspected && live.isEmpty() && !delegate.isAlive()) { terminated = true; return true; }
    ...
} while (true);   // 直到 deadlineNanos → return false
```
`liveMembers()` 首行调用 `rememberDescendants(delegate.toHandle())`（`OwnedProcess.java:151`），后者直接调用 `parent.descendants().toList()`（`OwnedProcess.java:190`）且**不吞异常**；`ProcessHandle.descendants()` 在被拒的主机会抛 RuntimeException（父提交 `ProcessTreeManager` 的旧注释原文：*"the primary process still has to be terminated even when descendants() is unavailable (for example, macOS sandbox denies sysctl)"*，且本提交把 `ProcessTreeManagerTest` 改成 `Assumptions.assumeTrue(false, "Host cannot enumerate children")` 隐式承认存在这类主机）。

**后果（此类主机）**
- 进程若在 terminate 时刻仍存活（超时、取消、`sleep &` 残留）：SIGTERM/SIGKILL 仍会发出，但**永远不返回 true** → 结果 `terminationConfirmed=false` → BashTool 走 F4 分支/超时分支 EffectState.PARTIAL → `RunTerminationCoordinator` 判 `PROCESS_TERMINATION_UNCONFIRMED` → 叠加 F1 的容量与 lease 泄漏。
- 每次 `terminate` 空转到 deadline：body 2s + finally 再 2s（`ManagedProcessRunner.java:145,170`）→ 每条此类命令多 4s 延迟。
- 我本机 macOS（Corretto 21）实测 `ProcessHandle.descendants()` **正常**（见"已验证"节），所以这不是平台必然，而是环境相关；官方 Linux 容器走 /proc 分支，风险最低。

**建议**：把"枚举不可用"与"存在存活成员"分开处理——枚举不可用时以 `known` + `ProcessHandle.isAlive()` 作为可确认依据（父提交即如此），或把 `inspected` 仅用于"是否有未知成员"的严格判定而不作为确认前提。

---

### F3 [P1] 前台命令**正常结束**后无条件清理残留子进程：`npm run dev &` / `nohup ... &` 被静默杀死，工具仍报成功
**位置**：`ManagedProcessRunner.java:147-149`；`OwnedProcess.java:98-130`

**证据（diff）**
```java
// 父提交：if (!completed) terminationConfirmed = terminate(activeProcess, cleanupDeadline);
// 现在（148 行，无条件）：
long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
// Foreground completion owns its remaining children, even after the shell has exited.
boolean terminationConfirmed = terminate(activeProcess, cleanupDeadline);
```
`terminate` → `OwnedProcess.liveMembers()` 通过 /proc 找到同 session+group 的残留成员 → `destroy()`（gracefulRootFirst=false 时立即 SIGTERM，`OwnedProcess.java:126-128`）→ 1s 后 SIGKILL。

**影响**
- LLM 常见的 `npm run dev > log 2>&1 &`、`nohup x &`、`python -m http.server &` 在工具返回时被杀死，而工具返回 **exit 0 + SUCCESS**（terminationConfirmed=true），调用方以为服务在跑。这是相对父提交的**用户可见行为变化**（父提交不会杀）。
- 设计者意图明确（"foreground completion owns its remaining children"，后台模式应使用 `is_background`），但 BashTool 的描述文案（`BashTool.java:175/197`）没有禁止 `&`，而 `cmd &` 是模型很常用的写法（未统计，属推断）；文档 `docs/zhikun/zhikuncode-AICoding通用缺陷修复实施方案.md:301` 也确认 `is_background` 才是后台通道。属于**需要产品确认的破坏性语义变更**。
- 反向副作用：残留进程若在 2s 内杀不掉（D 状态等），exit-0 的命令会被判"清理未确认"（F4）。

**建议**：明确文档/prompt 禁止 `&` 起长期服务；或对 exit==0 的正常结束只"记录未确认"而不升级为失败；至少把新失败码的 message 写清"残留子进程已被清理/未能确认"。

---

### F4 [P2] BashTool 新失败分支：exit-0 命令被判 PROCESS_TERMINATION_UNCONFIRMED（Retryability.NEVER / EffectState.UNKNOWN）
**位置**：`BashTool.java:408-418`（sandbox 对应分支 477-486）；`ToolExecutionPipeline.java:546-555`、`563-595`

**证据（代码）**
```java
// BashTool.java:408
if (!result.terminationConfirmed()) {
    return ToolResult.failed(ToolFailureType.PROCESS, "PROCESS_TERMINATION_UNCONFIRMED",
        "Command exited but child process cleanup could not be confirmed\n" + outputProcessor.processOutput(combined, true),
        Retryability.NEVER, EffectState.UNKNOWN, result.exitCode(), Map.of(...));
}
shellStateManager.updateStateFromSnapshot(sessionId);   // ← 该分支跳过（419 行之后）
```
**逐项结论**
- (i) 判定点穷举（`terminationConfirmed=false` 的来源）：① `OwnedProcess.terminate` 返回 false（F2 的 inspected 门控 / D 状态 / 枚举被拒）；② `cleanup()` 返回 false（`ManagedProcessRunner.java:372-401`：deadline 用尽、hook 抛异常、process==null）；③ backgroundGroup 路径的 `terminate` false。BashTool 前台路径 `terminationHook==null`，故 `cleanup()` 在 process!=null 时恒 true → 实际只有 ①。
- (ii) 会误报的合法流程：任何"命令正常结束但留下不可立即确认死亡的进程"（超时/取消路径另有分支，不落入此处；此处特指 exit=0 的正常命令）。若宿主枚举被拒（F2），**所有超时/取消命令**也会走这里（先被 `timedOut`/`cancelled` 分支截获，故实际影响转移到 `EffectState`/`terminationConfirmed` 语义）。
- (iii) 跳过 `shellStateManager.updateStateFromSnapshot(sessionId)` **无功能影响**：该方法体是 `log.debug(...)` 空实现（`ShellStateManager.java:104-107`），CWD 由包装脚本写文件维护。
- (iv) `ToolExecutionPipeline.copySafeResultMetadata`（`546-555`）白名单包含 `terminationConfirmed/descendantTrackingUnavailable/stdoutTruncated/stderrTruncated` → 新分支 metadata 会被完整透传到 run 事件；`ToolResult.failed` 的 `truncated` 取 `metadata["truncated"]`（此处未传 → false，即使 stdout 被截断；仅 metadata 里带 stdoutTruncated，**轻微不一致**，P3）。
- (v) `descendantTrackingUnavailable` 语义在真实路径上几乎恒为 false（它来自 `ManagedProcessRunner.descendantsUnavailable(process)`，即 **Result 构造时** `process.descendants()` 是否抛异常——与"终止未确认"的真实原因（枚举发生在 terminate 过程中）不是一回事）→ 该 metadata 会误导排障；测试用 mock 把它置 true，未验证真实语义。
- **不会自动重跑命令**（重要，已验证）：`ToolExecutionPipeline.attemptBashErrorRecovery`（`563-595`）只返回带 hint 的错误结果，`isRetryable()`（`ToolResult.java:132-135`）对 NEVER+UNKNOWN 为 false → 无双执行副作用。

**建议**：把 `truncated` 一并带入 metadata；`descendantTrackingUnavailable` 在 unconfirmed 分支重算（或改名为 `cleanupPhaseEnumerationFailed`）。

---

### F5 [P2] DevServer 未确认停止 → 句柄与 pid 文件保留，**下次验证因端口被占用直接失败**
**位置**：`DevServerLauncher.java:132-139`、`43/77（requireAvailablePort）`、`141-147（shutdownAll）`；`VerifyJourneyTool.java:333（固定端口）`

**证据**
```java
public void stop(DevServerHandle handle) {
    if (!processTreeManager.destroyProcessTree(handle.process(), GRACE_PERIOD)) {   // GRACE_PERIOD=2s
        activeHandles.putIfAbsent(String.valueOf(handle.pid()), handle);
        log.warn("Dev server cleanup unconfirmed: pid={}", handle.pid());
        return;     // pid 文件保留，句柄保留
    }
    activeHandles.remove(...); Files.deleteIfExists(handle.pidFile()); ...
}
```
重试只在 `@PreDestroy shutdownAll()`（`143-147`）发生。
**影响**：残留 dev server 仍占 `baseUrl` 端口（`VerifyJourneyTool:333` 用 `stack.defaultPort()`，端口固定，如 vite 5173）→ 下一次验证 `requireAvailablePort` 抛 `IllegalStateException("Dev server port is already in use")` → `VERIFY_JOURNEY_FAILED`，形成"一次未确认清理 = 后续验证持续失败"。`startProcess` 失败路径 `catch (RuntimeException|Error) { stop(handle) }`（`124-128`）在 stop 未确认时同样保留句柄。
**建议**：start 前对 `activeHandles` 中同端口残留做一次 terminate 重试；或未确认时改用动态端口并明确告警。

---

### F6 [P2] PythonProcessManager：未确认停止即置 FAILED 并保留 processRef；stop() 无返回值、@PreDestroy 最长阻塞 12s
**位置**：`PythonProcessManager.java:126-137（start 拒绝旧代）`、`181-190（stop）`、`197-205（restart）`、`360-366（stopProcess）`、`165-169（catch Error）`

**证据**
```java
public synchronized void stop() {
    Process process = processRef.get();
    if (process != null) {
        if (!stopProcess(process)) { stateRef.set(ProcessState.FAILED); return; }   // 不再置 STOPPED
        processRef.compareAndSet(process, null);
    }
    stateRef.set(ProcessState.STOPPED);
}
private boolean stopProcess(Process process) {
    boolean stopped = process instanceof OwnedProcess owned
        ? owned.terminate(System.nanoTime() + TimeUnit.SECONDS.toNanos(12), 10_000, true)   // deadline 12s / grace 10s
        : OwnedProcess.terminateTree(process, Duration.ofSeconds(10));
```
- 数学核对：`terminate(deadline=+12s, graceMillis=10000, gracefulRootFirst=true)` → `graceDeadline = min(deadline, now+10s)`（`OwnedProcess.java:100-101`）；**grace 内只给 root 发 SIGTERM，成员一律不发信号**（`OwnedProcess.java:125-128` 的 `if (!gracefulRootFirst || force)`）→ 若 root 已死但有遗留成员，会**空等满 10s** 才 SIGKILL，随后最多到 12s。故 `stop()/restart()/@PreDestroy onShutdown()` 最长阻塞 12s（三者都 synchronized，健康检查 `runHealthCheckCycle` 亦 synchronized → 会排队）。
- `stop()` 是 void：调用方无法区分"已停"与"FAILED 且进程仍存活"；未确认时 JVM 关闭（@PreDestroy 之后）会留下**孤儿 Python 进程**占端口。
- `start()` 若旧代停止失败直接 `FAILED + return false`（132-135）→ `startWithRetry()` 会按 `MAX_RESTART_ATTEMPTS` 重试 3 次、每次 sleep `restartDelayMs`（默认 5s），但每次都必然失败（没有重新尝试 terminate 之外的路径），只是浪费时间并刷日志。
- `restart()` 新增 `if (processRef.get() != null) return false;`（199）逻辑正确（避免在旧进程仍存活时启动新一代）。
- `catch (Error failure)`（165-169）会在 Error 时设定 FAILED 并 rethrow；`stopProcess` 在 Error 场景也可能耗时 12s——**Error 处理路径会吞掉"清理失败"（无日志强调），但保留了原始 Error**，可接受；未被测试覆盖。
- 影响面受限：`PythonProcessManager` 在本仓库**没有任何外部 Java 调用方**（只有自身 @EventListener/@Scheduled/@PreDestroy；`python.service.auto-start` 默认 false）——所以严重度降为 P2。

**建议**：stop() 返回 boolean 或暴露 lastStopConfirmed；grace 内对"root 已死"立即允许对成员发 TERM（不必等满 grace）。

---

### F7 [P2] `ProcessTreeManagerTest` 用 `Assumptions.assumeTrue(false)` 静默跳过核心断言
**位置**：`backend/src/test/java/.../ProcessTreeManagerTest.java:74-92`
```java
List<ProcessHandle> children;
try { children = process.descendants().toList(); }
catch (RuntimeException unavailable) {
    process.destroyForcibly();
    org.junit.jupiter.api.Assumptions.assumeTrue(false, "Host cannot enumerate children: " + unavailable);
    return;                       // ← 整个用例被跳过，"子进程也被终止"从未验证
}
```
**影响**：测试名仍宣称验证"所有子进程也已终止"，但在无法枚举的主机上静默 skip（不是 fail），覆盖率被高估；同时也说明"枚举被拒"是真实存在的环境（与 F2 呼应）。
**建议**：改为显式 `@EnabledOnOs(LINUX)` + 断言 /proc 枚举（不依赖 ProcessHandle.descendants()），不要在受限主机上吞掉覆盖。

---

### F8 [P3] `UserJourneyVerifier` 仍用 `"rv-" + sessionId`，与新的 UUID 资源 ID 不兼容（当前不可达，属潜在坑）
- `UserJourneyVerifier.java:36`：`"session_id", "rv-" + req.sessionId()`。
- 可达性核验：`VerifierFactory.selectVerifier`（`VerifierFactory.java:20-38`）只返回 `browserVerifier`/`httpApiVerifier`；全仓库仅 `VerifyJourneyTool.java:24` 有一个**未使用**的 import，无注入点、无其它引用 → **当前不可达，无实际泄漏**。
- 风险：若将来恢复该 bean，其资源 ID 与 `VerifyJourneyTool` finally 里 `close_session(browserResourceId=UUID)` 不匹配 → 浏览器会话泄漏到 TTL。建议直接删除该 bean 或改为使用 `req.browserResourceId()`。

### F9 [P3] CHANGELOG 未更新（与仓库惯例不符）
- `CHANGELOG.md` 有 `[Unreleased]` 段；`git log -- CHANGELOG.md` 显示 `e78d686`、`698299a`、`3b77e5f` 等 fix/feat 都同步更新，而 `0db4b04` 没有。
- 本提交改变用户可见行为（前台残留子进程清理、新失败码 `PROCESS_TERMINATION_UNCONFIRMED`、DevServer/Python 生命周期语义）→ 按惯例应补 `[Unreleased] → Changed/Fixed` 条目。

### F10 [P3] 次要问题（注释/死分支/性能/时延预算）
1. **死分支**：`ManagedProcessRunner.java:368` `return false; // A launch path without ownership must never claim whole-task cleanup.` —— 生产路径只有 `OwnedProcess` 或 `backgroundGroup`，该分支仅 mock/测试可达（无害，但与注释给人的"legacy 兼容"暗示不符）。
2. **时延预算收窄**：`VerifyJourneyTool` 声明 600s（`VerifyJourneyTool.java:119`）。最坏路径 ≈ npm install 300s（`DevServerLauncher.start` 内 `runSync`）+ dev server 就绪 120s + journey 130s（新，+10s）+ snapshot 2s + close 5s ≈ **557s**，余量从 ~53s 缩到 ~43s。建议显式对齐常量或加注释校验。
3. **每条命令的额外开销**：`OwnedProcess.start` 的身份校验最多自旋 2s（正常 <10ms）；每次 `terminate` 全量扫描 `/proc`（`OwnedProcess.java:160-174`），示例 500 进程机器 ≈ 每轮 500+ 次 stat 读，10ms 一轮持续到 deadline；`known` 只增不减（长寿命进程 + 大进程树时内存/CPU 增长，P3）。
4. **`truncated` 标志**：新分支未把 `stdoutTruncated/stderrTruncated` 折算进 `ToolResult.truncated`（`ToolResult.failed` 只读 `metadata["truncated"]`），下游 `outputTruncated` 为 false（P3）。
5. `DevServerLauncher.stop()` 在未确认路径 `activeHandles.putIfAbsent` 后 **不** 记录 `pendingCleanup`（pendingCleanup 只服务 npm install 的 Process）；语义割裂但无害。

---

## 二、逐条核验提交信息的四个声明（章节 I）

1. **"Retain process ownership through cleanup"** — ✅ 成立（有反例见 F1）：`releaseRetained` 只在 terminate+cleanup 都成功且 runnerFinished 时移除；`PythonProcessManager.stop` 未确认时保留 `processRef` 且置 FAILED（`181-188`）；`DevServerLauncher.stop` 未确认时保留句柄与 pid 文件（`132-139`）；`OwnedProcess.terminate` 用 `terminated` 记忆成功。**但**保留之后没有回收/重试通道（F1），这是该声明最严重的副作用。
2. **"guard descendant discovery against PID reuse"** — ✅ 成立（有残留 TOCTOU，已缓解）：`readIdentity`（`OwnedProcess.java:215-221`）字段映射**正确**：`/proc/<pid>/stat` 去掉 `pid`、`comm` 后 `fields[0]=state(第3列)`、`fields[1]=ppid(4)`、`fields[2]=pgrp(5)`、`fields[3]=session(6)`、`fields[19]=starttime(22)`。我在 `eclipse-temurin:21-jre-noble` 容器实测 `awk` 逐列输出确认第 22 列为 starttime ✓；`comm` 含空格/括号用 `lastIndexOf(')')` 处理 ✓。PID 复用守卫：`currentLeader.startTicks != leader.startTicks → groupRetired`（`156-159`）；`rememberDescendants` 双重 `isAlive()` 校验（`186-195`）；`known` 的 `compute` 只在旧句柄死亡时替换（`196-198`）；信号前再次 `readIdentity` 并跳过僵尸（`176-184`）。信号始终通过身份校验过的 `ProcessHandle`，从不直接 kill 裸 PGID ✓。残留窗口：`descendants()` 枚举到 `remember` 之间若 PID 被复用，新增句柄仍以 `handle.isAlive()`（JDK 内部按 starttime 校验）为准，风险低（未验证）。
3. **"preserve interruption and primary failures"** — ✅ 成立：`ManagedProcessRunner` finally 用 `Thread.interrupted()` 清标志 → 清理 → 末尾恢复（`168,180`）；`OwnedProcess.terminate` 同样保存/恢复（`99,130`）；`DevServerLauncher.runSync` 用 `primaryFailure` + `addSuppressed` 保留主异常，并在 `catch (IOException|InterruptedException)` 中恢复中断（`166-197`）；`PythonProcessManager.catch(Error)` 重抛原始 Error（`165-169`）；`OwnedProcess.start` 的 catch 中 `InterruptedException` 重设中断位（`92`）。测试 `DevServerLauncherTest.interruptedInstallPreservesInterruptAndPrimaryFailureWhenCleanupFails` 覆盖 ✓。
4. **"bounded lifecycle + regression coverage"** — ⚠️ 部分成立：所有终止路径都有 deadline（2s/12s/grace），但 (a) "未确认→保留"没有 TTL（F1，**并非 bounded**）；(b) F2 的 `inspected` 门控使"确认"在某些宿主不可达；(c) 回归覆盖见 F7 与第四节缺口清单。

---

## 三、已验证无问题清单（含反例排查）

| # | 结论 | 证据 |
|---|---|---|
| 1 | **官方镜像不缺 setsid/bash** —— `eclipse-temurin:21-jre-noble`：`/usr/bin/setsid`(util-linux, dpkg -S) + `/bin/bash`；`/bin -> usr/bin`。docker 实测：`command -v setsid → /usr/bin/setsid` | `docker run --rm --entrypoint sh eclipse-temurin:21-jre-noble -c 'command -v setsid; ls -l /usr/bin/setsid /bin/setsid /bin/bash; dpkg -S /usr/bin/setsid'` |
| 2 | **已构建产品镜像 `zhikuncode:latest` 同样存在** `/usr/bin/setsid`(14496B) 与 `/bin/bash`；`swebench eval`(Ubuntu 22.04) 也有 | 两次 `docker run --rm --entrypoint sh <image>` 实测 |
| 3 | Dockerfile runtime 未显式安装 util-linux 也不构成阻断（Ubuntu util-linux 为 required 优先级，随基础镜像存在）；`docker-compose.yml`/`docker-entrypoint.sh` 无相关删减；非 Linux（macOS）走 `OwnedProcess.java:33-41` 分支不依赖 setsid | Dockerfile:143-176、compose、entrypoint |
| 4 | `/proc/<pid>/stat` 字段映射正确（group/session/starttime/state），`lastIndexOf(')')` 解析健壮 | `OwnedProcess.java:215-221` + 容器实测列号 |
| 5 | `close_session` 的 `closed.get().get("success")` 语义**正确**：客户端 `objectMapper.readValue(response.body(), resultType)` 反序列化**整个 envelope**（非 `data`），Python 返回 `BrowserResponse(success=True, data={"closed":…})` | `PythonCapabilityAwareClient.java:274-277`、`python-service/src/routers/browser.py:254-262` |
| 6 | `strict_session:true` 不会创建幻影会话：Python 侧守卫返回 `{"success":False,"error_code":"SESSION_NOT_FOUND"}` → 路由返回 `success=False, data=None` → Java `resp.get().get("data")` 非 Map → 返回 baseMsg 静默降级 | `browser_service.py:558-568`、`routers/browser.py:288-312`、`VerifyJourneyTool.java:756-778` |
| 7 | 失败快照 → close_session 顺序正确（快照在 `handleVerificationResult` 内、close 在 finally） | `VerifyJourneyTool.java:346-348, 318-340, 373-387`；`VerifyJourneyEdgeCaseTest.concurrentJourneysKeep…` 用 stages 断言 created→snapshot→closed |
| 8 | `deadline_epoch_ms` 与 Python 端一致：Java `now+120s`；Python `remaining=min(120, deadline-now)`，`<=0 → 504`；HTTP 超时 130s > 120s | `BrowserVerifier.java:20-24,44`、`python-service/src/routers/journey.py:27-46` |
| 9 | HTTP 模式 4 参便利构造器（随机 `rv-UUID`）无副作用：`selectVerifier` 只用 `steps`，`browserResourceId=null` 时 `enrichWithFailureSnapshot` 被显式跳过 | `VerifyJourneyTool.java:267,412-421,505-507`、`JourneyRequest.java:15-18` |
| 10 | `putIfAbsent` 冲突路径 capacity 恰好释放一次；`releaseRetained` 由 `active.remove(key,value)` 守卫，不会双重释放；stale 回调不会误删新条目 | `ManagedProcessRunner.java:106-109,343-350`；测试 `duplicateRequestDoesNotExecuteCommandOrReleaseOriginalOwnership` |
| 11 | `ProcessKey.trackable()` 删除**安全**：`Request/BackgroundRequest` 的 compact 构造器强制 `runId/toolUseId` 非空非空白（`PROCESS_OWNERSHIP_MISSING`），生产调用点（BashTool:387/455、ChangedLineResolver:86/103(UUID opId)、MeooCliClient:26/42、PowerShellTool:162、serviceOwned）均不传 null → 不存在并发 null-key 冲突 | `ManagedProcessRunner.java:496-516` |
| 12 | backgroundGroup 条目不会被 `releaseRetained` 误释放（`backgroundGroup()==null` 守卫），其容量由 `group.exited` 回调释放 | `ManagedProcessRunner.java:170-190,226-233,343-346` |
| 13 | `ProcessTreeManager.destroyProcessTree(null)` / 已退出进程仍返回 true（与旧实现等价：旧 `null || !isAlive → true`；新 `terminateTree` 对 null→true，对死进程走 liveMembers→live 空→true） | `OwnedProcess.java:143-147,105-122` |
| 14 | `awaitDrain` 提前返回改动无害且更准确（future 已完成则直接取；异常仍走 `[stream read failed]/[stream drain incomplete]`） | `ManagedProcessRunner.java:452-468` |
| 15 | 中断保存/恢复正确（三处），`Thread.interrupted()` 只在 finally 内使用 | 见声明 3 |
| 16 | 新失败分支**不会自动重跑命令**（Recovery 仅附加 hint；`isRetryable()==false`） | `ToolExecutionPipeline.java:563-595`、`ToolResult.java:132-135` |
| 17 | 跳过 `updateStateFromSnapshot` 无功能影响（空实现，仅 debug 日志） | `ShellStateManager.java:104-107` |
| 18 | macOS 开发机：`ProcessHandle.descendants()` 实测正常（2 个子进程可枚举，destroy/waitFor 正常）；非 Linux 分支无需 setsid | 本机 Corretto 21 单文件探针（写于 /tmp，已非仓库文件） |
| 19 | `BashTool` 后台路径（`is_background`）与 backgroundGroup 逻辑未被本次改动影响 | diff 未触及 `startBackgroundLeased` 语义 |
| 20 | 未确认清理的"失败关闭"语义与 RunTerminationCoordinator 的既有设计一致（`PROCESS_TERMINATION_UNCONFIRMED`/`TOOL_TERMINATION_UNCONFIRMED` 已存在） | `RunTerminationCoordinator.java:57-78`、`RunEnvelope.java:32` |

---

## 四、测试质量评估（章节 G，只读审查，未运行）

**新增/修改测试的有效断言**
- `ManagedProcessRunnerTest`（+206 行）：`failedCleanupRetainsRunLeaseUntilRetryConfirmsExit`（参数化 lease/显式 cancel 两条重试路径，断言 capacity 0→1、`PROCESS_CAPACITY_EXCEEDED`、awaitQuiescence）、`failedStartReleasesReservedLeaseAndCapacityWithoutCallingCleanup`、`duplicateRequestDoesNotExecuteCommandOrReleaseOriginalOwnership`（并用 `touch should-not-exist` 证明**未执行**命令——有效）、`cancellationDuringLaunchDoesNotCleanOrCacheSuccessBeforeProcessExists`（用 mockStatic + 在 start 内触发 shutdown/beginTermination，精确覆盖"取消早于进程存在"竞态）、`concurrentCancellationsShareOneRetryAfterFailedCleanup`（断言 cleanup 只被多调用 1 次、`maximumCleaners==1`、capacity 回到 16）、`preservesCompletedOutputEvenWhenCleanupConsumesItsBudget`（配合 awaitDrain 改动）、`foregroundParallelWorkThatWaitsStillCompletesNormally`。**质量高**，断言的是行为而非实现细节。
- `OwnedProcessTest`（+210 行）：9 组 `@CsvSource` 覆盖 `rememberDescendants` 的 before/after 存活矩阵（root/member/wait 三条路径，并 verify `child.destroy/destroyForcibly` **never**），另 5 个 `@EnabledOnOs(LINUX)` 真实进程用例（顽固子进程、输出/环境/退出码、跨 session 的浏览器式子进程、root 优雅关闭 trap、中断保留）。断言对象正确（真实 PID 存活检查用 /proc）。
- `DevServerLauncherTest`：`failedCleanupRetainsHandleAndPidFileForRetry`（pid 文件保留→shutdownAll 后删除、destroyProcessTree 恰 2 次）、中断/主失败保留（`getSuppressed()` 断言）、`successfulInstallStillFailsAndRetainsProcessWhenCleanupIsUnconfirmed`。有效。
- `BashToolFailureClassificationTest.foregroundExitZeroDoesNotHideUnconfirmedCleanup`：断言 failureCode、content、4 个 metadata、`verify(outputProcessor).processOutput("done\ndiagnostic", true)`、`verify(shellStateManager, never()).updateStateFromSnapshot(...)` —— 覆盖了新分支，但 **metadata 全部来自 mock Result**，未证明真实路径上这些字段的值（见 F4(v)）。
- `PythonProcessManagerTest`：unconfirmed 保留 + 阻塞 restart/start；成功清理清空引用。用反射 `ReflectionTestUtils.getField(processRef)`，可接受。
- `VerifyJourneyEdgeCaseTest`：并发两条 journey 用 `CyclicBarrier` + stages 顺序断言（created→snapshot→closed），并验证 sessionId 保持业务值、evidence/通知按 session 归属。有效但依赖线程调度。
- `BrowserVerifierTest`：断言 `body.session_id == req.browserResourceId()`、两次 sampleRequest 的资源 ID 不同、`deadline≈now+120s` 区间、timeout=130s≠60s。

**mock 有效性/易碎性**
- 多处 `Thread.sleep(75/200/350)`、`CyclicBarrier.await(5s)`、`CountDownLatch` 超时、`while (...) Thread.sleep(10)` 轮询 → 慢机器/高负载下易碎（P3）。
- `OwnedProcessTest` 用反射访问私有 `known`/`liveMembers`/`ProcIdentity` → 与实现强耦合，重构即碎。
- `mockStatic(OwnedProcess.class)` 依赖字节码增强（spring-test）；对 `OwnedProcess.start` 的 mock 让"真实 setsid 启动"在该用例中被绕过（有意为之）。

**未覆盖的高风险路径**
1. `PROCESS_GROUP_UNAVAILABLE`：`/usr/bin/setsid` 与 `/bin/setsid` 均不可执行、`/bin/bash` 缺失、`redirectInput != PIPE`、身份校验 2s 超时（`OwnedProcess.java:43-48,50-52,67-77`）—— 无任何测试（F3/部署相关）。
2. `terminate` 返回 false 时的 **finally 保留路径**（`ManagedProcessRunner.java:171-179`）：只有"cleanup hook 失败"被覆盖；"terminate=false 且 cleanup=true"（真实 F2 场景）没有直接断言 → 正是 F1 的泄漏点。
3. `groupRetired` 真实 PID 复用（`OwnedProcess.java:156-159`）：仅 mock 模拟（`admitsDescendantsOnlyWhileOwnerIdentityRemainsAlive` 覆盖 before/after 语义，不覆盖 /proc 扫描与复用判定）。
4. BashTool sandbox 分支的 `PROCESS_TERMINATION_UNCONFIRMED`（`BashTool.java:477-486`）无测试。
5. DevServer 未确认清理 → 端口占用 → 后续验证失败的链路无测试（F5）。
6. `PythonProcessManager.catch (Error)` 分支、`drainOutput`、10s grace 下"root 死而成员活"的空等行为无测试（F6）。
7. 非 Linux 回退分支（`waitFor` 便携后代快照 `OwnedProcess.java:229-246`）在 Linux CI 上不会被覆盖（`@EnabledOnOs(LINUX)` 与 CI 平台假设）。

---

## 五、无法静态确认、需动态验证清单

1. 真实容器（Linux）内 `terminate` 对"正常结束 + 残留后台子进程"实际耗时/确认结果，以及 SIGTERM 后子进程树（npm→node→esbuild）是否都能在 2s 内被确认。
2. 16 槽容量在长时间运行中是否会被"未确认"逐步吞掉（需要人为构造不可杀进程，例如 D 状态或 SIGKILL 免疫的 NFS 挂载点）。
3. `WorkLease` 保留 → `QueryEngine` 会话卡死（F1）的端到端表现（需构造 unconfirmed + 走完整 query 流程）。
4. `hidepid=2` / 受限 seccomp 容器中 `/proc` 目录枚举与 `ProcessHandle.descendants()` 的实际行为（F2 的触发边界）；本机 macOS 无法复现 `descendants()` 被拒。
5. 真实 PID 复用（`groupRetired`）行为：需要精确制造 PID 环绕。
6. Playwright/Chromium 跨 session 子进程的实际清理（有 opt-in 测试 `ZHIKUN_REAL_BROWSER_PROCESS_TEST`，本次未运行）。
7. `setsid` 准入握手在 Java `ProcessBuilder` 下的 PGID==SID 断言稳定性（理论上 Java 子进程永不是组长，故 `setsid` 不会 fork；未动态验证）。
8. `OwnedProcess.start` 2s 身份校验窗口对首命令时延与失败率的影响量级。
9. DevServer `pendingCleanup`/`activeHandles` 在长跑进程中的积累（F5）在真实多轮验证下的表现。

---

## 六、建议的优先修复顺序
1. F1（未确认保留的回收通道 / 容量与 lease 解耦）—— 这是唯一能让"会话/服务整体不可用"的缺陷。
2. F2（`inspected` 门控回退到 ProcessHandle 快照判定）。
3. F3/F4（正常结束的清理语义与新失败码文案/字段；确认 `&` 场景的产品预期）。
4. F5（start 前重试残留句柄/动态端口）。
5. F6/F7/F8/F9/F10（工程性收尾）。
