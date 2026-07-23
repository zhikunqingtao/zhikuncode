package com.aicodeassistant.tool.impl;

import com.aicodeassistant.sandbox.SandboxManager;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.BashCommandClassifier;
import com.aicodeassistant.tool.bash.BashErrorClassifier;
import com.aicodeassistant.tool.bash.BashErrorClassifier.ErrorType;
import com.aicodeassistant.tool.bash.BashOutputProcessor;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ShellStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BashTool 失败分类编码（方案 6.3）单元测试。
 * <p>
 * 验证错误返回路径将 {@link BashErrorClassifier} 的分类结果通过
 * {@link ToolResult#metadata()} 暴露给模型。覆盖：
 * <ul>
 *     <li>预执行安全拦截 → NEEDS_HUMAN</li>
 *     <li>沙箱超时 → TIMEOUT</li>
 *     <li>沙箱非零退出（命令未找到）→ NON_RETRYABLE</li>
 *     <li>沙箱非零退出（网络错误）→ RETRYABLE</li>
 *     <li>沙箱成功 → 不包含 failure_category（向后兼容）</li>
 * </ul>
 */
class BashToolFailureClassificationTest {

    private BashTool bashTool;
    private SandboxManager sandboxManager;
    private ShellStateManager shellStateManager;
    private BashCommandClassifier commandClassifier;
    private BashOutputProcessor outputProcessor;
    private ManagedProcessRunner processRunner;
    private CommandBlacklistService blacklist;
    private ToolUseContext context;

    @BeforeEach
    void setUp() {
        BashSecurityAnalyzer securityAnalyzer = mock(BashSecurityAnalyzer.class);
        commandClassifier = mock(BashCommandClassifier.class);
        shellStateManager = mock(ShellStateManager.class);
        outputProcessor = mock(BashOutputProcessor.class);
        sandboxManager = mock(SandboxManager.class);
        processRunner = mock(ManagedProcessRunner.class);
        blacklist = mock(CommandBlacklistService.class);
        // 使用真实分类器以验证端到端契约
        BashErrorClassifier errorClassifier = new BashErrorClassifier();

        // 默认：命令被允许（非 ABSOLUTE_DENY）
        lenient().when(blacklist.checkCommand(anyString()))
                .thenReturn(new CommandBlacklistService.BlockResult(
                        CommandBlacklistService.BlockLevel.ALLOWED, null, null));

        // 通用桩（lenient — 部分用例不会触发）
        lenient().when(shellStateManager.wrapCommand(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(shellStateManager.resolveWorkingDirectory(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(commandClassifier.classifyForUI(anyString()))
                .thenReturn(com.aicodeassistant.tool.bash.CommandCategory.UNKNOWN);
        lenient().when(commandClassifier.classifyForTimeout(anyString()))
                .thenReturn(com.aicodeassistant.tool.bash.CommandCategory.UNKNOWN);
        lenient().when(outputProcessor.processOutput(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));

        bashTool = new BashTool(securityAnalyzer, commandClassifier, shellStateManager,
                outputProcessor, sandboxManager, blacklist, errorClassifier, processRunner);

        context = ToolUseContext.of("/tmp", "test-session").withCurrentRunId("run-1").withToolUseId("tool-1");
        lenient().when(sandboxManager.prepareInvocation(anyString(), any(Path.class), any(), anyString(), anyString()))
                .thenReturn(new SandboxManager.SandboxInvocation(List.of("echo", "test"), "container", deadline -> true));
        lenient().when(sandboxManager.getTimeoutSeconds()).thenReturn(30);
    }

    @Test
    @DisplayName("ABSOLUTE_DENY 命令 (rm -rf /) → permissionDenied with COMMAND_ABSOLUTELY_DENIED")
    void safetyRejection_attachesNeedsHumanCategory() {
        String command = "rm -rf /";
        when(blacklist.checkCommand(command))
                .thenReturn(CommandBlacklistService.BlockResult.deny(
                        "rm\\s+.*", "Recursive deletion of system directory"));

        ToolInput input = ToolInput.from(Map.of("command", command));
        ToolResult result = bashTool.call(input, context);

        assertThat(result.isError()).isTrue();
        assertThat(result.failureCode()).isEqualTo("COMMAND_ABSOLUTELY_DENIED");
        assertThat(result.content()).contains("Recursive deletion of system directory");
    }

    @Test
    @DisplayName("沙箱超时 → metadata.failure_category=TIMEOUT")
    void sandboxTimeout_attachesTimeoutCategory() throws Exception {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(sandboxManager.getTimeoutSeconds()).thenReturn(30);
        when(processRunner.run(any())).thenReturn(result(137, "partial output", "", true));

        ToolInput input = ToolInput.from(Map.of("command", "long-running-cmd"));
        ToolResult result = bashTool.call(input, context);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("timed out");
        assertThat(result.metadata())
                .containsEntry("failure_category", ErrorType.TIMEOUT.name())
                .containsKey("failure_suggestion");
    }

    @Test
    @DisplayName("沙箱非零退出 — 命令未找到 (exit 127) → metadata.failure_category=NON_RETRYABLE")
    void sandboxNonZero_commandNotFound_attachesNonRetryable() throws Exception {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(processRunner.run(any())).thenReturn(result(127, "bash: nosuchcmd: command not found", "", false));

        ToolInput input = ToolInput.from(Map.of("command", "python script.py"));
        ToolResult result = bashTool.call(input, context);

        assertThat(result.isError()).isTrue();
        assertThat(result.metadata())
                .containsEntry("failure_category", ErrorType.NON_RETRYABLE.name());
        // python → python3 替代建议
        assertThat((String) result.metadata().get("failure_suggestion"))
                .contains("python3");
    }

    @Test
    @DisplayName("沙箱非零退出 — 网络错误 → metadata.failure_category=RETRYABLE")
    void sandboxNonZero_networkError_attachesRetryable() throws Exception {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(processRunner.run(any())).thenReturn(result(7, "curl: (7) Connection refused", "", false));

        ToolInput input = ToolInput.from(Map.of("command", "curl http://localhost:9999"));
        ToolResult result = bashTool.call(input, context);

        assertThat(result.isError()).isTrue();
        assertThat(result.metadata())
                .containsEntry("failure_category", ErrorType.RETRYABLE.name());
        assertThat((String) result.metadata().get("failure_suggestion"))
                .containsIgnoringCase("network");
    }

    @Test
    @DisplayName("沙箱成功路径 → metadata 不包含 failure_category（向后兼容）")
    void sandboxSuccess_doesNotAttachFailureCategory() throws Exception {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(processRunner.run(any())).thenReturn(result(0, "hello\n", "", false));

        ToolInput input = ToolInput.from(Map.of("command", "echo hello"));
        ToolResult result = bashTool.call(input, context);

        assertThat(result.isError()).isFalse();
        assertThat(result.metadata())
                .doesNotContainKey("failure_category")
                .doesNotContainKey("failure_suggestion");
    }

    @Test
    @DisplayName("metadata 序列化 — failure_category/suggestion 均为 String 类型")
    void metadataValuesAreStrings() throws Exception {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(processRunner.run(any())).thenReturn(result(1, "compile error", "", false));

        ToolInput input = ToolInput.from(Map.of("command", "make"));
        ToolResult result = bashTool.call(input, context);

        assertThat(result.metadata().get("failure_category")).isInstanceOf(String.class);
        assertThat(result.metadata().get("failure_suggestion")).isInstanceOf(String.class);

        // toSerializable 应保留 failure_* 字段
        ToolResult serializable = result.toSerializable();
        assertThat(serializable.metadata())
                .containsKey("failure_category")
                .containsKey("failure_suggestion");
    }

    private static ManagedProcessRunner.Result result(int exit, String stdout, String stderr, boolean timedOut) {
        return new ManagedProcessRunner.Result(exit, stdout, stderr, false, false,
                timedOut, false, true, 10, false);
    }
}
