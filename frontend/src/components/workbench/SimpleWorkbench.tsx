import { useMemo, useState } from 'react';
import { AlertTriangle, ArrowRight, Eye, FolderOpen, MessageSquareText } from 'lucide-react';
import type { Message } from '@/types';
import type { SessionStoreState } from '@/store/sessionStore';
import { useActivityStore } from '@/store/activityStore';
import { useAppUiStore } from '@/store/appUiStore';
import { recoverPendingInteractions } from '@/api/dispatch';
import { useWorkbenchViewStore } from '@/store/workbenchViewStore';
import { useFileTreeStore } from '@/store/fileTreeStore';
import { useSimpleWorkbenchData } from '@/hooks/useSimpleWorkbenchData';
import { projectActivities } from '@/utils/simpleActivityProjection';
import { TaskOverviewCard } from './TaskOverviewCard';
import { StructuredResultCard } from './StructuredResultCard';
import { ResultActivityList } from './ResultActivityList';
import { PendingActionsSummary } from './PendingActionsSummary';
import { DeliverablesSummary } from './DeliverablesSummary';
import { AcceptanceCriteriaView } from './AcceptanceCriteriaView';
import { TaskMilestoneStrip } from './TaskMilestoneStrip';
import { FilePreviewDialog } from './FilePreviewDialog';

export function SimpleWorkbench({ sessionId, messages: _messages, status: _status }: {
    sessionId: string | null; messages: Message[]; status: SessionStoreState['status'];
}) {
    const data = useSimpleWorkbenchData(sessionId);
    const current = data.current.data;
    const setViewMode = useWorkbenchViewStore(state => state.setViewMode);
    const openMessageInDevelopment = useWorkbenchViewStore(state => state.openMessageInDevelopment);
    const requestVisualizationTab = useAppUiStore(state => state.requestVisualizationTab);
    const setL3ActivityId = useActivityStore(state => state.setL3ActivityId);
    const setSelectedFile = useFileTreeStore(state => state.setSelected);
    const fetchFileTree = useFileTreeStore(state => state.fetchTree);
    const [previewPath, setPreviewPath] = useState<string | null>(null);
    const [openError, setOpenError] = useState<string | null>(null);
    const activities = useMemo(() => projectActivities(current?.activities ?? []), [current?.activities]);
    const openActivity = (activityId: string) => { setL3ActivityId(activityId); requestVisualizationTab('apos'); setViewMode('development'); };
    const openFile = (filePath: string) => {
        const root = data.session.data?.workingDir?.replace(/\/$/, '');
        const relative = root && filePath.startsWith(`${root}/`) ? filePath.slice(root.length + 1) : filePath;
        setSelectedFile(relative); void fetchFileTree('.');
        requestVisualizationTab('files'); setViewMode('development');
    };
    const revealFile = async (filePath: string) => {
        if (!sessionId) return; setOpenError(null);
        try {
            const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/files/reveal`, {
                method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Session-Id': sessionId, 'X-Zhikun-User-Gesture': 'reveal-file' }, body: JSON.stringify({ path: filePath }),
            });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
        } catch (error) { setOpenError(`无法在文件夹中显示该文件：${error instanceof Error ? error.message : String(error)}`); }
    };
    const delivery = current?.delivery ?? { manifests: [], files: [], totalFiles: 0, primaryArtifactPath: null };
    const failedWithPrevious = Boolean(current?.currentFailure && current.previousAvailableDelivery);
    const canPreview = (path: string) => /\.(?:pdf|png|jpe?g|gif|webp|bmp|svg|txt|md|markdown|json|ya?ml|xml|csv|log)$/i.test(path);
    const openResult = (path: string) => {
        if (canPreview(path)) setPreviewPath(path);
        else void revealFile(path);
    };
    const primaryAction = (() => {
        if ((current?.pendingActionCount ?? 0) > 0) return {
            label: `处理 ${current!.pendingActionCount} 项待确认`, icon: MessageSquareText,
            action: () => document.getElementById('simple-pending-actions')?.scrollIntoView({ behavior: 'smooth', block: 'center' }),
            tone: 'amber',
        };
        if (current?.currentFailure && current.previousAvailableDelivery?.delivery.primaryArtifactPath) return {
            label: '打开上次可用成果', icon: AlertTriangle,
            action: () => openResult(current.previousAvailableDelivery!.delivery.primaryArtifactPath!), tone: 'amber',
        };
        if (delivery.primaryArtifactPath) return {
            label: canPreview(delivery.primaryArtifactPath) ? '查看本次主要成果' : '在文件夹中显示成果',
            icon: canPreview(delivery.primaryArtifactPath) ? Eye : FolderOpen,
            action: () => openResult(delivery.primaryArtifactPath!), tone: 'blue',
        };
        return null;
    })();
    const focusPendingAction = async (interactionId: string) => {
        if (sessionId) await recoverPendingInteractions(sessionId);
        window.setTimeout(() => {
            const dialog = Array.from(document.querySelectorAll<HTMLElement>(
                '[role="dialog"][data-interaction-id], [role="alertdialog"][data-interaction-id]',
            )).find(element => element.dataset.interactionId === interactionId);
            dialog?.focus(); dialog?.scrollIntoView({ block: 'center' });
        }, 0);
    };

    return <div className="h-full overflow-y-auto bg-[var(--bg-primary)]"><div className="mx-auto max-w-6xl space-y-4 p-4 md:p-6">
        <TaskOverviewCard session={data.session.data} request={current?.request ?? null}
            correlationMode={current?.correlationMode ?? 'LEGACY_FALLBACK'}
            loading={data.session.loading || data.current.loading} error={data.session.error ?? data.current.error} />
        <TaskMilestoneStrip current={current} />
        {primaryAction && <section className={`flex flex-wrap items-center justify-between gap-3 rounded-2xl border p-4 ${primaryAction.tone === 'amber' ? 'border-amber-500/30 bg-amber-500/5' : 'border-blue-500/25 bg-blue-500/5'}`}>
            <div><p className="text-xs font-medium text-[var(--text-muted)]">现在最值得做的事</p><p className="mt-1 text-sm font-medium text-[var(--text-primary)]">{primaryAction.label}</p></div>
            <button type="button" onClick={primaryAction.action} className={`inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium text-white ${primaryAction.tone === 'amber' ? 'bg-amber-600 hover:bg-amber-500' : 'bg-blue-600 hover:bg-blue-500'}`}><primaryAction.icon className="h-4 w-4" />{primaryAction.label}<ArrowRight className="h-4 w-4" /></button>
        </section>}
        <StructuredResultCard current={current} loading={data.current.loading} error={data.current.error} onOpenMessage={openMessageInDevelopment} />
        <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1.05fr)_minmax(360px,0.95fr)]">
            <DeliverablesSummary files={delivery.files} loading={data.current.loading} error={data.current.error}
                onOpenFile={openFile} onPreviewFile={setPreviewPath} onRevealFile={path => void revealFile(path)} />
            <AcceptanceCriteriaView business={current?.verification.businessCriteria ?? []}
                technical={current?.verification.technicalChecks ?? []}
                overall={current?.verification.overallStatus ?? 'NOT_VERIFIED'} />
        </div>
        {failedWithPrevious && current?.previousAvailableDelivery && (
            <section className="rounded-2xl border border-amber-500/30 bg-amber-500/5 p-5"><p className="text-xs font-medium uppercase tracking-wide text-amber-500">上次可用交付</p><div className="mt-2 flex flex-wrap items-center justify-between gap-3"><p className="text-sm text-[var(--text-secondary)]">当前执行失败，上一轮仍有 {current.previousAvailableDelivery.delivery.totalFiles} 个可用文件。</p>{current.previousAvailableDelivery.delivery.primaryArtifactPath && <button onClick={() => openResult(current.previousAvailableDelivery!.delivery.primaryArtifactPath!)} className="rounded-lg bg-amber-600 px-3 py-2 text-sm font-medium text-white">查看上次主要成果</button>}</div></section>
        )}
        <div id="simple-pending-actions"><PendingActionsSummary actions={current?.pendingActions ?? []} onSelect={id => void focusPendingAction(id)} /></div>
        {openError && <p className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-3 text-sm text-amber-400">{openError}</p>}
        <details className="rounded-2xl border border-[var(--border)] bg-[var(--bg-secondary)]"><summary className="cursor-pointer px-5 py-4 text-sm font-medium text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]">展开技术记录</summary><div className="border-t border-[var(--border)] p-4"><ResultActivityList activities={activities} onOpenActivity={openActivity} /></div></details>
    </div>{sessionId && previewPath && <FilePreviewDialog sessionId={sessionId} path={previewPath} onClose={() => setPreviewPath(null)} onFallback={() => { const path = previewPath; setPreviewPath(null); openFile(path); }} />}</div>;
}
