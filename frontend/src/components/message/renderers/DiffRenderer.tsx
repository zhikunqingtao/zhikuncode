/**
 * DiffRenderer — FileEditTool 专用渲染器
 * 功能: 解析 unified diff 格式 + 增删行分色 + 行号显示
 *
 * §7.2 diff 行：del/add 整行 --v2-diff-remove-bg / --v2-diff-add-bg + 行首 −/＋；
 * 头部 +n/−n chip 走 ok/err soft 底。
 */

import React, { useMemo } from 'react';

interface DiffLine {
    type: 'add' | 'remove' | 'context' | 'header';
    content: string;
    oldLine?: number;
    newLine?: number;
}

function parseDiff(content: string): DiffLine[] {
    const result: DiffLine[] = [];
    let oldLine = 0, newLine = 0;
    let inHunk = false;
    for (const line of content.split('\n')) {
        const hunk = line.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
        if (hunk) {
            oldLine = Number(hunk[1]); newLine = Number(hunk[2]);
            inHunk = true;
            result.push({ type: 'header', content: line });
        } else if (inHunk && line.startsWith('+')) {
            result.push({ type: 'add', content: line.slice(1), newLine: newLine++ });
        } else if (inHunk && line.startsWith('-')) {
            result.push({ type: 'remove', content: line.slice(1), oldLine: oldLine++ });
        } else if (inHunk && line.startsWith(' ')) {
            result.push({ type: 'context', content: line.slice(1), oldLine: oldLine++, newLine: newLine++ });
        } else {
            // File headers and "no newline" markers are not changed/context lines.
            result.push({ type: 'header', content: line });
        }
    }
    return result;
}

export function diffStats(content: string): { added: number; removed: number } {
    const lines = parseDiff(content);
    return {
        added: lines.filter(line => line.type === 'add').length,
        removed: lines.filter(line => line.type === 'remove').length,
    };
}

export const DiffRenderer: React.FC<{ content: string; filePath?: string; truncated?: boolean }> = ({ content, filePath, truncated = false }) => {
    const diffLines = useMemo(() => parseDiff(content), [content]);
    const addCount = diffLines.filter(l => l.type === 'add').length;
    const removeCount = diffLines.filter(l => l.type === 'remove').length;

    return (
        <div className="rounded-[14px] border border-hairline overflow-hidden bg-sunken2">
            {filePath && (
                <div className="bg-surface2 px-3 py-1.5 text-sm flex justify-between border-b border-hairline">
                    <span className="min-w-0 flex-1 truncate text-t2 font-mono text-[13px]" title={filePath}>{filePath}</span>
                    <span className="flex shrink-0 items-center gap-1 text-[13px]">
                        <span className="rounded bg-oksoft px-1.5 py-0.5 font-medium tabular-nums text-ok">+{addCount}</span>
                        <span className="rounded bg-errsoft px-1.5 py-0.5 font-medium tabular-nums text-err">−{removeCount}</span>
                        {truncated && <span className="text-t3">（已展示部分）</span>}
                    </span>
                </div>
            )}
            {truncated && <p className="px-3 py-2 text-[13px] text-warn">差异过大，仅展示部分内容；请核对完整文件变更。</p>}
            <div className="font-mono panel-code max-h-96 overflow-auto">
                {diffLines.map((line, i) => (
                    <div key={i} data-galaxy-diff={filePath ? line.type : undefined} data-galaxy-path={filePath} data-galaxy-line={line.newLine ?? line.oldLine} className={`flex
                        ${line.type === 'add' ? 'bg-[var(--v2-diff-add-bg)]' : ''}
                        ${line.type === 'remove' ? 'bg-[var(--v2-diff-remove-bg)]' : ''}
                        ${line.type === 'header' ? 'bg-accent2-soft text-accent2-ink' : ''}`}>
                        <span data-diff-gutter="" className="w-10 text-right text-t4 select-none px-1 flex-shrink-0 tabular-nums">
                            {line.oldLine || ''}
                        </span>
                        <span data-diff-gutter="" className="w-10 text-right text-t4 select-none px-1 flex-shrink-0 tabular-nums">
                            {line.newLine || ''}
                        </span>
                        <span className={`w-4 text-center flex-shrink-0 select-none
                            ${line.type === 'add' ? 'text-ok' : ''}
                            ${line.type === 'remove' ? 'text-err' : ''}`}>
                            {line.type === 'add' ? '+' : line.type === 'remove' ? '−' : ' '}
                        </span>
                        <span className="flex-1 whitespace-pre text-t1">{line.content}</span>
                    </div>
                ))}
            </div>
        </div>
    );
};
