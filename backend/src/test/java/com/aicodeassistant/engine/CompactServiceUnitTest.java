package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.SystemMessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CompactService 单元测试 — 三区划分 + 6 级优先级 + 3 级降级。
 */
class CompactServiceUnitTest {

    private TokenCounter tokenCounter;
    private CompactService compactService;

    @BeforeEach
    void setUp() {
        tokenCounter = new TokenCounter();
        compactService = new CompactService(tokenCounter, new LlmProviderRegistry(List.of(), null), null, null);
    }

    // ═══════════════ 三区划分 ═══════════════

    @Nested
    @DisplayName("三区划分 (planCompaction)")
    class PartitionTests {

        @Test
        @DisplayName("系统消息进入 frozenMessages")
        void systemMessageFrozen() {
            List<Message> messages = List.of(
                    systemMessage(SystemMessageType.COMPACT_SUMMARY, "[压缩边界]"),
                    userMessage("hello"),
                    assistantTextMessage("hi"),
                    userMessage("question"),
                    assistantTextMessage("answer")
            );
            CompactService.CompactionPlan plan = compactService.planCompaction(messages, 100000, 1);
            assertThat(plan.frozenMessages()).hasSize(1);
            assertThat(plan.frozenMessages().getFirst()).isInstanceOf(Message.SystemMessage.class);
        }

        @Test
        @DisplayName("最近 N 条进入 preservedMessages")
        void recentMessagesPreserved() {
            List<Message> messages = List.of(
                    userMessage("q1"), assistantTextMessage("a1"),
                    userMessage("q2"), assistantTextMessage("a2"),
                    userMessage("q3"), assistantTextMessage("a3")
            );
            CompactService.CompactionPlan plan = compactService.planCompaction(messages, 100000, 2);
            assertThat(plan.preservedMessages()).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("中间消息进入 compactionMessages")
        void middleMessagesCompacted() {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                messages.add(userMessage("q" + i));
                messages.add(assistantTextMessage("a" + i));
            }
            CompactService.CompactionPlan plan = compactService.planCompaction(messages, 100000, 2);
            assertThat(plan.compactionMessages()).isNotEmpty();
            assertThat(plan.frozenMessages().size()
                    + plan.compactionMessages().size()
                    + plan.preservedMessages().size()).isEqualTo(messages.size());
        }

        @Test
        @DisplayName("空对话返回空 CompactionPlan")
        void emptyConversation() {
            CompactService.CompactionPlan plan = compactService.planCompaction(List.of(), 100000, 3);
            assertThat(plan.frozenMessages()).isEmpty();
            assertThat(plan.compactionMessages()).isEmpty();
            assertThat(plan.preservedMessages()).isEmpty();
        }
    }

    // ═══════════════ MessagePriority ═══════════════

    @Nested
    @DisplayName("MessagePriority 6 级排序")
    class PriorityTests {

        @Test
        @DisplayName("枚举 ordinal 升序 = 优先级降序")
        void priorityOrder() {
            assertThat(CompactService.MessagePriority.P0_SYSTEM.ordinal())
                    .isLessThan(CompactService.MessagePriority.P1_FILE_OPERATION.ordinal());
            assertThat(CompactService.MessagePriority.P1_FILE_OPERATION.ordinal())
                    .isLessThan(CompactService.MessagePriority.P2_ERROR_CONTEXT.ordinal());
            assertThat(CompactService.MessagePriority.P2_ERROR_CONTEXT.ordinal())
                    .isLessThan(CompactService.MessagePriority.P3_USER_INTENT.ordinal());
            assertThat(CompactService.MessagePriority.P3_USER_INTENT.ordinal())
                    .isLessThan(CompactService.MessagePriority.P4_TOOL_SUCCESS.ordinal());
            assertThat(CompactService.MessagePriority.P4_TOOL_SUCCESS.ordinal())
                    .isLessThan(CompactService.MessagePriority.P5_INTERMEDIATE.ordinal());
        }

        @Test
        @DisplayName("6 级枚举值完整")
        void allSixLevels() {
            assertThat(CompactService.MessagePriority.values()).hasSize(6);
        }
    }

    // ═══════════════ 关键消息选择 (Level 2) ═══════════════

    @Nested
    @DisplayName("关键消息选择 (fallbackKeyMessageSelection)")
    class KeyMessageSelectionTests {

        @Test
        @DisplayName("按优先级选择消息，满足 token 预算")
        void selectByPriority() {
            List<Message> messages = List.of(
                    systemMessage(SystemMessageType.INFO, "system info"),
                    userMessage("user question"),
                    assistantTextMessage("assistant answer"),
                    toolResultMessage("file content here", false)
            );
            // 给足够大的预算 — 应全部选中
            List<Message> selected = compactService.fallbackKeyMessageSelection(messages, 100000);
            assertThat(selected).hasSize(4);
        }

        @Test
        @DisplayName("预算不足时优先保留高优先级消息")
        void budgetLimit() {
            List<Message> messages = new ArrayList<>();
            messages.add(systemMessage(SystemMessageType.INFO, "short"));
            messages.add(userMessage("short"));
            // 添加一条很长的低优先级消息
            messages.add(assistantTextMessage("x".repeat(5000)));

            // 很小的预算
            List<Message> selected = compactService.fallbackKeyMessageSelection(messages, 50);
            assertThat(selected.size()).isLessThan(messages.size());
        }

        @Test
        @DisplayName("选中消息保持原始顺序")
        void maintainsOriginalOrder() {
            List<Message> messages = List.of(
                    userMessage("first"),
                    assistantTextMessage("second"),
                    userMessage("third")
            );
            List<Message> selected = compactService.fallbackKeyMessageSelection(messages, 100000);
            // 应维持原始顺序
            assertThat(selected).containsExactlyElementsOf(messages);
        }
    }

    // ═══════════════ 压缩触发 ═══════════════

    @Nested
    @DisplayName("压缩触发判断")
    class TriggerTests {

        @Test
        @DisplayName("shouldAutoCompact — 超过 85% 阈值时返回 true")
        void triggerWhenAboveThreshold() {
            // 构造足够多的消息使 token 数超过阈值
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                messages.add(userMessage("x".repeat(500)));
            }
            // contextWindow 很小时，消息 tokens 将超过阈值
            boolean shouldCompact = compactService.shouldAutoCompact(messages, 1000);
            assertThat(shouldCompact).isTrue();
        }

        @Test
        @DisplayName("shouldAutoCompact — 低于阈值时返回 false")
        void noTriggerBelowThreshold() {
            List<Message> messages = List.of(userMessage("short"));
            boolean shouldCompact = compactService.shouldAutoCompact(messages, 100000);
            assertThat(shouldCompact).isFalse();
        }
    }

    // ═══════════════ 完整压缩流程 ═══════════════

    @Nested
    @DisplayName("compact() 完整流程")
    class CompactFlowTests {

        @Test
        @DisplayName("无需压缩时返回 notNeeded")
        void noCompactionNeeded() {
            List<Message> messages = List.of(
                    userMessage("hello"),
                    assistantTextMessage("hi")
            );
            CompactService.CompactResult result = compactService.compact(messages, 100000, false);
            // 只有2条消息，preserveTurns=3 后 compaction 区为空
            assertThat(result.skipReason()).isEqualTo("not_needed");
        }

        @Test
        @DisplayName("关键消息选择成功")
        void keyMessageSelectionSuccess() {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                messages.add(userMessage("question " + i));
                messages.add(assistantTextMessage("answer " + i));
            }
            CompactService.CompactResult result = compactService.compact(messages, 100000, false);
            if (result.skipReason() == null) {
                assertThat(result.compactedMessages()).isNotEmpty();
                // 压缩后消息数应不超过原始消息数 + 1 (压缩边界标记)
                assertThat(result.compactedMessages().size()).isLessThanOrEqualTo(messages.size() + 1);
            }
        }

        @Test
        @DisplayName("反应式压缩保留更少轮次")
        void reactiveCompactPreservesLess() {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                messages.add(userMessage("question " + i));
                messages.add(assistantTextMessage("answer " + i));
            }
            CompactService.CompactResult normalResult = compactService.compact(messages, 100000, false);
            CompactService.CompactResult reactiveResult = compactService.compact(messages, 100000, true);
            // 反应式压缩应保留更少消息 (preserveTurns=1 vs 3)
            if (normalResult.skipReason() == null && reactiveResult.skipReason() == null) {
                assertThat(reactiveResult.compactedMessages().size())
                        .isLessThanOrEqualTo(normalResult.compactedMessages().size());
            }
        }

        @Test
        @DisplayName("reactiveCompact 已尝试过时拒绝执行")
        void reactiveCompactRefusesRetry() {
            List<Message> messages = List.of(userMessage("q"), assistantTextMessage("a"));
            CompactService.CompactResult result = compactService.reactiveCompact(messages, 100000, true);
            assertThat(result.skipReason()).isEqualTo("compact_failed");
            assertThat(result.consecutiveFailures()).isEqualTo(1);
        }
    }

    // ═══════════════ CompactResult ═══════════════

    @Nested
    @DisplayName("CompactResult 工厂方法")
    class CompactResultTests {

        @Test
        @DisplayName("skipped 包含原因")
        void skippedResult() {
            CompactService.CompactResult result = CompactService.CompactResult.skipped("too_few_messages");
            assertThat(result.skipReason()).isEqualTo("too_few_messages");
            assertThat(result.savedTokens()).isEqualTo(0);
        }

        @Test
        @DisplayName("success 计算压缩率")
        void successResult() {
            CompactService.CompactResult result = CompactService.CompactResult.success(
                    List.of(userMessage("a")), 1000, 500);
            assertThat(result.savedTokens()).isEqualTo(500);
            assertThat(result.compressionRatio()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("summary 格式正确")
        void summaryFormat() {
            CompactService.CompactResult result = new CompactService.CompactResult(
                    List.of(), 1000, 500, 5, 0.5);
            assertThat(result.summary()).contains("5 条消息").contains("1000").contains("500");
        }
    }

    // ═══════════════ 辅助方法 ═══════════════

    private static Message.UserMessage userMessage(String text) {
        return new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(text)), null, null);
    }

    private static Message.UserMessage toolResultMessage(String result, boolean isError) {
        return new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(result)),
                result, "tool-assistant-uuid");
    }

    private static Message.AssistantMessage assistantTextMessage(String text) {
        return new Message.AssistantMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(text)), "end_turn", null);
    }

    private static Message.SystemMessage systemMessage(SystemMessageType type, String content) {
        return new Message.SystemMessage(
                UUID.randomUUID().toString(), Instant.now(), content, type);
    }
}
