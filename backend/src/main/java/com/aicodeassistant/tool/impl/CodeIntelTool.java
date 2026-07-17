package com.aicodeassistant.tool.impl;

import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class CodeIntelTool implements Tool {

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "python", "javascript", "typescript", "java", "go",
            "rust", "c", "cpp", "ruby", "php"
    );

    private final PythonCapabilityAwareClient pythonClient;

    public CodeIntelTool(PythonCapabilityAwareClient pythonClient) {
        this.pythonClient = pythonClient;
    }

    @Override public String getName() { return "CodeIntel"; }
    @Override public String getDescription() {
        return "查询代码符号结构、代码地图和依赖关系（基于 tree-sitter 的多语言静态分析）";
    }
    @Override public String getGroup() { return "code_intelligence"; }
    @Override public boolean shouldDefer() { return true; }
    @Override public PermissionRequirement getPermissionRequirement() { return PermissionRequirement.NONE; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("action", "content", "language"),
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "description", "操作类型: parse|symbols|code-map|dependencies"),
                        "content", Map.of("type", "string", "description", "要分析的源代码内容"),
                        "language", Map.of("type", "string",
                                "description", "编程语言: python|javascript|typescript|java|go|rust|c|cpp|ruby|php")
                )
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String action = input.getString("action");
        String content = input.getString("content");
        String language = input.getString("language", "").toLowerCase();

        if (!SUPPORTED_LANGUAGES.contains(language)) {
            return ToolResult.validationError("CODE_INTEL_LANGUAGE_UNSUPPORTED", "Language '" + language + "' not supported by code-intel. "
                    + "Supported: " + SUPPORTED_LANGUAGES + ". Use Read tool instead.");
        }

        String endpoint = switch (action) {
            case "parse" -> "/api/code-intel/parse";
            case "symbols" -> "/api/code-intel/symbols";
            case "code-map" -> "/api/code-intel/code-map";
            case "dependencies" -> "/api/code-intel/dependencies";
            default -> null;
        };
        if (endpoint == null) {
            return ToolResult.validationError("CODE_INTEL_ACTION_INVALID", "未知 CodeIntel 操作: " + action
                    + "（支持: parse|symbols|code-map|dependencies）");
        }

        Map<String, Object> body = Map.of("content", content, "language", language);
        Optional<String> result = pythonClient.callIfAvailable("CODE_INTEL", endpoint, body, String.class);
        return result
                .map(ToolResult::success)
                .orElseGet(() -> ToolResult.providerError("CODE_INTEL_UNAVAILABLE",
                        "CodeIntel 能力当前不可用（Python 服务未就绪或缺少 tree-sitter 依赖），请改用 Read 工具。",
                        ToolResult.Retryability.SAFE_READ_ONLY));
    }
}
