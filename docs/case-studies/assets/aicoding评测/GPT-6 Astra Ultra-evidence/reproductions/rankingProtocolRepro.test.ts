import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => { localStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
const request = { sourceSessionIds: ['A', 'B'], primarySessionId: 'A', title: 'E', model: 'm' };

it('drops still-authoritative server locks when status is cancelled', async () => {
  vi.resetModules();
  const { selectMergeSourceIds } = await import('./sessionMergeStore');
  const pending = { key: 'k', request, operation: {
    operationId: 'op', targetSessionId: 'E', status: 'cancelled' as const,
    stage: 'snapshotting', request, result: {}, lockedSourceSessionIds: ['A', 'B'],
  } };
  expect(selectMergeSourceIds({ pending })).toEqual([]);
});

it('polling raises submitting and a cancel call waits for the GET to finish', async () => {
  vi.resetModules();
  const op = { operationId: 'op', targetSessionId: 'E', status: 'paused', stage: 'extracting',
    request, result: {}, canCancel: true, canResume: true, runEpoch: 1, lockedSourceSessionIds: [] };
  localStorage.setItem('session-merge-pending-v2:k', JSON.stringify({ key: 'k', request, operation: op }));
  let completeGet!: (value: unknown) => void;
  const getResponse = new Promise(resolve => { completeGet = resolve; });
  const fetchMock = vi.fn().mockImplementation((url: string) => url.endsWith('/cancel')
    ? Promise.resolve({ ok: true, json: async () => ({ ...op, status: 'cancelled', canCancel: false }) })
    : getResponse);
  vi.stubGlobal('fetch', fetchMock);
  const { useSessionMergeStore: store } = await import('./sessionMergeStore');
  const poll = store.getState().refresh();
  expect(store.getState().submitting).toBe(true);
  const cancel = store.getState().cancel();
  await Promise.resolve();
  expect(fetchMock).toHaveBeenCalledTimes(1);
  completeGet({ ok: true, json: async () => op });
  await poll; await cancel;
  expect(fetchMock.mock.calls.map(call => call[0])).toEqual(['/api/session-merges/op', '/api/session-merges/op/cancel']);
});

it('adds two same-key notifications through the supported pause resume pause cycle', async () => {
  vi.resetModules();
  // Keep both observations inside the real default 5-second toast lifetime.
  vi.spyOn(Date, 'now').mockReturnValue(1000);
  const preparing = { operationId: 'op', targetSessionId: 'E', status: 'preparing', stage: 'extracting',
    request, result: {}, canCancel: true, canResume: false, runEpoch: 1, lockedSourceSessionIds: [] };
  const paused = { ...preparing, status: 'paused', canResume: true, error: '模型暂时不可用' };
  const resumed = { ...preparing, runEpoch: 2 };
  const pausedAgain = { ...paused, runEpoch: 2 };
  localStorage.setItem('session-merge-pending-v2:k', JSON.stringify({ key: 'k', request, operation: preparing }));
  const fetchMock = vi.fn()
    .mockResolvedValueOnce({ ok: true, json: async () => paused })
    .mockResolvedValueOnce({ ok: true, json: async () => resumed })
    .mockResolvedValueOnce({ ok: true, json: async () => pausedAgain });
  vi.stubGlobal('fetch', fetchMock);
  const { useSessionMergeStore: store } = await import('./sessionMergeStore');
  const { useNotificationStore: notifications } = await import('./notificationStore');

  await store.getState().refresh();
  expect(notifications.getState().notifications).toHaveLength(1);
  await store.getState().resume();
  expect(store.getState().pending?.operation?.status).toBe('preparing');
  await store.getState().refresh();

  expect(fetchMock.mock.calls.map(call => call[0])).toEqual([
    '/api/session-merges/op', '/api/session-merges/op/resume', '/api/session-merges/op',
  ]);
  expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ expectedEpoch: 1 });
  const current = notifications.getState().notifications;
  expect(current.map(item => item.key)).toEqual(['merge-op', 'merge-op']);
  expect(current.map(item => item.message)).toEqual(['模型暂时不可用', '模型暂时不可用']);
  expect(current.every(item => item.createdAt === 1000 && item.timeout === 5000)).toBe(true);
});

it('also repeats a paused notification when only worker cleanup makes resume available', async () => {
  vi.resetModules();
  vi.spyOn(Date, 'now').mockReturnValue(1000);
  const preparing = { operationId: 'op', targetSessionId: 'E', status: 'preparing', stage: 'extracting',
    request, result: {}, canCancel: true, canResume: false, runEpoch: 1, lockedSourceSessionIds: [] };
  const pausedWhileWorkerExits = { ...preparing, status: 'paused', error: '模型暂时不可用' };
  const pausedAfterWorkerExits = { ...pausedWhileWorkerExits, canResume: true };
  localStorage.setItem('session-merge-pending-v2:k', JSON.stringify({ key: 'k', request, operation: preparing }));
  const fetchMock = vi.fn()
    .mockResolvedValueOnce({ ok: true, json: async () => pausedWhileWorkerExits })
    .mockResolvedValueOnce({ ok: true, json: async () => pausedAfterWorkerExits })
    .mockResolvedValueOnce({ ok: true, json: async () => pausedAfterWorkerExits });
  vi.stubGlobal('fetch', fetchMock);
  const { useSessionMergeStore: store } = await import('./sessionMergeStore');
  const { useNotificationStore: notifications } = await import('./notificationStore');

  await store.getState().refresh();
  await store.getState().refresh();
  expect(notifications.getState().notifications.map(item => item.key)).toEqual(['merge-op', 'merge-op']);
  // Counterexample: the changed guard really does suppress an identical DTO.
  await store.getState().refresh();
  expect(notifications.getState().notifications).toHaveLength(2);
});

it('retains terminal target availability until reopening the dialog invalidates the cache', async () => {
  vi.resetModules();
  const completed = { operationId: 'op', targetSessionId: 'E', status: 'completed', stage: 'completed',
    request, result: {}, canCancel: false, canResume: false, runEpoch: 1,
    lockedSourceSessionIds: [], targetAvailable: true };
  localStorage.setItem('session-merge-pending-v2:k', JSON.stringify({ key: 'k', request, operation: completed }));
  const fetchMock = vi.fn()
    .mockResolvedValueOnce({ ok: true, json: async () => completed })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ ...completed, targetAvailable: false }) });
  vi.stubGlobal('fetch', fetchMock);
  const { useSessionMergeStore: store } = await import('./sessionMergeStore');

  await store.getState().refresh();
  // The second response models the target having been deleted through another entry point.
  await store.getState().refresh();
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(store.getState().pending?.operation?.targetAvailable).toBe(true);
  store.getState().openDialog();
  await store.getState().refresh();
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(store.getState().pending?.operation?.targetAvailable).toBe(false);
});
