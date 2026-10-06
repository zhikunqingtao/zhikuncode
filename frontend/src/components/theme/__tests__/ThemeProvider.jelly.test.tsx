/**
 * ThemeProvider · jelly 门控 class 生命周期测试（仿 ThemeProvider.inkHavoc.test.ts 先例）：
 * - jelly + 默认 fx → html 有 jelly / fx-jelly-rich / motion-full
 * - cinematic=false → 无 fx-jelly-rich（calm 档零装饰），motion-* 保留
 * - 三档 motion 写入正确；切走主题 → 全部对称移除零残留
 */
import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ThemeProvider } from '../ThemeProvider';
import { useConfigStore } from '@/store/configStore';

const FX_CLASSES = ['fx-jelly-rich', 'motion-full', 'motion-reduced', 'motion-off'];

function clearDom() {
    document.documentElement.classList.remove('light', 'dark', 'glass', 'spaceship',
        'ink-havoc', 'ink-havoc-night', 'jelly', 'fx-ink-rich', 'ink-retreat', ...FX_CLASSES);
}

describe('ThemeProvider jelly fx class 生命周期', () => {
    beforeEach(() => {
        clearDom();
        act(() => {
            useConfigStore.getState().resetTheme();
        });
    });

    afterEach(() => {
        clearDom();
        act(() => {
            useConfigStore.getState().resetTheme();
        });
    });

    it('jelly + 默认 fx → html 有 jelly fx-jelly-rich motion-full', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'jelly' });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('jelly')).toBe(true);
        expect(cls.contains('fx-jelly-rich')).toBe(true);
        expect(cls.contains('motion-full')).toBe(true);
    });

    it('jelly + cinematic=false（calm 档）→ 无 fx-jelly-rich，motion-* 保留', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'jelly',
                jellyFx: { cinematic: false, motion: 'reduced' },
            });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('jelly')).toBe(true);
        expect(cls.contains('fx-jelly-rich')).toBe(false);
        expect(cls.contains('motion-reduced')).toBe(true);
    });

    it.each(['full', 'reduced', 'off'] as const)('motion=%s → 写入对应 motion-* class', (motion) => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'jelly',
                jellyFx: { cinematic: true, motion },
            });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains(`motion-${motion}`)).toBe(true);
        expect(cls.contains('fx-jelly-rich')).toBe(true);
    });

    it('jelly 内切档（cinematic true→false）→ fx-jelly-rich 对称移除', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'jelly', jellyFx: { cinematic: true, motion: 'full' } });
        });
        expect(document.documentElement.classList.contains('fx-jelly-rich')).toBe(true);
        act(() => {
            useConfigStore.getState().setTheme({ jellyFx: { cinematic: false, motion: 'full' } });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('jelly')).toBe(true);
        expect(cls.contains('fx-jelly-rich')).toBe(false);
        expect(cls.contains('motion-full')).toBe(true);
    });

    it('切到 light → jelly 特效 class 全部对称移除，html 只有 light', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'jelly' });
        });
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'light' });
        });
        expect(document.documentElement.className).toBe('light');
    });

    it('jelly → ink-havoc 互切 → 双方门控 class 无残留', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'jelly' });
        });
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'ink-havoc' });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('jelly')).toBe(false);
        expect(cls.contains('fx-jelly-rich')).toBe(false);
        expect(cls.contains('ink-havoc')).toBe(true);
        expect(cls.contains('fx-ink-rich')).toBe(true);
    });

    it('jelly 开着浓郁档切到 dark → 纯 dark 零残留', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'jelly', jellyFx: { cinematic: true, motion: 'off' } });
        });
        expect(document.documentElement.classList.contains('motion-off')).toBe(true);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'dark' });
        });
        expect(document.documentElement.className).toBe('dark');
    });
});
