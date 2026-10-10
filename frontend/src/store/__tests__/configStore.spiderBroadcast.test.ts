import { afterEach, describe, expect, it, vi } from 'vitest';

class ThemeBroadcastChannel {
    static instances: ThemeBroadcastChannel[] = [];
    onmessage: ((event: MessageEvent) => void) | null = null;
    postMessage = vi.fn();
    close = vi.fn();
    constructor(readonly name: string) { ThemeBroadcastChannel.instances.push(this); }
}

afterEach(() => {
    ThemeBroadcastChannel.instances = [];
    vi.unstubAllGlobals();
    vi.resetModules();
});

describe('蜘蛛主题跨标签同步', () => {
    it('真实 ConfigStore 接收蜘蛛主题仍保留动作，后续可切回其他主题且不回声广播', async () => {
        vi.resetModules();
        vi.stubGlobal('BroadcastChannel', ThemeBroadcastChannel);
        const { useConfigStore } = await import('../configStore');
        const channel = ThemeBroadcastChannel.instances.find(item => item.name === 'ai-coder-config-broadcast')!;
        expect(channel).toBeDefined();
        const before = useConfigStore.getState();
        channel.postMessage.mockClear();
        channel.onmessage?.(new MessageEvent('message', { data: {
            type: 'STATE_UPDATE',
            senderId: 'another-browser-tab',
            state: {
                theme: { ...before.theme, mode: 'spider', accentColor: '#C9578A' },
                themePreferenceSet: true,
            },
        } }));
        expect(useConfigStore.getState().theme.mode).toBe('spider');
        expect(useConfigStore.getState().setTheme).toBe(before.setTheme);
        expect(useConfigStore.getState().loadConfig).toBe(before.loadConfig);
        expect(useConfigStore.getState().locale).toBe(before.locale);
        expect(channel.postMessage).not.toHaveBeenCalled();
        expect(JSON.parse(localStorage.getItem('ai-coder-config')!).state.theme.mode).toBe('spider');

        useConfigStore.getState().setTheme({ mode: 'dark' });
        expect(useConfigStore.getState().theme).toMatchObject({ mode: 'dark', accentColor: '#C9578A' });
        expect(channel.postMessage).toHaveBeenCalledOnce();
        const outgoing = channel.postMessage.mock.calls[0][0];
        expect(outgoing.state).not.toHaveProperty('setTheme');
        expect(outgoing.state).not.toHaveProperty('sessionId');
        expect(outgoing.state).not.toHaveProperty('bridgeStatus');
        expect(outgoing.state.theme.mode).toBe('dark');
    });
});
