/**
 * GroupedToolUseBlock — 将同一轮次的多个 tool_use 折叠为一组。
 *
 * SPEC: §8.2.4 批量工具卡片折叠
 * 当一轮中 tool_use 数量 > 1 时，显示为可展开的分组卡片:
 * - 折叠态: "N tool calls" + 工具名列表
 * - 展开态: 所有 ToolCallBlock 按顺序排列
 */

import React, { useState, useCallback } from 'react';
import { ChevronRight, Wrench } from 'lucide-react';
import type { ToolCallState } from '@/types';
import ToolCallBlock from './ToolCallBlock';

interface GroupedToolUseBlockProps {
    /** tool_use_id → ToolCallState 映射 */
    toolCalls: Record<string, ToolCallState>;
    /** 是否默认展开 */
    defaultExpanded?: boolean;
}

const GroupedToolUseBlock: React.FC<GroupedToolUseBlockProps> = ({
    toolCalls,
    defaultExpanded = false,
}) => {
    const entries = Object.entries(toolCalls);
    const [expanded, setExpanded] = useState(defaultExpanded || entries.length <= 2);

    const toggle = useCallback(() => setExpanded(prev => !prev), []);

    // 单个工具调用不需要分组
    if (entries.length <= 1) {
        return (
            <>
                {entries.map(([id, tc]) => (
                    <ToolCallBlock key={id} toolUseId={id} toolCall={tc} />
                ))}
            </>
        );
    }

    const completedCount = entries.filter(([, tc]) => tc.status === 'completed').length;
    const runningCount = entries.filter(([, tc]) => tc.status === 'running').length;
    const toolNames = [...new Set(entries.map(([, tc]) => tc.toolName))];

    return (
        <div className="grouped-tool-use my-2 rounded-lg border border-gray-700 bg-gray-900/30 overflow-hidden">
            {/* Group Header */}
            <button
                onClick={toggle}
                className="flex items-center gap-2 w-full px-3 py-2 bg-gray-800/30 hover:bg-gray-800/50 transition-colors text-left"
            >
                <ChevronRight
                    size={14}
                    className={`text-gray-500 transition-transform duration-200 ${expanded ? 'rotate-90' : ''}`}
                />
                <Wrench size={14} className="text-gray-500" />
                <span className="text-sm text-gray-300 font-medium">
                    {entries.length} tool calls
                </span>
                <span className="text-xs text-gray-500">
                    ({toolNames.join(', ')})
                </span>
                <span className="ml-auto text-xs text-gray-500">
                    {completedCount}/{entries.length} done
                    {runningCount > 0 && ` · ${runningCount} running`}
                </span>
            </button>

            {/* Expanded Content */}
            {expanded && (
                <div className="px-2 pb-2">
                    {entries.map(([id, tc]) => (
                        <ToolCallBlock key={id} toolUseId={id} toolCall={tc} />
                    ))}
                </div>
            )}
        </div>
    );
};

export default React.memo(GroupedToolUseBlock);
