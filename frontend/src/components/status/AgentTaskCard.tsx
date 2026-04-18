/**
 * AgentTaskCard — Agent 任务详情卡片
 * 显示 Agent 任务描述、分配的 Agent、进度、输出摘要
 * 可展开查看详细输出
 */

import React, { useState, useCallback, useMemo } from 'react';
import { useCoordinatorStore } from '@/store/coordinatorStore';
import type { AgentTask } from '@/types';

const statusConfig: Record<string, { color: string; label: string; icon: string }> = {
    running: {
        color: 'border-blue-400 dark:border-blue-600 bg-blue-50/50 dark:bg-blue-950/20',
        label: '运行中',
        icon: '⏳',
    },
    completed: {
        color: 'border-emerald-400 dark:border-emerald-600 bg-emerald-50/50 dark:bg-emerald-950/20',
        label: '已完成',
        icon: '✓',
    },
    failed: {
        color: 'border-red-400 dark:border-red-600 bg-red-50/50 dark:bg-red-950/20',
        label: '失败',
        icon: '✗',
    },
};

interface TaskCardItemProps {
    task: AgentTask;
}

const TaskCardItem: React.FC<TaskCardItemProps> = ({ task }) => {
    const [expanded, setExpanded] = useState(false);
    const config = statusConfig[task.status] || statusConfig.running;

    const toggleExpand = useCallback(() => setExpanded((prev) => !prev), []);

    const elapsed = useMemo(() => {
        const seconds = Math.floor((Date.now() - task.startTime) / 1000);
        if (seconds < 60) return `${seconds}s`;
        return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
    }, [task.startTime]);

    return (
        <div className={`border rounded-lg p-3 transition-all duration-200 ${config.color}`}>
            {/* Header row */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 min-w-0 flex-1">
                    {/* Status indicator */}
                    <span className={`flex-shrink-0 w-6 h-6 rounded-full flex items-center justify-center text-xs
                        ${task.status === 'running' ? 'bg-blue-500 text-white animate-spin-slow' :
                          task.status === 'completed' ? 'bg-emerald-500 text-white' :
                          'bg-red-500 text-white'}`}>
                        {task.status === 'running' ? (
                            <svg className="w-3.5 h-3.5 animate-spin" fill="none" viewBox="0 0 24 24">
                                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                            </svg>
                        ) : config.icon}
                    </span>

                    {/* Agent name & type */}
                    <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1.5">
                            <span className="text-sm font-medium text-gray-800 dark:text-gray-200 truncate">
                                {task.agentName}
                            </span>
                            <span className="text-[10px] px-1.5 py-0.5 rounded bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-400 flex-shrink-0">
                                {task.agentType}
                            </span>
                        </div>
                        <p className="text-xs text-gray-500 dark:text-gray-400 truncate mt-0.5">
                            {task.description}
                        </p>
                    </div>
                </div>

                {/* Elapsed time + expand button */}
                <div className="flex items-center gap-2 flex-shrink-0 ml-2">
                    <span className="text-[11px] text-gray-400 dark:text-gray-500 font-mono">
                        {elapsed}
                    </span>
                    {(task.progress || task.result) && (
                        <button
                            onClick={toggleExpand}
                            className="w-6 h-6 rounded flex items-center justify-center
                                       hover:bg-gray-200 dark:hover:bg-gray-700 transition-colors"
                            title={expanded ? '收起' : '展开详情'}
                        >
                            <svg
                                className={`w-3.5 h-3.5 text-gray-500 transition-transform duration-200
                                            ${expanded ? 'rotate-180' : ''}`}
                                fill="none" stroke="currentColor" viewBox="0 0 24 24"
                            >
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                            </svg>
                        </button>
                    )}
                </div>
            </div>

            {/* Expanded details */}
            {expanded && (
                <div className="mt-2 pt-2 border-t border-gray-200 dark:border-gray-700">
                    {task.progress && (
                        <div className="mb-1.5">
                            <span className="text-[10px] font-semibold text-gray-500 dark:text-gray-400 uppercase">
                                进度
                            </span>
                            <p className="text-xs text-gray-600 dark:text-gray-300 mt-0.5 whitespace-pre-wrap break-all max-h-24 overflow-y-auto">
                                {task.progress}
                            </p>
                        </div>
                    )}
                    {task.result && (
                        <div>
                            <span className="text-[10px] font-semibold text-gray-500 dark:text-gray-400 uppercase">
                                输出
                            </span>
                            <p className="text-xs text-gray-600 dark:text-gray-300 mt-0.5 whitespace-pre-wrap break-all max-h-32 overflow-y-auto">
                                {task.result}
                            </p>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};

export const AgentTaskCard: React.FC = () => {
    const agentTasks = useCoordinatorStore((s) => s.agentTasks);

    if (agentTasks.length === 0) return null;

    const runningCount = agentTasks.filter((t) => t.status === 'running').length;
    const completedCount = agentTasks.filter((t) => t.status === 'completed').length;

    return (
        <div className="px-4 py-3 bg-white/80 dark:bg-gray-900/80 backdrop-blur-sm border border-gray-200 dark:border-gray-700 rounded-xl shadow-sm">
            {/* Header */}
            <div className="flex items-center justify-between mb-2.5">
                <span className="text-sm font-semibold text-gray-800 dark:text-gray-200">
                    Agent 任务
                </span>
                <div className="flex items-center gap-2 text-[11px]">
                    {runningCount > 0 && (
                        <span className="text-blue-600 dark:text-blue-400">
                            {runningCount} 运行中
                        </span>
                    )}
                    <span className="text-gray-400">
                        {completedCount}/{agentTasks.length} 完成
                    </span>
                </div>
            </div>

            {/* Task list */}
            <div className="space-y-2 max-h-64 overflow-y-auto">
                {agentTasks.map((task) => (
                    <TaskCardItem key={task.taskId} task={task} />
                ))}
            </div>
        </div>
    );
};
