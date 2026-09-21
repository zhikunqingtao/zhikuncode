import { AlertTriangle, ArrowUpRight, CheckCircle2, CircleDashed, ListChecks } from 'lucide-react';
import TextBlock from '@/components/message/TextBlock';
import type { CurrentWorkbenchView } from '@/hooks/useSimpleWorkbenchData';

export function StructuredResultCard({ current, loading, error, onOpenMessage }: {
    current: CurrentWorkbenchView | null;
    loading: boolean;
    error: string | null;
    onOpenMessage: (messageId: string) => void;
}) {
    if (loading) return <section className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-4 md:p-6 text-sm text-[var(--v2-text-2)]">正在整理本次结果…</section>;
    if (error) return <section className="rounded-[14px] border border-err bg-[var(--v2-bg-sunken)] p-4 md:p-6 text-sm text-err">当前结果暂时无法读取：{error}</section>;
    const summary = current?.structuredSummary;
    const result = current?.result;
    const hasSections = Boolean(summary?.completed.length || summary?.issues.length || summary?.nextSteps.length);
    return (
        <section className="overflow-hidden rounded-[14px] border border-accent2-ring bg-[var(--v2-bg-sunken)] shadow-e1">
            <div className="border-b border-[var(--v2-border-hairline)] px-4 md:px-6 py-4">
                <p className="text-[13px] font-medium uppercase tracking-wide text-accent2-ink dark:text-accent2-ink">本次结果</p>
                <h2 className="mt-1   text-[var(--v2-text-1)] text-xl font-semibold">
                    {current?.currentFailure ? '本轮执行未成功完成' : result ? '先看结论与主要成果' : '任务尚未形成最终回复'}
                </h2>
            </div>
            <div className="space-y-4 p-4 md:p-6">
                {current?.currentFailure && (
                    <div className="flex gap-3 rounded-[14px] border border-err bg-errsoft p-4">
                        <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-err" />
                        <div><p className="font-medium text-err">当前失败：{current.currentFailure.status}</p><p className="mt-1 text-sm text-[var(--v2-text-2)]">{current.currentFailure.reason}</p></div>
                    </div>
                )}
                {summary?.conclusion && (
                    <div className="rounded-[14px] bg-[var(--v2-bg-surface)] p-4">
                        <p className="mb-2 text-[13px] font-medium text-[var(--v2-text-2)]">总体结论</p>
                        <p className="text-[15px] max-md:text-base leading-[1.7] max-md:leading-[1.7] text-[var(--v2-text-1)]">{summary.conclusion}</p>
                    </div>
                )}
                {hasSections && (
                    <div className="grid gap-3 lg:grid-cols-3">
                        <SummaryList title="已完成" icon={CheckCircle2} tone="text-ok" items={summary?.completed ?? []} />
                        <SummaryList title="问题与限制" icon={AlertTriangle} tone="text-warnstrong dark:text-warn" items={summary?.issues ?? []} />
                        <SummaryList title="下一步" icon={ListChecks} tone="text-accent2-ink dark:text-accent2-ink" items={summary?.nextSteps ?? []} />
                    </div>
                )}
                {!result && !current?.currentFailure && <div className="flex gap-3 text-sm text-[var(--v2-text-2)]"><CircleDashed className="h-5 w-5" />执行完成后，这里会先呈现规则提取的结论；提取不到时仍保留完整原文。</div>}
                {result && (
                    <details className="rounded-[14px] border border-[var(--v2-border-hairline)]">
                        <summary className="workbench-control cursor-pointer px-4 py-3 text-sm font-medium text-[var(--v2-text-2)] hover:bg-[var(--v2-bg-hover)]">展开完整回复</summary>
                        <div className="border-t border-[var(--v2-border-hairline)] p-4"><TextBlock text={result.text} />
                            <button type="button" onClick={() => onOpenMessage(result.messageId)} className="workbench-control mt-4 inline-flex items-center gap-1.5 rounded-[10px] border border-accent2-ring bg-accent2-soft px-3 py-2 text-sm font-medium text-accent2-ink dark:text-accent2-ink hover:bg-[color:color-mix(in_srgb,var(--v2-accent-hover)_15%,transparent)]">在完整对话中查看<ArrowUpRight className="h-4 w-4" /></button>
                        </div>
                    </details>
                )}
            </div>
        </section>
    );
}

function SummaryList({ title, icon: Icon, tone, items }: { title: string; icon: typeof CheckCircle2; tone: string; items: string[] }) {
    if (items.length === 0) return null;
    return <div className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-surface)] p-4"><div className={`flex items-center gap-2 text-sm font-medium ${tone}`}><Icon className="h-4 w-4" />{title}</div><ul className="mt-3 space-y-2 text-[15px] max-md:text-base leading-[1.7] max-md:leading-[1.7] text-[var(--v2-text-2)]">{items.slice(0, 8).map((item, index) => <li key={`${index}-${item}`} className="flex gap-2"><span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-current opacity-70" /><span>{item}</span></li>)}</ul></div>;
}
