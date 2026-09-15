/**
 * TurnProcessArea — 轮内「过程区」渲染（三层模型的中间层）
 *
 * 三档密度只控制过程区粒度（query 与 answer 由 TurnCard 常驻渲染）：
 * - compact（默认）：一条聚合条 `● N个任务 · M步 · Xs · 已收起 ▶`
 *   （hasTaskData=false 时省略「N个任务」；M = countToolUses）。
 *   展开只读任务标题清单（状态点 + 标题）；点击某任务 → setDensity('balanced')
 *   + setSectionExpanded(sectionKey, true) 跳转展开该分节（sectionIndex 为 null
 *   的 pending 任务不可点击）。运行中该轮：聚合条变实时状态条
 *   （呼吸蓝点 + 当前任务标题 + 已耗时，秒级刷新）。无任务数据的轮展开时
 *   退化为轮级过程清单（沿用 TurnContent 渲染）。
 * - balanced：TaskSectionBar 分节条列表，默认折叠、独立展开
 *   （resolveSectionExpanded：override 优先，否则仅运行中分节展开——
 *   任务完成自动折回，手动展开过的不折）；段内只显示工具摘要行，不下钻；
 *   运行中当前任务条内嵌实时行。无任务数据的轮 → compact 同款聚合条。
 * - detailed：分节（含 prep「准备」段，仅此档显示）全展开（默认），
 *   段内完整保真（完整工具参数/结果 + 完整 thinking，对齐原 detailed
 *   平铺档）；无任务数据的轮直接全保真渲染过程消息（无聚合条）。
 *
 * 展开态为受控语义：resolveSectionExpanded 求值（分节粒度 string key，
 * 见 store/turnViewStore），setSectionExpanded 写 override。
 * 聚合条展开/收起复用 globals.css 的 .expand-collapse 动画，折叠后延迟
 * 300ms 卸载内容（对齐原 TurnCard 行为）。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ChevronDown, Check, X, Minus, Loader2 } from 'lucide-react';
import type { Message, ToolCallState } from '@/types';
import type { Turn } from '@/store/selectors/turnProjection';
import {
    countToolUses,
    type TurnTaskSection,
    type TurnTaskSections,
} from '@/store/selectors/turnSections';
import {
    prepExpandKey,
    resolveSectionExpanded,
    sectionExpandKey,
    turnExpandKey,
    useTurnViewStore,
    type TurnDensity,
} from '@/store/turnViewStore';
import { cn } from '@/components/ui/cn';
import { isCancelledResult, resolveToolCallState } from '../toolCallState';
import TaskSectionBar, {
    DetailedSectionContent,
    TaskStatusDot,
    useNow,
} from './TaskSection';
import TurnContent from './TurnContent';
import {
    formatTurnDuration,
    resolveSectionStatus,
    resolveTurnOutcome,
    type TaskSectionStatus,
} from './turnUtils';

/** 折叠动画（--v2-dur-slow 240ms）结束后卸载内容的缓冲时长（对齐原 TurnCard） */
const COLLAPSE_UNMOUNT_MS = 300;

export interface TurnProcessAreaProps {
    turn: Turn;
    /** splitTurnLayers 产出的过程区消息 */
    process: Message[];
    /** buildTurnTaskSections 产出的任务分节（hasTaskData=false → 聚合条路径） */
    taskSections: TurnTaskSections;
    density: TurnDensity;
    sessionId: string | null;
    /** 当前会话的分节粒度手动展开偏好（expandOverrides[sessionId]） */
    overrides?: Record<string, boolean>;
    /** 运行中（turn.status === 'active' 且 run 进行中） */
    running: boolean;
    isRunActive: boolean;
    streamingMessageId?: string | null;
    streamingContent?: string;
    thinkingContent?: string;
    activeToolCalls?: Map<string, ToolCallState>;
    /** 聚合条切换展开后的回调（MessageList 用于滚动对齐） */
    onAfterToggle?: (turnIndex: number, expanded: boolean) => void;
}

// ==================== 状态推导辅助 ====================

/**
 * 聚合条状态点：运行中 → 蓝（呼吸）；轮次结果 error 或任一过程工具失败 → 红；
 * interrupt 或已取消工具 → 琥珀；否则 → 绿（与 TaskSectionBar 状态点同一语义）。
 */
function resolveProcessDotStatus(
    turn: Turn,
    process: Message[],
    running: boolean,
    activeToolCalls?: Map<string, ToolCallState>,
): TaskSectionStatus {
    if (running) return 'running';
    const outcome = resolveTurnOutcome(turn);
    if (outcome === 'error') return 'error';
    let hasCancelled = false;
    for (const message of process) {
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type !== 'tool_use') continue;
            const tc = resolveToolCallState(block, activeToolCalls);
            if (isCancelledResult(tc.result)) {
                hasCancelled = true;
                continue;
            }
            if (tc.status === 'error' || tc.result?.isError === true) return 'error';
        }
    }
    if (outcome === 'interrupted' || hasCancelled) return 'interrupted';
    return 'completed';
}

/**
 * 消息序列中「当前正在运行」的工具名：取最后一个 tool_use 块，
 * 其 resolved 状态为 running/pending 时返回工具名，否则 null
 * （工具串行执行，最后一个工具未运行即本段无实时工具）。
 */
function findRunningToolName(
    messages: Message[],
    activeToolCalls?: Map<string, ToolCallState>,
): string | null {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
        const message = messages[i];
        if (message.type !== 'assistant') continue;
        for (let j = message.content.length - 1; j >= 0; j -= 1) {
            const block = message.content[j];
            if (block.type !== 'tool_use') continue;
            const tc = resolveToolCallState(block, activeToolCalls);
            return tc.status === 'running' || tc.status === 'pending' ? tc.toolName : null;
        }
    }
    return null;
}

// ==================== compact 只读任务标题清单 ====================

const TaskTitleList: React.FC<{
    turn: Turn;
    taskSections: TurnTaskSections;
    sessionId: string | null;
    isRunActive: boolean;
    activeToolCalls?: Map<string, ToolCallState>;
}> = ({ turn, taskSections, sessionId, isRunActive, activeToolCalls }) => {
    const { tasks, sections } = taskSections;
    const isActiveTurn = turn.status === 'active';
    const turnOutcome = resolveTurnOutcome(turn);

    // 跳转展开：先切档（setDensity 会清空本会话 overrides），再写目标分节 override
    const handleJump = useCallback((sectionIndex: number) => {
        if (!sessionId) return;
        const store = useTurnViewStore.getState();
        store.setDensity('balanced', sessionId);
        store.setSectionExpanded(sessionId, sectionExpandKey(turn.index, sectionIndex), true);
    }, [sessionId, turn.index]);

    return (
        <ul className="py-1" data-testid={`turn-task-list-${turn.index}`}>
            {tasks.map((task, index) => {
                const section = task.sectionIndex !== null
                    ? sections[task.sectionIndex]
                    : undefined;
                const status: TaskSectionStatus | 'pending' = section
                    ? resolveSectionStatus(section, activeToolCalls, {
                        isActiveTurn,
                        isRunActive,
                        isLastSection: section.index === sections.length - 1,
                        turnOutcome,
                    })
                    : 'pending';
                // sectionIndex 为 null 的 pending 任务（TodoWrite 快照中从未开始）不可点击
                const clickable = task.sectionIndex !== null && Boolean(sessionId);
                return (
                    <li key={index}>
                        <button
                            type="button"
                            disabled={!clickable}
                            onClick={() => {
                                if (task.sectionIndex !== null) handleJump(task.sectionIndex);
                            }}
                            data-testid={`turn-task-item-${turn.index}-${index}`}
                            className={cn(
                                'flex w-full items-center gap-2 py-1.5 pl-9 pr-3 text-left',
                                clickable
                                    ? 'transition-colors duration-fast hover:bg-hover2'
                                    : 'cursor-default',
                            )}
                        >
                            <TaskStatusDot status={status} />
                            <span className="min-w-0 flex-1 truncate text-sm text-t1">
                                {task.title}
                            </span>
                        </button>
                    </li>
                );
            })}
        </ul>
    );
};

// ==================== compact 聚合条（balanced 无任务轮同款回退） ====================

const ProcessAggregateBar: React.FC<TurnProcessAreaProps> = ({
    turn,
    process,
    taskSections,
    density,
    sessionId,
    overrides,
    running,
    isRunActive,
    streamingMessageId,
    streamingContent,
    thinkingContent,
    activeToolCalls,
    onAfterToggle,
}) => {
    const { sections, tasks, hasTaskData } = taskSections;
    const expandKey = turnExpandKey(turn.index);
    const expanded = resolveSectionExpanded(density, expandKey, overrides);

    // 折叠时延迟卸载内容：保留 grid-rows 折叠动画，避免长过程区常驻 DOM
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
            useTurnViewStore.getState().setSectionExpanded(sessionId, expandKey, !expanded);
        }
        onAfterToggle?.(turn.index, !expanded);
    }, [sessionId, expandKey, expanded, turn.index, onAfterToggle]);

    const steps = useMemo(() => countToolUses(process), [process]);
    const now = useNow(running);
    const duration = formatTurnDuration(turn.startedAt, running ? now : turn.endedAt)
        .replace(/(\d+)h/g, (_, n) => `${Number(n)}时`)
        .replace(/(\d+)m/g, (_, n) => `${Number(n)}分`)
        .replace(/(\d+)s/g, (_, n) => `${Number(n)}秒`);
    const dotStatus = resolveProcessDotStatus(turn, process, running, activeToolCalls);
    const statusLabel = { completed: '执行已完成', running: '正在执行', error: '执行有失败', interrupted: '执行已中断' }[dotStatus];
    const StatusIcon = { completed: Check, running: Loader2, error: X, interrupted: Minus }[dotStatus];
    // 运行中实时状态条：当前任务 = 最后一个分节；无任务数据回退当前运行工具名
    const currentTitle = !running
        ? null
        : hasTaskData
          ? sections[sections.length - 1]?.title ?? '执行中'
          : findRunningToolName(process, activeToolCalls) ?? '执行中';

    // 无任务数据的轮展开时退化为轮级过程清单（沿用现有轮级展开内容渲染）：
    // 合成「仅含过程消息」的轮喂给 TurnContent（其只读 turn.messages，见 flattenTurnBlocks）
    const processTurn = useMemo<Turn>(
        () => ({ ...turn, instruction: null, messages: process }),
        [turn, process],
    );

    return (
        <div
            className="turn-process-aggregate overflow-hidden rounded-[14px] border border-hairline bg-surface2"
            data-testid={`turn-process-${turn.index}`}
        >
            <button
                type="button"
                onClick={handleToggle}
                aria-expanded={expanded}
                aria-label={`详细过程区，${expanded ? '点击收起' : '点击展开'}${running ? `，当前任务：${currentTitle}` : ''}`}
                data-turn-header={turn.index}
                className={cn(
                    'process-aggregate-toggle flex min-h-11 w-full flex-col gap-2 rounded-[13px] px-3 py-3 text-left',
                    'transition-colors duration-fast hover:bg-hover2',
                )}
            >
                <span className="flex w-full flex-wrap items-center justify-between gap-x-3 gap-y-1">
                    <span className="inline-flex items-center gap-2 text-sm font-medium text-t1">
                        <span role="img" aria-label={{ completed: '已完成', running: '进行中', error: '失败', interrupted: '被中断' }[dotStatus]} data-testid={`turn-process-dot-${turn.index}`} className={cn('inline-flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-full text-white',
                            dotStatus === 'completed' ? 'bg-ok' : dotStatus === 'error' ? 'bg-err' : dotStatus === 'interrupted' ? 'bg-warn' : 'bg-accent2 animate-accent-pulse motion-reduce:animate-none')}><StatusIcon size={12} aria-hidden="true" /></span>
                        {statusLabel}
                    </span>
                    <span className="flex flex-wrap items-center gap-x-1.5 text-[13px] tabular-nums text-t2">
                        {hasTaskData && <><span>{tasks.length} 个任务</span><span aria-hidden="true">·</span></>}
                        {steps > 0 && <><span>{steps} 步</span><span aria-hidden="true">·</span></>}
                        <span>{duration}</span>
                    </span>
                </span>
                {running && <span className="w-full truncate text-[13px] text-t2">当前任务：{currentTitle}</span>}
                <span className="flex w-full items-center justify-between gap-2 text-sm font-medium text-accent2-ink">
                    <span>{expanded ? '收起执行过程' : '查看执行过程'}</span>
                    <ChevronDown size={18} aria-hidden="true" className={cn('shrink-0 transition-transform duration-base motion-reduce:transition-none', expanded && 'rotate-180')} />
                </span>
            </button>

            <div className="expand-collapse" data-open={expanded}>
                <div className="expand-collapse-inner">
                    {contentMounted && (
                        <div className="border-t border-hairline">
                            {hasTaskData ? (
                                <TaskTitleList
                                    turn={turn}
                                    taskSections={taskSections}
                                    sessionId={sessionId}
                                    isRunActive={isRunActive}
                                    activeToolCalls={activeToolCalls}
                                />
                            ) : (
                                <TurnContent
                                    turn={processTurn}
                                    streamingMessageId={streamingMessageId}
                                    streamingContent={streamingContent}
                                    thinkingContent={thinkingContent}
                                    activeToolCalls={activeToolCalls}
                                />
                            )}
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

// ==================== balanced / detailed 分节条列表 ====================

const TaskSectionList: React.FC<TurnProcessAreaProps> = ({
    turn,
    taskSections,
    density,
    sessionId,
    overrides,
    isRunActive,
    activeToolCalls,
}) => {
    const { sections, prep } = taskSections;
    const isActiveTurn = turn.status === 'active';
    const turnOutcome = resolveTurnOutcome(turn);
    // prep「准备」段仅 detailed 档展示（排在全部任务分节之前）
    const ordered: TurnTaskSection[] = density === 'detailed' && prep
        ? [prep, ...sections]
        : sections;

    const handleToggle = useCallback((expandKey: string, next: boolean) => {
        if (!sessionId) return;
        useTurnViewStore.getState().setSectionExpanded(sessionId, expandKey, next);
    }, [sessionId]);

    return (
        <div
            className="flex flex-col gap-1.5"
            data-testid={`turn-task-sections-${turn.index}`}
        >
            {ordered.map(section => {
                const expandKey = section.isPrep
                    ? prepExpandKey(turn.index)
                    : sectionExpandKey(turn.index, section.index);
                const status = resolveSectionStatus(section, activeToolCalls, {
                    isActiveTurn,
                    isRunActive,
                    isLastSection: !section.isPrep && section.index === sections.length - 1,
                    turnOutcome,
                });
                const expanded = resolveSectionExpanded(density, expandKey, overrides, {
                    runningSection: status === 'running',
                });
                const runningToolName = status === 'running'
                    ? findRunningToolName(section.messages, activeToolCalls)
                    : null;
                return (
                    <TaskSectionBar
                        key={expandKey}
                        turnIndex={turn.index}
                        section={section}
                        status={status}
                        expanded={expanded}
                        runningToolName={runningToolName}
                        activeToolCalls={activeToolCalls}
                        variant={density === 'detailed' ? 'detailed' : 'balanced'}
                        onToggle={handleToggle}
                    />
                );
            })}
        </div>
    );
};

// ==================== 过程区分发 ====================

const TurnProcessArea: React.FC<TurnProcessAreaProps> = (props) => {
    const { density, taskSections, process, activeToolCalls, turn } = props;
    if (density === 'detailed' && !taskSections.hasTaskData) {
        // 无分节数据：详细档直接全保真渲染过程消息（无聚合条，对齐原 detailed 平铺档）
        return (
            <div className="turn-process-detailed" data-testid={`turn-process-${turn.index}`}>
                <DetailedSectionContent messages={process} activeToolCalls={activeToolCalls} />
            </div>
        );
    }
    if (density === 'compact' || !taskSections.hasTaskData) {
        // compact 恒为聚合条；balanced 无任务数据时退化为同款聚合条
        return <ProcessAggregateBar {...props} />;
    }
    return <TaskSectionList {...props} />;
};

export default React.memo(TurnProcessArea);
