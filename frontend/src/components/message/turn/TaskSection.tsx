/**
 * TaskSection — 任务分节条（balanced / detailed 过程区）+ 工具摘要行
 *
 * 分节条视觉复用 ProcessTurnItem 风格的单行聚合条：
 * `▶ 任务标题 · 状态点 · N步 · Xs`，每条独立展开/折叠：
 * - balanced：段内只显示工具摘要行（ToolSummaryRows：工具名 + 一行结果 +
 *   耗时），不再下钻；运行中分节条内嵌实时行（当前工具 + 耗时实时刷新）；
 * - detailed：段内为完整保真内容（DetailedSectionContent：完整工具参数/结果
 *   的 ToolCallBlock、完整 thinking、text/system 原位渲染），信息保真度
 *   对齐原 detailed 平铺档。
 *
 * 状态点颜色规范：蓝=运行 / 绿=完成 / 红=失败 / 琥珀=中断（灰=等待留给
 * 无分节的 TodoWrite pending 任务，见 TurnProcessArea 任务清单）。
 *
 * 展开态为受控语义：调用方（TurnProcessArea）经 resolveSectionExpanded 求值、
 * setSectionExpanded 写 override（分节粒度 key，见 store/turnViewStore）。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Check, ChevronRight, CircleSlash, Loader2 } from 'lucide-react';
import type { Message, ToolCallState } from '@/types';
import type { TurnTaskSection } from '@/store/selectors/turnSections';
import { countToolUses } from '@/store/selectors/turnSections';
import { extractPrimaryTarget, formatToolDuration } from '../ToolCallBlock';
import { AssistantBlockRenderer } from '../assistantBlockRenderer';
import { renderMessageContent } from '../renderMessageContent';
import { isCancelledResult, resolveToolCallState, type ToolUseBlock } from '../toolCallState';
import { cn } from '@/components/ui/cn';
import { useMessageStore } from '@/store/messageStore';
import { useStreamingText } from '@/hooks/useStreamingText';
import TextBlock from '../TextBlock';
import ThinkingBlock from '../ThinkingBlock';
import { formatTurnDuration, type TaskSectionStatus } from './turnUtils';

// ==================== 实时时钟 ====================

/** active 时每秒前进的时钟（运行中分节/聚合条的「已耗时」实时刷新） */
export function useNow(active: boolean): number {
    const [now, setNow] = useState(() => Date.now());
    useEffect(() => {
        if (!active) return;
        setNow(Date.now());
        const timer = window.setInterval(() => setNow(Date.now()), 1000);
        return () => window.clearInterval(timer);
    }, [active]);
    return now;
}

// ==================== 状态点 ====================

/** 分节状态点（蓝=运行 / 绿=完成 / 红=失败 / 琥珀=中断 / 灰=等待） */
export const TaskStatusDot: React.FC<{
    status: TaskSectionStatus | 'pending';
    testId?: string;
}> = ({ status, testId }) => {
    const dotClass = status === 'running'
        ? 'bg-accent2 animate-accent-pulse motion-reduce:animate-none'
        : status === 'error'
          ? 'bg-err'
          : status === 'interrupted'
            ? 'bg-warn'
            : status === 'pending'
              ? 'bg-t4'
              : 'bg-ok';
    const label = status === 'running'
        ? '进行中'
        : status === 'error'
          ? '失败'
          : status === 'interrupted'
            ? '被中断'
            : status === 'pending'
              ? '等待'
              : '已完成';
    return (
        <span
            className={cn('inline-block h-2 w-2 shrink-0 rounded-full', dotClass)}
            role="img"
            aria-label={label}
            data-testid={testId}
        />
    );
};

// ==================== 工具摘要行 ====================

/** 一行结果预览最大字符数 */
const RESULT_PREVIEW_MAX = 80;

function oneLineResult(content: string | undefined): string | null {
    if (!content) return null;
    const firstLine = content
        .split('\n')
        .map(line => line.trim())
        .find(line => line.length > 0);
    if (!firstLine) return null;
    return firstLine.length > RESULT_PREVIEW_MAX
        ? `${firstLine.slice(0, RESULT_PREVIEW_MAX)}…`
        : firstLine;
}

/** 消息序列中的全部 tool_use 块（按序，引用共享） */
export function collectToolUseBlocks(messages: Message[]): ToolUseBlock[] {
    const blocks: ToolUseBlock[] = [];
    for (const message of messages) {
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type === 'tool_use') blocks.push(block);
        }
    }
    return blocks;
}

const ToolSummaryRow: React.FC<{
    block: ToolUseBlock;
    /** 贡献该工具块的消息 uuid（pendingMessageId 深链锚点） */
    messageUuid: string;
    activeToolCalls?: Map<string, ToolCallState>;
}> = ({ block, messageUuid, activeToolCalls }) => {
    const tc = resolveToolCallState(block, activeToolCalls);
    const cancelled = isCancelledResult(tc.result);
    const running = tc.status === 'running' || tc.status === 'pending';
    const now = useNow(running);
    const target = extractPrimaryTarget(tc.input);
    const duration = running && tc.startTime > 0
        ? Math.max(0, now - tc.startTime)
        : tc.duration;
    const resultLine = !running ? oneLineResult(tc.result?.content) : null;

    return (
        <div
            className="flex items-center gap-2 py-1.5 pl-7 pr-3"
            data-message-uuid={messageUuid}
            data-testid={`tool-summary-row-${block.toolUseId}`}
        >
            {cancelled ? (
                <CircleSlash size={13} className="shrink-0 text-warn" aria-label="已取消" />
            ) : running ? (
                <Loader2 size={13} className="shrink-0 animate-spin text-accent2" aria-label="执行中" />
            ) : tc.status === 'error' ? (
                <CircleSlash size={13} className="shrink-0 text-err" aria-label="失败" />
            ) : (
                <Check size={13} className="shrink-0 text-ok" aria-label="完成" />
            )}
            <span className="shrink-0 text-sm font-medium text-t1">{tc.toolName}</span>
            {target && (
                <span
                    className="shrink-0 max-w-[36%] truncate rounded bg-sunken2 px-1.5 py-0.5 font-mono text-[11px] text-t2"
                    style={target.isPath ? { direction: 'rtl', textAlign: 'left' } : undefined}
                    title={target.target}
                >
                    {target.target}
                </span>
            )}
            {resultLine && (
                <span className="min-w-0 flex-1 truncate text-xs text-t4" title={resultLine}>
                    {resultLine}
                </span>
            )}
            {typeof duration === 'number' && (
                <span className="ml-auto shrink-0 text-xs tabular-nums text-t4">
                    {formatToolDuration(duration)}
                </span>
            )}
        </div>
    );
};

/** 工具摘要行清单（balanced 段内 / compact 无任务轮回退清单；不再下钻） */
export const ToolSummaryRows: React.FC<{
    messages: Message[];
    activeToolCalls?: Map<string, ToolCallState>;
}> = ({ messages, activeToolCalls }) => {
    const blocks = collectToolUseBlocks(messages);
    // 工具块 → 所属消息 uuid（深链锚点；同一消息贡献多个块时锚点重复无碍，
    // querySelector 取首个命中，行序即消息序）
    const uuidByBlock = new Map<ToolUseBlock, string>();
    for (const message of messages) {
        if (message.type !== 'assistant') continue;
        for (const block of message.content) {
            if (block.type === 'tool_use') uuidByBlock.set(block, message.uuid);
        }
    }
    if (blocks.length === 0) {
        return <div className="py-1.5 pl-9 pr-3 text-xs text-t4">无工具调用</div>;
    }
    return (
        <div className="divide-y divide-hairline" data-testid="tool-summary-rows">
            {blocks.map(block => (
                <ToolSummaryRow
                    key={block.toolUseId}
                    block={block}
                    messageUuid={uuidByBlock.get(block) ?? ''}
                    activeToolCalls={activeToolCalls}
                />
            ))}
        </div>
    );
};

// ==================== detailed 完整保真内容 ====================

/**
 * 分节内消息全保真渲染（对齐原 detailed 平铺档）：
 * - assistant 消息逐块渲染（text→TextBlock、thinking→ThinkingBlock、
 *   tool_use→完整 ToolCallBlock（默认态：running 展开 / 完成折叠，参数与结果
 *   可完整下钻）、image/server_tool_use/孤儿 tool_result 同 AssistantMessage）；
 * - user（steering）/system/visualization 等走共享 renderMessageContent 原位渲染；
 * - task_boundary 系统消息由 flatten/渲染层过滤，不产出气泡。
 */
/** 只有当前展开的流式过程段订阅文本增量，其他分节不随 token 重算。 */
const StreamingProcessBlocks: React.FC<{
    message: Extract<Message, { type: 'assistant' }>;
    activeToolCalls?: Map<string, ToolCallState>;
}> = ({ message, activeToolCalls }) => {
    const externalText = useStreamingText();
    const text = useMessageStore(s => s.streamingContent);
    const thinking = useMessageStore(s => s.thinkingContent);
    return <>
        {thinking && <ThinkingBlock content={thinking} streaming />}
        {(text || externalText) && <TextBlock text={text + externalText} streaming />}
        {message.content.filter(block => block.type !== 'text' && block.type !== 'thinking')
            .map((block, index) => <AssistantBlockRenderer key={index} block={block} activeToolCalls={activeToolCalls} />)}
    </>;
};

export const DetailedSectionContent: React.FC<{
    messages: Message[];
    activeToolCalls?: Map<string, ToolCallState>;
}> = ({ messages, activeToolCalls }) => {
    const streamingId = useMessageStore(s => s.streamingMessageId);
    return (
    <div className="px-3 pb-2 pl-6" data-testid="detailed-section-content">
        {messages.map((message, index) => {
            if (message.type === 'system' && message.subtype === 'task_boundary') return null;
            if (message.type === 'assistant' && message.content.length > 0) {
                return (
                    <div key={index} data-message-uuid={message.uuid} className="py-1">
                        {message.uuid === streamingId
                            ? <StreamingProcessBlocks message={message} activeToolCalls={activeToolCalls} />
                            : message.content.map((block, blockIndex) => (
                            <AssistantBlockRenderer
                                key={blockIndex}
                                block={block}
                                activeToolCalls={activeToolCalls}
                            />
                        ))}
                    </div>
                );
            }
            return (
                <div key={index} data-message-uuid={message.uuid}>
                    {renderMessageContent(message, { activeToolCalls })}
                </div>
            );
        })}
    </div>
    );
};

// ==================== 分节条 ====================

export interface TaskSectionBarProps {
    turnIndex: number;
    section: TurnTaskSection;
    status: TaskSectionStatus;
    expanded: boolean;
    /** 运行中实时行数据源：当前运行中的工具名（无则显示「执行中」） */
    runningToolName?: string | null;
    activeToolCalls?: Map<string, ToolCallState>;
    /** 内容形态：balanced = 工具摘要行；detailed = 完整保真 */
    variant: 'balanced' | 'detailed';
    onToggle: (expandKey: string, expanded: boolean) => void;
}

const TaskSectionBar: React.FC<TaskSectionBarProps> = ({
    turnIndex,
    section,
    status,
    expanded,
    runningToolName,
    activeToolCalls,
    variant,
    onToggle,
}) => {
    const expandKey = section.isPrep
        ? `${turnIndex}:prep`
        : `${turnIndex}:${section.index}`;
    const stepCount = useMemo(() => countToolUses(section.messages), [section.messages]);
    const running = status === 'running';
    const now = useNow(running);
    const duration = formatTurnDuration(section.startedAt, running ? now : section.endedAt);

    const handleToggle = useCallback(() => {
        onToggle(expandKey, !expanded);
    }, [onToggle, expandKey, expanded]);

    return (
        <div
            data-navigation-key={section.isPrep ? undefined : expandKey}
            className="task-section overflow-hidden rounded-xl border border-hairline bg-surface2"
            data-testid={`task-section-${turnIndex}-${section.isPrep ? 'prep' : section.index}`}
        >
            <button
                type="button"
                onClick={handleToggle}
                aria-expanded={expanded}
                data-expand-key={expandKey}
                className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors duration-fast hover:bg-hover2"
            >
                <ChevronRight
                    size={13}
                    className={cn(
                        'shrink-0 text-t4 transition-transform duration-base motion-reduce:transition-none',
                        expanded && 'rotate-90',
                    )}
                />
                <TaskStatusDot status={status} />
                <span className="min-w-0 flex-1 truncate text-sm font-medium text-t1">
                    {section.title}
                </span>
                {stepCount > 0 && (
                    <span className="shrink-0 text-xs tabular-nums text-t4">{stepCount} 步</span>
                )}
                <span className="shrink-0 text-xs tabular-nums text-t4">{duration}</span>
            </button>

            {/* 运行中分节条内嵌实时行（当前工具 + 耗时实时刷新） */}
            {running && (
                <div
                    className="flex items-center gap-2 border-t border-hairline py-1.5 pl-9 pr-3 text-xs text-t3"
                    data-testid={`task-section-live-${turnIndex}-${section.index}`}
                >
                    <Loader2 size={12} className="shrink-0 animate-spin text-accent2" />
                    <span className="min-w-0 truncate">
                        执行中{runningToolName ? ` · ` : ''}
                        {runningToolName && <span className="font-semibold">{runningToolName}</span>}
                    </span>
                    <span className="ml-auto shrink-0 tabular-nums text-t4">{duration}</span>
                </div>
            )}

            {expanded && (
                <div className="border-t border-hairline">
                    {variant === 'balanced' ? (
                        <ToolSummaryRows messages={section.messages} activeToolCalls={activeToolCalls} />
                    ) : (
                        <DetailedSectionContent messages={section.messages} activeToolCalls={activeToolCalls} />
                    )}
                </div>
            )}
        </div>
    );
};

export default React.memo(TaskSectionBar);
