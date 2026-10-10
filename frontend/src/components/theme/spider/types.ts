export type { VisualPhase as SpiderPhase, VisualSignal as SpiderSignal } from '../runtime/types';

export interface SpiderTextRun {
    text: string;
    /** CSS pixels relative to the anchor rectangle; y is the top of the run. */
    x: number;
    y: number;
    width: number;
    height: number;
    font: string;
    color: string;
}

export interface SpiderAnchor {
    id: string;
    sessionId: string | null;
    toolUseId?: string;
    messageId?: string;
    kind: 'tool' | 'text' | 'brand';
    element: HTMLElement;
    range: Range;
    rect: DOMRect;
    text: string;
    runs: SpiderTextRun[];
    contentVersion: string;
}
