package com.aicodeassistant.tool;

import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.engine.ElicitationService;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.TaskStatus;
import com.aicodeassistant.sandbox.SandboxManager;
import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.bash.BashCommandClassifier;
import com.aicodeassistant.tool.bash.BashErrorClassifier;
import com.aicodeassistant.tool.bash.BashOutputProcessor;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.config.ConfigTool;
import com.aicodeassistant.tool.impl.BashTool;
import com.aicodeassistant.tool.impl.EnterPlanModeTool;
import com.aicodeassistant.tool.impl.ExitPlanModeTool;
import com.aicodeassistant.tool.impl.GrepTool;
import com.aicodeassistant.tool.interaction.AskUserQuestionTool;
import com.aicodeassistant.tool.interaction.BriefTool;
import com.aicodeassistant.tool.interaction.TodoWriteTool;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.aicodeassistant.tool.repl.REPLTool;
import com.aicodeassistant.tool.repl.ReplManager;
import com.aicodeassistant.tool.task.TaskCoordinator;
import com.aicodeassistant.tool.task.TaskCreateTool;
import com.aicodeassistant.tool.task.TaskGetTool;
import com.aicodeassistant.tool.task.TaskListTool;
import com.aicodeassistant.tool.task.TaskShellExecutor;
import com.aicodeassistant.tool.task.TaskStopTool;
import com.aicodeassistant.tool.task.TaskUpdateTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 工具自述文本与真实 schema 的定向契约测试 — 只断言本批对齐的文本点。
 */
class ToolPromptContractTest {

    @Test
    void taskDefinitionsDescribeBackgroundExecutionAndActualRecords() {
        TaskCoordinator coordinator = mock(TaskCoordinator.class);
        Tool create = new TaskCreateTool(coordinator, mock(SubAgentExecutor.class),
                mock(ToolRegistry.class), mock(TaskShellExecutor.class));
        String createDescription = apiDescription(create);
        assertAll(
                () -> assertTrue(createDescription.contains("immediate background execution")),
                () -> assertTrue(createDescription.contains("TodoWrite")),
                () -> assertTrue(createDescription.contains("not the execution result")),
                () -> assertFalse(createDescription.contains("structured task list")),
                () -> assertFalse(createDescription.contains("set up dependencies")),
                () -> assertEquals(Set.of("agent", "shell", "local_workflow", "monitor_mcp", "dream"),
                        Set.copyOf((List<?>) ((Map<?, ?>) apiProperties(create).get("taskType")).get("enum"))));

        Tool list = new TaskListTool(coordinator);
        Tool get = new TaskGetTool(coordinator);
        Tool update = new TaskUpdateTool(coordinator);
        for (Tool tool : List.of(list, get)) {
            String description = apiDescription(tool);
            for (TaskStatus status : TaskStatus.values()) {
                assertTrue(description.contains(status.name()), tool.getName() + " must describe " + status);
            }
            assertFalse(description.contains("**subject**"));
            assertFalse(description.contains("no owner"));
            assertFalse(description.contains("task dependencies"));
        }
        assertAll(
                () -> assertTrue(apiDescription(list).contains("creation time")),
                () -> assertTrue(apiDescription(list).contains("newest to oldest")),
                () -> assertTrue(apiDescription(get).contains("child task count")),
                () -> assertTrue(apiDescription(get).contains("output and error")),
                () -> assertTrue(apiDescription(update).contains("does not start or stop execution")),
                () -> assertTrue(apiDescription(update).contains("status parameter is rejected")),
                () -> assertTrue(apiDescription(update).contains("current session")),
                () -> assertTrue(apiDescription(update).contains("TaskStop")),
                () -> assertEquals(Set.of("taskId", "output"), apiProperties(update).keySet()),
                () -> assertFalse(((Map<?, ?>) apiProperties(list).get("status")).containsKey("enum")));
    }

    static Stream<Tool> unavailablePlanTools() {
        return Stream.of(new EnterPlanModeTool(), new ExitPlanModeTool());
    }

    @ParameterizedTest
    @MethodSource("unavailablePlanTools")
    void planToolsReportUnavailabilityWithoutClaimingEffects(Tool tool) {
        String parameter = tool instanceof EnterPlanModeTool ? "reason" : "plan_summary";
        assertEquals(Set.of(parameter), apiProperties(tool).keySet());
        String description = apiDescription(tool);
        assertTrue(description.contains("PLAN_MODE_UNAVAILABLE"));
        assertTrue(description.contains("Do not call this tool"));
        for (Map<String, Object> values : List.of(Map.<String, Object>of(), Map.<String, Object>of(parameter, "fixture"))) {
            ToolResult result = tool.call(ToolInput.from(values), ToolUseContext.of("/workspace", "session-fixture"));
            assertAll(
                    () -> assertEquals(ToolResult.ExecutionStatus.FAILED, result.executionStatus()),
                    () -> assertEquals(ToolResult.ToolFailureType.VALIDATION, result.failureType()),
                    () -> assertEquals("PLAN_MODE_UNAVAILABLE", result.failureCode()),
                    () -> assertEquals(ToolResult.Retryability.NEVER, result.retryability()),
                    () -> assertEquals(ToolResult.EffectState.NOT_STARTED, result.effectState()),
                    () -> assertFalse(result.isRetryable()),
                    () -> assertFalse(result.metadata().containsKey("mode")),
                    () -> assertTrue(result.content().contains("No permission mode was changed")),
                    () -> assertTrue(result.content().contains("no approval request was submitted")),
                    () -> assertTrue(result.content().contains("no plan file was displayed")));
            if (!values.isEmpty()) assertTrue(result.content().contains("fixture"));
        }
    }

    @Test
    void questionDefinitionDoesNotInventPlanApprovalOrVisibility() {
        Tool tool = new AskUserQuestionTool(mock(ElicitationService.class));
        String description = apiDescription(tool);
        assertAll(
                () -> assertTrue(description.contains("clarify requirements")),
                () -> assertFalse(description.contains("ExitPlanMode")),
                () -> assertFalse(description.contains("cannot see the plan")),
                () -> assertTrue(apiProperties(tool).containsKey("questions")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "session", "custom"})
    void briefDefinitionAndResultsDescribeOnlyAvailableContext(String scope) {
        Tool tool = new BriefTool();
        String description = apiDescription(tool);
        ToolResult result = tool.call(ToolInput.from(Map.of("scope", scope, "topic", "API Design")),
                ToolUseContext.of("/workspace", "session-fixture"));
        assertAll(
                () -> assertTrue(description.contains("history are not collected")),
                () -> assertTrue(description.contains("does not summarize")),
                () -> assertFalse(description.contains("will be available")),
                () -> assertEquals(Set.of("scope", "topic"), apiProperties(tool).keySet()),
                () -> assertEquals(ToolResult.ExecutionStatus.SUCCEEDED, result.executionStatus()),
                () -> assertEquals(ToolResult.EffectState.NONE, result.effectState()),
                () -> assertTrue(result.content().contains("Context only:")),
                () -> assertFalse(result.content().contains("will be available")));
        switch (scope) {
            case "project" -> {
                assertTrue(result.content().contains("Working directory: /workspace"));
                assertTrue(result.content().contains("Session: session-fixture"));
                assertTrue(result.content().contains("Git status and commit history have not been collected"));
            }
            case "session" -> {
                assertTrue(result.content().contains("Session: session-fixture"));
                assertTrue(result.content().contains("session history, tool calls, and actions have not been collected"));
            }
            case "custom" -> {
                assertTrue(result.content().contains("Custom Brief: API Design"));
                assertTrue(result.content().contains("Working directory: /workspace"));
                assertTrue(result.content().contains("without generated analysis"));
            }
            default -> throw new AssertionError("Unexpected test scope: " + scope);
        }
    }

    private static String apiDescription(Tool tool) {
        return (String) ((Map<?, ?>) tool.toToolDefinition().get("function")).get("description");
    }

    private static Map<?, ?> apiProperties(Tool tool) {
        Map<?, ?> function = (Map<?, ?>) tool.toToolDefinition().get("function");
        return (Map<?, ?>) ((Map<?, ?>) function.get("parameters")).get("properties");
    }

    @Test
    void taskStopPromptUsesSchemaParameterName() {
        String prompt = new TaskStopTool(mock(TaskCoordinator.class)).prompt();
        assertAll(
                () -> assertTrue(prompt.contains("taskId")),
                () -> assertFalse(prompt.contains("task_id")));
    }

    @Test
    void replPromptUsesSchemaParameterName() {
        String prompt = new REPLTool(mock(ReplManager.class)).prompt();
        assertAll(
                () -> assertTrue(prompt.contains("sessionId")),
                () -> assertFalse(prompt.contains("session_id")));
    }

    @Test
    void todoWritePromptListsSchemaTaskStatesWithoutActiveForm() {
        String prompt = new TodoWriteTool(mock(SimpMessagingTemplate.class)).prompt();
        assertAll(
                () -> assertTrue(prompt.contains("PENDING, IN_PROGRESS, COMPLETE, CANCELLED")),
                () -> assertFalse(prompt.contains("activeForm")));
    }

    @Test
    void configPromptUsesActionKeyValueSchema() {
        String prompt = new ConfigTool(mock(LlmProviderRegistry.class)).prompt();
        assertAll(
                () -> assertTrue(prompt.contains("{ \"action\": \"get\", \"key\": \"theme\" }")),
                () -> assertTrue(prompt.contains(
                        "{ \"action\": \"set\", \"key\": \"theme\", \"value\": \"dark\" }")),
                () -> assertTrue(prompt.contains(
                        "{ \"action\": \"set\", \"key\": \"language\", \"value\": \"en\" }")),
                () -> assertTrue(prompt.contains("changes apply only to this tool's runtime store")),
                () -> assertTrue(prompt.contains("does not switch the session model")),
                () -> assertTrue(prompt.contains("use action \"set\"")),
                () -> assertFalse(prompt.contains("\"setting\"")));
    }

    @Test
    void grepSchemaCoversAllImplementedParameters() {
        GrepTool tool = new GrepTool(mock(KeyFileTracker.class), mock(PathSecurityService.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getInputSchema().get("properties");
        String prompt = tool.prompt();
        String description = tool.getDescription();
        assertAll(
                // schema 必须包含实现读取的全部键
                () -> assertTrue(properties.keySet().containsAll(List.of(
                        "pattern", "path", "glob", "include", "exclude", "output_mode",
                        "-i", "head_limit", "offset", "multiline", "type", "-A", "-B", "-C"))),
                () -> assertTrue(prompt.contains("glob/include/exclude")),
                () -> assertTrue(prompt.contains("`-i`")),
                () -> assertTrue(prompt.contains("head_limit")),
                () -> assertTrue(prompt.contains("offset")),
                () -> assertTrue(prompt.contains("multiline")),
                () -> assertTrue(prompt.contains("`type`")),
                () -> assertTrue(prompt.contains("`-C`/`-B`/`-A`")),
                () -> assertTrue(description.contains("context lines")),
                () -> assertTrue(description.contains("require ripgrep")));
    }

    @Test
    void bashPromptDoesNotPromiseAFixedDefaultTimeout() {
        BashTool tool = new BashTool(
                mock(BashSecurityAnalyzer.class), mock(BashCommandClassifier.class),
                mock(ShellStateManager.class), mock(BashOutputProcessor.class),
                mock(SandboxManager.class), mock(CommandBlacklistService.class),
                mock(BashErrorClassifier.class), mock(ManagedProcessRunner.class));
        String prompt = tool.prompt();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getInputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> timeoutSchema = (Map<String, Object>) properties.get("timeout");
        String schemaDescription = (String) timeoutSchema.get("description");
        assertAll(
                () -> assertTrue(prompt.contains(
                        "(the maximum is server-configured; default 600000ms / 10 minutes)")),
                () -> assertTrue(prompt.contains(
                        "When omitted, a recommended timeout based on the command type is applied (fallback: 120000ms / 2 minutes)")),
                () -> assertFalse(prompt.contains("up to 600000ms")),
                () -> assertFalse(prompt.contains("By default, your command will timeout after 120000ms")),
                () -> assertFalse(prompt.contains("notified when the command completes")),
                () -> assertFalse(prompt.contains("would like to be notified")),
                () -> assertTrue(schemaDescription.contains("fallback 120000)")),
                () -> assertTrue(schemaDescription.contains("the maximum is server-configured (default 600000)")),
                () -> assertFalse(schemaDescription.contains("default 120000")));
    }
}
