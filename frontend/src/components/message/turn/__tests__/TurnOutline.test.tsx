/**
 * TurnOutline 组件测试
 * 覆盖：轮次列表渲染（「第 N 轮」+ 指令首行 + 右侧耗时）、preamble 轮「会话上下文」、
 * active 轮高亮（aria-current）、状态点语义色（沿用 TurnCard）、点击回调 onSelect(turnIndex)、
 * 桌面抽屉开/关形态（aria-hidden / 初始关闭不渲染列表）、移动端 SheetShell 承载。
 */

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Message } from '@/types';
import { buildTurns } from '@/store/selectors/turnProjection';
import { computeTurnOrdinals } from '../turnUtils';
import TurnOutline from '../TurnOutline';

// ==================== 消息工厂（与 TurnCard.test 同款） ====================

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

/** preamble（系统消息）+ 两轮指令；末轮 active */
function threeTurns() {
    const turns = buildTurns([
        systemMsg('s0', 1000),
        userText('u1', 2000, '修复登录接口报错'),
        assistantMsg('a1', 33000),
        userText('u2', 40000, '再补一个单测'),
        assistantMsg('a2', 45000),
    ]);
    expect(turns).toHaveLength(3);
    return { turns, turnOrdinals: computeTurnOrdinals(turns) };
}

function renderOutline(overrides: Partial<Parameters<typeof TurnOutline>[0]> = {}) {
    const { turns, turnOrdinals } = threeTurns();
    const props: Parameters<typeof TurnOutline>[0] = {
        turns,
        turnOrdinals,
        open: true,
        isMobile: false,
        isRunActive: false,
        onClose: vi.fn(),
        onSelect: vi.fn(),
        ...overrides,
    };
    return { ...render(<TurnOutline {...props} />), props };
}

describe('TurnOutline 列表渲染（桌面抽屉）', () => {
    it('渲染全部轮次行：preamble「会话上下文」+「第 N 轮」+ 指令首行 + 耗时', () => {
        renderOutline();
        // preamble 轮
        expect(screen.getByTestId('turn-outline-item-0')).toHaveTextContent('会话上下文');
        // 第 1 轮：序号 + 指令首行 + 耗时（31s）
        const item1 = screen.getByTestId('turn-outline-item-1');
        expect(item1).toHaveTextContent('第 1 轮');
        expect(item1).toHaveTextContent('修复登录接口报错');
        expect(item1).toHaveTextContent('31s');
        // 第 2 轮（active）
        const item2 = screen.getByTestId('turn-outline-item-2');
        expect(item2).toHaveTextContent('第 2 轮');
        expect(item2).toHaveTextContent('再补一个单测');
    });

    it('active 轮高亮（aria-current），其余轮不高亮', () => {
        renderOutline();
        expect(screen.getByTestId('turn-outline-item-2')).toHaveAttribute('aria-current', 'true');
        expect(screen.getByTestId('turn-outline-item-1')).not.toHaveAttribute('aria-current');
        expect(screen.getByTestId('turn-outline-item-0')).not.toHaveAttribute('aria-current');
    });

    it('点击某轮 → onSelect(turnIndex)', () => {
        const { props } = renderOutline();
        fireEvent.click(screen.getByTestId('turn-outline-item-1'));
        expect(props.onSelect).toHaveBeenCalledTimes(1);
        expect(props.onSelect).toHaveBeenCalledWith(1);
    });

    it('状态点语义色与 TurnCard 一致：出错轮 bg-err，完成轮 bg-ok', () => {
        const turns = buildTurns([
            userText('u1', 1000, '第一轮'),
            systemMsg('s1', 2000, 'error'),
            userText('u2', 3000, '第二轮'),
            assistantMsg('a2', 4000),
        ]);
        renderOutline({ turns, turnOrdinals: computeTurnOrdinals(turns) });
        expect(screen.getByTestId('turn-outline-dot-0').className).toContain('bg-err');
        expect(screen.getByTestId('turn-outline-dot-1').className).toContain('bg-ok');
    });

    it('active 轮且 run 进行中 → accent 呼吸点', () => {
        renderOutline({ isRunActive: true });
        expect(screen.getByTestId('turn-outline-dot-2').className).toContain('animate-accent-pulse');
    });
});

describe('TurnOutline 桌面抽屉开/关', () => {
    it('open=true → aria-hidden=false，列表渲染', () => {
        renderOutline({ open: true });
        expect(screen.getByTestId('turn-outline-drawer')).toHaveAttribute('aria-hidden', 'false');
        expect(screen.getByTestId('turn-outline-list')).toBeInTheDocument();
    });

    it('初始 open=false → aria-hidden=true，列表不渲染（延迟卸载）', () => {
        renderOutline({ open: false });
        expect(screen.getByTestId('turn-outline-drawer')).toHaveAttribute('aria-hidden', 'true');
        expect(screen.queryByTestId('turn-outline-list')).not.toBeInTheDocument();
    });

    it('关闭按钮 → onClose', () => {
        const { props } = renderOutline();
        fireEvent.click(screen.getByTestId('turn-outline-close'));
        expect(props.onClose).toHaveBeenCalledTimes(1);
    });
});

describe('TurnOutline 移动端 SheetShell 形态', () => {
    it('isMobile=true → 底部 Sheet（role=dialog）承载同一份列表', () => {
        renderOutline({ isMobile: true });
        expect(screen.getByRole('dialog', { name: '轮次大纲' })).toBeInTheDocument();
        expect(screen.getByTestId('turn-outline-list')).toBeInTheDocument();
        expect(screen.getByTestId('turn-outline-item-0')).toHaveTextContent('会话上下文');
    });

    it('移动端点击某轮 → onSelect（关闭由调用方负责）', () => {
        const { props } = renderOutline({ isMobile: true });
        fireEvent.click(screen.getByTestId('turn-outline-item-2'));
        expect(props.onSelect).toHaveBeenCalledWith(2);
    });

    it('移动端 open=false → Sheet 不渲染', () => {
        renderOutline({ isMobile: true, open: false });
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
});
