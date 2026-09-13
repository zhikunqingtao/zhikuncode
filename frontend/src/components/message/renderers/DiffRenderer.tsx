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
    const lines = content.split('\n');
    const result: DiffLine[] = [];
    let oldLine = 0, newLine = 0;
    for (const line of lines) {
        if (line.startsWith('@@')) {
            const match = line.match(/@@ -(\d+).*\+(\d+)/);
            if (match) { oldLine = parseInt(match[1]) - 1; newLine = parseInt(match[2]) - 1; }
            result.push({ type: 'header', content: line });
        } else if (line.startsWith('+')) {
            newLine++;
            result.push({ type: 'add', content: line.slice(1), newLine });
        } else if (line.startsWith('-')) {
            oldLine++;
            result.push({ type: 'remove', content: line.slice(1), oldLine });
        } else {
            oldLine++; newLine++;
            result.push({ type: 'context', content: line.startsWith(' ') ? line.slice(1) : line, oldLine, newLine });
        }
    }
    return result;
}

export const DiffRenderer: React.FC<{ content: string; filePath?: string }> = ({ content, filePath }) => {
    const diffLines = useMemo(() => parseDiff(content), [content]);
    const addCount = diffLines.filter(l => l.type === 'add').length;
    const removeCount = diffLines.filter(l => l.type === 'remove').length;

    return (
        <div className="rounded-xl border border-hairline overflow-hidden bg-sunken2">
            {filePath && (
                <div className="bg-surface2 px-3 py-1.5 text-sm flex justify-between border-b border-hairline">
                    <span className="text-t2 font-mono text-xs">{filePath}</span>
                    <span className="flex items-center gap-1 text-[11px]">
                        <span className="rounded bg-oksoft px-1.5 py-0.5 font-medium tabular-nums text-ok">+{addCount}</span>
                        <span className="rounded bg-errsoft px-1.5 py-0.5 font-medium tabular-nums text-err">−{removeCount}</span>
                    </span>
                </div>
            )}
            <div className="font-mono text-[12.5px] leading-[1.7] overflow-x-auto">
                {diffLines.map((line, i) => (
                    <div key={i} className={`flex
                        ${line.type === 'add' ? 'bg-[var(--v2-diff-add-bg)]' : ''}
                        ${line.type === 'remove' ? 'bg-[var(--v2-diff-remove-bg)]' : ''}
                        ${line.type === 'header' ? 'bg-accent2-soft text-accent2' : ''}`}>
                        <span className="w-10 text-right text-t4 select-none px-1 flex-shrink-0 tabular-nums">
                            {line.oldLine || ''}
                        </span>
                        <span className="w-10 text-right text-t4 select-none px-1 flex-shrink-0 tabular-nums">
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
