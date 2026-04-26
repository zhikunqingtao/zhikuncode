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

// ==================== useBreakpoint Hook ====================

type Breakpoint = 'mobile' | 'tablet' | 'desktop';

function useBreakpoint(): Breakpoint {
    const [bp, setBp] = useState<Breakpoint>(() => {
        if (typeof window === 'undefined') return 'desktop';
        const w = window.innerWidth;
        if (w >= 1024) return 'desktop';
        if (w >= 768) return 'tablet';
        return 'mobile';
    });

    useEffect(() => {
        const handleResize = () => {
            const w = window.innerWidth;
            if (w >= 1024) setBp('desktop');
            else if (w >= 768) setBp('tablet');
            else setBp('mobile');
        };
        window.addEventListener('resize', handleResize);
        return () => window.removeEventListener('resize', handleResize);
    }, []);

    return bp;
}

// ==================== StatusIcon ====================

function StatusIcon({ status }: { status: PlanStep['status'] }) {
    switch (status) {
        case 'completed':
            return <CheckCircle className="w-4 h-4 text-green-500 shrink-0" />;
        case 'in_progress':
            return <Loader2 className="w-4 h-4 text-blue-500 animate-spin shrink-0" />;
        case 'failed':
            return <XCircle className="w-4 h-4 text-red-500 shrink-0" />;
        case 'pending':
        default:
            return <Circle className="w-4 h-4 text-[var(--text-muted)] shrink-0" />;
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
                ${isCurrent ? 'bg-blue-500/10 border border-blue-500/30' : 'hover:bg-[var(--bg-hover)]'}`}
        >
            {/* 拖拽把手占位 */}
            <GripVertical className="w-4 h-4 mt-0.5 text-[var(--text-muted)] opacity-0 group-hover:opacity-50 shrink-0 cursor-grab" />

            {/* Checklist 勾选 */}
            <button
                onClick={() => onToggleChecked(step.id)}
                className="mt-0.5 shrink-0 text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors"
            >
                {step.checked
                    ? <CheckSquare className="w-4 h-4 text-green-500" />
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
                        ? 'text-[var(--text-muted)] line-through'
                        : 'text-[var(--text-primary)]'
                }`}>
                    {step.title}
                </div>
                {step.description && (
                    <div className="text-xs text-[var(--text-secondary)] mt-0.5 line-clamp-2">
                        {step.description}
                    </div>
                )}
                {/* 元信息 */}
                <div className="flex items-center gap-3 mt-1">
                    {step.estimatedMinutes != null && (
                        <span className="flex items-center gap-1 text-xs text-[var(--text-muted)]">
                            <Clock className="w-3 h-3" />
                            {step.estimatedMinutes}min
                        </span>
                    )}
                    {step.files && step.files.length > 0 && (
                        <span className="flex items-center gap-1 text-xs text-[var(--text-muted)]">
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
            <div className="flex items-center justify-between text-xs text-[var(--text-secondary)] mb-1">
                <span>进度</span>
                <span>{completed}/{total} ({pct}%)</span>
            </div>
            <div className="h-1.5 bg-[var(--bg-primary)] rounded-full overflow-hidden">
                <div
                    className="h-full bg-blue-500 transition-all duration-300"
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
        <div className="border-t border-[var(--border)] px-3 py-2">
            <div className="flex items-center justify-between mb-2">
                <span className="flex items-center gap-1 text-xs font-medium text-[var(--text-secondary)]">
                    <History className="w-3.5 h-3.5" />
                    版本快照
                </span>
                <button
                    onClick={saveSnapshot}
                    className="text-xs px-2 py-0.5 rounded bg-[var(--bg-hover)] text-[var(--text-secondary)]
                        hover:text-[var(--text-primary)] transition-colors"
                >
                    保存
                </button>
            </div>
            {history.length === 0 ? (
                <div className="text-xs text-[var(--text-muted)] text-center py-2">
                    暂无快照
                </div>
            ) : (
                <div className="space-y-1 max-h-32 overflow-y-auto">
                    {history.map(snap => (
                        <button
                            key={snap.id}
                            onClick={() => restoreSnapshot(snap.id)}
                            className="w-full text-left px-2 py-1.5 rounded text-xs
                                hover:bg-[var(--bg-hover)] text-[var(--text-secondary)] transition-colors"
                        >
                            <div className="truncate font-medium">{snap.planName}</div>
                            <div className="text-[var(--text-muted)]">
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
            <div className="px-3 py-3 border-b border-[var(--border)]">
                <h2 className="text-sm font-semibold text-[var(--text-primary)] truncate">
                    {planName || '执行计划'}
                </h2>
                {planOverview && (
                    <p className="text-xs text-[var(--text-secondary)] mt-1 line-clamp-3">
                        {planOverview}
                    </p>
                )}
            </div>

            {/* Progress */}
            <ProgressBar steps={steps} />

            {/* Steps List */}
            <div className="flex-1 overflow-y-auto px-1 py-1 space-y-1">
                {steps.length === 0 ? (
                    <div className="text-center text-sm text-[var(--text-muted)] py-8">
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
        <aside className="w-80 h-full bg-[var(--bg-secondary)] border-l border-[var(--border)] flex flex-col shrink-0">
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
            className={`h-full bg-[var(--bg-secondary)] border-l border-[var(--border)] flex flex-col shrink-0
                transition-all duration-200 ${expanded ? 'w-72' : 'w-10'}`}
        >
            {/* 切换按钮 */}
            <button
                onClick={() => setExpanded(prev => !prev)}
                className="flex items-center justify-center h-10 border-b border-[var(--border)]
                    text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-hover)]
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
                className="fixed bottom-14 left-0 right-0 z-30 mx-4
                    flex items-center justify-between px-4 py-2
                    bg-[var(--bg-secondary)] border border-[var(--border)] rounded-xl shadow-lg
                    text-sm text-[var(--text-primary)]"
            >
                <span className="font-medium truncate">
                    📋 {usePlanStore.getState().planName || '执行计划'}
                </span>
                <span className="text-xs text-[var(--text-muted)] ml-2 shrink-0">
                    {completedCount}/{steps.length}
                </span>
            </button>

            {/* Overlay */}
            <div
                className={`fixed inset-0 z-40 bg-black/50 transition-opacity duration-200
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
                    bg-[var(--bg-primary)] rounded-t-2xl shadow-2xl
                    transition-transform duration-200 ease-out
                    ${open ? 'translate-y-0' : 'translate-y-full'}`}
                style={{ maxHeight: '75vh' }}
            >
                {/* 抽屉把手 */}
                <div className="flex justify-center py-2">
                    <div className="w-10 h-1 rounded-full bg-[var(--border)]" />
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
    const breakpoint = useBreakpoint();

    if (!isPlanMode) return null;

    switch (breakpoint) {
        case 'desktop':
            return <DesktopPanel />;
        case 'tablet':
            return <TabletPanel />;
        case 'mobile':
            return <MobileDrawer />;
    }
}
