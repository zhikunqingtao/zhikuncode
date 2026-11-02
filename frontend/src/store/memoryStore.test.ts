import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMemoryStore, type MemorySnapshot } from './memoryStore';

const fetchMock = vi.fn<typeof fetch>();
const initialStore = useMemoryStore.getInitialState();
const initial: MemorySnapshot = {
  content: 'old', entries: [], updatedAt: '2026-01-01T00:00:00Z', size: 3, maxSize: 50_000,
};
const saved: MemorySnapshot = {
  ...initial, content: 'saved', updatedAt: '2026-01-02T00:00:00Z', size: 5,
};

function deferNextRequest() {
  let resolve!: (value: Response) => void;
  fetchMock.mockReturnValueOnce(new Promise<Response>(done => { resolve = done; }));
  return (body: unknown, status = 200) => resolve(new Response(JSON.stringify(body), { status }));
}

beforeEach(() => {
  useMemoryStore.setState({ ...initialStore, ...initial, loaded: true }, true);
  fetchMock.mockReset().mockRejectedValue(new Error('Unexpected memory request'));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  useMemoryStore.setState(initialStore, true);
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('memory request ordering', () => {
  it('discards a GET started before a write when its stale response arrives after the ACK', async () => {
    const finishRead = deferNextRequest();
    const read = useMemoryStore.getState().loadFile();
    const finishSave = deferNextRequest();
    const write = useMemoryStore.getState().saveRaw('saved', initial.updatedAt);

    finishSave({ success: true, ...saved });
    const ack = await write;
    finishRead(initial);
    const stale = await read;
    expect(useMemoryStore.getState()).toMatchObject({
      ...saved, loading: false, saving: false, error: null,
    });
    expect(ack).toEqual(saved);
    expect(stale).toBeNull();
  });

  it('starts a remounted editor GET after the pending ACK and keeps a newer external file', async () => {
    const finishSave = deferNextRequest();
    const write = useMemoryStore.getState().saveRaw('saved', initial.updatedAt);
    const finishRead = deferNextRequest();
    const read = useMemoryStore.getState().loadFile();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const newer = { ...saved, content: 'saved + external', updatedAt: '2026-01-03T00:00:00Z' };

    finishSave({ success: true, ...saved });
    const ack = await write;
    expect(fetchMock).toHaveBeenCalledTimes(2);
    finishRead(newer);
    expect(await read).toEqual(newer);
    expect(ack).toEqual(saved);
    expect(useMemoryStore.getState()).toMatchObject({ ...newer, loading: false, saving: false });
  });

  it('only sends the latest of several reads queued behind one write', async () => {
    const finishSave = deferNextRequest();
    const write = useMemoryStore.getState().saveRaw('saved', initial.updatedAt);
    const finishRead = deferNextRequest();
    const first = useMemoryStore.getState().loadFile();
    const second = useMemoryStore.getState().loadFile();
    const latest = useMemoryStore.getState().loadFile();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    finishSave({ success: true, ...saved });
    await write;
    expect(await first).toBeNull();
    expect(await second).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    finishRead(saved);
    expect(await latest).toEqual(saved);
    expect(useMemoryStore.getState().loading).toBe(false);
  });

  it.each([409, 500])('releases queued reads when the PUT returns HTTP %s', async status => {
    const finishSave = deferNextRequest();
    const write = useMemoryStore.getState().saveRaw('saved', initial.updatedAt);
    const finishRead = deferNextRequest();
    const read = useMemoryStore.getState().loadFile();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    finishSave({ message: 'write rejected' }, status);
    expect(await write).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    finishRead(saved);
    expect(await read).toEqual(saved);
    expect(useMemoryStore.getState()).toMatchObject({
      ...saved, saving: false, loading: false, conflict: false,
      error: status === 500 ? 'HTTP 500' : null,
    });
  });

  it.each([200, 500])('ignores an older GET returning HTTP %s after a newer GET has completed', async status => {
    const finishOld = deferNextRequest();
    const oldRead = useMemoryStore.getState().loadFile();
    const finishNew = deferNextRequest();
    const newRead = useMemoryStore.getState().loadFile();

    finishNew(saved);
    const latest = await newRead;
    finishOld(status === 200 ? initial : { message: 'old read failed' }, status);
    const stale = await oldRead;
    expect(useMemoryStore.getState()).toMatchObject({ ...saved, loading: false, error: null });
    expect(latest).toEqual(saved);
    expect(stale).toBeNull();
  });

  it('keeps one in-flight write and does not send a second write against an unacknowledged version', async () => {
    const finishSave = deferNextRequest();
    const write = useMemoryStore.getState().saveRaw('saved', initial.updatedAt);
    const second = await useMemoryStore.getState().saveRaw('second', initial.updatedAt);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(second).toBeNull();
    expect(useMemoryStore.getState().saving).toBe(true);
    finishSave({ success: true, ...saved });
    expect(await write).toEqual(saved);
    expect(useMemoryStore.getState().saving).toBe(false);
  });
});

describe('memory save timeouts', () => {
  it.each([
    { stage: 'headers', readStatus: 200 },
    { stage: 'body', readStatus: 500 },
  ] as const)('aborts a PUT with pending $stage and keeps its warning after the queued GET returns $readStatus', async ({ stage, readStatus }) => {
    vi.useFakeTimers();
    let signal: AbortSignal | null | undefined;
    let abort = () => {};
    let response: Response | undefined;
    fetchMock.mockImplementationOnce((_url, init) => {
      signal = init?.signal;
      const abortError = new DOMException('The operation was aborted', 'AbortError');
      if (stage === 'headers') {
        return new Promise<Response>((_resolve, reject) => {
          abort = () => reject(abortError);
          signal?.addEventListener('abort', abort, { once: true });
        });
      }
      // Headers and part of a real JSON response have arrived, but the body never ends.
      response = new Response(new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new TextEncoder().encode('{"success":'));
          abort = () => controller.error(abortError);
          signal?.addEventListener('abort', abort, { once: true });
        },
      }), { status: 200 });
      return Promise.resolve(response);
    });
    const write = useMemoryStore.getState().saveRaw('saved', initial.updatedAt);
    const finishRead = deferNextRequest();
    const read = useMemoryStore.getState().loadFile();

    try {
      await vi.advanceTimersByTimeAsync(14_999);
      expect(useMemoryStore.getState().saving).toBe(true);
      expect(fetchMock).toHaveBeenCalledTimes(1);
      if (stage === 'body') expect(response?.bodyUsed).toBe(true);

      await vi.advanceTimersByTimeAsync(1);
      expect(useMemoryStore.getState().saving).toBe(false);
      expect(await write).toBeNull();
      expect(signal?.aborted).toBe(true);
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(fetchMock.mock.calls[1][1]?.method).not.toBe('PUT');
      finishRead(readStatus === 200 ? saved : { message: 'read failed' }, readStatus);
      expect(await read).toEqual(readStatus === 200 ? saved : null);
      expect(useMemoryStore.getState()).toMatchObject({
        ...(readStatus === 200 ? saved : initial), loading: false, saving: false,
      });
      expect(useMemoryStore.getState().error).toContain('保存超时，结果未确认');
      expect(vi.getTimerCount()).toBe(0);
    } finally {
      // Also settle requests when running this regression against code without a timeout.
      signal?.removeEventListener('abort', abort);
      abort();
      finishRead(saved);
      await Promise.all([write, read]);
    }
  });

  it('clears the deadline after completed or rejected PUT requests', async () => {
    vi.useFakeTimers();
    for (const status of [200, 409, 413, 500, 'network error'] as const) {
      let signal: AbortSignal | null | undefined;
      fetchMock.mockImplementationOnce(async (_url, init) => {
        signal = init?.signal;
        if (status === 'network error') throw new Error(status);
        return new Response(JSON.stringify({ success: true, ...saved }), { status });
      });
      await useMemoryStore.getState().saveRaw('saved', initial.updatedAt);

      expect(useMemoryStore.getState().saving).toBe(false);
      expect(vi.getTimerCount()).toBe(0);
      await vi.advanceTimersByTimeAsync(15_000);
      expect(signal?.aborted).toBe(false);
    }
  });
});
