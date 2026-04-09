/**
 * @deprecated Use '@/api/dispatch' instead. This file is kept for backward compatibility.
 * All message dispatching logic has been consolidated into dispatch.ts.
 * messageDispatcher — 25 种 ServerMessage 分发器
 * SPEC: §8.5.3 消息分发机制
 */

import type { ServerMessage } from '@/types';
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

/**
 * dispatchServerMessage — 将 ServerMessage 分发到对应的 Store
 */
export function dispatchServerMessage(message: ServerMessage): void {
    console.log('[Dispatcher]', message.type, message);

    switch (message.type) {
        // ===== messageStore (5) =====
        case 'stream_delta':
            useMessageStore.getState().appendStreamDelta(message.delta);
            break;

        case 'thinking_delta':
            useMessageStore.getState().appendThinkingDelta(message.delta);
            break;

        case 'tool_use_start':
            useMessageStore.getState().startToolCall(
                message.toolUseId,
                message.toolName,
                message.input
            );
            break;

        case 'tool_use_progress':
            useMessageStore.getState().updateToolCallProgress(
                message.toolUseId,
                message.progress
            );
            break;

        case 'tool_result':
            useMessageStore.getState().completeToolCall(message.toolUseId, (message as unknown as { result?: { content: string; isError: boolean } }).result ?? {
                content: message.content,
                isError: message.isError,
            });
            break;

        // ===== sessionStore (3) =====
        case 'compact_start':
            useSessionStore.getState().setStatus('compacting');
            break;

        case 'compact_complete':
            useSessionStore.getState().setStatus('idle');
            break;

        case 'rate_limit':
            useSessionStore.getState().handleRateLimit({
                retryAfterMs: message.retryAfterMs,
                limitType: message.limitType,
            });
            break;

        // ===== permissionStore (1) =====
        case 'permission_request':
            usePermissionStore.getState().showPermission({
                toolUseId: message.toolUseId,
                toolName: message.toolName,
                input: message.input,
                riskLevel: message.riskLevel,
                reason: message.reason,
            });
            break;

        // ===== costStore (1) =====
        case 'cost_update':
            useCostStore.getState().updateCost({
                sessionCost: message.sessionCost,
                totalCost: message.totalCost,
                usage: message.usage,
            });
            break;

        // ===== taskStore (4) =====
        case 'task_update':
            useTaskStore.getState().updateTask(message.taskId, {
                status: message.status as 'pending' | 'running' | 'completed' | 'failed' | 'cancelled',
                progress: message.progress,
                result: message.result,
            });
            break;

        case 'agent_spawn':
            useTaskStore.getState().addTask({
                taskId: message.taskId,
                status: 'running',
                agentName: message.agentName,
                agentType: message.agentType,
                isCoordinator: message.agentType === 'coordinator',
                createdAt: Date.now(),
            });
            break;

        case 'agent_update':
            useTaskStore.getState().updateTask(message.taskId, {
                progress: message.progress,
            });
            break;

        case 'agent_complete':
            useTaskStore.getState().updateTask(message.taskId, {
                status: 'completed',
                result: message.result,
            });
            break;

        // ===== appUiStore (2) =====
        case 'elicitation':
            useAppUiStore.getState().showElicitationDialog({
                requestId: message.requestId,
                question: message.question,
                options: message.options,
            });
            break;

        case 'prompt_suggestion':
            useAppUiStore.getState().setPromptSuggestion({
                text: message.text,
                promptId: message.promptId,
                generationRequestId: message.generationRequestId,
                shownAt: null,
                acceptedAt: null,
            });
            break;

        // ===== bridgeStore (1) =====
        case 'bridge_status':
            useBridgeStore.getState().updateBridgeStatus({ status: message.status, url: message.url });
            break;

        // ===== notificationStore (1) =====
        case 'notification':
            useNotificationStore.getState().addNotification({
                key: message.key,
                level: message.level,
                message: message.message,
                priority: message.priority || 'normal',
                timeout: message.timeout || 5000,
            });
            break;

        // ===== inboxStore (1) =====
        case 'teammate_message':
            useInboxStore.getState().addInboxMessage({
                fromId: message.fromId,
                content: message.content,
            });
            break;

        // ===== mcpStore (1) =====
        case 'mcp_tool_update':
            useMcpStore.getState().updateMcpTools({ serverId: message.serverId, tools: message.tools });
            break;

        // ===== 跨 Store (2) =====
        case 'session_restored':
            // Restore messages
            useMessageStore.setState({ messages: message.messages });
            // Restore session
            useSessionStore.setState({
                sessionId: message.metadata.sessionId,
                model: message.metadata.model,
                status: message.metadata.status as 'idle' | 'streaming' | 'waiting_permission' | 'compacting',
            });
            break;

        case 'message_complete':
            useMessageStore.getState().finalizeStream(message.usage);
            useSessionStore.getState().setStatus('idle');
            break;

        // ===== 其他 (2) =====
        case 'pong':
            // Heartbeat response, no action needed
            break;

        case 'error':
            console.error('[Server Error]', message.code, message.message);
            useNotificationStore.getState().addNotification({
                key: `error-${Date.now()}`,
                level: 'error',
                message: message.message,
                priority: 'high',
                timeout: 10000,
            });
            break;

        default:
            console.warn('[Dispatcher] Unknown message type:', (message as {type: string}).type);
    }
}
