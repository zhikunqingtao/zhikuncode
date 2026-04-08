/**
 * useWebSocket — WebSocket STOMP 连接管理
 * SPEC: §8.5 WebSocket 消息协议
 */

import { useEffect, useRef, useCallback } from 'react';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { dispatchServerMessage } from '@/utils/messageDispatcher';

interface UseWebSocketOptions {
    onConnect?: () => void;
    onDisconnect?: () => void;
    onError?: (error: Error) => void;
}

// Module-level singleton to survive React StrictMode
let globalClient: Client | null = null;
let globalSubscription: StompSubscription | null = null;
let connectionCount = 0;

/**
 * 模块级发送 — 可从任何地方调用（不限于 React 组件内）。
 */
export function sendToServer(destination: string, body: unknown): boolean {
    if (!globalClient?.active) {
        console.error('[WebSocket] sendToServer: not connected');
        return false;
    }
    console.log('[WebSocket] sendToServer:', destination, body);
    globalClient.publish({
        destination,
        body: JSON.stringify(body),
    });
    return true;
}

/**
 * 模块级连接状态检查。
 */
export function isWsConnected(): boolean {
    return globalClient?.active ?? false;
}

export function useWebSocket(options: UseWebSocketOptions = {}) {
    const optionsRef = useRef(options);
    const instanceId = useRef(++connectionCount);
    
    // Keep options ref up to date
    useEffect(() => {
        optionsRef.current = options;
    }, [options]);

    const connect = useCallback(() => {
        // If global client exists and is active, just call onConnect
        if (globalClient?.active) {
            console.log(`[WebSocket][${instanceId.current}] Using existing connection`);
            optionsRef.current.onConnect?.();
            return;
        }

        // If connecting, wait for it
        if (globalClient && !globalClient.active) {
            console.log(`[WebSocket][${instanceId.current}] Connection in progress, waiting...`);
            return;
        }

        console.log(`[WebSocket][${instanceId.current}] Creating new connection`);

        const client = new Client({
            webSocketFactory: () => new SockJS('/ws'),
            reconnectDelay: 5000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
            onConnect: () => {
                console.log(`[WebSocket][${instanceId.current}] Connected`);
                
                // Subscribe to user queue (only once globally)
                if (!globalSubscription) {
                    globalSubscription = client.subscribe(
                        '/user/queue/messages',
                        (message: IMessage) => {
                            try {
                                const payload = JSON.parse(message.body);
                                dispatchServerMessage(payload);
                            } catch (error) {
                                console.error('[WebSocket] Failed to parse message:', error);
                            }
                        }
                    );
                }

                optionsRef.current.onConnect?.();
            },
            onDisconnect: () => {
                console.log(`[WebSocket][${instanceId.current}] Disconnected`);
                globalSubscription = null;
                optionsRef.current.onDisconnect?.();
            },
            onStompError: (frame) => {
                console.error(`[WebSocket][${instanceId.current}] STOMP error:`, frame);
                optionsRef.current.onError?.(new Error(frame.headers['message'] || 'STOMP error'));
            },
            onWebSocketError: (event) => {
                console.error(`[WebSocket][${instanceId.current}] WebSocket error:`, event);
                optionsRef.current.onError?.(new Error('WebSocket connection failed'));
            },
        });

        globalClient = client;
        client.activate();
    }, []);

    const disconnect = useCallback(() => {
        // Only disconnect if this is the last instance
        if (instanceId.current === connectionCount) {
            console.log(`[WebSocket][${instanceId.current}] Last instance, keeping connection alive`);
        } else {
            console.log(`[WebSocket][${instanceId.current}] Instance unmounted, connection preserved`);
        }
        // Note: We don't actually disconnect here to survive StrictMode
        // The connection will be reused by the next mount
    }, []);

    const sendMessage = useCallback((destination: string, body: unknown) => {
        if (!globalClient?.active) {
            console.error('[WebSocket] Not connected');
            return false;
        }

        globalClient.publish({
            destination,
            body: JSON.stringify(body),
        });
        return true;
    }, []);

    const isConnected = useCallback(() => {
        return globalClient?.active ?? false;
    }, []);

    useEffect(() => {
        connect();
        return () => {
            disconnect();
        };
    }, [connect, disconnect]);

    return {
        connect,
        disconnect: () => {
            // Actual disconnect only when explicitly called
            globalSubscription?.unsubscribe();
            globalSubscription = null;
            globalClient?.deactivate();
            globalClient = null;
        },
        sendMessage,
        isConnected,
    };
}
