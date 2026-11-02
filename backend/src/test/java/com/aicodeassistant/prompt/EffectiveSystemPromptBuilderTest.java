package com.aicodeassistant.prompt;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorPromptBuilder;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.agent.AgentStrategyFactory;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    private CoordinatorPromptBuilder coordinatorPromptBuilder;
    private CoordinatorService coordinatorService;
    private EffectiveSystemPromptBuilder effectiveBuilder;

    @BeforeEach
    void setUp() {
        systemPromptBuilder = mock(SystemPromptBuilder.class);
        featureFlags = mock(FeatureFlagService.class);
        coordinatorPromptBuilder = mock(CoordinatorPromptBuilder.class);
        coordinatorService = mock(CoordinatorService.class);
        effectiveBuilder = new EffectiveSystemPromptBuilder(
                systemPromptBuilder, featureFlags,
                coordinatorPromptBuilder, coordinatorService);

        // 默认 mock 行为
        when(systemPromptBuilder.buildDefaultSystemPrompt(
                anyList(), anyString(), nullable(Path.class), nullable(String.class)))
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
    void testPriority1_AutomaticCoordinatorPromptReceivesSessionWorkingDirectory() {
        when(coordinatorService.isCoordinatorTopLevel(null)).thenReturn(true);
        Path workingDir = Path.of("/Users/zhikun/Desktop/郭庆涛/测试 zk");
        when(coordinatorPromptBuilder.buildCoordinatorPromptForProject(
                "session-project", workingDir))
                .thenReturn("COORDINATOR_PROJECT_PROMPT: " + workingDir);

        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withSessionId("session-project");

        String result = effectiveBuilder.buildEffectiveSystemPrompt(
                config, List.of(), "gpt-4o", workingDir);

        assertEquals("COORDINATOR_PROJECT_PROMPT: " + workingDir, result);
        verify(coordinatorPromptBuilder).buildCoordinatorPromptForProject(
                "session-project", workingDir);
        verifyNoInteractions(systemPromptBuilder);
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
                .withSessionId("session-agent")
                .withCustom("CUSTOM_PROMPT");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then - proactive 时 Agent 提示追加到默认提示
        assertTrue(result.contains("DEFAULT_SYSTEM_PROMPT"));
        assertTrue(result.contains("AGENT_SPECIFIC_PROMPT"));
        verify(systemPromptBuilder).buildDefaultSystemPrompt(
                tools, model, Path.of("."), "session-agent");
    }

    @ParameterizedTest(name = "general-purpose direct role, proactive={0}")
    @ValueSource(booleans = {false, true})
    void testPriority2_BuiltInGeneralPurposeKeepsDecisionBoundaryWithoutDelegationProtocol(boolean proactive) {
        AgentStrategyFactory factory = new AgentStrategyFactory();
        var builtIn = factory.getAgent("general-purpose").orElseThrow();
        var agentDef = factory.toAgentDefinition(builtIn);
        assertEquals(builtIn.systemPromptTemplate(), agentDef.systemPromptTemplate());
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withAgent(agentDef, proactive)
                .withSessionId("session-built-in-agent");

        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, List.of(), "gpt-4o", Path.of("."));

        assertEquals((proactive ? "DEFAULT_SYSTEM_PROMPT\n\n" : "") + builtIn.systemPromptTemplate(), result);
        assertAll(
                () -> assertTrue(result.contains("先调查可查明的歧义")),
                () -> assertTrue(result.contains("原任务范围及现有授权内的低风险、可逆实现细节")),
                () -> assertTrue(result.contains("影响目标、验收、业务含义或授权的歧义")),
                () -> assertTrue(result.contains("应明确说明证据和待决定项，不自行决定")),
                () -> assertTrue(result.contains("明确报告阻碍，不静默绕过")),
                () -> assertFalse(result.contains("父代理")),
                () -> assertFalse(result.contains("SendMessage")),
                () -> assertFalse(result.contains("## 委派任务的范围、证据与回传")),
                () -> assertFalse(result.contains("立即报告")));
        if (proactive) {
            verify(systemPromptBuilder).buildDefaultSystemPrompt(
                    List.of(), "gpt-4o", Path.of("."), "session-built-in-agent");
        } else {
            verifyNoInteractions(systemPromptBuilder);
        }
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
        SystemPromptConfig config = SystemPromptConfig.defaults()
                .withSessionId("session-1");

        List<Tool> tools = List.of();
        String model = "gpt-4o";

        // When
        String result = effectiveBuilder.buildEffectiveSystemPrompt(config, tools, model, Path.of("."));

        // Then
        assertEquals("DEFAULT_SYSTEM_PROMPT", result);
        verify(systemPromptBuilder).buildDefaultSystemPrompt(
                tools, model, Path.of("."), "session-1");
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

    // ═══════════════════════════════════════════════════════════════
    // 记忆段统一注入
    // ═══════════════════════════════════════════════════════════════

    private EffectiveSystemPromptBuilder withMemory(MemoryPromptService memory) {
        return new EffectiveSystemPromptBuilder(systemPromptBuilder, featureFlags,
                coordinatorPromptBuilder, coordinatorService, memory);
    }

    @Test
    void memoryAppendedBetweenBaseAndAppend() {
        MemoryPromptService memory = mock(MemoryPromptService.class);
        when(memory.buildMemoryPrompt(any())).thenReturn("MEMORY_BLOCK");

        String result = withMemory(memory).buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults().withAppend("APPEND_TEXT"),
                List.of(), "gpt-4o", Path.of("."));

        assertEquals("DEFAULT_SYSTEM_PROMPT\n\nMEMORY_BLOCK\n\nAPPEND_TEXT", result);
    }

    @Test
    void memoryAppendedInCoordinatorBranch() {
        when(coordinatorService.isCoordinatorTopLevel(null)).thenReturn(true);
        MemoryPromptService memory = mock(MemoryPromptService.class);
        when(memory.buildMemoryPrompt(any())).thenReturn("MEMORY_BLOCK");

        String result = withMemory(memory).buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults().withCoordinator("COORDINATOR_PROMPT"),
                List.of(), "gpt-4o", Path.of("."));

        assertEquals("COORDINATOR_PROMPT\n\nMEMORY_BLOCK", result);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customSkipsMemoryAndPreservesExplicitAppend(boolean append) {
        MemoryPromptService memory = mock(MemoryPromptService.class);
        SystemPromptConfig config = SystemPromptConfig.defaults().withCustom("CUSTOM_PROMPT");
        if (append) config = config.withAppend("APPEND_TEXT");

        String result = withMemory(memory).buildEffectiveSystemPrompt(
                config, List.of(), "gpt-4o", Path.of("."));

        assertEquals("CUSTOM_PROMPT" + (append ? "\n\nAPPEND_TEXT" : ""), result);
        verifyNoInteractions(memory);
        verifyNoInteractions(systemPromptBuilder);
    }

    @Test
    void coordinatorWinningOverCustomStillInjectsMemory() {
        when(coordinatorService.isCoordinatorTopLevel(null)).thenReturn(true);
        MemoryPromptService memory = mock(MemoryPromptService.class);
        Path project = Path.of("project");
        when(memory.buildMemoryPrompt(project)).thenReturn("MEMORY_BLOCK");

        String result = withMemory(memory).buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults().withCoordinator("COORDINATOR_PROMPT")
                        .withCustom("CUSTOM_PROMPT").withAppend("APPEND_TEXT"),
                List.of(), "gpt-4o", project);

        assertEquals("COORDINATOR_PROMPT\n\nMEMORY_BLOCK\n\nAPPEND_TEXT", result);
        verify(memory).buildMemoryPrompt(project);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void agentWinningOverCustomStillInjectsMemory(boolean proactive) {
        MemoryPromptService memory = mock(MemoryPromptService.class);
        Path project = Path.of("project");
        when(memory.buildMemoryPrompt(project)).thenReturn("MEMORY_BLOCK");
        var agent = new SubAgentExecutor.AgentDefinition(
                "test-agent", 10, null, Set.of(), Set.of(), false, "AGENT_PROMPT");

        String result = withMemory(memory).buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults().withAgent(agent, proactive)
                        .withCustom("CUSTOM_PROMPT").withAppend("APPEND_TEXT"),
                List.of(), "gpt-4o", project);

        assertEquals((proactive ? "DEFAULT_SYSTEM_PROMPT\n\n" : "")
                + "AGENT_PROMPT\n\nMEMORY_BLOCK\n\nAPPEND_TEXT", result);
        verify(memory).buildMemoryPrompt(project);
    }

    @Test
    void overrideSkipsMemory() {
        MemoryPromptService memory = mock(MemoryPromptService.class);

        String result = withMemory(memory).buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults().withOverride("OVERRIDE_PROMPT")
                        .withCoordinator("COORDINATOR_PROMPT").withCustom("CUSTOM_PROMPT")
                        .withAppend("APPEND_TEXT"),
                List.of(), "gpt-4o", Path.of("."));

        assertEquals("OVERRIDE_PROMPT", result);
        verifyNoInteractions(memory);
        verifyNoInteractions(systemPromptBuilder, coordinatorPromptBuilder, coordinatorService);
    }

    @Test
    void emptyMemoryLeavesPromptUnchanged() {
        MemoryPromptService memory = mock(MemoryPromptService.class);
        when(memory.buildMemoryPrompt(any())).thenReturn("");

        String result = withMemory(memory).buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults(), List.of(), "gpt-4o", Path.of("."));

        assertEquals("DEFAULT_SYSTEM_PROMPT", result);
    }

    @Test
    void springUsesMemoryAwareConstructor() throws Exception {
        var ctor = EffectiveSystemPromptBuilder.class.getConstructor(
                SystemPromptBuilder.class, FeatureFlagService.class,
                CoordinatorPromptBuilder.class, CoordinatorService.class, MemoryPromptService.class);
        assertTrue(ctor.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class));
    }
}
