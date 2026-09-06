import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Header } from '@/components/layout/Header';
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
