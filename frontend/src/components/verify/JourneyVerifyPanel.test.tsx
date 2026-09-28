import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { useJourneyVerifyStore } from '@/store/journeyVerifyStore';
import { JourneyVerifyPanel } from './JourneyVerifyPanel';

describe('JourneyVerifyPanel', () => {
    afterEach(() => {
        cleanup();
        useJourneyVerifyStore.getState().reset();
    });

    it.each([
        ['verified', 'Passed'],
        ['passed', 'Passed'],
        ['failed', 'Failed'],
        ['unavailable', 'Unavailable'],
        ['inconclusive', 'Inconclusive'],
        ['unknown_value', '范围未知'],
        ['', '范围未知'],
    ])('renders the explicit %s result received through the store', (verdict, label) => {
        render(<JourneyVerifyPanel />);
        act(() => useJourneyVerifyStore.getState().setResult(verdict, 'ev-result', ''));

        expect(screen.getByText(label)).toBeInTheDocument();
        if (label !== 'Failed') expect(screen.queryByText('Failed')).not.toBeInTheDocument();
        if (label !== 'Passed') expect(screen.queryByText('Passed')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: /Evidence: ev-result/ })).toBeInTheDocument();
    });

    it('prioritizes new progress over the retained result and states the limited scope', () => {
        render(<JourneyVerifyPanel />);
        act(() => useJourneyVerifyStore.getState().setRunning());
        expect(screen.getByText('Running...')).toBeInTheDocument();

        act(() => useJourneyVerifyStore.getState().setResult('verified', '', ''));
        expect(screen.getByText('Passed')).toBeInTheDocument();
        expect(screen.getByText(/范围有限：仅覆盖所列步骤在该次运行中的执行状态/)).toBeInTheDocument();

        act(() => useJourneyVerifyStore.getState().addStepProgress({
            stepIndex: 0, action: 'navigate', ok: true, durationMs: 10,
        }));
        expect(screen.getByText('Running...')).toBeInTheDocument();
        expect(screen.queryByText('Passed')).not.toBeInTheDocument();
    });
});
