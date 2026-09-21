import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
    bindSessionAndWait,
    isSessionBindingReady,
    dispatch,
    recoverPendingInteractions,
    resetBoundSession,
} from '@/api/dispatch';
import { useCostStore } from '@/store/costStore';
import { useMessageStore } from '@/store/messageStore';
import { useNotificationStore } from '@/store/notificationStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useRunStore } from '@/store/runStore';
import { useSessionStore } from '@/store/sessionStore';
import { useAppUiStore } from '@/store/appUiStore';
import { buildTurns } from '@/store/selectors/turnProjection';
import type { Message, SessionRestoredPayload } from '@/types';

const sendToServerMock = vi.hoisted(() => vi.fn(() => true));
vi.mock('@/api/stompClient', () => ({
    send: vi.fn(),
    sendToServer: sendToServerMock,
}));

function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (reason?: unknown) => void;
    const promise = new Promise<T>((done, fail) => {
        resolve = done;
        reject = fail;
    });
    return { promise, resolve, reject };
}

const response = (interactions: unknown[]) => ({
    ok: true,
    json: async () => interactions,
});

function startRestore(
    sessionId: string,
    runSnapshot: SessionRestoredPayload['runSnapshot'],
    messages: Message[] = [],
) {
    let restored!: SessionRestoredPayload;
    const bound = bindSessionAndWait(sessionId, binding => {
        restored = {
            type: 'session_restored', ...binding, messages, runSnapshot,
            metadata: { sessionId, model: 'model', permissionMode: 'DEFAULT', status: 'idle' },
        };
    });
    return { bound, restored };
}

const permissionInteraction = (sessionId: string, suffix: string) => ({
    protocolVersion: 3,
    interactionId: `permission-${suffix}`,
    correlationKey: `tool-${suffix}`,
    sessionId,
    runId: `run-${suffix}`,
    interactionType: 'permission',
    status: 'pending',
    prompt: {
        toolUseId: `tool-${suffix}`,
        toolName: 'Write',
        inputSummary: `${suffix}.txt`,
        riskLevel: 'medium',
        reason: 'test',
    },
    allowedDecisions: ['allow', 'deny'],
    scopeOptions: ['run', 'session'],
    deliveryGeneration: 1,
    deliveryWindowEndsAt: Date.now() + 60_000,
    version: 1,
    serverNow: Date.now(),
});

const elicitationInteraction = (sessionId: string, suffix: string) => ({
    protocolVersion: 2,
    interactionId: `elicitation-${suffix}`,
    correlationKey: `question-${suffix}`,
    sessionId,
    runId: `run-${suffix}`,
    interactionType: 'elicitation',
    status: 'pending',
    prompt: { question: `Question ${suffix}?`, options: [] },
    allowedDecisions: ['answer', 'cancel'],
    scopeOptions: [],
    deliveryGeneration: 1,
    deliveryWindowEndsAt: Date.now() + 60_000,
    version: 1,
    serverNow: Date.now(),
});

describe('transport-scoped bind recovery', () => {
    beforeEach(() => {
        sendToServerMock.mockClear();
        sendToServerMock.mockReturnValue(true);
        resetBoundSession();
        window.sessionStorage.clear();
        useSessionStore.setState({ sessionId: null });
        useMessageStore.getState().clearMessages();
        usePermissionStore.getState().clearPermissions();
        useAppUiStore.setState({ elicitationDialog: null });
        useNotificationStore.getState().clearAll();
        useCostStore.setState({
            sessionCost: 0,
            totalCost: 0,
            usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
        });
        useRunStore.setState({ recoverySnapshots: new Map(), recoveryEventSeq: new Map() });
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => [] }));
    });

    it.each(['refresh', 'reconnect'])('restores a FAILED run error after %s while keeping input available', async (recovery) => {
        useSessionStore.getState().setStatus('streaming');
        if (recovery === 'reconnect') {
            dispatch({ type: 'error', message: '实时错误提示', code: 'INCOMPLETE' });
        }
        const messages: Message[] = [{
            type: 'user', uuid: 'user-prompt', timestamp: 1,
            content: [{ type: 'text', text: '继续任务' }],
        }];
        const { bound, restored } = startRestore('session-failed', {
            id: 'run-failed', status: 'FAILED',
            errorSummary: '模型未返回有效正文', exitReason: 'INCOMPLETE',
        }, messages);
        restored.activeToolCalls = [{ toolUseId: 'stale-tool', toolName: 'Bash', input: {} }];
        dispatch(restored);
        await expect(bound).resolves.toBe(true);

        expect(useMessageStore.getState().messages).toEqual([
            messages[0],
            expect.objectContaining({
                type: 'system', uuid: 'run-failure-run-failed', subtype: 'error',
                content: '模型未返回有效正文', errorCode: 'INCOMPLETE',
            }),
        ]);
        expect(messages).toHaveLength(1);
        expect(useMessageStore.getState().activeToolCalls.size).toBe(0);
        expect(useMessageStore.getState().streamingMessageId).toBeNull();
        expect(useSessionStore.getState().status).toBe('idle');
        expect(isSessionBindingReady('session-failed')).toBe(true);
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });

    it('keeps one failure card with a stable identity and terminal time across duplicate snapshots and rebinds', async () => {
        const terminalAt = '2026-09-21T01:30:00Z';
        const snapshot = {
            id: 'run-failed', status: 'FAILED', errorSummary: '回复未完成', terminalAt,
            finishedAt: '2026-09-21T01:30:01Z', updatedAt: '2026-09-21T01:30:02Z',
        };
        const first = startRestore('session-failed', snapshot);
        dispatch(first.restored);
        dispatch(first.restored);
        await expect(first.bound).resolves.toBe(true);
        const firstMessage = useMessageStore.getState().messages[0];
        expect(useMessageStore.getState().messages).toHaveLength(1);
        expect(firstMessage.timestamp).toBe(Date.parse(terminalAt));
        expect(buildTurns(useMessageStore.getState().messages)[0].endedAt).toBe(Date.parse(terminalAt));

        resetBoundSession();
        const rebound = startRestore('session-failed', snapshot, [firstMessage]);
        dispatch(rebound.restored);
        await expect(rebound.bound).resolves.toBe(true);
        expect(useMessageStore.getState().messages).toHaveLength(1);
        expect(useMessageStore.getState().messages[0].uuid).toBe(firstMessage.uuid);
        expect(buildTurns(useMessageStore.getState().messages)[0].endedAt).toBe(Date.parse(terminalAt));
    });

    it.each([
        {
            source: 'finishedAt',
            times: { terminalAt: 'invalid', finishedAt: '2026-09-21T01:30:00Z', updatedAt: '2026-09-21T01:30:01Z' },
            expectedTimestamp: Date.parse('2026-09-21T01:30:00Z'),
        },
        {
            source: 'updatedAt',
            times: { terminalAt: null, finishedAt: 'invalid', updatedAt: '2026-09-21T01:30:01Z' },
            expectedTimestamp: Date.parse('2026-09-21T01:30:01Z'),
        },
        {
            source: 'history when terminal timestamps are invalid',
            times: { terminalAt: 'invalid', finishedAt: '', updatedAt: 'invalid' },
            expectedTimestamp: 1234,
        },
        { source: 'history when terminal timestamps are absent', times: {}, expectedTimestamp: 1234 },
    ])('uses $source as the failure card timestamp', async ({ times, expectedTimestamp }) => {
        const { bound, restored } = startRestore('session-failed', {
            id: 'run-failed', status: 'FAILED', ...times,
        }, [{ type: 'user', uuid: 'user-prompt', timestamp: 1234, content: [{ type: 'text', text: '继续' }] }]);
        dispatch(restored);
        await expect(bound).resolves.toBe(true);
        expect(useMessageStore.getState().messages.at(-1)?.timestamp).toBe(expectedTimestamp);
        expect(buildTurns(useMessageStore.getState().messages)[0].endedAt).toBe(expectedTimestamp);
    });

    it('falls back to the restore time only when the old snapshot has no valid run or history timestamp', async () => {
        const beforeRestore = Date.now();
        const { bound, restored } = startRestore('session-failed', {
            id: 'run-failed', status: 'FAILED', terminalAt: 'invalid', updatedAt: null,
        });
        dispatch(restored);
        await expect(bound).resolves.toBe(true);
        const timestamp = useMessageStore.getState().messages[0].timestamp;
        expect(timestamp).toBeGreaterThanOrEqual(beforeRestore);
        expect(timestamp).toBeLessThanOrEqual(Date.now());
    });

    it.each([
        { errorSummary: null, exitReason: 'PROVIDER_FAILURE', expectedCode: 'PROVIDER_FAILURE' },
        { errorSummary: '  ', exitReason: null, expectedCode: 'RUN_FAILED' },
        { errorSummary: undefined, exitReason: undefined, expectedCode: 'RUN_FAILED' },
    ])('uses a readable fallback for a failed run without an error summary ($expectedCode)', async (testCase) => {
        const { bound, restored } = startRestore('session-failed', {
            id: 'run-failed', status: 'FAILED',
            errorSummary: testCase.errorSummary, exitReason: testCase.exitReason,
        });
        dispatch(restored);
        await expect(bound).resolves.toBe(true);
        expect(useMessageStore.getState().messages).toEqual([
            expect.objectContaining({
                subtype: 'error', content: '上次回复未完成，你可以发送消息继续对话',
                errorCode: testCase.expectedCode,
            }),
        ]);
    });

    it.each(['COMPLETED', 'CANCELLED', 'INTERRUPTED', 'RUNNING', 'CANCELLING', 'WAITING_INTERACTION'])
    ('does not display a failure for a %s run', async (status) => {
        const { bound, restored } = startRestore('session-current', {
            id: 'run-current', status, errorSummary: '不应作为失败显示', exitReason: 'INCOMPLETE',
        });
        dispatch(restored);
        await expect(bound).resolves.toBe(true);
        expect(useMessageStore.getState().messages).toHaveLength(0);
    });

    it('ignores failed snapshots for stale binds, mismatched epochs and other sessions', async () => {
        const old = startRestore('session-old', { id: 'run-old', status: 'FAILED', errorSummary: '旧会话失败' });
        const current = startRestore('session-current', { id: 'run-current', status: 'COMPLETED' });
        await expect(old.bound).resolves.toBe(false);
        dispatch(old.restored);
        dispatch({
            ...current.restored,
            bindingEpoch: current.restored.bindingEpoch - 1,
            runSnapshot: { id: 'run-wrong-epoch', status: 'FAILED', errorSummary: '旧绑定失败' },
        });
        dispatch({
            ...current.restored,
            metadata: { ...current.restored.metadata, sessionId: 'session-other' },
            runSnapshot: { id: 'run-other', status: 'FAILED', errorSummary: '其他会话失败' },
        });
        expect(useMessageStore.getState().messages).toHaveLength(0);
        expect(isSessionBindingReady('session-current')).toBe(false);

        dispatch(current.restored);
        await expect(current.bound).resolves.toBe(true);
        dispatch(old.restored);
        expect(useSessionStore.getState().sessionId).toBe('session-current');
        expect(useMessageStore.getState().messages).toHaveLength(0);
    });

    it('clears old interactions at the matching restore and keeps recovered interactions for the new Session', async () => {
        usePermissionStore.getState().showPermission({
            interactionId: 'permission-old', toolUseId: 'tool-old', toolName: 'Write',
            input: {}, riskLevel: 'medium', reason: 'old Session',
        });
        useAppUiStore.getState().showElicitationDialog({
            interactionId: 'elicitation-old', requestId: 'elicitation-old',
            question: 'Old question?', options: [],
        });
        const pendingResponse = deferred<ReturnType<typeof response>>();
        vi.stubGlobal('fetch', vi.fn().mockReturnValue(pendingResponse.promise));

        let payload: { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number } | undefined;
        const bound = bindSessionAndWait('session-new', value => { payload = value; });
        dispatch({
            type: 'session_restored', bindRequestId: payload!.bindRequestId, protocolVersion: 3,
            bindingEpoch: payload!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-new', model: 'model', permissionMode: 'DEFAULT', status: 'idle' },
        });

        expect(usePermissionStore.getState().pendingPermissions).toEqual([]);
        expect(useAppUiStore.getState().elicitationDialog).toBeNull();

        pendingResponse.resolve(response([
            permissionInteraction('session-new', 'new'),
            elicitationInteraction('session-new', 'new'),
        ]));
        await expect(bound).resolves.toBe(true);
        expect(usePermissionStore.getState().pendingPermissions.map(item => item.interactionId))
            .toEqual(['permission-new']);
        expect(useAppUiStore.getState().elicitationDialog?.interactionId)
            .toBe('elicitation-new');
    });

    it('ignores a slow interaction recovery after a newer Session bind starts', async () => {
        const sessionAResponse = deferred<ReturnType<typeof response>>();
        const fetchMock = vi.fn()
            .mockReturnValueOnce(sessionAResponse.promise)
            .mockResolvedValueOnce(response([
                permissionInteraction('session-b', 'b'),
                elicitationInteraction('session-b', 'b'),
            ]));
        vi.stubGlobal('fetch', fetchMock);

        let payloadA: { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number } | undefined;
        let payloadB: typeof payloadA;
        const boundA = bindSessionAndWait('session-a', value => { payloadA = value; });
        dispatch({
            type: 'session_restored', bindRequestId: payloadA!.bindRequestId, protocolVersion: 3,
            bindingEpoch: payloadA!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-a', model: 'model-a', permissionMode: 'DEFAULT', status: 'idle' },
        });

        const boundB = bindSessionAndWait('session-b', value => { payloadB = value; });
        await expect(boundA).resolves.toBe(false);
        dispatch({
            type: 'session_restored', bindRequestId: payloadB!.bindRequestId, protocolVersion: 3,
            bindingEpoch: payloadB!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-b', model: 'model-b', permissionMode: 'DEFAULT', status: 'idle' },
        });
        await expect(boundB).resolves.toBe(true);

        sessionAResponse.resolve(response([
            permissionInteraction('session-a', 'a'),
            elicitationInteraction('session-a', 'a'),
        ]));
        await sessionAResponse.promise;
        await Promise.resolve();

        expect(useSessionStore.getState().sessionId).toBe('session-b');
        expect(usePermissionStore.getState().pendingPermissions.map(item => item.interactionId))
            .toEqual(['permission-b']);
        expect(useAppUiStore.getState().elicitationDialog?.interactionId)
            .toBe('elicitation-b');
    });

    it('does not notify when a superseded interaction recovery fails late', async () => {
        const sessionAResponse = deferred<ReturnType<typeof response>>();
        const fetchMock = vi.fn()
            .mockReturnValueOnce(sessionAResponse.promise)
            .mockResolvedValueOnce(response([]));
        vi.stubGlobal('fetch', fetchMock);

        let payloadA: { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number } | undefined;
        let payloadB: typeof payloadA;
        const boundA = bindSessionAndWait('session-a', value => { payloadA = value; });
        dispatch({
            type: 'session_restored', bindRequestId: payloadA!.bindRequestId, protocolVersion: 3,
            bindingEpoch: payloadA!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-a', model: 'model-a', permissionMode: 'DEFAULT', status: 'idle' },
        });
        const boundB = bindSessionAndWait('session-b', value => { payloadB = value; });
        await expect(boundA).resolves.toBe(false);
        dispatch({
            type: 'session_restored', bindRequestId: payloadB!.bindRequestId, protocolVersion: 3,
            bindingEpoch: payloadB!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-b', model: 'model-b', permissionMode: 'DEFAULT', status: 'idle' },
        });
        await expect(boundB).resolves.toBe(true);

        sessionAResponse.reject(new Error('late failure'));
        await Promise.resolve();
        await Promise.resolve();

        expect(useNotificationStore.getState().notifications
            .some(item => item.key === 'run-event-recovery-failed')).toBe(false);
    });

    it('keeps the public interaction refresh working for the authoritative bound Session', async () => {
        let payload: { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number } | undefined;
        const bound = bindSessionAndWait('session-current', value => { payload = value; });
        dispatch({
            type: 'session_restored', bindRequestId: payload!.bindRequestId, protocolVersion: 3,
            bindingEpoch: payload!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-current', model: 'model', permissionMode: 'DEFAULT', status: 'idle' },
        });
        await expect(bound).resolves.toBe(true);

        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([
            permissionInteraction('session-current', 'refresh'),
        ])));
        await recoverPendingInteractions('session-current');

        expect(usePermissionStore.getState().pendingPermissions.map(item => item.interactionId))
            .toEqual(['permission-refresh']);
        await vi.waitFor(() => expect(sendToServerMock).toHaveBeenCalledWith(
            '/app/interaction-received',
            expect.objectContaining({
                interactionId: 'permission-refresh',
                deliveryGeneration: 1,
            }),
        ));
    });

    it('retains a failed interaction ACK and sends the newest redelivery generation', async () => {
        let payload: { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number } | undefined;
        const bound = bindSessionAndWait('session-ack', value => { payload = value; });
        dispatch({
            type: 'session_restored', bindRequestId: payload!.bindRequestId, protocolVersion: 3,
            bindingEpoch: payload!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-ack', model: 'model', permissionMode: 'DEFAULT', status: 'idle' },
        });
        await expect(bound).resolves.toBe(true);

        sendToServerMock.mockReturnValue(false);
        dispatch({ type: 'interaction_created', ...permissionInteraction('session-ack', 'ack') } as any);
        await vi.waitFor(() => expect(sendToServerMock).toHaveBeenCalledWith(
            '/app/interaction-received',
            expect.objectContaining({ interactionId: 'permission-ack', deliveryGeneration: 1 }),
        ));

        resetBoundSession();
        sendToServerMock.mockClear();
        sendToServerMock.mockReturnValue(true);
        let reboundPayload: typeof payload;
        const rebound = bindSessionAndWait('session-ack', value => { reboundPayload = value; });
        dispatch({
            type: 'session_restored', bindRequestId: reboundPayload!.bindRequestId, protocolVersion: 3,
            bindingEpoch: reboundPayload!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-ack', model: 'model', permissionMode: 'DEFAULT', status: 'idle' },
        });
        await expect(rebound).resolves.toBe(true);
        await vi.waitFor(() => expect(sendToServerMock).toHaveBeenCalledWith(
            '/app/interaction-received',
            expect.objectContaining({ interactionId: 'permission-ack', deliveryGeneration: 1 }),
        ));

        sendToServerMock.mockClear();
        dispatch({
            type: 'interaction_created',
            ...permissionInteraction('session-ack', 'ack'),
            deliveryGeneration: 2,
        } as any);
        await vi.waitFor(() => expect(sendToServerMock).toHaveBeenCalledWith(
            '/app/interaction-received',
            expect.objectContaining({ interactionId: 'permission-ack', deliveryGeneration: 2 }),
        ));
        expect(sendToServerMock).not.toHaveBeenCalledWith(
            '/app/interaction-received',
            expect.objectContaining({ interactionId: 'permission-ack', deliveryGeneration: 1 }),
        );

        // 切到其他 Session 时应丢弃旧 Session 的本地 ACK；再次进入旧 Session
        // 必须等待服务端 pending 恢复，不能凭陈旧内存状态自行补发。
        resetBoundSession();
        let otherPayload: typeof payload;
        const otherBound = bindSessionAndWait('session-other', value => { otherPayload = value; });
        dispatch({
            type: 'session_restored', bindRequestId: otherPayload!.bindRequestId, protocolVersion: 3,
            bindingEpoch: otherPayload!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-other', model: 'model', permissionMode: 'DEFAULT', status: 'idle' },
        });
        await expect(otherBound).resolves.toBe(true);

        resetBoundSession();
        sendToServerMock.mockClear();
        let returnedPayload: typeof payload;
        const returned = bindSessionAndWait('session-ack', value => { returnedPayload = value; });
        dispatch({
            type: 'session_restored', bindRequestId: returnedPayload!.bindRequestId, protocolVersion: 3,
            bindingEpoch: returnedPayload!.bindingEpoch, messages: [],
            metadata: { sessionId: 'session-ack', model: 'model', permissionMode: 'DEFAULT', status: 'idle' },
        });
        await expect(returned).resolves.toBe(true);
        await Promise.resolve();
        expect(sendToServerMock).not.toHaveBeenCalledWith(
            '/app/interaction-received',
            expect.objectContaining({ interactionId: 'permission-ack' }),
        );
    });

    it('clears pending mode on bind start and rejects old-session confirmations during and after recovery', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([])));
        type Binding = { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number };
        let a!: Binding, b!: Binding;
        const restore = (binding: Binding, mode: string) => dispatch({
            type: 'session_restored', ...binding, messages: [],
            metadata: { sessionId: binding.sessionId, model: 'model', permissionMode: mode, status: 'idle' },
        });
        const first = bindSessionAndWait('session-a', payload => { a = payload; });
        restore(a, 'PLAN');
        await expect(first).resolves.toBe(true);
        usePermissionStore.setState({ pendingModeChange: { requestId: 'selection-1', sessionId: 'session-a', mode: 'auto_approve', startedAt: 1 } });
        const second = bindSessionAndWait('session-b', payload => { b = payload; });
        expect(usePermissionStore.getState().pendingModeChange).toBeNull();
        expect(isSessionBindingReady('session-a')).toBe(false);
        const stale = { type: 'permission_mode_changed', mode: 'AUTO_APPROVE',
            _sessionId: 'session-a', _bindingEpoch: a.bindingEpoch } as any;
        dispatch(stale);
        expect(usePermissionStore.getState().permissionMode).toBe('plan');
        restore(b, 'DONT_ASK');
        await expect(second).resolves.toBe(true);
        dispatch(stale);
        expect(usePermissionStore.getState().permissionMode).toBe('dont_ask');
        expect(isSessionBindingReady('session-b')).toBe(true);
    });

    it('matches bindRequestId, snapshots atomically, then replays frames received during recovery', async () => {
        let firstPayload: { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number } | undefined;
        let secondPayload: typeof firstPayload;
        const first = bindSessionAndWait('session-a', payload => { firstPayload = payload; });
        const second = bindSessionAndWait('session-b', payload => { secondPayload = payload; });
        await expect(first).resolves.toBe(false);

        dispatch({
            type: 'cost_update', sessionCost: 9, totalCost: 12,
            usage: { inputTokens: 2, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
        });
        dispatch({
            type: 'session_restored', bindRequestId: firstPayload!.bindRequestId, protocolVersion: 3,
            bindingEpoch: firstPayload!.bindingEpoch,
            messages: [], metadata: { sessionId: 'session-a', model: 'wrong', permissionMode: 'DEFAULT', status: 'idle' },
        });
        dispatch({
            type: 'session_restored', bindRequestId: secondPayload!.bindRequestId, protocolVersion: 3,
            bindingEpoch: secondPayload!.bindingEpoch,
            messages: [], metadata: { sessionId: 'session-b', model: 'model', permissionMode: 'DEFAULT', status: 'idle' },
            runSnapshot: { id: 'run-b', status: 'RUNNING' }, snapshotEventSeq: 42,
            activeToolCalls: [{ toolUseId: 'tool-b', toolName: 'Bash', input: { command: 'work' } }],
            costSummary: { totalCost: 3 },
        });

        await expect(second).resolves.toBe(true);
        expect(useRunStore.getState().recoveryEventSeq.get('run-b')).toBe(42);
        expect(useMessageStore.getState().activeToolCalls.has('tool-b')).toBe(true);
        expect(useCostStore.getState().sessionCost).toBe(9);
        expect(useCostStore.getState().totalCost).toBe(12);
    });

    it('clears a persisted session only when its bind returns SESSION_NOT_FOUND', async () => {
        await useSessionStore.getState().resumeSession('session-deleted');
        let payload: { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number } | undefined;
        const bound = bindSessionAndWait('session-deleted', value => { payload = value; });

        dispatch({
            type: 'protocol_error',
            code: 'SESSION_NOT_FOUND',
            supportedVersion: 3,
            bindRequestId: payload!.bindRequestId,
            bindingEpoch: payload!.bindingEpoch,
        });

        await expect(bound).resolves.toBe(false);
        expect(useSessionStore.getState().sessionId).toBe('');
        expect(window.sessionStorage.getItem('zhikuncode.activeSessionId')).toBeNull();
        expect(useNotificationStore.getState().notifications.at(-1)?.message)
            .toBe('原会话已不存在，已清除本地恢复状态');
    });

    it('does not clear a newer session when an older bind fails', async () => {
        await useSessionStore.getState().resumeSession('session-old');
        let payload: { sessionId: string; protocolVersion: number; bindRequestId: string; bindingEpoch: number } | undefined;
        const oldBind = bindSessionAndWait('session-old', value => { payload = value; });
        await useSessionStore.getState().resumeSession('session-new');

        dispatch({
            type: 'protocol_error',
            code: 'SESSION_NOT_FOUND',
            supportedVersion: 3,
            bindRequestId: payload!.bindRequestId,
            bindingEpoch: payload!.bindingEpoch,
        });

        await expect(oldBind).resolves.toBe(false);
        expect(useSessionStore.getState().sessionId).toBe('session-new');
        expect(window.sessionStorage.getItem('zhikuncode.activeSessionId')).toBe('session-new');
        expect(useNotificationStore.getState().notifications.at(-1)?.message)
            .toBe('请求恢复的会话已不存在，当前会话未受影响');
    });

    it('closes the recovery gate when publishing the bind throws', async () => {
        const consoleError = vi.spyOn(console, 'error')
            .mockImplementation(() => undefined);
        const bound = bindSessionAndWait('session-failed', () => {
            throw new Error('publish failed');
        });

        await expect(bound).resolves.toBe(false);
        expect(() => dispatch({
            type: 'cost_update',
            sessionCost: 4,
            totalCost: 6,
            usage: {
                inputTokens: 1,
                outputTokens: 2,
                cacheReadInputTokens: 0,
                cacheCreationInputTokens: 0,
            },
        })).not.toThrow();
        expect(useCostStore.getState().sessionCost).toBe(4);
        expect(consoleError).toHaveBeenCalledOnce();
        consoleError.mockRestore();
    });
});
