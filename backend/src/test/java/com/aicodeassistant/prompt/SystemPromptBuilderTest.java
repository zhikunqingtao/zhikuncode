package com.aicodeassistant.prompt;

import com.aicodeassistant.config.ProjectPromptLoader;
import com.aicodeassistant.config.FeatureFlagService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void testDynamicSections_Memory() {
        // Given
        String memoryContent = "# Project Guidelines\n- Use Java 21\n- Follow clean code principles";
        when(projectMemoryService.loadMemory(any())).thenReturn(memoryContent);

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When - 使用 tempDir 而不是 null，确保 memory 段被加载
        String prompt = String.join("\n\n",
                builder.buildSystemPrompt(tools, model, tempDir, List.of(), List.of()));

        // Then
        assertTrue(prompt.contains("project_memory"));
        assertTrue(prompt.contains("Project Guidelines"));
        assertTrue(prompt.contains("Java 21"));
    }

    @Test
    void testDynamicSections_SessionGuidance() {
        // Given - 使用实际的工具名称以触发 session guidance 条件分支
        Tool agentTool = createMockTool("AgentTool");
        Tool askTool = createMockTool("AskUserQuestion");
        Tool skillTool = createMockTool("SkillTool");
        List<Tool> tools = List.of(agentTool, askTool, skillTool);

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, "gpt-4o");

        // Then - session guidance 始终包含基础条目，工具匹配时包含对应指导
        assertTrue(prompt.contains("# 会话特定指导"));
        assertTrue(prompt.contains("AgentTool"));
        assertTrue(prompt.contains("AskUserQuestion"));
        assertTrue(prompt.contains("SkillTool"));
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
        Tool toolAgent = createMockTool("AgentTool");
        Tool toolAsk = createMockTool("AskUserQuestion");
        List<Tool> tools = List.of(toolAgent, toolAsk);

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, "gpt-4o");

        // Then - enabledTools 用于 session guidance 条件分支，工具名称出现在指导文本中
        assertTrue(prompt.contains("AgentTool"), "AgentTool should appear in session guidance");
        assertTrue(prompt.contains("AskUserQuestion"), "AskUserQuestion should appear in session guidance");
    }

    // ===== Helper Methods =====

    private Tool createMockTool(String name) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        return tool;
    }
}
