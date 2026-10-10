import { isSessionBindingReady, subscribeSessionBinding } from '@/api/dispatch';
import { streamingStore } from '@/hooks/useStreamingText';
import { useBridgeStore } from '@/store/bridgeStore';
import { useMessageStore, type MessageStoreState } from '@/store/messageStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useRunStore } from '@/store/runStore';
import { useSessionStore } from '@/store/sessionStore';
import type { SpiderPhase, SpiderSignal } from './types';
import { isTerminalRun, parseRunToolEvent, record, resultPhase, runStatusPhase, SpiderToolState } from './runtimeState';
import type { SpiderRunEvent, ToolPhase } from './runtimeState';

interface RunView { id: string; status: string; parentRunId: string | null }
interface CurrentRun extends RunView {
    cursor: number;
    terminalSeen: boolean;
    blocked: boolean;
    cancellingSeen: boolean;
    hydrating: boolean;
}

class ObservationError extends Error {
    constructor(readonly status: number) { super(`Spider observation unavailable (${status})`); }
}

const POLL_MS = 1000;

/**
 * Read-only projection: never dispatches, changes stores, opens sockets, or sends commands.
 * Store tool "running" means model preparation; only persisted tool_started proves execution.
 */
export function createSpiderRuntimeAdapter(emit: (signal: SpiderSignal) => void): { dispose(): void } {
    let disposed = false;
    let generation = 0;
    let sessionId: string | null = null;
    let ready = false;
    let disconnected = false;
    let waiting = false;
    let previousStatus = useSessionStore.getState().status;
    let current: CurrentRun | null = null;
    let initialized = false;
    let discoveryBlocked = false;
    let awaitingRunUntil = 0;
    let tailPolls = 0;
    let failures = 0;
    let busy = false;
    let pollAgain = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let pulseTimer: ReturnType<typeof setTimeout> | null = null;
    let request: AbortController | null = null;
    let lastPulse = 0;
    let lastStreamingText = streamingStore.getSnapshot();
    let lastThinkingText = useMessageStore.getState().thinkingContent;
    let lastRecoverySnapshots = useRunStore.getState().recoverySnapshots;
    let lastRecoveryCursors = useRunStore.getState().recoveryEventSeq;
    const knownRoots = new Set<string>();
    const toolState = new SpiderToolState();
    let observedCalls: MessageStoreState['activeToolCalls'] | null = null;
    let observedMessages: MessageStoreState['messages'] | null = null;
    let seenMessages = new WeakSet<object>();
    let seenToolBlocks = new WeakSet<object>();

    const signal = (phase: SpiderPhase, toolUseId?: string, resetScope?: SpiderSignal['resetScope']) => {
        if (disposed) return;
        // Decoration must not be able to interrupt a synchronous business-store subscription.
        try { emit({ phase, sessionId, ...(toolUseId ? { toolUseId } : {}), ...(resetScope ? { resetScope } : {}), at: Date.now() }); }
        catch { /* The renderer owns its own failure boundary. */ }
    };
    const visible = () => typeof document === 'undefined' || document.visibilityState !== 'hidden';
    const canObserve = () => !disposed && ready && !!sessionId && visible() && !disconnected;
    const clearTimer = () => { if (timer !== null) clearTimeout(timer); timer = null; };
    const clearPulse = () => { if (pulseTimer !== null) clearTimeout(pulseTimer); pulseTimer = null; };
    const tool = (id: string, phase: ToolPhase, quiet = false, source: 'store' | 'run' = 'store') => {
        if (toolState.accept(id, phase, source) && !quiet && visible()) signal(phase, id);
    };

    function refreshWaiting(): void {
        const session = useSessionStore.getState();
        const runStatus = current?.status.toUpperCase();
        // Elicitation can pause the Run while the Session remains streaming.
        // Local approvals still take precedence over a delayed RUNNING snapshot.
        const nextWaiting = ready && (usePermissionStore.getState().pendingPermissions.length > 0
            || session.status === 'waiting_permission' || runStatus === 'WAITING_INTERACTION');
        if (nextWaiting === waiting) return;
        waiting = nextWaiting;
        clearPulse();
        if (waiting) signal('waiting');
        else if (ready && session.status !== 'idle'
            && (!runStatus || runStatus === 'RUNNING' || runStatus === 'QUEUED')) signal('preparing');
    }

    function observeTools(quiet = false): void {
        if (!ready || !sessionId) return;
        const state = useMessageStore.getState();
        // Immer preserves unchanged objects. Thinking/text updates need no active-tool work,
        // and a changed Map only requires projecting entries whose call object changed.
        if (state.activeToolCalls !== observedCalls) {
            const previousCalls = observedCalls;
            observedCalls = state.activeToolCalls;
            for (const [id, call] of state.activeToolCalls) {
                if (previousCalls?.get(id) === call) continue;
                const phase = resultPhase(call.result) ?? (call.status === 'error' ? 'failed' : 'preparing');
                tool(id, phase, quiet);
            }
        }
        // Results may survive only in a committed assistant segment after activeToolCalls is cleared.
        // Weak identities also handle reordered/replayed history without retaining removed snapshots.
        if (state.messages === observedMessages) return;
        observedMessages = state.messages;
        for (const message of state.messages) {
            if (seenMessages.has(message)) continue;
            seenMessages.add(message);
            if (message.type !== 'assistant') continue;
            for (const block of message.content) {
                if (block.type !== 'tool_use' || seenToolBlocks.has(block)) continue;
                seenToolBlocks.add(block);
                tool(block.toolUseId, resultPhase(block.result) ?? 'preparing', quiet);
            }
        }
    }

    function clearToolObservation(): void {
        observedCalls = null;
        observedMessages = null;
        seenMessages = new WeakSet();
        seenToolBlocks = new WeakSet();
    }

    function pulse(): void {
        if (!canObserve() || waiting || current?.status.toUpperCase() === 'WAITING_INTERACTION'
            || current?.status.toUpperCase() === 'CANCELLING' || useSessionStore.getState().status === 'idle') return;
        if (pulseTimer !== null) return;
        const delay = Math.max(0, 240 - (Date.now() - lastPulse));
        const stamp = generation;
        pulseTimer = setTimeout(() => {
            pulseTimer = null;
            if (stamp !== generation || !canObserve() || waiting || current?.status.toUpperCase() === 'WAITING_INTERACTION'
                || current?.status.toUpperCase() === 'CANCELLING' || useSessionStore.getState().status === 'idle') return;
            lastPulse = Date.now();
            signal('streaming');
        }, delay);
    }

    function shouldPoll(): boolean {
        if (current?.terminalSeen && tailPolls === 0 && Date.now() >= awaitingRunUntil) return false;
        return !discoveryBlocked && (!initialized || tailPolls > 0 || Date.now() < awaitingRunUntil
            || useSessionStore.getState().status !== 'idle'
            // A terminal snapshot still needs its final event page, including after a failed read.
            || !!current && !current.blocked && !current.terminalSeen);
    }

    function schedule(delay = POLL_MS): void {
        if (!canObserve() || !shouldPoll()) return;
        // Only binding/recovery/visibility refreshes need an immediate follow-up.
        // Ordinary stream writes must not turn polling into a continuous fetch loop.
        if (busy) { if (delay === 0) pollAgain = true; return; }
        // Stream updates must not postpone the next poll indefinitely.
        if (timer !== null && delay > 0) return;
        clearTimer();
        timer = setTimeout(() => { timer = null; void poll(); }, delay);
    }

    async function get(path: string, sid: string, controller: AbortController): Promise<unknown> {
        const response = await fetch(path, { headers: { 'X-Session-Id': sid }, signal: controller.signal });
        if (!response.ok) throw new ObservationError(response.status);
        return response.json();
    }

    function recoveryCursor(id: string): number | undefined {
        const store = useRunStore.getState();
        const snapshot = store.recoverySnapshots.get(id);
        // Some bind snapshots omit sessionId. The REST-owned run identity still proves ownership.
        if (!snapshot || snapshot.sessionId && snapshot.sessionId !== sessionId) return undefined;
        const cursor = store.recoveryEventSeq.get(id);
        return typeof cursor === 'number' && Number.isInteger(cursor) && cursor >= 0 ? cursor : undefined;
    }

    async function poll(): Promise<void> {
        if (!canObserve() || !shouldPoll() || busy || !sessionId) return;
        busy = true;
        pollAgain = false;
        const stamp = generation;
        const sid = sessionId;
        const controller = new AbortController();
        request = controller;
        const valid = () => !disposed && generation === stamp && sessionId === sid && ready && !controller.signal.aborted;
        let fetchingEvents = false;
        try {
            const raw = await get(`/api/runs/session/${encodeURIComponent(sid)}?limit=100`, sid, controller);
            if (!valid()) return;
            if (!Array.isArray(raw)) throw new Error('Invalid run list');
            const roots = raw.map(record).filter((r): r is Record<string, unknown> => !!r
                && typeof r.id === 'string' && typeof r.status === 'string'
                && (r.parentRunId === null || r.parentRunId === undefined));
            const latest = roots[0];
            const initial = !initialized;
            const previousId = current?.id;
            if (latest && latest.id !== current?.id && (initial || !knownRoots.has(String(latest.id)))) {
                const id = String(latest.id);
                current = {
                    id, status: String(latest.status), parentRunId: null,
                    cursor: initial ? recoveryCursor(id) ?? 0 : 0,
                    terminalSeen: initial && isTerminalRun(String(latest.status)), blocked: false,
                    cancellingSeen: false,
                    hydrating: initial,
                };
                if (!initial) awaitingRunUntil = 0;
            } else if (latest && current && current.id === latest.id) {
                current.status = String(latest.status);
            }
            roots.forEach(root => knownRoots.add(String(root.id)));
            // Bound the baseline without losing the currently selected root.
            if (knownRoots.size > 512) {
                for (const id of knownRoots) {
                    if (id !== current?.id) knownRoots.delete(id);
                    if (knownRoots.size <= 256) break;
                }
            }
            initialized = true;
            if (tailPolls > 0) tailPolls--;
            const run = current;
            if (run && !run.blocked && !run.terminalSeen) {
                const authoritativeStatus = run.status;
                // A recovery checkpoint is a new silent baseline, not an animation replay.
                const checkpoint = recoveryCursor(run.id);
                if (checkpoint !== undefined && checkpoint > run.cursor && previousId === run.id) {
                    run.cursor = checkpoint;
                    run.hydrating = true;
                }
                fetchingEvents = true;
                let hasMore = true;
                while (hasMore && valid()) {
                    const page = record(await get(`/api/runs/${encodeURIComponent(run.id)}/events?afterSeq=${run.cursor}&limit=500`, sid, controller));
                    if (!valid()) return;
                    if (!page || !Array.isArray(page.events)) throw new Error('Invalid run event page');
                    const events = page.events.map(record).filter((e): e is Record<string, unknown> => !!e
                        && e.runId === run.id && typeof e.seq === 'number' && Number.isInteger(e.seq)
                        && e.seq > run.cursor && typeof e.eventType === 'string' && typeof e.eventData === 'string')
                        .sort((a, b) => Number(a.seq) - Number(b.seq));
                    for (const entry of events) {
                        const event = entry as unknown as SpiderRunEvent;
                        if (event.seq <= run.cursor) continue;
                        const parsed = parseRunToolEvent(event);
                        if (parsed) tool(parsed.toolUseId, parsed.phase, run.hydrating, 'run');
                        if (event.eventType === 'run_status_changed') {
                            try {
                                const to = record(record(JSON.parse(event.eventData))?.data)?.to;
                                if (typeof to === 'string') run.status = to;
                            } catch { /* Ignore malformed display-only metadata. */ }
                        }
                        run.cursor = event.seq;
                    }
                    const next = typeof page.nextSeq === 'number' ? page.nextSeq : run.cursor;
                    hasMore = page.hasMore === true && events.length > 0 && next >= run.cursor;
                    // Do not trust a nextSeq that would silently skip events absent from this page.
                }
                if (!valid()) return;
                if (isTerminalRun(authoritativeStatus)) run.status = authoritativeStatus;
                refreshWaiting();
                const phase = runStatusPhase(run.status);
                if (isTerminalRun(run.status)) {
                    run.terminalSeen = true;
                    tailPolls = 0;
                    if (!run.hydrating && phase) signal(phase);
                } else if (run.status.toUpperCase() === 'CANCELLING' && !run.cancellingSeen) {
                    run.cancellingSeen = true;
                    signal('reset');
                }
                run.hydrating = false;
            }
            failures = 0;
        } catch (error) {
            if (!valid()) return;
            if (error instanceof ObservationError && [401, 403, 404].includes(error.status)) {
                if (fetchingEvents && current) current.blocked = true;
                else discoveryBlocked = true;
            } else failures = Math.min(failures + 1, 4);
            // No notifications or business-state changes: observation is optional decoration.
        } finally {
            // A previous generation must release its own in-flight lock before a new fetch starts.
            busy = false;
            if (request === controller) request = null;
            const immediate = generation !== stamp || pollAgain && failures === 0;
            pollAgain = false;
            if (!disposed) schedule(immediate ? 0 : Math.min(5000, POLL_MS * 2 ** failures));
        }
    }

    function resetProjection(nextSession: string | null, nextReady: boolean): void {
        generation++;
        request?.abort();
        clearTimer();
        clearPulse();
        sessionId = nextSession;
        ready = nextReady;
        waiting = false;
        current = null;
        initialized = false;
        discoveryBlocked = false;
        awaitingRunUntil = 0;
        tailPolls = 0;
        failures = 0;
        knownRoots.clear();
        toolState.clear();
        clearToolObservation();
        previousStatus = useSessionStore.getState().status;
        lastStreamingText = streamingStore.getSnapshot();
        lastThinkingText = useMessageStore.getState().thinkingContent;
        signal('reset', undefined, 'binding');
        observeTools(true);
    }

    function refreshContext(): void {
        if (disposed) return;
        const session = useSessionStore.getState();
        const connected = useBridgeStore.getState().bridgeStatus === 'connected';
        const nextReady = !!session.sessionId && connected && isSessionBindingReady(session.sessionId);
        const changed = session.sessionId !== sessionId || nextReady !== ready;
        if (changed) resetProjection(session.sessionId, nextReady);
        if (!connected && !disconnected) {
            disconnected = true;
            signal('disconnected');
        } else if (connected) disconnected = false;
        refreshWaiting();
        if (ready && session.status !== previousStatus) {
            if (session.status === 'idle') {
                // Local idle includes cancellation requests and errors; never call it successful.
                signal('reset');
                tailPolls = 2;
            } else if (previousStatus === 'idle') {
                awaitingRunUntil = Date.now() + 10_000;
                if (!waiting) signal('preparing');
            }
        }
        previousStatus = session.status;
        if (canObserve()) schedule(changed ? 0 : POLL_MS);
    }

    const unsubscribers = [
        useSessionStore.subscribe(refreshContext),
        useBridgeStore.subscribe(refreshContext),
        usePermissionStore.subscribe(refreshContext),
        subscribeSessionBinding(refreshContext),
        useMessageStore.subscribe((state, previous) => {
            if (!ready) return;
            if (state.activeToolCalls !== previous.activeToolCalls || state.messages !== previous.messages) {
                observeTools();
                schedule();
            }
            if (state.thinkingContent !== lastThinkingText) {
                lastThinkingText = state.thinkingContent;
                if (lastThinkingText) pulse();
            }
        }),
        streamingStore.subscribe(() => {
            const text = streamingStore.getSnapshot();
            if (text !== lastStreamingText) { lastStreamingText = text; if (text) pulse(); }
        }),
        useRunStore.subscribe((state) => {
            if (state.recoverySnapshots === lastRecoverySnapshots && state.recoveryEventSeq === lastRecoveryCursors) return;
            lastRecoverySnapshots = state.recoverySnapshots;
            lastRecoveryCursors = state.recoveryEventSeq;
            // bind recovery gates business projection; refreshContext will seed it when ready.
            if (ready) schedule(0);
        }),
    ];
    const visibility = () => {
        if (!visible()) { clearTimer(); clearPulse(); request?.abort(); }
        else schedule(0);
    };
    document.addEventListener('visibilitychange', visibility);
    refreshContext();

    return { dispose() {
        if (disposed) return;
        disposed = true;
        generation++;
        clearTimer();
        clearPulse();
        request?.abort();
        unsubscribers.forEach(unsubscribe => unsubscribe());
        document.removeEventListener('visibilitychange', visibility);
        toolState.clear();
        clearToolObservation();
    } };
}
