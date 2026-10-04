import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
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

interface Deferred<T> {
    promise: Promise<T>;
    resolve: (value: T) => void;
    reject: (error: Error) => void;
}

function deferred<T>(): Deferred<T> {
    let resolve!: Deferred<T>['resolve'];
    let reject!: Deferred<T>['reject'];
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
}

interface PendingFetch {
    url: string;
    signal: AbortSignal | null | undefined;
    response: Deferred<Response>;
}

function controlFetch() {
    const requests: PendingFetch[] = [];
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const response = deferred<Response>();
        requests.push({ url: String(input), signal: init?.signal, response });
        // Deliberately do not reject on abort: late completions must also be ignored.
        return response.promise;
    });
    vi.stubGlobal('fetch', fetchMock);
    return { requests, fetchMock };
}

function jsonResponse(value: unknown): Response {
    return { ok: true, json: async () => value } as Response;
}

async function reply(request: PendingFetch, value: unknown) {
    await act(async () => { request.response.resolve(jsonResponse(value)); });
}

describe('EvidenceBundleView', () => {
    afterEach(() => {
        cleanup();
        useEvidenceStore.setState({ currentBundle: null, loading: false, error: null });
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
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

    it('uses the image preview endpoint and displays stored screenshot metadata', async () => {
        await renderBundle({ items: [{
            id: 'image-1', type: 'screenshot', summary: 'Click evidence', blobSha256: 'abc123',
            meta: { action: 'click', method: 'playwright', screenshotStatus: 'stored', mime: 'image/jpeg', bytes: 1234 },
        }] });

        expect(screen.getByRole('img', { name: 'Click evidence' })).toHaveAttribute('src', '/api/evidence/blob/abc123?preview=true');
        expect(screen.getByText('执行方式：playwright')).toBeInTheDocument();
        expect(screen.getByText(/截图已归档.*image\/jpeg.*1234 字节/)).toBeInTheDocument();
        expect(screen.getByText('Verified')).toBeInTheDocument();
    });

    it('preserves a legacy screenshot source and ordinary blob media URLs', async () => {
        await renderBundle({ items: [
            { id: 'old-image', type: 'screenshot', summary: 'Legacy image', blobSha256: null, meta: { dataUrl: 'data:image/png;base64,AA==' } },
            { id: 'video', type: 'video', summary: 'Existing video', blobSha256: 'video123', meta: {} },
        ] });

        expect(screen.getByRole('img', { name: 'Legacy image' })).toHaveAttribute('src', 'data:image/png;base64,AA==');
        fireEvent.click(screen.getByRole('button', { name: /Videos/ }));
        expect(document.querySelector('video')).toHaveAttribute('src', '/api/evidence/blob/video123');
    });

    it('reports legacy missing screenshots and never infers a native interaction', async () => {
        await renderBundle({ items: [{
            id: 'old-image', type: 'screenshot', summary: 'Legacy click', blobSha256: null, meta: { action: 'click', ok: true },
        }] });

        expect(screen.getByText('执行方式未记录')).toBeInTheDocument();
        expect(screen.getByText('截图缺失：原因未记录')).toBeInTheDocument();
        expect(screen.queryByRole('img')).not.toBeInTheDocument();
        expect(screen.getByText('Verified')).toBeInTheDocument();
    });

    it('handles a missing or unreadable blob without changing the recorded verdict', async () => {
        await renderBundle({ items: [{
            id: 'image-1', type: 'screenshot', summary: 'Missing file', blobSha256: 'missing',
            meta: { action: 'navigate', screenshotStatus: 'stored' },
        }] });
        fireEvent.error(screen.getByRole('img', { name: 'Missing file' }));

        expect(screen.getByText('截图不可读取：文件缺失、损坏或加载失败')).toBeInTheDocument();
        expect(screen.getByText('归档记录存在，当前无法读取')).toBeInTheDocument();
        expect(screen.queryByText(/截图已归档/)).not.toBeInTheDocument();
        expect(screen.queryByRole('img')).not.toBeInTheDocument();
        expect(screen.getByText('Verified')).toBeInTheDocument();
    });

    it('shows fallback limitations and missing screenshot reasons on command steps', async () => {
        await renderBundle({ items: [{
            id: 'input-1', type: 'command', summary: 'Input succeeded', blobSha256: null,
            meta: { action: 'type', ok: true, method: 'js_fallback', warning: 'Native input timed out', screenshotStatus: 'missing', screenshotReason: 'Page closed before capture' },
        }] });

        expect(screen.getByText('执行方式：js_fallback')).toBeInTheDocument();
        expect(screen.getByText('Native input timed out')).toBeInTheDocument();
        expect(screen.getByText(/不能单独证明原生用户交互可用/)).toBeInTheDocument();
        expect(screen.getByText('截图缺失：Page closed before capture')).toBeInTheDocument();
        expect(screen.getByText('Verified')).toBeInTheDocument();
    });

    it.each(['invalid', 'limit_exceeded'])('shows a %s screenshot gap without hiding the step', async (status) => {
        await renderBundle({ items: [{
            id: 'input-1', type: 'command', summary: 'Input step retained', blobSha256: null,
            meta: { action: 'type', screenshotStatus: status, screenshotReason: 'Explicit archive reason' },
        }] });

        expect(screen.getByText('Input step retained')).toBeInTheDocument();
        expect(screen.getByText('执行方式未记录')).toBeInTheDocument();
        expect(screen.getByText(/Explicit archive reason/)).toBeInTheDocument();
    });

    it.each([
        ['reason_not_recorded', '原因未记录'],
        ['invalid_base64', '截图编码无效'],
        ['single_image_limit_5_mib', '单张截图超过 5 MiB 限额'],
        ['journey_image_limit_20_mib', '本次 Journey 截图归档剩余额度不足（总限额 20 MiB）'],
        ['invalid_or_unsupported_image', '截图无效或不是支持的 JPEG/PNG 图片'],
    ])('explains the screenshot reason code %s', async (reason, message) => {
        await renderBundle({ items: [{
            id: 'step-1', type: 'command', summary: 'Capture gap', blobSha256: null,
            meta: { action: 'navigate', screenshotStatus: 'missing', screenshotReason: reason },
        }] });

        expect(screen.getByText(`截图缺失：${message}`)).toBeInTheDocument();
        expect(screen.queryByText(reason, { exact: true })).not.toBeInTheDocument();
    });

    it('shows fallback limitations with a screenshot and isolates image failures', async () => {
        await renderBundle({ items: [
            { id: 'image-1', type: 'screenshot', summary: 'Assisted input', blobSha256: 'abc', meta: { action: 'type', method: 'js_fallback', warning: 'Native input failed', screenshotStatus: 'stored' } },
            { id: 'image-2', type: 'screenshot', summary: 'Next step', blobSha256: 'def', meta: { action: 'navigate', screenshotStatus: 'stored' } },
        ] });
        fireEvent.error(screen.getByRole('img', { name: 'Assisted input' }));

        expect(screen.getByText('Native input failed')).toBeInTheDocument();
        expect(screen.getByText(/不能单独证明原生用户交互可用/)).toBeInTheDocument();
        expect(screen.getByRole('img', { name: 'Next step' })).toHaveAttribute('src', '/api/evidence/blob/def?preview=true');
    });

    it('does not add journey warnings to ordinary command evidence', async () => {
        await renderBundle({ kind: 'tests', items: [{
            id: 'command-1', type: 'command', summary: 'Run tests', blobSha256: null,
            meta: { command: 'npm test', stdout: 'passed', exitCode: 0 },
        }] });

        expect(screen.getByText('passed')).toBeInTheDocument();
        expect(screen.queryByText(/执行方式|截图/)).not.toBeInTheDocument();
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

    it('isolates B failure while keeping cached A and retries B when reopened', async () => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<EvidenceBundleView bundleId="A" />);
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'Evidence A' }));

        view.rerender(<EvidenceBundleView bundleId="B" />);
        expect(screen.queryByText('Evidence A')).not.toBeInTheDocument();
        expect(screen.getByText(/Loading evidence bundle/)).toBeInTheDocument();
        await act(async () => { requests[1].response.reject(new Error('B unavailable')); });

        expect(screen.getByText(/Failed to load evidence bundle:.*B unavailable/)).toBeInTheDocument();
        expect(screen.queryByText('Evidence A')).not.toBeInTheDocument();

        view.rerender(<EvidenceBundleView bundleId="A" />);
        expect(screen.getByText('Evidence A')).toBeInTheDocument();
        expect(screen.queryByText(/Failed to load evidence bundle/)).not.toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);

        view.unmount();
        render(<EvidenceBundleView bundleId="B" />);
        expect(fetchMock).toHaveBeenCalledTimes(3);
        await reply(requests[2], bundle({ bundleId: 'B', claim: 'Recovered B' }));
        expect(screen.getByText('Recovered B')).toBeInTheDocument();
    });

    it.each(['success', 'error'] as const)('ignores the original A %s after switching A → B → A', async (outcome) => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<EvidenceBundleView bundleId="A" />);
        view.rerender(<EvidenceBundleView bundleId="B" />);
        view.rerender(<EvidenceBundleView bundleId="A" />);
        expect(requests.map((request) => request.url)).toEqual(['/api/evidence/A', '/api/evidence/B', '/api/evidence/A']);
        await reply(requests[2], bundle({ bundleId: 'A', claim: 'Current A' }));

        await act(async () => {
            if (outcome === 'success') requests[0].response.resolve(jsonResponse(bundle({ bundleId: 'A', claim: 'Obsolete A' })));
            else requests[0].response.reject(new Error('Obsolete A error'));
        });
        await reply(requests[1], bundle({ bundleId: 'B', claim: 'Obsolete B' }));

        expect(screen.getByText('Current A')).toBeInTheDocument();
        expect(screen.queryByText(/Obsolete/)).not.toBeInTheDocument();
        expect(screen.queryByText(/Failed to load evidence bundle/)).not.toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(3);
    });

    it('reuses successful A when returning from pending B and ignores late B', async () => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<EvidenceBundleView bundleId="A" />);
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'Cached A' }));
        view.rerender(<EvidenceBundleView bundleId="B" />);
        view.rerender(<EvidenceBundleView bundleId="A" />);

        expect(screen.getByText('Cached A')).toBeInTheDocument();
        expect(screen.queryByText(/Loading evidence bundle/)).not.toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);
        await reply(requests[1], bundle({ bundleId: 'B', claim: 'Obsolete B' }));
        expect(screen.queryByText('Obsolete B')).not.toBeInTheDocument();

        view.unmount();
        render(<EvidenceBundleView bundleId="A" />);
        expect(screen.getByText('Cached A')).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it.each(['online', 'offline'] as const)('reuses the same successful bundle after remounting %s', async (connection) => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<EvidenceBundleView bundleId="A" />);
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'Saved evidence' }));
        view.unmount();
        if (connection === 'offline') fetchMock.mockRejectedValue(new TypeError('Network unavailable'));

        render(<EvidenceBundleView bundleId="A" />);

        expect(screen.getByText('Saved evidence')).toBeInTheDocument();
        expect(screen.queryByText(/Loading evidence bundle|Failed to load evidence bundle/)).not.toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('caches only the most recent successful bundle', async () => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<EvidenceBundleView bundleId="A" />);
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'Evidence A' }));
        view.rerender(<EvidenceBundleView bundleId="B" />);
        expect(screen.queryByText('Evidence A')).not.toBeInTheDocument();
        await reply(requests[1], bundle({ bundleId: 'B', claim: 'Evidence B' }));

        view.rerender(<EvidenceBundleView bundleId="A" />);

        expect(screen.queryByText(/Evidence A|Evidence B/)).not.toBeInTheDocument();
        expect(screen.getByText(/Loading evidence bundle/)).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(3);
        await reply(requests[2], bundle({ bundleId: 'A', claim: 'Evidence A' }));
        expect(screen.getByText('Evidence A')).toBeInTheDocument();
    });

    it('fetches again after the successful bundle cache is explicitly cleared', async () => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<EvidenceBundleView bundleId="A" />);
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'Evidence A' }));
        view.unmount();
        act(() => { useEvidenceStore.getState().clearCurrentBundle(); });

        render(<EvidenceBundleView bundleId="A" />);

        expect(screen.queryByText('Evidence A')).not.toBeInTheDocument();
        expect(screen.getByText(/Loading evidence bundle/)).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);
        await reply(requests[1], bundle({ bundleId: 'A', claim: 'Evidence A' }));
        expect(screen.getByText('Evidence A')).toBeInTheDocument();
    });

    it('ignores old JSON parsing that finishes after the target changes', async () => {
        const { requests, fetchMock } = controlFetch();
        const oldJson = deferred<unknown>();
        const view = render(<EvidenceBundleView bundleId="A" />);
        await act(async () => {
            requests[0].response.resolve({ ok: true, json: () => oldJson.promise } as Response);
        });
        view.rerender(<EvidenceBundleView bundleId="B" />);
        await reply(requests[1], bundle({ bundleId: 'B', claim: 'Current B' }));
        await act(async () => { oldJson.resolve(bundle({ bundleId: 'A', claim: 'Late parsed A' })); });

        expect(screen.getByText('Current B')).toBeInTheDocument();
        expect(screen.queryByText('Late parsed A')).not.toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);

        view.unmount();
        render(<EvidenceBundleView bundleId="B" />);
        expect(screen.getByText('Current B')).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('keeps two active viewers independent without response-triggered refetches', async () => {
        const { requests, fetchMock } = controlFetch();
        render(<>
            <section aria-label="First viewer"><EvidenceBundleView bundleId="A" /></section>
            <section aria-label="Second viewer"><EvidenceBundleView bundleId="B" /></section>
        </>);
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'First evidence' }));
        await reply(requests[1], bundle({ bundleId: 'B', claim: 'Second evidence' }));

        const first = within(screen.getByRole('region', { name: 'First viewer' }));
        const second = within(screen.getByRole('region', { name: 'Second viewer' }));
        expect(first.getByText('First evidence')).toBeInTheDocument();
        expect(first.queryByText('Second evidence')).not.toBeInTheDocument();
        expect(second.getByText('Second evidence')).toBeInTheDocument();
        expect(second.queryByText('First evidence')).not.toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it.each(['success first', 'failure first'] as const)('isolates concurrent success and failure (%s)', async (order) => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<>
            <section aria-label="Successful viewer"><EvidenceBundleView bundleId="A" /></section>
            <section aria-label="Failed viewer"><EvidenceBundleView bundleId="B" /></section>
        </>);
        const succeed = () => reply(requests[0], bundle({ bundleId: 'A', claim: 'Available A' }));
        const fail = () => act(async () => { requests[1].response.reject(new Error('B unavailable')); });
        if (order === 'success first') {
            await succeed();
            await fail();
        } else {
            await fail();
            await succeed();
        }

        const successful = within(screen.getByRole('region', { name: 'Successful viewer' }));
        const failed = within(screen.getByRole('region', { name: 'Failed viewer' }));
        expect(successful.getByText('Available A')).toBeInTheDocument();
        expect(successful.queryByText(/Failed to load evidence bundle/)).not.toBeInTheDocument();
        expect(failed.getByText(/Failed to load evidence bundle:.*B unavailable/)).toBeInTheDocument();
        expect(failed.queryByText('Available A')).not.toBeInTheDocument();

        view.unmount();
        render(<EvidenceBundleView bundleId="A" />);
        expect(screen.getByText('Available A')).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('aborts on unmount and ignores a late response after a new viewer mounts', async () => {
        const { requests, fetchMock } = controlFetch();
        const first = render(<EvidenceBundleView bundleId="A" />);
        first.unmount();
        const second = render(<EvidenceBundleView bundleId="A" />);
        await reply(requests[1], bundle({ bundleId: 'A', claim: 'New mount' }));
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'Unmounted result' }));

        expect(requests[0].signal?.aborted).toBe(true);
        expect(screen.getByText('New mount')).toBeInTheDocument();
        expect(screen.queryByText('Unmounted result')).not.toBeInTheDocument();

        second.unmount();
        render(<EvidenceBundleView bundleId="A" />);
        expect(screen.getByText('New mount')).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('invalidates the first StrictMode effect without cancelling the active effect', async () => {
        const { requests } = controlFetch();
        render(<StrictMode><EvidenceBundleView bundleId="A" /></StrictMode>);
        expect(requests).toHaveLength(2);
        await reply(requests[1], bundle({ bundleId: 'A', claim: 'Active effect' }));
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'Discarded effect' }));

        expect(requests[0].signal?.aborted).toBe(true);
        expect(requests[1].signal?.aborted).toBe(false);
        expect(screen.getByText('Active effect')).toBeInTheDocument();
        expect(screen.queryByText('Discarded effect')).not.toBeInTheDocument();
    });

    it.each(['null', 'wrong ID', 'invalid JSON'] as const)('rejects a %s response without losing valid cached evidence and retries on reopen', async (failure) => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<EvidenceBundleView bundleId="B" />);
        await reply(requests[0], bundle({ bundleId: 'B', claim: 'Valid B' }));
        view.rerender(<EvidenceBundleView bundleId="A" />);
        await act(async () => {
            requests[1].response.resolve(failure === 'invalid JSON'
                ? { ok: true, json: async (): Promise<unknown> => { throw new SyntaxError('Invalid JSON'); } } as Response
                : jsonResponse(failure === 'null' ? null : bundle({ bundleId: 'B', claim: 'Wrong identity' })));
        });

        expect(screen.getByText(/Failed to load evidence bundle/)).toBeInTheDocument();
        expect(screen.queryByText('Wrong identity')).not.toBeInTheDocument();
        expect(screen.queryByText('Valid B')).not.toBeInTheDocument();

        view.rerender(<EvidenceBundleView bundleId="B" />);
        expect(screen.getByText('Valid B')).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);

        view.unmount();
        render(<EvidenceBundleView bundleId="A" />);
        expect(fetchMock).toHaveBeenCalledTimes(3);
        await reply(requests[2], bundle({ bundleId: 'A', claim: 'Recovered A' }));
        expect(screen.getByText('Recovered A')).toBeInTheDocument();
    });

    it.each([404, 500])('rejects HTTP %i without replacing the successful cache and retries on reopen', async (status) => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<EvidenceBundleView bundleId="A" />);
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'Saved A' }));
        view.rerender(<EvidenceBundleView bundleId="B" />);
        const json = vi.fn().mockResolvedValue(bundle({ bundleId: 'B', claim: 'Rejected response body' }));
        await act(async () => {
            requests[1].response.resolve({ ok: false, status, json } as unknown as Response);
        });

        expect(screen.getByText(`Failed to load evidence bundle: HTTP ${status}`)).toBeInTheDocument();
        expect(screen.queryByText(/Saved A|Rejected response body/)).not.toBeInTheDocument();
        expect(json).not.toHaveBeenCalled();
        view.rerender(<EvidenceBundleView bundleId="A" />);
        expect(screen.getByText('Saved A')).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(2);

        view.rerender(<EvidenceBundleView bundleId="B" />);
        expect(screen.queryByText(/Saved A|Failed to load evidence bundle/)).not.toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(3);
        await reply(requests[2], bundle({ bundleId: 'B', claim: 'Recovered B' }));
        expect(screen.getByText('Recovered B')).toBeInTheDocument();
    });

    it('encodes special characters in the request path while matching the original bundle ID', async () => {
        const { requests, fetchMock } = controlFetch();
        const bundleId = 'evidence/A ?#%中文';
        const view = render(<EvidenceBundleView bundleId={bundleId} />);
        expect(requests[0].url).toBe('/api/evidence/evidence%2FA%20%3F%23%25%E4%B8%AD%E6%96%87');
        await reply(requests[0], bundle({ bundleId, claim: 'Special ID evidence' }));

        expect(screen.getByText('Special ID evidence')).toBeInTheDocument();
        view.unmount();
        render(<EvidenceBundleView bundleId={bundleId} />);
        expect(screen.getByText('Special ID evidence')).toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('does not fetch an empty ID or retain content when the ID becomes empty', async () => {
        const { requests, fetchMock } = controlFetch();
        const view = render(<EvidenceBundleView bundleId="" />);
        expect(fetchMock).not.toHaveBeenCalled();
        view.rerender(<EvidenceBundleView bundleId="A" />);
        await reply(requests[0], bundle({ bundleId: 'A', claim: 'Loaded evidence' }));
        view.rerender(<EvidenceBundleView bundleId="" />);

        expect(screen.queryByText('Loaded evidence')).not.toBeInTheDocument();
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('resets the selected tab when the bundle ID changes', async () => {
        const { requests } = controlFetch();
        const tabBundle = (id: string) => bundle({
            bundleId: id,
            items: [
                { id: `${id}-image`, type: 'screenshot', summary: `${id} screenshot`, blobSha256: null, meta: { dataUrl: 'data:image/png;base64,AA==' } },
                { id: `${id}-command`, type: 'command', summary: `${id} command`, blobSha256: null, meta: { stdout: `${id} output` } },
            ],
        });
        const view = render(<EvidenceBundleView bundleId="A" />);
        await reply(requests[0], tabBundle('A'));
        fireEvent.click(screen.getByRole('button', { name: /Commands/ }));
        expect(screen.getByText('A output')).toBeInTheDocument();

        view.rerender(<EvidenceBundleView bundleId="B" />);
        await reply(requests[1], tabBundle('B'));
        expect(screen.getByRole('img', { name: 'B screenshot' })).toBeInTheDocument();
        expect(screen.queryByText('B output')).not.toBeInTheDocument();
    });
});
