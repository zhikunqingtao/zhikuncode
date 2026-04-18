/**
 * WorkerProgressCard — 单个 Worker 进度卡片
 * 显示 Worker 状态、当前任务、工具调用计数、Token 消耗
 */

import React from 'react';
import type { WorkerInfo } from '@/types';

const statusConfig: Record<string, { color: string; bg: string; label: string }> = {
    STARTING: { color: 'text-yellow-600 dark:text-yellow-400', bg: 'bg-yellow-100 dark:bg-yellow-900/30', label: '启动中' },
    WORKING: { color: 'text-green-600 dark:text-green-400', bg: 'bg-green-100 dark:bg-green-900/30', label: '工作中' },
    IDLE: { color: 'text-blue-600 dark:text-blue-400', bg: 'bg-blue-100 dark:bg-blue-900/30', label: '空闲' },
    TERMINATED: { color: 'text-gray-600 dark:text-gray-400', bg: 'bg-gray-100 dark:bg-gray-800', label: '已终止' },
};

interface WorkerProgressCardProps {
    worker: WorkerInfo;
    swarmId: string;
}

export const WorkerProgressCard: React.FC<WorkerProgressCardProps> = ({ worker }) => {
    const cfg = statusConfig[worker.status] ?? statusConfig.TERMINATED;

    // Short worker ID for display
    const shortId = worker.workerId.split('-').pop() ?? worker.workerId;

    // Format token count
    const formatTokens = (n: number) => {
        if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
        return String(n);
    };

    return (
        <div className={`rounded-lg border border-zinc-200 dark:border-zinc-700 p-3 ${cfg.bg} transition-all duration-200`}>
            {/* Header Row */}
            <div className="flex items-center justify-between mb-1.5">
                <div className="flex items-center gap-1.5">
                    <div className={`w-2 h-2 rounded-full ${
                        worker.status === 'WORKING' ? 'bg-green-500 animate-pulse' :
                        worker.status === 'STARTING' ? 'bg-yellow-500 animate-pulse' :
                        worker.status === 'IDLE' ? 'bg-blue-500' : 'bg-gray-400'
                    }`} />
                    <span className="text-xs font-mono font-medium text-zinc-700 dark:text-zinc-300">
                        Worker #{shortId}
                    </span>
                </div>
                <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${cfg.color} ${cfg.bg}`}>
                    {cfg.label}
                </span>
            </div>

            {/* Task Description */}
            {worker.currentTask && worker.currentTask !== 'idle' && worker.currentTask !== 'terminated' && (
                <div className="text-xs text-zinc-600 dark:text-zinc-400 mb-2 line-clamp-2 leading-relaxed">
                    {worker.currentTask}
                </div>
            )}

            {/* Stats Row */}
            <div className="flex items-center gap-3 text-[10px] text-zinc-500 dark:text-zinc-400">
                <div className="flex items-center gap-0.5" title="工具调用次数">
                    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                    <span>{worker.toolCallCount}</span>
                </div>
                <div className="flex items-center gap-0.5" title="Token 消耗">
                    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z" />
                    </svg>
                    <span>{formatTokens(worker.tokenConsumed)}</span>
                </div>
            </div>

            {/* Recent Tool Calls */}
            {worker.recentToolCalls && worker.recentToolCalls.length > 0 && (
                <div className="mt-1.5 flex flex-wrap gap-1">
                    {worker.recentToolCalls.slice(-3).map((tool, i) => (
                        <span
                            key={`${tool}-${i}`}
                            className="text-[9px] px-1.5 py-0.5 rounded-full bg-zinc-200 dark:bg-zinc-700 text-zinc-600 dark:text-zinc-300 font-mono"
                        >
                            {tool}
                        </span>
                    ))}
                </div>
            )}
        </div>
    );
};

export default WorkerProgressCard;
