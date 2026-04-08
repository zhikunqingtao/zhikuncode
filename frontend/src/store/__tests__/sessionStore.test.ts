import { describe, it, expect, beforeEach } from 'vitest';
import { useSessionStore } from '../sessionStore';

describe('SessionStore', () => {
    beforeEach(() => {
        useSessionStore.setState({
            sessionId: null,
            model: null,
            status: 'idle',
            turnCount: 0,
            effortValue: 3,
            isAborted: false,
        });
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

    it('setEffort updates effort value', () => {
        useSessionStore.getState().setEffort(5);
        expect(useSessionStore.getState().effortValue).toBe(5);
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
    });
});
