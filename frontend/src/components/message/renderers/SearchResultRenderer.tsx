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
        return text.replace(regex, '<mark class="bg-warnsoft text-warnstrong">$1</mark>');
    };

    return (
        <div className="text-sm">
            <div className="text-[13px] text-t3 mb-2">
                {grouped.size} 个文件中找到 {totalMatches} 个匹配
            </div>
            {Array.from(grouped).map(([file, matches]) => (
                <div key={file} className="mb-3">
                    <span className="text-accent2-ink text-sm font-mono">
                        {file}
                    </span>
                    <span className="text-t4 text-[13px] ml-2 tabular-nums">({matches.length} 匹配)</span>
                    <div className="mt-1 bg-sunken2 border border-hairline rounded-[14px] overflow-hidden">
                        {matches.map((m, i) => (
                            <div key={i} className="flex hover:bg-hover2">
                                <span className="w-12 text-right text-t4 px-2 flex-shrink-0 tabular-nums"
                                    >{m.line}</span>
                                <span className="flex-1 font-mono panel-code text-t1 whitespace-pre"
                                    dangerouslySetInnerHTML={{ __html: highlightMatch(m.content) }} />
                            </div>
                        ))}
                    </div>
                </div>
            ))}
        </div>
    );
};
