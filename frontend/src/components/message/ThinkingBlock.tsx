/**
 * ThinkingBlock — 可折叠思考过程组件
 *
 * SPEC: §8.2.1 ThinkingBlock (可折叠/展开)
 * 显示 AI 的思考过程，默认折叠，用户可展开查看。
 * 流式思考时自动展开并显示光标动画。
 *
 * §7.2：嵌入 AI 卡内的次级块，容器 sunken + hairline，状态色走 accent2 令牌。
 */

import React, { useState, useCallback, useMemo } from 'react';
import { ChevronRight, Brain } from 'lucide-react';

interface ThinkingBlockProps {
    content: string;
    streaming?: boolean;
    /** 已脱敏的思考块 (redacted_thinking) — 仅显示占位符 */
    redacted?: boolean;
    /** 思考耗时（毫秒）：存在时折叠态标签显示「已思考 Ns」（不足 1 秒显示「已思考 <1s」） */
    durationMs?: number;
}

const ThinkingBlock: React.FC<ThinkingBlockProps> = ({
    content,
    streaming = false,
    redacted = false,
    durationMs,
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

    // 折叠态标签：有耗时优先显示「已思考 Ns」，否则保持原文案
    const collapsedLabel = useMemo(() => {
        if (!redacted && durationMs != null) {
            if (durationMs < 1000) return '已思考 <1s';
            return `已思考 ${Math.floor(durationMs / 1000)}s`;
        }
        return preview;
    }, [durationMs, redacted, preview]);

    return (
        <div className="thinking-block my-2 rounded-[14px] border border-hairline bg-surface2 overflow-hidden">
            {/* Header — always visible */}
            <button
                onClick={toggle}
                className="panel-control flex items-center gap-2 w-full px-3 py-2 text-left text-sm text-t3 hover:bg-hover2 transition-colors duration-fast"
                disabled={redacted}
            >
                <ChevronRight
                    size={14}
                    className={`transition-transform duration-base ${expanded ? 'rotate-90' : ''}`}
                />
                <Brain size={14} className="text-accent2-ink" />
                <span className="flex-1 truncate">
                    {expanded ? 'Thinking' : collapsedLabel}
                </span>
                {streaming && (
                    <span className="inline-block h-2 w-2 rounded-full bg-accent2 animate-accent-pulse motion-reduce:animate-none" />
                )}
            </button>

            {/* Content — collapsible */}
            {expanded && !redacted && (
                <div className="px-4 py-3 border-t border-hairline text-sm text-t2 whitespace-pre-wrap leading-[1.75] max-h-96 overflow-y-auto">
                    {content || 'Thinking...'}
                    {streaming && (
                        <span className="inline-block w-1.5 h-3 ml-0.5 bg-accent2 animate-pulse rounded-sm" />
                    )}
                </div>
            )}
        </div>
    );
};

export default React.memo(ThinkingBlock);
