/**
 * McpConnectionIndicator — MCP 服务器连接状态指示器
 * 显示所有 MCP 服务器的健康状态（绿/黄/红指示灯）。
 * 风格与 ConnectionIndicator 组件一致。
 */

import React from 'react';
import { useMcpStore } from '@/store/mcpStore';
import type { McpHealthState } from '@/store/mcpStore';

const statusConfig: Record<string, { color: string; label: string; pulse: boolean }> = {
    CONNECTED:    { color: 'bg-green-500', label: '已连接', pulse: false },
    DEGRADED:     { color: 'bg-yellow-500', label: '降级', pulse: true },
    FAILED:       { color: 'bg-red-500', label: '已断开', pulse: false },
    PENDING:      { color: 'bg-blue-400', label: '连接中', pulse: true },
    NEEDS_AUTH:   { color: 'bg-orange-400', label: '需认证', pulse: false },
    DISABLED:     { color: 'bg-gray-400', label: '已禁用', pulse: false },
};

/** 格式化最后成功 ping 时间为相对时间 */
function formatLastPing(epochMs: number | null): string {
    if (!epochMs) return '从未';
    const diffSec = Math.round((Date.now() - epochMs) / 1000);
    if (diffSec < 60) return `${diffSec}秒前`;
    if (diffSec < 3600) return `${Math.round(diffSec / 60)}分钟前`;
    return `${Math.round(diffSec / 3600)}小时前`;
}

const McpServerItem: React.FC<{ state: McpHealthState }> = ({ state }) => {
    const cfg = statusConfig[state.status] || statusConfig['FAILED'];

    return (
        <div className="flex items-center justify-between gap-2 py-0.5">
            <div className="flex items-center gap-1.5">
                <span
                    className={`w-2 h-2 rounded-full ${cfg.color} ${cfg.pulse ? 'animate-pulse' : ''}`}
                />
                <span className="text-xs font-medium text-gray-700 dark:text-gray-300 truncate max-w-[120px]">
                    {state.serverName}
                </span>
            </div>
            <div className="flex items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
                {state.consecutiveFailures > 0 && (
                    <span className="text-red-500" title="连续失败次数">
                        ×{state.consecutiveFailures}
                    </span>
                )}
                <span className="hidden md:inline" title="最后成功 ping">
                    {formatLastPing(state.lastSuccessfulPing)}
                </span>
                <span>{cfg.label}</span>
            </div>
        </div>
    );
};

export const McpConnectionIndicator: React.FC = () => {
    const healthStates = useMcpStore(s => s.mcpHealthStates);

    // 无任何 MCP 健康状态数据时不渲染
    if (healthStates.size === 0) return null;

    const entries = Array.from(healthStates.values());

    return (
        <div className="flex flex-col gap-0.5 px-2 py-1">
            <div className="text-[10px] font-semibold text-gray-400 dark:text-gray-500 uppercase tracking-wider mb-0.5">
                MCP 服务器
            </div>
            {entries.map(state => (
                <McpServerItem key={state.serverName} state={state} />
            ))}
        </div>
    );
};
