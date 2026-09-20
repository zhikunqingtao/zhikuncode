package com.aicodeassistant.session.merge;

import com.aicodeassistant.engine.*;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MergeSummaryServiceTest {
    @TempDir Path root;
    static final ModelCapabilities CAPS = new ModelCapabilities("test-model", "Test", 4096, 131072, true, false, false, 0, true, .01, .02);
    @Test void fiveRootsAreListedWhileDescendantHistoryAndLastSourceArtifactsRemainAvailable() throws Exception {
        var f = new MergeFixture(root);
        for (String id : List.of("D", "E")) f.sessions.createSessionRecord(id, "test-model", root.toString(), id);
        f.sessions.registerSubAgentSession("subagent-child", root.toString(), "A");
        f.message("subagent-child", "CHILD_EVIDENCE");
        f.message("E", "history line\n".repeat(4000) + "LAST_SOURCE_TAIL");
        f.sessions.addMessageWithId("failed-result", "E", "user", List.of(new com.aicodeassistant.model.ContentBlock.ToolResultBlock("duplicate-tool", "FAILED_TOOL_EVIDENCE", true)), null, 0, 0, null);
        Path artifact = Files.createDirectories(root.resolve("scratch/E")).resolve("binary.bin");
        byte[] bytes = new byte[8193]; new Random(42).nextBytes(bytes); Files.write(artifact, bytes);
        var roots = List.of("A", "B", "C", "D", "E");
        var bundle = f.packages.build(f.packages.packagePath("target", UUID.randomUUID().toString()), roots, () -> {});
        var inputs = new ArrayList<String>();
        var provider = mock(LlmProvider.class);
        stubSummary(provider, inputs, "目标 关键结论 已做改动 产物 验证与失败 未决事项", 100);
        var summary = new MergeSummaryService(mock(LlmProviderRegistry.class), mock(ModelRegistry.class), mock(TokenCounter.class));
        String body = summary.summarize(bundle, roots, new MergeSummaryService.Selection("test-model", provider, CAPS), "five", new AbortContext(), () -> {}, ignored -> {});
        assertThat(body.lines().filter(line -> line.startsWith("来源会话:")).findFirst().orElseThrow())
                .isEqualTo("来源会话: [A, B, C, D, E]");
        assertThat(String.join("", inputs)).contains("CHILD_EVIDENCE", "LAST_SOURCE_TAIL", "FAILED_TOOL_EVIDENCE");
        assertThat(bundle.sources()).hasSize(6);
        String canonicalArtifact = artifact.toRealPath().toString();
        var copy = bundle.assets().stream().filter(asset -> canonicalArtifact.equals(asset.originalPath())).findFirst().orElseThrow();
        assertThat(Files.readAllBytes(Path.of(copy.copiedPath()))).isEqualTo(bytes);
        assertThat(MergePackageService.hash(Path.of(copy.copiedPath()), () -> {})).isEqualTo(copy.sha256());
    }
    @Test void usesToolFreeIndependentCallsAndCountsFinalUsageOnce() throws Exception {
        var provider = mock(LlmProvider.class);
        var inputs = new ArrayList<String>();
        doAnswer(invocation -> {
            List<Map<String, Object>> messages = invocation.getArgument(1);
            inputs.add(messages.getFirst().get("content").toString());
            assertThat((List<?>) invocation.getArgument(3)).isEmpty();
            LlmCallContext context = invocation.getArgument(6);
            assertThat(context.requestId()).startsWith("merge-operation-");
            StreamChatCallback callback = invocation.getArgument(7);
            callback.onEvent(new LlmStreamEvent.TextDelta("目标：继续 A。\n关键结论：来源 A 尾部依据。\n已做改动：未记录。\n产物：未记录。\n验证与失败：未记录。\n未决事项：待用户继续。"));
            callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(40, 5, 0, 0), null));
            callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(40, 10, 0, 0), "end_turn"));
            callback.onComplete(); return null;
        }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(), anyInt(), any(), any(), any());
        var summary = new MergeSummaryService(mock(LlmProviderRegistry.class), mock(ModelRegistry.class), mock(TokenCounter.class));
        var transcript = Files.writeString(root.resolve("transcript.md"), "历史记录 ".repeat(20000) + "ONLY_AT_TAIL");
        var bundle = new MergePackageService.Bundle(root, List.of(transcript), List.of(new MergePackageService.Source("A", "A", "/tmp", 1, "last")), List.of());
        var calls = new ArrayList<MergeSummaryService.CallUsage>();
        String body = summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, CAPS), "operation", new AbortContext(), () -> {}, calls::add);
        assertThat(String.join("", inputs)).contains("ONLY_AT_TAIL");
        assertThat(calls).hasSizeGreaterThan(1).allMatch(u -> u.usage().outputTokens() == 10);
        assertThat(body).contains("index.md", "关键结论");
        assertThat(summary.capacity(body, "test-model")).isLessThanOrEqualTo(4096);
    }
    @Test void stagedSummaryInputsRespectDiskReserveBeforeCallingProvider() throws Exception {
        var provider = mock(LlmProvider.class);
        var summary = new MergeSummaryService(mock(LlmProviderRegistry.class), mock(ModelRegistry.class), mock(TokenCounter.class));
        var input = Files.writeString(root.resolve("input"), "history ".repeat(1000));
        var bundle = new MergePackageService.Bundle(root, List.of(input), List.of(), List.of(),
                new MergeTextBudget(128, () -> 128));
        assertThatThrownBy(() -> summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, CAPS),
                "op", new AbortContext(), () -> {}, ignored -> {})).hasMessageContaining("MERGE_DISK_SPACE_LOW");
        verifyNoInteractions(provider);
        try (var paths = Files.list(root)) { assertThat(paths).noneMatch(p -> p.getFileName().toString().startsWith(".summary-input-")); }
    }
    @Test void rejectsLengthStopAndPartialSuccessEvenWhenTextExists() throws Exception {
        for (String stop : List.of("max_tokens", "tool_use", "", "end_turn")) {
            var provider = mock(LlmProvider.class);
            doAnswer(invocation -> {
                StreamChatCallback callback = invocation.getArgument(7);
                callback.onEvent(new LlmStreamEvent.TextDelta("partial summary"));
                callback.onEvent(new LlmStreamEvent.MessageDelta(Usage.zero(), stop));
                callback.onComplete(); return null;
            }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(), anyInt(), any(), any(), any());
            var summary = new MergeSummaryService(mock(LlmProviderRegistry.class), mock(ModelRegistry.class), mock(TokenCounter.class));
            var file = Files.writeString(root.resolve("input"), "hello");
            var bundle = new MergePackageService.Bundle(root, List.of(file), List.of(), List.of());
            assertThatThrownBy(() -> summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, CAPS), "op", new AbortContext(), () -> {}, ignored -> {}))
                    .hasMessageContaining("SUMMARY_INCOMPLETE");
        }
    }
    @Test void largeModelWindowHandlesHistoryAboveOldByteCeilingAndKeepsTail() throws Exception {
        var provider = mock(LlmProvider.class);
        var inputs = new ArrayList<String>();
        stubSummary(provider, inputs, "目标 关键结论 已做改动 产物 验证与失败 未决事项", 100);
        var summary = new MergeSummaryService(mock(LlmProviderRegistry.class), mock(ModelRegistry.class), mock(TokenCounter.class));
        Path file = Files.writeString(root.resolve("long"), "development history ".repeat(65000) + "LAST_FACT_923");
        var caps = new ModelCapabilities("test-model", "Test", 4096, 1048576, true, false, false, 0, true, .01, .02);
        var bundle = new MergePackageService.Bundle(root, List.of(file), List.of(), List.of());
        summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, caps), "op", new AbortContext(), () -> {}, ignored -> {});
        assertThat(inputs).hasSize(3);
        assertThat(String.join("", inputs)).contains("LAST_FACT_923");
        try (var paths = Files.list(root)) { assertThat(paths).noneMatch(p -> p.getFileName().toString().startsWith(".summary-input-")); }
    }
    @Test void providerOutputTokensAllowFullChineseSummaryButMissingUsageStillFailsClosed() throws Exception {
        String answer = "目标 关键结论 已做改动 产物 验证与失败 未决事项\n" + "说明".repeat(900);
        var file = Files.writeString(root.resolve("input"), "source history");
        var bundle = new MergePackageService.Bundle(root, List.of(file), List.of(), List.of());
        var summary = new MergeSummaryService(mock(LlmProviderRegistry.class), mock(ModelRegistry.class), mock(TokenCounter.class));
        var provider = mock(LlmProvider.class);
        stubSummary(provider, new ArrayList<>(), answer, 2200);
        assertThat(summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, CAPS), "op", new AbortContext(), () -> {}, ignored -> {})).contains(answer);
        reset(provider);
        stubSummary(provider, new ArrayList<>(), answer, 0);
        assertThatThrownBy(() -> summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, CAPS), "op", new AbortContext(), () -> {}, ignored -> {}))
                .hasMessageContaining("SUMMARY_EXCEEDS_BUDGET");
    }
    @Test void overCallBudgetFailsBeforeProviderCallsAndCleansStagedInputs() throws Exception {
        var provider = mock(LlmProvider.class);
        var summary = new MergeSummaryService(mock(LlmProviderRegistry.class), mock(ModelRegistry.class), mock(TokenCounter.class));
        Path file = Files.writeString(root.resolve("too-long"), "x".repeat(2_000_000));
        var bundle = new MergePackageService.Bundle(root, List.of(file), List.of(), List.of());
        assertThatThrownBy(() -> summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, CAPS), "op", new AbortContext(), () -> {}, ignored -> {}))
                .hasMessageContaining("SUMMARY_CALL_BUDGET_EXCEEDED");
        verifyNoInteractions(provider);
        try (var paths = Files.list(root)) { assertThat(paths).noneMatch(p -> p.getFileName().toString().startsWith(".summary-input-")); }
    }
    @Test void reasoningUsageDoesNotRejectShortHandoffButOversizeVisibleTextStillFails() throws Exception {
        var provider = mock(LlmProvider.class);
        String answer = "目标：合并验收。关键结论：未部署。已做改动：无。产物：资料包。验证与失败：缺文件。未决事项：继续读取。";
        stubSummary(provider, new ArrayList<>(), answer, 12929);
        var summary = new MergeSummaryService(mock(LlmProviderRegistry.class), mock(ModelRegistry.class), mock(TokenCounter.class));
        var bundle = new MergePackageService.Bundle(root, List.of(Files.writeString(root.resolve("input"), "history")), List.of(), List.of());
        var calls = new ArrayList<MergeSummaryService.CallUsage>();
        assertThat(summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, CAPS),
                "op", new AbortContext(), () -> {}, calls::add)).contains(answer);
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.usage().outputTokens()).isEqualTo(12929);
            assertThat(call.estimatedCostUsd()).isEqualTo((40 * .01 + 12929 * .02) / 1000);
        });
        reset(provider);
        stubSummary(provider, new ArrayList<>(), answer + "x".repeat(5000), 12929);
        assertThatThrownBy(() -> summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, CAPS),
                "op", new AbortContext(), () -> {}, ignored -> {})).hasMessageContaining("SUMMARY_EXCEEDS_BUDGET");
    }
    @Test void reservesThinkingGenerationWithoutSpendingInputWindowOrHandoffBudget() throws Exception {
        var provider = mock(LlmProvider.class);
        var summary = new MergeSummaryService(mock(LlmProviderRegistry.class), mock(ModelRegistry.class), mock(TokenCounter.class));
        var caps = new ModelCapabilities("test-model", "Test", 65536, 131072, true, true, false, 0, true, .01, .02);
        var inputs = new ArrayList<String>();
        doAnswer(call -> {
            List<Map<String, Object>> messages = call.getArgument(1);
            String input = messages.getFirst().get("content").toString();
            String system = call.getArgument(2);
            int generation = call.getArgument(4);
            inputs.add(input);
            assertThat(generation).isBetween(32768, 32768 + 4096);
            assertThat(summary.capacity(input, "test-model") + summary.capacity(system, "test-model") + generation + 1024)
                    .isLessThanOrEqualTo(caps.contextWindow());
            assertThat((List<?>) call.getArgument(3)).isEmpty();
            assertThat((Object) call.getArgument(5)).isInstanceOf(ThinkingConfig.Disabled.class);
            StreamChatCallback callback = call.getArgument(7);
            callback.onEvent(new LlmStreamEvent.TextDelta("目标 关键结论 已做改动 产物 验证与失败 未决事项"));
            callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(40, 18000, 0, 0), "end_turn"));
            callback.onComplete(); return null;
        }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(), anyInt(), any(), any(), any());
        var bundle = new MergePackageService.Bundle(root,
                List.of(Files.writeString(root.resolve("thinking-input"), "x".repeat(240000) + "FINAL_EVIDENCE")), List.of(), List.of());
        String body = summary.summarize(bundle, List.of("A", "B"), new MergeSummaryService.Selection("test-model", provider, caps),
                "op", new AbortContext(), () -> {}, ignored -> {});
        assertThat(inputs).hasSizeGreaterThan(1);
        assertThat(String.join("", inputs)).contains("FINAL_EVIDENCE");
        assertThat(summary.capacity(body, "test-model")).isLessThanOrEqualTo(4096);
        var small = new ModelCapabilities("small", "Small", 2048, 16384, true, true, false, 0, true, 0, 0);
        assertThat(summary.generationBudget(new MergeSummaryService.Selection("small", provider, small), 1000)).isEqualTo(2048);
        assertThat(summary.generationBudget(new MergeSummaryService.Selection("test-model", provider, CAPS), 1000)).isEqualTo(1000);
    }
    private static void stubSummary(LlmProvider provider, List<String> inputs, String answer, int tokens) {
        doAnswer(call -> {
            List<Map<String, Object>> messages = call.getArgument(1);
            inputs.add(messages.getFirst().get("content").toString());
            StreamChatCallback callback = call.getArgument(7);
            callback.onEvent(new LlmStreamEvent.TextDelta(answer));
            callback.onEvent(new LlmStreamEvent.MessageDelta(new Usage(40, tokens, 0, 0), "end_turn"));
            callback.onComplete(); return null;
        }).when(provider).streamChat(anyString(), anyList(), anyString(), anyList(), anyInt(), any(), any(), any());
    }

}
