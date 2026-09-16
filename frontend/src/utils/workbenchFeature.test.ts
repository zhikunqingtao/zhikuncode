import { describe, expect, it } from 'vitest';
import {
    WORKBENCH_ENABLED_KEY,
    WORKBENCH_DEFAULT_VIEW_KEY,
    readDefaultWorkbenchView,
    readSessionWorkbenchView,
    readWorkbenchEnabled,
    type StorageLike,
} from './workbenchFeature';

function memoryStorage(initial: Record<string, string> = {}): StorageLike {
    const values = new Map(Object.entries(initial));
    return {
        getItem: key => values.get(key) ?? null,
        setItem: (key, value) => { values.set(key, value); },
        removeItem: key => { values.delete(key); },
    };
}

describe('workbenchFeature', () => {
    it('lets an explicit local flag override the environment', () => {
        expect(readWorkbenchEnabled(memoryStorage({
            [WORKBENCH_ENABLED_KEY]: 'false',
        }), 'true')).toBe(false);
    });

    it('defaults the local workbench to enabled and development', () => {
        expect(readWorkbenchEnabled(memoryStorage(), undefined)).toBe(true);
        const defaultView = readDefaultWorkbenchView(memoryStorage());
        expect(defaultView).toBe('development');
        expect(readSessionWorkbenchView('session-1', defaultView, memoryStorage())).toBe('development');
        expect(readDefaultWorkbenchView(null)).toBe('development');
        expect(readDefaultWorkbenchView(memoryStorage({ [WORKBENCH_DEFAULT_VIEW_KEY]: 'invalid' }))).toBe('development');
    });

    it('preserves a manually chosen default and falls back if storage is unavailable', () => {
        expect(readDefaultWorkbenchView(memoryStorage({ [WORKBENCH_DEFAULT_VIEW_KEY]: 'simple' }))).toBe('simple');
        const storage = memoryStorage();
        storage.getItem = () => { throw new Error('Storage unavailable'); };
        expect(readDefaultWorkbenchView(storage)).toBe('development');
    });

    it('uses a per-session view without changing the machine default', () => {
        const storage = memoryStorage({
            'zhikun.workbench.session-view.session-1': 'development',
        });
        expect(readSessionWorkbenchView('session-1', 'simple', storage)).toBe('development');
        expect(readSessionWorkbenchView('session-2', 'simple', storage)).toBe('simple');
    });

});
