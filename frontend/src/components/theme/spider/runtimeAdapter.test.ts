import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { useRunStore } from '@/store/runStore';
import { streamingStore } from '@/hooks/useStreamingText';
import type { ContentBlock, Message, ToolCallState } from '@/types';
import type { SpiderSignal } from './types';
import { createSpiderRuntimeAdapter } from './runtimeAdapter';
import * as runtimeState from '../runtime/state';

const binding = vi.hoisted(() => ({ ready: true, listeners: new Set<() => void>() }));
vi.mock('@/api/dispatch', () => ({
    isSessionBindingReady: () => binding.ready,
    subscribeSessionBinding: (listener: () => void) => {
        binding.listeners.add(listener);
        return () => binding.listeners.delete(listener);
    },
}));

const run = (id: string, status = 'RUNNING') => ({ id, status, sessionId: 's1', parentRunId: null });
const event = (seq: number, type: string, toolUseId = 't1', data = {}, runId = 'r1') => ({
    runId, seq, eventType: type,
    eventData: JSON.stringify({ schemaVersion: 2, entityId: runId, toolUseId, data }),
});
const response = (data: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => data });
const page = (events: ReturnType<typeof event>[], hasMore = false) => ({
    events, nextSeq: events.at(-1)?.seq ?? 0, hasMore,
});
const assistant = (uuid: string, content: ContentBlock[]): Extract<Message, { type: 'assistant' }> => ({
    type: 'assistant', uuid, timestamp: 1, content, stopReason: '',
    usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
});
const toolBlock = (toolUseId: string, result?: { content: string; isError: boolean }): Extract<ContentBlock, { type: 'tool_use' }> => ({
    type: 'tool_use', toolUseId, toolName: 'Read', input: {}, ...(result ? { result } : {}),
});
let cleanup: (() => void) | undefined;
let signals: SpiderSignal[];
let fetchMock: ReturnType<typeof vi.fn>;
let roots: ReturnType<typeof run>[];
let events: ReturnType<typeof event>[];

function start() {
    const adapter = createSpiderRuntimeAdapter(signal => signals.push(signal));
    cleanup = adapter.dispose;
    return adapter;
}
function toolPhases(id = 't1') { return signals.filter(s => s.toolUseId === id).map(s => s.phase); }
async function tick(ms = 0) { await vi.advanceTimersByTimeAsync(ms); }
function setTool(result?: { isError: boolean }) {
    useMessageStore.setState({ activeToolCalls: new Map([['t1', {
        toolName: 'Read', input: { secret: 'do not copy' }, status: result ? result.isError ? 'error' : 'completed' : 'running',
        startTime: Date.now(), ...(result ? { result: { ...result, content: 'do not copy result' } } : {}),
    }]]) });
}

beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-09T16:00:00Z'));
    binding.ready = true;
    binding.listeners.clear();
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    useSessionStore.setState({ sessionId: 's1', status: 'idle', isAborted: false });
    useMessageStore.setState({ messages: [], activeToolCalls: new Map(), thinkingContent: '', streamingMessageId: null });
    usePermissionStore.setState({ pendingPermissions: [] });
    useBridgeStore.setState({ bridgeStatus: 'connected' });
    useRunStore.setState({ recoverySnapshots: new Map(), recoveryEventSeq: new Map() });
    streamingStore.clear();
    signals = [];
    roots = [];
    events = [];
    fetchMock = vi.fn(async (url: string) => {
        if (url.includes('/session/')) return response(roots);
        const after = Number(new URL(url, 'http://local').searchParams.get('afterSeq'));
        return response(page(events.filter(e => e.seq > after)));
    });
    vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
    cleanup?.();
    cleanup = undefined;
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
});

describe('read-only spider runtime adapter', () => {
    it('does not revisit historical tool blocks while real thinking and text updates stream', async () => {
        let historicalBlockVisits = 0;
        const history = Array.from({ length: 20 }, (_, messageIndex) => {
            const content = Array.from({ length: 50 }, (_, blockIndex) => toolBlock(`old-${messageIndex}-${blockIndex}`, { content: '', isError: false }));
            Object.defineProperty(content, Symbol.iterator, {
                value: function* (this: ContentBlock[]) {
                    for (let index = 0; index < this.length; index++) { historicalBlockVisits++; yield this[index]; }
                },
            });
            return assistant(`history-${messageIndex}`, content);
        });
        useMessageStore.setState({ messages: history });
        const phaseReads = vi.spyOn(runtimeState, 'resultPhase');
        start(); await tick();
        expect(historicalBlockVisits).toBe(1000);
        expect(phaseReads).toHaveBeenCalledTimes(1000);
        useSessionStore.getState().setStatus('streaming');
        useMessageStore.getState().startToolCall('live-tool', 'Read', {});
        const readsAfterStart = phaseReads.mock.calls.length;
        // startToolCall itself searches existing history once to reject replayed starts.
        const visitsAfterStart = historicalBlockVisits;
        const liveMessage = useMessageStore.getState().messages.at(-1);
        for (let index = 0; index < 100; index++) {
            useMessageStore.getState().appendThinkingDelta('thinking');
            useMessageStore.getState().appendStreamDelta('text');
        }
        expect(useMessageStore.getState().messages.at(-1)).not.toBe(liveMessage);
        expect(historicalBlockVisits).toBe(visitsAfterStart);
        expect(phaseReads).toHaveBeenCalledTimes(readsAfterStart);
        useMessageStore.getState().completeToolCall('live-tool', { content: 'done', isError: false });
        expect(toolPhases('live-tool')).toEqual(['preparing', 'succeeded']);
        expect(signals.filter(s => s.toolUseId?.startsWith('old-'))).toHaveLength(0);
    });

    it('projects only changed active call objects without revisiting the message list', async () => {
        const calls = new Map<string, ToolCallState>(Array.from({ length: 30 }, (_, index) => [
            `active-${index}`, { toolName: 'Read', input: {}, status: 'running', startTime: 1 },
        ]));
        useMessageStore.setState({ activeToolCalls: calls });
        const phaseReads = vi.spyOn(runtimeState, 'resultPhase');
        start(); await tick();
        expect(phaseReads).toHaveBeenCalledTimes(30);
        phaseReads.mockClear();
        useMessageStore.getState().updateToolCallProgress('active-17', 'still reading');
        expect(phaseReads).toHaveBeenCalledTimes(1);
        expect(signals.filter(s => s.toolUseId)).toHaveLength(0);
        useMessageStore.getState().completeToolCall('active-17', { content: 'done', isError: false });
        expect(phaseReads).toHaveBeenCalledTimes(2);
        expect(toolPhases('active-17')).toEqual(['succeeded']);
    });

    it('observes replacements and late historical results without replaying trimmed history', async () => {
        const pending = assistant('historical', [toolBlock('historical-tool')]);
        useMessageStore.setState({ messages: [pending, assistant('newer', [{ type: 'text', text: 'later' }])] });
        start(); await tick();
        useSessionStore.getState().setStatus('streaming');
        // This tool exists only in an earlier message, not in activeToolCalls.
        useMessageStore.getState().completeToolCall('historical-tool', { content: 'done', isError: false });
        expect(toolPhases('historical-tool')).toEqual(['succeeded']);
        const completed = useMessageStore.getState().messages[0];
        useMessageStore.getState().clearMessages();
        useMessageStore.setState({ messages: [completed] });
        expect(toolPhases('historical-tool')).toEqual(['succeeded']);

        const replacement = assistant('historical', [toolBlock('replacement-tool')]);
        useMessageStore.getState().addMessage(replacement);
        expect(toolPhases('replacement-tool')).toEqual(['preparing']);
        useMessageStore.getState().reconcileCommittedRun(null, [assistant('historical', [toolBlock('replacement-tool', { content: 'failed', isError: true })])]);
        expect(toolPhases('replacement-tool')).toEqual(['preparing', 'failed']);
        roots = [run('r1')];
        events = [event(1, 'tool_started', 'historical-tool'), event(2, 'tool_started', 'replacement-tool')];
        await tick(1000);
        expect(signals.some(s => s.phase === 'executing')).toBe(false);
    });

    it('rebuilds the silent tool baseline on rebinding even when store identities are reused', async () => {
        roots = [run('r1')];
        useMessageStore.setState({ messages: [assistant('history', [toolBlock('t1', { content: 'done', isError: false })])] });
        useSessionStore.getState().setStatus('streaming');
        start(); await tick();
        binding.ready = false;
        binding.listeners.forEach(listener => listener());
        binding.ready = true;
        binding.listeners.forEach(listener => listener());
        await tick();
        events = [event(1, 'tool_started')];
        await tick(1000);
        expect(toolPhases()).toEqual([]);
        useMessageStore.getState().addMessage(assistant('after-bind', [toolBlock('new-after-bind')]));
        expect(toolPhases('new-after-bind')).toEqual(['preparing']);
    });

    it('prepares from the live store but waits for the persisted start to execute', async () => {
        start(); await tick();
        useSessionStore.setState({ status: 'streaming' });
        setTool();
        expect(toolPhases()).toEqual(['preparing']);
        roots = [run('r1')];
        events = [event(1, 'tool_started')];
        await tick(1000);
        expect(toolPhases()).toEqual(['preparing', 'executing']);
        setTool({ isError: false });
        expect(toolPhases()).toEqual(['preparing', 'executing', 'succeeded']);
        events.push(event(2, 'tool_finished', 't1', { executionStatus: 'succeeded', isError: false, durationMs: 2 }));
        await tick(1000);
        expect(toolPhases()).toEqual(['preparing', 'executing', 'succeeded']);
        expect(JSON.stringify(signals)).not.toMatch(/secret|do not copy|toolName|input|content/);
        expect(fetchMock.mock.calls.every(([, options]) => (options as RequestInit).headers?.['X-Session-Id' as keyof HeadersInit] === 's1')).toBe(true);
    });

    it('keeps result-first tools terminal and ignores duplicate/out-of-order pages', async () => {
        start(); await tick();
        useSessionStore.setState({ status: 'streaming' });
        setTool(); setTool({ isError: true });
        roots = [run('r1')];
        events = [event(2, 'tool_finished', 't1', { executionStatus: 'failed', isError: true }), event(1, 'tool_started')];
        await tick(1000);
        expect(toolPhases()).toEqual(['preparing', 'failed']);
        fetchMock.mockImplementation(async (url: string) => response(url.includes('/session/') ? roots : page(events)));
        await tick(2000);
        expect(toolPhases()).toEqual(['preparing', 'failed']);
    });

    it('waits for approval, retreats on local cancellation, and emits cancelled only from authority', async () => {
        start(); await tick();
        useSessionStore.setState({ status: 'streaming' });
        roots = [run('r1')];
        setTool();
        usePermissionStore.setState({ pendingPermissions: [{ toolUseId: 't1', toolName: 'Read' }] as never });
        await tick(1000);
        expect(signals.at(-1)?.phase).toBe('waiting');
        expect(toolPhases()).not.toContain('executing');
        usePermissionStore.setState({ pendingPermissions: [] });
        expect(signals.at(-1)?.phase).toBe('preparing');
        useSessionStore.setState({ status: 'idle', isAborted: true });
        expect(signals.at(-1)?.phase).toBe('reset');
        expect(signals.at(-1)?.resetScope).toBeUndefined();
        expect(signals.some(s => s.phase === 'cancelled')).toBe(false);
        roots = [run('r1', 'CANCELLED')];
        events = [event(1, 'tool_finished', 't1', { executionStatus: 'cancelled', isError: true })];
        await tick(1000);
        expect(toolPhases().at(-1)).toBe('cancelled');
        expect(signals.at(-1)?.phase).toBe('cancelled');
        const count = fetchMock.mock.calls.length;
        await tick(5000);
        expect(fetchMock).toHaveBeenCalledTimes(count);
    });

    it('leaves Run-only elicitation waiting even when the Session stays streaming', async () => {
        start(); await tick();
        useSessionStore.getState().setStatus('streaming');
        roots = [run('r1')];
        await tick(1000);

        roots = [run('r1', 'WAITING_INTERACTION')];
        events = [event(1, 'run_status_changed', '', { to: 'waiting_interaction' })];
        await tick(2000);
        expect(useSessionStore.getState().status).toBe('streaming');
        expect(signals.filter(s => s.phase === 'waiting')).toHaveLength(1);
        const beforeAnswer = signals.length;

        // DialogManager sets the same Session status after an elicitation answer.
        useSessionStore.getState().setStatus('streaming');
        roots = [run('r1')];
        events.push(event(2, 'run_status_changed', '', { to: 'running' }), event(3, 'tool_started'));
        await tick(1000);
        expect(signals.slice(beforeAnswer).filter(s => s.phase === 'preparing' && !s.toolUseId)).toHaveLength(1);
        expect(toolPhases()).toEqual(['executing']);
        await tick(1000);
        expect(signals.slice(beforeAnswer).filter(s => s.phase === 'preparing' && !s.toolUseId)).toHaveLength(1);
    });

    it('keeps a live approval waiting when an older RUNNING event and tool start arrive', async () => {
        start(); await tick();
        useSessionStore.getState().setStatus('streaming');
        roots = [run('r1', 'WAITING_INTERACTION')];
        events = [event(1, 'run_status_changed', '', { to: 'waiting_interaction' })];
        await tick(1000);
        usePermissionStore.setState({ pendingPermissions: [{ toolUseId: 'approval', toolName: 'Write' }] as never });
        useSessionStore.getState().setStatus('waiting_permission');
        const beforeLateEvents = signals.length;

        roots = [run('r1')];
        events.push(event(2, 'run_status_changed', '', { to: 'running' }), event(3, 'tool_started'));
        await tick(1000);
        expect(signals.slice(beforeLateEvents).some(s => !s.toolUseId && (s.phase === 'preparing' || s.phase === 'reset'))).toBe(false);
        streamingStore.append('still waiting'); streamingStore.flush();
        await tick(300);
        expect(signals.slice(beforeLateEvents).some(s => s.phase === 'streaming')).toBe(false);

        usePermissionStore.setState({ pendingPermissions: [] });
        expect(signals.slice(beforeLateEvents).some(s => s.phase === 'preparing')).toBe(false);
        useSessionStore.getState().setStatus('streaming');
        expect(signals.at(-1)).toMatchObject({ phase: 'preparing' });
        expect(signals.at(-1)?.toolUseId).toBeUndefined();
    });

    it('treats old terminal runs as a silent baseline, then discovers the actual new root', async () => {
        roots = [run('old', 'COMPLETED')];
        start(); await tick();
        expect(signals.map(s => s.phase)).toEqual(['reset']);
        useSessionStore.setState({ status: 'streaming' });
        await tick(1000);
        expect(signals.some(s => s.phase === 'complete')).toBe(false);
        roots = [{ ...run('child'), parentRunId: 'r1' } as never, run('r1'), run('old', 'COMPLETED')];
        events = [event(1, 'tool_started')];
        await tick(1000);
        expect(toolPhases()).toEqual(['executing']);
        roots = [run('r1', 'COMPLETED')];
        await tick(1000);
        expect(signals.at(-1)?.phase).toBe('complete');
        const count = fetchMock.mock.calls.length;
        await tick(5000);
        expect(fetchMock).toHaveBeenCalledTimes(count);
    });

    it('uses the bind recovery cursor without replaying history and resumes only new events', async () => {
        roots = [run('r1')];
        useSessionStore.setState({ status: 'streaming' });
        setTool();
        useRunStore.setState({ recoverySnapshots: new Map([['r1', { id: 'r1', status: 'RUNNING' }]]), recoveryEventSeq: new Map([['r1', 12]]) });
        events = [event(11, 'tool_started'), event(12, 'tool_finished', 'old', { isError: false })];
        start(); await tick();
        expect(fetchMock.mock.calls.some(([url]) => String(url).includes('afterSeq=12'))).toBe(true);
        expect(toolPhases()).toEqual([]);
        events.push(event(13, 'tool_finished', 't1', { executionStatus: 'succeeded', isError: false }));
        await tick(1000);
        expect(toolPhases()).toEqual(['succeeded']);
    });

    it('discards a late response after a session switch and never overlaps requests', async () => {
        let resolveOld!: (value: ReturnType<typeof response>) => void;
        fetchMock.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
        start(); await tick();
        expect(fetchMock).toHaveBeenCalledTimes(1);
        const oldSignal = fetchMock.mock.calls[0][1].signal;
        useSessionStore.setState({ sessionId: 's2', status: 'streaming' });
        await tick(1000);
        expect(oldSignal.aborted).toBe(true);
        expect(fetchMock).toHaveBeenCalledTimes(1);
        resolveOld(response([run('r1')]));
        await tick(1);
        expect(fetchMock.mock.calls.length).toBe(2);
        expect(String(fetchMock.mock.calls[1][0])).toContain('/session/s2');
        expect(signals.filter(s => s.toolUseId)).toEqual([]);
    });

    it('pauses during rebinding/disconnect, stops querying hidden pages, and cleans up', async () => {
        useSessionStore.setState({ status: 'streaming' });
        start(); await tick();
        binding.ready = false;
        binding.listeners.forEach(listener => listener());
        expect(signals.at(-1)).toMatchObject({ phase: 'reset', sessionId: 's1', resetScope: 'binding' });
        const count = fetchMock.mock.calls.length;
        await tick(3000);
        expect(fetchMock).toHaveBeenCalledTimes(count);
        useBridgeStore.setState({ bridgeStatus: 'disconnected' });
        expect(signals.at(-1)?.phase).toBe('disconnected');
        binding.ready = true;
        useBridgeStore.setState({ bridgeStatus: 'connected' });
        await tick();
        Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
        document.dispatchEvent(new Event('visibilitychange'));
        const hiddenCount = fetchMock.mock.calls.length;
        await tick(5000);
        expect(fetchMock).toHaveBeenCalledTimes(hiddenCount);
        Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
        document.dispatchEvent(new Event('visibilitychange'));
        await tick();
        expect(fetchMock.mock.calls.length).toBeGreaterThan(hiddenCount);
        cleanup?.(); cleanup = undefined;
        const settledCount = fetchMock.mock.calls.length;
        setTool();
        await tick(3000);
        expect(fetchMock).toHaveBeenCalledTimes(settledCount);
    });

    it('coalesces stream pulses and high-frequency store writes cannot starve polling', async () => {
        start(); await tick();
        useSessionStore.setState({ status: 'streaming' });
        roots = [run('r1')];
        for (let i = 0; i < 30; i++) {
            streamingStore.append('x'); streamingStore.flush();
            useMessageStore.setState({ thinkingContent: `thinking ${i}` });
            await tick(50);
        }
        expect(signals.filter(s => s.phase === 'streaming').length).toBeLessThanOrEqual(7);
        expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/session/')).length).toBeGreaterThanOrEqual(2);
    });

    it('keeps delayed reads at the polling interval during real thinking message updates', async () => {
        const listStarts: number[] = [];
        fetchMock.mockImplementation(async (url: string) => {
            if (url.includes('/session/')) listStarts.push(Date.now());
            await new Promise(resolve => setTimeout(resolve, 50));
            return response(url.includes('/session/') ? roots : page([]));
        });
        start(); await tick(100);
        useSessionStore.getState().setStatus('streaming');
        roots = [run('r1')];
        const previousMessages = useMessageStore.getState().messages;
        for (let i = 0; i < 300; i++) {
            useMessageStore.getState().appendThinkingDelta('x');
            await tick(10);
        }
        expect(useMessageStore.getState().messages).not.toBe(previousMessages);
        // Includes the initial baseline read; ordinary updates neither starve nor accelerate polling.
        expect(listStarts.length).toBeGreaterThanOrEqual(3);
        expect(listStarts.length).toBeLessThanOrEqual(4);
        expect(listStarts.slice(1).every((at, index) => at - listStarts[index] >= 1000)).toBe(true);
        expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/events?')).length).toBeLessThanOrEqual(3);
    });

    it('still follows up an explicit recovery refresh immediately after an in-flight read', async () => {
        start(); await tick();
        useSessionStore.getState().setStatus('streaming');
        roots = [run('r1')];
        let finishRead!: (value: ReturnType<typeof response>) => void;
        let delayed = false;
        fetchMock.mockImplementation(async (url: string) => {
            if (url.includes('/session/')) return response(roots);
            if (!delayed) {
                delayed = true;
                return new Promise(resolve => { finishRead = resolve; });
            }
            return response(page([]));
        });
        await tick(1000);
        expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/session/'))).toHaveLength(2);
        useRunStore.getState().replaceRecoverySnapshot('r1', { id: 'r1', status: 'RUNNING' }, 0);
        expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/session/'))).toHaveLength(2);
        finishRead(response(page([])));
        await tick(1);
        expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/session/'))).toHaveLength(3);
    });

    it('backs off network failures and suppresses inaccessible Run event requests', async () => {
        fetchMock.mockRejectedValueOnce(new Error('offline'));
        useSessionStore.setState({ status: 'streaming' });
        start(); await tick();
        expect(fetchMock).toHaveBeenCalledTimes(1);
        await tick(1999);
        expect(fetchMock).toHaveBeenCalledTimes(1);
        await tick(1);
        expect(fetchMock).toHaveBeenCalledTimes(2);
        roots = [run('r1')];
        fetchMock.mockImplementation(async (url: string) => response(url.includes('/session/') ? roots : {}, url.includes('/events?') ? 403 : 200));
        await tick(1000);
        await tick(4000);
        expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/events?'))).toHaveLength(1);
        expect(signals.some(s => s.phase === 'failed')).toBe(false);
    });

    it('retries the final event read even after the terminal snapshot and idle tail polls', async () => {
        start(); await tick();
        useSessionStore.setState({ status: 'streaming' });
        roots = [run('r1')];
        events = [event(1, 'tool_started')];
        await tick(1000);
        useSessionStore.setState({ status: 'idle' });
        roots = [run('r1', 'COMPLETED')];
        events.push(event(2, 'tool_finished', 't1', { executionStatus: 'succeeded', isError: false }));
        let failedFinalReads = 0;
        fetchMock.mockImplementation(async (url: string) => {
            if (url.includes('/session/')) return response(roots);
            if (failedFinalReads++ < 2) throw new Error('temporary event storage failure');
            return response(page(events));
        });
        await tick(7000);
        expect(failedFinalReads).toBe(3);
        expect(toolPhases()).toEqual(['executing', 'succeeded']);
        expect(signals.filter(s => s.phase === 'complete')).toHaveLength(1);
        const count = fetchMock.mock.calls.length;
        await tick(10_000);
        expect(fetchMock).toHaveBeenCalledTimes(count);
    });

    it('keeps the initial historical baseline silent when its event read has to retry', async () => {
        roots = [run('r1')];
        useSessionStore.setState({ status: 'streaming' });
        events = [event(1, 'tool_started'), event(2, 'tool_finished', 't1', { isError: false })];
        let eventReads = 0;
        fetchMock.mockImplementation(async (url: string) => {
            if (url.includes('/session/')) return response(roots);
            if (eventReads++ === 0) throw new Error('connection closed while reading baseline');
            return response(page(events));
        });
        start(); await tick();
        await tick(2000);
        expect(eventReads).toBe(2);
        expect(toolPhases()).toEqual([]);
        events.push(event(3, 'tool_started', 'new-tool'));
        await tick(1000);
        expect(toolPhases('new-tool')).toEqual(['executing']);
    });

    it('replays the recorded 129-second real-model event timing without claiming execution after the result', async () => {
        // Derived from the 2026-10-09 real-readonly-events.json / real-readonly.jsonl recording.
        // Preserve its 20 seqs and relative timestamps; replace identities and omit all content,
        // prompts, paths, authorization hashes, provider payloads and token/cost values.
        const captured = [
            [1, 0, 'run_started'], [2, 8758, 'message_completed'], [3, 8760, 'cost_snapshot'],
            [4, 8768, 'tool_started', 't1'], [5, 8770, 'tool_finished', 't1', 'succeeded'],
            [6, 63395, 'message_completed'], [7, 63396, 'cost_snapshot'],
            [8, 63399, 'tool_started', 't2'], [9, 63400, 'tool_finished', 't2', 'failed'],
            [10, 92795, 'message_completed'], [11, 92795, 'cost_snapshot'],
            [12, 92797, 'tool_started', 't3'], [13, 92799, 'tool_finished', 't3', 'succeeded'],
            [14, 95809, 'message_completed'], [15, 95810, 'cost_snapshot'],
            [16, 95812, 'tool_started', 't4'], [17, 95813, 'tool_finished', 't4', 'failed'],
            [18, 129261, 'message_completed'], [19, 129261, 'cost_snapshot'],
            [20, 129263, 'run_status_changed'],
        ] as const;
        const wire = [
            [8396, 't1', 'start'], [8762, 't1', 'input'], [8772, 't1', 'succeeded'],
            [63326, 't2', 'start'], [63397, 't2', 'input'], [63402, 't2', 'failed'],
            [92468, 't3', 'start'], [92796, 't3', 'input'], [92800, 't3', 'succeeded'],
            [95771, 't4', 'start'], [95810, 't4', 'input'], [95814, 't4', 'failed'],
        ] as const;
        start(); await tick();
        const began = Date.now();
        useSessionStore.setState({ status: 'streaming' });
        fetchMock.mockImplementation(async (url: string) => {
            const elapsed = Date.now() - began;
            if (url.includes('/session/')) return response([run('r1', elapsed >= 129263 ? 'COMPLETED' : 'RUNNING')]);
            const after = Number(new URL(url, 'http://local').searchParams.get('afterSeq'));
            return response(page(captured.filter(([seq, at]) => seq > after && at <= elapsed).map(row => {
                const [seq, , type] = row;
                const toolId = row.length > 3 ? row[3] : '';
                const phase = row.length > 4 ? row[4] : undefined;
                return event(seq, type, toolId, type === 'run_status_changed' ? { to: 'completed' }
                    : phase ? { executionStatus: phase, isError: phase === 'failed' } : {});
            })));
        });
        for (const [at, id, phase] of wire) setTimeout(() => {
            const store = useMessageStore.getState();
            if (phase === 'start') store.startToolCall(id, 'Read', {});
            else if (phase === 'input') store.updateToolCallInput(id, { file_path: 'omitted.fixture' });
            else store.completeToolCall(id, { content: '', isError: phase === 'failed' });
        }, at);
        setTimeout(() => useSessionStore.setState({ status: 'idle' }), 129269);
        await tick(130_000);
        expect(toolPhases('t1')).toEqual(['preparing', 'succeeded']);
        expect(toolPhases('t2')).toEqual(['preparing', 'failed']);
        expect(toolPhases('t3')).toEqual(['preparing', 'succeeded']);
        expect(toolPhases('t4')).toEqual(['preparing', 'failed']);
        expect(signals.filter(s => s.phase === 'executing')).toHaveLength(0);
        expect(signals.filter(s => s.phase === 'complete')).toHaveLength(1);
        const count = fetchMock.mock.calls.length;
        await tick(10_000);
        expect(fetchMock).toHaveBeenCalledTimes(count);
    });
});
