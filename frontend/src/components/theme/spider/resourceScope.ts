/** Owns completed construction steps until the scene's normal disposer takes over. */
export class SpiderResourceScope {
    private cleanups: Array<() => void> = [];
    private closed = false;

    /** Register immediately after allocation, before the next operation can fail. */
    defer(cleanup: () => void): void {
        if (this.closed) {
            // A late asynchronous construction step must not escape a failed owner.
            cleanup();
            return;
        }
        this.cleanups.push(cleanup);
    }

    track<T>(resource: T, cleanup: (resource: T) => void): T {
        this.defer(() => cleanup(resource));
        return resource;
    }

    /** Construction succeeded; the caller now owns all registered resources. */
    detach(): void {
        this.closed = true;
        this.cleanups = [];
    }

    /** Unwind every step, preserving the original failure even if a cleanup throws. */
    dispose(): unknown[] {
        if (this.closed) return [];
        this.closed = true;
        const cleanups = this.cleanups;
        this.cleanups = [];
        const errors: unknown[] = [];
        for (let index = cleanups.length - 1; index >= 0; index--) {
            try { cleanups[index](); }
            catch (error) { errors.push(error); }
        }
        return errors;
    }
}
