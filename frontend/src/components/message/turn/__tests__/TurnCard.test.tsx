/**
 * TurnCard 组件测试
 * 覆盖：折叠/展开切换调用 setTurnExpanded、状态点语义（含工具级 error/取消）、
 * 复制本轮、preamble 轮平铺渲染（无卡片头）、折叠时内容不常驻 DOM、
 * meta 行工具统计与文件变更、completed 轮结论预览。
 */

import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ContentBlock, Message, ToolResult } from '@/types';
import { buildTurns, type Turn } from '@/store/selectors/turnProjection';
import { useTurnViewStore } from '@/store/turnViewStore';
import { useNotificationStore } from '@/store/notificationStore';
import TurnCard from '../TurnCard';

vi.mock('@/hooks/useTtsAvailability', () => ({
    useTtsAvailability: () => false,
}));

// ==================== 消息工厂 ====================

function userText(uuid: string, timestamp: number, text = `text-${uuid}`): Message {
    return { type: 'user', uuid, timestamp, content: [{ type: 'text', text }] } as Message;
}

function assistantMsg(uuid: string, timestamp: number): Message {
    return {
        type: 'assistant', uuid, timestamp,
        content: [{ type: 'text', text: `reply-${uuid}` }],
        stopReason: 'end_turn',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
    } as Message;
}

function systemMsg(uuid: string, timestamp: number, subtype?: string): Message {
    return { type: 'system', uuid, timestamp, content: `sys-${uuid}`, subtype } as Message;
}

function singleTurn(messages: Message[]): Turn {
    const turns = buildTurns(messages);
    expect(turns).toHaveLength(1);
    return turns[0];
}

beforeEach(() => {
    localStorage.clear();
    useTurnViewStore.setState({ density: 'balanced', expandOverrides: {} });
    useNotificationStore.setState({ notifications: [] });
});

describe('TurnCard 折叠/展开切换', () => {
    it('点击 header 调用 setTurnExpanded 写入反向 override', () => {
        const turn = singleTurn([userText('u1', 1000, '帮我改个 bug'), assistantMsg('a1', 2000)]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        fireEvent.click(screen.getByRole('button', { name: /第 1 轮/ }));
        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toEqual({ 0: true });
    });

    it('展开态点击 header → override 写 false', () => {
        const turn = singleTurn([userText('u1', 1000), assistantMsg('a1', 2000)]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={3}
                sessionId="sess-1"
                expanded
                isRunActive={false}
            />,
        );
        fireEvent.click(screen.getByRole('button', { name: /第 3 轮/ }));
        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toEqual({ 0: false });
    });

    it('切换后回调 onAfterToggle（滚动对齐钩子）', () => {
        const onAfterToggle = vi.fn();
        const turn = singleTurn([userText('u1', 1000), assistantMsg('a1', 2000)]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
                onAfterToggle={onAfterToggle}
            />,
        );
        fireEvent.click(screen.getByRole('button', { name: /第 1 轮/ }));
        expect(onAfterToggle).toHaveBeenCalledWith(0, true);
    });

    it('折叠时轮内内容不挂载；展开时渲染轮内消息', () => {
        const turn = singleTurn([userText('u1', 1000, '折叠的问题'), assistantMsg('a1', 2000)]);
        const { rerender } = render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        expect(screen.queryByText('reply-a1')).not.toBeInTheDocument();
        rerender(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded
                isRunActive={false}
            />,
        );
        expect(screen.getByText('reply-a1')).toBeInTheDocument();
        // 深链锚点 wrapper
        expect(document.querySelector('[data-message-uuid="a1"]')).not.toBeNull();
    });
});

describe('TurnCard header 呈现', () => {
    it('展示第 N 轮、指令首行粗体截断与格式化耗时', () => {
        const turn = singleTurn([
            userText('u1', 0, '第一行标题\n第二行细节'),
            assistantMsg('a1', 154_000),
        ]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={2}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        expect(screen.getByText('第 2 轮')).toBeInTheDocument();
        expect(screen.getByText('第一行标题')).toBeInTheDocument();
        expect(screen.queryByText('第二行细节')).not.toBeInTheDocument();
        // header 右侧耗时 + 折叠态 meta 行（耗时 + 消息条数）
        expect(screen.getAllByText('2m34s').length).toBeGreaterThanOrEqual(1);
        expect(screen.getByText('2 条消息')).toBeInTheDocument();
        // meta 行含后续波次的语义化插槽
        expect(screen.getByTestId('turn-card-tool-stats')).toBeInTheDocument();
    });

    it('运行中 active 轮 → accent 呼吸点', () => {
        const turn = singleTurn([userText('u1', 1000), assistantMsg('a1', 2000)]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded
                isRunActive
            />,
        );
        const dot = screen.getByTestId('turn-status-dot-0');
        expect(dot.className).toContain('bg-accent2');
        expect(dot.className).toContain('animate-accent-pulse');
    });

    it('轮内 error → 红点；interrupt → 琥珀点；正常完成 → 翠绿点', () => {
        const errorTurn = singleTurn([userText('u1', 1), systemMsg('s1', 2, 'error')]);
        const { unmount } = render(
            <TurnCard turn={errorTurn} turnNumber={1} sessionId="s" expanded={false} isRunActive={false} />,
        );
        expect(screen.getByTestId('turn-status-dot-0').className).toContain('bg-err');
        unmount();

        const interruptTurn = singleTurn([userText('u1', 1), systemMsg('s1', 2, 'interrupt')]);
        const { unmount: unmount2 } = render(
            <TurnCard turn={interruptTurn} turnNumber={1} sessionId="s" expanded={false} isRunActive={false} />,
        );
        expect(screen.getByTestId('turn-status-dot-0').className).toContain('bg-warn');
        unmount2();

        const okTurn = singleTurn([userText('u1', 1), assistantMsg('a1', 2)]);
        render(
            <TurnCard turn={okTurn} turnNumber={1} sessionId="s" expanded={false} isRunActive={false} />,
        );
        expect(screen.getByTestId('turn-status-dot-0').className).toContain('bg-ok');
    });
});

describe('TurnCard 复制本轮', () => {
    beforeEach(() => {
        Object.defineProperty(navigator, 'clipboard', {
            value: { writeText: vi.fn().mockResolvedValue(undefined) },
            configurable: true,
        });
    });

    it('点击复制写入剪贴板（仅助手产出，不含用户指令）并发出成功 toast', async () => {
        const turn = singleTurn([userText('u1', 1, '问题'), assistantMsg('a1', 2)]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        fireEvent.click(screen.getByTestId('turn-copy-button-0'));
        await act(async () => {});
        // 复制本轮只复制 assistant 文本，排除 user 指令
        expect(navigator.clipboard.writeText).toHaveBeenCalledWith('reply-a1');
        const copiedText = vi.mocked(navigator.clipboard.writeText).mock.calls[0][0] as string;
        expect(copiedText).not.toContain('问题');
        expect(useNotificationStore.getState().notifications).toHaveLength(1);
        expect(useNotificationStore.getState().notifications[0].level).toBe('success');
    });

    it('点击复制按钮不触发卡片展开切换', async () => {
        const turn = singleTurn([userText('u1', 1), assistantMsg('a1', 2)]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        fireEvent.click(screen.getByTestId('turn-copy-button-0'));
        await act(async () => {});
        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toBeUndefined();
    });
});

describe('TurnCard preamble 轮', () => {
    it('不渲染卡片头，消息平铺展示', () => {
        const turns = buildTurns([systemMsg('s0', 1), userText('u1', 2)]);
        expect(turns[0].instruction).toBeNull();
        render(
            <TurnCard
                turn={turns[0]}
                turnNumber={null}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        expect(screen.queryByText(/第 \d+ 轮/)).not.toBeInTheDocument();
        expect(screen.getByText('sys-s0')).toBeInTheDocument();
        expect(document.querySelector('[data-message-uuid="s0"]')).not.toBeNull();
    });
});

// ==================== meta 行工具统计与结论预览 ====================

const okResult = (content = 'done'): ToolResult => ({ content, isError: false });

function toolUseBlock(
    id: string,
    toolName: string,
    input: Record<string, unknown> = {},
    result?: ToolResult,
): ContentBlock {
    return { type: 'tool_use', toolUseId: id, toolName, input, ...(result ? { result } : {}) };
}

function assistantWithBlocks(uuid: string, timestamp: number, content: ContentBlock[]): Message {
    return {
        type: 'assistant', uuid, timestamp, content,
        stopReason: 'end_turn',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
    } as Message;
}

describe('TurnCard meta 行工具统计', () => {
    it('折叠态展示工具统计（N 次工具 + top 工具名计数）与文件变更数', () => {
        const turn = singleTurn([
            userText('u1', 1000, '问题'),
            assistantWithBlocks('a1', 2000, [
                toolUseBlock('t1', 'Read', { file_path: '/a.ts' }, okResult()),
                toolUseBlock('t2', 'Read', { file_path: '/b.ts' }, okResult()),
                toolUseBlock('t3', 'Edit', { file_path: '/a.ts' }, okResult()),
            ]),
        ]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        const slot = screen.getByTestId('turn-card-tool-stats');
        expect(slot).toHaveTextContent('3 次工具');
        expect(slot).toHaveTextContent('Read×2 Edit×1');
        // Edit 的 file_path 去重计 1（Read 不计文件变更）
        expect(screen.getByText('1 个文件变更')).toBeInTheDocument();
    });

    it('无工具轮：统计槽位存在但为空，不展示文件变更', () => {
        const turn = singleTurn([userText('u1', 1000), assistantMsg('a1', 2000)]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        expect(screen.getByTestId('turn-card-tool-stats')).toBeEmptyDOMElement();
        expect(screen.queryByText(/次工具/)).not.toBeInTheDocument();
        expect(screen.queryByText(/个文件变更/)).not.toBeInTheDocument();
    });
});

describe('TurnCard 结论预览', () => {
    it('completed 轮折叠态追加结论预览（最后一个 assistant text 块首行）', () => {
        // 两轮会话：第一轮为 completed
        const turns = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [
                { type: 'text', text: '过程描述' },
                { type: 'tool_use', toolUseId: 't1', toolName: 'Read', input: {}, result: okResult() },
            ]),
            assistantWithBlocks('a2', 3, [{ type: 'text', text: '最终结论在此\n第二行忽略' }]),
            userText('u2', 4, '下一轮'),
        ]);
        expect(turns[0].status).toBe('completed');
        render(
            <TurnCard
                turn={turns[0]}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        const preview = screen.getByTestId('turn-card-conclusion-0');
        expect(preview).toHaveTextContent('最终结论在此');
        expect(preview.textContent).not.toContain('第二行忽略');
        expect(preview.className).toContain('text-t3');
    });

    it('active 轮与展开态不展示结论预览', () => {
        // 单轮即 active
        const activeTurn = singleTurn([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [{ type: 'text', text: '进行中的回复' }]),
        ]);
        expect(activeTurn.status).toBe('active');
        const { unmount } = render(
            <TurnCard
                turn={activeTurn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        expect(screen.queryByTestId('turn-card-conclusion-0')).not.toBeInTheDocument();
        unmount();

        // completed 但展开 → 不展示
        const turns = buildTurns([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [{ type: 'text', text: '最终结论在此' }]),
            userText('u2', 3, '下一轮'),
        ]);
        render(
            <TurnCard
                turn={turns[0]}
                turnNumber={1}
                sessionId="sess-1"
                expanded
                isRunActive={false}
            />,
        );
        expect(screen.queryByTestId('turn-card-conclusion-0')).not.toBeInTheDocument();
    });
});

describe('TurnCard 状态点工具级语义', () => {
    it('轮内工具 error（无 error 系统消息）→ 红点', () => {
        const turn = singleTurn([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [
                toolUseBlock('t1', 'Bash', {}, { content: 'boom', isError: true }),
            ]),
        ]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        expect(screen.getByTestId('turn-status-dot-0').className).toContain('bg-err');
    });

    it('已取消工具 → 琥珀点（不计入失败）', () => {
        const turn = singleTurn([
            userText('u1', 1, '问题'),
            assistantWithBlocks('a1', 2, [
                toolUseBlock('t1', 'Bash', {}, {
                    content: 'aborted',
                    isError: true,
                    metadata: { executionStatus: 'cancelled' },
                }),
            ]),
        ]);
        render(
            <TurnCard
                turn={turn}
                turnNumber={1}
                sessionId="sess-1"
                expanded={false}
                isRunActive={false}
            />,
        );
        const dot = screen.getByTestId('turn-status-dot-0');
        expect(dot.className).toContain('bg-warn');
        expect(dot.className).not.toContain('bg-err');
    });
});
