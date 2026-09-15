import { expect, it } from 'vitest';
import { PageSessionOrder } from './pageSessionOrder';
it('orders on entry, preserves existing ranks on refresh, and resets for a new page', () => {
    const order = new PageSessionOrder();
    const a = { id: 'a', updatedAt: '2026-09-15T01:00:00Z' };
    const b = { id: 'b', updatedAt: '2026-09-15T02:00:00Z' };
    expect(order.order([a,b]).map(x=>x.id)).toEqual(['b','a']);
    const recentA = { ...a, updatedAt: '2026-09-15T03:00:00Z' };
    expect(order.order([recentA,b]).map(x=>x.id)).toEqual(['b','a']);
    expect(new PageSessionOrder().order([recentA,b]).map(x=>x.id)).toEqual(['a','b']);
    expect(order.order([recentA,{id:'c',updatedAt:'2026-09-16'}]).map(x=>x.id)).toEqual(['a','c']);
});
