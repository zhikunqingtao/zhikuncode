import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useEvidenceStore } from '@/store/evidenceStore';
import type { EvidenceBundle } from '@/store/evidenceStore';
import { EvidenceBundleView } from './EvidenceBundleView';

function bundle(overrides: Partial<EvidenceBundle> = {}): EvidenceBundle {
    return {
        bundleId: 'ev-1',
        sessionId: 'session-1',
        agentId: null,
        kind: 'journey',
        claim: '必须生成当前报告',
        verdict: 'verified',
        items: [{ id: 'step-1', type: 'command', summary: 'Step 1 [navigate]: ok', blobSha256: null, meta: { action: 'navigate', ok: true } }],
        createdAt: '2026-08-12T01:00:00Z',
        ...overrides,
    };
}

async function renderBundle(overrides: Partial<EvidenceBundle> = {}) {
    const evidence = bundle(overrides);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => evidence }));
    render(<EvidenceBundleView bundleId={evidence.bundleId} />);
    await screen.findByText(evidence.claim!);
}

describe('EvidenceBundleView', () => {
    afterEach(() => {
        cleanup();
        useEvidenceStore.setState({ currentBundle: null, loading: false, error: null });
        vi.unstubAllGlobals();
    });

    it('maps unavailable distinctly from unknown verdicts', async () => {
        await renderBundle({ verdict: 'unavailable', items: [] });

        expect(screen.getByText('Unavailable')).toBeInTheDocument();
        expect(screen.queryByText('Unknown')).not.toBeInTheDocument();
        expect(screen.getByText(/该次检查未执行，不能据此判定通过/)).toBeInTheDocument();
    });

    it('does not crash on unknown verdicts and shows neutral wording', async () => {
        await renderBundle({ verdict: 'something_new' });

        expect(screen.getByText('Unknown')).toBeInTheDocument();
        expect(screen.getByText(/范围未知/)).toBeInTheDocument();
    });

    it('scopes verified journey wording to the listed steps', async () => {
        await renderBundle();

        expect(screen.getByText('Verified')).toBeInTheDocument();
        expect(screen.getByText(/仅表示所列步骤在该次执行中通过/)).toBeInTheDocument();
    });

    it.each([
        ['empty journey', { items: [] }],
        ['unknown kind', { kind: 'unknown_kind' }],
    ])('keeps the recorded verdict but does not invent coverage for %s', async (_name, overrides) => {
        await renderBundle(overrides);

        expect(screen.getByText('Verified')).toHaveClass('text-ok');
        expect(screen.getByText('该记录标记为通过；检查覆盖范围未知')).toBeInTheDocument();
        expect(screen.queryByText(/仅表示.*通过/)).not.toBeInTheDocument();
    });

    it('preserves explicit failure evidence even without step items', async () => {
        await renderBundle({ verdict: 'failed', items: [] });

        expect(screen.getByText('Failed')).toBeInTheDocument();
        expect(screen.queryByText('Verified')).not.toBeInTheDocument();
    });
});
