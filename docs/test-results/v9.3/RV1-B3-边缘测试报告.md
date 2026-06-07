# RV-1 & B3 边缘测试报告

> **报告版本**: v1.1 | **测试日期**: 2026-06-07 | **测试范围**: RV-1 运行时验证（VerifyJourneyTool）+ B3 证据包存储（EvidenceStore）边缘场景
> **执行口径**: `./mvnw test -pl . -Dtest="VerifyJourneyEdgeCaseTest,EvidenceStoreEdgeCaseTest" -DfailIfNoTests=false`
> **总体结果**: **PASS** — 32/32 全部通过，0 Failure / 0 Error / 0 Skipped

---

## 执行摘要

| 指标 | 值 |
|------|---|
| 测试日期 | 2026-06-07 |
| 测试范围 | RV-1 运行时验证 + B3 证据包存储 边缘场景 |
| 总用例数 | 32 |
| PASS | 32 |
| FAIL | 0 |
| 通过率 | 100% |
| 测试用例累计执行时间 | 2.748 s（VerifyJourney 1.302 s + EvidenceStore 1.446 s） |
| Maven 总耗时 | 12.731 s |
| 完成时间戳 | 2026-06-07T20:59:23+08:00 |

---

## 通过率矩阵

| 模块 | 用例数 | PASS | FAIL | 通过率 |
|------|--------|------|------|--------|
| VerifyJourneyTool 边缘（RV-1） | 17 | 17 | 0 | 100% |
| EvidenceStore 边缘（B3） | 15 | 15 | 0 | 100% |
| **合计** | **32** | **32** | **0** | **100%** |

---

## §1 RV-1 运行时验证边缘测试

### §1.1 VerifyJourneyTool 边缘场景 (17/17 PASS)

**用例源文件**: [VerifyJourneyEdgeCaseTest.java](../../../backend/src/test/java/com/aicodeassistant/verify/VerifyJourneyEdgeCaseTest.java)
**测试集耗时**: 1.302 s

#### 输入校验类

**TC-VT-EC-01: 空 journey 数组 — PASS**
- **入参**: `journey=[]` 的空列表
- **预期**: 返回 error 类型 ToolResult，提示 journey 必须非空，且不触发 verifierFactory/devServerLauncher/evidenceStore
- **实际**: `result.isError()=true`，错误信息含 `non-empty`；`verifierFactory.selectVerifier`、`devServerLauncher.start`、`evidenceStore.save` 均未被调用
- **执行耗时**: 0.003 s
- **判定**: PASS

**TC-VT-EC-02: 缺失 journey 字段 — PASS**
- **入参**: 仅含 `base_url=http://localhost:5173`，无 journey 字段
- **预期**: 返回 error，错误信息提及 journey
- **实际**: `result.isError()=true`，content 含 `journey`；verifierFactory 未被调用
- **执行耗时**: 0.002 s
- **判定**: PASS

**TC-VT-EC-03: 超大 journey（1000 步）http_api 模式透传 — PASS**
- **入参**: 1000 个 `http_get` 步骤 + `verification_mode=http_api` + `base_url=http://127.0.0.1:8080`
- **预期**: 不做强制截断，HttpApiVerifier 收到全部步骤，成功消息反映实际步数 1000
- **实际**: `result.isError()=false`，content 含 `1000 steps`；EvidenceStore.save 调用一次
- **执行耗时**: 0.019 s
- **判定**: PASS

#### DevServerLauncher 异常类

**TC-VT-EC-04: start_command 不存在 — PASS**
- **入参**: 浏览器模式 + `start_command=nonexistent-cmd-xyz`，launcher 抛 RuntimeException("command not found")
- **预期**: 工具返回 error，外层 catch 包装为 `VerifyJourney failed`；不进入 evidenceStore
- **实际**: `result.isError()=true`，content 含 `VerifyJourney failed`；`verifyNoInteractions(evidenceStore)` 通过
- **执行耗时**: 0.005 s
- **判定**: PASS

**TC-VT-EC-05: npm install 超时 — PASS**
- **入参**: 浏览器模式，launcher 抛 `npm install timed out after 300s`
- **预期**: 工具返回 error，错误信息保留底层根因
- **实际**: `result.isError()=true`，content 含 `npm install timed out`
- **执行耗时**: 0.005 s
- **判定**: PASS

**TC-VT-EC-06: HTTP 轮询超时（DevServerTimeoutException） — PASS**
- **入参**: 浏览器模式，launcher 抛 `DevServerTimeoutException(5173, 120s, logTail)`，logTail = `[vite] error: failed to bind 0.0.0.0:5173`
- **预期**: 错误信息含 `Dev server failed to start within 120s` 与日志尾巴
- **实际**: `result.isError()=true`，content 同时含上述两段
- **执行耗时**: 0.007 s
- **判定**: PASS

**TC-VT-EC-07: 端口被占用（EADDRINUSE） — PASS**
- **入参**: launcher 抛 `EADDRINUSE: address already in use :::5173`
- **预期**: 错误信息保留 `EADDRINUSE` 关键字
- **实际**: `result.isError()=true`，content 含 `EADDRINUSE`
- **执行耗时**: 0.007 s
- **判定**: PASS

#### 能力降级类

**TC-VT-EC-16: BROWSER_AUTOMATION 能力不可用 → success 降级 — PASS**
- **入参**: 浏览器模式（auto），`pythonClient.isCapabilityAvailable("BROWSER_AUTOMATION")` 返回 `false`
- **预期**: 返回 success（`isError()=false`），content 含 `Runtime verification unavailable` 与 `BROWSER_AUTOMATION`；不启动 DevServer、不保存证据、不进入 verifier.verify
- **实际**: `result.isError()=false`；content 包含 `Runtime verification unavailable` 和 `BROWSER_AUTOMATION`；`devServerLauncher.start` 未调用；`evidenceStore.save` 未调用；`mockBrowser.verify` 未调用
- **执行耗时**: 0.002 s
- **判定**: PASS

**TC-VT-EC-17: HTTP_API 能力不可用 → success 降级 — PASS**
- **入参**: `journey=[{action:http_get, url:/ping}]` + `verification_mode=http_api` + `base_url=http://127.0.0.1:8080`，`pythonClient.isCapabilityAvailable("HTTP_API")` 返回 `false`
- **预期**: 返回 success（`isError()=false`），content 含 `HTTP_API capability not available`；不进入 verifier.verify、不保存证据
- **实际**: `result.isError()=false`；content 包含 `HTTP_API capability not available`；`mockHttp.verify` 未调用；`evidenceStore.save` 未调用
- **执行耗时**: 0.003 s
- **判定**: PASS

#### 技术栈检测类

**TC-VT-EC-08: 无 package.json 与 index.html — PASS**
- **入参**: 真实 PreviewStackDetector 在空临时目录上 detect，配合 BrowserVerifier 桩
- **预期**: 检测为 unknown 栈（defaultPort=0），工具短路返回 success "unsupported stack"，不启动 dev server
- **实际**: `info.stackId()="unknown"`、`defaultPort=0`；`result.isError()=false`，content 含 `unsupported stack`；`devServerLauncher.start` 从未调用
- **执行耗时**: 0.003 s
- **判定**: PASS

**TC-VT-EC-09: package.json 非法 JSON — PASS**
- **入参**: 写入 `{ this is not valid json :: ` 到 package.json
- **预期**: 检测器吞掉 IOException，回退 unknown / port=0 / startCommand=""
- **实际**: 三个字段均符合预期，未抛出异常
- **执行耗时**: 0.013 s
- **判定**: PASS

#### 验证执行类

**TC-VT-EC-10: Python 服务不可达 — PASS**
- **入参**: BrowserVerifier 返回 `JourneyResult.failed("PYTHON_CALL_FAILED", "Python service unreachable or timeout")`
- **预期**: 工具返回 error；失败仍保存证据并推送 verify_attention；finally 必然 stop dev server
- **实际**: `result.isError()=true`，content 含 `Python service unreachable`；`evidenceStore.save` 调用 1 次、`notificationService.sendVerifyAttention` 调用 1 次、`devServerLauncher.stop(handle)` 调用 1 次
- **执行耗时**: 0.003 s
- **判定**: PASS

**TC-VT-EC-11: Python 返回非法 JSON 等价失败 — PASS**
- **入参**: BrowserVerifier 模拟 callIfAvailable 返回 Optional.empty 后的 failed verdict
- **预期**: 工具返回 error，失败结果含证据包标识便于追溯
- **实际**: `result.isError()=true`，content 含 `evidence bundle`
- **执行耗时**: 0.011 s
- **判定**: PASS

**TC-VT-EC-12: 验证超时不阻塞工具层 — PASS**
- **入参**: BrowserVerifier 模拟 120s 超时返回 failed
- **预期**: 工具立即返回 error，本地耗时 < 5 000 ms（不复制下游等待）
- **实际**: `result.isError()=true`，elapsed=0.006 s，远低于 5 s 阈值
- **执行耗时**: 0.006 s
- **判定**: PASS

#### 容错类

**TC-VT-EC-13: EvidenceStore.save 抛异常 — PASS**
- **入参**: BrowserVerifier 返回 verified；`evidenceStore.save` 抛 RuntimeException("DB connection lost")
- **预期**: 浏览器模式外层 catch 兜底，返回 error 而非未捕获异常，前缀 `VerifyJourney failed`
- **实际**: `result.isError()=true`，content 含 `VerifyJourney failed`；日志记录 `VerifyJourney failed with unexpected exception` 验证 catch 路径已触发
- **执行耗时**: 1.160 s（含 mockito 自附加 inline-mock-maker 启动开销）
- **判定**: PASS

**TC-VT-EC-14: STOMP 推送异常不影响 verdict — PASS**
- **入参**: messagingTemplate.convertAndSendToUser 抛 `STOMP broker disconnected`；BrowserVerifier 返回 verified；EvidenceStore 返回 bundleId=`ev-edge14`
- **预期**: STOMP 异常被 log.warn 吞掉，主流程返回成功（PASSED 文本 + bundleId）
- **实际**: `result.isError()=false`，content 含 `PASSED` 与 `ev-edge14`
- **执行耗时**: 0.024 s
- **判定**: PASS

**TC-VT-EC-15: finally 阶段 DevServer.stop 抛异常 — PASS**
- **入参**: `devServerLauncher.stop(handle)` 抛 `kill -9 failed: permission denied`；BrowserVerifier 返回 verified
- **预期**: 清理阶段异常被 log.warn 吞掉；主返回值仍为 PASSED；stop 仍被调用过（即使抛异常）
- **实际**: `result.isError()=false`，content 含 `PASSED`；`devServerLauncher.stop(handle)` 调用 1 次
- **执行耗时**: 0.009 s
- **判定**: PASS

---

## §2 B3 证据包存储边缘测试

### §2.1 EvidenceStore 边缘场景 (15/15 PASS)

**用例源文件**: [EvidenceStoreEdgeCaseTest.java](../../../backend/src/test/java/com/aicodeassistant/verify/EvidenceStoreEdgeCaseTest.java)
**测试集耗时**: 1.446 s
**隔离策略**: 通过 JUnit5 @TempDir 提供临时目录并显式注入 blob 根目录

#### Blob 写入类

**TC-EC-01: 10MB+ 大文件写入 SHA-256 一致 — PASS**
- **入参**: `byte[10 * 1024 * 1024 + 7]`，固定种子 Random(42) 填充
- **预期**: 返回 SHA-256 等于内容哈希；落盘字节数与原始一致；回读逐字节相同
- **实际**: `expected==returned`、`Files.size==payload.length`、`assertArrayEquals` 通过
- **执行耗时**: 0.152 s
- **判定**: PASS

**TC-EC-02: 多线程并发写入相同内容幂等 — PASS**
- **入参**: 10 线程同时调用 `saveBlob("concurrent payload — same sha".getBytes())`
- **预期**: 所有线程返回相同 SHA-256；最终文件字节与原始一致，无数据竞态损坏
- **实际**: 10 个 Future 全部返回同一 SHA；最终文件 `assertArrayEquals` 通过
- **执行耗时**: 0.005 s
- **判定**: PASS

**TC-EC-03: 文件系统创建目录失败抛 RuntimeException — PASS**
- **入参**: 把 `.ai-code-assistant/blobs` 占位为常规文件，使 `Files.createDirectories(blobs/<prefix>)` 失败
- **预期**: 抛 RuntimeException，消息含 `Failed to save blob`；cause 为 IOException
- **实际**: 抛出符合预期的 RuntimeException，cause instance of IOException
- **执行耗时**: 0.091 s
- **判定**: PASS

#### Blob 读取类

**TC-EC-04: SHA-256 长度 62（短 2 位）返回 empty — PASS**
- **入参**: `"a".repeat(62)`
- **预期**: `Optional.empty()`
- **实际**: `readBlob.isEmpty()=true`
- **执行耗时**: 0.001 s
- **判定**: PASS

**TC-EC-05: SHA-256 长度 65（长 1 位）返回 empty — PASS**
- **入参**: `"a".repeat(65)`
- **预期**: `Optional.empty()`
- **实际**: `readBlob.isEmpty()=true`
- **执行耗时**: 0.001 s
- **判定**: PASS

**TC-EC-06: 路径穿越输入被长度校验拦截 — PASS**
- **入参**: `../../etc/passwd`、`..`、`/` + 63 字符、`..` + 62 字符（构造长度恰为 64 的路径形态）
- **预期**: 全部返回 `Optional.empty()`，不读取到工作目录之外的诱饵文件
- **实际**: 4 类入参全部 `isEmpty()=true`
- **执行耗时**: 0.001 s
- **判定**: PASS

**TC-EC-07: blob 落盘后被外部删除返回 empty — PASS**
- **入参**: 先 `saveBlob("to be deleted")`，再 `Files.delete(blobPath)`
- **预期**: `readBlob(sha)` 降级为 `Optional.empty()`，不抛异常
- **实际**: `isEmpty()=true`，无异常抛出
- **执行耗时**: 0.004 s
- **判定**: PASS

**TC-EC-08: 空字符串入参返回 empty — PASS**
- **入参**: `""`
- **预期**: `Optional.empty()`
- **实际**: `isEmpty()=true`
- **执行耗时**: 0.001 s
- **判定**: PASS

**TC-EC-09: null 入参返回 empty 而非 NPE — PASS**
- **入参**: `null`
- **预期**: 不抛异常，返回 `Optional.empty()`
- **实际**: `assertDoesNotThrow` 通过；`isEmpty()=true`
- **执行耗时**: 0.001 s
- **判定**: PASS

#### 去重机制类

**TC-EC-10: 相同内容两次调用文件只写入一次（mtime 不变） — PASS**
- **入参**: 两次调用 `saveBlob("duplicate-write-check".getBytes())`，中间 `Thread.sleep(1100)`
- **预期**: 两次返回 SHA 相同；文件 mtime 保持不变（去重命中跳过实际写入）
- **实际**: `sha1.equals(sha2)`；`mtime1.equals(mtime2)`
- **执行耗时**: 1.110 s（含 1100 ms sleep）
- **判定**: PASS

**TC-EC-11: 不同内容前 2 字符相同时落盘到同一分片目录 — PASS**
- **入参**: 暴力枚举 `"payload-" + i` 寻找前 2 字符相同的 SHA-256 碰撞
- **预期**: 两个文件均出现在 `blobs/<prefix>/` 下，字节内容各自正确
- **实际**: 两个文件存在；`assertArrayEquals` 双向通过
- **执行耗时**: 0.021 s
- **判定**: PASS

#### 数据库操作类

**TC-EC-12: findById 不存在的 bundleId 跳过 items 查询 — PASS**
- **入参**: `bundleId="ev-ghost"`，jdbcTemplate 桩返回空列表
- **预期**: 返回 `Optional.empty()`；不查 evidence_items 表
- **实际**: `result.isEmpty()=true`；`verify(jdbcTemplate, never())` 校验 items 查询未发生
- **执行耗时**: 0.009 s
- **判定**: PASS

**TC-EC-13: findBySession 无匹配返回空列表（非 null） — PASS**
- **入参**: `sessionId="ghost-session"`，jdbcTemplate 桩返回空列表
- **预期**: 返回非 null 空 List；不查 evidence_items
- **实际**: `result != null && result.isEmpty()`；items 查询未触发
- **执行耗时**: 0.001 s
- **判定**: PASS

**TC-EC-14: meta 含循环引用时序列化降级为 null — PASS**
- **入参**: `Map cyclicMeta` 自引用 `cyclicMeta.put("self", cyclicMeta)`
- **预期**: 不抛异常；bundle 表正常 INSERT；item 表 meta_json 列写入 null
- **实际**: `assertDoesNotThrow` 通过；`verify(jdbcTemplate).update(...)` 两条 SQL 参数全部匹配（含 meta_json=null）
- **执行耗时**: 0.032 s
- **判定**: PASS

#### 并发安全类

**TC-EC-15: 10 线程并发写入不同 bundle 全部成功 — PASS**
- **入参**: 10 个 `EvidenceBundle("ev-c-0..9", "sess-c", ..., List.of())` 并发 save
- **预期**: 全部返回非 null；bundleId 与索引一一对应；jdbcTemplate.update 累计调用 10 次
- **实际**: 所有 Future 在 10 s 内完成；bundleId 顺序匹配；`verify(jdbcTemplate, times(10)).update(...)` 通过
- **执行耗时**: 0.005 s
- **判定**: PASS

---

## §3 发现与建议

### §3.1 测试结果总体评估

- **32/32 全部通过，0 Failure / 0 Error / 0 Skipped**，RV-1 与 B3 模块在异常输入、依赖故障、文件系统故障、并发竞争、清理阶段异常、能力降级等边缘路径上的容错与降级语义符合实现预期。
- VerifyJourneyTool 在「失败可见、降级可控、清理收敛、能力降级」四个语义维度均被显式断言覆盖：错误兜底（EC-04/13）、根因透传（EC-05/06/07/10）、finally 清理（EC-15）、不阻塞主流（EC-12）、推送降级（EC-14）、能力不可用优雅降级（EC-16/17）。
- EvidenceStore 在「内容寻址完整性、去重幂等、长度校验、并发安全、序列化容错」五个维度均被显式断言覆盖：大文件 SHA-256（EC-01）、并发幂等（EC-02/15）、长度校验拦截路径穿越（EC-04/05/06）、null/空字符串防御（EC-08/09）、循环引用 catch 降级（EC-14）。

### §3.2 观察到的非阻断告警（不影响判定）

测试运行期间日志中出现以下非阻断告警，建议作为后续工程改进项跟进：

1. **Mockito inline-mock-maker 自附加告警**
   原文：`Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build...`
   **建议**: 在 surefire `argLine` 中显式以 `-javaagent` 方式挂载 mockito-core，避免未来 JDK 升级（JDK 23+）后 mock 失效。

2. **Log4j2 LOG_DIR 环境变量解析死循环告警**
   原文：`WARN Infinite loop in property interpolation of LOG_DIR->env:LOG_DIR`，启动期出现 9 次。
   **建议**: log4j2 配置中 `${env:LOG_DIR}` 在测试环境下未注入该环境变量，建议在测试 profile 中给定 `-DLOG_DIR=target/test-logs` 或在 `log4j2-test.xml` 中提供默认值。

3. **Jackson StackOverflowError（循环引用序列化）**
   `EvidenceStoreEdgeCaseTest` 中 `save_metaWithCircularReference_serializesAsNullGracefully` 用例触发 Jackson `MapSerializer` 的递归序列化，最终抛出 StackOverflowError 被 EvidenceStore 内部 catch 并降级为 null。此为预期行为，但日志中产生大量堆栈输出（~400 行）。生产环境中循环引用 meta 出现概率极低，可考虑在序列化前做 `SerializationFeature.FAIL_ON_SELF_REFERENCES` 配置以快速失败减少日志噪音。

### §3.3 边缘覆盖建议（用于后续迭代）

- **VerifyJourneyTool**: 当前 EC-03 验证 1000 步 journey 透传，但未覆盖「单步入参体积过大」（如单个 `screenshot` 步骤携带 50MB 截图 base64）的内存防护场景，建议下一轮新增 `journey 单步 payload 上限` 边缘用例。
- **EvidenceStore**: 当前 EC-11 通过暴力枚举构造前 2 字符相同的 SHA-256 碰撞，依赖 SHA-256 输出分布；建议在 `@RepeatedTest(N)` 形式下补充统计稳定性，或改为预生成 fixture 以提升 CI 确定性。
- **EvidenceStore 跨平台**: EC-10 通过 `Thread.sleep(1100)` 规避 macOS APFS 与 Linux ext4 的 mtime 分辨率差异，目前已能稳定通过；建议后续改为 `Files.getAttribute` 直接比较文件 inode 或 size 时间戳，规避 sleep 引入的 1.110 s 测试耗时。

---

## §4 测试环境

| 组件 | 版本 |
|------|------|
| JDK | OpenJDK 21.0.10+7-LTS (Amazon Corretto, aarch64) |
| Maven | Apache Maven 3.9.9 (mvnw 包装器) |
| Surefire Plugin | 3.5.4（`junit-platform` provider） |
| JUnit Jupiter | 5.11.4 |
| JUnit Platform | 1.11.4 |
| Mockito | inline-mock-maker（运行期自附加） |
| OS | macOS 26.5.1 (Darwin, aarch64 / Apple Silicon) |
| Locale | zh_CN_#Hans / UTF-8 |
| 项目模块 | `com.aicode:ai-code-assistant-backend:1.0.0` |
| SQLite JDBC | 3.45.1.0（来自 `backend/pom.xml`） |
| 构建产物 | `backend/target/surefire-reports/` |

---

## §5 测试命令与时间戳

**执行命令**:

```bash
cd /Users/guoqingtao/Desktop/dev/code/zhikuncode/backend
./mvnw test -pl . \
  -Dtest="VerifyJourneyEdgeCaseTest,EvidenceStoreEdgeCaseTest" \
  -DfailIfNoTests=false 2>&1
```

**完成时间戳**: `2026-06-07T20:59:23+08:00`
**Maven 总耗时**: `12.731 s`
**测试集累计耗时**: `2.748 s`（VerifyJourney 1.302 s + EvidenceStore 1.446 s）

---

## §6 mvn test 原始输出摘要

### §6.1 输出头部（前 50 行节选 / 已去除 Jackson StackOverflow 重复堆栈）

```
[INFO] Scanning for projects...
[INFO]
[INFO] ----------------< com.aicode:ai-code-assistant-backend >----------------
[INFO] Building AI Code Assistant Backend 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO]
[INFO] --- resources:3.3.1:resources (default-resources) @ ai-code-assistant-backend ---
[INFO] Copying 1 resource from src/main/resources to target/classes
[INFO] Copying 16 resources from src/main/resources to target/classes
[INFO]
[INFO] --- compiler:3.13.0:compile (default-compile) @ ai-code-assistant-backend ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- resources:3.3.1:testResources (default-testResources) @ ai-code-assistant-backend ---
[INFO] Copying 7 resources from src/test/resources to target/test-classes
[INFO]
[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ ai-code-assistant-backend ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- surefire:3.5.4:test (default-test) @ ai-code-assistant-backend ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO]
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.aicodeassistant.verify.VerifyJourneyEdgeCaseTest
Mockito is currently self-attaching to enable the inline-mock-maker. (...告警，详见 §3.2)
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes...
2026-06-07T12:59:21 main WARN Infinite loop in property interpolation of LOG_DIR->env:LOG_DIR (...×9, 详见 §3.2)
2026-06-07 20:59:21.689 WARN  [main] com.aicodeassistant.tool.verify.VerifyJourneyTool
  - VerifyJourney failed with unexpected exception
java.lang.RuntimeException: DB connection lost
        at com.aicodeassistant.verify.EvidenceStore.save(EvidenceStore.java:48)
        at com.aicodeassistant.tool.verify.VerifyJourneyTool.handleVerificationResult
            (VerifyJourneyTool.java:302)
        at com.aicodeassistant.tool.verify.VerifyJourneyTool.call
            (VerifyJourneyTool.java:234)
        at com.aicodeassistant.verify.VerifyJourneyEdgeCaseTest
            .testEvidenceStoreSaveThrows_doesNotCrashTool(VerifyJourneyEdgeCaseTest.java:355)
（注：上述堆栈是 TC-VT-EC-13 用例的预期负面路径触发的 log.warn，
 已被外层 catch 捕获并转换为 error 结果，属正常断言流程）
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.302 s
  -- in com.aicodeassistant.verify.VerifyJourneyEdgeCaseTest
[INFO] Running com.aicodeassistant.verify.EvidenceStoreEdgeCaseTest
（...Jackson MapSerializer StackOverflowError 堆栈 ~400 行，TC-EC-14 预期触发，详见 §3.2）
```

### §6.2 输出尾部（最后 20 行节选）

```
2026-06-07 20:59:23.343 DEBUG [pool-4-thread-1]  com.aicodeassistant.verify.EvidenceStore
  - Blob saved: 3490fccc694df64a9be71e88d10a86c7e5bd24027252ff983f8f6f2bcc07fc30
2026-06-07 20:59:23.343 DEBUG [pool-4-thread-10] com.aicodeassistant.verify.EvidenceStore
  - Blob saved: 3490fccc694df64a9be71e88d10a86c7e5bd24027252ff983f8f6f2bcc07fc30
2026-06-07 20:59:23.343 DEBUG [pool-4-thread-2]  com.aicodeassistant.verify.EvidenceStore
  - Blob saved: 3490fccc694df64a9be71e88d10a86c7e5bd24027252ff983f8f6f2bcc07fc30
2026-06-07 20:59:23.343 DEBUG [pool-4-thread-6]  com.aicodeassistant.verify.EvidenceStore
  - Blob saved: 3490fccc694df64a9be71e88d10a86c7e5bd24027252ff983f8f6f2bcc07fc30
2026-06-07 20:59:23.344 DEBUG [pool-4-thread-4]  com.aicodeassistant.verify.EvidenceStore
  - Blob saved: 3490fccc694df64a9be71e88d10a86c7e5bd24027252ff983f8f6f2bcc07fc30
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.446 s
  -- in com.aicodeassistant.verify.EvidenceStoreEdgeCaseTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  12.731 s
[INFO] Finished at: 2026-06-07T20:59:23+08:00
[INFO] ------------------------------------------------------------------------
```

### §6.3 Surefire 结果文件指引

- 文本摘要: `backend/target/surefire-reports/com.aicodeassistant.verify.VerifyJourneyEdgeCaseTest.txt`
- 文本摘要: `backend/target/surefire-reports/com.aicodeassistant.verify.EvidenceStoreEdgeCaseTest.txt`
- XML 详细记录: `backend/target/surefire-reports/TEST-com.aicodeassistant.verify.VerifyJourneyEdgeCaseTest.xml`
- XML 详细记录: `backend/target/surefire-reports/TEST-com.aicodeassistant.verify.EvidenceStoreEdgeCaseTest.xml`

---

**报告结束**
