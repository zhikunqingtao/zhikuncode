import { describe, test, expect, vi, beforeEach } from 'vitest';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useNotificationStore } from '@/store/notificationStore';
import { useAppUiStore } from '@/store/appUiStore';
import { dispatch } from '@/api/dispatch';

beforeEach(() => {
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
});

describe('dispatch 消息分发', () => {
    test('stream_delta → appendStreamDelta (external store)', () => {
        // stream_delta now goes to external streaming store, not messageStore
        // Verify it doesn't throw
        expect(() => {
            dispatch({ type: 'stream_delta', delta: 'hello', messageId: 'msg-1', ts: 1 } as never);
        }).not.toThrow();
    });

    test('session_restored → clearMessages + addMessage + resumeSession', () => {
        dispatch({
            type: 'session_restored', ts: 1,
            messages: [{ type: 'user', uuid: '1', timestamp: 1, content: [{ type: 'text', text: 'hi' }] }],
            metadata: { sessionId: 's1', model: 'gpt-4o', status: 'idle' },
        } as never);
        expect(useMessageStore.getState().messages).toHaveLength(1);
        expect(useSessionStore.getState().model).toBe('gpt-4o');
    });

    test('permission_request → showPermission + waiting_permission', () => {
        dispatch({
            type: 'permission_request', ts: 1,
            toolUseId: 'tu1', toolName: 'BashTool',
            input: { command: 'rm -rf /' },
            suggestions: [],
        } as never);
        const { pendingPermission } = usePermissionStore.getState();
        expect(pendingPermission).toBeTruthy();
        expect(pendingPermission?.toolName).toBe('BashTool');
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
        dispatch({ type: 'model_changed', ts: 1, model: 'claude-sonnet-4-20250514' } as never);
        expect(useSessionStore.getState().model).toBe('claude-sonnet-4-20250514');
    });

    test('message_complete → finalizeStream + idle', () => {
        // Start streaming first
        useMessageStore.getState().appendStreamDelta('Test response');

        dispatch({
            type: 'message_complete', ts: 1,
            usage: { inputTokens: 100, outputTokens: 50, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
            stopReason: 'end_turn',
        } as never);

        expect(useSessionStore.getState().status).toBe('idle');
        expect(useMessageStore.getState().streamingContent).toBe('');
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
