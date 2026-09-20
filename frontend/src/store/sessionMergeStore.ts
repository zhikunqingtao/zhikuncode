import { create } from 'zustand';
import type { SessionSummary } from '@/utils/sessionGroups';
import { generateUUID } from '@/utils/uuid';
import { useNotificationStore } from '@/store/notificationStore';

export interface MergeRequest {
    sourceSessionIds: string[];
    primarySessionId: string;
    title: string;
    model: string;
}
export interface MergeOperation {
    operationId: string;
    targetSessionId: string;
    status: 'preparing' | 'completed' | 'failed';
    stage: string;
    request: MergeRequest;
    error?: string;
    result: { copiedCount?: number; warningCount?: number; indexPath?: string;
        warnings?: { originalPath: string; status: string; reason: string }[] };
}
export interface MergePending { key: string; request: MergeRequest; operation?: MergeOperation }
const NO_MERGE_SOURCES: readonly string[] = [];
export function selectMergeSourceIds(state: { pending: MergePending | null }): readonly string[] {
    const pending = state.pending;
    return pending && (!pending.operation || pending.operation.status === 'preparing')
        ? pending.request.sourceSessionIds : NO_MERGE_SOURCES;
}

// Compare the complete JSON response, including errors and warning details, independent of key order.
function sameProgress(a: unknown, b: unknown): boolean {
    if (Object.is(a, b)) return true;
    if (!a || !b || typeof a !== 'object' || typeof b !== 'object') return false;
    if (Array.isArray(a) || Array.isArray(b)) {
        return Array.isArray(a) && Array.isArray(b) && a.length === b.length
            && a.every((value, index) => sameProgress(value, b[index]));
    }
    const left = a as Record<string, unknown>, right = b as Record<string, unknown>;
    return Object.keys(left).length === Object.keys(right).length
        && Object.keys(left).every(key => Object.hasOwn(right, key) && sameProgress(left[key], right[key]));
}

const LEGACY_STORAGE_KEY = 'session-merge-pending-v1';
const STORAGE_PREFIX = 'session-merge-pending-v2:';
const storageKey = (key: string) => STORAGE_PREFIX + key;
function decode(value: string | null): MergePending | null {
    try {
        const parsed = JSON.parse(value ?? 'null');
        return typeof parsed?.key === 'string' && Array.isArray(parsed.request?.sourceSessionIds) ? parsed : null;
    } catch { return null; }
}
function restore(activeOnly = false): MergePending | null {
    try {
        const records = new Map<string, MergePending>();
        const legacy = decode(localStorage.getItem(LEGACY_STORAGE_KEY));
        if (legacy) records.set(legacy.key, legacy);
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (!key?.startsWith(STORAGE_PREFIX)) continue;
            const pending = decode(localStorage.getItem(key));
            if (pending && key === storageKey(pending.key)) records.set(pending.key, pending);
        }
        const active = (p: MergePending) => !p.operation || p.operation.status === 'preparing';
        const saved = [...records.values()];
        const pending = saved.find(active) ?? (activeOnly ? undefined : saved[0]);
        // Revalidate even saved terminal results with the server after reload.
        return pending ? { ...pending, operation: pending.operation
            ? { ...pending.operation, status: 'preparing', stage: 'recovering', result: {} } : undefined } : null;
    } catch { return null; }
}
function persist(pending: MergePending) {
    // Each identity owns a separate record, including simultaneous submissions from different tabs.
    localStorage.setItem(storageKey(pending.key), JSON.stringify({ ...pending,
        operation: pending.operation ? { ...pending.operation, result: {} } : undefined,
    }));
    removeLegacy(pending.key);
}
function removeLegacy(key: string) {
    if (decode(localStorage.getItem(LEGACY_STORAGE_KEY))?.key === key) localStorage.removeItem(LEGACY_STORAGE_KEY);
}
function removeSaved(key: string) {
    localStorage.removeItem(storageKey(key));
    removeLegacy(key);
}
// Once the server has responded, storage failure must not override its authoritative state.
function persistProgress(pending: MergePending | null, key: string) {
    try { if (pending) persist(pending); else removeSaved(key); return null; } catch {
        const warning = '无法更新本地恢复信息；当前页面状态已更新，刷新后可能再次显示旧进度。';
        const notifications = useNotificationStore.getState();
        notifications.removeNotification('merge-storage');
        notifications.addNotification({ key: 'merge-storage', level: 'warning', message: warning });
        return warning;
    }
}
interface State {
    pending: MergePending | null;
    source: SessionSummary | null;
    open: boolean;
    error: string | null;
    storageWarning: string | null;
    recoveryNotice: string | null;
    submitting: boolean;
    openDialog: (source?: SessionSummary) => void;
    closeDialog: () => void;
    submit: (request: MergeRequest) => Promise<void>;
    refresh: () => Promise<void>;
    dismiss: () => void;
}
let inflight: Promise<void> | null = null;
export const useSessionMergeStore = create<State>((set, get) => ({
    pending: restore(), source: null, open: false, error: null, storageWarning: null, recoveryNotice: null, submitting: false,
    openDialog: source => set({ open: true, source: source ?? get().source }),
    closeDialog: () => set({ open: false }),
    submit: async request => {
        if (get().pending) return;
        const existing = restore(true);
        if (existing) {
            set({ pending: existing, error: null, recoveryNotice: '已恢复其他页面保存的合并操作。' });
            await get().refresh(); return;
        }
        const pending = { key: generateUUID(), request };
        try { persist(pending); } catch { set({ error: '无法保存恢复信息，请检查浏览器存储后重试。' }); return; }
        set({ pending, error: null, storageWarning: null, recoveryNotice: null });
        await get().refresh();
    },
    refresh: () => {
        if (inflight) return inflight;
        const pending = get().pending;
        if (!pending || (pending.operation && pending.operation.status !== 'preparing')) return Promise.resolve();
        inflight = (async () => {
            set({ submitting: true });
            const controller = new AbortController();
            const timeout = window.setTimeout(() => controller.abort(), 15000);
            try {
                const response = pending.operation
                    ? await fetch(`/api/session-merges/${encodeURIComponent(pending.operation.operationId)}`, {
                        headers: { Accept: 'application/json' }, signal: controller.signal })
                    : await fetch('/api/sessions/merge', { method: 'POST',
                        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': pending.key },
                        body: JSON.stringify(pending.request), signal: controller.signal });
                if (get().pending?.key !== pending.key) return;
                if (!response.ok) {
                    const body = await response.json().catch(() => ({}));
                    if (get().pending?.key !== pending.key) return;
                    if (pending.operation && response.status === 404
                        && body?.error?.code === 'MERGE_OPERATION_NOT_FOUND') {
                        // Only an authoritative missing-operation response invalidates recovery.
                        // Release local occupancy before best-effort storage cleanup; execution stays server-gated.
                        set({ pending: null, error: null,
                            recoveryNotice: '合并操作已不存在，已解除本地占用。请到会话列表确认结果。' });
                        set({ storageWarning: persistProgress(null, pending.key) });
                        return;
                    }
                    const message = body.error?.message ?? body.message ?? body.detail ?? `合并请求失败（${response.status}）`;
                    if (!pending.operation && response.status >= 400 && response.status < 500) {
                        set({ pending: null, error: message, storageWarning: persistProgress(null, pending.key) });
                        return;
                    }
                    throw new Error(message);
                }
                const operation: MergeOperation = await response.json();
                if (get().pending?.key !== pending.key) return;
                const changed = !sameProgress(pending.operation, operation);
                const next = changed ? { ...pending, operation } : pending;
                // A successful retry still clears transient errors and retries failed persistence.
                const storageWarning = changed || get().storageWarning ? persistProgress(next, pending.key) : null;
                if (changed || get().error !== null || get().storageWarning !== storageWarning) {
                    set({ pending: next, error: null, storageWarning });
                }
                if (operation.operationId !== pending.operation?.operationId || operation.status !== pending.operation?.status)
                    window.dispatchEvent(new Event('session-list-updated'));
                if (operation.status !== 'preparing') useNotificationStore.getState().addNotification({
                    key: `merge-${operation.operationId}`, level: operation.status === 'completed' ? 'success' : 'error',
                    message: operation.status === 'completed' ? `合并完成，未收录 ${operation.result.warningCount ?? 0} 个文件。可通过“合并结果”打开新会话。` : operation.error ?? '合并失败',
                });
            } catch (error) {
                if (get().pending?.key !== pending.key) return;
                set({ error: controller.signal.aborted ? '连接超时，恢复信息已保留，正在重试查询。'
                    : error instanceof Error ? error.message : '读取进度失败，可重试恢复。' });
            } finally { window.clearTimeout(timeout); set({ submitting: false }); }
        })().finally(() => {
            inflight = null;
            if (get().pending && get().pending?.key !== pending.key) void get().refresh();
        });
        return inflight;
    },
    dismiss: () => {
        const pending = get().pending;
        if (!pending?.operation || pending.operation.status === 'preparing') return;
        const storageWarning = persistProgress(null, pending.key);
        set({ pending: null, error: null, open: false, source: null, storageWarning });
    },
}));

export function isMergeSource(sessionId: string | null): boolean {
    const pending = useSessionMergeStore.getState().pending;
    return !!sessionId && !!pending && pending.operation?.status !== 'completed'
        && pending.operation?.status !== 'failed' && pending.request.sourceSessionIds.includes(sessionId);
}

/** Subscribe once with the panel lifetime, so tab changes cannot leave orphan listeners. */
export function subscribeMergeRecovery(): () => void {
    const sync = (event: StorageEvent) => {
        if (!event.key || !(event.key.startsWith(STORAGE_PREFIX) || event.key === LEGACY_STORAGE_KEY)) return;
        const current = useSessionMergeStore.getState().pending;
        if (current) {
            if (event.key !== storageKey(current.key) || event.newValue !== null) return;
            // A peer dismissed or authoritatively rejected this exact operation.
            useSessionMergeStore.setState({ pending: null, error: null, storageWarning: null });
        }
        const pending = restore(true);
        if (pending) {
            useSessionMergeStore.setState({ pending, error: null });
            void useSessionMergeStore.getState().refresh();
        }
    };
    window.addEventListener('storage', sync);
    return () => window.removeEventListener('storage', sync);
}
