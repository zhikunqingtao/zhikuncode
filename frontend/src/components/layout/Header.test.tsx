import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Header } from '@/components/layout/Header';
import { ThemeProvider } from '@/components/theme/ThemeProvider';
import { useConfigStore } from '@/store/configStore';
import { useModelStore } from '@/store/modelStore';
import { useSessionStore } from '@/store/sessionStore';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';

describe('Header model selection', () => {
    beforeEach(() => {
        useWorkbenchViewStore.setState({ enabled: false });
        useConfigStore.setState({
            theme: { ...useConfigStore.getState().theme, mode: 'dark' },
        });
        useSessionStore.setState({ sessionId: null, model: null });
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        document.documentElement.classList.remove('light', 'dark', 'glass', 'system');
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
});
