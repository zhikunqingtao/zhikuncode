import { describe, expect, it } from 'vitest';
import {
    WORKBENCH_ENABLED_KEY,
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

    it('defaults the local workbench to enabled and simple', () => {
        expect(readWorkbenchEnabled(memoryStorage(), undefined)).toBe(true);
        expect(readSessionWorkbenchView('session-1', 'simple', memoryStorage())).toBe('simple');
    });

    it('uses a per-session view without changing the machine default', () => {
        const storage = memoryStorage({
            'zhikun.workbench.session-view.session-1': 'development',
        });
        expect(readSessionWorkbenchView('session-1', 'simple', storage)).toBe('development');
        expect(readSessionWorkbenchView('session-2', 'simple', storage)).toBe('simple');
    });

});
