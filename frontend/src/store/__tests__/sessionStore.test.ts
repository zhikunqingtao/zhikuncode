import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSessionStore } from '../sessionStore';
import { useWorkbenchViewStore } from '../workbenchViewStore';
import { useTurnViewStore } from '../turnViewStore';

describe('SessionStore', () => {
    beforeEach(() => {
        window.sessionStorage.clear();
        useSessionStore.setState({
            sessionId: null,
            model: null,
            status: 'idle',
            turnCount: 0,
            isAborted: false,
        });
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('applies development and balanced only when a new candidate is activated, once', async () => {
        useWorkbenchViewStore.setState({ activeSessionId: 'old-chat', viewMode: 'simple', defaultView: 'simple' });
        useTurnViewStore.setState({ density: 'detailed', expandOverrides: { 'old-chat': { '0:0': true } } });
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: true, json: async () => ({ sessionId: 'new-defaults-chat' }),
        }));
        const candidate = await useSessionStore.getState().createSession('project', 'model');
        expect(useWorkbenchViewStore.getState().viewMode).toBe('simple');
        expect(useTurnViewStore.getState().density).toBe('detailed');
        await useSessionStore.getState().resumeSession(candidate);
        expect(useWorkbenchViewStore.getState().viewMode).toBe('development');
        expect(useTurnViewStore.getState().density).toBe('balanced');
        expect(useTurnViewStore.getState().expandOverrides['old-chat']).toEqual({ '0:0': true });
        useWorkbenchViewStore.getState().setViewMode('simple');
        useTurnViewStore.getState().setDensity('compact');
        await useSessionStore.getState().resumeSession(candidate);
        expect(useWorkbenchViewStore.getState().viewMode).toBe('simple');
        expect(useTurnViewStore.getState().density).toBe('compact');
    });

    it('should start with idle status', () => {
        const state = useSessionStore.getState();
        expect(state.status).toBe('idle');
        expect(state.sessionId).toBeNull();
        expect(state.model).toBeNull();
    });

    it('setModel updates model', () => {
        useSessionStore.getState().setModel('gpt-4o');
        expect(useSessionStore.getState().model).toBe('gpt-4o');
    });

    it('setStatus updates status', () => {
        useSessionStore.getState().setStatus('streaming');
        expect(useSessionStore.getState().status).toBe('streaming');
    });

    it('abort sets isAborted and status to idle', () => {
        useSessionStore.getState().setStatus('streaming');
        useSessionStore.getState().abort();
        
        const state = useSessionStore.getState();
        expect(state.isAborted).toBe(true);
        expect(state.status).toBe('idle');
    });

    it('resumeSession sets sessionId and idle status', async () => {
        await useSessionStore.getState().resumeSession('session-123');
        const state = useSessionStore.getState();
        expect(state.sessionId).toBe('session-123');
        expect(state.status).toBe('idle');
        expect(window.sessionStorage.getItem('zhikuncode.activeSessionId')).toBe('session-123');
    });

    it('createSession requests the AUTO_APPROVE permission mode by default', async () => {
        const fetchMock = vi.fn().mockResolvedValue({
            ok: true,
            status: 201,
            json: async () => ({ sessionId: 'session-created' }),
        });
        vi.stubGlobal('fetch', fetchMock);

        const candidate = await useSessionStore.getState()
            .createSession('project-1', 'gpt-4o');

        expect(fetchMock).toHaveBeenCalledWith('/api/sessions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                projectId: 'project-1',
                model: 'gpt-4o',
                permissionMode: 'AUTO_APPROVE',
            }),
        });
        expect(candidate).toBe('session-created');
        expect(useSessionStore.getState().sessionId).toBeNull();
        expect(window.sessionStorage.getItem(
            'zhikuncode.activeSessionId'))
            .toBeNull();
    });

    it('createSession rejects a missing Project before sending a request', async () => {
        const fetchMock = vi.fn();
        vi.stubGlobal('fetch', fetchMock);

        await expect(useSessionStore.getState()
            .createSession('  ', 'gpt-4o'))
            .rejects.toThrow('必须选择已授权的 Project');

        expect(fetchMock).not.toHaveBeenCalled();
        expect(useSessionStore.getState().sessionId).toBeNull();
    });

    it('createSession failure preserves the previous Session state', async () => {
        window.sessionStorage.setItem(
            'zhikuncode.activeSessionId',
            'session-existing',
        );
        useSessionStore.setState({
            sessionId: 'session-existing',
            model: 'model-existing',
            status: 'waiting_permission',
            turnCount: 7,
            isAborted: true,
        });
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: false,
            status: 403,
        }));

        await expect(useSessionStore.getState()
            .createSession('project-denied', 'gpt-4o'))
            .rejects.toThrow('HTTP 403');

        expect(useSessionStore.getState()).toMatchObject({
            sessionId: 'session-existing',
            model: 'model-existing',
            status: 'waiting_permission',
            turnCount: 7,
            isAborted: true,
        });
        expect(window.sessionStorage.getItem(
            'zhikuncode.activeSessionId')).toBe('session-existing');
    });

    it('resumeSession with an empty id clears the persisted session', async () => {
        window.sessionStorage.setItem('zhikuncode.activeSessionId', 'session-old');

        await useSessionStore.getState().resumeSession('');

        expect(useSessionStore.getState().sessionId).toBe('');
        expect(window.sessionStorage.getItem('zhikuncode.activeSessionId')).toBeNull();
    });

    it('restores the active session when the store module is reloaded', async () => {
        window.sessionStorage.setItem('zhikuncode.activeSessionId', 'session-restored');
        vi.resetModules();

        const { useSessionStore: restoredStore } = await import('../sessionStore');

        expect(restoredStore.getState().sessionId).toBe('session-restored');
    });
});
