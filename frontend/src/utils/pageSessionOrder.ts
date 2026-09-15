/**
 * Page-lifetime ordering: refresh metadata without moving existing conversations.
 *
 * `order()` takes a `placement` that decides where ids not seen before on this
 * page are inserted:
 * - `'end'` (default): appended after every already-known entry. Used by cursor
 *   pagination ("load more"), which merges in *older* history that belongs at
 *   the bottom of the list.
 * - `'front'`: inserted before every already-known entry. Used by cursor-less
 *   refreshes (initial load, polling, WS events) so brand-new sessions surface
 *   at the top. Within one batch newer items (by `updatedAt`) come first, and
 *   a later front batch lands entirely before earlier front batches (latest
 *   discovery wins).
 *
 * The very first call (empty rank table) always ranks by `updatedAt` (newest
 * first); `placement` has no effect until ranks exist.
 */
export class PageSessionOrder {
    private ranks = new Map<string, number>();
    /** Decrements below zero so 'front' newcomers rank ahead of every known entry. */
    private frontCursor = 0;
    /**
     * Returns `items` in page-stable order. `placement` only affects ids not
     * seen before: 'end' appends them (paginated history), 'front' ranks them
     * ahead of everything known so far (newly discovered sessions, newest first).
     */
    order<T extends { id: string; updatedAt: string }>(items: readonly T[], placement: 'front' | 'end' = 'end'): T[] {
        const incoming = this.ranks.size === 0
            ? [...items].sort((a, b) => (Date.parse(b.updatedAt) || 0) - (Date.parse(a.updatedAt) || 0) || a.id.localeCompare(b.id))
            : [...items];
        if (placement === 'front' && this.ranks.size > 0) {
            // Display order within the batch: newest first (same tiebreak as the first call).
            const newcomers = incoming
                .filter(item => !this.ranks.has(item.id))
                .sort((a, b) => (Date.parse(b.updatedAt) || 0) - (Date.parse(a.updatedAt) || 0) || a.id.localeCompare(b.id));
            // Walk oldest→newest so the newest takes the smallest rank; the cursor
            // keeps decrementing, so later front batches land before earlier ones.
            for (let i = newcomers.length - 1; i >= 0; i--) {
                if (!this.ranks.has(newcomers[i].id)) this.ranks.set(newcomers[i].id, --this.frontCursor);
            }
        } else {
            for (const item of incoming) if (!this.ranks.has(item.id)) this.ranks.set(item.id, this.ranks.size);
        }
        return incoming.sort((a, b) => this.ranks.get(a.id)! - this.ranks.get(b.id)!);
    }
}
// Kept outside components so opening a mobile drawer does not reset the order.
export const pageSessionOrder = new PageSessionOrder();
