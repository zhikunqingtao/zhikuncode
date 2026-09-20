import { beforeEach, afterEach, expect, it, vi } from 'vitest';

const savedRecovery = () => {
    const key = Object.keys(localStorage).find(key => key.startsWith('session-merge-pending-v2:'));
    return key ? localStorage.getItem(key) : null;
};
const request = { sourceSessionIds: ['A', 'B'], primarySessionId: 'A', title: 'E', model: 'm' };
const preparing = { operationId: 'op', targetSessionId: 'E', status: 'preparing', stage: 'snapshot', request, result: {} };
beforeEach(() => { localStorage.clear(); vi.resetModules(); });
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('adopts another tabs pending operation instead of overwriting its recovery', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => preparing }));
    const tabA = await import('./sessionMergeStore');
    vi.resetModules();
    const tabB = await import('./sessionMergeStore');
    await tabA.useSessionMergeStore.getState().submit(request);
    const key = tabA.useSessionMergeStore.getState().pending!.key;
    await tabB.useSessionMergeStore.getState().submit({ ...request, title: 'other tab' });
    expect(tabB.useSessionMergeStore.getState().pending!.key).toBe(key);
    expect(JSON.parse(savedRecovery()!).key).toBe(key);
    expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});

it.each(['completed', 'failed'])('a peer %s result does not replace a new submission', async status => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => preparing });
    vi.stubGlobal('fetch', fetchMock);
    const { useSessionMergeStore: store } = await import('./sessionMergeStore');
    const key = 'session-merge-pending-v2:old';
    const saved = JSON.stringify({ key: 'old', request, operation: { ...preparing, status } });
    localStorage.setItem(key, saved);
    await store.getState().submit(request);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/sessions/merge', expect.objectContaining({
        method: 'POST', body: JSON.stringify(request),
    }));
    expect(store.getState().pending?.key).not.toBe('old');
    expect(localStorage.getItem(key)).toBe(saved);
});

it.each(['completed', 'failed'])('storage events do not adopt a peer %s result', async status => {
    vi.stubGlobal('fetch', vi.fn());
    const { useSessionMergeStore: store, subscribeMergeRecovery } = await import('./sessionMergeStore');
    const unsubscribe = subscribeMergeRecovery();
    try {
        const key = 'session-merge-pending-v2:old';
        const value = JSON.stringify({ key: 'old', request, operation: { ...preparing, status } });
        localStorage.setItem(key, value);
        window.dispatchEvent(new StorageEvent('storage', { key, newValue: value }));
        expect(store.getState().pending).toBeNull();
        expect(fetch).not.toHaveBeenCalled();
        expect(localStorage.getItem(key)).toBe(value);
    } finally { unsubscribe(); }
});

it('still restores and revalidates a completed result after reload', async () => {
    const completed = { ...preparing, status: 'completed' };
    localStorage.setItem('session-merge-pending-v2:old', JSON.stringify({ key: 'old', request, operation: completed }));
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => completed }));
    const { useSessionMergeStore: store } = await import('./sessionMergeStore');
    await store.getState().refresh();
    expect(fetch).toHaveBeenCalledWith('/api/session-merges/op', expect.objectContaining({ headers: { Accept: 'application/json' } }));
    expect(store.getState().pending?.operation?.status).toBe('completed');
});

it('a rejected simultaneous submission deletes only its own recovery record', async () => {
    let rejectB!: (response: unknown) => void;
    const fetchMock = vi.fn().mockImplementation(() => new Promise(resolve => { rejectB = resolve; }));
    vi.stubGlobal('fetch', fetchMock);
    const { useSessionMergeStore: tabB } = await import('./sessionMergeStore');
    // B has already passed admission locally when A saves its independent operation.
    const submitting = tabB.getState().submit(request);
    const a = { key: 'tab-A', request, operation: preparing };
    localStorage.setItem('session-merge-pending-v2:tab-A', JSON.stringify(a));
    tabB.getState().closeDialog();
    rejectB({ ok: false, status: 409, json: async () => ({ error: { message: 'busy' } }) });
    await submitting;
    expect(tabB.getState().pending).toBeNull();
    expect(localStorage.length).toBe(1);
    expect(JSON.parse(savedRecovery()!)).toEqual(a);
    expect(tabB.getState().error).toBe('busy');
    vi.resetModules();
    const reloaded = await import('./sessionMergeStore');
    expect(reloaded.useSessionMergeStore.getState().pending?.key).toBe('tab-A');
});

it('storage events recover a peer operation and unsubscribe with the panel', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => preparing }));
    const { useSessionMergeStore: store, subscribeMergeRecovery } = await import('./sessionMergeStore');
    const unsubscribe = subscribeMergeRecovery();
    try {
        const key = 'session-merge-pending-v2:peer';
        const value = JSON.stringify({ key: 'peer', request, operation: preparing });
        localStorage.setItem(key, value);
        window.dispatchEvent(new StorageEvent('storage', { key, newValue: value }));
        await store.getState().refresh();
        expect(store.getState().pending?.key).toBe('peer');
        localStorage.removeItem(key);
        window.dispatchEvent(new StorageEvent('storage', { key, newValue: null }));
        expect(store.getState().pending).toBeNull();
        unsubscribe();
        localStorage.setItem(key, value);
        window.dispatchEvent(new StorageEvent('storage', { key, newValue: value }));
        expect(store.getState().pending).toBeNull();
    } finally { unsubscribe(); }
});

it('migrates existing recovery only after the same operation is confirmed', async () => {
    localStorage.setItem('session-merge-pending-v1', JSON.stringify({ key: 'legacy', request }));
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => preparing }));
    const { useSessionMergeStore: store } = await import('./sessionMergeStore');
    await store.getState().refresh();
    expect(vi.mocked(fetch).mock.calls[0][1]?.headers).toMatchObject({ 'Idempotency-Key': 'legacy' });
    expect(JSON.parse(savedRecovery()!).key).toBe('legacy');
    expect(localStorage.getItem('session-merge-pending-v1')).toBeNull();
});

it('persists before POST and retries a lost response with the same key after reload', async () => {
    const fetchMock = vi.fn().mockRejectedValueOnce(new Error('connection lost'));
    vi.stubGlobal('fetch', fetchMock);
    const first = await import('./sessionMergeStore');
    await first.useSessionMergeStore.getState().submit(request);
    const firstKey = fetchMock.mock.calls[0][1].headers['Idempotency-Key'];
    expect(JSON.parse(savedRecovery()!).key).toBe(firstKey);
    vi.resetModules();
    const restored = await import('./sessionMergeStore');
    fetchMock.mockResolvedValueOnce({ ok: true, json: async () => preparing });
    await restored.useSessionMergeStore.getState().refresh();
    expect(fetchMock.mock.calls[1][1].headers['Idempotency-Key']).toBe(firstKey);
    expect(restored.useSessionMergeStore.getState().pending?.operation?.targetSessionId).toBe('E');
    expect(restored.isMergeSource('A')).toBe(true);
    expect(restored.isMergeSource('C')).toBe(false);
});

it('polls the accepted operation, preserves warnings, and stops polling after completion', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => preparing })
        .mockResolvedValueOnce({ ok: true, json: async () => ({ ...preparing, status: 'completed', stage: 'completed',
            result: { warningCount: 1, warnings: [{ originalPath: '/missing', reason: '缺失', status: 'missing' }] } }) });
    vi.stubGlobal('fetch', fetchMock);
    const { useSessionMergeStore: store, isMergeSource } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    await store.getState().refresh();
    expect(fetchMock.mock.calls[1][0]).toBe('/api/session-merges/op');
    expect(isMergeSource('A')).toBe(false);
    expect(store.getState().pending?.operation?.result.warningCount).toBe(1);
    await store.getState().refresh();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    store.getState().dismiss();
    expect(savedRecovery()).toBeNull();
});

it('rejected admission displays the server guidance and does not leave local source occupancy', async () => {
    const message = '来源会话或关联子会话（ID：B）仍有执行占用。请查看 Bash 启动结果中的 PID 和停止说明，待进程退出后重试合并。';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409,
        json: async () => ({ error: { code: message, message } }) }));
    const { useSessionMergeStore: store, isMergeSource } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    expect(store.getState().error).toBe(message);
    expect(store.getState().pending).toBeNull();
    expect(isMergeSource('A')).toBe(false);
});

it('does not clear a known operation when polling temporarily fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({ ok: true, json: async () => preparing })
        .mockRejectedValueOnce(new Error('offline')));
    const { useSessionMergeStore: store } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    await store.getState().refresh();
    expect(store.getState().pending?.operation?.operationId).toBe('op');
    expect(store.getState().error).toBe('offline');
});

const missingOperation = { ok: false, status: 404,
    json: async () => ({ error: { code: 'MERGE_OPERATION_NOT_FOUND', message: '合并操作不存在' } }) };

it('restores all five source occupancies and releases all on completion without another POST', async () => {
    const five = { ...request, sourceSessionIds: ['A', 'B', 'C', 'D', 'E'] };
    const accepted = { ...preparing, request: five };
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => accepted })
        .mockResolvedValueOnce({ ok: true, json: async () => ({ ...accepted, status: 'completed' }) });
    vi.stubGlobal('fetch', fetchMock);
    const first = await import('./sessionMergeStore');
    await first.useSessionMergeStore.getState().submit(five);
    vi.resetModules();
    const restored = await import('./sessionMergeStore');
    for (const id of five.sourceSessionIds) expect(restored.isMergeSource(id)).toBe(true);
    expect(restored.isMergeSource('unrelated')).toBe(false);
    await restored.useSessionMergeStore.getState().refresh();
    for (const id of five.sourceSessionIds) expect(restored.isMergeSource(id)).toBe(false);
    expect(fetchMock.mock.calls[1][0]).toBe('/api/session-merges/op');
    expect(fetchMock.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});

it('releases missing-operation sources, clears recovery, and never automatically resubmits', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => preparing })
        .mockResolvedValue(missingOperation);
    vi.stubGlobal('fetch', fetchMock);
    const { useSessionMergeStore: store, isMergeSource } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    await store.getState().refresh();
    expect(store.getState().pending).toBeNull();
    expect(store.getState().recoveryNotice).toContain('已解除本地占用');
    expect(store.getState().error).toBeNull();
    expect(store.getState().submitting).toBe(false);
    expect(isMergeSource('A')).toBe(false);
    expect(isMergeSource('B')).toBe(false);
    expect(isMergeSource('C')).toBe(false);
    expect(savedRecovery()).toBeNull();
    await store.getState().refresh();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][0]).toBe('/api/session-merges/op');
    expect(fetchMock.mock.calls[1][1].headers.Accept).toBe('application/json');
    expect(fetchMock.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});

it.each([
    [404, 'RESOURCE_NOT_FOUND'], [404, undefined], [401, 'MERGE_OPERATION_NOT_FOUND'],
    [403, 'MERGE_OPERATION_NOT_FOUND'], [500, 'MERGE_OPERATION_NOT_FOUND'],
])('retains a known operation for HTTP %s with code %s', async (status, code) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({ ok: true, json: async () => preparing })
        .mockResolvedValue({ ok: false, status, json: async () => ({ error: { code } }) }));
    const { useSessionMergeStore: store, isMergeSource } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    const persisted = savedRecovery();
    await store.getState().refresh();
    expect(store.getState().pending?.operation?.operationId).toBe('op');
    expect(isMergeSource('A')).toBe(true);
    expect(store.getState().recoveryNotice).toBeNull();
    expect(savedRecovery()).toBe(persisted);
});

it('releases missing-operation occupancy even if storage deletion fails, and revalidates on reload', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => preparing })
        .mockResolvedValue(missingOperation);
    vi.stubGlobal('fetch', fetchMock);
    const first = await import('./sessionMergeStore');
    await first.useSessionMergeStore.getState().submit(request);
    const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('unavailable'); });
    await first.useSessionMergeStore.getState().refresh();
    expect(first.isMergeSource('A')).toBe(false);
    expect(first.useSessionMergeStore.getState().storageWarning).toContain('无法更新本地恢复信息');
    expect(savedRecovery()).not.toBeNull();
    remove.mockRestore();
    vi.resetModules();
    const restored = await import('./sessionMergeStore');
    expect(restored.isMergeSource('A')).toBe(true);
    await restored.useSessionMergeStore.getState().refresh();
    expect(restored.isMergeSource('A')).toBe(false);
    expect(restored.isMergeSource('B')).toBe(false);
    expect(savedRecovery()).toBeNull();
    expect(fetchMock.mock.calls[2][0]).toBe('/api/session-merges/op');
    expect(fetchMock.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});

it('keeps submission rejection separate from recovery of a known operation', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(missingOperation));
    const { useSessionMergeStore: store } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    expect(store.getState().pending).toBeNull();
    expect(store.getState().recoveryNotice).toBeNull();
});

it('preserves a known operation when its progress request times out', async () => {
    vi.useFakeTimers();
    try {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({ ok: true, json: async () => preparing })
            .mockImplementationOnce((_url, options) => new Promise((_resolve, reject) => {
                options.signal.addEventListener('abort', () => reject(new Error('aborted')));
            })));
        const { useSessionMergeStore: store, isMergeSource } = await import('./sessionMergeStore');
        await store.getState().submit(request);
        const refreshing = store.getState().refresh();
        await vi.advanceTimersByTimeAsync(15000);
        await refreshing;
        expect(store.getState().pending?.operation?.operationId).toBe('op');
        expect(isMergeSource('A')).toBe(true);
        expect(store.getState().recoveryNotice).toBeNull();
        expect(store.getState().error).toContain('连接超时');
    } finally { vi.useRealTimers(); }
});

it.each(['completed', 'failed'] as const)('adopts %s and releases sources even when saving or dismissing fails', async status => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => preparing })
        .mockResolvedValueOnce({ ok: true, json: async () => ({ ...preparing, status, stage: status }) });
    vi.stubGlobal('fetch', fetchMock);
    const { useSessionMergeStore: store, isMergeSource } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    await store.getState().refresh();
    expect(store.getState().pending?.operation?.status).toBe(status);
    expect(store.getState().storageWarning).toContain('无法更新本地恢复信息');
    expect(isMergeSource('A')).toBe(false);
    expect(isMergeSource('B')).toBe(false);
    await store.getState().refresh();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const { useNotificationStore } = await import('./notificationStore');
    expect(useNotificationStore.getState().notifications).toEqual(expect.arrayContaining([
        expect.objectContaining({ key: 'merge-storage', level: 'warning' }),
    ]));
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('unavailable'); });
    expect(() => store.getState().dismiss()).not.toThrow();
    expect(store.getState().pending).toBeNull();
});

it('retains accepted operation identity in memory if updating storage fails', async () => {
    const fetchMock = vi.fn().mockImplementationOnce(async () => {
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
        return { ok: true, json: async () => preparing };
    }).mockResolvedValueOnce({ ok: true, json: async () => preparing });
    vi.stubGlobal('fetch', fetchMock);
    const { useSessionMergeStore: store, isMergeSource } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    expect(store.getState().pending?.operation?.operationId).toBe('op');
    expect(isMergeSource('A')).toBe(true);
    await store.getState().refresh();
    expect(fetchMock.mock.calls[1][0]).toBe('/api/session-merges/op');
});

it('still refuses to submit if recovery identity cannot be saved initially', async () => {
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
    const { useSessionMergeStore: store, isMergeSource } = await import('./sessionMergeStore');
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    await store.getState().submit(request);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(store.getState().pending).toBeNull();
    expect(isMergeSource('A')).toBe(false);
});

it('releases rejected sources even if removing stored recovery identity fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ message: 'busy' }) }));
    const { useSessionMergeStore: store, isMergeSource } = await import('./sessionMergeStore');
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('unavailable'); });
    await store.getState().submit(request);
    expect(store.getState().pending).toBeNull();
    expect(isMergeSource('A')).toBe(false);
    expect(store.getState().error).toBe('busy');
});

it('preserves pending and occupancy references for unchanged progress and clears recovered errors', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => ({ ok: true,
        json: async () => JSON.parse(JSON.stringify(preparing)) }));
    vi.stubGlobal('fetch', fetchMock);
    const { useSessionMergeStore: store, selectMergeSourceIds } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    const pending = store.getState().pending;
    const sources = selectMergeSourceIds(store.getState());
    const persist = vi.spyOn(Storage.prototype, 'setItem');
    const changes = vi.fn();
    const unsubscribe = store.subscribe((next, previous) => {
        if (next.pending !== previous.pending) changes();
    });
    await store.getState().refresh();
    expect(store.getState().pending).toBe(pending);
    expect(selectMergeSourceIds(store.getState())).toBe(sources);
    expect(changes).not.toHaveBeenCalled();
    expect(persist).not.toHaveBeenCalled();
    fetchMock.mockRejectedValueOnce(new Error('offline'));
    await store.getState().refresh();
    expect(store.getState().error).toBe('offline');
    await store.getState().refresh();
    expect(store.getState().error).toBeNull();
    expect(store.getState().pending).toBe(pending);
    unsubscribe();
});

it.each([
    { stage: 'copying' },
    { error: 'temporary detail' },
    { result: { copiedCount: 2 } },
    { result: { warnings: [{ originalPath: '/a', reason: 'missing', status: 'missing' }] } },
])('adopts changed progress without changing source occupancy: %j', async patch => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({ ok: true, json: async () => preparing })
        .mockResolvedValueOnce({ ok: true, json: async () => ({ ...preparing, ...patch }) }));
    const { useSessionMergeStore: store, selectMergeSourceIds } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    const pending = store.getState().pending;
    const sources = selectMergeSourceIds(store.getState());
    await store.getState().refresh();
    expect(store.getState().pending).not.toBe(pending);
    expect(store.getState().pending?.operation).toMatchObject(patch);
    expect(selectMergeSourceIds(store.getState())).toBe(sources);
});

it('retries failed persistence even when server progress is unchanged', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => ({ ok: true,
        json: async () => JSON.parse(JSON.stringify(preparing)) })));
    const { useSessionMergeStore: store } = await import('./sessionMergeStore');
    await store.getState().submit(request);
    store.setState({ storageWarning: 'previous persistence failure' });
    const save = vi.spyOn(Storage.prototype, 'setItem');
    const pending = store.getState().pending;
    await store.getState().refresh();
    expect(save).toHaveBeenCalledTimes(1);
    expect(store.getState().storageWarning).toBeNull();
    expect(store.getState().pending).toBe(pending);
});
