import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThemeProvider } from '../ThemeProvider';
import { ThemePicker } from '../ThemePicker';
import { SettingsPanel } from '@/components/dialog/SettingsPanel';
import { normalizeTheme, normalizeThemeMode, useConfigStore } from '@/store/configStore';
import { ACCENT_PRESETS, SPIDER_ACCENT } from '@/theme/accents';
import { getChartColors, getMonacoZkThemes, getXtermPalette, resolveTheme } from '@/styles/design-tokens';
import { getEffectiveTheme, zkMonacoTheme } from '@/styles/zkMonaco';

beforeEach(() => {
    document.documentElement.className = '';
    document.documentElement.removeAttribute('style');
    useConfigStore.getState().resetTheme();
});
afterEach(() => {
    cleanup();
    useConfigStore.getState().resetTheme();
    document.documentElement.className = '';
    document.documentElement.removeAttribute('style');
    vi.unstubAllGlobals();
});

describe('蜘蛛皮肤的持久化与主题隔离', () => {
    it('接受新主题，并在重新水合后保留原强调色与外观偏好', async () => {
        expect(normalizeThemeMode('spider')).toBe('spider');
        expect(normalizeTheme('spider').mode).toBe('spider');
        useConfigStore.getState().setTheme({ mode: 'spider', accentColor: '#C9578A', fontSize: 'large' });
        const persisted = localStorage.getItem('ai-coder-config')!;
        useConfigStore.getState().setTheme({ mode: 'light' });
        localStorage.setItem('ai-coder-config', persisted);
        await useConfigStore.persist.rehydrate();
        expect(useConfigStore.getState().theme).toMatchObject({ mode: 'spider', accentColor: '#C9578A', fontSize: 'large' });
        expect(useConfigStore.getState().themePreferenceSet).toBe(true);
        expect(useConfigStore.getState().theme).not.toHaveProperty('spiderFx');
    });

    it('切入后清除旧主题动效门控，切出恢复保存的强调色', () => {
        const saved = ACCENT_PRESETS.find(preset => preset.label === '品红')!;
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => useConfigStore.getState().setTheme({ mode: 'jelly', accentColor: saved.hex, jellyFx: { cinematic: true, motion: 'off' } }));
        expect(document.documentElement.classList.contains('motion-off')).toBe(true);
        act(() => useConfigStore.getState().setTheme({ mode: 'spider' }));
        expect(document.documentElement.className).toBe('spider');
        expect(document.documentElement.style.getPropertyValue('--v2-accent')).toBe(SPIDER_ACCENT.accent);
        expect(useConfigStore.getState().theme.accentColor).toBe(saved.hex);
        expect(getEffectiveTheme()).toBe('dark');
        expect(zkMonacoTheme()).toBe('zk-dark');
        act(() => useConfigStore.getState().setTheme({ mode: 'dark' }));
        expect(document.documentElement.className).toBe('dark');
        expect(document.documentElement.style.getPropertyValue('--v2-accent')).toBe(saved.dark.accent);
    });

    it('本机选择优先于服务端默认主题，不需新增服务端主题配置', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ theme: 'light' }) }));
        useConfigStore.getState().setTheme({ mode: 'spider' });
        await useConfigStore.getState().loadConfig();
        expect(useConfigStore.getState().theme.mode).toBe('spider');
    });

    it('Monaco、终端和图表使用暗色及固定配色，不污染其他主题', () => {
        expect(resolveTheme('spider')).toBe('dark');
        const saved = ACCENT_PRESETS[4];
        const editor = getMonacoZkThemes('dark', saved.hex, 'spider');
        expect(editor.base).toBe('vs-dark');
        expect(editor.colors['editorCursor.foreground']).toBe(SPIDER_ACCENT.accent);
        expect(editor.colors['editor.background']).toBe('#05070C');
        expect(getXtermPalette('dark', saved.hex, 'spider').cursor).toBe(SPIDER_ACCENT.accent);
        expect(getChartColors('dark', saved.hex, 'spider')[0]).toBe(SPIDER_ACCENT.accent);
        expect(getChartColors('dark', saved.hex)[0]).toBe(saved.dark.accent);
        expect(getXtermPalette('dark', saved.hex).cursor).toBe(saved.dark.accent);
    });
});

describe('蜘蛛皮肤入口', () => {
    it.each(['dialog', 'picker'] as const)('%s 支持 8 项主题，固定配色不改写已选颜色', entry => {
        useConfigStore.getState().setTheme({ accentColor: '#C9578A' });
        render(entry === 'dialog' ? <SettingsPanel onClose={() => undefined} /> : <ThemePicker />);
        const modes = screen.getByRole('group', { name: '主题' });
        expect(within(modes).getAllByRole('button')).toHaveLength(8);
        fireEvent.click(within(modes).getByRole('button', { name: '蜘蛛爬虫' }));
        expect(useConfigStore.getState().theme.mode).toBe('spider');
        expect(screen.getByText('本主题使用专属青色、洋红与紫色配色')).toBeInTheDocument();
        const accents = within(screen.getByRole('group', { name: '强调色' })).getAllByRole('button');
        accents.forEach(button => expect(button).toBeDisabled());
        fireEvent.click(accents[0]);
        expect(useConfigStore.getState().theme.accentColor).toBe('#C9578A');
        expect(screen.queryByText('动效档')).not.toBeInTheDocument();
        fireEvent.click(within(modes).getByRole('button', { name: '深色' }));
        accents.forEach(button => expect(button).not.toBeDisabled());
    });
});
