/**
 * MessageActions 组件测试
 *
 * 覆盖场景:
 * - 时间戳 (X月X日（周X）HH:MM:SS) 渲染
 * - 有文本 → 复制 Markdown 源码 (writeText)
 * - 无文本有图片 (base64) → Blob + ClipboardItem 写入 (clipboard.write)
 * - ClipboardItem 不支持 → 降级复制图片 URL 文本
 * - 无可复制内容 → 不渲染复制按钮 (时间戳仍显示)
 * - 流式进行中 (isStreaming) → 不渲染复制按钮 (时间戳仍显示)
 * - 点击后 Copy → Check 图标反馈，2 秒恢复
 * - UserMessage / AssistantMessage 集成 (group class + 传参)
 */

import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MessageActions from './MessageActions';
import UserMessage from './UserMessage';
import AssistantMessage from './AssistantMessage';
import type { Message, ContentBlock } from '@/types';

vi.mock('@/hooks/useTtsAvailability', () => ({
    useTtsAvailability: () => false,
}));

/** 2026-09-11 09:05:03 (本地时区，周五) → 期望时间戳 "9月11日（周五）09:05:03" */
const TS = new Date(2026, 8, 11, 9, 5, 3).getTime();

/** PNG 文件头 8 字节的 base64（无需有效图片，仅校验字节搬运） */
const PNG_HEADER_BASE64 = 'iVBORw0KGgo=';

function userMessage(content: ContentBlock[]): Extract<Message, { type: 'user' }> {
    return { type: 'user', uuid: 'u-1', timestamp: TS, content };
}

function assistantMessage(content: ContentBlock[]): Extract<Message, { type: 'assistant' }> {
    return {
        type: 'assistant',
        uuid: 'a-1',
        timestamp: TS,
        stopReason: 'end_turn',
        usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
        content,
    };
}

let clipboardWriteText: ReturnType<typeof vi.fn>;
let clipboardWrite: ReturnType<typeof vi.fn>;

class MockClipboardItem {
    readonly items: Record<string, Blob>;
    constructor(items: Record<string, Blob>) {
        this.items = items;
    }
}

beforeEach(() => {
    clipboardWriteText = vi.fn().mockResolvedValue(undefined);
    clipboardWrite = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: clipboardWriteText, write: clipboardWrite },
        configurable: true,
    });
});

afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
});

describe('MessageActions — 时间戳', () => {
    it('显示 X月X日（周X）HH:MM:SS 时间戳', () => {
        render(<MessageActions message={userMessage([{ type: 'text', text: 'hi' }])} />);
        expect(screen.getByTestId('message-timestamp')).toHaveTextContent('9月11日（周五）09:05:03');
    });
});

describe('MessageActions — 文本复制', () => {
    it('有文本时点击复制按钮复制 Markdown 源码（多 text 块 join 换行）', async () => {
        const msg = userMessage([
            { type: 'text', text: '# 标题\n\n正文 **加粗**' },
            { type: 'text', text: '第二段' },
        ]);
        render(<MessageActions message={msg} />);
        fireEvent.click(screen.getByTestId('message-copy-button'));
        await act(async () => {});
        expect(clipboardWriteText).toHaveBeenCalledTimes(1);
        expect(clipboardWriteText).toHaveBeenCalledWith('# 标题\n\n正文 **加粗**\n第二段');
        expect(clipboardWrite).not.toHaveBeenCalled();
    });

    it('点击复制后图标反馈为已复制，2 秒后恢复', async () => {
        vi.useFakeTimers();
        render(<MessageActions message={userMessage([{ type: 'text', text: 'hello' }])} />);
        const btn = screen.getByTestId('message-copy-button');
        expect(btn).toHaveAttribute('title', '复制');
        fireEvent.click(btn);
        await act(async () => {});
        expect(btn).toHaveAttribute('title', '已复制');
        expect(btn).toHaveAttribute('aria-label', '已复制');
        act(() => { vi.advanceTimersByTime(2000); });
        expect(btn).toHaveAttribute('title', '复制');
    });
});

describe('MessageActions — 图片复制', () => {
    it('无文本但有 base64 图片时经 ClipboardItem 写入图片 Blob', async () => {
        vi.stubGlobal('ClipboardItem', MockClipboardItem);
        const msg = assistantMessage([
            { type: 'image', mediaType: 'image/png', base64Data: PNG_HEADER_BASE64 },
        ]);
        render(<MessageActions message={msg} />);
        fireEvent.click(screen.getByTestId('message-copy-button'));
        await act(async () => {});
        expect(clipboardWrite).toHaveBeenCalledTimes(1);
        expect(clipboardWriteText).not.toHaveBeenCalled();
        const items = clipboardWrite.mock.calls[0][0] as MockClipboardItem[];
        expect(items).toHaveLength(1);
        const blob = items[0].items['image/png'];
        expect(blob).toBeInstanceOf(Blob);
        expect(blob.type).toBe('image/png');
        expect(blob.size).toBe(8); // PNG 头 8 字节
    });

    it('ClipboardItem 不支持时降级复制图片 URL 文本', async () => {
        vi.stubGlobal('ClipboardItem', undefined);
        const fetchMock = vi.fn().mockResolvedValue({
            ok: true,
            blob: async () => new Blob(['x'], { type: 'image/png' }),
        });
        vi.stubGlobal('fetch', fetchMock);
        const msg = assistantMessage([
            { type: 'image', mediaType: 'image/png', url: 'https://example.com/a.png' },
        ]);
        render(<MessageActions message={msg} />);
        fireEvent.click(screen.getByTestId('message-copy-button'));
        await act(async () => {});
        expect(clipboardWriteText).toHaveBeenCalledTimes(1);
        expect(clipboardWriteText).toHaveBeenCalledWith('https://example.com/a.png');
        expect(clipboardWrite).not.toHaveBeenCalled();
    });
});

describe('MessageActions — 复制按钮显示条件', () => {
    it('无可复制内容（仅 tool_use 块）时不渲染复制按钮，时间戳仍显示', () => {
        const msg = assistantMessage([
            { type: 'tool_use', toolUseId: 't-1', toolName: 'Bash', input: { command: 'ls' } },
        ]);
        render(<MessageActions message={msg} />);
        expect(screen.queryByTestId('message-copy-button')).not.toBeInTheDocument();
        expect(screen.getByTestId('message-timestamp')).toHaveTextContent('9月11日（周五）09:05:03');
    });

    it('流式进行中 (isStreaming) 不渲染复制按钮，时间戳仍显示', () => {
        const msg = assistantMessage([{ type: 'text', text: 'streaming...' }]);
        render(<MessageActions message={msg} isStreaming />);
        expect(screen.queryByTestId('message-copy-button')).not.toBeInTheDocument();
        expect(screen.getByTestId('message-timestamp')).toHaveTextContent('9月11日（周五）09:05:03');
    });

    it('图片块无 base64 也无 url 时不渲染复制按钮', () => {
        const msg = userMessage([{ type: 'image', mediaType: 'image/png' }]);
        render(<MessageActions message={msg} />);
        expect(screen.queryByTestId('message-copy-button')).not.toBeInTheDocument();
    });
});

describe('MessageActions — 消息组件集成', () => {
    it('UserMessage 根节点带 group class 并渲染操作条', () => {
        const { container } = render(
            <UserMessage message={userMessage([{ type: 'text', text: 'hi' }])} />,
        );
        const root = container.querySelector('.user-message') as HTMLElement;
        expect(root).not.toBeNull();
        expect(root.className).toContain('group');
        expect(screen.getByTestId('message-timestamp')).toBeInTheDocument();
        expect(screen.getByTestId('message-copy-button')).toBeInTheDocument();
    });

    it('AssistantMessage 根节点带 group class 并渲染操作条', () => {
        const { container } = render(
            <AssistantMessage message={assistantMessage([{ type: 'text', text: 'hi' }])} />,
        );
        const root = container.querySelector('.assistant-message') as HTMLElement;
        expect(root).not.toBeNull();
        expect(root.className).toContain('group');
        expect(screen.getByTestId('message-timestamp')).toBeInTheDocument();
        expect(screen.getByTestId('message-copy-button')).toBeInTheDocument();
    });

    it('AssistantMessage 流式进行中不渲染复制按钮', () => {
        render(
            <AssistantMessage
                message={assistantMessage([{ type: 'text', text: 'hi' }])}
                isStreaming
                streamingContent="hi"
            />,
        );
        expect(screen.queryByTestId('message-copy-button')).not.toBeInTheDocument();
        expect(screen.getByTestId('message-timestamp')).toBeInTheDocument();
    });
});
