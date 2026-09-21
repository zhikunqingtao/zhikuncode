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
        color: 'border-accent2 bg-accent2-soft',
        label: '运行中',
        icon: '⏳',
    },
    completed: {
        color: 'border-ok bg-oksoft',
        label: '已完成',
        icon: '✓',
    },
    failed: {
        color: 'border-err bg-errsoft',
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
        <div className={`border rounded-[10px] p-3 transition-colors duration-base ${config.color}`}>
            {/* Header row */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 min-w-0 flex-1">
                    {/* Status indicator */}
                    <span className={`flex-shrink-0 w-6 h-6 rounded-full flex items-center justify-center text-[13px]
                        ${task.status === 'running' ? 'bg-accent2 text-white dark:text-app2 animate-spin-slow' :
                          task.status === 'completed' ? 'bg-ok text-white dark:text-app2' :
                          'bg-err text-white dark:text-app2'}`}>
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
                            <span className="text-sm font-medium text-t1 truncate">
                                {task.agentName}
                            </span>
                            <span className="text-[13px] px-1.5 py-0.5 rounded bg-sunken2 text-t2 flex-shrink-0">
                                {task.agentType}
                            </span>
                        </div>
                        <p className="text-[13px] text-t2 truncate mt-0.5">
                            {task.description}
                        </p>
                    </div>
                </div>

                {/* Elapsed time + expand button */}
                <div className="flex items-center gap-2 flex-shrink-0 ml-2">
                    <span className="text-[13px] text-t2 font-mono">
                        {elapsed}
                    </span>
                    {(task.progress || task.result) && (
                        <button
                            onClick={toggleExpand}
                            className="panel-control w-6 h-6 rounded flex items-center justify-center
                                       hover:bg-sunken2 transition-colors"
                            title={expanded ? '收起' : '展开详情'}
                        >
                            <svg
                                className={`w-3.5 h-3.5 text-t2 transition-transform duration-base
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
                <div className="mt-2 pt-2 border-t border-border-hairline">
                    {task.progress && (
                        <div className="mb-1.5">
                            <span className="text-[13px] font-semibold text-t2 uppercase">
                                进度
                            </span>
                            <p className="text-[13px] text-t2 dark:text-t2 mt-0.5 whitespace-pre-wrap break-all max-h-24 overflow-y-auto">
                                {task.progress}
                            </p>
                        </div>
                    )}
                    {task.result && (
                        <div>
                            <span className="text-[13px] font-semibold text-t2 uppercase">
                                输出
                            </span>
                            <p className="text-[13px] text-t2 dark:text-t2 mt-0.5 whitespace-pre-wrap break-all max-h-32 overflow-y-auto">
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
        <div className="px-4 py-3 bg-surfacev2 backdrop-blur-sm border border-border-hairline rounded-[14px] shadow-e1">
            {/* Header */}
            <div className="flex items-center justify-between mb-2.5">
                <span className="text-sm font-semibold text-t1">
                    Agent 任务
                </span>
                <div className="flex items-center gap-2 text-[13px]">
                    {runningCount > 0 && (
                        <span className="text-accent2-ink">
                            {runningCount} 运行中
                        </span>
                    )}
                    <span className="text-t2">
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
