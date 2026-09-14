/**
 * ToolRunBlock — 轮内工具调用聚合块（三级展开）
 *
 * 由 flattenTurnBlocks 的 tool_run 段渲染，将一段连续工具调用收敛为一行摘要：
 * - L1 折叠行（默认态，含 active 轮内已完成工具段）：工具图标 + 「N 次工具调用」
 *   + top 工具名计数（前 3，Read×5 · Edit×3 样式）+ 总耗时（仅实时条目带
 *   duration 时非零）+ 结果状态（全部成功=ok 色 ✓ / 有失败=err 色计数 /
 *   有取消=warn 色计数）。
 * - L2 工具列表（点 L1 展开）：每工具一行 = 状态图标 + 工具名 + 主目标
 *   （复用 ToolCallBlock.extractPrimaryTarget）+ 耗时；超过 20 个默认只显示
 *   前 20 行 + 「显示全部 N 个」。
 * - L3 单工具详情（点 L2 行展开）：内嵌完整 ToolCallBlock（受控 expanded），
 *   tool_use ↔ 结果/实时状态配对与 AssistantMessage 共用 resolveToolCallState。
 *
 * 运行变体：段内含 running/pending 块时，L1 显示「执行中 · 当前工具名 (x/y)」
 * + accent 呼吸点；L2 自动展开且当前运行工具自动展开 L3 详情。
 *
 * L2/L3 展开态均为组件内 useState（不持久化）。样式只用 v2 token 类名。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Check, ChevronRight, CircleSlash, Wrench } from 'lucide-react';
import type { ToolCallState } from '@/types';
import ToolCallBlock, {
    STATUS_CONFIG,
    extractPrimaryTarget,
    formatToolDuration,
} from '../ToolCallBlock';
import { isCancelledResult, resolveToolCallState, type ToolUseBlock } from '../toolCallState';
import { cn } from '@/components/ui/cn';

/** L2 默认展示的工具行数上限（超出由「显示全部 N 个」展开） */
const L2_VISIBLE_LIMIT = 20;
/** L1 摘要展示的 top 工具名个数 */
const TOP_NAMES_LIMIT = 3;

export interface ToolRunBlockProps {
    /** 聚合段内的 tool_use 块（≥1，由 flattenTurnBlocks 保证） */
    blocks: ToolUseBlock[];
    /** 活跃工具调用（MessageStore.activeToolCalls 透传，实时状态/耗时来源） */
    activeToolCalls?: Map<string, ToolCallState>;
}

const ToolRunBlock: React.FC<ToolRunBlockProps> = ({ blocks, activeToolCalls }) => {
    // 逐块解析渲染状态（与 AssistantMessage 同一配对逻辑：实时优先，block 兜底）
    const resolved = useMemo(
        () => blocks.map(block => ({ block, tc: resolveToolCallState(block, activeToolCalls) })),
        [blocks, activeToolCalls],
    );

    // 段级汇总（L1 数据源）
    const summary = useMemo(() => {
        const nameCounts = new Map<string, number>();
        let errorCount = 0;
        let cancelledCount = 0;
        let totalDurationMs = 0;
        let activeIndex = -1;
        resolved.forEach(({ tc }, index) => {
            nameCounts.set(tc.toolName, (nameCounts.get(tc.toolName) ?? 0) + 1);
            if (isCancelledResult(tc.result)) cancelledCount += 1;
            else if (tc.status === 'error') errorCount += 1;
            if (typeof tc.duration === 'number') totalDurationMs += tc.duration;
            if (activeIndex < 0 && (tc.status === 'running' || tc.status === 'pending')) {
                activeIndex = index;
            }
        });
        const topNames = Array.from(nameCounts.entries())
            .sort((a, b) => b[1] - a[1])
            .slice(0, TOP_NAMES_LIMIT);
        return { topNames, errorCount, cancelledCount, totalDurationMs, activeIndex };
    }, [resolved]);

    const hasActive = summary.activeIndex >= 0;
    const activeToolUseId = hasActive ? resolved[summary.activeIndex].block.toolUseId : null;

    // L2 展开态：默认折叠；段内出现运行中工具时自动展开（不自动折回）
    const [l2Open, setL2Open] = useState(hasActive);
    useEffect(() => {
        if (hasActive) setL2Open(true);
    }, [hasActive]);

    // L3 展开集合：当前运行工具自动展开详情（不自动收起）
    const [expandedIds, setExpandedIds] = useState<ReadonlySet<string>>(
        () => (activeToolUseId ? new Set([activeToolUseId]) : new Set<string>()),
    );
    useEffect(() => {
        if (!activeToolUseId) return;
        setExpandedIds(prev => (prev.has(activeToolUseId) ? prev : new Set(prev).add(activeToolUseId)));
    }, [activeToolUseId]);

    const [showAll, setShowAll] = useState(false);

    const toggleL2 = useCallback(() => setL2Open(prev => !prev), []);
    const toggleTool = useCallback((toolUseId: string) => {
        setExpandedIds(prev => {
            const next = new Set(prev);
            if (next.has(toolUseId)) next.delete(toolUseId);
            else next.add(toolUseId);
            return next;
        });
    }, []);
    const handleShowAll = useCallback(() => setShowAll(true), []);

    if (resolved.length === 0) return null;

    const total = resolved.length;
    const current = hasActive ? resolved[summary.activeIndex] : null;
    const visibleRows = showAll ? resolved : resolved.slice(0, L2_VISIBLE_LIMIT);

    return (
        <div
            className="tool-run-block my-2 overflow-hidden rounded-xl border border-hairline bg-surface2"
            data-testid="tool-run-block"
        >
            {/* L1 摘要行 */}
            <button
                type="button"
                onClick={toggleL2}
                aria-expanded={l2Open}
                className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors duration-fast hover:bg-hover2"
            >
                <ChevronRight
                    size={13}
                    className={cn(
                        'shrink-0 text-t4 transition-transform duration-base motion-reduce:transition-none',
                        l2Open && 'rotate-90',
                    )}
                />
                <Wrench size={14} className="shrink-0 text-t3" />
                {current ? (
                    <>
                        <span
                            className="inline-block h-2 w-2 shrink-0 rounded-full bg-accent2 animate-accent-pulse motion-reduce:animate-none"
                            role="img"
                            aria-label="执行中"
                            data-testid="tool-run-active-dot"
                        />
                        <span className="min-w-0 truncate text-sm text-t1">
                            执行中 · <span className="font-semibold">{current.tc.toolName}</span>
                            <span className="tabular-nums text-t3"> ({summary.activeIndex + 1}/{total})</span>
                        </span>
                    </>
                ) : (
                    <>
                        <span className="shrink-0 text-sm font-semibold text-t1">
                            {total} 次工具调用
                        </span>
                        <span className="min-w-0 truncate text-xs text-t3">
                            {summary.topNames.map(([name, count]) => `${name}×${count}`).join(' · ')}
                        </span>
                    </>
                )}
                <span className="ml-auto flex shrink-0 items-center gap-1.5">
                    {summary.totalDurationMs > 0 && (
                        <span className="text-xs tabular-nums text-t4">
                            {formatToolDuration(summary.totalDurationMs)}
                        </span>
                    )}
                    {summary.errorCount > 0 && (
                        <span className="text-xs tabular-nums text-err">{summary.errorCount} 失败</span>
                    )}
                    {summary.cancelledCount > 0 && (
                        <span className="text-xs tabular-nums text-warn">{summary.cancelledCount} 取消</span>
                    )}
                    {!hasActive && summary.errorCount === 0 && summary.cancelledCount === 0 && (
                        <span role="img" aria-label="全部成功" className="inline-flex">
                            <Check size={14} className="text-ok" />
                        </span>
                    )}
                </span>
            </button>

            {/* L2 工具列表 */}
            {l2Open && (
                <div className="border-t border-hairline" data-testid="tool-run-list">
                    {visibleRows.map(({ block, tc }) => {
                        const statusCfg = STATUS_CONFIG[tc.status];
                        const StatusIcon = statusCfg.icon;
                        const cancelled = isCancelledResult(tc.result);
                        const target = extractPrimaryTarget(tc.input);
                        const toolExpanded = expandedIds.has(block.toolUseId);
                        return (
                            <div key={block.toolUseId} className="border-b border-hairline last:border-b-0">
                                <button
                                    type="button"
                                    onClick={() => toggleTool(block.toolUseId)}
                                    aria-expanded={toolExpanded}
                                    className="flex w-full items-center gap-2 py-1.5 pl-7 pr-3 text-left transition-colors duration-fast hover:bg-hover2"
                                >
                                    <ChevronRight
                                        size={12}
                                        className={cn(
                                            'shrink-0 text-t4 transition-transform duration-base motion-reduce:transition-none',
                                            toolExpanded && 'rotate-90',
                                        )}
                                    />
                                    {cancelled ? (
                                        <CircleSlash size={13} className="shrink-0 text-warn" />
                                    ) : (
                                        <StatusIcon
                                            size={13}
                                            className={cn(
                                                'shrink-0',
                                                statusCfg.color,
                                                statusCfg.spin && 'animate-spin',
                                            )}
                                        />
                                    )}
                                    <span className="shrink-0 text-sm font-medium text-t1">
                                        {tc.toolName}
                                    </span>
                                    {target && (
                                        <span
                                            className="min-w-0 truncate rounded bg-sunken2 px-1.5 py-0.5 font-mono text-[11px] text-t2"
                                            style={target.isPath ? { direction: 'rtl', textAlign: 'left' } : undefined}
                                            title={target.target}
                                        >
                                            {target.target}
                                        </span>
                                    )}
                                    {typeof tc.duration === 'number' && (
                                        <span className="ml-auto shrink-0 text-xs tabular-nums text-t4">
                                            {formatToolDuration(tc.duration)}
                                        </span>
                                    )}
                                </button>
                                {/* L3 单工具详情（内嵌完整 ToolCallBlock，受控展开） */}
                                {toolExpanded && (
                                    <div className="px-3 pb-2 pl-9">
                                        <ToolCallBlock
                                            toolUseId={block.toolUseId}
                                            toolCall={tc}
                                            expanded
                                        />
                                    </div>
                                )}
                            </div>
                        );
                    })}
                    {!showAll && total > L2_VISIBLE_LIMIT && (
                        <button
                            type="button"
                            onClick={handleShowAll}
                            className="flex w-full items-center gap-1.5 py-1.5 pl-9 pr-3 text-xs text-t4 transition-colors duration-fast hover:text-t2"
                        >
                            <ChevronRight size={12} className="rotate-90" />
                            显示全部 {total} 个
                        </button>
                    )}
                </div>
            )}
        </div>
    );
};

export default React.memo(ToolRunBlock);
