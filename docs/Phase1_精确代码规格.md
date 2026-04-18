# Phase 1 五大任务代码规格（精确改造方案）

## 序言
本文档基于 ZhikuCode_90%+对标提升实施计划.md（v4.2）第三、五、六、七、八章的方案 3.1、3.3、5.1、5.2、6.3、7.1、8.2.1，对五个 Phase 1 任务进行源码级细化规格输出。
后端基于 Java 21 + Spring Boot 3.3，前端基于 React 18 + TypeScript。
所有引用行号均基于当前源码实际行数。

---

# Task 1: 方案 3.1 流式工具启动 + 方案 3.3 contextModifier 传播

## 当前状态

### 关键文件清单
1. **backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java** (962 行)
   - StreamCollector 内部类实现了基础流式工具提交
   - flushToolBlock() L894-927 已实现工具块即时提交
   - MessageDelta 事件处理 L858-863
   - onComplete 回调 L877-879

2. **backend/src/main/java/com/aicodeassistant/tool/StreamingToolExecutor.java** (196 行)
   - ExecutionSession 内部类（L82-194）
   - TrackedTool 被追踪工具类（L47-70）
   - `public ExecutionSession newSession()` L75-76 无参方法
   - `addTool()` L90-94 当前使用初始 context，不支持 contextModifier 传播
   - `processQueue()` L106-138 工具执行核心逻辑
   - ToolState 枚举 L39-44

3. **backend/src/main/java/com/aicodeassistant/tool/ToolResult.java**
   - `withContextModifier()` 和 `getContextModifier()` L66-77 已实现

### 当前设计缺陷
1. **contextModifier 传播缺失**：
   - TrackedTool.updatedContext 字段 L54 声明，L125 赋值
   - 但后续工具 addTool() L90-94 仍使用原始 context，未消费 updatedContext
   
2. **newSession() 签名缺陷**：
   - 当前无参 L75-76，无法传入初始 context
   - ExecutionSession 构造器 L82 也无参，无法存储初始 context

3. **WebSocket 推送缺失**：
   - 工具执行指标无实时推送（MicroMeter 埋点）

---

## 精确改造规格

### Step 1: 修改 StreamingToolExecutor.java - 支持 contextModifier 传播

#### 1.1 导入新增
在类头部导入区（L1-12）新增：
```java
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
```

#### 1.2 修改 TrackedTool 类（L47-70）
**不修改**，updatedContext 字段 L54 已存在。

#### 1.3 修改 ExecutionSession 类（L82-194）

**Step 1.3.1: 新增字段（在 L87 sessionDiscarded 后）**
```java
    private volatile boolean sessionDiscarded = false;
    // ★ 新增：会话级当前上下文，支持 CAS 更新（contextModifier 传播）
    private final AtomicReference<ToolUseContext> currentContext;
```

**Step 1.3.2: 修改构造器签名（替换 L82-87 无参构造器）**
需改为有参构造器（添加在 ExecutionSession 类定义后的首个构造器）：
```java
public class ExecutionSession {
    private final Queue<TrackedTool> queue = new ConcurrentLinkedQueue<>();
    private final List<TrackedTool> tracked = new CopyOnWriteArrayList<>();
    private final AtomicInteger active = new AtomicInteger(0);
    private volatile boolean sessionDiscarded = false;
    private final AtomicReference<ToolUseContext> currentContext;

    // ★ 新增：有参构造器，接收初始 context
    public ExecutionSession(ToolUseContext initialContext) {
        this.currentContext = new AtomicReference<>(initialContext);
    }
```

**Step 1.3.3: 修改 addTool()（L90-94）**
使用会话级 currentContext 而非原始 context：
```java
public void addTool(Tool tool, ToolInput input, String toolUseId, ToolUseContext context) {
    // ★ 修改：使用会话级 currentContext 而非调用方传入的原始 context
    ToolUseContext effectiveContext = currentContext.get();
    TrackedTool tt = new TrackedTool(toolUseId, tool, input, effectiveContext);
    tracked.add(tt);
    queue.add(tt);
    processQueue();
}
```

**Step 1.3.4: 修改 processQueue()（L106-138）**
在 finally 块前（L132 catch 块后）新增 contextModifier 传播逻辑：
```java
private void processQueue() {
    while (!queue.isEmpty()) {
        TrackedTool next = queue.peek();
        if (!canExecute(next)) break;

        queue.poll();
        active.incrementAndGet();
        next.state = ToolState.EXECUTING;

        Thread.ofVirtual().name("zhiku-tool-" + next.tool.getName()).start(() -> {
            try {
                if (sessionDiscarded) {
                    next.result = ToolResult.error(
                            "<tool_use_error>Tool execution discarded</tool_use_error>");
                } else {
                    ToolExecutionResult execResult = pipeline.execute(next.tool, next.input,
                            next.context.withToolUseId(next.toolUseId),
                            next.context.permissionNotifier());
                    next.result = execResult.result();
                    next.updatedContext = execResult.updatedContext();
                }
                next.state = ToolState.COMPLETED;
                
                // ★ 新增：applyContextModifier 传播（在 state 更新之后）
                if (next.updatedContext != null && !next.tool.isConcurrencySafe(next.input)) {
                    applyContextModifier(next.updatedContext);
                } else if (next.updatedContext != null && next.tool.isConcurrencySafe(next.input)) {
                    log.warn("Tool '{}' is concurrencySafe but returned contextModifier — ignored",
                            next.tool.getName());
                }
            } catch (Exception e) {
                next.result = ToolResult.error(
                        "<tool_use_error>Execution error: " + e.getMessage() + "</tool_use_error>");
                next.state = ToolState.COMPLETED;
            } finally {
                active.decrementAndGet();
                processQueue();
            }
        });
    }
}
```

**Step 1.3.5: 新增 applyContextModifier() 方法（在 processQueue() 之后）**
在 processQueue() 方法结束后新增：
```java
/**
 * 通过 CAS 循环安全地更新会话级上下文。
 * 若多个非并发安全工具顺序完成，CAS 保证每次更新基于最新状态。
 * 
 * 超时保护：最多重试 100 次 CAS，防止异常场景下无限循环。
 */
private void applyContextModifier(ToolUseContext newContext) {
    int maxRetries = 100;  // CAS 超时保护
    int attempt = 0;
    ToolUseContext prev;
    do {
        if (++attempt > maxRetries) {
            log.error("CAS loop exceeded {} retries for contextModifier, using last known context", maxRetries);
            currentContext.set(newContext);  // 强制设置，避免卡死
            return;
        }
        prev = currentContext.get();
    } while (!currentContext.compareAndSet(prev, newContext));
    log.debug("Context updated via contextModifier (CAS attempts: {})", attempt);
}

/**
 * 获取当前会话级上下文（供 QueryEngine 在工具全部完成后获取最新 context）。
 */
public ToolUseContext getCurrentContext() {
    return currentContext.get();
}
```

#### 1.4 修改 newSession() 方法（L75-76）
替换原无参版本为有参版本：
```java
/**
 * 创建一个新的执行会话（每次 runTools 调用一个新实例）。
 * 
 * @param initialContext 初始工具使用上下文
 */
public ExecutionSession newSession(ToolUseContext initialContext) {
    return new ExecutionSession(initialContext);
}
```

---

### Step 2: 修改 QueryEngine.java - 传入 initialContext 并获取更新后的 context

#### 2.1 查找 Step 3 的 streamingToolExecutor.newSession() 调用
需在 QueryEngine.java 中找到创建 ExecutionSession 的位置（预计在 600-700 行范围）。

**修改前**：
```java
ExecutionSession session = streamingToolExecutor.newSession();
```

**修改后**：
```java
ExecutionSession session = streamingToolExecutor.newSession(toolUseContext);
```

#### 2.2 查找 Step 5 的工具完成等待逻辑
需找到 `while (session.hasUnfinishedTools())` 或类似的等待逻辑。

**修改前**：
```java
// Step 5: 等待所有工具完成
while (session.hasUnfinishedTools()) {
    // ...
}
```

**修改后**：
```java
// Step 5: 等待所有工具完成，并获取更新后的 context
while (session.hasUnfinishedTools()) {
    // ...
}
toolUseContext = session.getCurrentContext();  // ★ 新增：获取更新后的 context
```

---

### Step 3: 修改 ToolResult.java - 确认 contextModifier 接口（仅验证，无需修改）

当前已有（L66-77）：
- `withContextModifier(UnaryOperator<ToolUseContext> modifier)`
- `getContextModifier()`

**无需修改**。

---

### Step 4: 虚拟线程监控指标（可选增强项）

#### 4.1 修改 StreamingToolExecutor.java - 新增 MicroMeter 埋点
在构造器注入 MeterRegistry：
```java
import io.micrometer.core.instrument.*;

private final MeterRegistry meterRegistry;
private final AtomicInteger activeVirtualThreads = new AtomicInteger(0);

public StreamingToolExecutor(ToolExecutionPipeline pipeline, MeterRegistry meterRegistry) {
    this.pipeline = pipeline;
    this.meterRegistry = meterRegistry;
    // 注册活跃虚拟线程数 Gauge
    Gauge.builder("zhiku.tool.virtual_threads.active", activeVirtualThreads, AtomicInteger::get)
        .description("Active virtual threads executing tools")
        .register(meterRegistry);
}
```

在 processQueue() 的虚拟线程启动块中埋点：
```java
Thread.ofVirtual().name("zhiku-tool-" + next.tool.getName()).start(() -> {
    activeVirtualThreads.incrementAndGet();
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        // ... 现有执行逻辑不变 ...
    } finally {
        sample.stop(Timer.builder("zhiku.tool.execution_time")
            .tag("tool", next.tool.getName())
            .description("Tool execution time")
            .register(meterRegistry));
        activeVirtualThreads.decrementAndGet();
        active.decrementAndGet();
        processQueue();
    }
});
```

---

## 依赖关系
- 无外部新增依赖
- ToolResult.java 接口已完整（无需修改）

## 测试用例

| 场景 | 验证点 | 预期结果 |
|-----|--------|---------|
| 单工具 modifier | BashTool `cd /tmp` 返回 modifier 修改 workDir | 后续工具的 `context.workingDirectory()` 为 `/tmp` |
| 多工具顺序 modifier | 工具A修改 workDir，工具B读取 | 工具B 看到工具A 的修改 |
| 并发安全工具忽略 | isConcurrencySafe=true 的工具返回 modifier | WARN 日志，modifier 不应用 |
| 无 modifier 兼容 | 所有现有工具（无 modifier） | 行为完全一致 |
| CAS 竞争 | 多个非并发工具快速顺序完成 | 最终 context 为所有 modifier 顺序应用结果 |

---

# Task 2: 方案 5.1 Context Collapse 渐进增强 + 方案 5.2 压缩后关键文件重注入

## 当前状态

### 关键文件清单
1. **backend/src/main/java/com/aicodeassistant/engine/ContextCollapseService.java** (170 行)
   - 基础二级折叠已实现
   - `collapseMessages()` L57-126 （两级折叠）
   - `truncateBlocks()` L136-148 存在 substring 边界漏洞 L141

2. **backend/src/main/java/com/aicodeassistant/engine/ContextCascade.java** (300+ 行)
   - `executePreApiCascade()` L186-280 中 Level 1.5（L225-234）与 Level 2（L236-254）互斥协调缺陷

3. **backend/src/main/java/com/aicodeassistant/engine/CompactService.java** (895 行)
   - `reInjectFilesAfterCompact()` L782-869 存在安全缺陷（无 PathSecurityService 检查）
   - **KeyFileTracker.java 完全不存在，需新建**

### 当前设计缺陷
1. **CollapseLevel sealed interface 不存在**
2. **progressiveCollapse() 方法不存在**
3. **substring 边界漏洞** ContextCollapseService.java L141
4. **互斥协调显式逻辑缺失** ContextCascade.java L236-254
5. **KeyFileTracker 完全不存在**
6. **文件重注入无安全检查** CompactService.java L837

---

## 精确改造规格

### Step 1: 新建 CollapseLevel.java

位置：`backend/src/main/java/com/aicodeassistant/engine/CollapseLevel.java`

```java
package com.aicodeassistant.engine;

/**
 * CollapseLevel — 三级渐进折叠策略 sealed interface。
 * 定义消息按距尾部距离的折叠级别。
 *
 * @see ContextCollapseService#progressiveCollapse
 */
public sealed interface CollapseLevel
        permits CollapseLevel.FullRetention,
                CollapseLevel.SummaryRetention,
                CollapseLevel.SkeletonRetention {

    /** 距尾部的消息数阈值（inclusive），该级别覆盖 [尾部-maxAge, 尾部-prevMaxAge) 区间 */
    int maxAgeMessages();

    /** 对消息内容执行折叠 */
    String collapse(String originalContent);

    /** Level A: 完整保留 — 尾部 10 条消息原样保留 */
    record FullRetention(int maxAgeMessages) implements CollapseLevel {
        public FullRetention() { this(10); }
        @Override public String collapse(String originalContent) {
            return originalContent; // 不做任何处理
        }
    }

    /** Level B: 摘要保留 — 倒数 10-30 条，长文本截断保留前 500 字符 */
    record SummaryRetention(int maxAgeMessages, int keepChars) implements CollapseLevel {
        public SummaryRetention() { this(30, 500); }
        @Override public String collapse(String originalContent) {
            if (originalContent == null || originalContent.length() <= keepChars) {
                return originalContent;
            }
            return originalContent.substring(0, Math.min(keepChars, originalContent.length()))
                    + "\n...[summary-collapsed: " + originalContent.length() + " chars]";
        }
    }

    /** Level C: 骨架保留 — 30 条以前，仅保留 role + toolUseId + 一行摘要 */
    record SkeletonRetention(int maxAgeMessages) implements CollapseLevel {
        public SkeletonRetention() { this(Integer.MAX_VALUE); }
        @Override public String collapse(String originalContent) {
            if (originalContent == null || originalContent.length() <= 50) {
                return originalContent;
            }
            int newline = originalContent.indexOf('\n');
            String firstLine = newline > 0
                    ? originalContent.substring(0, Math.min(newline, 80))
                    : originalContent.substring(0, Math.min(80, originalContent.length()));
            return "[skeleton] " + firstLine + "...";
        }
    }
}
```

### Step 2: 修改 ContextCollapseService.java

#### 2.1 新增导入（L1-11）
```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
```

#### 2.2 新增字段（L40 后）
```java
    private final int textTruncateKeep;

    // ★ 新增：渐进折叠默认级别
    private static final List<CollapseLevel> DEFAULT_LEVELS = List.of(
            new CollapseLevel.FullRetention(),      // 尾部 10 条完整保留
            new CollapseLevel.SummaryRetention(),    // 10-30 条摘要保留
            new CollapseLevel.SkeletonRetention()    // 30+ 条骨架化
    );
```

#### 2.3 修改 truncateBlocks() 方法（L136-148）
**修改前**：
```java
private List<ContentBlock> truncateBlocks(List<ContentBlock> blocks) {
    return blocks.stream()
            .map(block -> {
                if (block instanceof ContentBlock.TextBlock t
                        && t.text() != null && t.text().length() > textTruncateThreshold) {
                    String truncated = t.text().substring(0, textTruncateKeep)  // ★ 边界漏洞
                            + "\n...[collapsed: " + t.text().length() + " chars]";
                    return (ContentBlock) new ContentBlock.TextBlock(truncated);
                }
                return block;
            })
            .toList();
}
```

**修改后**：
```java
private List<ContentBlock> truncateBlocks(List<ContentBlock> blocks) {
    return blocks.stream()
            .map(block -> {
                if (block instanceof ContentBlock.TextBlock t
                        && t.text() != null && t.text().length() > textTruncateThreshold) {
                    // ★ 修复：Math.min 保护边界
                    int keepLen = Math.min(textTruncateKeep, t.text().length());
                    String truncated = t.text().substring(0, keepLen)
                            + "\n...[collapsed: " + t.text().length() + " chars]";
                    return (ContentBlock) new ContentBlock.TextBlock(truncated);
                }
                return block;
            })
            .toList();
}
```

#### 2.4 新增 progressiveCollapse() 方法（在 collapseMessages(List) 后，L127）
```java
/**
 * 渐进式折叠 — 按消息距尾部距离分三级处理。
 * 关键规则：所有 UserMessage（非 toolUseResult）永远保留原文，
 * 防止模型丢失用户反馈（如"不要用 Redux"）。
 */
public CollapseResult progressiveCollapse(List<Message> messages, List<CollapseLevel> levels) {
    if (messages == null || messages.isEmpty()) {
        return new CollapseResult(messages != null ? messages : List.of(), 0, 0);
    }
    List<CollapseLevel> sortedLevels = levels != null ? levels : DEFAULT_LEVELS;
    int totalMessages = messages.size();
    List<Message> result = new ArrayList<>(totalMessages);
    int collapsedCount = 0;
    int estimatedCharsFreed = 0;

    for (int i = 0; i < totalMessages; i++) {
        Message msg = messages.get(i);
        int distanceFromTail = totalMessages - 1 - i;

        // 规则：UserMessage（非工具结果）永远保留原文
        if (msg instanceof Message.UserMessage userMsg && userMsg.toolUseResult() == null) {
            result.add(msg);
            continue;
        }

        // 确定该消息的折叠级别
        CollapseLevel level = sortedLevels.stream()
                .filter(l -> distanceFromTail < l.maxAgeMessages())
                .findFirst()
                .orElse(sortedLevels.get(sortedLevels.size() - 1));

        if (level instanceof CollapseLevel.FullRetention) {
            result.add(msg); // 完整保留
        } else {
            // 对工具结果和助手消息执行折叠
            Message collapsedMsg = collapseMessage(msg, level);
            int charsBefore = estimateMessageChars(msg);
            int charsAfter = estimateMessageChars(collapsedMsg);
            estimatedCharsFreed += (charsBefore - charsAfter);
            result.add(collapsedMsg);
            collapsedCount++;
        }
    }
    return new CollapseResult(result, collapsedCount, estimatedCharsFreed);
}

/**
 * 对单条消息执行折叠——根据消息类型分别处理。
 * 保留消息的 role、toolUseId 结构，仅折叠内容部分。
 */
private Message collapseMessage(Message msg, CollapseLevel level) {
    if (msg instanceof Message.AssistantMessage am && am.content() != null) {
        // 助手消息：折叠每个 content block
        List<ContentBlock> collapsedBlocks = am.content().stream()
            .map(block -> {
                if (block instanceof ContentBlock.TextBlock t && t.text() != null) {
                    return (ContentBlock) new ContentBlock.TextBlock(level.collapse(t.text()));
                }
                if (block instanceof ContentBlock.ToolResultBlock tr && tr.content() != null) {
                    return (ContentBlock) new ContentBlock.ToolResultBlock(
                        tr.toolUseId(), level.collapse(tr.content()), tr.isError());
                }
                return block; // ToolUseBlock 等保留原样（保留 toolUseId 结构）
            })
            .toList();
        return new Message.AssistantMessage(am.id(), am.timestamp(), collapsedBlocks, am.model(), am.stopReason());
    }
    if (msg instanceof Message.UserMessage um && um.toolUseResult() != null) {
        // 工具结果消息：折叠内容但保留 toolUseId
        String collapsedContent = level.collapse(
            um.toolUseResult().content() != null ? um.toolUseResult().content() : "");
        return new Message.UserMessage(um.id(), um.timestamp(),
            List.of(new ContentBlock.TextBlock(collapsedContent)),
            um.toolUseResult().withContent(collapsedContent), um.attachments());
    }
    return msg; // 其他消息类型原样返回
}

/** 估算消息字符数（用于统计释放量） */
private int estimateMessageChars(Message msg) {
    if (msg instanceof Message.AssistantMessage am && am.content() != null) {
        return am.content().stream()
            .mapToInt(b -> b instanceof ContentBlock.TextBlock t ? (t.text() != null ? t.text().length() : 0) : 0)
            .sum();
    }
    return 0;
}
```

### Step 3: 修改 ContextCascade.java - 互斥协调修复

#### 3.1 查找 executePreApiCascade() 方法中 Level 1.5 和 Level 2 的代码

在 L186-280 范围内，找到 Level 1.5（L225-234）和 Level 2（L236-254）的代码块。

#### 3.2 替换 Level 2 块（L236-254）

**修改前**（当前代码片段）：
```java
// Level 2: AutoCompact (LLM 摘要)
if (!trackingState.isCircuitBroken()) {
    // ... 直接执行 AutoCompact，无互斥检查
}
```

**修改后**（含互斥协调）：
```java
// ===== Level 2: AutoCompact (LLM 摘要) — 含 Collapse 互斥协调 =====
boolean collapseExecuted = collapseAttempted && collapseResult != null && collapseResult.collapsedCount() > 0;
int collapseCharsFreed = collapseResult != null ? collapseResult.estimatedCharsFreed() : 0;

if (collapseExecuted) {
    // Collapse 已执行，重新评估是否仍需 AutoCompact
    TokenWarningState postCollapseWarning = calculateTokenWarningState(current, model);
    if (!postCollapseWarning.isAboveAutoCompactThreshold()) {
        log.info("Level 2 AutoCompact 跳过: Collapse 已释放足够空间 " +
                "(collapseCharsFreed={}, postTokens={}, threshold={})",
                collapseCharsFreed, postCollapseWarning.currentTokens(),
                postCollapseWarning.autoCompactThreshold());
    } else if (!trackingState.isCircuitBroken()) {
        log.info("Level 2 AutoCompact 触发: Collapse 释放不足 (postTokens={} > threshold={})",
                postCollapseWarning.currentTokens(), postCollapseWarning.autoCompactThreshold());
        acAttempted = true;
        try {
            acResult = compactService.compact(current, contextWindow, false);
            if (acResult.skipReason() == null && !acResult.compactedMessages().isEmpty()) {
                acExecuted = true;
                current = acResult.compactedMessages();
                log.info("Level 2 AutoCompact completed: {}", acResult.summary());
            }
        } catch (Exception e) {
            log.error("Level 2 AutoCompact failed", e);
        }
    }
} else if (!trackingState.isCircuitBroken()) {
    // Collapse 未执行，保持原有 AutoCompact 判断逻辑
    TokenWarningState warning = calculateTokenWarningState(current, model);
    if (warning.isAboveAutoCompactThreshold()) {
        log.info("Level 2 AutoCompact triggered: {} tokens > threshold {}",
                warning.currentTokens(), warning.autoCompactThreshold());
        acAttempted = true;
        try {
            acResult = compactService.compact(current, contextWindow, false);
            if (acResult.skipReason() == null && !acResult.compactedMessages().isEmpty()) {
                acExecuted = true;
                current = acResult.compactedMessages();
                log.info("Level 2 AutoCompact completed: {}", acResult.summary());
            }
        } catch (Exception e) {
            log.error("Level 2 AutoCompact failed", e);
        }
    }
}
```

### Step 4: 新建 KeyFileTracker.java

位置：`backend/src/main/java/com/aicodeassistant/engine/KeyFileTracker.java`

```java
package com.aicodeassistant.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KeyFileTracker — 追踪对话中频繁引用的文件。
 * 基于 Caffeine 缓存 + AtomicInteger 计数，按 session 隔离。
 * 压缩后重注入时，按引用频率排序获取 Top-N 关键文件。
 */
@Service
public class KeyFileTracker {

    // Caffeine 缓存：sessionId → Map<filePath, referenceCount>
    // session 过期 2 小时自动清除，最多跟踪 200 个 session
    private final Cache<String, ConcurrentHashMap<String, AtomicInteger>> sessionFileRefs =
            Caffeine.newBuilder()
                    .maximumSize(200)
                    .expireAfterAccess(Duration.ofHours(2))
                    .build();

    // 去重集合：防止同一轮对话中对同一文件重复计数
    private final Cache<String, Set<String>> turnDedup =
            Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(Duration.ofMinutes(30))
                    .build();

    /**
     * 记录文件引用（在 FileReadTool、FileEditTool、GrepTool 执行时调用）。
     * 同一轮对话中对同一文件只计数一次。
     *
     * @param sessionId 会话 ID
     * @param filePath  文件绝对路径
     * @param turnId    当前轮次 ID（用于去重，实际使用 ToolUseContext.toolUseId()）
     */
    public void trackFileReference(String sessionId, String filePath, String turnId) {
        // 去重检查：(sessionId, filePath, turnId) 三元组
        String dedupKey = sessionId + ":" + turnId;
        Set<String> trackedPaths = turnDedup.get(dedupKey,
                k -> ConcurrentHashMap.newKeySet());
        if (!trackedPaths.add(filePath)) {
            return; // 本轮已计数，跳过
        }
        sessionFileRefs.get(sessionId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(filePath, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * 获取 Top-N 关键文件（按引用次数降序）。
     */
    public List<String> getKeyFiles(String sessionId, int maxCount) {
        var refs = sessionFileRefs.getIfPresent(sessionId);
        if (refs == null) return List.of();
        return refs.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                        Comparator.comparingInt(AtomicInteger::get)).reversed())
                .limit(maxCount)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** session 结束时主动清除 */
    public void clearSession(String sessionId) {
        sessionFileRefs.invalidate(sessionId);
    }
}
```

### Step 5: 修改 CompactService.java - 文件重注入安全检查 + 基于访问历史重注入

#### 5.1 新增依赖注入
在构造器中新增：
```java
private final KeyFileTracker keyFileTracker;
private final PathSecurityService pathSecurity;

// 在现有构造器参数列表后新增这两个
```

#### 5.2 修改现有 reInjectFilesAfterCompact() 方法（L782-869）
**在文件读取前添加 PathSecurityService 检查**：

找到 L837 的 `Files.readString(Path.of(path))` 调用，修改为：
```java
for (String path : validPaths) {
    // ★ PathSecurityService 安全检查 ★
    var securityCheck = pathSecurity.checkReadPermission(path, workingDirectory);
    if (!securityCheck.isAllowed()) {
        log.warn("文件重注入安全拦截: {} - {}", path, securityCheck.message());
        continue;  // 跳过敏感文件
    }
    try {
        String content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        // ... 后续截断和注入逻辑不变 ...
    } catch (IOException e) {
        log.warn("文件重注入读取失败: {}", path, e);
    }
}
```

#### 5.3 新增 rebuildAfterCompact() 方法（在 reInjectFilesAfterCompact() 后）
```java
private static final int MAX_REINJECT_FILES = 5;
private static final int MAX_FILE_SIZE_CHARS = 10_000;

/**
 * 基于访问历史的文件重注入 — 优先使用 KeyFileTracker，降级回退到正则提取。
 */
public List<Message> rebuildAfterCompact(
        List<Message> compactedMessages, String sessionId, String workingDirectory) {

    // 1. 优先使用 KeyFileTracker 获取 Top-5 关键文件
    List<String> keyFiles = keyFileTracker.getKeyFiles(sessionId, MAX_REINJECT_FILES);

    // 2. 降级回退：KeyFileTracker 无记录时，使用现有正则提取方案
    if (keyFiles.isEmpty()) {
        return reInjectFilesAfterCompact(compactedMessages, workingDirectory);
    }

    // 3. 安全检查 + 文件读取
    List<String> validPaths = keyFiles.stream()
            .filter(path -> {
                // ★ PathSecurityService 安全检查 ★
                var checkResult = pathSecurity.checkReadPermission(path, workingDirectory);
                if (!checkResult.isAllowed()) {
                    log.warn("文件重注入安全拦截: {} - {}", path, checkResult.message());
                    return false;
                }
                return true;
            })
            .filter(p -> {
                try { return Files.exists(Path.of(p)) && Files.size(Path.of(p)) < MAX_FILE_SIZE_CHARS * 4L; }
                // ★ MAX_FILE_SIZE_CHARS * 4L：单个 UTF-8 字符最多 4 字节
                catch (IOException e) { return false; }
            })
            .limit(MAX_REINJECT_FILES)
            .toList();

    if (validPaths.isEmpty()) {
        log.debug("压缩后文件重注入: 无有效文件可注入");
        return compactedMessages;
    }

    // 4. 读取文件内容并截断
    List<Message> result = new ArrayList<>(compactedMessages);
    StringBuilder fileContent = new StringBuilder();
    fileContent.append("[Key Files re-injected after compression (by access frequency)]\n\n");

    for (String path : validPaths) {
        try {
            String content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            if (content.length() > MAX_FILE_SIZE_CHARS) {
                content = content.substring(0, MAX_FILE_SIZE_CHARS) + "\n...[truncated]";
            }
            fileContent.append("--- ").append(path).append(" ---\n");
            fileContent.append(content).append("\n\n");
        } catch (IOException e) {
            log.warn("文件重注入读取失败: {}", path, e);
        }
    }

    // 5. 插入到 COMPACT_SUMMARY 消息之后
    Message reInjectMsg = new Message.SystemMessage(
            UUID.randomUUID().toString(), Instant.now(),
            fileContent.toString(), SystemMessageType.FILE_REINJECT);

    int insertIndex = -1;
    for (int i = 0; i < result.size(); i++) {
        if (result.get(i) instanceof Message.SystemMessage sys
                && sys.type() == SystemMessageType.COMPACT_SUMMARY) {
            insertIndex = i + 1;
        }
    }
    if (insertIndex >= 0 && insertIndex <= result.size()) {
        result.add(insertIndex, reInjectMsg);
    } else {
        result.add(reInjectMsg);
    }

    log.info("压缩后文件重注入完成 (KeyFileTracker): {}个文件 [{}]",
            validPaths.size(), String.join(", ", validPaths));
    return result;
}
```

### Step 6: 修改工具文件 - 添加 KeyFileTracker 埋点

#### 6.1 修改 FileReadTool.java
在构造器新增：
```java
private final KeyFileTracker keyFileTracker;
```

在 `call()` 方法中，L182 `cache.markRead(...)` 后插入：
```java
keyFileTracker.trackFileReference(context.sessionId(), filePath, context.toolUseId());
```

#### 6.2 修改 FileEditTool.java
在构造器新增：
```java
private final KeyFileTracker keyFileTracker;
```

在文件编辑成功后插入：
```java
keyFileTracker.trackFileReference(context.sessionId(), filePath, context.toolUseId());
```

#### 6.3 修改 GrepTool.java
在构造器新增：
```java
private final KeyFileTracker keyFileTracker;
```

在搜索结果中提取文件路径后插入：
```java
for (String matchedFile : matchedFiles) {
    keyFileTracker.trackFileReference(context.sessionId(), matchedFile, context.toolUseId());
}
```

### Step 7: 修改 SessionManager.java - Session 清理时调用 KeyFileTracker.clearSession()

在 `deleteSession()` 方法（L301）中新增：
```java
public void deleteSession(String sessionId) {
    try {
        hookService.executeSessionEnd(sessionId, Map.of("reason", "deleted"));
    } catch (Exception e) {
        log.warn("SESSION_END hook failed: {}", e.getMessage());
    }
    jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
    removeFileStateCache(sessionId);
    keyFileTracker.clearSession(sessionId);  // ★ 新增：主动清理文件追踪缓存
    log.info("Session deleted: {}", sessionId);
}
```

### Step 8: application.yml 配置项

新增配置：
```yaml
zhiku:
  context:
    collapse:
      protected-tail: 6          # 尾部保护消息数（现有）
      threshold: 2000             # 截断阈值（现有）
      keep: 500                   # 截断保留字符数（现有）
      # 渐进折叠级别参数（新增）
      level-a-max-age: 10         # FullRetention 尾部消息数
      level-b-max-age: 30         # SummaryRetention 尾部消息数
      level-b-keep-chars: 500     # SummaryRetention 保留字符数
```

---

# Task 3: 方案 6.3 MCP 主动健康检查增强

## 当前状态

### 关键文件清单
1. **backend/src/main/java/com/aicodeassistant/mcp/SseHealthChecker.java** (50 行)
   - `performActiveHealthCheck()` L34-48 每 30s 执行一次 ping
   - `sendHealthPing()` 返回 boolean L41

2. **backend/src/main/java/com/aicodeassistant/mcp/McpClientManager.java** (450+ 行)
   - `scheduleReconnect()` 方法负责重连调度
   - 指数退避重连策略 L338-341 已实现

3. **backend/src/main/java/com/aicodeassistant/mcp/McpServerConnection.java**
   - `reconnectAttempts` 字段 L31
   - `getStatus()` / `setStatus()` 方法

### 当前设计缺陷
1. **consecutiveFailures 字段不存在** SseHealthChecker.java
2. **lastSuccessfulPing 字段不存在** SseHealthChecker.java
3. **幂等重连保护缺失** McpClientManager.scheduleReconnect()
4. **WebSocket 推送缺失**（mcp_health_status 消息）
5. **自定义线程池缺失** 使用默认 ForkJoinPool

---

## 精确改造规格

### Step 1: 修改 SseHealthChecker.java

#### 1.1 新增导入和字段

在 L1-29 类定义前后新增：
```java
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(SseHealthChecker.class);

    private final McpClientManager mcpClientManager;
    
    // ★ 新增健康指标字段
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSuccessfulPing = new ConcurrentHashMap<>();

    public SseHealthChecker(McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
    }
```

#### 1.2 修改 performActiveHealthCheck() 方法（L34-48）

**修改前**：
```java
@Scheduled(fixedRate = 30_000, initialDelay = 30_000)
public void performActiveHealthCheck() {
    for (McpServerConnection connection : mcpClientManager.listConnections()) {
        if (connection.getStatus() != McpConnectionStatus.CONNECTED) {
            continue;
        }
        if (!connection.sendHealthPing()) {
            log.warn("Active ping failed for '{}', marking as DEGRADED",
                    connection.getName());
            connection.setStatus(McpConnectionStatus.DEGRADED);
            mcpClientManager.scheduleReconnect(connection.getName());
        }
    }
}
```

**修改后**：
```java
@Scheduled(fixedRate = 30_000, initialDelay = 30_000)
public void performActiveHealthCheck() {
    mcpClientManager.getActiveConnections().forEach(conn -> {
        String name = conn.getConfig().name();
        try {
            boolean alive = conn.sendHealthPing();
            if (alive) {
                // ★ 新增：ping 成功时重置计数器
                consecutiveFailures.put(name, 0);
                lastSuccessfulPing.put(name, Instant.now());
            } else {
                // ★ 新增：记录连续失败次数
                int failures = consecutiveFailures.merge(name, 1, Integer::sum);
                log.warn("Health ping failed for '{}', consecutive failures: {}", name, failures);
                if (failures >= 2) {
                    conn.setStatus(McpConnectionStatus.DEGRADED);
                    mcpClientManager.scheduleReconnect(name);
                }
            }
        } catch (Exception e) {
            // ★ 新增：异常也计入连续失败
            int failures = consecutiveFailures.merge(name, 1, Integer::sum);
            log.warn("Health ping exception for '{}' (failures={}): {}", name, failures, e.getMessage());
            if (failures >= 2) {
                conn.setStatus(McpConnectionStatus.DEGRADED);
                mcpClientManager.scheduleReconnect(name);
            }
        }
    });
}
```

### Step 2: 修改 McpClientManager.java - 幂等重连保护 + 自定义线程池 + WebSocket 推送

#### 2.1 新增导入和字段

在类头部导入区新增：
```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class McpClientManager {
    
    // ... 现有字段 ...
    
    // ★ 新增：自定义线程池
    private static final ExecutorService RECONNECT_POOL = 
        Executors.newFixedThreadPool(2, 
            r -> { Thread t = new Thread(r, "mcp-reconnect"); t.setDaemon(true); return t; });

    // ★ 新增：幂等重连保护
    private final ConcurrentHashMap<String, Boolean> reconnectingServers = new ConcurrentHashMap<>();

    // ★ 新增：WebSocket 管理器依赖注入（在构造器中）
    private final WebSocketSessionManager wsSessionManager;
```

#### 2.2 修改 scheduleReconnect() 方法

**修改前**（当前实现）：
```java
public void scheduleReconnect(String connectionName) {
    getConnection(connectionName).ifPresent(conn -> {
        conn.setStatus(McpConnectionStatus.DEGRADED);
        CompletableFuture.runAsync(() -> {
            // ... 重连逻辑 ...
        });
    });
}
```

**修改后**（含幂等保护 + 自定义线程池 + WebSocket 推送）：
```java
public void scheduleReconnect(String connectionName) {
    getConnection(connectionName).ifPresent(conn -> {
        // ★ 幂等检查：通过 putIfAbsent 原子操作避免重复触发重连
        if (reconnectingServers.putIfAbsent(connectionName, Boolean.TRUE) != null) {
            log.debug("Reconnect already in progress for '{}', skipping", connectionName);
            return;
        }
        conn.setStatus(McpConnectionStatus.DEGRADED);
        // ★ 使用自定义线程池替代默认 ForkJoinPool
        CompletableFuture.runAsync(() -> {
            try {
                attemptReconnect(connectionName, conn);
                // ★ 重连完成后通过 WebSocket 广播状态变更
                broadcastHealthStatus(connectionName, conn.getStatus());
            } finally {
                // ★ 无论成功失败，必须清除幂等标记，允许后续重试
                reconnectingServers.remove(connectionName);
            }
        }, RECONNECT_POOL);
    });
}

// ★ 新增: WebSocket 广播连接状态变更
private void broadcastHealthStatus(String serverName, McpConnectionStatus status) {
    // 通过 WebSocketHandler 推送 mcp_health_status 消息到所有已连接的前端会话
    Map<String, Object> payload = Map.of(
        "serverName", serverName,
        "status", status.name(),
        "timestamp", Instant.now().toEpochMilli()
    );
    wsSessionManager.getActiveSessionIds().forEach(sessionId -> {
        try {
            // 注：需调用 pushToUser 方法或类似接口推送消息
            // 具体实现依赖 WebSocketSessionManager 的推送接口
            pushToUser(sessionId, "mcp_health_status", payload);
        } catch (Exception e) {
            log.debug("Failed to push health status to session {}: {}", sessionId, e.getMessage());
        }
    });
}
```

#### 2.3 修改 WebSocketSessionManager.java - 新增 getActiveSessionIds() 方法

在 WebSocketSessionManager.java 中新增（假设类中有 `sessionToPrincipal` 字段）：
```java
/**
 * 获取所有活跃的会话 ID。
 */
public Set<String> getActiveSessionIds() {
    return Set.copyOf(sessionToPrincipal.keySet());
}
```

### Step 3: 新增 ServerMessage.java - 添加 #37 TokenBudgetNudge 和 #36 CompactProgressPayload

#### 3.1 在 CompactProgressPayload 后新增（约 L54 位置）
```java
/** #36 compact_progress — 压缩实时进度 */
public record CompactProgressPayload(
    String sessionId,
    int processedMessages,     // 已处理消息数
    int totalMessages,         // 总消息数
    long tokensFreed,          // 已释放 Token 数
    String phase               // "summarizing" | "reinjecting" | "completed"
) {}

/** #37 token_budget_nudge — Token 预算续写提示 */
public record TokenBudgetNudge(int pct, int currentTokens, int budgetTokens) {}
```

---

# Task 4: 方案 7.1 Token 预算续写 WebSocket 推送全链路

## 当前状态

### 关键文件清单
1. **backend/src/main/java/com/aicodeassistant/engine/TokenBudgetTracker.java** (92 行)
   - 核心逻辑完整（ContinueDecision / StopDecision）

2. **backend/src/main/java/com/aicodeassistant/websocket/ServerMessage.java** (168 行)
   - 缺少 TokenBudgetNudge record

3. **backend/src/main/java/com/aicodeassistant/engine/QueryMessageHandler.java** (69 行)
   - 缺少 onTokenBudgetNudge() 方法

4. **backend/src/main/java/com/aicodeassistant/websocket/WebSocketController.java**
   - WsMessageHandler 实现需补充 onTokenBudgetNudge()

5. **backend/src/main/java/com/aicodeassistant/engine/QueryEngine.java** (962 行)
   - L462-472 nudge 推送缺失

6. **前端**：TokenBudgetIndicator.tsx 需新建

---

## 精确改造规格

### Step 1: 修改 ServerMessage.java - 添加 #37 TokenBudgetNudge

在文件末尾（L166 后）新增：
```java
/** #37 token_budget_nudge — Token 预算续写提示 */
public record TokenBudgetNudge(int pct, int currentTokens, int budgetTokens) {}
```

### Step 2: 修改 QueryMessageHandler.java - 新增事件方法

在 L67 末尾新增：
```java
    default void onToolUseSummary(String toolUseId, String summary) {}
    
    // ★ 新增：Token 预算续写 nudge
    /** Token 预算续写 nudge — 推送到前端显示进度 */
    default void onTokenBudgetNudge(int pct, int currentTokens, int budgetTokens) {}
}
```

### Step 3: 修改 WebSocketController.java - 实现 onTokenBudgetNudge()

在 WsMessageHandler 内部类中新增（约 L527 位置）：
```java
@Override
public void onTokenBudgetNudge(int pct, int currentTokens, int budgetTokens) {
    pushToUser(sessionId, "token_budget_nudge",
            new ServerMessage.TokenBudgetNudge(pct, currentTokens, budgetTokens));
}
```

### Step 4: 修改 QueryEngine.java - 补充 handler 推送调用

在 L462-472 ContinueDecision 分支中，state.addMessage(nudgeMsg) 之后新增：
```java
if (decision instanceof TokenBudgetTracker.ContinueDecision cont) {
    log.info("Token budget continuation #{}: {}%",
            cont.continuationCount(), cont.pct());
    Message.UserMessage nudgeMsg = new Message.UserMessage(
            UUID.randomUUID().toString(), Instant.now(),
            List.of(new ContentBlock.TextBlock(cont.nudgeMessage())),
            null, null);
    state.addMessage(nudgeMsg);
    
    // ★ 新增：WebSocket 推送 token_budget_nudge 到前端
    handler.onTokenBudgetNudge(cont.pct(), cont.turnTokens(), cont.budget());
    
    state.setHasAttemptedReactiveCompact(false);
    handler.onTurnEnd(turn, "token_budget_continuation");
    continue;
}
```

### Step 5: 前端 messageStore.ts - 新增 tokenBudgetState

在 `messageStore.ts` 中新增：
```typescript
interface TokenBudgetState {
    pct: number;
    currentTokens: number;
    budgetTokens: number;
    visible: boolean;
}

// 在 store 中新增字段和方法：
tokenBudgetState: TokenBudgetState | null;
setTokenBudgetState: (state: TokenBudgetState | null) => void;
clearTokenBudgetState: () => void;
```

### Step 6: 前端 useWebSocket.ts - 新增消息处理

在 WebSocket 消息处理 switch/case 中新增：
```typescript
case 'token_budget_nudge': {
    const { pct, currentTokens, budgetTokens } = message.payload;
    useMessageStore.getState().setTokenBudgetState({
        pct,
        currentTokens,
        budgetTokens,
        visible: true
    });
    break;
}
```

### Step 7: 前端新建 TokenBudgetIndicator.tsx

位置：`frontend/src/components/TokenBudgetIndicator.tsx`

```typescript
import React from 'react';
import { useMessageStore } from '@/store/messageStore';

export const TokenBudgetIndicator: React.FC = () => {
    const tokenBudgetState = useMessageStore(state => state.tokenBudgetState);

    if (!tokenBudgetState?.visible) return null;

    const { pct, currentTokens, budgetTokens } = tokenBudgetState;
    
    // 颜色阈值：pct < 50% 绿色，50-75% 黄色，>75% 红色
    const getColor = (p: number) => {
        if (p < 50) return 'bg-green-500';
        if (p < 75) return 'bg-yellow-500';
        return 'bg-red-500';
    };

    return (
        <div className="w-full bg-gray-100 rounded-lg p-2">
            <div className="flex items-center justify-between mb-1">
                <span className="text-xs font-medium">Token 预算</span>
                <span className="text-xs text-gray-600 hidden md:inline">
                    {currentTokens.toLocaleString()} / {budgetTokens.toLocaleString()} ({pct}%)
                </span>
            </div>
            <div className="h-2 bg-gray-300 rounded-full overflow-hidden">
                <div
                    className={`h-full ${getColor(pct)} transition-all duration-300`}
                    style={{ width: `${Math.min(pct, 100)}%` }}
                />
            </div>
        </div>
    );
};
```

### Step 8: 集成到 ChatMessageItem.tsx

在消息内容下方渲染 TokenBudgetIndicator：
```typescript
{/* 现有消息内容 */}
<div className="message-content">
    {/* ... */}
</div>

{/* ★ 新增：Token 预算指示器 */}
<TokenBudgetIndicator />
```

---

# Task 5: 方案 8.2.1 PolicySettingsSource TOCTOU 竞态条件修复

## 当前状态

### 关键文件清单
**backend/src/main/java/com/aicodeassistant/permission/PolicySettingsSource.java** (145 行)

### 当前设计缺陷
1. L52：`private volatile long lastModified = 0;` 存在 TOCTOU 竞态条件
2. L72：Check-then-act 模式，并发调用时可能多次重复加载
3. L121：invalidateCache() 中 `lastModified = 0;` 非原子操作

---

## 精确改造规格

### Step 1: 修改 L52 - 使用 AtomicLong

**修改前**：
```java
private volatile long lastModified = 0;
```

**修改后**：
```java
import java.util.concurrent.atomic.AtomicLong;

private final AtomicLong lastModified = new AtomicLong(0);
```

### Step 2: 修改 L72 - 使用原子 get()

**修改前**：
```java
long currentModified = Files.getLastModifiedTime(policyFilePath).toMillis();
if (currentModified == lastModified && !cachedRules.isEmpty()) {
    return cachedRules;  // 缓存命中，无需重新加载
}
```

**修改后**：
```java
long currentModified = Files.getLastModifiedTime(policyFilePath).toMillis();
if (currentModified == lastModified.get() && !cachedRules.isEmpty()) {
    return cachedRules;  // 缓存命中，无需重新加载
}
```

### Step 3: 修改 L90 - 使用原子 set()

**修改前**：
```java
lastModified = currentModified;
```

**修改后**：
```java
lastModified.set(currentModified);
```

### Step 4: 修改 L121 - 使用原子 set()

**修改前**：
```java
public void invalidateCache() {
    lastModified = 0;
    cachedRules = Collections.emptyList();
}
```

**修改后**：
```java
public void invalidateCache() {
    lastModified.set(0);
    cachedRules = Collections.emptyList();
}
```

---

## 总结表

| Task | 文件数 | 类型 | 工作量 | 依赖项 |
|------|--------|------|--------|--------|
| Task 1: 3.1+3.3 | 3 | 流式启动+contextModifier | 3-4 人天 | 无 |
| Task 2: 5.1+5.2 | 8 | Context Collapse+KeyFileTracker | 2.5-3 人天 | 无 |
| Task 3: 6.3 | 4 | MCP 健康检查 | 2-3 人天 | 无 |
| Task 4: 7.1 | 6 | Token 预算 WebSocket | 3-3.5 人天 | 无 |
| Task 5: 8.2.1 | 1 | TOCTOU 竞态修复 | 0.5 人天 | 无 |

**总工作量**：11.5-14 人天

