/**
 * TurnCard — 一轮 = 完整 query ｜ 过程区 ｜ 完整回复（三层模型，Virtuoso item）
 *
 * 三层渲染（splitTurnLayers 取层）：
 * - instruction（query）与 steering 用户消息：简洁档默认折叠，其他档完整可见；
 * - 过程区：TurnProcessArea，按密度分档（compact 聚合条 / balanced 任务分节条 /
 *   detailed 分节全展开，详见 TurnProcessArea 头注释）；
 * - answer（最终回复）与 tail（error / provider_error / interrupt 系统消息）：
 *   answer 在简洁档默认折叠，tail 保持可见；answer 命中 streamingMessageId 时按流式渲染
 *   （isStreaming + streamingContent / thinkingContent / activeToolCalls 透传，
 *   与原平铺路径 MessageList.itemContent 规则一致）。
 *
 * 展开态为分节粒度受控语义：本组件订阅 density 与本会话 expandOverrides，
 * 过程区内部经 resolveSectionExpanded / setSectionExpanded 求值与写入
 * （string key：轮级 `${turnIndex}` / 分节 `${turnIndex}:${sectionIndex}` /
 * 准备段 `${turnIndex}:prep`）。
 *
 * preamble 轮（instruction = null）天然走同一三层结构（query 层为空，
 * 过程区无任务数据时退化为聚合条）。
 *
 * key 用位置序号而非 uuid：reconcileCommittedRun 会整体替换消息 uuid，
 * 位置 key 与原 TurnContent / 平铺路径语义一致，避免替换后无谓重挂载。
 */

import React, { useMemo, useState } from 'react';
import { Bot, ChevronRight } from 'lucide-react';
import type { Message, ToolCallState } from '@/types';
import type { Turn } from '@/store/selectors/turnProjection';
import { turnMessageExpandKey, useTurnViewStore } from '@/store/turnViewStore';
import { buildTurnTaskSections, splitTurnLayers } from '@/store/selectors/turnSections';
import { renderMessageContent } from '../renderMessageContent';
import TurnProcessArea from './TurnProcessArea';
import UserMessage from '../UserMessage';

export interface TurnCardProps {
    turn: Turn;
    sessionId: string | null;
    /** 运行中（streaming / waiting_permission），仅对 active 轮生效 */
    isRunActive: boolean;
    streamingMessageId?: string | null;
    streamingContent?: string;
    thinkingContent?: string;
    activeToolCalls?: Map<string, ToolCallState>;
    /** 过程区聚合条切换展开后的回调（MessageList 用于滚动对齐） */
    onAfterToggle?: (turnIndex: number, expanded: boolean) => void;
}

const TurnCard: React.FC<TurnCardProps> = ({
    turn,
    sessionId,
    isRunActive,
    streamingMessageId,
    streamingContent,
    thinkingContent,
    activeToolCalls,
    onAfterToggle,
}) => {
    const density = useTurnViewStore(s => s.density);
    const overrides = useTurnViewStore(s =>
        (sessionId ? s.expandOverrides[sessionId] : undefined));
    const [localOverrides, setLocalOverrides] = useState<Record<string, boolean>>({});
    const compact = density === 'compact';
    const messageExpanded = (key: string) => !compact || (sessionId ? overrides : localOverrides)?.[key] === true;
    const toggleMessage = (key: string) => {
        const next = !messageExpanded(key);
        if (sessionId) useTurnViewStore.getState().setSectionExpanded(sessionId, key, next);
        else setLocalOverrides(current => ({ ...current, [key]: next }));
    };
    const renderUser = (message: Message, key: string) => message.type === 'user'
        ? <UserMessage message={message} disclosure={compact ? {
            expanded: messageExpanded(key), onToggle: () => toggleMessage(key),
        } : undefined} />
        : renderMessageContent(message);
    const answerKey = turnMessageExpandKey(turn.index, 'answer');
    const answerExpanded = messageExpanded(answerKey);

    const layers = useMemo(
        () => splitTurnLayers(turn, streamingMessageId),
        [turn, streamingMessageId],
    );
    const taskSections = useMemo(
        () => buildTurnTaskSections(layers.process),
        [layers.process],
    );
    const running = turn.status === 'active' && isRunActive;
    const answerStreaming = layers.answer !== null
        && layers.answer.uuid === streamingMessageId;
    const answerContent = layers.answer && renderMessageContent(layers.answer, {
        embeddedAssistant: true,
        isStreaming: answerStreaming,
        streamingContent: answerStreaming ? streamingContent : undefined,
        thinkingContent: answerStreaming ? thinkingContent : undefined,
        activeToolCalls,
    });

    return (
        <div
            className="turn-card"
            data-turn-index={turn.index}
            data-testid={`turn-card-${turn.index}`}
        >
            {/* query 层：简洁档默认折叠（preamble 轮为 null） */}
            {layers.instruction && (
                <div data-message-uuid={layers.instruction.uuid} className="turn-message">
                    {renderUser(layers.instruction, turnMessageExpandKey(turn.index, 'query'))}
                </div>
            )}

            {/* steering 用户消息与 query 使用相同密度规则 */}
            {layers.steering.map((message, index) => (
                <div key={index} data-message-uuid={message.uuid} className="turn-message">
                    {renderUser(message, turnMessageExpandKey(turn.index, `steering-${index}`))}
                </div>
            ))}

            {/* 过程与回复共用一个外框和身份标识，形成一份完整的助手回应。 */}
            {(layers.process.length > 0 || layers.answer || layers.tail.length > 0) && (
                <section className="mx-3 mb-5 mt-1 min-w-0 rounded-2xl border border-hairline bg-surfacev2 shadow-e1 sm:mx-4"
                    aria-label="助手回复" data-testid={`turn-response-${turn.index}`}>
                    <div className="flex items-center gap-2 px-3 pt-3 text-xs font-medium text-t3 sm:px-[18px] sm:pt-4">
                        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-accent2 to-accent2-strong text-white shadow-e1">
                            <Bot size={15} aria-hidden="true" />
                        </span>
                        <span>Assistant</span>
                    </div>
                    {/* 过程区（密度分档；无过程消息则不渲染） */}
                    {layers.process.length > 0 && (
                        <div className="px-3 pb-3 pt-3 sm:px-[18px]">
                            <TurnProcessArea
                                turn={turn}
                                process={layers.process}
                                taskSections={taskSections}
                                density={density}
                                sessionId={sessionId}
                                overrides={overrides}
                                running={running}
                                isRunActive={isRunActive}
                                streamingMessageId={streamingMessageId}
                                streamingContent={streamingContent}
                                thinkingContent={thinkingContent}
                                activeToolCalls={activeToolCalls}
                                onAfterToggle={onAfterToggle}
                            />
                        </div>
                    )}

                    {/* answer 层：完整最终回复（流式命中时实时渲染） */}
                    {layers.answer && (
                        <div data-message-uuid={layers.answer.uuid} className="turn-message min-w-0 px-3 pb-3 pt-3 sm:px-[18px] sm:pb-4">
                            {compact && (
                                <button type="button" aria-expanded={answerExpanded} aria-label={`最终回复，点击${answerExpanded ? '收起' : '展开'}`}
                                    onClick={() => toggleMessage(answerKey)}
                                    className="flex min-h-11 w-full items-center gap-2 rounded-xl border border-hairline bg-surface2 px-3 py-2 text-left text-sm text-t2 hover:bg-hover2 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-accent2-ring">
                                    <span className="font-medium">{answerStreaming ? '回复生成中' : '最终回复'}</span>
                                    <span className="ml-auto shrink-0 text-xs">{answerExpanded ? '收起' : '展开'}</span>
                                    <ChevronRight size={13} aria-hidden="true" className={`shrink-0 text-t3 ${answerExpanded ? 'rotate-90' : ''}`} />
                                </button>
                            )}
                            {answerExpanded && (compact ? <div className="pt-3">{answerContent}</div> : answerContent)}
                        </div>
                    )}

                    {/* tail 层：轮次结果类系统消息（error / provider_error / interrupt） */}
                    {layers.tail.map((message, index) => (
                        <div key={index} data-message-uuid={message.uuid} className="turn-message">
                            {renderMessageContent(message)}
                        </div>
                    ))}
                </section>
            )}
        </div>
    );
};

export default React.memo(TurnCard);
