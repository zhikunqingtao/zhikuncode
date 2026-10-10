import { afterEach, describe, expect, it, vi } from 'vitest';
import { SpiderExclusionMask } from './exclusionMask';

afterEach(() => vi.restoreAllMocks());

function setup() {
    const context = { clearRect: vi.fn(), fillRect: vi.fn(), fillStyle: '', shadowColor: '', shadowBlur: 0 };
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D);
    const mask = new SpiderExclusionMask();
    return { mask, context };
}

describe('viewport exclusion mask', () => {
    it('includes late composer and selection rectangles beyond uniform-array limits', () => {
        const { mask, context } = setup();
        const controls = Array.from({ length: 80 }, (_, i) => new DOMRect(i * 10, 10, 8, 20));
        const composer = new DOMRect(30, 600, 800, 100);
        const selection = new DOMRect(100, 450, 260, 24);
        mask.update(1280, 800, [...controls, composer, selection]);
        expect(context.fillRect).toHaveBeenCalledTimes(82);
        expect(context.fillRect).toHaveBeenNthCalledWith(81, 22, 592, 816, 116);
        expect(context.fillRect).toHaveBeenLastCalledWith(92, 442, 276, 40);
        mask.dispose();
    });

    it('clears stale masks on collapse, scroll and viewport changes without reuploading unchanged geometry', () => {
        const { mask, context } = setup();
        const rect = new DOMRect(10, 20, 80, 30);
        mask.update(1280, 800, [rect]);
        const version = mask.texture.version;
        mask.update(1280, 800, [new DOMRect(10, 20, 80, 30)]);
        expect(mask.texture.version).toBe(version);
        mask.update(1280, 800, []);
        expect(context.clearRect).toHaveBeenCalledTimes(2);
        expect(mask.texture.version).toBeGreaterThan(version);
        mask.update(390, 844, [new DOMRect(10, 40, 80, 30)]);
        expect(mask.texture.image.width).toBe(390);
        expect(mask.texture.image.height).toBe(844);
        expect(context.clearRect).toHaveBeenLastCalledWith(0, 0, 390, 844);
        expect(context.fillRect).toHaveBeenLastCalledWith(2, 32, 96, 46);
        const disposed = vi.fn();
        mask.texture.addEventListener('dispose', disposed);
        mask.dispose();
        expect(disposed).toHaveBeenCalledOnce();
        expect(mask.texture.image.width).toBe(1);
    });

    it('fails into the scene construction boundary when a 2D mask cannot be allocated', () => {
        vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
        expect(() => new SpiderExclusionMask()).toThrow('Spider exclusion mask unavailable');
    });

    it('tracks in-place geometry changes even when the rectangle array is reused', () => {
        const { mask, context } = setup();
        const rects = [new DOMRect(10, 20, 80, 30), new DOMRect(120, 20, 60, 30)];
        mask.update(1280, 800, rects);
        const firstVersion = mask.texture.version;
        rects[0].x = 30;
        rects[1].height = 50;
        mask.update(1280, 800, rects);
        expect(mask.texture.version).toBe(firstVersion + 1);
        expect(context.fillRect).toHaveBeenNthCalledWith(3, 22, 12, 96, 46);
        expect(context.fillRect).toHaveBeenNthCalledWith(4, 112, 12, 76, 66);
        mask.update(1280, 800, rects);
        expect(mask.texture.version).toBe(firstVersion + 1);
        rects.pop();
        mask.update(1280, 800, rects);
        expect(mask.texture.version).toBe(firstVersion + 2);
        mask.dispose();
    });
});
