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
        <section className="rounded-2xl border border-[var(--border)] bg-[var(--bg-secondary)] p-5">
            <div className="mb-4 flex items-center justify-between">
                <h2 className="font-semibold text-[var(--text-primary)]">最近进展</h2>
                <span className="text-xs text-[var(--text-muted)]">最近 {Math.min(activities.length, 20)} 组</span>
            </div>
            {activities.length === 0 ? (
                <div className="rounded-xl border border-dashed border-[var(--border)] p-6 text-center text-sm text-[var(--text-muted)]">
                    任务开始后，这里会用结果语言显示进展。
                </div>
            ) : (
                <ol className="space-y-3">
                    {activities.map(activity => (
                        <li key={activity.key} className="flex gap-3 rounded-xl bg-[var(--bg-primary)] p-3">
                            {activity.failed
                                ? <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-500" />
                                : <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-green-500" />}
                            <div className="min-w-0 flex-1">
                                <p className="text-sm text-[var(--text-primary)]">
                                    {activity.label}{activity.count > 1 ? `（${activity.count} 次）` : ''}
                                </p>
                                {activity.detail && (
                                    <p className="mt-1 line-clamp-2 text-xs leading-5 text-[var(--text-muted)]" title={activity.detail}>
                                        {activity.detail}
                                    </p>
                                )}
                                {activity.files.length > 0 && (
                                    <p className="mt-1 truncate text-xs text-[var(--text-muted)]" title={activity.files.join(', ')}>
                                        {activity.files.map(compactPath).join('、')}
                                    </p>
                                )}
                            </div>
                            <span className="flex shrink-0 items-center gap-1 text-[11px] text-[var(--text-muted)]">
                                <Clock3 className="h-3 w-3" />
                                {new Date(activity.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                            </span>
                            {onOpenActivity && activity.activityIds.length > 0 && (
                                <button
                                    type="button"
                                    onClick={() => onOpenActivity(activity.activityIds.at(-1)!)}
                                    className="shrink-0 rounded p-1 text-[var(--text-muted)] hover:bg-[var(--bg-hover)] hover:text-blue-400"
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
