import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WorkbenchPendingAction } from '@/hooks/useSimpleWorkbenchData';
import { PendingActionsSummary } from './PendingActionsSummary';

function permission(id: string): WorkbenchPendingAction {
    return { interactionId: id, runId: 'root-1', interactionType: 'permission', prompt: { reason: `确认 ${id}` }, createdAt: '2026-08-12T00:00:00Z' };
}

describe('PendingActionsSummary', () => {
    afterEach(cleanup);

    it('uses the projected interaction id when selecting an action', () => {
        const onSelect = vi.fn();
        render(<PendingActionsSummary actions={[permission('permission-1')]} onSelect={onSelect} />);
        fireEvent.click(screen.getByRole('button', { name: /确认 permission-1/ }));
        expect(onSelect).toHaveBeenCalledWith('permission-1');
    });

    it('keeps later durable interactions queued', () => {
        render(<PendingActionsSummary actions={[permission('p1'), permission('p2')]} onSelect={vi.fn()} />);
        const buttons = screen.getAllByRole('button', { name: /需要确认一项操作/ });
        expect(buttons[0]).toBeEnabled();
        expect(buttons[1]).toBeDisabled();
    });
});
