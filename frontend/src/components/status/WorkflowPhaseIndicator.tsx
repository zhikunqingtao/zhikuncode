/**
 * WorkflowPhaseIndicator — Coordinator 四阶段进度指示器
 * 显示 Research → Synthesis → Implementation → Verification 的步骤条
 * 当前阶段高亮，已完成阶段打勾，未开始阶段灰色
 */

import React, { useMemo } from 'react';
import { useCoordinatorStore } from '@/store/coordinatorStore';
import type { WorkflowPhaseState } from '@/types';

const phaseIcons: Record<string, string> = {
    Research: '🔍',
    Synthesis: '🧠',
    Implementation: '⚙️',
    Verification: '✅',
};

const phaseLabels: Record<string, string> = {
    Research: '调研',
    Synthesis: '综合',
    Implementation: '实施',
    Verification: '验证',
};

interface PhaseStepProps {
    phase: WorkflowPhaseState;
    isLast: boolean;
}

const PhaseStep: React.FC<PhaseStepProps> = ({ phase, isLast }) => {
    const statusStyles = useMemo(() => {
        switch (phase.status) {
            case 'completed':
                return {
                    circle: 'bg-emerald-500 text-white ring-2 ring-emerald-200',
                    label: 'text-emerald-700 dark:text-emerald-400 font-medium',
                    line: 'bg-emerald-500',
                };
            case 'active':
                return {
                    circle: 'bg-blue-500 text-white ring-4 ring-blue-200 dark:ring-blue-900 animate-pulse',
                    label: 'text-blue-700 dark:text-blue-300 font-semibold',
                    line: 'bg-gradient-to-r from-emerald-500 to-blue-300',
                };
            case 'skipped':
                return {
                    circle: 'bg-amber-400 text-white ring-2 ring-amber-200',
                    label: 'text-amber-600 dark:text-amber-400',
                    line: 'bg-amber-300',
                };
            default: // pending
                return {
                    circle: 'bg-gray-200 dark:bg-gray-700 text-gray-500 dark:text-gray-400',
                    label: 'text-gray-500 dark:text-gray-500',
                    line: 'bg-gray-200 dark:bg-gray-700',
                };
        }
    }, [phase.status]);

    const elapsed = useMemo(() => {
        if (!phase.startTime) return null;
        const end = phase.endTime ?? Date.now();
        const seconds = Math.floor((end - phase.startTime) / 1000);
        if (seconds < 60) return `${seconds}s`;
        return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
    }, [phase.startTime, phase.endTime]);

    return (
        <div className="flex items-center">
            {/* Phase circle + label */}
            <div className="flex flex-col items-center min-w-[72px]">
                <div
                    className={`w-10 h-10 rounded-full flex items-center justify-center text-sm transition-all duration-300 ${statusStyles.circle}`}
                    title={`${phase.name}: ${phase.prompt || phaseLabels[phase.name]}`}
                >
                    {phase.status === 'completed' ? (
                        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M5 13l4 4L19 7" />
                        </svg>
                    ) : (
                        <span className="text-base">{phaseIcons[phase.name]}</span>
                    )}
                </div>
                <span className={`mt-1.5 text-xs leading-tight text-center ${statusStyles.label}`}>
                    {phaseLabels[phase.name]}
                </span>
                {elapsed && (
                    <span className="text-[10px] text-gray-400 dark:text-gray-500 mt-0.5">
                        {elapsed}
                    </span>
                )}
            </div>

            {/* Connector line */}
            {!isLast && (
                <div className="flex-1 mx-1.5 h-0.5 min-w-[24px]">
                    <div className={`h-full rounded-full transition-all duration-500 ${statusStyles.line}`} />
                </div>
            )}
        </div>
    );
};

export const WorkflowPhaseIndicator: React.FC = () => {
    const workflow = useCoordinatorStore((s) => s.activeWorkflow);

    if (!workflow) return null;

    const statusLabel = useMemo(() => {
        switch (workflow.status) {
            case 'RUNNING': return '工作流执行中';
            case 'COMPLETED': return '工作流已完成';
            case 'FAILED': return '工作流失败';
            case 'CANCELLED': return '工作流已取消';
            default: return '工作流准备中';
        }
    }, [workflow.status]);

    const statusColor = useMemo(() => {
        switch (workflow.status) {
            case 'RUNNING': return 'text-blue-600 dark:text-blue-400';
            case 'COMPLETED': return 'text-emerald-600 dark:text-emerald-400';
            case 'FAILED': return 'text-red-600 dark:text-red-400';
            case 'CANCELLED': return 'text-gray-500';
            default: return 'text-gray-500';
        }
    }, [workflow.status]);

    return (
        <div className="px-4 py-3 bg-white/80 dark:bg-gray-900/80 backdrop-blur-sm border border-gray-200 dark:border-gray-700 rounded-xl shadow-sm">
            {/* Header */}
            <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2">
                    <span className="text-sm font-semibold text-gray-800 dark:text-gray-200">
                        Coordinator 工作流
                    </span>
                    <span className={`text-xs px-2 py-0.5 rounded-full bg-gray-100 dark:bg-gray-800 ${statusColor}`}>
                        {statusLabel}
                    </span>
                </div>
                <span className="text-[11px] text-gray-400 dark:text-gray-500 font-mono">
                    {workflow.workflowId}
                </span>
            </div>

            {/* Objective */}
            {workflow.objective && (
                <p className="text-xs text-gray-500 dark:text-gray-400 mb-3 truncate" title={workflow.objective}>
                    目标: {workflow.objective}
                </p>
            )}

            {/* Phase stepper */}
            <div className="flex items-start justify-between">
                {workflow.phases.map((phase, idx) => (
                    <PhaseStep
                        key={phase.name}
                        phase={phase}
                        isLast={idx === workflow.phases.length - 1}
                    />
                ))}
            </div>
        </div>
    );
};
