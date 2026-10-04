import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThemeProvider } from '../ThemeProvider';
import { useConfigStore } from '@/store/configStore';

const FX_CLASSES = ['fx-ink-rich', 'ink-retreat', 'motion-full', 'motion-reduced', 'motion-off', 'ink-boot'];

function clearDom() {
    document.documentElement.classList.remove('light', 'dark', 'glass', 'spaceship',
        'ink-havoc', 'ink-havoc-night', ...FX_CLASSES);
}

describe('ThemeProvider ink-havoc fx class 生命周期', () => {
    beforeEach(() => {
        clearDom();
        act(() => {
            useConfigStore.getState().resetTheme();
        });
    });

    afterEach(() => {
        vi.useRealTimers();
        clearDom();
        act(() => {
            useConfigStore.getState().resetTheme();
        });
    });

    it('ink-havoc + 默认 fx → html 有 ink-havoc fx-ink-rich motion-full', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc' });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('ink-havoc')).toBe(true);
        expect(cls.contains('fx-ink-rich')).toBe(true);
        expect(cls.contains('motion-full')).toBe(true);
    });

    it('ink-havoc-night + 默认 fx → html 有 ink-havoc-night fx-ink-rich motion-full', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc-night' });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('ink-havoc-night')).toBe(true);
        expect(cls.contains('fx-ink-rich')).toBe(true);
        expect(cls.contains('motion-full')).toBe(true);
    });

    it('切到 light → ink 特效 class 全部对称移除，html 只有 light', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc' });
        });
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'light' });
        });
        expect(document.documentElement.className).toBe('light');
    });

    it('ink 双模式互切 → 旧 mode class 移除，新 mode class 生效', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc' });
        });
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc-night' });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('ink-havoc')).toBe(false);
        expect(cls.contains('ink-havoc-night')).toBe(true);
        expect(cls.contains('fx-ink-rich')).toBe(true);
    });

    it('ink 模式下 fx.cinematic=false → 无 fx-ink-rich，motion-* 保留', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'ink-havoc',
                inkHavocFx: { cinematic: false, motion: 'reduced', retreat: false },
            });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('ink-havoc')).toBe(true);
        expect(cls.contains('fx-ink-rich')).toBe(false);
        expect(cls.contains('motion-reduced')).toBe(true);
    });

    it('boot：切到 ink-havoc 加 ink-boot，1600ms 后移除', () => {
        vi.useFakeTimers();
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc' });
        });
        const root = document.documentElement;
        expect(root.classList.contains('ink-boot')).toBe(true);

        act(() => {
            vi.advanceTimersByTime(1700);
        });
        expect(root.classList.contains('ink-boot')).toBe(false);
        // boot 定时器不影响其余门控 class
        expect(root.classList.contains('ink-havoc')).toBe(true);
        expect(root.classList.contains('fx-ink-rich')).toBe(true);
    });

    it('boot：ink-havoc⇄ink-havoc-night 互切也触发 ink-boot', () => {
        vi.useFakeTimers();
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc' });
        });
        act(() => {
            vi.advanceTimersByTime(1700);
        });
        expect(document.documentElement.classList.contains('ink-boot')).toBe(false);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc-night' });
        });
        expect(document.documentElement.classList.contains('ink-boot')).toBe(true);
    });

    it.each([
        [{ cinematic: false, motion: 'full' as const, retreat: false }],
        [{ cinematic: true, motion: 'off' as const, retreat: false }],
    ])('fx.cinematic=false 或 motion=off（%j）时切 ink-havoc → 不加 ink-boot', (inkHavocFx) => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc', inkHavocFx });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('ink-havoc')).toBe(true);
        expect(cls.contains('ink-boot')).toBe(false);
    });

    it('boot 期间切走 → ink-boot 即刻清理（不等定时器）', () => {
        vi.useFakeTimers();
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc' });
        });
        expect(document.documentElement.classList.contains('ink-boot')).toBe(true);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'dark' });
        });
        expect(document.documentElement.className).toBe('dark');
        // 定时器到期后亦不残留
        act(() => {
            vi.advanceTimersByTime(1700);
        });
        expect(document.documentElement.className).toBe('dark');
    });

    it('闭关：retreat=true → html 加 ink-retreat；retreat=false → 移除', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'ink-havoc',
                inkHavocFx: { cinematic: true, motion: 'full', retreat: true },
            });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('ink-retreat')).toBe(true);
        // 与浓郁/动效门控正交叠加
        expect(cls.contains('fx-ink-rich')).toBe(true);
        expect(cls.contains('motion-full')).toBe(true);

        act(() => {
            useConfigStore.getState().setTheme({
                inkHavocFx: { cinematic: true, motion: 'full', retreat: false },
            });
        });
        expect(cls.contains('ink-retreat')).toBe(false);
        expect(cls.contains('ink-havoc')).toBe(true);
    });

    it('闭关：ink-retreat 期间切走主题 → class 对称移除零残留', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'ink-havoc-night',
                inkHavocFx: { cinematic: true, motion: 'reduced', retreat: true },
            });
        });
        expect(document.documentElement.classList.contains('ink-retreat')).toBe(true);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'light' });
        });
        expect(document.documentElement.className).toBe('light');
    });
});
