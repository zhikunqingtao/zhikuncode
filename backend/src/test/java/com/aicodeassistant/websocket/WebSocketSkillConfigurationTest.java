package com.aicodeassistant.websocket;

import com.aicodeassistant.authorization.OperationAnalyzerRegistry;
import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.command.impl.SkillCommand;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.config.ProjectPromptLoader;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.context.ProjectContextService;
import com.aicodeassistant.context.SystemPromptSectionCache;
import com.aicodeassistant.coordinator.CoordinatorPromptBuilder;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.engine.QueryConfig;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.prompt.SystemPromptBuilder;
import com.aicodeassistant.service.GitService;
import com.aicodeassistant.service.ProjectMemoryService;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.service.PromptCacheBreakDetector;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillExecutor;
import com.aicodeassistant.skill.SkillRegistry;
import com.aicodeassistant.skill.SkillStateService;
import com.aicodeassistant.skill.SkillTool;
import com.aicodeassistant.skill.SkillToolValidator;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real /app/chat handler up to the QueryEngine configuration boundary, without a broker or LLM. */
class WebSocketSkillConfigurationTest {
    private static final String SESSION = "skill-config-session";
    private static final String MODEL = "skill-config-model";
    private static final String HIDDEN_ID = "ws-disabled-name-sentinel";
    private static final String HIDDEN_DESCRIPTION = "WS_DISABLED_DESCRIPTION_SENTINEL";
    private static final String HIDDEN_BODY = "WS_DISABLED_BODY_SENTINEL";

    @TempDir Path workspace;

    @Test
    void reviewScopeReachesTheActualPromptCommandHandlerWithoutChangingSkillAvailability() throws Exception {
        SkillRegistry skills = new SkillRegistry(new SkillStateService(
                new ObjectMapper(), workspace.resolve("skill-states.json").toString()));
        try {
            SkillDefinition review = bundledSkill("review");
            skills.registerBuiltin(review);
            PromptCommandFixture fixture = promptCommandFixture(skills);
            String scope = "commit=abc123  排除 docs/**\n只查 \"src/a b.java\" {{args}} review_scope=literal";

            fixture.controller.handleSlashCommand(new ClientMessage.SlashCommandPayload(
                    "skill", "\"review\" " + scope), () -> "test-user");
            fixture.awaitDeliveredCount(1);
            assertThat(fixture.delivered.getFirst()).isEqualTo(review.content().replace("{{review_scope}}", scope))
                    .containsOnlyOnce(scope);

            skills.setEnabled("review", false);
            fixture.controller.handleSlashCommand(new ClientMessage.SlashCommandPayload(
                    "skill", "review " + scope), () -> "test-user");
            verify(fixture.engine, times(1)).execute(any(), any(), any());
            skills.setEnabled("review", true);
            fixture.controller.handleSlashCommand(new ClientMessage.SlashCommandPayload("skill", "review"), () -> "test-user");
            fixture.awaitDeliveredCount(2);
            assertThat(fixture.delivered.get(1)).isEqualTo(review.content().replace("{{review_scope}}", ""))
                    .doesNotContain("{{review_scope}}", scope);
        } finally {
            skills.stopWatching();
        }
    }

    @ParameterizedTest(name = "{0} supplemental instructions reach QueryEngine unchanged")
    @MethodSource("supplementalInstructions")
    void bundledTestAndVerifyPreserveSupplementalInstructionsInTheFinalUserMessage(
            String skillName, String skillArgs) throws Exception {
        SkillRegistry skills = new SkillRegistry(new SkillStateService(
                new ObjectMapper(), workspace.resolve("skill-states.json").toString()));
        try {
            SkillDefinition skill = bundledSkill(skillName);
            skills.registerBuiltin(skill);
            PromptCommandFixture fixture = promptCommandFixture(skills);

            fixture.controller.handleSlashCommand(new ClientMessage.SlashCommandPayload(
                    "skill", "\"" + skillName + "\" " + skillArgs), () -> "test-user");

            fixture.awaitDeliveredCount(1);
            assertThat(fixture.delivered.getFirst())
                    .startsWith(skill.renderTemplate(skill.parseArgs(skillArgs)))
                    .contains("## 本次用户补充说明")
                    .containsOnlyOnce(skillArgs)
                    .endsWith(skillArgs);
            verify(fixture.engine, times(1)).execute(any(), any(), any());
        } finally {
            skills.stopWatching();
        }
    }

    private static Stream<Arguments> supplementalInstructions() {
        return Stream.of(
                Arguments.of("test", "只运行已有测试，不生成测试、不安装依赖\n"
                        + "范围：\"backend/path with spaces\"  排除 docs/**\n"
                        + "保留 `npm test` | `cat` 和 {{scope}}"),
                Arguments.of("verify", "backend 和 frontend，禁止联网\n"
                        + "只查 \"src/a b.java\"  排除 generated/**\n"
                        + "参考 `rg pattern | head`，保留 {{scope}}"),
                Arguments.of("verify", "scope=backend scope=frontend\n"
                        + "禁止联网，保留 {{scope}} 与 \"frontend/src/a b.ts\""));
    }

    @Test
    void successiveUserMessagesUseCurrentSkillsThroughTheRealPromptAndConfigurationBuilders() throws Exception {
        SkillRegistry skills = new SkillRegistry();
        try {
            skills.register(SkillDefinition.fromMarkdown("available.md", "Available workflow body",
                    SkillDefinition.SkillSource.PROJECT, null));
            skills.register(SkillDefinition.fromMarkdown(HIDDEN_ID + ".md",
                    "---\ndescription: " + HIDDEN_DESCRIPTION + "\n---\n" + HIDDEN_BODY,
                    SkillDefinition.SkillSource.USER, null));
            skills.setEnabled(HIDDEN_ID, false);
            SkillTool skillTool = new SkillTool(new SkillExecutor(skills, mock(SkillToolValidator.class)), skills);
            OperationAnalyzerRegistry analyzers = mock(OperationAnalyzerRegistry.class);
            when(analyzers.isExplicitCoreTool("Skill")).thenReturn(true);
            ToolRegistry tools = new ToolRegistry(List.of(skillTool), analyzers);

            FeatureFlagService flags = mock(FeatureFlagService.class);
            ProjectContextService projectContext = mock(ProjectContextService.class);
            when(projectContext.formatProjectContext(any())).thenReturn("");
            GitService git = mock(GitService.class);
            when(git.getGitStatus(workspace)).thenReturn("main (clean)");
            SystemPromptBuilder defaultPrompt = spy(new SystemPromptBuilder(
                    mock(ProjectPromptLoader.class), flags, git, null, null, projectContext, null,
                    new SystemPromptSectionCache(), mock(PromptCacheBreakDetector.class),
                    mock(ProjectMemoryService.class)));
            EffectiveSystemPromptBuilder effectivePrompt = new EffectiveSystemPromptBuilder(defaultPrompt,
                    flags, mock(CoordinatorPromptBuilder.class), mock(CoordinatorService.class));

            LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
            when(providers.getDefaultModel()).thenReturn(MODEL);
            when(providers.resolveModelAlias(MODEL)).thenReturn(MODEL);
            ModelRegistry models = mock(ModelRegistry.class);
            when(models.getContextWindowForModel(MODEL)).thenReturn(200000);
            SessionManager sessions = mock(SessionManager.class);
            DataSource dataSource = mock(DataSource.class);
            when(sessions.dataSourceIdentity()).thenReturn(dataSource);
            AtomicReference<List<Message>> history = new AtomicReference<>(List.of());
            when(sessions.loadSession(SESSION)).thenAnswer(invocation -> Optional.of(new SessionData(
                    SESSION, MODEL, workspace.toString(), "Test", "active", history.get(), Map.of(),
                    Usage.zero(), 0, null, Instant.EPOCH, Instant.EPOCH)));
            ProjectWorkspaceService projectWorkspaces = mock(ProjectWorkspaceService.class);
            when(projectWorkspaces.requireCurrentBinding(workspace.toString())).thenReturn(workspace);
            WebSocketSessionManager transports = mock(WebSocketSessionManager.class);
            when(transports.getSessionForPrincipal("test-user")).thenReturn(SESSION);
            SessionExecutionGate gate = new SessionExecutionGate();

            List<QueryConfig> capturedConfigs = new CopyOnWriteArrayList<>();
            List<List<Message>> capturedMessages = new CopyOnWriteArrayList<>();
            QueryEngine engine = mock(QueryEngine.class);
            when(engine.execute(any(), any(), any())).thenAnswer(invocation -> {
                QueryLoopState state = invocation.getArgument(1);
                List<Message> messages = List.copyOf(state.getMessages());
                capturedConfigs.add(invocation.getArgument(0));
                capturedMessages.add(messages);
                history.set(messages);
                return new QueryEngine.QueryResult(messages, Usage.zero(), "end_turn", null, 1);
            });
            WebSocketController controller = new WebSocketController(
                    mock(SimpMessagingTemplate.class), transports, engine, tools, providers, effectivePrompt,
                    models, sessions, null, null, null, null, projectContext, projectWorkspaces,
                    mock(PermissionModeManager.class), null, null, new ObjectMapper(), null, null,
                    null, null, null, null, null, new OssPublishProperties(), gate);

            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            List<Boolean> toggles = List.of(true, false, true);
            for (int index = 0; index < toggles.size(); index++) {
                boolean enabled = toggles.get(index);
                skills.setEnabled("available", enabled);
                controller.handleUserMessage(new ClientMessage.UserMessagePayload(
                        "Continue the task", List.of(), List.of()), () -> "test-user");
                int expectedCount = index + 1;
                await().atMost(Duration.ofSeconds(5)).until(() ->
                        capturedConfigs.size() == expectedCount && !gate.isBusy(dataSource, SESSION));

                QueryConfig config = capturedConfigs.get(index);
                assertThat(config.querySource()).isEqualTo("websocket");
                assertThat(config.tools().stream().map(Tool::getName).toList())
                        .isEqualTo(enabled ? List.of("Skill") : List.of());
                assertThat(config.toolDefinitions())
                        .isEqualTo(enabled ? List.of(skillTool.toToolDefinition()) : List.of());
                assertThat(config.systemPrompt()).contains("# 系统", workspace.toString(), MODEL);
                assertThat(mapper.writeValueAsString(Map.of(
                        "system", config.systemPrompt(), "tools", config.toolDefinitions(),
                        "messages", capturedMessages.get(index))))
                        .doesNotContain(HIDDEN_ID, HIDDEN_DESCRIPTION, HIDDEN_BODY);
            }

            // Verify each real prompt build received the current tool set in the same session cache namespace.
            // Generic system guidance may remain identical; Skill-specific guidance lives in its tool schema.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Tool>> promptTools = ArgumentCaptor.forClass(List.class);
            verify(defaultPrompt, times(3)).buildDefaultSystemPrompt(
                    promptTools.capture(), eq(MODEL), eq(workspace), eq(SESSION));
            List<List<String>> promptToolNames = new ArrayList<>();
            for (List<Tool> current : promptTools.getAllValues()) {
                promptToolNames.add(current.stream().map(Tool::getName).toList());
            }
            assertThat(promptToolNames).containsExactly(List.of("Skill"), List.of(), List.of("Skill"));
            assertThat(capturedConfigs.get(2).toolDefinitions()).isEqualTo(capturedConfigs.getFirst().toolDefinitions());
        } finally {
            skills.stopWatching();
        }
    }

    private SkillDefinition bundledSkill(String name) throws Exception {
        try (var resource = java.util.Objects.requireNonNull(
                getClass().getResourceAsStream("/skills/bundled/" + name + ".md"))) {
            return SkillDefinition.fromMarkdown(name + ".md",
                    new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    SkillDefinition.SkillSource.BUNDLED, null);
        }
    }

    private PromptCommandFixture promptCommandFixture(SkillRegistry skills) throws Exception {
        CommandRegistry commands = new CommandRegistry(List.of(new SkillCommand(skills)));
        ToolRegistry tools = mock(ToolRegistry.class);
        when(tools.getEnabledTools()).thenReturn(List.of());
        when(tools.getToolDefinitions()).thenReturn(List.of());
        EffectiveSystemPromptBuilder prompts = mock(EffectiveSystemPromptBuilder.class);
        when(prompts.buildEffectiveSystemPrompt(any(), anyList(), eq(MODEL), eq(workspace)))
                .thenReturn("Test system prompt");
        LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
        when(providers.getDefaultModel()).thenReturn(MODEL);
        when(providers.resolveModelAlias(MODEL)).thenReturn(MODEL);
        ModelRegistry models = mock(ModelRegistry.class);
        when(models.getContextWindowForModel(MODEL)).thenReturn(200000);
        SessionManager sessions = mock(SessionManager.class);
        DataSource dataSource = mock(DataSource.class);
        when(sessions.dataSourceIdentity()).thenReturn(dataSource);
        AtomicReference<List<Message>> history = new AtomicReference<>(List.of());
        when(sessions.loadSession(SESSION)).thenAnswer(invocation -> Optional.of(new SessionData(
                SESSION, MODEL, workspace.toString(), "Test", "active", history.get(), Map.of(),
                Usage.zero(), 0, null, Instant.EPOCH, Instant.EPOCH)));
        ProjectWorkspaceService projectWorkspaces = mock(ProjectWorkspaceService.class);
        when(projectWorkspaces.requireCurrentBinding(workspace.toString())).thenReturn(workspace);
        WebSocketSessionManager transports = mock(WebSocketSessionManager.class);
        when(transports.getSessionForPrincipal("test-user")).thenReturn(SESSION);
        SessionExecutionGate gate = new SessionExecutionGate();
        List<String> delivered = new CopyOnWriteArrayList<>();
        QueryEngine engine = mock(QueryEngine.class);
        when(engine.execute(any(), any(), any())).thenAnswer(invocation -> {
            QueryLoopState state = invocation.getArgument(1);
            List<Message> messages = List.copyOf(state.getMessages());
            Message.UserMessage last = (Message.UserMessage) messages.getLast();
            delivered.add(((ContentBlock.TextBlock) last.content().getFirst()).text());
            history.set(messages);
            return new QueryEngine.QueryResult(messages, Usage.zero(), "end_turn", null, 1);
        });
        WebSocketController controller = new WebSocketController(
                mock(SimpMessagingTemplate.class), transports, engine, tools, providers, prompts,
                models, sessions, null, commands, null, null, mock(ProjectContextService.class), projectWorkspaces,
                mock(PermissionModeManager.class), null, null, new ObjectMapper(), null, null,
                null, null, null, null, null, new OssPublishProperties(), gate);
        return new PromptCommandFixture(controller, engine, gate, dataSource, delivered);
    }

    private record PromptCommandFixture(WebSocketController controller, QueryEngine engine,
                                        SessionExecutionGate gate, DataSource dataSource, List<String> delivered) {
        void awaitDeliveredCount(int count) {
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> delivered.size() == count && !gate.isBusy(dataSource, SESSION));
        }
    }
}
