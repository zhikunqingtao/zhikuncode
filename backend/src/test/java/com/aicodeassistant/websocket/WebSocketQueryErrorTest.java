package com.aicodeassistant.websocket;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandType;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.context.ProjectContextService;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.llm.LlmApiException;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import javax.sql.DataSource;
import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real query-error delivery through the chat/command handlers, without a broker or LLM. */
class WebSocketQueryErrorTest {
    private static final String SESSION = "query-error-session";
    private static final String PRINCIPAL = "query-error-user";
    private static final String MODEL = "query-error-model";

    private enum Exit { CHAT_SETUP, PROMPT_SETUP, MESSAGE_HANDLER }

    @TempDir Path workspace;
    private QueryEngine engine;
    private ToolRegistry tools;
    private WebSocketController controller;
    private SessionExecutionGate gate;
    private DataSource dataSource;
    private final List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        engine = mock(QueryEngine.class);
        tools = mock(ToolRegistry.class);
        when(tools.getEnabledTools()).thenReturn(List.of());
        when(tools.getToolDefinitions()).thenReturn(List.of());
        LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
        when(providers.getDefaultModel()).thenReturn(MODEL);
        when(providers.resolveModelAlias(MODEL)).thenReturn(MODEL);
        ModelRegistry models = mock(ModelRegistry.class);
        when(models.getContextWindowForModel(MODEL)).thenReturn(200000);
        EffectiveSystemPromptBuilder prompts = mock(EffectiveSystemPromptBuilder.class);
        when(prompts.buildEffectiveSystemPrompt(any(), anyList(), eq(MODEL), eq(workspace)))
                .thenReturn("Test system prompt");
        SessionManager sessions = mock(SessionManager.class);
        dataSource = mock(DataSource.class);
        when(sessions.dataSourceIdentity()).thenReturn(dataSource);
        when(sessions.loadSession(SESSION)).thenReturn(Optional.of(new SessionData(
                SESSION, MODEL, workspace.toString(), "Test", "active", List.of(), Map.of(),
                Usage.zero(), 0, null, Instant.EPOCH, Instant.EPOCH)));
        ProjectWorkspaceService projectWorkspaces = mock(ProjectWorkspaceService.class);
        when(projectWorkspaces.requireCurrentBinding(workspace.toString())).thenReturn(workspace);
        WebSocketSessionManager transports = mock(WebSocketSessionManager.class);
        when(transports.getSessionForPrincipal(PRINCIPAL)).thenReturn(SESSION);
        when(transports.getPrincipalsForSession(eq(SESSION), anyBoolean())).thenReturn(Set.of(PRINCIPAL));
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        doAnswer(invocation -> {
            delivered.add(Map.copyOf((Map<String, Object>) invocation.getArgument(2)));
            return null;
        }).when(messaging).convertAndSendToUser(eq(PRINCIPAL), eq("/queue/messages"), any(Object.class));
        Command command = mock(Command.class);
        when(command.getType()).thenReturn(CommandType.PROMPT);
        when(command.execute(eq(""), any())).thenReturn(CommandResult.text("Test prompt"));
        CommandRegistry commands = mock(CommandRegistry.class);
        when(commands.getCommand("probe")).thenReturn(command);
        gate = new SessionExecutionGate();
        controller = new WebSocketController(
                messaging, transports, engine, tools, providers, prompts, models, sessions,
                null, commands, null, null, mock(ProjectContextService.class), projectWorkspaces,
                mock(PermissionModeManager.class), null, null, new ObjectMapper(), null, null,
                null, null, null, null, null, new OssPublishProperties(), gate);
    }

    static Stream<Arguments> unclassifiedErrors() {
        return Stream.of(Exit.values()).flatMap(exit -> Stream.of(
                Arguments.of(exit, new LlmApiException("INVALID_TOOL_INPUT_JSON", false), false),
                Arguments.of(exit, new LlmApiException("TRUNCATED_TOOL_CALLS: max_tokens", false), false),
                Arguments.of(exit, new IllegalStateException("wrapped suppressed failure",
                        new LlmApiException("stream failure", true).withRetryable(false)), false),
                Arguments.of(exit, new LlmApiException("retry allowed", true), true),
                Arguments.of(exit, new IllegalStateException("unknown failure"), true)));
    }

    @ParameterizedTest(name = "{0}: {1} -> retryable={2}")
    @MethodSource("unclassifiedErrors")
    void allThreeUnclassifiedExitsPreserveRetryability(Exit exit, RuntimeException error,
                                                       boolean expectedRetryable) {
        Map<String, Object> payload = deliverError(exit, error);

        assertThat(payload).containsEntry("code", "query_error")
                .containsEntry("message", error.getMessage())
                .containsEntry("retryable", expectedRetryable)
                .doesNotContainKeys("errorCode", "httpStatus");
    }

    static Stream<Arguments> classifiedErrors() {
        // Deliberately oppose the exception's flag: the existing classification remains authoritative.
        return Stream.of(
                Arguments.of(new LlmApiException("payment", true, 402),
                        "PROVIDER_PAYMENT_REQUIRED", 402, false),
                Arguments.of(new LlmApiException("forbidden", true, 403),
                        "PROVIDER_FORBIDDEN", 403, false),
                Arguments.of(new LlmApiException("rate limited", false, 429),
                        "PROVIDER_RATE_LIMITED", 429, true),
                Arguments.of(new LlmApiException("server failure", false, 500),
                        "PROVIDER_ERROR", 500, true),
                Arguments.of(new LlmApiException("bad request", true, 400),
                        "PROVIDER_ERROR", 400, false),
                Arguments.of(new LlmApiException("connection failure",
                                new ConnectException("Connection refused"), false),
                        "PROVIDER_UNREACHABLE", 0, true));
    }

    @ParameterizedTest(name = "{1}: retryable={3}")
    @MethodSource("classifiedErrors")
    void messageHandlerKeepsHttpAndConnectionClassification(RuntimeException error, String code,
                                                            int status, boolean retryable) {
        Map<String, Object> payload = deliverError(Exit.MESSAGE_HANDLER, error);

        assertThat(payload).containsEntry("code", "query_error")
                .containsEntry("errorCode", code)
                .containsEntry("httpStatus", status)
                .containsEntry("retryable", retryable);
    }

    private Map<String, Object> deliverError(Exit exit, RuntimeException error) {
        if (exit == Exit.MESSAGE_HANDLER) {
            when(engine.execute(any(), any(), any())).thenThrow(error);
        } else {
            // Fail before executeQueryInternal's handler exists, reaching each outer query catch.
            when(tools.getEnabledTools()).thenThrow(error);
        }
        if (exit == Exit.PROMPT_SETUP) {
            controller.handleSlashCommand(new ClientMessage.SlashCommandPayload("probe", ""), () -> PRINCIPAL);
        } else {
            controller.handleUserMessage(new ClientMessage.UserMessagePayload("test", List.of(), List.of()),
                    () -> PRINCIPAL);
        }
        await().atMost(Duration.ofSeconds(5)).until(() ->
                !gate.isBusy(dataSource, SESSION) && !queryErrors().isEmpty());
        List<Map<String, Object>> errors = queryErrors();
        assertThat(errors).hasSize(1);
        if (exit == Exit.MESSAGE_HANDLER) verify(engine).execute(any(), any(), any());
        else verify(engine, never()).execute(any(), any(), any());
        return errors.getFirst();
    }

    private List<Map<String, Object>> queryErrors() {
        return delivered.stream()
                .filter(message -> "error".equals(message.get("type"))
                        && "query_error".equals(message.get("code")))
                .toList();
    }
}
