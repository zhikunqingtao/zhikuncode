/**
 * TurnCard 组件测试（三层模型：完整 query ｜ 过程区 ｜ 完整回复）
 * 覆盖：简洁档问题与回复默认折叠，其他档正文可见、tail 常驻、
 * compact 聚合条（N个任务 · M步 · 耗时 · 已收起/已展开、点击写轮级 override、
 * 延迟卸载）、聚合条状态点语义（运行/error/interrupt/工具级 error/取消）、
 * balanced 任务分节条（独立展开写分节 key）、compact 任务清单点击跳转升档、
 * detailed 无任务数据全保真回退、preamble 轮（无 instruction）、
 * answer 命中 streamingMessageId 的流式透传。
 */

import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ContentBlock, Message, ToolResult } from '@/types';
import { buildTurns, type Turn } from '@/store/selectors/turnProjection';
import { useTurnViewStore, type TurnDensity } from '@/store/turnViewStore';
import TurnCard from '../TurnCard';

vi.mock('@/hooks/useTtsAvailability', () => ({
    useTtsAvailability: () => false,
}));

// ==================== 消息工厂 ====================

function userText(uuid: string, timestamp: number, text = `text-${uuid}`): Message {
    return { type: 'user', uuid, timestamp, content: [{ type: 'text', text }] } as Message;
}

function steeringText(uuid: string, timestamp: number, text = `steering-${uuid}`): Message {
    return {
        type: 'user', uuid, timestamp,
        content: [{ type: 'text', text }],
        meta: { steering: true },
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

function systemMsg(uuid: string, timestamp: number, subtype?: string): Message {
    return { type: 'system', uuid, timestamp, content: `sys-${uuid}`, subtype } as Message;
}

function taskBoundary(uuid: string, timestamp: number, title: string, seq: number): Message {
    return {
        type: 'system', uuid, timestamp, content: '', subtype: 'task_boundary',
        metadata: { task_id: `task-${seq}`, title, seq },
    } as Message;
}

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

function singleTurn(messages: Message[]): Turn {
    const turns = buildTurns(messages);
    expect(turns).toHaveLength(1);
    return turns[0];
}

function renderCard(
    turn: Turn,
    opts?: {
        density?: TurnDensity;
        sessionId?: string | null;
        isRunActive?: boolean;
        streamingMessageId?: string | null;
        streamingContent?: string;
        onAfterToggle?: (turnIndex: number, expanded: boolean) => void;
    },
) {
    useTurnViewStore.setState({
        density: opts?.density ?? 'compact',
        expandOverrides: {},
    });
    return render(
        <TurnCard
            turn={turn}
            sessionId={opts?.sessionId === undefined ? 'sess-1' : opts.sessionId}
            isRunActive={opts?.isRunActive ?? false}
            streamingMessageId={opts?.streamingMessageId}
            streamingContent={opts?.streamingContent}
            onAfterToggle={opts?.onAfterToggle}
        />,
    );
}

beforeEach(() => {
    localStorage.clear();
    useTurnViewStore.setState({ density: 'compact', expandOverrides: {} });
});

// ==================== 三层常驻可见 ====================

describe('TurnCard 三层结构', () => {
    it('简洁档问题、补充指令、过程和回复独立折叠；切回简洁恢复默认', () => {
        const turn = singleTurn([
            userText('u1', 1000, '原始问题'),
            assistantWithBlocks('a1', 1500, [toolUseBlock('t1', 'Read', {}, okResult())]),
            steeringText('u2', 1600, '补充指令'),
            assistantMsg('a2', 2000),
        ]);
        renderCard(turn);
        const queries = screen.getAllByRole('button', { name: /用户问题/ });
        for (const button of queries) expect(button).toHaveAttribute('aria-expanded', 'false');
        expect(screen.queryByTestId('message-timestamp')).not.toBeInTheDocument();
        expect(screen.queryByText('reply-a2')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: /最终回复/ })).toHaveAttribute('aria-expanded', 'false');
        fireEvent.click(queries[0]);
        expect(screen.getByTestId('message-timestamp')).toBeVisible();
        expect(queries[1]).toHaveAttribute('aria-expanded', 'false');
        expect(screen.queryByText('reply-a2')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /最终回复/ }));
        expect(screen.getByText('reply-a2')).toBeVisible();
        expect(screen.getByRole('button', { name: /过程区/ })).toHaveAttribute('aria-expanded', 'false');
        act(() => useTurnViewStore.getState().setDensity('balanced', 'sess-1'));
        expect(screen.queryByRole('button', { name: /用户问题|最终回复/ })).not.toBeInTheDocument();
        expect(screen.getByText('reply-a2')).toBeVisible();
        act(() => useTurnViewStore.getState().setDensity('compact', 'sess-1'));
        expect(screen.queryByText('reply-a2')).not.toBeInTheDocument();
        for (const button of screen.getAllByRole('button', { name: /用户问题/ })) {
            expect(button).toHaveAttribute('aria-expanded', 'false');
        }
    });

    it.each(['balanced', 'detailed'] as const)('%s 档 query 与 answer 完整可见；无过程消息不渲染过程区', density => {
        const turn = singleTurn([userText('u1', 1000, '帮我改个 bug'), assistantMsg('a1', 2000)]);
        renderCard(turn, { density });
        expect(screen.getByText('帮我改个 bug')).toBeInTheDocument();
        expect(screen.getByText('reply-a1')).toBeInTheDocument();
        expect(screen.queryByTestId('turn-process-0')).not.toBeInTheDocument();
        // 深链锚点 wrapper
        expect(document.querySelector('[data-message-uuid="u1"]')).not.toBeNull();
        expect(document.querySelector('[data-message-uuid="a1"]')).not.toBeNull();
    });

    it('tail 层结果类系统消息（error）常驻可见，不受过程区折叠影响', () => {
        const turn = singleTurn([
            userText('u1', 1000),
            assistantWithBlocks('a1', 1500, [toolUseBlock('t1', 'Read', {}, okResult())]),
            systemMsg('s1', 2000, 'error'),
        ]);
        renderCard(turn);
        // 过程区折叠态（compact 默认），tail 仍可见
        expect(screen.getByTestId('turn-process-0')).toBeInTheDocument();
        expect(screen.getByText('sys-s1')).toBeInTheDocument();
        expect(document.querySelector('[data-message-uuid="s1"]')).not.toBeNull();
    });

    it('平衡档 steering 用户消息与 query 同层，完整可见', () => {
        const turn = singleTurn([
            userText('u1', 1000, '原始指令'),
            assistantWithBlocks('a1', 1500, [toolUseBlock('t1', 'Read', {}, okResult())]),
            steeringText('u2', 1600, '补充：顺便看下测试'),
            assistantMsg('a2', 2000),
        ]);
        renderCard(turn, { density: 'balanced' });
        expect(screen.getByText('原始指令')).toBeInTheDocument();
        expect(screen.getByText('补充：顺便看下测试')).toBeInTheDocument();
    });
});

// ==================== compact 聚合条 ====================

describe('TurnCard compact 聚合条', () => {
    it('展示「M 步 · 耗时 · 已收起」，折叠时过程内容不常驻 DOM', () => {
        const turn = singleTurn([
            userText('u1', 0, '问题'),
            assistantWithBlocks('a1', 154_000, [
                toolUseBlock('t1', 'Read', {}, okResult()),
                toolUseBlock('t2', 'Edit', {}, okResult()),
            ]),
            assistantMsg('a2', 154_100),
        ]);
        renderCard(turn);
        const bar = screen.getByRole('button', { name: /过程区/ });
        expect(bar).toHaveAttribute('aria-expanded', 'false');
        expect(bar).toHaveTextContent('2 步');
        expect(bar).toHaveTextContent('2分34秒');
        expect(bar).toHaveTextContent('执行');
        expect(bar).toHaveTextContent('查看执行过程');
        // 折叠时工具聚合段不挂载
        expect(document.querySelector('[data-message-uuid="a1"]')).toBeNull();
    });

    it('点击聚合条 → setSectionExpanded 写轮级 key override 并回调 onAfterToggle', () => {
        const onAfterToggle = vi.fn();
        const turn = singleTurn([
            userText('u1', 1000),
            assistantWithBlocks('a1', 1500, [toolUseBlock('t1', 'Read', {}, okResult())]),
            assistantMsg('a2', 2000),
        ]);
        renderCard(turn, { onAfterToggle });
        fireEvent.click(screen.getByRole('button', { name: /过程区/ }));
        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toEqual({ 0: true });
        expect(onAfterToggle).toHaveBeenCalledWith(0, true);
        expect(screen.getByRole('button', { name: /过程区/ })).toHaveAttribute('aria-expanded', 'true');

        // 再点 → 写 false
        fireEvent.click(screen.getByRole('button', { name: /过程区/ }));
        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toEqual({ 0: false });
        expect(onAfterToggle).toHaveBeenLastCalledWith(0, false);
    });

    it('无 sessionId 时点击不写 override，仍回调 onAfterToggle', () => {
        const onAfterToggle = vi.fn();
        const turn = singleTurn([
            userText('u1', 1000),
            assistantWithBlocks('a1', 1500, [toolUseBlock('t1', 'Read', {}, okResult())]),
            assistantMsg('a2', 2000),
        ]);
        renderCard(turn, { sessionId: null, onAfterToggle });
        fireEvent.click(screen.getByRole('button', { name: /过程区/ }));
        expect(useTurnViewStore.getState().expandOverrides).toEqual({});
        expect(onAfterToggle).toHaveBeenCalledWith(0, true);
    });
});

// ==================== 聚合条状态点语义 ====================

describe('TurnCard 过程区状态点', () => {
    const dotOf = (turn: Turn, isRunActive = false) => {
        renderCard(turn, { isRunActive });
        return screen.getByTestId('turn-process-dot-0');
    };

    it('运行中 active 轮 → accent 呼吸点', () => {
        const turn = singleTurn([
            userText('u1', 1000),
            assistantWithBlocks('a1', 1500, [toolUseBlock('t1', 'Read', {}, okResult())]),
        ]);
        const dot = dotOf(turn, true);
        expect(dot.className).toContain('bg-accent2');
        expect(dot.className).toContain('animate-accent-pulse');
    });

    it('轮内 error → 红点；interrupt → 琥珀点；正常完成 → 翠绿点', () => {
        const errorTurn = singleTurn([
            userText('u1', 1),
            assistantWithBlocks('a1', 2, [toolUseBlock('t1', 'Read', {}, okResult())]),
            systemMsg('s1', 3, 'error'),
        ]);
        const { unmount } = renderCard(errorTurn);
        expect(screen.getByTestId('turn-process-dot-0').className).toContain('bg-err');
        unmount();

        const interruptTurn = singleTurn([
            userText('u1', 1),
            assistantWithBlocks('a1', 2, [toolUseBlock('t1', 'Read', {}, okResult())]),
            systemMsg('s1', 3, 'interrupt'),
        ]);
        const { unmount: unmount2 } = renderCard(interruptTurn);
        expect(screen.getByTestId('turn-process-dot-0').className).toContain('bg-warn');
        unmount2();

        const okTurn = singleTurn([
            userText('u1', 1),
            assistantWithBlocks('a1', 2, [toolUseBlock('t1', 'Read', {}, okResult())]),
            assistantMsg('a2', 3),
        ]);
        renderCard(okTurn);
        expect(screen.getByTestId('turn-process-dot-0').className).toContain('bg-ok');
    });

    it('轮内工具 error（无 error 系统消息）→ 红点；已取消工具 → 琥珀点（不计入失败）', () => {
        const toolErrorTurn = singleTurn([
            userText('u1', 1),
            assistantWithBlocks('a1', 2, [
                toolUseBlock('t1', 'Bash', {}, { content: 'boom', isError: true }),
            ]),
        ]);
        const { unmount } = renderCard(toolErrorTurn);
        expect(screen.getByTestId('turn-process-dot-0').className).toContain('bg-err');
        unmount();

        const cancelledTurn = singleTurn([
            userText('u1', 1),
            assistantWithBlocks('a1', 2, [
                toolUseBlock('t1', 'Bash', {}, {
                    content: 'aborted',
                    isError: true,
                    metadata: { executionStatus: 'cancelled' },
                }),
            ]),
        ]);
        renderCard(cancelledTurn);
        const dot = screen.getByTestId('turn-process-dot-0');
        expect(dot.className).toContain('bg-warn');
        expect(dot.className).not.toContain('bg-err');
    });
});

// ==================== balanced 任务分节 ====================

const boundaryTurn = () => singleTurn([
    userText('u1', 1000, '问题'),
    taskBoundary('b1', 1100, '修复登录', 1),
    assistantWithBlocks('a1', 1200, [toolUseBlock('t1', 'Read', {}, okResult())]),
    taskBoundary('b2', 1300, '补充测试', 2),
    assistantWithBlocks('a2', 1400, [toolUseBlock('t2', 'Edit', {}, okResult())]),
    assistantMsg('a3', 1500),
]);

describe('TurnCard balanced 任务分节', () => {
    it('渲染任务分节条列表，默认折叠；点击分节条写分节 key override', () => {
        renderCard(boundaryTurn(), { density: 'balanced' });
        expect(screen.getByTestId('turn-task-sections-0')).toBeInTheDocument();
        expect(screen.queryByTestId('turn-process-0')).not.toBeInTheDocument();

        const first = screen.getByTestId('task-section-0-0');
        const second = screen.getByTestId('task-section-0-1');
        expect(first).toHaveTextContent('修复登录');
        expect(second).toHaveTextContent('补充测试');

        const firstBar = first.querySelector('button[data-expand-key="0:0"]');
        expect(firstBar).not.toBeNull();
        expect(firstBar).toHaveAttribute('aria-expanded', 'false');
        fireEvent.click(firstBar as Element);
        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toEqual({ '0:0': true });
        // 展开后段内为工具摘要行（不再下钻）
        expect(screen.getByTestId('tool-summary-rows')).toBeInTheDocument();
        expect(screen.getByTestId('tool-summary-row-t1')).toHaveTextContent('Read');
    });

    it('无任务数据的轮在 balanced 下退化为 compact 同款聚合条', () => {
        const turn = singleTurn([
            userText('u1', 1000),
            assistantWithBlocks('a1', 1500, [toolUseBlock('t1', 'Read', {}, okResult())]),
            assistantMsg('a2', 2000),
        ]);
        renderCard(turn, { density: 'balanced' });
        expect(screen.getByTestId('turn-process-0')).toBeInTheDocument();
        expect(screen.queryByTestId('turn-task-sections-0')).not.toBeInTheDocument();
    });

    it('prep「准备」段仅 detailed 展示', () => {
        const turn = singleTurn([
            userText('u1', 1000),
            assistantMsg('a0', 1050, '先了解一下上下文'),
            taskBoundary('b1', 1100, '修复登录', 1),
            assistantWithBlocks('a1', 1200, [toolUseBlock('t1', 'Read', {}, okResult())]),
            assistantMsg('a2', 1300),
        ]);
        const { unmount } = renderCard(turn, { density: 'balanced' });
        expect(screen.queryByTestId('task-section-0-prep')).not.toBeInTheDocument();
        unmount();

        renderCard(turn, { density: 'detailed' });
        expect(screen.getByTestId('task-section-0-prep')).toBeInTheDocument();
        // detailed 默认全展开：prep 段文本完整保真可见
        expect(screen.getByText('先了解一下上下文')).toBeInTheDocument();
    });
});

// ==================== compact 任务清单跳转 ====================

describe('TurnCard compact 任务清单', () => {
    it('聚合条显示「N 个任务」，展开为只读任务标题清单', () => {
        renderCard(boundaryTurn(), { density: 'compact' });
        const bar = screen.getByRole('button', { name: /过程区/ });
        expect(bar).toHaveTextContent('2 个任务');
        fireEvent.click(bar);
        const list = screen.getByTestId('turn-task-list-0');
        expect(list).toHaveTextContent('修复登录');
        expect(list).toHaveTextContent('补充测试');
    });

    it('点击任务项 → setDensity(\'balanced\') 升档并写目标分节 override', () => {
        renderCard(boundaryTurn(), { density: 'compact' });
        fireEvent.click(screen.getByRole('button', { name: /过程区/ }));
        fireEvent.click(screen.getByTestId('turn-task-item-0-1'));
        const state = useTurnViewStore.getState();
        expect(state.density).toBe('balanced');
        // setDensity 先清本会话 overrides，再写目标分节 key
        expect(state.expandOverrides['sess-1']).toEqual({ '0:1': true });
    });
});

// ==================== detailed 回退与 preamble ====================

describe('TurnCard detailed / preamble', () => {
    it('detailed 无任务数据 → 全保真渲染过程消息（无聚合条，无需展开）', () => {
        const turn = singleTurn([
            userText('u1', 1000),
            assistantWithBlocks('a1', 1500, [
                { type: 'text', text: '过程中的思考' },
                toolUseBlock('t1', 'Read', {}, okResult()),
            ]),
            assistantMsg('a2', 2000),
        ]);
        renderCard(turn, { density: 'detailed' });
        expect(screen.getByTestId('turn-process-0')).toBeInTheDocument();
        expect(screen.getByTestId('detailed-section-content')).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: /过程区/ })).not.toBeInTheDocument();
        expect(screen.getByText('过程中的思考')).toBeInTheDocument();
    });

    it('preamble 轮（无 instruction）走同一三层结构：query 层为空，过程消息入聚合条', () => {
        const turns = buildTurns([systemMsg('s0', 1), userText('u1', 2)]);
        expect(turns[0].instruction).toBeNull();
        renderCard(turns[0]);
        expect(screen.queryByText('text-u1')).not.toBeInTheDocument();
        const bar = screen.getByRole('button', { name: /过程区/ });
        // 折叠时 preamble 过程消息不常驻；展开后可见
        expect(screen.queryByText('sys-s0')).not.toBeInTheDocument();
        fireEvent.click(bar);
        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toEqual({ 0: true });
        expect(screen.getByText('sys-s0')).toBeInTheDocument();
        expect(document.querySelector('[data-message-uuid="s0"]')).not.toBeNull();
    });
});

// ==================== 流式透传 ====================

describe('TurnCard 流式透传', () => {
    it('answer 命中 streamingMessageId → 按流式渲染（实时内容可见）', () => {
        const turn = singleTurn([userText('u1', 1, '问题'), assistantMsg('a1', 2, '')]);
        renderCard(turn, {
            isRunActive: true,
            streamingMessageId: 'a1',
            streamingContent: '正在生成的流式内容',
            density: 'balanced',
        });
        expect(screen.getByText(/正在生成的流式内容/)).toBeInTheDocument();
    });
});


describe('审查回归：命令结果与任务终态', () => {
    it.each(['compact', 'balanced', 'detailed'] as const)('%s 档保留自动压缩与命令执行反馈，不依赖过程展开', density => {
        const turn = boundaryTurn();
        turn.messages.push(systemMsg('compact-notice', 1600, 'compact_boundary'),
            systemMsg('command-notice', 1700, 'command'));
        renderCard(turn, { density });
        expect(screen.getByText('sys-compact-notice')).toBeVisible();
        expect(screen.getByText('sys-command-notice')).toBeVisible();
    });

    it.each(['compact', 'balanced', 'detailed'] as const)('%s 档命令面板和文本结果常驻可见', density => {
        const turn = boundaryTurn();
        turn.messages.push({
            type: 'system', uuid: 'commit-preview', timestamp: 1600, content: '',
            subtype: 'jsx_result', metadata: {
                action: 'gitCommitPreview', status: 'M login.ts', stagedDiff: '',
                detailedDiff: '', changedFiles: ['login.ts'], fileCount: 1,
            },
        } as Message, {
            type: 'system', uuid: 'command-result', timestamp: 1700,
            subtype: 'command_result', content: '/status: REVIEW_COMMAND_RESULT',
        } as Message);
        renderCard(turn, { density });
        expect(screen.getByText('Git 提交')).toBeVisible();
        expect(screen.getByPlaceholderText('输入 commit message（或点击 AI 生成）...')).toBeVisible();
        expect(screen.getByText('/status: REVIEW_COMMAND_RESULT')).toBeVisible();
    });

    it.each([
        ['interrupt', '被中断'], ['provider_error', '失败'], ['error', '失败'],
    ])('%s 只影响最后任务，三档状态一致', (subtype, label) => {
        const turn = boundaryTurn();
        turn.messages = turn.messages.filter(message => message.uuid !== 'a3');
        turn.messages.push(systemMsg('terminal', 1600, subtype));
        // 后续已开始新一轮时，历史轮也保留失败/中断终态。
        turn.status = 'completed';
        for (const density of ['compact', 'balanced', 'detailed'] as const) {
            const { unmount } = renderCard(turn, { density, isRunActive: true });
            if (density === 'compact') {
                expect(screen.getByTestId('turn-process-dot-0')).toHaveAttribute('aria-label', label);
                fireEvent.click(screen.getByRole('button', { name: /过程区/ }));
                expect(within(screen.getByTestId('turn-task-item-0-0')).getByRole('img', { name: '已完成' })).toBeVisible();
                expect(within(screen.getByTestId('turn-task-item-0-1')).getByRole('img', { name: label })).toBeVisible();
            } else {
                expect(within(screen.getByTestId('task-section-0-0')).getByRole('img', { name: '已完成' })).toBeVisible();
                expect(within(screen.getByTestId('task-section-0-1')).getByRole('img', { name: label })).toBeVisible();
            }
            unmount();
        }
    });
});
