/**
 * CommandPalette 本地视图显示方式命令测试
 * P2 修复 detailed 密度陷阱：detailed 视图不渲染 TurnToolbar，
 * 面板显示方式命令 = 不依赖 toolbar 的常驻切换路径 —— 命中后本地 setDensity +
 * 关闭面板，不上送 onSelect（无服务端往返、不改输入草稿）；
 * 普通 slash 命令维持原上送语义、不自动关面板。
 */

import { fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Command } from '@/types';
import { useSessionStore } from '@/store/sessionStore';
import { useTurnViewStore } from '@/store/turnViewStore';
import CommandPalette from './CommandPalette';

// jsdom 无 scrollIntoView，面板选中项滚动定位需要
beforeAll(() => {
    Element.prototype.scrollIntoView = vi.fn();
});

const serverCommands: Command[] = [
    { name: 'clear', description: '清空当前会话', group: '会话' },
];

beforeEach(() => {
    localStorage.clear();
    useTurnViewStore.setState({ density: 'balanced', expandOverrides: {} });
    useSessionStore.setState({ sessionId: 'sess-1' });
});

describe('CommandPalette 视图显示方式命令（P2 detailed 陷阱常驻出口）', () => {
    it('渲染三个本地显示方式命令：视图：精简/标准/完整过程', () => {
        render(<CommandPalette commands={serverCommands} filter="" onSelect={vi.fn()} onClose={vi.fn()} />);
        expect(screen.getByRole('option', { name: /视图：精简/ })).toBeInTheDocument();
        expect(screen.getByRole('option', { name: /视图：标准/ })).toBeInTheDocument();
        expect(screen.getByRole('option', { name: /视图：完整过程/ })).toBeInTheDocument();
    });

    it('命中「视图：完整过程」→ 本地 setDensity + 关闭面板，不上送 onSelect', () => {
        const onSelect = vi.fn();
        const onClose = vi.fn();
        render(<CommandPalette commands={serverCommands} filter="" onSelect={onSelect} onClose={onClose} />);
        fireEvent.click(screen.getByRole('option', { name: /视图：完整过程/ }));
        expect(useTurnViewStore.getState().density).toBe('detailed');
        expect(onClose).toHaveBeenCalledTimes(1);
        expect(onSelect).not.toHaveBeenCalled();
    });

    it('detailed 下命中「视图：标准」→ 回 balanced 并清空当前会话手动展开偏好', () => {
        useTurnViewStore.setState({ density: 'detailed', expandOverrides: { 'sess-1': { 0: true } } });
        render(<CommandPalette commands={serverCommands} filter="" onSelect={vi.fn()} onClose={vi.fn()} />);
        fireEvent.click(screen.getByRole('option', { name: /视图：标准/ }));
        expect(useTurnViewStore.getState().density).toBe('balanced');
        expect(useTurnViewStore.getState().expandOverrides['sess-1']).toBeUndefined();
    });

    it('普通 slash 命令 → 上送 onSelect，密度不变、不自动关面板', () => {
        const onSelect = vi.fn();
        const onClose = vi.fn();
        render(<CommandPalette commands={serverCommands} filter="" onSelect={onSelect} onClose={onClose} />);
        fireEvent.click(screen.getByRole('option', { name: /\/clear/ }));
        expect(onSelect).toHaveBeenCalledWith('clear');
        expect(onClose).not.toHaveBeenCalled();
        expect(useTurnViewStore.getState().density).toBe('balanced');
    });
});
