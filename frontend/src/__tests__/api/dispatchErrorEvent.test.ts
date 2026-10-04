import { usePermissionStore } from '@/store/permissionStore';
/**
 * error 事件契约测试
 * 契约: type="error", payload = { message: string(人类可读中文),
 *   errorCode?: "PROVIDER_PAYMENT_REQUIRED"|"PROVIDER_FORBIDDEN"|"PROVIDER_RATE_LIMITED"|"PROVIDER_ERROR",
 *   httpStatus?: number }
 * errorCode 存在 → 醒目 provider_error 横幅 + 常驻通知；缺失 → 保持既有行为。
 * 运行错误终止“生成中”状态；命令及设置失败不得终止无关的运行。
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { dispatch, resetBoundSession } from '@/api/dispatch';
import { streamingStore } from '@/hooks/useStreamingText';
import { useMessageStore } from '@/store/messageStore';
import { useNotificationStore } from '@/store/notificationStore';
import { useSessionStore } from '@/store/sessionStore';
import { buildTurns } from '@/store/selectors/turnProjection';
import { resolveTurnOutcome } from '@/components/message/turn/turnUtils';
import type { ServerMessage } from '@/types';

vi.mock('@/api/stompClient', () => ({
    send: vi.fn(),
    sendToServer: vi.fn(() => true),
}));

function findSystemMessage() {
    return useMessageStore.getState().messages.find(m => m.type === 'system') as
        Extract<ReturnType<typeof useMessageStore.getState>['messages'][number], { type: 'system' }> | undefined;
}

describe('error 事件契约解析', () => {
    beforeEach(() => {
        useMessageStore.getState().clearMessages();
        useNotificationStore.getState().clearAll();
        useSessionStore.getState().setStatus('streaming');
    });

    it('same-mode confirmation and binding reset clear pending selection', () => {
        usePermissionStore.setState({ permissionMode: 'plan', pendingModeChange: { requestId: 'selection-1', sessionId: 's', mode: 'plan', startedAt: 1 } });
        dispatch({ type: 'permission_mode_changed', mode: 'PLAN', requestId: 'selection-1' } as ServerMessage);
        expect(usePermissionStore.getState().pendingModeChange).toBeNull();
        usePermissionStore.setState({ pendingModeChange: { requestId: 'selection-1', sessionId: 's', mode: 'auto_approve', startedAt: 2 } });
        resetBoundSession();
        expect(usePermissionStore.getState().pendingModeChange).toBeNull();
        expect(usePermissionStore.getState().permissionMode).toBe('plan');
    });

    it('permission save failure clears pending selection without terminating a running query', () => {
        usePermissionStore.setState({ permissionMode: 'plan', pendingModeChange: { requestId: 'selection-1', sessionId: 's', mode: 'auto_approve', startedAt: 1 } });
        useMessageStore.getState().appendStreamDelta('正在处理');
        const stream = useMessageStore.getState().streamingMessageId;
        dispatch({ type: 'error', code: 'PERMISSION_MODE_SAVE_FAILED', requestId: 'selection-1', message: '保存失败' } as ServerMessage);
        expect(usePermissionStore.getState().pendingModeChange).toBeNull();
        expect(usePermissionStore.getState().permissionMode).toBe('plan');
        expect(useSessionStore.getState().status).toBe('streaming');
        expect(useMessageStore.getState().streamingMessageId).toBe(stream);
        expect(findSystemMessage()).toBeUndefined();
    });

    it('unrelated permission events update authority without acknowledging the pending request', () => {
        const pending = { requestId: 'new', sessionId: 's', mode: 'plan' as const, startedAt: 1 };
        usePermissionStore.setState({ pendingModeChange: pending });
        dispatch({ type: 'permission_mode_changed', mode: 'AUTO_APPROVE', requestId: 'old' } as ServerMessage);
        expect(usePermissionStore.getState().permissionMode).toBe('auto_approve');
        expect(usePermissionStore.getState().pendingModeChange).toBe(pending);
        dispatch({ type: 'error', code: 'PERMISSION_MODE_SAVE_FAILED', requestId: 'old', message: 'old failure' } as ServerMessage);
        expect(usePermissionStore.getState().pendingModeChange).toBe(pending);
        dispatch({ type: 'permission_mode_changed', mode: 'PLAN', requestId: 'new' } as ServerMessage);
        expect(usePermissionStore.getState().pendingModeChange).toBeNull();
    });

    it.each(['COMMAND_ERROR', 'COMMAND_NOT_FOUND'].flatMap(code =>
        (['streaming', 'waiting_permission', 'idle'] as const).map(status => ({ code, status })),
    ))(
        '$code 在 $status 状态下独立显示，不改变当前任务', ({ code, status }) => {
            const message = code === 'COMMAND_NOT_FOUND'
                ? 'Unknown command: /missing'
                : '读取 Git 差异失败（正文），请稍后重试。';
            resetBoundSession();
            usePermissionStore.setState({ pendingPermissions: [] });
            useSessionStore.getState().setStatus(status);
            useMessageStore.getState().addMessage({
                type: 'user', uuid: 'unrelated-query', timestamp: 1,
                content: [{ type: 'text', text: '继续当前任务' }],
            });
            if (status !== 'idle') {
                useMessageStore.getState().appendStreamDelta('任务仍在执行');
                useMessageStore.getState().startToolCall('active-tool', 'Bash', { command: 'sleep 30' });
            }
            if (status === 'waiting_permission') {
                dispatch({
                    type: 'permission_request', toolUseId: 'active-tool', toolName: 'Bash',
                    input: { command: 'sleep 30' }, riskLevel: 'medium', reason: '等待批准已有工具',
                } as ServerMessage);
                expect(usePermissionStore.getState().pendingPermissions).toHaveLength(1);
            }
            const before = useMessageStore.getState();
            const permissionsBefore = usePermissionStore.getState().pendingPermissions;
            const outcomesBefore = buildTurns(before.messages).map(resolveTurnOutcome);

            dispatch({
                type: 'error', code, message, retryable: false,
            } as ServerMessage);

            const after = useMessageStore.getState();
            expect(useSessionStore.getState().status).toBe(status);
            expect(after.streamingMessageId).toBe(before.streamingMessageId);
            expect(after.streamingContent).toBe(before.streamingContent);
            expect(after.activeToolCalls).toEqual(before.activeToolCalls);
            expect(after.messages.slice(0, -1)).toEqual(before.messages);
            expect(after.messages.at(-1)).toMatchObject({
                type: 'system', subtype: 'command_result', errorCode: code,
                content: `命令执行失败：${message}`, retryable: false,
            });
            expect(buildTurns(after.messages).map(resolveTurnOutcome)).toEqual(outcomesBefore);
            expect(usePermissionStore.getState().pendingPermissions).toEqual(permissionsBefore);
            // 通知容器未挂载，不能只写 notificationStore 而让错误不可见。
            expect(useNotificationStore.getState().notifications).toHaveLength(0);
        },
    );

    it('COMMAND_NOT_FOUND 后原流仍能追加，原工具仍能正常完成', () => {
        resetBoundSession();
        dispatch({ type: 'stream_delta', delta: '前段', messageId: 'continuing-message' } as ServerMessage);
        dispatch({
            type: 'tool_use_start', toolUseId: 'continuing-tool', toolName: 'Bash', input: { command: 'echo ok' },
        } as ServerMessage);
        const streamId = useMessageStore.getState().streamingMessageId;
        expect(streamId).not.toBeNull();

        dispatch({
            type: 'error', code: 'COMMAND_NOT_FOUND', message: 'Unknown command: /missing', retryable: false,
        } as ServerMessage);
        dispatch({ type: 'stream_delta', delta: '后段', messageId: 'continuing-message' } as ServerMessage);
        dispatch({
            type: 'tool_result', toolUseId: 'continuing-tool', content: 'ok', isError: false,
        } as ServerMessage);

        expect(useSessionStore.getState().status).toBe('streaming');
        expect(useMessageStore.getState().streamingMessageId).toBe(streamId);
        expect(streamingStore.getSnapshot()).toBe('前段后段');
        expect(useMessageStore.getState().activeToolCalls.get('continuing-tool')).toMatchObject({
            status: 'completed', result: { content: 'ok', isError: false },
        });
        expect(findSystemMessage()).toMatchObject({
            subtype: 'command_result', errorCode: 'COMMAND_NOT_FOUND',
            content: '命令执行失败：Unknown command: /missing',
        });
    });

    it('errorCode 存在时渲染 provider_error 消息 + 常驻通知，并终止生成中状态', () => {
        useMessageStore.getState().appendStreamDelta('部分输出');
        expect(useMessageStore.getState().streamingMessageId).not.toBeNull();

        dispatch({
            type: 'error',
            message: '账户余额不足，请充值后重试',
            errorCode: 'PROVIDER_PAYMENT_REQUIRED',
            httpStatus: 402,
        } as ServerMessage);

        // 流式状态终止，spinner 停止，输入恢复可用
        expect(useMessageStore.getState().streamingMessageId).toBeNull();
        expect(useSessionStore.getState().status).toBe('idle');

        const system = findSystemMessage();
        expect(system?.subtype).toBe('provider_error');
        expect(system?.errorCode).toBe('PROVIDER_PAYMENT_REQUIRED');
        expect(system?.content).toBe('账户余额不足，请充值后重试');
        expect(system?.metadata).toEqual({ httpStatus: 402 });

        // 常驻错误通知横幅（timeout=0）
        const banner = useNotificationStore.getState().notifications
            .find(n => n.key === 'provider-error-PROVIDER_PAYMENT_REQUIRED');
        expect(banner?.level).toBe('error');
        expect(banner?.message).toBe('账户余额不足，请充值后重试');
        expect(banner?.timeout).toBe(0);
    });

    it.each(['INTERNAL_ERROR', 'query_error'].flatMap(code =>
        [true, false].map(retryable => ({ code, retryable })),
    ))('$code retryable=$retryable 仍终止生成中状态并标记运行工具失败', ({ code, retryable }) => {
        useMessageStore.getState().appendStreamDelta('部分输出');
        useMessageStore.getState().startToolCall('failed-run-tool', 'Bash', { command: 'sleep 30' });
        dispatch({
            type: 'error',
            code,
            message: '内部错误',
            retryable,
        } as ServerMessage);

        const system = findSystemMessage();
        expect(system?.subtype).toBe('error');
        expect(system?.errorCode).toBe(code);
        expect(system?.retryable).toBe(retryable);
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
        expect(useSessionStore.getState().status).toBe('idle');
        expect(useMessageStore.getState().streamingMessageId).toBeNull();
        expect(useMessageStore.getState().activeToolCalls.size).toBe(0);
        const assistant = useMessageStore.getState().messages.find(m => m.type === 'assistant');
        const tool = assistant?.type === 'assistant'
            ? assistant.content.find(b => b.type === 'tool_use' && b.toolUseId === 'failed-run-tool')
            : undefined;
        expect(tool?.type === 'tool_use' ? tool.result : undefined)
            .toEqual({ content: '内部错误', isError: true });
    });

    it('error 事件将 error 条目迁移进消息内容并清空 map，不再跨 run 残留', () => {
        const s = useMessageStore.getState();
        s.appendStreamDelta('working');
        s.startToolCall('err-tool-1', 'Bash', { command: 'sleep 100' });
        s.startToolCall('err-tool-2', 'Read', { path: 'a.ts' });
        s.completeToolCall('err-tool-2', { content: 'ok', isError: false });

        dispatch({
            type: 'error',
            message: '上游 Provider 错误',
        } as ServerMessage);

        const state = useMessageStore.getState();
        // 原 running 条目：error 状态迁移进消息内容后从 activeToolCalls 清理，无残留
        expect(state.activeToolCalls.size).toBe(0);
        expect(state.activeToolCalls.has('err-tool-1')).toBe(false);
        const assistant = state.messages.find(m => m.type === 'assistant');
        // 迁移后的 tool_use block 携带合成 isError result（渲染为终态错误，不转圈）
        const failedBlock = assistant?.type === 'assistant'
            ? assistant.content.find(b => b.type === 'tool_use' && b.toolUseId === 'err-tool-1')
            : undefined;
        expect(failedBlock && failedBlock.type === 'tool_use' ? failedBlock.result : undefined)
            .toEqual({ content: '上游 Provider 错误', isError: true });
        // 已完成条目被 finalizeStream 正常迁移清理，result 已附加到 assistant 消息
        const migrated = assistant?.type === 'assistant'
            ? assistant.content.find(b => b.type === 'tool_use' && b.toolUseId === 'err-tool-2')
            : undefined;
        expect(migrated && migrated.type === 'tool_use' ? migrated.result?.content : undefined).toBe('ok');
    });

    it('两轮 run：第一轮 error 清理后，第二轮流式渲染不含旧工具卡片', () => {
        // 第一轮：流式 + running 工具 → error 事件
        useMessageStore.getState().appendStreamDelta('round 1');
        useMessageStore.getState().startToolCall('r1-tool', 'Grep', { pattern: 'foo' });
        dispatch({ type: 'error', message: '第一轮失败' } as ServerMessage);
        expect(useMessageStore.getState().activeToolCalls.has('r1-tool')).toBe(false);

        // 第二轮流式渲染（StreamingContent 遍历 activeToolCalls）仅含新条目，旧卡片无残留
        useMessageStore.getState().appendStreamDelta('round 2');
        useMessageStore.getState().startToolCall('r2-tool', 'Bash', { command: 'echo hi' });
        const state = useMessageStore.getState();
        expect(Array.from(state.activeToolCalls.keys())).toEqual(['r2-tool']);
    });
});
