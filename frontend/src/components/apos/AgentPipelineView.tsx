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
                <h2 className="text-t1 mb-4 text-xl font-semibold">
                    Agent Pipeline
                </h2>
                <div className="flex flex-col items-center justify-center py-12 text-t2">
                    <span className="text-4xl mb-3">🔗</span>
                    <p className="text-sm">No active Swarm</p>
                    <p className="text-[13px] mt-1">Pipeline will appear when a Swarm is running</p>
                </div>
            </div>
        );
    }

    const workers = Object.values(activeSwarm.workers);

    return (
        <div className="p-6">
            <h2 className="text-t1 mb-4 text-xl font-semibold">
                Agent Pipeline
            </h2>

            {workers.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-8 text-t2">
                    <span className="text-3xl mb-2">⏳</span>
                    <p className="text-sm">Waiting for workers to start...</p>
                </div>
            ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
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
