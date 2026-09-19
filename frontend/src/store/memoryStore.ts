import { create } from 'zustand';

export type MemorySource = 'AUTO' | 'USER' | 'TOOL';

export interface MemoryEntry {
  source: MemorySource;
  category: string;
  timestamp: string;
  content: string;
}

interface MemoryFileResponse {
  content: string;
  entries: MemoryEntry[];
  updatedAt: string | null;
  size: number;
  maxSize: number;
}

interface MemorySaveResponse {
  success: boolean;
  content: string;
  updatedAt: string | null;
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
  loadFile: () => Promise<void>;
  saveRaw: (content: string) => Promise<boolean>;
  saveEntries: (entries: MemoryEntry[]) => Promise<boolean>;
  setDirty: (dirty: boolean) => void;
}

/** 后端按文件字节数上报 size；保存成功后本地以 UTF-8 字节数估算，保持用量指示连续。 */
const byteLength = (text: string): number => new TextEncoder().encode(text).length;

/** PUT 公共流程：409 → conflict，413 → error，其余非 2xx 抛错。成功返回响应体。 */
async function putMemory(
  url: string,
  body: Record<string, unknown>,
  set: (partial: Partial<MemoryStore>) => void,
): Promise<MemorySaveResponse | null> {
  const response = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (response.status === 409) {
    set({ saving: false, conflict: true });
    return null;
  }
  if (response.status === 413) {
    const payload = await response.json().catch(() => ({})) as { message?: string };
    set({ saving: false, error: payload.message ?? '内容超出记忆文件大小上限' });
    return null;
  }
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return await response.json() as MemorySaveResponse;
}

export const useMemoryStore = create<MemoryStore>((set, get) => ({
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
    set({ loading: true, error: null });
    try {
      const response = await fetch('/api/memory/file');
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json() as MemoryFileResponse;
      set({
        content: data.content ?? '',
        entries: data.entries ?? [],
        updatedAt: data.updatedAt ?? null,
        size: data.size ?? 0,
        maxSize: data.maxSize ?? 0,
        loading: false,
        loaded: true,
        conflict: false,
        dirty: false,
      });
    } catch (error) {
      set({ loading: false, loaded: true, error: error instanceof Error ? error.message : String(error) });
    }
  },

  saveRaw: async (content) => {
    set({ saving: true, error: null });
    try {
      const data = await putMemory('/api/memory/file', { content, baseUpdatedAt: get().updatedAt }, set);
      if (!data) return false;
      const saved = data.content ?? content;
      set({
        saving: false,
        content: saved,
        updatedAt: data.updatedAt ?? null,
        size: byteLength(saved),
        dirty: false,
      });
      return true;
    } catch (error) {
      set({ saving: false, error: error instanceof Error ? error.message : String(error) });
      return false;
    }
  },

  saveEntries: async (entries) => {
    set({ saving: true, error: null });
    try {
      const data = await putMemory('/api/memory/file/entries', { entries, baseUpdatedAt: get().updatedAt }, set);
      if (!data) return false;
      const saved = data.content ?? '';
      set({
        saving: false,
        content: saved,
        entries,
        updatedAt: data.updatedAt ?? null,
        size: byteLength(saved),
        dirty: false,
      });
      return true;
    } catch (error) {
      set({ saving: false, error: error instanceof Error ? error.message : String(error) });
      return false;
    }
  },

  setDirty: (dirty) => set({ dirty }),
}));
