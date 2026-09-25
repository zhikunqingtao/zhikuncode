# 测试执行与部署环境证据

- 仓库：`/Users/guoqingtao/Desktop/dev/code/zhikuncode`
- 被审提交：`0db4b0498f6cf886f862fe293883256b1c52e049`（本地 HEAD，`fix: close process and browser lifecycle leaks`）
- 机器：macOS 26.5.2 / aarch64（darwin）；本机另有正在运行的开发服务（8080 java、8000 python、5173 vite），本次未触碰
- 执行时间：2026-09-25 09:30–09:56（+08:00）
- 执行约束：未修改任何源码/配置/测试文件，未 commit/push；仅运行构建与测试（产生 backend/target 等构建产物）

---

## 0. 结论速览（关键数字）

| 项目 | 结果 |
|---|---|
| Java 定向（8 类）第1次 | 87 run / **3 failed** / 0 error / 6 skipped（失败全部在 ManagedProcessRunnerTest；与 Docker 探测并发执行的负载时段） |
| Java 定向（8 类）第2次 | 87 run / **0 failed** / 0 error / 6 skipped / BUILD SUCCESS |
| ManagedProcessRunnerTest 隔离重跑 | 17/17 通过（第1次全量失败后隔离复现失败未再出现） |
| Java 全量 `mvn test` | **3039 run / 14 failed / 0 error / 76 skipped**，总耗时 08:58 min，BUILD FAILURE |
| Java 全量 14 个失败归属 | 6 个失败类均**不在**本提交改动文件内；其中 4 类隔离后仍稳定失败（CoordinatorServiceTest×3、SecurityFilterIntegrationTest×6、WorkspaceFileBoundaryTest×1、OneKeyRegistryIntegrationTest×1），2 类仅在负载下失败（ConcurrencyControlTest×2、ManagedProcessRunnerTest×1） |
| Python 定向第1次（4 文件） | **65 passed / 1 skipped**（skip=仅限隔离 Linux 容器的测试）8.64s |
| Python 定向第2次（3 生命周期文件） | **65 passed / 0 skipped** 12.38s（两次无差异） |
| Python 全量 `pytest tests` | **2 failed / 227 passed / 1 skipped**（67.46s，与 Java 全量并发负载下运行）；2 个失败在空载复测分别 3/3、5/5 通过 → 负载敏感的时序断言 |
| **Docker/setsid（最关键）** | **实测通过**：`eclipse-temurin:21-jre-noble` = Ubuntu 24.04.4 LTS，`/usr/bin/setsid` 存在（util-linux 2.39.3，`command -v setsid` exit=0），`/bin/bash` 存在；本仓库构建的 `zhikuncode:meoo-review`（Ubuntu 24.04）与 `zhikuncode:latest`（Ubuntu 22.04）镜像内同样存在 setsid |
| CI 判定 | ci.yml 会在 ubuntu-latest 跑**全部** Java 测试（含 8 个新类，Linux 下 OwnedProcessTest 不再跳过）与全部 Python 测试（含 4 个新文件；容器测试自动 skip）；task3-5 workflow 因 `browser_service.py` 命中路径也会跑全量 Python 测试，但 Java 只跑 2 个无关类 |

---

## 1. 环境探测

### 命令与输出

```console
$ git status --short          # 初始状态：无输出（干净）
$ git rev-parse HEAD
0db4b0498f6cf886f862fe293883256b1c52e049
$ git log -1 --format='%H %s %ci'
0db4b0498f6cf886f862fe293883256b1c52e049 fix: close process and browser lifecycle leaks 2026-09-25 08:41:18 +0800

$ java -version
openjdk version "21.0.10" 2026-01-20 LTS
OpenJDK Runtime Environment Corretto-21.0.10.7.1 (build 21.0.10+7-LTS)
OpenJDK 64-Bit Server VM Corretto-21.0.10.7.1 (build 21.0.10+7-LTS, mixed mode, sharing)

$ mvn -version
-bash: mvn: command not found          # 系统 PATH 无 mvn，使用 backend/mvnw
$ cd backend && ./mvnw -version
Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)
Maven home: /Users/guoqingtao/.m2/wrapper/dists/apache-maven-3.9.9-bin/33b4b2b4/apache-maven-3.9.9
Java version: 21.0.10, vendor: Amazon.com Inc., runtime: /Users/guoqingtao/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
OS name: "mac os x", version: "26.5.2", arch: "aarch64", family: "mac"

$ cd python-service && .venv/bin/python --version
Python 3.11.15          # .venv/bin/python -> python3.11
$ .venv/bin/python -m pytest --version
pytest 8.3.4
$ .venv/bin/pip show playwright pytest-asyncio pytest-timeout pytest-cov | grep -E '^(Name|Version)'
Name: playwright / Version: 1.58.0
Name: pytest-asyncio / Version: 0.24.0
Name: pytest-timeout / Version: 2.3.1
Name: pytest-cov / Version: 7.1.0
```

### 其他环境事实

- 本机正在运行的开发服务（只读观察，未触碰）：`java *:8080`、`python3.11 127.0.0.1:8000`、`node 127.0.0.1:5173`。
- Docker：客户端 29.4.0 / Docker Desktop 4.70.0（Server Engine 29.4.0，linux/arm64，见 §7）。
- 说明：第 1 次 Java 定向测试（09:32:32–09:38:39）与 Docker 镜像探测命令在时间上重叠，机器存在额外负载（见 §2.1 与 §7 的 500/超时现象）。

---

## 2. Java 定向测试（8 个类）

命令（`mvn` 不在 PATH，使用仓库自带包装器）：

```console
$ cd backend && ./mvnw -Dtest='PythonProcessManagerTest,ProcessTreeManagerTest,BashToolFailureClassificationTest,ManagedProcessRunnerTest,OwnedProcessTest,BrowserVerifierTest,DevServerLauncherTest,VerifyJourneyEdgeCaseTest' -DfailIfNoTests=false test
```

### 2.1 第 1 次运行（2026-09-25 09:32:32 → 09:38:39，MVN_EXIT=1）

```
[INFO] Running com.aicodeassistant.verify.VerifyJourneyEdgeCaseTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 107.5 s -- in com.aicodeassistant.verify.VerifyJourneyEdgeCaseTest
[INFO] Running com.aicodeassistant.verify.DevServerLauncherTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.602 s -- in com.aicodeassistant.verify.DevServerLauncherTest
[INFO] Running com.aicodeassistant.verify.BrowserVerifierTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.047 s -- in com.aicodeassistant.verify.BrowserVerifierTest
[INFO] Running com.aicodeassistant.service.PythonProcessManagerTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.354 s -- in com.aicodeassistant.service.PythonProcessManagerTest
[INFO] Running com.aicodeassistant.tool.impl.BashToolFailureClassificationTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.269 s -- in com.aicodeassistant.tool.impl.BashToolFailureClassificationTest
[INFO] Running com.aicodeassistant.tool.bash.ProcessTreeManagerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.773 s -- in com.aicodeassistant.tool.bash.ProcessTreeManagerTest
[INFO] Running com.aicodeassistant.tool.process.ManagedProcessRunnerTest
[ERROR] Tests run: 17, Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 24.94 s <<< FAILURE! -- in com.aicodeassistant.tool.process.ManagedProcessRunnerTest
[INFO] Running com.aicodeassistant.tool.process.OwnedProcessTest
[WARNING] Tests run: 15, Failures: 0, Errors: 0, Skipped: 6, Time elapsed: 2.592 s -- in com.aicodeassistant.tool.process.OwnedProcessTest
[ERROR] Tests run: 87, Failures: 3, Errors: 0, Skipped: 6
[INFO] BUILD FAILURE        # MVN_EXIT=1
```

失败栈（surefire 报告 `target/surefire-reports/com.aicodeassistant.tool.process.ManagedProcessRunnerTest.txt`）：

```
com.aicodeassistant.tool.process.ManagedProcessRunnerTest.survivingChildRetainsLeaseAfterShellExitsOrIsKilled(boolean, Path)[1] -- Time elapsed: 6.736 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
Expecting value to be true but was false
    at ...ManagedProcessRunnerTest.survivingChildRetainsLeaseAfterShellExitsOrIsKilled(ManagedProcessRunnerTest.java:249)

com.aicodeassistant.tool.process.ManagedProcessRunnerTest.concurrentCancellationWaitsForTheSingleCleanupOwner -- Time elapsed: 5.016 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
Expecting value to be false but was true
    at ...ManagedProcessRunnerTest.concurrentCancellationWaitsForTheSingleCleanupOwner(ManagedProcessRunnerTest.java:365)

com.aicodeassistant.tool.process.ManagedProcessRunnerTest.cancellationIsDistinguishedFromTimeout -- Time elapsed: 0.215 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
Expecting value to be true but was false
    at ...ManagedProcessRunnerTest.cancellationIsDistinguishedFromTimeout(ManagedProcessRunnerTest.java:326)
```

对应的源码断言行（macOS 上运行，`bash -c 'sleep 30'` 等真实子进程语义）：

- 行 249：`assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isTrue();`（父进程被杀后子进程应存活）
- 行 326：`assertThat(result.cancelled()).isTrue();`（cancel 与超时应可区分）
- 行 365：`assertThat(first.isDone() && second.isDone()).isFalse();`（并发取消等待唯一清理者）

**6 个 skipped 的原因**（OwnedProcessTest，surefire XML）：

```
SKIPPED: com.aicodeassistant.tool.process.OwnedProcessTest | Disabled on operating system: Mac OS X   (×6)
```

`OwnedProcessTest` 中 `@EnabledOnOs(OS.LINUX)` 标注的 6 个用例在 macOS 跳过（源码标注：`OwnedProcessTest.java:73/101/116/137...`）。
`ManagedProcessRunnerTest` 无平台标注，在 macOS 上照常运行。

### 2.2 隔离重跑 ManagedProcessRunnerTest（第 1 次失败后立即执行）

```console
$ ./mvnw -Dtest='ManagedProcessRunnerTest' -DfailIfNoTests=false test
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 20.77 s -- in com.aicodeassistant.tool.process.ManagedProcessRunnerTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
```

→ 隔离重跑 17/17 全绿；第 1 次运行的 3 个失败未复现。

### 2.3 第 2 次完整定向运行（09:41:23 → 09:42:37，MVN_EXIT=0）

```
[INFO] Running com.aicodeassistant.verify.VerifyJourneyEdgeCaseTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 18.24 s
[INFO] Running com.aicodeassistant.verify.DevServerLauncherTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.069 s
[INFO] Running com.aicodeassistant.verify.BrowserVerifierTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.681 s
[INFO] Running com.aicodeassistant.service.PythonProcessManagerTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.696 s
[INFO] Running com.aicodeassistant.tool.impl.BashToolFailureClassificationTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.995 s
[INFO] Running com.aicodeassistant.tool.bash.ProcessTreeManagerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.668 s
[INFO] Running com.aicodeassistant.tool.process.ManagedProcessRunnerTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 14.30 s
[INFO] Running com.aicodeassistant.tool.process.OwnedProcessTest
[WARNING] Tests run: 15, Failures: 0, Errors: 0, Skipped: 6, Time elapsed: 0.627 s
[WARNING] Tests run: 87, Failures: 0, Errors: 0, Skipped: 6
[INFO] BUILD SUCCESS       # MVN_EXIT=0
```

**定向测试 flaky 观察**：同一组 8 类共运行 3 次（含 1 次单类隔离）——ManagedProcessRunnerTest 在第 1 次出现 3 个失败（当时机器有并发的 Docker 探测负载、VerifyJourneyEdgeCaseTest 从常规 ~3-18s 拉长到 107.5s），其后 2 次（隔离 + 完整组合）均为 17/17 全绿。断言均为时序/进程退出竞态类断言，失败未复现。

---

## 3. Java 全量回归（09:44:00 → 09:53:08）

```console
$ cd backend && ./mvnw test -B
[ERROR] Tests run: 3039, Failures: 14, Errors: 0, Skipped: 76
[INFO] BUILD FAILURE
[INFO] Total time:  08:58 min     # < 10 分钟上限，未使用后台轮询
```

### 14 个失败的完整清单（`Results:` 段原文）

```
[ERROR]   ConcurrencyControlTest.testConcurrentAcquireRelease()[1] 成功数应≤全局限制30 ==> expected: <true> but was: <false>
[ERROR]   ConcurrencyControlTest.testConcurrentAcquireRelease()[2] 成功数应≤全局限制30 ==> expected: <true> but was: <false>
[ERROR]   SecurityFilterIntegrationTest.bearerToken_invalid_shouldReturn401:92 Status expected:<401> but was:<200>
[ERROR]   SecurityFilterIntegrationTest.bearerToken_valid_shouldPassAndIssueCookie:78  Expecting actual not to be null
[ERROR]   SecurityFilterIntegrationTest.cookie_valid_shouldPass:114  Expecting actual not to be null
[ERROR]   SecurityFilterIntegrationTest.noAuth_shouldReturn401:137 Status expected:<401> but was:<200>
[ERROR]   SecurityFilterIntegrationTest.publicNetwork_shouldReturn403:165 Status expected:<403> but was:<200>
[ERROR]   SecurityFilterIntegrationTest.urlToken_valid_shouldRedirect:152 Range for response status value 200 expected:<REDIRECTION> but was:<SUCCESSFUL>
[ERROR]   CoordinatorServiceTest.matchSessionMode_coordinator_then_isCoordinatorMode_returns_true:37 expected: not <null>
[ERROR]   CoordinatorServiceTest.matchSessionMode_normal_then_isCoordinatorMode_returns_false:60 isCoordinatorMode() should return false after matchSessionMode("normal") ==> expected: <false> but was: <true>
[ERROR]   CoordinatorServiceTest.matchSessionMode_same_mode_returns_null:81 Should return null when current mode matches target ==> expected: <null> but was: <Exited coordinator mode to match resumed session.>
[ERROR]   OneKeyRegistryIntegrationTest.genericLlmApiKeyDoesNotAuthenticateZhipuDashscopeMcp:137 expected: <> but was: <sk-9***REDACTED-35***>
[ERROR]   WorkspaceFileBoundaryTest.recursiveGrepSkipsProtectedFilesButDirectAccessCanBeAuthorized:269  Expecting actual: "" to contain: "directory-secret"
[ERROR]   ManagedProcessRunnerTest.concurrentCancellationWaitsForTheSingleCleanupOwner:365  Expecting value to be false but was true
```

按类汇总（surefire 行）：

```
com.aicodeassistant.coordinator.CoordinatorServiceTest              7 run /  3 fail / 0.233 s
com.aicodeassistant.config.SecurityFilterIntegrationTest            9 run /  6 fail / 115.7 s
com.aicodeassistant.security.WorkspaceFileBoundaryTest             16 run /  1 fail / 5.015 s
com.aicodeassistant.agent.ConcurrencyControlTest（TC-CONC-005…）    3 run /  2 fail / 3.167 s
com.aicodeassistant.mcp.OneKeyRegistryIntegrationTest                7 run /  1 fail / 0.711 s
com.aicodeassistant.tool.process.ManagedProcessRunnerTest           17 run /  1 fail / 14.39 s   ← 唯一属于本提交 8 个定向类的失败
```

其余 8 个定向类在全量中的结果：VerifyJourneyEdgeCaseTest 21/0（3.403s）、DevServerLauncherTest 6/0、BrowserVerifierTest 5/0、PythonProcessManagerTest 12/0、BashToolFailureClassificationTest 7/0（截获）、ProcessTreeManagerTest 4/0（截获，均无失败）。

> 归因说明（结合 `git show --stat 0db4b04`）：本提交只改动
> `PythonProcessManager / ProcessTreeManager / BashTool / ManagedProcessRunner / OwnedProcess(new) / VerifyJourneyTool / BrowserVerifier / DevServerLauncher / JourneyRequest`（Java）与
> `python-service/src/routers/journey.py / services/browser_service.py / services/journey_models.py`（Python）及对应测试。
> 上表 5 个失败类（CoordinatorServiceTest、SecurityFilterIntegrationTest、WorkspaceFileBoundaryTest、ConcurrencyControlTest、OneKeyRegistryIntegrationTest）的源码文件均**不在**该清单内。

---

## 4. Java 全量失败类的隔离复测与父提交对照

### 4.1 HEAD 上隔离复测 5 个失败类（09:53:46 → 09:54:01）

```console
$ ./mvnw -Dtest='CoordinatorServiceTest,SecurityFilterIntegrationTest,WorkspaceFileBoundaryTest,ConcurrencyControlTest,OneKeyRegistryIntegrationTest' -DfailIfNoTests=false test
[ERROR] Tests run: 7,  Failures: 3, ... -- in com.aicodeassistant.coordinator.CoordinatorServiceTest
[ERROR] Tests run: 9,  Failures: 6, ... -- in com.aicodeassistant.config.SecurityFilterIntegrationTest
[ERROR] Tests run: 16, Failures: 1, ... -- in com.aicodeassistant.security.WorkspaceFileBoundaryTest
[INFO]  Tests run: 3,  Failures: 0, ... -- in TC-CONC-005 多线程并发获取/释放安全性
[INFO]  Tests run: 1,  Failures: 0, ... -- in TC-CONC-001/002/003/004
[ERROR] Tests run: 7,  Failures: 1, ... -- in com.aicodeassistant.mcp.OneKeyRegistryIntegrationTest
[ERROR] Tests run: 46, Failures: 11, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

| 类 | 全量 | 隔离 | 判定 |
|---|---|---|---|
| CoordinatorServiceTest | 3 fail | **3 fail（稳定复现）** | 与本提交无关的确定性失败 |
| SecurityFilterIntegrationTest | 6 fail | **6 fail（稳定复现）** | 与本提交无关的确定性失败 |
| WorkspaceFileBoundaryTest | 1 fail | **1 fail（稳定复现）** | 与本提交无关的确定性失败 |
| OneKeyRegistryIntegrationTest | 1 fail | **1 fail（稳定复现）** | 与本提交无关的确定性失败 |
| ConcurrencyControlTest (TC-CONC-005) | 2 fail | **0 fail** | 仅在负载下失败的并发断言 |
| ManagedProcessRunnerTest | 1 fail | 0 fail（§2.2 亦 0 fail） | 仅在负载下失败；属本提交测试 |

### 4.2 父提交（HEAD~1）对照运行

为排除“本提交改动是否导致上述确定性失败”，用 `git archive HEAD~1` 将父提交快照导出到 `/tmp/zhikun_parent`（不触碰当前工作树、不产生 git 提交/worktree 元数据），在快照内以完全相同命令运行 4 个稳定失败类：

```console
$ git archive HEAD~1 | tar -x -C /tmp/zhikun_parent
$ cd /tmp/zhikun_parent/backend && ./mvnw -Dtest='CoordinatorServiceTest,SecurityFilterIntegrationTest,WorkspaceFileBoundaryTest,OneKeyRegistryIntegrationTest' -DfailIfNoTests=false test
```

结果（09:56:19 → 09:56:49，MVN_EXIT=1，首次编译 689 个源文件后运行）：

```
[INFO] Running com.aicodeassistant.coordinator.CoordinatorServiceTest
[ERROR] Tests run: 7,  Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 0.762 s -- in com.aicodeassistant.coordinator.CoordinatorServiceTest
[INFO] Running com.aicodeassistant.config.SecurityFilterIntegrationTest
[ERROR] Tests run: 9,  Failures: 6, Errors: 0, Skipped: 0, Time elapsed: 7.083 s -- in com.aicodeassistant.config.SecurityFilterIntegrationTest
[INFO] Running com.aicodeassistant.security.WorkspaceFileBoundaryTest
[ERROR] Tests run: 16, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.444 s -- in com.aicodeassistant.security.WorkspaceFileBoundaryTest
[INFO] Running com.aicodeassistant.mcp.OneKeyRegistryIntegrationTest
[ERROR] Tests run: 7,  Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.245 s -- in com.aicodeassistant.mcp.OneKeyRegistryIntegrationTest
[ERROR] Tests run: 39, Failures: 11, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

**结论**：父提交 HEAD~1 上同样的 4 个类失败数逐一相同（3+6+1+1 = 11），与 HEAD 上隔离复测的这 4 类失败完全一致 →
**这 11 个确定性失败系本机/既有问题，与本提交 0db4b049 无关**（这些类的源文件也不在本提交改动清单内）。
另有 3 个失败（ConcurrencyControlTest×2、ManagedProcessRunnerTest×1）仅在负载下出现、空载复测通过。

---

## 5. Python 定向测试（4 个文件）与二次重跑

### 5.1 第 1 次（09:41:25 → 09:41:44；与 Java 定向第 2 次有小段并发）

```console
$ cd python-service && .venv/bin/python -m pytest tests/test_browser_resource_container.py tests/test_browser_service_lifecycle.py tests/test_journey_lifecycle.py tests/test_journey_publication.py -q -p no:cacheprovider
s.................................................................       [100%]
=========================== short test summary info ============================
SKIPPED [1] tests/test_browser_resource_container.py:31: isolated Linux container only
65 passed, 1 skipped in 8.64s
PYTEST_EXIT=0
```

skip 原因（源码）：`tests/test_browser_resource_container.py:29` 有
`@unittest.skipUnless(os.getenv("ZHIKUN_BROWSER_CONTAINER_TEST") == "1", "isolated Linux container only")`
→ 需要专门的隔离 Linux 容器环境，本机（macOS）与 CI 默认均跳过。

### 5.2 第 2 次（09:44:02 → 09:44:22；3 个生命周期文件）

```console
$ .venv/bin/python -m pytest tests/test_browser_service_lifecycle.py tests/test_journey_lifecycle.py tests/test_journey_publication.py -q -p no:cacheprovider
.................................................................        [100%]
65 passed in 12.38s
PYTEST_EXIT=0
```

**flaky 观察**：两次定向运行结果逐项一致（65 passed；4 文件那次唯一的 skip 为容器专用用例），
定向集合内未观察到 flaky。（全量运行中曾有 1 个新用例失败、空载复测通过，见 §6。）

---

## 6. Python 全量测试（09:44:26 → 09:45:50；与 Java 全量测试并发执行）

```console
$ .venv/bin/python -m pytest tests -q -p no:cacheprovider
=========================== short test summary info ============================
SKIPPED [1] tests/test_browser_resource_container.py:31: isolated Linux container only
FAILED tests/test_browser_service_lifecycle.py::test_close_during_creation_prevents_late_registration_even_if_driver_swallows_cancel
FAILED tests/test_token_estimation.py::test_estimate_batch - AssertionError: ...
2 failed, 227 passed, 1 skipped in 67.46s (0:01:07)
PYTEST_EXIT=1
```

### 失败 1（本提交新增测试）：`test_close_during_creation_prevents_late_registration_even_if_driver_swallows_cancel`

```
tests/test_browser_service_lifecycle.py:207: assert await service.close_session("resource")
src/services/browser_service.py:524: RuntimeError
E   RuntimeError: Browser session 'resource' cleanup was not confirmed
------------------------------ Captured log call -------------------------------
WARNING  services.browser_service:browser_service.py:348 Timed out closing creating session resource
```

该测试 fixture 将 `service.cleanup_timeout = 0.05`（50ms，`test_browser_service_lifecycle.py:18`）——断言窗口很紧。

### 失败 2（既有测试，非本提交范围）：`tests/test_token_estimation.py::test_estimate_batch`

```
assert elapsed_ms < 500, f"响应耗时 {elapsed_ms:.1f}ms 超过 500ms"
E       AssertionError: 响应耗时 782.4ms 超过 500ms
```

### 负载敏感性复测（区分“真失败”与“负载抖动”）

| 复测条件 | browser 生命周期用例 | token_estimation 用例 |
|---|---|---|
| Java 全量并发期间，组合重复 5 次 | （同批输出含 token 失败；后续单独复测见下） | **5/5 failed**（~782ms>500ms） |
| Java 刚结束后的单独复测 3 次 | **1 failed (3.73s, 同款 Timeout 警告) / 2 passed (0.86s, 1.01s)** | — |
| 完全空载单独复测 | **5/5 passed，0.04–0.07s/次** | **3/3 passed，0.13–0.19s/次** |

**结论**：Python 全量的 2 个失败均为负载/事件循环延迟敏感型；空载下两者均可稳定通过。
新增生命周期用例因 50ms 的 cleanup 超时窗口，在负载高（如 CI 共享 runner）时存在 flaky 失败风险；
token_estimation 的 500ms 性能断言同样是负载敏感（空载 0.13s，负载 0.78s）。
Python 全量其余用例（227 passed）无失败；1 个 skip 为容器专用用例（§5.1）。

---

## 7. Docker / setsid 部署检查（最高优先级）

### 7.1 daemon 可用性

```console
$ docker version
Client: Docker Desktop 4.70.0, Version 29.4.0, API 1.54, OS/Arch darwin/arm64
Server: Docker Desktop 4.70.0 (224270), Engine 29.4.0, OS/Arch linux/arm64
```

注意：第一次 `docker run` 探测返回
`request returned 500 Internal Server Error for API route ... /docker.sock/_ping`，
`docker info` 60s 超时，`docker ps` 显示 0 个运行中容器（daemon 响应迟缓，疑似刚刚唤醒/负载）；
随后重试即正常。**最终 daemon 可用，完成实测。**

### 7.2 关键实测：生产基础镜像 `eclipse-temurin:21-jre-noble`

```console
$ docker run --rm --entrypoint sh eclipse-temurin:21-jre-noble -c \
    'echo "--os-release--"; head -3 /etc/os-release; echo "--command -v setsid--"; command -v setsid; echo "exit=$?"; \
     echo "--ls setsid--"; ls -l /usr/bin/setsid /bin/setsid 2>&1; echo "--dpkg util-linux--"; dpkg -l | grep -i util-linux; echo "--bash--"; ls -l /bin/bash 2>&1'

--os-release--
PRETTY_NAME="Ubuntu 24.04.4 LTS"
NAME="Ubuntu"
VERSION_ID="24.04"
--command -v setsid--
/usr/bin/setsid
exit=0
--ls setsid--
-rwxr-xr-x 1 root root 67744 Aug 19 16:47 /bin/setsid
-rwxr-xr-x 1 root root 67744 Aug 19 16:47 /usr/bin/setsid
--dpkg util-linux--
ii  util-linux                2.39.3-9ubuntu6.6                arm64        miscellaneous system utilities
--bash--
-rwxr-xr-x 1 root root 1543048 Mar 31  2024 /bin/bash
DOCKER_RUN_EXIT=0

$ docker run --rm --entrypoint sh eclipse-temurin:21-jre-noble -c 'dpkg -S /usr/bin/setsid; setsid --version | head -2'
util-linux: /usr/bin/setsid
setsid from util-linux 2.39.3
```

镜像标识：`sha256:d35199d74a3b2dff1bfb435d9adde4ef974e74a7d2a4fcb3079063c55926edb5`
（RepoDigest `eclipse-temurin@sha256:d35199d74a3b...`，Created 2026-09-09T02:17:53Z）

**判定：`setsid` 存在（`/usr/bin/setsid` 与 `/bin/setsid` 同源，属 util-linux 2.39.3 核心包，无需 util-linux-extra），`/bin/bash` 存在。**

### 7.3 本仓库构建镜像内的同样探测

```console
$ docker images | grep -i zhikun
zhikuncode-meoo-cli:review       069483024073   360MB
zhikuncode:latest                a2eb6742487e   1.65GB
zhikuncode:local-prepush         b71989fa7b76   2.29GB
zhikuncode:local-ready           351f30877512   2.29GB
zhikuncode:local-review          c790749f273b   2.29GB
zhikuncode:meoo-review           7e47cf3b1ab2   2.45GB
zhikuncode:test                  4a5ce239a6d8   1.65GB
zhikuncode:verification-fix      ddb9d1a34856   3.4GB
```

`zhikuncode:meoo-review`（Created 2026-09-15，与当前 Dockerfile 形态一致的生产镜像）：

```
user=zhikun
PRETTY_NAME="Ubuntu 24.04.4 LTS"
/usr/bin/setsid            # command -v setsid，exit=0
-rwxr-xr-x 1 root root 1543048 Mar 31  2024 /bin/bash
-rwxr-xr-x 1 root root   67744 Aug 19 16:47 /usr/bin/setsid
util-linux: /usr/bin/setsid
/app/app.jar
/app/python-service
```

`zhikuncode:latest`（Created 2026-04-22，Ubuntu 22.04.5 旧形态）：

```
user=zhikun
PRETTY_NAME="Ubuntu 22.04.5 LTS"
/usr/bin/setsid            # exit=0（jammy 实例 14496 bytes）
/app/app.jar
/app/python-service
```

### 7.4 与代码的关联（为什么这是关键证据）

- 根 `Dockerfile` 生产运行阶段：`FROM eclipse-temurin:21-jre-noble AS runtime`（Ubuntu 24.04/noble 基础）。
- 本提交新增 `backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java`：
  ```java
  Path setsid = Files.isExecutable(Path.of("/usr/bin/setsid"))
          ? Path.of("/usr/bin/setsid") : Path.of("/bin/setsid");
  if (!Files.isExecutable(setsid) || !Files.isExecutable(Path.of("/bin/bash"))) {
      throw new IOException("PROCESS_GROUP_UNAVAILABLE: setsid and bash are required on Linux");
  }
  ```
  即在 Linux 上若缺 `setsid` 或 `/bin/bash`，进程组管理直接抛异常。
- 实测证据表明：**基础镜像与该仓库构建的生产镜像内 setsid/bash 均存在，生产部署不会触发 `PROCESS_GROUP_UNAVAILABLE`。**

---

## 8. CI 配置检查（.github/workflows/）

存在 3 个 workflow：`ci.yml`、`security.yml`、`task3-5-regression.yml`。

### ci.yml（push main / 所有 PR）

| Job | 运行平台 | 是否覆盖本次新测试 |
|---|---|---|
| backend-build | ubuntu-latest，JDK 21 (temurin)，`./mvnw test -B -q` | **是**——全量 Java 测试，含 8 个新类；且 Linux 下 `OwnedProcessTest` 的 6 个 `@EnabledOnOs(OS.LINUX)` 用例**不再跳过**，会真正覆盖 setsid 进程组路径 |
| python-test | ubuntu-latest，Python 3.11，`pip install -r requirements.txt && pip install -e . && pip install pytest pytest-asyncio httpx`，`pytest tests/ -v --ignore=tests/integration -k "not api_key and not llm"` | **是**——4 个新文件全部收集；本机实测 `-k` 过滤后收集数 230 = 全量 230，未被过滤；`tests/integration` 目录不存在（--ignore 为 no-op） |
| frontend-build | ubuntu-latest，npm ci + npm run build | 无关 |
| docker-build | 仅 push main，build Dockerfile（内部装 playwright chromium） | 无测试 |

- 新 Python 测试**不需要** CI 安装浏览器二进制：4 个文件用 mock 驱动（`SimpleNamespace/AsyncMock`），
  `test_real_playwright_*` 用 `object.__new__(DriverContext)` 直接调用 SDK 方法而不 launch 浏览器；
  唯一需要真实容器的 `test_browser_resource_container.py` 靠 `ZHIKUN_BROWSER_CONTAINER_TEST=1` 门禁，CI 未设置 → 自动 skip。
- 风险点：ci.yml 的 Python job **无** `playwright install` 步骤，但如上分析不构成失败；
  backend job 跑全量，会包含本机出现的时序敏感用例（ManagedProcessRunnerTest 等），加载高的 runner 上有 flaky 风险。

### task3-5-regression.yml（PR 命中路径 / 每日 cron / 手动）

- p0-smoke 触发路径包含 `python-service/src/services/browser_service.py` —— **本提交修改了该文件，会触发本 workflow**。
  - Java 步骤只跑 `./mvnw -q test -DskipITs -Dtest='CoordinatorEventBusTest,VisualizationIntentClassifierTest'`（与本提交 8 个类无关）→ 新 Java 测试在此 workflow 不跑（但在 ci.yml 会跑）。
  - Python 步骤跑 `pip install -e '.[test]' && pytest -q tests/`（全量）→ **新 Python 测试会在此运行**；playwright chromium 的安装在 Python 步骤之后（仅前端 E2E 用）。
- p1-nightly（schedule）：backend `./mvnw verify -Pcoverage`（全量+覆盖率门）、frontend、python `pytest --cov=src --cov-fail-under=70`、全量 E2E。

### security.yml（push main / 每周一）

npm-audit、pip-audit、trivy 镜像扫描（`docker build -t zhikuncode:scan .`），不含单元测试。

### CI 判定汇总

1. 8 个新 Java 类与 4 个新 Python 文件**都会在 CI 中被执行**（ci.yml；task3-5 p0 另跑 Python 全量），不是"新增但未接入 CI"。
2. CI 为 ubuntu-latest（Linux）：`OwnedProcessTest` 不再跳过，setsid 路径会被真实覆盖；本机 macOS 的 6 个 skip 在 CI 不存在。
3. 未见"环境缺失导致必然失败"的新测试依赖（无真实浏览器/API Key 需求）；但新增/既有若干时序敏感断言（50ms cleanup 窗口、取消/超时区分、并发清理、500ms 性能断言）在加载较重的共享 runner 上存在 flaky 风险，本地已观测到负载下的偶发失败。

---

## 9. 清洁性检查

```console
$ cd /Users/guoqingtao/Desktop/dev/code/zhikuncode && git status --short
（无输出，0 行）
```

- **未修改任何被跟踪的源码/配置/测试文件**（`git status --short` 与 `git diff --stat` 均为空）。
- 测试产生的构建产物（均被 git 忽略，未污染工作树）：
  - `backend/target/`（classes、test-classes、surefire-reports 等）；
  - `python-service` 下 `__pycache__`（本次 pytest 使用 `-p no:cacheprovider`，未生成 .pytest_cache）；
  - 仓库外：`/tmp/zhikun_verify/*`（运行日志，保留）；`/tmp/zhikun_parent/`（父提交快照，位于 /tmp，不影响仓库，对照运行结束后已删除）。
- 未执行 start.sh/stop.sh/docker compose up；未 kill 任何非测试进程；未绑定 8080 等应用端口（本机原有 8080/8000/5173 开发服务保持运行）。

---

## 10. 局限与注意事项

1. **平台差异**：本机 macOS；`OwnedProcessTest` 6 个 Linux 专用用例跳过，`OwnedProcess` 的 setsid 真实路径只能在 Linux（CI 或容器）验证。本次通过 Docker 验证了生产镜像内 setsid/bash 可用（§7）。
2. **负载干扰**：Java 定向第 1 次运行与 Docker 探测并发；Python 全量与 Java 全量并发。观察到的 3 类时序失败（ManagedProcessRunnerTest、ConcurrencyControlTest、browser 生命周期用例）在空载下均通过——属于负载敏感，而非稳定失败。建议 CI/机器空载时复核。
3. **既有失败**：4 个类共 11 个确定性失败在父提交 HEAD~1 上同样复现（§4.2），与本次提交无关，但会让 `mvn test` 全量在**本机**始终 BUILD FAILURE。
4. 容器专用测试 `test_browser_resource_container.py` 未实际执行（需 `ZHIKUN_BROWSER_CONTAINER_TEST=1` 的隔离 Linux 容器），本地与 CI 均为 skip。

---

## 附录：本次执行的完整命令清单

```bash
# 环境
git status --short; git rev-parse HEAD; git log -1 --format='%H %s %ci'
java -version; mvn -version; cd backend && ./mvnw -version
cd python-service && .venv/bin/python --version && .venv/bin/python -m pytest --version

# Java 定向（3 次）
cd backend && ./mvnw -Dtest='PythonProcessManagerTest,ProcessTreeManagerTest,BashToolFailureClassificationTest,ManagedProcessRunnerTest,OwnedProcessTest,BrowserVerifierTest,DevServerLauncherTest,VerifyJourneyEdgeCaseTest' -DfailIfNoTests=false test    # ×2
cd backend && ./mvnw -Dtest='ManagedProcessRunnerTest' -DfailIfNoTests=false test

# Java 全量 + 失败类隔离 + 父提交对照
cd backend && ./mvnw test -B
cd backend && ./mvnw -Dtest='CoordinatorServiceTest,SecurityFilterIntegrationTest,WorkspaceFileBoundaryTest,ConcurrencyControlTest,OneKeyRegistryIntegrationTest' -DfailIfNoTests=false test
git archive HEAD~1 | tar -x -C /tmp/zhikun_parent
cd /tmp/zhikun_parent/backend && ./mvnw -Dtest='CoordinatorServiceTest,SecurityFilterIntegrationTest,WorkspaceFileBoundaryTest,OneKeyRegistryIntegrationTest' -DfailIfNoTests=false test

# Python 定向（2 次）+ 全量 + 失败复测
cd python-service && .venv/bin/python -m pytest tests/test_browser_resource_container.py tests/test_browser_service_lifecycle.py tests/test_journey_lifecycle.py tests/test_journey_publication.py -q -p no:cacheprovider
cd python-service && .venv/bin/python -m pytest tests/test_browser_service_lifecycle.py tests/test_journey_lifecycle.py tests/test_journey_publication.py -q -p no:cacheprovider
cd python-service && .venv/bin/python -m pytest tests -q -p no:cacheprovider
# 失败用例隔离/重复复测（负载下与空载，各 3–5 次）

# Docker/setsid
docker version; docker ps; docker images | grep -i zhikun
docker run --rm --entrypoint sh eclipse-temurin:21-jre-noble -c '...(os-release / command -v setsid / ls / dpkg)'
docker run --rm --entrypoint sh eclipse-temurin:21-jre-noble -c 'dpkg -S /usr/bin/setsid; setsid --version'
docker run --rm --entrypoint sh zhikuncode:meoo-review -c '...(同上探测)'
docker run --rm --entrypoint sh zhikuncode:latest -c '...(同上探测)'
docker image inspect eclipse-temurin:21-jre-noble --format '{{.Id}} {{.RepoDigests}} {{.Created}}'

# 清洁性
cd /Users/guoqingtao/Desktop/dev/code/zhikuncode && git status --short
```

原始日志留存位置（仓库外）：`/tmp/zhikun_verify/{java_targeted.log,java_targeted_run2.log,java_managed_rerun1.log,java_full.log,java_isolation_rerun.log,java_parent_run.log,python_targeted_run1.log,python_targeted_run2.log,python_full.log}`

