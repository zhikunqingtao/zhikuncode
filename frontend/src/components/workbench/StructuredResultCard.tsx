import { AlertTriangle, ArrowUpRight, CheckCircle2, CircleDashed, ListChecks } from 'lucide-react';
import TextBlock from '@/components/message/TextBlock';
import type { CurrentWorkbenchView } from '@/hooks/useSimpleWorkbenchData';

export function StructuredResultCard({ current, loading, error, onOpenMessage }: {
    current: CurrentWorkbenchView | null;
    loading: boolean;
    error: string | null;
    onOpenMessage: (messageId: string) => void;
}) {
    if (loading) return <section className="rounded-2xl border border-[var(--border)] bg-[var(--bg-secondary)] p-5 text-sm text-[var(--text-muted)]">正在整理本次结果…</section>;
    if (error) return <section className="rounded-2xl border border-red-500/30 bg-[var(--bg-secondary)] p-5 text-sm text-red-400">当前结果暂时无法读取：{error}</section>;
    const summary = current?.structuredSummary;
    const result = current?.result;
    const hasSections = Boolean(summary?.completed.length || summary?.issues.length || summary?.nextSteps.length);
    return (
        <section className="overflow-hidden rounded-2xl border border-blue-500/25 bg-[var(--bg-secondary)] shadow-sm">
            <div className="border-b border-[var(--border)] px-5 py-4">
                <p className="text-xs font-medium uppercase tracking-wide text-blue-500">本次结果</p>
                <h2 className="mt-1 text-lg font-semibold text-[var(--text-primary)]">
                    {current?.currentFailure ? '本轮执行未成功完成' : result ? '先看结论与主要成果' : '任务尚未形成最终回复'}
                </h2>
            </div>
            <div className="space-y-4 p-5">
                {current?.currentFailure && (
                    <div className="flex gap-3 rounded-xl border border-red-500/25 bg-red-500/5 p-4">
                        <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-red-500" />
                        <div><p className="font-medium text-red-400">当前失败：{current.currentFailure.status}</p><p className="mt-1 text-sm text-[var(--text-secondary)]">{current.currentFailure.reason}</p></div>
                    </div>
                )}
                {summary?.conclusion && (
                    <div className="rounded-xl bg-[var(--bg-primary)] p-4">
                        <p className="mb-2 text-xs font-medium text-[var(--text-muted)]">总体结论</p>
                        <p className="text-base leading-7 text-[var(--text-primary)]">{summary.conclusion}</p>
                    </div>
                )}
                {hasSections && (
                    <div className="grid gap-3 lg:grid-cols-3">
                        <SummaryList title="已完成" icon={CheckCircle2} tone="text-green-500" items={summary?.completed ?? []} />
                        <SummaryList title="问题与限制" icon={AlertTriangle} tone="text-amber-500" items={summary?.issues ?? []} />
                        <SummaryList title="下一步" icon={ListChecks} tone="text-blue-500" items={summary?.nextSteps ?? []} />
                    </div>
                )}
                {!result && !current?.currentFailure && <div className="flex gap-3 text-sm text-[var(--text-muted)]"><CircleDashed className="h-5 w-5" />执行完成后，这里会先呈现规则提取的结论；提取不到时仍保留完整原文。</div>}
                {result && (
                    <details className="rounded-xl border border-[var(--border)]">
                        <summary className="cursor-pointer px-4 py-3 text-sm font-medium text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]">展开完整回复</summary>
                        <div className="border-t border-[var(--border)] p-4"><TextBlock text={result.text.replace(/^\[(?:skeleton|final)\]\s*/i, '')} />
                            <button type="button" onClick={() => onOpenMessage(result.messageId)} className="mt-4 inline-flex items-center gap-1.5 rounded-lg border border-blue-500/30 bg-blue-500/10 px-3 py-2 text-sm font-medium text-blue-400 hover:bg-blue-500/15">在完整对话中查看<ArrowUpRight className="h-4 w-4" /></button>
                        </div>
                    </details>
                )}
            </div>
        </section>
    );
}

function SummaryList({ title, icon: Icon, tone, items }: { title: string; icon: typeof CheckCircle2; tone: string; items: string[] }) {
    if (items.length === 0) return null;
    return <div className="rounded-xl border border-[var(--border)] bg-[var(--bg-primary)] p-4"><div className={`flex items-center gap-2 text-sm font-medium ${tone}`}><Icon className="h-4 w-4" />{title}</div><ul className="mt-3 space-y-2 text-sm leading-6 text-[var(--text-secondary)]">{items.slice(0, 8).map((item, index) => <li key={`${index}-${item}`} className="flex gap-2"><span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-current opacity-70" /><span>{item}</span></li>)}</ul></div>;
}
