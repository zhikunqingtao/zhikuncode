import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMemoryStore, type MemoryEntry } from '@/store/memoryStore';
import { MemoryPage } from './MemoryPage';

const fetchMock = vi.fn<typeof fetch>();
const initialStore = useMemoryStore.getInitialState();

function memoryFile(text: string, updatedAt = '2026-01-01T00:00:00Z') {
  const entries: MemoryEntry[] = [{
    source: 'USER', category: 'semantic', timestamp: updatedAt, content: text,
  }];
  const content = `<!-- source:USER time:${updatedAt} category:semantic -->\n${text}`;
  return { content, entries, updatedAt, size: content.length, maxSize: 50_000 };
}

const initialFile = memoryFile('old');
const savedFile = memoryFile('saved', '2026-01-02T00:00:00Z');
const jsonResponse = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
});

function deferNextRequest() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>(done => { resolve = done; });
  fetchMock.mockReturnValueOnce(promise);
  return async (body: unknown, status = 200) => {
    await act(async () => { resolve(jsonResponse(body, status)); });
  };
}

const cardInput = (index = 1) => screen.getByRole('textbox', { name: `记忆条目 ${index} 内容` });
const rawInput = () => screen.getByRole('textbox', { name: '记忆文件内容' });
// The page renders equivalent desktop and mobile save buttons.
const saveButton = () => screen.getAllByRole('button', { name: '保存记忆' })[0];
const change = (input: HTMLElement, value: string) => fireEvent.change(input, { target: { value } });
const lastPutBody = () => {
  const request = fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT').at(-1)!;
  return JSON.parse(String(request[1]?.body)) as {
    content?: string; entries?: MemoryEntry[]; baseUpdatedAt: string | null;
  };
};
function DialogHost() {
  const [open, setOpen] = useState(true);
  return open
    ? <MemoryPage onClose={() => setOpen(false)} />
    : <button onClick={() => setOpen(true)}>重新打开记忆</button>;
}

async function renderLoaded() {
  render(<MemoryPage onClose={vi.fn()} />);
  await waitFor(() => expect(cardInput()).toHaveValue('old'));
}

function startSave(mode: 'raw' | 'cards', value = 'saved') {
  if (mode === 'raw') fireEvent.click(screen.getByRole('tab', { name: '整篇' }));
  change(mode === 'raw' ? rawInput() : cardInput(), value);
  fireEvent.click(saveButton());
  expect(useMemoryStore.getState().saving).toBe(true);
}

beforeEach(() => {
  useMemoryStore.setState(initialStore, true);
  fetchMock.mockReset()
    .mockRejectedValue(new Error('Unexpected memory request'))
    .mockResolvedValueOnce(jsonResponse(initialFile));
  vi.stubGlobal('fetch', fetchMock);
  vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => {
  // Unmount also removes the real beforeunload guard and clears the saved-hint timer.
  cleanup();
  useMemoryStore.setState(initialStore, true);
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('MemoryPage asynchronous editing', () => {
  it('keeps a new card draft after reopening while an earlier raw save response is pending', async () => {
    render(<DialogHost />);
    await waitFor(() => expect(cardInput()).toHaveValue('old'));
    const finishSave = deferNextRequest();
    startSave('raw');

    fireEvent.click(screen.getByRole('button', { name: '关闭记忆页面' }));
    // Reopening queues its GET until the pending PUT has completed.
    fetchMock.mockResolvedValueOnce(jsonResponse(savedFile));
    fireEvent.click(screen.getByRole('button', { name: '重新打开记忆' }));
    expect(cardInput()).toHaveValue('old');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(useMemoryStore.getState().saving).toBe(true);
    change(cardInput(), 'new draft after reopening');

    await finishSave({ success: true, ...savedFile });

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(cardInput()).toHaveValue('new draft after reopening');
    expect(saveButton()).toBeEnabled();
  });

  it.each([200, 500])('keeps edits made during a refresh that returns HTTP %s', async status => {
    await renderLoaded();
    const finishRefresh = deferNextRequest();
    fireEvent.click(screen.getByRole('button', { name: '刷新记忆' }));
    expect(useMemoryStore.getState().loading).toBe(true);
    change(cardInput(), 'new draft during refresh');

    await finishRefresh(status === 200 ? memoryFile('refreshed') : { message: 'read failed' }, status);

    expect(cardInput()).toHaveValue('new draft during refresh');
    expect(saveButton()).toBeEnabled();
    if (status === 500) expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('keeps an existing draft when refresh fails without any further edits', async () => {
    await renderLoaded();
    change(cardInput(), 'draft before refresh');
    const finishRefresh = deferNextRequest();
    fireEvent.click(screen.getByRole('button', { name: '刷新记忆' }));
    expect(useMemoryStore.getState().loading).toBe(true);

    await finishRefresh({ message: 'read failed' }, 500);

    expect(cardInput()).toHaveValue('draft before refresh');
    expect(saveButton()).toBeEnabled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it.each([
    { mode: 'raw', revert: false },
    { mode: 'raw', revert: true },
    { mode: 'cards', revert: false },
    { mode: 'cards', revert: true },
  ] as const)('preserves later $mode input during save (revert to old baseline: $revert)', async ({ mode, revert }) => {
    await renderLoaded();
    const finishSave = deferNextRequest();
    startSave(mode);
    const draft = revert ? (mode === 'raw' ? initialFile.content : 'old') : 'later draft';
    change(mode === 'raw' ? rawInput() : cardInput(), draft);

    await finishSave({ success: true, ...savedFile });

    expect(mode === 'raw' ? rawInput() : cardInput()).toHaveValue(draft);
    expect(saveButton()).toBeEnabled();
  });

  it.each(['add', 'delete'] as const)('preserves a card %s performed while saving', async action => {
    await renderLoaded();
    const finishSave = deferNextRequest();
    startSave('cards');
    if (action === 'add') {
      fireEvent.click(screen.getByRole('button', { name: '新增条目' }));
      change(cardInput(2), 'new second card');
    } else {
      fireEvent.click(screen.getByRole('button', { name: '删除记忆条目 1' }));
    }

    await finishSave({ success: true, ...savedFile });

    if (action === 'add') expect(cardInput(2)).toHaveValue('new second card');
    else expect(screen.queryAllByRole('textbox')).toHaveLength(0);
    expect(saveButton()).toBeEnabled();
  });

  it.each(['raw', 'cards'] as const)('adopts canonical %s content when no edits follow the save', async mode => {
    await renderLoaded();
    const finishSave = deferNextRequest();
    if (mode === 'cards') {
      change(cardInput(), '  saved  ');
      fireEvent.click(screen.getByRole('button', { name: '新增条目' }));
      fireEvent.click(saveButton());
    } else {
      startSave('raw');
    }

    await finishSave({ success: true, ...savedFile });

    if (mode === 'raw') expect(rawInput()).toHaveValue(savedFile.content);
    else {
      expect(cardInput()).toHaveValue('saved');
      expect(screen.queryAllByRole('textbox')).toHaveLength(1);
    }
    expect(saveButton()).toBeDisabled();
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it.each([409, 413, 500])('preserves newer input after a save fails with HTTP %s', async status => {
    await renderLoaded();
    const finishSave = deferNextRequest();
    startSave('raw');
    change(rawInput(), 'new draft after sending');

    await finishSave({ message: 'save rejected' }, status);

    expect(rawInput()).toHaveValue('new draft after sending');
    expect(screen.getByRole('tab', { name: '卡片' })).toBeEnabled();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(useMemoryStore.getState().saving).toBe(false);
  });

  it.each(['raw', 'cards'] as const)('keeps the original %s draft version when refresh discovers an external write', async mode => {
    await renderLoaded();
    if (mode === 'raw') fireEvent.click(screen.getByRole('tab', { name: '整篇' }));
    const editor = () => mode === 'raw' ? rawInput() : cardInput();
    change(editor(), 'draft before refresh');
    const finishRefresh = deferNextRequest();
    fireEvent.click(screen.getByRole('button', { name: '刷新记忆' }));
    change(editor(), 'draft edited during refresh');
    let server = memoryFile('old\nEXTERNAL_WRITE', savedFile.updatedAt);
    await finishRefresh(server);

    expect(editor()).toHaveValue('draft edited during refresh');
    expect(useMemoryStore.getState().updatedAt).toBe(server.updatedAt);
    fetchMock.mockImplementationOnce(async (_url, init) => {
      const body = JSON.parse(String(init?.body));
      if (body.baseUpdatedAt !== server.updatedAt) return jsonResponse({ message: 'conflict' }, 409);
      server = memoryFile(body.content ?? body.entries[0].content, '2026-01-03T00:00:00Z');
      return jsonResponse({ success: true, ...server });
    });
    fireEvent.click(saveButton());
    await waitFor(() => expect(useMemoryStore.getState().saving).toBe(false));

    expect(lastPutBody().baseUpdatedAt).toBe(initialFile.updatedAt);
    expect(useMemoryStore.getState().conflict).toBe(true);
    expect(server.content).toContain('EXTERNAL_WRITE');
    expect(editor()).toHaveValue('draft edited during refresh');
  });

  it.each(['raw', 'cards'] as const)('preserves the %s draft and its version when a committed save loses its ACK', async mode => {
    await renderLoaded();
    vi.useFakeTimers();
    let server = initialFile;
    let abortPut = () => {};
    fetchMock.mockImplementationOnce((_url, init) => {
      const body = JSON.parse(String(init?.body));
      // The server commits, but the client receives neither headers nor an ACK.
      server = memoryFile(body.content ?? body.entries[0].content, savedFile.updatedAt);
      return new Promise<Response>((_resolve, reject) => {
        abortPut = () => reject(new DOMException('The operation was aborted', 'AbortError'));
        init?.signal?.addEventListener('abort', abortPut, { once: true });
      });
    });
    const editor = () => mode === 'raw' ? rawInput() : cardInput();
    startSave(mode, 'sent draft');
    change(editor(), 'later draft before timeout');

    try {
      await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
      expect(useMemoryStore.getState().saving).toBe(false);
      expect(screen.getByRole('alert')).toHaveTextContent('保存超时，结果未确认');
      expect(editor()).toHaveValue('later draft before timeout');
      expect(saveButton()).toBeEnabled();
      expect(useMemoryStore.getState().updatedAt).toBe(initialFile.updatedAt);
      expect(server.entries[0].content).toBe('sent draft');
      expect(lastPutBody().baseUpdatedAt).toBe(initialFile.updatedAt);
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
      vi.useRealTimers();

      const finishRefresh = deferNextRequest();
      fireEvent.click(screen.getByRole('button', { name: '刷新记忆' }));
      change(editor(), 'later draft during reconciliation');
      await finishRefresh(server);
      expect(useMemoryStore.getState().updatedAt).toBe(server.updatedAt);
      expect(editor()).toHaveValue('later draft during reconciliation');
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);

      fetchMock.mockImplementationOnce(async (_url, init) => {
        const body = JSON.parse(String(init?.body));
        if (body.baseUpdatedAt !== server.updatedAt) return jsonResponse({ message: 'conflict' }, 409);
        server = memoryFile(body.content ?? body.entries[0].content, '2026-01-03T00:00:00Z');
        return jsonResponse({ success: true, ...server });
      });
      fireEvent.click(saveButton());
      await waitFor(() => expect(useMemoryStore.getState().saving).toBe(false));

      expect(lastPutBody().baseUpdatedAt).toBe(initialFile.updatedAt);
      expect(lastPutBody()).toMatchObject(mode === 'raw'
        ? { content: 'later draft during reconciliation' }
        : { entries: [{ content: 'later draft during reconciliation' }] });
      expect(useMemoryStore.getState().conflict).toBe(true);
      expect(editor()).toHaveValue('later draft during reconciliation');
      expect(server.entries[0].content).toBe('sent draft');
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(2);
    } finally {
      await act(async () => { abortPut(); });
      vi.useRealTimers();
    }
  });

  it.each(['raw', 'cards'] as const)('advances later %s edits to their own successful save ACK', async mode => {
    await renderLoaded();
    const finishSave = deferNextRequest();
    startSave(mode);
    change(mode === 'raw' ? rawInput() : cardInput(), 'later draft');
    await finishSave({ success: true, ...savedFile });
    expect(useMemoryStore.getState().dirty).toBe(true);
    const nextFile = memoryFile('later draft', '2026-01-03T00:00:00Z');
    fetchMock.mockImplementationOnce(async (_url, init) => {
      const body = JSON.parse(String(init?.body));
      return body.baseUpdatedAt === savedFile.updatedAt
        ? jsonResponse({ success: true, ...nextFile })
        : jsonResponse({ message: 'conflict' }, 409);
    });
    fireEvent.click(saveButton());
    await waitFor(() => expect(useMemoryStore.getState().saving).toBe(false));

    expect(lastPutBody().baseUpdatedAt).toBe(savedFile.updatedAt);
    expect(useMemoryStore.getState().conflict).toBe(false);
    expect(useMemoryStore.getState().updatedAt).toBe(nextFile.updatedAt);
    expect(mode === 'raw' ? rawInput() : cardInput()).toHaveValue(mode === 'raw' ? nextFile.content : 'later draft');
  });

  it('does not rebase a new mounted editor onto the previous editor save ACK', async () => {
    render(<DialogHost />);
    await waitFor(() => expect(cardInput()).toHaveValue('old'));
    const finishSave = deferNextRequest();
    startSave('raw');
    fireEvent.click(screen.getByRole('button', { name: '关闭记忆页面' }));
    const finishReopen = deferNextRequest();
    fireEvent.click(screen.getByRole('button', { name: '重新打开记忆' }));
    // Editing while the reopened GET is pending must keep the currently displayed version.
    change(cardInput(), 'draft from the old file');
    await finishSave({ success: true, ...savedFile });
    // The queued read sees the new file; the retained draft still belongs to the old one.
    await finishReopen(savedFile);
    expect(cardInput()).toHaveValue('draft from the old file');
    fetchMock.mockImplementationOnce(async (_url, init) => {
      const body = JSON.parse(String(init?.body));
      return body.baseUpdatedAt === savedFile.updatedAt
        ? jsonResponse({ success: true, ...savedFile })
        : jsonResponse({ message: 'conflict' }, 409);
    });
    fireEvent.click(saveButton());
    await waitFor(() => expect(useMemoryStore.getState().saving).toBe(false));
    expect(lastPutBody().baseUpdatedAt).toBe(initialFile.updatedAt);
    expect(useMemoryStore.getState().conflict).toBe(true);
    expect(cardInput()).toHaveValue('draft from the old file');
  });

  it('does not start a user refresh while a save is pending', async () => {
    await renderLoaded();
    const finishSave = deferNextRequest();
    startSave('cards');
    const before = fetchMock.mock.calls.length;
    const refresh = screen.getByRole('button', { name: '刷新记忆' });
    expect(refresh).toBeDisabled();
    fireEvent.click(refresh);
    expect(fetchMock).toHaveBeenCalledTimes(before);
    await finishSave({ success: true, ...savedFile });
    expect(refresh).toBeEnabled();
  });

  it('keeps conflict drafts on cancelled or failed refresh and rebases only after confirmed successful reload', async () => {
    await renderLoaded();
    const finishSave = deferNextRequest();
    startSave('raw', 'conflicting draft');
    await finishSave({ message: 'conflict' }, 409);
    const conflictRefresh = () => screen.getByRole('button', { name: /^刷新$/ });
    const before = fetchMock.mock.calls.length;
    vi.mocked(window.confirm).mockReturnValue(false);
    fireEvent.click(conflictRefresh());
    expect(fetchMock).toHaveBeenCalledTimes(before);
    expect(rawInput()).toHaveValue('conflicting draft');

    vi.mocked(window.confirm).mockReturnValue(true);
    const finishFailedRefresh = deferNextRequest();
    fireEvent.click(conflictRefresh());
    await finishFailedRefresh({ message: 'read failed' }, 500);
    expect(rawInput()).toHaveValue('conflicting draft');
    expect(useMemoryStore.getState().conflict).toBe(true);

    const finishRefresh = deferNextRequest();
    fireEvent.click(conflictRefresh());
    await finishRefresh(savedFile);
    expect(rawInput()).toHaveValue(savedFile.content);
    expect(useMemoryStore.getState().conflict).toBe(false);
    change(rawInput(), 'manually merged after reloading');
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, ...savedFile }));
    fireEvent.click(saveButton());
    await waitFor(() => expect(useMemoryStore.getState().saving).toBe(false));
    expect(lastPutBody().baseUpdatedAt).toBe(savedFile.updatedAt);
  });
});
