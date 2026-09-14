/**
 * flattenTurnBlocks — 轮内消息序列 → 渲染序列（纯函数，O(n)，不改输入）
 *
 * 将一轮（Turn）的消息按序摊平，并把「无人值守块介入的连续 tool_use」
 * 聚合为工具段（tool_run），供 TurnContent 渲染：
 * - message：普通消息（user 指令/steering、system、visualization、attachment 等），
 *   走共享 renderMessageContent 分发；
 * - blocks：assistant 消息内一段连续的非 tool_use 块（text/thinking/image/
 *   server_tool_use/孤儿 tool_result），保持原位渲染（与 AssistantMessage 内部一致）；
 * - tool_run：聚合工具段（可跨相邻 assistant 消息），渲染为 ToolRunBlock；
 * - streaming：streamingMessageId 命中的消息，整体豁免聚合，
 *   由 TurnContent 按现有流式逻辑原样渲染（activeToolCalls 行为零变化）。
 *
 * 合并矩阵（阻断 = 结束当前工具段）：
 * | 介入元素                                   | 对工具段 |
 * |-------------------------------------------|---------|
 * | assistant 内连续 tool_use                  | 并入    |
 * | 纯 tool_result 的 user 载体消息            | 透明跳过（不阻断，不产出渲染项——
 * |                                           | 与 TurnContent/UserMessage 现状一致） |
 * | 跨相邻 assistant 消息的 tool_use           | 并入（无阻断元素时） |
 * | text/thinking 等非 tool_use 块             | 阻断（产出 blocks 项） |
 * | steering user 消息（含 text/image）        | 阻断（产出 message 项） |
 * | system / visualization / attachment 等     | 阻断（产出 message 项） |
 * | streamingMessageId 命中的消息              | 阻断（产出 streaming 项） |
 *
 * 边界情形：
 * - 非流式 assistant 空消息（content 无块，异常兜底）→ 整体作为 message 项
 *   原样渲染（与摊平前行为一致）；
 * - tool_run.messageIds 记录贡献了工具块的消息 uuid（按序去重），
 *   供深链锚点使用。
 */

import type { ContentBlock, Message } from '@/types';
import type { Turn } from '@/store/selectors/turnProjection';
import type { ToolUseBlock } from '../toolCallState';

export type AssistantMessage = Extract<Message, { type: 'assistant' }>;

export type FlattenedTurnItem =
    | { kind: 'message'; message: Message }
    | { kind: 'blocks'; message: AssistantMessage; blocks: ContentBlock[] }
    | { kind: 'tool_run'; messageIds: string[]; blocks: ToolUseBlock[] }
    | { kind: 'streaming'; message: Message };

export interface FlattenTurnBlocksOptions {
    /** 当前流式消息 uuid；命中的消息整体作为 streaming 项，不参与聚合 */
    streamingMessageId?: string | null;
}

export function flattenTurnBlocks(
    turn: Turn,
    opts?: FlattenTurnBlocksOptions,
): FlattenedTurnItem[] {
    const items: FlattenedTurnItem[] = [];
    let runBlocks: ToolUseBlock[] = [];
    let runMessageIds: string[] = [];

    const flushRun = () => {
        if (runBlocks.length === 0) return;
        items.push({ kind: 'tool_run', messageIds: runMessageIds, blocks: runBlocks });
        runBlocks = [];
        runMessageIds = [];
    };
    const appendToRun = (messageId: string, block: ToolUseBlock) => {
        // 消息按序处理，同一 uuid 只会连续出现，尾比较即可 O(1) 去重
        if (runMessageIds[runMessageIds.length - 1] !== messageId) {
            runMessageIds.push(messageId);
        }
        runBlocks.push(block);
    };

    for (const message of turn.messages) {
        // 流式消息：豁免聚合，整体作为 streaming 项
        if (opts?.streamingMessageId != null && message.uuid === opts.streamingMessageId) {
            flushRun();
            items.push({ kind: 'streaming', message });
            continue;
        }

        if (message.type === 'user') {
            const hasRenderableBlock = message.content.some(
                block => block.type === 'text' || block.type === 'image',
            );
            // 纯 tool_result 载体：透明跳过（现状即渲染 null），不阻断合并
            if (!hasRenderableBlock) continue;
            // 指令 / steering user 消息：阻断合并，原位渲染
            flushRun();
            items.push({ kind: 'message', message });
            continue;
        }

        if (message.type === 'assistant') {
            // 异常兜底：非流式空消息原样渲染（与摊平前一致）
            if (message.content.length === 0) {
                flushRun();
                items.push({ kind: 'message', message });
                continue;
            }
            // 按块序切分：tool_use 并入当前段；非 tool_use 段产出 blocks 项并阻断合并
            let segment: ContentBlock[] = [];
            const flushSegment = () => {
                if (segment.length === 0) return;
                flushRun();
                items.push({ kind: 'blocks', message, blocks: segment });
                segment = [];
            };
            for (const block of message.content) {
                if (block.type === 'tool_use') {
                    flushSegment();
                    appendToRun(message.uuid, block);
                } else {
                    segment.push(block);
                }
            }
            flushSegment();
            continue;
        }

        // system / visualization / attachment / grouped_tool_use / collapsed_read_search
        flushRun();
        items.push({ kind: 'message', message });
    }
    flushRun();
    return items;
}
