# 会话合并 v2 代码审查报告

| 项目 | 内容 |
| --- | --- |
| 报告文件 | `deepseekV4.1flashmax-session-merge-v2代码审查报告.md` |
| 被审仓库 | `/Users/guoqingtao/Desktop/dev/code/zhikuncode` |
| 分支 / 基线 | `main` @ `b98e187`（工作区未提交改动） |
| 对照规范 | `docs/session-merge-architecture-v2.md`（会话合并 v2：最小必要改造规格） |
| 审查范围 | 后端 18 个生产文件（12 改 + 6 新增）、11 个测试文件、前端合并域 4 个文件；架构文档本身 |
| 明确排除 | 按用户要求忽略：`ImageBlock.tsx`、`ImageBlock.test.tsx`、`messageContent.ts`、`messageContent.test.ts`、`MessageActions.tsx`、`MessageActions.test.tsx`、`e2e/message-copy-all.spec.ts`、`sessionStatusMeta.ts`、`SessionStatusCapsule.tsx`、`Header.tsx`、`PermissionMenu.tsx`、`PromptInput/index.tsx`、`Sidebar.tsx`、`globals.css`、`liquid-glass.css`、`Sidebar.desktop.test.tsx` |
| 审查方式 | 全量 diff 阅读 + 规范逐条比对 + 本机实跑回归 + 干净基线对照（非仅采信文档结论） |

---

## 一、结论（先说结果）

| 问题 | 结论 |
| --- | --- |
| 是否破坏非合并分支？ | **未发现破坏。** 静默隔离 + 全量回归 + 基线对照三重证据一致（详见第二节） |
| 是否达到可提交 GitHub 的发布标准？ | **尚未达到。** 有 2 个必须修复的正确性缺陷、1 个性能缺陷、若干测试/文档缺口（详见第四、六节） |
| 最大风险 | ① 封存期"被引用文件"复制的 symlink 穿透（违反规范第 156 行硬约束）；② 阻断型 gap 被标为 `paused` 但实际无法恢复，形成"暂停—恢复—再暂停"死循环；③ 每轮请求对整包做 2～4 次全量哈希 |

---

## 二、对非合并链路的影响评估（审查重点 1）

### 2.1 静态隔离检查：通过

所有非合并路径的接入点都带显式开关，且**没有改动任何核心链路文件**：

- `git diff --name-only` 显示 `SessionManager`、`SessionMessagePersistence`、`SubAgentExecutor`、`SwarmWorkerRunner`、`SessionExecutionGate`、`ToolRegistry`、`App/Sidebar/SessionController` 全部**与 HEAD 完全一致**（与规范第 511 行要求吻合）。
- 接入点仅 7 处，全部有守卫：
  - `QueryController` 第 178 / 265 / 362 行与 `WebSocketController` 第 959 行均先判 `isMerged(metadata)`，普通会话直接跳过，**不查合并表**。
  - `QueryEngine` 第 1811-1818 行的 `handoffProjection` 在 `state.getHandoffOperationId()==null` 时直接返回空投影 —— 普通请求零调用。
  - `HandoffContextService` 第 84-87 行的 `inject` 在空投影时原样返回入参列表（同一引用，不改写历史）。
  - `ImageRefInjector` 第 96-99 行新增重载；旧 7 参入口内部传入 `(path,hash)->false` 谓词，**普通路径行为逐字保持**，仅当谓词为真时才跳过路径安全检查（谓词只在 E 会话且投影非空时提供）。
  - `AuthorizationService` 第 158 行只是给 `safeRead` 增补一个 analyzerId；`OperationAnalyzerRegistry` 第 87 行用 `instanceof` 判定，同名动态/MCP 工具不会继承该权限（测试已覆盖）。
  - `HandoffReadTool` 不是 Spring Bean（测试断言无 `@Component`），**不进入全局工具池**，普通会话工具 schema/token 开销不变。
- 预算接点符合规范 §7.1 第 2、3 点：phase1 用 `inputBudget - handoff.reservedTokens()`，phase2 用**原总预算** `inputBudget` 校验含投影的真实 payload（`QueryEngine` 第 951 行），专项测试断言 `phase2 - phase1 == 200`，证明**没有重复扣减**，并且投影**未写入 `state.getMessages()`**（不落库）。

### 2.2 动态回归证据：通过

本机实跑结果（非采信文档结论）：

| 验证 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `./mvnw -Dspring.profiles.active=test test` | **2,975 项：2,900 通过 / 5 失败 / 0 错误 / 70 跳过**（BUILD FAILURE） |
| 前端全量 | `npx vitest run --maxWorkers=2 --minWorkers=1` | **103 文件：894 通过 / 16 跳过 / 0 失败** |
| 前端生产构建 | `npm run build`（`tsc && vite build`） | **exit 0**，仅既有的 chunk 体积提示 |
| **基线对照** | 在 `b98e187` 干净 worktree 上单跑上述两个失败类 | **同样的 5 个失败，行号完全一致** |

5 个失败全部位于 `WorkspaceFileBoundaryTest`（1 个）与 `ManagedProcessRunnerTest`（4 个），与本次改动无任何文件交集；**基线同样失败，属环境相关（疑似沙箱对进程/受保护文件操作的限制），不是本次回归**。这直接回答了审查重点 1：**非合并分支的功能链路没有被破坏**。

### 2.3 仍需注意的两个"非合并但全局"影响

1. **迁移对所有用户库生效**：`V026` 会 DROP 并重建 `session_merges`（`V026_ExtendSessionMerges.java` 第 13-98 行）。已核对旧校验器 `V024_CreateSessionMerges.validate()` 只校验列存在，因此**不会因 schema 变更导致启动校验失败**；`MigrationRunner` 在事务内执行并以 `protocol_version` 存在性做幂等，测试覆盖了"再启动校验通过 / 重复 target 回滚"。
2. **启动期副作用**：`SessionMergeService.@PostConstruct recover()`（第 74-80 行）会在应用启动时**同步扫描并删除**已取消/目标已删除的包目录，并对未完成的 v2 操作**自动续跑（含真实付费模型调用）**。这是规范 §5.2 的既定行为，但使"所有用户"的启动耗时与重启后费用受合并任务影响。建议 cleanup 加超时或异步化，并在文档中显式写明"重启会自动续跑并计费"。

---

## 三、与文档的一致性核对（做得好的部分）

- 文件落点与规范 §10 完全对齐：14 个既有文件 + 6 个新增文件，无额外子系统。
- 规范明令"保持不改"的组件确实未改（第 511 行）。
- §9 的"本次失败条件"（4,183 bytes / output 13,370 / >16 单元）已用合成回归覆盖，且**无 usage 时不再拒绝**（`MergeSummaryServiceTest` 第 55 行）。
- 取消与发布互斥、迟到 usage 不能复活任务、封存即释放、重启续跑、目标删除后绑定立即失效 —— 都有针对性测试（`SessionMergeServiceTest`、`MergeProgressRepositoryTest`），质量明显优于 v1。
- 读取与权限：跨会话 ref、路径穿越、symlink、跨包游标、目录偏移、8 MiB 扫描续页、长行尾部、代理对、哈希不缓存 —— `HandoffReadServiceTest` 覆盖扎实。

---

## 四、必须处理的问题（按严重度）

### P1-1 封存期引用文件复制存在 symlink 穿透（违反规范第 156 行）

`MergePackageService` 第 458-462 行：

```java
if ("external_reference".equals(asset.status()) && asset.originalPath().startsWith("/")) {
    Path path = Path.of(asset.originalPath()).toAbsolutePath().normalize();
    if (path.startsWith(own.toAbsolutePath().normalize()) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        copyFile(source, path, ...);
}
```

- ref 来源是消息正文里任意绝对路径字符串（`collectReferences` 第 937-941 行，含 agent 输出），只做**规范化**（`normalize`，不解析符号链接）。
- `NOFOLLOW_LINKS` 只作用于**最后一段**。若 scratchpad 内存在中间软链（如 `<own>/link -> /`），则 `<own>/link/etc/passwd` 既通过前缀校验、也不是目录，`copyFile` 会顺着中间软链把**包外文件**复制进快照，随后可被 E 的 `HandoffRead` 读取。
- 同文件的兄弟实现都做了正确校验（`copyTree` 第 956-964 行用 `toRealPath()` 比对；`safeFile` 第 304-318 行逐段拒绝软链），只有这一处没有 → 属实现疏漏而非有意设计；**无任何测试覆盖该分支**。
- 建议：改为 `Path canonical = path.toRealPath()` 后与 `own.toRealPath()` 比对，或直接复用 `safeFile` 的"逐段拒绝符号链接"逻辑。

### P1-2 阻断型 gap 报 `paused` 但实际不可恢复（暂停/恢复死循环）

`recoverBlockedProjections` 只接受 `RECORD_REQUIRES_HANDLING:<recordRef>` 且该 recordRef 必须在 `records.jsonl` 里是 `blocked`（`MergePackageService` 第 232-236 行），否则抛错。但两处写入的 gap 键不满足该格式：

| 位置 | gap 键 | 后果 |
| --- | --- | --- |
| 第 436 行：消息内容解析失败 | 裸 `RECORD_REQUIRES_HANDLING` | 明明已 `record(..., "blocked")` 注册了 raw 记录，却因键没有 recordRef 而永远无法走恢复分支（第 406 行同一场景就带了 `":"+ref`，属不一致） |
| 第 531 行：内嵌图片 base64 非法 | `RECORD_REQUIRES_HANDLING:invalid_image` | `invalid_image` 不是 recordRef → 永远抛错 |

而 `SessionMergeService` 第 195-198 行对任何以 `RECORD_REQUIRES_HANDLING` 开头的 blockedReason 都调用 `recoverBlockedProjections`，失败后落 `paused`，`explain()` 还提示用户"检查原件后再恢复"。用户恢复 → 同点再失败，**唯一出路是 cancel（随后包被删除）**。这与规范第 210/211 行"paused = 可处理问题 / failed = 已确认不可恢复"的语义划分冲突。

建议：① 第 436 行改为携带 recordRef（利用 `record(...)` 返回值）；② `invalid_image` 要么也登记 blocked 记录，要么改为非阻断 gap 或直接判 `failed/RECORD_REQUIRES_HANDLING`，并在 `explain()` 中给出可行操作。

### P1-3 每轮请求对整包做 2～4 次全量哈希校验（性能）

`HandoffContextService.project`（第 59 行）每次调用都会 `repository.binding(root)` + `reads.brief(root)` → `verify()` → 对 manifest、records/files/gaps/occurrences、details.jsonl、handoff.md、overview.json **全部重新哈希**（`HandoffReadService` 第 66-91 行；校验缓存仅限单次调用内）。而 `project` 在一次请求里会被调用 **2～4 次**：`QueryEngine` 第 841 行、第 844 行的 `prepareCompactionContext`（内部第 1835 行）、以及第 1933 / 1973 行的压缩与恢复路径。摘要包随来源规模增长（details.jsonl 可达数 MB），即"每轮对话重复哈希数 MB～数十 MB 并查库 2N 次"。

建议：在 `QueryLoopState` 或请求作用域内记忆绑定与 `ready.json` 校验结果（同一次请求内包不变，语义安全）。规范 §7.1 只要求**入口 token 有界**，并未要求每轮重做全包完整性校验。

### P2 其他正确性 / 健壮性问题

1. **图片复制配额异常绕过错误分类**：`MergePackageService` 第 528 行抛 `CopyLimitException`（`IOException` 子类），只被第 531 行的 `IllegalArgumentException` catch 覆盖，异常逃逸到 `SessionMergeService.safeCode`，中文消息 → 落成 `MERGE_PREPARATION_FAILED`，绕过了规范第 339/352 行要求的 `MERGE_COPY_INCOMPLETE` / `MERGE_DISK_SPACE_LOW` 可操作分类（`explain()` 也匹配不到）。
2. **封存无 fsync**：`Files.move(staging, sealed, ATOMIC_MOVE)`（第 170-173 行）前未 `flush/fsync` 目录与文件，与规范第 166 行"关闭/刷新文件"不完全一致；掉电可能封存出撕裂快照（`validateSnapshot` 能检出，退化为再暂停，而非静默损坏）。建议补 fsync 或明确记录该取舍。
3. **来源变更检测偏弱**：仅比较 `MAX(seq_num)` 与行数（第 439 行），原地修改一条历史消息不会被发现；并发删行会让 `queryForObject` 抛 `EmptyResultDataAccessException`（RuntimeException）而非 `SOURCE_CHANGED`。规范第 164 行要求"观测到变更必须暂停"。
4. **恢复路径的能力差距**：`resume` 会把 `failed` 单元重置为 `pending`（`MergeProgressRepository` 第 115 行），而 `pause` 不使用 `changed()` 校验（第 98-103 行）→ 若 pause 因 epoch 不符而静默失效，操作可能滞留在 `preparing` 且无 worker（需重启才被 `interrupted()` 捞回）。建议 pause 失败时补降级日志或兜底调度。
5. **`HandoffReadTool.call` 未防 `currentRunId == null`**（第 29 行），而 `HandoffContextService.canReadAsset` 做了空判，属同类不一致。
6. **错误码被压平**：`HandoffReadService.read` 把校验阶段的所有 `IOException`（含 `HANDOFF_READ_BUDGET_EXCEEDED`）统一转成 `AuthorizationException("HANDOFF_INVALID_RESOURCE")`（第 206-208 行），仅保留 message。规范 §7.2 要求超时"明确报 HANDOFF_READ_BUDGET_EXCEEDED"——现有测试只断言 message，掩盖了码值变化；且工具层最终呈现为 `permission denied`，可能误导模型以为"没权限"。另：`asset` 动作在 `read()` 的 else 分支会静默返回空列表（实际靠工具层拦截），属死分支。
7. **入口失败是 500 而非容量错误**：`project()` 抛 `IllegalStateException("HANDOFF_CONTEXT_BUDGET_TOO_SMALL" / "HANDOFF_UNAVAILABLE")`，经 `GlobalExceptionHandler` 第 138 行变成不透明的 500；规范第 371 行要求"返回正常容量错误"。同理 `configure` 里的 `AuthorizationException(HANDOFF_NOT_BOUND)` 也是 500。建议加显式异常映射（容量类 4xx + 可读 code）。
8. **`lockedSourceSessionIds` 只报根来源**（`SessionMergeService` 第 263 行），gate 实际同时锁子任务；虽然同批释放，但契约上不如规范"锁集合来自真实 gate"严谨。
9. **大结果上限需与规范对齐**：单个 unit 结果 >4 MiB 即整任务暂停（`MergeSummaryService` 第 178 行）；可见输出 256 KiB 上限使正常路径够用，但该上限与规范"超大详细结果保留原文件"的表述需要对齐说明。

---

## 五、代码质量与规范性

1. **注入风格不一致（会被 review 直接打回）**：`QueryController` 第 70-73 行与 `WebSocketController` 第 91-93 行新增了 **field injection**，而这两个文件在 HEAD 上 `@Autowired` 计数为 **0**（全构造器注入）；且使用行内全限定名 `com.aicodeassistant.engine.HandoffContextService`。同样问题在 `QueryEngine` 第 1809 行（此处 `required=false` 是有意的，但建议改为构造器可选注入并加注释）。建议统一为构造器注入 + 正常 import。
2. **同一文件内两种排版风格**：新增的合并类大量使用 `a=1;b=2;if(x) return;` 单行密集写法（如 `HandoffReadService`、`MergeProgressRepository`），与 `MergePackageService` 既有部分（4 空格、逐行、注释充分）风格割裂。仓库无 checkstyle/spotless，编译不会拦，但可读性与后续维护成本明显上升，建议至少对新增文件做一次格式化。
3. **`isExplicitCoreTool("HandoffRead")` 是无效/误导性放行**（`OperationAnalyzerRegistry` 第 120 行）：该方法只服务 `ToolRegistry` 的启动自检，而 `HandoffReadTool` 并非 Bean；把它列为"显式核心工具"反而让未来一个同名 Spring Bean 逃过 `AUTHORIZATION_ANALYZER_MISSING` 自检。建议删除该字符串（真正生效的是 `instanceof` 分支）。
4. **`Execution.promptVersion` 写入但从不校验**（`MergeHandoffData` 第 21 行、`SessionMergeService` 第 106 行）：修改提示词不会使旧单元失效，属隐性错误风险；要么用它参与 unitId/校验，要么删掉。
5. **ObjectMapper 未复用容器实例**：`projectionCatalogs` 内 `new ObjectMapper()`，`MergeSummaryService` 第 32 行自建实例，建议注入共享实例（也避免与全局 Jackson 配置漂移）。
6. **可维护性小项**：`finishAttempt` / `failAttempt` 的"先 completed 再翻 error"补偿逻辑（`MergeProgressRepository` 第 197-200 行）语义晦涩，建议在 `call()` 里按结果直接上报 outcome；`HandoffReadTool` 用 ImageIO 探测类型导致 **WebP 永远判为不支持**（白名单里有 `image/webp`），与"复用现有图片能力"表述不符。
7. **无明显脏代码**：无 TODO / FIXME / `System.out` / `printStackTrace`；测试全部使用 `@TempDir`，未触碰运行库（已 grep 验证）。

---

## 六、测试覆盖评估（审查重点 2）

后端 5 个新增/修改测试类 + 前端 2 个测试类整体质量**高于本仓库平均水平**（真库 + 真服务 + stub provider 的集成式断言，而不是纯 mock 自证）。逐条对照规范 §9：

| §9 用例 | 状态 | 说明 |
| --- | --- | --- |
| 迁移与单一操作 | ✅ | 但 **`MERGE_IDEMPOTENCY_CONFLICT`（同键不同参数 409，规范第 220 行）零覆盖**；`normalize()` 的 2～5 / 主来源 / 非法 ID 校验在重写测试中**被删除且未补** |
| 占用与释放 | ⚠️ | 仅根 + 子任务忙；**第五来源忙、后台服务占用、等待审批**用例被删未补；来源"可删除"仅验证了发消息与截止边界 |
| 普通 / fork / Swarm 隔离 | ⚠️ | 有 `verifyNoInteractions(handoff)` 强断言；但没有"执行和保存代码与基线一致"的守护测试（靠人工比对） |
| 长历史与重复 | ✅ | 含 >16 单元、尾部可检索、跨分片搜索 |
| 极端记录 / 附件 | ✅ | raw + 进度保留后暂停 |
| 本次失败条件 | ✅ | 4,183 bytes 仅存在于注释，建议加真实长度断言 |
| 当前单元恢复 | ⚠️ | 413 / 非法 JSON / 坏哈希有；**`MERGE_LENGTH_STOP`（`MergeSummaryService` 第 343 行）与 256 KiB `MERGE_RESPONSE_LIMIT` 的测试被删且未补** |
| 发布与取消竞态 | ⚠️ | 不确定提交、迟到响应、回滚已覆盖；**慢 hook、磁盘/消息提交失败**用例被删未补；"不误删 completed 包"间接验证 |
| 绑定与重复合并 | ⚠️ | 重复合并去重有；**新包未做 HandoffRead 可读性断言**；"删目标只清自己的包"仅单操作 |
| 接续资料与状态隔离 | ⚠️ | **工具 ID 隔离测试被删**（规范第 449 行明确要求保留）；E 的独立文件缓存 / 待办 / 设置隔离无自动化 |
| 读取与权限 | ✅ | 较强；`foreign-package-ref` 与穿越用例共用同一断言，无法区分命中哪条检查 |
| UI 与控制 | ⚠️ | active 恢复、真实锁集合已覆盖；**protocol=1 只读展示无实现分支也无测试**（目前仅靠后端 `canResume/canCancel=false` 间接只读）；**stale epoch 409 后刷新状态**、**取消期间有轮询在飞**两条前端路径无测试 |

补充发现：

- `artifact_manifests` / `artifact_entries` 导出（规范第 118 行硬要求）在测试夹具里**没有对应表**，`tableExists` 恒为 false → **该分支零执行**。
- `build()` 已不在在线入口使用，但 `MergePackageServiceTest` 仍有 17 处调用它、12 处调用 `seal()`，存在"大量测试养着死路径"的结构性问题（建议将复制 / 软链 / 配额断言迁移到 `seal`）。
- 前端两处**空洞断言**：`sessionMergeStore.test.ts:410` 未调用 `refresh()` 就断言 paused 不被改写（即使回归也通过）；`sessionMergeStore.test.ts:487-508` 的 `finishPolling?.()` 因 `control()` 先占用 `inflight` 而是死分支（该用例实际证明的是"根本不会并发发 GET"，本身符合规范"恢复/取消与轮询串行"，但断言写法有误导）。
- `control()` 会 `while (inflight) await inflight`（`sessionMergeStore.ts` 第 227 行），最坏情况下"取消"要等一个 15 秒超时的轮询结束，建议在 UI 上给出"正在取消"反馈。
- 前端 `selectMergeSourceIds` 仅在 `status === 'preparing'` 时返回 `lockedSourceSessionIds`（`sessionMergeStore.ts` 第 30-31 行），而规范第 421 行要求"来源是否锁住只看 `lockedSourceSessionIds`"；`paused` 且仍持锁时 UI 会显示未锁定（服务端 gate 仍会拒绝，属向用户展示不一致的低危问题）。

---

## 七、文档与工程卫生

1. **文档结论与本机实测不符**：`docs/session-merge-architecture-v2.md` 第 7 行写"后端全量 2,975 项：2,905 通过、70 跳过、0 失败/错误"，实测为 **2,900 通过 / 5 失败**（`WorkspaceFileBoundaryTest`、`ManagedProcessRunnerTest`，基线同样失败）。既然文档要随包提交，应改为"5 项与本改动无关的环境相关失败（已用 HEAD 干净副本对照复现）"。
2. **文档缺失内容**：规范第 9 节要求"必须包含一个接续开发样本"（A 改 `name`→`displayName`、B 未联调）与真实模型语义验收，文档第 3 / 471 行自认**尚未执行**。按该文档自身的验收标准，本改动目前**只完成了 stub 层验证**，语义验收仍是发布前的未完成项——必须在 PR 描述里明确，不能以"自动化全绿"代替。
3. **未跟踪文件**：6 个新增 Java 文件、2 个新增前端文件与架构文档均为 `??` 未跟踪状态（共 49 项变更）。提交时若使用 `git commit -am` 会**静默漏掉全部新增文件**；请显式 `git add` 后再提交。
4. 用户给出的忽略清单中 `frontend/e2e/message-copy-all.spec.ts` 在工作区**并不存在**，请确认是否属于另一未完成的任务。
5. 无密钥 / 凭据泄漏；`.env` 未被跟踪；测试未写入运行库。

---

## 八、发布前建议的处理顺序

**必须修（P1）**

1. `MergePackageService` 第 458-462 行改为 real-path 校验，堵住 symlink 穿透，并补一条测试。
2. 统一 `RECORD_REQUIRES_HANDLING:<recordRef>` 键（第 436 行）或对 `invalid_image` 改判 `failed`，消除"暂停 → 恢复 → 再暂停"死循环；补对应测试。
3. 把 `project()` 的绑定与整包校验做成请求级记忆，避免每轮 2～4 次全包哈希。

**建议修（P2）**

4. 修正 `CopyLimitException` 的错误码分类；`HandoffRead` 的 BUDGET_EXCEEDED 保留原码；为 `HANDOFF_CONTEXT_BUDGET_TOO_SMALL` / `HANDOFF_UNAVAILABLE` 加显式异常映射。
5. 补测：同键不同参数 409、来源集合校验、第五来源 / 后台 / 审批忙、慢 hook、`MERGE_LENGTH_STOP`、artifact 导出、工具 ID 隔离、protocol=1 只读、stale-epoch 恢复、取消与轮询竞态。
6. 统一注入风格与格式化；删除 `isExplicitCoreTool("HandoffRead")`；决定 `promptVersion` 的去留。
7. 修正文档中的测试结论数字，并显式标注"真实模型语义验收未执行、5 项环境相关失败"。

---

## 九、总体评价

- 本次改动在**非合并链路上是安全的**：隔离设计正确（7 个接入点全部带开关、核心链路文件零改动），全量回归与干净基线对照均未发现新增破坏。
- 合并域本身的架构落地度较高：封存即释放、单一名额、epoch 栅栏、原子发布、可恢复单元、只读交接工具与按轮有界入口都已按规范实现，测试质量明显优于 v1。
- 但仍存在 2 个正确性缺陷与 1 个会随包体积放大的性能缺陷，加上若干关键路径零覆盖与文档口径不准，**建议按第八节 P1/P2 修复后再提交 GitHub**，不要在修复前宣称会话合并 v2 已达到其自身文档定义的验收标准。

---

### 附：本次审查执行的验证命令

```sh
# 后端全量（工作区）
cd backend && ./mvnw -Dspring.profiles.active=test test

# 基线对照（HEAD 干净副本，验证 5 个失败是否为既有问题）
git worktree add .tmp/head-baseline HEAD --detach
cd .tmp/head-baseline/backend
./mvnw -q -Dspring.profiles.active=test \
  -Dtest=WorkspaceFileBoundaryTest,ManagedProcessRunnerTest \
  -DfailIfNoSpecifiedTests=false test
git worktree remove .tmp/head-baseline --force

# 前端全量 + 生产构建
cd frontend && npx vitest run --maxWorkers=2 --minWorkers=1
npm run build
```

**实测结果汇总**：后端 2,975 项（2,900 通过 / 5 失败 / 70 跳过，失败项与基线完全一致）；前端 103 文件（894 通过 / 16 跳过 / 0 失败）；前端生产构建通过（exit 0）。
