/**
 * renderMessageContent — 按消息类型分发渲染单条消息的共享逻辑
 *
 * 供轮次回复区与过程分节共用的消息渲染入口。
 */

import React, { useMemo } from 'react';
import type { Message, ToolCallState } from '@/types';
import UserMessage from './UserMessage';
import AssistantMessage from './AssistantMessage';
import SystemMessage from './SystemMessage';
import VisualizationMessage from './VisualizationMessage';
import { Paperclip, Layers, FolderSearch } from 'lucide-react';

/** 单条消息渲染上下文（流式透传 + 活跃工具调用） */
export interface MessageRenderContext {
    embeddedAssistant?: boolean;
    hideAssistantActions?: boolean;
    /** 是否正在流式接收此消息 */
    isStreaming?: boolean;
    streamingContent?: string;
    thinkingContent?: string;
    activeToolCalls?: Map<string, ToolCallState>;
}

export function renderMessageContent(
    message: Message,
    ctx?: MessageRenderContext,
): React.ReactNode {
    switch (message.type) {
        case 'user':
            return <UserMessage message={message} />;
        case 'assistant':
            return (
                <AssistantMessage
                    message={message}
                    embedded={ctx?.embeddedAssistant}
                    hideActions={ctx?.hideAssistantActions}
                    isStreaming={ctx?.isStreaming}
                    streamingContent={ctx?.streamingContent}
                    thinkingContent={ctx?.thinkingContent}
                    activeToolCalls={ctx?.activeToolCalls}
                />
            );
        case 'system':
            return <SystemMessage message={message} />;
        case 'attachment':
            return <AttachmentMessage message={message} />;
        case 'grouped_tool_use':
            return <GroupedToolUseMessage message={message} />;
        case 'collapsed_read_search':
            return <CollapsedReadSearchMessage message={message} />;
        case 'visualization':
            return <VisualizationMessage message={message} />;
        default:
            return null;
    }
}

// ==================== Attachment Message ====================

const AttachmentMessage: React.FC<{
    message: Extract<Message, { type: 'attachment' }>;
}> = ({ message }) => (
    <div className="px-4 py-2 my-1">
        <div className="flex items-center gap-2 px-3 py-2 rounded-[14px] bg-surface2 border border-hairline text-sm">
            <Paperclip size={14} className="text-t4" />
            <span className="text-t1">{message.fileName}</span>
            <span className="text-[13px] text-t4 tabular-nums">
                ({formatFileSize(message.size)})
            </span>
        </div>
    </div>
);

// ==================== Grouped Tool Use ====================

const GroupedToolUseMessage: React.FC<{
    message: Extract<Message, { type: 'grouped_tool_use' }>;
}> = ({ message }) => (
    <div className="px-4 py-2 my-1">
        <div className="flex items-center gap-2 px-3 py-2 rounded-[14px] bg-surface2 border border-hairline">
            <Layers size={14} className="text-t4" />
            <span className="text-[13px] text-t2 tabular-nums">
                {message.toolCalls.length} tool calls
            </span>
            <div className="flex flex-wrap gap-1 ml-1">
                {message.toolCalls.map((tc) => (
                    <span
                        key={tc.toolUseId}
                        className={`text-[13px] px-1.5 py-0.5 rounded ${
                            tc.status === 'completed'
                                ? 'bg-oksoft text-ok'
                                : tc.status === 'error'
                                  ? 'bg-errsoft text-err'
                                  : 'bg-sunken2 text-t2'
                        }`}
                    >
                        {tc.toolName}
                    </span>
                ))}
            </div>
        </div>
    </div>
);

// ==================== Collapsed Read/Search ====================

const CollapsedReadSearchMessage: React.FC<{
    message: Extract<Message, { type: 'collapsed_read_search' }>;
}> = ({ message }) => {
    const summary = useMemo(() => {
        const reads = message.operations.filter(op => op.type === 'read').length;
        const searches = message.operations.filter(op => op.type === 'search').length;
        const parts: string[] = [];
        if (reads > 0) parts.push(`Read ${reads} file${reads > 1 ? 's' : ''}`);
        if (searches > 0) parts.push(`Searched ${searches} pattern${searches > 1 ? 's' : ''}`);
        return parts.join(', ') || `${message.operations.length} operations`;
    }, [message.operations]);

    return (
        <div className="px-4 py-2 my-1">
            <div className="flex items-center gap-2 px-3 py-2 rounded-[14px] bg-surface2 border border-hairline">
                <FolderSearch size={14} className="text-t4" />
                <span className="text-[13px] text-t2">{summary}</span>
            </div>
        </div>
    );
};

// ==================== Helpers ====================

function formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
