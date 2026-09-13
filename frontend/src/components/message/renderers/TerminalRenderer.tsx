/**
 * TerminalRenderer — BashTool 专用渲染器
 * 功能: stdout/stderr 分色 + 可折叠 + 复制按钮 + 退出码标签
 *
 * §7.2：终端容器走 sunken2 + hairline 令牌（与代码块同族），状态色 ok/err。
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
            <div className={`font-mono text-[12.5px] leading-[1.7] p-3 rounded-xl overflow-x-auto
                ${isError || (exitCode !== undefined && exitCode !== 0)
                    ? 'bg-errsoft border border-err text-err'
                    : 'bg-sunken2 border border-hairline text-t1'}`}>
                {exitCode !== undefined && (
                    <span className={`absolute top-2 right-2 text-xs px-1.5 py-0.5 rounded tabular-nums
                        ${exitCode === 0 ? 'bg-oksoft text-ok' : 'bg-errsoft text-err'}`}>
                        exit {exitCode}
                    </span>
                )}
                <pre className="whitespace-pre-wrap">{displayContent}</pre>
            </div>
            {shouldCollapse && (
                <button className="text-xs text-accent2 mt-1 hover:underline"
                    onClick={() => setExpanded(!expanded)}>
                    {expanded ? '收起' : `展开全部 (${lines.length} 行)`}
                </button>
            )}
            <button className="absolute top-2 right-10 opacity-0 group-hover:opacity-100
                text-xs text-t4 hover:text-t1 transition"
                onClick={handleCopy}>
                复制
            </button>
        </div>
    );
};
