package com.aicodeassistant.mcp.roots;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 集中管理当前 MCP 客户端声明的 Roots 范围。
 * <p>
 * MCP 服务器应仅在这些 roots 范围内进行文件操作（安全边界）。
 * 工程切换或多工程加载时由 {@code McpClientManager} 更新。
 */
@Service
public class McpRootsProvider {

    private final List<RootDescriptor> currentRoots = new CopyOnWriteArrayList<>();

    /**
     * 获取当前声明的所有 roots（不可变快照）。
     */
    public List<RootDescriptor> getCurrentRoots() {
        return List.copyOf(currentRoots);
    }

    /**
     * 替换当前 roots 集合 — 工程切换时调用。
     */
    public void updateRoots(String workspacePath, String projectName) {
        currentRoots.clear();
        if (workspacePath != null && !workspacePath.isBlank()) {
            currentRoots.add(RootDescriptor.fromPath(workspacePath, projectName));
        }
    }

    /**
     * 追加额外 root — 多工程场景。
     */
    public void addRoot(String workspacePath, String projectName) {
        if (workspacePath != null && !workspacePath.isBlank()) {
            currentRoots.add(RootDescriptor.fromPath(workspacePath, projectName));
        }
    }

    /** 清空所有 roots（连接关闭或测试场景）。 */
    public void clear() {
        currentRoots.clear();
    }
}
