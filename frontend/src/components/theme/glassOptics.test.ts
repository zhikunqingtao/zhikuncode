import { describe, expect, it } from 'vitest';
import { lensVector } from './glassOptics';

describe('rounded glass lens', () => {
    it('leaves the center and outside untouched', () => {
        expect(lensVector(200, 70, 400, 140, 28)).toEqual([0, 0]);
        expect(lensVector(-5, 70, 400, 140, 28)).toEqual([0, 0]);
    });
    it('bends opposite edges symmetrically without shifting the tangential axis', () => {
        const left = lensVector(8, 70, 400, 140, 28);
        const right = lensVector(392, 70, 400, 140, 28);
        expect(left[0]).toBeLessThan(0);
        expect(right[0]).toBeCloseTo(-left[0]);
        expect(left[1]).toBe(0);
        expect(right[1]).toBe(0);
    });
    it('follows the curved corner normal with finite bounded displacement', () => {
        const vector = lensVector(14, 14, 400, 140, 28);
        expect(vector[0]).toBeLessThan(0);
        expect(vector[0]).toBeCloseTo(vector[1]);
        expect(Math.hypot(...vector)).toBeLessThanOrEqual(.9);
    });
});
