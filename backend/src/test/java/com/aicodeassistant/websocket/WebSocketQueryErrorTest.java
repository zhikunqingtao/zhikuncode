package com.aicodeassistant.websocket;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandType;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.context.ProjectContextService;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.llm.LlmApiException;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.SystemMessageType;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import javax.sql.DataSource;
import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
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
    private final List<Message> persistedMessages = new CopyOnWriteArrayList<>();
    private UnaryOperator<List<Message>> readback = UnaryOperator.identity();

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
        when(sessions.loadSession(SESSION)).thenAnswer(invocation -> Optional.of(new SessionData(
                SESSION, MODEL, workspace.toString(), "Test", "active",
                List.copyOf(readback.apply(new ArrayList<>(persistedMessages))), Map.of(),
                Usage.zero(), 0, null, Instant.EPOCH, Instant.EPOCH)));
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            String role = invocation.getArgument(2);
            Message stored = switch (role) {
                case "user" -> new Message.UserMessage(id, Instant.EPOCH,
                        invocation.getArgument(3), null, null, invocation.getArgument(7));
                case "assistant" -> new Message.AssistantMessage(id, Instant.EPOCH,
                        invocation.getArgument(3), invocation.getArgument(4),
                        new Usage(invocation.getArgument(5), invocation.getArgument(6), 0, 0));
                case "system" -> new Message.SystemMessage(id, Instant.EPOCH,
                        invocation.getArgument(3), null);
                default -> throw new AssertionError("Unexpected persisted role: " + role);
            };
            persistedMessages.add(stored);
            return null;
        }).when(sessions).addMessageWithId(anyString(), eq(SESSION), anyString(), any(),
                nullable(String.class), anyInt(), anyInt(), nullable(Map.class));
        ProjectWorkspaceService projectWorkspaces = mock(ProjectWorkspaceService.class);
        when(projectWorkspaces.requireCurrentBinding(workspace.toString())).thenReturn(workspace);
        WebSocketSessionManager transports = mock(WebSocketSessionManager.class);
        when(transports.getSessionForPrincipal(PRINCIPAL)).thenReturn(SESSION);
        when(transports.getPrincipalsForSession(eq(SESSION), anyBoolean())).thenReturn(Set.of(PRINCIPAL));
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        doAnswer(invocation -> {
            delivered.add(new LinkedHashMap<>((Map<String, Object>) invocation.getArgument(2)));
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

    @Test
    void messageHandlerReportsUnknownOutcomeWithoutRawCause() {
        var error = new IllegalStateException("PRIVATE_WRAPPER", new LlmApiException(
                "PRIVATE_MESSAGE", new javax.net.ssl.SSLException("RAW_TLS_DETAIL"), true,
                200, LlmApiException.RESPONSE_INTERRUPTED, 0));

        Map<String, Object> payload = deliverError(Exit.MESSAGE_HANDLER, error);

        assertThat(payload).containsEntry("code", "query_error")
                .containsEntry("errorCode", "MODEL_RESPONSE_INTERRUPTED")
                .containsEntry("httpStatus", 200)
                .containsEntry("upstreamOutcome", "unknown")
                .containsEntry("usageStatus", "unknown")
                .containsEntry("retryable", false)
                .containsEntry("message", "模型响应中断，上游结果未知。为避免重复请求，已停止自动重试。");
        assertThat(payload.toString()).doesNotContain("PRIVATE_", "RAW_TLS_DETAIL");
    }

    @Test
    @SuppressWarnings("unchecked")
    void completionContainsTheFullPersistedTailAfterWorkingMessagesChange() {
        Map<String, Object> completion = deliverCompactedCompletion("unchanged");

        List<Map<String, Object>> committed = (List<Map<String, Object>>) completion.get("committedMessages");
        assertThat(committed).isNotEmpty().hasSize(4);
        assertThat(committed).extracting(message -> message.get("uuid")).containsExactly(
                persistedMessages.get(2).uuid(), "appended-assistant", "appended-result", "appended-final");
        assertThat(committed).extracting(message -> message.get("type"))
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(committed.toString()).contains("Durable result", "Durable final answer")
                .doesNotContain("COMPACT_OMISSION", "transient-omission", "history-user", "history-assistant");
        assertThat(persistedMessages).extracting(Message::uuid).doesNotContain("transient-omission");
    }

    @ParameterizedTest(name = "{0} database readback preserves the empty-tail recovery fallback")
    @ValueSource(strings = {"missing", "reordered"})
    void unconfirmedPersistedTailStillFallsBackToEmptyCommittedMessages(String corruption) {
        Map<String, Object> completion = deliverCompactedCompletion(corruption);

        assertThat(completion).containsEntry("committedMessages", List.of());
        // The mock really accepted all appends; only the completion readback is corrupted.
        assertThat(persistedMessages).hasSize(6);
    }

    private Map<String, Object> deliverCompactedCompletion(String corruption) {
        persistedMessages.add(new Message.UserMessage("history-user", Instant.EPOCH,
                List.of(new ContentBlock.TextBlock("Earlier request")), null, null));
        persistedMessages.add(new Message.AssistantMessage("history-assistant", Instant.EPOCH,
                List.of(new ContentBlock.TextBlock("Earlier answer")), "end_turn", Usage.zero()));
        when(engine.execute(any(), any(), any())).thenAnswer(invocation -> {
            QueryLoopState state = invocation.getArgument(1);
            Message.AssistantMessage assistant = new Message.AssistantMessage("appended-assistant", Instant.EPOCH,
                    List.of(new ContentBlock.ToolUseBlock("query-call", "Grep",
                            new ObjectMapper().createObjectNode().put("pattern", "A"))), "tool_use", Usage.zero());
            Message.UserMessage toolResult = new Message.UserMessage("appended-result", Instant.EPOCH,
                    List.of(new ContentBlock.ToolResultBlock("query-call", "Durable result", false)), null, null);
            Message.AssistantMessage answer = new Message.AssistantMessage("appended-final", Instant.EPOCH,
                    List.of(new ContentBlock.TextBlock("Durable final answer")), "end_turn", Usage.zero());
            state.addMessage(assistant);
            state.addMessage(toolResult);
            state.addMessage(answer);

            // Compaction changes the API working set, never the successful append history.
            state.setMessages(List.of(new Message.SystemMessage("transient-omission", Instant.EPOCH,
                    "COMPACT_OMISSION: temporary working-set marker", SystemMessageType.COMPACT_OMISSION), answer));
            readback = messages -> {
                if ("missing".equals(corruption)) messages.remove(4);
                if ("reordered".equals(corruption)) Collections.swap(messages, 3, 4);
                return messages;
            };
            return new QueryEngine.QueryResult(state.getMessages(), Usage.zero(), "end_turn", null, 6);
        });

        controller.handleUserMessage(new ClientMessage.UserMessagePayload("test", List.of(), List.of()),
                () -> PRINCIPAL);
        await().atMost(Duration.ofSeconds(5)).until(() -> !gate.isBusy(dataSource, SESSION)
                && delivered.stream().anyMatch(message -> "message_complete".equals(message.get("type"))));
        List<Map<String, Object>> completions = delivered.stream()
                .filter(message -> "message_complete".equals(message.get("type"))).toList();
        assertThat(completions).hasSize(1);
        Map<String, Object> completion = completions.getFirst();
        assertThat(completion).containsEntry("stopReason", "end_turn")
                .containsEntry("replaceAfterMessageId", "history-assistant");
        assertThat(queryErrors()).isEmpty();
        verify(engine).execute(any(), any(), any());
        return completion;
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
