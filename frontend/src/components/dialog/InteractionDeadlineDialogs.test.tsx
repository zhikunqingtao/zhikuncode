import { act, fireEvent, render, screen } from '@testing-library/react';
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
    scopeOptions: ['session'] as Array<'session' | 'workspace'>,
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
        expect(screen.getByRole('option', { name: 'This session' })).toBeInTheDocument();
        expect(screen.queryByRole('option', { name: 'This workspace' })).not.toBeInTheDocument();

        act(() => {
            vi.setSystemTime(new Date('2026-07-17T00:00:02Z'));
            vi.advanceTimersByTime(1_000);
        });
        expect(screen.getByRole('button', { name: /Allow/ })).toBeDisabled();
        fireEvent.keyDown(window, { key: 'y' });
        expect(onDecision).not.toHaveBeenCalled();
    });

    it('does not invent a remember scope when the server offers ONCE only', () => {
        const onDecision = vi.fn();
        render(<PermissionDialog
            request={{ ...permission(Date.now() + 30_000), scopeOptions: [] }}
            onDecision={onDecision}
        />);

        expect(screen.queryByLabelText(/Remember this decision/)).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /Allow/ }));
        expect(onDecision).toHaveBeenCalledWith(expect.objectContaining({
            decision: 'allow',
            remember: false,
        }));
        expect(onDecision.mock.calls[0][0]).not.toHaveProperty('scope');
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
