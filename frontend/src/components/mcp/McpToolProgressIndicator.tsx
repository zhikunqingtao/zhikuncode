/**
 * MCP 工具执行进度指示器 — M4 长操作支持
 * <p>
 * 在消息流中展示当前 MCP 工具调用的进度，并允许用户点击取消。
 * 进度数据由 {@link useMcpStore} 的 inflightMcpCalls 维护，
 * 来源于 WebSocket {@code mcp_tool_progress} 消息。
 */
import React from 'react';

export interface McpToolProgress {
    progressToken: string;
    serverName: string;
    toolName: string;
    progress: number;
    total: number;
    message: string;
}

interface Props {
    progress: McpToolProgress;
    onCancel: (progressToken: string) => void;
}

export const McpToolProgressIndicator: React.FC<Props> = ({ progress, onCancel }) => {
    const percentage = progress.total > 0
        ? Math.round((progress.progress / progress.total) * 100)
        : 0;

    const statusLabel = percentage > 0
        ? `${percentage}%`
        : (progress.message || '执行中…');

    return (
        <div className="flex items-center gap-2 p-2 bg-gray-50 dark:bg-gray-800 rounded-md text-sm">
            <div className="flex-1 min-w-0">
                <div className="flex justify-between text-xs text-gray-500 mb-1">
                    <span className="truncate" title={`${progress.serverName}:${progress.toolName}`}>
                        {progress.serverName}: {progress.toolName}
                    </span>
                    <span className="ml-2 flex-shrink-0">{statusLabel}</span>
                </div>
                <div className="w-full bg-gray-200 dark:bg-gray-700 rounded-full h-1.5">
                    <div
                        className="bg-blue-500 h-1.5 rounded-full transition-all duration-300"
                        style={{ width: `${Math.max(percentage, 5)}%` }}
                    />
                </div>
                {progress.message && percentage > 0 && (
                    <div className="text-xs text-gray-400 mt-1 truncate" title={progress.message}>
                        {progress.message}
                    </div>
                )}
            </div>
            <button
                type="button"
                onClick={() => onCancel(progress.progressToken)}
                className="text-xs text-red-500 hover:text-red-700 px-2 py-1 rounded hover:bg-red-50 dark:hover:bg-red-900/20"
                title="取消"
            >
                取消
            </button>
        </div>
    );
};

export default McpToolProgressIndicator;
