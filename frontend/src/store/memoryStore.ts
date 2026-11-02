import { create } from 'zustand';

export type MemorySource = 'AUTO' | 'USER' | 'TOOL';

export interface MemoryEntry {
  source: MemorySource;
  category: string;
  timestamp: string;
  content: string;
}

export interface MemorySnapshot {
  content: string;
  entries: MemoryEntry[];
  updatedAt: string | null;
  size: number;
  maxSize: number;
}

/** 保存成功响应：服务端实际落盘的规范化结果，前端以此为准同步两种视图。 */
interface MemorySaveResponse extends MemorySnapshot {
  success: boolean;
}

interface MemoryStore {
  content: string;
  entries: MemoryEntry[];
  updatedAt: string | null;
  size: number;
  maxSize: number;
  loading: boolean;
  saving: boolean;
  loaded: boolean;
  error: string | null;
  conflict: boolean;
  dirty: boolean;
  loadFile: () => Promise<MemorySnapshot | null>;
  saveRaw: (content: string, baseUpdatedAt: string | null) => Promise<MemorySnapshot | null>;
  saveEntries: (entries: MemoryEntry[], baseUpdatedAt: string | null) => Promise<MemorySnapshot | null>;
  setDirty: (dirty: boolean) => void;
}

/** 用服务端保存响应覆盖本地状态（content / entries / size 单位与加载时一致，均以服务端为准）。 */
const snapshotOf = (data: MemorySnapshot): MemorySnapshot => ({
  content: data.content ?? '',
  entries: data.entries ?? [],
  updatedAt: data.updatedAt ?? null,
  size: data.size ?? 0,
  maxSize: data.maxSize ?? 0,
});

/** PUT 公共流程：409 → conflict，413 → error，其余非 2xx 抛错。成功返回响应体。 */
async function putMemory(
  url: string,
  body: Record<string, unknown>,
  set: (partial: Partial<MemoryStore>) => void,
): Promise<MemorySaveResponse | null> {
  const controller = new AbortController();
  // Bound both the response headers and body so queued reads can recover.
  const timeout = setTimeout(() => controller.abort(), 15_000);
  try {
    const response = await fetch(url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (response.status === 409) {
      set({ conflict: true });
      return null;
    }
    if (response.status === 413) {
      const payload = await response.json().catch(() => ({})) as { message?: string };
      set({ error: payload.message ?? '内容超出记忆文件大小上限' });
      return null;
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json() as MemorySaveResponse;
  } catch (error) {
    if (controller.signal.aborted) {
      // Aborting the client does not prove that the server rolled back its write.
      throw new Error('保存超时，结果未确认。草稿已保留，请先复制草稿并刷新核对后再保存。');
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

export const useMemoryStore = create<MemoryStore>((set, get) => {
  let readSequence = 0;
  let mutationVersion = 0;
  let writeCompletion: Promise<void> | null = null;

  const save = async (
    url: string,
    body: Record<string, unknown>,
    baseUpdatedAt: string | null,
  ): Promise<MemorySnapshot | null> => {
    if (get().saving) return null;
    let finishWrite!: () => void;
    writeCompletion = new Promise<void>(resolve => { finishWrite = resolve; });
    // Invalidate reads started before the write, including their error responses.
    mutationVersion += 1;
    readSequence += 1;
    set({ saving: true, loading: false, error: null });
    try {
      const data = await putMemory(url, { ...body, baseUpdatedAt }, set);
      if (!data) return null;
      const snapshot = snapshotOf(data);
      set({ ...snapshot, conflict: false, dirty: false });
      // Return this write's ACK, rather than asking the page to reread mutable store state.
      return snapshot;
    } catch (error) {
      set({ error: error instanceof Error ? error.message : String(error) });
      return null;
    } finally {
      mutationVersion += 1;
      set({ saving: false });
      writeCompletion = null;
      // Release queued loads on success, conflict and failure alike.
      finishWrite();
    }
  };

  return {
    content: '',
    entries: [],
    updatedAt: null,
    size: 0,
    maxSize: 0,
    loading: false,
    saving: false,
    loaded: false,
    error: null,
    conflict: false,
    dirty: false,

    loadFile: async () => {
      const requestId = ++readSequence;
      set({ loading: true, error: null });
      // A remounted page may request a read while another editor's PUT is pending.
      // Start that GET after the ACK, so it cannot be overwritten by an older ACK.
      const pendingWrite = writeCompletion;
      if (pendingWrite) await pendingWrite;
      if (requestId !== readSequence) return null;
      const versionAtStart = mutationVersion;
      const isCurrent = () => requestId === readSequence && versionAtStart === mutationVersion;
      // A queued read must not hide a write failure that happened while it waited.
      const writeError = pendingWrite ? get().error : null;
      set({ loading: true, error: writeError });
      try {
        const response = await fetch('/api/memory/file');
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json() as MemorySnapshot;
        if (!isCurrent()) return null;
        const snapshot = snapshotOf(data);
        set({
          ...snapshot,
          loading: false,
          loaded: true,
          conflict: false,
          dirty: false,
        });
        return snapshot;
      } catch (error) {
        if (!isCurrent()) return null;
        set({
          loading: false,
          loaded: true,
          error: writeError ?? (error instanceof Error ? error.message : String(error)),
        });
        return null;
      }
    },

    saveRaw: (content, baseUpdatedAt) => save('/api/memory/file', { content }, baseUpdatedAt),

    saveEntries: (entries, baseUpdatedAt) => save('/api/memory/file/entries', { entries }, baseUpdatedAt),

    setDirty: (dirty) => set({ dirty }),
  };
});
