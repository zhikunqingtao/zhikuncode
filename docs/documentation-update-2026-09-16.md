# 文档与架构图适配记录（2026-09-16）

## 基线与版本边界

- 基线：2026-09-09 至 2026-09-16 的提交，HEAD `1d76bb7`，以及本次开始时已有的工作区修改。
- 盘点开始时，首页模型预选、本轮文件修改尚未提交；随本次提交纳入源码，正文与图中改标为本次新增，不代表已部署版本。
- 只调整文档与图示；未改变产品代码、API、数据结构或历史案例内容。
- “结构保留”表示按本周变更及对应模块复核后保留；不代表重新验证全部历史实现细节。源代码行数装饰已清理，总览规模饼图仍明确保留历史统计。

## 逐图清单

图号保留首次盘点编号作为稳定标识，不包括品牌与图标。已合并的图注明其目标；README 文本结构图另列。

### ZhikunCode-Architecture.html

| 图 | 处理 | 对应源码 / 核对范围 |
| --- | --- | --- |
| [1. React 前端](ZhikunCode-Architecture.html#audit-diagram-1) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [2. React 前端](ZhikunCode-Architecture.html#audit-diagram-2) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [3. REACT 前端](ZhikunCode-Architecture.html#audit-diagram-3) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [4. User (Browser)](ZhikunCode-Architecture.html#audit-diagram-4) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [5. QueryEngine.java — 8步循环 · 流式工具执行](ZhikunCode-Architecture.html#audit-diagram-5) | 结构保留；去除过时代码行数，补充局部滚动 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [6. QueryEngine.java L540-585 → SelfCorrectionLoop.java · 错误检测 → 修复指令](ZhikunCode-Architecture.html#sc-svg) | 结构保留；去除过时代码行数，补充局部滚动 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [7. WebSocket](ZhikunCode-Architecture.html#audit-diagram-7) | 结构保留；去除过时代码行数，补充局部滚动 | [session](../backend/src/main/java/com/aicodeassistant/session/SessionSnapshotService.java) |
| [8. AuthorizationService · OperationAnalyzerRegistry · ToolExecutionG](ZhikunCode-Architecture.html#audit-diagram-8) | 结构保留；去除过时代码行数，补充局部滚动 | [auth](../backend/src/main/java/com/aicodeassistant/authorization) |
| [9. AuthorizationService · PermissionGrantRepository · DurableInterac](ZhikunCode-Architecture.html#audit-diagram-9) | 结构保留；去除过时代码行数，补充局部滚动 | [auth](../backend/src/main/java/com/aicodeassistant/authorization) |
| [10. Project Selection](ZhikunCode-Architecture.html#audit-diagram-10) | 结构保留；去除过时代码行数，补充局部滚动 | [auth](../backend/src/main/java/com/aicodeassistant/authorization) |
| [11. Bash Command Input](ZhikunCode-Architecture.html#audit-diagram-11) | 结构保留；去除过时代码行数，补充局部滚动 | [tool](../backend/src/main/java/com/aicodeassistant/tool) |
| [12. Docker 沙箱隔离架构 — SandboxManager + SandboxConfig + ToolExecutionGat](ZhikunCode-Architecture.html#audit-diagram-12) | 结构保留；去除过时代码行数，补充局部滚动 | [tool](../backend/src/main/java/com/aicodeassistant/tool) |
| [13. LLM 多提供商抽象层架构](ZhikunCode-Architecture.html#audit-diagram-13) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [llm](../backend/src/main/java/com/aicodeassistant/llm) |
| [14. ModelTierService 多级模型降级链](ZhikunCode-Architecture.html#audit-diagram-14) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [llm](../backend/src/main/java/com/aicodeassistant/llm) |
| [15. API 密钥轮换机制 — Round-robin + 冷却跳过](ZhikunCode-Architecture.html#audit-diagram-15) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [llm](../backend/src/main/java/com/aicodeassistant/llm) |
| [16. STOMP over SockJS — UML 时序图](ZhikunCode-Architecture.html#audit-diagram-16) | 结构保留；去除过时代码行数，补充局部滚动 | [websocket](../frontend/src/api/dispatch.ts) |
| [17. LAYER 1 · CONNECTION](ZhikunCode-Architecture.html#ws-protocol-stack) | 结构保留；去除过时代码行数，补充局部滚动 | [websocket](../frontend/src/api/dispatch.ts) |
| [18. MCP 双向协议架构 · 4种传输层](ZhikunCode-Architecture.html#audit-diagram-18) | 结构保留；去除过时代码行数，补充局部滚动 | [mcp](../backend/src/main/java/com/aicodeassistant/mcp) |
| [19. MCP 协议双向通信架构 · Client/Server · 4种传输](ZhikunCode-Architecture.html#audit-diagram-19) | 结构保留；去除过时代码行数，补充局部滚动 | [mcp](../backend/src/main/java/com/aicodeassistant/mcp) |
| [20. 51 个工具 × 5 层执行管道](ZhikunCode-Architecture.html#audit-diagram-20) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [tool](../backend/src/main/java/com/aicodeassistant/tool) |
| [21. ToolExecutionPipeline · 9 阶段执行闭环 · StreamingToolExecutor 并发模型](ZhikunCode-Architecture.html#audit-diagram-21) | 结构保留；去除过时代码行数，补充局部滚动 | [tool](../backend/src/main/java/com/aicodeassistant/tool) |
| [22. ① 发现](ZhikunCode-Architecture.html#audit-diagram-22) | 结构保留；去除过时代码行数，补充局部滚动 | [plugin](../backend/src/main/java/com/aicodeassistant/plugin) |
| [23. 5模式](ZhikunCode-Architecture.html#audit-diagram-23) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [auth](../backend/src/main/java/com/aicodeassistant/authorization) |
| [24. L1](ZhikunCode-Architecture.html#audit-diagram-24) | 结构保留；去除过时代码行数，补充局部滚动 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [25. CLI Slash Command Architecture · 7-Layer Interactive Framework](ZhikunCode-Architecture.html#audit-diagram-25) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [cli](../backend/src/main/java/com/aicodeassistant/command) |
| [26. // VerifyJourneyTool.call() → VerifierFactory.selectVerifier() → ](ZhikunCode-Architecture.html#audit-diagram-26) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [verify](../backend/src/main/java/com/aicodeassistant/verify) |
| [27. // HttpApiVerifier (Java) → callIfAvailable → http_journey_run (P](ZhikunCode-Architecture.html#audit-diagram-27) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [verify](../backend/src/main/java/com/aicodeassistant/verify) |
| [28. 秒悟发布 · 验证与授权边界](ZhikunCode-Architecture.html#current-publication) | 已融入原有对应 SVG；移除图外补充块，保留版本边界 | [publish](../backend/src/main/java/com/aicodeassistant/artifact/meoo) |
| [29. declare](ZhikunCode-Architecture.html#audit-diagram-29) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [publish](../backend/src/main/java/com/aicodeassistant/artifact/meoo) |
| [30. 6 层级联上下文压缩管道 — ContextCascade.java · 统一协调器](ZhikunCode-Architecture.html#audit-diagram-30) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [31. ContextCascade 正常执行流程 — 每次 API 调用前无条件执行 (executePreApiCascade)](ZhikunCode-Architecture.html#audit-diagram-31) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [32. CompactService · 三区划分 (planCompaction) — CompactService.java L322](ZhikunCode-Architecture.html#tz-svg) | 结构保留；去除过时代码行数，补充局部滚动 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [33. Agent Loop 任务执行全链路时序图 — Session 7675c534](ZhikunCode-Architecture.html#audit-diagram-33) | 保留带 Session 与时间的历史示例 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [34. memdir + history + controller + frontend · Project记忆持久化与检索链路](ZhikunCode-Architecture.html#audit-diagram-34) | 结构保留；去除过时代码行数，补充局部滚动 | [memory](../backend/src/main/java/com/aicodeassistant/memdir) |
| [35. 存储层 · Storage](ZhikunCode-Architecture.html#audit-diagram-35) | 结构保留；去除过时代码行数，补充局部滚动 | [memory](../backend/src/main/java/com/aicodeassistant/memdir) |
| [36. SPI 服务发现流程](ZhikunCode-Architecture.html#audit-diagram-36) | 结构保留；去除过时代码行数，补充局部滚动 | [plugin](../backend/src/main/java/com/aicodeassistant/plugin) |
| [37. 高级任务](ZhikunCode-Architecture.html#audit-diagram-37) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [skill](../backend/src/main/java/com/aicodeassistant/skill/SkillRegistry.java) |
| [38. 运行与恢复 · 成功必须有依据](ZhikunCode-Architecture.html#current-recovery) | 已融入原有对应 SVG；移除图外补充块，保留版本边界 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [39. MDC Correlation](ZhikunCode-Architecture.html#audit-diagram-39) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [session](../backend/src/main/java/com/aicodeassistant/session/SessionSnapshotService.java) |
| [40. Leader Agent](ZhikunCode-Architecture.html#audit-diagram-40) | 结构保留；去除过时代码行数，补充局部滚动 | [coordinator](../backend/src/main/java/com/aicodeassistant/coordinator) |
| [41. CoordinatorWorkflowEngine — 统一调度入口](ZhikunCode-Architecture.html#coord-svg) | 结构保留；去除过时代码行数，补充局部滚动 | [coordinator](../backend/src/main/java/com/aicodeassistant/coordinator) |
| [42. Team 模式 · 固定角色分配](ZhikunCode-Architecture.html#audit-diagram-42) | 结构保留；去除过时代码行数，补充局部滚动 | [coordinator](../backend/src/main/java/com/aicodeassistant/coordinator) |
| [43. 对话前端 · 状态与只读投影](ZhikunCode-Architecture.html#current-frontend) | 已融入原有对应 SVG；移除图外补充块，保留版本边界 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [44. 业务领域 Store](ZhikunCode-Architecture.html#audit-diagram-44) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [45. dispatch.ts](ZhikunCode-Architecture.html#zst-dag) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [websocket](../frontend/src/api/dispatch.ts) |
| [46. Visualization · Code](ZhikunCode-Architecture.html#viz-matrix) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [47. SPA 单页应用 · 无 URL 路由 · Tab-based 导航 · Feature Flag 条件渲染](ZhikunCode-Architecture.html#rt-arch) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [48. FastAPI 应用入口](ZhikunCode-Architecture.html#audit-diagram-48) | 结构保留；去除过时代码行数，补充局部滚动 | [python](../python-service) |
| [49. Java Backend :8080](ZhikunCode-Architecture.html#pya-root) | 结构保留；去除过时代码行数，补充局部滚动 | [python](../python-service) |
| [50. 后端 → Python 服务完整调用流](ZhikunCode-Architecture.html#audit-diagram-50) | 结构保留；去除过时代码行数，补充局部滚动 | [python](../python-service) |
| [51. Single Container — zhikuncode:latest](ZhikunCode-Architecture.html#audit-diagram-51) | 更新标签或相邻说明；关联新增发布 / 恢复 / 前端图 | [deploy](../Dockerfile) |

### ZhikunCode-Capability-Overview.html

| 图 | 处理 | 对应源码 / 核对范围 |
| --- | --- | --- |
| [1. 当前能力补充 · 对话与发布](ZhikunCode-Capability-Overview.html#current-publication) | 图外摘要已移除；内容融入原图的前端层与后端发布节点 | [publish](../backend/src/main/java/com/aicodeassistant/artifact/meoo) |
| [2. ZhikunCode](ZhikunCode-Capability-Overview.html#audit-diagram-2) | 同步现有能力标签、技能与模型；保留结构 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |

### index.html

| 图 | 处理 | 对应源码 / 核对范围 |
| --- | --- | --- |
| [1. React 前端](index.html#audit-diagram-1) | 同步现有能力标签、技能与模型；保留结构 | [frontend](../frontend/src/components/message/turn/TurnCard.tsx) |
| [2. ZhikunCode](index.html#audit-diagram-2) | 同步现有能力标签、技能与模型；保留结构 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [3. User (Browser)](index.html#audit-diagram-3) | 同步现有能力标签、技能与模型；保留结构 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [4. Coordinator 指挥中心](index.html#audit-diagram-4) | 同步现有能力标签、技能与模型；保留结构 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [5. QueryEngine.java — 8步循环 · 流式工具执行](index.html#audit-diagram-5) | 同步现有能力标签、技能与模型；保留结构 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [6. 5+1 层级联上下文压缩管道 — ContextCascade.java · 统一协调器](index.html#audit-diagram-6) | 同步现有能力标签、技能与模型；保留结构 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [7. AuthorizationService · OperationAnalyzerRegistry · ToolExecutionG](index.html#audit-diagram-7) | 同步现有能力标签、技能与模型；保留结构 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [8. Bash Command Input](index.html#audit-diagram-8) | 同步现有能力标签、技能与模型；保留结构 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [9. Project → Run → Verified Artifact → Recovery](index.html#audit-diagram-9) | 同步现有能力标签、技能与模型；保留结构 | [engine](../backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java) |
| [10. 当前能力补充 · 对话与发布](index.html#current-publication) | 图外摘要已移除；内容融入原图的前端层与后端发布节点 | [publish](../backend/src/main/java/com/aicodeassistant/artifact/meoo) |

## README 与历史资料

- 中英文 README：对话体验、本轮修改边界、首页模型预选、运行恢复及发布部署说明同步；本轮修改移出配置章节。
- README 系统分层与 Docker 文本图保留，发布扩展通过部署指南与架构图说明；技能来源优先级、权限链路和多 Agent 文本图保留原有关系。
- README 的 413 编排归属改为 QueryEngine：两级压缩后仅在媒体相关错误下补充媒体恢复；耗尽报错终止。
- 服务端 app.model.default 与前端初始模型偏好分别描述，不合并称为同一默认值。
- 历史案例与 SWE-bench 报告只检查本地链接，性能、模型、截图和结论不改写为当前版本。

## 验证

- Chromium：393、768、1440px 检查通过；无整页横向溢出、融入节点文字越界、重复 ID 或失效页内锚点。键盘右方向键可滚动图示；架构页无脚本异常。
- 图内横向滚动保留原始阅读宽度；复杂图无需缩小成不可读文字。
- 本地文件链接检查覆盖双语 README、三个产品 HTML、历史案例与 benchmark 报告；外部站点可达性未作承诺。
- 不运行业务构建或测试：本次不改业务代码；文档检查不能代替既有工作区代码的回归测试。


## 图内融合收尾

- 移除三个页面全部图外补充块及其专用样式。
- 前端状态图使用原有留白展示轮次与文件投影；能力总览和首页同步更新原有前端节点。
- OSS 与秒悟在原有发布图中分别展示，保留各自证据与授权前提；总览在后端层原有留白放入秒悟发布链。
- 空回复恢复、工具终态恢复及可选发布依赖分别融入原有引擎、恢复与部署 SVG。
