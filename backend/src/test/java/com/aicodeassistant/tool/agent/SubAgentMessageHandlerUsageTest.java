package com.aicodeassistant.tool.agent;

import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子代理检查点的用量口径：只用 QueryLoopState 的已观测累计值，
 * onAssistantMessage / onUsage 双回调重复触发不得双计。
 */
class SubAgentMessageHandlerUsageTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CheckpointService checkpointService = mock(CheckpointService.class);
    private final SessionManager sessionManager = mock(SessionManager.class);

    private SubAgentExecutor.SubAgentMessageHandler handler(QueryLoopState state) {
        return new SubAgentExecutor.SubAgentMessageHandler(checkpointService, objectMapper, sessionManager,
                state, "run-1", "test-session", "agent-1", "/tmp");
    }

    private static QueryLoopState state() {
        return new QueryLoopState(List.of(), ToolUseContext.of("/tmp", "test-session"));
    }

    private static Message.AssistantMessage assistantMessage(Usage usage) {
        return new Message.AssistantMessage("assistant-1", Instant.now(),
                List.of(new ContentBlock.TextBlock("sub-agent result")), "end_turn", usage);
    }

    @Test
    void checkpointReportsObservedUsageOnceWhenBothCallbacksFireRepeatedly() {
        when(checkpointService.shouldCheckpoint(anyInt(), anyInt(), anyInt())).thenReturn(true);
        QueryLoopState state = state();
        SubAgentExecutor.SubAgentMessageHandler handler = handler(state);

        // 引擎在持久化/回调前收敛一次；usage=10（输入 6 + 输出 4）。
        Usage reported = new Usage(6, 4, 0, 0);
        state.recordRawUsage(reported);

        Message.AssistantMessage message = assistantMessage(reported);
        handler.onAssistantMessage(message);
        handler.onUsage(reported);
        handler.onAssistantMessage(message);
        handler.onUsage(reported);
        handler.onTurnEnd(5, "end_turn");

        ArgumentCaptor<AgentCheckpoint> captured = ArgumentCaptor.forClass(AgentCheckpoint.class);
        verify(checkpointService, timeout(5_000)).save(captured.capture());
        assertThat(captured.getValue().tokensConsumed())
                .isEqualTo(10L)
                .isEqualTo(state.getObservedUsage().totalTokens());
    }

    @Test
    void assistantAndUsageCallbacksDoNotAccumulateOnTheirOwn() {
        when(checkpointService.shouldCheckpoint(anyInt(), anyInt(), anyInt())).thenReturn(true);
        QueryLoopState state = state();
        SubAgentExecutor.SubAgentMessageHandler handler = handler(state);

        // 回调本身不再累计：即使携带 usage=10，也不得变成 10 或 20。
        Usage reported = new Usage(10, 0, 0, 0);
        Message.AssistantMessage message = assistantMessage(reported);
        handler.onAssistantMessage(message);
        handler.onUsage(reported);
        handler.onAssistantMessage(message);
        handler.onUsage(reported);
        handler.onTurnEnd(5, "end_turn");

        ArgumentCaptor<AgentCheckpoint> captured = ArgumentCaptor.forClass(AgentCheckpoint.class);
        verify(checkpointService, timeout(5_000)).save(captured.capture());
        assertThat(captured.getValue().tokensConsumed()).isZero();
        assertThat(captured.getValue().tokensConsumed())
                .isEqualTo(state.getObservedUsage().totalTokens());
    }

    @Test
    void everyCheckpointSnapshotsTheLatestObservedTotal() {
        when(checkpointService.shouldCheckpoint(anyInt(), anyInt(), anyInt())).thenReturn(true);
        QueryLoopState state = state();
        SubAgentExecutor.SubAgentMessageHandler handler = handler(state);

        state.recordRawUsage(new Usage(6, 4, 0, 0)); // 10
        handler.onTurnEnd(5, "end_turn");
        state.recordRawUsage(new Usage(2, 4, 0, 0)); // +6 → 16
        handler.onTurnEnd(10, "end_turn");

        ArgumentCaptor<AgentCheckpoint> captured = ArgumentCaptor.forClass(AgentCheckpoint.class);
        verify(checkpointService, timeout(5_000).times(2)).save(captured.capture());
        // 两次保存各自异步执行，顺序不做保证，按 seq 对应断言。
        assertThat(captured.getAllValues())
                .extracting(checkpoint -> checkpoint.seq() + ":" + checkpoint.tokensConsumed())
                .containsExactlyInAnyOrder("0:10", "1:16");
    }
}
