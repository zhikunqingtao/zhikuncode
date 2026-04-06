/**
 * API 模块统一导出
 * SPEC: §8.5.3
 */

// STOMP 客户端
export {
    createStompClient,
    disconnectStomp,
    getStompClient,
    isConnected,
    send,
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

// 消息分发器
export { dispatch, resetSequence } from './dispatch';

// WebSocket Provider + Hook
export { WebSocketProvider, useWebSocket } from './WebSocketProvider';
