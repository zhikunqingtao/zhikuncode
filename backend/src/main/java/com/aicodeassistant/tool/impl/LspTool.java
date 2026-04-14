package com.aicodeassistant.tool.impl;

import com.aicodeassistant.lsp.LspService;
import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LspTool implements Tool {

    private final LspService lspService;

    public LspTool(LspService lspService) { this.lspService = lspService; }

    @Override public String getName() { return "LSP"; }
    @Override public String getDescription() {
        return "Query Language Server Protocol for code intelligence "
             + "(definitions, references, symbols, diagnostics)";
    }
    @Override public String getGroup() { return "code_intelligence"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "required", List.of("action"),
            "properties", Map.of(
                "action", Map.of("type", "string",
                    "description", "LSP 操作: definition|references|symbols|diagnostics|hover"),
                "file_path", Map.of("type", "string", "description", "文件路径"),
                "line", Map.of("type", "integer", "description", "行号"),
                "column", Map.of("type", "integer", "description", "列号"),
                "query", Map.of("type", "string", "description", "符号查询字符串")
            )
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String action = input.getString("action");
        String filePath = input.getString("file_path", null);
        try {
            return switch (action) {
                case "definition" -> ToolResult.success(formatLocation(
                    lspService.getDefinition(filePath, input.getInt("line"), input.getInt("column"))));
                case "references" -> ToolResult.success(formatLocations(
                    lspService.getReferences(filePath, input.getInt("line"), input.getInt("column"))));
                case "symbols" -> ToolResult.success(formatSymbols(
                    filePath != null ? lspService.getDocumentSymbols(filePath)
                                     : lspService.getWorkspaceSymbols(input.getString("query"))));
                case "diagnostics" -> ToolResult.success(formatDiagnostics(
                    lspService.getDiagnostics(filePath)));
                case "hover" -> ToolResult.success(
                    lspService.getHoverInfo(filePath, input.getInt("line"), input.getInt("column")));
                default -> ToolResult.error("未知 LSP 操作: " + action);
            };
        } catch (Exception e) {
            return ToolResult.error("LSP 查询失败: " + e.getMessage());
        }
    }

    @Override public boolean shouldDefer() { return true; }
    @Override public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    private String formatLocation(Object loc) { return loc != null ? loc.toString() : "未找到"; }
    private String formatLocations(List<?> locs) { return locs != null ? locs.toString() : "未找到"; }
    private String formatSymbols(List<?> syms) { return syms != null ? syms.toString() : "无符号"; }
    private String formatDiagnostics(List<?> diags) { return diags != null ? diags.toString() : "无诊断"; }
}
