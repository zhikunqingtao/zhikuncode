import { describe, expect, it } from 'vitest';
import { parseRunToolEvent, resultPhase, runStatusPhase, SpiderToolState } from './runtimeState';

describe('spider execution truth', () => {
    it('never regresses a tool when its persisted start arrives after its WS result', () => {
        const state = new SpiderToolState();
        expect(state.accept('read-1', 'preparing')).toBe(true);
        expect(state.accept('read-1', 'succeeded')).toBe(true);
        expect(state.accept('read-1', 'executing', 'run')).toBe(false);
        expect(state.accept('read-1', 'succeeded', 'run')).toBe(false);
        expect(state.accept('read-1', 'preparing')).toBe(false);
    });

    it('refines a generic WS failure to confirmed cancellation without restarting the tool', () => {
        const state = new SpiderToolState();
        expect(state.accept('read-2', 'failed')).toBe(true);
        expect(state.accept('read-2', 'cancelled', 'run')).toBe(true);
        expect(state.accept('read-2', 'failed')).toBe(false);
        expect(state.accept('read-2', 'executing', 'run')).toBe(false);
    });

    it('reads the envelope shape captured by the live read-only test, without exposing its payload', () => {
        const event = {
            runId: 'run', seq: 4, eventType: 'tool_started',
            eventData: JSON.stringify({ schemaVersion: 2, entityId: 'run', toolUseId: 'call-read', data: {
                toolName: 'Read', executionAttemptId: 'attempt', evaluationStage: 'FINAL_RECHECK',
                outcome: 'ALLOW', redactedSummary: 'Read inside Project: spider.ts', inputHash: 'sensitive-hash',
            } }),
        };
        expect(parseRunToolEvent(event)).toEqual({ toolUseId: 'call-read', phase: 'executing' });
        expect(parseRunToolEvent({ ...event, seq: 5, eventType: 'tool_finished', eventData: JSON.stringify({
            schemaVersion: 2, entityId: 'run', toolUseId: 'call-read', data: {
                executionStatus: 'succeeded', isError: false, durationMs: 2, outputPreview: 'private source text',
            },
        }) })).toEqual({ toolUseId: 'call-read', phase: 'succeeded' });
        expect(parseRunToolEvent({ ...event, eventType: 'tool_use_start' })).toBeNull();
        expect(parseRunToolEvent({ ...event, eventData: '{broken' })).toBeNull();
    });

    it('does not invent result or run success', () => {
        expect(resultPhase({ content: 'done' })).toBeNull();
        expect(resultPhase({ isError: true })).toBe('failed');
        expect(resultPhase({ metadata: { executionStatus: 'cancelled' } })).toBe('cancelled');
        expect(resultPhase({ executionStatus: 'timed_out' })).toBe('failed');
        expect(runStatusPhase('CANCELLING')).toBeNull();
        expect(runStatusPhase('idle')).toBeNull();
        expect(runStatusPhase('cancelled')).toBe('cancelled');
    });

    it('retains long-session terminal identities when history is reconciled again', () => {
        const state = new SpiderToolState();
        for (let i = 0; i < 2300; i++) expect(state.accept(`tool-${i}`, 'succeeded')).toBe(true);
        for (let i = 0; i < 2300; i++) expect(state.accept(`tool-${i}`, 'succeeded')).toBe(false);
        expect(state.accept('tool-0', 'executing', 'run')).toBe(false);
        state.clear();
        expect(state.accept('tool-0', 'preparing')).toBe(true);
    });
});
