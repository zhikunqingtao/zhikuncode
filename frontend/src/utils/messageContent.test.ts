/**
 * messageContent 工具函数单元测试
 *
 * 覆盖:
 * - extractMessageText: user/assistant text 块 join、system 正文、空内容返回 null、其余类型 null
 * - extractMessageImage / extractMessageImages: 第一个 / 全部 image 块提取、非 user/assistant 返回空
 * - hasCopyableContent: 文本/图片判定、attachment 等类型固定 false
 * - base64ToBlob: 字节搬运与 mediaType
 * - copyImageToClipboard: ClipboardItem 缺失时 URL 文本降级
 * - copyMessageWithImageRefs: 文本 + 全部图片链接拼接、无 URL 占位、异常路径
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Message, ContentBlock } from '@/types';
import {
    extractMessageText,
    extractMessageImage,
    extractMessageImages,
    hasCopyableContent,
    base64ToBlob,
    copyImageToClipboard,
    copyMessageWithImageRefs,
} from './messageContent';

const TS = new Date(2026, 8, 11, 9, 5, 3).getTime();

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

const systemMessage = (content: string): Extract<Message, { type: 'system' }> =>
    ({ type: 'system', uuid: 's-1', timestamp: TS, content });

const attachmentMessage: Extract<Message, { type: 'attachment' }> = {
    type: 'attachment',
    uuid: 'at-1',
    timestamp: TS,
    filePath: '/tmp/a.pdf',
    fileName: 'a.pdf',
    mimeType: 'application/pdf',
    size: 1024,
};

const groupedToolUseMessage: Extract<Message, { type: 'grouped_tool_use' }> = {
    type: 'grouped_tool_use',
    uuid: 'g-1',
    timestamp: TS,
    toolCalls: [{ toolUseId: 't-1', toolName: 'Bash', status: 'completed' }],
};

describe('extractMessageText', () => {
    it('user 消息拼接多个 text 块 (join \\n)', () => {
        const msg = userMessage([
            { type: 'text', text: '第一段' },
            { type: 'image', mediaType: 'image/png', base64Data: 'iVBORw0KGgo=' },
            { type: 'text', text: '第二段' },
        ]);
        expect(extractMessageText(msg)).toBe('第一段\n第二段');
    });

    it('assistant 消息仅取 text 块，跳过 tool_use/thinking', () => {
        const msg = assistantMessage([
            { type: 'thinking', thinking: '内心独白' },
            { type: 'text', text: '回答' },
            { type: 'tool_use', toolUseId: 't-1', toolName: 'Bash', input: {} },
        ]);
        expect(extractMessageText(msg)).toBe('回答');
    });

    it('system 消息直接返回 content', () => {
        expect(extractMessageText(systemMessage('系统提示'))).toBe('系统提示');
    });

    it('空白文本返回 null', () => {
        expect(extractMessageText(userMessage([{ type: 'text', text: '   \n  ' }]))).toBeNull();
        expect(extractMessageText(systemMessage('  '))).toBeNull();
        expect(extractMessageText(userMessage([]))).toBeNull();
    });

    it('attachment 等类型返回 null', () => {
        expect(extractMessageText(attachmentMessage)).toBeNull();
        expect(extractMessageText(groupedToolUseMessage)).toBeNull();
    });
});

describe('extractMessageImage', () => {
    it('提取第一个 image 块字段', () => {
        const msg = userMessage([
            { type: 'image', mediaType: 'image/png', base64Data: 'AAAA' },
            { type: 'image', mediaType: 'image/jpeg', url: 'https://x/b.jpg' },
        ]);
        expect(extractMessageImage(msg)).toEqual({
            base64Data: 'AAAA',
            url: undefined,
            mediaType: 'image/png',
        });
    });

    it('无 image 块或非 user/assistant 类型返回 null', () => {
        expect(extractMessageImage(userMessage([{ type: 'text', text: 'hi' }]))).toBeNull();
        expect(extractMessageImage(attachmentMessage)).toBeNull();
        expect(extractMessageImage(systemMessage('hi'))).toBeNull();
    });
});

describe('hasCopyableContent', () => {
    it('有文本或有图片 (base64/url) 返回 true', () => {
        expect(hasCopyableContent(userMessage([{ type: 'text', text: 'hi' }]))).toBe(true);
        expect(hasCopyableContent(userMessage([
            { type: 'image', mediaType: 'image/png', base64Data: 'AAAA' },
        ]))).toBe(true);
        expect(hasCopyableContent(userMessage([
            { type: 'image', mediaType: 'image/png', url: 'https://x/a.png' },
        ]))).toBe(true);
        expect(hasCopyableContent(systemMessage('提示'))).toBe(true);
    });

    it('无文本且图片无 base64/url、或文件类消息返回 false', () => {
        expect(hasCopyableContent(userMessage([{ type: 'image', mediaType: 'image/png' }]))).toBe(false);
        expect(hasCopyableContent(assistantMessage([
            { type: 'tool_use', toolUseId: 't-1', toolName: 'Bash', input: {} },
        ]))).toBe(false);
        expect(hasCopyableContent(attachmentMessage)).toBe(false);
        expect(hasCopyableContent(groupedToolUseMessage)).toBe(false);
    });
});

describe('base64ToBlob', () => {
    it('base64 转 Blob 保留字节与 mediaType', () => {
        // PNG 文件头 8 字节
        const blob = base64ToBlob('iVBORw0KGgo=', 'image/png');
        expect(blob).toBeInstanceOf(Blob);
        expect(blob.type).toBe('image/png');
        expect(blob.size).toBe(8);
    });
});

describe('copyImageToClipboard', () => {
    let writeText: ReturnType<typeof vi.fn>;
    let write: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        writeText = vi.fn().mockResolvedValue(undefined);
        write = vi.fn().mockResolvedValue(undefined);
        Object.defineProperty(navigator, 'clipboard', {
            value: { writeText, write },
            configurable: true,
        });
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('ClipboardItem 不支持时降级复制 URL 文本', async () => {
        vi.stubGlobal('ClipboardItem', undefined);
        const fetchMock = vi.fn().mockResolvedValue({
            ok: true,
            blob: async () => new Blob(['x'], { type: 'image/png' }),
        });
        vi.stubGlobal('fetch', fetchMock);
        await copyImageToClipboard({ url: 'https://example.com/a.png', mediaType: 'image/png' });
        expect(writeText).toHaveBeenCalledWith('https://example.com/a.png');
        expect(write).not.toHaveBeenCalled();
    });

    it('clipboard.write 失败时降级复制 URL 文本', async () => {
        class MockClipboardItem {
            constructor(public readonly items: Record<string, Blob>) {}
        }
        vi.stubGlobal('ClipboardItem', MockClipboardItem);
        write.mockRejectedValue(new Error('denied'));
        await copyImageToClipboard({ base64Data: 'iVBORw0KGgo=', url: 'https://example.com/a.png', mediaType: 'image/png' });
        expect(write).toHaveBeenCalledTimes(1);
        expect(writeText).toHaveBeenCalledWith('https://example.com/a.png');
    });

    it('无 url 且写入不可用时抛出异常', async () => {
        vi.stubGlobal('ClipboardItem', undefined);
        await expect(
            copyImageToClipboard({ base64Data: 'iVBORw0KGgo=', mediaType: 'image/png' }),
        ).rejects.toThrow('copyImageToClipboard');
    });
});

describe('extractMessageImages', () => {
    it('按顺序提取全部图片块并过滤无数据空块', () => {
        const msg = userMessage([
            { type: 'text', text: 'hi' },
            { type: 'image', mediaType: 'image/png', base64Data: 'AAAA' },
            { type: 'image', mediaType: 'image/png' },
            { type: 'image', mediaType: 'image/jpeg', url: 'https://x/b.jpg' },
        ]);
        expect(extractMessageImages(msg)).toEqual([
            { base64Data: 'AAAA', url: undefined, mediaType: 'image/png' },
            { base64Data: undefined, url: 'https://x/b.jpg', mediaType: 'image/jpeg' },
        ]);
    });

    it('无图片返回空数组；非 user/assistant 返回空数组', () => {
        expect(extractMessageImages(userMessage([{ type: 'text', text: 'hi' }]))).toEqual([]);
        expect(extractMessageImages(systemMessage('sys'))).toEqual([]);
        expect(extractMessageImages(attachmentMessage)).toEqual([]);
    });
});

describe('copyMessageWithImageRefs', () => {
    let writeText: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        writeText = vi.fn().mockResolvedValue(undefined);
        Object.defineProperty(navigator, 'clipboard', {
            value: { writeText },
            configurable: true,
        });
    });

    it('文字 + 单个带 URL 图片：拼接文本与 [图片] URL 行', async () => {
        const msg = userMessage([
            { type: 'text', text: '如图，帮我看看' },
            { type: 'image', mediaType: 'image/png', url: 'https://oss.example.com/a.png' },
        ]);
        await copyMessageWithImageRefs(msg);
        expect(writeText).toHaveBeenCalledWith('如图，帮我看看\n[图片] https://oss.example.com/a.png');
    });

    it('多图消息：全部图片 URL 按序编号带上', async () => {
        const msg = assistantMessage([
            { type: 'text', text: '两张截图' },
            { type: 'image', mediaType: 'image/png', url: 'https://oss.example.com/1.png' },
            { type: 'image', mediaType: 'image/png', url: 'https://oss.example.com/2.png' },
        ]);
        await copyMessageWithImageRefs(msg);
        expect(writeText).toHaveBeenCalledWith(
            '两张截图\n[图片1] https://oss.example.com/1.png\n[图片2] https://oss.example.com/2.png',
        );
    });

    it('base64 无 URL 图片：占位标注内嵌图片无链接', async () => {
        const msg = userMessage([
            { type: 'text', text: '直传图' },
            { type: 'image', mediaType: 'image/png', base64Data: 'iVBORw0KGgo=' },
        ]);
        await copyMessageWithImageRefs(msg);
        expect(writeText).toHaveBeenCalledWith('直传图\n[图片]（内嵌图片，无链接）');
    });

    it('无可复制内容 / writeText 不可用时抛异常', async () => {
        await expect(copyMessageWithImageRefs(userMessage([]))).rejects.toThrow('nothing to copy');
        Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
        await expect(
            copyMessageWithImageRefs(userMessage([{ type: 'text', text: 'hi' }])),
        ).rejects.toThrow('writeText unavailable');
    });
});
