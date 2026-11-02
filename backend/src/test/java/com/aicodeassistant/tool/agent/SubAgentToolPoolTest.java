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
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.config.SendMessageTool;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real tool identities and ordinary/fork/background execution, without running a model or a tool. */
class SubAgentToolPoolTest {
    @TempDir Path workspace;

    @ParameterizedTest(name = "{0}, fork={1}, background={2}")
    @CsvSource({
            "explore,false,false", "explore,true,false",
            "verification,false,false", "verification,true,false",
            "plan,false,false", "plan,true,false",
            "guide,false,false", "guide,true,false",
            "general-purpose,false,false", "general-purpose,true,false",
            "general-purpose,false,true"
    })
    void deliversRoleToolPoolToQueryEngineForOrdinaryForkAndBackgroundAgents(
            String type, boolean fork, boolean background) throws Exception {
        // Include the real messaging description to exercise the one-shot reporting contract alongside it.
        SendMessageTool sendMessage = new SendMessageTool(mock(SimpMessagingTemplate.class));
        // Names and API schemas come from the production file/search tools, not copied test strings.
        List<Tool> registered = List.of(
                new FileReadTool(null, null, null, null, null),
                new FileEditTool(null, null, null, null, null, null),
                new FileWriteTool(null, null, null, null, null),
                new GlobTool(null), new GrepTool(null, null), sendMessage,
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
        BackgroundAgentTracker tracker = mock(BackgroundAgentTracker.class);
        SubAgentExecutor executor = new SubAgentExecutor(
                mock(AgentConcurrencyController.class), engine, registry, tracker, null,
                new TaskNotificationFormatter(), mock(FeatureFlagService.class), mock(CoordinatorService.class),
                sessions, null, providers, new AgentTimeoutConfig(), null,
                mock(CheckpointService.class), new ObjectMapper());

        var request = new SubAgentExecutor.AgentRequest("role-tools-" + UUID.randomUUID(), "Inspect the fixture", type,
                null, SubAgentExecutor.IsolationMode.NONE, background, null, fork);
        var parent = ToolUseContext.of(workspace.toString(), "parent").withParentModel("stub-model");
        if (background) {
            CountDownLatch cleanedUp = new CountDownLatch(1);
            var outerLease = mock(SessionExecutionGate.BackgroundLease.class);
            var innerLease = mock(SessionExecutionGate.BackgroundLease.class);
            when(sessions.acquireBackgroundLease("parent")).thenReturn(outerLease, innerLease);
            // Lease closure is the last finally action, after completion publication and resource cleanup.
            doAnswer(call -> { cleanedUp.countDown(); return null; }).when(outerLease).close();
            var launched = executor.executeAsync(request, parent);
            try {
                assertThat(launched.status()).isEqualTo(SubAgentExecutor.AgentResult.STATUS_ASYNC_LAUNCHED);
                assertThat(cleanedUp.await(10, TimeUnit.SECONDS)).as("background finally cleanup completed").isTrue();
                var completed = ArgumentCaptor.forClass(SubAgentExecutor.AgentResult.class);
                verify(tracker).markCompleted(eq(request.agentId()), completed.capture());
                assertThat(completed.getValue().status()).isEqualTo(SubAgentExecutor.AgentResult.STATUS_COMPLETED);
                verify(tracker, never()).markFailed(anyString(), anyString());
                verify(engine, atLeastOnce()).removeAbortContext("subagent-" + request.agentId());
                verify(sessions, atLeastOnce()).removeFileStateCache("subagent-" + request.agentId());
            } finally {
                if (cleanedUp.getCount() == 0) Files.deleteIfExists(Path.of(launched.outputFile()));
            }
        } else {
            var result = executor.executeSync(request, parent);
            assertThat(result.status()).as(result.result()).isEqualTo(SubAgentExecutor.AgentResult.STATUS_COMPLETED);
        }
        var captured = ArgumentCaptor.forClass(QueryConfig.class);
        verify(engine).execute(captured.capture(), any(QueryLoopState.class), any(QueryMessageHandler.class));
        QueryConfig config = captured.getValue();
        Set<String> names = config.tools().stream().map(Tool::getName).collect(Collectors.toSet());
        Set<String> expected = switch (type) {
            case "guide" -> Set.of("Read", "Glob", "Grep", "WebFetch", "WebSearch");
            case "general-purpose" -> Set.of("Read", "Edit", "Write", "Glob", "Grep", "Bash", "PowerShell",
                    "WebBrowser", "WebFetch", "WebSearch", "mcp__fixture__write", "ExitPlanMode", "NotebookEdit",
                    "SendMessage");
            default -> Set.of("Read", "Glob", "Grep", "Bash", "PowerShell", "WebBrowser", "WebFetch",
                    "WebSearch", "mcp__fixture__write", "SendMessage");
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
        // Ordinary, fork and background execution share the same bounded final-result protocol.
        assertThat(config.systemPrompt()).containsOnlyOnce("## 委派任务的范围、证据与回传")
                .contains("原任务范围、原修改范围和现有授权", "始终遵守当前角色约束",
                        "专业或跨领域方法不产生额外授权", "按任务复杂度和风险选择必要的目标检查",
                        "适用时覆盖边界输入", "检查也须遵守角色和授权限制",
                        "涉及当前项目、版本或配置的事实结论应依据已有或实际查得的证据",
                        "已有证据充分时不重复查证，通用概念可直接解释", "无法验证的明确标为\"未验证\"",
                        "对上层目标的影响", "本次委派通过你的最终回复向父代理返回结果",
                        "停止依赖该决定的动作", "在最终回复中列出证据、影响、待决定项和未完成部分",
                        "可以继续独立且已授权的工作", "不要通过 SendMessage 请求或等待父代理的中途决定",
                        "不要把等待中的事项报告为已完成")
                .doesNotContain("不要尝试超出上述范围的任务");
        if (!"guide".equals(type)) {
            // Clarifying the reporting protocol must not remove or rewrite the communication tool.
            Map<?, ?> sendMessageDefinition = config.toolDefinitions().stream()
                    .map(definition -> (Map<?, ?>) definition.get("function"))
                    .filter(definition -> "SendMessage".equals(definition.get("name")))
                    .findFirst().orElseThrow();
            assertThat(sendMessageDefinition.get("description")).isEqualTo(sendMessage.prompt());
        }
        if ("general-purpose".equals(type)) {
            assertThat(config.systemPrompt())
                    .contains("先调查可查明的歧义", "原任务范围及现有授权内的低风险、可逆实现细节",
                            "应明确说明证据和待决定项，不自行决定", "明确报告阻碍，不静默绕过")
                    .doesNotContain("严格按照任务提示执行", "仍无法确定的，做出合理选择", "立即报告");
        }
        if ("explore".equals(type) || "plan".equals(type)) {
            assertThat(config.systemPrompt()).contains(
                    "你不能编辑、创建或删除任何文件", "你不能执行修改状态的命令");
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
