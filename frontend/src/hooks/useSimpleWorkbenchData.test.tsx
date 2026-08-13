import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useSimpleWorkbenchData } from './useSimpleWorkbenchData';

function jsonResponse(body: unknown, status = 200) {
    return Promise.resolve({ ok: status >= 200 && status < 300, status, json: async () => body });
}

const session = (id: string, title: string) => ({
    sessionId: id, model: 'test', workingDir: `/workspace/${id}`, title, status: 'idle', summary: null,
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
});
const current = (id: string) => ({
    correlationMode: 'EXACT', requestMessageId: 'u-1', resultMessageId: 'a-1',
    rootRun: { id: 'r-1', sessionId: id, parentRunId: null, status: 'COMPLETED', agentType: 'query', startedAt: null, finishedAt: null, updatedAt: '2026-01-01T00:00:00Z', verificationStatus: 'NOT_REQUESTED', errorSummary: null },
    request: { messageId: 'u-1', text: '新要求', timestamp: '2026-01-01T00:00:00Z' },
    result: { messageId: 'a-1', text: '新结果', timestamp: '2026-01-01T00:01:00Z' },
    structuredSummary: { conclusion: '新结果', completed: [], issues: [], nextSteps: [] },
    delivery: { manifests: [], files: [], totalFiles: 0, primaryArtifactPath: null },
    verification: { businessCriteria: [], technicalChecks: [], evidence: [], overallStatus: 'NOT_VERIFIED' },
    pendingActionCount: 0, pendingActions: [], activities: [], previousAvailableDelivery: null, currentFailure: null,
});

describe('useSimpleWorkbenchData', () => {
    afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

    it('loads the single authoritative current projection', async () => {
        const fetchMock = vi.fn((input: RequestInfo | URL) => {
            const url = String(input);
            if (url === '/api/sessions/session-a') return jsonResponse(session('session-a', 'Demo'));
            if (url === '/api/sessions/session-a/workbench/current') return jsonResponse(current('session-a'));
            throw new Error(`Unexpected request: ${url}`);
        });
        vi.stubGlobal('fetch', fetchMock);
        const { result } = renderHook(() => useSimpleWorkbenchData('session-a'));
        await waitFor(() => expect(result.current.current.loading).toBe(false));
        expect(result.current.current.data?.request?.text).toBe('新要求');
        expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining('/api/runs/'), expect.anything());
        expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining('/api/evidence/'), expect.anything());
    });

    it('does not turn a projection failure into an empty successful state', async () => {
        vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => String(input).endsWith('/workbench/current')
            ? jsonResponse({}, 503) : jsonResponse(session('session-a', 'Demo'))));
        const { result } = renderHook(() => useSimpleWorkbenchData('session-a'));
        await waitFor(() => expect(result.current.current.loading).toBe(false));
        expect(result.current.current.error).toBe('HTTP 503');
        expect(result.current.current.data).toBeNull();
    });

    it('ignores a late response from the previously selected Session', async () => {
        let resolveOld!: (value: Awaited<ReturnType<typeof jsonResponse>>) => void;
        const old = new Promise<Awaited<ReturnType<typeof jsonResponse>>>(resolve => { resolveOld = resolve; });
        vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
            const url = String(input);
            if (url === '/api/sessions/session-a') return old;
            if (url === '/api/sessions/session-b') return jsonResponse(session('session-b', 'New'));
            if (url.endsWith('/workbench/current')) return jsonResponse(current(url.includes('session-b') ? 'session-b' : 'session-a'));
            throw new Error(url);
        }));
        const { result, rerender } = renderHook(({ sessionId }) => useSimpleWorkbenchData(sessionId), { initialProps: { sessionId: 'session-a' } });
        rerender({ sessionId: 'session-b' });
        await waitFor(() => expect(result.current.session.data?.title).toBe('New'));
        resolveOld(await jsonResponse(session('session-a', 'Old')));
        await Promise.resolve();
        expect(result.current.session.data?.title).toBe('New');
    });
});
