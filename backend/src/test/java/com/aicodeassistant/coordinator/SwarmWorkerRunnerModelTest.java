package com.aicodeassistant.coordinator;

import com.aicodeassistant.engine.QueryConfig;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SwarmWorkerRunnerModelTest {
    private final QueryEngine engine = mock(QueryEngine.class);
    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final ModelRegistry models = mock(ModelRegistry.class);
    private final SwarmWorkerRunner runner = new SwarmWorkerRunner(engine, tools, models);

    @org.junit.jupiter.api.Test
    void cancelledWaiterDoesNotReleaseAnExecutingWorkerLease() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        var gate = new com.aicodeassistant.session.SessionExecutionGate();
        var ds = mock(javax.sql.DataSource.class);
        var sessions = mock(com.aicodeassistant.session.SessionManager.class);
        when(sessions.acquireBackgroundLease("test-session")).thenAnswer(call -> gate.acquireBackground(ds, "test-session"));
        ReflectionTestUtils.setField(runner, "sessions", sessions);
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            entered.countDown(); finish.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new QueryEngine.QueryResult(List.of(), null, "end_turn", null, 1);
        });
        var config = new SwarmConfig("test", 1, SwarmConfig.SwarmBackendType.IN_PROCESS,
                "test-model", List.of(), List.of(), null, 1000, 10, false);
        var future = runner.startWorker("worker", "test", config, ToolUseContext.of("/tmp", "test-session"), mock(SwarmState.class));
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            future.cancel(true);
            assertNull(gate.tryAcquireMerge(ds, "test-session", "merge"));
        } finally { finish.countDown(); }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        com.aicodeassistant.session.SessionExecutionGate.Token token = null;
        while (token == null && System.nanoTime() < deadline) {
            token = gate.tryAcquireMerge(ds, "test-session", "merge");
            if (token == null) Thread.sleep(10);
        }
        assertNotNull(token); token.close();
    }

    @BeforeEach
    void setUp() {
        when(tools.getEnabledTools()).thenReturn(List.of());
        when(models.getCapabilities(anyString())).thenReturn(ModelCapabilities.DEFAULT);
        when(engine.execute(any(), any(), any())).thenReturn(
                new QueryEngine.QueryResult(List.of(), null, "end_turn", null, 1));
    }

    static Stream<Arguments> modelSelections() {
        return Stream.of(
                Arguments.of(null, "kimi-k3", "kimi-k3"),
                Arguments.of("", "deepseek-flash", "deepseek-flash"),
                Arguments.of(" ", "qwen3.8-max-0902", "qwen3.8-max-0902"),
                Arguments.of(null, "deepseek-v4.1-flash", "deepseek-v4.1-flash"),
                Arguments.of("deepseek-flash", "kimi-k3", "deepseek-flash"),
                Arguments.of("kimi-k3", null, "kimi-k3"));
    }

    @ParameterizedTest
    @MethodSource("modelSelections")
    void inheritsUserModelUnlessWorkerModelIsExplicit(String selected, String parent, String expected) {
        execute(selected, parent);
        var query = ArgumentCaptor.forClass(QueryConfig.class);
        var state = ArgumentCaptor.forClass(QueryLoopState.class);
        verify(engine).execute(query.capture(), state.capture(), any());
        assertEquals(expected, query.getValue().model());
        assertEquals(expected, state.getValue().getToolUseContext().parentModel());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingParentModelDoesNotSilentlySelectAnotherProvider(String parent) {
        var error = assertThrows(IllegalArgumentException.class, () -> execute(null, parent));
        assertTrue(error.getMessage().contains("parent query model"));
        verifyNoInteractions(engine, tools, models);
    }

    private void execute(String selected, String parent) {
        var config = new SwarmConfig("test", 1, SwarmConfig.SwarmBackendType.IN_PROCESS,
                selected, List.of(), List.of(), null, 1000, 10, false);
        ReflectionTestUtils.invokeMethod(runner, "executeWorkerLoop", "worker-1", "test prompt", config,
                ToolUseContext.of("/tmp", "test-session").withParentModel(parent), mock(SwarmState.class));
    }
}
