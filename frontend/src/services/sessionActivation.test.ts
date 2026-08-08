import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    dispatch,
    isSessionBound,
    resetBoundSession,
} from '@/api/dispatch';
import {
    isWsConnected,
    sendToServer,
    waitForWsConnection,
} from '@/api/stompClient';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { useCostStore } from '@/store/costStore';
import type { Message } from '@/types';
import {
    activateSessionCandidate,
    getPendingSessionActivation,
} from './sessionActivation';

vi.mock('@/api/stompClient', () => ({
    isWsConnected: vi.fn(() => true),
    sendToServer: vi.fn(() => true),
    waitForWsConnection: vi.fn(() => Promise.resolve()),
}));

interface BindPayload {
    sessionId: string;
    protocolVersion: number;
    bindRequestId: string;
    bindingEpoch: number;
}

const oldMessage: Message = {
    uuid: 'old-message',
    type: 'user',
    content: [{ type: 'text', text: 'keep old state' }],
    timestamp: 1,
};

function restore(payload: BindPayload, messages: Message[] = []): void {
    dispatch({
        type: 'session_restored',
        protocolVersion: 3,
        bindRequestId: payload.bindRequestId,
        bindingEpoch: payload.bindingEpoch,
        messages,
        metadata: {
            sessionId: payload.sessionId,
            model: 'test-model',
            status: 'idle',
        },
    } as never);
}

describe('Session activation transaction', () => {
    beforeEach(async () => {
        vi.useFakeTimers();
        vi.mocked(isWsConnected).mockReturnValue(true);
        vi.mocked(sendToServer).mockReset();
        vi.mocked(waitForWsConnection).mockReset();
        vi.mocked(waitForWsConnection).mockResolvedValue();
        resetBoundSession();
        window.sessionStorage.clear();
        useMessageStore.getState().clearMessages();
        useCostStore.getState().resetSessionCost();
        useSessionStore.setState({
            sessionId: null,
            model: null,
            status: 'idle',
        });
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: true,
            json: async () => [],
        }));
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.unstubAllGlobals();
        vi.clearAllMocks();
    });

    it('keeps the old Session until a timed-out switch is safely rebound', async () => {
        await useSessionStore.getState().resumeSession('session-old');
        useMessageStore.getState().addMessage(oldMessage);
        const binds: BindPayload[] = [];
        vi.mocked(sendToServer).mockImplementation((_destination, body) => {
            const payload = body as BindPayload;
            binds.push(payload);
            if (payload.sessionId === 'session-old') {
                restore(payload, [oldMessage]);
            }
            return true;
        });

        const activation = activateSessionCandidate('session-new', {
            bindTimeoutMs: 50,
        });

        expect(useSessionStore.getState().sessionId).toBe('session-old');
        expect(useMessageStore.getState().messages).toEqual([oldMessage]);
        expect(window.sessionStorage.getItem('zhikuncode.activeSessionId'))
            .toBe('session-old');

        await vi.advanceTimersByTimeAsync(50);
        await expect(activation).resolves.toMatchObject({ status: 'failed' });
        expect(binds).toHaveLength(2);
        expect(binds[1].sessionId).toBe('session-old');
        expect(binds[1].bindingEpoch).toBeGreaterThan(
            binds[0].bindingEpoch,
        );
        expect(useSessionStore.getState().sessionId).toBe('session-old');
        expect(useMessageStore.getState().messages).toEqual([oldMessage]);
        expect(isSessionBound('session-old')).toBe(true);

        // A restore from the timed-out candidate is no longer authoritative.
        restore(binds[0], [{ ...oldMessage, uuid: 'late-new-state' }]);
        expect(useSessionStore.getState().sessionId).toBe('session-old');
        expect(useMessageStore.getState().messages).toEqual([oldMessage]);
    });

    it('keeps the latest selection pending beyond the old connection timeout', async () => {
        let connect: (() => void) | undefined;
        vi.mocked(isWsConnected).mockReturnValue(false);
        vi.mocked(waitForWsConnection).mockImplementation(() =>
            new Promise<void>(resolve => { connect = resolve; }));
        vi.mocked(sendToServer).mockImplementation((_destination, body) => {
            restore(body as BindPayload, []);
            return true;
        });

        const activation = activateSessionCandidate('session-delayed');
        await vi.advanceTimersByTimeAsync(5_000);

        expect(sendToServer).not.toHaveBeenCalled();
        expect(getPendingSessionActivation()).toBe(activation);
        expect(useSessionStore.getState().sessionId).toBeNull();

        vi.mocked(isWsConnected).mockReturnValue(true);
        connect?.();
        await expect(activation).resolves.toEqual({
            status: 'activated',
            sessionId: 'session-delayed',
        });
        expect(sendToServer).toHaveBeenCalledTimes(1);
        expect(useSessionStore.getState().sessionId)
            .toBe('session-delayed');
    });

    it('cancels a disconnected selection when a newer selection wins', async () => {
        const waits: Array<{
            resolve: () => void;
            reject: (error: Error) => void;
        }> = [];
        vi.mocked(isWsConnected).mockReturnValue(false);
        vi.mocked(waitForWsConnection).mockImplementation(signal =>
            new Promise<void>((resolve, reject) => {
                waits.push({ resolve, reject });
                signal?.addEventListener('abort', () => {
                    const error = new Error('superseded');
                    error.name = 'AbortError';
                    reject(error);
                }, { once: true });
            }));
        vi.mocked(sendToServer).mockImplementation((_destination, body) => {
            restore(body as BindPayload, []);
            return true;
        });

        const first = activateSessionCandidate('session-a');
        await Promise.resolve();
        const second = activateSessionCandidate('session-b');

        await expect(first).resolves.toEqual({
            status: 'superseded',
            sessionId: 'session-a',
        });
        expect(waits).toHaveLength(2);

        vi.mocked(isWsConnected).mockReturnValue(true);
        waits[1].resolve();
        await expect(second).resolves.toEqual({
            status: 'activated',
            sessionId: 'session-b',
        });
        expect(sendToServer).toHaveBeenCalledTimes(1);
        expect(useSessionStore.getState().sessionId).toBe('session-b');
    });

    it('lets a newer switch win over an older in-flight result', async () => {
        await useSessionStore.getState().resumeSession('session-old');
        useMessageStore.getState().addMessage(oldMessage);
        const binds: BindPayload[] = [];
        vi.mocked(sendToServer).mockImplementation((_destination, body) => {
            const payload = body as BindPayload;
            binds.push(payload);
            if (payload.sessionId === 'session-b') {
                restore(payload, []);
            }
            return true;
        });

        const first = activateSessionCandidate('session-a');
        await Promise.resolve();
        const second = activateSessionCandidate('session-b');

        await expect(first).resolves.toEqual({
            status: 'superseded',
            sessionId: 'session-a',
        });
        await expect(second).resolves.toEqual({
            status: 'activated',
            sessionId: 'session-b',
        });
        expect(useSessionStore.getState().sessionId).toBe('session-b');
        expect(isSessionBound('session-b')).toBe(true);

        const stale = binds.find(bind => bind.sessionId === 'session-a');
        expect(stale).toBeDefined();
        restore(stale!, [oldMessage]);
        dispatch({
            type: 'protocol_error',
            code: 'SESSION_NOT_FOUND',
            supportedVersion: 3,
            bindRequestId: stale!.bindRequestId,
            bindingEpoch: stale!.bindingEpoch,
        } as never);
        expect(useSessionStore.getState().sessionId).toBe('session-b');
        expect(useMessageStore.getState().messages).toEqual([]);
    });

    it('lets a send path await the candidate already being activated', async () => {
        await useSessionStore.getState().resumeSession('session-old');
        let candidateBind: BindPayload | undefined;
        vi.mocked(sendToServer).mockImplementation((_destination, body) => {
            candidateBind = body as BindPayload;
            return true;
        });

        const switching = activateSessionCandidate('session-new');
        await Promise.resolve();
        const sendReadiness = getPendingSessionActivation();

        expect(sendReadiness).toBe(switching);
        expect(candidateBind?.sessionId).toBe('session-new');
        restore(candidateBind!, []);
        await expect(sendReadiness).resolves.toEqual({
            status: 'activated',
            sessionId: 'session-new',
        });
        expect(vi.mocked(sendToServer)).toHaveBeenCalledTimes(1);
        expect(useSessionStore.getState().sessionId).toBe('session-new');
    });

    it('accepts a matching restore even when interaction recovery is slow', async () => {
        vi.mocked(fetch).mockImplementation(() => new Promise(() => {}));
        vi.mocked(sendToServer).mockImplementation((_destination, body) => {
            restore(body as BindPayload, []);
            return true;
        });

        const activation = activateSessionCandidate('session-restored', {
            bindTimeoutMs: 50,
        });
        await Promise.resolve();
        dispatch({
            type: 'cost_update',
            sessionCost: 7,
            totalCost: 9,
            usage: {
                inputTokens: 1,
                outputTokens: 2,
                cacheReadInputTokens: 0,
                cacheCreationInputTokens: 0,
            },
        } as never);
        await vi.advanceTimersByTimeAsync(50);

        await expect(activation).resolves.toEqual({
            status: 'activated',
            sessionId: 'session-restored',
        });
        expect(useSessionStore.getState().sessionId)
            .toBe('session-restored');
        expect(isSessionBound('session-restored')).toBe(true);
        expect(useCostStore.getState().sessionCost).toBe(7);
    });
});
