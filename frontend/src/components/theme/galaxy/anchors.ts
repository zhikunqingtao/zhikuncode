import type { Bounds, GalaxyAnchor } from './scene';
import { MAX_GALAXY_ANCHORS } from './limits';
export const PROTECTED = '[role="dialog"],[role="menu"],[role="listbox"],.monaco-editor,.xterm,[data-spider-exclude],input,textarea,[contenteditable="true"]';
export function visibleRect(element: Element, source = element.getBoundingClientRect()): DOMRect | null {
    if (!element.isConnected || source.width < 1 || source.height < 1)
        return null;
    const style = getComputedStyle(element);
    if (style.visibility === 'hidden' || style.display === 'none')
        return null;
    let left = Math.max(0, source.left), top = Math.max(0, source.top), right = Math.min(innerWidth, source.right), bottom = Math.min(innerHeight, source.bottom);
    for (let parent = element.parentElement; parent; parent = parent.parentElement) {
        const css = getComputedStyle(parent);
        if (css.display === 'none' || css.visibility === 'hidden')
            return null;
        const clipX = /auto|scroll|hidden|clip/.test(css.overflowX);
        const clipY = /auto|scroll|hidden|clip/.test(css.overflowY);
        if (!clipX && !clipY)
            continue;
        const box = parent.getBoundingClientRect();
        if (clipX) {
            left = Math.max(left, box.left);
            right = Math.min(right, box.right);
        }
        if (clipY) {
            top = Math.max(top, box.top);
            bottom = Math.min(bottom, box.bottom);
        }
    }
    if (right - left < 2 || bottom - top < 2)
        return null;
    const hit = document.elementFromPoint((left + right) / 2, (top + bottom) / 2);
    if (!hit || !(hit === element || element.contains(hit)))
        return null;
    return new DOMRect(left, top, right - left, bottom - top);
}
export const bounds = (r: DOMRect): Bounds => ({ x: r.left / innerWidth, y: r.top / innerHeight, w: r.width / innerWidth, h: r.height / innerHeight });
export function selected(element: Element): boolean {
    const selection = getSelection();
    if (!selection || selection.isCollapsed)
        return false;
    try {
        return Array.from({ length: selection.rangeCount }, (_, i) => selection.getRangeAt(i)).some(range => range.intersectsNode(element));
    }
    catch {
        return true;
    }
}
/** Measurements are invalidated by layout changes, never used to move business DOM. */
export function collectAnchors(pointer: {
    x: number;
    y: number;
}, identity: { replyMessageId?: string | null; activeToolId?: string } = {}) {
    const anchors: GalaxyAnchor[] = [], zones: Bounds[] = [];
    const blocked = [...document.querySelectorAll(PROTECTED)].map(el => visibleRect(el)).filter((r): r is DOMRect => r !== null);
    const targetRect = (element: HTMLElement): DOMRect | null => {
        if (element.closest(PROTECTED) || selected(element))
            return null;
        const range = document.createRange();
        range.selectNodeContents(element);
        const rects = [...range.getClientRects()].slice(-40).reverse();
        for (const raw of rects) {
            const r = visibleRect(element, raw);
            if (!r)
                continue;
            const x = r.left + r.width / 2, y = r.bottom;
            if (pointer.x > r.left - 36 && pointer.x < r.right + 36 && pointer.y > r.top - 30 && pointer.y < r.bottom + 30)
                continue;
            if (blocked.some(b => x >= b.left - 8 && x <= b.right + 8 && y >= b.top - 8 && y <= b.bottom + 8))
                continue;
            return r;
        }
        return null;
    };
    const seen = new Set<string>();
    for (const el of [...document.querySelectorAll<HTMLElement>('[data-spider-anchor="tool"]')].reverse()) {
        const toolId = el.closest<HTMLElement>('[data-tool-use-id]')?.dataset.toolUseId;
        if (!toolId || seen.has(toolId))
            continue;
        const r = targetRect(el);
        if (!r)
            continue;
        seen.add(toolId);
        anchors.push({ id: `tool:${toolId}`, kind: 'tool', x: (r.left + r.width / 2) / innerWidth, y: r.bottom / innerHeight });

    }
    // A collapsed process still owns real tool identities; dock on its visible summary.
    for (const el of [...document.querySelectorAll<HTMLElement>('[data-galaxy-tool-ids]')].reverse()) {
        const r = targetRect(el);
        if (!r) continue;
        let ids: unknown;
        try { ids = JSON.parse(el.dataset.galaxyToolIds ?? '[]'); } catch { continue; }
        if (!Array.isArray(ids)) continue;
        const candidates = ids.slice(-12).reverse();
        if (identity.activeToolId && ids.includes(identity.activeToolId)) candidates.unshift(identity.activeToolId);
        for (const id of candidates) {
            if (typeof id !== 'string' || seen.has(id)) continue;
            seen.add(id);
            anchors.push({ id: `tool:${id}`, kind: 'tool', x: (r.left + r.width / 2) / innerWidth, y: r.bottom / innerHeight });
        }
    }
    // DOM order is not message identity: virtual lists may only mount historical turns.
    const texts = identity.replyMessageId
        ? [...document.querySelectorAll<HTMLElement>('[data-galaxy-text]')].filter(el => {
            const owner = el.closest<HTMLElement>('[data-galaxy-message-id],[data-message-uuid]');
            return !el.closest('.user-message') && (owner?.dataset.galaxyMessageId ?? owner?.dataset.messageUuid) === identity.replyMessageId;
        })
        : [];

    for (const el of texts.reverse()) {
        const r = targetRect(el);
        if (!r)
            continue;
        anchors.push({ id: 'reply', kind: 'result', x: (r.left + r.width / 2) / innerWidth, y: r.bottom / innerHeight });
        break;
    }
    // Launch docks at the outside edge of the composer, never inside its controls.
    const composer = document.querySelector<HTMLElement>('.chat-composer-surface');
    if (composer) {
        const r = visibleRect(composer);
        if (r)
            anchors.push({ id: 'input', kind: 'input', x: (r.left + r.width * .6) / innerWidth, y: Math.max(0, r.top - 10) / innerHeight });
    }
    for (const el of document.querySelectorAll('.app-header,.app-sidebar,.chat-composer-surface,.text-block > p,.text-block > ul,.text-block > ol,.user-message-bubble,[role="dialog"],.monaco-editor,.xterm')) {
        const r = visibleRect(el);
        if (r)
            zones.push(bounds(r));
    }
    const priority = (a: GalaxyAnchor) => a.id === `tool:${identity.activeToolId}` ? 0 : a.id === 'reply' ? 1 : a.id === 'input' ? 2 : 3;
    anchors.sort((a, b) => priority(a) - priority(b));
    return { anchors: anchors.slice(0, MAX_GALAXY_ANCHORS), zones: zones.slice(0, 12) };
}
/** DOM text offsets, never raw Markdown offsets. Non-prefix rewrites establish a new baseline. */
export function appendedText(previous: string | undefined, current: string): {
    start: number;
    end: number;
} | null {
    return previous !== undefined && current.length > previous.length && current.startsWith(previous)
        ? { start: previous.length, end: current.length } : null;
}
