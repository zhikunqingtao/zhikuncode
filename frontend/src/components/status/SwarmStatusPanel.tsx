/**
 * SwarmStatusPanel — Swarm 状态总览面板
 * 显示 Swarm 运行状态、Worker 数量、任务进度，以及关闭按钮
 */

import React, { useCallback, useMemo } from 'react';
import { useSwarmStore } from '@/store/swarmStore';
import { WorkerProgressCard } from './WorkerProgressCard';

const phaseColors: Record<string, string> = {
    INITIALIZING: 'bg-warn',
    RUNNING: 'bg-ok',
    IDLE: 'bg-accent2',
    SHUTTING_DOWN: 'bg-warn',
    TERMINATED: 'bg-t3',
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
        <div className="fixed right-0 top-0 h-full w-80 lg:w-96 bg-surfacev2 border-l border-hairline shadow-xl z-40 flex flex-col overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between px-4 py-3 border-b border-hairline bg-surface2">
                <div className="flex items-center gap-2">
                    <div className={`w-2.5 h-2.5 rounded-full ${phaseColors[swarm.phase] ?? 'bg-t3'} animate-pulse`} />
                    <h3 className="text-t1 text-base font-semibold">
                        Swarm
                    </h3>
                    <span className="text-[13px] text-t3">
                        {phaseLabels[swarm.phase] ?? swarm.phase}
                    </span>
                </div>
                <div className="flex items-center gap-1">
                    {(swarm.phase === 'RUNNING' || swarm.phase === 'IDLE') && (
                        <button
                            onClick={handleShutdown}
                            className="panel-control text-[13px] px-2 py-1 rounded bg-errsoft text-err hover:bg-errsoft transition-colors"
                            title="关闭 Swarm"
                        >
                            停止
                        </button>
                    )}
                    <button
                        onClick={handleClose}
                        className="panel-control p-1 rounded hover:bg-hover2 text-t3 transition-colors"
                        title="关闭面板"
                    >
                        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                        </svg>
                    </button>
                </div>
            </div>

            {/* Stats Bar */}
            <div className="px-4 py-2 border-b border-hairline bg-surface2">
                <div className="grid grid-cols-3 gap-2 text-center">
                    <div>
                        <div className="text-lg font-bold text-t1">{swarm.activeWorkers}</div>
                        <div className="text-[13px] text-t3 uppercase tracking-wider">活跃</div>
                    </div>
                    <div>
                        <div className="text-lg font-bold text-t1">{swarm.completedTasks}/{swarm.totalTasks}</div>
                        <div className="text-[13px] text-t3 uppercase tracking-wider">任务</div>
                    </div>
                    <div>
                        <div className="text-lg font-bold text-t1">{swarm.totalWorkers}</div>
                        <div className="text-[13px] text-t3 uppercase tracking-wider">Workers</div>
                    </div>
                </div>

                {/* Progress Bar */}
                <div className="mt-2">
                    <div className="h-1.5 bg-sunken2 rounded-full overflow-hidden">
                        <div
                            className="h-full bg-ok rounded-full transition-[width] duration-sheet"
                            style={{ width: `${progressPct}%` }}
                        />
                    </div>
                    <div className="text-[13px] text-t3 mt-0.5 text-right">{progressPct}%</div>
                </div>
            </div>

            {/* Workers List */}
            <div className="flex-1 overflow-y-auto p-3 space-y-2">
                {workers.length === 0 ? (
                    <div className="text-center text-sm text-t3 py-8">
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
            <div className="px-4 py-2 border-t border-hairline bg-surface2">
                <div className="text-[13px] text-t3">
                    Swarm ID: {swarm.swarmId}
                </div>
            </div>
        </div>
    );
};

export default SwarmStatusPanel;
