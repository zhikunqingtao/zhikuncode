import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ModelChip, PermissionModeChip } from './PromptComposerChips';
import { useModelStore } from '@/store/modelStore';
import { useNotificationStore } from '@/store/notificationStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useSessionStore } from '@/store/sessionStore';
import { useAppUiStore } from '@/store/appUiStore';
import { useBridgeStore } from '@/store/bridgeStore';

const { binding, connection, sendSetModel, sendSetPermissionMode } = vi.hoisted(() => ({
    binding: { bound: true },
    connection: { connected: true },
    sendSetModel: vi.fn(),
    sendSetPermissionMode: vi.fn(() => true),
}));

vi.mock('@/api/dispatch', () => ({
    isSessionBound: () => binding.bound,
    isSessionBindingReady: () => binding.bound,
    subscribeSessionBinding: () => () => {},
}));

vi.mock('@/api/stompClient', () => ({
    isWsConnected: () => connection.connected,
    sendSetModel,
    sendSetPermissionMode,
}));

describe('PromptComposerChips', () => {
    beforeEach(() => {
        sendSetModel.mockReset();
        sendSetPermissionMode.mockClear();
        sendSetPermissionMode.mockReturnValue(true);
        binding.bound = true;
        connection.connected = true;
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200 })));
        useBridgeStore.setState({ bridgeStatus: 'connected' });
        useAppUiStore.setState({ mobileNavTab: null });
        useSessionStore.setState({ sessionId: 'session-1', model: 'model-a' });
        usePermissionStore.setState({ permissionMode: 'default', pendingPermissions: [] });
        useModelStore.setState({
            models: [
                { id: 'model-a', displayName: 'Model A', supportsImages: false, maxImages: 0 },
                { id: 'model-b', displayName: 'Model B', supportsImages: false, maxImages: 0 },
            ],
            defaultModel: 'model-a',
            loaded: true,
            loading: false,
            error: null,
        });
        useNotificationStore.getState().clearAll();
    });
    afterEach(() => vi.unstubAllGlobals());

    describe('PermissionModeChip', () => {
        it('keeps the optimistic mode when the send succeeds', () => {
            render(<PermissionModeChip />);

            fireEvent.click(screen.getByRole('button', { name: '权限模式' }));
            fireEvent.click(screen.getByRole('button', { name: /完全访问/ }));

            expect(sendSetPermissionMode).toHaveBeenCalledWith('AUTO_APPROVE');
            expect(usePermissionStore.getState().permissionMode).toBe('auto_approve');
            expect(useNotificationStore.getState().notifications).toHaveLength(0);
        });

        it('rolls back and notifies when the send returns false', () => {
            sendSetPermissionMode.mockReturnValue(false);
            render(<PermissionModeChip />);

            fireEvent.click(screen.getByRole('button', { name: '权限模式' }));
            fireEvent.click(screen.getByRole('button', { name: /完全访问/ }));

            expect(usePermissionStore.getState().permissionMode).toBe('default');
            expect(useNotificationStore.getState().notifications)
                .toEqual(expect.arrayContaining([expect.objectContaining({
                    key: 'permission-mode-send-failed',
                    level: 'error',
                })]));
        });

        it('rolls back and notifies when the send throws', () => {
            sendSetPermissionMode.mockImplementation(() => {
                throw new Error('ws down');
            });
            render(<PermissionModeChip />);

            fireEvent.click(screen.getByRole('button', { name: '权限模式' }));
            fireEvent.click(screen.getByRole('button', { name: /完全访问/ }));

            expect(usePermissionStore.getState().permissionMode).toBe('default');
            expect(useNotificationStore.getState().notifications)
                .toEqual(expect.arrayContaining([expect.objectContaining({
                    key: 'permission-mode-send-failed',
                    level: 'error',
                })]));
        });

        it('rolls back and notifies without sending when no session is bound', () => {
            binding.bound = false;
            render(<PermissionModeChip />);

            fireEvent.click(screen.getByRole('button', { name: '权限模式' }));
            fireEvent.click(screen.getByRole('button', { name: /完全访问/ }));

            expect(sendSetPermissionMode).not.toHaveBeenCalled();
            expect(usePermissionStore.getState().permissionMode).toBe('default');
            expect(useNotificationStore.getState().notifications)
                .toEqual(expect.arrayContaining([expect.objectContaining({
                    key: 'permission-mode-no-session',
                    level: 'error',
                })]));
        });
    });

    describe('ModelChip', () => {
        it('keeps the optimistic model when the send succeeds', () => {
            render(<ModelChip />);

            fireEvent.change(screen.getByLabelText('模型选择'), { target: { value: 'model-b' } });

            expect(sendSetModel).toHaveBeenCalledWith('model-b');
            expect(useSessionStore.getState().model).toBe('model-b');
            expect(useNotificationStore.getState().notifications).toHaveLength(0);
            expect(fetch).not.toHaveBeenCalled();
        });

        it('disables model changes when disconnected', () => {
            connection.connected = false;
            useBridgeStore.setState({ bridgeStatus: 'disconnected' });
            render(<ModelChip />);

            expect(screen.getByLabelText('模型选择')).toBeDisabled();
            fireEvent.change(screen.getByLabelText('模型选择'), { target: { value: 'model-b' } });

            expect(sendSetModel).not.toHaveBeenCalled();
            expect(useSessionStore.getState().model).toBe('model-a');
            expect(fetch).not.toHaveBeenCalled();
        });

        it('rolls back and notifies when the send throws', () => {
            sendSetModel.mockImplementation(() => {
                throw new Error('ws down');
            });
            render(<ModelChip />);

            fireEvent.change(screen.getByLabelText('模型选择'), { target: { value: 'model-b' } });

            expect(useSessionStore.getState().model).toBe('model-a');
            expect(useNotificationStore.getState().notifications)
                .toEqual(expect.arrayContaining([expect.objectContaining({
                    key: 'model-send-failed',
                    level: 'error',
                })]));
        });

        it('disables model changes when no session is bound', () => {
            binding.bound = false;
            render(<ModelChip />);

            expect(screen.getByLabelText('模型选择')).toBeDisabled();
            fireEvent.change(screen.getByLabelText('模型选择'), { target: { value: 'model-b' } });

            expect(sendSetModel).not.toHaveBeenCalled();
            expect(useSessionStore.getState().model).toBe('model-a');
            expect(fetch).not.toHaveBeenCalled();
        });

        it('disables the mobile selector and closes its sheet when entering the session list', async () => {
            vi.stubGlobal('innerWidth', 390);
            vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
                matches: query === '(max-width: 767px)', addEventListener: vi.fn(), removeEventListener: vi.fn(),
            })));
            useAppUiStore.setState({ mobileNavTab: 'sessions' });
            render(<ModelChip mobile />);
            const selector = screen.getByRole('button', { name: /模型/ });
            expect(selector).toBeDisabled();
            act(() => useAppUiStore.getState().setMobileNavTab(null));
            expect(selector).toBeEnabled();
            fireEvent.click(selector);
            expect(screen.getByRole('dialog', { name: '选择模型' })).toBeInTheDocument();
            act(() => useAppUiStore.getState().setMobileNavTab('sessions'));
            expect(selector).toBeDisabled();
            await waitFor(() => expect(screen.queryByRole('dialog', { name: '选择模型' })).not.toBeInTheDocument());
            expect(useSessionStore.getState().model).toBe('model-a');
            expect(sendSetModel).not.toHaveBeenCalled();
            expect(fetch).not.toHaveBeenCalled();
        });

        it('keeps a choice from an open mobile sheet local after returning home', async () => {
            vi.stubGlobal('innerWidth', 390);
            vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
                matches: query === '(max-width: 767px)', addEventListener: vi.fn(), removeEventListener: vi.fn(),
            })));
            render(<ModelChip mobile />);
            const selector = screen.getByRole('button', { name: /模型/ });
            fireEvent.click(selector);
            const option = screen.getByRole('button', { name: 'Model B' });
            act(() => useSessionStore.setState({ sessionId: '' }));
            fireEvent.click(option);
            expect(selector).toBeEnabled();
            expect(useSessionStore.getState().model).toBe('model-b');
            await waitFor(() => expect(screen.queryByRole('dialog', { name: '选择模型' })).not.toBeInTheDocument());
            expect(sendSetModel).not.toHaveBeenCalled();
            expect(fetch).not.toHaveBeenCalled();
        });
    });
});
