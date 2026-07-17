package com.aicodeassistant.permission;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.model.PermissionDecisionReason;
import com.aicodeassistant.sandbox.SandboxManager;
import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.bash.BashCommandClassifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionPipelineSandboxTest {
    private final SandboxManager sandbox = mock(SandboxManager.class);
    private final PermissionPipeline pipeline = new PermissionPipeline(
            mock(PermissionRuleMatcher.class), mock(PermissionRuleRepository.class),
            mock(AutoModeClassifier.class), mock(HookService.class), sandbox,
            mock(PathSecurityService.class), mock(BashCommandClassifier.class),
            mock(FeatureFlagService.class), mock(CommandBlacklistService.class),
            mock(PermissionInteractionService.class));

    @Test
    void hostFileToolsNeverReceiveDockerSandboxOverride() {
        when(sandbox.isSandboxingEnabled()).thenReturn(true);

        assertThat(pipeline.evaluateSandboxRules("FileWrite",
                ToolInput.from(Map.of("file_path", "src/App.java")))).isEmpty();
        assertThat(pipeline.evaluateSandboxRules("FileEdit",
                ToolInput.from(Map.of("file_path", "src/App.java")))).isEmpty();
    }

    @Test
    void bashOverrideRequiresTheExactCommandToBeSandboxRouted() {
        when(sandbox.isSandboxingEnabled()).thenReturn(true);
        when(sandbox.shouldUseSandbox("echo safe")).thenReturn(false);
        when(sandbox.shouldUseSandbox("rm generated.tmp")).thenReturn(true);

        assertThat(pipeline.evaluateSandboxRules("Bash",
                ToolInput.from(Map.of("command", "echo safe")))).isEmpty();
        assertThat(pipeline.evaluateSandboxRules("Bash",
                ToolInput.from(Map.of("command", "rm generated.tmp"))))
                .hasValueSatisfying(decision -> assertThat(decision.reasonType())
                        .isEqualTo(PermissionDecisionReason.SANDBOX_OVERRIDE));
    }
}
