import { describe, test, expect, vi, beforeEach } from 'vitest';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useNotificationStore } from '@/store/notificationStore';
import { bindSessionAndWait, dispatch, resetBoundSession } from '@/api/dispatch';

beforeEach(() => {
    resetBoundSession();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => [] }));
    // Reset stores between tests
    useMessageStore.setState({
        messages: [],
        streamingMessageId: null,
        streamingContent: '',
        thinkingContent: '',
        activeToolCalls: new Map(),
    });
    useSessionStore.setState({
        sessionId: null,
        model: null,
        status: 'idle',
        turnCount: 0,
        isAborted: false,
    });
    usePermissionStore.setState({
        pendingPermissions: [],
        permissionMode: 'default',
    });
});

describe('dispatch 消息分发', () => {
    test('stream_delta → appendStreamDelta (external store)', () => {
        // stream_delta now goes to external streaming store, not messageStore
        // Verify it doesn't throw
        expect(() => {
            dispatch({ type: 'stream_delta', delta: 'hello', messageId: 'msg-1', ts: 1 } as never);
        }).not.toThrow();
    });

    test('session_restored → clearMessages + addMessage + resumeSession', async () => {
        let bindRequestId = '';
        let bindingEpoch = 0;
        const bound = bindSessionAndWait('s1', payload => {
            bindRequestId = payload.bindRequestId;
            bindingEpoch = payload.bindingEpoch;
        });
        dispatch({
            type: 'session_restored', ts: 1, bindRequestId, protocolVersion: 3,
            bindingEpoch,
            messages: [{ type: 'user', uuid: '1', timestamp: 1, content: [{ type: 'text', text: 'hi' }] }],
            metadata: { sessionId: 's1', model: 'gpt-4o', permissionMode: 'AUTO_APPROVE', status: 'idle' },
        } as never);
        await expect(bound).resolves.toBe(true);
        expect(useMessageStore.getState().messages).toHaveLength(1);
        expect(useSessionStore.getState().model).toBe('gpt-4o');
        expect(usePermissionStore.getState().permissionMode).toBe('auto_approve');
    });

    test('permission_request → showPermission + waiting_permission', () => {
        dispatch({
            type: 'permission_request', ts: 1,
            toolUseId: 'tu1', toolName: 'BashTool',
            input: { command: 'rm -rf /' },
            suggestions: [],
        } as never);
        const { pendingPermissions } = usePermissionStore.getState();
        expect(pendingPermissions.length).toBe(1);
        expect(pendingPermissions[0]?.toolName).toBe('BashTool');
        expect(useSessionStore.getState().status).toBe('waiting_permission');
    });

    test('error → addMessage(system) + setStatus(idle)', () => {
        dispatch({
            type: 'error', ts: 1,
            message: 'Rate limited', code: 'RATE_LIMIT', retryable: true,
        } as never);
        expect(useSessionStore.getState().status).toBe('idle');
        const msgs = useMessageStore.getState().messages;
        expect(msgs.length).toBeGreaterThan(0);
        const lastMsg = msgs[msgs.length - 1];
        expect(lastMsg.type).toBe('system');
        if (lastMsg.type === 'system') {
            expect(lastMsg.content).toContain('Rate limited');
        }
    });

    test('compact_event warning → addNotification', () => {
        const spy = vi.spyOn(useNotificationStore.getState(), 'addNotification');
        dispatch({
            type: 'compact_event', ts: 1,
            phase: 'warning', usagePercent: 85,
        } as never);
        expect(spy).toHaveBeenCalledWith(
            expect.objectContaining({ key: 'compact-warning', level: 'warning' }),
        );
        spy.mockRestore();
    });

    test('token_warning → addNotification', () => {
        const spy = vi.spyOn(useNotificationStore.getState(), 'addNotification');
        dispatch({
            type: 'token_warning', ts: 1,
            currentTokens: 180000, maxTokens: 200000,
            usagePercent: 90, warningLevel: 'red',
        } as never);
        expect(spy).toHaveBeenCalled();
        spy.mockRestore();
    });

    test('interrupt_ack USER_INTERRUPT → idle + system message', () => {
        dispatch({
            type: 'interrupt_ack', ts: 1, reason: 'USER_INTERRUPT',
        } as never);
        expect(useSessionStore.getState().status).toBe('idle');
        const msgs = useMessageStore.getState().messages;
        expect(msgs.some(m => m.type === 'system' && (m as { content: string }).content.includes('已中断'))).toBe(true);
    });

    test('model_changed → setModel', () => {
        dispatch({ type: 'model_changed', ts: 1, model: 'qwen3.6-plus' } as never);
        expect(useSessionStore.getState().model).toBe('qwen3.6-plus');
    });

    test('permission_mode_changed commits server mode without hiding pending requests', () => {
        usePermissionStore.getState().showPermission({
            interactionId: 'permission-1',
            toolUseId: 'tool-1',
            toolName: 'Bash',
            input: {},
            riskLevel: 'high',
            reason: 'existing request',
        });

        dispatch({
            type: 'permission_mode_changed',
            mode: 'AUTO_APPROVE',
            previous: 'DEFAULT',
            ts: 1,
        } as never);

        expect(usePermissionStore.getState().permissionMode).toBe('auto_approve');
        expect(usePermissionStore.getState().pendingPermissions).toHaveLength(1);
    });

    test('permission_mode_changed ignores unknown server values', () => {
        dispatch({
            type: 'permission_mode_changed',
            mode: 'UNKNOWN',
            ts: 1,
        } as never);

        expect(usePermissionStore.getState().permissionMode).toBe('default');
    });

    test('message_complete → finalizeStream + idle', async () => {
        // Start streaming first
        useMessageStore.getState().appendStreamDelta('Test response');

        dispatch({
            type: 'message_complete', ts: 1,
            usage: { inputTokens: 100, outputTokens: 50, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
            stopReason: 'end_turn',
        } as never);

        // handleMessageComplete uses queueMicrotask, so we need to wait for it
        await new Promise<void>(resolve => queueMicrotask(() => resolve()));

        expect(useSessionStore.getState().status).toBe('idle');
        expect(useMessageStore.getState().streamingContent).toBe('');
    });

    test('message_complete atomically reconciles the authoritative committed tail', async () => {
        useSessionStore.setState({ sessionId: 's1', status: 'streaming' });
        useMessageStore.setState({
            messages: [
                { type: 'assistant', uuid: 'anchor', timestamp: 1, content: [{ type: 'text', text: 'history' }] },
                { type: 'user', uuid: 'provisional', timestamp: 2, content: [{ type: 'text', text: 'draft' }] },
            ] as never,
        });
        useMessageStore.getState().startToolCall('tool-1', 'Bash', { command: 'pwd' });

        dispatch({
            type: 'message_complete', ts: 2, sessionId: 's1', runId: 'run-1',
            replaceAfterMessageId: 'anchor',
            committedMessages: [
                { type: 'user', uuid: 'saved-user', timestamp: 3, content: [{ type: 'text', text: 'saved' }] },
                { type: 'assistant', uuid: 'saved-final', timestamp: 4, content: [{ type: 'text', text: 'done' }] },
            ],
            usage: { inputTokens: 10, outputTokens: 5, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
            stopReason: 'end_turn',
        } as never);

        await new Promise<void>(resolve => queueMicrotask(resolve));

        expect(useMessageStore.getState().messages.map(message => message.uuid))
            .toEqual(['anchor', 'saved-user', 'saved-final']);
        expect(useMessageStore.getState().activeToolCalls.size).toBe(0);
        expect(useSessionStore.getState().status).toBe('idle');
    });

    test('a late committed completion cannot replace the newly selected session', async () => {
        useSessionStore.setState({ sessionId: 's2', status: 'idle' });
        useMessageStore.setState({
            messages: [{
                type: 'assistant', uuid: 's2-message', timestamp: 1,
                content: [{ type: 'text', text: 'current session' }],
            }] as never,
        });

        dispatch({
            type: 'message_complete', ts: 3, sessionId: 's1',
            replaceAfterMessageId: null,
            committedMessages: [{
                type: 'assistant', uuid: 's1-message', timestamp: 2,
                content: [{ type: 'text', text: 'stale session' }],
            }],
            usage: { inputTokens: 1, outputTokens: 1, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
            stopReason: 'end_turn',
        } as never);

        await new Promise<void>(resolve => queueMicrotask(resolve));

        expect(useMessageStore.getState().messages.map(message => message.uuid))
            .toEqual(['s2-message']);
        expect(useSessionStore.getState().sessionId).toBe('s2');
    });

    test('run_input_applied closes the current assistant segment without ending the run', () => {
        useSessionStore.getState().setStatus('streaming');
        useMessageStore.getState().appendStreamDelta('before steering');
        const firstAssistantId = useMessageStore.getState().streamingMessageId;

        dispatch({
            type: 'run_input_applied', requestId: 'request-1',
            text: 'change direction', appliedAt: 123,
        } as never);

        let state = useMessageStore.getState();
        expect(state.streamingMessageId).toBeNull();
        expect(state.messages.map(message => message.type))
            .toEqual(['assistant', 'user']);
        expect(state.messages[0]).toMatchObject({
            uuid: firstAssistantId,
            content: [{ type: 'text', text: 'before steering' }],
        });
        expect(state.messages[1]).toMatchObject({
            uuid: 'request-1',
            content: [{ type: 'text', text: 'change direction' }],
        });
        expect(useSessionStore.getState().status).toBe('streaming');

        dispatch({ type: 'stream_delta', delta: 'after steering', messageId: 'next' } as never);
        state = useMessageStore.getState();
        expect(state.streamingMessageId).not.toBe(firstAssistantId);
        expect(state.messages.map(message => message.type))
            .toEqual(['assistant', 'user', 'assistant']);

        const nextAssistantId = state.streamingMessageId;
        dispatch({
            type: 'run_input_applied', requestId: 'request-1',
            text: 'change direction', appliedAt: 123,
        } as never);
        expect(useMessageStore.getState().streamingMessageId)
            .toBe(nextAssistantId);
        expect(useMessageStore.getState().messages.map(message => message.type))
            .toEqual(['assistant', 'user', 'assistant']);
    });

    test('run_input_rejected only idles a stale client when no active run exists', () => {
        useSessionStore.getState().setStatus('streaming');
        dispatch({
            type: 'run_input_rejected', requestId: 'request-1',
            code: 'QUEUE_FULL', message: 'full', rejectedAt: 1,
        } as never);
        expect(useSessionStore.getState().status).toBe('streaming');

        dispatch({
            type: 'run_input_rejected', requestId: 'request-2',
            code: 'NO_ACTIVE_RUN', message: 'finished', rejectedAt: 2,
        } as never);
        expect(useSessionStore.getState().status).toBe('idle');
    });

    test('未知消息类型 → console.warn (不崩溃)', () => {
        const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
        expect(() => {
            dispatch({ type: 'unknown_future_type', ts: 1 } as never);
        }).not.toThrow();
        expect(warnSpy).toHaveBeenCalled();
        warnSpy.mockRestore();
    });
});
