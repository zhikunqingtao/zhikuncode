/**
 * TurnContent — 轮内消息渲染（轮次分组路径）
 *
 * 渲染序列为 flattenTurnBlocks 的摊平输出（合并矩阵见其头注释）：
 * - message：普通消息（user 指令/steering、system、visualization、attachment、
 *   异常空 assistant 兜底），走共享 renderMessageContent 分发（与 detailed
 *   平铺路径同一渲染逻辑）；
 * - blocks：assistant 消息内一段连续的非 tool_use 块（text/thinking/image/
 *   server_tool_use/孤儿 tool_result），经共享 AssistantBlockRenderer 块级
 *   渲染（与 AssistantMessage 终态内部分发一致）；blocks 段按 messageId 归组，
 *   每条 assistant 消息的末段下挂共享 AssistantMessageActions 消息级操作行
 *   （TTS/复制/时间戳，与 AssistantMessage 一致）——工具调用把回复拆成多条
 *   assistant 消息时，每条消息仍保有各自的操作入口；
 * - tool_run：聚合工具段，渲染为 ToolRunBlock（三级展开），tool_use ↔ 结果/
 *   实时状态配对与 AssistantMessage 共用 toolCallState.resolveToolCallState；
 * - streaming：streamingMessageId 命中的消息，整体豁免聚合，按现有流式逻辑
 *   原样渲染（关键路径零变化，见下）。
 *
 * 流式透传（关键路径）：与平铺路径 MessageList.itemContent 的规则逐条对齐 ——
 * uuid === streamingMessageId 的消息获得 isStreaming + streamingContent /
 * thinkingContent，activeToolCalls 与平铺路径一样透传，因此 active 轮内最后
 * 一条 assistant 消息的流式渲染（含 useStreamingText 外部缓冲、实时工具挂
 * 流式消息下、Thinking... RunningIndicator 兜底）行为零变化。
 *
 * 深链锚点：每个渲染项包 data-message-uuid wrapper（pendingMessageId 轮内
 * 定位）；tool_run 段贡献了工具块的全部消息 uuid 都会落锚点（首 uuid 在
 * wrapper 上，其余为 sr-only 锚点），吸收进聚合段的消息仍可深链直达。
 *
 * key 用位置序号而非 uuid：reconcileCommittedRun 会整体替换消息 uuid，
 * 位置 key 与平铺路径 Virtuoso 的 index key 语义一致，避免替换后无谓重挂载。
 */

import React from 'react';
import type { ToolCallState } from '@/types';
import type { Turn } from '@/store/selectors/turnProjection';
import { renderMessageContent } from '../renderMessageContent';
import { AssistantBlockRenderer } from '../assistantBlockRenderer';
import AssistantMessageActions from '../AssistantMessageActions';
import { flattenTurnBlocks } from './flattenTurnBlocks';
import ToolRunBlock from './ToolRunBlock';

export interface TurnContentProps {
    turn: Turn;
    streamingMessageId?: string | null;
    streamingContent?: string;
    thinkingContent?: string;
    activeToolCalls?: Map<string, ToolCallState>;
}

const TurnContent: React.FC<TurnContentProps> = ({
    turn,
    streamingMessageId,
    streamingContent,
    thinkingContent,
    activeToolCalls,
}) => {
    const items = flattenTurnBlocks(turn, { streamingMessageId });
    // blocks 段按 messageId 归组：记录每条 assistant 消息最后一个 blocks 段的
    // 位置，仅在该段下挂消息级操作行（同消息被工具段切开的多段不重复 chrome）
    const lastBlocksIndexByMessage = new Map<string, number>();
    items.forEach((item, i) => {
        if (item.kind === 'blocks') lastBlocksIndexByMessage.set(item.message.uuid, i);
    });
    return (
        <>
            {items.map((item, index) => {
                switch (item.kind) {
                    case 'streaming':
                        // 流式分支：与摊平前逐字对齐（isStreaming + 流式上下文 +
                        // activeToolCalls 透传），行为零变化
                        return (
                            <div key={index} data-message-uuid={item.message.uuid} className="turn-message">
                                {renderMessageContent(item.message, {
                                    isStreaming: true,
                                    streamingContent,
                                    thinkingContent,
                                    activeToolCalls,
                                })}
                            </div>
                        );
                    case 'tool_run':
                        return (
                            <div
                                key={index}
                                data-message-uuid={item.messageIds[0]}
                                className="turn-message px-4"
                            >
                                {/* 被吸收消息的深链锚点（不可见，不占布局） */}
                                {item.messageIds.slice(1).map(uuid => (
                                    <span
                                        key={uuid}
                                        data-message-uuid={uuid}
                                        className="sr-only"
                                        aria-hidden="true"
                                    />
                                ))}
                                <ToolRunBlock blocks={item.blocks} activeToolCalls={activeToolCalls} />
                            </div>
                        );
                    case 'blocks':
                        return (
                            <div key={index} data-message-uuid={item.message.uuid} className="turn-message">
                                <div className="px-4 py-1 text-sm text-t1 leading-[1.75]">
                                    {item.blocks.map((block, blockIndex) => (
                                        <AssistantBlockRenderer
                                            key={blockIndex}
                                            block={block}
                                            activeToolCalls={activeToolCalls}
                                        />
                                    ))}
                                </div>
                                {/* 消息级操作行（该消息末个 blocks 段挂一次） */}
                                {lastBlocksIndexByMessage.get(item.message.uuid) === index && (
                                    <AssistantMessageActions
                                        message={item.message}
                                        className="mx-4 mb-1"
                                    />
                                )}
                            </div>
                        );
                    case 'message':
                        return (
                            <div key={index} data-message-uuid={item.message.uuid} className="turn-message">
                                {renderMessageContent(item.message, { activeToolCalls })}
                            </div>
                        );
                    default:
                        return null;
                }
            })}
        </>
    );
};

export default React.memo(TurnContent);
