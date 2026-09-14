/**
 * TurnToolbar 组件测试
 * 覆盖：密度三档 segmented control 切换（setDensity + 清当前会话 overrides）、
 * 全部展开 / 全部折叠（expandAll / collapseAll 写入全部轮次 index）、
 * 「大纲」切换按钮（Wave 3：成对提供回调且有轮次才渲染，aria-pressed 反映开关态）。
 */

import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useTurnViewStore } from '@/store/turnViewStore';
import TurnToolbar from '../TurnToolbar';

beforeEach(() => {
    localStorage.clear();
    useTurnViewStore.setState({ density: 'balanced', expandOverrides: {} });
});

describe('TurnToolbar 密度切换', () => {
    it('点击「简洁」→ setDensity 生效并清空当前会话 overrides', () => {
        useTurnViewStore.getState().setTurnExpanded('sess-1', 0, true);
        useTurnViewStore.getState().setTurnExpanded('sess-2', 3, false);

        render(<TurnToolbar density="balanced" sessionId="sess-1" turnIndexes={[0, 1]} />);
        fireEvent.click(screen.getByRole('tab', { name: '简洁' }));

        const state = useTurnViewStore.getState();
        expect(state.density).toBe('compact');
        // 当前会话 overrides 被清空；其他会话保留
        expect(state.expandOverrides['sess-1']).toBeUndefined();
        expect(state.expandOverrides['sess-2']).toEqual({ 3: false });
    });

    it('点击「详细」→ density=detailed（一键回旧视图）', () => {
        render(<TurnToolbar density="balanced" sessionId="sess-1" turnIndexes={[0]} />);
        fireEvent.click(screen.getByRole('tab', { name: '详细' }));
        expect(useTurnViewStore.getState().density).toBe('detailed');
    });

    it('当前密度对应 tab 为选中态', () => {
        render(<TurnToolbar density="compact" sessionId="sess-1" turnIndexes={[0]} />);
        expect(screen.getByRole('tab', { name: '简洁' })).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByRole('tab', { name: '平衡' })).toHaveAttribute('aria-selected', 'false');
    });
});

describe('TurnToolbar 全部展开 / 全部折叠', () => {
    it('全部展开 → 所有轮次 override=true', () => {
        render(<TurnToolbar density="balanced" sessionId="sess-1" turnIndexes={[0, 1, 2]} />);
        fireEvent.click(screen.getByTestId('turn-expand-all'));
        expect(useTurnViewStore.getState().expandOverrides['sess-1'])
            .toEqual({ 0: true, 1: true, 2: true });
    });

    it('全部折叠 → 所有轮次 override=false', () => {
        render(<TurnToolbar density="balanced" sessionId="sess-1" turnIndexes={[0, 1, 2]} />);
        fireEvent.click(screen.getByTestId('turn-collapse-all'));
        expect(useTurnViewStore.getState().expandOverrides['sess-1'])
            .toEqual({ 0: false, 1: false, 2: false });
    });

    it('无 sessionId 时按钮禁用', () => {
        render(<TurnToolbar density="balanced" sessionId={null} turnIndexes={[0, 1]} />);
        expect(screen.getByTestId('turn-expand-all')).toBeDisabled();
        expect(screen.getByTestId('turn-collapse-all')).toBeDisabled();
    });
});

describe('TurnToolbar 大纲切换按钮（Wave 3）', () => {
    it('提供 onToggleOutline 且有轮次 → 渲染按钮，点击触发回调', () => {
        const onToggleOutline = vi.fn();
        render(
            <TurnToolbar
                density="balanced"
                sessionId="sess-1"
                turnIndexes={[0, 1]}
                outlineOpen={false}
                onToggleOutline={onToggleOutline}
            />,
        );
        const toggle = screen.getByTestId('turn-outline-toggle');
        expect(toggle).toHaveAttribute('aria-pressed', 'false');
        fireEvent.click(toggle);
        expect(onToggleOutline).toHaveBeenCalledTimes(1);
    });

    it('outlineOpen=true → aria-pressed=true', () => {
        render(
            <TurnToolbar
                density="balanced"
                sessionId="sess-1"
                turnIndexes={[0]}
                outlineOpen
                onToggleOutline={() => {}}
            />,
        );
        expect(screen.getByTestId('turn-outline-toggle')).toHaveAttribute('aria-pressed', 'true');
    });

    it('空会话（无轮次）→ 不渲染大纲按钮', () => {
        render(
            <TurnToolbar
                density="balanced"
                sessionId="sess-1"
                turnIndexes={[]}
                onToggleOutline={() => {}}
            />,
        );
        expect(screen.queryByTestId('turn-outline-toggle')).not.toBeInTheDocument();
    });

    it('未提供 onToggleOutline → 不渲染大纲按钮（向后兼容）', () => {
        render(<TurnToolbar density="balanced" sessionId="sess-1" turnIndexes={[0]} />);
        expect(screen.queryByTestId('turn-outline-toggle')).not.toBeInTheDocument();
    });
});
