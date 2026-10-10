import { isSessionBindingReady } from '@/api/dispatch';
import { usePermissionStore } from '@/store/permissionStore';
import { useSessionStore } from '@/store/sessionStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { useMessageStore } from '@/store/messageStore';
import { useAppUiStore } from '@/store/appUiStore';
import { streamingStore } from '@/hooks/useStreamingText';
import { createVisualRuntimeObserver } from '../runtime/observer';
import type { VisualSignal } from '../runtime/types';
import { createGalaxy, type GalaxyPhase } from './scene';
import { createTextDust } from './textDust';
import { createTaskGlow } from './taskGlow';
import { createToolProjection, projectFiles, currentReplyMessageId, type ToolView } from './projection';
import { appendedText, collectAnchors } from './anchors';
/** Owns decoration only. All business subscriptions are read-only and individually disposable. */
export function createGalaxyController(host: HTMLElement): () => void {
    const cleanups: (() => void)[] = [];
    let disposed = false, failed = false, raf = 0;
    function dispose() {
        if (disposed)
            return;
        disposed = true;
        cancelAnimationFrame(raf);
        for (const cleanup of cleanups.reverse()) {
            try {
                cleanup();
            }
            catch { /* Continue releasing the other owners. */ }
        }
        host.replaceChildren();
    }
    try {
        const canvas = document.createElement('canvas');
        canvas.className = 'galaxy-scene';
        canvas.id = 'galaxy-canvas';
        host.append(canvas);
        const scene = createGalaxy(canvas, {
            onReady: () => { host.dataset.state = 'ready'; },
            onError: () => { failed = true; host.dataset.state = 'fallback'; queueMicrotask(dispose); },
        });
        cleanups.push(scene.dispose);
        if (failed) {
            dispose();
            return dispose;
        }
        const foreground = document.createElement('div');
        foreground.className = 'galaxy-foreground';
        foreground.setAttribute('aria-hidden', 'true');
        document.body.append(foreground);
        cleanups.push(() => foreground.remove());
        const glow = createTaskGlow(foreground, () => ({ x: Number(canvas.dataset.coreX), y: Number(canvas.dataset.coreY) }));
        cleanups.push(glow.dispose);
        const dust = createTextDust({ host: foreground, getOrigin: () => ({ x: Number(canvas.dataset.coreX), y: Number(canvas.dataset.coreY) }) });
        cleanups.push(dust.dispose);
        const projection = createToolProjection();
        let session = '', activeTool: string | undefined, tools = new Map<string, ToolView>();
        let terminal = false;
        let replyMessageId: string | null = null;
        let phase: GalaxyPhase = 'idle', dirty = true, textDirty = true, silent = true;
        let previousMessages: unknown, previousCalls: unknown;
        let texts = new WeakMap<HTMLElement, string>(), dustTarget: HTMLElement | null = null;
        type PendingFlight = {
            targetId: string;
            fileId?: string;
            kind: 'launch' | 'read' | 'edit' | 'compose';
            expires: number;
        };
        let pending: PendingFlight | null = null;
        let glowPending: PendingFlight | null = null;
        const diffs = new Map<string, number>();
        const pointer = { x: -10000, y: -10000 };
        let geometry: ReturnType<typeof collectAnchors> | null = null, geometryAt = 0, flightUntil = 0, last = performance.now();
        let lastCompose = 0;
        const ready = () => Boolean(session && isSessionBindingReady(session) && useBridgeStore.getState().bridgeStatus === 'connected');
        const setPhase = (next: GalaxyPhase) => { phase = next; scene.setPhase(next); };
        const invalidate = () => { dirty = true; };
        const updateFiles = () => {
            const files = projectFiles(session, tools.values(), activeTool);
            scene.setSession(session, files);
            for (const file of files)
                scene.setFileState(file.id, file.state);
            const active = tools.get(activeTool ?? '');
            if (active && !active.result && (phase === 'read' || phase === 'edit'))
                for (const path of active.paths)
                    scene.setFileState(`${session}|${path}`, 'reading');
        };
        function refreshTools(force = false) {
            if (!ready())
                return;
            const state = useMessageStore.getState();
            replyMessageId = currentReplyMessageId(state);
            if (!force && state.messages === previousMessages && state.activeToolCalls === previousCalls)
                return;
            previousMessages = state.messages;
            previousCalls = state.activeToolCalls;
            tools = projection.read(state);
            updateFiles();
            dirty = true;
        }
        function enqueue(targetId: string, kind: 'launch' | 'read' | 'edit' | 'compose', tool?: ToolView) {
            const path = tool?.paths[0];
            pending = { targetId, kind, ...(path ? { fileId: `${session}|${path}` } : {}), expires: performance.now() + 4200 };
            glowPending = pending;
            dirty = true;
        }
        function signal(event: VisualSignal) {
            if (disposed || failed)
                return;
            const next = event.sessionId ?? '';
            if (event.resetScope === 'binding' || session !== next) {
                session = next;
                terminal = false;
                replyMessageId = null;
                activeTool = undefined;
                pending = null;
                flightUntil = 0;
                diffs.clear();
                projection.reset();
                tools.clear();
                previousMessages = previousCalls = undefined;
                texts = new WeakMap();
                dustTarget = null;
                dust.clear();
                glow.clear();
                glowPending = null;
                silent = true;
                textDirty = dirty = true;
                scene.setSession('', []);
                scene.setSession(session, []);
                setPhase('idle');
                refreshTools(true);
                return;
            }
            if (event.phase === 'disconnected') {
                pending = null;
                diffs.clear();
                dust.clear();
                glow.clear();
                glowPending = null;
                setPhase('cancel');
                return;
            }
            if (!ready())
                return;
            refreshTools();
            dirty = true;
            const tool = tools.get(event.toolUseId ?? '');
            if (tool) {
                activeTool = tool.id;
            }
            if (event.toolUseId && (terminal || usePermissionStore.getState().pendingPermissions.length > 0 || useAppUiStore.getState().elicitationDialog || useSessionStore.getState().status === 'waiting_permission')) {
                updateFiles();
                return;
            }
            if (event.phase === 'waiting') {
                pending = null;
                dust.clear();
                glow.clear();
                glowPending = null;
                setPhase('approval');
                return;
            }
            if (event.phase === 'cancelled' || event.phase === 'failed') {
                if (!event.toolUseId)
                    terminal = true;
                pending = null;
                diffs.clear();
                dust.clear();
                glow.clear();
                glowPending = null;
                setPhase(event.phase === 'failed' ? 'error' : 'cancel');
                return;
            }
            if (event.phase === 'reset') {
                pending = null;
                diffs.clear();
                dust.clear();
                glow.clear();
                glowPending = null;
                setPhase('cancel');
                return;
            }
            if (event.phase === 'complete') {
                terminal = true;
                setPhase('complete');
                scene.setActiveAnchor('reply');
                return;
            }
            if (phase === 'approval' && (useAppUiStore.getState().elicitationDialog || usePermissionStore.getState().pendingPermissions.length > 0 || useSessionStore.getState().status === 'waiting_permission'))
                return;
            if (terminal && event.phase === 'streaming')
                return;
            if (event.phase === 'preparing') {
                if (!event.toolUseId) {
                    terminal = false;
                    setPhase('launch');
                    scene.warp();
                    scene.setActiveAnchor('input');
                }
                else {
                    setPhase('search');
                    scene.setActiveAnchor(`tool:${event.toolUseId}`);
                    if (tool?.paths.length && phase !== 'approval' && performance.now() >= flightUntil)
                        enqueue('input', 'launch', tool);
                }
            }
            else if (event.phase === 'executing') {
                setPhase(tool?.kind === 'edit' ? 'edit' : tool?.kind === 'search' ? 'search' : 'read');
                scene.setActiveAnchor(`tool:${event.toolUseId}`);
                enqueue(`tool:${event.toolUseId}`, tool?.kind === 'edit' ? 'edit' : 'read', tool);
            }
            else if (event.phase === 'succeeded') {
                // A tool result is not Run success. Never replay a late execution start.
                setPhase(tool?.kind === 'edit' ? 'edit' : 'read');
                scene.setActiveAnchor(`tool:${event.toolUseId}`);
                glowPending = { targetId: `tool:${event.toolUseId}`, kind: tool?.kind === 'edit' ? 'edit' : 'read', expires: performance.now() + 4200 };
                if (tool?.actualDiff)
                    diffs.set(tool.id, performance.now() + 1800);
                if (performance.now() >= flightUntil)
                    enqueue(`tool:${event.toolUseId}`, tool?.kind === 'edit' ? 'edit' : 'read', tool);
            }
            else if (event.phase === 'streaming' && streamingStore.getSnapshot()) {
                setPhase('compose');
                scene.setActiveAnchor('reply');
                if (performance.now() - lastCompose > 4500) {
                    enqueue('reply', 'compose', tools.get(activeTool ?? ''));
                    lastCompose = performance.now();
                }
            }
            updateFiles();
        }
        const observer = createVisualRuntimeObserver(signal);
        cleanups.push(observer.dispose);
        cleanups.push(useMessageStore.subscribe((state, previous) => {
            try {
                if (state.messages !== previous.messages || state.activeToolCalls !== previous.activeToolCalls || state.streamingMessageId !== previous.streamingMessageId) {
                    refreshTools();
                    dirty = true;
                }
            }
            catch { /* Optional visualization must not break a store notification. */ }
        }));
        cleanups.push(useAppUiStore.subscribe((state, previous) => {
            if (state.elicitationDialog === previous.elicitationDialog)
                return;
            if (state.elicitationDialog) {
                pending = null;
                dust.clear();
                glow.clear();
                glowPending = null;
                setPhase('approval');
            }
            else if (phase === 'approval') {
                setPhase('search');
            }
        }));
        const onMutation = () => { dirty = true; textDirty = true; };
        const mutations = new MutationObserver(onMutation);
        // The home view can be replaced when entering a session; observe a stable owner.
        const workspace = document.getElementById('root') ?? document.body;
        if (workspace)
            mutations.observe(workspace, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['class', 'style', 'open', 'hidden', 'data-tool-use-id', 'data-galaxy-streaming', 'data-galaxy-tool-ids', 'data-galaxy-message-id', 'data-message-uuid'] });
        // Dialogs are portal children. Canvas diagnostics are excluded by the attribute filter.
        const portals = new MutationObserver(invalidate);
        portals.observe(document.body, { childList: true });
        cleanups.push(() => mutations.disconnect(), () => portals.disconnect());
        const resize = new ResizeObserver(() => { dirty = true; });
        for (const el of document.querySelectorAll('.chat-workspace,.app-sidebar,.chat-composer-dock'))
            resize.observe(el);
        cleanups.push(() => resize.disconnect());
        const onResize = () => { scene.resize(); dirty = true; dust.clear(); glow.clear(); };
        const onPointer = (event: PointerEvent) => { pointer.x = event.clientX; pointer.y = event.clientY; dirty = true; };
        const visibility = () => {
            scene.setPaused(document.hidden);
            dust.clear();
            glow.clear();
            glowPending = null;
            pending = null;
            texts = new WeakMap();
            silent = true;
            textDirty = dirty = true;
            cancelAnimationFrame(raf);
            if (!document.hidden && !disposed) {
                last = performance.now();
                raf = requestAnimationFrame(frame);
            }
        };
        window.addEventListener('resize', onResize);
        window.addEventListener('pointermove', onPointer, { passive: true });
        document.addEventListener('scroll', invalidate, true);
        document.addEventListener('selectionchange', invalidate);
        document.addEventListener('visibilitychange', visibility);
        window.visualViewport?.addEventListener('resize', onResize);
        cleanups.push(() => {
            window.removeEventListener('resize', onResize);
            window.removeEventListener('pointermove', onPointer);
            document.removeEventListener('scroll', invalidate, true);
            document.removeEventListener('selectionchange', invalidate);
            document.removeEventListener('visibilitychange', visibility);
            window.visualViewport?.removeEventListener('resize', onResize);
        });
        function frame(now: number) {
            if (disposed || document.hidden)
                return;
            try {
                if (dirty && now - geometryAt >= 80) {
                    geometry = collectAnchors(pointer, { replyMessageId, activeToolId: activeTool });
                    scene.setAnchors(geometry.anchors);
                    glow.geometry(geometry.anchors);
                    scene.setReadingZones(geometry.zones);
                    geometryAt = now;
                    dirty = false;
                }
                if (pending) {
                    if (pending.expires < now || ['idle', 'cancel', 'error', 'approval'].includes(phase))
                        pending = null;
                    else if (now >= flightUntil && geometry?.anchors.some(a => a.id === pending!.targetId)) {
                        scene.travel(pending);
                        flightUntil = now + 3350;
                        pending = null;
                    }
                }
                if (glowPending) {
                    if (glowPending.expires < now) glowPending = null;
                    else if (geometry?.anchors.some(a => a.id === glowPending!.targetId)) {
                        glow.launch(glowPending);
                        glowPending = null;
                    }
                }
                glow.update((now - last) / 1000, phase);
                if (textDirty) {
                    textDirty = false;
                    for (const el of document.querySelectorAll<HTMLElement>('[data-galaxy-text]')) {
                        const current = el.textContent ?? '', delta = appendedText(texts.get(el), current);
                        texts.set(el, current);
                        if (!silent && delta && el.dataset.galaxyStreaming === 'true' && phase === 'compose' && ready()) {
                            if (dustTarget !== el) {
                                dust.start('', el);
                                dustTarget = el;
                            }
                            dust.emitRange(el, delta.start, delta.end);
                        }
                    }
                    silent = false;
                }
                for (const [id, expires] of diffs) {
                    if (expires < now) {
                        diffs.delete(id);
                        continue;
                    }
                    const rows = [...document.querySelectorAll<HTMLElement>('[data-galaxy-diff]')].filter(el => el.closest<HTMLElement>('[data-tool-use-id]')?.dataset.toolUseId === id);
                    if (rows.length) {
                        dust.pulseDiff(rows);
                        diffs.delete(id);
                    }
                }
                dust.update((now - last) / 1000);
                last = now;
            }
            catch {
                failed = true;
                host.dataset.state = 'fallback';
                dispose();
                return;
            }
            raf = requestAnimationFrame(frame);
        }
        if (document.hidden)
            scene.setPaused(true);
        else
            raf = requestAnimationFrame(frame);
        return dispose;
    }
    catch (error) {
        dispose();
        throw error;
    }
}
