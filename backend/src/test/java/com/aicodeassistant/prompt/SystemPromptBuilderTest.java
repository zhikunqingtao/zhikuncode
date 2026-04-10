package com.aicodeassistant.prompt;

import com.aicodeassistant.config.ClaudeMdLoader;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.mcp.McpServerConnection;
import com.aicodeassistant.service.GitService;
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

    private ClaudeMdLoader claudeMdLoader;
    private FeatureFlagService featureFlags;
    private GitService gitService;
    private SystemPromptBuilder builder;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        claudeMdLoader = mock(ClaudeMdLoader.class);
        featureFlags = mock(FeatureFlagService.class);
        gitService = mock(GitService.class);
        builder = new SystemPromptBuilder(claudeMdLoader, featureFlags, gitService, null, null);

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
        assertTrue(prompt.contains("Claude"));
        assertTrue(prompt.contains("SYSTEM RULES"));
        assertTrue(prompt.contains("TASK EXECUTION"));
        assertTrue(prompt.contains("ENVIRONMENT:"));
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
        assertTrue(prompt.contains("Claude, an AI assistant"), "Intro section missing");
        assertTrue(prompt.contains("SYSTEM RULES:"), "System section missing");
        assertTrue(prompt.contains("TASK EXECUTION:"), "DoingTasks section missing");
        assertTrue(prompt.contains("RISK CLASSIFICATION:"), "Actions section missing");
        assertTrue(prompt.contains("TOOL USAGE:"), "UsingTools section missing");
        assertTrue(prompt.contains("COMMUNICATION STYLE:"), "ToneStyle section missing");
        assertTrue(prompt.contains("OUTPUT EFFICIENCY:"), "OutputEfficiency section missing");
    }

    @Test
    void testDynamicSections_EnvInfo() {
        // Given
        when(gitService.getGitStatus(any(Path.class))).thenReturn("feature-branch (+2~3)");

        List<Tool> tools = List.of();
        String model = "claude-sonnet-4";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then
        assertTrue(prompt.contains("ENVIRONMENT:"));
        assertTrue(prompt.contains("feature-branch (+2~3)"));
        assertTrue(prompt.contains("claude-sonnet-4"));
        assertTrue(prompt.contains("OS:"));
        assertTrue(prompt.contains("Shell:"));
    }

    @Test
    void testDynamicSections_Memory() {
        // Given
        String memoryContent = "# Project Guidelines\n- Use Java 21\n- Follow clean code principles";
        when(claudeMdLoader.loadMergedContent(any())).thenReturn(memoryContent);

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When - 使用 tempDir 而不是 null，确保 memory 段被加载
        String prompt = String.join("\n\n",
                builder.buildSystemPrompt(tools, model, tempDir, List.of(), List.of()));

        // Then
        assertTrue(prompt.contains("USER MEMORIES AND PREFERENCES"));
        assertTrue(prompt.contains("Project Guidelines"));
        assertTrue(prompt.contains("Java 21"));
    }

    @Test
    void testDynamicSections_SessionGuidance() {
        // Given
        Tool agentTool = createMockTool("Agent");
        Tool todoTool = createMockTool("TodoWrite");
        Tool webSearchTool = createMockTool("WebSearch");
        List<Tool> tools = List.of(agentTool, todoTool, webSearchTool);

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, "gpt-4o");

        // Then
        assertTrue(prompt.contains("SESSION GUIDANCE:"));
        assertTrue(prompt.contains("Multi-agent"));
        assertTrue(prompt.contains("Task tracking"));
        assertTrue(prompt.contains("Web search"));
    }

    @Test
    void testDynamicSections_Scratchpad_WhenEnabled() {
        // Given
        when(featureFlags.isEnabled("SCRATCHPAD")).thenReturn(true);

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then
        assertTrue(prompt.contains("SCRATCHPAD:"));
        assertTrue(prompt.contains("scratchpad.md"));
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
        assertFalse(prompt.contains("SCRATCHPAD:"));
    }

    @Test
    void testFrcSection_ForHaikuModel() {
        // Given
        List<Tool> tools = List.of();
        String model = "claude-3-5-haiku";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then
        assertTrue(prompt.contains("function results"));
        assertTrue(prompt.contains("relevant information"));
    }

    @Test
    void testFrcSection_NotForOtherModels() {
        // Given
        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, model);

        // Then
        assertFalse(prompt.contains("function results"));
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
        // Given
        McpServerConnection mockClient = mock(McpServerConnection.class);
        when(mockClient.getName()).thenReturn("filesystem");
        when(mockClient.getTools()).thenReturn(List.of(
                new McpServerConnection.McpToolDefinition("read_file", "Read a file", Map.of()),
                new McpServerConnection.McpToolDefinition("write_file", "Write a file", Map.of())
        ));

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String prompt = builder.buildSystemPrompt(tools, model, tempDir, List.of(), List.of(mockClient))
                .stream().filter(s -> s.contains("MCP SERVERS")).findFirst().orElse("");

        // Then
        assertTrue(prompt.contains("MCP SERVERS:"));
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
        // Given - 工具按名称字母序排列
        Tool toolZ = createMockTool("ZooTool");
        Tool toolA = createMockTool("AlphaTool");
        Tool toolM = createMockTool("MiddleTool");
        List<Tool> tools = List.of(toolZ, toolA, toolM);

        // When
        String prompt = builder.buildDefaultSystemPrompt(tools, "gpt-4o");

        // Then - 验证工具列表存在（实际排序在输出中验证）
        assertTrue(prompt.contains("AlphaTool"));
        assertTrue(prompt.contains("MiddleTool"));
        assertTrue(prompt.contains("ZooTool"));
    }

    // ===== Helper Methods =====

    private Tool createMockTool(String name) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        return tool;
    }
}
