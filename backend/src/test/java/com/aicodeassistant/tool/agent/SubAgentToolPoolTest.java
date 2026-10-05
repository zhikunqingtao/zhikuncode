package com.aicodeassistant.tool.agent;

import com.aicodeassistant.authorization.OperationAnalyzerRegistry;
import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.coordinator.TaskNotificationFormatter;
import com.aicodeassistant.engine.QueryConfig;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.engine.QueryMessageHandler;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.impl.FileEditTool;
import com.aicodeassistant.tool.impl.FileReadTool;
import com.aicodeassistant.tool.impl.FileWriteTool;
import com.aicodeassistant.tool.impl.GlobTool;
import com.aicodeassistant.tool.impl.GrepTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real tool identities and both public execution paths, without running a model or a tool. */
class SubAgentToolPoolTest {
    @TempDir Path workspace;

    @ParameterizedTest(name = "{0}, fork={1}")
    @CsvSource({
            "explore,false", "explore,true",
            "verification,false", "verification,true",
            "plan,false", "plan,true",
            "guide,false", "guide,true",
            "general-purpose,false", "general-purpose,true"
    })
    void deliversRoleToolPoolToQueryEngineForOrdinaryAndForkAgents(String type, boolean fork) {
        // Names and API schemas come from the production file/search tools, not copied test strings.
        List<Tool> registered = List.of(
                new FileReadTool(null, null, null, null, null),
                new FileEditTool(null, null, null, null, null, null),
                new FileWriteTool(null, null, null, null, null),
                new GlobTool(null), new GrepTool(null, null),
                unavailableTool("Bash"), unavailableTool("PowerShell"), unavailableTool("WebBrowser"),
                unavailableTool("WebFetch"), unavailableTool("WebSearch"), unavailableTool("mcp__fixture__write"),
                unavailableTool("Agent"), unavailableTool("TeamCreate"), unavailableTool("TeamDelete"),
                unavailableTool("TaskCreate"), unavailableTool("VerifyPlanExecution"),
                unavailableTool("ExitPlanMode"), unavailableTool("NotebookEdit"));
        OperationAnalyzerRegistry analyzers = mock(OperationAnalyzerRegistry.class);
        when(analyzers.isExplicitCoreTool(anyString())).thenReturn(true);
        ToolRegistry registry = new ToolRegistry(registered, analyzers);
        QueryEngine engine = mock(QueryEngine.class);
        when(engine.execute(any(QueryConfig.class), any(QueryLoopState.class), any(QueryMessageHandler.class)))
                .thenReturn(new QueryEngine.QueryResult(List.of(), null, "end_turn", null, 1));
        SessionManager sessions = mock(SessionManager.class);
        Map<String, FileStateCache> caches = new ConcurrentHashMap<>();
        when(sessions.getFileStateCache(anyString()))
                .thenAnswer(call -> caches.computeIfAbsent(call.getArgument(0), ignored -> new FileStateCache()));
        when(sessions.loadSession(anyString())).thenReturn(Optional.empty());
        LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
        SubAgentExecutor executor = new SubAgentExecutor(
                mock(AgentConcurrencyController.class), engine, registry, null, null,
                new TaskNotificationFormatter(), mock(FeatureFlagService.class), mock(CoordinatorService.class),
                sessions, null, providers, new AgentTimeoutConfig(), null,
                mock(CheckpointService.class), new ObjectMapper());

        var request = new SubAgentExecutor.AgentRequest("role-tools", "Inspect the fixture", type,
                null, SubAgentExecutor.IsolationMode.NONE, false, null, fork);
        var result = executor.executeSync(request,
                ToolUseContext.of(workspace.toString(), "parent").withParentModel("stub-model"));

        assertThat(result.status()).as(result.result()).isEqualTo(SubAgentExecutor.AgentResult.STATUS_COMPLETED);
        var captured = ArgumentCaptor.forClass(QueryConfig.class);
        verify(engine).execute(captured.capture(), any(QueryLoopState.class), any(QueryMessageHandler.class));
        QueryConfig config = captured.getValue();
        Set<String> names = config.tools().stream().map(Tool::getName).collect(Collectors.toSet());
        Set<String> expected = switch (type) {
            case "guide" -> Set.of("Read", "Glob", "Grep", "WebFetch", "WebSearch");
            case "general-purpose" -> Set.of("Read", "Edit", "Write", "Glob", "Grep", "Bash", "PowerShell",
                    "WebBrowser", "WebFetch", "WebSearch", "mcp__fixture__write", "ExitPlanMode", "NotebookEdit");
            default -> Set.of("Read", "Glob", "Grep", "Bash", "PowerShell", "WebBrowser", "WebFetch",
                    "WebSearch", "mcp__fixture__write");
        };
        // Bash/MCP remain as before: this repair is not a complete read-only execution sandbox.
        assertThat(names).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(config.toolDefinitions()).extracting(definition ->
                (String) ((Map<?, ?>) definition.get("function")).get("name"))
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(config.model()).isEqualTo("stub-model");
        assertThat(config.systemPrompt()).contains("Inspect the fixture", workspace.toString())
                .doesNotContain("你可以使用文件读写工具", "FileRead", "FileEdit", "FileWrite", "GlobTool", "GrepTool");
        if (!fork) {
            assertThat(config.systemPrompt()).contains("工具以当前请求提供的定义为准");
        }
        if ("explore".equals(type)) {
            // Explore 提示只允许引用真实工具名
            assertThat(config.systemPrompt())
                    .doesNotContain("search_codebase", "search_symbol")
                    .contains("**Glob**", "**Grep**", "**Read**");
        }
        if ("verification".equals(type)) {
            // Check the delivered contract in both execution paths, not model adherence to it.
            assertThat(config.systemPrompt()).contains(
                    "测试结果是其断言覆盖范围内的证据",
                    "至少执行一项针对主要风险",
                    "重要动态检查未完成时，不得给出功能 PASS",
                    "不得为跳过必要动态检查而自行缩小验收范围",
                    "有意行为仍须符合任务验收要求，不能据此豁免已证实的验收失败",
                    "已确认失败与未完成项并存时，整体仍为 FAIL",
                    "数值退出码仅在工具或命令明确提供时记录",
                    "缺少数值退出码本身不决定检查结论",
                    "VERDICT: PASS\n", "VERDICT: FAIL\n", "VERDICT: PARTIAL\n");
            assertThat(config.systemPrompt()).doesNotContain(
                    "测试套件结果是上下文，不是证据", "构建失败就自动 FAIL",
                    "测试失败就自动 FAIL", "阅读不是验证", "PARTIAL 仅用于环境限制",
                    "Exit code: 0（curl 正常完成");
        }
        verifyNoInteractions(providers);

        // The typed definitions must describe the same canonical capability sets as the execution path.
        BuiltInAgentDefinition definition = new AgentStrategyFactory().getAgent(type).orElseThrow();
        SubAgentExecutor.AgentDefinition executable = new AgentStrategyFactory().toAgentDefinition(definition);
        assertThat(definition.allowedTools()).isEqualTo(executable.allowedTools());
        assertThat(definition.deniedTools()).isEqualTo(executable.deniedTools());
    }

    private static Tool unavailableTool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "Tool-pool fixture"; }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public boolean isMcp() { return name.startsWith("mcp__"); }
            @Override public ToolResult call(ToolInput input, ToolUseContext context) {
                throw new AssertionError("A tool must not execute in this wiring test");
            }
        };
    }
}
