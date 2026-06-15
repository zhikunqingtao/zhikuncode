package com.aicodeassistant.tool.verify;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.notify.NotificationService;
import com.aicodeassistant.notify.NotificationService.VerifyAttentionPayload;
import com.aicodeassistant.service.ActivityRepository;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.PermissionRequirement;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.verify.DevServerHandle;
import com.aicodeassistant.verify.DevServerLauncher;
import com.aicodeassistant.verify.DevServerTimeoutException;
import com.aicodeassistant.verify.EvidenceBundle;
import com.aicodeassistant.verify.EvidenceItem;
import com.aicodeassistant.verify.EvidenceStore;
import com.aicodeassistant.verify.JourneyRequest;
import com.aicodeassistant.verify.JourneyResult;
import com.aicodeassistant.verify.PreviewStackDetector;
import com.aicodeassistant.verify.StackInfo;
import com.aicodeassistant.verify.StepResult;
import com.aicodeassistant.verify.UserJourneyVerifier;
import com.aicodeassistant.verify.Verifier;
import com.aicodeassistant.verify.VerifierFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * VerifyJourneyTool — RV-1 运行时验证核心 Tool。
 *
 * <p>编排 DevServer 启动 → 浏览器 Journey 执行 → 证据保存 → 结果返回的完整流程。
 * 双重门控：feature flag (RUNTIME_VERIFICATION) + Python BROWSER_AUTOMATION 能力域。</p>
 */
@Component
public class VerifyJourneyTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(VerifyJourneyTool.class);

    private static final String CAPABILITY = "BROWSER_AUTOMATION";
    private static final String FEATURE_FLAG = "RUNTIME_VERIFICATION";
    private static final Duration DEV_SERVER_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CLOSE_SESSION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FAILURE_SNAPSHOT_TIMEOUT = Duration.ofSeconds(2);

    private final PythonCapabilityAwareClient pythonClient;
    private final DevServerLauncher devServerLauncher;
    private final VerifierFactory verifierFactory;
    private final PreviewStackDetector previewStackDetector;
    private final EvidenceStore evidenceStore;
    private final SimpMessagingTemplate messagingTemplate;
    private final FeatureFlagService featureFlags;
    private final ActivityRepository activityRepository;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public VerifyJourneyTool(PythonCapabilityAwareClient pythonClient,
                             DevServerLauncher devServerLauncher,
                             VerifierFactory verifierFactory,
                             PreviewStackDetector previewStackDetector,
                             EvidenceStore evidenceStore,
                             SimpMessagingTemplate messagingTemplate,
                             FeatureFlagService featureFlags,
                             ActivityRepository activityRepository,
                             ObjectMapper objectMapper,
                             NotificationService notificationService) {
        this.pythonClient = pythonClient;
        this.devServerLauncher = devServerLauncher;
        this.verifierFactory = verifierFactory;
        this.previewStackDetector = previewStackDetector;
        this.evidenceStore = evidenceStore;
        this.messagingTemplate = messagingTemplate;
        this.featureFlags = featureFlags;
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @Override
    public String getName() {
        return "VerifyJourney";
    }

    @Override
    public long getMaxExecutionTimeMs() {
        return 600_000L; // 10 minutes for full journey verification (dev server + browser)
    }

    @Override
    public String getDescription() {
        return "Run a user journey verification against the running application. "
             + "Starts dev server, executes steps DSL, collects evidence (screenshots + console + video). "
             + "Returns verified/failed with evidence bundle.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "required", List.of("journey"),
            "properties", Map.ofEntries(
                Map.entry("journey", Map.of(
                    "type", "array",
                    "description", "Steps DSL array. "
                        + "Browser actions: navigate, click, type, wait_for, assert_text, assert_url, assert_no_console_error, screenshot. "
                        + "HTTP actions: http_get, http_post, http_put, http_delete, assert_status, assert_json, assert_header, set_variable"
                )),
                Map.entry("start_command", Map.of(
                    "type", "string",
                    "description", "Dev server start command (e.g. 'npm run dev'). Only for browser verification. If omitted, auto-detected from package.json"
                )),
                Map.entry("base_url", Map.of(
                    "type", "string",
                    "description", "Base URL for verification. For browser: frontend URL (e.g. 'http://localhost:5173'). For HTTP API: backend URL (e.g. 'http://localhost:8080'). If omitted, auto-detected from stack"
                )),
                Map.entry("verification_mode", Map.of(
                    "type", "string",
                    "enum", List.of("browser", "http_api", "auto"),
                    "description", "Verification mode: 'browser' (Playwright), 'http_api' (HTTP calls), 'auto' (detect from steps). Default: 'auto'"
                )),
                Map.entry("record", Map.of(
                    "type", "boolean",
                    "description", "Whether to record video/trace/HAR. Only for browser verification. Default: true"
                ))
            )
        );
    }

    @Override
    public String getGroup() {
        return "verify";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.ALWAYS_ASK;
    }

    @Override
    public boolean shouldDefer() {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return false;
    }

    @Override
    public boolean isEnabled() {
        if (!featureFlags.isEnabled(FEATURE_FLAG)) {
            return false;
        }
        return pythonClient.isCapabilityAvailable("BROWSER_AUTOMATION")
            || pythonClient.isCapabilityAvailable("HTTP_API");
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return false;
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult call(ToolInput input, ToolUseContext context) {
        // 1. 解析输入
        Object journeyRaw = input.getRawData().get("journey");
        if (!(journeyRaw instanceof List<?> journeyList) || journeyList.isEmpty()) {
            return ToolResult.error("VerifyJourney requires a non-empty 'journey' array");
        }
        List<Map<String, Object>> journey = (List<Map<String, Object>>) journeyRaw;

        String startCommand = input.getString("start_command", null);
        String baseUrl = input.getString("base_url", null);
        String verificationMode = input.getString("verification_mode", "auto");
        boolean record = input.has("record") ? input.getBoolean("record") : true;

        String workspace = context.workingDirectory();
        String sessionId = context.sessionId();

        // 2. 选择 Verifier（多态）
        JourneyRequest req = new JourneyRequest(sessionId, null, journey, Map.of());
        Verifier verifier = verifierFactory.selectVerifier(req, verificationMode);
        String selectedMode = verifier instanceof com.aicodeassistant.verify.BrowserVerifier ? "browser" : "http_api";

        // 3. 浏览器模式：需要启动 DevServer
        if ("browser".equals(selectedMode)) {
            if (!pythonClient.isCapabilityAvailable(CAPABILITY)) {
                return ToolResult.success("Runtime verification unavailable: BROWSER_AUTOMATION capability not available. "
                        + "This does not block your task - proceed without runtime verification.");
            }

            // PreviewStackDetector 探测
            StackInfo stack = previewStackDetector.detect(Path.of(workspace));
            if ("unknown".equals(stack.stackId())) {
                return ToolResult.success("Runtime verification skipped: unsupported stack (" + stack.stackId() + "). "
                        + "Proceed without runtime verification.");
            }
            if (startCommand == null) {
                startCommand = stack.defaultStartCommand();
            }
            if (baseUrl == null) {
                baseUrl = "http://127.0.0.1:" + stack.defaultPort();
            }

            // 启动 DevServer
            DevServerHandle handle = null;
            try {
                handle = devServerLauncher.start(Path.of(workspace), startCommand, stack.defaultPort(),
                        DEV_SERVER_TIMEOUT);

                JourneyRequest browserReq = new JourneyRequest(
                        sessionId,
                        baseUrl,
                        journey,
                        record ? Map.of("video", true, "har", true, "trace", true) : Map.of()
                );

                String principal = sessionId;
                JourneyResult result = verifier.verify(browserReq, principal);
                return handleVerificationResult(result, sessionId, journey.size());

            } catch (DevServerTimeoutException e) {
                return ToolResult.error("Dev server failed to start within " + DEV_SERVER_TIMEOUT.toSeconds()
                        + "s. Log tail:\n" + e.getLogTail()
                        + "\nFix the dev server issue and retry VerifyJourney.");
            } catch (Exception e) {
                log.warn("VerifyJourney failed with unexpected exception", e);
                return ToolResult.error("VerifyJourney failed: " + e.getMessage());
            } finally {
                // 清理 DevServer + 浏览器 session
                if (handle != null) {
                    try {
                        devServerLauncher.stop(handle);
                    } catch (Exception e) {
                        log.warn("Failed to stop dev server pid={}: {}", handle.pid(), e.getMessage());
                    }
                }
                try {
                    pythonClient.callIfAvailable(
                            CAPABILITY,
                            "/api/browser/close_session",
                            Map.of("session_id", "rv-" + sessionId),
                            Map.class,
                            CLOSE_SESSION_TIMEOUT
                    );
                } catch (Exception ignored) {
                    // 清理失败不影响主流程
                }
            }

        } else {
            // HTTP API 模式：无需启动 DevServer
            if (!pythonClient.isCapabilityAvailable("HTTP_API")) {
                return ToolResult.success("Runtime verification unavailable: HTTP_API capability not available. "
                        + "This does not block your task - proceed without runtime verification.");
            }

            if (baseUrl == null) {
                baseUrl = "http://127.0.0.1:8080";
                log.info("HTTP API verification: using default base_url={}", baseUrl);
            }

            JourneyRequest apiReq = new JourneyRequest(
                    sessionId,
                    baseUrl,
                    journey,
                    Map.of()  // HTTP 模式无需录制
            );

            String principal = sessionId;
            JourneyResult result = verifier.verify(apiReq, principal);
            return handleVerificationResult(result, sessionId, journey.size());
        }
    }

    /**
     * 统一后处理：证据保存 → STOMP 推送 → Activity 记录 → 返回 ToolResult
     * 浏览器模式和 HTTP 模式共用此方法，避免逻辑重复。
     */
    private ToolResult handleVerificationResult(JourneyResult result, String sessionId, int stepCount) {
        EvidenceBundle bundle = EvidenceBundle.builder()
                .sessionId(sessionId)
                .kind("journey")
                .verdict(result.verdict())
                .claim("VerifyJourney invoked")
                .items(buildEvidenceItems(result))
                .build();
        EvidenceBundle saved = evidenceStore.save(bundle);

        // STOMP 推送最终结果
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "verification_result");
            payload.put("verdict", result.verdict());
            payload.put("bundleId", saved.bundleId());
            payload.put("errorMessage", result.errorMessage() != null ? result.errorMessage() : "");
            messagingTemplate.convertAndSendToUser(sessionId, "/queue/messages", payload);
        } catch (Exception e) {
            log.warn("Failed to push verification_result via STOMP: {}", e.getMessage());
        }

        // 三路返回
        if ("verified".equals(result.verdict())) {
            log.info("[RV-METRICS] verify_journey_passed session={} steps={} timestamp={}",
                    sessionId, stepCount, Instant.now().toString());
            recordActivity(sessionId, result.verdict(), saved.bundleId(), "success",
                    "Runtime verification passed: " + stepCount + " steps", null);
            return ToolResult.success("✓ Runtime verification PASSED. All " + stepCount
                    + " steps succeeded. Evidence bundle: " + saved.bundleId());
        } else if ("unavailable".equals(result.verdict())) {
            recordActivity(sessionId, result.verdict(), saved.bundleId(), "skipped",
                    "Runtime verification unavailable", result.errorMessage());
            return ToolResult.success("Runtime verification unavailable: " + result.errorMessage()
                    + ". This does not block your task.");
        } else {
            String baseMsg = result.errorMessage() != null
                    ? result.errorMessage()
                    : "Runtime verification failed";

            // RV-5：在 finally 块销毁会话之前，直接调用 /api/browser/snapshot-semantic
            String enrichedMsg = enrichWithFailureSnapshot(baseMsg, sessionId);

            // RV-METRICS: 结构化失败日志，便于 grep 统计失败率与错误分类
            StepResult failedStep = findFailedStep(result.stepResults());
            int failedIdx = failedStep != null ? failedStep.index() : -1;
            String failedAction = failedStep != null ? failedStep.action() : "unknown";
            int passedSteps = countPassedSteps(result.stepResults());
            String errorCategory = categorizeError(baseMsg, failedStep);
            String truncatedError = baseMsg.length() > 200 ? baseMsg.substring(0, 200) : baseMsg;
            log.info("[RV-METRICS] verify_journey_failed session={} step={}/{} action={} category={} passed={} timestamp={} error={}",
                    sessionId, failedIdx, stepCount, failedAction, errorCategory, passedSteps,
                    Instant.now().toString(), truncatedError);
            recordActivity(sessionId, result.verdict(), saved.bundleId(), "failed",
                    "Runtime verification failed", enrichedMsg);
            // RV-4: failed 时主动推送 verify_attention 通知（payload 使用 enrichedMsg）
            try {
                VerifyAttentionPayload attention = new VerifyAttentionPayload(
                        "verify_attention",
                        sessionId,
                        saved.bundleId(),
                        result.verdict(),
                        saved.claim(),
                        enrichedMsg,
                        true,
                        Instant.now().toString()
                );
                notificationService.sendVerifyAttention(sessionId, attention);
            } catch (Exception e) {
                log.warn("Failed to send verify_attention notification: {}", e.getMessage());
            }
            return ToolResult.error(enrichedMsg + " (evidence bundle: " + saved.bundleId() + ")");
        }
    }

    private List<EvidenceItem> buildEvidenceItems(JourneyResult result) {
        List<EvidenceItem> items = new ArrayList<>();
        if (result.stepResults() == null) {
            return items;
        }
        for (StepResult step : result.stepResults()) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("action", step.action());
            meta.put("ok", step.ok());
            meta.put("durationMs", step.durationMs());
            if (step.error() != null) {
                meta.put("error", step.error());
            }
            if (step.consoleErrors() != null && !step.consoleErrors().isEmpty()) {
                meta.put("consoleErrors", step.consoleErrors());
            }

            String type = step.screenshotBase64() != null ? "screenshot" : "command";
            String summary = String.format("Step %d [%s]: %s",
                    step.index(), step.action(), step.ok() ? "ok" : "failed");

            items.add(new EvidenceItem(null, type, summary, null, meta));
        }
        return items;
    }

    private static StepResult findFailedStep(List<StepResult> steps) {
        if (steps == null) {
            return null;
        }
        for (StepResult s : steps) {
            if (!s.ok()) {
                return s;
            }
        }
        return null;
    }

    private static int countPassedSteps(List<StepResult> steps) {
        if (steps == null) {
            return 0;
        }
        int count = 0;
        for (StepResult s : steps) {
            if (s.ok()) {
                count++;
            }
        }
        return count;
    }

    private static String categorizeError(String msg, StepResult failedStep) {
        if (failedStep != null && failedStep.consoleErrors() != null && !failedStep.consoleErrors().isEmpty()) {
            return "CONSOLE_ERROR";
        }
        if (msg == null) {
            return "OTHER";
        }
        String lower = msg.toLowerCase();
        if (lower.contains("not found") || lower.contains("no element")) {
            return "SELECTOR_NOT_FOUND";
        }
        if (lower.contains("timeout")) {
            return "TIMEOUT";
        }
        if (lower.contains("navigation") || lower.contains("err_connection")) {
            return "NAVIGATION_FAILED";
        }
        return "OTHER";
    }

    /**
     * 记录一条 verify_journey 类型的 Activity。失败不影响主流程。
     */
    private void recordActivity(String sessionId, String verdict, String bundleId,
                                String status, String summary, String errorMessage) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> toolResult = new HashMap<>();
            toolResult.put("verdict", verdict);
            toolResult.put("bundleId", bundleId);
            if (errorMessage != null) {
                toolResult.put("errorMessage", errorMessage);
            }
            String toolResultJson;
            try {
                toolResultJson = objectMapper.writeValueAsString(toolResult);
            } catch (JsonProcessingException jpe) {
                toolResultJson = null;
            }
            activityRepository.upsert(
                    UUID.randomUUID().toString(),
                    sessionId,
                    "verify_journey",
                    summary,
                    status,
                    System.currentTimeMillis(),
                    null,
                    0,
                    null,
                    toolResultJson,
                    null,
                    null
            );
        } catch (Exception e) {
            log.warn("Failed to record verify_journey activity: {}", e.getMessage());
        }
    }

    /**
     * RV-5：在验证失败时调用 /api/browser/snapshot-semantic 拉取语义快照，
     * 将摘要追加到错误消息末尾。
     *
     * <p>必须在 finally 块销毁会话之前调用。任何异常 / 超时 / 字段缺失均静默降级，
     * 返回原始 baseMsg，不影响主流程。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String enrichWithFailureSnapshot(String baseMsg, String sessionId) {
        try {
            Optional<Map> resp = pythonClient.callIfAvailable(
                    CAPABILITY,
                    "/api/browser/snapshot-semantic",
                    Map.of(
                            "session_id", "rv-" + sessionId,
                            "interesting_only", true,
                            "include_screenshot", false
                    ),
                    Map.class,
                    FAILURE_SNAPSHOT_TIMEOUT
            );
            if (resp.isEmpty()) {
                return baseMsg;
            }
            Object data = resp.get().get("data");
            if (!(data instanceof Map<?, ?> snapData) || snapData.isEmpty()) {
                return baseMsg;
            }
            String summary = formatSnapshotSummary((Map<String, Object>) snapData);
            if (summary == null || summary.isBlank()) {
                return baseMsg;
            }
            return baseMsg + "\n\n" + summary;
        } catch (Exception e) {
            log.debug("[RV-5] Failure snapshot unavailable: {}", e.getMessage());
            return baseMsg;
        }
    }

    /**
     * RV-5：将语义快照摘要为 LLM 友好的文本块。
     * 输入结构：{url, title, node_count, interactive[], tree:{aria}}
     */
    private String formatSnapshotSummary(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("=== Page Snapshot at Failure ===\n");

        Object url = snapshot.get("url");
        if (url != null) sb.append("URL: ").append(url).append('\n');

        Object title = snapshot.get("title");
        if (title != null) sb.append("Title: ").append(title).append('\n');

        Object nodeCount = snapshot.get("node_count");
        Object interactive = snapshot.get("interactive");
        int interactiveSize = (interactive instanceof List<?> il) ? il.size() : 0;
        sb.append("Nodes: ").append(nodeCount != null ? nodeCount : "?")
          .append(", Interactive: ").append(interactiveSize).append('\n');

        // ARIA tree 前 15 行
        Object tree = snapshot.get("tree");
        if (tree instanceof Map<?, ?> tm) {
            Object aria = tm.get("aria");
            if (aria instanceof String s && !s.isBlank()) {
                sb.append("\n[ARIA Tree (top 15 lines)]\n");
                String[] lines = s.split("\\R");
                int limit = Math.min(15, lines.length);
                for (int i = 0; i < limit; i++) sb.append(lines[i]).append('\n');
                if (lines.length > limit) sb.append("... (+").append(lines.length - limit).append(" lines)\n");
            }
        }

        // 交互元素清单 前 15 个
        if (interactive instanceof List<?> il && !il.isEmpty()) {
            sb.append("\n[Interactive Elements (top 15)]\n");
            int limit = Math.min(15, il.size());
            for (int i = 0; i < limit; i++) {
                Object item = il.get(i);
                if (item instanceof Map<?, ?> m) {
                    Object role = m.get("role");
                    Object name = m.get("name");
                    Object selector = m.get("selector");
                    sb.append("- role=").append(role)
                      .append(" name=").append(truncateField(name, 40))
                      .append(" selector=").append(truncateField(selector, 60))
                      .append('\n');
                }
            }
            if (il.size() > limit) sb.append("... (+").append(il.size() - limit).append(" more)\n");
        }
        sb.append("================================");
        return sb.toString();
    }

    private static String truncateField(Object o, int max) {
        if (o == null) return "null";
        String s = o.toString();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
