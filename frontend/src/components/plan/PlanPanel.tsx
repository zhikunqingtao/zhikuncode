/**
 * PlanPanel — 计划模式面板组件
 * SPEC: §F7 Plan Mode
 *
 * 响应式三段布局:
 * - 桌面端 (>=1024px): 侧边栏常驻 w-80
 * - 平板端 (768-1023px): 折叠为图标条 w-10，可展开
 * - 手机端 (<768px): 底部抽屉模式
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import {
    CheckCircle,
    Circle,
    Loader2,
    XCircle,
    FileText,
    Clock,
    ChevronLeft,
    ChevronRight,
    GripVertical,
    History,
    CheckSquare,
    Square,
} from 'lucide-react';
import { usePlanStore, type PlanStep } from '@/store/planStore';
import { useResponsive } from '@/hooks/useResponsive';

// ==================== StatusIcon ====================

function StatusIcon({ status }: { status: PlanStep['status'] }) {
    switch (status) {
        case 'completed':
            return <CheckCircle className="w-4 h-4 text-ok shrink-0" />;
        case 'in_progress':
            return <Loader2 className="w-4 h-4 text-accent2-ink animate-spin shrink-0" />;
        case 'failed':
            return <XCircle className="w-4 h-4 text-err shrink-0" />;
        case 'pending':
        default:
            return <Circle className="w-4 h-4 text-[var(--v2-text-2)] shrink-0" />;
    }
}

// ==================== StepItem ====================

interface StepItemProps {
    step: PlanStep;
    isCurrent: boolean;
    onToggleChecked: (id: string) => void;
}

function StepItem({ step, isCurrent, onToggleChecked }: StepItemProps) {
    return (
        <div
            className={`group flex items-start gap-2 px-3 py-2 rounded-lg transition-colors
                ${isCurrent ? 'bg-accent2-soft border border-blue-500/30' : 'hover:bg-[var(--v2-bg-hover)]'}`}
        >
            {/* 拖拽把手占位 */}
            <GripVertical className="w-4 h-4 mt-0.5 text-[var(--v2-text-2)] opacity-0 group-hover:opacity-50 shrink-0 cursor-grab" />

            {/* Checklist 勾选 */}
            <button
                onClick={() => onToggleChecked(step.id)}
                className="panel-control mt-0.5 shrink-0 text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)] transition-colors"
            >
                {step.checked
                    ? <CheckSquare className="w-4 h-4 text-ok" />
                    : <Square className="w-4 h-4" />
                }
            </button>

            {/* 状态图标 */}
            <div className="mt-0.5">
                <StatusIcon status={step.status} />
            </div>

            {/* 内容 */}
            <div className="flex-1 min-w-0">
                <div className={`text-sm font-medium truncate ${
                    step.status === 'completed'
                        ? 'text-[var(--v2-text-2)] line-through'
                        : 'text-[var(--v2-text-1)]'
                }`}>
                    {step.title}
                </div>
                {step.description && (
                    <div className="text-[13px] text-[var(--v2-text-2)] mt-0.5 line-clamp-2">
                        {step.description}
                    </div>
                )}
                {/* 元信息 */}
                <div className="flex items-center gap-3 mt-1">
                    {step.estimatedMinutes != null && (
                        <span className="flex items-center gap-1 text-[13px] text-[var(--v2-text-2)]">
                            <Clock className="w-3 h-3" />
                            {step.estimatedMinutes}min
                        </span>
                    )}
                    {step.files && step.files.length > 0 && (
                        <span className="flex items-center gap-1 text-[13px] text-[var(--v2-text-2)]">
                            <FileText className="w-3 h-3" />
                            {step.files.length} 文件
                        </span>
                    )}
                </div>
            </div>
        </div>
    );
}

// ==================== ProgressBar ====================

function ProgressBar({ steps }: { steps: PlanStep[] }) {
    const { completed, total } = useMemo(() => {
        const total = steps.length;
        const completed = steps.filter(s => s.status === 'completed').length;
        return { completed, total };
    }, [steps]);

    const pct = total === 0 ? 0 : Math.round((completed / total) * 100);

    return (
        <div className="px-3 py-2">
            <div className="flex items-center justify-between text-[13px] text-[var(--v2-text-2)] mb-1">
                <span>进度</span>
                <span>{completed}/{total} ({pct}%)</span>
            </div>
            <div className="h-1.5 bg-[var(--v2-bg-surface)] rounded-full overflow-hidden">
                <div
                    className="h-full bg-blue-500 transition-[width] duration-slow"
                    style={{ width: `${pct}%` }}
                />
            </div>
        </div>
    );
}

// ==================== SnapshotHistory ====================

function SnapshotHistory() {
    const { history, restoreSnapshot, saveSnapshot } = usePlanStore();

    return (
        <div className="border-t border-[var(--v2-border-hairline)] px-3 py-2">
            <div className="flex items-center justify-between mb-2">
                <span className="flex items-center gap-1 text-[13px] font-medium text-[var(--v2-text-2)]">
                    <History className="w-3.5 h-3.5" />
                    版本快照
                </span>
                <button
                    onClick={saveSnapshot}
                    className="panel-control text-[13px] px-2 py-0.5 rounded bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)]
                        hover:text-[var(--v2-text-1)] transition-colors"
                >
                    保存
                </button>
            </div>
            {history.length === 0 ? (
                <div className="text-[13px] text-[var(--v2-text-2)] text-center py-2">
                    暂无快照
                </div>
            ) : (
                <div className="space-y-1 max-h-32 overflow-y-auto">
                    {history.map(snap => (
                        <button
                            key={snap.id}
                            onClick={() => restoreSnapshot(snap.id)}
                            className="panel-control w-full text-left px-2 py-1.5 rounded text-[13px]
                                hover:bg-[var(--v2-bg-hover)] text-[var(--v2-text-2)] transition-colors"
                        >
                            <div className="truncate font-medium">{snap.planName}</div>
                            <div className="text-[var(--v2-text-2)]">
                                {new Date(snap.createdAt).toLocaleTimeString()} · {snap.steps.length} 步骤
                            </div>
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
}

// ==================== PlanPanelContent (共享内容) ====================

function PlanPanelContent() {
    const { planName, planOverview, steps, currentStepId, toggleStepChecked } = usePlanStore();

    return (
        <div className="flex flex-col h-full">
            {/* Header */}
            <div className="px-3 py-3 border-b border-[var(--v2-border-hairline)]">
                <h2 className="text-[var(--v2-text-1)] truncate text-xl font-semibold">
                    {planName || '执行计划'}
                </h2>
                {planOverview && (
                    <p className="text-[13px] text-[var(--v2-text-2)] mt-1 line-clamp-3">
                        {planOverview}
                    </p>
                )}
            </div>

            {/* Progress */}
            <ProgressBar steps={steps} />

            {/* Steps List */}
            <div className="flex-1 overflow-y-auto px-1 py-1 space-y-1">
                {steps.length === 0 ? (
                    <div className="text-center text-sm text-[var(--v2-text-2)] py-8">
                        暂无步骤
                    </div>
                ) : (
                    steps.map(step => (
                        <StepItem
                            key={step.id}
                            step={step}
                            isCurrent={step.id === currentStepId}
                            onToggleChecked={toggleStepChecked}
                        />
                    ))
                )}
            </div>

            {/* Snapshot History */}
            <SnapshotHistory />
        </div>
    );
}

// ==================== 桌面端: 常驻侧边栏 ====================

function DesktopPanel() {
    return (
        <aside className="w-80 h-full bg-[var(--v2-bg-sunken)] border-l border-[var(--v2-border-hairline)] flex flex-col shrink-0">
            <PlanPanelContent />
        </aside>
    );
}

// ==================== 平板端: 折叠图标条 / 可展开 ====================

function TabletPanel() {
    const [expanded, setExpanded] = useState(false);
    const steps = usePlanStore(state => state.steps);

    return (
        <aside
            className={`h-full bg-[var(--v2-bg-sunken)] border-l border-[var(--v2-border-hairline)] flex flex-col shrink-0
                transition-[width] duration-base ${expanded ? 'w-72' : 'w-10'}`}
        >
            {/* 切换按钮 */}
            <button
                onClick={() => setExpanded(prev => !prev)}
                className="panel-control flex items-center justify-center h-10 border-b border-[var(--v2-border-hairline)]
                    text-[var(--v2-text-2)] hover:text-[var(--v2-text-1)] hover:bg-[var(--v2-bg-hover)]
                    transition-colors"
                title={expanded ? '收起计划面板' : '展开计划面板'}
            >
                {expanded ? <ChevronRight className="w-4 h-4" /> : <ChevronLeft className="w-4 h-4" />}
            </button>

            {expanded ? (
                <PlanPanelContent />
            ) : (
                /* 图标条模式: 显示步骤状态图标 */
                <div className="flex-1 overflow-y-auto py-2 space-y-2">
                    {steps.map(step => (
                        <div key={step.id} className="flex items-center justify-center" title={step.title}>
                            <StatusIcon status={step.status} />
                        </div>
                    ))}
                </div>
            )}
        </aside>
    );
}

// ==================== 手机端: 底部抽屉 ====================

function MobileDrawer() {
    const [open, setOpen] = useState(false);
    const { steps } = usePlanStore();

    const completedCount = useMemo(
        () => steps.filter(s => s.status === 'completed').length,
        [steps],
    );

    const handleKeyDown = useCallback((e: KeyboardEvent) => {
        if (e.key === 'Escape') setOpen(false);
    }, []);

    useEffect(() => {
        if (open) {
            document.addEventListener('keydown', handleKeyDown);
            document.body.style.overflow = 'hidden';
            return () => {
                document.removeEventListener('keydown', handleKeyDown);
                document.body.style.overflow = '';
            };
        }
    }, [open, handleKeyDown]);

    return (
        <>
            {/* 底部触发条 */}
            <button
                onClick={() => setOpen(true)}
                className="panel-control fixed bottom-14 left-0 right-0 z-30 mx-4
                    flex items-center justify-between px-4 py-2
                    bg-[var(--v2-bg-sunken)] border border-[var(--v2-border-hairline)] rounded-xl shadow-lg
                    text-sm text-[var(--v2-text-1)]"
            >
                <span className="font-medium truncate">
                    📋 {usePlanStore.getState().planName || '执行计划'}
                </span>
                <span className="text-[13px] text-[var(--v2-text-2)] ml-2 shrink-0">
                    {completedCount}/{steps.length}
                </span>
            </button>

            {/* Overlay */}
            <div
                className={`fixed inset-0 z-40 bg-black/50 transition-opacity duration-base
                    ${open ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
                onClick={() => setOpen(false)}
                aria-hidden="true"
            />

            {/* 抽屉面板 */}
            <div
                role="dialog"
                aria-modal="true"
                aria-label="计划面板"
                className={`fixed bottom-0 left-0 right-0 z-50
                    bg-[var(--v2-bg-surface)] rounded-t-2xl shadow-2xl
                    transition-transform duration-base ease-out
                    ${open ? 'translate-y-0' : 'translate-y-full'}`}
                style={{ maxHeight: '75vh' }}
            >
                {/* 抽屉把手 */}
                <div className="flex justify-center py-2">
                    <div className="w-10 h-1 rounded-full bg-[var(--v2-border-hairline)]" />
                </div>
                <div className="overflow-y-auto overscroll-contain" style={{ maxHeight: 'calc(75vh - 24px)' }}>
                    <PlanPanelContent />
                </div>
            </div>
        </>
    );
}

// ==================== PlanPanel (入口) ====================

export function PlanPanel() {
    const isPlanMode = usePlanStore(s => s.isPlanMode);
    // §8.1 断点统一：<768 mobile / 768–1023 compact(tablet) / ≥1024 desktop，语义与原 useBreakpoint 一一映射
    const { isTablet, isDesktop } = useResponsive();

    if (!isPlanMode) return null;

    if (isDesktop) return <DesktopPanel />;
    if (isTablet) return <TabletPanel />;
    // isMobile（<768px）：底部抽屉
    return <MobileDrawer />;
}
