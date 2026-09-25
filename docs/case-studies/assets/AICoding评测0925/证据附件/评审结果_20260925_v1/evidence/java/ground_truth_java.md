# Java 生命周期独立核验

核验对象：`0db4b0498f6cf886f862fe293883256b1c52e049`。未阅读参赛报告。原仓库只读，执行对象为本目录 `source-head/` 与 `source-parent/` 的 git archive 副本。下文代码路径均以 `backend/src/main/java/com/aicodeassistant/` 为基准；HEAD/parent 指相应副本。

## 可进入真实回归机会集的结论

### J1：启动窗口的未确认摘要在进程实际已清理后仍把取消判为 FAILED（P2，本次引入，已确定性复现）

- 触发条件：取消扫描发生于 HEAD `tool/process/ManagedProcessRunner.java:102–109` 已注册 active、但 `:120–121` 尚未发布实际 Process 之间；随后启动/清理在 coordinator 的等待期内完成。
- HEAD `:364–374` 对空 process 返回未确认，`:311–329` 把它计入 summary。`run/RunTerminationCoordinator.java:60–68` 先取得 summary，再等待 quiescence；等到 work 已全部释放后仍使用旧 summary，从而 `runs.fail(PROCESS_TERMINATION_UNCONFIRMED)`。
- 证据源：`probes/GroundTruthLifecycleProbeTest.java:21–59`。用 latch 固定启动窗口，真实 runner/registry/coordinator，仅进程启动和持久化出口为 mock。`logs/lifecycle-maven.log:7`：`quiescent=true activeNow=0 cached=CancelSummary[activeCount=1, confirmedCount=0, unconfirmedCount=1] terminalReason=PROCESS_TERMINATION_UNCONFIRMED`。
- 父提交对照：父 runner `:109–124` 启动返回后才注册 active；相同窗口在 `probes/GroundTruthParentWindowTest.java:17–46` 固定，`logs/parent-window-maven.log:2` 显示 cached active=0/unconfirmed=0，最终 `CANCELLED confirmation=true`。
- 影响边界：是取消终态/诊断误判；探针明确证明最后没有活跃工作，不能据此宣称留下真实进程泄漏或普遍取消失败。若取消扫描发生在 process 发布后且退出得到确认，此问题不触发。

### J2：有限清理重试用尽后保留的 WorkLease/容量缺少恢复驱动（P2，本次引入，条件性可用性缺陷）

- 触发条件：进程根已退出，但 terminationHook/资源检查在正常返回、finally、completion 转 termination 的回调和扫描时均不能确认；Run 已进入终态并 unregister；资源稍后恢复可清理。
- HEAD runner `:164–189` 保留 active 和容量；`:343–350` 只在再次确认时释放 lease/容量。Registry `run/RunExecutionRegistry.java:588–590,612–614` 每个取消 callback 只调用一次；`:258–263,632–650` unregister 等待后仍保留非空 work；`:87–96` 因 session 索引仍存在而拒绝下一 Run。Coordinator `:50–52` 在已终态请求上提前返回，不再扫描。QueryEngine `engine/QueryEngine.java:410,458,511–515` 只请求 unregister。
- 证据源：`probes/GroundTruthLifecycleProbeTest.java:62–91`。真实 `true` 命令、真实 registry/runner；hook 用可恢复布尔值模拟外部清理暂时不可用。`logs/lifecycle-maven.log:2–3`：失败尝试 4 次后恢复，反复 registry 取消仍为 4 次，`registered=true permits=0`，同一 session 新 Run 被 `SESSION_EXECUTION_ALREADY_REGISTERED` 拒绝；直接调用 runner 再取消则恢复注册和容量。
- 必须保留的反例：`run/RunTracker.java:74–79` 的 completeRun 确有等待后进入 termination 的自动重试；coordinator `:57–60` 又有 lease callback 和 process scan。因此“清理失败后完全没有重试”“任意一次短暂失败立刻永久卡住”均不准确。已有测试也覆盖手动 runner/首次 registry 取消成功恢复。
- 引入判断：parent runner `:88–92,164–173` 无条件释放 WorkLease/active/capacity，不会产生这个保留后无恢复驱动的锁定；但旧行为会丢失实际未清理资源的归属。修复方向应保留归属同时提供 reaper/可达重试，不能简单恢复无条件释放。
- 严重度建议 P2：已证明异常后的会话阻塞，可累积到全局 16 槽容量耗尽；未证明生产环境清理异常频率，不宜夸大为无条件全服务故障或 P0。若评测将持续容量耗尽定义为 P1，可按其统一标尺提升。

### J3：新 Bash 未确认分支的通用 outputTruncated 字段不承接 stream 标志（P2，本次新增分支缺陷，静态闭环）

- `tool/impl/BashTool.java:408–417` 新分支给 `ToolResult.failed` 的 metadata 只有 `stdoutTruncated/stderrTruncated`，缺 `truncated`。
- `tool/ToolResult.java:114–120` 仅以 metadata 的 `truncated` 初始化结构化 `outputTruncated`，`:137–145` 也仅识别这个通用键。故 completed=true、terminationConfirmed=false 且任一 stream 被截断时，可得到 stream 标志=true 但 `outputTruncated=false`。
- 这是结构化结果字段不一致，非完整输出必然丢失的新根因。成功分支 `BashTool.java:425–428` 已把两个标志 OR 为 `truncated`，提供直接反例/对照。未另跑 UI 或消费方集成测试；用户可见影响需以消费方使用该字段为前提。
- 不要混同既有问题：runner 在 1 MiB drain 之前/之后另做 30,000 字符 preview，未把 preview 截断计入 flag（HEAD `:154–155,435,468–469`，parent `:153,420,452–453`），该旧问题不是本次引入。

## 真实剩余限制 / 既有问题，不能直接记为本次回归

### J4：非 Linux 自然退出父进程的孤儿子进程仍可能漏清理（P2 剩余缺陷，非新增回归）

- `OwnedProcess.java:35–43` 在非 Linux 直接启动；`:236–248` 只在调用 waitFor 时周期保存 descendants，`:153,202–208` 不会从已经退出的父进程追溯孤儿。DevServer/Python 服务的就绪轮询只调用 isAlive/HTTP，未周期调用 owned.waitFor 来保留快照。
- 同一真实 Java 探针 `probes/OwnedProcessPortableProbe.java:14–28` 让子进程后台 sleep、父 shell 正常退出，故意在清理前不用 owned.waitFor（匹配服务轮询路径）。`logs/portable-mac.log:1`：Mac OS X，rootAlive=false/childBefore=true，cleanup=true 但 childAfter=true。`logs/portable-linux.log:1`：Linux 同源同命令 cleanup=true，childAfter=false。
- 该缺口在 parent 的普通 Process/即时 descendants 方案同样存在；新 Linux session/group 支持实际修复了相应 Linux 场景。不能把 macOS 结果外推为 Linux 全部失效，也不能宣称 macOS 已有 Linux 同级保证。显式 setsid/setpgid 逃离本来就被 `OwnedProcess.java:16–19` 声明不属于安全隔离保证。
- 探针只控制自己创建的 shell/sleep；子进程在 finally 中独立回收，Linux 容器有 --init。

### J5：取消已提出后仍可能跨过启动 admission（P2 既有启动竞态，HEAD 仍未消除）

- HEAD runner `:120–123` 在 OwnedProcess.start 返回后才装 WorkLease.onCancel；OwnedProcess Linux `:79–82` 验证 PID/group 后立即写入 start，无 run cancellation predicate。若取消介于 lease acquisition 与 callback 安装，命令可能已执行，再由迟装回调终止。
- parent runner `:109–124` 同样先 builder.start 再 callback，故“取消后仍执行任意一点用户代码”的基本缺陷不能记为本次新引入。HEAD 的空 active 预注册使 J1 的误判成为新问题，与此不是同一根因/影响。
- 本项为代码时序证据，没有人为扩展为必然长期存活或安全越权。

### J6：清理过程共用 deadline 是既有设计；持久 inspection 错误要具体化

- HEAD runner `:146–151,170–173` terminate 与 cleanup 共用 2 秒预算；parent `:145–148,166–168` 已有此结构。新代码对正常 foreground 完成也 terminate，从而实际消费预算机会增加，但“共用 deadline”本身不是新增。 需进一步区分：HEAD cleanup `:380` 新增“首次调用 hook 前预算已耗尽则不调用”的门控；parent `:360–370` 首次仍会调用 hook 并传入过期 deadline，旧前置 return 只在后续等待缓存结果路径。因此“旧 cleanup 首次也不调用 hook”不准确。不过实际 Docker hook `sandbox/DockerRuntimeService.java:25–28` 自身先检查 remaining<=0 后返回 false，故旧版本也不会执行有效 docker rm。独立 hook 的可观察调用次数可能改变，不等于 Docker 有效清理首次在本次被取消。
- OwnedProcess `:108–115` 捕获 inspection 失败后不确认成功，是保守语义；`:153,202–208` root 死后可能不再调用失败的 descendants，于下一轮成功返回。确定性反例 `probes/GroundTruthLifecycleProbeTest.java:94–108` 令活 root 的 descendants 抛 UnsupportedOperationException，destroy 后 root 不活；`logs/lifecycle-maven.log:5` 为 confirmed=true/elapsedMs=16。
- 因而“任意 descendants 异常都会永远未确认”“每条命令普遍增加 4 秒”均是过度概括。持续 /proc 权限错误、仍存活的已知 parent 持续枚举失败等条件可以持续未确认，需要给出实际环境/路径。
- `OwnedProcess.java:161–168,215–222` 全 /proc 扫描中的非 NoSuchFileException 会使本次扫描失败；特殊 hidepid/权限/文件系统条件有环境风险，但未在普通本地 Linux 镜像复现，不可据此认定普遍 Linux 故障。

## 已明确排除的误报/夸大

1. **跳过 updateStateFromSnapshot 导致 cwd/env 丢失：错误。** `tool/bash/ShellStateManager.java:87–101` shell 本身原子替换 cwd 文件；`:105–109` update 方法只 log.debug；`:52–67,123–126` 下一命令直接读跟踪文件。类注释 `:19–24` 明示不跨调用保留完整环境。真实 bash probe `GroundTruthLifecycleProbeTest.java:111–124` 刻意不调用 update，仍正确 resolve child；日志 `lifecycle-maven.log:6`。
2. **PID reuse 必然误杀新进程：未发现依据。** `OwnedProcess.java:155–158,175–180` 检查 leader startTicks；`:167–171` 获取 handle 前后复查 startTicks/group/session；`:202–208` descendants 入库前后检查 retained parent 是否活；`:191–195` 使用 ProcessHandle 身份化 signal，未对保存的 raw PGID 执行 kill。未跑人为 PID wrap/reuse 压力；不能把未测试当绝对无竞态证明。
3. **DevServer/Python 清理引用一定提前丢失：与新代码相反。** `verify/DevServerLauncher.java:132–140,188–194` 在不确认时保留 handle/pendingCleanup；`service/PythonProcessManager.java:131–136,171–174,181–199,360–365` 保留 processRef 并阻止覆盖重启。DevServer pendingCleanup 只有 shutdownAll 重试是有限恢复性设计，不能假设运行中定时重试。
4. **descendantTrackingUnavailable 精确表示范围清理是否可靠：错误。** runner `:486–489` 仅在末尾尝试 process.descendants().close()；不能承接 OwnedProcess 内部 /proc 检查失败、不能表达非 Linux 的重新收养限制。此探测函数在 parent `:470–473` 已存在。新机制应有更精确诊断，但这不是每次 metadata=false 都错误，也不是通用新回归。
5. **DevServer npm 大输出死锁是本次新增：错误。** HEAD `DevServerLauncher.java:178–183` 先 wait、仅失败才 readAllBytes；parent 同样先 wait 再读，pipe 堵塞风险已有。新 finally 清理改善了所有返回路径，未解决旧 drain 问题。

## 测试可信边界与留存

- 新 probe 4/4 通过；父提交对照 1/1 通过。XML/TXT 都复制到 `logs/surefire-head/`、`logs/surefire-parent/`。只做了这些小测试和两个 real-process 跨平台探针，没有运行全量套件，没有连接真实业务服务。
- J1 用模拟进程固定真实代码时序，证明控制流结果，不声称测量竞态概率；J2 用可恢复 hook 模型证明管理缺口，不声称已复现某个具体 Docker 故障；J6 mock 枚举异常是驳斥“必然永久失败”的有效反例，不覆盖所有 Linux /proc 故障。
- `logs/lifecycle-maven-initial-syntax-error.log` 是第一次 probe 自身方法声明笔误，随后修正；不是项目编译缺陷。正式成功日志为 `logs/lifecycle-maven.log`。
- 首次 CWD probe 使用 JDK 默认 createTempDirectory，实际目录落入系统临时目录；其唯一残留探针目录已迁移到本证据目录 `tmp/cwd-probe-first-run-moved`，生成的 tracking 文件已删除。已把复现源改为显式 evidence 临时根与 TMPDIR（未改断言和业务调用），原始已执行源保存在 `probes/GroundTruthLifecycleProbeTest.executed.java`。这是一次探针落盘范围偏差，不应隐藏；目标仓库全程未修改。
- 可复现命令见 `reproduce.md`。不阅读参赛报告的前提下完成上述裁决；后续仅接受主审所列需澄清主题，没有采用参赛方的测试输出。
