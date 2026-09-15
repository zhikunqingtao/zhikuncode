import { expect, it } from 'vitest';
import { PageSessionOrder } from './pageSessionOrder';

it('orders on entry, preserves existing ranks on refresh, and resets for a new page', () => {
    const order = new PageSessionOrder();
    const a = { id: 'a', updatedAt: '2026-09-15T01:00:00Z' };
    const b = { id: 'b', updatedAt: '2026-09-15T02:00:00Z' };
    expect(order.order([a, b]).map(x => x.id)).toEqual(['b', 'a']);
    const recentA = { ...a, updatedAt: '2026-09-15T03:00:00Z' };
    expect(order.order([recentA, b]).map(x => x.id)).toEqual(['b', 'a']);
    expect(new PageSessionOrder().order([recentA, b]).map(x => x.id)).toEqual(['a', 'b']);
    // Default placement 'end' (pagination semantics): unseen ids append at the bottom.
    expect(order.order([recentA, { id: 'c', updatedAt: '2026-09-16' }]).map(x => x.id)).toEqual(['a', 'c']);
});

it('front placement puts newly discovered sessions ahead of known ones', () => {
    const order = new PageSessionOrder();
    const a = { id: 'a', updatedAt: '2026-09-15T01:00:00Z' };
    const b = { id: 'b', updatedAt: '2026-09-15T02:00:00Z' };
    order.order([a, b]); // first load: ['b','a']
    const c = { id: 'c', updatedAt: '2026-09-16T00:00:00Z' };
    expect(order.order([c, b, a], 'front').map(x => x.id)).toEqual(['c', 'b', 'a']);
});

it('front placement orders one batch newest-first', () => {
    const order = new PageSessionOrder();
    const a = { id: 'a', updatedAt: '2026-09-15T01:00:00Z' };
    const b = { id: 'b', updatedAt: '2026-09-15T02:00:00Z' };
    order.order([a, b]);
    const c = { id: 'c', updatedAt: '2026-09-16T00:00:00Z' };
    const d = { id: 'd', updatedAt: '2026-09-16T01:00:00Z' }; // newer than c
    expect(order.order([c, d, a, b], 'front').map(x => x.id)).toEqual(['d', 'c', 'b', 'a']);
});

it('later front batches land entirely before earlier front batches', () => {
    const order = new PageSessionOrder();
    const a = { id: 'a', updatedAt: '2026-09-15T01:00:00Z' };
    const b = { id: 'b', updatedAt: '2026-09-15T02:00:00Z' };
    order.order([a, b]);
    const c = { id: 'c', updatedAt: '2026-09-16T00:00:00Z' };
    order.order([c, b, a], 'front'); // ['c','b','a']
    const d = { id: 'd', updatedAt: '2026-09-14T00:00:00Z' }; // older than c, discovered later
    expect(order.order([d, c, b, a], 'front').map(x => x.id)).toEqual(['d', 'c', 'b', 'a']);
});

it('front placement keeps the relative order of already-known entries', () => {
    const order = new PageSessionOrder();
    const a = { id: 'a', updatedAt: '2026-09-15T01:00:00Z' };
    const b = { id: 'b', updatedAt: '2026-09-15T02:00:00Z' };
    const c = { id: 'c', updatedAt: '2026-09-15T03:00:00Z' };
    order.order([a, b, c]); // ['c','b','a']
    const cOlder = { ...c, updatedAt: '2026-09-14T00:00:00Z' }; // metadata changed: must not move
    const d = { id: 'd', updatedAt: '2026-09-16T00:00:00Z' };
    expect(order.order([a, cOlder, b, d], 'front').map(x => x.id)).toEqual(['d', 'c', 'b', 'a']);
});

it('end placement (pagination) still appends older history after front-placed entries', () => {
    const order = new PageSessionOrder();
    const a = { id: 'a', updatedAt: '2026-09-15T01:00:00Z' };
    const b = { id: 'b', updatedAt: '2026-09-15T02:00:00Z' };
    order.order([a, b]);
    const c = { id: 'c', updatedAt: '2026-09-16T00:00:00Z' };
    order.order([c, b, a], 'front'); // ['c','b','a']
    const old = { id: 'old', updatedAt: '2026-09-01T00:00:00Z' };
    expect(order.order([old, a, b, c]).map(x => x.id)).toEqual(['c', 'b', 'a', 'old']);
});
