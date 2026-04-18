/**
 * SwarmMessageLog — Swarm 消息日志面板
 * 按时间顺序显示 Worker 活动、任务分配、完成事件
 */

import React, { useRef, useEffect } from 'react';
import { useSwarmStore } from '@/store/swarmStore';

const typeIcons: Record<string, { icon: string; color: string }> = {
    worker_start: { icon: '▶', color: 'text-green-500' },
    worker_complete: { icon: '✓', color: 'text-blue-500' },
    worker_error: { icon: '✗', color: 'text-red-500' },
    task_assigned: { icon: '→', color: 'text-purple-500' },
    permission_bubble: { icon: '⚠', color: 'text-amber-500' },
    message: { icon: '●', color: 'text-zinc-400' },
};

export const SwarmMessageLog: React.FC = () => {
    const { logs, panelVisible } = useSwarmStore();
    const scrollRef = useRef<HTMLDivElement>(null);

    // Auto-scroll to bottom
    useEffect(() => {
        if (scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [logs.length]);

    if (!panelVisible) return null;

    const formatTime = (ts: number) => {
        const d = new Date(ts);
        return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
    };

    return (
        <div className="border-t border-zinc-200 dark:border-zinc-700 bg-zinc-50/50 dark:bg-zinc-900/50">
            {/* Header */}
            <div className="px-4 py-2 border-b border-zinc-100 dark:border-zinc-800">
                <div className="flex items-center gap-2">
                    <svg className="w-3.5 h-3.5 text-zinc-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 10h16M4 14h16M4 18h16" />
                    </svg>
                    <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
                        活动日志
                    </span>
                    <span className="text-[10px] text-zinc-400">({logs.length})</span>
                </div>
            </div>

            {/* Log Entries */}
            <div
                ref={scrollRef}
                className="max-h-48 overflow-y-auto px-3 py-2 space-y-1"
            >
                {logs.length === 0 ? (
                    <div className="text-center text-xs text-zinc-400 py-4">
                        暂无活动日志
                    </div>
                ) : (
                    logs.slice(-50).map((entry) => {
                        const cfg = typeIcons[entry.type] ?? typeIcons.message;
                        return (
                            <div key={entry.id} className="flex items-start gap-2 text-xs leading-relaxed group">
                                <span className="text-[10px] text-zinc-400 font-mono tabular-nums shrink-0 mt-0.5">
                                    {formatTime(entry.timestamp)}
                                </span>
                                <span className={`shrink-0 mt-0.5 ${cfg.color}`}>
                                    {cfg.icon}
                                </span>
                                <span className="text-zinc-600 dark:text-zinc-400 break-all">
                                    {entry.workerId && (
                                        <span className="font-mono text-zinc-500 dark:text-zinc-500 mr-1">
                                            [{entry.workerId.split('-').pop()}]
                                        </span>
                                    )}
                                    {entry.content}
                                </span>
                            </div>
                        );
                    })
                )}
            </div>
        </div>
    );
};

export default SwarmMessageLog;
