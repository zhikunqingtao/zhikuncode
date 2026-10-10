import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { streamingStore } from '@/hooks/useStreamingText';
import { useSessionStore } from '@/store/sessionStore';
import { useMessageStore } from '@/store/messageStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useAppUiStore } from '@/store/appUiStore';
import type { VisualSignal } from '../runtime/types';
import { collectAnchors } from './anchors';
import { createGalaxyController } from './controller';
import type { ToolCallState } from '@/types';
const mocks = vi.hoisted(() => ({ emit: undefined as undefined | ((s: VisualSignal) => void), stop: vi.fn(), scene: Object.fromEntries(['setPhase', 'setPaused', 'setFocus', 'setSession', 'setFileState', 'travel', 'setReadingZones', 'setStageBounds', 'setAnchors', 'setActiveAnchor', 'warp', 'resize', 'dispose'].map(k => [k, vi.fn()])), dust: Object.fromEntries(['start', 'emitRange', 'pulseDiff', 'update', 'clear', 'dispose'].map(k => [k, vi.fn()])), fail: false }));
vi.mock('@/api/dispatch', () => ({ isSessionBindingReady: () => true }));
vi.mock('../runtime/observer', () => ({ createVisualRuntimeObserver: (emit: (s: VisualSignal) => void) => { mocks.emit = emit; emit({ phase: 'reset', resetScope: 'binding', sessionId: 's', at: 0 }); return { dispose: mocks.stop }; } }));
vi.mock('./scene', () => ({ createGalaxy: (_canvas: unknown, options: {
        onError: (e: Error) => void;
    }) => { if (mocks.fail)
        options.onError(new Error('context lost')); return mocks.scene; } }));
const glow = vi.hoisted(() => ({ geometry: vi.fn(), launch: vi.fn(), update: vi.fn(), clear: vi.fn(), dispose: vi.fn() }));
vi.mock('./taskGlow', () => ({ createTaskGlow: () => glow }));
vi.mock('./textDust', () => ({ createTextDust: () => mocks.dust }));
vi.mock('./anchors', async (importOriginal) => ({ ...await importOriginal<typeof import('./anchors')>(), collectAnchors: vi.fn(() => ({ anchors: [{ id: 'tool:t', x: .3, y: .4, kind: 'tool' }, { id: 'input', x: .2, y: .8, kind: 'input' }, { id: 'reply', x: .4, y: .5, kind: 'result' }], zones: [], stage: null })) }));
let stop: (() => void) | undefined, host: HTMLDivElement, frame: FrameRequestCallback | undefined;
const emit = (phase: VisualSignal['phase'], toolUseId?: string) => mocks.emit!({ phase, toolUseId, sessionId: 's', at: 0 });
const call = (result?: ToolCallState['result']): ToolCallState => ({ toolName: 'Read', input: { file_path: 'a.ts' }, status: result ? 'completed' : 'running', startTime: 0, result });
beforeEach(() => {
    vi.clearAllMocks();
    streamingStore.clear();
    mocks.fail = false;
    frame = undefined;
    vi.stubGlobal('requestAnimationFrame', vi.fn((cb: FrameRequestCallback) => { frame = cb; return 1; }));
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
    vi.stubGlobal('ResizeObserver', class {
        observe() { }
        disconnect() { }
    });
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    useSessionStore.setState({ sessionId: 's', status: 'streaming' });
    useBridgeStore.setState({ bridgeStatus: 'connected' });
    useMessageStore.setState({ messages: [], streamingMessageId: null, activeToolCalls: new Map([['t', call()]]) });
    usePermissionStore.setState({ pendingPermissions: [] });
    useAppUiStore.setState({ elicitationDialog: null });
    host = document.createElement('div');
    document.body.append(host);
    stop = createGalaxyController(host);
});
afterEach(() => { stop?.(); host.remove(); document.querySelector('.chat-workspace')?.remove(); vi.unstubAllGlobals(); });
describe('galaxy controller protects business state', () => {
    it('updates reply identity even when only the streaming message id changes', () => {
        useMessageStore.setState({ streamingMessageId: 'live-reply' });
        emit('executing', 't');
        frame?.(performance.now() + 100);
        expect(collectAnchors).toHaveBeenLastCalledWith(expect.anything(), { replyMessageId: 'live-reply', activeToolId: 't' });
    });
    it('keeps docking light above cards and removes its portal on exit', () => {
        expect(document.querySelector('.galaxy-foreground')?.parentElement).toBe(document.body);
        emit('executing', 't');
        emit('complete');
        frame?.(performance.now() + 100);
        expect(glow.launch).toHaveBeenCalledWith(expect.objectContaining({ targetId: 'tool:t' }));
        stop!();
        expect(document.querySelector('.galaxy-foreground')).toBeNull();
        expect(glow.dispose).toHaveBeenCalledTimes(1);
    });
    it('preparation never changes a file to reading; execution follows the real tool signal', () => {
        emit('preparing', 't');
        expect(mocks.scene.setPhase).toHaveBeenLastCalledWith('search');
        expect(mocks.scene.setFileState).not.toHaveBeenCalledWith('s|a.ts', 'reading');
        emit('executing', 't');
        expect(mocks.scene.setFileState).toHaveBeenCalledWith('s|a.ts', 'reading');
        frame?.(performance.now() + 100);
        expect(mocks.scene.travel).toHaveBeenCalledWith(expect.objectContaining({ fileId: 's|a.ts', targetId: 'tool:t', kind: 'read' }));
        expect(useSessionStore.getState().status).toBe('streaming');
        expect(useMessageStore.getState().activeToolCalls.get('t')?.status).toBe('running');
    });
    it('keeps elicitation holding while other tools finish, and terminal Runs cannot restart', () => {
        useAppUiStore.setState({ elicitationDialog: { requestId: 'q', question: 'confirm', options: [], multiSelect: false } });
        emit('succeeded', 't');
        expect(mocks.scene.setPhase).toHaveBeenLastCalledWith('approval');
        useAppUiStore.setState({ elicitationDialog: null });
        emit('complete');
        emit('executing', 't');
        expect(mocks.scene.setPhase).toHaveBeenLastCalledWith('complete');
    });
    it.each(['failed', 'cancelled', 'disconnected'] as const)('%s clears queued decorations without changing stores', phase => {
        emit('executing', 't');
        emit(phase);
        frame?.(performance.now() + 100);
        expect(mocks.scene.travel).not.toHaveBeenCalled();
        expect(mocks.dust.clear).toHaveBeenCalled();
        expect(useMessageStore.getState().activeToolCalls.size).toBe(1);
    });
    it('binding reset drops old file scope, clears glyphs and does not replay past actions', () => {
        emit('executing', 't');
        useMessageStore.setState({ messages: [], activeToolCalls: new Map() });
        mocks.emit!({ phase: 'reset', resetScope: 'binding', sessionId: 'new', at: 1 });
        frame?.(performance.now() + 100);
        expect(mocks.scene.travel).not.toHaveBeenCalled();
        expect(mocks.scene.setSession).toHaveBeenLastCalledWith('new', []);
        expect(mocks.dust.clear).toHaveBeenCalled();
    });
    it('disposes exactly once and detached observers cannot restart graphics', () => {
        stop!();
        stop!();
        emit('executing', 't');
        expect(mocks.scene.dispose).toHaveBeenCalledTimes(1);
        expect(mocks.dust.dispose).toHaveBeenCalledTimes(1);
        expect(mocks.stop).toHaveBeenCalledTimes(1);
        expect(host.children.length).toBe(0);
        expect(mocks.scene.travel).not.toHaveBeenCalled();
    });
    it('animates only appended rendered streaming text, not restored history or final commits', async () => {
        stop!();
        const workspace = document.createElement('div');
        workspace.className = 'chat-workspace';
        workspace.innerHTML = '<div data-galaxy-text data-galaxy-streaming="true"><p>你好<strong>星河🌌</strong></p></div>';
        document.body.append(workspace);
        stop = createGalaxyController(host);
        streamingStore.append('some streamed text');
        emit('streaming');
        frame?.(performance.now() + 100);
        expect(mocks.dust.emitRange).not.toHaveBeenCalled();
        const el = workspace.querySelector<HTMLElement>('[data-galaxy-text]')!, before = el.textContent!.length;
        el.querySelector('p')!.append('，继续');
        await Promise.resolve();
        frame?.(performance.now() + 200);
        expect(mocks.dust.emitRange).toHaveBeenCalledWith(el, before, before + 3);
        mocks.dust.emitRange.mockClear();
        delete el.dataset.galaxyStreaming;
        await Promise.resolve();
        frame?.(performance.now() + 300);
        expect(mocks.dust.emitRange).not.toHaveBeenCalled();
    });
    it('observes streaming text after the chat workspace is replaced', async () => {
        const old = document.createElement('div');
        old.className = 'chat-workspace';
        document.body.append(old);
        stop!();
        stop = createGalaxyController(host);
        const replacement = old.cloneNode() as HTMLElement;
        replacement.innerHTML = '<div data-galaxy-text data-galaxy-streaming="true">开始</div>';
        old.replaceWith(replacement);
        streamingStore.append('开始');
        emit('streaming');
        await Promise.resolve();
        frame?.(performance.now() + 100);
        const el = replacement.firstElementChild as HTMLElement;
        el.append('继续');
        await Promise.resolve();
        frame?.(performance.now() + 200);
        expect(mocks.dust.emitRange).toHaveBeenCalledWith(el, 2, 4);
    });
    it('pauses hidden drawing and establishes a silent text baseline on return', () => {
        Object.defineProperty(document, 'hidden', { value: true, configurable: true });
        document.dispatchEvent(new Event('visibilitychange'));
        expect(mocks.scene.setPaused).toHaveBeenLastCalledWith(true);
        Object.defineProperty(document, 'hidden', { value: false, configurable: true });
        document.dispatchEvent(new Event('visibilitychange'));
        expect(mocks.scene.setPaused).toHaveBeenLastCalledWith(false);
        expect(mocks.dust.clear).toHaveBeenCalled();
    });
    it('contains construction failure without starting observation', () => {
        stop!();
        vi.clearAllMocks();
        mocks.fail = true;
        stop = createGalaxyController(host);
        expect(host.dataset.state).toBe('fallback');
        expect(mocks.scene.dispose).toHaveBeenCalledTimes(1);
        expect(host.children.length).toBe(0);
        expect(useSessionStore.getState().status).toBe('streaming');
    });
});
