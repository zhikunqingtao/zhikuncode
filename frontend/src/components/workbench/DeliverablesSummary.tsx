import { Eye, FileCheck2, FileClock, FileCode2, FileImage, FileText, FileX2, FolderOpen, PackageOpen } from 'lucide-react';
import type { DeliveryFileView } from '@/hooks/useSimpleWorkbenchData';

function fileStatus(state: string, verified: boolean) {
    if (verified) return { label: '已核对', icon: FileCheck2, color: 'text-green-500' };
    if (state === 'failed') return { label: '有问题', icon: FileX2, color: 'text-red-500' };
    return { label: '待核对', icon: FileClock, color: 'text-amber-500' };
}

const operationLabels = {
    created: '新建',
    modified: '修改',
    deleted: '删除',
} as const;

type DeliverableCategory = 'result' | 'code' | 'media' | 'other';

const categoryMeta = {
    result: { label: '主要成果', icon: FileText },
    code: { label: '代码与配置', icon: FileCode2 },
    media: { label: '图片和媒体', icon: FileImage },
    other: { label: '其他文件', icon: PackageOpen },
} as const;

function categoryFor(path: string): DeliverableCategory {
    const extension = path.split('.').at(-1)?.toLowerCase() ?? '';
    if (['html', 'md', 'pdf', 'docx', 'xlsx', 'xls', 'csv', 'pptx', 'ppt', 'txt'].includes(extension)) return 'result';
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'mp4', 'mov', 'webm'].includes(extension)) return 'media';
    if (['js', 'jsx', 'ts', 'tsx', 'java', 'py', 'go', 'rs', 'c', 'cpp', 'h', 'css', 'scss', 'json', 'yaml', 'yml', 'toml', 'xml', 'sh', 'command'].includes(extension)) return 'code';
    return 'other';
}

function canPreviewInsideWorkbench(path: string): boolean {
    const extension = path.split('.').at(-1)?.toLowerCase() ?? '';
    return ['pdf', 'png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'txt', 'md', 'markdown', 'json', 'yaml', 'yml', 'xml', 'csv', 'log'].includes(extension);
}

export function DeliverablesSummary({
    files,
    loading,
    error,
    onOpenFile,
    onPreviewFile,
    onRevealFile,
}: {
    files: DeliveryFileView[];
    loading: boolean;
    error: string | null;
    onOpenFile?: (path: string) => void;
    onPreviewFile?: (path: string) => void;
    onRevealFile?: (path: string) => void;
}) {
    const ordered = files.map(entry => ({
        entry,
        path: entry.relativePath || entry.filePath,
        category: categoryFor(entry.relativePath || entry.filePath),
    }));
    const featured = ordered.slice(0, 6);
    const primary = ordered.find(item => item.entry.primary) ?? null;
    const secondary = featured.filter(item => !item.entry.primary).slice(0, 5);
    const grouped = (Object.keys(categoryMeta) as DeliverableCategory[])
        .map(category => ({ category, items: ordered.filter(item => item.category === category) }))
        .filter(group => group.items.length > 0);

    return (
        <section className="rounded-2xl border border-[var(--border)] bg-[var(--bg-secondary)] p-5">
            <div className="flex items-center justify-between">
                <div>
                    <p className="text-xs font-medium uppercase tracking-wide text-emerald-500">当前交付</p>
                    <h2 className="mt-1 font-semibold text-[var(--text-primary)]">可以查看和继续处理的文件</h2>
                </div>
                {files.length > 0 && <span className="text-xs text-[var(--text-muted)]">{files.length} 个文件</span>}
            </div>
            {loading && <p className="mt-3 text-sm text-[var(--text-muted)]">正在读取交付物记录…</p>}
            {error && <p className="mt-3 text-sm text-red-500">交付物记录暂时无法读取：{error}</p>}
            {!loading && !error && files.length === 0 && (
                <div className="mt-3 rounded-xl border border-dashed border-[var(--border)] p-4">
                    <p className="text-sm text-[var(--text-muted)]">暂未记录结构化交付物。</p>
                    <p className="mt-1 text-xs text-[var(--text-muted)]">可以切换到开发工作台查看最后回复和文件变化。</p>
                </div>
            )}
            {files.length > 0 && (
                <>
                {primary && (() => {
                    const status = fileStatus(primary.entry.state, primary.entry.verified);
                    const Icon = status.icon;
                    const previewInline = canPreviewInsideWorkbench(primary.path);
                    return (
                        <div className="mt-4 rounded-xl border border-emerald-500/25 bg-emerald-500/5 p-4">
                            <div className="flex items-start gap-3">
                                <div className="rounded-xl bg-emerald-500/10 p-2.5"><Icon className={`h-5 w-5 ${status.color}`} /></div>
                                <div className="min-w-0 flex-1">
                                    <p className="truncate font-medium text-[var(--text-primary)]" title={primary.path}>{primary.path}</p>
                                    <p className="mt-1 text-xs text-[var(--text-muted)]">{operationLabels[primary.entry.operation]} · {status.label}{primary.entry.fileSize != null ? ` · ${formatSize(primary.entry.fileSize)}` : ''}</p>
                                    {primary.entry.mismatchDetail && <p className="mt-1 text-xs text-red-500">{primary.entry.mismatchDetail}</p>}
                                </div>
                            </div>
                            <div className="mt-3 flex flex-wrap gap-2">
                                {previewInline && onPreviewFile && <button type="button" onClick={() => onPreviewFile(primary.entry.filePath)} className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-2 text-xs font-medium text-white hover:bg-emerald-500"><Eye className="h-3.5 w-3.5" />查看主要成果</button>}
                                {onRevealFile && <button type="button" onClick={() => onRevealFile(primary.entry.filePath)} className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-2 text-xs font-medium ${previewInline ? 'border border-[var(--border)] text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]' : 'bg-emerald-600 text-white hover:bg-emerald-500'}`}><FolderOpen className="h-3.5 w-3.5" />在文件夹中显示</button>}
                                {onOpenFile && <button type="button" onClick={() => onOpenFile(primary.entry.filePath)} className="rounded-lg border border-[var(--border)] px-3 py-2 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]">在文件区打开</button>}
                            </div>
                        </div>
                    );
                })()}
                <div className="mt-3 grid grid-cols-2 gap-2">
                    {grouped.map(({ category, items }) => {
                        const meta = categoryMeta[category];
                        const Icon = meta.icon;
                        return (
                            <div key={category} className="rounded-xl bg-[var(--bg-primary)] p-3">
                                <div className="flex items-center gap-2 text-xs text-[var(--text-muted)]">
                                    <Icon className="h-4 w-4" />{meta.label}
                                </div>
                                <p className="mt-1 text-lg font-semibold text-[var(--text-primary)]">{items.length}</p>
                            </div>
                        );
                    })}
                </div>
                {secondary.length > 0 && <p className="mt-4 text-xs font-medium text-[var(--text-muted)]">其他重点文件</p>}
                <ul className="mt-2 space-y-2">
                    {secondary.map(({ entry, path }) => {
                        const status = fileStatus(entry.state, entry.verified);
                        const Icon = status.icon;
                        return (
                            <li key={entry.id} className="flex items-center gap-3 rounded-xl bg-[var(--bg-primary)] p-3">
                                <Icon className={`h-4 w-4 shrink-0 ${status.color}`} />
                                <div className="min-w-0 flex-1">
                                    <p className="truncate text-sm text-[var(--text-primary)]" title={path}>{path}</p>
                                    <p className="text-xs text-[var(--text-muted)]">{operationLabels[entry.operation]} · {status.label}{entry.fileSize != null ? ` · ${formatSize(entry.fileSize)}` : ''}</p>
                                </div>
                            </li>
                        );
                    })}
                </ul>
                {files.length > featured.length && (
                    <details className="mt-3 rounded-xl border border-[var(--border)]">
                        <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]">
                            查看全部 {files.length} 个交付文件
                        </summary>
                        <div className="max-h-80 space-y-3 overflow-y-auto border-t border-[var(--border)] p-3">
                            {grouped.map(({ category, items }) => (
                                <div key={category}>
                                    <p className="mb-1.5 text-xs font-medium text-[var(--text-muted)]">{categoryMeta[category].label} · {items.length}</p>
                                    <ul className="space-y-1">
                                        {items.map(({ entry, path }) => (
                                            <li key={entry.id} className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-xs hover:bg-[var(--bg-hover)]">
                                                <span className="min-w-0 flex-1 truncate text-[var(--text-secondary)]" title={path}>{path}</span>
                                                <span className="shrink-0 text-[var(--text-muted)]">{operationLabels[entry.operation]}</span>
                                                {onOpenFile && <button type="button" onClick={() => onOpenFile(entry.filePath)} className="text-blue-400 hover:text-blue-300">打开</button>}
                                            </li>
                                        ))}
                                    </ul>
                                </div>
                            ))}
                        </div>
                    </details>
                )}
                </>
            )}
        </section>
    );
}

function formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
