import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThemeProvider } from '../ThemeProvider';
import { useConfigStore } from '@/store/configStore';

const FX_CLASSES = ['fx-cinematic', 'fx-event', 'motion-full', 'motion-reduced', 'motion-off', 'spaceship-boot'];

function clearDom() {
    document.documentElement.classList.remove('light', 'dark', 'glass', 'spaceship', ...FX_CLASSES);
}

describe('ThemeProvider spaceship fx class 生命周期', () => {
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

    it('spaceship + 默认 fx → html 有 spaceship fx-cinematic fx-event motion-full', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'spaceship' });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('spaceship')).toBe(true);
        expect(cls.contains('fx-cinematic')).toBe(true);
        expect(cls.contains('fx-event')).toBe(true);
        expect(cls.contains('motion-full')).toBe(true);
    });

    it('切到 light → spaceship 特效 class 全部对称移除，html 只有 light', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'spaceship' });
        });
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'light' });
        });
        expect(document.documentElement.className).toBe('light');
    });

    it('spaceship 下 fx.cinematic=false → 无 fx-cinematic，fx-event 保留', () => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({
                mode: 'spaceship',
                spaceshipFx: { cinematic: false, eventFx: true, motion: 'full' },
            });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('spaceship')).toBe(true);
        expect(cls.contains('fx-cinematic')).toBe(false);
        expect(cls.contains('fx-event')).toBe(true);
        expect(cls.contains('motion-full')).toBe(true);
    });

    it('boot：切到 spaceship 加 spaceship-boot，1200ms 后移除', () => {
        vi.useFakeTimers();
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'spaceship' });
        });
        const root = document.documentElement;
        expect(root.classList.contains('spaceship-boot')).toBe(true);

        act(() => {
            vi.advanceTimersByTime(1300);
        });
        expect(root.classList.contains('spaceship-boot')).toBe(false);
        // boot 定时器不影响其余门控 class
        expect(root.classList.contains('spaceship')).toBe(true);
        expect(root.classList.contains('fx-event')).toBe(true);
    });

    it.each([
        [{ cinematic: true, eventFx: false, motion: 'full' as const }],
        [{ cinematic: true, eventFx: true, motion: 'off' as const }],
    ])('fx.eventFx=false 或 motion=off（%j）时切 spaceship → 不加 spaceship-boot', (spaceshipFx) => {
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => {
            useConfigStore.getState().setTheme({ mode: 'spaceship', spaceshipFx });
        });
        const cls = document.documentElement.classList;
        expect(cls.contains('spaceship')).toBe(true);
        expect(cls.contains('spaceship-boot')).toBe(false);
    });
});
