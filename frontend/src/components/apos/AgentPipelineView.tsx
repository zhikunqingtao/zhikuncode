/**
 * AgentPipelineView — Pipeline 布局视图
 * 展示当前活跃 Swarm 的所有 Worker 节点
 */

import React from 'react';
import { useSwarmStore } from '@/store/swarmStore';
import PipelineNode from './PipelineNode';

export const AgentPipelineView: React.FC = () => {
    const { swarms, activeSwarmId } = useSwarmStore();

    const activeSwarm = activeSwarmId ? swarms.get(activeSwarmId) : null;

    if (!activeSwarm) {
        return (
            <div className="p-6">
                <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100 mb-4">
                    Agent Pipeline
                </h2>
                <div className="flex flex-col items-center justify-center py-12 text-gray-400 dark:text-gray-500">
                    <span className="text-4xl mb-3">🔗</span>
                    <p className="text-sm">No active Swarm</p>
                    <p className="text-xs mt-1">Pipeline will appear when a Swarm is running</p>
                </div>
            </div>
        );
    }

    const workers = Object.values(activeSwarm.workers);

    return (
        <div className="p-6">
            <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100 mb-4">
                Agent Pipeline
            </h2>

            {workers.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-8 text-gray-400 dark:text-gray-500">
                    <span className="text-3xl mb-2">⏳</span>
                    <p className="text-sm">Waiting for workers to start...</p>
                </div>
            ) : (
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                    {workers.map((worker) => (
                        <PipelineNode
                            key={worker.workerId}
                            worker={worker}
                            swarmId={activeSwarm.swarmId}
                        />
                    ))}
                </div>
            )}
        </div>
    );
};

export default AgentPipelineView;
