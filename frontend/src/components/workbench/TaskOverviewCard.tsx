import { Folder, History, MessageSquareText } from 'lucide-react';
import type { SessionDetail, WorkbenchMessage } from '@/hooks/useSimpleWorkbenchData';
import { taskTitle } from '@/utils/workbenchPresentation';

export function TaskOverviewCard({
    session, request, correlationMode, loading, error,
}: {
    session: SessionDetail | null;
    request: WorkbenchMessage | null;
    correlationMode: 'EXACT' | 'LEGACY_FALLBACK';
    loading: boolean;
    error: string | null;
}) {
    // 当前投影的 request 是标题回退的唯一消息来源，禁止会话尾部消息串入当前版本。
    const derivedTitle = taskTitle(session?.title, [], session?.workingDir, request?.text ?? undefined);
    const title = derivedTitle;
    const folder = session?.workingDir?.split('/').filter(Boolean).at(-1) ?? '尚未选择';

    return (
        <section className="rounded-2xl border border-[var(--border)] bg-[var(--bg-secondary)] p-5 shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                        <p className="text-xs font-medium uppercase tracking-wide text-blue-500">当前任务</p>
                        {correlationMode === 'LEGACY_FALLBACK' && (
                            <span className="inline-flex items-center gap-1 rounded-full border border-amber-500/30 bg-amber-500/10 px-2 py-0.5 text-[11px] text-amber-400">
                                <History className="h-3 w-3" />历史记录
                            </span>
                        )}
                    </div>
                    <h1 className="mt-1 truncate text-xl font-semibold text-[var(--text-primary)]">{loading ? '正在读取任务' : error ? '任务信息暂时无法读取' : title}</h1>
                </div>
                <span className="inline-flex items-center gap-1.5 text-xs text-[var(--text-muted)]" title={session?.workingDir}><Folder className="h-4 w-4" />{folder}</span>
            </div>
            {!loading && error && <p className="mt-3 rounded-xl border border-red-500/30 bg-red-500/5 p-3 text-sm text-red-500">无法读取当前任务：{error}</p>}
            <div className="mt-4 flex gap-3 rounded-xl bg-[var(--bg-primary)] p-4">
                <MessageSquareText className="mt-0.5 h-4 w-4 shrink-0 text-blue-500" />
                <div className="min-w-0">
                    <p className="text-xs text-[var(--text-muted)]">本次要求</p>
                    <p className="mt-1 line-clamp-4 whitespace-pre-wrap text-sm leading-6 text-[var(--text-primary)]">{request?.text || '输入你希望完成的事情'}</p>
                </div>
            </div>
        </section>
    );
}
