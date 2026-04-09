/**
 * SearchResultRenderer — GrepTool 专用渲染器
 * 功能: 文件路径分组 + 关键词高亮 + 行号显示
 */

import React, { useMemo } from 'react';

interface GrepMatch {
    file: string;
    line: number;
    content: string;
}

function parseGrepOutput(content: string): Map<string, GrepMatch[]> {
    const grouped = new Map<string, GrepMatch[]>();
    for (const line of content.split('\n')) {
        const match = line.match(/^(.+?):(\d+):(.*)$/);
        if (!match) continue;
        const [, file, lineNum, text] = match;
        if (!grouped.has(file)) grouped.set(file, []);
        grouped.get(file)!.push({ file, line: parseInt(lineNum), content: text });
    }
    return grouped;
}

export const SearchResultRenderer: React.FC<{ content: string; query?: string }> = ({ content, query }) => {
    const grouped = useMemo(() => parseGrepOutput(content), [content]);
    const totalMatches = Array.from(grouped.values()).reduce((sum, arr) => sum + arr.length, 0);

    const highlightMatch = (text: string) => {
        if (!query) return text;
        const regex = new RegExp(`(${query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi');
        return text.replace(regex, '<mark class="bg-yellow-500/40 text-yellow-200">$1</mark>');
    };

    return (
        <div className="text-sm">
            <div className="text-xs text-gray-400 mb-2">
                {grouped.size} 个文件中找到 {totalMatches} 个匹配
            </div>
            {Array.from(grouped).map(([file, matches]) => (
                <div key={file} className="mb-3">
                    <span className="text-blue-400 text-sm font-mono">
                        {file}
                    </span>
                    <span className="text-gray-500 text-xs ml-2">({matches.length} 匹配)</span>
                    <div className="mt-1 bg-gray-900 rounded overflow-hidden">
                        {matches.map((m, i) => (
                            <div key={i} className="flex hover:bg-gray-800/50">
                                <span className="w-12 text-right text-gray-600 px-2 flex-shrink-0"
                                    >{m.line}</span>
                                <span className="flex-1 font-mono whitespace-pre"
                                    dangerouslySetInnerHTML={{ __html: highlightMatch(m.content) }} />
                            </div>
                        ))}
                    </div>
                </div>
            ))}
        </div>
    );
};
