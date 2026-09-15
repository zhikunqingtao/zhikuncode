import { AlertCircle, ArrowUpRight, CheckCircle2, Clock3 } from 'lucide-react';
import type { SimpleActivityGroup } from '@/utils/simpleActivityProjection';

function compactPath(path: string): string {
    const parts = path.replaceAll('\\', '/').split('/').filter(Boolean);
    const sourceIndex = parts.findIndex(part => ['src', 'docs', 'frontend', 'backend'].includes(part));
    if (sourceIndex >= 0 && parts.length - sourceIndex <= 5) return parts.slice(sourceIndex).join('/');
    return parts.slice(-3).join('/');
}

export function ResultActivityList({
    activities,
    onOpenActivity,
}: {
    activities: SimpleActivityGroup[];
    onOpenActivity?: (activityId: string) => void;
}) {
    return (
        <section className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-4 md:p-6">
            <div className="mb-4 flex items-center justify-between">
                <h2 className="text-[var(--v2-text-1)] text-xl font-semibold">最近进展</h2>
                <span className="text-[13px] text-[var(--v2-text-2)]">最近 {Math.min(activities.length, 20)} 组</span>
            </div>
            {activities.length === 0 ? (
                <div className="rounded-[14px] border border-dashed border-[var(--v2-border-hairline)] p-6 text-center text-sm text-[var(--v2-text-2)]">
                    任务开始后，这里会用结果语言显示进展。
                </div>
            ) : (
                <ol className="space-y-3">
                    {activities.map(activity => (
                        <li key={activity.key} className="flex gap-3 rounded-[14px] bg-[var(--v2-bg-surface)] p-3">
                            {activity.failed
                                ? <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-err" />
                                : <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-ok" />}
                            <div className="min-w-0 flex-1">
                                <p className="text-sm text-[var(--v2-text-1)]">
                                    {activity.label}{activity.count > 1 ? `（${activity.count} 次）` : ''}
                                </p>
                                {activity.detail && (
                                    <p className="mt-1 line-clamp-2 text-[13px] leading-5 text-[var(--v2-text-2)]" title={activity.detail}>
                                        {activity.detail}
                                    </p>
                                )}
                                {activity.files.length > 0 && (
                                    <p className="mt-1 truncate text-[13px] text-[var(--v2-text-2)]" title={activity.files.join(', ')}>
                                        {activity.files.map(compactPath).join('、')}
                                    </p>
                                )}
                            </div>
                            <span className="flex shrink-0 items-center gap-1 text-[13px] text-[var(--v2-text-2)]">
                                <Clock3 className="h-3 w-3" />
                                {new Date(activity.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                            </span>
                            {onOpenActivity && activity.activityIds.length > 0 && (
                                <button
                                    type="button"
                                    onClick={() => onOpenActivity(activity.activityIds.at(-1)!)}
                                    className="workbench-control shrink-0 rounded p-1 text-[var(--v2-text-2)] hover:bg-[var(--v2-bg-hover)] hover:text-accent2-ink dark:text-accent2-ink"
                                    title="查看对应技术记录"
                                    aria-label={`查看“${activity.label}”的技术记录`}
                                >
                                    <ArrowUpRight className="h-3.5 w-3.5" />
                                </button>
                            )}
                        </li>
                    ))}
                </ol>
            )}
        </section>
    );
}
