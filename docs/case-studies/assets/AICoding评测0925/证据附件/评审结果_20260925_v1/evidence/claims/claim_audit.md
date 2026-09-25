# 同轮八份报告逐项主张与证据审计

本审计不做最终排名。已逐份读完 E01–E07 全文；结构化提取见 `claims.json`，包含原始严重度、报告自身行号、简短原话、完整对应段落、声明的源码位置、测试宣称、证据可见性和审计注记。摘要中的重复条目不重复计数；包含有实质含义的正向保证与补充项。

报告标题/自报名称可见，不能称双盲。没有运行测试，没有修改被审仓库。只对争议点做静态核查，并检查报告直接提及的日志和探针路径是否可见。缺日志不是捏造证据；文件存在也不等于所有测试或因果结论已认证。

## 最关键的可核查争议

1. **shell 状态丢失的错误因果。** E01 L169、E02 L81、E03 L171、E04 L68、E07 L173 都把跳过 `updateStateFromSnapshot` 推成 CWD/env 未更新。源码 `ShellStateManager.java:94-106` 显示 CWD 已由 shell 脚本原子写入，该 Java 方法仅打 debug；环境按产品约定不跨调用保存。E06 L283 明确指出这个反证。
2. **“没有完成期重试”遗漏真实调用链。** E01 L182、E03 L144-157、E06 L170-178、E07 L109 的强断言遗漏 `RunTracker.java:74-80 → RunTerminationCoordinator.java:60` 的一次完成期取消重试。E02 L88-90、L210 已自行修正。持续失败仍可能占许可/会话，但一次未确认并不等于必然永久锁死；`RunExecutionRegistry.java:620-629` 允许晚到 lease release 移除已请求注销的 execution。
3. **E03 F1 探针没有跑报告所称的上层路径。** E03 L95-105 的探针可见；`/tmp/zc_repro/Repro.java:15` 把 hook 固定为 false，L19 直接 unregister，L31 直接 runner.cancelRunDetailed。源码没有 RunTracker/RunTerminationCoordinator/终态数据库。因此 E03 L149 将 [4]/[5] 归因为 ALREADY_TERMINAL 提前返回不受该探针支持。该探针支持的是持续 hook 失败下底层注册阻塞。
4. **Python 异常型 close 不是 TTL 自动重发。** E03 L233-237 正向保证“周期性重试、有界可自愈”过强。`browser_service.py:335-356` 缓存失败 task，TTL 后续复用同一异常，不发新 RPC。E05 L165-177、E06 L294-300 识别了此区别；但 E06 L298 又把未确认直接称真实 context 必残留，仍需缩小到“容量归属被保留”。
5. **受限枚举探针的混杂条件。** E01 L116/L211 的探针源码可见，但 `/tmp/probe/Probe.java:33-36,84-86` 让 root 永久 alive 且 destroy 不改状态。它不能隔离证明单纯枚举失败使已死亡根也永不确认。`OwnedProcess.java:206` 对已死 parent 早退，E06 L213 已主动补充这一限制。
6. **父提交归属。** E02 L93-96 把共享 terminate/hook budget 叫“本次引入”不准：父提交 `ManagedProcessRunner.java:145-147` 已共享。然而旧 cleanup 首次直接调用 hook（360-364），HEAD:380 新增首次 hook 前 deadline 门控；E01 L342 称旧 cleanup 此前也早退不精确。实际 DockerRuntimeService.ensureRemoved:25-28 在父提交已自行因耗尽预算早退，因此 Docker 实际清理饥饿既有、通用 hook 首次是否调用则有新差异，两者应部分支持。E03 L172 把沙箱硬失败分支称新增也不对；E02 L100/L209 与 E07 L26/L223 已辨明其父提交既有。
7. **定位和平台证据。** E07 L75/L137 的 OwnedProcess 锚点 L603、L720、L806 均超出现文件257行。E07 L146 把 0–4ms 放在 Linux 实测语境，但 L248 是 Mac OS X，L276 又声明未运行 Linux。E01 L229/E03 L133 称每 PID 双读；源码164-168只有匹配本组 PID 才第二读。
8. **其它强因果应加条件。** E06 L268“未确认必端口占用”缺监听进程仍活条件；L325“startWithRetry 每次必失败”忽略下一次 stopProcess 可能确认；L531“生产不会触发 PROCESS_GROUP_UNAVAILABLE”超出仅验证 setsid/bash 存在的范围。E07 L237“未改文件所以无因果”不等于父提交A/B；E06 L426称 HTTP API 跳快照与父提交一致，与父提交调用链不符。

## 逐报告证据与内部一致性

### E01（32 项）

身份：标题可见 DeepSeekHarness。方法：E01.md:L1-L5,L47-L51,L66-L127。
交叉阅读披露：**承认先独立后交叉阅读**（E01.md:L336-L352）。明确对同目录其他工具报告线索逐条复核后追加 S1-S5，不能称整份报告彼此隔离或双盲。

| 证据路径 | 当前可见 | 报告引用 |
|---|---|---|
| `/tmp/probe/Probe.java` | 是 | E01.md:L331 |
| `/tmp/probe3/LaunchWindowCancelProbe.java` | 是 | E01.md:L332 |
| `/tmp/probe3/CancelTimelineProbe.java` | 是 | E01.md:L117 |
| `/tmp/zc-verify/target/surefire-reports/com.aicodeassistant.tool.process.ManagedProcessRunnerTest.txt` | 是 | E01.md:L51 |
| `/tmp/zc-parent/target/surefire-reports/com.aicodeassistant.tool.process.ManagedProcessRunnerTest.txt` | 是 | E01.md:L51 |

- R1窗口Linux典型10-30ms（L144）无报告内Linux实测；macOS单例58ms不是通用概率估计。
- L257把父提交同样失败当作负载敏感证明，但L94说明的是ps权限问题，两者不能互换。
- L352称所有已证实均附可复现命令或原始输出；探针源现可见，但附录cp含<slf4j>占位符，重跑还需解析环境。

| 主张 | 原始级别 | 报告行号 | 审计提示 |
|---|---|---|---|
| R1：启动占位窗口取消导致 Run 终态误判 | 中高 | L132–L151 | 中性提取；未在本审计重跑测试 |
| R2：前台清理硬失败、行为文档与 shell 快照分支 | 中 | L155–L174 | 静态反证：ShellStateManager.java:94-100 的包装脚本已原子写入 CWD；104-106 的 updateStateFromSnapshot 仅 debug，并注明环境不跨调用保存。跳过该方法为真，推断 cwd/env 丢失不成立。 |
| R3：保留许可且无后台回收导致容量耗尽 | 中 | L178–L184 | 遗漏完成期重试：RunTracker.java:74-80 在 awaitQuiescence 失败后会调用 termination.terminate；RunTerminationCoordinator.java:60 会再次 cancelRunDetailed。需限定为该重试仍持续失败。RunExecutionRegistry.java:620-629 的晚到 lease release 在 unregisterRequested 后会移除 execution，故单次 unregister 超时不等于永久不可逆。 |
| R4：浏览器容量硬拒绝与未完成条目占额 | 中低 | L188–L203 | 中性提取；未在本审计重跑测试 |
| R5：枚举异常阻断确认并跳过 Linux 组扫描 | 中低 | L207–L217 | /tmp/probe/Probe.java:33-36 的 destroy/destroyForcibly 不改变 alive；84-86 的 live root 永远 alive。该模型同时包含不可杀根与枚举异常，不能隔离证明“枚举异常本身使已杀死根永不确认”。OwnedProcess.java:206 对死亡 parent 早退；E06 L213 明确限定了这一点。 |
| R6：2 秒清理预算使 exit 0 变失败 | 中低 | L221–L223 | 中性提取；未在本审计重跑测试 |
| R7：每 10ms 全 /proc 扫描开销 | 低 | L227–L229 | 读数夸大：OwnedProcess.java:164 首读每 PID；165-166 过滤非本组后，168 仅匹配组成员二次读。不是每 PID 均两次 stat。10ms 是 sleep 下界，实际每轮还含扫描耗时，不能直接当每秒 100 次固定频率。 |
| R8：CHANGELOG/rv 文档/提交语言不同步 | 低 | L233–L237 | 中性提取；未在本审计重跑测试 |
| R9：真实浏览器测试未接 CI 及测试缺口 | 低 | L241–L245 | 中性提取；未在本审计重跑测试 |
| R10：废弃 verifier 旧 ID 且不关闭 | 低（信息） | L249–L251 | 中性提取；未在本审计重跑测试 |
| R11：固定 sleep/极短阈值测试脆弱 | 低 | L255–L259 | 中性提取；未在本审计重跑测试 |
| S1：共享清理预算可跳 hook，但父提交已有 | 低（既有） | L342–L342 | 原文称旧cleanup此前也return false不精确：父提交cleanup首次直接调用hook（360-364），HEAD:380才新增首次调用前deadline门控。父提交已共享deadline，且实际DockerRuntimeService.ensureRemoved:25-28原本会在remaining<=0自行早退，故Docker实际清理饥饿既有这一结论有支撑；通用hook首次是否被调用则为新差异。应部分支持。 |
| S2：非 Linux 确认假阳性为既有设计局限 | 信息（非回归） | L343–L343 | 中性提取；未在本审计重跑测试 |
| S3：沙箱未确认清理时丢命令输出 | 中低（新暴露） | L344–L344 | 中性提取；未在本审计重跑测试 |
| S4：PythonProcessManager FAILED 无自动恢复 | 低 | L345–L345 | 中性提取；未在本审计重跑测试 |
| S5：官方镜像满足 setsid/bash，部署风险排除 | 已排除 | L346–L348 | 实质正向保证；未在本审计重跑测试 |
| C1：Java/Python 新字段双向兼容 | 未分级 | L122–L122 | 实质正向保证；未在本审计重跑测试 |
| C2：FastAPI Request 注入实测正常 | 未分级 | L123–L123 | 实质正向保证；未在本审计重跑测试 |
| C3：生产镜像满足新增依赖 | 未分级 | L124–L124 | 实质正向保证；未在本审计重跑测试 |
| C4：journey 504/499 不触发客户端自动重试 | 未分级 | L125–L125 | 实质正向保证；未在本审计重跑测试 |
| C5：API 模式跳失败快照修复幽灵 context | 未分级 | L126–L126 | 实质正向保证；未在本审计重跑测试 |
| A1：输出/退出码/截断/元数据兼容且 awaitDrain 纯优化 | 未分级 | L267–L267 | 实质正向保证；未在本审计重跑测试 |
| A2：后台进程未受影响 | 未分级 | L268–L268 | 实质正向保证；未在本审计重跑测试 |
| A3：沙箱失败清理可重试并共享一次执行 | 未分级 | L269–L269 | 实质正向保证；未在本审计重跑测试 |
| A4：browser 并发资源隔离与 http_api 无回归 | 未分级 | L270–L271 | 实质正向保证；未在本审计重跑测试 |
| A5：Python/DevServer 保留句柄安全、pid 文件无误杀消费方 | 未分级 | L272–L273 | 实质正向保证；未在本审计重跑测试 |
| A6：前端无影响与版本双向兼容 | 未分级 | L274–L275 | 实质正向保证；未在本审计重跑测试 |
| Q1：身份快照、握手解决 PID 复用与失控窗口 | 未分级 | L282–L282 | 实质正向保证；未在本审计重跑测试 |
| Q2：取消安全清理与 deadline 预算设计可靠 | 未分级 | L283–L284 | 实质正向保证；未在本审计重跑测试 |
| Q3：测试真并发/状态机与注释可维护性 | 未分级 | L285–L286 | 实质正向保证；未在本审计重跑测试 |
| Q4：pending/reservation 可读性与 bound-method 隐式 key | 未分级 | L291–L291 | 中性提取；未在本审计重跑测试 |
| Q5：cleanup CAS 语义正确但职责复杂 | 未分级 | L292–L292 | 中性提取；未在本审计重跑测试 |

### E02（34 项）

身份：标题无审查器姓名。方法：E02.md:L6-L8,L203-L205。
交叉阅读披露：**未声明读过其他报告**（E02.md:L208-L210）。披露自我复核修正，不等同于披露交叉读取。仅文本不能证明独立执行过程。

未提供具体可检查的探针/原始日志路径；报告内摘录和静态断言仍按各自证据等级保留。

- 明确只读静态、未执行构建测试（L205）；L191“已用…用例核验”应读为核对断言，不是运行通过证据。
- L16/191“成功路径行为不变”需限定常规无残留清理；L24/114-117已承认正常exit后杀子进程。

| 主张 | 原始级别 | 报告行号 | 审计提示 |
|---|---|---|---|
| P1-1：exit 0 硬失败、shell 状态丢失与元数据不齐 | P1 | L79–L83 | 静态反证：ShellStateManager.java:94-100 的包装脚本已原子写入 CWD；104-106 的 updateStateFromSnapshot 仅 debug，并注明环境不跨调用保存。跳过该方法为真，推断 cwd/env 丢失不成立。 |
| P1-2：完成期一次重试后仍未确认会耗尽许可 | P1 | L85–L91 | 报告已在 L88-90 与 L210 自我修正完成期重试；有重试仍持续失败时保留许可的风险与断言可区分。 |
| P2-1：terminate/hook 共用预算可跳过 hook | P2（本次引入） | L93–L96 | 需细分新旧：父提交 ManagedProcessRunner.java:145-147 已共享2秒deadline，但旧cleanup首次CAS成功会直接调用hook（360-364），只在等待已有attempt时检查remaining；HEAD:380新增首次hook调用前deadline门控。所以“共享预算本次引入”不准，但“首次hook被预算直接跳过”确为新变化。实际DockerRuntimeService.ensureRemoved:25-28在父提交已自行因remaining<=0返回false，Docker实际清理饥饿仍是既有；正常完成新增terminate可扩大触发条件。应部分支持，不能整体驳倒。 |
| P2-2：沙箱分支丢输出，既有缺陷被放大 | P2（非本次引入） | L98–L102 | 中性提取；未在本审计重跑测试 |
| P2-3：Linux 新增 setsid/bash 依赖 | P2 | L104–L107 | 中性提取；未在本审计重跑测试 |
| P2-4：持续 /proc 失败导致硬失败与许可保留 | P2 | L109–L112 | 需分开“枚举抛异常”“静默返回空”“根/已知成员已退出”“持续 /proc 扫描异常”。OwnedProcess.java:206 的死亡 parent 早退意味着单纯 descendants 异常未必持续到清理结束；所有命令/永远未确认需要额外持续失败条件。 |
| P2-5：前台完成杀残留子进程需文档化 | P2 | L114–L117 | 中性提取；未在本审计重跑测试 |
| P2-6：Python FAILED 无健康检查自动恢复 | P2 | L119–L122 | 中性提取；未在本审计重跑测试 |
| P3-1：工具和 Run 失败码同名 | P3 | L127–L127 | 中性提取；未在本审计重跑测试 |
| P3-2：废弃 verifier 保留旧命名 | P3 | L128–L128 | 中性提取；未在本审计重跑测试 |
| P3-3：VerifyJourneyTool 未使用 import | P3 | L129–L129 | 中性提取；未在本审计重跑测试 |
| P3-4：DevServer pendingCleanup 仅停机重试 | P3 | L130–L130 | 中性提取；未在本审计重跑测试 |
| P3-5：4 参构造器隐式生成随机 ID | P3 | L131–L131 | 中性提取；未在本审计重跑测试 |
| P3-6：非 Linux 20ms descendants 采样开销 | P3 | L132–L132 | 中性提取；未在本审计重跑测试 |
| P3-7：10ms /proc 扫描无日志 | P3 | L133–L133 | 中性提取；未在本审计重跑测试 |
| P3-8：绝对 deadline 依赖双端时钟 | P3 | L134–L134 | 中性提取；未在本审计重跑测试 |
| P3-9：Python 总清理时限不足以保证 130 秒预算 | P3 | L135–L135 | 中性提取；未在本审计重跑测试 |
| P3-10：runSync finally throw 维护性 | P3 | L136–L136 | 中性提取；未在本审计重跑测试 |
| P3-11：WARN 无 Run/tool 标识 | P3 | L137–L137 | 中性提取；未在本审计重跑测试 |
| T1：未覆盖无取消场景最终回收 | 未分级 | L152–L152 | 中性提取；未在本审计重跑测试 |
| T2：未覆盖慢 terminate 加慢 hook | 未分级 | L153–L153 | 中性提取；未在本审计重跑测试 |
| T3：沙箱硬失败无断言 | 未分级 | L154–L154 | 中性提取；未在本审计重跑测试 |
| T4：非 Linux 真实路径测试薄 | 未分级 | L155–L155 | 中性提取；未在本审计重跑测试 |
| T5：真实 Chromium 容器 opt-in 非 CI 门禁 | 未分级 | L156–L156 | 中性提取；未在本审计重跑测试 |
| T6：反射/static mock 测试耦合强 | 未分级 | L157–L157 | 中性提取；未在本审计重跑测试 |
| T7：setsid/bash 缺失与 stdin 前提无测试 | 未分级 | L158–L158 | 中性提取；未在本审计重跑测试 |
| Q1：cleanup 语义正确但 CAS 可读性低 | 未分级 | L169–L169 | 中性提取；未在本审计重跑测试 |
| A1：跨端字段兼容与生产调用点无遗漏 | 未分级 | L17–L18 | 实质正向保证；未在本审计重跑测试 |
| A2：后台命令无破坏 | 未分级 | L62–L62 | 实质正向保证；未在本审计重跑测试 |
| A3：browser/http_api/journey 契约一致，close 日志加 TTL 兜底 | 未分级 | L66–L68 | “TTL 兜底”应限定超时 task 后续成功/可过期正常资源；已完成异常 close task 在缓存中不会发新 RPC，不能笼统保证关闭失败自动恢复。 |
| A4：ProcessTreeManager 签名语义与所有调用点同步 | 未分级 | L69–L71 | 实质正向保证；未在本审计重跑测试 |
| A5：fail-safe 设计一致，新增 process 观测事件 | 未分级 | L164–L164 | 实质正向保证；未在本审计重跑测试 |
| A6：可安全保留 main，但上线需 Linux 冒烟 | 未分级 | L191–L197 | 实质正向保证；未在本审计重跑测试 |
| A7：明确未发现问题模块与测试核对范围 | 未分级 | L213–L213 | 实质正向保证；未在本审计重跑测试 |

### E03（40 项）

身份：L5 可见 ZCode。方法：E03.md:L11-L28。
交叉阅读披露：**明确声明未阅读指定其他工具报告**（E03.md:L13）。声明未阅读QoderCN/QoderIDE/pi报告；不自动覆盖所有7份、也不是可验证双盲。

| 证据路径 | 当前可见 | 报告引用 |
|---|---|---|
| `/tmp/zc_linux/Probe.java` | 是 | E03.md:L353 |
| `/tmp/zc_linux/Probe2.java` | 是 | E03.md:L353 |
| `/tmp/zc_linux/Probe3.java` | 是 | E03.md:L353 |
| `/tmp/zc_repro/Repro.java` | 是 | E03.md:L358 |
| `/tmp/zc_java_full.log` | 是 | E03.md:L364 |
| `/tmp/zc_java_tests.log` | 是 | E03.md:L364 |
| `/tmp/zc_py_tests.log` | 是 | E03.md:L364 |
| `/tmp/zc_cls_results.txt` | 是 | E03.md:L364 |

- L21定向总87（含6跳过），L278又称88 passed/6 skipped；内部数量矛盾。
- L15声称未对仓库任何写操作，但L344/347在backend运行Maven默认写target，L351 pytest默认也可写缓存；“未改源码”与“未写任何文件”应区分。未证明报告运行真的改了受保护源码。
- 可见zc_java_full.log摘要支持3039/1/76；zc_py_tests.log支持229 passed/1 skip；zc_cls_results.txt支持6次exit=0，但文件存在不建立全部因果链或身份归属。

| 主张 | 原始级别 | 报告行号 | 审计提示 |
|---|---|---|---|
| F1：清理未确认锁死会话的探针与普遍化结论 | P1 | L93–L105 | 探针源码 /tmp/zc_repro/Repro.java 实际可读：L15 hook 恒 false，L19 直接 unregister，L31 直接 runner.cancelRunDetailed；未实例化 RunTracker/RunTerminationCoordinator，不是完整 query/终态取消路径。可证明持续 hook 失败时底层注册被阻塞，不能证明任何单次清理未确认都永久锁死。 |
| F1-chain：保留 lease→注册不移除→终态取消无恢复 | P1 | L144–L157 | 遗漏完成期重试：RunTracker.java:74-80 在 awaitQuiescence 失败后会调用 termination.terminate；RunTerminationCoordinator.java:60 会再次 cancelRunDetailed。需限定为该重试仍持续失败。RunExecutionRegistry.java:620-629 的晚到 lease release 在 unregisterRequested 后会移除 execution，故单次 unregister 超时不等于永久不可逆。；L149 把探针 [4]/[5] 归因于 RunControlService 的 ALREADY_TERMINAL 提前返回，与可见源码直接 runner.cancelRunDetailed 的路径不符。 |
| F2：受限枚举失去降级导致每条失败 | P1（条件性） | L123–L129 | 需分开“枚举抛异常”“静默返回空”“根/已知成员已退出”“持续 /proc 扫描异常”。OwnedProcess.java:206 的死亡 parent 早退意味着单纯 descendants 异常未必持续到清理结束；所有命令/永远未确认需要额外持续失败条件。 |
| F3：浏览器容量拒绝、owner 未释放永久占位 | P2 | L206–L207 | 中性提取；未在本审计重跑测试 |
| F4：exit 0 硬失败、cwd/env 丢失、沙箱新增与失败码 | P2 | L166–L174 | 静态反证：ShellStateManager.java:94-100 的包装脚本已原子写入 CWD；104-106 的 updateStateFromSnapshot 仅 debug，并注明环境不跨调用保存。跳过该方法为真，推断 cwd/env 丢失不成立。；L172 称沙箱分支“同样新增”与父提交 BashTool 既有分支不符；E02 L100/L209、E07 L26/L223 已说明既有。 |
| F5：时钟漂移提前超时或保护失效 | P2 | L217–L217 | 中性提取；未在本审计重跑测试 |
| F6：断连 watcher 异常当成断连 | P2 | L216–L216 | 中性提取；未在本审计重跑测试 |
| F7：废弃 verifier 及未用 import | P3 | L289–L289 | 中性提取；未在本审计重跑测试 |
| F8-parse：readIdentity 空截断解析脆弱 | P3 | L131–L131 | 中性提取；未在本审计重跑测试 |
| F8-perf：每次 /proc 双读全扫性能 | P3 | L133–L133 | 读数夸大：OwnedProcess.java:164 首读每 PID；165-166 过滤非本组后，168 仅匹配组成员二次读。不是每 PID 均两次 stat。10ms 是 sleep 下界，实际每轮还含扫描耗时，不能直接当每秒 100 次固定频率。 |
| F8-deps：精简镜像 setsid/bash 依赖 | P3 | L138–L138 | 中性提取；未在本审计重跑测试 |
| F9：包装器注入 PWD/SHLVL 破坏严格清空环境 | P3 | L136–L136 | 中性提取；未在本审计重跑测试 |
| F10：新 session 改变 tty/Ctrl+C/docker stop 信号传播 | P3 | L137–L137 | 中性提取；未在本审计重跑测试 |
| F11：ProcessTreeManager/DevServer 清理预算缩短 | P3 | L161–L164 | 中性提取；未在本审计重跑测试 |
| D1：CHANGELOG/部署配置/commit body 缺行为说明 | P2 | L285–L287 | 中性提取；未在本审计重跑测试 |
| S1：PythonProcessManager FAILED 粘性失败 | P3 | L181–L181 | 中性提取；未在本审计重跑测试 |
| S2：catch Error 注释与 synchronized 阻塞 12s | P3 | L182–L183 | 中性提取；未在本审计重跑测试 |
| S3：npm install 成功仍清理失败，浪费安装成本 | 未分级 | L189–L189 | 中性提取；未在本审计重跑测试 |
| S4：lambda close key 可读性 | 未分级 | L208–L208 | 中性提取；未在本审计重跑测试 |
| S5：499/504 被 Java 泛化成服务不可达 | 未分级 | L218–L218 | 中性提取；未在本审计重跑测试 |
| S6：journey mode 字段未使用是遗留 | 未分级 | L219–L219 | 中性提取；未在本审计重跑测试 |
| S7：Python 未确认自动 TTL 自愈，Java 不自愈 | P2 | L229–L237 | 静态反证：browser_service.py:335-340 仅成功移除 close task；失败 task 被缓存，343-356 后续复用同一异常结果，不发新 RPC。TTL 会循环访问不等于关闭失败可自愈。E05 R5/E06 H6 的异常型与超时型区分更精确。 |
| T1：Linux 路径本地跳过，容器探针补覆盖 | P2 | L252–L252 | 中性提取；未在本审计重跑测试 |
| T2：真实浏览器两测试未接 CI | P2 | L253–L253 | 中性提取；未在本审计重跑测试 |
| T3：缺清理未确认后同会话新 Run 测试 | P2 | L254–L254 | 中性提取；未在本审计重跑测试 |
| T4：本地 pytest-cov 缺失，覆盖率门未验证 | P2 | L255–L255 | 中性提取；未在本审计重跑测试 |
| T5：缺 Java 504/499 归因测试 | P2 | L256–L256 | 中性提取；未在本审计重跑测试 |
| T6：全量单一失败归因为既有预算 flake | P2（非本次引入） | L258–L274 | 中性提取；未在本审计重跑测试 |
| Q1：单行 catch 风格与魔法数字 | 未分级 | L290–L290 | 中性提取；未在本审计重跑测试 |
| Q2：WARN 与保留容量缺可观测性 | 未分级 | L291–L291 | 中性提取；未在本审计重跑测试 |
| A1：生产容器命令/退出码/输入/子进程回收保真 | 未分级 | L73–L91 | L91 “环境保真”需要与同段 L88/F9 的 PWD/SHLVL 新增并列限定；少数常规命令 37-294ms 不证明所有常规命令不会误报。 |
| A2：身份校验、root first 与中断保存正确 | 未分级 | L115–L119 | 实质正向保证；未在本审计重跑测试 |
| A3：预占防重复、CAS 单执行者、输出完成后仍保留 | 未分级 | L142–L142 | 实质正向保证；未在本审计重跑测试 |
| A4：Python stop 所有权与 root 优雅退出有效 | 未分级 | L178–L178 | 实质正向保证；未在本审计重跑测试 |
| A5：130 秒预算/不重试/资源 ID 隔离/strict 快照 | 未分级 | L186–L188 | 实质正向保证；未在本审计重跑测试 |
| A6：迟到 context/取消安全/latch/TTL/strict 语义正确 | 未分级 | L198–L202 | 实质正向保证；未在本审计重跑测试 |
| A7：journey 有界 join 与 FastAPI 注入实测正确 | 未分级 | L212–L212 | 实质正向保证；未在本审计重跑测试 |
| A8：Optional 字段与 import 启动安全 | 未分级 | L223–L223 | 实质正向保证；未在本审计重跑测试 |
| A9：新增测试设计质量与覆盖范围 | 未分级 | L245–L248 | 实质正向保证；未在本审计重跑测试 |
| A10：常规链路全部无确定性破坏 | 未分级 | L299–L312 | 实质正向保证；未在本审计重跑测试 |

### E04（29 项）

身份：L5 可见 QoderCN。方法：E04.md:L4,L117-L132。
交叉阅读披露：**无交叉阅读披露**（E04.md:L4）。只声称独立重新审查；未说明读没读其他报告。

未提供具体可检查的探针/原始日志路径；报告内摘录和静态断言仍按各自证据等级保留。

- 未给测试日志或探针源码路径；只有报告内摘要和复跑次数，不能仅凭缺路径称捏造。
- L16/155“两侧全通过”省略L121的筛除范围、L122/125失败与重跑条件。
- “无命令内容泄露”（L90）是广泛正向保证，报告未附逐日志字段审计记录。

| 主张 | 原始级别 | 报告行号 | 审计提示 |
|---|---|---|---|
| S1：exit 0 硬失败、shell 状态丢失、受限主机全失败 | P1 | L68–L68 | 静态反证：ShellStateManager.java:94-100 的包装脚本已原子写入 CWD；104-106 的 updateStateFromSnapshot 仅 debug，并注明环境不跨调用保存。跳过该方法为真，推断 cwd/env 丢失不成立。；需分开“枚举抛异常”“静默返回空”“根/已知成员已退出”“持续 /proc 扫描异常”。OwnedProcess.java:206 的死亡 parent 早退意味着单纯 descendants 异常未必持续到清理结束；所有命令/永远未确认需要额外持续失败条件。 |
| S2：许可/lease 永久保留，Python 同理 | P1 | L69–L69 | 遗漏完成期重试：RunTracker.java:74-80 在 awaitQuiescence 失败后会调用 termination.terminate；RunTerminationCoordinator.java:60 会再次 cancelRunDetailed。需限定为该重试仍持续失败。RunExecutionRegistry.java:620-629 的晚到 lease release 在 unregisterRequested 后会移除 execution，故单次 unregister 超时不等于永久不可逆。 |
| S3：浏览器容量由淘汰变拒绝 | P1 | L70–L70 | 中性提取；未在本审计重跑测试 |
| S4：API 模式删除失败快照修复幽灵会话 | P1 | L71–L71 | 实质正向保证；未在本审计重跑测试 |
| D1：Linux setsid/bash 与 /proc 部署前提 | P1/P2 | L75–L75 | 中性提取；未在本审计重跑测试 |
| D2：Java/Python 跨主机时钟同步 | P2 | L76–L76 | 中性提取；未在本审计重跑测试 |
| D3：PowerShell 未同步 terminationConfirmed | P3 | L77–L77 | 中性提取；未在本审计重跑测试 |
| Q1：保留资源无界增长无指标 | P1 | L96–L96 | Java active 受 16 许可约束，Python session/creating/context 受 max_sessions 约束；“无界/无限增长”需分别证明 pendingCleanup/resource_close_tasks 的累积路径，不能与有容量上限的计数直接等同。 |
| Q2：new_context 挂起使同 ID waiter 无界等待 | P2 | L97–L97 | 中性提取；未在本审计重跑测试 |
| Q3：Playwright 私有 API 升级风险 | P2 | L98–L98 | 中性提取；未在本审计重跑测试 |
| Q4：stat 字段解析需短字段防御 | P2 | L99–L99 | 中性提取；未在本审计重跑测试 |
| Q5：npm install 成功清理未确认仍整体失败 | P2 | L100–L100 | 中性提取；未在本审计重跑测试 |
| Q6：gather set 解包无序但无实际影响 | P3 | L101–L101 | 中性提取；未在本审计重跑测试 |
| Q7：close_session closed 字段未消费 | P3 | L102–L102 | 中性提取；未在本审计重跑测试 |
| Q8：descendants 元数据额外开销 | P3 | L103–L103 | 中性提取；未在本审计重跑测试 |
| Q9：测试反射耦合 | P3 | L104–L104 | 中性提取；未在本审计重跑测试 |
| Q10：取消 shield 复杂度 | P3 | L105–L105 | 中性提取；未在本审计重跑测试 |
| T1：50ms creation close 测试 flake | P2 | L129–L132 | 中性提取；未在本审计重跑测试 |
| T2：真实 Chromium 未跑与 token 性能失败 | 未分级 | L124–L125 | 中性提取；未在本审计重跑测试 |
| A1：前台命令/输出/超时测试全通过 | 未分级 | L55–L55 | 实质正向保证；未在本审计重跑测试 |
| A2：后台命令未受影响 | 未分级 | L56–L56 | 实质正向保证；未在本审计重跑测试 |
| A3：Python 托管启停健康检查语义兼容 | 未分级 | L57–L57 | 实质正向保证；未在本审计重跑测试 |
| A4：DevServer 日志/pid/就绪语义不变 | 未分级 | L58–L58 | 实质正向保证；未在本审计重跑测试 |
| A5：VerifyJourney 快照先于 close | 未分级 | L59–L59 | 实质正向保证；未在本审计重跑测试 |
| A6：浏览器 ad-hoc touch 与 JS 错误采集无回归 | 未分级 | L60–L60 | 实质正向保证；未在本审计重跑测试 |
| A7：close envelope 与生产依赖满足 | 未分级 | L61–L62 | 实质正向保证；未在本审计重跑测试 |
| A8：所有权对称、中断/错误/并发/PID/日志安全 | 未分级 | L85–L90 | 实质正向保证；未在本审计重跑测试 |
| A9：可合并、无需回滚、两侧测试全绿 | 未分级 | L155–L158 | L155“两侧测试全绿”与 L121 明确排除 token_estimation、L125 失败一次及 L122 首轮 flake 的统计口径不同；应说明是筛选/重跑后而非无条件全绿。 |
| A10：Browser shutdown 全量有界清理，journey 取消后 join 有界清理 | 未分级 | L45–L46 | 实质正向保证；未在本审计重跑测试 |

### E05（29 项）

身份：标题无审查器姓名。方法：E05.md:L7-L10,L78-L89,L250-L284。
交叉阅读披露：**无交叉阅读披露**（E05.md:L7,L366）。声称独立；未说明是否看其他报告。

| 证据路径 | 当前可见 | 报告引用 |
|---|---|---|
| `/tmp/zk-parent` | 未找到 | E05.md:L7 |

- 仅命名父提交副本/tmp/zk-parent，当前该路径不存在；启动窗口探针没有源文件具体路径、没有原始测试日志路径。此为留存不足，不是捏造结论。
- L366称所有构建产物/tmp，L265/L356-357却在仓库backend默认mvn；未说明target重定向。
- ProcessTreeManagerTest父提交结果在L23-25与L155/L282冲突。
- Linux“命中概率不低/更长”（L89）未有Linux动态测量；单机窗口不能量化生产概率。

| 主张 | 原始级别 | 报告行号 | 审计提示 |
|---|---|---|---|
| R1：启动占位取消错误返回/Run 错判 | 高 | L46–L95 | 中性提取；未在本审计重跑测试 |
| R2：浏览器容量删除 LRU 的可用性回归 | 高 | L97–L116 | 中性提取；未在本审计重跑测试 |
| R3：仅 Linux 前台完成杀残留子进程 | 中高 | L118–L137 | 把行为限定“仅 Linux 生效/macOS 不会触发”过强：非 Linux waitFor 仍采样记住后代，对已捕获的存活子进程也会 terminate；单机静默空枚举的一个示例不能代表全部 macOS。 |
| R4：非 Linux 受限枚举与新非空测试断言 | 中 | L139–L161 | 需分开“枚举抛异常”“静默返回空”“根/已知成员已退出”“持续 /proc 扫描异常”。OwnedProcess.java:206 的死亡 parent 早退意味着单纯 descendants 异常未必持续到清理结束；所有命令/永远未确认需要额外持续失败条件。；报告摘要 L23-25 将 ProcessTreeManagerTest 归到“父提交同样失败”，正文 L155 与表 L282 却说父提交通过、为新断言；内部矛盾。 |
| R5：失败 close 永久缓存不重试且占容量 | 中 | L163–L179 | 中性提取；未在本审计重跑测试 |
| R6：close_session 未确认由 bool 变异常契约 | 中 | L181–L194 | 中性提取；未在本审计重跑测试 |
| R7：资源未释放拒 startup | 正文低；汇总中 | L196–L207 | 正文 L196 标低风险，汇总 L323 标中风险，严重度不一致。 |
| R8：新增 Linux 依赖、安全检查未发现注入等 | 低 | L309–L309 | 中性提取；未在本审计重跑测试 |
| R9：OwnedProcess.destroy 仅根进程易误用 | 低 | L233–L233 | 中性提取；未在本审计重跑测试 |
| R10：真实浏览器 CI 缺口、Linux 跳过、测试脆弱 | 低 | L294–L299 | 中性提取；未在本审计重跑测试 |
| R11：CHANGELOG 未更新 | 低 | L305–L305 | 中性提取；未在本审计重跑测试 |
| Q1：cleanup CAS 循环可读性 | 未分级 | L234–L234 | 中性提取；未在本审计重跑测试 |
| Q2：browser 多状态容器复杂与双 close 标签逻辑 | 未分级 | L235–L236 | 中性提取；未在本审计重跑测试 |
| Q3：时间单位边界需常量/断言 | 未分级 | L237–L237 | 中性提取；未在本审计重跑测试 |
| Q4：known 只增不删 | 未分级 | L238–L238 | 中性提取；未在本审计重跑测试 |
| Q5：提交粒度过大难回滚二分 | 未分级 | L306–L306 | 中性提取；未在本审计重跑测试 |
| Q6：容量/清理/异常行为未登记 | 未分级 | L307–L307 | 中性提取；未在本审计重跑测试 |
| T1：Java 各失败 A/B 归因 | 未分级 | L278–L282 | 中性提取；未在本审计重跑测试 |
| T2：两真实浏览器测试未接 CI | 未分级 | L294–L294 | 中性提取；未在本审计重跑测试 |
| T3：Linux 本地跳过无法发现启动窗口问题 | 未分级 | L295–L295 | L295 称 2.1 的启动窗口问题恰为 Linux 专属路径暴露，但 L80-81 已明确在 macOS 实测；平台覆盖论证自相矛盾。 |
| T4：缺工具级残留进程与超限路由端到端 | 未分级 | L296–L296 | 中性提取；未在本审计重跑测试 |
| T5：Playwright 私有属性测试升级风险 | 未分级 | L297–L297 | 中性提取；未在本审计重跑测试 |
| T6：非空枚举断言与 100ms 取消时序 | 未分级 | L298–L299 | 中性提取；未在本审计重跑测试 |
| A1：资源 ID 隔离与 strict 快照不重建 | 未分级 | L213–L214 | 实质正向保证；未在本审计重跑测试 |
| A2：deadline/disconnect 有界返回并清理 | 未分级 | L215–L215 | 实质正向保证；未在本审计重跑测试 |
| A3：Python 保留旧 processRef 防双启动 | 未分级 | L216–L216 | 实质正向保证；未在本审计重跑测试 |
| A4：抽象/注释/取消安全/错误保真良好 | 未分级 | L224–L228 | 实质正向保证；未在本审计重跑测试 |
| A5：主源码编译通过、类型规范、清理普遍有界 | 未分级 | L242–L244 | 实质正向保证；未在本审计重跑测试 |
| A6：新增测试生命周期/身份/capacity 覆盖 | 未分级 | L288–L290 | 实质正向保证；未在本审计重跑测试 |

### E06（52 项）

身份：L8 可见三路并行工作方式。方法：E06.md:L8-L9,L104-L118,L580-L603。
交叉阅读披露：**披露内部子审查交叉复核；未披露读其他工具报告**（E06.md:L112,L132,L300）。协调者复核worker结论是团队内部方法，不能推断读取其它评审报告。

| 证据路径 | 当前可见 | 报告引用 |
|---|---|---|
| `backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098/java_review_findings.md` | 是 | E06.md:L594 |
| `backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098/python_review_findings.md` | 是 | E06.md:L595 |
| `backend/.zhikun/scratchpad/824e9ba4-7e97-4ead-9c30-7db8e486c098/test_execution_evidence.md` | 是 | E06.md:L596 |
| `/tmp/zhikun_parent` | 未找到 | E06.md:L602 |

- L35称3 P1+6 P2+5 P3，而主表/正文H4-H9共有6 P2，H10-H14为5 P3，与主编号一致；H13含大量不同小项，本提取拆分保留。
- L68称12个测试类/文件又括号Java8+Python3=11；L101/L479列第4个Python修改文件可解释为表述漏列。
- 动态环境/软件版本与8m58s仅是报告宣称；有证据文件可读，不等于全部时间、并行成本、模型参数可审计。
- 声明未改源码/配置/测试且允许gitignored构建产物（L9/L603）较“未写任何文件”精确。

| 主张 | 原始级别 | 报告行号 | 审计提示 |
|---|---|---|---|
| H1：任一未确认→无自动重试→永久会话不可用 | P1 | L125–L184 | 遗漏完成期重试：RunTracker.java:74-80 在 awaitQuiescence 失败后会调用 termination.terminate；RunTerminationCoordinator.java:60 会再次 cancelRunDetailed。需限定为该重试仍持续失败。RunExecutionRegistry.java:620-629 的晚到 lease release 在 unregisterRequested 后会移除 execution，故单次 unregister 超时不等于永久不可逆。；H3 的正常清理成功仅是行为变化，本身不造成 retained lease；“任一未确认一次即永久不可用”的链条缺 retry 持续失败前提。 |
| H2：受限枚举条件未确认加延迟，正文补死根可确认限制 | P1（条件） | L188–L216 | 中性提取；未在本审计重跑测试 |
| H3：前台正常返回杀残留子进程仍报告成功 | P1 | L220–L243 | 中性提取；未在本审计重跑测试 |
| H4：DevServer 未确认必残留端口使后续失败 | P2 | L249–L270 | 未确认不等于端口必仍被占：可因 inspection 不可用、非监听子进程等导致未确认。保留句柄与缺定期 retry 可静态证，后续端口持续失败须附端口实际仍被占的条件。 |
| H5：exit 0 硬失败、枚举元数据/截断不齐、shell 空实现 | P2 | L274–L285 | 中性提取；未在本审计重跑测试 |
| H6：close 异常缓存永久占额、TTL 无法重试 RPC | P2 | L289–L300 | 失败 close 未确认与真实 context 必残留不能等同；RPC 异常也可能发生在服务端已关闭之后。每次“泄漏一个真实 Chromium context”因果过强，容量账目保留则静态成立。正常已登记 session 失败也可留在 _sessions，与 _unclosed_contexts 的回滚场景需区分。 |
| H7：journey 首次限容，异常未映射导致裸 500 | P2 | L304–L314 | 中性提取；未在本审计重跑测试 |
| H8：Python stop void/12s root grace/重试必失败 | P2 | L318–L329 | PythonProcessManager.java:131-136 每次 start 会重新 stopProcess，197-199 的 restart 先 stop 再检查。进程后来退出即可成功，不支持“每次必然同样失败”。只有持续失败条件下重复无新手段；root-first 空等是另一可独立核查项。 |
| H9：测试 skip/CI opt-in/私有字段/flaky | P2 | L333–L339 | 中性提取；未在本审计重跑测试 |
| H10：499/504 缺日志和部分结果 | P3 | L345–L347 | 路由并非没有任何 logger：journey.py:20 创建 logger、82 记录清理异常。准确表述应是 499/504 分支无日志（37/48/49）。 |
| H11：创建中 close 取消 owner/waiter 逃逸成 500 | P3 | L349–L351 | 中性提取；未在本审计重跑测试 |
| H12：废弃 verifier 旧 ID 与 120s | P3 | L353–L355 | 中性提取；未在本审计重跑测试 |
| H13a：CHANGELOG 与 docs 不同步 | P3 | L359–L360 | 中性提取；未在本审计重跑测试 |
| H13b：中英注释与死代码/import | P3 | L361–L362 | 中性提取；未在本审计重跑测试 |
| H13c：unclosed_contexts label 双语义 | P3 | L363–L363 | 中性提取；未在本审计重跑测试 |
| H13d：fallback rv ID 仅 32bit | P3 | L364–L364 | 中性提取；未在本审计重跑测试 |
| H13e：整体 600s 预算余量收窄 | P3 | L365–L365 | 中性提取；未在本审计重跑测试 |
| H13f：/proc 全扫描与 known 集合开销 | P3 | L366–L366 | 中性提取；未在本审计重跑测试 |
| H13g：动态 callable key 可读性 | P3 | L367–L367 | 中性提取；未在本审计重跑测试 |
| H13h：Request=None mypy 注解建议 | P3 | L368–L368 | 中性提取；未在本审计重跑测试 |
| H14：driver stop latch 后 browser 能力不恢复且 health 不暴露 | P3 | L370–L372 | 中性提取；未在本审计重跑测试 |
| T1：缺启动前提各失败分支 | 未分级 | L508–L508 | 中性提取；未在本审计重跑测试 |
| T2：缺 terminate=false cleanup=true 保留断言 | 未分级 | L509–L509 | 中性提取；未在本审计重跑测试 |
| T3：PID reuse 仅 mock | 未分级 | L510–L510 | 中性提取；未在本审计重跑测试 |
| T4：sandbox 未确认无测试 | 未分级 | L511–L511 | 中性提取；未在本审计重跑测试 |
| T5：DevServer 残留端口后续失败无测试 | 未分级 | L512–L512 | 中性提取；未在本审计重跑测试 |
| T6：真实 disconnect 轮询无测试 | 未分级 | L513–L513 | 中性提取；未在本审计重跑测试 |
| T7：容量拒绝路由响应无测试 | 未分级 | L514–L514 | 中性提取；未在本审计重跑测试 |
| T8：关闭失败长期影响测试固化而未讨论 | 未分级 | L515–L515 | 中性提取；未在本审计重跑测试 |
| T9：Python catch Error/drain/grace 空等未覆盖 | 未分级 | L516–L516 | 中性提取；未在本审计重跑测试 |
| T10：资源收敛容器测试仅手动 | 未分级 | L517–L517 | 中性提取；未在本审计重跑测试 |
| A19：HTTP API 跳失败快照行为与父提交一致 | 未分级 | L426–L426 | 静态反证：父提交 VerifyJourneyTool.java:415-416 仍把业务 sessionId 传到 handleVerificationResult，失败快照没有新 browserResourceId=null 跳过条件。此项与 E01/E02/E04/E07 对 API 模式修复的正确说明相冲突。 |
| A20：生产不会触发 PROCESS_GROUP_UNAVAILABLE | 未分级 | L531–L531 | Docker 仅证明 setsid/bash 存在，不能排除 PROCESS_GROUP_UNAVAILABLE 的其它分支（stdin、身份等待/proc）；“生产不会触发”超出实测范围。 |
| A21：提交四项承诺部分达成 | 未分级 | L546–L549 | 实质正向保证；未在本审计重跑测试 |
| A1：已确认无问题清单 1 | 未分级 | L380–L380 | 实质正向保证；未在本审计重跑测试 |
| A2：已确认无问题清单 2 | 未分级 | L381–L381 | 实质正向保证；未在本审计重跑测试 |
| A3：已确认无问题清单 3 | 未分级 | L382–L382 | 实质正向保证；未在本审计重跑测试 |
| A4：已确认无问题清单 4 | 未分级 | L383–L383 | 实质正向保证；未在本审计重跑测试 |
| A5：已确认无问题清单 5 | 未分级 | L384–L384 | 实质正向保证；未在本审计重跑测试 |
| A6：已确认无问题清单 6 | 未分级 | L385–L385 | 实质正向保证；未在本审计重跑测试 |
| A7：已确认无问题清单 7 | 未分级 | L386–L386 | 实质正向保证；未在本审计重跑测试 |
| A8：已确认无问题清单 8 | 未分级 | L387–L387 | 实质正向保证；未在本审计重跑测试 |
| A9：已确认无问题清单 9 | 未分级 | L388–L388 | 实质正向保证；未在本审计重跑测试 |
| A10：已确认无问题清单 10 | 未分级 | L389–L389 | 实质正向保证；未在本审计重跑测试 |
| A11：已确认无问题清单 11 | 未分级 | L390–L390 | 实质正向保证；未在本审计重跑测试 |
| A12：已确认无问题清单 12 | 未分级 | L391–L391 | 实质正向保证；未在本审计重跑测试 |
| A13：已确认无问题清单 13 | 未分级 | L392–L392 | 实质正向保证；未在本审计重跑测试 |
| A14：已确认无问题清单 14 | 未分级 | L393–L393 | 实质正向保证；未在本审计重跑测试 |
| A15：已确认无问题清单 15 | 未分级 | L394–L394 | 实质正向保证；未在本审计重跑测试 |
| A16：已确认无问题清单 16 | 未分级 | L395–L395 | 实质正向保证；未在本审计重跑测试 |
| A17：已确认无问题清单 17 | 未分级 | L396–L396 | 实质正向保证；未在本审计重跑测试 |
| A18：已确认无问题清单 18 | 未分级 | L397–L397 | 实质正向保证；未在本审计重跑测试 |

### E07（17 项）

身份：标题无审查器姓名。方法：E07.md:L7-L8,L271-L279。
交叉阅读披露：**无交叉阅读披露**（E07.md:L7,L273）。声明独立复审，未说明是否阅读其他报告；不据此推断抄袭或双盲。

| 证据路径 | 当前可见 | 报告引用 |
|---|---|---|
| `/tmp/oprobe/Probe.java` | 是 | E07.md:L245 |
| `/tmp/oprobe/Probe2.java` | 是 | E07.md:L245 |
| `/tmp/oprobe/Probe3.java` | 是 | E07.md:L245 |
| `/tmp/oprobe/OwnedProcess.java` | 是 | E07.md:L245 |

- L15宣称6项P2，正文P2-1到P2-7实际7项。
- L9统计9 Java源+4 Python源+11测试；其它报告及提交地图为9 Java源+3 Python源+12测试（Python publication测试应计测试）。
- L137/L75多处OwnedProcess源文件锚超过257行（可能错用拼接输出行号），影响可定位性。
- L193/243的一次新用例失败与L240摘要只1个token失败可能来自不同轮次，但缺完整轮次日志映射，不能合并成一次运行。
- L279“未修改任何文件”与仓库中mvn/pytest命令默认写构建缓存的语义需区分；git clean不证明无ignored写入。

| 主张 | 原始级别 | 报告行号 | 审计提示 |
|---|---|---|---|
| P1-1：容量 LRU 删除且 close 占额不可恢复 | P1 | L45–L69 | 一次 close 失败不总等于需重启整个 Python 进程：browser/driver 祖先确认关闭会解除子资源归属；必须持续祖先 shutdown 也失败才达到整个进程不可恢复。 |
| P1-2：非 Linux 终止确认假阳性与泄漏未闭合 | P1 | L73–L101 | 现版本 OwnedProcess.java 共257行，报告位置锚 L603-614/L720-770 不存在；机制描述可定位到35-44、150-208。非 Linux 漏捕获局限可成立，但报告未用父提交 A/B 证明新引入，宜区分未完全修复和回归。 |
| P2-1：清理未确认长期许可/lease 保留无自动重试 | P2 | L105–L114 | 遗漏完成期重试：RunTracker.java:74-80 在 awaitQuiescence 失败后会调用 termination.terminate；RunTerminationCoordinator.java:60 会再次 cancelRunDetailed。需限定为该重试仍持续失败。RunExecutionRegistry.java:620-629 的晚到 lease release 在 unregisterRequested 后会移除 execution，故单次 unregister 超时不等于永久不可逆。 |
| P2-2：Linux setsid/bash/proc 新部署前提 | P2 | L118–L131 | 中性提取；未在本审计重跑测试 |
| P2-3：非 Linux CPU 量化与 Linux /proc 开销 | P2 | L135–L148 | 同样引用不存在的 L720-770/L806-820。L146 将 cleanupMs=0-4ms 接在“Linux侧…实测”之后，但附录 L248 标明 os=Mac OS X，且 L276 声明未运行 Linux；该耗时不能作为 Linux 实证。 |
| P2-4：前台正常完成清理残留行为变化 | P2 | L152–L165 | 中性提取；未在本审计重跑测试 |
| P2-5：跳 shell 快照导致旧 cwd/env 与失败分类歧义 | P2 | L169–L175 | 静态反证：ShellStateManager.java:94-100 的包装脚本已原子写入 CWD；104-106 的 updateStateFromSnapshot 仅 debug，并注明环境不跨调用保存。跳过该方法为真，推断 cwd/env 丢失不成立。 |
| P2-6a：真实 Chromium 两测试 CI 未执行 | P2 | L181–L189 | 中性提取；未在本审计重跑测试 |
| P2-6b：Python 50ms 测试负载 flake | P2 | L191–L200 | 中性提取；未在本审计重跑测试 |
| P2-6c：Java 取消等失败归为负载 flake | P2 | L202–L204 | 中性提取；未在本审计重跑测试 |
| P2-7：CHANGELOG 缺失与覆盖率 workflow 需确认 | P2 | L208–L212 | 中性提取；未在本审计重跑测试 |
| S1：未改的 ConcurrencyControlTest 为既有无因果 | 未分级 | L237–L237 | 文件未在 diff 中并不足以证明失败与提交无因果；本项没有报告在父提交执行同用例，只是 HEAD 单独复跑。应为既有问题线索，而非已完成 A/B 因果排除。 |
| S2：close_session 异常契约变严，路由处理但其他调用注意 | 未分级 | L230–L230 | 中性提取；未在本审计重跑测试 |
| A1：编译和公共调用签名未破坏 | 未分级 | L19–L19 | 实质正向保证；未在本审计重跑测试 |
| A2：PID/握手/取消/ID 隔离方向正确；沙箱分支父提交已有 | 未分级 | L26–L26 | 实质正向保证；未在本审计重跑测试 |
| A3：后台、沙箱、Python/DevServer 与 HTTP 模式影响断言 | 未分级 | L222–L227 | 实质正向保证；未在本审计重跑测试 |
| A4：macOS 普通命令/杀运行根/优雅 root 可确认 | 未分级 | L248–L253 | 实质正向保证；未在本审计重跑测试 |

## 时间、成本与配置的可证边界

七份报告均不提供足以统一比较的审查总运行时间、token 用量、费用账单或完整模型配置。报告中的单次 pytest/Maven 耗时、主机软件版本、并行方法只能原样记录，不可把它们当作总审查耗时或成本。E06 说明三路并行（L8），E03/E01/E05/E07 多处说明探针和隔离方法，但均不足以还原模型版本、reasoning、采样、上下文、工具调用重试配置。

不同报告的失败数不应直接互相判假：套件筛选、跳过、负载、运行轮次和隔离副本不同。特别是“隔离通过”只能支持负载/时序敏感线索；未构造 A/B 条件时不充分证明失败根因。已在每份条目记录内部计数冲突与测试口径变化。

结构化主张总数：233。数量不是质量分数，不应以条目多寡排名。


## 提交证据留存

已按原路径层级复制直接引用的探针与日志到 `submitted_evidence/<Eid>/`，原路径、报告引用、文件大小和 SHA-256 索引见 `submitted_evidence/index.json`。当前可见文件已留存；不存在的目录只记录状态，不推断当时未执行。`claims.json` 另加 `scoring_category`，将正向保证、测试覆盖建议、维护性建议与待裁决缺陷/行为变化分开；条目数不能直接当作 D2 分母。


## E08 同轮追加：opencode

E01–E07 的逐项内容保持原样；本次按用户追加请求纳入 E08，仍为同一轮。E08 共提取17项，含6项主要发现、测试/因果/维护性项及5项正向保证。当前总数为 250；条目数量不是有效缺陷分母或质量得分。

### 证据与方法

E08 L59–148 详述 Request CancelScope 吞取消→finally gather挂起；L103–126 自报真实uvicorn、curl、5/5复现和机制实验。附录L311明示是重新构造仅mock browser_service的app；是否含真实main.app中间件没有显示。主审正在独立验证真实应用栈，提取审计不替代该实验。

`/tmp/jdelay.py`（E08 L311）和`/tmp/mech.py`（E08 L122/L321）当前均未找到；已在同一 submitted_evidence/index.json 标记状态，无文件可复制或计算SHA。没有给出原始日志路径。该事实仅限制可复核性，不构成捏造判断。

| 主张 | 原始级别 | 报告行号 | 审计提示 |
|---|---|---|---|
| F1：真实 Request CancelScope 吞外部取消令 finally gather 请求挂起 | 阻断级 / 高危 | L59–L148 | 报告附录 E08 L311 明确构造“仅 mock browser_service 的 app（jdelay.py）”；这证明使用真实HTTP与uvicorn不等于使用真实 main.app 全中间件。现存 main.py:104-105 有 HTTP middleware，报告未展示其是否包含在探针中。需由主审核验真实中间件下机制适用性。；L108-115 是 curl -m 4 超时返回 HTTP000 的报告内记录；单凭有限4秒观察不足以证明数学意义永久挂死。报告另称任务栈与取消机制，但源/原始日志目前不可见，不能在本提取中认证。；L131 缓存页面/截图极快完成与 L105 无任何await的 mocked创建并非同一执行条件；真实 Playwright RPC 通常含异步让步，不能仅凭5/5人工时序样本外推生产发生率。该问题是否实际成立留待主审新复现裁定。 |
| F2：前台正常退出仍可能清理未确认而报告失败 | 中危 / 行为变更 | L152–L165 | L164只陈述updateStateFromSnapshot被跳过，未像部分报告进一步声称cwd/env必丢失；不应给它套用其它报告的错误因果。L156的setsid逃逸若根本未被发现，也可能返回确认假阳性而非未确认，应限定是否已被known追踪。 |
| F3：浏览器满载由淘汰最旧变为硬拒绝 | 中危 / 行为变更 | L167–L177 | 中性提取；未运行测试，待主审裁决 |
| F4：非 Linux 不提供进程组归属保证但非回归 | 低危 / 非 Linux 降级 | L179–L181 | 中性提取；未运行测试，待主审裁决 |
| F5：废弃 UserJourneyVerifier 与旧资源命名耦合 | 低危 / 死代码 | L183–L185 | 中性提取；未运行测试，待主审裁决 |
| F6：Request=None 类型标注不严谨、功能无影响 | 低危 / 健壮性 | L187–L189 | 修复建议 Optional[Request] 本身没有运行验证；FastAPI 对 Request 参数有特殊注入规则，不能只凭静态类型风格保证修改方案可直接使用。现状“运行功能无影响”是报告保证，待主审统一判定。 |
| T1：断连 watcher 缺真实 Request/ASGI 覆盖 | 未分级 | L136–L140 | 内部矛盾：E08 L19称没有一条测试传http_request，L137称全部单参；L138随即承认SimpleNamespace假对象。源码 test_journey_lifecycle.py:143-145确实以第二参数传入http_request。准确缺口是没有真实Request/ASGI栈覆盖，不能说从不创建watcher。 |
| T2：生命周期用例出现间歇失败但50次未再现 | 需关注 | L230–L254 | 报告诚实承认后续50次未稳定复现（L254）；不能把这些失败直接归因为F1或唯一已证根因，原始日志未命名路径。 |
| T3：未改模块单跑失败即判既有且非本次因果 | 未分级 | L205–L206 | 未改文件与HEAD单独运行仍失败不足以完成父提交A/B因果排除。报告未给该失败在父提交执行结果；“既有/非本次引入”应降为线索。 |
| Q1：browser 多状态并发与 shield 理解维护成本高 | 未分级 | L271–L271 | 中性提取；未运行测试，待主审裁决 |
| Q2：平台契约需明确且行为变化未在提交中声明 | 未分级 | L272–L273 | 中性提取；未运行测试，待主审裁决 |
| Q3：提交 why 导向但缺行为兼容性说明 | 未分级 | L276–L278 | 中性提取；未运行测试，待主审裁决 |
| A1：Java 正常链路基本安全且修改测试通过 | 未分级 | L17–L19 | L19“没有一条测试真正传入http_request”与本报告L138及仓库测试矛盾。OwnedProcessTest的L215“15/15（6 skipped）”应读作15 collected、9 executed，不能称15全部执行通过。 |
| A2：正常多秒级 browser journey 通常安全 | 未分级 | L129–L129 | 此保证没有真实 main.app 各中间件的运行证据；与F1随机时间窗本身也只能做条件概率描述，而非可靠无回归保证。 |
| A3：Java PID/归属/租约/CAS 重试逻辑严谨 | 未分级 | L265–L265 | 中性提取；未运行测试，待主审裁决 |
| A4：Python 并发边界建模细致、回归测试针对性好 | 未分级 | L266–L267 | 中性提取；未运行测试，待主审裁决 |
| A5：Java 进程验证链路基本安全 | 未分级 | L330–L330 | 中性提取；未运行测试，待主审裁决 |

- 核心hang有详细文字机制与curl命令；但/tmp/jdelay.py和/tmp/mech.py当前均不存在，且未给原始uvicorn/curl/机制日志具体路径，需以新的独立复现认证。缺失不等于捏造。
- “真实HTTP/真实uvicorn/真实路由”不自动等价于生产main.app：L311明示重新构造app，未说明包含main.py中间件。
- L19/L137说没有任何传http_request测试，与L138及源码143-145矛盾；有效缺口是缺真实Starlette Request覆盖。
- Java全量失败归既有（L205-206）只凭未改模块和HEAD单跑失败，未呈现父提交运行对照。
- Python全量229通过、早期间歇失败、后续50次通过属于不同轮次；报告有区分，但未提供逐轮原始日志索引。
- E08有审查日期和平台/依赖版本宣称，没有审查总耗时、token/成本/模型详细配置，不可纳入统一速度成本结论。
