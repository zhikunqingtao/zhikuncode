export const WORKBENCH_ENABLED_KEY = 'zhikun.workbench.enabled';
export const WORKBENCH_DEFAULT_VIEW_KEY = 'zhikun.workbench.default-view';
export const WORKBENCH_SESSION_VIEW_PREFIX = 'zhikun.workbench.session-view.';

export type WorkbenchViewMode = 'simple' | 'development';

export interface StorageLike {
    getItem(key: string): string | null;
    setItem(key: string, value: string): void;
    removeItem(key: string): void;
}

function browserStorage(): StorageLike | null {
    if (typeof window === 'undefined') return null;
    try {
        return window.localStorage;
    } catch {
        return null;
    }
}

export function parseBooleanFlag(value: unknown): boolean | null {
    if (value === 'true') return true;
    if (value === 'false') return false;
    return null;
}

export function readWorkbenchEnabled(
    storage: StorageLike | null = browserStorage(),
    envValue: unknown = (import.meta as ImportMeta & {
        env?: Record<string, string | undefined>;
    }).env?.VITE_LOCAL_SIMPLE_WORKBENCH,
): boolean {
    if (storage) {
        try {
            const raw = storage.getItem(WORKBENCH_ENABLED_KEY);
            const stored = parseBooleanFlag(raw);
            if (stored !== null) return stored;
            if (raw !== null) storage.removeItem(WORKBENCH_ENABLED_KEY);
        } catch {
            // Privacy mode or a full storage quota must not block startup.
        }
    }
    return parseBooleanFlag(envValue) ?? true;
}

export function parseWorkbenchView(value: unknown): WorkbenchViewMode | null {
    return value === 'simple' || value === 'development' ? value : null;
}

export function readDefaultWorkbenchView(
    storage: StorageLike | null = browserStorage(),
): WorkbenchViewMode {
    if (!storage) return 'simple';
    try {
        const raw = storage.getItem(WORKBENCH_DEFAULT_VIEW_KEY);
        const parsed = parseWorkbenchView(raw);
        if (parsed) return parsed;
        if (raw !== null) storage.removeItem(WORKBENCH_DEFAULT_VIEW_KEY);
    } catch {
        // Fall through to the safe first-use default.
    }
    return 'simple';
}

export function readSessionWorkbenchView(
    sessionId: string | null,
    defaultView: WorkbenchViewMode,
    storage: StorageLike | null = browserStorage(),
): WorkbenchViewMode {
    if (!sessionId || !storage) return defaultView;
    try {
        const key = `${WORKBENCH_SESSION_VIEW_PREFIX}${sessionId}`;
        const raw = storage.getItem(key);
        const parsed = parseWorkbenchView(raw);
        if (parsed) return parsed;
        if (raw !== null) storage.removeItem(key);
    } catch {
        // Fall through to the machine default.
    }
    return defaultView;
}

export function writeStorageValue(
    key: string,
    value: string,
    storage: StorageLike | null = browserStorage(),
): void {
    if (!storage) return;
    try {
        storage.setItem(key, value);
    } catch {
        // View switching remains usable even when persistence is unavailable.
    }
}
