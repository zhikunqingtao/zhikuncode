import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ModelChip, PermissionModeChip } from './PromptComposerChips';
import { useModelStore } from '@/store/modelStore';
import { useNotificationStore } from '@/store/notificationStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useSessionStore } from '@/store/sessionStore';

const { binding, connection, sendSetModel, sendSetPermissionMode } = vi.hoisted(() => ({
    binding: { bound: true },
    connection: { connected: true },
    sendSetModel: vi.fn(),
    sendSetPermissionMode: vi.fn(() => true),
}));

vi.mock('@/api/dispatch', () => ({
    isSessionBound: () => binding.bound,
}));

vi.mock('@/api/stompClient', () => ({
    isWsConnected: () => connection.connected,
    sendSetModel,
    sendSetPermissionMode,
}));

describe('PromptComposerChips', () => {
    beforeEach(() => {
        sendSetModel.mockClear();
        sendSetPermissionMode.mockClear();
        sendSetPermissionMode.mockReturnValue(true);
        binding.bound = true;
        connection.connected = true;
        // ModelChip 切换会经 saveConfig 持久化 defaultModel（fetch PUT /api/config）
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200 })));
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
        });

        it('rolls back and notifies when the socket is disconnected', () => {
            connection.connected = false;
            render(<ModelChip />);

            fireEvent.change(screen.getByLabelText('模型选择'), { target: { value: 'model-b' } });

            expect(sendSetModel).not.toHaveBeenCalled();
            expect(useSessionStore.getState().model).toBe('model-a');
            expect(useNotificationStore.getState().notifications)
                .toEqual(expect.arrayContaining([expect.objectContaining({
                    key: 'model-send-failed',
                    level: 'error',
                })]));
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

        it('rolls back and notifies without sending when no session is bound', () => {
            binding.bound = false;
            render(<ModelChip />);

            fireEvent.change(screen.getByLabelText('模型选择'), { target: { value: 'model-b' } });

            expect(sendSetModel).not.toHaveBeenCalled();
            expect(useSessionStore.getState().model).toBe('model-a');
            expect(useNotificationStore.getState().notifications)
                .toEqual(expect.arrayContaining([expect.objectContaining({
                    key: 'model-no-session',
                    level: 'error',
                })]));
        });
    });
});
