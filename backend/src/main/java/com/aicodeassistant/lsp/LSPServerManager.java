package com.aicodeassistant.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP 服务器管理器 — LSPTool 的底层服务层。
 * <p>
 * 职责:
 * <ul>
 *   <li>多服务器实例管理: 按文件扩展名映射到不同的 LSP 服务器</li>
 *   <li>文件同步协议: didOpen/didChange/didSave/didClose 生命周期管理</li>
 *   <li>请求路由: 根据文件路径自动选择目标服务器并转发请求</li>
 *   <li>生命周期管理: 初始化、空闲关闭、错误恢复</li>
 * </ul>
 *
 * @see <a href="SPEC §4.1.4a">LSPServerManager</a>
 */
@Service
public class LSPServerManager {

    private static final Logger log = LoggerFactory.getLogger(LSPServerManager.class);

    /** 文件扩展名 -> LSP 服务器实例 */
    private final Map<String, LSPServerInstance> serversByExtension = new ConcurrentHashMap<>();

    /** 服务器名称 -> LSP 服务器实例（去重用） */
    private final Map<String, LSPServerInstance> serversByName = new ConcurrentHashMap<>();

    /** 已打开文件追踪 (防止重复 didOpen) */
    private final Set<String> openedFiles = ConcurrentHashMap.newKeySet();

    // ===== 生命周期 =====

    /** 注册 LSP 服务器配置并启动 */
    public LSPServerInstance registerAndStart(LSPServerConfig config) {
        LSPServerInstance instance = new LSPServerInstance(config);
        try {
            instance.start();
            serversByName.put(config.name(), instance);
            for (String ext : config.fileExtensions()) {
                serversByExtension.put(ext, instance);
            }
            log.info("LSP server registered: {} for extensions {}", config.name(), config.fileExtensions());
        } catch (Exception e) {
            log.error("Failed to start LSP server: {}", config.name(), e);
        }
        return instance;
    }

    /** 关闭所有 LSP 服务器 */
    public void shutdown() {
        serversByName.values().forEach(LSPServerInstance::stop);
        serversByExtension.clear();
        serversByName.clear();
        openedFiles.clear();
    }

    // ===== 请求路由 =====

    /** 根据文件路径查找对应的 LSP 服务器 */
    public LSPServerInstance getServerForFile(String filePath) {
        String ext = getFileExtension(filePath);
        return serversByExtension.get(ext);
    }

    /** 确保文件对应的 LSP 服务器已启动（按需启动） */
    public LSPServerInstance ensureServerStarted(String filePath) {
        LSPServerInstance server = getServerForFile(filePath);
        if (server != null && !server.isRunning()) {
            server.start();
        }
        return server;
    }

    /** 发送 LSP 请求（自动路由到正确的服务器） */
    public Map<String, Object> sendRequest(String filePath, String method, Map<String, Object> params) {
        LSPServerInstance server = ensureServerStarted(filePath);
        if (server == null) {
            return null;
        }
        return server.sendRequest(method, params);
    }

    // ===== 文件同步协议 =====

    /** textDocument/didOpen */
    public void openFile(String filePath) {
        if (openedFiles.add(filePath)) {
            LSPServerInstance server = getServerForFile(filePath);
            if (server != null) {
                server.sendNotification("textDocument/didOpen",
                        Map.of("textDocument", Map.of("uri", "file://" + filePath)));
            }
        }
    }

    /** textDocument/didChange */
    public void changeFile(String filePath, String content) {
        LSPServerInstance server = getServerForFile(filePath);
        if (server != null && openedFiles.contains(filePath)) {
            server.sendNotification("textDocument/didChange",
                    Map.of("textDocument", Map.of("uri", "file://" + filePath),
                            "contentChanges", List.of(Map.of("text", content))));
        }
    }

    /** textDocument/didClose */
    public void closeFile(String filePath) {
        if (openedFiles.remove(filePath)) {
            LSPServerInstance server = getServerForFile(filePath);
            if (server != null) {
                server.sendNotification("textDocument/didClose",
                        Map.of("textDocument", Map.of("uri", "file://" + filePath)));
            }
        }
    }

    /** 检查文件是否已打开 */
    public boolean isFileOpen(String filePath) {
        return openedFiles.contains(filePath);
    }

    /** 获取所有已注册的服务器名 */
    public Set<String> getRegisteredServers() {
        return Set.copyOf(serversByName.keySet());
    }

    /** 获取支持的文件扩展名 */
    public Set<String> getSupportedExtensions() {
        return Set.copyOf(serversByExtension.keySet());
    }

    /** 服务器数量（测试用） */
    int serverCount() {
        return serversByName.size();
    }

    // ===== 工具方法 =====

    static String getFileExtension(String filePath) {
        if (filePath == null) return "";
        int dot = filePath.lastIndexOf('.');
        return dot >= 0 ? filePath.substring(dot) : "";
    }
}
