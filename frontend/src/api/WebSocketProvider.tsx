/**
 * WebSocketProvider — React Context 管理 STOMP 连接生命周期
 * SPEC: §8.5.3 (Provider + dispatch 函数模式)
 *
 * 使用方式:
 *   <WebSocketProvider sessionId="..." authToken="...">
 *     <App />
 *   </WebSocketProvider>
 *
 *   const { sendMessage, sendInterrupt, isConnected } = useWebSocket();
 */

import React, { createContext, useContext, useEffect, useRef, useCallback, useMemo } from 'react';
import {
    createStompClient,
    disconnectStomp,
    isConnected as checkConnected,
    sendUserMessage,
    sendPermissionResponse,
    sendInterrupt,
    sendSetModel,
    sendSetPermissionMode,
    sendSlashCommand,
    sendMcpOperation,
    sendRewindFiles,
    sendElicitationResponse,
    sendPing,
} from './stompClient';
import type { Client as StompClient } from '@stomp/stompjs';
import type { Attachment } from '@/types';

// ==================== Context 类型 ====================

interface WebSocketContextValue {
    /** 发送用户消息 */
    sendMessage: (text: string, attachments?: Attachment[]) => void;
    /** 发送权限响应 */
    respondPermission: (toolUseId: string, decision: 'allow' | 'deny' | 'allow_always', remember?: boolean, scope?: string) => void;
    /** 发送中断 */
    interrupt: () => void;
    /** 切换模型 */
    setModel: (model: string) => void;
    /** 切换权限模式 */
    setPermissionMode: (mode: string) => void;
    /** 执行 Slash 命令 */
    executeCommand: (command: string, args: string) => void;
    /** MCP 操作 */
    mcpOperation: (operation: string, serverId: string, config?: object) => void;
    /** 回退文件 */
    rewindFiles: (messageId: string, filePaths: string[]) => void;
    /** 响应 AI 反向提问 */
    respondElicitation: (requestId: string, answer: string | string[] | null) => void;
    /** 心跳探测 */
    ping: () => void;
    /** 连接状态 */
    isConnected: boolean;
}

const WebSocketContext = createContext<WebSocketContextValue | null>(null);

// ==================== Provider ====================

interface WebSocketProviderProps {
    sessionId: string;
    authToken: string;
    children: React.ReactNode;
}

export function WebSocketProvider({ sessionId, authToken, children }: WebSocketProviderProps): React.ReactElement {
    const clientRef = useRef<StompClient | null>(null);

    // 连接/断开生命周期
    useEffect(() => {
        if (sessionId && authToken) {
            clientRef.current = createStompClient(sessionId, authToken);
        }
        return () => {
            disconnectStomp();
            clientRef.current = null;
        };
    }, [sessionId, authToken]);

    // 便捷方法 — 稳定引用
    const sendMessage = useCallback((text: string, attachments?: Attachment[]) => {
        sendUserMessage(text, attachments);
    }, []);

    const respondPermission = useCallback((toolUseId: string, decision: 'allow' | 'deny' | 'allow_always', remember?: boolean, scope?: string) => {
        sendPermissionResponse(toolUseId, decision, remember, scope);
    }, []);

    const interrupt = useCallback(() => { sendInterrupt(); }, []);
    const setModel = useCallback((model: string) => { sendSetModel(model); }, []);
    const setPermissionMode = useCallback((mode: string) => { sendSetPermissionMode(mode); }, []);
    const executeCommand = useCallback((command: string, args: string) => { sendSlashCommand(command, args); }, []);
    const mcpOp = useCallback((operation: string, serverId: string, config?: object) => { sendMcpOperation(operation, serverId, config); }, []);
    const rewind = useCallback((messageId: string, filePaths: string[]) => { sendRewindFiles(messageId, filePaths); }, []);
    const respondElicitation = useCallback((requestId: string, answer: string | string[] | null) => { sendElicitationResponse(requestId, answer); }, []);
    const ping = useCallback(() => { sendPing(); }, []);

    const value = useMemo<WebSocketContextValue>(() => ({
        sendMessage,
        respondPermission,
        interrupt,
        setModel,
        setPermissionMode,
        executeCommand,
        mcpOperation: mcpOp,
        rewindFiles: rewind,
        respondElicitation,
        ping,
        isConnected: checkConnected(),
    }), [sendMessage, respondPermission, interrupt, setModel, setPermissionMode, executeCommand, mcpOp, rewind, respondElicitation, ping]);

    return React.createElement(WebSocketContext.Provider, { value }, children);
}

// ==================== Hook ====================

/**
 * useWebSocket — 组件级接入 STOMP 客户端。
 * 必须在 <WebSocketProvider> 内部使用。
 */
export function useWebSocket(): WebSocketContextValue {
    const context = useContext(WebSocketContext);
    if (!context) {
        throw new Error('useWebSocket must be used within a <WebSocketProvider>');
    }
    return context;
}
