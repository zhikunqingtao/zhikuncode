/**
 * useWebSocket — WebSocket STOMP 连接管理 (薄封装)
 * SPEC: §8.5 WebSocket 消息协议
 *
 * P1-01: 统一为 stompClient.ts 核心实现，本文件仅为 React Hook 适配层。
 * 所有连接管理、重连、心跳、消息解析逻辑均委托给 stompClient。
 */

import { useEffect, useCallback, useRef, useState } from 'react';
import {
    createStompClient,
    disconnectStomp,
    isConnected as stompIsConnected,
    sendToServer as stompSendToServer,
    isWsConnected as stompIsWsConnected,
} from '@/api/stompClient';
import { useSessionStore } from '@/store/sessionStore';

interface UseWebSocketOptions {
    onConnect?: () => void;
    onDisconnect?: () => void;
    onError?: (error: Error) => void;
}

// 模块级标志 — 防止 React StrictMode 双重挂载导致重复连接
let globalConnected = false;

/**
 * 模块级发送 — 委托给 stompClient.sendToServer
 * 保持向后兼容：可从任何地方调用（不限于 React 组件内）。
 */
export function sendToServer(destination: string, body: unknown): boolean {
    return stompSendToServer(destination, body);
}

/**
 * 模块级连接状态检查 — 委托给 stompClient.isWsConnected
 */
export function isWsConnected(): boolean {
    return stompIsWsConnected();
}

/**
 * useWebSocket Hook — 薄封装层
 *
 * 保留原有 hook 签名（参数和返回值类型）以保持向后兼容。
 * 内部实现全部委托给 stompClient，移除重复的连接管理/重连/心跳逻辑。
 */
export function useWebSocket(options: UseWebSocketOptions = {}) {
    const optionsRef = useRef(options);
    const [, setConnectedState] = useState(false);

    // Keep options ref up to date
    useEffect(() => {
        optionsRef.current = options;
    }, [options]);

    const connect = useCallback(() => {
        // 如果已连接，仅触发回调
        if (stompIsConnected()) {
            optionsRef.current.onConnect?.();
            setConnectedState(true);
            return;
        }

        // 防止重复连接
        if (globalConnected) {
            return;
        }
        globalConnected = true;

        // 获取 session 信息
        const sessionId = useSessionStore.getState().sessionId || 'default';
        const authToken = ''; // 当前无需 auth token，由 SockJS/STOMP 处理

        try {
            createStompClient(sessionId, authToken);
            optionsRef.current.onConnect?.();
            setConnectedState(true);
        } catch (err) {
            globalConnected = false;
            optionsRef.current.onError?.(err instanceof Error ? err : new Error(String(err)));
            setConnectedState(false);
        }
    }, []);

    const disconnect = useCallback(() => {
        // Note: 不主动断开以支持 React StrictMode — 连接将被复用
    }, []);

    const sendMessage = useCallback((destination: string, body: unknown) => {
        return stompSendToServer(destination, body);
    }, []);

    const isConnectedFn = useCallback(() => {
        return stompIsConnected();
    }, []);

    // 自动连接
    useEffect(() => {
        connect();
        return () => {
            disconnect();
        };
    }, [connect, disconnect]);

    return {
        connect,
        disconnect: () => {
            // 显式调用时才真正断开
            globalConnected = false;
            disconnectStomp();
            optionsRef.current.onDisconnect?.();
            setConnectedState(false);
        },
        sendMessage,
        isConnected: isConnectedFn,
    };
}
