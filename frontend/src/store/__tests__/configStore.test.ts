import { describe, it, expect, beforeEach } from 'vitest';
import { useConfigStore } from '../configStore';

describe('ConfigStore', () => {
    beforeEach(() => {
        // Reset to defaults
        useConfigStore.setState({
            theme: {
                mode: 'system',
                accentColor: '#3b82f6',
                fontSize: 'medium',
                fontFamily: 'monospace',
                borderRadius: 'md',
            },
            locale: 'zh-CN',
            autoCompact: { enabled: true, threshold: 80 },
            verbose: false,
            expandedView: false,
            outputStyle: { availableStyles: [], activeStyleName: null },
            defaultModel: 'claude-sonnet-4-20250514',
        });
    });

    it('should have default theme', () => {
        const { theme } = useConfigStore.getState();
        expect(theme.mode).toBe('system');
        expect(theme.accentColor).toBe('#3b82f6');
        expect(theme.fontSize).toBe('medium');
    });

    it('setTheme updates theme partially', () => {
        useConfigStore.getState().setTheme({ mode: 'dark' });
        const { theme } = useConfigStore.getState();
        expect(theme.mode).toBe('dark');
        expect(theme.accentColor).toBe('#3b82f6'); // unchanged
    });

    it('resetTheme restores defaults', () => {
        useConfigStore.getState().setTheme({ mode: 'dark', accentColor: '#ff0000' });
        useConfigStore.getState().resetTheme();
        const { theme } = useConfigStore.getState();
        expect(theme.mode).toBe('system');
        expect(theme.accentColor).toBe('#3b82f6');
    });

    it('setLocale updates locale', () => {
        useConfigStore.getState().setLocale('en-US');
        expect(useConfigStore.getState().locale).toBe('en-US');
    });

    it('default model is set', () => {
        expect(useConfigStore.getState().defaultModel).toBe('claude-sonnet-4-20250514');
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
