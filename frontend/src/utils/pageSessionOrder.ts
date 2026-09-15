/** Page-lifetime ordering: refresh metadata without moving existing conversations. */
export class PageSessionOrder {
    private ranks = new Map<string, number>();
    order<T extends { id: string; updatedAt: string }>(items: readonly T[]): T[] {
        const incoming = this.ranks.size === 0
            ? [...items].sort((a, b) => (Date.parse(b.updatedAt) || 0) - (Date.parse(a.updatedAt) || 0) || a.id.localeCompare(b.id))
            : [...items];
        for (const item of incoming) if (!this.ranks.has(item.id)) this.ranks.set(item.id, this.ranks.size);
        return incoming.sort((a, b) => this.ranks.get(a.id)! - this.ranks.get(b.id)!);
    }
}
// Kept outside components so opening a mobile drawer does not reset the order.
export const pageSessionOrder = new PageSessionOrder();
