import { useState } from 'react';
import { Copy, Info } from 'lucide-react';
import { Dialog } from '@/components/ui/Dialog';

export function SessionTitle({ title, sessionId, connection }: { title: string; sessionId: string | null; connection: string }) {
    const [open, setOpen] = useState(false);
    const [copyState, setCopyState] = useState('');
    return <>
        <button type="button" aria-label="查看会话详情" aria-haspopup="dialog" aria-expanded={open} title={title} onClick={() => { setCopyState(''); setOpen(true); }} className="hidden md:flex min-w-0 max-w-[180px] xl:max-w-[240px] items-center gap-1.5 rounded-[10px] px-2 py-1 text-left hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink">
            <span className="min-w-0"><span className="block truncate text-sm font-medium text-t1">{title}</span><span className="block text-[13px] text-t3">{connection}</span></span><Info size={14} className="shrink-0 text-t3" aria-hidden="true" />
        </button>
        <Dialog open={open} onClose={() => setOpen(false)} title="会话详情" className="w-full max-w-md">
            <div className="space-y-4 p-4">
                <p className="break-words text-sm text-t1">{title}</p>
                <div><div className="mb-2 text-[13px] text-t2">会话 ID</div><code className="block select-text break-all rounded-[10px] bg-sunken2 p-3 text-[13px] text-t1">{sessionId ?? '尚未创建会话'}</code></div>
                {sessionId && <button type="button" className="inline-flex min-h-11 items-center gap-2 rounded-[10px] border border-hairline px-3 text-sm text-t1 hover:bg-hover2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink" onClick={async () => {
                    try { await navigator.clipboard.writeText(sessionId); setCopyState('已复制'); }
                    catch { setCopyState('复制失败，请选中上方 ID 手动复制'); }
                }}><Copy size={18} />复制会话 ID</button>}
                <p role="status" className="text-[13px] text-t2">{copyState}</p>
            </div>
        </Dialog>
    </>;
}
