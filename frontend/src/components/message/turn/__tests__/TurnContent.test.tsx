/**
 * TurnContent 组件测试
 * 覆盖：按序渲染 + data-message-uuid 锚点 wrapper、流式透传关键路径
 * （仅 streamingMessageId 命中的消息获得流式上下文）、纯 tool_result
 * 载体 user 消息跳过、无 >5min 时间分隔条、flattenTurnBlocks 工具聚合
 * 接线（tool_run → ToolRunBlock、跨消息合并锚点、阻断矩阵、streaming 豁免）。
 */

import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ContentBlock, Message, ToolCallState, ToolResult } from '@/types';
import { buildTurns } from '@/store/selectors/turnProjection';
import TurnContent from '../TurnContent';

vi.mock('@/hooks/useTtsAvailability', () => ({
    useTtsAvailability: () => false,
}));

function userText(uuid: string, timestamp: number, text = `text-${uuid}`): Message {
    return { type: 'user', uuid, timestamp, content: [{ type: 'text', text }] } as Message;
}

function userToolResultCarrier(uuid: string, timestamp: number): Message {
    return {
        type: 'user', uuid, timestamp,
        content: [{ type: 'tool_result', toolUseId: `tu-${uuid}`, content: 'result', isError: false }],
    } as Message;
}

function assistantMsg(uuid: string, timestamp: number, text = `reply-${uuid}`): Message {
    return {
        type: 'assistant', uuid, timestamp,
        content: [{ type: 'text', text }],
        stopReason: 'end_turn',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
    } as Message;
}

describe('TurnContent 轮内渲染', () => {
    it('按序渲染全部消息并包 data-message-uuid wrapper', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantMsg('a1', 2),
            assistantMsg('a2', 3, '第二段回复'),
        ]);
        const { container } = render(<TurnContent turn={turn} />);
        expect(screen.getByText('问题')).toBeInTheDocument();
        expect(screen.getByText('reply-a1')).toBeInTheDocument();
        expect(screen.getByText('第二段回复')).toBeInTheDocument();

        const wrappers = container.querySelectorAll('[data-message-uuid]');
        expect(Array.from(wrappers).map(el => el.getAttribute('data-message-uuid')))
            .toEqual(['u1', 'a1', 'a2']);
    });

    it('纯 tool_result 载体 user 消息整条跳过（与 MessageItem 兜底一致）', () => {
        // 载体消息不开新轮，归并到 u1 所在轮
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            userToolResultCarrier('u2', 2),
            assistantMsg('a1', 3),
        ]);
        const { container } = render(<TurnContent turn={turn} />);
        expect(container.querySelector('[data-message-uuid="u2"]')).toBeNull();
        expect(screen.getByText('reply-a1')).toBeInTheDocument();
    });

    it('轮内不渲染 >5min 时间分隔条', () => {
        // 两条消息间隔 10 分钟，平铺路径会渲染时间分隔条
        const [turn] = buildTurns([
            userText('u1', 1_000_000, '问题'),
            assistantMsg('a1', 1_000_000 + 10 * 60 * 1000),
        ]);
        const { container } = render(<TurnContent turn={turn} />);
        expect(container.querySelector('.message-item')).toBeNull();
    });
});

describe('TurnContent 流式透传', () => {
    it('streamingMessageId 命中的消息渲染流式文本', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantMsg('a1', 2, ''),
        ]);
        render(
            <TurnContent
                turn={turn}
                streamingMessageId="a1"
                streamingContent="正在生成的流式内容"
            />,
        );
        expect(screen.getByText(/正在生成的流式内容/)).toBeInTheDocument();
    });

    it('未命中 streamingMessageId 的消息按终态渲染（不吃流式上下文）', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantMsg('a1', 2, '已完成的回复'),
            assistantMsg('a2', 3, ''),
        ]);
        render(
            <TurnContent
                turn={turn}
                streamingMessageId="a2"
                streamingContent="第二轮流式"
            />,
        );
        // a1 终态渲染原文；a2 渲染流式内容
        expect(screen.getByText(/已完成的回复/)).toBeInTheDocument();
        expect(screen.getByText(/第二轮流式/)).toBeInTheDocument();
    });

    it('流式无内容时保留 RunningIndicator 兜底（Thinking...）', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantMsg('a1', 2, ''),
        ]);
        render(
            <TurnContent
                turn={turn}
                streamingMessageId="a1"
                streamingContent=""
            />,
        );
        expect(screen.getByText('Thinking...')).toBeInTheDocument();
    });
});

// ==================== 工具聚合接线（flattenTurnBlocks → ToolRunBlock） ====================

// jsdom 无 matchMedia 实现，ToolCallBlock/CodeBlock 的 resolveTheme('system') 依赖它
beforeAll(() => {
    if (typeof window.matchMedia !== 'function') {
        window.matchMedia = ((query: string) => ({
            matches: false,
            media: query,
            onchange: null,
            addListener: () => {},
            removeListener: () => {},
            addEventListener: () => {},
            removeEventListener: () => {},
            dispatchEvent: () => false,
        })) as unknown as typeof window.matchMedia;
    }
});

const okResult = (content = 'done'): ToolResult => ({ content, isError: false });

function toolUseBlock(id: string, toolName = 'Read', result?: ToolResult): ContentBlock {
    return {
        type: 'tool_use',
        toolUseId: id,
        toolName,
        input: { file_path: `/f/${id}` },
        ...(result ? { result } : {}),
    };
}

function assistantWithBlocks(uuid: string, timestamp: number, content: ContentBlock[]): Message {
    return {
        type: 'assistant', uuid, timestamp, content,
        stopReason: 'end_turn',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
    } as Message;
}

describe('TurnContent 工具聚合接线', () => {
    it('assistant 内连续 tool_use 聚合为 ToolRunBlock，text 段原位渲染', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [
                { type: 'text', text: '先查一下' },
                toolUseBlock('t1', 'Read', okResult()),
                toolUseBlock('t2', 'Edit', okResult()),
                { type: 'text', text: '改完了' },
            ]),
        ]);
        render(<TurnContent turn={turn} />);
        // 唯一聚合块，L1 摘要计数正确
        expect(screen.getAllByTestId('tool-run-block')).toHaveLength(1);
        expect(screen.getByText('2 次工具调用')).toBeInTheDocument();
        // text 段保持原位块级渲染（未被聚合吞掉）
        expect(screen.getByText('先查一下')).toBeInTheDocument();
        expect(screen.getByText('改完了')).toBeInTheDocument();
    });

    it('跨消息合并（纯 tool_result 载体透明跳过）且被吸收消息保留深链锚点', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [toolUseBlock('t1', 'Read', okResult())]),
            userToolResultCarrier('r1', 3),
            assistantWithBlocks('a2', 4, [toolUseBlock('t2', 'Edit', okResult())]),
        ]);
        const { container } = render(<TurnContent turn={turn} />);
        // 无阻断元素 → 两段工具合并为一段
        expect(screen.getAllByTestId('tool-run-block')).toHaveLength(1);
        // 首贡献消息 uuid 落在 wrapper；后续贡献消息为 sr-only 锚点；载体消息无锚点
        expect(container.querySelector('[data-message-uuid="a1"]')).not.toBeNull();
        expect(container.querySelector('[data-message-uuid="a2"]')).not.toBeNull();
        expect(container.querySelector('[data-message-uuid="r1"]')).toBeNull();
    });

    it('text 阻断合并：thinking/text 隔开的工具各成 ToolRunBlock', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [toolUseBlock('t1', 'Read', okResult())]),
            assistantWithBlocks('a2', 3, [
                { type: 'text', text: '中间说明' },
                toolUseBlock('t2', 'Bash', okResult()),
            ]),
        ]);
        render(<TurnContent turn={turn} />);
        expect(screen.getAllByTestId('tool-run-block')).toHaveLength(2);
        expect(screen.getByText('中间说明')).toBeInTheDocument();
    });

    it('blocks 段块级渲染：server_tool_use 与孤儿 tool_result 原位保留', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [
                { type: 'server_tool_use', toolUseId: 'st1', toolName: 'web_search' },
                { type: 'tool_result', toolUseId: 'orphan', content: '孤儿结果', isError: false },
            ]),
        ]);
        render(<TurnContent turn={turn} />);
        expect(screen.getByText('Server tool: web_search')).toBeInTheDocument();
        // 孤儿 tool_result 合成 ToolCallBlock（toolName 占位 'Tool'）
        expect(screen.getByText('Tool')).toBeInTheDocument();
        // 不产生聚合块
        expect(screen.queryByTestId('tool-run-block')).not.toBeInTheDocument();
    });

    it('streaming 消息豁免聚合：实时工具挂流式消息下，已提交工具仍聚合', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [toolUseBlock('t1', 'Read', okResult())]),
            assistantWithBlocks('a2', 3, [{ type: 'text', text: '' }]),
        ]);
        const live = new Map<string, ToolCallState>([
            ['live-1', { toolName: 'Bash', input: {}, status: 'running', startTime: 0 }],
        ]);
        render(
            <TurnContent
                turn={turn}
                streamingMessageId="a2"
                streamingContent="正在写"
                activeToolCalls={live}
            />,
        );
        // 已提交工具聚合成唯一 ToolRunBlock（流式消息的块不参与）
        expect(screen.getAllByTestId('tool-run-block')).toHaveLength(1);
        // 流式消息渲染流式文本 + 实时工具（与平铺路径一致挂在流式消息下）
        expect(screen.getByText(/正在写/)).toBeInTheDocument();
        expect(screen.getByText('Bash')).toBeInTheDocument();
        // 实时工具不进入聚合块
        expect(within(screen.getByTestId('tool-run-block')).queryByText('Bash')).toBeNull();
    });
});

// ==================== 消息级操作行（assistant blocks 按 messageId 分组） ====================

describe('TurnContent 消息级操作行', () => {
    beforeEach(() => {
        Object.defineProperty(navigator, 'clipboard', {
            value: { writeText: vi.fn().mockResolvedValue(undefined) },
            configurable: true,
        });
    });

    it('工具调用隔开的两条 assistant 消息：各自 blocks 组渲染消息级操作行（复制 + 时间戳）', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [
                { type: 'text', text: '先查一下' },
                toolUseBlock('t1', 'Read', okResult()),
            ]),
            assistantWithBlocks('a2', 3, [{ type: 'text', text: '最终答复' }]),
        ]);
        render(<TurnContent turn={turn} />);
        // 每条 assistant 消息的 blocks wrapper 内各有复制按钮与时间戳
        const w1 = screen.getByText('先查一下').closest('[data-message-uuid="a1"]') as HTMLElement;
        const w2 = screen.getByText('最终答复').closest('[data-message-uuid="a2"]') as HTMLElement;
        expect(w1).not.toBeNull();
        expect(w2).not.toBeNull();
        expect(within(w1).getByTestId('message-copy-button')).toBeInTheDocument();
        expect(within(w1).getByTestId('message-timestamp')).toBeInTheDocument();
        expect(within(w2).getByTestId('message-copy-button')).toBeInTheDocument();
        expect(within(w2).getByTestId('message-timestamp')).toBeInTheDocument();
        // user 指令块保持原样：不挂 assistant 操作行
        const wUser = screen.getByText('问题').closest('[data-message-uuid="u1"]') as HTMLElement;
        expect(within(wUser).queryByTestId('assistant-message-actions')).toBeNull();
    });

    it('同一条消息被工具段切开的多个 blocks 段只渲染一次操作行（挂在末段）', () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [
                { type: 'text', text: '前半段' },
                toolUseBlock('t1', 'Read', okResult()),
                { type: 'text', text: '后半段' },
            ]),
        ]);
        render(<TurnContent turn={turn} />);
        // 同一消息的两段 blocks 共享一条操作行，且只挂末段
        const firstSeg = screen.getByText('前半段').closest('[data-message-uuid="a1"]') as HTMLElement;
        const lastSeg = screen.getByText('后半段').closest('[data-message-uuid="a1"]') as HTMLElement;
        const actions = screen.getAllByTestId('assistant-message-actions');
        expect(actions).toHaveLength(1);
        expect(lastSeg.contains(actions[0])).toBe(true);
        expect(firstSeg.contains(actions[0])).toBe(false);
    });

    it('per-message 复制按钮复制该消息文本（而非整轮）', async () => {
        const [turn] = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [
                { type: 'text', text: '先查一下' },
                toolUseBlock('t1', 'Read', okResult()),
            ]),
            assistantWithBlocks('a2', 3, [{ type: 'text', text: '最终答复' }]),
        ]);
        render(<TurnContent turn={turn} />);
        const w2 = screen.getByText('最终答复').closest('[data-message-uuid="a2"]') as HTMLElement;
        fireEvent.click(within(w2).getByTestId('message-copy-button'));
        await act(async () => {});
        expect(navigator.clipboard.writeText).toHaveBeenCalledWith('最终答复');
    });
});
