/**
 * SwarmStatusPanel — Swarm 状态总览面板
 * 显示 Swarm 运行状态、Worker 数量、任务进度，以及关闭按钮
 */

import React, { useCallback, useMemo } from 'react';
import { useSwarmStore } from '@/store/swarmStore';
import { WorkerProgressCard } from './WorkerProgressCard';

const phaseColors: Record<string, string> = {
    INITIALIZING: 'bg-yellow-500',
    RUNNING: 'bg-green-500',
    IDLE: 'bg-blue-500',
    SHUTTING_DOWN: 'bg-orange-500',
    TERMINATED: 'bg-gray-500',
};

const phaseLabels: Record<string, string> = {
    INITIALIZING: '初始化中',
    RUNNING: '运行中',
    IDLE: '空闲',
    SHUTTING_DOWN: '关闭中',
    TERMINATED: '已终止',
};

export const SwarmStatusPanel: React.FC = () => {
    const { swarms, activeSwarmId, panelVisible, setPanelVisible } = useSwarmStore();
    const swarm = activeSwarmId ? swarms.get(activeSwarmId) : null;

    const handleShutdown = useCallback(async () => {
        if (!swarm) return;
        try {
            await fetch(`/api/swarm/${swarm.swarmId}/shutdown`, { method: 'POST' });
        } catch (e) {
            console.error('Failed to shutdown swarm:', e);
        }
    }, [swarm]);

    const handleClose = useCallback(() => {
        setPanelVisible(false);
    }, [setPanelVisible]);

    const workers = useMemo(() => {
        if (!swarm?.workers) return [];
        return Object.values(swarm.workers);
    }, [swarm?.workers]);

    const progressPct = useMemo(() => {
        if (!swarm || swarm.totalTasks === 0) return 0;
        return Math.round((swarm.completedTasks / swarm.totalTasks) * 100);
    }, [swarm]);

    if (!panelVisible || !swarm) return null;

    return (
        <div className="fixed right-0 top-0 h-full w-80 lg:w-96 bg-white dark:bg-zinc-900 border-l border-zinc-200 dark:border-zinc-700 shadow-xl z-40 flex flex-col overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between px-4 py-3 border-b border-zinc-200 dark:border-zinc-700 bg-zinc-50 dark:bg-zinc-800">
                <div className="flex items-center gap-2">
                    <div className={`w-2.5 h-2.5 rounded-full ${phaseColors[swarm.phase] ?? 'bg-gray-400'} animate-pulse`} />
                    <h3 className="text-sm font-semibold text-zinc-800 dark:text-zinc-200">
                        Swarm
                    </h3>
                    <span className="text-xs text-zinc-500 dark:text-zinc-400">
                        {phaseLabels[swarm.phase] ?? swarm.phase}
                    </span>
                </div>
                <div className="flex items-center gap-1">
                    {(swarm.phase === 'RUNNING' || swarm.phase === 'IDLE') && (
                        <button
                            onClick={handleShutdown}
                            className="text-xs px-2 py-1 rounded bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 hover:bg-red-200 dark:hover:bg-red-900/50 transition-colors"
                            title="关闭 Swarm"
                        >
                            停止
                        </button>
                    )}
                    <button
                        onClick={handleClose}
                        className="p-1 rounded hover:bg-zinc-200 dark:hover:bg-zinc-700 text-zinc-500 transition-colors"
                        title="关闭面板"
                    >
                        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                        </svg>
                    </button>
                </div>
            </div>

            {/* Stats Bar */}
            <div className="px-4 py-2 border-b border-zinc-100 dark:border-zinc-800 bg-zinc-50/50 dark:bg-zinc-800/50">
                <div className="grid grid-cols-3 gap-2 text-center">
                    <div>
                        <div className="text-lg font-bold text-zinc-800 dark:text-zinc-200">{swarm.activeWorkers}</div>
                        <div className="text-[10px] text-zinc-500 dark:text-zinc-400 uppercase tracking-wider">活跃</div>
                    </div>
                    <div>
                        <div className="text-lg font-bold text-zinc-800 dark:text-zinc-200">{swarm.completedTasks}/{swarm.totalTasks}</div>
                        <div className="text-[10px] text-zinc-500 dark:text-zinc-400 uppercase tracking-wider">任务</div>
                    </div>
                    <div>
                        <div className="text-lg font-bold text-zinc-800 dark:text-zinc-200">{swarm.totalWorkers}</div>
                        <div className="text-[10px] text-zinc-500 dark:text-zinc-400 uppercase tracking-wider">Workers</div>
                    </div>
                </div>

                {/* Progress Bar */}
                <div className="mt-2">
                    <div className="h-1.5 bg-zinc-200 dark:bg-zinc-700 rounded-full overflow-hidden">
                        <div
                            className="h-full bg-green-500 dark:bg-green-400 rounded-full transition-all duration-500"
                            style={{ width: `${progressPct}%` }}
                        />
                    </div>
                    <div className="text-[10px] text-zinc-400 mt-0.5 text-right">{progressPct}%</div>
                </div>
            </div>

            {/* Workers List */}
            <div className="flex-1 overflow-y-auto p-3 space-y-2">
                {workers.length === 0 ? (
                    <div className="text-center text-sm text-zinc-400 dark:text-zinc-500 py-8">
                        暂无 Worker
                    </div>
                ) : (
                    workers.map((worker) => (
                        <WorkerProgressCard
                            key={worker.workerId}
                            worker={worker}
                            swarmId={swarm.swarmId}
                        />
                    ))
                )}
            </div>

            {/* Footer */}
            <div className="px-4 py-2 border-t border-zinc-200 dark:border-zinc-700 bg-zinc-50 dark:bg-zinc-800">
                <div className="text-[10px] text-zinc-400 dark:text-zinc-500">
                    Swarm ID: {swarm.swarmId}
                </div>
            </div>
        </div>
    );
};

export default SwarmStatusPanel;
