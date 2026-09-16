import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Header } from '@/components/layout/Header';
import { ThemeProvider } from '@/components/theme/ThemeProvider';
import { useConfigStore } from '@/store/configStore';
import { useModelStore } from '@/store/modelStore';
import { useSessionStore } from '@/store/sessionStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { bindSessionAndWait, clearSessionBinding, markSessionBound } from '@/api/dispatch';
import * as stompClient from '@/api/stompClient';

describe('Header model selection', () => {
    beforeEach(() => {
        useWorkbenchViewStore.setState({ enabled: false });
        useConfigStore.setState({
            theme: { ...useConfigStore.getState().theme, mode: 'dark' },
        });
        useSessionStore.setState({ sessionId: null, model: null });
        useBridgeStore.setState({ bridgeStatus: 'connected' });
    });

    afterEach(() => {
        clearSessionBinding();
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
        document.documentElement.classList.remove('light', 'dark', 'glass', 'system');
    });

    it('enables only bound session details and never changes a default model', async () => {
        const send = vi.spyOn(stompClient, 'sendSetModel').mockImplementation(() => {});
        vi.spyOn(stompClient, 'isWsConnected').mockReturnValue(true);
        vi.stubGlobal('fetch', vi.fn());
        useModelStore.setState({
            loaded: true, loading: false, error: null, defaultModel: 'model-a',
            models: ['a', 'b'].map(id => ({ id: `model-${id}`, displayName: `Model ${id}`, supportsImages: false, maxImages: 0 })),
        });
        useConfigStore.setState({ defaultModel: 'original-default' });
        useSessionStore.setState({ sessionId: 'session-a', model: 'model-a' });
        clearSessionBinding();
        render(<Header />);
        const selector = screen.getByLabelText('模型选择');
        expect(selector).toBeDisabled();
        act(() => markSessionBound('session-a'));
        expect(selector).toBeEnabled();
        fireEvent.change(selector, { target: { value: 'model-b' } });
        expect(send).toHaveBeenCalledTimes(1);
        expect(send).toHaveBeenCalledWith('model-b');
        expect(useConfigStore.getState().defaultModel).toBe('original-default');
        expect(fetch).not.toHaveBeenCalled();

        let pending!: Promise<boolean>;
        act(() => { pending = bindSessionAndWait('session-b', () => true); });
        expect(selector).toBeDisabled();
        fireEvent.change(selector, { target: { value: 'model-a' } });
        act(() => clearSessionBinding());
        await expect(pending).resolves.toBe(false);
        act(() => markSessionBound('session-a'));
        expect(selector).toBeEnabled();
        fireEvent.click(screen.getAllByRole('button', { name: '返回首页' })[0]);
        expect(selector).toBeDisabled();
        fireEvent.change(selector, { target: { value: 'model-a' } });
        expect(send).toHaveBeenCalledTimes(1);
        expect(fetch).not.toHaveBeenCalled();
    });

    it.each(['system', 'unknown'])('safely renders unexpected runtime theme %s', mode => {
        useModelStore.setState({ loaded: true, loading: false, models: [], defaultModel: null });
        // 模拟未经持久化迁移的旧运行态，验证最后一层渲染防护。
        useConfigStore.setState({ theme: JSON.parse(JSON.stringify({ ...useConfigStore.getState().theme, mode })) });
        document.documentElement.classList.add('system');
        render(<ThemeProvider><Header /></ThemeProvider>);
        expect(screen.getByRole('button', { name: '外观设置' })).toBeVisible();
        expect(document.documentElement.classList.contains('system')).toBe(false);
        expect(document.documentElement.classList.contains('light') || document.documentElement.classList.contains('dark')).toBe(true);
    });

    it('shows a retry action when the provider model list fails to load', async () => {
        const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 503 });
        vi.stubGlobal('fetch', fetchMock);
        useModelStore.setState({
            models: [],
            defaultModel: null,
            loaded: false,
            loading: false,
            error: null,
        });

        render(<Header />);

        const retry = await screen.findByRole('button', {
            name: '重新加载模型列表',
        });
        expect(screen.getByRole('combobox')).toBeDisabled();
        expect(screen.getByRole('option', { name: '模型列表加载失败' }))
            .toBeInTheDocument();

        fireEvent.click(retry);
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    });

    it('presents an active mobile run as a distinct status label', () => {
        useModelStore.setState({
            loaded: true,
            loading: false,
            error: null,
            defaultModel: 'kimi-k3',
            models: [{ id: 'kimi-k3', displayName: 'Kimi K3', supportsImages: false, maxImages: 0 }],
        });
        useSessionStore.setState({ model: 'kimi-k3', status: 'streaming' });

        render(<Header />);

        expect(screen.getByRole('status')).toHaveTextContent('运行中');
    });
});
