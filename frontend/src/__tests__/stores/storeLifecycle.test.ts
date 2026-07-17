import { describe, it, expect, beforeEach } from 'vitest';
import { useMessageStore } from '../../store/messageStore';
import { useSessionStore } from '../../store/sessionStore';
import { usePermissionStore } from '../../store/permissionStore';
import { useCostStore } from '../../store/costStore';
import { useTaskStore } from '../../store/taskStore';
import { useConfigStore } from '../../store/configStore';
import { useBridgeStore } from '../../store/bridgeStore';
import { useNotificationStore } from '../../store/notificationStore';
import type { Message, PermissionRequest, PermissionDecision, TaskState } from '@/types';

/**
 * TC-STORE-001~008: Store 状态流转综合测试
 */

// ==================== TC-STORE-001: messageStore 消息流生命周期 ====================

describe('TC-STORE-001: messageStore 消息流生命周期', () => {
    beforeEach(() => {
        useMessageStore.setState({
            messages: [],
            streamingMessageId: null,
            streamingContent: '',
            thinkingContent: '',
            activeToolCalls: new Map(),
            tokenBudgetState: null,
        });
    });

    it('addMessage 应正确添加用户消息', () => {
        const msg: Message = {
            uuid: 'test-1',
            type: 'user',
            content: [{ type: 'text', text: 'hello' }],
            timestamp: Date.now(),
        } as Message;
        useMessageStore.getState().addMessage(msg);

        const messages = useMessageStore.getState().messages;
        expect(messages).toHaveLength(1);
        expect(messages[0].uuid).toBe('test-1');
    });

    it('appendStreamDelta 应追加流式内容增量', () => {
        useMessageStore.getState().appendStreamDelta('Hello');
        useMessageStore.getState().appendStreamDelta(' World');

        const state = useMessageStore.getState();
        expect(state.streamingContent).toBe('Hello World');
        expect(state.streamingMessageId).toBeTruthy();
        expect(state.messages).toHaveLength(1);
    });

    it('finalizeStream 应正确结束流式传输', () => {
        useMessageStore.getState().appendStreamDelta('test content');
        useMessageStore.getState().finalizeStream({
            inputTokens: 100,
            outputTokens: 50,
            cacheReadInputTokens: 0,
            cacheCreationInputTokens: 0,
        });

        const state = useMessageStore.getState();
        expect(state.streamingMessageId).toBeNull();
        expect(state.streamingContent).toBe('');
    });
});

// ==================== TC-STORE-002: sessionStore 会话生命周期 ====================

describe('TC-STORE-002: sessionStore 会话生命周期', () => {
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

    it('resumeSession 正确切换会话', async () => {
        await useSessionStore.getState().resumeSession('session-1');
        useSessionStore.getState().setModel('qwen3.7-max');

        expect(useSessionStore.getState().sessionId).toBe('session-1');
        expect(useSessionStore.getState().model).toBe('qwen3.7-max');

        await useSessionStore.getState().resumeSession('session-2');
        expect(useSessionStore.getState().sessionId).toBe('session-2');
    });

    it('abort 设置 isAborted 状态', () => {
        useSessionStore.getState().abort();
        expect(useSessionStore.getState().isAborted).toBe(true);
        expect(useSessionStore.getState().status).toBe('idle');
    });
});

// ==================== TC-STORE-003: permissionStore 权限审批流程 ====================

describe('TC-STORE-003: permissionStore 权限审批流程', () => {
    beforeEach(() => {
        usePermissionStore.setState({
            pendingPermissions: [],
            permissionMode: 'default',
            denialTracking: { consecutiveDenials: 0, totalDenials: 0 },
        });
    });

    it('权限请求/审批/拒绝完整流程', () => {
        expect(usePermissionStore.getState().pendingPermissions.length).toBe(0);

        const request: PermissionRequest = {
            toolUseId: 'perm-001',
            toolName: 'Write',
            input: { path: 'test.txt' },
            riskLevel: 'medium',
            reason: '写入文件 test.txt',
        };
        usePermissionStore.getState().showPermission(request);
        expect(usePermissionStore.getState().pendingPermissions.length).toBe(1);
        expect(usePermissionStore.getState().pendingPermissions[0].toolName).toBe('Write');

        const allowDecision: PermissionDecision = { toolUseId: 'perm-001', decision: 'allow' };
        usePermissionStore.getState().respondPermission(allowDecision);
        expect(usePermissionStore.getState().pendingPermissions.length).toBe(0);

        // 拒绝场景
        usePermissionStore.getState().showPermission({
            toolUseId: 'perm-002',
            toolName: 'Bash',
            input: { command: 'rm -rf /' },
            riskLevel: 'high',
            reason: '危险命令',
        });
        const denyDecision: PermissionDecision = { toolUseId: 'perm-002', decision: 'deny' };
        usePermissionStore.getState().respondPermission(denyDecision);
        expect(usePermissionStore.getState().pendingPermissions.length).toBe(0);
        expect(usePermissionStore.getState().denialTracking.totalDenials).toBe(1);
    });

    it('permission terminal/REST race never removes the next queued request', () => {
        usePermissionStore.getState().showPermission({ interactionId: 'i-1', toolUseId: 't-1',
            toolName: 'Bash', input: {}, riskLevel: 'low', reason: 'first' });
        usePermissionStore.getState().showPermission({ interactionId: 'i-2', toolUseId: 't-2',
            toolName: 'Bash', input: {}, riskLevel: 'low', reason: 'second' });
        usePermissionStore.getState().removeInteraction('i-1');
        usePermissionStore.getState().respondPermission({ toolUseId: 't-1', decision: 'allow' }, 'i-1');
        expect(usePermissionStore.getState().pendingPermissions.map(p => p.interactionId)).toEqual(['i-2']);
    });
});

// ==================== TC-STORE-004: costStore Token 费用累计 ====================

describe('TC-STORE-004: costStore Token 费用累计', () => {
    beforeEach(() => {
        useCostStore.setState({
            sessionCost: 0,
            totalCost: 0,
            usage: { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
        });
    });

    it('Token 费用正确累计', () => {
        useCostStore.getState().updateCost({
            sessionCost: 0.005,
            totalCost: 0.005,
            usage: { inputTokens: 1000, outputTokens: 200, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
        });
        expect(useCostStore.getState().sessionCost).toBe(0.005);

        useCostStore.getState().updateCost({
            sessionCost: 0.017,
            totalCost: 0.017,
            usage: { inputTokens: 3000, outputTokens: 700, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
        });
        expect(useCostStore.getState().sessionCost).toBe(0.017);
        expect(useCostStore.getState().totalCost).toBe(0.017);
    });

    it('resetSessionCost 重置会话费用', () => {
        useCostStore.getState().updateCost({
            sessionCost: 0.1,
            totalCost: 0.5,
            usage: { inputTokens: 5000, outputTokens: 1000, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
        });
        useCostStore.getState().resetSessionCost();

        expect(useCostStore.getState().sessionCost).toBe(0);
        expect(useCostStore.getState().usage.inputTokens).toBe(0);
    });
});

// ==================== TC-STORE-005: taskStore 任务状态管理 ====================

describe('TC-STORE-005: taskStore 任务状态管理', () => {
    beforeEach(() => {
        useTaskStore.setState({
            tasks: new Map(),
            foregroundedTaskId: null,
            viewingAgentTaskId: null,
            agentNameRegistry: new Map(),
        });
    });

    it('任务创建/更新/前台切换', () => {
        const task: TaskState = {
            taskId: 'task-1',
            status: 'pending',
            createdAt: Date.now(),
        };
        useTaskStore.getState().addTask(task);
        expect(useTaskStore.getState().tasks.get('task-1')).toBeDefined();

        useTaskStore.getState().updateTask('task-1', { status: 'running' });
        expect(useTaskStore.getState().tasks.get('task-1')!.status).toBe('running');

        useTaskStore.getState().setForegroundedTask('task-1');
        expect(useTaskStore.getState().foregroundedTaskId).toBe('task-1');
    });
});

// ==================== TC-STORE-006: configStore 持久化与恢复 ====================

describe('TC-STORE-006: configStore 持久化与恢复', () => {
    it('setTheme 和 setLocale 正确更新状态', () => {
        useConfigStore.getState().setTheme({ mode: 'dark' });
        useConfigStore.getState().setLocale('zh-CN');

        const state = useConfigStore.getState();
        expect(state.theme.mode).toBe('dark');
        expect(state.locale).toBe('zh-CN');
    });

    it('localStorage 持久化写入', () => {
        useConfigStore.getState().setTheme({ mode: 'light' });
        // persist middleware should write to localStorage
        const stored = localStorage.getItem('ai-coder-config');
        expect(stored).toBeTruthy();
        if (stored) {
            const parsed = JSON.parse(stored);
            expect(parsed.state.theme.mode).toBe('light');
        }
    });
});

// ==================== TC-STORE-007: bridgeStore WebSocket 连接状态管理 ====================

describe('TC-STORE-007: bridgeStore WebSocket 连接状态管理', () => {
    beforeEach(() => {
        useBridgeStore.setState({
            bridgeStatus: 'disconnected',
            reconnectAttempt: 0,
            nextRetryAt: null,
            connectedAt: null,
            lastError: null,
        });
    });

    it('updateBridgeStatus 连接成功应设置 connectedAt 并清空重连状态', () => {
        // 先模拟重连中状态
        useBridgeStore.getState().updateBridgeStatus({
            status: 'reconnecting', url: '', attempt: 3, nextRetryAt: Date.now() + 5000,
        });

        // 连接成功
        const now = Date.now();
        useBridgeStore.getState().updateBridgeStatus({
            status: 'connected', url: 'ws://localhost:8080', connectedAt: now,
        });

        const state = useBridgeStore.getState();
        expect(state.bridgeStatus).toBe('connected');
        expect(state.connectedAt).toBe(now);
        expect(state.reconnectAttempt).toBe(0);       // 自动清零
        expect(state.nextRetryAt).toBeNull();           // 自动清空
        expect(state.lastError).toBeNull();             // 自动清空
    });

    it('updateBridgeStatus 断连应记录错误信息', () => {
        useBridgeStore.getState().updateBridgeStatus({
            status: 'error', url: '', error: 'Connection refused',
        });

        const state = useBridgeStore.getState();
        expect(state.bridgeStatus).toBe('error');
        expect(state.lastError).toBe('Connection refused');
    });

    it('updateBridgeStatus 重连中应递增重连次数', () => {
        useBridgeStore.getState().updateBridgeStatus({ status: 'reconnecting', url: '', attempt: 1 });
        expect(useBridgeStore.getState().reconnectAttempt).toBe(1);

        useBridgeStore.getState().updateBridgeStatus({
            status: 'reconnecting', url: '', attempt: 2, nextRetryAt: Date.now() + 10000,
        });
        const state = useBridgeStore.getState();
        expect(state.reconnectAttempt).toBe(2);
        expect(state.nextRetryAt).toBeGreaterThan(0);
    });

    it('状态流转完整路径: disconnected → reconnecting → connected → disconnected', () => {
        expect(useBridgeStore.getState().bridgeStatus).toBe('disconnected');

        useBridgeStore.getState().updateBridgeStatus({ status: 'reconnecting', url: '', attempt: 1 });
        expect(useBridgeStore.getState().bridgeStatus).toBe('reconnecting');

        useBridgeStore.getState().updateBridgeStatus({
            status: 'connected', url: 'ws://localhost:8080', connectedAt: Date.now(),
        });
        expect(useBridgeStore.getState().bridgeStatus).toBe('connected');

        useBridgeStore.getState().updateBridgeStatus({ status: 'disconnected', url: '' });
        expect(useBridgeStore.getState().bridgeStatus).toBe('disconnected');
    });
});

// ==================== TC-STORE-008: notificationStore 通知队列管理 ====================

describe('TC-STORE-008: notificationStore 通知队列管理', () => {
    beforeEach(() => {
        useNotificationStore.setState({ notifications: [] });
    });

    it('通知添加/删除/清空', () => {
        useNotificationStore.getState().addNotification({
            key: 'n-1', level: 'info', message: '测试通知',
        });
        useNotificationStore.getState().addNotification({
            key: 'n-2', level: 'error', message: '错误通知',
        });
        expect(useNotificationStore.getState().notifications).toHaveLength(2);

        useNotificationStore.getState().removeNotification('n-1');
        expect(useNotificationStore.getState().notifications).toHaveLength(1);

        useNotificationStore.getState().clearAll();
        expect(useNotificationStore.getState().notifications).toHaveLength(0);
    });
});
