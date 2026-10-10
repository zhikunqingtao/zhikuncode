interface Rect {
    left: number;
    right: number;
    top: number;
    bottom: number;
    width: number;
    height: number;
}
interface Particle {
    sx: number;
    sy: number;
    ex: number;
    ey: number;
    bend: number;
    delay: number;
    duration: number;
    size: number;
    rgb: number[];
    scatter: boolean;
}
interface Trace {
    x1: number;
    x2: number;
    y: number;
    y2?: number;
    rgb: number[];
}
interface Batch {
    element: HTMLElement;
    range: Range | null;
    rects: Rect[];
    particles: Particle[];
    lines: Trace[];
    age: number;
    life: number;
    kind: string;
}
// Local accents only. The renderer owns the long 3D flight; DOM text stays untouched.
export function createTextDust(options: {
    getOrigin?: () => {
        x: number;
        y: number;
    };
    host: HTMLElement;
}) {
    const canvas = document.createElement('canvas');
    canvas.className = 'galaxy-text-dust';
    canvas.setAttribute('aria-hidden', 'true');
    options.host.append(canvas);
    const context = canvas.getContext('2d');
    if (!context) {
        canvas.remove();
        return { start() { }, emitRange() { }, pulseDiff() { }, update() { }, clear() { }, dispose() { } };
    }
    const ctx = context;
    const MAX_BATCHES = 18, MAX_PARTICLES = 320;
    const cyan = [104, 188, 255], violet = [172, 105, 255], rose = [255, 77, 157];
    let width = 1, height = 1, target: HTMLElement | null = null, batches: Batch[] = [], disposed = false, serial = 0;
    let pointerX = -10000, pointerY = -10000;
    const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));
    const rectCopy = (r: Rect) => ({ left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height });
    const noise = (n: number) => { const v = Math.sin(n * 127.1 + 311.7) * 43758.5453; return v - Math.floor(v); };
    const color = (rgb: number[], alpha: number) => `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${alpha})`;
    function resize() {
        width = Math.max(1, innerWidth);
        height = Math.max(1, innerHeight);
        const dpr = Math.min(devicePixelRatio || 1, 2);
        canvas.width = Math.round(width * dpr);
        canvas.height = Math.round(height * dpr);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    function clear() {
        batches = [];
        ctx.clearRect(0, 0, width, height);
        canvas.dataset.batches = '0';
        canvas.dataset.particles = '0';
    }
    function start(_text: string, element: HTMLElement) {
        if (disposed)
            return;
        clear();
        target = element;
    }
    function origin() {
        const supplied = options.getOrigin?.();
        const data = document.getElementById('galaxy-canvas')?.dataset;
        const x = Number(supplied?.x ?? data?.coreX), y = Number(supplied?.y ?? data?.coreY);
        return { x: Number.isFinite(x) ? x : width * .74, y: Number.isFinite(y) ? y : height * .4 };
    }
    function selected(element: HTMLElement) {
        const selection = window.getSelection();
        if (!selection || selection.isCollapsed)
            return false;
        for (let i = 0; i < selection.rangeCount; i++) {
            try {
                if (selection.getRangeAt(i).intersectsNode(element))
                    return true;
            }
            catch { /* A removed selection node is no longer a drawing target. */ }
        }
        return false;
    }
    function reading(rects: Rect[]) {
        return rects.some(r => pointerX > r.left - 36 && pointerX < r.right + 36 && pointerY > r.top - 30 && pointerY < r.bottom + 30);
    }
    function visible(element: HTMLElement, rect: Rect) {
        if (!element?.isConnected || rect.width < 1 || rect.height < 1 || rect.left < 0 || rect.right > width || rect.top < 0 || rect.bottom > height)
            return false;
        const style = getComputedStyle(element);
        if (style.visibility === 'hidden' || style.display === 'none')
            return false;
        const hit = document.elementFromPoint((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2);
        return Boolean(hit && (hit === element || element.contains(hit)));
    }
    function safe(element: HTMLElement, rects: Rect[]) {
        return !selected(element) && !reading(rects) && rects.every(r => visible(element, r));
    }
    // Offsets follow Text.length / appendData: UTF-16 offsets into the element's text.
    function textRange(element: HTMLElement, startOffset: number, endOffset: number) {
        if (!Number.isFinite(startOffset) || !Number.isFinite(endOffset))
            return null;
        const start = Math.max(0, Math.floor(startOffset)), end = Math.max(start, Math.floor(endOffset));
        if (start === end)
            return null;
        const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
        let node: Node | null, offset = 0, first: {
            node: Text;
            offset: number;
        } | null = null, last: {
            node: Text;
            offset: number;
        } | null = null;
        while ((node = walker.nextNode())) {
            const limit = offset + (node as Text).length;
            if (!first && start < limit)
                first = { node: node as Text, offset: start - offset };
            if (first && end <= limit) {
                last = { node: node as Text, offset: end - offset };
                break;
            }
            offset = limit;
        }
        if (!first || !last)
            return null; // A queued segment may belong to a replaced response.
        const range = document.createRange();
        range.setStart(first.node, first.offset);
        range.setEnd(last.node, last.offset);
        return range;
    }
    function rangeRects(range: Range) {
        const rects: Rect[] = [];
        for (const source of range.getClientRects()) {
            if (source.width < .5 || source.height < 1)
                continue;
            const r = rectCopy(source), previous = rects.at(-1);
            if (previous && Math.abs(previous.top - r.top) < 1 && Math.abs(previous.bottom - r.bottom) < 1 && r.left <= previous.right + 1) {
                previous.right = Math.max(previous.right, r.right);
                previous.width = previous.right - previous.left;
            }
            else
                rects.push(r);
        }
        return rects;
    }
    function addBatch(batch: Batch) {
        if (!batch.particles.length)
            return;
        batches.push(batch);
        canvas.dataset.emitted = String(Number(canvas.dataset.emitted ?? 0) + 1);
        let count = batches.reduce((sum, item) => sum + item.particles.length, 0);
        while (batches.length > MAX_BATCHES || count > MAX_PARTICLES)
            count -= batches.shift()!.particles.length;
    }
    function emitRange(element: HTMLElement, startOffset: number, endOffset: number) {
        if (disposed || element !== target || document.hidden)
            return;
        const range = textRange(element, startOffset, endOffset);
        if (!range)
            return;
        if ((range.startContainer.parentElement?.closest('pre,code,button,a,input,textarea') || range.endContainer.parentElement?.closest('pre,code,button,a,input,textarea')))
            return;
        const rects = rangeRects(range);
        if (!rects.length || rects.length > 6 || !safe(element, rects))
            return;
        const source = origin(), particles = [], lines = [];
        for (const r of rects) {
            // Land below the actual glyph box, never into the original character strokes.
            const y = r.bottom + 2.5, count = clamp(Math.ceil(r.width / 12), 5, 13);
            lines.push({ x1: r.left + 1, x2: r.right - 1, y, rgb: cyan });
            for (let i = 0; i < count; i++) {
                const seed = noise(++serial), x = r.left + 2 + (r.width - 4) * (i + .5) / count;
                const dx = source.x - x, dy = source.y - y, distance = Math.hypot(dx, dy) || 1;
                const approach = Math.min(distance, 54 + seed * 54);
                particles.push({ sx: x + dx / distance * approach, sy: y + dy / distance * approach - 15 - seed * 16,
                    ex: x, ey: y, bend: 8 + seed * 15, delay: i * .023 + seed * .05, duration: .66 + seed * .3,
                    size: .6 + seed * .65, rgb: seed > .77 ? violet : cyan, scatter: false });
            }
        }
        addBatch({ element, range, rects, particles, lines, age: 0, life: 1.55, kind: 'reply' });
    }
    function diffKind(element: HTMLElement) {
        const value = (element.dataset.galaxyDiff || '').toLowerCase();
        if (['remove', 'removed', 'delete', 'deleted', 'del', '-'].includes(value) || element.matches('.diff-remove,.diff-removed,.diff-delete,.diff-deleted,.is-removed'))
            return 'remove';
        if (['add', 'added', 'insert', 'inserted', '+'].includes(value) || element.matches('.diff-add,.diff-added,.diff-insert,.is-added'))
            return 'add';
        return null;
    }
    function pulseDiff(elements: Iterable<HTMLElement>) {
        if (disposed || document.hidden)
            return;
        let accepted = 0;
        for (const element of elements || []) {
            const kind = diffKind(element);
            if (!kind || accepted >= 10)
                continue;
            const r = rectCopy(element.getBoundingClientRect());
            if (!safe(element, [r]))
                continue;
            const gutter = element.querySelector('[data-diff-gutter],.diff-gutter,.line-number,[data-line-number]');
            const g = gutter ? gutter.getBoundingClientRect() : r;
            // Keep the effect outside the line-number glyphs and away from source code.
            const x = g.left - 4, y = r.top + r.height * .5, particles = [];
            const rgb = kind === 'remove' ? rose : cyan;
            for (let i = 0; i < 14; i++) {
                const seed = noise(++serial), side = noise(serial + 19), reach = 17 + seed * 36;
                const sx = x - reach, sy = y + (side - .5) * 36;
                const endY = y + (seed - .5) * Math.min(14, r.height * .6);
                particles.push({ sx: kind === 'remove' ? x : sx, sy: kind === 'remove' ? endY : sy,
                    ex: kind === 'remove' ? sx : x, ey: kind === 'remove' ? sy : endY,
                    bend: 6 + seed * 7, delay: accepted * .05 + seed * .15, duration: .62 + side * .34,
                    size: .6 + seed * .7, rgb: kind === 'add' && seed > .7 ? violet : rgb, scatter: kind === 'remove' });
            }
            addBatch({ element, range: null, rects: [r], particles,
                lines: [{ x1: x, x2: x, y: r.top + 3, y2: r.bottom - 3, rgb }], age: 0, life: 1.9, kind: 'diff' });
            accepted++;
        }
    }
    function unchanged(batch: Batch) {
        if (!batch.element.isConnected || !safe(batch.element, batch.rects))
            return false;
        if (batch.range && (!batch.element.contains(batch.range.startContainer) || !batch.element.contains(batch.range.endContainer)))
            return false;
        const rects = batch.range ? rangeRects(batch.range) : [rectCopy(batch.element.getBoundingClientRect())];
        return rects.length === batch.rects.length && rects.every((r, i) => {
            const before = batch.rects[i];
            return Math.abs(r.left - before.left) < 1.5 && Math.abs(r.top - before.top) < 1.5 && Math.abs(r.right - before.right) < 1.5 && Math.abs(r.bottom - before.bottom) < 1.5;
        });
    }
    function path(p: Particle, q: number) {
        const e = 1 - (1 - q) ** 3;
        return { x: p.sx + (p.ex - p.sx) * e, y: p.sy + (p.ey - p.sy) * e - Math.sin(q * Math.PI) * p.bend };
    }
    function drawBatch(batch: Batch) {
        // Even the glow halo stays outside the measured glyph / diff row boxes.
        ctx.save();
        ctx.beginPath();
        ctx.rect(0, 0, width, height);
        for (const r of batch.rects)
            ctx.rect(r.left - .5, r.top - .5, r.width + 1, r.height + 1);
        ctx.clip('evenodd');
        for (const p of batch.particles) {
            const q = (batch.age - p.delay) / p.duration;
            if (q <= 0 || q >= 1.35)
                continue;
            const progress = Math.min(1, q), position = path(p, progress);
            const fade = Math.min(1, q * 7) * Math.max(0, 1 - Math.max(0, q - .65) / .7);
            const alpha = fade * (p.scatter ? .64 : .78);
            // A narrow tail and changing apparent size imply depth without a second sky.
            if (q < 1) {
                const tail = path(p, Math.max(0, progress - .14));
                const gradient = ctx.createLinearGradient(tail.x, tail.y, position.x + .01, position.y + .01);
                gradient.addColorStop(0, color(p.rgb, 0));
                gradient.addColorStop(1, color(p.rgb, alpha * .58));
                ctx.strokeStyle = gradient;
                ctx.lineWidth = .75;
                ctx.beginPath();
                ctx.moveTo(tail.x, tail.y);
                ctx.lineTo(position.x, position.y);
                ctx.stroke();
            }
            const size = p.size * (.55 + Math.sin(progress * Math.PI) * .5 + progress * .45);
            ctx.fillStyle = color(p.rgb, alpha);
            ctx.beginPath();
            ctx.arc(position.x, position.y, size, 0, Math.PI * 2);
            ctx.fill();
            ctx.fillStyle = color(p.rgb, alpha * .075);
            ctx.beginPath();
            ctx.arc(position.x, position.y, size * 3.5, 0, Math.PI * 2);
            ctx.fill();
        }
        const trace = Math.max(0, 1 - Math.abs(batch.age - .72) / .65);
        if (!trace) {
            ctx.restore();
            return;
        }
        for (const line of batch.lines) {
            ctx.strokeStyle = color(line.rgb, trace * .42);
            ctx.lineWidth = .65;
            const progress = clamp((batch.age - .15) / .65, 0, 1);
            const x2 = line.x1 + (line.x2 - line.x1) * progress;
            const y2 = line.y + ((line.y2 ?? line.y) - line.y) * progress;
            ctx.beginPath();
            ctx.moveTo(line.x1, line.y);
            ctx.lineTo(x2, y2);
            ctx.stroke();
            ctx.fillStyle = color([231, 244, 255], trace * .8);
            ctx.beginPath();
            ctx.arc(x2, y2, 1.15, 0, Math.PI * 2);
            ctx.fill();
        }
        ctx.restore();
    }
    function update(dt: number) {
        // The last active frame already cleared expired particles; an empty layer needs no repaint.
        if (disposed || batches.length === 0)
            return;
        ctx.clearRect(0, 0, width, height);
        if (document.hidden)
            return;
        const delta = Number.isFinite(dt) ? clamp(dt, 0, .1) : 0;
        batches = batches.filter(batch => {
            batch.age += delta;
            return batch.age < batch.life && unchanged(batch);
        });
        ctx.globalCompositeOperation = 'lighter';
        for (const batch of batches)
            drawBatch(batch);
        ctx.globalCompositeOperation = 'source-over';
        canvas.dataset.batches = String(batches.length);
        canvas.dataset.particles = String(batches.reduce((sum, batch) => sum + batch.particles.length, 0));
    }
    function protectReading() {
        const count = batches.length;
        batches = batches.filter(batch => !selected(batch.element) && !reading(batch.rects));
        if (batches.length !== count)
            ctx.clearRect(0, 0, width, height);
    }
    function onPointer(event: PointerEvent) { pointerX = event.clientX; pointerY = event.clientY; protectReading(); }
    function onBlur() { pointerX = pointerY = -10000; }
    function onResize() { clear(); resize(); }
    function onScroll() {
        // A follow-to-bottom scroll can dispatch after a batch was measured at its
        // new position. Keep that batch; discard anything whose real DOM moved.
        batches = batches.filter(unchanged);
        // Clear the old frame immediately, including while animation is paused.
        ctx.clearRect(0, 0, width, height);
        canvas.dataset.batches = String(batches.length);
        canvas.dataset.particles = String(batches.reduce((sum, batch) => sum + batch.particles.length, 0));
    }
    function dispose() {
        if (disposed)
            return;
        disposed = true;
        clear();
        target = null;
        window.removeEventListener('resize', onResize);
        window.removeEventListener('pointermove', onPointer);
        window.removeEventListener('blur', onBlur);
        document.removeEventListener('scroll', onScroll, true);
        document.removeEventListener('selectionchange', protectReading);
        canvas.remove();
    }
    resize();
    window.addEventListener('resize', onResize, { passive: true });
    window.addEventListener('pointermove', onPointer, { passive: true });
    window.addEventListener('blur', onBlur);
    document.addEventListener('scroll', onScroll, { passive: true, capture: true });
    document.addEventListener('selectionchange', protectReading);
    return { start, emitRange, pulseDiff, update, clear, dispose };
}
