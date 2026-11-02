package com.aicodeassistant.tool.agent;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorPromptBuilder;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.engine.MessageNormalizer;
import com.aicodeassistant.llm.ApiKeyRotationManager;
import com.aicodeassistant.llm.CancellationSignal;
import com.aicodeassistant.llm.LlmCallContext;
import com.aicodeassistant.llm.LlmHttpProperties;
import com.aicodeassistant.llm.LlmStreamEvent;
import com.aicodeassistant.llm.MessageParamConverter;
import com.aicodeassistant.llm.StreamChatCallback;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.notify.NotificationService;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.prompt.SystemPromptBuilder;
import com.aicodeassistant.prompt.SystemPromptConfig;
import com.aicodeassistant.service.ActivityRepository;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.verify.VerifyJourneyTool;
import com.aicodeassistant.verify.DevServerLauncher;
import com.aicodeassistant.verify.EvidenceStore;
import com.aicodeassistant.verify.HttpApiVerifier;
import com.aicodeassistant.verify.PreviewStackDetector;
import com.aicodeassistant.verify.VerifierFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Wire contracts: real prompt builders/tool result, normalizer and provider serializer.
 * Only localhost HTTP fixtures are used. This does not exercise QueryEngine dispatch,
 * compaction, model behavior or the production process; those are separate boundaries.
 */
class QualityGuidanceRequestIntegrationTest {
    private static final String MODEL = "deepseek-v4.1-flash";
    private static final String SESSION = "quality-wire-session";
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void coordinatorAndActualNotRunResultReachTheNextRequestUnchanged() throws Exception {
        SystemPromptBuilder defaultBuilder = mock(SystemPromptBuilder.class);
        EffectiveSystemPromptBuilder builder = coordinatorBuilder(defaultBuilder);
        String prompt = builder.buildEffectiveSystemPrompt(
                SystemPromptConfig.defaults().withSessionId(SESSION), List.of(), MODEL, workspace);

        PythonCapabilityAwareClient python = mock(PythonCapabilityAwareClient.class);
        VerifierFactory factory = mock(VerifierFactory.class);
        HttpApiVerifier verifier = mock(HttpApiVerifier.class);
        when(factory.selectVerifier(any(), eq("http_api"))).thenReturn(verifier);
        when(python.isCapabilityAvailable("HTTP_API")).thenReturn(false);
        EvidenceStore evidence = mock(EvidenceStore.class);
        DevServerLauncher launcher = mock(DevServerLauncher.class);
        VerifyJourneyTool tool = new VerifyJourneyTool(python, launcher, factory,
                mock(PreviewStackDetector.class), evidence, mock(SimpMessagingTemplate.class),
                mock(FeatureFlagService.class), mock(ActivityRepository.class), mapper,
                mock(NotificationService.class));
        Map<String, Object> arguments = Map.of(
                "journey", List.of(Map.of("action", "http_get", "url", "/ping")),
                "verification_mode", "http_api", "base_url", "http://127.0.0.1:8080");
        ToolResult result = tool.call(ToolInput.from(arguments), ToolUseContext.of(workspace.toString(), SESSION));
        assertThat(result.isError()).isFalse();
        assertThat(result.retryability()).isEqualTo(ToolResult.Retryability.NEVER);
        verifyNoInteractions(defaultBuilder, verifier, launcher, evidence);

        // Replay the actual tool result as QueryEngine's next-request message types.
        // Tool choice is a fixture, not a claim that a model chose to verify independently.
        List<Message> history = List.of(userMessage("确认接口可用"),
                new Message.AssistantMessage("assistant-1", Instant.EPOCH,
                        List.of(new ContentBlock.ToolUseBlock("verify-1", tool.getName(), mapper.valueToTree(arguments))),
                        "tool_use", null),
                new Message.UserMessage("result-1", Instant.EPOCH,
                        List.of(new ContentBlock.ToolResultBlock("verify-1", result.content(), result.isError())),
                        null, "assistant-1"));
        JsonNode request = captureRequest("coordinator-after-not-run", prompt, history, List.of(tool));

        String sentPrompt = request.at("/messages/0/content").asText();
        assertThat(sentPrompt).isEqualTo(prompt)
                .contains("按实际结果组织工作", "要实现的结果、委派范围与授权边界、交付物及验收方式",
                        "说明\"完成\"对应的实际结果和独立验收方式", "回传产物位置、实际检查结果和未验证项")
                .doesNotContain("Commit and report the hash");
        assertThat(sentPrompt.indexOf("# 目标、质量与证据"))
                .isEqualTo(sentPrompt.lastIndexOf("# 目标、质量与证据")).isGreaterThanOrEqualTo(0);
        assertThat(request.at("/tools/0")).isEqualTo(mapper.valueToTree(tool.toToolDefinition()));
        assertThat(request.at("/messages/2/tool_calls/0/id").asText()).isEqualTo("verify-1");
        assertThat(request.at("/messages/3/role").asText()).isEqualTo("tool");
        assertThat(request.at("/messages/3/tool_call_id").asText()).isEqualTo("verify-1");
        assertThat(request.at("/messages/3/content").asText()).isEqualTo(result.content())
                .contains("Runtime verification NOT RUN", "No journey steps were executed", "report the verification gap")
                .doesNotContain("This does not block your task", "Proceed without runtime verification");
    }

    @Test
    void normalAndForkVerificationPromptsKeepConclusionAndRuntimeContractsOnTheWire() throws Exception {
        SubAgentExecutor executor = mock(SubAgentExecutor.class, CALLS_REAL_METHODS);
        String task = "只读核验：检查结论的原始依据，报告未验证项；保留数据中的 25%。";
        ToolUseContext context = ToolUseContext.of(workspace.toString(), "quality-worker");
        for (String method : List.of("buildAgentSystemPrompt", "buildForkSystemPrompt")) {
            String prompt = ReflectionTestUtils.invokeMethod(executor, method,
                    task, SubAgentExecutor.AgentDefinition.VERIFICATION, context);
            assertThat(prompt).isNotNull();
            JsonNode request = captureRequest(method, prompt, List.of(userMessage(task)), List.of());
            String sentPrompt = request.at("/messages/0/content").asText();
            assertThat(sentPrompt).isEqualTo(prompt)
                    .contains(task, "结论类任务的核验", "对照原始来源核实关键断言，并主动寻找反例",
                            "只读委派不修改文件", "涉及实际运行效果的关键结论仍须相应动态证据",
                            "VERDICT: PASS", "VERDICT: FAIL", "VERDICT: PARTIAL");
            assertThat(sentPrompt.indexOf("结论类任务的核验"))
                    .isEqualTo(sentPrompt.lastIndexOf("结论类任务的核验"));
        }
    }

    @Test
    void overrideStillReplacesCoordinatorBeforeSerialization() throws Exception {
        SystemPromptBuilder defaultBuilder = mock(SystemPromptBuilder.class);
        EffectiveSystemPromptBuilder builder = coordinatorBuilder(defaultBuilder);
        String override = "Only this user-supplied system prompt: 25%.";
        String prompt = builder.buildEffectiveSystemPrompt(SystemPromptConfig.defaults().withSessionId(SESSION)
                .withOverride(override).withAppend("must not be appended"), List.of(), MODEL, workspace);
        JsonNode request = captureRequest("override", prompt, List.of(userMessage("hello")), List.of());
        assertThat(request.at("/messages/0/content").asText()).isEqualTo(override);
        verifyNoInteractions(defaultBuilder);
    }

    private EffectiveSystemPromptBuilder coordinatorBuilder(SystemPromptBuilder defaultBuilder) {
        CoordinatorService service = mock(CoordinatorService.class);
        McpClientManager mcp = mock(McpClientManager.class);
        when(service.isCoordinatorTopLevel(null)).thenReturn(true);
        when(service.getWorkerToolsContext(SESSION)).thenReturn(Map.of("workerToolsContext", "standard tools"));
        when(service.getScratchpadDir(SESSION)).thenReturn(workspace.resolve("scratchpad"));
        when(mcp.getConnectedServers()).thenReturn(List.of());
        return new EffectiveSystemPromptBuilder(defaultBuilder, mock(FeatureFlagService.class),
                new CoordinatorPromptBuilder(service, mcp), service);
    }

    private JsonNode captureRequest(String label, String prompt, List<Message> history, List<Tool> tools)
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                    data: {"choices":[{"delta":{"content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":1}}

                    data: [DONE]

                    """));
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider("deepseek", mapper,
                    new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, false),
                    new ApiKeyRotationManager("fixture-key"), "fixture-key", server.url("/api/v1").toString(),
                    MODEL, List.of(MODEL));
            Capture callback = new Capture();
            provider.streamChat(MODEL, MessageParamConverter.toMaps(new MessageNormalizer().normalizeTyped(history)),
                    prompt, tools.stream().map(Tool::toToolDefinition).toList(),
                    1024, new ThinkingConfig.Disabled(), new LlmCallContext(SESSION, CancellationSignal.none()), callback);
            assertThat(callback.error).isNull();
            assertThat(callback.completed).isTrue();
            RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(recorded).isNotNull();
            assertThat(recorded.getPath()).isEqualTo("/api/v1/chat/completions");
            assertThat(server.getRequestCount()).isEqualTo(1);
            JsonNode request = mapper.readTree(recorded.getBody().readUtf8());
            assertThat(request.path("model").asText()).isEqualTo(MODEL);
            assertThat(request.at("/messages/0/role").asText()).isEqualTo("system");
            // Opt-in evidence from these synthetic fixtures only; never export HTTP headers or app configuration.
            String captureDirectory = System.getProperty("quality.capture.dir");
            if (captureDirectory != null && !captureDirectory.isBlank()) {
                Path directory = Path.of(captureDirectory);
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(label + ".request.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
                Files.writeString(directory.resolve(label + ".system.txt"), prompt);
            }
            return request;
        }
    }

    private static Message.UserMessage userMessage(String text) {
        return new Message.UserMessage("user-1", Instant.EPOCH,
                List.of(new ContentBlock.TextBlock(text)), null, null);
    }

    private static final class Capture implements StreamChatCallback {
        private Throwable error;
        private boolean completed;

        @Override public void onEvent(LlmStreamEvent event) { }
        @Override public void onComplete() { completed = true; }
        @Override public void onError(Throwable failure) { error = failure; }
    }
}
