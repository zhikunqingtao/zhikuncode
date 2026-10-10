import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThemeProvider } from '../ThemeProvider';
import { ThemePicker } from '../ThemePicker';
import { SettingsPanel } from '@/components/dialog/SettingsPanel';
import { normalizeTheme, normalizeThemeMode, useConfigStore } from '@/store/configStore';
import { ACCENT_PRESETS, GALAXY_ACCENT } from '@/theme/accents';
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

describe('银河皮肤的持久化与主题隔离', () => {
    it('接受新主题，并在重新水合后保留原强调色与外观偏好', async () => {
        expect(normalizeThemeMode('galaxy')).toBe('galaxy');
        expect(normalizeTheme('galaxy').mode).toBe('galaxy');
        useConfigStore.getState().setTheme({ mode: 'galaxy', accentColor: '#C9578A', fontSize: 'large' });
        const persisted = localStorage.getItem('ai-coder-config')!;
        useConfigStore.getState().setTheme({ mode: 'light' });
        localStorage.setItem('ai-coder-config', persisted);
        await useConfigStore.persist.rehydrate();
        expect(useConfigStore.getState().theme).toMatchObject({ mode: 'galaxy', accentColor: '#C9578A', fontSize: 'large' });
        expect(useConfigStore.getState().themePreferenceSet).toBe(true);
        expect(useConfigStore.getState().theme).not.toHaveProperty('galaxyFx');
    });

    it('切入后清除旧主题动效门控，切出恢复保存的强调色', () => {
        const saved = ACCENT_PRESETS.find(preset => preset.label === '品红')!;
        render(<ThemeProvider>{null}</ThemeProvider>);
        act(() => useConfigStore.getState().setTheme({ mode: 'jelly', accentColor: saved.hex, jellyFx: { cinematic: true, motion: 'off' } }));
        expect(document.documentElement.classList.contains('motion-off')).toBe(true);
        act(() => useConfigStore.getState().setTheme({ mode: 'galaxy' }));
        expect(document.documentElement.className).toBe('galaxy');
        expect(document.documentElement.style.getPropertyValue('--v2-accent')).toBe(GALAXY_ACCENT.accent);
        expect(useConfigStore.getState().theme.accentColor).toBe(saved.hex);
        expect(getEffectiveTheme()).toBe('dark');
        expect(zkMonacoTheme()).toBe('zk-dark');
        act(() => useConfigStore.getState().setTheme({ mode: 'dark' }));
        expect(document.documentElement.className).toBe('dark');
        expect(document.documentElement.style.getPropertyValue('--v2-accent')).toBe(saved.dark.accent);
    });

    it('本机选择优先于服务端默认主题，不需新增服务端主题配置', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ theme: 'light' }) }));
        useConfigStore.getState().setTheme({ mode: 'galaxy' });
        await useConfigStore.getState().loadConfig();
        expect(useConfigStore.getState().theme.mode).toBe('galaxy');
    });

    it('Monaco、终端和图表使用暗色及固定配色，不污染其他主题', () => {
        expect(resolveTheme('galaxy')).toBe('dark');
        const saved = ACCENT_PRESETS[4];
        const editor = getMonacoZkThemes('dark', saved.hex, 'galaxy');
        expect(editor.base).toBe('vs-dark');
        expect(editor.colors['editorCursor.foreground']).toBe(GALAXY_ACCENT.accent);
        expect(editor.colors['editor.background']).toBe('#060D1B');
        expect(getXtermPalette('dark', saved.hex, 'galaxy').cursor).toBe(GALAXY_ACCENT.accent);
        expect(getChartColors('dark', saved.hex, 'galaxy')[0]).toBe(GALAXY_ACCENT.accent);
        expect(getChartColors('dark', saved.hex)[0]).toBe(saved.dark.accent);
        expect(getXtermPalette('dark', saved.hex).cursor).toBe(saved.dark.accent);
    });
});

describe('银河皮肤入口', () => {
    it.each(['dialog', 'picker'] as const)('%s 支持 9 项主题，固定配色不改写已选颜色', entry => {
        useConfigStore.getState().setTheme({ accentColor: '#C9578A' });
        render(entry === 'dialog' ? <SettingsPanel onClose={() => undefined} /> : <ThemePicker />);
        const modes = screen.getByRole('group', { name: '主题' });
        expect(within(modes).getAllByRole('button')).toHaveLength(9);
        fireEvent.click(within(modes).getByRole('button', { name: '璀璨银河' }));
        expect(useConfigStore.getState().theme.mode).toBe('galaxy');
        expect(screen.getByText('本主题使用专属星蓝、紫罗兰与玫红配色')).toBeInTheDocument();
        const accents = within(screen.getByRole('group', { name: '强调色' })).getAllByRole('button');
        accents.forEach(button => expect(button).toBeDisabled());
        fireEvent.click(accents[0]);
        expect(useConfigStore.getState().theme.accentColor).toBe('#C9578A');
        expect(screen.queryByText('动效档')).not.toBeInTheDocument();
        fireEvent.click(within(modes).getByRole('button', { name: '深色' }));
        accents.forEach(button => expect(button).not.toBeDisabled());
    });
});
