import React, { useState, lazy, Suspense } from 'react';
import { FileText, ChevronDown, ChevronRight, GitBranch, Columns, AlignLeft } from 'lucide-react';

// Monaco DiffEditor 懒加载，避免首屏加载大包
const DiffEditor = lazy(() =>
    import('@monaco-editor/react').then(mod => ({ default: mod.DiffEditor }))
);

interface GitDiffData {
    staged: boolean;
    stat: string;
    diff: string;
    fileCount: number;
}

export const GitDiffPanel: React.FC<{ data: GitDiffData }> = ({ data }) => {
    const [expandedFiles, setExpandedFiles] = useState<Set<string>>(new Set());
    const [useMonaco, setUseMonaco] = useState(false);
    const isMobile = typeof window !== 'undefined' && window.innerWidth < 768;

    // 解析 diff 按文件分组
    const fileDiffs = parseDiffByFile(data.diff);

    const toggleFile = (path: string) => {
        setExpandedFiles(prev => {
            const next = new Set(prev);
            next.has(path) ? next.delete(path) : next.add(path);
            return next;
        });
    };

    return (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between px-4 py-2 border-b border-[var(--border)]">
                <div className="flex items-center gap-2">
                    <GitBranch size={16} className="text-blue-400" />
                    <span className="font-semibold text-sm text-[var(--text-primary)]">
                        Git Diff {data.staged ? '(Staged)' : '(Working Tree)'}
                    </span>
                </div>
                <div className="flex items-center gap-2">
                    {!isMobile && (
                        <button
                            onClick={() => setUseMonaco(!useMonaco)}
                            className="flex items-center gap-1 px-2 py-1 rounded text-xs bg-[var(--bg-tertiary)] hover:bg-[var(--bg-primary)] text-[var(--text-secondary)]"
                            title={useMonaco ? '切换为行级着色' : '切换为 Side-by-Side Diff'}
                        >
                            {useMonaco ? <AlignLeft size={12} /> : <Columns size={12} />}
                            {useMonaco ? 'Inline' : 'Side-by-Side'}
                        </button>
                    )}
                    <span className="text-xs text-[var(--text-muted)]">
                        {data.fileCount} 个文件变更
                    </span>
                </div>
            </div>

            {/* Stat overview */}
            {data.stat && (
                <pre className="px-4 py-2 text-xs font-mono text-[var(--text-secondary)] border-b border-[var(--border)] bg-[var(--bg-tertiary)]">
                    {data.stat}
                </pre>
            )}

            {/* File-by-file diff */}
            <div className="divide-y divide-[var(--border)]">
                {fileDiffs.map(({ path, additions, deletions, lines }) => (
                    <div key={path}>
                        <button
                            onClick={() => toggleFile(path)}
                            className="w-full flex items-center gap-2 px-4 py-2 text-xs hover:bg-[var(--bg-tertiary)] transition-colors"
                        >
                            {expandedFiles.has(path) ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                            <FileText size={12} className="text-[var(--text-muted)]" />
                            <span className="flex-1 text-left font-mono text-[var(--text-primary)]">{path}</span>
                            <span className="text-green-400">+{additions}</span>
                            <span className="text-red-400">-{deletions}</span>
                        </button>
                        {expandedFiles.has(path) && (
                            <div className="bg-[var(--bg-primary)] overflow-x-auto">
                                {useMonaco && !isMobile ? (
                                    <Suspense fallback={<div className="p-4 text-xs text-[var(--text-muted)]">Loading diff editor...</div>}>
                                        <DiffEditor
                                            height="300px"
                                            theme="vs-dark"
                                            original={extractOriginal(lines)}
                                            modified={extractModified(lines)}
                                            options={{
                                                readOnly: true,
                                                minimap: { enabled: false },
                                                renderSideBySide: true,
                                                fontSize: 12,
                                            }}
                                        />
                                    </Suspense>
                                ) : (
                                    lines.map((line, i) => (
                                        <div key={i}
                                             className={`px-4 py-0.5 text-xs font-mono whitespace-pre ${
                                                 line.startsWith('+') && !line.startsWith('+++')
                                                     ? 'bg-green-900/20 text-green-300'
                                                     : line.startsWith('-') && !line.startsWith('---')
                                                         ? 'bg-red-900/20 text-red-300'
                                                         : line.startsWith('@@')
                                                             ? 'bg-blue-900/10 text-blue-300'
                                                             : 'text-[var(--text-secondary)]'
                                             }`}
                                        >
                                            {line}
                                        </div>
                                    ))
                                )}
                            </div>
                        )}
                    </div>
                ))}
            </div>
        </div>
    );
};

/** 解析 unified diff 按文件分组 */
function parseDiffByFile(diff: string): Array<{
    path: string; additions: number; deletions: number; lines: string[];
}> {
    if (!diff) return [];
    const files: Array<{ path: string; additions: number; deletions: number; lines: string[] }> = [];
    let current: typeof files[0] | null = null;

    for (const line of diff.split('\n')) {
        if (line.startsWith('diff --git')) {
            if (current) files.push(current);
            const match = line.match(/b\/(.+)$/);
            current = { path: match?.[1] ?? 'unknown', additions: 0, deletions: 0, lines: [] };
        } else if (current) {
            current.lines.push(line);
            if (line.startsWith('+') && !line.startsWith('+++')) current.additions++;
            if (line.startsWith('-') && !line.startsWith('---')) current.deletions++;
        }
    }
    if (current) files.push(current);
    return files;
}

/** 从 diff 行中提取原始文件内容（供 Monaco DiffEditor 使用） */
function extractOriginal(lines: string[]): string {
    return lines
        .filter(l => !l.startsWith('+') || l.startsWith('+++'))
        .filter(l => !l.startsWith('@@') && !l.startsWith('---') && !l.startsWith('+++'))
        .map(l => l.startsWith('-') ? l.slice(1) : l)
        .join('\n');
}

/** 从 diff 行中提取修改后文件内容（供 Monaco DiffEditor 使用） */
function extractModified(lines: string[]): string {
    return lines
        .filter(l => !l.startsWith('-') || l.startsWith('---'))
        .filter(l => !l.startsWith('@@') && !l.startsWith('---') && !l.startsWith('+++'))
        .map(l => l.startsWith('+') ? l.slice(1) : l)
        .join('\n');
}
