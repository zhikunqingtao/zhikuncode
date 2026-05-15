package com.aicodeassistant.lsp;

import com.aicodeassistant.lsp.model.CallLocation;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LspService — 语言服务器协议服务（Stub 实现）。
 * <p>
 * 当前为占位实现，所有方法返回空结果。
 * 完整实现需要 LSP 协议客户端 + 进程管理 + 多语言支持，
 * 或通过 IDE Bridge 代理 LSP 请求。
 */
@Service
public class LspService {

    public Object getDefinition(String filePath, int line, int column) {
        return "LSP service not yet implemented — definition lookup unavailable";
    }

    public List<?> getReferences(String filePath, int line, int column) {
        return Collections.emptyList();
    }

    public List<?> getDocumentSymbols(String filePath) {
        return Collections.emptyList();
    }

    public List<?> getWorkspaceSymbols(String query) {
        return Collections.emptyList();
    }

    public List<?> getDiagnostics(String filePath) {
        return Collections.emptyList();
    }

    public String getHoverInfo(String filePath, int line, int column) {
        return "LSP service not yet implemented — hover info unavailable";
    }

    // ===== Call Hierarchy 协议方法 (LSP 3.16+) =====

    /**
     * textDocument/prepareCallHierarchy — 准备调用层级。
     * <p>
     * 返回指定位置的 CallHierarchyItem 列表，用于后续 incoming/outgoing 查询。
     */
    public Optional<Map<String, Object>> prepareCallHierarchy(String filePath, int line, int column) {
        return Optional.empty();
    }

    /**
     * callHierarchy/incomingCalls — 获取调用当前符号的所有调用者。
     */
    public List<CallLocation> getIncomingCalls(String filePath, int line, int column) {
        return Collections.emptyList();
    }

    /**
     * callHierarchy/outgoingCalls — 获取当前符号调用的所有目标。
     */
    public List<CallLocation> getOutgoingCalls(String filePath, int line, int column) {
        return Collections.emptyList();
    }

    /**
     * textDocument/implementation — 获取接口/抽象类的实现位置。
     */
    public List<CallLocation> getImplementations(String filePath, int line, int column) {
        return Collections.emptyList();
    }
}
