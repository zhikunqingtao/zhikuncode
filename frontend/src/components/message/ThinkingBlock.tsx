/**
 * ThinkingBlock — 可折叠思考过程组件
 *
 * SPEC: §8.2.1 ThinkingBlock (可折叠/展开)
 * 显示 AI 的思考过程，默认折叠，用户可展开查看。
 * 流式思考时自动展开并显示光标动画。
 */

import React, { useState, useCallback, useMemo } from 'react';
import { ChevronRight, Brain } from 'lucide-react';

interface ThinkingBlockProps {
    content: string;
    streaming?: boolean;
    /** 已脱敏的思考块 (redacted_thinking) — 仅显示占位符 */
    redacted?: boolean;
}

const ThinkingBlock: React.FC<ThinkingBlockProps> = ({
    content,
    streaming = false,
    redacted = false,
}) => {
    const [expanded, setExpanded] = useState(streaming);

    const toggle = useCallback(() => {
        if (!redacted) setExpanded(prev => !prev);
    }, [redacted]);

    const preview = useMemo(() => {
        if (redacted) return 'Thinking (redacted)';
        if (!content) return 'Thinking...';
        const first = content.slice(0, 120).replace(/\n/g, ' ');
        return first.length < content.length ? `${first}...` : first;
    }, [content, redacted]);

    return (
        <div className="thinking-block my-2 rounded-lg border border-gray-700 bg-gray-900/50 overflow-hidden">
            {/* Header — always visible */}
            <button
                onClick={toggle}
                className="flex items-center gap-2 w-full px-3 py-2 text-left text-sm text-gray-400 hover:bg-gray-800/50 transition-colors"
                disabled={redacted}
            >
                <ChevronRight
                    size={14}
                    className={`transition-transform duration-200 ${expanded ? 'rotate-90' : ''}`}
                />
                <Brain size={14} className="text-purple-400" />
                <span className="flex-1 truncate">
                    {expanded ? 'Thinking' : preview}
                </span>
                {streaming && (
                    <span className="inline-block w-1.5 h-3 bg-purple-400 animate-pulse rounded-sm" />
                )}
            </button>

            {/* Content — collapsible */}
            {expanded && !redacted && (
                <div className="px-4 py-3 border-t border-gray-700/50 text-sm text-gray-400 whitespace-pre-wrap leading-relaxed max-h-96 overflow-y-auto">
                    {content || 'Thinking...'}
                    {streaming && (
                        <span className="inline-block w-1.5 h-3 ml-0.5 bg-purple-400 animate-pulse rounded-sm" />
                    )}
                </div>
            )}
        </div>
    );
};

export default React.memo(ThinkingBlock);
