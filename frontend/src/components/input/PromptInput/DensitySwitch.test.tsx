/**
 * DensitySwitch 组件测试（消息密度切换器，输入框工具行承载）
 * 覆盖：桌面分段控件三档切换（setDensity + 清当前会话 overrides，其他会话
 * 保留）、当前密度选中态；移动端单按钮 + 三选一菜单（开关、选中、backdrop
 * 收起）。
 */

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSessionStore } from '@/store/sessionStore';
import { useTurnViewStore } from '@/store/turnViewStore';
import { DensitySwitch, MobileDensitySwitch } from './DensitySwitch';

beforeEach(() => {
    localStorage.clear();
    useTurnViewStore.setState({ density: 'compact', expandOverrides: {} });
    useSessionStore.setState({ sessionId: 'sess-1' });
});

describe('DensitySwitch 桌面分段控件', () => {
    it('渲染三档 tab，当前密度为选中态', () => {
        useTurnViewStore.setState({ density: 'balanced' });
        render(<DensitySwitch />);
        expect(screen.getByRole('tab', { name: '简洁' })).toHaveAttribute('aria-selected', 'false');
        expect(screen.getByRole('tab', { name: '平衡' })).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByRole('tab', { name: '详细' })).toHaveAttribute('aria-selected', 'false');
    });

    it('点击「详细」→ setDensity 生效并清空当前会话 overrides，其他会话保留', () => {
        useTurnViewStore.getState().setSectionExpanded('sess-1', '0', true);
        useTurnViewStore.getState().setSectionExpanded('sess-2', '3:0', false);

        render(<DensitySwitch />);
        fireEvent.click(screen.getByRole('tab', { name: '详细' }));

        const state = useTurnViewStore.getState();
        expect(state.density).toBe('detailed');
        expect(state.expandOverrides['sess-1']).toBeUndefined();
        expect(state.expandOverrides['sess-2']).toEqual({ '3:0': false });
    });

    it('点击「简洁」→ density=compact', () => {
        useTurnViewStore.setState({ density: 'balanced' });
        render(<DensitySwitch />);
        fireEvent.click(screen.getByRole('tab', { name: '简洁' }));
        expect(useTurnViewStore.getState().density).toBe('compact');
    });
});

describe('MobileDensitySwitch 移动端菜单', () => {
    it('chip 显示当前密度，点击弹出三选一菜单', () => {
        useTurnViewStore.setState({ density: 'balanced' });
        render(<MobileDensitySwitch />);
        const chip = screen.getByTestId('mobile-density-chip');
        expect(chip).toHaveTextContent('平衡');
        expect(screen.queryByTestId('mobile-density-menu')).not.toBeInTheDocument();

        fireEvent.click(chip);
        const menu = screen.getByTestId('mobile-density-menu');
        expect(menu).toBeInTheDocument();
        expect(chip).toHaveAttribute('aria-expanded', 'true');
        // 当前密度项标记 aria-current
        expect(screen.getByRole('button', { name: /^平衡/ }))
            .toHaveAttribute('aria-pressed', 'true');
    });

    it('菜单选择「详细」→ 切密度并收起菜单', async () => {
        render(<MobileDensitySwitch />);
        fireEvent.click(screen.getByTestId('mobile-density-chip'));
        fireEvent.click(screen.getByRole('button', { name: /^详细/ }));
        expect(useTurnViewStore.getState().density).toBe('detailed');
        await waitFor(() => expect(screen.queryByTestId('mobile-density-menu')).not.toBeInTheDocument());
        expect(screen.getByTestId('mobile-density-chip')).toHaveTextContent('详细');
    });

    it('菜单选择走当前 sessionId 清空其 overrides', () => {
        useTurnViewStore.getState().setSectionExpanded('sess-1', '0:1', true);
        render(<MobileDensitySwitch />);
        fireEvent.click(screen.getByTestId('mobile-density-chip'));
        fireEvent.click(screen.getByRole('button', { name: /^平衡/ }));
        expect(useTurnViewStore.getState().density).toBe('balanced');
        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toBeUndefined();
    });
});
