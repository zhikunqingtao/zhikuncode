package com.aicodeassistant.mcp;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.engine.MessageNormalizer;
import com.aicodeassistant.llm.ApiKeyRotationManager;
import com.aicodeassistant.llm.LlmHttpProperties;
import com.aicodeassistant.llm.MessageParamConverter;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.mcp.progress.McpProgressTracker;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real adapter and request projection; only the remote MCP connection is a substitute. */
class McpToolAdapterResultTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ERROR_PREFIX =
            "MCP tool reported an error (MCP_TOOL_REPORTED_ERROR); effects are unknown:\n";
    private static final String TRUNCATION =
            "\n[Truncated: exceeded " + McpToolAdapter.MAX_MCP_RESULT_SIZE + " chars]";
    private static final ToolInput INPUT = ToolInput.from(Map.of("path", "synthetic.txt"));

    @ParameterizedTest
    @ValueSource(strings = {"false", "missing", "\"true\"", "1", "null"})
    void onlyBooleanTrueChangesSuccessfulResults(String flagJson) throws Exception {
        Fixture f = fixture("server", uniqueTool("read_file"));
        JsonNode flag = "missing".equals(flagJson) ? null : JSON.readTree(flagJson);
        String body = "  original UTF-8 诊断\nsecond line\n";
        respond(f, response(flag, body));

        for (int i = 0; i < 2; i++) {
            ToolResult result = f.adapter().call(INPUT, f.context());
            assertFalse(result.isError());
            assertEquals(ToolResult.ExecutionStatus.SUCCEEDED, result.executionStatus());
            assertEquals(body, result.content());
            assertEquals(Map.of("mcpServer", f.server(), "mcpTool", f.tool()), result.metadata());
            assertNull(result.failureCode());
            assertNull(result.failureType());
        }
        verifyRpcCount(f, 2); // Connected success always calls the remote, including repeated arguments.
        verifyProgressCleared(f, 2);
    }

    @Test
    void remoteToolErrorIsExplicitConservativeAndNeverCachedOrRetried() {
        Fixture f = fixture("server", uniqueTool("read_file"));
        respond(f, response(JSON.getNodeFactory().booleanNode(true), "REMOTE_DIAGNOSTIC"));

        ToolResult result = f.adapter().call(INPUT, f.context());
        assertRemoteFailure(result);
        assertEquals(ERROR_PREFIX + "REMOTE_DIAGNOSTIC", result.content());
        assertEquals(Map.of("mcpServer", f.server(), "mcpTool", f.tool()), result.metadata());

        when(f.connection().getStatus()).thenReturn(McpConnectionStatus.FAILED);
        assertConnectionFailure(f.adapter().call(INPUT, f.context()));
        verifyRpcCount(f, 1);
        verifyProgressCleared(f, 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1_048_576})
    void errorPrefixSurvivesEmptyAndTruncatedDiagnostics(int bodyLength) {
        Fixture f = fixture("server", uniqueTool("read_file"));
        String body = "x".repeat(bodyLength);
        respond(f, response(JSON.getNodeFactory().booleanNode(true), body));

        ToolResult result = f.adapter().call(INPUT, f.context());
        assertRemoteFailure(result);
        if (bodyLength == 0) {
            assertEquals(ERROR_PREFIX, result.content());
        } else {
            assertTrue(result.content().startsWith(ERROR_PREFIX));
            assertEquals(ERROR_PREFIX + "x".repeat(McpToolAdapter.MAX_MCP_RESULT_SIZE - ERROR_PREFIX.length())
                    + TRUNCATION, result.content());
        }
        verifyRpcCount(f, 1);
        verifyProgressCleared(f, 1);
    }

    @Test
    void successfulOutputRetainsExistingTruncationExactly() {
        Fixture f = fixture("server", uniqueTool("read_file"));
        respond(f, response(null, "s".repeat(McpToolAdapter.MAX_MCP_RESULT_SIZE + 20)));

        ToolResult result = f.adapter().call(INPUT, f.context());
        assertFalse(result.isError());
        assertEquals("s".repeat(McpToolAdapter.MAX_MCP_RESULT_SIZE) + TRUNCATION, result.content());
        assertEquals(Map.of("mcpServer", f.server(), "mcpTool", f.tool()), result.metadata());
        verifyRpcCount(f, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "protocol", "transport"})
    void exceptionsAfterSuccessKeepTheirOriginalErrorCodesInsteadOfReplayingSuccess(String kind) {
        Fixture f = fixture("server", uniqueTool("read_file"));
        RuntimeException failure = switch (kind) {
            case "timeout" -> new McpProtocolException(new JsonRpcError(JsonRpcError.REQUEST_TIMEOUT, "deadline"));
            case "protocol" -> new McpProtocolException(JsonRpcError.invalidParams("bad arguments"));
            default -> new IllegalStateException("synthetic transport failure");
        };
        String expectedCode = switch (kind) {
            case "timeout" -> "MCP_CALL_DEADLINE_EXCEEDED";
            case "protocol" -> "MCP_PROTOCOL_ERROR";
            default -> "MCP_TRANSPORT_ERROR";
        };
        when(f.connection().callTool(eq(f.tool()), anyMap(), anyLong(), anyString()))
                .thenReturn(response(null, "OLD_SUCCESS_SENTINEL")).thenThrow(failure);

        assertFalse(f.adapter().call(INPUT, f.context()).isError());
        ToolResult result = f.adapter().call(INPUT, f.context());
        assertTrue(result.isError());
        assertEquals(expectedCode, result.failureCode());
        assertEquals(ToolResult.ToolFailureType.NETWORK, result.failureType());
        assertEquals(ToolResult.Retryability.NEVER, result.retryability());
        assertEquals(ToolResult.EffectState.UNKNOWN, result.effectState());
        assertFalse(result.content().contains("OLD_SUCCESS_SENTINEL"));
        assertFalse(result.metadata().containsKey("cached"));
        verifyRpcCount(f, 2);
        verifyProgressCleared(f, 2);
    }

    @Test
    void disconnectedServerCannotReceiveAnotherServersSameNameAndArgumentsResult() {
        String tool = uniqueTool("read_file");
        Fixture a = fixture("server_a", tool);
        Fixture b = fixture("server_b", tool);
        respond(a, response(null, "SERVER_A_SENTINEL"));
        assertFalse(a.adapter().call(INPUT, a.context()).isError());
        when(b.connection().getStatus()).thenReturn(McpConnectionStatus.FAILED);

        ToolResult result = b.adapter().call(INPUT, b.context());
        assertConnectionFailure(result);
        assertFalse(result.content().contains("SERVER_A_SENTINEL"));
        verifyRpcCount(a, 1);
        verifyRpcCount(b, 0);
        verifyNoInteractions(b.progress());
    }

    @Test
    void disconnectedWriteToolCannotReplayItsPreviousSuccessfulWrite() {
        Fixture f = fixture("server", uniqueTool("write_file"));
        respond(f, response(null, "OLD_WRITE_COMPLETED")); // Synthetic response; no file is written.
        assertFalse(f.adapter().call(INPUT, f.context()).isError());
        when(f.connection().getStatus()).thenReturn(McpConnectionStatus.FAILED);

        ToolResult result = f.adapter().call(INPUT, f.context());
        assertConnectionFailure(result);
        assertFalse(result.content().contains("OLD_WRITE_COMPLETED"));
        verifyRpcCount(f, 1);
        verifyProgressCleared(f, 1);
    }

    @Test
    void uncachedDisconnectedCallStillReportsTheOriginalConnectionFailure() {
        Fixture f = fixture("server", uniqueTool("read_file"));
        when(f.connection().getStatus()).thenReturn(McpConnectionStatus.FAILED);
        assertConnectionFailure(f.adapter().call(INPUT, f.context()));
        verifyRpcCount(f, 0);
        verifyNoInteractions(f.progress());
    }

    @Test
    void cancellationCallbackStillUsesTheRegisteredProgressToken() {
        Fixture f = fixture("server", uniqueTool("read_file"));
        when(f.connection().callTool(eq(f.tool()), anyMap(), anyLong(), anyString())).thenAnswer(call -> {
            String token = call.getArgument(3);
            f.abort().abort(AbortReason.USER_INTERRUPT);
            verify(f.connection()).sendCancelNotification(token, "user_cancelled");
            return response(JSON.getNodeFactory().booleanNode(true), "cancelled by remote");
        });

        assertRemoteFailure(f.adapter().call(INPUT, f.context()));
        verifyRpcCount(f, 1);
        verifyProgressCleared(f, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualAdapterBodyReachesChatAndResponsesRequests(boolean remoteError) throws Exception {
        Fixture f = fixture("server", uniqueTool("read_file"));
        respond(f, response(JSON.getNodeFactory().booleanNode(remoteError), "PROJECTION_DIAGNOSTIC"));
        ToolResult result = f.adapter().call(INPUT, f.context());
        assertEquals(remoteError, result.isError());
        assertEquals((remoteError ? ERROR_PREFIX : "") + "PROJECTION_DIAGNOSTIC", result.content());

        String callId = "call-" + UUID.randomUUID();
        Message assistant = new Message.AssistantMessage("assistant", Instant.EPOCH,
                List.of(new ContentBlock.ToolUseBlock(callId, f.adapter().getName(), JSON.createObjectNode())),
                "tool_use", null);
        Message user = new Message.UserMessage("tool-result", Instant.EPOCH,
                List.of(new ContentBlock.ToolResultBlock(callId, result.content(), result.isError())),
                null, "assistant");
        List<Map<String, Object>> messages = MessageParamConverter.toMaps(
                new MessageNormalizer().normalizeTyped(List.of(assistant, user)));
        var http = new LlmHttpProperties(new LlmHttpProperties.PoolProperties(1, 1), 1, 1, false);
        var provider = new OpenAiCompatibleProvider("isolated-projection", JSON, http,
                new ApiKeyRotationManager("fake-test-key"), "fake-test-key",
                "http://127.0.0.1:1/v1", "probe-model", List.of("probe-model"));

        // Only serialize requests. No streamChat, chatSync, summarize or network method is called.
        Method chat = OpenAiCompatibleProvider.class.getDeclaredMethod("buildBaseRequest",
                String.class, List.class, String.class, List.class, int.class);
        chat.setAccessible(true);
        JsonNode chatRequest = (JsonNode) chat.invoke(provider, "probe-model", messages, "", List.of(), 100);
        JsonNode chatOutput = chatRequest.path("messages").get(1);
        assertEquals("tool", chatOutput.path("role").asText());
        assertEquals(callId, chatOutput.path("tool_call_id").asText());
        assertEquals(result.content(), chatOutput.path("content").asText());

        Method responses = OpenAiCompatibleProvider.class.getDeclaredMethod("buildResponsesRequest",
                String.class, List.class, String.class, List.class, int.class, ThinkingConfig.class);
        responses.setAccessible(true);
        JsonNode responsesRequest = (JsonNode) responses.invoke(provider, "probe-model", messages, "", List.of(),
                100, new ThinkingConfig.Disabled());
        JsonNode responsesOutput = responsesRequest.path("input").get(1);
        assertEquals("function_call_output", responsesOutput.path("type").asText());
        assertEquals(callId, responsesOutput.path("call_id").asText());
        assertEquals(result.content(), responsesOutput.path("output").asText());
        verifyRpcCount(f, 1);
    }

    private static void assertRemoteFailure(ToolResult result) {
        assertTrue(result.isError());
        assertEquals(ToolResult.ExecutionStatus.FAILED, result.executionStatus());
        assertEquals(ToolResult.ToolFailureType.PROVIDER, result.failureType());
        assertEquals("MCP_TOOL_REPORTED_ERROR", result.failureCode());
        assertEquals(ToolResult.Retryability.NEVER, result.retryability());
        assertEquals(ToolResult.EffectState.UNKNOWN, result.effectState());
        assertNull(result.exitCode());
    }

    private static void assertConnectionFailure(ToolResult result) {
        assertTrue(result.isError());
        assertEquals("MCP_CONNECTION_UNAVAILABLE", result.failureCode());
        assertEquals(ToolResult.ToolFailureType.NETWORK, result.failureType());
        assertEquals(ToolResult.Retryability.NEVER, result.retryability());
        assertEquals(ToolResult.EffectState.UNKNOWN, result.effectState());
        assertTrue(result.content().contains("not connected"));
        assertFalse(result.content().contains("[cached]"));
        assertFalse(result.metadata().containsKey("cached"));
    }

    private static String uniqueTool(String prefix) { return prefix + "_" + UUID.randomUUID(); }

    private static ObjectNode response(JsonNode error, String body) {
        ObjectNode response = JSON.createObjectNode();
        if (error != null) response.set("isError", error);
        response.putArray("content").addObject().put("type", "text").put("text", body);
        return response;
    }

    private static Fixture fixture(String server, String tool) {
        McpServerConnection connection = mock(McpServerConnection.class);
        when(connection.getName()).thenReturn(server);
        when(connection.getStatus()).thenReturn(McpConnectionStatus.CONNECTED);
        McpProgressTracker progress = mock(McpProgressTracker.class);
        AbortContext abort = new AbortContext();
        ToolUseContext context = ToolUseContext.of(System.getProperty("java.io.tmpdir"), "session-" + UUID.randomUUID());
        McpToolAdapter adapter = new McpToolAdapter("mcp__" + server + "__" + tool, "Test",
                Map.of("type", "object"), connection, tool, null, 100, null, progress, ignored -> abort);
        return new Fixture(server, tool, connection, adapter, progress, abort, context);
    }

    private static void respond(Fixture f, JsonNode response) {
        when(f.connection().callTool(eq(f.tool()), anyMap(), anyLong(), anyString())).thenReturn(response);
    }

    private static void verifyRpcCount(Fixture f, int expected) {
        verify(f.connection(), times(expected)).callTool(eq(f.tool()), eq(INPUT.getRawData()), eq(100L), anyString());
    }

    private static void verifyProgressCleared(Fixture f, int calls) {
        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(f.progress(), times(calls)).registerProgress(tokens.capture(), eq(f.context().sessionId()),
                eq(f.server()), eq(f.tool()));
        verify(f.progress(), times(calls)).unregisterProgress(anyString());
        for (String token : tokens.getAllValues()) verify(f.progress()).unregisterProgress(token);
    }

    private record Fixture(String server, String tool, McpServerConnection connection, McpToolAdapter adapter,
                           McpProgressTracker progress, AbortContext abort, ToolUseContext context) {}
}
