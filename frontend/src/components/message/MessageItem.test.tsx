/**
 * MessageItem 渲染测试
 * 覆盖 user 空内容消息防御兜底上移：无可渲染块（text/image）的 user 消息
 * 整条不渲染（含时间分隔条），避免 UserMessage 返回 null 时出现孤立浮动时间戳。
 */

import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import MessageItem from './MessageItem';
import type { Message } from '@/types';

vi.mock('@/hooks/useTtsAvailability', () => ({
    useTtsAvailability: () => false,
}));

type UserMessage = Extract<Message, { type: 'user' }>;

function userMessage(content: UserMessage['content']): UserMessage {
    return {
        type: 'user',
        uuid: 'u-1',
        timestamp: Date.now(),
        content,
    };
}

describe('MessageItem user 空内容消息兜底', () => {
    it('无可渲染块的 user 消息整条不渲染：无时间分隔条也无消息主体', () => {
        // 首条消息（无 prevMessage）showTimestamp=true，正是孤立时间戳的场景
        const { container } = render(
            <MessageItem
                message={userMessage([{
                    type: 'tool_result', toolUseId: 't-1', content: 'x', isError: false,
                }])}
            />,
        );
        expect(container.querySelector('.message-item')).toBeNull();
        expect(screen.queryByText('You')).not.toBeInTheDocument();
    });

    it('带 text block 的 user 消息正常渲染消息主体与时间分隔条', () => {
        const { container } = render(
            <MessageItem message={userMessage([{ type: 'text', text: 'hello' }])} />,
        );
        expect(container.querySelector('.message-item')).not.toBeNull();
        expect(screen.getByText('You')).toBeInTheDocument();
        expect(screen.getByText('hello')).toBeInTheDocument();
        // 首条消息必须有时间分隔条
        expect(container.querySelector('.message-item .justify-center')).not.toBeNull();
    });

    it('带 image block 的 user 消息正常渲染', () => {
        const { container } = render(
            <MessageItem message={userMessage([{
                type: 'image', mediaType: 'image/png', url: 'https://example.com/a.png',
            }])} />,
        );
        expect(container.querySelector('.message-item')).not.toBeNull();
        expect(screen.getByText('You')).toBeInTheDocument();
    });

    it('空内容检查仅针对 user 类型：assistant 消息不受影响', () => {
        const assistant: Extract<Message, { type: 'assistant' }> = {
            type: 'assistant',
            uuid: 'a-1',
            timestamp: Date.now(),
            stopReason: 'end_turn',
            usage: {
                inputTokens: 0, outputTokens: 0,
                cacheReadInputTokens: 0, cacheCreationInputTokens: 0,
            },
            content: [{ type: 'text', text: 'hi there' }],
        };
        const { container } = render(<MessageItem message={assistant} />);
        expect(container.querySelector('.message-item')).not.toBeNull();
        expect(screen.getByText('hi there')).toBeInTheDocument();
    });
});
