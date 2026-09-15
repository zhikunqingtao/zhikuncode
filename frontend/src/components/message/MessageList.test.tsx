/** MessageList 导航可见性、旧工具条移除与深链回归。真实滚动由浏览器测试验证。 */

import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ContentBlock, Message } from '@/types';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useTurnViewStore } from '@/store/turnViewStore';
import MessageList from './MessageList';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';

vi.mock('@/hooks/useTtsAvailability', () => ({
    useTtsAvailability: () => false,
}));

// jsdom 无 ResizeObserver，Virtuoso 挂载需要
beforeAll(() => {
    global.ResizeObserver = class {
        observe() {}
        unobserve() {}
        disconnect() {}
    };
});

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

beforeEach(() => {
    localStorage.clear();
    useWorkbenchViewStore.setState({ pendingMessageId: null });
    useTurnViewStore.setState({ density: 'compact', expandOverrides: {} });
    useMessageStore.setState({
        messages: [userText('u1', 1, '问题'), assistantMsg('a1', 2)],
        streamingMessageId: null,
        streamingContent: '',
        thinkingContent: '',
        activeToolCalls: new Map(),
        steeringMessageIds: {},
    });
    useSessionStore.setState({ sessionId: 'sess-1', status: 'idle' });
});

describe('MessageList 详细档导航', () => {
    it.each(['compact', 'balanced'] as const)('%s 档没有顶部工具条或详细导航', density => {
        useTurnViewStore.setState({ density });
        render(<MessageList />);
        expect(screen.queryByTestId('turn-toolbar')).not.toBeInTheDocument();
        expect(screen.queryByRole('navigation', { name: '详细视图导航' })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '全部展开' })).not.toBeInTheDocument();
    });
    it('详细档无任务数据时展示轮次导航', () => {
        useTurnViewStore.setState({ density: 'detailed' });
        render(<MessageList />);
        expect(screen.getByRole('navigation', { name: '详细视图导航' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '第 1 轮' })).toBeInTheDocument();
        expect(screen.queryByTestId('turn-toolbar')).not.toBeInTheDocument();
    });
    it('空消息不渲染导航', () => {
        useMessageStore.setState({ messages: [] });
        useTurnViewStore.setState({ density: 'detailed' });
        render(<MessageList />);
        expect(screen.getByText('Start a conversation')).toBeInTheDocument();
        expect(screen.queryByTestId('detail-navigation')).not.toBeInTheDocument();
    });
});

describe('MessageList 回到最新胶囊', () => {
    it.each(['compact', 'balanced', 'detailed'] as const)('%s 档新问题到达时只按该档规则折叠前轮过程', density => {
        useTurnViewStore.setState({ density, expandOverrides: { 'sess-1': { '0:0': true } } });
        useMessageStore.setState({ messages: [
            userText('u1', 1),
            { type: 'system', uuid: 'b1', timestamp: 2, subtype: 'task_boundary', content: '',
                metadata: { task_id: 'task', title: '任务一', seq: 1 } } as Message,
            assistantMsg('a1', 3),
        ] });
        render(<MessageList />);
        act(() => useMessageStore.getState().addMessage(userText('u2', 4)));
        expect(useTurnViewStore.getState().expandOverrides['sess-1']['0:0']).toBe(density === 'detailed');
    });

    it('分组路径：胶囊挂载（显隐由 BackToLatestCapsule 单测覆盖）', () => {
        render(<MessageList />);
        expect(screen.getByTestId('back-to-latest')).toBeInTheDocument();
    });

    it('空会话：不渲染胶囊', () => {
        useMessageStore.setState({ messages: [] });
        render(<MessageList />);
        expect(screen.queryByTestId('back-to-latest')).not.toBeInTheDocument();
    });
});


describe('审查回归：深链按目标内容选择密度', () => {
    it.each([['u1', '0:query'], ['a1', '0:answer']])('简洁档定位 %s 自动展开正文，不改变密度', async (uuid, key) => {
        render(<MessageList />);
        act(() => useWorkbenchViewStore.getState().openMessageInDevelopment(uuid));
        await waitFor(() => {
            expect(useTurnViewStore.getState().density).toBe('compact');
            expect(useTurnViewStore.getState().expandOverrides['sess-1']?.[key]).toBe(true);
            expect(useWorkbenchViewStore.getState().pendingMessageId).toBeNull();
        });
    });

    const tool: ContentBlock = {
        type: 'tool_use', toolUseId: 'read', toolName: 'Read', input: {},
        result: { content: 'ok', isError: false },
    };
    it.each([
        ['text', [{ type: 'text', text: '待定位的过程说明' }], 'detailed'],
        ['thinking', [{ type: 'thinking', thinking: '待定位的思考' }], 'detailed'],
        ['mixed', [{ type: 'text', text: '说明' }, tool], 'detailed'],
        ['tool', [tool], 'balanced'],
    ] as Array<[string, ContentBlock[], 'detailed' | 'balanced']>)(
        '%s 目标完整展示；纯工具仍使用平衡档', async (_name, content, density) => {
            useMessageStore.setState({ messages: [
                userText('u1', 1),
                { type: 'system', uuid: 'boundary', timestamp: 2, content: '',
                    subtype: 'task_boundary', metadata: { title: '任务一', seq: 1 } } as Message,
                { ...assistantMsg('target', 3), content } as Message,
                { ...assistantMsg('later-tool', 4), content: [tool] } as Message,
                assistantMsg('answer', 5),
            ] });
            render(<MessageList />);
            act(() => useWorkbenchViewStore.getState().openMessageInDevelopment('target'));
            await waitFor(() => {
                expect(useTurnViewStore.getState().density).toBe(density);
                expect(useTurnViewStore.getState().expandOverrides['sess-1']['0:0']).toBe(true);
                expect(useWorkbenchViewStore.getState().pendingMessageId).toBeNull();
            });
        },
    );
});
