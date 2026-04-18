package com.aicodeassistant.prompt;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * EffectiveSystemPromptBuilder 单元测试 — 5级优先级链验证。
 *
 * @see EffectiveSystemPromptBuilder
 */
class EffectiveSystemPromptBuilderTest {

    private SystemPromptBuilder systemPromptBuilder;
    private FeatureFlagService featureFlags;
    private CoordinatorService coordinatorService;
    private EffectiveSystemPromptBuilder effectiveBuilder;

    @BeforeEach
    void setUp() {
        systemPromptBuilder = mock(SystemPromptBuilder.class);
        featureFlags = mock(FeatureFlagService.class);
        coordinatorService = mock(CoordinatorService.class);
        effectiveBuilder = new EffectiveSystemPromptBuilder(
                systemPromptBuilder, featureFlags, null, coordinatorService);

        // 默认 mock 行为
        when(systemPromptBuilder.buildDefaultSystemPrompt(anyList(), anyString()))
                .thenReturn("DEFAULT_SYSTEM_PROMPT");
        when(featureFlags.isEnabled(anyString())).thenReturn(false);
        when(coordinatorService.isCoordinatorMode()).thenReturn(false);
        lenient().when(coordinatorService.isCoordinatorTopLevel(any())).thenReturn(false);
    }

    // ═══════════════════════════════════════════════════════════════
    // 优先级 0: Override 系统提示（最高优先级）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testPriority0_OverrideSystemPrompt() {
        // Given
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withOverride("OVERRIDE_PROMPT")
                .withCoordinator("COORDINATOR_PROMPT")
                .withCustom("CUSTOM_PROMPT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertEquals("OVERRIDE_PROMPT", result);
    }

    @Test
    void testPriority0_OverrideWithAppend() {
        // Given - Override 时不追加 appendSystemPrompt
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withOverride("OVERRIDE_PROMPT")
                .withAppend("APPEND_TEXT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertEquals("OVERRIDE_PROMPT", result);
        assertFalse(result.contains("APPEND_TEXT"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 优先级 1: Coordinator 模式
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testPriority1_CoordinatorMode_WhenEnabled() {
        // Given — coordinatorService.isCoordinatorMode() returns true
        when(coordinatorService.isCoordinatorMode()).thenReturn(true);
        when(coordinatorService.isCoordinatorTopLevel(null)).thenReturn(true);

        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withCoordinator("COORDINATOR_PROMPT")
                .withCustom("CUSTOM_PROMPT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertEquals("COORDINATOR_PROMPT", result);
    }

    @Test
    void testPriority1_CoordinatorMode_WhenDisabled() {
        // Given — coordinatorService.isCoordinatorMode() returns false
        when(coordinatorService.isCoordinatorMode()).thenReturn(false);

        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withCoordinator("COORDINATOR_PROMPT")
                .withCustom("CUSTOM_PROMPT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then - Coordinator 被跳过，使用 Custom
        assertEquals("CUSTOM_PROMPT", result);
    }

    @Test
    void testPriority1_CoordinatorWithAppend() {
        // Given
        when(coordinatorService.isCoordinatorMode()).thenReturn(true);
        when(coordinatorService.isCoordinatorTopLevel(null)).thenReturn(true);

        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withCoordinator("COORDINATOR_PROMPT")
                .withAppend("ADDITIONAL_CONTEXT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertTrue(result.contains("COORDINATOR_PROMPT"));
        assertTrue(result.contains("ADDITIONAL_CONTEXT"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 优先级 2: Agent 模式
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testPriority2_AgentMode_NonProactive() {
        // Given
        SubAgentExecutor.AgentDefinition agentDef = new SubAgentExecutor.AgentDefinition(
                "TestAgent", 30, "gpt-4o", Set.of("*"), null,
                false, "AGENT_SPECIFIC_PROMPT"
        );

        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withAgent(agentDef, false) // 非 proactive
                .withCustom("CUSTOM_PROMPT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then - 非 proactive 时 Agent 提示替换默认提示
        assertEquals("AGENT_SPECIFIC_PROMPT", result);
    }

    @Test
    void testPriority2_AgentMode_Proactive() {
        // Given
        SubAgentExecutor.AgentDefinition agentDef = new SubAgentExecutor.AgentDefinition(
                "TestAgent", 30, "gpt-4o", Set.of("*"), null,
                false, "AGENT_SPECIFIC_PROMPT"
        );

        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withAgent(agentDef, true) // proactive
                .withCustom("CUSTOM_PROMPT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then - proactive 时 Agent 提示追加到默认提示
        assertTrue(result.contains("DEFAULT_SYSTEM_PROMPT"));
        assertTrue(result.contains("AGENT_SPECIFIC_PROMPT"));
    }

    @Test
    void testPriority2_AgentWithAppend() {
        // Given
        SubAgentExecutor.AgentDefinition agentDef = new SubAgentExecutor.AgentDefinition(
                "TestAgent", 30, "gpt-4o", Set.of("*"), null,
                false, "AGENT_PROMPT"
        );

        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withAgent(agentDef, false)
                .withAppend("APPEND_TEXT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertTrue(result.contains("AGENT_PROMPT"));
        assertTrue(result.contains("APPEND_TEXT"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 优先级 3: Custom 系统提示
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testPriority3_CustomSystemPrompt() {
        // Given
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withCustom("MY_CUSTOM_PROMPT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertEquals("MY_CUSTOM_PROMPT", result);
    }

    @Test
    void testPriority3_CustomWithAppend() {
        // Given
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withCustom("CUSTOM_BASE")
                .withAppend("CUSTOM_APPEND");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertTrue(result.contains("CUSTOM_BASE"));
        assertTrue(result.contains("CUSTOM_APPEND"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 优先级 4: Default 系统提示
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testPriority4_DefaultSystemPrompt() {
        // Given
        SystemPromptConfig config = SystemPromptConfig.defaults();

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertEquals("DEFAULT_SYSTEM_PROMPT", result);
        verify(systemPromptBuilder).buildDefaultSystemPrompt(tools, model);
    }

    @Test
    void testPriority4_DefaultWithAppend() {
        // Given
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withAppend("EXTRA_CONTEXT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertTrue(result.contains("DEFAULT_SYSTEM_PROMPT"));
        assertTrue(result.contains("EXTRA_CONTEXT"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 边界情况测试
    // ═══════════════════════════════════════════════════════════════

    @Test
    void testEmptyAppendNotAdded() {
        // Given
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withCustom("BASE_PROMPT")
                .withAppend(""); // 空字符串

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then - 空 append 不应添加额外换行
        assertEquals("BASE_PROMPT", result);
    }

    @Test
    void testNullAppendNotAdded() {
        // Given
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withCustom("BASE_PROMPT");
        // append 为 null

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertEquals("BASE_PROMPT", result);
    }

    @Test
    void testBlankAppendNotAdded() {
        // Given
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withCustom("BASE_PROMPT")
                .withAppend("   "); // 空白字符

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then - 空白 append 不应添加
        assertEquals("BASE_PROMPT", result);
    }

    @Test
    void testSimplifiedMethod() {
        // Given - 使用简化方法（无配置）
        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(tools, model, Path.of("."));

        // Then
        assertEquals("DEFAULT_SYSTEM_PROMPT", result);
    }

    @Test
    void testCoordinatorNullWhenEnabled() {
        // Given - Coordinator 启用但 prompt 为 null，且无 CoordinatorPromptBuilder
        when(coordinatorService.isCoordinatorMode()).thenReturn(true);
        when(coordinatorService.isCoordinatorTopLevel(null)).thenReturn(true);

        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withCoordinator(null)
                .withCustom("CUSTOM_PROMPT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then - 跳过 Coordinator，使用 Custom
        assertEquals("CUSTOM_PROMPT", result);
    }
}
