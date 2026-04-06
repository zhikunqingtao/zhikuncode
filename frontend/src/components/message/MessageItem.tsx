/**
 * MessageItem — 消息路由组件
 *
 * SPEC: §8.2.4J 消息类型 → 渲染组件映射
 * 根据 Message.type 分发到对应的渲染组件。
 * 动态显示时间戳 (消息间隔 >5 分钟时显示)。
 */

import React, { useMemo } from 'react';
import type { Message, ToolCallState } from '@/types';
import UserMessage from './UserMessage';
import AssistantMessage from './AssistantMessage';
import SystemMessage from './SystemMessage';
import { Paperclip, Layers, FolderSearch } from 'lucide-react';

interface MessageItemProps {
    message: Message;
    /** 前一条消息 (用于计算时间间隔) */
    prevMessage?: Message;
    /** 是否正在流式接收此消息 */
    isStreaming?: boolean;
    streamingContent?: string;
    thinkingContent?: string;
    activeToolCalls?: Map<string, ToolCallState>;
}

/** 5 分钟间隔阈值 (ms) */
const TIME_GAP_THRESHOLD = 5 * 60 * 1000;

const MessageItem: React.FC<MessageItemProps> = ({
    message,
    prevMessage,
    isStreaming,
    streamingContent,
    thinkingContent,
    activeToolCalls,
}) => {
    // Show timestamp if gap > 5 min
    const showTimestamp = useMemo(() => {
        if (!prevMessage) return true; // First message always shows
        const gap = message.timestamp - prevMessage.timestamp;
        return gap > TIME_GAP_THRESHOLD;
    }, [message.timestamp, prevMessage]);

    return (
        <div className="message-item">
            {/* Timestamp divider */}
            {showTimestamp && (
                <div className="flex justify-center py-2">
                    <span className="text-xs text-gray-600">
                        {formatTimestamp(message.timestamp)}
                    </span>
                </div>
            )}

            {/* Message content — route by type */}
            {renderMessage(message, isStreaming, streamingContent, thinkingContent, activeToolCalls)}
        </div>
    );
};

function renderMessage(
    message: Message,
    isStreaming?: boolean,
    streamingContent?: string,
    thinkingContent?: string,
    activeToolCalls?: Map<string, ToolCallState>,
): React.ReactNode {
    switch (message.type) {
        case 'user':
            return <UserMessage message={message} />;
        case 'assistant':
            return (
                <AssistantMessage
                    message={message}
                    isStreaming={isStreaming}
                    streamingContent={streamingContent}
                    thinkingContent={thinkingContent}
                    activeToolCalls={activeToolCalls}
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
        default:
            return null;
    }
}

// ==================== Attachment Message ====================

const AttachmentMessage: React.FC<{
    message: Extract<Message, { type: 'attachment' }>;
}> = ({ message }) => (
    <div className="px-4 py-2 my-1">
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-gray-800/50 border border-gray-700/50 text-sm">
            <Paperclip size={14} className="text-gray-500" />
            <span className="text-gray-300">{message.fileName}</span>
            <span className="text-xs text-gray-500">
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
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-gray-800/30 border border-gray-700/50">
            <Layers size={14} className="text-gray-500" />
            <span className="text-xs text-gray-400">
                {message.toolCalls.length} tool calls
            </span>
            <div className="flex flex-wrap gap-1 ml-1">
                {message.toolCalls.map((tc) => (
                    <span
                        key={tc.toolUseId}
                        className={`text-xs px-1.5 py-0.5 rounded ${
                            tc.status === 'completed'
                                ? 'bg-green-900/30 text-green-400'
                                : tc.status === 'error'
                                  ? 'bg-red-900/30 text-red-400'
                                  : 'bg-gray-800 text-gray-400'
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
            <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-gray-800/30 border border-gray-700/50">
                <FolderSearch size={14} className="text-gray-500" />
                <span className="text-xs text-gray-400">{summary}</span>
            </div>
        </div>
    );
};

// ==================== Helpers ====================

function formatTimestamp(ts: number): string {
    const d = new Date(ts);
    const now = new Date();
    const isToday = d.toDateString() === now.toDateString();
    const time = d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
    if (isToday) return time;
    return `${d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} ${time}`;
}

function formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default React.memo(MessageItem);
