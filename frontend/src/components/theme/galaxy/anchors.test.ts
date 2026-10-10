import { afterEach, describe, expect, it, vi } from 'vitest';
import { collectAnchors } from './anchors';

const originalRects = Object.getOwnPropertyDescriptor(Range.prototype, 'getClientRects');
const originalHit = Object.getOwnPropertyDescriptor(document, 'elementFromPoint');
afterEach(() => {
    document.body.replaceChildren();
    if (originalRects) Object.defineProperty(Range.prototype, 'getClientRects', originalRects);
    else Reflect.deleteProperty(Range.prototype, 'getClientRects');
    if (originalHit) Object.defineProperty(document, 'elementFromPoint', originalHit);
    else Reflect.deleteProperty(document, 'elementFromPoint');
    vi.restoreAllMocks();
});
function measure() {
    const rect = new DOMRect(100, 100, 120, 24);
    Object.defineProperty(Range.prototype, 'getClientRects', { configurable: true, value: () => [rect] });
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => document.querySelector('[data-galaxy-text],[data-galaxy-tool-ids]') });
}
describe('galaxy business anchors', () => {
    it('never treats the user prompt as an assistant reply', () => {
        document.body.innerHTML = '<div class="turn-card"><div class="user-message"><p data-galaxy-text>新问题</p></div></div>';
        measure();
        expect(collectAnchors({ x: -1000, y: -1000 }).anchors).toEqual([]);
    });
    it('does not target an old reply when the newest turn has no reply yet', () => {
        document.body.innerHTML = '<div class="turn-card"><p data-galaxy-text>历史回答</p></div><div class="turn-card"></div>';
        measure();
        expect(collectAnchors({ x: -1000, y: -1000 }).anchors).toEqual([]);
    });
    it('uses confirmed tool identities on the collapsed process summary', () => {
        document.body.innerHTML = `<button data-galaxy-tool-ids='["read-1","read-2"]'>正在执行 · 2 步</button>`;
        measure();
        expect(collectAnchors({ x: -1000, y: -1000 }).anchors.map(a => a.id)).toEqual(['tool:read-2', 'tool:read-1']);
    });
});

function measureAll() {
    let measured: Element | null = null;
    Object.defineProperty(Range.prototype, 'getClientRects', { configurable: true, value: function(this: Range) {
        measured = this.commonAncestorContainer.nodeType === Node.ELEMENT_NODE
            ? this.commonAncestorContainer as Element : this.commonAncestorContainer.parentElement;
        return [new DOMRect(100, 100, 120, 24)];
    } });
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: (_x: number, y: number) =>
        y > 300 ? document.querySelector('.chat-composer-surface') : measured });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function(this: HTMLElement) {
        return this.matches('.chat-composer-surface') ? new DOMRect(100, 400, 300, 80) : new DOMRect(100, 100, 120, 24);
    });
}
describe('galaxy bounded identity selection', () => {
    it('keeps active tool, current reply and composer when summaries exceed renderer capacity', () => {
        document.body.innerHTML = Array.from({ length: 3 }, (_, group) => {
            const ids = Array.from({ length: 25 }, (_, i) => `g${group}-${i}`);
            return `<button data-galaxy-tool-ids='${JSON.stringify(ids)}'>执行摘要</button>`;
        }).join('') + '<div data-message-uuid="current"><p data-galaxy-text>当前回复</p></div><div class="chat-composer-surface">输入</div>';
        measureAll();
        const result = collectAnchors({ x: -1000, y: -1000 }, { activeToolId: 'g0-0', replyMessageId: 'current' }).anchors;
        expect(result).toHaveLength(16);
        expect(result.slice(0, 3).map(a => a.id)).toEqual(['tool:g0-0', 'reply', 'input']);
        expect(new Set(result.map(a => a.id)).size).toBe(result.length);
    });
    it('does not fall back to historical rows when the current reply unmounts', () => {
        document.body.innerHTML = '<div class="turn-card" data-message-uuid="old"><p data-galaxy-text>历史回答</p></div><div class="turn-card" data-message-uuid="current"><p data-galaxy-text>当前回答</p></div>';
        measureAll();
        const collect = () => collectAnchors({ x: -1000, y: -1000 }, { replyMessageId: 'current' }).anchors;
        expect(collect().some(a => a.id === 'reply')).toBe(true);
        document.querySelector('[data-message-uuid="current"]')!.remove();
        expect(collect().some(a => a.id === 'reply')).toBe(false);
    });
    it('does not select a visible user prompt even when its id is supplied', () => {
        document.body.innerHTML = '<div class="user-message" data-message-uuid="user"><p data-galaxy-text>问题</p></div>';
        measureAll();
        expect(collectAnchors({ x: -1000, y: -1000 }, { replyMessageId: 'user' }).anchors).toEqual([]);
    });
});
