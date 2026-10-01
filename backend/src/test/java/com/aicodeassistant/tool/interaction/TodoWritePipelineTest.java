package com.aicodeassistant.tool.interaction;

import com.aicodeassistant.authorization.*;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real tool and execution pipeline; authorization persistence and websocket transport are stubbed. */
class TodoWritePipelineTest {
    @TempDir Path workspace;

    private TodoWriteTool tool;
    private ToolExecutionPipeline pipeline;
    private ToolUseContext context;
    private AuthorizationService authorization;
    private ToolExecutionGateway gateway;
    private SimpMessagingTemplate messaging;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        tool = new TodoWriteTool(messaging);
        authorization = mock(AuthorizationService.class);
        gateway = mock(ToolExecutionGateway.class);
        context = ToolUseContext.of(workspace.toString(), "todo-session")
                .withCurrentRunId("todo-run").withToolUseId("todo-call");
        var subject = new AuthorizationSubject("todo-session", "todo-run", "todo-run",
                "workspace", workspace);
        var descriptor = new OperationDescriptor(1, "TodoWrite", "invoke", "input-hash", "generic-v1",
                List.of(EffectClass.SAFE_INTERNAL), List.of(), List.of(), List.of(),
                RiskClass.SAFE, "operation-hash", "todo fixture");
        var prepared = new PreparedOperation(subject, descriptor, "attempt");
        when(authorization.prepare(any(), any(), any(), any())).thenReturn(prepared);
        when(authorization.authorizePrepared(any(), any(), any(), any(), any()))
                .thenAnswer(call -> new AuthorizedOperation(subject, descriptor, call.getArgument(2),
                        AuthorizationDiagnostic.Source.MODE, "AUTO_APPROVE",
                        null, null, null, "attempt"));
        when(gateway.execute(any(), any(), any(), any(), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(3)).run();
            ((Runnable) call.getArgument(4)).run();
            Tool executing = call.getArgument(0);
            AuthorizedOperation allowed = call.getArgument(1);
            return executing.call(allowed.executionInput(), call.getArgument(2));
        });
        var json = new ObjectMapper();
        pipeline = new ToolExecutionPipeline(new HookService(new HookRegistry(), null), json,
                new SensitiveDataFilter(), new FrozenToolInputFactory(json, 1024, 4096),
                authorization, gateway, new ToolRecoveryFramework(List.of()), null, null);
    }

    @Test
    void canonicalStatusesReachExecutionUnchanged() {
        List<Map<String, Object>> todos = List.of(
                todo("one", "PENDING"), todo("two", "IN_PROGRESS"),
                todo("three", "COMPLETE"), todo("four", "CANCELLED"));

        assertThat(execute(todos, false).isError()).isFalse();
        assertThat(tool.getTodos(context.sessionId())).containsExactlyElementsOf(todos);
        verify(gateway).execute(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "pending, PENDING", "in_progress, IN_PROGRESS", "complete, COMPLETE",
            "completed, COMPLETE", "cancelled, CANCELLED", "CoMpLeTeD, COMPLETE"
    })
    void legacyStatusAliasesReachExecutionAndAreStoredCanonically(String supplied, String expected) {
        assertThat(execute(List.of(todo("anchor", "PENDING"), todo("alias", supplied)), false)
                .isError()).isFalse();

        assertThat(tool.getTodos(context.sessionId())).containsExactly(
                todo("anchor", "PENDING"), todo("alias", expected));
        verify(gateway).execute(any(), any(), any(), any(), any());
    }

    @Test
    void completedAliasStillClearsTheFinishedList() {
        assertThat(execute(List.of(todo("finished", "completed")), false).isError()).isFalse();

        assertThat(tool.getTodos(context.sessionId())).isEmpty();
    }

    @Test
    void normalizedCompletionsStillTriggerTheVerificationNudge() throws Exception {
        ToolResult result = execute(List.of(todo("one", "completed"), todo("two", "CoMpLeTeD"),
                todo("three", "complete"), todo("remaining", "pending")), false);

        assertThat(result.isError()).isFalse();
        assertThat(new ObjectMapper().readTree(result.content()).path("verificationNudgeNeeded").asBoolean())
                .isTrue();
        assertThat(tool.getTodos(context.sessionId())).containsExactly(
                todo("one", "COMPLETE"), todo("two", "COMPLETE"),
                todo("three", "COMPLETE"), todo("remaining", "PENDING"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"done", "IN PROGRESS", ""})
    void unknownStatusIsRejectedWithoutChangingExistingTodosOrSendingUpdates(String status) {
        List<Map<String, Object>> original = List.of(todo("original", "PENDING"));
        assertThat(execute(original, false).isError()).isFalse();
        clearInvocations(authorization, gateway, messaging);

        ToolResult result = execute(List.of(todo("valid-first", "completed"), todo("replacement", status)), false);

        assertThat(result.failureCode()).isEqualTo("TODO_STATUS_INVALID");
        assertThat(result.effectState()).isEqualTo(ToolResult.EffectState.NOT_STARTED);
        assertThat(tool.getTodos(context.sessionId())).containsExactlyElementsOf(original);
        // TodoWrite validates status inside call(); this is not a pre-gateway schema rejection.
        verify(gateway).execute(any(), any(), any(), any(), any());
        verifyNoInteractions(messaging);
    }

    @Test
    void missingStatusKeepsTheExistingOptionalFieldContract() {
        List<Map<String, Object>> todos = List.of(Map.of("id", "one", "content", "Needs triage"));

        assertThat(execute(todos, false).isError()).isFalse();
        assertThat(tool.getTodos(context.sessionId())).containsExactlyElementsOf(todos);
        assertThat(tool.getTodos(context.sessionId()).getFirst()).doesNotContainKey("status");
    }

    @Test
    void explicitNullStatusKeepsTheExistingRuntimeBehavior() {
        Map<String, Object> todo = new LinkedHashMap<>();
        todo.put("id", "one");
        todo.put("content", "Needs triage");
        todo.put("status", null);

        assertThat(execute(List.of(todo), false).isError()).isFalse();

        assertThat(tool.getTodos(context.sessionId())).containsExactly(todo);
        assertThat(tool.getTodos(context.sessionId()).getFirst()).containsEntry("status", null);
    }

    @Test
    void mergeUpdatesIdentifiedTodosAndKeepsEveryUnidentifiedTodo() {
        Map<String, Object> previousWithoutId = Map.of("content", "Existing without id", "status", "PENDING");
        Map<String, Object> nextWithoutId = Map.of("content", "New without id", "status", "IN_PROGRESS");
        Map<String, Object> blankId = Map.of("id", "", "content", "Blank id", "status", "PENDING");
        assertThat(execute(List.of(todo("same", "PENDING"), previousWithoutId), false).isError()).isFalse();

        assertThat(execute(List.of(todo("same", "COMPLETE"), todo("new", "PENDING"),
                nextWithoutId, blankId), true).isError()).isFalse();

        assertThat(tool.getTodos(context.sessionId())).containsExactly(
                todo("same", "COMPLETE"), todo("new", "PENDING"),
                previousWithoutId, nextWithoutId, blankId);
    }

    private ToolResult execute(List<Map<String, Object>> todos, boolean merge) {
        return pipeline.execute(tool, ToolInput.from(Map.of("todos", todos, "merge", merge)), context).result();
    }

    private static Map<String, Object> todo(String id, String status) {
        return Map.of("id", id, "content", "Task " + id, "status", status);
    }
}
