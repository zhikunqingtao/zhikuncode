export type SpiderPhase = 'reset' | 'preparing' | 'executing' | 'succeeded' | 'failed' | 'cancelled' | 'waiting' | 'streaming' | 'complete' | 'disconnected';

/** Visual signals never write to the conversation or authorize tools. */
export interface SpiderSignal {
    phase: SpiderPhase;
    sessionId: string | null;
    toolUseId?: string;
    /** Binding generations discard all old actions; local idle may retain result afterglow. */
    resetScope?: 'binding';
    at: number;
}

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
