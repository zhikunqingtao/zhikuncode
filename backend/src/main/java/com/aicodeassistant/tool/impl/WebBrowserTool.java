package com.aicodeassistant.tool.impl;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * WebBrowserTool — 浏览器自动化工具（Java→Python→Playwright 架构）。
 *
 * <p>通过 Python Playwright 提供页面导航、内容提取、截图、JavaScript 执行等能力。
 * 双重门控：feature flag + Python BROWSER_AUTOMATION 能力域。</p>
 *
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
            "handle_dialog", "get_cookies", "set_cookie", "close_session",
            "get_js_errors");
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
             + "Supports JavaScript rendering with structured error capture (js_errors + collected_errors), "
             + "advanced wait conditions (wait_until: networkidle/load/domcontentloaded, text_contains), "
             + "session validation (strict_session), and interactive web operations.\n\n"
             + "## Multi-step Browser Testing\n"
             + "This tool supports chaining multiple browser actions for end-to-end testing. Typical workflow:\n"
             + "1. navigate: Open the target URL\n"
             + "2. screenshot: Capture initial page state\n"
             + "3. type: Fill in form fields (use CSS selectors like 'input[name=q]', '#search-input', etc.)\n"
             + "4. click: Click buttons or links (use CSS selectors like 'button[type=submit]', 'a.link-class', etc.)\n"
             + "5. wait_for: Wait for page transitions or element appearance\n"
             + "6. screenshot: Capture result page\n"
             + "7. extract_text: Get page content for analysis\n"
             + "8. close_session: Clean up when done\n\n"
             + "## Common Selectors for Popular Sites\n"
             + "- Baidu (2025+ new version): search box `#chat-textarea`, submit button `#chat-submit-button`\n"
             + "- Baidu (legacy, hidden): `input[name=wd]`, `#kw`, `#su`\n"
             + "- Search inputs: input[type=search], input[name=q]\n"
             + "- Submit buttons: button[type=submit], input[type=submit]\n"
             + "- Links: a[href*=\"keyword\"]\n\n"
             + "## Important Notes\n"
             + "- Always use navigate first to create a session\n"
             + "- Use the same session_id across all actions in a test sequence\n"
             + "- Use wait_for after click/navigate to ensure page loads completely\n"
             + "- For AJAX pages (e.g. Baidu new search), use `no_wait_after: true` in click to avoid navigation timeout\n"
             + "- If click times out due to element visibility issues, the tool automatically retries with JS click\n"
             + "- Use click with `force: true` for elements that exist in DOM but are not visible (e.g. headless mode)\n"
             + "- After AJAX click, use `wait_for(wait_until: 'networkidle')` to wait for content loading\n"
             + "- Take screenshots at key checkpoints for visual evidence\n"
             + "- Always close_session when testing is complete\n"
             + "- Use get_js_errors to check for JavaScript errors on the page";
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
                        Map.entry("wait_until", Map.of(
                                "type", "string",
                                "description", "Wait condition type for wait_for action: networkidle, load, domcontentloaded")),
                        Map.entry("state", Map.of(
                                "type", "string",
                                "description", "Element state for wait_for action: visible, hidden, attached, detached (default: visible)")),
                        Map.entry("text_contains", Map.of(
                                "type", "string",
                                "description", "Wait until element text contains this value (requires selector, used with wait_for action)")),
                        Map.entry("strict_session", Map.of(
                                "type", "boolean",
                                "description", "If true, fail when session does not exist instead of auto-creating (default: false)")),
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
                                "description", "Values to select in a dropdown")),
                        Map.entry("no_wait_after", Map.of(
                                "type", "boolean",
                                "description", "If true, skip waiting for navigation after click (useful for AJAX pages like Baidu new search)")),
                        Map.entry("force", Map.of(
                                "type", "boolean",
                                "description", "Force click, skip visibility/actionability checks. Useful for elements that exist in DOM but are not visible in headless mode (default: false). Click auto-falls back to JS click on visibility errors."))
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
                // wait_for 现在支持 selector + wait_until 二选一
                if ("wait_for".equals(action)) {
                    String selector = input.getString("selector", null);
                    String waitUntil = input.getString("wait_until", null);
                    if ((selector == null || selector.isBlank()) && (waitUntil == null || waitUntil.isBlank())) {
                        return ValidationResult.invalid("MISSING_PARAM",
                                "wait_for action requires either 'selector' or 'wait_until' parameter");
                    }
                } else {
                    String selector = input.getString("selector", null);
                    if (selector == null || selector.isBlank()) {
                        return ValidationResult.invalid("MISSING_SELECTOR",
                                "Selector is required for " + action + " action");
                    }
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
        // 截图特殊处理：保存为文件，只返回路径给 LLM（避免 base64 撑爆上下文）
        if ("screenshot".equals(action) && br.data() != null && br.data().containsKey("screenshot_base64")) {
            String b64 = (String) br.data().get("screenshot_base64");
            long size = ((Number) br.data().get("size")).longValue();
            try {
                Path screenshotDir = Path.of(context.workingDirectory(), "screenshots");
                Files.createDirectories(screenshotDir);
                String filename = "screenshot_" + sessionId + "_" + System.currentTimeMillis() + ".png";
                Path filepath = screenshotDir.resolve(filename);
                byte[] imageBytes = Base64.getDecoder().decode(b64);
                Files.write(filepath, imageBytes);
                log.info("Screenshot saved: {} ({} bytes)", filepath, imageBytes.length);
                return ToolResult.success(
                        "Screenshot saved to: " + filepath.toAbsolutePath()
                        + " (size: " + imageBytes.length + " bytes)"
                );
            } catch (IOException e) {
                log.warn("Failed to save screenshot to file, returning truncated info: {}", e.getMessage());
                return ToolResult.success(
                        "Screenshot taken (" + size + " bytes) but failed to save to file: " + e.getMessage()
                );
            }
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
