package com.aicodeassistant.prompt;

import com.aicodeassistant.config.ProjectPromptLoader;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorPromptBuilder;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.context.ProjectContextService;
import com.aicodeassistant.context.SystemPromptSectionCache;
import com.aicodeassistant.service.ProjectMemoryService;
import com.aicodeassistant.service.PromptCacheBreakDetector;
import com.aicodeassistant.engine.ToolResultSummarizer;
import com.aicodeassistant.mcp.McpConnectionStatus;
import com.aicodeassistant.mcp.McpServerConnection;
import com.aicodeassistant.service.GitService;
import com.aicodeassistant.state.AppState;
import com.aicodeassistant.state.AppStateStore;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.impl.BashTool;
import com.aicodeassistant.tool.impl.FileEditTool;
import com.aicodeassistant.tool.impl.FileReadTool;
import com.aicodeassistant.tool.impl.FileWriteTool;
import com.aicodeassistant.tool.impl.GlobTool;
import com.aicodeassistant.tool.impl.GrepTool;
import com.aicodeassistant.tool.powershell.PowerShellTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SystemPromptBuilder 单元测试。
 *
 * @see SystemPromptBuilder
 */
class SystemPromptBuilderTest {

    private ProjectPromptLoader projectPromptLoader;
    private FeatureFlagService featureFlags;
    private GitService gitService;
    private ProjectContextService projectContextService;
    private SystemPromptSectionCache promptSectionCache;
    private PromptCacheBreakDetector promptCacheBreakDetector;
    private ProjectMemoryService projectMemoryService;
    private SystemPromptBuilder builder;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        projectPromptLoader = mock(ProjectPromptLoader.class);
        featureFlags = mock(FeatureFlagService.class);
        gitService = mock(GitService.class);
        projectContextService = mock(ProjectContextService.class);
        when(projectContextService.getContext(any())).thenReturn(null);
        when(projectContextService.formatProjectContext(any())).thenReturn("");
        promptSectionCache = new SystemPromptSectionCache();
        promptCacheBreakDetector = mock(PromptCacheBreakDetector.class);
        when(promptCacheBreakDetector.computeBreakpointPosition(any())).thenReturn(0);
        projectMemoryService = mock(ProjectMemoryService.class);
        builder = new SystemPromptBuilder(projectPromptLoader, featureFlags, gitService, null, null, projectContextService, null, promptSectionCache, promptCacheBreakDetector, projectMemoryService);

        // 默认 mock 行为
        when(gitService.getGitStatus(any(Path.class))).thenReturn("main (clean)");
        when(featureFlags.isEnabled(anyString())).thenReturn(false);
    }

    @Test
    void testBuildDefaultSystemPrompt() {
        // Given
        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then
        assertNotNull(prompt);
        assertTrue(prompt.contains("交互式 AI 编码助手"));
        assertTrue(prompt.contains("# 系统"));
        assertTrue(prompt.contains("# 执行任务"));
        assertTrue(prompt.contains("# 环境"));
        assertTrue(prompt.contains("gpt-4o"));
        assertTrue(prompt.contains("超时后会尝试终止命令并返回超时信息；停止是否确认以实际结果为准，未确认时不得假定命令已停止")
                        && !prompt.contains("超时后命令会被终止"),
                "Bash timeout guidance must not imply confirmed termination");
    }

    @Test
    void defaultPromptIncludesScopedAuthorizationAndClarificationGuidance() {
        String prompt = builder.buildDefaultSystemPrompt(List.of(), "gpt-4o");

        assertFalse(prompt.contains("不要对用户已经决定要做的任务提出质疑"));
        assertFalse(prompt.contains("1-2 次工具调用"));
        assertFalse(prompt.contains("能通过读取代码、文档或运行命令消除的歧义，先自己调查"));
        assertFalse(prompt.contains("但不要拒绝或拖延用户已决定的任务"));
        assertFalse(prompt.contains("只有在调查后确实卡住时"));
        assertFalse(prompt.contains("## 安全操作（可自由执行）"));
        assertFalse(prompt.contains("## 风险操作（先询问用户）"));
        assertTrue(prompt.contains("## 风险操作（需要明确授权）"));
        assertFalse(prompt.contains("会改变范围、架构、依赖，或与用户指定的做法不同时"));
        assertTrue(prompt.contains("如果关键歧义无法通过调查消除，请求澄清而不是猜错。"));
        assertTrue(prompt.contains("可以简要指出一次更有效的方法或关键风险"));
        assertTrue(prompt.contains("明确覆盖操作类型、对象和影响范围的用户授权"));
        assertTrue(prompt.contains("用户的明确请求本身可以构成对相应操作及范围的授权"));
        assertTrue(prompt.contains("结合已有上下文判断，授权不明确时先询问"));
        assertTrue(prompt.contains("已有明确授权覆盖的操作及其必要执行步骤直接继续"));
        assertTrue(prompt.contains("不因操作属于风险类别而重复确认"));
        assertTrue(prompt.contains("工具权限和审批仍须遵守"));
        assertTrue(prompt.contains("必要执行步骤不包括强制推送、跳过钩子（--no-verify）、git reset --hard"));
        assertTrue(prompt.contains("未被已有明确授权覆盖时，须在采用前另行确认"));
        assertTrue(prompt.contains("仍不得绕过安全检查或工具权限"));
        assertTrue(prompt.contains("操作对象、影响范围或实质风险将超出已有授权时"));
        assertTrue(prompt.contains("在越界操作前请求确认"));
        assertTrue(prompt.contains("等待确认时，可继续已授权且不依赖待定事项的工作"));
        assertTrue(prompt.contains("会调整范围、架构或依赖时，按下方授权规则判断是否需要确认"));
    }

    @Test
    void defaultPromptIncludesScopedEvidenceAndReuseGuidance() {
        String prompt = builder.buildDefaultSystemPrompt(List.of(), "gpt-4o");
        String heading = "## 以原始证据为准";

        assertTrue(prompt.contains(heading));
        assertEquals(prompt.indexOf(heading), prompt.lastIndexOf(heading));
        assertTrue(prompt.contains("对于依赖当前项目、运行环境"));
        assertTrue(prompt.contains("API 行为、真实数据或时效信息的关键事实"));
        assertTrue(prompt.contains("用用户已提供或实际读取、运行得到的"));
        assertTrue(prompt.contains("已有证据足够且仍适用时直接复用，不重复查证"));
        assertTrue(prompt.contains("稳定的通用概念解释、翻译"));
        assertTrue(prompt.contains("基于所给材料的摘要等任务可直接完成"));
        assertFalse(prompt.contains("结论来自你实际读取或运行得到的"));
        assertFalse(prompt.contains("不凭文件名、记忆或\"通常如此\"下结论"));
    }

    @Test
    void defaultPromptContainsGoalQualitySectionOnceBetweenDoingTasksAndActions() {
        String prompt = builder.buildDefaultSystemPrompt(List.of(), "gpt-4o");
        String heading = "# 目标、质量与证据";

        int idx = prompt.indexOf(heading);
        assertTrue(idx >= 0, "goal/quality section missing");
        assertEquals(idx, prompt.lastIndexOf(heading), "goal/quality section must appear exactly once");
        assertTrue(prompt.contains(SystemPromptBuilder.GOAL_QUALITY_SECTION));
        String clarification = "## 调查与澄清";
        int clarificationIdx = prompt.indexOf(clarification);
        assertTrue(clarificationIdx >= 0, "shared investigation/clarification guidance missing");
        assertEquals(clarificationIdx, prompt.lastIndexOf(clarification),
                "investigation/clarification guidance must appear exactly once");
        assertTrue(prompt.contains("遵守用户明确提出的确认要求。"));
        assertTrue(prompt.contains("能在授权范围内通过上下文、源码、文档、命令或小规模试验查明的问题，先自行调查"));
        assertTrue(prompt.indexOf("# 执行任务") < idx, "must follow DoingTasks section");
        assertTrue(idx < prompt.indexOf("# 谨慎执行操作"), "must precede Actions section");
    }

    @Test
    void effectiveMainPromptAndShellSchemasUseActualToolNamesWithoutChangingOverrides() {
        Tool read = new FileReadTool(null, null, null, null, null);
        Tool edit = new FileEditTool(null, null, null, null, null, null);
        Tool write = new FileWriteTool(null, null, null, null, null);
        Tool glob = new GlobTool(null);
        Tool grep = new GrepTool(null, null);
        // Only schema/prompt methods run: no shell, model, or file operation is executed.
        List<Tool> tools = List.of(read, edit, write, glob, grep,
                mock(BashTool.class, CALLS_REAL_METHODS), mock(PowerShellTool.class, CALLS_REAL_METHODS));
        var effective = new EffectiveSystemPromptBuilder(builder, featureFlags,
                mock(CoordinatorPromptBuilder.class), mock(CoordinatorService.class));

        String prompt = effective.buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults(), tools, "stub-model", tempDir);
        assertAll(
                () -> assertTrue(prompt.contains("读取文件使用 " + read.getName())),
                () -> assertTrue(prompt.contains("编辑文件使用 " + edit.getName())),
                () -> assertTrue(prompt.contains("创建文件使用 " + write.getName())),
                () -> assertTrue(prompt.contains("搜索文件使用 " + glob.getName())),
                () -> assertTrue(prompt.contains("搜索文件内容使用 " + grep.getName())));
        String deliveredGuidance = prompt + tools.stream().map(Tool::toToolDefinition).toList();
        var legacyName = java.util.regex.Pattern.compile("\\b(?:File(?:Read|Edit|Write)(?:Tool)?|GlobTool|GrepTool)\\b")
                .matcher(deliveredGuidance);
        assertFalse(legacyName.find(), () -> "Model-visible guidance contains an unregistered name: "
                + deliveredGuidance.substring(Math.max(0, legacyName.start() - 60),
                Math.min(deliveredGuidance.length(), legacyName.end() + 100)));

        assertEquals("OVERRIDE", effective.buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults().withOverride("OVERRIDE").withCustom("CUSTOM").withAppend("APPEND"),
                tools, "stub-model", tempDir));
        assertEquals("CUSTOM\n\nAPPEND", effective.buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults().withCustom("CUSTOM").withAppend("APPEND"),
                tools, "stub-model", tempDir));
    }

    @Test
    void testStaticSectionsPresent() {
        // Given
        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then - 验证所有静态段都存在
        assertTrue(prompt.contains("交互式 AI 编码助手"), "Intro section missing");
        assertTrue(prompt.contains("# 系统"), "System section missing");
        assertTrue(prompt.contains("# 执行任务"), "DoingTasks section missing");
        assertTrue(prompt.contains("# 谨慎执行操作"), "Actions section missing");
        assertTrue(prompt.contains("# 使用你的工具"), "UsingTools section missing");
        assertTrue(prompt.contains("# 语调和风格"), "ToneStyle section missing");
        // 外部用户使用 "# 输出效率" 段落
        assertTrue(prompt.contains("# 输出效率"), "OutputEfficiency section missing");
    }

    @Test
    void testDynamicSections_EnvInfo() {
        // Given
        when(gitService.getGitStatus(any(Path.class))).thenReturn("feature-branch (+2~3)");

        List<Tool> tools = List.of();
        String model = "qwen3.7-plus";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then - computeEnvInfo 使用 "# Environment" 格式
        assertTrue(prompt.contains("# 环境"));
        assertTrue(prompt.contains("qwen3.7-plus"));
        assertTrue(prompt.contains("平台："));
        assertTrue(prompt.contains("Shell："));
    }

    @Test
    void defaultPromptUsesTheSuppliedSessionWorkingDirectory() {
        String prompt = builder.buildDefaultSystemPrompt(
                List.of(), "gpt-4o", tempDir);

        assertTrue(prompt.contains(
                "主工作目录：" + tempDir));
        verify(projectContextService).getContext(tempDir);
    }

    @Test
    void defaultPromptCacheIsIsolatedByExplicitSession() throws Exception {
        Path projectA = Files.createDirectory(tempDir.resolve("project-a"));
        Path projectB = Files.createDirectory(tempDir.resolve("project-b"));

        String promptA = builder.buildDefaultSystemPrompt(
                List.of(), "model-a", projectA, "session-a");
        String promptB = builder.buildDefaultSystemPrompt(
                List.of(), "model-b", projectB, "session-b");

        assertTrue(promptA.contains(projectA.toString()));
        assertTrue(promptA.contains("model-a"));
        assertTrue(promptB.contains(projectB.toString()));
        assertTrue(promptB.contains("model-b"));
        assertFalse(promptB.contains(projectA.toString()));
        assertFalse(promptB.contains("model-a"));
    }

    @Test
    void sessionCacheRecomputesWhenPromptInputsChange() throws Exception {
        Path projectA = Files.createDirectory(tempDir.resolve("project-a"));
        Path projectB = Files.createDirectory(tempDir.resolve("project-b"));

        String promptA = String.join("\n\n", builder.buildSystemPrompt(
                List.of(), "model-a", projectA,
                List.of("extra-a"), List.of(), "shared-session"));
        String promptB = String.join("\n\n", builder.buildSystemPrompt(
                List.of(), "model-b", projectB, List.of("extra-b"),
                List.of(), "shared-session"));

        assertTrue(promptA.contains(projectA.toString()));
        assertTrue(promptA.contains("model-a"));
        assertTrue(promptA.contains("extra-a"));
        assertTrue(promptB.contains(projectB.toString()));
        assertTrue(promptB.contains("model-b"));
        assertTrue(promptB.contains("extra-b"));
        assertFalse(promptB.contains(projectA.toString()));
        assertFalse(promptB.contains("model-a"));
        assertFalse(promptB.contains("extra-a"));
    }

    @Test
    void memoryIsNotInjectedByDefaultBuilder() {
        // 记忆段改由 EffectiveSystemPromptBuilder → MemoryPromptService 统一注入（每次请求重读），
        // SystemPromptBuilder 不再读取或缓存项目记忆
        String prompt = String.join("\n\n",
                builder.buildSystemPrompt(List.of(), "gpt-4o", tempDir, List.of(), List.of()));

        assertFalse(prompt.contains("project_memory"));
        assertFalse(prompt.contains("Project Guidelines"));
        verifyNoInteractions(projectMemoryService);
    }

    @Test
    void testDynamicSections_SessionGuidance() {
        // Given - 使用实际的工具名称以触发 session guidance 条件分支
        Tool agentTool = createMockTool("Agent");
        Tool askTool = createMockTool("AskUserQuestion");
        Tool skillTool = createMockTool("Skill");
        List<Tool> tools = List.of(agentTool, askTool, skillTool);

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, "gpt-4o");

        // Then - session guidance 始终包含基础条目，工具匹配时包含对应指导
        assertTrue(prompt.contains("# 会话特定指导"));
        assertTrue(prompt.contains("使用 Agent 和专业代理"));
        assertTrue(prompt.contains("AskUserQuestion"));
        assertTrue(prompt.contains("Skill({skill:\"debug\", args:\"...\"})"));
    }

    @Test
    void sessionGuidanceGatesOnRealAgentAndSkillToolNames() {
        // 工具集含真实工具名时，会话指导生效
        String enabled = builder.buildDefaultSystemPrompt(
                List.of(createMockTool("Agent"), createMockTool("Skill")), "gpt-4o");
        assertAll(
                () -> assertTrue(enabled.contains("使用 Agent 和专业代理")),
                () -> assertTrue(enabled.contains("subagent_type=explore")),
                () -> assertTrue(enabled.contains("`/skill debug`")),
                () -> assertTrue(enabled.contains("Skill({skill:\"debug\", args:\"...\"})")),
                () -> assertTrue(enabled.contains("`/commit` 由命令系统处理")),
                () -> assertTrue(enabled.contains("Skill({skill:\"commit\"})")));

        // 工具集不含对应工具时，对应条目缺失（"! <command>" 基础条目仍在）
        String disabled = builder.buildDefaultSystemPrompt(List.of(), "gpt-4o");
        assertTrue(disabled.contains("# 会话特定指导"));
        assertAll(
                () -> assertFalse(disabled.contains("使用 Agent 和专业代理")),
                () -> assertFalse(disabled.contains("subagent_type=explore")),
                () -> assertFalse(disabled.contains("Skill({skill:")));
    }

    @Test
    void assembledSessionGuidanceDoesNotUseLegacyToolNamesOrDeadReferences() {
        // 收窄到会话指导段：断言只约束该段文本
        String sessionGuidance = builder.buildSystemPrompt(
                        List.of(createMockTool("Agent"), createMockTool("Skill")),
                        "gpt-4o", tempDir, List.of(), List.of())
                .stream()
                .filter(section -> section.startsWith("# 会话特定指导"))
                .findFirst()
                .orElseThrow();
        assertAll(
                () -> assertFalse(sessionGuidance.contains("AgentTool")),
                () -> assertFalse(sessionGuidance.contains("SkillTool")),
                () -> assertFalse(sessionGuidance.contains("isForkSubagentEnabled")),
                () -> assertFalse(sessionGuidance.contains("search_codebase")),
                () -> assertFalse(sessionGuidance.contains("search_symbol")),
                () -> assertFalse(sessionGuidance.contains("其用户可调用技能部分")));
    }

    @Test
    void agentExecutionModeGuidanceMatchesRealAgentParameters() {
        // FORK_SUBAGENT 死配置段已删除：默认配置下 prompt 不得含 fork 残留文本
        String prompt = builder.buildDefaultSystemPrompt(
                List.of(createMockTool("Agent")), "gpt-4o");
        assertAll(
                () -> assertTrue(prompt.contains("## Agent 执行模式")),
                () -> assertTrue(prompt.contains("`run_in_background: true`")),
                () -> assertTrue(prompt.contains("`isolation: \"worktree\"`")),
                () -> assertTrue(prompt.contains("不包含父工作区未提交内容")),
                () -> assertTrue(prompt.contains("原授权范围")),
                () -> assertTrue(prompt.contains("失败或停止未确认时保留成果及恢复位置")),
                () -> assertTrue(prompt.contains("不因目录保留而重跑任务")),
                () -> assertFalse(prompt.contains("FORK_SUBAGENT")),
                () -> assertFalse(prompt.contains("子代理的工作区隔离")),
                () -> assertFalse(prompt.contains("isForkSubagentEnabled")),
                () -> assertFalse(prompt.contains("不指定 subagent_type")),
                () -> assertFalse(prompt.contains("**fork**")),
                () -> assertFalse(prompt.contains("完全文件隔离的")));
    }

    @Test
    void boundaryConditionsTemplateMatchesRealSearchAndBashContracts() {
        String prompt = builder.buildDefaultSystemPrompt(List.of(), "gpt-4o");
        assertAll(
                () -> assertTrue(prompt.contains("默认结果限制为 250 条匹配")),
                () -> assertTrue(prompt.contains("结果限制为 200 个匹配文件")),
                () -> assertTrue(prompt.contains("使用文件过滤参数（glob/include/exclude）")),
                () -> assertFalse(prompt.contains("1800 秒")),
                () -> assertFalse(prompt.contains("自动转为后台")),
                () -> assertFalse(prompt.contains("500 个字符")));
    }

    @Test
    void toolExamplesTemplateMatchesRealReadAndGrepSchemas() {
        String prompt = builder.buildDefaultSystemPrompt(List.of(), "gpt-4o");
        assertAll(
                () -> assertTrue(prompt.contains("offset: 49")),
                () -> assertTrue(prompt.contains("limit: 51")),
                () -> assertTrue(prompt.contains("offset 为起始行号（0 基")),
                () -> assertFalse(prompt.contains("start_line")),
                () -> assertFalse(prompt.contains("end_line")),
                () -> assertFalse(prompt.contains("使用上下文行")),
                () -> assertFalse(prompt.contains("-A、-B、-C")));
    }

    @Test
    void testDynamicSections_Scratchpad_WhenEnabled() {
        // Given - 需要 appStateStore 返回有效的 workingDirectory
        when(featureFlags.isEnabled("SCRATCHPAD")).thenReturn(true);
        AppStateStore appStateStore = mock(AppStateStore.class);
        AppState appState = AppState.defaultState()
                .withSession(s -> s.withWorkingDirectory(tempDir.toString()));
        when(appStateStore.getState()).thenReturn(appState);
        // 重新创建 builder，包含 appStateStore
        builder = new SystemPromptBuilder(projectPromptLoader, featureFlags, gitService, null, appStateStore, projectContextService, null, promptSectionCache, promptCacheBreakDetector, projectMemoryService);
        when(gitService.getGitStatus(any(Path.class))).thenReturn("main (clean)");
        when(featureFlags.isEnabled(anyString())).thenReturn(false);
        when(featureFlags.isEnabled("SCRATCHPAD")).thenReturn(true);

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then
        assertTrue(prompt.contains("# 临时目录"));
        assertTrue(prompt.contains(".scratchpad"));
    }

    @Test
    void testDynamicSections_Scratchpad_WhenDisabled() {
        // Given
        when(featureFlags.isEnabled("SCRATCHPAD")).thenReturn(false);

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then
        assertFalse(prompt.contains("# 临时目录"));
    }

    @Test
    void testFrcSection_ForLightModel() {
        // Given - FRC 段需要 CACHED_MICROCOMPACT 特性标记和模型匹配
        when(featureFlags.isEnabled("CACHED_MICROCOMPACT")).thenReturn(true);
        when(featureFlags.getFeatureValue(eq("FRC_SUPPORTED_MODELS"), anyString())).thenReturn("light,standard");
        when(featureFlags.getFeatureValue(eq("FRC_KEEP_RECENT"), anyInt())).thenReturn(3);

        List<Tool> tools = List.of();
        String model = "qwen-plus-light";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then
        assertTrue(prompt.contains("函数结果清除"));
        assertTrue(prompt.contains("个结果始终保留"));
    }

    @Test
    void testFrcSection_NotForOtherModels() {
        // Given
        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then
        assertFalse(prompt.contains("函数结果清除"));
    }

    @Test
    void testCacheBoundary_WhenEnabled() {
        // Given
        when(featureFlags.isEnabled("PROMPT_CACHE_GLOBAL_SCOPE")).thenReturn(true);

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        List<String> sections = builder.buildSystemPrompt(tools, model, tempDir, List.of(), List.of());

        // Then
        assertTrue(sections.contains(SystemPromptBuilder.SYSTEM_PROMPT_DYNAMIC_BOUNDARY));
    }

    @Test
    void testCacheBoundary_WhenDisabled() {
        // Given
        when(featureFlags.isEnabled("PROMPT_CACHE_GLOBAL_SCOPE")).thenReturn(false);

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        List<String> sections = builder.buildSystemPrompt(tools, model, tempDir, List.of(), List.of());

        // Then
        assertFalse(sections.contains(SystemPromptBuilder.SYSTEM_PROMPT_DYNAMIC_BOUNDARY));
    }

    @Test
    void testMcpInstructions_WithMcpClients() {
        // Given - MCP 指令段要求连接状态为 CONNECTED
        McpServerConnection mockClient = mock(McpServerConnection.class);
        when(mockClient.getName()).thenReturn("filesystem");
        when(mockClient.getStatus()).thenReturn(McpConnectionStatus.CONNECTED);
        when(mockClient.getTools()).thenReturn(List.of(
                new McpServerConnection.McpToolDefinition("read_file", "Read a file", Map.of()),
                new McpServerConnection.McpToolDefinition("write_file", "Write a file", Map.of())
        ));

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String prompt = builder.buildSystemPrompt(tools, model, tempDir, List.of(), List.of(mockClient))
                .stream().filter(s -> s.contains("# MCP 服务器指令")).findFirst().orElse("");

        // Then
        assertTrue(prompt.contains("# MCP 服务器指令"));
        assertTrue(prompt.contains("filesystem"));
        assertTrue(prompt.contains("read_file"));
        assertTrue(prompt.contains("write_file"));
    }

    @Test
    void testClearSectionCache() {
        // Given - 先构建一次以填充缓存
        List<Tool> tools = List.of();
        String model = "gpt-4o";
        builder.buildDefaultSystemPrompt(tools, model);

        // When
        builder.clearSectionCache();

        // Then - 验证无异常抛出
        assertDoesNotThrow(() -> builder.clearSectionCache());
    }

    @Test
    void testToolListSorting() {
        // Given - 工具名称出现在条件段落中（enabledTools 集合用于条件判断）
        Tool toolAgent = createMockTool("Agent");
        Tool toolAsk = createMockTool("AskUserQuestion");
        List<Tool> tools = List.of(toolAgent, toolAsk);

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, "gpt-4o");

        // Then - enabledTools 用于 session guidance 条件分支，工具名称出现在指导文本中
        assertTrue(prompt.contains("使用 Agent 和专业代理"), "Agent should appear in session guidance");
        assertTrue(prompt.contains("AskUserQuestion"), "AskUserQuestion should appear in session guidance");
    }

    // ===== Helper Methods =====

    private Tool createMockTool(String name) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        return tool;
    }
}
