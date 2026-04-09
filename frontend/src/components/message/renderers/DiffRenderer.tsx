/**
 * DiffRenderer — FileEditTool 专用渲染器
 * 功能: 解析 unified diff 格式 + 增删行分色 + 行号显示
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
        <div className="rounded-md border border-gray-700 overflow-hidden">
            {filePath && (
                <div className="bg-gray-800 px-3 py-1.5 text-sm flex justify-between">
                    <span className="text-gray-300 font-mono">{filePath}</span>
                    <span className="text-xs">
                        <span className="text-green-400">+{addCount}</span>{' '}
                        <span className="text-red-400">-{removeCount}</span>
                    </span>
                </div>
            )}
            <div className="font-mono text-sm overflow-x-auto">
                {diffLines.map((line, i) => (
                    <div key={i} className={`flex
                        ${line.type === 'add' ? 'bg-green-900/30' : ''}
                        ${line.type === 'remove' ? 'bg-red-900/30' : ''}
                        ${line.type === 'header' ? 'bg-blue-900/20 text-blue-400' : ''}`}>
                        <span className="w-10 text-right text-gray-600 select-none px-1 flex-shrink-0">
                            {line.oldLine || ''}
                        </span>
                        <span className="w-10 text-right text-gray-600 select-none px-1 flex-shrink-0">
                            {line.newLine || ''}
                        </span>
                        <span className={`w-4 text-center flex-shrink-0
                            ${line.type === 'add' ? 'text-green-400' : ''}
                            ${line.type === 'remove' ? 'text-red-400' : ''}`}>
                            {line.type === 'add' ? '+' : line.type === 'remove' ? '-' : ' '}
                        </span>
                        <span className="flex-1 whitespace-pre">{line.content}</span>
                    </div>
                ))}
            </div>
        </div>
    );
};
