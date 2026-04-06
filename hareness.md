
> **核心等式：普通模型 + 精密Harness = 超越顶尖模型的系统级智能**
>
> 不是模型能力的超越，而是**系统工程能力的超越**。本文从方法论维度剖析AI Native系统（Java 350K行 + Python 64K行 + 前端 139K行 + Prompt 18K行 + 知识库 1,144K行，合计 **1,714,861行/8,251文件**）如何通过七大Harness工程模式，让Qwen系列模型在受控运行环境中表现出超越顶尖大模型的系统级智能。本文所有技术方案均有对应的可运行系统和功能演示录屏，非概念验证。
> 相关文章：《龙虾云原生版+Manus千问版+豆包增强版》https://ata.atatech.org/articles/11020603735
> ATA视频直播：《ATA「技说」｜大模型实战：从复刻灵光到手搓豆包增强版》：https://ata.atatech.org/live/videos/8794
> D2大会分享：《以模型为积木、以工程为骨架、以用户价值为终点》https://grow.alibaba-inc.com/course/4810000000003401

---

## 一、核心范式：工程能力 > 模型能力

### 1.1 Harness Engineering——2026年AI工程核心范式

**Harness Engineering（模型外运行环境工程）** 是2026年AI工程领域的核心范式转移：

> **模型之外的一切工程——提示词架构、上下文管理、工具编排、护栏系统、可观测性——才是决定AI应用最终表现的关键因素。**

三大行业验证：
- **Mitchell Hashimoto**（HashiCorp联合创始人）将AI工程定义为六阶段：原始调用→提示词工程→流程编排→工具集成→评估体系→护栏系统
- **Anthropic** 提出 **Generator-Evaluator 架构**：一个模型生成，另一个独立评估，通过对抗性反馈持续提升
- **OpenAI** Codex 实验证明：**运行环境代码（100万行）远超模型调用代码（数千行），比例达100:1**

### 1.2 AI Native系统：系统规模概览

| 维度 | 数据                                                                                                                |
|------|-------------------------------------------------------------------------------------------------------------------|
| 技术栈 | Java 17 + Spring Boot 2.7.14 + Python微服务 + 前端，三语言全栈                                                               |
| 代码总规模 | **1,714,861行 / 8,251文件**（Java 350K/1,044文件 + Python 64K/226文件 + 前端 139K/205文件 + Prompt 18K/110文件 + 知识库 1,144K/6,666文件） |
| 架构分层 | **11层**，Service类**269个**（含34个AI模型Service），核心编排**47,200+行**                                                        |
| AI能力 | **215种**（内置98 + MCP 117），34个AI模型Service                                                                           |
| 决策系统 | **43种** DecisionType + **43个** 专属Prompt模板（11大类）                                                                   |
| 编排架构 | **8阶段流水线** + **10种协议适配器** + 两层渐进匹配                                                                                |
| 知识库 | **2,590+知识项**覆盖12大领域（游戏292 / 音乐1,348 / AI应用675 / 体感170等）                                                          |
| AgentBay沙箱 | **37种能力**，5大域（CodeSpace/Browser Use/Computer Use/Mobile Use/FileSystem）                                           |
| 交付形态 | Web + Android APK + iOS IPA **三端**                                                                                |
| 阿里云集成 | **11项**（DashScope/OSS/Tair/AgentBay/IMM/FC/SchedulerX/PDS/RDS/ECS/钉钉MCP广场）                                        |

#### 核心功能模块——Harness Engineering全域渗透

| 功能模块 | 知识库/领域规模 | 匹配/流水线架构 | 质量护栏 |
|---------|---------------|---------------|---------|
| **游戏大师** | 292示例，20类型32机制 | 四层渐进匹配 | 12维复杂度100分+Bug自动检测修复 |
| **AI音乐** | 1,348示例，~1,392文件 | 8层AI匹配+11阶段流水线 | 5维100分(阈值80)+乐理自检1,112行 |
| **帮我P图** | 6个AI模型协同 | AI Native+四场景自适应路由 | 四维评估(VL-Max,<8分重试)+Mask物理约束 |
| **万相视频** | Wan2.6系列模型 | 11种编辑模式+DualCanvas | tracking时空跟踪+7场景Prompt增强 |
| **体感游戏** | 170示例(85+85)，15类型 | 在线/离线双模式+7步流程 | 6项代码验证(≥4/6)+Bug检测修复 |
| **作业辅导** | 可视化三件套(D3/Manim/WASM) | 四阶段并行编排 | 答案一致性交叉验证+智能任务跳过(+60%) |
| **角色扮演** | character专模型 | 四模型串行编排 | 三级降级(视频→音频→文本)+4000tokens压缩 |
| **方言翻译** | 8种方言(ASR专模型) | 双模型协作+双WebSocket桥接 | uniqueId关联+长度双重护栏+isSentenceEnd |
| **PPT生成** | 三层知识库(10场景×12布局×模板) | 多阶段流水线+MCP联网搜索 | 四维度评分100分(<75分重新生成) |
| **手势游戏** | 119示例(59+60)，MediaPipe | 在线/离线双模式+8步流程 | 6项验证+静态检查扣分制100分+Bug修复 |
| **拍照专家** | 骨架模板库(10模板)+COCO 17关键点 | 三级降级位置识别+Java2D确定性渲染 | 5项解剖学验证+融合评分(AI 70%+距离30%) |
| **文件分析** | qwen-doc-turbo+qwen-long双模型 | 双模型协作+file-id引用锚定 | 1000万Token上下文+HTML→PDF/TXT转换 |
| **视频通话** | AI自动生成策略代码 | 8状态状态机+问题调度器 | 代码验证器+危险代码禁止+指数退避 |
| **Android打包** | Capacitor 5.x框架 | 八阶段流水线 | PURE/FULLSTACK双类型+权限自动检测注入 |
| **深度思考** | 平均引用24条研究 | 双阶段交互(ask+research) | 双会话ID隔离+HTML模板(成本0.1%) |
| **AI律师** | farui-plus法律专模 | 7种服务类型 | IntentDetection动态识别+1000+并发 |
| **穿搭大师** | 47种服装分类 | VL-Max分析+虚拟试穿 | 5维特征保护+6种骨骼图引导 |
| **体感健身** | 41动作，8训练计划，7肌群 | 前端+后端双重验证 | 5阶段状态机+扣分制100分+三层降级 |
| **AI舞蹈教室** | 30编舞，6舞种，12套课程 | 帧级多维评分+DTW时空规整 | THUNDER→LIGHTNING双模型降级+EMA |
| **iOS打包** | Xcode 14+/iOS SDK 16+ | 六阶段流水线 | 权限关键词匹配+自动Info.plist注入 |
| **浏览器自动化** | 10种Chromium能力，8站点反检测预设 | HttpAdapter→FastAPI:6600→Playwright→Chromium，三层工具业务优先级（MCP→BROWSER_TOOL→AgentBay） | BrowserToolServiceManager进程管理+自动重启+CapabilityTypeNormalizer 28条归一化映射 |

> **关键洞察**：上表21个模块的共同点——每个都独立实践了知识库驱动、多层匹配、量化护栏、确定性降级、双层分离。Harness Engineering不是编排引擎的独角戏，而是整个系统的工程DNA。
>
> **造工具哲学**：BROWSER_TOOL并非对业界方案的简单接入，而是**当现有浏览器自动化工具无法满足AI编排精度要求时，自造的AI专用浏览器工具箱**。Java进程管理→Python FastAPI→Playwright Chromium→10种标准化能力，每一层都为AI编排的"确定性执行"而设计。这体现了Harness Engineering的元洞察——**系统智能的天花板不取决于模型能力，而取决于你愿意为模型提供什么级别的运行环境**。

### 1.3 七大Harness方法论模式

本文围绕七大核心方法论组织，每种模式在多个业务领域独立验证：

| 方法论模式 | 核心思想 | 行业对标 | 典型跨域实践 | 章节 |
|-----------|---------|---------|------------|------|
| **注意力编程** | 通过语言结构控制模型注意力分布 | Lost in the Middle (Liu et al.) | 43模板/音乐八段式/领域黄金法则 | §三 |
| **知识驱动搜索空间收敛** | 多层渐进匹配将O(N)→O(1) | RAG增强（但非简单检索） | 编排L1+L2/游戏四层/音乐八层 | §四 |
| **Generator-Evaluator质量闭环** | 生成→检测→修复→再验证 | Anthropic Generator-Evaluator | SelfTest/体感6项/P图跨模态 | §五 |
| **AI认知+确定性执行双层分离** | AI做认知，工程做确定性执行 | OpenAI Codex 100:1 | Manim/OpenCV标注/BFS Mask | §六 |
| **专模型做专事** | 每个模型只做最擅长的事 | 业界MoE/专模型趋势 | 方言双模型/角色四模型 | §七 |
| **多层确定性降级** | 逐级优雅降级，零停机韧性 | Netflix Hystrix降级模式 | 五层防御/三层恢复升级 | §八 |
| **运行时自我进化** | 系统越用越聪明 | 在线学习/经验回放 | 六维飞轮/模式记忆/参数沉淀 | §九 |

**20条核心设计原则与章节溯源**：

本文从七大方法论模式出发组织叙事，最终在§十一.5汇聚为20条核心设计原则。每条原则在正文中都有对应的代码证据和跨域验证：

| 原则编号 | 核心内容 | 支撑章节 |
|---------|---------|---------|
| 1. 协议适配+AI/Rule双模 | 10种适配器统一接口+降级策略 | §二 2.4 |
| 2. 43种DecisionType+Prompt模板 | 结构化决策轨道 | §二 2.2 / §三 3.2 |
| 3. 五层纵深防御+URL保护 | 参数安全工程化 | §八 8.1 |
| 4. ToolPool预热+双模式执行 | 工具可用性保障 | §八 8.2 |
| 5. 配置驱动零硬编码 | 两级配置链+热重载 | §二 2.4 |
| 6. 双构建器Prompt架构+KV Cache | 注意力编程工程化 | §三 3.2 |
| 7. 620KB知识库+TairVector三索引 | 结构化系统记忆 | §四 4.1 |
| 8. Generator-Evaluator-Accumulator | 三角质量闭环 | §五 5.1 / §四 4.2 |
| 9. 29次决策链路追踪+18种SSE | 100%可观测可审计 | §十 10.5 |
| 10. ParamMappingEngine四阶段 | AI经验确定性固化 | §九 9.3 |
| 11. Strangler Pattern+委托服务 | 持续运行架构进化 | §九 9.4 |
| 12. 重规划+心跳守护+模式记忆 | 运行时自愈巡检+triedCapabilities防循环 | §八 8.5 / §九 9.1/9.2 |
| 13. 全链路optional+多级缓存 | 优雅降级抗熵 | §八 8.3 |
| 14. 2,590+知识库+渐进匹配 | 领域专家知识Harness | §四 4.3 |
| 15. 契约驱动+Bug检测修复 | 代码生成质量保障 | §四 4.3.2 / §五 5.4 |
| 16. Python动态代码+FluidSynth | AI能力边界扩展 | §六 6.5/6.7 |
| 17. file-id锚定+专模型协同 | 文档知识库抗幻觉 | §四 4.4 / §七 7.4 |
| 18. 117种MCP工具+双传输协议 | 外部能力工程化接入+MCP→BROWSER_TOOL跨域降级 | §八 8.6 / §十 10.1 |
| 19. 5种通知+SchedulerX调度 | 企业级系统集成 | §十 10.2/10.3 |
| 20. 六维进化飞轮 | Harness持续进化 | §九 9.2 |

> 后续章节遵循统一叙事结构：**原理阐述→代码证据→跨域验证→工程洞察**。§二先介绍七大模式的共同基座（编排引擎），§三-§九逐一深入每个方法论模式。

### 1.4 贯穿全文的设计哲学

**确定性优先，AI兜底**（Deterministic-First, AI-Fallback）——贯穿全部七大方法论的元原则：
- **能用规则解决的，不浪费LLM**（零Token/零延迟/100%确定性）
- **AI失败时，有工程化降级路径**（不崩溃、不阻断、可追溯）
- **AI成功时，将经验固化为规则**（ParamMappingEngine四阶段沉淀，§九.3）

后续章节中标注 *[确定性优先]* 的设计决策均遵循此原则。

---

## 二、编排基座——结构化决策的骨骼系统

> 编排引擎是七大方法论的共同基座。它通过8阶段流水线将AI的"自由度"约束在结构化轨道内，通过43种决策类型将开放式对话转化为精确铁轨，通过两层匹配将搜索空间从O(215)缩小到O(15)。

### 2.1 八阶段编排流水线

**代码证据**：`OrchestratorController.java`（1873行）

```
S0 多模态感知 → S1 任务拆解 → S2 能力匹配 → S3 动态工具创建 → 
S4 DAG规划 → S5 DAG执行 → S6 流式结果生成 → S6.5 自测试
```

每个阶段有严格的输入/输出契约，下一阶段只能处理上一阶段的标准化输出。AI的自由度被限制在每个阶段内部，阶段间数据流动完全由架构控制。

**核心代码逻辑**（`OrchestratorController.executeOrchestration()`）——八阶段流水线的完整编排流程：

```java
private void executeOrchestration(String sessionId, String prompt, List<FileInfo> attachments) {
    long orchestrationStartTime = System.currentTimeMillis();

    // S0: 多模态输入理解（在任务拆解之前）
    String effectivePrompt = prompt;
    EnrichedContext enrichedContext = null;
    if (multimodalInputEngine != null && attachments != null && !attachments.isEmpty()) {
        enrichedContext = multimodalInputEngine.process(prompt, attachments);
        if (enrichedContext != null && enrichedContext.getEnrichedPrompt() != null) {
            effectivePrompt = enrichedContext.getEnrichedPrompt();  // 多模态增强后的Prompt
        }
        // 多模态理解失败不阻塞流程，继续使用原始prompt
    }

    // S1: 任务拆解（AI驱动，DECOMPOSE_STRATEGY决策类型）
    List<SubTask> subtasks = taskDecomposerService.decompose(effectivePrompt, attachments);
    if (subtasks.isEmpty()) { progressService.pushError(sessionId, "任务拆解失败"); return; }
    progressService.pushDagStructure(sessionId, buildDagData(subtasks));  // 推送DAG结构

    // S2: AI智能能力匹配（L1+L2两层匹配）
    CapabilityMatchResult matchResult = aiCapabilityMatcherService.match(subtasks);

    // S3: 动态工具创建（仅当存在缺失能力时触发）
    if (!matchResult.getMissing().isEmpty()) {
        List<Capability> createdTools = aiToolFactoryService.createTools(matchResult.getMissing());
        // 回填capabilityId到对应SubTask + 容错：创建失败的工具过滤掉
    }

    // S4: 构建执行计划（含编排级超时deadline）
    long orchestrationTimeoutMs = orchestratorConfigService.getLong(
            "execution.orchestration_timeout_ms", 2400000L);
    long deadlineMs = orchestrationStartTime + orchestrationTimeoutMs - gracefulBuffer;
    ExecutionPlan plan = ExecutionPlan.builder()
            .sessionId(sessionId).originalRequest(effectivePrompt)
            .subtasks(subtasks).attachments(attachments)
            .deadline(deadlineMs).build();

    // S5: DAG执行（AI智能执行 + 并行调度 + 错误恢复）
    TaskResult result = aiExecutorEngine.execute(sessionId, plan);

    // S6: 流式结果生成（先推骨架HTML再逐块推完整结果）
    if (result.getSuccess()) {
        String resultHtml = aiResultGeneratorService.generateResultHtmlStreaming(
                sessionId, plan, result.getResults(), result.getStats());

        // S6.5: 自测试（质量增强，不阻断主流程）
        if (selfTestEngine != null) {
            SelfTestReport testReport = selfTestEngine.runTests(
                    sessionId, prompt, resultHtml, result);
        }
    }

    // 后续流程：示例自动沉淀 + 编排模式记忆 + 推送完成事件
    if (exampleAutoDepositService != null) exampleAutoDepositService.tryDeposit(plan, result);
    if (orchestrationPatternService != null) orchestrationPatternService.recordSuccessPattern(plan, result);
    progressService.pushComplete(sessionId, result.getResultHtml());
}
```

> **架构要点**：`executeOrchestration()`体现了"**阶段隔离+异常不阻塞**"的设计——S0多模态理解失败继续用原始prompt，S3工具创建部分失败过滤掉null继续执行，S6.5自测试异常设`passed=true`不中断主流程。每个阶段的耗时独立计量，通过`progressService`实时推送18种SSE事件给前端。

**真实日志**（session=aad6ca88，手势游戏+APK打包，743.5秒完成）：S0(24.4s) → S1(108.9s,8次AI决策) → S2(39.8s) → S5(535.5s,72%耗时) → S6(14.6s) → S6.5(13.5s,passed=true)。整个编排29次AI结构化决策，覆盖10种DecisionType。

#### 2.1.5 DAG执行引擎——Kahn拓扑排序与并行调度

**代码证据**：`AIExecutorEngine.java`（3519行）

S5阶段（DAG执行）是整个编排中最复杂也最耗时的环节（上例中占72%耗时）。`AIExecutorEngine`通过三个核心方法实现"**分层拓扑排序→层内并行执行→三级超时保护**"的执行架构。

**① `buildExecutionLayers()`——Kahn拓扑排序分层（含循环依赖降级）**

```java
private List<List<SubTask>> buildExecutionLayers(List<SubTask> tasks) {
    // 1. 构建任务映射 + 计算每个任务的入度（被依赖数）
    Map<String, Integer> inDegree = new HashMap<>();
    for (SubTask task : tasks) inDegree.put(task.getId(), 0);
    for (SubTask task : tasks) {
        if (task.getDependencies() != null) {
            for (String dep : task.getDependencies()) {
                if (taskMap.containsKey(dep))
                    inDegree.put(task.getId(), inDegree.get(task.getId()) + 1);
            }
        }
    }
    // 2. Kahn算法分层：入度为0的任务组成同一层（可并行执行）
    List<List<SubTask>> layers = new ArrayList<>();
    Set<String> processed = new HashSet<>();
    while (processed.size() < tasks.size()) {
        List<SubTask> currentLayer = new ArrayList<>();
        for (SubTask task : tasks) {
            if (!processed.contains(task.getId()) && inDegree.get(task.getId()) == 0)
                currentLayer.add(task);
        }
        // 3. 循环依赖降级——剩余任务无法找到入度为0的节点时，打包为一层执行
        if (currentLayer.isEmpty()) {
            for (SubTask task : tasks) {
                if (!processed.contains(task.getId())) currentLayer.add(task);
            }
            logger.error("[AIExecutorEngine] 检测到循环依赖，将剩余{}个任务打包为一层执行（降级处理）",
                    currentLayer.size());
        }
        layers.add(currentLayer);
        // 4. 移除已处理任务，更新后续任务的入度
        for (SubTask task : currentLayer) {
            processed.add(task.getId());
            // 将依赖当前任务的其他任务入度减1
        }
    }
    return layers;
}
```

> **设计决策**：循环依赖降级是关键——当AI任务拆解产生循环依赖（A→B→A）时，不报错中断，而是将所有剩余任务"打包"为同一层并行执行。这是"**确定性优先**"原则在DAG调度中的体现：宁可放弃最优并行度，也不让拓扑排序异常阻断编排。

**② `executeLayerParallel()`——CompletableFuture层内并行调度**

```java
private Map<String, Object> executeLayerParallel(String sessionId, List<SubTask> layer,
        Map<String, Object> previousResults, String originalRequest, List<SubTask> allTasks) {
    Map<String, Object> layerResults = new ConcurrentHashMap<>();
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (SubTask task : layer) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            // 依赖校验：前置任务无输出或包含error标记 → 跳过当前任务
            for (String depId : task.getDependencies()) {
                Object depResult = previousResults.get(depId);
                if (depResult == null || (depResult instanceof Map && ((Map)depResult).containsKey("error"))) {
                    task.setStatus(TaskStatus.FAILED);
                    layerResults.put(task.getId(), Map.of("error", "前置任务失败/超时"));
                    return;  // 跳过，不抛异常
                }
            }
            Object result = executeTask(sessionId, task, previousResults, ...);
            result = normalizeTaskOutput(result, task);  // 输出标准化
            layerResults.put(task.getId(), result);
        }, executorService);
        futures.add(future);
    }
    // 层级超时等待
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(taskTimeoutSeconds, TimeUnit.SECONDS);
    return layerResults;
}
```

**三个关键异常处理路径**：

| 异常类型 | 处理方式 | 传播行为 |
|---------|---------|---------|
| **ReplanSignalException** | 不在层内吞掉，通过CompletionException向上传播到`execute()` | 触发`DynamicRePlanningService.replan()` |
| **TimeoutException** | `future.cancel(true)`取消所有未完成future，标记超时任务为FAILED | 不传播，继续下一层 |
| **普通Exception** | 标记当前任务FAILED，`layerResults`放入error标记 | 不传播，后续层通过依赖校验跳过 |

**③ 三级超时架构**

| 超时级别 | 配置项 | 默认值 | 作用范围 |
|---------|--------|--------|---------|
| **编排级deadline** | `execution.orchestration_timeout_ms` | 2,400,000ms (40分钟) | 整个编排流程，每层执行前检查`plan.getDeadline()` |
| **层级超时** | `optimus.orchestrator.task-timeout` | 1,200s (20分钟) | 单层`CompletableFuture.allOf().get()`等待上限 |
| **任务级超时** | `optimus.browser-tool.timeout-ms` | 60,000ms (1分钟) | BROWSER_TOOL等特定工具类型的独立超时 |

编排级超时每层执行前检查——超时后将剩余所有层的任务标记为FAILED，推送"编排执行超时，正在整理已完成的结果..."，但**已完成的层结果保留**，继续进入S6结果生成。

**④ `normalizeTaskOutput()`——输出标准化**

MCP服务可能返回裸JSONArray（非Map），导致后续占位符解析（`task_N.output.field`）无法提取字段。`normalizeTaskOutput()`在任务执行完成后自动将non-Map类型输出包裹为Map格式：

- **配置驱动**：优先从能力注册表的`output schema`推断包裹字段名
- **配置兜底**：output schema未定义时使用配置默认字段名
- **开关控制**：`output_resolve.enable_non_map_normalize`配置可禁用

> **架构要点**：`AIExecutorEngine`的执行架构体现了"**最大化并行+最小化阻断**"的原则——同层任务通过CompletableFuture并行执行，依赖失败不阻断同层其他任务（仅跳过自身），超时不阻断已完成结果，循环依赖不阻断整体执行。`ReplanSignalException`是唯一能中断整个DAG执行的信号——它代表"当前方案根本不可行，需要AI重新规划"。

#### 2.1.6 编排路由决策——意图协商与全栈降级三级分类

8阶段流水线描述了"正常路径"的执行顺序，但在实际工程中，**流水线的入口和分叉点**同样重要——系统需要在进入流水线之前判断"是否需要协商"，以及在匹配阶段之后判断"是否应该跳过DAG编排"。这两个路由决策由`IntentNegotiationService`和`isStandaloneAppRequest()`分别承担。

**① IntentNegotiationService——S1前置意图协商（按需启用，协商阈值：clearness_score < 0.7 时触发）**

**代码证据**：`IntentNegotiationService.java`（162行）

当用户需求模糊度超过阈值时，系统在进入S1任务拆解之前主动发起"协商"——通过SSE推送候选方案让用户选择，避免在模糊需求上浪费编排资源：

```java
@Service
@ConditionalOnProperty(name = "orchestrator.intent_negotiation.enabled", 
        havingValue = "true", matchIfMissing = false)  // 默认关闭，零侵入
public class IntentNegotiationService {
    private static final double DEFAULT_CLEARNESS_THRESHOLD = 0.7;

    public boolean analyzeAndNegotiateIfNeeded(String sessionId, String prompt) {
        // Step 1: 调用INTENT_NEGOTIATION决策类型分析需求清晰度
        AIDecision decision = aiDecisionEngine.makeDecision(
                DecisionType.INTENT_NEGOTIATION, context, true);  // useCache=true
        
        // Step 2: 解析清晰度评分（含markdown代码块清理+JSON提取）
        NegotiationResult result = parseNegotiationResult(decision.getRawResponse());
        
        // Step 3: 低于阈值 + AI判定需协商 + 有可选方案 → 推送SSE协商事件
        if (result.needsClarification && result.clearnessScore < threshold 
                && result.options != null && !result.options.isEmpty()) {
            progressService.pushClarificationRequest(
                    sessionId, result.clearnessScore, result.options);
            return true;  // 中断主流程，等待用户选择
        }
        return false;  // 清晰度足够，继续S1
    }
}
```

**协商结果结构**——AI返回的每个候选方案包含三个字段：`label`（方案名称）、`description`（方案描述）、`refined_prompt`（优化后的精确需求），前端展示后用户选择的`refined_prompt`替换原始prompt重新进入流水线。

**工程安全机制**：`@ConditionalOnProperty(matchIfMissing=false)`确保该服务默认不加载，不影响任何现有流程；`analyzeAndNegotiateIfNeeded()`内部所有异常被catch后返回`false`（继续主流程），确保协商失败不阻断编排。

**② isStandaloneAppRequest()——S2后置全栈降级三级分类**

**代码证据**：`OrchestratorController.java`（lines 1499-1558）

当S2能力匹配完成后、S3工具创建之前，系统需要判断"用户是否想要一个完整的单体应用"——如果是，则跳过DAG编排，降级为独立应用模式（直接调用全栈代码生成能力）。这个判断采用**三级分类策略**，体现"确定性优先，AI兜底"原则：

```java
private boolean isStandaloneAppRequest(List<SubTask> subtasks, String prompt) {
    if (subtasks == null || subtasks.size() != 1) return false;
    
    // === 第1级：确定性快速路径（零LLM调用）    ===
    String capType = subtasks.get(0).getCapabilityType();
    if (capType != null && (capType.contains("fullstack") || capType.contains("html_game")
            || capType.contains("motion_game") || capType.contains("gesture_game"))) {
        return true;  // 能力类型已明确为全栈类型
    }
    
    // === 第2级：LLM意图分类（INTENT_CLASSIFY决策类型） ===
    AIDecision decision = aiDecisionEngine.makeDecision(
            DecisionType.INTENT_CLASSIFY, context, true);  // useCache=true, 5分钟TTL
    // 解析三种分类结果：
    //   STANDALONE_APP         → 完整单体应用（跳过DAG编排）
    //   MULTI_TASK_ORCHESTRATION → 多能力协作（继续DAG编排）
    //   SINGLE_CAPABILITY      → 单一能力调用（直接执行）
    return "STANDALONE_APP".equals(intent) && confidence >= 0.6;
    
    // === 第3级：降级关键词匹配（仅LLM不可用时触发） ===
    // "全栈应用" / "web app" / "单页应用" 关键词匹配
    // + 子任务能力类型包含 html/game/web 关键词兜底
}
```

**三级分类与流水线的集成关系**：

| 路由决策 | 触发时机 | 决策类型 | 影响 |
|---------|---------|---------|------|
| **意图协商** | S1之前（可选） | INTENT_NEGOTIATION | 模糊需求→SSE推送候选方案→用户选择后重新进入 |
| **全栈降级** | S2之后、S3之前 | INTENT_CLASSIFY | 单任务全栈请求→跳过DAG编排→独立应用模式 |

> **架构要点**：两个路由决策体现了流水线的"**前置过滤+后置分叉**"设计——意图协商在入口处过滤模糊需求（减少无效编排），全栈降级在匹配后识别特殊路径（避免不必要的DAG复杂性）。三级分类的"快速路径→LLM→关键词"递进策略确保在确定性、准确性、可用性三个维度的最优平衡。`INTENT_CLASSIFY`的`confidence >= 0.6`阈值低于常规匹配的0.7/0.85阈值——因为此处是"全栈降级"场景，误判为全栈的代价（少走DAG）低于漏判的代价（全栈需求走复杂编排必然失败）。

#### 2.1.7 MultimodalInputEngine——S0多模态输入理解引擎

**代码证据**：`MultimodalInputEngine.java`（410行）

S0阶段是整个编排流水线的第一道关口——将用户上传的多种格式文件（图片、文档、音频等）转化为AI可理解的enrichedPrompt。`MultimodalInputEngine.process()`通过"**PDS分享检测→策略模式提取→并行理解→AI融合→意图推断**"五步流程，在不阻塞主流程的前提下完成多模态输入增强。

**核心代码逻辑**（`MultimodalInputEngine.process()`）——五步多模态理解流程：

```java
@Service
public class MultimodalInputEngine {
    @Autowired(required = false) private List<FileContentExtractor> extractors;  // 策略模式
    @Autowired(required = false) private Qwen3MaxTextService qwen3MaxTextService;
    @Autowired(required = false) private PDSShareResolver pdsShareResolver;
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);  // 4线程并行
    @Value("${optimus.multimodal.file-timeout:60}") private int fileTimeoutSeconds;
    @Value("${optimus.multimodal.total-timeout:180}") private int totalTimeoutSeconds;

    public EnrichedContext process(String originalPrompt, List<FileInfo> attachments) {
        // Step 1: PDS分享链接自动检测——prompt中的pds://链接透明注入为附件
        List<FileInfo> pdsShareFiles = detectAndResolvePDSShareLinks(originalPrompt);
        if (!pdsShareFiles.isEmpty()) {
            attachments = new ArrayList<>(attachments);  // 保护原始列表
            attachments.addAll(pdsShareFiles);
        }

        // 快速路径：无附件直接返回原始prompt（零开销）
        if (!enabled || attachments == null || attachments.isEmpty()) {
            return EnrichedContext.builder().enrichedPrompt(originalPrompt).build();
        }

        // Step 2: 策略模式选择FileContentExtractor（按FileCategory分派）
        // Step 3: CompletableFuture并行理解所有文件（4线程池，180s总超时）
        List<FileUnderstanding> understandings = processAllFiles(attachments, originalPrompt);
        List<FileUnderstanding> successful = understandings.stream()
                .filter(FileUnderstanding::isSuccess).collect(Collectors.toList());

        // Step 4: AI融合enrichedPrompt（降级：直接拼接原始prompt+文件理解）
        String enrichedPrompt = fusePromptWithUnderstandings(originalPrompt, successful);
        // Step 5: 推断用户综合意图（一句话概括）
        String inferredIntent = inferUserIntent(originalPrompt, successful);

        return EnrichedContext.builder()
                .originalPrompt(originalPrompt).enrichedPrompt(enrichedPrompt)
                .fileUnderstandings(understandings).inferredIntent(inferredIntent).build();
    }
}
```

**五大工程设计特点**：

| 设计特点 | 实现细节 | 工程意义 |
|---------|---------|---------|
| **PDS分享链接透明注入** | `detectAndResolvePDSShareLinks()`检测prompt中的PDS链接，自动解析为FileInfo注入附件列表 | 用户无需手动上传阿里云PDS文件，链接自动转化为附件 |
| **策略模式提取器** | `List<FileContentExtractor>`按`supports(FileCategory)`选择提取器，无匹配时返回基础元信息 | 新增文件类型只需实现`FileContentExtractor`接口，零代码侵入 |
| **CompletableFuture并行+超时** | 4线程`newFixedThreadPool`，`allOf().get(180s)`总超时，超时后收集已完成结果 | 单文件理解失败不影响整体，超时不阻断后续阶段 |
| **AI融合→拼接降级** | `fusePromptWithUnderstandings()`先调用Qwen3-Max语义融合，异常/空响应时降级到直接拼接 | AI增强prompt质量，但绝不因AI失败而丢失文件信息 |
| **FileCategory自动推断** | `FileInfo.inferCategory(contentType)`根据MIME类型自动推断，未识别时设为`OTHER` | 用户无需声明文件类型，系统自动处理 |

> **架构要点**：`MultimodalInputEngine`体现了S0阶段的"**增强但不阻断**"设计——PDS检测异常返回空列表、文件理解失败标记`success=false`但不阻断其他文件、AI融合失败降级到拼接、整个S0异常时`executeOrchestration()`继续使用原始prompt。4线程并行理解+180s总超时确保了S0阶段的耗时上限可控。

#### 2.1.8 TaskDecomposerService——S1任务拆解7.5步优化流水线

**代码证据**：`TaskDecomposerService.java`（~1666行）

S1阶段是8阶段流水线中逻辑最复杂的单一服务——`decompose()`入口方法先查询编排模式记忆，再分派到`decomposeOptimized()`或`decomposeOriginal()`。优化路径包含完整的**7.5步内部流水线**，覆盖从匹配、生成、优化到验证修复的全链路：

**入口方法**——编排模式记忆查询 + 路径分派：

```java
public List<SubTask> decompose(String userPrompt, List<FileInfo> attachments) {
    // 编排模式记忆读取（默认关闭，仅read_enabled=true时生效）
    if (orchestrationPatternService != null) {
        PatternEntry pattern = orchestrationPatternService.findMatchingPattern(userPrompt, null);
        if (pattern != null && pattern.getSuccessCount() >= 2) {
            // 命中历史成功模式，当前仅记录日志（未来可直接复用跳过AI拆解）
            logger.info("[TaskDecomposer] 命中历史编排模式: successCount={}", pattern.getSuccessCount());
        }
    }
    // 根据配置和组件可用性选择优化/原始流程
    if (optimizedEnabled && isOptimizedComponentsAvailable()) {
        return decomposeOptimized(userPrompt, attachments);  // 7.5步优化流水线
    } else {
        return decomposeOriginal(userPrompt, attachments);    // 兼容原始流程
    }
}
```

**核心代码逻辑**（`decomposeOptimized()`）——7.5步优化分解流水线：

```java
private List<SubTask> decomposeOptimized(String userPrompt, List<FileInfo> attachments) {
    try {
        // Step 1: L1+L2 两层匹配（215种能力→~15种→精确匹配）
        TwoLayerMatchResult matchResult = twoLayerMatcher.match(userPrompt, attachments);

        // Step 2: 示例匹配（含ORCH-DIAG诊断日志：capabilityId有效性+示例与L2一致性检查）
        String capabilityId = matchResult.getL2Result() != null ? 
                matchResult.getL2Result().getCapabilityId() : null;
        List<CapabilityExample> examples = exampleKnowledgeBase.findSimilarExamples(
                capabilityId, userPrompt, 3);

        // Step 3: 构建六段式Prompt（PromptStructureBuilder优先，降级到OrchestratorPromptTemplate四段式）
        String prompt = buildOptimizedPrompt(userPrompt, attachments, examples, matchResult);

        // Step 4: 调用Qwen3-Max生成任务列表
        String response = qwen3MaxTextService.generateText(prompt);
        List<SubTask> subTasks = parseResponse(response);  // 含attemptLLMJsonRepair()修复降级

        // Step 4.5: LLM驱动的冗余任务链优化（TASK_CHAIN_OPTIMIZE决策类型）
        subTasks = optimizeTaskChainWithAI(subTasks, userPrompt);

        // Step 4.6: LLM驱动的依赖关系审查（DEPENDENCY_REVIEW决策类型，修复遗漏依赖）
        subTasks = reviewDependenciesWithAI(subTasks, userPrompt);

        // Step 5: TaskValidator 7项指标验证
        ValidationResult validation = taskValidator.validate(subTasks, null);

        // Step 6: TaskAutoFixer 3轮渐进式修复（验证不通过时触发）
        if (!validation.isAcceptable() && !subTasks.isEmpty()) {
            FixResult fixResult = taskAutoFixer.fix(subTasks, validation);
            if (fixResult.isSuccess()) subTasks = fixResult.getFixedTasks();
        }

        // Step 7: 输出 + Legacy验证过滤
        TaskValidationResult legacyValidation = validateSubtasks(subTasks);
        if (!legacyValidation.isValid()) subTasks = legacyValidation.getValidatedTasks();

        // Step 7.5: ParamTransformHints自动补全（从task.input自动推断缺失的参数转换提示）
        ensureParamTransformHints(subTasks);

        return subTasks;
    } catch (Exception e) {
        logger.error("[TaskDecomposer-Optimized] 优化分解失败，降级到原始流程: {}", e.getMessage());
        return decomposeOriginal(userPrompt, attachments);  // 异常安全降级
    }
}
```

**7.5步流水线架构图**：

```
decompose() 入口
  ├── 编排模式记忆查询（OrchestrationPatternService，可选）
  └── decomposeOptimized()
        │
        ├── Step 1: L1+L2两层匹配（TwoLayerMatcher）
        ├── Step 2: 示例匹配（ExampleKnowledgeBase + ORCH-DIAG诊断）
        ├── Step 3: 六段式Prompt构建（PromptStructureBuilder）
        ├── Step 4: LLM生成（Qwen3-Max + JSON修复降级）
        ├── Step 4.5: 冗余任务链优化（TASK_CHAIN_OPTIMIZE决策）
        ├── Step 4.6: 依赖关系审查（DEPENDENCY_REVIEW决策）
        ├── Step 5: 7项验证（TaskValidator）
        ├── Step 6: 3轮修复（TaskAutoFixer）
        ├── Step 7: Legacy验证过滤 + 输出
        └── Step 7.5: ParamTransformHints自动补全
        │
        └── [异常] → decomposeOriginal() 降级
```

**关键工程细节**：

| 步骤 | 工程细节 | DecisionType |
|------|---------|-------------|
| **Step 2** | ORCH-DIAG诊断日志：对比L2推荐的capabilityId与示例的capabilityId，输出一致性分析 | — |
| **Step 3** | `buildOptimizedPrompt()`优先调用`PromptStructureBuilder`六段式构建，组件不可用时降级到`OrchestratorPromptTemplate`四段式 | — |
| **Step 4** | `parseResponse()`解析失败时调用`attemptLLMJsonRepair()`通过`JSON_STRUCTURE_REPAIR`决策类型修复 | JSON_STRUCTURE_REPAIR |
| **Step 4.5** | `optimizeTaskChainWithAI()`识别并合并冗余任务链（如两个相似的浏览器任务合并为一个） | TASK_CHAIN_OPTIMIZE |
| **Step 4.6** | `reviewDependenciesWithAI()`审查任务间数据流依赖关系，补充遗漏的`dependencies`字段 | DEPENDENCY_REVIEW |
| **Step 7.5** | `ensureParamTransformHints()`从`task.input`中自动推断URL模式、separator等参数转换提示 | — |

> **架构要点**：`decomposeOptimized()`的异常处理是关键——整个7.5步流水线被`try-catch`包裹，**任何步骤失败都降级到`decomposeOriginal()`**，确保S1阶段始终有输出。7.5步中有5次AI调用（Step 2示例匹配、Step 4 LLM生成、Step 4.5任务链优化、Step 4.6依赖审查、Step 6自动修复），但只有Step 4（LLM生成）是必须成功的——其余AI步骤失败时静默跳过，体现了"**AI增强但不阻断**"的设计原则。`PipelineStage`枚举+`PerformanceMetrics`为每个步骤独立计时，生成性能报告用于后续优化。

#### 2.1.9 AIResultGeneratorService——S6流式结果生成引擎

**代码证据**：`AIResultGeneratorService.java`（709行）+ `FrontendKnowledgeBridge.java`（270行）

S6阶段将DAG执行结果转化为用户可见的HTML展示。核心创新是"**骨架先推+分块补充**"的流式渲染策略，让用户在<2秒内看到结果框架，同时AI在后台生成完整内容。

**核心代码逻辑**（`generateResultHtmlStreaming()`）——流式三步生成：

```java
@Service
public class AIResultGeneratorService {
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();  // 模板缓存（55s→1s优化）
    private static final int MAX_RESULT_CONTENT_LENGTH = 40000;   // 40K结果截断
    private static final int STREAMING_CHUNK_SIZE = 2048;         // 2KB分块

    @Autowired(required = false) private FrontendKnowledgeBridge frontendKnowledgeBridge;

    public String generateResultHtmlStreaming(String sessionId, ExecutionPlan plan,
                                              Map<String, Object> results, ExecutionStats stats) {
        // 降级保护：progressService不可用→同步模式
        if (progressService == null) return generateResultHtml(plan, results, stats);

        try {
            // Step 1: 立即推送骨架HTML（<2秒到达前端，含shimmer加载动画+真实统计信息）
            String skeletonHtml = generateSkeletonHtml(plan, stats);
            progressService.pushStreamingResult(sessionId, 0, skeletonHtml, false);

            // Step 2: AI生成完整结果（RESULT_STRATEGY决策类型+FrontendKnowledgeBridge可视化注入）
            String fullHtml = generateResultHtml(plan, results, stats);

            // Step 3: 标签边界感知的分块推送（2048字符/块）
            List<String> chunks = splitHtmlIntoChunks(fullHtml, STREAMING_CHUNK_SIZE);
            for (int i = 0; i < chunks.size(); i++) {
                progressService.pushStreamingResult(sessionId, i + 1, chunks.get(i), i == chunks.size() - 1);
            }
            return fullHtml;
        } catch (Exception e) {
            // 异常降级到同步模式
            String fallbackHtml = generateResultHtml(plan, results, stats);
            progressService.pushStreamingResult(sessionId, 1, fallbackHtml, true);
            return fallbackHtml;
        }
    }
}
```

**骨架HTML设计**——`generateSkeletonHtml()`生成包含**真实统计数据**和**CSS shimmer动画**的占位框架：

```java
String generateSkeletonHtml(ExecutionPlan plan, ExecutionStats stats) {
    // CSS shimmer动画：skeleton-line元素通过@keyframes shimmer实现从左到右的渐变流动
    // 骨架卡片数量 = min(实际任务数, 5)，每个卡片3行skeleton-line（60%/90%/75%宽度）
    // Header区域：真实的totalTasks/completedTasks/failedTasks/duration统计
    // 用户在等待AI生成时已能看到完成进度和耗时信息
}
```

**四大核心机制**：

| 机制 | 实现细节 | 工程意义 |
|------|---------|---------|
| **FrontendKnowledgeBridge可视化注入** | 分析结果数据类型（numeric_array/image_urls/markdown_text等6种）→映射可视化方式→推荐CDN库+加载前端示例代码 | AI生成HTML时可参考Chart.js/Marked.js示例，输出可视化质量更高 |
| **标签边界感知分块** | `splitHtmlIntoChunks()`在2048字符处向后搜索最近的`>`，在`chunkSize/4`范围内找到标签闭合点切分 | 避免在`<div class=`中间切分导致前端渲染异常 |
| **模板缓存优化** | `ConcurrentHashMap`按任务类型签名缓存AI生成的HTML模板，缓存命中时直接填充数据 | 相同类型任务重复执行时结果生成从55s降至1s |
| **结果内容智能截断** | `truncateForAIContext()`保留前70%+后20%，中间截断并标注原始长度 | 防止结果内容超大导致Prompt超出40K安全限制 |

**任务类型分析——AI优先+规则降级**：

```java
// AI分析：调用RESULT_STRATEGY决策类型识别任务类型
String taskType = analyzeTaskTypeWithAI(request);

// 规则降级（6种类型）：
// "分析/检测/诊断" → analysis | "生成/创建/制作" → generation
// "查询/搜索/获取" → query    | "转换/格式化" → transform
// "处理/优化/修复" → process  | 其他 → general
```

> **架构要点**：`AIResultGeneratorService`的流式设计解决了S6阶段的"**用户等待焦虑**"问题——骨架HTML在<2秒到达前端（包含真实完成统计+shimmer动画），用户立即感知到"系统在工作"。`FrontendKnowledgeBridge`的可视化注入体现了"**知识驱动**"原则——不是让AI从零猜测如何可视化数据，而是通过领域知识匹配告诉AI"这种数据类型应该用什么前端库和示例代码"。AI生成失败时降级到`generateDefaultHtml()`规则模板，确保S6始终有输出。

### 2.2 43种决策类型——从开放式对话到精确铁轨

**代码证据**：`DecisionType.java`（344行）

| 决策类型 | Prompt模板文件 | 职责 |
|---------|--------------|------|
| DECOMPOSE_STRATEGY | ai_decision.txt | 任务拆解策略 |
| MATCH_STRATEGY | ai_capability_match.txt | 能力匹配策略 |
| EXECUTE_STRATEGY | ai_execution_plan.txt | 执行调度策略 |
| RECOVERY_STRATEGY | ai_error_recovery.txt | 错误恢复策略 |
| ADAPTER_SELECT | ai_adapter_select.txt | 适配器选择 |
| L1_CATEGORY_MATCH | ai_l1_match.txt | L1功能域识别 |
| L2_CAPABILITY_MATCH | ai_l2_match.txt | L2精确匹配 |
| TASK_VALIDATE | ai_task_validate.txt | 任务验证（7项指标） |
| TASK_AUTO_FIX | ai_task_fix.txt | 渐进式修复（3轮） |
| FORMAT_COMPATIBILITY_CHECK | ai_format_compatibility_check.txt | 格式兼容性校验 |
| TASK_CHAIN_OPTIMIZE | ai_task_chain_optimize.txt | 冗余任务链优化 |
| DEPENDENCY_REVIEW | ai_dependency_review.txt | 依赖关系审查 |
| SELF_TEST_PLAN / SELF_TEST_EVALUATE | ai_self_test_*.txt | 自测试计划+结果评估 |
| HEARTBEAT_EVALUATE | ai_heartbeat_evaluate.txt | 心跳守护评估 |
| INTENT_CLASSIFY | ai_intent_classify.txt | 用户意图分类 |
| PLACEHOLDER_RESOLVE | ai_placeholder_resolve.txt | 占位符智能解析 |
| JSON_STRUCTURE_REPAIR | ai_json_structure_repair.txt | JSON结构修复 |
| TOOL_SMOKE_DIAGNOSTIC / FIX | ai_tool_smoke_*.txt | 工具冒烟测试诊断+修复 |
| ... | ... | 共43种 |

每次AI调用都不是"开放式对话"，而是约束明确的结构化决策任务。43种DecisionType = 43条"铁轨"，模型在每条铁轨上只需做一个精确决策——这是"分而治之"工程思想的体现。

**核心代码逻辑**（`DecisionType.java`）——每个枚举值绑定唯一的code、描述和Prompt模板文件，形成"决策类型→Prompt模板"的一一对应关系：

```java
public enum DecisionType {
    // === 编排核心决策 ===
    DECOMPOSE_STRATEGY("decompose_strategy", "任务拆解策略", "ai_decision.txt"),
    MATCH_STRATEGY("match_strategy", "能力匹配策略", "ai_capability_match.txt"),
    EXECUTE_STRATEGY("execute_strategy", "执行调度策略", "ai_execution_plan.txt"),
    RECOVERY_STRATEGY("recovery_strategy", "错误恢复策略", "ai_error_recovery.txt"),
    ADAPTER_SELECT("adapter_select", "适配器选择策略", "ai_adapter_select.txt"),
    
    // === 两层渐进式匹配 ===
    L1_CATEGORY_MATCH("l1_category_match", "L1任务类型识别", "ai_l1_match.txt"),
    L2_CAPABILITY_MATCH("l2_capability_match", "L2能力精确匹配", "ai_l2_match.txt"),
    
    // === 质量保证 ===
    TASK_VALIDATE("task_validate", "任务验证", "ai_task_validate.txt"),
    TASK_AUTO_FIX("task_auto_fix", "任务自动修复", "ai_task_fix.txt"),
    FORMAT_COMPATIBILITY_CHECK("format_compatibility_check", "格式兼容性校验", "ai_format_compatibility_check.txt"),
    SELF_TEST_PLAN("self_test_plan", "自测试计划生成", "ai_self_test_plan.txt"),
    SELF_TEST_EVALUATE("self_test_evaluate", "自测试结果评估", "ai_self_test_evaluate.txt"),
    
    // === 任务优化 ===
    TASK_CHAIN_OPTIMIZE("task_chain_optimize", "冗余任务链优化", "ai_task_chain_optimize.txt"),
    DEPENDENCY_REVIEW("dependency_review", "任务依赖关系审查", "ai_dependency_review.txt"),
    
    // === 系统运维 ===
    HEARTBEAT_EVALUATE("heartbeat_evaluate", "心跳评估", "ai_heartbeat_evaluate.txt"),
    INTENT_CLASSIFY("intent_classify", "用户意图分类", "ai_intent_classify.txt"),
    JSON_STRUCTURE_REPAIR("json_structure_repair", "JSON结构修复", "ai_json_structure_repair.txt"),
    PLACEHOLDER_RESOLVE("placeholder_resolve", "占位符智能解析", "ai_placeholder_resolve.txt"),
    SEARCH_STRATEGY("search_strategy", "搜索策略决策", "ai_search_strategy.txt"),
    // ... 共43种

    private final String code;
    private final String description;
    private final String promptTemplate;
    
    // 根据code反查决策类型，未匹配时降级到DECOMPOSE_STRATEGY
    public static DecisionType fromCode(String code) {
        for (DecisionType type : values()) {
            if (type.code.equalsIgnoreCase(code)) return type;
        }
        return DECOMPOSE_STRATEGY;  // 安全降级
    }
}
```

> **架构要点**：每个DecisionType携带的`promptTemplate`字段直接指向`orchestrator_knowledge/prompts/`目录下的模板文件，实现"决策类型→Prompt模板→AI行为约束"的三级绑定。`fromCode()`方法在无法匹配时降级到`DECOMPOSE_STRATEGY`，体现"确定性优先"原则。

#### 2.2.5 AIDecisionEngine——43种决策的统一执行引擎

**代码证据**：`AIDecisionEngine.java`（639行）

DecisionType定义了43条"铁轨"，而`AIDecisionEngine`是在这些铁轨上实际驱动列车的引擎——**所有AI结构化决策都通过`makeDecision(DecisionType, context, useCache)`统一入口执行**。它的核心职责是：加载Prompt模板→构建Prompt→调用大模型→解析响应→缓存结果，同时在每个环节提供工程化的安全保障和降级路径。

**核心代码逻辑**（`AIDecisionEngine.makeDecision()`）——决策执行的完整流程：

```java
@Component
public class AIDecisionEngine {
    private final Map<DecisionType, String> promptTemplates = new ConcurrentHashMap<>();  // Prompt模板缓存
    private final Map<String, CachedDecision> decisionCache = new ConcurrentHashMap<>();  // 本地决策缓存
    private static final long CACHE_TTL_MS = 300000;  // 5分钟过期
    private static final int MAX_PROMPT_SAFE_LENGTH = 40000;  // 40K字符安全防线

    @PostConstruct
    public void init() {
        // 启动时预加载所有43个Prompt模板到内存
        for (DecisionType type : DecisionType.values()) {
            String template = loadPromptTemplate(type.getPromptTemplate());
            if (template != null) promptTemplates.put(type, template);
        }
    }

    public AIDecision makeDecision(DecisionType decisionType, Map<String, String> context, boolean useCache) {
        // Step 1: 两级缓存查找（本地ConcurrentHashMap优先 → Tair分布式兜底 → 回填本地）
        if (useCache) {
            String cacheKey = generateCacheKey(decisionType, context);
            CachedDecision cached = decisionCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) return cached.getDecision();  // 本地命中
            AIDecision tairDecision = loadDecisionFromTair(cacheKey);
            if (tairDecision != null) {
                decisionCache.put(cacheKey, new CachedDecision(tairDecision));  // 回填本地
                return tairDecision;
            }
        }
        // Step 2: 获取Prompt模板（不存在→AIDecision.fallback降级）
        String template = promptTemplates.get(decisionType);
        if (template == null) return AIDecision.fallback(decisionType, "default", "Prompt模板不存在");
        // Step 3: 构建Prompt（KV Cache优化 + 40K安全截断）
        String prompt = buildPrompt(template, context);
        // Step 4: 调用Qwen3-Max大模型
        String response = qwen3MaxTextService.generateText(prompt);
        if (response == null) return AIDecision.fallback(decisionType, "default", "大模型返回空响应");
        // Step 5: 解析响应（支持4种格式 + cleanJsonResponse清理markdown标记）
        AIDecision decision = parseDecisionResponse(decisionType, response, decisionId);
        // Step 6: 缓存双写（本地 + Tair，仅reliable决策缓存）
        if (useCache && decision.isReliable()) {
            decisionCache.put(cacheKey, new CachedDecision(decision));
            storeDecisionToTair(cacheKey, decision);
        }
        return decision;
    }
}
```

**五大核心机制**：

| 机制 | 实现细节 | 工程意义 |
|------|---------|---------|
| **两级缓存** | L1本地ConcurrentHashMap(5分钟TTL) + L2 Tair分布式(300秒TTL) + 回填策略 | 相同决策上下文重复调用零LLM消耗 |
| **SHA-256缓存键** | `generateCacheKeyDigest()`取SHA-256前128位(16字节hex)替代hashCode() | hashCode()仅32位碰撞概率高，SHA-256碰撞概率忽略不计 |
| **KV Cache前缀稳定化** | `buildPrompt()`先写模板主体→按字母序添加上下文→时间戳放末尾 | 相同DecisionType调用共享DashScope Prompt Cache前缀 |
| **40K安全截断** | Prompt超40K字符时保留前90%+后5%，中间截断+截断标记 | 防止上游遗漏导致Prompt超出模型处理能力（实测2.3M+字符时模型返回空响应） |
| **LLM JSON修复** | `repairMalformedJson()`调用JSON_STRUCTURE_REPAIR决策类型，修复前安全阈值检查(70%上限) | 首次解析失败时LLM语义修复，零侵入（正常流程零开销） |

**定时缓存清理**——`@Scheduled(fixedRate=300000)`每5分钟扫描`decisionCache`移除过期条目，防止内存泄漏。Tair缓存依赖TTL自然过期。

**`parseDecisionResponse()`支持4种AI响应格式**：标准格式(`selected_strategy`+`strategy_params`)、Prompt生成格式(`prompt`+`reasoning`)、URL提取格式(`extracted_urls`)、适配器选择格式(`selected_adapter`+`config`)。无strategy_params时将整个响应（排除元数据字段）作为params，确保业务数据不丢失。

> **架构要点**：`AIDecisionEngine`是"**43条铁轨的调度中心**"——所有决策调用走统一入口，享受统一的缓存、安全限制、降级保护。`AIDecision.fallback()`在模板不存在、AI返回空、解析异常三种场景统一降级，置信度设为0.5区分AI正常决策。`@Autowired(required=false) TairCacheManager`确保Tair不可用时仅使用本地缓存，不影响决策流程。

### 2.3 两层渐进式匹配与信息披露

**代码证据**：`TwoLayerMatcher.java`（745行）+ `ToolTierManager.java`（~276行）

```java
// L1层：12大功能域快速分类（215种 → ~15种候选）
L1MatchResult l1 = matchLayer1(userRequest, sessionId);
// L2层：精确匹配（~15种 → 1种）
L2MatchResult l2 = matchLayer2(userRequest, l1, sessionId);
```

**三层信息披露**控制Token消耗：Tier 1核心能力（~15个，~100 Token）→ Tier 2常用能力（~67个，~500 Token）→ Tier 3扩展能力（其余，~1000 Token）。搜索空间从O(215)降到O(15)，匹配准确率极高。

**核心代码逻辑**（`TwoLayerMatcher.java`）——两层匹配的核心入口与L1候选ID注册表校验：

```java
@Service
public class TwoLayerMatcher {
    @Value("${optimus.matcher.l1-confidence-threshold:0.7}")
    private double l1ConfidenceThreshold;
    @Value("${optimus.matcher.l2-confidence-threshold:0.85}")
    private double l2ConfidenceThreshold;

    // 两层匹配入口——L1缩小范围，L2精确匹配
    public TwoLayerMatchResult match(String userRequest, List<FileInfo> attachments) {
        long startTime = System.currentTimeMillis();
        // Step 1: L1 功能域识别（215种 → ~15种候选）
        L1MatchResult l1Result = matchLayer1(userRequest, attachments, sessionId);
        // Step 2: L2 能力匹配（~15种 → 1种），基于L1结果收窄范围
        L2MatchResult l2Result = matchLayer2(userRequest, l1Result, sessionId);
        return new TwoLayerMatchResult(l1Result, l2Result);
    }

    // L1层核心：AI返回候选ID必须通过注册表校验
    private L1MatchResult matchLayer1(String userRequest, ...) {
        AIDecision decision = aiDecisionEngine.makeDecision(
                DecisionType.L1_CATEGORY_MATCH, context, true);
        // 校验AI返回的候选ID是否在能力注册表中存在
        List<String> validCandidates = new ArrayList<>();
        for (String cid : rawCandidates) {
            if (capabilityRegistryService.get(cid) != null) {
                validCandidates.add(cid);
            }
        }
        if (validCandidates.isEmpty()) {
            // AI返回的候选ID全部无效，回退到TaskCategory默认能力列表
            result.setCandidateCapabilities(
                new ArrayList<>(result.getCategory().getCapabilities()));
        }
        // ...
    }
}
```

> **架构要点**：`matchLayer1()`中对AI返回的候选ID逐一校验`capabilityRegistryService.get(cid)`，全部无效时回退到`TaskCategory`默认候选集。这是"**配置驱动是底线，AI驱动是增强**"原则的代码级体现——AI可以增强匹配精度，但注册表始终是最终仲裁者。

**v3.28三层工具分级重构**（`ToolTierManager`）：

| 工具类型 | Tier | 分级策略 | Harness工程意义 |
|---------|------|---------|----------------|
| MCP_TOOL | Tier 2 | `getTier()`硬编码`return 2` | 结构化API精确性优于通用浏览器，Prompt中以"名称+一句话描述"呈现 |
| BROWSER_TOOL | Tier 2 | 本地浏览器低延迟高可控，固定Tier 2 | 与MCP共享Tier 2但通过`briefDescription`语义隔离业务优先级（MCP优先） |
| agentbay_browser_* | Tier 3 | AgentBay云端浏览器固定Tier 3 | 云端沙箱定位为最终降级方案，Prompt中仅以"ID+名称"列表呈现 |

**L1候选ID注册表校验**——AI返回的候选ID必须通过`capabilityRegistry.contains(id)`验证，全部无效时回退到`TaskCategory`默认候选集。体现"**配置驱动是底线，AI驱动是增强**"的原则。

#### 2.3.5 能力匹配精准度保障——三层后置校验

**代码证据**：`AICapabilityMatcherService.java`（589行）

L1+L2两层匹配完成后，系统并非直接信任AI匹配结果。`AICapabilityMatcherService.validateMatchCompatibility()`对每个EXACT/SEMANTIC匹配结果执行**三层后置校验**，这是"**永远不信任AI输出**"原则在能力匹配维度的完整落地：

```java
private CapabilityMatch validateMatchCompatibility(SubTask subTask, CapabilityMatch matchResult) {
    // 只对 EXACT/SEMANTIC 匹配进行后置校验
    if (matchResult.getMatchType() != MatchType.EXACT && matchResult.getMatchType() != MatchType.SEMANTIC) {
        return matchResult;
    }

    // 校验1：检查能力的 inputConstraints.rejectPatterns（数据驱动）
    String rejectReason = checkInputConstraintRejectPatterns(capability, combinedTaskContext);
    if (rejectReason != null) {
        return downgradeToMissing(subTask, matchResult, rejectReason);
    }

    // 校验2：检查能力的 incompatibleInputSources（数据驱动）
    String incompatibleReason = checkIncompatibleInputSources(capability, combinedTaskContext);
    if (incompatibleReason != null) {
        return downgradeToMissing(subTask, matchResult, incompatibleReason);
    }

    // 校验3：LLM驱动的格式兼容性校验（FORMAT_COMPATIBILITY_CHECK决策类型）
    String formatMismatchReason = checkFormatCompatibilityWithAI(subTask, capability);
    if (formatMismatchReason != null) {
        return downgradeToMissing(subTask, matchResult, formatMismatchReason);
    }

    return matchResult;
}
```

**三层后置校验矩阵**：

| 校验层 | 驱动方式 | 数据来源 | 失败策略 | 零LLM调用 |
|--------|---------|---------|---------|----------|
| **rejectPatterns** | 数据驱动 | 能力注册JSON的`inputConstraints.rejectPatterns` | 降级为MISSING | 是 |
| **incompatibleInputSources** | 数据驱动 | 能力描述中的"不接受"声明（URL/文件路径/二进制等6类） | 降级为MISSING | 是 |
| **FORMAT_COMPATIBILITY_CHECK** | LLM驱动 | AI语义理解任务输入与能力输入Schema的兼容性 | 降级为MISSING | 否 |

**关键设计决策**：校验3（LLM驱动）失败或异常时**默认放行**——"宁可放行让执行层校验，不误拦截"。这与确定性优先原则一致：数据驱动的前两层负责高确定性拦截，LLM层负责长尾场景，但绝不因LLM不稳定而阻断正常流程。

**create_new_spec能力纠偏**——当AI判定匹配为MISSING（CREATE_NEW）时，`extractSuggestedCapability()`从AI返回的`create_new_spec.name`中提取正确的能力名称（如`pptx_to_pdf`替代`html_to_pdf`），确保后续工具工厂创建/复用正确的工具。

**ORCH-DIAG诊断日志**——匹配冗余分析：每个任务的前置分配（decompose阶段）与AI匹配结果对比，输出`WASTED_SAME_RESULT`（AI匹配与预分配相同，匹配可能冗余）或`USEFUL_DIFFERENT_RESULT`（AI修正了预分配，匹配有价值），为后续优化提供数据依据。

#### 2.3.7 CapabilityRegistryService——215种能力的统一注册表引擎

**代码证据**：`CapabilityRegistryService.java`（~800行）

`TwoLayerMatcher`和`AICapabilityMatcherService`的所有匹配结果最终都需要通过`capabilityRegistryService.get(id)`校验——这个注册表是215种能力的**唯一真相来源（Single Source of Truth）**。它管理能力的加载、索引、查找、动态注册和Schema质量校验的完整生命周期。

**核心代码逻辑**（`CapabilityRegistryService.java`）——三索引加载+四级查找降级+动态注册+Schema校验：

```java
@Service
public class CapabilityRegistryService {
    private static final String CAPABILITY_REGISTRY_PATH = 
            "classpath:orchestrator_knowledge/unified_capability_registry.json";
    @Autowired(required = false) private AIDecisionEngine aiDecisionEngine;
    @Value("${optimus.capability.ai-semantic-enabled:true}") private boolean aiSemanticEnabled;
    @Value("${optimus.browser-tool.enabled:true}") private boolean browserToolEnabled;

    // ========== 三维索引 ==========
    private final ConcurrentHashMap<String, Capability> capabilitiesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> capabilitiesByType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> capabilitiesByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> aliasMap = new ConcurrentHashMap<>();  // 双向别名
    private final Set<String> dynamicCapabilityIds = ConcurrentHashMap.newKeySet();  // 动态注册ID追踪

    // ========== 启动加载流水线 ==========
    @PostConstruct
    public void init() {
        Resource resource = resourceLoader.getResource(CAPABILITY_REGISTRY_PATH);
        JSONArray capabilitiesArray = registry.getJSONArray("capabilities");
        for (int i = 0; i < capabilitiesArray.size(); i++) {
            try {
                JSONObject capJson = capabilitiesArray.getJSONObject(i);
                Capability capability = parseCapability(capJson);  // 20+字段解析
                indexCapability(capability);    // 三维索引构建（byId+byType+byName）
                buildAliasIndex(capability, capJson);  // 双向别名映射
            } catch (Exception e) {
                // 单条解析失败跳过，不影响其他能力
            }
        }
        validateSchemaCompleteness();  // R4: Schema完整性校验
    }

    // 三维索引构建——byId + byType + byName
    private void indexCapability(Capability capability) {
        capabilitiesById.put(capability.getId(), capability);
        if (capability.getType() != null)
            capabilitiesByType.computeIfAbsent(capability.getType(), k -> new ArrayList<>()).add(capability.getId());
        if (capability.getName() != null)
            capabilitiesByName.put(capability.getName(), capability.getId());
    }

    // ========== 四级查找降级（核心查找方法）==========
    public Capability findExact(String capabilityType) {
        // 第1级：按ID精确查找
        Capability capability = capabilitiesById.get(capabilityType);
        if (capability != null) {
            if ("BROWSER_TOOL".equals(capability.getType()) && !browserToolEnabled)
                return null;  // BROWSER_TOOL域级开关过滤
            return capability;
        }
        // 第2级：按类型查找第一个匹配
        List<String> ids = capabilitiesByType.get(capabilityType);
        if (ids != null && !ids.isEmpty()) return capabilitiesById.get(ids.get(0));
        // 第3级：按名称查找
        String id = capabilitiesByName.get(capabilityType);
        if (id != null) return capabilitiesById.get(id);
        // 第4级：别名降级查找
        String alternateId = aliasMap.get(capabilityType);
        if (alternateId != null) return capabilitiesById.get(alternateId);
        return null;
    }

    // get()——附带别名降级的简洁查找入口（TwoLayerMatcher/AICapabilityMatcherService的校验入口）
    public Capability get(String id) {
        Capability capability = capabilitiesById.get(id);
        if (capability != null) {
            if ("BROWSER_TOOL".equals(capability.getType()) && !browserToolEnabled) return null;
            return capability;
        }
        // 别名降级
        String alternateId = aliasMap.get(id);
        return (alternateId != null) ? capabilitiesById.get(alternateId) : null;
    }

    // ========== AI+Rules双模语义查找 ==========
    public Capability findSemantic(String name, String type) {
        // AI优先：SEMANTIC_MATCH决策类型
        if (aiSemanticEnabled && aiDecisionEngine != null) {
            try {
                Capability aiMatched = findSemanticWithAI(name, type);
                if (aiMatched != null) return aiMatched;
            } catch (Exception e) {
                // AI失败，降级到规则匹配
            }
        }
        // 规则降级：名称/ID contains模糊匹配
        return findSemanticByRules(name, type);
    }

    // ========== 动态注册（AIToolFactoryService调用）==========
    public Capability registerDynamic(String toolId, String name, String description, 
                                       String type, String endpoint, int port, ...) {
        Capability capability = Capability.builder()
                .id(toolId).name(name).description(description)
                .type(type).source("dynamic").endpoint(endpoint).port(port)
                .enabled(true).build();
        indexCapability(capability);           // 加入三维索引
        dynamicCapabilityIds.add(toolId);      // 追踪动态注册ID
        return capability;
    }

    // MCP工具批量注册
    public int registerMCPTools(List<Capability> mcpCapabilities) {
        for (Capability cap : mcpCapabilities) {
            cap.setType("MCP_TOOL"); cap.setSource("mcp");
            indexCapability(cap);
            dynamicCapabilityIds.add(cap.getId());
        }
        return registeredCount;
    }

    // ========== Schema完整性校验（R4质量保障）==========
    private void validateSchemaCompleteness() {
        // 遍历所有enabled能力（排除MCP_TOOL动态注册），检查input/output Schema是否为空
        // 缺失时输出[R4]警告日志，全部通过时输出确认日志
    }

    // 可创建能力类型集合（S3动态工具创建的输入）
    public Set<String> getCreatableCapabilityTypes() {
        return capabilitiesById.values().stream()
                .filter(cap -> Boolean.TRUE.equals(cap.getCreatable()))
                .flatMap(cap -> {
                    Set<String> ids = new HashSet<>();
                    ids.add(cap.getId());
                    if (cap.getAliases() != null) ids.addAll(cap.getAliases());
                    return ids.stream();
                }).collect(Collectors.toSet());
    }

    // 热重载：reload() → init()，运行时更新注册表
    public void reload() { init(); }
}
```

**四级查找降级与BROWSER_TOOL域级开关**：

| 查找级别 | 查找方式 | 确定性 | 降级触发条件 |
|---------|---------|--------|------------|
| 第1级 | `capabilitiesById.get(id)` 精确ID匹配 | 最高 | 未命中 |
| 第2级 | `capabilitiesByType.get(type)` 类型索引 | 高 | ID索引未命中 |
| 第3级 | `capabilitiesByName.get(name)` 名称索引 | 中 | 类型索引未命中 |
| 第4级 | `aliasMap.get(id)` 别名双向映射 | 中 | 名称索引未命中 |

**BROWSER_TOOL域级开关**（`browserToolEnabled`）贯穿`get()`/`findExact()`/`findSemantic()`/`findByDomain()`/`getAllCapabilities()`五个查找入口——一处配置`optimus.browser-tool.enabled=false`即可全局屏蔽所有BROWSER_TOOL类型能力，上层匹配器和执行引擎无需修改任何代码。

> **架构要点**：`CapabilityRegistryService`是编排引擎的"**能力真相仲裁者**"——TwoLayerMatcher的L1候选ID必须通过`get(id)`校验（§2.3），AIToolFactoryService创建的动态工具通过`registerDynamic()`纳入注册表（§9.2.5），MCP工具通过`registerMCPTools()`批量注册。`aliasMap`的**双向映射**（`put(primaryId, aliasId)` + `put(aliasId, primaryId)`）支持Strangler Pattern渐进重构（§9.4）——旧ID和新ID互为别名，调用方使用任一ID都能找到同一能力。`validateSchemaCompleteness()`在启动时主动检测Schema缺失，将质量问题从运行时错误前移到启动日志警告。`findSemantic()`的AI+Rules双模查找是"确定性优先，AI增强"原则在注册表查找维度的实践——AI语义匹配增强准确率（SEMANTIC_MATCH DecisionType），规则匹配保证可用性。

### 2.4 10协议统一适配与配置驱动

**代码证据**：`AdapterRegistry.java`（288行）+ `CapabilityAdapter.java`（226行）

**统一契约接口**——10种适配器全部实现`CapabilityAdapter`接口：

```java
public interface CapabilityAdapter {
    ExecutionProtocol getProtocol();           // 声明协议类型
    boolean supports(Capability capability);    // 判断是否支持
    AdapterResult invoke(Capability capability, // 统一调用接口
                         Map<String, Object> input, 
                         InvokeConfig config) throws AdapterException;
    boolean healthCheck(Capability capability); // 健康检查
}
```

**10种适配器实现**：

| 协议 | 适配器类 | 职责 |
|------|---------|------|
| HTTP | `HttpAdapter` | REST API调用 |
| ATOMIC | `AtomicToolAdapter` | 内置Utility工具，ServiceBridgeService路由 |
| SCRIPT | `ScriptAdapter` | Python脚本，CompletableFuture超时 |
| MCP | `MCPCapabilityAdapter` | MCP协议（SSE + Streamable HTTP） |
| AGENTBAY | `AgentBayAdapter` | 阿里云无影沙箱37种能力 |
| BUILTIN | `BuiltinToolAdapter` | 系统内置工具 |
| IMM | `IMMAdapter` | 阿里云智能媒体管理10种能力 |
| LOCAL | `LocalAdapter` | 本地Bean反射调用 |
| AGENT | `AgentAdapter` | 业务Agent路由，agent_knowledge.json |
| LLM_MODEL | `LlmModelAdapter` | 基础大模型直接调用 |

AI只需输出协议名称，`adapter.supports(capability)`验证确保无法"幻想"不存在的执行路径。如果AI输出了无效协议，规则降级自动接管。

**核心代码逻辑**（`AdapterRegistry.java`）——AI/Rule双模式适配器选择：

```java
@Component
public class AdapterRegistry {
    @Autowired(required = false)
    private AIDecisionEngine aiDecisionEngine;  // AI引擎可选注入
    
    private final Map<ExecutionProtocol, CapabilityAdapter> adapterMap = new HashMap<>();

    // 根据能力自动选择适配器——AI优先，规则降级
    public CapabilityAdapter getAdapterForCapability(Capability capability, 
                                                      Map<String, Object> invokeContext) {
        // 尝试使用AI智能选择适配器
        if (aiSelectionEnabled && aiDecisionEngine != null) {
            try {
                CapabilityAdapter aiSelectedAdapter = selectAdapterWithAI(capability, invokeContext);
                if (aiSelectedAdapter != null) return aiSelectedAdapter;
            } catch (Exception e) {
                // AI选择失败，降级到规则
                logger.warn("[AdapterRegistry] AI选择失败, fallbackToRules=true");
            }
        }
        // 降级到规则选择——遍历所有适配器，找到supports(capability)为true的
        return selectAdapterByRules(capability);
    }

    private CapabilityAdapter selectAdapterWithAI(Capability capability, ...) {
        AIDecision decision = aiDecisionEngine.makeDecision(
                DecisionType.ADAPTER_SELECT, context, true);
        // AI推荐协议 → 验证adapter.supports(capability) → 验证通过才返回
        ExecutionProtocol protocol = ExecutionProtocol.valueOf(protocolStr);
        CapabilityAdapter adapter = adapterMap.get(protocol);
        if (adapter != null && adapter.supports(capability)) return adapter;
        return null;  // 验证不通过，返回null触发规则降级
    }
}
```

**`CapabilityAdapter`统一契约接口**——10种适配器的公共抽象：

```java
public interface CapabilityAdapter {
    ExecutionProtocol getProtocol();                         // 声明协议类型
    boolean supports(Capability capability);                  // 判断是否支持
    AdapterResult invoke(Capability capability,               // 统一调用接口
                         Map<String, Object> input, 
                         InvokeConfig config) throws AdapterException;
    boolean healthCheck(Capability capability);               // 健康检查

    // 调用配置——超时/重试/延迟全部可配
    class InvokeConfig {
        private int timeoutMs = 30000;
        private int maxRetries = 3;
        private int retryDelayMs = 1000;
    }
    // 调用结果——success/data/errorMessage/statusCode/durationMs 五元组
    class AdapterResult { ... }
    // 适配器异常——statusCode + retryable 两维分类
    class AdapterException extends Exception {
        private final int statusCode;
        private final boolean retryable;  // 是否可重试
    }
}
```

> **架构要点**：`AdapterException.retryable`字段让错误恢复系统（§8.5）能精确判断"该不该重试"——HTTP 4xx客户端错误`retryable=false`直接跳过，网络超时`retryable=true`才触发指数退避重试。

**配置驱动零硬编码**：`ai_config.json` + `ai_config_defaults.json`两级配置链 + 热重载：

| 配置段 | 关键参数 | 工程意义 |
|--------|---------|---------|
| `execution` | `orchestration_timeout_ms`  | 全局超时预算 |
| `decision_engine` | 缓存TTL/最大条目/降级策略 | AI决策控制 |
| `matching` | L1/L2置信度阈值/候选数量 | 匹配精度调节 |
| `replan` | `base_max: 3, absolute_limit: 7` | 重规划次数约束 |
| `input_resolve` | 五层防御正则/AI兜底开关 | 参数安全配置 |
| `param_transform` | URL模式/separator/prefix清理 | 参数转换规则 |
| `example_deposit` | 沉淀条件/质量阈值 | 经验积累策略 |
| `pattern_memory` | 读写开关/缓存TTL/最大条目 | 模式记忆控制 |
| `monitoring` | 性能指标/告警阈值 | 可观测性配置 |
| `tool_creation` | 预热类型/端口范围/FC开关 | 工具管理策略 |

**工具链兼容性配置驱动**——`ToolChainCompatibilityService.java`（217行）

"配置驱动零硬编码"的深度实践：任务拆解Prompt中的工具链约束不再硬编码在`task_decompose.txt`模板中，而是从`tool_chain_compatibility.json`结构化配置**运行时动态生成**。配置包含5类约束规则：

```java
@Service
public class ToolChainCompatibilityService {
    private static final String CONFIG_PATH = 
            "classpath:orchestrator_knowledge/tool_chain_compatibility.json";
    private volatile JSONObject fullConfig;

    // 动态生成工具链约束Prompt → 注入${tool_chain_constraints}变量
    public String generateToolChainConstraintsPrompt() {
        // === 不适合DAG编排的工具约束 [KEY] ===
        // === 全流程工具不可拆分约束 [KEY] ===（含internal_stages+forbidden_splits）
        // === 工具链兼容性约束 [KEY] [MUST-FOLLOW] ===（forbidden_chains+allowed_chains）
    }
    // 动态生成强制规则 → 注入${forced_chain_rules}变量
    public String generateForcedChainRulesPrompt() { ... }
    // 热重载配置
    public void reload() { loadConfig(); }
}
```

| 配置类别 | 约束内容 | Prompt注入变量 |
|---------|---------|---------------|
| `forbidden_chains` | 禁止的工具链组合（输入输出格式不兼容） | `${tool_chain_constraints}` |
| `allowed_chains` | 允许的标准工具链路径（含唯一正确路径标记） | `${tool_chain_constraints}` |
| `forced_rules` | 强制工具链规则（场景→必须使用的链路+禁止替代方案） | `${forced_chain_rules}` |
| `non_dag_tools` | 不适合DAG编排的工具（附规则说明） | `${tool_chain_constraints}` |
| `non_splittable_tools` | 不可拆分的全流程工具（含internal_stages声明） | `${tool_chain_constraints}` |

> **Harness工程意义**：这是"**配置驱动约束**"的教科书级实践——新增工具链约束时，只需修改JSON配置文件，无需修改任何Prompt模板或Java代码。`volatile`修饰的`fullConfig`支持运行时热重载，配置变更无需重启服务。

> 编排基座定义了"AI在哪些轨道上做决策"。下一章深入每条轨道的核心：**如何通过Prompt结构控制AI的注意力分布**。

---

## 三、注意力编程——Prompt是对智能的编程

> **核心原理**：结构化Prompt之所以有效，根源在于Transformer的Self-Attention机制对输入位置的非均匀注意力分配。通过语言结构控制注意力分布，是Harness Engineering"让普通模型做复杂事"的核心武器。

### 3.1 Lost in the Middle——注意力分布的位置偏差

Transformer的 `Attention(Q,K,V) = softmax(QK^T/√d_k)V` 机制在实际推理中表现出显著的位置偏差。**Liu et al. (2023)** 通过大规模实验证实了 **"Lost in the Middle"** 现象：模型对序列**首部**和**末尾**信息的利用率显著高于**中段**——这一结论在GPT-4、Claude、Llama等多种架构上均得到验证，是Transformer家族的共性特征。

三个工程可利用的关键规律：

| 效应 | 位置 | 注意力权重 | 工程对策 |
|------|------|-----------|---------|
| **首部偏高（Primacy Effect）** | 开头 | 高 | 角色设定、任务定义放最前面 |
| **末尾强化（Recency Effect）** | 结尾 | 高 | 输出格式、自检清单放最后面 |
| **中段稀释（Lost in the Middle）** | 中间 | 中-低 | 上下文数据、示例引用放中间 |

好的Prompt本质上是在**"编程注意力分布"——通过语言结构把模型的思考能量分配到正确的位置**。从信息论角度，结构化设计减少了无关语义干扰，降低上下文熵，让模型在更集中的语义子空间中高效推理。

**弓形注意力分布**可视化（工程简化模型，实际分布受Attention Sink、RoPE、上下文长度等因素影响，此处取Liu et al.论文的工程可利用形态）：

```
注意力权重
  ▲
  │ ██                                          ████
  │ ████                                      ██████
  │ ██████                                  ████████
  │ ████████████████████████████████████████████████
  └──────────────────────────────────────────────────→ Prompt位置
    Role/Context                Task细节               Format/Check
    [高权重区]                  [中权重区]               [高权重区]
```

这就是"Lost in the Middle"效应的工程可利用形态——**首尾高权重，中段低权重**。所有Prompt设计策略都是在利用这一弓形分布。

补充说明：Xiao et al. (2023) 进一步发现了 **Attention Sink** 现象——序列首个token获得异常高的注意力权重，这是注意力机制的数学特性而非语义效应。工程对策：System Message的首段应包含角色定义等**高信息密度内容**（而非空泛的问候语），以充分利用这一位置的高注意力权重。

### 3.2 双构建器Prompt架构——43模板11类

**代码证据**：`OrchestratorPromptTemplate.java` + `PromptStructureBuilder.java`（415行）

系统的43个Prompt模板基于**两套互补构建器**：

**四段式（OrchestratorPromptTemplate）**——编排核心决策，结构紧凑：
```
System(角色) → Context(上下文注入) → Instruction(决策指令) → Output Format(JSON Schema)
```

**六段式（PromptStructureBuilder）**——复杂决策，边缘效应工程化：
```java
public enum PromptSection {
    ROLE_DEFINITION(1, "角色定义与核心目标", true),   // [高权重] Primacy
    INPUT_INFO(2, "输入信息描述", false),
    CONSTRAINTS(3, "约束条件", false),
    EXAMPLES(4, "示例参考", false),
    OUTPUT_FORMAT(5, "输出格式要求", false),
    KEY_REMINDER(6, "关键提醒与强调", true);           // [高权重] Recency
    private final boolean highWeight;  // 标记高注意力权重段
}
// 构建时高权重段落加强标记：=== [重要] ===
```

**3个预定义模板方法**：`buildTaskDecomposePrompt()`（角色="资深任务编排专家"+8条硬约束+L2推荐注入）、`buildL1MatchPrompt()`（0.9置信度阈值）、`buildL2MatchPrompt()`（0.85置信度阈值）。

**核心代码逻辑**（`PromptStructureBuilder.java`）——六段式Prompt的完整构建流程：

```java
@Component
public class PromptStructureBuilder {
    // 六段枚举——highWeight标记利用边缘效应的高权重段
    public enum PromptSection {
        ROLE_DEFINITION(1, "角色定义与核心目标", true),   // [高权重] Primacy
        INPUT_INFO(2, "输入信息描述", false),
        CONSTRAINTS(3, "约束条件", false),
        EXAMPLES(4, "示例参考", false),
        OUTPUT_FORMAT(5, "输出格式要求", false),
        KEY_REMINDER(6, "关键提醒与强调", true);          // [高权重] Recency
        private final boolean highWeight;
    }

    // 按顺序组装，高权重段落加强标记 "=== [重要] ==="
    public String build(Map<PromptSection, String> sections) {
        StringBuilder sb = new StringBuilder();
        for (PromptSection section : PromptSection.values()) {
            String content = sections.get(section);
            if (content != null && !content.isEmpty()) {
                if (section.isHighWeight()) {
                    sb.append("=== ").append(section.getName()).append(" [重要] ===\n");
                } else {
                    sb.append("=== ").append(section.getName()).append(" ===\n");
                }
                sb.append(content.trim()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    // 预定义模板：任务拆解Prompt——8条硬约束+L2推荐注入
    public String buildTaskDecomposePrompt(String userPrompt, ..., String l2Recommendation) {
        return builder()
            .roleDefinition(
                "你是资深任务编排专家，专注于将复杂用户需求拆解为可执行的原子任务序列。\n" +
                "核心目标：准确识别任务间依赖关系，为每个子任务匹配最合适的能力类型。")
            .constraints(
                "硬性约束：\n" +
                "1. 每个子任务必须对应一个明确的能力类型\n" +
                "2. 任务ID必须唯一且有序（task_1, task_2, ...）\n" +
                "3. 依赖关系必须形成DAG，禁止循环依赖\n" +
                "4. 输入参数必须明确来源：user_input 或 task_x.output.field\n" +
                "5. URL保护：用户输入中的URL必须原样保留，禁止修改任何字符\n" +
                "6. 工具IO独立性：可直接接受URL输入的能力无需拆分web_fetch前置任务\n" +
                "7. 【强制】参数完整性：每个能力的[必填]参数必须全部出现\n" +
                "8. 【浏览器搜索最佳实践】优先URL直搜而非fill+click多步操作。常见URL模板：" +
                "百度=https://www.baidu.com/s?wd=关键词, " +
                "小红书=https://www.xiaohongshu.com/search_result?keyword=关键词, ...")
            .keyReminder(
                "7. 【强制】capability_type必须严格使用注册能力ID，禁止使用示例中的非注册ID\n" +
                "8. 【强制】如果存在L2推荐，必须优先使用推荐的能力\n" +
                "9. 示例仅供参考。agentbay_browser_*前缀能力已被browser_*替代，不应直接采用")
            .build();
    }
}
```

> **架构要点**：`buildTaskDecomposePrompt()`的第8条硬约束注入了8大站点URL模板（百度/淘宝/京东/知乎/B站/小红书/抖音/搜狗微信），与§8.7 ELEMENT_NOT_FOUND的`search_url_template`形成**跨阶段联动**——Prompt层种下"种子"（URL模板），执行层在错误恢复时"收获"（零LLM调用的确定性URL转换）。

**43个模板分11大类**：任务拆解5/能力匹配4/执行策略3/错误恢复2/质量保证4/任务优化2/参数处理2/意图理解2/系统运维2/能力选择2/领域专用14+。

**降级策略**（`TaskDecomposerService.java`）：优先六段式PromptStructureBuilder，降级时四段式+手动注入示例。

**KV Cache优化**："前缀稳定化+动态内容后置"原则，相同DecisionType的调用共享KV Cache前缀。设计目标是利用DashScope API的Prompt Cache机制，理论上可使相同DecisionType的重复调用命中缓存前缀，减少约60%的重复Token处理。

### 3.3 跨领域一致性——编排/音乐/游戏的统一范式

结构化Prompt不是编排引擎的孤立实践，各业务领域同样严格遵循，且代码注释中明确引用了"Prompt咒语"理论：

```java
// MusicStyleAnalyzer.java
/** 遵循 Prompt 咒语四段式结构：Role → Context → Task → Format */
private String buildStyleAnalysisPrompt(String userQuery) {
    return String.format("""
        [Role] 资深音乐制作人，20年经验
        [Context] 用户需求：%s
        [Task] 判断最适合的音乐风格、调式、速度、乐器配置
        [Hard Constraints] 调式选择黄金法则（基于1348首统计）...
        [Output Spec] { "primaryStyle": "...", "mood": "...", ... }
        """, userQuery);
}
```

**6个音乐Service统一结构**（Prompt结构一致性矩阵）：

| Service类 | [Role] | [Context] | [Task] | [Hard Constraints] | [Output Spec] |
|-----------|--------|-----------|--------|-------------------|---------------|
| `MusicStyleAnalyzer` | 资深音乐制作人20年 | 用户需求 | 风格/调式/速度/乐器判断 | 调式黄金法则+15种情绪映射 | JSON |
| `LyricsGenerationService` | 资深词作家20年 | 需求+风格+情绪+BPM | Verse→Chorus结构歌词 | 每行8-15字+押韵+诗意画面感 | 歌词文本 |
| `MusicGenerationService`(基础) | 资深音乐理论专家20年 | 歌词+风格分析结果 | 5大维度音乐创作方案 | 11条硬约束 | JSON |
| `MusicGenerationService`(增强) | 同上+L1-L4匹配注入 | 四层匹配+质量反馈 | 增强版音乐方案 | 段落配器渐变+和弦差异化 | JSON |
| `MusicQualityEvaluator` | 资深质量评估专家20年 | 需求+音乐配置JSON | 5维度×20分评估 | 调式/乐器/节奏/旋律/整体 | JSON评分 |
| `AIMusicKnowledgeService` | 音乐风格/元素匹配专家 | 知识库索引+用户描述 | 逐层匹配筛选 | 各层置信度阈值 | JSON |

全部遵循 [Role]→[Context]→[Task]→[Hard Constraints]→[Output Spec]。

### 3.4 L6旋律设计八段式——Prompt工程的极致

**代码证据**：`AIMusicKnowledgeService.buildL6MelodyDesignPrompt()`（~500行Prompt）

```
[Role]            → 顶级作曲家，多部电影和流行专辑创作经验
[Context]         → L5 MIDI分析数据 + 曲式结构 + 调式/速度
[Task]            → 为每个段落设计具体旋律音符（MIDI pitch/duration/velocity）
[Hard Constraints]→ 音高60-84 + 音程≤7半音 + 五声音阶约束 + 密度要求
[Bad Examples]    → ❌ 连续8音无休止 / ❌ 音程>7半音（附"为什么"解释）
[Output Spec]     → 完整JSON Schema（melodyDesign + globalSettings）
[Self Check]      → 9项合规检查清单（音域/音程/时值/密度/动机统一）
[Final Output]    → "仅输出JSON，禁止输出任何解释性文字"
```

注意力编程原理的精确体现：[Role]激活专业知识子空间（Primacy），[Bad Examples]通过解释"为什么"激活Chain of Thought，[Self Check]+[Final Output]形成末尾双锚点（Recency）。

### 3.5 领域黄金法则注入

[Hard Constraints]段注入**经知识库统计验证的领域专家规则**，使普通LLM获得专业级判断能力：

- **调式选择黄金法则**（1348首统计）：温柔/浪漫→大调 / 忧伤/悲伤→小调 / "思念"→大调（反直觉）/ 避免过度C major（45%占比）
- **音乐理论11条硬约束**：乐器2-3种 / MIDI Program 0-79（禁合成器80-127）/ 音程≤纯五度 / 中国民族乐器MIDI映射（古筝→46/二胡→40/琵琶→24/笛子→73）
- **5维度×20分评分标准**：调式匹配 + 乐器选择 + 节奏结构 + 旋律质量 + 整体专业 = 100分

本质：**将领域专家经验编码为Prompt约束**——与模型无关的知识工程。

### 3.6 模板文件化+降级兜底

**代码证据**：`AIMusicKnowledgeService.loadPromptTemplates()`

音乐系统7个`.md`外部模板 + 内联备用模板双模式：

```java
// 启动时加载7个.md格式Prompt模板
private void loadPromptTemplates() {
    matchingPromptL1 = loadPromptTemplate("l1-matching-prompt.md");
    matchingPromptL2 = loadPromptTemplate("l2-matching-prompt.md");
    // ... 共7个模板
    qualityEvaluationPromptTemplate = loadPromptTemplate("quality-evaluation-prompt.md");
}

// 使用时：模板文件优先 → 内联降级
if (!isBlank(matchingPromptL1)) {
    prompt = matchingPromptL1.replace("{{USER_DESCRIPTION}}", userDescription);
} else {
    logger.warn("[L1匹配] Prompt模板未加载，使用内联备用模板");
    prompt = String.format("[Role] 音乐风格分析专家\n...", ...);
}
```

| 维度 | 模板文件模式 | 内联降级模式 |
|------|------------|------------|
| 可维护性 | 产品/运营可直接编辑 | 需要开发者修改代码 |
| 部署灵活性 | 不需要重新编译 | 编译时固化 |
| 可靠性 | 文件加载可能失败 | 100%可用 |

编排引擎43个`.txt` + 音乐7个`.md` + 游戏3个`.md` = **53个外部Prompt模板文件**，形成"Prompt即文档"工程范式。

**Prompt模板实物证据——§3理论的两个标杆实现**

§3.2展示了Java构建器代码（工厂），此处展示构建器产出的成品（产品）——两个跨域Prompt模板文件完整体现注意力编程原理。

**标杆1：`generation-prompt-template.md`（222行，游戏域）——六段式的规范实现**

**代码证据**：`game_knowledge/prompts/generation-prompt-template.md`（222行）

唯一自我标注注意力权重的Prompt模板，文件首行声明：_"严格遵循 prompt咒语.md 的六段式结构化设计，应用注意力边缘效应原理"_。六段结构精确对应§3.1弓形注意力分布：

```markdown
## [Role] 系统角色定义（开头高权重区）                    ← Primacy Effect
你是一位专业的 HTML5 游戏开发专家...
**核心约束预告**：你必须严格遵守以下硬性约束...

## [Context] 上下文信息（中段区）                        ← Lost in the Middle
### 用户需求: {{userDescription}}
### 匹配的游戏类型: {{matchedType}}
### 需要实现的核心机制: {{requiredMechanics}}
### 参考示例代码片段: {{referenceCodeSnippet}}

## [Task] 具体任务指令（中段区）
请生成一个完整的、可直接运行的 HTML5 游戏...

## [Hard Constraints] 硬性约束（中段详细列出）
### 代码结构约束
1. 必须是单个完整的 HTML 文件
2. 必须包含 GAME_CONFIG 对象                            ← 确定性契约
### 禁止的代码模式（❌/✅双向约束）
1. ❌ 不要使用 ES6 模块语法（import/export）
2. ❌ 不要使用外部 CDN 库
3. ❌ 不要硬编码素材路径，必须通过 GAME_CONFIG 配置
### 必须包含的功能
1. ✅ 开始/重新开始按钮
2. ✅ 游戏结束检测和提示
3. ✅ 触摸控制（移动端支持）

## [Output Spec] 输出格式规范（结尾高权重区）              ← Recency Effect
请直接输出完整的 HTML 代码，不要添加任何解释性文字。

## [Self Check] 自检清单（结尾锚点，最高权重）             ← 双锚点 Recency
### 语法正确性（5项）
- [ ] JavaScript 语法正确，无 SyntaxError
- [ ] HTML 标签正确闭合
### 功能完整性（5项）
- [ ] GAME_CONFIG 包含所有可配置元素
- [ ] 游戏主循环正常运行
### 素材集成（3项）
- [ ] 图片加载失败时有降级处理
### 移动端支持（3项）
- [ ] 包含触摸控制逻辑
### 硬性约束确认（5项）
- [ ] 没有使用禁止的代码模式
- [ ] GAME_CONFIG 格式符合标准
**请确保以上所有检查项都已满足后再输出代码。**
```

> **§3理论映射**：(1) `{{userDescription}}`等模板变量由§3.6的`replace("{{XX}}", value)`机制注入——Prompt模板是**静态骨架**，Java代码注入**动态上下文**，两者协同构成完整的注意力编程；(2) [Self Check]的21项检查清单（5+5+3+3+5分类）本质是**在Prompt末尾嵌入Generator-Evaluator闭环**——模型生成代码后先自检再输出，利用Recency高权重确保自检指令不被"遗忘"；(3) ❌禁止模式 + ✅必须模式的双向约束通过对比强化模型注意力，比单向约束效果更强。

**标杆2：`layer4_javascript_generation.txt`（170行，AI应用域）——边缘效应的跨域应用**

**代码证据**：`fullstack_knowledge/prompts/layer4_javascript_generation.txt`（170行）

来自AI应用五层可视化生成流水线的第四层（代码生成层），明确标注**"利用 Attention 边缘效应"**：

```
# 角色                                                   ← Primacy（简洁角色锚定）
你是 JavaScript 开发专家，擅长生成高质量、可维护的前端交互代码。

# 上下文                                                  ← 中段（变量注入三端契约）
## 用户需求: {requirement}
## HTML 元素 ID 列表: {element_ids}                       ← HTML→JS契约对齐
## 后端 API 契约: {api_contract}                          ← JS→API契约对齐

# 任务
生成完整的 app.js 文件...

## 代码结构要求
const ELEMENT_IDS = { ... };                              ← 常量化契约
const API_BASE = `http://${window.location.hostname}:{port}`;
document.addEventListener('DOMContentLoaded', function() { ... });

# ⚠️ 必须遵守的硬约束（放在结尾，利用 Attention 边缘效应） ← 显式标注Recency利用
## 1. ELEMENT_IDS 常量定义（必须包含）
    // 🚨 元素 ID 常量 - 必须与 HTML 中的 ID 完全一致
## 2. 元素获取方式（必须使用常量）
    // ✅ 正确：使用 ELEMENT_IDS 常量
    const loadingIndicator = document.getElementById(ELEMENT_IDS.loadingIndicator);
    // ❌ 错误：硬编码 ID 字符串
    // const loading = document.getElementById('loading-indicator');
## 6. API 响应处理（必须检查 success 字段）
    if (result.success) { return result.data; }
    else { throw new Error(result.error || '操作失败'); }
## 7. API 请求字段命名
    - 使用 snake_case：loan_amount、interest_rate
    - ❌ 禁止使用 camelCase：loanAmount

## [Self Check] 合规检查
### 1. 动态地址检查
- [ ] API_BASE 是否使用 window.location.hostname 动态获取？
- [ ] 是否存在硬编码的 localhost 地址？
### 2. 错误示例（必须避免）
❌ const API_BASE = 'http://localhost:18000'
❌ fetch('http://localhost:18000/api/...')
### 3. 正确示例（必须遵守）
✅ const API_BASE = `http://${window.location.hostname}:18000`

# 输出格式
直接输出 JavaScript 代码，不要添加 markdown 代码块标记
```

> **§3理论映射**：(1) 与标杆1的关键差异：标杆1六段全标注权重区（"开头高权重区"/"中段区"/"结尾高权重区"），标杆2**把全部7大硬约束集中到末尾**单点爆破Recency Effect——两种策略殊途同归；(2) `{element_ids}` + `{api_contract}`注入实现**HTML→JS→API三端契约对齐**，是§6"AI认知+确定性执行分离"在Prompt层的前置实践——AI只负责填充逻辑，元素ID和API契约由确定性系统注入；(3) 每个硬约束都附带**✅正确/❌错误的对比代码**，这不是装饰而是注意力工程——对比示例激活模型的discriminative attention，比纯文字描述约束效果更强。

**两个标杆的互补关系**：

| 对比维度 | 标杆1 generation-prompt-template | 标杆2 layer4_javascript_generation |
|---------|-------------------------------|----------------------------------|
| **所属域** | 游戏代码生成 | AI应用JS代码生成 |
| **段数** | 六段（完整六段式） | 五段（精简变体） |
| **注意力策略** | 六段全标注权重区 | 硬约束集中末尾单点爆破 |
| **Self Check** | 21项/5类（最完整） | 3项+❌/✅对比（最精准） |
| **契约机制** | `{{变量}}`单端注入 | `{element_ids}`+`{api_contract}`三端对齐 |
| **约束方式** | ❌禁止+✅必须（文字描述） | ❌错误代码+✅正确代码（代码对比） |
| **文件行数** | 222行 | 170行 |

### 3.7 注意力编程五大工程原则

**跨领域结构一致性对比**：

| 对比维度 | 编排引擎六段式 | 音乐系统五~八段式 | 游戏/体感系统 |
|---------|-------------|----------------|------------|
| 段数 | 固定6段 | 5段(基础)→8段(L6) | 5-6段 |
| 高权重标记 | `[重要]`文本标记 | Javadoc注释说明边缘效应 | 无显式标记 |
| 角色定义 | "资深XX专家" | "资深XX，20年经验" | "XX匹配专家" |
| 约束注入 | 通用约束规则 | **领域黄金法则**(1348首统计) | 知识库约束 |
| 自检段 | KEY_REMINDER(末尾强化) | [Self Check]9项清单 | 无 |
| Bad Examples | 无 | L6含反例+原因解释 | 无 |
| 模板管理 | 43个.txt文件 | 7个.md+内联降级 | 3个.md+内联降级 |
| KV Cache | 前缀稳定化+动态后置 | 注释说明注意力分散规避 | 无 |

五大原则：

1. **边缘效应工程化**：首部放角色定义（Primacy），末尾放格式和自检（Recency）。L6的双锚点是极致体现
2. **结构即约束**：段落标签显式划分降低上下文熵，使模型在更集中的语义子空间中高效推理
3. **领域知识注入[Hard Constraints]**：编码专家经验为硬约束，使普通LLM获专业级判断
4. **"为什么"激活CoT**：[Bad Examples]解释错误原因，激活Chain of Thought推理
5. **模板文件化+降级兜底**：53个外部模板文件，加载失败降级内联硬编码

**v3.28跨域验证**：PromptStructureBuilder约束规则新增URL直搜最佳实践——8大站点URL模板直接注入Prompt `[Hard Constraints]`段，AI在搜索场景中直接使用预设URL模板而非自由构造（与§8.7 ELEMENT_NOT_FOUND的`search_url_template`形成跨阶段联动）。

**Prompt = 对智能的编程**——结构控制注意力分布，Prompt决定输出质量。这是与模型无关的工程方法。

---

## 四、知识驱动的搜索空间收敛——从O(N)到O(1)

> **核心原理**：知识库不是简单的"RAG检索"，而是精心设计的多层渐进式匹配架构。每一层通过工程化的知识注入缩小搜索空间，使下一层的AI决策更精准。这一模式在编排引擎、AI应用、游戏、体感、音乐等每个垂直领域都有独立实现。

### 4.1 620KB结构化系统记忆

**代码证据**：`orchestrator_knowledge/` 目录 + `TairVectorService.java`（~725行）

```
orchestrator_knowledge/
├── unified_capability_registry.json    # 111条能力注册（98 enabled），~4083行
├── mcp_capability_registry.json        # 156条MCP工具（117 enabled），~6800行
├── param_mapping_rules.json            # 41条参数映射规则
├── keyword_mappings.json               # 37条关键词映射
├── prompts/                            # 43个Prompt模板文件
├── examples/                           # 26个领域，75手工+187自动=262示例
│   └── auto_generated/                 # 自动沉淀示例
├── templates/                          # 代码生成模板
└── dynamic_tools/                      # 动态工具定义
```

**TairVector三层记忆语义检索**：1024维text-embedding-v3模型 + HNSW算法 + 余弦相似度。三个自动创建索引：`optimus_knowledge`/`tvs_memories`/`tvs_examples`。

**统一能力注册表JSON结构**——215种能力的结构化元数据：
```json
{
  "id": "atomic_web_summary",
  "name": "网页摘要",
  "type": "ATOMIC_TOOL",
  "tier": 2,
  "domain": "lifestyle",
  "description": "网页内容摘要提取",
  "input": { "url": { "type": "string", "required": true } },
  "output": { "summary": { "type": "string" } },
  "targetService": "serviceBridgeService",
  "targetMethod": "webSummary"
}
```

### 4.2 示例自动沉淀——越用越聪明

**代码证据**：`ExampleKnowledgeBase`（893行）+ `ExampleAutoDepositService`（~354行）

每次编排成功后自动沉淀为示例，下次类似请求时L2匹配直接参考。`findSimilarExamples()`三层匹配渐进降级：

```
Layer 1: VECTOR路径（TairVector语义预筛，topK×3候选→AI精选）
  → 异常时完全无损回退到Layer 2
Layer 2: AI_LLM路径（EXAMPLE_MATCH决策类型+示例摘要上下文）
  → AI不可用时降级到Layer 3
Layer 3: RULES路径（标签匹配0.3/tag + 关键词匹配0.2/word × quality加权）
```

**异步向量化**：`@PostConstruct`启动时daemon线程异步向量化所有示例到`tvs_examples`索引，不阻塞启动。

**核心代码逻辑**（`ExampleAutoDepositService.java`）——三重验证+深度输出失败检测：

```java
@Service
public class ExampleAutoDepositService {
    private final Set<String> depositedSignatures = ConcurrentHashMap.newKeySet();  // 去重缓存

    public void tryDeposit(ExecutionPlan plan, TaskResult result) {
        if (!isEnabled() || !Boolean.TRUE.equals(result.getSuccess())) return;

        for (SubTask task : plan.getSubtasks()) {
            if (task.getStatus() != TaskStatus.COMPLETED) continue;
            // 三重验证第2条：检查下游任务是否也成功
            if (!areDownstreamTasksSuccessful(task, allTasks, taskResults)) continue;
            // 质量评分 ≥ 0.6 才沉淀
            double qualityScore = calculateQualityScore(task, taskOutput, originalRequest);
            if (qualityScore < minQualityThreshold) continue;
            // 签名去重
            String signature = buildSignature(task);
            if (depositedSignatures.contains(signature)) continue;
            // 构建示例并持久化
            JSONObject example = buildExample(task, taskOutput, originalRequest, qualityScore);
            persistExample(example, task.getCapabilityId());
            depositedSignatures.add(signature);
        }
        // 有新沉淀 → 热加载到知识库
        if (depositedCount > 0) exampleKnowledgeBase.reload();
    }

    // 深度检测——防止"表面成功实际失败"的结果被沉淀为正例
    private boolean isOutputIndicatingFailure(Object taskOutput) {
        if (taskOutput instanceof Map) {
            Map<?, ?> outputMap = (Map<?, ?>) taskOutput;
            if (outputMap.containsKey("error")) return true;  // 显式error字段
            Object status = outputMap.get("status");
            if (status instanceof String) {
                String s = ((String) status).toLowerCase();
                if (s.contains("failed") || s.contains("timeout")) return true;
            }
            Object taskResult = outputMap.get("task_result");
            if (taskResult instanceof String) {
                String r = ((String) taskResult).toLowerCase();
                if (r.contains("timed out") || r.contains("not implemented")) return true;
            }
        }
        return false;
    }
}
```

> **架构要点**：`isOutputIndicatingFailure()`的深度检测是v3.28的关键改进——执行器可能将HTTP 200的`status=SUCCESS`透传，但实际内容含`"status":"failed"`。此方法检查error字段、status值、task_result关键词三个维度，防止低质量结果污染知识库。

**示例知识库"反熵"主动治理**（v3.28）——知识库的质量不仅取决于"加了什么"，更取决于"清理了什么"：

| 治理机制 | 具体内容 | Harness意义 |
|---------|---------|-----------|
| **deprecated示例过滤** | `filterDeprecatedExamples()`过滤agentbay_browser_*前缀示例 | 防止已废弃能力的历史示例误导AI拆解 |
| **L2候选过滤** | 示例推荐的能力ID必须在L1候选列表中 | 确保示例推荐不超出匹配范围 |
| **深度输出失败检测** | `isOutputIndicatingFailure()`检测status/error_code字段 | 防止"表面成功实际失败"的结果被沉淀为正例 |

> 这三项治理机制体现了Harness工程的**"反熵"设计**——主动降低知识库熵值，保持搜索空间收敛的精度。

**核心代码逻辑**（`ExampleKnowledgeBase.java`，893行）——三步启动+三层匹配降级+知识治理：

```java
@Service
public class ExampleKnowledgeBase {
    @Autowired(required = false) private TairVectorService tairVectorService;
    @Autowired(required = false) private AIDecisionEngine aiDecisionEngine;
    @Value("${tairvector.example-vector.enabled:true}") private boolean exampleVectorEnabled;

    private final Map<String, List<CapabilityExample>> exampleCache = new ConcurrentHashMap<>();
    private final List<CapabilityExample> allExamples = new ArrayList<>();
    private volatile boolean exampleVectorIndexReady = false;
    private static final String EXAMPLE_VECTOR_INDEX = "tvs_examples";

    // ========== 三步启动流水线 ==========
    @PostConstruct
    public void init() {
        loadAllExamples();             // Step 1: 扫描 orchestrator_knowledge/examples/**/*.json
        asyncVectorizeAllExamples();   // Step 2: daemon线程异步向量化到tvs_examples索引
    }

    // 3种JSON格式兼容加载
    private void loadAllExamples() {
        Resource[] resources = resolver.getResources(EXAMPLES_BASE_PATH + "**/*.json");
        for (Resource resource : resources) loadExampleFromResource(resource);
        // 扫描失败时降级到loadBuiltInExamples()（9个领域内置示例兜底）
    }
    private void loadExampleFromResource(Resource resource) {
        // 格式1: JSONArray（纯数组格式）
        // 格式2: JSONObject + "examples"嵌套（{"version":"v1", "examples":[...]}）
        // 格式3: 单个JSONObject（标准格式 / DAG编排格式）
        // DAG编排格式兼容：subtasks.size==1 → 取capability; subtasks.size>1 → "composite"
        // 防御性检查：ConcurrentHashMap不允许null key → 跳过无capability_id的示例
    }

    // 异步向量化——daemon线程，不阻塞启动，向量化完成前自动降级
    private void asyncVectorizeAllExamples() {
        if (tairVectorService == null || !exampleVectorEnabled) return;
        Thread vectorizeThread = new Thread(() -> {
            tairVectorService.createIndex(EXAMPLE_VECTOR_INDEX, 1024); // 幂等创建
            for (CapabilityExample example : new ArrayList<>(allExamples)) {
                vectorizeSingleExample(example);  // 逐一插入向量文档
            }
        }, "example-vectorize");
        vectorizeThread.setDaemon(true);
        vectorizeThread.start();
    }

    // ========== 三层匹配降级核心 ==========
    public List<CapabilityExample> findSimilarExamples(String capabilityId, String userRequest, int topK) {
        // 按能力ID定位候选池，无匹配时降级到全局allExamples
        List<CapabilityExample> candidates = (capabilityId != null) ? exampleCache.get(capabilityId) : null;
        if (candidates == null || candidates.isEmpty()) candidates = allExamples;

        // 【Path 1: VECTOR】TairVector语义预筛（开关控制，任何异常完全无损回退）
        if (isExampleVectorAvailable() && userRequest != null) {
            try {
                List<CapabilityExample> vectorResults = findSimilarExamplesWithVector(
                        capabilityId, userRequest, topK, candidates);
                if (vectorResults != null && !vectorResults.isEmpty()) return vectorResults;
            } catch (Exception e) {
                // 向量预筛任何异常 → 完全无损回退到Path 2
            }
        }
        // 【Path 2: AI_LLM】EXAMPLE_MATCH决策类型
        if (aiDecisionEngine != null) {
            try {
                List<CapabilityExample> aiResult = matchExamplesWithAI(candidates, userRequest, topK);
                if (aiResult != null) return aiResult;
            } catch (Exception e) {
                // AI匹配失败 → 降级到Path 3
            }
        }
        // 【Path 3: RULES】标签匹配0.3/tag + 关键词匹配0.2/word × quality加权
        return matchExamplesByRules(candidates, userRequest, topK);
    }

    // VECTOR路径内部：向量预筛 + 精选
    private List<CapabilityExample> findSimilarExamplesWithVector(...) {
        // Step 1: 向量语义预筛（请求 topK×3 个候选，留出精选空间）
        List<Map<String, Object>> vectorResults = tairVectorService.semanticSearch(
                EXAMPLE_VECTOR_INDEX, userRequest, Math.min(topK * 3, candidates.size()), filter);
        // Step 2: 从向量结果解析示例ID，映射到内存CapabilityExample对象
        List<CapabilityExample> resolved = resolveFromVectorResults(vectorResults, candidates);
        // Step 3: 候选 ≤ topK → 直接返回；> topK → AI精选（仅在预筛候选上，非全量）
        if (resolved.size() <= topK) return resolved;
        return matchExamplesWithAI(resolved, userRequest, topK);
    }

    // deprecated示例主动治理——三步清理确保所有存储层彻底移除
    public Map<String, Object> cleanupDeprecatedExamples() {
        List<String> deprecatedPrefixes = List.of("agentbay_browser_", "agentbay_computer_");
        // Step 1: 从Tair Vector索引批量删除向量文档
        tairVectorService.deleteDocs(EXAMPLE_VECTOR_INDEX, vectorDocIds);
        // Step 2: 从内存allExamples中移除
        allExamples.removeIf(ex -> deprecatedIds.contains(ex.getId()));
        // Step 3: 从exampleCache中清理deprecated能力键 + 残留引用
        cacheKeysToRemove.forEach(exampleCache::remove);
    }
}
```

> **架构要点**：`findSimilarExamples()`的三层降级体现了"确定性优先，AI兜底"在知识检索维度的实践——VECTOR路径最高效（向量距离计算，topK×3预筛+精选两步法控制精度/召回平衡）、AI_LLM路径最智能（EXAMPLE_MATCH DecisionType，将示例摘要作为上下文传入）、RULES路径最可靠（标签匹配+关键词匹配+quality加权的确定性评分）。`asyncVectorizeAllExamples()`使用daemon线程避免阻塞启动，`exampleVectorIndexReady`用`volatile`修饰保证跨线程可见性，向量化完成前自动降级到Path 2/3——**零功能损失的增量增强**。`cleanupDeprecatedExamples()`三步清理（向量索引→内存列表→能力缓存）确保deprecated示例从所有存储层彻底移除，是上方"反熵"治理机制的代码基础。`loadExampleFromResource()`兼容3种JSON格式+DAG编排格式，使得手工编写的标准示例和ExampleAutoDepositService自动沉淀的DAG示例可以统一加载——知识源的多样性不增加检索逻辑的复杂度。

### 4.3 多层渐进式匹配——跨域统一模式

| 领域 | 示例规模 | 匹配层数 | 收敛路径 | 独特增强 |
|------|---------|---------|---------|---------|
| **编排引擎** | 262示例+215能力 | **2层** | L1功能域→L2精确能力 | TairVector向量语义预筛 |
| **AI应用** | 675示例 | **5层** | 意图→契约→生成→验证→修复 | UnifiedContract三端契约 |
| **游戏大师** | 292示例 | **4层** | L1类型→L2机制→L3预筛→L4精确 | 多维度复杂度评估 |
| **体感游戏** | 170示例(85×2) | **双模式** | 在线/离线×(L1类型→L2手势) | TF.js CDN/本地双路径 |
| **音乐生成** | 匹配+11阶段 | **8层** | 风格→元素→预筛→精确→分析→设计→评估→优化 | LRU缓存+跨模态评估 |
| **前端AI** | 317示例 | **8层** | Meta→反思→特征→匹配→反思→筛选→降级→审查 | 三次AI反思机制 |

核心公式不变：**Search Space = O(N) → O(K) → O(1)**。

#### 4.3.1 游戏大师——292示例四层渐进匹配

**代码证据**：`SmartPromptAssembler.java` + `AIGameKnowledgeService.java` + `EnhancedGameAppGeneratorService.java`

```java
// L1 类型匹配：292示例 → 按20种游戏类型筛选
GameType gameType = GameType.recommendFromDescription(params.getDescription());
// 日志：[L1匹配] 主类型: 射击类, 置信度: 0.95, 耗时=8027ms

// L2 机制匹配：32种机制 → 3-8个相关机制
List<String> mechanisms = extractGameMechanisms(params.getDescription());

// L3 预筛选：292示例 → 5-15个候选（多维度交叉筛选）
List<GameExample> candidates = preFilterExamples(typeMatches, mechanisms, complexityLevel);

// L4 精确匹配：5-15候选 → 1个最佳（语义相似度）
GameExample bestMatch = findBestMatchExample(candidates, params.getDescription());
// 日志：[L4匹配] 最终选择: shooter_contra.html, 置信度: 0.98, 总耗时: 60822ms
```

**L1+L2知识库索引实物证据**——上述Java代码中`GameType.recommendFromDescription()`和`extractGameMechanisms()`的底层知识源：

**代码证据**：`game_knowledge/index/game-type-index.json`（169行）+ `game_knowledge/index/game-mechanics-index.json`（264行）

```json
// ========== game-type-index.json（L1类型索引）—— 292示例 × 20类型 ==========
{
  "version": "2.0",
  "description": "游戏类型索引（L1），供大模型进行类型匹配使用，包含292个游戏示例的完整分类",
  "totalGames": 292,
  "types": [
    {
      "id": "puzzle",
      "name": "益智类",
      "keywords": ["益智", "puzzle", "思考", "策略", "解谜", "逻辑", "消除", "数字", "拼图"],
      "examples": ["puzzle-2048", "sokoban-pusher", "match-three-gems", "sudoku-solver", ...],  // 40个
      "typicalMechanics": ["grid-movement", "match-three", "score-system", "level-system", "undo-system"]
    },
    {
      "id": "arcade", "name": "经典街机",
      "keywords": ["街机", "arcade", "经典", "复古", "像素", "retro"],
      "examples": ["space-invaders", "pacman-maze", "tetris-classic", ...],  // 16个
      "typicalMechanics": ["shooting", "collision-detection", "score-system", "lives-system"]
    },
    {
      "id": "sports", "name": "体育类",
      "keywords": ["体育", "sports", "运动", "球类", "竞技", "田径", "奥运"],
      "examples": ["basketball-shoot", "football-kick", "tennis-rally", ...],  // 32个
      "typicalMechanics": ["physics", "score-system", "timer", "collision-detection"]
    },
    // ... 共20种类型：puzzle/arcade/casual/action/sports/board/music/education/
    //     word/simulation/memory/creative/adventure/tower-defense/racing/
    //     snake/shooter/fighting/platformer/card-game
  ]
}

// ========== game-mechanics-index.json（L2机制索引）—— 32种游戏机制 ==========
{
  "version": "2.0",
  "description": "游戏机制索引（L2），供大模型进行机制匹配使用，包含32个游戏机制",
  "mechanics": [
    {
      "id": "shooting", "name": "射击机制",
      "keywords": ["射击", "子弹", "发射", "攻击", "shoot", "bullet", "fire"],
      "codePatterns": ["bullet", "shoot", "fire", "projectile", "laser", "missile"],
      "relatedMechanics": ["collision-detection", "score-system", "lives-system"]
    },
    {
      "id": "physics", "name": "物理模拟",
      "keywords": ["物理", "重力", "弹跳", "摩擦", "physics", "gravity"],
      "codePatterns": ["physics", "velocity", "acceleration", "friction", "bounce"],
      "relatedMechanics": ["collision-detection", "jumping"]
    },
    {
      "id": "match-three", "name": "三消机制",
      "keywords": ["消除", "匹配", "三消", "连线", "match", "eliminate"],
      "codePatterns": ["match", "swap", "eliminate", "gem", "消除", "连"],
      "relatedMechanics": ["score-system", "combo-system", "grid-movement"]
    },
    // ... 共32种：shooting/jumping/grid-movement/push-mechanics/collision-detection/
    //     score-system/lives-system/level-system/undo-system/timer/match-three/
    //     physics/ai-enemy/wave-system/combo-system/power-up/inventory/maze/
    //     card-system/multiplayer/touch-control/drag-drop/room-navigation/
    //     resource-management/upgrade-system/trading/building/puzzle-solving/
    //     rhythm-timing/particle-effects/day-night-cycle/weather-system
  ]
}
```

> **§4理论映射**：(1) L1的`keywords`数组是搜索空间收敛的**第一级漏斗**——用户输入"做一个射击游戏"，确定性关键词匹配直接定位`shooter`类型（20个类型中命中1个），搜索空间从292→~20；(2) L2的`codePatterns`是注入Prompt的**代码级约束**——匹配到`shooting`机制后，`["bullet", "shoot", "fire", "projectile"]`直接告诉AI"生成的代码必须包含这些函数/变量名"；(3) `typicalMechanics`和`relatedMechanics`形成**类型↔机制的双向交叉索引**——L1输出的类型关联典型机制，L2输出的机制关联相关机制，两级索引交叉验证，避免单级匹配的遗漏；(4) 两个JSON文件合计433行纯数据，**零代码、零AI调用**，却驱动了整个四层匹配的前两层——这就是"知识驱动"的本质：搜索空间收敛的前置层完全由确定性知识完成，AI只在L4语义相似度阶段才被调用。

**多维度复杂度评估**（`GameComplexityEvaluator.evaluateByRules()`）：机制数量(30分)+状态管理(25分)+场景数量(20分)+交互复杂度(15分)+特殊功能(10分) = 100分。分三档驱动生成策略：SIMPLE(≤40)/MEDIUM(41-70)/COMPLEX(>70)。纯规则评估，零LLM消耗。

#### 4.3.2 AI应用——675示例五层+契约驱动

**代码证据**：`SmartIntentionService` + `ContractGenerationService` + `SmartCodeGenerationService` + `SmartValidationService` + `SmartRepairService`

```
第1层：意图理解 → 判断PURE_HTML/FULLSTACK + OnlineSearchService搜索增强
第2层：智能决策 → 全栈应用生成OpenAPI 3.0契约（消灭"幻想API"）
第3层：代码生成 → 知识库匹配注入 + 多模型协同(qwen3-coder-plus+qwen3-max)
第4层：智能验证 → 语法30% + 逻辑30% + 契约符合度40%
第5层：渐进修复 → 最多3轮，每轮携带上轮验证反馈
```

**UnifiedContract三端一致性编码**（`UnifiedContract.java`，~548行）——同一份契约对象分别生成HTML/JavaScript/Python三端约束代码。

**契约驱动的端到端流程**：
```
用户需求："做一个图书管理系统"
    ↓
ContractGenerationService 生成 OpenAPI 3.0 规范
    ↓
{ "openapi": "3.0.0", "paths": { "/api/books": { "get": {...}, "post": {...} } } }
    ↓
UnifiedContract 三端契约块生成：
  toHtmlContractBlock()       → 元素ID表 + "禁止修改ID"硬约束
  toJavaScriptFetchCode()     → 🔥 直接生成可用的fetch调用代码
  toPythonRouteCode()         → 🔥 直接生成Flask路由代码骨架
    ↓
SmartValidationService 三维度验证（契约符合度占比最高40%）
```

**核心数据结构**：
```java
// UnifiedContract 核心字段
private Map<String, ElementInfo> elementIds;     // 元素ID映射（语义名称→DOM ID）
private List<EndpointInfo> apiEndpoints;          // API端点（路径+方法+请求/响应字段）
private ServerConfig serverConfig;                // 服务器配置（端口+基础URL）
private PreGeneratedIdentifiers preGeneratedIdentifiers;  // 多端统一标识符
```

`toJavaScriptFetchCode()`和`toPythonRouteCode()`的Harness价值——**不信任AI能记住契约细节，直接将契约编码为代码片段注入Prompt**，AI只需填充业务逻辑，大幅降低"幻想API"概率。

**核心代码逻辑**（`UnifiedContract.java` + `UnifiedContractServiceImpl.java`）——三端契约块生成与契约构建：

```java
// ========== UnifiedContract.java —— 三端契约对象（~548行）==========
@Data @Builder
public class UnifiedContract {
    private String contractId;
    private Map<String, ElementInfo> elementIds;       // 元素ID映射（语义名称→DOM ID）
    private List<EndpointInfo> apiEndpoints;            // API端点（路径+方法+请求/响应字段）
    private ServerConfig serverConfig;                  // 服务器配置（端口+基础URL）
    private PreGeneratedIdentifiers preGeneratedIdentifiers;  // 多端统一标识符

    // ========== HTML契约块：生成元素ID表 + 硬约束 ==========
    public String toHtmlContractBlock() {
        // 输出表格：| 语义名称 | 元素 ID | 元素类型 | 描述 |
        // ⚠️ 硬约束：禁止修改ID / 禁止使用其他命名 / id属性必须完全一致
    }

    // ========== JavaScript契约块：生成ELEMENT_IDS常量 + fetch代码 ==========
    public String toJavaScriptContractBlock() {
        sb.append("const ELEMENT_IDS = {\n");
        for (Map.Entry<String, ElementInfo> entry : elementIds.entrySet()) {
            String camelKey = toCamelCase(entry.getKey());
            if (!isValidJavaScriptIdentifier(camelKey)) continue;  // 🔴 跳过中文键名
            sb.append(String.format("    %s: '%s'", camelKey, entry.getValue().getId()));
        }
        // + API配置（端口+baseUrl）+ 三端一致性约束（5条）
    }

    // ========== 🔥 AI Native：直接生成可用的JavaScript fetch调用代码 ==========
    public String toJavaScriptFetchCode() {
        for (EndpointInfo endpoint : apiEndpoints) {
            String functionName = generateFunctionName(endpoint.getPath());
            // 生成: async function apiV1LoanCalculate(params) { fetch(baseUrl+path, {...}) }
            // 🚨 注释强调：请求体字段必须使用snake_case（如 loan_amount, interest_rate）
            // + 示例调用代码（字段名必须完全一致）
        }
    }

    // ========== 🔥 AI Native：直接生成Flask路由代码骨架 ==========
    public String toPythonRouteCode() {
        for (EndpointInfo endpoint : apiEndpoints) {
            String functionName = generatePythonFunctionName(endpoint.getPath());
            // 生成: @app.route('/api/...', methods=['POST'])
            //       def api_v1_loan_calculate():
            //           data = request.get_json()
            //           loan_amount = data.get('loan_amount')  # snake_case字段
            //           # TODO: 实现业务逻辑
        }
        // + if __name__ == '__main__': app.run(port=serverConfig.port)
    }

    // ========== 字段名映射表（camelCase ↔ snake_case 双向）==========
    public Map<String, String> getFieldNameMapping() {
        // 遍历apiEndpoints的请求/响应字段，生成 camelCase→snake_case 映射
        // 供前端代码生成时校验字段名一致性
    }
}

// ========== UnifiedContractServiceImpl.java —— 契约构建（~294行）==========
@Service
public class UnifiedContractServiceImpl implements UnifiedContractService {
    // 常见UI元素类型正则映射：输入→input, 按钮→button, 选择→select, 结果→div
    private static final Map<String, String> ELEMENT_TYPE_PATTERNS = new LinkedHashMap<>();

    public UnifiedContract generateUnifiedContract(
            IntentionAnalysisResult intentionResult, ApiContract apiContract, Integer port) {
        // 1. 从意图分析+API契约中提取UI元素（请求字段→input, 响应字段→div）
        Map<String, ElementInfo> elementIds = extractElementIds(intentionResult, apiContract);
        // 2. 从API契约提取端点（路径+方法+请求/响应字段）
        List<EndpointInfo> endpoints = extractEndpoints(apiContract);
        // 3. 构建服务器配置（ServerIpConfig.getLocalHostIp()动态获取）
        ServerConfig serverConfig = ServerConfig.builder()
            .port(port != null ? port : 18000)
            .baseUrl("http://" + ServerIpConfig.getLocalHostIp() + ":" + port)
            .build();
        // 4. 组装统一契约（UUID + 时间戳 + snake_case命名规范）
        return UnifiedContract.builder()
            .contractId(UUID.randomUUID().toString())
            .elementIds(elementIds).apiEndpoints(endpoints)
            .serverConfig(serverConfig).fieldNamingConvention("snake_case").build();
    }

    /** 从请求字段推断UI元素类型：枚举→select, boolean→checkbox, 默认→input */
    private String guessElementType(ApiContract.Field field) {
        if (field.getEnumValues() != null && !field.getEnumValues().isEmpty()) return "select";
        if (field.getType().contains("boolean")) return "checkbox";
        return "input";
    }
}
```

> **架构要点**：`UnifiedContract`解决全栈AI应用代码生成中最棘手的"幻想API"问题——HTML用`elementA`而JS用`element_a`，前端调`/api/calc`而后端是`/api/calculate`。三端契约块生成方法（`toHtmlContractBlock()`/`toJavaScriptFetchCode()`/`toPythonRouteCode()`）将**同一份契约对象**编码为三种语言的代码片段直接注入Prompt，AI只需在骨架上填充业务逻辑。`isValidJavaScriptIdentifier()`过滤中文键名是生产中发现的防御——大模型偶尔生成中文标识符导致JS语法错误。`UnifiedContractServiceImpl`从`ApiContract`确定性提取元素和端点，不再依赖大模型推断UI结构。

**全栈渐进式披露四层架构**（`FullstackAIOrchestrationServiceImpl.java`，~547行）：

```
Layer 1: 场景识别（ScenarioIdentificationResult）→ 提取特征+关键词+复杂度
Layer 2: Python库推荐（LibraryRecommendationResult）→ 库索引按场景精简注入
Layer 3: 示例筛选（ExampleSelectionResult）→ 匹配度=场景×0.4+特征×0.4+关键词×0.2
Layer 4: 分离式代码生成 → Python后端/HTML前端/JavaScript交互 + 契约注入
```

**0.3匹配度阈值**——"有约束地信任AI"：
```java
public static final double MATCH_SCORE_THRESHOLD = 0.3;
if (matchScore >= MATCH_SCORE_THRESHOLD) {
    Map<String, String> codes = loadExampleCodes(List.of(bestExampleId));  // 加载示例约束
} else {
    log.info("匹配度过低 ({}), 不使用示例代码", matchScore);  // 避免误导AI
}
```

低于阈值不加载示例（避免误导AI），让模型在自由度和约束度间自适应平衡。

#### 4.3.3 音乐——八层AI驱动匹配+11阶段流水线

**代码证据**：`AIMusicKnowledgeService.java` + `MusicGenerationService.java`

**8层AI驱动匹配架构**（L1-L8）：

```
L1 风格匹配   → 从用户描述识别音乐风格（流行/古典/电子/爵士等）
L2 元素匹配   → 并行提取节奏、调式、乐器等音乐元素
L3 示例预筛选 → 并行筛选候选音乐示例
L4 精确匹配   → 语义相似度选择最佳示例
L5 MIDI深度分析 → 对匹配示例进行MIDI结构分析（音符/和弦/时值分布）
L6 AI旋律设计 → LRU缓存旋律设计结果，避免重复生成（~500行Prompt）
L7 质量评估   → 5维度100分制评估（旋律/和声/节奏/乐器/整体各20分）
L8 迭代优化   → 质量不达标时自动迭代改进（最多3次循环）
```

**11阶段生成流水线**：

```
阶段1: 多模态分析（图片→色彩情感、音频→节奏风格、视频→场景氛围）
阶段2: 风格分析（MusicStyleAnalyzer）
阶段3: 歌词生成（LyricsGenerationService）
阶段4: 音乐理论设计（含4层匹配 + 自检 + Bug检测修复 + 质量评估）
阶段5: Python MIDI生成代码执行（FluidSynth 48kHz渲染）
阶段6: OSS上传
阶段7: 音频质量评估（Qwen3-Omni-Flash模型听觉评估）
阶段8: 封面图生成（QwenImageMax两阶段生成）
阶段9: 乐器信息提取
阶段10: 标题生成
阶段11: 响应构建
```

每层输出是下层输入，搜索空间逐层收敛。L6旋律设计的Prompt是系统中最复杂的（~500行八段式），L5的MIDI分析数据直接注入L6上下文，使AI在精确的音乐结构约束下设计旋律。

#### 4.3.4 体感游戏——170示例双模式确定性分叉

**代码证据**：`MotionGameGeneratorService.java` + `MotionGameKnowledgeService` + `MotionPromptAssembler`

**知识库结构**：

```
motion_game_knowledge/
├── index/                    # 类型索引、手势索引
├── prompts/
│   ├── motion-generation-template.md        # 在线模式Prompt（TF.js CDN）
│   ├── motion-l1-matching-prompt.md         # L1类型识别
│   ├── motion-l2-matching-prompt.md         # L2手势匹配
│   ├── local_motion-generation-template.md  # 离线模式Prompt（本地/libs/tfjs/）
│   ├── local_motion-l1-matching-prompt.md   # 离线L1
│   └── local_motion-l2-matching-prompt.md   # 离线L2
├── html/                     # 85个在线示例（引用CDN）
├── local_html/               # 85个离线示例（引用本地tfjs）
└── gesture-mapping-templates.json  # 手势映射配置
```

15种游戏类型（`MotionGameType`枚举），每种预定义必需手势和可选手势。10种手势（`MotionGesture`枚举），每种定义检测条件、优先级和冷却时间（如SHIELD冷却500ms、SKILL冷却3000ms）。

**在线/离线双模式——同一流水线双路径依赖解析**：`offlineMode`布尔参数在三个维度做确定性分叉：

```java
// MotionGameGeneratorService.generateMotionGame()
boolean offlineMode = params.isOfflineMode();
// 维度1: Prompt模板选择 → 在线用motion-*模板，离线用local_motion-*模板
// 维度2: 示例匹配 → 在线从html/匹配（85个），离线从local_html/匹配（85个）
// 维度3: CDN路径 → 在线引用cdn.jsdelivr.net，离线引用/libs/tfjs/
```

AI完全不感知在线/离线区别——`offlineMode`参数仅在工程层的Prompt选择、示例加载、路径注入中生效。这是"**工程层透明切换运行环境，AI层专注代码生成**"的Harness模式。

**素材使用5条铁律**——Prompt `[Hard Constraints]`段约束AI正确使用用户素材：

| 铁律 | 约束内容 | 防止的AI典型错误 |
|------|---------|-----------------|
| type=image | 素材类型必须为image | AI可能错误分类为sprite/video |
| new Image() | 必须用Image对象加载 | AI可能直接写src字符串 |
| drawImage() | 必须用Canvas API绘制 | AI可能用CSS background |
| 相对路径 | 必须使用assets/xxx相对路径 | AI可能编造绝对路径或URL |
| 禁止base64 | 禁止将素材转为base64内联 | AI可能为"方便"转为data:image |

**GAME_CONFIG 6域标准结构**——系统通过`AIGameKnowledgeService`自动生成包含6个标准域的配置模板注入Prompt，约束AI必须按此格式组织代码中的游戏配置：

**代码证据**：`AIGameKnowledgeService.java` L829-839 + `AssetAvailabilityContext.java`（440行）

```javascript
const GAME_CONFIG = {
    characters: { /* 角色配置：type/src/width/height/states(8种状态) */ },
    items:      { /* 道具配置：type/src/color，支持多个道具实体 */ },
    background: { /* 背景配置：type(solid|gradient|image)/src/colors */ },
    ui:         { /* UI配置：primaryColor/fontFamily */ },
    audio:      { /* 音频配置：bgm{src/loop/volume} */ },
    parameters: { /* 游戏参数：tileSize/cols/rows/timeLimit等 */ }
};
```

真实游戏示例（`frogger-crossing/index.html`）展示了GAME_CONFIG的工程化素材降级模式：`type:'shape'`+`src:null`+`fallbackColor`——当用户未提供素材时，AI必须使用Canvas纯色绘制代替图片渲染，通过配置模板而非自由发挥决定降级策略。体感游戏场景进一步扩展了`characters.player.states`（8种：normal/jumping/ducking/attacking/invincible/shield/sprint/skill），支持不同游戏状态切换不同素材。

**AssetAvailabilityContext——三位置Prompt注入架构**：

`AssetAvailabilityContext`（440行）基于"边缘效应"设计原则，在Prompt的三个战略位置注入素材约束，让AI"看得见"哪些素材可用、哪些必须降级：

| 组件 | 类型 | 职责 |
|------|------|------|
| `AssetPurpose` | 枚举(4种) | BACKGROUND/CHARACTER/ITEM/UI 用途分类 |
| `AssetInfo` | 内部类 | path + description + purpose 素材三元组 |
| `AudioAssetInfo` | 内部类 | originalUrl + ossUrl + purpose（OSS音频素材） |
| `availableAssets` | EnumMap | 按用途分类的可用素材映射（类型安全） |
| `missingPurposes` | List | 缺失素材的用途列表（动态维护） |

三位置注入策略：

| 注入方法 | Prompt位置 | 注意力原理 | 生成内容 |
|---------|-----------|-----------|---------|
| `toPromptSection()` | Prompt前部 | 首部高注意力区（边缘效应） | 表格化素材清单（用途/状态/路径/处理方式） |
| `toHardConstraintsSection()` | Hard Constraints段 | 约束收敛区 | 三大铁律 + 动态缺失素材列表 |
| `toSelfCheckSection()` | Prompt末端 | 终端注意力锚点 | 5项素材检查 + 3项变量初始化检查 |

**三大铁律动态注入机制**——`toHardConstraintsSection()`遍历`missingPurposes`列表，将具体缺失素材动态注入铁律二（如"当前缺失素材：背景、敌人——这些用途的src必须设为null"），让模型精确知道**哪些用途必须降级**而非笼统的"缺失素材设null"。

**MotionPromptAssembler动态GAME_CONFIG生成**（`MotionPromptAssembler.java` L394-444）——当用户提供了自定义素材时，系统遍历`processedAssets`列表，按`AssetType`（CHARACTER/BACKGROUND）动态生成带真实路径的GAME_CONFIG代码片段，并附带"禁止的代码模式"表（如`characters.player.type='shape'`→"用户已提供角色素材，必须使用`'image'`"），从配置模板到动态代码双层约束AI的素材使用行为。

> 5条铁律管"素材如何正确引用"（type/加载/绘制/路径/编码），GAME_CONFIG标准结构管"素材如何结构化配置"（6域模板），AssetAvailabilityContext管"素材约束如何注入Prompt"（三位置边缘效应），三者构成完整的素材Harness闭环。

#### 4.3.5 前端AI——八层决策+三次反思

**代码证据**：`FrontendAIOrchestrationServiceImpl.java`（~1155行）

前端可视化生成是AI应用中最复杂的场景——317个示例涵盖Chart.js、ECharts、D3.js等多个库，选错库或选错示例会导致生成代码完全不可用。系统构建了**8层AI Native决策架构**：

```
Layer 0:   Meta判断（performMetaJudgment）
  → AI动态生成评估标准，判断 pure_html / native_api / use_library

Layer 0.5: 反思（performReflection）
  → 对Meta判断进行自我审视，shouldRevise=true时修正决策

Layer 1:   技术特征提取（extractTechnicalFeatures）
  → 从需求中提取图表类型、数据结构、交互方式等技术特征

Layer 2:   能力匹配（performCapabilityMatching）
  → 根据技术特征匹配最合适的可视化库和实现方案

Layer 2.5: 能力匹配反思（performCapabilityReflection）
  → 对匹配结果进行反思，评估是否需要降级到原生方案

Layer 3:   示例筛选（selectExamples）
  → 从知识库中筛选最匹配的示例代码

Layer 3.5: AI驱动降级评估（performFallbackEvaluation）
  → 仅在Layer 3示例加载失败时触发
  → 三种决策：fallback_to_native / fallback_to_html / continue

Layer 4:   最终全局审查（performFinalGlobalReview）
  → 综合所有前置Layer的信息做最终决策（必定执行）
  → 输出：use_pure_html / use_native_api / use_library
```

**关键设计**：每个Layer的结果都"不提前返回"，而是作为Layer 4的**参考输入**。即使Layer 0建议pure_html，Layer 2建议native_api，**最终决策权在Layer 4**——它综合所有前置信息做全局最优判断，避免任何单层误判导致不可逆的错误。三次AI反思机制（Layer 0.5/2.5/3.5）确保匹配结果的可靠性。

### 4.4 文档知识库——file-id锚定抗幻觉

**代码证据**：`DataMiningServiceImpl.java`（379行）+ `QwenLongQuestionAnswerService.java`（901行）

**file-id引用机制**：上传文档获得唯一file-id，后续所有对话通过file-id引用文档全文，模型在1000万Token上下文窗口中检索任意部分——工程化的RAG替代方案，无需向量化和分块。

**HTML格式自动转换**：qwen-long不支持HTML文件，工程层自动检测并转换（方案A: HTML→PDF保结构，方案B降级: HTML→TXT保内容），用户无感知。

> 文档分析的"专模型做专事"协作架构（首轮qwen-doc-turbo + 追问qwen-long）详见§七。

### 4.5 实时知识增强——从训练数据到围墙花园

**代码证据**：`OnlineSearchService.java`（~355行）+ `SearchPreJudgmentEngine.java`（~220行）+ `SearchResultAggregator.java`（~302行）+ 11个`DomainSearchAdapter`，合计~4,255行/23个Java文件

§4.1-4.4讨论的知识库都是**离线知识**——示例、模板、文档在开发期沉淀。但LLM面临一个根本矛盾：**训练数据天然过时**（截止日期），且大量有价值的实时信息存在于模型无法触达的**围墙花园**——微信公众号、小红书、微博等封闭平台的内容从未进入任何模型的训练语料。当用户问"今天杭州天气""苹果最新财报""小红书穿搭趋势"时，模型只能猜测或拒绝。

OnlineSearchService将§4核心公式**从离线知识库延伸到实时互联网**——O(全网∞) → O(LLM选定K个领域) → O(预算管控后精华)。

#### 4.5.1 LLM-as-Gate——用AI决定要不要搜

**核心矛盾**：盲搜每次消耗3-180秒+MCP调用开销，但大部分用户输入不需要搜索（如"你好""帮我写首诗"）。系统用**AI做门控决策**，而非对所有请求盲目触发搜索：

```
用户输入 → SearchPreJudgmentEngine.judge()
    ↓  Qwen3-Max通过DecisionType.SEARCH_STRATEGY判断
    ↓
needs_search=false → 快速短路，零额外开销（"你好"/"帮我写代码"）
needs_search=true  → 输出selected_domains + 每域专精query + confidence
    ↓
"今天杭州天气"   → [WEATHER], query="杭州", confidence=0.95
"苹果最新财报"   → [FINANCE_YFINANCE, GENERAL_WEB], query=["AAPL", "苹果财报"]
"小红书穿搭推荐" → [SOCIAL_XIAOHONGSHU], query="穿搭推荐"
```

这验证了**元原则的双向落地**——"确定性优先"（不搜=零开销，预算硬上限），"AI兜底"（LLM判断搜索必要性和领域选择）。`SearchPreJudgmentEngine`不可用时降级到`SearchJudgment.fallback()`默认搜GENERAL_WEB，保证功能不中断。

**核心代码逻辑**（`OnlineSearchService.java`）——完整的LLM门控→多域并行→预算管控聚合Pipeline：

```java
@Slf4j
@Service
@ConditionalOnBean(MCPToolAdapter.class)  // 条件加载：MCP适配层存在时才创建
public class OnlineSearchService {
    /** 适配器注册表（domain → adapter），@PostConstruct自动注册 */
    private final Map<SearchDomain, DomainSearchAdapter> adapterRegistry = new ConcurrentHashMap<>();
    private static final long DEFAULT_TOTAL_TIMEOUT_MS = 180000L;  // 3分钟总超时

    // ========== 智能搜索入口（完整流程）==========
    public OnlineSearchResponse smartSearch(String prompt, SearchPurpose purpose) {
        // Step1: LLM前置判断——Qwen3-Max决定是否需要搜索
        SearchJudgment judgment;
        if (preJudgmentEngine != null) {
            judgment = preJudgmentEngine.judge(prompt, purpose);
        } else {
            judgment = SearchJudgment.fallback(prompt);  // 降级：默认搜GENERAL_WEB
        }
        // Step2: 短路判断——不需要搜索时零额外开销
        if (!judgment.isNeedsSearch()) {
            return OnlineSearchResponse.empty(judgment);  // 快速返回
        }
        // Step3: 按LLM选定的领域并行搜索
        List<SearchResult> results = executeParallelSearch(judgment);
        // Step4: 聚合结果（含预算管控）
        return buildResponse(results, judgment, purpose, costMs);
    }

    // ========== CompletableFuture多域并行Pipeline ==========
    private List<SearchResult> executeParallelSearch(SearchJudgment judgment) {
        List<SearchJudgment.DomainQuery> validQueries = strategyEngine != null
                ? strategyEngine.validateAndFilter(judgment.getSelectedDomains(), adapterRegistry)
                : judgment.getSelectedDomains().stream()
                    .filter(dq -> adapterRegistry.containsKey(dq.getDomain())
                            && adapterRegistry.get(dq.getDomain()).isAvailable())
                    .collect(Collectors.toList());

        // 多域并行：每域独立超时+独立降级，任一失败不影响其他域
        List<CompletableFuture<SearchResult>> futures = validQueries.stream()
                .map(dq -> {
                    DomainSearchAdapter adapter = adapterRegistry.get(dq.getDomain());
                    return CompletableFuture.supplyAsync(() -> {
                                try { return adapter.searchPipeline(dq.getQuery(), dq.getMaxResults()); }
                                catch (Exception e) { return SearchResult.empty(dq.getDomain()); }
                            })
                            .orTimeout(adapter.getDefaultTimeoutMs(), TimeUnit.MILLISECONDS)
                            .exceptionally(ex -> SearchResult.empty(dq.getDomain()));  // 超时降级
                })
                .collect(Collectors.toList());

        // 等待全部完成，总超时3分钟
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(DEFAULT_TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) { /* 总超时，获取已完成的结果 */ }

        return futures.stream()
                .map(f -> { try { return f.getNow(null); } catch (Exception e) { return null; } })
                .filter(r -> r != null && r.getMergedContent() != null && !r.getMergedContent().isEmpty())
                .collect(Collectors.toList());
    }

    // ========== 预算管控聚合 ==========
    private OnlineSearchResponse buildResponse(List<SearchResult> results,
                                                SearchJudgment judgment,
                                                SearchPurpose purpose, long totalCostMs) {
        OnlineSearchResponse response = new OnlineSearchResponse();
        response.setResults(results);
        // 构建promptSnippet（经预算管控——4种SearchPurpose差异化预算）
        if (!results.isEmpty()) {
            response.setPromptSnippet(
                searchResultAggregator.buildPromptSnippetWithBudget(results, judgment, purpose));
        }
        return response;
    }

    // ========== 格式化为Prompt注入文本（按场景差异化）==========
    public String formatForPromptInjection(OnlineSearchResponse response, SearchPurpose purpose) {
        switch (purpose) {
            case PPT_CONTENT:    return searchResultAggregator.formatForPptPrompt(...);
            case APP_KNOWLEDGE:  return searchResultAggregator.formatForAppGenPrompt(...);
            case CHAT_KNOWLEDGE: return searchResultAggregator.formatForChatPrompt(...);
            default:             return searchResultAggregator.buildPromptSnippetWithBudget(...);
        }
    }
}
```

> **架构要点**：`OnlineSearchService`将§4的搜索空间收敛公式从离线知识库延伸到实时互联网。三个关键设计决策：(1) **LLM-as-Gate**——`smartSearch()`入口处由Qwen3-Max判断是否需要搜索，"你好"类请求零开销短路；(2) **CompletableFuture多域并行+独立降级**——`executeParallelSearch()`中每个域的超时和异常完全隔离，`SearchResult.empty()`保证任一域故障不影响整体；(3) **`@ConditionalOnBean`条件加载**——MCP适配层不存在时整个搜索服务不创建，调用方通过`@Autowired(required=false)`安全注入。

#### 4.5.2 11领域Pipeline并行——搜索空间逐层收敛

11个`DomainSearchAdapter`（通用网络/小红书/微信公众号/微博/B站/抖音/arXiv论文/Yahoo Finance/天气/高德/滴滴）通过接口+自动注册统一编排，每个适配器封装独立的**搜索→详情→合并Pipeline**：

```
LLM选定领域 → SearchStrategyEngine.validateAndFilter()
    ↓  验证适配器可用性 + 所有域不可用时降级到GENERAL_WEB
    ↓
CompletableFuture多域并行 → 每域独立超时(默认60s，域可自定义) + 独立降级
    ↓
SearchResultAggregator → 去重 + 相关性排序 + 预算管控聚合
```

**搜索空间收敛过程**：全网信息O(∞) → LLM选定2-3个领域O(K) → 每域Pipeline筛选top-3结果O(3K) → 预算裁减后的精华。这与§4.3的离线知识匹配在方法论上完全同构——只是知识源从本地文件变成了实时互联网。

#### 4.5.3 Anti-Overshadowing——搜索结果的质量护栏

联网搜索最微妙的工程问题不是"如何搜"，而是**"搜到的内容如何不喧宾夺主"**。搜索结果过多过长会淹没AI自身推理——模型退化为搜索结果的"复读机"而非"思考者"。系统通过**4层Anti-Overshadowing架构**解决：

```
Layer A: 适配器层截断 — 每域maxContentPerItem（小红书1000/微信3000/arXiv5000字符）
Layer B: 比例裁减 — maxSearchContentRatio（搜索内容不超过总Prompt的X%）
Layer C: 优先级加权分配 — LLM判断的领域优先级 × 权重[1.0, 0.7, 0.5, 0.4, 0.3]
Layer D: LLM摘要压缩 — 内容超出分配额2.5倍时调用Qwen3-Max智能压缩（保留核心数据和关键观点）
```

**4种SearchPurpose差异化预算**——同一OnlineSearchService跨3个场景复用，预算策略按场景自适应：

| 场景 | 调用方 | 总预算 | 搜索占比上限 | 设计意图 |
|------|--------|--------|-------------|---------|
| CHAT_KNOWLEDGE | ChatOrchestrationService | 4,000字符 | 10% | 最保守——对话中搜索仅补充事实，不改变AI回答逻辑 |
| APP_KNOWLEDGE | AiAppGenerationAsyncServiceImpl | 8,000字符 | 15% | 中等——代码生成需要技术资料但不能干扰架构设计 |
| PPT_CONTENT | PptGeneratorService | 12,000字符 | 25% | 最宽松——PPT内容生成高度依赖外部素材 |
| GENERAL | 通用入口 | 15,000字符 | 30% | 无约束场景 |

Chat场景的**反喧宾Prompt包裹**是Anti-Overshadowing最强约束——`SearchResultAggregator.formatForChatPrompt()`在搜索结果外包裹明确指令："以下信息**仅用于核实或补充事实性数据**（如日期、数字、名称），你的回答逻辑、观点和建议应基于你自身的理解，而非搜索结果的转述"。

> 实时知识增强是知识工程的"在线维度"——§4.1-4.4解决**AI看到什么离线知识**，§4.5解决**AI看到什么实时知识**。底层MCP传输基础设施（双协议/懒连接/反爬降级）详见§十 10.1。

### 4.6 知识库工程总结

从AI应用的675示例到音乐的1,348示例，每个领域的知识库都配备**多层匹配引擎+结构化Prompt模板**，形成"知识库+匹配器+Prompt"三位一体的Harness；OnlineSearchService进一步将这一模式延伸到实时互联网，通过LLM门控+11领域Pipeline+Anti-Overshadowing预算管控，在**离线知识+在线知识**双维度实现搜索空间收敛。核心公式：**O(N) → O(K) → O(1)**——每层匹配将搜索空间缩小一个数量级。这是**与模型无关的工程方法**——换任何模型，知识库和匹配架构都能提供同等的搜索空间收敛效果。

> 知识工程解决了"AI看到什么"。下一章解决更关键的问题：**AI看到了正确的输入，但输出质量如何保证？**

---

## 五、质量闭环——Generator-Evaluator架构的工程化落地

> **核心原理**：AI生成的结果不可能100%正确。与其期望模型"一次生成正确"，不如建立工程化的"生成→检测→修复→再验证"闭环。按检测手段分为四大类型：**确定性检测**（静态检查/规则引擎/代码验证）、**AI驱动检测**（LLM Bug分析/视觉评估）、**跨模态评估**（扩散模型生成+视觉模型评估）、**交叉验证**（同一输出的多维度校验）。

### 5.1 SelfTestEngine——独立Evaluator

**代码证据**：`SelfTestEngine.java`（~592行）

这是 Anthropic Generator-Evaluator 架构的工程化实现——Generator和Evaluator使用不同的DecisionType（不同Prompt模板），实现真正的角色分离：

```
编排引擎（Generator）→ 生成结果
      ↓
SelfTestEngine（Evaluator）→ 独立评估
      ↓
4维度质量评分 → 反馈到系统（不阻塞主流程）
```

**三层测试架构**：Level 1 URL可访问性测试（确定性检查）→ Level 2 AI内容质量评估（LLM驱动，`SELF_TEST_EVALUATE`决策类型）→ Level 3 浏览器自动化测试（Playwright/AgentBay，执行链路：SelfTestEngine→HttpAdapter→FastAPI:6600→Playwright→页面可访问性+交互验证）。

**4维度评分体系**（基于`ai_self_test_evaluate.txt`）：功能完整性40分 + 内容质量30分 + 技术可靠性20分 + 用户体验10分 = **总分100分，及格线70分**。

**核心代码逻辑**（`SelfTestEngine.java`）——三层测试架构与异常不阻塞主流程设计：

```java
@Service
public class SelfTestEngine {
    @Value("${optimus.selftest.enabled:true}")
    private boolean selfTestEnabled;
    @Value("${optimus.selftest.l3-enabled:true}")
    private boolean l3Enabled;

    public SelfTestReport runTests(String sessionId, String originalPrompt,
                                     String resultHtml, TaskResult taskResult) {
        if (!selfTestEnabled || resultHtml == null) return null;
        SelfTestReport report = SelfTestReport.builder().sessionId(sessionId).build();
        try {
            // L1: URL可访问性验证（确定性检查，HTTP HEAD请求）
            List<TestCaseResult> l1Results = runL1UrlTests(resultHtml, taskResult);
            // L2: AI内容质量验证（LLM驱动，SELF_TEST_EVALUATE决策类型）
            List<TestCaseResult> l2Results = runL2ContentTests(sessionId, originalPrompt, resultHtml, taskResult);
            // 统计 + AI综合评估
            evaluateOverall(report, sessionId, originalPrompt);
        } catch (Exception e) {
            report.setPassed(true);   // 测试异常不阻塞主流程
            report.setQualityScore(0); // 异常时设为0表示未能有效评估
        }
        return report;
    }

    // L1: 从HTML中提取URL，限制最多检查5个，跳过data:/localhost/超长URL
    private List<TestCaseResult> runL1UrlTests(String resultHtml, TaskResult taskResult) {
        Set<String> urls = extractUrls(resultHtml);  // Jsoup解析img/a/iframe + 正则补充
        for (String url : urls) {
            if (checkCount >= MAX_URL_CHECKS) break;
            if (shouldSkipUrl(url)) continue;        // 跳过data:/localhost/长度>2000
            // HTTP HEAD请求，10秒超时，2xx/3xx为通过
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(URL_CHECK_TIMEOUT_MS);
        }
    }

    // L2: AI生成测试计划 → 执行测试用例（最多8个）
    private List<TestCaseResult> runL2ContentTests(...) {
        AIDecision testPlanDecision = aiDecisionEngine.makeDecision(
                DecisionType.SELF_TEST_PLAN, context, false);
        if (testPlanDecision != null) {
            return executeTestPlan(testPlanDecision.getRawResponse(), resultHtml);
        } else {
            return runBasicContentChecks(resultHtml, originalPrompt); // AI不可用时降级
        }
    }
}
```

> **架构要点**：`catch`块中`report.setPassed(true)`是关键设计决策——自测试是质量增强而非阻断机制，任何测试异常都不应中断用户体验。L2测试中AI生成测试计划失败时，降级到`runBasicContentChecks()`基础内容检查，体现"确定性优先"。

### 5.2 编排内部闭环——任务验证+渐进修复

**代码证据**：`TaskValidator.java`（751行）+ `TaskAutoFixer.java`（686行）

编排流水线内部的Generator-Evaluator闭环：AI拆解任务（Generator）→ TaskValidator验证7项指标（Evaluator）→ TaskAutoFixer渐进修复（最多3轮）→ 修复后再次验证。

**TaskValidator——7项验证指标**：

```java
public enum ValidationMetric {
    STRUCTURE_COMPLETE("任务结构完整性", true),      // required：id/name/description/capabilityType必填
    CAPABILITY_MATCH("能力匹配准确性", false),        // 验证capabilityId在注册表中存在
    PARAM_SCHEMA_VALID("参数Schema符合性", false),    // 参数来源格式正则校验
    DEPENDENCY_VALID("依赖关系有效性", true),          // required：无循环依赖+引用任务存在
    EXECUTION_ORDER_VALID("执行顺序合理性", false),    // 拓扑排序可行性
    OUTPUT_FORMAT_VALID("输出格式正确性", false),       // 输出字段定义完整性
    IO_COMPATIBILITY("工具链输入输出兼容性", true);     // required：前置输出与后续输入格式兼容
    private final boolean required;
}
// 通过标准：7项中至少通过4项，且3项required必须全部通过
public boolean isAcceptable() {
    if (passedCount < 4) return false;
    for (ValidationMetric metric : ValidationMetric.values()) {
        if (metric.isRequired()) {
            MetricResult result = metricResults.get(metric);
            if (result == null || !result.isPassed()) return false;
        }
    }
    return true;
}
```

**TaskAutoFixer——3轮渐进式修复**：

```java
@Service
public class TaskAutoFixer {
    @Value("${optimus.fixer.max-rounds:3}")
    private int maxFixRounds = 3;

    public FixResult fix(List<SubTask> subtasks, ValidationResult validationResult) {
        for (int round = 1; round <= maxFixRounds; round++) {
            // 选择本轮修复目标（按优先级）
            ValidationMetric targetMetric = selectTargetMetric(failedMetrics, round);
            // 执行修复：AI优先，规则降级
            List<SubTask> fixedTasks = fixRound(currentTasks, targetMetric, currentValidation);
            // 重新验证
            currentValidation = taskValidator.validate(fixedTasks, null);
            if (currentValidation.isAcceptable()) break;  // 达标即退出
        }
    }
    // 修复优先级：STRUCTURE_COMPLETE → DEPENDENCY_VALID → PARAM_SCHEMA_VALID → ...
    private ValidationMetric selectTargetMetric(List<ValidationMetric> failedMetrics, int round) {
        List<ValidationMetric> priority = Arrays.asList(
                ValidationMetric.STRUCTURE_COMPLETE,    // 第1轮优先修复结构
                ValidationMetric.DEPENDENCY_VALID,      // 第2轮修复依赖
                ValidationMetric.PARAM_SCHEMA_VALID     // 第3轮修复参数
        );
        // ...
    }
}
```

修复流程严格遵循**"AI优先，规则降级"**：每轮先尝试`fixWithAI()`（TASK_AUTO_FIX决策类型），AI修复失败时降级到`fixWithRules()`确定性修复。修复成功率从40%提升至70%。

**FORMAT_COMPATIBILITY_CHECK**——LLM驱动的后置校验：每个任务匹配后验证能力的输入格式与任务实际输入是否兼容，替代硬编码的格式互斥组关键词匹配，支持长尾场景。

### 5.2.5 AI输出容错——永远不信任AI输出的格式

AI模型的输出格式不可信——即使Prompt严格要求JSON，模型仍可能输出变体字段名、缺失字段或损坏的JSON结构。系统在多个层面建立了容错机制：

**前端AI输出三层纵深容错**（10个frontend model类，共102处注解）：

```
Layer 1: 配置驱动容错（@JsonIgnoreProperties + @JsonAlias 102处注解）→ 99%场景零Token
Layer 2: LLM驱动字段补全（repairMetaFieldsWithLLM）→ 1%长尾拼写变体
Layer 3: 确定性兜底（null检测+默认值）→ 系统永远不因JSON解析失败崩溃
```

**游戏类型三层解析**：直接valueOf → 模糊contains → 默认ARCADE兜底。

**JSON结构修复**（`DecisionType.JSON_STRUCTURE_REPAIR`，v3.20.1）——标准解析失败时调用LLM理解语义修复，绝大多数场景走确定性路径（0 Token），仅结构损坏时才调用LLM。

> AI输出容错的核心原则：**确定性优先**——102处`@JsonAlias`注解覆盖99%变体，LLM仅处理无法预见的1%长尾。

### 5.3 确定性检测——"能用规则解决的不浪费LLM"

确定性检测是质量闭环的第一道防线——零Token消耗。这一模式在体感/手势/音乐/拍照中形成统一范式：

**体感游戏6项代码验证**（`MotionCodeBugDetectionService`）：TensorFlow引入/PoseDetection API/Detector实例/GestureMapping/DetectGesture/HandleGesture，至少4/6通过。

**手势游戏6项静态检查+健康分扣分制**（`FingerCodeBugDetectionService`）：

```java
int score = 100;
score -= result.getCriticalCount() * 30;  // STATIC_001: 缺MediaPipe CDN(-30)
score -= result.getHighCount() * 15;       // STATIC_003: 缺recognizeGesture(-15)
score -= result.getMediumCount() * 5;      // STATIC_005: 缺GESTURE_MAPPING(-5)
score -= result.getLowCount() * 2;
return Math.max(0, score);
```

**静态检查快速通道**：发现critical级Bug → **跳过LLM深度检测直接进入修复**（节省~3秒+Token费用）。

**音乐4步前置自检+规则引擎**（`MusicTheorySelfChecker`）：MIDI修正→乐器检查→色彩和弦注入→规则前置检测 → AI自检 → 7类Bug检测 → AI修复 → 再验证（最多3次循环）。**5维度100分制评估**（`MusicQualityEvaluator`）：旋律/和声/节奏/乐器/整体各20分。

**拍照专家5项解剖学验证**（`PoseDescriptionParser.validateKeypoints()`）：肩宽≥20px / 鼻子在肩上方 / 肩在髋上方 / 上臂30-120px / 分布范围xSpan≥50且ySpan≥150。验证失败降级到`PoseTemplateLibrary`的10个硬编码模板。

**拍照打卡点融合评分**（`CameraExpertService.searchPhotoSpots()`）：
```java
// 综合评分 = AI语义(70%) + 距离(30%)
double aiScore = Double.parseDouble(parts[0].trim());  // AI语义评分（0-10分）
double score = aiScore * 0.7 + (1000 - spot.getDistance()) / 100 * 0.3;
```
AI语义判断为主、距离量化兜底——即使AI评分偏差，距离因素也保证推荐结果合理性。

### 5.4 Bug检测→修复循环——跨域统一闭环

#### 5.4.1 音乐——确定性修复+用户意图豁免

**P4-8质量自动修复（applyQualityFixes）**——不重新生成而是精准局部修复：Electric Guitar(27-31)→Acoustic Guitar(25)、MIDI Program裁剪到0-127、超过4种乐器保留volume最高的4个。

**电子味检测+用户意图豁免**——规则引擎不死板的关键设计：

```java
boolean isElectronicStyle = userQuery != null &&
    (userQuery.contains("电子") || userQuery.contains("摇滚") || userQuery.contains("电吉他"));
if (!isElectronicStyle && guide.getInstrumentation() != null) {
    // Electric → Acoustic替换；Synth(80-127) → Piano(0)
}
```

当检测到用户要求"电子"/"摇滚"风格时，跳过电子味替换规则。这种"规则+意图感知"的组合比纯规则或纯AI判断更精准。

**P0旋律扩展**：AI音符总时值不足段落85%时，循环扩展原始旋律（每轮±2半音变奏，最多5轮）。

**L6旋律密度校验**：每段至少4个音符+时值覆盖率≥70%+三轮渐进式解析容错。

#### 5.4.2 前端代码Bug检测+修复

**代码证据**：`FrontendCodeValidationOrchestrator`（~107行）+ `FrontendCodeDetectionService` + `FrontendCodeFixService`

```
步骤1: FrontendCodeDetectionService.detectBugs() → LLM深度Bug检测
步骤2: 严重度过滤(warning/info不触发修复) → 精准修复决策
步骤3: FrontendCodeFixService.fixBugs() → LLM自动修复代码
步骤4: 验证修复结果 → 修复失败返回原始代码，不阻断主流程
```

### 5.5 跨模态质量闭环——P图视觉评估

**代码证据**：`ChatOrchestrationService.java`（`generateImageWithRetry` + `evaluateImage`）

P图模块的Harness独特之处：不是约束文本输入/输出，而是**在人类空间意图与AI图像生成之间架设工程化桥梁**。

**四场景自适应路由——确定性分发**：

```java
// 三个布尔信号的组合确定性路由到4种场景，AI不参与路由决策
if (hasPureSketch && hasPrompt && !hasOriginalImages) → 涂鸦生图(Wanx-Sketch-To-Image)
if (hasPureSketch && hasOriginalImages && hasPrompt)  → 局部重绘(Wanx-Image-Local-Repaint)
if (hasOriginalImages)                                → 图生图(Qwen-Image-Edit)
else                                                  → 文生图(Wan2.2-T2I-Plus)
```

**Qwen-VL-Max视觉评估+反馈驱动Prompt优化**：

```
generateOrEditImage()（Generator，万相扩散模型）→ 生成图像
      ↓
evaluateImage()（Evaluator，Qwen-VL-Max视觉模型）→ 四维评估
      ↓
score ≥ 8 → 返回 / score < 8 → optimizePrompt(注入suggestions) → 再次生成
最多2次尝试（int maxAttempts = 2）
```

**四维评估体系**：准确性（核心）+ 美观度 + 自然度 + 细节质量。评估建议在下一轮直接注入`optimizePrompt()`，形成基于结构化诊断的精准重试。

> P图的空间意图→Mask工程（BFS洪水填充、双画布分离）详见§六双层分离。

### 5.6 答案一致性交叉验证——作业辅导

**代码证据**：`ImageAnalysisServiceV2.java`（434行）

AI视觉推理模型（QvqMax）存在隐蔽问题：**推理过程得出的答案可能与最终输出不一致**。

```java
// validateAnswerConsistency()：
// Step 1: extractAnswerFromReasoning() → 6关键词反向搜索+选择题/填空题模式提取
// Step 2: normalizeAnswer() → 去序号/括号/标点/空格标准化
// Step 3: 交叉比较 → 一致返回 / 不一致信任推理过程（推理过程 > 总结答案）
```

这是Generator-Evaluator模式在教育领域的变体——将同一模型输出拆解为"推理过程"和"最终答案"两个维度交叉验证。

**智能任务跳过**：多题批改模式（`isMultipleProblems || isMarkingMode`）时跳过知识点可视化+Manim动画等耗时步骤，阶段3-4从~85秒→~1ms，响应提升60%+。

### 5.7 跨场景质量闭环总结

| 模式类型 | 跨域实践 | 核心特征 |
|---------|---------|---------|
| **确定性检测** | 体感6项/手势6项/音乐4步前置/拍照5项解剖学 | 零Token消耗，静态检查快速通道 |
| **健康分扣分制** | 手势-30/-15/-5/-2 + 音乐5×20分 + SelfTest 4×100分 | 量化+阈值驱动修复决策 |
| **Bug检测→修复** | 体感3次/手势两阶段/音乐7类/前端检测+修复 | 最多N轮，每轮携带上轮反馈 |
| **确定性修复+意图感知** | 音乐电子味+豁免/旋律扩展±2半音/MIDI裁剪 | 不重生成，精准局部修复 |
| **跨模态评估** | P图：万相扩散+Qwen-VL-Max视觉评估 | Generator/Evaluator跨模型架构 |
| **答案交叉验证** | 作业：推理过程 vs 最终答案 | 同一输出多维度自校验 |
| **异步流水线保障** | 方言：uniqueId关联+isSentenceEnd过滤+长度护栏 | 异步不阻塞+降级不崩溃 |

> 质量闭环保证了"输出正确"。但有些任务天然不适合AI端到端完成——下一章讨论**如何将AI擅长的认知与工程擅长的确定性执行分开**。

---

## 六、AI认知+确定性执行——双层分离的工程范式

> **核心原理**：大模型擅长认知（理解语义、做创意决策、分析图像），但不擅长像素级精确操作、音频渲染、几何变换等确定性任务。Harness Engineering的策略是**将每个任务拆分为"AI认知层+确定性执行层"**，让AI只做AI擅长的事，让工程代码负责确定性执行——两者通过结构化接口连接。

### 6.1 为什么不用端到端模型？

端到端AI模型（如文生视频、图像编辑模型）的诱惑在于"一步到位"，但在实际工程中存在三个致命缺陷：**精度不可控**（数学公式渲染近似、几何变换不精确）、**可复现性差**（相同输入每次输出不同）、**成本高昂**（大模型推理几十秒/次）。

AI Native系统在**10个场景**中实践了双层分离：

| 场景 | AI认知层 | 确定性执行层 | 结构化接口 | 接口复杂度 |
|------|---------|------------|-----------|-----------|
| Manim数学动画 | Qwen3-Max生成Python代码 | Manim引擎480p15fps渲染 | Python .py文件 | 低（单文件） |
| 试卷标注 | QwenVlOcr+QvqMax+Qwen3-Max | Python+OpenCV像素级绘制 | JSON{text,location,tag,color} | 中（4字段×N题） |
| 骨架图 | Qwen3-Max解析17个COCO关键点 | Java2D确定性渲染 | List\<PoseKeypoint\> | 中（17个关键点坐标） |
| 音乐渲染 | AI生成音乐理论JSON | FluidSynth MIDI 48kHz渲染 | 音乐理论JSON | 高（~500行JSON Schema） |
| 前端姿势匹配 | Qwen3-Max(后端认知) | TF.js MoveNet 12fps(前端) | 关键点坐标JSON | 中（17关键点+8角度） |
| 视频通话策略 | AI生成Python策略代码 | Python WebSocket+安全验证 | Python代码+白名单/黑名单 | 中（4必需元素） |
| APK打包 | AI生成全栈应用代码 | 8阶段自动化流水线 | HTML/Python文件 | 低（文件级接口） |
| P图Mask | 用户空间意图(涂抹) | BFS洪水填充+DualCanvas | Mask图像URL | 低（URL+参数） |
| 视频局部编辑 | 用户涂抹编辑区域 | DualCanvas+tracking跟踪 | mask_type:tracking参数 | 低（类型+扩展比例） |
| **浏览器自动化** | TaskDecomposer拆解+CapabilityMatcher匹配+AIParamTransform修正+CapabilityTypeNormalizer归一化 | Playwright Chromium确定性执行（FastAPI→Playwright API→真实浏览器） | HTTP POST JSON（10种标准化端点，统一`{success,data,session_id,error_code}`） | 中（10种端点×参数Schema） |

### 6.2 Manim数学动画——AI生成代码+本地确定性渲染

**代码证据**：`ManimAnimationServiceV2.java`（~500行）

直觉上生成"数学教学动画"应该用文生视频模型。但文生视频模型**数学公式渲染不精确、几何图形变换不可控、动画节奏无法编程**。系统的Harness方案：

```
AI认知层（Qwen3-Max）：                     确定性执行层（Manim引擎）：
  1. analyzeKnowledgeType()                  1. savePythonFile() → .py文件
     → 识别知识点类型                          2. extractSceneClassName() → 正则提取Scene类名
  2. buildManimPrompt()                      3. renderManimAnimation()
     → 7项约束的结构化Prompt                     → python3 -m manim -ql --format=mp4
  3. qwen3MaxService.generateText()            → 120秒超时保护
     → AI输出完整Python Manim代码             4. uploadToOss() + cleanupFiles()
```

**核心代码逻辑**（`ManimAnimationServiceV2.java`）——AI认知层+确定性执行层的完整流程：

```java
@Service
public class ManimAnimationServiceV2 {
    @Autowired private Qwen3MaxTextService qwen3MaxService;
    @Autowired private OssService ossService;

    // 完整流程：生成代码 → 渲染视频 → 上传OSS → 清理临时文件
    public String generateAndUploadManimVideo(String solutionProcess) {
        String pythonFilePath = null;
        String videoFilePath = null;
        try {
            // AI认知层：生成Manim Python代码
            pythonFilePath = generatePythonManimCode(solutionProcess);
            // 确定性执行层：Manim引擎渲染
            videoFilePath = renderManimAnimation(pythonFilePath);
            // 上传到OSS
            return uploadToOss(videoFilePath);
        } finally {
            cleanupFiles(pythonFilePath, videoFilePath);  // 双路径清理
        }
    }

    // AI认知层：6步生成Manim代码
    public String generatePythonManimCode(String solutionProcess) {
        // 1. AI分析知识点类型（几何/函数/代数/统计/数列/其他）
        String knowledgeType = analyzeKnowledgeType(solutionProcess);
        // 2. 根据类型构建特定动画要求
        String animationRequirements = buildManimRequirements(knowledgeType);
        // 3. 构建7项约束的Manim专用Prompt
        String prompt = buildManimPrompt(knowledgeType, animationRequirements, solutionProcess);
        // 4. AI生成代码
        String manimContent = qwen3MaxService.generateText(prompt);
        // 5. 清理代码（移除markdown标记，定位import/from/class起始位置）
        String cleaned = cleanPythonResponse(manimContent);
        // 6. 保存Python文件
        return savePythonFile(cleaned);
    }

    // 确定性执行层：Manim引擎渲染
    public String renderManimAnimation(String pythonFilePath) {
        // 1. 正则提取Scene类名：class (\w*Scene\w*)\(.*Scene.*\):
        String sceneClassName = extractSceneClassName(pythonFilePath);
        // 2. 构建渲染命令（低质量快速渲染 + MP4格式）
        String command = String.format(
                "python3 -m manim -ql --format=mp4 --media_dir=%s %s %s",
                outputDir, pythonFilePath, sceneClassName);
        // 3. ProcessBuilder执行，120秒超时保护
        Process process = new ProcessBuilder("sh", "-c", command).start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); return ""; }
        // 4. 输出路径：{media_dir}/videos/{fileName}/480p15/{sceneName}.mp4
        return outputDir.resolve("videos").resolve(fileName).resolve("480p15")
                .resolve(sceneClassName + ".mp4").toString();
    }
}
```

> **架构要点**：`cleanPythonResponse()`通过定位`import`/`from`/`class`三种Python代码标志位置，移除AI可能输出的markdown标记和解释文字。`extractSceneClassName()`使用正则`class (\w*Scene\w*)\(.*Scene.*\):`提取类名，确保Manim命令参数正确——这两个"桥接方法"是AI认知层和确定性执行层之间的关键连接器。

**7项Harness约束**：必须`from manim import *`、类名含Scene、白色背景、Text()而非Tex()、utf-8编码、禁止emoji、只返回纯代码。将AI自由度限制在"数学创意表达"窄带内，渲染精度由Manim引擎确定性保证。

| 维度 | 文生视频模型    | AI+Manim方案 |
|------|-----------|----------|
| 数学公式精度 | 视觉近似，常有错误 | LaTeX级精确渲染 |
| 几何变换 | 不可编程      | Transform/Rotate/Scale精确控制 |
| 可复现性 | 每次不同      | 相同代码100%一致 |
| 成本 | 高（大模型推理）  | 低（本地渲染） |

### 6.3 试卷标注——AI认知+OpenCV确定性绘制

**代码证据**：`ExamAnnotationService.java`（~452行）+ `exam_annotation.py`（381行）

```
AI认知层（多模型协同）：                      确定性执行层（Python+OpenCV）：
  QwenVlOcr → OCR识别试卷文字+坐标            cv2.imread() → 读取图像
  QvqMax → 视觉推理提取题目和答案              cv2.line() → 像素级绘制√/×
  Qwen3-Max → 答案比对判断对错                 cv2.imwrite() → 保存标注图像
      ↓ 输出JSON                              uploadToOss() → 上传到OSS
  {text, location[8坐标], tag:"√"/"×", color}
```

**Java→Python跨语言调用**：`ProcessBuilder`调用`python3 exam_annotation.py`，30秒超时保护，`finally`块双路径清理。AI擅长理解题目判断对错，但不擅长像素级精确操作；OpenCV擅长确定性绘图（对勾3点折线、叉号2条对角线），但不具备理解能力——通过结构化JSON接口连接。

### 6.4 骨架图——AI关键点解析+Java2D确定性渲染

**代码证据**：`PoseDescriptionParser.java`（313行）+ `PoseSkeletonGenerator.java`（283行）+ `PoseTemplateLibrary.java`（528行）

**方案演进**——旧方案（AI图像编辑）成功率~75%、消耗Semaphore(2)并发、延迟15-30s → 新方案（AI关键点+Java2D）成功率100%、零图像并发、延迟2-5s：

```java
// PoseDescriptionParser.parse() 核心流程：缓存→AI→降级
public List<PoseKeypoint> parse(String description) {
    List<PoseKeypoint> cached = parseCache.get(cacheKey);  // ConcurrentHashMap缓存
    if (cached != null) return new ArrayList<>(cached);
    List<PoseKeypoint> aiKeypoints = parseWithAI(description);  // AI大模型解析17个COCO关键点
    if (aiKeypoints != null && aiKeypoints.size() == 17) return aiKeypoints;
    return templateLibrary.matchTemplate(description).getKeypoints();  // 10个硬编码模板降级
}
```

AI的SYSTEM_PROMPT（80行）含人体解剖比例基准（肩宽110px/躯干125px/上臂75px）+5种姿势变换规则+严格输出格式。`PoseSkeletonGenerator`7步确定性渲染：createImage→createGraphics→configureGraphics→drawBackground→drawSkeleton(16条连接线)→drawKeypoints(17个实心圆)→dispose。

**核心代码逻辑**（`PoseSkeletonGenerator.java`）——7步确定性渲染流水线：

```java
@Component
public class PoseSkeletonGenerator {
    @Autowired private PoseSkeletonConfig config;
    @Autowired private PoseDescriptionParser parser;  // AI解析+降级模板
    @Autowired private ColorScheme colorScheme;

    // 从文字描述生成骨骼图：AI解析关键点 → 确定性渲染
    public BufferedImage generate(String description) {
        List<PoseKeypoint> keypoints = parser.parse(description);  // AI解析17个COCO关键点
        return generateFromKeypoints(keypoints);
    }

    // 7步确定性渲染（从17个COCO关键点到完整骨骼图）
    public BufferedImage generateFromKeypoints(List<PoseKeypoint> keypoints) {
        if (keypoints == null || keypoints.size() < 17) throw new IllegalArgumentException("需要17个关键点");
        // 1. 创建BufferedImage（TYPE_INT_ARGB支持透明度）
        BufferedImage image = new BufferedImage(config.getImageWidth(), config.getImageHeight(),
                BufferedImage.TYPE_INT_ARGB);
        // 2. 获取Graphics2D对象
        Graphics2D g2d = image.createGraphics();
        try {
            // 3. 配置渲染参数（抗锯齿+高质量渲染+双三次插值）
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // 4. 绘制背景
            g2d.setColor(colorScheme.getBackgroundColor());
            g2d.fillRect(0, 0, config.getImageWidth(), config.getImageHeight());
            // 5. 绘制骨骼连接线（16条标准COCO连接，圆形端点+连接）
            for (SkeletonConnection conn : SkeletonConnection.getStandardConnections()) {
                PoseKeypoint start = findKeypointByIndex(keypoints, conn.getStartIndex());
                PoseKeypoint end = findKeypointByIndex(keypoints, conn.getEndIndex());
                g2d.setColor(colorScheme.getColorForConnection(conn));
                g2d.drawLine(start.getX(), start.getY(), end.getX(), end.getY());
            }
            // 6. 绘制关键点（17个实心圆）
            for (PoseKeypoint kp : keypoints) {
                g2d.setColor(colorScheme.getColorForKeypoint(kp.getIndex()));
                g2d.fillOval(kp.getX() - radius, kp.getY() - radius, diameter, diameter);
            }
            return image;
        } finally {
            g2d.dispose();  // 7. 释放Graphics2D资源
        }
    }
}
```

> **架构要点**：`PoseDescriptionParser.parse()`内部实现了三级降级——缓存→AI大模型→10个硬编码模板（`PoseTemplateLibrary`）。AI认知层只需输出17个坐标点，确定性执行层用Java2D保证每次渲染结果100%一致，成功率从旧方案的~75%提升到100%，延迟从15-30s降到2-5s。

### 6.5 音乐——AI生成理论JSON+FluidSynth渲染

AI不是直接输出音频，而是**生成Python音乐理论代码**（旋律/和声/编配），由FluidSynth引擎渲染为48kHz专业级音频。多轨分离渲染→专业混缩→后处理滤镜链（EQ+loudnorm EBU R128 -14 LUFS+alimiter）。

**中国民族乐器MIDI映射**（18种）：古筝→竖琴(46) / 二胡→小提琴(40) / 琵琶→古典吉他(24) / 笛子→长笛(73) / 唢呐→双簧管(68)。AI负责创意决策（音乐理论），工程代码负责执行（MIDI渲染），两者通过结构化JSON接口连接。

### 6.6 前端MoveNet——重认知后端化、轻感知前端化

**代码证据**：`camera-expert.html`（1900行）

直觉上实时姿势指导需要后端AI持续分析视频流（往返延迟~500ms+），系统的Harness方案——**将实时推理完全下沉到前端**：

```
TF.js MoveNet（THUNDER→LIGHTNING降级，~12fps → ~30fps）
  → EMA指数平滑（factor=0.65），消除高频抖动
  → 双指标评分：距离评分55% + 角度评分45%（17关键点+8关节角度）
  → 确认机制：连续15帧得分≥75 → 姿势达标
  → Web Speech API TTS语音纠正（2秒throttle）
```

后端AI（Qwen3-Max）负责高级认知——生成关键点坐标一次即可；前端轻量模型（TF.js MoveNet 3.3MB）以12fps持续运行不依赖网络。

### 6.7 视频通话——可编程策略代码+安全验证

**代码证据**：`VideoCallService.java` + `PythonWebSocketClient.java`

AI动态生成Python问询策略代码（含QUESTIONS/calculate_interval/should_send_now/get_next_question），实现"可编程通话"能力。

**代码安全验证三道防线**：
```java
// 白名单：REQUIRED_ELEMENTS = {"QUESTIONS", "calculate_interval", ...}
// 黑名单：FORBIDDEN_PATTERNS = {"eval(", "exec(", "__import__", "os.system", ...}
// 危险导入：FORBIDDEN_IMPORTS = {"os", "sys", "subprocess", "socket", "requests"}
```

上述Java侧三道防线保障AI生成的Python策略代码安全。而**策略代码的实际运行环境**——Python侧的`StateMonitor`状态机和`QuestionScheduler`智能调度器——同样是关键的Harness工程组件：

**Python StateMonitor——8状态机+9项检查+自适应间隔**（`state_monitor.py` 656行）：

```python
class SystemState(Enum):
    """系统8状态枚举"""
    IDLE = "空闲"                    # 唯一可发送问题的状态
    INITIALIZING = "初始化中"         # 系统启动状态
    USER_SPEAKING = "用户说话中"      # 用户正在说话
    AI_RESPONDING = "AI回复中"        # AI正在生成回复
    TTS_GENERATING = "TTS生成中"      # 音频生成中
    ERROR = "错误恢复中"              # 错误保护状态
    RECONNECTING = "重连中"           # 网络重连状态
    CLOSING = "关闭中"                # 会话结束状态

class StateMonitor:
    """8状态机 + 最小间隔保护"""
    def __init__(self, session_id=None, strategy_config=None):
        self.current_state = SystemState.INITIALIZING
        self.state_lock = threading.RLock()        # 可重入锁
        self.is_connected = False
        self.is_closing = False
        self.min_question_interval = 3.0           # 最小间隔3秒（可配置）
        self.error_cooldown = 10.0                 # 错误冷却时间
        # 超时保护：每种状态独立超时阈值
        self.state_timeout = {
            SystemState.INITIALIZING: 30,          # 初始化超时30秒
            SystemState.USER_SPEAKING: 10,         # 用户说话超时10秒
            SystemState.AI_RESPONDING: 30,         # AI响应超时30秒
            SystemState.TTS_GENERATING: 15,        # TTS生成超时15秒
            SystemState.RECONNECTING: 60,          # 重连超时60秒
        }

    def can_send_question(self) -> bool:
        """完整的9项检查，判断是否可以发送问题"""
        with self.state_lock:
            if self.current_state != SystemState.IDLE: return False    # 检查1：状态
            if not self.is_connected: return False                      # 检查2：连接
            if self.is_closing: return False                            # 检查3：关闭
            if self.error_count > 0:                                    # 检查4：错误冷却
                if time.time() - self.last_error_time < self.error_cooldown:
                    return False
                self.error_count = 0                                    # 冷却完成重置
            # 检查5-8：隐含在状态检查中（USER_SPEAKING/AI_RESPONDING/TTS/RECONNECTING均非IDLE）
            if self.last_question_time > 0:                             # 检查9：最小间隔
                if time.time() - self.last_question_time < self.min_question_interval:
                    return False
            return True

    def on_websocket_message(self, message: Dict[str, Any]):
        """WebSocket消息驱动状态转换"""
        msg_type = message.get('type', '')
        with self.state_lock:
            if msg_type == "session_initialized":              # INITIALIZING → IDLE
                self._change_state(SystemState.IDLE, "会话初始化完成")
                self.is_connected = True
            elif msg_type == "input_audio_buffer.speech_started":  # IDLE → USER_SPEAKING
                self._change_state(SystemState.USER_SPEAKING, "用户开始说话")
            elif msg_type == "response.text.delta":                # IDLE → AI_RESPONDING
                self._change_state(SystemState.AI_RESPONDING, "AI开始生成回复")
            elif msg_type == "response.audio.delta":               # AI_RESPONDING → TTS
                self._change_state(SystemState.TTS_GENERATING, "TTS开始生成音频")
            elif msg_type == "response.audio.done":                # TTS → IDLE（回到可发送状态）
                self._change_state(SystemState.IDLE, "TTS生成完成")
            elif msg_type == "error":                              # → ERROR
                self._handle_error(message.get('error', {}).get('message', ''))

    def _handle_error(self, error_message: str):
        """错误分类+差异化冷却"""
        self.error_count += 1
        error_type = self._classify_error(error_message)
        if error_type == ErrorType.NETWORK:     self.error_cooldown = 5       # 网络：5秒
        elif error_type == ErrorType.RATE_LIMIT: self.error_cooldown = 30     # 限流：30秒
        elif error_type == ErrorType.SERVER:                                   # 服务器：指数退避
            self.error_cooldown = min(60, 10 * (2 ** self.error_count))
        else:                                    self.error_cooldown = 10      # 未知：10秒

    def _auto_adjust_interval(self):
        """自适应间隔调整——根据用户活跃度"""
        recent_count = self._get_recent_speaking_count()  # 60秒窗口
        if recent_count > 5:       self.min_question_interval = 10.0  # 活跃→延长
        elif recent_count >= 2:    self.min_question_interval = 5.0   # 中等→中间值
        elif recent_count == 0:    self.min_question_interval = 3.0   # 沉默→恢复默认
```

> **架构要点**：Java侧`VideoCallService`+`PythonWebSocketClient`负责AI生成策略代码的安全验证和Python进程管理（上层Harness），Python侧`StateMonitor`负责策略代码的运行时状态守护（下层Harness）。两层协作形成**Java安全验证→Python状态守护**的全栈防护链：Java确保代码安全，Python确保运行时安全。`can_send_question()`的9项检查防止了通话中的各种并发冲突——用户说话时不打断、AI回复时不抢答、错误后自动冷却、间隔自适应调整。

**错误处理四级分类与冷却策略**：

| 错误类型 | 关键词匹配 | 冷却时间 | 工程考量 |
|---------|-----------|---------|---------|
| `NETWORK` | network/connection/timeout | 5秒 | 网络抖动通常短暂恢复 |
| `RATE_LIMIT` | rate limit/throttle | 30秒 | 尊重API限流退避 |
| `SERVER` | server/500/502/503 | 指数退避(max 60秒) | 服务器过载需更长恢复 |
| `UNKNOWN` | 其他 | 10秒 | 保守估计 |

**超时保护——daemon线程自动恢复**：独立`TimeoutChecker`线程每秒巡检，各状态超时后自动恢复到IDLE（安全状态）或ERROR（需干预状态）。这与Java侧`HeartbeatDaemon`的设计理念一致——**后台守护进程自动检测并恢复异常状态**。

**Python StrategyLoader——动态策略加载+三级降级兜底**（`strategy_loader.py` 148行）：

Java侧三道防线验证通过的AI生成策略代码，最终由Python侧`StrategyLoader`动态加载执行。加载过程本身也设计了完整的降级链：

```python
class StrategyLoader:
    """策略加载器——动态导入AI生成的策略模块"""
    def __init__(self):
        self.current_strategy = None
        self.strategy_module_name = "strategies.generated_strategy"   # AI生成
        self.default_module_name = "strategies.default_strategy"      # 人工兜底

    def load_strategy(self) -> Optional[Dict]:
        """加载策略——三级降级：AI生成策略 → 默认策略 → None"""
        try:
            strategy_module = importlib.import_module(self.strategy_module_name)
            # 必需属性校验——4个函数/常量缺一不可
            required_attrs = ['QUESTIONS', 'calculate_interval', 'should_send_now', 'get_next_question']
            for attr in required_attrs:
                if not hasattr(strategy_module, attr):
                    raise AttributeError(f"策略模块缺少必需属性: {attr}")
            strategy = {
                'questions': strategy_module.QUESTIONS,
                'calculate_interval': strategy_module.calculate_interval,
                'should_send_now': strategy_module.should_send_now,
                'get_next_question': strategy_module.get_next_question,
                'metadata': getattr(strategy_module, 'STRATEGY_METADATA', {}),
            }
            self.current_strategy = strategy
            return strategy
        except ModuleNotFoundError:                  # 策略模块不存在 → 降级
            return self._load_default_strategy()
        except AttributeError:                        # 必需属性缺失 → 降级
            return self._load_default_strategy()
        except Exception:                             # 其他异常 → 降级
            return self._load_default_strategy()

    def reload_strategy(self) -> Optional[Dict]:
        """热加载——清除sys.modules缓存后重新导入"""
        if self.strategy_module_name in sys.modules:
            del sys.modules[self.strategy_module_name]  # 关键：清除旧模块缓存
        return self.load_strategy()
```

**Python QuestionScheduler——3模式智能调度+类型安全防御**（`question_scheduler.py` 773行）：

```python
class QuestionScheduler:
    """问题调度器——策略/变体/轮换三模式 + 类型安全防御"""
    def __init__(self, state_monitor: StateMonitor, questions=None,
                 check_interval=1.0, enable_variants=False, use_strategy=True):
        self.state_monitor = state_monitor
        self.strategy_loader = StrategyLoader() if use_strategy else None
        self.sent_question_indices = []   # 已发送索引（策略上下文）

        # ========== 策略加载（空问题列表触发） ==========
        if questions is None or len(questions) == 0:
            if self.strategy_loader:
                self.current_strategy = self.strategy_loader.load_strategy()
                if self.current_strategy:
                    self.questions = self.current_strategy['questions']
                    # 🔥 保护属性——防止策略字典覆盖调度器内部状态
                    protected = {'state_monitor', 'strategy_loader', 'scheduler_thread'}
                    for key in protected:
                        if key in self.current_strategy:
                            logger.warning(f"策略字典包含保护属性 '{key}'，已忽略")
                else:
                    self.questions = ["你最近怎么样？", ...]  # 兜底默认
        # 🔥 类型安全——构造时强制校验，快速失败
        if not isinstance(self.state_monitor, StateMonitor):
            raise TypeError(f"期望 StateMonitor 对象，实际得到 {type(self.state_monitor).__name__}")

    def _get_next_question(self) -> str:
        """三模式问题选择：策略优先 → 变体模式 → 顺序轮换"""
        # 模式1：策略模式（AI生成策略的get_next_question函数）
        if self.current_strategy:
            try:
                context = self._build_strategy_context()
                return self.current_strategy['get_next_question'](context)
            except Exception:
                pass  # 策略执行失败 → 降级到模式2/3
        # 模式2/3：变体生成 或 顺序轮换
        base_question = self.questions[self.question_index]
        self.question_index = (self.question_index + 1) % len(self.questions)
        if self.enable_variants:
            return random.choice(self._get_or_generate_variants(base_question))
        return base_question

    def _build_strategy_context(self) -> Dict:
        """构建策略函数上下文——融合调度器+状态机+时间信息"""
        return {
            'sent_questions': self.sent_question_indices.copy(),
            'conversation_length': self.total_sent,
            'time_since_last': time.time() - self.state_monitor.last_question_time,
            'last_response_time': time.time() - self.state_monitor.last_ai_response_time,
            'system_state': self.state_monitor.current_state.value,
            'user_speaking': self.state_monitor.current_state.value == 'USER_SPEAKING',
            'ai_responding': self.state_monitor.current_state.value in ['AI_RESPONDING', 'TTS_GENERATING'],
        }

    def _scheduler_loop(self):
        """调度器主循环——daemon线程 + StateMonitor守门"""
        while self.scheduler_running:
            time.sleep(self.check_interval)
            # 🔥 运行时类型防御——防止state_monitor被意外替换
            if not isinstance(self.state_monitor, StateMonitor):
                self.scheduler_running = False; return
            if self.state_monitor.can_send_question():    # StateMonitor 9项检查
                question = self._get_next_question()      # 三模式选择
                self.send_question_callback(question)     # 回调发送
                self.state_monitor.mark_question_sent()   # 标记已发送
```

> **全栈Harness链路**：`StrategyLoader`+`QuestionScheduler`与Java侧三道防线形成完整的**代码安全→动态加载→运行时守护**闭环。Java的`VideoCallService`验证AI生成的Python策略代码结构安全性（正则过滤危险import/exec、AST解析验证函数签名、沙箱试运行），验证通过后写入`generated_strategy.py`文件。Python的`StrategyLoader`通过`importlib.import_module`动态加载该文件，并校验4个必需属性（QUESTIONS/calculate_interval/should_send_now/get_next_question）——缺失任一属性触发降级到`default_strategy`。`QuestionScheduler`在使用策略时还有两层额外防护：**保护属性机制**防止策略字典意外覆盖`state_monitor`等关键内部状态；**构造时+运行时双重类型检查**确保`state_monitor`始终是`StateMonitor`实例。三模式降级（策略→变体→轮换）确保即使AI生成的策略函数运行时抛异常，调度器仍能继续工作。整条链路中每个环节都有独立的失败兜底，体现了Harness Engineering的核心理念——**不信任任何单一环节，每层都有降级方案**。

### 6.8 APK打包——8阶段流水线+Python嵌入Android

**代码证据**：`AndroidPackageService.java`（~591行）+ `FlaskEmbedService.java`（~224行）

```
阶段1: 初始化环境(5%) → 阶段2: 复制Capacitor模板(15%) → 阶段3: 复制HTML应用(25%)
→ 阶段4: 配置应用信息(35%) → 阶段5: 嵌入Flask后端(45%,仅全栈)
→ 阶段6: Gradle构建(60%) → 阶段7: 准备下载(85%) → 阶段8: 完成(100%)
```

**Python嵌入Android——Chaquopy方案**：Flask应用→assets/python/ + startup.py(threading.Thread) + build.gradle(Chaquopy+pip) + HTML注入API_BASE_URL=`http://127.0.0.1:5000`。

### 6.9 空间意图转译——P图BFS Mask+视频DualCanvas

**代码证据**：`MaskImageProcessor.java` + `DualCanvas.js`（770行）

#### P图——前端双画布+后端BFS洪水填充

**前端双画布分离**——在数据源头解决信号分离：主画布canvas(原图+叠加)、手绘画布sketchCanvas(纯笔触/透明背景)、canvasPosition坐标信息，三份结构化数据同时提交。

**P图前端核心代码逻辑**（`multimodal-input.html` 3911行）——双画布同步绘制+撤销/重做+空白检测+三份数据提交：

```javascript
// ==================== 全局状态管理 ====================
const state = {
    canvas: null,        // 显示画布（合成手绘+图片+文字）
    ctx: null,
    sketchCanvas: null,  // 独立的手绘画布（仅手绘笔触，透明背景，不可见）
    sketchCtx: null,
    isDrawing: false,
    currentTool: 'pen',  // pen | eraser
    brushSize: 3,
    history: [],         // 撤销历史
    historyStep: -1,
    images: [], texts: [], audios: [], videos: [],  // 多模态元素
    canvasScreenshot: null,   // 画板截图信息 {objectKey, downloadUrl}
    canvasPureSketch: null,   // 纯手绘图信息 {objectKey, downloadUrl}
};

// ==================== 画布初始化——创建隐形独立手绘层 ====================
function initCanvas() {
    state.canvas = document.getElementById('mainCanvas');
    state.ctx = state.canvas.getContext('2d');
    // 创建独立手绘画布（与显示画布同尺寸，不挂载到DOM）
    const sketchCanvas = document.createElement('canvas');
    sketchCanvas.width = state.canvas.width;
    sketchCanvas.height = state.canvas.height;
    state.sketchCanvas = sketchCanvas;
    state.sketchCtx = sketchCanvas.getContext('2d');
}

// ==================== 关键：双画布同步绘制 ====================
function draw(e) {
    if (!state.isDrawing) return;
    const pos = getPosition(e);
    // 1. 显示画布绘制（用户看到的合成效果）
    state.ctx.beginPath();
    state.ctx.moveTo(state.lastX, state.lastY);
    state.ctx.lineTo(pos.x, pos.y);
    state.ctx.strokeStyle = state.currentTool === 'pen' ? state.currentColor : '#FFFFFF';
    state.ctx.lineWidth = state.currentTool === 'eraser' ? state.brushSize * 3 : state.brushSize;
    state.ctx.stroke();
    // 2. 手绘画布同步绘制（仅记录纯笔触，不含图片/文字等元素）
    state.sketchCtx.beginPath();
    state.sketchCtx.moveTo(state.lastX, state.lastY);
    state.sketchCtx.lineTo(pos.x, pos.y);
    state.sketchCtx.strokeStyle = state.currentTool === 'pen' ? state.currentColor : '#FFFFFF';
    state.sketchCtx.lineWidth = state.currentTool === 'eraser' ? state.brushSize * 3 : state.brushSize;
    state.sketchCtx.stroke();
    state.lastX = pos.x;  state.lastY = pos.y;
}

// ==================== 撤销/重做——保存5份状态数据 ====================
function saveState() {
    state.history.push({
        canvasData: state.canvas.toDataURL(),         // 显示画布快照
        sketchData: state.sketchCanvas.toDataURL(),   // 手绘画布快照
        nextY: state.nextY,                           // 元素堆叠位置
        images: JSON.parse(JSON.stringify(state.images)),  // 深拷贝
        texts: JSON.parse(JSON.stringify(state.texts))     // 深拷贝
    });
}

// ==================== 空白检测——采样优化避免全像素遍历 ====================
function isCanvasBlank(canvas) {
    const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
    for (let i = 0; i < data.length; i += 40) {  // 40=10像素×4(RGBA)，每隔10像素采样
        const a = data[i + 3];
        if (a > 0 && !(data[i]===255 && data[i+1]===255 && data[i+2]===255)) {
            return false;  // 有非白色非透明像素 = 有实际内容
        }
    }
    return true;  // 全透明或全白 = 空白
}

// ==================== 核心提交：生成三份结构化数据 ====================
async function saveCanvas() {
    // 第1份：合成截图（白色背景 + 所有元素 + 手绘顶层）
    const tempCanvas = document.createElement('canvas');
    tempCtx.fillStyle = '#FFFFFF';
    tempCtx.fillRect(0, 0, fullWidth, fullHeight);
    await redrawFullCanvas(tempCtx, fullWidth, fullHeight);  // 绘制图片/文字/音频元素
    tempCtx.drawImage(state.sketchCanvas, 0, 0);             // 手绘图层叠加最顶层
    state.canvasScreenshot = await uploadToOSS(tempCanvas);

    // 第2份：纯手绘图（仅笔触，空白时跳过——避免后端误判）
    if (!isCanvasBlank(state.sketchCanvas)) {
        const pureSketchCanvas = document.createElement('canvas');
        pureSketchCtx.fillStyle = '#FFFFFF';
        pureSketchCtx.fillRect(0, 0, w, h);
        pureSketchCtx.drawImage(state.sketchCanvas, 0, 0);   // 仅手绘内容
        state.canvasPureSketch = await uploadToOSS(pureSketchCanvas);
    } else {
        state.canvasPureSketch = null;  // 无手绘→跳过上传
    }
    // 第3份：canvasPosition坐标信息（由前端状态管理提供）
}
```

> **架构要点**：P图前端的双画布设计与视频编辑的`DualCanvas.js`是**两套独立实现**，解决不同场景的信号分离问题。P图的`sketchCanvas`不挂载到DOM（用户不可见），与显示画布通过`draw()`函数同步绘制——每一笔都同时写入两个画布。提交时生成三份结构化数据：合成截图（用于P图方案A全图编辑）、纯手绘图（用于后端`MaskImageProcessor`的BFS闭合区域检测）、坐标信息（用于方案B局部裁剪）。`isCanvasBlank()`的10像素采样检测是关键防护——如果用户只添加了图片/文字但没有手绘涂抹，纯手绘图为空白，此时跳过上传避免后端将空白图误判为"全区域编辑"。橡皮擦使用3倍笔刷大小的白色覆盖，同时作用于两个画布确保擦除一致性。

**后端Mask处理流水线**（`MaskImageProcessor`）：

```
Step 1-2: 下载+格式验证
Step 3: convertAndFillMask()
  3a. 像素遍历：alpha≥128 && 非近白色(RGB>200) → 灰色(128)
  3b. BFS洪水填充：从四条边出发标记连通白色为"外部"
  3c. 未被BFS标记的白色 = 闭合"内部" → 填充灰色(128)
Step 4: API格式转换 — 灰色→白色(255)=编辑区 / 其他→黑色(0)=保留区
Step 5: resizeIfNeeded() — 512-4096px，NEAREST_NEIGHBOR插值
Step 6: 上传标准Mask到OSS
```

BFS洪水填充解决了关键的意图推理：用户画了一个"圈"，圈内区域虽无笔触覆盖，但意图是编辑圈内整个区域。用**确定性图论算法**推理空间意图。

**核心代码逻辑**（`MaskImageProcessor.java`）——BFS洪水填充的完整算法：

```java
@Component
public class MaskImageProcessor {
    private static final int WHITE_THRESHOLD = 200;  // RGB>200视为近白色（背景）
    private static final int ALPHA_THRESHOLD = 128;   // alpha>=128视为有效笔触
    private static final int GRAY_COLOR = (128<<16)|(128<<8)|128;  // 中间态标记
    private static final int WHITE_COLOR = (255<<16)|(255<<8)|255;
    private static final int BLACK_COLOR = 0;

    // 核心处理流水线：下载 → 颜色转换 → BFS填充 → 格式转换 → 缩放 → 上传
    private BufferedImage convertAndFillMask(BufferedImage original) {
        int width = original.getWidth(), height = original.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Step 1: 像素遍历——有色笔触→灰色标记，白色/透明→白色
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = original.getRGB(x, y);
                int alpha = (rgb >> 24) & 0xFF;
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                // 有效笔触：不透明(alpha>=128) 且 非近白色
                boolean isStroke = (alpha >= ALPHA_THRESHOLD)
                        && !(r > WHITE_THRESHOLD && g > WHITE_THRESHOLD && b > WHITE_THRESHOLD);
                result.setRGB(x, y, isStroke ? GRAY_COLOR : WHITE_COLOR);
            }
        }

        // Step 2: BFS洪水填充——从四条边出发标记外部白色区域
        boolean[][] visited = new boolean[height][width];
        for (int x = 0; x < width; x++) {
            floodFillFromEdge(result, visited, x, 0);        // 上边
            floodFillFromEdge(result, visited, x, height-1); // 下边
        }
        for (int y = 0; y < height; y++) {
            floodFillFromEdge(result, visited, 0, y);        // 左边
            floodFillFromEdge(result, visited, width-1, y);  // 右边
        }

        // Step 3: 未被BFS标记的白色 = 闭合"内部" → 填充灰色
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!visited[y][x] && isWhite(result.getRGB(x, y))) {
                    result.setRGB(x, y, GRAY_COLOR);  // 闭合区域内部 → 编辑区
                }
            }
        }

        // Step 4: 转换为API标准格式（灰色→白色=编辑区，其他→黑色=保留区）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result.setRGB(x, y, isGray(result.getRGB(x, y)) ? WHITE_COLOR : BLACK_COLOR);
            }
        }
        return result;
    }

    // BFS从边缘洪水填充——只填充白色像素，遇到灰色（笔触）停止
    private void floodFillFromEdge(BufferedImage image, boolean[][] visited, int startX, int startY) {
        if (visited[startY][startX] || !isWhite(image.getRGB(startX, startY))) return;
        Queue<Point> queue = new LinkedList<>();
        queue.offer(new Point(startX, startY));
        visited[startY][startX] = true;
        int[][] directions = {{0,1}, {1,0}, {0,-1}, {-1,0}};  // 四连通
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            for (int[] dir : directions) {
                int nx = p.x + dir[0], ny = p.y + dir[1];
                if (nx >= 0 && nx < image.getWidth() && ny >= 0 && ny < image.getHeight()
                        && !visited[ny][nx] && isWhite(image.getRGB(nx, ny))) {
                    visited[ny][nx] = true;
                    queue.offer(new Point(nx, ny));
                }
            }
        }
    }
}
```

> **架构要点**：BFS洪水填充的关键在于"反向标记"——不是找闭合区域，而是从四条边向内标记所有与边缘连通的白色像素为"外部"，**未被标记的白色即为闭合内部**。这是确定性图论算法在AI系统中的典型应用——用户画了一个"圈"的意图，通过O(W×H)复杂度的BFS精确推理，完全不需要LLM参与。

**Mask处理常量护栏**：

| 常量 | 值 | 工程作用 |
|------|---|---------|
| `BLACK_THRESHOLD` | 50 | RGB均<50判定为黑色 |
| `WHITE_THRESHOLD` | 200 | RGB均>200判定为近白色 |
| `ALPHA_THRESHOLD` | 128 | alpha≥128视为有效笔触 |
| `GRAY_COLOR` | 128 | 中间态标记色 |

**双轨策略+三级降级**：方案B(单图+有坐标→原图高清+裁剪Mask) → 方案A(画板截图+全图Mask) → 文本指令编辑兜底。Mask的Harness价值在于**物理性约束**——不是在Prompt中"建议"AI只修改某区域，而是通过黑白像素**强制限定**。

#### 视频——DualCanvas双层画布+tracking时空跟踪

**DualCanvas双层架构**：底层mainCanvas(视频帧显示)+顶层maskCanvas(Mask涂抹绘制)，两层完全独立。`generateMaskWithVideoSize()`确保Mask与视频像素级精确对应。

**核心前端代码逻辑**（`DualCanvas.js` 770行）——双画布初始化+鼠标/触摸双模式+视频播放联动+像素级Mask生成：

```javascript
// ==================== 双画布初始化 ====================
let mainCanvas, mainCtx;     // 底层：视频帧显示
let maskCanvas, maskCtx;     // 顶层：Mask涂抹绘制
let maskHistory = [];        // 撤销历史
const MAX_HISTORY_SIZE = 20; // 最多保存20步撤销
const MAX_RESIZE_RETRY = 3;  // 容器隐藏时resize重试上限

function initDualCanvas(mainCanvasId, maskCanvasId) {
    mainCanvas = document.getElementById(mainCanvasId);
    maskCanvas = document.getElementById(maskCanvasId);
    mainCtx = mainCanvas.getContext('2d');
    maskCtx = maskCanvas.getContext('2d');
    resizeCanvas();
    // 鼠标事件
    maskCanvas.addEventListener('mousedown', startDrawing);
    maskCanvas.addEventListener('mousemove', draw);
    maskCanvas.addEventListener('mouseup', stopDrawing);
    // 触摸事件（移动端支持）
    maskCanvas.addEventListener('touchstart', handleTouchStart);
    maskCanvas.addEventListener('touchmove', handleTouchMove);
    maskCanvas.addEventListener('touchend', stopDrawing);
}

// ==================== 视频播放联动 ====================
// play事件 → 禁用绘制，pause事件 → 启用绘制
videoEventListeners.play = () => {
    maskCanvas.style.pointerEvents = 'none';    // 视频播放时禁止涂抹
    drawVideoFrameLoop();                        // 持续绘制视频帧
};
videoEventListeners.pause = () => {
    maskCanvas.style.pointerEvents = 'auto';    // 暂停后允许涂抹
};

// ==================== 像素级Mask生成（与视频尺寸精确匹配）====================
function generateMaskWithVideoSize(videoWidth, videoHeight) {
    const tempCanvas = document.createElement('canvas');
    tempCanvas.width = videoWidth;
    tempCanvas.height = videoHeight;
    const scaleX = videoWidth / maskCanvas.width;
    const scaleY = videoHeight / maskCanvas.height;
    const originalData = maskCtx.getImageData(0, 0, maskCanvas.width, maskCanvas.height).data;
    const targetImageData = tempCtx.getImageData(0, 0, videoWidth, videoHeight);
    const targetData = targetImageData.data;
    // 逐像素遍历：alpha>0的绘制内容→白色(255)=编辑区，否则→黑色(0)=保留区
    for (let y = 0; y < videoHeight; y++) {
        for (let x = 0; x < videoWidth; x++) {
            const srcX = Math.floor(x / scaleX);
            const srcY = Math.floor(y / scaleY);
            const srcIndex = (srcY * maskCanvas.width + srcX) * 4;
            if (originalData[srcIndex + 3] > 0) {        // alpha > 0 = 有笔触
                targetData[(y * videoWidth + x) * 4] = 255;      // R
                targetData[(y * videoWidth + x) * 4 + 1] = 255;  // G
                targetData[(y * videoWidth + x) * 4 + 2] = 255;  // B
                targetData[(y * videoWidth + x) * 4 + 3] = 255;  // A
            }
        }
    }
    return tempCanvas.toDataURL('image/png');
}

// ==================== 生成+上传完整流程 ====================
async function generateAndUploadMask() {
    if (!validateMaskCanvas()) throw new Error('请先绘制Mask');
    let dataURL;
    // 局部编辑模式：获取视频尺寸，用generateMaskWithVideoSize确保精确匹配
    const editState = getEditPageState();
    if (editState.videoWidth > 0 && editState.videoHeight > 0) {
        dataURL = generateMaskWithVideoSize(editState.videoWidth, editState.videoHeight);
    } else {
        dataURL = generateMaskDataURL();  // 默认画布尺寸
    }
    const file = await dataURLToFile(dataURL, `mask_${Date.now()}.png`);
    return await uploadFileToOSS(file);   // 上传到OSS，返回downloadUrl
}
```

> **架构要点**：`DualCanvas.js`与后端`MaskImageProcessor.java`形成前后端Mask处理的完整链路——前端`generateMaskWithVideoSize()`通过逐像素缩放将画布坐标系Mask映射为视频原始分辨率Mask，后端BFS洪水填充进一步处理闭合区域。前端的`pointerEvents`联动确保视频播放时不会误触发绘制，`MAX_RESIZE_RETRY=3`的重试机制应对容器DOM尚未渲染完成的边缘场景。`validateMaskCanvas()`通过遍历alpha通道验证是否有实际笔触——无笔触时阻止上传，避免API无效调用。

`mask_type:tracking`——第一帧Mask自动跟踪全视频，`expand_ratio:0.05`(5%扩展覆盖边缘过渡)。7场景Prompt增强模板（text_to_video/image_to_video/video_repaint/local_edit/video_expansion等）。

### 6.10 跨域双层分离总结

| 场景 | 端到端缺陷 | Harness双层分离优势 | 确定性保障 |
|------|----------|-------------------|-----------|
| **Manim动画** | 公式不精确 | LaTeX级精确+100%可复现 | Manim 480p15fps |
| **试卷标注** | 标记位置随机 | 像素级精确√/× | OpenCV坐标计算 |
| **骨架图** | 成功率~75% | 成功率100%，延迟2-5s | Java2D 7步渲染 |
| **音乐渲染** | 无法输出音频 | 48kHz专业级 | FluidSynth MIDI |
| **姿势匹配** | 延迟~500ms+ | 前端12fps零网络 | TF.js MoveNet |
| **视频通话** | 策略不可编程 | 可编程+安全三防线 | Python沙箱 |
| **APK打包** | 无法完成 | 8阶段自动化 | Gradle确定性构建 |
| **P图Mask** | 猜测编辑区域 | 精确涂抹+BFS填充 | 像素级Mask |
| **视频编辑** | 逐帧标注不可行 | tracking自动跟踪 | 第一帧Mask扩展 |
| **浏览器自动化** | AI直接控制浏览器：指令不标准、选择器幻觉、session混乱 | AI只输出结构化意图，Playwright按标准API确定性执行 | 10种标准化HTTP端点+反检测预设+Cookie自动注入 |

**统一工程哲学**：AI负责**认知决策**，工程代码负责**确定性执行**，两者通过**结构化接口**连接。每层可独立升级和替换。

> 双层分离让每个模型做自己擅长的事。推而广之：**不同专长的模型是否可以协同工作？**

---

## 七、专模型做专事——多模型协作工程

> **核心原理**：不用一个通用大模型做所有事，而是让多个专长不同的模型各司其职，通过工程手段将它们可靠地串联或并行协作。核心策略："**用最小的模型完成够用的任务**"——在每个环节选择最合适（而非最强大）的模型，通过工程手段保障协作可靠性。

### 7.1 为什么不用一个大模型做所有事？

1. **成本浪费**——方言翻译是低复杂度文本转换，Qwen-Flash成本仅为Qwen-Max的1/10
2. **延迟不可接受**——端到端语音翻译>5秒，双模型方案<3秒
3. **专业度不足**——ASR专模型方言识别准确率95%+，通用模型仅~70%
4. **容错粒度粗**——单模型全部失败，多模型每级独立降级

### 7.2 方言翻译——双模型实时协作

**代码证据**：`DialectAsrWebSocketClient.java`（341行）+ `DialectAsrService.java`（277行）+ `QwenFlashTextService.java`

```
fun-asr-realtime（ASR专模型）：方言语音→方言文字
  ├─ 内置VAD，支持8种方言（粤语/闽南语/四川话等）
  ├─ 延迟<1秒（流式实时），准确率95%+
  └─ 协议：DashScope WebSocket流式音频
               ↓ 方言文字
Qwen-Flash（轻量文本模型）：方言文字→标准普通话
  ├─ 文本到文本，延迟1-2秒，成本低
  └─ 超时TIMEOUT_SECONDS=300
```

**双WebSocket透明桥接**架构：

```
前端浏览器                  Spring后端                    DashScope
┌──────────┐    WS1     ┌──────────────────┐    WS2    ┌───────────┐
│ 麦克风    │ ──PCM──→ │ DialectAsr       │ ──PCM──→ │ fun-asr-  │
│ 采集PCM  │           │ WebSocketHandler │           │ realtime  │
│          │ ←─JSON── │     ↕ 转发       │ ←─JSON── │           │
│ 显示结果  │           │ DialectAsr       │           │ 识别引擎  │
└──────────┘           │ WebSocketClient  │           └───────────┘
                        │     ↓ 异步翻译    │
                        │ QwenFlashText    │
                        │ Service          │
                        └──────────────────┘
```

前端只需"把PCM数据通过WebSocket发过来"，后端`DialectAsrWebSocketHandler`+`DialectAsrWebSocketClient`构成透明桥接——**对前端隐藏所有ASR工程复杂性**。

**核心代码逻辑**（`DialectAsrWebSocketClient.java`）——双WebSocket桥接+异步翻译+uniqueId关联：

```java
public class DialectAsrWebSocketClient {
    private final WebSocketSession frontendSession;    // WS1: 前端→后端
    private final QwenFlashTextService qwenFlashTextService;  // 异步翻译
    private Recognition recognizer;                     // WS2: 后端→DashScope
    private volatile boolean isConnected = false;       // 双volatile标记多线程可见
    private volatile boolean isClosed = false;

    // 连接ASR服务（WS2: 后端→DashScope fun-asr-realtime）
    public void connect() {
        RecognitionParam param = RecognitionParam.builder()
                .model(dialectAsrConfig.getModel())
                .apiKey(dialectAsrConfig.getApiKey())
                .format("pcm").sampleRate(16000)
                .parameter("max_sentence_silence", 1300)        // VAD静音1300ms
                .parameter("language_hints", new String[]{"zh"}) // 方言hints
                .build();
        recognizer = new Recognition();
        recognizer.call(param, new ResultCallback<RecognitionResult>() {
            @Override public void onEvent(RecognitionResult result) { handleRecognitionResult(result); }
            @Override public void onComplete() { isConnected = false; }
            @Override public void onError(Exception e) { sendErrorToFrontend(e.getMessage()); }
        });
        isConnected = true;
    }

    // 核心：处理识别结果——isSentenceEnd过滤+uniqueId关联+异步翻译
    private void handleRecognitionResult(RecognitionResult result) {
        // isSentenceEnd过滤：一句话只翻译最终结果，Token消耗降到1/4~1/5
        if (result.isSentenceEnd()) {
            String text = result.getSentence().getText();
            Long beginTime = result.getSentence().getBeginTime();
            Long endTime = result.getSentence().getEndTime();
            // uniqueId三因素关联（sessionId+beginTime+endTime）
            String uniqueId = frontendSession.getId() + "_" + beginTime + "_" + endTime;
            // 先推识别结果（不等翻译）
            sendToFrontend(DialectAsrWebSocketMessage.transcriptionCompleted(recognitionResult));
            // 异步翻译（翻译完成后用同一uniqueId更新）
            CompletableFuture.runAsync(() -> translateText(uniqueId, text, recognitionResult));
        }
        // 中间结果忽略，不转发
    }

    // 翻译——Prompt软约束+代码硬截取双重护栏
    private void translateText(String uniqueId, String text, ...) {
        int maxLength = text.length() * 2;  // 翻译输出不超过输入的2倍
        String prompt = String.format(
                "请将以下方言文本翻译为标准中文，输出字数不能超过%d字。方言文本：%s", maxLength, text);
        String translated = qwenFlashTextService.generateText(prompt);
        // 代码硬截取——超长时强制截断
        if (translated != null && translated.length() > maxLength) {
            translated = translated.substring(0, maxLength);
        }
        sendToFrontend(DialectAsrWebSocketMessage.translationCompleted(recognitionResult));
        // 翻译失败不影响原始识别结果——"只识别不翻译"降级
    }

    // 三层防资源泄漏：ConcurrentHashMap + 双volatile + finally清理
    public void close() {
        isClosed = true; isConnected = false;
        try { recognizer.stop(); recognizer.getDuplexApi().close(1000, "bye"); }
        finally { translationExecutor.shutdown(); }
    }
}
```

> **架构要点**：`isSentenceEnd`过滤是关键的Token节省机制——fun-asr-realtime模型每句话会推送多个中间结果，只对`isSentenceEnd=true`的最终结果触发Qwen-Flash翻译，Token消耗降低至1/4~1/5。`uniqueId`三因素关联确保前端收到识别和翻译两条消息后能正确匹配。

**四大工程模式**：
- **uniqueId三因素关联**（sessionId+beginTime+endTime）：识别结果立即推送（不等翻译），翻译完成后用同一uniqueId更新
- **CompletableFuture异步翻译**：翻译失败降级为"只识别不翻译"
- **翻译输出长度双重护栏**：Prompt软约束+代码硬截取（maxLength = inputLength × 2）
- **isSentenceEnd过滤**：一句话只翻译一次最终结果，Token消耗降低至1/4~1/5

**VAD配置化护栏**（`DialectAsrConfig.java`）：`silenceDurationMs=1300`经实测调优——太短切断正常停顿，太长延迟增加。4类配置全部Java Bean化外部管理。

**会话生命周期——三层防资源泄漏**：① ConcurrentHashMap并发安全 ② 双volatile标记(isConnected/isClosed)多线程可见 ③ `@PreDestroy`+`finally`双重清理。

**双模型协作 vs 单大模型对比**：

| 维度 | 单大模型端到端 | Harness双模型协作 |
|------|-------------|-----------------|
| 方言识别准确率 | ~70%（通用模型） | **95%+**（ASR专模型） |
| 端到端延迟 | >5秒 | **<3秒**（识别<1秒+翻译1-2秒） |
| 用户体验 | 等5秒才看到结果 | **<1秒看到识别，1-2秒后翻译** |
| 单次成本 | 高（多模态大模型） | **低**（ASR专模型+轻量文本） |
| 容错能力 | 全部失败 | **翻译失败不影响识别** |

### 7.3 角色扮演——四模型串行编排

**代码证据**：`ChatOrchestrationService.generateSimpleHtmlForRolePlay()`（~370行）+ `VideoRetalkService.java`（494行）

```
四模型全链路编排流水线：
① qwen-plus-character（角色对话专模型）
  → ② CosyVoice TTS合成（文本→语音）
    → ③ VoiceEnrollment音色克隆（视频→音色→TTS）
      → ④ VideoRetalk/DigitalHuman（口型替换/数字人视频）
```

**模型①→②：TTS括号内容过滤**——`replaceAll("（[^）]*）", "")`移除动作描述，确保CosyVoice只合成对话内容。

**模型②→③：音色克隆缓存化**——`VideoVoiceMappingService`同一参考视频只需克隆一次，后续复用缓存音色。完整四步克隆流程：extractAudio→voicePrefix("vv"+UUID前6位,≤10字符)→createVoice→save映射。失败映射也记录，避免对同一失败视频反复重试。

**模型③→④：双路径选择**：
```java
// 路径A：有参考视频 → VideoRetalk口型替换
if (StringUtils.isNotEmpty(referVideoUrl)) {
    videoUrl = videoRetalkService.generateVideo(referVideoUrl, ttsResponse.getOssAudioUrl(), imageUrl);
}
// 路径B：有图像无视频 → 数字人视频生成
else if (imageUrl != null && digitalHumanVideoService != null) {
    videoUrl = digitalHumanVideoService.generateVideo(imageUrl, ttsResponse.getOssAudioUrl());
}
// 路径C：两者都没有 → 仅返回音频（三级降级的Level 2）
```

**三级降级策略——视频→音频→文本**：每一级异常独立catch，不级联影响上一级。系统可用性>99%。

**智能上下文压缩**——4000 tokens窗口+高角色一致性：System消息（角色设定）永远保留，从后往前裁剪历史对话，确保角色不"出戏"。

**四模型编排 vs 单大模型对比**：

| 维度 | 单大模型端到端 | Harness四模型编排 |
|------|-------------|-----------------|
| 文本质量 | 通用模型，角色感弱 | **qwen-plus-character**角色一致性强 |
| 语音自然度 | 无法做到 | **CosyVoice专业TTS**+音色克隆个性化 |
| 口型同步 | 无法做到 | **VideoRetalk**口型与音频精确匹配 |
| 故障影响 | 全链路失败 | **每级独立降级**，视频→音频→文本 |
| 扩展性 | 升级影响全部 | **每环节独立替换** |

### 7.4 文档分析——专模型协同+跨模型上下文传递

**代码证据**：`DataMiningServiceImpl.java`（379行）+ `QwenLongQuestionAnswerService.java`（901行）+ `ChatContextService.java`

| 阶段 | 模型 | 选型依据 | 用途 |
|------|------|---------|------|
| 首轮分析 | qwen-doc-turbo | 文档理解专模型，原生支持10+格式 | 非结构化→结构化数据 |
| 后续追问 | qwen-long | 1000万Token上下文窗口 | file-id长上下文追问 |
| HTML可视化 | qwen3-max/qwen3-coder-plus | 通用推理+代码专精 | 分析结果→交互式HTML |

**file-id文档锚定**——上传文档获得唯一file-id，后续通过file-id引用全文。模型在1000万Token窗口中检索任意部分——工程化的RAG替代方案，无需向量化和分块。

**跨模型会话上下文持久化**（`ChatContextService`）：首轮分析结果通过session持久化传递给追问模型——用户感受不到模型切换，工程层完成文件引用、分析上下文、追问路径的完整交接。

**HTML格式自动转换**：qwen-long不支持HTML → 方案A(HTML→PDF保结构) / 方案B降级(HTML→TXT保内容)，用户无感知。



### 7.5 跨域多模型协作总结

| 领域 | 模型数 | 协作模式 | 关键工程手段 | vs 单大模型 |
|------|-------|---------|------------|-----------|
| **方言翻译** | 2 | 串行流水线 | 双WebSocket桥接+uniqueId+异步降级 | 准确率70%→95%，延迟>5s→<3s |
| **角色扮演** | 4 | 串行编排 | 缓存音色+三级降级+OSS中转 | 多模态能力从无到有 |
| **文档分析** | 2 | 分阶段协同 | file-id锚定+跨模型上下文持久化 | 抗幻觉质的提升 |
| **AI应用** | 2 | 分工协作 | qwen3-coder-plus(代码)+qwen3-max(通用) | 代码+创意双保障 |

**统一工程哲学**：每个模型只做自己最擅长的事，工程代码负责将它们可靠地串联或并行协作——缓存避免重复调用、降级保障链路可用、异步避免阻塞、超时防止悬挂。

**模型选型成本矩阵**（代表性模型，需要继续扩充，包含V4文档里提到的全部模型）：

| 模型 | 用途场景 | 选型理由            |
|------|---------|-----------------|
| qwen3-max | 编排决策/复杂推理/Manim代码 | 最强推理能力，编排核心     |
| qwen3-coder-plus | 代码生成/全栈应用 | 代码专精，结构规范       |
| qwen-flash | 方言翻译/轻量文本 | 延时低             |
| qwen-vl-max | P图评估/OCR/试卷标注 | 多模态视觉理解         |
| qvq-max | 作业推理/视觉分析 | 视觉推理专精          |
| qwen-plus-character | 角色扮演对话 | 角色一致性专精         |
| qwen-doc-turbo | 文档理解/首轮分析 | 文档专模型，10+格式原生支持 |
| qwen-long | 文档追问 | 1000万Token上下文窗口 |
| Qwen3-Omni-Flash | 音频质量评估 | 多模态听觉评估         |
| QwenImageMax | 封面图生成 | 图像生成专模型         |
| CosyVoice | TTS语音合成+音色克隆 | 专业TTS+声音克隆      |
| fun-asr-realtime | 方言语音识别(8种方言) | ASR专模型，准确率极高    |
| Wan2.6系列 | 视频生成/编辑 | 视频生成专模型         |
| VideoRetalk | 口型替换 | 口型与音频精确匹配       |



**选型原则**：在每个环节选择**最合适**（而非最强大）的模型——既控制成本，又最大化专业维度的质量。

> 多模型协作解决了"每个模型做什么"。剩下的问题是：**当模型失败时系统如何保持韧性？**

---

## 八、系统韧性——多层确定性降级

> **核心原理**：AI系统的可靠性不取决于"一切正常时表现多好"，而取决于"出错时降级多优雅"。系统通过五层纵深防御、全链路optional注入、智能重试策略、动态重规划构建了**多层确定性降级体系**——每一层失败都有工程化的兜底方案。

### 8.1 五层纵深防御——参数安全护栏

**代码证据**：`AIExecutorEngine.java`（~3519行）+ `ai_config.json` input_resolve配置段

AI编排中最隐蔽的失败模式是"参数占位符未解析"——AI输出了`${task_1.output.image_url}`但运行时未被替换。系统构建**五层纵深防御**：

```
Layer 1: 配置化模式检测（ai_config.json，4种正则+4种关键词，零代码扩展）
Layer 2: resolveTaskOutputPlaceholders() 安全网（正则提取+模式剥离+递归解析）
Layer 3: resolveWithAI() AI兜底（PLACEHOLDER_RESOLVE决策类型+专属Prompt）
Layer 4: preflightValidateNoUnresolvedPlaceholders() 预飞校验（残留→阻断执行）
Layer 5: AIExecutionMonitor 确定性早停（占位符错误→SKIP+智能诊断）
```

**URL保护机制**（`AIParamTransformService`）：AI在参数转换时经常"篡改"URL编码。内置16种URL模式检测，发现LLM输出URL与原始不一致时**自动替换为原始URL**。

**能力路由归一化**（v3.28）：`CapabilityTypeNormalizer`共享映射表（28条映射），防止LLM输出的`agentbay_browser_navigate`被错误路由到AgentBay云端而非本地BROWSER_TOOL——参数安全在**能力路由维度**的新实践。

**核心代码逻辑**（`CapabilityTypeNormalizer.java`）——28条静态归一化映射的完整实现：

```java
public final class CapabilityTypeNormalizer {
    // 不可变映射表——LLM常见错误命名 → 正确注册能力ID
    public static final Map<String, String> NORMALIZE_MAP;
    static {
        Map<String, String> map = new LinkedHashMap<>();
        // LLM常见错误命名归一化
        map.put("browser_act", "browser_action");
        map.put("browser_input", "browser_action");
        map.put("browser_type", "browser_action");
        map.put("browser_fill", "browser_action");
        map.put("browser_click", "browser_action");
        map.put("browser_hover", "browser_action");
        map.put("browser_interact", "browser_action");
        map.put("web_page_extract", "browser_get_content");
        map.put("browser_extract", "browser_get_content");
        map.put("browser_read", "browser_get_content");
        map.put("browser_capture", "browser_screenshot");
        map.put("browser_js", "browser_execute_js");
        // agentbay_旧前缀迁移（防止REPLAN任务走AgentBay云端浏览器）
        map.put("agentbay_browser_navigate", "browser_navigate");
        map.put("agentbay_browser_type", "browser_action");
        map.put("agentbay_browser_click", "browser_action");
        map.put("agentbay_browser_screenshot", "browser_screenshot");
        map.put("agentbay_browser_extract", "browser_get_content");
        map.put("agentbay_browser_wait_for", "browser_wait");
        // ... 共28条映射
        NORMALIZE_MAP = Collections.unmodifiableMap(map);
    }

    // 归一化——不需要归一化时返回原值
    public static String normalize(String capabilityType) {
        if (capabilityType == null) return capabilityType;
        return NORMALIZE_MAP.getOrDefault(capabilityType, capabilityType);
    }
}
```

> **架构要点**：此类在`TaskDecomposerService`（拆解阶段）和`DynamicRePlanningService`（重规划阶段）**双路径引用**同一份`NORMALIZE_MAP`，避免两处维护导致不一致。这是ParamMappingEngine四阶段沉淀（§9.3）在浏览器领域的具体实践——将AI运行中发现的常见错误命名固化为确定性规则。

### 8.2 ToolPool预热与双模式执行

**代码证据**：`ToolPool.java`（534行）+ `ToolInstance.java`（322行）

动态创建的工具首次调用需36秒，通过**异步预热**降低94%延迟：

```java
@PostConstruct
public void init() {
    ExecutorService warmupExecutor = Executors.newFixedThreadPool(3);
    for (String toolType : warmupTypes.split(",")) {
        warmupExecutor.submit(() -> warmupToolType(toolType.trim()));
    }
}
// 首次调用: 36s → 预热后: 2s
```

**端口分配护栏**：5300-5500循环分配+可用性检查+最多100次重试。**双模式执行**：本地ProcessBuilder(localhost:5300-5500) / FC Serverless(自动弹性)。**定时清理**：60秒扫描，空闲>5分钟或不健康实例自动销毁。

**核心代码逻辑**（`ToolPool.java`）——池化管理+异步预热+优先复用+健康检测：

```java
@Component
public class ToolPool {
    private final Map<String, Queue<ToolInstance>> pool = new ConcurrentHashMap<>();
    private final Map<String, ToolInstance> inUse = new ConcurrentHashMap<>();
    private final AtomicInteger portAllocator = new AtomicInteger(5300);

    @PostConstruct
    public void init() {
        warmupExecutor = Executors.newFixedThreadPool(3);
        if (warmupTypes != null && !warmupTypes.isEmpty()) {
            warmupExecutor.submit(this::warmUp);  // 异步预热，不阻塞启动
        }
    }

    // 获取工具实例——池中优先，池空则创建新实例
    public ToolInstance acquire(String toolType, String requiredCapability) {
        // 1. 尝试从池中获取
        Queue<ToolInstance> queue = pool.get(toolType);
        if (queue != null) {
            ToolInstance instance = queue.poll();
            if (instance != null && instance.isHealthy()) {
                instance.reset();
                inUse.put(instance.getId(), instance);
                return instance;   // 从池中获取：~0ms
            } else if (instance != null) {
                instance.destroy(); // 实例不健康，销毁
            }
        }
        // 2. 池中没有，创建新实例
        ToolInstance newInstance = createInstance(toolType);  // 首次：~36s，预热后：~2s
        if (newInstance != null) inUse.put(newInstance.getId(), newInstance);
        return newInstance;
    }

    // 归还到池中（池满或不健康则销毁）
    public void release(String instanceId) {
        ToolInstance instance = inUse.remove(instanceId);
        if (instance != null && instance.isHealthy()) {
            Queue<ToolInstance> queue = pool.computeIfAbsent(
                instance.getToolType(), k -> new ConcurrentLinkedQueue<>());
            if (queue.size() < maxPoolSize) {
                queue.offer(instance);  // 归还到池
            } else {
                instance.destroy();     // 池满则销毁
            }
        }
    }

    @PreDestroy
    public void destroy() {
        // 销毁所有池中+使用中实例，确保资源回收
        pool.values().forEach(q -> { ToolInstance i; while ((i = q.poll()) != null) i.destroy(); });
        inUse.values().forEach(ToolInstance::destroy);
    }
}
```

> **架构要点**：`acquire()`方法体现了"池优先→创建兜底"的资源策略。`isHealthy()`检测确保从池中取出的实例是可用的——不健康实例立即销毁而非返回给调用方。`@PreDestroy`双重清理（pool + inUse）防止JVM退出时的资源泄漏。

#### 8.2.5 BrowserToolServiceManager——浏览器工具跨语言进程生命周期

**代码证据**：`BrowserToolServiceManager.java`（644行）+ `browser_tool/app.py`（140行）+ `browser_tool/models.py`（113行）+ `browser_tool/site_presets.py`（514行）

浏览器自动化能力（BROWSER_TOOL）的架构独特性在于其**跨语言三层进程模型**：Java编排引擎 → Python FastAPI服务（uvicorn） → Playwright Chromium浏览器。`BrowserToolServiceManager`是这个三层架构的核心管理器，负责Python进程的完整生命周期——"与Spring同生同死"。

**① 五步启动流程**（`@PostConstruct → CompletableFuture.runAsync → startService()`）

```java
@Component
public class BrowserToolServiceManager {
    @Value("${optimus.browser-tool.enabled:true}") private boolean browserToolEnabled;
    @Value("${optimus.browser-tool.port:6600}") private int servicePort;
    @Value("${optimus.browser-tool.max-restart-attempts:5}") private int maxRestartAttempts;
    @Value("${optimus.browser-tool.health-check-interval-seconds:30}") private int healthCheckIntervalSeconds;

    private volatile Process serviceProcess;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger restartCount = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        // 异步启动，避免阻塞Spring容器初始化
        CompletableFuture.runAsync(() -> startService());
    }

    private synchronized void startService() {
        // Step 1: 安装Python依赖（pip install -r requirements.txt, 120秒超时）
        installDependencies(workDir);
        // Step 2: 安装Playwright Chromium（playwright install chromium, 180秒超时）
        installPlaywright();
        // Step 3: 清理可能残留的旧进程（lsof -ti:6600 | xargs kill -9）
        cleanupPortIfNeeded();
        // Step 4: 启动uvicorn进程（--workers 1，Playwright不支持多进程并发）
        startProcess(workDir);  // 设置PYTHONUNBUFFERED=1确保日志实时输出
        // Step 5: 等待/health端点返回200 + 启动定时健康监控
        waitForServiceReady();  // 轮询间隔渐进1s→3s，最长90秒
        startHealthMonitor();   // scheduleWithFixedDelay, 30秒间隔
    }
}
```

**② 健康监控与自动重启**——`scheduleWithFixedDelay`每30秒双重检测（进程存活 + HTTP /health 200），进程死亡触发`attemptRestart()`：停止旧进程 → 2秒冷却期 → 清理端口 → 重启。超过`maxRestartAttempts`（5次）后放弃重启，`shouldRun.set(false)`。重启成功后`restartCount.set(0)`重置计数。

**③ @PreDestroy三层清理**——确保JVM退出时Python进程、Chromium浏览器、线程池全部回收：`shouldRun.set(false)` → 取消健康检查定时任务 → 关闭healthCheck/outputReader两个线程池（5秒优雅→shutdownNow兜底） → `stopServiceProcess()`（destroy优雅关闭5秒 → destroyForcibly强制终止3秒兜底）。

**④ 10种浏览器标准化能力端点**

Python FastAPI层（`app.py`）提供10种标准化浏览器能力，统一`BrowserResponse{success, data, message, session_id, error_code}`响应格式：

| 端点 | 核心参数 | 能力说明 |
|------|---------|---------|
| POST /browser/navigate | url, wait_until, timeout_ms | 页面导航（集成站点预设+Cookie自动注入） |
| POST /browser/screenshot | selector, full_page, format | 精确截图（选择器定位/全页） |
| POST /browser/action | action, selector, value | 交互操作（click/fill/type/hover/press_key/select_option） |
| POST /browser/get_content | mode, selector, max_length | 内容提取（html/text/selector_text/attribute 4种模式） |
| POST /browser/execute_js | expression, timeout_ms | JavaScript执行（带超时+错误码分类） |
| POST /browser/scroll | mode, pixels | 页面滚动（to_bottom/to_top/by_pixels/to_element） |
| POST /browser/wait | condition, value | 等待条件（selector/url/timeout） |
| POST /browser/cookie | action, cookies | Cookie管理（get/set/delete/clear） |
| POST /browser/dialog | mode, action | 对话框处理（register/handle_next/get_last） |
| POST /browser/network | action, url_pattern | 网络请求拦截（start_capture/stop_capture/get_captured） |

所有端点的`error_code`使用统一错误码分类：`INVALID_PARAMS`（参数校验失败）、`ELEMENT_NOT_FOUND`（选择器未命中）、`TIMEOUT`（操作超时）、`JS_EXECUTION_ERROR`（JS执行异常）、`NAVIGATION_FAILED`（导航失败）、`BROWSER_ERROR`（浏览器级错误）。`action`端点在`ELEMENT_NOT_FOUND`时自动采集页面可见可交互元素摘要（`_collect_visible_elements()`），附加到错误响应中供AI决策下一步操作。

**⑤ 站点预设机制——8大国内热门站点的反爬虫策略适配**

`site_presets.py`（514行）注册了8大站点预设（百度/淘宝/京东/小红书/抖音/知乎/B站/搜狗微信）。每个`SitePreset`数据类包含：`wait_until`等待策略、`post_nav_js`弹窗自动关闭脚本、`selector_aliases`选择器别名映射、`search_url_template`搜索URL模板、`block_resource_types`资源拦截、`cookie_storage_key` Cookie存储标识。导航时`match_preset(url)`通过域名索引自动匹配（精确匹配→后缀匹配→DEFAULT），新增站点只需注册`SitePreset`数据类，无需修改任何handler逻辑。

**⑥ 反检测增强**——`BrowserManager`启动Chromium注入反检测参数（`--disable-blink-features=AutomationControlled`/`--disable-infobars`/`--lang=zh-CN`）；`SessionManager`创建会话注入`COMMON_ANTI_DETECT_JS`（7项：移除webdriver标记、伪造plugins/languages/chrome.runtime、Canvas指纹随机化、WebGL Vendor伪造、permissions query伪造）+ 真实Chrome UA伪装 + `playwright-stealth`库集成。

**⑦ 会话管理**——`SessionManager`维护全局会话池，`get_or_create()`自动创建/复用会话（含反检测增强），`cleanup_idle()`每60秒扫描清理超过TTL（300秒）的空闲会话，`DELETE /browser/session/{id}`端点供Java编排器在阶段6.5自测试完成后的finally块中主动释放。

**7项配置化参数**（`application.yml`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `optimus.browser-tool.enabled` | true | 总开关 |
| `optimus.browser-tool.auto-start` | true | 自动随Spring启动 |
| `optimus.browser-tool.auto-restart` | true | 进程死亡自动重启 |
| `optimus.browser-tool.port` | 6600 | FastAPI服务端口 |
| `optimus.browser-tool.max-restart-attempts` | 5 | 最大重启次数 |
| `optimus.browser-tool.startup-timeout-seconds` | 90 | 启动等待超时 |
| `optimus.browser-tool.health-check-interval-seconds` | 30 | 健康检查间隔 |

> **架构要点**：`BrowserToolServiceManager`体现了"**与Spring同生同死**"的进程管理哲学——`@PostConstruct`异步启动不阻塞容器、`@PreDestroy`三层清理确保资源回收、`ensureServiceAvailable()`提供按需懒启动入口。`--workers 1`是Playwright的硬约束（不支持多进程并发），通过`SessionManager`的会话复用在单worker内支持多任务并行。站点预设机制将"哪些站点需要特殊处理"从代码逻辑中抽离为数据配置——这是"配置驱动"原则在跨语言架构中的工程化实践。`COMMON_ANTI_DETECT_JS`注入7项反检测措施，解决国内主流站点严格的bot检测问题，而这些措施对AI编排调用方完全透明——AI只需调用`/browser/navigate`，反检测在基础设施层自动处理。

### 8.3 全链路优雅降级——零停机韧性

**代码证据**：全链路`@Autowired(required=false)`

```java
@Autowired(required = false) private OrchestrationPatternService patternService;
@Autowired(required = false) private DynamicRePlanningService rePlanningService;
@Autowired(required = false) private SelfTestEngine selfTestEngine;
@Autowired(required = false) private HeartbeatDaemon heartbeatDaemon;
@Autowired(required = false) private TairCacheManager tairCacheManager;
```

**多级降级链**：TairCacheManager(L1本地5分钟+L2 Redis分布式) → MySQL不可用降级ConcurrentHashMap → AI决策不可用降级规则策略(confidence=0.5)。`AIDecision.fallback()`统一标记降级决策置信度为0.5。

### 8.4 Agent资源管理——虚拟环境池+端口池

**代码证据**：`AgentResourceManager.java`（~184行）+ `PortManager.java`（~381行）

全栈应用每次部署需独立端口/目录/Python环境，通过**资源池化管理**隔离：

**PortManager 4层端口安全机制**（`PortManager.java`，~381行）：

```java
// Layer 1: isPortAvailable() 内存集合检测（ConcurrentHashMap<Integer, Boolean>）
// Layer 2: isPortTrulyAvailable() 系统级端口检测（lsof命令）
// Layer 3: allocateAndHoldPort() ServerSocket预占用机制
// Layer 4: ensurePortAvailable() 强制清理占用进程+自动切换新端口
```

端口范围18000-19000。资源生命周期管理：`allocateResources()`分配 → `getResourceAllocation()`查询 → `releaseResources()`释放。`@PreDestroy`确保JVM退出时释放所有持有的ServerSocket，防止端口泄漏。

### 8.5 动态重规划——7种恢复动作+3类错误分类

**代码证据**：`DynamicRePlanningService.java`（~593行）+ `RecoveryAction.java` + `AIErrorClassifier.java`

**7种恢复动作**：RETRY(指数退避)/SKIP/FALLBACK(记录已尝试集合防循环)/ROLLBACK/REPLAN/ABORT/MANUAL。

**3类错误分类**：RETRYABLE(临时性,网络超时) / NON_RETRYABLE(永久性,参数错误) / RECOVERABLE(可恢复,限流)。

**三层恢复升级**：

```
Level 1: RETRY（指数退避，delayMs = 1000 × recoveryAttempt）
    ↓ 失败
Level 2: FALLBACK（降级备选能力，triedCapabilities集合防循环）
    ↓ 失败
Level 3: REPLAN（recoveryAttempt ≥ 2 时升级，抛出ReplanSignalException）
```

**动态重规划次数公式**：`dynamicMax = min(3 + max(0, taskCount - 3), 7)`——超过3个任务的每个额外任务+1次机会，上限7次。已完成任务结果保留不回退。

**核心代码逻辑**（`DynamicRePlanningService.java`）——重规划核心流程与任务归一化：

```java
@Service
public class DynamicRePlanningService {
    // 引用共享常量类，避免与TaskDecomposerService重复维护
    private static final Map<String, String> CAPABILITY_TYPE_NORMALIZE_MAP = 
            CapabilityTypeNormalizer.NORMALIZE_MAP;
    
    @Autowired(required = false)
    private Qwen3MaxTextService qwen3MaxTextService;  // AI服务可选注入
    
    private final Map<String, Integer> sessionReplanCount = new ConcurrentHashMap<>();

    public RePlanResult replan(String sessionId, String originalRequest,
                                List<SubTask> allTasks, Map<String, Object> completedResults,
                                String failedTaskId, String failedError, ...) {
        int currentReplanCount = sessionReplanCount.getOrDefault(sessionId, 0) + 1;
        // 动态计算最大重规划次数
        int effectiveMaxReplan = getEffectiveMaxReplan(sessionId, allTasks);
        if (currentReplanCount > effectiveMaxReplan) {
            return RePlanResult.builder().success(false)
                    .reasoning("已达最大重规划次数限制(" + effectiveMaxReplan + "次)").build();
        }
        // AI服务不可用时直接返回失败
        if (qwen3MaxTextService == null) {
            return RePlanResult.builder().success(false).reasoning("AI服务不可用").build();
        }
        // 分类已完成/未完成任务（已完成结果保留不回退）
        List<String> completedTaskIds = allTasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.COMPLETED)
                .map(SubTask::getId).collect(Collectors.toList());
        // 构建重规划Prompt → 调用AI生成新计划 → 解析 → 验证（含能力归一化）
        String aiResponse = qwen3MaxTextService.generateText(replanPrompt);
        List<SubTask> newSubtasks = parseReplanResponse(aiResponse);
        List<SubTask> validTasks = validateNewTasks(newSubtasks); // 内含归一化
        sessionReplanCount.put(sessionId, currentReplanCount);
        return RePlanResult.builder().success(true).newSubtasks(validTasks).build();
    }

    // 动态最大重规划次数公式
    private int getEffectiveMaxReplan(String sessionId, List<SubTask> allTasks) {
        int taskCount = (allTasks != null) ? allTasks.size() : 0;
        // dynamicMax = min(3 + max(0, taskCount - 3), 7)
        int dynamicMax = Math.min(3 + Math.max(0, taskCount - 3), 7);
        return sessionDynamicMaxReplan.computeIfAbsent(sessionId, k -> dynamicMax);
    }
}
```

> **架构要点**：`validateNewTasks()`内部调用`CapabilityTypeNormalizer.normalize()`对REPLAN任务的能力类型进行归一化，防止AI在重规划时使用`agentbay_browser_*`等非标ID。`sessionReplanCount`使用`ConcurrentHashMap`保证多线程安全，`getEffectiveMaxReplan()`的动态公式确保复杂任务（多子任务）获得更多重规划机会。

**`triedCapabilities`已失败能力追踪**（v3.28）：同任务内降级防循环（记录已尝试能力集合）→ 多轮恢复失败自动升级REPLAN → `ReplanSignalException`传递到重规划Prompt注入"禁止使用列表"，形成"**恢复→降级→REPLAN**"三级韧性链路。`CapabilityTypeNormalizer`在TaskDecomposerService拆解阶段和DynamicRePlanningService重规划阶段双路径覆盖，确保重规划后的能力ID通过注册表校验。

#### 8.5.5 AIExecutionMonitor——AI驱动的智能错误恢复

**代码证据**：`AIExecutionMonitor.java`（973行）+ `RecoveryAction.java`（86行）

`AIExecutionMonitor`是错误恢复链路的核心决策组件，负责将任务执行错误转化为7种`RecoveryAction`中的一种。其核心方法`analyzeAndRecover()`实现了**确定性早停+AI决策+多层解析降级**的三层恢复架构：

```java
@Component
public class AIExecutionMonitor {
    // 【确定性输入错误早停】——跳过AI决策直接快速返回
    if (error.contains("[PERMANENT]") || error.contains("未解析的占位符")) {
        // 确定性错误，重试无效 → 直接REPLAN或ABORT，confidence=0.95
        fastDecision.setAction(replanAvailable ? RecoveryAction.REPLAN : RecoveryAction.ABORT);
        return fastDecision;  // 零LLM调用
    }

    // 【AI驱动恢复决策】——RECOVERY_STRATEGY决策类型
    AIDecision decision = aiDecisionEngine.makeDecision(
            DecisionType.RECOVERY_STRATEGY, context, false);

    // 【多层解析降级】——从strategyParams→action字段→reasoning语义推断
    RecoveryAction action = RecoveryAction.fromCode(decision.getSelectedStrategy());
    if (action == ABORT && params != null) {
        String realAction = extractActionFromParams(params);       // 5种字段名优先级查找
        if (realAction == null && reasoning != null) {
            // 否定语义排除：reasoningPositivelyMentions()
            // 防止"不符合重试条件"中的"重试"被误匹配
        }
    }
}
```

**7种RecoveryAction及其继续执行语义**：

| 恢复动作 | code | 描述 | canContinue |
|---------|------|------|------------|
| RETRY | retry | 指数退避重试（delayMs = 1000 × retryCount） | true |
| SKIP | skip | 跳过当前任务继续执行 | true |
| FALLBACK | fallback | 使用降级能力 | true |
| ABORT | abort | 中止整个执行计划 | **false** |
| MANUAL | manual | 需要人工介入 | **false** |
| ROLLBACK | rollback | 回滚到上一个快照 | true |
| REPLAN | replan | 动态重规划 | true |

**否定语义排除**（`reasoningPositivelyMentions()`）——防止AI reasoning中的否定表述被误匹配。例如"不符合重试条件"包含"重试"关键词，但其前10字符内存在否定词"不符合"，应判定为**不建议RETRY**：

```java
private boolean reasoningPositivelyMentions(String reasoning, String chineseKeyword, String englishKeyword) {
    String[] negationPrefixes = {"不", "无法", "不应", "不宜", "不符合", "不建议",
            "不需要", "不满足", "不适合", "无意义", "没有必要",
            "not ", "no ", "don't ", "cannot ", "shouldn't "};
    // 检查关键词前10个字符内是否有否定词
    int lookbackStart = Math.max(0, idx - 10);
    String prefix = reasoning.substring(lookbackStart, idx);
    for (String neg : negationPrefixes) {
        if (prefix.contains(neg)) return false;  // 否定语义 → 不是正面建议
    }
}
```

> **架构要点**：`RecoveryAction.fromCode()`在无法匹配时默认返回`ABORT`（安全降级），`canContinue`字段控制是否允许后续任务继续执行——ABORT和MANUAL是唯二会终止整个编排的动作。确定性错误早停设`confidence=0.95`（高于AI决策的典型置信度），表达"工程判断优先于AI判断"的确定性优先原则。

### 8.6 三层工具降级链路——"确定性优先，AI兜底"的工具选择维度（v3.28）

v3.28浏览器自动化引入了**三层工具业务优先级与降级链路**，是"确定性优先，AI兜底"原则在工具选择维度的完整落地：

```
业务优先级最高：MCP工具（结构化API直连，精确高效）
  ↓ MCP Server不可达 / 超时 / 反爬触发
业务优先级次选：BROWSER_TOOL（本地Playwright，通用浏览器操作兜底）
  ↓ BROWSER_TOOL进程不可用
业务优先级兜底：AgentBay浏览器（云端沙箱Tier 3）/ SKIP / ABORT
```

> 注：此处"业务优先级"是工具选择序列，与ToolTierManager的Tier编号是两个维度——MCP与BROWSER_TOOL共享Tier 2，通过`briefDescription`语义隔离业务优先级。

**CAPABILITY_FALLBACK_MAP双路径降级映射**：

| 降级路径 | 源能力 | 目标能力 | 场景 |
|---------|-------|---------|------|
| 路径A：MCP→browser | 8个MCP工具（xhs/douyin等） | `browser_navigate` | MCP Server故障/反爬触发时，浏览器兜底 |
| 路径B：browser→atomic | `browser_get_content`等 | `atomic_web_summary` | BROWSER_TOOL不可用时，降级到内置网页摘要 |

**`findFallbackForCapability()`三级降级查找**——本方法是v3.28最具方法论价值的创新：

| 降级级别 | 查找方式 | 确定性级别 |
|---------|---------|-----------|
| 第一级 | **静态映射查找**：`CAPABILITY_FALLBACK_MAP.get(capabilityId)` | 完全确定性（零LLM调用） |
| 第二级 | **同域候选查找**：按domain匹配同域能力，排除FULLSTACK_TOOL和BROWSER_TOOL类型（防同域无意义降级） | 候选范围确定性 |
| 第三级 | **AI语义相似度过滤**：`filterFallbackBySemanticSimilarity()`从同域候选中选择最匹配项（相似度≥0.5） | AI驱动（大模型兜底） |

> **Harness工程意义**：静态映射精确降级优先→同域候选收敛范围→AI语义匹配兜底长尾。三级降级每层都有工程化保障，是"**确定性优先，AI兜底**"的教科书级实现。注：FULLSTACK_TOOL→ATOMIC_TOOL的降级映射由`AIExecutionMonitor`独立实现，不在此MAP中。

### 8.7 ELEMENT_NOT_FOUND确定性降级——Prompt种下"种子"，执行层"收获"（v3.28）

**代码证据**：`AIExecutorEngine.java` BROWSER_TOOL错误码处理

v3.28在BROWSER_TOOL执行层实现了5种错误码分类处理，其中`ELEMENT_NOT_FOUND`的处理是"配置驱动→大模型降级"原则的精选案例：

**三条件确定性降级**（零LLM调用）：

| 条件 | 检查内容 |
|------|---------|
| 条件1 | 当前action是`fill`或`type`（搜索输入操作） |
| 条件2 | inputData有`value`（搜索关键词非空） |
| 条件3 | previousResults中存在`search_url_template`（站点预设配置） |
| **三条件全满足** | 确定性转换为`browser_navigate(url=搜索URL)`，URL编码关键词+保留session_id → continue重进执行循环 |

> **跨阶段联动设计**：`search_url_template`来自§3中PromptStructureBuilder注入的8大站点URL模板，形成**Prompt注入→执行层消费**的跨阶段联动——Prompt层种下"种子"（URL模板），执行层在错误恢复时"收获"（零LLM调用的确定性转换）。三条件不满足时降级到LLM恢复决策，并携带`visible_interactive_elements`结构化信息辅助AI判断。

### 8.8 韧性工程总结

从参数安全（§8.1）到三层工具降级（§8.6）再到动态重规划（§8.5），系统在每一层都遵循**"确定性优先，AI兜底"**原则——能用规则解决的不浪费LLM，AI失败时有工程化降级路径。v3.28的`findFallbackForCapability()`三级降级查找（§8.6）和ELEMENT_NOT_FOUND跨阶段联动（§8.7）是这一原则在工具选择和错误恢复维度的教科书级新实现。这是"防御性韧性"——确保系统不崩溃。下一章转向另一个维度：**进攻性进化**——让系统越用越聪明。

---

## 九、运行时自我进化——让系统越用越聪明

> **核心原理**：传统AI系统每次调用都"从零开始"——不记住过去的成功和失败。Harness Engineering的进攻性策略是通过工程化的经验沉淀机制，使系统的"有序度"随时间单调递增——这不是模型的自我学习（不改变模型权重），而是**Harness的自我进化**。

### 9.1 HeartbeatDaemon——AI自主巡检

**代码证据**：`HeartbeatDaemon.java`（496行）

```java
@Scheduled(fixedDelayString = "${optimus.heartbeat.interval-ms:1800000}", initialDelay = 60000)
public void heartbeat() {
    Map<String, Object> systemState = collectSystemState();
    List<HeartbeatTask> dueTasks = heartbeatTaskRegistry.getDueTasks();
    AIDecision decision = aiDecisionEngine.makeDecision(
        DecisionType.HEARTBEAT_EVALUATE, context, false);
    executeAIActions(decision.getRawResponse(), dueTasks);
}
```

**AI评估维度**：① 运行中任务超时检测(>40分钟) ② 定时任务到期触发 ③ 过期缓存/僵尸进程清理 ④ Redis/Tair/DashScope API健康状态。按`heartbeat_config.json`（45行）结构化配置执行。

**核心代码逻辑**（`HeartbeatDaemon.java`）——完整的心跳检查流程与降级机制：

```java
@Service
public class HeartbeatDaemon {
    @Autowired(required = false) private TaskCenterService taskCenterService;
    @Autowired(required = false) private ScheduledTaskManager scheduledTaskManager;
    @Autowired(required = false) private TairCacheManager tairCacheManager;
    
    private volatile String lastHeartbeatResult = "首次心跳，无历史记录";
    private int heartbeatCount = 0;

    @Scheduled(fixedDelayString = "${optimus.heartbeat.interval-ms:1800000}", initialDelay = 60000)
    public void heartbeat() {
        if (!heartbeatEnabled) return;
        heartbeatCount++;
        // 1. 收集系统状态（运行中任务+定时任务+健康信息）
        Map<String, Object> systemState = collectSystemState();
        // 2. 获取到期的心跳任务
        List<HeartbeatTask> dueTasks = heartbeatTaskRegistry.getDueTasks();
        if (dueTasks.isEmpty()) { lastHeartbeatResult = "无到期任务"; return; }
        // 3. 调用AI评估引擎（HEARTBEAT_EVALUATE决策类型）
        AIDecision decision = aiDecisionEngine.makeDecision(
                DecisionType.HEARTBEAT_EVALUATE, context, false);
        // 4. 解析并执行AI建议的操作
        if (decision != null && decision.getRawResponse() != null) {
            executeAIActions(decision.getRawResponse(), dueTasks);
        } else {
            executeFallbackChecks(dueTasks);  // AI评估失败，降级到基础检查
        }
    }

    // 系统状态收集——三维度：运行任务+定时任务+健康信息
    private Map<String, Object> collectSystemState() {
        Map<String, Object> state = new LinkedHashMap<>();
        // 运行中任务（检测超时>40分钟的任务）
        List<Map<String, Object>> runningTasks = collectRunningTasks();
        state.put("running_tasks", runningTasks);
        // 待触发的定时任务
        List<Map<String, Object>> pendingScheduled = collectPendingScheduledTasks();
        state.put("scheduled_tasks", pendingScheduled);
        // 系统健康（Redis/Tair/DashScope API状态）
        Map<String, Object> health = collectHealthInfo();
        state.put("system_health", health);
        return state;
    }
}
```

> **架构要点**：三个`@Autowired(required = false)`确保`TaskCenterService`/`ScheduledTaskManager`/`TairCacheManager`任一不可用时心跳仍能启动。`lastHeartbeatResult`使用`volatile`修饰保证跨线程可见性，上次心跳结果传递给下次评估作为上下文——形成心跳间的经验传递。

### 9.2 六维智能自我进化飞轮

系统通过六维飞轮使Harness自身持续积累工程经验：

```
  ① 示例自动沉淀 → ② 编排模式记忆 → ③ 三层记忆语义检索
        ↑                                       ↓
  ⑥ 自测试闭环  ← ⑤ 反思引擎动态重规划 ← ④ 动态工具创建
```

| 维度 | 核心Service | 机制 |
|------|------------|------|
| ① 示例自动沉淀 | `ExampleAutoDepositService`（~354行） | 编排成功→质量评分≥0.6→自动沉淀为新示例 |
| ② 编排模式记忆 | `OrchestrationPatternService`（~480行） | 成功/失败双维缓存，独立读写开关（默认只写不读，安全上线） |

**编排模式记忆详细**：
```java
// 双维缓存：
// 维度1: 成功模式（patternCache: ConcurrentHashMap<String, PatternEntry>，key=能力类型序列）
// 维度2: 失败规避（antiPatternCache: ConcurrentHashMap<String, AntiPatternEntry>，key=失败能力类型）

// 独立读写开关（默认只写不读，安全上线）：
private boolean isWriteEnabled() { return configService.getBoolean("pattern_memory.write_enabled", true); }
private boolean isReadEnabled() { return configService.getBoolean("pattern_memory.read_enabled", false); }

// 集成点（read_enabled=true时）：命中历史成功模式（successCount>=2）→ 直接复用，跳过LLM拆解
// 缓存管理：最大200条 × 1小时TTL × LRU驱逐最旧20%
```

**核心代码逻辑**（`OrchestrationPatternService.java`）——双维缓存+独立读写开关+成功/失败模式记录：

```java
@Service
public class OrchestrationPatternService {
    // 双维缓存：成功模式 + 失败规避
    private final Map<String, PatternEntry> patternCache = new ConcurrentHashMap<>();
    private final Map<String, AntiPatternEntry> antiPatternCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 200;
    private static final long DEFAULT_CACHE_TTL_MS = 3600_000L;  // 1小时

    // 独立读写开关——默认只写不读，安全上线
    private boolean isWriteEnabled() {
        return configService.getBoolean("pattern_memory.write_enabled", true);
    }
    private boolean isReadEnabled() {
        return configService.getBoolean("pattern_memory.read_enabled", false);
    }

    // 编排成功后记录模式——提取能力类型序列作为模式签名
    public void recordSuccessPattern(ExecutionPlan plan, TaskResult result) {
        if (!isWriteEnabled() || !Boolean.TRUE.equals(result.getSuccess())) return;
        String patternKey = buildPatternKey(plan);  // 基于能力类型序列的摘要
        PatternEntry existing = patternCache.get(patternKey);
        if (existing != null && !existing.isExpired(getCacheTtlMs())) {
            existing.successCount++;  // 已有模式：更新成功计数
            existing.lastSuccessTime = System.currentTimeMillis();
        } else {
            PatternEntry entry = new PatternEntry();
            entry.taskChain = plan.getSubtasks().stream()
                    .map(t -> t.getCapabilityType()).filter(Objects::nonNull)
                    .collect(Collectors.toList());
            entry.dagDepth = computeDagDepth(plan.getSubtasks());
            entry.successCount = 1;
            patternCache.put(patternKey, entry);
        }
    }

    // 编排失败后记录Anti-Pattern
    public void recordFailurePattern(ExecutionPlan plan, String failedTaskId, String errorMessage) {
        if (!isWriteEnabled()) return;
        AntiPatternEntry entry = new AntiPatternEntry();
        entry.failedTaskId = failedTaskId;
        entry.errorMessage = truncate(errorMessage, 300);
        antiPatternCache.put(buildAntiPatternKey(plan, failedTaskId), entry);
    }

    // 查询历史模式（read_enabled=true时生效，successCount>=2才返回）
    public Optional<PatternEntry> findMatchingPattern(String patternKey) {
        if (!isReadEnabled()) return Optional.empty();
        PatternEntry entry = patternCache.get(patternKey);
        if (entry != null && !entry.isExpired(getCacheTtlMs()) && entry.successCount >= 2) {
            return Optional.of(entry);  // 命中：跳过LLM拆解，直接复用
        }
        return Optional.empty();
    }
}
```

> **架构要点**：`isReadEnabled()`默认为`false`（只写不读）是关键的安全上线策略——先积累模式数据，确认质量后再开启读取。`successCount >= 2`的阈值确保只有经过多次验证的成功模式才被复用，避免偶然成功的模式误导后续编排。
| ③ 三层记忆检索 | `MemoryService`+`TairVectorService`+`UserProfileService` | 短期(Map)+长期(MySQL+TairVector双写)+用户画像 |
| ④ 动态工具创建 | `AIToolFactoryService` | AI判断现有工具不满足时自动创建FC Serverless新工具 |
| ⑤ 反思引擎 | `DynamicRePlanningService`+`ReflectionEngine` | 3类错误→7种恢复→REPLAN三层升级 |
| ⑥ 自测试闭环 | `SelfTestEngine`（~592行） | L1 URL→L2 AI质量→L3 浏览器自动化（**v3.28已开启**） |

**设计理念映射**：系统设计参考了 **系统智能 = f(模型能力, 运行环境)** 的工程思维框架——模型能力是"天花板"，但运行环境（Harness）决定了模型能力的实际发挥程度。六维进化使环境本身也在持续进化，形成"**模型能力 × 环境进化**"的乘数效应。

#### 9.2.5 AIToolFactoryService——S4动态工具创建引擎

**代码证据**：`AIToolFactoryService.java`（~1986行）+ `ToolTemplateCache` + `DynamicToolLifecycleManager` + `FCDeploymentService`

当S2能力匹配发现所需能力不存在于215种注册表时，S4阶段触发AIToolFactoryService**运行时动态创建新工具**。这是六维进化飞轮"④ 动态工具创建"的核心引擎，~1986行代码实现了从"AI设计→代码生成→部署→测试→沉淀"的完整流水线。

**`createSingleTool()` 8步创建流水线**：

```
Step 1: ToolTemplateCache语义匹配 → 命中历史成功模板则快速部署（跳过AI设计，~5s vs ~30s）
Step 2: 上下文构建（CapabilityRegistryService input/output schema + FullstackKnowledgeBridge知识注入）
Step 3: TOOL_DESIGN_STRATEGY AI决策（生成ToolDefinition：name/techStack/appPy/indexHtml）
Step 4: DynamicToolValidationService代码验证 + DynamicToolRepairService 3轮修复（开关控制）
Step 5: FC双模式部署：FC Serverless优先 → 本地ProcessBuilder降级（端口5500-6500，最多3次重试）
Step 6: 冒烟测试 + Detection-then-Fix 2轮修复（Round 1精准诊断修复 / Round 2从头重新生成）
Step 7: ToolTemplateCache.publishTemplate() 沉淀为模板（qualityScore=80/50）
Step 8: DynamicToolLifecycleManager生命周期注册 + CapabilityRegistryService动态注册
```

**核心代码逻辑**（`AIToolFactoryService.java`）——模板缓存快速路径+AI创建+FC双模式+冒烟修复：

```java
@Service
public class AIToolFactoryService {
    @Autowired private AIDecisionEngine aiDecisionEngine;
    @Autowired private CapabilityRegistryService capabilityRegistryService;
    @Autowired private DynamicToolLifecycleManager dynamicToolLifecycleManager;
    @Autowired private ToolTemplateCache toolTemplateCache;
    @Autowired(required = false) private FCDeploymentService fcDeploymentService;
    @Autowired(required = false) private FullstackKnowledgeBridge fullstackKnowledgeBridge;
    @Autowired(required = false) private DynamicToolValidationService dynamicToolValidationService;
    @Autowired(required = false) private DynamicToolRepairService dynamicToolRepairService;
    @Autowired(required = false) private ToolSmokeTestDiagnosticService toolSmokeDiagnosticService;
    @Autowired(required = false) private ToolSmokeTestFixService toolSmokeFixService;

    private static final AtomicInteger portAllocator = new AtomicInteger(5500);
    private static final int PORT_RANGE_END = 6500;
    private static final int MAX_RETRY_COUNT = 3;
    private final Map<String, DynamicToolInfo> dynamicToolIndex = new ConcurrentHashMap<>();

    // ========== 入口方法：逐个创建缺失工具 ==========
    public List<Capability> createTools(List<CapabilityMatch> missingCapabilities) {
        for (CapabilityMatch match : missingCapabilities) {
            // 复用优先：findReusableTool() → AI评估复用可行性
            DynamicToolInfo reusableTool = findReusableTool(requiredCapability, match);
            if (reusableTool != null && isToolHealthy(reusableTool.getPort())) {
                reusableTool.recordUsage();  // 复用成功，更新访问记录
                dynamicToolLifecycleManager.recordAccess(reusableTool.getToolId());
                continue;  // 跳过创建
            }
            // 复用失败/不健康 → 创建新工具
            Capability capability = createSingleTool(match);
            if (capability != null) {
                capabilityRegistryService.registerDynamic(...);  // 注册到统一能力库
                dynamicToolIndex.put(capability.getId(), newToolInfo);
            }
        }
    }

    // ========== 8步创建流水线 ==========
    private Capability createSingleTool(CapabilityMatch match) {
        String toolId = "dynamic_" + UUID.randomUUID().toString().substring(0, 8);

        // 【Step 1】模板缓存快速路径——语义匹配历史成功模板
        ToolTemplate bestTemplate = toolTemplateCache.findBestTemplate(capabilityType, requiredCapability);
        if (bestTemplate != null) {
            Path cachedToolDir = toolTemplateCache.createFromTemplate(capabilityType, toolId);
            int port = allocatePort();
            if (startService(cachedToolDir, ToolTechStack.pythonFlask(port), port)) {
                dynamicToolLifecycleManager.registerTool(toolId, port, null);
                return Capability.builder().id(toolId).type("DYNAMIC_TOOL")
                        .source("template_cache").endpoint("http://localhost:" + port + "/api/process")
                        .port(port).build();  // 跳过AI设计，耗时从~30s降至~5s
            }
        }

        // 【Step 2-3】AI设计——知识注入 + TOOL_DESIGN_STRATEGY决策
        Map<String, String> context = new HashMap<>();
        Capability capability = capabilityRegistryService.get(requiredCapability);
        if (capability != null) {
            context.put("input_spec", convertInputSchemaToPrompt(capability.getInput()));
            context.put("output_spec", convertOutputSchemaToPrompt(capability.getOutput()));
        }
        // FullstackKnowledgeBridge知识注入（推荐库+参考代码+文档片段）
        if (fullstackKnowledgeBridge != null) {
            ToolKnowledgeContext knowledgeCtx = fullstackKnowledgeBridge
                    .getKnowledgeForToolCreation(capabilityType, requiredCapability);
            context.put("recommended_libraries", knowledgeCtx.getRecommendedLibraries());
            context.put("reference_python_code", knowledgeCtx.getExamplePythonCode());
        }
        AIDecision decision = aiDecisionEngine.makeDecision(
                DecisionType.TOOL_DESIGN_STRATEGY, context, false);
        ToolDefinition toolDef = parseToolDesignDecision(decision, toolId, requiredCapability);

        // 【Step 4】代码验证 + 3轮修复（toolValidationEnabled开关控制）
        if (toolValidationEnabled && dynamicToolValidationService != null) {
            ToolValidationResult validationResult = dynamicToolValidationService.validate(
                    toolDef.getAppPy(), capability);
            if (!validationResult.isPassed() && dynamicToolRepairService != null) {
                RepairResult repairResult = dynamicToolRepairService.repair(
                        toolDef.getAppPy(), validationResult, requiredCapability, 3);
                if (repairResult.isRepairSuccessful()) toolDef.setAppPy(repairResult.getBestCode());
            }
        }

        // 【Step 5】FC双模式部署——FC Serverless优先 + 本地ProcessBuilder降级
        if (fcToolConfig != null && fcToolConfig.isFcEnabled() && fcDeploymentService.isAvailable()) {
            Capability fcCapability = deployToFC(toolId, toolDef, requiredCapability, ...);
            if (fcCapability != null) return fcCapability;
            if (!fcToolConfig.isFallbackToLocal()) return null;  // FC失败且不允许降级
            // FC失败 → 自动降级到本地部署
        }
        // 本地部署：端口5500-6500循环分配 + 清理旧进程 + 最多3次重试
        while (!started && retryCount < MAX_RETRY_COUNT) {
            port = allocatePort();
            tryKillProcessOnPort(port);
            started = startService(toolDir, toolDef.getTechStack(), port); // Python/Node.js双语言
        }

        // 【Step 6】冒烟测试 + Detection-then-Fix 2轮修复
        SmokeTestResult testResult = performSmokeTest(toolId, port, requiredCapability);
        if (!testResult.isPassed()) {
            // Round 1: Detection-then-Fix（精准诊断+修复，保留业务逻辑）
            if (toolSmokeDiagnosticService != null && toolSmokeFixService != null) {
                DiagnosticResult diagResult = toolSmokeDiagnosticService.diagnose(
                        toolDef.getAppPy(), testResult, requiredCapability);
                if (diagResult.isNeedsFix()) {
                    FixResult fixResult = toolSmokeFixService.fix(
                            toolDef.getAppPy(), diagResult, requiredCapability);
                    if (fixResult.isFixSuccessful()) {
                        toolDef.setAppPy(fixResult.getFixedCode());
                        // 重写文件 → 重启服务 → 重新测试
                    }
                }
            }
            // Round 2: 从头重新生成（传入previous_error反馈让AI改进）
            retryContext.put("previous_error", "上次生成的工具测试失败: " + testResult.getErrorMessage());
            AIDecision retryDecision = aiDecisionEngine.makeDecision(
                    DecisionType.TOOL_DESIGN_STRATEGY, retryContext, false);
            toolDef = parseToolDesignDecision(retryDecision, toolId, requiredCapability);
            // 重写文件 → 重启服务 → 重新测试
        }

        // 【Step 7-8】模板发布 + 生命周期注册
        double qualityScore = testResult.isPassed() ? 80.0 : 50.0;
        toolTemplateCache.publishTemplate(capabilityType, toolDir, toolDef, qualityScore);
        dynamicToolLifecycleManager.registerTool(toolId, port, null);
        return Capability.builder().id(toolId).type("DYNAMIC_TOOL").source("dynamic")
                .endpoint("http://localhost:" + port + "/api/process").port(port).build();
    }

    // 10分钟定时清理过期工具索引
    @Scheduled(fixedRate = 600000)
    public void cleanStaleToolIndex() { /* 清理dynamicToolIndex中不健康/过期的工具 */ }
}
```

> **架构要点**：`createSingleTool()`的8步流水线体现了"**工具也是可进化的**"——ToolTemplateCache将成功工具沉淀为模板，后续相似需求命中缓存直接部署（耗时从~30s降至~5s），是§9.2六维进化飞轮在工具创建维度的闭环。FC双模式部署（`fcToolConfig.isFallbackToLocal()`）是云端Serverless/本地ProcessBuilder的韧性切换——FC弹性扩缩容但有网络依赖，本地部署零网络依赖但占用资源。Detection-then-Fix 2轮修复借鉴了体感游戏Bug检测修复模式（§5）——第1轮通过`ToolSmokeTestDiagnosticService`精准诊断+`ToolSmokeTestFixService`修复（保留业务逻辑），第2轮携带`previous_error`反馈从头重新生成（AI自我纠错）。`findReusableTool()`优先复用已有健康工具，`dynamicToolLifecycleManager`统一管理工具进程的创建/访问/销毁生命周期——所有动态工具"与编排引擎同生同死"。Python/Node.js双语言支持通过`ToolTechStack`配置化选择，`startPythonService()`和`startNodeService()`分别使用ProcessBuilder启动uvicorn和node进程。

### 9.3 参数规则自动沉淀——AI经验的确定性固化

**代码证据**：`ParamMappingEngine`

**参数规则自动沉淀**：AI转换→成功沉淀(confidence≥0.8)→频次确认(命中3次+升级正式规则)→自动淘汰(命中≥3且失败率>50%)。越来越多参数转换由规则引擎完成（0ms/0Token/100%确定性）。

**核心代码逻辑**（`ParamMappingEngine.java`）——规则加载+六种转换类型+动态注册+自动淘汰：

```java
@Service
public class ParamMappingEngine {
    // 规则索引：key为 "sourceCapability->targetCapability"
    private final Map<String, List<ParamMappingRule>> ruleIndex = new ConcurrentHashMap<>();
    // 动态规则命中/失败统计（key: ruleId, value: [hitCount, failCount]）
    private final ConcurrentHashMap<String, int[]> dynamicRuleStats = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 从 orchestrator_knowledge/param_mapping_rules.json 加载41条静态规则
        // 按 sourceCapability->targetCapability 建立索引，按优先级排序
    }

    // 核心方法：根据规则自动映射参数（命中时跳过AI调用：0ms/0Token）
    public Map<String, Object> applyRules(String sourceCapability, String targetCapability,
                                           Map<String, Object> sourceOutput) {
        String key = sourceCapability + "->" + targetCapability;
        List<ParamMappingRule> rules = ruleIndex.get(key);
        if (rules == null) return null;  // 无匹配规则 → 降级到AI转换
        // 防御性拷贝：避免迭代期间被registerRule()的并发修改触发ConcurrentModificationException
        List<ParamMappingRule> rulesSnapshot = new ArrayList<>(rules);
        Map<String, Object> result = new HashMap<>();
        for (ParamMappingRule rule : rulesSnapshot) {
            // blocked类型规则：参数映射被禁止（异常必须向上传播）
            if ("blocked".equals(rule.getTransformType())) {
                throw new RuntimeException("参数映射被禁止: " + sourceCapability + " → " + targetCapability);
            }
            Object value = applyRule(rule, sourceOutput);  // 6种转换类型
            if (value != null) result.put(rule.getTargetField(), value);
        }
        return result.isEmpty() ? null : result;
    }

    // 6种转换类型——覆盖常见的参数映射模式
    private Object applyRule(ParamMappingRule rule, Map<String, Object> sourceOutput) {
        switch (rule.getTransformType()) {
            case "direct":          return sourceOutput.get(rule.getSourceField());       // 直接映射
            case "constant":        return rule.getTransformExpression();                  // 常量注入
            case "append_to_array": return List.of(sourceOutput.get(rule.getSourceField())); // 封装为数组
            case "first_element":   return ((List<?>)sourceOutput.get(rule.getSourceField())).get(0); // 取首元素
            case "format":          return rule.getTransformExpression()
                    .replace("${value}", sourceOutput.get(rule.getSourceField()).toString()); // 格式化
            case "extract":         return ((Map<?,?>)sourceOutput.get(rule.getSourceField()))
                    .get(rule.getTransformExpression());  // Map提取
            default: return null;
        }
    }

    // 运行时动态注册规则——AI转换成功后自动沉淀
    public synchronized boolean registerRule(ParamMappingRule rule) {
        // 去重检查（相同source->target字段的规则不重复注册）
        // 注册后按优先级重排序 + 初始化统计
        dynamicRuleStats.put(rule.getId(), new int[]{0, 0});
        return true;
    }

    // 自动淘汰：命中>=3次且失败率>50%的低质量规则
    public void recordRuleUsage(String ruleId, boolean success) {
        int[] stats = dynamicRuleStats.get(ruleId);
        if (stats == null) return;
        synchronized (stats) {
            stats[0]++; // hitCount
            if (!success) stats[1]++; // failCount
            if (stats[0] >= 3 && (double) stats[1] / stats[0] > 0.5) {
                evictDynamicRule(ruleId);  // 质量低，自动淘汰
            }
        }
    }
}
```

> **架构要点**：ParamMappingEngine实现了四阶段生命周期——**AI转换(阶段1)→成功沉淀(阶段2,confidence≥0.8)→频次确认(阶段3,命中3次+升级)→自动淘汰(阶段4,失败率>50%)**。`blocked`类型规则是"防御性禁令"——当发现某个参数映射会导致错误时，通过blocked规则阻止而非修复，异常必须向上传播不能被catch块吞掉。`applyRules()`的防御性拷贝`new ArrayList<>(rules)`解决了读写并发问题——迭代中的`registerRule()`不会触发ConcurrentModificationException。

**ParamTransformHints前置规划**（v3.3）——任务拆解时即规划：`DIRECT_MAPPING`(0ms,0Token) / `AI_TRANSFORM`(需LLM)，前置减少运行时不确定性。

**CapabilityTypeNormalizer 28条静态映射**（v3.28）= 已从AI运行经验中固化的确定性规则（LLM常见错误命名→正确注册能力ID），是ParamMappingEngine四阶段沉淀在浏览器领域的新实践。

### 9.4 Strangler Pattern渐进重构——持续运行中的架构进化

**代码证据**：`AIExecutorEngine.java`（~3519行）+ 13个委托/协调器（合计~2715行）

**Strangler Pattern渐进重构**：v3.27三分裂（AtomicToolAdapter→UTILITY 16 + AGENT_TOOL 10 + LLM_MODEL_TOOL 5），`@Autowired(required=false)`确保任一新适配器不可用时降级旧路径。v3.0别名声明式映射零风险迁移。

**Strangler委托组件详细**（从AIExecutorEngine 2500+行中提取，共13个委托/协调器，合计~2715行）：

| 组件类型 | 委托组件 | 行数 | 职责 |
|---------|---------|------|------|
| 执行引擎 | `ExecutionValidationDelegate` | 327 | 执行前校验、输入参数验证 |
| 执行引擎 | `LineageTrackingDelegate` | 173 | 数字资产血缘追踪 |
| 执行引擎 | `SessionResultDelegate` | 115 | 会话结果汇总 |
| 执行引擎 | `SessionCleanupCoordinator` | 73 | 三层清理：L1即时→L2协调→L3延迟(30s) |
| 参数转换 | `ParamContextResolverDelegate` | 365 | 参数上下文解析 |
| 参数转换 | `ParamUrlRepairDelegate` | 340 | URL修复+16种模式保护 |
| 参数转换 | `ParamValidationDelegate` | 179 | 参数验证 |
| 参数转换 | `ParamDepositDelegate` | 149 | 规则沉淀队列 |
| 参数转换 | `ParamConfigDelegate` | 144 | 参数配置管理 |
| 工具工厂 | `ToolCodeGeneratorDelegate` | 348 | 工具代码生成 |
| 工具工厂 | `ToolDeploymentDelegate` | 214 | 工具部署管理 |
| 工具工厂 | `ToolReuseEvaluatorDelegate` | 168 | 工具复用评估 |
| 工具工厂 | `ToolSmokeTestDelegate` | 120 | 工具冒烟测试 |

**智能重试策略**（`SmartRetryStrategy`）——避免无意义重试：
```java
// 策略1: HTTP 4xx客户端错误不重试（参数错误重试无法修复）
if (statusCode >= 400 && statusCode < 500) { throw e; }

// 策略2: 连续相同错误模式早停
if (currentErrorMessage.equals(lastErrorMessage)) {
    consecutiveSameErrorCount++;
    if (consecutiveSameErrorCount >= 2) { throw e; }  // 确定性问题，重试无意义
}
```

**定时清理矩阵**：AIDecisionEngine(5分钟)/AsyncExecutionManager(5分钟)/ParamDepositDelegate(1分钟)/AIToolFactoryService(5分钟)。

飞轮6步循环：**沉淀→记忆→检索→创建→反思→测试**——每步输出是下步输入，Harness自身持续进化。

---

## 十、企业级工程实现——从单机智能到生产级系统

> **核心观点**：前面九章讨论的方法论模式都运行在"编排引擎内部"。但生产级AI系统还需要对接外部工具生态、管理数字资产生命周期、提供全链路可观测性。本章讨论这些**从原型到生产**的关键工程实现。

### 10.1 MCP双传输协议——117种外部工具的工程化接入

**代码证据**：`MCPToolAdapter.java`（1155行）+ `MCPStreamableHttpClient.java` + `MCPSSEClient.java`

MCP（Model Context Protocol）是AI应用接入外部工具的标准协议。系统通过MCP适配层接入了**钉钉文档/日历/待办/邮箱**、**高德地图/天气**、**Yahoo Finance**金融数据、**arXiv论文**检索等117种外部工具。

**关键工程决策**：

**① 双传输协议**——SSE（Server-Sent Events）+ Streamable HTTP，运行时按Server配置自动选择：

```java
if (isStreamableHttp) {
    MCPStreamableHttpClient client = new MCPStreamableHttpClient(sseUrl, apiKey, timeoutMs);
} else {
    MCPSSEClient client = new MCPSSEClient(sseUrl, apiKey, timeoutMs);
}
```

**② 懒连接模式（Lazy Connect）**——117个MCP工具启动时**零网络连接**。首次调用时才通过DCL（双重检查锁）建立连接，避免启动延迟。SSE连接经历6个生命周期状态：创建(DCL)→复用(无锁快速路径)→重连(最多3次)→断开→清理(@PreDestroy)→刷新(60s探活)。

**③ 主备降级**——每个Server配置`priority`和`fallbackId`，主Server不可达时自动切换备用Server（可跨传输协议降级）。

**④ 四层错误处理矩阵**：

| 层级 | 错误类型 | 处理策略 |
|------|---------|---------|
| 传输层 | SSE连接中断/HTTP超时 | 自动重连（最多3次，指数退避） |
| 适配层 | 参数序列化失败 | 降级到默认参数+日志告警 |
| 路由层 | Server不可达 | 从MCPServerRegistry摘除+定时探活恢复 |
| 执行层 | 工具执行异常 | 返回结构化错误→编排引擎FALLBACK |

**⑤ `extractMCPContent()` 5种响应解析策略**：标准MCP Tool Result → 嵌套JSON递归提取 → 纯文本直接返回 → Base64解码 → 错误响应结构化提取。

**⑥ MCP端到端链路实测**（小红书内容搜索场景）：端到端总耗时37.2s，占编排总时间138.3s的26.9%。

**MCP Server运行时生态**（7+个自管理服务，60秒健康检查周期）：

| MCP Server | 用途 | 状态管理 |
|-----------|------|---------|
| 抖音 | 短视频内容获取 | 运行时动态启停 |
| 微信 | 社交平台数据 | 运行时动态启停 |
| Yahoo Finance | 金融数据 | 运行时动态启停 |
| 微博 | 社交媒体热点 | 运行时动态启停 |
| 小红书 | 生活方式内容 | 运行时动态启停 |
| Bilibili | 视频内容 | 运行时动态启停 |
| arXiv | 学术论文检索 | 运行时动态启停 |

每个Server配置`priority`和`fallbackId`，主Server不可达时自动切换备用Server。

**⑦ 社交媒体反爬降级**——MCP调用触发反爬时，通过`CAPABILITY_FALLBACK_MAP`静态映射（§8.6）+ `findFallbackForCapability()`三级降级查找，透明降级到Playwright浏览器模拟：

```java
// MCPToolAdapter.java 反爬降级逻辑
if (isAntiCrawlResponse(response)) {
    log.warn("[MCP反爬降级] server={}, 触发反爬检测，降级到Playwright", serverName);
    // 通过findFallbackForCapability()查找CAPABILITY_FALLBACK_MAP映射
    // → browser_navigate → BrowserToolServiceManager → Playwright模拟真实浏览器访问
    return executeWithPlaywright(toolName, params);  // 透明降级
}
```

降级链路工程化闭环：MCPCapabilityAdapter检测反爬 → `findFallbackForCapability()`静态映射查找 → `browser_navigate` → `HttpAdapter` → `BrowserToolServiceManager` → Playwright。上层编排引擎不感知底层传输变化——反爬降级对编排层完全透明。

**核心代码逻辑**（`MCPToolAdapter.java`）——完整的初始化→连接→调用→响应解析生命周期：

```java
@Component
public class MCPToolAdapter {
    private static final String MCP_REGISTRY_PATH = "classpath:orchestrator_knowledge/mcp_capability_registry.json";

    @Value("${optimus.mcp.enabled:true}")
    private boolean mcpEnabled;
    @Value("${optimus.mcp.timeout:120000}")
    private int mcpTimeout;              // 图像生成建议120000+
    @Value("${optimus.mcp.lazy-connect:true}")
    private boolean lazyConnect;         // 懒连接：首次调用时才建立连接

    /** MCP工具配置：toolId -> MCPToolConfig */
    private final Map<String, MCPToolConfig> toolConfigs = new ConcurrentHashMap<>();
    /** SSE客户端连接池：sseUrl -> MCPSSEClient */
    private final Map<String, MCPSSEClient> sseClients = new ConcurrentHashMap<>();
    private final Map<String, MCPStreamableHttpClient> streamableHttpClients = new ConcurrentHashMap<>();

    // ========== 初始化三步：加载注册表→注册能力库→(非懒模式)连接全部Server ==========
    @PostConstruct
    public void init() {
        if (!mcpEnabled) return;
        loadMCPRegistry();                    // 1. 从mcp_capability_registry.json加载工具定义
        registerToCapabilityRegistry();        // 2. 自动注册到统一能力库(CapabilityRegistryService)
        if (!lazyConnect) connectAllServers(); // 3. 非懒连接模式下立即连接所有Server
    }

    // ========== 双传输协议连接：SSE vs Streamable HTTP ==========
    public boolean connectToServer(String sseUrl, String apiKey, String transportType) {
        boolean isStreamableHttp = "streamable-http".equals(transportType);
        long timeoutMs = toolConfigs.values().stream()
                .filter(c -> sseUrl.equals(c.getSseUrl()))
                .mapToLong(MCPToolConfig::getTimeoutMs).findFirst().orElse(mcpTimeout);
        if (isStreamableHttp) {
            MCPStreamableHttpClient client = new MCPStreamableHttpClient(sseUrl, apiKey, timeoutMs);
            client.connect();
            streamableHttpClients.put(sseUrl, client);
        } else {
            MCPSSEClient client = new MCPSSEClient(sseUrl, apiKey, timeoutMs);
            client.connect();
            sseClients.put(sseUrl, client);
        }
        return true;
    }

    // ========== 懒连接核心：DCL双重检查锁 ==========
    private MCPSSEClient getOrConnect(MCPToolConfig config) {
        MCPSSEClient client = sseClients.get(config.getSseUrl());
        if (client != null && client.isConnected()) return client;  // 快速路径（无锁）
        synchronized (this) {
            client = sseClients.get(config.getSseUrl());
            if (client != null && client.isConnected()) return client;
            connectToServer(config.getSseUrl(), config.getApiKey(), config.getTransportType());
            return sseClients.get(config.getSseUrl());
        }
    }

    // ========== 工具调用入口：路由+反爬拦截+双传输分派 ==========
    public Object invokeMCPTool(String toolId, Map<String, Object> params) {
        MCPToolConfig config = resolveToolConfig(toolId);  // 支持三种ID格式匹配

        // 反爬降级拦截：小红书→浏览器搜索、微信→Playwright提取
        if ("mcp_xhs_search_feeds".equals(config.getId())) return invokeXhsBrowserSearch(params);
        if ("mcp_wx_get_article".equals(config.getId())) return invokeWxBrowserContent(params);

        boolean isStreamableHttp = "streamable-http".equals(config.getTransportType());
        JSONObject response;
        if (isStreamableHttp) {
            response = getOrConnectStreamableHttp(config).callTool(config.getToolName(), params);
        } else {
            response = getOrConnect(config).callTool(config.getToolName(), params);
        }
        return extractMCPContent(response.getJSONObject("result"));  // 通用响应解析
    }

    // ========== 5种MCP响应解析策略（完全通用，不依赖特定Server实现）==========
    private Object extractMCPContent(JSONObject mcpResult) {
        if (mcpResult.getBooleanValue("isError")) throw new RuntimeException("MCP工具执行错误");
        JSONArray contentArray = mcpResult.getJSONArray("content");
        if (contentArray == null || contentArray.isEmpty()) return mcpResult;

        // 分类收集：JSON / 纯文本 / 非text（image/resource）
        List<Object> jsonParts = new ArrayList<>();
        List<String> textParts = new ArrayList<>();
        List<JSONObject> nonTextParts = new ArrayList<>();
        for (int i = 0; i < contentArray.size(); i++) {
            JSONObject item = contentArray.getJSONObject(i);
            if ("text".equals(item.getString("type")) || item.getString("type") == null) {
                String text = item.getString("text");
                if (text != null && (text.trim().startsWith("{") || text.trim().startsWith("["))) {
                    try { jsonParts.add(JSON.parse(text.trim())); continue; } catch (Exception ignored) {}
                }
                if (text != null) textParts.add(text);
            } else { nonTextParts.add(item); }
        }
        // 策略1: 单JSON → 直接返回（最常见，如万象{"results":[...]}）
        if (jsonParts.size() == 1 && textParts.isEmpty() && nonTextParts.isEmpty()) return jsonParts.get(0);
        // 策略2: 单文本 → {"text":"..."}
        if (textParts.size() == 1 && jsonParts.isEmpty() && nonTextParts.isEmpty())
            return new JSONObject(Map.of("text", textParts.get(0)));
        // 策略3: 单非text → 直接返回（如image类型）
        if (nonTextParts.size() == 1 && jsonParts.isEmpty() && textParts.isEmpty()) return nonTextParts.get(0);
        // 策略4/5: 复合模式 → 智能合并JSON+文本+attachments
        JSONObject composite = new JSONObject(new LinkedHashMap<>());
        if (!jsonParts.isEmpty()) composite.put("data", jsonParts.size() == 1 ? jsonParts.get(0) : jsonParts);
        if (!textParts.isEmpty()) composite.put("text", String.join("\n", textParts));
        if (!nonTextParts.isEmpty()) composite.put("attachments", nonTextParts);
        return composite;
    }

    // ========== 动态Server发现：运行时连接→自动发现工具→注册能力库 ==========
    public Map<String, Object> discoverAndAddServer(String sseUrl, String apiKey) {
        connectToServer(sseUrl, apiKey);
        List<JSONObject> tools = sseClients.get(sseUrl).listTools();  // 自动发现
        for (JSONObject toolJson : tools) {
            MCPToolConfig config = /* 构建配置 */;
            toolConfigs.put(config.getId(), config);
        }
        capabilityRegistryService.registerMCPTools(getAllMCPCapabilities());  // 注册到统一能力库
        return result;
    }
}
```

> **架构要点**：`MCPToolAdapter`是MCP协议与编排引擎的桥接层。三个关键设计决策：(1) **懒连接+DCL**——117个工具启动时零网络连接，首次调用才建立SSE/HTTP连接，避免启动阻塞；(2) **反爬拦截**——小红书/微信在`invokeMCPTool()`入口处被拦截到Playwright浏览器路径，对上层编排完全透明；(3) **5种响应解析策略**——`extractMCPContent()`通过分类收集+策略分派，将所有MCP Server的异构响应归一化为平坦的业务数据，使编排引擎无需感知不同MCP Server的返回格式差异。

### 10.2 外部通知通道——出入站对称设计

**代码证据**：`NotificationChannel.java` + `ExternalInputAdapter.java` + `DingTalkStreamClient.java`

编排系统不仅接收请求，还主动通知外部系统：

**出站通知**（5种通道策略模式）：DingTalk / Email / Feishu / Slack / Webhook，MySQL持久化支持重试+审计。

**入站输入**（出入站对称设计）：
```
HTTP回调模式：外部系统 → POST /api/external/input → ExternalInputAdapter → 编排引擎
DingTalk Stream模式：钉钉消息 → DingTalkStreamClient(WebSocket长连接) → 消息路由 → 编排引擎
  → SignatureUtils.verifySignature() 验签确保消息来源可信
```

### 10.3 SchedulerX分布式调度

**代码证据**：`SchedulerXConfig.java` + `SchedulerXJobManager.java` + `OrchestrationJobProcessor.java`

将编排能力从"用户触发"扩展为"定时自动触发"：

**双通道架构**：Worker回调通道（核心路径，无需暴露公网端口）+ OpenAPI管理通道（CRUD定时任务）。

**三层降级保护**：Layer 1 `@ConditionalOnProperty` Bean存在性 → Layer 2 运行时可用性检查 → Layer 3 OpenAPI失败降级到本地定时器兜底。

### 10.4 数字资产全生命周期闭环

**代码证据**：`AssetContextService.java`（~185行）+ `FormatAdaptationEngine.java`（~372行）+ `DuplicateDetectionService.java`（~624行）

编排系统不仅生产数字资产，还自动管理它们的**全生命周期**：

```
编排前：AssetContextService → TairVector语义搜索(top-5) + 最近10项资产 → 注入编排Prompt
    ↓  AI"看到"用户历史资产，做出更精准的能力选择
编排中：FormatAdaptationEngine(34种源格式→5种目标格式) + LineageTrackingDelegate血缘记录
    ↓  每个转换步骤自动记录 PRODUCED_BY / TRANSCODED_FROM / CONVERTED_FROM
编排后：DuplicateDetectionService(4级去重) + TagClassificationService(5级标签) + 向量化索引
    ↓  新资产入库 tvs_assets
    ↺  回到编排前：下次编排语义检索到更丰富的资产上下文
```

**4级智能去重**：Level 1 SHA-256精确Hash(1.0) → Level 2 pHash感知Hash(汉明距离≤8, 0.85) → Level 3 元数据匹配(Levenshtein≥0.75, 0.70) → Level 4 TairVector语义匹配(余弦≥0.85, 0.80)。

这是Harness驱动的**正反馈飞轮**——资产越多→语义检索越精准→编排质量越高→优质资产继续积累。

### 10.5 全链路可观测性——从黑盒AI到白盒编排

编排系统的每一步决策都**100%可观测、100%可审计**：

**① DAG可视化实时状态追踪**（`dag-visualizer.js` 637行）：4种节点状态实时动画 pending(灰色)→running(蓝色脉冲)→completed(绿色)→failed(红色)，层级高亮显示当前执行层。

**核心前端代码逻辑**（`dag-visualizer.js`）——Kahn拓扑排序+4种节点状态+层级高亮+统计面板+耗时热力图+右键菜单：

```javascript
class DagVisualizer {
    constructor(containerId) {
        this.nodes = [];  this.edges = [];  this.nodeElements = {};
        this.layers = [];
        this.intermediateResults = {};      // 中间结果缓存
        this.taskDurations = {};            // 各节点耗时记录
    }

    // ==================== Kahn拓扑排序——前端镜像后端buildExecutionLayers() ====================
    computeLayers() {
        const inDegree = {};
        this.nodes.forEach(node => { inDegree[node.id] = 0; });
        this.edges.forEach(edge => { inDegree[edge.to] = (inDegree[edge.to] || 0) + 1; });
        const layers = [];
        let currentLayer = Object.keys(inDegree).filter(id => inDegree[id] === 0);
        while (currentLayer.length > 0) {
            layers.push(currentLayer.map(id => nodeMap[id]));
            const nextLayer = [];
            currentLayer.forEach(id => {
                (adjacency[id] || []).forEach(targetId => {
                    inDegree[targetId]--;
                    if (inDegree[targetId] === 0) nextLayer.push(targetId);
                });
            });
            currentLayer = nextLayer;
        }
        return layers;
    }

    // ==================== 4种节点状态实时切换 ====================
    updateNodeStatus(nodeId, status) {
        const statusStyles = {
            'pending':   'background:rgba(0,20,40,0.6);border:1px solid rgba(0,212,255,0.3);',
            'running':   'border:1px solid #00d4ff;animation:dagPulse 1.5s infinite;',  // 蓝色脉冲
            'completed': 'border:1px solid #4CAF50;color:#4CAF50;',                      // 绿色
            'failed':    'border:1px solid #f44336;color:#f44336;'                        // 红色
        };
        nodeEl.style.cssText = baseStyle + statusStyles[status];
        if (status === 'completed') {
            this.completedCount++;
            this.showNodeDuration(nodeId, duration);  // 显示耗时
            this.updateStatsBar();                     // 更新统计面板
        }
    }

    // ==================== 耗时热力图——绿(快)→黄→红(慢) ====================
    applyHeatmap() {
        const durations = this.nodes.filter(n => n.durationMs > 0).map(n => n.durationMs);
        const maxDuration = Math.max(...durations), minDuration = Math.min(...durations);
        this.nodes.forEach(n => {
            const ratio = (n.durationMs - minDuration) / (maxDuration - minDuration);
            // 归一化到0~1，ratio<0.5=绿→黄，ratio≥0.5=黄→红
            const r = ratio < 0.5 ? Math.round(ratio * 2 * 255) : 255;
            const g = ratio < 0.5 ? 200 : Math.round((1 - (ratio - 0.5) * 2) * 200);
            el.style.borderColor = `rgb(${r},${g},50)`;
        });
    }

    // ==================== 统计面板（已耗时/完成率/预估剩余）====================
    updateEstimate() {
        const avgPerTask = elapsed / done;
        const remaining = avgPerTask * (this.totalCount - done);
        estimateEl.textContent = '⏳ 预计剩余 ' + this.formatTime(remaining);
    }

    // ==================== 右键菜单（复制ID/查看详情/中间结果）====================
    bindContextMenu(nodeEl, nodeId) {
        nodeEl.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            this._showContextMenu(e.pageX, e.pageY, nodeId);
        });
    }

    // ==================== 中间结果预览（点击节点弹窗，图片URL自动渲染）====================
    showNodeDetail(nodeId) {
        const result = this.intermediateResults[nodeId];
        if (typeof result === 'string' && result.match(/\.(jpg|jpeg|png|gif|webp)/i)) {
            html += '<img src="' + result + '" style="max-width:100%;">';  // 图片自动渲染
        }
    }
}
// 全局脉冲动画CSS
@keyframes dagPulse {
    0%, 100% { box-shadow: 0 0 0 0 rgba(0, 212, 255, 0.4); }
    50% { box-shadow: 0 0 0 8px rgba(0, 212, 255, 0); }
}
```

> **架构要点**：`dag-visualizer.js`的`computeLayers()`使用Kahn拓扑排序在前端镜像后端`AIExecutorEngine.buildExecutionLayers()`的DAG分层逻辑——前后端使用相同算法确保可视化层级与实际执行层级一致。4种节点状态通过CSS animation实现零JavaScript定时器的脉冲效果（`dagPulse`），耗时热力图将归一化的duration映射为绿→黄→红渐变色，让用户直观识别性能瓶颈节点。统计面板基于已完成任务的平均耗时实时预估剩余时间（线性外推）。

**② 18种SSE事件细粒度推送**（`ProgressEventType.java` 97行 + `OrchestratorProgressService.java` 456行）：

```java
public enum ProgressEventType {
    START, PROGRESS,
    TASK_START, TASK_COMPLETE, TASK_FAILED, TOOL_CREATED,
    STAGE_START, STAGE_COMPLETE,
    DAG_STRUCTURE, LAYER_START, LAYER_COMPLETE,
    INTERMEDIATE_RESULT,
    STREAMING_RESULT, STREAMING_RESULT_DONE,
    CLARIFICATION_REQUEST, CLARIFICATION_RESOLVED,
    COMPLETE, ERROR;
}
```

`OrchestratorProgressService`实现了**事件缓冲+补发机制**：SSE连接建立前的事件存入`ConcurrentLinkedQueue`缓冲队列（MAX_BUFFER_SIZE=50），连接建立后`flushBufferedEvents()`立即补发。流式结果推送使用`chunkIndex`递增，进度值为`85 + min(14, chunkIndex)`，确保进度条从85%平滑推进到99%。

**②-b SSE前端消费——sse-client.js全栈闭环**（`sse-client.js` 311行）：

后端`OrchestratorProgressService`推送18种SSE事件，前端`SSEClient`类通过EventSource API消费并渲染：

```javascript
class SSEClient {
    constructor() {
        this.eventSource = null;
        this.maxReconnectAttempts = 5;   // 最大重连次数
        this.reconnectDelay = 1000;       // 初始重连延迟1秒
        this.lastActivityTime = Date.now();
        this.callbacks = {
            onConnected: null, onProgressUpdate: null,
            onGenerationComplete: null, onGenerationError: null,
            onDisconnected: null, onError: null
        };
    }

    // 连接后端SSE端点
    connect(sessionId) {
        const sseUrl = `/api/optimus/progress/stream/${sessionId}`;
        this.eventSource = new EventSource(sseUrl);
        this.setupEventListeners();
        this.startHeartbeat();
    }

    setupEventListeners() {
        // 5种事件监听——对应后端ProgressEventType的分组推送
        this.eventSource.addEventListener('CONNECTED', (event) => {
            this.updateLastActivity();
        });
        this.eventSource.addEventListener('PROGRESS_UPDATE', (event) => {
            this.updateLastActivity();
            const data = safeJSONParse(event.data);
            if (data) this.callbacks.onProgressUpdate(data);   // → progress.js渲染
        });
        this.eventSource.addEventListener('GENERATION_COMPLETE', (event) => {
            const data = safeJSONParse(event.data);
            if (data) this.callbacks.onGenerationComplete(data);
        });
        this.eventSource.addEventListener('GENERATION_ERROR', (event) => { ... });
        this.eventSource.addEventListener('HEARTBEAT', (event) => {
            this.updateLastActivity();   // 心跳保活
        });
    }

    // 指数退避重连——delay * 2^(attempts-1)
    attemptReconnect() {
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            this.handleError('连接中断且无法重连，请刷新页面重试');
            return;
        }
        this.reconnectAttempts++;
        const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts - 1);
        setTimeout(() => this.connect(this.sessionId), delay);
    }

    // 前端心跳检测——30秒周期检查，60秒无活动告警
    startHeartbeat() {
        this.heartbeatInterval = setInterval(() => {
            if (Date.now() - this.lastActivityTime > 60000) {
                console.warn('长时间无服务器响应，检查连接状态');
                if (this.eventSource.readyState !== EventSource.OPEN) {
                    this.attemptReconnect();
                }
            }
        }, 30000);
    }
}
const sseClient = new SSEClient();   // 全局单例
```

> **全栈SSE链路闭环**：后端`OrchestratorProgressService`(Java) → SSE事件流(`text/event-stream`) → 前端`SSEClient`(JS EventSource) → `progress.js`渲染(进度条/阶段状态/子步骤) → `dag-visualizer.js`可视化(DAG节点状态更新)。重连策略采用**指数退避**（1s→2s→4s→8s→16s，最多5次），前端30秒心跳周期+60秒无活动告警确保长时间编排任务（如12.7分钟的"手势游戏+APK打包"场景）的连接稳定性。后端事件缓冲+前端指数退避重连形成双端韧性保障——即使网络瞬断，重连后`flushBufferedEvents()`会补发缓冲期间的所有事件，前端不会丢失任何进度更新。

**⑤ 上下文压缩——ContextCompactor三级压缩**（`ContextCompactor.java` 635行）

`AIExecutionMonitor`创建执行快照时调用`ContextCompactor.compressWithAI()`对历史任务结果进行智能压缩，防止跨层传递时上下文膨胀。三级压缩策略：

| 压缩级别 | 触发条件 | 压缩方式 | Token消耗 |
|---------|---------|---------|----------|
| Level 1 基础截断 | 任何结果 | 保留前N字符+尾部摘要 | 零LLM |
| Level 2 AI智能摘要 | 长文本结果 | LLM提取关键信息 | 少量 |
| Level 3 URL保留 | 含URL结果 | 保留URL+压缩描述文本 | 零LLM |

配置从`ai_config.json`的`context_compactor`段加载。压缩后的上下文注入到后续任务的执行Prompt中，确保DAG跨层传递时既保留关键信息又不超出Token限制。

**核心代码逻辑**（`ContextCompactor.java` 635行）——AI策略选择+配置化URL保留+三级压缩pipeline：

```java
@Component
public class ContextCompactor {
    private static final int MAX_STRING_LENGTH = 1000;   // 字符串截断阈值
    private static final int MAX_LIST_LENGTH = 10;        // 列表截断阈值

    @Value("${optimus.context.ai-compress-threshold:4096}")
    private int aiCompressThreshold;                      // AI压缩触发阈值（字节）
    @Value("${optimus.context.ai-compress-enabled:true}")
    private boolean aiCompressEnabled;

    @Autowired(required = false) private Qwen3MaxTextService qwen3MaxTextService;
    @Autowired(required = false) private AIDecisionEngine aiDecisionEngine;

    // ========== 配置化URL字段保留（14种默认+自定义扩展）==========
    private Set<String> urlFieldPatterns = new HashSet<>(Arrays.asList(
            "url", "result_url", "image_url", "video_url", "audio_url", "file_url",
            "download_url", "output_url", "link", "href", "ppt_url", "game_url",
            "session_url", "apk_url", "ipa_url", "voice_id"
    ));
    private Set<String> largeContentFields = new HashSet<>(Arrays.asList("content", "base64", "data"));
    private Map<String, String> mediaTypeMapping = Map.of(
            "video","视频", "image","图片", "audio","音频", "ppt","PPT", "game","游戏",
            "apk","APK", "ipa","IPA");

    // ========== 启动加载：从ai_config.json覆盖默认值 ==========
    @PostConstruct
    public void init() {
        // 从 orchestrator_knowledge/ai_config.json 的 param_transform 段加载：
        //   url_field_patterns → 覆盖urlFieldPatterns（扩展URL字段保留范围）
        //   media_type_mapping → 覆盖mediaTypeMapping（扩展媒体类型推断）
        //   large_content_fields → 覆盖largeContentFields（扩展大内容字段名）
    }

    // ========== 主入口：AIExecutionMonitor创建执行快照时调用 ==========
    public Map<String, Object> compressWithAI(Map<String, Object> taskResults, String sessionId) {
        Map<String, Object> compressed = new HashMap<>();
        for (Map.Entry<String, Object> entry : taskResults.entrySet()) {
            String taskId = entry.getKey();
            String resultJson = JSON.toJSONString(entry.getValue());
            int originalSize = resultJson.length();

            // 决策：是否使用AI压缩
            boolean willUseAI = false;
            if (!aiCompressEnabled || qwen3MaxTextService == null) {
                // AI不可用 → 基础压缩
            } else if (originalSize <= aiCompressThreshold) {
                // 未超阈值 → 基础压缩
            } else {
                // 超阈值 → AI智能策略选择
                String strategy = decideCompressStrategy(taskId, resultJson, originalSize, entry.getValue());
                willUseAI = "ai_summary".equals(strategy);
            }

            if (willUseAI) {
                try {
                    String summary = generateAISummary(taskId, resultJson);
                    compressed.put(taskId, createCompressedResult(summary, originalSize));
                } catch (Exception e) {
                    compressed.put(taskId, compactResult(entry.getValue()));  // AI失败→基础压缩降级
                }
            } else {
                compressed.put(taskId, compactResult(entry.getValue()));
            }
        }
        return compressed;
    }

    // ========== AI策略选择——COMPRESS_STRATEGY DecisionType ==========
    private String decideCompressStrategy(String taskId, String resultJson, int originalSize, Object result) {
        if (aiDecisionEngine != null) {
            try {
                Map<String, String> context = new HashMap<>();
                context.put("task_id", taskId);
                context.put("result_size", String.valueOf(originalSize));
                context.put("result_type", result.getClass().getSimpleName());
                context.put("result_snippet", resultJson.substring(0, Math.min(500, resultJson.length())));
                // 检查是否包含URL字段（影响压缩策略选择）
                boolean hasUrlFields = (result instanceof Map) && ((Map<?,?>)result).keySet().stream()
                        .anyMatch(k -> k != null && isUrlField(k.toString()));
                context.put("has_url_fields", String.valueOf(hasUrlFields));

                AIDecision decision = aiDecisionEngine.makeDecision(
                        DecisionType.COMPRESS_STRATEGY, context, true);
                String strategy = decision.getSelectedStrategy();
                if ("ai_summary".equals(strategy) || "basic_compact".equals(strategy)) return strategy;
            } catch (Exception e) { /* AI失败降级 */ }
        }
        return "ai_summary";  // 阈值兜底：默认使用AI摘要
    }

    // ========== 基础压缩——递归类型分派 ==========
    public Object compactResult(Object result) {
        if (result instanceof Map)    return compactMap((Map<?, ?>) result);
        if (result instanceof List)   return compactList((List<?>) result);
        if (result instanceof String) return compactString((String) result);
        return result;
    }

    // ========== Map压缩——URL字段保留+大内容移除+递归压缩 ==========
    private Map<String, Object> compactMap(Map<?, ?> map) {
        Map<String, Object> compacted = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = entry.getKey().toString();
            // 规则1：URL类型字段——原样保留（三级匹配：精确+后缀_url+包含）
            if (isUrlField(key)) { compacted.put(key, entry.getValue()); continue; }
            // 规则2：大内容字段（content/base64/data）——超长替换为标记
            if (largeContentFields.contains(key) && entry.getValue() instanceof String
                    && ((String)entry.getValue()).length() > MAX_STRING_LENGTH) {
                compacted.put(key, "[内容已压缩]");
                compacted.put("_" + key + "_compacted", true);  // 压缩标记
                continue;
            }
            // 规则3：其他字段——递归压缩
            compacted.put(key, compactResult(entry.getValue()));
        }
        return compacted;
    }

    // String中段截断——保留首尾各500字符
    private String compactString(String str) {
        if (str == null || str.length() <= MAX_STRING_LENGTH) return str;
        int keepLength = MAX_STRING_LENGTH / 2;
        return str.substring(0, keepLength) +
                "\n... [省略 " + (str.length() - MAX_STRING_LENGTH) + " 字符] ...\n" +
                str.substring(str.length() - keepLength);
    }

    // List截断——保留前10项
    private List<Object> compactList(List<?> list) {
        List<Object> compacted = new ArrayList<>();
        int size = Math.min(list.size(), MAX_LIST_LENGTH);
        for (int i = 0; i < size; i++) compacted.add(compactResult(list.get(i)));
        if (list.size() > MAX_LIST_LENGTH)
            compacted.add("... [省略 " + (list.size() - MAX_LIST_LENGTH) + " 项] ...");
        return compacted;
    }

    // ========== AI摘要生成——截取前2000字符+URL必须保留 ==========
    private String generateAISummary(String taskId, String resultJson) {
        String truncated = resultJson.length() > 2000 ? resultJson.substring(0, 2000) + "..." : resultJson;
        String prompt = String.format(
            "请将以下任务执行结果压缩为简洁摘要，保留关键输出信息：\n" +
            "任务ID: %s\n结果: %s\n\n要求：\n" +
            "1. 必须完整保留所有URL字段（image_url/video_url/audio_url/ppt_url/game_url/apk_url等）\n" +
            "2. 保留核心输出值（ID、状态等关键字段）\n3. 移除调试信息和冗余数据\n" +
            "4. 控制在200字以内\n5. 使用JSON格式输出摘要", taskId, truncated);
        return qwen3MaxTextService.generateText(prompt);
    }

    // 压缩结果封装——携带元信息
    private Map<String, Object> createCompressedResult(String summary, int originalSize) {
        return Map.of("_compressed", true, "_compress_method", "ai_summary",
                "_summary", summary, "_original_size", originalSize,
                "_compressed_at", System.currentTimeMillis());
    }

    // ========== URL字段三级匹配——精确+后缀+包含 ==========
    private boolean isUrlField(String fieldName) {
        String lower = fieldName.toLowerCase();
        if (urlFieldPatterns.contains(lower)) return true;        // 精确匹配
        if (lower.endsWith("_url")) return true;                  // 后缀匹配
        for (String pattern : urlFieldPatterns)
            if (lower.contains(pattern)) return true;             // 包含匹配
        return false;
    }

    // ========== 历史记录压缩——保留最近N条，旧条目摘要化 ==========
    public Map<String, Object> compactHistory(Map<String, Object> history, int keepRecent, String sessionId) {
        if (history.size() <= keepRecent) return history;
        List<String> keys = new ArrayList<>(history.keySet());
        List<String> recentKeys = keys.subList(Math.max(0, keys.size() - keepRecent), keys.size());
        List<String> oldKeys = keys.subList(0, Math.max(0, keys.size() - keepRecent));
        Map<String, Object> compacted = new HashMap<>();
        for (String key : recentKeys) compacted.put(key, history.get(key));  // 保留最近
        if (!oldKeys.isEmpty()) {
            StringBuilder summary = new StringBuilder("历史任务摘要: ");
            for (String key : oldKeys)
                summary.append(key).append("=").append(summarizeResult(history.get(key))).append("; ");
            compacted.put("_history_summary", summary.toString());           // 旧条目摘要化
        }
        return compacted;
    }

    // 结果摘要——URL字段优先展示（媒体类型推断）
    public String summarizeResult(Object result) {
        if (result instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) result).entrySet()) {
                if (entry.getKey() != null && isUrlField(entry.getKey().toString()) && entry.getValue() != null)
                    return inferMediaType(entry.getKey().toString()) + ":" + entry.getValue();
            }
            return "[Map with " + ((Map<?, ?>) result).size() + " entries]";
        }
        if (result instanceof String) return ((String)result).length() <= 100
                ? (String)result : ((String)result).substring(0, 100) + "...";
        if (result instanceof List) return "[List of " + ((List<?>)result).size() + " items]";
        return result != null ? result.toString() : "[null]";
    }
}
```

**ContextCompactor五大压缩机制**：

| 压缩机制 | 实现方法 | 触发条件 | Token消耗 |
|---------|---------|---------|----------|
| **AI策略选择** | `decideCompressStrategy()` | 结果超过`aiCompressThreshold`(4096字节) | COMPRESS_STRATEGY 1次 |
| **AI智能摘要** | `generateAISummary()` | AI策略选择返回`ai_summary` | Qwen3-Max 1次（前2000字符） |
| **Map配置化压缩** | `compactMap()` | 任何Map类型结果 | 零（isUrlField三级匹配+largeContentFields替换） |
| **String中段截断** | `compactString()` | 字符串>1000字符 | 零（保留首500+尾500） |
| **历史记录压缩** | `compactHistory()` | 历史条目超过keepRecent | 零（旧条目summarizeResult摘要化） |

**URL字段保留的工程意义**——DAG跨层传递中，task_1的输出URL（如`image_url`/`game_url`/`apk_url`）是task_2的关键输入。`compactMap()`通过`isUrlField()`三级匹配（精确→后缀→包含）确保所有URL字段原样保留，`generateAISummary()`的Prompt第1条硬约束也要求"必须完整保留所有URL字段"——**配置化规则+AI Prompt双重保障，URL绝不丢失**。

> **架构要点**：`ContextCompactor`解决了DAG编排的核心矛盾——上下文越丰富AI决策越精准，但上下文越长Token消耗越高且可能超出模型处理能力。五大压缩机制形成"**配置驱动基础压缩（零Token）+ AI驱动智能摘要（少量Token）**"的双层架构。`decideCompressStrategy()`通过COMPRESS_STRATEGY DecisionType让AI根据结果类型、大小、是否含URL等特征选择最优压缩策略——这是"确定性优先，AI增强"原则在上下文管理维度的实践。`createCompressedResult()`封装的`_compressed`/`_compress_method`/`_original_size`元信息支持下游通过`isCompressed()`/`getCompressedSummary()`检测和提取压缩结果，实现压缩/解压的对称设计。配置从`ai_config.json`的`param_transform`段加载，URL字段模式和大内容字段名均可运行时扩展，新增需保留的URL字段无需修改代码。

**③ 编排仪表盘**（`dashboard.html` 163行）：全局概览（总次数/活跃会话/平均耗时/成功率）+ 链路查询（输入sessionId查看完整编排链路）+ 30秒自动刷新。

**④ 29次AI决策完整链路追踪**——以Session `aad6ca88`（双手弹钢琴手势游戏+APK打包）为例：

| 阶段 | 耗时 | 占比 | AI决策次数 |
|------|------|------|-----------|
| S0 多模态感知 | 24.4s | 3.3% | 1次 |
| S1 匹配+拆解 | 108.9s | 14.6% | 8次 |
| S2 能力匹配校验 | 39.8s | 5.4% | 3次 |
| S3 适配器选择 | 6.8s | 0.9% | 3次 |
| S5 DAG执行 | 535.5s | 72.0% | ~10次 |
| S6 结果生成 | 14.6s | 2.0% | 1次 |
| S6.5 自测试 | 13.5s | 1.8% | 1次 |

743.5秒总耗时，29次AI结构化决策调用覆盖10种DecisionType——这是传统AI"黑盒对话"与Harness Engineering"白盒编排"的根本差异。

**4个生产问题自动修复案例**：

| 问题 | 检测层 | 修复机制 | 耗时 |
|------|--------|---------|------|
| 端口5300被占用 | ToolPool端口分配 | 自动递增到5301，最多100次重试 | <1s |
| AI篡改URL编码 | 16种URL模式 | 自动替换为原始URL | <0.5s |
| 游戏示例加载超时 | ExampleKnowledgeBase | 降级到默认模板 | 0s |
| Layer 0.5 AI反思修正 | TASK_CHAIN_OPTIMIZE | AI识别冗余步骤并删除 | 8.3s |

### 10.6 编排引擎REST API接口——OrchestratorController

**代码证据**：`OrchestratorController.java`（1873行）

编排引擎通过`@RestController @RequestMapping("/api/orchestrator")`对外暴露完整的REST API接口。所有接口统一返回`{success, data/message}`格式，服务不可用时通过`@Autowired(required=false)`优雅降级。

**核心API接口清单**：

| HTTP方法 | 路径 | 功能 | 关键参数 | 返回格式 |
|---------|------|------|---------|---------|
| POST | `/preview` | 预览执行计划（不执行） | `prompt`(String) + `files`(MultipartFile[]) | `{preview: {sessionId, subtasks[], matchResult, estimatedSteps}}` |
| POST | `/confirm/{sessionId}` | 确认并执行预览计划 | `sessionId`(PathVariable) | `{sessionId, status:"processing"}` |
| POST | `/execute` | 直接编排执行（JSON） | `{prompt, attachments[]}` | SSE流式事件（通过`/progress/{sessionId}`获取） |
| POST | `/execute` | 直接编排执行（表单+文件） | `prompt`(String) + `files`(MultipartFile[]) | 同上 |
| GET | `/progress/{sessionId}` | SSE事件流订阅 | `sessionId`(PathVariable) | `text/event-stream`（18种ProgressEventType） |
| GET | `/result/{sessionId}` | 获取编排结果（JSON） | `sessionId`(PathVariable) | `{success, results, stats}` |
| GET | `/result/{sessionId}/html` | 获取编排结果（HTML） | `sessionId`(PathVariable) | `text/html`原始结果HTML |
| GET | `/capabilities` | 获取所有可用能力列表 | 无 | `{capabilities[{id, name, type, protocol}]}` |
| GET | `/health` | 健康检查 | 无 | `{status:"UP", components}` |
| GET | `/recommendations` | 智能任务推荐 | `userId`(可选), `limit`(默认4) | `{data: [{title, description, prompt}]}` |
| GET | `/patterns/stats` | 编排模式缓存统计 | 无 | `{data: {totalPatterns, hitRate, ...}}` |
| POST | `/summarize` | AI生成编排结果摘要 | `{resultHtml, prompt}` | `{summary: "..."}` |
| POST | `/clarification/resolve` | 意图协商确认 | `{sessionId, optionIndex, optionLabel}` | `{success, message}` |
| POST | `/clarification/skip` | 跳过意图协商 | `{sessionId}` | `{success, message}` |
| GET | `/trace/{sessionId}` | 链路追踪数据 | `sessionId`(PathVariable) | `{data: {stages[], decisions[], timeline}}` |
| GET | `/metrics/overview` | 全局性能指标 | 无 | `{data: {totalOrchestrations, avgDuration, successRate}}` |
| POST | `/examples/cleanup-deprecated` | 清理deprecated示例 | 无 | `{data: {removedCount, cleanedVectors}}` |

**预览→确认两阶段模式**——`/preview`执行S1任务拆解+S2能力匹配后将结果缓存到本地ConcurrentHashMap + Tair双写（10分钟TTL），用户确认后`/confirm/{sessionId}`从缓存恢复PreviewData跳过前两阶段直接执行S3-S6.5，避免重复LLM调用。

**客户端IP获取三级策略**——`getClientIP()`：X-Forwarded-For → X-Real-IP → RemoteAddr → 公网API（ipify.org/ifconfig.me/checkip.amazonaws.com，30分钟缓存）。IPv6回环`::1`归一化为`127.0.0.1`，私有IP自动解析公网IP。

**编排专用线程池**——`orchestratorExecutor`使用`Executors.newFixedThreadPool(max(4, availableProcessors))`独立线程池，避免使用`ForkJoinPool.commonPool`与其他框架组件互相干扰。线程命名`orchestrator-async-{timestamp}`，设为daemon线程。

---

## 十一、实战验证与结论

> **一分钟速览**：AI Native系统通过7大Harness工程模式、43种结构化决策类型、2,590+知识项，使Qwen系列模型在170万行受控运行环境中完成20+领域的复杂AI任务。核心编排引擎单次任务平均29次AI决策、12.7分钟端到端完成"手势游戏+APK打包"等复合任务，质量通过SelfTestEngine 4维度100分制验证。系统已集成11项阿里云服务、117种MCP外部工具，具备完整的生产级韧性和可观测性。

### 11.1 场景一：双手弹钢琴手势游戏+APK打包（12.7分钟）

**用户需求**："帮我做一个双手隔空弹钢琴的手势游戏，然后打包成安卓APK"

这个需求涉及游戏代码生成、手势交互设计、音频集成、APK打包等多个子域——任何单一模型在一次对话中无法完成。Harness Engineering的介入点：

```
L1识别 COMPOSITE复合任务(confidence=0.95) → 搜索空间缩小
L2精确匹配 atomic_gesture_game(confidence=0.92) → 精确能力选择
TASK_CHAIN_OPTIMIZE → 消除冗余中间步骤
DEPENDENCY_REVIEW → 修复遗漏的数据流依赖
TaskValidator 7/7通过 → 任务质量保证
AdapterRegistry AI→RULE降级 → 正确路由到AtomicToolAdapter/AgentBayAdapter
FingerGameGeneratorService 内部7阶段流水线 → 代码验证6/6通过
AndroidPackageService 8阶段打包流水线 → APK 22.95MB
SelfTestEngine passed=true → 质量确认
ExampleAutoDepositService 3个示例沉淀 → 知识积累
```

约22次结构化决策调用，涉及12种DecisionType。12.7分钟的复杂编排过程中，Qwen模型从未"迷失方向"——每一步都被Harness约束在正确的轨道上。

### 11.2 场景二：网页+图片分析生成PPT并转PDF（12.4分钟）

5个子任务形成4层DAG，Layer 1两个任务并行执行（网页摘要+图像理解），时间节省43%：

```java
// AIExecutorEngine.java 并行执行
for (SubTask task : layer) {
    CompletableFuture<Void> future = CompletableFuture.runAsync(
        () -> executeWithRecovery(sessionId, task, allResults, ...), executorService);
    futures.add(future);
}
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .get(taskTimeoutSeconds, TimeUnit.SECONDS);
```

多任务编排+并行执行+跨格式转换，需要DAG引擎、并行调度器、格式转换器、结果渲染器的工程化协同——这是纯模型能力无法完成的。

### 11.3 量化对比——Harness工程化价值

> **对比前提说明**：以下对比旨在量化**Harness工程化的增量价值**——即同一模型在有无Harness加持下的能力差异。左列"任意大模型裸用"代表**任何模型裸用**的通用局限性（非特指某厂商），右列展示Harness工程化如何系统性地弥补这些局限。这不是模型间的横向评测，而是**工程投入的纵向价值证明**。

| 维度 | 任意大模型裸用 | Qwen + Harness Engineering |
|------|----------------|---------------------|
| **可用工具数** | 模型内置（~20种） | 215种（10种协议统一接入+MCP 117种） |
| **决策结构化** | 开放式隐式决策 | 43种显式DecisionType+专属Prompt模板 |
| **搜索空间** | 面对全部工具描述 | L1+L2匹配缩小40-100倍+Tier分层披露节省60-80% Token |
| **领域知识** | 无专业知识库 | 2,590+知识项（12领域）+多层渐进匹配 |
| **质量保证** | 用户自行判断 | SelfTestEngine 4维度100分制+多维度Bug检测修复 |
| **错误恢复** | 用户手动重试 | RETRY→FALLBACK→REPLAN三层自动+7种恢复动作 |
| **系统韧性** | 单点故障 | 全链路optional注入+多级缓存降级链 |
| **知识积累** | 不积累 | 六维进化飞轮（示例沉淀+模式记忆+参数规则固化） |
| **AI认知+执行分离** | 端到端模型（不精确） | 10场景双层分离（Manim/OpenCV/FluidSynth/Playwright等确定性引擎） |
| **多模型协作** | 单模型全能 | 专模型做专事（方言双模型/角色四模型） |
| **抗幻觉** | 依赖模型自身 | file-id文档锚定+契约驱动+五层参数防御 |
| **并行执行** | 不支持 | DAG分层+CompletableFuture |
| **可观测性** | 黑盒输出 | 29次决策链路追踪+18种SSE事件+DAG可视化 |
| **配置化+演进** | 模型参数固定 | 10+配置段零硬编码+Strangler Pattern渐进重构 |
| **系统自愈** | 不支持 | HeartbeatDaemon 30分钟巡检+AI自主诊断 |
| **Prompt工程化** | 用户手写自然语言 | 双构建器43模板11类+KV Cache 60%命中+边缘效应工程化 |
| **参数安全** | 无保护 | 五层纵深防御+16种URL模式保护+预飞校验 |
| **工具可用性** | 冷启动延迟 | ToolPool异步预热(94%延迟降低)+双模式执行(本地/FC) |
| **企业级集成** | 单机孤岛 | 5种通知通道+SchedulerX分布式调度+出入站对称设计 |
| **数字资产管理** | 不管理 | 4级智能去重+血缘追踪+正反馈飞轮 |
| **三层工具业务优先级** | 无优先级机制 | MCP→BROWSER_TOOL→AgentBay三层业务优先级+CAPABILITY_FALLBACK_MAP静态降级 |

### 11.4 领域知识库——工程化碾压的根基

| 领域 | 知识库规模 | 匹配层数 | 核心Harness机制 |
|------|-----------|---------|-----------|
| AI应用生成 | 675示例+195库 | 四层 | 契约驱动+三维度验证+渐进修复 |
| 游戏大师 | 292示例+294个HTML | 四层 | 六段式Prompt |
| 音乐生成 | 1,348示例+6理论库+18乐器 | 八层 | Python MIDI+FluidSynth+7类Bug检测 |
| 体感游戏 | 85示例+15游戏类型 | 四层 | 7步流水线+6项验证+Bug检测修复 |
| 手势游戏 | 119示例+8种手势 | 两层 | Bug检测修复+在线/离线双模式 |
| PPT生成 | 8模板+三层索引 | 五阶 | 三点知识注入+四维度评估+修复 |
| 视频通话 | 策略模板+MCP | 动态生成 | 可编程通话+安全验证+心跳守护 |
| 文件分析 | 用户文档 | file-id锚定 | 专模型协同+跨模型上下文传递 |
| 帮我P图 | 6个AI模型协同 | 四场景路由 | 空间意图→Mask+双轨降级+跨模态评估 |
| 万相视频 | 3个Wan模型 | API驱动 | DualCanvas+tracking+7场景Prompt |
| AI健身 | 41项目+8训练计划 | 定义驱动 | 关节角度+MET卡路里+TTS语音 |
| AI舞蹈 | 30编舞+6舞种 | 分类匹配 | DTW骨架对比+TTS+Video模式 |

知识库总规模达**2,590+知识项**——这是纯模型能力无法替代的工程化领域积累。

### 11.5 二十条核心设计原则

AI Native系统 是 Harness Engineering 思想的深度工程化实践。它通过以下**20条核心设计**，使普通大模型在受控环境中表现出超越顶尖模型的综合能力：

1. **10种协议适配器 + AI/Rule双模选择** = 架构护栏约束AI行为 `→ §二`
2. **43种DecisionType + 43个Prompt模板** = 将开放式AI调用转化为结构化决策 `→ §二/§三`
3. **五层纵深防御 + 16种URL模式保护** = 参数安全的工程化保障 `→ §八`
4. **ToolPool预热(94%延迟降低) + 双模式执行** = 工具可用性的架构保证 `→ §八`
5. **10+配置段零硬编码 + 两级配置链** = 配置驱动的行为约束 `→ §二`
6. **双构建器Prompt架构(43模板11类) + KV Cache优化** = 结构化知识的工程化组织 `→ §三`
7. **620KB知识库文件体系 + TairVector 1024维三索引** = 多层结构化系统记忆 `→ §四`
8. **SelfTestEngine + TaskValidator + ExampleAutoDepositService** = Generator-Evaluator-Accumulator 三角闭环 `→ §五/§四`
9. **29次/编排决策链路追踪 + 18种SSE事件 + PerformanceMetrics** = 100%可观测可审计 `→ §十`
10. **ParamMappingEngine四阶段规则沉淀** = AI经验的确定性固化 `→ §九`
11. **Strangler Pattern + 4个委托服务 + 声明式别名** = 持续运行中的架构进化 `→ §九`
12. **DynamicRePlanningService + HeartbeatDaemon + OrchestrationPatternService** = 运行时自愈+主动巡检+经验积累+`triedCapabilities`已失败能力追踪防循环 `→ §八/§九`
13. **全链路 @Autowired(required=false) + TairCacheManager多级缓存** = 优雅降级抗熵 `→ §八`
14. **2,590+示例知识库（12领域） + 四层/八层/五阶渐进式匹配** = 领域级专家知识Harness `→ §四`
15. **API契约驱动 + 三维度验证 + Bug检测修复闭环** = 代码生成质量工程化保障 `→ §四/§五`
16. **Python动态代码 + FluidSynth渲染 + 策略代码安全验证** = AI能力边界工程化扩展 `→ §六`
17. **file-id文档锚定 + 专模型协同 + 跨模型上下文传递** = 文档知识库抗幻觉 `→ §四/§七`
18. **117种MCP工具 + 双传输协议 + 四层错误处理矩阵** = 外部能力的工程化接入+MCP→BROWSER_TOOL跨域降级映射+三层工具业务优先级 `→ §八/§十`
19. **5种通知通道 + 出入站对称设计 + SchedulerX分布式调度** = 企业级系统集成 `→ §十`
20. **六维进化飞轮（沉淀→记忆→检索→创建→反思→测试）** = Harness自身的持续进化 `→ §九`

> **原则→章节溯源**：每条原则后标注的章节编号即为其详细论述所在位置。20条原则分布在§二（编排基座，原则1/2/5）、§三（注意力编程，原则2/6）、§四（知识工程，原则7/8/14/15/17）、§五（质量闭环，原则8/15）、§六（双层分离，原则16）、§七（多模型协作，原则17）、§八（系统韧性，原则3/4/13）、§九（运行时进化，原则10/11/12/20）、§十（企业级工程，原则9/18/19）——每条原则都有对应的代码证据和跨域验证。

### 11.6 Harness架构全景

```
┌─────────────────────────────────────────────────────────────────┐
│                    AI Native系统 Harness                         │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  §2 编排基座（269个Service类）                              │   │
│  │  AdapterRegistry(10协议) + TwoLayerMatcher(L1+L2)        │   │
│  │  + 43种DecisionType + 8阶段流水线                          │   │
│  │  + ToolTierManager(三层工具业务优先级+Tier分级)            │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  §3 注意力编程（53个外部Prompt模板文件）                     │   │
│  │  43模板(11类) + 双构建器 + KV Cache 60%命中 + Tier分层     │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  §4 知识工程（12大领域）                                    │   │
│  │  620KB知识库 + 215能力注册表 + 2590+知识项                 │   │
│  │  + TairVector三索引 + 契约驱动 + 专模型协同抗幻觉          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  §5 质量闭环   §6 双层分离   §7 多模型协作（14领域全覆盖）  │   │
│  │  SelfTestEngine + TaskValidator + ExampleAutoDeposit      │   │
│  │  + Manim/OpenCV/Java2D/FluidSynth/MoveNet/Playwright     │   │
│  │  + 方言双模型/角色四模型                        │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  §8 系统韧性（7种恢复动作）                                 │   │
│  │  五层纵深防御 + 全链路optional + 动态重规划               │   │
│  │  + ToolPool预热 + 资源管理                                │   │
│  │  + CAPABILITY_FALLBACK_MAP三级降级 + BROWSER_TOOL错误码   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  §9 运行时自我进化（六维飞轮）                               │   │
│  │  HeartbeatDaemon + 参数规则沉淀 + 模式记忆                │   │
│  │  + Strangler Pattern渐进重构 + 设计理念映射                │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  §10 企业级工程实现（11项阿里云集成）                       │   │
│  │  MCP 117种工具 + 5种通知通道 + SchedulerX调度              │   │
│  │  + 数字资产闭环 + 全链路可观测性                            │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                 Qwen系列普通模型                             │   │
│  │  Qwen3-Max / Qwen-VL-Max / Qwen-Coder / QVQ-Max等       │   │
│  │  在Harness约束下做出43种结构化决策                         │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 最终等式

> **普通模型 + 精密Harness = 超越顶尖模型的系统级智能**

这不是模型能力的超越，而是**系统工程能力的超越**。AI Native系统 证明了 Harness Engineering 的核心论断：**模型之外的工程，才是决定AI应用最终表现的关键因素。**

---

## 附录：代码证据索引

| 组件 | 文件 | 行数 | 核心职责 |
|------|------|------|---------|
| **编排引擎核心** | | | |
| OrchestratorController | `controller/OrchestratorController.java` | 1873 | 编排API入口+17个REST端点+预览两阶段模式 |
| AdapterRegistry | `adapter/AdapterRegistry.java` | 288 | 10协议统一路由 |
| AIDecisionEngine | `engine/AIDecisionEngine.java` | 639 | 43种决策类型引擎 |
| DecisionType | `model/DecisionType.java` | 344 | 43种决策类型枚举 |
| TwoLayerMatcher | `service/TwoLayerMatcher.java` | 745 | L1+L2两层匹配 |
| ToolTierManager | `context/ToolTierManager.java` | ~276 | 3层工具信息披露+三层业务优先级 |
| TaskDecomposerService | `service/TaskDecomposerService.java` | ~1666 | 任务拆解+验证+修复+能力归一化 |
| AIExecutorEngine | `executor/AIExecutorEngine.java` | ~3519 | DAG执行+错误恢复+三级降级+BROWSER_TOOL错误码 |
| SelfTestEngine | `service/SelfTestEngine.java` | 592 | 3层自测试+AI评估 |
| DynamicRePlanningService | `service/DynamicRePlanningService.java` | ~593 | 动态重规划+7种恢复+triedCapabilities |
| HeartbeatDaemon | `service/HeartbeatDaemon.java` | 496 | AI自主心跳巡检 |
| MCPToolAdapter | `mcp/MCPToolAdapter.java` | 1155 | MCP双传输协议 |
| ToolPool | `service/ToolPool.java` | 534 | 预热+端口分配 |
| ExampleAutoDepositService | `service/ExampleAutoDepositService.java` | ~354 | 示例自动沉淀+深度输出失败检测 |
| ParamMappingEngine | `service/ParamMappingEngine.java` | 344 | 四阶段规则沉淀 |
| OrchestrationPatternService | `service/OrchestrationPatternService.java` | 480 | 编排模式记忆 |
| AIToolFactoryService | `service/AIToolFactoryService.java` | ~1986 | S4动态工具创建+模板缓存+FC双模式+冒烟修复 |
| Prompt模板（43个） | `orchestrator_knowledge/prompts/*.txt` | ~3000 | 结构化AI操作手册 |
| BrowserToolServiceManager | `adapter/BrowserToolServiceManager.java` | 643 | Python FastAPI进程管理+健康监控+自动重启 |
| IntentNegotiationService | `service/IntentNegotiationService.java` | 161 | S1前置意图协商+清晰度评分+SSE候选推送 |
| MultimodalInputEngine | `multimodal/MultimodalInputEngine.java` | 409 | S0多模态输入理解+PDS分享检测+并行文件理解+AI融合 |
| AIResultGeneratorService | `service/AIResultGeneratorService.java` | 708 | S6流式结果生成+骨架HTML+分块推送+模板缓存 |
| FrontendKnowledgeBridge | `service/FrontendKnowledgeBridge.java` | 271 | 结果可视化分析+数据类型映射+CDN推荐+前端示例注入 |
| **浏览器自动化层（Python）** | | | |
| browser_tool/app.py | `python/browser_tool/app.py` | 139 | FastAPI入口+10能力端点+会话管理端点 |
| browser_tool/site_presets.py | `python/browser_tool/site_presets.py` | 513 | 8大站点预设+反检测JS+选择器别名+资源拦截 |
| browser_tool/session_manager.py | `python/browser_tool/session_manager.py` | 158 | 会话池+反检测增强+TTL清理+Cookie注入 |
| browser_tool/models.py | `python/browser_tool/models.py` | 112 | Pydantic请求/响应模型+统一BrowserResponse |
| browser_tool/browser_manager.py | `python/browser_tool/browser_manager.py` | 65 | Playwright全局单例+反检测Chromium启动参数 |
| CapabilityTypeNormalizer | `service/CapabilityTypeNormalizer.java` | 90 | 浏览器能力类型归一化（28条静态映射） |
| AICapabilityMatcherService | `service/AICapabilityMatcherService.java` | 588 | AI能力匹配+三层后置校验 |
| TaskValidator | `service/TaskValidator.java` | 751 | 7项验证指标+通过标准 |
| TaskAutoFixer | `service/TaskAutoFixer.java` | 686 | 3轮渐进式修复+AI/规则双模 |
| AIExecutionMonitor | `monitor/AIExecutionMonitor.java` | 972 | AI错误恢复决策+7种RecoveryAction |
| RecoveryAction | `model/RecoveryAction.java` | 85 | 7种恢复动作枚举+canContinue语义 |
| ToolChainCompatibilityService | `service/ToolChainCompatibilityService.java` | 216 | JSON配置驱动工具链约束生成 |
| OrchestratorProgressService | `service/OrchestratorProgressService.java` | 456 | SSE事件推送+缓冲补发 |
| ProgressEventType | `model/ProgressEventType.java` | 97 | 18种SSE事件类型枚举 |
| ContextCompactor | `context/ContextCompactor.java` | 635 | 三级上下文压缩（截断/AI摘要/URL保留） |
| CapabilityRegistryService | `service/CapabilityRegistryService.java` | ~800 | 215种能力统一注册表（三维索引+四级查找降级+动态注册+Schema校验） |
| SearchPreJudgmentEngine | `onlineSearch/SearchPreJudgmentEngine.java` | 220 | LLM-as-Gate搜索前置判断 |
| **AI应用生成层** | | | |
| UnifiedContract | `smart/UnifiedContract.java` | ~548 | 三端一致性编码 |
| FrontendAIOrchestrationServiceImpl | `smart/FrontendAIOrchestrationServiceImpl.java` | ~1155 | 前端8层决策架构 |
| SmartValidationService | `smart/SmartValidationService.java` | 91 | 三维度质量验证 |
| layer4_javascript_generation.txt | `fullstack_knowledge/prompts/layer4_javascript_generation.txt` | 170 | JS代码生成Prompt+硬约束末尾集中+三端契约对齐+Attention边缘效应显式标注 |
| **游戏/音乐/体感层** | | | |
| SmartPromptAssembler | `game/prompt/SmartPromptAssembler.java` | 515 | 四层匹配+六段式Prompt |
| generation-prompt-template.md | `game_knowledge/prompts/generation-prompt-template.md` | 222 | 六段式Prompt标杆实现+21项Self Check+注意力权重全标注 |
| game-type-index.json | `game_knowledge/index/game-type-index.json` | 169 | L1类型索引：20类型×292示例+keywords关键词匹配+typicalMechanics交叉索引 |
| game-mechanics-index.json | `game_knowledge/index/game-mechanics-index.json` | 264 | L2机制索引：32机制+codePatterns代码约束+relatedMechanics交叉索引 |
| MusicGenerationService | `music/MusicGenerationService.java` | 2625 | 11阶段+7类Bug检测 |
| MusicTheorySelfChecker | `music/MusicTheorySelfChecker.java` | 1112 | 乐理自检+收敛检测+超时保护 |
| MotionCodeBugDetectionService | `motionGame/MotionCodeBugDetectionService.java` | 1019 | TF.js/姿态检测Bug检测+严重度分级 |
| ExampleKnowledgeBase | `service/ExampleKnowledgeBase.java` | 893 | 领域示例知识库+降级模板 |
| MotionGameGeneratorService | `motion/MotionGameGeneratorService.java` | 591 | 7步生成+6项验证 |
| **作业辅导/拍照层** | | | |
| ManimAnimationServiceV2 | `homework/ManimAnimationServiceV2.java` | ~502 | AI+Manim数学动画 |
| ExamAnnotationService | `homework/ExamAnnotationService.java` | ~451 | AI+OpenCV试卷标注 |
| PoseSkeletonGenerator | `camera/PoseSkeletonGenerator.java` | 283 | Java2D骨架图渲染 |
| **方言/角色/P图/视频层** | | | |
| DialectAsrWebSocketClient | `dialect/DialectAsrWebSocketClient.java` | 341 | 双WebSocket桥接 |
| ChatOrchestrationService(RP) | `service/ChatOrchestrationService.java` | ~370 | 四模型串行编排 |
| MaskImageProcessor | `util/MaskImageProcessor.java` | 875 | BFS洪水填充Mask |
| multimodal-input.html | `static/multimodal-input.html` | 3911 | P图双画布(canvas+sketchCanvas)+三份数据提交 |
| DualCanvas.js | `video/DualCanvas.js` | 770 | 双层画布+Mask生成 |
| **前端可视化与SSE消费层（JavaScript）** | | | |
| sse-client.js | `static/game/js/sse-client.js` | 311 | SSE前端消费+指数退避重连+心跳检测 |
| progress.js | `static/game/js/progress.js` | 487 | SSE事件前端渲染（进度条/阶段/子步骤） |
| **视频通话Python层** | | | |
| state_monitor.py | `python/video_call/state_monitor.py` | 660 | 8状态机+9项检查+自适应间隔+错误分类 |
| question_scheduler.py | `python/video_call/question_scheduler.py` | 777 | 策略加载器+线程化调度+保护属性 |
| video_call_client.py | `python/video_call/video_call_client.py` | 300 | WebSocket集成+StateMonitor+自动重连 |
| strategy_loader.py | `python/video_call/strategies/strategy_loader.py` | 147 | importlib动态导入+三级降级+热加载 |
| default_strategy.py | `python/video_call/strategies/default_strategy.py` | 141 | 默认兜底策略+动态间隔+对话阶段分段 |
| **企业级集成层** | | | |
| AssetContextService | `service/AssetContextService.java` | ~185 | 数字资产语义注入 |
| FormatAdaptationEngine | `service/FormatAdaptationEngine.java` | ~372 | 34种格式适配 |
| DuplicateDetectionService | `service/DuplicateDetectionService.java` | ~624 | 4级智能去重 |
| AndroidPackageService | `android/AndroidPackageService.java` | ~590 | 8阶段APK打包 |
| dag-visualizer.js | `static/dag-visualizer.js` | 637 | DAG可视化追踪 |
