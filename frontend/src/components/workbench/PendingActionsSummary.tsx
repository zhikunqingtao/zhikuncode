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
        <section className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-4 md:p-6">
            <div className="flex items-center justify-between">
                <h2 className="text-[var(--v2-text-1)] text-xl font-semibold">待我处理</h2>
                <span className={`rounded-full px-2.5 py-1 text-[13px] ${actions.length ? 'bg-warnsoft text-warnstrong dark:text-warn' : 'bg-oksoft text-ok'}`}>
                    {actions.length ? `${actions.length} 项` : '暂无'}
                </span>
            </div>
            {actions.length === 0 ? (
                <p className="mt-3 text-sm text-[var(--v2-text-2)]">当前不需要你做决定，任务可以继续推进。</p>
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
                                className="workbench-control flex w-full items-start gap-3 rounded-[14px] border border-warn bg-warnsoft p-3 text-left enabled:hover:bg-[color:color-mix(in_srgb,var(--v2-accent-hover)_10%,transparent)] disabled:opacity-60"
                            >
                                <Icon className="mt-0.5 h-4 w-4 shrink-0 text-warnstrong dark:text-warn" />
                                <span>
                                    <span className="block text-sm font-medium text-[var(--v2-text-1)]">{title}</span>
                                    <span className="mt-0.5 block line-clamp-2 text-[13px] text-[var(--v2-text-2)]">
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
