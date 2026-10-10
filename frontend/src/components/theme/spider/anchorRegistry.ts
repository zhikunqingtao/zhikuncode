import type { SpiderAnchor, SpiderTextRun } from './types';

const ANCHOR_SELECTOR = '[data-spider-anchor="tool"], [data-spider-anchor="text"], [data-spider-anchor="brand"]';
const EXCLUDED_SELECTOR = '.chat-composer-dock, .chat-composer-surface, [data-testid="mobile-prompt-bar"], input:not([type="hidden"]), textarea, [contenteditable]:not([contenteditable="false"]), [role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"], dialog[open], .monaco-editor, .xterm, .code-block, .image-block, button[aria-label="Close zoom"], [data-image-preview], [data-spider-exclude]';
const CONTROL_SELECTOR = 'button, [role="button"], a[href], summary, select';
const READING_DISCLOSURE_SELECTOR = '.thinking-block button, button[aria-label^="详细过程区"]';
const SKIP_TEXT_SELECTOR = 'script, style, svg, pre, .code-block, .markdown-embed, .monaco-editor, .xterm, [aria-hidden="true"], [data-spider-exclude]';
// Capture a compact real text range before the renderer applies its dramatic zoom.
// Keep the original font/glyph geometry instead of squeezing a long bitmap strip.
const MAX_WIDTH = 136;
const MAX_CHARACTERS = 60;
const MAX_ANCHORS = 80;
const MAX_ROOT_CHARACTERS = 2400;
const VISIBLE_CHUNK_CHARACTERS = 128;

interface Piece { node: Text; start: number; end: number; original: string; }
interface Glyph extends Piece { rect: DOMRect; }
type MeasuredStyle = Pick<CSSStyleDeclaration, 'display' | 'visibility' | 'opacity'
    | 'overflowX' | 'overflowY' | 'overflow' | 'textOverflow' | 'direction'
    | 'fontStyle' | 'fontVariant' | 'fontWeight' | 'fontSize' | 'fontFamily'
    | 'color' | 'letterSpacing' | 'wordSpacing'>;

function rectOf(range: Range): DOMRect | null {
    const rect = range.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 ? rect : null;
}

function viewport(): DOMRect {
    const view = window.visualViewport;
    return new DOMRect(view?.offsetLeft ?? 0, view?.offsetTop ?? 0,
        view?.width ?? window.innerWidth, view?.height ?? window.innerHeight);
}

function intersects(a: DOMRect, b: DOMRect): boolean {
    return a.right > b.left && a.left < b.right && a.bottom > b.top && a.top < b.bottom;
}

function contained(a: DOMRect, b: DOMRect): boolean {
    return a.left >= b.left - 0.5 && a.right <= b.right + 0.5
        && a.top >= b.top - 0.5 && a.bottom <= b.bottom + 0.5;
}

function broadRoot(element: Element): boolean {
    return element === document.body || element === document.documentElement;
}

function controlEngaged(element: HTMLElement): boolean {
    return element.matches(':hover, :active')
        || Boolean(document.activeElement && element.contains(document.activeElement));
}

function blockedAnchor(element: HTMLElement): boolean {
    const excluded = element.closest(EXCLUDED_SELECTOR);
    if (excluded && !broadRoot(excluded)) return true;
    const control = element.closest<HTMLElement>(CONTROL_SELECTOR);
    // Tool names intentionally live inside disclosure buttons. Keep them available at rest,
    // but immediately yield the whole control when the user interacts with it.
    return Boolean(control && !broadRoot(control)
        && (element.dataset.spiderAnchor !== 'tool' || controlEngaged(control)));
}

function selectionRects(view: DOMRect): DOMRect[] {
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed) return [];
    const rects: DOMRect[] = [];
    for (let index = 0; index < selection.rangeCount; index++) {
        for (const rect of selection.getRangeAt(index).getClientRects()) {
            if (rect.width > 0 && rect.height > 0 && intersects(rect, view)) rects.push(rect);
        }
    }
    return rects;
}

/** Clip to each scroll/overflow ancestor, including horizontal truncation chips. */
function visibleBounds(element: HTMLElement, frame: MeasurementFrame): DOMRect | null {
    let bounds = frame.view;
    for (let current: HTMLElement | null = element; current; current = current.parentElement) {
        if (current.hidden || current.getAttribute('aria-hidden') === 'true' || current.hasAttribute('inert')) return null;
        if (current.tagName === 'DETAILS' && !current.hasAttribute('open')) {
            const summary = Array.from(current.children).find(child => child.tagName === 'SUMMARY');
            if (!summary?.contains(element)) return null;
        }
        const style = frame.style(current);
        if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse'
            || Number(style.opacity || 1) === 0) return null;
        const clipX = /(auto|scroll|hidden|clip|overlay)/.test(style.overflowX || style.overflow);
        const clipY = /(auto|scroll|hidden|clip|overlay)/.test(style.overflowY || style.overflow);
        if (clipX || clipY) {
            const rect = frame.rect(current);
            const ellipsis = style.textOverflow === 'ellipsis' && current.scrollWidth > current.clientWidth
                ? parseFloat(style.fontSize) || 16 : 0;
            const left = clipX ? Math.max(bounds.left, rect.left + (style.direction === 'rtl' ? ellipsis : 0)) : bounds.left;
            const right = clipX ? Math.min(bounds.right, rect.right - (style.direction === 'rtl' ? 0 : ellipsis)) : bounds.right;
            const top = clipY ? Math.max(bounds.top, rect.top) : bounds.top;
            const bottom = clipY ? Math.min(bounds.bottom, rect.bottom) : bounds.bottom;
            if (right <= left || bottom <= top) return null;
            bounds = new DOMRect(left, top, right - left, bottom - top);
        }
    }
    return bounds;
}

/** Measurements live for one synchronous renderer frame (or one standalone read). */
class MeasurementFrame {
    readonly view = viewport();
    readonly measured = new WeakMap<SpiderAnchor, SpiderAnchor | null>();
    exclusions: DOMRect[] | null = null;
    private styles = new WeakMap<HTMLElement, MeasuredStyle>();
    private rects = new WeakMap<HTMLElement, DOMRect>();
    private bounds = new WeakMap<HTMLElement, DOMRect | null>();
    private contents = new WeakMap<HTMLElement, string>();
    private selected: DOMRect[] | null = null;

    style(element: HTMLElement): MeasuredStyle {
        let style = this.styles.get(element);
        if (!style) {
            // CSSStyleDeclaration is live: merely caching it still repeats native
            // property reads for every glyph/ancestor. Snapshot the values we use.
            const computed = getComputedStyle(element);
            style = { display: computed.display, visibility: computed.visibility, opacity: computed.opacity,
                overflowX: computed.overflowX, overflowY: computed.overflowY, overflow: computed.overflow,
                textOverflow: computed.textOverflow, direction: computed.direction,
                fontStyle: computed.fontStyle, fontVariant: computed.fontVariant, fontWeight: computed.fontWeight,
                fontSize: computed.fontSize, fontFamily: computed.fontFamily, color: computed.color,
                letterSpacing: computed.letterSpacing, wordSpacing: computed.wordSpacing };
            this.styles.set(element, style);
        }
        return style;
    }

    rect(element: HTMLElement): DOMRect {
        let rect = this.rects.get(element);
        if (!rect) { rect = element.getBoundingClientRect(); this.rects.set(element, rect); }
        return rect;
    }

    visibleBounds(element: HTMLElement): DOMRect | null {
        if (!this.bounds.has(element)) this.bounds.set(element, visibleBounds(element, this));
        return this.bounds.get(element) ?? null;
    }

    contentVersion(element: HTMLElement): string {
        // Keep the full version: different text must never pass a hash collision.
        if (!this.contents.has(element)) this.contents.set(element, element.textContent ?? '');
        return this.contents.get(element)!;
    }

    selectionRects(): DOMRect[] {
        return this.selected ??= selectionRects(this.view);
    }
}

function fontOf(style: MeasuredStyle): string {
    return `${style.fontStyle || 'normal'} ${style.fontVariant || 'normal'} ${style.fontWeight || '400'} ${style.fontSize || '16px'} ${style.fontFamily || 'sans-serif'}`;
}

function rangeFor(piece: Piece): Range {
    const range = document.createRange();
    range.setStart(piece.node, piece.start);
    range.setEnd(piece.node, piece.end);
    return range;
}

function* visibleTextOffsets(node: Text, bounds: DOMRect, segmenter: Intl.Segmenter): Generator<{ start: number; end: number }> {
    // Find the visible part before spending the glyph budget. Range bounds follow real
    // wrapping/RTL/layout; binary pruning skips long offscreen prefixes in one text node.
    const probe = document.createRange();
    function* visibleChunks(start: number, end: number): Generator<{ start: number; end: number }> {
        if (start >= end) return;
        probe.setStart(node, start);
        probe.setEnd(node, end);
        const rect = rectOf(probe);
        if (!rect || !intersects(rect, bounds)) return;
        if (end - start <= VISIBLE_CHUNK_CHARACTERS) {
            yield { start, end };
            return;
        }
        const middle = start + Math.floor((end - start) / 2);
        yield* visibleChunks(start, middle);
        yield* visibleChunks(middle, end);
    }
    // Segment lazily at candidate offsets, rather than allocating every grapheme in a
    // long reply. containing() also preserves clusters straddling a binary split.
    const segments = segmenter.segment(node.data);
    let previousEnd = -1;
    for (const chunk of visibleChunks(0, node.length)) {
        let part = segments.containing(chunk.start);
        while (part && part.index < chunk.end) {
            const end = part.index + part.segment.length;
            if (part.index >= previousEnd) yield { start: part.index, end };
            previousEnd = end;
            part = segments.containing(end);
        }
    }
}

/** Read-only DOM adapter. Construct only while the spider surface is mounted. */
export class SpiderAnchorRegistry {
    private sessionId: string | null = null;
    private disposed = false;
    private dirty = true;
    private nextElementId = 0;
    private nextTextId = 0;
    private elementIds = new WeakMap<HTMLElement, number>();
    private textIds = new WeakMap<Text, number>();
    private pieces = new WeakMap<Range, Piece[]>();
    private cached: SpiderAnchor[] = [];
    private roots: HTMLElement[] = [];
    private observedRoots = new Set<HTMLElement>();
    private mutationObserver: MutationObserver;
    private resizeObserver: ResizeObserver | null;
    private measurements: MeasurementFrame | null = null;
    private segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
    private selectionState: { anchor: Node | null; focus: Node | null; anchorOffset: number; focusOffset: number; count: number } | null = null;

    constructor() {
        this.mutationObserver = new MutationObserver(records => this.handleMutations(records));
        this.resizeObserver = typeof ResizeObserver === 'undefined' ? null
            : new ResizeObserver(this.invalidate);
        this.mutationObserver.observe(document.body, {
            subtree: true, childList: true, characterData: true, attributes: true,
            attributeFilter: ['class', 'style', 'hidden', 'open', 'aria-hidden', 'inert', 'role', 'aria-label', 'aria-expanded', 'href', 'type', 'contenteditable', 'data-spider-exclude', 'data-spider-anchor', 'data-message-uuid', 'data-tool-use-id'],
        });
        window.addEventListener('resize', this.invalidate);
        document.addEventListener('scroll', this.invalidate, { capture: true, passive: true });
        document.addEventListener('selectionchange', this.invalidate);
        document.addEventListener('focusin', this.invalidateControl);
        document.addEventListener('focusout', this.invalidateControl);
        document.addEventListener('pointerover', this.invalidateControl, { passive: true });
        document.addEventListener('pointerout', this.invalidateControl, { passive: true });
        window.visualViewport?.addEventListener('resize', this.invalidate);
        window.visualViewport?.addEventListener('scroll', this.invalidate);
        document.fonts?.addEventListener('loadingdone', this.invalidate);
    }

    private invalidate = (): void => {
        this.dirty = true;
        if (this.measurements) this.measurements = new MeasurementFrame();
    };
    private invalidateControl = (event: Event): void => {
        // Hover/focus may change typography through an ancestor selector, even outside a button.
        if (event.target instanceof Element) this.invalidate();
    };

    private handleMutations(records: MutationRecord[]): void {
        if (!records.length || this.disposed) return;
        // A sibling can move an anchor without mutating that anchor. Geometry is never
        // retained after a DOM mutation; only text-fragment discovery is selective.
        if (this.measurements) this.measurements = new MeasurementFrame();
        if (records.some(record => {
            const element = record.target instanceof Element ? record.target : record.target.parentElement;
            if (element?.closest(ANCHOR_SELECTOR)) return true;
            if (record.type === 'attributes') return Boolean(element?.matches(`${EXCLUDED_SELECTOR}, ${CONTROL_SELECTOR}`))
                || this.roots.some(root => element?.contains(root));
            return [...record.addedNodes, ...record.removedNodes].some(node =>
                node instanceof Element && (node.matches(`${ANCHOR_SELECTOR}, ${EXCLUDED_SELECTOR}, ${CONTROL_SELECTOR}`)
                    || node.querySelector(`${ANCHOR_SELECTOR}, ${EXCLUDED_SELECTOR}, ${CONTROL_SELECTOR}`)));
        })) this.dirty = true;
        // Release detached elements even when no subsequent sampling is requested.
        this.cached = this.cached.filter(anchor => anchor.element.isConnected);
        this.roots = this.roots.filter(root => {
            if (root.isConnected) return true;
            this.resizeObserver?.unobserve(root);
            this.observedRoots.delete(root);
            return false;
        });
    }

    /** Pair with endFrame() in the renderer's finally block; never carry geometry across RAFs. */
    beginFrame(): void {
        if (this.disposed) return;
        this.handleMutations(this.mutationObserver.takeRecords());
        this.measurements = new MeasurementFrame();
    }

    endFrame(): void { this.measurements = null; }

    private read<T>(operation: () => T): T {
        if (this.disposed) return operation();
        this.handleMutations(this.mutationObserver.takeRecords());
        // Programmatic selection changes need not dispatch selectionchange before a
        // synchronous measure. Multiple native ranges are rare; re-read them each time.
        const selection = window.getSelection();
        const previous = this.selectionState;
        const count = selection?.rangeCount ?? 0;
        if (!previous || previous.anchor !== (selection?.anchorNode ?? null)
            || previous.focus !== (selection?.focusNode ?? null)
            || previous.anchorOffset !== (selection?.anchorOffset ?? 0)
            || previous.focusOffset !== (selection?.focusOffset ?? 0)
            || previous.count !== count || count > 1) {
            this.selectionState = { anchor: selection?.anchorNode ?? null, focus: selection?.focusNode ?? null,
                anchorOffset: selection?.anchorOffset ?? 0, focusOffset: selection?.focusOffset ?? 0, count };
            this.invalidate();
        }
        const ownsMeasurements = !this.measurements;
        this.measurements ??= new MeasurementFrame();
        try { return operation(); }
        finally { if (ownsMeasurements) this.measurements = null; }
    }

    setSession(id: string | null): void {
        if (id === this.sessionId || this.disposed) return;
        this.sessionId = id;
        this.cached = [];
        this.pieces = new WeakMap();
        this.invalidate();
    }

    sample(): SpiderAnchor[] {
        return this.read(() => this.sampleCurrent());
    }

    private sampleCurrent(): SpiderAnchor[] {
        if (this.disposed) return [];
        const frame = this.measurements!;
        const exclusions = this.exclusionsCurrent();
        if (!this.dirty) return this.cached.flatMap(anchor => {
            const measured = this.measureCurrent(anchor);
            return measured && !exclusions.some(rect => intersects(measured.rect, rect)) ? [measured] : [];
        });
        this.dirty = false;
        this.roots = Array.from(document.querySelectorAll<HTMLElement>(ANCHOR_SELECTOR));
        const rootSet = new Set(this.roots);
        for (const observed of this.observedRoots) {
            if (rootSet.has(observed)) continue;
            this.resizeObserver?.unobserve(observed);
            this.observedRoots.delete(observed);
        }
        const priority = { tool: 0, text: 1, brand: 2 };
        this.roots.sort((a, b) => priority[a.dataset.spiderAnchor as SpiderAnchor['kind']]
            - priority[b.dataset.spiderAnchor as SpiderAnchor['kind']]);
        const hasMessages = Boolean(document.querySelector('[data-message-uuid]'));
        const anchors: SpiderAnchor[] = [];
        for (const element of this.roots) {
            if (anchors.length >= MAX_ANCHORS) break;
            if (element.dataset.spiderAnchor === 'brand' && hasMessages) continue;
            const bounds = frame.visibleBounds(element);
            if (!bounds || !intersects(frame.rect(element), bounds)
                || blockedAnchor(element)) continue;
            if (!this.observedRoots.has(element)) {
                this.resizeObserver?.observe(element);
                this.observedRoots.add(element);
            }
            const fragments = this.fragments(element, bounds);
            for (const glyphs of fragments) {
                if (anchors.length >= MAX_ANCHORS) break;
                const anchor = this.createAnchor(element, glyphs);
                if (anchor && !exclusions.some(rect => intersects(anchor.rect, rect))) anchors.push(anchor);
            }
        }
        this.cached = anchors;
        return anchors;
    }

    private fragments(element: HTMLElement, bounds: DOMRect): Glyph[][] {
        const frame = this.measurements!;
        const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
        const result: Glyph[][] = [];
        let current: Glyph[] = [];
        let characters = 0;
        const flush = () => {
            if (current.some(glyph => glyph.node.data.slice(glyph.start, glyph.end).trim())) result.push(current);
            current = [];
        };
        for (let node = walker.nextNode(); node && characters < MAX_ROOT_CHARACTERS; node = walker.nextNode()) {
            const text = node as Text;
            const parent = text.parentElement;
            if (!parent || parent.closest(SKIP_TEXT_SELECTOR) || parent.closest(ANCHOR_SELECTOR) !== element) { flush(); continue; }
            if (element.dataset.spiderAnchor !== 'tool' && parent.closest(CONTROL_SELECTOR)) { flush(); continue; }
            const localBounds = frame.visibleBounds(parent);
            if (!localBounds) { flush(); continue; }
            let previousEnd = 0;
            for (const offset of visibleTextOffsets(text, localBounds, this.segmenter)) {
                // A pruned interval must not be bridged by a multi-node DOM Range.
                if (offset.start > previousEnd) flush();
                previousEnd = offset.end;
                if (++characters > MAX_ROOT_CHARACTERS) break;
                const piece = { node: text, ...offset, original: text.data };
                const range = rangeFor(piece);
                const rect = rectOf(range);
                if (!rect || !contained(rect, bounds) || !contained(rect, localBounds)) { flush(); continue; }
                const first = current[0]?.rect;
                const last = current.at(-1)?.rect;
                const newLine = last && (rect.top >= last.bottom - 1 || rect.bottom <= last.top + 1);
                const tooWide = first && Math.max(rect.right, first.right) - Math.min(rect.left, first.left) > MAX_WIDTH;
                if (newLine || tooWide || current.length >= MAX_CHARACTERS) flush();
                if (rect.width <= MAX_WIDTH) current.push({ ...piece, rect });
                if (result.length >= MAX_ANCHORS) break;
            }
            if (previousEnd < text.length) flush();
            if (result.length >= MAX_ANCHORS) break;
        }
        flush();
        return result;
    }

    private createAnchor(element: HTMLElement, glyphs: Glyph[]): SpiderAnchor | null {
        if (!glyphs.length) return null;
        let elementId = this.elementIds.get(element);
        if (elementId === undefined) { elementId = ++this.nextElementId; this.elementIds.set(element, elementId); }
        const range = document.createRange();
        range.setStart(glyphs[0].node, glyphs[0].start);
        const last = glyphs[glyphs.length - 1];
        range.setEnd(last.node, last.end);
        let textId = this.textIds.get(glyphs[0].node);
        if (textId === undefined) { textId = ++this.nextTextId; this.textIds.set(glyphs[0].node, textId); }
        this.pieces.set(range, glyphs);
        const messageId = element.closest<HTMLElement>('[data-message-uuid]')?.dataset.messageUuid;
        const toolUseId = element.closest<HTMLElement>('[data-tool-use-id]')?.dataset.toolUseId;
        const anchor: SpiderAnchor = {
            id: JSON.stringify([this.sessionId, messageId ?? '', toolUseId ?? '', elementId, textId, glyphs[0].start, last.end]),
            sessionId: this.sessionId, messageId, toolUseId,
            kind: element.dataset.spiderAnchor as SpiderAnchor['kind'],
            element, range, rect: new DOMRect(), text: range.toString(), runs: [],
            contentVersion: this.measurements!.contentVersion(element),
        };
        return this.measureCurrent(anchor);
    }

    measure(anchor: SpiderAnchor): SpiderAnchor | null {
        return this.read(() => this.measureCurrent(anchor));
    }

    private measureCurrent(anchor: SpiderAnchor): SpiderAnchor | null {
        if (this.disposed) return null;
        const frame = this.measurements!;
        if (frame.measured.has(anchor)) return frame.measured.get(anchor) ?? null;
        const result = this.measureUncached(anchor, frame);
        frame.measured.set(anchor, result);
        if (result) frame.measured.set(result, result);
        return result;
    }

    private measureUncached(anchor: SpiderAnchor, frame: MeasurementFrame): SpiderAnchor | null {
        if (this.disposed || anchor.sessionId !== this.sessionId || !anchor.element.isConnected
            || blockedAnchor(anchor.element)
            || anchor.element.dataset.spiderAnchor !== anchor.kind
            || anchor.contentVersion !== frame.contentVersion(anchor.element)
            || anchor.element.closest<HTMLElement>('[data-message-uuid]')?.dataset.messageUuid !== anchor.messageId
            || anchor.element.closest<HTMLElement>('[data-tool-use-id]')?.dataset.toolUseId !== anchor.toolUseId) return null;
        const pieces = this.pieces.get(anchor.range);
        if (!pieces || pieces.some(piece => !piece.node.isConnected || piece.node.data !== piece.original)) return null;
        const bounds = frame.visibleBounds(anchor.element);
        if (!bounds || anchor.range.toString() !== anchor.text) return null;
        const rect = rectOf(anchor.range);
        if (!rect || rect.width > MAX_WIDTH + 1 || !contained(rect, bounds)) return null;
        // Selection is checked live, including during a lift; never mutate or clear it.
        if (frame.selectionRects().some(selected => intersects(rect, selected))) return null;
        const lineRects = Array.from(anchor.range.getClientRects()).filter(part => part.width > 0 && part.height > 0);
        if (lineRects.length && Math.max(...lineRects.map(part => part.top)) >= Math.min(...lineRects.map(part => part.bottom)) - 1) return null;
        const runs: SpiderTextRun[] = [];
        // Keep glyph positions for tracking/RTL text; merge ordinary contiguous glyphs per text node.
        let pending: Piece | null = null;
        const append = (piece: Piece) => {
            const parent = piece.node.parentElement;
            if (!parent) return false;
            const clip = frame.visibleBounds(parent);
            const partRect = rectOf(rangeFor(piece));
            if (!clip || !partRect || !contained(partRect, clip)) return false;
            const style = frame.style(parent);
            runs.push({ text: piece.node.data.slice(piece.start, piece.end), x: partRect.left - rect.left,
                y: partRect.top - rect.top, width: partRect.width, height: partRect.height,
                font: fontOf(style), color: style.color });
            return true;
        };
        for (const piece of pieces) {
            const style = frame.style(piece.node.parentElement!);
            const separate = style.direction === 'rtl'
                || (Number.isFinite(parseFloat(style.letterSpacing)) && parseFloat(style.letterSpacing) !== 0)
                || (Number.isFinite(parseFloat(style.wordSpacing)) && parseFloat(style.wordSpacing) !== 0);
            if (pending && pending.node === piece.node && pending.end === piece.start && !separate) {
                pending.end = piece.end;
            } else {
                if (pending && !append(pending)) return null;
                pending = { ...piece };
            }
        }
        if (pending && !append(pending)) return null;
        return { ...anchor, rect, runs };
    }

    exclusionRects(): DOMRect[] {
        return this.read(() => this.exclusionsCurrent());
    }

    private exclusionsCurrent(): DOMRect[] {
        if (this.disposed) return [];
        const frame = this.measurements!;
        if (frame.exclusions) return frame.exclusions;
        const rects: DOMRect[] = [];
        for (const candidate of document.querySelectorAll<HTMLElement>(`${EXCLUDED_SELECTOR}, ${CONTROL_SELECTOR}`)) {
            if (candidate.matches(CONTROL_SELECTOR) && !candidate.matches(EXCLUDED_SELECTOR)
                && (candidate.dataset.spiderAnchor === 'tool' || candidate.querySelector('[data-spider-anchor="tool"]')
                    || candidate.matches(READING_DISCLOSURE_SELECTOR))
                && !controlEngaged(candidate)) continue;
            const element = candidate.matches('button[aria-label="Close zoom"]')
                ? candidate.closest<HTMLElement>('.fixed') ?? candidate : candidate;
            // Never let a broad application/body role accidentally exclude the entire page.
            if (broadRoot(element)) continue;
            const clip = frame.visibleBounds(element);
            const rect = frame.rect(element);
            if (clip && rect.width > 0 && rect.height > 0 && intersects(rect, clip)
                && !rects.some(existing => contained(rect, existing))) rects.push(rect);
        }
        rects.push(...frame.selectionRects());
        return frame.exclusions = rects;
    }

    dispose(): void {
        this.disposed = true;
        this.mutationObserver.disconnect();
        this.resizeObserver?.disconnect();
        window.removeEventListener('resize', this.invalidate);
        document.removeEventListener('scroll', this.invalidate, true);
        document.removeEventListener('selectionchange', this.invalidate);
        document.removeEventListener('focusin', this.invalidateControl);
        document.removeEventListener('focusout', this.invalidateControl);
        document.removeEventListener('pointerover', this.invalidateControl);
        document.removeEventListener('pointerout', this.invalidateControl);
        window.visualViewport?.removeEventListener('resize', this.invalidate);
        window.visualViewport?.removeEventListener('scroll', this.invalidate);
        document.fonts?.removeEventListener('loadingdone', this.invalidate);
        this.cached = [];
        this.roots = [];
        this.observedRoots.clear();
        this.pieces = new WeakMap();
        this.elementIds = new WeakMap();
        this.textIds = new WeakMap();
        this.measurements = null;
        this.selectionState = null;
    }
}
