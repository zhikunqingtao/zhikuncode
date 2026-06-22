package com.aicodeassistant.mcp.roots;

import org.springframework.context.ApplicationEvent;

/**
 * 工程切换事件 — 触发 MCP 服务器 roots 更新通知。
 * <p>
 * 由前端切换工程或运行时切换 workspace 时发布，
 * {@code McpClientManager} 监听此事件并向所有已连接的 MCP 服务器
 * 发送 {@code notifications/roots/list_changed} 通知。
 */
public class WorkspaceChangedEvent extends ApplicationEvent {

    private final String workspacePath;
    private final String projectName;

    public WorkspaceChangedEvent(Object source, String workspacePath, String projectName) {
        super(source);
        this.workspacePath = workspacePath;
        this.projectName = projectName;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public String getProjectName() {
        return projectName;
    }
}
