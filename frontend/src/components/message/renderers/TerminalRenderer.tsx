/**
 * TerminalRenderer — BashTool 专用渲染器
 * 功能: stdout/stderr 分色 + 可折叠 + 复制按钮 + 退出码标签
 */

import React, { useState } from 'react';

interface TerminalRendererProps {
    content: string;
    exitCode?: number;
    isError?: boolean;
    maxLines?: number;
}

export const TerminalRenderer: React.FC<TerminalRendererProps> = ({
    content, exitCode, isError, maxLines = 50,
}) => {
    const [expanded, setExpanded] = useState(false);
    const lines = content.split('\n');
    const shouldCollapse = lines.length > maxLines;
    const displayContent = shouldCollapse && !expanded
        ? lines.slice(0, maxLines).join('\n') + `\n... (${lines.length - maxLines} more lines)`
        : content;

    const handleCopy = () => {
        navigator.clipboard.writeText(content);
    };

    return (
        <div className="relative group">
            <div className={`font-mono text-sm p-3 rounded-md overflow-x-auto
                ${isError || (exitCode !== undefined && exitCode !== 0)
                    ? 'bg-red-950/30 border border-red-800/50'
                    : 'bg-gray-900 text-gray-100'}`}>
                {exitCode !== undefined && (
                    <span className={`absolute top-2 right-2 text-xs px-1.5 py-0.5 rounded
                        ${exitCode === 0 ? 'bg-green-800 text-green-200' : 'bg-red-800 text-red-200'}`}>
                        exit {exitCode}
                    </span>
                )}
                <pre className="whitespace-pre-wrap">{displayContent}</pre>
            </div>
            {shouldCollapse && (
                <button className="text-xs text-blue-400 mt-1 hover:underline"
                    onClick={() => setExpanded(!expanded)}>
                    {expanded ? '收起' : `展开全部 (${lines.length} 行)`}
                </button>
            )}
            <button className="absolute top-2 right-10 opacity-0 group-hover:opacity-100
                text-xs text-gray-400 hover:text-white transition"
                onClick={handleCopy}>
                复制
            </button>
        </div>
    );
};
