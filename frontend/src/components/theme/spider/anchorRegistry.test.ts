import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SpiderAnchorRegistry } from './anchorRegistry';

let registry: SpiderAnchorRegistry;
const disconnect = vi.fn();

function layoutRoot(node: Node): HTMLElement {
    const element = node instanceof HTMLElement ? node : node.parentElement!;
    return element.closest<HTMLElement>('[data-spider-anchor]') ?? element;
}

/** Deterministic browser-layout substitute: ten-pixel glyphs and 20-pixel line boxes. */
function rangeRect(range: Range): DOMRect {
    const root = layoutRoot(range.startContainer);
    const before = document.createRange();
    before.selectNodeContents(root);
    before.setEnd(range.startContainer, range.startOffset);
    const start = before.toString().length;
    const length = range.toString().length;
    const wrap = Number(root.dataset.wrap ?? 1000);
    const x = Number(root.dataset.x ?? 20);
    const y = Number(root.dataset.y ?? 30);
    const firstLine = Math.floor(start / wrap);
    const lastLine = Math.floor(Math.max(start, start + length - 1) / wrap);
    return new DOMRect(x + (firstLine === lastLine ? start % wrap * 10 : 0),
        y + firstLine * 20, firstLine === lastLine ? length * 10 : wrap * 10,
        (lastLine - firstLine + 1) * 20);
}

function mount(html: string): void {
    document.body.innerHTML = html;
    registry = new SpiderAnchorRegistry();
    registry.setSession('session-a');
}

beforeEach(() => {
    disconnect.mockClear();
    vi.stubGlobal('ResizeObserver', class {
        observe = vi.fn(); unobserve = vi.fn(); disconnect = disconnect;
    });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
        return new DOMRect(Number(this.dataset.x ?? 0), Number(this.dataset.y ?? 0),
            Number(this.dataset.width ?? 700), Number(this.dataset.height ?? 600));
    });
    Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
        configurable: true, value: function (this: Range) { return rangeRect(this); },
    });
    Object.defineProperty(Range.prototype, 'getClientRects', {
        configurable: true, value: function (this: Range) { return [rangeRect(this)]; },
    });
});

afterEach(() => {
    registry?.dispose();
    window.getSelection()?.removeAllRanges();
    document.body.innerHTML = '';
    document.body.removeAttribute('role');
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

describe('SpiderAnchorRegistry', () => {
    it('shares a synchronous frame scan, ancestor styles and anchor measurements without repeated layout reads', () => {
        mount('<div style="overflow-x: auto; overflow-y: auto"><p data-spider-anchor="text">abcdefghijklmnopqrstuvwxyz0123456789</p><button data-x="600" data-width="50" data-height="25">copy</button></div>');
        const styleReads = vi.spyOn(window, 'getComputedStyle');
        const elementReads = vi.mocked(HTMLElement.prototype.getBoundingClientRect);
        const rangeReads = vi.spyOn(Range.prototype, 'getBoundingClientRect');
        const scans = vi.spyOn(document, 'querySelectorAll');
        registry.beginFrame();
        const anchors = registry.sample();
        expect(anchors).toHaveLength(3);
        const counts = [styleReads.mock.calls.length, elementReads.mock.calls.length,
            rangeReads.mock.calls.length, scans.mock.calls.length];
        // Each unique styled element (including shared ancestors) is read once, even
        // while generating multiple fragments and measuring each text run.
        expect(new Set(styleReads.mock.calls.map(call => call[0])).size).toBe(counts[0]);
        expect(registry.exclusionRects()).toHaveLength(1);
        for (const anchor of anchors) {
            expect(registry.measure(anchor)?.rect).toEqual(anchor.rect);
            expect(registry.measure(anchor)?.runs).toEqual(anchor.runs);
        }
        expect(registry.measure({ ...anchors[0], contentVersion: 'different version' })).toBeNull();
        expect(registry.sample()).toEqual(anchors);
        expect([styleReads.mock.calls.length, elementReads.mock.calls.length,
            rangeReads.mock.calls.length, scans.mock.calls.length]).toEqual(counts);
        registry.endFrame();
    });

    it('never reuses a previous frame or standalone read after geometry moves', () => {
        mount('<p data-spider-anchor="text">stable text</p>');
        registry.beginFrame();
        const anchor = registry.sample()[0];
        registry.endFrame();
        anchor.element.dataset.x = '80';
        registry.beginFrame();
        expect(registry.measure(anchor)?.rect.x).toBe(80);
        registry.endFrame();
        anchor.element.dataset.x = '110';
        expect(registry.measure(anchor)?.rect.x).toBe(110);
        anchor.element.dataset.x = '120';
        expect(registry.measure(anchor)?.rect.x).toBe(120);
        expect(registry.measure({ ...anchor, contentVersion: 'different version' })).toBeNull();
    });

    it('invalidates same-frame font, content, native collapse and virtual unmount changes synchronously', () => {
        mount('<details open><summary data-height="20">details</summary><p data-spider-anchor="text" data-y="150" style="font: 14px Arial">stable text</p></details>');
        registry.beginFrame();
        const anchor = registry.sample()[0];
        expect(anchor.runs[0].font).toContain('14px Arial');
        anchor.element.style.font = '700 18px monospace';
        expect(registry.measure(anchor)?.runs[0].font).toContain('700 18px monospace');
        anchor.element.firstChild!.textContent = 'updated text';
        expect(registry.measure(anchor)).toBeNull();
        const current = registry.sample()[0];
        expect(current.text).toBe('updated text');
        const details = document.querySelector('details')!;
        details.open = false;
        expect(registry.measure(current)).toBeNull();
        expect(registry.sample()).toEqual([]);
        details.open = true;
        const reopened = registry.sample()[0];
        expect(reopened.text).toBe('updated text');
        reopened.element.remove();
        expect(registry.measure(reopened)).toBeNull();
        expect(registry.sample()).toEqual([]);
        registry.endFrame();
    });

    it('invalidates same-frame scroll, resize and selection without moving the original content', () => {
        mount('<div style="overflow-x: auto; overflow-y: auto" data-height="100"><p data-spider-anchor="text">scroll text</p></div>');
        registry.beginFrame();
        const anchor = registry.sample()[0];
        anchor.element.dataset.x = '50';
        anchor.element.dispatchEvent(new Event('scroll'));
        expect(registry.measure(anchor)?.rect.left).toBe(50);
        const selection = window.getSelection()!;
        selection.addRange(anchor.range.cloneRange()); // no selectionchange dispatch
        expect(registry.measure(anchor)).toBeNull();
        expect(registry.exclusionRects()).toHaveLength(1);
        expect(selection.toString()).toBe('scroll text');
        selection.removeAllRanges();
        expect(registry.measure(anchor)?.text).toBe('scroll text');
        expect(registry.exclusionRects()).toEqual([]);
        anchor.element.dataset.y = '110';
        window.dispatchEvent(new Event('resize'));
        expect(registry.measure(anchor)).toBeNull();
        expect(anchor.element.textContent).toBe('scroll text');
        registry.endFrame();
    });

    it('invalidates same-frame control hover and focus and retains full control exclusions', () => {
        mount('<button data-width="300" data-height="80"><span data-spider-anchor="tool">Read</span></button>');
        registry.beginFrame();
        const anchor = registry.sample()[0];
        expect(registry.exclusionRects()).toEqual([]);
        const button = document.querySelector('button')!;
        const matches = button.matches.bind(button);
        const hovered = vi.spyOn(button, 'matches').mockImplementation(selector => selector === ':hover, :active' || matches(selector));
        button.dispatchEvent(new Event('pointerover', { bubbles: true }));
        expect(registry.measure(anchor)).toBeNull();
        expect(registry.exclusionRects()[0].width).toBe(300);
        hovered.mockRestore();
        button.dispatchEvent(new Event('pointerout', { bubbles: true }));
        expect(registry.measure(anchor)?.text).toBe('Read');
        button.focus();
        expect(registry.measure(anchor)).toBeNull();
        expect(registry.exclusionRects()[0].width).toBe(300);
        button.blur();
        expect(registry.measure(anchor)?.text).toBe('Read');
        registry.endFrame();
    });

    it('prioritizes tool text and distinguishes repeated message/tool presentations', () => {
        mount(`<div data-message-uuid="same"><p data-spider-anchor="text">正文</p>
            <span data-tool-use-id="tool-a" data-spider-anchor="tool">Read</span></div>
            <div data-message-uuid="same"><span data-tool-use-id="tool-a" data-spider-anchor="tool">Read</span></div>`);
        const anchors = registry.sample();
        expect(anchors.map(anchor => anchor.kind)).toEqual(['tool', 'tool', 'text']);
        expect(new Set(anchors.map(anchor => anchor.id)).size).toBe(3);
        expect(anchors[0]).toMatchObject({ sessionId: 'session-a', messageId: 'same', toolUseId: 'tool-a' });
        expect(registry.sample().map(anchor => anchor.id)).toEqual(anchors.map(anchor => anchor.id));
    });

    it('splits visual lines and long lines into bounded text fragments', () => {
        mount(`<p data-spider-anchor="text" data-wrap="20">${'a'.repeat(65)}</p>
            <p data-spider-anchor="text" data-y="160">${'b'.repeat(80)}</p>`);
        const anchors = registry.sample();
        expect(anchors.filter(anchor => anchor.text.includes('a')).map(anchor => anchor.text.length)).toEqual([13, 7, 13, 7, 13, 7, 5]);
        expect(anchors.every(anchor => anchor.rect.width <= 136 && anchor.text.length <= 60)).toBe(true);
    });

    it('finds a long single text node at its visible tail before spending the character budget', () => {
        const tail = '0123456789'.repeat(20);
        mount(`<div style="overflow-x: auto; overflow-y: auto" data-y="40" data-height="100"><p data-spider-anchor="text" data-wrap="20" data-y="-19960" data-height="20200">${'x'.repeat(20000)}${tail}</p></div>`);
        const measurements = vi.spyOn(Range.prototype, 'getBoundingClientRect');
        const anchors = registry.sample();
        expect(anchors.map(anchor => anchor.text).join('')).toBe(tail.slice(0, 100));
        expect(anchors.every(anchor => anchor.range.startOffset >= 20000
            && anchor.rect.top >= 40 && anchor.rect.bottom <= 140 && anchor.rect.width <= 136)).toBe(true);
        // Layout probes grow with the visible range and binary search depth, not the
        // 20,000 invisible characters. This catches an unbounded per-glyph workaround.
        expect(measurements.mock.calls.length).toBeLessThan(650);
        expect(anchors[0].range.startContainer.textContent).toBe('x'.repeat(20000) + tail);
    });

    it('relocates visible slices after scroll, viewport clipping changes and collapse', async () => {
        const tail = '0123456789'.repeat(12);
        mount(`<details open style="overflow-x: auto; overflow-y: auto" data-y="40" data-height="60"><summary data-height="20">details</summary><p data-spider-anchor="text" data-wrap="20" data-y="0" data-height="5200">${'x'.repeat(5000)}${tail}</p></details>`);
        const first = registry.sample()[0];
        expect(first.text).toMatch(/^x+$/);
        const root = document.querySelector('p')!;
        root.dataset.y = '-4960';
        root.dispatchEvent(new Event('scroll'));
        expect(registry.measure(first)).toBeNull();
        expect(registry.sample().map(anchor => anchor.text).join('')).toBe(tail.slice(0, 60));
        const container = document.querySelector('details')!;
        container.dataset.height = '40';
        window.dispatchEvent(new Event('resize'));
        expect(registry.sample().map(anchor => anchor.text).join('')).toBe(tail.slice(0, 40));
        container.open = false;
        await Promise.resolve();
        expect(registry.sample()).toEqual([]);
        container.open = true;
        await Promise.resolve();
        expect(registry.sample().map(anchor => anchor.text).join('')).toBe(tail.slice(0, 40));
    });

    it('preserves graphemes crossing a visible-search split and skips whole offscreen inline nodes', () => {
        // A 4,096-code-unit node splits at 3,968, inside the combining sequence.
        const text = 'x'.repeat(3967) + 'e\u0301👩‍💻' + 'z'.repeat(122);
        mount(`<div style="overflow-x: auto; overflow-y: auto" data-y="40" data-height="100"><p data-spider-anchor="text" data-wrap="20" data-y="-3880" data-height="4400">${text}<span>${'z'.repeat(104)}</span><code style="font: 700 13px monospace">tail</code></p></div>`);
        const anchors = registry.sample();
        expect(anchors.map(anchor => anchor.text).join('')).toContain('e\u0301👩‍💻');
        const boundaries = new Set([0, ...Array.from(new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text), part => part.index + part.segment.length)]);
        expect(anchors.every(anchor => boundaries.has(anchor.range.startOffset) && boundaries.has(anchor.range.endOffset))).toBe(true);
        const root = document.querySelector('p')!;
        root.dataset.y = '-4160';
        root.dispatchEvent(new Event('scroll'));
        const later = registry.sample();
        expect(later.map(anchor => anchor.text).join('')).toBe('tail');
        expect(later[0].runs[0].font).toContain('700 13px monospace');
    });

    it('bounds real DOM ranges to 136px without changing their text, mixed fonts or measured position', () => {
        mount('<p data-spider-anchor="text" data-x="72" data-y="90" style="font: 15px Arial">src/<code style="font: 700 13px monospace">spider-renderer.ts</code> → Read</p>');
        const element = document.querySelector<HTMLElement>('[data-spider-anchor]')!;
        const original = element.textContent;
        const anchors = registry.sample();
        expect(anchors).toHaveLength(3);
        expect(anchors.map(anchor => anchor.text).join('')).toBe(original);
        expect(anchors.every(anchor => anchor.rect.width <= 136 && anchor.range.toString() === anchor.text)).toBe(true);
        expect(anchors.map(anchor => [anchor.rect.x, anchor.rect.y, anchor.rect.width])).toEqual([
            [72, 90, 130], [202, 90, 130], [332, 90, 30],
        ]);
        expect(anchors[0].runs.map(run => run.text)).toEqual(['src/', 'spider-re']);
        expect(anchors[0].runs[1]).toMatchObject({ x: 40, y: 0 });
        expect(anchors[0].runs[0].font).toContain('15px Arial');
        expect(anchors[0].runs[1].font).toContain('700 13px monospace');
        element.dataset.x = '97';
        expect(registry.measure(anchors[1])?.rect.x).toBe(227);
        expect(registry.measure(anchors[1])?.text).toBe(anchors[1].text);
        expect(element.textContent).toBe(original);
    });

    it('preserves inline code fonts/colors and relative glyph positions', () => {
        mount('<p data-spider-anchor="text" style="font: 16px Arial; color: rgb(1, 2, 3)">Run <code style="font: 700 14px monospace; color: rgb(4, 5, 6)">npm</code> now</p>');
        const anchor = registry.sample()[0];
        expect(anchor.text).toBe('Run npm now');
        expect(anchor.runs.map(run => run.text)).toEqual(['Run ', 'npm', ' now']);
        expect(anchor.runs[1]).toMatchObject({ x: 40, y: 0, color: 'rgb(4, 5, 6)' });
        expect(anchor.runs[1].font).toContain('700 14px monospace');
        expect(anchor.runs[0].font).toContain('16px Arial');
    });

    it('rejects modified, replaced, hidden, detached and previous-session anchors', () => {
        mount('<p data-spider-anchor="text">stable text</p>');
        const anchor = registry.sample()[0];
        anchor.element.firstChild!.textContent = 'changed text';
        expect(registry.measure(anchor)).toBeNull();
        anchor.element.textContent = 'stable text';
        expect(registry.measure(anchor)).toBeNull(); // same text, different node identity
        registry.setSession('session-b');
        expect(registry.measure(anchor)).toBeNull();
        const current = registry.sample()[0];
        current.element.hidden = true;
        expect(registry.measure(current)).toBeNull();
        current.element.hidden = false;
        current.element.remove();
        expect(registry.measure(current)).toBeNull();
    });

    it('remeasures each frame and rejects text scrolled outside its own overflow container', () => {
        mount('<div style="overflow-x: auto; overflow-y: auto" data-height="100"><p data-spider-anchor="text">scroll text</p></div>');
        const anchor = registry.sample()[0];
        anchor.element.dataset.x = '50';
        expect(registry.measure(anchor)?.rect.left).toBe(50);
        anchor.element.dataset.y = '110';
        expect(registry.measure(anchor)).toBeNull();
        expect(window.scrollY).toBe(0);
    });

    it('rejects recycled message identities and a block that resumes streaming', () => {
        mount('<div data-message-uuid="message-a"><p data-spider-anchor="text">same text</p></div>');
        const anchor = registry.sample()[0];
        anchor.element.parentElement!.dataset.messageUuid = 'message-b';
        expect(registry.measure(anchor)).toBeNull();
        anchor.element.parentElement!.dataset.messageUuid = 'message-a';
        delete anchor.element.dataset.spiderAnchor;
        expect(registry.measure(anchor)).toBeNull();
    });

    it('excludes the actual image zoom overlay even though it has no dialog role', () => {
        mount('<p data-spider-anchor="text">body text</p><div class="fixed" data-width="1000" data-height="700"><button aria-label="Close zoom">close</button></div>');
        expect(registry.exclusionRects()[0].width).toBe(1000);
        expect(registry.sample()).toEqual([]);
    });

    it('protects the entire composer dock including send, stop, toolbar and gaps', () => {
        mount('<p data-spider-anchor="text">outside</p><div class="chat-composer-dock" data-y="450" data-width="700" data-height="200"><div class="chat-composer-surface" data-y="460" data-width="680" data-height="170"><textarea data-y="470" data-width="300" data-height="60">draft</textarea><button data-x="580" data-y="550" data-width="45" data-height="45">send</button><button data-x="630" data-y="550" data-width="45" data-height="45">stop</button><span data-spider-anchor="tool" data-y="580">never capture</span></div></div>');
        const rects = registry.exclusionRects();
        expect(rects).toHaveLength(1);
        expect(rects[0]).toMatchObject({ x: 0, y: 450, width: 700, height: 200 });
        expect(registry.sample().map(anchor => anchor.text)).toEqual(['outside']);
        expect(document.querySelector('textarea')?.value).toBe('draft');
    });

    it('protects a standalone mobile composer and ignores hidden composer copies', () => {
        mount('<div data-testid="mobile-prompt-bar" data-y="400" data-width="320" data-height="220"><textarea data-y="420" data-width="260" data-height="60"></textarea><button data-x="270" data-y="560" data-width="44" data-height="44">send</button></div><div class="chat-composer-dock" hidden></div>');
        const rects = registry.exclusionRects();
        expect(rects).toHaveLength(1);
        expect(rects[0]).toMatchObject({ width: 320, height: 220 });
    });

    it('leaves tool disclosure text available at rest, then yields its whole focused control', () => {
        mount('<button aria-expanded="false" data-width="300" data-height="80"><span data-spider-anchor="tool">Read</span></button>');
        const anchor = registry.sample()[0];
        expect(anchor.text).toBe('Read');
        expect(registry.exclusionRects()).toEqual([]);
        const button = document.querySelector('button')!;
        const clicked = vi.fn();
        button.addEventListener('click', clicked);
        button.focus();
        expect(registry.measure(anchor)).toBeNull();
        expect(registry.exclusionRects()[0]).toMatchObject({ width: 300, height: 80 });
        button.click();
        expect(clicked).toHaveBeenCalledOnce();
        expect(button.getAttribute('aria-expanded')).toBe('false');
        button.blur();
    });

    it('immediately yields a hovered tool disclosure and excludes ordinary action buttons', () => {
        mount('<button data-width="300" data-height="80"><span data-spider-anchor="tool">Read</span></button><button data-x="500" data-width="60" data-height="30">copy</button>');
        const anchor = registry.sample()[0];
        const button = document.querySelector('button')!;
        const matches = button.matches.bind(button);
        vi.spyOn(button, 'matches').mockImplementation(selector => selector === ':hover, :active' || matches(selector));
        expect(registry.measure(anchor)).toBeNull();
        expect(registry.exclusionRects()).toHaveLength(2);
    });

    it('leaves reading disclosure rows clear at rest and protects their whole hovered or focused row', () => {
        mount('<div class="thinking-block"><button id="thinking" data-width="600" data-height="40">Thinking</button></div><button id="process" aria-label="详细过程区，点击展开" data-y="80" data-width="600" data-height="40">详细过程</button><button data-y="160" data-width="60" data-height="30">copy</button>');
        expect(registry.exclusionRects()).toHaveLength(1);
        expect(registry.exclusionRects()[0]).toMatchObject({ y: 160, width: 60 });
        const thinking = document.getElementById('thinking')!;
        const matches = thinking.matches.bind(thinking);
        vi.spyOn(thinking, 'matches').mockImplementation(selector => selector === ':hover, :active' || matches(selector));
        document.getElementById('process')!.focus();
        expect(registry.exclusionRects()).toEqual([
            expect.objectContaining({ y: 0, width: 600, height: 40 }),
            expect.objectContaining({ y: 80, width: 600, height: 40 }),
            expect.objectContaining({ y: 160, width: 60, height: 30 }),
        ]);
    });

    it('keeps explicit exclusions and approval dialogs protected around reading disclosures', () => {
        mount('<div class="thinking-block"><button data-spider-exclude data-width="600" data-height="40">Protected</button></div><div role="dialog" data-y="80" data-width="600" data-height="120"><button aria-label="详细过程区，点击收起" data-y="100" data-width="600" data-height="40">Approval details</button></div>');
        expect(registry.exclusionRects()).toEqual([
            expect.objectContaining({ y: 0, width: 600, height: 40 }),
            expect.objectContaining({ y: 80, width: 600, height: 120 }),
        ]);
    });

    it('protects code controls and skips linked/action text while preserving inline code', () => {
        mount('<div data-spider-anchor="text">plain <code>inline</code><a href="https://example.com" data-x="400" data-width="70" data-height="25">link</a><div class="code-block" data-x="400" data-y="150" data-width="250" data-height="150"><span>typescript</span><button data-x="550" data-y="160" data-width="50" data-height="30">copy</button><pre>const a = 1</pre></div></div>');
        const anchors = registry.sample();
        expect(anchors.map(anchor => anchor.text)).toEqual(['plain inline']);
        expect(registry.exclusionRects()).toHaveLength(2);
    });

    it('releases selected text immediately without changing the native selection or content', () => {
        mount('<p data-spider-anchor="text">select me</p>');
        const anchor = registry.sample()[0];
        const selection = window.getSelection()!;
        const range = anchor.range.cloneRange();
        range.setEnd(anchor.element.firstChild!, 6);
        selection.addRange(range);
        expect(registry.measure(anchor)).toBeNull();
        expect(registry.sample()).toEqual([]);
        expect(selection.toString()).toBe('select');
        expect(anchor.element.textContent).toBe('select me');
        selection.removeAllRanges();
        expect(registry.measure(anchor)?.text).toBe('select me');
    });

    it('resamples after selection clears or an exclusion overlay is removed', async () => {
        mount('<p data-spider-anchor="text">select me</p>');
        const node = document.querySelector('p')!.firstChild!;
        const range = document.createRange();
        range.selectNodeContents(node);
        window.getSelection()!.addRange(range);
        expect(registry.sample()).toEqual([]);
        window.getSelection()!.removeAllRanges();
        document.dispatchEvent(new Event('selectionchange'));
        expect(registry.sample()).toHaveLength(1);
        const overlay = document.createElement('div');
        overlay.setAttribute('role', 'dialog');
        document.body.append(overlay);
        await Promise.resolve();
        expect(registry.sample()).toEqual([]);
        overlay.remove();
        await Promise.resolve();
        expect(registry.sample()).toHaveLength(1);
    });

    it('rejects a collapsed native details subtree immediately', async () => {
        mount('<details open><summary>details</summary><p data-spider-anchor="text" data-y="150">detail text</p></details>');
        // The summary is a separate control, not the whole details container.
        document.querySelector('summary')!.dataset.height = '30';
        const anchor = registry.sample()[0];
        expect(anchor.text).toBe('detail text');
        document.querySelector('details')!.open = false;
        expect(registry.measure(anchor)).toBeNull();
        await Promise.resolve();
        expect(registry.sample()).toEqual([]);
    });

    it('samples only visible characters of an overflow-hidden target', () => {
        mount('<span data-spider-anchor="tool" style="overflow-x: hidden; overflow-y: hidden" data-width="80">long-file-name.ts</span>');
        const anchors = registry.sample();
        expect(anchors).toHaveLength(1);
        expect(anchors[0].text).toBe('long-f'); // 20px origin + six glyphs fit within 80px clip
        expect(anchors[0].rect.right).toBeLessThanOrEqual(80);
    });

    it('collects visible controls, dialogs, editors and selection without excluding broad body roles', () => {
        mount('<textarea data-x="400" data-width="200" data-height="50"></textarea><div role="dialog" data-x="400" data-y="100" data-width="200" data-height="60"></div><div class="monaco-editor" data-x="400" data-y="200" data-width="200" data-height="100"></div><input hidden><p data-spider-anchor="text">select me</p>');
        document.body.setAttribute('role', 'dialog');
        const anchor = registry.sample()[0];
        const selection = window.getSelection()!;
        selection.addRange(anchor.range.cloneRange());
        expect(registry.exclusionRects()).toHaveLength(4);
        document.body.removeAttribute('role');
    });

    it('ignores fenced blocks and brand fallback when messages exist', () => {
        mount('<span data-spider-anchor="brand">zhikuncode</span><div data-message-uuid="m"><div data-spider-anchor="text"><pre>code block</pre><span>body text</span></div></div>');
        expect(registry.sample().map(anchor => anchor.text)).toEqual(['body text']);
    });

    it('uses brand only in the empty state and unregisters observers/listeners on dispose', () => {
        mount('<span data-spider-anchor="brand">zhikuncode</span>');
        expect(registry.sample()[0].kind).toBe('brand');
        const removeWindow = vi.spyOn(window, 'removeEventListener');
        const removeDocument = vi.spyOn(document, 'removeEventListener');
        registry.dispose();
        expect(disconnect).toHaveBeenCalled();
        expect(removeWindow).toHaveBeenCalledWith('resize', expect.any(Function));
        expect(removeDocument).toHaveBeenCalledWith('scroll', expect.any(Function), true);
        expect(registry.sample()).toEqual([]);
        expect(registry.exclusionRects()).toEqual([]);
    });

    it('discovers mounted anchors after mutation without moving or expanding original DOM', async () => {
        mount('<div id="host"></div>');
        expect(registry.sample()).toEqual([]);
        document.getElementById('host')!.innerHTML = '<button aria-expanded="false"><span data-spider-anchor="tool">Read</span></button>';
        await Promise.resolve();
        expect(registry.sample()[0].text).toBe('Read');
        expect(document.querySelector('button')?.getAttribute('aria-expanded')).toBe('false');
    });
});
