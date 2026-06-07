package com.aicodeassistant.verify;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.notify.NotificationService;
import com.aicodeassistant.service.ActivityRepository;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.verify.VerifyJourneyTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * VerifyJourneyTool 边缘场景测试 —— 覆盖 RV-1 运行时验证在异常输入、
 * DevServer 启动失败、技术栈检测异常、Python 服务异常、清理阶段异常等场景下的
 * 容错与降级行为。
 *
 * <p>风格沿用 {@link VerifyJourneyToolTest}：
 * <ul>
 *   <li>仅 {@link ObjectMapper} 使用真实实例，其余协作者全部 Mock；</li>
 *   <li>测试方法命名 {@code test场景_预期行为}，并附 {@link DisplayName} 描述测试意图；</li>
 *   <li>断言以 {@link ToolResult#isError()} 与 {@link ToolResult#content()} 为主，避免过度耦合实现。</li>
 * </ul>
 *
 * <p>说明：DevServerLauncher / PreviewStackDetector 自身已存在专项单测；本类聚焦
 * 这些组件抛出异常或返回边界值时，{@link VerifyJourneyTool} 编排层是否做到「失败可见、降级可控、清理收敛」。
 */
class VerifyJourneyEdgeCaseTest {

    private PythonCapabilityAwareClient pythonClient;
    private DevServerLauncher devServerLauncher;
    private VerifierFactory verifierFactory;
    private PreviewStackDetector previewStackDetector;
    private EvidenceStore evidenceStore;
    private SimpMessagingTemplate messagingTemplate;
    private FeatureFlagService featureFlags;
    private ActivityRepository activityRepository;
    private ObjectMapper objectMapper;
    private NotificationService notificationService;

    private VerifyJourneyTool tool;

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() {
        pythonClient = mock(PythonCapabilityAwareClient.class);
        devServerLauncher = mock(DevServerLauncher.class);
        verifierFactory = mock(VerifierFactory.class);
        previewStackDetector = mock(PreviewStackDetector.class);
        evidenceStore = mock(EvidenceStore.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        featureFlags = mock(FeatureFlagService.class);
        activityRepository = mock(ActivityRepository.class);
        objectMapper = new ObjectMapper();
        notificationService = mock(NotificationService.class);

        tool = new VerifyJourneyTool(
                pythonClient,
                devServerLauncher,
                verifierFactory,
                previewStackDetector,
                evidenceStore,
                messagingTemplate,
                featureFlags,
                activityRepository,
                objectMapper,
                notificationService
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // 输入校验类
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-01 空 journey 数组 → 立即返回错误且不触发任何后续编排")
    void testEmptyJourneySteps_returnsError() {
        ToolInput input = ToolInput.from(Map.of("journey", List.of()));
        ToolUseContext ctx = ToolUseContext.of(workspace.toString(), "session-empty");

        ToolResult result = tool.call(input, ctx);

        assertTrue(result.isError(), "空 journey 必须返回错误");
        assertTrue(result.content().contains("non-empty"),
                "错误信息应明确提示 journey 必须非空，实际：" + result.content());
        // 不应进入 verifier 选择 / DevServer 启动 / 证据保存
        verify(verifierFactory, never()).selectVerifier(any(), anyString());
        verify(devServerLauncher, never()).start(any(), anyString(), anyInt(), any());
        verify(evidenceStore, never()).save(any());
    }

    @Test
    @DisplayName("EC-02 缺失 journey 字段 → 视为非法输入，错误返回")
    void testMissingJourneyField_returnsError() {
        // 不携带 journey 字段，仅给 base_url
        ToolInput input = ToolInput.from(Map.of("base_url", "http://localhost:5173"));
        ToolUseContext ctx = ToolUseContext.of(workspace.toString(), "session-missing");

        ToolResult result = tool.call(input, ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("journey"),
                "错误信息应提及 journey 字段，实际：" + result.content());
        verify(verifierFactory, never()).selectVerifier(any(), anyString());
    }

    @Test
    @DisplayName("EC-03 超大 journey（1000 步）→ 不做强制截断，正常向下游 Verifier 透传")
    void testOversizedJourney_isPassedThroughToVerifier() {
        // 构造 1000 个 navigate 步骤（HTTP 模式以避免 DevServer 路径）
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            steps.add(Map.of("action", "http_get", "url", "/ping?i=" + i));
        }
        ToolInput input = ToolInput.from(Map.of(
                "journey", steps,
                "verification_mode", "http_api",
                "base_url", "http://127.0.0.1:8080"
        ));
        ToolUseContext ctx = ToolUseContext.of(workspace.toString(), "session-large");

        when(pythonClient.isCapabilityAvailable("HTTP_API")).thenReturn(true);
        HttpApiVerifier mockVerifier = mock(HttpApiVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("http_api"))).thenReturn(mockVerifier);
        when(mockVerifier.verify(any(JourneyRequest.class), anyString()))
                .thenReturn(new JourneyResult("verified", null, List.of(), Map.of()));
        when(evidenceStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = tool.call(input, ctx);

        assertFalse(result.isError(), "无尺寸限制时大 journey 应通过：" + result.content());
        assertTrue(result.content().contains("1000 steps"),
                "成功消息应反映实际步数 1000，实际：" + result.content());
    }

    // ─────────────────────────────────────────────────────────────────────
    // DevServerLauncher 类（通过 Mock 模拟启动失败的不同形态）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-04 start_command 不存在 → launcher 抛 RuntimeException，工具返回错误")
    void testStartCommandNotFound_returnsError() {
        prepareBrowserModeStubs();
        stubBrowserVerifier();
        when(devServerLauncher.start(any(), anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException(
                        "Failed to start dev server: bash: nonexistent-cmd-xyz: command not found"));

        ToolResult result = tool.call(browserInput("nonexistent-cmd-xyz"), browserCtx());

        assertTrue(result.isError());
        assertTrue(result.content().contains("VerifyJourney failed"),
                "应被外层 catch 包装为 VerifyJourney failed，实际：" + result.content());
        // 启动失败 → 不应进入 verifier
        verifyNoInteractions(evidenceStore);
    }

    @Test
    @DisplayName("EC-05 npm install 超时 → launcher 抛 RuntimeException(timeout)，工具优雅降级")
    void testNpmInstallTimeout_returnsError() {
        prepareBrowserModeStubs();
        stubBrowserVerifier();
        when(devServerLauncher.start(any(), anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("npm install timed out after 300s"));

        ToolResult result = tool.call(browserInput(null), browserCtx());

        assertTrue(result.isError());
        assertTrue(result.content().contains("npm install timed out"),
                "错误信息应保留底层根因，实际：" + result.content());
    }

    @Test
    @DisplayName("EC-06 HTTP 轮询超时 → DevServerTimeoutException，错误信息含日志尾巴与 timeout 秒数")
    void testHttpPollingTimeout_returnsTimeoutErrorWithLogTail() {
        prepareBrowserModeStubs();
        stubBrowserVerifier();
        String logTail = "[vite] error: failed to bind 0.0.0.0:5173";
        when(devServerLauncher.start(any(), anyString(), anyInt(), any()))
                .thenThrow(new DevServerTimeoutException(5173, Duration.ofSeconds(120), logTail));

        ToolResult result = tool.call(browserInput(null), browserCtx());

        assertTrue(result.isError());
        assertTrue(result.content().contains("Dev server failed to start within 120s"),
                "应明确提示 120s 超时，实际：" + result.content());
        assertTrue(result.content().contains(logTail),
                "应回传日志尾巴用于排障，实际：" + result.content());
    }

    @Test
    @DisplayName("EC-07 端口被占用 → launcher 抛 RuntimeException(EADDRINUSE)，错误返回")
    void testPortAlreadyInUse_returnsError() {
        prepareBrowserModeStubs();
        stubBrowserVerifier();
        when(devServerLauncher.start(any(), anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("Failed to start dev server: EADDRINUSE: address already in use :::5173"));

        ToolResult result = tool.call(browserInput(null), browserCtx());

        assertTrue(result.isError());
        assertTrue(result.content().contains("EADDRINUSE"),
                "错误信息应保留端口冲突线索，实际：" + result.content());
    }

    // ──────────────────────────────────────────────────────────────────
    // 能力降级类
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-16 BROWSER_AUTOMATION 能力不可用 → 返回 success 降级提示，不阻塞主任务")
    void testBrowserCapabilityUnavailable_returnsSuccessGracefulSkip() {
        // verifierFactory 选中 BrowserVerifier 路径，使 selectedMode == "browser"
        BrowserVerifier mockBrowser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("auto"))).thenReturn(mockBrowser);
        // 但能力不可用 → 应走降级分支
        when(pythonClient.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(false);

        ToolResult result = tool.call(browserInput(null), browserCtx());

        assertFalse(result.isError(), "能力不可用应降级为 success，实际：" + result.content());
        assertTrue(result.content().contains("Runtime verification unavailable"),
                "应明示运行时验证不可用，实际：" + result.content());
        assertTrue(result.content().contains("BROWSER_AUTOMATION"),
                "应点名缺失的能力名，实际：" + result.content());
        // 不应启动 DevServer、不应保存证据
        verify(devServerLauncher, never()).start(any(), anyString(), anyInt(), any());
        verify(evidenceStore, never()).save(any());
        // 未进入 verifier.verify
        verify(mockBrowser, never()).verify(any(), anyString());
    }

    @Test
    @DisplayName("EC-17 HTTP_API 能力不可用 → 返回 success 降级提示，不阻塞主任务")
    void testHttpApiCapabilityUnavailable_returnsSuccessGracefulSkip() {
        // 构造 HTTP 模式输入
        ToolInput input = ToolInput.from(Map.of(
                "journey", List.of(Map.of("action", "http_get", "url", "/ping")),
                "verification_mode", "http_api",
                "base_url", "http://127.0.0.1:8080"
        ));
        ToolUseContext ctx = ToolUseContext.of(workspace.toString(), "session-http-cap-off");

        // verifierFactory 返回非 BrowserVerifier → selectedMode == "http_api"
        HttpApiVerifier mockHttp = mock(HttpApiVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("http_api"))).thenReturn(mockHttp);
        when(pythonClient.isCapabilityAvailable("HTTP_API")).thenReturn(false);

        ToolResult result = tool.call(input, ctx);

        assertFalse(result.isError(), "能力不可用应降级为 success，实际：" + result.content());
        assertTrue(result.content().contains("HTTP_API capability not available"),
                "应明示 HTTP_API 能力不可用，实际：" + result.content());
        // HTTP 模式不走 DevServer；且不应进入 verifier.verify / evidenceStore.save
        verify(mockHttp, never()).verify(any(), anyString());
        verify(evidenceStore, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 技术栈检测类（直接使用真实 PreviewStackDetector + 临时目录）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-08 无 package.json 且无 index.html → 检测为 unknown 栈，工具跳过验证")
    void testNoPackageJson_returnsUnknownStackAndSkips() throws Exception {
        // 真实检测器 → unknown
        PreviewStackDetector realDetector = new PreviewStackDetector(objectMapper);
        StackInfo info = realDetector.detect(workspace);
        assertEquals("unknown", info.stackId());
        assertEquals(0, info.defaultPort());

        // 同时验证工具层在 stackId == unknown 时短路：returns success "skipped"
        when(pythonClient.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(true);
        BrowserVerifier mockBrowser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("auto"))).thenReturn(mockBrowser);
        when(previewStackDetector.detect(any())).thenReturn(info);

        ToolResult result = tool.call(browserInput(null), browserCtx());

        assertFalse(result.isError(), "unknown 栈应降级为 success（不阻塞主任务），实际：" + result.content());
        assertTrue(result.content().contains("unsupported stack"),
                "应明确说明栈不支持，实际：" + result.content());
        verify(devServerLauncher, never()).start(any(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("EC-09 package.json 非法 JSON → 检测器吞掉 IOException，回退为 unknown")
    void testMalformedPackageJson_returnsUnknown() throws Exception {
        Files.writeString(workspace.resolve("package.json"), "{ this is not valid json :: ");

        PreviewStackDetector realDetector = new PreviewStackDetector(objectMapper);
        StackInfo info = realDetector.detect(workspace);

        assertEquals("unknown", info.stackId(), "解析失败应回退为 unknown，避免误判");
        assertEquals(0, info.defaultPort());
        assertEquals("", info.defaultStartCommand());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 验证执行类（Python 服务异常通过 Verifier 返回 failed 模拟）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-10 Python 服务不可达 → Verifier 返回 PYTHON_CALL_FAILED，工具返回错误")
    void testPythonServiceUnreachable_returnsError() {
        prepareBrowserModeStubs();
        DevServerHandle handle = mockHandle();
        when(devServerLauncher.start(any(), anyString(), anyInt(), any())).thenReturn(handle);

        BrowserVerifier mockBrowser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("auto"))).thenReturn(mockBrowser);
        when(mockBrowser.verify(any(), anyString()))
                .thenReturn(JourneyResult.failed("PYTHON_CALL_FAILED", "Python service unreachable or timeout"));
        when(evidenceStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = tool.call(browserInput(null), browserCtx());

        assertTrue(result.isError());
        assertTrue(result.content().contains("Python service unreachable"),
                "错误信息应透传 Python 不可达原因，实际：" + result.content());
        // 失败仍应保存证据 + 推送 verify_attention
        verify(evidenceStore, times(1)).save(any());
        verify(notificationService, times(1)).sendVerifyAttention(anyString(), any());
        // 清理：finally 必然 stop
        verify(devServerLauncher, times(1)).stop(handle);
    }

    @Test
    @DisplayName("EC-11 Python 返回非法 JSON → callIfAvailable Optional.empty 等价于失败")
    void testPythonReturnsInvalidJson_treatedAsFailure() {
        // PythonCapabilityAwareClient.callWithRetry 在 ObjectMapper.readValue 抛异常时
        // 走 catch 分支并最终返回 Optional.empty()，与 BrowserVerifier 视角下「不可达」同源 → failed verdict
        prepareBrowserModeStubs();
        when(devServerLauncher.start(any(), anyString(), anyInt(), any())).thenReturn(mockHandle());

        BrowserVerifier mockBrowser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("auto"))).thenReturn(mockBrowser);
        when(mockBrowser.verify(any(), anyString()))
                .thenReturn(JourneyResult.failed("PYTHON_CALL_FAILED", "Python service unreachable or timeout"));
        when(evidenceStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = tool.call(browserInput(null), browserCtx());

        assertTrue(result.isError(), "非法 JSON 应被等价视为调用失败");
        assertTrue(result.content().contains("evidence bundle"),
                "失败结果应包含证据包标识便于追溯，实际：" + result.content());
    }

    @Test
    @DisplayName("EC-12 验证执行 >120s 超时 → Verifier 内部已超时返回 failed，工具不再继续等待")
    void testVerificationTimeout_returnsErrorWithoutBlocking() {
        prepareBrowserModeStubs();
        when(devServerLauncher.start(any(), anyString(), anyInt(), any())).thenReturn(mockHandle());

        BrowserVerifier mockBrowser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("auto"))).thenReturn(mockBrowser);
        // 模拟 BrowserVerifier 内部 120s timeout 命中 → Optional.empty → JourneyResult.failed
        when(mockBrowser.verify(any(), anyString()))
                .thenReturn(JourneyResult.failed("PYTHON_CALL_FAILED", "Python service unreachable or timeout"));
        when(evidenceStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        long startMs = System.currentTimeMillis();
        ToolResult result = tool.call(browserInput(null), browserCtx());
        long elapsed = System.currentTimeMillis() - startMs;

        assertTrue(result.isError());
        assertTrue(elapsed < 5_000,
                "Verifier 已自带 120s 超时；工具层不应再阻塞，本次耗时 ms=" + elapsed);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 容错类
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-13 EvidenceStore.save 抛异常 → 浏览器模式由外层 catch 兜底，返回错误而非崩溃")
    void testEvidenceStoreSaveThrows_doesNotCrashTool() {
        prepareBrowserModeStubs();
        when(devServerLauncher.start(any(), anyString(), anyInt(), any())).thenReturn(mockHandle());

        BrowserVerifier mockBrowser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("auto"))).thenReturn(mockBrowser);
        when(mockBrowser.verify(any(), anyString()))
                .thenReturn(new JourneyResult("verified", null, List.of(), Map.of()));
        when(evidenceStore.save(any())).thenThrow(new RuntimeException("DB connection lost"));

        ToolResult result = tool.call(browserInput(null), browserCtx());

        // 浏览器路径外层 try/catch 会捕获 → 返回 error 而非抛出
        assertTrue(result.isError(), "save 异常应被兜底为错误结果而非未捕获异常");
        assertTrue(result.content().contains("VerifyJourney failed"),
                "错误前缀应一致，实际：" + result.content());
    }

    @Test
    @DisplayName("EC-14 STOMP 推送异常 → log.warn 后吞掉，主流程仍返回 verified 成功")
    void testStompPushThrows_doesNotAffectVerdict() {
        prepareBrowserModeStubs();
        when(devServerLauncher.start(any(), anyString(), anyInt(), any())).thenReturn(mockHandle());

        BrowserVerifier mockBrowser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("auto"))).thenReturn(mockBrowser);
        when(mockBrowser.verify(any(), anyString()))
                .thenReturn(new JourneyResult("verified", null, List.of(), Map.of()));
        EvidenceBundle saved = EvidenceBundle.builder()
                .bundleId("ev-edge14").sessionId("s").kind("journey").verdict("verified").build();
        when(evidenceStore.save(any())).thenReturn(saved);

        // 让 messagingTemplate 抛异常
        doThrow(new RuntimeException("STOMP broker disconnected"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), eq("/queue/messages"), any());

        ToolResult result = tool.call(browserInput(null), browserCtx());

        assertFalse(result.isError(), "STOMP 异常不应影响最终 verdict");
        assertTrue(result.content().contains("PASSED"),
                "verified 应转化为 PASSED 文本，实际：" + result.content());
        assertTrue(result.content().contains("ev-edge14"),
                "成功消息应携带 bundleId");
    }

    @Test
    @DisplayName("EC-15 finally 阶段 DevServer.stop 抛异常 → log.warn 吞掉，结果仍正常返回")
    void testDevServerStopThrows_inFinally_doesNotAffectResult() {
        prepareBrowserModeStubs();
        DevServerHandle handle = mockHandle();
        when(devServerLauncher.start(any(), anyString(), anyInt(), any())).thenReturn(handle);

        BrowserVerifier mockBrowser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), eq("auto"))).thenReturn(mockBrowser);
        when(mockBrowser.verify(any(), anyString()))
                .thenReturn(new JourneyResult("verified", null, List.of(), Map.of()));
        when(evidenceStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // finally 中 stop 抛异常
        doThrow(new RuntimeException("kill -9 failed: permission denied"))
                .when(devServerLauncher).stop(handle);

        ToolResult result = tool.call(browserInput(null), browserCtx());

        assertFalse(result.isError(), "清理阶段异常不应影响主返回值");
        assertTrue(result.content().contains("PASSED"));
        // stop 仍被调用过（即使抛了异常）
        verify(devServerLauncher, times(1)).stop(handle);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 测试辅助方法
    // ─────────────────────────────────────────────────────────────────────

    /** 浏览器模式公共桩：BROWSER_AUTOMATION 可用 + Vite 栈检测命中。 */
    private void prepareBrowserModeStubs() {
        when(pythonClient.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(true);
        when(previewStackDetector.detect(any()))
                .thenReturn(new StackInfo("vite", 5173, "npm run dev"));
    }

    /** 让 verifierFactory 返回一个 BrowserVerifier 实例（用于让工具走浏览器分支）。 */
    private BrowserVerifier stubBrowserVerifier() {
        BrowserVerifier mockBrowser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), anyString())).thenReturn(mockBrowser);
        return mockBrowser;
    }

    /** 构造一个浏览器模式输入；startCommand 为 null 时自动从栈推断。 */
    private ToolInput browserInput(String startCommand) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("journey", List.of(Map.of("action", "navigate", "url", "/")));
        raw.put("verification_mode", "auto");
        if (startCommand != null) {
            raw.put("start_command", startCommand);
        }
        return ToolInput.from(raw);
    }

    private ToolUseContext browserCtx() {
        return ToolUseContext.of(workspace.toString(), "session-edge");
    }

    /** 返回一个携带 mock Process 的 DevServerHandle；DevServerHandle.pid() 走 record 字段，无需 stub Process#pid。 */
    private DevServerHandle mockHandle() {
        Process proc = mock(Process.class);
        return new DevServerHandle(
                proc,
                99999L,
                5173,
                workspace.resolve(".ai-code-assistant/devserver.log"),
                workspace.resolve(".ai-code-assistant/devserver.pid")
        );
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
