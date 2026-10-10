import { PROTECTED, bounds, visibleRect } from './anchors';
import type { GalaxyAnchor, GalaxyPhase } from './scene';

/** Foreground docking light: business DOM stays untouched and retains all input events. */
export function createTaskGlow(host: HTMLElement, origin: () => { x: number; y: number }) {
    const canvas = document.createElement('canvas');
    canvas.className = 'galaxy-task-glow';
    host.append(canvas);
    const ctx = canvas.getContext('2d');
    let width = 0, height = 0, age = 0, targetId = '', kind = '', phase: GalaxyPhase = 'idle';
    let anchors: GalaxyAnchor[] = [];
    let protectedRects: ReturnType<typeof bounds>[] = [];
    let glyphRects: DOMRect[] = [];
    let disposed = false, maskDirty = true, painted = false, pixelRatio = 0;
    const clear = () => {
        targetId = '';
        if (canvas.dataset.active !== 'false') canvas.dataset.active = 'false';
        if (painted) ctx?.clearRect(0, 0, width, height);
        painted = false;
    };
    function geometry(next: GalaxyAnchor[]) {
        anchors = next;
        maskDirty = true;
    }
    function measureMasks() {
        maskDirty = false;
        protectedRects = [...document.querySelectorAll(PROTECTED + ',.chat-composer-surface,.app-header,.app-sidebar')]
            .map(el => visibleRect(el)).filter((r): r is DOMRect => r !== null).map(bounds);
        // Light can cross the card material, but never paints over its glyphs.
        glyphRects = [];
        for (const el of document.querySelectorAll('[data-galaxy-text],[data-spider-anchor="tool"]')) {
            const box = el.getBoundingClientRect();
            if (box.bottom <= 0 || box.top >= innerHeight || box.right <= 0 || box.left >= innerWidth || box.width < 1 || box.height < 1) continue;
            const range = document.createRange();
            range.selectNodeContents(el);
            for (const r of range.getClientRects())
                if (r.bottom > 0 && r.top < innerHeight && r.width > 0) glyphRects.push(r);
        }
    }
    function launch(job: { targetId: string; kind: string }) {
        targetId = job.targetId;
        kind = job.kind;
        age = 0;
        maskDirty = true;
        canvas.dataset.active = 'true';
        canvas.dataset.target = targetId;
    }
    function update(dt: number, nextPhase: GalaxyPhase) {
        if (disposed || !ctx) return;
        phase = nextPhase;
        if (document.hidden || !targetId) { clear(); return; }
        if (['idle', 'cancel', 'error', 'approval'].includes(phase)) { clear(); return; }
        const target = anchors.find(a => a.id === targetId);
        if (!target) { clear(); return; }
        const dpr = Math.min(devicePixelRatio || 1, 2);
        if (width !== innerWidth || height !== innerHeight || pixelRatio !== dpr) {
            width = innerWidth; height = innerHeight;
            pixelRatio = dpr;
            maskDirty = true;
            canvas.width = Math.round(width * dpr); canvas.height = Math.round(height * dpr);
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        }
        ctx.clearRect(0, 0, width, height);
        painted = false;
        if (maskDirty) measureMasks();
        age += Math.min(Math.max(dt, 0), .1);
        if (age > 2.3) { clear(); return; }
        const start = origin(), end = { x: target.x * width, y: target.y * height + 4 };
        if (!Number.isFinite(start.x + start.y)) return;
        const rgb = kind === 'edit' ? '255,77,157' : kind === 'compose' ? '183,125,255' : '104,188,255';
        const point = (t: number) => ({
            x: start.x + (end.x - start.x) * t + Math.sin(t * Math.PI) * Math.min(140, Math.abs(end.y - start.y) * .3),
            y: start.y + (end.y - start.y) * t - Math.sin(t * Math.PI) * 65,
        });
        painted = true;
        ctx.save();
        ctx.globalCompositeOperation = 'lighter';
        const t = Math.min(1, age / 1.25);
        const fade = Math.min(1, (2.3 - age) / .65);
        for (let i = 0; i < 48; i++) {
            const p = t - i * .006;
            if (p < 0) break;
            const a = point(p), alpha = (1 - i / 48) * fade;
            ctx.fillStyle = `rgba(${rgb},${alpha * .85})`;
            ctx.shadowColor = `rgb(${rgb})`; ctx.shadowBlur = i < 8 ? 15 : 5;
            ctx.beginPath(); ctx.arc(a.x, a.y, i === 0 ? 3 : 1.6, 0, Math.PI * 2); ctx.fill();
        }
        if (age > 1.25) {
            const p = (age - 1.25) / 1.05;
            ctx.shadowBlur = 12;
            ctx.strokeStyle = `rgba(${rgb},${(1 - p) * .9})`; ctx.lineWidth = 1.5;
            ctx.beginPath(); ctx.ellipse(end.x, end.y, 8 + p * 42, 3 + p * 9, 0, 0, Math.PI * 2); ctx.stroke();
        }
        ctx.restore();
        // Cut out controls, portals, selected text, and the actual rendered glyph boxes.
        for (const r of protectedRects) ctx.clearRect(r.x * width - 3, r.y * height - 3, r.w * width + 6, r.h * height + 6);
        for (const r of glyphRects) ctx.clearRect(r.left, r.top, r.width, r.height);
        canvas.dataset.progress = t.toFixed(3);
    }
    function dispose() { if (disposed) return; disposed = true; clear(); canvas.remove(); }
    return { geometry, launch, update, clear, dispose };
}
