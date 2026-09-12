/**
 * error 事件契约测试
 * 契约: type="error", payload = { message: string(人类可读中文),
 *   errorCode?: "PROVIDER_PAYMENT_REQUIRED"|"PROVIDER_FORBIDDEN"|"PROVIDER_RATE_LIMITED"|"PROVIDER_ERROR",
 *   httpStatus?: number }
 * errorCode 存在 → 醒目 provider_error 横幅 + 常驻通知；缺失 → 保持既有行为。
 * 任何 error 事件都必须终止“生成中”状态。
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { dispatch } from '@/api/dispatch';
import { useMessageStore } from '@/store/messageStore';
import { useNotificationStore } from '@/store/notificationStore';
import { useSessionStore } from '@/store/sessionStore';
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

    it('errorCode 缺失时保持既有行为（向后兼容），仍终止生成中状态', () => {
        dispatch({
            type: 'error',
            code: 'INTERNAL_ERROR',
            message: '内部错误',
            retryable: true,
        } as ServerMessage);

        const system = findSystemMessage();
        expect(system?.subtype).toBe('error');
        expect(system?.errorCode).toBe('INTERNAL_ERROR');
        expect(system?.retryable).toBe(true);
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
        expect(useSessionStore.getState().status).toBe('idle');
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
