package com.aicodeassistant.engine;

import com.aicodeassistant.llm.ModelCapabilityRegistry;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContextCascade 5层级联端到端测试 — 验证 Snip → MicroCompact → Collapse → AutoCompact → ErrorRecovery 级联行为。
 * <p>
 * 纯 Mockito 测试，不启动 Spring 容器。
 * 通过精确控制各 mock 的返回值，验证每一层的触发条件和 CascadeResult 记录。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContextCascade 5层级联 E2E 测试")
class ContextCascadeE2ETest {

    @Mock private SnipService snipService;
    @Mock private MicroCompactService microCompactService;
    @Mock private ContextCollapseService contextCollapseService;
    @Mock private CompactService compactService;
    @Mock private TokenCounter tokenCounter;
    @Mock private ModelRegistry modelRegistry;
    @Mock private ModelCapabilityRegistry modelCapabilityRegistry;

    private ContextCascade cascade;

    private static final String MODEL = "test-model";
    private static final int CONTEXT_WINDOW = 100_000;

    @BeforeEach
    void setUp() {
        cascade = new ContextCascade(
                snipService, microCompactService, contextCollapseService,
                compactService, tokenCounter, modelRegistry, modelCapabilityRegistry);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private List<Message> buildMessages(int count) {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i % 2 == 0) {
                msgs.add(new Message.AssistantMessage(
                        "a-" + i, Instant.now(),
                        List.of(new ContentBlock.TextBlock("assistant response " + i)),
                        "end_turn", new Usage(10, 20, 0, 0)));
            } else {
                msgs.add(new Message.UserMessage(
                        "u-" + i, Instant.now(),
                        List.of(new ContentBlock.TextBlock("user msg " + i)),
                        null, null));
            }
        }
        return msgs;
    }

    private List<Message> buildMessagesWithToolResults(int count, int resultSize) {
        List<Message> msgs = new ArrayList<>();
        String bigContent = "x".repeat(resultSize);
        for (int i = 0; i < count; i++) {
            String toolUseId = "tu-" + i;
            // AssistantMessage with ToolUseBlock
            msgs.add(new Message.AssistantMessage(
                    "a-" + i, Instant.now(),
                    List.of(new ContentBlock.ToolUseBlock(toolUseId, "Bash", null)),
                    "end_turn", new Usage(10, 20, 0, 0)));
            // UserMessage with tool result
            msgs.add(new Message.UserMessage(
                    "u-" + i, Instant.now(),
                    List.of(new ContentBlock.ToolResultBlock(toolUseId, bigContent, false)),
                    bigContent, "a-" + i));
        }
        return msgs;
    }

    /** 设置 mock：所有层不做任何操作（透传） */
    private void setupPassthroughMocks(List<Message> messages) {
        lenient().when(modelRegistry.getContextWindowForModel(MODEL)).thenReturn(CONTEXT_WINDOW);
        lenient().when(modelCapabilityRegistry.isRegistered(MODEL)).thenReturn(false);
        lenient().when(tokenCounter.estimateTokens(anyList())).thenReturn(5000);
        lenient().when(snipService.snipToolResults(anyList(), anyInt())).thenReturn(messages);
        lenient().when(microCompactService.compactMessages(anyList(), anyInt()))
                .thenReturn(new MicroCompactService.MicroCompactResult(messages, 0));
        lenient().when(contextCollapseService.progressiveCollapse(anyList()))
                .thenReturn(new ContextCollapseService.CollapseResult(messages, 0, 0));
    }

    // ═══════════════════════════════════════════════════════════════
    // Level 0: Snip
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Level 0 - Snip: 超长工具结果被截断")
    void testLevel0_Snip_TruncatesLargeToolResult() {
        // Given: 包含超长工具结果的消息
        List<Message> original = buildMessagesWithToolResults(2, 50_000);
        List<Message> afterSnip = buildMessages(4); // 较短的消息模拟截断后

        setupPassthroughMocks(original);
        // 覆盖 snip mock: 返回截断后的消息
        when(snipService.snipToolResults(anyList(), anyInt())).thenReturn(afterSnip);
        // snip前后的 token 差异
        when(tokenCounter.estimateTokens(original)).thenReturn(10000);
        when(tokenCounter.estimateTokens(afterSnip)).thenReturn(6000);
        // 后续层透传
        when(microCompactService.compactMessages(anyList(), anyInt()))
                .thenReturn(new MicroCompactService.MicroCompactResult(afterSnip, 0));
        when(contextCollapseService.progressiveCollapse(anyList()))
                .thenReturn(new ContextCollapseService.CollapseResult(afterSnip, 0, 0));

        // When
        var result = cascade.executePreApiCascade(
                original, MODEL, ContextCascade.AutoCompactTrackingState.initial());

        // Then
        assertThat(result.snipExecuted()).isTrue();
        assertThat(result.snipTokensFreed()).isEqualTo(4000);
        verify(snipService).snipToolResults(eq(original), anyInt());
    }

    @Test
    @DisplayName("Level 0 - Snip: 短工具结果不截断")
    void testLevel0_Snip_ShortResultNotTruncated() {
        // Given: 短消息
        List<Message> messages = buildMessages(4);
        setupPassthroughMocks(messages);

        // When
        var result = cascade.executePreApiCascade(
                messages, MODEL, ContextCascade.AutoCompactTrackingState.initial());

        // Then
        assertThat(result.snipExecuted()).isFalse();
        assertThat(result.snipTokensFreed()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════
    // Level 1: MicroCompact
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Level 1 - MicroCompact: 旧工具结果被清除")
    void testLevel1_MicroCompact_ClearsOldToolResults() {
        // Given: 足够多消息触发 MicroCompact
        List<Message> messages = buildMessagesWithToolResults(8, 1000);
        List<Message> afterMC = buildMessages(16); // 模拟清除后的消息

        setupPassthroughMocks(messages);
        when(microCompactService.compactMessages(anyList(), eq(10)))
                .thenReturn(new MicroCompactService.MicroCompactResult(afterMC, 2000));
        when(contextCollapseService.progressiveCollapse(anyList()))
                .thenReturn(new ContextCollapseService.CollapseResult(afterMC, 0, 0));

        // When
        var result = cascade.executePreApiCascade(
                messages, MODEL, ContextCascade.AutoCompactTrackingState.initial());

        // Then
        assertThat(result.microCompactExecuted()).isTrue();
        assertThat(result.microCompactTokensFreed()).isEqualTo(2000);
        // 验证 protectedTail=10
        verify(microCompactService).compactMessages(anyList(), eq(10));
    }

    @Test
    @DisplayName("Level 1 - MicroCompact: 无可清除内容时不执行")
    void testLevel1_MicroCompact_NothingToClean() {
        List<Message> messages = buildMessages(4);
        setupPassthroughMocks(messages);

        var result = cascade.executePreApiCascade(
                messages, MODEL, ContextCascade.AutoCompactTrackingState.initial());

        assertThat(result.microCompactExecuted()).isFalse();
        assertThat(result.microCompactTokensFreed()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════
    // Level 1.5: ContextCollapse
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Level 1.5 - ContextCollapse: 渐进折叠触发")
    void testLevel1_5_ContextCollapse_ProgressiveFolding() {
        // Given: 足够的消息触发折叠
        List<Message> messages = buildMessages(40);
        List<Message> afterCollapse = buildMessages(40); // 内容已折叠

        setupPassthroughMocks(messages);
        when(contextCollapseService.progressiveCollapse(anyList()))
                .thenReturn(new ContextCollapseService.CollapseResult(afterCollapse, 15, 8000));

        // When
        var result = cascade.executePreApiCascade(
                messages, MODEL, ContextCascade.AutoCompactTrackingState.initial());

        // Then
        assertThat(result.contextCollapseExecuted()).isTrue();
        assertThat(result.contextCollapseCharsFreed()).isEqualTo(8000);
    }

    // ═══════════════════════════════════════════════════════════════
    // Level 2: AutoCompact
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Level 2 - AutoCompact: 超过阈值触发LLM摘要")
    void testLevel2_AutoCompact_TriggersCompaction() {
        // Given: token数超过阈值
        List<Message> messages = buildMessages(20);
        List<Message> compacted = buildMessages(5);

        setupPassthroughMocks(messages);
        // 模拟 Collapse 未执行
        when(contextCollapseService.progressiveCollapse(anyList()))
                .thenReturn(new ContextCollapseService.CollapseResult(messages, 0, 0));

        // calculateTokenWarningState 需要 tokenCounter 返回超阈值的值
        // contextWindow=100000, effectiveWindow=75000, buffer=13000(default), threshold=62000
        when(tokenCounter.estimateTokens(anyList())).thenReturn(70000);

        CompactService.CompactResult compactResult = new CompactService.CompactResult(
                compacted, 70000, 20000, 15, 0.71);
        when(compactService.compact(anyList(), eq(CONTEXT_WINDOW), eq(false)))
                .thenReturn(compactResult);

        // When
        var result = cascade.executePreApiCascade(
                messages, MODEL, ContextCascade.AutoCompactTrackingState.initial());

        // Then
        assertThat(result.autoCompactAttempted()).isTrue();
        assertThat(result.autoCompactExecuted()).isTrue();
        assertThat(result.autoCompactResult()).isNotNull();
        assertThat(result.autoCompactResult().beforeTokens()).isEqualTo(70000);
        verify(compactService).compact(anyList(), eq(CONTEXT_WINDOW), eq(false));
    }

    @Test
    @DisplayName("Level 2 - AutoCompact: 未超过阈值不触发")
    void testLevel2_AutoCompact_BelowThreshold_Skipped() {
        // Given: token数低于阈值
        List<Message> messages = buildMessages(6);
        setupPassthroughMocks(messages);
        // token数=5000，远低于threshold=62000
        when(tokenCounter.estimateTokens(anyList())).thenReturn(5000);

        // When
        var result = cascade.executePreApiCascade(
                messages, MODEL, ContextCascade.AutoCompactTrackingState.initial());

        // Then
        assertThat(result.autoCompactAttempted()).isFalse();
        assertThat(result.autoCompactExecuted()).isFalse();
        verify(compactService, never()).compact(anyList(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Level 2 - AutoCompact: 电路断路器阻止执行")
    void testLevel2_AutoCompact_CircuitBroken_Skipped() {
        // Given: 连续3次失败，电路断路
        List<Message> messages = buildMessages(20);
        setupPassthroughMocks(messages);
        when(tokenCounter.estimateTokens(anyList())).thenReturn(70000);

        var brokenState = new ContextCascade.AutoCompactTrackingState(false, 5, "prev", 3);
        assertThat(brokenState.isCircuitBroken()).isTrue();

        // When
        var result = cascade.executePreApiCascade(messages, MODEL, brokenState);

        // Then
        assertThat(result.autoCompactAttempted()).isFalse();
        verify(compactService, never()).compact(anyList(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Level 2 - AutoCompact: Collapse已释放足够空间则跳过")
    void testLevel2_AutoCompact_SkippedAfterCollapse() {
        // Given: Collapse 执行后 token 降到阈值以下
        List<Message> messages = buildMessages(30);
        List<Message> afterCollapse = buildMessages(30);

        setupPassthroughMocks(messages);
        // Collapse 执行并释放空间
        when(contextCollapseService.progressiveCollapse(anyList()))
                .thenReturn(new ContextCollapseService.CollapseResult(afterCollapse, 10, 5000));
        // Collapse 后 token 已低于阈值
        when(tokenCounter.estimateTokens(anyList())).thenReturn(5000);
        when(modelRegistry.getContextWindowForModel(MODEL)).thenReturn(CONTEXT_WINDOW);
        when(modelCapabilityRegistry.isRegistered(MODEL)).thenReturn(false);

        // When
        var result = cascade.executePreApiCascade(
                messages, MODEL, ContextCascade.AutoCompactTrackingState.initial());

        // Then
        assertThat(result.contextCollapseExecuted()).isTrue();
        assertThat(result.autoCompactAttempted()).isFalse();
        verify(compactService, never()).compact(anyList(), anyInt(), anyBoolean());
    }

    // ═══════════════════════════════════════════════════════════════
    // Level 3: CollapseDrain (413 错误恢复)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Level 3 - CollapseDrain: 413错误恢复触发激进压缩")
    void testLevel3_CollapseDrain_AggressiveCompaction() {
        // Given
        List<Message> messages = buildMessages(30);
        List<Message> drained = buildMessages(10);
        CompactService.CompactResult drainResult = new CompactService.CompactResult(
                drained, 80000, 30000, 20, 0.625);

        when(compactService.compact(eq(messages), eq(50000), eq(true))).thenReturn(drainResult);

        // When
        List<Message> result = cascade.executeErrorRecoveryCascade(messages, CONTEXT_WINDOW, false);

        // Then
        assertThat(result).isNotNull().hasSize(10);
        // 验证 compact 被调用，isUrgent=true，目标为 contextWindow*0.5
        verify(compactService).compact(eq(messages), eq(50000), eq(true));
    }

    @Test
    @DisplayName("Level 3 - CollapseDrain: 压缩失败后降级到 Level 4")
    void testLevel3_CollapseDrain_FailsFallsToLevel4() {
        // Given: Level 3 返回跳过
        List<Message> messages = buildMessages(20);
        CompactService.CompactResult skipResult = CompactService.CompactResult.skipped("no_compactable");
        when(compactService.compact(anyList(), anyInt(), eq(true))).thenReturn(skipResult);

        List<Message> reactiveResult = buildMessages(5);
        CompactService.CompactResult reactiveCompactResult = new CompactService.CompactResult(
                reactiveResult, 60000, 15000, 15, 0.75);
        when(compactService.reactiveCompact(eq(messages), eq(CONTEXT_WINDOW), eq(false)))
                .thenReturn(reactiveCompactResult);

        // When
        List<Message> result = cascade.executeErrorRecoveryCascade(messages, CONTEXT_WINDOW, false);

        // Then
        assertThat(result).isNotNull().hasSize(5);
        verify(compactService).reactiveCompact(eq(messages), eq(CONTEXT_WINDOW), eq(false));
    }

    // ═══════════════════════════════════════════════════════════════
    // Level 4: ReactiveCompact
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Level 4 - ReactiveCompact: 已尝试过则跳过")
    void testLevel4_ReactiveCompact_AlreadyAttempted_Skipped() {
        // Given: hasAttemptedReactive=true, Level 3 也失败
        List<Message> messages = buildMessages(20);
        CompactService.CompactResult skipResult = CompactService.CompactResult.skipped("failed");
        when(compactService.compact(anyList(), anyInt(), eq(true))).thenReturn(skipResult);

        // When: hasAttemptedReactive=true
        List<Message> result = cascade.executeErrorRecoveryCascade(messages, CONTEXT_WINDOW, true);

        // Then: 返回 null（所有恢复策略耗尽）
        assertThat(result).isNull();
        verify(compactService, never()).reactiveCompact(anyList(), anyInt(), anyBoolean());
    }

    // ═══════════════════════════════════════════════════════════════
    // 端到端: 级联顺序
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("端到端: 级联顺序正确执行 Snip → MicroCompact → Collapse → AutoCompact")
    void testEndToEnd_CascadeOrderCorrect() {
        // Given: 构造场景，同时触发 Snip + MicroCompact + Collapse + AutoCompact
        List<Message> original = buildMessagesWithToolResults(15, 5000);
        List<Message> afterSnip = buildMessagesWithToolResults(15, 500);
        List<Message> afterMC = buildMessages(30);
        List<Message> afterCollapse = buildMessages(30);
        List<Message> afterCompact = buildMessages(10);

        when(modelRegistry.getContextWindowForModel(MODEL)).thenReturn(CONTEXT_WINDOW);
        when(modelCapabilityRegistry.isRegistered(MODEL)).thenReturn(false);

        // Step 1: Snip reduces tokens
        when(snipService.snipToolResults(eq(original), anyInt())).thenReturn(afterSnip);
        when(tokenCounter.estimateTokens(original)).thenReturn(90000);
        when(tokenCounter.estimateTokens(afterSnip)).thenReturn(80000);

        // Step 2: MicroCompact frees tokens
        when(microCompactService.compactMessages(eq(afterSnip), eq(10)))
                .thenReturn(new MicroCompactService.MicroCompactResult(afterMC, 5000));

        // Step 3: Collapse frees chars
        when(contextCollapseService.progressiveCollapse(eq(afterMC)))
                .thenReturn(new ContextCollapseService.CollapseResult(afterCollapse, 8, 3000));

        // Step 4: AutoCompact (Collapse 释放不够, 仍超阈值)
        when(tokenCounter.estimateTokens(afterCollapse)).thenReturn(75000);
        CompactService.CompactResult acResult = new CompactService.CompactResult(
                afterCompact, 75000, 25000, 20, 0.67);
        when(compactService.compact(eq(afterCollapse), eq(CONTEXT_WINDOW), eq(false)))
                .thenReturn(acResult);

        // final token count
        when(tokenCounter.estimateTokens(afterCompact)).thenReturn(25000);

        // When
        var result = cascade.executePreApiCascade(
                original, MODEL, ContextCascade.AutoCompactTrackingState.initial());

        // Then: 所有层都执行
        assertThat(result.snipExecuted()).isTrue();
        assertThat(result.snipTokensFreed()).isEqualTo(10000);
        assertThat(result.microCompactExecuted()).isTrue();
        assertThat(result.microCompactTokensFreed()).isEqualTo(5000);
        assertThat(result.contextCollapseExecuted()).isTrue();
        assertThat(result.contextCollapseCharsFreed()).isEqualTo(3000);
        assertThat(result.autoCompactAttempted()).isTrue();
        assertThat(result.autoCompactExecuted()).isTrue();
        assertThat(result.originalTokens()).isEqualTo(90000);
        assertThat(result.finalTokens()).isEqualTo(25000);
        assertThat(result.totalTokensFreed()).isEqualTo(65000);
    }

    // ═══════════════════════════════════════════════════════════════
    // CascadeResult 和 AutoCompactTrackingState 完整性
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CascadeResult: summary() 格式完整记录所有执行信息")
    void testCascadeResult_SummaryFormat() {
        CompactService.CompactResult acResult = new CompactService.CompactResult(
                List.of(), 8000, 3000, 5, 0.625);

        var cascadeResult = new ContextCascade.CascadeResult(
                List.of(), 10000, 3000,
                true, 2000,
                true, 1500,
                true, 800,
                true, true, acResult);

        String summary = cascadeResult.summary();
        assertThat(summary)
                .contains("10000")
                .contains("3000")
                .contains("Snip")
                .contains("MicroCompact")
                .contains("Collapse")
                .contains("AutoCompact");
        assertThat(cascadeResult.totalTokensFreed()).isEqualTo(7000);
    }

    @Test
    @DisplayName("AutoCompactTrackingState: 状态流转正确")
    void testAutoCompactTrackingState_StateTransitions() {
        var initial = ContextCascade.AutoCompactTrackingState.initial();
        assertThat(initial.compactedThisTurn()).isFalse();
        assertThat(initial.consecutiveFailures()).isZero();
        assertThat(initial.isCircuitBroken()).isFalse();

        // 成功
        var success = initial.withSuccess("turn-1");
        assertThat(success.compactedThisTurn()).isTrue();
        assertThat(success.lastTurnId()).isEqualTo("turn-1");
        assertThat(success.consecutiveFailures()).isZero();

        // 连续失败到断路
        var fail1 = initial.withFailure();
        assertThat(fail1.consecutiveFailures()).isEqualTo(1);
        assertThat(fail1.isCircuitBroken()).isFalse();

        var fail2 = fail1.withFailure();
        assertThat(fail2.consecutiveFailures()).isEqualTo(2);

        var fail3 = fail2.withFailure();
        assertThat(fail3.consecutiveFailures()).isEqualTo(3);
        assertThat(fail3.isCircuitBroken()).isTrue();
    }

    @Test
    @DisplayName("TokenWarningState: 多级阈值判断")
    void testTokenWarningState_ThresholdLevels() {
        // Given: contextWindow=100000, effectiveWindow=75000, buffer=13000, threshold=62000
        List<Message> messages = buildMessages(10);
        when(modelRegistry.getContextWindowForModel(MODEL)).thenReturn(CONTEXT_WINDOW);
        when(modelCapabilityRegistry.isRegistered(MODEL)).thenReturn(false);

        // 低于所有阈值
        when(tokenCounter.estimateTokens(anyList())).thenReturn(30000);
        var low = cascade.calculateTokenWarningState(messages, MODEL);
        assertThat(low.isAboveAutoCompactThreshold()).isFalse();
        assertThat(low.isAboveWarningThreshold()).isFalse();

        // 超过警告阈值 (62000*0.7=43400)
        when(tokenCounter.estimateTokens(anyList())).thenReturn(50000);
        var warning = cascade.calculateTokenWarningState(messages, MODEL);
        assertThat(warning.isAboveWarningThreshold()).isTrue();
        assertThat(warning.isAboveAutoCompactThreshold()).isFalse();

        // 超过自动压缩阈值
        when(tokenCounter.estimateTokens(anyList())).thenReturn(70000);
        var above = cascade.calculateTokenWarningState(messages, MODEL);
        assertThat(above.isAboveAutoCompactThreshold()).isTrue();
        assertThat(above.isAboveErrorThreshold()).isTrue();
        assertThat(above.contextWindowSize()).isEqualTo(CONTEXT_WINDOW);
    }
}
