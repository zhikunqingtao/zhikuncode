import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { useConfigStore } from '../configStore';

describe('ConfigStore', () => {
    beforeEach(() => {
        // Reset to defaults（§3.4/§9.6：默认强调色 = 靛蓝 #6366F1）
        useConfigStore.setState({
            themePreferenceSet: false,
            theme: {
                mode: 'light',
                accentColor: '#6366F1',
                fontSize: 'medium',
                fontFamily: 'monospace',
                borderRadius: 'md',
            },
            locale: 'zh-CN',
            autoCompact: { enabled: true, threshold: 80 },
            verbose: false,
            expandedView: false,
            outputStyle: { availableStyles: [], activeStyleName: null },
            defaultModel: 'qwen3.6-plus',
        });
    });

    afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

    it.each([1, 2, 3])('rehydrates v%s system preferences using the current OS theme', async version => {
        vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })));
        localStorage.setItem('ai-coder-config', JSON.stringify({ version, state: {
            theme: version === 1 ? 'system' : { mode: 'system', accentColor: '#ff0000' },
            locale: 'en-US',
        } }));
        await useConfigStore.persist.rehydrate();
        expect(useConfigStore.getState().theme.mode).toBe('dark');
        expect(useConfigStore.getState().locale).toBe('en-US');
        expect(useConfigStore.getState().theme.fontSize).toBe('medium');
        if (version > 1) expect(useConfigStore.getState().theme.accentColor).toBe('#ff0000');
    });

    it.each(['light', 'dark', 'glass', 'unknown'])('normalizes persisted mode %s without losing other appearance fields', async mode => {
        localStorage.setItem('ai-coder-config', JSON.stringify({ version: 2, state: {
            theme: { mode, accentColor: '#123456', fontSize: 'large' },
        } }));
        await useConfigStore.persist.rehydrate();
        expect(useConfigStore.getState().theme).toMatchObject({
            mode: mode === 'unknown' ? 'light' : mode, accentColor: '#123456', fontSize: 'large',
        });
    });

    it.each(['system', { mode: 'system' }, 'unknown', { mode: 'unknown' }])('normalizes server theme %j', async theme => {
        vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })));
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ theme }) }));
        await useConfigStore.getState().loadConfig();
        expect(useConfigStore.getState().theme).toMatchObject({ mode: 'light', fontSize: 'medium' });
    });

    it('normalizes cached system theme after all network retries fail', async () => {
        vi.useFakeTimers();
        vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })));
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
        localStorage.setItem('config_cache', JSON.stringify({ theme: { mode: 'system' } }));
        const loading = useConfigStore.getState().loadConfig();
        await vi.runAllTimersAsync();
        await loading;
        expect(useConfigStore.getState().theme).toMatchObject({ mode: 'dark', fontSize: 'medium' });
    });

    it('keeps a locally chosen light theme when the server returns a fixed dark theme', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ theme: 'dark' }) }));
        useConfigStore.getState().setTheme({ mode: 'light' });
        await useConfigStore.getState().loadConfig();
        expect(useConfigStore.getState().theme.mode).toBe('light');
        expect(JSON.parse(localStorage.getItem('ai-coder-config')!).state.themePreferenceSet).toBe(true);
    });

    it('uses the server theme until the user chooses an appearance', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ theme: 'dark' }) }));
        await useConfigStore.getState().loadConfig();
        expect(useConfigStore.getState().theme.mode).toBe('dark');
    });

    it('should have default theme', () => {
        const { theme } = useConfigStore.getState();
        expect(theme.mode).toBe('light');
        expect(theme.accentColor).toBe('#6366F1');
        expect(theme.fontSize).toBe('medium');
    });

    it('setTheme updates theme partially', () => {
        useConfigStore.getState().setTheme({ mode: 'dark' });
        const { theme } = useConfigStore.getState();
        expect(theme.mode).toBe('dark');
        expect(theme.accentColor).toBe('#6366F1'); // unchanged
    });

    it('resetTheme restores defaults', () => {
        useConfigStore.getState().setTheme({ mode: 'dark', accentColor: '#ff0000' });
        useConfigStore.getState().resetTheme();
        const { theme } = useConfigStore.getState();
        expect(theme.mode).toBe('light');
        // §9.6：默认强调色已改靛蓝
        expect(theme.accentColor).toBe('#6366F1');
    });

    it('setLocale updates locale', () => {
        useConfigStore.getState().setLocale('en-US');
        expect(useConfigStore.getState().locale).toBe('en-US');
    });

    it('default model is set', () => {
        expect(useConfigStore.getState().defaultModel).toBe('qwen3.6-plus');
    });

    it('setOutputStyles updates available styles', () => {
        const styles = [
            { name: 'concise', description: 'Brief responses', systemPrompt: 'Be concise' },
        ];
        useConfigStore.getState().setOutputStyles(styles as any);
        expect(useConfigStore.getState().outputStyle.availableStyles).toHaveLength(1);
    });

    it('setActiveOutputStyle updates active style', () => {
        useConfigStore.getState().setActiveOutputStyle('concise');
        expect(useConfigStore.getState().outputStyle.activeStyleName).toBe('concise');
    });
});
