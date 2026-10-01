package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.agent.WorktreeManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorktreeToolTest {
    private final WorktreeManager manager = mock(WorktreeManager.class);
    private final WorktreeTool tool = new WorktreeTool(manager);
    private final ToolUseContext context = ToolUseContext.of("/fixture/project", "session").withCurrentRunId("run");
    private final Path path = Path.of("/fixture/tree");

    @Test void listPassesTrustedContextAndPropagatesFailure() {
        when(manager.listWorktrees(context)).thenReturn("current project trees");
        var result = call(Map.of("subcommand", "list"));
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("current project trees");
        when(manager.listWorktrees(context)).thenThrow(new IllegalStateException("cannot read Git"));
        assertThat(call(Map.of("subcommand", "list")).isError()).isTrue();
        verify(manager, times(2)).listWorktrees(context);
    }

    @Test void addUsesManualOwnershipAndReportsActualDirectory() {
        when(manager.createWorktree("worker", context, true)).thenReturn(
                new WorktreeManager.ManagedWorktree(path, path.resolve("backend"), "branch", "HEAD snapshot"));
        var result = call(Map.of("subcommand", "add", "agent_id", "worker"));
        assertThat(result.isError()).isFalse();
        assertThat(result.effectState()).isEqualTo(ToolResult.EffectState.APPLIED);
        assertThat(result.content()).contains(path.resolve("backend").toString(), "HEAD snapshot");
        verify(manager).createWorktree("worker", context, true);
    }

    @Test void partialCreationPreservesRecoveryTextWithoutSuggestingRetry() {
        when(manager.createWorktree("worker", context, true)).thenThrow(
                new IllegalStateException("Worktree creation failed; retained at /fixture/tree"));
        var result = call(Map.of("subcommand", "add", "agent_id", "worker"));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("retained at /fixture/tree");
        assertThat(result.retryability()).isEqualTo(ToolResult.Retryability.NEVER);
    }

    @Test void removeNeverTurnsRejectedOrPartialCleanupIntoSuccess() {
        var input = Map.<String, Object>of("subcommand", "remove", "path", path.toString());
        when(manager.removeWorktree(path, context)).thenReturn(
                new WorktreeManager.DeliveryResult(false, false, "active worker retained", ""));
        assertThat(call(input).isError()).isTrue();
        when(manager.removeWorktree(path, context)).thenReturn(
                new WorktreeManager.DeliveryResult(true, false, "delivered; branch remains", "branch removal failed"));
        assertThat(call(input).isError()).isTrue();
        when(manager.removeWorktree(path, context)).thenReturn(
                new WorktreeManager.DeliveryResult(true, false, "directory and branch removed", ""));
        assertThat(call(input).isError()).isFalse();
        when(manager.removeWorktree(path, context)).thenThrow(new IllegalArgumentException("unknown tree"));
        assertThat(call(input).isError()).isTrue();
    }

    private ToolResult call(Map<String, Object> input) { return tool.call(new ToolInput(input), context); }
}
