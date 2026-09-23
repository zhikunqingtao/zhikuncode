import { useEffect, useRef, useState } from 'react';
import { taskTitle } from '@/utils/workbenchPresentation';
import { Dialog } from '@/components/ui/Dialog';
import { subscribeMergeRecovery, useSessionMergeStore } from '@/store/sessionMergeStore';
import { useSessionStore } from '@/store/sessionStore';
import { useModelStore } from '@/store/modelStore';
import { activateSessionCandidate, getPendingSessionActivation } from '@/services/sessionActivation';
import { isSessionGenerating, type SessionSummary } from '@/utils/sessionGroups';

const field = 'w-full rounded-[10px] border border-hairline bg-surfacev2 p-2 text-t1';
const button = 'rounded-[10px] border border-hairline px-3 py-2 text-sm text-t1 hover:bg-hover2 disabled:opacity-40';
const sessionName = (session: SessionSummary) => session.title || taskTitle(null, [], session.workingDirectory, session.goalPreview);
const stages: Record<string, string> = { snapshotting: '复制并封存来源', extracting: '整理详细交接', aggregating: '生成有界概览', validating: '校验交接资料', publishing: '创建新会话', recovering: '恢复合并进度', snapshot: '整理过程与复制产物', summarizing: '生成交接摘要', committing: '创建新会话', completed: '合并完成', failed: '合并失败', interrupted: '合并已中断' };

/** Mounted once by App so polling survives sidebar collapse, tab changes, and mobile navigation. */
export function SessionMergePanel() {
    const { open, source, pending, error, storageWarning, recoveryNotice, submitting, closeDialog, submit, refresh, dismiss, resume, cancel } = useSessionMergeStore();
    const models = useModelStore(s => s.models);
    const sessionId = useSessionStore(s => s.sessionId);
    const status = useSessionStore(s => s.status);
    const [query, setQuery] = useState('');
    const [candidates, setCandidates] = useState<SessionSummary[]>([]);
    const [cursor, setCursor] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);
    const [listError, setListError] = useState('');
    const [others, setOthers] = useState<SessionSummary[]>([]);
    const [primary, setPrimary] = useState('');
    const [model, setModel] = useState('');
    const [title, setTitle] = useState('');
    const [activationError, setActivationError] = useState('');
    const autoOpen = useRef(false);
    const originSession = useRef<string | null>(null);
    const version = useRef(0);
    const busy = !!pending && (!pending.operation || pending.operation.status === 'preparing');
    const syncUncertain = busy && !!error;

    useEffect(() => {
        setActivationError('');
    }, [pending?.key]);
    useEffect(() => {
        const unsubscribe = subscribeMergeRecovery();
        const refreshProgress = () => { void refresh(); };
        const refreshWhenVisible = () => {
            if (document.visibilityState === 'visible') refreshProgress();
        };
        refreshProgress();
        const timer = window.setInterval(refreshProgress, 2000);
        // Resume promptly after suspension or a server notification; refresh coalesces in-flight requests.
        document.addEventListener('visibilitychange', refreshWhenVisible);
        window.addEventListener('focus', refreshProgress);
        window.addEventListener('online', refreshProgress);
        window.addEventListener('session-list-updated', refreshProgress);
        return () => {
            window.clearInterval(timer);
            unsubscribe();
            document.removeEventListener('visibilitychange', refreshWhenVisible);
            window.removeEventListener('focus', refreshProgress);
            window.removeEventListener('online', refreshProgress);
            window.removeEventListener('session-list-updated', refreshProgress);
        };
    }, [refresh]);
    useEffect(() => {
        if (open) void refresh();
    }, [open, refresh]);
    useEffect(() => {
        if (!open) { autoOpen.current = false; return; }
        if (source && !pending) {
            setOthers([]); setPrimary(source.id); setModel(source.model); setTitle(''); setQuery('');
            void useModelStore.getState().fetchModels();
        }
        // Only the user who starts this operation in this still-open panel can auto-activate.
        originSession.current = useSessionStore.getState().sessionId;
    }, [open, source]); // pending intentionally does not reset the form on submission
    useEffect(() => {
        if (sessionId !== originSession.current) autoOpen.current = false;
    }, [sessionId]);
    const openTarget = async () => {
        if (pending?.operation?.status !== 'completed' || pending.operation.targetAvailable === false) return;
        const result = await activateSessionCandidate(pending.operation.targetSessionId);
        if (useSessionMergeStore.getState().pending?.key !== pending.key) return;
        if (result.status === 'activated') closeDialog();
        else if (result.status === 'failed') setActivationError(result.error.message);
    };
    useEffect(() => {
        if (open && autoOpen.current && pending?.operation?.status === 'completed') {
            autoOpen.current = false;
            if (useSessionStore.getState().sessionId === originSession.current && !getPendingSessionActivation()) void openTarget();
        }
    }, [pending?.operation?.status, open]);

    const loadCandidates = async (next?: string | null) => {
        const current = ++version.current;
        setLoading(true); setListError('');
        try {
            const params = new URLSearchParams({ limit: '50', query });
            if (next) params.set('cursor', next);
            const response = await fetch(`/api/sessions?${params}`);
            if (!response.ok) throw new Error('无法读取会话列表');
            const body = await response.json();
            if (version.current !== current) return;
            setCandidates(previous => next ? [...new Map([...previous, ...body.sessions].map(s => [s.id, s])).values()] : body.sessions);
            setCursor(body.hasMore ? body.nextCursor : null);
        } catch { if (version.current === current) setListError('无法读取会话列表，请重试。'); }
        finally { if (version.current === current) setLoading(false); }
    };
    useEffect(() => {
        if (!open || pending) return;
        ++version.current; setCandidates([]); setCursor(null);
        const timer = window.setTimeout(() => { void loadCandidates(); }, 250);
        return () => { ++version.current; window.clearTimeout(timer); };
    }, [open, query, pending]);
    const selected = source ? [source, ...others] : [];
    const primarySource = selected.find(s => s.id === primary);
    const externalDirectories = [...new Set(selected.map(s => s.workingDirectory))]
        .filter(directory => directory !== primarySource?.workingDirectory);
    const removeSource = (id: string) => {
        setOthers(previous => previous.filter(s => s.id !== id));
        if (primary === id && source) { setPrimary(source.id); setModel(source.model); }
    };

    const recoveryMessages = <>
        {recoveryNotice && <div role="alert" className="rounded-[10px] border border-hairline bg-surfacev2 p-3 text-sm text-t1">
            <p>{recoveryNotice}</p>
            <button className={button} onClick={() => useSessionMergeStore.setState({ recoveryNotice: null })}>关闭合并恢复提示</button>
        </div>}
        {storageWarning && <div role="alert" className="rounded-[10px] border border-hairline bg-surfacev2 p-3 text-sm text-t1">
            <p>{storageWarning}</p>
            <button className={button} aria-label="关闭恢复提示" onClick={() => useSessionMergeStore.setState({ storageWarning: null })}>关闭提示</button>
        </div>}
    </>;
    return <>
        {!open && (storageWarning || recoveryNotice || error) && <div className="fixed bottom-36 right-4 z-40 max-w-sm space-y-2 shadow-e4">
            {recoveryMessages}
            {error && <div role="alert" className="rounded-[10px] border border-hairline bg-surfacev2 p-3 text-sm text-t1">
                <p>{busy ? '进度同步失败：' : ''}{error}</p>
                {busy
                    ? <button className={button} disabled={submitting} onClick={() => void refresh()}>重试查询</button>
                    : <button className={button} onClick={() => useSessionMergeStore.setState({ error: null })}>关闭合并错误提示</button>}
            </div>}
        </div>}
        {pending && !open && <button className={`${button} fixed bottom-24 right-4 z-40 bg-surfacev2 shadow-e4`}
            onClick={() => useSessionMergeStore.getState().openDialog()}>
            {busy ? syncUncertain ? '合并状态待确认 · 查看进度' : '合并中 · 查看进度' : '合并结果'}
        </button>}
        <Dialog open={open} onClose={closeDialog} title="合并为新会话" className="max-w-xl">
            <div className="p-4 md:p-6 space-y-4 max-h-[75vh] overflow-y-auto text-sm text-t2">
                {!pending && source && <>
                    <p>选择 2～5 个空闲会话。来源保留原样，仅复制快照期间暂不能执行或删除；封存后立即恢复使用。新会话使用独立交接资料，工程代码目录共享。</p>
                    <div role="group" aria-label="已选来源会话" className="space-y-2">
                        <p>已选 {selected.length}/5</p>
                        {selected.map(s => <div key={s.id} className="break-all">
                            <p>{sessionName(s)}{s.id === source.id ? '（发起会话，固定保留）' : ''}<br />{s.workingDirectory}</p>
                            {s.id !== source.id && <button className={button} aria-label={`移除 ${sessionName(s)}`} onClick={() => removeSource(s.id)}>移除</button>}
                        </div>)}
                    </div>
                    <label className="block">搜索来源会话<input className={field} value={query} onChange={e => setQuery(e.target.value)} /></label>
                    <div className="max-h-44 overflow-y-auto space-y-1" role="group" aria-label="选择来源会话">
                        {candidates.filter(s => s.id !== source.id).map(s => {
                            const chosen = others.some(other => other.id === s.id);
                            const disabled = !chosen && (selected.length >= 5 || !!s.mergeOperationId || isSessionGenerating(s, sessionId, status));
                            return <button key={s.id} disabled={disabled} aria-pressed={chosen}
                                className={`${button} w-full text-left ${chosen ? 'bg-accent2-soft' : ''}`}
                                onClick={() => {
                                    if (chosen) removeSource(s.id);
                                    else setOthers(previous => previous.length < 4 && !previous.some(other => other.id === s.id) ? [...previous, s] : previous);
                                }}>
                                {sessionName(s)} {disabled ? '（不可选择）' : ''}<span className="block text-xs break-all">{s.workingDirectory}</span>
                            </button>;
                        })}
                    </div>
                    {loading && <p role="status">读取会话中…</p>}
                    {listError && <p role="alert">{listError}<button className={button} onClick={() => void loadCandidates()}>重试</button></p>}
                    {cursor && <button className={button} disabled={loading} onClick={() => void loadCandidates(cursor)}>加载更多会话</button>}
                    <label className="block">主会话（使用其工程目录）<select className={field} value={primary} onChange={e => {
                        setPrimary(e.target.value); setModel(selected.find(s => s.id === e.target.value)!.model);
                    }}>{selected.map(s => <option key={s.id} value={s.id}>{sessionName(s)}</option>)}</select></label>
                    <div role="group" aria-label="新会话目录与权限" className="rounded-[10px] border border-hairline bg-surfacev2 p-3 space-y-2">
                        <p className="break-all"><strong>新会话的主工作目录：</strong>{primarySource?.workingDirectory}</p>
                        {externalDirectories.map(directory => <p key={directory} className="break-all"><strong>外部引用目录：</strong>{directory}</p>)}
                        {externalDirectories.length > 0 && <p>外部引用目录不会成为新会话的额外工作目录。</p>}
                        {selected.length > 1 && externalDirectories.length === 0 && <p>所选来源会话使用同一工作目录。</p>}
                        <p>相对路径以主工作目录为准。工程代码保留原路径，不自动合并。</p>
                        <p><strong>新会话将使用“完全访问权限”</strong>，与 Web 普通新建会话一致。通过安全检查的跨目录读写操作可能无需再次确认；系统安全限制和操作系统权限仍然生效。</p>
                    </div>
                    <label className="block">新会话标题<input className={field} maxLength={200} placeholder={`合并 · ${primarySource ? sessionName(primarySource) : '会话'}`} value={title} onChange={e => setTitle(e.target.value)} /></label>
                    <label className="block">目标模型<select className={field} value={model} onChange={e => setModel(e.target.value)}>
                        {!models.some(m => m.id === model) && <option value={model}>{model}</option>}
                        {models.map(m => <option key={m.id} value={m.id}>{m.displayName}</option>)}
                    </select></label>
                    <p>复制临时产物，缺失文件会列出。新会话等待你的下一条指令，不复制来源会话的历史授权。</p>
                    <button className={button} disabled={selected.length < 2 || submitting} onClick={() => {
                        autoOpen.current = true;
                        originSession.current = useSessionStore.getState().sessionId;
                        void submit({ sourceSessionIds: selected.map(s => s.id), primarySessionId: primary, title, model });
                    }}>开始合并</button>
                </>}
                {pending && <>
                    <p role="status">{syncUncertain ? '合并状态待确认' : pending.operation?.status === 'paused' ? '合并已暂停，进度已保留' : pending.operation?.status === 'cancelled' ? '合并已取消' : stages[pending.operation?.stage ?? ''] ?? '提交中，可关闭面板，稍后恢复进度。'}</p>
                    {busy && <p>{syncUncertain
                        ? '进度同步失败，正在重试查询；来源占用以服务端为准。'
                        : `${pending.operation?.lockedSourceSessionIds?.length ?? 0} 个来源会话正在复制；快照封存后可继续使用来源。`}</p>}
                    {busy && pending.operation?.result.copiedCount !== undefined && <p>
                        资料已整理：复制 {pending.operation.result.copiedCount} 个文件，资料缺口或未解析项 {pending.operation.result.warningCount ?? 0} 项。
                    </p>}
                    {pending.operation?.progress && <p>整理单元：{pending.operation.progress.completedUnits}/{pending.operation.progress.knownUnits}{pending.operation.progress.totalFinal ? '' : '（总数随分片增加）'}</p>}
                    {pending.operation?.retryAt && <p>正在等待重试：{pending.operation.retryAt}</p>}
                    {['paused', 'failed'].includes(pending.operation?.status ?? '') && <p role="alert">{pending.operation?.error}</p>}
                    {!!pending.operation?.result.warnings?.length && <details open><summary>资料缺口与未解析项</summary><ul className="space-y-2 break-all">
                        {pending.operation.result.warnings.map((w, i) => <li key={i}>{w.sourceId ? `来源 ${w.sourceId}：` : ''}{w.originalPath}：{w.reason}</li>)}
                    </ul>
                        {(pending.operation.result.warningCount ?? 0) > pending.operation.result.warnings.length
                            && <p>这里只展示前 {pending.operation.result.warnings.length} 项，完整清单保存在资料包 snapshot/gaps.jsonl；新会话可通过 HandoffRead 读取 ref=gaps。</p>}
                    </details>}
                    {pending.operation?.status === 'completed' && <>
                        <p>已复制 {pending.operation.result.copiedCount ?? 0} 个文件，资料缺口或未解析项 {pending.operation.result.warningCount ?? 0} 项。</p>
                        <p className="break-all">资料索引：{pending.operation.result.indexPath}</p>
                        <button className={button} disabled={pending.operation.targetAvailable === false} onClick={() => void openTarget()}>打开新会话</button>
                        {pending.operation.targetAvailable === false && <p>目标会话已删除。</p>}
                    </>}
                    {pending.operation?.canResume && <>
                        <label className="block">恢复时使用模型<select className={field} value={model} onChange={e => setModel(e.target.value)}>
                            <option value="">继续使用原模型</option>
                            {models.map(m => <option key={m.id} value={m.id}>{m.displayName}</option>)}
                        </select></label>
                        <button className={button} disabled={submitting} onClick={() => void resume(model || undefined)}>恢复合并</button>
                        <p>已提交的整理单元会复用；中断时未提交的最后一次调用可能重做并再次计费。</p>
                    </>}
                    {pending.operation?.canCancel && <button className={button} disabled={submitting} onClick={() => void cancel()}>取消合并</button>}
                    {!busy && !pending.operation?.canCancel && <button className={button} onClick={dismiss}>关闭结果</button>}
                </>}
                {recoveryMessages}
                {(error || activationError) && <p role="alert">{error || activationError}{busy && <button className={button} disabled={submitting} onClick={() => void refresh()}>重试查询</button>}</p>}
            </div>
        </Dialog>
    </>;
}
