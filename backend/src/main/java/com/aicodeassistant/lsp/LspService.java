package com.aicodeassistant.lsp;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

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
}
