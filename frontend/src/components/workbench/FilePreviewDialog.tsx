import { useEffect, useState } from 'react';
import { ExternalLink, FileText, Loader2, X } from 'lucide-react';
import TextBlock from '@/components/message/TextBlock';

export function FilePreviewDialog({ sessionId, path, onClose, onFallback }: { sessionId: string; path: string; onClose: () => void; onFallback: () => void }) {
    const [url, setUrl] = useState<string | null>(null);
    const [text, setText] = useState<string | null>(null);
    const [type, setType] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    useEffect(() => {
        const controller = new AbortController(); let objectUrl: string | null = null;
        void fetch(`/api/sessions/${encodeURIComponent(sessionId)}/files/preview?path=${encodeURIComponent(path)}`, { headers: { 'X-Session-Id': sessionId }, signal: controller.signal })
            .then(async response => { if (!response.ok) throw new Error(`HTTP ${response.status}`); const contentType = response.headers.get('content-type') ?? ''; setType(contentType); if (contentType.startsWith('text/') || contentType.includes('json') || contentType.includes('xml')) setText(await response.text()); else { objectUrl = URL.createObjectURL(await response.blob()); setUrl(objectUrl); } })
            .catch(fetchError => { if (!controller.signal.aborted) setError(fetchError instanceof Error ? fetchError.message : String(fetchError)); });
        return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
    }, [path, sessionId]);
    return <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/70 p-4" role="dialog" aria-modal="true" aria-label={`预览 ${path}`}>
        <div className="flex max-h-[90vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--bg-secondary)] shadow-2xl"><div className="flex items-center justify-between border-b border-[var(--border)] px-4 py-3"><div className="min-w-0"><p className="truncate font-medium text-[var(--text-primary)]">{path.split(/[\\/]/).at(-1)}</p><p className="truncate text-xs text-[var(--text-muted)]">{path}</p></div><button onClick={onClose} className="rounded-lg p-2 text-[var(--text-muted)] hover:bg-[var(--bg-hover)]" aria-label="关闭预览"><X className="h-5 w-5" /></button></div>
            <div className="min-h-0 flex-1 overflow-auto bg-[var(--bg-primary)] p-4">{!error && !url && text == null && <div className="flex h-64 items-center justify-center"><Loader2 className="h-6 w-6 animate-spin text-blue-500" /></div>}{error && <div className="mx-auto max-w-lg rounded-xl border border-amber-500/30 bg-amber-500/5 p-5 text-sm text-[var(--text-secondary)]"><FileText className="mb-3 h-6 w-6 text-amber-500" /><p>此文件不能在工作台内预览（{error}）。</p><button onClick={onFallback} className="mt-4 inline-flex items-center gap-2 rounded-lg bg-blue-600 px-3 py-2 text-white"><ExternalLink className="h-4 w-4" />在文件区打开</button></div>}{url && type?.startsWith('image/') && <img src={url} alt={path} className="mx-auto max-h-[75vh] max-w-full object-contain" />}{url && type?.includes('pdf') && <iframe title={path} src={url} className="h-[75vh] w-full rounded-lg bg-white" />}{text != null && (type?.includes('markdown') || /\.md$/i.test(path)) && <div className="mx-auto max-w-4xl rounded-xl bg-[var(--bg-secondary)] p-6"><TextBlock text={text} /></div>}{text != null && !(type?.includes('markdown') || /\.md$/i.test(path)) && <pre className="whitespace-pre-wrap break-words font-mono text-sm leading-6 text-[var(--text-primary)]">{text}</pre>}</div>
        </div></div>;
}
