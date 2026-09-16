import { useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ChevronDown, ChevronRight, Copy, FileCode2, X } from 'lucide-react';
import { useModalBehavior } from '@/hooks/useModalBehavior';
import type { TurnFileChange, TurnFileOperation } from '@/store/selectors/turnFileChanges';

const button = 'min-h-11 rounded-[10px] focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent2-ink hover:bg-hover2';
const fileName = (path: string) => path.split('/').at(-1) || path;

function OperationContent({ operation }: { operation: TurnFileOperation }) {
    if (operation.content === undefined) return <p className="p-4 text-[13px] text-t2">已记录此操作，未提供可展示的差异</p>;
    return <div className="overflow-x-auto p-3" tabIndex={0} aria-label={operation.label}>
        <pre className="m-0 min-w-max font-mono text-[14px] leading-[1.65] lg:text-[13px]">{operation.content.split('\n').map((line, index) => {
            const diff = operation.label !== '写入内容';
            const added = diff && line.startsWith('+') && !line.startsWith('+++');
            const removed = diff && line.startsWith('-') && !line.startsWith('---');
            return <span key={index} className={`block min-h-[1.65em] ${added ? 'bg-ok/10 text-ok' : removed ? 'bg-err/10 text-err' : 'text-t1'}`}>{line || ' '}</span>;
        })}</pre>
    </div>;
}

function FileDetails({ file, onClose }: { file: TurnFileChange; onClose: () => void }) {
    const ref = useRef<HTMLDivElement>(null);
    const titleId = useId();
    const [expanded, setExpanded] = useState(() => new Set([file.operations.at(-1)!.id]));
    const [copyStatus, setCopyStatus] = useState('');
    useModalBehavior(true, ref, onClose);
    return createPortal(<div className="fixed inset-0 z-[80] bg-black/30" onClick={onClose}>
        <div ref={ref} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}
            onClick={event => event.stopPropagation()}
            className="absolute inset-y-0 right-0 flex w-full flex-col border-l border-hairline bg-surfacev2 text-t1 shadow-e2 outline-none lg:w-[50vw] lg:min-w-[480px] lg:max-w-[800px]">
            <header className="shrink-0 border-b border-hairline p-4">
                <div className="flex items-center gap-2"><h2 id={titleId} className="min-w-0 flex-1 break-all text-xl font-semibold">{fileName(file.path)}</h2>
                    <button className={`${button} w-11 shrink-0`} aria-label="复制文件路径" onClick={async () => {
                        try { await navigator.clipboard.writeText(file.path); setCopyStatus('路径已复制'); }
                        catch { setCopyStatus('复制失败，请选择下方路径手动复制'); }
                    }}><Copy size={18} className="mx-auto" /></button>
                    <button className={`${button} w-11 shrink-0`} aria-label="关闭文件差异" onClick={onClose}><X size={20} className="mx-auto" /></button></div>
                <p className="select-text break-all text-[13px] text-t2">{file.path}</p>
                <p className="text-[13px] text-t2">本轮 {file.operations.length} 次修改记录 · 按执行顺序展示</p>
                <span role="status" className="text-[13px] text-t2">{copyStatus}</span>
            </header>
            <div className="min-h-0 flex-1 overflow-y-auto p-4 pb-[max(16px,env(safe-area-inset-bottom))]">
                {file.operations.map((operation, index) => <section key={operation.id} className="mb-3 overflow-hidden rounded-[10px] border border-hairline">
                    <button className={`${button} flex w-full items-center gap-2 px-3 text-left text-sm`} aria-expanded={expanded.has(operation.id)} onClick={() => setExpanded(current => {
                        const next = new Set(current); if (next.has(operation.id)) next.delete(operation.id); else next.add(operation.id); return next;
                    })}><ChevronRight size={18} className={expanded.has(operation.id) ? 'rotate-90' : ''} />第 {index + 1} 次 · {operation.kind}<span className="ml-auto text-[13px] text-t2">{operation.label}</span></button>
                    {expanded.has(operation.id) && <OperationContent operation={operation} />}
                </section>)}
            </div>
        </div>
    </div>, document.body);
}

export function TurnFileChanges({ files, running }: { files: TurnFileChange[]; running: boolean }) {
    const [open, setOpen] = useState(false);
    const [showAll, setShowAll] = useState(false);
    const [selected, setSelected] = useState<string | null>(null);
    if (!files.length) return null;
    const selectedFile = files.find(file => file.path === selected);
    return <div className="my-3 min-w-0 rounded-[10px] border border-hairline bg-surface2" data-testid="turn-file-changes">
        <button className={`${button} flex w-full items-center gap-2 px-3 text-left text-sm font-medium text-t1`} aria-expanded={open} onClick={() => setOpen(!open)}>
            <FileCode2 size={18} className="shrink-0 text-t2" /><span>{running ? '本轮已记录修改' : '本轮修改'} · {files.length} 个文件</span><ChevronDown size={18} className={`ml-auto shrink-0 text-t2 ${open ? 'rotate-180' : ''}`} />
        </button>
        {open && <div className="border-t border-hairline p-2">
            {(showAll ? files : files.slice(0, 5)).map(file => <button key={file.path} className={`${button} flex w-full items-center gap-2 px-2 py-2 text-left`} onClick={() => setSelected(file.path)}>
                <span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium text-t1">{fileName(file.path)}</span><span className="block truncate text-[13px] text-t2">{file.path.includes('/') ? file.path.slice(0, file.path.lastIndexOf('/')) : '相对路径'}</span></span>
                <span className="shrink-0 text-[13px] text-t2">修改 {file.operations.length} 次</span><ChevronRight size={18} className="shrink-0 text-t2" />
            </button>)}
            {!showAll && files.length > 5 && <button className={`${button} w-full px-2 text-left text-sm text-accent2-ink`} onClick={() => setShowAll(true)}>查看全部 {files.length} 个文件</button>}
            <p className="px-2 pb-2 pt-2 text-[13px] text-t2">仅包含已记录的成功文件操作，可能不含命令或子任务产生的改动。</p>
        </div>}
        {selectedFile && <FileDetails key={selectedFile.path} file={selectedFile} onClose={() => setSelected(null)} />}
    </div>;
}
