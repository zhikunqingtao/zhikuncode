import { describe, expect, it } from 'vitest';
import { planContactBodyPoint, type ContactArea, type ContactPoint } from './contactPlanner';

const area: ContactArea = { left: 0, top: 0, right: 1000, bottom: 700 };
const anchor: ContactArea = { left: 120, top: 100, right: 180, bottom: 120 };
const clear = () => false;

function reach(point: ContactPoint, rect = anchor): number {
    const gripX = point.x > (rect.left + rect.right) / 2 ? rect.right + 4 : rect.left - 4;
    return Math.hypot(point.x - gripX, point.y - (rect.top + rect.bottom) / 2, 60);
}

describe('planContactBodyPoint', () => {
    it('preserves an already safe, reachable preferred body center', () => {
        const preferred = { x: 250, y: 145 };
        expect(planContactBodyPoint(anchor, preferred, area, 1, clear)).toEqual(preferred);
    });

    it('goes around a neighboring disclosure button without fleeing to the far edge', () => {
        const blocked = (x: number, y: number) => x > 90 && x < 650 && y >= 135 && y < 195;
        const point = planContactBodyPoint(anchor, { x: 235, y: 155 }, area, 1, blocked)!;
        expect(point).not.toBeNull();
        expect(blocked(point.x, point.y)).toBe(false);
        expect(reach(point)).toBeLessThan(145);
        expect(point.x).toBeLessThan(330);
        expect(point.y).toBeLessThan(135);
    });

    it('can change contact side when the entire preferred side is excluded', () => {
        const blocked = (x: number) => x >= 150;
        const point = planContactBodyPoint(anchor, { x: 240, y: 150 }, area, 1, blocked)!;
        expect(point).not.toBeNull();
        expect(point.x).toBeLessThan(150);
        expect(reach(point)).toBeLessThan(145);
    });

    it('budgets the unscaled 60px body elevation at mobile scale', () => {
        const scale = 0.72;
        const preferred = { x: 266, y: 150 };
        const point = planContactBodyPoint(anchor, preferred, area, scale, clear)!;
        expect(reach(preferred)).toBeGreaterThan(145 * scale);
        expect(point).not.toEqual(preferred);
        expect(reach(point)).toBeLessThan(145 * scale);
    });

    it('stays within a narrow mobile chat area while remaining reachable', () => {
        const phoneArea = { left: 10, top: 80, right: 310, bottom: 500 };
        const edgeAnchor = { left: 268, top: 100, right: 304, bottom: 120 };
        const point = planContactBodyPoint(edgeAnchor, { x: 390, y: 155 }, phoneArea, 0.72, clear)!;
        expect(point.x).toBeGreaterThanOrEqual(10);
        expect(point.x).toBeLessThanOrEqual(310);
        expect(point.y).toBeGreaterThanOrEqual(80);
        expect(point.y).toBeLessThanOrEqual(500);
        expect(reach(point, edgeAnchor)).toBeLessThan(145 * 0.72);
    });

    it('returns null rather than using a safe but unreachable distant fallback', () => {
        expect(planContactBodyPoint(anchor, { x: 900, y: 300 }, area, 1, x => x < 700)).toBeNull();
        expect(planContactBodyPoint(anchor, { x: 200, y: 130 }, area, 1, () => true)).toBeNull();
    });

    it('is deterministic and does not mutate its rectangle or preferred point inputs', () => {
        const rect = Object.freeze({ ...anchor });
        const preferred = Object.freeze({ x: 250, y: 165 });
        const bounds = Object.freeze({ ...area });
        const blocked = (_x: number, y: number) => y > 130;
        expect(planContactBodyPoint(rect, preferred, bounds, 1.12, blocked))
            .toEqual(planContactBodyPoint(rect, preferred, bounds, 1.12, blocked));
        expect(preferred).toEqual({ x: 250, y: 165 });
    });

    it('rejects invalid dimensions, nonfinite inputs and physically impossible scales', () => {
        expect(planContactBodyPoint(anchor, { x: NaN, y: 100 }, area, 1, clear)).toBeNull();
        expect(planContactBodyPoint(anchor, { x: 200, y: 100 }, area, 0.3, clear)).toBeNull();
        expect(planContactBodyPoint(anchor, { x: 200, y: 100 }, { ...area, right: 0 }, 1, clear)).toBeNull();
        expect(planContactBodyPoint({ ...anchor, right: 100 }, { x: 200, y: 100 }, area, 1, clear)).toBeNull();
    });
});
