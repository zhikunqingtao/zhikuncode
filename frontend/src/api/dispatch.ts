/**
 * WebSocket 消息分发器 — 覆盖全部 25 种 Server→Client 消息类型
 * SPEC: §8.5.3 dispatch 函数
 *
 * 按 type 字段分发到对应 Zustand Store，
 * 跨 Store 消息通过私有 handle* 方法协调。
 */

import type { Message, ServerMessage, Usage, PermissionRequest } from '@/types';
import { useMessageStore } from '@/store/messageStore';
import { useSessionStore } from '@/store/sessionStore';
import { usePermissionStore } from '@/store/permissionStore';
import { useCostStore } from '@/store/costStore';
import { useTaskStore } from '@/store/taskStore';
import { useAppUiStore } from '@/store/appUiStore';
import { useBridgeStore } from '@/store/bridgeStore';
import { useNotificationStore } from '@/store/notificationStore';
import { useInboxStore } from '@/store/inboxStore';
import { useMcpStore } from '@/store/mcpStore';

/** 序列号校验器 — 检测乱序/丢失消息 */
let lastSeqTs = 0;

/**
 * dispatch — 按 type 字段分发 Server→Client 消息到对应 Store。
 * @param data 原始 JSON body (WsMessage 格式: { type, ts, ...payload })
 */
export function dispatch(data: ServerMessage & { ts?: number }): void {
    // 序列号/时间戳校验
    if (data.ts) {
        if (data.ts < lastSeqTs) {
            console.warn(`[WS] Out-of-order message: ts=${data.ts} < lastTs=${lastSeqTs}, type=${data.type}`);
        }
        lastSeqTs = data.ts;
    }

    const handler = handlers[data.type];
    if (handler) {
        handler(data as never);
    } else {
        console.warn(`[WS] Unknown message type: ${data.type}`);
    }
}

/** 重置序列号 (断线重连时调用) */
export function resetSequence(): void {
    lastSeqTs = 0;
}

// ==================== 事件分发表 — 覆盖全部 25 种消息 ====================

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const handlers: Record<string, (data: any) => void> = {
    // === messageStore (5 种) ===
    'stream_delta':       (d) => useMessageStore.getState().appendStreamDelta(d.delta),
    'thinking_delta':     (d) => useMessageStore.getState().appendThinkingDelta(d.delta),
    'tool_use_start':     (d) => useMessageStore.getState().startToolCall(d.toolUseId, d.toolName, d.input),
    'tool_use_progress':  (d) => useMessageStore.getState().updateToolCallProgress(d.toolUseId, d.progress),
    'tool_result':        (d) => useMessageStore.getState().completeToolCall(d.toolUseId, d.result),

    // === messageStore + sessionStore (2 种) ===
    'error':              (d) => handleError(d),
    'compact_complete':   (d) => handleCompactComplete(d),

    // === messageStore + sessionStore (1 种) ===
    'message_complete':   (d) => handleMessageComplete(d),

    // === sessionStore (2 种) ===
    'compact_start':      ()  => useSessionStore.getState().setStatus('compacting'),
    'rate_limit':         (d) => useSessionStore.getState().handleRateLimit(d),

    // === permissionStore + sessionStore (1 种) ===
    'permission_request': (d) => handlePermissionRequest(d),

    // === costStore (1 种) ===
    'cost_update':        (d) => useCostStore.getState().updateCost(d),

    // === taskStore (4 种) ===
    'task_update':        (d) => useTaskStore.getState().updateTask(d.taskId, d),
    'agent_spawn':        (d) => useTaskStore.getState().addAgentTask(d),
    'agent_update':       (d) => useTaskStore.getState().updateAgentTask(d.taskId, d.progress),
    'agent_complete':     (d) => useTaskStore.getState().completeAgentTask(d.taskId, d.result),

    // === appUiStore (3 种) ===
    'elicitation':        (d) => useAppUiStore.getState().showElicitationDialog(d),
    'prompt_suggestion':  (d) => useAppUiStore.getState().setPromptSuggestion(d),
    'speculation_result': (d) => useAppUiStore.getState().updateSpeculation(d),

    // === bridgeStore (1 种) ===
    'bridge_status':      (d) => useBridgeStore.getState().updateBridgeStatus(d),

    // === notificationStore (1 种) ===
    'notification':       (d) => useNotificationStore.getState().addNotification(d),

    // === inboxStore (1 种) ===
    'teammate_message':   (d) => useInboxStore.getState().addInboxMessage(d),

    // === mcpStore (1 种) ===
    'mcp_tool_update':    (d) => useMcpStore.getState().updateMcpTools(d),

    // === 断线重连 (1 种) ===
    'session_restored':   (d) => handleSessionRestore(d),

    // === 心跳 (1 种) ===
    'pong':               ()  => { /* 连接存活确认, 重置超时计时器 */ },
};

// ==================== 跨 Store 私有方法 ====================

/** 权限请求 — permissionStore + sessionStore */
function handlePermissionRequest(data: PermissionRequest): void {
    usePermissionStore.getState().showPermission(data);
    useSessionStore.getState().setStatus('waiting_permission');
}

/**
 * 助手回合完成 — messageStore + sessionStore
 * v1.53.0: 不再更新 costStore，费用由 #15 cost_update 权威推送
 */
function handleMessageComplete(data: { usage: Usage; stopReason: string }): void {
    useMessageStore.getState().finalizeStream(data.usage);
    if (data.stopReason === 'end_turn') {
        useSessionStore.getState().setStatus('idle');
    }
    // stopReason === 'tool_use' 时保持 streaming 状态，等待工具结果
}

/** API 错误 — messageStore + sessionStore */
function handleError(data: { code: string; message: string; retryable: boolean }): void {
    useMessageStore.getState().addMessage({
        type: 'system',
        uuid: crypto.randomUUID(),
        timestamp: Date.now(),
        content: data.message,
        subtype: 'error',
        errorCode: data.code,
        retryable: data.retryable,
    } as Message);
    useSessionStore.getState().setStatus('idle');
}

/** 上下文压缩完成 — messageStore + sessionStore */
function handleCompactComplete(data: { summary: string; tokensSaved: number }): void {
    useSessionStore.getState().setStatus('idle');
    useMessageStore.getState().addMessage({
        type: 'system',
        uuid: crypto.randomUUID(),
        timestamp: Date.now(),
        content: `上下文已压缩，节省 ${data.tokensSaved} tokens`,
        subtype: 'compact_boundary',
    } as Message);
}

/**
 * 断线重连恢复 — 全量同步
 * messageStore + sessionStore + bridgeStore + notificationStore
 */
function handleSessionRestore(data: {
    messages: Message[];
    metadata: {
        sessionId: string;
        model: string;
        status: 'idle' | 'interrupted';
    };
}): void {
    // 1. 重置序列号
    resetSequence();

    // 2. 清除旧状态 → 加载完整消息历史
    useMessageStore.getState().clearMessages();
    data.messages.forEach(msg => useMessageStore.getState().addMessage(msg));

    // 3. 恢复会话元数据
    useSessionStore.getState().resumeSession(data.metadata.sessionId);
    useSessionStore.getState().setModel(data.metadata.model);

    // 4. 恢复状态
    if (data.metadata.status === 'interrupted') {
        useSessionStore.getState().setStatus('idle');
        useNotificationStore.getState().addNotification({
            key: 'session-restore-interrupted',
            level: 'warning',
            message: 'AI 输出在断线期间被中断，你可以发送消息继续对话',
            timeout: 8000,
        });
    } else {
        useSessionStore.getState().setStatus('idle');
    }

    // 5. 更新连接状态
    useBridgeStore.getState().updateBridgeStatus({ status: 'connected', url: '' });
}
