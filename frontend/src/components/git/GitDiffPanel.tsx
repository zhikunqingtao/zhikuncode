import React, { useState, lazy, Suspense } from 'react';
import { FileText, ChevronDown, ChevronRight, GitBranch, Columns, AlignLeft } from 'lucide-react';
import { useResponsive } from '@/hooks/useResponsive';
import { ensureZkMonacoThemes, zkMonacoTheme } from '@/styles/zkMonaco';

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
    // §8.1 断点统一：useResponsive 带 resize 监听，顺带修复缩放不更新缺陷
    const { isMobile } = useResponsive();

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
        <div className="rounded-[14px] border border-[var(--v2-border-hairline)] bg-[var(--v2-bg-sunken)] overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between px-4 py-2 border-b border-[var(--v2-border-hairline)]">
                <div className="flex items-center gap-2">
                    <GitBranch size={16} className="text-accent2-ink dark:text-accent2-ink" />
                    <span className="font-semibold text-base text-[var(--v2-text-1)]">
                        Git Diff {data.staged ? '(Staged)' : '(Working Tree)'}
                    </span>
                </div>
                <div className="flex items-center gap-2">
                    {!isMobile && (
                        <button
                            onClick={() => setUseMonaco(!useMonaco)}
                            className="panel-control flex items-center gap-1 px-2 py-1 rounded text-[13px] bg-sunken2 hover:bg-[var(--v2-bg-surface)] text-[var(--v2-text-2)]"
                            title={useMonaco ? '切换为行级着色' : '切换为 Side-by-Side Diff'}
                        >
                            {useMonaco ? <AlignLeft size={12} /> : <Columns size={12} />}
                            {useMonaco ? 'Inline' : 'Side-by-Side'}
                        </button>
                    )}
                    <span className="text-[13px] text-[var(--v2-text-2)]">
                        {data.fileCount} 个文件变更
                    </span>
                </div>
            </div>

            {/* Stat overview */}
            {data.stat && (
                <pre className="panel-code px-4 py-2 text-[13px] font-mono text-[var(--v2-text-2)] border-b border-[var(--v2-border-hairline)] bg-sunken2">
                    {data.stat}
                </pre>
            )}

            {/* File-by-file diff */}
            <div className="divide-y divide-[var(--v2-border-hairline)]">
                {fileDiffs.map(({ path, additions, deletions, lines }) => (
                    <div key={path}>
                        <button
                            onClick={() => toggleFile(path)}
                            className="panel-control w-full flex items-center gap-2 px-4 py-2 text-[13px] hover:bg-sunken2 transition-colors"
                        >
                            {expandedFiles.has(path) ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                            <FileText size={12} className="text-[var(--v2-text-2)]" />
                            <span className="min-w-0 flex-1 truncate text-left font-mono text-[var(--v2-text-1)]">{path}</span>
                            <span className="text-ok">+{additions}</span>
                            <span className="text-err">-{deletions}</span>
                        </button>
                        {expandedFiles.has(path) && (
                            <div className="bg-[var(--v2-bg-surface)] overflow-x-auto">
                                {useMonaco && !isMobile ? (
                                    <Suspense fallback={<div className="p-4 text-[13px] text-[var(--v2-text-2)]">Loading diff editor...</div>}>
                                        <DiffEditor
                                            height="300px"
                                            beforeMount={ensureZkMonacoThemes}
                                            theme={zkMonacoTheme()}
                                            original={extractOriginal(lines)}
                                            modified={extractModified(lines)}
                                            options={{
                                                readOnly: true,
                                                minimap: { enabled: false },
                                                renderSideBySide: true,
                                                fontSize: 13,
                                                lineHeight: 21.45,
                                            }}
                                        />
                                    </Suspense>
                                ) : (
                                    lines.map((line, i) => (
                                        <div key={i}
                                             className={`panel-code px-4 py-0.5 text-[13px] font-mono whitespace-pre ${
                                                 line.startsWith('+') && !line.startsWith('+++')
                                                     ? 'bg-[var(--v2-diff-add-bg)] text-t1'
                                                     : line.startsWith('-') && !line.startsWith('---')
                                                         ? 'bg-[var(--v2-diff-remove-bg)] text-t1'
                                                         : line.startsWith('@@')
                                                             ? 'bg-accent2-soft text-accent2-ink'
                                                             : 'text-t2'
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
