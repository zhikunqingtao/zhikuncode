/**
 * MessageItem — 消息路由组件
 *
 * SPEC: §8.2.4J 消息类型 → 渲染组件映射
 * 分发渲染逻辑共享自 ./renderMessageContent（轮次分组路径的 TurnContent 复用同一分发），
 * 本组件仅补充平铺路径特有的时间分隔条。
 * 动态显示时间戳 (消息间隔 >5 分钟时显示)。
 */

import React, { useMemo } from 'react';
import type { Message, ToolCallState } from '@/types';
import { renderMessageContent } from './renderMessageContent';

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

    // 与 UserMessage 的防御兜底保持一致：user 消息无可渲染块时整条不渲染
    // （含时间分隔条），避免孤立的浮动时间戳
    if (message.type === 'user'
            && !message.content.some(block => block.type === 'text' || block.type === 'image')) {
        return null;
    }

    return (
        <div className="message-item">
            {/* Timestamp divider */}
            {showTimestamp && (
                <div className="flex justify-center py-2">
                    <span className="text-xs text-[var(--text-muted)]">
                        {formatTimestamp(message.timestamp)}
                    </span>
                </div>
            )}

            {/* Message content — route by type（与轮次分组路径共享 renderMessageContent） */}
            {renderMessageContent(message, {
                isStreaming,
                streamingContent,
                thinkingContent,
                activeToolCalls,
            })}
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

export default React.memo(MessageItem);
