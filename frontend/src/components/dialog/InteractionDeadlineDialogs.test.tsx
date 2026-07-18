import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import PermissionDialog from '@/components/permission/PermissionDialog';
import { ElicitationDialog } from '@/components/dialog/ElicitationDialog';

vi.mock('@/components/message', () => ({
    CodeBlock: ({ code }: { code: string }) => <pre>{code}</pre>,
}));

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
});

const permission = (deadline?: number) => ({
    interactionId: 'interaction-1',
    version: 0,
    toolUseId: 'tool-1',
    toolName: 'FileWrite',
    input: { file_path: 'src/App.tsx' },
    riskLevel: 'low' as const,
    reason: 'Write a workspace file',
    decisionDeadlineAt: deadline,
    deliveryGeneration: 1,
    operationHash: 'op-1',
    options: [
        { optionId: 'allow_once', decision: 'allow' as const, scope: 'once' as const },
        { optionId: 'allow_session', decision: 'allow' as const, scope: 'session' as const },
        { optionId: 'deny', decision: 'deny' as const, scope: 'once' as const },
    ],
    scopeOptions: ['session'] as Array<'run' | 'session' | 'workspace'>,
});

describe('durable interaction deadlines', () => {
    it('does not allow a permission decision before the server confirms delivery', () => {
        const onDecision = vi.fn();
        render(<PermissionDialog request={permission()} onDecision={onDecision} />);

        expect(screen.getByText('Waiting for delivery confirmation')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /Allow/ })).toBeDisabled();
        fireEvent.keyDown(window, { key: 'y' });
        expect(onDecision).not.toHaveBeenCalled();
    });

    it('renders only server-authorized scopes and never decides after expiry', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-07-17T00:00:00Z'));
        const onDecision = vi.fn();
        render(<PermissionDialog
            request={permission(Date.now() + 1_000)}
            onDecision={onDecision}
        />);

        fireEvent.click(screen.getByLabelText(/Remember this decision/));
        expect(screen.getByRole('option', { name: 'This session and child agents' })).toBeInTheDocument();
        expect(screen.queryByRole('option', { name: 'This workspace' })).not.toBeInTheDocument();

        act(() => {
            vi.setSystemTime(new Date('2026-07-17T00:00:02Z'));
            vi.advanceTimersByTime(1_000);
        });
        expect(screen.getByRole('button', { name: /Allow/ })).toBeDisabled();
        fireEvent.keyDown(window, { key: 'y' });
        expect(onDecision).not.toHaveBeenCalled();
    });

    it('does not invent a remember scope when the server offers ONCE only', async () => {
        const onDecision = vi.fn().mockResolvedValue(undefined);
        render(<PermissionDialog
            request={{ ...permission(Date.now() + 30_000), scopeOptions: [] }}
            onDecision={onDecision}
        />);

        expect(screen.queryByLabelText(/Remember this decision/)).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /Allow/ }));
        await waitFor(() => expect(onDecision).toHaveBeenCalledWith(expect.objectContaining({
            decision: 'allow',
            remember: false,
        })));
        expect(onDecision.mock.calls[0][0]).not.toHaveProperty('scope');
    });

    it('renders the explicit run scope and keeps session as the inheritance-friendly default', () => {
        const onDecision = vi.fn();
        const request = permission(Date.now() + 30_000);
        render(<PermissionDialog
            request={{
                ...request,
                scopeOptions: ['run', 'session'],
                options: [
                    ...request.options,
                    { optionId: 'allow_run', decision: 'allow' as const, scope: 'run' as const },
                ],
            }}
            onDecision={onDecision}
        />);

        fireEvent.click(screen.getByLabelText(/Remember this decision/));
        expect(screen.getByRole('option', { name: 'Only this agent/run' })).toBeInTheDocument();
        expect(screen.getByRole('combobox')).toHaveValue('session');
    });

    it('re-enables a pending permission after a transient decision failure', async () => {
        const onDecision = vi.fn()
            .mockRejectedValueOnce(new Error('INTERACTION_DECISION_FAILED_503'))
            .mockResolvedValueOnce(undefined);
        render(<PermissionDialog request={permission(Date.now() + 30_000)} onDecision={onDecision} />);

        fireEvent.click(screen.getByRole('button', { name: /Allow/ }));
        expect(await screen.findByRole('alert')).toHaveTextContent('INTERACTION_DECISION_FAILED_503');
        await waitFor(() => expect(screen.getByRole('button', { name: /Allow/ })).toBeEnabled());
        fireEvent.click(screen.getByRole('button', { name: /Allow/ }));
        await waitFor(() => expect(onDecision).toHaveBeenCalledTimes(2));
    });

    it('elicitation expiry disables actions without manufacturing a cancel decision', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-07-17T00:00:00Z'));
        const onSubmit = vi.fn();
        const onCancel = vi.fn();
        render(<ElicitationDialog
            requestId="question-1"
            question="Continue?"
            inputType="confirm"
            decisionDeadlineAt={Date.now() + 1_000}
            onSubmit={onSubmit}
            onCancel={onCancel}
        />);

        act(() => {
            vi.setSystemTime(new Date('2026-07-17T00:00:02Z'));
            vi.advanceTimersByTime(1_000);
        });
        expect(screen.getByText('请求已到期，等待服务端确认')).toBeInTheDocument();
        fireEvent.keyDown(window, { key: 'Escape' });
        expect(onCancel).not.toHaveBeenCalled();
        expect(onSubmit).not.toHaveBeenCalled();
    });
});
