/**
 * TurnCard — 一轮 = 一张卡（compact/balanced 分组路径的 Virtuoso item）
 *
 * 折叠态：v2 卡（surfacev2 + rounded-2xl + hairline + shadow-e1，hover 升 e2），
 * header 单行 = 状态点 + 「第 N 轮」+ 指令首行粗体截断 + 右侧耗时 + 复制本轮 + chevron；
 * header 下挂折叠态 meta 行（.turn-card-meta）：耗时 + 消息条数 + 工具统计
 * （.turn-card-tool-stats：🔧 N 次工具 · top3 工具名计数，turnToolStats 数据源）
 * + 文件变更数（>0 时显示）；completed 轮再追加一行结论预览
 * （turnConclusionPreview：最后一个 assistant text 块首行 ~80 字符，tertiary 色
 * + 右侧渐隐 mask）。
 * 展开态：header 吸顶（sticky，长轮滚动中可随时折回）+ 内容区左侧 hairline 竖导轨。
 *
 * 状态点：active 且 run 进行中 → accent 呼吸点；否则轮内任一工具 error 或
 * error/provider_error system 消息 → 红；interrupt system 消息或已取消工具 → 琥珀；
 * 其余 → 翠绿。
 *
 * preamble 轮（instruction = null）不渲染卡片头，消息平铺展示。
 *
 * 展开/折叠动画复用 globals.css 的 .expand-collapse（grid-rows 方案，reduced-motion
 * 已由全局 §8.8.5 规则降级）；折叠时内容延迟 300ms 卸载，兼顾折叠动画与长列表性能。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { Check, ChevronDown, Copy, FilePen, Wrench } from 'lucide-react';
import type { ToolCallState } from '@/types';
import type { Turn } from '@/store/selectors/turnProjection';
import { useTurnViewStore } from '@/store/turnViewStore';
import { useNotificationStore } from '@/store/notificationStore';
import { cn } from '@/components/ui/cn';
import TurnContent from './TurnContent';
import {
    extractTurnText,
    formatTurnDuration,
    instructionFirstLine,
    resolveTurnOutcome,
    turnConclusionPreview,
    turnToolStats,
} from './turnUtils';

/** 复制成功图标反馈时长 (ms)，与 MessageActions/CodeBlock 保持一致 */
const COPY_ICON_RESET_MS = 2000;
/** 折叠动画（--v2-dur-slow 240ms）结束后卸载内容的缓冲时长 */
const COLLAPSE_UNMOUNT_MS = 300;

export interface TurnCardProps {
    turn: Turn;
    /** 「第 N 轮」序号（preamble 为 null） */
    turnNumber: number | null;
    sessionId: string | null;
    expanded: boolean;
    /** 运行中（streaming / waiting_permission），仅对 active 轮生效 */
    isRunActive: boolean;
    streamingMessageId?: string | null;
    streamingContent?: string;
    thinkingContent?: string;
    activeToolCalls?: Map<string, ToolCallState>;
    /** 切换展开后的回调（MessageList 用于「手动展开历史轮不抢滚动」对齐） */
    onAfterToggle?: (turnIndex: number, expanded: boolean) => void;
}

const TurnCard: React.FC<TurnCardProps> = ({
    turn,
    turnNumber,
    sessionId,
    expanded,
    isRunActive,
    streamingMessageId,
    streamingContent,
    thinkingContent,
    activeToolCalls,
    onAfterToggle,
}) => {
    const [copied, setCopied] = useState(false);
    // 折叠时延迟卸载内容：保留 grid-rows 折叠动画，避免长轮内容常驻 DOM
    const [contentMounted, setContentMounted] = useState(expanded);
    useEffect(() => {
        if (expanded) {
            setContentMounted(true);
            return;
        }
        if (!contentMounted) return;
        const timer = setTimeout(() => setContentMounted(false), COLLAPSE_UNMOUNT_MS);
        return () => clearTimeout(timer);
    }, [expanded, contentMounted]);

    const handleToggle = useCallback(() => {
        if (sessionId) {
            useTurnViewStore.getState().setTurnExpanded(sessionId, turn.index, !expanded);
        }
        onAfterToggle?.(turn.index, !expanded);
    }, [sessionId, turn.index, expanded, onAfterToggle]);

    const turnText = extractTurnText(turn);

    const handleCopy = useCallback(async (event: React.MouseEvent<HTMLButtonElement>) => {
        event.stopPropagation();
        if (!turnText) return;
        try {
            await navigator.clipboard.writeText(turnText);
            setCopied(true);
            setTimeout(() => setCopied(false), COPY_ICON_RESET_MS);
            useNotificationStore.getState().addNotification({
                key: `turn-copy-${turn.key}`,
                level: 'success',
                message: '已复制本轮内容',
            });
        } catch {
            // 剪贴板权限被拒等场景静默失败，与 MessageActions 保持一致
        }
    }, [turnText, turn.key]);

    // preamble 轮：无卡片头，消息平铺
    if (!turn.instruction) {
        return (
            <div className="turn-preamble" data-turn-index={turn.index}>
                <TurnContent
                    turn={turn}
                    streamingMessageId={streamingMessageId}
                    streamingContent={streamingContent}
                    thinkingContent={thinkingContent}
                    activeToolCalls={activeToolCalls}
                />
            </div>
        );
    }

    const running = turn.status === 'active' && isRunActive;
    const outcome = resolveTurnOutcome(turn);
    // 工具统计（meta 行 + 状态点数据源）：轮内 tool_use 计数/top 工具名/
    // 文件变更/失败与取消计数（O(轮内块数)，纯函数）
    const toolStats = turnToolStats(turn, activeToolCalls);
    // completed 轮结论预览（折叠态追加行）；active 轮不展示
    const conclusion = turn.status === 'completed' ? turnConclusionPreview(turn) : null;
    // 状态点：工具 error 与 error 系统消息同级判红；取消与 interrupt 同级判琥珀
    const hasError = outcome === 'error' || toolStats.errorCount > 0;
    const hasInterrupt = outcome === 'interrupted' || toolStats.cancelledCount > 0;
    const dotClass = running
        ? 'bg-accent2 animate-accent-pulse motion-reduce:animate-none'
        : hasError
          ? 'bg-err'
          : hasInterrupt
            ? 'bg-warn'
            : 'bg-ok';
    const dotLabel = running
        ? '进行中'
        : hasError
          ? '出错'
          : hasInterrupt
            ? '被中断'
            : '已完成';
    const duration = formatTurnDuration(turn.startedAt, turn.endedAt);

    return (
        <div
            className={cn(
                'turn-card group/turn-card mx-3 my-2 rounded-2xl border border-hairline',
                'bg-surfacev2 shadow-e1 transition-surface duration-base hover:shadow-e2',
            )}
            data-turn-index={turn.index}
            data-testid={`turn-card-${turn.index}`}
        >
            {/* Header（单行；展开时吸顶 + 底部 hairline）。展开切换 = 真实 <button>
                （包裹全部非交互内容，原生支持 Enter/Space），复制按钮为其兄弟节点，
                避免嵌套交互元素。 */}
            <div
                className={cn(
                    'flex items-center select-none rounded-t-2xl',
                    expanded && 'sticky top-0 z-10 border-b border-hairline bg-surfacev2',
                )}
            >
                <button
                    type="button"
                    aria-expanded={expanded}
                    aria-label={`第 ${turnNumber} 轮，${expanded ? '点击折叠' : '点击展开'}`}
                    data-turn-header={turn.index}
                    onClick={handleToggle}
                    className={cn(
                        'flex min-w-0 flex-1 cursor-pointer items-center gap-2 px-3 py-2.5 text-left',
                        'rounded-t-2xl focus-visible:outline-none focus-visible:ring-[3px]',
                        'focus-visible:ring-accent2-ring focus-visible:ring-inset',
                    )}
                >
                    <span
                        className={cn('inline-block h-2 w-2 shrink-0 rounded-full', dotClass)}
                        role="img"
                        aria-label={dotLabel}
                        data-testid={`turn-status-dot-${turn.index}`}
                    />
                    <span className="shrink-0 text-xs text-t4 tabular-nums">
                        第 {turnNumber} 轮
                    </span>
                    <span className="min-w-0 flex-1 truncate text-left text-sm font-semibold text-t1">
                        {instructionFirstLine(turn.instruction)}
                    </span>
                    <span className="shrink-0 text-xs text-t4 tabular-nums">{duration}</span>
                    <ChevronDown
                        className={cn(
                            'h-4 w-4 shrink-0 text-t4 transition-transform duration-base motion-reduce:transition-none',
                            expanded && 'rotate-180',
                        )}
                    />
                </button>
                {turnText !== null && (
                    <button
                        type="button"
                        onClick={handleCopy}
                        title={copied ? '已复制' : '复制本轮'}
                        aria-label={copied ? '已复制' : '复制本轮'}
                        data-testid={`turn-copy-button-${turn.index}`}
                        className={cn(
                            'mr-3 shrink-0 rounded-md p-1 text-t4 transition-all duration-fast',
                            'hover:bg-hover2 hover:text-t1',
                            'opacity-0 group-hover/turn-card:opacity-100 focus-visible:opacity-100',
                            copied && 'opacity-100 text-ok',
                        )}
                    >
                        {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
                    </button>
                )}
            </div>

            {/* 折叠态 meta 行：耗时 + 消息条数 + 工具统计 + 文件变更 */}
            {!expanded && (
                <div className="turn-card-meta flex items-center gap-1.5 px-3 pb-2 text-xs text-t4">
                    <span className="tabular-nums">{duration}</span>
                    <span aria-hidden="true">·</span>
                    <span className="tabular-nums">{turn.messages.length} 条消息</span>
                    <span
                        className="turn-card-tool-stats flex min-w-0 items-center gap-1.5"
                        data-testid="turn-card-tool-stats"
                    >
                        {toolStats.total > 0 && (
                            <>
                                <span aria-hidden="true">·</span>
                                <Wrench size={12} className="shrink-0" aria-hidden="true" />
                                <span className="shrink-0 tabular-nums">{toolStats.total} 次工具</span>
                                {toolStats.topNames.length > 0 && (
                                    <>
                                        <span aria-hidden="true">·</span>
                                        <span className="min-w-0 truncate tabular-nums">
                                            {toolStats.topNames
                                                .map(([name, count]) => `${name}×${count}`)
                                                .join(' ')}
                                        </span>
                                    </>
                                )}
                            </>
                        )}
                    </span>
                    {toolStats.filesChanged > 0 && (
                        <>
                            <span aria-hidden="true">·</span>
                            <FilePen size={12} className="shrink-0" aria-hidden="true" />
                            <span className="shrink-0 tabular-nums">
                                {toolStats.filesChanged} 个文件变更
                            </span>
                        </>
                    )}
                </div>
            )}

            {/* 折叠态结论预览（completed 轮：最后一个 assistant text 块首行，右侧渐隐） */}
            {!expanded && conclusion !== null && (
                <div
                    className={cn(
                        'turn-card-conclusion overflow-hidden whitespace-nowrap px-3 pb-2 text-xs text-t3',
                        '[mask-image:linear-gradient(to_right,black_72%,transparent_98%)]',
                    )}
                    data-testid={`turn-card-conclusion-${turn.index}`}
                >
                    {conclusion}
                </div>
            )}

            {/* 展开内容（grid-rows 展开/收起动画 + 左侧 hairline 竖导轨） */}
            <div className="expand-collapse" data-open={expanded}>
                <div className="expand-collapse-inner">
                    {contentMounted && (
                        <div className="turn-card-content ml-4 border-l border-hairline pb-1 pl-1">
                            <TurnContent
                                turn={turn}
                                streamingMessageId={streamingMessageId}
                                streamingContent={streamingContent}
                                thinkingContent={thinkingContent}
                                activeToolCalls={activeToolCalls}
                            />
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

export default React.memo(TurnCard);
