package com.aicodeassistant.tool;

import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.tool.agent.AgentTool;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 超时 / Watchdog / 级联 / 高危标记单元测试。
 * <p>
 * 覆盖五大场景:
 * <ol>
 *   <li>Watchdog 触发后生成 synthetic error（计数器路径 + 日志关键字）</li>
 *   <li>高危工具错误触发 sibling 选择性级联中止</li>
 *   <li>SubAgent 超时返回结构化 AgentResult.STATUS_TIMEOUT</li>
 *   <li>Tool.getMaxExecutionTimeMs() 正确注册到 Session</li>
 *   <li>Tool.isHighRisk() 缺省与覆写实现</li>
 * </ol>
 */
class ToolExecutionTimeoutTest {

    private MeterRegistry meterRegistry;
    private ToolExecutionPipeline pipeline;
    private StreamingToolExecutor executor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        pipeline = mock(ToolExecutionPipeline.class);
        executor = new StreamingToolExecutor(pipeline, meterRegistry);
    }

    // ═══════════════ 场景 1: Watchdog 触发 + synthetic error 计数 ═══════════════

    @Test
    @DisplayName("场景1: notifyWatchdogFired/notifySyntheticError 增加对应 Counter")
    void testWatchdogFireGeneratesSyntheticError() {
        var ctx = ToolUseContext.of("/tmp", "session-watchdog");
        var session = executor.newSession(ctx);

        // 模拟 QueryEngine 在 Watchdog 触发后调用的 session 钩子
        session.notifyWatchdogFired();
        session.notifyWatchdogFired();
        session.notifySyntheticError();

        assertThat(meterRegistry.counter("zhiku.tool.watchdog_fired").count())
                .as("watchdogFiredCounter 应等于 2")
                .isEqualTo(2.0);
        assertThat(meterRegistry.counter("zhiku.tool.synthetic_errors").count())
                .as("syntheticErrorCounter 应等于 1")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("场景1: discard() 标记 sessionDiscarded，新工具产出 'discarded' error")
    void testDiscardedSessionYieldsDiscardError() throws Exception {
        var ctx = ToolUseContext.of("/tmp", "session-discard");
        var session = executor.newSession(ctx);

        Tool slowTool = mockTool("SlowMock", false, false, 30_000L);
        // pipeline 不会真正被调用 — sessionDiscarded 时直接走 short-circuit 分支
        session.discard();
        session.addTool(slowTool, ToolInput.from(Map.of()), "tu-orphan", ctx);
        waitUntilCompleted(session, 1500);

        var yielded = session.yieldCompleted();
        assertThat(yielded).hasSize(1);
        assertThat(yielded.get(0).getResult().isError()).isTrue();
        assertThat(yielded.get(0).getResult().content())
                .contains("Tool execution discarded");
    }

    // ═══════════════ 场景 2: 高危工具错误触发选择性级联 ═══════════════

    @Test
    @DisplayName("场景2: 高危工具 error → 触发级联中止 + cascadeAbortCounter+1")
    void testHighRiskErrorTriggersSelectiveCascade() throws Exception {
        var ctx = ToolUseContext.of("/tmp", "session-cascade");
        var session = executor.newSession(ctx);

        // pipeline 返回 error result
        when(pipeline.execute(any(Tool.class), any(ToolInput.class), any(ToolUseContext.class), any()))
                .thenReturn(ToolExecutionResult.of(
                        ToolResult.error("<tool_use_error>boom</tool_use_error>")));

        Tool highRisk = mockTool("HighRiskMock", true, false, 10_000L);
        session.addTool(highRisk, ToolInput.from(Map.of()), "tu-hr", ctx);
        waitUntilCompleted(session, 1500);

        assertThat(session.isDiscarded()).as("高危工具错误后会话应被标记 discarded").isTrue();
        assertThat(meterRegistry.counter("zhiku.tool.cascade_aborts").count())
                .as("cascadeAbortCounter 应被增加").isEqualTo(1.0);

        // 已 discard 的 session 上排队的后续工具应直接拿到 'discarded' error
        Tool sibling = mockTool("SiblingMock", false, false, 10_000L);
        session.addTool(sibling, ToolInput.from(Map.of()), "tu-sib", ctx);
        waitUntilCompleted(session, 1500);

        var resultsAll = session.yieldCompleted();
        // 仅返回尚未 yield 的（sibling）结果
        assertThat(resultsAll).extracting(t -> t.getResult().content())
                .anyMatch(c -> c != null && c.contains("Tool execution discarded"));
    }

    @Test
    @DisplayName("场景2: 普通工具 error 不触发级联")
    void testLowRiskErrorDoesNotCascade() throws Exception {
        var ctx = ToolUseContext.of("/tmp", "session-no-cascade");
        var session = executor.newSession(ctx);

        when(pipeline.execute(any(Tool.class), any(ToolInput.class), any(ToolUseContext.class), any()))
                .thenReturn(ToolExecutionResult.of(
                        ToolResult.error("<tool_use_error>read failed</tool_use_error>")));

        Tool lowRisk = mockTool("ReadMock", false, true, 10_000L);
        session.addTool(lowRisk, ToolInput.from(Map.of()), "tu-low", ctx);
        waitUntilCompleted(session, 1500);

        assertThat(session.isDiscarded()).as("低风险工具错误不应导致 discard").isFalse();
        assertThat(meterRegistry.counter("zhiku.tool.cascade_aborts").count())
                .as("cascadeAbortCounter 不应递增").isEqualTo(0.0);
    }

    // ═══════════════ 场景 3: SubAgent 超时返回结构化 error ═══════════════

    @Test
    @DisplayName("场景3: AgentResult.STATUS_TIMEOUT 通过 AgentTool 映射为 ToolResult.error()")
    void testSubAgentTimeoutMappedToToolErrorWithTag() {
        SubAgentExecutor mockExecutor = mock(SubAgentExecutor.class);
        LlmProviderRegistry mockRegistry = mock(LlmProviderRegistry.class);
        when(mockRegistry.getBuiltinAliases()).thenReturn(List.of("standard"));
        lenient().when(mockRegistry.listAvailableModels()).thenReturn(List.of("standard"));

        AgentTool agentTool = new AgentTool(mockExecutor, mockRegistry);

        String timeoutMsg = "<tool_use_error>Sub-agent 'agent-x' (type=explore) "
                + "timed out after 300 seconds. Task: hang forever</tool_use_error>";
        var timeoutResult = new SubAgentExecutor.AgentResult(
                SubAgentExecutor.AgentResult.STATUS_TIMEOUT, timeoutMsg, "hang forever", null);
        when(mockExecutor.executeSync(any(), any())).thenReturn(timeoutResult);

        var ctx = ToolUseContext.of("/tmp", "session-timeout");
        ToolResult mapped = agentTool.call(ToolInput.from(Map.of("prompt", "hang")), ctx);

        assertThat(timeoutResult.isTimeout()).isTrue();
        assertThat(timeoutResult.status()).isEqualTo("timeout");
        assertThat(mapped.isError()).as("超时应映射为 error ToolResult").isTrue();
        assertThat(mapped.content()).contains("<tool_use_error>");
        assertThat(mapped.content()).contains("timed out");
    }

    // ═══════════════ 场景 4: Tool.getMaxExecutionTimeMs 注册到 Session ═══════════════

    @Test
    @DisplayName("场景4: addTool 累积工具最大预期时长，session.getMaxExpectedDurationMs 取最大值")
    void testRegisterMaxExecutionTimeFromTools() throws Exception {
        var ctx = ToolUseContext.of("/tmp", "session-maxdur");
        var session = executor.newSession(ctx);

        // 默认初始值 = 600_000 ms (10 min)
        assertThat(session.getMaxExpectedDurationMs()).isEqualTo(600_000L);

        // pipeline 直接返回 success（避免触发级联）
        when(pipeline.execute(any(Tool.class), any(ToolInput.class), any(ToolUseContext.class), any()))
                .thenReturn(ToolExecutionResult.of(ToolResult.success("ok")));

        // 工具 A: 5 分钟（小于初始值，不应抬高）
        Tool toolA = mockTool("Short", false, true, 300_000L);
        session.addTool(toolA, ToolInput.from(Map.of()), "tu-a", ctx);
        waitUntilCompleted(session, 1500);
        assertThat(session.getMaxExpectedDurationMs())
                .as("低于初始值的注册不应降低 maxExpectedDuration")
                .isEqualTo(600_000L);

        // 工具 B: 30 分钟（大于初始值，应抬高至 1_800_000）
        Tool toolB = mockTool("Long", false, true, 1_800_000L);
        session.addTool(toolB, ToolInput.from(Map.of()), "tu-b", ctx);
        waitUntilCompleted(session, 1500);
        assertThat(session.getMaxExpectedDurationMs())
                .as("高于初始值的注册应抬高 maxExpectedDuration")
                .isEqualTo(1_800_000L);

        // 工具 C: 5 分钟（再注册一个小值，max 不应回退）
        Tool toolC = mockTool("ShortAgain", false, true, 300_000L);
        session.addTool(toolC, ToolInput.from(Map.of()), "tu-c", ctx);
        waitUntilCompleted(session, 1500);
        assertThat(session.getMaxExpectedDurationMs())
                .as("max 应保持单调不降")
                .isEqualTo(1_800_000L);
    }

    // ═══════════════ 场景 5: isHighRisk 接口缺省与覆写 ═══════════════

    @Test
    @DisplayName("场景5: BashTool.isHighRisk()=true / AgentTool.isHighRisk()=true / 默认=false")
    void testIsHighRiskInterfaceContract() {
        // 默认：匿名 Tool 实现 isHighRisk() 应为 false
        Tool defaultTool = new Tool() {
            @Override public String getName() { return "Default"; }
            @Override public String getDescription() { return ""; }
            @Override public Map<String, Object> getInputSchema() { return Map.of(); }
            @Override public ToolResult call(ToolInput input, ToolUseContext context) {
                return ToolResult.success("");
            }
        };
        assertThat(defaultTool.isHighRisk()).as("默认实现应为 false").isFalse();
        assertThat(defaultTool.getMaxExecutionTimeMs()).as("默认时长应为 2 分钟").isEqualTo(120_000L);

        // BashTool: 高风险，构造仅用于读取常量字段（依赖全部 mock）
        com.aicodeassistant.tool.impl.BashTool bashTool = new com.aicodeassistant.tool.impl.BashTool(
                mock(com.aicodeassistant.tool.bash.BashSecurityAnalyzer.class),
                mock(com.aicodeassistant.tool.bash.BashCommandClassifier.class),
                mock(com.aicodeassistant.tool.bash.ShellStateManager.class),
                mock(com.aicodeassistant.tool.bash.BashOutputProcessor.class),
                mock(com.aicodeassistant.tool.bash.ProcessTreeManager.class),
                mock(com.aicodeassistant.sandbox.SandboxManager.class),
                mock(com.aicodeassistant.security.CommandBlacklistService.class),
                mock(com.aicodeassistant.tool.bash.BashErrorClassifier.class));
        assertThat(bashTool.isHighRisk()).as("BashTool 必须为高危").isTrue();
        assertThat(bashTool.getMaxExecutionTimeMs()).as("BashTool 应声明 10 分钟").isEqualTo(600_000L);

        // AgentTool: 高风险
        AgentTool agentTool = new AgentTool(
                mock(SubAgentExecutor.class), mock(LlmProviderRegistry.class));
        assertThat(agentTool.isHighRisk()).as("AgentTool 必须为高危").isTrue();
        assertThat(agentTool.getMaxExecutionTimeMs()).as("AgentTool 应声明 30 分钟").isEqualTo(1_800_000L);
    }

    // ═══════════════ 工具方法 ═══════════════

    private Tool mockTool(String name, boolean highRisk, boolean readOnly, long maxMs) {
        Tool t = mock(Tool.class);
        lenient().when(t.getName()).thenReturn(name);
        lenient().when(t.isHighRisk()).thenReturn(highRisk);
        lenient().when(t.isReadOnly(any())).thenReturn(readOnly);
        lenient().when(t.isConcurrencySafe(any())).thenReturn(readOnly);
        lenient().when(t.getMaxExecutionTimeMs()).thenReturn(maxMs);
        return t;
    }

    /** 在指定毫秒内轮询等待 session 完成（用于虚拟线程 fire-and-forget 场景）。 */
    private static void waitUntilCompleted(StreamingToolExecutor.ExecutionSession session,
                                            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (session.isAllCompleted()) return;
            Thread.sleep(10);
        }
    }
}
