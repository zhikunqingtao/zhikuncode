package com.aicodeassistant.tool.impl;

import com.aicodeassistant.sandbox.SandboxManager;
import com.aicodeassistant.sandbox.SandboxManager.SandboxResult;
import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.BashCommandClassifier;
import com.aicodeassistant.tool.bash.BashErrorClassifier;
import com.aicodeassistant.tool.bash.BashErrorClassifier.ErrorType;
import com.aicodeassistant.tool.bash.BashOutputProcessor;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ProcessTreeManager;
import com.aicodeassistant.tool.bash.ShellStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
    private ToolUseContext context;

    @BeforeEach
    void setUp() {
        BashSecurityAnalyzer securityAnalyzer = mock(BashSecurityAnalyzer.class);
        commandClassifier = mock(BashCommandClassifier.class);
        shellStateManager = mock(ShellStateManager.class);
        outputProcessor = mock(BashOutputProcessor.class);
        ProcessTreeManager processTreeManager = mock(ProcessTreeManager.class);
        sandboxManager = mock(SandboxManager.class);
        CommandBlacklistService blacklist = mock(CommandBlacklistService.class);
        // 使用真实分类器以验证端到端契约
        BashErrorClassifier errorClassifier = new BashErrorClassifier();

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
                outputProcessor, processTreeManager, sandboxManager, blacklist, errorClassifier);

        context = ToolUseContext.of("/tmp", "test-session");
    }

    @Test
    @DisplayName("预执行安全拦截 (sudo) → metadata.failure_category=NEEDS_HUMAN")
    void safetyRejection_attachesNeedsHumanCategory() {
        ToolInput input = ToolInput.from(Map.of("command", "sudo rm -rf /"));

        ToolResult result = bashTool.call(input, context);

        assertThat(result.isError()).isTrue();
        assertThat(result.metadata())
                .containsEntry("failure_category", ErrorType.NEEDS_HUMAN.name())
                .containsKey("failure_suggestion");
        assertThat((String) result.metadata().get("failure_suggestion"))
                .containsIgnoringCase("privilege");
    }

    @Test
    @DisplayName("沙箱超时 → metadata.failure_category=TIMEOUT")
    void sandboxTimeout_attachesTimeoutCategory() {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(sandboxManager.getTimeoutSeconds()).thenReturn(30);
        when(sandboxManager.execute(anyString(), any(Path.class), any()))
                .thenReturn(new SandboxResult("partial output", -1, true));

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
    void sandboxNonZero_commandNotFound_attachesNonRetryable() {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(sandboxManager.execute(anyString(), any(Path.class), any()))
                .thenReturn(new SandboxResult("bash: nosuchcmd: command not found", 127, false));

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
    void sandboxNonZero_networkError_attachesRetryable() {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(sandboxManager.execute(anyString(), any(Path.class), any()))
                .thenReturn(new SandboxResult("curl: (7) Connection refused", 7, false));

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
    void sandboxSuccess_doesNotAttachFailureCategory() {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(sandboxManager.execute(anyString(), any(Path.class), any()))
                .thenReturn(new SandboxResult("hello\n", 0, false));

        ToolInput input = ToolInput.from(Map.of("command", "echo hello"));
        ToolResult result = bashTool.call(input, context);

        assertThat(result.isError()).isFalse();
        assertThat(result.metadata())
                .doesNotContainKey("failure_category")
                .doesNotContainKey("failure_suggestion");
    }

    @Test
    @DisplayName("metadata 序列化 — failure_category/suggestion 均为 String 类型")
    void metadataValuesAreStrings() {
        when(sandboxManager.isSandboxingEnabled()).thenReturn(true);
        when(sandboxManager.shouldUseSandbox(anyString())).thenReturn(true);
        when(sandboxManager.execute(anyString(), any(Path.class), any()))
                .thenReturn(new SandboxResult("compile error", 1, false));

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
}
