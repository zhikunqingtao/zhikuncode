/**
 * InterruptConfirmDialog 测试
 * 覆盖：桌面/平板 Dialog 形态与手机 SheetShell 形态的渲染、
 * 「取消」/「确认停止」回调互斥、Esc 关闭等价取消、open=false 不渲染内容。
 */

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InterruptConfirmDialog } from './InterruptConfirmDialog';

const DESCRIPTION = '停止后 AI 会立即中断当前生成，已经产出的内容会保留。确定要停止吗？';

/** jsdom 无 matchMedia 实现：沿用项目既有 polyfill 写法，按需命中 (max-width:767px) 模拟手机视口 */
let mobileViewport = false;

beforeEach(() => {
    mobileViewport = false;
    window.matchMedia = ((query: string) => ({
        matches: mobileViewport && query === '(max-width: 767px)',
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;
});

afterEach(cleanup);

describe('InterruptConfirmDialog — 桌面/平板形态', () => {
    it('open 时渲染对话框、标题与说明文案，且不触发任何回调', () => {
        const onClose = vi.fn();
        const onConfirm = vi.fn();
        render(
            <InterruptConfirmDialog open onClose={onClose} onConfirm={onConfirm} />,
        );

        expect(
            screen.getByRole('dialog', { name: '停止当前任务' }),
        ).toBeInTheDocument();
        expect(screen.getByText(DESCRIPTION)).toBeInTheDocument();
        expect(onClose).not.toHaveBeenCalled();
        expect(onConfirm).not.toHaveBeenCalled();
    });

    it('点击「取消」只调用 onClose', () => {
        const onClose = vi.fn();
        const onConfirm = vi.fn();
        render(
            <InterruptConfirmDialog open onClose={onClose} onConfirm={onConfirm} />,
        );

        fireEvent.click(screen.getByRole('button', { name: '取消' }));

        expect(onClose).toHaveBeenCalledTimes(1);
        expect(onConfirm).not.toHaveBeenCalled();
    });

    it('点击「确认停止」只调用 onConfirm', () => {
        const onClose = vi.fn();
        const onConfirm = vi.fn();
        render(
            <InterruptConfirmDialog open onClose={onClose} onConfirm={onConfirm} />,
        );

        fireEvent.click(screen.getByRole('button', { name: '确认停止' }));

        expect(onConfirm).toHaveBeenCalledTimes(1);
        expect(onClose).not.toHaveBeenCalled();
    });

    it('Esc 关闭等价取消，不触发中断', () => {
        const onClose = vi.fn();
        const onConfirm = vi.fn();
        render(
            <InterruptConfirmDialog open onClose={onClose} onConfirm={onConfirm} />,
        );

        fireEvent.keyDown(document, { key: 'Escape' });

        expect(onClose).toHaveBeenCalledTimes(1);
        expect(onConfirm).not.toHaveBeenCalled();
    });

    it('open=false 时不渲染任何内容', () => {
        render(
            <InterruptConfirmDialog
                open={false}
                onClose={vi.fn()}
                onConfirm={vi.fn()}
            />,
        );

        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(screen.queryByText(DESCRIPTION)).not.toBeInTheDocument();
    });
});

describe('InterruptConfirmDialog — 手机形态', () => {
    beforeEach(() => {
        mobileViewport = true;
    });

    it('open 时渲染底部抽屉（无桌面关闭按钮），保留同一说明文案', () => {
        render(
            <InterruptConfirmDialog
                open
                onClose={vi.fn()}
                onConfirm={vi.fn()}
            />,
        );

        expect(
            screen.getByRole('dialog', { name: '停止当前任务' }),
        ).toBeInTheDocument();
        expect(screen.getByText(DESCRIPTION)).toBeInTheDocument();
        /* SheetShell 形态没有 Dialog 的右上角关闭按钮 */
        expect(
            screen.queryByRole('button', { name: '关闭' }),
        ).not.toBeInTheDocument();
    });

    it('点击「取消」只调用 onClose', () => {
        const onClose = vi.fn();
        const onConfirm = vi.fn();
        render(
            <InterruptConfirmDialog open onClose={onClose} onConfirm={onConfirm} />,
        );

        fireEvent.click(screen.getByRole('button', { name: '取消' }));

        expect(onClose).toHaveBeenCalledTimes(1);
        expect(onConfirm).not.toHaveBeenCalled();
    });

    it('点击「确认停止」只调用 onConfirm', () => {
        const onClose = vi.fn();
        const onConfirm = vi.fn();
        render(
            <InterruptConfirmDialog open onClose={onClose} onConfirm={onConfirm} />,
        );

        fireEvent.click(screen.getByRole('button', { name: '确认停止' }));

        expect(onConfirm).toHaveBeenCalledTimes(1);
        expect(onClose).not.toHaveBeenCalled();
    });

    it('Esc 关闭等价取消，不触发中断', () => {
        const onClose = vi.fn();
        const onConfirm = vi.fn();
        render(
            <InterruptConfirmDialog open onClose={onClose} onConfirm={onConfirm} />,
        );

        fireEvent.keyDown(document, { key: 'Escape' });

        expect(onClose).toHaveBeenCalledTimes(1);
        expect(onConfirm).not.toHaveBeenCalled();
    });

    it('open=false 时不渲染任何内容', () => {
        render(
            <InterruptConfirmDialog
                open={false}
                onClose={vi.fn()}
                onConfirm={vi.fn()}
            />,
        );

        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(screen.queryByText(DESCRIPTION)).not.toBeInTheDocument();
    });
});
