import {
    bindSessionAndWait,
    isSessionBound,
    resetBoundSession,
} from '@/api/dispatch';
import {
    isWsConnected,
    sendToServer,
    waitForWsConnection,
} from '@/api/stompClient';
import { useSessionStore } from '@/store/sessionStore';

export type SessionActivationResult =
    | { status: 'activated'; sessionId: string }
    | { status: 'failed'; sessionId: string; error: Error }
    | { status: 'superseded'; sessionId: string };

export interface ActivationOptions {
    bindTimeoutMs?: number;
}

interface PendingActivation {
    sessionId: string;
    generation: number;
    connectionController: AbortController;
    promise: Promise<SessionActivationResult>;
}

let activationGeneration = 0;
let pendingActivation: PendingActivation | null = null;

/** Returns the authoritative Session activation already in progress, if any. */
export function getPendingSessionActivation():
        Promise<SessionActivationResult> | null {
    return pendingActivation?.promise ?? null;
}

function publishBind(payload: {
    sessionId: string;
    protocolVersion: number;
    bindRequestId: string;
    bindingEpoch: number;
}): boolean {
    return sendToServer('/app/bind-session', payload);
}

async function restorePreviousBinding(
    previousSessionId: string | null,
    generation: number,
    bindTimeoutMs: number,
): Promise<void> {
    if (generation !== activationGeneration) return;

    // A failed/timeout bind is ambiguous: the server may have switched before
    // its restore frame was lost. Never retain a client-side "bound" claim.
    resetBoundSession();
    if (!previousSessionId
            || useSessionStore.getState().sessionId !== previousSessionId
            || !isWsConnected()) {
        return;
    }

    await bindSessionAndWait(
        previousSessionId,
        publishBind,
        bindTimeoutMs,
    );
    if (generation !== activationGeneration) return;
    if (useSessionStore.getState().sessionId !== previousSessionId
            || !isSessionBound(previousSessionId)) {
        resetBoundSession();
    }
}

async function safelyRestorePreviousBinding(
    previousSessionId: string | null,
    generation: number,
    bindTimeoutMs: number,
): Promise<void> {
    try {
        await restorePreviousBinding(
            previousSessionId,
            generation,
            bindTimeoutMs,
        );
    } catch (error) {
        if (generation === activationGeneration) {
            resetBoundSession();
        }
        console.warn(
            '[SessionActivation] Failed to restore previous binding:',
            error,
        );
    }
}

/**
 * Binds a candidate Session and lets the matching session_restored frame be
 * the only commit point for active Session state, messages and persistence.
 * A newer activation supersedes an older one without allowing the older
 * failure path to restore or clear anything.
 */
export function activateSessionCandidate(
    sessionId: string,
    options: ActivationOptions = {},
): Promise<SessionActivationResult> {
    const normalizedSessionId = sessionId.trim();
    if (!normalizedSessionId) {
        return Promise.resolve({
            status: 'failed',
            sessionId,
            error: new Error('Session ID 不能为空'),
        });
    }
    if (pendingActivation?.sessionId === normalizedSessionId) {
        return pendingActivation.promise;
    }

    const generation = ++activationGeneration;
    pendingActivation?.connectionController.abort();
    const connectionController = new AbortController();
    const previousSessionId =
        useSessionStore.getState().sessionId?.trim() || null;
    const bindTimeoutMs = options.bindTimeoutMs ?? 5000;

    const operation = (async (): Promise<SessionActivationResult> => {
        try {
            if (previousSessionId === normalizedSessionId
                    && isSessionBound(normalizedSessionId)) {
                return {
                    status: 'activated',
                    sessionId: normalizedSessionId,
                };
            }

            await waitForWsConnection(connectionController.signal);
            if (generation !== activationGeneration) {
                return {
                    status: 'superseded',
                    sessionId: normalizedSessionId,
                };
            }

            await bindSessionAndWait(
                normalizedSessionId,
                publishBind,
                bindTimeoutMs,
            );
            if (generation !== activationGeneration) {
                return {
                    status: 'superseded',
                    sessionId: normalizedSessionId,
                };
            }
            // The matching restore frame is the authority. Interaction
            // recovery can outlive the bind wait without invalidating a
            // Session that has already been committed and marked bound.
            if (useSessionStore.getState().sessionId
                        === normalizedSessionId
                    && isSessionBound(normalizedSessionId)) {
                return {
                    status: 'activated',
                    sessionId: normalizedSessionId,
                };
            }

            await safelyRestorePreviousBinding(
                previousSessionId,
                generation,
                bindTimeoutMs,
            );
            return {
                status: 'failed',
                sessionId: normalizedSessionId,
                error: new Error(
                    '会话绑定未获得服务端确认，已保留原会话'),
            };
        } catch (cause) {
            if (generation !== activationGeneration) {
                return {
                    status: 'superseded',
                    sessionId: normalizedSessionId,
                };
            }
            await safelyRestorePreviousBinding(
                previousSessionId,
                generation,
                bindTimeoutMs,
            );
            return {
                status: 'failed',
                sessionId: normalizedSessionId,
                error: cause instanceof Error
                    ? cause : new Error(String(cause)),
            };
        }
    })();

    const tracked = operation.finally(() => {
        if (pendingActivation?.generation === generation) {
            pendingActivation = null;
        }
    });
    pendingActivation = {
        sessionId: normalizedSessionId,
        generation,
        connectionController,
        promise: tracked,
    };
    return tracked;
}
