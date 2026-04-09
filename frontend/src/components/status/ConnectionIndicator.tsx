/**
 * ConnectionIndicator — WebSocket 连接状态指示器
 * SPEC: §3.8 连接状态指示器
 */

import React from 'react';
import { useBridgeStore } from '@/store/bridgeStore';

const statusConfig: Record<string, { color: string; label: string; pulse: boolean }> = {
    connected:    { color: 'bg-green-500', label: '已连接', pulse: false },
    disconnected: { color: 'bg-red-500', label: '已断开', pulse: false },
    reconnecting: { color: 'bg-yellow-500', label: '重连中', pulse: true },
    error:        { color: 'bg-red-600', label: '错误', pulse: true },
};

export const ConnectionIndicator: React.FC = () => {
    const status = useBridgeStore(s => s.bridgeStatus);
    const attempt = useBridgeStore(s => s.reconnectAttempt);

    const cfg = statusConfig[status] || statusConfig['disconnected'];
    const label = status === 'reconnecting' ? `重连中 #${attempt}` : cfg.label;

    return (
        <div className="flex items-center gap-1.5 text-xs text-gray-500">
            <span className={`w-2 h-2 rounded-full ${cfg.color} ${cfg.pulse ? 'animate-pulse' : ''}`} />
            <span>{label}</span>
        </div>
    );
};
