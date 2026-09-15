/**
 * PipelineNode — Pipeline 单节点组件
 * 展示单个 Worker 的状态、进度和错误信息
 */

import React from 'react';
import type { WorkerInfo } from '@/types';

interface PipelineNodeProps {
    worker: WorkerInfo;
    swarmId: string;
}

/** 根据 Worker 状态返回对应图标 */
function getStatusIcon(worker: WorkerInfo): string {
    switch (worker.status) {
        case 'STARTING':
            return '⏳';
        case 'WORKING':
            return '🔄';
        case 'IDLE':
            return '⏸';
        case 'TERMINATED':
            switch (worker.terminationReason) {
                case 'completed':
                    return '✅';
                case 'error':
                    return '❌';
                case 'aborted':
                    return '⚫';
                default:
                    return '⚫';
            }
        default:
            return '❓';
    }
}

/** 根据状态返回边框颜色 */
function getStatusBorderColor(worker: WorkerInfo): string {
    switch (worker.status) {
        case 'STARTING':
            return 'border-yellow-400';
        case 'WORKING':
            return 'border-blue-500';
        case 'IDLE':
            return 'border-gray-400';
        case 'TERMINATED':
            return worker.terminationReason === 'error' ? 'border-red-500' : 'border-gray-500';
        default:
            return 'border-gray-300';
    }
}

export const PipelineNode: React.FC<PipelineNodeProps> = ({ worker, swarmId: _swarmId }) => {
    const statusIcon = getStatusIcon(worker);
    const borderColor = getStatusBorderColor(worker);

    return (
        <div className={`rounded-lg border-2 ${borderColor} bg-surfacev2 p-4 shadow-sm transition-[width]`}>
            {/* Header: Name + Status */}
            <div className="flex items-center justify-between mb-2">
                <span className="font-semibold text-sm text-t1 truncate">
                    {worker.workerId}
                </span>
                <span className="text-lg" title={`${worker.status}${worker.terminationReason ? ` (${worker.terminationReason})` : ''}`}>
                    {statusIcon}
                </span>
            </div>

            {/* Progress bar (WORKING state) */}
            {worker.status === 'WORKING' && worker.progressPercent != null && (
                <div className="mb-2">
                    <div className="w-full bg-gray-200 dark:bg-gray-700 rounded-full h-2">
                        <div
                            className="bg-blue-500 h-2 rounded-full transition-[width] duration-slow"
                            style={{ width: `${Math.min(100, Math.max(0, worker.progressPercent))}%` }}
                        />
                    </div>
                    <div className="flex justify-between mt-1">
                        <span className="text-[13px] text-t2">
                            {worker.completedSteps ?? 0}/{worker.totalSteps ?? '?'} steps
                        </span>
                        <span className="text-[13px] text-t2">
                            {worker.progressPercent}%
                        </span>
                    </div>
                </div>
            )}

            {/* Current step description */}
            {worker.currentStepDescription && (
                <p className="text-[13px] text-t2 dark:text-t2 mb-1 truncate" title={worker.currentStepDescription}>
                    {worker.currentStepDescription}
                </p>
            )}

            {/* Error message */}
            {worker.errorMessage && (
                <p className="text-[13px] text-err mt-1 truncate" title={worker.errorMessage}>
                    ⚠️ {worker.errorMessage}
                </p>
            )}
        </div>
    );
};

export default PipelineNode;
