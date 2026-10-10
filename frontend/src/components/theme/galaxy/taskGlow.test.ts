import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTaskGlow } from './taskGlow';
import { createTextDust } from './textDust';

const context = Object.fromEntries(['clearRect', 'setTransform', 'save', 'restore', 'beginPath', 'arc', 'fill', 'ellipse', 'stroke'].map(name => [name, vi.fn()]));
let host: HTMLDivElement;
let glow: ReturnType<typeof createTaskGlow>;
beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D);
    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
    host = document.createElement('div'); document.body.append(host);
    glow = createTaskGlow(host, () => ({ x: 100, y: 100 }));
});
afterEach(() => { glow.dispose(); host.remove(); vi.restoreAllMocks(); });
const anchors = [{ id: 'reply', kind: 'result' as const, x: .5, y: .5 }];
describe('task light avoids idle work without losing lifecycle safety', () => {
    it('does not repaint an empty reply dust layer', () => {
        const dust = createTextDust({ host });
        context.clearRect.mockClear();
        for (let i = 0; i < 120; i++) dust.update(1 / 60);
        expect(context.clearRect).not.toHaveBeenCalled();
        dust.dispose();
        expect(host.querySelector('.galaxy-text-dust')).toBeNull();
    });
    it('does not measure DOM, resize or clear the canvas when idle', () => {
        const query = vi.spyOn(document, 'querySelectorAll');
        for (let i = 0; i < 120; i++) { glow.geometry(anchors); glow.update(1 / 60, 'idle'); }
        expect(query).not.toHaveBeenCalled();
        expect(context.clearRect).not.toHaveBeenCalled();
        expect(context.setTransform).not.toHaveBeenCalled();
    });
    it('reuses masks until geometry changes and clears once when the target vanishes', () => {
        const query = vi.spyOn(document, 'querySelectorAll');
        glow.geometry(anchors); glow.launch({ targetId: 'reply', kind: 'compose' }); glow.update(.1, 'compose');
        const measured = query.mock.calls.length;
        expect(measured).toBeGreaterThan(0);
        glow.update(.1, 'compose');
        expect(query).toHaveBeenCalledTimes(measured);
        glow.geometry(anchors); glow.update(.1, 'compose');
        expect(query).toHaveBeenCalledTimes(measured * 2);
        glow.geometry([]); glow.update(.1, 'compose');
        const cleared = context.clearRect.mock.calls.length;
        glow.update(.1, 'compose');
        expect(context.clearRect).toHaveBeenCalledTimes(cleared);
        expect(host.firstElementChild).toHaveAttribute('data-active', 'false');
    });
    it('clears the last frame when hidden and releases its canvas', () => {
        glow.geometry(anchors); glow.launch({ targetId: 'reply', kind: 'compose' }); glow.update(.1, 'compose');
        context.clearRect.mockClear();
        Object.defineProperty(document, 'hidden', { configurable: true, value: true });
        glow.update(.1, 'compose'); glow.update(.1, 'compose');
        expect(context.clearRect).toHaveBeenCalledTimes(1);
        glow.dispose(); glow.dispose();
        expect(host.children).toHaveLength(0);
    });
});
