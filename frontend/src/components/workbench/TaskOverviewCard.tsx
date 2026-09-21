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
        <section className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] p-4 md:p-6 shadow-e1">
            <div className="flex flex-wrap items-start justify-between gap-4">
                <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                        <p className="text-[13px] font-medium uppercase tracking-wide text-accent2-ink dark:text-accent2-ink">当前任务</p>
                        {correlationMode === 'LEGACY_FALLBACK' && (
                            <span className="inline-flex items-center gap-1 rounded-full border border-warn bg-warnsoft px-2 py-0.5 text-[13px] text-warnstrong dark:text-warn">
                                <History className="h-3 w-3" />历史记录
                            </span>
                        )}
                    </div>
                    <h1 className="mt-1 truncate max-md:whitespace-normal max-md:line-clamp-2 text-xl font-semibold text-[var(--v2-text-1)]">{loading ? '正在读取任务' : error ? '任务信息暂时无法读取' : title}</h1>
                </div>
                <span className="inline-flex items-center gap-1.5 text-[13px] text-[var(--v2-text-2)]" title={session?.workingDir}><Folder className="h-4 w-4" />{folder}</span>
            </div>
            {!loading && error && <p className="mt-3 rounded-[14px] border border-err bg-errsoft p-3 text-sm text-err">无法读取当前任务：{error}</p>}
            <div className="mt-4 flex gap-3 rounded-[14px] bg-[var(--v2-bg-surface)] p-4">
                <MessageSquareText className="mt-0.5 h-4 w-4 shrink-0 text-accent2-ink dark:text-accent2-ink" />
                <div className="min-w-0">
                    <p className="text-[13px] text-[var(--v2-text-2)]">本次要求</p>
                    <p className="mt-1 line-clamp-4 whitespace-pre-wrap text-[15px] max-md:text-base leading-[1.7] max-md:leading-[1.7] text-[var(--v2-text-1)]">{request?.text || '输入你希望完成的事情'}</p>
                </div>
            </div>
        </section>
    );
}
