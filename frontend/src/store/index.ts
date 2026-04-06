/**
 * Store 统一导出 + 会话切换清理 + 副作用 + 选择器
 * SPEC: §8.3, §8.3.1
 */

// ── Store 导出 ──
export { useSessionStore } from './sessionStore';
export { useMessageStore } from './messageStore';
export { usePermissionStore } from './permissionStore';
export { useConfigStore } from './configStore';
export { useCostStore } from './costStore';
export { useTaskStore } from './taskStore';
export { useAppUiStore } from './appUiStore';
export { useBridgeStore } from './bridgeStore';
export { useNotificationStore } from './notificationStore';
export { useInboxStore } from './inboxStore';
export { useMcpStore } from './mcpStore';

// ── Store 接口类型导出 ──
export type { SessionStoreState } from './sessionStore';
export type { MessageStoreState } from './messageStore';
export type { PermissionStoreState } from './permissionStore';
export type { ConfigStoreState } from './configStore';
export type { CostStoreState } from './costStore';
export type { TaskStoreState } from './taskStore';
export type { AppUiStoreState } from './appUiStore';
export type { BridgeStoreState } from './bridgeStore';
export type { NotificationStoreState } from './notificationStore';
export type { InboxStoreState } from './inboxStore';
export type { McpStoreState } from './mcpStore';

import { useMessageStore } from './messageStore';
import { usePermissionStore } from './permissionStore';
import { useCostStore } from './costStore';
import { useTaskStore } from './taskStore';
import { useAppUiStore } from './appUiStore';
import type { TaskState, InputTarget } from '@/types';
import type { TaskStoreState } from './taskStore';
import type { SessionStoreState } from './sessionStore';

// ══════════════════════════════════════════════════════════
// §8.3.1 会话切换时 Store 清理
// ══════════════════════════════════════════════════════════

/**
 * clearStores() — 会话切换时清理所有会话相关状态。
 *
 * 清理原则:
 * - 会话相关 Store (message/permission/cost/task/appUi): 全部 reset
 * - 全局 Store (config/notification/inbox/mcp): 不清除
 * - bridgeStore: 由新连接覆盖
 * - sessionStore: 由新连接的 resumeSession() 覆盖
 */
export function clearStores(): void {
    // ===== 必须清除的 (会话绑定) =====
    useMessageStore.getState().clearMessages();
    usePermissionStore.getState().clearPendingPermission();
    useTaskStore.setState({ tasks: new Map(), foregroundedTaskId: null, viewingAgentTaskId: null });
    useCostStore.getState().resetSessionCost();
    useAppUiStore.getState().dismissElicitationDialog();

    // ===== 不清除的 (全局/跨会话) =====
    // configStore    — 全局配置，persist 到 localStorage
    // notificationStore — 跨会话通知
    // inboxStore     — Swarm 消息跨会话
    // mcpStore       — MCP 连接跨会话
}

// ══════════════════════════════════════════════════════════
// §8.3 选择器 — 纯函数派生状态
// ══════════════════════════════════════════════════════════

/** 获取当前查看的同伴任务 */
export function getViewedTeammateTask(state: TaskStoreState): TaskState | null {
    if (!state.viewingAgentTaskId) return null;
    return state.tasks.get(state.viewingAgentTaskId) ?? null;
}

/** 确定输入路由目标 — 区别联合类型 */
export function getActiveAgentForInput(state: TaskStoreState & SessionStoreState): InputTarget {
    if (state.viewingAgentTaskId) {
        const task = state.tasks.get(state.viewingAgentTaskId);
        if (task?.status === 'running') {
            return task.isCoordinator
                ? { type: 'coordinator', taskId: state.viewingAgentTaskId }
                : { type: 'agent', taskId: state.viewingAgentTaskId };
        }
    }
    return { type: 'main' };
}
