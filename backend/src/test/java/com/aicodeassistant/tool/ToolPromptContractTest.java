package com.aicodeassistant.tool;

import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.llm.LlmProviderRegistry;
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
import com.aicodeassistant.tool.impl.GrepTool;
import com.aicodeassistant.tool.interaction.TodoWriteTool;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.aicodeassistant.tool.repl.REPLTool;
import com.aicodeassistant.tool.repl.ReplManager;
import com.aicodeassistant.tool.task.TaskCoordinator;
import com.aicodeassistant.tool.task.TaskStopTool;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 工具自述文本与真实 schema 的定向契约测试 — 只断言本批对齐的文本点。
 */
class ToolPromptContractTest {

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
