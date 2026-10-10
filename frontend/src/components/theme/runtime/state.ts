import type { VisualPhase } from './types';
export type ToolPhase = Extract<VisualPhase, 'preparing' | 'executing' | 'succeeded' | 'failed' | 'cancelled'>;
type Source = 'store' | 'run';
export const isTerminalTool = (phase: ToolPhase): boolean => phase === 'succeeded' || phase === 'failed' || phase === 'cancelled';
/** A completed tool cannot become executing when the REST start arrives late. */
export class VisualToolState {
    private tools = new Map<string, {
        phase: ToolPhase;
        source: Source;
    }>();
    accept(id: string, phase: ToolPhase, source: Source = 'store'): boolean {
        const previous = this.tools.get(id);
        if (previous) {
            if (isTerminalTool(previous.phase)) {
                // A generic WS error may later be identified as an authoritative cancellation.
                if (!isTerminalTool(phase) || previous.source === 'run' || source !== 'run')
                    return false;
            }
            else if (previous.phase === 'executing' && phase === 'preparing')
                return false;
            if (previous.phase === phase) {
                if (source === 'run')
                    previous.source = source;
                return false;
            }
        }
        this.tools.set(id, { phase, source });
        // Keep identities for this mounted Session: message reconciliation can expose
        // every historical result again. Evicting them would replay old completions.
        // Session switches, rebinding and skin disposal clear this entire projection.
        return true;
    }
    clear(): void { this.tools.clear(); }
}
export function record(value: unknown): Record<string, unknown> | null {
    return value !== null && typeof value === 'object' && !Array.isArray(value)
        ? value as Record<string, unknown> : null;
}
export function resultPhase(result: unknown): ToolPhase | null {
    const value = record(result);
    if (!value)
        return null;
    const metadata = record(value.metadata);
    const status = value.executionStatus ?? metadata?.executionStatus;
    if (status === 'cancelled')
        return 'cancelled';
    if (status === 'failed' || status === 'timed_out')
        return 'failed';
    if (status === 'succeeded')
        return 'succeeded';
    return typeof value.isError === 'boolean' ? value.isError ? 'failed' : 'succeeded' : null;
}
export interface VisualRunEvent {
    runId: string;
    seq: number;
    eventType: string;
    eventData: string;
}
/** Parse only documented identity and phase fields; no payload text reaches the renderer. */
export function parseRunToolEvent(event: VisualRunEvent): {
    toolUseId: string;
    phase: ToolPhase;
} | null {
    if (event.eventType !== 'tool_started' && event.eventType !== 'tool_finished')
        return null;
    try {
        const envelope = record(JSON.parse(event.eventData));
        const data = record(envelope?.data);
        const id = envelope?.toolUseId ?? data?.toolUseId;
        if (typeof id !== 'string' || !id)
            return null;
        if (event.eventType === 'tool_started')
            return { toolUseId: id, phase: 'executing' };
        const phase = resultPhase(data);
        return phase ? { toolUseId: id, phase } : null;
    }
    catch {
        return null;
    }
}
export function runStatusPhase(status: string): VisualPhase | null {
    switch (status.toUpperCase()) {
        case 'COMPLETED': return 'complete';
        case 'FAILED': return 'failed';
        case 'CANCELLED':
        case 'INTERRUPTED': return 'cancelled';
        case 'WAITING_INTERACTION': return 'waiting';
        default: return null;
    }
}
export function isTerminalRun(status: string): boolean {
    return ['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(status.toUpperCase());
}
