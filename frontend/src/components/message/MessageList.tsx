/**
 * MessageList — 虚拟滚动消息列表
 *
 * SPEC: §8.2.1 MessageList (virtualized), §8.2.4E VirtualMessageList
 * 使用 react-virtuoso 替代原版自研虚拟滚动:
 * - 动态高度消息项自动测量
 * - followOutput="smooth" 自动滚动到底部
 * - 大量消息场景下的高性能渲染 (仅渲染可见区域)
 * - 流式更新不闪烁 (streaming 消息使用增量渲染)
 */

import React, { useCallback, useEffect, useRef } from 'react';
import { Virtuoso, type VirtuosoHandle } from 'react-virtuoso';
import { useMessageStore } from '@/store/messageStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { useResponsive } from '@/hooks/useResponsive';

import MessageItem from './MessageItem';

// react-virtuoso 配置 — 对齐 §8.2.4E VIRTUOSO_CONFIG
const VIRTUOSO_CONFIG = {
    overscan: 200,
    increaseViewportBy: { top: 200, bottom: 200 },
    defaultItemHeight: 80,
};

const MessageList: React.FC = () => {
    const virtuosoRef = useRef<VirtuosoHandle>(null);
    // §7.6 移动态：消息流底部 96px 渐隐遮罩（仅移动渲染，桌面零变化）
    const { isMobile } = useResponsive();

    // Subscribe to store slices
    const messages = useMessageStore(s => s.messages);
    const streamingMessageId = useMessageStore(s => s.streamingMessageId);
    const streamingContent = useMessageStore(s => s.streamingContent);
    const thinkingContent = useMessageStore(s => s.thinkingContent);
    const activeToolCalls = useMessageStore(s => s.activeToolCalls);
    const pendingMessageId = useWorkbenchViewStore(s => s.pendingMessageId);
    const consumePendingMessage = useWorkbenchViewStore(s => s.consumePendingMessage);

    useEffect(() => {
        if (!pendingMessageId) return;
        const index = messages.findIndex(message => message.uuid === pendingMessageId);
        if (index < 0) {
            consumePendingMessage();
            return;
        }
        let cancelled = false;
        let timer: ReturnType<typeof setTimeout> | null = null;
        let attempts = 0;
        const reveal = () => {
            if (cancelled) return;
            attempts += 1;
            if (!virtuosoRef.current && attempts < 10) {
                timer = setTimeout(reveal, 50);
                return;
            }
            virtuosoRef.current?.scrollToIndex({ index, align: 'center', behavior: 'auto' });
            // Virtuoso performs its initial measurement after mount. A second
            // authoritative scroll prevents that first layout pass from
            // resetting a deep link to the beginning of a long Session.
            timer = setTimeout(() => {
                virtuosoRef.current?.scrollToIndex({ index, align: 'center', behavior: 'auto' });
                consumePendingMessage();
            }, 120);
        };
        const frame = requestAnimationFrame(reveal);
        return () => {
            cancelled = true;
            cancelAnimationFrame(frame);
            if (timer) clearTimeout(timer);
        };
    }, [consumePendingMessage, messages, pendingMessageId]);

    // Render each message item
    const itemContent = useCallback((index: number, _data: unknown) => {
        const msg = messages[index];
        if (!msg) return null;

        const prevMsg = index > 0 ? messages[index - 1] : undefined;
        const isStreaming = msg.uuid === streamingMessageId;

        return (
            <MessageItem
                message={msg}
                prevMessage={prevMsg}
                isStreaming={isStreaming}
                streamingContent={isStreaming ? streamingContent : undefined}
                thinkingContent={isStreaming ? thinkingContent : undefined}
                activeToolCalls={activeToolCalls}
            />
        );
    }, [messages, streamingMessageId, streamingContent, thinkingContent, activeToolCalls]);

    // Auto-scroll: follow output when streaming
    const followOutput = useCallback((isAtBottom: boolean): boolean | 'smooth' => {
        // Always follow when streaming, otherwise follow if user is at bottom
        if (streamingMessageId) return 'smooth';
        return isAtBottom ? 'smooth' : false;
    }, [streamingMessageId]);

    // §7.6 底部渐隐：transparent → --v2-bg-app，pointer-events-none，仅 isMobile
    const mobileBottomFade = isMobile ? (
        <div
            data-testid="mobile-message-fade"
            aria-hidden="true"
            className="pointer-events-none absolute inset-x-0 bottom-0 z-10 h-24"
            style={{
                background: 'linear-gradient(to bottom, transparent, var(--v2-bg-app))',
            }}
        />
    ) : null;

    if (messages.length === 0) {
        return (
            <div className="message-list relative flex h-full flex-col overflow-hidden"
                role="log" aria-live="polite" aria-label="对话消息">
                <EmptyState />
                {mobileBottomFade}
            </div>
        );
    }

    return (
        <div className="message-list relative h-full overflow-hidden" role="log" aria-live="polite" aria-label="对话消息">
            <Virtuoso
                ref={virtuosoRef}
                totalCount={messages.length}
                itemContent={itemContent}
                followOutput={followOutput}
                overscan={VIRTUOSO_CONFIG.overscan}
                increaseViewportBy={VIRTUOSO_CONFIG.increaseViewportBy}
                defaultItemHeight={VIRTUOSO_CONFIG.defaultItemHeight}
                alignToBottom
                className="h-full"
            />
            {mobileBottomFade}
        </div>
    );
};

// ==================== Empty State ====================

const EmptyState: React.FC = () => (
    <div className="flex-1 flex items-center justify-center text-gray-500">
        <div className="text-center">
            <div className="text-4xl mb-3">💬</div>
            <div className="text-sm">Start a conversation</div>
            <div className="text-xs text-gray-600 mt-1">
                Type a message or use / for commands
            </div>
        </div>
    </div>
);

export default React.memo(MessageList);
