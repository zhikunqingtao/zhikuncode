/**
 * InkSealStamp（盖印仪式 · 波次1增强①）门控与渲染测试
 * 覆盖：主题/浓郁档门控（calm 档与其他主题返回 null）、级别白文取字、
 * 落定角 --ink-seal-rotate ±3° 内联变量，以及 ToastContainer 集成渲染。
 */

import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { InkSealStamp } from '../InkSealStamp';
import { ToastContainer } from '@/components/common/ToastContainer';
import { useConfigStore } from '@/store/configStore';
import { useNotificationStore } from '@/store/notificationStore';

/** 落定角合法区间：±3° */
function expectRotateInRange(el: HTMLElement) {
    const raw = el.style.getPropertyValue('--ink-seal-rotate');
    expect(raw).toMatch(/^-?\d+(\.\d+)?deg$/);
    const deg = parseFloat(raw);
    expect(deg).toBeGreaterThanOrEqual(-3);
    expect(deg).toBeLessThanOrEqual(3);
}

describe('InkSealStamp 门控与渲染', () => {
    beforeEach(() => {
        act(() => {
            useConfigStore.getState().resetTheme();
            useNotificationStore.getState().clearAll();
        });
    });

    afterEach(() => {
        act(() => {
            useConfigStore.getState().resetTheme();
            useNotificationStore.getState().clearAll();
        });
    });

    it('非 ink 主题（light）→ 返回 null 不渲染', () => {
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'light' });
        });
        const { container } = render(<InkSealStamp level="success" />);
        expect(container.querySelector('.ink-seal-stamp')).toBeNull();
    });

    it('ink + cinematic=false（克制档）→ 返回 null（calm 档 toast 常规）', () => {
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'ink-havoc',
                inkHavocFx: { cinematic: false, motion: 'full', retreat: false },
            });
        });
        const { container } = render(<InkSealStamp level="success" />);
        expect(container.querySelector('.ink-seal-stamp')).toBeNull();
    });

    it.each([
        ['success', '成'],
        ['warning', '警'],
        ['error', '误'],
        ['info', '报'],
    ] as const)('ink-havoc 浓郁档：level=%s → 白文「%s」+ 落定角 ±3°', (level, glyph) => {
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'ink-havoc',
                inkHavocFx: { cinematic: true, motion: 'full', retreat: false },
            });
        });
        const { container } = render(<InkSealStamp level={level} />);
        const stamp = container.querySelector('.ink-seal-stamp');
        expect(stamp).not.toBeNull();
        expect(stamp!.textContent).toBe(glyph);
        expect(stamp!.getAttribute('data-level')).toBe(level);
        expect(stamp!.getAttribute('aria-hidden')).toBe('true');
        expectRotateInRange(stamp as HTMLElement);
    });

    it('ink-havoc-night 浓郁档 → 同样渲染', () => {
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'ink-havoc-night',
                inkHavocFx: { cinematic: true, motion: 'full', retreat: false },
            });
        });
        const { container } = render(<InkSealStamp level="error" />);
        const stamp = container.querySelector('.ink-seal-stamp');
        expect(stamp).not.toBeNull();
        expect(stamp!.textContent).toBe('误');
    });

    it('ToastContainer 集成：ink 浓郁档 toast 卡片内含对应朱印', () => {
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'ink-havoc',
                inkHavocFx: { cinematic: true, motion: 'full', retreat: false },
            });
            useNotificationStore.getState().addNotification({
                key: 't-seal',
                level: 'success',
                message: '设置已保存',
            });
        });
        const { container } = render(<ToastContainer />);
        const stamp = container.querySelector('.toast-card .ink-seal-stamp');
        expect(stamp).not.toBeNull();
        expect(stamp!.textContent).toBe('成');
    });

    it('ToastContainer 集成：light 主题 toast 常规（无朱印）', () => {
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'light' });
            useNotificationStore.getState().addNotification({
                key: 't-plain',
                level: 'success',
                message: '设置已保存',
            });
        });
        const { container } = render(<ToastContainer />);
        expect(container.querySelector('.toast-card')).not.toBeNull();
        expect(container.querySelector('.ink-seal-stamp')).toBeNull();
    });
});
