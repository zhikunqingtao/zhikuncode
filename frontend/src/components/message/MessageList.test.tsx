/**
 * MessageList 双渲染路径冒烟测试
 * 覆盖：density=detailed → 平铺路径（无 TurnToolbar，但渲染「退出详细视图」
 * 浮动出口，点击回 balanced —— P2 修复 detailed 密度陷阱）；
 * density=compact/balanced → 轮次分组路径（渲染 TurnToolbar，无 detailed 出口）；
 * 空消息 → 空态兜底不变。
 * Wave 3：大纲切换按钮（仅分组路径）、抽屉开关、点选轮次写入展开 override、
 * 「回到最新」胶囊两条路径均挂载（jsdom 下 Virtuoso 不可测高，显隐逻辑由
 * BackToLatestCapsule 单测覆盖）。
 * （jsdom 下 Virtuoso 不可测高、不渲染 item，故仅断言路径分支结构。）
 */

import { fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Message } from '@/types';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useTurnViewStore } from '@/store/turnViewStore';
import MessageList from './MessageList';

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
    useTurnViewStore.setState({ density: 'balanced', expandOverrides: {} });
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

describe('MessageList 双渲染路径', () => {
    it('density=balanced → 分组路径：渲染 TurnToolbar', () => {
        render(<MessageList />);
        expect(screen.getByTestId('turn-toolbar')).toBeInTheDocument();
        expect(screen.getByRole('tab', { name: '平衡' })).toHaveAttribute('aria-selected', 'true');
    });

    it('density=compact → 分组路径：渲染 TurnToolbar', () => {
        useTurnViewStore.setState({ density: 'compact', expandOverrides: {} });
        render(<MessageList />);
        expect(screen.getByTestId('turn-toolbar')).toBeInTheDocument();
    });

    it('density=detailed → 平铺路径：不渲染 TurnToolbar', () => {
        useTurnViewStore.setState({ density: 'detailed', expandOverrides: {} });
        render(<MessageList />);
        expect(screen.queryByTestId('turn-toolbar')).not.toBeInTheDocument();
    });

    it('density=detailed → 渲染「退出详细视图」浮动出口（真实 button，可键盘聚焦）', () => {
        useTurnViewStore.setState({ density: 'detailed', expandOverrides: {} });
        render(<MessageList />);
        const exit = screen.getByRole('button', { name: '退出详细视图' });
        expect(exit.tagName).toBe('BUTTON');
        expect(exit).not.toHaveAttribute('tabindex', '-1');
    });

    it('detailed 出口点击 → setDensity(\'balanced\')：回到分组路径且出口消失', () => {
        useTurnViewStore.setState({ density: 'detailed', expandOverrides: {} });
        render(<MessageList />);
        fireEvent.click(screen.getByTestId('exit-detailed-view'));
        expect(useTurnViewStore.getState().density).toBe('balanced');
        expect(screen.getByTestId('turn-toolbar')).toBeInTheDocument();
        expect(screen.queryByTestId('exit-detailed-view')).not.toBeInTheDocument();
    });

    it('density=balanced → 分组路径：不渲染 detailed 出口', () => {
        render(<MessageList />);
        expect(screen.queryByTestId('exit-detailed-view')).not.toBeInTheDocument();
    });

    it('空消息 → 空态兜底（与路径无关）', () => {
        useMessageStore.setState({ messages: [] });
        render(<MessageList />);
        expect(screen.getByText('Start a conversation')).toBeInTheDocument();
        expect(screen.queryByTestId('turn-toolbar')).not.toBeInTheDocument();
    });
});

describe('MessageList Wave 3：轮次大纲 + 回到最新胶囊', () => {
    it('分组路径：大纲按钮开关抽屉（aria-hidden 翻转）', () => {
        render(<MessageList />);
        const toggle = screen.getByTestId('turn-outline-toggle');
        expect(toggle).toHaveAttribute('aria-pressed', 'false');
        expect(screen.getByTestId('turn-outline-drawer')).toHaveAttribute('aria-hidden', 'true');

        fireEvent.click(toggle);
        expect(screen.getByTestId('turn-outline-drawer')).toHaveAttribute('aria-hidden', 'false');
        expect(toggle).toHaveAttribute('aria-pressed', 'true');

        fireEvent.click(toggle);
        expect(screen.getByTestId('turn-outline-drawer')).toHaveAttribute('aria-hidden', 'true');
    });

    it('分组路径：点选大纲轮次 → setTurnExpanded(true) 写入 override，桌面保持抽屉打开', () => {
        render(<MessageList />);
        fireEvent.click(screen.getByTestId('turn-outline-toggle'));
        fireEvent.click(screen.getByTestId('turn-outline-item-0'));

        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toEqual({ 0: true });
        // jsdom 无 matchMedia → isMobile=false → 桌面形态：点选后抽屉保持打开
        expect(screen.getByTestId('turn-outline-drawer')).toHaveAttribute('aria-hidden', 'false');
    });

    it('detailed 平铺路径：无大纲按钮，胶囊仍挂载', () => {
        useTurnViewStore.setState({ density: 'detailed', expandOverrides: {} });
        render(<MessageList />);
        expect(screen.queryByTestId('turn-outline-toggle')).not.toBeInTheDocument();
        expect(screen.queryByTestId('turn-outline-drawer')).not.toBeInTheDocument();
        expect(screen.getByTestId('back-to-latest')).toBeInTheDocument();
    });

    it('空会话：不渲染大纲按钮与胶囊', () => {
        useMessageStore.setState({ messages: [] });
        render(<MessageList />);
        expect(screen.queryByTestId('turn-outline-toggle')).not.toBeInTheDocument();
        expect(screen.queryByTestId('back-to-latest')).not.toBeInTheDocument();
    });
});
