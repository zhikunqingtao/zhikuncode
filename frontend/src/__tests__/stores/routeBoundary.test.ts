import { describe, it, expect, beforeEach } from 'vitest';
import { useSessionStore } from '../../store/sessionStore';
import { useMessageStore } from '../../store/messageStore';
import { useCostStore } from '../../store/costStore';
import { usePermissionStore } from '../../store/permissionStore';
import { useBridgeStore } from '../../store/bridgeStore';
import { useDialogStore } from '../../store/dialogStore';
import type { Message } from '@/types';

/**
 * TC-ROUTE-001~004: 路由边界测试（状态驱动路由）
 *
 * 前端无 React Router，使用状态驱动 UI 分支。
 * 测试聚焦状态驱动的"路由"边界。
 */

describe('State-based routing', () => {
    beforeEach(() => {
        useSessionStore.setState({
            sessionId: null, model: null, status: 'idle',
            turnCount: 0, effortValue: 3, isAborted: false,
        });
        useMessageStore.setState({
            messages: [], streamingMessageId: null, streamingContent: '',
            thinkingContent: '', activeToolCalls: new Map(), tokenBudgetState: null,
        });
        useCostStore.setState({
            sessionCost: 0, totalCost: 0,
            usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
        });
        usePermissionStore.setState({
            pendingPermission: null, permissionMode: 'default',
            denialTracking: { consecutiveDenials: 0, totalDenials: 0 },
        });
        useBridgeStore.setState({
            bridgeStatus: 'disconnected', reconnectAttempt: 0,
            nextRetryAt: null, connectedAt: null, lastError: null,
        });
        useDialogStore.setState({
            activeDialog: null, dialogData: {},
        });
    });

    it('TC-ROUTE-001: 切换会话时正确清理前一会话状态', () => {
        // 设置会话 A 的状态
        useSessionStore.setState({ sessionId: 'session-A' });
        const msg: Message = {
            uuid: 'msg-A', type: 'user',
            content: [{ type: 'text', text: 'msg in A' }],
            timestamp: Date.now(),
        } as Message;
        useMessageStore.getState().addMessage(msg);
        expect(useMessageStore.getState().messages).toHaveLength(1);

        // 切换到会话 B 并清理
        useSessionStore.setState({ sessionId: 'session-B' });
        useMessageStore.getState().clearMessages();

        expect(useMessageStore.getState().messages).toHaveLength(0);
        expect(useSessionStore.getState().sessionId).toBe('session-B');
    });

    it('TC-ROUTE-002: WebSocket 断开时 UI 正确降级', () => {
        useBridgeStore.getState().updateBridgeStatus({ status: 'disconnected', url: '' });
        expect(useBridgeStore.getState().bridgeStatus).toBe('disconnected');

        // 验证 error 状态
        useBridgeStore.getState().updateBridgeStatus({
            status: 'error', url: '', error: 'Network error',
        });
        expect(useBridgeStore.getState().bridgeStatus).toBe('error');
        expect(useBridgeStore.getState().lastError).toBe('Network error');
    });

    it('TC-ROUTE-003: 对话框正确打开/关闭/切换', () => {
        useDialogStore.getState().openDialog('permission', {
            toolName: 'Bash', requestId: 'req-001',
        });
        expect(useDialogStore.getState().activeDialog).toBe('permission');

        useDialogStore.getState().closeDialog();
        expect(useDialogStore.getState().activeDialog).toBeNull();

        useDialogStore.getState().openDialog('settings', {});
        expect(useDialogStore.getState().activeDialog).toBe('settings');
    });

    it('TC-ROUTE-004: 会话创建时多Store协调一致', () => {
        const newSessionId = 'session-new';

        // 模拟会话创建时的多 Store 协调
        useSessionStore.setState({ sessionId: newSessionId, model: 'qwen3.6-max-preview' });
        useMessageStore.getState().clearMessages();
        useCostStore.getState().resetSessionCost();
        usePermissionStore.getState().setPermissionMode('default');

        // 验证最终状态
        expect(useSessionStore.getState().sessionId).toBe(newSessionId);
        expect(useMessageStore.getState().messages).toHaveLength(0);
        expect(useCostStore.getState().sessionCost).toBe(0);
        expect(usePermissionStore.getState().permissionMode).toBe('default');
    });
});
