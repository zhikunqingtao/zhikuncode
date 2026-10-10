import { describe, expect, it, vi } from 'vitest';
import { SpiderResourceScope } from './resourceScope';

describe('SpiderResourceScope', () => {
    it('unwinds completed allocation steps in reverse order after later initialization fails', () => {
        const scope = new SpiderResourceScope();
        const released: string[] = [];
        const originalFailure = new Error('first resize failed');
        expect(() => {
            try {
                scope.track({ name: 'renderer' }, resource => released.push(resource.name));
                scope.defer(() => released.push('canvas'));
                scope.defer(() => released.push('observers'));
                scope.defer(() => released.push('runtime subscription'));
                throw originalFailure;
            } catch (error) {
                scope.dispose();
                throw error;
            }
        }).toThrow(originalFailure);
        expect(released).toEqual(['runtime subscription', 'observers', 'canvas', 'renderer']);
        scope.dispose();
        expect(released).toHaveLength(4);
    });

    it('continues releasing resources when an individual cleanup throws', () => {
        const scope = new SpiderResourceScope();
        const releaseRenderer = vi.fn();
        const cleanupFailure = new Error('observer cleanup failed');
        scope.defer(releaseRenderer);
        scope.defer(() => { throw cleanupFailure; });
        expect(scope.dispose()).toEqual([cleanupFailure]);
        expect(releaseRenderer).toHaveBeenCalledOnce();
    });

    it('hands off successful construction without prematurely releasing live resources', () => {
        const scope = new SpiderResourceScope();
        const release = vi.fn();
        const resource = { id: 1 };
        expect(scope.track(resource, release)).toBe(resource);
        scope.detach();
        scope.dispose();
        expect(release).not.toHaveBeenCalled();
    });

    it('immediately releases a late allocation after the construction scope has failed', () => {
        const scope = new SpiderResourceScope();
        const release = vi.fn();
        scope.dispose();
        scope.track({ id: 2 }, release);
        expect(release).toHaveBeenCalledWith({ id: 2 });
    });
});
