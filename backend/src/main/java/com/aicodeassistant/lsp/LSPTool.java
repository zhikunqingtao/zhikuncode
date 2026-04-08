package com.aicodeassistant.lsp;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LSPTool — 语言服务器协议集成，提供代码智能。
 * <p>
 * 支持 9 种操作:
 * <ul>
 *   <li>goToDefinition — 跳转到符号定义</li>
 *   <li>findReferences — 查找所有引用</li>
 *   <li>hover — 获取悬停信息 (类型签名、文档)</li>
 *   <li>documentSymbol — 文档符号列表</li>
 *   <li>workspaceSymbol — 工作区符号搜索</li>
 *   <li>goToImplementation — 跳转到实现</li>
 *   <li>prepareCallHierarchy — Call Hierarchy 第一步</li>
 *   <li>incomingCalls — 查找调用者</li>
 *   <li>outgoingCalls — 查找被调用者</li>
 * </ul>
 * <p>
 * 文件大小限制: 10MB。通过 Python 辅助服务管理 LSP 服务器生命周期。
 *
 * @see <a href="SPEC §4.1.4">LSPTool</a>
 */
@Component
public class LSPTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LSPTool.class);
    static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    private static final Set<String> VALID_OPERATIONS = Set.of(
            "goToDefinition", "findReferences", "hover",
            "documentSymbol", "workspaceSymbol", "goToImplementation",
            "prepareCallHierarchy", "incomingCalls", "outgoingCalls"
    );

    /** 操作 → LSP 方法名映射 */
    private static final Map<String, String> OPERATION_TO_METHOD = Map.of(
            "goToDefinition", "textDocument/definition",
            "findReferences", "textDocument/references",
            "hover", "textDocument/hover",
            "documentSymbol", "textDocument/documentSymbol",
            "workspaceSymbol", "workspace/symbol",
            "goToImplementation", "textDocument/implementation",
            "prepareCallHierarchy", "textDocument/prepareCallHierarchy",
            "incomingCalls", "callHierarchy/incomingCalls",
            "outgoingCalls", "callHierarchy/outgoingCalls"
    );

    /** 需要位置参数 (line/character) 的操作 */
    private static final Set<String> POSITION_OPERATIONS = Set.of(
            "goToDefinition", "findReferences", "hover",
            "goToImplementation", "prepareCallHierarchy"
    );

    private final LSPServerManager serverManager;

    public LSPTool(LSPServerManager serverManager) {
        this.serverManager = serverManager;
    }

    @Override
    public String getName() {
        return "LSP";
    }

    @Override
    public String getDescription() {
        return "Language Server Protocol integration providing code intelligence. " +
                "Supports go-to-definition, find-references, hover, document/workspace symbols, " +
                "go-to-implementation, and call hierarchy operations.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "operation", Map.of(
                                "type", "string",
                                "enum", List.of("goToDefinition", "findReferences", "hover",
                                        "documentSymbol", "workspaceSymbol", "goToImplementation",
                                        "prepareCallHierarchy", "incomingCalls", "outgoingCalls"),
                                "description", "LSP operation to perform"),
                        "filePath", Map.of(
                                "type", "string",
                                "description", "Target file path"),
                        "line", Map.of(
                                "type", "integer",
                                "description", "Line number (1-based)"),
                        "character", Map.of(
                                "type", "integer",
                                "description", "Column number (1-based)"),
                        "query", Map.of(
                                "type", "string",
                                "description", "Search query (for workspaceSymbol)")
                ),
                "required", List.of("operation")
        );
    }

    @Override
    public String getGroup() {
        return "lsp";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String operation = input.getString("operation");

        // 1. 验证操作类型
        if (!VALID_OPERATIONS.contains(operation)) {
            return ToolResult.error("Unknown LSP operation: " + operation
                    + ". Valid operations: " + VALID_OPERATIONS);
        }

        // 2. workspaceSymbol 特殊处理（不需要 filePath）
        if ("workspaceSymbol".equals(operation)) {
            return handleWorkspaceSymbol(input);
        }

        // 3. 获取文件路径
        String filePath = input.getString("filePath", null);
        if (filePath == null || filePath.isEmpty()) {
            return ToolResult.error("'filePath' is required for operation: " + operation);
        }

        // 4. 文件大小检查
        File file = new File(filePath);
        if (file.exists() && file.length() > MAX_FILE_SIZE) {
            return ToolResult.error("File too large: " + file.length()
                    + " bytes (max " + MAX_FILE_SIZE / 1024 / 1024 + "MB)");
        }

        // 5. 检查是否有对应语言的 LSP 服务器
        LSPServerInstance server = serverManager.getServerForFile(filePath);
        if (server == null) {
            String ext = LSPServerManager.getFileExtension(filePath);
            return ToolResult.error("No LSP server available for file type: " + ext
                    + ". Supported extensions: " + serverManager.getSupportedExtensions());
        }

        // 6. 需要位置参数的操作 — 验证 line/character
        if (POSITION_OPERATIONS.contains(operation)) {
            return handlePositionOperation(operation, filePath, input, server);
        }

        // 7. documentSymbol — 只需 filePath
        if ("documentSymbol".equals(operation)) {
            return handleDocumentSymbol(filePath, server);
        }

        // 8. incomingCalls / outgoingCalls — 需要先 prepareCallHierarchy
        if ("incomingCalls".equals(operation) || "outgoingCalls".equals(operation)) {
            return handleCallHierarchy(operation, filePath, input, server);
        }

        return ToolResult.error("Unhandled operation: " + operation);
    }

    private ToolResult handlePositionOperation(String operation, String filePath,
                                                ToolInput input, LSPServerInstance server) {
        int line = input.getInt("line", -1);
        int character = input.getInt("character", -1);
        if (line < 1 || character < 1) {
            return ToolResult.error("'line' and 'character' (1-based) are required for: " + operation);
        }

        String method = OPERATION_TO_METHOD.get(operation);
        Map<String, Object> params = new HashMap<>();
        params.put("textDocument", Map.of("uri", "file://" + filePath));
        params.put("position", Map.of("line", line - 1, "character", character - 1)); // LSP 0-based

        try {
            Map<String, Object> result = server.sendRequest(method, params);
            return ToolResult.success(formatResult(operation, result));
        } catch (Exception e) {
            return ToolResult.error("LSP " + operation + " failed: " + e.getMessage());
        }
    }

    private ToolResult handleDocumentSymbol(String filePath, LSPServerInstance server) {
        Map<String, Object> params = Map.of(
                "textDocument", Map.of("uri", "file://" + filePath));

        try {
            Map<String, Object> result = server.sendRequest("textDocument/documentSymbol", params);
            return ToolResult.success(formatResult("documentSymbol", result));
        } catch (Exception e) {
            return ToolResult.error("LSP documentSymbol failed: " + e.getMessage());
        }
    }

    private ToolResult handleWorkspaceSymbol(ToolInput input) {
        String query = input.getString("query", "");
        if (query.isEmpty()) {
            return ToolResult.error("'query' is required for workspaceSymbol operation");
        }

        // P1: 需要遍历所有服务器发送请求
        return ToolResult.success(
                "Workspace symbol search for '" + query + "': "
                        + "[P1 placeholder — requires active LSP server connections]");
    }

    private ToolResult handleCallHierarchy(String operation, String filePath,
                                            ToolInput input, LSPServerInstance server) {
        // Call Hierarchy 两步流程:
        // Step 1: prepareCallHierarchy → CallHierarchyItem[]
        // Step 2: incomingCalls/outgoingCalls → CallHierarchyCall[]
        int line = input.getInt("line", -1);
        int character = input.getInt("character", -1);
        if (line < 1 || character < 1) {
            return ToolResult.error("'line' and 'character' are required for " + operation);
        }

        String method = OPERATION_TO_METHOD.get(operation);
        Map<String, Object> params = new HashMap<>();
        params.put("textDocument", Map.of("uri", "file://" + filePath));
        params.put("position", Map.of("line", line - 1, "character", character - 1));

        try {
            Map<String, Object> result = server.sendRequest(method, params);
            return ToolResult.success(formatResult(operation, result));
        } catch (Exception e) {
            return ToolResult.error("LSP " + operation + " failed: " + e.getMessage());
        }
    }

    private String formatResult(String operation, Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return "No results for " + operation;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("LSP ").append(operation).append(" result:\n");
        result.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }
}
