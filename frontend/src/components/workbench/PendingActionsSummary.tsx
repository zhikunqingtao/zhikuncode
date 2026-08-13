import { CircleHelp, ClipboardCheck, ShieldAlert } from 'lucide-react';
import type { WorkbenchPendingAction } from '@/hooks/useSimpleWorkbenchData';

function description(action: WorkbenchPendingAction): string {
    const prompt = action.prompt ?? {};
    const value = action.interactionType === 'permission'
        ? prompt.reason ?? prompt.description
        : prompt.question ?? prompt.description;
    return typeof value === 'string' && value.trim()
        ? value : '请查看当前决定窗口中的影响和选项。';
}

export function PendingActionsSummary({
    actions,
    onSelect,
}: {
    actions: WorkbenchPendingAction[];
    onSelect: (interactionId: string) => void;
}) {
    const firstPermissionId = actions.find(action => action.interactionType === 'permission')?.interactionId;
    return (
        <section className="rounded-2xl border border-[var(--border)] bg-[var(--bg-secondary)] p-5">
            <div className="flex items-center justify-between">
                <h2 className="font-semibold text-[var(--text-primary)]">待我处理</h2>
                <span className={`rounded-full px-2.5 py-1 text-xs ${actions.length ? 'bg-amber-500/15 text-amber-500' : 'bg-green-500/10 text-green-500'}`}>
                    {actions.length ? `${actions.length} 项` : '暂无'}
                </span>
            </div>
            {actions.length === 0 ? (
                <p className="mt-3 text-sm text-[var(--text-muted)]">当前不需要你做决定，任务可以继续推进。</p>
            ) : (
                <div className="mt-3 space-y-2">
                    {actions.map((action) => {
                        const Icon = action.interactionType === 'permission'
                            ? ShieldAlert : action.interactionType === 'plan_approval'
                                ? ClipboardCheck : CircleHelp;
                        const title = action.interactionType === 'permission'
                            ? '需要确认一项操作'
                            : action.interactionType === 'plan_approval'
                                ? '需要确认执行方案' : '需要补充一个选择';
                        const actionable = action.interactionType !== 'permission'
                            || action.interactionId === firstPermissionId;
                        return (
                            <button
                                type="button"
                                key={action.interactionId}
                                onClick={() => onSelect(action.interactionId)}
                                disabled={!actionable}
                                className="flex w-full items-start gap-3 rounded-xl border border-amber-500/30 bg-amber-500/5 p-3 text-left enabled:hover:bg-amber-500/10 disabled:opacity-60"
                            >
                                <Icon className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
                                <span>
                                    <span className="block text-sm font-medium text-[var(--text-primary)]">{title}</span>
                                    <span className="mt-0.5 block line-clamp-2 text-xs text-[var(--text-muted)]">
                                        {actionable ? description(action) : '等待上一项处理完成后即可确认。'}
                                    </span>
                                </span>
                            </button>
                        );
                    })}
                </div>
            )}
        </section>
    );
}
