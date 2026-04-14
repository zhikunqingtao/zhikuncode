/**
 * STOMP 客户端 — @stomp/stompjs + SockJS fallback
 * SPEC: §8.5.3, §8.5.0
 *
 * 功能:
 * - SockJS fallback (非原生 WebSocket API)
 * - 心跳: incoming/outgoing 10s
 * - 断线自动重连 (指数退避 1s→2s→4s→8s→10s cap)
 * - 消息序列号校验
 * - 便捷发送方法 (chat/permission/interrupt 等)
 */

import { Client as StompClient, IFrame, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { dispatch, resetSequence } from './dispatch';
import type { ServerMessage } from '@/types';

/**
 * 防御性消息解析 — 处理心跳、SockJS 协议帧等非 JSON 消息
 * 对齐 P-FE-02 修复方案
 */
function parseMessage(raw: string): (ServerMessage & { ts?: number }) | null {
    // 防御 null/undefined/空字符串
    if (!raw || raw.trim() === '') {
        return null;
    }

    // 跳过心跳消息
    if (raw === '\n' || raw === 'h') {
        return null;
    }

    try {
        return JSON.parse(raw);
    } catch {
        // 降级1: 尝试从 STOMP 帧中提取 body（支持多行 JSON）
        const bodyMatch = raw.match(/\n\n([\s\S]+?)\u0000/);
        if (bodyMatch) {
            try {
                return JSON.parse(bodyMatch[1]);
            } catch {
                // 静默忽略
            }
        }
        // 降级2: 尝试提取最后一个 JSON 对象
        const jsonMatch = raw.match(/(\{[\s\S]*\})\s*\u0000?$/);
        if (jsonMatch) {
            try {
                return JSON.parse(jsonMatch[1]);
            } catch {
                // 静默忽略
            }
        }
        // 非 JSON 消息（SockJS 协议帧等）静默忽略
        if (raw.length > 2) {
            console.debug('[STOMP] Non-JSON message ignored:', raw.substring(0, 80));
        }
        return null;
    }
}
import { useBridgeStore } from '@/store/bridgeStore';
import { useNotificationStore } from '@/store/notificationStore';
import type { Attachment } from '@/types';

/** 重连延迟配置 */
const RECONNECT_DELAY_INITIAL = 1000;    // 初始 1s
const RECONNECT_DELAY_MAX = 10000;       // 最大 10s
const RECONNECT_TIMEOUT = 10 * 60 * 1000; // 重连超时 10min

/** STOMP 客户端单例 */
let stompClient: StompClient | null = null;
let reconnectAttempts = 0;
let reconnectStartTime = 0;

/**
 * 创建并激活 STOMP 客户端连接
 */
export function createStompClient(sessionId: string, authToken: string): StompClient {
    // 如果已有连接，先断开
    if (stompClient?.active) {
        stompClient.deactivate();
    }

    reconnectAttempts = 0;
    reconnectStartTime = 0;

    const client = new StompClient({
        // SockJS fallback — 对齐 §8.5.0
        webSocketFactory: () => new SockJS('/ws') as WebSocket,

        // CONNECT 帧 headers — 对齐 §8.5.4
        connectHeaders: {
            Authorization: `Bearer ${authToken}`,
            'X-Session-Id': sessionId,
        },

        // 心跳配置 — 对齐 §8.5.4 configureMessageBroker
        heartbeatIncoming: 10000,   // 服务端心跳间隔 10s
        heartbeatOutgoing: 10000,   // 客户端心跳间隔 10s

        // 重连延迟 — 指数退避
        reconnectDelay: RECONNECT_DELAY_INITIAL,

        // 连接成功回调
        onConnect: () => {
            reconnectAttempts = 0;
            reconnectStartTime = 0;

            // 重置序列号校验
            resetSequence();

            // 更新连接状态
            useBridgeStore.getState().updateBridgeStatus({ status: 'connected', url: '' });

            // 移除断线警告通知
            useNotificationStore.getState().removeNotification('disconnect-warning');

            // 订阅用户专属消息队列 — 对齐 §8.5.3 onConnected
            client.subscribe('/user/queue/messages', (message: IMessage) => {
                const data = parseMessage(message.body);
                if (data) {
                    dispatch(data);
                }
            });
        },

        // STOMP 错误回调
        onStompError: (frame: IFrame) => {
            console.error('[WS] STOMP error:', frame.headers['message'], frame.body);
            useNotificationStore.getState().addNotification({
                key: 'stomp-error',
                level: 'error',
                message: `WebSocket 错误: ${frame.headers['message'] || 'Unknown error'}`,
                timeout: 10000,
            });
        },

        // WebSocket 关闭回调
        onWebSocketClose: () => {
            if (reconnectStartTime === 0) {
                reconnectStartTime = Date.now();
            }

            reconnectAttempts++;

            // 更新连接状态
            if (reconnectAttempts === 1) {
                useBridgeStore.getState().updateBridgeStatus({ status: 'disconnected', url: '' });
                useNotificationStore.getState().addNotification({
                    key: 'disconnect-warning',
                    level: 'warning',
                    message: '连接已断开，正在尝试重连...',
                    timeout: 0,  // 不自动消失
                });
            } else {
                useBridgeStore.getState().updateBridgeStatus({ status: 'reconnecting', url: '' });
            }

            // 重连超时检测 (10min)
            if (Date.now() - reconnectStartTime > RECONNECT_TIMEOUT) {
                client.deactivate();
                useBridgeStore.getState().updateBridgeStatus({ status: 'disconnected', url: '' });
                useNotificationStore.getState().removeNotification('disconnect-warning');
                useNotificationStore.getState().addNotification({
                    key: 'reconnect-failed',
                    level: 'error',
                    message: '连接已断开，请刷新页面重试',
                    timeout: 0,
                });
                return;
            }

            // 指数退避重连延迟
            const delay = Math.min(
                RECONNECT_DELAY_INITIAL * Math.pow(2, reconnectAttempts - 1),
                RECONNECT_DELAY_MAX
            );
            client.reconnectDelay = delay;
        },
    });

    client.activate();
    stompClient = client;
    return client;
}

/**
 * 断开 STOMP 连接
 */
export function disconnectStomp(): void {
    if (stompClient?.active) {
        stompClient.deactivate();
    }
    stompClient = null;
}

/**
 * 获取当前 STOMP 客户端实例
 */
export function getStompClient(): StompClient | null {
    return stompClient;
}

/**
 * 检查连接状态
 */
export function isConnected(): boolean {
    return stompClient?.active ?? false;
}

// ==================== 便捷发送方法 — 对齐 §8.5.2 ====================

/** 发送原始 STOMP 消息 */
export function send(destination: string, body: object): void {
    if (!stompClient?.active) {
        console.warn('[WS] Cannot send: not connected');
        return;
    }
    stompClient.publish({
        destination,
        body: JSON.stringify(body),
    });
}

/** #1 发送用户消息 → /app/chat */
export function sendUserMessage(text: string, attachments?: Attachment[], references?: Array<{ type: string; path: string }>): void {
    send('/app/chat', { text, attachments, references });
}

/** #2 发送权限响应 → /app/permission */
export function sendPermissionResponse(
    toolUseId: string,
    decision: 'allow' | 'deny' | 'allow_always',
    remember?: boolean,
    scope?: string
): void {
    send('/app/permission', { toolUseId, decision, remember, scope });
}

/** #3 发送中断 → /app/interrupt */
export function sendInterrupt(): void {
    send('/app/interrupt', {});
}

/** #4 切换模型 → /app/model */
export function sendSetModel(model: string): void {
    send('/app/model', { model });
}

/** #5 切换权限模式 → /app/permission-mode */
export function sendSetPermissionMode(mode: string): void {
    send('/app/permission-mode', { mode });
}

/** #6 Slash 命令 → /app/command */
export function sendSlashCommand(command: string, args: string): void {
    send('/app/command', { command, args });
}

/** #7 MCP 操作 → /app/mcp */
export function sendMcpOperation(operation: string, serverId: string, config?: object): void {
    send('/app/mcp', { operation, serverId, config });
}

/** #8 回退文件 → /app/rewind */
export function sendRewindFiles(messageId: string, filePaths: string[]): void {
    send('/app/rewind', { messageId, filePaths });
}

/** #9 AI 反向提问响应 → /app/elicitation */
export function sendElicitationResponse(requestId: string, answer: string | string[] | null): void {
    send('/app/elicitation', { requestId, answer });
}

/** #10 心跳探测 → /app/ping */
export function sendPing(): void {
    send('/app/ping', {});
}
