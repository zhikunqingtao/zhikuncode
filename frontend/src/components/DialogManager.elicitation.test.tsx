import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DialogManager } from './DialogManager';
import { bindSessionAndWait, dispatch, recoverPendingInteractions, resetBoundSession } from '@/api/dispatch';
import { useAppUiStore } from '@/store/appUiStore';
import { useDialogStore } from '@/store/dialogStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useSessionStore } from '@/store/sessionStore';

vi.mock('@/api/stompClient', () => ({ send: vi.fn(), sendToServer: vi.fn(() => true) }));
vi.mock('@/components/permission/PermissionDialog', () => ({ default: () => null }));
vi.mock('@/components/dialog/SettingsPanel', () => ({ SettingsPanel: () => null }));
vi.mock('@/components/dialog/KeyboardShortcutsDialog', () => ({ KeyboardShortcutsDialog: () => null }));
vi.mock('@/components/mcp/McpManagementPage', () => ({ McpManagementPage: () => null }));
vi.mock('@/components/memory/MemoryPage', () => ({ MemoryPage: () => null }));

const pendingQuestion = (multiSelect?: boolean) => ({
    protocolVersion: 2,
    interactionId: 'question-1',
    correlationKey: 'question-1',
    sessionId: 'session-1',
    runId: 'run-1',
    interactionType: 'elicitation',
    status: 'pending',
    prompt: {
        question: '哪些方向进入正篇？',
        options: [
            { value: 'A', label: 'A 安全授权' },
            { value: 'B', label: 'B 韧性工程' },
        ],
        ...(multiSelect === undefined ? {} : { multiSelect }),
    },
    allowedDecisions: ['answer', 'cancel'],
    scopeOptions: [],
    deliveryGeneration: 1,
    decisionDeadlineAt: Date.now() + 300_000,
    deliveryWindowEndsAt: Date.now() + 60_000,
    version: 1,
    serverNow: Date.now(),
});

const fetchMock = vi.fn();

beforeEach(async () => {
    resetBoundSession();
    useAppUiStore.setState({ elicitationDialog: null });
    useDialogStore.setState({ activeDialog: null });
    usePermissionStore.getState().clearPermissions();
    useSessionStore.setState({ sessionId: null });
    fetchMock.mockReset().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);

    const bound = bindSessionAndWait('session-1', payload => {
        dispatch({
            type: 'session_restored', ...payload, messages: [],
            metadata: { sessionId: 'session-1', model: 'test', permissionMode: 'DEFAULT', status: 'idle' },
        });
    });
    await bound;
    fetchMock.mockClear();
});

afterEach(() => {
    cleanup();
    resetBoundSession();
    vi.unstubAllGlobals();
});

async function expectSubmittedAnswer(answer: string | string[]) {
    fireEvent.click(screen.getByRole('button', { name: '确认' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
        '/api/interactions/question-1/decisions',
        expect.objectContaining({ method: 'POST' }),
    ));
    const [, init] = fetchMock.mock.calls.find(([url]) => url.endsWith('/decisions'))!;
    expect(JSON.parse(init.body)).toMatchObject({
        expectedVersion: 1, decision: 'answer', response: answer,
    });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
}

describe('elicitation selection through transport, dialog and decision submission', () => {
    it.each(['live', 'recovery'] as const)('keeps both selected options for a %s multiselect request', async source => {
        const interaction = pendingQuestion(true);
        if (source === 'recovery') {
            fetchMock.mockResolvedValueOnce({ ok: true, json: async () => [interaction] });
            await recoverPendingInteractions('session-1');
        } else {
            dispatch({ type: 'interaction_created', ...interaction } as never);
        }
        render(<DialogManager />);

        const first = screen.getByRole('button', { name: 'A 安全授权' });
        const second = screen.getByRole('button', { name: 'B 韧性工程' });
        fireEvent.click(first);
        fireEvent.click(second);
        expect(first).toHaveClass('bg-accent2-soft');
        expect(second).toHaveClass('bg-accent2-soft');
        await expectSubmittedAnswer(['A', 'B']);
    });

    it('toggles individual options and keeps a one-item multiselect answer as an array', async () => {
        dispatch({ type: 'interaction_created', ...pendingQuestion(true) } as never);
        render(<DialogManager />);
        const first = screen.getByRole('button', { name: 'A 安全授权' });
        const second = screen.getByRole('button', { name: 'B 韧性工程' });
        const confirm = screen.getByRole('button', { name: '确认' });
        expect(confirm).toBeDisabled();
        fireEvent.click(first);
        fireEvent.click(second);
        fireEvent.click(first);
        expect(first).not.toHaveClass('bg-accent2-soft');
        expect(second).toHaveClass('bg-accent2-soft');
        fireEvent.click(second);
        expect(confirm).toBeDisabled();
        fireEvent.click(first);

        // Delivery/deadline updates must not discard the current selection.
        act(() => dispatch({
            type: 'interaction_updated', interactionId: 'question-1',
            decisionDeadlineAt: Date.now() + 300_000, version: 1,
        } as never));
        expect(first).toHaveClass('bg-accent2-soft');
        await expectSubmittedAnswer(['A']);
    });

    it.each([false, undefined])('preserves single-select behavior when multiSelect is %s', async multiSelect => {
        dispatch({ type: 'interaction_created', ...pendingQuestion(multiSelect) } as never);
        render(<DialogManager />);
        const first = screen.getByRole('button', { name: 'A 安全授权' });
        const second = screen.getByRole('button', { name: 'B 韧性工程' });
        fireEvent.click(first);
        fireEvent.click(second);
        expect(first).not.toHaveClass('bg-accent2-soft');
        expect(second).toHaveClass('bg-accent2-soft');
        await expectSubmittedAnswer('B');
    });
});
