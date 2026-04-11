package com.aicodeassistant.tool.impl;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * WebBrowserTool — 浏览器自动化工具（Java→Python→Playwright 架构）。
 *
 * <p>通过 Python Playwright 提供页面导航、内容提取、截图、JavaScript 执行等能力。
 * 双重门控：feature flag + Python BROWSER_AUTOMATION 能力域。</p>
 *
 * @see <a href="§10.4 B3">WebBrowserTool 设计</a>
 */
@Component
public class WebBrowserTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebBrowserTool.class);

    private final PythonCapabilityAwareClient pythonClient;
    private final FeatureFlagService featureFlags;
    private final ObjectMapper objectMapper;
    private static final String CAPABILITY = "BROWSER_AUTOMATION";
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "navigate", "screenshot", "click", "type", "evaluate",
            "extract_text", "extract_html", "wait_for", "select_option",
            "handle_dialog", "get_cookies", "set_cookie", "close_session");
    private static final Set<String> SAFE_PROTOCOLS = Set.of("http://", "https://");
    private static final int MAX_SCRIPT_LENGTH = 10000;
    private static final int MAX_TIMEOUT_MS = 120000;

    public WebBrowserTool(PythonCapabilityAwareClient pythonClient,
                          FeatureFlagService featureFlags,
                          ObjectMapper objectMapper) {
        this.pythonClient = pythonClient;
        this.featureFlags = featureFlags;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() { return "WebBrowser"; }

    @Override
    public String getDescription() {
        return "Browser automation tool for navigating web pages, clicking elements, "
             + "filling forms, taking screenshots, and extracting content. "
             + "Supports JavaScript rendering and interactive web operations.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("action"),
                "properties", Map.ofEntries(
                        Map.entry("action", Map.of(
                                "type", "string",
                                "enum", List.copyOf(ALLOWED_ACTIONS),
                                "description", "Browser action to perform")),
                        Map.entry("session_id", Map.of(
                                "type", "string",
                                "description", "Browser session ID (optional, defaults to current session)")),
                        Map.entry("url", Map.of(
                                "type", "string",
                                "description", "URL to navigate to")),
                        Map.entry("selector", Map.of(
                                "type", "string",
                                "description", "CSS selector for target element")),
                        Map.entry("text", Map.of(
                                "type", "string",
                                "description", "Text to type into an input field")),
                        Map.entry("script", Map.of(
                                "type", "string",
                                "description", "JavaScript expression to evaluate")),
                        Map.entry("full_page", Map.of(
                                "type", "boolean",
                                "description", "Whether to take a full page screenshot")),
                        Map.entry("wait_for", Map.of(
                                "type", "string",
                                "description", "Wait condition: load, domcontentloaded, or networkidle")),
                        Map.entry("timeout", Map.of(
                                "type", "integer",
                                "description", "Timeout in milliseconds (default: 30000, max: 120000)")),
                        Map.entry("accept", Map.of(
                                "type", "boolean",
                                "description", "Whether to accept a dialog")),
                        Map.entry("cookie", Map.of(
                                "type", "object",
                                "description", "Cookie object with name, value, domain, path")),
                        Map.entry("values", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Values to select in a dropdown"))
                )
        );
    }

    @Override
    public boolean isEnabled() {
        return featureFlags.isEnabled("WEB_BROWSER_TOOL")
                && pythonClient.isCapabilityAvailable(CAPABILITY);
    }

    @Override
    public boolean shouldDefer() { return true; }

    @Override
    public boolean isConcurrencySafe(ToolInput input) { return false; }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.ALWAYS_ASK;
    }

    @Override
    public ValidationResult validateInput(ToolInput input, ToolUseContext context) {
        String action = input.getString("action", null);
        if (action == null || !ALLOWED_ACTIONS.contains(action)) {
            return ValidationResult.invalid("INVALID_ACTION",
                    "Action must be one of: " + ALLOWED_ACTIONS);
        }

        switch (action) {
            case "navigate" -> {
                String url = input.getString("url", null);
                if (url == null || url.isBlank()) {
                    return ValidationResult.invalid("MISSING_URL", "URL is required for navigate action");
                }
                if (SAFE_PROTOCOLS.stream().noneMatch(url::startsWith)) {
                    return ValidationResult.invalid("UNSAFE_PROTOCOL",
                            "Only http:// and https:// protocols are allowed");
                }
            }
            case "click", "wait_for" -> {
                String selector = input.getString("selector", null);
                if (selector == null || selector.isBlank()) {
                    return ValidationResult.invalid("MISSING_SELECTOR",
                            "Selector is required for " + action + " action");
                }
            }
            case "type" -> {
                String selector = input.getString("selector", null);
                String text = input.getString("text", null);
                if (selector == null || selector.isBlank()) {
                    return ValidationResult.invalid("MISSING_SELECTOR", "Selector is required for type action");
                }
                if (text == null) {
                    return ValidationResult.invalid("MISSING_TEXT", "Text is required for type action");
                }
            }
            case "evaluate" -> {
                String script = input.getString("script", null);
                if (script == null || script.isBlank()) {
                    return ValidationResult.invalid("MISSING_SCRIPT", "Script is required for evaluate action");
                }
                if (script.length() > MAX_SCRIPT_LENGTH) {
                    return ValidationResult.invalid("SCRIPT_TOO_LONG",
                            "Script must be less than " + MAX_SCRIPT_LENGTH + " characters");
                }
            }
        }

        // 超时上限检查
        int timeout = input.getInt("timeout", 30000);
        if (timeout > MAX_TIMEOUT_MS) {
            return ValidationResult.invalid("TIMEOUT_TOO_HIGH",
                    "Timeout must be less than " + MAX_TIMEOUT_MS + "ms");
        }

        return ValidationResult.ok();
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String action = input.getString("action");
        String sessionId = input.getString("session_id", context.sessionId());
        Map<String, Object> body = new HashMap<>(input.getRawData());
        body.put("session_id", sessionId);

        Optional<BrowserResponse> resp = pythonClient.callIfAvailable(
                CAPABILITY, "/api/browser/" + action, body, BrowserResponse.class);

        if (resp.isEmpty()) {
            return ToolResult.error(
                    "Browser automation unavailable. Ensure playwright is installed: "
                    + "pip install playwright && playwright install chromium");
        }
        BrowserResponse br = resp.get();
        if (!br.success()) {
            return ToolResult.error(br.errorCode() + ": " + br.errorMessage());
        }
        // 截图特殊处理
        if ("screenshot".equals(action) && br.data() != null && br.data().containsKey("screenshot_base64")) {
            String b64 = (String) br.data().get("screenshot_base64");
            long size = ((Number) br.data().get("size")).longValue();
            return ToolResult.image(b64, "image/png", size);
        }
        try {
            return ToolResult.success(objectMapper.writeValueAsString(br.data()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ToolResult.error("Failed to serialize browser response: " + e.getMessage());
        }
    }

    // 注意：Python Pydantic 输出 snake_case JSON（error_code / error_message），
    // 项目 ObjectMapper 未配置 SNAKE_CASE 策略，需用 @JsonProperty 显式映射
    record BrowserResponse(
            boolean success,
            Map<String, Object> data,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage) {}
}
