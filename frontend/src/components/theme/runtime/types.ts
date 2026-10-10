export type VisualPhase = 'reset' | 'preparing' | 'executing' | 'succeeded' | 'failed' | 'cancelled' | 'waiting' | 'streaming' | 'complete' | 'disconnected';
/** Visual signals never write to the conversation or authorize tools. */
export interface VisualSignal {
    phase: VisualPhase;
    sessionId: string | null;
    runId?: string;
    toolUseId?: string;
    /** Binding generations discard all old actions; local idle may retain result afterglow. */
    resetScope?: 'binding';
    at: number;
}
